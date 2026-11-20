import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { after, test } from "node:test";
import { SessionBusyError, SessionLock } from "./session-lock.js";

const dirs: string[] = [];

function tempDir(): string {
  const dir = mkdtempSync(join(tmpdir(), "gateway-lock-"));
  dirs.push(dir);
  return dir;
}

after(() => {
  for (const dir of dirs) rmSync(dir, { recursive: true, force: true });
});

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

test("holds an exclusive lock and releases it", () => {
  const dir = tempDir();
  const first = new SessionLock(dir, "s1", {
    instanceId: "g1",
    heartbeatMs: 60_000,
    staleMs: 20,
  });

  first.acquire();

  const owner = JSON.parse(readFileSync(join(dir, "s1.lock", "owner.json"), "utf8"));
  assert.equal(owner.instanceId, "g1");
  assert.throws(
    () =>
      new SessionLock(dir, "s1", {
        instanceId: "g2",
        heartbeatMs: 60_000,
        staleMs: 20,
      }).acquire(),
    SessionBusyError,
  );

  first.release();

  const second = new SessionLock(dir, "s1", {
    instanceId: "g2",
    heartbeatMs: 60_000,
    staleMs: 20,
  });
  second.acquire();
  second.release();
});

test("reports the current owner on conflict", () => {
  const dir = tempDir();
  const first = new SessionLock(dir, "s1", {
    instanceId: "g1",
    heartbeatMs: 60_000,
    staleMs: 20,
  });
  first.acquire();

  try {
    new SessionLock(dir, "s1", {
      instanceId: "g2",
      heartbeatMs: 60_000,
      staleMs: 20,
    }).acquire();
    assert.fail("expected SessionBusyError");
  } catch (err) {
    assert.ok(err instanceof SessionBusyError);
    assert.equal(err.owner?.instanceId, "g1");
  } finally {
    first.release();
  }
});

test("takes over a stale lock after the owner stops heartbeating", async () => {
  const dir = tempDir();
  const first = new SessionLock(dir, "s1", {
    instanceId: "g1",
    heartbeatMs: 60_000,
    staleMs: 20,
  });
  first.acquire();

  await sleep(30);

  const second = new SessionLock(dir, "s1", {
    instanceId: "g2",
    heartbeatMs: 60_000,
    staleMs: 20,
  });
  second.acquire();

  // The old owner must not delete the new owner's lock when it eventually releases.
  first.release();
  const stillOwned = JSON.parse(readFileSync(join(dir, "s1.lock", "owner.json"), "utf8"));
  assert.equal(stillOwned.instanceId, "g2");

  second.release();
});

test("does not take over a stale heartbeat while the owner instance is alive", async () => {
  const dir = tempDir();
  const first = new SessionLock(dir, "s1", {
    instanceId: "g1",
    incarnation: "inc-1",
    heartbeatMs: 60_000,
    staleMs: 20,
    isInstanceAlive: (instanceId, incarnation) =>
      instanceId === "g1" && incarnation === "inc-1",
  });
  first.acquire();

  await sleep(30);

  assert.throws(
    () =>
      new SessionLock(dir, "s1", {
        instanceId: "g2",
        incarnation: "inc-2",
        heartbeatMs: 60_000,
        staleMs: 20,
        isInstanceAlive: (instanceId, incarnation) =>
          instanceId === "g1" && incarnation === "inc-1",
      }).acquire(),
    SessionBusyError,
  );

  first.release();
});

test("keeps the lock alive while the heartbeat runs", async () => {
  const dir = tempDir();
  const first = new SessionLock(dir, "s1", {
    instanceId: "g1",
    heartbeatMs: 10,
    staleMs: 40,
  });
  first.acquire();

  await sleep(60);

  assert.throws(
    () =>
      new SessionLock(dir, "s1", {
        instanceId: "g2",
        heartbeatMs: 10,
        staleMs: 40,
      }).acquire(),
    SessionBusyError,
  );

  first.release();
});
