"use client";

import { Fragment, useCallback, useEffect, useState } from "react";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { TextField } from "@/components/ui/TextField";
import {
  createKbEvalLabel,
  deleteKbEvalLabel,
  fetchKbEvalLabels,
  fetchKbEvalRun,
  fetchKbEvalRuns,
  knowledgeError,
  queryKbRetrieval,
  runKbEval,
} from "@/lib/api/knowledge";
import type {
  KbEvalItem,
  KbEvalLabel,
  KbEvalLabelList,
  KbEvalRun,
  KbEvalRunSummary,
  KbRetrievalPassage,
  KbRetrievalQueryResult,
} from "@/types/knowledge";
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
  const [labels, setLabels] = useState<KbEvalLabelList | null>(null);
  const [labelBusy, setLabelBusy] = useState(false);
  const [labelError, setLabelError] = useState<string | null>(null);
  const [runs, setRuns] = useState<KbEvalRunSummary[]>([]);
  const [evalBusy, setEvalBusy] = useState(false);
  const [evalError, setEvalError] = useState<string | null>(null);
  const [lastRun, setLastRun] = useState<KbEvalRun | null>(null);
  const [expandedRunId, setExpandedRunId] = useState<number | null>(null);
  const [runDetails, setRunDetails] = useState<Record<number, KbEvalRun>>({});
  const [detailBusy, setDetailBusy] = useState(false);

  const refreshLabels = useCallback(async () => {
    if (!kbId) {
      setLabels(null);
      return;
    }
    setLabels(await fetchKbEvalLabels(kbId));
  }, [kbId]);

  const refreshRuns = useCallback(async () => {
    if (!kbId) {
      setRuns([]);
      return;
    }
    setRuns((await fetchKbEvalRuns(kbId)).items);
  }, [kbId]);

  useEffect(() => {
    let cancelled = false;
    setLabelError(null);
    setEvalError(null);
    setResult(null);
    setLastRun(null);
    setExpandedRunId(null);
    setRunDetails({});
    if (!kbId) {
      setLabels(null);
      setRuns([]);
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
    fetchKbEvalRuns(kbId)
      .then((list) => {
        if (!cancelled) setRuns(list.items);
      })
      .catch((cause: unknown) => {
        if (!cancelled) setEvalError(knowledgeError(cause));
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

  const startEvaluation = async () => {
    if (!kbId) return;
    setEvalBusy(true);
    setEvalError(null);
    try {
      const report = await runKbEval(kbId);
      setLastRun(report);
      setRunDetails((previous) => ({ ...previous, [report.runId]: report }));
      setExpandedRunId(report.runId);
      await refreshRuns();
      await refreshLabels();
    } catch (cause: unknown) {
      console.error(cause);
      setEvalError(knowledgeError(cause));
    } finally {
      setEvalBusy(false);
    }
  };

  const ensureDetail = async (runId: number) => {
    if (runDetails[runId]) return;
    setDetailBusy(true);
    try {
      const detail = await fetchKbEvalRun(runId);
      setRunDetails((previous) => ({ ...previous, [runId]: detail }));
    } catch (cause: unknown) {
      console.error(cause);
      setEvalError(knowledgeError(cause));
    } finally {
      setDetailBusy(false);
    }
  };

  const toggleRun = async (run: KbEvalRunSummary) => {
    if (expandedRunId === run.runId) {
      setExpandedRunId(null);
      return;
    }
    setExpandedRunId(run.runId);
    await ensureDetail(run.runId);
    const baseline = previousCompleteRun(runs, run.runId);
    if (baseline) {
      await ensureDetail(baseline.runId);
    }
  };

  const labelForQuery = (queryText: string): KbEvalLabel | null =>
    labels?.items.find((label) => label.query === queryText.trim()) ?? null;

  const validLabels = labels?.items.filter((label) => label.state === "ok").length ?? 0;
  const canRun = Boolean(kbId) && validLabels > 0 && staleCount === 0 && !evalBusy;

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

          <div className={styles.evalBlock}>
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

          <div className={styles.evalBlock}>
            <div className={styles.evalLabelsHeader}>
              <span>质量评测</span>
              <Button size="sm" disabled={!canRun} onClick={() => void startEvaluation()}>
                {evalBusy ? "评测中…" : "运行评测"}
              </Button>
            </div>
            {evalError ? <Banner tone="error">{evalError}</Banner> : null}
            {validLabels === 0 ? (
              <p className={styles.hint}>至少需要 1 条有效标注才能运行。</p>
            ) : null}
            {staleCount > 0 ? (
              <p className={styles.hint}>
                有 {staleCount} 篇文档待重建，先在文档列表执行「全部重建」再评测。
              </p>
            ) : null}
            {validLabels > 0 && validLabels < 5 ? (
              <p className={styles.hint}>样本过少（{validLabels} 条），指标仅供参考。</p>
            ) : null}
            {processingCount > 0 ? (
              <p className={styles.hint}>
                {processingCount} 篇文档仍在处理中，指标可能偏低。
              </p>
            ) : null}

            {lastRun ? (
              <div className={styles.evalReport}>
                {!lastRun.complete ? (
                  <div className={styles.evalIncomplete} role="status">
                    本次运行不完整：{lastRun.skippedCount} 条未执行（预算用尽），指标仅基于已执行的{" "}
                    {lastRun.executedCount} 条。
                  </div>
                ) : null}
                <div className={styles.metricGrid}>
                  <MetricCard
                    label="当前配置 recall@5"
                    value={lastRun.metrics.recall5}
                    hint="前 5 条是否含正确片段"
                  />
                  <MetricCard
                    label="当前配置 MRR@5"
                    value={lastRun.metrics.mrr5}
                    hint="正确片段排得有多靠前"
                  />
                  <MetricCard
                    label="候选池 recall@10"
                    value={lastRun.metrics.poolRecall10}
                    hint="embedding 上限"
                  />
                  <MetricCard
                    label="直搜 recall@5"
                    value={lastRun.metrics.baselineRecall5}
                    hint="rerank 关闭对照"
                  />
                  <MetricCard
                    label="直搜 MRR@5"
                    value={lastRun.metrics.baselineMrr5}
                    hint="rerank 关闭对照"
                  />
                </div>
                {lastRun.rerankModel == null ? (
                  <p className={styles.hint}>
                    rerank 未启用，当前配置指标等于直搜指标。
                  </p>
                ) : null}
                {lastRun.rotten.length > 0 ? (
                  <p className={styles.hint}>
                    已排除 {lastRun.rotten.length} 条过期待确认标注：
                    {lastRun.rotten.map((issue) => issue.query).join("、")}
                  </p>
                ) : null}
                <p className={styles.hint}>
                  模型：{lastRun.embeddingModel}（{lastRun.embeddingDim} 维）
                  {lastRun.rerankModel ? ` · rerank：${lastRun.rerankModel}` : ""} · 耗时{" "}
                  {(lastRun.durationMs / 1000).toFixed(1)}s
                </p>
              </div>
            ) : null}

            {runs.length === 0 ? (
              <p className={styles.hint}>还没有运行记录。运行后这里会保留最近 20 次并可与上次对比。</p>
            ) : (
              <div className={styles.evalHistory}>
                <table className={styles.evalTable}>
                  <thead>
                    <tr>
                      <th>时间</th>
                      <th>模型</th>
                      <th>recall@5</th>
                      <th>MRR@5</th>
                      <th>池 recall@10</th>
                      <th>直搜 recall@5</th>
                      <th>状态</th>
                      <th />
                    </tr>
                  </thead>
                  <tbody>
                    {runs.map((run, index) => {
                      const baseline = previousCompleteRun(runs, run.runId);
                      const expanded = expandedRunId === run.runId;
                      return (
                        <Fragment key={run.runId}>
                          <tr>
                            <td>{formatTime(run.createdAt)}</td>
                            <td className={styles.evalModelCell} title={run.embeddingModel}>
                              {run.embeddingModel}
                              {run.rerankModel ? ` + ${run.rerankModel}` : " · 无 rerank"}
                            </td>
                            <td>
                              {run.metrics.recall5.toFixed(2)}{" "}
                              <Delta current={run} baseline={baseline} field="recall5" />
                            </td>
                            <td>
                              {run.metrics.mrr5.toFixed(2)}{" "}
                              <Delta current={run} baseline={baseline} field="mrr5" />
                            </td>
                            <td>{run.metrics.poolRecall10.toFixed(2)}</td>
                            <td>{run.metrics.baselineRecall5.toFixed(2)}</td>
                            <td>
                              {run.complete ? (
                                "完整"
                              ) : (
                                <span className={styles.incompleteBadge}>
                                  不完整 · {run.skippedCount} 未执行
                                </span>
                              )}
                            </td>
                            <td>
                              <Button
                                variant="ghost"
                                size="sm"
                                onClick={() => void toggleRun(run)}
                              >
                                {expanded ? "收起" : "对比"}
                              </Button>
                            </td>
                          </tr>
                          {expanded ? (
                            <tr>
                              <td colSpan={8} className={styles.evalDiffCell}>
                                <RunDiff
                                  current={runDetails[run.runId]}
                                  baseline={
                                    baseline ? runDetails[baseline.runId] : undefined
                                  }
                                  loading={detailBusy}
                                />
                              </td>
                            </tr>
                          ) : null}
                        </Fragment>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </>
      )}
    </section>
  );
}

function previousCompleteRun(
  runs: KbEvalRunSummary[],
  runId: number,
): KbEvalRunSummary | null {
  const index = runs.findIndex((run) => run.runId === runId);
  if (index < 0) return null;
  for (let i = index + 1; i < runs.length; i++) {
    if (runs[i].complete) return runs[i];
  }
  return null;
}

function Delta({
  current,
  baseline,
  field,
}: {
  current: KbEvalRunSummary;
  baseline: KbEvalRunSummary | null;
  field: "recall5" | "mrr5";
}) {
  if (!current.complete || !baseline) return null;
  const delta = current.metrics[field] - baseline.metrics[field];
  if (Math.abs(delta) < 0.005) return <span className={styles.deltaFlat}>±0.00</span>;
  return (
    <span className={delta > 0 ? styles.deltaUp : styles.deltaDown}>
      {delta > 0 ? "+" : ""}
      {delta.toFixed(2)}
    </span>
  );
}

function MetricCard({
  label,
  value,
  hint,
}: {
  label: string;
  value: number;
  hint: string;
}) {
  return (
    <div className={styles.metricCard}>
      <span className={styles.metricValue}>{value.toFixed(2)}</span>
      <span className={styles.metricLabel}>{label}</span>
      <span className={styles.metricHint}>{hint}</span>
    </div>
  );
}

const DIFF_LABEL = {
  new: "新命中",
  lost: "新丢失",
  "still-miss": "仍丢失",
  rotten: "过期待确认",
  skipped: "未执行",
} as const;

type DiffKind = keyof typeof DIFF_LABEL;

function RunDiff({
  current,
  baseline,
  loading,
}: {
  current?: KbEvalRun;
  baseline?: KbEvalRun;
  loading: boolean;
}) {
  if (!current) return <p className={styles.hint}>对比加载中…</p>;
  if (loading && !baseline) return <p className={styles.hint}>对比加载中…</p>;
  if (!baseline) {
    return <p className={styles.hint}>没有可比较的完整运行。</p>;
  }

  const before = new Map<number, KbEvalItem>(
    baseline.items.map((item) => [item.labelId, item]),
  );
  const rows = current.items.map((item) => {
    const previous = before.get(item.labelId);
    let kind: DiffKind;
    if (item.status === "skipped") {
      kind = "skipped";
    } else if (item.status === "rotten") {
      kind = "rotten";
    } else if (item.status === "hit" && previous?.status !== "hit") {
      kind = "new";
    } else if (item.status !== "hit" && previous?.status === "hit") {
      kind = "lost";
    } else if (item.status !== "hit") {
      kind = "still-miss";
    } else {
      kind = "new"; // unreachable: both hit means stable and is filtered out below
    }
    const stable = item.status === "hit" && previous?.status === "hit";
    return { item, previous, kind, stable };
  });
  const interesting = rows.filter((row) => !row.stable);

  if (interesting.length === 0) {
    return <p className={styles.hint}>与上次完整运行相比，逐条结果没有变化。</p>;
  }
  return (
    <ul className={styles.diffList}>
      {interesting.map(({ item, previous, kind }) => (
        <li key={item.labelId} className={styles.diffItem}>
          <span className={styles.diffKind}>{DIFF_LABEL[kind]}</span>
          <span className={styles.diffQuery}>{item.query}</span>
          <span className={styles.diffRanks}>
            上次 {rankText(previous)} → 本次 {rankText(item)}
          </span>
        </li>
      ))}
    </ul>
  );
}

function rankText(item?: KbEvalItem): string {
  if (!item) return "—";
  if (item.status === "hit" && item.rankFinal != null) return `#${item.rankFinal}`;
  if (item.rankVector != null) return `池 #${item.rankVector}`;
  return "未命中";
}

function formatTime(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "—" : date.toLocaleString("zh-CN");
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
