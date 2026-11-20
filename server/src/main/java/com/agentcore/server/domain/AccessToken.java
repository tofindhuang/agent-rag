package com.agentcore.server.domain;

/** Newly issued access token for a successful login. */
public record AccessToken(String value, long expiresInSeconds) {}
