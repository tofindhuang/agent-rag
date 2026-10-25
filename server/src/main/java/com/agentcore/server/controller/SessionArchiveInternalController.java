package com.agentcore.server.controller;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.common.storage.StoredObject;
import com.agentcore.server.service.SessionArchiveService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Gateway-only archive transport (ADR-0025). Java is the only service holding
 * object-storage credentials; the gateway streams bytes through this endpoint.
 */
@RestController
@RequestMapping("/api/internal/session-archives")
public class SessionArchiveInternalController {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");

    private final SessionArchiveService archiveService;

    public SessionArchiveInternalController(SessionArchiveService archiveService) {
        this.archiveService = archiveService;
    }

    @PutMapping("/{sessionId}/jsonl")
    public ResponseEntity<Void> putJsonl(
            @PathVariable String sessionId,
            @RequestHeader("X-User-Id") long userId,
            @RequestHeader("X-Sha256") String sha256,
            HttpServletRequest request) {
        archiveService.storeJsonl(sessionId, userId, body(request), requireSha(sha256));
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{sessionId}/meta")
    public ResponseEntity<Void> putMeta(
            @PathVariable String sessionId,
            @RequestHeader("X-User-Id") long userId,
            @RequestHeader("X-Sha256") String sha256,
            HttpServletRequest request) {
        archiveService.storeMeta(sessionId, userId, body(request), requireSha(sha256));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{sessionId}/jsonl")
    public ResponseEntity<StreamingResponseBody> getJsonl(
            @PathVariable String sessionId, @RequestHeader("X-User-Id") long userId) {
        return stream(archiveService.loadJsonl(sessionId, userId), MediaType.parseMediaType("application/gzip"));
    }

    @GetMapping("/{sessionId}/meta")
    public ResponseEntity<StreamingResponseBody> getMeta(
            @PathVariable String sessionId, @RequestHeader("X-User-Id") long userId) {
        return stream(archiveService.loadMeta(sessionId, userId), MediaType.APPLICATION_JSON);
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> delete(
            @PathVariable String sessionId, @RequestHeader("X-User-Id") long userId) {
        archiveService.delete(sessionId, userId);
        return ResponseEntity.noContent().build();
    }

    private static InputStream body(HttpServletRequest request) {
        try {
            return request.getInputStream();
        } catch (IOException ex) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "cannot read archive body", ex);
        }
    }

    private static String requireSha(String sha256) {
        if (sha256 == null || !SHA256.matcher(sha256).matches()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "X-Sha256 must be a hex sha256");
        }
        return sha256.toLowerCase();
    }

    private static ResponseEntity<StreamingResponseBody> stream(
            StoredObject stored, MediaType contentType) {
        StreamingResponseBody body = out -> {
            try (stored) {
                stored.body().transferTo(out);
            }
        };
        return ResponseEntity.ok()
                .contentType(contentType)
                .contentLength(stored.size())
                .header("X-Sha256", stored.sha256() == null ? "" : stored.sha256())
                .body(body);
    }
}
