package com.agentcore.server.dto.kb;

/**
 * Per-label outcome: {@code status} is hit/miss/rotten/skipped; ranks are
 * 1-based or null when the expected passage was absent. No-answer labels carry
 * no target, so {@code documentId/seq/title/page} stay null.
 */
public record KbEvalItemView(
        long labelId,
        String query,
        String documentId,
        Integer seq,
        String title,
        Integer page,
        String status,
        Integer rankFinal,
        Integer rankVector,
        Double scoreFinal) {}
