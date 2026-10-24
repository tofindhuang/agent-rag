export const CONTRACT_VERSION = "1" as const;
export type ContractVersion = typeof CONTRACT_VERSION;

export type StopReason = "pending" | "stop" | "length" | "toolUse" | "error" | "aborted" | "deferred";

export interface UsagePayload {
  input: number;
  output: number;
  cacheRead: number;
  cacheWrite: number;
  reasoning: number;
  totalTokens: number;
  cost: {
    input: number;
    output: number;
    cacheRead: number;
    cacheWrite: number;
    total: number;
  };
}

export interface RunError {
  code: string;
  message: string;
  retryable: boolean;
}

export type ToolProgressKind = "phase" | "stdout" | "stderr";

export interface MessageToolCall {
  toolCallId: string;
  name: string;
  args: unknown;
}

/**
 * Contract v1 domain events. See contract/v1/README.md. `seq` and the SSE
 * frame id are transport concerns and are added by the SSE writer.
 */
export interface ContextUsageData {
  tokens: number | null;
  contextWindow: number;
  percent: number | null;
  compactionThresholdPercent: number;
  compactionEnabled: boolean;
  /** provider = last assistant usage; estimated = pi context projection. */
  source: "provider" | "estimated";
}

export type DomainEvent =
  | {
      type: "run_started";
      data: {
        runId: string;
        turnId: string;
        sessionId: string;
        contractVersion: ContractVersion;
      };
    }
  | { type: "thinking_delta"; data: { delta: string } }
  | { type: "text_delta"; data: { delta: string } }
  | { type: "tool_call_delta"; data: { toolCallId: string; name: string; delta: string } }
  | { type: "tool_call"; data: { toolCallId: string; name: string; args: unknown } }
  | {
      type: "tool_progress";
      data: {
        toolCallId: string;
        name: string;
        kind: ToolProgressKind;
        phase?: string;
        chunk?: string;
      };
    }
  | {
      type: "tool_result";
      data: {
        toolCallId: string;
        name: string;
        isError: boolean;
        durationMs: number;
        content: string;
        truncated: boolean;
        details?: unknown;
      };
    }
  | {
      type: "message_end";
      data: {
        seqInTurn: number;
        role: "assistant";
        text: string;
        thinking: string;
        toolCalls: MessageToolCall[];
        usage: UsagePayload;
        stopReason: StopReason;
      };
    }
  | { type: "context_usage"; data: ContextUsageData }
  | {
      type: "compaction";
      data: {
        reason: "manual" | "threshold" | "overflow";
        tokensBefore: number;
        tokensAfter: number | null;
      };
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
