import assert from "node:assert/strict";
import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import type { GatewayConfig } from "./config.js";
import { kbSearchExtension, runCodeExtension } from "./extensions.js";
import type { RunIdentity } from "./identity.js";

interface CapturedTool {
  execute: (
    toolCallId: string,
    params: { language?: string; code: string; timeoutMs?: number },
    signal: AbortSignal | undefined,
    onUpdate: ((partial: { content: []; details: Record<string, unknown> }) => void) | undefined,
  ) => Promise<{
    isError?: boolean;
    content: Array<{ type: string; text?: string }>;
    details?: Record<string, unknown>;
  }>;
}

interface CapturedRequest {
  headers: IncomingMessage["headers"];
  body: string;
}

const identity: RunIdentity = {
  userId: "42",
  sessionId: "session-1",
  turnId: "turn-1",
  runId: "run-1",
  requestId: "req-1",
};

function toolFor(url: string): CapturedTool {
  let captured: CapturedTool | undefined;
  const pi = {
    registerTool: (tool: CapturedTool) => {
      captured = tool;
    },
  } as unknown as ExtensionAPI;
  const config: GatewayConfig = {
    port: 0,
    javaApiUrl: url,
    sandboxApiUrl: url,
    apiToken: "shared-token",
    sessionDir: "/tmp",
    workDir: "/tmp/agent-core-work",
    allowPrivateModelEndpoints: false,
    compaction: { enabled: true, reserveRatio: 0.2, keepRecentTokens: 20_000 },
    profileExtractMinIntervalMs: 60_000,
    profileExtractMaxMessages: 30,
    profileExtractMaxChars: 6_000,
    profileExtractMaxCandidates: 5,
    instanceId: "test-ext",
    instanceIncarnation: "test-inc",
    publicUrl: "http://127.0.0.1:0",
    maxSessions: 60,
    idleEvictMs: 900_000,
    drainGraceMs: 600_000,
    registryHeartbeatMs: 60_000,
    registryTtlSeconds: 15,
    lockHeartbeatMs: 60_000,
    lockStaleMs: 30_000,
  };
  runCodeExtension(config, () => identity)(pi);
  assert.ok(captured);
  return captured;
}

