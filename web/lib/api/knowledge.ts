import { ApiError, toApiError } from "./errors";
import { authFetch } from "./http";
import type {
  KbDocument,
  KbDocumentPage,
  KbDocumentStatus,
  KbEmbeddingSettings,
  KbModelListRequest,
  KbModelListResult,
  KbProbeResult,
  KbRerankProbeInput,
  KbRerankSettings,
  KbSettings,
  KbSettingsInput,
  KbSourceType,
  KbUsage,
} from "@/types/knowledge";

// Boundary values from our own API; every field is narrowed before use.
function asRecord(value: unknown): Record<string, unknown> {
  return typeof value === "object" && value !== null ? (value as Record<string, unknown>) : {};
}

function asString(value: unknown): string {
  return typeof value === "string" ? value : "";
}

function asNullableString(value: unknown): string | null {
  return typeof value === "string" ? value : null;
}

function asNumber(value: unknown, fallback = 0): number {
  return typeof value === "number" && Number.isFinite(value) ? value : fallback;
}

function asNullableNumber(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

async function readJson(response: Response): Promise<unknown> {
  if (!response.ok) throw await toApiError(response);
  return response.json();
}

const DOCUMENT_STATUSES: KbDocumentStatus[] = [
  "queued",
  "processing",
  "ready",
  "failed",
  "deleting",
  "stale",
];

function asStatus(value: unknown): KbDocumentStatus {
  return DOCUMENT_STATUSES.includes(value as KbDocumentStatus)
    ? (value as KbDocumentStatus)
    : "queued";
}

function asSourceType(value: unknown): KbSourceType {
  return value === "url" ? "url" : "upload";
}

function parseDocument(value: unknown): KbDocument {
  const document = asRecord(value);
  return {
    id: asString(document.id),
    title: asString(document.title),
    sourceType: asSourceType(document.sourceType),
    sourceUrl: asNullableString(document.sourceUrl),
    mime: asNullableString(document.mime),
    sizeBytes: asNumber(document.sizeBytes),
    status: asStatus(document.status),
    chunkCount: asNumber(document.chunkCount),
    ocrPages: asNumber(document.ocrPages),
    errorCode: asNullableString(document.errorCode),
    errorMessage: asNullableString(document.errorMessage),
    createdAt: asString(document.createdAt),
    updatedAt: asString(document.updatedAt),
  };
}

function parseUsage(value: unknown): KbUsage {
  const quota = asRecord(value);
  return {
    documents: asNumber(quota.documents),
    bytes: asNumber(quota.bytes),
    maxDocuments: asNumber(quota.maxDocuments, 200),
    maxBytes: asNumber(quota.maxBytes, 1024 * 1024 * 1024),
  };
}

export async function fetchKnowledgeDocuments(): Promise<KbDocumentPage> {
  const body = asRecord(await readJson(await authFetch("/api/kb/documents")));
  const items = Array.isArray(body.items) ? body.items.map(parseDocument) : [];
  return { items, usage: parseUsage(body.usage) };
}

export async function uploadKnowledgeFile(file: File): Promise<void> {
  const form = new FormData();
  form.append("file", file);
  const response = await authFetch("/api/kb/documents", { method: "POST", body: form });
  if (!response.ok) throw await toApiError(response);
}

export async function uploadKnowledgeUrl(url: string): Promise<void> {
  const response = await authFetch("/api/kb/documents", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ url }).toString(),
  });
  if (!response.ok) throw await toApiError(response);
}

export async function reingestKnowledgeDocument(id: string): Promise<void> {
  const response = await authFetch(`/api/kb/documents/${encodeURIComponent(id)}/reingest`, {
    method: "POST",
  });
  if (!response.ok) throw await toApiError(response);
}

export async function reingestAllKnowledgeDocuments(): Promise<void> {
  const response = await authFetch("/api/kb/documents/reingest-all", { method: "POST" });
  if (!response.ok) throw await toApiError(response);
}

export async function deleteKnowledgeDocument(id: string): Promise<void> {
  const response = await authFetch(`/api/kb/documents/${encodeURIComponent(id)}`, {
    method: "DELETE",
  });
  if (!response.ok) throw await toApiError(response);
}

