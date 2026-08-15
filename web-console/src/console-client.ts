import {
  CONSOLE_PROTOCOL_VERSION,
  decodeServerConsoleMessage,
  encodeClientConsoleMessage,
  type CommandRequestPayload,
  type ConsoleEnvelope,
  type ControlNeutralPayload,
  type ServerConsoleMessage,
} from "./console-protocol.js";
import {
  actuationReadiness,
  createInitialConsoleState,
  markCommandSent,
  markConnecting,
  markHandshaking,
  markRetryWaiting,
  markStopped,
  ownsControlLease,
  reduceServerMessage,
  type ConsoleViewState,
} from "./console-state.js";

export interface ControlVector {
  readonly forward: number;
  readonly right: number;
  readonly up: number;
  readonly yaw: number;
}

export interface ControlNeutralReceipt {
  readonly leaseId: string;
  readonly inputSequence: number;
}

export const CONTROL_VECTORS = Object.freeze({
  forward: Object.freeze({ forward: 1, right: 0, up: 0, yaw: 0 }),
  backward: Object.freeze({ forward: -1, right: 0, up: 0, yaw: 0 }),
  ascend: Object.freeze({ forward: 0, right: 0, up: 1, yaw: 0 }),
  descend: Object.freeze({ forward: 0, right: 0, up: -1, yaw: 0 }),
  yawLeft: Object.freeze({ forward: 0, right: 0, up: 0, yaw: -1 }),
  yawRight: Object.freeze({ forward: 0, right: 0, up: 0, yaw: 1 }),
} satisfies Record<string, ControlVector>);

export const DEFAULT_LEASE_TTL_MS = 5_000;
export const DEFAULT_COMMAND_TTL_MS = 5_000;
export const CONTROL_FRAME_TTL_MS = 250;
export const CONTROL_FRAME_PERIOD_MS = 100;

export interface ConsoleSocket {
  readonly readyState: number;
  send(text: string): void;
  close(code?: number, reason?: string): void;
  onOpen(listener: () => void): void;
  onMessage(listener: (data: unknown) => void): void;
  onClose(listener: (detail: string) => void): void;
  onError(listener: () => void): void;
}

export type ConsoleSocketFactory = (url: string) => ConsoleSocket;

export interface ConsoleClientScheduler {
  setTimeout(callback: () => void, delayMs: number): unknown;
  clearTimeout(handle: unknown): void;
  setInterval(callback: () => void, delayMs: number): unknown;
  clearInterval(handle: unknown): void;
}

export interface CompanionConsoleClientOptions {
  readonly url: string;
  readonly socketFactory?: ConsoleSocketFactory;
  readonly scheduler?: ConsoleClientScheduler;
  readonly idFactory?: () => string;
  readonly onState?: (state: ConsoleViewState) => void;
  readonly reconnectBaseDelayMs?: number;
  readonly reconnectMaximumDelayMs?: number;
  readonly handshakeTimeoutMs?: number;
}

export interface ConsoleMessageIdFactoryOptions {
  /** Injected for tests. Null explicitly disables randomUUID and exercises the fallback. */
  readonly randomUuid?: (() => string) | null;
  /** One per browser tab/process. Defaults to time plus random entropy. */
  readonly fallbackSeed?: string;
}

const SOCKET_OPEN = 1;
const MAX_CONTROL_SEQUENCE = Number.MAX_SAFE_INTEGER;

export class CompanionConsoleClient {
  private readonly url: string;
  private readonly socketFactory: ConsoleSocketFactory;
  private readonly scheduler: ConsoleClientScheduler;
  private readonly idFactory: () => string;
  private readonly onState: (state: ConsoleViewState) => void;
  private readonly reconnectBaseDelayMs: number;
  private readonly reconnectMaximumDelayMs: number;
  private readonly handshakeTimeoutMs: number;

  private state: ConsoleViewState = createInitialConsoleState();
  private socket: ConsoleSocket | null = null;
  private connectionGeneration = 0;
  private reconnectAttempt = 0;
  private reconnectHandle: unknown | null = null;
  private handshakeHandle: unknown | null = null;
  private leaseRenewalHandle: unknown | null = null;
  private controlIntervalHandle: unknown | null = null;
  private activeControl: ControlVector | null = null;
  private controlSequence = 0;
  private sequenceLeaseId: string | null = null;
  private wantsConnection = false;

