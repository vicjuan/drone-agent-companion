import assert from "node:assert/strict";
import test from "node:test";

import {
  confirmationPrompt,
  dispatchConfirmedCommand,
} from "../dist/assets/console-actions.js";

test("every discrete action requires explicit confirmation before dispatch", () => {
  for (const action of ["takeoff", "landing", "return_to_home"]) {
    const prompts = [];
    const dispatched = [];

    assert.equal(
      dispatchConfirmedCommand(
        action,
        (prompt) => {
          prompts.push(prompt);
          return false;
        },
        (confirmed) => dispatched.push(confirmed),
      ),
      false,
    );
    assert.deepEqual(dispatched, []);
    assert.deepEqual(prompts, [confirmationPrompt(action)]);

    assert.equal(
      dispatchConfirmedCommand(action, () => true, (confirmed) => dispatched.push(confirmed)),
      true,
    );
    assert.deepEqual(dispatched, [action]);
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
