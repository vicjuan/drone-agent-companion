import assert from "node:assert/strict";
import test from "node:test";

import {
  CompanionConsoleClient,
  CONTROL_FRAME_PERIOD_MS,
  CONTROL_FRAME_TTL_MS,
  CONTROL_VECTORS,
  createConsoleMessageIdFactory,
} from "../dist/assets/console-client.js";
import {
  decodeClientConsoleMessage,
  encodeServerConsoleMessage,
} from "../dist/assets/console-protocol.js";

class FakeSocket {
  readyState = 0;
  sent = [];
  closeCalls = [];
  #open = [];
  #message = [];
  #close = [];
  #error = [];

  send(text) {
    if (this.readyState !== 1) throw new Error("socket is not open");
    this.sent.push(text);
  }

  close(code, reason) {
    this.closeCalls.push({ code, reason });
    this.readyState = 3;
  }

  onOpen(listener) {
    this.#open.push(listener);
  }

  onMessage(listener) {
    this.#message.push(listener);
  }

  onClose(listener) {
    this.#close.push(listener);
  }

  onError(listener) {
    this.#error.push(listener);
  }

  open() {
    this.readyState = 1;
    this.#open.forEach((listener) => listener());
  }

  receive(message) {
    const encoded = encodeServerConsoleMessage(message);
    this.#message.forEach((listener) => listener(encoded));
  }

  receiveRaw(text) {
    this.#message.forEach((listener) => listener(text));
  }

  serverClose(detail = "peer closed") {
    this.readyState = 3;
    this.#close.forEach((listener) => listener(detail));
  }

  fail() {
    this.#error.forEach((listener) => listener());
  }
}

class FakeScheduler {
  timeouts = [];
  intervals = [];

  constructor(clock) {
    this.clock = clock;
  }

  setTimeout(callback, delayMs) {
    const task = {
      callback,
      delayMs,
      scheduledAt: this.clock.now,
      cancelled: false,
    };
    this.timeouts.push(task);
    return task;
  }

  clearTimeout(task) {
    task.cancelled = true;
  }

  setInterval(callback, delayMs) {
    const task = { callback, delayMs, cancelled: false };
    this.intervals.push(task);
    return task;
  }

  clearInterval(task) {
    task.cancelled = true;
  }

  runNextTimeout(predicate = () => true) {
    const task = this.timeouts.find(
      (candidate) => !candidate.cancelled && predicate(candidate),
    );
    assert.ok(task, "expected an active timeout");
    task.cancelled = true;
    this.clock.now = Math.max(
      this.clock.now,
      task.scheduledAt + task.delayMs,
    );
    task.callback();
  }

  runTimeoutWithDelay(delayMs) {
    this.runNextTimeout((task) => task.delayMs === delayMs);
  }

  tickIntervals() {
    this.intervals
      .filter((task) => !task.cancelled)
      .forEach((task) => task.callback());
  }

  activeIntervals() {
    return this.intervals.filter((task) => !task.cancelled);
  }
}

const serverMessage = (
  type,
  payload,
  id = `server-${type}`,
  protocolVersion = "1.0",
) => ({
  protocolVersion,
  messageId: id,
  type,
  payload,
});

const hello = serverMessage("server_hello", {
  sessionId: "session-client-1",
  serverVersion: "test-server",
  selectedProtocolVersion: "1.0",
  authenticationRequired: false,
  acceptedAuthenticationSchemes: [],
});

const helloV11 = (sessionId = "session-client-1") =>
  serverMessage("server_hello", {
    sessionId,
    serverVersion: "test-server",
    selectedProtocolVersion: "1.1",
    authenticationRequired: false,
    acceptedAuthenticationSchemes: [],
  }, `server-hello-${sessionId}`, "1.1");

const runtime = (overrides = {}) =>
  serverMessage("runtime_state", {
    adapter: "mock",
    aircraftConnection: "connected",
    actuationLock: "unlocked",
    operatingProfile: "localhost_development",
    ...overrides,
  });

