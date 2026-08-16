import assert from "node:assert/strict";
import test from "node:test";

import {
  CommissioningAuthorityConflictError,
  actuationReadiness,
  controlSurfaceReadiness,
  createInitialConsoleState,
  markCommissioningAuthorityLocallyExpired,
  markCommandSent,
  markHandshaking,
  markRetryWaiting,
  ownsControlLease,
  reduceServerMessage,
} from "../dist/assets/console-state.js";

const envelope = (
  type,
  payload,
  messageId = `server-${type}`,
  protocolVersion = "1.0",
) => ({
  protocolVersion,
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

const helloV11 = envelope("server_hello", {
  sessionId: "session-web-1",
  serverVersion: "test-server",
  selectedProtocolVersion: "1.1",
  authenticationRequired: false,
  acceptedAuthenticationSchemes: [],
}, "server-hello-v11", "1.1");

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

const commissioningId = "123e4567-e89b-42d3-a456-426614174000";

const v11Envelope = (type, payload, messageId = `server-v11-${type}`) =>
  envelope(type, payload, messageId, "1.1");

const commissioningAuthority = (overrides = {}) =>
  v11Envelope("commissioning_authority_state", {
    stateRevision: "10",
    state: "active",
    commissioningId,
    generation: "5",
    allowedIntents: ["virtual_stick"],
    expiresInMs: 5_000,
    reason: null,
    ...overrides,
  });

function djiReadyState(allowedIntents = ["virtual_stick"], now = 1_000) {
  let state = markHandshaking(createInitialConsoleState());
  for (const message of [
    helloV11,
    v11Envelope("runtime_state", {
      adapter: "dji",
      aircraftConnection: "connected",
      actuationLock: "locked",
      operatingProfile: "hardware_commissioning",
    }),
    v11Envelope("health", {
      status: "healthy",
      uptimeMs: 1_000,
      detail: null,
    }),
    v11Envelope("lease_state", heldLease().payload),
    commissioningAuthority({ allowedIntents }),
  ]) {
    state = reduceServerMessage(state, message, now);
  }
  return state;
}

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
    reason: "Mock localhost 控制路徑已就緒",
    leaseId: "lease-web-1",
  });
  assert.deepEqual(
    Object.values(controlSurfaceReadiness(state)).map(({ enabled }) => enabled),
    [true, true, true, true],
  );

  const locked = reduceServerMessage(state, runtime({ actuationLock: "locked" }));
  assert.equal(actuationReadiness(locked).enabled, false);
  assert.match(actuationReadiness(locked).reason, /lock/i);

  const otherHolder = reduceServerMessage(state, heldLease("session-other"));
  assert.equal(ownsControlLease(otherHolder), false);
  assert.equal(actuationReadiness(otherHolder).enabled, false);

  const degraded = reduceServerMessage(state, health("degraded"));
  assert.equal(actuationReadiness(degraded).enabled, false);
});

test("DJI readiness requires the exact per-intent temporary grant while runtime remains LOCKED", () => {
  const intentKeys = [
    "takeoff",
    "landing",
    "return_to_home",
    "virtual_stick",
  ];
  for (const intent of intentKeys) {
    const surface = controlSurfaceReadiness(djiReadyState([intent]), 1_001);
    assert.deepEqual(
      Object.fromEntries(
        intentKeys.map((candidate) => [candidate, surface[candidate].enabled]),
      ),
      Object.fromEntries(
        intentKeys.map((candidate) => [candidate, candidate === intent]),
      ),
    );
  }

  const combined = controlSurfaceReadiness(
    djiReadyState(["takeoff", "return_to_home", "virtual_stick"]),
    1_001,
  );
  assert.equal(combined.takeoff.enabled, true);
  assert.equal(combined.landing.enabled, false);
  assert.equal(combined.return_to_home.enabled, true);
  assert.equal(combined.virtual_stick.enabled, true);

  const missingAuthority = {
    ...djiReadyState(),
    commissioningAuthority: null,
  };
  assert.equal(actuationReadiness(missingAuthority, "virtual_stick", 1_001).enabled, false);
  const v10Fallback = {
    ...djiReadyState(),
    negotiatedProtocolVersion: "1.0",
  };
  assert.equal(actuationReadiness(v10Fallback, "virtual_stick", 1_001).enabled, false);
  const unexpectedlyUnlocked = reduceServerMessage(
    djiReadyState(),
    v11Envelope("runtime_state", {
      adapter: "dji",
      aircraftConnection: "connected",
      actuationLock: "unlocked",
      operatingProfile: "hardware_commissioning",
    }),
    1_001,
  );
  assert.equal(actuationReadiness(unexpectedlyUnlocked, "virtual_stick", 1_001).enabled, false);
  const otherHolder = reduceServerMessage(
    djiReadyState(),
    v11Envelope("lease_state", heldLease("session-other").payload),
    1_001,
  );
  assert.equal(actuationReadiness(otherHolder, "virtual_stick", 1_001).enabled, false);
  const degraded = reduceServerMessage(
    djiReadyState(),
    v11Envelope("health", { status: "degraded", uptimeMs: 1_001, detail: "test" }),
    1_001,
  );
  assert.equal(actuationReadiness(degraded, "virtual_stick", 1_001).enabled, false);
});

