"use client";

import Link from "next/link";
import { useState } from "react";
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

type SettingsTab = "model" | "memory" | "account" | "appearance";

const TABS: Array<{ key: SettingsTab; label: string }> = [
  { key: "model", label: "模型与接入" },
  { key: "memory", label: "记忆与用量" },
  { key: "account", label: "账户与安全" },
  { key: "appearance", label: "外观" },
];

export function SettingsPage({ initialTab = "model" }: { initialTab?: SettingsTab }) {
  const { user } = useAuth();
  const [tab, setTab] = useState<SettingsTab>(initialTab);

  return (
    <PageShell title="设置" subtitle="模型、长期记忆、账户与外观偏好">
      <div className={styles.tabs} role="tablist" aria-label="设置页签">
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

      <div className={styles.sections}>
        {tab === "model" ? <ModelSettings /> : null}

        {tab === "memory" ? (
          <>
            <MemorySettings />
            <UsageSettings />
          </>
        ) : null}

        {tab === "account" ? (
          <>
            <AccountSettings />
            <IdentitySettings />
            <SecurityEvents />
            <DataExport />
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
          </>
        ) : null}

        {tab === "appearance" ? <AppearanceSettings /> : null}
      </div>
    </PageShell>
  );
}
