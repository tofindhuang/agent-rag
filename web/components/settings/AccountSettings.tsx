"use client";

import { useState } from "react";
import { useAuth } from "@/components/auth/AuthProvider";
import { changePassword, logoutAll } from "@/lib/api/auth";
import { ApiError, userMessage } from "@/lib/api/errors";
import { setAuthNotice } from "@/lib/auth-notice";
import styles from "./settings.module.css";

function passwordBytes(value: string): number {
  return new TextEncoder().encode(value).length;
}

/**
 * Account section: display email, change the password and log out every
 * device. Both actions revoke all refresh tokens server-side, so success ends
 * the local session and hands a one-shot notice to the login page.
 */
export function AccountSettings() {
  const { user, endSession } = useAuth();
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [confirm, setConfirm] = useState("");
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirmLogoutAll, setConfirmLogoutAll] = useState(false);

  const changePasswordSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError(null);
    setNotice(null);

    const bytes = passwordBytes(next);
    if (next !== confirm) {
      setError("两次输入的新密码不一致");
      return;
    }
    if (bytes < 8 || bytes > 72) {
      setError("新密码需为 8–72 字节");
      return;
    }
    if (next === current) {
      setError("新密码不能与当前密码相同");
      return;
    }

    setBusy(true);
    try {
      await changePassword(current, next);
      setNotice("密码已修改，请重新登录");
      setAuthNotice("密码已修改，请重新登录");
      endSession();
    } catch (cause: unknown) {
      console.error(cause);
      // UNAUTHORIZED here means the current password, not the login credentials.
      setError(
        cause instanceof ApiError && cause.code === "UNAUTHORIZED"
          ? "当前密码不正确。"
          : userMessage(cause),
      );
    } finally {
      setBusy(false);
    }
  };

  const confirmLogoutAllAction = async () => {
    setError(null);
    setNotice(null);
    setBusy(true);
    try {
      await logoutAll();
      setAuthNotice("已登出所有设备，请重新登录");
      endSession();
    } catch (cause: unknown) {
      console.error(cause);
      setError(userMessage(cause));
      setConfirmLogoutAll(false);
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className={styles.section}>
      <div className={styles.sectionHeader}>
        <h2>账号</h2>
      </div>

      <p className={styles.hint}>
        当前邮箱：<strong>{user?.email ?? "—"}</strong>
      </p>

      <form className={styles.form} onSubmit={(event) => void changePasswordSubmit(event)}>
        <h3>修改密码</h3>
        <label className={styles.label}>
          当前密码
          <input
            className={styles.input}
            type="password"
            autoComplete="current-password"
            value={current}
            onChange={(event) => setCurrent(event.target.value)}
            maxLength={72}
            required
          />
        </label>
        <label className={styles.label}>
          新密码
          <input
            className={styles.input}
            type="password"
            autoComplete="new-password"
            value={next}
            onChange={(event) => setNext(event.target.value)}
            maxLength={72}
            required
          />
        </label>
        <label className={styles.label}>
          确认新密码
          <input
            className={styles.input}
            type="password"
            autoComplete="new-password"
            value={confirm}
            onChange={(event) => setConfirm(event.target.value)}
            maxLength={72}
            required
          />
        </label>

        {notice ? (
          <p className={styles.notice} role="status">
            {notice}
          </p>
        ) : null}
        {error ? (
          <p className={styles.error} role="alert">
            {error}
          </p>
        ) : null}

        <div className={styles.row}>
          <button className={styles.primary} type="submit" disabled={busy}>
            {busy ? "处理中…" : "修改密码"}
          </button>
          <span className={styles.hint}>修改后所有设备都需要重新登录</span>
        </div>
      </form>

      <form
        className={styles.form}
        onSubmit={(event) => {
          event.preventDefault();
          setConfirmLogoutAll(true);
        }}
      >
        <h3>登出所有设备</h3>
        <p className={styles.hint}>
          撤销全部刷新令牌；已登录设备的访问令牌会在 15 分钟内自然过期。
        </p>

        <div className={styles.row}>
          {confirmLogoutAll ? (
            <>
              <button
                className={styles.danger}
                type="button"
                onClick={() => void confirmLogoutAllAction()}
                disabled={busy}
              >
                {busy ? "处理中…" : "确认登出所有设备"}
              </button>
              <button
                className={styles.ghost}
                type="button"
                onClick={() => setConfirmLogoutAll(false)}
                disabled={busy}
              >
                取消
              </button>
            </>
          ) : (
            <button className={styles.dangerGhost} type="submit" disabled={busy}>
              登出所有设备
            </button>
          )}
        </div>
      </form>
    </section>
  );
}
