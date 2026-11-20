import type { DomainEvent } from "./events.js";

export const HEARTBEAT_INTERVAL_MS = 15_000;
export const HEARTBEAT_FRAME = ": ping\n\n";

/** Format one contract v1 SSE frame. `seq` is 1-based within a Run. */
export function formatSseFrame(runId: string, seq: number, event: DomainEvent): string {
  return `id: ${runId}:${seq}\nevent: ${event.type}\ndata: ${JSON.stringify(event.data)}\n\n`;
}
