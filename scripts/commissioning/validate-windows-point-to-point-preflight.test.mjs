import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdtemp, readFile, readdir, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import { validateWindowsPointToPointPreflight } from "./validate-windows-point-to-point-preflight.mjs";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const fixturesDirectory = path.join(scriptDirectory, "fixtures");
const casesDirectory = path.join(fixturesDirectory, "cases");
const profilePath = path.join(fixturesDirectory, "profile.json");
const passSnapshotPath = path.join(fixturesDirectory, "snapshot-pass.json");
const validatorPath = path.join(scriptDirectory, "validate-windows-point-to-point-preflight.mjs");
const collectorPath = path.join(scriptDirectory, "collect-windows-point-to-point-snapshot.ps1");
const routePolicyTestPath = path.join(scriptDirectory, "windows-point-to-point-route-policy.test.ps1");
const canonicalTemplatePath = path.resolve(
  scriptDirectory,
  "../../config/commissioning/g520-point-to-point-v1.template.json",
);

const profileBytes = await readFile(profilePath);
const profile = JSON.parse(profileBytes.toString("utf8"));
const passSnapshot = JSON.parse(await readFile(passSnapshotPath, "utf8"));
const profileSha256 = createHash("sha256").update(profileBytes).digest("hex");

function isPlainObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function deepMerge(base, patch) {
  if (!isPlainObject(patch)) return structuredClone(patch);
  const merged = isPlainObject(base) ? structuredClone(base) : {};
  for (const [key, value] of Object.entries(patch)) {
    merged[key] = isPlainObject(value) ? deepMerge(merged[key], value) : structuredClone(value);
  }
  return merged;
}

function validate(
  profileValue,
  snapshotValue,
  nowEpochMillis = Date.parse(snapshotValue.collector?.capturedAtUtc ?? passSnapshot.collector.capturedAtUtc),
) {
  return validateWindowsPointToPointPreflight(profileValue, snapshotValue, { profileSha256, nowEpochMillis });
}

test("pass fixture accepts only the frozen /30 point-to-point candidate", () => {
  const result = validate(profile, passSnapshot);
  assert.equal(result.valid, true);
  assert.deepEqual(result.issues, []);
  assert.equal(result.profileId, "g520-p2p-windows-v1");
  assert.equal(Object.values(result.checks).every((status) => status === "PASS"), true);
});

test("repo canonical profile stays an unbound template that cannot pass preflight", async () => {
  const templateBytes = await readFile(canonicalTemplatePath);
  const template = JSON.parse(templateBytes.toString("utf8"));
  const templateSha256 = createHash("sha256").update(templateBytes).digest("hex");
  const result = validateWindowsPointToPointPreflight(template, passSnapshot, {
    profileSha256: templateSha256,
    nowEpochMillis: Date.parse(passSnapshot.collector.capturedAtUtc),
  });

  assert.equal(template.status, "UNVERIFIED_TEMPLATE");
  assert.equal(template.operator.adapterGuid, null);
  assert.equal(template.operator.pnpDeviceIdSha256, null);
  assert.equal(result.valid, false);
  assert.ok(result.issues.some((issue) => issue.code === "PROFILE_NOT_COMMISSIONING_CANDIDATE"));
});

const caseFiles = (await readdir(casesDirectory)).filter((name) => name.endsWith(".json")).sort();
for (const caseFile of caseFiles) {
  test(`fail-closed fixture: ${caseFile}`, async () => {
    const fixture = JSON.parse(await readFile(path.join(casesDirectory, caseFile), "utf8"));
    const patchedProfile = deepMerge(profile, fixture.profilePatch ?? {});
    const patchedSnapshot = deepMerge(passSnapshot, fixture.snapshotPatch ?? {});
    const result = validate(patchedProfile, patchedSnapshot);
    const actualCodes = result.issues.map((issue) => issue.code).sort();
    const expectedCodes = [...fixture.expectedIssueCodes].sort();
    assert.equal(result.valid, false);
    assert.deepEqual(actualCodes, expectedCodes);
  });
}

