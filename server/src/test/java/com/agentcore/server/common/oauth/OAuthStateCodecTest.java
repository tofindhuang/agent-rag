package com.agentcore.server.common.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentcore.server.config.AuthProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class OAuthStateCodecTest {

    private static final String SECRET = "test-only-jwt-secret-that-is-long-enough-32+";

    private final OAuthStateCodec codec = new OAuthStateCodec(
            new AuthProperties(SECRET, null, null, false), new ObjectMapper());

    @Test
    void roundTripsTheStateAndLinkUser() {
        OAuthState state = new OAuthState(
                OAuthState.CURRENT_VERSION, "state-1", "nonce-1", "verifier-1",
                OAuthIntent.LINK, 42L, Instant.now().getEpochSecond());

        OAuthState decoded = codec.decode(codec.encode(state));

        assertThat(decoded).isEqualTo(state);
    }

    @Test
    void rejectsATamperedPayload() {
        OAuthState state = new OAuthState(
                OAuthState.CURRENT_VERSION, "state-1", "nonce-1", "verifier-1",
                OAuthIntent.LOGIN, null, Instant.now().getEpochSecond());
        String encoded = codec.encode(state);
        String tampered = "x" + encoded.substring(1);

        assertThatThrownBy(() -> codec.decode(tampered))
                .isInstanceOf(OAuthStateInvalidException.class);
    }

    @Test
    void rejectsAnExpiredState() {
        OAuthState expired = new OAuthState(
                OAuthState.CURRENT_VERSION, "state-1", "nonce-1", "verifier-1",
                OAuthIntent.LOGIN, null, Instant.now().minusSeconds(700).getEpochSecond());

        assertThatThrownBy(() -> codec.decode(codec.encode(expired)))
                .isInstanceOf(OAuthStateInvalidException.class);
    }

    @Test
    void rejectsAStateSignedWithAnotherSecret() {
        OAuthStateCodec other = new OAuthStateCodec(
                new AuthProperties("another-test-secret-that-is-long-enough-32+", null, null, false),
                new ObjectMapper());
        OAuthState state = new OAuthState(
                OAuthState.CURRENT_VERSION, "state-1", "nonce-1", "verifier-1",
                OAuthIntent.LOGIN, null, Instant.now().getEpochSecond());

        assertThatThrownBy(() -> other.decode(codec.encode(state)))
                .isInstanceOf(OAuthStateInvalidException.class);
    }
}
