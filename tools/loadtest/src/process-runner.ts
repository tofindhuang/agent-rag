import { spawn, type ChildProcess } from "node:child_process";
import { createWriteStream } from "node:fs";
import { setTimeout as delay } from "node:timers/promises";

export interface ManagedProcess {
  readonly pid: number;
  stop(): Promise<void>;
}

export interface StartProcessOptions {
  command: string;
  args: string[];
  cwd: string;
  env?: NodeJS.ProcessEnv;
  logFile: string;
}

/**
 * Starts the process in its own process group so a single signal reaches the
 * whole tree (bash → mvn → java, or npm → tsx → node).
 */
export function startProcess(options: StartProcessOptions): ManagedProcess {
  const log = createWriteStream(options.logFile, { flags: "a" });
  const child = spawn(options.command, options.args, {
    cwd: options.cwd,
    env: { ...process.env, ...options.env },
    detached: true,
    stdio: ["ignore", "pipe", "pipe"],
  });
  child.stdout?.pipe(log);
  child.stderr?.pipe(log);
  let stopped = false;

  return {
    pid: child.pid ?? -1,
    async stop() {
      if (stopped) return;
      stopped = true;
      if (child.exitCode === null && child.pid !== undefined) {
        try {
          process.kill(-child.pid, "SIGTERM");
        } catch {
          child.kill("SIGTERM");
        }
        if (!(await waitForExit(child, 10_000)) && child.pid !== undefined) {
          try {
            process.kill(-child.pid, "SIGKILL");
          } catch {
            child.kill("SIGKILL");
          }
          await waitForExit(child, 5_000);
        }
      }
      log.end();
    },
  };
}

function waitForExit(child: ChildProcess, timeoutMs: number): Promise<boolean> {
  if (child.exitCode !== null) return Promise.resolve(true);
  return new Promise((resolve) => {
    const timer = setTimeout(() => resolve(false), timeoutMs);
    child.once("exit", () => {
      clearTimeout(timer);
      resolve(true);
    });
  });
}

export async function waitForHttp(
  url: string,
  timeoutMs: number,
  intervalMs = 250,
): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  let lastError = "unreachable";
  while (Date.now() < deadline) {
    try {
      const response = await fetch(url);
      if (response.ok) return;
      lastError = `status ${response.status}`;
    } catch (error) {
      lastError = error instanceof Error ? error.message : String(error);
    }
    await delay(intervalMs);
  }
  throw new Error(`timed out waiting for ${url} (${lastError})`);
}

export async function waitForHttpDown(
  url: string,
  timeoutMs: number,
  intervalMs = 250,
): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      await fetch(url);
    } catch {
      return;
    }
    await delay(intervalMs);
  }
  throw new Error(`timed out waiting for ${url} to stop answering`);
}
