package com.agentcore.server.support;

import com.agentcore.server.common.audit.AuditAction;
import com.agentcore.server.common.audit.AuditService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Test double for {@link AuditService}: records calls instead of writing rows.
 * A real subclass (not a Mockito mock) because this JVM's Byte Buddy cannot
 * instrument concrete classes.
 */
public class RecordingAudit extends AuditService {

    public record Call(
            AuditAction action,
            Outcome outcome,
            Long actorUserId,
            String targetType,
            String targetId,
            Map<String, Object> detail) {}

    private final List<Call> calls = new ArrayList<>();

    public RecordingAudit() {
        super(null, new com.fasterxml.jackson.databind.ObjectMapper(), null,
                new SimpleMeterRegistry());
    }

    @Override
    public void record(
            AuditAction action,
            Outcome outcome,
            Long actorUserId,
            String targetType,
            String targetId,
            Map<String, Object> detail) {
        calls.add(new Call(action, outcome, actorUserId, targetType, targetId, detail));
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    public boolean contains(AuditAction action) {
        return calls.stream().anyMatch((call) -> call.action() == action);
    }
}
