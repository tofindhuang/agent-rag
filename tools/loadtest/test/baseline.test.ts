import { test } from "node:test";
import assert from "node:assert/strict";

import { baselineOffsets, chatCompletionsUrl, netOfBaseline, sampleFirstToken } from "../src/baseline.js";

function sseResponse(chunks: string[], advance: () => void): Response {
  const encoder = new TextEncoder();
  let index = 0;
  const stream = new ReadableStream<Uint8Array>(
    {
    pull(controller) {
      advance();
      if (index === chunks.length) {
        controller.close();
        return;
      }
      controller.enqueue(encoder.encode(chunks[index]));
      index += 1;
    },
    },
    { highWaterMark: 0 },
  );
  return new Response(stream, {
    status: 200,
    headers: { "content-type": "text/event-stream" },
    // no eager prefetch: one pull per read keeps the fake clock deterministic
  });
}

test("chatCompletionsUrl appends the completions path", () => {
  assert.equal(chatCompletionsUrl("https://api.deepseek.com"), "https://api.deepseek.com/chat/completions");
  assert.equal(chatCompletionsUrl("https://api.deepseek.com/"), "https://api.deepseek.com/chat/completions");
  assert.equal(
    chatCompletionsUrl("https://api.deepseek.com/v1"),
    "https://api.deepseek.com/v1/chat/completions",
  );
});

test("sampleFirstToken measures the first content delta", async () => {
  let now = 0;
  const advance = () => (now += 50);
  const fetchImpl = (async () =>
    sseResponse(
      [
        'data: {"choices":[{"delta":{"role":"assistant"}}]}\n\n',
        'data: {"choices":[{"delta":{"content":"O"}}]}\n\n',
        'data: {"choices":[{"delta":{"content":"K"}}]}\n\n',
        "data: [DONE]\n\n",
      ],
      advance,
    )) as typeof fetch;

  const sample = await sampleFirstToken({
    apiKey: "test-key",
    baseUrl: "https://api.deepseek.com",
    modelId: "deepseek-chat",
    fetchImpl,
    clock: { now: () => now },
  });

  assert.equal(sample.firstTokenMs, 100);
});

test("baselineOffsets reports median and p95, netOfBaseline subtracts", () => {
  const samples = [100, 200, 300, 400, 500].map((firstTokenMs, index) => ({
    firstTokenMs,
    at: `2026-10-25T00:00:0${index}Z`,
  }));
  assert.deepEqual(baselineOffsets(samples), { count: 5, medianMs: 300, p95Ms: 500 });
  assert.deepEqual(baselineOffsets([]), { count: 0, medianMs: null, p95Ms: null });
  assert.equal(netOfBaseline(1200, 300), 900);
  assert.equal(netOfBaseline(1200, null), 1200);
  assert.equal(netOfBaseline(null, 300), null);
  assert.equal(netOfBaseline(200, 300), 0);
});
