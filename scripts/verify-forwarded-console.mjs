#!/usr/bin/env node

import { createHash, randomBytes, randomUUID } from "node:crypto";
import { readFile } from "node:fs/promises";
import net from "node:net";
import path from "node:path";
import { pathToFileURL } from "node:url";
import { TextDecoder } from "node:util";

const DEFAULT_HOST = "127.0.0.1";
const DEFAULT_PORT = 18080;
const CONSOLE_PATH = "/api/console/v1";
const CONSOLE_ORIGIN = "http://127.0.0.1:18080";
const CONSOLE_PROTOCOL_VERSION = "1.0";
const G520_MATRIX_ID = "mini4pro-rcn3-g520-android";
export const REQUIRED_G520_CAPABILITY_IDS = Object.freeze([
  "battery",
  "gps_position",
  "flight_state",
  "gimbal_camera_state",
  "stream_capability",
  "headless_boot_service",
  "point_to_point_ethernet",
  "rcn3_usb_attach",
  "msdk_registration_activation",
  "aircraft_connection",
  "rcn3_four_axis_input",
  "takeoff_actuation",
  "landing_actuation",
  "rth_actuation",
  "virtual_stick_actuation",
  "continuous_control_neutralization",
  "opencv_on_device_recognition",
]);
const MAX_HTTP_HEADER_BYTES = 16 * 1024;
const MAX_SERVER_FRAME_BYTES = 64 * 1024;
const HANDSHAKE_TIMEOUT_MS = 5_000;
const SMOKE_TIMEOUT_MS = 20_000;
const WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

export const OPCODE_CONTINUATION = 0x0;
export const OPCODE_TEXT = 0x1;
export const OPCODE_BINARY = 0x2;
export const OPCODE_CLOSE = 0x8;
export const OPCODE_PING = 0x9;
export const OPCODE_PONG = 0xa;

export class SmokeVerificationError extends Error {
  constructor(message) {
    super(message);
    this.name = "SmokeVerificationError";
  }
}

function requireCondition(condition, message) {
  if (!condition) throw new SmokeVerificationError(message);
}

function toPayloadBuffer(payload) {
  if (Buffer.isBuffer(payload)) return payload;
  if (payload instanceof Uint8Array) return Buffer.from(payload);
  return Buffer.from(String(payload), "utf8");
}

function websocketLengthHeader(payloadLength, masked) {
  requireCondition(
    Number.isSafeInteger(payloadLength) && payloadLength >= 0,
    "WebSocket payload length must be a non-negative safe integer",
  );

  const maskBit = masked ? 0x80 : 0;
  if (payloadLength <= 125) {
    return Buffer.from([maskBit | payloadLength]);
  }
  if (payloadLength <= 0xffff) {
    const header = Buffer.allocUnsafe(3);
    header[0] = maskBit | 126;
    header.writeUInt16BE(payloadLength, 1);
    return header;
  }

  const header = Buffer.allocUnsafe(9);
  header[0] = maskBit | 127;
  header.writeBigUInt64BE(BigInt(payloadLength), 1);
  return header;
}

export function expectedWebSocketAccept(webSocketKey) {
  return createHash("sha1")
    .update(`${webSocketKey}${WEBSOCKET_GUID}`, "ascii")
    .digest("base64");
}

export function buildUpgradeRequest({
  host = DEFAULT_HOST,
  port = DEFAULT_PORT,
  path = CONSOLE_PATH,
  origin = CONSOLE_ORIGIN,
  webSocketKey,
} = {}) {
  requireCondition(typeof webSocketKey === "string" && webSocketKey.length > 0, "Missing WebSocket key");
  requireCondition(!/[\r\n]/u.test(webSocketKey), "Invalid WebSocket key");
  requireCondition(!/[\r\n]/u.test(host), "Invalid WebSocket host");
  requireCondition(!/[\r\n]/u.test(path), "Invalid WebSocket path");
  requireCondition(origin === null || typeof origin === "string", "Invalid WebSocket Origin");
  requireCondition(origin === null || !/[\r\n]/u.test(origin), "Invalid WebSocket Origin");

  return Buffer.from(
    [
      `GET ${path} HTTP/1.1`,
      `Host: ${host}:${port}`,
      "Upgrade: websocket",
      "Connection: Upgrade",
      `Sec-WebSocket-Key: ${webSocketKey}`,
      "Sec-WebSocket-Version: 13",
      ...(origin === null ? [] : [`Origin: ${origin}`]),
      "",
      "",
    ].join("\r\n"),
    "ascii",
  );
}

function parseHttpHeaders(lines) {
  const headers = new Map();
  for (const line of lines) {
    const separator = line.indexOf(":");
    requireCondition(separator > 0, `Malformed HTTP Upgrade header: ${line}`);
    const name = line.slice(0, separator).trim().toLowerCase();
    const value = line.slice(separator + 1).trim();
    requireCondition(name.length > 0, "HTTP Upgrade response contained an empty header name");
    const values = headers.get(name) ?? [];
    values.push(value);
    headers.set(name, values);
  }
  return headers;
}

function requireSingleHeader(headers, name) {
  const values = headers.get(name) ?? [];
  requireCondition(values.length === 1, `HTTP Upgrade response must contain exactly one ${name} header`);
  return values[0];
}

