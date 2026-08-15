export const CONSOLE_PROTOCOL_VERSION = "1.0" as const;

export const CONSOLE_MESSAGE_TYPES = Object.freeze([
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
] as const);

export const CLIENT_CONSOLE_MESSAGE_TYPES = Object.freeze([
  "client_hello",
  "lease_acquire",
  "lease_renew",
  "lease_release",
  "command_request",
  "control_frame",
  "control_neutral",
] as const);

export const SERVER_CONSOLE_MESSAGE_TYPES = Object.freeze([
  "server_hello",
  "runtime_state",
  "telemetry",
  "capability_snapshot",
  "health",
  "lease_state",
  "command_ack",
  "command_result",
  "control_ack",
  "safety_event",
  "protocol_error",
] as const);

export type ConsoleMessageType = (typeof CONSOLE_MESSAGE_TYPES)[number];
export type ClientConsoleMessageType =
  (typeof CLIENT_CONSOLE_MESSAGE_TYPES)[number];
export type ServerConsoleMessageType =
  (typeof SERVER_CONSOLE_MESSAGE_TYPES)[number];

export interface ClientHelloPayload {
  readonly clientName: string;
  readonly clientVersion: string;
  readonly supportedProtocolVersions: readonly string[];
  readonly authentication: {
    readonly scheme: string;
    readonly credential: string;
  } | null;
}

export interface ServerHelloPayload {
  readonly sessionId: string;
  readonly serverVersion: string;
  readonly selectedProtocolVersion: string;
  readonly authenticationRequired: boolean;
  readonly acceptedAuthenticationSchemes: readonly string[];
}

export interface RuntimeStatePayload {
  readonly adapter: "mock" | "dji";
  readonly aircraftConnection:
    | "disconnected"
    | "connecting"
    | "connected"
    | "error";
  readonly actuationLock: "locked" | "unlocked";
  readonly operatingProfile:
    | "localhost_development"
    | "hardware_commissioning"
    | "operational";
}

export interface TelemetryPayload {
  readonly sequence: number;
  readonly batteryPercent: number | null;
  readonly latitude: number | null;
  readonly longitude: number | null;
  readonly altitudeM: number | null;
  readonly flightState:
    | "grounded"
    | "taking_off"
    | "flying"
    | "landing"
    | "returning_home"
    | "unknown";
  readonly gimbalPitchDeg: number | null;
  readonly cameraRecording: boolean | null;
}

export interface CapabilitySnapshotRow {
  readonly id: string;
  readonly status: "CONFIRMED" | "LIMITED" | "UNKNOWN";
  readonly assessment: string;
}

export interface CapabilitySnapshotPayload {
  readonly matrixId: string;
  readonly schemaVersion: 1;
  readonly lastUpdated: string;
  readonly sourceDigestSha256: string;
  readonly rows: readonly CapabilitySnapshotRow[];
}

export interface HealthPayload {
  readonly status: "healthy" | "degraded" | "stopping";
  readonly uptimeMs: number;
  readonly detail: string | null;
}

export interface LeaseAcquirePayload {
  readonly requestedTtlMs: number;
}

export interface LeaseRenewPayload {
  readonly leaseId: string;
  readonly requestedTtlMs: number;
}

export interface LeaseReleasePayload {
  readonly leaseId: string;
}

export interface LeaseStatePayload {
  readonly requestMessageId: string | null;
  readonly state: "available" | "held" | "denied" | "released" | "expired";
  readonly leaseId: string | null;
  readonly holderSessionId: string | null;
  readonly expiresInMs: number | null;
  readonly reason: string | null;
}

export interface CommandRequestPayload {
  readonly commandId: string;
  readonly leaseId: string;
  readonly action: "takeoff" | "landing" | "return_to_home";
  readonly ttlMs: number;
}

export interface CommandAckPayload {
  readonly commandId: string;
  readonly decision: "accepted" | "rejected";
  readonly reason: string | null;
  readonly intentDigestSha256: string;
}

export interface CommandResultPayload {
  readonly commandId: string;
  readonly status: "succeeded" | "failed" | "timed_out" | "cancelled";
  readonly reason: string | null;
  readonly detail: string | null;
}

export interface ControlFramePayload {
  readonly leaseId: string;
  readonly inputSequence: number;
  readonly ttlMs: number;
  readonly forward: number;
  readonly right: number;
  readonly up: number;
  readonly yaw: number;
}

export interface ControlNeutralPayload {
  readonly leaseId: string;
  readonly inputSequence: number;
  readonly reason:
    | "operator_release"
    | "pointer_cancel"
    | "window_blur"
    | "page_hide";
}

export interface ControlAckPayload {
  readonly leaseId: string;
  readonly inputSequence: number;
  readonly status: "applied" | "rejected" | "stale";
  readonly reason: string | null;
}

export interface SafetyEventPayload {
  readonly action: "neutralize";
  readonly outcome: "succeeded" | "failed";
  readonly trigger:
    | "client_request"
    | "client_disconnect"
    | "lease_expired"
    | "control_ttl_expired"
    | "actuation_readiness_lost"
    | "server_stop";
  readonly leaseId: string | null;
  readonly lastInputSequence: number | null;
  readonly detail: string | null;
}

export interface ProtocolErrorPayload {
  readonly relatedMessageId: string | null;
  readonly code:
    | "malformed_json"
    | "invalid_envelope"
    | "unsupported_protocol_version"
    | "unknown_message_type"
    | "wrong_message_direction"
    | "invalid_payload"
    | "handshake_required"
    | "unexpected_message"
    | "server_unavailable";
  readonly detail: string | null;
}