function parseEmbeddingSettings(value: unknown): KbEmbeddingSettings {
  const embedding = asRecord(value);
  const source = embedding.source;
  return {
    source:
      source === "user" || source === "system" || source === "none" ? source : "none",
    baseUrl: asNullableString(embedding.baseUrl),
    apiKeyHint: asNullableString(embedding.apiKeyHint),
    model: asNullableString(embedding.model),
    dimension: asNullableNumber(embedding.dimension),
    path: asNullableString(embedding.path),
  };
}

function parseRerankSettings(value: unknown): KbRerankSettings {
  const rerank = asRecord(value);
  const source = rerank.source;
  return {
    source:
      source === "user" || source === "system" || source === "none" || source === "disabled"
        ? source
        : "none",
    enabled: rerank.enabled === true,
    baseUrl: asNullableString(rerank.baseUrl),
    apiKeyHint: asNullableString(rerank.apiKeyHint),
    model: asNullableString(rerank.model),
    path: asNullableString(rerank.path),
  };
}

function parseSettings(value: unknown): KbSettings {
  const body = asRecord(value);
  return {
    embedding: parseEmbeddingSettings(body.embedding),
    rerank: parseRerankSettings(body.rerank),
  };
}

export async function fetchKbSettings(): Promise<KbSettings> {
  return parseSettings(await readJson(await authFetch("/api/kb/settings")));
}

export async function fetchKbModels(
  request: KbModelListRequest,
): Promise<KbModelListResult> {
  const response = await authFetch("/api/kb/settings/models", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(request),
  });
  const body = asRecord(await readJson(response));
  return {
    models: Array.isArray(body.models)
      ? body.models.filter((model): model is string => typeof model === "string")
      : [],
    message: asNullableString(body.message),
  };
}

export async function probeKbRerank(
  input: KbRerankProbeInput,
): Promise<KbProbeResult> {
  const response = await authFetch("/api/kb/settings/probe/rerank", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(input),
  });
  const body = asRecord(await readJson(response));
  return { embedding: null, rerank: parseRerankProbe(body.rerank) };
}

/** Saves rerank only; embedding settings are preserved server-side. */
export async function saveKbRerank(input: {
  enabled: boolean;
  baseUrl?: string;
  apiKey?: string;
  model?: string;
  path?: string;
}): Promise<KbSettings> {
  const response = await authFetch("/api/kb/settings/rerank", {
    method: "PUT",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(input),
  });
  return parseSettings(await readJson(response));
}

export async function setKbRerankEnabled(enabled: boolean): Promise<KbSettings> {
  return saveKbRerank({ enabled });
}

export async function resetKbEmbedding(): Promise<KbSettings> {
  return parseSettings(
    await readJson(await authFetch("/api/kb/settings/embedding", { method: "DELETE" })),
  );
}

export async function resetKbRerank(): Promise<KbSettings> {
  return parseSettings(
    await readJson(await authFetch("/api/kb/settings/rerank", { method: "DELETE" })),
  );
}

export async function saveKbSettings(input: KbSettingsInput): Promise<KbSettings> {
  const response = await authFetch("/api/kb/settings", {
    method: "PUT",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(input),
  });
  return parseSettings(await readJson(response));
}

export async function probeKbSettings(input: KbSettingsInput): Promise<KbProbeResult> {
  const response = await authFetch("/api/kb/settings/probe", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(input),
  });
  const body = asRecord(await readJson(response));
  return {
    embedding: parseEmbeddingProbe(body.embedding),
    rerank: parseRerankProbe(body.rerank),
  };
}

function parseEmbeddingProbe(value: unknown) {
  if (value == null) return null;
  const probe = asRecord(value);
  return {
    ok: probe.ok === true,
    dimension: asNullableNumber(probe.dimension),
    latencyMs: asNumber(probe.latencyMs),
    error: asNullableString(probe.error),
  };
}

function parseRerankProbe(value: unknown) {
  if (value == null) return null;
  const probe = asRecord(value);
  return {
    ok: probe.ok === true,
    latencyMs: asNumber(probe.latencyMs),
    score: asNullableNumber(probe.score),
    error: asNullableString(probe.error),
  };
}

/** Error message for upload/settings failures, including the server code. */
export function knowledgeError(cause: unknown): string {
  if (cause instanceof ApiError) {
    return cause.code ? `${cause.message}（${cause.code}）` : cause.message;
  }
  return cause instanceof Error ? cause.message : "操作失败，请稍后重试";
}
