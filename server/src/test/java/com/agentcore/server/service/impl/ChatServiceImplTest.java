package com.agentcore.server.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentcore.server.config.GatewayProperties;
import com.agentcore.server.dao.MessageCache;
import com.agentcore.server.dao.MessageDao;
import com.agentcore.server.common.gateway.GatewayClient;
import com.agentcore.server.dao.RunDao;
import com.agentcore.server.dao.SessionModelDao;
import com.agentcore.server.dao.SessionDao;
import com.agentcore.server.domain.Session;
import com.agentcore.server.service.SessionModelChoice;
import com.agentcore.server.service.ChatProjectionService;
import com.agentcore.server.service.SessionModelService;
import com.agentcore.server.service.PromptStream;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceImplTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<String> promptMode = new AtomicReference<>("complete");
    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastUserId = new AtomicReference<>();
    private final AtomicReference<String> lastTurnId = new AtomicReference<>();

    private HttpServer server;
    private SessionDao sessionDao;
    private ChatProjectionService projection;
    private SessionModelService sessionModelService;
    private ChatServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        GatewayProperties properties = new GatewayProperties(
                "http://127.0.0.1:" + server.getAddress().getPort(), null);
        sessionDao = mock(SessionDao.class);
        when(sessionDao.findActiveOwned(anyString(), anyLong())).thenReturn(new Session());
        when(sessionDao.archive(anyString(), anyLong(), any())).thenReturn(1);
        projection = mock(ChatProjectionService.class);
        sessionModelService = mock(SessionModelService.class);
        SessionModelChoice system =
                SessionModelChoice.system("系统默认");
        when(sessionModelService.forNewSession(anyLong(), any(), anyBoolean())).thenReturn(system);
        when(sessionModelService.forExistingSession(anyLong(), anyString())).thenReturn(system);
        service = new ChatServiceImpl(
                properties,
                mapper,
                sessionDao,
                mock(SessionModelDao.class),
                mock(MessageDao.class),
                mock(RunDao.class),
                mock(MessageCache.class),
                projection,
                sessionModelService,
                transactionManager(),
                new GatewayClient(properties, mapper));
    }

    private static PlatformTransactionManager transactionManager() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return manager;
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void createSessionUsesPutAndIdentityHeader() {
        String sessionId = service.createSession(1L, null, null, false);

        assertNotNull(sessionId);
        assertEquals("1", lastUserId.get());
    }

    @Test
    void relaysTheSharedCompleteFixture() throws Exception {
        PromptStream stream = openPrompt("complete");

        String sse = relay(stream);

        assertTrue(sse.contains("event: run_started"));
        assertTrue(sse.contains("event: thinking_delta"));
        assertTrue(sse.contains("event: text_delta"));
        assertTrue(sse.contains("event: message_end"));
        assertTrue(sse.contains("event: run_settled"));
        assertFalse(sse.contains("STREAM_INTERRUPTED"));
        assertEquals(stream.turn().turnId(), lastTurnId.get());
    }

    @Test
    void synthesizesATerminalForTheSharedInterruptedFixture() throws Exception {
        PromptStream stream = openPrompt("interrupted");

        String sse = relay(stream);

        assertTrue(sse.contains("event: run_settled"));
        assertTrue(sse.contains("STREAM_INTERRUPTED"));
        assertTrue(sse.contains("\"status\":\"error\""));
    }

    @Test
    void rejectsARunStartedTurnIdMismatch() throws Exception {
        PromptStream stream = openPrompt("mismatch");

        String sse = relay(stream);

        assertTrue(sse.contains("STREAM_INTERRUPTED"));
        assertTrue(sse.contains("turnId mismatch"));
        assertFalse(sse.contains("event: text_delta"));
    }

    @Test
    void relaysToolResultDetailsToTheProjection() throws Exception {
        PromptStream stream = openPrompt("tool-details");

        relay(stream);

        org.mockito.ArgumentCaptor<String> details =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(projection).appendTool(
                any(), anyInt(), eq("tool output"), details.capture());
        assertThat(details.getValue()).contains("\"passageId\":\"p-1\"");
    }

    @Test
    void archiveUsesDeleteWithOwnerHeader() {
        String sessionId = service.createSession(1L, null, null, false);

        service.archiveSession(1L, sessionId);

        assertEquals("DELETE", lastMethod.get());
        assertEquals("1", lastUserId.get());
    }

    private PromptStream openPrompt(String mode) {
        promptMode.set(mode);
        String sessionId = service.createSession(1L, null, null, false);
        return service.openPrompt(1L, sessionId, "hi", null);
    }

    private String relay(PromptStream stream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.relayPrompt(stream, out);
        return out.toString(StandardCharsets.UTF_8);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        lastMethod.set(method);

        if ("PUT".equals(method) && path.startsWith("/sessions/")) {
            lastUserId.set(exchange.getRequestHeaders().getFirst("X-User-Id"));
            respond(exchange, 200, "{\"sessionId\":\"s\",\"created\":true,\"restored\":false}");
            return;
        }

        if ("POST".equals(method) && path.endsWith("/prompt")) {
            lastUserId.set(exchange.getRequestHeaders().getFirst("X-User-Id"));
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String turnId = mapper.readTree(body).path("turnId").asText();
            lastTurnId.set(turnId);
            respondSse(exchange, replayFixture(turnId));
            return;
        }

        if ("DELETE".equals(method) && path.startsWith("/sessions/")) {
            lastUserId.set(exchange.getRequestHeaders().getFirst("X-User-Id"));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }

        respond(exchange, 404, "{}");
    }

    /** Replays the shared fixture, echoing the turnId Java generated. */
    private String replayFixture(String turnId) throws IOException {
        String mode = promptMode.get();
        if ("tool-details".equals(mode)) {
            String runId = "run-1";
            return frame(runId, 1, "run_started",
                            "{\"runId\":\"run-1\",\"turnId\":\"" + turnId
                                    + "\",\"sessionId\":\"s\",\"contractVersion\":\"1\"}")
                    + frame(runId, 2, "tool_result",
                            "{\"toolCallId\":\"c1\",\"name\":\"kb_search\",\"isError\":false,"
                                    + "\"durationMs\":12,\"content\":\"tool output\","
                                    + "\"truncated\":false,\"details\":{\"count\":1,"
                                    + "\"passages\":[{\"passageId\":\"p-1\"}]}}")
                    + frame(runId, 3, "run_settled",
                            "{\"status\":\"completed\",\"turnId\":\"" + turnId + "\"}");
        }
        JsonNode frames = "complete".equals(mode)
                ? loadFixture("gateway", "01-text-thinking.json").path("expected").path("frames")
                : loadFixture("consumer", "01-interrupted-stream.json").path("input").path("frames");

        String runId = frames.get(0).path("data").path("runId").asText("run-1");
        StringBuilder sse = new StringBuilder();
        for (JsonNode frame : frames) {
            String event = frame.path("event").asText();
            ObjectNode data = frame.path("data").deepCopy();
            if ("run_started".equals(event)) {
                data.put("turnId", "mismatch".equals(mode) ? "other-turn" : turnId);
            }
            sse.append(frame(runId, frame.path("seq").asLong(), event, mapper.writeValueAsString(data)));
        }
        return sse.toString();
    }

    private JsonNode loadFixture(String kind, String file) throws IOException {
        Path direct = Path.of("contract", "v1", "fixtures", kind, file);
        Path path = Files.exists(direct)
                ? direct
                : Path.of("..", "contract", "v1", "fixtures", kind, file);
        return mapper.readTree(path.toFile());
    }

    private static String frame(String runId, long seq, String event, String data) {
        return "id: " + runId + ":" + seq + "\nevent: " + event + "\ndata: " + data + "\n\n";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("content-type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void respondSse(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("content-type", "text/event-stream; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
