/**
 * Inline pi extensions: the two custom tools the model can call.
 *
 * - kb_search  -> Java business layer /api/kb/search (RAG retrieval, M1)
 * - run_code   -> sandbox execution service /api/exec  (FR-3, M1)
 *
 * kb_search returns JSON; run_code consumes the Java SSE stream
 * (started/phase/stdout/stderr/result) and forwards progress updates.
 */

import { Type } from "@earendil-works/pi-ai";
import { defineTool, type ExtensionAPI } from "@earendil-works/pi-coding-agent";
import type { GatewayConfig } from "./config.js";
import { SseFrameParser, type SseFrame } from "./sse.js";
import type { RunIdentity } from "./identity.js";
import { buildToolHeaders } from "./tool-headers.js";

interface KbPassage {
  passageId: string;
  documentId: string;
  seq: number;
  page?: number | null;
  title?: string;
  content: string;
  score: number;
  reranked?: boolean;
}

interface KbSearchResponse {
  passages?: KbPassage[];
  error?: string;
}

interface SandboxExecDetails {
  execId?: string;
  exitCode?: number;
  durationMs?: number;
  timedOut?: boolean;
  truncated?: boolean;
  errorCode?: string;
  progress?: {
    kind: "phase" | "stdout" | "stderr";
    phase?: string;
    chunk?: string;
  };
}

interface ExecResultFrame {
  exitCode: number;
  durationMs: number;
  timedOut: boolean;
  truncated: boolean;
}

interface RunCodeParams {
  language?: string;
  code: string;
  timeoutMs?: number;
}

function errorMessage(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

async function postJson<T>(
  url: string,
  body: unknown,
  signal: AbortSignal | undefined,
  headers: Record<string, string>,
): Promise<T> {
  const res = await fetch(url, {
    method: "POST",
    headers: { "content-type": "application/json", accept: "application/json", ...headers },
    body: JSON.stringify(body),
    signal,
  });
  if (!res.ok) {
    const detail = await res.text().catch(() => "");
    throw new Error(`HTTP ${res.status}${detail ? `: ${detail.slice(0, 300)}` : ""}`);
  }
  return (await res.json()) as T;
}

export function kbSearchExtension(
  config: GatewayConfig,
  getIdentity: () => RunIdentity | null,
) {
  return (pi: ExtensionAPI): void => {
    pi.registerTool(
      defineTool({
        name: "kb_search",
        label: "Knowledge base search",
        description:
          "Search the user's knowledge base. Returns the top matched passages with document " +
          "references. Use for any question that may relate to the user's documents.",
        parameters: Type.Object({
          query: Type.String({ description: "Search query in the user's language" }),
          topK: Type.Optional(Type.Number({ description: "Number of passages to return (default 5)" })),
        }),
        async execute(_toolCallId, params, signal) {
          const identity = getIdentity();
          if (!identity) {
            return {
              isError: true,
              content: [{ type: "text", text: "kb_search failed: no active run" }],
              details: { count: 0, passages: [] },
            };
          }
          try {
            const data = await postJson<KbSearchResponse>(
              `${config.javaApiUrl}/api/kb/search`,
              { query: params.query, topK: params.topK ?? 5 },
              signal,
              buildToolHeaders(identity, _toolCallId, config.apiToken),
            );
            const passages = data.passages ?? [];
            const text =
              passages.length > 0
                ? passages
                    .map((p, i) => {
                      const title = p.title ? `《${p.title}》 ` : "";
                      const page = p.page != null ? ` page=${p.page}` : "";
                      return `[${i + 1}] ${title}doc=${p.documentId} seq=${p.seq}${page} score=${p.score.toFixed(3)}\n${p.content}`;
                    })
                    .join("\n\n")
                : "No matching passages.";
            return {
              content: [{ type: "text", text }],
              details: { count: passages.length, passages },
            };
          } catch (err) {
            return {
              isError: true,
              content: [{ type: "text", text: `kb_search failed: ${errorMessage(err)}` }],
              details: { count: 0, passages: [] },
            };
          }
        },
      }),
    );
  };
}

export function runCodeExtension(
  config: GatewayConfig,
  getIdentity: () => RunIdentity | null,
) {
  return (pi: ExtensionAPI): void => {
    pi.registerTool(
      defineTool({
        name: "run_code",
        label: "Run code in sandbox",
        description:
          "Execute code in an isolated sandbox (no network access, resource-limited, " +
          "time-limited). Currently supports Python. Returns stdout, stderr, exit code.",
        parameters: Type.Object({
          language: Type.Optional(Type.String({ description: "Programming language, default 'python'" })),
          code: Type.String({ description: "Full source code to execute" }),
          timeoutMs: Type.Optional(
            Type.Number({ description: "Execution timeout in milliseconds (default 30000, max 60000)" }),
          ),
        }),
        async execute(_toolCallId, params, signal, onUpdate) {
          const identity = getIdentity();
          if (!identity) {
            return errorResult("run_code failed: no active run");
          }
          try {
            const outcome = await runInSandbox(
              config,
              identity,
              _toolCallId,
              params,
              signal,
              onUpdate,
            );
            return {
              content: [{ type: "text", text: outcome.text }],
              details: outcome.details,
            };
          } catch (err) {
            const aborted = isAbortError(err);
            const code = err instanceof SandboxHttpError ? err.code : undefined;
            const message = aborted ? "run_code aborted" : `run_code failed: ${errorMessage(err)}`;
            return errorResult(message, code);
          }
        },
      }),
    );
  };
}

class SandboxHttpError extends Error {
  constructor(
    readonly code: string,
    message: string,
  ) {
    super(message);
    this.name = "SandboxHttpError";
  }
}

class CappedOutput {
  private bytes = 0;
  private readonly out: string[] = [];
  private readonly err: string[] = [];

  constructor(private readonly limit: number) {}

  append(stream: "stdout" | "stderr", chunk: string): void {
    if (this.bytes >= this.limit) return;
    let remaining = this.limit - this.bytes;
    let prefix = "";
    for (const char of chunk) {
      const size = Buffer.byteLength(char, "utf8");
      if (size > remaining) break;
      prefix += char;
      remaining -= size;
    }
    if (!prefix) return;
    this.bytes += Buffer.byteLength(prefix, "utf8");
    (stream === "stdout" ? this.out : this.err).push(prefix);
  }

  stdout(): string {
    return this.out.join("");
  }

  stderr(): string {
    return this.err.join("");
  }
}

async function runInSandbox(
  config: GatewayConfig,
  identity: RunIdentity,
  toolCallId: string,
  params: RunCodeParams,
  signal: AbortSignal | undefined,
  onUpdate:
    | ((partial: {
        content: [];
        details: SandboxExecDetails;
      }) => void)
    | undefined,
): Promise<{ text: string; details: SandboxExecDetails }> {
  const res = await fetch(`${config.sandboxApiUrl}/api/exec`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      accept: "text/event-stream",
      ...buildToolHeaders(identity, toolCallId, config.apiToken),
    },
    body: JSON.stringify({
      language: params.language ?? "python",
      code: params.code,
      timeoutMs: Math.min(params.timeoutMs ?? 30_000, 60_000),
    }),
    signal,
  });
  if (!res.ok) {
    throw await sandboxHttpError(res);
  }
  if (!res.body) {
    throw new Error("run_code response has no body");
  }

  const reader = res.body.getReader();
  const parser = new SseFrameParser();
  const output = new CappedOutput(64 * 1024);
  let execId: string | undefined;
  let result: ExecResultFrame | undefined;

  const handle = (frame: SseFrame): void => {
    switch (frame.event) {
      case "started": {
        const data = JSON.parse(frame.data) as { execId?: string };
        execId = data.execId;
        break;
      }
      case "phase": {
        const data = JSON.parse(frame.data) as { phase?: string };
        if (data.phase) {
          onUpdate?.({
            content: [],
            details: { progress: { kind: "phase", phase: data.phase } },
          });
        }
        break;
      }
      case "stdout": {
        const data = JSON.parse(frame.data) as { chunk?: string };
        if (typeof data.chunk === "string") {
          output.append("stdout", data.chunk);
          onUpdate?.({
            content: [],
            details: { progress: { kind: "stdout", chunk: data.chunk } },
          });
        }
        break;
      }
      case "stderr": {
        const data = JSON.parse(frame.data) as { chunk?: string };
        if (typeof data.chunk === "string") {
          output.append("stderr", data.chunk);
          onUpdate?.({
            content: [],
            details: { progress: { kind: "stderr", chunk: data.chunk } },
          });
        }
        break;
      }
      case "result": {
        result = JSON.parse(frame.data) as ExecResultFrame;
        break;
      }
      default:
        break;
    }
  };

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    for (const frame of parser.push(value)) handle(frame);
  }
  for (const frame of parser.end()) handle(frame);

  if (!result) {
    throw new Error("run_code stream ended without a result frame");
  }
  return {
    text: formatExecText(result, output),
    details: {
      execId,
      exitCode: result.exitCode,
      durationMs: result.durationMs,
      timedOut: result.timedOut,
      truncated: result.truncated,
    },
  };
}

