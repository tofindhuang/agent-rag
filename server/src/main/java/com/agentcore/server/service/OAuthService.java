package com.agentcore.server.service;

import com.agentcore.server.domain.AuthResult;
import com.agentcore.server.domain.OAuthStartResult;

/** OIDC login use cases (ADR-0027). Controllers depend on this interface. */
public interface OAuthService {

    /** Builds the provider redirect and the signed state cookie value. */
    OAuthStartResult startLogin();

    /**
     * Completes the callback: validates state, exchanges the code, verifies the
     * ID token, resolves/creates the local account and issues a refresh session.
     */
    AuthResult completeLogin(String code, String state, String stateCookie);
}
