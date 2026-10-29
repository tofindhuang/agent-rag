package com.agentcore.server.dto.auth;

/** Public OAuth capability for the login/register pages. */
public record OAuthConfigResponse(boolean enabled, String providerName) {}