export function parseHttpResponse(buffer) {
  const headerEnd = buffer.indexOf("\r\n\r\n");
  if (headerEnd < 0) return null;

  const headerText = buffer.subarray(0, headerEnd).toString("latin1");
  const lines = headerText.split("\r\n");
  const statusLine = lines.shift() ?? "";
  const statusMatch = /^HTTP\/1\.[01] ([0-9]{3})(?: |$)/u.exec(statusLine);
  requireCondition(statusMatch !== null, `Malformed HTTP status line: ${statusLine}`);
  return {
    statusCode: Number(statusMatch[1]),
    headers: parseHttpHeaders(lines),
    remainder: Buffer.from(buffer.subarray(headerEnd + 4)),
  };
}

export function parseUpgradeResponse(buffer, webSocketKey) {
  const parsed = parseHttpResponse(buffer);
  if (parsed === null) return null;
  const { statusCode, headers, remainder } = parsed;
  requireCondition(statusCode === 101, `WebSocket Upgrade returned HTTP ${statusCode}, expected 101`);

  const upgrade = requireSingleHeader(headers, "upgrade");
  requireCondition(upgrade.toLowerCase() === "websocket", "HTTP Upgrade response did not select WebSocket");
  const connection = requireSingleHeader(headers, "connection");
  requireCondition(
    connection.split(",").some((token) => token.trim().toLowerCase() === "upgrade"),
    "HTTP Upgrade response Connection header did not contain Upgrade",
  );
  const accept = requireSingleHeader(headers, "sec-websocket-accept");
  requireCondition(
    accept === expectedWebSocketAccept(webSocketKey),
    "HTTP Upgrade response Sec-WebSocket-Accept did not match the request key",
  );

  return {
    statusCode,
    acceptVerified: true,
    remainder,
  };
}

export function parseRejectedUpgradeResponse(buffer, description) {
  const parsed = parseHttpResponse(buffer);
  if (parsed === null) return null;
  requireCondition(
    parsed.statusCode === 403,
    `${description} WebSocket Upgrade returned HTTP ${parsed.statusCode}, expected explicit HTTP 403`,
  );
  return { statusCode: parsed.statusCode };
}

export function encodeClientFrame(
  opcode,
  payload = Buffer.alloc(0),
  { fin = true, maskKey = randomBytes(4) } = {},
) {
  requireCondition(Number.isInteger(opcode) && opcode >= 0 && opcode <= 0xf, "Invalid WebSocket opcode");
  requireCondition(Buffer.isBuffer(maskKey) && maskKey.length === 4, "WebSocket mask key must be four bytes");
  const payloadBuffer = toPayloadBuffer(payload);
  const controlFrame = opcode >= OPCODE_CLOSE;
  requireCondition(!controlFrame || fin, "WebSocket control frames must not be fragmented");
  requireCondition(!controlFrame || payloadBuffer.length <= 125, "WebSocket control frame payload exceeds 125 bytes");

  const firstByte = (fin ? 0x80 : 0) | opcode;
  const lengthHeader = websocketLengthHeader(payloadBuffer.length, true);
  const maskedPayload = Buffer.allocUnsafe(payloadBuffer.length);
  for (let index = 0; index < payloadBuffer.length; index += 1) {
    maskedPayload[index] = payloadBuffer[index] ^ maskKey[index % 4];
  }
  return Buffer.concat([Buffer.from([firstByte]), lengthHeader, maskKey, maskedPayload]);
}

function decodeExtendedLength(buffer, offset, lengthCode) {
  if (lengthCode <= 125) return { payloadLength: lengthCode, offset };
  if (lengthCode === 126) {
    if (buffer.length < offset + 2) return null;
    return { payloadLength: buffer.readUInt16BE(offset), offset: offset + 2 };
  }
  if (buffer.length < offset + 8) return null;
  const payloadLengthBigInt = buffer.readBigUInt64BE(offset);
  requireCondition(
    payloadLengthBigInt <= BigInt(Number.MAX_SAFE_INTEGER),
    "WebSocket payload length exceeds the JavaScript safe-integer range",
  );
  return { payloadLength: Number(payloadLengthBigInt), offset: offset + 8 };
}

export function decodeServerFrames(buffer, maxPayloadBytes = MAX_SERVER_FRAME_BYTES) {
  requireCondition(Buffer.isBuffer(buffer), "WebSocket parser input must be a Buffer");
  requireCondition(
    Number.isSafeInteger(maxPayloadBytes) && maxPayloadBytes >= 0,
    "Invalid maximum WebSocket payload size",
  );

  const frames = [];
  let offset = 0;
  while (buffer.length - offset >= 2) {
    const frameStart = offset;
    const firstByte = buffer[offset];
    const secondByte = buffer[offset + 1];
    const fin = (firstByte & 0x80) !== 0;
    const reservedBits = firstByte & 0x70;
    const opcode = firstByte & 0x0f;
    const masked = (secondByte & 0x80) !== 0;
    const lengthCode = secondByte & 0x7f;
    offset += 2;

    requireCondition(reservedBits === 0, "Server WebSocket frame used unsupported RSV bits");
    requireCondition(!masked, "Server WebSocket frame must not be masked");
    requireCondition(
      [OPCODE_CONTINUATION, OPCODE_TEXT, OPCODE_BINARY, OPCODE_CLOSE, OPCODE_PING, OPCODE_PONG].includes(opcode),
      `Server WebSocket frame used unsupported opcode ${opcode}`,
    );

    const decodedLength = decodeExtendedLength(buffer, offset, lengthCode);
    if (decodedLength === null) {
      return { frames, remainder: Buffer.from(buffer.subarray(frameStart)) };
    }
    const { payloadLength } = decodedLength;
    offset = decodedLength.offset;

    const controlFrame = opcode >= OPCODE_CLOSE;
    requireCondition(!controlFrame || fin, "Server WebSocket control frame was fragmented");
    requireCondition(!controlFrame || payloadLength <= 125, "Server WebSocket control frame exceeded 125 bytes");
    requireCondition(payloadLength <= maxPayloadBytes, `Server WebSocket frame exceeded ${maxPayloadBytes} bytes`);
    if (buffer.length - offset < payloadLength) {
      return { frames, remainder: Buffer.from(buffer.subarray(frameStart)) };
    }

    frames.push({
      fin,
      opcode,
      payload: Buffer.from(buffer.subarray(offset, offset + payloadLength)),
    });
    offset += payloadLength;
  }

  return { frames, remainder: Buffer.from(buffer.subarray(offset)) };
}

