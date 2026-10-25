package com.agentcore.server.service.impl;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.common.gateway.GatewayClient;
import com.agentcore.server.common.gateway.SessionTarget;
import com.agentcore.server.common.ratelimit.RateLimitDecision;
import com.agentcore.server.common.ratelimit.RateLimitService;
import com.agentcore.server.common.sse.SseFrame;
import com.agentcore.server.common.sse.SseFrameParser;
import com.agentcore.server.config.GatewayProperties;
import com.agentcore.server.dao.MessageCache;
import com.agentcore.server.dao.MessageDao;
import com.agentcore.server.dao.RunDao;
import com.agentcore.server.dao.KbSessionKnowledgeBaseDao;
import com.agentcore.server.dao.SessionDao;
import com.agentcore.server.dao.SessionModelDao;
import com.agentcore.server.domain.Message;
import com.agentcore.server.domain.MessagePage;
import com.agentcore.server.domain.Session;
import com.agentcore.server.domain.TurnKey;
import com.agentcore.server.service.ChatProjectionService;
import com.agentcore.server.service.ProfileFactService;
import com.agentcore.server.service.SessionArchiveService;
import com.agentcore.server.service.ChatService;
import com.agentcore.server.service.SessionModelChoice;
import com.agentcore.server.service.KbKnowledgeBaseService;
import com.agentcore.server.service.SessionModelService;
import com.agentcore.server.service.PromptStream;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Default {@link ChatService}: contract v1 client, session persistence, event
 * projection and SSE relay.
 *
 * <p>Java owns the business session ID, turn ID and run-row ID, sends the user
 * identity as a header, validates the turn echoed by {@code run_started}, and
 * synthesizes {@code STREAM_INTERRUPTED} when the gateway stream ends without
 * a terminal.
 */
