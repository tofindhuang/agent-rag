import { test } from "node:test";
import assert from "node:assert/strict";

import { ApiError, LoadTestClient } from "../src/api.js";

interface RecordedCall {
  url: string;
  init: RequestInit;
}

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}

function sseResponse(chunks: string[]): Response {
  const encoder = new TextEncoder();
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(encoder.encode(chunk));
      controller.close();
    },
  });
  return new Response(stream, {
    status: 200,
    headers: { "content-type": "text/event-stream; charset=utf-8" },
  });
}

function recordingClient(responses: Response[]) {
  const calls: RecordedCall[] = [];
  const fetchImpl = (async (input: Parameters<typeof fetch>[0], init?: RequestInit) => {
    calls.push({ url: String(input), init: init ?? {} });
    const next = responses.shift();
    if (!next) throw new Error("unexpected fetch call");
    return next;
  }) as typeof fetch;
  const client = new LoadTestClient({ baseUrl: "http://localhost:8080", fetchImpl });
  return { client, calls };
}

function headersOf(call: RecordedCall): Record<string, string> {
  return (call.init.headers ?? {}) as Record<string, string>;
}

test("register posts credentials to the auth endpoint", async () => {
  const { client, calls } = recordingClient([
    jsonResponse(201, { userId: 7, email: "user7@example.com" }),
  ]);

  const result = await client.register("user7@example.com", "password-7");

  assert.deepEqual(result, { userId: 7, email: "user7@example.com" });
  assert.equal(calls[0].url, "http://localhost:8080/api/auth/register");
  assert.equal(calls[0].init.method, "POST");
  assert.equal(headersOf(calls[0])["content-type"], "application/json");
  assert.deepEqual(JSON.parse(String(calls[0].init.body)), {
    email: "user7@example.com",
    password: "password-7",
  });
});

test("login returns the access token", async () => {
  const { client, calls } = recordingClient([
    jsonResponse(200, { accessToken: "token-7", tokenType: "Bearer", expiresIn: 900 }),
  ]);

  const result = await client.login("user7@example.com", "password-7");

  assert.equal(result.accessToken, "token-7");
  assert.equal(calls[0].url, "http://localhost:8080/api/auth/login");
});

test("createProvider sends a bearer token and the provider body", async () => {
  const { client, calls } = recordingClient([jsonResponse(201, { id: 11 })]);

  const result = await client.createProvider("token-7", {
    name: "deepseek",
    api: "openai-completions",
    baseUrl: "https://api.deepseek.com",
    apiKey: "test-key",
    models: [{ modelId: "deepseek-chat", displayName: "DeepSeek Chat" }],
  });

  assert.deepEqual(result, { id: 11 });
  assert.equal(calls[0].url, "http://localhost:8080/api/models/providers");
  assert.equal(headersOf(calls[0]).authorization, "Bearer token-7");
  assert.deepEqual(JSON.parse(String(calls[0].init.body)), {
    name: "deepseek",
    api: "openai-completions",
    baseUrl: "https://api.deepseek.com",
    apiKey: "test-key",
    models: [{ modelId: "deepseek-chat", displayName: "DeepSeek Chat" }],
  });
});

test("createSession posts the selected model and returns the session id", async () => {
  const { client, calls } = recordingClient([
    jsonResponse(200, { sessionId: "session-1" }),
  ]);

  const result = await client.createSession("token-7", { modelId: 11, noKnowledgeBase: true });

  assert.deepEqual(result, { sessionId: "session-1" });
  assert.equal(calls[0].url, "http://localhost:8080/api/chat/sessions");
  assert.deepEqual(JSON.parse(String(calls[0].init.body)), {
    modelId: 11,
    noKnowledgeBase: true,
  });
});

test("sendMessage streams contract events", async () => {
  const { client, calls } = recordingClient([
    sseResponse([
      'id: run1:1\nevent: run_started\ndata: {"runId":"run1"}\n\n',
      'id: run1:2\nevent: text_delta\ndata: {"delta":"hi"}\n\n',
      'id: run1:3\nevent: run_settled\ndata: {"status":"completed"}\n\n',
    ]),
  ]);

  const frames = [];
  for await (const frame of client.sendMessage("token-7", "session-1", {
    text: "hello",
    turnId: "turn-1",
  })) {
    frames.push(frame);
  }

  assert.deepEqual(
    frames.map((frame) => frame.event),
    ["run_started", "text_delta", "run_settled"],
  );
  assert.equal(
    calls[0].url,
    "http://localhost:8080/api/chat/sessions/session-1/messages",
  );
  assert.equal(headersOf(calls[0]).accept, "text/event-stream");
  assert.equal(headersOf(calls[0]).authorization, "Bearer token-7");
  assert.deepEqual(JSON.parse(String(calls[0].init.body)), {
    text: "hello",
    turnId: "turn-1",
  });
});

