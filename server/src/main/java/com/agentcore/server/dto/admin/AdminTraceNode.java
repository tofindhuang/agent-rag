package com.agentcore.server.dto.admin;

import java.time.Instant;
import java.util.List;

/**
 * One trace timeline node. Only fields relevant to {@code type} are populated;
 * content, thinking, code and stdout/stderr are never included.
 */
public record AdminTraceNode(
        String type,
        Instant at,
        boolean missing,
        String modelId,
        Integer seq,
        Long tokensIn,
        Long tokensOut,
        Long cacheReadTokens,
        Long cacheWriteTokens,
        List<AdminTraceToolCall> toolCalls,
        String name,
        Long passages,
        Double topScore,
        String execId,
        String status,
        Integer durationMs,
        Integer queueMs,
        Integer exitCode,
        String errorCode,
        String errorMessage) {

    public static AdminTraceNode runStarted(Instant at, String modelId) {
        return new AdminTraceNode("run_started", at, false, modelId, null, null, null,
                null, null, List.of(), null, null, null, null, null, null, null, null,
                null, null);
    }

    public static AdminTraceNode assistant(
            Instant at,
            Integer seq,
            Long tokensIn,
            Long tokensOut,
            Long cacheReadTokens,
            Long cacheWriteTokens,
            List<AdminTraceToolCall> toolCalls) {
        return new AdminTraceNode("assistant", at, false, null, seq, tokensIn, tokensOut,
                cacheReadTokens, cacheWriteTokens, toolCalls, null, null, null, null, null,
                null, null, null, null, null);
    }

    public static AdminTraceNode kbSearch(Instant at, long passages, Double topScore) {
        return new AdminTraceNode("tool", at, false, null, null, null, null, null, null,
                List.of(), "kb_search", passages, topScore, null, null, null, null, null,
                null, null);
    }

    public static AdminTraceNode runCode(
            Instant at,
            String execId,
            boolean missing,
            String status,
            Integer durationMs,
            Integer queueMs,
            Integer exitCode) {
        return new AdminTraceNode("tool", at, missing, null, null, null, null, null, null,
                List.of(), "run_code", null, null, execId, status, durationMs, queueMs,
                exitCode, null, null);
    }

    public static AdminTraceNode genericTool(Instant at, String name, boolean missing) {
        return new AdminTraceNode("tool", at, missing, null, null, null, null, null, null,
                List.of(), name, null, null, null, null, null, null, null, null, null);
    }

    public static AdminTraceNode runSettled(
            Instant at,
            boolean missing,
            String status,
            String errorCode,
            String errorMessage) {
        return new AdminTraceNode("run_settled", at, missing, null, null, null, null, null,
                null, List.of(), null, null, null, null, status, null, null, null,
                errorCode, errorMessage);
    }
}
