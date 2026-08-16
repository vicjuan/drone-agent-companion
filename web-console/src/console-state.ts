import type {
  CapabilitySnapshotPayload,
  CommandRequestPayload,
  CommissioningAuthorityStatePayload,
  CommissioningIntent,
  ConsoleProtocolVersion,
  HealthPayload,
  LeaseStatePayload,
  ProtocolErrorPayload,
  RuntimeStatePayload,
  SafetyEventPayload,
  ServerConsoleMessage,
  TelemetryPayload,
} from "./console-protocol.js";

export type ConsoleConnectionPhase =
  | "stopped"
  | "connecting"
  | "handshaking"
  | "online"
  | "retry_wait";

export type CommandUiStatus =
  | "pending"
  | "accepted"
  | "rejected"
  | "succeeded"
  | "failed"
  | "timed_out"
  | "cancelled";

export interface CommandUiRecord {
  readonly commandId: string;
  readonly action: CommandRequestPayload["action"] | null;
  readonly status: CommandUiStatus;
  readonly reason: string | null;
  readonly detail: string | null;
}

export interface ControlUiReceipt {
  readonly leaseId: string;
  readonly inputSequence: number;
  readonly status: "applied" | "rejected" | "stale";
  readonly reason: string | null;
}

export interface ConsoleViewState {
  readonly connectionPhase: ConsoleConnectionPhase;
  readonly connectionDetail: string;
  readonly reconnectAttempt: number;
  readonly sessionId: string | null;
  readonly serverVersion: string | null;
  readonly negotiatedProtocolVersion: ConsoleProtocolVersion | null;
  readonly runtime: RuntimeStatePayload | null;
  readonly health: HealthPayload | null;
  readonly lease: LeaseStatePayload | null;
  /** Correlated request receipt; never replaces the global lease truth. */
  readonly lastLeaseDenial: LeaseStatePayload | null;
  readonly telemetry: TelemetryPayload | null;
  readonly capabilities: CapabilitySnapshotPayload | null;
  readonly commissioningAuthority: CommissioningAuthorityView | null;
  /** Connection-scoped high-water mark; inactive observations must not erase it. */
  readonly commissioningAuthorityFence: CommissioningAuthorityGenerationFence | null;
  readonly commands: readonly CommandUiRecord[];
  readonly lastControlReceipt: ControlUiReceipt | null;
  readonly lastSafetyEvent: SafetyEventPayload | null;
  readonly lastProtocolError: ProtocolErrorPayload | null;
}

export interface CommissioningAuthorityView
  extends CommissioningAuthorityStatePayload {
  readonly expiresAtMonotonicMs: number | null;
  readonly locallyExpired: boolean;
}

export interface CommissioningAuthorityGenerationFence {
  readonly commissioningId: string;
  readonly generation: string;
  readonly allowedIntents: readonly CommissioningIntent[];
  readonly expiresAtMonotonicMs: number | null;
  readonly locallyExpired: boolean;
  readonly terminal: boolean;
}

export interface ActuationReadiness {
  readonly enabled: boolean;
  readonly reason: string;
  readonly leaseId: string | null;
}

export interface ControlSurfaceReadiness {
  readonly takeoff: ActuationReadiness;
  readonly landing: ActuationReadiness;
  readonly return_to_home: ActuationReadiness;
  readonly virtual_stick: ActuationReadiness;
}

export class CommissioningAuthorityConflictError extends Error {
  constructor() {
    super("Conflicting commissioning authority state revision");
    this.name = "CommissioningAuthorityConflictError";
  }
}

const MAX_COMMAND_RECORDS = 16;

export function createInitialConsoleState(): ConsoleViewState {
  return {
    connectionPhase: "stopped",
    connectionDetail: "尚未連線",
    reconnectAttempt: 0,
    sessionId: null,
    serverVersion: null,
    negotiatedProtocolVersion: null,
    runtime: null,
    health: null,
    lease: null,
    lastLeaseDenial: null,
    telemetry: null,
    capabilities: null,
    commissioningAuthority: null,
    commissioningAuthorityFence: null,
    commands: [],
    lastControlReceipt: null,
    lastSafetyEvent: null,
    lastProtocolError: null,
  };
}

