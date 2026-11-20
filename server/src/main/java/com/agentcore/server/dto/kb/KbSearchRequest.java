package com.agentcore.server.dto.kb;

public record KbSearchRequest(String query, Integer topK) {}