test("CLI accepts a fresh profile-bound snapshot and is deterministic", async (t) => {
  const temporaryDirectory = await mkdtemp(path.join(os.tmpdir(), "commissioning-fresh-"));
  t.after(() => rm(temporaryDirectory, { recursive: true, force: true }));
  const freshSnapshotPath = path.join(temporaryDirectory, "snapshot.json");
  const freshSnapshot = structuredClone(passSnapshot);
  freshSnapshot.collector.capturedAtUtc = new Date().toISOString();
  await writeFile(freshSnapshotPath, `${JSON.stringify(freshSnapshot)}\n`, "utf8");
  const args = [validatorPath, profilePath, freshSnapshotPath];
  const first = spawnSync(process.execPath, args, { encoding: "utf8" });
  const second = spawnSync(process.execPath, args, { encoding: "utf8" });
  assert.equal(first.status, 0, first.stderr);
  assert.equal(second.status, 0, second.stderr);
  assert.equal(first.stdout, second.stdout);
  assert.equal(JSON.parse(first.stdout).valid, true);
  assert.ok(Buffer.byteLength(first.stdout, "utf8") < 16 * 1024);
});

test("capture timestamp must be fresh and cannot be materially in the future", () => {
  const capturedAt = Date.parse(passSnapshot.collector.capturedAtUtc);
  const stale = validate(profile, passSnapshot, capturedAt + (5 * 60 * 1000) + 1);
  assert.deepEqual(stale.issues.map((issue) => issue.code), ["CAPTURE_STALE"]);

  const future = validate(profile, passSnapshot, capturedAt - (30 * 1000) - 1);
  assert.deepEqual(future.issues.map((issue) => issue.code), ["CAPTURE_FROM_FUTURE"]);
});

test("CLI exits nonzero for an invalid snapshot without echoing observed values", async (t) => {
  const temporaryDirectory = await mkdtemp(path.join(os.tmpdir(), "commissioning-preflight-"));
  t.after(() => rm(temporaryDirectory, { recursive: true, force: true }));
  const invalidSnapshotPath = path.join(temporaryDirectory, "snapshot.json");
  const invalidSnapshot = deepMerge(passSnapshot, {
    unexpected: "do-not-echo-this-value",
    ics: { enabledConnectionCount: 1 },
  });
  invalidSnapshot.collector.capturedAtUtc = new Date().toISOString();
  await writeFile(invalidSnapshotPath, `${JSON.stringify(invalidSnapshot)}\n`, "utf8");

  const run = spawnSync(process.execPath, [validatorPath, profilePath, invalidSnapshotPath], { encoding: "utf8" });
  assert.equal(run.status, 1, run.stderr);
  const result = JSON.parse(run.stdout);
  assert.equal(result.valid, false);
  assert.deepEqual(result.issues.map((issue) => issue.code).sort(), ["ICS_ENABLED", "UNKNOWN_FIELD"]);
  assert.doesNotMatch(run.stdout, /do-not-echo-this-value/u);
  assert.ok(Buffer.byteLength(run.stdout, "utf8") < 16 * 1024);
});

test("CLI fails closed for oversized and malformed inputs", async (t) => {
  const temporaryDirectory = await mkdtemp(path.join(os.tmpdir(), "commissioning-input-"));
  t.after(() => rm(temporaryDirectory, { recursive: true, force: true }));
  const oversizedPath = path.join(temporaryDirectory, "oversized.json");
  const malformedPath = path.join(temporaryDirectory, "malformed.json");
  await writeFile(oversizedPath, "x".repeat((64 * 1024) + 1), "utf8");
  await writeFile(malformedPath, "{not-json-and-not-secret}", "utf8");

  const oversized = spawnSync(process.execPath, [validatorPath, oversizedPath, passSnapshotPath], { encoding: "utf8" });
  assert.equal(oversized.status, 1);
  assert.deepEqual(JSON.parse(oversized.stdout).issues.map((issue) => issue.code), ["INPUT_SIZE_OR_TYPE"]);

  const malformed = spawnSync(process.execPath, [validatorPath, malformedPath, passSnapshotPath], { encoding: "utf8" });
  assert.equal(malformed.status, 1);
  assert.deepEqual(JSON.parse(malformed.stdout).issues.map((issue) => issue.code), ["JSON_INVALID"]);
  assert.doesNotMatch(malformed.stdout, /not-secret/u);
});

