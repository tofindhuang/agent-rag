package com.agentcore.server.common.kb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.config.KbProperties;
import org.junit.jupiter.api.Test;

class KbUrlGuardTest {

    @Test
    void defaultsToPublicHttps() {
        KbUrlGuard guard = guard(false);

        assertThatThrownBy(() -> guard.validate("http://example.com/page"))
                .isInstanceOf(ApiException.class)
                .extracting((error) -> ((ApiException) error).code())
                .isEqualTo(ErrorCode.INVALID_REQUEST);

        assertThatThrownBy(() -> guard.validate("https://127.0.0.1/page"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> guard.validate("https://10.0.0.1/page"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void privateEndpointsOptInAlsoAllowsHttp() {
        KbUrlGuard guard = guard(true);

        assertThatCode(() -> guard.validate("http://localhost:8080/page"))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard.validate("http://192.168.1.10/page"))
                .doesNotThrowAnyException();
    }

    @Test
    void metadataAddressesStayBlockedEvenWhenPrivateEndpointsAreAllowed() {
        KbUrlGuard guard = guard(true);

        assertThatThrownBy(() -> guard.validate("http://169.254.169.254/latest/meta-data"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> guard.validate("http://100.100.100.200/latest/meta-data"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsMalformedAndCredentialBearingUrls() {
        KbUrlGuard guard = guard(true);

        assertThatThrownBy(() -> guard.validate("ftp://example.com/file"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> guard.validate("http://user:pass@localhost/file"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> guard.validate("not a url"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> guard.validate("x".repeat(2049)))
                .isInstanceOf(ApiException.class);
    }

    private static KbUrlGuard guard(boolean allowPrivate) {
        KbProperties properties = new KbProperties(
                true, allowPrivate, 200, 1L << 30, 3, null, null);
        return new KbUrlGuard(properties);
    }
}
