/**
 * AgentSession pool: one pi AgentSession per conversation.
 *
 * M0 skeleton: sessions live in this process only (in-memory SessionManager),
 * created on demand and kept until disposed. Horizontal scaling, idle
 * eviction, and cold-start restore are M2 (requirements.md FR-1).
 *
 * Lifecycle invariants:
 * - session IDs are owned by Java; the gateway never generates business IDs.
 * - PUT/ensure is idempotent and enforces one userId per session.
 * - at most one Run is active per session; beginRun is the atomic gate.
 */

import {
  createAgentSession,
  DefaultResourceLoader,
  getAgentDir,
  SessionManager,
} from "@earendil-works/pi-coding-agent";
import type { GatewayConfig } from "./config.js";
import { kbSearchExtension, runCodeExtension } from "./extensions.js";
import type { RunIdentity, RunRef } from "./identity.js";
import type { EnsureSessionResult, GatewayPool } from "./pool-contract.js";
import { SessionConflictError, SessionExistsError, SessionNotFoundError } from "./pool-contract.js";
import { buildSystemPrompt } from "./prompt.js";

type PiSession = Awaited<ReturnType<typeof createAgentSession>>["session"];

export interface PoolEntry {
  sessionId: string;
  userId: string;
  session: PiSession;
  createdAt: number;
  lastActiveAt: number;
  activeRun: RunIdentity | null;
  runRef: RunRef;
}

export class SessionPool implements GatewayPool {
  private readonly entries = new Map<string, PoolEntry>();
  private readonly pendingCreates = new Map<string, Promise<PoolEntry>>();

  constructor(private readonly config: GatewayConfig) {}

  get size(): number {
    return this.entries.size;
  }

  get(sessionId: string): PoolEntry | undefined {
    return this.entries.get(sessionId);
  }

  /**
   * Idempotent create used by PUT /sessions/:id. Concurrent calls for the same
   * session share one creation promise, so retries cannot create duplicates.
   */
  async ensure(sessionId: string, userId: string): Promise<EnsureSessionResult> {
    const existing = this.entries.get(sessionId);
    if (existing) {
      if (existing.userId !== userId) throw new SessionConflictError(sessionId);
      return { created: false, restored: false };
    }

    const inflight = this.pendingCreates.get(sessionId);
    if (inflight) {
      const entry = await inflight;
      if (entry.userId !== userId) throw new SessionConflictError(sessionId);
      return { created: false, restored: false };
    }

    const creation = this.create(sessionId, userId).finally(() => {
      this.pendingCreates.delete(sessionId);
    });
    this.pendingCreates.set(sessionId, creation);
    await creation;
    return { created: true, restored: false };
  }

  async create(sessionId: string, userId: string): Promise<PoolEntry> {
    const existing = this.entries.get(sessionId);
    if (existing) throw new SessionExistsError(sessionId);

    const runRef: RunRef = { current: null };
    const loader = new DefaultResourceLoader({
      cwd: process.cwd(),
      agentDir: getAgentDir(),
      // Replace pi's coding prompt entirely (requirements.md §1.1).
      systemPromptOverride: () => buildSystemPrompt({ userName: userId, profileFacts: [] }),
      // Long-term profile facts (FR-5) will be fetched from Java here in M1.
      appendSystemPromptOverride: () => [],
      extensionFactories: [
        kbSearchExtension(this.config, () => runRef.current),
        runCodeExtension(this.config, () => runRef.current),
      ],
    });
    await loader.reload();

    const { session } = await createAgentSession({
      resourceLoader: loader,
      // Only the extension-registered tools are active; every built-in tool
      // (bash/edit/write/read/...) is excluded (FR-6 tool whitelist).
      tools: ["kb_search", "run_code"],
      sessionManager: SessionManager.inMemory(),
    });

    const entry: PoolEntry = {
      sessionId,
      userId,
      session,
      createdAt: Date.now(),
      lastActiveAt: Date.now(),
      activeRun: null,
      runRef,
    };
    this.entries.set(sessionId, entry);
    return entry;
  }

  /** Atomically claim the single Run slot for a session. */
  beginRun(sessionId: string, identity: RunIdentity): PoolEntry | null {
    const entry = this.entries.get(sessionId);
    if (!entry || entry.activeRun) return null;
    entry.activeRun = identity;
    entry.runRef.current = identity;
    entry.lastActiveAt = Date.now();
    return entry;
  }

  endRun(sessionId: string, runId: string): void {
    const entry = this.entries.get(sessionId);
    if (!entry) return;
    if (entry.activeRun?.runId === runId) {
      entry.activeRun = null;
      entry.runRef.current = null;
    }
  }

  abort(sessionId: string): boolean {
    const entry = this.entries.get(sessionId);
    if (!entry || !entry.activeRun) return false;
    entry.session.abort().catch(() => undefined);
    return true;
  }

  /** Run a prompt; the caller must have claimed the Run slot via beginRun. */
  async run(sessionId: string, text: string): Promise<void> {
    const entry = this.entries.get(sessionId);
    if (!entry) throw new SessionNotFoundError(sessionId);
    entry.lastActiveAt = Date.now();
    await entry.session.prompt(text);
  }

  async dispose(sessionId: string): Promise<boolean> {
    const entry = this.entries.get(sessionId);
    if (!entry) return false;
    const wasRunning = entry.activeRun !== null;
    this.entries.delete(sessionId);
    entry.activeRun = null;
    entry.runRef.current = null;
    if (wasRunning) {
      await entry.session.abort().catch(() => undefined);
    }
    entry.session.dispose();
    return true;
  }

  async disposeAll(): Promise<void> {
    await Promise.allSettled([...this.entries.keys()].map((id) => this.dispose(id)));
  }
}

export { SessionConflictError, SessionExistsError, SessionNotFoundError } from "./pool-contract.js";
