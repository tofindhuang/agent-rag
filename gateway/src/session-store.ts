import { existsSync, mkdirSync, readFileSync, renameSync, unlinkSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { SessionManager } from "@earendil-works/pi-coding-agent";
import { logWarn } from "./log.js";
import {
  InvalidSessionIdError,
  SessionConflictError,
  SessionStateCorruptError,
} from "./pool-contract.js";
import { SessionLock } from "./session-lock.js";

export interface SessionLease {
  manager: SessionManager;
  created: boolean;
  restored: boolean;
  /** Held for as long as the AgentSession is resident. */
  lock: SessionLock;
}

export interface SessionStoreOptions {
  /** Stable gateway instance identity; used by the single-writer lock. */
  instanceId?: string;
  lockHeartbeatMs?: number;
  lockStaleMs?: number;
  isInstanceAlive?: (instanceId: string) => boolean | undefined;
}

interface SidecarMeta {
  userId: string;
  /** pi session cwd; restore must use the same value pi wrote into the header. */
  cwd: string;
  boundAt: string;
}

type SidecarRead =
  | { state: "missing" }
  | { state: "ok"; meta: SidecarMeta }
  | { state: "invalid"; reason: string };

const SESSION_ID_PATTERN = /^[A-Za-z0-9_-]{1,128}$/;
const DEFAULT_INSTANCE_ID = "local";

/**
 * File-backed pi session storage (ADR-0008). pi keeps the engine context;
 * a sidecar `<id>.meta.json` keeps the owning user so ownership survives a
 * gateway restart.
 *
 * ADR-0024 adds a single-writer lock held for the resident lifetime and makes a
 * missing sidecar fail closed instead of silently rebinding ownership.
 */
export class SessionFileStore {
  private readonly instanceId: string;

  constructor(
    private readonly cwd: string,
    private readonly sessionDir: string,
    private readonly options: SessionStoreOptions = {},
  ) {
    mkdirSync(this.sessionDir, { recursive: true });
    this.instanceId = options.instanceId ?? DEFAULT_INSTANCE_ID;
  }

  /** Creates a new session file or restores the existing one. */
  acquire(sessionId: string, userId: string, requireExisting = false): SessionLease {
    if (!SESSION_ID_PATTERN.test(sessionId)) {
      throw new InvalidSessionIdError(sessionId);
    }

    const lock = this.newLock(sessionId);
    lock.acquire();

    try {
      // Ownership is checked even before pi has flushed a file (pi only writes
      // once the session contains a conversation message).
      const sidecar = this.readSidecar(sessionId);
      if (sidecar.state === "invalid") {
        throw new SessionStateCorruptError(sessionId, sidecar.reason);
      }
      if (sidecar.state === "ok" && sidecar.meta.userId !== userId) {
        throw new SessionConflictError(sessionId);
      }

      // Restore with the cwd recorded at creation, so a gateway restarted from a
      // different directory still finds (and must not fork) the same session.
      const cwd = sidecar.state === "ok" ? sidecar.meta.cwd : this.cwd;
      const existingPath = SessionManager.findById(cwd, sessionId, this.sessionDir);

      if (sidecar.state === "missing" && existingPath) {
        throw new SessionStateCorruptError(sessionId, "session sidecar is missing");
      }

      if (!existingPath) {
        if (requireExisting) {
          throw new SessionStateCorruptError(sessionId, "session context is missing");
        }
        const manager = SessionManager.create(cwd, this.sessionDir, { id: sessionId });
        if (sidecar.state === "missing") {
          this.writeSidecar(sessionId, userId, cwd);
        }
        return { manager, created: true, restored: false, lock };
      }

      return {
        manager: SessionManager.open(existingPath, this.sessionDir, cwd),
        created: false,
        restored: true,
        lock,
      };
    } catch (err) {
      lock.release();
      throw err;
    }
  }

  private newLock(sessionId: string): SessionLock {
    return new SessionLock(this.sessionDir, sessionId, {
      instanceId: this.instanceId,
      heartbeatMs: this.options.lockHeartbeatMs,
      staleMs: this.options.lockStaleMs,
      isInstanceAlive: this.options.isInstanceAlive,
    });
  }

  private sidecarPath(sessionId: string): string {
    return join(this.sessionDir, `${sessionId}.meta.json`);
  }

  /**
   * Deletes the pi JSONL and its ownership sidecar (ADR-0011). Idempotent.
   *
   * Ownership is proven by the sidecar; a file without one is refused, because
   * a delete cannot be undone and the owner is no longer knowable. The session
   * lock must be free, so a resident session cannot be purged underneath a
   * running gateway.
   */
  remove(sessionId: string, userId: string): void {
    if (!SESSION_ID_PATTERN.test(sessionId)) {
      throw new InvalidSessionIdError(sessionId);
    }

    const lock = this.newLock(sessionId);
    lock.acquire();
    try {
      const sidecar = this.readSidecar(sessionId);
      if (sidecar.state === "invalid") {
        throw new SessionStateCorruptError(sessionId, sidecar.reason);
      }
      if (sidecar.state === "ok" && sidecar.meta.userId !== userId) {
        throw new SessionConflictError(sessionId);
      }

      const cwd = sidecar.state === "ok" ? sidecar.meta.cwd : this.cwd;
      const file = SessionManager.findById(cwd, sessionId, this.sessionDir);
      if (sidecar.state === "missing" && file) {
        throw new SessionStateCorruptError(sessionId, "session sidecar is missing");
      }

      if (file) unlinkSync(file);
      const sidecarPath = this.sidecarPath(sessionId);
      if (existsSync(sidecarPath)) unlinkSync(sidecarPath);
    } finally {
      lock.release();
    }
  }

  private readSidecar(sessionId: string): SidecarRead {
    const path = this.sidecarPath(sessionId);
    if (!existsSync(path)) return { state: "missing" };
    try {
      const parsed: unknown = JSON.parse(readFileSync(path, "utf8"));
      if (typeof parsed !== "object" || parsed === null) {
        logWarn("session sidecar is not an object", { sessionId });
        return { state: "invalid", reason: "session sidecar is not an object" };
      }
      const meta = parsed as Partial<SidecarMeta>;
      if (typeof meta.userId !== "string" || meta.userId.length === 0) {
        logWarn("session sidecar has no userId", { sessionId });
        return { state: "invalid", reason: "session sidecar has no userId" };
      }
      if (typeof meta.cwd !== "string" || meta.cwd.length === 0) {
        logWarn("session sidecar has no cwd", { sessionId });
        return { state: "invalid", reason: "session sidecar has no cwd" };
      }
      return {
        state: "ok",
        meta: {
          userId: meta.userId,
          cwd: meta.cwd,
          boundAt: typeof meta.boundAt === "string" ? meta.boundAt : "",
        },
      };
    } catch (err) {
      logWarn("unreadable session sidecar", {
        sessionId,
        error: (err as Error).message,
      });
      return { state: "invalid", reason: "session sidecar is unreadable" };
    }
  }

  private writeSidecar(sessionId: string, userId: string, cwd: string): void {
    const path = this.sidecarPath(sessionId);
    const tmp = `${path}.tmp`;
    const meta: SidecarMeta = { userId, cwd, boundAt: new Date().toISOString() };
    writeFileSync(tmp, JSON.stringify(meta), "utf8");
    renameSync(tmp, path);
  }
}
