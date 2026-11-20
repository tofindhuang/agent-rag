package com.agentcore.server.common.auth;

/** Authenticated end user resolved from a valid access token. */
public record AuthenticatedUser(long userId) {}
