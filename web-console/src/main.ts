import {
  CompanionConsoleClient,
  CONTROL_VECTORS,
  type ControlVector,
} from "./console-client.js";
import {
  DiscreteCommandConfirmation,
} from "./console-actions.js";
import {
  ContinuousHoldController,
  installGlobalHoldEndListeners,
  isHoldActivationKey,
} from "./continuous-hold.js";
import {
  discreteActionIntent,
  ownsControlLease,
  type CommandUiRecord,
  type ConsoleViewState,
} from "./console-state.js";
import type { CommandRequestPayload } from "./console-protocol.js";

const root = document.querySelector<HTMLElement>("#app");
if (root === null) throw new Error("Missing #app root");

root.innerHTML = `
  <div class="app-shell">
    <header class="topbar">
      <div><p class="eyebrow">DRONE AGENT COMPANION</p><h1>Flight Console</h1></div>
      <div class="connection-cluster" aria-live="polite">
        <span id="connection-pill" class="status-pill status-pill--offline">
          <span class="status-dot"></span><span id="connection-label">尚未連線</span>
        </span>
        <span id="session-label" class="session-label">Session —</span>
      </div>
    </header>

    <section class="truth-strip" aria-label="Runtime truth">
      <article class="truth-item"><span class="truth-label">Adapter</span><strong id="adapter-value">—</strong><small id="profile-value">等待 runtime</small></article>
      <article class="truth-item"><span class="truth-label">Aircraft</span><strong id="aircraft-value">—</strong><small>即時連線狀態</small></article>
      <article class="truth-item"><span class="truth-label">Actuation</span><strong id="actuation-value">—</strong><small>Server lock</small></article>
      <article class="truth-item"><span class="truth-label">Control lease</span><strong id="lease-value">—</strong><small id="lease-detail">尚無 lease 狀態</small></article>
    </section>
    <p id="runtime-truth" class="runtime-truth">Runtime 尚未回報；所有致動控制維持關閉。</p>

    <main class="dashboard-grid">
      <div class="dashboard-column dashboard-column--telemetry">
        <section class="panel telemetry-panel">
          <div class="panel-heading">
            <div><p class="eyebrow">LIVE STATE</p><h2>Telemetry</h2></div>
            <span id="telemetry-sequence" class="mono-badge">SEQ —</span>
          </div>
          <div class="battery-block">
            <div><span class="metric-label">Battery</span><strong id="battery-value" class="battery-value">—</strong></div>
            <progress id="battery-progress" class="battery-track" max="100" value="0" aria-label="Battery level"></progress>
          </div>
          <dl class="metric-grid">
            <div><dt>Flight state</dt><dd id="flight-state">—</dd></div>
            <div><dt>Altitude</dt><dd id="altitude-value">—</dd></div>
            <div><dt>Latitude</dt><dd id="latitude-value">—</dd></div>
            <div><dt>Longitude</dt><dd id="longitude-value">—</dd></div>
            <div><dt>Gimbal pitch</dt><dd id="gimbal-value">—</dd></div>
            <div><dt>Camera</dt><dd id="camera-value">—</dd></div>
          </dl>
        </section>

        <section class="panel health-panel">
          <div class="panel-heading panel-heading--compact">
            <div><p class="eyebrow">SERVER</p><h2>Health & safety</h2></div>
            <span id="health-pill" class="mini-pill">UNKNOWN</span>
          </div>
          <dl class="event-list">
            <div><dt>Connection</dt><dd id="connection-detail">尚未啟動</dd></div>
            <div><dt>Last control ack</dt><dd id="control-receipt">尚無控制回覆</dd></div>
            <div><dt>Last neutral</dt><dd id="safety-event">尚無 safety event</dd></div>
            <div><dt>Protocol</dt><dd id="protocol-event">正常</dd></div>
          </dl>
        </section>
      </div>

      <div class="dashboard-column dashboard-column--control">
        <section class="panel control-panel">
          <div class="panel-heading">
            <div><p class="eyebrow">OPERATOR</p><h2>Flight control</h2></div>
            <span id="control-mode-pill" class="mini-pill mini-pill--blocked">BLOCKED</span>
          </div>
          <div id="control-readiness" class="readiness-callout readiness-callout--blocked" role="status">Console 尚未完成連線</div>
          <div class="lease-toolbar" aria-label="Control lease actions">
            <button id="lease-acquire" class="button button--primary" type="button">取得 lease</button>
            <button id="lease-renew" class="button button--quiet" type="button">續租</button>
            <button id="lease-release" class="button button--danger-quiet" type="button">釋放</button>
          </div>
          <div class="command-section">
            <h3>Discrete actions</h3>
            <div class="command-buttons">
              <button class="action-button action-button--takeoff" type="button" data-action="takeoff"><span>TAKEOFF</span><small>起飛</small></button>
              <button class="action-button" type="button" data-action="landing"><span>LAND</span><small>降落</small></button>
              <button class="action-button action-button--rth" type="button" data-action="return_to_home"><span>RTH</span><small>返航</small></button>
            </div>
          </div>
          <div class="hold-section">
            <div class="section-heading-inline"><h3>Press & hold</h3><span>100ms frame · 250ms TTL</span></div>
            <div class="hold-grid" aria-label="Continuous directional controls">
              <button class="hold-button hold-button--ascend" type="button" data-control="ascend"><span class="hold-icon">↑</span><span>上升</span></button>
              <button class="hold-button hold-button--forward" type="button" data-control="forward"><span class="hold-icon">▲</span><span>前進</span></button>
              <button class="hold-button hold-button--yaw-left" type="button" data-control="yawLeft"><span class="hold-icon">↶</span><span>左旋</span></button>
              <div class="hold-center" aria-hidden="true"><span></span></div>
              <button class="hold-button hold-button--yaw-right" type="button" data-control="yawRight"><span class="hold-icon">↷</span><span>右旋</span></button>
              <button class="hold-button hold-button--backward" type="button" data-control="backward"><span class="hold-icon">▼</span><span>後退</span></button>
              <button class="hold-button hold-button--descend" type="button" data-control="descend"><span class="hold-icon">↓</span><span>下降</span></button>
            </div>
            <p class="hold-help">放開按鍵或 pointer、按鈕失焦、取消、遺失 pointer capture、視窗失焦或離頁都會立即送 neutral；斷線由 server dead-man 接手。</p>
          </div>
        </section>

        <section class="panel command-log-panel">
          <div class="panel-heading panel-heading--compact"><div><p class="eyebrow">RECEIPTS</p><h2>Command ack / result</h2></div></div>
          <ol id="command-log" class="command-log"><li class="empty-state">尚未送出命令</li></ol>
        </section>
      </div>

      <div class="dashboard-column dashboard-column--capability">
        <section class="panel capability-panel">
          <div class="panel-heading">
            <div><p class="eyebrow">TARGET STACK EVIDENCE</p><h2>Capability matrix</h2></div>
            <span id="capability-date" class="mono-badge">—</span>
          </div>
          <p class="capability-note">這裡顯示 Mini 4 Pro + RC-N3 + G520 的證據狀態；Mock 成功不會自動升級硬體能力。</p>
          <div id="capability-list" class="capability-list"><p class="empty-state">等待 capability snapshot</p></div>
        </section>
      </div>
    </main>

    <dialog id="command-confirmation" class="confirmation-dialog" aria-labelledby="confirmation-title" aria-describedby="confirmation-prompt confirmation-safety">
      <form id="confirmation-form" class="confirmation-card" method="dialog">
        <p class="eyebrow">OPERATOR CONFIRMATION</p>
        <h2 id="confirmation-title">確認飛行命令</h2>
        <p id="confirmation-prompt" class="confirmation-prompt"></p>
        <p id="confirmation-safety" class="confirmation-safety" aria-live="polite" hidden></p>
        <div class="confirmation-actions">
          <button class="button button--quiet" type="submit" value="cancel">取消</button>
          <button id="confirmation-confirm" class="button button--danger" type="submit" value="confirm">確認送出</button>
        </div>
      </form>
    </dialog>
  </div>
`;

