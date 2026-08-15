import assert from "node:assert/strict";
import test from "node:test";

import {
  actuationReadiness,
  createInitialConsoleState,
  markCommandSent,
  markHandshaking,
  markRetryWaiting,
  ownsControlLease,
  reduceServerMessage,
} from "../dist/assets/console-state.js";

const envelope = (type, payload, messageId = `server-${type}`) => ({
  protocolVersion: "1.0",
  messageId,
  type,
  payload,
});

const hello = envelope("server_hello", {
  sessionId: "session-web-1",
  serverVersion: "test-server",
  selectedProtocolVersion: "1.0",
  authenticationRequired: false,
  acceptedAuthenticationSchemes: [],
});

const runtime = (overrides = {}) =>
  envelope("runtime_state", {
    adapter: "mock",
    aircraftConnection: "connected",
    actuationLock: "unlocked",
    operatingProfile: "localhost_development",
    ...overrides,
  });

const health = (status = "healthy") =>
  envelope("health", {
    status,
    uptimeMs: 1_000,
    detail: status === "healthy" ? null : "test degradation",
  });

const heldLease = (holderSessionId = "session-web-1") =>
  envelope("lease_state", {
    requestMessageId: "request-lease-1",
    state: "held",
    leaseId: "lease-web-1",
    holderSessionId,
    expiresInMs: 5_000,
    reason: null,
  });

function readyState() {
  let state = markHandshaking(createInitialConsoleState());
  for (const message of [hello, runtime(), health(), heldLease()]) {
    state = reduceServerMessage(state, message);
  }
  return state;
}

test("actuation stays fail-closed until health runtime connection lock and owned lease are all ready", () => {
  let state = markHandshaking(createInitialConsoleState());
  state = reduceServerMessage(state, hello);
  assert.equal(actuationReadiness(state).enabled, false);

  state = reduceServerMessage(state, runtime());
  state = reduceServerMessage(state, health());
  assert.equal(actuationReadiness(state).enabled, false);

  state = reduceServerMessage(state, heldLease());
  assert.equal(ownsControlLease(state), true);
  assert.deepEqual(actuationReadiness(state), {
    enabled: true,
    reason: "控制路徑已就緒",
    leaseId: "lease-web-1",
  });

  const locked = reduceServerMessage(state, runtime({ actuationLock: "locked" }));
  assert.equal(actuationReadiness(locked).enabled, false);
  assert.match(actuationReadiness(locked).reason, /lock/i);

  const otherHolder = reduceServerMessage(state, heldLease("session-other"));
  assert.equal(ownsControlLease(otherHolder), false);
  assert.equal(actuationReadiness(otherHolder).enabled, false);

  const degraded = reduceServerMessage(state, health("degraded"));
  assert.equal(actuationReadiness(degraded).enabled, false);
});

test("telemetry never regresses when stale or duplicate sequences arrive", () => {
  const telemetry = (sequence, batteryPercent) =>
    envelope(
      "telemetry",
      {
        sequence,
        batteryPercent,
        latitude: null,
        longitude: null,
        altitudeM: 12.5,
        flightState: "flying",
        gimbalPitchDeg: -10,
        cameraRecording: false,
      },
      `telemetry-${sequence}-${batteryPercent}`,
    );
  let state = reduceServerMessage(readyState(), telemetry(10, 80));
  state = reduceServerMessage(state, telemetry(9, 1));
  state = reduceServerMessage(state, telemetry(10, 2));
  assert.equal(state.telemetry?.sequence, 10);
  assert.equal(state.telemetry?.batteryPercent, 80);

  state = reduceServerMessage(state, telemetry(11, 79));
  assert.equal(state.telemetry?.sequence, 11);
  assert.equal(state.telemetry?.batteryPercent, 79);
});

