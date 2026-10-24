import { resolve } from "node:path";

export interface ModelConfig {
  apiKey: string;
  baseUrl: string;
  modelId: string;
  displayName: string;
  api: "openai-completions" | "anthropic-messages";
  providerName: string;
}

export interface LoadTestConfig {
  baseUrl: string;
  metricsFile: string;
  model: ModelConfig;
}

function required(env: NodeJS.ProcessEnv, name: string): string {
  const value = env[name];
  if (!value) {
    // Only the variable name is reported; never echo credentials.
    throw new Error(`missing required environment variable ${name}`);
  }
  return value;
}

export function configFromEnv(env: NodeJS.ProcessEnv = process.env): LoadTestConfig {
  return {
    baseUrl: env.LOADTEST_BASE_URL ?? "http://localhost:8080",
    metricsFile: resolve(process.cwd(), env.LOADTEST_METRICS_FILE ?? ".run/metrics.jsonl"),
    model: {
      apiKey: required(env, "LOADTEST_MODEL_API_KEY"),
      baseUrl: env.LOADTEST_MODEL_BASE_URL ?? "https://api.deepseek.com",
      modelId: env.LOADTEST_MODEL_ID ?? "deepseek-chat",
      displayName: env.LOADTEST_MODEL_DISPLAY_NAME ?? "DeepSeek Chat",
      api: "openai-completions",
      providerName: env.LOADTEST_PROVIDER_NAME ?? "deepseek-loadtest",
    },
  };
}
