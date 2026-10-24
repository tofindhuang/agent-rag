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
  ): Promise<{ messages: unknown[] }> {
    return this.requestJson("GET", `/api/chat/sessions/${sessionId}/messages`, token);
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
