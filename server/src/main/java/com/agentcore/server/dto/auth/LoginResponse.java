package com.agentcore.server.dto.auth;

public record LoginResponse(String accessToken, String tokenType, long expiresIn) {}
