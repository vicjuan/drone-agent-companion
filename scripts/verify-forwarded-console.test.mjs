import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { EventEmitter } from "node:events";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import {
  OPCODE_PING,
  OPCODE_PONG,
  OPCODE_TEXT,
  REQUIRED_G520_CAPABILITY_IDS,
  ConsoleEnvelopeReader,
  RawWebSocketConnection,
  buildUpgradeRequest,
  computeDiscreteIntentDigest,
  decodeServerFrames,
  encodeClientFrame,
  expectedWebSocketAccept,
  loadCanonicalCapabilityMatrix,
  loadCanonicalServerDecoder,
  parseRejectedUpgradeResponse,
  parseUpgradeResponse,
  requireCapabilitySnapshotMatchesCanonical,
  verifyForwardedConsole,
} from "./verify-forwarded-console.mjs";

function lengthHeader(payloadLength, masked = false) {
  const maskBit = masked ? 0x80 : 0;
  if (payloadLength <= 125) return Buffer.from([maskBit | payloadLength]);
  if (payloadLength <= 0xffff) {
    const header = Buffer.alloc(3);
    header[0] = maskBit | 126;
    header.writeUInt16BE(payloadLength, 1);
    return header;
  }
  const header = Buffer.alloc(9);
  header[0] = maskBit | 127;
  header.writeBigUInt64BE(BigInt(payloadLength), 1);
  return header;
}

function serverFrame(opcode, payload, { fin = true, masked = false } = {}) {
  const payloadBuffer = Buffer.from(payload);
  const firstByte = (fin ? 0x80 : 0) | opcode;
  const header = lengthHeader(payloadBuffer.length, masked);
  if (!masked) return Buffer.concat([Buffer.from([firstByte]), header, payloadBuffer]);
  const maskKey = Buffer.from([1, 2, 3, 4]);
  const encoded = Buffer.from(payloadBuffer);
  for (let index = 0; index < encoded.length; index += 1) encoded[index] ^= maskKey[index % 4];
  return Buffer.concat([Buffer.from([firstByte]), header, maskKey, encoded]);
}

function decodeSingleClientFrame(frame) {
  const firstByte = frame[0];
  const secondByte = frame[1];
  assert.equal((secondByte & 0x80) !== 0, true, "client frame must be masked");
  const lengthCode = secondByte & 0x7f;
  let offset = 2;
  let payloadLength = lengthCode;
  if (lengthCode === 126) {
    payloadLength = frame.readUInt16BE(offset);
    offset += 2;
  } else if (lengthCode === 127) {
    payloadLength = Number(frame.readBigUInt64BE(offset));
    offset += 8;
  }
  const maskKey = frame.subarray(offset, offset + 4);
  offset += 4;
  const payload = Buffer.from(frame.subarray(offset, offset + payloadLength));
  for (let index = 0; index < payload.length; index += 1) payload[index] ^= maskKey[index % 4];
  assert.equal(offset + payloadLength, frame.length);
  return { fin: (firstByte & 0x80) !== 0, opcode: firstByte & 0x0f, payload, lengthCode };
}

class FakeSocket extends EventEmitter {
  constructor() {
    super();
    this.writes = [];
    this.ended = false;
    this.destroyed = false;
  }

  write(value) {
    this.writes.push(Buffer.from(value));
    return true;
  }

  end() {
    this.ended = true;
  }

  destroy() {
    this.destroyed = true;
  }
}

const parseServerFixture = (text) => JSON.parse(text);

