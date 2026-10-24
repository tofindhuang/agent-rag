/**
 * AgentSession pool: one pi AgentSession per conversation.
 *
 * M1: pi sessions are file-backed under `GATEWAY_SESSION_DIR` (ADR-0008), so a
 * gateway restart can restore the engine context and its owning user. Horizontal
 * scaling and idle eviction remain M2.
 *
 * Lifecycle invariants:
 * - session IDs are owned by Java; the gateway never generates business IDs.
 * - PUT/ensure is idempotent, restores existing files, and enforces one userId
 *   per session via a sidecar.
 * - at most one Run is active per session; beginRun is the atomic gate.
 */

import {
  createAgentSession,
  DefaultResourceLoader,
  getAgentDir,
  SessionManager,
  SettingsManager,
} from "@earendil-works/pi-coding-agent";
import type { GatewayConfig } from "./config.js";
import {
  applyCompactionSettings,
  buildCompactionPlan,
  type CompactionPlan,
} from "./compaction.js";
import { kbSearchExtension, runCodeExtension } from "./extensions.js";
import { profileExtractionExtension, profileFactsExtension } from "./profile.js";
import type { RunIdentity, RunRef } from "./identity.js";
import type {
  EnsureSessionResult,
  GatewayPool,
  ModelRef,
  ProfileInput,
  ProfileRef,
  SessionModelConfig,
} from "./pool-contract.js";
import { SessionConflictError, SessionExistsError, SessionNotFoundError } from "./pool-contract.js";
import { buildSystemPrompt } from "./prompt.js";
import { SessionFileStore, type SessionLease } from "./session-store.js";
import {
  buildSessionModel,
  modelRefFromConfig,
  modelRefFromSession,
} from "./session-model.js";

export type PiSession = Awaited<ReturnType<typeof createAgentSession>>["session"];

/** Injectable for tests; production builds a real pi AgentSession. */
/** FR-6: only these extension tools are exposed to the model. */
export const AGENT_TOOL_WHITELIST = ["kb_search", "run_code"] as const;

export type SessionFactory = (
  manager: SessionManager,
  runRef: RunRef,
  userId: string,
  modelConfig: SessionModelConfig | undefined,
  profileRef: ProfileRef,
) => Promise<PiSession>;

export interface PoolEntry {
  sessionId: string;
  userId: string;
  session: PiSession;
  createdAt: number;
  lastActiveAt: number;
  activeRun: RunIdentity | null;
  runRef: RunRef;
  model?: ModelRef;
  /** Last model configuration applied to this runtime (credentials included). */
  appliedModel?: SessionModelConfig;
  /** Compaction policy computed for the session's model. */
  compaction: CompactionPlan;
  /** Last provider-reported context size; null until the next assistant turn. */
  lastContextTokens: number | null;
  profileRef: ProfileRef;
}

interface EnsureOutcome {
  entry: PoolEntry;
  created: boolean;
  restored: boolean;
}

export class SessionPool implements GatewayPool {
  private readonly entries = new Map<string, PoolEntry>();
  private readonly pendingCreates = new Map<string, Promise<EnsureOutcome>>();
  private readonly store: SessionFileStore;
  private readonly factory: SessionFactory;

  constructor(
    private readonly config: GatewayConfig,
    factory?: SessionFactory,
  ) {
    this.store = new SessionFileStore(process.cwd(), config.sessionDir);
    this.factory = factory ?? ((manager, runRef, userId, modelConfig, profileRef) =>
      this.createPiSession(manager, runRef, userId, modelConfig, profileRef));
  }

  get size(): number {
    return this.entries.size;
  }

  get(sessionId: string): PoolEntry | undefined {
    return this.entries.get(sessionId);
  }

  /**
   * Idempotent create/restore used by PUT /sessions/:id. Concurrent calls for
   * the same session share one creation promise, so retries cannot duplicate
   * sessions.
   */
  async ensure(
    sessionId: string,
    userId: string,
    modelConfig?: SessionModelConfig,
    profile?: ProfileInput,
  ): Promise<EnsureSessionResult> {
    const existing = this.entries.get(sessionId);
    if (existing) {
      if (existing.userId !== userId) throw new SessionConflictError(sessionId);
      if (profile) {
        if (profile.facts !== undefined) existing.profileRef.facts = profile.facts;
        if (profile.enabled !== undefined) existing.profileRef.enabled = profile.enabled;
      }
      // No incoming config means "keep the resident runtime's model": only an
      // explicit, different config justifies a rebuild.
      if (!modelConfig || sameModelConfig(existing.appliedModel, modelConfig)) {
        return ensureResult(false, false, existing.model);
      }
      // Provider credentials/endpoint changed while the session is resident
      // (e.g. an edited API key). Rebuild now so the next turn uses them
      // instead of silently keeping the runtime built from the old config.
      await this.dispose(sessionId);
    }

    const inflight = this.pendingCreates.get(sessionId);
    if (inflight) {
      const outcome = await inflight;
      if (outcome.entry.userId !== userId) throw new SessionConflictError(sessionId);
      return ensureResult(false, false, outcome.entry.model);
    }

    const creation = this.createEntry(sessionId, userId, modelConfig, profile).finally(() => {
      this.pendingCreates.delete(sessionId);
    });
    this.pendingCreates.set(sessionId, creation);

    const outcome = await creation;
    return ensureResult(outcome.created, outcome.restored, outcome.entry.model);
  }

