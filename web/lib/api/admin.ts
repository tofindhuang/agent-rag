import { apiJson } from "./http";
import type { AdminOverview, AdminWindow } from "@/types/admin";

/** Deployment-wide observability payload for the admin dashboard. */
export function fetchAdminOverview(window: AdminWindow): Promise<AdminOverview> {
  return apiJson<AdminOverview>(`/api/admin/overview?window=${window}`);
}
