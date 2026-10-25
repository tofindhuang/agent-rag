"use client";

import { Fragment } from "react";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { previousCompleteRun } from "@/hooks/useRetrievalQuality";
import type { RetrievalQuality } from "@/hooks/useRetrievalQuality";
import type { KbEvalItem, KbEvalRun, KbEvalRunSummary } from "@/types/knowledge";
import styles from "./knowledge.module.css";

interface RetrievalEvalSectionProps {
  quality: RetrievalQuality;
  processingCount: number;
  staleCount: number;
}

/** Evaluation run, report and history for the selected KB. */
export function RetrievalEvalSection({
  quality,
  processingCount,
  staleCount,
}: RetrievalEvalSectionProps) {
  const canRun =
    quality.validLabels > 0 && staleCount === 0 && !quality.evalBusy;

  return (
    <div className={styles.evalBlock}>
      <div className={styles.evalLabelsHeader}>
        <span>质量评测</span>
        <Button
          size="sm"
          disabled={!canRun}
          onClick={() => void quality.startEvaluation()}
        >
          {quality.evalBusy ? "评测中…" : "运行评测"}
        </Button>
      </div>
      {quality.evalError ? <Banner tone="error">{quality.evalError}</Banner> : null}
      {quality.validLabels === 0 ? (
        <p className={styles.hint}>至少需要 1 条有效标注才能运行。</p>
      ) : null}
      {quality.validLabels > 0 && quality.validLabels < 5 ? (
        <p className={styles.hint}>样本过少（{quality.validLabels} 条），指标仅供参考。</p>
      ) : null}
      {staleCount > 0 ? (
        <p className={styles.hint}>
          有 {staleCount} 篇文档待重建，先在文档列表执行「全部重建」再评测。
        </p>
      ) : null}
      {processingCount > 0 ? (
        <p className={styles.hint}>
          {processingCount} 篇文档仍在处理中，指标可能偏低。
        </p>
      ) : null}

      {quality.lastRun ? <RunReport run={quality.lastRun} /> : null}

      {quality.runs.length === 0 ? (
        <p className={styles.hint}>
          还没有运行记录。运行后这里会保留最近 20 次并可与上次对比。
        </p>
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
              {quality.runs.map((run) => {
                const baseline = previousCompleteRun(quality.runs, run.runId);
                const expanded = quality.expandedRunId === run.runId;
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
                          onClick={() => void quality.toggleRun(run)}
                        >
                          {expanded ? "收起" : "对比"}
                        </Button>
                      </td>
                    </tr>
                    {expanded ? (
                      <tr>
                        <td colSpan={8} className={styles.evalDiffCell}>
                          <RunDiff
                            current={quality.runDetails[run.runId]}
                            baseline={
                              baseline ? quality.runDetails[baseline.runId] : undefined
                            }
                            loading={quality.detailBusy}
                            expected={Boolean(baseline)}
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
  );
}

function RunReport({ run }: { run: KbEvalRun }) {
  return (
    <div className={styles.evalReport}>
      {!run.complete ? (
        <div className={styles.evalIncomplete} role="status">
          本次运行不完整：{run.skippedCount} 条未执行（预算用尽），指标仅基于已执行的{" "}
          {run.executedCount} 条。
        </div>
      ) : null}
      <div className={styles.metricGrid}>
        <MetricCard label="当前配置 recall@5" value={run.metrics.recall5} hint="前 5 条是否含正确片段" />
        <MetricCard label="当前配置 MRR@5" value={run.metrics.mrr5} hint="正确片段排得有多靠前" />
        <MetricCard label="候选池 recall@10" value={run.metrics.poolRecall10} hint="embedding 上限" />
        <MetricCard label="直搜 recall@5" value={run.metrics.baselineRecall5} hint="rerank 关闭对照" />
        <MetricCard label="直搜 MRR@5" value={run.metrics.baselineMrr5} hint="rerank 关闭对照" />
      </div>
      {run.rerankModel == null ? (
        <p className={styles.hint}>rerank 未启用，当前配置指标等于直搜指标。</p>
      ) : null}
      {run.rotten.length > 0 ? (
        <p className={styles.hint}>
          已排除 {run.rotten.length} 条过期待确认标注：
          {run.rotten.map((issue) => issue.query).join("、")}
        </p>
      ) : null}
      <p className={styles.hint}>
        模型：{run.embeddingModel}（{run.embeddingDim} 维）
        {run.rerankModel ? ` · rerank：${run.rerankModel}` : ""} · 耗时{" "}
        {(run.durationMs / 1000).toFixed(1)}s
      </p>
    </div>
  );
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
  expected,
}: {
  current?: KbEvalRun;
  baseline?: KbEvalRun;
  loading: boolean;
  expected: boolean;
}) {
  if (!current) return <p className={styles.hint}>对比加载中…</p>;
  if (!baseline) {
    return (
      <p className={styles.hint}>
        {loading || expected ? "对比加载中…" : "没有可比较的完整运行。"}
      </p>
    );
  }

  const before = new Map<number, KbEvalItem>(
    baseline.items.map((item) => [item.labelId, item]),
  );
  const rows = current.items.flatMap((item) => {
    const previous = before.get(item.labelId);
    let kind: DiffKind | null;
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
      kind = null; // both hit: stable, reported only as "no change"
    }
    return kind === null ? [] : [{ item, previous, kind }];
  });

  if (rows.length === 0) {
    return <p className={styles.hint}>与上次完整运行相比，逐条结果没有变化。</p>;
  }
  return (
    <ul className={styles.diffList}>
      {rows.map(({ item, previous, kind }) => (
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
