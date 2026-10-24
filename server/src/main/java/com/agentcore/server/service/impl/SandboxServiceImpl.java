package com.agentcore.server.service.impl;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.common.identity.ToolIdentity;
import com.agentcore.server.common.sandbox.CancellationToken;
import com.agentcore.server.common.sandbox.SandboxAdmission;
import com.agentcore.server.common.sandbox.SandboxContainerRunner;
import com.agentcore.server.common.sandbox.SandboxEventSink;
import com.agentcore.server.common.sandbox.SandboxExecResult;
import com.agentcore.server.common.sandbox.SandboxRunOutcome;
import com.agentcore.server.common.sandbox.SandboxPermit;
import com.agentcore.server.common.sandbox.SandboxRunSpec;
import com.agentcore.server.common.sandbox.SandboxUnavailableException;
import com.agentcore.server.config.SandboxProperties;
import com.agentcore.server.dao.ExecRecordDao;
import com.agentcore.server.domain.ExecRecord;
import com.agentcore.server.dto.sandbox.SandboxExecRequest;
import com.agentcore.server.dto.sandbox.SandboxExecution;
import com.agentcore.server.service.SandboxService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * run_code orchestration (FR-3): validate and audit before the stream starts,
 * then delegate the container work to the runner and settle the audit row.
 */
@Service
public class SandboxServiceImpl implements SandboxService {

    private static final Logger log = LoggerFactory.getLogger(SandboxServiceImpl.class);
    private static final String LANGUAGE_PYTHON = "python";
    private static final int MIN_TIMEOUT_MS = 1_000;

    private final SandboxProperties properties;
    private final SandboxContainerRunner runner;
    private final SandboxAdmission admission;
    private final ExecRecordDao recordDao;

    public SandboxServiceImpl(
            SandboxProperties properties,
            SandboxContainerRunner runner,
            SandboxAdmission admission,
            ExecRecordDao recordDao) {
        this.properties = properties;
        this.runner = runner;
        this.admission = admission;
        this.recordDao = recordDao;
    }

