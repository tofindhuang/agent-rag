import assert from "node:assert/strict";
import { test } from "node:test";
import {
  RedisInstanceRegistry,
  type RegistryStore,
} from "./registry.js";

class FakeStore implements RegistryStore {
  readonly values = new Map<string, { value: string; ttl: number }>();
  readonly sets = new Map<string, Set<string>>();
  fail = false;

  private check(): void {
    if (this.fail) throw new Error("redis unavailable");
  }

  async set(key: string, value: string, ttlSeconds: number): Promise<void> {
    this.check();
    this.values.set(key, { value, ttl: ttlSeconds });
  }

  async addToSet(key: string, member: string): Promise<void> {
    this.check();
    const set = this.sets.get(key) ?? new Set<string>();
    set.add(member);
    this.sets.set(key, set);
  }

  async removeFromSet(key: string, member: string): Promise<void> {
    this.check();
    this.sets.get(key)?.delete(member);
  }

  async delete(key: string): Promise<void> {
    this.check();
    this.values.delete(key);
  }

  async members(key: string): Promise<string[]> {
    this.check();
    return [...(this.sets.get(key) ?? [])];
  }

  async mget(keys: string[]): Promise<Array<string | null>> {
    this.check();
    return keys.map((key) => this.values.get(key)?.value ?? null);
  }

  async quit(): Promise<void> {}
}

function registry(
  store: RegistryStore,
  overrides: Partial<ConstructorParameters<typeof RedisInstanceRegistry>[0]> = {},
): RedisInstanceRegistry {
  return new RedisInstanceRegistry({
    instanceId: "g1",
    incarnation: "inc-1",
    publicUrl: "http://127.0.0.1:3001",
    maxSessions: 60,
    sessionCount: () => 0,
    store,
    heartbeatMs: 60_000,
    ttlSeconds: 15,
    ...overrides,
  });
}

test("registers the instance with its capacity and heartbeat", async () => {
  const store = new FakeStore();
  let sessions = 3;
  const reg = registry(store, { sessionCount: () => sessions });

  await reg.start();

  assert.ok(store.sets.get("gw:instances")?.has("g1"));
  const raw = store.values.get("gw:inst:g1");
  assert.ok(raw);
  assert.equal(raw.ttl, 15);
  const payload = JSON.parse(raw.value);
  assert.equal(payload.url, "http://127.0.0.1:3001");
  assert.equal(payload.maxSessions, 60);
  assert.equal(payload.sessions, 3);
  assert.equal(payload.draining, false);

  sessions = 7;
  await reg.heartbeat();

  assert.equal(JSON.parse(store.values.get("gw:inst:g1")!.value).sessions, 7);
  await reg.stop();
});

test("publishes draining state while keeping the registration", async () => {
  const store = new FakeStore();
  const reg = registry(store);
  await reg.start();

  reg.markDraining();
  await reg.heartbeat();

  const payload = JSON.parse(store.values.get("gw:inst:g1")!.value);
  assert.equal(payload.draining, true);
  assert.equal(reg.isDraining(), true);
  await reg.stop();
});

test("removes the registration on graceful shutdown", async () => {
  const store = new FakeStore();
  const reg = registry(store);
  await reg.start();

  await reg.stop();

  assert.equal(store.values.has("gw:inst:g1"), false);
  assert.equal(store.sets.get("gw:instances")?.has("g1"), false);
});

test("does not throw when Redis is unavailable and recovers on the next heartbeat", async () => {
  const store = new FakeStore();
  store.fail = true;
  const reg = registry(store);

  await reg.start();
  await reg.heartbeat();
  assert.equal(store.values.has("gw:inst:g1"), false);

  store.fail = false;
  await reg.heartbeat();

  assert.ok(store.values.has("gw:inst:g1"));
  await reg.stop();
});

test("tracks whether another instance is alive", async () => {
  const store = new FakeStore();
  const first = registry(store, { instanceId: "g1" });
  const second = registry(store, { instanceId: "g2" });
  await first.start();
  await second.start();

  await first.refreshPeers();
  assert.equal(first.isInstanceAlive("g1", "inc-1"), true);
  assert.equal(first.isInstanceAlive("g2", "inc-1"), true);
  assert.equal(first.isInstanceAlive("g3", "inc-1"), false);

  await second.stop();
  await first.refreshPeers();
  assert.equal(first.isInstanceAlive("g2", "inc-1"), false);

  await first.stop();
});

test("drops a session route hint on eviction", async () => {
  const store = new FakeStore();
  const reg = registry(store);
  await reg.start();
  await store.set("route:sess:s1", "g1", 60);

  await reg.deleteSessionRoute("s1");

  assert.equal(store.values.has("route:sess:s1"), false);
  await reg.stop();
});

test("returns unknown liveness when the registry refresh is stale", async () => {
  const store = new FakeStore();
  let now = 1_000;
  const reg = registry(store, { now: () => now, heartbeatMs: 1_000 });
  await reg.start();
  await reg.refreshPeers();

  now += 10_000;
  assert.equal(reg.isInstanceAlive("g1", "inc-1"), undefined);
  await reg.stop();
});
