import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { after, test } from "node:test";
import type { AgentSessionEvent } from "@earendil-works/pi-coding-agent";
import { createApp } from "./app.js";
import type { GatewayConfig } from "./config.js";
import type { RunIdentity } from "./identity.js";
import type { GatewayPool, GatewaySession, GatewaySessionEntry } from "./pool-contract.js";
import { SessionConflictError } from "./pool-contract.js";

class FakeSession implements GatewaySession {
  private readonly listeners = new Set<(event: AgentSessionEvent) => void>();
  aborted = false;
  onAbort: (() => void) | undefined;

  get listenerCount(): number {
    return this.listeners.size;
  }

  subscribe(listener: (event: AgentSessionEvent) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  async abort(): Promise<void> {
    this.aborted = true;
    this.emit({ type: "agent_settled", aborted: true } as unknown as AgentSessionEvent);
    this.onAbort?.();
  }

  emit(event: AgentSessionEvent): void {
    for (const listener of [...this.listeners]) listener(event);
  }
}

interface FakeEntry extends GatewaySessionEntry {
  session: FakeSession;
  activeRun: RunIdentity | null;
}

class FakePool implements GatewayPool {
  readonly entries = new Map<string, FakeEntry>();
  deferRuns = false;

  get size(): number {
    return this.entries.size;
  }

  get(sessionId: string): FakeEntry | undefined {
    return this.entries.get(sessionId);
  }

  async ensure(sessionId: string, userId: string) {
    const existing = this.entries.get(sessionId);
    if (existing) {
      if (existing.userId !== userId) throw new SessionConflictError(sessionId);
      return { created: false, restored: false };
    }
    this.entries.set(sessionId, {
      sessionId,
      userId,
      activeRun: null,
      session: new FakeSession(),
    });
    return { created: true, restored: false };
  }

  beginRun(sessionId: string, identity: RunIdentity): FakeEntry | null {
    const entry = this.entries.get(sessionId);
    if (!entry || entry.activeRun) return null;
    entry.activeRun = identity;
    return entry;
  }

  endRun(sessionId: string, runId: string): void {
    const entry = this.entries.get(sessionId);
    if (entry?.activeRun?.runId === runId) entry.activeRun = null;
  }

  abort(sessionId: string): boolean {
    const entry = this.entries.get(sessionId);
    if (!entry?.activeRun) return false;
    void entry.session.abort();
    return true;
  }

  async dispose(sessionId: string): Promise<boolean> {
    return this.entries.delete(sessionId);
  }

