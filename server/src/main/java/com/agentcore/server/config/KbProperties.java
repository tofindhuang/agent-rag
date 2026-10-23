package com.agentcore.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Knowledge-base limits and deployment-default embedding/rerank endpoints
 * (FR-2). User-level settings override these values; when neither exists the
 * KB reports {@code KB_NOT_CONFIGURED}.
 */
@ConfigurationProperties(prefix = "app.kb")
public record KbProperties(
        Boolean enabled,
        Boolean allowPrivateEndpoints,
        Integer maxDocumentsPerUser,
        Long maxBytesPerUser,
        Integer workerThreads,
        Embedding embedding,
        Rerank rerank) {

    public KbProperties {
        if (enabled == null) {
            enabled = true; // KB is opt-out: only KB_ENABLED=false disables it.
        }
        if (allowPrivateEndpoints == null) {
            allowPrivateEndpoints = false;
        }
        if (maxDocumentsPerUser == null || maxDocumentsPerUser <= 0) {
            maxDocumentsPerUser = 200;
        }
        if (maxBytesPerUser == null || maxBytesPerUser <= 0) {
            maxBytesPerUser = 1024L * 1024 * 1024;
        }
        if (workerThreads == null || workerThreads <= 0) {
            workerThreads = 3;
        }
        if (embedding == null) {
            embedding = Embedding.defaults();
        }
        if (rerank == null) {
            rerank = Rerank.defaults();
        }
    }

    /** Deployment-level embedding fallback (OpenAI-compatible endpoint). */
    public record Embedding(
            String baseUrl, String apiKey, String model, Integer dim, Integer batchSize, String path) {

        public Embedding {
            if (model == null || model.isBlank()) {
                model = "BAAI/bge-m3";
            }
            if (dim == null || dim <= 0) {
                dim = 1024;
            }
            if (batchSize == null || batchSize <= 0) {
                batchSize = 32;
            }
            if (path == null || path.isBlank()) {
                path = "/embeddings";
            }
        }

        static Embedding defaults() {
            return new Embedding(null, null, "BAAI/bge-m3", 1024, 32, "/embeddings");
        }

        public boolean configured() {
            return baseUrl != null && !baseUrl.isBlank();
        }
    }

    /** Deployment-level rerank fallback; candidate/top-n mirror ADR-0005. */
    public record Rerank(
            Boolean enabled,
            String baseUrl,
            String apiKey,
            String model,
            String path,
            Integer candidates,
            Integer topN) {

        public Rerank {
            if (enabled == null) {
                enabled = true;
            }
            if (model == null || model.isBlank()) {
                model = "BAAI/bge-reranker-v2-m3";
            }
            if (path == null || path.isBlank()) {
                path = "/rerank";
            }
            if (candidates == null || candidates <= 0) {
                candidates = 10;
            }
            if (topN == null || topN <= 0) {
                topN = 5;
            }
        }

        static Rerank defaults() {
            return new Rerank(true, null, null, "BAAI/bge-reranker-v2-m3", "/rerank", 10, 5);
        }

        public boolean configured() {
            return baseUrl != null && !baseUrl.isBlank();
        }
    }
}