const refs = {
  connectionPill: requireElement<HTMLElement>("#connection-pill"),
  connectionLabel: requireElement<HTMLElement>("#connection-label"),
  sessionLabel: requireElement<HTMLElement>("#session-label"),
  adapter: requireElement<HTMLElement>("#adapter-value"),
  profile: requireElement<HTMLElement>("#profile-value"),
  aircraft: requireElement<HTMLElement>("#aircraft-value"),
  actuation: requireElement<HTMLElement>("#actuation-value"),
  lease: requireElement<HTMLElement>("#lease-value"),
  leaseDetail: requireElement<HTMLElement>("#lease-detail"),
  runtimeTruth: requireElement<HTMLElement>("#runtime-truth"),
  telemetrySequence: requireElement<HTMLElement>("#telemetry-sequence"),
  battery: requireElement<HTMLElement>("#battery-value"),
  batteryProgress: requireElement<HTMLProgressElement>("#battery-progress"),
  flightState: requireElement<HTMLElement>("#flight-state"),
  altitude: requireElement<HTMLElement>("#altitude-value"),
  latitude: requireElement<HTMLElement>("#latitude-value"),
  longitude: requireElement<HTMLElement>("#longitude-value"),
  gimbal: requireElement<HTMLElement>("#gimbal-value"),
  camera: requireElement<HTMLElement>("#camera-value"),
  healthPill: requireElement<HTMLElement>("#health-pill"),
  connectionDetail: requireElement<HTMLElement>("#connection-detail"),
  controlReceipt: requireElement<HTMLElement>("#control-receipt"),
  safetyEvent: requireElement<HTMLElement>("#safety-event"),
  protocolEvent: requireElement<HTMLElement>("#protocol-event"),
  controlModePill: requireElement<HTMLElement>("#control-mode-pill"),
  controlReadiness: requireElement<HTMLElement>("#control-readiness"),
  leaseAcquire: requireElement<HTMLButtonElement>("#lease-acquire"),
  leaseRenew: requireElement<HTMLButtonElement>("#lease-renew"),
  leaseRelease: requireElement<HTMLButtonElement>("#lease-release"),
  commandLog: requireElement<HTMLOListElement>("#command-log"),
  capabilityDate: requireElement<HTMLElement>("#capability-date"),
  capabilityList: requireElement<HTMLElement>("#capability-list"),
  confirmationDialog: requireElement<HTMLDialogElement>("#command-confirmation"),
  confirmationForm: requireElement<HTMLFormElement>("#confirmation-form"),
  confirmationTitle: requireElement<HTMLElement>("#confirmation-title"),
  confirmationPrompt: requireElement<HTMLElement>("#confirmation-prompt"),
  confirmationSafety: requireElement<HTMLElement>("#confirmation-safety"),
  confirmationConfirm: requireElement<HTMLButtonElement>("#confirmation-confirm"),
};

