package com.agentcore.server.common.vector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.agentcore.server.config.KbProperties;
import com.agentcore.server.config.KbQdrantProperties;
import com.agentcore.server.dao.KbUserSettingsDao;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the dimension guard actually fails context startup (not just its unit
 * method) when a pre-existing collection has the wrong vector size.
 */
@Testcontainers(disabledWithoutDocker = true)
class QdrantDimensionStartupFailureTest {

    @Container
    static final GenericContainer<?> QDRANT = new GenericContainer<>("qdrant/qdrant:v1.14.1")
            .withExposedPorts(6333)
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(60)));

    @Test
    void contextRefusesToStartOnADimensionMismatch() throws Exception {
        String baseUrl = "http://" + QDRANT.getHost() + ":" + QDRANT.getMappedPort(6333);
        String collection = "kb_guard_" + UUID.randomUUID().toString().replace("-", "") + "_u42";
        String prefix = collection.substring(0, collection.length() - "_u42".length());
        HttpResponse<String> created = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/collections/" + collection))
                        .header("content-type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(
                                "{\"vectors\":{\"size\":4,\"distance\":\"Cosine\"}}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(created.statusCode()).isEqualTo(200);

        new ApplicationContextRunner()
                .withUserConfiguration(GuardConfiguration.class)
                .withPropertyValues(
                        "app.kb.enabled=true",
                        "app.kb.embedding.dim=1024",
                        "app.kb.qdrant.url=" + baseUrl,
                        "app.kb.qdrant.collection-prefix=" + prefix)
                .run((context) -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("has dimension 4")
                            .hasStackTraceContaining("expects 1024");
                });
    }

    @Configuration
    @EnableConfigurationProperties({KbProperties.class, KbQdrantProperties.class})
    static class GuardConfiguration {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        VectorStore vectorStore(KbQdrantProperties properties, ObjectMapper objectMapper) {
            return new QdrantVectorStore(properties, objectMapper);
        }

        @Bean
        KbUserSettingsDao settingsDao() {
            return mock(KbUserSettingsDao.class);
        }

        @Bean
        QdrantDimensionGuard guard(
                KbProperties properties,
                KbQdrantProperties qdrant,
                VectorStore vectorStore,
                KbUserSettingsDao settingsDao) {
            return new QdrantDimensionGuard(properties, qdrant, vectorStore, settingsDao);
        }
    }
}
