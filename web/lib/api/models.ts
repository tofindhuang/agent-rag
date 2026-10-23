import { ApiError, toApiError } from "./errors";
import { authFetch } from "./http";
import type {
  ModelCatalog,
  ProbeModel,
  ProviderApi,
  ProviderInput,
} from "@/types/models";

interface ProbeRequest {
  api: ProviderApi;
  baseUrl: string;
  apiKey?: string;
  providerId?: number;
}

async function readJson(response: Response): Promise<unknown> {
  if (!response.ok) throw await toApiError(response);
  return response.json();
}

// Boundary value from our own API; callers narrow fields before use.
function asRecord(value: unknown): Record<string, unknown> {
  return typeof value === "object" && value !== null ? (value as Record<string, unknown>) : {};
}

function parseCatalog(value: unknown): ModelCatalog {
  const body = asRecord(value);
  const providers = Array.isArray(body.providers)
    ? body.providers.map((raw) => {
        const provider = asRecord(raw);
        const models = Array.isArray(provider.models)
          ? provider.models.map((modelRaw) => {
              const model = asRecord(modelRaw);
              return {
                id: Number(model.id),
                modelId: String(model.modelId ?? ""),
                displayName: String(model.displayName ?? ""),
                contextWindow:
                  typeof model.contextWindow === "number" ? model.contextWindow : null,
                reasoning: model.reasoning === true,
                vision: model.vision === true,
              };
            })
          : [];
        return {
          id: Number(provider.id),
          name: String(provider.name ?? ""),
          // Boundary assumption: the server only emits the two known APIs.
          api: (provider.api === "anthropic-messages"
            ? "anthropic-messages"
            : "openai-completions") as ProviderApi,
          baseUrl: String(provider.baseUrl ?? ""),
          apiKeyHint: String(provider.apiKeyHint ?? ""),
          models,
        };
      })
    : [];
  const systemDefault = asRecord(body.systemDefault);
  return {
    providers,
    defaultModelId: typeof body.defaultModelId === "number" ? body.defaultModelId : null,
    systemDefault: {
      available: systemDefault.available === true,
      label: typeof systemDefault.label === "string" ? systemDefault.label : null,
    },
  };
}

export async function fetchModelCatalog(): Promise<ModelCatalog> {
  return parseCatalog(await readJson(await authFetch("/api/models")));
}

export async function probeModels(request: ProbeRequest): Promise<ProbeModel[]> {
  const response = await authFetch("/api/models/probe", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(request),
  });
  const body = asRecord(await readJson(response));
  if (!Array.isArray(body.models)) throw new ApiError("服务响应格式不正确");
  return body.models.map((raw) => {
    const model = asRecord(raw);
    return { id: String(model.id ?? ""), name: String(model.name ?? "") };
  });
}

export async function createProvider(input: ProviderInput): Promise<number> {
  const response = await authFetch("/api/models/providers", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(input),
  });
  const body = asRecord(await readJson(response));
  if (typeof body.id !== "number") throw new ApiError("服务响应缺少供应商 ID");
  return body.id;
}

export async function updateProvider(id: number, input: ProviderInput): Promise<void> {
  const response = await authFetch(`/api/models/providers/${id}`, {
    method: "PATCH",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(input),
  });
  if (!response.ok) throw await toApiError(response);
}

export async function deleteProvider(id: number): Promise<void> {
  const response = await authFetch(`/api/models/providers/${id}`, { method: "DELETE" });
  if (!response.ok) throw await toApiError(response);
}

export async function setDefaultModel(modelId: number | null): Promise<void> {
  const response = await authFetch("/api/models/default", {
    method: "PUT",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ modelId }),
  });
  if (!response.ok) throw await toApiError(response);
}