export function markConnecting(
  state: ConsoleViewState,
  reconnectAttempt: number,
): ConsoleViewState {
  return {
    ...state,
    connectionPhase: "connecting",
    connectionDetail:
      reconnectAttempt === 0
        ? "正在連線到 companion server"
        : `正在進行第 ${reconnectAttempt} 次重新連線`,
    reconnectAttempt,
    sessionId: null,
    serverVersion: null,
    negotiatedProtocolVersion: null,
    runtime: null,
    health: null,
    lease: null,
    lastLeaseDenial: null,
    telemetry: null,
    capabilities: null,
    commissioningAuthority: null,
    commissioningAuthorityFence: null,
    lastControlReceipt: null,
  };
}

export function markHandshaking(state: ConsoleViewState): ConsoleViewState {
  return {
    ...state,
    connectionPhase: "handshaking",
    connectionDetail: "WebSocket 已連線，正在協商 console protocol",
  };
}

export function markRetryWaiting(
  state: ConsoleViewState,
  reconnectAttempt: number,
  detail: string,
): ConsoleViewState {
  return {
    ...state,
    connectionPhase: "retry_wait",
    connectionDetail: detail,
    reconnectAttempt,
    sessionId: null,
    serverVersion: null,
    negotiatedProtocolVersion: null,
    runtime: null,
    health: null,
    lease: null,
    lastLeaseDenial: null,
    telemetry: null,
    capabilities: null,
    commissioningAuthority: null,
    commissioningAuthorityFence: null,
    lastControlReceipt: null,
  };
}

export function markStopped(
  state: ConsoleViewState,
  detail: string,
): ConsoleViewState {
  return {
    ...state,
    connectionPhase: "stopped",
    connectionDetail: detail,
    sessionId: null,
    serverVersion: null,
    negotiatedProtocolVersion: null,
    runtime: null,
    health: null,
    lease: null,
    lastLeaseDenial: null,
    telemetry: null,
    capabilities: null,
    commissioningAuthority: null,
    commissioningAuthorityFence: null,
    lastControlReceipt: null,
  };
}

export function markCommandSent(
  state: ConsoleViewState,
  commandId: string,
  action: CommandRequestPayload["action"],
): ConsoleViewState {
  return {
    ...state,
    commands: upsertCommand(state.commands, {
      commandId,
      action,
      status: "pending",
      reason: null,
      detail: null,
    }),
  };
}

export function reduceServerMessage(
  state: ConsoleViewState,
  message: ServerConsoleMessage,
  nowMonotonicMs = 0,
): ConsoleViewState {
  const base =
    message.type === "server_hello"
      ? { ...state, lastProtocolError: null }
      : state;

  switch (message.type) {
    case "server_hello":
      return {
        ...base,
        connectionPhase: "online",
        connectionDetail: "Console protocol 已就緒",
        reconnectAttempt: 0,
        sessionId: message.payload.sessionId,
        serverVersion: message.payload.serverVersion,
        negotiatedProtocolVersion:
          message.payload.selectedProtocolVersion as ConsoleProtocolVersion,
        commissioningAuthority: null,
        commissioningAuthorityFence: null,
      };
    case "runtime_state":
      return { ...base, runtime: message.payload };
    case "telemetry":
      if (
        base.telemetry !== null &&
        message.payload.sequence <= base.telemetry.sequence
      ) {
        return base;
      }
      return { ...base, telemetry: message.payload };
    case "capability_snapshot":
      return { ...base, capabilities: message.payload };
    case "health":
      return { ...base, health: message.payload };
    case "lease_state":
      if (message.payload.state === "denied") {
        return { ...base, lastLeaseDenial: message.payload };
      }
      return { ...base, lease: message.payload, lastLeaseDenial: null };
    case "command_ack": {
      const existing = findCommand(base.commands, message.payload.commandId);
      return {
        ...base,
        commands: upsertCommand(base.commands, {
          commandId: message.payload.commandId,
          action: existing?.action ?? null,
          status:
            message.payload.decision === "accepted" ? "accepted" : "rejected",
          reason: message.payload.reason,
          detail: null,
        }),
      };
    }
    case "command_result": {
      const existing = findCommand(base.commands, message.payload.commandId);
      return {
        ...base,
        commands: upsertCommand(base.commands, {
          commandId: message.payload.commandId,
          action: existing?.action ?? null,
          status: message.payload.status,
          reason: message.payload.reason,
          detail: message.payload.detail,
        }),
      };
    }
    case "control_ack":
      return {
        ...base,
        lastControlReceipt: {
          leaseId: message.payload.leaseId,
          inputSequence: message.payload.inputSequence,
          status: message.payload.status,
          reason: message.payload.reason,
        },
      };
    case "safety_event":
      return { ...base, lastSafetyEvent: message.payload };
    case "protocol_error":
      return { ...base, lastProtocolError: message.payload };
    case "commissioning_authority_state":
      return reduceCommissioningAuthority(
        base,
        message.payload,
        nowMonotonicMs,
      );
  }
}

