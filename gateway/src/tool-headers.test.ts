import assert from "node:assert/strict";
import { test } from "node:test";
import { buildToolHeaders } from "./tool-headers.js";

test("tool callbacks carry the full run identity", () => {
  const headers = buildToolHeaders(
    {
      userId: "u1",
      sessionId: "s1",
      turnId: "t1",
      runId: "r1",
      requestId: "q1",
    },
    "call-1",
    "token",
  );

  assert.deepEqual(headers, {
    "X-User-Id": "u1",
    "X-Session-Id": "s1",
    "X-Turn-Id": "t1",
    "X-Run-Id": "r1",
    "X-Tool-Call-Id": "call-1",
    "X-Request-Id": "q1",
    Authorization: "Bearer token",
  });
});

test("tool callbacks omit authorization when no token is configured", () => {
  const headers = buildToolHeaders(
    {
      userId: "u1",
      sessionId: "s1",
      turnId: "t1",
      runId: "r1",
      requestId: "q1",
    },
    "call-1",
  );
  assert.equal(headers.Authorization, undefined);
});
