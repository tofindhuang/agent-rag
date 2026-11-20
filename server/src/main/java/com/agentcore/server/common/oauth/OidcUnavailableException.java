package com.agentcore.server.common.oauth;

/** Provider/network failure; the login flow may retry or fall back to password login. */
public class OidcUnavailableException extends RuntimeException {

    public OidcUnavailableException(String message) {
        super(message);
    }

    public OidcUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
