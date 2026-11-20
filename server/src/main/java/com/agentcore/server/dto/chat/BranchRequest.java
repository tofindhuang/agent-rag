package com.agentcore.server.dto.chat;

/** Body of {@code POST /api/chat/sessions/{id}/branch} (ADR-0035). */
public record BranchRequest(Long messageId) {}
