package com.agentcore.server.controller;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.common.error.GlobalExceptionHandler;
import com.agentcore.server.common.identity.ToolIdentity;
import com.agentcore.server.common.sandbox.CancellationToken;
import com.agentcore.server.common.sandbox.SandboxEventSink;
import com.agentcore.server.common.sandbox.SandboxExecResult;
import com.agentcore.server.common.sandbox.SandboxPermit;
import com.agentcore.server.dto.sandbox.SandboxExecRequest;
import com.agentcore.server.dto.sandbox.SandboxExecution;
import com.agentcore.server.service.SandboxService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SandboxControllerTest {

    private final java.util.concurrent.ScheduledExecutorService schedulerExecutor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "sandbox-heartbeat-test");
                thread.setDaemon(true);
                return thread;
            });
    private final ConcurrentTaskScheduler scheduler =
            new ConcurrentTaskScheduler(schedulerExecutor);

    @AfterEach
    void shutdownScheduler() {
        schedulerExecutor.shutdownNow();
    }

    @Test
    void streamsContractFramesInOrder() throws Exception {
        SandboxService service = new StubService() {
            @Override
            public void run(
                    SandboxExecution execution,
                    SandboxEventSink sink,
                    CancellationToken cancellation) {
                sink.started(execution.execId());
                sink.phase("starting");
                sink.phase("running");
                sink.stdout("hello");
                sink.stderr("warn");
                sink.result(new SandboxExecResult(0, 12, false, true));
            }
        };
        MockMvc mockMvc = mockMvc(service);

        MvcResult result = mockMvc.perform(execRequest())
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(containsString("event:started")))
                .andExpect(content().string(containsString("event:phase")))
                .andExpect(content().string(containsString("event:stdout")))
                .andExpect(content().string(containsString("event:stderr")))
                .andExpect(content().string(containsString("event:result")))
                .andExpect(content().string(containsString("\"timedOut\":false")))
                .andExpect(content().string(containsString("\"truncated\":true")));
    }

    @Test
    void rejectedRequestStaysJsonBeforeTheStreamStarts() throws Exception {
        SandboxService service = new StubService() {
            @Override
            public SandboxExecution start(SandboxExecRequest request, ToolIdentity identity) {
                throw new ApiException(ErrorCode.SANDBOX_CODE_TOO_LARGE, "code exceeds 262144 bytes");
            }
        };
        MockMvc mockMvc = mockMvc(service);

        mockMvc.perform(execRequest())
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error.code").value("SANDBOX_CODE_TOO_LARGE"));
    }

    private MockMvc mockMvc(SandboxService service) {
        SandboxController controller = new SandboxController(
                service, Runnable::run, scheduler, new ObjectMapper());
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder execRequest() {
        return post("/api/exec")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"print(1)\"}")
                .header("X-User-Id", "42")
                .header("X-Session-Id", "session-1")
                .header("X-Turn-Id", "turn-1")
                .header("X-Run-Id", "run-1")
                .header("X-Tool-Call-Id", "call-1")
                .header("X-Request-Id", "req-1");
    }

    private static class StubService implements SandboxService {

        @Override
        public SandboxExecution start(SandboxExecRequest request, ToolIdentity identity) {
            return new SandboxExecution(
                    "exec-1",
                    request.code(),
                    "python",
                    30_000,
                    42L,
                    identity.sessionId(),
                    identity.turnId(),
                    identity.runId(),
                    identity.toolCallId(),
                    identity.requestId(),
                    SandboxPermit.noop());
        }

        @Override
        public void run(
                SandboxExecution execution, SandboxEventSink sink, CancellationToken cancellation) {}

        @Override
        public void abandon(SandboxExecution execution, String reason) {}
    }
}
