"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { createChatSession, openChatStream } from "@/lib/api/chat";
import { userMessage } from "@/lib/api/errors";
import { parseSse } from "@/lib/sse/parse-sse";
import type { ChatMessage } from "@/types/chat";
import type { GatewayPayload } from "@/types/gateway";

const DEV_USER_ID = "dev-user";

/**
 * Owns the ephemeral chat turn state and the SSE lifecycle. Components stay
 * presentational; HTTP lives in lib/api and frame parsing in lib/sse.
 */
export function useChat() {
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    createChatSession(DEV_USER_ID, controller.signal)
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

  const appendDelta = useCallback((delta: string) => {
    setMessages((previous) => {
      const last = previous[previous.length - 1];
      if (!last || last.role !== "assistant") {
        return [...previous, { role: "assistant", content: delta }];
      }
      return [...previous.slice(0, -1), { role: "assistant", content: last.content + delta }];
    });
  }, []);

  const send = useCallback(async (): Promise<void> => {
    const text = input.trim();
    if (!sessionId || text.length === 0 || busy) return;

    setInput("");
    setError(null);
    setBusy(true);
    setMessages((previous) => [...previous, { role: "user", content: text }]);

    const controller = new AbortController();
    abortRef.current = controller;
    let settled = false;

    try {
      const stream = await openChatStream(sessionId, text, controller.signal);
      for await (const frame of parseSse(stream)) {
        const payload = parsePayload(frame.data);
        if (frame.event === "message_update" && payload?.assistantMessageEvent?.type === "text_delta") {
          appendDelta(payload.assistantMessageEvent.delta ?? "");
        } else if (frame.event === "prompt_settled") {
          settled = true;
          break;
        } else if (frame.event === "prompt_error") {
          setError(typeof payload?.message === "string" ? payload.message : "模型调用失败");
          settled = true;
          break;
        }
      }
      if (!settled) setError("连接中断，回复可能不完整。");
    } catch (cause: unknown) {
      if (!controller.signal.aborted) {
        console.error(cause);
        setError(userMessage(cause));
      }
    } finally {
      if (abortRef.current === controller) abortRef.current = null;
      setBusy(false);
    }
  }, [appendDelta, busy, input, sessionId]);

  return { sessionId, messages, input, setInput, busy, error, send };
}

function parsePayload(data: string | undefined): GatewayPayload | null {
  if (!data) return null;
  try {
    return JSON.parse(data) as GatewayPayload;
  } catch {
    return null;
  }
}
