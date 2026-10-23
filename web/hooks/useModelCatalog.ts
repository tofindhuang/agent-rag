"use client";

import { useCallback, useEffect, useState } from "react";
import { userMessage } from "@/lib/api/errors";
import {
  deleteProvider,
  fetchModelCatalog,
  setDefaultModel,
} from "@/lib/api/models";
import type { ModelCatalog } from "@/types/models";

/** Owns model catalog loading and provider mutations; components only render. */
export function useModelCatalog() {
  const [catalog, setCatalog] = useState<ModelCatalog | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const reload = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setCatalog(await fetchModelCatalog());
    } catch (cause: unknown) {
      console.error(cause);
      setError(userMessage(cause));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  const changeDefault = useCallback(
    async (modelId: number | null) => {
      setError(null);
      try {
        await setDefaultModel(modelId);
        setNotice(modelId == null ? "已切换为系统默认模型" : "已更新默认模型");
        await reload();
      } catch (cause: unknown) {
        console.error(cause);
        setError(userMessage(cause));
      }
    },
    [reload],
  );

  const removeProvider = useCallback(
    async (providerId: number) => {
      setError(null);
      try {
        await deleteProvider(providerId);
        setNotice("供应商已删除");
        await reload();
      } catch (cause: unknown) {
        console.error(cause);
        setError(userMessage(cause));
      }
    },
    [reload],
  );

  return {
    catalog,
    loading,
    error,
    notice,
    setNotice,
    reload,
    changeDefault,
    removeProvider,
  };
}
