package com.agentcore.server.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentcore.server.support.AbstractIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;

/**
 * The operations contract: Prometheus can scrape the low-cardinality counters
 * from the separate management port.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RateLimitMetricsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private RateLimitService rateLimitService;

    @LocalManagementPort
    private int managementPort;

    @Test
    void exposesRateLimitCountersOnTheManagementPort() throws Exception {
        rateLimitService.checkUserRpm(ThreadLocalRandom.current().nextLong(1_000_000L));

        HttpResponse<String> response = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()
                .send(
                        HttpRequest.newBuilder()
                                .uri(URI.create("http://127.0.0.1:" + managementPort + "/actuator/prometheus"))
                                .timeout(Duration.ofSeconds(10))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("agentcore_rate_limit_checks_total")
                .contains("scope=\"user-rpm\"");
    }
}
