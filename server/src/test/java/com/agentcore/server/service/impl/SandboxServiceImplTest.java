package com.agentcore.server.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.common.identity.ToolIdentity;
import com.agentcore.server.common.sandbox.CancellationToken;
import com.agentcore.server.common.sandbox.SandboxContainerRunner;
import com.agentcore.server.common.sandbox.SandboxEventSink;
import com.agentcore.server.common.sandbox.SandboxExecResult;
import com.agentcore.server.common.sandbox.SandboxRunOutcome;
import com.agentcore.server.common.sandbox.SandboxRunSpec;
import com.agentcore.server.common.sandbox.SandboxUnavailableException;
import com.agentcore.server.config.SandboxProperties;
import com.agentcore.server.dao.ExecRecordDao;
import com.agentcore.server.domain.ExecRecord;
import com.agentcore.server.dto.sandbox.SandboxExecRequest;
import com.agentcore.server.dto.sandbox.SandboxExecution;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SandboxServiceImplTest {

    private final InMemoryExecRecordDao records = new InMemoryExecRecordDao();
    private final RecordingSink sink = new RecordingSink();
    private final FakeRunner runner = new FakeRunner();
    private final SandboxProperties properties = SandboxProperties.defaults();
    private final SandboxServiceImpl service = new SandboxServiceImpl(properties, runner, records);
    private final ToolIdentity identity =
            new ToolIdentity("42", "session-1", "turn-1", "run-1", "call-1", "req-1");

    @Test
    void startDefaultsLanguageAndTimeoutAndWritesRunningAuditRow() {
        SandboxExecution execution = service.start(new SandboxExecRequest(null, "print(1)", null), identity);

        assertThat(execution.language()).isEqualTo("python");
        assertThat(execution.timeoutMs()).isEqualTo(30_000);
        assertThat(execution.userId()).isEqualTo(42L);

        ExecRecord row = records.findById(execution.execId());
        assertThat(row.getStatus()).isEqualTo("running");
        assertThat(row.getCode()).isEqualTo("print(1)");
        assertThat(row.getSessionId()).isEqualTo("session-1");
        assertThat(row.getTurnId()).isEqualTo("turn-1");
        assertThat(row.getRunId()).isEqualTo("run-1");
        assertThat(row.getToolCallId()).isEqualTo("call-1");
        assertThat(row.getImage()).isEqualTo("agent-core-sandbox:python-3.12");
    }

    @Test
    void startClampsTimeoutToConfiguredBounds() {
        assertThat(service.start(new SandboxExecRequest("python", "pass", 500), identity).timeoutMs())
                .isEqualTo(1_000);
        assertThat(service.start(new SandboxExecRequest("python", "pass", 90_000), identity).timeoutMs())
                .isEqualTo(60_000);
    }

    @Test
    void startRejectsUnsupportedLanguage() {
        assertThatThrownBy(() ->
                        service.start(new SandboxExecRequest("javascript", "console.log(1)", null), identity))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo(ErrorCode.INVALID_REQUEST));
        assertThat(records.rows).isEmpty();
    }

    @Test
    void startRejectsOversizedCode() {
        SandboxProperties tiny = new SandboxProperties(
                null, null, null, null, 4, null, null, null);
        SandboxServiceImpl strict = new SandboxServiceImpl(tiny, runner, records);

        assertThatThrownBy(() ->
                        strict.start(new SandboxExecRequest("python", "12345", null), identity))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo(ErrorCode.SANDBOX_CODE_TOO_LARGE));
        assertThat(records.rows).isEmpty();
    }

    @Test
    void startRejectsInvalidUserIdHeader() {
        ToolIdentity broken = new ToolIdentity("abc", "s", "t", "r", "c", "q");
        assertThatThrownBy(() -> service.start(new SandboxExecRequest("python", "pass", null), broken))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    void startFailsClosedWhenDockerOrImageIsUnavailable() {
        runner.available = false;

        assertThatThrownBy(() ->
                        service.start(new SandboxExecRequest("python", "pass", null), identity))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo(ErrorCode.SANDBOX_UNAVAILABLE));
        assertThat(records.rows).isEmpty();
    }

    @Test
    void startFailsClosedWhenAuditInsertFails() {
        records.failInsert = true;

        assertThatThrownBy(() ->
                        service.start(new SandboxExecRequest("python", "pass", null), identity))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo(ErrorCode.SANDBOX_UNAVAILABLE));
    }

    @Test
    void runStreamsFramesAndSettlesSucceededAudit() {
        runner.outcome = outcome(0, false, false, false, "hello\n", "", 12_345_678L, 3_000L);
        SandboxExecution execution = service.start(
                new SandboxExecRequest("python", "print('hello')", null), identity);

        service.run(execution, sink, new CancellationToken());

        assertThat(sink.events).containsExactly("started", "phase", "phase", "result");
        assertThat(sink.phases).containsExactly("starting", "running");
        assertThat(sink.result).isEqualTo(new SandboxExecResult(0, 123, false, false));

        ExecRecord row = records.findById(execution.execId());
        assertThat(row.getStatus()).isEqualTo("succeeded");
        assertThat(row.getExitCode()).isZero();
        assertThat(row.getStdout()).isEqualTo("hello\n");
        assertThat(row.getPeakMemoryBytes()).isEqualTo(12_345_678L);
        assertThat(row.getCpuTimeMs()).isEqualTo(3_000L);
        assertThat(row.getStartedAt()).isNotNull();
        assertThat(row.getFinishedAt()).isNotNull();
    }

    @Test
    void runMarksTimedOutExecution() {
        runner.outcome = outcome(137, true, false, false, "", "", null, null);
        SandboxExecution execution = service.start(
                new SandboxExecRequest("python", "while True: pass", 1_000), identity);

        service.run(execution, sink, new CancellationToken());

        assertThat(records.findById(execution.execId()).getStatus()).isEqualTo("timed_out");
        assertThat(sink.result.timedOut()).isTrue();
    }

    @Test
    void runMarksOomAsFailed() {
        runner.outcome = outcome(137, false, false, true, "", "container killed: out of memory\n", null, null);
        SandboxExecution execution = service.start(
                new SandboxExecRequest("python", "x = bytearray(1024**3)", null), identity);

        service.run(execution, sink, new CancellationToken());

        ExecRecord row = records.findById(execution.execId());
        assertThat(row.getStatus()).isEqualTo("failed");
        assertThat(row.getOomKilled()).isTrue();
        assertThat(sink.result.exitCode()).isEqualTo(137);
    }

    @Test
    void runMarksNonZeroExitAsFailed() {
        runner.outcome = outcome(2, false, false, false, "", "boom\n", null, null);
        SandboxExecution execution = service.start(
                new SandboxExecRequest("python", "raise SystemExit(2)", null), identity);

        service.run(execution, sink, new CancellationToken());

        assertThat(records.findById(execution.execId()).getStatus()).isEqualTo("failed");
        assertThat(sink.result.exitCode()).isEqualTo(2);
    }

    @Test
    void runTurnsInfrastructureFailureIntoErrorResult() {
        runner.failure = new SandboxUnavailableException("docker went away");
        SandboxExecution execution = service.start(
                new SandboxExecRequest("python", "print(1)", null), identity);

        service.run(execution, sink, new CancellationToken());

        ExecRecord row = records.findById(execution.execId());
        assertThat(row.getStatus()).isEqualTo("error");
        assertThat(row.getErrorMessage()).contains("docker went away");
        assertThat(sink.stderrText).anyMatch(text -> text.contains("sandbox unavailable"));
        assertThat(sink.result.exitCode()).isEqualTo(-1);
    }

    @Test
    void runSkipsResultFrameWhenClientDisconnects() {
        runner.duringRun = cancellation -> cancellation.cancel();
        SandboxExecution execution = service.start(
                new SandboxExecRequest("python", "print(1)", null), identity);

        service.run(execution, sink, new CancellationToken());

        assertThat(records.findById(execution.execId()).getStatus()).isEqualTo("aborted");
        assertThat(sink.events).doesNotContain("result");
    }

    private static SandboxRunOutcome outcome(
            int exitCode,
            boolean timedOut,
            boolean truncated,
            boolean oom,
            String stdout,
            String stderr,
            Long peakMemory,
            Long cpuTime) {
        return new SandboxRunOutcome(
                exitCode, 123, timedOut, truncated, oom, peakMemory, cpuTime, stdout, stderr);
    }

    private static final class FakeRunner implements SandboxContainerRunner {

        private boolean available = true;
        private SandboxRunOutcome outcome =
                new SandboxRunOutcome(0, 10, false, false, false, null, null, "", "");
        private RuntimeException failure;
        private java.util.function.Consumer<CancellationToken> duringRun;

        @Override
        public void ensureAvailable() {
            if (!available) {
                throw new SandboxUnavailableException("docker daemon or image unavailable");
            }
        }

        @Override
        public SandboxRunOutcome run(
                SandboxRunSpec spec, SandboxEventSink sink, CancellationToken cancellation) {
            sink.phase("running");
            if (duringRun != null) {
                duringRun.accept(cancellation);
            }
            if (failure != null) {
                throw failure;
            }
            return outcome;
        }
    }

    private static final class RecordingSink implements SandboxEventSink {

        private final List<String> events = new ArrayList<>();
        private final List<String> phases = new ArrayList<>();
        private final List<String> stderrText = new ArrayList<>();
        private SandboxExecResult result;

        @Override
        public void started(String execId) {
            events.add("started");
        }

        @Override
        public void phase(String phase) {
            events.add("phase");
            phases.add(phase);
        }

        @Override
        public void stdout(String chunk) {
            events.add("stdout");
        }

        @Override
        public void stderr(String chunk) {
            events.add("stderr");
            stderrText.add(chunk);
        }

        @Override
        public void result(SandboxExecResult result) {
            events.add("result");
            this.result = result;
        }
    }

    private static final class InMemoryExecRecordDao implements ExecRecordDao {

        private final Map<String, ExecRecord> rows = new LinkedHashMap<>();
        private boolean failInsert;

        @Override
        public int insert(ExecRecord record) {
            if (failInsert) {
                throw new IllegalStateException("database down");
            }
            rows.put(record.getId(), record);
            return 1;
        }

        @Override
        public int markStarted(String id, LocalDateTime startedAt) {
            ExecRecord row = rows.get(id);
            if (row != null) {
                row.setStartedAt(startedAt);
            }
            return 1;
        }

        @Override
        public int settle(
                String id,
                String status,
                Integer exitCode,
                Integer durationMs,
                boolean timedOut,
                boolean truncated,
                boolean oomKilled,
                Long peakMemoryBytes,
                Long cpuTimeMs,
                String stdout,
                String stderr,
                String errorMessage,
                LocalDateTime finishedAt) {
            ExecRecord row = rows.get(id);
            row.setStatus(status);
            row.setExitCode(exitCode);
            row.setDurationMs(durationMs);
            row.setTimedOut(timedOut);
            row.setTruncated(truncated);
            row.setOomKilled(oomKilled);
            row.setPeakMemoryBytes(peakMemoryBytes);
            row.setCpuTimeMs(cpuTimeMs);
            row.setStdout(stdout);
            row.setStderr(stderr);
            row.setErrorMessage(errorMessage);
            row.setFinishedAt(finishedAt);
            return 1;
        }

        @Override
        public ExecRecord findById(String id) {
            return rows.get(id);
        }
    }
}
