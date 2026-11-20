import { createHash } from "node:crypto";
import { gunzipSync, gzipSync } from "node:zlib";
import type { GatewayConfig } from "./config.js";

export interface RestoredFiles {
  jsonl: Buffer;
  meta: Buffer;
}

/** Boundary for the Java-proxied session archive (ADR-0025). */
export interface ArchiveTransport {
  upload(sessionId: string, userId: string, jsonl: Buffer, meta: Buffer): Promise<void>;
  download(sessionId: string, userId: string): Promise<RestoredFiles>;
}

export class ArchiveError extends Error {
  constructor(
    message: string,
    readonly status?: number,
  ) {
    super(message);
  }
}

/**
 * Streams archive bytes through Java, which is the only service holding object
 * storage credentials. The JSONL is gzipped here so the transport stays opaque.
 */
export class HttpArchiveTransport implements ArchiveTransport {
  constructor(private readonly config: GatewayConfig) {}

  async upload(sessionId: string, userId: string, jsonl: Buffer, meta: Buffer): Promise<void> {
    const gz = gzipSync(jsonl);
    await this.put(sessionId, userId, "jsonl", gz, "application/gzip");
    await this.put(sessionId, userId, "meta", meta, "application/json");
  }

  async download(sessionId: string, userId: string): Promise<RestoredFiles> {
    return {
      jsonl: gunzipSync(await this.get(sessionId, userId, "jsonl")),
      meta: await this.get(sessionId, userId, "meta"),
    };
  }

  private async put(
    sessionId: string,
    userId: string,
    kind: "jsonl" | "meta",
    body: Buffer,
    contentType: string,
  ): Promise<void> {
    const sha = sha256(body);
    const response = await fetch(this.url(sessionId, kind), {
      method: "PUT",
      headers: {
        ...this.headers(userId),
        "content-type": contentType,
        "X-Sha256": sha,
      },
      body,
    });
    if (!response.ok) {
      throw new ArchiveError(
        `archive upload failed: ${response.status} ${await response.text()}`,
        response.status,
      );
    }
  }

  private async get(
    sessionId: string,
    userId: string,
    kind: "jsonl" | "meta",
  ): Promise<Buffer> {
    const response = await fetch(this.url(sessionId, kind), {
      method: "GET",
      headers: this.headers(userId),
    });
    if (!response.ok) {
      throw new ArchiveError(
        `archive download failed: ${response.status} ${await response.text()}`,
        response.status,
      );
    }
    const body = Buffer.from(await response.arrayBuffer());
    const expected = response.headers.get("x-sha256");
    if (expected && sha256(body) !== expected.toLowerCase()) {
      throw new ArchiveError("archive download failed sha256 verification");
    }
    return body;
  }

  private url(sessionId: string, kind: "jsonl" | "meta"): string {
    return `${this.config.javaApiUrl}/api/internal/session-archives/${encodeURIComponent(sessionId)}/${kind}`;
  }

  private headers(userId: string): Record<string, string> {
    const headers: Record<string, string> = { "X-User-Id": userId };
    if (this.config.apiToken) headers.Authorization = `Bearer ${this.config.apiToken}`;
    return headers;
  }
}

function sha256(value: Buffer): string {
  return createHash("sha256").update(value).digest("hex");
}
