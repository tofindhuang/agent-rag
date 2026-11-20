package com.agentcore.server.common.sandbox;

import com.agentcore.server.config.SandboxProperties;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.HostConfig;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * docker-java adapter for the sandbox container port (ADR-0026); wired by
 * {@code SandboxConfiguration}.
 *
 * <p>Pool containers and execution containers are created from the same
 * HostConfig, so warming a container never weakens isolation. The only
 * differences are the labels and the fact that pool containers are unused until
 * checkout.
 */
public class DockerSandboxContainerFactory implements SandboxContainerFactory {

    static final String SANDBOX_LABEL = "agent-core.sandbox";
    static final String ROLE_LABEL = "agent-core.sandbox.role";
    static final String POOL_ROLE = "pool";
    static final String EXEC_ID_LABEL = "agent-core.exec-id";

    private static final Logger log =
            LoggerFactory.getLogger(DockerSandboxContainerFactory.class);
    private static final long MEMORY_BYTES = 512L * 1024 * 1024;
    private static final long NANO_CPUS = 1_000_000_000L;
    private static final long PIDS_LIMIT = 64L;
    private static final String IDLE_COMMAND = "import time; time.sleep(86400)";
    private static final String USER = "1000:1000";
    private static final String WORK_TMPFS = "size=64m,uid=1000,gid=1000";

    private final DockerClient docker;
    private final SandboxProperties properties;

    public DockerSandboxContainerFactory(DockerClient docker, SandboxProperties properties) {
        this.docker = docker;
        this.properties = properties;
    }

    @Override
    public String createPoolContainer() {
        return createAndStart(Map.of(SANDBOX_LABEL, "1", ROLE_LABEL, POOL_ROLE));
    }

    @Override
    public String createExecutionContainer(String execId) {
        return createAndStart(Map.of(SANDBOX_LABEL, "1", EXEC_ID_LABEL, execId));
    }

    @Override
    public boolean isRunning(String containerId) {
        try {
            InspectContainerResponse response = docker.inspectContainerCmd(containerId).exec();
            return Boolean.TRUE.equals(response.getState().getRunning());
        } catch (RuntimeException ex) {
            log.trace("sandbox container {} is not inspectable", containerId, ex);
            return false;
        }
    }

    @Override
    public void remove(String containerId) {
        docker.removeContainerCmd(containerId)
                .withForce(true)
                .withRemoveVolumes(true)
                .exec();
    }

    @Override
    public String currentImageId() {
        return docker.inspectImageCmd(properties.image()).exec().getId();
    }

    @Override
    public List<String> listPoolContainers() {
        List<Container> containers = docker.listContainersCmd()
                .withShowAll(true)
                .withLabelFilter(Map.of(SANDBOX_LABEL, "1", ROLE_LABEL, POOL_ROLE))
                .exec();
        return containers.stream().map(Container::getId).toList();
    }

    private String createAndStart(Map<String, String> labels) {
        CreateContainerResponse created = docker.createContainerCmd(properties.image())
                .withCmd("python", "-c", IDLE_COMMAND)
                .withUser(USER)
                .withWorkingDir("/work")
                .withAttachStdout(true)
                .withAttachStderr(true)
                .withTty(false)
                .withLabels(labels)
                .withHostConfig(hostConfig())
                .exec();
        String containerId = created.getId();
        docker.startContainerCmd(containerId).exec();
        return containerId;
    }

    private static HostConfig hostConfig() {
        return new HostConfig()
                .withNetworkMode("none")
                .withMemory(MEMORY_BYTES)
                .withMemorySwap(MEMORY_BYTES)
                .withNanoCPUs(NANO_CPUS)
                .withPidsLimit(PIDS_LIMIT)
                .withReadonlyRootfs(true)
                .withTmpFs(Map.of("/work", WORK_TMPFS))
                .withCapDrop(Capability.ALL)
                .withSecurityOpts(List.of("no-new-privileges"))
                .withInit(true);
    }
}
