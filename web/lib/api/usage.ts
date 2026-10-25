import { apiJson } from "./http";
import type { UsageWindowKey, UserUsage } from "@/types/usage";

/** Self-service usage for the signed-in user (FR-7). */
export function fetchUserUsage(window: UsageWindowKey): Promise<UserUsage> {
  return apiJson<UserUsage>(`/api/usage/me?window=${window}`);
}
