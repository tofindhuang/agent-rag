import assert from "node:assert/strict";
import { test } from "node:test";
import { DrainGate } from "./drain-gate.js";

test("admits requests until drain starts, then rejects new ones", () => {
  const gate = new DrainGate();

  assert.equal(gate.tryEnter(), true);
  gate.beginDrain();
  assert.equal(gate.tryEnter(), false);

  gate.leave();
  assert.equal(gate.inflightCount(), 0);
});

test("drain waits for every admitted request", async () => {
  const gate = new DrainGate();
  gate.tryEnter();
  gate.tryEnter();

  const waiting = gate.waitForZero(1_000);
  gate.leave();
  assert.equal(gate.inflightCount(), 1);

  gate.leave();
  assert.equal(await waiting, true);
});

test("drain returns false when the deadline expires", async () => {
  const gate = new DrainGate();
  gate.tryEnter();

  assert.equal(await gate.waitForZero(10), false);
  gate.leave();
});
