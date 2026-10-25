package com.agentcore.server.dto.admin;

/** kb_search call/hit aggregates; hitRate is null when there are no calls. */
public record RetrievalView(
        long calls,
        long hitCalls,
        Double hitRate,
        Double avgPassages,
        long failedCalls) {}
