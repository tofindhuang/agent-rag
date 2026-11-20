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

export type AdminTab = "overview" | "users" | "trace" | "audit";

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

export interface AdminTraceToolCall {
  name: string | null;
  toolCallId: string | null;
}

export interface AdminTraceNode {
  type: "run_started" | "assistant" | "tool" | "run_settled";
  at: string | null;
  missing: boolean;
  modelId: string | null;
  seq: number | null;
  tokensIn: number | null;
  tokensOut: number | null;
  cacheReadTokens: number | null;
  cacheWriteTokens: number | null;
  toolCalls: AdminTraceToolCall[];
  name: string | null;
  passages: number | null;
  topScore: number | null;
  execId: string | null;
  status: string | null;
  durationMs: number | null;
  queueMs: number | null;
  exitCode: number | null;
  errorCode: string | null;
  errorMessage: string | null;
}

export interface AdminTraceSummary {
  requestId: string | null;
  userId: number;
  email: string;
  sessionId: string;
  turnId: string;
  runId: string;
  modelId: string | null;
  status: string;
  tokensIn: number;
  tokensOut: number;
  cacheReadTokens: number;
  cacheWriteTokens: number;
  cost: number | null;
  startedAt: string | null;
  settledAt: string | null;
}

export interface AdminTrace {
  currency: string;
  summary: AdminTraceSummary;
  timeline: AdminTraceNode[];
}

export type AdminTraceKey = "requestId" | "turnId" | "runId";
