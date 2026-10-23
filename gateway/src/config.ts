import { resolve } from "node:path";

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
  };
}
