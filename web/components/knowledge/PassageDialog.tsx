"use client";

import { useEffect, useState } from "react";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { Dialog } from "@/components/ui/Dialog";
import { TextField } from "@/components/ui/TextField";
import {
  createKbEvalLabel,
  fetchKbPassages,
  knowledgeError,
} from "@/lib/api/knowledge";
import type { KbDocument, KbPassage } from "@/types/knowledge";
import styles from "./knowledge.module.css";

interface PassageDialogProps {
  document: KbDocument;
  onClose: () => void;
}

/** Paginated passage viewer; no document content is fetched from the original. */
export function PassageDialog({ document, onClose }: PassageDialogProps) {
  const [items, setItems] = useState<KbPassage[]>([]);
  const [nextSeq, setNextSeq] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [annotatingSeq, setAnnotatingSeq] = useState<number | null>(null);
  const [annotationQuery, setAnnotationQuery] = useState("");
  const [saving, setSaving] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  const load = async (afterSeq: number) => {
    setLoading(true);
    setError(null);
    try {
      const page = await fetchKbPassages(document.id, afterSeq);
      setItems((previous) => (afterSeq === 0 ? page.items : [...previous, ...page.items]));
      setNextSeq(page.nextSeq);
    } catch (cause: unknown) {
      console.error(cause);
      setError(knowledgeError(cause));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load(0);
    // The dialog is remounted per document; only the id matters here.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [document.id]);

  const saveAnnotation = async (seq: number) => {
    const trimmed = annotationQuery.trim();
    if (trimmed.length === 0) return;
    setSaving(true);
    setError(null);
    setNotice(null);
    try {
      await createKbEvalLabel({
        kbId: document.kbId,
        query: trimmed,
        documentId: document.id,
        seq,
      });
      setAnnotatingSeq(null);
      setAnnotationQuery("");
      setNotice("已标为期望答案；同一问题再次标注会覆盖目标片段。");
    } catch (cause: unknown) {
      console.error(cause);
      setError(knowledgeError(cause));
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog
      title={`切片 · ${document.title}`}
      description={`共 ${document.chunkCount} 个切片；展示顺序、页码、字数与入库正文。`}
      onClose={onClose}
    >
      {error ? <Banner tone="error">{error}</Banner> : null}
      {notice ? <Banner tone="notice">{notice}</Banner> : null}
      {items.length === 0 && !loading ? (
        <p className={styles.hint}>还没有切片；文档可能仍在处理或没有可抽取文本。</p>
      ) : (
        <ul className={styles.passageList}>
          {items.map((passage) => (
            <li key={passage.seq} className={styles.passage}>
              <div className={styles.passageMeta}>
                第 {passage.seq} 段 · 页码 {passage.page ?? "—"} · {passage.charCount} 字
              </div>
              <pre className={styles.passageBody}>{passage.content}</pre>
              {annotatingSeq === passage.seq ? (
                <div className={styles.annotationForm}>
                  <TextField
                    label="这个问题应该命中本段"
                    value={annotationQuery}
                    maxLength={512}
                    placeholder="输入你能判断答案的问题"
                    onChange={(event) => setAnnotationQuery(event.target.value)}
                  />
                  <div className={styles.formActions}>
                    <Button
                      size="sm"
                      disabled={saving || annotationQuery.trim().length === 0}
                      onClick={() => void saveAnnotation(passage.seq)}
                    >
                      {saving ? "保存中…" : "保存标注"}
                    </Button>
                    <Button
                      size="sm"
                      variant="secondary"
                      disabled={saving}
                      onClick={() => {
                        setAnnotatingSeq(null);
                        setAnnotationQuery("");
                      }}
                    >
                      取消
                    </Button>
                  </div>
                </div>
              ) : (
                <div className={styles.retrievalRowActions}>
                  <Button
                    size="sm"
                    variant="ghost"
                    onClick={() => {
                      setAnnotatingSeq(passage.seq);
                      setAnnotationQuery("");
                      setNotice(null);
                    }}
                  >
                    标为期望答案
                  </Button>
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
      <div className={styles.formActions}>
        <Button variant="secondary" type="button" onClick={onClose}>
          关闭
        </Button>
        {nextSeq != null ? (
          <Button
            type="button"
            disabled={loading}
            onClick={() => void load(nextSeq)}
          >
            {loading ? "加载中…" : "加载更多"}
          </Button>
        ) : null}
      </div>
    </Dialog>
  );
}
