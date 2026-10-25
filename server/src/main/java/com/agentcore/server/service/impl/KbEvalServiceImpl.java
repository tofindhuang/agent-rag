package com.agentcore.server.service.impl;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.common.kb.KbDigests;
import com.agentcore.server.common.kb.KbEvalMetrics;
import com.agentcore.server.common.kb.KbEvalMetricsCalculator;
import com.agentcore.server.common.kb.KbEmbeddingConfig;
import com.agentcore.server.common.kb.KbIndexGuard;
import com.agentcore.server.common.kb.KbRetrievalCore;
import com.agentcore.server.common.kb.KbRetrievalCore.RankedHit;
import com.agentcore.server.common.vector.VectorHit;
import com.agentcore.server.config.KbProperties;
import com.agentcore.server.dao.KbDocumentDao;
import com.agentcore.server.dao.KbEvalItemDao;
import com.agentcore.server.dao.KbEvalLabelDao;
import com.agentcore.server.dao.KbEvalRunDao;
import com.agentcore.server.dao.KbPassageDao;
import com.agentcore.server.domain.KbEvalItem;
import com.agentcore.server.domain.KbEvalLabel;
import com.agentcore.server.domain.KbEvalRun;
import com.agentcore.server.domain.KbPassage;
import com.agentcore.server.dto.kb.KbEvalIssueView;
import com.agentcore.server.dto.kb.KbEvalItemView;
import com.agentcore.server.dto.kb.KbEvalRunListResponse;
import com.agentcore.server.dto.kb.KbEvalRunResponse;
import com.agentcore.server.dto.kb.KbEvalRunSummary;
import com.agentcore.server.service.KbEvalService;
import com.agentcore.server.service.KbKnowledgeBaseService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One synchronous run: prepare labels (rotten detection), retrieve each query
 * with bounded concurrency, compute the five metrics, then persist an
 * immutable summary and its items (ADR-0022). Network calls never run inside
 * the persistence transaction.
 */
@Service
public class KbEvalServiceImpl implements KbEvalService {

    private static final Logger log = LoggerFactory.getLogger(KbEvalServiceImpl.class);

    private static final int TOP_K = KbRetrievalCore.DEFAULT_TOP_K;
    private static final int SCALE = 4;

    private final KbIndexGuard indexGuard;
    private final KbRetrievalCore retrievalCore;
    private final KbKnowledgeBaseService knowledgeBaseService;
    private final KbEvalLabelDao labelDao;
    private final KbPassageDao passageDao;
    private final KbDocumentDao documentDao;
    private final KbEvalRunDao runDao;
    private final KbEvalItemDao itemDao;
    private final KbProperties properties;
    private final TransactionTemplate transactions;

