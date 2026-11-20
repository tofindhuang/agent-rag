package com.agentcore.server.dto.kb;

/**
 * Probe result. Provider failures are reported per section with {@code ok =
 * false} and a readable message; only local validation failures raise 400.
 */
public record KbProbeResponse(EmbeddingProbe embedding, RerankProbe rerank) {

    public record EmbeddingProbe(
            boolean ok, Integer dimension, long latencyMs, String error) {}

    public record RerankProbe(boolean ok, long latencyMs, Double score, String error) {}
}
