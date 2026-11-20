package com.agentcore.server.common.vector;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VectorPointTest {

    @Test
    void pointIdsAreStablePerDocumentAndSequence() {
        String documentId = UUID.randomUUID().toString();

        assertThat(VectorPoint.pointId(documentId, 1))
                .isEqualTo(VectorPoint.pointId(documentId, 1));
        assertThat(VectorPoint.pointId(documentId, 1))
                .isNotEqualTo(VectorPoint.pointId(documentId, 2));
        assertThat(VectorPoint.pointId(documentId, 1))
                .isNotEqualTo(VectorPoint.pointId(UUID.randomUUID().toString(), 1));
    }

    @Test
    void payloadRoundTripsPageAndNullPage() {
        VectorPayload withPage = new VectorPayload(7, "doc", 3, 12, "Title", "text");
        VectorPayload withoutPage = new VectorPayload(7, "doc", 3, null, "Title", "text");

        assertThat(VectorPayload.fromMap(withPage.toMap())).isEqualTo(withPage);
        assertThat(VectorPayload.fromMap(withoutPage.toMap())).isEqualTo(withoutPage);

        Map<String, Object> map = withoutPage.toMap();
        assertThat(map).doesNotContainKey("page");
    }
}
