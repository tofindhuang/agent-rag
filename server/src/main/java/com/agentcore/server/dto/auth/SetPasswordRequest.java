package com.agentcore.server.dto.auth;

/** First password for an OAuth-only account. */
public record SetPasswordRequest(String newPassword) {}