export class RawWebSocketConnection {
  constructor(socket, initialData = Buffer.alloc(0), { maxPayloadBytes = MAX_SERVER_FRAME_BYTES } = {}) {
    this.socket = socket;
    this.maxPayloadBytes = maxPayloadBytes;
    this.buffer = Buffer.alloc(0);
    this.textQueue = [];
    this.waiters = [];
    this.failure = null;
    this.closed = false;
    this.locallyClosed = false;
    this.fragmentOpcode = null;
    this.fragmentBuffers = [];
    this.fragmentBytes = 0;
    this.utf8Decoder = new TextDecoder("utf-8", { fatal: true });

    socket.on("data", (chunk) => this.consume(toPayloadBuffer(chunk)));
    socket.on("error", (error) => this.fail(error));
    socket.on("end", () => this.handleTransportClose("WebSocket transport ended"));
    socket.on("close", () => this.handleTransportClose("WebSocket transport closed"));
    if (initialData.length > 0) this.consume(initialData);
  }

  consume(chunk) {
    if (this.closed) return;
    try {
      this.buffer = Buffer.concat([this.buffer, chunk]);
      const decoded = decodeServerFrames(this.buffer, this.maxPayloadBytes);
      this.buffer = decoded.remainder;
      for (const frame of decoded.frames) {
        if (this.closed) break;
        this.handleFrame(frame);
      }
    } catch (error) {
      this.fail(error);
      this.socket.destroy();
    }
  }

  handleFrame(frame) {
    if (frame.opcode === OPCODE_PING) {
      this.socket.write(encodeClientFrame(OPCODE_PONG, frame.payload));
      return;
    }
    if (frame.opcode === OPCODE_PONG) return;
    if (frame.opcode === OPCODE_CLOSE) {
      if (!this.locallyClosed) this.socket.write(encodeClientFrame(OPCODE_CLOSE, frame.payload));
      this.fail(new SmokeVerificationError("Server closed the WebSocket before smoke verification completed"));
      this.socket.end();
      return;
    }
    if (frame.opcode === OPCODE_BINARY) {
      throw new SmokeVerificationError("Console server sent an unexpected binary WebSocket message");
    }

    if (frame.opcode === OPCODE_TEXT) {
      requireCondition(this.fragmentOpcode === null, "Server started a new data frame during fragmentation");
      if (frame.fin) {
        this.deliverText(this.decodeUtf8(frame.payload));
      } else {
        this.fragmentOpcode = OPCODE_TEXT;
        this.fragmentBuffers = [frame.payload];
        this.fragmentBytes = frame.payload.length;
      }
      return;
    }

    requireCondition(frame.opcode === OPCODE_CONTINUATION, "Unexpected WebSocket data opcode");
    requireCondition(this.fragmentOpcode === OPCODE_TEXT, "Server sent an unexpected continuation frame");
    this.fragmentBytes += frame.payload.length;
    requireCondition(
      this.fragmentBytes <= this.maxPayloadBytes,
      `Fragmented server WebSocket message exceeded ${this.maxPayloadBytes} bytes`,
    );
    this.fragmentBuffers.push(frame.payload);
    if (frame.fin) {
      const payload = Buffer.concat(this.fragmentBuffers, this.fragmentBytes);
      this.fragmentOpcode = null;
      this.fragmentBuffers = [];
      this.fragmentBytes = 0;
      this.deliverText(this.decodeUtf8(payload));
    }
  }

  decodeUtf8(payload) {
    try {
      return this.utf8Decoder.decode(payload);
    } catch {
      throw new SmokeVerificationError("Console server sent invalid UTF-8 in a text frame");
    }
  }

  deliverText(text) {
    const waiter = this.waiters.shift();
    if (waiter !== undefined) {
      clearTimeout(waiter.timer);
      waiter.resolve(text);
      return;
    }
    requireCondition(this.textQueue.length < 256, "Console message queue exceeded its safety bound");
    this.textQueue.push(text);
  }

  nextText(timeoutMs) {
    requireCondition(Number.isFinite(timeoutMs) && timeoutMs > 0, "WebSocket receive timeout must be positive");
    if (this.failure !== null) return Promise.reject(this.failure);
    if (this.textQueue.length > 0) return Promise.resolve(this.textQueue.shift());
    if (this.closed) return Promise.reject(new SmokeVerificationError("WebSocket is closed"));

    return new Promise((resolve, reject) => {
      const waiter = { resolve, reject, timer: null };
      waiter.timer = setTimeout(() => {
        const index = this.waiters.indexOf(waiter);
        if (index >= 0) this.waiters.splice(index, 1);
        reject(new SmokeVerificationError(`Timed out waiting for a console WebSocket message after ${timeoutMs} ms`));
      }, timeoutMs);
      this.waiters.push(waiter);
    });
  }

