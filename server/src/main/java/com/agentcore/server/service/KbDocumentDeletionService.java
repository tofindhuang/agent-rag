package com.agentcore.server.service;

/** Async deletion worker use case: object → Qdrant → MySQL, idempotent. */
public interface KbDocumentDeletionService {

    void delete(long userId, String documentId);
}
