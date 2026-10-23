"use client";

import Link from "next/link";
import { useState } from "react";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { Dialog } from "@/components/ui/Dialog";
import { Select } from "@/components/ui/Select";
import uiStyles from "@/components/ui/ui.module.css";
import { useModelCatalog } from "@/hooks/useModelCatalog";
import { ModelOptions, SYSTEM_MODEL_VALUE, defaultModelValue } from "./ModelSelect";

interface SwitchModelDialogProps {
  sessionId: string;
  /** Only switch failures; other chat errors must not leak into the dialog. */
  error: string | null;
  onSwitch: (sessionId: string, modelId: number | null) => Promise<boolean>;
  onClose: () => void;
}

const FORM_ID = "switch-model-form";

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
    <Dialog
      title="当前模型不可用"
      description="选择一个可用模型，切换后从下一轮回复生效。"
      onClose={onClose}
      actions={
        <>
          <Button variant="secondary" onClick={onClose} disabled={pending}>
            取消
          </Button>
          <Button
            type="submit"
            form={FORM_ID}
            disabled={pending || loading || catalog == null || !hasModels}
          >
            {pending ? "切换中…" : "切换模型"}
          </Button>
        </>
      }
    >
      <form id={FORM_ID} className={uiStyles.form} onSubmit={(event) => void submit(event)}>
        {catalog ? (
          <Select
            label="模型"
            value={model ?? defaultModelValue(catalog)}
            onChange={(event) => setModel(event.target.value)}
            disabled={!hasModels}
          >
            <ModelOptions catalog={catalog} />
          </Select>
        ) : (
          <Banner tone="status">{loading ? "加载模型…" : "模型列表不可用"}</Banner>
        )}

        {catalogError && catalog == null ? <Banner tone="error">{catalogError}</Banner> : null}

        {catalog && !hasModels ? (
          <Banner tone="status">
            <span>
              没有可切换的模型，请先在
              <Link className={uiStyles.link} href="/settings">
                设置
              </Link>
              中添加供应商。
            </span>
          </Banner>
        ) : null}

        {error ? <Banner tone="error">{error}</Banner> : null}
      </form>
    </Dialog>
  );
}
