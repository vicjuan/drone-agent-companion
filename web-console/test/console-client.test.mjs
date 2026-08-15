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

  setTimeout(callback, delayMs) {
    const task = { callback, delayMs, cancelled: false };
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

  runNextTimeout() {
    const task = this.timeouts.find((candidate) => !candidate.cancelled);
    assert.ok(task, "expected an active timeout");
    task.cancelled = true;
    task.callback();
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

const serverMessage = (type, payload, id = `server-${type}`) => ({
  protocolVersion: "1.0",
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

function harness() {
  const sockets = [];
  const scheduler = new FakeScheduler();
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
  });
  return { client, sockets, scheduler, states };
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
  assert.deepEqual(sentMessages(first)[0]?.payload.supportedProtocolVersions, ["1.0"]);

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