  constructor(options: CompanionConsoleClientOptions) {
    if (options.url.trim().length === 0) {
      throw new Error("Console WebSocket URL must not be blank");
    }
    this.url = options.url;
    this.socketFactory = options.socketFactory ?? createBrowserConsoleSocket;
    this.scheduler = options.scheduler ?? BROWSER_SCHEDULER;
    this.idFactory = options.idFactory ?? createConsoleMessageIdFactory();
    this.onState = options.onState ?? (() => undefined);
    this.reconnectBaseDelayMs = requirePositiveDelay(
      options.reconnectBaseDelayMs ?? 500,
      "reconnectBaseDelayMs",
    );
    this.reconnectMaximumDelayMs = requirePositiveDelay(
      options.reconnectMaximumDelayMs ?? 8_000,
      "reconnectMaximumDelayMs",
    );
    if (this.reconnectMaximumDelayMs < this.reconnectBaseDelayMs) {
      throw new Error("reconnectMaximumDelayMs must not be less than base delay");
    }
    this.handshakeTimeoutMs = requirePositiveDelay(
      options.handshakeTimeoutMs ?? 5_000,
      "handshakeTimeoutMs",
    );
  }

  getState(): ConsoleViewState {
    return this.state;
  }

  start(): void {
    if (this.wantsConnection) return;
    this.wantsConnection = true;
    this.reconnectAttempt = 0;
    this.connect();
  }

  stop(detail = "Console client 已停止"): void {
    this.neutralizeControl("page_hide", true);
    this.wantsConnection = false;
    this.clearReconnect();
    this.clearHandshake();
    this.clearLeaseRenewal();
    this.clearControlInterval();
    this.activeControl = null;
    const socket = this.socket;
    this.socket = null;
    this.connectionGeneration += 1;
    if (socket !== null) {
      runSafely(() => socket.close(1000, "console client stopped"));
    }
    this.updateState(markStopped(this.state, detail));
  }

  acquireLease(requestedTtlMs = DEFAULT_LEASE_TTL_MS): boolean {
    if (!this.isProtocolOnline()) return false;
    const message: ConsoleEnvelope<"lease_acquire"> = {
      protocolVersion: CONSOLE_PROTOCOL_VERSION,
      messageId: this.idFactory(),
      type: "lease_acquire",
      payload: { requestedTtlMs },
    };
    return this.send(message);
  }

  renewLease(requestedTtlMs = DEFAULT_LEASE_TTL_MS): boolean {
    const leaseId = this.ownedLeaseId();
    if (leaseId === null || !this.isProtocolOnline()) return false;
    const message: ConsoleEnvelope<"lease_renew"> = {
      protocolVersion: CONSOLE_PROTOCOL_VERSION,
      messageId: this.idFactory(),
      type: "lease_renew",
      payload: { leaseId, requestedTtlMs },
    };
    return this.send(message);
  }

  releaseLease(): boolean {
    const leaseId = this.ownedLeaseId();
    if (leaseId === null || !this.isProtocolOnline()) return false;
    this.neutralizeControl("operator_release", true);
    const message: ConsoleEnvelope<"lease_release"> = {
      protocolVersion: CONSOLE_PROTOCOL_VERSION,
      messageId: this.idFactory(),
      type: "lease_release",
      payload: { leaseId },
    };
    return this.send(message);
  }

  sendCommand(action: CommandRequestPayload["action"]): string | null {
    const readiness = actuationReadiness(this.state);
    if (!readiness.enabled || readiness.leaseId === null) return null;
    // Stop producing frames locally. The discrete-command core owns one atomic neutral barrier
    // before every action; a separate asynchronous neutral request would race the command and
    // make the server reject it as neutral_in_progress.
    this.clearControlInterval();
    this.activeControl = null;
    const commandId = this.idFactory();
    const message: ConsoleEnvelope<"command_request"> = {
      protocolVersion: CONSOLE_PROTOCOL_VERSION,
      messageId: commandId,
      type: "command_request",
      payload: {
        commandId,
        leaseId: readiness.leaseId,
        action,
        ttlMs: DEFAULT_COMMAND_TTL_MS,
      },
    };
    if (!this.send(message)) return null;
    this.updateState(markCommandSent(this.state, commandId, action));
    return commandId;
  }

  beginControl(vector: ControlVector): boolean {
    requireNormalizedVector(vector);
    const readiness = actuationReadiness(this.state);
    if (!readiness.enabled || readiness.leaseId === null) return false;
    if (this.activeControl !== null) {
      this.neutralizeControl("operator_release", false);
    }
    this.activeControl = Object.freeze({ ...vector });
    if (!this.sendActiveControlFrame()) {
      this.activeControl = null;
      return false;
    }
    this.clearControlInterval();
    this.controlIntervalHandle = this.scheduler.setInterval(() => {
      if (this.activeControl === null) return;
      if (!actuationReadiness(this.state).enabled) {
        this.neutralizeControl("operator_release", false);
        return;
      }
      if (!this.sendActiveControlFrame()) {
        this.clearControlInterval();
        this.activeControl = null;
      }
    }, CONTROL_FRAME_PERIOD_MS);
    return true;
  }