  async run(sessionId: string, _text: string): Promise<void> {
    const entry = this.entries.get(sessionId);
    if (!entry) throw new Error("session missing");
    if (this.deferRuns) {
      await new Promise<void>((resolve) => {
        entry.session.onAbort = resolve;
      });
      entry.session.onAbort = undefined;
      return;
    }
    entry.session.emit({
      type: "message_update",
      assistantMessageEvent: { type: "text_delta", delta: "ok" },
    } as unknown as AgentSessionEvent);
    entry.session.emit({ type: "agent_settled", aborted: false } as unknown as AgentSessionEvent);
  }
}

interface TestServer {
  base: string;
  pool: FakePool;
  close: () => Promise<void>;
}

const tempDirs: string[] = [];

after(() => {
  for (const dir of tempDirs) rmSync(dir, { recursive: true, force: true });
});

async function startServer(apiToken?: string): Promise<TestServer> {
  const sessionDir = mkdtempSync(join(tmpdir(), "gateway-app-"));
  tempDirs.push(sessionDir);
  const pool = new FakePool();
  const config: GatewayConfig = {
    port: 0,
    javaApiUrl: "http://localhost:0",
    sandboxApiUrl: "http://localhost:0",
    apiToken,
    allowPrivateModelEndpoints: true,
    sessionDir,
  };
  const app = createApp({ config, pool });
  const server = app.listen(0, "127.0.0.1");
  await new Promise<void>((resolve) => server.once("listening", resolve));
  const address = server.address();
  if (!address || typeof address === "string") throw new Error("server did not bind a port");
  return {
    base: `http://127.0.0.1:${address.port}`,
    pool,
    close: () => new Promise((resolve) => server.close(() => resolve())),
  };
}

async function withServer(fn: (server: TestServer) => Promise<void>, apiToken?: string): Promise<void> {
  const server = await startServer(apiToken);
  try {
    await fn(server);
  } finally {
    await server.close();
  }
}

async function waitFor(predicate: () => boolean, timeoutMs = 2_000): Promise<void> {
  const started = Date.now();
  while (!predicate()) {
    if (Date.now() - started > timeoutMs) throw new Error("waitFor timed out");
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
}

function identity(overrides: Partial<RunIdentity> = {}): RunIdentity {
  return {
    userId: "u1",
    sessionId: "s1",
    turnId: "t1",
    runId: "r1",
    requestId: "q1",
    ...overrides,
  };
}

type ErrorBody = { error: { code: string; message: string; retryable: boolean } };

test("PUT is idempotent and rejects a different user", async () => {
  await withServer(async ({ base }) => {
    const first = await fetch(`${base}/sessions/s1`, {
      method: "PUT",
      headers: { "x-user-id": "u1" },
    });
    assert.equal(first.status, 200);
    assert.deepEqual(await first.json(), { sessionId: "s1", created: true, restored: false });

    const second = await fetch(`${base}/sessions/s1`, {
      method: "PUT",
      headers: { "x-user-id": "u1" },
    });
    assert.equal(second.status, 200);
    assert.deepEqual(await second.json(), { sessionId: "s1", created: false, restored: false });

    const conflict = await fetch(`${base}/sessions/s1`, {
      method: "PUT",
      headers: { "x-user-id": "u2" },
    });
    assert.equal(conflict.status, 409);
    const body = (await conflict.json()) as ErrorBody;
    assert.equal(body.error.code, "SESSION_CONFLICT");
  });
});

test("session endpoints require X-User-Id", async () => {
  await withServer(async ({ base }) => {
    const response = await fetch(`${base}/sessions/s1`, { method: "PUT" });
    assert.equal(response.status, 400);
    const body = (await response.json()) as ErrorBody;
    assert.equal(body.error.code, "INVALID_REQUEST");
  });
});

test("prompt rejects a user mismatch and a missing turnId", async () => {
  await withServer(async ({ base, pool }) => {
    await pool.ensure("s1", "u1");
    const mismatch = await fetch(`${base}/sessions/s1/prompt`, {
      method: "POST",
      headers: { "x-user-id": "u2", "content-type": "application/json" },
      body: JSON.stringify({ text: "hi", turnId: "t1" }),
    });
    assert.equal(mismatch.status, 403);
    assert.equal(((await mismatch.json()) as ErrorBody).error.code, "USER_MISMATCH");

    const missingTurn = await fetch(`${base}/sessions/s1/prompt`, {
      method: "POST",
      headers: { "x-user-id": "u1", "content-type": "application/json" },
      body: JSON.stringify({ text: "hi" }),
    });
    assert.equal(missingTurn.status, 400);
    assert.equal(((await missingTurn.json()) as ErrorBody).error.code, "INVALID_REQUEST");
  });
});

test("prompt returns 409 before opening SSE when a run is active", async () => {
  await withServer(async ({ base, pool }) => {
    await pool.ensure("s1", "u1");
    pool.entries.get("s1")!.activeRun = identity();
    const response = await fetch(`${base}/sessions/s1/prompt`, {
      method: "POST",
      headers: { "x-user-id": "u1", "content-type": "application/json" },
      body: JSON.stringify({ text: "hi", turnId: "t2" }),
    });
    assert.equal(response.status, 409);
    assert.equal(((await response.json()) as ErrorBody).error.code, "RUN_IN_PROGRESS");
  });
});

test("prompt streams contract v1 frames and releases the run slot", async () => {
  await withServer(async ({ base, pool }) => {
    await pool.ensure("s1", "u1");
    const response = await fetch(`${base}/sessions/s1/prompt`, {
      method: "POST",
      headers: { "x-user-id": "u1", "content-type": "application/json" },
      body: JSON.stringify({ text: "hi", turnId: "t1" }),
    });
    assert.equal(response.status, 200);
    const stream = await response.text();

    assert.match(stream, /^id: [^:]+:1\nevent: run_started\n/m);
    assert.match(stream, /"contractVersion":"1"/);
    assert.match(stream, /event: text_delta\ndata: \{"delta":"ok"\}\n/);
    assert.match(stream, /event: run_settled\ndata: \{"status":"completed","turnId":"t1"\}\n/);
    assert.equal((stream.match(/event: run_settled/g) ?? []).length, 1);
    assert.equal(pool.entries.get("s1")?.activeRun, null);
  });
});

test("abort requires an active run", async () => {
  await withServer(async ({ base, pool }) => {
    await pool.ensure("s1", "u1");
    const idle = await fetch(`${base}/sessions/s1/abort`, {
      method: "POST",
      headers: { "x-user-id": "u1" },
    });
    assert.equal(idle.status, 409);
    assert.equal(((await idle.json()) as ErrorBody).error.code, "NO_ACTIVE_RUN");

    pool.entries.get("s1")!.activeRun = identity();
    const active = await fetch(`${base}/sessions/s1/abort`, {
      method: "POST",
      headers: { "x-user-id": "u1" },
    });
    assert.equal(active.status, 202);
    assert.equal(pool.entries.get("s1")!.session.aborted, true);
  });
});

test("DELETE is idempotent and enforces ownership", async () => {
  await withServer(async ({ base, pool }) => {
    const absent = await fetch(`${base}/sessions/missing`, {
      method: "DELETE",
      headers: { "x-user-id": "u1" },
    });
    assert.equal(absent.status, 204);

    await pool.ensure("s1", "u1");
    const mismatch = await fetch(`${base}/sessions/s1`, {
      method: "DELETE",
      headers: { "x-user-id": "u2" },
    });
    assert.equal(mismatch.status, 403);
    assert.equal(((await mismatch.json()) as ErrorBody).error.code, "USER_MISMATCH");

    const deleted = await fetch(`${base}/sessions/s1`, {
      method: "DELETE",
      headers: { "x-user-id": "u1" },
    });
    assert.equal(deleted.status, 204);
    assert.equal(pool.entries.has("s1"), false);

    const afterDelete = await fetch(`${base}/sessions/s1/prompt`, {
      method: "POST",
      headers: { "x-user-id": "u1", "content-type": "application/json" },
      body: JSON.stringify({ text: "hi", turnId: "t1" }),
    });
    assert.equal(afterDelete.status, 404);
  });
});

test("provider probe requires the shared token", async () => {
  await withServer(
    async ({ base }) => {
      const response = await fetch(`${base}/providers/probe`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: "{}",
      });
      assert.equal(response.status, 401);
    },
    "secret",
  );
});

test("provider probe validates its request body", async () => {
  await withServer(async ({ base }) => {
    const response = await fetch(`${base}/providers/probe`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ api: "unknown", baseUrl: "", apiKey: "" }),
    });
    assert.equal(response.status, 400);
    const body = (await response.json()) as ErrorBody;
    assert.equal(body.error.code, "INVALID_REQUEST");
  });
});