const health = serverMessage("health", {
  status: "healthy",
  uptimeMs: 100,
  detail: null,
});

const heldLease = serverMessage("lease_state", {
  requestMessageId: "lease-request-1",
  state: "held",
  leaseId: "lease-client-1",
  holderSessionId: "session-client-1",
  expiresInMs: 5_000,
  reason: null,
});

const commissioningId = "123e4567-e89b-42d3-a456-426614174000";

const v11Message = (type, payload, id = `server-v11-${type}`) =>
  serverMessage(type, payload, id, "1.1");

const authority = (overrides = {}) =>
  v11Message("commissioning_authority_state", {
    stateRevision: "10",
    state: "active",
    commissioningId,
    generation: "5",
    allowedIntents: ["virtual_stick"],
    expiresInMs: 5_000,
    reason: null,
    ...overrides,
  });

const djiRuntime = v11Message("runtime_state", {
  adapter: "dji",
  aircraftConnection: "connected",
  actuationLock: "locked",
  operatingProfile: "hardware_commissioning",
});

const healthyV11 = v11Message("health", {
  status: "healthy",
  uptimeMs: 100,
  detail: null,
});

const heldLeaseV11 = (sessionId = "session-client-1") =>
  v11Message("lease_state", {
    requestMessageId: "lease-request-v11",
    state: "held",
    leaseId: "lease-client-v11",
    holderSessionId: sessionId,
    expiresInMs: 5_000,
    reason: null,
  });

function harness() {
  const sockets = [];
  const clock = { now: 0 };
  const scheduler = new FakeScheduler(clock);
  let id = 0;
  const states = [];
  const client = new CompanionConsoleClient({
    url: "ws://127.0.0.1/api/console/v1",
    socketFactory: () => {
      const socket = new FakeSocket();
      sockets.push(socket);
      return socket;
    },
    scheduler,
    idFactory: () => `client-message-${++id}`,
    onState: (state) => states.push(state),
    reconnectBaseDelayMs: 10,
    reconnectMaximumDelayMs: 40,
    handshakeTimeoutMs: 100,
    now: () => clock.now,
  });
  return { client, sockets, scheduler, states, clock };
}

function bringOnline(fixture, runtimeMessage = runtime()) {
  fixture.client.start();
  const socket = fixture.sockets[0];
  assert.ok(socket);
  socket.open();
  socket.receive(hello);
  socket.receive(runtimeMessage);
  socket.receive(health);
  socket.receive(heldLease);
  return socket;
}

function bringDjiOnline(
  fixture,
  authorityMessage = authority(),
  sessionId = "session-client-1",
) {
  fixture.client.start();
  const socket = fixture.sockets[0];
  assert.ok(socket);
  socket.open();
  socket.receive(helloV11(sessionId));
  socket.receive(djiRuntime);
  socket.receive(healthyV11);
  socket.receive(heldLeaseV11(sessionId));
  socket.receive(authorityMessage);
  return socket;
}

function sentMessages(socket) {
  return socket.sent.map((text) => decodeClientConsoleMessage(text));
}

test("client sends hello, waits for server hello, then reconnects after close", () => {
  const fixture = harness();
  fixture.client.start();
  assert.equal(fixture.client.getState().connectionPhase, "connecting");
  const first = fixture.sockets[0];
  assert.ok(first);

  first.open();
  assert.equal(fixture.client.getState().connectionPhase, "handshaking");
  assert.equal(sentMessages(first)[0]?.type, "client_hello");
  assert.equal(sentMessages(first)[0]?.protocolVersion, "1.0");
  assert.deepEqual(sentMessages(first)[0]?.payload.supportedProtocolVersions, ["1.1", "1.0"]);

  first.receive(hello);
  assert.equal(fixture.client.getState().connectionPhase, "online");
  assert.equal(fixture.client.getState().sessionId, "session-client-1");

  first.serverClose("network lost");
  assert.equal(fixture.client.getState().connectionPhase, "retry_wait");
  assert.equal(fixture.client.getState().sessionId, null);
  fixture.scheduler.runNextTimeout();
  assert.equal(fixture.sockets.length, 2);
  assert.equal(fixture.client.getState().connectionPhase, "connecting");
});

