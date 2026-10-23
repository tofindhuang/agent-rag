import { randomUUID } from "node:crypto";
import express, { type Express, type NextFunction, type Request, type Response } from "express";
import type { GatewayConfig } from "./config.js";
import { CONTRACT_VERSION, type DomainEvent } from "./contract/events.js";
import { formatSseFrame, HEARTBEAT_FRAME, HEARTBEAT_INTERVAL_MS } from "./contract/sse.js";
import { RunTranslator } from "./contract/translate.js";
import type { RunIdentity } from "./identity.js";
import { logPiEvent } from "./log.js";
import type { GatewayPool } from "./pool-contract.js";
import { InvalidSessionIdError, SessionConflictError } from "./pool-contract.js";

export type GatewayErrorCode =
  | "INVALID_REQUEST"
  | "UNAUTHORIZED"
  | "USER_MISMATCH"
  | "SESSION_NOT_FOUND"
  | "SESSION_CONFLICT"
  | "RUN_IN_PROGRESS"
  | "NO_ACTIVE_RUN"
  | "UPSTREAM_UNAVAILABLE"
  | "INTERNAL";

export interface AppDependencies {
  config: GatewayConfig;
  pool: GatewayPool;
}

/** Build the gateway HTTP app; index.ts owns process startup and shutdown. */
export function createApp({ config, pool }: AppDependencies): Express {
  const app = express();
  app.disable("x-powered-by");
  app.use(express.json({ limit: "1mb" }));

  // Shared-token auth (FR-6). Java passes the same GATEWAY_API_TOKEN.
  app.use((req, res, next) => {
    if (!config.apiToken) return next();
    if (req.header("authorization") === `Bearer ${config.apiToken}`) return next();
    sendError(res, 401, "UNAUTHORIZED", "unauthorized");
  });

  app.get("/health", (_req, res) => {
    res.json({ ok: true, contractVersion: CONTRACT_VERSION, sessions: pool.size });
  });

  app.put("/sessions/:id", async (req, res) => {
    const userId = requireUserId(req, res);
    if (!userId) return;

    try {
      const result = await pool.ensure(req.params.id, userId);
      res.status(200).json({
        sessionId: req.params.id,
        created: result.created,
        restored: result.restored,
      });
    } catch (err) {
      if (err instanceof InvalidSessionIdError) {
        sendError(res, 400, "INVALID_REQUEST", "invalid session id");
        return;
      }
      if (err instanceof SessionConflictError) {
        sendError(res, 409, "SESSION_CONFLICT", err.message);
        return;
      }
      sendError(res, 500, "INTERNAL", "internal error");
    }
  });

  app.post("/sessions/:id/prompt", async (req, res) => {
    const userId = requireUserId(req, res);
    if (!userId) return;

    const entry = pool.get(req.params.id);
    if (!entry) {
      sendError(res, 404, "SESSION_NOT_FOUND", "session not found");
      return;
    }
    if (entry.userId !== userId) {
      sendError(res, 403, "USER_MISMATCH", "session belongs to another user");
      return;
    }

    const { text, turnId } = (req.body ?? {}) as { text?: unknown; turnId?: unknown };
    if (typeof text !== "string" || text.trim().length === 0) {
      sendError(res, 400, "INVALID_REQUEST", "text (non-empty string) is required");
      return;
    }
    if (typeof turnId !== "string" || turnId.length === 0) {
      sendError(res, 400, "INVALID_REQUEST", "turnId (non-empty string) is required");
      return;
    }

    const runId = randomUUID();
    const requestId = req.header("x-request-id") || randomUUID();
    const identity: RunIdentity = {
      userId,
      sessionId: req.params.id,
      turnId,
      runId,
      requestId,
    };

    const running = pool.beginRun(req.params.id, identity);
    if (!running) {
      sendError(res, 409, "RUN_IN_PROGRESS", "a run is already active for this session");
      return;
    }

    res.writeHead(200, {
      "content-type": "text/event-stream; charset=utf-8",
      "cache-control": "no-cache, no-transform",
      connection: "keep-alive",
      "x-accel-buffering": "no",
    });

    const translator = new RunTranslator({
      runId,
      turnId,
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
      running.session.abort().catch(() => undefined);
    });

    for (const event of translator.start()) sendEvent(event);

    try {
      // Subscribe before prompting: fast completions must not race the listener.
      unsubscribe = running.session.subscribe((event) => {
        logPiEvent({ sessionId: req.params.id, runId, turnId }, event);
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
      pool.endRun(req.params.id, runId);
      if (!closed && !res.writableEnded) res.end();
    }
  });

  app.post("/sessions/:id/abort", async (req, res) => {
    const userId = requireUserId(req, res);
    if (!userId) return;

    const entry = pool.get(req.params.id);
    if (!entry) {
      sendError(res, 404, "SESSION_NOT_FOUND", "session not found");
      return;
    }
    if (entry.userId !== userId) {
      sendError(res, 403, "USER_MISMATCH", "session belongs to another user");
      return;
    }
    if (!entry.activeRun || !pool.abort(req.params.id)) {
      sendError(res, 409, "NO_ACTIVE_RUN", "no active run");
      return;
    }
    res.status(202).end();
  });

  app.delete("/sessions/:id", async (req, res) => {
    const userId = requireUserId(req, res);
    if (!userId) return;

    const entry = pool.get(req.params.id);
    if (entry && entry.userId !== userId) {
      sendError(res, 403, "USER_MISMATCH", "session belongs to another user");
      return;
    }
    await pool.dispose(req.params.id);
    res.status(204).end();
  });

  // Malformed JSON from express.json() should still use the contract envelope.
  app.use((err: unknown, _req: Request, res: Response, next: NextFunction) => {
    if (err instanceof SyntaxError) {
      sendError(res, 400, "INVALID_REQUEST", "malformed JSON");
      return;
    }
    next(err);
  });

  return app;
}

function requireUserId(req: Request, res: Response): string | null {
  const userId = req.header("x-user-id");
  if (!userId || userId.trim().length === 0) {
    sendError(res, 400, "INVALID_REQUEST", "X-User-Id header is required");
    return null;
  }
  return userId;
}

function sendError(
  res: Response,
  status: number,
  code: GatewayErrorCode,
  message: string,
  retryable = false,
): void {
  if (res.headersSent) return;
  res.status(status).json({ error: { code, message, retryable } });
}
