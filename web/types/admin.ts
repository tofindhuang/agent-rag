import type {
  UsageDaily,
  UsageTotals,
  UsageWindow,
  UsageWindowKey,
} from "./usage";

export interface AdminActive {
  runningRuns: number;
  activeSessions15m: number;
}

export interface AdminUsage {
  tokensIn: number;
  tokensOut: number;
  cacheReadTokens: number;
  cacheWriteTokens: number;
  cost: number;
  unpricedRuns: number;
}

export interface AdminExecutions {
  total: number;
  succeeded: number;
  failed: number;
  timedOut: number;
}

export interface AdminRetrieval {
  calls: number;
  hitCalls: number;
  hitRate: number | null;
  avgPassages: number | null;
  failedCalls: number;
}

export interface AdminCompactions {
  count: number;
  tokensBefore: number;
  tokensAfter: number;
}

export interface AdminSandbox {
  executions: number;
  queueP50Ms: number | null;
  queueP95Ms: number | null;
  durationP50Ms: number | null;
  durationP95Ms: number | null;
}

export interface AdminDaily {
  date: string;
  tokensIn: number;
  tokensOut: number;
  cacheReadTokens: number;
  cacheWriteTokens: number;
  cost: number;
  unpricedRuns: number;
  executions: number;
  compactions: number;
  hitRate: number | null;
}

export interface AdminOverview {
  days: number;
  currency: string;
  active: AdminActive;
  usage: AdminUsage;
  executions: AdminExecutions;
  retrieval: AdminRetrieval;
  compactions: AdminCompactions;
  sandbox: AdminSandbox;
  daily: AdminDaily[];
}

export type AdminTab = "overview" | "users" | "trace";

export type AdminWindow = UsageWindowKey;

export interface AdminUserSummary {
  userId: number;
  email: string;
  createdAt: string | null;
  lastActiveAt: string | null;
  sessions: number;
  tokensIn: number;
  tokensOut: number;
  cacheReadTokens: number;
  cacheWriteTokens: number;
  cost: number;
  unpricedRuns: number;
  executions: number;
  searches: number;
}

export interface AdminUserList {
  currency: string;
  items: AdminUserSummary[];
  total: number;
  page: number;
  size: number;
}

export interface AdminUserInfo {
  userId: number;
  email: string;
  createdAt: string | null;
  lastActiveAt: string | null;
  sessions: number;
}

export interface AdminUserModel {
  modelId: string | null;
  tokensIn: number;
  tokensOut: number;
  cacheReadTokens: number;
  cacheWriteTokens: number;
  cost: number;
}

export interface AdminUserSession {
  sessionId: string;
  createdAt: string | null;
  lastActiveAt: string | null;
  modelId: string | null;
  tokensIn: number;
  tokensOut: number;
  cacheReadTokens: number;
  cacheWriteTokens: number;
  cost: number;
  executions: number;
}

export interface AdminUserSessions {
  items: AdminUserSession[];
  total: number;
  page: number;
  size: number;
}

export interface AdminUserDetail {
  currency: string;
  user: AdminUserInfo;
  today: UsageTotals;
  window: UsageWindow;
  daily: UsageDaily[];
  models: AdminUserModel[];
  sessions: AdminUserSessions;
}

export type AdminUserSort = "lastActive" | "cost" | "tokens";
