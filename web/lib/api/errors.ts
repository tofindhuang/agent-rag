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

const CODE_MESSAGES: Record<string, string> = {
  INVALID_REQUEST: "请求参数有误，请检查后重试。",
  UNAUTHORIZED: "邮箱或密码不正确。",
  EMAIL_ALREADY_EXISTS: "该邮箱已注册。",
  FORBIDDEN: "没有权限执行该操作。",
  USER_MISMATCH: "没有权限访问该会话。",
  SESSION_NOT_FOUND: "会话不存在或已删除。",
  SESSION_CONFLICT: "会话冲突，请刷新后重试。",
  RUN_IN_PROGRESS: "当前会话还有回复在进行中。",
  NO_ACTIVE_RUN: "当前没有进行中的回复。",
  UPSTREAM_UNAVAILABLE: "上游服务暂不可用，请稍后重试。",
  UPSTREAM_ERROR: "上游服务返回错误，请稍后重试。",
  MODEL_NOT_CONFIGURED: "还没有可用的模型，请先在设置中添加供应商。",
  MODEL_UNAVAILABLE: "当前模型不可用，请切换模型后重试。",
  PROVIDER_UNREACHABLE: "无法连接模型供应商，请检查 Base URL 与网络。",
  PROVIDER_AUTH_FAILED: "模型供应商拒绝了 API Key，请检查后重填。",
  PROVIDER_PROBE_FAILED: "模型供应商返回异常，请检查配置后重试。",
  NOT_IMPLEMENTED: "该功能尚未开放。",
  INTERNAL: "服务内部错误，请稍后重试。",
};

/** User-facing message; diagnostics stay in console/network logs. */
export function userMessage(error: unknown): string {
  if (error instanceof ApiError) {
    const mapped = error.code ? CODE_MESSAGES[error.code] : undefined;
    return mapped ?? error.message;
  }
  return "无法连接业务服务，请确认后端已启动。";
}
