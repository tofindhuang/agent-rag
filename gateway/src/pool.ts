/**
 * AgentSession pool: one pi AgentSession per conversation.
 *
 * M0 skeleton: sessions live in this process only (in-memory SessionManager),
 * created on demand and kept until disposed. Horizontal scaling, idle
 * eviction, and cold-start restore are M2 (requirements.md FR-1).
 *
 * Prompts on the same session are serialized through a promise queue because
 * session.prompt() rejects when a run is already streaming.
 */

import {
  createAgentSession,
  DefaultResourceLoader,
  getAgentDir,
  SessionManager,
} from "@earendil-works/pi-coding-agent";
import type { GatewayConfig } from "./config.js";
import { kbSearchExtension, runCodeExtension } from "./extensions.js";
import { buildSystemPrompt } from "./prompt.js";

type PiSession = Awaited<ReturnType<typeof createAgentSession>>["session"];

export interface PoolEntry {
  sessionId: string;
  userId: string;
  session: PiSession;
  createdAt: number;
  lastActiveAt: number;
  queue: Promise<unknown>;
}

export class SessionPool {
  private readonly entries = new Map<string, PoolEntry>();

  constructor(private readonly config: GatewayConfig) {}

  get size(): number {
    return this.entries.size;
  }

  get(sessionId: string): PoolEntry | undefined {
    return this.entries.get(sessionId);
  }

  async create(sessionId: string, userId: string): Promise<PoolEntry> {
    const existing = this.entries.get(sessionId);
    if (existing) throw new SessionExistsError(sessionId);

    const loader = new DefaultResourceLoader({
      cwd: process.cwd(),
      agentDir: getAgentDir(),
      // Replace pi's coding prompt entirely (requirements.md §1.1).
      systemPromptOverride: () => buildSystemPrompt({ userName: userId, profileFacts: [] }),
      // Long-term profile facts (FR-5) will be fetched from Java here in M1.
      appendSystemPromptOverride: () => [],
      extensionFactories: [kbSearchExtension(this.config), runCodeExtension(this.config)],
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
      queue: Promise.resolve(),
    };
    this.entries.set(sessionId, entry);
    return entry;
  }

  /**
   * Enqueue a prompt on the session's serialized queue and resolve when the
   * run finishes (including automatic retries).
   */
  async run(sessionId: string, text: string): Promise<void> {
    const entry = this.entries.get(sessionId);
    if (!entry) throw new SessionNotFoundError(sessionId);
    entry.lastActiveAt = Date.now();
    const run = entry.queue.then(() => entry.session.prompt(text));
    entry.queue = run.catch(() => undefined);
    await run;
  }

  async dispose(sessionId: string): Promise<boolean> {
    const entry = this.entries.get(sessionId);
    if (!entry) return false;
    this.entries.delete(sessionId);
    await entry.queue.catch(() => undefined);
    entry.session.dispose();
    return true;
  }

  async disposeAll(): Promise<void> {
    await Promise.allSettled([...this.entries.keys()].map((id) => this.dispose(id)));
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
