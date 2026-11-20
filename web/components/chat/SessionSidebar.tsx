"use client";

import { useState } from "react";
import type { SessionSummary } from "@/types/chat";
import styles from "./chat.module.css";

export type SessionTab = "active" | "archived";

interface SessionSidebarProps {
  sessions: SessionSummary[];
  archivedSessions: SessionSummary[];
  tab: SessionTab;
  currentSessionId: string | null;
  loading: boolean;
  archivedLoading: boolean;
  archivedHasMore: boolean;
  onTabChange: (tab: SessionTab) => void;
  onCreate: () => void;
  onSelect: (sessionId: string) => void;
  onRename: (sessionId: string, title: string) => void;
  onArchive: (sessionId: string) => void;
  onRestore: (sessionId: string) => void;
  onPermanentDelete: (sessionId: string) => void;
  onLoadMoreArchived: () => void;
}

export function SessionSidebar({
  sessions,
  archivedSessions,
  tab,
  currentSessionId,
  loading,
  archivedLoading,
  archivedHasMore,
  onTabChange,
  onCreate,
  onSelect,
  onRename,
  onArchive,
  onRestore,
  onPermanentDelete,
  onLoadMoreArchived,
}: SessionSidebarProps) {
  const [editingId, setEditingId] = useState<string | null>(null);
  const [draft, setDraft] = useState("");
  const [confirmDeleteId, setConfirmDeleteId] = useState<string | null>(null);

  const startRename = (session: SessionSummary) => {
    setEditingId(session.id);
    setDraft(session.title);
  };

  const commitRename = () => {
    if (editingId) onRename(editingId, draft);
    setEditingId(null);
  };

  const archived = tab === "archived";

  return (
    <nav className={styles.sidebar} aria-label="会话列表">
      <div className={styles.sidebarHeader}>
        <span className={styles.sidebarTitle}>会话</span>
        <button className={styles.newSession} type="button" onClick={onCreate}>
          新建
        </button>
      </div>

      <div className={styles.tabList} role="tablist" aria-label="会话范围">
        <button
          className={tab === "active" ? styles.tabActive : styles.tab}
          type="button"
          role="tab"
          aria-selected={tab === "active"}
          onClick={() => onTabChange("active")}
        >
          进行中
        </button>
        <button
          className={tab === "archived" ? styles.tabActive : styles.tab}
          type="button"
          role="tab"
          aria-selected={tab === "archived"}
          onClick={() => onTabChange("archived")}
        >
          已归档
        </button>
      </div>

      {archived ? (
        archivedLoading && archivedSessions.length === 0 ? (
          <p className={styles.sidebarHint} role="status">
            加载中…
          </p>
        ) : archivedSessions.length === 0 ? (
          <p className={styles.sidebarHint}>还没有已归档的会话</p>
        ) : (
          <>
            <ul className={styles.sessionList}>
              {archivedSessions.map((session) => (
                <li key={session.id} className={styles.session}>
                  <div className={styles.sessionRow}>
                    <span className={styles.sessionTitle} title={session.title}>
                      {session.title}
                    </span>
                    <div className={styles.sessionActions}>
                      <button
                        className={styles.smallButton}
                        type="button"
                        aria-label={`恢复 ${session.title}`}
                        onClick={() => onRestore(session.id)}
                      >
                        恢复
                      </button>
                      {confirmDeleteId === session.id ? (
                        <>
                          <button
                            className={styles.dangerSmall}
                            type="button"
                            aria-label={`确认彻底删除 ${session.title}`}
                            onClick={() => {
                              setConfirmDeleteId(null);
                              onPermanentDelete(session.id);
                            }}
                          >
                            确认删除
                          </button>
                          <button
                            className={styles.smallButton}
                            type="button"
                            onClick={() => setConfirmDeleteId(null)}
                          >
                            取消
                          </button>
                        </>
                      ) : (
                        <button
                          className={styles.smallButton}
                          type="button"
                          aria-label={`彻底删除 ${session.title}`}
                          onClick={() => setConfirmDeleteId(session.id)}
                        >
                          彻底删除
                        </button>
                      )}
                    </div>
                    {confirmDeleteId === session.id ? (
                      <p className={styles.dangerHint}>
                        不可恢复：历史与引擎上下文都会删除。
                      </p>
                    ) : null}
                  </div>
                </li>
              ))}
            </ul>
            {archivedHasMore ? (
              <button
                className={styles.loadMore}
                type="button"
                onClick={onLoadMoreArchived}
                disabled={archivedLoading}
              >
                {archivedLoading ? "加载中…" : "加载更多"}
              </button>
            ) : null}
          </>
        )
      ) : loading ? (
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
