package com.agentcore.server.dto.kb;

/**
 * PUT body for the refusal thresholds (ADR-0029). Both values are optional:
 * an omitted or null value disables refusal for that score scale.
 */
public record KbThresholdUpdateRequest(Double rerankMinScore, Double vectorMinScore) {}