export interface ConsolePayloadByType {
  readonly client_hello: ClientHelloPayload;
  readonly server_hello: ServerHelloPayload;
  readonly runtime_state: RuntimeStatePayload;
  readonly telemetry: TelemetryPayload;
  readonly capability_snapshot: CapabilitySnapshotPayload;
  readonly health: HealthPayload;
  readonly lease_acquire: LeaseAcquirePayload;
  readonly lease_renew: LeaseRenewPayload;
  readonly lease_release: LeaseReleasePayload;
  readonly lease_state: LeaseStatePayload;
  readonly command_request: CommandRequestPayload;
  readonly command_ack: CommandAckPayload;
  readonly command_result: CommandResultPayload;
  readonly control_frame: ControlFramePayload;
  readonly control_neutral: ControlNeutralPayload;
  readonly control_ack: ControlAckPayload;
  readonly safety_event: SafetyEventPayload;
  readonly protocol_error: ProtocolErrorPayload;
}

export type ConsoleEnvelope<T extends ConsoleMessageType> = Readonly<{
  protocolVersion: typeof CONSOLE_PROTOCOL_VERSION;
  messageId: string;
  type: T;
  payload: ConsolePayloadByType[T];
}>;

export type ConsoleMessage = {
  [T in ConsoleMessageType]: ConsoleEnvelope<T>;
}[ConsoleMessageType];

export type ClientConsoleMessage = Extract<
  ConsoleMessage,
  { readonly type: ClientConsoleMessageType }
>;

export type ServerConsoleMessage = Extract<
  ConsoleMessage,
  { readonly type: ServerConsoleMessageType }
>;

const ENVELOPE_FIELDS = new Set([
  "protocolVersion",
  "messageId",
  "type",
  "payload",
]);
const MESSAGE_TYPE_VALUES = new Set<string>(CONSOLE_MESSAGE_TYPES);
const CLIENT_MESSAGE_TYPE_VALUES = new Set<string>(CLIENT_CONSOLE_MESSAGE_TYPES);
const SERVER_MESSAGE_TYPE_VALUES = new Set<string>(SERVER_CONSOLE_MESSAGE_TYPES);
const IDENTIFIER_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$/;
const CAPABILITY_ID_PATTERN = /^[a-z][a-z0-9_]*$/;
const SHA256_PATTERN = /^[0-9a-f]{64}$/;
const DATE_PATTERN = /^(\d{4})-(\d{2})-(\d{2})$/;
const MAX_SAFE_SEQUENCE = Number.MAX_SAFE_INTEGER;
const MAX_NAME_LENGTH = 128;
const MAX_VERSION_LENGTH = 128;
const MAX_SCHEME_LENGTH = 128;
const MAX_CREDENTIAL_LENGTH = 4_096;
const MAX_TEXT_LENGTH = 1_024;
const MAX_NEGOTIATION_LIST_SIZE = 16;
const MAX_CAPABILITY_ROWS = 256;
const MAX_WIRE_MESSAGE_UTF8_BYTES = 65_536;
const MAX_JSON_NESTING_DEPTH = 16;
const G520_CAPABILITY_MATRIX_ID = "mini4pro-rcn3-g520-android";
const UTF8_ENCODER = new TextEncoder();
const NUMBER_LEXEME_MARKER = "__consoleProtocolNumberLexeme__";
const INTEGRAL_PAYLOAD_FIELDS: Readonly<
  Partial<Record<ConsoleMessageType, readonly string[]>>
> = Object.freeze({
  telemetry: Object.freeze(["sequence", "batteryPercent"]),
  capability_snapshot: Object.freeze(["schemaVersion"]),
  health: Object.freeze(["uptimeMs"]),
  lease_acquire: Object.freeze(["requestedTtlMs"]),
  lease_renew: Object.freeze(["requestedTtlMs"]),
  lease_state: Object.freeze(["expiresInMs"]),
  command_request: Object.freeze(["ttlMs"]),
  control_frame: Object.freeze(["inputSequence", "ttlMs"]),
  control_neutral: Object.freeze(["inputSequence"]),
  control_ack: Object.freeze(["inputSequence"]),
  safety_event: Object.freeze(["lastInputSequence"]),
});

/** Decode an inbound JSON message and detach it as a recursively immutable snapshot. */
export function decodeConsoleMessage(text: string): ConsoleMessage {
  preflightWireText(text);
  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch {
    throw new ConsoleProtocolValidationError("Malformed console protocol JSON");
  }
  assertConsoleMessage(value);
  assertIntegralWireLexemes(value, parseNumberLexemeShadow(text));
  return deepFreeze(value);
}

/** Decode a browser-to-server message and reject a valid message in the wrong direction. */
export function decodeClientConsoleMessage(text: string): ClientConsoleMessage {
  const message = decodeConsoleMessage(text);
  if (!CLIENT_MESSAGE_TYPE_VALUES.has(message.type)) {
    throw new ConsoleProtocolValidationError(
      `Console message type ${message.type} is not valid from a client`,
    );
  }
  return message as ClientConsoleMessage;
}

/** Decode a server-to-browser message and reject a valid message in the wrong direction. */
export function decodeServerConsoleMessage(text: string): ServerConsoleMessage {
  const message = decodeConsoleMessage(text);
  if (!SERVER_MESSAGE_TYPE_VALUES.has(message.type)) {
    throw new ConsoleProtocolValidationError(
      `Console message type ${message.type} is not valid from a server`,
    );
  }
  return message as ServerConsoleMessage;
}

