package com.agentcore.server.service.impl;

import com.agentcore.server.common.crypto.ApiKeyCipher;
import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.common.gateway.GatewayClient;
import com.agentcore.server.config.GatewayProperties;
import com.agentcore.server.config.ModelProperties;
import com.agentcore.server.dao.ModelProviderDao;
import com.agentcore.server.dao.ProviderModelDao;
import com.agentcore.server.dao.UserModelDefaultDao;
import com.agentcore.server.domain.CreateProviderCommand;
import com.agentcore.server.domain.ModelCatalog;
import com.agentcore.server.domain.ModelProvider;
import com.agentcore.server.domain.ProbeCommand;
import com.agentcore.server.domain.ProbeModel;
import com.agentcore.server.domain.ProviderModel;
import com.agentcore.server.domain.ProviderModelSpec;
import com.agentcore.server.domain.ProviderWithModels;
import com.agentcore.server.domain.UpdateProviderCommand;
import com.agentcore.server.domain.UserModelDefault;
import com.agentcore.server.service.ModelProviderService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ModelProviderServiceImpl implements ModelProviderService {

    private final ModelProviderDao providerDao;
    private final ProviderModelDao modelDao;
    private final UserModelDefaultDao defaultDao;
    private final ApiKeyCipher cipher;
    private final ModelProperties modelProperties;
    private final GatewayClient client;
    private final ObjectMapper objectMapper;

    public ModelProviderServiceImpl(
            ModelProviderDao providerDao,
            ProviderModelDao modelDao,
            UserModelDefaultDao defaultDao,
            ApiKeyCipher cipher,
            ModelProperties modelProperties,
            GatewayClient client,
            ObjectMapper objectMapper) {
        this.providerDao = providerDao;
        this.modelDao = modelDao;
        this.defaultDao = defaultDao;
        this.cipher = cipher;
        this.modelProperties = modelProperties;
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @Override
    public ModelCatalog catalog(long userId) {
        List<ModelProvider> providers = providerDao.selectList(
                new LambdaQueryWrapper<ModelProvider>()
                        .eq(ModelProvider::getUserId, userId)
                        .orderByAsc(ModelProvider::getId));
        List<ProviderWithModels> result = new ArrayList<>(providers.size());
        if (!providers.isEmpty()) {
            List<Long> providerIds = providers.stream().map(ModelProvider::getId).toList();
            List<ProviderModel> models = modelDao.selectList(
                    new LambdaQueryWrapper<ProviderModel>()
                            .in(ProviderModel::getProviderId, providerIds)
                            .orderByAsc(ProviderModel::getId));
            for (ModelProvider provider : providers) {
                List<ProviderModel> own = models.stream()
                        .filter((model) -> model.getProviderId().equals(provider.getId()))
                        .toList();
                result.add(new ProviderWithModels(provider, own));
            }
        }

        UserModelDefault stored = defaultDao.selectById(userId);
        return new ModelCatalog(
                result,
                stored == null ? null : stored.getModelId(),
                modelProperties.allowSystemDefault(),
                null);
    }

    @Override
    @Transactional
    public long create(long userId, CreateProviderCommand command) {
        ModelProvider provider = new ModelProvider();
        provider.setUserId(userId);
        provider.setName(command.name());
        provider.setApi(command.api());
        provider.setBaseUrl(command.baseUrl());
        provider.setApiKeyCipher(cipher.encrypt(userId, command.apiKey()));
        provider.setApiKeyHint(ApiKeyCipher.hint(command.apiKey()));
        providerDao.insert(provider);
        insertModels(provider.getId(), command.models());
        return provider.getId();
    }

    @Override
    @Transactional
    public void update(long userId, long providerId, UpdateProviderCommand command) {
        ModelProvider provider = ownedProvider(userId, providerId);
        if (command.name() != null) provider.setName(command.name());
        if (command.api() != null) provider.setApi(command.api());
        if (command.baseUrl() != null) provider.setBaseUrl(command.baseUrl());
        if (command.apiKey() != null && !command.apiKey().isBlank()) {
            provider.setApiKeyCipher(cipher.encrypt(userId, command.apiKey()));
            provider.setApiKeyHint(ApiKeyCipher.hint(command.apiKey()));
        }
        providerDao.updateById(provider);

        if (command.models() != null) {
            replaceModels(providerId, command.models());
        }
    }

    @Override
    @Transactional
    public void delete(long userId, long providerId) {
        int deleted = providerDao.delete(new LambdaQueryWrapper<ModelProvider>()
                .eq(ModelProvider::getId, providerId)
                .eq(ModelProvider::getUserId, userId));
        if (deleted == 0) {
            throw notFound();
        }
    }

    @Override
    @Transactional
    public void setDefault(long userId, Long modelId) {
        if (modelId == null) {
            defaultDao.deleteById(userId);
            return;
        }
        if (modelDao.findOwned(modelId, userId) == null) {
            throw notFound();
        }
        defaultDao.upsert(userId, modelId);
    }

    @Override
    public List<ProbeModel> probe(long userId, ProbeCommand command) {
        String api;
        String baseUrl;
        String apiKey;
        if (command.providerId() != null) {
            ModelProvider provider = ownedProvider(userId, command.providerId());
            api = command.api() == null || command.api().isBlank()
                    ? provider.getApi() : command.api();
            baseUrl = command.baseUrl() == null || command.baseUrl().isBlank()
                    ? provider.getBaseUrl() : command.baseUrl();
            if (command.apiKey() == null || command.apiKey().isBlank()) {
                try {
                    apiKey = cipher.decrypt(userId, provider.getApiKeyCipher());
                } catch (IllegalStateException ex) {
                    throw new ApiException(ErrorCode.INVALID_REQUEST,
                            "stored API key cannot be decrypted; re-enter it");
                }
            } else {
                apiKey = command.apiKey();
            }
        } else {
            api = command.api();
            baseUrl = command.baseUrl();
            apiKey = command.apiKey();
            if (api == null || baseUrl == null || apiKey == null) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "api, baseUrl and apiKey are required");
            }
        }
        return callGatewayProbe(api, baseUrl, apiKey);
    }

    private List<ProbeModel> callGatewayProbe(String api, String baseUrl, String apiKey) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("api", api);
        payload.put("baseUrl", baseUrl);
        payload.put("apiKey", apiKey);
        String body;
        try {
            body = objectMapper.writeValueAsString(payload);
        } catch (IOException ex) {
            throw new ApiException(ErrorCode.INTERNAL, "failed to serialize probe request", ex);
        }

        HttpRequest.Builder builder = client.request("/providers/probe")
                .timeout(Duration.ofSeconds(15))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));

        HttpResponse<String> response;
        try {
            response = client.send(builder.build());
        } catch (ApiException ex) {
            throw new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE,
                    "cannot reach model provider gateway", ex);
        }

        if (response.statusCode() == 200) {
            return parseProbeModels(response.body());
        }
        throw probeError(response.statusCode(), response.body());
    }

    private List<ProbeModel> parseProbeModels(String body) {
        try {
            JsonNode models = objectMapper.readTree(body).path("models");
            List<ProbeModel> result = new ArrayList<>();
            for (JsonNode model : models) {
                String id = model.path("id").asText("");
                if (!id.isEmpty()) {
                    result.add(new ProbeModel(id, model.path("name").asText(id)));
                }
            }
            return result;
        } catch (IOException ex) {
            throw new ApiException(ErrorCode.PROVIDER_PROBE_FAILED,
                    "invalid probe response from gateway", ex);
        }
    }

    private ApiException probeError(int status, String body) {
        GatewayClient.GatewayError error = client.errorBody(body);
        String code = error.code().isEmpty() ? "PROVIDER_PROBE_FAILED" : error.code();
        String message =
                error.message().isEmpty() ? "model provider probe failed" : error.message();
        ErrorCode mapped = switch (code) {
            case "PROVIDER_UNREACHABLE" -> ErrorCode.PROVIDER_UNREACHABLE;
            case "PROVIDER_AUTH_FAILED" -> ErrorCode.PROVIDER_AUTH_FAILED;
            case "INVALID_REQUEST" -> ErrorCode.INVALID_REQUEST;
            default -> ErrorCode.PROVIDER_PROBE_FAILED;
        };
        if (status == 400 && mapped != ErrorCode.INVALID_REQUEST) {
            mapped = ErrorCode.INVALID_REQUEST;
        }
        return new ApiException(mapped, message);
    }

    private ModelProvider ownedProvider(long userId, long providerId) {
        ModelProvider provider = providerDao.selectById(providerId);
        if (provider == null || !provider.getUserId().equals(userId)) {
            throw notFound();
        }
        return provider;
    }

    private void replaceModels(long providerId, List<ProviderModelSpec> specs) {
        Map<String, ProviderModelSpec> desired = new LinkedHashMap<>();
        for (ProviderModelSpec spec : specs) {
            desired.put(spec.modelId(), spec);
        }
        List<ProviderModel> existing = modelDao.selectList(
                new LambdaQueryWrapper<ProviderModel>()
                        .eq(ProviderModel::getProviderId, providerId));
        Map<String, ProviderModel> byModelId = new LinkedHashMap<>();
        for (ProviderModel model : existing) {
            byModelId.put(model.getModelId(), model);
        }
        for (ProviderModelSpec spec : desired.values()) {
            ProviderModel model = byModelId.remove(spec.modelId());
            if (model == null) {
                modelDao.insert(toEntity(providerId, spec));
                continue;
            }
            model.setDisplayName(spec.displayName());
            model.setContextWindow(spec.contextWindow());
            model.setSupportsReasoning(spec.reasoning());
            model.setSupportsVision(spec.vision());
            modelDao.updateById(model);
        }
        for (ProviderModel removed : byModelId.values()) {
            modelDao.deleteById(removed.getId());
        }
    }

    private void insertModels(long providerId, List<ProviderModelSpec> specs) {
        if (specs == null) return;
        Map<String, ProviderModelSpec> unique = new LinkedHashMap<>();
        for (ProviderModelSpec spec : specs) {
            unique.put(spec.modelId(), spec);
        }
        for (ProviderModelSpec spec : unique.values()) {
            modelDao.insert(toEntity(providerId, spec));
        }
    }

    private static ProviderModel toEntity(long providerId, ProviderModelSpec spec) {
        ProviderModel model = new ProviderModel();
        model.setProviderId(providerId);
        model.setModelId(spec.modelId());
        model.setDisplayName(spec.displayName());
        model.setContextWindow(spec.contextWindow());
        model.setSupportsReasoning(spec.reasoning());
        model.setSupportsVision(spec.vision());
        return model;
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND, "provider or model not found");
    }
}
