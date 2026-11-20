import { test } from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { mkdtemp } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { startProcess, waitForHttp } from "../src/process-runner.js";

test("waitForHttp resolves once the endpoint answers", async () => {
  const server = createServer((_request, response) => {
    response.writeHead(200).end("ok");
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  assert.ok(address && typeof address === "object");
  try {
    await waitForHttp(`http://127.0.0.1:${address.port}/health`, 2_000, 20);
  } finally {
    await new Promise<void>((resolve, reject) =>
      server.close((error) => (error ? reject(error) : resolve())),
    );
  }
});

test("waitForHttp times out with the last error", async () => {
  await assert.rejects(waitForHttp("http://127.0.0.1:1/health", 200, 20), /timed out/);
});

test("startProcess runs a detached process and stop terminates its group", async () => {
  const dir = await mkdtemp(join(tmpdir(), "loadtest-process-"));
  const managed = startProcess({
    command: process.execPath,
    args: ["-e", "setInterval(() => {}, 1000)"],
    cwd: dir,
    logFile: join(dir, "proc.log"),
  });

  assert.ok(managed.pid > 0);
  assert.doesNotThrow(() => process.kill(managed.pid, 0));

  await managed.stop();

  const alive = (() => {
    try {
      process.kill(managed.pid, 0);
      return true;
    } catch {
      return false;
    }
  })();
  assert.equal(alive, false);
});
