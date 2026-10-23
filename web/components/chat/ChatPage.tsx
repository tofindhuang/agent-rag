"use client";

import Link from "next/link";
import { useAuth } from "@/components/auth/AuthProvider";
import { useChat } from "@/hooks/useChat";
import { Composer } from "./Composer";
import { MessageList } from "./MessageList";
import { SessionSidebar } from "./SessionSidebar";
import styles from "./chat.module.css";

export function ChatPage() {
  const { user, logout } = useAuth();
  const chat = useChat();

  return (
    <div className={styles.shell}>
      <SessionSidebar
        sessions={chat.sessions}
        currentSessionId={chat.currentSessionId}
        loading={chat.sessionsLoading}
        onCreate={() => void chat.createSession()}
        onSelect={(sessionId) => void chat.selectSession(sessionId)}
        onRename={(sessionId, title) => void chat.renameSession(sessionId, title)}
        onArchive={(sessionId) => void chat.archiveSession(sessionId)}
      />

      <div className={styles.mainArea}>
        <main className={styles.chat}>
          <header className={styles.header}>
            <h1 className={styles.title}>{chat.currentTitle}</h1>
            <div className={styles.account}>
              <span className={styles.email}>{user?.email}</span>
              <Link className={styles.accountLink} href="/settings">
                设置
              </Link>
              <button className={styles.logout} type="button" onClick={() => void logout()}>
                退出登录
              </button>
            </div>
          </header>

          {chat.historyHasMore ? (
            <button
              className={styles.loadOlder}
              type="button"
              onClick={() => void chat.loadOlder()}
              disabled={chat.historyLoading}
            >
              {chat.historyLoading ? "加载中…" : "加载更早消息"}
            </button>
          ) : null}

          <MessageList
            messages={chat.messages}
            busy={chat.busy}
            error={chat.error}
            notice={chat.notice}
            historyLoading={chat.historyLoading}
          />

          <Composer
            value={chat.input}
            onChange={chat.setInput}
            onSend={() => void chat.send()}
            onStop={chat.stop}
            disabled={!chat.currentSessionId || chat.busy || chat.historyLoading}
            busy={chat.busy}
          />
        </main>
      </div>
    </div>
  );
}
