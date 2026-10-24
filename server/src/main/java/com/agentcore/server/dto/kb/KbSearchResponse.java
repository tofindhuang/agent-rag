package com.agentcore.server.dto.kb;

import java.util.List;

/**
 * Response of the kb_search tool callback. The passage shape mirrors the
 * Java↔gateway contract v1 and is the unit of citation (see CONTEXT.md).
 */
public record KbSearchResponse(List<Passage> passages) {

    public record Passage(
            String passageId,
            String documentId,
            int seq,
            Integer page,
            String title,
            String content,
            double score,
            boolean reranked) {}
}