export function markCommissioningAuthorityLocallyExpired(
  state: ConsoleViewState,
  stateRevision: string,
): ConsoleViewState {
  const authority = state.commissioningAuthority;
  if (
    authority === null ||
    authority.state !== "active" ||
    authority.stateRevision !== stateRevision ||
    authority.locallyExpired
  ) {
    return state;
  }
  return {
    ...state,
    commissioningAuthority: { ...authority, locallyExpired: true },
    commissioningAuthorityFence:
      state.commissioningAuthorityFence !== null &&
      state.commissioningAuthorityFence.generation === authority.generation &&
      state.commissioningAuthorityFence.commissioningId === authority.commissioningId
        ? { ...state.commissioningAuthorityFence, locallyExpired: true }
        : state.commissioningAuthorityFence,
  };
}

export function markCommissioningAuthorityConflict(
  state: ConsoleViewState,
): ConsoleViewState {
  return {
    ...state,
    commissioningAuthority: null,
    lastProtocolError: {
      relatedMessageId: null,
      code: "invalid_payload",
      detail: "Conflicting commissioning authority state revision.",
    },
  };
}

export function ownsControlLease(state: ConsoleViewState): boolean {
  return (
    state.sessionId !== null &&
    state.lease?.state === "held" &&
    state.lease.leaseId !== null &&
    state.lease.holderSessionId === state.sessionId
  );
}

export function actuationReadiness(
  state: ConsoleViewState,
  intent: CommissioningIntent = "virtual_stick",
  nowMonotonicMs = Number.POSITIVE_INFINITY,
): ActuationReadiness {
  if (state.connectionPhase !== "online" || state.sessionId === null) {
    return disabled("Console 尚未完成連線");
  }
  if (state.lastProtocolError !== null) {
    return disabled(`Protocol 錯誤：${state.lastProtocolError.code}`);
  }
  if (state.health?.status !== "healthy") {
    return disabled(
      state.health === null
        ? "尚未收到 server health"
        : `Server health：${state.health.status}`,
    );
  }
  if (state.runtime === null) {
    return disabled("尚未收到 runtime 狀態");
  }
  if (state.runtime.aircraftConnection !== "connected") {
    return disabled(`Aircraft：${state.runtime.aircraftConnection}`);
  }
  if (state.lastSafetyEvent?.outcome === "failed") {
    return disabled("最近一次 neutral safety action 失敗");
  }
  if (!ownsControlLease(state)) {
    return disabled("目前 session 未持有 control lease");
  }
  if (
    state.runtime.adapter === "mock" &&
    state.runtime.operatingProfile === "localhost_development"
  ) {
    if (state.runtime.actuationLock !== "unlocked") {
      return disabled("Actuation lock 尚未解除");
    }
    return ready(state, "Mock localhost 控制路徑已就緒");
  }
  if (
    state.runtime.adapter !== "dji" ||
    state.runtime.operatingProfile !== "hardware_commissioning"
  ) {
    return disabled("目前 runtime profile 不允許致動");
  }
  if (state.runtime.actuationLock !== "locked") {
    return disabled("DJI commissioning 的公開 runtime lock 必須維持 LOCKED");
  }
  if (state.negotiatedProtocolVersion !== "1.1") {
    return disabled("Protocol 1.1 commissioning authority view 尚未協商");
  }
  const authority = state.commissioningAuthority;
  if (authority === null || authority.state !== "active") {
    return disabled("本 session 沒有 temporary commissioning grant");
  }
  if (
    authority.locallyExpired ||
    authority.expiresAtMonotonicMs === null ||
    nowMonotonicMs >= authority.expiresAtMonotonicMs
  ) {
    return disabled("Temporary commissioning grant 已到期");
  }
  if (!authority.allowedIntents.includes(intent)) {
    return disabled(`Temporary commissioning grant 未開放 ${intent}`);
  }
  return ready(state, `Temporary commissioning grant 已開放 ${intent}`);
}

