package com.agentcore.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentcore.server.support.FakeGateway;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * HTTP-seam proof that history keeps working when Redis is really down. Uses
 * its own containers so the shared test context is never poisoned.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class RedisOutageIntegrationTest {

    private static final String JWT_SECRET = "test-only-jwt-secret-that-is-long-enough-32+";
    private static final String GATEWAY_API_TOKEN = "test-gateway-token";
    private static final String MODEL_SECRET_KEY =
            "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("agent_core")
            .withUsername("agent_core")
            .withPassword("agent_core");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    private static final FakeGateway GATEWAY = new FakeGateway();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> MYSQL.getJdbcUrl() + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.timeout", () -> "1s");
        registry.add("spring.data.redis.connect-timeout", () -> "1s");
        registry.add("app.auth.jwt-secret", () -> JWT_SECRET);
        registry.add("app.gateway.api-token", () -> GATEWAY_API_TOKEN);
        registry.add("app.gateway.base-url", GATEWAY::baseUrl);
        registry.add("app.models.secret-key", () -> MODEL_SECRET_KEY);
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void historyFallsBackToMysqlWhileRedisIsStopped() throws Exception {
        GATEWAY.setPromptMode("complete");
        String email = "outage-" + UUID.randomUUID() + "@example.com";
        register(email);
        String token = login(email);
        String sessionId = createSession(token);
        sendTurn(token, sessionId, "hi");

        REDIS.stop();
        try {
            String history = mockMvc.perform(get("/api/chat/sessions/" + sessionId + "/messages?limit=50")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertThat((List<?>) JsonPath.read(history, "$.items")).hasSize(2);
            assertThat((Boolean) JsonPath.read(history, "$.hasMore")).isFalse();
        } finally {
            REDIS.start();
        }
    }

    private void register(String email) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"correct-horse\"}"))
                .andExpect(status().isCreated());
    }

    private String login(String email) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"correct-horse\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private void sendTurn(String token, String sessionId, String text) throws Exception {
        MvcResult pending = mockMvc.perform(post("/api/chat/sessions/" + sessionId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"" + text + "\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(pending)).andExpect(status().isOk());
    }

    private String createSession(String token) throws Exception {
        String body = mockMvc.perform(post("/api/chat/sessions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.sessionId");
    }
}
