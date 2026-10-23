import assert from "node:assert/strict";
import { createServer, type IncomingHttpHeaders, type Server } from "node:http";
import { test } from "node:test";
import { EndpointBlockedError } from "./outbound.js";
import { probeProvider, ProbeError } from "./providers.js";

interface Stub {
  base: string;
  requests: Array<{ path: string; headers: IncomingHttpHeaders }>;
  close: () => Promise<void>;
}

async function startStub(
  respond: (req: { path: string; headers: IncomingHttpHeaders }) => {
    status: number;
    body: string;
  },
): Promise<Stub> {
  const requests: Stub["requests"] = [];
  const server: Server = createServer((req, res) => {
    const headers = req.headers;
    const path = req.url ?? "/";
    requests.push({ path, headers });
    const result = respond({ path, headers });
    res.writeHead(result.status, { "content-type": "application/json" });
    res.end(result.body);
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  if (address === null || typeof address === "string") throw new Error("stub failed to bind");
  return {
    base: `http://127.0.0.1:${address.port}`,
    requests,
    close: () =>
      new Promise<void>((resolve, reject) =>
        server.close((err) => (err ? reject(err) : resolve())),
      ),
  };
}

test("probes an OpenAI-compatible provider and maps its models", async () => {
  const stub = await startStub(() => ({
    status: 200,
    body: JSON.stringify({
      data: [
        { id: "deepseek-chat", name: "DeepSeek Chat" },
        { id: "deepseek-chat" },
        { id: "glm-4.6", display_name: "GLM 4.6" },
      ],
    }),
  }));
  try {
    const models = await probeProvider(
      { api: "openai-completions", baseUrl: `${stub.base}/v1/`, apiKey: "sk-test" },
      { allowPrivate: true },
    );

    assert.deepEqual(models, [
      { id: "deepseek-chat", name: "DeepSeek Chat" },
      { id: "glm-4.6", name: "GLM 4.6" },
    ]);
    assert.equal(stub.requests[0].path, "/v1/models");
    assert.equal(stub.requests[0].headers.authorization, "Bearer sk-test");
  } finally {
    await stub.close();
  }
});

test("probes an Anthropic-compatible provider with its headers", async () => {
  const stub = await startStub(() => ({
    status: 200,
    body: JSON.stringify({ data: [{ id: "claude-x", display_name: "Claude X" }] }),
  }));
  try {
    const models = await probeProvider(
      { api: "anthropic-messages", baseUrl: stub.base, apiKey: "sk-ant" },
      { allowPrivate: true },
    );

    assert.deepEqual(models, [{ id: "claude-x", name: "Claude X" }]);
    assert.equal(stub.requests[0].path, "/v1/models");
    assert.equal(stub.requests[0].headers["x-api-key"], "sk-ant");
    assert.equal(stub.requests[0].headers["anthropic-version"], "2023-06-01");
  } finally {
    await stub.close();
  }
});

test("accepts the alternative { models } list shape", async () => {
  const stub = await startStub(() => ({
    status: 200,
    body: JSON.stringify({ models: [{ id: "m1" }] }),
  }));
  try {
    const models = await probeProvider(
      { api: "openai-completions", baseUrl: stub.base, apiKey: "k" },
      { allowPrivate: true },
    );
    assert.deepEqual(models, [{ id: "m1", name: "m1" }]);
  } finally {
    await stub.close();
  }
});

test("maps 401 to PROVIDER_AUTH_FAILED", async () => {
  const stub = await startStub(() => ({ status: 401, body: "{}" }));
  try {
    await assert.rejects(
      () =>
        probeProvider(
          { api: "openai-completions", baseUrl: stub.base, apiKey: "bad" },
          { allowPrivate: true },
        ),
      (error: unknown) =>
        error instanceof ProbeError && error.code === "PROVIDER_AUTH_FAILED",
    );
  } finally {
    await stub.close();
  }
});

test("maps other non-2xx responses to PROVIDER_PROBE_FAILED", async () => {
  const stub = await startStub(() => ({ status: 500, body: "{}" }));
  try {
    await assert.rejects(
      () =>
        probeProvider(
          { api: "openai-completions", baseUrl: stub.base, apiKey: "k" },
          { allowPrivate: true },
        ),
      (error: unknown) =>
        error instanceof ProbeError && error.code === "PROVIDER_PROBE_FAILED",
    );
  } finally {
    await stub.close();
  }
});

test("maps invalid JSON to PROVIDER_PROBE_FAILED", async () => {
  const stub = await startStub(() => ({ status: 200, body: "not-json" }));
  try {
    await assert.rejects(
      () =>
        probeProvider(
          { api: "openai-completions", baseUrl: stub.base, apiKey: "k" },
          { allowPrivate: true },
        ),
      (error: unknown) =>
        error instanceof ProbeError && error.code === "PROVIDER_PROBE_FAILED",
    );
  } finally {
    await stub.close();
  }
});

test("maps a refused connection to PROVIDER_UNREACHABLE", async () => {
  const stub = await startStub(() => ({ status: 200, body: "{}" }));
  const base = stub.base;
  await stub.close();

  await assert.rejects(
    () => probeProvider({ api: "openai-completions", baseUrl: base, apiKey: "k" }, { allowPrivate: true }),
    (error: unknown) => error instanceof ProbeError && error.code === "PROVIDER_UNREACHABLE",
  );
});

test("maps DNS resolution failure to PROVIDER_UNREACHABLE", async () => {
  await assert.rejects(
    () =>
      probeProvider(
        { api: "openai-completions", baseUrl: "https://missing.example.com", apiKey: "k" },
        {
          allowPrivate: false,
          resolve: async () => {
            throw new Error("NXDOMAIN");
          },
        },
      ),
    (error: unknown) =>
      error instanceof ProbeError &&
      error.code === "PROVIDER_UNREACHABLE" &&
      error.retryable,
  );
});

test("rejects loopback providers by default without calling them", async () => {
  const stub = await startStub(() => ({ status: 200, body: "{}" }));
  try {
    await assert.rejects(
      () =>
        probeProvider(
          { api: "openai-completions", baseUrl: stub.base, apiKey: "k" },
          { allowPrivate: false },
        ),
      EndpointBlockedError,
    );
    assert.equal(stub.requests.length, 0);
  } finally {
    await stub.close();
  }
});

test("always rejects metadata endpoints, even with private access enabled", async () => {
  await assert.rejects(
    () =>
      probeProvider(
        {
          api: "openai-completions",
          baseUrl: "http://169.254.169.254/latest/meta-data",
          apiKey: "k",
        },
        { allowPrivate: true },
      ),
    EndpointBlockedError,
  );
});
