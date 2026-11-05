"use client";

import { useState } from "react";
import { useAuth } from "@/components/auth/AuthProvider";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { Dialog } from "@/components/ui/Dialog";
import { userMessage } from "@/lib/api/errors";
import { downloadPersonalExport } from "@/lib/api/export";
import styles from "./usage.module.css";

/**
 * Personal data package (ADR-0034): everything textual, no credentials and no
 * binary originals. Accounts with a local password must re-enter it.
 */
export function DataExport() {
  const { user } = useAuth();
  const [open, setOpen] = useState(false);
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  if (!user) return null;
  const needsPassword = user.hasPassword;

  const submit = async () => {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await downloadPersonalExport(needsPassword ? password : undefined);
      setOpen(false);
      setPassword("");
      setNotice("导出已开始下载；文件只包含你的数据。");
    } catch (cause: unknown) {
      console.error(cause);
      setError(userMessage(cause));
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className={styles.section} aria-labelledby="data-export-title">
      <div className={styles.sectionHeader}>
        <h2 id="data-export-title">导出我的数据</h2>
      </div>
      <p className={styles.hint}>
        打包你的账号资料、会话与消息、压缩摘要、记忆、知识库与文档元数据、评测标注、用量与安全事件。
        <br />
        不包含：密码哈希、模型 API Key、refresh 令牌、他人数据、向量，以及知识库原文（原文可在文档列表单独下载）。
      </p>
      {notice ? <Banner tone="status">{notice}</Banner> : null}
      <Button variant="secondary" size="sm" onClick={() => setOpen(true)}>
        导出为 ZIP
      </Button>

      {open ? (
        <Dialog
          title="确认导出"
          description={
            needsPassword
              ? "导出包含你的全部对话内容，请输入当前密码确认。"
              : "导出包含你的全部对话内容，确认后开始下载。"
          }
          onClose={() => setOpen(false)}
        >
          <div className={styles.exportDialog}>
            {error ? <Banner tone="error">{error}</Banner> : null}
            {needsPassword ? (
              <label className={styles.exportField}>
                <span>当前密码</span>
                <input
                  type="password"
                  value={password}
                  autoComplete="current-password"
                  onChange={(event) => setPassword(event.target.value)}
                />
              </label>
            ) : null}
            <div className={styles.exportActions}>
              <Button variant="ghost" size="sm" onClick={() => setOpen(false)}>
                取消
              </Button>
              <Button
                size="sm"
                disabled={busy || (needsPassword && password.length === 0)}
                onClick={() => void submit()}
              >
                {busy ? "准备中…" : "开始导出"}
              </Button>
            </div>
          </div>
        </Dialog>
      ) : null}
    </section>
  );
}
