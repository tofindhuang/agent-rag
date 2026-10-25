import { apiJson } from "./http";
import type {
  AdminOverview,
  AdminTrace,
  AdminUserDetail,
  AdminUserList,
  AdminWindow,
} from "@/types/admin";

/** Deployment-wide observability payload for the admin dashboard. */
export function fetchAdminOverview(window: AdminWindow): Promise<AdminOverview> {
  return apiJson<AdminOverview>(`/api/admin/overview?window=${window}`);
}

export interface AdminUsersQuery {
  query: string;
  page: number;
  size: number;
  window: AdminWindow;
  sort: "lastActive" | "cost" | "tokens";
}

export function fetchAdminUsers(params: AdminUsersQuery): Promise<AdminUserList> {
  const search = new URLSearchParams({
    query: params.query,
    page: String(params.page),
    size: String(params.size),
    window: params.window,
    sort: params.sort,
  });
  return apiJson<AdminUserList>(`/api/admin/users?${search.toString()}`);
}

export function fetchAdminUserDetail(
  userId: number,
  window: AdminWindow,
  page: number,
  size: number,
): Promise<AdminUserDetail> {
  const search = new URLSearchParams({
    window,
    page: String(page),
    size: String(size),
  });
  return apiJson<AdminUserDetail>(
    `/api/admin/users/${userId}?${search.toString()}`,
  );
}

export function fetchAdminTrace(
  params: { requestId?: string; turnId?: string; runId?: string },
): Promise<AdminTrace> {
  const search = new URLSearchParams();
  if (params.requestId) search.set("requestId", params.requestId);
  if (params.turnId) search.set("turnId", params.turnId);
  if (params.runId) search.set("runId", params.runId);
  return apiJson<AdminTrace>(`/api/admin/trace?${search.toString()}`);
}
