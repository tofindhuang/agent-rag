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
        async execute(_toolCallId, params, signal) {
          const identity = getIdentity();
          if (!identity) {
            return {
              isError: true,
              content: [{ type: "text", text: "run_code failed: no active run" }],
              details: {
                stdout: "",
                stderr: "no active run",
                exitCode: -1,
                durationMs: 0,
                timedOut: false,
              },
            };
          }
          try {
            const data = await postJson<SandboxExecResponse>(
              `${config.sandboxApiUrl}/api/exec`,
              {
                language: params.language ?? "python",
                code: params.code,
                timeoutMs: Math.min(params.timeoutMs ?? 30_000, 60_000),
              },
              signal,
              buildToolHeaders(identity, _toolCallId, config.apiToken),
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
