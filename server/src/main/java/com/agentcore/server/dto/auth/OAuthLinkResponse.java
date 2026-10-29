package com.agentcore.server.dto.auth;

/** The authorization URL the Web must navigate to for an explicit link. */
public record OAuthLinkResponse(String authorizationUrl) {}
