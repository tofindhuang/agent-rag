"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { takeAuthNotice } from "@/lib/auth-notice";
import { userMessage } from "@/lib/api/errors";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { TextField } from "@/components/ui/TextField";
import { useAuth } from "./AuthProvider";
import styles from "./auth.module.css";

const EMAIL_PATTERN = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;

function passwordBytes(value: string): number {
  return new TextEncoder().encode(value).length;
}

export function AuthForm({ mode }: { mode: "login" | "register" }) {
  const { status, login, register } = useAuth();
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [flash, setFlash] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    // StrictMode may run this twice; only a real notice overwrites the state.
    const notice = takeAuthNotice();
    if (notice !== null) setFlash(notice);
  }, []);

  useEffect(() => {
    if (status === "authenticated") router.replace("/");
  }, [status, router]);

  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError(null);

    const normalized = email.trim().toLowerCase();
    if (!EMAIL_PATTERN.test(normalized)) {
      setError("请输入有效的邮箱地址");
      return;
    }
    const bytes = passwordBytes(password);
    if (bytes < 8 || bytes > 72) {
      setError("密码需为 8–72 字节");
      return;
    }

    setBusy(true);
    try {
      if (mode === "login") await login(normalized, password);
      else await register(normalized, password);
      router.replace("/");
    } catch (cause) {
      console.error(cause);
      setError(userMessage(cause));
    } finally {
      setBusy(false);
    }
  };

  const isLogin = mode === "login";

  return (
    <main className={styles.page}>
      <form className={styles.card} onSubmit={(event) => void submit(event)}>
        <h1 className={styles.title}>{isLogin ? "登录" : "注册"}</h1>
        <TextField
          label="邮箱"
          type="email"
          autoComplete="email"
          value={email}
          onChange={(event) => setEmail(event.target.value)}
          disabled={busy}
          required
        />
        <TextField
          label="密码"
          type="password"
          autoComplete={isLogin ? "current-password" : "new-password"}
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          disabled={busy}
          required
        />
        {flash ? <Banner tone="notice">{flash}</Banner> : null}
        {error ? <Banner tone="error">{error}</Banner> : null}
        <Button className={styles.submitButton} type="submit" disabled={busy}>
          {isLogin ? "登录" : "注册并登录"}
        </Button>
        {busy ? <Banner tone="status">处理中…</Banner> : null}
        <p className={styles.switch}>
          {isLogin ? "还没有账号？" : "已有账号？"}{" "}
          <Link href={isLogin ? "/register" : "/login"}>
            {isLogin ? "去注册" : "去登录"}
          </Link>
        </p>
      </form>
    </main>
  );
}
