package com.agentcore.server.common.oauth;

/** ID token and userinfo disagree about the stable subject. */
public class OidcClaimMismatchException extends OidcTokenInvalidException {

    public OidcClaimMismatchException(String message) {
        super(message);
    }
}
