import type { SettingsManager } from "@earendil-works/pi-coding-agent";

/** Global compaction policy (FR-4); the actual reserve is per model window. */
export interface CompactionConfig {
  enabled: boolean;
  reserveRatio: number;
  keepRecentTokens: number;
}

export interface CompactionPlan {
  enabled: boolean;
  contextWindow: number;
  reserveTokens: number;
  keepRecentTokens: number;
  /** Percentage of the window at which pi starts compacting. */
  thresholdPercent: number;
}

export const DEFAULT_CONTEXT_WINDOW = 128_000;
export const MIN_RESERVE_TOKENS = 1024;
const MAX_CONTEXT_WINDOW = 10_000_000;
const DEFAULT_RESERVE_RATIO = 0.2;
const DEFAULT_KEEP_RECENT_TOKENS = 20_000;

export function buildCompactionPlan(
  contextWindow: number | undefined,
  config: CompactionConfig,
): CompactionPlan {
  const window =
    typeof contextWindow === "number" &&
    Number.isInteger(contextWindow) &&
    contextWindow > 0 &&
    contextWindow <= MAX_CONTEXT_WINDOW
      ? contextWindow
      : DEFAULT_CONTEXT_WINDOW;
  const ratio = Number.isFinite(config.reserveRatio)
    ? Math.min(0.5, Math.max(0.05, config.reserveRatio))
    : DEFAULT_RESERVE_RATIO;
  const configuredKeep =
    Number.isSafeInteger(config.keepRecentTokens) && config.keepRecentTokens > 0
      ? config.keepRecentTokens
      : DEFAULT_KEEP_RECENT_TOKENS;
  const reserve = Math.min(
    Math.max(MIN_RESERVE_TOKENS, Math.floor(window * ratio)),
    Math.max(1, window - 1),
  );
  const keepRecent = Math.min(configuredKeep, Math.max(1, Math.floor(window / 2)));
  return {
    enabled: config.enabled,
    contextWindow: window,
    reserveTokens: reserve,
    keepRecentTokens: keepRecent,
    thresholdPercent: Math.max(
      0,
      Math.min(100, Math.round((1 - reserve / window) * 100)),
    ),
  };
}

/**
 * Applies the plan to one session's settings manager. Overrides stay in the
 * effective settings and are never written back to ~/.pi/agent.
 */
export function applyCompactionSettings(
  settingsManager: SettingsManager,
  contextWindow: number | undefined,
  config: CompactionConfig,
): CompactionPlan {
  const plan = buildCompactionPlan(contextWindow, config);
  settingsManager.applyOverrides({
    compaction: {
      enabled: plan.enabled,
      reserveTokens: plan.reserveTokens,
      keepRecentTokens: plan.keepRecentTokens,
    },
  });
  return plan;
}