test("press-and-hold emits sequenced TTL frames and every pointer ending emits neutral", () => {
  const fixture = harness();
  const socket = bringOnline(fixture);

  assert.equal(fixture.client.beginControl(CONTROL_VECTORS.forward), true);
  fixture.scheduler.tickIntervals();
  assert.equal(fixture.scheduler.activeIntervals()[0]?.delayMs, CONTROL_FRAME_PERIOD_MS);
  assert.equal(fixture.client.releaseControl(), true);

  assert.equal(fixture.client.beginControl(CONTROL_VECTORS.ascend), true);
  assert.equal(fixture.client.cancelControl(), true);

  assert.equal(fixture.client.beginControl(CONTROL_VECTORS.yawLeft), true);
  assert.equal(fixture.client.lostPointerCapture(), true);

  const controls = sentMessages(socket).filter((message) =>
    ["control_frame", "control_neutral"].includes(message.type),
  );
  assert.deepEqual(
    controls.map((message) => message.payload.inputSequence),
    [1, 2, 3, 4, 5, 6, 7],
  );
  const frames = controls.filter((message) => message.type === "control_frame");
  assert.equal(frames[0]?.payload.ttlMs, CONTROL_FRAME_TTL_MS);
  assert.deepEqual(
    controls
      .filter((message) => message.type === "control_neutral")
      .map((message) => message.payload.reason),
    ["operator_release", "pointer_cancel", "pointer_cancel"],
  );
  assert.equal(fixture.scheduler.activeIntervals().length, 0);
});

test("duplicate hold endings emit exactly one neutral and leave no control interval", () => {
  const fixture = harness();
  const socket = bringOnline(fixture);

  assert.equal(fixture.client.beginControl(CONTROL_VECTORS.forward), true);
  assert.equal(fixture.scheduler.activeIntervals().length, 1);
  assert.equal(fixture.client.releaseControl(), true);
  assert.equal(fixture.client.releaseControl(), false);
  assert.equal(fixture.client.cancelControl(), false);
  assert.equal(fixture.client.lostPointerCapture(), false);

  const controls = sentMessages(socket).filter((message) =>
    ["control_frame", "control_neutral"].includes(message.type),
  );
  assert.deepEqual(
    controls.map((message) => message.type),
    ["control_frame", "control_neutral"],
  );
  assert.equal(fixture.scheduler.activeIntervals().length, 0);
});

test("command confirmation stops held frames and exposes the neutral sequence", () => {
  const fixture = harness();
  const socket = bringOnline(fixture);

  assert.equal(fixture.client.beginControl(CONTROL_VECTORS.forward), true);
  assert.equal(fixture.scheduler.activeIntervals().length, 1);
  assert.deepEqual(fixture.client.releaseControlForCommandConfirmation(), {
    leaseId: "lease-client-1",
    inputSequence: 2,
  });
  assert.equal(fixture.scheduler.activeIntervals().length, 0);
  fixture.scheduler.tickIntervals();
  assert.equal(fixture.client.releaseControlForCommandConfirmation(), null);

  const controls = sentMessages(socket).filter((message) =>
    ["control_frame", "control_neutral"].includes(message.type),
  );
  assert.deepEqual(
    controls.map((message) => [message.type, message.payload.inputSequence]),
    [
      ["control_frame", 1],
      ["control_neutral", 2],
    ],
  );
  assert.equal(controls[1]?.payload.reason, "operator_release");
});

test("blur and pagehide neutralize even an idle owned lease and pagehide prevents reconnect", () => {
  const fixture = harness();
  const socket = bringOnline(fixture);

  assert.equal(fixture.client.handleWindowBlur(), true);
  fixture.client.handlePageHide();

  const neutrals = sentMessages(socket).filter(
    (message) => message.type === "control_neutral",
  );
  assert.deepEqual(
    neutrals.map((message) => message.payload.reason),
    ["window_blur", "page_hide"],
  );
  assert.equal(fixture.client.getState().connectionPhase, "stopped");
  assert.equal(socket.closeCalls.length, 1);
  socket.serverClose();
  assert.equal(fixture.client.getState().connectionPhase, "stopped");
});