test("PowerShell collector is read-only and does not select by localized adapter name", async () => {
  const source = await readFile(collectorPath, "utf8");
  assert.match(source, /\[Guid\]\s+\$AdapterGuid/u);
  assert.match(source, /\[int\]\s+\$IfIndex/u);
  assert.match(source, /Find-NetRoute\s+-RemoteIPAddress/u);
  assert.match(source, /PSObject\.Properties\["IPAddress"\]/u);
  assert.match(source, /PSObject\.Properties\["DestinationPrefix"\]/u);
  assert.match(source, /HNetCfg\.HNetShare\.1/u);
  assert.match(source, /netsh\.exe bridge show adapter/u);
  assert.match(source, /Get-NetIPInterface\s+-PolicyStore ActiveStore/u);
  assert.match(source, /profileSha256/u);
  assert.match(source, /hostIdentitySha256/u);
  assert.doesNotMatch(source, /\b(?:Set|New|Remove|Enable|Disable|Rename|Restart)-Net(?:Adapter|IPAddress|IPInterface|Route|ConnectionSharing)\b/iu);
  assert.doesNotMatch(source, /-Name\s+["'](?:Ethernet|乙太網路)["']/iu);
});

test("PowerShell collector emits a bounded partial snapshot on an unsupported or unmatched host", (t) => {
  const probe = spawnSync("pwsh", ["-NoProfile", "-Command", "$PSVersionTable.PSVersion.ToString()"], {
    encoding: "utf8",
  });
  if (probe.error?.code === "ENOENT") {
    t.skip("pwsh is not installed on this host");
    return;
  }
  assert.equal(probe.status, 0, probe.stderr);
  const run = spawnSync(
    "pwsh",
    [
      "-NoProfile",
      "-File",
      collectorPath,
      "-ProfilePath",
      profilePath,
      "-AdapterGuid",
      profile.operator.adapterGuid,
      "-IfIndex",
      "2147483647",
      "-G520IPv4",
      profile.g520.ipv4,
    ],
    { encoding: "utf8" },
  );
  assert.equal(run.status, 0, run.stderr);
  assert.ok(Buffer.byteLength(run.stdout, "utf8") < 16 * 1024);
  const snapshot = JSON.parse(run.stdout);
  assert.equal(snapshot.captureStatus, "PARTIAL");
  for (const query of Object.values(snapshot.queries)) {
    assert.equal(typeof query.complete, "boolean");
    assert.match(query.status, /^(?:OK|ERROR|NOT_RUN)$/u);
  }
  const result = validate(profile, snapshot);
  assert.equal(result.valid, false);
  assert.equal(result.snapshotStatus, "PARTIAL");
});

test("PowerShell route policy rejects broad and noncanonical local-prefix disguises", (t) => {
  const probe = spawnSync("pwsh", ["-NoProfile", "-Command", "$PSVersionTable.PSVersion.ToString()"], {
    encoding: "utf8",
  });
  if (probe.error?.code === "ENOENT") {
    t.skip("pwsh is not installed on this host");
    return;
  }
  assert.equal(probe.status, 0, probe.stderr);
  const run = spawnSync("pwsh", ["-NoProfile", "-File", routePolicyTestPath], { encoding: "utf8" });
  assert.equal(run.status, 0, run.stderr);
  assert.equal(run.stdout.trim(), "PowerShell route policy tests PASS");
});
