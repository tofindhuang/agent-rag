package com.agentcore.server.common.oauth;

/** The provider returned a token that failed validation; retrying is not useful. */
public class OidcTokenInvalidException extends RuntimeException {

    public OidcTokenInvalidException(String message) {
        super(message);
    }

    public OidcTokenInvalidException(String message, Throwable cause) {
        super(message, cause);
    }
}