const STRICT_DECODER_SOURCE = `
const envelopeKeys = ["messageId", "payload", "protocolVersion", "type"];
const payloadKeys = {
  server_hello: ["acceptedAuthenticationSchemes", "authenticationRequired", "selectedProtocolVersion", "serverVersion", "sessionId"],
  runtime_state: ["actuationLock", "adapter", "aircraftConnection", "operatingProfile"],
  telemetry: ["altitudeM", "batteryPercent", "cameraRecording", "flightState", "gimbalPitchDeg", "latitude", "longitude", "sequence"],
  capability_snapshot: ["lastUpdated", "matrixId", "rows", "schemaVersion", "sourceDigestSha256"],
  lease_state: ["expiresInMs", "holderSessionId", "leaseId", "reason", "requestMessageId", "state"],
  command_ack: ["commandId", "decision", "intentDigestSha256", "reason"],
  command_result: ["commandId", "detail", "reason", "status"],
  protocol_error: ["code", "detail", "relatedMessageId"],
};
function exact(value, keys, label) {
  if (value === null || typeof value !== "object" || Array.isArray(value)) throw new Error(label + " must be an object");
  const actual = Object.keys(value).sort();
  const expected = [...keys].sort();
  if (JSON.stringify(actual) !== JSON.stringify(expected)) throw new Error(label + " has unexpected or missing fields");
}
export function decodeServerConsoleMessage(text) {
  const envelope = JSON.parse(text);
  exact(envelope, envelopeKeys, "console envelope");
  if (envelope.protocolVersion !== "1.0") throw new Error("unsupported protocol version");
  if (typeof envelope.messageId !== "string" || envelope.messageId.length === 0) throw new Error("invalid messageId");
  const expectedPayloadKeys = payloadKeys[envelope.type];
  if (expectedPayloadKeys === undefined) throw new Error("invalid server message type");
  exact(envelope.payload, expectedPayloadKeys, envelope.type + " payload");
  if (envelope.type === "capability_snapshot") {
    if (!Array.isArray(envelope.payload.rows)) throw new Error("rows must be an array");
    for (const row of envelope.payload.rows) exact(row, ["assessment", "id", "status"], "capability row");
  }
  if (envelope.type === "telemetry" && (!Number.isSafeInteger(envelope.payload.sequence) || envelope.payload.sequence < 1)) {
    throw new Error("invalid telemetry sequence");
  }
  return envelope;
}
`;

async function createFixtureAssets(t) {
  const temporaryDirectory = await mkdtemp(path.join(os.tmpdir(), "forwarded-console-test-"));
  t.after(() => rm(temporaryDirectory, { recursive: true, force: true }));
  const decoderPath = path.join(temporaryDirectory, "console-protocol.mjs");
  const matrixPath = path.join(temporaryDirectory, "g520-stack.json");
  const rows = REQUIRED_G520_CAPABILITY_IDS.map((id) => ({
    id,
    title: `Fixture ${id}`,
    status: "UNKNOWN",
    assessment: `No first-hand fixture evidence for ${id}.`,
    verificationMethod: "Fixture verification method.",
    trackingIssues: [2],
    evidenceRefs: [],
  }));
  const matrixBytes = Buffer.from(`${JSON.stringify({
    schemaVersion: 1,
    matrixId: "mini4pro-rcn3-g520-android",
    targetStack: {
      aircraft: "DJI Mini 4 Pro",
      remoteController: "DJI RC-N3",
      host: "MediaTek Genio 520 (Android)",
    },
    lastUpdated: "2026-08-15",
    rows,
    evidenceRecords: [],
  }, null, 2)}\n`, "utf8");
  await writeFile(decoderPath, STRICT_DECODER_SOURCE, "utf8");
  await writeFile(matrixPath, matrixBytes);
  return {
    decoderPath,
    matrixPath,
    matrixRows: rows.map(({ id, status, assessment }) => ({ id, status, assessment })),
    matrixRawSha256: createHash("sha256").update(matrixBytes).digest("hex"),
  };
}

