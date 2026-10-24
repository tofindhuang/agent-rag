package com.agentcore.server.support;

import java.time.Duration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base for KB HTTP/worker integration tests: MySQL, Redis, a real S3-compatible
 * Silo, RabbitMQ and Qdrant. Containers start once per JVM; the Spring context
 * is cached by its dynamic properties.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = "spring.rabbitmq.listener.simple.auto-startup=true")
public abstract class AbstractKbIntegrationTest extends AbstractIntegrationTest {

    protected static final String KB_QUEUE_PREFIX = "kb_it";
    protected static final String KB_INGEST_QUEUE = KB_QUEUE_PREFIX + ".ingest.q";
    protected static final String KB_DELETE_QUEUE = KB_QUEUE_PREFIX + ".ingest.delete.q";
    protected static final String KB_DLQ = KB_QUEUE_PREFIX + ".ingest.dlq";

    private static final boolean DOCKER_AVAILABLE =
            DockerClientFactory.instance().isDockerAvailable();

    protected static final FakeEmbeddingServer EMBEDDING = FakeEmbeddingServer.start(1024);
    protected static final FakeOcrServer OCR = FakeOcrServer.start();

    static final GenericContainer<?> SILO =
            new GenericContainer<>("pgsty/silo:RELEASE.2026-10-18T00-00-00Z")
                    .withExposedPorts(9000)
                    .withEnv("MINIO_ROOT_USER", "agent_core")
                    .withEnv("MINIO_ROOT_PASSWORD", "agent_core_dev_only")
                    .withCommand("server", "/data")
                    .waitingFor(Wait.forHttp("/minio/health/live")
                            .forStatusCode(200)
                            .withStartupTimeout(Duration.ofSeconds(60)));

    static final GenericContainer<?> RABBIT =
            new GenericContainer<>("rabbitmq:4.3.7-management")
                    .withExposedPorts(5672)
                    .withEnv("RABBITMQ_DEFAULT_USER", "agent_core")
                    .withEnv("RABBITMQ_DEFAULT_PASS", "agent_core_dev_only")
                    .withEnv("RABBITMQ_DEFAULT_VHOST", "/")
                    .waitingFor(Wait.forListeningPort()
                            .withStartupTimeout(Duration.ofSeconds(90)));

    static final GenericContainer<?> QDRANT =
            new GenericContainer<>("qdrant/qdrant:v1.14.1")
                    .withExposedPorts(6333)
                    .waitingFor(Wait.forListeningPort()
                            .withStartupTimeout(Duration.ofSeconds(60)));

    static {
        if (DOCKER_AVAILABLE) {
            SILO.start();
            RABBIT.start();
            QDRANT.start();
        }
    }

    @DynamicPropertySource
    static void kbProperties(DynamicPropertyRegistry registry) {
        registry.add("app.kb.enabled", () -> "true");
        registry.add("app.kb.allow-private-endpoints", () -> "true");
        registry.add("app.kb.queue.prefix", () -> KB_QUEUE_PREFIX);
        registry.add("app.kb.embedding.base-url", EMBEDDING::baseUrl);
        registry.add("app.kb.embedding.api-key", () -> "test-embedding-key");
        registry.add("app.kb.embedding.model", () -> "test-embedding-model");
        registry.add("app.kb.embedding.path", () -> "/embeddings");
        registry.add("app.kb.embedding.dim", () -> "1024");
        registry.add("app.kb.ocr.enabled", () -> "true");
        registry.add("app.kb.ocr.base-url", OCR::baseUrl);
        registry.add("app.kb.ocr.max-pages", () -> "50");
        registry.add("app.kb.ocr.timeout-ms", () -> "2000");
        registry.add("app.kb.ocr.total-budget-ms", () -> "60000");
        registry.add("app.kb.qdrant.url",
                () -> "http://" + QDRANT.getHost() + ":" + QDRANT.getMappedPort(6333));
        registry.add("app.kb.qdrant.collection-prefix", () -> "kb_it");
        registry.add("app.kb.s3.endpoint",
                () -> "http://" + SILO.getHost() + ":" + SILO.getMappedPort(9000));
        registry.add("app.kb.s3.access-key", () -> "agent_core");
        registry.add("app.kb.s3.secret-key", () -> "agent_core_dev_only");
        registry.add("app.kb.s3.bucket", () -> "kb-it");
        registry.add("app.kb.s3.region", () -> "us-east-1");
        registry.add("app.kb.s3.path-style", () -> "true");
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", () -> RABBIT.getMappedPort(5672));
        registry.add("spring.rabbitmq.username", () -> "agent_core");
        registry.add("spring.rabbitmq.password", () -> "agent_core_dev_only");
        registry.add("spring.rabbitmq.virtual-host", () -> "/");
    }
}
