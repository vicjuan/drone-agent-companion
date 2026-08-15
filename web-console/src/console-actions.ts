import type { CommandRequestPayload } from "./console-protocol.js";

export type DiscreteCommandAction = CommandRequestPayload["action"];

export interface PendingCommandConfirmation {
  readonly action: DiscreteCommandAction;
  readonly title: string;
  readonly prompt: string;
  readonly awaitingNeutral: boolean;
}

export type NeutralObservation = "ignored" | "ready" | "failed";

export interface RequiredNeutralReceipt {
  readonly leaseId: string;
  readonly inputSequence: number;
}

/**
 * One-shot confirmation state for the in-page modal. Confirm/cancel consumes
 * the pending action, so duplicate DOM events can never dispatch it twice.
 */
export class DiscreteCommandConfirmation {
  private pending: PendingCommandConfirmation | null = null;
  private requiredNeutralReceipt: RequiredNeutralReceipt | null = null;

  request(
    action: DiscreteCommandAction,
    requiredNeutralReceipt: RequiredNeutralReceipt | null = null,
  ): PendingCommandConfirmation | null {
    if (this.pending !== null) return null;
    const pending = {
      action,
      title: confirmationTitle(action),
      prompt: confirmationPrompt(action),
      awaitingNeutral: requiredNeutralReceipt !== null,
    } as const;
    this.pending = pending;
    this.requiredNeutralReceipt = requiredNeutralReceipt;
    return pending;
  }

  observeNeutral(
    leaseId: string | null,
    inputSequence: number | null,
    outcome: "succeeded" | "failed",
  ): NeutralObservation {
    if (
      this.pending === null ||
      this.requiredNeutralReceipt === null ||
      leaseId !== this.requiredNeutralReceipt.leaseId ||
      inputSequence !== this.requiredNeutralReceipt.inputSequence
    ) {
      return "ignored";
    }
    if (outcome === "failed") {
      this.cancel();
      return "failed";
    }
    this.requiredNeutralReceipt = null;
    this.pending = { ...this.pending, awaitingNeutral: false };
    return "ready";
  }

  confirm(): DiscreteCommandAction | null {
    if (this.requiredNeutralReceipt !== null) return null;
    const action = this.pending?.action ?? null;
    this.pending = null;
    return action;
  }

  cancel(): void {
    this.pending = null;
    this.requiredNeutralReceipt = null;
  }

  get pendingAction(): DiscreteCommandAction | null {
    return this.pending?.action ?? null;
  }
}

export function confirmationPrompt(action: DiscreteCommandAction): string {
  switch (action) {
    case "takeoff":
      return "確認送出 TAKEOFF？請先確認槳葉與周圍淨空。";
    case "landing":
      return "確認送出 LANDING？Server 會先強制 neutral，命令接受後 control lease 將釋放。";
    case "return_to_home":
      return "確認送出 RETURN TO HOME？Server 會先強制 neutral，命令接受後 control lease 將釋放。";
  }
}

export function confirmationTitle(action: DiscreteCommandAction): string {
  switch (action) {
    case "takeoff":
      return "確認起飛";
    case "landing":
      return "確認降落";
    case "return_to_home":
      return "確認返航";
  }
}