function decodeClientFrames(buffer) {
  const messages = [];
  let offset = 0;
  while (buffer.length - offset >= 2) {
    const start = offset;
    const firstByte = buffer[offset];
    const secondByte = buffer[offset + 1];
    const masked = (secondByte & 0x80) !== 0;
    let payloadLength = secondByte & 0x7f;
    offset += 2;
    if (payloadLength === 126) {
      if (buffer.length - offset < 2) return { messages, remainder: buffer.subarray(start) };
      payloadLength = buffer.readUInt16BE(offset);
      offset += 2;
    } else if (payloadLength === 127) {
      if (buffer.length - offset < 8) return { messages, remainder: buffer.subarray(start) };
      payloadLength = Number(buffer.readBigUInt64BE(offset));
      offset += 8;
    }
    if (!masked || buffer.length - offset < 4 + payloadLength) {
      if (!masked) throw new Error("fake server received an unmasked client frame");
      return { messages, remainder: buffer.subarray(start) };
    }
    const maskKey = buffer.subarray(offset, offset + 4);
    offset += 4;
    const payload = Buffer.from(buffer.subarray(offset, offset + payloadLength));
    for (let index = 0; index < payload.length; index += 1) payload[index] ^= maskKey[index % 4];
    offset += payloadLength;
    messages.push({ opcode: firstByte & 0x0f, payload });
  }
  return { messages, remainder: buffer.subarray(offset) };
}

function fakeEnvelope(messageId, type, payload, extra = {}) {
  return {
    protocolVersion: "1.0",
    messageId,
    type,
    payload,
    ...extra,
  };
}

