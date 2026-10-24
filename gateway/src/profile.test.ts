import assert from "node:assert/strict";
import { test } from "node:test";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { parseCandidates, profileFactsExtension, transcriptFrom } from "./profile.js";
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
});

test("parses only valid candidates and caps the list", () => {
  const text = "```json\n" + JSON.stringify({
    facts: [
      { category: "preference", factKey: "reply.language", factValue: "zh", factText: "中文" },
      { category: "preference", factKey: 7, factValue: "x", factText: "bad" },
      { category: "project", factKey: "project.db", factValue: "mysql", factText: "MySQL" },
    ],
  }) + "\n```";
  assert.deepEqual(parseCandidates(text, 1), [
    { category: "preference", factKey: "reply.language", factValue: "zh", factText: "中文" },
  ]);
  assert.deepEqual(parseCandidates("no json", 5), []);
});
