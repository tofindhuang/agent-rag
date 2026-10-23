package com.agentcore.server.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentcore.server.config.KbQueueProperties;
import com.agentcore.server.dao.KbDocumentDao;
import com.agentcore.server.domain.KbDocument;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class KbDocumentReconcilerTest {

    @Test
    void republishesQueuedAndDeletingRowsAndMarksTheOutbox() {
        KbDocumentDao dao = Mockito.mock(KbDocumentDao.class);
        FakePublisher publisher = new FakePublisher();
        when(dao.findQueuedForDispatch(any(LocalDateTime.class), eq(100)))
                .thenReturn(List.of(document("queued", "doc-1")));
        when(dao.findDeletingForDispatch(any(LocalDateTime.class), eq(100)))
                .thenReturn(List.of(document("deleting", "doc-2")));

        int republished = new KbDocumentReconciler(dao, publisher).runOnce();

        assertThat(republished).isEqualTo(2);
        assertThat(publisher.ingested)
                .containsExactly(KbTaskMessage.ingest(7, "doc-1", 0));
        assertThat(publisher.deleted)
                .containsExactly(KbTaskMessage.delete(7, "doc-2", 0));
        verify(dao).markEnqueued(eq("doc-1"), eq(7L), any(LocalDateTime.class));
        verify(dao).markEnqueued(eq("doc-2"), eq(7L), any(LocalDateTime.class));
    }

    @Test
    void leavesTheRowUnmarkedWhenPublishFails() {
        KbDocumentDao dao = Mockito.mock(KbDocumentDao.class);
        FakePublisher publisher = new FakePublisher();
        publisher.ingestSucceeds = false;
        when(dao.findQueuedForDispatch(any(LocalDateTime.class), eq(100)))
                .thenReturn(List.of(document("queued", "doc-1")));
        when(dao.findDeletingForDispatch(any(LocalDateTime.class), eq(100)))
                .thenReturn(List.of());

        int republished = new KbDocumentReconciler(dao, publisher).runOnce();

        assertThat(republished).isZero();
        verify(dao, never()).markEnqueued(any(), any(Long.class), any());
    }

    private static KbDocument document(String status, String id) {
        KbDocument document = new KbDocument();
        document.setId(id);
        document.setUserId(7L);
        document.setStatus(status);
        document.setVersion(0);
        return document;
    }

    private static final class FakePublisher extends KbTaskPublisher {

        private boolean ingestSucceeds = true;
        private boolean deleteSucceeds = true;
        private final List<KbTaskMessage> ingested = new ArrayList<>();
        private final List<KbTaskMessage> deleted = new ArrayList<>();

        private FakePublisher() {
            super(null, new KbQueueProperties("test"));
        }

        @Override
        public boolean publishIngest(KbTaskMessage message) {
            ingested.add(message);
            return ingestSucceeds;
        }

        @Override
        public boolean publishDelete(KbTaskMessage message) {
            deleted.add(message);
            return deleteSucceeds;
        }
    }
}