export function controlSurfaceReadiness(
  state: ConsoleViewState,
  nowMonotonicMs = Number.POSITIVE_INFINITY,
): ControlSurfaceReadiness {
  return {
    takeoff: actuationReadiness(state, "takeoff", nowMonotonicMs),
    landing: actuationReadiness(state, "landing", nowMonotonicMs),
    return_to_home: actuationReadiness(
      state,
      "return_to_home",
      nowMonotonicMs,
    ),
    virtual_stick: actuationReadiness(
      state,
      "virtual_stick",
      nowMonotonicMs,
    ),
  };
}

export function discreteActionIntent(
  action: CommandRequestPayload["action"],
): CommissioningIntent {
  return action;
}

function ready(
  state: ConsoleViewState,
  reason: string,
): ActuationReadiness {
  return {
    enabled: true,
    reason,
    leaseId: state.lease?.leaseId ?? null,
  };
}

function disabled(reason: string): ActuationReadiness {
  return { enabled: false, reason, leaseId: null };
}

function reduceCommissioningAuthority(
  state: ConsoleViewState,
  payload: CommissioningAuthorityStatePayload,
  nowMonotonicMs: number,
): ConsoleViewState {
  if (!Number.isFinite(nowMonotonicMs) || nowMonotonicMs < 0) {
    throw new RangeError("nowMonotonicMs must be finite and non-negative");
  }
  const current = state.commissioningAuthority;
  if (current !== null) {
    const revisionOrder = compareDecimal(payload.stateRevision, current.stateRevision);
    if (revisionOrder === 0) {
      if (!sameAuthorityRevision(current, payload)) {
        throw new CommissioningAuthorityConflictError();
      }
      return state;
    }
    if (revisionOrder < 0) return state;
  }

  return payload.state === "active"
    ? reduceActiveCommissioningAuthority(state, payload, nowMonotonicMs)
    : reduceInactiveCommissioningAuthority(state, payload);
}

function reduceActiveCommissioningAuthority(
  state: ConsoleViewState,
  payload: CommissioningAuthorityStatePayload,
  nowMonotonicMs: number,
): ConsoleViewState {
  const candidateDeadline = checkNotNull(
    authorityDeadline(payload, nowMonotonicMs),
  );
  const fence = state.commissioningAuthorityFence;
  if (fence === null) {
    return acceptNewActiveGeneration(state, payload, candidateDeadline);
  }

  const generationOrder = compareDecimal(payload.generation, fence.generation);
  if (generationOrder < 0) throw new CommissioningAuthorityConflictError();
  if (generationOrder === 0 && fence.terminal) return state;
  if (generationOrder > 0) {
    return acceptNewActiveGeneration(state, payload, candidateDeadline);
  }
  if (!sameActiveGeneration(fence, payload)) {
    throw new CommissioningAuthorityConflictError();
  }

  const expiresAtMonotonicMs = Math.min(
    checkNotNull(fence.expiresAtMonotonicMs),
    candidateDeadline,
  );
  return {
    ...state,
    commissioningAuthority: {
      ...payload,
      expiresAtMonotonicMs,
      locallyExpired: fence.locallyExpired,
    },
    commissioningAuthorityFence: {
      ...fence,
      expiresAtMonotonicMs,
    },
  };
}

function acceptNewActiveGeneration(
  state: ConsoleViewState,
  payload: CommissioningAuthorityStatePayload,
  expiresAtMonotonicMs: number,
): ConsoleViewState {
  const commissioningId = checkNotNull(payload.commissioningId);
  return {
    ...state,
    commissioningAuthority: {
      ...payload,
      expiresAtMonotonicMs,
      locallyExpired: false,
    },
    commissioningAuthorityFence: {
      commissioningId,
      generation: payload.generation,
      allowedIntents: payload.allowedIntents,
      expiresAtMonotonicMs,
      locallyExpired: false,
      terminal: false,
    },
  };
}

