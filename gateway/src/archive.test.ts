import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { after, test } from "node:test";
import { gunzipSync } from "node:zlib";
import { HttpArchiveTransport } from "./archive.js";
import type { GatewayConfig } from "./config.js";

const servers: ReturnType<typeof createServer>[] = [];
after(() => {
  for (const server of servers) server.close();
});

function config(url: string): GatewayConfig {
  return {
    port: 0,
    javaApiUrl: url,
    sandboxApiUrl: url,
    apiToken: "shared-token",
    sessionDir: "/tmp",
    instanceId: "test",
    publicUrl: "http://127.0.0.1:0",
    maxSessions: 60,
    idleEvictMs: 0,
    drainGraceMs: 0,
    registryHeartbeatMs: 5_000,
    registryTtlSeconds: 15,
    lockHeartbeatMs: 5_000,
    lockStaleMs: 30_000,
    allowPrivateModelEndpoints: false,
    compaction: { enabled: true, reserveRatio: 0.2, keepRecentTokens: 20_000 },
    profileExtractMinIntervalMs: 60_000,
    profileExtractMaxMessages: 30,
    profileExtractMaxChars: 6_000,
    profileExtractMaxCandidates: 5,
  };
}

function sha(value: Buffer): string {
  return createHash("sha256").update(value).digest("hex");
}

test("gzips the JSONL, uploads both parts, and verifies downloads", async () => {
  const uploaded = new Map<string, Buffer>();
  const server = createServer((req, res) => {
    void handle(req, res, uploaded);
  });
  servers.push(server);
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  assert.ok(address && typeof address !== "string");
  const transport = new HttpArchiveTransport(config(`http://127.0.0.1:${address.port}`));

  await transport.upload("s1", "7", Buffer.from("hello"), Buffer.from("{}"));

  const gz = uploaded.get("jsonl");
  assert.ok(gz);
  assert.equal(gunzipSync(gz).toString("utf8"), "hello");
  assert.equal(uploaded.get("meta")?.toString("utf8"), "{}");

  const restored = await transport.download("s1", "7");
  assert.equal(restored.jsonl.toString("utf8"), "hello");
  assert.equal(restored.meta.toString("utf8"), "{}");
});

async function handle(
  req: IncomingMessage,
  res: ServerResponse,
  uploaded: Map<string, Buffer>,
): Promise<void> {
  const chunks: Buffer[] = [];
  for await (const chunk of req) chunks.push(Buffer.from(chunk));
  const body = Buffer.concat(chunks);
  const kind = req.url?.endsWith("/jsonl") ? "jsonl" : "meta";
  assert.equal(req.headers["x-user-id"], "7");
  assert.equal(req.headers.authorization, "Bearer shared-token");

  if (req.method === "PUT") {
    assert.equal(req.headers["x-sha256"], sha(body));
    uploaded.set(kind, body);
    res.writeHead(204).end();
    return;
  }
  const value = uploaded.get(kind) ?? Buffer.from(kind === "jsonl" ? "hello" : "{}");
  res.writeHead(200, {
    "content-type": kind === "jsonl" ? "application/gzip" : "application/json",
    "x-sha256": sha(value),
    "content-length": String(value.length),
  });
  res.end(value);
}
