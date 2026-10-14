/**
 * Inline pi extensions: the two custom tools the model can call.
 *
 * - kb_search  -> Java business layer /api/kb/search (RAG retrieval, M1)
 * - run_code   -> sandbox execution service /api/exec  (FR-3, M1)
 *
 * Both endpoints are stubs in the Java skeleton and return 501, so tools
 * surface a clear error until the features land.
 */

import { Type } from "@earendil-works/pi-ai";
import { defineTool, type ExtensionAPI } from "@earendil-works/pi-coding-agent";
import type { GatewayConfig } from "./config.js";

interface KbSearchResponse {
  passages?: Array<{ documentId: string; seq: number; content: string; score: number }>;
  error?: string;
}

interface SandboxExecResponse {
  stdout: string;
  stderr: string;
  exitCode: number;
  durationMs: number;
  timedOut: boolean;
  error?: string;
}

function errorMessage(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

async function postJson<T>(url: string, body: unknown, signal: AbortSignal | undefined): Promise<T> {
  const res = await fetch(url, {
    method: "POST",
    headers: { "content-type": "application/json", accept: "application/json" },
    body: JSON.stringify(body),
    signal,
  });
  if (!res.ok) {
    const detail = await res.text().catch(() => "");
    throw new Error(`HTTP ${res.status}${detail ? `: ${detail.slice(0, 300)}` : ""}`);
  }
  return (await res.json()) as T;
}

export function kbSearchExtension(config: GatewayConfig) {
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
          try {
            const data = await postJson<KbSearchResponse>(
              `${config.javaApiUrl}/api/kb/search`,
              { query: params.query, topK: params.topK ?? 5 },
              signal,
            );
            const text =
              data.passages && data.passages.length > 0
                ? data.passages
                    .map(
                      (p, i) =>
                        `[${i + 1}] doc=${p.documentId} seq=${p.seq} score=${p.score.toFixed(3)}\n${p.content}`,
                    )
                    .join("\n\n")
                : "No matching passages.";
            return { content: [{ type: "text", text }], details: { count: data.passages?.length ?? 0 } };
          } catch (err) {
            return {
              isError: true,
              content: [{ type: "text", text: `kb_search failed: ${errorMessage(err)}` }],
              details: { count: 0 },
            };
          }
        },
      }),
    );
  };
}

export function runCodeExtension(config: GatewayConfig) {
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
        async execute(_toolCallId, params, signal) {
          try {
            const data = await postJson<SandboxExecResponse>(
              `${config.sandboxApiUrl}/api/exec`,
              {
                language: params.language ?? "python",
                code: params.code,
                timeoutMs: Math.min(params.timeoutMs ?? 30_000, 60_000),
              },
              signal,
            );
            const text = [
              data.timedOut ? "[execution timed out]" : `[exit code ${data.exitCode}] (${data.durationMs}ms)`,
              data.stdout ? `stdout:\n${data.stdout}` : "",
              data.stderr ? `stderr:\n${data.stderr}` : "",
            ]
              .filter(Boolean)
              .join("\n");
            return { content: [{ type: "text", text }], details: data };
          } catch (err) {
            return {
              isError: true,
              content: [{ type: "text", text: `run_code failed: ${errorMessage(err)}` }],
              details: {
                stdout: "",
                stderr: errorMessage(err),
                exitCode: -1,
                durationMs: 0,
                timedOut: false,
              },
            };
          }
        },
      }),
    );
  };
}
