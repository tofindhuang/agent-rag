import { mkdir } from "node:fs/promises";
import { dirname } from "node:path";

import { LoadTestClient } from "./api.js";
import { configFromEnv } from "./config.js";
import { appendJsonl } from "./metrics.js";
import { parseSseData, type SseFrame } from "./sse.js";

interface RunSettled {
  status: "completed" | "aborted" | "error";
  error?: { code: string; message: string; retryable: boolean };
}

interface ToolCall {
  toolCallId: string;
  name: string;
}

interface ToolResult {
  toolCallId: string;
  name: string;
  isError: boolean;
}

/**
 * Single-user tracer bullet: one virtual user completes one real turn through
 * the public Java API. The turn's raw timings land in JSONL for the later
 * concurrency and report tickets to reuse.
 */
async function main(): Promise<void> {
  const config = configFromEnv();
  const client = new LoadTestClient({ baseUrl: config.baseUrl });
  const runId = `smoke-${Date.now()}-${Math.floor(Math.random() * 1_000_000)}`;
  const email = `${runId}@example.com`;
  const password = "loadtest-password-1";

  const registered = await client.register(email, password);
  const { accessToken } = await client.login(email, password);
  const provider = await client.createProvider(accessToken, {
    name: config.model.providerName,
    api: config.model.api,
    baseUrl: config.model.baseUrl,
    apiKey: config.model.apiKey,
    models: [
      { modelId: config.model.modelId, displayName: config.model.displayName },
    ],
  });
  const catalog = await client.listModels(accessToken);
  const model = catalog.providers
    .flatMap((entry) => entry.models)
    .find((entry) => entry.modelId === config.model.modelId);
  if (!model) {
    throw new Error(`provider ${provider.id} did not expose model ${config.model.modelId}`);
  }

  const { sessionId } = await client.createSession(accessToken, {
    modelId: model.id,
    noKnowledgeBase: true,
  });

  const turnId = `${runId}-turn-1`;
  const prompt = "用一句话介绍你自己。";
  const turnStarted = performance.now();
  let firstTokenMs: number | null = null;
  let text = "";
  let settled: RunSettled | null = null;
  const toolCalls: ToolCall[] = [];
  const toolResults: ToolResult[] = [];

  for await (const frame of client.sendMessage(accessToken, sessionId, { text: prompt, turnId })) {
    trackFrame(frame);
  }

  // Control-flow analysis cannot see assignments made inside trackFrame, so
  // read the settled value through an explicit local after the stream closes.
  const finalSettled: RunSettled | null = settled;
  if (!finalSettled) throw new Error("stream ended without run_settled");

  const record = {
    scenario: "smoke",
    runId,
    userId: registered.userId,
    sessionId,
    turnId,
    model: config.model.modelId,
    prompt,
    firstTokenMs,
    turnMs: Math.round(performance.now() - turnStarted),
    status: finalSettled.status,
    error: finalSettled.error ?? null,
    textChars: text.length,
    toolCalls: toolCalls.map((call) => call.name),
    toolResults: toolResults.map((result) => ({
      name: result.name,
      isError: result.isError,
    })),
    at: new Date().toISOString(),
  };
  await mkdir(dirname(config.metricsFile), { recursive: true });
  await appendJsonl(config.metricsFile, record);

  console.log(JSON.stringify({ ...record, email, apiKeyHint: "env-injected" }, null, 2));
  if (finalSettled.status !== "completed") {
    process.exitCode = 1;
  }

  function trackFrame(frame: SseFrame): void {
    switch (frame.event) {
      case "text_delta": {
        if (firstTokenMs === null) {
          firstTokenMs = Math.round(performance.now() - turnStarted);
        }
        text += parseSseData<{ delta: string }>(frame).delta;
        break;
      }
      case "tool_call": {
        const call = parseSseData<ToolCall>(frame);
        toolCalls.push({ toolCallId: call.toolCallId, name: call.name });
        break;
      }
      case "tool_result": {
        const result = parseSseData<ToolResult>(frame);
        toolResults.push({
          toolCallId: result.toolCallId,
          name: result.name,
          isError: result.isError,
        });
        break;
      }
      case "run_settled": {
        settled = parseSseData<RunSettled>(frame);
        break;
      }
      default:
        break;
    }
  }
}

main().catch((error: unknown) => {
  console.error(error instanceof Error ? error.message : error);
  process.exitCode = 1;
});
