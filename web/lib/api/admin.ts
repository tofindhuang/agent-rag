import { apiJson } from "./http";
import type {
  AdminOverview,
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
