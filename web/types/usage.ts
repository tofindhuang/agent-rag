export type UsageWindowKey = "7d" | "30d";

export interface UsageTotals {
  tokensIn: number;
  tokensOut: number;
  cacheReadTokens: number;
  cacheWriteTokens: number;
  cost: number;
  unpricedRuns: number;
  executions: number;
  searches: number;
}

export interface UsageWindow extends UsageTotals {
  days: number;
}

export interface UsageDaily extends UsageTotals {
  date: string;
}

export interface UsageTopSession {
  sessionId: string;
  title: string;
  lastActiveAt: string | null;
  tokensIn: number;
  tokensOut: number;
  cacheReadTokens: number;
  cacheWriteTokens: number;
  cost: number;
  executions: number;
}

export interface UsageQuota {
  enforced: boolean;
  limitTokens: number | null;
  usedTokens: number;
  remainingTokens: number | null;
  resetsAt: string | null;
}

export interface UserUsage {
  currency: string;
  today: UsageTotals;
  window: UsageWindow;
  daily: UsageDaily[];
  topSessions: UsageTopSession[];
  quota: UsageQuota;
}
