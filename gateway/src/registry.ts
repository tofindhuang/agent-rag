import { createClient, type RedisClientType } from "redis";
import { logWarn } from "./log.js";

/** Minimal Redis surface used by the instance registry, injectable in tests. */
export interface RegistryStore {
  set(key: string, value: string, ttlSeconds: number): Promise<void>;
  addToSet(key: string, member: string): Promise<void>;
  removeFromSet(key: string, member: string): Promise<void>;
  delete(key: string): Promise<void>;
  members(key: string): Promise<string[]>;
  mget(keys: string[]): Promise<Array<string | null>>;
  quit(): Promise<void>;
}

export interface RegistryOptions {
  instanceId: string;
  publicUrl: string;
  maxSessions: number;
  sessionCount: () => number;
  store: RegistryStore;
  heartbeatMs?: number;
  ttlSeconds?: number;
  now?: () => number;
}

const INSTANCES_KEY = "gw:instances";
const INSTANCE_KEY_PREFIX = "gw:inst:";
const DEFAULT_HEARTBEAT_MS = 5_000;
const DEFAULT_TTL_SECONDS = 15;

/**
 * Redis-backed gateway instance registry (ADR-0024).
 *
 * Registration is best effort: a Redis outage never blocks startup or session
 * serving. The heartbeat retries on every tick, so the instance rejoins the
 * registry when Redis recovers.
 */
export class RedisInstanceRegistry {
  private readonly instanceKey: string;
  private readonly startedAt: number;
  private readonly heartbeatMs: number;
  private readonly ttlSeconds: number;
  private readonly peers = new Set<string>();
  private started = false;
  private draining = false;
  private lastRefreshAt = 0;
  private timer: ReturnType<typeof setInterval> | undefined;

  constructor(private readonly options: RegistryOptions) {
    this.instanceKey = `${INSTANCE_KEY_PREFIX}${options.instanceId}`;
    this.startedAt = this.now();
    this.heartbeatMs = Math.max(10, options.heartbeatMs ?? DEFAULT_HEARTBEAT_MS);
    this.ttlSeconds = Math.max(1, options.ttlSeconds ?? DEFAULT_TTL_SECONDS);
  }

  async start(): Promise<void> {
    if (this.started) return;
    this.started = true;
    await this.heartbeat();
    this.timer = setInterval(() => void this.heartbeat(), this.heartbeatMs);
    this.timer.unref?.();
  }

  async heartbeat(): Promise<void> {
    await this.publish();
    await this.refreshPeers();
  }

  async markDraining(): Promise<void> {
    this.draining = true;
    await this.publish();
  }

  isDraining(): boolean {
    return this.draining;
  }

  /**
   * Returns true/false from the last successful registry refresh, or
   * `undefined` when that view is too old to trust. A stale registry must not
   * be used to deny a lock takeover forever.
   */
  isInstanceAlive(instanceId: string): boolean | undefined {
    if (this.lastRefreshAt === 0) return undefined;
    if (this.now() - this.lastRefreshAt > this.heartbeatMs * 3) return undefined;
    return this.peers.has(instanceId);
  }

  async refreshPeers(): Promise<void> {
    try {
      const ids = await this.options.store.members(INSTANCES_KEY);
      const values = await this.options.store.mget(ids.map((id) => `${INSTANCE_KEY_PREFIX}${id}`));
      this.peers.clear();
      ids.forEach((id, index) => {
        if (values[index] !== null) this.peers.add(id);
      });
      this.lastRefreshAt = this.now();
    } catch (err) {
      logWarn("gateway registry refresh failed", { error: errorMessage(err) });
    }
  }

  async stop(): Promise<void> {
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = undefined;
    }
    if (!this.started) return;
    this.started = false;
    try {
      await this.options.store.delete(this.instanceKey);
      await this.options.store.removeFromSet(INSTANCES_KEY, this.options.instanceId);
      await this.options.store.quit();
    } catch (err) {
      logWarn("gateway registry cleanup failed", { error: errorMessage(err) });
    }
  }

  private async publish(): Promise<void> {
    const payload = {
      url: this.options.publicUrl,
      startedAt: this.startedAt,
      heartbeatAt: this.now(),
      draining: this.draining,
      sessions: this.options.sessionCount(),
      maxSessions: this.options.maxSessions,
    };
    try {
      await this.options.store.addToSet(INSTANCES_KEY, this.options.instanceId);
      await this.options.store.set(this.instanceKey, JSON.stringify(payload), this.ttlSeconds);
    } catch (err) {
      logWarn("gateway registry publish failed", { error: errorMessage(err) });
    }
  }

  private now(): number {
    return (this.options.now ?? Date.now)();
  }
}

/** Redis client adapter; the registry logic stays testable without a server. */
export class RedisRegistryStore implements RegistryStore {
  private readonly client: RedisClientType;

  constructor(url: string) {
    this.client = createClient({ url });
    this.client.on("error", (err) => {
      logWarn("gateway redis client error", { error: errorMessage(err) });
    });
  }

  async set(key: string, value: string, ttlSeconds: number): Promise<void> {
    await (await this.ready()).set(key, value, { EX: ttlSeconds });
  }

  async addToSet(key: string, member: string): Promise<void> {
    await (await this.ready()).sAdd(key, member);
  }

  async removeFromSet(key: string, member: string): Promise<void> {
    await (await this.ready()).sRem(key, member);
  }

  async delete(key: string): Promise<void> {
    await (await this.ready()).del(key);
  }

  async members(key: string): Promise<string[]> {
    return (await this.ready()).sMembers(key);
  }

  async mget(keys: string[]): Promise<Array<string | null>> {
    if (keys.length === 0) return [];
    return (await this.ready()).mGet(keys);
  }

  async quit(): Promise<void> {
    if (this.client.isOpen) await this.client.quit();
  }

  private async ready(): Promise<RedisClientType> {
    if (!this.client.isOpen) await this.client.connect();
    return this.client;
  }
}

function errorMessage(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}