/** Encode only after re-validating the complete runtime shape. */
export function encodeConsoleMessage(message: unknown): string {
  assertConsoleMessage(message);
  return encodeWithPreflight(message);
}

export function encodeClientConsoleMessage(message: unknown): string {
  assertConsoleMessage(message);
  if (!CLIENT_MESSAGE_TYPE_VALUES.has(message.type)) {
    throw new ConsoleProtocolValidationError(
      `Console message type ${message.type} is not valid from a client`,
    );
  }
  return encodeWithPreflight(message);
}

export function encodeServerConsoleMessage(message: unknown): string {
  assertConsoleMessage(message);
  if (!SERVER_MESSAGE_TYPE_VALUES.has(message.type)) {
    throw new ConsoleProtocolValidationError(
      `Console message type ${message.type} is not valid from a server`,
    );
  }
  return encodeWithPreflight(message);
}

export function assertConsoleMessage(
  value: unknown,
): asserts value is ConsoleMessage {
  const envelope = requireRecord(value, "console envelope");
  requireExactKeys(envelope, ENVELOPE_FIELDS, "console envelope");
  if (envelope.protocolVersion !== CONSOLE_PROTOCOL_VERSION) {
    throw new ConsoleProtocolValidationError(
      "Unsupported console protocol version",
    );
  }
  requireIdentifier(envelope, "messageId", "console envelope");
  const type = requireString(envelope, "type", "console envelope");
  if (!MESSAGE_TYPE_VALUES.has(type)) {
    throw new ConsoleProtocolValidationError("Unknown console message type");
  }

  const messageType = type as ConsoleMessageType;
  switch (messageType) {
    case "client_hello":
      validateClientHello(envelope.payload);
      return;
    case "server_hello":
      validateServerHello(envelope.payload);
      return;
    case "runtime_state":
      validateRuntimeState(envelope.payload);
      return;
    case "telemetry":
      validateTelemetry(envelope.payload);
      return;
    case "capability_snapshot":
      validateCapabilitySnapshot(envelope.payload);
      return;
    case "health":
      validateHealth(envelope.payload);
      return;
    case "lease_acquire":
      validateLeaseAcquire(envelope.payload);
      return;
    case "lease_renew":
      validateLeaseRenew(envelope.payload);
      return;
    case "lease_release":
      validateLeaseRelease(envelope.payload);
      return;
    case "lease_state":
      validateLeaseState(envelope.payload);
      return;
    case "command_request":
      validateCommandRequest(envelope.payload);
      return;
    case "command_ack":
      validateCommandAck(envelope.payload);
      return;
    case "command_result":
      validateCommandResult(envelope.payload);
      return;
    case "control_frame":
      validateControlFrame(envelope.payload);
      return;
    case "control_neutral":
      validateControlNeutral(envelope.payload);
      return;
    case "control_ack":
      validateControlAck(envelope.payload);
      return;
    case "safety_event":
      validateSafetyEvent(envelope.payload);
      return;
    case "protocol_error":
      validateProtocolError(envelope.payload);
      return;
    default:
      return assertNever(messageType);
  }
}

export class ConsoleProtocolValidationError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ConsoleProtocolValidationError";
  }
}

function assertNever(value: never): never {
  throw new ConsoleProtocolValidationError(
    `Unhandled console message type: ${String(value)}`,
  );
}

function validateClientHello(value: unknown): void {
  const payload = requirePayload(value, [
    "clientName",
    "clientVersion",
    "supportedProtocolVersions",
    "authentication",
  ], "client_hello");
  requireNonBlankString(
    payload,
    "clientName",
    "client_hello payload",
    MAX_NAME_LENGTH,
  );
  requireNonBlankString(
    payload,
    "clientVersion",
    "client_hello payload",
    MAX_VERSION_LENGTH,
  );
  requireUniqueNonBlankStringArray(
    payload,
    "supportedProtocolVersions",
    "client_hello payload",
    {
      nonEmpty: true,
      requiredValue: CONSOLE_PROTOCOL_VERSION,
      maximumItemLength: MAX_VERSION_LENGTH,
      maximumItems: MAX_NEGOTIATION_LIST_SIZE,
    },
  );
  if (payload.authentication !== null) {
    const authentication = requireRecord(
      payload.authentication,
      "client_hello authentication",
    );
    requireExactKeys(
      authentication,
      new Set(["scheme", "credential"]),
      "client_hello authentication",
    );
    requireNonBlankString(
      authentication,
      "scheme",
      "client_hello authentication",
      MAX_SCHEME_LENGTH,
    );
    requireNonBlankString(
      authentication,
      "credential",
      "client_hello authentication",
      MAX_CREDENTIAL_LENGTH,
    );
  }
}

function validateServerHello(value: unknown): void {
  const payload = requirePayload(value, [
    "sessionId",
    "serverVersion",
    "selectedProtocolVersion",
    "authenticationRequired",
    "acceptedAuthenticationSchemes",
  ], "server_hello");
  requireIdentifier(payload, "sessionId", "server_hello payload");
  requireNonBlankString(
    payload,
    "serverVersion",
    "server_hello payload",
    MAX_VERSION_LENGTH,
  );
  if (payload.selectedProtocolVersion !== CONSOLE_PROTOCOL_VERSION) {
    throw new ConsoleProtocolValidationError(
      "server_hello payload selectedProtocolVersion is unsupported",
    );
  }
  requireBoolean(payload, "authenticationRequired", "server_hello payload");
  const acceptedSchemes = requireUniqueNonBlankStringArray(
    payload,
    "acceptedAuthenticationSchemes",
    "server_hello payload",
    {
      maximumItemLength: MAX_SCHEME_LENGTH,
      maximumItems: MAX_NEGOTIATION_LIST_SIZE,
    },
  );
  if (payload.authenticationRequired === true && acceptedSchemes.length === 0) {
    throw new ConsoleProtocolValidationError(
      "server_hello payload requires at least one accepted authentication scheme",
    );
  }
}

