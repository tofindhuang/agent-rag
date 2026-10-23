/**
 * agent-core gateway entrypoint. HTTP behavior lives in app.ts; this file owns
 * process startup, the session pool, and graceful shutdown.
 */

import { createApp } from "./app.js";
import { loadConfig } from "./config.js";
import { SessionPool } from "./pool.js";

const config = loadConfig();
const pool = new SessionPool(config);
const app = createApp({ config, pool });

const server = app.listen(config.port, () => {
  process.stdout.write(`gateway listening on :${config.port}\n`);
});

async function shutdown(): Promise<void> {
  server.close();
  await pool.disposeAll();
  process.exit(0);
}
process.on("SIGINT", () => void shutdown());
process.on("SIGTERM", () => void shutdown());
