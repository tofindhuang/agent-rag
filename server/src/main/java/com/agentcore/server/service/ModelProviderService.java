package com.agentcore.server.service;

import com.agentcore.server.domain.CreateProviderCommand;
import com.agentcore.server.domain.ModelCatalog;
import com.agentcore.server.domain.ProbeCommand;
import com.agentcore.server.domain.ProbeModel;
import com.agentcore.server.domain.UpdateProviderCommand;
import java.util.List;

/** Model provider and default-model use cases (ADR-0010). */
public interface ModelProviderService {

    ModelCatalog catalog(long userId);

    long create(long userId, CreateProviderCommand command);

    void update(long userId, long providerId, UpdateProviderCommand command);

    void delete(long userId, long providerId);

    void setDefault(long userId, Long modelId);

    /** Probes a new or existing provider through the gateway. */
    List<ProbeModel> probe(long userId, ProbeCommand command);
}
