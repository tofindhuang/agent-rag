package com.agentcore.server.common.oauth;

/**
 * Short-lived OAuth transaction stored only in the signed state cookie.
 *
 * @param issuedAt epoch seconds; avoids a Jackson JavaTime dependency
 */
public record OAuthState(
        int version,
        String state,
        String nonce,
        String codeVerifier,
        OAuthIntent intent,
        Long userId,
        long issuedAt) {

    public static final int CURRENT_VERSION = 1;
}
