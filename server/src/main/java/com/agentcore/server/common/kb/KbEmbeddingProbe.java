package com.agentcore.server.common.kb;

/** Result of a probe call: the endpoint's actual vector size and round-trip latency. */
public record KbEmbeddingProbe(int dimension, long latencyMs) {}
