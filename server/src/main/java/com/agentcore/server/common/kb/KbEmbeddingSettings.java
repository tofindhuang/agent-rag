package com.agentcore.server.common.kb;

import com.agentcore.server.common.crypto.ApiKeyCipher;
import com.agentcore.server.config.KbProperties;
import com.agentcore.server.dao.KbUserSettingsDao;
import com.agentcore.server.domain.KbUserSettings;
import org.springframework.stereotype.Component;

/**
 * Resolves the embedding endpoint for a user: the stored user setting wins,
 * otherwise the deployment {@code EMBEDDING_*} values apply. Ticket 04 owns the
 * settings write/probe API; ingestion only needs this read path.
 */
@Component
public class KbEmbeddingSettings {

    private final KbProperties properties;
    private final KbUserSettingsDao settingsDao;
    private final ApiKeyCipher cipher;

    public KbEmbeddingSettings(
            KbProperties properties, KbUserSettingsDao settingsDao, ApiKeyCipher cipher) {
        this.properties = properties;
        this.settingsDao = settingsDao;
        this.cipher = cipher;
    }

    public KbEmbeddingConfig resolve(long userId) {
        KbProperties.Embedding fallback = properties.embedding();
        KbUserSettings setting = settingsDao.selectById(userId);
        if (setting != null
                && setting.getEmbeddingBaseUrl() != null
                && !setting.getEmbeddingBaseUrl().isBlank()) {
            String apiKey = null;
            if (setting.getEmbeddingApiKeyCipher() != null
                    && !setting.getEmbeddingApiKeyCipher().isBlank()) {
                try {
                    apiKey = cipher.decrypt(userId, setting.getEmbeddingApiKeyCipher());
                } catch (IllegalStateException e) {
                    throw KbIngestException.permanent(
                            KbIngestException.EMBEDDING_NOT_CONFIGURED,
                            "stored embedding API key cannot be decrypted",
                            e);
                }
            }
            return new KbEmbeddingConfig(
                    setting.getEmbeddingBaseUrl(),
                    apiKey,
                    notBlank(setting.getEmbeddingModel())
                            ? setting.getEmbeddingModel()
                            : fallback.model(),
                    notBlank(setting.getEmbeddingPath())
                            ? setting.getEmbeddingPath()
                            : fallback.path(),
                    setting.getEmbeddingDim() != null && setting.getEmbeddingDim() > 0
                            ? setting.getEmbeddingDim()
                            : fallback.dim());
        }

        if (!fallback.configured()) {
            throw KbIngestException.permanent(
                    KbIngestException.EMBEDDING_NOT_CONFIGURED,
                    "no embedding endpoint is configured");
        }
        return new KbEmbeddingConfig(
                fallback.baseUrl(),
                fallback.apiKey(),
                fallback.model(),
                fallback.path(),
                fallback.dim());
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
