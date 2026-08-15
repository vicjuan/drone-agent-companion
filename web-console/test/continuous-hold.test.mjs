import assert from "node:assert/strict";
import test from "node:test";

import { CONTROL_VECTORS } from "../dist/assets/console-client.js";
import {
  ContinuousHoldController,
  installGlobalHoldEndListeners,
} from "../dist/assets/continuous-hold.js";

class FakeGlobalTarget {
  registrations = [];

  addEventListener(type, listener, options) {
    this.registrations.push({ type, listener, options });
  }

  dispatch(type, event) {
    for (const registration of this.registrations) {
      if (registration.type === type) registration.listener(event);
    }
  }
}

function endingEvent(properties) {
  return {
    ...properties,
    preventDefaultCalls: 0,
    preventDefault() {
      this.preventDefaultCalls += 1;
    },
  };
}

function fakeTarget({ captureThrows = false, disabled = false } = {}) {
  const classes = new Set();
  const captureCalls = [];
  return {
    disabled,
    classes,
    captureCalls,
    classList: {
      add: (token) => classes.add(token),
      remove: (token) => classes.delete(token),
    },
    setPointerCapture(pointerId) {
      captureCalls.push(pointerId);
      if (captureThrows) throw new Error("pointer capture unavailable");
    },
  };
}

function harness() {
  const calls = [];
  let confirmationSequence = 40;
  const port = {
    beginControl(vector) {
      calls.push({ type: "begin", vector });
      return true;
    },
    releaseControl() {
      calls.push({ type: "release" });
      return true;
    },
    releaseControlForCommandConfirmation() {
      confirmationSequence += 1;
      calls.push({ type: "confirmation_release", sequence: confirmationSequence });
      return { leaseId: "lease-held", inputSequence: confirmationSequence };
    },
    cancelControl() {
      calls.push({ type: "cancel" });
      return true;
    },
    lostPointerCapture() {
      calls.push({ type: "lost_capture" });
      return true;
    },
  };
  return { calls, controller: new ContinuousHoldController(port) };
}

test("global keyup releases a keyboard hold exactly once after focus moves", () => {
  const { calls, controller } = harness();
  const originalButton = fakeTarget();
  const newFocus = fakeTarget();
  const fakeWindow = new FakeGlobalTarget();
  const fakeDocument = new FakeGlobalTarget();
  installGlobalHoldEndListeners(controller, fakeWindow, fakeDocument);

  assert.equal(
    controller.beginKeyboard(originalButton, " ", false, CONTROL_VECTORS.ascend),
    true,
  );
  assert.equal(originalButton.classes.has("is-active"), true);
  assert.equal(controller.endKeyboardOnBlur(newFocus), false);

  // Window capture sees this keyup before document capture. Dispatching the
  // same event along both phases must not produce another neutral.
  const keyup = endingEvent({ key: " " });
  fakeWindow.dispatch("keyup", keyup);
  fakeDocument.dispatch("keyup", keyup);
  assert.equal(controller.endKeyboardOnBlur(originalButton), false);
  assert.equal(keyup.preventDefaultCalls, 1);
  assert.equal(originalButton.classes.has("is-active"), false);
  assert.deepEqual(calls.map(({ type }) => type), ["begin", "release"]);
  assert.deepEqual(
    [...fakeWindow.registrations, ...fakeDocument.registrations].map(
      ({ type, options }) => ({ type, options }),
    ),
    [
      { type: "keyup", options: true },
      { type: "pointerup", options: true },
      { type: "pointercancel", options: true },
      { type: "keyup", options: true },
      { type: "pointerup", options: true },
      { type: "pointercancel", options: true },
    ],
  );
});

test("blur of the original keyboard button releases before a later keyup", () => {
  const { calls, controller } = harness();
  const button = fakeTarget();

  assert.equal(
    controller.beginKeyboard(button, "Enter", false, CONTROL_VECTORS.backward),
    true,
  );
  assert.equal(controller.beginKeyboard(button, "Enter", true, CONTROL_VECTORS.backward), true);
  assert.equal(controller.endKeyboardOnBlur(button), true);
  assert.equal(controller.endKeyboard("Enter"), false);
  assert.deepEqual(calls.map(({ type }) => type), ["begin", "release"]);
});

test("document pointerup closes a hold when pointer capture failed or pointer left button", () => {
  const { calls, controller } = harness();
  const button = fakeTarget({ captureThrows: true });
  const fakeWindow = new FakeGlobalTarget();
  const fakeDocument = new FakeGlobalTarget();
  installGlobalHoldEndListeners(controller, fakeWindow, fakeDocument);

  assert.equal(controller.beginPointer(button, 41, CONTROL_VECTORS.forward), true);
  assert.deepEqual(button.captureCalls, [41]);
  assert.equal(button.classes.has("is-active"), true);
  assert.equal(controller.endPointer(99, "release"), false);

  // Matching pointerup can originate outside the original button. A later
  // button/lost-capture event for the same id is an idempotent no-op.
  const pointerup = endingEvent({ pointerId: 41 });
  fakeWindow.dispatch("pointerup", pointerup);
  fakeDocument.dispatch("pointerup", pointerup);
  assert.equal(controller.endPointer(41, "cancel"), false);
  assert.equal(controller.endPointer(41, "lost_capture"), false);
  assert.equal(pointerup.preventDefaultCalls, 1);
  assert.equal(button.classes.has("is-active"), false);
  assert.deepEqual(calls.map(({ type }) => type), ["begin", "release"]);
});

test("document pointercancel matches only the active pointer id", () => {
  const { calls, controller } = harness();
  const button = fakeTarget();

  assert.equal(controller.beginPointer(button, 7, CONTROL_VECTORS.yawLeft), true);
  assert.equal(controller.endPointer(8, "cancel"), false);
  assert.equal(controller.endPointer(7, "cancel"), true);
  assert.equal(controller.endPointer(7, "release"), false);
  assert.deepEqual(calls.map(({ type }) => type), ["begin", "cancel"]);
});

test("opening command confirmation consumes a hold and requests one neutral", () => {
  const { calls, controller } = harness();
  const button = fakeTarget();

  assert.equal(controller.beginPointer(button, 12, CONTROL_VECTORS.forward), true);
  assert.deepEqual(controller.prepareCommandConfirmation(), {
    hadActiveControl: true,
    neutralReceipt: { leaseId: "lease-held", inputSequence: 41 },
  });
  assert.equal(button.classes.has("is-active"), false);
  assert.equal(controller.endPointer(12, "release"), false);
  assert.deepEqual(calls.map(({ type }) => type), ["begin", "confirmation_release"]);

  assert.deepEqual(controller.prepareCommandConfirmation(), {
    hadActiveControl: false,
    neutralReceipt: null,
  });
  assert.deepEqual(calls.map(({ type }) => type), ["begin", "confirmation_release"]);
});
