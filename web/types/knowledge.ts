/** Knowledge-base page domain types (server DTOs in server/dto/kb). */

export type KbSourceType = "upload" | "url";

export type KbDocumentStatus =
  | "queued"
  | "processing"
  | "ready"
  | "failed"
  | "deleting"
  | "stale";

export interface KbDocument {
  id: string;
  title: string;
  sourceType: KbSourceType;
  sourceUrl: string | null;
  mime: string | null;
  sizeBytes: number;
  status: KbDocumentStatus;
  chunkCount: number;
  errorCode: string | null;
  errorMessage: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface KbUsage {
  documents: number;
  bytes: number;
  maxDocuments: number;
  maxBytes: number;
}

export interface KbDocumentPage {
  items: KbDocument[];
  usage: KbUsage;
}

export type KbEmbeddingSource = "user" | "system" | "none";
export type KbRerankSource = "user" | "system" | "none" | "disabled";

export interface KbEmbeddingSettings {
  source: KbEmbeddingSource;
  baseUrl: string | null;
  apiKeyHint: string | null;
  model: string | null;
  dimension: number | null;
  path: string | null;
}

export interface KbRerankSettings {
  source: KbRerankSource;
  enabled: boolean;
  baseUrl: string | null;
  apiKeyHint: string | null;
  model: string | null;
  path: string | null;
}

export interface KbSettings {
  embedding: KbEmbeddingSettings;
  rerank: KbRerankSettings;
}

export interface KbEmbeddingInput {
  baseUrl: string;
  /** undefined keeps the stored key; "" clears it. */
  apiKey?: string;
  model: string;
  dimension: number;
  path?: string;
}

export interface KbRerankInput {
  enabled: boolean;
  baseUrl?: string;
  apiKey?: string;
  model?: string;
  path?: string;
}

export interface KbSettingsInput {
  embedding: KbEmbeddingInput;
  rerank?: KbRerankInput;
}

export interface KbEmbeddingProbe {
  ok: boolean;
  dimension: number | null;
  latencyMs: number;
  error: string | null;
}

export interface KbRerankProbe {
  ok: boolean;
  latencyMs: number;
  score: number | null;
  error: string | null;
}

export interface KbProbeResult {
  embedding: KbEmbeddingProbe | null;
  rerank: KbRerankProbe | null;
}
