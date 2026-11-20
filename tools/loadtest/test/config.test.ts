import { test } from "node:test";
import assert from "node:assert/strict";

import { configFromEnv } from "../src/config.js";

test("reads model settings from the environment with DeepSeek defaults", () => {
  const config = configFromEnv({
    LOADTEST_BASE_URL: "http://localhost:8080",
    LOADTEST_MODEL_API_KEY: "secret-key-value",
    LOADTEST_METRICS_FILE: "/tmp/metrics.jsonl",
  });
  assert.equal(config.baseUrl, "http://localhost:8080");
  assert.equal(config.model.apiKey, "secret-key-value");
  assert.equal(config.model.baseUrl, "https://api.deepseek.com");
  assert.equal(config.model.modelId, "deepseek-chat");
  assert.equal(config.model.api, "openai-completions");
  assert.equal(config.metricsFile, "/tmp/metrics.jsonl");
});

test("missing model key fails without echoing any secret material", () => {
  assert.throws(
    () => configFromEnv({}),
    (error: unknown) => {
      assert.ok(error instanceof Error);
      assert.match(error.message, /LOADTEST_MODEL_API_KEY/);
      assert.doesNotMatch(error.message, /secret/);
      return true;
    },
  );
});
