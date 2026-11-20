package com.agentcore.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Qdrant connection settings; collections are per user and created lazily. */
@ConfigurationProperties(prefix = "app.kb.qdrant")
public record KbQdrantProperties(String url, String apiKey, String collectionPrefix) {

    public String url() {
        return url == null || url.isBlank() ? "http://localhost:6333" : url;
    }

    public String collectionPrefix() {
        return collectionPrefix == null || collectionPrefix.isBlank()
                ? "kb_passages"
                : collectionPrefix;
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
