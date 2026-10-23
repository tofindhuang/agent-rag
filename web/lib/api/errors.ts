export class ApiError extends Error {
  constructor(
    message: string,
    readonly status?: number,
    readonly code?: string,
    readonly retryable = false,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

interface ErrorEnvelope {
  error?: {
    code?: unknown;
    message?: unknown;
    retryable?: unknown;
  };
}

/** Maps a non-2xx response to the business layer's stable error envelope. */
export async function toApiError(response: Response): Promise<ApiError> {
  let envelope: ErrorEnvelope | null = null;
  try {
    envelope = (await response.json()) as ErrorEnvelope;
  } catch {
    // Non-JSON error body; fall back to the HTTP status.
  }

  const code = typeof envelope?.error?.code === "string" ? envelope.error.code : undefined;
  const message =
    typeof envelope?.error?.message === "string"
      ? envelope.error.message
      : `请求失败（HTTP ${response.status}）`;
  const retryable = envelope?.error?.retryable === true;

  return new ApiError(message, response.status, code, retryable);
}

/** User-facing message; diagnostics stay in console/network logs. */
export function userMessage(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  return "无法连接业务服务，请确认后端已启动。";
}
