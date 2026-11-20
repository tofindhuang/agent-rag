package com.agentcore.server.common.oauth;

/** Tokens returned by the provider for one authorization-code exchange. */
public record OidcTokenResponse(String accessToken, String idToken) {}
