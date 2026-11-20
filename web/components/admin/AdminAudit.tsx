"use client";

import { useEffect, useState } from "react";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { fetchAdminAudit } from "@/lib/api/audit";
import { userMessage } from "@/lib/api/errors";
import type { AuditEvent } from "@/types/audit";
import styles from "./admin.module.css";

const ACTION_FILTERS = [
  { value: "", label: "全部" },
  { value: "auth.login.failed", label: "登录失败" },
  { value: "auth.refresh.reuse", label: "令牌重用" },
  { value: "model.provider.update", label: "供应商变更" },
  { value: "session.purge", label: "会话彻底删除" },
  { value: "kb.document.delete", label: "文档删除" },
];

function formatTime(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? "—" : date.toLocaleString("zh-CN");
}

/** Read-only audit table (ADR-0033); metadata only, never content. */
export function AdminAudit() {
  const [action, setAction] = useState("");
  const [items, setItems] = useState<AuditEvent[]>([]);
  const [nextBeforeId, setNextBeforeId] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = async (filter: string, beforeId?: number | null) => {
    setLoading(true);
    setError(null);
    try {
      const page = await fetchAdminAudit(50, beforeId, filter || null);
      setItems((previous) => (beforeId == null ? page.items : [...previous, ...page.items]));
      setNextBeforeId(page.nextBeforeId);
    } catch (cause: unknown) {
      console.error(cause);
      setError(userMessage(cause));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load("", null);
  }, []);

  return (
    <div className={styles.section}>
      {error ? <Banner tone="error">{error}</Banner> : null}
      <div className={styles.actions}>
        {ACTION_FILTERS.map((filter) => (
          <Button
            key={filter.value || "all"}
            size="sm"
            variant={action === filter.value ? "primary" : "secondary"}
            onClick={() => {
              setAction(filter.value);
              void load(filter.value, null);
            }}
          >
            {filter.label}
          </Button>
        ))}
      </div>
      <table className={styles.table}>
        <thead>
          <tr>
            <th>时间</th>
            <th>操作者</th>
            <th>动作</th>
            <th>目标</th>
            <th>结果</th>
            <th>IP</th>
          </tr>
        </thead>
        <tbody>
          {items.map((event) => (
            <tr key={event.id}>
              <td>{formatTime(event.createdAt)}</td>
              <td>{event.actorUserId ?? "—"}</td>
              <td>{event.action}</td>
              <td>{event.targetType ? `${event.targetType}/${event.targetId ?? ""}` : "—"}</td>
              <td>{event.outcome}</td>
              <td>{event.ip ?? "—"}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {items.length === 0 && !loading ? <p className={styles.hint}>没有审计记录。</p> : null}
      {nextBeforeId != null ? (
        <Button
          variant="secondary"
          size="sm"
          onClick={() => void load(action, nextBeforeId)}
        >
          {loading ? "加载中…" : "加载更早"}
        </Button>
      ) : null}
    </div>
  );
}
