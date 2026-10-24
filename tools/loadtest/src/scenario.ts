import { setTimeout as delay } from "node:timers/promises";

import { ApiError, type CreateProviderInput, type ModelCatalog } from "./api.js";
import { summarize, type Summary } from "./metrics.js";
import { parseSseData, type SseFrame } from "./sse.js";

export type Workload = "chat" | "run_code" | "kb_search";
export type ErrorClass =
  | "none"
  | "hard_error"
  | "rate_limited"
  | "upstream_429"
  | "upstream_unavailable"
  | "disconnected";
export type TurnStatus = "completed" | "aborted" | "error" | "disconnected";

/** The subset of the HTTP client a scenario needs; tests inject a fake. */
export interface ScenarioClient {
  register(email: string, password: string): Promise<{ userId: number; email: string }>;
  login(
    email: string,
    password: string,
  ): Promise<{ accessToken: string; tokenType: string; expiresIn: number }>;
  createProvider(token: string, input: CreateProviderInput): Promise<{ id: number }>;
  listModels(token: string): Promise<ModelCatalog>;
  createSession(
    token: string,
    input: { modelId?: number; useSystemDefault?: boolean; kbId?: string; noKnowledgeBase?: boolean },
  ): Promise<{ sessionId: string }>;
  sendMessage(
    token: string,
    sessionId: string,
    input: { text: string; turnId: string },
  ): AsyncGenerator<SseFrame>;
  createKnowledgeBase(
    token: string,
    name: string,
  ): Promise<{ id: string; name: string; isDefault: boolean; documentCount: number }>;
  uploadDocument(
    token: string,
    kbId: string,
    filename: string,
    content: string,
  ): Promise<{ id: string; status: string }>;
  getDocument(token: string, documentId: string): Promise<{ id: string; status: string }>;
  waitForDocumentReady(
    token: string,
    documentId: string,
    options?: { timeoutMs?: number; intervalMs?: number },
  ): Promise<{ id: string; status: string }>;
}

export interface ToolCallRecord {
  toolCallId: string;
  name: string;
  latencyMs: number | null;
  isError: boolean | null;
}

export interface TurnRecord {
  scenario: Workload;
  userId: number | null;
  email: string;
  sessionId: string | null;
  turnId: string;
  round: number;
  firstTokenMs: number | null;
  turnMs: number;
  status: TurnStatus;
  errorClass: ErrorClass;
  attempts: number;
  /** First interruption that triggered a retry, for run-report attribution. */
  retryReason: ErrorClass | "aborted" | null;
  error: { code: string; message: string; retryable: boolean } | null;
  textChars: number;
  toolCalls: ToolCallRecord[];
  at: string;
}

export interface LoadTestProvider {
  name: string;
  api: "openai-completions" | "anthropic-messages";
  baseUrl: string;
  apiKey: string;
  modelId: string;
  displayName: string;
}

export interface LoadTestOptions {
  client: ScenarioClient;
  runId?: string;
  users: number;
  rounds: number;
  composition: { chat: number; run_code: number; kb_search: number };
  thinkTimeMinMs: number;
  thinkTimeMaxMs: number;
  provider: LoadTestProvider;
  /** Per-workload knowledge base; kb_search users need a seeded KB. */
  kbId?: string;
  /** When set, each kb_search user seeds its own KB before the first turn. */
  kbSeed?: { documents: { filename: string; content: string }[] };
  /** Retry turns whose stream broke (e.g. gateway restart) with the same turnId. */
  retryOnDisconnect?: boolean;
  maxDisconnectRetries?: number;
  retryDelayMs?: number;
  prompts?: Partial<Record<Workload, (round: number) => string>>;
  random?: () => number;
  clock?: { now(): number };
  sleep?: (ms: number) => Promise<void>;
  onRecord?: (record: TurnRecord) => void | Promise<void>;
  onProgress?: (message: string) => void;
}

export interface WorkloadSummary {
  turns: number;
  firstTokenMs: Summary;
  turnMs: Summary;
  toolLatencyMs: Summary;
}

export interface RunSummary {
  users: number;
  rounds: number;
  turns: number;
  durationMs: number;
  statusCounts: Record<string, number>;
  errors: Record<ErrorClass, number>;
  retries: number;
  retryReasons: Record<string, number>;
  firstTokenMs: Summary;
  turnMs: Summary;
  toolLatencyMs: Summary;
  byWorkload: Record<Workload, WorkloadSummary>;
}

