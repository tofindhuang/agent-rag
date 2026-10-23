package com.agentcore.server.service.impl;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agentcore.server.common.storage.ObjectStore;
import com.agentcore.server.common.vector.VectorStore;
import com.agentcore.server.dao.KbDocumentDao;
import com.agentcore.server.domain.KbDocument;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class KbDocumentDeletionServiceImplTest {

    @Test
    void deletesObjectThenVectorsThenTheRow() {
        KbDocumentDao dao = mock(KbDocumentDao.class);
        ObjectStore objectStore = mock(ObjectStore.class);
        VectorStore vectorStore = mock(VectorStore.class);
        KbDocument document = new KbDocument();
        document.setId("doc");
        document.setUserId(7L);
        document.setStatus(KbDocument.STATUS_DELETING);
        document.setOriginalKey("7/doc/sha.pdf");
        when(dao.findOwned("doc", 7)).thenReturn(document);

        new KbDocumentDeletionServiceImpl(dao, objectStore, vectorStore).delete(7, "doc");

        InOrder order = inOrder(objectStore, vectorStore, dao);
        order.verify(objectStore).delete("7/doc/sha.pdf");
        order.verify(vectorStore).deleteByDocument(7, "doc");
        order.verify(dao).deleteOwned("doc", 7);
    }

    @Test
    void redeliveryAfterTheRowIsGoneIsANoOp() {
        KbDocumentDao dao = mock(KbDocumentDao.class);
        ObjectStore objectStore = mock(ObjectStore.class);
        VectorStore vectorStore = mock(VectorStore.class);
        when(dao.findOwned("doc", 7)).thenReturn(null);

        assertThatCode(() -> new KbDocumentDeletionServiceImpl(dao, objectStore, vectorStore)
                .delete(7, "doc"))
                .doesNotThrowAnyException();

        verifyNoInteractions(objectStore, vectorStore);
    }

    @Test
    void ignoresAStaleMessageWhenTheDocumentIsNoLongerDeleting() {
        KbDocumentDao dao = mock(KbDocumentDao.class);
        ObjectStore objectStore = mock(ObjectStore.class);
        VectorStore vectorStore = mock(VectorStore.class);
        KbDocument document = new KbDocument();
        document.setId("doc");
        document.setUserId(7L);
        document.setStatus(KbDocument.STATUS_READY);
        when(dao.findOwned("doc", 7)).thenReturn(document);

        new KbDocumentDeletionServiceImpl(dao, objectStore, vectorStore).delete(7, "doc");

        verifyNoInteractions(objectStore, vectorStore);
    }
}
