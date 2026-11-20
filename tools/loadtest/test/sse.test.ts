import { test } from "node:test";
import assert from "node:assert/strict";

import { parseSse } from "../src/sse.js";

async function* chunks(...parts: (string | Uint8Array)[]): AsyncGenerator<string | Uint8Array> {
  for (const part of parts) yield part;
}

async function collect(stream: AsyncIterable<unknown>): Promise<unknown[]> {
  const out: unknown[] = [];
  for await (const item of stream) out.push(item);
  return out;
}

test("parses a complete frame with id, event and data", async () => {
  const frames = await collect(
    parseSse(chunks('id: r:1\nevent: run_started\ndata: {"runId":"r"}\n\n')),
  );
  assert.deepEqual(frames, [{ id: "r:1", event: "run_started", data: '{"runId":"r"}' }]);
});

test("parses consecutive frames in order and skips ping comments", async () => {
  const frames = await collect(
    parseSse(
      chunks(
        ": ping\n\n",
        'id: r:1\nevent: run_started\ndata: {"seq":1}\n\n',
        ": ping\n\n",
        'id: r:2\nevent: text_delta\ndata: {"delta":"hi"}\n\n',
        'id: r:3\nevent: run_settled\ndata: {"status":"completed"}\n\n',
      ),
    ),
  );
  assert.deepEqual(
    frames.map((frame) => (frame as { event: string }).event),
    ["run_started", "text_delta", "run_settled"],
  );
});

test("handles CRLF frames and joins multiple data lines", async () => {
  const frames = await collect(
    parseSse(chunks("event: note\r\ndata: line-1\r\ndata: line-2\r\n\r\n")),
  );
  assert.deepEqual(frames, [{ event: "note", data: "line-1\nline-2" }]);
});

test("decodes a multi-byte character split across chunks", async () => {
  const payload = Buffer.from("event: text_delta\ndata: {\"delta\":\"你好\"}\n\n", "utf8");
  const splitAt = payload.indexOf(Buffer.from("你", "utf8")) + 1;
  const frames = await collect(parseSse(chunks(payload.subarray(0, splitAt), payload.subarray(splitAt))));
  assert.deepEqual(frames, [{ event: "text_delta", data: '{"delta":"你好"}' }]);
});

test("flushes a trailing frame that lacks the final blank line", async () => {
  const frames = await collect(
    parseSse(chunks('event: run_settled\ndata: {"status":"completed"}\n')),
  );
  assert.deepEqual(frames, [{ event: "run_settled", data: '{"status":"completed"}' }]);
});

test("defaults the event name to message when absent", async () => {
  const frames = await collect(parseSse(chunks("data: {}\n\n")));
  assert.deepEqual(frames, [{ event: "message", data: "{}" }]);
});
