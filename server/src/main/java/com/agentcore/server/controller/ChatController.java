package com.agentcore.server.controller;

import com.agentcore.server.common.auth.AuthenticatedUser;
import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.domain.Message;
import com.agentcore.server.domain.MessagePage;
import com.agentcore.server.domain.Session;
import com.agentcore.server.dto.chat.CreateSessionRequest;
import com.agentcore.server.dto.chat.CreateSessionResponse;
import com.agentcore.server.dto.chat.MessageListResponse;
import com.agentcore.server.dto.chat.MessageSummary;
import com.agentcore.server.dto.chat.RenameSessionRequest;
import com.agentcore.server.dto.chat.SendMessageRequest;
import com.agentcore.server.dto.chat.SessionListResponse;
import com.agentcore.server.dto.chat.SessionSummary;
import com.agentcore.server.dto.chat.SwitchModelRequest;
import com.agentcore.server.service.ChatService;
import com.agentcore.server.service.PromptStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Chat API consumed by the Next.js frontend. The caller's identity comes from
 * the access token; request bodies never carry a user id.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_TITLE_LENGTH = 255;

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/sessions")
    public CreateSessionResponse createSession(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody(required = false) CreateSessionRequest request) {
        String title = request == null ? null : request.title();
        if (title != null && title.trim().length() > MAX_TITLE_LENGTH) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "title must be at most 255 characters");
        }
        Long modelId = request == null ? null : request.modelId();
        boolean useSystemDefault = request != null && Boolean.TRUE.equals(request.useSystemDefault());
        if (useSystemDefault && modelId != null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "modelId and useSystemDefault are mutually exclusive");
        }
        return new CreateSessionResponse(
                chatService.createSession(user.userId(), title, modelId, useSystemDefault));
    }

    @PostMapping("/sessions/{sessionId}/model")
    public void switchModel(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String sessionId,
            @RequestBody SwitchModelRequest request) {
        chatService.switchModel(user.userId(), sessionId, request.modelId());
    }

    @GetMapping("/sessions")
    public SessionListResponse listSessions(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int limit) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "limit must be between 1 and 100");
        }
        SessionCursor after = cursor == null || cursor.isBlank() ? null : SessionCursor.decode(cursor);

        List<Session> fetched = chatService.listSessions(
                user.userId(),
                after == null ? null : after.lastActiveAt(),
                after == null ? null : after.sessionId(),
                limit + 1);
        boolean hasMore = fetched.size() > limit;
        List<Session> sessions = hasMore ? fetched.subList(0, limit) : fetched;

        String nextCursor = hasMore
                ? SessionCursor.encode(sessions.get(sessions.size() - 1))
                : null;
        return new SessionListResponse(
                sessions.stream().map(ChatController::toSummary).toList(), nextCursor);
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public MessageListResponse history(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String sessionId,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int limit) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "limit must be between 1 and 100");
        }
        MessagePage page = chatService.history(user.userId(), sessionId, beforeId, limit);
        return new MessageListResponse(
                page.items().stream().map(ChatController::toView).toList(),
                page.hasMore(),
                page.nextBeforeId());
    }

    @PatchMapping("/sessions/{sessionId}")
    public void renameSession(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String sessionId,
            @RequestBody RenameSessionRequest request) {
        String title = request.title() == null ? "" : request.title().trim();
        if (title.isEmpty() || title.length() > MAX_TITLE_LENGTH) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "title must be 1-255 characters");
        }
        chatService.renameSession(user.userId(), sessionId, title);
    }

    @DeleteMapping("/sessions/{sessionId}")
    public void archiveSession(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String sessionId) {
        chatService.archiveSession(user.userId(), sessionId);
    }

    @PostMapping(value = "/sessions/{sessionId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public StreamingResponseBody sendMessage(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String sessionId,
            @RequestBody SendMessageRequest request) {
        if (request.text() == null || request.text().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "text is required");
        }
        PromptStream stream = chatService.openPrompt(
                user.userId(), sessionId, request.text(), request.turnId());
        return out -> chatService.relayPrompt(stream, out);
    }

    private static MessageSummary toView(Message message) {
        return new MessageSummary(
                message.getId(),
                message.getTurnId(),
                message.getOrdinal(),
                message.getRole(),
                message.getContent(),
                message.getThinking(),
                message.getStatus(),
                message.getDeliveryStatus(),
                toInstant(message.getCreatedAt()));
    }

    private static SessionSummary toSummary(Session session) {
        return new SessionSummary(
                session.getId(),
                session.getTitle(),
                session.getModelLabel(),
                session.getModelSource(),
                toInstant(session.getLastActiveAt()),
                toInstant(session.getCreatedAt()));
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    /** Composite cursor, base64url(&lt;epochMicros&gt;:&lt;sessionId&gt;); queries stay user-scoped. */
    private record SessionCursor(LocalDateTime lastActiveAt, String sessionId) {

        static String encode(Session session) {
            Instant activeAt = session.getLastActiveAt().toInstant(ZoneOffset.UTC);
            long micros = activeAt.getEpochSecond() * 1_000_000 + activeAt.getNano() / 1_000;
            String value = micros + ":" + session.getId();
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(value.getBytes(StandardCharsets.UTF_8));
        }

        static SessionCursor decode(String cursor) {
            try {
                String value = new String(
                        Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                int colon = value.indexOf(':');
                long micros = Long.parseLong(value.substring(0, colon));
                String sessionId = value.substring(colon + 1);
                LocalDateTime at = LocalDateTime.ofInstant(
                        Instant.ofEpochSecond(micros / 1_000_000, (micros % 1_000_000) * 1_000),
                        ZoneOffset.UTC);
                return new SessionCursor(at, sessionId);
            } catch (RuntimeException ex) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "invalid cursor");
            }
        }
    }
}
