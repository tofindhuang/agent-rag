package com.agentcore.server.common.sandbox;

import java.util.List;

/**
 * Port for sandbox container lifecycle (ADR-0026). The pool manager and the
 * execution runner only depend on this interface; the docker-java adapter and
 * test fakes are interchangeable.
 */
public interface SandboxContainerFactory {

    /**
     * Creates and starts an idle pool container with the same isolation as an
     * execution container, labeled {@code agent-core.sandbox.role=pool}.
     */
    String createPoolContainer();

    /** Creates and starts the one-shot container for a cold execution path. */
    String createExecutionContainer(String execId);

    /** Health check before handing a pool container to an execution. */
    boolean isRunning(String containerId);

    /** Force-removes the container and its volumes. */
    void remove(String containerId);

    /** Image ID currently backing the configured image tag. */
    String currentImageId();

    /** All container IDs labeled as pool containers. */
    List<String> listPoolContainers();
}
