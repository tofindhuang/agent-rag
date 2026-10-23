import { assertSafeEndpoint, EndpointResolutionError, type EndpointPolicy } from "./outbound.js";

export type ProviderApi = "openai-completions" | "anthropic-messages";

export interface ProbeInput {
  api: ProviderApi;
  baseUrl: string;
  apiKey: string;
}

export interface ProviderModel {
  id: string;
  name: string;
}

export type ProbeErrorCode =
  | "PROVIDER_UNREACHABLE"
  | "PROVIDER_AUTH_FAILED"
  | "PROVIDER_PROBE_FAILED";

export class ProbeError extends Error {
  constructor(
    readonly code: ProbeErrorCode,
    message: string,
    readonly retryable = false,
  ) {
    super(message);
    this.name = "ProbeError";
  }
}

export type ProbeOptions = EndpointPolicy;

const MODELS_TIMEOUT_MS = 10_000;

/**
 * Calls the provider's model-list endpoint with the user's key. Stateless:
 * nothing is persisted, and the key only lives in this request.
 */
export async function probeProvider(
  input: ProbeInput,
  options: ProbeOptions,
): Promise<ProviderModel[]> {
  let base: URL;
  try {
    base = await assertSafeEndpoint(input.baseUrl, options);
  } catch (error) {
    if (error instanceof EndpointResolutionError) {
      throw new ProbeError("PROVIDER_UNREACHABLE", "cannot resolve model provider", true);
    }
    throw error;
  }
  const url = modelsEndpoint(base, input.api);
  const headers: Record<string, string> =
    input.api === "openai-completions"
      ? { authorization: `Bearer ${input.apiKey}`, accept: "application/json" }
      : {
          "x-api-key": input.apiKey,
          "anthropic-version": "2023-06-01",
          accept: "application/json",
        };

  let response: Response;
  try {
    response = await fetch(url, {
      method: "GET",
      headers,
      redirect: "error",
      signal: AbortSignal.timeout(MODELS_TIMEOUT_MS),
    });
  } catch {
    throw new ProbeError("PROVIDER_UNREACHABLE", "cannot reach model provider", true);
  }

  if (response.status === 401 || response.status === 403) {
    throw new ProbeError("PROVIDER_AUTH_FAILED", "provider rejected the API key");
  }
  if (!response.ok) {
    throw new ProbeError("PROVIDER_PROBE_FAILED", `provider returned HTTP ${response.status}`);
  }

  let body: unknown;
  try {
    body = await response.json();
  } catch {
    throw new ProbeError("PROVIDER_PROBE_FAILED", "provider returned invalid JSON");
  }
  const models = parseModels(body);
  if (models === null) {
    throw new ProbeError("PROVIDER_PROBE_FAILED", "provider response has no model list");
  }
  return models;
}

function modelsEndpoint(base: URL, api: ProviderApi): URL {
  const url = new URL(base.toString());
  url.search = "";
  url.hash = "";
  let path = url.pathname.replace(/\/+$/, "");
  if (api === "anthropic-messages" && !path.endsWith("/v1")) {
    path += "/v1";
  }
  url.pathname = `${path}/models`;
  return url;
}

/** Accepts the OpenAI `{data:[...]}` and Anthropic `{data:[...]}` shapes. */
function parseModels(body: unknown): ProviderModel[] | null {
  if (typeof body !== "object" || body === null) return null;
  const record = body as Record<string, unknown>;
  const list = Array.isArray(record.data)
    ? record.data
    : Array.isArray(record.models)
      ? record.models
      : null;
  if (list === null) return null;

  const models: ProviderModel[] = [];
  const seen = new Set<string>();
  for (const raw of list) {
    if (typeof raw !== "object" || raw === null) continue;
    const item = raw as Record<string, unknown>;
    if (typeof item.id !== "string" || item.id.length === 0) continue;
    if (seen.has(item.id)) continue;
    seen.add(item.id);
    const name =
      typeof item.display_name === "string"
        ? item.display_name
        : typeof item.name === "string"
          ? item.name
          : item.id;
    models.push({ id: item.id, name });
  }
  return models;
}