async function startFakeConsoleServer({
  assets,
  wrongOriginStatus = 403,
  invalidEnvelope = false,
  digestMismatch = false,
  preResultTelemetry = false,
  postTelemetry = "valid",
}) {
  const sockets = new Set();
  const server = net.createServer((socket) => {
    sockets.add(socket);
    socket.on("close", () => sockets.delete(socket));
    // Negative-path clients deliberately destroy the connection as soon as a contract mismatch is
    // proven. The fake server may still be flushing its scripted batch; absorb that expected peer
    // reset so it cannot turn the assertion into an unrelated process-level error.
    socket.on("error", (error) => {
      assert.ok(
        error.code === "ECONNRESET" || error.code === "EPIPE",
        `fake server observed unexpected socket error ${error.code ?? error.message}`,
      );
    });
    let buffer = Buffer.alloc(0);
    let upgraded = false;
    let clientFrameBuffer = Buffer.alloc(0);

    const sendHttp = (statusCode, webSocketKey) => {
      if (statusCode === 101) {
        socket.write(Buffer.from([
          "HTTP/1.1 101 Switching Protocols",
          "Upgrade: websocket",
          "Connection: Upgrade",
          `Sec-WebSocket-Accept: ${expectedWebSocketAccept(webSocketKey)}`,
          "",
          "",
        ].join("\r\n"), "ascii"));
      } else {
        socket.end(Buffer.from([
          `HTTP/1.1 ${statusCode} Forbidden`,
          "Content-Length: 0",
          "Connection: close",
          "",
          "",
        ].join("\r\n"), "ascii"));
      }
    };
    const send = (envelope) => socket.write(serverFrame(OPCODE_TEXT, Buffer.from(JSON.stringify(envelope))));
    const onClientMessage = (envelope) => {
      if (envelope.type === "client_hello") {
        if (invalidEnvelope) {
          send(fakeEnvelope("server-hello-invalid", "server_hello", {
            sessionId: "fake-session",
            serverVersion: "test",
            selectedProtocolVersion: "1.0",
            authenticationRequired: false,
            acceptedAuthenticationSchemes: [],
          }, { unexpected: true }));
          return;
        }
        send(fakeEnvelope("server-hello", "server_hello", {
          sessionId: "fake-session",
          serverVersion: "test",
          selectedProtocolVersion: "1.0",
          authenticationRequired: false,
          acceptedAuthenticationSchemes: [],
        }));
        send(fakeEnvelope("runtime", "runtime_state", {
          adapter: "mock",
          aircraftConnection: "connected",
          actuationLock: "unlocked",
          operatingProfile: "localhost_development",
        }));
        send(fakeEnvelope("telemetry-initial", "telemetry", {
          sequence: 1,
          batteryPercent: 87,
          latitude: null,
          longitude: null,
          altitudeM: 0,
          flightState: "grounded",
          gimbalPitchDeg: 0,
          cameraRecording: false,
        }));
        send(fakeEnvelope("capability", "capability_snapshot", {
          matrixId: "mini4pro-rcn3-g520-android",
          schemaVersion: 1,
          lastUpdated: "2026-08-15",
          sourceDigestSha256: assets.matrixRawSha256,
          rows: assets.matrixRows,
        }));
      } else if (envelope.type === "lease_acquire") {
        send(fakeEnvelope("lease-held", "lease_state", {
          requestMessageId: envelope.messageId,
          state: "held",
          leaseId: "fake-lease",
          holderSessionId: "fake-session",
          expiresInMs: 10_000,
          reason: null,
        }));
      } else if (envelope.type === "command_request") {
        const digest = computeDiscreteIntentDigest(envelope.payload);
        send(fakeEnvelope("command-ack", "command_ack", {
          commandId: envelope.payload.commandId,
          decision: "accepted",
          reason: null,
          intentDigestSha256: digestMismatch ? "0".repeat(64) : digest,
        }));
        if (preResultTelemetry) {
          send(fakeEnvelope("telemetry-before-result", "telemetry", {
            sequence: 2,
            batteryPercent: 86,
            latitude: null,
            longitude: null,
            altitudeM: 0.2,
            flightState: "taking_off",
            gimbalPitchDeg: 0,
            cameraRecording: false,
          }));
        }
        send(fakeEnvelope("command-result", "command_result", {
          commandId: envelope.payload.commandId,
          status: "succeeded",
          reason: null,
          detail: null,
        }));
        if (postTelemetry !== "missing") {
          send(fakeEnvelope("telemetry-post", "telemetry", {
            sequence: postTelemetry === "stale_sequence" ? 1 : 2,
            batteryPercent: 86,
            latitude: null,
            longitude: null,
            altitudeM: postTelemetry === "wrong_state" ? 0 : 0.2,
            flightState: postTelemetry === "wrong_state" ? "grounded" : "taking_off",
            gimbalPitchDeg: 0,
            cameraRecording: false,
          }));
        }
      }
    };

    socket.on("data", (chunk) => {
      if (!upgraded) {
        buffer = Buffer.concat([buffer, chunk]);
        const headerEnd = buffer.indexOf("\r\n\r\n");
        if (headerEnd < 0) return;
        const request = buffer.subarray(0, headerEnd).toString("latin1");
        const key = /^Sec-WebSocket-Key: (.+)$/imu.exec(request)?.[1]?.trim();
        assert.ok(key, "fake server expected a WebSocket key");
        const origin = /^Origin: (.+)$/imu.exec(request)?.[1]?.trim() ?? null;
        const address = server.address();
        assert.notEqual(address, null);
        const expectedOrigin = `http://127.0.0.1:${address.port}`;
        if (origin === null) {
          sendHttp(403, key);
          return;
        }
        if (origin !== expectedOrigin) {
          if (wrongOriginStatus === "tcp_close") {
            socket.destroy();
            return;
          }
          sendHttp(wrongOriginStatus, key);
          if (wrongOriginStatus === 101) socket.end();
          return;
        }
        upgraded = true;
        sendHttp(101, key);
        clientFrameBuffer = Buffer.from(buffer.subarray(headerEnd + 4));
        buffer = Buffer.alloc(0);
      } else {
        clientFrameBuffer = Buffer.concat([clientFrameBuffer, chunk]);
      }
      if (!upgraded || clientFrameBuffer.length === 0) return;
      const decoded = decodeClientFrames(clientFrameBuffer);
      clientFrameBuffer = Buffer.from(decoded.remainder);
      for (const frame of decoded.messages) {
        if (frame.opcode === OPCODE_TEXT) onClientMessage(JSON.parse(frame.payload.toString("utf8")));
      }
    });
  });
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  const address = server.address();
  assert.notEqual(address, null);
  return {
    port: address.port,
    async close() {
      for (const socket of sockets) socket.destroy();
      await new Promise((resolve) => server.close(resolve));
    },
  };
}

