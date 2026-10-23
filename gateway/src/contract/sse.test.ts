import assert from "node:assert/strict";
import { test } from "node:test";
import type { DomainEvent } from "./events.js";
import { formatSseFrame, HEARTBEAT_FRAME } from "./sse.js";
import { truncateUtf8 } from "./text.js";

test("formats a contract v1 SSE frame", () => {
  const event: DomainEvent = { type: "text_delta", data: { delta: "hi\n" } };
  assert.equal(
    formatSseFrame("run-1", 1, event),
    'id: run-1:1\nevent: text_delta\ndata: {"delta":"hi\\n"}\n\n',
  );
});

test("heartbeat is an SSE comment frame", () => {
  assert.equal(HEARTBEAT_FRAME, ": ping\n\n");
});

test("truncates UTF-8 without splitting a character", () => {
  assert.deepEqual(truncateUtf8("abc", 3), { text: "abc", truncated: false });
  assert.deepEqual(truncateUtf8("你好世界", 6), { text: "你好", truncated: true });
  assert.deepEqual(truncateUtf8("你好世界", 7), { text: "你好", truncated: true });
});