test("sendMessage surfaces JSON errors before the stream starts", async () => {
  const { client } = recordingClient([
    jsonResponse(409, {
      error: { code: "RUN_IN_PROGRESS", message: "run already in progress", retryable: false },
    }),
  ]);

  await assert.rejects(
    async () => {
      for await (const frame of client.sendMessage("token-7", "session-1", {
        text: "hello",
        turnId: "turn-1",
      })) {
        void frame;
      }
    },
    (error: unknown) => {
      assert.ok(error instanceof ApiError);
      assert.equal(error.status, 409);
      assert.equal(error.code, "RUN_IN_PROGRESS");
      assert.equal(error.retryable, false);
      return true;
    },
  );
});

test("listMessages reads the projected conversation", async () => {
  const response = jsonResponse(200, {
    items: [{ id: 1, role: "user", content: "hello", turnId: "turn-1", ordinal: 1 }],
    hasMore: false,
    nextBeforeId: null,
  });
  const { client: pageClient, calls: pageCalls } = recordingClient([response]);

  const result = await pageClient.listMessages("token-7", "session-1", { limit: 100 });

  assert.equal(result.items[0].content, "hello");
  assert.equal(pageCalls[0].url, "http://localhost:8080/api/chat/sessions/session-1/messages?limit=100");
  assert.equal(pageCalls[0].init.method, "GET");
  assert.equal(headersOf(pageCalls[0]).authorization, "Bearer token-7");
});

test("listModels returns the provider catalog used to pick a session model", async () => {
  const { client, calls } = recordingClient([
    jsonResponse(200, {
      providers: [
        {
          id: 11,
          name: "deepseek",
          api: "openai-completions",
          baseUrl: "https://api.deepseek.com",
          apiKeyHint: "…abcd",
          models: [{ id: 42, modelId: "deepseek-chat", displayName: "DeepSeek Chat" }],
        },
      ],
      defaultModelId: null,
      systemDefault: { label: "system" },
    }),
  ]);

  const catalog = await client.listModels("token-7");

  assert.equal(catalog.providers[0].models[0].id, 42);
  assert.equal(calls[0].url, "http://localhost:8080/api/models");
  assert.equal(headersOf(calls[0]).authorization, "Bearer token-7");
});

test("createKnowledgeBase posts the name", async () => {
  const { client, calls } = recordingClient([
    jsonResponse(200, { id: "kb-1", name: "loadtest", isDefault: true, documentCount: 0 }),
  ]);

  const result = await client.createKnowledgeBase("token-7", "loadtest");

  assert.equal(result.id, "kb-1");
  assert.equal(calls[0].url, "http://localhost:8080/api/kb/knowledge-bases");
  assert.deepEqual(JSON.parse(String(calls[0].init.body)), { name: "loadtest" });
});

test("uploadDocument sends multipart content with the kbId query parameter", async () => {
  const { client, calls } = recordingClient([
    jsonResponse(202, { id: "doc-1", status: "queued" }),
  ]);

  const result = await client.uploadDocument("token-7", "kb-1", "notes.md", "# notes");

  assert.deepEqual(result, { id: "doc-1", status: "queued" });
  assert.equal(calls[0].url, "http://localhost:8080/api/kb/documents?kbId=kb-1");
  assert.equal(calls[0].init.method, "POST");
  assert.equal(headersOf(calls[0]).authorization, "Bearer token-7");
  const form = calls[0].init.body as FormData;
  assert.ok(form instanceof FormData);
  const file = form.get("file") as File;
  assert.equal(file.name, "notes.md");
  assert.equal(await file.text(), "# notes");
});

test("getDocument reads the ingestion status", async () => {
  const { client, calls } = recordingClient([
    jsonResponse(200, { id: "doc-1", status: "ready" }),
  ]);

  const result = await client.getDocument("token-7", "doc-1");

  assert.equal(result.status, "ready");
  assert.equal(calls[0].url, "http://localhost:8080/api/kb/documents/doc-1");
});
