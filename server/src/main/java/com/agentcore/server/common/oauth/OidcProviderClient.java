package com.agentcore.server.common.oauth;

/** Provider-agnostic OIDC operations used by the OAuth login flow (ADR-0027). */
public interface OidcProviderClient {

    String buildAuthorizationUrl(String state, String nonce, String codeChallenge);

    OidcTokenResponse exchangeCode(String code, String codeVerifier);

    OidcProfile verifyIdToken(String idToken, String nonce);

    OidcProfile fetchUserInfo(String accessToken, String expectedSubject);

    String providerName();

    String issuer();
}
