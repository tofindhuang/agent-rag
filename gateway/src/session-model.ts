import { ModelRuntime } from "@earendil-works/pi-coding-agent";
import type { Api, Model } from "@earendil-works/pi-ai";
import { assertSafeEndpoint, type Resolver } from "./outbound.js";
import type { ModelRef, SessionModelConfig } from "./pool-contract.js";

export interface BuiltSessionModel {
  runtime: ModelRuntime;
  model: Model<Api>;
}

export interface SessionModelPolicy {
  allowPrivate: boolean;
  resolve?: Resolver;
}

const DEFAULT_CONTEXT_WINDOW = 128_000;
const MAX_OUTPUT_TOKENS = 8_192;

/**
 * Builds an isolated, in-memory ModelRuntime for one session (ADR-0010).
 * The API key lives only in the runtime credential overlay.
 */
export async function buildSessionModel(
  config: SessionModelConfig,
  policy: SessionModelPolicy,
): Promise<BuiltSessionModel> {
  // Every user-supplied base URL goes through the same outbound policy as
  // provider probing (ADR-0010), before pi can issue any request.
  await assertSafeEndpoint(config.baseUrl, policy);

  const providerKey = providerKeyFor(config);
  const contextWindow = config.contextWindow ?? DEFAULT_CONTEXT_WINDOW;
  const runtime = await ModelRuntime.create({ refreshOnCreate: false, modelsPath: null });

  runtime.registerProvider(providerKey, {
    name: config.name ?? providerKey,
    baseUrl: config.baseUrl,
    api: config.api,
    models: [
      {
        id: config.modelId,
        name: config.displayName ?? config.modelId,
        api: config.api,
        reasoning: config.reasoning === true,
        input: config.vision === true ? ["text", "image"] : ["text"],
        cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
        contextWindow,
        maxTokens: Math.max(256, Math.min(contextWindow, MAX_OUTPUT_TOKENS)),
      },
    ],
  });
  await runtime.setRuntimeApiKey(providerKey, config.apiKey);

  const model = runtime.getModel(providerKey, config.modelId);
  if (!model) {
    throw new Error(`failed to register session model ${config.modelId}`);
  }
  return { runtime, model };
}

export function modelRefFromConfig(config: SessionModelConfig): ModelRef {
  return {
    provider: config.name ?? providerKeyFor(config),
    id: config.modelId,
    name: config.displayName ?? config.modelId,
  };
}

export function modelRefFromSession(model: Model<Api> | undefined): ModelRef | undefined {
  if (!model) return undefined;
  return { provider: model.provider, id: model.id, name: model.name };
}

function providerKeyFor(config: SessionModelConfig): string {
  const raw =
    config.providerId !== undefined
      ? String(config.providerId)
      : (config.name ?? "provider");
  return `user-${raw.replace(/[^a-zA-Z0-9_-]/g, "_")}`;
}
