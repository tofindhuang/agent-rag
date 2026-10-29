package com.agentcore.server.common.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentcore.server.config.OAuthProperties;
import com.agentcore.server.support.FakeOidcProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OidcProviderClientTest {

    private FakeOidcProvider provider;
    private OAuthProperties properties;
    private OidcProviderClient client;

    @BeforeEach
    void setUp() {
        provider = new FakeOidcProvider();
        properties = new OAuthProperties(
                true,
                provider.issuer(),
                "client-id",
                "client-secret",
                provider.redirectUri(),
                "http://localhost:3000",
                "Fake provider",
                "openid email profile");
        client = new DefaultOidcProviderClient(properties, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        provider.close();
    }

    @Test
    void buildsAuthorizationUrlWithCodePkceAndState() {
        String url = client.buildAuthorizationUrl("state-1", "nonce-1", "challenge-1");

        assertThat(url).startsWith(provider.issuer() + "/authorize?");
        assertThat(url)
                .contains("response_type=code")
                .contains("client_id=client-id")
                .contains("redirect_uri=" + encode(provider.redirectUri()))
                .contains("scope=openid+email+profile")
                .contains("state=state-1")
                .contains("nonce=nonce-1")
                .contains("code_challenge=challenge-1")
                .contains("code_challenge_method=S256");
    }

    @Test
    void exchangesCodeAndVerifiesTheIdToken() {
        provider.nonce("nonce-1");

        OidcTokenResponse tokens = client.exchangeCode("code-1", "verifier-1");
        OidcProfile profile = client.verifyIdToken(tokens.idToken(), "nonce-1");

        assertThat(tokens.accessToken()).isEqualTo("fake-access-token");
        assertThat(tokens.idToken()).isNotBlank();
        assertThat(profile.issuer()).isEqualTo(provider.issuer());
        assertThat(profile.subject()).isEqualTo("user-1");
        assertThat(profile.email()).isEqualTo("user@example.com");
        assertThat(profile.emailVerified()).isTrue();
        assertThat(provider.lastTokenAuthMethod()).isEqualTo("client_secret_basic");
    }

    @Test
    void usesClientSecretPostWhenItIsTheOnlySupportedMethod() {
        provider.tokenEndpointAuthMethods(List.of("client_secret_post"));

        client.exchangeCode("code-1", "verifier-1");

        assertThat(provider.lastTokenAuthMethod()).isEqualTo("client_secret_post");
    }

    @Test
    void rejectsWhenTheProviderSupportsNoTokenAuthMethod() {
        provider.tokenEndpointAuthMethods(List.of());

        assertThatThrownBy(() -> client.exchangeCode("code-1", "verifier-1"))
                .isInstanceOf(OidcUnavailableException.class);
    }

    @Test
    void rejectsAnIdTokenSignedWithAnotherKey() {
        provider.useWrongKey(true);
        OidcTokenResponse tokens = client.exchangeCode("code-1", "verifier-1");

        assertThatThrownBy(() -> client.verifyIdToken(tokens.idToken(), "nonce"))
                .isInstanceOf(OidcTokenInvalidException.class);
    }

    @Test
    void rejectsAnIdTokenWithTheWrongIssuer() {
        provider.issuerClaim("https://evil.example");
        OidcTokenResponse tokens = client.exchangeCode("code-1", "verifier-1");

        assertThatThrownBy(() -> client.verifyIdToken(tokens.idToken(), "nonce"))
                .isInstanceOf(OidcTokenInvalidException.class);
    }

    @Test
    void rejectsAnIdTokenWithTheWrongAudience() {
        provider.audience("another-client");
        OidcTokenResponse tokens = client.exchangeCode("code-1", "verifier-1");

        assertThatThrownBy(() -> client.verifyIdToken(tokens.idToken(), "nonce"))
                .isInstanceOf(OidcTokenInvalidException.class);
    }

    @Test
    void rejectsAnIdTokenWithTheWrongNonce() {
        provider.nonce("expected-nonce");
        OidcTokenResponse tokens = client.exchangeCode("code-1", "verifier-1");

        assertThatThrownBy(() -> client.verifyIdToken(tokens.idToken(), "other-nonce"))
                .isInstanceOf(OidcTokenInvalidException.class);
    }

    @Test
    void rejectsAnExpiredIdToken() {
        provider.expirationOffsetSeconds(-120);
        OidcTokenResponse tokens = client.exchangeCode("code-1", "verifier-1");

        assertThatThrownBy(() -> client.verifyIdToken(tokens.idToken(), "nonce"))
                .isInstanceOf(OidcTokenInvalidException.class);
    }

    @Test
    void rejectsAnIdTokenWhoseEmailIsNotVerified() {
        provider.emailVerified(false);
        OidcTokenResponse tokens = client.exchangeCode("code-1", "verifier-1");

        assertThatThrownBy(() -> client.verifyIdToken(tokens.idToken(), "nonce"))
                .isInstanceOf(OidcTokenInvalidException.class);
    }

    @Test
    void fetchesUserInfoWhenTheIdTokenHasNoEmail() {
        provider.includeEmailInIdToken(false);
        OidcTokenResponse tokens = client.exchangeCode("code-1", "verifier-1");

        OidcProfile idTokenProfile = client.verifyIdToken(tokens.idToken(), "nonce");
        OidcProfile userInfoProfile = client.fetchUserInfo(tokens.accessToken(), "user-1");

        assertThat(idTokenProfile.email()).isNull();
        assertThat(idTokenProfile.emailVerified()).isFalse();
        assertThat(userInfoProfile.subject()).isEqualTo("user-1");
        assertThat(userInfoProfile.email()).isEqualTo("user@example.com");
        assertThat(userInfoProfile.emailVerified()).isTrue();
        assertThat(provider.userinfoRequests()).isEqualTo(1);
    }

    @Test
    void rejectsUserInfoWhoseSubjectDoesNotMatchTheIdToken() {
        OidcTokenResponse tokens = client.exchangeCode("code-1", "verifier-1");

        assertThatThrownBy(() -> client.fetchUserInfo(tokens.accessToken(), "another-user"))
                .isInstanceOf(OidcTokenInvalidException.class);
    }

    @Test
    void discoveryFailureIsReportedAsUnavailable() {
        provider.discoveryFailure(true);

        assertThatThrownBy(() -> client.buildAuthorizationUrl("state", "nonce", "challenge"))
                .isInstanceOf(OidcUnavailableException.class);
    }

    @Test
    void tokenEndpointFailureIsReportedAsUnavailable() {
        provider.tokenFailure(true);

        assertThatThrownBy(() -> client.exchangeCode("code-1", "verifier-1"))
                .isInstanceOf(OidcUnavailableException.class);
    }

    @Test
    void discoveryIsCached() {
        client.buildAuthorizationUrl("state", "nonce", "challenge");
        client.buildAuthorizationUrl("state", "nonce", "challenge");

        assertThat(provider.discoveryRequests()).isEqualTo(1);
    }

    @Test
    void jwksIsCachedAcrossVerifications() {
        OidcTokenResponse tokens = client.exchangeCode("code-1", "verifier-1");
        client.verifyIdToken(tokens.idToken(), "nonce");
        client.verifyIdToken(tokens.idToken(), "nonce");

        assertThat(provider.jwksRequests()).isEqualTo(1);
    }

    @Test
    void providerTimeoutIsReportedAsUnavailable() {
        provider.delayMs(500);
        OidcProviderClient slowClient = new DefaultOidcProviderClient(
                properties, new ObjectMapper(), Duration.ofMillis(100));

        assertThatThrownBy(() -> slowClient.buildAuthorizationUrl("state", "nonce", "challenge"))
                .isInstanceOf(OidcUnavailableException.class);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
