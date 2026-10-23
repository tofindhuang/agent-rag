package com.agentcore.server.controller;

import com.agentcore.server.common.auth.AuthenticatedUser;
import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.domain.CreateProviderCommand;
import com.agentcore.server.domain.ModelCatalog;
import com.agentcore.server.domain.ProbeCommand;
import com.agentcore.server.domain.ProviderModel;
import com.agentcore.server.domain.ProviderModelSpec;
import com.agentcore.server.domain.ProviderWithModels;
import com.agentcore.server.domain.UpdateProviderCommand;
import com.agentcore.server.dto.models.CreateProviderRequest;
import com.agentcore.server.dto.models.CreateProviderResponse;
import com.agentcore.server.dto.models.ModelCatalogResponse;
import com.agentcore.server.dto.models.ProbeModelView;
import com.agentcore.server.dto.models.ProbeRequest;
import com.agentcore.server.dto.models.ProbeResponse;
import com.agentcore.server.dto.models.ProviderModelInput;
import com.agentcore.server.dto.models.ProviderModelView;
import com.agentcore.server.dto.models.ProviderView;
import com.agentcore.server.dto.models.SetDefaultRequest;
import com.agentcore.server.dto.models.SystemDefaultView;
import com.agentcore.server.dto.models.UpdateProviderRequest;
import com.agentcore.server.service.ModelProviderService;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** User-supplied model providers, enabled models and the default model. */
@RestController
@RequestMapping("/api/models")
public class ModelController {

    private static final Set<String> APIS = Set.of("openai-completions", "anthropic-messages");
    private static final int MAX_NAME = 100;
    private static final int MAX_URL = 1_000;
    private static final int MAX_KEY = 4_096;
    private static final int MAX_MODEL_ID = 200;
    private static final int MAX_MODELS = 200;
    private static final int MAX_CONTEXT_WINDOW = 10_000_000;

    private final ModelProviderService modelProviderService;

    public ModelController(ModelProviderService modelProviderService) {
        this.modelProviderService = modelProviderService;
    }

    @GetMapping
    public ModelCatalogResponse catalog(@AuthenticationPrincipal AuthenticatedUser user) {
        ModelCatalog catalog = modelProviderService.catalog(user.userId());
        return new ModelCatalogResponse(
                catalog.providers().stream().map(ModelController::toProviderView).toList(),
                catalog.defaultModelId(),
                new SystemDefaultView(
                        catalog.systemDefaultAvailable(), catalog.systemDefaultLabel()));
    }

    @GetMapping("/providers")
    public List<ProviderView> providers(@AuthenticationPrincipal AuthenticatedUser user) {
        return modelProviderService.catalog(user.userId()).providers().stream()
                .map(ModelController::toProviderView)
                .toList();
    }

    @PostMapping("/providers")
    @ResponseStatus(HttpStatus.CREATED)
    public CreateProviderResponse create(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody CreateProviderRequest request) {
        validateName(request.name());
        validateApi(request.api());
        validateUrl(request.baseUrl());
        validateKey(request.apiKey(), true);
        List<ProviderModelSpec> models = toSpecs(request.models());

        long id = modelProviderService.create(user.userId(), new CreateProviderCommand(
                request.name().trim(),
                request.api(),
                request.baseUrl().trim(),
                request.apiKey(),
                models));
        return new CreateProviderResponse(id);
    }

    @PatchMapping("/providers/{providerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void update(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long providerId,
            @RequestBody UpdateProviderRequest request) {
        if (request.name() != null) validateName(request.name());
        if (request.api() != null) validateApi(request.api());
        if (request.baseUrl() != null) validateUrl(request.baseUrl());
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            validateKey(request.apiKey(), true);
        }
        List<ProviderModelSpec> models =
                request.models() == null ? null : toSpecs(request.models());

