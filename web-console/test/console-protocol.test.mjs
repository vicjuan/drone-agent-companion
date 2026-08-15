import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readdir, readFile } from "node:fs/promises";
import test from "node:test";

import {
  CLIENT_CONSOLE_MESSAGE_TYPES,
  CONSOLE_PROTOCOL_VERSION,
  SERVER_CONSOLE_MESSAGE_TYPES,
  assertConsoleMessage,
  decodeClientConsoleMessage,
  decodeConsoleMessage,
  decodeServerConsoleMessage,
  encodeClientConsoleMessage,
  encodeConsoleMessage,
  encodeServerConsoleMessage,
} from "../dist/assets/console-protocol.js";

const contractDirectory = new URL(
  "../../contracts/console-protocol/v1/",
  import.meta.url,
);
const fixtureDirectory = new URL(
  "fixtures/",
  contractDirectory,
);
const expectedTypes = [
  "client_hello",
  "server_hello",
  "runtime_state",
  "telemetry",
  "capability_snapshot",
  "health",
  "lease_acquire",
  "lease_renew",
  "lease_release",
  "lease_state",
  "command_request",
  "command_ack",
  "command_result",
  "control_frame",
  "control_neutral",
  "control_ack",
  "safety_event",
  "protocol_error",
];
const expectedProtocolErrorCodes = [
  "malformed_json",
  "invalid_envelope",
  "unsupported_protocol_version",
  "unknown_message_type",
  "wrong_message_direction",
  "invalid_payload",
  "handshake_required",
  "unexpected_message",
  "server_unavailable",
];
const expectedLimits = {
  maxWireMessageUtf8Bytes: 65_536,
  maxJsonNestingDepth: 16,
  maxSafeInteger: Number.MAX_SAFE_INTEGER,
  identifierMaxLength: 64,
  nameMaxLength: 128,
  credentialMaxLength: 4_096,
  textMaxLength: 1_024,
  negotiationMaxItems: 16,
  capabilityMaxRows: 256,
  leaseTtlMs: { minimum: 500, maximum: 30_000 },
  commandTtlMs: { minimum: 100, maximum: 30_000 },
  controlTtlMs: { minimum: 50, maximum: 1_000 },
};

const [manifestText, canonicalDigestText] = await Promise.all([
  readFile(new URL("manifest.json", contractDirectory), "utf8"),
  readFile(new URL("CANONICAL.sha256", contractDirectory), "utf8"),
]);
const manifest = JSON.parse(manifestText);
const fixtureEntries = manifest.fixtures.toSorted((left, right) =>
  left.path.localeCompare(right.path),
);
const fixtureFiles = (await readdir(fixtureDirectory))
  .filter((name) => name.endsWith(".json"))
  .sort();
const fixtureTexts = await Promise.all(
  fixtureEntries.map((entry) =>
    readFile(new URL(entry.path, contractDirectory), "utf8"),
  ),
);
const fixtures = fixtureTexts.map((text) => JSON.parse(text));

