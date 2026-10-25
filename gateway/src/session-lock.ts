import { randomUUID } from "node:crypto";
import {
  existsSync,
  mkdirSync,
  readFileSync,
  renameSync,
  rmSync,
  statSync,
  writeFileSync,
} from "node:fs";
import { join } from "node:path";
import { logWarn } from "./log.js";

/** Metadata written inside `<sessionId>.lock/owner.json`. */
export interface SessionLockOwner {
  instanceId: string;
  /** Process-incarnation token; absent on locks written before ADR-0024 hardening. */
  incarnation?: string;
  pid: number;
  acquiredAt: number;
  heartbeatAt: number;
}

export interface SessionLockOptions {
  /** Stable identity of this gateway instance. */
  instanceId: string;
  /** Process-incarnation token for this run of the instance. */
  incarnation?: string;
  /** How often the owner refreshes `heartbeatAt`. */
  heartbeatMs?: number;
  /** Age after which another instance may attempt a takeover. */
  staleMs?: number;
  /**
   * Optional registry probe. When it confirms the owner instance is still
   * alive, a stale heartbeat is not enough to take the lock away.
   */
  isInstanceAlive?: (instanceId: string, incarnation?: string) => boolean | undefined;
  /** Injectable clock for tests. */
  now?: () => number;
  /** Injectable pid for tests. */
  pid?: number;
}

const DEFAULT_HEARTBEAT_MS = 5_000;
const DEFAULT_STALE_MS = 30_000;
const ACQUIRE_ATTEMPTS = 3;
const OWNER_READ_RETRIES = 5;
const OWNER_READ_DELAY_MS = 2;
const sleeper = new Int32Array(new SharedArrayBuffer(4));

/** The session is already owned by another gateway instance. */
export class SessionBusyError extends Error {
  constructor(
    readonly sessionId: string,
    readonly owner?: SessionLockOwner,
  ) {
    super(
      owner
        ? `session is owned by another gateway instance: ${owner.instanceId}`
        : "session is owned by another gateway instance",
    );
  }
}

/**
 * Single-writer lock for one pi session (ADR-0024).
 *
 * The lock is a directory created with the atomic `mkdir` primitive; the owner
 * refreshes a heartbeat file while the AgentSession is resident. A stale lock
 * can be taken over by renaming the old directory away and creating a new one,
 * which keeps the takeover atomic on a local filesystem.
 */
export class SessionLock {
  private readonly lockDir: string;
  private readonly ownerPath: string;
  private readonly heartbeatMs: number;
  private readonly staleMs: number;
  private owned = false;
  private timer: ReturnType<typeof setInterval> | undefined;

  constructor(
    sessionDir: string,
    private readonly sessionId: string,
    private readonly options: SessionLockOptions,
  ) {
    this.lockDir = join(sessionDir, `${sessionId}.lock`);
    this.ownerPath = join(this.lockDir, "owner.json");
    this.heartbeatMs = Math.max(10, options.heartbeatMs ?? DEFAULT_HEARTBEAT_MS);
    this.staleMs = Math.max(10, options.staleMs ?? DEFAULT_STALE_MS);
  }

  get isHeld(): boolean {
    return this.owned;
  }

  acquire(): void {
    let lastOwner: SessionLockOwner | undefined;

    for (let attempt = 0; attempt < ACQUIRE_ATTEMPTS; attempt += 1) {
      if (this.tryCreate()) return;

      lastOwner = this.readOwnerWithGrace();
      const heartbeatAt = lastOwner?.heartbeatAt ?? this.lockDirMtime();
      const stale = this.now() - heartbeatAt > this.staleMs;

      if (!stale) {
        throw new SessionBusyError(this.sessionId, lastOwner);
      }
      if (lastOwner
          && lastOwner.incarnation
          && this.options.isInstanceAlive?.(lastOwner.instanceId, lastOwner.incarnation) === true) {
        throw new SessionBusyError(this.sessionId, lastOwner);
      }
      if (this.tryTakeover()) return;
    }

    throw new SessionBusyError(this.sessionId, lastOwner);
  }

  release(): void {
    if (!this.owned) return;
    this.owned = false;
    this.stopHeartbeat();

    const owner = this.readOwner();
    if (!owner || owner.instanceId !== this.options.instanceId) {
      // The lock was taken over (or its owner file vanished) while this
      // instance was paused; never remove the new owner's directory.
      return;
    }
    rmSync(this.lockDir, { recursive: true, force: true });
  }

