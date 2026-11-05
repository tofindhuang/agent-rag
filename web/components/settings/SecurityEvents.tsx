"use client";

import { useEffect, useState } from "react";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { fetchMyAudit } from "@/lib/api/audit";
import { userMessage } from "@/lib/api/errors";
import type { AuditEvent } from "@/types/audit";
import styles from "./usage.module.css";

const ACTION_LABELS: Record<string, string> = {
  "auth.register": "注册账号",
  "auth.login": "登录",
  "auth.login.failed": "登录失败",
  "auth.refresh.reuse": "刷新令牌重用被拒绝",
  "auth.logout.all": "登出所有设备",
  "auth.password.set": "设置密码",
  "auth.password.change": "修改密码",
  "auth.identity.link": "绑定外部登录",
  "auth.identity.unlink": "解绑外部登录",
  "model.provider.create": "新增模型供应商",
  "model.provider.update": "修改模型供应商",
  "model.provider.delete": "删除模型供应商",
  "model.provider.default": "切换默认模型",
  "kb.settings.update": "修改知识库设置",
  "kb.settings.reset": "恢复知识库部署默认",
  "kb.threshold.update": "修改拒答阈值",
  "session.purge": "彻底删除会话",
  "session.archive": "归档会话",
  "session.restore": "恢复会话",
  "kb.document.delete": "删除文档",
  "kb.delete": "删除知识库",
  "memory.fact.delete": "删除记忆条目",
};

function formatTime(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? "—" : date.toLocaleString("zh-CN");
}

/** The caller's own audit trail: sign-ins, credential and data changes. */
export function SecurityEvents() {
  const [items, setItems] = useState<AuditEvent[]>([]);
  const [nextBeforeId, setNextBeforeId] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = async (beforeId?: number | null) => {
    setLoading(true);
    setError(null);
    try {
      const page = await fetchMyAudit(20, beforeId);
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
    void load(null);
  }, []);

  return (
    <section className={styles.section} aria-labelledby="security-events-title">
      <div className={styles.sectionHeader}>
        <h2 id="security-events-title">安全事件</h2>
        <span className={styles.hint}>登录、凭证与数据变更记录（不含对话内容）</span>
      </div>
      {error ? <Banner tone="error">{error}</Banner> : null}
      {items.length === 0 && !loading ? (
        <p className={styles.hint}>还没有安全事件。</p>
      ) : (
        <ul className={styles.auditList}>
          {items.map((event) => (
            <li key={event.id} className={styles.auditItem}>
              <span className={styles.auditTime}>{formatTime(event.createdAt)}</span>
              <span className={styles.auditAction}>
                {ACTION_LABELS[event.action] ?? event.action}
              </span>
              <span
                className={[
                  styles.auditOutcome,
                  event.outcome === "success" ? styles.auditOk : styles.auditBad,
                ].join(" ")}
              >
                {event.outcome === "success" ? "成功" : event.outcome === "denied" ? "拒绝" : "失败"}
              </span>
              <span className={styles.auditTarget}>
                {event.targetType ? `${event.targetType}/${event.targetId ?? ""}` : ""}
              </span>
            </li>
          ))}
        </ul>
      )}
      {nextBeforeId != null ? (
        <Button variant="secondary" size="sm" onClick={() => void load(nextBeforeId)}>
          {loading ? "加载中…" : "加载更早"}
        </Button>
      ) : null}
    </section>
  );
}
