package com.agentcore.server.domain;

import com.agentcore.server.common.oauth.OAuthIntent;

/** Outcome of an OAuth callback: login issues a session; link does not. */
public record OAuthCallbackResult(OAuthIntent intent, AuthResult auth) {}
