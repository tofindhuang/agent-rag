package com.agentcore.server.controller;

import com.agentcore.server.common.audit.AuditAction;
import com.agentcore.server.common.audit.AuditService;
import com.agentcore.server.common.auth.AuthenticatedUser;
import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.common.export.ExportService;
import com.agentcore.server.domain.User;
import com.agentcore.server.dao.UserDao;
import com.agentcore.server.dto.export.ExportRequest;
import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
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
    private final UserDao userDao;
    private final PasswordEncoder passwordEncoder;

    public ExportController(
            ExportService exportService,
            AuditService audit,
            UserDao userDao,
            PasswordEncoder passwordEncoder) {
        this.exportService = exportService;
        this.audit = audit;
        this.userDao = userDao;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping("/me")
    public ResponseEntity<StreamingResponseBody> export(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody(required = false) ExportRequest request) {
        long userId = user.userId();
        try {
            requirePassword(userId, request == null ? null : request.password());
            exportService.requireWithinDailyLimit(userId);
            exportService.requireWithinBudget(userId);
        } catch (ApiException ex) {
            audit.record(AuditAction.USER_DATA_EXPORT, AuditService.Outcome.FAILED,
                    userId, "user", String.valueOf(userId),
                    Map.of("reason", ex.code().name()));
            throw ex;
        }

        StreamingResponseBody body = (out) -> {
            try {
                exportService.write(userId, out);
                audit.success(AuditAction.USER_DATA_EXPORT, userId,
                        "user", String.valueOf(userId));
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

    /** Accounts with a local password must re-enter it; OAuth-only accounts may not. */
    private void requirePassword(long userId, String presented) {
        User user = userDao.selectById(userId);
        if (user == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "account not found");
        }
        if (user.getPasswordHash() == null) {
            return;
        }
        if (presented == null || presented.isBlank()
                || !passwordEncoder.matches(presented, user.getPasswordHash())) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "current password is required");
        }
    }
}
