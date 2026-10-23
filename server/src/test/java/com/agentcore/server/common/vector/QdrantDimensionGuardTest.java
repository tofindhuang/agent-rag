package com.agentcore.server.common.vector;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agentcore.server.common.kb.KbStoreException;
import com.agentcore.server.config.KbProperties;
import com.agentcore.server.config.KbQdrantProperties;
import com.agentcore.server.dao.KbUserSettingsDao;
import com.agentcore.server.domain.KbUserSettings;
import java.util.List;
import org.junit.jupiter.api.Test;

class QdrantDimensionGuardTest {

    private final KbQdrantProperties qdrant =
            new KbQdrantProperties("http://localhost:6333", "", "kb_passages");

    @Test
    void failsStartupWhenACollectionDimensionDoesNotMatchTheDefault() {
        VectorStore store = mock(VectorStore.class);
        KbUserSettingsDao settings = mock(KbUserSettingsDao.class);
        when(store.collections())
                .thenReturn(List.of(new VectorCollection("kb_passages_u7", 768)));
        when(settings.selectList(any())).thenReturn(List.of());

        QdrantDimensionGuard guard = guard(true, store, settings);

        assertThatThrownBy(guard::verify)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("kb_passages_u7")
                .hasMessageContaining("dimension 768")
                .hasMessageContaining("expects 1024");
    }

    @Test
    void acceptsACollectionMatchingTheUsersRecordedDimension() {
        VectorStore store = mock(VectorStore.class);
        KbUserSettingsDao settings = mock(KbUserSettingsDao.class);
        KbUserSettings user = new KbUserSettings();
        user.setUserId(7L);
        user.setEmbeddingDim(768);
        when(store.collections())
                .thenReturn(List.of(new VectorCollection("kb_passages_u7", 768)));
        when(settings.selectList(any())).thenReturn(List.of(user));

        QdrantDimensionGuard guard = guard(true, store, settings);

        assertThatCode(guard::verify).doesNotThrowAnyException();
    }

    @Test
    void unavailableQdrantDoesNotBlockStartup() {
        VectorStore store = mock(VectorStore.class);
        KbUserSettingsDao settings = mock(KbUserSettingsDao.class);
        when(store.collections())
                .thenThrow(KbStoreException.retryable("connection refused", null));

        QdrantDimensionGuard guard = guard(true, store, settings);

        assertThatCode(guard::verify).doesNotThrowAnyException();
        verifyNoInteractions(settings);
    }

    @Test
    void disabledKnowledgeBaseSkipsTheGuardEntirely() {
        VectorStore store = mock(VectorStore.class);
        KbUserSettingsDao settings = mock(KbUserSettingsDao.class);

        QdrantDimensionGuard guard = guard(false, store, settings);

        assertThatCode(guard::verify).doesNotThrowAnyException();
        verifyNoInteractions(store, settings);
    }

    @Test
    void ignoresCollectionsOutsideTheKbPrefix() {
        VectorStore store = mock(VectorStore.class);
        KbUserSettingsDao settings = mock(KbUserSettingsDao.class);
        when(store.collections())
                .thenReturn(List.of(new VectorCollection("other_collection", 3)));
        when(settings.selectList(any())).thenReturn(List.of());

        QdrantDimensionGuard guard = guard(true, store, settings);

        assertThatCode(guard::verify).doesNotThrowAnyException();
    }

    private QdrantDimensionGuard guard(
            boolean enabled, VectorStore store, KbUserSettingsDao settings) {
        KbProperties properties = new KbProperties(enabled, false, 200, 1L << 30, 3, null, null);
        return new QdrantDimensionGuard(properties, qdrant, store, settings);
    }
}
