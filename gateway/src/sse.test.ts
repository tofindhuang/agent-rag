import assert from "node:assert/strict";
import { test } from "node:test";
import { SseFrameParser } from "./sse.js";

test("parses named frames and ignores comments", () => {
  const parser = new SseFrameParser();
  const frames = parser.push(
    Buffer.from(': ping\n\nevent: started\ndata: {"execId":"e1"}\n\nevent: result\ndata: {"exitCode":0}\n\n'),
  );

  assert.deepEqual(
    frames.map((frame) => frame.event),
    ["started", "result"],
  );
  assert.equal(frames[0].data, '{"execId":"e1"}');
});

test("reassembles frames split across chunks and CRLF line endings", () => {
  const parser = new SseFrameParser();
  const first = parser.push(Buffer.from("event: std"));
  const second = parser.push(Buffer.from('out\r\ndata: {"chunk":"hi"}\r\n\r\n'));

  assert.deepEqual(first, []);
  assert.deepEqual(second, [{ event: "stdout", data: '{"chunk":"hi"}' }]);
});

test("joins multi-line data fields", () => {
  const parser = new SseFrameParser();
  const frames = parser.push(Buffer.from('event: x\ndata: a\ndata: b\n\n'));

  assert.deepEqual(frames, [{ event: "x", data: "a\nb" }]);
});

test("keeps a partial UTF-8 code point between chunks", () => {
  const parser = new SseFrameParser();
  const bytes = Buffer.from('event: stdout\ndata: {"chunk":"你"}\n\n');
  const split = bytes.indexOf(Buffer.from("你")) + 1;

  const first = parser.push(bytes.subarray(0, split));
  const second = parser.push(bytes.subarray(split));

  assert.deepEqual(first, []);
  assert.deepEqual(second, [{ event: "stdout", data: '{"chunk":"你"}' }]);
});

test("flushes a pending frame at end of stream", () => {
  const parser = new SseFrameParser();
  parser.push(Buffer.from('event: result\ndata: {"exitCode":0}'));

  assert.deepEqual(parser.end(), [{ event: "result", data: '{"exitCode":0}' }]);
});