test("RFC 6455 accept digest and Upgrade request include the exact localhost Origin", () => {
  const key = "dGhlIHNhbXBsZSBub25jZQ==";
  assert.equal(expectedWebSocketAccept(key), "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=");

  const request = buildUpgradeRequest({ webSocketKey: key }).toString("ascii");
  assert.match(request, /^GET \/api\/console\/v1 HTTP\/1\.1\r\n/u);
  assert.equal(request.match(/^Origin: http:\/\/127\.0\.0\.1:18080$/gmu)?.length, 1);
  assert.equal(request.match(/^Sec-WebSocket-Key: /gmu)?.length, 1);
  assert.equal(request.endsWith("\r\n\r\n"), true);
});

test("Upgrade parser verifies status and accept while retaining the first WebSocket bytes", () => {
  const key = "dGhlIHNhbXBsZSBub25jZQ==";
  const firstFrame = serverFrame(OPCODE_TEXT, Buffer.from("hello"));
  const response = Buffer.concat([
    Buffer.from(
      [
        "HTTP/1.1 101 Switching Protocols",
        "Upgrade: websocket",
        "Connection: keep-alive, Upgrade",
        `Sec-WebSocket-Accept: ${expectedWebSocketAccept(key)}`,
        "",
        "",
      ].join("\r\n"),
      "ascii",
    ),
    firstFrame,
  ]);

  const parsed = parseUpgradeResponse(response, key);
  assert.equal(parsed?.statusCode, 101);
  assert.equal(parsed?.acceptVerified, true);
  assert.deepEqual(parsed?.remainder, firstFrame);
  assert.throws(
    () => parseUpgradeResponse(Buffer.from(response.toString("latin1").replace(expectedWebSocketAccept(key), "wrong"), "latin1"), key),
    /Sec-WebSocket-Accept/u,
  );
});

test("rejected Upgrade parser requires explicit HTTP 403 and rejects an accidental 101", () => {
  const forbidden = Buffer.from("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n", "ascii");
  const accepted = Buffer.from("HTTP/1.1 101 Switching Protocols\r\n\r\n", "ascii");
  assert.equal(parseRejectedUpgradeResponse(forbidden, "wrong-Origin")?.statusCode, 403);
  assert.throws(
    () => parseRejectedUpgradeResponse(accepted, "wrong-Origin"),
    /wrong-Origin.*HTTP 101.*explicit HTTP 403/u,
  );
});

test("discrete intent digest matches the Kotlin ConsoleIntentDigest canonical fixture", () => {
  assert.equal(
    computeDiscreteIntentDigest({
      commandId: "command-takeoff-001",
      leaseId: "lease-primary-001",
      action: "takeoff",
      ttlMs: 5_000,
    }),
    "707a5ad01589dda74b7cf8240c10aec56ad1fffaf440ed02204276e2f4158487",
  );
});

test("client frame encoder masks payloads across 125, 126, and 127 length encodings", () => {
  const maskKey = Buffer.from([0x11, 0x22, 0x33, 0x44]);
  for (const payloadLength of [125, 126, 65_536]) {
    const payload = Buffer.alloc(payloadLength, payloadLength & 0xff);
    const decoded = decodeSingleClientFrame(
      encodeClientFrame(OPCODE_TEXT, payload, { maskKey }),
    );
    assert.equal(decoded.opcode, OPCODE_TEXT);
    assert.equal(decoded.fin, true);
    assert.equal(decoded.lengthCode, payloadLength <= 125 ? payloadLength : payloadLength <= 0xffff ? 126 : 127);
    assert.deepEqual(decoded.payload, payload);
  }
});

