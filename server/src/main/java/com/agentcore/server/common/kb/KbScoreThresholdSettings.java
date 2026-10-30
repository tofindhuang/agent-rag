package com.agentcore.server.common.kb;

import com.agentcore.server.dao.KbUserSettingsDao;
import com.agentcore.server.domain.KbUserSettings;
import org.springframework.stereotype.Component;

/**
 * Resolves the user's refusal thresholds (ADR-0029). Missing row or missing
 * values mean "do not refuse" — enabling refusal is always an explicit act.
 */
@Component
public class KbScoreThresholdSettings {

    private final KbUserSettingsDao settingsDao;

    public KbScoreThresholdSettings(KbUserSettingsDao settingsDao) {
        this.settingsDao = settingsDao;
    }

    public KbScoreThresholds resolve(long userId) {
        KbUserSettings settings = settingsDao.selectById(userId);
        if (settings == null) {
            return KbScoreThresholds.NONE;
        }
        return new KbScoreThresholds(settings.getRerankMinScore(), settings.getVectorMinScore());
    }
}
