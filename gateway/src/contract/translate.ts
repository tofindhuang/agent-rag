import type { AgentSessionEvent } from "@earendil-works/pi-coding-agent";
import {
  CONTRACT_VERSION,
  type ContractVersion,
  type DomainEvent,
  type RunError,
  type StopReason,
  type UsagePayload,
} from "./events.js";
import { truncateUtf8 } from "./text.js";

export interface RunContext {
  runId: string;
  turnId: string;
  sessionId: string;
  contractVersion?: ContractVersion;
}

interface PiContentBlock {
  type?: string;
  text?: string;
  thinking?: string;
  id?: string;
  name?: string;
  arguments?: unknown;
}

interface PiUsage {
  input?: number;
  output?: number;
  cacheRead?: number;
  cacheWrite?: number;
  reasoning?: number;
  totalTokens?: number;
  cost?: {
    input?: number;
    output?: number;
    cacheRead?: number;
    cacheWrite?: number;
    total?: number;
  };
}

interface PiMessage {
  role?: string;
  content?: PiContentBlock[];
  usage?: PiUsage;
  stopReason?: string;
  errorMessage?: string;
}

interface PiProgress {
  kind?: string;
  phase?: string;
  chunk?: string;
}

interface PiEventLike {
  type: string;
  assistantMessageEvent?: {
    type?: string;
    delta?: string;
    contentIndex?: number;
    partial?: { content?: PiContentBlock[] };
    toolCall?: { id?: string; name?: string; arguments?: unknown };
  };
  message?: PiMessage;
  toolCallId?: string;
  toolName?: string;
  partialResult?: { details?: { progress?: PiProgress } };
  result?: unknown;
  isError?: boolean;
  durationMs?: number;
  reason?: string;
  attempt?: number;
  maxAttempts?: number;
  delayMs?: number;
  errorMessage?: string;
  aborted?: boolean;
}

const TOOL_RESULT_MAX_BYTES = 65_536;
const TOOL_PROGRESS_MAX_BYTES = 2_048;

/**
 * Translates pi AgentSessionEvent values into contract v1 domain events.
 * One translator owns exactly one Run; events after run_settled are ignored.
 */
export class RunTranslator {
  private started = false;
  private settled = false;
  private messageSeqInTurn = 0;
  private hasErrorStop = false;
  private lastErrorMessage: string | undefined;

  constructor(private readonly context: RunContext) {}

  get isSettled(): boolean {
    return this.settled;
  }

  /** Emit run_started once; safe to call eagerly before pi emits agent_start. */
  start(): DomainEvent[] {
    if (this.started) return [];
    this.started = true;
    return [
      {
        type: "run_started",
        data: {
          runId: this.context.runId,
          turnId: this.context.turnId,
          sessionId: this.context.sessionId,
          contractVersion: this.context.contractVersion ?? CONTRACT_VERSION,
        },
      },
    ];
  }

  translate(event: AgentSessionEvent): DomainEvent[] {
    const raw = event as unknown as PiEventLike;

    if (raw.type === "agent_start") return this.start();
    if (this.settled) return [];

    switch (raw.type) {
      case "message_update":
        return this.translateMessageUpdate(raw);
      case "message_end":
        return this.translateMessageEnd(raw);
      case "tool_execution_update":
        return this.translateToolProgress(raw);
      case "tool_execution_end":
        return this.translateToolResult(raw);
      case "compaction_end":
        return this.translateCompaction(raw);
      case "auto_retry_start":
        return this.translateRetry(raw);
      case "agent_settled":
        return this.translateSettled(raw);
      default:
        return [];
    }
  }

  /** Force a terminal when the engine ends without agent_settled. */
  finish(status: "completed" | "aborted" | "error", error?: RunError): DomainEvent[] {
    return this.settle(status, error);
  }

  /** Map an unexpected engine failure to a terminal error. */
  fail(code: string, message: string, retryable = true): DomainEvent[] {
    return this.settle("error", { code, message: concise(message, "internal error"), retryable });
  }

  private translateMessageUpdate(event: PiEventLike): DomainEvent[] {
    const update = event.assistantMessageEvent;
    if (!update) return [];

    if (update.type === "thinking_delta" && typeof update.delta === "string") {
      return [{ type: "thinking_delta", data: { delta: update.delta } }];
    }
    if (update.type === "text_delta" && typeof update.delta === "string") {
      return [{ type: "text_delta", data: { delta: update.delta } }];
    }
    if (update.type === "toolcall_delta" && typeof update.delta === "string") {
      const content = update.partial?.content?.[update.contentIndex ?? -1];
      if (content?.type === "toolCall" && typeof content.id === "string" && typeof content.name === "string") {
        return [
          {
            type: "tool_call_delta",
            data: { toolCallId: content.id, name: content.name, delta: update.delta },
          },
        ];
      }
    }
    if (update.type === "toolcall_end") {
      const toolCall = update.toolCall;
      if (toolCall && typeof toolCall.id === "string" && typeof toolCall.name === "string") {
        return [
          {
            type: "tool_call",
            data: { toolCallId: toolCall.id, name: toolCall.name, args: toolCall.arguments },
          },
        ];
      }
    }
    return [];
  }

