/**
 * agent-core gateway entrypoint. HTTP behavior lives in app.ts; this file owns
 * process startup, the session pool, the instance registry and graceful
 * shutdown (drain, admission barrier, in-flight grace, then exit).
 */

import { createApp } from "./app.js";
import { loadConfig } from "./config.js";
import { logInfo, logWarn } from "./log.js";
import { SessionPool } from "./pool.js";
import { HttpArchiveTransport } from "./archive.js";
import { DrainGate } from "./drain-gate.js";
import { RedisInstanceRegistry, RedisRegistryStore } from "./registry.js";

const config = loadConfig();
const gate = new DrainGate();
let pool: SessionPool;
const registry = config.redisUrl
  ? new RedisInstanceRegistry({
      instanceId: config.instanceId,
      incarnation: config.instanceIncarnation,
      publicUrl: config.publicUrl,
      maxSessions: config.maxSessions,
      sessionCount: () => pool.size,
      store: new RedisRegistryStore(config.redisUrl),
      heartbeatMs: config.registryHeartbeatMs,
      ttlSeconds: config.registryTtlSeconds,
    })
  : undefined;

pool = new SessionPool(config, undefined, {
  isInstanceAlive: (instanceId, incarnation) =>
    registry?.isInstanceAlive(instanceId, incarnation),
  onEvict: (sessionId) => void registry?.deleteSessionRoute(sessionId),
  onSessionCountChange: () => void registry?.publishNow(),
  archiveTransport: new HttpArchiveTransport(config),
});

let draining = false;

const lifecycle = {
  isDraining: () => draining || gate.isDraining() || registry?.isDraining() === true,
  beginDrain: () => void beginDrain(),
};

const app = createApp({ config, pool, lifecycle, gate });

const server = app.listen(config.port, () => {
  process.stdout.write(`gateway listening on :${config.port}\n`);
});

if (registry) {
  void registry.start();
}

async function closeServer(): Promise<void> {
  await new Promise<void>((resolve) => {
    const timer = setTimeout(() => {
      server.closeAllConnections?.();
      resolve();
    }, 5_000);
    timer.unref?.();
    server.close(() => {
      clearTimeout(timer);
      resolve();
    });
    server.closeIdleConnections?.();
  });
}

async function beginDrain(): Promise<void> {
  if (draining) return;
  draining = true;
  gate.beginDrain();
  logInfo("gateway draining", {
    instanceId: config.instanceId,
    graceMs: config.drainGraceMs,
  });
  if (registry) await registry.markDraining();

  const started = Date.now();
  const zero = await gate.waitForZero(config.drainGraceMs);
  const remaining = Math.max(0, config.drainGraceMs - (Date.now() - started));
  const idle = await pool.waitForIdle(remaining);
  logInfo("gateway drain complete", {
    instanceId: config.instanceId,
    zero,
    idle,
    inflight: gate.inflightCount(),
  });
  if (!zero || !idle) {
    logWarn("drain grace expired; aborting remaining work", {
      instanceId: config.instanceId,
      inflight: gate.inflightCount(),
      idle,
    });
  }

  await closeServer();
  await pool.disposeAll();
  if (registry) await registry.stop();
  process.exit(0);
}

process.on("SIGINT", () => void beginDrain());
process.on("SIGTERM", () => void beginDrain());
