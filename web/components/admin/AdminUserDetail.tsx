"use client";

import { useEffect, useState } from "react";
import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { fetchAdminUserDetail } from "@/lib/api/admin";
import { userMessage } from "@/lib/api/errors";
import type { AdminUserDetail as AdminUserDetailData, AdminWindow } from "@/types/admin";
import { formatCost, formatNumber, formatTime } from "./format";
import styles from "./admin.module.css";

const SESSION_PAGE_SIZE = 20;

/** One user's usage, model split and session metadata; no titles or content. */
export function AdminUserDetail({
  userId,
  onBack,
}: {
  userId: number;
  onBack: () => void;
}) {
  const [selected, setSelected] = useState<AdminWindow>("7d");
  const [page, setPage] = useState(1);
  const [data, setData] = useState<AdminUserDetailData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    fetchAdminUserDetail(userId, selected, page, SESSION_PAGE_SIZE)
      .then((detail) => {
        if (!cancelled) setData(detail);
      })
      .catch((cause: unknown) => {
        if (!cancelled) setError(userMessage(cause));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [userId, selected, page]);

  const daily = (data?.daily ?? []).map((day) => ({
    ...day,
    totalTokens:
      day.tokensIn + day.tokensOut + day.cacheReadTokens + day.cacheWriteTokens,
  }));
  const totalPages =
    data == null ? 1 : Math.max(1, Math.ceil(data.sessions.total / data.sessions.size));

  return (
    <div className={styles.panel}>
      <div className={styles.toolbar}>
        <Button variant="secondary" size="sm" onClick={onBack}>
          ← 返回用户列表
        </Button>
        <div className={styles.actions}>
          <Button
            size="sm"
            variant={selected === "7d" ? "primary" : "secondary"}
            onClick={() => {
              setSelected("7d");
              setPage(1);
            }}
          >
            7 天
          </Button>
          <Button
            size="sm"
            variant={selected === "30d" ? "primary" : "secondary"}
            onClick={() => {
              setSelected("30d");
              setPage(1);
            }}
          >
            30 天
          </Button>
        </div>
      </div>

      {error ? <Banner tone="error">{error}</Banner> : null}
      {loading && !data ? (
        <p className={styles.hint} role="status">
          用户详情加载中…
        </p>
      ) : null}

      {data ? (
        <>
          <div className={styles.detailHeader}>
            <h2>{data.user.email}</h2>
            <span className={styles.hint}>
              #{data.user.userId} · 注册 {formatTime(data.user.createdAt)} · 最后活跃{" "}
              {formatTime(data.user.lastActiveAt)}
            </span>
          </div>

          <div className={styles.cards}>
            <Stat label="会话" value={formatNumber(data.user.sessions)} />
            <Stat label="输入 token" value={formatNumber(data.window.tokensIn)} />
            <Stat label="输出 token" value={formatNumber(data.window.tokensOut)} />
            <Stat label="缓存读" value={formatNumber(data.window.cacheReadTokens)} />
            <Stat label="缓存写" value={formatNumber(data.window.cacheWriteTokens)} />
            <Stat
              label="成本"
              value={formatCost(data.window.cost, data.currency)}
              hint={data.window.unpricedRuns > 0 ? "含未定价运行" : undefined}
            />
            <Stat label="执行" value={formatNumber(data.window.executions)} />
            <Stat label="检索" value={formatNumber(data.window.searches)} />
          </div>

          {daily.length > 0 ? (
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
          ) : (
            <p className={styles.hint}>这个时间窗口还没有用量数据。</p>
          )}

          <h3 className={styles.subheading}>模型分布</h3>
          {data.models.length === 0 ? (
            <p className={styles.hint}>没有模型用量。</p>
          ) : (
            <div className={styles.tableWrap}>
              <table className={styles.table}>
                <thead>
                  <tr>
                    <th>模型</th>
                    <th>输入</th>
                    <th>输出</th>
                    <th>缓存读</th>
                    <th>缓存写</th>
                    <th>成本</th>
                  </tr>
                </thead>
                <tbody>
                  {data.models.map((model) => (
                    <tr key={model.modelId ?? "unknown"}>
                      <td>{model.modelId ?? "—"}</td>
                      <td>{formatNumber(model.tokensIn)}</td>
                      <td>{formatNumber(model.tokensOut)}</td>
                      <td>{formatNumber(model.cacheReadTokens)}</td>
                      <td>{formatNumber(model.cacheWriteTokens)}</td>
                      <td>{formatCost(model.cost, data.currency)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          <h3 className={styles.subheading}>会话</h3>
          <div className={styles.tableWrap}>
            <table className={styles.table}>
              <thead>
                <tr>
                  <th>sessionId</th>
                  <th>创建</th>
                  <th>最后活跃</th>
                  <th>模型</th>
                  <th>输入</th>
                  <th>输出</th>
                  <th>成本</th>
                  <th>执行</th>
                </tr>
              </thead>
              <tbody>
                {data.sessions.items.map((session) => (
                  <tr key={session.sessionId}>
                    <td className={styles.mono} title={session.sessionId}>
                      {session.sessionId}
                    </td>
                    <td>{formatTime(session.createdAt)}</td>
                    <td>{formatTime(session.lastActiveAt)}</td>
                    <td>{session.modelId ?? "—"}</td>
                    <td>{formatNumber(session.tokensIn)}</td>
                    <td>{formatNumber(session.tokensOut)}</td>
                    <td>{formatCost(session.cost, data.currency)}</td>
                    <td>{formatNumber(session.executions)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {data.sessions.items.length === 0 ? (
            <p className={styles.hint}>这个时间窗口还没有会话。</p>
          ) : null}
          <div className={styles.pagination}>
            <Button
              size="sm"
              variant="secondary"
              disabled={page <= 1 || loading}
              onClick={() => setPage((current) => Math.max(1, current - 1))}
            >
              上一页
            </Button>
            <span className={styles.pageInfo}>
              第 {page} / {totalPages} 页 · 共 {data.sessions.total} 个会话
            </span>
            <Button
              size="sm"
              variant="secondary"
              disabled={page >= totalPages || loading}
              onClick={() => setPage((current) => current + 1)}
            >
              下一页
            </Button>
          </div>
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
