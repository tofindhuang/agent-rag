import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import type { AgentSessionEvent } from "@earendil-works/pi-coding-agent";
import type { DomainEvent } from "./events.js";
import { RunTranslator } from "./translate.js";

interface GatewayFixture {
  contractVersion: string;
  kind: string;
  name: string;
  input: {
    run: { runId: string; turnId: string; sessionId: string };
    events: unknown[];
  };
  expected: {
    frames: Array<{ seq: number; event: string; data: unknown }>;
    invariants?: Record<string, unknown>;
  };
}

const fixturesDir = fileURLToPath(
  new URL("../../../contract/v1/fixtures/gateway/", import.meta.url),
);
const fixtureFiles = readdirSync(fixturesDir)
  .filter((file) => file.endsWith(".json"))
  .sort();

for (const file of fixtureFiles) {
  test(`contract fixture: ${file}`, () => {
    const fixture = JSON.parse(readFileSync(join(fixturesDir, file), "utf8")) as GatewayFixture;
    assert.equal(fixture.kind, "gateway");
    assert.equal(fixture.contractVersion, "1");

    const translator = new RunTranslator({
      ...fixture.input.run,
      contractVersion: "1",
    });
    const actual: DomainEvent[] = [];
    for (const event of fixture.input.events) {
      actual.push(...translator.translate(event as unknown as AgentSessionEvent));
    }

    const expected = fixture.expected.frames.map((frame) => ({
      type: frame.event,
      data: frame.data,
    }));
    assert.deepEqual(actual, expected);

    assert.equal(actual[0]?.type, "run_started");
    assert.equal(actual.at(-1)?.type, "run_settled");
    assert.equal(actual.filter((event) => event.type === "run_settled").length, 1);
    actual.forEach((event, index) => {
      assert.equal(index + 1, fixture.expected.frames[index]?.seq);
    });

    const first = actual[0];
    assert.equal(first?.type, "run_started");
    if (first?.type === "run_started") {
      assert.equal(first.data.contractVersion, "1");
      assert.equal(first.data.runId, fixture.input.run.runId);
      assert.equal(first.data.turnId, fixture.input.run.turnId);
    }

    const countAfterTerminal = actual.length;
    actual.push(
      ...translator.translate({
        type: "message_update",
        assistantMessageEvent: { type: "text_delta", delta: "ignored" },
      } as unknown as AgentSessionEvent),
    );
    assert.equal(actual.length, countAfterTerminal);
    assert.equal(translator.isSettled, true);
  });
}

test("truncates large tool results and reports truncation", () => {
  const translator = new RunTranslator({
    runId: "run-1",
    turnId: "turn-1",
    sessionId: "session-1",
    contractVersion: "1",
  });
  translator.translate({ type: "agent_start" } as unknown as AgentSessionEvent);

  const events = translator.translate({
    type: "tool_execution_end",
    toolCallId: "call-1",
    toolName: "run_code",
    result: { content: [{ type: "text", text: "a".repeat(70_000) }] },
    isError: false,
    durationMs: 1,
  } as unknown as AgentSessionEvent);

  assert.equal(events.length, 1);
  const event = events[0];
  assert.equal(event?.type, "tool_result");
  if (event?.type === "tool_result") {
    assert.equal(event.data.truncated, true);
    assert.ok(Buffer.byteLength(event.data.content, "utf8") <= 65_536);
  }
});
