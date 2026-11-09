"use client";

import { Fragment, useEffect, useRef, type RefObject } from "react";
import { Banner } from "@/components/ui/Banner";
import type { ChatMessage, CompactionSummary } from "@/types/chat";
import { CompactionCard } from "./CompactionCard";
import { MessageBubble } from "./MessageBubble";
import styles from "./chat.module.css";

interface MessageListProps {
  messages: ChatMessage[];
  busy: boolean;
  error: string | null;
  notice: string | null;
  historyLoading?: boolean;
  /** Projected summaries, oldest first; placed after their anchor message. */
  compactions?: CompactionSummary[];
  /** Summaries received live in this session; always rendered at the end. */
  liveCompactions?: CompactionSummary[];
  latestCompactionRef?: RefObject<HTMLDivElement | null>;
  /** When false the oldest page is loaded, so unanchored cards go on top. */
  historyHasMore?: boolean;
  onBranch?: (message: ChatMessage) => void;
}

export function MessageList({
  messages,
  busy,
  error,
  notice,
  historyLoading = false,
  compactions = [],
  liveCompactions = [],
  latestCompactionRef,
  historyHasMore = false,
  onBranch,
}: MessageListProps) {
  const bottomRef = useRef<HTMLDivElement>(null);
  const lastMessage = messages[messages.length - 1];
  const streaming = messages.some((message) => message.status === "streaming");

  const byAnchor = new Map<number, CompactionSummary[]>();
  const unanchored: CompactionSummary[] = [];
  for (const item of compactions) {
    if (item.afterMessageId == null) {
      unanchored.push(item);
      continue;
    }
    const bucket = byAnchor.get(item.afterMessageId);
    if (bucket) bucket.push(item);
    else byAnchor.set(item.afterMessageId, [item]);
  }
  const latestId =
    liveCompactions[liveCompactions.length - 1]?.id ??
    compactions[compactions.length - 1]?.id ??
    null;

  const renderCard = (item: CompactionSummary) => (
    <CompactionCard
      key={`compaction-${item.id}`}
      item={item}
      latest={item.id === latestId}
      cardRef={item.id === latestId ? latestCompactionRef : undefined}
    />
  );

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
      {!historyHasMore ? unanchored.map(renderCard) : null}
      {messages.map((message, index) => {
        const anchored =
          message.historyId == null ? undefined : byAnchor.get(message.historyId);
        return (
          <Fragment key={message.historyId ?? `live-${index}`}>
            <MessageBubble message={message} onBranch={onBranch} />
            {anchored?.map(renderCard)}
          </Fragment>
        );
      })}
      {liveCompactions.map(renderCard)}
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
