package com.agentcore.server.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for integration tests that need the real persistence stack.
 *
 * <p>Containers start once per JVM (singleton pattern) so the cached Spring
 * context never points at a container that was stopped between test classes.
 * Flyway runs the production migrations against the container before the
 * context starts. Without a local Docker daemon the tests are skipped.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
// Most tests have no broker; KB integration tests switch the listener back on.
@TestPropertySource(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
public abstract class AbstractIntegrationTest {

    protected static final String JWT_SECRET =
            "test-only-jwt-secret-that-is-long-enough-32+";
    protected static final String GATEWAY_API_TOKEN = "test-gateway-token";
    protected static final String MODEL_SECRET_KEY =
            "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private static final boolean DOCKER_AVAILABLE = DockerClientFactory.instance().isDockerAvailable();

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("agent_core")
            .withUsername("agent_core")
            .withPassword("agent_core");

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    protected static final FakeGateway GATEWAY = new FakeGateway();

    static {
        if (DOCKER_AVAILABLE) {
            MYSQL.start();
            REDIS.start();
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> MYSQL.getJdbcUrl() + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.timeout", () -> "2s");
        registry.add("spring.data.redis.connect-timeout", () -> "2s");
        registry.add("app.auth.jwt-secret", () -> JWT_SECRET);
        registry.add("app.gateway.api-token", () -> GATEWAY_API_TOKEN);
        registry.add("app.gateway.base-url", GATEWAY::baseUrl);
        registry.add("app.models.secret-key", () -> MODEL_SECRET_KEY);
    }
}
