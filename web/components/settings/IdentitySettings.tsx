"use client";

import { useCallback, useEffect, useState } from "react";
import { useAuth } from "@/components/auth/AuthProvider";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import {
  listIdentities,
  oauthConfig,
  startOAuthLink,
  unlinkIdentity,
  type OAuthConfig,
  type OAuthIdentity,
} from "@/lib/api/auth";
import { userMessage } from "@/lib/api/errors";
import { oauthErrorMessage } from "@/lib/oauth-errors";
import styles from "./settings.module.css";

/** Login methods: bind/unlink an OIDC identity without exposing its subject. */
export function IdentitySettings() {
  const { user } = useAuth();
  const [identities, setIdentities] = useState<OAuthIdentity[] | null>(null);
  const [oauth, setOauth] = useState<OAuthConfig | null>(null);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirmId, setConfirmId] = useState<number | null>(null);

  const load = useCallback(async () => {
    if (!user) return;
    setError(null);
    try {
      const [nextIdentities, nextOauth] = await Promise.all([listIdentities(), oauthConfig()]);
      setIdentities(nextIdentities);
      setOauth(nextOauth);
    } catch (cause: unknown) {
      console.error(cause);
      setError(userMessage(cause));
    }
  }, [user]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    if (params.get("oauth") === "linked") {
      setNotice("已绑定 OAuth 登录方式。");
    }
    const code = params.get("oauth_error");
    if (code) {
      setError(oauthErrorMessage(code) ?? "OAuth 操作失败，请重试。");
    }
    if (params.has("oauth") || params.has("oauth_error")) {
      window.history.replaceState({}, "", window.location.pathname);
    }
  }, []);

  const bind = async () => {
    setError(null);
    setNotice(null);
    setBusy(true);
    try {
      const { authorizationUrl } = await startOAuthLink();
      window.location.assign(authorizationUrl);
    } catch (cause: unknown) {
      console.error(cause);
      setError(userMessage(cause));
      setBusy(false);
    }
  };

  const unlink = async (id: number) => {
    setError(null);
    setNotice(null);
    setBusy(true);
    try {
      await unlinkIdentity(id);
      setConfirmId(null);
      setNotice("已解绑该登录方式。");
      await load();
    } catch (cause: unknown) {
      console.error(cause);
      setError(userMessage(cause));
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className={styles.section}>
      <div className={styles.sectionHeader}>
        <h2>登录方式</h2>
      </div>
      <p className={styles.hint}>
        绑定 OAuth 后可以用它登录；解绑时必须保留至少一种登录方式。
      </p>

      {notice ? <Banner tone="notice">{notice}</Banner> : null}
      {error ? <Banner tone="error">{error}</Banner> : null}

      {identities === null ? (
        <p className={styles.hint}>加载中…</p>
      ) : identities.length === 0 ? (
        <p className={styles.hint}>尚未绑定外部身份。</p>
      ) : (
        <ul className={styles.identityList}>
          {identities.map((identity) => (
            <li className={styles.identityRow} key={identity.id}>
              <div>
                <strong>{identity.providerName}</strong>
                <span className={styles.hint}>
                  绑定于 {new Date(identity.linkedAt).toLocaleDateString()}
                </span>
              </div>
              {confirmId === identity.id ? (
                <div className={styles.row}>
                  <Button
                    variant="danger"
                    type="button"
                    disabled={busy}
                    onClick={() => void unlink(identity.id)}
                  >
                    {busy ? "处理中…" : "确认解绑"}
                  </Button>
                  <Button
                    variant="secondary"
                    type="button"
                    disabled={busy}
                    onClick={() => setConfirmId(null)}
                  >
                    取消
                  </Button>
                </div>
              ) : (
                <Button
                  variant="ghost"
                  type="button"
                  disabled={busy}
                  onClick={() => setConfirmId(identity.id)}
                >
                  解绑
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}

      {oauth?.enabled ? (
        <div className={styles.row}>
          <Button type="button" disabled={busy} onClick={() => void bind()}>
            {busy ? "处理中…" : `绑定 ${oauth.providerName}`}
          </Button>
        </div>
      ) : null}
    </section>
  );
}
