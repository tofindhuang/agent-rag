package com.agentcore.server.dao;

import com.agentcore.server.domain.KbDocument;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Persistence access for KB document metadata and the task outbox. */
@Mapper
public interface KbDocumentDao extends BaseMapper<KbDocument> {

    KbDocument findOwned(@Param("id") String id, @Param("userId") long userId);

    List<KbDocument> listByUser(@Param("userId") long userId);

    long countByUser(@Param("userId") long userId);

    long sumSizeByUser(@Param("userId") long userId);

    /** Outbox marker; only touches rows still queued or deleting. */
    int markEnqueued(
            @Param("id") String id,
            @Param("userId") long userId,
            @Param("enqueuedAt") LocalDateTime enqueuedAt);

    /** Queued/deleting → deleting, clearing the outbox marker for re-publish. */
    int markDeleting(@Param("id") String id, @Param("userId") long userId);

    /** Reconciler: queued rows whose last publish is missing or older than the cutoff. */
    List<KbDocument> findQueuedForDispatch(
            @Param("before") LocalDateTime before, @Param("limit") int limit);

    /** Reconciler: deleting rows whose last publish is missing or older than the cutoff. */
    List<KbDocument> findDeletingForDispatch(
            @Param("before") LocalDateTime before, @Param("limit") int limit);

    /** Reconciler: processing rows whose lease expired after a worker crash. */
    List<KbDocument> findExpiredProcessingForDispatch(
            @Param("now") LocalDateTime now,
            @Param("before") LocalDateTime before,
            @Param("limit") int limit);

    int deleteOwned(@Param("id") String id, @Param("userId") long userId);

    /**
     * Atomically claims a queued/stale/expired-processing row for this worker.
     * The lease prevents duplicate deliveries from processing concurrently.
     */
    int claimForProcessing(
            @Param("id") String id,
            @Param("userId") long userId,
            @Param("version") int version,
            @Param("leaseUntil") LocalDateTime leaseUntil,
            @Param("now") LocalDateTime now);

    int markReady(
            @Param("id") String id,
            @Param("userId") long userId,
            @Param("version") int version,
            @Param("chunkCount") int chunkCount);

    int markFailed(
            @Param("id") String id,
            @Param("userId") long userId,
            @Param("version") int version,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage);

    int markQueuedForRetry(
            @Param("id") String id,
            @Param("userId") long userId,
            @Param("version") int version,
            @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
            @Param("enqueuedAt") LocalDateTime enqueuedAt);

    List<KbDocument> findStaleByUser(@Param("userId") long userId);

    /** Reingest: increments version, clears the old attempt/lease state. */
    int requeue(@Param("id") String id, @Param("userId") long userId);
}
