"use client";

import { useCallback, useEffect, useState } from "react";
import { userMessage } from "@/lib/api/errors";
import { fetchUserUsage } from "@/lib/api/usage";
import type { UsageWindowKey, UserUsage } from "@/types/usage";

/** Loads the signed-in user's usage for the selected window. */
export function useUsage(window: UsageWindowKey) {
  const [data, setData] = useState<UserUsage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setData(await fetchUserUsage(window));
    } catch (cause: unknown) {
      console.error(cause);
      setError(userMessage(cause));
    } finally {
      setLoading(false);
    }
  }, [window]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  return { data, loading, error, refresh };
}