function validateRuntimeState(value: unknown): void {
  const payload = requirePayload(value, [
    "adapter",
    "aircraftConnection",
    "actuationLock",
    "operatingProfile",
  ], "runtime_state");
  requireEnum(payload, "adapter", ["mock", "dji"], "runtime_state payload");
  requireEnum(
    payload,
    "aircraftConnection",
    ["disconnected", "connecting", "connected", "error"],
    "runtime_state payload",
  );
  requireEnum(
    payload,
    "actuationLock",
    ["locked", "unlocked"],
    "runtime_state payload",
  );
  requireEnum(
    payload,
    "operatingProfile",
    ["localhost_development", "hardware_commissioning", "operational"],
    "runtime_state payload",
  );
}

function validateTelemetry(value: unknown): void {
  const payload = requirePayload(value, [
    "sequence",
    "batteryPercent",
    "latitude",
    "longitude",
    "altitudeM",
    "flightState",
    "gimbalPitchDeg",
    "cameraRecording",
  ], "telemetry");
  requireSafeInteger(payload, "sequence", 1, MAX_SAFE_SEQUENCE, "telemetry payload");
  requireNullableInteger(payload, "batteryPercent", 0, 100, "telemetry payload");
  requireNullableFiniteNumber(payload, "latitude", -90, 90, "telemetry payload");
  requireNullableFiniteNumber(payload, "longitude", -180, 180, "telemetry payload");
  requireNullableFiniteNumber(payload, "altitudeM", -1_000, 10_000, "telemetry payload");
  requireEnum(
    payload,
    "flightState",
    [
      "grounded",
      "taking_off",
      "flying",
      "landing",
      "returning_home",
      "unknown",
    ],
    "telemetry payload",
  );
  requireNullableFiniteNumber(
    payload,
    "gimbalPitchDeg",
    -180,
    180,
    "telemetry payload",
  );
  requireNullableBoolean(payload, "cameraRecording", "telemetry payload");
}

function validateCapabilitySnapshot(value: unknown): void {
  const payload = requirePayload(value, [
    "matrixId",
    "schemaVersion",
    "lastUpdated",
    "sourceDigestSha256",
    "rows",
  ], "capability_snapshot");
  const matrixId = requireNonBlankString(
    payload,
    "matrixId",
    "capability_snapshot payload",
    MAX_NAME_LENGTH,
  );
  if (matrixId !== G520_CAPABILITY_MATRIX_ID) {
    throw new ConsoleProtocolValidationError(
      "capability_snapshot payload matrixId targets an unexpected stack",
    );
  }
  if (payload.schemaVersion !== 1) {
    throw new ConsoleProtocolValidationError(
      "capability_snapshot payload schemaVersion must be 1",
    );
  }
  requireDate(payload, "lastUpdated", "capability_snapshot payload");
  requireSha256(payload, "sourceDigestSha256", "capability_snapshot payload");
  if (
    !Array.isArray(payload.rows) ||
    payload.rows.length === 0 ||
    payload.rows.length > MAX_CAPABILITY_ROWS
  ) {
    throw new ConsoleProtocolValidationError(
      `capability_snapshot payload rows must contain 1 through ${MAX_CAPABILITY_ROWS} entries`,
    );
  }
  const rowIds = new Set<string>();
  for (const candidate of payload.rows) {
    const row = requireRecord(candidate, "capability_snapshot row");
    requireExactKeys(
      row,
      new Set(["id", "status", "assessment"]),
      "capability_snapshot row",
    );
    const id = requireNonBlankString(
      row,
      "id",
      "capability_snapshot row",
      MAX_NAME_LENGTH,
    );
    if (!CAPABILITY_ID_PATTERN.test(id)) {
      throw new ConsoleProtocolValidationError(
        `capability_snapshot row id ${id} is invalid`,
      );
    }
    requireEnum(
      row,
      "status",
      ["CONFIRMED", "LIMITED", "UNKNOWN"],
      "capability_snapshot row",
    );
    requireNonBlankString(
      row,
      "assessment",
      "capability_snapshot row",
      MAX_TEXT_LENGTH,
    );
    if (rowIds.has(id)) {
      throw new ConsoleProtocolValidationError(
        `capability_snapshot payload has duplicate row ${id}`,
      );
    }
    rowIds.add(id);
  }
}

function validateHealth(value: unknown): void {
  const payload = requirePayload(value, ["status", "uptimeMs", "detail"], "health");
  requireEnum(
    payload,
    "status",
    ["healthy", "degraded", "stopping"],
    "health payload",
  );
  requireSafeInteger(payload, "uptimeMs", 0, MAX_SAFE_SEQUENCE, "health payload");
  requireNullableNonBlankString(payload, "detail", "health payload");
}

function validateLeaseAcquire(value: unknown): void {
  const payload = requirePayload(value, ["requestedTtlMs"], "lease_acquire");
  requireSafeInteger(payload, "requestedTtlMs", 500, 30_000, "lease_acquire payload");
}

function validateLeaseRenew(value: unknown): void {
  const payload = requirePayload(value, ["leaseId", "requestedTtlMs"], "lease_renew");
  requireIdentifier(payload, "leaseId", "lease_renew payload");
  requireSafeInteger(payload, "requestedTtlMs", 500, 30_000, "lease_renew payload");
}

