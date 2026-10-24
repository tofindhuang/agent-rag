package com.agentcore.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentcore.server.common.identity.ToolIdentity;
import com.agentcore.server.common.sandbox.CancellationToken;
import com.agentcore.server.common.sandbox.SandboxEventSink;
import com.agentcore.server.common.sandbox.SandboxExecResult;
import com.agentcore.server.config.SandboxProperties;
import com.agentcore.server.dao.ExecRecordDao;
import com.agentcore.server.dao.SessionDao;
import com.agentcore.server.domain.ExecRecord;
import com.agentcore.server.domain.Session;
import com.agentcore.server.dto.sandbox.SandboxExecRequest;
import com.agentcore.server.dto.sandbox.SandboxExecution;
import com.agentcore.server.support.AbstractIntegrationTest;
import com.agentcore.server.support.AuthTestCalls;
import com.github.dockerjava.api.DockerClient;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Real Docker coverage for FR-3 AC1–AC3. Skips when the daemon or the
 * {@code agent-core-sandbox:python-3.12} image is missing.
 */
@Tag("sandbox")
@AutoConfigureMockMvc
class SandboxDockerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private SandboxService sandboxService;
    @Autowired
    private ExecRecordDao execRecordDao;
    @Autowired
    private SessionDao sessionDao;
    @Autowired
    private DockerClient docker;
    @Autowired
    private SandboxProperties properties;
    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void requireSandboxRuntime() {
        boolean available;
        try {
            docker.pingCmd().exec();
            docker.inspectImageCmd(properties.image()).exec();
            available = true;
        } catch (RuntimeException ex) {
            available = false;
        }
        Assumptions.assumeTrue(available, "docker or sandbox image unavailable");
    }

    @Test
    void networkRequestsFailInsideTheSandbox() {
        String code = """
                import requests
                try:
                    requests.get("http://example.com", timeout=2)
                    print("REACHED")
                except Exception as exc:
                    print("BLOCKED", type(exc).__name__)
                """;
        RecordingSink sink = new RecordingSink();
        SandboxExecution execution = start(code, 10_000);

        sandboxService.run(execution, sink, new CancellationToken());

        assertThat(sink.result).isNotNull();
        assertThat(sink.result.exitCode()).isZero();
        assertThat(sink.stdout()).contains("BLOCKED").doesNotContain("REACHED");

        ExecRecord row = execRecordDao.findById(execution.execId());
        assertThat(row.getStatus()).isEqualTo("succeeded");
        assertThat(row.getCode()).isEqualTo(code);
        assertThat(row.getPeakMemoryBytes()).isNotNull().isPositive();
        assertThat(row.getDurationMs()).isNotNull();
        assertThat(row.getStdout()).contains("BLOCKED");
    }

    @Test
    void runawayLoopIsKilledAtTimeoutAndContainerIsRemoved() {
        RecordingSink sink = new RecordingSink();
        SandboxExecution execution = start("while True:\n    pass\n", 1_000);

        sandboxService.run(execution, sink, new CancellationToken());

        assertThat(sink.result).isNotNull();
        assertThat(sink.result.timedOut()).isTrue();
        assertThat(sink.result.exitCode()).isEqualTo(137);
        assertThat(sink.result.durationMs()).isBetween(900L, 20_000L);

        ExecRecord row = execRecordDao.findById(execution.execId());
        assertThat(row.getStatus()).isEqualTo("timed_out");
        assertThat(row.getTimedOut()).isTrue();

        var remaining = docker.listContainersCmd()
                .withShowAll(true)
                .withLabelFilter(Map.of("agent-core.exec-id", execution.execId()))
                .exec();
        assertThat(remaining).isEmpty();
    }

    @Test
    @EnabledIfSystemProperty(named = "sandbox.oomIT", matches = "true")
    void oomKilledProcessIsReportedInTheAuditRow() {
        RecordingSink sink = new RecordingSink();
        SandboxExecution execution = start("x = bytearray(800 * 1024 * 1024)\nprint(len(x))\n", 10_000);

        sandboxService.run(execution, sink, new CancellationToken());

        assertThat(sink.result).isNotNull();
        assertThat(sink.result.exitCode()).isEqualTo(137);
        assertThat(sink.result.timedOut()).isFalse();
        assertThat(sink.stderr()).contains("out of memory");
        ExecRecord row = execRecordDao.findById(execution.execId());
        assertThat(row.getOomKilled()).isTrue();
        assertThat(row.getStatus()).isEqualTo("failed");
    }

    @Test
    void containerRunsAsNonRootWithReadonlyRootfsAndWritableWorkDir() {
        String code = """
                import os
                print("uid", os.getuid())
                try:
                    open("/rootfs-probe", "w").write("x")
                    print("ROOTFS_WRITABLE")
                except OSError:
                    print("ROOTFS_READONLY")
                open("/work/probe", "w").write("ok")
                print("WORK_WRITABLE")
                """;
        RecordingSink sink = new RecordingSink();
        SandboxExecution execution = start(code, 10_000);

        sandboxService.run(execution, sink, new CancellationToken());

        assertThat(sink.stdout())
                .contains("uid 1000")
                .contains("ROOTFS_READONLY")
                .contains("WORK_WRITABLE")
                .doesNotContain("ROOTFS_WRITABLE");
    }

    private SandboxExecution start(String code, int timeoutMs) {
        long userId = registerUser();
        String sessionId = createSession(userId);
        ToolIdentity identity = new ToolIdentity(
                String.valueOf(userId),
                sessionId,
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString());
        return sandboxService.start(
                new SandboxExecRequest("python", code, timeoutMs), identity);
    }

    private long registerUser() {
        try {
            String email = "sandbox-" + UUID.randomUUID() + "@example.com";
            return AuthTestCalls.register(mockMvc, email, "correct-horse");
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String createSession(long userId) {
        Session session = new Session();
        session.setId(UUID.randomUUID().toString());
        session.setUserId(userId);
        session.setTitle("sandbox test");
        session.setStatus("active");
        session.setLastActiveAt(LocalDateTime.now(ZoneOffset.UTC));
        sessionDao.insert(session);
        return session.getId();
    }

    private static final class RecordingSink implements SandboxEventSink {

        private final List<String> stdout = new ArrayList<>();
        private final List<String> stderr = new ArrayList<>();
        private SandboxExecResult result;

        @Override
        public void started(String execId) {}

        @Override
        public void phase(String phase) {}

        @Override
        public void stdout(String chunk) {
            stdout.add(chunk);
        }

        @Override
        public void stderr(String chunk) {
            stderr.add(chunk);
        }

        @Override
        public void result(SandboxExecResult result) {
            this.result = result;
        }

        String stdout() {
            return String.join("", stdout);
        }

        @SuppressWarnings("unused")
        String stderr() {
            return String.join("", stderr);
        }
    }
}