@Service
public class ChatServiceImpl implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);
    private static final String STATUS_ACTIVE = "active";
    private static final String STREAM_INTERRUPTED = "STREAM_INTERRUPTED";
    private static final String INTERRUPTED_MESSAGE = "连接中断，未收到终态";

    private final GatewayProperties gateway;
    private final ObjectMapper objectMapper;
    private final SessionDao sessionDao;
    private final KbSessionKnowledgeBaseDao sessionKbDao;
    private final KbKnowledgeBaseService knowledgeBaseService;
    private final SessionModelDao sessionModelDao;
    private final MessageDao messageDao;
    private final RunDao runDao;
    private final MessageCache messageCache;
    private final ChatProjectionService projection;
    private final SessionModelService sessionModelService;
    private final TransactionTemplate transactionTemplate;
    private final GatewayClient client;
    private final RateLimitService rateLimitService;
    private final ProfileFactService profileFactService;
    private final SessionArchiveService sessionArchiveService;

    public ChatServiceImpl(
            GatewayProperties gateway,
            ObjectMapper objectMapper,
            SessionDao sessionDao,
            KbSessionKnowledgeBaseDao sessionKbDao,
            KbKnowledgeBaseService knowledgeBaseService,
            SessionModelDao sessionModelDao,
            MessageDao messageDao,
            RunDao runDao,
            MessageCache messageCache,
            ChatProjectionService projection,
            SessionModelService sessionModelService,
            PlatformTransactionManager transactionManager,
            GatewayClient client,
            RateLimitService rateLimitService,
            ProfileFactService profileFactService,
            SessionArchiveService sessionArchiveService) {
        this.gateway = gateway;
        this.objectMapper = objectMapper;
        this.sessionDao = sessionDao;
        this.sessionKbDao = sessionKbDao;
        this.knowledgeBaseService = knowledgeBaseService;
        this.sessionModelDao = sessionModelDao;
        this.messageDao = messageDao;
        this.runDao = runDao;
        this.messageCache = messageCache;
        this.projection = projection;
        this.sessionModelService = sessionModelService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.client = client;
        this.rateLimitService = rateLimitService;
        this.profileFactService = profileFactService;
        this.sessionArchiveService = sessionArchiveService;
    }

    @Override
    public String createSession(
            long userId,
            String title,
            Long modelId,
            boolean useSystemDefault,
            String kbId,
            boolean noKnowledgeBase) {
        SessionModelChoice choice = sessionModelService.forNewSession(userId, modelId, useSystemDefault);
        String resolvedKbId = noKnowledgeBase
                ? null
                : kbId == null || kbId.isBlank()
                        ? knowledgeBaseService.defaultId(userId)
                        : knowledgeBaseService.requireOwned(userId, kbId).getId();

        String sessionId = UUID.randomUUID().toString();
        Session session = new Session();
        session.setId(sessionId);
        session.setUserId(userId);
        session.setTitle(title == null || title.isBlank() ? Session.DEFAULT_TITLE : title.trim());
        session.setStatus(STATUS_ACTIVE);
        session.setLastActiveAt(nowUtc());
        sessionDao.insert(session);
        if (resolvedKbId != null) {
            sessionKbDao.insert(sessionId, resolvedKbId);
        }
        sessionModelDao.upsert(sessionId, choice.providerModelId(), choice.label(), choice.source());

        // DB first: if the gateway rejects the session, compensate by removing
        // the row instead of leaving an unreachable AgentSession behind.
        try {
            EnsureOutcome ensured = ensureGatewaySession(sessionId, userId, choice, false);
            String actualName = ensured.modelName();
            if ("system".equals(choice.source()) && actualName != null && !actualName.isBlank()) {
                sessionModelDao.upsert(sessionId, null, actualName, "system");
            }
            return sessionId;
        } catch (RuntimeException ex) {
            try {
                sessionDao.deleteById(sessionId);
            } catch (RuntimeException cleanupFailure) {
                log.warn("failed to remove session {} after gateway error: {}",
                        sessionId, cleanupFailure.getMessage());
            }
            throw ex;
        }
    }

    @Override
    public void switchKnowledgeBase(long userId, String sessionId, String kbId) {
        if (sessionDao.findActiveOwned(sessionId, userId) == null) {
            throw sessionNotFound();
        }
        String resolved = kbId == null || kbId.isBlank()
                ? null
                : knowledgeBaseService.requireOwned(userId, kbId).getId();
        transactionTemplate.executeWithoutResult((status) -> {
            sessionKbDao.deleteBySession(sessionId);
            if (resolved != null) {
                sessionKbDao.insert(sessionId, resolved);
            }
        });
    }

    @Override
    public void switchModel(long userId, String sessionId, Long modelId) {
        if (sessionDao.findActiveOwned(sessionId, userId) == null) {
            throw sessionNotFound();
        }
        SessionModelChoice choice = sessionModelService.forSwitch(userId, modelId);

        // The gateway keeps the model of a resident AgentSession; dispose it so
        // the next ensure rebuilds the runtime from the pi file with the new model.
        disposeGatewaySession(sessionId, userId);
        EnsureOutcome ensured = ensureGatewaySession(
                sessionId, userId, choice, messageDao.existsBySession(sessionId));
        String actualName = ensured.modelName();
        sessionModelDao.upsert(
                sessionId, choice.providerModelId(), labelFor(choice, actualName), choice.source());
    }

    private static String labelFor(SessionModelChoice choice, String actualName) {
        if (!"system".equals(choice.source())) {
            return choice.label();
        }
        return actualName == null || actualName.isBlank() ? choice.label() : actualName;
    }

    /** Fills a missing snapshot (pre-migration sessions) and refreshes a stale system label. */
    private void syncSnapshot(Session session, SessionModelChoice choice, String actualName) {
        boolean missing = session.getModelLabel() == null;
        boolean staleSystemLabel = "system".equals(choice.source())
                && actualName != null
                && !actualName.isBlank()
                && !actualName.equals(session.getModelLabel());
        if (missing || staleSystemLabel) {
            sessionModelDao.upsert(
                    session.getId(), choice.providerModelId(), labelFor(choice, actualName), choice.source());
        }
    }

    @Override
    public List<Session> listSessions(
            long userId,
            String status,
            LocalDateTime beforeActiveAt,
            String beforeId,
            int limit) {
        return sessionDao.listPage(userId, status, beforeActiveAt, beforeId, limit);
    }

    @Override
    public void renameSession(long userId, String sessionId, String title) {
        if (sessionDao.rename(sessionId, userId, title) == 0) {
            throw sessionNotFound();
        }
    }

    @Override
    public void restoreSession(long userId, String sessionId) {
        if (sessionDao.restore(sessionId, userId, nowUtc()) == 0) {
            throw sessionNotFound();
        }
    }

    @Override
    public void permanentDelete(long userId, String sessionId) {
        // Only archived sessions can be purged (ADR-0011): no active run may
        // still hold the engine context while its files disappear.
        if (sessionDao.findArchivedOwned(sessionId, userId) == null) {
            throw sessionNotFound();
        }

        // Files first. On failure the rows stay, so the caller can retry.
        deleteGatewayFiles(sessionId, userId);
        // Archived engine files live in object storage; clear them before rows.
        sessionArchiveService.deleteIfPresent(sessionId, userId);

        transactionTemplate.executeWithoutResult(status -> {
            messageDao.deleteBySession(sessionId);
            runDao.deleteBySession(sessionId);
            sessionDao.deleteOwned(sessionId, userId);
        });
        // Redis may still hold the recent window of the deleted session.
        messageCache.clear(sessionId);
    }

    private void deleteGatewayFiles(String sessionId, long userId) {
        SessionTarget target = client.sessionTarget(sessionId);
        HttpResponse<String> response;
        try {
            response = deleteSessionFiles(target, sessionId, userId);
            if (response.statusCode() == 409
                    && "SESSION_BUSY".equals(gatewayErrorCode(response.body()))) {
                String owner = gatewayErrorOwner(response.body());
                if (owner != null && !owner.isBlank()) {
                    client.correctRoute(sessionId, owner);
                    target = client.sessionTarget(sessionId);
                    response = deleteSessionFiles(target, sessionId, userId);
                }
            }
        } catch (ApiException ex) {
            // Transport failure: rows stay, the user retries.
            throw new ApiException(ErrorCode.SESSION_PURGE_FAILED,
                    "failed to delete session files; retry later", ex);
        }
        if (response.statusCode() == 403) {
            // Ownership cannot be proven (e.g. missing sidecar). Retrying will
            // never succeed, so do not report it as a transient 502.
            throw new ApiException(ErrorCode.SESSION_PURGE_BLOCKED,
                    "session files cannot be attributed to this user");
        }
        if (response.statusCode() == 409
                && "SESSION_STATE_CORRUPT".equals(gatewayErrorCode(response.body()))) {
            throw new ApiException(ErrorCode.SESSION_PURGE_BLOCKED,
                    "session files cannot be attributed to this user");
        }
        if (response.statusCode() >= 400) {
            throw new ApiException(ErrorCode.SESSION_PURGE_FAILED,
                    "failed to delete session files; retry later");
        }
        client.forgetRoute(sessionId);
    }

    private HttpResponse<String> deleteSessionFiles(
            SessionTarget target, String sessionId, long userId) {
        return client.send(gatewaySession(target, sessionId, userId, "/files").DELETE().build());
    }

    @Override
    public void archiveSession(long userId, String sessionId) {
        if (sessionDao.archive(sessionId, userId, nowUtc()) == 0) {
            throw sessionNotFound();
        }

        // The gateway is allowed to forget the AgentSession; a restart already
        // means it has nothing to dispose, so this stays best-effort.
        try {
            SessionTarget target = client.sessionTarget(sessionId);
            HttpResponse<String> response =
                    client.send(gatewaySession(target, sessionId, userId, "").DELETE().build());
            if (response.statusCode() >= 400 && response.statusCode() != 404) {
                log.warn("gateway dispose returned {} for archived session {}",
                        response.statusCode(), sessionId);
            }
        } catch (RuntimeException ex) {
            log.warn("gateway dispose failed for archived session {}: {}", sessionId, ex.getMessage());
        }
    }

    @Override
    public MessagePage history(long userId, String sessionId, Long beforeId, int limit) {
        if (sessionDao.findActiveOwned(sessionId, userId) == null) {
            throw sessionNotFound();
        }

        if (beforeId == null) {
            List<Message> cached = messageCache.recent(sessionId, limit + 1);
            if (cached.size() >= limit) {
                List<Message> newest = cached.subList(0, limit);
                boolean hasMore = cached.size() > limit
                        || messageDao.existsBefore(sessionId, newest.get(newest.size() - 1).getId());
                return new MessagePage(ascending(newest), hasMore);
            }
            // Fewer cached rows than a full page: fall through to MySQL so the
            // cache can never truncate the visible window.
        }

        List<Message> rows = messageDao.listPage(sessionId, beforeId, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<Message> newest = hasMore ? rows.subList(0, limit) : rows;
        return new MessagePage(ascending(newest), hasMore);
    }

    private static List<Message> ascending(List<Message> newestFirst) {
        List<Message> items = new ArrayList<>(newestFirst);
        Collections.reverse(items);
        return items;
    }

    @Override
    public PromptStream openPrompt(long userId, String sessionId, String text, String requestTurnId) {
        Session session = sessionDao.findActiveOwned(sessionId, userId);
        if (session == null) {
            throw sessionNotFound();
        }
        SessionModelChoice choice = sessionModelService.forExistingSession(userId, sessionId);
        RateLimitDecision tpm = rateLimitService.checkUserTpm(userId);
        if (!tpm.allowed()) {
            throw new ApiException(ErrorCode.RATE_LIMITED,
                    "token rate limit exceeded; retry in " + tpm.retryAfterSeconds() + "s",
                    tpm.retryAfterSeconds());
        }

        String turnId = requestTurnId == null || requestTurnId.isBlank()
                ? UUID.randomUUID().toString()
                : requestTurnId;
        TurnKey turn = new TurnKey(sessionId, turnId, UUID.randomUUID().toString());
        String requestId = UUID.randomUUID().toString();
        String modelId = choice.usesUserProvider() ? choice.binding().modelId() : "system";
        projection.startTurn(turn, text, requestId, modelId);

        // Cold start (ADR-0008): restore the file-backed AgentSession with its
        // model before every prompt; PUT is idempotent and cheap.
        SessionTarget promptTarget;
        try {
            EnsureOutcome ensured = ensureGatewaySession(
                    sessionId, userId, choice, messageDao.existsBeforeTurn(sessionId, turnId));
            syncSnapshot(session, choice, ensured.modelName());
            promptTarget = ensured.target();
        } catch (ApiException ex) {
            projection.failBeforeStream(turn, ex.code().name(), ex.getMessage());
            throw ex;
        }

        HttpResponse<InputStream> response;
        try {
            response = client.sendStreaming(
                    promptRequest(promptTarget, sessionId, userId, text, turnId, requestId));
        } catch (ApiException e) {
            // No SSE bytes were received: the turn did not start downstream.
            // Re-ensure on another instance and retry once with the same turnId.
            try {
                client.markRouteUnavailable(promptTarget.instanceId());
                client.failoverRoute(sessionId);
                EnsureOutcome retried = ensureGatewaySession(
                        sessionId, userId, choice, messageDao.existsBeforeTurn(sessionId, turnId));
                syncSnapshot(session, choice, retried.modelName());
                promptTarget = retried.target();
                response = client.sendStreaming(
                        promptRequest(promptTarget, sessionId, userId, text, turnId, requestId));
            } catch (ApiException retryFailure) {
                projection.failBeforeStream(
                        turn, retryFailure.code().name(), retryFailure.getMessage());
                throw retryFailure;
            }
        }
        if (response.statusCode() != 200) {
            String detail = readFully(response.body());
            String[] error = gatewayError(detail, response.statusCode());
            if (response.statusCode() == 409 && "INSTANCE_DRAINING".equals(error[0])
                    && releaseGatewaySession(promptTarget, sessionId, userId)) {
                log.info("moving session off draining gateway: {}", sessionId);
                client.failoverRoute(sessionId, false);
                EnsureOutcome moved = ensureGatewaySession(
                        sessionId, userId, choice, messageDao.existsBeforeTurn(sessionId, turnId));
                syncSnapshot(session, choice, moved.modelName());
                promptTarget = moved.target();
                response = client.sendStreaming(
                        promptRequest(promptTarget, sessionId, userId, text, turnId, requestId));
                if (response.statusCode() != 200) {
                    detail = readFully(response.body());
                    error = gatewayError(detail, response.statusCode());
                }
            }
            if (response.statusCode() != 200) {
                projection.failBeforeStream(turn, error[0], error[1]);
                throw new ApiException(ErrorCode.UPSTREAM_ERROR,
                        "gateway error " + response.statusCode() + ": " + detail);
            }
        }
        return new PromptStream(response.body(), turn, requestId, userId);
    }

    @Override
    public void relayPrompt(PromptStream prompt, OutputStream out) throws IOException {
        ProjectionState state = new ProjectionState();
        boolean terminal = false;
        boolean settled = false;
        String runId = "unknown";
        long seq = 0;

        try (InputStream body = prompt.body()) {
            SseFrameParser parser = new SseFrameParser(body);
            SseFrame frame;
            while ((frame = parser.read()) != null) {
                // Heartbeat/comment blocks have no event and must not consume seq.
                if (frame.event() == null || frame.event().isBlank()) {
                    continue;
                }
                if (frame.id() == null) {
                    seq += 1;
                } else {
                    int colon = frame.id().lastIndexOf(':');
                    if (colon > 0) {
                        runId = frame.id().substring(0, colon);
                        try {
                            seq = Long.parseLong(frame.id().substring(colon + 1));
                        } catch (NumberFormatException e) {
                            seq += 1;
                        }
                    } else {
                        seq += 1;
                    }
                }

                JsonNode data = parseData(frame);
                switch (frame.event()) {
                    case "run_started" -> {
                        String echoedTurnId = data.path("turnId").asText("");
                        if (!prompt.turn().turnId().equals(echoedTurnId)) {
                            log.warn("gateway run_started turnId mismatch: expected {}, got {}",
                                    prompt.turn().turnId(), echoedTurnId);
                            persistInterrupted(prompt, state, "run_started turnId mismatch");
                            writeTerminal(out, runId, seq + 1, prompt.turn().turnId(),
                                    STREAM_INTERRUPTED, "run_started turnId mismatch");
                            return;
                        }
                        String gatewayRunId = data.path("runId").asText("");
                        if (!gatewayRunId.isEmpty()) {
                            projection.gatewayAccepted(prompt.turn().runRowId(), gatewayRunId);
                        }
                    }
                    case "thinking_delta" -> state.thinking.append(data.path("delta").asText(""));
                    case "text_delta" -> state.text.append(data.path("delta").asText(""));
                    case "message_end" -> {
                        long tokensIn = data.path("usage").path("input").asLong(0);
                        long tokensOut = data.path("usage").path("output").asLong(0);
                        long cacheRead = data.path("usage").path("cacheRead").asLong(0);
                        long cacheWrite = data.path("usage").path("cacheWrite").asLong(0);
                        state.tokensIn += tokensIn;
                        state.tokensOut += tokensOut;
                        state.cacheReadTokens += cacheRead;
                        state.cacheWriteTokens += cacheWrite;
                        long totalTokens = data.path("usage").path("totalTokens").asLong(0);
                        rateLimitService.addUserTokens(
                                prompt.userId(), totalTokens > 0 ? totalTokens : tokensIn + tokensOut);
                        String status = "error".equals(data.path("stopReason").asText())
                                ? "error" : "complete";
                        projection.appendAssistant(
                                prompt.turn(),
                                state.nextOrdinal++,
                                data.path("text").asText(""),
                                data.path("thinking").asText(""),
                                toolCallsJson(data.path("toolCalls")),
                                data.path("seqInTurn").isNumber() ? data.path("seqInTurn").asInt() : null,
                                tokensIn,
                                tokensOut,
                                cacheRead,
                                cacheWrite,
                                status);
                        // The persisted message is authoritative; keep the
                        // remaining deltas scoped to this message only.
                        state.text.setLength(0);
                        state.thinking.setLength(0);
                    }
                    case "tool_result" -> projection.appendTool(
                            prompt.turn(),
                            state.nextOrdinal++,
                            data.path("content").asText(""),
                            data.path("name").asText(""),
                            detailsJson(data.path("details")));
                    case "compaction" -> projection.recordCompaction(
                            prompt.turn(),
                            prompt.userId(),
                            prompt.requestId(),
                            data.path("reason").asText(null),
                            data.hasNonNull("tokensBefore")
                                    ? data.path("tokensBefore").asLong() : null,
                            data.hasNonNull("tokensAfter")
                                    ? data.path("tokensAfter").asLong() : null);
                    case "run_settled" -> {
                        JsonNode error = data.path("error");
                        projection.settleRun(
                                prompt.turn(),
                                data.path("status").asText("error"),
                                error.path("code").asText(null),
                                error.path("message").asText(null),
                                state.tokensIn,
                                state.tokensOut,
                                state.cacheReadTokens,
                                state.cacheWriteTokens);
                        settled = true;
                    }
                    default -> {
                        // Deltas/progress/compaction/retry are display-only here.
                    }
                }

                out.write(frame.raw().getBytes(StandardCharsets.UTF_8));
                out.flush();

                if ("run_settled".equals(frame.event())) {
                    terminal = true;
                    break;
                }
            }
        } catch (IOException e) {
            if (!settled) {
                persistInterrupted(prompt, state, INTERRUPTED_MESSAGE);
            }
            if (!terminal) {
                try {
                    writeTerminal(out, runId, seq + 1, prompt.turn().turnId(),
                            STREAM_INTERRUPTED, INTERRUPTED_MESSAGE);
                } catch (IOException ignored) {
                    // client is gone as well; nothing else to do
                }
            }
            return;
        }

        if (!terminal) {
            persistInterrupted(prompt, state, INTERRUPTED_MESSAGE);
            writeTerminal(out, runId, seq + 1, prompt.turn().turnId(),
                    STREAM_INTERRUPTED, INTERRUPTED_MESSAGE);
        }
    }

    private void persistInterrupted(PromptStream prompt, ProjectionState state, String message) {
        try {
            if (!state.text.isEmpty() || !state.thinking.isEmpty()) {
                projection.appendAssistant(
                        prompt.turn(),
                        state.nextOrdinal++,
                        state.text.toString(),
                        state.thinking.toString(),
                        null,
                        null,
                        0,
                        0,
                        0,
                        0,
                        "partial");
            }
            projection.settleRun(
                    prompt.turn(),
                    "aborted",
                    STREAM_INTERRUPTED,
                    message,
                    state.tokensIn,
                    state.tokensOut,
                    state.cacheReadTokens,
                    state.cacheWriteTokens);
        } catch (RuntimeException ex) {
            log.error("failed to persist interrupted run {}", prompt.turn().runRowId(), ex);
        }
    }

    private JsonNode parseData(SseFrame frame) throws IOException {
        return frame.data() == null ? objectMapper.createObjectNode() : objectMapper.readTree(frame.data());
    }

    private String toolCallsJson(JsonNode toolCalls) throws IOException {
        if (toolCalls == null || !toolCalls.isArray() || toolCalls.isEmpty()) {
            return null;
        }
        return objectMapper.writeValueAsString(toolCalls);
    }

    private String detailsJson(JsonNode details) {
        if (details == null || details.isMissingNode() || details.isNull()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            log.warn("cannot serialize tool details; history citations will be missing", e);
            return null;
        }
    }

    /** Contract error envelope; falls back to the HTTP status. */
    private String[] gatewayError(String detail, int status) {
        GatewayClient.GatewayError error = client.errorBody(detail);
        String code = error.code().isEmpty() ? ErrorCode.UPSTREAM_ERROR.name() : error.code();
        String message = error.message().isEmpty() ? "gateway status " + status : error.message();
        return new String[] {code, message};
    }

    private String gatewayErrorCode(String detail) {
        return client.errorBody(detail).code();
    }

    /** Owner instance id from a SESSION_BUSY envelope; null when absent. */
    private String gatewayErrorOwner(String detail) {
        try {
            JsonNode owner = objectMapper.readTree(detail)
                    .path("error").path("owner").path("instanceId");
            return owner.isMissingNode() || owner.isNull() ? null : owner.asText(null);
        } catch (IOException ex) {
            return null;
        }
    }

    private record EnsureOutcome(SessionTarget target, String modelName) {}

    private EnsureOutcome ensureGatewaySession(
            String sessionId, long userId, SessionModelChoice choice, boolean requireExisting) {
        Map<String, Object> body = new LinkedHashMap<>();
        SessionModelChoice.Binding binding = choice.binding();
        if (binding != null) {
            Map<String, Object> model = new LinkedHashMap<>();
            model.put("providerId", choice.providerModelId());
            model.put("name", binding.providerName());
            model.put("api", binding.api());
            model.put("baseUrl", binding.baseUrl());
            model.put("apiKey", binding.apiKey());
            model.put("modelId", binding.modelId());
            model.put("displayName", binding.displayName());
            if (binding.contextWindow() != null) model.put("contextWindow", binding.contextWindow());
            model.put("reasoning", binding.reasoning());
            model.put("vision", binding.vision());
            body.put("model", model);
        }

        body.put("profileFacts", profileFactService.injectionFacts(userId, sessionId));
        body.put("profileMemoryEnabled", profileFactService.enabled(userId));
        body.put("requireExisting", requireExisting);

        String payload;
        try {
            payload = objectMapper.writeValueAsString(body);
        } catch (IOException ex) {
            throw new ApiException(ErrorCode.INTERNAL, "failed to serialize session model", ex);
        }

        SessionTarget target = client.sessionTarget(sessionId);
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpRequest request = gatewaySession(target, sessionId, userId, "")
                    .header("content-type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            try {
                HttpResponse<String> response = client.send(request);
                if (response.statusCode() == 409) {
                    String code = gatewayErrorCode(response.body());
                    if ("SESSION_BUSY".equals(code)) {
                        if (attempt == 0) {
                            String owner = gatewayErrorOwner(response.body());
                            if (owner != null && !owner.isBlank()) {
                                SessionTarget ownerTarget = client.sessionTargetFor(owner);
                                if (ownerTarget == null
                                        || !releaseGatewaySession(ownerTarget, sessionId, userId)) {
                                    // The owner still has an active run. Let the
                                    // caller retry instead of opening the same
                                    // file on a second instance.
                                    throw new ApiException(ErrorCode.SESSION_BUSY,
                                            "session is busy; retry later");
                                }
                                client.failoverRoute(sessionId, false);
                            } else {
                                // Owner metadata can be missing for a few
                                // milliseconds while another instance creates
                                // the lock directory. Re-place instead of
                                // treating it as an ownership conflict.
                                client.failoverRoute(sessionId, false);
                            }
                            target = client.sessionTarget(sessionId);
                            continue;
                        }
                        throw new ApiException(ErrorCode.SESSION_BUSY,
                                "session is busy; retry later");
                    }
                    if ("SESSION_STATE_CORRUPT".equals(code)) {
                        throw new ApiException(ErrorCode.SESSION_STATE_CORRUPT,
                                "gateway session state is corrupt");
                    }
                    if ("INSTANCE_DRAINING".equals(code) && attempt == 0) {
                        if (releaseGatewaySession(target, sessionId, userId)) {
                            client.failoverRoute(sessionId, false);
                            target = client.sessionTarget(sessionId);
                            continue;
                        }
                        throw new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE,
                                "gateway is draining and the session is still running");
                    }
                    if ("SESSION_CONFLICT".equals(code) || "USER_MISMATCH".equals(code)) {
                        throw new ApiException(ErrorCode.USER_MISMATCH,
                                "session belongs to another user");
                    }
                    throw new ApiException(ErrorCode.UPSTREAM_ERROR,
                            "unexpected gateway conflict: " + code);
                }
                if (response.statusCode() == 503
                        && "GATEWAY_CAPACITY".equals(gatewayErrorCode(response.body()))) {
                    if (attempt == 0) {
                        client.failoverRoute(sessionId, false);
                        target = client.sessionTarget(sessionId);
                        continue;
                    }
                    throw new ApiException(ErrorCode.GATEWAY_CAPACITY,
                            "no gateway instance has capacity");
                }
                if (response.statusCode() >= 400) {
                    if (response.statusCode() == 400) {
                        String[] error = gatewayError(response.body(), 400);
                        throw new ApiException(ErrorCode.MODEL_UNAVAILABLE,
                                error[1] == null || error[1].isBlank()
                                        ? "model configuration rejected" : error[1]);
                    }
                    throw upstreamError("ensure session", response);
                }
                return new EnsureOutcome(target, readModelName(response.body()));
            } catch (ApiException ex) {
                if (ex.code() == ErrorCode.UPSTREAM_UNAVAILABLE && attempt == 0) {
                    client.markRouteUnavailable(target.instanceId());
                    client.failoverRoute(sessionId);
                    target = client.sessionTarget(sessionId);
                    continue;
                }
                throw ex;
            }
        }
        throw new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE,
                "gateway ensure retry exhausted");
    }

    private String readModelName(String body) {
        try {
            JsonNode name = objectMapper.readTree(body).path("model").path("name");
            String value = name.asText("");
            return value.isEmpty() ? null : value;
        } catch (IOException ex) {
            log.warn("gateway returned an unreadable model reference: {}", ex.getMessage());
            return null;
        }
    }

    private HttpRequest promptRequest(
            SessionTarget target,
            String sessionId,
            long userId,
            String text,
            String turnId,
            String requestId) {
        return gatewaySession(target, sessionId, userId, "/prompt")
                .header("content-type", "application/json")
                .header("accept", "text/event-stream")
                .header("X-Request-Id", requestId)
                .POST(HttpRequest.BodyPublishers.ofString(
                        toJson(Map.of("text", text, "turnId", turnId))))
                .build();
    }

    /** Releases an idle resident session during drain; false when a run is active. */
    private boolean releaseGatewaySession(SessionTarget target, String sessionId, long userId) {
        try {
            HttpResponse<String> response = client.send(gatewaySession(
                            target, sessionId, userId, "/release")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build());
            return response.statusCode() < 400;
        } catch (ApiException ex) {
            return false;
        }
    }

    private void disposeGatewaySession(String sessionId, long userId) {
        SessionTarget target = client.sessionTarget(sessionId);
        HttpResponse<String> response = disposeSession(target, sessionId, userId);
        if (response.statusCode() == 409
                && "SESSION_BUSY".equals(gatewayErrorCode(response.body()))) {
            String owner = gatewayErrorOwner(response.body());
            if (owner != null && !owner.isBlank()) {
                client.correctRoute(sessionId, owner);
                target = client.sessionTarget(sessionId);
                response = disposeSession(target, sessionId, userId);
            }
        }
        if (response.statusCode() >= 400 && response.statusCode() != 404) {
            throw upstreamError("dispose session", response);
        }
    }

    private HttpResponse<String> disposeSession(
            SessionTarget target, String sessionId, long userId) {
        return client.send(gatewaySession(target, sessionId, userId, "").DELETE().build());
    }

    private HttpRequest.Builder gatewaySession(
            SessionTarget target, String sessionId, long userId, String suffix) {
        return client.request(target.baseUrl(), "/sessions/" + urlEncode(sessionId) + suffix)
                .header("X-User-Id", Long.toString(userId));
    }

    private static ApiException sessionNotFound() {
        return new ApiException(ErrorCode.SESSION_NOT_FOUND, "session not found");
    }

    private static LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private void writeTerminal(
            OutputStream out,
            String runId,
            long seq,
            String turnId,
            String code,
            String message) throws IOException {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        error.put("retryable", false);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "error");
        data.put("turnId", turnId);
        data.put("error", error);

        String frame = "id: " + runId + ":" + seq + "\nevent: run_settled\ndata: "
                + objectMapper.writeValueAsString(data) + "\n\n";
        out.write(frame.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private ApiException upstreamError(String what, HttpResponse<String> response) {
        // Never echo the gateway body: it may contain internal details.
        return new ApiException(ErrorCode.UPSTREAM_ERROR,
                "gateway " + what + " failed with status " + response.statusCode());
    }

    private String toJson(Map<String, String> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INTERNAL, "failed to serialize gateway request", e);
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String readFully(InputStream in) {
        try (InputStream ignored = in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "(unreadable: " + e.getMessage() + ")";
        }
    }

    /** Mutable per-run projection counter; a run may emit several messages. */
    private static final class ProjectionState {
        private int nextOrdinal = 1;
        private long tokensIn;
        private long tokensOut;
        private long cacheReadTokens;
        private long cacheWriteTokens;
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder thinking = new StringBuilder();
    }
}
