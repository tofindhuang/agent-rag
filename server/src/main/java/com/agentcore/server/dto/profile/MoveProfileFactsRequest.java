package com.agentcore.server.dto.profile;

import java.util.List;

public record MoveProfileFactsRequest(List<String> factIds, String collectionId) {}