    public KbEvalServiceImpl(
            KbIndexGuard indexGuard,
            KbRetrievalCore retrievalCore,
            KbKnowledgeBaseService knowledgeBaseService,
            KbEvalLabelDao labelDao,
            KbPassageDao passageDao,
            KbDocumentDao documentDao,
            KbEvalRunDao runDao,
            KbEvalItemDao itemDao,
            KbProperties properties,
            PlatformTransactionManager transactionManager) {
        this.indexGuard = indexGuard;
        this.retrievalCore = retrievalCore;
        this.knowledgeBaseService = knowledgeBaseService;
        this.labelDao = labelDao;
        this.passageDao = passageDao;
        this.documentDao = documentDao;
        this.runDao = runDao;
        this.itemDao = itemDao;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public KbEvalRunResponse run(long userId, String kbId) {
        long started = System.nanoTime();
        var kb = knowledgeBaseService.requireOwned(userId, kbId);
        indexGuard.requireIndexUsable(userId);
        KbEmbeddingConfig embedding = retrievalCore.resolveEmbedding(userId);
        indexGuard.requireFingerprint(userId, embedding);
        String rerankModel = retrievalCore.resolveRerank(userId)
                .map((config) -> config.model())
                .orElse(null);

        List<KbEvalLabel> labels = labelDao.listByKb(userId, kb.getId());
        if (labels.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "no evaluation labels");
        }
        Map<String, String> titles = documentTitles(userId, kb.getId());
        List<Work> work = new ArrayList<>(labels.size());
        for (KbEvalLabel label : labels) {
            KbPassage passage = passageDao.findByDocumentAndSeq(
                    label.getDocumentId(), label.getSeq());
            boolean fresh = passage != null
                    && passage.getContent() != null
                    && KbDigests.sha256(passage.getContent())
                            .equals(label.getPassageSha256());
            work.add(new Work(label, passage, fresh));
        }

        long deadline = started + Duration.ofMillis(properties.eval().budgetMs()).toNanos();
        Map<Long, Outcome> outcomes = execute(userId, kb.getId(), embedding, work, deadline);

        int executed = 0;
        int rotten = 0;
        int skipped = 0;
        List<KbEvalMetricsCalculator.Observation> observations = new ArrayList<>();
        for (Work entry : work) {
            Outcome outcome = outcomes.get(entry.label().getId());
            if (outcome == null || KbEvalItem.STATUS_SKIPPED.equals(outcome.item().getStatus())) {
                skipped++;
            } else if (KbEvalItem.STATUS_ROTTEN.equals(outcome.item().getStatus())) {
                rotten++;
            } else {
                executed++;
                observations.add(new KbEvalMetricsCalculator.Observation(
                        outcome.item().getRankFinal(), outcome.item().getRankVector()));
            }
        }
        if (executed == 0) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "no evaluable labels; re-label rotten passages or retry");
        }

        KbEvalMetrics metrics = round(
                KbEvalMetricsCalculator.compute(observations));
        int durationMs = (int) ((System.nanoTime() - started) / 1_000_000L);
        boolean complete = skipped == 0;

        KbEvalRun run = new KbEvalRun();
        run.setUserId(userId);
        run.setKbId(kb.getId());
        run.setComplete(complete);
        run.setLabelCount(labels.size());
        run.setExecutedCount(executed);
        run.setRottenCount(rotten);
        run.setSkippedCount(skipped);
        run.setEmbeddingModel(embedding.model());
        run.setEmbeddingDim(embedding.dimension());
        run.setRerankModel(rerankModel);
        run.setDurationMs(durationMs);
        run.setRecall5(metrics.recall5());
        run.setMrr5(metrics.mrr5());
        run.setPoolRecall10(metrics.poolRecall10());
        run.setBaselineRecall5(metrics.baselineRecall5());
        run.setBaselineMrr5(metrics.baselineMrr5());

        List<KbEvalItem> items = new ArrayList<>(work.size());
        for (Work entry : work) {
            items.add(outcomes.get(entry.label().getId()).item());
        }
        transactions.executeWithoutResult((status) -> {
            runDao.insertRun(run);
            for (KbEvalItem item : items) {
                item.setRunId(run.getId());
            }
            itemDao.insertBatch(items);
            runDao.prune(userId, kb.getId(), properties.eval().historyLimit());
        });

        log.info("evaluation run {} for user {} kb {}: recall5={} mrr5={} poolRecall10={}"
                        + " executed={} rotten={} skipped={} complete={}",
                run.getId(), userId, kb.getId(), metrics.recall5(), metrics.mrr5(),
                metrics.poolRecall10(), executed, rotten, skipped, complete);
        return toResponse(run, metrics, work, outcomes, titles);
    }

    @Override
    public KbEvalRunListResponse listRuns(long userId, String kbId, int limit) {
        knowledgeBaseService.requireOwned(userId, kbId);
        int capped = Math.min(Math.max(limit, 1), properties.eval().historyLimit());
        List<KbEvalRunSummary> items = runDao.listByKb(userId, kbId, capped).stream()
                .map(KbEvalServiceImpl::toSummary)
                .toList();
        return new KbEvalRunListResponse(items);
    }

    @Override
    public KbEvalRunResponse getRun(long userId, long runId) {
        KbEvalRun run = runDao.findOwned(runId, userId);
        if (run == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "evaluation run not found");
        }
        Map<String, String> titles = documentTitles(userId, run.getKbId());
        Map<Long, KbEvalLabel> labels = new HashMap<>();
        for (KbEvalLabel label : labelDao.listByKb(userId, run.getKbId())) {
            labels.put(label.getId(), label);
        }
        List<KbEvalItemView> items = new ArrayList<>();
        List<KbEvalIssueView> rotten = new ArrayList<>();
        List<KbEvalIssueView> skipped = new ArrayList<>();
        for (KbEvalItem item : itemDao.listByRun(runId)) {
            KbEvalLabel label = labels.get(item.getLabelId());
            if (label == null) {
                continue;
            }
            KbPassage passage =
                    passageDao.findByDocumentAndSeq(label.getDocumentId(), label.getSeq());
            items.add(new KbEvalItemView(
                    label.getId(),
                    label.getQuery(),
                    label.getDocumentId(),
                    label.getSeq(),
                    titles.get(label.getDocumentId()),
                    passage == null ? null : passage.getPage(),
                    item.getStatus(),
                    item.getRankFinal(),
                    item.getRankVector(),
                    item.getScoreFinal()));
            if (KbEvalItem.STATUS_ROTTEN.equals(item.getStatus())) {
                rotten.add(new KbEvalIssueView(label.getId(), label.getQuery(), rottenReason(passage)));
            } else if (KbEvalItem.STATUS_SKIPPED.equals(item.getStatus())) {
                skipped.add(new KbEvalIssueView(label.getId(), label.getQuery(), "budget exceeded"));
            }
        }
        return new KbEvalRunResponse(
                run.getId(),
                Boolean.TRUE.equals(run.getComplete()),
                value(run.getLabelCount()),
                value(run.getExecutedCount()),
                value(run.getRottenCount()),
                value(run.getSkippedCount()),
                run.getEmbeddingModel(),
                value(run.getEmbeddingDim()),
                run.getRerankModel(),
                value(run.getDurationMs()),
                new KbEvalMetrics(
                        value(run.getRecall5()),
                        value(run.getMrr5()),
                        value(run.getPoolRecall10()),
                        value(run.getBaselineRecall5()),
                        value(run.getBaselineMrr5())),
                items,
                rotten,
                skipped);
    }

    private Map<Long, Outcome> execute(
            long userId,
            String kbId,
            KbEmbeddingConfig embedding,
            List<Work> work,
            long deadline) {
        Map<Long, Outcome> outcomes = new HashMap<>();
        List<Work> runnable = new ArrayList<>();
        for (Work entry : work) {
            if (!entry.fresh()) {
                KbEvalItem item = baseItem(entry.label(), KbEvalItem.STATUS_ROTTEN);
                outcomes.put(entry.label().getId(), new Outcome(
                        item, rottenReason(entry.passage())));
            } else {
                runnable.add(entry);
            }
        }
        if (runnable.isEmpty()) {
            return outcomes;
        }

        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(properties.eval().concurrency(), runnable.size()),
                (runnableTask) -> {
                    Thread thread = new Thread(runnableTask, "kb-eval");
                    thread.setDaemon(true);
                    return thread;
                });
        List<Future<KbEvalItem>> futures = new ArrayList<>(runnable.size());
        try {
            for (Work entry : runnable) {
                futures.add(pool.submit(
                        () -> evaluate(userId, kbId, embedding, entry, deadline)));
            }
            for (int i = 0; i < runnable.size(); i++) {
                Work entry = runnable.get(i);
                long remainingMs = Math.max(0, deadline - System.nanoTime()) / 1_000_000L;
                if (remainingMs <= 0) {
                    futures.get(i).cancel(true);
                    outcomes.put(entry.label().getId(), skipped(entry.label()));
                    continue;
                }
                try {
                    KbEvalItem item = futures.get(i).get(remainingMs, TimeUnit.MILLISECONDS);
                    outcomes.put(
                            entry.label().getId(),
                            new Outcome(
                                    item,
                                    KbEvalItem.STATUS_SKIPPED.equals(item.getStatus())
                                            ? "budget exceeded" : null));
                } catch (TimeoutException e) {
                    futures.get(i).cancel(true);
                    outcomes.put(entry.label().getId(), skipped(entry.label()));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.INTERNAL, "evaluation interrupted", e);
        } catch (ExecutionException e) {
            throw evaluationFailure(e);
        } finally {
            pool.shutdownNow();
        }
        return outcomes;
    }

    private KbEvalItem evaluate(
            long userId,
            String kbId,
            KbEmbeddingConfig embedding,
            Work entry,
            long deadline) {
        Duration remaining = Duration.ofNanos(Math.max(1, deadline - System.nanoTime()));
        Duration budget = remaining.compareTo(KbRetrievalCore.SEARCH_BUDGET) < 0
                ? remaining
                : KbRetrievalCore.SEARCH_BUDGET;
        KbRetrievalCore.Outcome outcome;
        try {
            outcome = retrievalCore.retrieve(
                    userId, kbId, embedding, entry.label().getQuery(), TOP_K, budget);
        } catch (ApiException e) {
            // The per-query budget can expire exactly at the run deadline; that
            // is a skipped label, not a broken endpoint.
            if (e.code() == ErrorCode.KB_UNAVAILABLE
                    && e.getMessage() != null
                    && e.getMessage().contains("budget")) {
                return baseItem(entry.label(), KbEvalItem.STATUS_SKIPPED);
            }
            throw e;
        }
        Integer rankVector = rankInPool(outcome.pool(), entry.label());
        Integer rankFinal = rankInRanked(outcome.ranked(), entry.label());
        KbEvalItem item = baseItem(
                entry.label(),
                rankFinal == null ? KbEvalItem.STATUS_MISS : KbEvalItem.STATUS_HIT);
        item.setRankVector(rankVector);
        item.setRankFinal(rankFinal);
        if (rankFinal != null) {
            item.setScoreFinal(outcome.ranked().get(rankFinal - 1).score());
        }
        return item;
    }

    private static Integer rankInPool(List<VectorHit> pool, KbEvalLabel label) {
        for (int i = 0; i < pool.size(); i++) {
            VectorHit hit = pool.get(i);
            if (hit.payload() != null
                    && label.getDocumentId().equals(hit.payload().documentId())
                    && label.getSeq() == hit.payload().seq()) {
                return i + 1;
            }
        }
        return null;
    }

    private static Integer rankInRanked(List<RankedHit> ranked, KbEvalLabel label) {
        for (int i = 0; i < ranked.size(); i++) {
            VectorHit hit = ranked.get(i).hit();
            if (hit.payload() != null
                    && label.getDocumentId().equals(hit.payload().documentId())
                    && label.getSeq() == hit.payload().seq()) {
                return i + 1;
            }
        }
        return null;
    }

    private static KbEvalItem baseItem(KbEvalLabel label, String status) {
        KbEvalItem item = new KbEvalItem();
        item.setLabelId(label.getId());
        item.setStatus(status);
        return item;
    }

    private static Outcome skipped(KbEvalLabel label) {
        return new Outcome(baseItem(label, KbEvalItem.STATUS_SKIPPED), "budget exceeded");
    }

    private static String rottenReason(KbPassage passage) {
        if (passage == null) {
            return "passage missing";
        }
        return "passage text changed";
    }

    private static ApiException evaluationFailure(ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof ApiException apiException) {
            return apiException;
        }
        return new ApiException(ErrorCode.INTERNAL, "evaluation failed", cause);
    }

    private KbEvalRunResponse toResponse(
            KbEvalRun run,
            KbEvalMetrics metrics,
            List<Work> work,
            Map<Long, Outcome> outcomes,
            Map<String, String> titles) {
        List<KbEvalItemView> items = new ArrayList<>(work.size());
        List<KbEvalIssueView> rotten = new ArrayList<>();
        List<KbEvalIssueView> skipped = new ArrayList<>();
        for (Work entry : work) {
            Outcome outcome = outcomes.get(entry.label().getId());
            KbEvalItem item = outcome.item();
            items.add(new KbEvalItemView(
                    entry.label().getId(),
                    entry.label().getQuery(),
                    entry.label().getDocumentId(),
                    entry.label().getSeq(),
                    titles.get(entry.label().getDocumentId()),
                    entry.passage() == null ? null : entry.passage().getPage(),
                    item.getStatus(),
                    item.getRankFinal(),
                    item.getRankVector(),
                    item.getScoreFinal()));
            if (KbEvalItem.STATUS_ROTTEN.equals(item.getStatus())) {
                rotten.add(new KbEvalIssueView(
                        entry.label().getId(), entry.label().getQuery(), outcome.reason()));
            } else if (KbEvalItem.STATUS_SKIPPED.equals(item.getStatus())) {
                skipped.add(new KbEvalIssueView(
                        entry.label().getId(), entry.label().getQuery(), outcome.reason()));
            }
        }
        return new KbEvalRunResponse(
                run.getId(),
                Boolean.TRUE.equals(run.getComplete()),
                value(run.getLabelCount()),
                value(run.getExecutedCount()),
                value(run.getRottenCount()),
                value(run.getSkippedCount()),
                run.getEmbeddingModel(),
                value(run.getEmbeddingDim()),
                run.getRerankModel(),
                value(run.getDurationMs()),
                metrics,
                items,
                rotten,
                skipped);
    }

    private Map<String, String> documentTitles(long userId, String kbId) {
        Map<String, String> titles = new HashMap<>();
        for (var document : documentDao.listByUser(userId, kbId)) {
            titles.put(document.getId(), document.getTitle());
        }
        return titles;
    }

    private static KbEvalRunSummary toSummary(KbEvalRun run) {
        return new KbEvalRunSummary(
                run.getId(),
                toInstant(run.getCreatedAt()),
                Boolean.TRUE.equals(run.getComplete()),
                value(run.getLabelCount()),
                value(run.getExecutedCount()),
                value(run.getRottenCount()),
                value(run.getSkippedCount()),
                run.getEmbeddingModel(),
                value(run.getEmbeddingDim()),
                run.getRerankModel(),
                value(run.getDurationMs()),
                new KbEvalMetrics(
                        value(run.getRecall5()),
                        value(run.getMrr5()),
                        value(run.getPoolRecall10()),
                        value(run.getBaselineRecall5()),
                        value(run.getBaselineMrr5())));
    }

    private static KbEvalMetrics round(KbEvalMetrics metrics) {
        return new KbEvalMetrics(
                round(metrics.recall5()),
                round(metrics.mrr5()),
                round(metrics.poolRecall10()),
                round(metrics.baselineRecall5()),
                round(metrics.baselineMrr5()));
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(SCALE, RoundingMode.HALF_UP).doubleValue();
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private static double value(Double value) {
        return value == null ? 0.0 : value;
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private record Work(KbEvalLabel label, KbPassage passage, boolean fresh) {}

    private record Outcome(KbEvalItem item, String reason) {}
}
