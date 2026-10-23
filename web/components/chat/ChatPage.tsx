"use client";

import { useChat } from "@/hooks/useChat";
import { Composer } from "./Composer";
import { MessageList } from "./MessageList";
import styles from "./chat.module.css";

export function ChatPage() {
  const { sessionId, messages, input, setInput, busy, error, notice, send } = useChat();

  return (
    <main className={styles.chat}>
      <h1 className={styles.title}>agent-core {sessionId ? "" : "— 连接中..."}</h1>
      <MessageList messages={messages} busy={busy} error={error} notice={notice} />
      <Composer
        value={input}
        onChange={setInput}
        onSend={() => void send()}
        disabled={!sessionId || busy}
      />
    </main>
  );
}