test("server frame parser handles 125, 126, and 127 lengths plus an incomplete tail", () => {
  const frame125 = serverFrame(OPCODE_TEXT, Buffer.alloc(125, 0x61));
  const frame126 = serverFrame(OPCODE_TEXT, Buffer.alloc(126, 0x62));
  const frame127 = serverFrame(OPCODE_TEXT, Buffer.alloc(65_536, 0x63));
  const splitAt = frame127.length - 13;
  const firstPass = decodeServerFrames(
    Buffer.concat([frame125, frame126, frame127.subarray(0, splitAt)]),
  );

  assert.deepEqual(firstPass.frames.map((frame) => frame.payload.length), [125, 126]);
  assert.deepEqual(firstPass.remainder, frame127.subarray(0, splitAt));

  const secondPass = decodeServerFrames(
    Buffer.concat([firstPass.remainder, frame127.subarray(splitAt)]),
  );
  assert.deepEqual(secondPass.frames.map((frame) => frame.payload.length), [65_536]);
  assert.equal(secondPass.remainder.length, 0);
});

test("server frame parser rejects masked frames", () => {
  assert.throws(
    () => decodeServerFrames(serverFrame(OPCODE_TEXT, Buffer.from("masked"), { masked: true })),
    /must not be masked/u,
  );
});

test("connection answers server ping with a masked pong carrying the same payload", () => {
  const socket = new FakeSocket();
  const connection = new RawWebSocketConnection(socket);
  socket.emit("data", serverFrame(OPCODE_PING, Buffer.from("still-here")));

  assert.equal(socket.writes.length, 1);
  const pong = decodeSingleClientFrame(socket.writes[0]);
  assert.equal(pong.opcode, OPCODE_PONG);
  assert.deepEqual(pong.payload, Buffer.from("still-here"));
  connection.destroy();
});

test("connection reassembles fragmented UTF-8 text around an interleaved ping", async () => {
  const socket = new FakeSocket();
  const connection = new RawWebSocketConnection(socket);
  const utf8 = Buffer.from('{"type":"測試"}', "utf8");
  socket.emit("data", serverFrame(OPCODE_TEXT, utf8.subarray(0, 8), { fin: false }));
  socket.emit("data", serverFrame(OPCODE_PING, Buffer.from("p")));
  socket.emit("data", serverFrame(0x0, utf8.subarray(8)));

  assert.equal(await connection.nextText(100), '{"type":"測試"}');
  assert.equal(decodeSingleClientFrame(socket.writes[0]).opcode, OPCODE_PONG);
  connection.destroy();
});

test("health check catches an invalid frame following the final text in one TCP chunk", async () => {
  const socket = new FakeSocket();
  const connection = new RawWebSocketConnection(socket);
  const finalText = connection.nextText(100);
  socket.emit(
    "data",
    Buffer.concat([
      serverFrame(OPCODE_TEXT, Buffer.from("final-result")),
      serverFrame(0x2, Buffer.from("unexpected-binary")),
    ]),
  );

  assert.equal(await finalText, "final-result");
  assert.throws(() => connection.requireHealthy(), /unexpected binary/u);
  assert.equal(socket.destroyed, true);
});

test("command wait fails closed when result arrives before acknowledgement", async () => {
  const commandId = "smoke-takeoff-order-test";
  const result = JSON.stringify({
    protocolVersion: "1.0",
    messageId: "result-before-ack",
    type: "command_result",
    payload: { commandId, status: "succeeded", reason: null, detail: null },
  });
  const connection = {
    async nextText() {
      return result;
    },
  };
  const reader = new ConsoleEnvelopeReader(connection, parseServerFixture);

  await assert.rejects(
    reader.waitFor(
      (envelope) => envelope.type === "command_ack" && envelope.payload.commandId === commandId,
      Date.now() + 1_000,
      "command acknowledgement",
      {
        rejectIf: (envelope) =>
          envelope.type === "command_result" && envelope.payload.commandId === commandId,
        rejectionMessage: "Takeoff command_result arrived before command_ack",
      },
    ),
    /command_result arrived before command_ack/u,
  );
});

