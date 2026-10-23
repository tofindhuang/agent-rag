import { ApiError, toApiError } from "./errors";
import { authFetch } from "./http";

interface CreateSessionResponse {
  sessionId?: unknown;
}

export async function createChatSession(signal?: AbortSignal): Promise<string> {
  const response = await authFetch("/api/chat/sessions", { method: "POST", signal });
  if (!response.ok) throw await toApiError(response);

  const data = (await response.json()) as CreateSessionResponse;
  if (typeof data.sessionId !== "string" || data.sessionId.length === 0) {
    throw new ApiError("服务响应缺少 sessionId", response.status);
  }
  return data.sessionId;
}

/** Opens the SSE prompt stream; the caller owns the returned body. */
export async function openChatStream(
  sessionId: string,
  text: string,
  signal: AbortSignal,
): Promise<ReadableStream<Uint8Array>> {
  const response = await authFetch(
    `/api/chat/sessions/${encodeURIComponent(sessionId)}/messages`,
    {
      method: "POST",
      headers: { "content-type": "application/json", accept: "text/event-stream" },
      body: JSON.stringify({ text }),
      signal,
    },
  );
  if (!response.ok) throw await toApiError(response);
  if (!response.body) throw new ApiError("服务未返回流式响应", response.status);
  return response.body;
}
