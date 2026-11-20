package com.agentcore.server.common.messaging;

/**
 * RabbitMQ task payload shared by the ingest and delete workers. The MySQL
 * {@code kb_documents} row remains the source of truth; this message only
 * points at it.
 */
public record KbTaskMessage(String type, long userId, String documentId, int version) {

    public static final String INGEST = "ingest";
    public static final String DELETE = "delete";

    public KbTaskMessage {
        if (!INGEST.equals(type) && !DELETE.equals(type)) {
            throw new IllegalArgumentException("unsupported KB task type: " + type);
        }
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("documentId is required");
        }
    }

    public static KbTaskMessage ingest(long userId, String documentId, int version) {
        return new KbTaskMessage(INGEST, userId, documentId, version);
    }

    public static KbTaskMessage delete(long userId, String documentId, int version) {
        return new KbTaskMessage(DELETE, userId, documentId, version);
    }

    public boolean isDelete() {
        return DELETE.equals(type);
    }
}
