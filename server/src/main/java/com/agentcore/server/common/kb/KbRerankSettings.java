package com.agentcore.server.common.kb;

import com.agentcore.server.common.crypto.ApiKeyCipher;
import com.agentcore.server.config.KbProperties;
import com.agentcore.server.dao.KbUserSettingsDao;
import com.agentcore.server.domain.KbUserSettings;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Resolves the rerank endpoint for a user. An explicit user "off" wins over
 * the deployment default; otherwise user settings win, then the deployment
 * {@code RERANK_*} values. Empty means retrieval keeps the vector order.
 */
@Component
public class KbRerankSettings {

    private final KbProperties properties;
    private final KbUserSettingsDao settingsDao;
    private final ApiKeyCipher cipher;

    public KbRerankSettings(
            KbProperties properties, KbUserSettingsDao settingsDao, ApiKeyCipher cipher) {
        this.properties = properties;
        this.settingsDao = settingsDao;
        this.cipher = cipher;
    }

    public Optional<KbRerankConfig> resolve(long userId) {
        KbUserSettings setting = settingsDao.selectById(userId);
        if (setting != null && Boolean.FALSE.equals(setting.getRerankEnabled())) {
            return Optional.empty();
        }
        if (setting != null
                && setting.getRerankBaseUrl() != null
                && !setting.getRerankBaseUrl().isBlank()) {
            String apiKey = null;
            if (setting.getRerankApiKeyCipher() != null
                    && !setting.getRerankApiKeyCipher().isBlank()) {
                try {
                    apiKey = cipher.decrypt(userId, setting.getRerankApiKeyCipher());
                } catch (IllegalStateException | IllegalArgumentException e) {
                    throw KbRerankException.permanent(
                            KbRerankException.RERANK_NOT_CONFIGURED,
                            "stored rerank API key cannot be decrypted; re-enter it",
                            e);
                }
            }
            return Optional.of(new KbRerankConfig(
                    setting.getRerankBaseUrl(),
                    apiKey,
                    notBlank(setting.getRerankModel())
                            ? setting.getRerankModel()
                            : properties.rerank().model(),
                    notBlank(setting.getRerankPath())
                            ? setting.getRerankPath()
                            : properties.rerank().path()));
        }

        KbProperties.Rerank fallback = properties.rerank();
        if (!fallback.enabled() || !fallback.configured()) {
            return Optional.empty();
        }
        return Optional.of(new KbRerankConfig(
                fallback.baseUrl(), fallback.apiKey(), fallback.model(), fallback.path()));
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
