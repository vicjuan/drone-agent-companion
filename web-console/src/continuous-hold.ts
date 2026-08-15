import type { ControlVector } from "./console-client.js";

export type HoldActivationKey = " " | "Enter";

export interface HoldPresentationTarget {
  readonly disabled: boolean;
  readonly classList: {
    add(token: string): void;
    remove(token: string): void;
  };
  setPointerCapture(pointerId: number): void;
}

export interface ContinuousControlPort {
  beginControl(vector: ControlVector): boolean;
  releaseControl(): boolean;
  cancelControl(): boolean;
  lostPointerCapture(): boolean;
}

export type PointerHoldEnding = "release" | "cancel" | "lost_capture";

interface ActivePointer<TTarget extends HoldPresentationTarget> {
  readonly target: TTarget;
  readonly pointerId: number;
}

/**
 * Owns the UI-side identity of one continuous-control gesture.
 *
 * Browser pointer/key ending events can be observed by more than one listener
 * (window capture, document capture, then the original button). Every ending
 * clears its active token before invoking the control port, so those duplicate
 * observations cannot emit a second neutral frame.
 */
export class ContinuousHoldController<TTarget extends HoldPresentationTarget> {
  private activePointer: ActivePointer<TTarget> | null = null;
  private activeKeyboardTarget: TTarget | null = null;

  constructor(private readonly port: ContinuousControlPort) {}

  beginPointer(target: TTarget, pointerId: number, vector: ControlVector): boolean {
    if (target.disabled) return false;
    this.clearPresentation();
    if (!this.port.beginControl(vector)) return false;
    this.activePointer = { target, pointerId };
    target.classList.add("is-active");
    try {
      target.setPointerCapture(pointerId);
    } catch {
      // Capture is only an optimization. Document/window capture listeners own
      // the fallback ending path when the browser rejects pointer capture.
    }
    return true;
  }

  endPointer(pointerId: number, ending: PointerHoldEnding): boolean {
    const active = this.activePointer;
    if (active === null || active.pointerId !== pointerId) return false;
    this.activePointer = null;
    active.target.classList.remove("is-active");
    switch (ending) {
      case "release":
        this.port.releaseControl();
        break;
      case "cancel":
        this.port.cancelControl();
        break;
      case "lost_capture":
        this.port.lostPointerCapture();
        break;
    }
    return true;
  }

  beginKeyboard(
    target: TTarget,
    key: string,
    repeat: boolean,
    vector: ControlVector,
  ): boolean {
    if (target.disabled || !isHoldActivationKey(key)) return false;
    if (repeat) return this.activeKeyboardTarget === target;
    this.clearPresentation();
    if (!this.port.beginControl(vector)) return false;
    this.activeKeyboardTarget = target;
    target.classList.add("is-active");
    return true;
  }

  endKeyboard(key: string): boolean {
    if (!isHoldActivationKey(key) || this.activeKeyboardTarget === null) return false;
    const target = this.activeKeyboardTarget;
    this.activeKeyboardTarget = null;
    target.classList.remove("is-active");
    this.port.releaseControl();
    return true;
  }

  endKeyboardOnBlur(target: TTarget): boolean {
    if (this.activeKeyboardTarget !== target) return false;
    this.activeKeyboardTarget = null;
    target.classList.remove("is-active");
    this.port.releaseControl();
    return true;
  }

  clearPresentation(): void {
    this.activePointer?.target.classList.remove("is-active");
    this.activeKeyboardTarget?.classList.remove("is-active");
    this.activePointer = null;
    this.activeKeyboardTarget = null;
  }
}

export function isHoldActivationKey(key: string): key is HoldActivationKey {
  return key === " " || key === "Enter";
}

export function installGlobalHoldEndListeners<TTarget extends HoldPresentationTarget>(
  controller: ContinuousHoldController<TTarget>,
  windowTarget: Window,
  documentTarget: Document,
): void {
  const finishKeyboard = (event: KeyboardEvent): void => {
    if (controller.endKeyboard(event.key)) event.preventDefault();
  };
  const finishPointer =
    (ending: PointerHoldEnding) =>
    (event: PointerEvent): void => {
      if (controller.endPointer(event.pointerId, ending)) event.preventDefault();
    };
  const releasePointer = finishPointer("release");
  const cancelPointer = finishPointer("cancel");

  windowTarget.addEventListener("keyup", finishKeyboard, true);
  documentTarget.addEventListener("keyup", finishKeyboard, true);
  windowTarget.addEventListener("pointerup", releasePointer, true);
  documentTarget.addEventListener("pointerup", releasePointer, true);
  windowTarget.addEventListener("pointercancel", cancelPointer, true);
  documentTarget.addEventListener("pointercancel", cancelPointer, true);
}
