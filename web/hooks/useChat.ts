"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { createChatSession, openChatStream } from "@/lib/api/chat";
import { userMessage } from "@/lib/api/errors";
import { parseSse } from "@/lib/sse/parse-sse";
import type { ChatMessage, ToolActivity } from "@/types/chat";
import type { MessageEndData } from "@/types/gateway";
import { parseGatewayEvent } from "@/types/gateway";

function newAssistant(): ChatMessage {
  return { role: "assistant", content: "", thinking: "", tools: [], status: "streaming" };
}

function newUser(content: string): ChatMessage {
  return { role: "user", content, thinking: "", tools: [] };
}

/**
 * Owns ephemeral chat state and the contract v1 SSE lifecycle. Tool progress
 * is display-only; the model only sees the final tool result.
 */
export function useChat() {
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    createChatSession(controller.signal)
      .then((id) => {
        if (!controller.signal.aborted) setSessionId(id);
      })
      .catch((cause: unknown) => {
        if (controller.signal.aborted) return;
        console.error(cause);
        setError(userMessage(cause));
      });
    return () => controller.abort();
  }, []);

  useEffect(() => () => abortRef.current?.abort(), []);

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
    if (!sessionId || text.length === 0 || busy) return;

    setInput("");
    setError(null);
    setNotice(null);
    setBusy(true);
    setMessages((previous) => [...previous, newUser(text)]);

    const controller = new AbortController();
    abortRef.current = controller;
    let settled = false;

    try {
      const stream = await openChatStream(sessionId, text, controller.signal);
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
            }));
            break;
          case "message_end":
            finalizeMessage(event.data);
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
              status: event.data.status === "completed" ? "complete" : event.data.status,
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
        updateAssistant((message) => ({ ...message, status: "error" }));
      }
    } catch (cause: unknown) {
      if (!controller.signal.aborted) {
        console.error(cause);
        setError(userMessage(cause));
        updateAssistant((message) => ({ ...message, status: "error" }));
      }
    } finally {
      if (abortRef.current === controller) abortRef.current = null;
      setBusy(false);
    }
  }, [appendText, appendThinking, busy, finalizeMessage, input, sessionId, updateAssistant, updateTool]);

  return { sessionId, messages, input, setInput, busy, error, notice, send };
}