test("reconnect clears stream snapshots so a restarted server may begin at sequence one", () => {
  let state = reduceServerMessage(
    readyState(),
    envelope("telemetry", {
      sequence: 42,
      batteryPercent: 80,
      latitude: null,
      longitude: null,
      altitudeM: null,
      flightState: "flying",
      gimbalPitchDeg: null,
      cameraRecording: null,
    }),
  );
  state = reduceServerMessage(
    state,
    envelope("capability_snapshot", {
      matrixId: "mini4pro-rcn3-g520-android",
      schemaVersion: 1,
      lastUpdated: "2026-08-15",
      sourceDigestSha256: "d".repeat(64),
      rows: [],
    }),
  );

  state = markRetryWaiting(state, 1, "server restarted");
  assert.equal(state.telemetry, null);
  assert.equal(state.capabilities, null);
  state = reduceServerMessage(state, hello);
  state = reduceServerMessage(
    state,
    envelope("telemetry", {
      sequence: 1,
      batteryPercent: 79,
      latitude: null,
      longitude: null,
      altitudeM: null,
      flightState: "grounded",
      gimbalPitchDeg: null,
      cameraRecording: null,
    }),
  );
  assert.equal(state.telemetry?.sequence, 1);
});

test("command ack and result update one bounded operator-facing record", () => {
  let state = markCommandSent(readyState(), "command-1", "takeoff");
  assert.equal(state.commands[0]?.status, "pending");

  state = reduceServerMessage(
    state,
    envelope("command_ack", {
      commandId: "command-1",
      decision: "accepted",
      reason: null,
      intentDigestSha256: "a".repeat(64),
    }),
  );
  assert.equal(state.commands[0]?.status, "accepted");
  assert.equal(state.commands[0]?.action, "takeoff");

  state = reduceServerMessage(
    state,
    envelope("command_result", {
      commandId: "command-1",
      status: "succeeded",
      reason: null,
      detail: "Mock command completed",
    }),
  );
  assert.deepEqual(state.commands[0], {
    commandId: "command-1",
    action: "takeoff",
    status: "succeeded",
    reason: null,
    detail: "Mock command completed",
  });
});

test("capability statuses are rendered from the server snapshot without assuming UNKNOWN", () => {
  const state = reduceServerMessage(
    readyState(),
    envelope("capability_snapshot", {
      matrixId: "mini4pro-rcn3-g520-android",
      schemaVersion: 1,
      lastUpdated: "2026-08-15",
      sourceDigestSha256: "b".repeat(64),
      rows: [
        { id: "battery", status: "CONFIRMED", assessment: "Commissioned." },
        { id: "rth_actuation", status: "LIMITED", assessment: "Limited." },
        { id: "opencv_on_device_recognition", status: "UNKNOWN", assessment: "Pending." },
      ],
    }),
  );

  assert.deepEqual(
    state.capabilities?.rows.map(({ id, status }) => ({ id, status })),
    [
      { id: "battery", status: "CONFIRMED" },
      { id: "rth_actuation", status: "LIMITED" },
      { id: "opencv_on_device_recognition", status: "UNKNOWN" },
    ],
  );
});

test("a failed neutral safety event disables every actuation control", () => {
  const state = reduceServerMessage(
    readyState(),
    envelope("safety_event", {
      action: "neutralize",
      outcome: "failed",
      trigger: "control_ttl_expired",
      leaseId: "lease-web-1",
      lastInputSequence: 7,
      detail: "Neutral failed",
    }),
  );

  assert.equal(actuationReadiness(state).enabled, false);
  assert.match(actuationReadiness(state).reason, /neutral/i);
});

test("telemetry cannot clear a protocol failure before a new server hello", () => {
  let state = readyState();
  state = reduceServerMessage(
    state,
    envelope("protocol_error", {
      code: "invalid_payload",
      relatedMessageId: "bad-request",
      detail: "The message payload is invalid.",
    }),
  );
  assert.equal(actuationReadiness(state).enabled, false);

  state = reduceServerMessage(
    state,
    envelope("telemetry", {
      sequence: 2,
      batteryPercent: 80,
      latitude: null,
      longitude: null,
      altitudeM: null,
      flightState: "connected",
      gimbalPitchDeg: null,
      cameraRecording: null,
    }),
  );
  assert.equal(state.lastProtocolError?.code, "invalid_payload");
  assert.equal(actuationReadiness(state).enabled, false);
});

test("a correlated lease denial never overwrites global held truth", () => {
  let state = readyState();
  state = reduceServerMessage(
    state,
    envelope("lease_state", {
      requestMessageId: "request-denied",
      state: "denied",
      leaseId: null,
      holderSessionId: null,
      expiresInMs: null,
      reason: "lease_held",
    }),
  );

  assert.equal(state.lease?.state, "held");
  assert.equal(state.lease?.leaseId, "lease-web-1");
  assert.equal(ownsControlLease(state), true);
  assert.equal(state.lastLeaseDenial?.reason, "lease_held");
});
