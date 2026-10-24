import { setTimeout as delay } from "node:timers/promises";

import { parseSse, type SseFrame } from "./sse.js";

export interface LoadTestClientOptions {
  baseUrl: string;
  fetchImpl?: typeof fetch;
}

export interface ProviderModelInput {
  modelId: string;
  displayName?: string;
  contextWindow?: number;
  reasoning?: boolean;
  vision?: boolean;
}

export interface CreateProviderInput {
  name: string;
  api: "openai-completions" | "anthropic-messages";
  baseUrl: string;
  apiKey: string;
  models: ProviderModelInput[];
}

export interface CreateSessionInput {
  title?: string;
  modelId?: number;
  useSystemDefault?: boolean;
  kbId?: string;
  noKnowledgeBase?: boolean;
}

export interface ProviderModelView {
  id: number;
  modelId: string;
  displayName: string;
  contextWindow?: number | null;
  reasoning?: boolean;
  vision?: boolean;
}

export interface ProviderView {
  id: number;
  name: string;
  api: string;
  baseUrl: string;
  apiKeyHint: string;
  models: ProviderModelView[];
}

export interface KnowledgeBaseView {
  id: string;
  name: string;
  isDefault: boolean;
  documentCount: number;
}

export interface KbDocumentCreated {
  id: string;
  status: string;
}

export interface KbDocumentView {
  id: string;
  status: string;
}

export interface MessageSummary {
  id: number;
  turnId: string | null;
  ordinal: number;
  role: string;
  content: string;
  status: string;
  deliveryStatus?: string;
}

export interface MessagePage {
  items: MessageSummary[];
  hasMore: boolean;
  nextBeforeId: number | null;
}

export interface ModelCatalog {
  providers: ProviderView[];
  defaultModelId: number | null;
  systemDefault: { label: string } | null;
}

export interface SendMessageInput {
  text: string;
  turnId: string;
}

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly retryable: boolean;

  constructor(status: number, code: string, message: string, retryable: boolean) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.retryable = retryable;
  }
}

interface ErrorEnvelope {
  error?: { code?: string; message?: string; retryable?: boolean };
}

/**
 * Thin client over the public Java HTTP API — the same seam the web app uses.
 * The harness never calls gateway internals directly.
 */
export class LoadTestClient {
  private readonly baseUrl: string;
  private readonly fetchImpl: typeof fetch;

  constructor(options: LoadTestClientOptions) {
    this.baseUrl = options.baseUrl.replace(/\/$/, "");
    this.fetchImpl = options.fetchImpl ?? fetch;
  }

  register(email: string, password: string): Promise<{ userId: number; email: string }> {
    return this.requestJson("POST", "/api/auth/register", undefined, { email, password });
  }

  login(
    email: string,
    password: string,
  ): Promise<{ accessToken: string; tokenType: string; expiresIn: number }> {
    return this.requestJson("POST", "/api/auth/login", undefined, { email, password });
  }

  createProvider(token: string, input: CreateProviderInput): Promise<{ id: number }> {
    return this.requestJson("POST", "/api/models/providers", token, input);
  }

  createSession(token: string, input: CreateSessionInput): Promise<{ sessionId: string }> {
    return this.requestJson("POST", "/api/chat/sessions", token, input);
  }

  listModels(token: string): Promise<ModelCatalog> {
    return this.requestJson("GET", "/api/models", token);
  }

  listMessages(
    token: string,
    sessionId: string,
    options: { beforeId?: number; limit?: number } = {},
  ): Promise<MessagePage> {
    const params = new URLSearchParams();
    if (options.beforeId !== undefined) params.set("beforeId", String(options.beforeId));
    if (options.limit !== undefined) params.set("limit", String(options.limit));
    const query = params.size > 0 ? `?${params.toString()}` : "";
    return this.requestJson(
      "GET",
      `/api/chat/sessions/${sessionId}/messages${query}`,
      token,
    );
  }

  createKnowledgeBase(token: string, name: string): Promise<KnowledgeBaseView> {
    return this.requestJson("POST", "/api/kb/knowledge-bases", token, { name });
  }

  async uploadDocument(
    token: string,
    kbId: string,
    filename: string,
    content: string,
  ): Promise<KbDocumentCreated> {
    const form = new FormData();
    form.append(
      "file",
      new Blob([content], {
        type: filename.endsWith(".txt") ? "text/plain" : "text/markdown",
      }),
      filename,
    );
    const response = await this.fetchImpl(
      `${this.baseUrl}/api/kb/documents?kbId=${encodeURIComponent(kbId)}`,
      {
        method: "POST",
        headers: { authorization: `Bearer ${token}` },
        body: form,
      },
    );
    const text = await response.text();
    if (!response.ok) throw this.errorFrom(response.status, text);
    return JSON.parse(text) as KbDocumentCreated;
  }

  getDocument(token: string, documentId: string): Promise<KbDocumentView> {
    return this.requestJson("GET", `/api/kb/documents/${documentId}`, token);
  }

  async waitForDocumentReady(
    token: string,
    documentId: string,
    options: { timeoutMs?: number; intervalMs?: number } = {},
  ): Promise<KbDocumentView> {
    const timeoutMs = options.timeoutMs ?? 120_000;
    const intervalMs = options.intervalMs ?? 250;
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      const document = await this.getDocument(token, documentId);
      if (document.status === "ready") return document;
      if (document.status === "failed") {
        throw new ApiError(500, "KB_INGEST_FAILED", `document ${documentId} failed`, false);
      }
      if (Date.now() > deadline) {
        throw new ApiError(
          504,
          "KB_INGEST_TIMEOUT",
          `document ${documentId} not ready after ${timeoutMs}ms`,
          true,
        );
      }
      await delay(intervalMs);
    }
  }

  async *sendMessage(
    token: string,
    sessionId: string,
    input: SendMessageInput,
  ): AsyncGenerator<SseFrame> {
    const response = await this.fetchImpl(
      `${this.baseUrl}/api/chat/sessions/${sessionId}/messages`,
      {
        method: "POST",
        headers: {
          accept: "text/event-stream",
          "content-type": "application/json",
          authorization: `Bearer ${token}`,
        },
        body: JSON.stringify({ text: input.text, turnId: input.turnId }),
      },
    );
    if (!response.ok) {
      throw this.errorFrom(response.status, await response.text());
    }
    if (!response.body) {
      throw new ApiError(response.status, "EMPTY_STREAM", "SSE response has no body", false);
    }
    yield* parseSse(response.body as unknown as AsyncIterable<Uint8Array>);
  }

  private async requestJson<T>(
    method: string,
    path: string,
    token?: string,
    body?: unknown,
  ): Promise<T> {
    const headers: Record<string, string> = {};
    if (body !== undefined) headers["content-type"] = "application/json";
    if (token) headers.authorization = `Bearer ${token}`;
    const response = await this.fetchImpl(`${this.baseUrl}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const text = await response.text();
    if (!response.ok) throw this.errorFrom(response.status, text);
    return (text ? JSON.parse(text) : undefined) as T;
  }

  private errorFrom(status: number, body: string): ApiError {
    let envelope: ErrorEnvelope = {};
    try {
      envelope = JSON.parse(body) as ErrorEnvelope;
    } catch {
      // non-JSON error body; keep the status as the only signal
    }
    return new ApiError(
      status,
      envelope.error?.code ?? `HTTP_${status}`,
      envelope.error?.message ?? `request failed with status ${status}`,
      envelope.error?.retryable ?? false,
    );
  }
}