const actionButtons = Array.from(document.querySelectorAll<HTMLButtonElement>("[data-action]"));
const holdButtons = Array.from(document.querySelectorAll<HTMLButtonElement>("[data-control]"));
const vectors: Readonly<Record<string, ControlVector>> = CONTROL_VECTORS;

const client = new CompanionConsoleClient({ url: consoleWebSocketUrl(), onState: render });
const holdController = new ContinuousHoldController(client);
const commandConfirmation = new DiscreteCommandConfirmation();

refs.leaseAcquire.addEventListener("click", () => client.acquireLease());
refs.leaseRenew.addEventListener("click", () => client.renewLease());
refs.leaseRelease.addEventListener("click", () => {
  holdController.clearPresentation();
  client.releaseLease();
});

for (const button of actionButtons) {
  button.addEventListener("click", () => {
    const action = button.dataset.action;
    if (!isDiscreteAction(action)) return;
    const release = holdController.prepareCommandConfirmation();
    if (release.hadActiveControl && release.neutralReceipt === null) return;
    const pending = commandConfirmation.request(action, release.neutralReceipt);
    if (pending === null) return;
    refs.confirmationTitle.textContent = pending.title;
    refs.confirmationPrompt.textContent = pending.prompt;
    refs.confirmationConfirm.disabled = pending.awaitingNeutral;
    refs.confirmationSafety.hidden = !pending.awaitingNeutral;
    refs.confirmationSafety.textContent = pending.awaitingNeutral
      ? "正在等待 server 確認 neutral；確認按鈕維持鎖定。"
      : "";
    try {
      refs.confirmationDialog.showModal();
    } catch {
      // A missing/invalid modal surface must never dispatch a command.
      commandConfirmation.cancel();
    }
  });
}

