"use client";

import { useState } from "react";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { TextField } from "@/components/ui/TextField";
import { fetchAdminTrace } from "@/lib/api/admin";
import { userMessage } from "@/lib/api/errors";
import type { AdminTrace as AdminTraceData, AdminTraceKey, AdminTraceNode } from "@/types/admin";
import { formatCost, formatNumber, formatTime } from "./format";
import styles from "./admin.module.css";

const KEY_LABEL: Record<AdminTraceKey, string> = {
  requestId: "request_id",
  turnId: "turn_id",
  runId: "run_id",
};

/** Trace tab: one run's metadata and timeline by request/turn/run id. */
export function AdminTrace() {
  const [key, setKey] = useState<AdminTraceKey>("requestId");
  const [value, setValue] = useState("");
  const [data, setData] = useState<AdminTraceData | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const trimmed = value.trim();
    if (trimmed.length === 0) return;
    setLoading(true);
    setError(null);
    try {
      setData(await fetchAdminTrace({ [key]: trimmed }));
    } catch (cause: unknown) {
      console.error(cause);
      setError(userMessage(cause));
      setData(null);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className={styles.panel}>
      <form className={styles.filters} onSubmit={submit}>
        <label className={styles.field}>
          <span>查询键</span>
          <select
            className={styles.select}
            value={key}
            onChange={(event) => setKey(event.target.value as AdminTraceKey)}
          >
            {Object.entries(KEY_LABEL).map(([itemKey, label]) => (
              <option key={itemKey} value={itemKey}>
                {label}
              </option>
            ))}
          </select>
        </label>
        <TextField
          label={KEY_LABEL[key]}
          value={value}
          placeholder="粘贴 ID"
          onChange={(event) => setValue(event.target.value)}
        />
        <Button type="submit" size="sm" disabled={loading || value.trim().length === 0}>
          {loading ? "查询中…" : "查询"}
        </Button>
      </form>

      {error ? <Banner tone="error">{error}</Banner> : null}

      {data ? (
        <>
          <div className={styles.detailHeader}>
            <h2>{data.summary.email}</h2>
            <span className={styles.hint}>
              #{data.summary.userId} · {data.summary.status} · 模型{" "}
              {data.summary.modelId ?? "—"}
            </span>
          </div>
          <div className={styles.cards}>
            <Stat label="输入 token" value={formatNumber(data.summary.tokensIn)} />
            <Stat label="输出 token" value={formatNumber(data.summary.tokensOut)} />
            <Stat label="缓存读" value={formatNumber(data.summary.cacheReadTokens)} />
            <Stat label="缓存写" value={formatNumber(data.summary.cacheWriteTokens)} />
            <Stat
              label="成本"
              value={
                data.summary.cost == null
                  ? "—"
                  : formatCost(data.summary.cost, data.currency)
              }
            />
            <Stat label="开始" value={formatTime(data.summary.startedAt)} />
            <Stat label="结束" value={formatTime(data.summary.settledAt)} />
          </div>
          <p className={styles.hint}>
            request {data.summary.requestId ?? "—"} · turn {data.summary.turnId} · run{" "}
            {data.summary.runId} · session {data.summary.sessionId}
          </p>

          <ol className={styles.timeline}>
            {data.timeline.map((node, index) => (
              <li key={`${node.type}-${index}`} className={styles.timelineItem}>
                <span className={styles.timelineDot} />
                <div className={styles.timelineBody}>
                  <div className={styles.timelineHead}>
                    <span className={styles.timelineType}>{nodeLabel(node)}</span>
                    <span className={styles.hint}>{formatTime(node.at)}</span>
                    {node.missing ? (
                      <span className={styles.missingBadge}>未记录</span>
                    ) : null}
                  </div>
                  <TimelineDetail node={node} />
                </div>
              </li>
            ))}
          </ol>
        </>
      ) : null}
    </div>
  );
}

function TimelineDetail({ node }: { node: AdminTraceNode }) {
  if (node.type === "run_started") {
    return <p className={styles.timelineText}>模型：{node.modelId ?? "—"}</p>;
  }
  if (node.type === "assistant") {
    return (
      <p className={styles.timelineText}>
        seq {node.seq ?? "—"} · 输入 {formatNumber(node.tokensIn ?? 0)} · 输出{" "}
        {formatNumber(node.tokensOut ?? 0)} · 缓存读{" "}
        {formatNumber(node.cacheReadTokens ?? 0)} · 缓存写{" "}
        {formatNumber(node.cacheWriteTokens ?? 0)}
        {node.toolCalls.length > 0
          ? ` · 工具 ${node.toolCalls.map((call) => call.name ?? "?").join("、")}`
          : ""}
      </p>
    );
  }
  if (node.type === "run_settled") {
    return (
      <p className={styles.timelineText}>
        状态 {node.status ?? "—"}
        {node.errorCode ? ` · ${node.errorCode}` : ""}
        {node.errorMessage ? ` · ${node.errorMessage}` : ""}
      </p>
    );
  }
  if (node.name === "kb_search") {
    return (
      <p className={styles.timelineText}>
        命中片段 {node.passages ?? 0} · 最高分{" "}
        {node.topScore == null ? "—" : node.topScore.toFixed(4)}
      </p>
    );
  }
  return (
    <p className={styles.timelineText}>
      {node.execId ? `exec ${node.execId} · ` : ""}
      状态 {node.status ?? "—"}
      {node.durationMs != null ? ` · 耗时 ${node.durationMs}ms` : ""}
      {node.queueMs != null ? ` · 排队 ${node.queueMs}ms` : ""}
      {node.exitCode != null ? ` · exit ${node.exitCode}` : ""}
    </p>
  );
}

function nodeLabel(node: AdminTraceNode): string {
  if (node.type === "run_started") return "run 开始";
  if (node.type === "run_settled") return "run 结束";
  if (node.type === "assistant") return "assistant 消息";
  return node.name ?? "工具";
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className={styles.card}>
      <span className={styles.cardValue}>{value}</span>
      <span className={styles.cardLabel}>{label}</span>
    </div>
  );
}
