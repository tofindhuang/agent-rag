"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Dialog } from "@/components/ui/Dialog";
import { formatBytes, formatDateTime } from "@/lib/format";
import type {
  KbDocument,
  KbDocumentStatus,
  KbSourceType,
  KbUsage,
} from "@/types/knowledge";
import styles from "./knowledge.module.css";

const STATUS_LABEL: Record<KbDocumentStatus, string> = {
  queued: "排队中",
  processing: "处理中",
  ready: "可检索",
  failed: "失败",
  deleting: "删除中",
  stale: "需重建",
};

const STATUS_CLASS: Record<KbDocumentStatus, string> = {
  queued: styles.statusQueued,
  processing: styles.statusProcessing,
  ready: styles.statusReady,
  failed: styles.statusFailed,
  deleting: styles.statusDeleting,
  stale: styles.statusStale,
};

const SOURCE_LABEL: Record<KbSourceType, string> = {
  upload: "文件",
  url: "网页",
};

interface DocumentListProps {
  documents: KbDocument[];
  usage: KbUsage | null;
  busy: boolean;
  onReingest: (id: string) => Promise<boolean>;
  onReingestAll: () => Promise<boolean>;
  onDelete: (id: string) => Promise<boolean>;
}

/** Document rows with status polling surfaced by useKnowledge. */
export function DocumentList({
  documents,
  usage,
  busy,
  onReingest,
  onReingestAll,
  onDelete,
}: DocumentListProps) {
  const [pendingDelete, setPendingDelete] = useState<KbDocument | null>(null);
  const hasStale = documents.some((document) => document.status === "stale");
  const quotaFull =
    usage !== null &&
    (usage.documents >= usage.maxDocuments || usage.bytes >= usage.maxBytes);

  return (
    <section className={styles.section} aria-labelledby="kb-docs-title">
      <div className={styles.sectionHeader}>
        <h2 id="kb-docs-title">文档</h2>
        <Button
          variant="secondary"
          size="sm"
          type="button"
          onClick={() => void onReingestAll()}
          disabled={busy || !hasStale}
        >
          全部重建
        </Button>
      </div>

      {usage ? (
        <div className={styles.quota}>
          <div className={styles.quotaRow}>
            <span>
              文档 {usage.documents}/{usage.maxDocuments} · 原文 {formatBytes(usage.bytes)}/
              {formatBytes(usage.maxBytes)}
            </span>
          </div>
          <div
            className={styles.quotaBar}
            role="progressbar"
            aria-label="原文存储用量"
            aria-valuemin={0}
            aria-valuemax={usage.maxBytes}
            aria-valuenow={Math.min(usage.bytes, usage.maxBytes)}
          >
            <div
              className={`${styles.quotaFill} ${quotaFull ? styles.quotaFillFull : ""}`}
              style={{ width: `${usage.maxBytes > 0 ? Math.min((usage.bytes / usage.maxBytes) * 100, 100) : 0}%` }}
            />
          </div>
          {quotaFull ? (
            <p className={styles.quotaWarning}>已达配额上限，请删除部分文档后再上传。</p>
          ) : null}
        </div>
      ) : null}

      {documents.length === 0 ? (
        <p className={styles.hint}>
          还没有文档。上传后会自动解析、切片并建立索引，可检索状态出现后即可在对话中引用。
        </p>
      ) : (
        <ul className={styles.docList}>
          {documents.map((document) => (
            <li key={document.id} className={styles.doc}>
              <div className={styles.docMain}>
                <div className={styles.docTitle}>
                  <span>{document.title}</span>
                  <span className={`${styles.status} ${STATUS_CLASS[document.status]}`}>
                    {STATUS_LABEL[document.status]}
                  </span>
                </div>
                <div className={styles.docMeta}>
                  {SOURCE_LABEL[document.sourceType]} · {formatBytes(document.sizeBytes)} ·{" "}
                  {document.chunkCount} 个切片 · {formatDateTime(document.updatedAt)}
                </div>
                {document.status === "failed" && document.errorMessage ? (
                  <div className={styles.docError}>
                    {document.errorCode ? `[${document.errorCode}] ` : ""}
                    {document.errorMessage}
                  </div>
                ) : null}
                {document.status === "stale" ? (
                  <div className={styles.docError}>
                    embedding 模型或维度已变化，需重建后才能检索。
                  </div>
                ) : null}
              </div>
              <div className={styles.docActions}>
                {document.status === "failed" ||
                document.status === "ready" ||
                document.status === "stale" ? (
                  <Button
                    variant="secondary"
                    size="sm"
                    type="button"
                    disabled={busy}
                    onClick={() => void onReingest(document.id)}
                  >
                    重建
                  </Button>
                ) : null}
                {document.status !== "deleting" ? (
                  <Button
                    variant="danger"
                    size="sm"
                    type="button"
                    disabled={busy}
                    onClick={() => setPendingDelete(document)}
                  >
                    删除
                  </Button>
                ) : null}
              </div>
            </li>
          ))}
        </ul>
      )}

      {pendingDelete ? (
        <Dialog
          title="删除文档"
          description={`《${pendingDelete.title}》的原文、切片与向量都会被删除，且不可恢复。`}
          closeOnOverlay={false}
          onClose={() => setPendingDelete(null)}
          actions={
            <>
              <Button variant="secondary" type="button" onClick={() => setPendingDelete(null)}>
                取消
              </Button>
              <Button
                variant="danger"
                type="button"
                disabled={busy}
                onClick={() => {
                  const id = pendingDelete.id;
                  setPendingDelete(null);
                  void onDelete(id);
                }}
              >
                确认删除
              </Button>
            </>
          }
        >
          <p className={styles.hint}>删除是异步的：列表状态会先变为「删除中」，完成后行消失。</p>
        </Dialog>
      ) : null}
    </section>
  );
}