function formatExecText(result: ExecResultFrame, output: CappedOutput): string {
  const lines = [
    result.timedOut
      ? `[execution timed out] (${result.durationMs}ms)`
      : `[exit code ${result.exitCode}] (${result.durationMs}ms)`,
  ];
  if (result.truncated) lines.push("[output truncated]");
  const stdout = output.stdout();
  const stderr = output.stderr();
  if (stdout) lines.push(`stdout:\n${stdout}`);
  if (stderr) lines.push(`stderr:\n${stderr}`);
  return lines.join("\n");
}

async function sandboxHttpError(res: Response): Promise<SandboxHttpError> {
  const text = await res.text().catch(() => "");
  try {
    const body = JSON.parse(text) as { error?: { code?: string; message?: string } };
    if (body.error?.code) {
      return new SandboxHttpError(
        body.error.code,
        `${body.error.code}: ${body.error.message ?? "request failed"}`,
      );
    }
  } catch {
    // Fall through to the generic HTTP error.
  }
  return new SandboxHttpError(
    "HTTP_ERROR",
    `HTTP ${res.status}${text ? `: ${text.slice(0, 300)}` : ""}`,
  );
}

function errorResult(message: string, errorCode?: string): {
  isError: true;
  content: [{ type: "text"; text: string }];
  details: SandboxExecDetails;
} {
  return {
    isError: true,
    content: [{ type: "text", text: message }],
    details: {
      exitCode: -1,
      durationMs: 0,
      timedOut: false,
      truncated: false,
      errorCode,
    },
  };
}

function isAbortError(err: unknown): boolean {
  return typeof err === "object" && err !== null && (err as { name?: string }).name === "AbortError";
}