refs.confirmationForm.addEventListener("submit", (event) => {
  event.preventDefault();
  const submitter = (event as SubmitEvent).submitter;
  if (!(submitter instanceof HTMLButtonElement) || submitter.value !== "confirm") {
    closeCommandConfirmation("cancel");
    return;
  }
  const action = commandConfirmation.confirm();
  if (action === null) return;
  refs.confirmationDialog.close("confirm");
  if (!client.getIntentReadiness(discreteActionIntent(action)).enabled) return;
  holdController.clearPresentation();
  client.sendCommand(action);
});

refs.confirmationDialog.addEventListener("cancel", (event) => {
  event.preventDefault();
  closeCommandConfirmation("cancel");
});

for (const button of holdButtons) {
  const vector = vectors[button.dataset.control ?? ""];
  if (vector === undefined) continue;
  button.addEventListener("pointerdown", (event) => {
    if (button.disabled || event.button !== 0) return;
    event.preventDefault();
    holdController.beginPointer(button, event.pointerId, vector);
  });
  button.addEventListener("lostpointercapture", (event) => {
    holdController.endPointer(event.pointerId, "lost_capture");
  });
  button.addEventListener("keydown", (event) => {
    if (!isHoldActivationKey(event.key)) return;
    if (holdController.beginKeyboard(button, event.key, event.repeat, vector)) {
      event.preventDefault();
    }
  });
  button.addEventListener("blur", () => holdController.endKeyboardOnBlur(button));
}

// Capture-phase endings do not depend on the original button retaining focus,
// hover, or pointer capture. Window + document duplication is intentionally
// safe because ContinuousHoldController consumes each active token once.
installGlobalHoldEndListeners(holdController, window, document);

window.addEventListener("blur", () => {
  closeCommandConfirmation("attention_lost");
  client.handleWindowBlur();
  holdController.clearPresentation();
});
document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "hidden") {
    closeCommandConfirmation("attention_lost");
    client.handleWindowBlur();
    holdController.clearPresentation();
  }
});
window.addEventListener("pagehide", () => {
  closeCommandConfirmation("page_hide");
  holdController.clearPresentation();
  client.handlePageHide();
});
window.addEventListener("pageshow", (event) => {
  if (event.persisted) client.start();
});

render(client.getState());
client.start();

