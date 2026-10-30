package com.agentcore.server.common.kb;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.common.vector.VectorHit;
import com.agentcore.server.common.vector.VectorStore;
import com.agentcore.server.config.KbProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Shared retrieval pipeline (ADR-0022): query embedding → candidate pool →
 * optional rerank. Callers own session/KB resolution, index guards and DTO
 * mapping, so the tool callback, the debug query and the evaluation runner
 * cannot drift in timeout, filtering or candidate-pool parameters.
 */
@Component
public class KbRetrievalCore {

    private static final Logger log = LoggerFactory.getLogger(KbRetrievalCore.class);

    public static final Duration SEARCH_BUDGET = Duration.ofSeconds(20);
    public static final int DEFAULT_TOP_K = 5;
    public static final int MAX_TOP_K = 10;
    public static final int MAX_QUERY_CHARS = 512;

    private final KbEmbeddingSettings embeddingSettings;
    private final KbEmbeddingClient embeddingClient;
    private final KbRerankSettings rerankSettings;
    private final KbRerankClient rerankClient;
    private final VectorStore vectorStore;
    private final KbScoreThresholdSettings thresholdSettings;
    private final KbProperties properties;

    public KbRetrievalCore(
            KbEmbeddingSettings embeddingSettings,
            KbEmbeddingClient embeddingClient,
            KbRerankSettings rerankSettings,
            KbRerankClient rerankClient,
            VectorStore vectorStore,
            KbScoreThresholdSettings thresholdSettings,
            KbProperties properties) {
        this.embeddingSettings = embeddingSettings;
        this.embeddingClient = embeddingClient;
        this.rerankSettings = rerankSettings;
        this.rerankClient = rerankClient;
        this.vectorStore = vectorStore;
        this.thresholdSettings = thresholdSettings;
        this.properties = properties;
    }

    /** One hit in the final ordering, already scored by rerank or by vector similarity. */
    public record RankedHit(VectorHit hit, double score, boolean reranked) {}

    /**
     * @param pool the filtered candidate pool, vector-ordered
     * @param ranked the final top-k; identical to the pool prefix when rerank is disabled.
     *     Empty when the refusal policy rejected the whole ordering (ADR-0029)
     * @param noAnswer true when retrieval succeeded but the best score stayed below the
     *     threshold that belongs to the ordering's score scale
     * @param maxScore highest score before any refusal, or {@code null} for an empty pool
     */
    public record Outcome(
            List<VectorHit> pool,
            List<RankedHit> ranked,
            boolean reranked,
            String rerankModel,
            boolean noAnswer,
            Double maxScore,
            Double threshold,
            String thresholdSource) {}

    public KbEmbeddingConfig resolveEmbedding(long userId) {
        try {
            return embeddingSettings.resolve(userId);
        } catch (KbIngestException e) {
            throw mapEmbeddingFailure(e);
        }
    }

    /** Rerank config snapshot for the run report; resolves the same config retrieval will use. */
    public Optional<KbRerankConfig> resolveRerank(long userId) {
        try {
            return rerankSettings.resolve(userId);
        } catch (KbRerankException e) {
            throw mapRerankFailure(e);
        }
    }

    public Outcome retrieve(
            long userId, String kbId, KbEmbeddingConfig embedding, String query, int topK) {
        return retrieve(userId, kbId, embedding, query, topK, SEARCH_BUDGET);
    }

    public Outcome retrieve(
            long userId,
            String kbId,
            KbEmbeddingConfig embedding,
            String query,
            int topK,
            Duration budget) {
        long deadline = System.nanoTime() + budget.toNanos();

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
            return new Outcome(List.of(), List.of(), false, null, false, null, null, null);
        }

        Optional<KbRerankConfig> rerank;
        try {
            rerank = rerankSettings.resolve(userId);
        } catch (KbRerankException e) {
            throw mapRerankFailure(e);
        }
        if (rerank.isEmpty()) {
            List<RankedHit> ranked = own.stream()
                    .limit(topK)
                    .map((hit) -> new RankedHit(hit, hit.score(), false))
                    .toList();
            return withRefusal(own, ranked, false, null, userId);
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
        List<RankedHit> ranked = new ArrayList<>(result.hits().size());
        for (KbRerankHit hit : result.hits()) {
            ranked.add(new RankedHit(own.get(hit.index()), hit.score(), true));
        }
        return withRefusal(own, List.copyOf(ranked), true, rerank.get().model(), userId);
    }

    /**
     * Applies the per-user refusal threshold to the final ordering. A refused
     * result keeps no passages: callers must not be able to forward rejected
     * content (ADR-0029).
     */
    private Outcome withRefusal(
            List<VectorHit> pool,
            List<RankedHit> ranked,
            boolean reranked,
            String rerankModel,
            long userId) {
        KbScoreThresholds thresholds =
                ranked.isEmpty() ? KbScoreThresholds.NONE : thresholdSettings.resolve(userId);
        KbRefusalPolicy.Verdict verdict = KbRefusalPolicy.evaluate(
                ranked.isEmpty() ? null : ranked.get(0).score(), reranked, thresholds);
        List<RankedHit> kept = verdict.noAnswer() ? List.of() : ranked;
        return new Outcome(
                pool,
                kept,
                reranked,
                rerankModel,
                verdict.noAnswer(),
                verdict.maxScore(),
                verdict.threshold(),
                verdict.source());
    }

    public static int normalizeTopK(Integer topK) {
        if (topK == null || topK <= 0) {
            return DEFAULT_TOP_K;
        }
        return Math.min(topK, MAX_TOP_K);
    }

    private static Duration remaining(long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            throw new ApiException(ErrorCode.KB_UNAVAILABLE,
                    "knowledge base retrieval exceeded the request budget");
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
