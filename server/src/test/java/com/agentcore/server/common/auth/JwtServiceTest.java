package com.agentcore.server.common.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentcore.server.config.AuthProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-that-is-at-least-32-bytes";
    private static final String OTHER_SECRET = "another-unit-test-secret-at-least-32-bytes!";

    @Test
    void rejectsSecretShorterThan32Bytes() {
        assertThatThrownBy(() -> service("too-short", Duration.ofMinutes(15)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void issuedTokenRoundTripsToUserId() {
        JwtService service = service(SECRET, Duration.ofMinutes(15));

        AuthenticatedUser user = service.verify(service.issue(42L));

        assertThat(user.userId()).isEqualTo(42L);
        assertThat(service.accessTtlSeconds()).isEqualTo(900);
    }

    @Test
    void rejectsExpiredToken() {
        JwtService service = service(SECRET, Duration.ofMinutes(15));
        JwtService alreadyExpired = service(SECRET, Duration.ofSeconds(-5));

        assertThatThrownBy(() -> service.verify(alreadyExpired.issue(1L)))
                .isInstanceOf(JwtRejectedException.class);
    }

    @Test
    void rejectsTokenSignedWithAnotherSecret() {
        JwtService issuer = service(OTHER_SECRET, Duration.ofMinutes(15));
        JwtService verifier = service(SECRET, Duration.ofMinutes(15));

        assertThatThrownBy(() -> verifier.verify(issuer.issue(1L)))
                .isInstanceOf(JwtRejectedException.class);
    }

    @Test
    void rejectsGarbageToken() {
        JwtService service = service(SECRET, Duration.ofMinutes(15));

        assertThatThrownBy(() -> service.verify("not-a-jwt"))
                .isInstanceOf(JwtRejectedException.class);
    }

    private static JwtService service(String secret, Duration ttl) {
        return new JwtService(new AuthProperties(secret, ttl));
    }
}
