"use client";

import { useState } from "react";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { TextField } from "@/components/ui/TextField";
import { knowledgeError, queryKbRetrieval } from "@/lib/api/knowledge";
import { useRetrievalQuality } from "@/hooks/useRetrievalQuality";
import type {
  KbEvalLabel,
  KbRetrievalPassage,
  KbRetrievalQueryResult,
} from "@/types/knowledge";
import { RetrievalEvalSection } from "./RetrievalEvalSection";
import styles from "./knowledge.module.css";

interface RetrievalPanelProps {
  kbId: string | null;
  /** Documents still being ingested; their passages dilute the metric. */
  processingCount: number;
  /** Documents waiting for reingest; the server refuses to evaluate a stale index. */
  staleCount: number;
}

/**
 * Retrieval debugging: one candidate pool rendered twice so the effect of
 * rerank is visible passage by passage, plus eval labels, runs and history.
 */
export function RetrievalPanel({ kbId, processingCount, staleCount }: RetrievalPanelProps) {
  const [query, setQuery] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<KbRetrievalQueryResult | null>(null);
  const [noAnswerQuery, setNoAnswerQuery] = useState("");
  const quality = useRetrievalQuality(kbId);

  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const trimmed = query.trim();
    if (!kbId || trimmed.length === 0) return;
    setBusy(true);
    setError(null);
    try {
      setResult(await queryKbRetrieval(kbId, trimmed));
    } catch (cause: unknown) {
      console.error(cause);
      setError(knowledgeError(cause));
      setResult(null);
    } finally {
      setBusy(false);
    }
  };

  const mark = (passage: KbRetrievalPassage) => {
    if (!result) return;
    return quality.createLabel({
      query: result.query.trim(),
      documentId: passage.documentId,
      seq: passage.seq,
    });
  };

  const addNoAnswerLabel = async () => {
    const trimmed = noAnswerQuery.trim();
    if (!kbId || trimmed.length === 0) return;
    await quality.createLabel({ query: trimmed, expectation: "no_answer" });
    setNoAnswerQuery("");
  };

  const labelForQuery = (queryText: string): KbEvalLabel | null =>
    quality.labels?.items.find((label) => label.query === queryText.trim()) ?? null;

  return (
    <section className={styles.section} aria-labelledby="kb-retrieval-title">
      <header className={styles.sectionHeader}>
        <h2 id="kb-retrieval-title">检索调试</h2>
        <span className={styles.hint}>同一候选池 · 向量序与当前配置对照</span>
      </header>
      <span role="status" className={styles.srOnly}>
        {busy ? "检索中" : quality.evalBusy ? "评测中" : quality.labelBusy ? "保存标注中" : ""}
      </span>

      {error ? <Banner tone="error">{error}</Banner> : null}

      {!kbId ? (
        <p className={styles.hint}>先选择一个知识库。</p>
      ) : (
        <>
          <form className={styles.retrievalForm} onSubmit={submit}>
            <TextField
              label="问题"
              value={query}
              maxLength={512}
              placeholder="输入一个你能判断答案的问题"
              onChange={(event) => setQuery(event.target.value)}
            />
            <Button type="submit" disabled={busy || query.trim().length === 0}>
              {busy ? "检索中…" : "查询"}
            </Button>
          </form>

          {result ? (
            <div className={styles.retrievalResults}>
              <p
                className={[
                  styles.verdictLine,
                  result.noAnswer ? styles.verdictRefused : "",
                ].join(" ")}
                role="status"
              >
                {result.threshold == null
                  ? "未启用拒答阈值。"
                  : `阈值 ${result.threshold.toFixed(3)}（${
                      result.thresholdSource === "rerank" ? "rerank" : "向量"
                    }）· 最高分 ${
                      result.maxScore == null ? "—" : result.maxScore.toFixed(3)
                    } → ${result.noAnswer ? "会被拒答" : "通过"}`}
              </p>
              {result.reranked ? (
                <div className={styles.retrievalGrid}>
                  <PassageColumn
                    title="向量序"
                    caption="候选池前 5 条，未经 rerank"
                    passages={result.vector}
                  />
                  <PassageColumn
                    title="当前配置"
                    caption={result.rerankModel ?? "rerank"}
                    passages={result.current}
                    label={labelForQuery(result.query)}
                    onMark={quality.labelBusy ? undefined : mark}
                    onUnmark={quality.labelBusy ? undefined : quality.removeLabel}
                  />
                </div>
              ) : (
                <>
                  <p className={styles.hint}>rerank 未启用，仅显示向量序。</p>
                  <PassageColumn
                    title="向量序"
                    passages={result.vector}
                    label={labelForQuery(result.query)}
                    onMark={quality.labelBusy ? undefined : mark}
                    onUnmark={quality.labelBusy ? undefined : quality.removeLabel}
                  />
                </>
              )}
            </div>
          ) : null}

          <div className={styles.evalBlock}>
            <div className={styles.evalLabelsHeader}>
              <span>评测标注</span>
              <span className={styles.hint}>
                {quality.labels ? `${quality.labels.total} / ${quality.labels.limit}` : "—"}
              </span>
            </div>
            {quality.labelError ? <Banner tone="error">{quality.labelError}</Banner> : null}
            <div className={styles.noAnswerForm}>
              <input
                className={styles.noAnswerInput}
                placeholder="输入一个库里确实没有答案的问题"
                value={noAnswerQuery}
                maxLength={512}
                disabled={quality.labelBusy}
                onChange={(event) => setNoAnswerQuery(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === "Enter") {
                    event.preventDefault();
                    void addNoAnswerLabel();
                  }
                }}
              />
              <Button
                variant="secondary"
                type="button"
                size="sm"
                disabled={quality.labelBusy || noAnswerQuery.trim().length === 0}
                onClick={() => void addNoAnswerLabel()}
              >
                标为无答案
              </Button>
            </div>
            {!quality.labels || quality.labels.items.length === 0 ? (
              <p className={styles.hint}>
                还没有标注。查询后把正确片段标为期望答案，或用上面的输入框标「无答案」样本；
                重建导致的目标变化会标为「过期待确认」。
              </p>
            ) : (
              <ul className={styles.labelList}>
                {quality.labels.items.map((label) => (
                  <li key={label.id} className={styles.labelItem}>
                    <div className={styles.labelMain}>
                      <span className={styles.labelQuery}>
                        {label.expectation === "no_answer" ? (
                          <span className={styles.labelTypeBadge}>无答案</span>
                        ) : null}
                        {label.query}
                      </span>
                      <span className={styles.labelMeta}>
                        {label.expectation === "no_answer"
                          ? "库里应当没有答案"
                          : `${label.title ?? "（文档已删除）"}${
                              label.page != null ? ` · 第 ${label.page} 页` : ""
                            } · #${label.seq ?? "?"}`}
                      </span>
                    </div>
                    <span
                      className={[
                        styles.stateBadge,
                        label.state === "ok" ? styles.stateOk : styles.stateRotten,
                      ].join(" ")}
                    >
                      {label.state === "ok" ? "有效" : "过期待确认"}
                    </span>
                    <Button
                      variant="ghost"
                      size="sm"
                      disabled={quality.labelBusy}
                      onClick={() => void quality.removeLabel(label)}
                    >
                      删除
                    </Button>
                  </li>
                ))}
              </ul>
            )}
          </div>

          <RetrievalEvalSection
            quality={quality}
            processingCount={processingCount}
            staleCount={staleCount}
          />
        </>
      )}
    </section>
  );
}

