"use client";

import { useEffect, useRef } from "react";
import type { ChatMessage } from "@/types/chat";
import { MessageBubble } from "./MessageBubble";
import styles from "./chat.module.css";

interface MessageListProps {
  messages: ChatMessage[];
  busy: boolean;
  error: string | null;
}

export function MessageList({ messages, busy, error }: MessageListProps) {
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages, busy, error]);

  return (
    <div className={styles.messages}>
      {messages.map((message, index) => (
        <MessageBubble key={index} message={message} />
      ))}
      {busy && (
        <div className={styles.status} role="status">
          回复中...
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