  sendJson(value) {
    requireCondition(!this.closed && this.failure === null, "Cannot write to a closed WebSocket");
    this.socket.write(encodeClientFrame(OPCODE_TEXT, JSON.stringify(value)));
  }

  requireHealthy() {
    if (this.failure !== null) throw this.failure;
    requireCondition(!this.closed, "WebSocket closed before smoke verification completed");
  }

  close() {
    if (this.closed) return;
    this.locallyClosed = true;
    this.closed = true;
    this.rejectWaiters(new SmokeVerificationError("WebSocket closed locally"));
    this.socket.write(encodeClientFrame(OPCODE_CLOSE, Buffer.from([0x03, 0xe8])));
    this.socket.end();
    this.socket.unref?.();
  }

  destroy() {
    this.locallyClosed = true;
    this.closed = true;
    this.rejectWaiters(new SmokeVerificationError("WebSocket destroyed locally"));
    this.socket.destroy();
  }

  handleTransportClose(message) {
    if (this.closed || this.locallyClosed) return;
    this.fail(new SmokeVerificationError(message));
  }

  fail(error) {
    if (this.failure !== null || this.locallyClosed) return;
    this.failure = error instanceof Error ? error : new SmokeVerificationError(String(error));
    this.closed = true;
    this.rejectWaiters(this.failure);
  }

  rejectWaiters(error) {
    for (const waiter of this.waiters.splice(0)) {
      clearTimeout(waiter.timer);
      waiter.reject(error);
    }
  }
}

export function expectRejectedWebSocketUpgrade({
  host = DEFAULT_HOST,
  port = DEFAULT_PORT,
  path: requestPath = CONSOLE_PATH,
  origin,
  description,
  timeoutMs = HANDSHAKE_TIMEOUT_MS,
} = {}) {
  requireCondition(origin === null || typeof origin === "string", "Rejected-upgrade probe needs an Origin or null");
  requireCondition(typeof description === "string" && description.length > 0, "Rejected-upgrade probe needs a description");

  return new Promise((resolve, reject) => {
    const webSocketKey = randomBytes(16).toString("base64");
    const socket = net.createConnection({ host, port });
    socket.setNoDelay(true);
    let responseBuffer = Buffer.alloc(0);
    let settled = false;

    const cleanup = () => {
      clearTimeout(timer);
      socket.off("connect", onConnect);
      socket.off("data", onData);
      socket.off("error", onError);
      socket.off("close", onClose);
    };
    const fail = (error) => {
      if (settled) return;
      settled = true;
      cleanup();
      socket.destroy();
      reject(error instanceof Error ? error : new SmokeVerificationError(String(error)));
    };
    const onConnect = () => {
      socket.write(buildUpgradeRequest({ host, port, path: requestPath, origin, webSocketKey }));
    };
    const onError = (error) => fail(
      new SmokeVerificationError(`${description} probe failed before an explicit HTTP 403: ${error.message}`),
    );
    const onClose = () => fail(
      new SmokeVerificationError(`${description} probe TCP connection closed before an explicit HTTP 403`),
    );
    const onData = (chunk) => {
      try {
        responseBuffer = Buffer.concat([responseBuffer, chunk]);
        const headerEnd = responseBuffer.indexOf("\r\n\r\n");
        requireCondition(
          headerEnd < 0
            ? responseBuffer.length <= MAX_HTTP_HEADER_BYTES
            : headerEnd + 4 <= MAX_HTTP_HEADER_BYTES,
          `HTTP rejection response headers exceeded ${MAX_HTTP_HEADER_BYTES} bytes`,
        );
        const rejection = parseRejectedUpgradeResponse(responseBuffer, description);
        if (rejection === null) return;
        settled = true;
        cleanup();
        socket.destroy();
        resolve(rejection);
      } catch (error) {
        fail(error);
      }
    };
    const timer = setTimeout(
      () => fail(new SmokeVerificationError(`Timed out waiting for ${description} HTTP 403 after ${timeoutMs} ms`)),
      timeoutMs,
    );

    socket.on("connect", onConnect);
    socket.on("data", onData);
    socket.on("error", onError);
    socket.on("close", onClose);
  });
}

export function connectRawWebSocket({
  host = DEFAULT_HOST,
  port = DEFAULT_PORT,
  path = CONSOLE_PATH,
  origin = CONSOLE_ORIGIN,
  timeoutMs = HANDSHAKE_TIMEOUT_MS,
} = {}) {
  return new Promise((resolve, reject) => {
    const webSocketKey = randomBytes(16).toString("base64");
    const socket = net.createConnection({ host, port });
    socket.setNoDelay(true);
    let responseBuffer = Buffer.alloc(0);
    let settled = false;

    const cleanupHandshakeListeners = () => {
      clearTimeout(timer);
      socket.off("connect", onConnect);
      socket.off("data", onData);
      socket.off("error", onError);
      socket.off("close", onClose);
    };
    const fail = (error) => {
      if (settled) return;
      settled = true;
      cleanupHandshakeListeners();
      socket.destroy();
      reject(error instanceof Error ? error : new SmokeVerificationError(String(error)));
    };
    const onConnect = () => {
      socket.write(buildUpgradeRequest({ host, port, path, origin, webSocketKey }));
    };
    const onError = (error) => fail(error);
    const onClose = () => fail(new SmokeVerificationError("TCP connection closed during WebSocket Upgrade"));
    const onData = (chunk) => {
      try {
        responseBuffer = Buffer.concat([responseBuffer, chunk]);
        const headerEnd = responseBuffer.indexOf("\r\n\r\n");
        requireCondition(
          headerEnd < 0
            ? responseBuffer.length <= MAX_HTTP_HEADER_BYTES
            : headerEnd + 4 <= MAX_HTTP_HEADER_BYTES,
          `HTTP Upgrade response headers exceeded ${MAX_HTTP_HEADER_BYTES} bytes`,
        );
        const handshake = parseUpgradeResponse(responseBuffer, webSocketKey);
        if (handshake === null) return;
        settled = true;
        cleanupHandshakeListeners();
        const connection = new RawWebSocketConnection(socket, handshake.remainder);
        resolve({ connection, handshake });
      } catch (error) {
        fail(error);
      }
    };
    const timer = setTimeout(
      () => fail(new SmokeVerificationError(`Timed out waiting for WebSocket Upgrade after ${timeoutMs} ms`)),
      timeoutMs,
    );

    socket.on("connect", onConnect);
    socket.on("data", onData);
    socket.on("error", onError);
    socket.on("close", onClose);
  });
}

