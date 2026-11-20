package com.agentcore.server.controller;

import com.agentcore.server.common.identity.ToolIdentity;
import com.agentcore.server.common.sandbox.CancellationToken;
import com.agentcore.server.dto.sandbox.SandboxExecRequest;
import com.agentcore.server.dto.sandbox.SandboxExecution;
import com.agentcore.server.service.SandboxService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * run_code tool callback (requirements.md FR-3). Validation and admission run
 * before the SSE stream starts so failures stay JSON envelopes; execution then
 * streams {@code started/phase/stdout/stderr/result} frames.
 */
@RestController
@RequestMapping("/api/exec")
public class SandboxController {

    private static final long EMITTER_TIMEOUT_MS = 300_000L;
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    private final SandboxService sandboxService;
    private final TaskExecutor sandboxExecutor;
    private final TaskScheduler heartbeatScheduler;
    private final ObjectMapper objectMapper;

    public SandboxController(
            SandboxService sandboxService,
            @Qualifier("sandboxExecutor") TaskExecutor sandboxExecutor,
            @Qualifier("sandboxHeartbeatScheduler") TaskScheduler heartbeatScheduler,
            ObjectMapper objectMapper) {
        this.sandboxService = sandboxService;
        this.sandboxExecutor = sandboxExecutor;
        this.heartbeatScheduler = heartbeatScheduler;
        this.objectMapper = objectMapper;
    }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> exec(
            @RequestHeader HttpHeaders headers,
            @RequestBody SandboxExecRequest request) {
        ToolIdentity identity = ToolIdentity.from(headers);
        SandboxExecution execution = sandboxService.start(request, identity);
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        CancellationToken cancellation = new CancellationToken();
        SseSandboxEventSink sink = new SseSandboxEventSink(emitter, objectMapper, cancellation);

        ScheduledFuture<?> heartbeat = heartbeatScheduler.scheduleAtFixedRate(
                () -> sendHeartbeat(emitter, sink, cancellation),
                HEARTBEAT_INTERVAL);
        emitter.onCompletion(() -> {
            heartbeat.cancel(false);
            if (!sink.isFinished()) {
                cancellation.cancel();
            }
        });
        emitter.onError(ex -> {
            heartbeat.cancel(false);
            cancellation.cancel();
        });
        emitter.onTimeout(() -> {
            heartbeat.cancel(false);
            cancellation.cancel();
        });

        try {
            sandboxExecutor.execute(() -> sandboxService.run(execution, sink, cancellation));
        } catch (RuntimeException ex) {
            heartbeat.cancel(false);
            cancellation.cancel();
            sandboxService.abandon(execution, "sandbox executor rejected the request");
            emitter.completeWithError(ex);
        }
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "event-stream", StandardCharsets.UTF_8))
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform")
                .header("x-accel-buffering", "no")
                .body(emitter);
    }

    private static void sendHeartbeat(
            SseEmitter emitter, SseSandboxEventSink sink, CancellationToken cancellation) {
        if (sink.isFinished()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().comment(" ping"));
        } catch (Exception ex) {
            cancellation.cancel();
        }
    }
}
