package com.agentcore.server.service;

import com.agentcore.server.domain.KbDocument;
import java.nio.file.Path;
import java.util.List;

/** Knowledge-base document lifecycle (FR-2). */
public interface KbDocumentService {

    /** Stores an uploaded file, inserts the queued row and publishes the task. */
    KbDocument uploadFile(long userId, String filename, Path source);

    /** Fetches an HTML URL, stores the snapshot and queues ingestion. */
    KbDocument uploadUrl(long userId, String url);

    List<KbDocument> list(long userId);

    KbDocument detail(long userId, String documentId);

    /** Marks the document deleting and publishes the async delete task. */
    void delete(long userId, String documentId);
}
