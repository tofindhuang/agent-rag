"use client";

import { useEffect, useState } from "react";
import {
  applyThemePreference,
  readThemePreference,
  saveThemePreference,
  type ThemePreference,
} from "@/lib/theme";
import styles from "./settings.module.css";

const OPTIONS: Array<{ value: ThemePreference; label: string; hint: string }> = [
  { value: "system", label: "跟随系统", hint: "随操作系统的浅色/深色设置切换" },
  { value: "light", label: "浅色", hint: "白底，适合明亮环境" },
  { value: "dark", label: "深色", hint: "暗底，适合夜间与长时阅读" },
];

/** Appearance preference; the first frame is applied by the inline script. */
export function AppearanceSettings() {
  const [preference, setPreference] = useState<ThemePreference>("system");

  useEffect(() => {
    setPreference(readThemePreference());
  }, []);

  const choose = (next: ThemePreference) => {
    setPreference(next);
    applyThemePreference(next);
    saveThemePreference(next);
  };

  return (
    <section className={styles.section}>
      <div className={styles.sectionHeader}>
        <h2>外观</h2>
      </div>
      <div className={styles.themeOptions} role="radiogroup" aria-label="主题">
        {OPTIONS.map((option) => (
          <label key={option.value} className={styles.themeOption}>
            <input
              type="radio"
              name="theme"
              value={option.value}
              checked={preference === option.value}
              onChange={() => choose(option.value)}
            />
            <span>
              <strong>{option.label}</strong>
              <span className={styles.hint}>{option.hint}</span>
            </span>
          </label>
        ))}
      </div>
    </section>
  );
}