    @Override
    public SandboxExecution start(SandboxExecRequest request, ToolIdentity identity) {
        String language = normalizeLanguage(request.language());
        if (!LANGUAGE_PYTHON.equals(language)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "unsupported language; only python is supported");
        }
        String code = request.code();
        if (code == null || code.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "code is required");
        }
        if (code.getBytes(StandardCharsets.UTF_8).length > properties.codeMaxBytes()) {
            throw new ApiException(ErrorCode.SANDBOX_CODE_TOO_LARGE,
                    "code exceeds " + properties.codeMaxBytes() + " bytes");
        }
        long userId = parseUserId(identity.userId());
        int timeoutMs = clampTimeout(request.timeoutMs());

        try {
            runner.ensureAvailable();
        } catch (SandboxUnavailableException ex) {
            throw new ApiException(ErrorCode.SANDBOX_UNAVAILABLE, ex.getMessage(), ex);
        }

        SandboxPermit permit = admission.acquire(userId);
        String execId = UUID.randomUUID().toString();
        ExecRecord record = new ExecRecord();
        record.setId(execId);
        record.setUserId(userId);
        record.setSessionId(identity.sessionId());
        record.setTurnId(identity.turnId());
        record.setRunId(identity.runId());
        record.setToolCallId(identity.toolCallId());
        record.setRequestId(identity.requestId());
        record.setLanguage(language);
        record.setCode(code);
        record.setStatus("running");
        record.setImage(properties.image());
        record.setCreatedAt(now());
        try {
            recordDao.insert(record);
        } catch (RuntimeException ex) {
            permit.release();
            log.error("failed to insert exec audit row for {}", execId, ex);
            throw new ApiException(ErrorCode.SANDBOX_UNAVAILABLE,
                    "sandbox audit unavailable; retry later", ex);
        }
        return new SandboxExecution(
                execId,
                code,
                language,
                timeoutMs,
                userId,
                identity.sessionId(),
                identity.turnId(),
                identity.runId(),
                identity.toolCallId(),
                identity.requestId(),
                permit);
    }

    @Override
    public void run(
            SandboxExecution execution, SandboxEventSink sink, CancellationToken cancellation) {
        try {
            runExecution(execution, sink, cancellation);
        } finally {
            execution.permit().release();
        }
    }

    private void runExecution(
            SandboxExecution execution, SandboxEventSink sink, CancellationToken cancellation) {
        long startedNanos = System.nanoTime();
        sink.started(execution.execId());
        sink.phase("starting");
        if (cancellation.isCancelled()) {
            settleFailure(execution, "aborted", null, elapsedMs(startedNanos), "client disconnected");
            return;
        }
        markStartedQuietly(execution.execId());
        try {
            SandboxRunOutcome outcome = runner.run(
                    new SandboxRunSpec(execution.execId(), execution.code(), execution.timeoutMs()),
                    sink,
                    cancellation);
            if (cancellation.isCancelled()) {
                settleOutcome(execution, "aborted", outcome, "client disconnected");
                return;
            }
            String status = terminalStatus(outcome);
            settleOutcome(execution, status, outcome, null);
            sink.result(new SandboxExecResult(
                    outcome.exitCode(), outcome.durationMs(), outcome.timedOut(), outcome.truncated()));
        } catch (RuntimeException ex) {
            long durationMs = elapsedMs(startedNanos);
            String message = ex instanceof SandboxUnavailableException
                    ? "sandbox unavailable: " + ex.getMessage()
                    : "sandbox error: " + ex.getMessage();
            log.error("sandbox execution {} failed", execution.execId(), ex);
            if (cancellation.isCancelled()) {
                settleFailure(execution, "aborted", null, durationMs, "client disconnected");
                return;
            }
            settleFailure(execution, "error", -1, durationMs, message);
            sink.stderr(message);
            sink.result(new SandboxExecResult(-1, durationMs, false, false));
        }
    }

    @Override
    public void abandon(SandboxExecution execution, String reason) {
        try {
            settleFailure(execution, "error", -1, 0, reason);
        } finally {
            execution.permit().release();
        }
    }

    private static String terminalStatus(SandboxRunOutcome outcome) {
        if (outcome.timedOut()) {
            return "timed_out";
        }
        if (outcome.oomKilled()) {
            return "failed";
        }
        return outcome.exitCode() == 0 ? "succeeded" : "failed";
    }

    private void settleOutcome(
            SandboxExecution execution,
            String status,
            SandboxRunOutcome outcome,
            String errorMessage) {
        settle(execution, status, outcome.exitCode(), outcome.durationMs(),
                outcome.timedOut(), outcome.truncated(), outcome.oomKilled(),
                outcome.peakMemoryBytes(), outcome.cpuTimeMs(), outcome.stdout(),
                outcome.stderr(), errorMessage);
    }

    private void settleFailure(
            SandboxExecution execution,
            String status,
            Integer exitCode,
            long durationMs,
            String errorMessage) {
        settle(execution, status, exitCode, durationMs, false, false, false,
                null, null, null, null, errorMessage);
    }

    private void settle(
            SandboxExecution execution,
            String status,
            Integer exitCode,
            long durationMs,
            boolean timedOut,
            boolean truncated,
            boolean oomKilled,
            Long peakMemoryBytes,
            Long cpuTimeMs,
            String stdout,
            String stderr,
            String errorMessage) {
        try {
            recordDao.settle(
                    execution.execId(),
                    status,
                    exitCode,
                    (int) Math.min(durationMs, Integer.MAX_VALUE),
                    timedOut,
                    truncated,
                    oomKilled,
                    peakMemoryBytes,
                    cpuTimeMs,
                    stdout,
                    stderr,
                    errorMessage,
                    now());
        } catch (RuntimeException ex) {
            // The execution already happened; losing the terminal update leaves a
            // running row that the startup reaper marks aborted later.
            log.error("failed to settle exec audit row {}", execution.execId(), ex);
        }
    }

    private void markStartedQuietly(String execId) {
        try {
            recordDao.markStarted(execId, now());
        } catch (RuntimeException ex) {
            log.warn("failed to mark exec {} started", execId, ex);
        }
    }

    private int clampTimeout(Integer requested) {
        int fallback = (int) properties.defaultTimeout().toMillis();
        int max = (int) properties.maxTimeout().toMillis();
        int timeout = requested == null ? fallback : requested;
        return Math.max(MIN_TIMEOUT_MS, Math.min(timeout, max));
    }

    private static String normalizeLanguage(String language) {
        return language == null || language.isBlank()
                ? LANGUAGE_PYTHON
                : language.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static long parseUserId(String userId) {
        try {
            return Long.parseLong(userId);
        } catch (NumberFormatException ex) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "invalid X-User-Id header");
        }
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
