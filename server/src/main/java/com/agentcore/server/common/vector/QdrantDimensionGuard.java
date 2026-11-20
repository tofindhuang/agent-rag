package com.agentcore.server.common.vector;

import com.agentcore.server.common.kb.KbStoreException;
import com.agentcore.server.config.KbProperties;
import com.agentcore.server.config.KbQdrantProperties;
import com.agentcore.server.dao.KbUserSettingsDao;
import com.agentcore.server.domain.KbUserSettings;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * Startup guard for per-user collection dimensions (FR-2).
 *
 * <p>A mismatch is a deployment error: the collection was built with a
 * different embedding dimension than the current user setting / deployment
 * default, so retrieval would fail on every query. Fail fast instead of
 * serving a broken index. Qdrant being unreachable is <em>not</em> fatal —
 * chat must keep working without the KB (ticket 01).
 */
@Component
public class QdrantDimensionGuard implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(QdrantDimensionGuard.class);

    private final KbProperties properties;
    private final KbQdrantProperties qdrant;
    private final VectorStore vectorStore;
    private final KbUserSettingsDao settingsDao;

    public QdrantDimensionGuard(
            KbProperties properties,
            KbQdrantProperties qdrant,
            VectorStore vectorStore,
            KbUserSettingsDao settingsDao) {
        this.properties = properties;
        this.qdrant = qdrant;
        this.vectorStore = vectorStore;
        this.settingsDao = settingsDao;
    }

    @Override
    public void afterSingletonsInstantiated() {
        verify();
    }

    void verify() {
        if (!properties.enabled()) {
            return;
        }
        List<VectorCollection> collections;
        try {
            collections = vectorStore.collections();
        } catch (KbStoreException e) {
            log.warn("Qdrant unavailable at startup; knowledge base stays offline: {}",
                    e.getMessage());
            return;
        }

        Map<Long, Integer> userDimensions = userDimensions();
        String prefix = qdrant.collectionPrefix() + "_u";
        int validated = 0;
        for (VectorCollection collection : collections) {
            String name = collection.name();
            if (!name.startsWith(prefix)) {
                continue;
            }
            long userId;
            try {
                userId = Long.parseLong(name.substring(prefix.length()));
            } catch (NumberFormatException e) {
                log.warn("ignoring Qdrant collection with unexpected name: {}", name);
                continue;
            }
            int expected = userDimensions.getOrDefault(userId, properties.embedding().dim());
            if (collection.dimension() != expected) {
                String message = "Qdrant collection " + name + " has dimension "
                        + collection.dimension() + " but user " + userId + " expects " + expected
                        + " (kb_user_settings / EMBEDDING_DIM); rebuild that user's collection";
                log.error(message);
                throw new IllegalStateException(message);
            }
            validated++;
        }
        log.info("Qdrant dimension guard validated {} user collection(s)", validated);
    }

    private Map<Long, Integer> userDimensions() {
        Map<Long, Integer> result = new HashMap<>();
        List<KbUserSettings> settings = settingsDao.selectList(null);
        if (settings == null) {
            return result;
        }
        for (KbUserSettings setting : settings) {
            if (setting.getUserId() != null
                    && setting.getEmbeddingDim() != null
                    && setting.getEmbeddingDim() > 0) {
                result.put(setting.getUserId(), setting.getEmbeddingDim());
            }
        }
        return result;
    }
}