async function withServer(
  handler: (req: IncomingMessage, res: ServerResponse, body: string) => void,
): Promise<{ url: string; server: Server; requests: CapturedRequest[] }> {
  const requests: CapturedRequest[] = [];
  const server = createServer((req, res) => {
    let body = "";
    req.setEncoding("utf8");
    req.on("data", (chunk) => {
      body += chunk;
    });
    req.on("end", () => {
      requests.push({ headers: req.headers, body });
      handler(req, res, body);
    });
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  if (address === null || typeof address === "string") {
    throw new Error("server did not bind");
  }
  return { url: `http://127.0.0.1:${address.port}`, server, requests };
}

test("streams run_code frames into progress updates and a final result", async () => {
  const { url, server, requests } = await withServer((_req, res) => {
    res.writeHead(200, { "content-type": "text/event-stream" });
    res.write(": ping\n\n");
    res.write('event: started\ndata: {"execId":"exec-1"}\n\n');
    res.write('event: phase\ndata: {"phase":"starting"}\n\n');
    res.write('event: stdout\ndata: {"chunk":"hel');
    setTimeout(() => {
      res.write('lo"}\n\n');
      res.write('event: stderr\ndata: {"chunk":"warn"}\n\n');
      res.write(
        'event: result\ndata: {"exitCode":0,"durationMs":5,"timedOut":false,"truncated":true}\n\n',
      );
      res.end();
    }, 5);
  });

  try {
    const updates: Array<{
      details?: { progress?: { kind?: string; phase?: string; chunk?: string } };
    }> = [];
    const result = await toolFor(url).execute(
      "call-1",
      { code: "print('hello')", timeoutMs: 90_000 },
      undefined,
      (update) => updates.push(update as never),
    );

    assert.equal(result.isError, undefined);
    assert.deepEqual(
      updates.map((update) => update.details?.progress?.kind),
      ["phase", "stdout", "stderr"],
    );
    assert.equal(updates[1].details?.progress?.chunk, "hello");

    const text = result.content[0]?.text ?? "";
    assert.match(text, /\[exit code 0\]/);
    assert.match(text, /\[output truncated\]/);
    assert.match(text, /stdout:\nhello/);
    assert.match(text, /stderr:\nwarn/);
    assert.deepEqual(result.details, {
      execId: "exec-1",
      exitCode: 0,
      durationMs: 5,
      timedOut: false,
      truncated: true,
    });

    assert.equal(requests.length, 1);
    assert.equal(requests[0].headers.authorization, "Bearer shared-token");
    assert.equal(requests[0].headers["x-user-id"], "42");
    const body = JSON.parse(requests[0].body) as {
      language: string;
      code: string;
      timeoutMs: number;
    };
    assert.equal(body.language, "python");
    assert.equal(body.code, "print('hello')");
    assert.equal(body.timeoutMs, 60_000);
  } finally {
    server.close();
  }
});

test("maps a JSON error envelope to an error result with the code", async () => {
  const { url, server } = await withServer((_req, res) => {
    res.writeHead(503, { "content-type": "application/json" });
    res.end('{"error":{"code":"SANDBOX_QUEUE_TIMEOUT","message":"queue wait exceeded","retryable":true}}');
  });

  try {
    const result = await toolFor(url).execute("call-1", { code: "pass" }, undefined, undefined);

    assert.equal(result.isError, true);
    assert.match(result.content[0]?.text ?? "", /SANDBOX_QUEUE_TIMEOUT/);
    assert.equal(result.details?.errorCode, "SANDBOX_QUEUE_TIMEOUT");
  } finally {
    server.close();
  }
});

test("fails when the stream ends without a result frame", async () => {
  const { url, server } = await withServer((_req, res) => {
    res.writeHead(200, { "content-type": "text/event-stream" });
    res.end('event: started\ndata: {"execId":"exec-1"}\n\n');
  });

  try {
    const result = await toolFor(url).execute("call-1", { code: "pass" }, undefined, undefined);

    assert.equal(result.isError, true);
    assert.match(result.content[0]?.text ?? "", /without a result frame/);
  } finally {
    server.close();
  }
});

test("aborting the run cancels the request", async () => {
  const { url, server } = await withServer((_req, res) => {
    res.writeHead(200, { "content-type": "text/event-stream" });
    res.write(": ping\n\n");
    // Keep the stream open; the client abort should reject the read.
  });

  try {
    const controller = new AbortController();
    const pending = toolFor(url).execute("call-1", { code: "pass" }, controller.signal, undefined);
    setTimeout(() => controller.abort(), 20);

    const result = await pending;
    assert.equal(result.isError, true);
    assert.match(result.content[0]?.text ?? "", /aborted/);
  } finally {
    server.close();
  }
});

function kbSearchToolFor(url: string): CapturedTool {
  let captured: CapturedTool | undefined;
  const pi = {
    registerTool: (tool: CapturedTool) => {
      captured = tool;
    },
  } as unknown as ExtensionAPI;
  const config: GatewayConfig = {
    port: 0,
    javaApiUrl: url,
    sandboxApiUrl: url,
    apiToken: "shared-token",
    sessionDir: "/tmp",
    workDir: "/tmp/agent-core-work",
    allowPrivateModelEndpoints: false,
    compaction: { enabled: true, reserveRatio: 0.2, keepRecentTokens: 20_000 },
    profileExtractMinIntervalMs: 60_000,
    profileExtractMaxMessages: 30,
    profileExtractMaxChars: 6_000,
    profileExtractMaxCandidates: 5,
    instanceId: "test-ext",
    instanceIncarnation: "test-inc",
    publicUrl: "http://127.0.0.1:0",
    maxSessions: 60,
    idleEvictMs: 900_000,
    drainGraceMs: 600_000,
    registryHeartbeatMs: 60_000,
    registryTtlSeconds: 15,
    lockHeartbeatMs: 60_000,
    lockStaleMs: 30_000,
  };
  kbSearchExtension(config, () => identity)(pi);
  assert.ok(captured);
  return captured;
}

test("kb_search formats hits exactly as before", async () => {
  const { url, server } = await withServer((_req, res) => {
    res.writeHead(200, { "content-type": "application/json" });
    res.end(
      JSON.stringify({
        passages: [
          {
            passageId: "p-1",
            documentId: "doc-1",
            seq: 2,
            page: 7,
            title: "handbook.pdf",
            content: "段落内容",
            score: 0.8123,
            reranked: false,
            kbId: "kb-1",
            kbName: "默认",
          },
        ],
        noAnswer: false,
      }),
    );
  });

  try {
    const result = await kbSearchToolFor(url).execute(
      "call-1",
      { query: "问题" } as never,
      undefined,
      undefined,
    );

    assert.equal(result.isError, undefined);
    assert.equal(
      result.content[0]?.text,
      "[1] 《handbook.pdf》 doc=doc-1 seq=2 page=7 score=0.812\n段落内容",
    );
    assert.deepEqual(result.details, { count: 1, passages: (result.details as { passages: unknown[] }).passages });
  } finally {
    server.close();
  }
});

test("kb_search reports a refusal as no-answer instead of an empty hit list", async () => {
  const { url, server } = await withServer((_req, res) => {
    res.writeHead(200, { "content-type": "application/json" });
    res.end(
      JSON.stringify({
        passages: [],
        noAnswer: true,
        maxScore: 0.042,
        threshold: 0.15,
      }),
    );
  });

  try {
    const result = await kbSearchToolFor(url).execute(
      "call-1",
      { query: "库里没有的问题" } as never,
      undefined,
      undefined,
    );

    assert.equal(result.isError, undefined);
    const text = result.content[0]?.text ?? "";
    assert.match(text, /知识库中没有找到/);
    assert.match(text, /不要凭空回答/);
    assert.equal((result.details as { noAnswer?: boolean }).noAnswer, true);
    assert.equal((result.details as { maxScore?: number }).maxScore, 0.042);
    assert.equal((result.details as { threshold?: number }).threshold, 0.15);
  } finally {
    server.close();
  }
});