interface SettledEvent {
  status: "completed" | "aborted" | "error";
  error?: { code: string; message: string; retryable: boolean };
}

class StreamEndedError extends Error {
  constructor() {
    super("stream ended without run_settled");
    this.name = "StreamEndedError";
  }
}

function isSettledFailure(
  value: unknown,
): value is { settled: { status: string; error?: { code: string; message: string; retryable: boolean } } } {
  if (typeof value !== "object" || value === null) return false;
  const settled = (value as { settled?: unknown }).settled;
  return typeof settled === "object" && settled !== null && "status" in settled;
}

export function classifyFailure(error: unknown): ErrorClass {
  if (error instanceof ApiError) {
    if (error.status === 429) {
      return error.code === "RATE_LIMITED" ? "rate_limited" : "upstream_429";
    }
    // 5xx with retryable=true means the gateway hop was unavailable (for
    // example during a rolling gateway restart); the turn can be resumed.
    if (error.retryable) return "upstream_unavailable";
    return "hard_error";
  }
  if (isSettledFailure(error)) {
    const message = error.settled.error?.message ?? "";
    const code = error.settled.error?.code ?? "";
    if (/429|rate.?limit|too many requests/i.test(message)) return "upstream_429";
    if (code === "STREAM_INTERRUPTED" || code === "UPSTREAM_UNAVAILABLE") {
      return "upstream_unavailable";
    }
    return "hard_error";
  }
  // Network failures, aborted streams and missing run_settled.
  return "disconnected";
}

export function isRetryableClass(errorClass: ErrorClass): boolean {
  return (
    errorClass === "disconnected" ||
    errorClass === "upstream_unavailable" ||
    errorClass === "upstream_429"
  );
}

const DEFAULT_PROMPTS: Record<Workload, (round: number) => string> = {
  chat: (round) => `用两句话介绍你自己。这是第 ${round} 轮。`,
  run_code: (round) =>
    `请调用 run_code 工具执行 Python 代码 print(sum(range(10)))，然后告诉我输出结果。这是第 ${round} 轮。`,
  kb_search: (round) =>
    `请调用 kb_search 工具，在知识库中查询「Agent Core 的会话存储方式」，并总结命中要点。这是第 ${round} 轮。`,
};

interface TurnContext {
  client: ScenarioClient;
  clock: { now(): number };
  token: string;
  sessionId: string;
  email: string;
  userId: number | null;
  scenario: Workload;
  prompt: string;
  turnId: string;
  round: number;
  attempt: number;
  retryReason: ErrorClass | "aborted" | null;
}

async function runTurn(context: TurnContext): Promise<TurnRecord> {
  const started = context.clock.now();
  let firstTokenAt: number | null = null;
  let settled: SettledEvent | null = null;
  let textChars = 0;
  const toolCalls: ToolCallRecord[] = [];
  const toolStart = new Map<string, number>();

  try {
    for await (const frame of context.client.sendMessage(context.token, context.sessionId, {
      text: context.prompt,
      turnId: context.turnId,
    })) {
      switch (frame.event) {
        case "text_delta":
        case "thinking_delta": {
          if (firstTokenAt === null) firstTokenAt = context.clock.now();
          if (frame.event === "text_delta") {
            textChars += parseSseData<{ delta: string }>(frame).delta.length;
          }
          break;
        }
        case "tool_call": {
          const call = parseSseData<{ toolCallId: string; name: string }>(frame);
          toolStart.set(call.toolCallId, context.clock.now());
          toolCalls.push({
            toolCallId: call.toolCallId,
            name: call.name,
            latencyMs: null,
            isError: null,
          });
          break;
        }
        case "tool_result": {
          const result = parseSseData<{ toolCallId: string; name: string; isError: boolean }>(
            frame,
          );
          const at = context.clock.now();
          const startedAt = toolStart.get(result.toolCallId);
          const record = toolCalls.find((call) => call.toolCallId === result.toolCallId);
          if (record) {
            record.latencyMs = startedAt === undefined ? null : at - startedAt;
            record.isError = result.isError;
          }
          break;
        }
        case "run_settled": {
          settled = parseSseData<SettledEvent>(frame);
          break;
        }
        default:
          break;
      }
    }

    if (!settled) throw new StreamEndedError();

    const failure = settled.status === "error" ? classifyFailure({ settled }) : "none";
    return {
      scenario: context.scenario,
      userId: context.userId,
      email: context.email,
      sessionId: context.sessionId,
      turnId: context.turnId,
      round: context.round,
      attempts: context.attempt,
      retryReason: context.retryReason,
      firstTokenMs: firstTokenAt === null ? null : Math.round(firstTokenAt - started),
      turnMs: Math.round(context.clock.now() - started),
      status:
        settled.status === "completed"
          ? "completed"
          : settled.status === "aborted"
            ? "aborted"
            : "error",
      errorClass: failure,
      error: settled.error ?? null,
      textChars,
      toolCalls,
      at: new Date().toISOString(),
    };
  } catch (error) {
    const errorClass = classifyFailure(error);
    return {
      scenario: context.scenario,
      userId: context.userId,
      email: context.email,
      sessionId: context.sessionId,
      turnId: context.turnId,
      round: context.round,
      attempts: context.attempt,
      retryReason: context.retryReason,
      firstTokenMs: firstTokenAt === null ? null : Math.round(firstTokenAt - started),
      turnMs: Math.round(context.clock.now() - started),
      status: errorClass === "disconnected" ? "disconnected" : "error",
      errorClass,
      error:
        error instanceof ApiError
          ? { code: error.code, message: error.message, retryable: error.retryable }
          : { code: "TRANSPORT", message: error instanceof Error ? error.message : String(error), retryable: true },
      textChars,
      toolCalls,
      at: new Date().toISOString(),
    };
  }
}

