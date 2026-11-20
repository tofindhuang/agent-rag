"use client";

import { useEffect, useState } from "react";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { TextField } from "@/components/ui/TextField";
import { fetchAdminUsers } from "@/lib/api/admin";
import { userMessage } from "@/lib/api/errors";
import type { AdminUserList, AdminUserSort } from "@/types/admin";
import { AdminUserDetail } from "./AdminUserDetail";
import { formatCost, formatNumber, formatTime } from "./format";
import styles from "./admin.module.css";

const PAGE_SIZE = 20;

/** User tab: search, sort and paginate accounts; drill into one user. */
export function AdminUsers() {
  const [query, setQuery] = useState("");
  const [applied, setApplied] = useState("");
  const [sort, setSort] = useState<AdminUserSort>("lastActive");
  const [page, setPage] = useState(1);
  const [data, setData] = useState<AdminUserList | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<number | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    fetchAdminUsers({ query: applied, page, size: PAGE_SIZE, window: "7d", sort })
      .then((list) => {
        if (!cancelled) setData(list);
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
  }, [applied, page, sort]);

  if (selected !== null) {
    return <AdminUserDetail userId={selected} onBack={() => setSelected(null)} />;
  }

  const totalPages = data == null ? 1 : Math.max(1, Math.ceil(data.total / data.size));

  return (
    <div className={styles.panel}>
      <form
        className={styles.filters}
        onSubmit={(event) => {
          event.preventDefault();
          setPage(1);
          setApplied(query.trim());
        }}
      >
        <TextField
          label="搜索用户"
          value={query}
          placeholder="email 或 userId"
          onChange={(event) => setQuery(event.target.value)}
        />
        <label className={styles.field}>
          <span>排序</span>
          <select
            className={styles.select}
            value={sort}
            onChange={(event) => {
              setSort(event.target.value as AdminUserSort);
              setPage(1);
            }}
          >
            <option value="lastActive">最后活跃</option>
            <option value="cost">成本</option>
            <option value="tokens">token</option>
          </select>
        </label>
        <Button type="submit" size="sm">
          搜索
        </Button>
      </form>

      {error ? <Banner tone="error">{error}</Banner> : null}
      {loading && !data ? (
        <p className={styles.hint} role="status">
          用户加载中…
        </p>
      ) : null}

      {data ? (
        <>
          <div className={styles.tableWrap}>
            <table className={styles.table}>
              <thead>
                <tr>
                  <th>用户</th>
                  <th>最后活跃</th>
                  <th>会话</th>
                  <th>输入</th>
                  <th>输出</th>
                  <th>缓存读/写</th>
                  <th>成本</th>
                  <th>执行</th>
                  <th>检索</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {data.items.map((user) => (
                  <tr key={user.userId}>
                    <td>
                      <div className={styles.userEmail}>{user.email}</div>
                      <div className={styles.userId}>#{user.userId}</div>
                    </td>
                    <td>{formatTime(user.lastActiveAt)}</td>
                    <td>{formatNumber(user.sessions)}</td>
                    <td>{formatNumber(user.tokensIn)}</td>
                    <td>{formatNumber(user.tokensOut)}</td>
                    <td>
                      {formatNumber(user.cacheReadTokens)} /{" "}
                      {formatNumber(user.cacheWriteTokens)}
                    </td>
                    <td>
                      {formatCost(user.cost, data.currency)}
                      {user.unpricedRuns > 0 ? " *" : ""}
                    </td>
                    <td>{formatNumber(user.executions)}</td>
                    <td>{formatNumber(user.searches)}</td>
                    <td>
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => setSelected(user.userId)}
                      >
                        详情
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {data.items.length === 0 ? (
            <p className={styles.hint}>没有匹配的用户。</p>
          ) : null}
          {data.items.some((user) => user.unpricedRuns > 0) ? (
            <p className={styles.hint}>带 * 的用户含未配置价格的运行，成本未计入。</p>
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
              第 {page} / {totalPages} 页 · 共 {data.total} 人
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
