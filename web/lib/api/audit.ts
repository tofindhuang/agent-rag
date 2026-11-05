import { apiJson } from "./http";
import type { AuditPage } from "@/types/audit";

/** The caller's own security events (ADR-0033). */
export function fetchMyAudit(limit = 20, beforeId?: number | null): Promise<AuditPage> {
  const params = new URLSearchParams({ limit: String(limit) });
  if (beforeId != null) params.set("beforeId", String(beforeId));
  return apiJson<AuditPage>(`/api/audit/me?${params.toString()}`);
}

/** Admin-only trail across all actors; metadata only. */
export function fetchAdminAudit(
  limit = 50,
  beforeId?: number | null,
  action?: string | null,
): Promise<AuditPage> {
  const params = new URLSearchParams({ limit: String(limit) });
  if (beforeId != null) params.set("beforeId", String(beforeId));
  if (action) params.set("action", action);
  return apiJson<AuditPage>(`/api/admin/audit?${params.toString()}`);
}