function validateLeaseRelease(value: unknown): void {
  const payload = requirePayload(value, ["leaseId"], "lease_release");
  requireIdentifier(payload, "leaseId", "lease_release payload");
}

function validateLeaseState(value: unknown): void {
  const payload = requirePayload(value, [
    "requestMessageId",
    "state",
    "leaseId",
    "holderSessionId",
    "expiresInMs",
    "reason",
  ], "lease_state");
  requireNullableIdentifier(payload, "requestMessageId", "lease_state payload");
  requireEnum(
    payload,
    "state",
    ["available", "held", "denied", "released", "expired"],
    "lease_state payload",
  );
  requireNullableIdentifier(payload, "leaseId", "lease_state payload");
  requireNullableIdentifier(payload, "holderSessionId", "lease_state payload");
  requireNullableSafeInteger(
    payload,
    "expiresInMs",
    0,
    MAX_SAFE_SEQUENCE,
    "lease_state payload",
  );
  requireNullableNonBlankString(payload, "reason", "lease_state payload");
  validateLeaseStateCombination(payload);
}

function validateLeaseStateCombination(payload: Record<string, unknown>): void {
  switch (payload.state) {
    case "available":
      requireNullLeaseFields(payload, [
        "leaseId",
        "holderSessionId",
        "expiresInMs",
        "reason",
      ]);
      return;
    case "held":
      requirePresentLeaseFields(payload, [
        "leaseId",
        "holderSessionId",
        "expiresInMs",
      ]);
      requireNullLeaseFields(payload, ["reason"]);
      return;
    case "denied":
      requireNullLeaseFields(payload, [
        "leaseId",
        "holderSessionId",
        "expiresInMs",
      ]);
      requirePresentLeaseFields(payload, ["reason"]);
      return;
    case "released":
      requirePresentLeaseFields(payload, ["leaseId"]);
      requireNullLeaseFields(payload, [
        "holderSessionId",
        "expiresInMs",
        "reason",
      ]);
      return;
    case "expired":
      requirePresentLeaseFields(payload, ["leaseId", "reason"]);
      requireNullLeaseFields(payload, ["holderSessionId", "expiresInMs"]);
      return;
  }
}

function requireNullLeaseFields(
  payload: Record<string, unknown>,
  fields: readonly string[],
): void {
  for (const field of fields) {
    if (payload[field] !== null) {
      throw new ConsoleProtocolValidationError(
        `lease_state payload ${field} must be null when state is ${String(payload.state)}`,
      );
    }
  }
}

function requirePresentLeaseFields(
  payload: Record<string, unknown>,
  fields: readonly string[],
): void {
  for (const field of fields) {
    if (payload[field] === null) {
      throw new ConsoleProtocolValidationError(
        `lease_state payload ${field} is required when state is ${String(payload.state)}`,
      );
    }
  }
}

function validateCommandRequest(value: unknown): void {
  const payload = requirePayload(value, [
    "commandId",
    "leaseId",
    "action",
    "ttlMs",
  ], "command_request");
  requireIdentifier(payload, "commandId", "command_request payload");
  requireIdentifier(payload, "leaseId", "command_request payload");
  requireEnum(
    payload,
    "action",
    ["takeoff", "landing", "return_to_home"],
    "command_request payload",
  );
  requireSafeInteger(payload, "ttlMs", 100, 30_000, "command_request payload");
}

function validateCommandAck(value: unknown): void {
  const payload = requirePayload(value, [
    "commandId",
    "decision",
    "reason",
    "intentDigestSha256",
  ], "command_ack");
  requireIdentifier(payload, "commandId", "command_ack payload");
  requireEnum(
    payload,
    "decision",
    ["accepted", "rejected"],
    "command_ack payload",
  );
  requireNullableNonBlankString(payload, "reason", "command_ack payload");
  requireSha256(payload, "intentDigestSha256", "command_ack payload");
  requireOutcomeReason(
    payload.decision === "rejected",
    payload.reason,
    "command_ack payload",
  );
}

function validateCommandResult(value: unknown): void {
  const payload = requirePayload(value, ["commandId", "status", "reason", "detail"], "command_result");
  requireIdentifier(payload, "commandId", "command_result payload");
  requireEnum(
    payload,
    "status",
    ["succeeded", "failed", "timed_out", "cancelled"],
    "command_result payload",
  );
  requireNullableNonBlankString(payload, "reason", "command_result payload");
  requireNullableNonBlankString(payload, "detail", "command_result payload");
  requireOutcomeReason(
    payload.status !== "succeeded",
    payload.reason,
    "command_result payload",
  );
}

function validateControlFrame(value: unknown): void {
  const payload = requirePayload(value, [
    "leaseId",
    "inputSequence",
    "ttlMs",
    "forward",
    "right",
    "up",
    "yaw",
  ], "control_frame");
  requireIdentifier(payload, "leaseId", "control_frame payload");
  requireSafeInteger(
    payload,
    "inputSequence",
    1,
    MAX_SAFE_SEQUENCE,
    "control_frame payload",
  );
  requireSafeInteger(payload, "ttlMs", 50, 1_000, "control_frame payload");
  for (const axis of ["forward", "right", "up", "yaw"] as const) {
    requireFiniteNumber(payload, axis, -1, 1, "control_frame payload");
  }
}

