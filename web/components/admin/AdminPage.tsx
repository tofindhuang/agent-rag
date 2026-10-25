"use client";

import { useAuth } from "@/components/auth/AuthProvider";
import { PageShell } from "@/components/ui/PageShell";
import styles from "./admin.module.css";

/** Read-only admin shell; the real tabs land with the overview ticket. */
export function AdminPage() {
  const { user } = useAuth();
  if (!user) return null;

  return (
    <PageShell
      title="运营看板"
      subtitle="管理员只读视图"
      backHref="/settings"
      backLabel="返回设置"
    >
      {user.role === "admin" ? (
        <p className={styles.hint}>总览、用户与 trace 即将上线。</p>
      ) : (
        <p className={styles.denied} role="alert">
          没有权限访问运营看板。
        </p>
      )}
    </PageShell>
  );
}
