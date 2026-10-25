/**
 * agent-core gateway entrypoint. HTTP behavior lives in app.ts; this file owns
 * process startup, the session pool, the instance registry and graceful
 * shutdown.
 */

import { createApp } from "./app.js";
import { loadConfig } from "./config.js";
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
const app = createApp({ config, pool, lifecycle: registry });

const server = app.listen(config.port, () => {
  process.stdout.write(`gateway listening on :${config.port}\n`);
});

if (registry) {
  void registry.start();
}

let shuttingDown = false;
async function shutdown(): Promise<void> {
  if (shuttingDown) return;
  shuttingDown = true;
  if (registry) await registry.markDraining();
  server.close();
  await pool.disposeAll();
  if (registry) await registry.stop();
  process.exit(0);
}
process.on("SIGINT", () => void shutdown());
process.on("SIGTERM", () => void shutdown());