  private translateMessageEnd(event: PiEventLike): DomainEvent[] {
    const message = event.message;
    if (!message || message.role !== "assistant") return [];

    let text = "";
    let thinking = "";
    const toolCalls: Array<{ toolCallId: string; name: string; args: unknown }> = [];

    for (const block of message.content ?? []) {
      if (block.type === "text" && typeof block.text === "string") {
        text += block.text;
      } else if (block.type === "thinking" && typeof block.thinking === "string") {
        thinking += block.thinking;
      } else if (block.type === "toolCall" && typeof block.id === "string" && typeof block.name === "string") {
        toolCalls.push({ toolCallId: block.id, name: block.name, args: block.arguments });
      }
    }

    const stopReason = (message.stopReason ?? "stop") as StopReason;
    this.hasErrorStop = stopReason === "error";
    if (this.hasErrorStop) {
      this.lastErrorMessage = message.errorMessage;
    }

    this.messageSeqInTurn += 1;
    return [
      {
        type: "message_end",
        data: {
          seqInTurn: this.messageSeqInTurn,
          role: "assistant",
          text,
          thinking,
          toolCalls,
          usage: normalizeUsage(message.usage),
          stopReason,
        },
      },
    ];
  }

  private translateToolProgress(event: PiEventLike): DomainEvent[] {
    const progress = event.partialResult?.details?.progress;
    if (!progress || typeof event.toolCallId !== "string" || typeof event.toolName !== "string") {
      return [];
    }

    if (progress.kind === "phase") {
      if (typeof progress.phase !== "string" || progress.phase.length === 0) return [];
      return [
        {
          type: "tool_progress",
          data: {
            toolCallId: event.toolCallId,
            name: event.toolName,
            kind: "phase",
            phase: progress.phase,
          },
        },
      ];
    }

    if (progress.kind === "stdout" || progress.kind === "stderr") {
      if (typeof progress.chunk !== "string" || progress.chunk.length === 0) return [];
      return [
        {
          type: "tool_progress",
          data: {
            toolCallId: event.toolCallId,
            name: event.toolName,
            kind: progress.kind,
            chunk: truncateUtf8(progress.chunk, TOOL_PROGRESS_MAX_BYTES).text,
          },
        },
      ];
    }

    return [];
  }

  private translateToolResult(event: PiEventLike): DomainEvent[] {
    if (typeof event.toolCallId !== "string" || typeof event.toolName !== "string") return [];

    const result = event.result as { content?: PiContentBlock[] } | undefined;
    const content = (result?.content ?? [])
      .filter((block) => block.type === "text" && typeof block.text === "string")
      .map((block) => block.text as string)
      .join("\n");
    const truncated = truncateUtf8(content, TOOL_RESULT_MAX_BYTES);

    return [
      {
        type: "tool_result",
        data: {
          toolCallId: event.toolCallId,
          name: event.toolName,
          isError: event.isError === true,
          durationMs: typeof event.durationMs === "number" ? event.durationMs : 0,
          content: truncated.text,
          truncated: truncated.truncated,
        },
      },
    ];
  }

  private translateCompaction(event: PiEventLike): DomainEvent[] {
    const reason = event.reason;
    if (reason !== "manual" && reason !== "threshold" && reason !== "overflow") return [];

    const result = event.result as
      | { tokensBefore?: number; estimatedTokensAfter?: number }
      | undefined;
    return [
      {
        type: "compaction",
        data: {
          reason,
          tokensBefore: typeof result?.tokensBefore === "number" ? result.tokensBefore : 0,
          tokensAfter:
            typeof result?.estimatedTokensAfter === "number" ? result.estimatedTokensAfter : null,
        },
      },
    ];
  }

  private translateRetry(event: PiEventLike): DomainEvent[] {
    if (
      typeof event.attempt !== "number" ||
      typeof event.maxAttempts !== "number" ||
      typeof event.delayMs !== "number"
    ) {
      return [];
    }
    return [
      {
        type: "retry",
        data: {
          attempt: event.attempt,
          maxAttempts: event.maxAttempts,
          delayMs: event.delayMs,
          message: concise(event.errorMessage, "retrying"),
        },
      },
    ];
  }

  private translateSettled(event: PiEventLike): DomainEvent[] {
    const status = event.aborted === true ? "aborted" : this.hasErrorStop ? "error" : "completed";
    const error =
      status === "error"
        ? {
            code: "MODEL_ERROR",
            message: concise(this.lastErrorMessage, "model call failed"),
            retryable: true,
          }
        : undefined;
    return this.settle(status, error);
  }

  private settle(
    status: "completed" | "aborted" | "error",
    error?: RunError,
  ): DomainEvent[] {
    if (this.settled) return [];
    this.settled = true;
    return [
      {
        type: "run_settled",
        data: {
          status,
          turnId: this.context.turnId,
          ...(error ? { error } : {}),
        },
      },
    ];
  }
}

function concise(message: string | undefined, fallback: string): string {
  if (message === undefined || message.trim().length === 0) return fallback;
  const firstLine = message.split(/\r?\n/, 1)[0]?.trim() ?? fallback;
  return truncateUtf8(firstLine.length > 0 ? firstLine : fallback, 300).text;
}

function normalizeUsage(usage: PiUsage | undefined): UsagePayload {
  return {
    input: usage?.input ?? 0,
    output: usage?.output ?? 0,
    cacheRead: usage?.cacheRead ?? 0,
    cacheWrite: usage?.cacheWrite ?? 0,
    reasoning: usage?.reasoning ?? 0,
    totalTokens: usage?.totalTokens ?? 0,
    cost: {
      input: usage?.cost?.input ?? 0,
      output: usage?.cost?.output ?? 0,
      cacheRead: usage?.cost?.cacheRead ?? 0,
      cacheWrite: usage?.cost?.cacheWrite ?? 0,
      total: usage?.cost?.total ?? 0,
    },
  };
}
