"use client";

import { useState } from "react";
import { useAuth } from "@/components/auth/AuthProvider";
import { PageShell } from "@/components/ui/PageShell";
import type { AdminTab } from "@/types/admin";
import { AdminOverview } from "./AdminOverview";
import { AdminUsers } from "./AdminUsers";
import styles from "./admin.module.css";

const TABS: Array<{ key: AdminTab; label: string }> = [
  { key: "overview", label: "总览" },
  { key: "users", label: "用户" },
  { key: "trace", label: "trace" },
];

/** Read-only admin shell; users and trace tabs land with their own tickets. */
export function AdminPage() {
  const { user } = useAuth();
  const [tab, setTab] = useState<AdminTab>("overview");
  if (!user) return null;

  return (
    <PageShell
      title="运营看板"
      subtitle="管理员只读视图"
      backHref="/settings"
      backLabel="返回设置"
    >
      {user.role === "admin" ? (
        <>
          <div className={styles.tabs} role="tablist" aria-label="运营看板页签">
            {TABS.map((item) => (
              <button
                key={item.key}
                type="button"
                role="tab"
                aria-selected={tab === item.key}
                className={[styles.tab, tab === item.key ? styles.tabActive : ""]
                  .filter(Boolean)
                  .join(" ")}
                onClick={() => setTab(item.key)}
              >
                {item.label}
              </button>
            ))}
          </div>
          {tab === "overview" ? <AdminOverview /> : null}
          {tab === "users" ? <AdminUsers /> : null}
          {tab === "trace" ? <p className={styles.hint}>trace 页签将在后续票中实现。</p> : null}
        </>
      ) : (
        <p className={styles.denied} role="alert">
          没有权限访问运营看板。
        </p>
      )}
    </PageShell>
  );
}
