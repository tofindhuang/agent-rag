package com.agentcore.server.dto.kb;

import java.time.Instant;

/** Document metadata for the management UI; never exposes the original bytes. */
public record KbDocumentView(
        String id,
        String kbId,
        String title,
        String sourceType,
        String sourceUrl,
        String mime,
        long sizeBytes,
        String status,
        int chunkCount,
        int ocrPages,
        String errorCode,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt) {}
