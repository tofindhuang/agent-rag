package com.agentcore.server.config;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.gateway.GatewayClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Fails startup when a reachable gateway speaks an incompatible contract
 * major version. If the gateway is not up yet, startup continues with a
 * warning so local development does not require a strict ordering.
 */
@Component
public class GatewayContractVerifier implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GatewayContractVerifier.class);
    private static final String SUPPORTED_MAJOR = "1";

    private final GatewayClient client;
    private final ObjectMapper objectMapper;

    public GatewayContractVerifier(GatewayClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        HttpRequest request = client.request("/health")
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();

        try {
            HttpResponse<String> response = client.send(request);
            if (response.statusCode() != 200) {
                log.warn("gateway health returned {}; contract version check skipped", response.statusCode());
                return;
            }
            JsonNode version = objectMapper.readTree(response.body()).path("contractVersion");
            String major = version.asText("").split("\\.")[0];
            if (!SUPPORTED_MAJOR.equals(major)) {
                throw new IllegalStateException(
                        "gateway contract version " + version.asText("<missing>")
                                + " is incompatible with server major " + SUPPORTED_MAJOR);
            }
            log.info("gateway contract v{} verified", version.asText());
        } catch (ApiException e) {
            log.warn("gateway unreachable at startup; contract version check skipped: {}", e.getMessage());
        } catch (JsonProcessingException e) {
            log.warn("gateway health body is not JSON; contract version check skipped: {}", e.getMessage());
        }
    }
}
