import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";

/**
 * Deterministic local embedding/rerank double. It mirrors the OpenAI-compatible
 * shapes the Java KB clients expect, so ingestion and retrieval exercise the
 * real pipeline without depending on an external provider's latency or quota.
 */
export const DEFAULT_EMBEDDING_DIMENSION = 1024;

function fnv1a(token: string): number {
  let hash = 0x811c9dc5;
  for (let index = 0; index < token.length; index += 1) {
    hash ^= token.charCodeAt(index);
    hash = Math.imul(hash, 0x01000193) >>> 0;
  }
  return hash;
}

/**
 * Bag-of-ngrams hashed into a fixed number of dimensions. Shared substrings
 * land in the same dimensions, so related texts get a higher cosine than
 * unrelated ones; the result is stable across runs.
 */
export function embedText(text: string, dimension = DEFAULT_EMBEDDING_DIMENSION): number[] {
  const vector = new Float64Array(dimension);
  const chars = Array.from(text.toLowerCase().normalize("NFKC"));
  const add = (token: string, weight: number): void => {
    vector[fnv1a(token) % dimension] += weight;
  };
  for (let index = 0; index < chars.length; index += 1) {
    add(chars[index], 0.25);
    if (index + 1 < chars.length) add(chars[index] + chars[index + 1], 0.5);
    if (index + 2 < chars.length) add(chars[index] + chars[index + 1] + chars[index + 2], 1);
  }
  let norm = 0;
  for (const value of vector) norm += value * value;
  norm = Math.sqrt(norm);
  if (norm > 0) {
    for (let index = 0; index < vector.length; index += 1) vector[index] /= norm;
  }
  return Array.from(vector);
}

export interface RerankHit {
  index: number;
  score: number;
}

export function rerankScores(query: string, documents: string[], topN: number): RerankHit[] {
  const queryVector = embedText(query);
  const hits = documents.map((document, index) => {
    const vector = embedText(document);
    let score = 0;
    for (let position = 0; position < queryVector.length; position += 1) {
      score += queryVector[position] * vector[position];
    }
    return { index, score };
  });
  hits.sort((left, right) => right.score - left.score || left.index - right.index);
  return topN > 0 ? hits.slice(0, topN) : hits;
}

interface EmbeddingRequest {
  model?: string;
  input?: string | string[];
}

interface RerankRequest {
  model?: string;
  query?: string;
  documents?: string[];
  top_n?: number;
}

async function readJson<T>(request: IncomingMessage): Promise<T> {
  const chunks: Buffer[] = [];
  for await (const chunk of request) chunks.push(Buffer.from(chunk));
  if (chunks.length === 0) return {} as T;
  return JSON.parse(Buffer.concat(chunks).toString("utf8")) as T;
}

function sendJson(response: ServerResponse, status: number, body: unknown): void {
  const payload = JSON.stringify(body);
  response.writeHead(status, {
    "content-type": "application/json",
    "content-length": Buffer.byteLength(payload),
  });
  response.end(payload);
}

export interface FakeEmbeddingServerOptions {
  dimension?: number;
  port?: number;
  host?: string;
}

export function createFakeEmbeddingServer(options: FakeEmbeddingServerOptions = {}): Server {
  const dimension = options.dimension ?? DEFAULT_EMBEDDING_DIMENSION;
  return createServer((request, response) => {
    void (async () => {
      const url = request.url ?? "";
      try {
        if (request.method === "GET" && url.endsWith("/health")) {
          sendJson(response, 200, { status: "ok" });
          return;
        }
        if (request.method === "POST" && url.endsWith("/embeddings")) {
          const body = await readJson<EmbeddingRequest>(request);
          const inputs = Array.isArray(body.input)
            ? body.input
            : typeof body.input === "string"
              ? [body.input]
              : [];
          sendJson(response, 200, {
            object: "list",
            model: body.model ?? "demo-embedding",
            data: inputs.map((text, index) => ({
              object: "embedding",
              index,
              embedding: embedText(text, dimension),
            })),
            usage: { prompt_tokens: inputs.length, total_tokens: inputs.length },
          });
          return;
        }
        if (request.method === "POST" && url.endsWith("/rerank")) {
          const body = await readJson<RerankRequest>(request);
          const documents = body.documents ?? [];
          const hits = rerankScores(body.query ?? "", documents, body.top_n ?? 0);
          sendJson(response, 200, {
            model: body.model ?? "demo-rerank",
            results: hits.map((hit) => ({ index: hit.index, relevance_score: hit.score })),
          });
          return;
        }
        sendJson(response, 404, { error: { code: "NOT_FOUND", message: url } });
      } catch (error) {
        sendJson(response, 400, {
          error: { code: "BAD_REQUEST", message: error instanceof Error ? error.message : "bad request" },
        });
      }
    })();
  });
}

const isEntrypoint =
  process.argv[1] !== undefined && import.meta.url === new URL(`file://${process.argv[1]}`).href;

if (isEntrypoint) {
  const port = Number.parseInt(process.env.LOADTEST_EMBEDDING_PORT ?? "18099", 10);
  const dimension = Number.parseInt(
    process.env.LOADTEST_EMBEDDING_DIM ?? String(DEFAULT_EMBEDDING_DIMENSION),
    10,
  );
  const server = createFakeEmbeddingServer({ dimension });
  server.listen(port, "127.0.0.1", () => {
    console.log(`fake embedding/rerank listening on http://127.0.0.1:${port} (dim=${dimension})`);
  });
}
