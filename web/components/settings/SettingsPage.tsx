"use client";

import { PageShell } from "@/components/ui/PageShell";
import { AccountSettings } from "./AccountSettings";
import { AppearanceSettings } from "./AppearanceSettings";
import { MemorySettings } from "./MemorySettings";
import { ModelSettings } from "./ModelSettings";
import styles from "./settings.module.css";

export function SettingsPage() {
  return (
    <PageShell title="设置" subtitle="模型、长期记忆、账户与外观偏好">
      <div className={styles.sections}>
        <ModelSettings />
        <MemorySettings />
        <AccountSettings />
        <AppearanceSettings />
      </div>
    </PageShell>
  );
}
