import assert from "node:assert/strict";
import { existsSync, mkdtempSync, readFileSync, rmSync, unlinkSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { after, test } from "node:test";
import { SessionConflictError } from "./pool-contract.js";
import { SessionFileStore } from "./session-store.js";

const dirs: string[] = [];

function tempStore(): { store: SessionFileStore; dir: string } {
  const dir = mkdtempSync(join(tmpdir(), "gateway-sessions-"));
  dirs.push(dir);
  return { store: new SessionFileStore(process.cwd(), dir), dir };
}

after(() => {
  for (const dir of dirs) rmSync(dir, { recursive: true, force: true });
});

function flushConversation(manager: ReturnType<SessionFileStore["acquire"]>["manager"]): void {
  // Test fixture: only the fields pi uses to persist a user message.
  manager.appendMessage({
    role: "user",
    content: [{ type: "text", text: "hello" }],
    timestamp: Date.now(),
  } as unknown as Parameters<typeof manager.appendMessage>[0]);
}

test("creates a session file and its ownership sidecar", () => {
  const { store, dir } = tempStore();

  const lease = store.acquire("s1", "u1");

  assert.equal(lease.created, true);
  assert.equal(lease.restored, false);
  const meta = JSON.parse(readFileSync(join(dir, "s1.meta.json"), "utf8"));
  assert.equal(meta.userId, "u1");

  flushConversation(lease.manager);
  const file = lease.manager.getSessionFile();
  assert.ok(file);
  assert.ok(existsSync(file));
});

test("restores an existing session after a restart", () => {
  const { dir } = tempStore();
  const first = new SessionFileStore(process.cwd(), dir);
  const created = first.acquire("s2", "u1");
  flushConversation(created.manager);
  const file = created.manager.getSessionFile();

  const restarted = new SessionFileStore(process.cwd(), dir).acquire("s2", "u1");

  assert.equal(restarted.created, false);
  assert.equal(restarted.restored, true);
  assert.equal(restarted.manager.getSessionId(), "s2");
  assert.equal(restarted.manager.getSessionFile(), file);
  assert.ok(restarted.manager.getEntries().length > 0, "restored context must not be empty");
});

test("restores with the cwd recorded at creation", () => {
  const { dir } = tempStore();
  const otherCwd = mkdtempSync(join(tmpdir(), "gateway-cwd-"));
  dirs.push(otherCwd);
  flushConversation(new SessionFileStore(process.cwd(), dir).acquire("s5", "u1").manager);

  const restarted = new SessionFileStore(otherCwd, dir).acquire("s5", "u1");

  assert.equal(restarted.restored, true);
  assert.ok(restarted.manager.getEntries().length > 0);
});

test("rejects a different user for an existing session", () => {
  const { store } = tempStore();
  store.acquire("s3", "u1");

  assert.throws(() => store.acquire("s3", "u2"), SessionConflictError);
});

test("rebinds ownership when the sidecar is missing", () => {
  const { store, dir } = tempStore();
  flushConversation(store.acquire("s4", "u1").manager);
  unlinkSync(join(dir, "s4.meta.json"));

  const rebound = store.acquire("s4", "u2");

  assert.equal(rebound.restored, true);
  const meta = JSON.parse(readFileSync(join(dir, "s4.meta.json"), "utf8"));
  assert.equal(meta.userId, "u2");
});

test("removes the pi file and sidecar for the owning user, idempotently", () => {
  const { store, dir } = tempStore();
  const lease = store.acquire("s6", "u1");
  flushConversation(lease.manager);
  const file = lease.manager.getSessionFile();
  assert.ok(file && existsSync(file));

  store.remove("s6", "u1");

  assert.equal(existsSync(file), false);
  assert.equal(existsSync(join(dir, "s6.meta.json")), false);
  store.remove("s6", "u1"); // second call is a no-op
});

test("refuses to remove another user's session files", () => {
  const { store } = tempStore();
  store.acquire("s7", "u1");

  assert.throws(() => store.remove("s7", "u2"), SessionConflictError);
});

test("refuses to remove a file whose sidecar is missing", () => {
  const { store, dir } = tempStore();
  const lease = store.acquire("s8", "u1");
  flushConversation(lease.manager);
  unlinkSync(join(dir, "s8.meta.json"));

  assert.throws(() => store.remove("s8", "u1"), SessionConflictError);
});
