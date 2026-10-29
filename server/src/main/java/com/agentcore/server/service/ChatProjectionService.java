package com.agentcore.server.service;

import com.agentcore.server.domain.TurnKey;

/**
 * Writes the message/run projection from contract events (ADR-0006). Writes are
 * idempotent by {@code (session_id, turn_id, ordinal)}.
 */
public interface ChatProjectionService {

    /** Creates the run ledger row, pending user message, title and activity bump. */
    void startTurn(TurnKey turn, String userText, String requestId, String modelId);

    /** Records the gateway-generated run id echoed by {@code run_started}. */
    void gatewayAccepted(String runRowId, String gatewayRunId);

    void appendAssistant(
            TurnKey turn,
            int ordinal,
            String text,
            String thinking,
            String toolCallsJson,
            Integer seqInTurn,
            long tokensIn,
            long tokensOut,
            long cacheReadTokens,
            long cacheWriteTokens,
            String status);

    /** Stores a tool result; toolName and optional structured details are bounded to 8 KiB. */
    void appendTool(
            TurnKey turn, int ordinal, String content, String toolName, String detailsJson);

    /**
     * Records one successful compaction: the FR-7 metrics row always, and the
     * user-visible summary (ADR-0028) when the gateway sent one.
     */
    void recordCompaction(
            TurnKey turn,
            long userId,
            String requestId,
            String reason,
            Long tokensBefore,
            Long tokensAfter,
            String summary);

    /** Settles the run; any settled run means the user message reached the gateway. */
    void settleRun(
            TurnKey turn,
            String contractStatus,
            String errorCode,
            String errorMessage,
            long tokensIn,
            long tokensOut,
            long cacheReadTokens,
            long cacheWriteTokens);

    /** The gateway rejected or was unreachable before a run existed. */
    void failBeforeStream(TurnKey turn, String errorCode, String errorMessage);
}
