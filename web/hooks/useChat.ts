"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import {
  archiveChatSession,
  createChatSession,
  fetchHistory,
  listChatSessions,
  openChatStream,
  permanentDeleteChatSession,
  renameChatSession,
  restoreChatSession,
  switchChatSessionModel,
  switchSessionKnowledgeBase,
} from "@/lib/api/chat";
import { ApiError, userMessage } from "@/lib/api/errors";
import { parseSse } from "@/lib/sse/parse-sse";
import type { ChatMessage, HistoryItem, SessionSummary, ToolActivity } from "@/types/chat";
import type { ContextUsageData, MessageEndData } from "@/types/gateway";
import { parseCitationPassages, parseGatewayEvent } from "@/types/gateway";
import type { CreateSessionOptions } from "@/lib/api/chat";

const HISTORY_PAGE_SIZE = 50;

/** Server-side label written when the gateway never reports a model name. */
const SYSTEM_DEFAULT_SENTINEL = "系统默认";

/** Which action produced the current error; each surface shows only its own. */
type ErrorSource = "send" | "create" | "switch" | "other";

function toolNameFromDetails(details: unknown): string {
  if (details !== null && typeof details === "object" && "toolName" in details) {
    const name = (details as { toolName?: unknown }).toolName;
    if (typeof name === "string" && name.trim().length > 0) return name;
  }
  return "工具结果";
}

function newAssistant(): ChatMessage {
  return { role: "assistant", content: "", thinking: "", tools: [], status: "streaming" };
}

function newUser(content: string): ChatMessage {
  return { role: "user", content, thinking: "", tools: [] };
}

function toHistoryMessages(items: HistoryItem[]): ChatMessage[] {
  const messages: ChatMessage[] = [];
  for (const item of items) {
    if (item.role === "tool") {
      const tool: ToolActivity = {
        toolCallId: `history-${item.id}`,
        name: toolNameFromDetails(item.details),
        argsText: "",
        output: item.content ?? "",
        status: item.status === "error" ? "error" : "done",
        passages: parseCitationPassages(item.details),
      };
      const previous = messages[messages.length - 1];
      if (previous && previous.role === "assistant") {
        previous.tools = [...previous.tools, tool];
      } else {
        // A page boundary can start mid-turn; keep the tool row visible.
        messages.push({
          role: "assistant",
          content: "",
          thinking: "",
          tools: [tool],
          status: "complete",
          historyId: item.id,
        });
      }
      continue;
    }
    if (item.role === "user") {
      messages.push({
        role: "user",
        content: item.content ?? "",
        thinking: "",
        tools: [],
        historyId: item.id,
      });
      continue;
    }
    const status =
      item.status === "partial" ? "partial" : item.status === "error" ? "error" : "complete";
    messages.push({
      role: "assistant",
      content: item.content ?? "",
      thinking: item.thinking ?? "",
      tools: [],
      status,
      historyId: item.id,
    });
  }
  return messages;
}

/**
 * Owns chat state, the session sidebar data and the contract v1 SSE lifecycle.
 * Session/history writes go through lib/api; components only render.
 */
