package com.agentcore.server.common.auth;

/** Credential endpoints that share the per-IP limit (FR-6). */
public final class AuthPaths {

    public static final String[] CREDENTIAL_POST = {
            "/api/auth/register",
            "/api/auth/login",
            "/api/auth/refresh"
    };

    private AuthPaths() {
    }
}
