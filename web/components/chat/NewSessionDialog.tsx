"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { Dialog } from "@/components/ui/Dialog";
import { Select } from "@/components/ui/Select";
import { TextField } from "@/components/ui/TextField";
import uiStyles from "@/components/ui/ui.module.css";
import { fetchKnowledgeBases } from "@/lib/api/knowledge";
import { fetchProfileCollections } from "@/lib/api/profile";
import type { KbKnowledgeBase } from "@/types/knowledge";
import type { ProfileCollection } from "@/types/profile";
import { useModelCatalog } from "@/hooks/useModelCatalog";
import type { CreateSessionOptions } from "@/lib/api/chat";
import { ModelOptions, SYSTEM_MODEL_VALUE, defaultModelValue } from "./ModelSelect";

interface NewSessionDialogProps {
  /** Only creation failures; other chat errors must not leak into the dialog. */
  error: string | null;
  errorCode: string | null;
  onSubmit: (options: CreateSessionOptions) => Promise<boolean>;
  onClose: () => void;
}

const FORM_ID = "new-session-form";

export function NewSessionDialog({ error, errorCode, onSubmit, onClose }: NewSessionDialogProps) {
  const { catalog, loading, error: catalogError } = useModelCatalog();
  const [title, setTitle] = useState("");
  const [model, setModel] = useState<string | null>(null);
  const [knowledgeBases, setKnowledgeBases] = useState<KbKnowledgeBase[]>([]);
  const [kbId, setKbId] = useState<string>("");
  const [collections, setCollections] = useState<ProfileCollection[]>([]);
  const [selectedCollections, setSelectedCollections] = useState<Set<string>>(new Set());
  const [pending, setPending] = useState(false);

  useEffect(() => {
    let active = true;
    fetchKnowledgeBases()
      .then((items) => {
        if (!active) return;
        setKnowledgeBases(items);
        setKbId((current) =>
          current || items.find((item) => item.isDefault)?.id || items[0]?.id || "none",
        );
      })
      .catch(() => undefined);
    fetchProfileCollections()
      .then((items) => {
        if (!active) return;
        setCollections(items);
        setSelectedCollections(
          new Set(
            items
              .filter((item) => item.systemKey === "general")
              .map((item) => item.id),
          ),
        );
      })
      .catch(() => undefined);
    return () => {
      active = false;
    };
  }, []);
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
        kbId: kbId === "none" ? undefined : kbId || undefined,
        noKnowledgeBase: kbId === "none",
        profileCollectionIds: [...selectedCollections],
      });
      if (ok) onClose();
    } finally {
      setPending(false);
    }
  };

  return (
    <Dialog
      title="新建会话"
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
            {pending ? "创建中…" : "创建"}
          </Button>
        </>
      }
    >
      <form id={FORM_ID} className={uiStyles.form} onSubmit={(event) => void submit(event)}>
        <TextField
          label="标题（可选）"
          value={title}
          maxLength={255}
          placeholder="新会话"
          onChange={(event) => setTitle(event.target.value)}
          autoFocus
        />
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

        <Select
          label="知识库"
          value={kbId}
          onChange={(event) => setKbId(event.target.value)}
          disabled={pending}
        >
          <option value="none">不使用知识库</option>
          {knowledgeBases.map((kb) => (
            <option key={kb.id} value={kb.id}>
              {kb.name}
              {kb.isDefault ? "（默认）" : ""}
            </option>
          ))}
        </Select>

        {collections.length > 0 ? (
          <div className={uiStyles.field}>
            <span>记忆集</span>
            <div className={uiStyles.checkboxGroup}>
              {collections.map((collection) => (
                <label key={collection.id} className={uiStyles.checkboxRow}>
                  <input
                    type="checkbox"
                    checked={selectedCollections.has(collection.id)}
                    onChange={(event) => {
                      setSelectedCollections((previous) => {
                        const next = new Set(previous);
                        if (event.target.checked) next.add(collection.id);
                        else next.delete(collection.id);
                        return next;
                      });
                    }}
                  />
                  {collection.name}
                  {collection.systemKey === "inbox" ? "（默认不注入）" : ""}
                </label>
              ))}
            </div>
            <span className={uiStyles.hint}>「通用」默认启用；场景集合按需勾选。</span>
          </div>
        ) : null}

        {catalogError && catalog == null ? <Banner tone="error">{catalogError}</Banner> : null}

        {catalog && !hasModels ? (
          <Banner tone="status">
            <span>
              还没有可用模型，请先在
              <Link className={uiStyles.link} href="/settings">
                设置
              </Link>
              中添加供应商。
            </span>
          </Banner>
        ) : null}

        {error ? (
          <Banner tone="error">
            <span>
              {error}
              {errorCode === "MODEL_NOT_CONFIGURED" ? (
                <>
                  {" "}
                  <Link className={uiStyles.link} href="/settings">
                    前往设置
                  </Link>
                </>
              ) : null}
            </span>
          </Banner>
        ) : null}
      </form>
    </Dialog>
  );
}