test("manifest and digests lock every shared Kotlin/TypeScript fixture", () => {
  assert.equal(manifest.schemaVersion, 1);
  assert.equal(manifest.protocolVersion, CONSOLE_PROTOCOL_VERSION);
  assert.deepEqual(manifest.envelopeFields, [
    "protocolVersion",
    "messageId",
    "type",
    "payload",
  ]);
  assert.deepEqual(manifest.limits, expectedLimits);
  assert.deepEqual(
    manifest.clientMessageTypes,
    [...CLIENT_CONSOLE_MESSAGE_TYPES],
  );
  assert.deepEqual(
    manifest.serverMessageTypes,
    [...SERVER_CONSOLE_MESSAGE_TYPES],
  );
  assert.deepEqual(manifest.protocolErrorCodes, expectedProtocolErrorCodes);
  assert.deepEqual(
    fixtureFiles,
    fixtureEntries.map((entry) => entry.path.replace(/^fixtures\//, "")),
  );

  const canonicalParts = canonicalDigestText.trim().split(/\s+/);
  assert.deepEqual(canonicalParts.slice(1), ["manifest.json"]);
  assert.equal(sha256(manifestText), canonicalParts[0]);

  assert.deepEqual(
    [...new Set(fixtures.map((fixture) => fixture.type))].sort(),
    expectedTypes.toSorted(),
  );

  for (const [index, text] of fixtureTexts.entries()) {
    const entry = fixtureEntries[index];
    assert.equal(sha256(text), entry.sha256);
    assert.equal(fixtures[index].type, entry.type);

    const decoded = decodeConsoleMessage(text);
    assert.deepEqual(JSON.parse(encodeConsoleMessage(decoded)), fixtures[index]);
    assert.deepEqual(
      decodeConsoleMessage(encodeConsoleMessage(decoded)),
      decoded,
    );
    if (entry.direction === "client_to_server") {
      assert.deepEqual(decodeClientConsoleMessage(text), decoded);
    } else {
      assert.equal(entry.direction, "server_to_client");
      assert.deepEqual(decodeServerConsoleMessage(text), decoded);
    }
  }
});

test("decoder returns a recursively frozen protocol snapshot", () => {
  for (const text of fixtureTexts) {
    assertDeeplyFrozen(decodeConsoleMessage(text));
  }

  const capability = decodeConsoleMessage(
    JSON.stringify(fixtureOf("capability_snapshot")),
  );
  assert.throws(() => capability.payload.rows.push({}), TypeError);

  const frame = decodeConsoleMessage(JSON.stringify(fixtureOf("control_frame")));
  assert.throws(() => {
    frame.payload.forward = 1;
  }, TypeError);
});

test("decoder rejects unknown envelope and payload fields", () => {
  const fixture = fixtureOf("command_request");

  assertRejected({ ...fixture, unexpected: true }, /unknown|field/i);
  assertRejected(
    { ...fixture, payload: { ...fixture.payload, authority: {} } },
    /unknown|field/i,
  );

  const ack = structuredClone(fixtureOf("command_ack"));
  delete ack.payload.reason;
  assertRejected(ack, /missing|field/i);
});

test("directional codecs reject otherwise-valid messages from the wrong peer", () => {
  const clientText = JSON.stringify(fixtureOf("client_hello"));
  const serverText = JSON.stringify(fixtureOf("server_hello"));

  assert.equal(decodeClientConsoleMessage(clientText).type, "client_hello");
  assert.equal(decodeServerConsoleMessage(serverText).type, "server_hello");
  assert.throws(() => decodeClientConsoleMessage(serverText), /client/i);
  assert.throws(() => decodeServerConsoleMessage(clientText), /server/i);
  assert.throws(
    () => encodeClientConsoleMessage(fixtureOf("server_hello")),
    /client/i,
  );
  assert.throws(
    () => encodeServerConsoleMessage(fixtureOf("client_hello")),
    /server/i,
  );
});

test("decoder rejects unsupported versions, message types, and mismatched payloads", () => {
  const command = fixtureOf("command_request");

  assertRejected({ ...command, protocolVersion: "2.0" }, /version/i);
  assertRejected({ ...command, type: "future_message" }, /type/i);
  assertRejected(
    { ...command, payload: fixtureOf("telemetry").payload },
    /payload|field|command/i,
  );
});

test("malformed JSON errors do not reflect rejected credentials", () => {
  const secret = "do-not-reflect-this-credential";
  assert.throws(
    () => decodeConsoleMessage(`{"authentication":"${secret}"`),
    (error) =>
      error instanceof Error &&
      /malformed/i.test(error.message) &&
      !error.message.includes(secret),
  );
});

test("decoder applies UTF-8 byte and JSON nesting limits before parsing", () => {
  const encoder = new TextEncoder();
  const base = JSON.stringify(fixtureOf("health"));
  const exactLimit =
    base + " ".repeat(expectedLimits.maxWireMessageUtf8Bytes - encoder.encode(base).byteLength);
  assert.equal(
    encoder.encode(exactLimit).byteLength,
    expectedLimits.maxWireMessageUtf8Bytes,
  );
  assert.equal(decodeConsoleMessage(exactLimit).type, "health");
  assert.throws(
    () => decodeConsoleMessage(`${exactLimit} `),
    /UTF-8|wire message/i,
  );

  const multibyteOverflow = `{"x":"${"界".repeat(21_844)}"}`;
  assert.ok(multibyteOverflow.length < expectedLimits.maxWireMessageUtf8Bytes);
  assert.ok(
    encoder.encode(multibyteOverflow).byteLength >
      expectedLimits.maxWireMessageUtf8Bytes,
  );
  assert.throws(
    () => decodeConsoleMessage(multibyteOverflow),
    /UTF-8|wire message/i,
  );

  const deeplyNested = `${"[".repeat(5_000)}0${"]".repeat(5_000)}`;
  assert.ok(encoder.encode(deeplyNested).byteLength < 65_536);
  assert.throws(
    () => decodeConsoleMessage(deeplyNested),
    /nesting/i,
  );
});

test("generic and server encoders reject valid shapes that exceed the wire limit", () => {
  const capability = fixtureOf("capability_snapshot");
  const oversized = {
    ...capability,
    payload: {
      ...capability.payload,
      rows: Array.from({ length: 100 }, (_, index) => ({
        id: `oversized_capability_${index}`,
        status: "UNKNOWN",
        assessment: "x".repeat(1_024),
      })),
    },
  };
  const wire = JSON.stringify(oversized);

  assert.doesNotThrow(() => assertConsoleMessage(oversized));
  assert.ok(
    new TextEncoder().encode(wire).byteLength >
      expectedLimits.maxWireMessageUtf8Bytes,
  );
  assert.throws(() => encodeConsoleMessage(oversized), /UTF-8|wire message/i);
  assert.throws(
    () => encodeServerConsoleMessage(oversized),
    /UTF-8|wire message/i,
  );
  assert.throws(() => decodeConsoleMessage(wire), /UTF-8|wire message/i);
});

test("nesting preflight ignores brackets, braces, quotes, and escapes in strings", () => {
  const health = fixtureOf("health");
  const detail = `${"[{".repeat(100)}quoted \\" value${"]}".repeat(100)}`;
  const message = {
    ...health,
    payload: { ...health.payload, detail },
  };

  assertAccepted(message);
  assert.equal(
    decodeConsoleMessage(JSON.stringify(message)).payload.detail,
    detail,
  );
});

test("protocol errors accept only the stable v1 error-code vocabulary", () => {
  const fixture = fixtureOf("protocol_error");
  for (const code of expectedProtocolErrorCodes) {
    assertAccepted({
      ...fixture,
      payload: { ...fixture.payload, code },
    });
  }
  assertRejected(
    {
      ...fixture,
      payload: { ...fixture.payload, code: "internal_exception" },
    },
    /code|allowed/i,
  );
});

test("safety events report neutral execution outcome fail-honestly", () => {
  const safety = fixtureOf("safety_event");
  assertAccepted({
    ...safety,
    payload: {
      ...safety.payload,
      action: "neutralize",
      outcome: "succeeded",
      detail: null,
    },
  });
  assertRejected(
    {
      ...safety,
      payload: { ...safety.payload, action: "neutralized" },
    },
    /action/i,
  );
  assertRejected(
    {
      ...safety,
      payload: { ...safety.payload, outcome: "failed", detail: null },
    },
    /detail|fail/i,
  );
  assertRejected(
    {
      ...safety,
      payload: { ...safety.payload, outcome: "failed", detail: " " },
    },
    /detail|blank/i,
  );
});

test("command request validates its discriminated action", () => {
  const command = fixtureOf("command_request");
  for (const action of ["takeoff", "landing", "return_to_home"]) {
    assertAccepted({ ...command, payload: { ...command.payload, action } });
  }
  for (const action of ["launch", "virtual_stick"]) {
    assertRejected(
      {
        ...command,
        payload: { ...command.payload, action },
      },
      /action|command/i,
    );
  }
  assertRejected(
    {
      ...command,
      payload: { ...command.payload, altitudeM: 10 },
    },
    /unknown|field/i,
  );
});

test("hello negotiation is bounded and internally consistent", () => {
  const client = fixtureOf("client_hello");
  const server = fixtureOf("server_hello");

  assertRejected(
    {
      ...client,
      payload: { ...client.payload, supportedProtocolVersions: [] },
    },
    /supported|empty|include/i,
  );
  assertRejected(
    {
      ...client,
      payload: {
        ...client.payload,
        supportedProtocolVersions: ["1.0", "1.0"],
      },
    },
    /duplicate/i,
  );
  assertRejected(
    {
      ...client,
      payload: {
        ...client.payload,
        authentication: { scheme: " ", credential: "secret" },
      },
    },
    /scheme|blank/i,
  );
  assertRejected(
    {
      ...client,
      payload: {
        ...client.payload,
        authentication: {
          scheme: "bearer",
          credential: "x".repeat(4_097),
        },
      },
    },
    /credential|limit/i,
  );
  assertRejected(
    {
      ...server,
      payload: {
        ...server.payload,
        authenticationRequired: true,
        acceptedAuthenticationSchemes: [],
      },
    },
    /authentication scheme/i,
  );
  assertRejected(
    {
      ...server,
      payload: {
        ...server.payload,
        acceptedAuthenticationSchemes: Array.from(
          { length: 17 },
          (_, index) => `scheme-${index}`,
        ),
      },
    },
    /item limit/i,
  );
});

test("identifiers, names, and human-readable text have fail-closed bounds", () => {
  const client = fixtureOf("client_hello");
  assertRejected({ ...client, messageId: "x".repeat(65) }, /messageId|identifier/i);
  assertRejected(
    { ...client, payload: { ...client.payload, clientName: "x".repeat(129) } },
    /clientName|limit/i,
  );

  const health = fixtureOf("health");
  assertRejected(
    { ...health, payload: { ...health.payload, detail: "x".repeat(1_025) } },
    /detail|limit/i,
  );

  const capability = fixtureOf("capability_snapshot");
  assertRejected(
    {
      ...capability,
      payload: { ...capability.payload, matrixId: "another-stack" },
    },
    /matrixId|stack/i,
  );
  assertRejected(
    {
      ...capability,
      payload: {
        ...capability.payload,
        rows: [
          {
            ...capability.payload.rows[0],
            assessment: " ",
          },
          ...capability.payload.rows.slice(1),
        ],
      },
    },
    /assessment|blank/i,
  );
});

test("lease state enforces its nullability state machine", () => {
  const fixture = fixtureOf("lease_state");
  const states = [
    {
      state: "available",
      leaseId: null,
      holderSessionId: null,
      expiresInMs: null,
      reason: null,
    },
    {
      state: "held",
      leaseId: "lease-held",
      holderSessionId: "session-holder",
      expiresInMs: 5_000,
      reason: null,
    },
    {
      state: "denied",
      leaseId: null,
      holderSessionId: null,
      expiresInMs: null,
      reason: "lease_already_held",
    },
    {
      state: "released",
      leaseId: "lease-released",
      holderSessionId: null,
      expiresInMs: null,
      reason: null,
    },
    {
      state: "expired",
      leaseId: "lease-expired",
      holderSessionId: null,
      expiresInMs: null,
      reason: "lease_ttl_expired",
    },
  ];

  for (const payload of states) {
    assertAccepted({
      ...fixture,
      payload: { requestMessageId: null, ...payload },
    });
  }

  for (const payload of [
    { ...states[0], leaseId: "unexpected-lease" },
    { ...states[1], holderSessionId: null },
    { ...states[2], reason: null },
    { ...states[3], expiresInMs: 1 },
    { ...states[4], reason: null },
  ]) {
    assertRejected(
      { ...fixture, payload: { requestMessageId: null, ...payload } },
      /lease_state|lease/i,
    );
  }
});

test("ack and result reason fields agree with their outcomes", () => {
  const commandAck = fixtureOf("command_ack");
  assertRejected(
    {
      ...commandAck,
      payload: { ...commandAck.payload, decision: "accepted", reason: "no" },
    },
    /reason/i,
  );
  assertRejected(
    {
      ...commandAck,
      payload: { ...commandAck.payload, decision: "rejected", reason: null },
    },
    /reason/i,
  );

  const commandResult = fixtureOf("command_result");
  assertRejected(
    {
      ...commandResult,
      payload: {
        ...commandResult.payload,
        status: "succeeded",
        reason: "failure_reason",
      },
    },
    /reason/i,
  );
  assertRejected(
    {
      ...commandResult,
      payload: { ...commandResult.payload, status: "failed", reason: null },
    },
    /reason/i,
  );

  const controlAck = fixtureOf("control_ack");
  assertRejected(
    {
      ...controlAck,
      payload: { ...controlAck.payload, status: "applied", reason: "stale" },
    },
    /reason/i,
  );
  assertRejected(
    {
      ...controlAck,
      payload: { ...controlAck.payload, status: "stale", reason: null },
    },
    /reason/i,
  );
});

test("control frame rejects non-finite or out-of-range axes", () => {
  const frame = fixtureOf("control_frame");

  for (const axis of ["forward", "right", "up", "yaw"]) {
    for (const value of [-1.000_001, 1.000_001, Number.NaN, Infinity]) {
      assertRejected(
        { ...frame, payload: { ...frame.payload, [axis]: value } },
        new RegExp(axis, "i"),
      );
    }
  }
});

test("control frame rejects invalid TTL values", () => {
  const frame = fixtureOf("control_frame");

  for (const ttlMs of [49, 1_001, 50.5, Number.NaN, Infinity]) {
    assertRejected(
      { ...frame, payload: { ...frame.payload, ttlMs } },
      /ttl/i,
    );
  }
});

test("telemetry and request numbers are finite and within protocol bounds", () => {
  const telemetry = fixtureOf("telemetry");
  for (const [field, value] of [
    ["batteryPercent", 101],
    ["latitude", -90.01],
    ["longitude", 180.01],
  ]) {
    assertRejected(
      { ...telemetry, payload: { ...telemetry.payload, [field]: value } },
      new RegExp(field, "i"),
    );
  }
  for (const [field, value] of [
    ["altitudeM", Infinity],
    ["gimbalPitchDeg", Number.NaN],
  ]) {
    const invalid = {
      ...telemetry,
      payload: { ...telemetry.payload, [field]: value },
    };
    assert.throws(
      () => assertConsoleMessage(invalid),
      new RegExp(field, "i"),
    );
    assert.throws(
      () => encodeConsoleMessage(invalid),
      new RegExp(field, "i"),
    );

    const finiteSentinel = 123.456_789;
    const overflowWire = JSON.stringify({
      ...telemetry,
      payload: { ...telemetry.payload, [field]: finiteSentinel },
    }).replace(String(finiteSentinel), "1e400");
    assert.throws(
      () => decodeConsoleMessage(overflowWire),
      new RegExp(field, "i"),
    );
  }

  const lease = fixtureOf("lease_acquire");
  for (const requestedTtlMs of [499, 30_001, 500.5]) {
    assertRejected(
      { ...lease, payload: { ...lease.payload, requestedTtlMs } },
      /ttl/i,
    );
  }

  const command = fixtureOf("command_request");
  for (const ttlMs of [99, 30_001, 100.5]) {
    assertRejected(
      { ...command, payload: { ...command.payload, ttlMs } },
      /ttl/i,
    );
  }
});

test("integral fields accept JSON decimal/exponent spellings but not leading zeroes", () => {
  const lease = fixtureOf("lease_acquire");
  const canonical = JSON.stringify({
    ...lease,
    payload: { ...lease.payload, requestedTtlMs: 500 },
  });
  const marker = '"requestedTtlMs":500';
  assert.ok(canonical.includes(marker));

  for (const literal of ["500.0", "5e2"]) {
    const decoded = decodeConsoleMessage(
      canonical.replace(marker, `"requestedTtlMs":${literal}`),
    );
    assert.equal(decoded.payload.requestedTtlMs, 500);
  }
  assert.throws(
    () =>
      decodeConsoleMessage(
        canonical.replace(marker, '"requestedTtlMs":0500'),
      ),
    /malformed/i,
  );
  assert.throws(
    () =>
      decodeConsoleMessage(
        canonical.replace(marker, '"requestedTtlMs":+500'),
      ),
    /malformed/i,
  );

  const frame = fixtureOf("control_frame");
  const frameWire = JSON.stringify({
    ...frame,
    payload: { ...frame.payload, forward: 0.5 },
  });
  const axisMarker = '"forward":0.5';
  assert.ok(frameWire.includes(axisMarker));
  for (const literal of [".5", "-.5", "+0.5"]) {
    assert.throws(
      () =>
        decodeConsoleMessage(
          frameWire.replace(axisMarker, `"forward":${literal}`),
        ),
      /malformed/i,
    );
  }
});

test("decoder checks integral fields before IEEE-754 precision can round them", () => {
  const lease = fixtureOf("lease_acquire");
  const leaseWire = JSON.stringify({
    ...lease,
    payload: { ...lease.payload, requestedTtlMs: 500 },
  });
  const ttlMarker = '"requestedTtlMs":500';
  assert.ok(leaseWire.includes(ttlMarker));

  for (const literal of [
    "500.00000000000000001",
    "5.000000000000000001e2",
  ]) {
    assert.throws(
      () =>
        decodeConsoleMessage(
          leaseWire.replace(ttlMarker, `"requestedTtlMs":${literal}`),
        ),
      /integral/i,
    );
  }

  const frame = fixtureOf("control_frame");
  const frameWire = JSON.stringify({
    ...frame,
    payload: { ...frame.payload, inputSequence: Number.MAX_SAFE_INTEGER },
  });
  const sequenceMarker = `"inputSequence":${Number.MAX_SAFE_INTEGER}`;
  assert.ok(frameWire.includes(sequenceMarker));
  assert.throws(
    () =>
      decodeConsoleMessage(
        frameWire.replace(
          sequenceMarker,
          '"inputSequence":9007199254740991.1',
        ),
      ),
    /integral/i,
  );
});

test("control messages reject unsafe or non-positive input sequences", () => {
  for (const type of ["control_frame", "control_neutral", "control_ack"]) {
    const fixture = fixtureOf(type);
    for (const inputSequence of [0, -1, 1.5, Number.MAX_SAFE_INTEGER + 1, Infinity]) {
      assertRejected(
        { ...fixture, payload: { ...fixture.payload, inputSequence } },
        /sequence/i,
      );
    }
  }
});

function fixtureOf(type) {
  const fixture = fixtures.find((candidate) => candidate.type === type);
  assert.notEqual(fixture, undefined, `missing canonical fixture for ${type}`);
  return fixture;
}

function assertRejected(value, expectation) {
  assert.throws(() => assertConsoleMessage(value), expectation);
  assert.throws(
    () => decodeConsoleMessage(JSON.stringify(value)),
    expectation,
  );
  assert.throws(() => encodeConsoleMessage(value), expectation);
}

function assertAccepted(value) {
  assert.doesNotThrow(() => assertConsoleMessage(value));
  assert.deepEqual(decodeConsoleMessage(JSON.stringify(value)), value);
  assert.deepEqual(JSON.parse(encodeConsoleMessage(value)), value);
}

function assertDeeplyFrozen(value) {
  if (value === null || typeof value !== "object") {
    return;
  }
  assert.ok(Object.isFrozen(value));
  for (const child of Object.values(value)) {
    assertDeeplyFrozen(child);
  }
}

function sha256(text) {
  return createHash("sha256").update(text).digest("hex");
}
