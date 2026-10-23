"use client";

import Link from "next/link";
import { useState } from "react";
import { useModelCatalog } from "@/hooks/useModelCatalog";
import type { CreateSessionOptions } from "@/lib/api/chat";
import { ModelSelect, SYSTEM_MODEL_VALUE, defaultModelValue } from "./ModelSelect";
import styles from "./chat.module.css";

interface NewSessionDialogProps {
  /** Only creation failures; other chat errors must not leak into the dialog. */
  error: string | null;
  errorCode: string | null;
  onSubmit: (options: CreateSessionOptions) => Promise<boolean>;
  onClose: () => void;
}

export function NewSessionDialog({ error, errorCode, onSubmit, onClose }: NewSessionDialogProps) {
  const { catalog, loading, error: catalogError } = useModelCatalog();
  const [title, setTitle] = useState("");
  const [model, setModel] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const hasModels =
    catalog != null &&
    (catalog.systemDefault.available ||
      catalog.providers.some((provider) => provider.models.length > 0));

  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (catalog == null || !hasModels || pending) return;
    const value = model ?? defaultModelValue(catalog);
    setPending(true);
    try {
      const ok = await onSubmit({
        title: title.trim().length > 0 ? title.trim() : undefined,
        modelId: value !== SYSTEM_MODEL_VALUE ? Number(value) : undefined,
        useSystemDefault: value === SYSTEM_MODEL_VALUE || undefined,
      });
      if (ok) onClose();
    } finally {
      setPending(false);
    }
  };

  return (
    <div className={styles.dialogOverlay} role="dialog" aria-modal="true" aria-label="新建会话">
      <form className={styles.dialog} onSubmit={(event) => void submit(event)}>
        <h2 className={styles.dialogTitle}>新建会话</h2>

        <label className={styles.dialogLabel}>
          标题（可选）
          <input
            className={styles.dialogInput}
            value={title}
            maxLength={255}
            placeholder="新会话"
            onChange={(event) => setTitle(event.target.value)}
            autoFocus
          />
        </label>

        <label className={styles.dialogLabel}>
          模型
          {catalog ? (
            <ModelSelect
              catalog={catalog}
              value={model ?? defaultModelValue(catalog)}
              onChange={setModel}
              disabled={!hasModels}
            />
          ) : (
            <input className={styles.dialogInput} disabled placeholder="加载中…" />
          )}
        </label>

        {catalogError && catalog == null ? (
          <p className={styles.dialogError} role="alert">
            {catalogError}
          </p>
        ) : null}

        {catalog && !hasModels ? (
          <p className={styles.dialogHint} role="alert">
            还没有可用模型，请先在
            <Link className={styles.dialogLink} href="/settings">
              设置
            </Link>
            中添加供应商。
          </p>
        ) : null}

        {error ? (
          <p className={styles.dialogError} role="alert">
            {error}
            {errorCode === "MODEL_NOT_CONFIGURED" ? (
              <>
                {" "}
                <Link className={styles.dialogLink} href="/settings">
                  前往设置
                </Link>
              </>
            ) : null}
          </p>
        ) : null}

        <div className={styles.dialogActions}>
          <button
            className={styles.dialogPrimary}
            type="submit"
            disabled={pending || loading || catalog == null || !hasModels}
          >
            {pending ? "创建中…" : "创建"}
          </button>
          <button
            className={styles.dialogGhost}
            type="button"
            onClick={onClose}
            disabled={pending}
          >
            取消
          </button>
        </div>
      </form>
    </div>
  );
}
