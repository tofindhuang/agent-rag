package com.agentcore.server.common.auth;

/** Raised when a token is malformed, unsigned, expired, or carries foreign claims. */
public class JwtRejectedException extends RuntimeException {

    public JwtRejectedException() {
        super("access token rejected");
    }

    public JwtRejectedException(Throwable cause) {
        super("access token rejected", cause);
    }
}
