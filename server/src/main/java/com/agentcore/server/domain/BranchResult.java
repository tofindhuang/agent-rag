package com.agentcore.server.domain;

/**
 * Outcome of "branch from here" (ADR-0035).
 *
 * @param editorText the user message text to prefill, or null when unavailable
 * @param hiddenCount rows hidden by the branch; they stay in the table
 */
public record BranchResult(String editorText, int hiddenCount) {}