function validateControlNeutral(value: unknown): void {
  const payload = requirePayload(value, [
    "leaseId",
    "inputSequence",
    "reason",
  ], "control_neutral");
  requireIdentifier(payload, "leaseId", "control_neutral payload");
  requireSafeInteger(
    payload,
    "inputSequence",
    1,
    MAX_SAFE_SEQUENCE,
    "control_neutral payload",
  );
  requireEnum(
    payload,
    "reason",
    ["operator_release", "pointer_cancel", "window_blur", "page_hide"],
    "control_neutral payload",
  );
}

function validateControlAck(value: unknown): void {
  const payload = requirePayload(value, [
    "leaseId",
    "inputSequence",
    "status",
    "reason",
  ], "control_ack");
  requireIdentifier(payload, "leaseId", "control_ack payload");
  requireSafeInteger(
    payload,
    "inputSequence",
    1,
    MAX_SAFE_SEQUENCE,
    "control_ack payload",
  );
  requireEnum(
    payload,
    "status",
    ["applied", "rejected", "stale"],
    "control_ack payload",
  );
  requireNullableNonBlankString(payload, "reason", "control_ack payload");
  requireOutcomeReason(
    payload.status !== "applied",
    payload.reason,
    "control_ack payload",
  );
}

function validateSafetyEvent(value: unknown): void {
  const payload = requirePayload(value, [
    "action",
    "outcome",
    "trigger",
    "leaseId",
    "lastInputSequence",
    "detail",
  ], "safety_event");
  requireEnum(payload, "action", ["neutralize"], "safety_event payload");
  requireEnum(
    payload,
    "outcome",
    ["succeeded", "failed"],
    "safety_event payload",
  );
  requireEnum(
    payload,
    "trigger",
    [
      "client_request",
      "client_disconnect",
      "lease_expired",
      "control_ttl_expired",
      "actuation_readiness_lost",
      "server_stop",
    ],
    "safety_event payload",
  );
  requireNullableIdentifier(payload, "leaseId", "safety_event payload");
  requireNullableSafeInteger(
    payload,
    "lastInputSequence",
    1,
    MAX_SAFE_SEQUENCE,
    "safety_event payload",
  );
  requireNullableNonBlankString(payload, "detail", "safety_event payload");
  if (payload.outcome === "failed" && payload.detail === null) {
    throw new ConsoleProtocolValidationError(
      "safety_event payload detail is required when neutralization fails",
    );
  }
}

function validateProtocolError(value: unknown): void {
  const payload = requirePayload(value, [
    "relatedMessageId",
    "code",
    "detail",
  ], "protocol_error");
  requireNullableIdentifier(payload, "relatedMessageId", "protocol_error payload");
  requireEnum(
    payload,
    "code",
    [
      "malformed_json",
      "invalid_envelope",
      "unsupported_protocol_version",
      "unknown_message_type",
      "wrong_message_direction",
      "invalid_payload",
      "handshake_required",
      "unexpected_message",
      "server_unavailable",
    ],
    "protocol_error payload",
  );
  requireNullableNonBlankString(payload, "detail", "protocol_error payload");
}

function requirePayload(
  value: unknown,
  fields: readonly string[],
  type: ConsoleMessageType,
): Record<string, unknown> {
  const payload = requireRecord(value, `${type} payload`);
  requireExactKeys(payload, new Set(fields), `${type} payload`);
  return payload;
}

/** Reject resource-exhaustion inputs before the recursive JSON parser runs. */
function preflightWireText(text: string): void {
  if (typeof text !== "string") {
    throw new ConsoleProtocolValidationError(
      "Console protocol wire message must be a string",
    );
  }
  if (
    text.length > MAX_WIRE_MESSAGE_UTF8_BYTES ||
    UTF8_ENCODER.encode(text).byteLength > MAX_WIRE_MESSAGE_UTF8_BYTES
  ) {
    throw new ConsoleProtocolValidationError(
      `Console protocol wire message exceeds ${MAX_WIRE_MESSAGE_UTF8_BYTES} UTF-8 bytes`,
    );
  }

  let depth = 0;
  let inString = false;
  let escaped = false;
  for (const character of text) {
    if (inString) {
      if (escaped) {
        escaped = false;
      } else if (character === "\\") {
        escaped = true;
      } else if (character === '"') {
        inString = false;
      }
      continue;
    }

    if (character === '"') {
      inString = true;
    } else if (character === "{" || character === "[") {
      depth += 1;
      if (depth > MAX_JSON_NESTING_DEPTH) {
        throw new ConsoleProtocolValidationError(
          `Console protocol JSON exceeds ${MAX_JSON_NESTING_DEPTH} nesting levels`,
        );
      }
    } else if ((character === "}" || character === "]") && depth > 0) {
      depth -= 1;
    }
  }
}

/**
 * Parse a second, lossless shadow of the bounded JSON document. JavaScript's
 * JSON.parse rounds numbers to IEEE-754 before schema validation, so a wire
 * literal such as 500.00000000000000001 otherwise becomes the integer 500.
 * Replacing every number token with a marked string preserves the original
 * decimal spelling without adding a third-party parser.
 */
