package com.agentcore.server.domain;

/** Result of a successful login or refresh: short-lived access plus opaque refresh. */
public record AuthResult(AccessToken accessToken, String refreshToken) {}
