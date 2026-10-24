package com.agentcore.server.common.kb;

/** One rerank result: the input document index and its relevance score. */
public record KbRerankHit(int index, double score) {}
