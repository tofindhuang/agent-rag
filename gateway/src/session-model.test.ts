import assert from "node:assert/strict";
import { test } from "node:test";
import { buildSessionModel, modelRefFromConfig } from "./session-model.js";
import type { SessionModelConfig } from "./pool-contract.js";

const policy = {
  allowPrivate: false,
  resolve: async () => [{ address: "93.184.216.34", family: 4 }],
};

const config: SessionModelConfig = {
  name: "SiliconFlow",
  api: "openai-completions",
  baseUrl: "https://api.example.com/v1",
  apiKey: "sk-secret",
  modelId: "deepseek-chat",
  displayName: "DeepSeek Chat",
  contextWindow: 64_000,
  reasoning: true,
  vision: true,
};

test("builds an isolated runtime with the requested model", async () => {
  const built = await buildSessionModel(config, policy);

  assert.equal(built.model.id, "deepseek-chat");
  assert.equal(built.model.name, "DeepSeek Chat");
  assert.equal(built.model.reasoning, true);
  assert.ok(built.model.input.includes("image"));
  assert.equal(built.model.contextWindow, 64_000);
  assert.deepEqual(modelRefFromConfig(config), {
    provider: "SiliconFlow",
    id: "deepseek-chat",
    name: "DeepSeek Chat",
  });
});

test("registers only the requested model", async () => {
  const built = await buildSessionModel({ ...config, modelId: "another-model" }, policy);

  assert.equal(built.model.id, "another-model");
  assert.equal(built.runtime.getModel("user-SiliconFlow", "missing-model"), undefined);
});

test("applies the outbound policy to the session base URL", async () => {
  await assert.rejects(
    () =>
      buildSessionModel(
        { ...config, baseUrl: "http://169.254.169.254/latest" },
        { allowPrivate: true },
      ),
    /not allowed/,
  );
  await assert.rejects(
    () => buildSessionModel({ ...config, baseUrl: "https://10.0.0.5/v1" }, policy),
    /private/,
  );
});

test("derives the echo shape from the config", () => {
  assert.deepEqual(modelRefFromConfig(config), {
    provider: "SiliconFlow",
    id: "deepseek-chat",
    name: "DeepSeek Chat",
  });
});