function workloadList(composition: LoadTestOptions["composition"]): Workload[] {
  return [
    ...Array<Workload>(composition.chat).fill("chat"),
    ...Array<Workload>(composition.run_code).fill("run_code"),
    ...Array<Workload>(composition.kb_search).fill("kb_search"),
  ];
}

async function runUser(
  options: LoadTestOptions,
  index: number,
  workload: Workload,
  runId: string,
  password: string,
): Promise<TurnRecord[]> {
  const client = options.client;
  const clock = options.clock ?? { now: () => performance.now() };
  const sleep = options.sleep ?? ((ms: number) => delay(ms));
  const random = options.random ?? Math.random;
  const email = `loadtest-${runId}-${index}@example.com`;
  const records: TurnRecord[] = [];
  const prompts = { ...DEFAULT_PROMPTS, ...options.prompts };

  let userId: number | null = null;
  let sessionId: string | null = null;
  try {
    const registered = await client.register(email, password);
    userId = registered.userId;
    const { accessToken } = await client.login(email, password);
    await client.createProvider(accessToken, {
      name: options.provider.name,
      api: options.provider.api,
      baseUrl: options.provider.baseUrl,
      apiKey: options.provider.apiKey,
      models: [
        { modelId: options.provider.modelId, displayName: options.provider.displayName },
      ],
    });
    const catalog = await client.listModels(accessToken);
    const model = catalog.providers
      .flatMap((provider) => provider.models)
      .find((entry) => entry.modelId === options.provider.modelId);
    if (!model) throw new Error(`model ${options.provider.modelId} missing from catalog`);
    let kbId = workload === "kb_search" ? options.kbId : undefined;
    if (workload === "kb_search" && options.kbSeed) {
      const kb = await client.createKnowledgeBase(accessToken, `loadtest-${runId}-${index}`);
      for (const document of options.kbSeed.documents) {
        const created = await client.uploadDocument(
          accessToken,
          kb.id,
          document.filename,
          document.content,
        );
        await client.waitForDocumentReady(accessToken, created.id);
      }
      kbId = kb.id;
    }
    const session = await client.createSession(accessToken, {
      modelId: model.id,
      ...(kbId ? { kbId } : { noKnowledgeBase: workload !== "kb_search" }),
    });
    sessionId = session.sessionId;

    const maxAttempts = options.retryOnDisconnect
      ? 1 + Math.max(0, options.maxDisconnectRetries ?? 2)
      : 1;
    for (let round = 1; round <= options.rounds; round += 1) {
      const turnId = `${runId}-u${index}-r${round}`;
      let record: TurnRecord;
      let retryReason: ErrorClass | "aborted" | null = null;
      for (let attempt = 1; ; attempt += 1) {
        record = await runTurn({
          client,
          clock,
          token: accessToken,
          sessionId,
          email,
          userId,
          scenario: workload,
          prompt: prompts[workload](round),
          turnId,
          round,
          attempt,
          retryReason,
        });
        const retryable =
          isRetryableClass(record.errorClass) || record.status === "aborted";
        if (retryable && attempt < maxAttempts) {
          retryReason ??= record.status === "aborted" ? "aborted" : record.errorClass;
          await sleep(options.retryDelayMs ?? 1_500);
          continue;
        }
        break;
      }
      records.push(record!);
      await options.onRecord?.(record);
      if (round < options.rounds) {
        const min = Math.min(options.thinkTimeMinMs, options.thinkTimeMaxMs);
        const max = Math.max(options.thinkTimeMinMs, options.thinkTimeMaxMs);
        await sleep(min + Math.floor(random() * (max - min + 1)));
      }
    }
  } catch (error) {
    const errorClass = classifyFailure(error);
    const record: TurnRecord = {
      scenario: workload,
      userId,
      email,
      sessionId,
      turnId: `${runId}-u${index}-setup`,
      round: 0,
      attempts: 1,
      retryReason: null,
      firstTokenMs: null,
      turnMs: 0,
      status: errorClass === "disconnected" ? "disconnected" : "error",
      errorClass,
      error: {
        code: error instanceof ApiError ? error.code : "SETUP",
        message: error instanceof Error ? error.message : String(error),
        retryable: error instanceof ApiError ? error.retryable : true,
      },
      textChars: 0,
      toolCalls: [],
      at: new Date().toISOString(),
    };
    records.push(record);
    await options.onRecord?.(record);
  }
  return records;
}

