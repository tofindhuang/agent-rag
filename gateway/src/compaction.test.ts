import assert from "node:assert/strict";
import { test } from "node:test";
import { SettingsManager } from "@earendil-works/pi-coding-agent";
import {
  applyCompactionSettings,
  buildCompactionPlan,
  DEFAULT_CONTEXT_WINDOW,
  type CompactionConfig,
} from "./compaction.js";

const config: CompactionConfig = {
  enabled: true,
  reserveRatio: 0.2,
  keepRecentTokens: 20_000,
};

test("computes reserve from each model window at the configured ratio", () => {
  assert.deepEqual(buildCompactionPlan(128_000, config), {
    enabled: true,
    contextWindow: 128_000,
    reserveTokens: 25_600,
    keepRecentTokens: 20_000,
    thresholdPercent: 80,
  });
  assert.deepEqual(buildCompactionPlan(8_000, config), {
    enabled: true,
    contextWindow: 8_000,
    reserveTokens: 1_600,
    keepRecentTokens: 4_000,
    thresholdPercent: 80,
  });
  assert.deepEqual(buildCompactionPlan(200_000, config), {
    enabled: true,
    contextWindow: 200_000,
    reserveTokens: 40_000,
    keepRecentTokens: 20_000,
    thresholdPercent: 80,
  });
});

test("falls back to 128k for missing or invalid windows", () => {
  for (const window of [undefined, 0, -1, 1.5, Number.NaN, 20_000_000]) {
    assert.equal(buildCompactionPlan(window, config).contextWindow, DEFAULT_CONTEXT_WINDOW);
  }
});

test("clamps the ratio and keeps a minimum reserve", () => {
  assert.equal(
    buildCompactionPlan(128_000, { ...config, reserveRatio: 0.01 }).reserveTokens,
    6_400,
  );
  assert.equal(
    buildCompactionPlan(128_000, { ...config, reserveRatio: 0.9 }).reserveTokens,
    64_000,
  );
  assert.equal(
    buildCompactionPlan(2_000, { ...config, reserveRatio: 0.01 }).reserveTokens,
    1_024,
  );
});

test("keeps at most half of small windows verbatim", () => {
  assert.equal(
    buildCompactionPlan(8_000, config).keepRecentTokens,
    4_000,
  );
  assert.equal(
    buildCompactionPlan(40_000, { ...config, keepRecentTokens: 50_000 }).keepRecentTokens,
    20_000,
  );
  assert.equal(
    buildCompactionPlan(128_000, { ...config, keepRecentTokens: 50_000 }).keepRecentTokens,
    50_000,
  );
});

test("disabled keeps the plan for display but stops auto compaction", () => {
  const plan = buildCompactionPlan(128_000, { ...config, enabled: false });

  assert.equal(plan.enabled, false);
  assert.equal(plan.thresholdPercent, 80);
});

test("applies overrides to a session settings manager without writing files", () => {
  const settingsManager = SettingsManager.inMemory();
  const plan = applyCompactionSettings(settingsManager, 8_000, config);

  assert.deepEqual(
    settingsManager.getCompactionSettings({ provider: "test", id: "small" }),
    {
      enabled: true,
      reserveTokens: 1_600,
      keepRecentTokens: 4_000,
    },
  );
  assert.equal(plan.reserveTokens, 1_600);

  applyCompactionSettings(settingsManager, 128_000, config);
  assert.deepEqual(
    settingsManager.getCompactionSettings({ provider: "test", id: "large" }),
    {
      enabled: true,
      reserveTokens: 25_600,
      keepRecentTokens: 20_000,
    },
  );
});
