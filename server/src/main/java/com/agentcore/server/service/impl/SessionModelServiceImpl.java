package com.agentcore.server.service.impl;

import com.agentcore.server.common.crypto.ApiKeyCipher;
import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.config.ModelProperties;
import com.agentcore.server.dao.ModelProviderDao;
import com.agentcore.server.dao.ProviderModelDao;
import com.agentcore.server.dao.SessionModelDao;
import com.agentcore.server.dao.UserModelDefaultDao;
import com.agentcore.server.domain.ModelProvider;
import com.agentcore.server.domain.ProviderModel;
import com.agentcore.server.domain.SessionModel;
import com.agentcore.server.service.SessionModelChoice;
import com.agentcore.server.domain.UserModelDefault;
import com.agentcore.server.service.SessionModelService;
import org.springframework.stereotype.Service;

@Service
public class SessionModelServiceImpl implements SessionModelService {

    private static final String SYSTEM_LABEL = "系统默认";

    private final ModelProviderDao providerDao;
    private final ProviderModelDao providerModelDao;
    private final UserModelDefaultDao defaultDao;
    private final SessionModelDao sessionModelDao;
    private final ApiKeyCipher cipher;
    private final ModelProperties properties;

    public SessionModelServiceImpl(
            ModelProviderDao providerDao,
            ProviderModelDao providerModelDao,
            UserModelDefaultDao defaultDao,
            SessionModelDao sessionModelDao,
            ApiKeyCipher cipher,
            ModelProperties properties) {
        this.providerDao = providerDao;
        this.providerModelDao = providerModelDao;
        this.defaultDao = defaultDao;
        this.sessionModelDao = sessionModelDao;
        this.cipher = cipher;
        this.properties = properties;
    }

    @Override
    public SessionModelChoice forNewSession(long userId, Long modelId, boolean useSystemDefault) {
        // An explicit system choice must bypass the user default, otherwise a
        // picker selection of "system default" would silently pick the user's
        // own default model instead.
        if (useSystemDefault) {
            return system(userId, true);
        }
        if (modelId != null) {
            return explicit(userId, modelId);
        }
        UserModelDefault stored = defaultDao.selectById(userId);
        if (stored != null) {
            return explicit(userId, stored.getModelId());
        }
        return system(userId, true);
    }

    @Override
    public SessionModelChoice forExistingSession(long userId, String sessionId) {
        SessionModel snapshot = sessionModelDao.selectById(sessionId);
        if (snapshot == null) {
            // Pre-migration sessions keep working even if the system default is
            // disabled; they were created when it was allowed.
            return system(userId, false);
        }
        if ("system".equals(snapshot.getModelSource())) {
            return SessionModelChoice.system(
                    snapshot.getModelLabel() == null ? SYSTEM_LABEL : snapshot.getModelLabel());
        }
        if (snapshot.getProviderModelId() == null) {
            throw new ApiException(ErrorCode.MODEL_UNAVAILABLE, "session model is no longer available");
        }
        return explicit(userId, snapshot.getProviderModelId());
    }

    @Override
    public SessionModelChoice forSwitch(long userId, Long modelId) {
        return modelId == null ? system(userId, true) : explicit(userId, modelId);
    }

    private static String truncate(String label) {
        return label.length() <= 300 ? label : label.substring(0, 300);
    }

    private SessionModelChoice explicit(long userId, long modelId) {
        ProviderModel model = providerModelDao.findOwned(modelId, userId);
        if (model == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "model not found");
        }
        ModelProvider provider = providerDao.selectById(model.getProviderId());
        if (provider == null) {
            throw new ApiException(ErrorCode.MODEL_UNAVAILABLE, "model provider is missing");
        }
        String apiKey;
        try {
            apiKey = cipher.decrypt(userId, provider.getApiKeyCipher());
        } catch (IllegalStateException ex) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "stored API key cannot be decrypted; re-enter it");
        }
        return SessionModelChoice.user(
                model.getId(),
                truncate(provider.getName() + " / " + model.getDisplayName()),
                new SessionModelChoice.Binding(
                        provider.getName(),
                        provider.getApi(),
                        provider.getBaseUrl(),
                        apiKey,
                        model.getModelId(),
                        model.getDisplayName(),
                        model.getContextWindow(),
                        Boolean.TRUE.equals(model.getSupportsReasoning()),
                        Boolean.TRUE.equals(model.getSupportsVision())));
    }

    private SessionModelChoice system(long userId, boolean enforceGate) {
        if (enforceGate && !properties.allowSystemDefault()) {
            throw new ApiException(ErrorCode.MODEL_NOT_CONFIGURED,
                    "no model configured; add a provider or enable the system default");
        }
        return SessionModelChoice.system(SYSTEM_LABEL);
    }
}
