import { appendFile } from "node:fs/promises";

/** Nearest-rank percentile; null when there are no samples. */
export function percentile(values: number[], p: number): number | null {
  if (values.length === 0) return null;
  const sorted = [...values].sort((left, right) => left - right);
  const rank = Math.ceil((p / 100) * sorted.length);
  const index = Math.min(Math.max(rank - 1, 0), sorted.length - 1);
  return sorted[index];
}

export interface Summary {
  count: number;
  min: number | null;
  p50: number | null;
  p95: number | null;
  p99: number | null;
  max: number | null;
}

export function summarize(values: number[]): Summary {
  if (values.length === 0) {
    return { count: 0, min: null, p50: null, p95: null, p99: null, max: null };
  }
  const sorted = [...values].sort((left, right) => left - right);
  return {
    count: sorted.length,
    min: sorted[0],
    p50: percentile(sorted, 50),
    p95: percentile(sorted, 95),
    p99: percentile(sorted, 99),
    max: sorted[sorted.length - 1],
  };
}

/** Appends one JSON object per line; safe for concurrent single-process runs. */
export async function appendJsonl(file: string, record: unknown): Promise<void> {
  await appendFile(file, `${JSON.stringify(record)}\n`, "utf8");
}
