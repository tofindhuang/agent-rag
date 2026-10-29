package com.agentcore.server.dto.auth;

public record MeResponse(long userId, String email, String role, boolean hasPassword) {}
