import { mkdir, writeFile } from "node:fs/promises";
import { dirname } from "node:path";

import { LoadTestClient } from "./api.js";
import { configFromEnv } from "./config.js";
import { appendJsonl } from "./metrics.js";
import { runLoadTest, type TurnRecord } from "./scenario.js";

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
    onRecord: (record: TurnRecord) => appendJsonl(config.metricsFile, record),
    onProgress: (message) => console.error(`[loadtest] ${message}`),
  });

  const summaryFile = config.metricsFile.replace(/\.jsonl$/, "") + ".summary.json";
  await writeFile(summaryFile, `${JSON.stringify(summary, null, 2)}\n`, "utf8");
  console.log(JSON.stringify(summary, null, 2));
  console.log(`[loadtest] records: ${config.metricsFile}`);
  console.log(`[loadtest] summary: ${summaryFile}`);

  const failed =
    summary.errors.hard_error > 0 ||
    summary.errors.disconnected > 0 ||
    (summary.statusCounts.error ?? 0) > 0 ||
    (summary.statusCounts.disconnected ?? 0) > 0;
  if (failed) process.exitCode = 1;
}

main().catch((error: unknown) => {
  console.error(error instanceof Error ? error.message : error);
  process.exitCode = 1;
});
