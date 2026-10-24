package com.agentcore.server.service.impl;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.common.identity.ToolIdentity;
import com.agentcore.server.common.kb.KbAvailability;
import com.agentcore.server.common.kb.KbDimensionMismatchException;
import com.agentcore.server.common.kb.KbEmbeddingClient;
import com.agentcore.server.common.kb.KbEmbeddingConfig;
import com.agentcore.server.common.kb.KbEmbeddingSettings;
import com.agentcore.server.common.kb.KbIngestException;
import com.agentcore.server.common.kb.KbRerankClient;
import com.agentcore.server.common.kb.KbRerankConfig;
import com.agentcore.server.common.kb.KbRerankException;
import com.agentcore.server.common.kb.KbRerankHit;
import com.agentcore.server.common.kb.KbRerankResult;
import com.agentcore.server.common.kb.KbRerankSettings;
import com.agentcore.server.common.kb.KbStoreException;
import com.agentcore.server.common.vector.VectorHit;
import com.agentcore.server.common.vector.VectorStore;
import com.agentcore.server.domain.KbKnowledgeBase;
import com.agentcore.server.config.KbProperties;
import com.agentcore.server.dao.KbDocumentDao;
import com.agentcore.server.dao.KbKnowledgeBaseDao;
import com.agentcore.server.dao.KbSessionKnowledgeBaseDao;
import com.agentcore.server.dto.kb.KbSearchRequest;
import com.agentcore.server.dto.kb.KbSearchResponse;
import com.agentcore.server.service.KbService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Retrieval pipeline (FR-2): query embedding → Qdrant top-N with the user
 * filter → optional rerank → citation-shaped passages. Failures surface as
 * business errors instead of an empty list, so a broken index is never
 * mistaken for "no matching documents".
 */
@Service
public class KbServiceImpl implements KbService {

    private static final Logger log = LoggerFactory.getLogger(KbServiceImpl.class);

    private static final Duration SEARCH_BUDGET = Duration.ofSeconds(20);
    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 10;

    private final KbAvailability availability;
    private final KbDocumentDao documentDao;
    private final KbSessionKnowledgeBaseDao sessionKbDao;
    private final KbKnowledgeBaseDao knowledgeBaseDao;
    private final KbEmbeddingSettings embeddingSettings;
    private final KbEmbeddingClient embeddingClient;
    private final KbRerankSettings rerankSettings;
    private final KbRerankClient rerankClient;
    private final VectorStore vectorStore;
    private final KbProperties properties;

    public KbServiceImpl(
            KbAvailability availability,
            KbDocumentDao documentDao,
            KbSessionKnowledgeBaseDao sessionKbDao,
            KbKnowledgeBaseDao knowledgeBaseDao,
            KbEmbeddingSettings embeddingSettings,
            KbEmbeddingClient embeddingClient,
            KbRerankSettings rerankSettings,
            KbRerankClient rerankClient,
            VectorStore vectorStore,
            KbProperties properties) {
        this.availability = availability;
        this.documentDao = documentDao;
        this.sessionKbDao = sessionKbDao;
        this.knowledgeBaseDao = knowledgeBaseDao;
        this.embeddingSettings = embeddingSettings;
        this.embeddingClient = embeddingClient;
        this.rerankSettings = rerankSettings;
        this.rerankClient = rerankClient;
        this.vectorStore = vectorStore;
        this.properties = properties;
    }

