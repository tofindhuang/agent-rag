"use client";

import { useCallback, useEffect, useState } from "react";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { TextField } from "@/components/ui/TextField";
import {
  createKbEvalLabel,
  deleteKbEvalLabel,
  fetchKbEvalLabels,
  knowledgeError,
  queryKbRetrieval,
} from "@/lib/api/knowledge";
import type {
  KbEvalLabel,
  KbEvalLabelList,
  KbRetrievalPassage,
  KbRetrievalQueryResult,
} from "@/types/knowledge";
import styles from "./knowledge.module.css";

interface RetrievalPanelProps {
  kbId: string | null;
}

/**
 * Retrieval debugging: one candidate pool rendered twice so the effect of
 * rerank is visible passage by passage, plus the eval labels created from it.
 */
export function RetrievalPanel({ kbId }: RetrievalPanelProps) {
  const [query, setQuery] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<KbRetrievalQueryResult | null>(null);
  const [labels, setLabels] = useState<KbEvalLabelList | null>(null);
  const [labelBusy, setLabelBusy] = useState(false);
  const [labelError, setLabelError] = useState<string | null>(null);

  const refreshLabels = useCallback(async () => {
    if (!kbId) {
      setLabels(null);
      return;
    }
    setLabels(await fetchKbEvalLabels(kbId));
  }, [kbId]);

  useEffect(() => {
    let cancelled = false;
    setLabelError(null);
    setResult(null);
    if (!kbId) {
      setLabels(null);
      return () => {
        cancelled = true;
      };
    }
    fetchKbEvalLabels(kbId)
      .then((loaded) => {
        if (!cancelled) setLabels(loaded);
      })
      .catch((cause: unknown) => {
        if (!cancelled) setLabelError(knowledgeError(cause));
      });
    return () => {
      cancelled = true;
    };
  }, [kbId]);

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

  const mark = async (passage: KbRetrievalPassage) => {
    if (!kbId || !result) return;
    setLabelBusy(true);
    setLabelError(null);
    try {
      await createKbEvalLabel({
        kbId,
        query: result.query.trim(),
        documentId: passage.documentId,
        seq: passage.seq,
      });
      await refreshLabels();
    } catch (cause: unknown) {
      console.error(cause);
      setLabelError(knowledgeError(cause));
    } finally {
      setLabelBusy(false);
    }
  };

  const removeLabel = async (label: KbEvalLabel) => {
    setLabelBusy(true);
    setLabelError(null);
    try {
      await deleteKbEvalLabel(label.id);
      await refreshLabels();
    } catch (cause: unknown) {
      console.error(cause);
      setLabelError(knowledgeError(cause));
    } finally {
      setLabelBusy(false);
    }
  };

  const labelForQuery = (queryText: string): KbEvalLabel | null =>
    labels?.items.find((label) => label.query === queryText.trim()) ?? null;

  return (
    <section className={styles.section} aria-labelledby="kb-retrieval-title">
      <header className={styles.sectionHeader}>
        <h2 id="kb-retrieval-title">检索调试</h2>
        <span className={styles.hint}>同一候选池 · 向量序与当前配置对照</span>
      </header>

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
                    onMark={labelBusy ? undefined : mark}
                  />
                </div>
              ) : (
                <>
                  <p className={styles.hint}>rerank 未启用，仅显示向量序。</p>
                  <PassageColumn
                    title="向量序"
                    passages={result.vector}
                    label={labelForQuery(result.query)}
                    onMark={labelBusy ? undefined : mark}
                  />
                </>
              )}
            </div>
          ) : null}

          <div className={styles.evalLabels}>
            <div className={styles.evalLabelsHeader}>
              <span>评测标注</span>
              <span className={styles.hint}>
                {labels ? `${labels.total} / ${labels.limit}` : "—"}
              </span>
            </div>
            {labelError ? <Banner tone="error">{labelError}</Banner> : null}
            {!labels || labels.items.length === 0 ? (
              <p className={styles.hint}>
                还没有标注。查询后，把正确片段标为期望答案；重建导致的目标变化会标为「过期待确认」。
              </p>
            ) : (
              <ul className={styles.labelList}>
                {labels.items.map((label) => (
                  <li key={label.id} className={styles.labelItem}>
                    <div className={styles.labelMain}>
                      <span className={styles.labelQuery}>{label.query}</span>
                      <span className={styles.labelMeta}>
                        {label.title ?? "（文档已删除）"}
                        {label.page != null ? ` · 第 ${label.page} 页` : ""} · #{label.seq}
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
                      disabled={labelBusy}
                      onClick={() => void removeLabel(label)}
                    >
                      删除
                    </Button>
                  </li>
                ))}
              </ul>
            )}
          </div>
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
}: {
  title: string;
  caption?: string;
  passages: KbRetrievalPassage[];
  label?: KbEvalLabel | null;
  onMark?: (passage: KbRetrievalPassage) => void;
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
                    {isTarget ? (
                      <span className={styles.markedBadge}>已标注</span>
                    ) : (
                      <Button size="sm" variant="ghost" onClick={() => onMark(passage)}>
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
