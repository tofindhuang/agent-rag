import { test } from "node:test";
import assert from "node:assert/strict";

import { verifySettledMessages, type VerifyRecord } from "../src/verify.js";

interface StoredMessage {
  id: number;
  turnId: string;
  role: string;
  ordinal: number;
  content: string;
  status: string;
}

function fakeClient(messagesBySession: Record<string, StoredMessage[]>) {
  const calls: { sessionId: string; beforeId?: number }[] = [];
  return {
    calls,
    async login(email: string) {
      return { accessToken: `token-${email}`, tokenType: "Bearer", expiresIn: 900 };
    },
    async listMessages(
      _token: string,
      sessionId: string,
      options: { beforeId?: number; limit?: number } = {},
    ) {
      calls.push({ sessionId, beforeId: options.beforeId });
      const all = messagesBySession[sessionId] ?? [];
      const limit = options.limit ?? 50;
      const end = options.beforeId === undefined ? all.length : all.findIndex((m) => m.id === options.beforeId);
      const sliceEnd = end === -1 ? 0 : end;
      const items = all.slice(Math.max(0, sliceEnd - limit), sliceEnd);
      return {
        items,
        hasMore: Math.max(0, sliceEnd - limit) > 0,
        nextBeforeId: items.length > 0 ? items[0].id : null,
      };
    },
  };
}

function record(overrides: Partial<VerifyRecord>): VerifyRecord {
  return {
    email: "user@example.com",
    sessionId: "session-1",
    turnId: "turn-1",
    status: "completed",
    errorClass: "none",
    ...overrides,
  };
}

function message(overrides: Partial<StoredMessage>): StoredMessage {
  return {
    id: 1,
    turnId: "turn-1",
    role: "user",
    ordinal: 1,
    content: "hello",
    status: "sent",
    ...overrides,
  };
}

test("accepts a session where every completed turn was projected once", async () => {
  const client = fakeClient({
    "session-1": [
      message({ id: 1, turnId: "turn-1", role: "user", ordinal: 1 }),
      message({ id: 2, turnId: "turn-1", role: "assistant", ordinal: 2 }),
      message({ id: 3, turnId: "turn-2", role: "user", ordinal: 3 }),
      message({ id: 4, turnId: "turn-2", role: "assistant", ordinal: 4 }),
    ],
  });

  const result = await verifySettledMessages(
    client,
    [record({ turnId: "turn-1" }), record({ turnId: "turn-2" })],
    "password",
  );

  assert.deepEqual(result.missing, []);
  assert.deepEqual(result.duplicates, []);
  assert.deepEqual(result.roleMismatches, []);
  assert.equal(result.sessionsChecked, 1);
  assert.equal(result.turnsChecked, 2);
});

test("reports a completed turn whose messages are missing", async () => {
  const client = fakeClient({
    "session-1": [
      message({ id: 1, turnId: "turn-1", role: "user", ordinal: 1 }),
      message({ id: 2, turnId: "turn-1", role: "assistant", ordinal: 2 }),
    ],
  });

  const result = await verifySettledMessages(
    client,
    [record({ turnId: "turn-1" }), record({ turnId: "turn-2" })],
    "password",
  );

  assert.deepEqual(result.missing, ["turn-2"]);
});

test("reports duplicate user rows for the same turn", async () => {
  const client = fakeClient({
    "session-1": [
      message({ id: 1, turnId: "turn-1", role: "user", ordinal: 1 }),
      message({ id: 2, turnId: "turn-1", role: "user", ordinal: 2 }),
      message({ id: 3, turnId: "turn-1", role: "assistant", ordinal: 3 }),
    ],
  });

  const result = await verifySettledMessages(client, [record({ turnId: "turn-1" })], "password");

  assert.deepEqual(result.duplicates, ["turn-1"]);
});

test("reports a completed turn without an assistant reply", async () => {
  const client = fakeClient({
    "session-1": [message({ id: 1, turnId: "turn-1", role: "user", ordinal: 1 })],
  });

  const result = await verifySettledMessages(client, [record({ turnId: "turn-1" })], "password");

  assert.deepEqual(result.roleMismatches, ["turn-1"]);
});

test("paginates through long conversations", async () => {
  const messages: StoredMessage[] = Array.from({ length: 130 }, (_, index) =>
    message({
      id: index + 1,
      turnId: `turn-${Math.floor(index / 2) + 1}`,
      role: index % 2 === 0 ? "user" : "assistant",
      ordinal: index + 1,
    }),
  );
  const client = fakeClient({ "session-1": messages });

  const result = await verifySettledMessages(
    client,
    [record({ turnId: "turn-1" }), record({ turnId: "turn-65" })],
    "password",
  );

  assert.deepEqual(result.missing, []);
  assert.equal(client.calls.length, 2);
  assert.equal(client.calls[1].beforeId, 31);
});