  private tryCreate(): boolean {
    try {
      mkdirSync(this.lockDir);
    } catch (err) {
      if ((err as NodeJS.ErrnoException).code === "EEXIST") return false;
      throw err;
    }
    this.writeOwner();
    this.owned = true;
    this.startHeartbeat();
    return true;
  }

  private tryTakeover(): boolean {
    const staleDir = `${this.lockDir}.stale.${this.options.instanceId}.${randomUUID()}`;
    try {
      renameSync(this.lockDir, staleDir);
    } catch (err) {
      if ((err as NodeJS.ErrnoException).code === "ENOENT") return false;
      throw err;
    }

    try {
      mkdirSync(this.lockDir);
      this.writeOwner();
      this.owned = true;
      this.startHeartbeat();
      return true;
    } catch (err) {
      if ((err as NodeJS.ErrnoException).code === "EEXIST") return false;
      throw err;
    } finally {
      rmSync(staleDir, { recursive: true, force: true });
    }
  }

  private startHeartbeat(): void {
    this.stopHeartbeat();
    this.timer = setInterval(() => this.heartbeat(), this.heartbeatMs);
    this.timer.unref?.();
  }

  private stopHeartbeat(): void {
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = undefined;
    }
  }

  private heartbeat(): void {
    if (!this.owned) return;
    const owner = this.readOwner();
    if (!owner || owner.instanceId !== this.options.instanceId) {
      // The lock directory is gone or belongs to another instance. Stop
      // heartbeating instead of recreating or overwriting its owner file.
      this.owned = false;
      this.stopHeartbeat();
      logWarn("session lock lost to another instance", {
        sessionId: this.sessionId,
        owner: owner?.instanceId,
      });
      return;
    }
    try {
      this.writeOwner();
    } catch (err) {
      this.owned = false;
      this.stopHeartbeat();
      logWarn("session lock heartbeat failed", {
        sessionId: this.sessionId,
        error: (err as Error).message,
      });
    }
  }

  private writeOwner(): void {
    const now = this.now();
    const owner: SessionLockOwner = {
      instanceId: this.options.instanceId,
      incarnation: this.options.incarnation,
      pid: this.options.pid ?? process.pid,
      acquiredAt: this.readOwner()?.acquiredAt ?? now,
      heartbeatAt: now,
    };
    const tmp = `${this.ownerPath}.${process.pid}.${randomUUID()}.tmp`;
    writeFileSync(tmp, JSON.stringify(owner), "utf8");
    renameSync(tmp, this.ownerPath);
  }

  /** The creator writes owner.json right after mkdir; give it a few ms. */
  private readOwnerWithGrace(): SessionLockOwner | undefined {
    for (let attempt = 0; attempt < OWNER_READ_RETRIES; attempt += 1) {
      const owner = this.readOwner();
      if (owner) return owner;
      Atomics.wait(sleeper, 0, 0, OWNER_READ_DELAY_MS);
    }
    return undefined;
  }

  private readOwner(): SessionLockOwner | undefined {
    if (!existsSync(this.ownerPath)) return undefined;
    try {
      const parsed: unknown = JSON.parse(readFileSync(this.ownerPath, "utf8"));
      if (typeof parsed !== "object" || parsed === null) return undefined;
      const owner = parsed as Partial<SessionLockOwner>;
      if (typeof owner.instanceId !== "string" || owner.instanceId.length === 0) {
        return undefined;
      }
      if (typeof owner.heartbeatAt !== "number" || !Number.isFinite(owner.heartbeatAt)) {
        return undefined;
      }
      return {
        instanceId: owner.instanceId,
        incarnation: typeof owner.incarnation === "string" ? owner.incarnation : undefined,
        pid: typeof owner.pid === "number" ? owner.pid : 0,
        acquiredAt: typeof owner.acquiredAt === "number" ? owner.acquiredAt : owner.heartbeatAt,
        heartbeatAt: owner.heartbeatAt,
      };
    } catch {
      return undefined;
    }
  }

  private lockDirMtime(): number {
    try {
      return statSync(this.lockDir).mtimeMs;
    } catch {
      return this.now();
    }
  }

  private now(): number {
    return (this.options.now ?? Date.now)();
  }
}
