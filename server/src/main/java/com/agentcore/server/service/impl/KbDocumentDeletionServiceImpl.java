package com.agentcore.server.service.impl;

import com.agentcore.server.common.audit.AuditAction;
import com.agentcore.server.common.audit.AuditService;
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
    private final AuditService audit;
    private final ObjectStore objectStore;
    private final VectorStore vectorStore;

    public KbDocumentDeletionServiceImpl(
            KbDocumentDao documentDao, ObjectStore objectStore, VectorStore vectorStore,
            AuditService audit) {
        this.documentDao = documentDao;
        this.audit = audit;
        this.objectStore = objectStore;
        this.vectorStore = vectorStore;
    }

    @Override
    public void delete(long userId, String documentId) {
        audit.success(AuditAction.KB_DOCUMENT_DELETE, userId, "document", documentId);
        KbDocument document = documentDao.findOwned(documentId, userId);
        if (document == null || !KbDocument.STATUS_DELETING.equals(document.getStatus())) {
            return;
        }
        objectStore.delete(document.getOriginalKey());
        vectorStore.deleteByDocument(userId, documentId);
        documentDao.deleteOwned(documentId, userId);
    }
}