test("DJI authority deadline is fail-closed by default and at the exact monotonic boundary", () => {
  const state = djiReadyState(["virtual_stick"], 1_000);
  assert.equal(actuationReadiness(state, "virtual_stick").enabled, false);
  assert.equal(actuationReadiness(state, "virtual_stick", 5_999).enabled, true);
  assert.equal(actuationReadiness(state, "virtual_stick", 6_000).enabled, false);
});

test("authority revision and generation races cannot widen or revive a grant", () => {
  const initial = djiReadyState(["takeoff"], 1_000);
  const initialDeadline = initial.commissioningAuthority?.expiresAtMonotonicMs;

  const lowerRevision = reduceServerMessage(
    initial,
    commissioningAuthority({
      stateRevision: "9",
      generation: "99",
      allowedIntents: ["takeoff", "virtual_stick"],
    }),
    1_100,
  );
  assert.strictEqual(lowerRevision, initial);

  const exactDuplicate = reduceServerMessage(
    initial,
    commissioningAuthority({ allowedIntents: ["takeoff"] }),
    1_100,
  );
  assert.strictEqual(exactDuplicate, initial);
  assert.throws(
    () => reduceServerMessage(
      initial,
      commissioningAuthority({ allowedIntents: ["takeoff"], expiresInMs: 4_999 }),
      1_100,
    ),
    CommissioningAuthorityConflictError,
  );

  const longerTtl = reduceServerMessage(
    initial,
    commissioningAuthority({
      stateRevision: "11",
      allowedIntents: ["takeoff"],
      expiresInMs: 300_000,
    }),
    1_100,
  );
  assert.equal(longerTtl.commissioningAuthority?.expiresAtMonotonicMs, initialDeadline);
  const shorterTtl = reduceServerMessage(
    initial,
    commissioningAuthority({
      stateRevision: "11",
      allowedIntents: ["takeoff"],
      expiresInMs: 100,
    }),
    1_100,
  );
  assert.equal(shorterTtl.commissioningAuthority?.expiresAtMonotonicMs, 1_200);

  assert.throws(
    () => reduceServerMessage(
      initial,
      commissioningAuthority({
        stateRevision: "11",
        allowedIntents: ["takeoff", "virtual_stick"],
      }),
      1_100,
    ),
    CommissioningAuthorityConflictError,
  );

  const expired = markCommissioningAuthorityLocallyExpired(initial, "10");
  const attemptedRevival = reduceServerMessage(
    expired,
    commissioningAuthority({
      stateRevision: "11",
      allowedIntents: ["takeoff"],
      expiresInMs: 300_000,
    }),
    1_100,
  );
  assert.equal(attemptedRevival.commissioningAuthority?.locallyExpired, true);
  assert.equal(actuationReadiness(attemptedRevival, "takeoff", 1_101).enabled, false);

  const terminal = reduceServerMessage(
    initial,
    commissioningAuthority({
      stateRevision: "11",
      state: "inactive",
      allowedIntents: [],
      expiresInMs: null,
      reason: "host_revoked",
    }),
    1_100,
  );
  const staleSameGenerationActive = reduceServerMessage(
    terminal,
    commissioningAuthority({
      stateRevision: "12",
      allowedIntents: ["virtual_stick"],
    }),
    1_200,
  );
  assert.strictEqual(staleSameGenerationActive, terminal);

  assert.throws(
    () => reduceServerMessage(
      initial,
      commissioningAuthority({
        stateRevision: "11",
        generation: "4",
        allowedIntents: ["virtual_stick"],
      }),
      1_100,
    ),
    CommissioningAuthorityConflictError,
  );
  assert.throws(
    () => reduceServerMessage(
      initial,
      commissioningAuthority({
        stateRevision: "11",
        state: "inactive",
        generation: "4",
        allowedIntents: [],
        expiresInMs: null,
        reason: "host_revoked",
      }),
      1_100,
    ),
    CommissioningAuthorityConflictError,
  );

  const nextGeneration = reduceServerMessage(
    initial,
    commissioningAuthority({
      stateRevision: "11",
      commissioningId: "223e4567-e89b-42d3-a456-426614174000",
      generation: "6",
      allowedIntents: ["virtual_stick"],
    }),
    1_100,
  );
  assert.equal(nextGeneration.commissioningAuthority?.generation, "6");
  assert.equal(actuationReadiness(nextGeneration, "takeoff", 1_101).enabled, false);
  assert.equal(actuationReadiness(nextGeneration, "virtual_stick", 1_101).enabled, true);
});

