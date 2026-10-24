"use client";

import Link from "next/link";
import { Banner } from "@/components/ui/Banner";
import { useKnowledge } from "@/hooks/useKnowledge";
import { DocumentList } from "./DocumentList";
import { SettingsPanel } from "./SettingsPanel";
import { UploadPanel } from "./UploadPanel";
import styles from "./knowledge.module.css";

/** Knowledge management page: upload, status polling, rebuild/delete, settings. */
export function KnowledgePage() {
  const knowledge = useKnowledge();

  return (
    <main className={styles.page}>
      <header className={styles.header}>
        <Link className={styles.back} href="/">
          ← 返回聊天
        </Link>
        <h1 className={styles.title}>知识库</h1>
        <p className={styles.subtitle}>
          上传后自动解析、切片并建立索引；对话中的 kb_search 会检索这些文档并展示引用。
        </p>
      </header>

      {knowledge.error ? <Banner tone="error">{knowledge.error}</Banner> : null}
      {knowledge.notice ? <Banner tone="notice">{knowledge.notice}</Banner> : null}
      {knowledge.loading && knowledge.documents.length === 0 ? (
        <p className={styles.hint} role="status">
          文档加载中…
        </p>
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
    </main>
  );
}
