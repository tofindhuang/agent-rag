package com.agentcore.server.common.kb;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.config.KbProperties;
import com.agentcore.server.config.KbS3Properties;
import com.agentcore.server.dao.KbUserSettingsDao;
import com.agentcore.server.domain.KbUserSettings;
import org.springframework.stereotype.Component;

/**
 * Upload-time gate: a user can ingest only when either their own embedding
 * endpoint or the deployment default is configured. Ticket 04 extends the
 * setting probe; the fallback rule stays the same.
 */
@Component
public class KbConfigurationGate {

    private final KbProperties properties;
    private final KbS3Properties storage;
    private final KbUserSettingsDao settingsDao;

    public KbConfigurationGate(
            KbProperties properties, KbS3Properties storage, KbUserSettingsDao settingsDao) {
        this.properties = properties;
        this.storage = storage;
        this.settingsDao = settingsDao;
    }

    public void requireEmbeddingConfigured(long userId) {
        if (userConfigured(settingsDao.selectById(userId))
                || properties.embedding().configured()) {
            return;
        }
        throw new ApiException(ErrorCode.KB_NOT_CONFIGURED,
                "configure an embedding endpoint or set the deployment default");
    }

    public void requireStorageConfigured() {
        if (!storage.configured()) {
            throw new ApiException(ErrorCode.KB_NOT_CONFIGURED,
                    "configure object storage credentials (S3_ACCESS_KEY / S3_SECRET_KEY)");
        }
    }

    static boolean userConfigured(KbUserSettings settings) {
        return settings != null
                && settings.getEmbeddingBaseUrl() != null
                && !settings.getEmbeddingBaseUrl().isBlank()
                && settings.getEmbeddingDim() != null
                && settings.getEmbeddingDim() > 0;
    }
}