test("socket disconnect stops held control locally and waits for server dead-man neutral", () => {
  const fixture = harness();
  const socket = bringOnline(fixture);
  fixture.client.beginControl(CONTROL_VECTORS.backward);
  assert.equal(fixture.scheduler.activeIntervals().length, 1);

  socket.serverClose("cable removed");

  assert.equal(fixture.scheduler.activeIntervals().length, 0);
  assert.equal(fixture.client.getState().connectionPhase, "retry_wait");
  assert.equal(fixture.client.sendCommand("takeoff"), null);
});

test("runtime lock prevents commands and continuous control", () => {
  const fixture = harness();
  const socket = bringOnline(fixture, runtime({ actuationLock: "locked" }));
  const sentBefore = socket.sent.length;

  assert.equal(fixture.client.sendCommand("takeoff"), null);
  assert.equal(fixture.client.beginControl(CONTROL_VECTORS.forward), false);
  assert.equal(socket.sent.length, sentBefore);
});

test("a v1.0 DJI session stays disabled even with connection health and an owned lease", () => {
  const fixture = harness();
  const socket = bringOnline(
    fixture,
    runtime({
      adapter: "dji",
      actuationLock: "locked",
      operatingProfile: "hardware_commissioning",
    }),
  );
  const sentBefore = socket.sent.length;

  assert.equal(fixture.client.sendCommand("takeoff"), null);
  assert.equal(fixture.client.sendCommand("landing"), null);
  assert.equal(fixture.client.sendCommand("return_to_home"), null);
  assert.equal(fixture.client.beginControl(CONTROL_VECTORS.forward), false);
  assert.equal(socket.sent.length, sentBefore);
});

test("v1.1 dispatch uses the negotiated envelope and gates every DJI intent exactly", () => {
  const takeoffFixture = harness();
  const takeoffSocket = bringDjiOnline(
    takeoffFixture,
    authority({ allowedIntents: ["takeoff"] }),
  );
  assert.ok(takeoffFixture.client.sendCommand("takeoff"));
  assert.equal(takeoffFixture.client.sendCommand("landing"), null);
  assert.equal(takeoffFixture.client.sendCommand("return_to_home"), null);
  assert.equal(
    takeoffFixture.client.beginControl(CONTROL_VECTORS.forward),
    false,
  );
  assert.equal(takeoffFixture.client.renewLease(), true);
  const takeoffMessages = sentMessages(takeoffSocket);
  assert.equal(takeoffMessages[0]?.type, "client_hello");
  assert.equal(takeoffMessages[0]?.protocolVersion, "1.0");
  for (const message of takeoffMessages.slice(1)) {
    assert.equal(message.protocolVersion, "1.1");
  }
  assert.deepEqual(
    takeoffMessages
      .filter((message) => message.type === "command_request")
      .map((message) => message.payload.action),
    ["takeoff"],
  );

  const virtualStickFixture = harness();
  const virtualStickSocket = bringDjiOnline(
    virtualStickFixture,
    authority({ allowedIntents: ["virtual_stick"] }),
  );
  assert.equal(virtualStickFixture.client.sendCommand("takeoff"), null);
  assert.equal(
    virtualStickFixture.client.beginControl(CONTROL_VECTORS.forward),
    true,
  );
  assert.equal(
    sentMessages(virtualStickSocket).at(-1)?.type,
    "control_frame",
  );
});

