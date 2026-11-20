import { setTimeout as delay } from "node:timers/promises";

import { ApiError } from "./api.js";
import { percentile } from "./metrics.js";
import { parseSse, parseSseData } from "./sse.js";

export interface BaselineSample {
  firstTokenMs: number;
  at: string;
}

export interface BaselineOptions {
  apiKey: string;
  baseUrl: string;
  modelId: string;
  fetchImpl?: typeof fetch;
  clock?: { now(): number };
  signal?: AbortSignal;
}

export interface BaselineSummary {
  count: number;
  medianMs: number | null;
  p95Ms: number | null;
}

/** OpenAI-compatible chat completions path for a provider base URL. */
export function chatCompletionsUrl(baseUrl: string): string {
  return `${baseUrl.replace(/\/$/, "")}/chat/completions`;
}

interface ChatChunk {
  choices?: { delta?: { content?: string | null } }[];
}

/**
 * Direct streaming call to the model provider, measuring time to the first
 * content token. The M1 latency target is "first token P95 excluding the
 * model's own TTFB", so this baseline is sampled in parallel with the run and
 * subtracted from the gateway-observed numbers.
 */
export async function sampleFirstToken(options: BaselineOptions): Promise<BaselineSample> {
  const fetchImpl = options.fetchImpl ?? fetch;
  const clock = options.clock ?? { now: () => performance.now() };
  const started = clock.now();
  const response = await fetchImpl(chatCompletionsUrl(options.baseUrl), {
    method: "POST",
    headers: {
      authorization: `Bearer ${options.apiKey}`,
      "content-type": "application/json",
      accept: "text/event-stream",
    },
    body: JSON.stringify({
      model: options.modelId,
      messages: [{ role: "user", content: "Reply with OK." }],
      max_tokens: 4,
      stream: true,
    }),
    signal: options.signal,
  });
  if (!response.ok) {
    throw new ApiError(
      response.status,
      `UPSTREAM_${response.status}`,
      `baseline request failed with status ${response.status}`,
      response.status === 429 || response.status >= 500,
    );
  }
  if (!response.body) {
    throw new ApiError(response.status, "EMPTY_STREAM", "baseline response has no body", false);
  }
  for await (const frame of parseSse(response.body as unknown as AsyncIterable<Uint8Array>)) {
    if (frame.data === "[DONE]") break;
    const chunk = parseSseData<ChatChunk>(frame);
    const content = chunk.choices?.[0]?.delta?.content;
    if (content) {
      return { firstTokenMs: Math.round(clock.now() - started), at: new Date().toISOString() };
    }
  }
  throw new Error("baseline stream ended before the first content token");
}

export function baselineOffsets(samples: BaselineSample[]): BaselineSummary {
  const values = samples.map((sample) => sample.firstTokenMs);
  return {
    count: values.length,
    medianMs: percentile(values, 50),
    p95Ms: percentile(values, 95),
  };
}

/** Net latency after removing the provider baseline; never negative. */
export function netOfBaseline(value: number | null, offset: number | null): number | null {
  if (value === null) return null;
  if (offset === null) return value;
  return Math.max(0, value - offset);
}

export interface BaselineSampler {
  samples: BaselineSample[];
  errors: string[];
  stop(): Promise<void>;
}

/** Samples the provider TTFB every `intervalMs` until stopped. */
export function startBaselineSampler(
  options: BaselineOptions & { intervalMs?: number },
): BaselineSampler {
  const intervalMs = options.intervalMs ?? 5_000;
  const samples: BaselineSample[] = [];
  const errors: string[] = [];
  const controller = new AbortController();
  let stopped = false;

  const loop = (async () => {
    while (!stopped) {
      try {
        samples.push(await sampleFirstToken({ ...options, signal: controller.signal }));
      } catch (error) {
        if (!stopped) {
          errors.push(error instanceof Error ? error.message : String(error));
        }
      }
      if (stopped) break;
      try {
        await delay(intervalMs, undefined, { signal: controller.signal });
      } catch {
        break;
      }
    }
  })();

  return {
    samples,
    errors,
    async stop() {
      stopped = true;
      controller.abort();
      await loop;
    },
  };
}
