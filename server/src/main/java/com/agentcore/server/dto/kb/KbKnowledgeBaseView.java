package com.agentcore.server.dto.kb;

/** One knowledge base plus its document count for the management page. */
public record KbKnowledgeBaseView(
        String id, String name, boolean isDefault, long documentCount) {}