function render(state: ConsoleViewState): void {
  const { nowMonotonicMs, surface } = client.getControlSurfaceReadiness();
  const readyIntents = Object.entries(surface)
    .filter(([, readiness]) => readiness.enabled)
    .map(([intent]) => intent);
  const overallReady = readyIntents.length > 0;
  const controlModeLabel =
    readyIntents.length === Object.keys(surface).length
      ? "READY"
      : overallReady
        ? "PARTIAL READY"
        : "BLOCKED";
  const connected = state.connectionPhase === "online";
  const safety = state.lastSafetyEvent;
  if (safety?.trigger === "client_request") {
    const observation = commandConfirmation.observeNeutral(
      safety.leaseId,
      safety.lastInputSequence,
      safety.outcome,
    );
    if (observation === "ready") {
      refs.confirmationConfirm.disabled = false;
      refs.confirmationSafety.hidden = false;
      refs.confirmationSafety.textContent = "Server neutral 已確認；可送出離散命令。";
    } else if (observation === "failed") {
      closeCommandConfirmation("neutral_failed");
    }
  }
  refs.connectionPill.className = `status-pill status-pill--${connectionTone(state.connectionPhase)}`;
  refs.connectionLabel.textContent = connectionLabel(state.connectionPhase);
  refs.sessionLabel.textContent = state.sessionId ? `Session ${shortId(state.sessionId)}` : `Reconnect ${state.reconnectAttempt}`;
  refs.adapter.textContent = state.runtime?.adapter.toUpperCase() ?? "—";
  refs.profile.textContent = state.runtime ? humanize(state.runtime.operatingProfile) : "等待 runtime";
  refs.aircraft.textContent = state.runtime ? humanize(state.runtime.aircraftConnection) : "—";
  refs.aircraft.dataset.tone = state.runtime?.aircraftConnection ?? "unknown";
  refs.actuation.textContent = state.runtime ? state.runtime.actuationLock.toUpperCase() : "—";
  refs.actuation.dataset.tone = state.runtime?.actuationLock ?? "unknown";
  renderLease(state);
  refs.runtimeTruth.textContent = runtimeTruth(state, nowMonotonicMs);
  refs.runtimeTruth.dataset.adapter = state.runtime?.adapter ?? "unknown";
  renderTelemetry(state);

  refs.healthPill.textContent = state.health?.status.toUpperCase() ?? "UNKNOWN";
  refs.healthPill.dataset.tone = state.health?.status ?? "unknown";
  refs.connectionDetail.textContent = state.connectionDetail;
  refs.controlReceipt.textContent = state.lastControlReceipt
    ? `${state.lastControlReceipt.status.toUpperCase()} · seq ${state.lastControlReceipt.inputSequence}${formatReason(state.lastControlReceipt.reason)}`
    : "尚無控制回覆";
  refs.safetyEvent.textContent = state.lastSafetyEvent
    ? `${state.lastSafetyEvent.outcome.toUpperCase()} · ${humanize(state.lastSafetyEvent.trigger)}${state.lastSafetyEvent.detail ? ` · ${state.lastSafetyEvent.detail}` : ""}`
    : "尚無 safety event";
  refs.protocolEvent.textContent = state.lastProtocolError
    ? `${state.lastProtocolError.code}${formatReason(state.lastProtocolError.detail)}`
    : "正常";

  refs.controlModePill.textContent = controlModeLabel;
  refs.controlModePill.className = overallReady ? "mini-pill mini-pill--ready" : "mini-pill mini-pill--blocked";
  refs.controlReadiness.textContent = overallReady
    ? `本 session 目前可用 intents：${readyIntents.join(", ")}`
    : surface.virtual_stick.reason;
  refs.controlReadiness.className = overallReady
    ? "readiness-callout readiness-callout--ready"
    : "readiness-callout readiness-callout--blocked";

  const ownsLease = ownsControlLease(state);
  refs.leaseAcquire.disabled = !connected || state.lease?.state === "held";
  refs.leaseRenew.disabled = !connected || !ownsLease;
  refs.leaseRelease.disabled = !connected || !ownsLease;
  for (const button of actionButtons) {
    const action = button.dataset.action;
    if (!isDiscreteAction(action)) continue;
    const readiness = surface[action];
    button.disabled = !readiness.enabled;
    button.title = readiness.enabled ? "" : readiness.reason;
  }
  for (const button of holdButtons) {
    button.disabled = !surface.virtual_stick.enabled;
    button.title = surface.virtual_stick.enabled
      ? ""
      : surface.virtual_stick.reason;
  }
  if (!surface.virtual_stick.enabled) {
    holdController.clearPresentation();
  }
  const pendingAction = commandConfirmation.pendingAction;
  if (pendingAction !== null && !surface[pendingAction].enabled) {
    closeCommandConfirmation("blocked");
  }
  renderCommandLog(state.commands);
  renderCapabilities(state);
}

