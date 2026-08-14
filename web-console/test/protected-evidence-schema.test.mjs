import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
import test from "node:test";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, "../..");
const schema = JSON.parse(
  await readFile(
    resolve(repoRoot, "docs/evidence/protected-bundle-manifest.schema.json"),
    "utf8",
  ),
);
const template = JSON.parse(
  await readFile(
    resolve(repoRoot, "docs/evidence/protected-bundle-manifest.template.json"),
    "utf8",
  ),
);
const ajv = new Ajv2020({ allErrors: true, strict: true });
addFormats(ajv);
const validate = ajv.compile(schema);

function productionManifest() {
  return {
    schemaVersion: 1,
    recordKind: "PROTECTED_EVIDENCE_MANIFEST",
    bundleId: "6f0b65f0-48b3-4e16-970a-a2358572f8b1",
    deviceSetId: "device-set:3ed50972-63a8-4926-8549-65313296471f",
    targetStack: {
      aircraft: "DJI Mini 4 Pro",
      remoteController: "DJI RC-N3",
      host: "MediaTek Genio 520 (Android)",
    },
    capturedAt: "2026-08-15T01:02:03Z",
    testedCommit: "abcdef0123456789abcdef0123456789abcdef01",
    operator: "commissioning-operator",
    runtimeProfile: "g520-hardware-commissioning",
    hardwareSerials: {
      g520: "PRIVATE-G520-SERIAL",
      remoteController: "PRIVATE-RCN3-SERIAL",
      aircraft: "PRIVATE-AIRCRAFT-SERIAL",
    },
    files: [
      {
        path: "raw/session.jsonl",
        sha256: "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
        mediaType: "application/x-ndjson",
        byteLength: 2048,
      },
    ],
  };
}

test("protected evidence schema accepts a complete production manifest", () => {
  assert.equal(validate(productionManifest()), true, JSON.stringify(validate.errors));
});

test("changing only the template record kind cannot make it production evidence", () => {
  const disguisedTemplate = {
    ...template,
    recordKind: "PROTECTED_EVIDENCE_MANIFEST",
  };

  assert.equal(validate(disguisedTemplate), false);
  assert.ok(validate.errors.length > 0);
});

test("protected evidence file paths reject dot-segment traversal", () => {
  for (const path of ["raw/../other/secret", "raw/./session.jsonl"]) {
    const manifest = productionManifest();
    manifest.files[0].path = path;

    assert.equal(validate(manifest), false, `${path} must be rejected`);
    assert.ok(
      validate.errors.some((error) => error.instancePath === "/files/0/path"),
      JSON.stringify(validate.errors),
    );
  }
});
