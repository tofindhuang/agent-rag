import assert from "node:assert/strict";
import { existsSync, mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { after, test } from "node:test";
import { SessionManager } from "@earendil-works/pi-coding-agent";
import type { GatewayConfig } from "./config.js";
import { AGENT_TOOL_WHITELIST, SessionPool, type PiSession, type SessionFactory } from "./pool.js";
import type { SessionModelConfig } from "./pool-contract.js";
import type { RunRef } from "./identity.js";
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
    allowPrivateModelEndpoints: false,
    compaction: { enabled: true, reserveRatio: 0.2, keepRecentTokens: 20_000 },
    profileExtractMinIntervalMs: 60_000,
    profileExtractMaxMessages: 30,
    profileExtractMaxChars: 6_000,
    profileExtractMaxCandidates: 5,
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

test("the default session factory forwards the requested model config", async () => {
  class CapturingPool extends SessionPool {
    captured?: SessionModelConfig;

    protected override async createPiSession(
      _manager: SessionManager,
      _runRef: RunRef,
      _userId: string,
      modelConfig?: SessionModelConfig,
    ): Promise<PiSession> {
      this.captured = modelConfig;
      return {
        abort: async () => undefined,
        dispose: () => undefined,
        prompt: async () => undefined,
        subscribe: () => () => undefined,
        getContextUsage: () => ({
          tokens: null,
          contextWindow: 8_000,
          percent: null,
        }),
      } as unknown as PiSession;
    }
  }

  const pool = new CapturingPool(config());
  const requested: SessionModelConfig = {
    providerId: 7,
    name: "Test",
    api: "openai-completions",
    baseUrl: "https://example.com/v1",
    apiKey: "sk-test",
    modelId: "test-model",
    contextWindow: 8_000,
  };

  await pool.ensure("capture-model", "u1", requested, {
    facts: ["[preference] 中文"],
    enabled: true,
  });
  assert.deepEqual(pool.captured, requested);
  const entryBefore = pool.get("capture-model");
  assert.deepEqual(entryBefore?.profileRef.facts, ["[preference] 中文"]);

  await pool.ensure("capture-model", "u1", requested, {
    facts: ["[project] MySQL 8"],
    enabled: false,
  });
  assert.equal(pool.get("capture-model"), entryBefore);
  assert.deepEqual(pool.get("capture-model")?.profileRef.facts, ["[project] MySQL 8"]);
  assert.equal(pool.get("capture-model")?.profileRef.enabled, false);

  const entry = pool.get("capture-model");
  assert.ok(entry);
  entry.lastContextTokens = 1_234;
  pool.beginRun("capture-model", {
    userId: "u1",
    sessionId: "capture-model",
    turnId: "t",
    runId: "r",
    requestId: "q",
  });
  assert.equal(entry.lastContextTokens, null);
  await pool.disposeAll();
});

test("only the FR-6 tool whitelist is exposed to the model", () => {
  assert.deepEqual([...AGENT_TOOL_WHITELIST], ["kb_search", "run_code"]);
});

test("ensure echoes the requested model and keeps its key off disk", async () => {
  const cfg = config();
  const pool = new SessionPool(cfg, fakeFactory);

  const result = await pool.ensure("s-model", "u1", {
    providerId: 1,
    name: "SiliconFlow",
    api: "openai-completions",
    baseUrl: "https://api.example.com/v1",
    apiKey: "sk-secret-key",
    modelId: "deepseek-chat",
    displayName: "DeepSeek Chat",
  });

  assert.deepEqual(result.model, {
    provider: "SiliconFlow",
    id: "deepseek-chat",
    name: "DeepSeek Chat",
  });
  const again = await pool.ensure("s-model", "u1");
  assert.deepEqual(again.model, result.model);

  const sidecar = readFileSync(join(cfg.sessionDir, "s-model.meta.json"), "utf8");
  assert.ok(!sidecar.includes("sk-secret-key"));
  const file = SessionManager.findById(process.cwd(), "s-model", cfg.sessionDir);
  assert.ok(file);
  assert.ok(!readFileSync(file, "utf8").includes("sk-secret-key"));

  await pool.disposeAll();

  const restarted = new SessionPool(cfg, fakeFactory);
  const restored = await restarted.ensure("s-model", "u1", {
    providerId: 1,
    name: "SiliconFlow",
    api: "openai-completions",
    baseUrl: "https://api.example.com/v1",
    apiKey: "sk-secret-key",
    modelId: "deepseek-chat",
    displayName: "DeepSeek Chat",
  });
  assert.equal(restored.restored, true);
  assert.deepEqual(restored.model, result.model);
  await restarted.disposeAll();
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

test("ensure rebuilds the runtime when the model config changes", async () => {
  const cfg = config();
  const factoryCalls: Array<string | undefined> = [];
  const factory: SessionFactory = async (manager, _runRef, _userId, modelConfig) => {
    factoryCalls.push(modelConfig?.apiKey);
    manager.appendMessage({
      role: "user",
      content: [{ type: "text", text: "hello" }],
      timestamp: Date.now(),
    } as unknown as Parameters<typeof manager.appendMessage>[0]);
    return {
      abort: async () => undefined,
      dispose: () => undefined,
      prompt: async () => undefined,
      subscribe: () => () => undefined,
    } as unknown as PiSession;
  };
  const pool = new SessionPool(cfg, factory);
  const base = {
    providerId: 1,
    name: "SiliconFlow",
    api: "openai-completions" as const,
    baseUrl: "https://api.example.com/v1",
    modelId: "deepseek-chat",
    displayName: "DeepSeek Chat",
  };

  await pool.ensure("s-key", "u1", { ...base, apiKey: "sk-old" });
  const same = await pool.ensure("s-key", "u1", { ...base, apiKey: "sk-old" });
  assert.equal(factoryCalls.length, 1, "identical config must not rebuild");
  assert.equal(same.created, false);

  const rotated = await pool.ensure("s-key", "u1", { ...base, apiKey: "sk-new" });
  assert.equal(factoryCalls.length, 2, "a rotated key must rebuild the runtime");
  assert.equal(factoryCalls[1], "sk-new");
  assert.equal(rotated.restored, true);

  const bySnapshot = await pool.ensure("s-key", "u1");
  assert.equal(factoryCalls.length, 2, "a missing config must not rebuild a user-model runtime");
  assert.equal(bySnapshot.model?.id, "deepseek-chat");

  await pool.disposeAll();
});