test("losing only VIRTUAL_STICK neutralizes a hold while unrelated intent loss does not", () => {
  const lostVirtualStick = harness();
  const lostSocket = bringDjiOnline(
    lostVirtualStick,
    authority({ allowedIntents: ["takeoff", "virtual_stick"] }),
  );
  assert.equal(
    lostVirtualStick.client.beginControl(CONTROL_VECTORS.forward),
    true,
  );
  lostSocket.receive(
    authority({
      stateRevision: "11",
      commissioningId: "223e4567-e89b-42d3-a456-426614174000",
      generation: "6",
      allowedIntents: ["takeoff"],
    }),
  );
  assert.equal(lostVirtualStick.scheduler.activeIntervals().length, 0);
  const lostControls = sentMessages(lostSocket).filter((message) =>
    ["control_frame", "control_neutral"].includes(message.type),
  );
  assert.deepEqual(
    lostControls.map((message) => message.type),
    ["control_frame", "control_neutral"],
  );
  assert.equal(lostControls.at(-1)?.payload.reason, "operator_release");

  const retainedVirtualStick = harness();
  const retainedSocket = bringDjiOnline(
    retainedVirtualStick,
    authority({ allowedIntents: ["takeoff", "virtual_stick"] }),
  );
  assert.equal(
    retainedVirtualStick.client.beginControl(CONTROL_VECTORS.forward),
    true,
  );
  retainedSocket.receive(
    authority({
      stateRevision: "11",
      commissioningId: "223e4567-e89b-42d3-a456-426614174000",
      generation: "6",
      allowedIntents: ["virtual_stick"],
    }),
  );
  assert.equal(retainedVirtualStick.scheduler.activeIntervals().length, 1);
  assert.equal(
    sentMessages(retainedSocket).filter(
      (message) => message.type === "control_neutral",
    ).length,
    0,
  );
});

test("same-revision or same-generation authority conflicts neutralize and fail the socket closed", () => {
  for (const conflicting of [
    authority({ expiresInMs: 4_999 }),
    authority({
      stateRevision: "11",
      allowedIntents: ["takeoff", "virtual_stick"],
    }),
    authority({
      stateRevision: "11",
      commissioningId: "223e4567-e89b-42d3-a456-426614174000",
    }),
  ]) {
    const fixture = harness();
    const socket = bringDjiOnline(fixture);
    assert.equal(fixture.client.beginControl(CONTROL_VECTORS.forward), true);

    socket.receive(conflicting);

    assert.equal(fixture.client.getState().connectionPhase, "retry_wait");
    assert.equal(fixture.client.getState().commissioningAuthority, null);
    assert.equal(fixture.client.getState().lastProtocolError?.code, "invalid_payload");
    assert.equal(fixture.scheduler.activeIntervals().length, 0);
    assert.equal(socket.closeCalls.length, 1);
    assert.equal(
      sentMessages(socket).filter(
        (message) => message.type === "control_neutral",
      ).length,
      1,
    );
  }
});

test("a newer same-generation snapshot may only shorten the local authority deadline", () => {
  const fixture = harness();
  const socket = bringDjiOnline(
    fixture,
    authority({ allowedIntents: ["virtual_stick"], expiresInMs: 100 }),
  );
  assert.equal(fixture.client.beginControl(CONTROL_VECTORS.forward), true);
  fixture.clock.now = 50;
  socket.receive(
    authority({
      stateRevision: "11",
      allowedIntents: ["virtual_stick"],
      expiresInMs: 300_000,
    }),
  );
  assert.equal(
    fixture.client.getState().commissioningAuthority?.expiresAtMonotonicMs,
    100,
  );

  fixture.scheduler.runTimeoutWithDelay(50);

  assert.equal(fixture.clock.now, 100);
  assert.equal(
    fixture.client.getState().commissioningAuthority?.locallyExpired,
    true,
  );
  assert.equal(fixture.scheduler.activeIntervals().length, 0);
  assert.equal(
    sentMessages(socket).filter(
      (message) => message.type === "control_neutral",
    ).length,
    1,
  );
  fixture.scheduler.tickIntervals();
  assert.equal(
    sentMessages(socket).filter(
      (message) => message.type === "control_frame",
    ).length,
    1,
  );
});

