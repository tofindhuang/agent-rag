"use client";

import { useAuth } from "@/components/auth/AuthProvider";
import { useChat } from "@/hooks/useChat";
import { Composer } from "./Composer";
import { MessageList } from "./MessageList";
import styles from "./chat.module.css";

export function ChatPage() {
  const { user, logout } = useAuth();
  const { sessionId, messages, input, setInput, busy, error, notice, send } = useChat();

  return (
    <main className={styles.chat}>
      <header className={styles.header}>
        <h1 className={styles.title}>agent-core {sessionId ? "" : "— 连接中..."}</h1>
        <div className={styles.account}>
          <span className={styles.email}>{user?.email}</span>
          <button
            className={styles.logout}
            type="button"
            onClick={() => void logout()}
          >
            退出登录
          </button>
        </div>
      </header>
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
