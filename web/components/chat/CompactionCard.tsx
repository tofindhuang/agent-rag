"use client";

import { useState, type Ref } from "react";

import type { CompactionSummary } from "@/types/chat";
import { MarkdownContent } from "./MarkdownContent";
import styles from "./chat.module.css";

const REASON_LABELS: Record<string, string> = {
  threshold: "阈值触发",
  overflow: "溢出压缩",
  manual: "手动压缩",
};

function reasonLabel(reason: string): string {
  return REASON_LABELS[reason] ?? "自动压缩";
}

function formatTokens(value: number | null): string | null {
  return value == null ? null : value.toLocaleString("zh-CN");
}

function formatTime(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "";
  return date.toLocaleString("zh-CN", { hour12: false });
}

/** "12,400 → 3,100 tokens", or just the before value when pi had no estimate. */
function tokenSummary(item: CompactionSummary): string | null {
  const before = formatTokens(item.tokensBefore);
  const after = formatTokens(item.tokensAfter);
  if (before == null && after == null) return null;
  if (before == null) return `${after} tokens`;
  if (after == null) return `${before} tokens`;
  return `${before} → ${after} tokens`;
}

interface CompactionCardProps {
  item: CompactionSummary;
  /** Latest card gets scrolled to by the "已压缩" marker. */
  latest?: boolean;
  cardRef?: Ref<HTMLDivElement>;
}

/**
 * One compaction summary in place of the history it replaced. Collapsed by
 * default; the expanded state is intentionally not persisted (ADR-0028).
 */
export function CompactionCard({ item, latest = false, cardRef }: CompactionCardProps) {
  const [expanded, setExpanded] = useState(false);
  const meta = [reasonLabel(item.reason), tokenSummary(item), formatTime(item.createdAt)]
    .filter((part): part is string => part != null && part.length > 0)
    .join(" · ");

  return (
    <div className={styles.compactionCard} ref={cardRef} data-compaction-id={item.id}>
      <div className={styles.compactionHead}>
        <span className={styles.compactionTag}>历史已压缩</span>
        {latest ? <span className={styles.compactionLatest}>最新</span> : null}
        <span className={styles.compactionMeta}>{meta}</span>
      </div>
      {expanded ? (
        <div className={styles.compactionBody}>
          <MarkdownContent text={item.summary} />
        </div>
      ) : (
        <p className={styles.compactionPreview}>{item.summary}</p>
      )}
      <div className={styles.compactionFoot}>
        {item.truncated ? (
          <span className={styles.compactionWarn}>摘要过长，已截断保存</span>
        ) : null}
        <button
          type="button"
          className={styles.compactionToggle}
          onClick={() => setExpanded((previous) => !previous)}
          aria-expanded={expanded}
        >
          {expanded ? "收起" : "展开全文"}
        </button>
      </div>
    </div>
  );
}
