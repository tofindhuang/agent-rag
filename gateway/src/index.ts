/**
 * agent-core gateway: HTTP front for the pi agent engine.
 *
 * API (contract v1, see contract/v1/README.md):
 *   GET    /health                    -> { ok, contractVersion, sessions }
 *   POST   /sessions                  { sessionId, userId } -> 201 { sessionId }
 *   POST   /sessions/:id/prompt       { text, turnId? } -> SSE contract v1 events
 *   DELETE /sessions/:id              -> dispose session
 *
 * The SSE stream is a translation of pi AgentSessionEvent values into the
 * normalized contract events; pi types are not exposed to Java or the web.
 */

import { randomUUID } from "node:crypto";
import express, { type Request, type Response } from "express";
import { loadConfig } from "./config.js";
import { CONTRACT_VERSION, type DomainEvent } from "./contract/events.js";
import { formatSseFrame, HEARTBEAT_FRAME, HEARTBEAT_INTERVAL_MS } from "./contract/sse.js";
import { RunTranslator } from "./contract/translate.js";
import { logPiEvent } from "./log.js";
import { SessionExistsError, SessionNotFoundError, SessionPool } from "./pool.js";

const config = loadConfig();
const pool = new SessionPool(config);
const app = express();
app.disable("x-powered-by");
app.use(express.json({ limit: "1mb" }));

// Shared-token auth (FR-6). Java passes the same GATEWAY_API_TOKEN.
app.use((req, res, next) => {
  if (!config.apiToken) return next();
  const header = req.headers.authorization ?? "";
  if (header === `Bearer ${config.apiToken}`) return next();
  res.status(401).json({ error: "unauthorized" });
});

app.get("/health", (_req, res) => {
  res.json({ ok: true, contractVersion: CONTRACT_VERSION, sessions: pool.size });
});

app.post("/sessions", async (req: Request, res: Response) => {
  const { sessionId, userId } = (req.body ?? {}) as { sessionId?: unknown; userId?: unknown };
  if (typeof sessionId !== "string" || sessionId.length === 0) {
    res.status(400).json({ error: "sessionId (string) is required" });
    return;
  }
  if (typeof userId !== "string" || userId.length === 0) {
    res.status(400).json({ error: "userId (string) is required" });
    return;
  }
  try {
    await pool.create(sessionId, userId);
    res.status(201).json({ sessionId });
  } catch (err) {
    if (err instanceof SessionExistsError) {
      res.status(409).json({ error: err.message });
      return;
    }
    res.status(500).json({ error: err instanceof Error ? err.message : String(err) });
  }
});

app.post("/sessions/:id/prompt", async (req: Request, res: Response) => {
  const entry = pool.get(req.params.id);
  if (!entry) {
    res.status(404).json({ error: "session not found" });
    return;
  }
  const { text, turnId } = (req.body ?? {}) as { text?: unknown; turnId?: unknown };
  if (typeof text !== "string" || text.trim().length === 0) {
    res.status(400).json({ error: "text (non-empty string) is required" });
    return;
  }

  res.writeHead(200, {
    "content-type": "text/event-stream; charset=utf-8",
    "cache-control": "no-cache, no-transform",
    connection: "keep-alive",
    "x-accel-buffering": "no",
  });

  const runId = randomUUID();
  const resolvedTurnId =
    typeof turnId === "string" && turnId.length > 0 ? turnId : randomUUID();
  const translator = new RunTranslator({
    runId,
    turnId: resolvedTurnId,
    sessionId: req.params.id,
    contractVersion: CONTRACT_VERSION,
  });

  let seq = 0;
  let closed = false;
  let terminalSent = false;
  let heartbeat: ReturnType<typeof setInterval> | undefined;
  let unsubscribe: (() => void) | null = null;
  const sendEvent = (event: DomainEvent): void => {
    if (closed || terminalSent || res.writableEnded) return;
    seq += 1;
    res.write(formatSseFrame(runId, seq, event));
    if (event.type === "run_settled") {
      terminalSent = true;
      if (heartbeat) clearInterval(heartbeat);
      res.end();
    }
  };
  heartbeat = setInterval(() => {
    if (!closed && !terminalSent && !res.writableEnded) res.write(HEARTBEAT_FRAME);
  }, HEARTBEAT_INTERVAL_MS);

  // NOTE: use res 'close', not req 'close' (ADR-0002). A close before
  // res.end() means the client dropped mid-stream; abort the run.
  res.on("close", () => {
    if (res.writableEnded) return;
    closed = true;
    if (heartbeat) clearInterval(heartbeat);
    unsubscribe?.();
    entry.session.abort().catch(() => undefined);
  });

  for (const event of translator.start()) sendEvent(event);

  try {
    // Subscribe before prompting: fast completions must not race the listener.
    unsubscribe = entry.session.subscribe((event) => {
      logPiEvent({ sessionId: req.params.id, runId, turnId: resolvedTurnId }, event);
      for (const domainEvent of translator.translate(event)) sendEvent(domainEvent);
    });

    await pool.run(req.params.id, text);
    for (const event of translator.finish("completed")) sendEvent(event);
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    for (const event of translator.fail("MODEL_ERROR", message)) sendEvent(event);
  } finally {
    if (heartbeat) clearInterval(heartbeat);
    unsubscribe?.();
    if (!closed && !res.writableEnded) res.end();
  }
});

app.delete("/sessions/:id", async (req: Request, res: Response) => {
  const disposed = await pool.dispose(req.params.id);
  res.status(disposed ? 204 : 404).end();
});

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
