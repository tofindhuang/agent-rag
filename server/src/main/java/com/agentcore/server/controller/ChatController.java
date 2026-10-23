package com.agentcore.server.controller;

import com.agentcore.server.common.auth.AuthenticatedUser;
import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.dto.chat.CreateSessionResponse;
import com.agentcore.server.dto.chat.SendMessageRequest;
import com.agentcore.server.service.ChatService;
import com.agentcore.server.service.PromptStream;

import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Chat API consumed by the Next.js frontend. The caller's identity comes from
 * the access token; request bodies never carry a user id.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/sessions")
    public CreateSessionResponse createSession(@AuthenticationPrincipal AuthenticatedUser user) {
        return new CreateSessionResponse(chatService.createSession(Long.toString(user.userId())));
    }

    @PostMapping(value = "/sessions/{sessionId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public StreamingResponseBody sendMessage(@PathVariable String sessionId, @RequestBody SendMessageRequest request) {
        if (request.text() == null || request.text().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "text is required");
        }
        PromptStream stream = chatService.openPrompt(sessionId, request.text());
        return out -> chatService.relayPrompt(stream, out);
    }

    @DeleteMapping("/sessions/{sessionId}")
    public void disposeSession(@PathVariable String sessionId) {
        chatService.disposeSession(sessionId);
    }
}
