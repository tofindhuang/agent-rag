import assert from "node:assert/strict";
import { existsSync, mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { after, test } from "node:test";
import { SessionManager } from "@earendil-works/pi-coding-agent";
import type { GatewayConfig } from "./config.js";
import { SessionPool, type PiSession, type SessionFactory } from "./pool.js";
import { SessionConflictError } from "./pool-contract.js";

const dirs: string[] = [];

function config(): GatewayConfig {
  const dir = mkdtempSync(join(tmpdir(), "gateway-pool-"));
  dirs.push(dir);
  return {
    port: 0,
    javaApiUrl: "http://localhost:8080",
    sandboxApiUrl: "http://localhost:8080",
    apiToken: undefined,
    sessionDir: dir,
  };
}

/** File store is real; the pi AgentSession is faked so no model is needed. */
const fakeFactory: SessionFactory = async (manager) => {
  // pi only flushes a session file once it contains a conversation message.
  manager.appendMessage({
    role: "user",
    content: [{ type: "text", text: "hello" }],
    timestamp: Date.now(),
  } as unknown as Parameters<typeof manager.appendMessage>[0]);
  // Test double: the pool only calls these four members.
  return {
    abort: async () => undefined,
    dispose: () => undefined,
    prompt: async () => undefined,
    subscribe: () => () => undefined,
  } as unknown as PiSession;
};

after(() => {
  for (const dir of dirs) rmSync(dir, { recursive: true, force: true });
});

test("ensure creates, dispose keeps the file, restart restores", async () => {
  const cfg = config();
  const first = new SessionPool(cfg, fakeFactory);

  const created = await first.ensure("s1", "u1");

  assert.deepEqual(created, { created: true, restored: false });
  const file = SessionManager.findById(process.cwd(), "s1", cfg.sessionDir);
  assert.ok(file && existsSync(file));

  await first.disposeAll();
  assert.ok(existsSync(file), "dispose must not delete the pi session file");

  const restarted = new SessionPool(cfg, fakeFactory);
  const restored = await restarted.ensure("s1", "u1");

  assert.deepEqual(restored, { created: false, restored: true });
  await assert.rejects(() => restarted.ensure("s1", "u2"), SessionConflictError);
  await restarted.disposeAll();
});
