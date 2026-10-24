"use client";

import { useCallback, useEffect, useState } from "react";
import { Banner } from "@/components/ui/Banner";
import { PageShell } from "@/components/ui/PageShell";
import { Button } from "@/components/ui/Button";
import { Dialog } from "@/components/ui/Dialog";
import { TextField } from "@/components/ui/TextField";
import {
  createKnowledgeBase,
  deleteKnowledgeBase,
  fetchKnowledgeBases,
  knowledgeError,
  renameKnowledgeBase,
} from "@/lib/api/knowledge";
import { useKnowledge } from "@/hooks/useKnowledge";
import type { KbKnowledgeBase } from "@/types/knowledge";
import { DocumentList } from "./DocumentList";
import { SettingsPanel } from "./SettingsPanel";
import { UploadPanel } from "./UploadPanel";
import styles from "./knowledge.module.css";

/** Knowledge base selector/management plus the scoped document workspace. */
export function KnowledgePage() {
  const [knowledgeBases, setKnowledgeBases] = useState<KbKnowledgeBase[]>([]);
  const [kbId, setKbId] = useState<string | null>(null);
  const [kbLoading, setKbLoading] = useState(true);
  const [kbBusy, setKbBusy] = useState(false);
  const [kbError, setKbError] = useState<string | null>(null);
  const [editMode, setEditMode] = useState<"create" | "rename" | null>(null);
  const [editName, setEditName] = useState("");
  const [deleteTarget, setDeleteTarget] = useState<KbKnowledgeBase | null>(null);

  const loadKnowledgeBases = useCallback(async (preferredId?: string | null) => {
    const items = await fetchKnowledgeBases();
    setKnowledgeBases(items);
    setKbId((current) => {
      if (preferredId && items.some((item) => item.id === preferredId)) return preferredId;
      if (current && items.some((item) => item.id === current)) return current;
      return items.find((item) => item.isDefault)?.id ?? items[0]?.id ?? null;
    });
  }, []);

  useEffect(() => {
    let active = true;
    loadKnowledgeBases()
      .catch((cause: unknown) => {
        if (active) setKbError(knowledgeError(cause));
      })
      .finally(() => {
        if (active) setKbLoading(false);
      });
    return () => {
      active = false;
    };
  }, [loadKnowledgeBases]);

  const knowledge = useKnowledge(kbId);
  const currentKb = knowledgeBases.find((item) => item.id === kbId) ?? null;

  const runKb = async (action: () => Promise<void>, success: string) => {
    setKbBusy(true);
    setKbError(null);
    try {
      await action();
      await loadKnowledgeBases(kbId);
      knowledge.clearError();
      knowledge.refresh().catch(() => undefined);
      setKbError(null);
      setEditMode(null);
      setDeleteTarget(null);
      void success;
    } catch (cause: unknown) {
      console.error(cause);
      setKbError(knowledgeError(cause));
    } finally {
      setKbBusy(false);
    }
  };

  const openCreate = () => {
    setEditName("");
    setEditMode("create");
  };

  const openRename = () => {
    if (!currentKb) return;
    setEditName(currentKb.name);
    setEditMode("rename");
  };

  const submitEdit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (editMode === "create") {
      await runKb(async () => {
        const created = await createKnowledgeBase(editName);
        await loadKnowledgeBases(created.id);
      }, "已创建知识库");
    } else if (editMode === "rename" && currentKb) {
      await runKb(async () => {
        await renameKnowledgeBase(currentKb.id, editName);
        await loadKnowledgeBases(currentKb.id);
      }, "已重命名");
    }
  };

  return (
    <PageShell
      title="知识库"
      subtitle="上传后自动解析、切片并建立索引；对话中的 kb_search 只检索当前对话绑定的知识库。"
    >
      {kbError || knowledge.error || knowledge.notice ? (
        <div className={styles.notices}>
          {kbError ? <Banner tone="error">{kbError}</Banner> : null}
          {knowledge.error ? <Banner tone="error">{knowledge.error}</Banner> : null}
          {knowledge.notice ? <Banner tone="notice">{knowledge.notice}</Banner> : null}
        </div>
      ) : null}

      <section className={styles.kbBar} aria-label="知识库选择">
        <label className={styles.label} htmlFor="kb-select">
          <span>当前知识库</span>
          <select
            id="kb-select"
            className={styles.select}
            value={kbId ?? ""}
            disabled={kbLoading || kbBusy || knowledgeBases.length === 0}
            onChange={(event) => setKbId(event.target.value || null)}
          >
            {knowledgeBases.map((kb) => (
              <option key={kb.id} value={kb.id}>
                {kb.name}
                {kb.isDefault ? "（默认）" : ""} · {kb.documentCount} 篇
              </option>
            ))}
          </select>
        </label>
        <div className={styles.serviceActions}>
          <Button size="sm" type="button" disabled={kbBusy} onClick={openCreate}>
            新建知识库
          </Button>
          <Button
            variant="secondary"
            size="sm"
            type="button"
            disabled={kbBusy || !currentKb}
            onClick={openRename}
          >
            重命名
          </Button>
          <Button
            variant="danger"
            size="sm"
            type="button"
            disabled={kbBusy || !currentKb || currentKb.isDefault}
            onClick={() => currentKb && setDeleteTarget(currentKb)}
          >
            删除
          </Button>
        </div>
      </section>

      {kbLoading ? (
        <p className={styles.hint} role="status">
          知识库加载中…
        </p>
      ) : null}
      {!kbLoading && knowledgeBases.length === 0 ? (
        <p className={styles.hint}>还没有知识库，先新建一个。</p>
      ) : null}

      <div className={styles.sections}>
        <UploadPanel
          busy={knowledge.busy}
          onUploadFile={knowledge.uploadFile}
          onUploadUrl={knowledge.uploadUrl}
        />
        <DocumentList
          documents={knowledge.documents}
          usage={knowledge.usage}
          busy={knowledge.busy}
          onReingest={knowledge.reingest}
          onReingestAll={knowledge.reingestAll}
          onDelete={knowledge.remove}
        />
        <SettingsPanel />
      </div>

      {editMode ? (
        <Dialog
          title={editMode === "create" ? "新建知识库" : "重命名知识库"}
          description={
            editMode === "create"
              ? "名称在用户内唯一；默认知识库会自动保留。"
              : "重命名不影响已有文档、会话绑定和引用快照。"
          }
          onClose={() => setEditMode(null)}
          closeOnOverlay={!kbBusy}
        >
          <form className={styles.form} onSubmit={(event) => void submitEdit(event)}>
            <TextField
              label="名称"
              value={editName}
              onChange={(event) => setEditName(event.target.value)}
              maxLength={100}
              required
              autoFocus
            />
            <div className={styles.formActions}>
              <Button
                variant="secondary"
                type="button"
                disabled={kbBusy}
                onClick={() => setEditMode(null)}
              >
                取消
              </Button>
              <Button type="submit" disabled={kbBusy || editName.trim().length === 0}>
                {kbBusy ? "保存中…" : "保存"}
              </Button>
            </div>
          </form>
        </Dialog>
      ) : null}

      {deleteTarget ? (
        <Dialog
          title="删除知识库"
          description={`确认删除「${deleteTarget.name}」？只有空库（无文档、无会话绑定）才能删除，且不可恢复。`}
          onClose={() => setDeleteTarget(null)}
          closeOnOverlay={!kbBusy}
          actions={
            <>
              <Button
                variant="secondary"
                type="button"
                disabled={kbBusy}
                onClick={() => setDeleteTarget(null)}
              >
                取消
              </Button>
              <Button
                variant="danger"
                type="button"
                disabled={kbBusy}
                onClick={() =>
                  void runKb(async () => {
                    await deleteKnowledgeBase(deleteTarget.id);
                    await loadKnowledgeBases(null);
                  }, "已删除知识库")
                }
              >
                {kbBusy ? "删除中…" : "确认删除"}
              </Button>
            </>
          }
        />
      ) : null}
    </PageShell>
  );
}