  releaseControl(): boolean {
    return this.neutralizeControl("operator_release", false) !== null;
  }

  /**
   * Stops a held input before an operator confirmation opens and returns the
   * sequence whose safety completion must be observed before dispatch.
   */
  releaseControlForCommandConfirmation(): ControlNeutralReceipt | null {
    return this.neutralizeControl("operator_release", false);
  }

  cancelControl(): boolean {
    return this.neutralizeControl("pointer_cancel", false) !== null;
  }

  lostPointerCapture(): boolean {
    return this.neutralizeControl("pointer_cancel", false) !== null;
  }

  handleWindowBlur(): boolean {
    return this.neutralizeControl("window_blur", true) !== null;
  }

  handlePageHide(): void {
    this.stop("頁面離開；等待重新載入後連線");
  }

  private connect(): void {
    if (!this.wantsConnection || this.socket !== null) return;
    this.clearReconnect();
    const generation = ++this.connectionGeneration;
    this.updateState(markConnecting(this.state, this.reconnectAttempt));
    let socket: ConsoleSocket;
    try {
      socket = this.socketFactory(this.url);
    } catch {
      this.scheduleReconnect("無法建立 WebSocket");
      return;
    }
    this.socket = socket;
    socket.onOpen(() => this.handleOpen(generation, socket));
    socket.onMessage((data) => this.handleMessage(generation, socket, data));
    socket.onClose((detail) =>
      this.handleDisconnect(generation, socket, detail || "WebSocket 已關閉"),
    );
    socket.onError(() =>
      this.failConnection(generation, socket, "WebSocket transport error"),
    );
  }

  private handleOpen(generation: number, socket: ConsoleSocket): void {
    if (!this.isCurrent(generation, socket) || !this.wantsConnection) return;
    this.updateState(markHandshaking(this.state));
    const hello: ConsoleEnvelope<"client_hello"> = {
      protocolVersion: CONSOLE_PROTOCOL_VERSION,
      messageId: this.idFactory(),
      type: "client_hello",
      payload: {
        clientName: "drone-agent-web-console",
        clientVersion: "0.1.0",
        supportedProtocolVersions: [CONSOLE_PROTOCOL_VERSION],
        authentication: null,
      },
    };
    if (!this.send(hello)) return;
    this.clearHandshake();
    this.handshakeHandle = this.scheduler.setTimeout(() => {
      this.failConnection(generation, socket, "Console protocol handshake timeout");
    }, this.handshakeTimeoutMs);
  }

  private handleMessage(
    generation: number,
    socket: ConsoleSocket,
    data: unknown,
  ): void {
    if (!this.isCurrent(generation, socket)) return;
    if (typeof data !== "string") {
      this.failConnection(generation, socket, "Server 傳回非文字訊息");
      return;
    }
    let message: ServerConsoleMessage;
    try {
      message = decodeServerConsoleMessage(data);
    } catch {
      this.failConnection(generation, socket, "Server 訊息未通過 protocol 驗證");
      return;
    }
    if (
      message.type === "server_hello" &&
      message.payload.selectedProtocolVersion !== CONSOLE_PROTOCOL_VERSION
    ) {
      this.failConnection(generation, socket, "Server 選擇不支援的 protocol version");
      return;
    }

    const wasReady = actuationReadiness(this.state).enabled;
    this.updateState(reduceServerMessage(this.state, message));
    if (message.type === "server_hello") {
      this.clearHandshake();
      this.reconnectAttempt = 0;
    }
    if (message.type === "lease_state") {
      this.onLeaseStateChanged();
    }
    const isReady = actuationReadiness(this.state).enabled;
    if (this.activeControl !== null && wasReady && !isReady) {
      this.neutralizeControl("operator_release", false);
    }
    if (
      this.activeControl !== null &&
      message.type === "control_ack" &&
      message.payload.status !== "applied"
    ) {
      this.neutralizeControl("operator_release", false);
    }
    if (message.type === "protocol_error") {
      this.failConnection(
        generation,
        socket,
        `Protocol error：${message.payload.code}`,
      );
      return;
    }
  }

