"use client";

import Link from "next/link";
import { useState } from "react";
import { useModelCatalog } from "@/hooks/useModelCatalog";
import { ModelSelect, SYSTEM_MODEL_VALUE, defaultModelValue } from "./ModelSelect";
import styles from "./chat.module.css";

interface SwitchModelDialogProps {
  sessionId: string;
  /** Only switch failures; other chat errors must not leak into the dialog. */
  error: string | null;
  onSwitch: (sessionId: string, modelId: number | null) => Promise<boolean>;
  onClose: () => void;
}

/** Fault-recovery picker shown after MODEL_UNAVAILABLE (ADR-0010). */
export function SwitchModelDialog({
  sessionId,
  error,
  onSwitch,
  onClose,
}: SwitchModelDialogProps) {
  const { catalog, loading, error: catalogError } = useModelCatalog();
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
      const ok = await onSwitch(sessionId, value === SYSTEM_MODEL_VALUE ? null : Number(value));
      if (ok) onClose();
    } finally {
      setPending(false);
    }
  };

  return (
    <div className={styles.dialogOverlay} role="dialog" aria-modal="true" aria-label="切换模型">
      <form className={styles.dialog} onSubmit={(event) => void submit(event)}>
        <h2 className={styles.dialogTitle}>当前模型不可用</h2>
        <p className={styles.dialogHint}>选择一个可用模型，切换后从下一轮回复生效。</p>

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
          <p className={styles.dialogHint} role="status">
            没有可切换的模型，请先在
            <Link className={styles.dialogLink} href="/settings">
              设置
            </Link>
            中添加供应商。
          </p>
        ) : null}

        {error ? (
          <p className={styles.dialogError} role="alert">
            {error}
          </p>
        ) : null}

        <div className={styles.dialogActions}>
          <button
            className={styles.dialogPrimary}
            type="submit"
            disabled={pending || loading || catalog == null || !hasModels}
          >
            {pending ? "切换中…" : "切换模型"}
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
