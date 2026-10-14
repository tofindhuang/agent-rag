/**
 * agent-core gateway: HTTP front for the pi agent engine.
 *
 * API (contract v0, see docs/requirements.md §8 O1):
 *   GET    /health                    -> { ok, sessions }
 *   POST   /sessions                  { sessionId, userId } -> 201 { sessionId }
 *   POST   /sessions/:id/prompt       { text } -> SSE stream of pi session events
 *                                          (event name = pi event type), ending with
 *                                          `prompt_settled` or `prompt_error`
 *   DELETE /sessions/:id              -> dispose session
 *
 * The SSE stream is a passthrough of pi session events; Java and the frontend
 * consume text_delta / tool_execution_* / message_end / agent_settled from it.
 */

import express, { type Request, type Response } from "express";
import { loadConfig } from "./config.js";
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
  res.json({ ok: true, sessions: pool.size });
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
  const { text } = (req.body ?? {}) as { text?: unknown };
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
  const send = (event: string, data: unknown): void => {
    if (!res.writableEnded) res.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
  };

  let closed = false;
  let unsubscribe: (() => void) | null = null;
  // NOTE: use res 'close', not req 'close'. req 'close' fires as soon as the
  // request body has been consumed, which would abort every run immediately.
  // res 'close' with writableEnded == false means the client dropped mid-stream.
  res.on("close", () => {
    if (res.writableEnded) return;
    closed = true;
    unsubscribe?.();
    entry.session.abort().catch(() => undefined);
  });

  // Subscribe before prompting: fast completions must not race the listener.
  unsubscribe = entry.session.subscribe((event) => send(event.type, event));

  try {
    await pool.run(req.params.id, text);
    if (!closed) send("prompt_settled", { sessionId: req.params.id });
  } catch (err) {
    if (!closed) send("prompt_error", { message: err instanceof Error ? err.message : String(err) });
  } finally {
    unsubscribe?.();
    res.end();
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
