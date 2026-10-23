import type { AgentSessionEvent } from "@earendil-works/pi-coding-agent";

export interface RunLogContext {
  sessionId: string;
  runId: string;
  turnId: string;
}

/**
 * Raw pi events are never exposed on the SSE contract. When explicitly enabled,
 * they are emitted as one structured JSON line for debugging.
 */
/** Structured warning line; never emits free-form console output. */
export function logWarn(msg: string, fields: Record<string, unknown> = {}): void {
  process.stdout.write(JSON.stringify({ level: "warn", msg, ...fields }) + "\n");
}

export function logPiEvent(context: RunLogContext, event: AgentSessionEvent): void {
  if (process.env.GATEWAY_LOG_PI_EVENTS !== "1") return;
  process.stdout.write(
    JSON.stringify({ level: "debug", msg: "pi_event", ...context, event }) + "\n",
  );
}