function renderLease(state: ConsoleViewState): void {
  const lease = state.lease;
  const denialReason = state.lastLeaseDenial?.reason;
  if (lease === null) {
    refs.lease.textContent = "—";
    refs.leaseDetail.textContent = "尚無 lease 狀態";
    return;
  }
  refs.lease.textContent = lease.state.toUpperCase();
  refs.lease.dataset.tone = lease.state;
  if (ownsControlLease(state)) {
    refs.leaseDetail.textContent = `本 session · ${formatDuration(lease.expiresInMs)}`;
  } else if (lease.state === "held") {
    refs.leaseDetail.textContent = "由另一個 session 持有";
  } else {
    refs.leaseDetail.textContent = denialReason
      ? `最近請求遭拒：${humanize(denialReason)}`
      : lease.reason
        ? humanize(lease.reason)
        : "可由 operator 取得";
  }
}

function renderTelemetry(state: ConsoleViewState): void {
  const telemetry = state.telemetry;
  refs.telemetrySequence.textContent = telemetry ? `SEQ ${telemetry.sequence}` : "SEQ —";
  refs.battery.textContent = formatPercent(telemetry?.batteryPercent ?? null);
  const battery = telemetry?.batteryPercent ?? 0;
  refs.batteryProgress.value = Math.max(0, Math.min(100, battery));
  refs.batteryProgress.dataset.level = battery <= 20 ? "low" : battery <= 40 ? "medium" : "good";
  refs.flightState.textContent = telemetry ? humanize(telemetry.flightState) : "—";
  refs.altitude.textContent = formatNumber(telemetry?.altitudeM ?? null, "m", 1);
  refs.latitude.textContent = formatNumber(telemetry?.latitude ?? null, "", 6);
  refs.longitude.textContent = formatNumber(telemetry?.longitude ?? null, "", 6);
  refs.gimbal.textContent = formatNumber(telemetry?.gimbalPitchDeg ?? null, "°", 1);
  refs.camera.textContent = telemetry?.cameraRecording == null ? "—" : telemetry.cameraRecording ? "RECORDING" : "STANDBY";
}

function renderCommandLog(commands: readonly CommandUiRecord[]): void {
  if (commands.length === 0) {
    const empty = document.createElement("li");
    empty.className = "empty-state";
    empty.textContent = "尚未送出命令";
    refs.commandLog.replaceChildren(empty);
    return;
  }
  refs.commandLog.replaceChildren(
    ...commands.map((command) => {
      const item = document.createElement("li");
      item.className = "command-record";
      const top = document.createElement("div");
      const action = document.createElement("strong");
      action.textContent = command.action ? commandLabel(command.action) : "COMMAND";
      const status = document.createElement("span");
      status.className = `receipt-status receipt-status--${command.status}`;
      status.textContent = command.status.toUpperCase();
      top.append(action, status);
      const detail = document.createElement("small");
      detail.textContent = command.detail ?? command.reason ?? shortId(command.commandId);
      item.append(top, detail);
      return item;
    }),
  );
}

