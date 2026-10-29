package com.agentcore.server.common.auth;

/** Credential endpoints that share the per-IP limit (FR-6). */
public final class AuthPaths {

    public static final String[] CREDENTIAL_POST = {
            "/api/auth/register",
            "/api/auth/login",
            "/api/auth/refresh"
    };

    /** Public GET endpoints that need no JWT. */
    public static final String[] PUBLIC_GET = {
            "/api/auth/oauth/config",
            "/api/auth/oauth/start",
            "/api/auth/oauth/callback"
    };

    /** OAuth endpoints that share the per-IP credential limit. */
    public static final String[] RATE_LIMIT_GET = {
            "/api/auth/oauth/start",
            "/api/auth/oauth/callback"
    };

    private AuthPaths() {
    }
}
