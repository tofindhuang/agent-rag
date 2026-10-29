package com.agentcore.server.common.oauth;

/** ID token/userinfo email is missing or not verified. */
public class OidcEmailUnverifiedException extends OidcTokenInvalidException {

    public OidcEmailUnverifiedException(String message) {
        super(message);
    }
}
