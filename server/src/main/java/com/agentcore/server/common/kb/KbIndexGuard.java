package com.agentcore.server.common.kb;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.dao.KbDocumentDao;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Single retrieval gate for index consistency (ADR-0022).
 *
 * <p>Two independent proofs are required before a query may run: no document
 * is stale, and every document that may have vectors can prove the embedding
 * model and dimension that produced them. A mismatch is a deployment or
 * ingestion error; answering with mixed vector spaces would silently degrade
 * retrieval, so fail fast with a recovery hint instead.
 */
@Component
public class KbIndexGuard {

    private static final Logger log = LoggerFactory.getLogger(KbIndexGuard.class);

    private final KbAvailability availability;
    private final KbDocumentDao documentDao;

    public KbIndexGuard(KbAvailability availability, KbDocumentDao documentDao) {
        this.availability = availability;
        this.documentDao = documentDao;
    }

    /** Knowledge base enabled and no stale document waiting for a rebuild. */
    public void requireIndexUsable(long userId) {
        availability.requireEnabled();
        if (documentDao.countStaleByUser(userId) > 0) {
            throw new ApiException(ErrorCode.KB_INDEX_STALE,
                    "the embedding model or dimension changed; rebuild the knowledge base"
                            + " with reingest-all before searching");
        }
    }

    /** Every document with vectors must match the currently effective config. */
    public void requireFingerprint(long userId, KbEmbeddingConfig embedding) {
        if (documentDao.countUnknownFingerprintByUser(userId) > 0) {
            String message = "some documents were ingested before embedding fingerprints"
                    + " existed; run reingest-all before searching";
            log.warn("unknown embedding fingerprint for user {}: {}", userId, message);
            throw new ApiException(ErrorCode.KB_INDEX_STALE, message);
        }
        List<String> mismatched = documentDao.findMismatchedModelsByUser(
                userId, embedding.model(), embedding.dimension());
        if (!mismatched.isEmpty()) {
            String message = "some documents were embedded with a different model or dimension ("
                    + String.join(", ", mismatched) + "); current configuration is "
                    + embedding.model() + " (" + embedding.dimension() + " dims);"
                    + " run reingest-all before searching";
            log.warn("embedding fingerprint mismatch for user {}: {}", userId, message);
            throw new ApiException(ErrorCode.KB_INDEX_STALE, message);
        }
    }
}
