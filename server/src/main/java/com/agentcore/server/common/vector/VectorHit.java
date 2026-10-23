package com.agentcore.server.common.vector;

/** Search hit with its passage payload and similarity score. */
public record VectorHit(String id, double score, VectorPayload payload) {}
