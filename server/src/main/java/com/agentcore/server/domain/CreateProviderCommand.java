package com.agentcore.server.domain;

import java.util.List;

public record CreateProviderCommand(
        String name, String api, String baseUrl, String apiKey, List<ProviderModelSpec> models) {}