test("NO_ACTIVE preserves the generation fence and cannot widen or revive that generation", () => {
  const initial = djiReadyState(["takeoff"], 1_000);
  const initialDeadline = initial.commissioningAuthority?.expiresAtMonotonicMs;
  const idle = reduceServerMessage(
    initial,
    commissioningAuthority({
      stateRevision: "11",
      state: "inactive",
      commissioningId: null,
      generation: "0",
      allowedIntents: [],
      expiresInMs: null,
      reason: "no_active_session",
    }),
    1_100,
  );
  assert.deepEqual(idle.commissioningAuthorityFence, {
    commissioningId,
    generation: "5",
    allowedIntents: ["takeoff"],
    expiresAtMonotonicMs: initialDeadline,
    locallyExpired: false,
    terminal: true,
  });

  const attemptedWidening = reduceServerMessage(
    idle,
    commissioningAuthority({
      stateRevision: "12",
      generation: "5",
      allowedIntents: ["takeoff", "virtual_stick"],
      expiresInMs: 300_000,
    }),
    1_200,
  );
  assert.strictEqual(attemptedWidening, idle);
  assert.equal(actuationReadiness(attemptedWidening, "takeoff", 1_201).enabled, false);
  assert.equal(actuationReadiness(attemptedWidening, "virtual_stick", 1_201).enabled, false);

  const newerTerminalId = "223e4567-e89b-42d3-a456-426614174000";
  const newerTerminal = reduceServerMessage(
    idle,
    commissioningAuthority({
      stateRevision: "13",
      state: "inactive",
      commissioningId: newerTerminalId,
      generation: "6",
      allowedIntents: [],
      expiresInMs: null,
      reason: "host_revoked",
    }),
    1_300,
  );
  assert.equal(newerTerminal.commissioningAuthorityFence?.commissioningId, newerTerminalId);
  assert.equal(newerTerminal.commissioningAuthorityFence?.generation, "6");
  assert.equal(newerTerminal.commissioningAuthorityFence?.terminal, true);
});

test("a locally expired generation stays expired across NO_ACTIVE and rejects a TTL refresh", () => {
  const initial = djiReadyState(["virtual_stick"], 1_000);
  const initialDeadline = initial.commissioningAuthority?.expiresAtMonotonicMs;
  const expired = markCommissioningAuthorityLocallyExpired(initial, "10");
  assert.equal(expired.commissioningAuthorityFence?.locallyExpired, true);

  const idle = reduceServerMessage(
    expired,
    commissioningAuthority({
      stateRevision: "11",
      state: "inactive",
      commissioningId: null,
      generation: "0",
      allowedIntents: [],
      expiresInMs: null,
      reason: "no_active_session",
    }),
    1_100,
  );
  const attemptedRevival = reduceServerMessage(
    idle,
    commissioningAuthority({
      stateRevision: "12",
      generation: "5",
      allowedIntents: ["virtual_stick"],
      expiresInMs: 300_000,
    }),
    1_200,
  );

  assert.strictEqual(attemptedRevival, idle);
  assert.equal(attemptedRevival.commissioningAuthorityFence?.locallyExpired, true);
  assert.equal(
    attemptedRevival.commissioningAuthorityFence?.expiresAtMonotonicMs,
    initialDeadline,
  );
  assert.equal(attemptedRevival.commissioningAuthorityFence?.terminal, true);
  assert.equal(actuationReadiness(attemptedRevival, "virtual_stick", 1_201).enabled, false);
});

test("reconnect clears negotiated authority and a replacement session starts blocked", () => {
  const previous = djiReadyState(["virtual_stick"]);
  assert.notEqual(previous.commissioningAuthorityFence, null);
  let replacement = markRetryWaiting(previous, 1, "socket replaced");
  assert.equal(replacement.negotiatedProtocolVersion, null);
  assert.equal(replacement.commissioningAuthority, null);
  assert.equal(replacement.commissioningAuthorityFence, null);
  assert.equal(actuationReadiness(replacement, "virtual_stick", 1_001).enabled, false);

  replacement = reduceServerMessage(replacement, {
    ...helloV11,
    payload: { ...helloV11.payload, sessionId: "session-web-2" },
  });
  assert.equal(replacement.sessionId, "session-web-2");
  assert.equal(replacement.commissioningAuthority, null);
  assert.equal(replacement.commissioningAuthorityFence, null);
  assert.equal(actuationReadiness(replacement, "virtual_stick", 1_001).enabled, false);
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
