"use client";

import { useState } from "react";
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
import { useUsage } from "@/hooks/useUsage";
import type { UsageQuota, UsageWindowKey } from "@/types/usage";
import styles from "./usage.module.css";

function formatReset(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "";
  return date.toLocaleDateString("zh-CN", { month: "long", day: "numeric" });
}

function formatTokens(value: number): string {
  return value.toLocaleString("zh-CN");
}

/** Monthly token budget (ADR-0032); hidden entirely when no quota is configured. */
function QuotaSummary({ quota }: { quota: UsageQuota | null }) {
  if (!quota) return null;
  if (!quota.enforced || quota.limitTokens == null) {
    return (
      <p className={styles.hint}>
        未设置配额 · 本月已用 {formatTokens(quota.usedTokens)} tokens
      </p>
    );
  }
  const percent =
    quota.limitTokens === 0 ? 0 : Math.min(100, (quota.usedTokens / quota.limitTokens) * 100);
  const reset = quota.resetsAt ? formatReset(quota.resetsAt) : null;
  return (
    <p className={styles.quotaLine} role="status">
      本月额度：已用 {formatTokens(quota.usedTokens)} / {formatTokens(quota.limitTokens)}（
      {percent.toFixed(1)}%）
      {reset ? ` · ${reset}重置` : ""}
    </p>
  );
}

/** Self-service usage: today, the selected window and per-session ranking. */
export function UsageSettings() {
  const [window, setWindow] = useState<UsageWindowKey>("30d");
  const { data, loading, error, refresh } = useUsage(window);
  const daily = (data?.daily ?? []).map((day) => ({
    ...day,
    totalTokens:
      day.tokensIn + day.tokensOut + day.cacheReadTokens + day.cacheWriteTokens,
  }));

  return (
    <section className={styles.section} aria-labelledby="usage-title">
      <div className={styles.sectionHeader}>
        <h2 id="usage-title">用量</h2>
        <div className={styles.actions}>
          <Button
            size="sm"
            variant={window === "7d" ? "primary" : "secondary"}
            onClick={() => setWindow("7d")}
          >
            7 天
          </Button>
          <Button
            size="sm"
            variant={window === "30d" ? "primary" : "secondary"}
            onClick={() => setWindow("30d")}
          >
            30 天
          </Button>
          <Button size="sm" variant="ghost" disabled={loading} onClick={() => void refresh()}>
            刷新
          </Button>
        </div>
      </div>

      {error ? <Banner tone="error">{error}</Banner> : null}
      <QuotaSummary quota={data?.quota ?? null} />
      {loading && !data ? (
        <p className={styles.hint} role="status">
          用量加载中…
        </p>
      ) : null}

      {data ? (
        <>
          <div className={styles.cards}>
            <Stat label="今天 · 输入" value={formatNumber(data.today.tokensIn)} />
            <Stat label="今天 · 输出" value={formatNumber(data.today.tokensOut)} />
            <Stat label="今天 · 缓存读" value={formatNumber(data.today.cacheReadTokens)} />
            <Stat label="今天 · 缓存写" value={formatNumber(data.today.cacheWriteTokens)} />
            <Stat
              label="今天 · 成本"
              value={formatCost(data.today, data.currency)}
              hint={data.today.unpricedRuns > 0 ? "含未定价运行" : undefined}
            />
            <Stat label="今天 · 执行" value={formatNumber(data.today.executions)} />
            <Stat label="今天 · 检索" value={formatNumber(data.today.searches)} />
          </div>

          <p className={styles.hint}>
            {window === "7d" ? "近 7 天" : "近 30 天"}：输入{" "}
            {formatNumber(data.window.tokensIn)} · 输出{" "}
            {formatNumber(data.window.tokensOut)} · 缓存读{" "}
            {formatNumber(data.window.cacheReadTokens)} · 缓存写{" "}
            {formatNumber(data.window.cacheWriteTokens)} · 成本{" "}
            {formatCost(data.window, data.currency)} · 执行{" "}
            {formatNumber(data.window.executions)} · 检索{" "}
            {formatNumber(data.window.searches)}
          </p>
          {data.window.unpricedRuns > 0 ? (
            <p className={styles.hint}>
              有 {data.window.unpricedRuns} 次运行使用的模型未配置价格，未计入成本。
            </p>
          ) : null}

          {daily.length === 0 ? (
            <p className={styles.hint}>这个时间窗口还没有用量数据。</p>
          ) : (
            <>
              <div className={styles.chartGrid}>
                <div className={styles.chart}>
                  <h3>总 token 趋势</h3>
                  <ResponsiveContainer width="100%" height="100%">
                    <LineChart data={daily}>
                      <CartesianGrid stroke="var(--border)" strokeDasharray="3 3" />
                      <XAxis dataKey="date" tick={{ fontSize: 11 }} />
                      <YAxis tick={{ fontSize: 11 }} width={48} />
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
                  <h3>成本趋势</h3>
                  <ResponsiveContainer width="100%" height="100%">
                    <BarChart data={daily}>
                      <CartesianGrid stroke="var(--border)" strokeDasharray="3 3" />
                      <XAxis dataKey="date" tick={{ fontSize: 11 }} />
                      <YAxis tick={{ fontSize: 11 }} width={48} />
                      <Tooltip />
                      <Bar dataKey="cost" name="成本" fill="var(--accent)" radius={[3, 3, 0, 0]} />
                    </BarChart>
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
                      <th>检索</th>
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
                        <td>{formatCost(day, data.currency)}</td>
                        <td>{formatNumber(day.executions)}</td>
                        <td>{formatNumber(day.searches)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          )}

          <h3 className={styles.subheading}>Top 会话</h3>
          {data.topSessions.length === 0 ? (
            <p className={styles.hint}>这个时间窗口还没有会话用量。</p>
          ) : (
            <ol className={styles.sessionList}>
              {data.topSessions.map((session) => (
                <li key={session.sessionId} className={styles.sessionItem}>
                  <span className={styles.sessionTitle}>{session.title}</span>
                  <span className={styles.sessionMeta}>
                    {formatNumber(
                      session.tokensIn +
                        session.tokensOut +
                        session.cacheReadTokens +
                        session.cacheWriteTokens,
                    )}{" "}
                    token · {formatCost(session, data.currency)} · {session.executions} 次执行
                  </span>
                </li>
              ))}
            </ol>
          )}
        </>
      ) : null}
    </section>
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

function formatCost(
  totals: { cost: number; unpricedRuns?: number },
  currency: string,
): string {
  if ((totals.unpricedRuns ?? 0) > 0 && totals.cost === 0) return "—";
  const symbol = currency === "CNY" ? "¥" : currency === "USD" ? "$" : `${currency} `;
  return `${symbol}${totals.cost.toFixed(4)}`;
}
