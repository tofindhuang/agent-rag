package com.agentcore.server.controller;

import com.agentcore.server.common.audit.AuditAction;
import com.agentcore.server.common.audit.AuditService;
import com.agentcore.server.common.auth.AuthenticatedUser;
import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.service.ExportService;
import com.agentcore.server.dto.export.ExportRequest;
import com.agentcore.server.service.AuthService;
import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** Self-service personal data export (ADR-0034). */
@RestController
@RequestMapping("/api/export")
public class ExportController {

    private final ExportService exportService;
    private final AuditService audit;
    private final AuthService authService;

    public ExportController(
            ExportService exportService,
            AuditService audit,
            AuthService authService) {
        this.exportService = exportService;
        this.audit = audit;
        this.authService = authService;
    }

    @PostMapping("/me")
    public ResponseEntity<StreamingResponseBody> export(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody(required = false) ExportRequest request) {
        long userId = user.userId();
        try {
            authService.verifyCurrentPassword(userId, request == null ? null : request.password());
            exportService.requireWithinBudget(userId);
            exportService.requireWithinDailyLimit(userId);
        } catch (ApiException ex) {
            audit.record(AuditAction.USER_DATA_EXPORT, AuditService.Outcome.FAILED,
                    userId, "user", String.valueOf(userId),
                    Map.of("reason", failureReason(ex.code())));
            throw ex;
        }

        StreamingResponseBody body = (out) -> {
            try {
                long entries = exportService.write(userId, out);
                audit.success(AuditAction.USER_DATA_EXPORT, userId,
                        "user", String.valueOf(userId), Map.of("entries", entries));
            } catch (IOException | RuntimeException ex) {
                audit.record(AuditAction.USER_DATA_EXPORT, AuditService.Outcome.FAILED,
                        userId, "user", String.valueOf(userId),
                        Map.of("reason", "stream_failed"));
                throw ex;
            }
        };

        String filename = "agent-core-export-" + LocalDate.now() + ".zip";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(body);
    }

    /** Spec failure tokens for the audit detail (data-export spec §2). */
    private static String failureReason(ErrorCode code) {
        return switch (code) {
            case UNAUTHORIZED -> "wrong_password";
            case EXPORT_TOO_LARGE -> "too_large";
            case RATE_LIMITED -> "rate_limited";
            default -> code.name().toLowerCase(java.util.Locale.ROOT);
        };
    }
}
