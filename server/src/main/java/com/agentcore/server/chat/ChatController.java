package com.agentcore.server.chat;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Chat API consumed by the Next.js frontend.
 *
 * M0 skeleton: no auth yet (FR-6 lands in M1 with JWT), so userId is trusted
 * from the request body for development only.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    public record CreateSessionRequest(String userId) {}

    public record MessageRequest(String text) {}

    public record SessionResponse(String sessionId) {}

    @PostMapping("/sessions")
    public SessionResponse createSession(@RequestBody CreateSessionRequest request) {
        if (request.userId() == null || request.userId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId is required");
        }
        return new SessionResponse(chatService.createSession(request.userId()));
    }

    @PostMapping(value = "/sessions/{sessionId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public StreamingResponseBody sendMessage(@PathVariable String sessionId, @RequestBody MessageRequest request) {
        if (request.text() == null || request.text().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "text is required");
        }
        return chatService.streamPrompt(sessionId, request.text());
    }

    @DeleteMapping("/sessions/{sessionId}")
    public void disposeSession(@PathVariable String sessionId) {
        chatService.disposeSession(sessionId);
    }
}
