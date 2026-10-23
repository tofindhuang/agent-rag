package com.agentcore.server.common.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentcore.server.common.kb.KbStoreException;
import com.agentcore.server.config.KbS3Properties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class S3ObjectStoreIntegrationTest {

    @Container
    static final GenericContainer<?> SILO =
            new GenericContainer<>("pgsty/silo:RELEASE.2026-10-18T00-00-00Z")
                    .withExposedPorts(9000)
                    .withEnv("MINIO_ROOT_USER", "agent_core")
                    .withEnv("MINIO_ROOT_PASSWORD", "agent_core_dev_only")
                    .withCommand("server", "/data")
                    .waitingFor(Wait.forHttp("/minio/health/live")
                            .forStatusCode(200)
                            .withStartupTimeout(Duration.ofSeconds(60)));

    private static S3ObjectStore store;

    @BeforeAll
    static void createStore() {
        store = new S3ObjectStore(new KbS3Properties(
                "http://" + SILO.getHost() + ":" + SILO.getMappedPort(9000),
                "agent_core",
                "agent_core_dev_only",
                "kb-test",
                "us-east-1",
                true));
    }

    @Test
    void storesLoadsAndDeletesAnOriginalWithMetadata(@TempDir Path tempDir) throws Exception {
        byte[] content = "hello knowledge base".getBytes(StandardCharsets.UTF_8);
        String sha = sha256(content);
        String documentId = UUID.randomUUID().toString();
        Path file = tempDir.resolve("original.pdf");
        Files.write(file, content);

        String key = store.key(42, documentId, sha, "original.PDF");
        assertThat(key).isEqualTo("42/" + documentId + "/" + sha + ".pdf");

        store.put(key, file, "application/pdf", sha);
        assertThat(store.exists(key)).isTrue();

        try (StoredObject object = store.get(key)) {
            assertThat(object.contentType()).isEqualTo("application/pdf");
            assertThat(object.sha256()).isEqualTo(sha);
            assertThat(object.size()).isEqualTo(content.length);
            assertThat(object.body().readAllBytes()).isEqualTo(content);
        }

        store.delete(key);
        assertThat(store.exists(key)).isFalse();
        store.delete(key);
    }

    @Test
    void missingObjectIsANonRetryableNotFound(@TempDir Path tempDir) throws Exception {
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);
        String sha = sha256(content);
        String key = store.key(7, UUID.randomUUID().toString(), sha, "x.txt");
        Path file = tempDir.resolve("x.txt");
        Files.write(file, content);
        store.put(key, file, "text/plain", sha);
        store.delete(key);

        assertThatThrownBy(() -> store.get(key))
                .isInstanceOf(KbStoreException.class)
                .matches((error) -> ((KbStoreException) error).notFound())
                .matches((error) -> !((KbStoreException) error).retryable());
    }

    @Test
    void blankCredentialsFailWithAConfigurationError(@TempDir Path tempDir) throws Exception {
        S3ObjectStore unconfigured = new S3ObjectStore(new KbS3Properties(
                "http://localhost:9000", "", "", "kb-test", "us-east-1", true));
        Path file = tempDir.resolve("x.txt");
        Files.writeString(file, "x");

        assertThatThrownBy(() -> unconfigured.put(
                        "1/doc/" + sha256("x".getBytes(StandardCharsets.UTF_8)) + ".txt",
                        file,
                        "text/plain",
                        sha256("x".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(KbStoreException.class)
                .matches((error) -> !((KbStoreException) error).retryable());
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
