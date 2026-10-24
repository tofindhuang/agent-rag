import { test } from "node:test";
import assert from "node:assert/strict";

import { createFakeEmbeddingServer, embedText, rerankScores } from "../src/fake-embedding.js";

test("embedText is deterministic and returns a unit 1024-dim vector", () => {
  const first = embedText("用户的项目使用 MySQL 8");
  const second = embedText("用户的项目使用 MySQL 8");
  assert.equal(first.length, 1024);
  assert.deepEqual(first, second);
  const norm = Math.sqrt(first.reduce((sum, value) => sum + value * value, 0));
  assert.ok(Math.abs(norm - 1) < 1e-9);
});

test("related texts are more similar than unrelated ones", () => {
  const base = embedText("the project database is MySQL 8");
  const related = embedText("the project database is MySQL 9");
  const unrelated = embedText("weather forecast for tomorrow in Shanghai");

  const dot = (a: number[], b: number[]): number =>
    a.reduce((sum, value, index) => sum + value * b[index], 0);

  assert.ok(dot(base, related) > dot(base, unrelated));
});

test("rerankScores orders by cosine similarity and respects top_n", () => {
  const query = "database mysql";
  const documents = [
    "weather forecast for tomorrow",
    "database mysql version 8",
    "database mysql version 9",
  ];
  const scores = rerankScores(query, documents, 2);
  assert.equal(scores.length, 2);
  assert.ok(scores[0].score >= scores[1].score);
  for (const hit of scores) {
    assert.ok(hit.index === 1 || hit.index === 2, "top hits must be the database documents");
  }
});

test("server answers OpenAI-compatible embeddings and rerank requests", async () => {
  const server = createFakeEmbeddingServer({ dimension: 1024 });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  assert.ok(address && typeof address === "object");
  const baseUrl = `http://127.0.0.1:${address.port}`;

  try {
    const embeddingResponse = await fetch(`${baseUrl}/v1/embeddings`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ model: "demo-embedding", input: ["hello", "world"] }),
    });
    assert.equal(embeddingResponse.status, 200);
    const embeddingBody = (await embeddingResponse.json()) as {
      data: { index: number; embedding: number[] }[];
    };
    assert.equal(embeddingBody.data.length, 2);
    assert.deepEqual(embeddingBody.data.map((item) => item.index), [0, 1]);
    assert.equal(embeddingBody.data[0].embedding.length, 1024);

    const rerankResponse = await fetch(`${baseUrl}/v1/rerank`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        model: "demo-rerank",
        query: "hello",
        documents: ["hello world", "goodbye"],
        top_n: 1,
      }),
    });
    assert.equal(rerankResponse.status, 200);
    const rerankBody = (await rerankResponse.json()) as {
      results: { index: number; relevance_score: number }[];
    };
    assert.equal(rerankBody.results.length, 1);
    assert.equal(rerankBody.results[0].index, 0);
  } finally {
    await new Promise<void>((resolve, reject) =>
      server.close((error) => (error ? reject(error) : resolve())),
    );
  }
});
