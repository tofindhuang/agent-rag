package com.agentcore.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OAuthPropertiesTest {

    @Test
    void defaultsWhenDisabled() {
        OAuthProperties properties = OAuthProperties.defaults();

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.providerName()).isEqualTo("OIDC");
        assertThat(properties.scopes()).isEqualTo("openid email profile");
    }

    @Test
    void enabledRequiresIssuer() {
        assertThatThrownBy(() -> enabled(null, "client-id", "client-secret",
                "http://127.0.0.1:8080/callback", "http://localhost:3000", "openid email profile"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.oauth.issuer");
    }

    @Test
    void enabledRequiresClientId() {
        assertThatThrownBy(() -> enabled("http://127.0.0.1:8080", null, "client-secret",
                "http://127.0.0.1:8080/callback", "http://localhost:3000", "openid email profile"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.oauth.client-id");
    }

    @Test
    void enabledRequiresNonLocalhostHttps() {
        assertThatThrownBy(() -> enabled("http://127.0.0.1:8080", "client-id", "client-secret",
                "http://provider.example/callback", "http://localhost:3000", "openid email profile"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.oauth.redirect-uri");
    }

    @Test
    void enabledRequiresOpenidAndEmailScopes() {
        assertThatThrownBy(() -> enabled("http://127.0.0.1:8080", "client-id", "client-secret",
                "http://127.0.0.1:8080/callback", "http://localhost:3000", "openid"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.oauth.scopes");
    }

    @Test
    void errorMessagesNeverContainTheClientSecret() {
        assertThatThrownBy(() -> enabled(null, "client-id", "super-secret-client-secret",
                "http://127.0.0.1:8080/callback", "http://localhost:3000", "openid email profile"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("super-secret-client-secret");
    }

    private static OAuthProperties enabled(
            String issuer, String clientId, String clientSecret, String redirectUri,
            String webBaseUrl, String scopes) {
        return new OAuthProperties(true, issuer, clientId, clientSecret, redirectUri,
                webBaseUrl, "OIDC", scopes);
    }
}
