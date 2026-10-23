"use client";

import { useState } from "react";
import type { SessionSummary } from "@/types/chat";
import styles from "./chat.module.css";

interface SessionSidebarProps {
  sessions: SessionSummary[];
  currentSessionId: string | null;
  loading: boolean;
  onCreate: () => void;
  onSelect: (sessionId: string) => void;
  onRename: (sessionId: string, title: string) => void;
  onArchive: (sessionId: string) => void;
}

export function SessionSidebar({
  sessions,
  currentSessionId,
  loading,
  onCreate,
  onSelect,
  onRename,
  onArchive,
}: SessionSidebarProps) {
  const [editingId, setEditingId] = useState<string | null>(null);
  const [draft, setDraft] = useState("");

  const startRename = (session: SessionSummary) => {
    setEditingId(session.id);
    setDraft(session.title);
  };

  const commitRename = () => {
    if (editingId) onRename(editingId, draft);
    setEditingId(null);
  };

  return (
    <nav className={styles.sidebar} aria-label="会话列表">
      <div className={styles.sidebarHeader}>
        <span className={styles.sidebarTitle}>会话</span>
        <button className={styles.newSession} type="button" onClick={onCreate}>
          新建
        </button>
      </div>

      {loading ? (
        <p className={styles.sidebarHint} role="status">
          加载中…
        </p>
      ) : sessions.length === 0 ? (
        <p className={styles.sidebarHint}>还没有会话</p>
      ) : (
        <ul className={styles.sessionList}>
          {sessions.map((session) => {
            const active = session.id === currentSessionId;
            return (
              <li key={session.id} className={active ? styles.sessionActive : styles.session}>
                {editingId === session.id ? (
                  <form
                    className={styles.renameForm}
                    onSubmit={(event) => {
                      event.preventDefault();
                      commitRename();
                    }}
                  >
                    <input
                      className={styles.renameInput}
                      value={draft}
                      aria-label="会话标题"
                      autoFocus
                      maxLength={255}
                      onChange={(event) => setDraft(event.target.value)}
                      onKeyDown={(event) => {
                        if (event.key === "Escape") setEditingId(null);
                      }}
                    />
                    <button className={styles.smallButton} type="submit">
                      保存
                    </button>
                  </form>
                ) : (
                  <div className={styles.sessionRow}>
                    <button
                      className={styles.sessionButton}
                      type="button"
                      aria-current={active ? "page" : undefined}
                      onClick={() => onSelect(session.id)}
                    >
                      {session.title}
                    </button>
                    <div className={styles.sessionActions}>
                      <button
                        className={styles.smallButton}
                        type="button"
                        aria-label={`重命名 ${session.title}`}
                        onClick={() => startRename(session)}
                      >
                        重命名
                      </button>
                      <button
                        className={styles.smallButton}
                        type="button"
                        aria-label={`归档 ${session.title}`}
                        onClick={() => onArchive(session.id)}
                      >
                        归档
                      </button>
                    </div>
                  </div>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </nav>
  );
}
