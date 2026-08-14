import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import {
  assertCapabilityMatrix,
  loadCapabilityEvidenceMatrix,
} from "../dist/assets/capability-matrix.js";

const canonicalUrl = new URL(
  "../../config/capability-matrix/g520-stack.json",
  import.meta.url,
);
const bundledUrl = new URL(
  "../dist/capability-matrix/g520-stack.json",
  import.meta.url,
);

test("web artifact bundles the canonical matrix without rewriting it", async () => {
  const [canonical, bundled] = await Promise.all([
    readFile(canonicalUrl, "utf8"),
    readFile(bundledUrl, "utf8"),
  ]);

  assert.equal(bundled, canonical);
  const value = JSON.parse(bundled);
  assertCapabilityMatrix(value);
});

test("runtime decoder rejects duplicate capability rows", async () => {
  const value = await loadCanonical();
  value.rows.push({ ...value.rows[0] });

  assert.throws(() => assertCapabilityMatrix(value), /Duplicate capability row/);
});

test("runtime decoder rejects unknown fields like the Kotlin loader", async () => {
  const value = await loadCanonical();
  value.unexpected = true;

  assert.throws(() => assertCapabilityMatrix(value), /missing or unknown fields/);
});

test("runtime decoder rejects a missing target-stack capability", async () => {
  const value = await loadCanonical();
  value.rows = value.rows.filter((row) => row.id !== "takeoff_actuation");

  assert.throws(() => assertCapabilityMatrix(value), /inventory does not match/);
});

test("runtime decoder locks matrix identity to the G520 stack", async () => {
  const value = await loadCanonical();
  value.targetStack.host = "Pixel 8 Pro";

  assert.throws(() => assertCapabilityMatrix(value), /targetStack.host is unexpected/);
});

test("runtime decoder rejects MOCK evidence promotion", async () => {
  const value = await loadCanonical();
  const record = evidenceRecord({ environment: "MOCK", outcome: "PASS" });
  value.evidenceRecords = [record];
  const row = value.rows.find((candidate) => candidate.id === "takeoff_actuation");
  row.status = "CONFIRMED";
  row.evidenceRefs = [record.id];

  assert.throws(() => assertCapabilityMatrix(value), /first-hand G520 evidence/);
});

test("runtime decoder rejects orphan evidence", async () => {
  const value = await loadCanonical();
  value.evidenceRecords = [evidenceRecord({ environment: "MOCK", outcome: "FAIL" })];

  assert.throws(() => assertCapabilityMatrix(value), /orphaned/);
});

test("runtime decoder rejects raw-evidence path traversal", async () => {
  const value = await loadCanonical();
  const record = evidenceRecord({ environment: "MOCK", outcome: "FAIL" });
  record.rawLogLocation = record.rawLogLocation.replace("/raw/", "/raw/../");
  value.evidenceRecords = [record];

  assert.throws(() => assertCapabilityMatrix(value), /raw evidence reference/);
});

test("loader deep-freezes the validated evidence snapshot", async () => {
  const value = await loadCanonical();
  const matrix = await loadCapabilityEvidenceMatrix(async () =>
    new Response(JSON.stringify(value), { status: 200 }),
  );

  assert.ok(Object.isFrozen(matrix));
  assert.ok(Object.isFrozen(matrix.rows));
  assert.ok(Object.isFrozen(matrix.rows[0].trackingIssues));
  assert.throws(() => matrix.rows.push({}), TypeError);
});

async function loadCanonical() {
  return JSON.parse(await readFile(canonicalUrl, "utf8"));
}

function evidenceRecord({ environment, outcome }) {
  return {
    id: "web-test-evidence",
    recordKind: "EVIDENCE",
    environment,
    capturedAt: "2026-08-15T01:02:03Z",
    commit: "abcdef0123456789abcdef0123456789abcdef01",
    operator: "web-test-operator",
    runtimeProfile: "web-test-profile",
    deviceSetId:
      environment === "G520_HARDWARE"
        ? "device-set:6f0b65f0-48b3-4e16-970a-a2358572f8b1"
        : null,
    rawLogLocation:
      "evidence://bundle/6f0b65f0-48b3-4e16-970a-a2358572f8b1/raw/session.jsonl",
    trackingIssue: 9,
    outcome,
    summary: "Synthetic web decoder test evidence.",
  };
}
