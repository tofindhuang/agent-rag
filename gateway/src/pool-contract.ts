import type { AgentSessionEvent } from "@earendil-works/pi-coding-agent";
import type { RunIdentity } from "./identity.js";
import type { CompactionPlan } from "./compaction.js";

export interface GatewaySession {
  subscribe(listener: (event: AgentSessionEvent) => void): () => void;
  abort(): Promise<void>;
  getContextUsage?():
    | { tokens: number | null; contextWindow: number; percent: number | null }
    | undefined;
}

export interface GatewaySessionEntry {
  sessionId: string;
  userId: string;
  activeRun: RunIdentity | null;
  session: GatewaySession;
  /** Compaction policy computed for this session's model. */
  compaction?: CompactionPlan;
}

import type { ProviderApi } from "./providers.js";

export type { ProviderApi };

/** Per-session model configuration pushed by Java at ensure time. */
export interface SessionModelConfig {
  providerId?: string | number;
  name?: string;
  api: ProviderApi;
  baseUrl: string;
  apiKey: string;
  modelId: string;
  displayName?: string;
  contextWindow?: number;
  reasoning?: boolean;
  vision?: boolean;
}

export interface ModelRef {
  provider: string;
  id: string;
  name: string;
}

export interface EnsureSessionResult {
  created: boolean;
  restored: boolean;
  /** Actual model of the session; absent when the engine reports none. */
  model?: ModelRef;
}

/** The subset of SessionPool used by the HTTP layer (and by tests). */
export interface GatewayPool {
  readonly size: number;
  get(sessionId: string): GatewaySessionEntry | undefined;
  ensure(
    sessionId: string,
    userId: string,
    model?: SessionModelConfig,
  ): Promise<EnsureSessionResult>;
  beginRun(sessionId: string, identity: RunIdentity): GatewaySessionEntry | null;
  endRun(sessionId: string, runId: string): void;
  abort(sessionId: string): boolean;
  dispose(sessionId: string): Promise<boolean>;
  /** Deletes the pi file and sidecar; ownership-checked and idempotent. */
  removeFiles(sessionId: string, userId: string): void;
  run(sessionId: string, text: string): Promise<void>;
}

export class InvalidSessionIdError extends Error {
  constructor(sessionId: string) {
    super(`invalid session id: ${sessionId}`);
  }
}

export class SessionExistsError extends Error {
  constructor(sessionId: string) {
    super(`session already exists: ${sessionId}`);
  }
}

export class SessionNotFoundError extends Error {
  constructor(sessionId: string) {
    super(`session not found: ${sessionId}`);
  }
}

export class SessionConflictError extends Error {
  constructor(sessionId: string) {
    super(`session belongs to another user: ${sessionId}`);
  }
}