  private onLeaseStateChanged(): void {
    this.clearLeaseRenewal();
    if (!ownsControlLease(this.state)) {
      this.clearControlInterval();
      this.activeControl = null;
      return;
    }
    const leaseId = this.ownedLeaseId();
    if (leaseId !== this.sequenceLeaseId) {
      this.sequenceLeaseId = leaseId;
      this.controlSequence = 0;
    }
    const expiresInMs = this.state.lease?.expiresInMs;
    if (expiresInMs === null || expiresInMs === undefined) return;
    const renewInMs = Math.max(250, Math.floor(expiresInMs * 0.6));
    this.leaseRenewalHandle = this.scheduler.setTimeout(() => {
      this.leaseRenewalHandle = null;
      this.renewLease();
    }, renewInMs);
  }

  private sendActiveControlFrame(): boolean {
    const vector = this.activeControl;
    const readiness = actuationReadiness(this.state);
    if (vector === null || !readiness.enabled || readiness.leaseId === null) {
      return false;
    }
    const inputSequence = this.nextControlSequence(readiness.leaseId);
    if (inputSequence === null) return false;
    const message: ConsoleEnvelope<"control_frame"> = {
      protocolVersion: CONSOLE_PROTOCOL_VERSION,
      messageId: this.idFactory(),
      type: "control_frame",
      payload: {
        leaseId: readiness.leaseId,
        inputSequence,
        ttlMs: CONTROL_FRAME_TTL_MS,
        ...vector,
      },
    };
    return this.send(message);
  }

  private neutralizeControl(
    reason: ControlNeutralPayload["reason"],
    evenWhenIdle: boolean,
  ): ControlNeutralReceipt | null {
    const hadActiveControl = this.activeControl !== null;
    this.clearControlInterval();
    this.activeControl = null;
    if (!hadActiveControl && !evenWhenIdle) return null;
    const leaseId = this.ownedLeaseId();
    if (leaseId === null || !this.isProtocolOnline()) return null;
    const inputSequence = this.nextControlSequence(leaseId);
    if (inputSequence === null) return null;
    const message: ConsoleEnvelope<"control_neutral"> = {
      protocolVersion: CONSOLE_PROTOCOL_VERSION,
      messageId: this.idFactory(),
      type: "control_neutral",
      payload: { leaseId, inputSequence, reason },
    };
    return this.send(message) ? { leaseId, inputSequence } : null;
  }

  private nextControlSequence(leaseId: string): number | null {
    if (this.sequenceLeaseId !== leaseId) {
      this.sequenceLeaseId = leaseId;
      this.controlSequence = 0;
    }
    if (this.controlSequence >= MAX_CONTROL_SEQUENCE) {
      const socket = this.socket;
      if (socket !== null) {
        this.failConnection(
          this.connectionGeneration,
          socket,
          "Control sequence 已耗盡",
        );
      }
      return null;
    }
    this.controlSequence += 1;
    return this.controlSequence;
  }

  private send(message: unknown): boolean {
    const socket = this.socket;
    if (socket === null || socket.readyState !== SOCKET_OPEN) return false;
    try {
      socket.send(encodeClientConsoleMessage(message));
      return true;
    } catch {
      this.failConnection(
        this.connectionGeneration,
        socket,
        "Client 訊息無法送出",
      );
      return false;
    }
  }

  private failConnection(
    generation: number,
    socket: ConsoleSocket,
    detail: string,
  ): void {
    if (!this.isCurrent(generation, socket)) return;
    this.socket = null;
    this.connectionGeneration += 1;
    runSafely(() => socket.close(1011, "console connection reset"));
    this.scheduleReconnect(detail);
  }

  private handleDisconnect(
    generation: number,
    socket: ConsoleSocket,
    detail: string,
  ): void {
    if (!this.isCurrent(generation, socket)) return;
    this.socket = null;
    this.scheduleReconnect(detail);
  }

  private scheduleReconnect(detail: string): void {
    this.clearHandshake();
    this.clearLeaseRenewal();
    this.clearControlInterval();
    this.activeControl = null;
    if (!this.wantsConnection) {
      this.updateState(markStopped(this.state, detail));
      return;
    }
    this.reconnectAttempt += 1;
    const exponent = Math.min(this.reconnectAttempt - 1, 20);
    const delayMs = Math.min(
      this.reconnectMaximumDelayMs,
      this.reconnectBaseDelayMs * 2 ** exponent,
    );
    this.updateState(
      markRetryWaiting(
        this.state,
        this.reconnectAttempt,
        `${detail}；${delayMs}ms 後重試`,
      ),
    );
    this.clearReconnect();
    this.reconnectHandle = this.scheduler.setTimeout(() => {
      this.reconnectHandle = null;
      this.connect();
    }, delayMs);
  }