test("console protocol errors fail closed with the canonical detail field", async () => {
  const connection = {
    async nextText() {
      return JSON.stringify({
        protocolVersion: "1.0",
        messageId: "protocol-error-test",
        type: "protocol_error",
        payload: {
          relatedMessageId: "bad-message-test",
          code: "invalid_payload",
          detail: "Payload does not match the message schema.",
        },
      });
    },
  };
  const reader = new ConsoleEnvelopeReader(connection, parseServerFixture);

  await assert.rejects(
    reader.next(Date.now() + 1_000),
    /invalid_payload: Payload does not match the message schema\./u,
  );
});

test("dynamically loaded canonical decoder rejects an envelope with an extra field", async (t) => {
  const assets = await createFixtureAssets(t);
  const decoder = await loadCanonicalServerDecoder(assets.decoderPath);
  assert.throws(
    () => decoder.decodeServerMessage(JSON.stringify(fakeEnvelope(
      "strict-envelope",
      "runtime_state",
      {
        adapter: "mock",
        aircraftConnection: "connected",
        actuationLock: "unlocked",
        operatingProfile: "localhost_development",
      },
      { unexpected: true },
    ))),
    /unexpected or missing fields/u,
  );
});

test("capability evidence requires raw SHA and exact canonical row assessment", async (t) => {
  const assets = await createFixtureAssets(t);
  const canonicalMatrix = await loadCanonicalCapabilityMatrix(assets.matrixPath);
  const payload = {
    matrixId: canonicalMatrix.matrixId,
    schemaVersion: canonicalMatrix.schemaVersion,
    lastUpdated: canonicalMatrix.lastUpdated,
    sourceDigestSha256: canonicalMatrix.rawSha256,
    rows: canonicalMatrix.rows.map((row) => ({ ...row })),
  };
  requireCapabilitySnapshotMatchesCanonical({ payload }, canonicalMatrix);
  assert.throws(
    () => requireCapabilitySnapshotMatchesCanonical({
      payload: { ...payload, sourceDigestSha256: "0".repeat(64) },
    }, canonicalMatrix),
    /raw source SHA-256 did not match/u,
  );
  const changedRows = payload.rows.map((row) => ({ ...row }));
  changedRows[0].assessment = "Different assessment.";
  assert.throws(
    () => requireCapabilitySnapshotMatchesCanonical({
      payload: { ...payload, rows: changedRows },
    }, canonicalMatrix),
    /assessment did not match/u,
  );
});

test("fake server cannot turn a wrong-Origin probe into success with HTTP 101", async (t) => {
  const assets = await createFixtureAssets(t);
  const fakeServer = await startFakeConsoleServer({ assets, wrongOriginStatus: 101 });
  t.after(() => fakeServer.close());
  await assert.rejects(
    verifyForwardedConsole({
      host: "127.0.0.1",
      port: fakeServer.port,
      decoderPath: assets.decoderPath,
      matrixPath: assets.matrixPath,
      handshakeTimeoutMs: 500,
      smokeTimeoutMs: 1_500,
    }),
    /wrong-Origin.*HTTP 101.*explicit HTTP 403/u,
  );
});

test("fake server TCP close cannot masquerade as an explicit wrong-Origin rejection", async (t) => {
  const assets = await createFixtureAssets(t);
  const fakeServer = await startFakeConsoleServer({ assets, wrongOriginStatus: "tcp_close" });
  t.after(() => fakeServer.close());
  await assert.rejects(
    verifyForwardedConsole({
      host: "127.0.0.1",
      port: fakeServer.port,
      decoderPath: assets.decoderPath,
      matrixPath: assets.matrixPath,
      handshakeTimeoutMs: 500,
      smokeTimeoutMs: 1_500,
    }),
    /closed before an explicit HTTP 403/u,
  );
});

