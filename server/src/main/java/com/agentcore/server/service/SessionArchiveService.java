package com.agentcore.server.service;

import com.agentcore.server.common.storage.StoredObject;
import com.agentcore.server.domain.SessionArchive;
import java.io.InputStream;

/** Cold archive boundary for pi session files (ADR-0025). */
public interface SessionArchiveService {

    void storeJsonl(String sessionId, long userId, InputStream body, String sha256);

    void storeMeta(String sessionId, long userId, InputStream body, String sha256);

    StoredObject loadJsonl(String sessionId, long userId);

    StoredObject loadMeta(String sessionId, long userId);

    /** Idempotent deletion of both objects and the ledger row. */
    void delete(String sessionId, long userId);

    SessionArchive find(String sessionId);
}
