"use client";

import { useCallback, useEffect, useState } from "react";
import { fetchAdminOverview } from "@/lib/api/admin";
import { userMessage } from "@/lib/api/errors";
import type { AdminOverview, AdminWindow } from "@/types/admin";

/** Loads the admin overview and exposes a manual refresh for the auto timer. */
export function useAdminOverview(window: AdminWindow) {
  const [data, setData] = useState<AdminOverview | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setData(await fetchAdminOverview(window));
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