function parseNumberLexemeShadow(text: string): unknown {
  let shadow = "";
  let index = 0;
  let inString = false;
  let escaped = false;

  while (index < text.length) {
    const character = text[index]!;
    if (inString) {
      shadow += character;
      if (escaped) {
        escaped = false;
      } else if (character === "\\") {
        escaped = true;
      } else if (character === '"') {
        inString = false;
      }
      index += 1;
      continue;
    }

    if (character === '"') {
      inString = true;
      shadow += character;
      index += 1;
      continue;
    }

    if (character === "-" || isAsciiDigit(character)) {
      let end = index + 1;
      while (end < text.length && isJsonNumberCharacter(text[end]!)) {
        end += 1;
      }
      const lexeme = text.slice(index, end);
      shadow += `{"${NUMBER_LEXEME_MARKER}":${JSON.stringify(lexeme)}}`;
      index = end;
      continue;
    }

    shadow += character;
    index += 1;
  }

  try {
    return JSON.parse(shadow);
  } catch {
    // The original document has already parsed successfully. Keep all parser
    // implementation details out of the rejection returned to the peer.
    throw new ConsoleProtocolValidationError("Malformed console protocol JSON");
  }
}

function assertIntegralWireLexemes(
  message: ConsoleMessage,
  shadowValue: unknown,
): void {
  const fields = INTEGRAL_PAYLOAD_FIELDS[message.type] ?? [];
  if (fields.length === 0) {
    return;
  }

  const shadowEnvelope = requireRecord(shadowValue, "console envelope shadow");
  const shadowPayload = requireRecord(
    shadowEnvelope.payload,
    `${message.type} payload shadow`,
  );
  const payload = message.payload as unknown as Record<string, unknown>;

  for (const field of fields) {
    if (payload[field] === null) {
      continue;
    }
    const marker = requireRecord(
      shadowPayload[field],
      `${message.type} payload ${field} number`,
    );
    const lexeme = marker[NUMBER_LEXEME_MARKER];
    if (typeof lexeme !== "string" || !isMathematicallyIntegral(lexeme)) {
      throw new ConsoleProtocolValidationError(
        `${message.type} payload ${field} must be mathematically integral on the wire`,
      );
    }
  }
}

function isMathematicallyIntegral(lexeme: string): boolean {
  const match = /^(-?)(\d+)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/.exec(lexeme);
  if (match === null) {
    return false;
  }

  const fractionalDigits = match[3] ?? "";
  const exponent = Number(match[4] ?? "0");
  const coefficient = `${match[2]}${fractionalDigits}`;
  if (/^0+$/.test(coefficient)) {
    return true;
  }

  const requiredTrailingZeroes = fractionalDigits.length - exponent;
  if (requiredTrailingZeroes <= 0) {
    return true;
  }
  return (
    coefficient.length >= requiredTrailingZeroes &&
    coefficient.endsWith("0".repeat(requiredTrailingZeroes))
  );
}

function isAsciiDigit(character: string): boolean {
  return character >= "0" && character <= "9";
}

function isJsonNumberCharacter(character: string): boolean {
  return (
    isAsciiDigit(character) ||
    character === "-" ||
    character === "+" ||
    character === "." ||
    character === "e" ||
    character === "E"
  );
}

function encodeWithPreflight(message: ConsoleMessage): string {
  const text = JSON.stringify(message);
  preflightWireText(text);
  return text;
}

function requireRecord(
  value: unknown,
  context: string,
): Record<string, unknown> {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw new ConsoleProtocolValidationError(`${context} must be an object`);
  }
  return value as Record<string, unknown>;
}

function requireExactKeys(
  value: Record<string, unknown>,
  expected: ReadonlySet<string>,
  context: string,
): void {
  const actual = Object.keys(value);
  const missing = [...expected].filter((field) => !Object.hasOwn(value, field));
  const unknown = actual.filter((field) => !expected.has(field));
  if (missing.length > 0 || unknown.length > 0) {
    throw new ConsoleProtocolValidationError(
      `${context} has missing or unknown fields`,
    );
  }
}

function requireString(
  value: Record<string, unknown>,
  field: string,
  context: string,
): string {
  const candidate = value[field];
  if (typeof candidate !== "string") {
    throw new ConsoleProtocolValidationError(`${context} ${field} must be a string`);
  }
  return candidate;
}

function requireNonBlankString(
  value: Record<string, unknown>,
  field: string,
  context: string,
  maximumLength: number = MAX_TEXT_LENGTH,
): string {
  const candidate = requireString(value, field, context);
  if (candidate.trim().length === 0) {
    throw new ConsoleProtocolValidationError(
      `${context} ${field} must not be blank`,
    );
  }
  if (candidate.length > maximumLength) {
    throw new ConsoleProtocolValidationError(
      `${context} ${field} exceeds the ${maximumLength} character limit`,
    );
  }
  return candidate;
}

function requireNullableNonBlankString(
  value: Record<string, unknown>,
  field: string,
  context: string,
): void {
  if (value[field] !== null) {
    requireNonBlankString(value, field, context);
  }
}

function requireOutcomeReason(
  reasonRequired: boolean,
  reason: unknown,
  context: string,
): void {
  if (reasonRequired && reason === null) {
    throw new ConsoleProtocolValidationError(
      `${context} reason is required for an unsuccessful outcome`,
    );
  }
  if (!reasonRequired && reason !== null) {
    throw new ConsoleProtocolValidationError(
      `${context} reason must be null for a successful outcome`,
    );
  }
}