function renderCapabilities(state: ConsoleViewState): void {
  const snapshot = state.capabilities;
  refs.capabilityDate.textContent = snapshot?.lastUpdated ?? "—";
  if (snapshot === null) {
    const empty = document.createElement("p");
    empty.className = "empty-state";
    empty.textContent = "等待 capability snapshot";
    refs.capabilityList.replaceChildren(empty);
    return;
  }
  refs.capabilityList.replaceChildren(
    ...snapshot.rows.map((capability) => {
      const row = document.createElement("article");
      row.className = "capability-row";
      const heading = document.createElement("div");
      const title = document.createElement("strong");
      title.textContent = humanize(capability.id);
      const status = document.createElement("span");
      status.className = `evidence-status evidence-status--${capability.status.toLowerCase()}`;
      status.textContent = capability.status;
      heading.append(title, status);
      const assessment = document.createElement("p");
      assessment.textContent = capability.assessment;
      row.append(heading, assessment);
      return row;
    }),
  );
}

function consoleWebSocketUrl(): string {
  const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
  return `${protocol}//${window.location.host}/api/console/v1`;
}

function runtimeTruth(
  state: ConsoleViewState,
  nowMonotonicMs: number,
): string {
  if (state.runtime === null) return "Runtime 尚未回報；所有致動控制維持關閉。";
  if (state.runtime.adapter === "mock") {
    return "目前為 Mock adapter：操作結果只代表模擬路徑，不構成 G520 或 aircraft 硬體證據。";
  }
  const authority = state.commissioningAuthority;
  if (
    authority?.state === "active" &&
    !authority.locallyExpired &&
    authority.expiresAtMonotonicMs !== null &&
    authority.expiresAtMonotonicMs > nowMonotonicMs
  ) {
    return `DJI runtime lock 維持 ${state.runtime.actuationLock.toUpperCase()}；本 session 只有 temporary commissioning grant 所列 intents：${authority.allowedIntents.join(", ")}。UNKNOWN 不會自動開放致動。`;
  }
  return `DJI runtime lock 維持 ${state.runtime.actuationLock.toUpperCase()}；本 session 沒有 active temporary commissioning grant，所有 DJI 致動維持關閉。`;
}

function connectionTone(phase: ConsoleViewState["connectionPhase"]): string {
  switch (phase) {
    case "online": return "online";
    case "connecting":
    case "handshaking": return "pending";
    case "retry_wait": return "warning";
    case "stopped": return "offline";
  }
}

function connectionLabel(phase: ConsoleViewState["connectionPhase"]): string {
  switch (phase) {
    case "online": return "ONLINE";
    case "connecting": return "CONNECTING";
    case "handshaking": return "HANDSHAKE";
    case "retry_wait": return "RECONNECTING";
    case "stopped": return "OFFLINE";
  }
}

function commandLabel(action: CommandRequestPayload["action"]): string {
  switch (action) {
    case "takeoff": return "TAKEOFF";
    case "landing": return "LANDING";
    case "return_to_home": return "RETURN TO HOME";
  }
}

function isDiscreteAction(value: string | undefined): value is CommandRequestPayload["action"] {
  return value === "takeoff" || value === "landing" || value === "return_to_home";
}

function closeCommandConfirmation(returnValue: string): void {
  commandConfirmation.cancel();
  refs.confirmationConfirm.disabled = false;
  refs.confirmationSafety.hidden = true;
  refs.confirmationSafety.textContent = "";
  if (refs.confirmationDialog.open) refs.confirmationDialog.close(returnValue);
}

function requireElement<T extends Element>(selector: string): T {
  const element = document.querySelector<T>(selector);
  if (element === null) throw new Error(`Missing UI element ${selector}`);
  return element;
}

function humanize(value: string): string { return value.replaceAll("_", " "); }
function shortId(value: string): string { return value.length <= 16 ? value : `${value.slice(0, 7)}…${value.slice(-6)}`; }
function formatDuration(value: number | null): string { return value === null ? "期限未知" : `${(value / 1_000).toFixed(1)}s`; }
function formatPercent(value: number | null): string { return value === null ? "—" : `${Math.round(value)}%`; }
function formatNumber(value: number | null, suffix: string, digits: number): string { return value === null ? "—" : `${value.toFixed(digits)}${suffix}`; }
function formatReason(value: string | null): string { return value ? ` · ${humanize(value)}` : ""; }
