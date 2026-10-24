"use client";

import { useEffect, useRef } from "react";
import { Banner } from "@/components/ui/Banner";
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
  const streaming = messages.some((message) => message.status === "streaming");

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
        <div className={styles.empty}>
          <p className={styles.emptyTitle}>开始一段对话</p>
          <p className={styles.emptyHint}>
            可以提问、检索知识库，或让它调用 <code>run_code</code> 执行代码。
          </p>
        </div>
      ) : null}
      {messages.map((message, index) => (
        <MessageBubble key={message.historyId ?? `live-${index}`} message={message} />
      ))}
      {busy && !streaming && (
        <div className={styles.status} role="status">
          正在准备回复…
        </div>
      )}
      {notice && (
        <div className={styles.status} role="status">
          {notice}
        </div>
      )}
      {error && <Banner tone="error">{error}</Banner>}
      <div ref={bottomRef} />
    </div>
  );
}
