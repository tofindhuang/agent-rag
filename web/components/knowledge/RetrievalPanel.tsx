"use client";

import { useState } from "react";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { TextField } from "@/components/ui/TextField";
import { knowledgeError, queryKbRetrieval } from "@/lib/api/knowledge";
import type { KbRetrievalPassage, KbRetrievalQueryResult } from "@/types/knowledge";
import styles from "./knowledge.module.css";

interface RetrievalPanelProps {
  kbId: string | null;
}

/**
 * Retrieval debugging: one candidate pool rendered twice so the effect of
 * rerank is visible passage by passage. It never calls the model.
 */
export function RetrievalPanel({ kbId }: RetrievalPanelProps) {
  const [query, setQuery] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<KbRetrievalQueryResult | null>(null);

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
                  />
                </div>
              ) : (
                <>
                  <p className={styles.hint}>rerank 未启用，仅显示向量序。</p>
                  <PassageColumn title="向量序" passages={result.vector} />
                </>
              )}
            </div>
          ) : null}
        </>
      )}
    </section>
  );
}

function PassageColumn({
  title,
  caption,
  passages,
}: {
  title: string;
  caption?: string;
  passages: KbRetrievalPassage[];
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
          {passages.map((passage) => (
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
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}
