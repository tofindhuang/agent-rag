"use client";

import Link from "next/link";
import { AccountSettings } from "./AccountSettings";
import { AppearanceSettings } from "./AppearanceSettings";
import { ModelSettings } from "./ModelSettings";
import styles from "./settings.module.css";

export function SettingsPage() {
  return (
    <main className={styles.page}>
      <header className={styles.pageHeader}>
        <Link className={styles.back} href="/">
          ← 返回聊天
        </Link>
        <h1 className={styles.title}>设置</h1>
      </header>
      <div className={styles.sections}>
        <ModelSettings />
        <AccountSettings />
        <AppearanceSettings />
      </div>
    </main>
  );
}
