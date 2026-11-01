package com.agentcore.server.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentcore.server.config.RateLimitProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** Trusted-proxy resolution is pure request parsing; pin every branch (ADR-0030). */
class ClientIpResolverTest {

    private static ClientIpResolver resolver(String... proxies) {
        return new ClientIpResolver(new RateLimitProperties(
                true, 120, 100_000, 10, Duration.ofSeconds(60), List.of(proxies)));
    }

    private static MockHttpServletRequest request(String remoteAddr, String... forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRemoteAddr(remoteAddr);
        if (forwardedFor.length > 0) {
            request.addHeader("X-Forwarded-For", String.join(", ", forwardedFor));
        }
        return request;
    }

    @Test
    void keepsRemoteAddrWhenNoProxyIsTrusted() {
        assertThat(resolver().resolve(request("203.0.113.9", "198.51.100.4")))
                .isEqualTo("203.0.113.9");
    }

    @Test
    void keepsRemoteAddrWhenTheDirectPeerIsNotTrusted() {
        assertThat(resolver("10.0.0.0/8").resolve(request("203.0.113.9", "1.2.3.4")))
                .isEqualTo("203.0.113.9");
    }

    @Test
    void usesTheForwardedClientBehindATrustedProxy() {
        assertThat(resolver("10.0.0.0/8").resolve(request("10.1.2.3", "203.0.113.9")))
                .isEqualTo("203.0.113.9");
    }

    @Test
    void ignoresClientForgedEntriesOnTheLeft() {
        // The client can prepend anything; only the right-most untrusted hop counts.
        assertThat(resolver("10.0.0.0/8")
                        .resolve(request("10.1.2.3", "1.1.1.1", "203.0.113.9", "10.9.9.9")))
                .isEqualTo("203.0.113.9");
    }

    @Test
    void walksPastSeveralTrustedProxies() {
        assertThat(resolver("10.0.0.0/8", "192.168.0.0/16")
                        .resolve(request("10.1.2.3", "203.0.113.9", "192.168.5.5")))
                .isEqualTo("203.0.113.9");
    }

    @Test
    void fallsBackToRemoteAddrWhenTheHeaderHasNoLiteralAddress() {
        assertThat(resolver("10.0.0.0/8").resolve(request("10.1.2.3", "not-an-ip")))
                .isEqualTo("10.1.2.3");
    }

    @Test
    void respectsTheConfiguredPrefixLength() {
        assertThat(resolver("10.0.0.0/8").resolve(request("10.255.255.255", "203.0.113.9")))
                .isEqualTo("203.0.113.9");
        assertThat(resolver("10.1.0.0/16").resolve(request("10.2.0.1", "203.0.113.9")))
                .isEqualTo("10.2.0.1");
    }

    @Test
    void supportsFullAddressesAndIpv6() {
        assertThat(resolver("10.1.2.3").resolve(request("10.1.2.3", "203.0.113.9")))
                .isEqualTo("203.0.113.9");
        assertThat(resolver("2001:db8::/32").resolve(request("2001:db8::1", "203.0.113.9")))
                .isEqualTo("203.0.113.9");
    }

    @Test
    void rejectsInvalidProxyConfiguration() {
        assertThatThrownBy(() -> resolver("not-a-network"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver("10.0.0.0/33"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
