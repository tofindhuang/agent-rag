package com.agentcore.server.common.auth;

/** Extracts the token from an {@code Authorization: Bearer <token>} header. */
public final class BearerToken {

    private static final String PREFIX = "Bearer ";

    private BearerToken() {}

    /** Returns the token, or {@code null} when the header is absent or not a Bearer header. */
    public static String extract(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(PREFIX)) {
            return null;
        }
        return authorizationHeader.substring(PREFIX.length());
    }
}
