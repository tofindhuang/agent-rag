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

test("passes structured kb_search details through tool_result", () => {
  const translator = new RunTranslator({
    runId: "run-1",
    turnId: "turn-1",
    sessionId: "session-1",
    contractVersion: "1",
  });
  translator.translate({ type: "agent_start" } as unknown as AgentSessionEvent);

  const details = {
    count: 1,
    passages: [
      {
        passageId: "p-1",
        documentId: "doc-1",
        seq: 2,
        page: 3,
        title: "handbook.pdf",
        content: "passage text",
        score: 0.82,
        reranked: true,
      },
    ],
  };
  const events = translator.translate({
    type: "tool_execution_end",
    toolCallId: "call-1",
    toolName: "kb_search",
    result: { content: [{ type: "text", text: "passage text" }], details },
    isError: false,
    durationMs: 1,
  } as unknown as AgentSessionEvent);

  assert.equal(events.length, 1);
  const event = events[0];
  assert.equal(event?.type, "tool_result");
  if (event?.type === "tool_result") {
    assert.deepEqual(event.data.details, details);
  }
});

test("omits tool details that exceed the frame budget", () => {
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
    toolName: "kb_search",
    result: {
      content: [{ type: "text", text: "ok" }],
      details: { passages: [{ content: "a".repeat(9_000) }] },
    },
    isError: false,
    durationMs: 1,
  } as unknown as AgentSessionEvent);

  const event = events[0];
  assert.equal(event?.type, "tool_result");
  if (event?.type === "tool_result") {
    assert.equal(event.data.details, undefined);
  }
});

test("emits context usage after start, assistant messages and compaction", () => {
  let usage: {
    tokens: number | null;
    contextWindow: number;
    percent: number | null;
  } = { tokens: 1_000, contextWindow: 10_000, percent: 10 };
  const translator = new RunTranslator({
    runId: "run-1",
    turnId: "turn-1",
    sessionId: "session-1",
    contractVersion: "1",
    contextUsage: () => usage,
    compactionThresholdPercent: 80,
    compactionEnabled: true,
  });

  const started = translator.start();
  assert.deepEqual(
    started.map((event) => event.type),
    ["run_started", "context_usage"],
  );
  if (started[1]?.type === "context_usage") {
    assert.deepEqual(started[1].data, {
      tokens: 1_000,
      contextWindow: 10_000,
      percent: 10,
      compactionThresholdPercent: 80,
      compactionEnabled: true,
    });
  }

  usage = { tokens: 2_000, contextWindow: 10_000, percent: 20 };
  const message = translator.translate({
    type: "message_end",
    message: {
      role: "assistant",
      content: [{ type: "text", text: "ok" }],
      usage: {
        input: 10,
        output: 5,
        totalTokens: 15,
        cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
      },
      stopReason: "stop",
    },
  } as unknown as AgentSessionEvent);
  assert.deepEqual(
    message.map((event) => event.type),
    ["message_end", "context_usage"],
  );

  usage = { tokens: null, contextWindow: 10_000, percent: null };
  const compaction = translator.translate({
    type: "compaction_end",
    reason: "threshold",
    result: { tokensBefore: 9_000, estimatedTokensAfter: 2_000 },
  } as unknown as AgentSessionEvent);
  assert.deepEqual(
    compaction.map((event) => event.type),
    ["compaction", "context_usage"],
  );
  if (compaction[1]?.type === "context_usage") {
    assert.equal(compaction[1].data.tokens, null);
    assert.equal(compaction[1].data.percent, null);
  }
});
