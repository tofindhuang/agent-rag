"use client";

import { useEffect, useState } from "react";
import {
  Bar,
  BarChart,
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { useAdminOverview } from "@/hooks/useAdminOverview";
import type { AdminWindow } from "@/types/admin";
import styles from "./admin.module.css";

/** Overview tab: activity, usage, executions, retrieval, compactions and sandbox. */
export function AdminOverview() {
  const [selected, setSelected] = useState<AdminWindow>("7d");
  const { data, loading, error, refresh } = useAdminOverview(selected);
  const daily = (data?.daily ?? []).map((day) => ({
    ...day,
    totalTokens:
      day.tokensIn + day.tokensOut + day.cacheReadTokens + day.cacheWriteTokens,
    hitRatePercent: day.hitRate == null ? null : Math.round(day.hitRate * 1000) / 10,
  }));

  useEffect(() => {
    const timer = window.setInterval(() => {
      if (document.visibilityState === "visible") void refresh();
    }, 60_000);
    return () => window.clearInterval(timer);
  }, [refresh]);

  return (
    <div className={styles.panel}>
      <div className={styles.toolbar}>
        <div className={styles.actions}>
          <Button
            size="sm"
            variant={selected === "7d" ? "primary" : "secondary"}
            onClick={() => setSelected("7d")}
          >
            7 天
          </Button>
          <Button
            size="sm"
            variant={selected === "30d" ? "primary" : "secondary"}
            onClick={() => setSelected("30d")}
          >
            30 天
          </Button>
        </div>
        <Button size="sm" variant="ghost" disabled={loading} onClick={() => void refresh()}>
          刷新
        </Button>
      </div>

      {error ? <Banner tone="error">{error}</Banner> : null}
      {loading && !data ? (
        <p className={styles.hint} role="status">
          指标加载中…
        </p>
      ) : null}

      {data ? (
        <>
          <div className={styles.cards}>
            <Stat label="运行中 run" value={formatNumber(data.active.runningRuns)} />
            <Stat label="15 分钟活跃会话" value={formatNumber(data.active.activeSessions15m)} />
            <Stat
              label="输入 token"
              value={formatNumber(data.usage.tokensIn)}
              hint={data.usage.unpricedRuns > 0 ? "含未定价运行" : undefined}
            />
            <Stat label="输出 token" value={formatNumber(data.usage.tokensOut)} />
            <Stat label="缓存读" value={formatNumber(data.usage.cacheReadTokens)} />
            <Stat label="缓存写" value={formatNumber(data.usage.cacheWriteTokens)} />
            <Stat label="成本" value={formatCost(data.usage.cost, data.currency)} />
            <Stat label="执行" value={formatNumber(data.executions.total)} />
            <Stat
              label="检索命中率"
              value={
                data.retrieval.hitRate == null
                  ? "—"
                  : `${Math.round(data.retrieval.hitRate * 1000) / 10}%`
              }
              hint={`${formatNumber(data.retrieval.calls)} 次调用`}
            />
            <Stat label="compaction" value={formatNumber(data.compactions.count)} />
            <Stat
              label="排队 p95"
              value={data.sandbox.queueP95Ms == null ? "—" : `${data.sandbox.queueP95Ms} ms`}
            />
            <Stat
              label="执行耗时 p95"
              value={
                data.sandbox.durationP95Ms == null ? "—" : `${data.sandbox.durationP95Ms} ms`
              }
            />
          </div>

          {data.usage.unpricedRuns > 0 ? (
            <p className={styles.hint}>
              有 {data.usage.unpricedRuns} 次运行使用的模型未配置价格，未计入成本。
            </p>
          ) : null}

          {daily.length === 0 ? (
            <p className={styles.hint}>这个时间窗口还没有运营数据。</p>
          ) : (
            <>
              <div className={styles.chartGrid}>
                <div className={styles.chart}>
                  <h3>总 token 趋势</h3>
                  <ResponsiveContainer width="100%" height="100%">
                    <LineChart data={daily}>
                      <CartesianGrid stroke="var(--border)" strokeDasharray="3 3" />
                      <XAxis dataKey="date" tick={{ fontSize: 11 }} />
                      <YAxis tick={{ fontSize: 11 }} width={56} />
                      <Tooltip />
                      <Line
                        type="monotone"
                        dataKey="totalTokens"
                        name="总 token"
                        stroke="var(--accent)"
                        strokeWidth={2}
                        dot={false}
                      />
                    </LineChart>
                  </ResponsiveContainer>
                </div>
                <div className={styles.chart}>
                  <h3>执行与 compaction</h3>
                  <ResponsiveContainer width="100%" height="100%">
                    <BarChart data={daily}>
                      <CartesianGrid stroke="var(--border)" strokeDasharray="3 3" />
                      <XAxis dataKey="date" tick={{ fontSize: 11 }} />
                      <YAxis tick={{ fontSize: 11 }} width={56} />
                      <Tooltip />
                      <Bar dataKey="executions" name="执行" fill="var(--accent)" radius={[3, 3, 0, 0]} />
                      <Bar dataKey="compactions" name="compaction" fill="var(--warning)" radius={[3, 3, 0, 0]} />
                    </BarChart>
                  </ResponsiveContainer>
                </div>
                <div className={styles.chart}>
                  <h3>检索命中率</h3>
                  <ResponsiveContainer width="100%" height="100%">
                    <LineChart data={daily}>
                      <CartesianGrid stroke="var(--border)" strokeDasharray="3 3" />
                      <XAxis dataKey="date" tick={{ fontSize: 11 }} />
                      <YAxis tick={{ fontSize: 11 }} width={56} unit="%" />
                      <Tooltip />
                      <Line
                        type="monotone"
                        dataKey="hitRatePercent"
                        name="命中率"
                        stroke="var(--success)"
                        strokeWidth={2}
                        dot={false}
                        connectNulls
                      />
                    </LineChart>
                  </ResponsiveContainer>
                </div>
              </div>

              <div className={styles.tableWrap}>
                <table className={styles.table}>
                  <thead>
                    <tr>
                      <th>日期</th>
                      <th>输入</th>
                      <th>输出</th>
                      <th>缓存读</th>
                      <th>缓存写</th>
                      <th>成本</th>
                      <th>执行</th>
                      <th>compaction</th>
                      <th>命中率</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.daily.map((day) => (
                      <tr key={day.date}>
                        <td>{day.date}</td>
                        <td>{formatNumber(day.tokensIn)}</td>
                        <td>{formatNumber(day.tokensOut)}</td>
                        <td>{formatNumber(day.cacheReadTokens)}</td>
                        <td>{formatNumber(day.cacheWriteTokens)}</td>
                        <td>{formatCost(day.cost, data.currency)}</td>
                        <td>{formatNumber(day.executions)}</td>
                        <td>{formatNumber(day.compactions)}</td>
                        <td>
                          {day.hitRate == null
                            ? "—"
                            : `${Math.round(day.hitRate * 1000) / 10}%`}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          )}

          <p className={styles.hint}>
            执行：成功 {formatNumber(data.executions.succeeded)} · 失败{" "}
            {formatNumber(data.executions.failed)} · 超时{" "}
            {formatNumber(data.executions.timedOut)} · 检索失败调用{" "}
            {formatNumber(data.retrieval.failedCalls)} · 平均命中片段{" "}
            {data.retrieval.avgPassages == null ? "—" : data.retrieval.avgPassages}
          </p>
        </>
      ) : null}
    </div>
  );
}

function Stat({
  label,
  value,
  hint,
}: {
  label: string;
  value: string;
  hint?: string;
}) {
  return (
    <div className={styles.card}>
      <span className={styles.cardValue}>{value}</span>
      <span className={styles.cardLabel}>{label}</span>
      {hint ? <span className={styles.cardHint}>{hint}</span> : null}
    </div>
  );
}

function formatNumber(value: number): string {
  return new Intl.NumberFormat("zh-CN").format(value);
}

function formatCost(cost: number, currency: string): string {
  const symbol = currency === "CNY" ? "¥" : currency === "USD" ? "$" : `${currency} `;
  return `${symbol}${cost.toFixed(4)}`;
}
