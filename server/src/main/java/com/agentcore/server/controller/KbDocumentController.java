package com.agentcore.server.controller;

import com.agentcore.server.common.auth.AuthenticatedUser;
import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.common.kb.KbAvailability;
import com.agentcore.server.common.kb.KbFileTypes;
import com.agentcore.server.domain.KbDocument;
import com.agentcore.server.dto.kb.KbDocumentCreatedResponse;
import com.agentcore.server.dto.kb.KbDocumentListResponse;
import com.agentcore.server.dto.kb.KbDocumentView;
import com.agentcore.server.service.KbDocumentService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** User-facing KB document management; originals never leave the server. */
@RestController
@RequestMapping("/api/kb/documents")
public class KbDocumentController {

    private final KbDocumentService documentService;
    private final KbAvailability availability;

    public KbDocumentController(
            KbDocumentService documentService, KbAvailability availability) {
        this.documentService = documentService;
        this.availability = availability;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public KbDocumentCreatedResponse uploadFile(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestPart("file") MultipartFile file) {
        availability.requireEnabled();
        if (file == null || file.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "file is required");
        }
        if (file.getSize() > KbFileTypes.MAX_UPLOAD_BYTES) {
            throw new ApiException(ErrorCode.KB_FILE_TOO_LARGE, "file exceeds the 20 MB limit");
        }
        String filename = file.getOriginalFilename();
        KbFileTypes.requireMime(filename);

        Path temp = null;
        try {
            temp = Files.createTempFile("kb-upload-", ".tmp");
            file.transferTo(temp);
            KbDocument document = documentService.uploadFile(user.userId(), filename, temp);
            return new KbDocumentCreatedResponse(document.getId(), document.getStatus());
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INTERNAL, "cannot read uploaded file", e);
        } finally {
            deleteQuietly(temp);
        }
    }

    @PostMapping(consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public KbDocumentCreatedResponse uploadUrl(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam("url") String url) {
        availability.requireEnabled();
        if (url == null || url.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "url is required");
        }
        if (url.length() > KbFileTypes.MAX_URL_LENGTH) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "url must be at most 2048 characters");
        }
        KbDocument document = documentService.uploadUrl(user.userId(), url);
        return new KbDocumentCreatedResponse(document.getId(), document.getStatus());
    }

    @GetMapping
    public KbDocumentListResponse list(@AuthenticationPrincipal AuthenticatedUser user) {
        List<KbDocumentView> items = documentService.list(user.userId()).stream()
                .map(KbDocumentController::toView)
                .toList();
        return new KbDocumentListResponse(items);
    }

    @GetMapping("/{documentId}")
    public KbDocumentView detail(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String documentId) {
        return toView(documentService.detail(user.userId(), documentId));
    }

    @DeleteMapping("/{documentId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void delete(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String documentId) {
        documentService.delete(user.userId(), documentId);
    }

    private static KbDocumentView toView(KbDocument document) {
        return new KbDocumentView(
                document.getId(),
                document.getTitle(),
                document.getSourceType(),
                document.getSourceUrl(),
                document.getMime(),
                document.getSizeBytes() == null ? 0 : document.getSizeBytes(),
                document.getStatus(),
                document.getChunkCount() == null ? 0 : document.getChunkCount(),
                document.getErrorCode(),
                document.getErrorMessage(),
                toInstant(document.getCreatedAt()),
                toInstant(document.getUpdatedAt()));
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Temp files are best effort; ingestion already has its own copy.
        }
    }
}