test("fake server strict invalid envelope fails canonical runtime decoding", async (t) => {
  const assets = await createFixtureAssets(t);
  const fakeServer = await startFakeConsoleServer({ assets, invalidEnvelope: true });
  t.after(() => fakeServer.close());
  await assert.rejects(
    verifyForwardedConsole({
      host: "127.0.0.1",
      port: fakeServer.port,
      decoderPath: assets.decoderPath,
      matrixPath: assets.matrixPath,
      handshakeTimeoutMs: 500,
      smokeTimeoutMs: 1_500,
    }),
    /Canonical console decoder rejected.*unexpected or missing fields/u,
  );
});

test("fake server digest mismatch fails before takeoff can be reported as verified", async (t) => {
  const assets = await createFixtureAssets(t);
  const fakeServer = await startFakeConsoleServer({ assets, digestMismatch: true });
  t.after(() => fakeServer.close());
  await assert.rejects(
    verifyForwardedConsole({
      host: "127.0.0.1",
      port: fakeServer.port,
      decoderPath: assets.decoderPath,
      matrixPath: assets.matrixPath,
      handshakeTimeoutMs: 500,
      smokeTimeoutMs: 1_500,
    }),
    /intent digest did not match ConsoleIntentDigest canonical JSON/u,
  );
});

test("fake server pre-result telemetry cannot satisfy post-command runtime evidence", async (t) => {
  const assets = await createFixtureAssets(t);
  const fakeServer = await startFakeConsoleServer({
    assets,
    preResultTelemetry: true,
    postTelemetry: "missing",
  });
  t.after(() => fakeServer.close());
  await assert.rejects(
    verifyForwardedConsole({
      host: "127.0.0.1",
      port: fakeServer.port,
      decoderPath: assets.decoderPath,
      matrixPath: assets.matrixPath,
      handshakeTimeoutMs: 300,
      smokeTimeoutMs: 600,
    }),
    /Timed out|smoke verification timed out/u,
  );
});

test("fake server success requires exact matrix bytes, canonical digest, and post-takeoff telemetry", async (t) => {
  const assets = await createFixtureAssets(t);
  const fakeServer = await startFakeConsoleServer({ assets });
  t.after(() => fakeServer.close());
  const summary = await verifyForwardedConsole({
    host: "127.0.0.1",
    port: fakeServer.port,
    decoderPath: assets.decoderPath,
    matrixPath: assets.matrixPath,
    handshakeTimeoutMs: 500,
    smokeTimeoutMs: 1_500,
  });

  assert.equal(summary.ok, true);
  assert.deepEqual(summary.originAdmission, {
    wrongOrigin: `http://127.0.0.1:${fakeServer.port + 1}`,
    wrongOriginStatusCode: 403,
    missingOriginStatusCode: 403,
  });
  assert.equal(summary.capability.rawSourceDigestSha256, assets.matrixRawSha256);
  assert.equal(summary.capability.rowCount, 17);
  assert.equal(summary.capability.exactCanonicalRows, true);
  assert.equal(summary.command.intentDigestSha256, summary.command.expectedIntentDigestSha256);
  assert.equal(summary.telemetry.initialSequence, 1);
  assert.equal(summary.telemetry.postCommandSequenceFloor, 1);
  assert.equal(summary.telemetry.postCommandSequence, 2);
  assert.equal(summary.telemetry.postCommandFlightState, "taking_off");
});

test("CLI validation keeps stdout empty and reports JSON failure on stderr", () => {
  const scriptPath = fileURLToPath(new URL("./verify-forwarded-console.mjs", import.meta.url));
  const result = spawnSync(
    process.execPath,
    [scriptPath, "0.0.0.0", "18080", "/not/read.mjs", "/not/read.json"],
    {
      encoding: "utf8",
    },
  );

  assert.equal(result.status, 1);
  assert.equal(result.stdout, "");
  const failure = JSON.parse(result.stderr);
  assert.equal(failure.ok, false);
  assert.match(failure.error, /only permits host 127\.0\.0\.1/u);
});