test("a cancelled old-generation TTL callback cannot expire or detach the replacement timer", () => {
  const fixture = harness();
  const socket = bringDjiOnline(fixture, authority({ expiresInMs: 100 }));
  const oldTimer = fixture.scheduler.timeouts.find(
    (task) => !task.cancelled && task.delayMs === 100,
  );
  assert.ok(oldTimer);

  socket.receive(
    authority({
      stateRevision: "11",
      commissioningId: "223e4567-e89b-42d3-a456-426614174000",
      generation: "6",
      expiresInMs: 500,
    }),
  );
  const replacementTimer = fixture.scheduler.timeouts.find(
    (task) => !task.cancelled && task.delayMs === 500,
  );
  assert.ok(replacementTimer);
  assert.equal(oldTimer.cancelled, true);

  oldTimer.callback();
  assert.equal(fixture.client.getState().commissioningAuthority?.generation, "6");
  assert.equal(fixture.client.getState().commissioningAuthority?.locallyExpired, false);

  socket.receive(
    authority({
      stateRevision: "12",
      state: "inactive",
      commissioningId: "223e4567-e89b-42d3-a456-426614174000",
      generation: "6",
      allowedIntents: [],
      expiresInMs: null,
      reason: "host_revoked",
    }),
  );
  assert.equal(replacementTimer.cancelled, true);
  assert.equal(fixture.client.getState().commissioningAuthority?.state, "inactive");
});

test("every held-frame dispatch rechecks monotonic expiry even before a throttled timer runs", () => {
  const fixture = harness();
  const socket = bringDjiOnline(
    fixture,
    authority({ expiresInMs: 100 }),
  );
  assert.equal(fixture.client.beginControl(CONTROL_VECTORS.forward), true);
  fixture.clock.now = 100;

  fixture.scheduler.tickIntervals();

  assert.equal(fixture.scheduler.activeIntervals().length, 0);
  assert.deepEqual(
    sentMessages(socket)
      .filter((message) => ["control_frame", "control_neutral"].includes(message.type))
      .map((message) => message.type),
    ["control_frame", "control_neutral"],
  );
});

test("disconnect clears authority and stale sockets cannot grant a replacement session", () => {
  const fixture = harness();
  const first = bringDjiOnline(fixture);
  assert.equal(fixture.client.beginControl(CONTROL_VECTORS.forward), true);

  first.serverClose("operator disconnected");
  assert.equal(fixture.client.getState().commissioningAuthority, null);
  assert.equal(fixture.scheduler.activeIntervals().length, 0);
  first.receive(
    authority({
      stateRevision: "99",
      commissioningId: "323e4567-e89b-42d3-a456-426614174000",
      generation: "99",
    }),
  );
  assert.equal(fixture.client.getState().commissioningAuthority, null);

  fixture.scheduler.runTimeoutWithDelay(10);
  const replacement = fixture.sockets[1];
  assert.ok(replacement);
  replacement.open();
  replacement.receive(helloV11("session-client-2"));
  replacement.receive(djiRuntime);
  replacement.receive(healthyV11);
  replacement.receive(heldLeaseV11("session-client-2"));
  replacement.receive(
    authority({
      stateRevision: "100",
      state: "inactive",
      commissioningId: null,
      generation: "0",
      allowedIntents: [],
      expiresInMs: null,
      reason: "no_active_session",
    }),
  );
  assert.equal(fixture.client.getState().sessionId, "session-client-2");
  assert.equal(fixture.client.getState().commissioningAuthority?.state, "inactive");
  assert.equal(fixture.client.beginControl(CONTROL_VECTORS.forward), false);
});

test("handshake-first and selected-version rules reject protocol errors or downgraded envelopes", () => {
  for (const invalidWire of [
    serverMessage("protocol_error", {
      relatedMessageId: null,
      code: "handshake_required",
      detail: "hello required",
    }),
    { ...runtime(), protocolVersion: "1.1" },
  ]) {
    const fixture = harness();
    fixture.client.start();
    const socket = fixture.sockets[0];
    socket.open();
    socket.receive(invalidWire);
    assert.equal(fixture.client.getState().connectionPhase, "retry_wait");
    assert.equal(socket.closeCalls.length, 1);
  }

  const fixture = harness();
  const socket = bringDjiOnline(fixture);
  socket.receiveRaw(JSON.stringify({
    ...runtime(),
    protocolVersion: "1.0",
  }));
  assert.equal(fixture.client.getState().connectionPhase, "retry_wait");
  assert.equal(fixture.client.getState().commissioningAuthority, null);
});

