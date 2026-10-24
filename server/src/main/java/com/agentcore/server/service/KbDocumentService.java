package com.agentcore.server.service;

import com.agentcore.server.domain.KbDocument;
import com.agentcore.server.domain.KbUsage;
import java.nio.file.Path;
import java.util.List;

/** Knowledge-base document lifecycle (FR-2). */
public interface KbDocumentService {

    /** Stores an uploaded file, inserts the queued row and publishes the task. */
    KbDocument uploadFile(long userId, String filename, Path source);

    /** Fetches an HTML URL, stores the snapshot and queues ingestion. */
    KbDocument uploadUrl(long userId, String url);

    List<KbDocument> list(long userId);

    /** Current document count/bytes and the configured per-user limits. */
    KbUsage usage(long userId);

    KbDocument detail(long userId, String documentId);

    /** Marks the document deleting and publishes the async delete task. */
    void delete(long userId, String documentId);

    /** Rebuilds one document with a new version; queues it for ingestion. */
    void reingest(long userId, String documentId);

    /** Queues every stale document for rebuild; returns the queued count. */
    int reingestAll(long userId);
}
