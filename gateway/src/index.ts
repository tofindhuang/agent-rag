/**
 * agent-core gateway entrypoint. HTTP behavior lives in app.ts; this file owns
 * process startup, the session pool, the instance registry and graceful
 * shutdown (drain, in-flight grace, then exit).
 */

import { createApp } from "./app.js";
import { loadConfig } from "./config.js";
import { logInfo } from "./log.js";
import { SessionPool } from "./pool.js";
import { RedisInstanceRegistry, RedisRegistryStore } from "./registry.js";

const config = loadConfig();
let pool: SessionPool;
const registry = config.redisUrl
  ? new RedisInstanceRegistry({
      instanceId: config.instanceId,
      publicUrl: config.publicUrl,
      maxSessions: config.maxSessions,
      sessionCount: () => pool.size,
      store: new RedisRegistryStore(config.redisUrl),
      heartbeatMs: config.registryHeartbeatMs,
      ttlSeconds: config.registryTtlSeconds,
    })
  : undefined;

pool = new SessionPool(config, undefined, {
  isInstanceAlive: (instanceId) => registry?.isInstanceAlive(instanceId),
  onEvict: (sessionId) => void registry?.deleteSessionRoute(sessionId),
});

let draining = false;
let shuttingDown = false;

const lifecycle = {
  isDraining: () => draining || registry?.isDraining() === true,
  beginDrain: () => void beginDrain(),
};

const app = createApp({ config, pool, lifecycle });

const server = app.listen(config.port, () => {
  process.stdout.write(`gateway listening on :${config.port}\n`);
});

if (registry) {
  void registry.start();
}

async function shutdown(): Promise<void> {
  if (shuttingDown) return;
  shuttingDown = true;
  server.close();
  await pool.disposeAll();
  if (registry) await registry.stop();
  process.exit(0);
}

async function beginDrain(): Promise<void> {
  if (draining) return;
  draining = true;
  logInfo("gateway draining", {
    instanceId: config.instanceId,
    graceMs: config.drainGraceMs,
  });
  if (registry) await registry.markDraining();
  const idle = await pool.waitForIdle(config.drainGraceMs);
  logInfo("gateway drain complete", { instanceId: config.instanceId, idle });
  if (!idle) {
    process.stdout.write(
      JSON.stringify({ level: "warn", msg: "drain grace expired; aborting active runs" }) + "\n",
    );
  }
  await shutdown();
}

process.on("SIGINT", () => void beginDrain());
process.on("SIGTERM", () => void beginDrain());