    @Override
    public KbSearchResponse search(KbSearchRequest request, ToolIdentity identity) {
        availability.requireEnabled();
        long userId = parseUserId(identity);
        if (documentDao.countStaleByUser(userId) > 0) {
            throw new ApiException(ErrorCode.KB_INDEX_STALE,
                    "the embedding model or dimension changed; rebuild the knowledge base"
                            + " with reingest-all before searching");
        }
        String kbId = sessionKbDao.findKbId(identity.sessionId());
        KbKnowledgeBase kb = kbId == null ? null : knowledgeBaseDao.findOwned(kbId, userId);
        if (kb == null) {
            return new KbSearchResponse(List.of());
        }

        long deadline = System.nanoTime() + SEARCH_BUDGET.toNanos();
        int topK = normalizeTopK(request.topK());
        String query = request.query();

        KbEmbeddingConfig embedding = resolveEmbedding(userId);
        List<float[]> vectors;
        try {
            vectors = embeddingClient.embed(embedding, List.of(query), remaining(deadline));
        } catch (KbIngestException e) {
            throw mapEmbeddingFailure(e);
        }
        if (vectors.isEmpty() || vectors.get(0).length == 0) {
            throw new ApiException(ErrorCode.KB_UNAVAILABLE,
                    "embedding endpoint returned an empty query vector");
        }

        int candidates = Math.max(topK, properties.rerank().candidates());
        List<VectorHit> hits;
        try {
            hits = vectorStore.search(
                    userId, kbId, vectors.get(0), candidates, remaining(deadline));
        } catch (KbDimensionMismatchException e) {
            throw new ApiException(ErrorCode.KB_INDEX_STALE, e.getMessage(), e);
        } catch (KbStoreException e) {
            throw new ApiException(ErrorCode.KB_UNAVAILABLE, e.getMessage(), e);
        }

        List<VectorHit> own = new ArrayList<>(hits.size());
        for (VectorHit hit : hits) {
            if (hit.payload() == null || hit.payload().userId() != userId) {
                // Defense in depth: collections are per user, but a corrupted
                // payload must never leak another user's passage.
                log.warn("dropping cross-user KB hit for user {} (hit {})", userId, hit.id());
                continue;
            }
            own.add(hit);
        }
        if (own.isEmpty()) {
            return new KbSearchResponse(List.of());
        }

        Optional<KbRerankConfig> rerank;
        try {
            rerank = rerankSettings.resolve(userId);
        } catch (KbRerankException e) {
            throw mapRerankFailure(e);
        }
        if (rerank.isEmpty()) {
            return new KbSearchResponse(own.stream()
                    .limit(topK)
                    .map((hit) -> toPassage(hit, hit.score(), false, kb))
                    .toList());
        }

        KbRerankResult result;
        try {
            result = rerankClient.rerank(
                    rerank.get(),
                    query,
                    own.stream().map((hit) -> hit.payload().text()).toList(),
                    topK,
                    remaining(deadline));
        } catch (KbRerankException e) {
            throw mapRerankFailure(e);
        }
        List<KbSearchResponse.Passage> passages = new ArrayList<>(result.hits().size());
        for (KbRerankHit hit : result.hits()) {
            passages.add(toPassage(own.get(hit.index()), hit.score(), true, kb));
        }
        return new KbSearchResponse(passages);
    }

    private KbEmbeddingConfig resolveEmbedding(long userId) {
        try {
            return embeddingSettings.resolve(userId);
        } catch (KbIngestException e) {
            throw mapEmbeddingFailure(e);
        }
    }

    private static KbSearchResponse.Passage toPassage(
            VectorHit hit, double score, boolean reranked, KbKnowledgeBase kb) {
        return new KbSearchResponse.Passage(
                hit.id(),
                hit.payload().documentId(),
                hit.payload().seq(),
                hit.payload().page(),
                hit.payload().title(),
                hit.payload().text(),
                score,
                reranked,
                kb.getId(),
                kb.getName());
    }

    private static int normalizeTopK(Integer topK) {
        if (topK == null || topK <= 0) {
            return DEFAULT_TOP_K;
        }
        return Math.min(topK, MAX_TOP_K);
    }

    private static long parseUserId(ToolIdentity identity) {
        try {
            return Long.parseLong(identity.userId());
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "X-User-Id must be numeric");
        }
    }

    private static Duration remaining(long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            throw new ApiException(ErrorCode.KB_UNAVAILABLE,
                    "knowledge base retrieval exceeded the 20s budget");
        }
        return Duration.ofNanos(remaining);
    }

    private static ApiException mapEmbeddingFailure(KbIngestException e) {
        if (KbIngestException.EMBEDDING_DIMENSION_MISMATCH.equals(e.errorCode())
                || !e.retryable()) {
            return new ApiException(ErrorCode.KB_NOT_CONFIGURED, e.getMessage(), e);
        }
        return new ApiException(ErrorCode.KB_UNAVAILABLE, e.getMessage(), e);
    }

    private static ApiException mapRerankFailure(KbRerankException e) {
        if (e.retryable()) {
            return new ApiException(ErrorCode.KB_UNAVAILABLE, e.getMessage(), e);
        }
        return new ApiException(ErrorCode.KB_NOT_CONFIGURED, e.getMessage(), e);
    }
}
