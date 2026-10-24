import { test } from "node:test";
import assert from "node:assert/strict";

import { ApiError } from "../src/api.js";
import {
  classifyFailure,
  runLoadTest,
  type LoadTestOptions,
  type ScenarioClient,
  type TurnRecord,
} from "../src/scenario.js";
import type { SseFrame } from "../src/sse.js";

function completedFrames(toolCalls: string[] = []): SseFrame[] {
  const frames: SseFrame[] = [
    {
      event: "run_started",
      data: JSON.stringify({ runId: "run", turnId: "turn", sessionId: "session" }),
    },
    { event: "text_delta", data: JSON.stringify({ delta: "hello" }) },
  ];
  for (const name of toolCalls) {
    frames.push({
      event: "tool_call",
      data: JSON.stringify({ toolCallId: `call-${name}`, name, args: {} }),
    });
    frames.push({
      event: "tool_result",
      data: JSON.stringify({ toolCallId: `call-${name}`, name, isError: false, content: "ok" }),
    });
  }
  frames.push({ event: "run_settled", data: JSON.stringify({ status: "completed" }) });
  return frames;
}

interface FakeClientOptions {
  tickMs?: number;
  advance?: (ms: number) => void;
  failOnText?: string;
  settledError?: { code: string; message: string; retryable: boolean };
}

function fakeClient(options: FakeClientOptions = {}): ScenarioClient & { turns: number } {
  const tickMs = options.tickMs ?? 100;
  const advance = options.advance ?? (() => {});
  const client = {
    turns: 0,
    async register(email: string) {
      return { userId: client.turns + 1, email };
    },
    async login() {
      return { accessToken: "token", tokenType: "Bearer", expiresIn: 900 };
    },
    async createProvider() {
      return { id: 1 };
    },
    async listModels() {
      return {
        providers: [
          {
            id: 1,
            name: "fake",
            api: "openai-completions",
            baseUrl: "https://example.invalid",
            apiKeyHint: "…",
            models: [{ id: 10, modelId: "fake-model", displayName: "Fake", contextWindow: 8192 }],
          },
        ],
        defaultModelId: null,
        systemDefault: null,
      };
    },
    async createSession() {
      return { sessionId: "session" };
    },
    async *sendMessage(_token: string, _sessionId: string, input: { text: string }) {
      client.turns += 1;
      advance(tickMs);
      if (options.failOnText && input.text.includes(options.failOnText)) {
        throw new ApiError(500, "INTERNAL", "boom", false);
      }
      const frames = options.settledError
        ? [
            ...completedFrames().slice(0, 2),
            {
              event: "run_settled",
              data: JSON.stringify({ status: "error", error: options.settledError }),
            },
          ]
        : completedFrames();
      for (const frame of frames) {
        yield frame;
        if (frame.event !== "run_settled") advance(tickMs);
      }
    },
  };
  return client;
}

function baseOptions(
  client: ScenarioClient,
  overrides: Partial<LoadTestOptions> = {},
): LoadTestOptions {
  return {
    client,
    runId: "test-run",
    users: 2,
    rounds: 1,
    composition: { chat: 2, run_code: 0, kb_search: 0 },
    thinkTimeMinMs: 0,
    thinkTimeMaxMs: 0,
    provider: {
      name: "fake",
      api: "openai-completions",
      baseUrl: "https://example.invalid",
      apiKey: "secret",
      modelId: "fake-model",
      displayName: "Fake",
    },
    sleep: async () => {},
    ...overrides,
  };
}

test("runs every configured user for the requested rounds and records each turn", async () => {
  const client = fakeClient();
  const records: TurnRecord[] = [];
  const summary = await runLoadTest(
    baseOptions(client, {
      rounds: 2,
      onRecord: (record) => {
        records.push(record);
      },
    }),
  );

  assert.equal(records.length, 4);
  assert.equal(summary.turns, 4);
  assert.equal(summary.statusCounts.completed, 4);
  assert.deepEqual(
    records.map((record) => record.scenario),
    ["chat", "chat", "chat", "chat"],
  );
  assert.equal(client.turns, 4);
});

test("splits the composition into the requested workloads", async () => {
  const client = fakeClient();
  const records: TurnRecord[] = [];
  await runLoadTest(
    baseOptions(client, {
      users: 3,
      composition: { chat: 1, run_code: 1, kb_search: 1 },
      onRecord: (record) => {
        records.push(record);
      },
    }),
  );

  assert.deepEqual(
    records.map((record) => record.scenario).sort(),
    ["chat", "kb_search", "run_code"],
  );
});

test("one failing user does not stop the others", async () => {
  const client = fakeClient({ failOnText: "run_code" });
  const records: TurnRecord[] = [];
  const summary = await runLoadTest(
    baseOptions(client, {
      users: 2,
      composition: { chat: 1, run_code: 1, kb_search: 0 },
      onRecord: (record) => {
        records.push(record);
      },
    }),
  );

  assert.equal(summary.turns, 2);
  assert.equal(summary.statusCounts.completed, 1);
  assert.equal(summary.errors.hard_error, 1);
  assert.equal(records.length, 2);
});

test("classifies local rate limits, upstream 429s, hard errors and disconnects", () => {
  assert.equal(classifyFailure(new ApiError(429, "RATE_LIMITED", "slow down", true)), "rate_limited");
  assert.equal(classifyFailure(new ApiError(500, "INTERNAL", "boom", false)), "hard_error");
  assert.equal(classifyFailure(new TypeError("fetch failed")), "disconnected");
  assert.equal(
    classifyFailure({
      settled: {
        status: "error",
        error: { code: "MODEL_ERROR", message: "429 Too Many Requests", retryable: true },
      },
    }),
    "upstream_429",
  );
  assert.equal(
    classifyFailure({
      settled: {
        status: "error",
        error: { code: "MODEL_ERROR", message: "model call failed", retryable: true },
      },
    }),
    "hard_error",
  );
});

test("summary aggregates first-token and turn durations from the shared clock", async () => {
  let now = 0;
  const client = fakeClient({ tickMs: 100, advance: (ms) => (now += ms) });
  const summary = await runLoadTest(
    baseOptions(client, {
      users: 1,
      composition: { chat: 1, run_code: 0, kb_search: 0 },
      clock: { now: () => now },
      sleep: async (ms) => {
        now += ms;
      },
    }),
  );

  // run_started at +100, first text_delta at +200, run_settled at +300.
  assert.equal(summary.firstTokenMs.count, 1);
  assert.equal(summary.firstTokenMs.p50, 200);
  assert.equal(summary.turnMs.count, 1);
  assert.equal(summary.turnMs.p50, 300);
});
