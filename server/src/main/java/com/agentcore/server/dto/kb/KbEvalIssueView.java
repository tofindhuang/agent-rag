package com.agentcore.server.dto.kb;

/** Why a label was excluded from the metrics of this run. */
public record KbEvalIssueView(long labelId, String query, String reason) {}