function requireUniqueNonBlankStringArray(
  value: Record<string, unknown>,
  field: string,
  context: string,
  options: {
    readonly nonEmpty?: boolean;
    readonly requiredValue?: string;
    readonly maximumItemLength?: number;
    readonly maximumItems?: number;
  } = {},
): readonly string[] {
  const candidate = value[field];
  const maximumItemLength = options.maximumItemLength ?? MAX_TEXT_LENGTH;
  if (
    !Array.isArray(candidate) ||
    candidate.some(
      (item) =>
        typeof item !== "string" ||
        item.trim().length === 0 ||
        item.length > maximumItemLength,
    )
  ) {
    throw new ConsoleProtocolValidationError(
      `${context} ${field} must be an array of non-blank strings`,
    );
  }
  if (new Set(candidate).size !== candidate.length) {
    throw new ConsoleProtocolValidationError(
      `${context} ${field} must not contain duplicates`,
    );
  }
  if (
    options.maximumItems !== undefined &&
    candidate.length > options.maximumItems
  ) {
    throw new ConsoleProtocolValidationError(
      `${context} ${field} exceeds the ${options.maximumItems} item limit`,
    );
  }
  if (options.nonEmpty === true && candidate.length === 0) {
    throw new ConsoleProtocolValidationError(
      `${context} ${field} must not be empty`,
    );
  }
  if (
    options.requiredValue !== undefined &&
    !candidate.includes(options.requiredValue)
  ) {
    throw new ConsoleProtocolValidationError(
      `${context} ${field} must include ${options.requiredValue}`,
    );
  }
  return candidate;
}

function requireBoolean(
  value: Record<string, unknown>,
  field: string,
  context: string,
): void {
  if (typeof value[field] !== "boolean") {
    throw new ConsoleProtocolValidationError(`${context} ${field} must be a boolean`);
  }
}

function requireNullableBoolean(
  value: Record<string, unknown>,
  field: string,
  context: string,
): void {
  if (value[field] !== null) {
    requireBoolean(value, field, context);
  }
}

function requireIdentifier(
  value: Record<string, unknown>,
  field: string,
  context: string,
): string {
  const candidate = requireString(value, field, context);
  if (!IDENTIFIER_PATTERN.test(candidate)) {
    throw new ConsoleProtocolValidationError(
      `${context} ${field} is not a valid identifier`,
    );
  }
  return candidate;
}

function requireNullableIdentifier(
  value: Record<string, unknown>,
  field: string,
  context: string,
): void {
  if (value[field] !== null) {
    requireIdentifier(value, field, context);
  }
}

function requireEnum<T extends string>(
  value: Record<string, unknown>,
  field: string,
  allowed: readonly T[],
  context: string,
): T {
  const candidate = value[field];
  if (typeof candidate !== "string" || !allowed.includes(candidate as T)) {
    throw new ConsoleProtocolValidationError(
      `${context} ${field} is not an allowed value`,
    );
  }
  return candidate as T;
}

function requireSafeInteger(
  value: Record<string, unknown>,
  field: string,
  minimum: number,
  maximum: number,
  context: string,
): void {
  const candidate = value[field];
  if (
    typeof candidate !== "number" ||
    !Number.isSafeInteger(candidate) ||
    candidate < minimum ||
    candidate > maximum
  ) {
    throw new ConsoleProtocolValidationError(
      `${context} ${field} must be a safe integer from ${minimum} through ${maximum}`,
    );
  }
}

function requireNullableSafeInteger(
  value: Record<string, unknown>,
  field: string,
  minimum: number,
  maximum: number,
  context: string,
): void {
  if (value[field] !== null) {
    requireSafeInteger(value, field, minimum, maximum, context);
  }
}

function requireNullableInteger(
  value: Record<string, unknown>,
  field: string,
  minimum: number,
  maximum: number,
  context: string,
): void {
  const candidate = value[field];
  if (candidate === null) {
    return;
  }
  if (
    typeof candidate !== "number" ||
    !Number.isInteger(candidate) ||
    candidate < minimum ||
    candidate > maximum
  ) {
    throw new ConsoleProtocolValidationError(
      `${context} ${field} must be null or an integer from ${minimum} through ${maximum}`,
    );
  }
}

function requireFiniteNumber(
  value: Record<string, unknown>,
  field: string,
  minimum: number,
  maximum: number,
  context: string,
): void {
  const candidate = value[field];
  if (
    typeof candidate !== "number" ||
    !Number.isFinite(candidate) ||
    candidate < minimum ||
    candidate > maximum
  ) {
    throw new ConsoleProtocolValidationError(
      `${context} ${field} must be finite and within [${minimum}, ${maximum}]`,
    );
  }
}

function requireNullableFiniteNumber(
  value: Record<string, unknown>,
  field: string,
  minimum: number,
  maximum: number,
  context: string,
): void {
  if (value[field] !== null) {
    requireFiniteNumber(value, field, minimum, maximum, context);
  }
}

function requireSha256(
  value: Record<string, unknown>,
  field: string,
  context: string,
): void {
  const candidate = requireString(value, field, context);
  if (!SHA256_PATTERN.test(candidate)) {
    throw new ConsoleProtocolValidationError(
      `${context} ${field} must be a lowercase SHA-256 digest`,
    );
  }
}

function requireDate(
  value: Record<string, unknown>,
  field: string,
  context: string,
): void {
  const candidate = requireString(value, field, context);
  const match = DATE_PATTERN.exec(candidate);
  if (match === null) {
    throw new ConsoleProtocolValidationError(
      `${context} ${field} must use YYYY-MM-DD`,
    );
  }
  const date = new Date(`${candidate}T00:00:00Z`);
  if (
    Number.isNaN(date.getTime()) ||
    date.toISOString().slice(0, 10) !== candidate
  ) {
    throw new ConsoleProtocolValidationError(
      `${context} ${field} is not a valid calendar date`,
    );
  }
}

function deepFreeze<T>(value: T): T {
  if (value !== null && typeof value === "object" && !Object.isFrozen(value)) {
    for (const child of Object.values(value)) {
      deepFreeze(child);
    }
    Object.freeze(value);
  }
  return value;
}
