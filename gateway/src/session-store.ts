import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { SessionManager } from "@earendil-works/pi-coding-agent";
import { logWarn } from "./log.js";
import { InvalidSessionIdError, SessionConflictError } from "./pool-contract.js";

export interface SessionLease {
  manager: SessionManager;
  created: boolean;
  restored: boolean;
}

interface SidecarMeta {
  userId: string;
  /** pi session cwd; restore must use the same value pi wrote into the header. */
  cwd: string;
  boundAt: string;
}

const SESSION_ID_PATTERN = /^[A-Za-z0-9_-]{1,128}$/;

/**
 * File-backed pi session storage (ADR-0008). pi keeps the engine context;
 * a sidecar `<id>.meta.json` keeps the owning user so ownership survives a
 * gateway restart.
 */
export class SessionFileStore {
  constructor(
    private readonly cwd: string,
    private readonly sessionDir: string,
  ) {
    mkdirSync(this.sessionDir, { recursive: true });
  }

  /** Creates a new session file or restores the existing one. */
  acquire(sessionId: string, userId: string): SessionLease {
    if (!SESSION_ID_PATTERN.test(sessionId)) {
      throw new InvalidSessionIdError(sessionId);
    }

    // Ownership is checked even before pi has flushed a file (pi only writes
    // once the session contains a conversation message).
    const meta = this.readSidecar(sessionId);
    if (meta && meta.userId !== userId) {
      throw new SessionConflictError(sessionId);
    }

    // Restore with the cwd recorded at creation, so a gateway restarted from a
    // different directory still finds (and must not fork) the same session.
    const cwd = meta?.cwd ?? this.cwd;
    const existingPath = SessionManager.findById(cwd, sessionId, this.sessionDir);
    if (!existingPath) {
      const manager = SessionManager.create(cwd, this.sessionDir, { id: sessionId });
      this.writeSidecar(sessionId, userId, cwd);
      return { manager, created: true, restored: false };
    }

    if (!meta) {
      logWarn("session sidecar missing; rebinding owner", { sessionId, userId });
      this.writeSidecar(sessionId, userId, cwd);
    }
    return {
      manager: SessionManager.open(existingPath, this.sessionDir, cwd),
      created: false,
      restored: true,
    };
  }

  private sidecarPath(sessionId: string): string {
    return join(this.sessionDir, `${sessionId}.meta.json`);
  }

  private readSidecar(sessionId: string): Partial<SidecarMeta> | undefined {
    const path = this.sidecarPath(sessionId);
    if (!existsSync(path)) return undefined;
    try {
      const parsed: unknown = JSON.parse(readFileSync(path, "utf8"));
      if (typeof parsed !== "object" || parsed === null) {
        logWarn("sidecar is not an object; rebinding owner", { sessionId });
        return undefined;
      }
      const meta = parsed as Partial<SidecarMeta>;
      if (typeof meta.userId !== "string" || meta.userId.length === 0) {
        logWarn("sidecar has no userId; rebinding owner", { sessionId });
        return undefined;
      }
      return meta;
    } catch (err) {
      logWarn("unreadable sidecar; rebinding owner", {
        sessionId,
        error: (err as Error).message,
      });
      return undefined;
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
