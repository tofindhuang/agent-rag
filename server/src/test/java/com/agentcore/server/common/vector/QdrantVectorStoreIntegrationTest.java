package com.agentcore.server.common.vector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentcore.server.common.kb.KbDimensionMismatchException;
import com.agentcore.server.config.KbQdrantProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class QdrantVectorStoreIntegrationTest {

    @Container
    static final GenericContainer<?> QDRANT = new GenericContainer<>("qdrant/qdrant:v1.14.1")
            .withExposedPorts(6333)
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(60)));

    private static QdrantVectorStore store;

    @BeforeAll
    static void createStore() {
        KbQdrantProperties properties = new KbQdrantProperties(
                "http://" + QDRANT.getHost() + ":" + QDRANT.getMappedPort(6333),
                "",
                "kb_test_" + UUID.randomUUID().toString().replace("-", ""));
        store = new QdrantVectorStore(properties, new ObjectMapper());
    }

    @Test
    void createsACollectionLazilyAndLocksItsDimension() {
        long userId = 101;
        store.ensureCollection(userId, 4);

        assertThat(store.collections())
                .anySatisfy((collection) -> {
                    assertThat(collection.name()).isEqualTo(store.collectionName(userId));
                    assertThat(collection.dimension()).isEqualTo(4);
                });

        store.ensureCollection(userId, 4);

        assertThatThrownBy(() -> store.ensureCollection(userId, 8))
                .isInstanceOf(KbDimensionMismatchException.class)
                .hasMessageContaining("dimension 4")
                .hasMessageContaining("dimension is 8");
    }

    @Test
    void upsertsSearchesAndDeletesByDocument() {
        long userId = 102;
        String documentA = UUID.randomUUID().toString();
        String documentB = UUID.randomUUID().toString();
        store.upsert(userId, 4, List.of(
                point(userId, documentA, 1, 1, 0, 0, 0, "Alpha", "first"),
                point(userId, documentA, 2, 0, 1, 0, 0, "Beta", "second"),
                point(userId, documentB, 1, 0, 0, 1, 0, "Gamma", "third")));

        List<VectorHit> hits = store.search(userId, new float[] {1, 0, 0, 0}, 10);
        assertThat(hits).hasSize(3);
        assertThat(hits.get(0).payload().documentId()).isEqualTo(documentA);
        assertThat(hits.get(0).payload().text()).isEqualTo("first");
        assertThat(hits.get(0).payload().page()).isEqualTo(1);

        store.deleteByDocument(userId, documentA);

        List<VectorHit> remaining = store.search(userId, new float[] {1, 0, 0, 0}, 10);
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).payload().documentId()).isEqualTo(documentB);

        // Idempotent delete and searches for users without a collection.
        store.deleteByDocument(userId, documentA);
        assertThat(store.search(9999, new float[] {1, 0, 0, 0}, 10)).isEmpty();

        // Collections are per user: another user's points are never visible.
        String otherDocument = UUID.randomUUID().toString();
        store.upsert(9998, 4, List.of(
                point(9998, otherDocument, 1, 1, 0, 0, 0, "Other", "private")));
        assertThat(store.search(userId, new float[] {1, 0, 0, 0}, 10))
                .noneMatch((hit) -> hit.payload().documentId().equals(otherDocument));
    }

    @Test
    void reingestionOverwritesTheSamePointInsteadOfDuplicating() {
        long userId = 103;
        String documentId = UUID.randomUUID().toString();
        store.upsert(userId, 4, List.of(point(userId, documentId, 1, 1, 0, 0, 0, "Title", "old")));
        store.upsert(userId, 4, List.of(point(userId, documentId, 1, 0.5f, 0.5f, 0, 0, "Title", "new")));

        List<VectorHit> hits = store.search(userId, new float[] {1, 0, 0, 0}, 10);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).payload().text()).isEqualTo("new");
    }

    @Test
    void recreateCollectionDropsOldVectorsAtTheNewDimension() {
        long userId = 104;
        String documentId = UUID.randomUUID().toString();
        store.upsert(userId, 4, List.of(point(userId, documentId, 1, 1, 0, 0, 0, "Title", "old")));

        store.recreateCollection(userId, 8);

        assertThat(store.collections())
                .filteredOn((collection) -> collection.name().equals(store.collectionName(userId)))
                .singleElement()
                .extracting(VectorCollection::dimension)
                .isEqualTo(8);
        assertThat(store.search(userId, new float[] {1, 0, 0, 0, 0, 0, 0, 0}, 10)).isEmpty();
    }

    @Test
    void rejectsQueryWithTheWrongDimension() {
        long userId = 105;
        store.ensureCollection(userId, 4);

        assertThatThrownBy(() -> store.search(userId, new float[] {1, 0, 0}, 5))
                .isInstanceOf(KbDimensionMismatchException.class);
    }

    private static VectorPoint point(
            long userId,
            String documentId,
            int seq,
            float x,
            float y,
            float z,
            float w,
            String title,
            String text) {
        return new VectorPoint(
                VectorPoint.pointId(documentId, seq),
                new float[] {x, y, z, w},
                new VectorPayload(userId, documentId, seq, seq, title, text));
    }
}
