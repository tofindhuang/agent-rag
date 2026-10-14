"use client";

import { useCallback, useEffect, useRef, useState } from "react";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080";

interface ChatMessage {
  role: "user" | "assistant";
  content: string;
}

/** One SSE frame from the gateway, passthrough of a pi session event. */
interface PiEvent {
  type: string;
  assistantMessageEvent?: {
    type: string;
    delta?: string;
  };
}

export default function ChatPage() {
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    fetch(`${API_BASE}/api/chat/sessions`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ userId: "dev-user" }),
    })
      .then((r) => {
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        return r.json() as Promise<{ sessionId: string }>;
      })
      .then((d) => setSessionId(d.sessionId))
      .catch((e: unknown) => setError(`会话创建失败（需要 server 运行在 ${API_BASE}）: ${String(e)}`));
  }, []);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages]);

  const appendDelta = useCallback((delta: string) => {
    setMessages((prev) => {
      if (prev.length === 0 || prev[prev.length - 1].role !== "assistant") {
        return [...prev, { role: "assistant", content: delta }];
      }
      const last = prev[prev.length - 1];
      return [...prev.slice(0, -1), { role: "assistant", content: last.content + delta }];
    });
  }, []);

  async function send() {
    const text = input.trim();
    if (!sessionId || !text || busy) return;
    setInput("");
    setBusy(true);
    setError(null);
    setMessages((prev) => [...prev, { role: "user", content: text }]);

    try {
      const res = await fetch(`${API_BASE}/api/chat/sessions/${sessionId}/messages`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ text }),
      });
      if (!res.ok || !res.body) {
        throw new Error(`HTTP ${res.status}: ${(await res.text()).slice(0, 200)}`);
      }

      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";
      let settled = false;

      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });

        let sep: number;
        while ((sep = buffer.indexOf("\n\n")) >= 0) {
          const frame = buffer.slice(0, sep);
          buffer = buffer.slice(sep + 2);
          const event = parseFrame(frame);
          if (!event) continue;

          if (event.pi?.type === "message_update" && event.pi.assistantMessageEvent?.type === "text_delta") {
            appendDelta(event.pi.assistantMessageEvent.delta ?? "");
          } else if (event.name === "prompt_settled") {
            settled = true;
          } else if (event.name === "prompt_error") {
            setError(`模型调用失败: ${event.data?.message ?? "unknown"}`);
          }
        }
      }
      if (!settled && !error) {
        // Stream ended without prompt_settled: mark the turn as complete anyway.
        appendDelta("");
      }
    } catch (e: unknown) {
      setError(String(e instanceof Error ? e.message : e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="chat">
      <h1>agent-core {sessionId ? "" : "— 连接中..."}</h1>
      <div className="messages">
        {messages.map((m, i) => (
          <div key={i} className={`message ${m.role}`}>
            {m.content || "..."}
          </div>
        ))}
        {busy && <div className="status">回复中...</div>}
        {error && <div className="error">{error}</div>}
        <div ref={bottomRef} />
      </div>

      <div className="composer">
        <div className="composer-inner">
          <input
            value={input}
            placeholder="输入消息，Enter 发送"
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !e.nativeEvent.isComposing) void send();
            }}
            disabled={!sessionId || busy}
          />
          <button onClick={() => void send()} disabled={!sessionId || busy || !input.trim()}>
            发送
          </button>
        </div>
      </div>
    </main>
  );
}

interface ParsedFrame {
  name?: string;
  data?: { message?: string };
  pi?: PiEvent;
}

function parseFrame(frame: string): ParsedFrame | null {
  const result: ParsedFrame = {};
  for (const line of frame.split("\n")) {
    if (line.startsWith("event: ")) {
      result.name = line.slice(7).trim();
    } else if (line.startsWith("data: ")) {
      try {
        result.data = JSON.parse(line.slice(6)) as ParsedFrame["data"];
        // pi passthrough events carry their own type; error frames do not.
        if (result.data && "type" in (result.data as Record<string, unknown>)) {
          result.pi = result.data as unknown as PiEvent;
        }
      } catch {
        // ignore malformed frames
      }
    }
  }
  return result.name || result.pi || result.data ? result : null;
}
