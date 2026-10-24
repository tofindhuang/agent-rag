import { ApiError, toApiError } from "./errors";
import { authFetch } from "./http";
import type {
  KbDocument,
  KbDocumentPage,
  KbDocumentStatus,
  KbEmbeddingSettings,
  KbKnowledgeBase,
  KbModelListRequest,
  KbModelListResult,
  KbPassagePage,
  KbProbeResult,
  KbRerankProbeInput,
  KbRerankSettings,
  KbRetrievalTestResult,
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
    kbId: asString(document.kbId),
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

export async function fetchKnowledgeBases(): Promise<KbKnowledgeBase[]> {
  const body = asRecord(await readJson(await authFetch("/api/kb/knowledge-bases")));
  if (!Array.isArray(body.items)) return [];
  return body.items.map((raw) => {
    const kb = asRecord(raw);
    return {
      id: asString(kb.id),
      name: asString(kb.name),
      isDefault: kb.isDefault === true,
      documentCount: asNumber(kb.documentCount),
    };
  });
}

export async function createKnowledgeBase(name: string): Promise<KbKnowledgeBase> {
  const body = asRecord(
    await readJson(
      await authFetch("/api/kb/knowledge-bases", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ name }),
      }),
    ),
  );
  return {
    id: asString(body.id),
    name: asString(body.name),
    isDefault: body.isDefault === true,
    documentCount: asNumber(body.documentCount),
  };
}

export async function renameKnowledgeBase(id: string, name: string): Promise<void> {
  const response = await authFetch(`/api/kb/knowledge-bases/${encodeURIComponent(id)}`, {
    method: "PATCH",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ name }),
  });
  if (!response.ok) throw await toApiError(response);
}

export async function deleteKnowledgeBase(id: string): Promise<void> {
  const response = await authFetch(`/api/kb/knowledge-bases/${encodeURIComponent(id)}`, {
    method: "DELETE",
  });
  if (!response.ok) throw await toApiError(response);
}

export async function fetchKnowledgeDocuments(kbId?: string): Promise<KbDocumentPage> {
  const params = new URLSearchParams();
  if (kbId) params.set("kbId", kbId);
  const body = asRecord(
    await readJson(
      await authFetch(`/api/kb/documents${params.size > 0 ? `?${params.toString()}` : ""}`),
    ),
  );
  const items = Array.isArray(body.items) ? body.items.map(parseDocument) : [];
  return { items, usage: parseUsage(body.usage) };
}

export async function fetchKbPassages(
  documentId: string,
  afterSeq = 0,
  limit = 20,
): Promise<KbPassagePage> {
  const params = new URLSearchParams({
    afterSeq: String(afterSeq),
    limit: String(limit),
  });
  const body = asRecord(
    await readJson(
      await authFetch(
        `/api/kb/documents/${encodeURIComponent(documentId)}/passages?${params.toString()}`,
      ),
    ),
  );
  const items = Array.isArray(body.items)
    ? body.items.map((raw) => {
        const passage = asRecord(raw);
        return {
          seq: asNumber(passage.seq),
          page: asNullableNumber(passage.page),
          charCount: asNumber(passage.charCount),
          content: asString(passage.content),
        };
      })
    : [];
  return {
    items,
    nextSeq: asNullableNumber(body.nextSeq),
  };
}

export async function uploadKnowledgeFile(file: File, kbId?: string): Promise<void> {
  const form = new FormData();
  form.append("file", file);
  if (kbId) form.append("kbId", kbId);
  const response = await authFetch("/api/kb/documents", { method: "POST", body: form });
  if (!response.ok) throw await toApiError(response);
}

export async function uploadKnowledgeUrl(url: string, kbId?: string): Promise<void> {
  const params = new URLSearchParams({ url });
  if (kbId) params.set("kbId", kbId);
  const response = await authFetch("/api/kb/documents", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: params.toString(),
  });
  if (!response.ok) throw await toApiError(response);
}

export async function reingestKnowledgeDocument(id: string): Promise<void> {
  const response = await authFetch(`/api/kb/documents/${encodeURIComponent(id)}/reingest`, {
    method: "POST",
  });
  if (!response.ok) throw await toApiError(response);
}

export async function reingestAllKnowledgeDocuments(kbId?: string): Promise<void> {
  const params = new URLSearchParams();
  if (kbId) params.set("kbId", kbId);
  const response = await authFetch(
    `/api/kb/documents/reingest-all${params.size > 0 ? `?${params.toString()}` : ""}`,
    { method: "POST" },
  );
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
    batchSize: asNumber(embedding.batchSize, 32),
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
export async function testKbRetrieval(): Promise<KbRetrievalTestResult> {
  const response = await authFetch("/api/kb/settings/test", {
    method: "POST",
    headers: { "content-type": "application/json" },
  });
  const body = asRecord(await readJson(response));
  return {
    query: asString(body.query),
    embeddingModel: asString(body.embeddingModel),
    dimension: asNumber(body.dimension),
    embeddingLatencyMs: asNumber(body.embeddingLatencyMs),
    candidates: asNumber(body.candidates),
    hits: asNumber(body.hits),
    reranked: body.reranked === true,
    rerankModel: asNullableString(body.rerankModel),
    rerankLatencyMs: asNullableNumber(body.rerankLatencyMs),
  };
}

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
