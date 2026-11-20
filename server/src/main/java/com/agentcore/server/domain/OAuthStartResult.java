package com.agentcore.server.domain;

/** Result of starting an OAuth authorization request. */
public record OAuthStartResult(String authorizationUrl, String stateCookie) {}
