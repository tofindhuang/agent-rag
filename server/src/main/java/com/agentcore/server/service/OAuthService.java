package com.agentcore.server.service;

import com.agentcore.server.common.oauth.OAuthIntent;
import com.agentcore.server.domain.AuthResult;
import com.agentcore.server.domain.OAuthCallbackResult;
import com.agentcore.server.domain.OAuthIdentityView;
import com.agentcore.server.domain.OAuthStartResult;
import java.util.List;

/** OIDC login/link use cases (ADR-0027). Controllers depend on this interface. */
public interface OAuthService {

    OAuthStartResult startLogin();

    OAuthStartResult startLink(long userId);

    /**
     * Completes either intent. Login returns a refresh session; link performs the
     * explicit binding and returns no new session.
     */
    OAuthCallbackResult complete(
            String code, String state, String stateCookie, String refreshToken);

    List<OAuthIdentityView> listIdentities(long userId);

    void unlink(long userId, long identityId);
}
