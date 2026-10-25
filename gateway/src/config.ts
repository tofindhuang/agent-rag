import { randomUUID } from "node:crypto";
import { hostname } from "node:os";
import { resolve } from "node:path";
import type { CompactionConfig } from "./compaction.js";

/**
 * Gateway runtime configuration. All values come from environment variables
 * with localhost defaults for development.
 */

export interface GatewayConfig {
  /** HTTP listen port of this gateway. */
  port: number;
  /** Java business-layer base URL, called by the kb_search tool. */
  javaApiUrl: string;
  /** Sandbox execution service base URL, called by the run_code tool. */
  sandboxApiUrl: string;
  /** Optional shared token; when set, requests must carry it as `authorization: Bearer <token>`. */
  apiToken?: string;
  /** Directory holding pi session files and ownership sidecars (ADR-0008). */
  sessionDir: string;
  /** Stable identity of this gateway instance (ADR-0024). */
  instanceId: string;
  /** Process-incarnation token; distinguishes restarts that reuse instanceId. */
  instanceIncarnation: string;
  /** Public address Java uses to reach this instance (ADR-0024). */
  publicUrl: string;
  /** Soft cap on resident sessions per instance (ADR-0024). */
  maxSessions: number;
  /** Idle time after which a resident AgentSession is released. */
  idleEvictMs: number;
  /** Grace period for in-flight runs during drain (ADR-0024). */
  drainGraceMs: number;
  /** Redis URL for the instance registry; unset disables registration. */
  redisUrl?: string;
  registryHeartbeatMs: number;
  registryTtlSeconds: number;
  /** Single-writer lock heartbeat and takeover threshold (ADR-0024). */
  lockHeartbeatMs: number;
  lockStaleMs: number;
  /** Allow private/loopback/HTTP model provider endpoints (ADR-0010). */
  allowPrivateModelEndpoints: boolean;
  /** Global compaction policy (FR-4); reserve is computed per model window. */
  compaction: CompactionConfig;
  /** Profile extraction throttles and transcript bounds (FR-5). */
  profileExtractMinIntervalMs: number;
  profileExtractMaxMessages: number;
  profileExtractMaxChars: number;
  profileExtractMaxCandidates: number;
}

function floatEnv(name: string, fallback: number): number {
  const raw = process.env[name];
  if (!raw) return fallback;
  const parsed = Number.parseFloat(raw);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function intEnv(name: string, fallback: number): number {
  const raw = process.env[name];
  if (!raw) return fallback;
  const parsed = Number.parseInt(raw, 10);
  return Number.isFinite(parsed) ? parsed : fallback;
}

export function loadConfig(): GatewayConfig {
  const port = intEnv("GATEWAY_PORT", 3001);
  return {
    port,
    javaApiUrl: process.env.JAVA_API_URL ?? "http://localhost:8080",
    sandboxApiUrl: process.env.SANDBOX_API_URL ?? "http://localhost:8080",
    apiToken: process.env.GATEWAY_API_TOKEN,
    sessionDir: resolve(process.env.GATEWAY_SESSION_DIR ?? "data/sessions"),
    instanceId: process.env.GATEWAY_INSTANCE_ID?.trim() || `${hostname()}:${port}`,
    instanceIncarnation: randomUUID(),
    publicUrl: process.env.GATEWAY_PUBLIC_URL?.trim() || `http://127.0.0.1:${port}`,
    maxSessions: Math.max(1, intEnv("GATEWAY_MAX_SESSIONS", 60)),
    idleEvictMs: Math.max(0, intEnv("GATEWAY_IDLE_EVICT_MS", 900_000)),
    drainGraceMs: Math.max(0, intEnv("GATEWAY_DRAIN_GRACE_MS", 600_000)),
    redisUrl: process.env.REDIS_URL?.trim() || undefined,
    registryHeartbeatMs: Math.max(10, intEnv("GATEWAY_REGISTRY_HEARTBEAT_MS", 5_000)),
    registryTtlSeconds: Math.max(1, intEnv("GATEWAY_REGISTRY_TTL_SECONDS", 15)),
    lockHeartbeatMs: Math.max(10, intEnv("GATEWAY_LOCK_HEARTBEAT_MS", 5_000)),
    lockStaleMs: Math.max(20, intEnv("GATEWAY_LOCK_STALE_MS", 30_000)),
    allowPrivateModelEndpoints: process.env.MODEL_ALLOW_PRIVATE_ENDPOINTS === "true",
    compaction: {
      enabled: process.env.COMPACTION_ENABLED !== "false",
      reserveRatio: Math.min(
        0.5,
        Math.max(0.05, floatEnv("COMPACTION_RESERVE_RATIO", 0.2)),
      ),
      keepRecentTokens: Math.max(
        1,
        intEnv("COMPACTION_KEEP_RECENT_TOKENS", 20_000),
      ),
    },
    profileExtractMinIntervalMs: Math.max(
      0,
      intEnv("PROFILE_EXTRACT_MIN_INTERVAL_MS", 60_000),
    ),
    profileExtractMaxMessages: Math.max(
      1,
      intEnv("PROFILE_EXTRACT_MAX_MESSAGES", 30),
    ),
    profileExtractMaxChars: Math.max(
      1,
      intEnv("PROFILE_EXTRACT_MAX_CHARS", 6_000),
    ),
    profileExtractMaxCandidates: Math.max(
      1,
      intEnv("PROFILE_EXTRACT_MAX_CANDIDATES", 5),
    ),
  };
}
