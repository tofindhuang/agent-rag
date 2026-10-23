"use client";

import { useEffect, useRef } from "react";
import type { ChatMessage } from "@/types/chat";
import { MessageBubble } from "./MessageBubble";
import styles from "./chat.module.css";

interface MessageListProps {
  messages: ChatMessage[];
  busy: boolean;
  error: string | null;
  notice: string | null;
  historyLoading?: boolean;
}

export function MessageList({
  messages,
  busy,
  error,
  notice,
  historyLoading = false,
}: MessageListProps) {
  const bottomRef = useRef<HTMLDivElement>(null);
  const lastMessage = messages[messages.length - 1];

  useEffect(() => {
    // Prepending an older page keeps the same last message; don't jump down.
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [lastMessage, busy, error, notice]);

  return (
    <div className={styles.messages}>
      {historyLoading && messages.length === 0 ? (
        <div className={styles.status} role="status">
          加载历史…
        </div>
      ) : null}
      {!historyLoading && messages.length === 0 ? (
        <p className={styles.empty}>还没有消息，发一条开始吧。</p>
      ) : null}
      {messages.map((message, index) => (
        <MessageBubble key={message.historyId ?? `live-${index}`} message={message} />
      ))}
      {busy && (
        <div className={styles.status} role="status">
          回复中...
        </div>
      )}
      {notice && (
        <div className={styles.status} role="status">
          {notice}
        </div>
      )}
      {error && (
        <div className={styles.error} role="alert">
          {error}
        </div>
      )}
      <div ref={bottomRef} />
    </div>
  );
}
