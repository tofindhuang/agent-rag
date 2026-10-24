"use client";

import { useCallback, useEffect, useState } from "react";
import {
  deleteKnowledgeDocument,
  fetchKnowledgeDocuments,
  knowledgeError,
  reingestAllKnowledgeDocuments,
  reingestKnowledgeDocument,
  uploadKnowledgeFile,
  uploadKnowledgeUrl,
} from "@/lib/api/knowledge";
import type { KbDocument, KbUsage } from "@/types/knowledge";

const POLL_INTERVAL_MS = 2_000;

function isActive(document: KbDocument): boolean {
  return (
    document.status === "queued" ||
    document.status === "processing" ||
    document.status === "deleting"
  );
}

/**
 * Document list + quota state. `queued`/`processing`/`deleting` rows are
 * polled every 2s until the worker settles them; every mutation refreshes the
 * list so the outbox has one visible source of truth.
 */
export function useKnowledge() {
  const [documents, setDocuments] = useState<KbDocument[]>([]);
  const [usage, setUsage] = useState<KbUsage | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    const page = await fetchKnowledgeDocuments();
    setDocuments(page.items);
    setUsage(page.usage);
  }, []);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    refresh()
      .catch((cause: unknown) => {
        if (!cancelled) setError(knowledgeError(cause));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [refresh]);

  const polling = documents.some(isActive);
  useEffect(() => {
    if (!polling) return;
    const timer = window.setInterval(() => {
      void refresh().catch((cause: unknown) => setError(knowledgeError(cause)));
    }, POLL_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [polling, refresh]);

  const run = useCallback(
    async (action: () => Promise<void>, success?: string): Promise<boolean> => {
      setBusy(true);
      setError(null);
      setNotice(null);
      try {
        await action();
        await refresh();
        if (success) setNotice(success);
        return true;
      } catch (cause: unknown) {
        console.error(cause);
        setError(knowledgeError(cause));
        return false;
      } finally {
        setBusy(false);
      }
    },
    [refresh],
  );

  return {
    documents,
    usage,
    loading,
    busy,
    error,
    notice,
    refresh,
    clearError: () => setError(null),
    uploadFile: (file: File) =>
      run(() => uploadKnowledgeFile(file), "已提交，正在排队解析与建立索引"),
    uploadUrl: (url: string) =>
      run(() => uploadKnowledgeUrl(url), "已提交，正在抓取与建立索引"),
    reingest: (id: string) => run(() => reingestKnowledgeDocument(id), "已重新排队"),
    reingestAll: () =>
      run(() => reingestAllKnowledgeDocuments(), "已把需重建的文档全部重新排队"),
    remove: (id: string) => run(() => deleteKnowledgeDocument(id), "已提交删除"),
  };
}