function emptyErrorCounts(): Record<ErrorClass, number> {
  return {
    none: 0,
    hard_error: 0,
    rate_limited: 0,
    upstream_429: 0,
    upstream_unavailable: 0,
    disconnected: 0,
  };
}

function aggregateWorkload(records: TurnRecord[]): WorkloadSummary {
  return {
    turns: records.length,
    firstTokenMs: summarize(
      records.flatMap((record) => (record.firstTokenMs === null ? [] : [record.firstTokenMs])),
    ),
    turnMs: summarize(records.map((record) => record.turnMs)),
    toolLatencyMs: summarize(
      records.flatMap((record) =>
        record.toolCalls.flatMap((call) => (call.latencyMs === null ? [] : [call.latencyMs])),
      ),
    ),
  };
}

export async function runLoadTest(options: LoadTestOptions): Promise<RunSummary> {
  const composition = options.composition;
  const workloadTotal = composition.chat + composition.run_code + composition.kb_search;
  if (workloadTotal !== options.users) {
    throw new Error(
      `composition sums to ${workloadTotal} users but ${options.users} were requested`,
    );
  }

  const clock = options.clock ?? { now: () => performance.now() };
  const runId = options.runId ?? `${Date.now()}`;
  const password = "loadtest-password-1";
  const startedAt = clock.now();
  const workloadOf = workloadList(composition);

  const perUser = await Promise.all(
    workloadOf.map((workload, index) =>
      runUser(options, index, workload, runId, password).then((records) => {
        options.onProgress?.(
          `user ${index + 1}/${options.users} (${workload}) finished: ${records.length} turn(s)`,
        );
        return records;
      }),
    ),
  );
  const records = perUser.flat();
  const durationMs = Math.round(clock.now() - startedAt);

  const statusCounts: Record<string, number> = {};
  const errors = emptyErrorCounts();
  for (const record of records) {
    statusCounts[record.status] = (statusCounts[record.status] ?? 0) + 1;
    errors[record.errorClass] += 1;
  }

  return {
    users: options.users,
    rounds: options.rounds,
    turns: records.length,
    durationMs,
    statusCounts,
    errors,
    retries: records.filter((record) => record.attempts > 1).length,
    retryReasons: records.reduce<Record<string, number>>((counts, record) => {
      if (record.attempts > 1 && record.retryReason) {
        counts[record.retryReason] = (counts[record.retryReason] ?? 0) + 1;
      }
      return counts;
    }, {}),
    firstTokenMs: summarize(
      records.flatMap((record) => (record.firstTokenMs === null ? [] : [record.firstTokenMs])),
    ),
    turnMs: summarize(records.map((record) => record.turnMs)),
    toolLatencyMs: summarize(
      records.flatMap((record) =>
        record.toolCalls.flatMap((call) => (call.latencyMs === null ? [] : [call.latencyMs])),
      ),
    ),
    byWorkload: {
      chat: aggregateWorkload(records.filter((record) => record.scenario === "chat")),
      run_code: aggregateWorkload(records.filter((record) => record.scenario === "run_code")),
      kb_search: aggregateWorkload(records.filter((record) => record.scenario === "kb_search")),
    },
  };
}
