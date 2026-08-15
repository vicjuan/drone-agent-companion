import type { CommandRequestPayload } from "./console-protocol.js";

export type DiscreteCommandAction = CommandRequestPayload["action"];

/** Keeps every high-consequence discrete action behind the same explicit operator decision. */
export function dispatchConfirmedCommand(
  action: DiscreteCommandAction,
  confirmOperator: (prompt: string) => boolean,
  dispatch: (action: DiscreteCommandAction) => void,
): boolean {
  if (!confirmOperator(confirmationPrompt(action))) return false;
  dispatch(action);
  return true;
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
