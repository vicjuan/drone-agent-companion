import type {
  CapabilitySnapshotPayload,
  CommandRequestPayload,
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
  readonly runtime: RuntimeStatePayload | null;
  readonly health: HealthPayload | null;
  readonly lease: LeaseStatePayload | null;
  /** Correlated request receipt; never replaces the global lease truth. */
  readonly lastLeaseDenial: LeaseStatePayload | null;
  readonly telemetry: TelemetryPayload | null;
  readonly capabilities: CapabilitySnapshotPayload | null;
  readonly commands: readonly CommandUiRecord[];
  readonly lastControlReceipt: ControlUiReceipt | null;
  readonly lastSafetyEvent: SafetyEventPayload | null;
  readonly lastProtocolError: ProtocolErrorPayload | null;
}

export interface ActuationReadiness {
  readonly enabled: boolean;
  readonly reason: string;
  readonly leaseId: string | null;
}

const MAX_COMMAND_RECORDS = 16;

export function createInitialConsoleState(): ConsoleViewState {
  return {
    connectionPhase: "stopped",
    connectionDetail: "尚未連線",
    reconnectAttempt: 0,
    sessionId: null,
    serverVersion: null,
    runtime: null,
    health: null,
    lease: null,
    lastLeaseDenial: null,
    telemetry: null,
    capabilities: null,
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
    runtime: null,
    health: null,
    lease: null,
    lastLeaseDenial: null,
    telemetry: null,
    capabilities: null,
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
    runtime: null,
    health: null,
    lease: null,
    lastLeaseDenial: null,
    telemetry: null,
    capabilities: null,
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
    runtime: null,
    health: null,
    lease: null,
    lastLeaseDenial: null,
    telemetry: null,
    capabilities: null,
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
  }
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
  if (state.runtime.actuationLock !== "unlocked") {
    return disabled("Actuation lock 尚未解除");
  }
  if (state.lastSafetyEvent?.outcome === "failed") {
    return disabled("最近一次 neutral safety action 失敗");
  }
  if (!ownsControlLease(state)) {
    return disabled("目前 session 未持有 control lease");
  }
  return {
    enabled: true,
    reason: "控制路徑已就緒",
    leaseId: state.lease?.leaseId ?? null,
  };
}

function disabled(reason: string): ActuationReadiness {
  return { enabled: false, reason, leaseId: null };
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
