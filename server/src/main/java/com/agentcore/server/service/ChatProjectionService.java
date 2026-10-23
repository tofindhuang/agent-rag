package com.agentcore.server.service;

import com.agentcore.server.domain.TurnKey;

/**
 * Writes the message/run projection from contract events (ADR-0006). Writes are
 * idempotent by {@code (session_id, turn_id, ordinal)}.
 */
public interface ChatProjectionService {

    /** Creates the run ledger row, pending user message, title and activity bump. */
    void startTurn(TurnKey turn, String userText);

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
            String status);

    void appendTool(TurnKey turn, int ordinal, String content);

    /** Settles the run; any settled run means the user message reached the gateway. */
    void settleRun(
            TurnKey turn,
            String contractStatus,
            String errorCode,
            String errorMessage,
            long tokensIn,
            long tokensOut);

    /** The gateway rejected or was unreachable before a run existed. */
    void failBeforeStream(TurnKey turn, String errorCode, String errorMessage);
}
