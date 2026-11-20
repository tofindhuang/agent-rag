package com.agentcore.server.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OIDC login settings (ADR-0027). Disabled by default; when enabled, startup
 * validates shape only and never calls the provider.
 */
@ConfigurationProperties(prefix = "app.oauth")
public record OAuthProperties(
        boolean enabled,
        String issuer,
        String clientId,
        String clientSecret,
        String redirectUri,
        String webBaseUrl,
        String providerName,
        String scopes) {

    public OAuthProperties {
        providerName = blankToDefault(providerName, "OIDC");
        scopes = blankToDefault(scopes, "openid email profile");
        if (enabled) {
            issuer = require(issuer, "app.oauth.issuer");
            clientId = require(clientId, "app.oauth.client-id");
            clientSecret = require(clientSecret, "app.oauth.client-secret");
            redirectUri = require(redirectUri, "app.oauth.redirect-uri");
            webBaseUrl = require(webBaseUrl, "app.oauth.web-base-url");
            validateHttpUrl(issuer, "app.oauth.issuer");
            validateHttpUrl(redirectUri, "app.oauth.redirect-uri");
            validateHttpUrl(webBaseUrl, "app.oauth.web-base-url");
            requireScopes(scopes);
        }
    }

    public static OAuthProperties defaults() {
        return new OAuthProperties(false, null, null, null, null, null, null, null);
    }

    /** Redacts the client secret so accidental logs cannot leak it. */
    @Override
    public String toString() {
        return "OAuthProperties[enabled=" + enabled
                + ", issuer=" + issuer
                + ", clientId=" + clientId
                + ", clientSecret=<redacted>"
                + ", redirectUri=" + redirectUri
                + ", webBaseUrl=" + webBaseUrl
                + ", providerName=" + providerName
                + ", scopes=" + scopes
                + "]";
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String require(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " is required when OAuth is enabled");
        }
        return value.trim();
    }

    private static void requireScopes(String scopes) {
        boolean hasOpenId = false;
        boolean hasEmail = false;
        for (String scope : scopes.trim().split("\\s+")) {
            if ("openid".equals(scope)) {
                hasOpenId = true;
            } else if ("email".equals(scope)) {
                hasEmail = true;
            }
        }
        if (!hasOpenId || !hasEmail) {
            throw new IllegalStateException(
                    "app.oauth.scopes must include openid and email");
        }
    }

    private static void validateHttpUrl(String value, String key) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException ex) {
            throw new IllegalStateException(key + " must be an absolute http(s) URL", ex);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (uri.getHost() == null || (!"http".equals(scheme) && !"https".equals(scheme))) {
            throw new IllegalStateException(key + " must be an absolute http(s) URL");
        }
        if (!"https".equals(scheme) && !isLocalhost(uri.getHost())) {
            throw new IllegalStateException(key + " must use HTTPS outside localhost");
        }
    }

    private static boolean isLocalhost(String host) {
        String value = host.toLowerCase(Locale.ROOT);
        return "localhost".equals(value)
                || "127.0.0.1".equals(value)
                || "::1".equals(value)
                || "0:0:0:0:0:0:0:1".equals(value);
    }
}
