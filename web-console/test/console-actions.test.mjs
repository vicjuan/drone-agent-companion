import assert from "node:assert/strict";
import test from "node:test";

import {
  DiscreteCommandConfirmation,
  confirmationPrompt,
  confirmationTitle,
} from "../dist/assets/console-actions.js";

test("every discrete action requires explicit confirmation before dispatch", () => {
  const confirmation = new DiscreteCommandConfirmation();
  for (const action of ["takeoff", "landing", "return_to_home"]) {
    confirmation.request(action);
    confirmation.cancel();
    assert.equal(confirmation.confirm(), null);

    confirmation.request(action);
    assert.equal(confirmation.confirm(), action);
  }
});

test("takeoff confirmation calls out propeller and surrounding clearance", () => {
  const prompt = confirmationPrompt("takeoff");
  assert.match(prompt, /TAKEOFF/);
  assert.match(prompt, /槳葉與周圍淨空/);
});

test("landing and RTH confirmations state the neutral barrier and lease release", () => {
  for (const action of ["landing", "return_to_home"]) {
    const prompt = confirmationPrompt(action);
    assert.match(prompt, /neutral/);
    assert.match(prompt, /lease 將釋放/);
  }
});

test("in-page confirmation consumes each pending action exactly once", () => {
  const confirmation = new DiscreteCommandConfirmation();

  for (const action of ["takeoff", "landing", "return_to_home"]) {
    assert.deepEqual(confirmation.request(action), {
      action,
      title: confirmationTitle(action),
      prompt: confirmationPrompt(action),
      awaitingNeutral: false,
    });
    assert.equal(confirmation.pendingAction, action);
    assert.equal(confirmation.confirm(), action);
    assert.equal(confirmation.pendingAction, null);
    assert.equal(confirmation.confirm(), null);
  }

  confirmation.request("takeoff");
  confirmation.cancel();
  assert.equal(confirmation.pendingAction, null);
  assert.equal(confirmation.confirm(), null);
});

test("a second request cannot replace a pending neutral-gated confirmation", () => {
  const confirmation = new DiscreteCommandConfirmation();
  const required = { leaseId: "lease-a", inputSequence: 2 };

  assert.equal(confirmation.request("takeoff", required)?.action, "takeoff");
  assert.equal(confirmation.request("landing"), null);
  assert.equal(confirmation.pendingAction, "takeoff");
  assert.equal(confirmation.observeNeutral("lease-a", 2, "succeeded"), "ready");
  assert.equal(confirmation.confirm(), "takeoff");
});

test("confirmation waits for the matching successful neutral and fails closed", () => {
  const confirmation = new DiscreteCommandConfirmation();

  assert.deepEqual(
    confirmation.request("takeoff", { leaseId: "lease-b", inputSequence: 7 }),
    {
      action: "takeoff",
      title: confirmationTitle("takeoff"),
      prompt: confirmationPrompt("takeoff"),
      awaitingNeutral: true,
    },
  );
  assert.equal(confirmation.confirm(), null);
  assert.equal(confirmation.observeNeutral("lease-a", 7, "succeeded"), "ignored");
  assert.equal(confirmation.observeNeutral("lease-b", 6, "succeeded"), "ignored");
  assert.equal(confirmation.confirm(), null);
  assert.equal(confirmation.observeNeutral("lease-b", 7, "succeeded"), "ready");
  assert.equal(confirmation.confirm(), "takeoff");
  assert.equal(confirmation.confirm(), null);

  confirmation.request("landing", { leaseId: "lease-c", inputSequence: 9 });
  assert.equal(confirmation.observeNeutral("lease-c", 9, "failed"), "failed");
  assert.equal(confirmation.pendingAction, null);
  assert.equal(confirmation.confirm(), null);
});
