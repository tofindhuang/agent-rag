"use client";

import styles from "./ui.module.css";

type Tone = "error" | "notice" | "status";

interface BannerProps {
  tone?: Tone;
  children: React.ReactNode;
}

const TONES: Record<Tone, string> = {
  error: styles.bannerError,
  notice: styles.bannerNotice,
  status: styles.bannerStatus,
};

/** Inline message; errors announce as alert, everything else as status. */
export function Banner({ tone = "status", children }: BannerProps) {
  return (
    <div
      className={[styles.banner, TONES[tone]].join(" ")}
      role={tone === "error" ? "alert" : "status"}
    >
      {children}
    </div>
  );
}
