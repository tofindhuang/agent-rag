import type { CitationPassage } from "./gateway";

export type ChatRole = "user" | "assistant";
export type ChatStatus = "streaming" | "complete" | "partial" | "aborted" | "error";
export type ToolStatus = "calling" | "running" | "done" | "error";

export interface ToolActivity {
  toolCallId: string;
  name: string;
  argsText: string;
  phase?: string;
  output: string;
  status: ToolStatus;
  /** kb_search citations parsed from tool_result.details; absent on old gateways. */
  passages?: CitationPassage[];
}

export interface ChatMessage {
  role: ChatRole;
  content: string;
  thinking: string;
  tools: ToolActivity[];
  status?: ChatStatus;
  /** Stable id for historical messages; live messages use their index. */
  historyId?: number;
}

export type HistoryRole = "user" | "assistant" | "tool";

export interface SessionSummary {
  id: string;
  title: string;
  /** Snapshot written at create/switch; `provider / model` or the actual system model name. */
  modelLabel: string | null;
  modelSource: "system" | "user" | null;
  kbId: string | null;
  kbName: string | null;
  lastActiveAt: string;
  createdAt: string;
}

export interface SessionPage {
  items: SessionSummary[];
  nextCursor: string | null;
}

export interface HistoryItem {
  id: number;
  turnId: string;
  ordinal: number;
  role: HistoryRole;
  content: string | null;
  thinking: string | null;
  status: string | null;
  deliveryStatus: string | null;
  createdAt: string;
  /** Persisted tool_result.details (kb_search citations); absent on old rows. */
  details?: unknown;
}

export interface MessagePage {
  items: HistoryItem[];
  hasMore: boolean;
  nextBeforeId: number | null;
}
