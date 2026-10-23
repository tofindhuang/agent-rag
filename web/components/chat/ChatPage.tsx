"use client";

import Link from "next/link";
import { useState } from "react";
import { useAuth } from "@/components/auth/AuthProvider";
import { Banner } from "@/components/ui/Banner";
import { useChat } from "@/hooks/useChat";
import { Composer } from "./Composer";
import { MessageList } from "./MessageList";
import { NewSessionDialog } from "./NewSessionDialog";
import { SessionSidebar, type SessionTab } from "./SessionSidebar";
import { SwitchModelDialog } from "./SwitchModelDialog";
import styles from "./chat.module.css";

export function ChatPage() {
  const { user, logout } = useAuth();
  const chat = useChat();
  const [creating, setCreating] = useState(false);
  const [switching, setSwitching] = useState(false);
  const [tab, setTab] = useState<SessionTab>("active");

  const changeTab = (next: SessionTab) => {
    setTab(next);
    if (next === "archived") void chat.loadArchived();
  };

  // Special error codes get their own affordances instead of plain text.
  const needsSetup = chat.errorCode === "MODEL_NOT_CONFIGURED";
  const modelUnavailable =
    chat.errorCode === "MODEL_UNAVAILABLE" && chat.errorSource === "send";
  const plainError =
    chat.error != null && !needsSetup && !modelUnavailable ? chat.error : null;

  return (
    <div className={styles.shell}>
      <SessionSidebar
        sessions={chat.sessions}
        archivedSessions={chat.archivedSessions}
        tab={tab}
        currentSessionId={chat.currentSessionId}
        loading={chat.sessionsLoading}
        archivedLoading={chat.archivedLoading}
        archivedHasMore={chat.archivedHasMore}
        onTabChange={changeTab}
        onCreate={() => setCreating(true)}
        onSelect={(sessionId) => void chat.selectSession(sessionId)}
        onRename={(sessionId, title) => void chat.renameSession(sessionId, title)}
        onArchive={(sessionId) => void chat.archiveSession(sessionId)}
        onRestore={(sessionId) => void chat.restoreSession(sessionId)}
        onPermanentDelete={(sessionId) => void chat.permanentDeleteSession(sessionId)}
        onLoadMoreArchived={() => void chat.loadMoreArchived()}
      />

      <div className={styles.mainArea}>
        <main className={styles.chat}>
          <header className={styles.header}>
            <div className={styles.titleWrap}>
              <h1 className={styles.title}>{chat.currentTitle}</h1>
              {chat.currentModel ? (
                <span className={styles.modelTag} title="当前会话使用的模型">
                  {chat.currentModel}
                </span>
              ) : null}
            </div>
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

          {needsSetup && !creating ? (
            <Banner tone="error">
              <span>{chat.error}</span>
              <Link className={styles.bannerLink} href="/settings">
                前往设置
              </Link>
            </Banner>
          ) : null}

          {modelUnavailable && !switching && chat.currentSessionId ? (
            <Banner tone="error">
              <span>{chat.error}</span>
              <button
                className={styles.bannerLink}
                type="button"
                onClick={() => setSwitching(true)}
              >
                切换模型
              </button>
            </Banner>
          ) : null}

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
            error={plainError}
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

      {creating ? (
        <NewSessionDialog
          error={chat.errorSource === "create" ? chat.error : null}
          errorCode={chat.errorSource === "create" ? chat.errorCode : null}
          onSubmit={async (options) => chat.createSession(options)}
          onClose={() => setCreating(false)}
        />
      ) : null}

      {switching && chat.currentSessionId ? (
        <SwitchModelDialog
          sessionId={chat.currentSessionId}
          error={chat.errorSource === "switch" ? chat.error : null}
          onSwitch={async (sessionId, modelId) => chat.switchModel(sessionId, modelId)}
          onClose={() => setSwitching(false)}
        />
      ) : null}
    </div>
  );
}
