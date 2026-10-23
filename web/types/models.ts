export type ProviderApi = "openai-completions" | "anthropic-messages";

export interface ProviderModel {
  id: number;
  modelId: string;
  displayName: string;
  contextWindow: number | null;
  reasoning: boolean;
  vision: boolean;
}

export interface ModelProvider {
  id: number;
  name: string;
  api: ProviderApi;
  baseUrl: string;
  apiKeyHint: string;
  models: ProviderModel[];
}

export interface SystemDefaultModel {
  available: boolean;
  label: string | null;
}

export interface ModelCatalog {
  providers: ModelProvider[];
  defaultModelId: number | null;
  systemDefault: SystemDefaultModel;
}

export interface ProbeModel {
  id: string;
  name: string;
}

export interface ProviderModelInput {
  modelId: string;
  displayName: string;
  contextWindow?: number | null;
  reasoning?: boolean;
  vision?: boolean;
}

export interface ProviderInput {
  name: string;
  api: ProviderApi;
  baseUrl: string;
  apiKey?: string;
  models: ProviderModelInput[];
}
