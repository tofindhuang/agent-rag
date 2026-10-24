package com.agentcore.server.dto.kb;

import java.util.List;

/** Document list plus the quota snapshot so the page needs one round trip. */
public record KbDocumentListResponse(
        List<KbDocumentView> items, Usage usage, Quota quota) {

    public record Usage(long documents, long bytes) {}

    public record Quota(int maxDocuments, long maxBytes) {}
}
