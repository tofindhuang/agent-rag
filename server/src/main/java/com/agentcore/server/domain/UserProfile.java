package com.agentcore.server.domain;

/** Read model for the authenticated user's own profile. */
public record UserProfile(long userId, String email) {}
