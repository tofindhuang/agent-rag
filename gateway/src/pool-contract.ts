import type { AgentSessionEvent } from "@earendil-works/pi-coding-agent";
import type { RunIdentity } from "./identity.js";

export interface GatewaySession {
  subscribe(listener: (event: AgentSessionEvent) => void): () => void;
  abort(): Promise<void>;
}

export interface GatewaySessionEntry {
  sessionId: string;
  userId: string;
  activeRun: RunIdentity | null;
  session: GatewaySession;
}

export interface EnsureSessionResult {
  created: boolean;
  restored: boolean;
}

/** The subset of SessionPool used by the HTTP layer (and by tests). */
export interface GatewayPool {
  readonly size: number;
  get(sessionId: string): GatewaySessionEntry | undefined;
  ensure(sessionId: string, userId: string): Promise<EnsureSessionResult>;
  beginRun(sessionId: string, identity: RunIdentity): GatewaySessionEntry | null;
  endRun(sessionId: string, runId: string): void;
  abort(sessionId: string): boolean;
  dispose(sessionId: string): Promise<boolean>;
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