  private isCurrent(generation: number, socket: ConsoleSocket): boolean {
    return generation === this.connectionGeneration && socket === this.socket;
  }

  private isProtocolOnline(): boolean {
    return (
      this.state.connectionPhase === "online" &&
      this.state.sessionId !== null &&
      this.socket?.readyState === SOCKET_OPEN
    );
  }

  private ownedLeaseId(): string | null {
    return ownsControlLease(this.state) ? this.state.lease?.leaseId ?? null : null;
  }

  private updateState(next: ConsoleViewState): void {
    this.state = next;
    runSafely(() => this.onState(this.state));
  }

  private clearReconnect(): void {
    if (this.reconnectHandle === null) return;
    this.scheduler.clearTimeout(this.reconnectHandle);
    this.reconnectHandle = null;
  }

  private clearHandshake(): void {
    if (this.handshakeHandle === null) return;
    this.scheduler.clearTimeout(this.handshakeHandle);
    this.handshakeHandle = null;
  }

  private clearLeaseRenewal(): void {
    if (this.leaseRenewalHandle === null) return;
    this.scheduler.clearTimeout(this.leaseRenewalHandle);
    this.leaseRenewalHandle = null;
  }

  private clearControlInterval(): void {
    if (this.controlIntervalHandle === null) return;
    this.scheduler.clearInterval(this.controlIntervalHandle);
    this.controlIntervalHandle = null;
  }
}

function createBrowserConsoleSocket(url: string): ConsoleSocket {
  const socket = new WebSocket(url);
  return {
    get readyState() {
      return socket.readyState;
    },
    send: (text) => socket.send(text),
    close: (code, reason) => socket.close(code, reason),
    onOpen: (listener) => socket.addEventListener("open", listener),
    onMessage: (listener) =>
      socket.addEventListener("message", (event) => listener(event.data)),
    onClose: (listener) =>
      socket.addEventListener("close", (event) =>
        listener(event.reason || `WebSocket closed (${event.code})`),
      ),
    onError: (listener) => socket.addEventListener("error", listener),
  };
}

const BROWSER_SCHEDULER: ConsoleClientScheduler = {
  setTimeout: (callback, delayMs) => globalThis.setTimeout(callback, delayMs),
  clearTimeout: (handle) => globalThis.clearTimeout(handle as number),
  setInterval: (callback, delayMs) => globalThis.setInterval(callback, delayMs),
  clearInterval: (handle) => globalThis.clearInterval(handle as number),
};

export function createConsoleMessageIdFactory(
  options: ConsoleMessageIdFactoryOptions = {},
): () => string {
  const randomUuid =
    options.randomUuid === undefined ? browserRandomUuid() : options.randomUuid;
  const fallbackSeed = options.fallbackSeed ?? createFallbackSeed();
  if (!/^[A-Za-z0-9][A-Za-z0-9._:-]{0,39}$/.test(fallbackSeed)) {
    throw new Error("fallbackSeed must be a protocol-safe token up to 40 characters");
  }
  let sequence = 0;
  return () => {
    if (randomUuid !== null) {
      const uuid = runForValue(randomUuid);
      if (uuid !== null && /^[0-9a-f]{8}-[0-9a-f-]{27}$/i.test(uuid)) {
        return `web-${uuid}`;
      }
    }
    sequence += 1;
    return `web-${fallbackSeed}-${sequence.toString(36)}`;
  };
}

function browserRandomUuid(): (() => string) | null {
  const randomUuid = globalThis.crypto?.randomUUID;
  return typeof randomUuid === "function"
    ? () => randomUuid.call(globalThis.crypto)
    : null;
}

function createFallbackSeed(): string {
  return `${Date.now().toString(36)}-${Math.floor(Math.random() * Number.MAX_SAFE_INTEGER).toString(36)}`;
}

function runForValue(action: () => string): string | null {
  try {
    return action();
  } catch {
    return null;
  }
}

function requirePositiveDelay(value: number, name: string): number {
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new Error(`${name} must be a positive safe integer`);
  }
  return value;
}

function requireNormalizedVector(vector: ControlVector): void {
  for (const value of [vector.forward, vector.right, vector.up, vector.yaw]) {
    if (!Number.isFinite(value) || value < -1 || value > 1) {
      throw new Error("Control vector axes must be finite and normalized");
    }
  }
}

function runSafely(action: () => void): void {
  try {
    action();
  } catch {
    // UI and transport observers are never allowed to break the safety state machine.
  }
}
