package com.agentcore.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentcore.server.support.AbstractIntegrationTest;
import com.agentcore.server.support.AuthTestCalls;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class ModelProviderIntegrationTest extends AbstractIntegrationTest {

    private static final String API_KEY = "sk-test-key-1234";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetProbe() {
        GATEWAY.setProbeMode("ok");
    }

    @Test
    void createsProviderMasksKeyAndDecryptsOnlyForProbe() throws Exception {
        String token = newUserToken();

        long providerId = createProvider(token, "SiliconFlow", API_KEY);

        mockMvc.perform(get("/api/models").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers.length()").value(1))
                .andExpect(jsonPath("$.providers[0].id").value(providerId))
                .andExpect(jsonPath("$.providers[0].name").value("SiliconFlow"))
                .andExpect(jsonPath("$.providers[0].apiKeyHint").value("••••1234"))
                .andExpect(jsonPath("$.providers[0].models[0].modelId").value("model-a"))
                .andExpect(jsonPath("$.providers[0].models[0].contextWindow").value(128000))
                .andExpect(jsonPath("$.providers[0].models[0].reasoning").value(true));

        String cipher = jdbc.queryForObject(
                "SELECT api_key_cipher FROM model_providers WHERE id = ?", String.class, providerId);
        assertThat(cipher).isNotNull().doesNotContain(API_KEY);

        mockMvc.perform(post("/api/models/probe")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"providerId\":" + providerId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models.length()").value(2))
                .andExpect(jsonPath("$.models[0].id").value("model-a"));

        assertThat(GATEWAY.lastProbeApiKey()).isEqualTo(API_KEY);
        assertThat(GATEWAY.lastProbeApi()).isEqualTo("openai-completions");
        assertThat(GATEWAY.lastProbeBaseUrl()).isEqualTo("https://api.example.com/v1");
    }

    @Test
    void keepsProvidersIsolatedBetweenUsers() throws Exception {
        String owner = newUserToken();
        String intruder = newUserToken();
        long providerId = createProvider(owner, "Private", API_KEY);

        mockMvc.perform(get("/api/models").header(HttpHeaders.AUTHORIZATION, "Bearer " + intruder))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers.length()").value(0));

        mockMvc.perform(patch("/api/models/providers/" + providerId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + intruder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"hijack\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/models/providers/" + providerId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + intruder))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/models/probe")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + intruder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"providerId\":" + providerId + "}"))
                .andExpect(status().isNotFound());

        long modelId = firstModelId(owner, providerId);
        mockMvc.perform(put("/api/models/default")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + intruder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelId\":" + modelId + "}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletingProviderClearsItsModelsAndDefault() throws Exception {
        String token = newUserToken();
        long providerId = createProvider(token, "Temp", API_KEY);
        long modelId = firstModelId(token, providerId);

        mockMvc.perform(put("/api/models/default")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelId\":" + modelId + "}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/models").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.defaultModelId").value(modelId));

        mockMvc.perform(delete("/api/models/providers/" + providerId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/models").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.providers.length()").value(0))
                .andExpect(jsonPath("$.defaultModelId").doesNotExist());

        Integer models = jdbc.queryForObject(
                "SELECT COUNT(*) FROM provider_models WHERE provider_id = ?",
                Integer.class,
                providerId);
        assertThat(models).isZero();
    }

    @Test
    void updatesProviderAndReplacesModels() throws Exception {
        String token = newUserToken();
        long providerId = createProvider(token, "Old", API_KEY);

        mockMvc.perform(patch("/api/models/providers/" + providerId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"New","models":[{"modelId":"model-b","displayName":"B"}]}
                                """))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/models").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.providers[0].name").value("New"))
                .andExpect(jsonPath("$.providers[0].models.length()").value(1))
                .andExpect(jsonPath("$.providers[0].models[0].modelId").value("model-b"))
                .andExpect(jsonPath("$.providers[0].apiKeyHint").value("••••1234"));
    }

    @Test
    void editingModelsPreservesAnUnchangedDefault() throws Exception {
        String token = newUserToken();
        long providerId = createProvider(token, "Stable", API_KEY);
        long modelId = firstModelId(token, providerId);

        mockMvc.perform(put("/api/models/default")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelId\":" + modelId + "}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(patch("/api/models/providers/" + providerId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"models":[{"modelId":"model-a","displayName":"Renamed A"}]}
                                """))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/models").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.defaultModelId").value(modelId))
                .andExpect(jsonPath("$.providers[0].models[0].displayName").value("Renamed A"));
    }

    @Test
    void listsProvidersOnTheirOwnEndpoint() throws Exception {
        String token = newUserToken();
        createProvider(token, "Listed", API_KEY);

        mockMvc.perform(get("/api/models/providers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Listed"))
                .andExpect(jsonPath("$[0].apiKeyHint").value("••••1234"));
    }

    @Test
    void mapsGatewayProbeErrors() throws Exception {
        String token = newUserToken();

        GATEWAY.setProbeMode("auth");
        mockMvc.perform(post("/api/models/probe")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"api":"openai-completions","baseUrl":"https://api.example.com/v1","apiKey":"bad"}
                                """))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("PROVIDER_AUTH_FAILED"));

        GATEWAY.setProbeMode("failed");
        mockMvc.perform(post("/api/models/probe")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"api":"openai-completions","baseUrl":"https://api.example.com/v1","apiKey":"k"}
                                """))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("PROVIDER_PROBE_FAILED"));

        GATEWAY.setProbeMode("unreachable");
        mockMvc.perform(post("/api/models/probe")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"api":"openai-completions","baseUrl":"https://api.example.com/v1","apiKey":"k"}
                                """))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("PROVIDER_UNREACHABLE"))
                .andExpect(jsonPath("$.error.retryable").value(true));
    }

    @Test
    void validatesProviderInput() throws Exception {
        String token = newUserToken();

        mockMvc.perform(post("/api/models/providers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"X","api":"unknown","baseUrl":"https://x","apiKey":"k","models":[]}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/models/providers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"X","api":"openai-completions","baseUrl":"https://x","apiKey":"","models":[]}
                                """))
                .andExpect(status().isBadRequest());
    }

    private long createProvider(String token, String name, String apiKey) throws Exception {
        String body = mockMvc.perform(post("/api/models/providers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","api":"openai-completions","baseUrl":"https://api.example.com/v1",
                                 "apiKey":"%s","models":[{"modelId":"model-a","displayName":"Model A",
                                 "contextWindow":128000,"reasoning":true}]}
                                """.formatted(name, apiKey)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private long firstModelId(String token, long providerId) throws Exception {
        String body = mockMvc.perform(get("/api/models")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        java.util.List<Number> ids = JsonPath.read(
                body,
                "$.providers[?(@.id == " + providerId + ")].models[0].id");
        return ids.get(0).longValue();
    }

    private String newUserToken() throws Exception {
        String email = "models-" + UUID.randomUUID() + "@example.com";
        AuthTestCalls.register(mockMvc, email, "correct-horse");
        return AuthTestCalls.login(mockMvc, email, "correct-horse");
    }
}