function sha256Hex(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

function requireRecord(value, description) {
  requireCondition(value !== null && typeof value === "object" && !Array.isArray(value), `${description} was not an object`);
  return value;
}

function requireIsoCalendarDate(value, description) {
  requireCondition(typeof value === "string", `${description} was not a string`);
  const match = /^(\d{4})-(\d{2})-(\d{2})$/u.exec(value);
  requireCondition(match !== null, `${description} was not an ISO calendar date`);
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const date = new Date(Date.UTC(year, month - 1, day));
  requireCondition(
    date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day,
    `${description} was not a valid calendar date`,
  );
}

export async function loadCanonicalServerDecoder(decoderPath) {
  requireCondition(typeof decoderPath === "string" && decoderPath.length > 0, "A canonical console decoder path is required");
  const absolutePath = path.resolve(decoderPath);
  let bytes;
  try {
    bytes = await readFile(absolutePath);
  } catch (error) {
    throw new SmokeVerificationError(`Could not read canonical console decoder ${absolutePath}: ${error.message}`);
  }
  const sourceDigestSha256 = sha256Hex(bytes);
  let decoderModule;
  try {
    decoderModule = await import(`${pathToFileURL(absolutePath).href}?sha256=${sourceDigestSha256}`);
  } catch (error) {
    throw new SmokeVerificationError(`Could not load canonical console decoder ${absolutePath}: ${error.message}`);
  }
  requireCondition(
    typeof decoderModule.decodeServerConsoleMessage === "function",
    "Canonical console decoder did not export decodeServerConsoleMessage",
  );
  return {
    absolutePath,
    sourceDigestSha256,
    decodeServerMessage: decoderModule.decodeServerConsoleMessage,
  };
}

export async function loadCanonicalCapabilityMatrix(matrixPath) {
  requireCondition(typeof matrixPath === "string" && matrixPath.length > 0, "A canonical capability matrix path is required");
  const absolutePath = path.resolve(matrixPath);
  let bytes;
  try {
    bytes = await readFile(absolutePath);
  } catch (error) {
    throw new SmokeVerificationError(`Could not read canonical capability matrix ${absolutePath}: ${error.message}`);
  }

  let parsed;
  try {
    parsed = JSON.parse(bytes.toString("utf8"));
  } catch (error) {
    throw new SmokeVerificationError(`Canonical capability matrix was not valid JSON: ${error.message}`);
  }
  const document = requireRecord(parsed, "Canonical capability matrix");
  requireCondition(document.schemaVersion === 1, "Canonical capability matrix schemaVersion was not 1");
  requireCondition(document.matrixId === G520_MATRIX_ID, `Canonical capability matrix id was not ${G520_MATRIX_ID}`);
  requireIsoCalendarDate(document.lastUpdated, "Canonical capability matrix lastUpdated");
  requireCondition(Array.isArray(document.rows), "Canonical capability matrix rows was not an array");
  requireCondition(
    document.rows.length === REQUIRED_G520_CAPABILITY_IDS.length,
    `Canonical capability matrix must contain exactly ${REQUIRED_G520_CAPABILITY_IDS.length} rows`,
  );

  const rows = document.rows.map((candidate, index) => {
    const row = requireRecord(candidate, `Canonical capability matrix row ${index}`);
    requireCondition(
      row.id === REQUIRED_G520_CAPABILITY_IDS[index],
      `Canonical capability matrix row ${index} id was ${String(row.id)}, expected ${REQUIRED_G520_CAPABILITY_IDS[index]}`,
    );
    requireCondition(row.status === "UNKNOWN", `Canonical capability matrix row ${String(row.id)} was not UNKNOWN`);
    requireCondition(
      typeof row.assessment === "string" && row.assessment.trim().length > 0,
      `Canonical capability matrix row ${String(row.id)} had no assessment`,
    );
    return Object.freeze({
      id: row.id,
      status: row.status,
      assessment: row.assessment,
    });
  });

  return Object.freeze({
    absolutePath,
    rawSha256: sha256Hex(bytes),
    schemaVersion: document.schemaVersion,
    matrixId: document.matrixId,
    lastUpdated: document.lastUpdated,
    rows: Object.freeze(rows),
  });
}

export function requireCapabilitySnapshotMatchesCanonical(snapshotEnvelope, canonicalMatrix) {
  const payload = requireRecord(snapshotEnvelope.payload, "Capability snapshot payload");
  requireCondition(payload.sourceDigestSha256 === canonicalMatrix.rawSha256, "Capability snapshot raw source SHA-256 did not match the canonical matrix bytes");
  requireCondition(payload.schemaVersion === canonicalMatrix.schemaVersion, "Capability snapshot schemaVersion did not match the canonical matrix");
  requireCondition(payload.matrixId === canonicalMatrix.matrixId, "Capability snapshot matrixId did not match the canonical matrix");
  requireCondition(payload.lastUpdated === canonicalMatrix.lastUpdated, "Capability snapshot lastUpdated did not match the canonical matrix");
  requireCondition(Array.isArray(payload.rows), "Capability snapshot rows was not an array");
  requireCondition(
    payload.rows.length === REQUIRED_G520_CAPABILITY_IDS.length,
    `Capability snapshot must contain exactly ${REQUIRED_G520_CAPABILITY_IDS.length} rows`,
  );
  payload.rows.forEach((candidate, index) => {
    const row = requireRecord(candidate, `Capability snapshot row ${index}`);
    const canonicalRow = canonicalMatrix.rows[index];
    requireCondition(row.id === canonicalRow.id, `Capability snapshot row ${index} id did not match the canonical matrix`);
    requireCondition(row.status === "UNKNOWN", `Capability snapshot row ${String(row.id)} attempted to promote G520 evidence`);
    requireCondition(row.status === canonicalRow.status, `Capability snapshot row ${String(row.id)} status did not match the canonical matrix`);
    requireCondition(row.assessment === canonicalRow.assessment, `Capability snapshot row ${String(row.id)} assessment did not match the canonical matrix`);
  });
}

export function computeDiscreteIntentDigest(payload) {
  const intent = requireRecord(payload, "Discrete command intent");
  requireCondition(typeof intent.commandId === "string" && intent.commandId.length > 0, "Discrete command intent had no commandId");
  requireCondition(typeof intent.leaseId === "string" && intent.leaseId.length > 0, "Discrete command intent had no leaseId");
  requireCondition(
    ["takeoff", "landing", "return_to_home"].includes(intent.action),
    "Discrete command intent had an unsupported action",
  );
  requireCondition(Number.isSafeInteger(intent.ttlMs) && intent.ttlMs > 0, "Discrete command intent had an invalid ttlMs");
  const canonicalJson = JSON.stringify({
    kind: "discrete",
    commandId: intent.commandId,
    leaseId: intent.leaseId,
    action: intent.action,
    ttlMs: intent.ttlMs,
  });
  return sha256Hex(Buffer.from(canonicalJson, "utf8"));
}

export class ConsoleEnvelopeReader {
  constructor(connection, decodeServerMessage) {
    requireCondition(typeof decodeServerMessage === "function", "ConsoleEnvelopeReader requires the canonical server decoder");
    this.connection = connection;
    this.decodeServerMessage = decodeServerMessage;
    this.pending = [];
    this.observedMessageTypes = [];
    this.latestTelemetrySequence = null;
  }

  async next(deadlineMs) {
    const text = await this.connection.nextText(remainingMillis(deadlineMs));
    let envelope;
    try {
      envelope = this.decodeServerMessage(text);
    } catch (error) {
      throw new SmokeVerificationError(`Canonical console decoder rejected a server message: ${error.message}`);
    }
    requireRecord(envelope, "Canonical decoder result");
    this.observedMessageTypes.push(envelope.type);
    if (envelope.type === "telemetry") {
      this.latestTelemetrySequence = Math.max(
        this.latestTelemetrySequence ?? envelope.payload.sequence,
        envelope.payload.sequence,
      );
    }
    if (envelope.type === "protocol_error") {
      const code = typeof envelope.payload.code === "string" ? envelope.payload.code : "unknown";
      const detail = typeof envelope.payload.detail === "string" ? envelope.payload.detail : "no detail";
      throw new SmokeVerificationError(`Console server returned protocol_error ${code}: ${detail}`);
    }
    return envelope;
  }

  async waitFor(
    predicate,
    deadlineMs,
    description,
    { rejectIf = () => false, rejectionMessage = "Console message sequence was invalid" } = {},
  ) {
    const rejectedPending = this.pending.find(rejectIf);
    requireCondition(rejectedPending === undefined, rejectionMessage);
    const pendingIndex = this.pending.findIndex(predicate);
    if (pendingIndex >= 0) return this.pending.splice(pendingIndex, 1)[0];

    while (true) {
      const envelope = await this.next(deadlineMs);
      requireCondition(!rejectIf(envelope), rejectionMessage);
      if (predicate(envelope)) return envelope;
      requireCondition(this.pending.length < 256, "Pending console envelope queue exceeded its safety bound");
      this.pending.push(envelope);
      requireCondition(Date.now() < deadlineMs, `Timed out waiting for ${description}`);
    }
  }

  discardPending(predicate) {
    this.pending = this.pending.filter((envelope) => !predicate(envelope));
  }
}

function remainingMillis(deadlineMs) {
  const remaining = deadlineMs - Date.now();
  requireCondition(remaining > 0, "Forwarded console smoke verification timed out");
  return remaining;
}

function requireRuntimeState(envelope) {
  const runtime = envelope.payload;
  requireCondition(runtime.adapter === "mock", `Expected mock adapter, received ${String(runtime.adapter)}`);
  requireCondition(
    runtime.aircraftConnection === "connected",
    `Expected connected aircraft state, received ${String(runtime.aircraftConnection)}`,
  );
  requireCondition(
    runtime.actuationLock === "unlocked",
    `Expected unlocked actuation state, received ${String(runtime.actuationLock)}`,
  );
  requireCondition(
    runtime.operatingProfile === "localhost_development",
    `Expected localhost_development profile, received ${String(runtime.operatingProfile)}`,
  );
}

function message(messageId, type, payload) {
  return {
    protocolVersion: CONSOLE_PROTOCOL_VERSION,
    messageId,
    type,
    payload,
  };
}

function parseCliArguments(argv) {
  requireCondition(
    argv.length === 4,
    "usage: verify-forwarded-console.mjs 127.0.0.1 18080 <console-protocol.mjs> <g520-stack.json>",
  );
  const host = argv[0];
  const portText = argv[1];
  const port = Number(portText);
  requireCondition(host === DEFAULT_HOST, `Smoke helper only permits host ${DEFAULT_HOST}`);
  requireCondition(Number.isInteger(port) && port === DEFAULT_PORT, `Smoke helper only permits port ${DEFAULT_PORT}`);
  const decoderPath = argv[2];
  const matrixPath = argv[3];
  requireCondition(typeof decoderPath === "string" && decoderPath.length > 0, "Smoke helper requires a canonical decoder path");
  requireCondition(typeof matrixPath === "string" && matrixPath.length > 0, "Smoke helper requires a canonical matrix path");
  return { host, port, decoderPath, matrixPath };
}

export async function verifyForwardedConsole({
  host = DEFAULT_HOST,
  port = DEFAULT_PORT,
  decoderPath,
  matrixPath,
  handshakeTimeoutMs = HANDSHAKE_TIMEOUT_MS,
  smokeTimeoutMs = SMOKE_TIMEOUT_MS,
} = {}) {
  requireCondition(host === DEFAULT_HOST, "Forwarded console endpoint must use 127.0.0.1");
  requireCondition(Number.isInteger(port) && port >= 1 && port <= 65_535, "Forwarded console endpoint port was invalid");
  requireCondition(Number.isFinite(handshakeTimeoutMs) && handshakeTimeoutMs > 0, "Handshake timeout must be positive");
  requireCondition(Number.isFinite(smokeTimeoutMs) && smokeTimeoutMs > 0, "Smoke timeout must be positive");
  const startedAt = Date.now();
  const deadlineMs = startedAt + smokeTimeoutMs;
  const origin = `http://${host}:${port}`;
  const wrongOriginPort = port === 65_535 ? port - 1 : port + 1;
  const wrongOrigin = `http://${host}:${wrongOriginPort}`;
  const decoder = await loadCanonicalServerDecoder(decoderPath);
  const canonicalMatrix = await loadCanonicalCapabilityMatrix(matrixPath);
  let connection;

  try {
    const wrongOriginRejection = await expectRejectedWebSocketUpgrade({
      host,
      port,
      origin: wrongOrigin,
      description: "wrong-Origin",
      timeoutMs: Math.min(handshakeTimeoutMs, remainingMillis(deadlineMs)),
    });
    const missingOriginRejection = await expectRejectedWebSocketUpgrade({
      host,
      port,
      origin: null,
      description: "missing-Origin",
      timeoutMs: Math.min(handshakeTimeoutMs, remainingMillis(deadlineMs)),
    });
    const connected = await connectRawWebSocket({
      host,
      port,
      origin,
      timeoutMs: Math.min(handshakeTimeoutMs, remainingMillis(deadlineMs)),
    });
    connection = connected.connection;
    const reader = new ConsoleEnvelopeReader(connection, decoder.decodeServerMessage);

    const helloMessageId = `smoke-client-hello-${randomUUID()}`;
    connection.sendJson(
      message(helloMessageId, "client_hello", {
        clientName: "forwarded-console-smoke",
        clientVersion: "1.0.0",
        supportedProtocolVersions: [CONSOLE_PROTOCOL_VERSION],
        authentication: null,
      }),
    );

    const serverHello = await reader.next(deadlineMs);
    requireCondition(serverHello.type === "server_hello", `First console message was ${serverHello.type}, expected server_hello`);
    requireCondition(
      serverHello.payload.selectedProtocolVersion === CONSOLE_PROTOCOL_VERSION,
      "server_hello selected an unexpected protocol version",
    );
    requireCondition(serverHello.payload.authenticationRequired === false, "Localhost server unexpectedly required authentication");
    const sessionId = serverHello.payload.sessionId;
    requireCondition(typeof sessionId === "string" && sessionId.length > 0, "server_hello had no sessionId");

    const runtimeState = await reader.waitFor(
      (envelope) => envelope.type === "runtime_state",
      deadlineMs,
      "runtime_state",
    );
    requireRuntimeState(runtimeState);

    const telemetry = await reader.waitFor(
      (envelope) => envelope.type === "telemetry",
      deadlineMs,
      "telemetry",
    );
    requireCondition(
      Number.isSafeInteger(telemetry.payload.sequence) && telemetry.payload.sequence >= 1,
      "Telemetry snapshot had no valid positive sequence",
    );

    const capabilitySnapshot = await reader.waitFor(
      (envelope) => envelope.type === "capability_snapshot",
      deadlineMs,
      "capability_snapshot",
    );
    requireCapabilitySnapshotMatchesCanonical(capabilitySnapshot, canonicalMatrix);

    const leaseRequestMessageId = `smoke-lease-acquire-${randomUUID()}`;
    connection.sendJson(message(leaseRequestMessageId, "lease_acquire", { requestedTtlMs: 10_000 }));
    const leaseState = await reader.waitFor(
      (envelope) =>
        envelope.type === "lease_state" &&
        envelope.payload.requestMessageId === leaseRequestMessageId,
      deadlineMs,
      "correlated lease_state",
    );
    requireCondition(leaseState.payload.state === "held", `Lease acquisition returned ${String(leaseState.payload.state)}`);
    requireCondition(leaseState.payload.holderSessionId === sessionId, "Lease holder did not match this WebSocket session");
    const leaseId = leaseState.payload.leaseId;
    requireCondition(typeof leaseId === "string" && leaseId.length > 0, "Held lease had no leaseId");

    const commandId = `smoke-takeoff-${randomUUID()}`;
    const commandMessageId = `smoke-command-message-${randomUUID()}`;
    const commandIntent = {
      commandId,
      leaseId,
      action: "takeoff",
      ttlMs: 5_000,
    };
    const expectedIntentDigestSha256 = computeDiscreteIntentDigest(commandIntent);
    connection.sendJson(
      message(commandMessageId, "command_request", commandIntent),
    );

    const commandAck = await reader.waitFor(
      (envelope) => envelope.type === "command_ack" && envelope.payload.commandId === commandId,
      deadlineMs,
      "takeoff command_ack",
      {
        rejectIf: (envelope) =>
          envelope.type === "command_result" && envelope.payload.commandId === commandId,
        rejectionMessage: "Takeoff command_result arrived before command_ack",
      },
    );
    requireCondition(
      commandAck.payload.decision === "accepted",
      `Takeoff command was ${String(commandAck.payload.decision)}: ${String(commandAck.payload.reason)}`,
    );
    requireCondition(
      commandAck.payload.intentDigestSha256 === expectedIntentDigestSha256,
      "Accepted takeoff acknowledgement intent digest did not match ConsoleIntentDigest canonical JSON",
    );

    const commandResult = await reader.waitFor(
      (envelope) => envelope.type === "command_result" && envelope.payload.commandId === commandId,
      deadlineMs,
      "takeoff command_result",
    );
    requireCondition(
      commandResult.payload.status === "succeeded",
      `Takeoff command result was ${String(commandResult.payload.status)}: ${String(commandResult.payload.reason)}`,
    );
    const postCommandSequenceFloor = reader.latestTelemetrySequence ?? telemetry.payload.sequence;
    // Telemetry decoded while waiting for ack/result preceded the successful result and cannot
    // serve as post-command runtime evidence, even if its sequence happened to be higher.
    reader.discardPending((envelope) => envelope.type === "telemetry");
    const postCommandTelemetry = await reader.waitFor(
      (envelope) =>
        envelope.type === "telemetry" &&
        envelope.payload.sequence > postCommandSequenceFloor &&
        ["taking_off", "flying"].includes(envelope.payload.flightState),
      deadlineMs,
      "post-takeoff telemetry with an increased sequence and taking_off/flying state",
    );
    // A final text waiter may resolve before a later frame in the same TCP chunk is processed.
    // Recheck the batch-level transport state before emitting durable success evidence.
    connection.requireHealthy();

    const summary = {
      ok: true,
      endpoint: `ws://${host}:${port}${CONSOLE_PATH}`,
      origin,
      originAdmission: {
        wrongOrigin,
        wrongOriginStatusCode: wrongOriginRejection.statusCode,
        missingOriginStatusCode: missingOriginRejection.statusCode,
      },
      webSocketUpgrade: {
        statusCode: connected.handshake.statusCode,
        acceptVerified: connected.handshake.acceptVerified,
      },
      protocolVersion: CONSOLE_PROTOCOL_VERSION,
      canonicalDecoder: {
        sourceDigestSha256: decoder.sourceDigestSha256,
      },
      sessionId,
      runtime: {
        adapter: runtimeState.payload.adapter,
        aircraftConnection: runtimeState.payload.aircraftConnection,
        actuationLock: runtimeState.payload.actuationLock,
        operatingProfile: runtimeState.payload.operatingProfile,
      },
      telemetry: {
        initialSequence: telemetry.payload.sequence,
        postCommandSequenceFloor,
        postCommandSequence: postCommandTelemetry.payload.sequence,
        postCommandFlightState: postCommandTelemetry.payload.flightState,
      },
      capability: {
        matrixId: capabilitySnapshot.payload.matrixId,
        schemaVersion: capabilitySnapshot.payload.schemaVersion,
        lastUpdated: capabilitySnapshot.payload.lastUpdated,
        rawSourceDigestSha256: capabilitySnapshot.payload.sourceDigestSha256,
        rowCount: capabilitySnapshot.payload.rows.length,
        allUnknown: true,
        exactCanonicalRows: true,
      },
      lease: {
        requestMessageId: leaseRequestMessageId,
        state: leaseState.payload.state,
        leaseId,
        holderMatchesSession: true,
      },
      command: {
        commandId,
        action: "takeoff",
        decision: commandAck.payload.decision,
        intentDigestSha256: commandAck.payload.intentDigestSha256,
        expectedIntentDigestSha256,
        status: commandResult.payload.status,
      },
      observedMessageTypes: reader.observedMessageTypes,
      elapsedMs: Date.now() - startedAt,
    };
    connection.close();
    return summary;
  } catch (error) {
    if (connection !== undefined) connection.destroy();
    throw error;
  }
}

async function main() {
  try {
    const options = parseCliArguments(process.argv.slice(2));
    const summary = await verifyForwardedConsole(options);
    process.stdout.write(`${JSON.stringify(summary)}\n`);
  } catch (error) {
    const messageText = error instanceof Error ? error.message : String(error);
    process.stderr.write(`${JSON.stringify({ ok: false, error: messageText })}\n`);
    process.exitCode = 1;
  }
}

if (process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href) {
  await main();
}
