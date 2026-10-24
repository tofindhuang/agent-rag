import { mkdir, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";

import { LoadTestClient } from "./api.js";
import { baselineOffsets, startBaselineSampler } from "./baseline.js";
import { configFromEnv } from "./config.js";
import { appendJsonl } from "./metrics.js";
import { startProcess, waitForHttp, waitForHttpDown, type ManagedProcess } from "./process-runner.js";
import { runLoadTest, type TurnRecord } from "./scenario.js";
import { verifySettledMessages } from "./verify.js";

function intEnv(name: string, fallback: number): number {
  const raw = process.env[name];
  if (!raw) return fallback;
  const parsed = Number.parseInt(raw, 10);
  return Number.isFinite(parsed) ? parsed : fallback;
}

async function main(): Promise<void> {
  const config = configFromEnv();
  const users = intEnv("LOADTEST_USERS", 50);
  const rounds = intEnv("LOADTEST_ROUNDS", 3);
  const chatUsers = intEnv("LOADTEST_CHAT_USERS", 40);
  const runCodeUsers = intEnv("LOADTEST_RUNCODE_USERS", 5);
  const kbUsers = intEnv("LOADTEST_KB_USERS", 5);
  const runId = process.env.LOADTEST_RUN_ID ?? `${Date.now()}`;

  await mkdir(dirname(config.metricsFile), { recursive: true });
  const client = new LoadTestClient({ baseUrl: config.baseUrl });

  const root = resolve(process.cwd(), "../..");
  const manageApp = process.env.LOADTEST_MANAGE_APP === "1";
  const keepApp = process.env.LOADTEST_KEEP_APP === "1";
  const restartAfterMs = intEnv("LOADTEST_RESTART_GATEWAY_AFTER_MS", 0);
  const logDir = dirname(config.metricsFile);
  const managed: ManagedProcess[] = [];
  let gateway: ManagedProcess | null = null;
  let restartPromise: Promise<void> | null = null;
  let restartTimer: NodeJS.Timeout | null = null;

  const startGateway = (): ManagedProcess =>
    startProcess({
      command: "bash",
      args: [resolve(root, "tools/loadtest/scripts/run-gateway.sh")],
      cwd: root,
      logFile: join(logDir, "gateway.log"),
    });

  const baselineEnabled = process.env.LOADTEST_BASELINE_ENABLED !== "false";
  const sampler = baselineEnabled
    ? startBaselineSampler({
        apiKey: config.model.apiKey,
        baseUrl: config.model.baseUrl,
        modelId: config.model.modelId,
        intervalMs: intEnv("LOADTEST_BASELINE_INTERVAL_MS", 5_000),
      })
    : null;

  try {
    if (manageApp) {
      const server = startProcess({
        command: "bash",
        args: [resolve(root, "tools/loadtest/scripts/run-server.sh")],
        cwd: root,
        logFile: join(logDir, "server.log"),
      });
      managed.push(server);
      await waitForHttp("http://127.0.0.1:8080/api/health", 180_000);
      gateway = startGateway();
      managed.push(gateway);
      await waitForHttp("http://127.0.0.1:3001/health", 60_000);
    }

    if (restartAfterMs > 0) {
      restartTimer = setTimeout(() => {
        restartPromise = (async () => {
          if (!gateway) throw new Error("gateway restart requested without LOADTEST_MANAGE_APP=1");
          console.error("[loadtest] restarting gateway");
          await gateway.stop();
          await waitForHttpDown("http://127.0.0.1:3001/health", 30_000);
          gateway = startGateway();
          managed.push(gateway);
          await waitForHttp("http://127.0.0.1:3001/health", 60_000);
          console.error("[loadtest] gateway restarted");
        })();
      }, restartAfterMs);
    }

    const records: TurnRecord[] = [];
    const summary = await runLoadTest({
    client,
    runId,
    users,
    rounds,
    composition: { chat: chatUsers, run_code: runCodeUsers, kb_search: kbUsers },
    thinkTimeMinMs: intEnv("LOADTEST_THINK_MIN_MS", 3_000),
    thinkTimeMaxMs: intEnv("LOADTEST_THINK_MAX_MS", 8_000),
    provider: {
      name: config.model.providerName,
      api: config.model.api,
      baseUrl: config.model.baseUrl,
      apiKey: config.model.apiKey,
      modelId: config.model.modelId,
      displayName: config.model.displayName,
    },
    kbId: process.env.LOADTEST_KB_ID,
    kbSeed: {
      documents: [
        {
          filename: "architecture.md",
          content:
            "# Agent Core 架构\n\nAgent Core 由 gateway、Java server、web、sandbox 四个模块组成。" +
            "gateway 托管 pi 会话并通过 SSE 透传事件；Java 负责认证、RAG、沙箱调度与业务编排。",
        },
        {
          filename: "sessions.md",
          content:
            "# 会话存储方式\n\n会话消息双写：pi session 以 JSONL 形式落盘，MySQL 侧保留消息投影。" +
            "服务重启时可以从持久化会话恢复，已持久化消息不会丢失。",
        },
        {
          filename: "sandbox.md",
          content:
            "# 代码执行沙箱\n\nrun_code 工具在一次性 Docker 容器中执行 Python 代码：" +
            "无网络、非 root、只读根文件系统、CPU 与内存受限，执行记录写入 exec_records 审计表。",
        },
      ],
    },
    retryOnDisconnect: process.env.LOADTEST_RETRY_DISCONNECTS !== "false",
    maxDisconnectRetries: intEnv("LOADTEST_MAX_DISCONNECT_RETRIES", 2),
    retryDelayMs: intEnv("LOADTEST_RETRY_DELAY_MS", 1_500),
    onRecord: (record: TurnRecord) => {
      records.push(record);
      return appendJsonl(config.metricsFile, record);
    },
    onProgress: (message) => console.error(`[loadtest] ${message}`),
  });

    if (restartTimer) clearTimeout(restartTimer);
    if (restartPromise) await restartPromise;

    const verification =
      process.env.LOADTEST_VERIFY === "false"
        ? null
        : await verifySettledMessages(client, records, "loadtest-password-1");

    const baseline = sampler ? await stopSampler(sampler) : null;
    const output = { ...summary, baseline, verification };
    const summaryFile = config.metricsFile.replace(/\.jsonl$/, "") + ".summary.json";
    await writeFile(summaryFile, `${JSON.stringify(output, null, 2)}\n`, "utf8");
    console.log(JSON.stringify(output, null, 2));
    console.log(`[loadtest] records: ${config.metricsFile}`);
    console.log(`[loadtest] summary: ${summaryFile}`);

    const verificationFailed =
      verification !== null &&
      (verification.missing.length > 0 ||
        verification.duplicates.length > 0 ||
        verification.roleMismatches.length > 0 ||
        verification.errors.length > 0);
    const failed =
      summary.errors.hard_error > 0 ||
      summary.errors.disconnected > 0 ||
      (summary.statusCounts.error ?? 0) > 0 ||
      (summary.statusCounts.disconnected ?? 0) > 0 ||
      verificationFailed;
    if (failed) process.exitCode = 1;
  } finally {
    if (restartTimer) clearTimeout(restartTimer);
    if (manageApp && !keepApp) {
      for (const processHandle of [...managed].reverse()) {
        await processHandle.stop();
      }
    }
  }
}

async function stopSampler(
  sampler: ReturnType<typeof startBaselineSampler>,
): Promise<{ count: number; medianMs: number | null; p95Ms: number | null; errors: string[] }> {
  await sampler.stop();
  return { ...baselineOffsets(sampler.samples), errors: sampler.errors };
}

main().catch((error: unknown) => {
  console.error(error instanceof Error ? error.message : error);
  process.exitCode = 1;
});