test("shared token guards all endpoints", async () => {
  await withServer(
    async ({ base }) => {
      const denied = await fetch(`${base}/health`);
      assert.equal(denied.status, 401);
      assert.equal(((await denied.json()) as ErrorBody).error.code, "UNAUTHORIZED");

      const allowed = await fetch(`${base}/health`, {
        headers: { authorization: "Bearer secret" },
      });
      assert.equal(allowed.status, 200);
      const body = (await allowed.json()) as { contractVersion: string };
      assert.equal(body.contractVersion, "1");
    },
    "secret",
  );
});

test("abort ends the active stream with an aborted terminal", async () => {
  await withServer(async ({ base, pool }) => {
    await pool.ensure("s1", "u1");
    pool.deferRuns = true;

    const prompt = fetch(`${base}/sessions/s1/prompt`, {
      method: "POST",
      headers: { "x-user-id": "u1", "content-type": "application/json" },
      body: JSON.stringify({ text: "hi", turnId: "t1" }),
    });
    await waitFor(() => pool.entries.get("s1")?.session.listenerCount === 1);

    const abort = await fetch(`${base}/sessions/s1/abort`, {
      method: "POST",
      headers: { "x-user-id": "u1" },
    });
    assert.equal(abort.status, 202);

    const stream = await (await prompt).text();
    assert.match(stream, /event: run_settled\ndata: \{"status":"aborted","turnId":"t1"\}/);
    await waitFor(() => pool.entries.get("s1")?.activeRun === null);
  });
});

test("client disconnect aborts the run", async () => {
  await withServer(async ({ base, pool }) => {
    await pool.ensure("s1", "u1");
    pool.deferRuns = true;

    const controller = new AbortController();
    const prompt = fetch(`${base}/sessions/s1/prompt`, {
      method: "POST",
      headers: { "x-user-id": "u1", "content-type": "application/json" },
      body: JSON.stringify({ text: "hi", turnId: "t1" }),
      signal: controller.signal,
    });
    void prompt.catch(() => undefined);

    await waitFor(() => pool.entries.get("s1")?.session.listenerCount === 1);
    controller.abort();
    await waitFor(() => pool.entries.get("s1")?.session.aborted === true);
    await waitFor(() => pool.entries.get("s1")?.activeRun === null);
  });
});
