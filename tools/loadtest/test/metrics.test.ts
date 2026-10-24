import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, readFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { appendJsonl, percentile, summarize } from "../src/metrics.js";

function range(count: number): number[] {
  return Array.from({ length: count }, (_, index) => index + 1);
}

test("percentile uses nearest-rank for p50/p95/p99", () => {
  const values = range(100);
  assert.equal(percentile(values, 50), 50);
  assert.equal(percentile(values, 95), 95);
  assert.equal(percentile(values, 99), 99);
  assert.equal(percentile(values, 100), 100);
});

test("percentile is order independent and null for empty input", () => {
  assert.equal(percentile([5, 1, 3, 2, 4], 50), 3);
  assert.equal(percentile([], 95), null);
});

test("summarize reports count, min, max and percentiles", () => {
  assert.deepEqual(summarize([10, 20, 30, 40]), {
    count: 4,
    min: 10,
    p50: 20,
    p95: 40,
    p99: 40,
    max: 40,
  });
  assert.equal(summarize([]).count, 0);
  assert.equal(summarize([]).p95, null);
});

test("appendJsonl writes one JSON object per line and roundtrips", async () => {
  const dir = await mkdtemp(join(tmpdir(), "loadtest-metrics-"));
  const file = join(dir, "metrics.jsonl");
  await appendJsonl(file, { turn: 1, firstTokenMs: 120 });
  await appendJsonl(file, { turn: 2, firstTokenMs: 180 });
  const lines = (await readFile(file, "utf8")).trim().split("\n");
  assert.equal(lines.length, 2);
  assert.deepEqual(JSON.parse(lines[0]), { turn: 1, firstTokenMs: 120 });
  assert.deepEqual(JSON.parse(lines[1]), { turn: 2, firstTokenMs: 180 });
});
