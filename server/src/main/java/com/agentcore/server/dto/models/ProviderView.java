package com.agentcore.server.dto.models;

import java.util.List;

public record ProviderView(
        long id,
        String name,
        String api,
        String baseUrl,
        String apiKeyHint,
        List<ProviderModelView> models) {}
