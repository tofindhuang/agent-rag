"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { Banner } from "@/components/ui/Banner";
import { useAuth } from "./AuthProvider";
import styles from "./auth.module.css";

/** Non-blocking reminder for OAuth-only accounts; dismissal is per user. */
export function PasswordPrompt() {
  const { user } = useAuth();
  const key = user ? `agentcore.passwordPromptDismissed.${user.userId}` : null;
  const [dismissed, setDismissed] = useState(false);

  useEffect(() => {
    if (!key) {
      setDismissed(false);
      return;
    }
    setDismissed(window.localStorage.getItem(key) === "1");
  }, [key]);

  if (!user || user.hasPassword !== false || dismissed || !key) {
    return null;
  }

  const dismiss = () => {
    window.localStorage.setItem(key, "1");
    setDismissed(true);
  };

  return (
    <Banner tone="notice">
      <span>你正在用 OAuth 登录。建议设置本地密码，避免 OAuth 不可用时无法登录。</span>
      <Link className={styles.promptAction} href="/settings">
        去设置
      </Link>
      <button className={styles.promptAction} type="button" onClick={dismiss}>
        稍后
      </button>
    </Banner>
  );
}
