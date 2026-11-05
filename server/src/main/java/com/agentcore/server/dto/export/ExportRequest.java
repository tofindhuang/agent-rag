package com.agentcore.server.dto.export;

/**
 * Body of {@code POST /api/export/me}. {@code password} is required only when
 * the account has a local password (ADR-0034).
 */
public record ExportRequest(String password) {}
