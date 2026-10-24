/**
 * Contract v1 domain events (contract/v1/README.md). Only the fields the UI
 * consumes are modelled here; Java relays the frames unchanged.
 */
export interface RunError {
  code: string;
  message: string;
  retryable: boolean;
}

export interface MessageEndData {
  seqInTurn: number;
  role: "assistant";
  text: string;
  thinking: string;
  toolCalls: Array<{ toolCallId: string; name: string; args: unknown }>;
  stopReason: string;
}

export interface ToolProgressData {
  toolCallId: string;
  name: string;
  kind: "phase" | "stdout" | "stderr";
  phase?: string;
  chunk?: string;
}

export interface ToolResultData {
  toolCallId: string;
  name: string;
  isError: boolean;
  content: string;
  /** Optional structured payload (contract v1 addition); kb_search sends passages. */
  details?: unknown;
}

/** One kb_search citation; mirrors KbSearchResponse.Passage on the server. */
export interface CitationPassage {
  passageId: string;
  documentId: string;
  seq: number;
  page: number | null;
  title: string;
  content: string;
  score: number;
  reranked: boolean;
  kbId?: string;
  kbName?: string;
}

/**
 * Reads `tool_result.details.passages` defensively: an older gateway omits
 * details entirely, so callers get an empty list and keep the plain tool card.
 */
export function parseCitationPassages(details: unknown): CitationPassage[] {
  if (typeof details !== "object" || details === null) return [];
  const passages = (details as { passages?: unknown }).passages;
  if (!Array.isArray(passages)) return [];
  const parsed: CitationPassage[] = [];
  for (const raw of passages) {
    if (typeof raw !== "object" || raw === null) continue;
    const passage = raw as Record<string, unknown>;
    if (
      typeof passage.passageId !== "string" ||
      typeof passage.documentId !== "string" ||
      typeof passage.seq !== "number" ||
      typeof passage.content !== "string" ||
      typeof passage.score !== "number"
    ) {
      continue;
    }
    parsed.push({
      passageId: passage.passageId,
      documentId: passage.documentId,
      seq: passage.seq,
      page: typeof passage.page === "number" ? passage.page : null,
      title: typeof passage.title === "string" ? passage.title : "未命名文档",
      content: passage.content,
      score: passage.score,
      reranked: passage.reranked === true,
      kbId: typeof passage.kbId === "string" ? passage.kbId : undefined,
      kbName: typeof passage.kbName === "string" ? passage.kbName : undefined,
    });
  }
  return parsed;
}

export type GatewayEvent =
  | {
      type: "run_started";
      data: {
        runId: string;
        turnId: string;
        sessionId: string;
        contractVersion: string;
      };
    }
  | { type: "thinking_delta"; data: { delta: string } }
  | { type: "text_delta"; data: { delta: string } }
  | { type: "tool_call_delta"; data: { toolCallId: string; name: string; delta: string } }
  | { type: "tool_call"; data: { toolCallId: string; name: string; args: unknown } }
  | { type: "tool_progress"; data: ToolProgressData }
  | { type: "tool_result"; data: ToolResultData }
  | { type: "message_end"; data: MessageEndData }
  | {
      type: "compaction";
      data: { reason: string; tokensBefore: number; tokensAfter: number | null };
    }
  | { type: "retry"; data: { attempt: number; maxAttempts: number; delayMs: number; message: string } }
  | {
      type: "run_settled";
      data: {
        status: "completed" | "aborted" | "error";
        turnId: string;
        error?: RunError;
      };
    };

const EVENT_TYPES = new Set<GatewayEvent["type"]>([
  "run_started",
  "thinking_delta",
  "text_delta",
  "tool_call_delta",
  "tool_call",
  "tool_progress",
  "tool_result",
  "message_end",
  "compaction",
  "retry",
  "run_settled",
]);

/** Parses a raw SSE frame into a known contract event; unknown frames are ignored. */
export function parseGatewayEvent(
  event: string | undefined,
  data: string | undefined,
): GatewayEvent | null {
  if (!event || !data || !EVENT_TYPES.has(event as GatewayEvent["type"])) return null;
  try {
    return { type: event, data: JSON.parse(data) } as GatewayEvent;
  } catch {
    return null;
  }
}