function reduceInactiveCommissioningAuthority(
  state: ConsoleViewState,
  payload: CommissioningAuthorityStatePayload,
): ConsoleViewState {
  const fence = state.commissioningAuthorityFence;
  if (payload.reason === "no_active_session") {
    return {
      ...state,
      commissioningAuthority: inactiveAuthorityView(payload, false),
      commissioningAuthorityFence:
        fence === null ? null : { ...fence, terminal: true },
    };
  }

  if (fence === null) {
    return acceptNewTerminalGeneration(state, payload);
  }
  const generationOrder = compareDecimal(payload.generation, fence.generation);
  if (generationOrder < 0) throw new CommissioningAuthorityConflictError();
  if (generationOrder > 0) {
    return acceptNewTerminalGeneration(state, payload);
  }
  if (fence.commissioningId !== payload.commissioningId) {
    throw new CommissioningAuthorityConflictError();
  }

  const locallyExpired =
    fence.locallyExpired || payload.reason === "ttl_expired";
  return {
    ...state,
    commissioningAuthority: inactiveAuthorityView(payload, locallyExpired),
    commissioningAuthorityFence: {
      ...fence,
      locallyExpired,
      terminal: true,
    },
  };
}

function acceptNewTerminalGeneration(
  state: ConsoleViewState,
  payload: CommissioningAuthorityStatePayload,
): ConsoleViewState {
  const locallyExpired = payload.reason === "ttl_expired";
  return {
    ...state,
    commissioningAuthority: inactiveAuthorityView(payload, locallyExpired),
    commissioningAuthorityFence: {
      commissioningId: checkNotNull(payload.commissioningId),
      generation: payload.generation,
      allowedIntents: payload.allowedIntents,
      expiresAtMonotonicMs: null,
      locallyExpired,
      terminal: true,
    },
  };
}

function inactiveAuthorityView(
  payload: CommissioningAuthorityStatePayload,
  locallyExpired: boolean,
): CommissioningAuthorityView {
  return {
    ...payload,
    expiresAtMonotonicMs: null,
    locallyExpired,
  };
}

function authorityDeadline(
  payload: CommissioningAuthorityStatePayload,
  nowMonotonicMs: number,
): number | null {
  return payload.state === "active" && payload.expiresInMs !== null
    ? nowMonotonicMs + payload.expiresInMs
    : null;
}

function sameAuthorityRevision(
  current: CommissioningAuthorityView,
  next: CommissioningAuthorityStatePayload,
): boolean {
  return (
    current.state === next.state &&
    current.commissioningId === next.commissioningId &&
    current.generation === next.generation &&
    current.reason === next.reason &&
    current.expiresInMs === next.expiresInMs &&
    current.allowedIntents.length === next.allowedIntents.length &&
    current.allowedIntents.every(
      (intent, index) => intent === next.allowedIntents[index],
    )
  );
}

function sameActiveGeneration(
  current: CommissioningAuthorityGenerationFence,
  next: CommissioningAuthorityStatePayload,
): boolean {
  return (
    next.state === "active" &&
    current.commissioningId === next.commissioningId &&
    current.generation === next.generation &&
    current.allowedIntents.length === next.allowedIntents.length &&
    current.allowedIntents.every(
      (intent, index) => intent === next.allowedIntents[index],
    )
  );
}

function compareDecimal(left: string, right: string): number {
  const leftValue = BigInt(left);
  const rightValue = BigInt(right);
  return leftValue < rightValue ? -1 : leftValue > rightValue ? 1 : 0;
}

function checkNotNull<T>(value: T | null): T {
  if (value === null) throw new Error("expected non-null value");
  return value;
}

function findCommand(
  records: readonly CommandUiRecord[],
  commandId: string,
): CommandUiRecord | undefined {
  return records.find((record) => record.commandId === commandId);
}

function upsertCommand(
  records: readonly CommandUiRecord[],
  next: CommandUiRecord,
): readonly CommandUiRecord[] {
  return [
    next,
    ...records.filter((record) => record.commandId !== next.commandId),
  ].slice(0, MAX_COMMAND_RECORDS);
}
