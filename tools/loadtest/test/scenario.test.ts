import { test } from "node:test";
import assert from "node:assert/strict";

import { ApiError } from "../src/api.js";
import {
  classifyFailure,
  isRetryableClass,
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
  abortOnce?: boolean;
  settledError?: { code: string; message: string; retryable: boolean };
}

interface FakeClient extends ScenarioClient {
  turns: number;
  kbName: string | null;
  uploads: string[];
  sessionKbId: string | null;
}

function fakeClient(options: FakeClientOptions = {}): FakeClient {
  const tickMs = options.tickMs ?? 100;
  const advance = options.advance ?? (() => {});
  const client: FakeClient = {
    turns: 0,
    kbName: null,
    uploads: [],
    sessionKbId: null,
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
    async createSession(_token: string, input: { kbId?: string }) {
      client.sessionKbId = input.kbId ?? null;
      return { sessionId: "session" };
    },
    async createKnowledgeBase(_token: string, name: string) {
      client.kbName = name;
      return { id: "kb-1", name, isDefault: true, documentCount: 0 };
    },
    async uploadDocument(_token: string, _kbId: string, filename: string) {
      client.uploads.push(filename);
      return { id: `doc-${client.uploads.length}`, status: "queued" };
    },
    async getDocument(_token: string, id: string) {
      return { id, status: "ready" };
    },
    async waitForDocumentReady(_token: string, id: string) {
      return { id, status: "ready" };
    },
    async *sendMessage(_token: string, _sessionId: string, input: { text: string }) {
      client.turns += 1;
      advance(tickMs);
      if (options.failOnText && input.text.includes(options.failOnText)) {
        throw new ApiError(500, "INTERNAL", "boom", false);
      }
      if (options.abortOnce) {
        options.abortOnce = false;
        yield {
          event: "run_settled",
          data: JSON.stringify({ status: "aborted" }),
        };
        return;
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
  assert.equal(
    classifyFailure(new ApiError(502, "UPSTREAM_UNAVAILABLE", "gateway unreachable", true)),
    "upstream_unavailable",
  );
  assert.equal(
    classifyFailure({
      settled: {
        status: "error",
        error: { code: "STREAM_INTERRUPTED", message: "连接中断，未收到终态", retryable: false },
      },
    }),
    "upstream_unavailable",
  );
  assert.equal(isRetryableClass("upstream_unavailable"), true);
  assert.equal(isRetryableClass("hard_error"), false);
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

test("kb_search users seed their own knowledge base before the session starts", async () => {
  const client = fakeClient();
  const records: TurnRecord[] = [];
  const summary = await runLoadTest(
    baseOptions(client, {
      users: 1,
      composition: { chat: 0, run_code: 0, kb_search: 1 },
      kbSeed: {
        documents: [
          { filename: "architecture.md", content: "# Agent Core architecture" },
          { filename: "sessions.md", content: "# Session storage" },
        ],
      },
      onRecord: (record) => {
        records.push(record);
      },
    }),
  );

  assert.equal(summary.turns, 1);
  assert.equal(client.kbName, "loadtest-test-run-0");
  assert.deepEqual(client.uploads, ["architecture.md", "sessions.md"]);
  assert.equal(client.sessionKbId, "kb-1");
  assert.equal(records[0].errorClass, "none");
});

test("retries a disconnected turn with the same turnId", async () => {
  const client = fakeClient();
  const original = client.sendMessage.bind(client);
  let failures = 1;
  const turnIds: string[] = [];
  client.sendMessage = async function* (token: string, sessionId: string, input: { text: string; turnId: string }) {
    turnIds.push(input.turnId);
    if (failures > 0) {
      failures -= 1;
      throw new TypeError("fetch failed");
    }
    yield* original(token, sessionId, input);
  };

  const records: TurnRecord[] = [];
  const summary = await runLoadTest(
    baseOptions(client, {
      users: 1,
      composition: { chat: 1, run_code: 0, kb_search: 0 },
      retryOnDisconnect: true,
      retryDelayMs: 0,
      sleep: async () => {},
      onRecord: (record) => {
        records.push(record);
      },
    }),
  );

  assert.equal(records.length, 1);
  assert.equal(records[0].status, "completed");
  assert.equal(records[0].attempts, 2);
  assert.equal(records[0].retryReason, "disconnected");
  assert.equal(new Set(turnIds).size, 1, "retries must reuse the turnId");
  assert.equal(summary.statusCounts.completed, 1);
});

test("gives up after the disconnect retry budget", async () => {
  const client = fakeClient();
  client.sendMessage = async function* () {
    throw new TypeError("fetch failed");
  };

  const records: TurnRecord[] = [];
  const summary = await runLoadTest(
    baseOptions(client, {
      users: 1,
      composition: { chat: 1, run_code: 0, kb_search: 0 },
      retryOnDisconnect: true,
      maxDisconnectRetries: 2,
      retryDelayMs: 0,
      sleep: async () => {},
      onRecord: (record) => {
        records.push(record);
      },
    }),
  );

  assert.equal(records.length, 1);
  assert.equal(records[0].attempts, 3);
  assert.equal(records[0].status, "disconnected");
  assert.equal(summary.errors.disconnected, 1);
});

test("retries a turn when the gateway hop is temporarily unavailable", async () => {
  const client = fakeClient();
  const original = client.sendMessage.bind(client);
  let failures = 2;
  client.sendMessage = async function* (token: string, sessionId: string, input: { text: string; turnId: string }) {
    if (failures > 0) {
      failures -= 1;
      throw new ApiError(502, "UPSTREAM_UNAVAILABLE", "gateway unreachable", true);
    }
    yield* original(token, sessionId, input);
  };

  const records: TurnRecord[] = [];
  await runLoadTest(
    baseOptions(client, {
      users: 1,
      composition: { chat: 1, run_code: 0, kb_search: 0 },
      retryOnDisconnect: true,
      maxDisconnectRetries: 3,
      retryDelayMs: 0,
      sleep: async () => {},
      onRecord: (record) => {
        records.push(record);
      },
    }),
  );

  assert.equal(records.length, 1);
  assert.equal(records[0].attempts, 3);
  assert.equal(records[0].status, "completed");
  assert.equal(records[0].retryReason, "upstream_unavailable");
});

test("retries a turn aborted by an infrastructure restart", async () => {
  const client = fakeClient({ abortOnce: true });
  const records: TurnRecord[] = [];
  const summary = await runLoadTest(
    baseOptions(client, {
      users: 1,
      composition: { chat: 1, run_code: 0, kb_search: 0 },
      retryOnDisconnect: true,
      retryDelayMs: 0,
      sleep: async () => {},
      onRecord: (record) => {
        records.push(record);
      },
    }),
  );

  assert.equal(records.length, 1);
  assert.equal(records[0].attempts, 2);
  assert.equal(records[0].status, "completed");
  assert.equal(summary.retries, 1);
  assert.equal(records[0].retryReason, "aborted");
});
