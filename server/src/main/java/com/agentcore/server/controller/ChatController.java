package com.agentcore.server.controller;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.dto.chat.CreateSessionRequest;
import com.agentcore.server.dto.chat.CreateSessionResponse;
import com.agentcore.server.dto.chat.SendMessageRequest;
import com.agentcore.server.service.ChatService;

import java.io.IOException;
import java.io.InputStream;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Chat API consumed by the Next.js frontend.
 *
 * <p>M0 skeleton: no auth yet (FR-6 lands in M1 with JWT), so userId is trusted
 * from the request body for development only. This controller only validates
 * parameters and maps HTTP; gateway traffic lives in {@link ChatService}.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/sessions")
    public CreateSessionResponse createSession(@RequestBody CreateSessionRequest request) {
        if (request.userId() == null || request.userId().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "userId is required");
        }
        return new CreateSessionResponse(chatService.createSession(request.userId()));
    }

    @PostMapping(value = "/sessions/{sessionId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public StreamingResponseBody sendMessage(@PathVariable String sessionId, @RequestBody SendMessageRequest request) {
        if (request.text() == null || request.text().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "text is required");
        }
        InputStream upstream = chatService.openPrompt(sessionId, request.text());
        return out -> {
            // A mid-stream IOException here is a client disconnect; drop it.
            try (InputStream in = upstream) {
                in.transferTo(out);
                out.flush();
            } catch (IOException e) {
                // no-op: client went away
            }
        };
    }

    @DeleteMapping("/sessions/{sessionId}")
    public void disposeSession(@PathVariable String sessionId) {
        chatService.disposeSession(sessionId);
    }
}