  private async createEntry(
    sessionId: string,
    userId: string,
    modelConfig?: SessionModelConfig,
    profile?: ProfileInput,
  ): Promise<EnsureOutcome> {
    if (this.entries.has(sessionId)) throw new SessionExistsError(sessionId);

    const lease: SessionLease = this.store.acquire(sessionId, userId);
    const runRef: RunRef = { current: null };
    const profileRef: ProfileRef = {
      facts: profile?.facts ?? [],
      enabled: profile?.enabled ?? true,
      lastExtractAt: 0,
    };
    const session = await this.factory(lease.manager, runRef, userId, modelConfig, profileRef);

    const now = Date.now();
    const entry: PoolEntry = {
      sessionId,
      userId,
      session,
      createdAt: now,
      lastActiveAt: now,
      activeRun: null,
      runRef,
      model:
        modelRefFromSession(session.model) ??
        (modelConfig ? modelRefFromConfig(modelConfig) : undefined),
      appliedModel: modelConfig,
      compaction: buildCompactionPlan(session.model?.contextWindow, this.config.compaction),
      lastContextTokens: null,
      profileRef,
    };
    this.entries.set(sessionId, entry);
    return { entry, created: lease.created, restored: lease.restored };
  }

  protected async createPiSession(
    manager: SessionManager,
    runRef: RunRef,
    userId: string,
    modelConfig: SessionModelConfig | undefined,
    profileRef: ProfileRef,
  ): Promise<PiSession> {
    const settingsManager = SettingsManager.create(process.cwd(), getAgentDir());
    const sessionRef: { current?: PiSession } = {};
    const loader = new DefaultResourceLoader({
      cwd: process.cwd(),
      agentDir: getAgentDir(),
      settingsManager,
      // Replace pi's coding prompt entirely (requirements.md §1.1).
      systemPromptOverride: () =>
        buildSystemPrompt({ userName: userId }),
      // Long-term profile facts (FR-5) will be fetched from Java here in M1.
      appendSystemPromptOverride: () => [],
      extensionFactories: [
        kbSearchExtension(this.config, () => runRef.current),
        runCodeExtension(this.config, () => runRef.current),
        profileFactsExtension(profileRef),
        profileExtractionExtension(
          this.config,
          () => runRef.current,
          profileRef,
          () => sessionRef.current,
        ),
      ],
    });
    await loader.reload();

    const built = modelConfig
      ? await buildSessionModel(modelConfig, {
          allowPrivate: this.config.allowPrivateModelEndpoints,
        })
      : undefined;
    const { session } = await createAgentSession({
      resourceLoader: loader,
      // Only the extension-registered tools are active; every built-in tool
      // (bash/edit/write/read/...) is excluded (FR-6 tool whitelist).
      tools: [...AGENT_TOOL_WHITELIST],
      sessionManager: manager,
      settingsManager,
      ...(built ? { modelRuntime: built.runtime, model: built.model } : {}),
    });
    applyCompactionSettings(
      settingsManager,
      session.model?.contextWindow,
      this.config.compaction,
    );
    sessionRef.current = session;
    return session;
  }

  /** Atomically claim the single Run slot for a session. */
  beginRun(sessionId: string, identity: RunIdentity): PoolEntry | null {
    const entry = this.entries.get(sessionId);
    if (!entry || entry.activeRun) return null;
    // pi reports null after a between-runs compaction; the previous provider
    // count is no longer a valid description of the current context.
    const estimate = entry.session.getContextUsage?.();
    if (estimate && estimate.tokens === null) {
      entry.lastContextTokens = null;
    }
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

  /**
   * Releases the in-memory AgentSession only. The pi session file and its
   * sidecar stay on disk (ADR-0008: M1 keeps files; DELETE must not wipe the
   * engine context).
   */
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

  removeFiles(sessionId: string, userId: string): void {
    this.store.remove(sessionId, userId);
  }

  async disposeAll(): Promise<void> {
    await Promise.allSettled([...this.entries.keys()].map((id) => this.dispose(id)));
  }
}

function ensureResult(
  created: boolean,
  restored: boolean,
  model?: ModelRef,
): EnsureSessionResult {
  return model ? { created, restored, model } : { created, restored };
}

/** Compares every field that changes the built runtime (including secrets). */
function sameModelConfig(
  applied: SessionModelConfig | undefined,
  incoming: SessionModelConfig | undefined,
): boolean {
  if (applied === undefined || incoming === undefined) {
    return applied === incoming;
  }
  return (
    applied.providerId === incoming.providerId &&
    applied.name === incoming.name &&
    applied.api === incoming.api &&
    applied.baseUrl === incoming.baseUrl &&
    applied.apiKey === incoming.apiKey &&
    applied.modelId === incoming.modelId &&
    applied.displayName === incoming.displayName &&
    applied.contextWindow === incoming.contextWindow &&
    applied.reasoning === incoming.reasoning &&
    applied.vision === incoming.vision
  );
}

export { SessionConflictError, SessionExistsError, SessionNotFoundError } from "./pool-contract.js";
