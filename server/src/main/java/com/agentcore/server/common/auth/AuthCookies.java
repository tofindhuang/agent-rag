package com.agentcore.server.common.auth;

import com.agentcore.server.config.AuthProperties;
import java.time.Duration;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/** Shared cookie shapes for refresh sessions and the OAuth state transaction. */
@Component
public class AuthCookies {

    public static final String REFRESH_COOKIE = "agentcore_refresh";
    public static final String OAUTH_STATE_COOKIE = "agentcore_oauth_state";
    private static final Duration OAUTH_STATE_TTL = Duration.ofMinutes(10);

    private final AuthProperties authProperties;

    public AuthCookies(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    public ResponseCookie refresh(String value) {
        return cookie(REFRESH_COOKIE, value, "/api/auth",
                authProperties.refreshTtl().toSeconds());
    }

    public ResponseCookie clearRefresh() {
        return cookie(REFRESH_COOKIE, "", "/api/auth", 0);
    }

    public ResponseCookie oauthState(String value) {
        return cookie(OAUTH_STATE_COOKIE, value, "/api/auth/oauth",
                OAUTH_STATE_TTL.toSeconds());
    }

    public ResponseCookie clearOAuthState() {
        return cookie(OAUTH_STATE_COOKIE, "", "/api/auth/oauth", 0);
    }

    private ResponseCookie cookie(String name, String value, String path, long maxAgeSeconds) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(authProperties.cookieSecure())
                .sameSite("Lax")
                .path(path)
                .maxAge(maxAgeSeconds)
                .build();
    }
}
