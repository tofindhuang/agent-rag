package com.agentcore.server.common.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.config.GatewayProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpRequest;
import org.junit.jupiter.api.Test;

class GatewayClientTest {

    @Test
    void parsesTheContractErrorEnvelope() {
        GatewayClient client = client("http://127.0.0.1:1", null);

        GatewayClient.GatewayError error = client.errorBody(
                "{\"error\":{\"code\":\"USER_MISMATCH\",\"message\":\"session belongs to another user\"}}");

        assertThat(error.code()).isEqualTo("USER_MISMATCH");
        assertThat(error.message()).isEqualTo("session belongs to another user");
    }

    @Test
    void malformedErrorBodyYieldsEmptyFields() {
        GatewayClient client = client("http://127.0.0.1:1", null);

        GatewayClient.GatewayError error = client.errorBody("<html>not json</html>");

        assertThat(error.code()).isEmpty();
        assertThat(error.message()).isEmpty();
    }

    @Test
    void addsTheSharedTokenWhenConfigured() {
        GatewayClient client = client("http://127.0.0.1:1", "shared-token");
        HttpRequest request = client.request("/health").GET().build();
        assertThat(request.headers().firstValue("authorization"))
                .contains("Bearer shared-token");

        HttpRequest anonymous = client("http://127.0.0.1:1", null).request("/health").GET().build();
        assertThat(anonymous.headers().firstValue("authorization")).isEmpty();
    }

    @Test
    void mapsTransportFailuresToUpstreamUnavailable() {
        GatewayClient client = client("http://127.0.0.1:1", null);
        HttpRequest request = client.request("/health").timeout(java.time.Duration.ofMillis(500)).GET().build();

        assertThatThrownBy(() -> client.send(request))
                .isInstanceOfSatisfying(ApiException.class, (error) -> {
                    assertThat(error.code()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
                    assertThat(error.code().retryable()).isTrue();
                });
    }

    private static GatewayClient client(String baseUrl, String token) {
        return new GatewayClient(new GatewayProperties(baseUrl, token), new ObjectMapper());
    }
}
