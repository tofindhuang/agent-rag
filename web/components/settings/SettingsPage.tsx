"use client";

import Link from "next/link";
import { useAuth } from "@/components/auth/AuthProvider";
import { PageShell } from "@/components/ui/PageShell";
import { AccountSettings } from "./AccountSettings";
import { IdentitySettings } from "./IdentitySettings";
import { AppearanceSettings } from "./AppearanceSettings";
import { MemorySettings } from "./MemorySettings";
import { ModelSettings } from "./ModelSettings";
import { DataExport } from "./DataExport";
import { SecurityEvents } from "./SecurityEvents";
import { UsageSettings } from "./UsageSettings";
import styles from "./settings.module.css";

export function SettingsPage() {
  const { user } = useAuth();
  return (
    <PageShell title="设置" subtitle="模型、长期记忆、账户与外观偏好">
      <div className={styles.sections}>
        <ModelSettings />
        <MemorySettings />
        <UsageSettings />
        <SecurityEvents />
        <DataExport />
        <AccountSettings />
        <IdentitySettings />
        <AppearanceSettings />
        {user?.role === "admin" ? (
          <section className={styles.section}>
            <div className={styles.sectionHeader}>
              <h2>运营</h2>
            </div>
            <p className={styles.hint}>
              只读的全局用量、运营指标与对话轮次 trace；仅管理员可见。
            </p>
            <Link className={styles.adminLink} href="/admin">
              打开运营看板
            </Link>
          </section>
        ) : null}
      </div>
    </PageShell>
  );
}
