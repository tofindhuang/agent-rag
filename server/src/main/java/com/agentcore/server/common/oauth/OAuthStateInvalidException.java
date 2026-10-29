package com.agentcore.server.common.oauth;

/** OAuth state cookie failed signature, shape or expiry validation. */
public class OAuthStateInvalidException extends RuntimeException {

    public OAuthStateInvalidException(String message) {
        super(message);
    }

    public OAuthStateInvalidException(String message, Throwable cause) {
        super(message, cause);
    }
}