function PassageColumn({
  title,
  caption,
  passages,
  label,
  onMark,
  onUnmark,
}: {
  title: string;
  caption?: string;
  passages: KbRetrievalPassage[];
  label?: KbEvalLabel | null;
  onMark?: (passage: KbRetrievalPassage) => Promise<void> | void;
  onUnmark?: (label: KbEvalLabel) => Promise<void> | void;
}) {
  return (
    <div className={styles.retrievalColumn}>
      <div className={styles.retrievalColumnHeader}>
        <span>{title}</span>
        {caption ? <span className={styles.retrievalColumnCaption}>{caption}</span> : null}
      </div>
      {passages.length === 0 ? (
        <p className={styles.hint}>没有命中片段。</p>
      ) : (
        <ol className={styles.retrievalList}>
          {passages.map((passage) => {
            const isTarget =
              label?.documentId === passage.documentId && label.seq === passage.seq;
            return (
              <li key={passage.passageId} className={styles.retrievalRow}>
                <div className={styles.retrievalRowHead}>
                  <span className={styles.retrievalTitle} title={passage.title}>
                    {passage.title}
                  </span>
                  <span className={styles.retrievalMeta}>
                    {passage.page != null ? `第 ${passage.page} 页 · ` : ""}#{passage.seq}
                  </span>
                  <span className={styles.retrievalScore}>{passage.score.toFixed(4)}</span>
                </div>
                <p className={styles.retrievalContent}>{passage.content}</p>
                {onMark ? (
                  <div className={styles.retrievalRowActions}>
                    {isTarget && label ? (
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={() => void onUnmark?.(label)}
                      >
                        已标注 · 取消
                      </Button>
                    ) : (
                      <Button size="sm" variant="ghost" onClick={() => void onMark(passage)}>
                        {label ? "改标到此" : "标为期望答案"}
                      </Button>
                    )}
                  </div>
                ) : null}
              </li>
            );
          })}
        </ol>
      )}
    </div>
  );
}
