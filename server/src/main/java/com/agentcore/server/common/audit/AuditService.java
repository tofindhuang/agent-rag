package com.agentcore.server.common.audit;

import com.agentcore.server.common.ratelimit.ClientIpResolver;
import com.agentcore.server.dao.AuditEventDao;
import com.agentcore.server.domain.AuditEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Writes the audit trail (ADR-0033). Best-effort by design: a failed audit
 * write must never fail the action being audited, but it is counted and logged
 * so the gap is visible.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final Logger auditLog = LoggerFactory.getLogger("audit");
    private static final int DETAIL_LIMIT_CHARS = 2_000;
    private static final int USER_AGENT_LIMIT = 255;

    private final AuditEventDao auditEventDao;
    private final ObjectMapper objectMapper;
    private final ClientIpResolver clientIpResolver;
    private final MeterRegistry meterRegistry;

    public AuditService(
            AuditEventDao auditEventDao,
            ObjectMapper objectMapper,
            ClientIpResolver clientIpResolver,
            MeterRegistry meterRegistry) {
        this.auditEventDao = auditEventDao;
        this.objectMapper = objectMapper;
        this.clientIpResolver = clientIpResolver;
        this.meterRegistry = meterRegistry;
    }

    public void success(
            AuditAction action, Long actorUserId, String targetType, String targetId) {
        record(action, Outcome.SUCCESS, actorUserId, targetType, targetId, null);
    }

    public void success(
            AuditAction action,
            Long actorUserId,
            String targetType,
            String targetId,
            Map<String, Object> detail) {
        record(action, Outcome.SUCCESS, actorUserId, targetType, targetId, detail);
    }

    public void record(
            AuditAction action,
            Outcome outcome,
            Long actorUserId,
            String targetType,
            String targetId,
            Map<String, Object> detail) {
        HttpServletRequest request = currentRequest();
        String ip = null;
        String userAgent = null;
        if (request != null) {
            try {
                ip = clientIpResolver.resolve(request);
            } catch (RuntimeException ex) {
                ip = request.getRemoteAddr();
            }
            userAgent = truncate(request.getHeader("User-Agent"), USER_AGENT_LIMIT);
        }
        AuditEvent event = new AuditEvent();
        event.setActorUserId(actorUserId);
        event.setActorType(actorUserId == null ? "anonymous" : "user");
        event.setAction(action.wire());
        event.setTargetType(targetType);
        event.setTargetId(targetId);
        event.setOutcome(outcome.wire());
        event.setIp(ip);
        event.setUserAgent(userAgent);
        event.setDetail(serialize(detail));
        event.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        try {
            auditEventDao.insert(event);
            logAuditLine(event);
        } catch (RuntimeException ex) {
            Counter.builder("agentcore_audit_write_failures_total")
                    .register(meterRegistry)
                    .increment();
            log.warn("audit write failed for {} (actor {}): {}",
                    event.getAction(), actorUserId, ex.getMessage());
        }
    }

    /** Spec: every successful write also logs one JSON line on logger "audit". */
    private void logAuditLine(AuditEvent event) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("action", event.getAction());
        line.put("outcome", event.getOutcome());
        line.put("actorUserId", event.getActorUserId());
        line.put("targetType", event.getTargetType());
        line.put("targetId", event.getTargetId());
        line.put("ip", event.getIp());
        line.put("createdAt", event.getCreatedAt());
        try {
            auditLog.info(objectMapper.writeValueAsString(line));
        } catch (JsonProcessingException ex) {
            // The row is already persisted; only the mirror line is lost.
            auditLog.info(
                    "audit action={} outcome={} actor={} target={}/{}",
                    event.getAction(), event.getOutcome(), event.getActorUserId(),
                    event.getTargetType(), event.getTargetId());
        }
    }

    private String serialize(Map<String, Object> detail) {
        if (detail == null || detail.isEmpty()) {
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(detail);
            return json.length() <= DETAIL_LIMIT_CHARS
                    ? json
                    : json.substring(0, DETAIL_LIMIT_CHARS);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** Result of an audited action. */
    public enum Outcome {
        SUCCESS("success"),
        DENIED("denied"),
        FAILED("failed");

        private final String wire;

        Outcome(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }
}
