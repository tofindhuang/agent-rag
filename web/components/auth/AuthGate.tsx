"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "./AuthProvider";
import styles from "./auth.module.css";

/** Redirects anonymous visitors to /login before rendering the app. */
export function AuthGate({ children }: { children: React.ReactNode }) {
  const { status, error } = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (status === "anonymous") router.replace("/login");
  }, [status, router]);

  if (status === "loading") {
    return (
      <main className={styles.gate}>
        <p role="status">加载中…</p>
      </main>
    );
  }
  if (status === "error") {
    return (
      <main className={styles.gate}>
        <p className={styles.error} role="alert">
          {error ?? "无法连接业务服务，请确认后端已启动。"}
        </p>
      </main>
    );
  }
  if (status === "anonymous") return null;
  return <>{children}</>;
}
