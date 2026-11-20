package com.agentcore.server.common.kb;

import java.util.List;

/** Rerank results in the endpoint's order plus the round-trip latency. */
public record KbRerankResult(List<KbRerankHit> hits, long latencyMs) {}
