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
  kbId: string;
  chunkCount: number;
  /** Pages recognized by OCR in the last ingestion; 0 = text layer only. */
  ocrPages: number;
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

export interface KbKnowledgeBase {
  id: string;
  name: string;
  isDefault: boolean;
  documentCount: number;
}

export interface KbDocumentPage {
  items: KbDocument[];
  usage: KbUsage;
}

export interface KbPassage {
  seq: number;
  page: number | null;
  charCount: number;
  content: string;
}

export interface KbPassagePage {
  items: KbPassage[];
  nextSeq: number | null;
}

export type KbEmbeddingSource = "user" | "system" | "none";
export type KbRerankSource = "user" | "system" | "none" | "disabled";

export interface KbEmbeddingSettings {
  source: KbEmbeddingSource;
  baseUrl: string | null;
  apiKeyHint: string | null;
  model: string | null;
  dimension: number | null;
  /** 1 means the provider cannot be trusted with batched responses. */
  batchSize: number;
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
  /** Omitted during discovery; the probe returns the endpoint's actual size. */
  dimension?: number;
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

export interface KbRetrievalTestResult {
  query: string;
  embeddingModel: string;
  dimension: number;
  embeddingLatencyMs: number;
  candidates: number;
  hits: number;
  reranked: boolean;
  rerankModel: string | null;
  rerankLatencyMs: number | null;
}

export type KbModelType = "embedding" | "rerank";

export interface KbModelListRequest {
  type: KbModelType;
  baseUrl: string;
  apiKey?: string;
}

export interface KbModelListResult {
  models: string[];
  message: string | null;
}

export interface KbRerankProbeInput {
  baseUrl: string;
  apiKey?: string;
  model: string;
  path?: string;
}

/** One hit in the retrieval debug view; mirrors the kb_search passage shape. */
export interface KbRetrievalPassage {
  passageId: string;
  documentId: string;
  seq: number;
  page: number | null;
  title: string;
  content: string;
  score: number;
  reranked: boolean;
  kbId: string;
  kbName: string;
}

/** Two orderings of one candidate pool: vector order and the effective configuration. */
export interface KbRetrievalQueryResult {
  query: string;
  reranked: boolean;
  rerankModel: string | null;
  current: KbRetrievalPassage[];
  vector: KbRetrievalPassage[];
}

export type KbEvalLabelState = "ok" | "rotten";

/** One "question → expected passage" label; rotten means the target changed after a rebuild. */
export interface KbEvalLabel {
  id: number;
  query: string;
  documentId: string;
  seq: number;
  title: string | null;
  page: number | null;
  state: KbEvalLabelState;
  createdAt: string;
}

export interface KbEvalLabelList {
  items: KbEvalLabel[];
  total: number;
  limit: number;
}

export interface KbEvalLabelInput {
  kbId: string;
  query: string;
  documentId: string;
  seq: number;
}

export interface KbEvalMetrics {
  recall5: number;
  mrr5: number;
  poolRecall10: number;
  baselineRecall5: number;
  baselineMrr5: number;
}

export type KbEvalItemStatus = "hit" | "miss" | "rotten" | "skipped";

export interface KbEvalItem {
  labelId: number;
  query: string;
  documentId: string;
  seq: number;
  title: string | null;
  page: number | null;
  status: KbEvalItemStatus;
  rankFinal: number | null;
  rankVector: number | null;
  scoreFinal: number | null;
}

export interface KbEvalIssue {
  labelId: number;
  query: string;
  reason: string;
}

export interface KbEvalRun {
  runId: number;
  complete: boolean;
  labelCount: number;
  executedCount: number;
  rottenCount: number;
  skippedCount: number;
  embeddingModel: string;
  embeddingDim: number;
  rerankModel: string | null;
  durationMs: number;
  metrics: KbEvalMetrics;
  items: KbEvalItem[];
  rotten: KbEvalIssue[];
  skipped: KbEvalIssue[];
}

export interface KbEvalRunSummary {
  runId: number;
  createdAt: string;
  complete: boolean;
  labelCount: number;
  executedCount: number;
  rottenCount: number;
  skippedCount: number;
  embeddingModel: string;
  embeddingDim: number;
  rerankModel: string | null;
  durationMs: number;
  metrics: KbEvalMetrics;
}

export interface KbEvalRunList {
  items: KbEvalRunSummary[];
}
