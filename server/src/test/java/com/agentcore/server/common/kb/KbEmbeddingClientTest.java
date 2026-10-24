package com.agentcore.server.common.kb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentcore.server.config.KbProperties;
import com.agentcore.server.support.FakeEmbeddingServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KbEmbeddingClientTest {

    private FakeEmbeddingServer server;
    private KbEmbeddingClient client;

    @BeforeEach
    void setUp() {
        server = FakeEmbeddingServer.start(8);
        KbProperties properties = new KbProperties(
                true, true, 200, 1L << 30, 3, null, null);
        client = new KbEmbeddingClient(new ObjectMapper(), new KbUrlGuard(properties));
    }

    @Test
    void returnsOneVectorPerInputAndBatchesAt32() {
        List<String> inputs = java.util.stream.IntStream.range(0, 33)
                .mapToObj((index) -> "text " + index)
                .toList();

        List<float[]> vectors = client.embed(config(8), inputs);

        assertThat(vectors).hasSize(33);
        assertThat(vectors).allSatisfy((vector) -> assertThat(vector).hasSize(8));
        assertThat(server.requests()).isEqualTo(2);
    }

    @Test
    void classifiesRateLimitsAsRetryable() {
        server.mode(FakeEmbeddingServer.Mode.RATE_LIMITED);

        assertThatThrownBy(() -> client.embed(config(8), List.of("text")))
                .isInstanceOfSatisfying(KbIngestException.class, (error) -> {
                    assertThat(error.retryable()).isTrue();
                    assertThat(error.errorCode()).isEqualTo(KbIngestException.EMBEDDING_UNAVAILABLE);
                });
    }

    @Test
    void classifiesBadRequestsAsPermanent() {
        server.mode(FakeEmbeddingServer.Mode.BAD_REQUEST);

        assertThatThrownBy(() -> client.embed(config(8), List.of("text")))
                .isInstanceOfSatisfying(KbIngestException.class, (error) -> {
                    assertThat(error.retryable()).isFalse();
                    assertThat(error.errorCode()).isEqualTo(KbIngestException.EMBEDDING_REJECTED);
                });
    }

    @Test
    void fallsBackToResponseOrderWhenProviderIndexesAreNotZeroBased() {
        server.mode(FakeEmbeddingServer.Mode.ONE_BASED_INDEX);

        List<float[]> vectors = client.embed(config(8), List.of("a", "b", "c"));

        assertThat(vectors).hasSize(3);
        assertThat(vectors.get(0)[0]).isEqualTo(1f);
        assertThat(vectors.get(1)[0]).isEqualTo(2f);
        assertThat(vectors.get(2)[0]).isEqualTo(3f);
    }

    @Test
    void retriesAmbiguousIndexesIndividuallyInsteadOfGuessingOrder() {
        server.mode(FakeEmbeddingServer.Mode.DUPLICATE_INDEX);

        List<float[]> vectors = client.embed(config(8), List.of("a", "b", "c"));

        assertThat(vectors).hasSize(3);
        assertThat(vectors).allSatisfy((vector) -> assertThat(vector).hasSize(8));
        // One batch attempt + one request per input: never guess the mapping.
        assertThat(server.requests()).isEqualTo(4);
    }

    @Test
    void classifiesTimeoutsAsRetryable() {
        server.mode(FakeEmbeddingServer.Mode.SLOW);

        assertThatThrownBy(() -> client.embed(
                        config(8), List.of("text"), java.time.Duration.ofMillis(100)))
                .isInstanceOfSatisfying(KbIngestException.class, (error) -> {
                    assertThat(error.retryable()).isTrue();
                    assertThat(error.errorCode()).isEqualTo(KbIngestException.EMBEDDING_UNAVAILABLE);
                });
    }

    @Test
    void rejectsADimensionThatDiffersFromConfiguration() {
        server.mode(FakeEmbeddingServer.Mode.WRONG_DIMENSION);

        assertThatThrownBy(() -> client.embed(config(8), List.of("text")))
                .isInstanceOfSatisfying(KbIngestException.class, (error) -> {
                    assertThat(error.retryable()).isFalse();
                    assertThat(error.errorCode())
                            .isEqualTo(KbIngestException.EMBEDDING_DIMENSION_MISMATCH);
                });
    }

    private KbEmbeddingConfig config(int dimension) {
        return new KbEmbeddingConfig(
                server.baseUrl(), "key", "test-model", "/embeddings", dimension);
    }
}