export function useChat() {
  const [sessions, setSessions] = useState<SessionSummary[]>([]);
  const [sessionsLoading, setSessionsLoading] = useState(true);
  const [currentSessionId, setCurrentSessionId] = useState<string | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [contextUsage, setContextUsage] = useState<ContextUsageData | null>(null);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [errorCode, setErrorCode] = useState<string | null>(null);
  const [errorSource, setErrorSource] = useState<ErrorSource | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [historyHasMore, setHistoryHasMore] = useState(false);
  const [nextBeforeId, setNextBeforeId] = useState<number | null>(null);
  const [archivedSessions, setArchivedSessions] = useState<SessionSummary[]>([]);
  const [archivedLoading, setArchivedLoading] = useState(false);
  const [archivedHasMore, setArchivedHasMore] = useState(false);
  const [archivedCursor, setArchivedCursor] = useState<string | null>(null);

  const abortRef = useRef<AbortController | null>(null);
  const fail = useCallback((cause: unknown, source: ErrorSource = "other") => {
    console.error(cause);
    setError(userMessage(cause));
    setErrorCode(cause instanceof ApiError ? (cause.code ?? null) : null);
    setErrorSource(source);
  }, []);
  const clearError = useCallback(() => {
    setError(null);
    setErrorCode(null);
    setErrorSource(null);
  }, []);
  const currentIdRef = useRef<string | null>(null);
  const initialized = useRef(false);

  const loadHistory = useCallback(async (sessionId: string) => {
    setHistoryLoading(true);
    setError(null);
    try {
      const page = await fetchHistory(sessionId, null, HISTORY_PAGE_SIZE);
      if (currentIdRef.current !== sessionId) return;
      setMessages(toHistoryMessages(page.items));
      setHistoryHasMore(page.hasMore);
      setNextBeforeId(page.nextBeforeId);
    } catch (cause: unknown) {
      if (currentIdRef.current !== sessionId) return;
      fail(cause);
    } finally {
      if (currentIdRef.current === sessionId) setHistoryLoading(false);
    }
  }, [fail]);

  const selectSession = useCallback(
    async (sessionId: string) => {
      if (currentIdRef.current === sessionId) return;
      abortRef.current?.abort();
      abortRef.current = null;
      setBusy(false);
      currentIdRef.current = sessionId;
      setCurrentSessionId(sessionId);
      setMessages([]);
      setContextUsage(null);
      setHistoryHasMore(false);
      setNextBeforeId(null);
      setNotice(null);
      clearError();
      await loadHistory(sessionId);
    },
    [clearError, loadHistory],
  );

  const refreshSessions = useCallback(async (): Promise<SessionSummary[]> => {
    const page = await listChatSessions("active");
    setSessions(page.items);
    return page.items;
  }, []);

  /** Fresh first page of archived sessions; called when the tab opens. */
  const loadArchived = useCallback(async () => {
    setArchivedLoading(true);
    try {
      const page = await listChatSessions("archived");
      setArchivedSessions(page.items);
      setArchivedHasMore(page.nextCursor != null);
      setArchivedCursor(page.nextCursor);
    } catch (cause: unknown) {
      fail(cause);
    } finally {
      setArchivedLoading(false);
    }
  }, [fail]);

  const loadMoreArchived = useCallback(async () => {
    if (archivedLoading || archivedCursor == null) return;
    setArchivedLoading(true);
    try {
      const page = await listChatSessions("archived", archivedCursor);
      setArchivedSessions((previous) => [...previous, ...page.items]);
      setArchivedHasMore(page.nextCursor != null);
      setArchivedCursor(page.nextCursor);
    } catch (cause: unknown) {
      fail(cause);
    } finally {
      setArchivedLoading(false);
    }
  }, [archivedCursor, archivedLoading, fail]);

  useEffect(() => {
    // StrictMode runs effects twice in development. The guard keeps one
    // bootstrap in flight; it must not be aborted by the fake cleanup, or the
    // sidebar would stay in "loading" forever.
    if (initialized.current) return;
    initialized.current = true;

    void (async () => {
      try {
        const existing = await refreshSessions();
        if (existing.length > 0) {
          await selectSession(existing[0].id);
        } else {
          const sessionId = await createChatSession();
          await refreshSessions();
          await selectSession(sessionId);
        }
      } catch (cause: unknown) {
        fail(cause);
      } finally {
        setSessionsLoading(false);
      }
    })();
  }, [fail, refreshSessions, selectSession]);

  useEffect(() => () => abortRef.current?.abort(), []);

  const createSession = useCallback(
    async (options?: CreateSessionOptions): Promise<boolean> => {
      clearError();
      try {
        const sessionId = await createChatSession(options);
        await refreshSessions();
        await selectSession(sessionId);
        return true;
      } catch (cause: unknown) {
        fail(cause, "create");
        return false;
      }
    },
    [clearError, fail, refreshSessions, selectSession],
  );

  /** Fault-recovery switch (ADR-0010); the next prompt uses the new model. */
  const switchModel = useCallback(
    async (sessionId: string, modelId: number | null): Promise<boolean> => {
      try {
        await switchChatSessionModel(sessionId, modelId);
        await refreshSessions();
        clearError();
        setNotice("已切换模型，重新发送消息即可使用新模型");
        return true;
      } catch (cause: unknown) {
        fail(cause, "switch");
        return false;
      }
    },
    [clearError, fail, refreshSessions],
  );

  const renameSession = useCallback(
    async (sessionId: string, title: string) => {
      const trimmed = title.trim();
      if (trimmed.length === 0) return;
      try {
        await renameChatSession(sessionId, trimmed);
        setSessions((previous) =>
          previous.map((session) =>
            session.id === sessionId ? { ...session, title: trimmed } : session,
          ),
        );
      } catch (cause: unknown) {
        fail(cause);
      }
    },
    [fail],
  );

  const archiveSession = useCallback(
    async (sessionId: string) => {
      try {
        await archiveChatSession(sessionId);
        const remaining = await refreshSessions();
        if (currentIdRef.current !== sessionId) return;
        if (remaining.length > 0) {
          currentIdRef.current = null;
          await selectSession(remaining[0].id);
        } else {
          currentIdRef.current = null;
          await createSession();
        }
      } catch (cause: unknown) {
        fail(cause);
      }
    },
    [createSession, fail, refreshSessions, selectSession],
  );

  const restoreSession = useCallback(
    async (sessionId: string) => {
      try {
        await restoreChatSession(sessionId);
        setArchivedSessions((previous) => previous.filter((session) => session.id !== sessionId));
        await refreshSessions();
        clearError();
        setNotice("会话已恢复");
      } catch (cause: unknown) {
        fail(cause);
      }
    },
    [clearError, fail, refreshSessions],
  );

  const permanentDeleteSession = useCallback(
    async (sessionId: string) => {
      try {
        await permanentDeleteChatSession(sessionId);
        setArchivedSessions((previous) => previous.filter((session) => session.id !== sessionId));
        // The main area must not keep rendering a purged session.
        if (currentIdRef.current === sessionId) {
          abortRef.current?.abort();
          abortRef.current = null;
          currentIdRef.current = null;
          setCurrentSessionId(null);
          setMessages([]);
          setHistoryHasMore(false);
          setNextBeforeId(null);
          setBusy(false);
        }
        clearError();
        setNotice("会话已彻底删除");
      } catch (cause: unknown) {
        fail(cause);
      }
    },
    [clearError, fail],
  );

  const loadOlder = useCallback(async () => {
    const sessionId = currentIdRef.current;
    if (!sessionId || nextBeforeId == null || historyLoading) return;
    setHistoryLoading(true);
    try {
      const page = await fetchHistory(sessionId, nextBeforeId, HISTORY_PAGE_SIZE);
      if (currentIdRef.current !== sessionId) return;
      setMessages((previous) => [...toHistoryMessages(page.items), ...previous]);
      setHistoryHasMore(page.hasMore);
      setNextBeforeId(page.nextBeforeId);
    } catch (cause: unknown) {
      if (currentIdRef.current !== sessionId) return;
      fail(cause);
    } finally {
      if (currentIdRef.current === sessionId) setHistoryLoading(false);
    }
  }, [fail, historyLoading, nextBeforeId]);

  const updateAssistant = useCallback((update: (message: ChatMessage) => ChatMessage) => {
    setMessages((previous) => {
      const last = previous[previous.length - 1];
      if (!last || last.role !== "assistant") {
        return [...previous, update(newAssistant())];
      }
      return [...previous.slice(0, -1), update(last)];
    });
  }, []);

  const appendText = useCallback(
    (delta: string) => updateAssistant((message) => ({ ...message, content: message.content + delta })),
    [updateAssistant],
  );

  const appendThinking = useCallback(
    (delta: string) =>
      updateAssistant((message) => ({ ...message, thinking: message.thinking + delta })),
    [updateAssistant],
  );

  const updateTool = useCallback(
    (
      toolCallId: string,
      name: string,
      update: (tool: ToolActivity) => ToolActivity,
    ) => {
      updateAssistant((message) => {
        const tools = [...message.tools];
        const index = tools.findIndex((tool) => tool.toolCallId === toolCallId);
        const current: ToolActivity =
          index >= 0
            ? tools[index]
            : { toolCallId, name, argsText: "", output: "", status: "calling" };
        const next = update(current);
        if (index >= 0) tools[index] = next;
        else tools.push(next);
        return { ...message, tools };
      });
    },
    [updateAssistant],
  );

  const finalizeMessage = useCallback(
    (data: MessageEndData) => {
      updateAssistant((message) => {
        const tools = [...message.tools];
        for (const call of data.toolCalls) {
          if (!tools.some((tool) => tool.toolCallId === call.toolCallId)) {
            tools.push({
              toolCallId: call.toolCallId,
              name: call.name,
              argsText: call.args === undefined ? "" : JSON.stringify(call.args),
              output: "",
              status: "running",
            });
          }
        }
        return {
          ...message,
          content: data.text.length > 0 ? data.text : message.content,
          thinking: data.thinking.length > 0 ? data.thinking : message.thinking,
          tools,
        };
      });
    },
    [updateAssistant],
  );

  const send = useCallback(async (): Promise<void> => {
    const text = input.trim();
    const sessionId = currentIdRef.current;
    if (!sessionId || text.length === 0 || busy) return;

    setInput("");
    clearError();
    setNotice(null);
    setBusy(true);
    setMessages((previous) => [...previous, newUser(text)]);

    const controller = new AbortController();
    abortRef.current = controller;
    let settled = false;
    let opened = false;

    try {
      const stream = await openChatStream(sessionId, text, controller.signal);
      opened = true;
      for await (const frame of parseSse(stream)) {
        const event = parseGatewayEvent(frame.event, frame.data);
        if (!event) continue;

        switch (event.type) {
          case "run_started":
            setNotice(null);
            break;
          case "thinking_delta":
            appendThinking(event.data.delta);
            break;
          case "text_delta":
            appendText(event.data.delta);
            break;
          case "tool_call_delta":
            updateTool(event.data.toolCallId, event.data.name, (tool) => ({
              ...tool,
              name: event.data.name,
              argsText: tool.argsText + event.data.delta,
              status: "calling",
            }));
            break;
          case "tool_call":
            updateTool(event.data.toolCallId, event.data.name, (tool) => ({
              ...tool,
              name: event.data.name,
              argsText:
                event.data.args === undefined ? tool.argsText : JSON.stringify(event.data.args),
              status: "running",
            }));
            break;
          case "tool_progress":
            updateTool(event.data.toolCallId, event.data.name, (tool) => ({
              ...tool,
              phase: event.data.kind === "phase" ? event.data.phase ?? tool.phase : tool.phase,
              output:
                event.data.kind === "stdout" || event.data.kind === "stderr"
                  ? tool.output + (event.data.chunk ?? "")
                  : tool.output,
              status: "running",
            }));
            break;
          case "tool_result":
            updateTool(event.data.toolCallId, event.data.name, (tool) => ({
              ...tool,
              output: event.data.content.length > 0 ? event.data.content : tool.output,
              status: event.data.isError ? "error" : "done",
              passages: parseCitationPassages(event.data.details),
            }));
            break;
          case "message_end":
            finalizeMessage(event.data);
            break;
          case "context_usage":
            setContextUsage(event.data);
            break;
          case "compaction":
            setNotice("上下文已压缩");
            break;
          case "retry":
            setNotice(`模型限流，正在重试（${event.data.attempt}/${event.data.maxAttempts}）`);
            break;
          case "run_settled":
            settled = true;
            updateAssistant((message) => ({
              ...message,
              status:
                event.data.status === "completed"
                  ? "complete"
                  : event.data.status === "aborted"
                    ? "aborted"
                    : "error",
            }));
            if (event.data.status === "error") {
              setError(event.data.error?.message ?? "模型调用失败");
            } else if (event.data.status === "aborted") {
              setNotice("已停止生成");
            } else {
              setNotice(null);
            }
            break;
        }

        if (settled) break;
      }

      if (!settled) {
        setError("连接中断，回复可能不完整。");
        updateAssistant((message) => ({ ...message, status: "partial" }));
      }
    } catch (cause: unknown) {
      if (controller.signal.aborted) {
        updateAssistant((message) => ({ ...message, status: "aborted" }));
        setNotice("已停止生成");
      } else if (!opened) {
        // The stream never started: roll back the optimistic message so the
        // user can retry (e.g. after switching a MODEL_UNAVAILABLE model).
        setMessages((previous) => previous.slice(0, -1));
        setInput(text);
        fail(cause, "send");
      } else {
        fail(cause, "send");
        updateAssistant((message) => ({ ...message, status: "error" }));
      }
    } finally {
      if (abortRef.current === controller) abortRef.current = null;
      setBusy(false);
      // Title and activity order are projected server-side; refresh the list.
      void refreshSessions().catch(() => undefined);
    }
  }, [
    appendText,
    appendThinking,
    busy,
    finalizeMessage,
    input,
    fail,
    refreshSessions,
    updateAssistant,
    updateTool,
  ]);

  const stop = useCallback(() => {
    abortRef.current?.abort();
  }, []);

  const switchKnowledgeBase = useCallback(
    async (sessionId: string, kbId: string | null): Promise<boolean> => {
      try {
        await switchSessionKnowledgeBase(sessionId, kbId);
        await refreshSessions();
        clearError();
        setNotice("已切换知识库，后续检索使用新库");
        return true;
      } catch (cause: unknown) {
        fail(cause, "other");
        return false;
      }
    },
    [clearError, fail, refreshSessions],
  );

  const currentSession = sessions.find((session) => session.id === currentSessionId);
  const currentTitle = currentSession?.title ?? "agent-core";
  const currentKbId = currentSession?.kbId ?? null;
  const currentKbName = currentSession?.kbName ?? null;
  // System labels store the actual model name; user labels are `provider / model`.
  const currentModel = currentSession?.modelLabel
    ? currentSession.modelSource === "system"
      ? currentSession.modelLabel === SYSTEM_DEFAULT_SENTINEL
        ? currentSession.modelLabel
        : `系统默认 · ${currentSession.modelLabel}`
      : currentSession.modelLabel
    : null;

  return {
    sessions,
    sessionsLoading,
    archivedSessions,
    archivedLoading,
    archivedHasMore,
    loadArchived,
    loadMoreArchived,
    currentSessionId,
    currentTitle,
    currentModel,
    currentKbId,
    currentKbName,
    contextUsage,
    messages,
    input,
    setInput,
    busy,
    error,
    errorCode,
    errorSource,
    notice,
    historyLoading,
    historyHasMore,
    loadOlder,
    send,
    stop,
    selectSession,
    createSession,
    switchModel,
    switchKnowledgeBase,
    restoreSession,
    permanentDeleteSession,
    renameSession,
    archiveSession,
  };
}
