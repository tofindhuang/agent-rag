package com.agentcore.server.dto.profile;

public record UpdateProfileFactRequest(
        String factTitle, String factText, String category, String collectionId) {}
