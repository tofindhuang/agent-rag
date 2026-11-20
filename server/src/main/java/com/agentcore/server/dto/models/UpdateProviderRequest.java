package com.agentcore.server.dto.models;

import java.util.List;

public record UpdateProviderRequest(
        String name, String api, String baseUrl, String apiKey, List<ProviderModelInput> models) {}
