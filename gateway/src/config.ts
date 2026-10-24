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
  /** Allow private/loopback/HTTP model provider endpoints (ADR-0010). */
  allowPrivateModelEndpoints: boolean;
  /** Global compaction policy (FR-4); reserve is computed per model window. */
  compaction: CompactionConfig;
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
  return {
    port: intEnv("GATEWAY_PORT", 3001),
    javaApiUrl: process.env.JAVA_API_URL ?? "http://localhost:8080",
    sandboxApiUrl: process.env.SANDBOX_API_URL ?? "http://localhost:8080",
    apiToken: process.env.GATEWAY_API_TOKEN,
    sessionDir: resolve(process.env.GATEWAY_SESSION_DIR ?? "data/sessions"),
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
  };
}
