package com.agentcore.server.service.impl;

import com.agentcore.server.common.storage.ObjectStore;
import com.agentcore.server.common.vector.VectorStore;
import com.agentcore.server.dao.KbDocumentDao;
import com.agentcore.server.domain.KbDocument;
import com.agentcore.server.service.KbDocumentDeletionService;
import org.springframework.stereotype.Service;

/**
 * Deletion order is part of the storage contract (ADR-0013): remove the
 * original, then vectors, then the MySQL row. Every step is idempotent, so a
 * redelivered message is safe.
 */
@Service
public class KbDocumentDeletionServiceImpl implements KbDocumentDeletionService {

    private final KbDocumentDao documentDao;
    private final ObjectStore objectStore;
    private final VectorStore vectorStore;

    public KbDocumentDeletionServiceImpl(
            KbDocumentDao documentDao, ObjectStore objectStore, VectorStore vectorStore) {
        this.documentDao = documentDao;
        this.objectStore = objectStore;
        this.vectorStore = vectorStore;
    }

    @Override
    public void delete(long userId, String documentId) {
        KbDocument document = documentDao.findOwned(documentId, userId);
        if (document == null || !KbDocument.STATUS_DELETING.equals(document.getStatus())) {
            return;
        }
        objectStore.delete(document.getOriginalKey());
        vectorStore.deleteByDocument(userId, documentId);
        documentDao.deleteOwned(documentId, userId);
    }
}
