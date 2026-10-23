import { ApiError, toApiError } from "./errors";
import { authFetch } from "./http";
import type { HistoryItem, MessagePage, SessionPage, SessionSummary } from "@/types/chat";

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

export async function listChatSessions(): Promise<SessionPage> {
  const response = await authFetch("/api/chat/sessions");
  if (!response.ok) throw await toApiError(response);
  return parseSessionPage(await response.json());
}

export async function renameChatSession(sessionId: string, title: string): Promise<void> {
  const response = await authFetch(`/api/chat/sessions/${encodeURIComponent(sessionId)}`, {
    method: "PATCH",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ title }),
  });
  if (!response.ok) throw await toApiError(response);
}

export async function archiveChatSession(sessionId: string): Promise<void> {
  const response = await authFetch(`/api/chat/sessions/${encodeURIComponent(sessionId)}`, {
    method: "DELETE",
  });
  if (!response.ok) throw await toApiError(response);
}

export async function fetchHistory(
  sessionId: string,
  beforeId?: number | null,
  limit = 50,
): Promise<MessagePage> {
  const params = new URLSearchParams({ limit: String(limit) });
  if (beforeId != null) params.set("beforeId", String(beforeId));
  const response = await authFetch(
    `/api/chat/sessions/${encodeURIComponent(sessionId)}/messages?${params.toString()}`,
  );
  if (!response.ok) throw await toApiError(response);
  return parseMessagePage(await response.json());
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

function record(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null ? (value as Record<string, unknown>) : null;
}

function badShape(): ApiError {
  return new ApiError("服务响应格式不正确");
}

function parseSessionPage(value: unknown): SessionPage {
  const body = record(value);
  if (!body || !Array.isArray(body.items)) throw badShape();
  const items: SessionSummary[] = body.items.map((raw) => {
    const item = record(raw);
    if (!item || typeof item.id !== "string" || typeof item.title !== "string") {
      throw badShape();
    }
    return {
      id: item.id,
      title: item.title,
      lastActiveAt: typeof item.lastActiveAt === "string" ? item.lastActiveAt : "",
      createdAt: typeof item.createdAt === "string" ? item.createdAt : "",
    };
  });
  return {
    items,
    nextCursor: typeof body.nextCursor === "string" ? body.nextCursor : null,
  };
}

function parseMessagePage(value: unknown): MessagePage {
  const body = record(value);
  if (!body || !Array.isArray(body.items)) throw badShape();
  const items: HistoryItem[] = body.items.map((raw) => {
    const item = record(raw);
    if (
      !item ||
      typeof item.id !== "number" ||
      (item.role !== "user" && item.role !== "assistant" && item.role !== "tool")
    ) {
      throw badShape();
    }
    const text = (field: unknown): string | null => (typeof field === "string" ? field : null);
    return {
      id: item.id,
      turnId: text(item.turnId) ?? "",
      ordinal: typeof item.ordinal === "number" ? item.ordinal : 0,
      role: item.role,
      content: text(item.content),
      thinking: text(item.thinking),
      status: text(item.status),
      deliveryStatus: text(item.deliveryStatus),
      createdAt: text(item.createdAt) ?? "",
    };
  });
  return {
    items,
    hasMore: body.hasMore === true,
    nextBeforeId: typeof body.nextBeforeId === "number" ? body.nextBeforeId : null,
  };
}
