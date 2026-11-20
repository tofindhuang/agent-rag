package com.agentcore.server.controller;

import com.agentcore.server.common.sandbox.CancellationToken;
import com.agentcore.server.common.sandbox.SandboxEventSink;
import com.agentcore.server.common.sandbox.SandboxExecResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Bridges executor events to one {@link SseEmitter}; disconnects cancel the run. */
final class SseSandboxEventSink implements SandboxEventSink {

    private static final Logger log = LoggerFactory.getLogger(SseSandboxEventSink.class);

    private final SseEmitter emitter;
    private final ObjectMapper objectMapper;
    private final CancellationToken cancellation;
    private final AtomicBoolean finished = new AtomicBoolean();

    SseSandboxEventSink(
            SseEmitter emitter, ObjectMapper objectMapper, CancellationToken cancellation) {
        this.emitter = emitter;
        this.objectMapper = objectMapper;
        this.cancellation = cancellation;
    }

    boolean isFinished() {
        return finished.get();
    }

    @Override
    public void started(String execId) {
        send("started", Map.of("execId", execId));
    }

    @Override
    public void phase(String phase) {
        send("phase", Map.of("phase", phase));
    }

    @Override
    public void stdout(String chunk) {
        send("stdout", Map.of("chunk", chunk));
    }

    @Override
    public void stderr(String chunk) {
        send("stderr", Map.of("chunk", chunk));
    }

    @Override
    public void result(SandboxExecResult result) {
        if (finished.get()) {
            return;
        }
        send("result", result);
        finished.set(true);
        emitter.complete();
    }

    private void send(String event, Object payload) {
        if (finished.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event()
                    .name(event)
                    .data(objectMapper.writeValueAsString(payload), MediaType.TEXT_PLAIN));
        } catch (IOException | IllegalStateException ex) {
            // The client is gone (or the emitter completed): stop the container.
            log.debug("sandbox SSE write failed; cancelling execution", ex);
            cancellation.cancel();
        }
    }
}