        modelProviderService.update(user.userId(), providerId, new UpdateProviderCommand(
                request.name() == null ? null : request.name().trim(),
                request.api(),
                request.baseUrl() == null ? null : request.baseUrl().trim(),
                request.apiKey(),
                models));
    }

    @DeleteMapping("/providers/{providerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable long providerId) {
        modelProviderService.delete(user.userId(), providerId);
    }

    @PutMapping("/default")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setDefault(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody SetDefaultRequest request) {
        modelProviderService.setDefault(user.userId(), request.modelId());
    }

    @PostMapping("/probe")
    public ProbeResponse probe(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody ProbeRequest request) {
        if (request.providerId() == null) {
            validateApi(request.api());
            validateUrl(request.baseUrl());
            validateKey(request.apiKey(), true);
        } else {
            if (request.api() != null && !request.api().isBlank()) validateApi(request.api());
            if (request.baseUrl() != null && !request.baseUrl().isBlank()) {
                validateUrl(request.baseUrl());
            }
            if (request.apiKey() != null && !request.apiKey().isBlank()) {
                validateKey(request.apiKey(), true);
            }
        }

        return new ProbeResponse(modelProviderService.probe(user.userId(), new ProbeCommand(
                        request.api(), request.baseUrl(), request.apiKey(), request.providerId()))
                .stream()
                .map((model) -> new ProbeModelView(model.id(), model.name()))
                .toList());
    }

    private static ProviderView toProviderView(ProviderWithModels value) {
        return new ProviderView(
                value.provider().getId(),
                value.provider().getName(),
                value.provider().getApi(),
                value.provider().getBaseUrl(),
                value.provider().getApiKeyHint(),
                value.models().stream().map(ModelController::toModelView).toList());
    }

    private static ProviderModelView toModelView(ProviderModel model) {
        return new ProviderModelView(
                model.getId(),
                model.getModelId(),
                model.getDisplayName(),
                model.getContextWindow(),
                Boolean.TRUE.equals(model.getSupportsReasoning()),
                Boolean.TRUE.equals(model.getSupportsVision()));
    }

    private static List<ProviderModelSpec> toSpecs(List<ProviderModelInput> inputs) {
        if (inputs == null) return List.of();
        if (inputs.size() > MAX_MODELS) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "too many models");
        }
        return inputs.stream()
                .map((input) -> {
                    String modelId = input.modelId() == null ? "" : input.modelId().trim();
                    if (modelId.isEmpty() || modelId.length() > MAX_MODEL_ID) {
                        throw new ApiException(ErrorCode.INVALID_REQUEST, "invalid modelId");
                    }
                    String displayName = input.displayName() == null || input.displayName().isBlank()
                            ? modelId : input.displayName().trim();
                    if (displayName.length() > MAX_NAME) {
                        throw new ApiException(ErrorCode.INVALID_REQUEST, "invalid displayName");
                    }
                    Integer contextWindow = input.contextWindow();
                    if (contextWindow != null
                            && (contextWindow < 1 || contextWindow > MAX_CONTEXT_WINDOW)) {
                        throw new ApiException(ErrorCode.INVALID_REQUEST, "invalid contextWindow");
                    }
                    return new ProviderModelSpec(
                            modelId,
                            displayName,
                            contextWindow,
                            Boolean.TRUE.equals(input.reasoning()),
                            Boolean.TRUE.equals(input.vision()));
                })
                .toList();
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank() || name.trim().length() > MAX_NAME) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "invalid provider name");
        }
    }

    private static void validateApi(String api) {
        if (api == null || !APIS.contains(api)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "invalid provider api");
        }
    }

    private static void validateUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank() || baseUrl.trim().length() > MAX_URL) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "invalid baseUrl");
        }
    }

    private static void validateKey(String apiKey, boolean required) {
        if (required && (apiKey == null || apiKey.isBlank())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "apiKey is required");
        }
        if (apiKey != null && apiKey.length() > MAX_KEY) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "apiKey is too long");
        }
    }
}