test("command request is correlated into state and updated by ack and result", () => {
  const fixture = harness();
  const socket = bringOnline(fixture);

  const commandId = fixture.client.sendCommand("return_to_home");
  assert.ok(commandId);
  const messages = sentMessages(socket);
  const request = messages.find(
    (message) => message.type === "command_request",
  );
  assert.equal(request?.payload.action, "return_to_home");
  assert.equal(messages.at(-1)?.type, "command_request");
  assert.equal(fixture.client.getState().commands[0]?.status, "pending");

  socket.receive(
    serverMessage("command_ack", {
      commandId,
      decision: "accepted",
      reason: null,
      intentDigestSha256: "c".repeat(64),
    }),
  );
  socket.receive(
    serverMessage("command_result", {
      commandId,
      status: "succeeded",
      reason: null,
      detail: "Mock RTH accepted",
    }),
  );
  assert.equal(fixture.client.getState().commands[0]?.status, "succeeded");
  assert.equal(fixture.client.getState().commands[0]?.detail, "Mock RTH accepted");
});

test("a discrete command stops held control and lets the server own the neutral barrier", () => {
  const fixture = harness();
  const socket = bringOnline(fixture);
  assert.equal(fixture.client.beginControl(CONTROL_VECTORS.forward), true);

  const commandId = fixture.client.sendCommand("takeoff");

  assert.ok(commandId);
  assert.equal(fixture.scheduler.activeIntervals().length, 0);
  assert.deepEqual(
    sentMessages(socket).slice(-2).map((message) => message.type),
    ["control_frame", "command_request"],
  );
});

test("a rejected control ack stops the hold loop and sends operator neutral", () => {
  const fixture = harness();
  const socket = bringOnline(fixture);
  fixture.client.beginControl(CONTROL_VECTORS.descend);

  socket.receive(
    serverMessage("control_ack", {
      leaseId: "lease-client-1",
      inputSequence: 1,
      status: "rejected",
      reason: "actuation_locked",
    }),
  );

  assert.equal(fixture.scheduler.activeIntervals().length, 0);
  const messages = sentMessages(socket);
  assert.equal(messages.at(-1)?.type, "control_neutral");
  assert.equal(messages.at(-1)?.payload.reason, "operator_release");
});

test("lease release sends neutral before the release request", () => {
  const fixture = harness();
  const socket = bringOnline(fixture);

  assert.equal(fixture.client.releaseLease(), true);
  const tail = sentMessages(socket).slice(-2);
  assert.equal(tail[0]?.type, "control_neutral");
  assert.equal(tail[1]?.type, "lease_release");
});

test("default message ids prefer random UUIDs and fallback seeds remain unique per tab", () => {
  const uuids = [
    "11111111-1111-4111-8111-111111111111",
    "22222222-2222-4222-8222-222222222222",
  ];
  const randomFactory = createConsoleMessageIdFactory({
    randomUuid: () => uuids.shift(),
    fallbackSeed: "unused-seed",
  });
  const randomIds = [randomFactory(), randomFactory()];
  assert.deepEqual(randomIds, [
    "web-11111111-1111-4111-8111-111111111111",
    "web-22222222-2222-4222-8222-222222222222",
  ]);

  const tabA = createConsoleMessageIdFactory({
    randomUuid: null,
    fallbackSeed: "tab-a",
  });
  const tabB = createConsoleMessageIdFactory({
    randomUuid: null,
    fallbackSeed: "tab-b",
  });
  const fallbackIds = [tabA(), tabA(), tabB()];
  assert.equal(new Set(fallbackIds).size, fallbackIds.length);
  for (const id of [...randomIds, ...fallbackIds]) {
    assert.match(id, /^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$/);
  }
});
