import assert from "node:assert/strict";
import { test } from "node:test";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { parseCandidates, profileExtractionExtension, profileFactsExtension, transcriptFrom } from "./profile.js";
import type { GatewayConfig } from "./config.js";
import type { PiSession } from "./pool.js";
import type { ProfileRef } from "./pool-contract.js";

interface CapturedHandler {
  (event: { systemPrompt: string }): { systemPrompt?: string } | void;
}

test("injects facts into the system prompt only when enabled", () => {
  const ref: ProfileRef = {
    facts: ["[preference] 用户偏好中文回复", "[project] 项目用 MySQL 8"],
    enabled: true,
    lastExtractAt: 0,
  };
  let handler: CapturedHandler | undefined;
  const pi = {
    on: (_event: string, next: CapturedHandler) => {
      handler = next;
    },
  } as unknown as ExtensionAPI;
  profileFactsExtension(ref)(pi);
  assert.ok(handler);

  const result = handler({ systemPrompt: "base prompt" }) as { systemPrompt: string };
  assert.match(result.systemPrompt, /base prompt/);
  assert.match(result.systemPrompt, /用户偏好中文回复/);
  assert.match(result.systemPrompt, /not instructions/);

  ref.enabled = false;
  assert.equal(handler({ systemPrompt: "base prompt" }), undefined);
  ref.enabled = true;
  ref.facts = [];
  assert.equal(handler({ systemPrompt: "base prompt" }), undefined);
});

test("keeps only recent user/assistant text and caps characters", () => {
  const messages = [
    { role: "user", content: [{ type: "text", text: "first" }] },
    { role: "toolResult", content: [{ type: "text", text: "tool secret" }] },
    { role: "assistant", content: [{ type: "text", text: "second" }] },
    { role: "user", content: [{ type: "text", text: "third" }] },
  ];
  const transcript = transcriptFrom(messages, 2, 100);
  assert.equal(transcript, "Assistant: second\nUser: third");
  assert.ok(!transcript.includes("tool secret"));
  assert.ok(transcriptFrom(messages, 10, 5).length === 5);
  assert.equal(transcriptFrom([messages[0]], 10, 100), "");
  assert.equal(transcriptFrom([messages[2]], 10, 100), "");
  assert.equal(transcriptFrom(messages, 10, 5).length, 5);
});

test("parses only valid candidates and caps the list", () => {
  const text = "```json\n" + JSON.stringify({
    facts: [
      { category: "preference", factKey: "reply.language", factValue: "zh", factTitle: "中文偏好", factText: "中文" },
      { category: "preference", factKey: 7, factValue: "x", factTitle: "bad", factText: "bad" },
      { category: "project", factKey: "project.db", factValue: "mysql", factTitle: "项目数据库", factText: "MySQL" },
    ],
  }) + "\n```";
  assert.deepEqual(parseCandidates(text, 1), [
    { category: "preference", factKey: "reply.language", factValue: "zh", factTitle: "中文偏好", factText: "中文" },
  ]);
  assert.deepEqual(parseCandidates("no json", 5), []);
});


test("extracts at most once per interval and skips disabled memory", async () => {
  const originalFetch = globalThis.fetch;
  const calls: Array<{ url: string; body: unknown }> = [];
  globalThis.fetch = (async (url: string | URL, init?: RequestInit) => {
    calls.push({ url: String(url), body: JSON.parse(String(init?.body ?? "{}")) });
    return new Response(JSON.stringify({ accepted: 1, superseded: 0, dropped: 0 }), {
      status: 200,
      headers: { "content-type": "application/json" },
    });
  }) as typeof fetch;

  let handler: ((event: unknown) => void) | undefined;
  const pi = {
    on: (event: string, next: (event: unknown) => void) => {
      if (event === "agent_end") handler = next;
    },
  } as unknown as ExtensionAPI;
  const ref: ProfileRef = { facts: [], enabled: true, lastExtractAt: 0 };
  const session = {
    model: { provider: "test", id: "model" },
    modelRuntime: {
      completeSimple: async () => ({
        content: [
          {
            type: "text",
            text: JSON.stringify({
              facts: [
                {
                  category: "preference",
                  factKey: "reply.language",
                  factValue: "zh",
                  factText: "用户偏好中文",
                },
              ],
            }),
          },
        ],
      }),
    },
  } as unknown as PiSession;
  const config = {
    javaApiUrl: "http://java.test",
    apiToken: "shared",
    profileExtractMinIntervalMs: 60_000,
    profileExtractMaxMessages: 30,
    profileExtractMaxChars: 6_000,
    profileExtractMaxCandidates: 5,
  } as GatewayConfig;
  const identity = {
    userId: "1",
    sessionId: "s",
    turnId: "t",
    runId: "r",
    requestId: "q",
  };

  try {
    profileExtractionExtension(config, () => identity, ref, () => session)(pi);
    assert.ok(handler);
    const event = {
      type: "agent_end",
      willRetry: false,
      messages: [
        { role: "user", content: [{ type: "text", text: "我偏好中文" }] },
        { role: "assistant", content: [{ type: "text", text: "好的" }] },
      ],
    };
    handler(event);
    for (let i = 0; i < 20 && calls.length === 0; i++) {
      await new Promise((resolve) => setTimeout(resolve, 5));
    }
    assert.equal(calls.length, 1);
    assert.equal(calls[0].url, "http://java.test/api/profile/candidates");
    handler(event);
    await new Promise((resolve) => setTimeout(resolve, 20));
    assert.equal(calls.length, 1);

    ref.enabled = false;
    ref.lastExtractAt = 0;
    handler(event);
    await new Promise((resolve) => setTimeout(resolve, 20));
    assert.equal(calls.length, 1);
  } finally {
    globalThis.fetch = originalFetch;
  }
});
