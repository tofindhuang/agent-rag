"use client";

import { useState } from "react";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { TextField } from "@/components/ui/TextField";
import styles from "./knowledge.module.css";

const MAX_FILE_BYTES = 20 * 1024 * 1024;
const ACCEPT = ".pdf,.md,.markdown,.txt,.docx";
const ALLOWED_EXTENSIONS = new Set(["pdf", "md", "markdown", "txt", "docx"]);

interface UploadPanelProps {
  busy: boolean;
  onUploadFile: (file: File) => Promise<boolean>;
  onUploadUrl: (url: string) => Promise<boolean>;
}

/** File/URL intake with the same limits the server enforces. */
export function UploadPanel({ busy, onUploadFile, onUploadUrl }: UploadPanelProps) {
  const [mode, setMode] = useState<"file" | "url">("file");
  const [file, setFile] = useState<File | null>(null);
  const [url, setUrl] = useState("");
  const [error, setError] = useState<string | null>(null);

  const pickFile = (event: React.ChangeEvent<HTMLInputElement>) => {
    const selected = event.target.files?.[0] ?? null;
    setError(null);
    if (!selected) {
      setFile(null);
      return;
    }
    const extension = selected.name.split(".").pop()?.toLowerCase() ?? "";
    if (!ALLOWED_EXTENSIONS.has(extension)) {
      setError("仅支持 PDF / Markdown / TXT / DOCX");
      setFile(null);
      return;
    }
    if (selected.size > MAX_FILE_BYTES) {
      setError("文件不能超过 20 MB");
      setFile(null);
      return;
    }
    setFile(selected);
  };

  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError(null);
    if (mode === "file") {
      if (!file) {
        setError("请选择要上传的文件");
        return;
      }
      if (await onUploadFile(file)) setFile(null);
      return;
    }
    const trimmed = url.trim();
    if (!/^https?:\/\//i.test(trimmed)) {
      setError("请输入以 http(s):// 开头的网页地址");
      return;
    }
    if (await onUploadUrl(trimmed)) setUrl("");
  };

  return (
    <section className={styles.section} aria-labelledby="kb-upload-title">
      <div className={styles.sectionHeader}>
        <h2 id="kb-upload-title">添加文档</h2>
      </div>
      <div className={styles.tabs} role="tablist" aria-label="添加方式">
        <button
          className={mode === "file" ? styles.tabActive : styles.tab}
          type="button"
          role="tab"
          aria-selected={mode === "file"}
          onClick={() => setMode("file")}
        >
          上传文件
        </button>
        <button
          className={mode === "url" ? styles.tabActive : styles.tab}
          type="button"
          role="tab"
          aria-selected={mode === "url"}
          onClick={() => setMode("url")}
        >
          抓取网页
        </button>
      </div>

      <form className={`${styles.form} ${styles.uploadForm}`} onSubmit={(event) => void submit(event)}>
        {mode === "file" ? (
          <div className={styles.uploadRow}>
            <input
              id="kb-file"
              className={styles.fileInput}
              aria-label="选择文件"
              type="file"
              accept={ACCEPT}
              onChange={pickFile}
              disabled={busy}
            />
            <Button type="submit" disabled={busy || file === null}>
              {busy ? "提交中…" : "上传并解析"}
            </Button>
          </div>
        ) : (
          <div className={styles.uploadRow}>
            <TextField
              className={styles.urlInput}
              label="网页地址"
              type="url"
              placeholder="https://example.com/article"
              value={url}
              onChange={(event) => setUrl(event.target.value)}
              disabled={busy}
              maxLength={2048}
            />
            <Button type="submit" disabled={busy || url.trim().length === 0}>
              {busy ? "提交中…" : "抓取并解析"}
            </Button>
          </div>
        )}
        <p className={styles.hint}>
          支持 PDF / Markdown / TXT / DOCX，单文件 ≤ 20 MB；网页抓取 ≤ 5 MB、30 秒超时。
        </p>
        {error ? <Banner tone="error">{error}</Banner> : null}
      </form>
    </section>
  );
}
