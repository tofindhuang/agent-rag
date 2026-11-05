package com.agentcore.server.common.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentcore.server.common.ratelimit.ClientIpResolver;
import com.agentcore.server.config.RateLimitProperties;
import com.agentcore.server.dao.AuditEventDao;
import com.agentcore.server.domain.AuditEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

/** The trail must never break the action it records (ADR-0033). */
class AuditServiceTest {

    private final AuditEventDao dao = mock(AuditEventDao.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AuditService service = new AuditService(
            dao,
            new ObjectMapper(),
            new ClientIpResolver(new RateLimitProperties(
                    true, 120, 100_000, 10, Duration.ofSeconds(60), List.of())),
            registry);

    @Test
    void recordsMetadataWithoutSecrets() {
        service.success(AuditAction.MODEL_PROVIDER_UPDATE, 7L, "provider", "3",
                Map.of("apiKeyChanged", true));

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(dao).insert(captor.capture());
        AuditEvent event = captor.getValue();
        assertThat(event.getAction()).isEqualTo("model.provider.update");
        assertThat(event.getOutcome()).isEqualTo("success");
        assertThat(event.getActorType()).isEqualTo("user");
        assertThat(event.getDetail()).isEqualTo("{\"apiKeyChanged\":true}");
    }

    @Test
    void swallowsPersistenceFailuresAndCountsThem() {
        org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("db down"))
                .when(dao).insert(org.mockito.ArgumentMatchers.<AuditEvent>any());

        assertThatCode(() -> service.success(AuditAction.AUTH_LOGIN, 1L, "user", "1"))
                .doesNotThrowAnyException();

        assertThat(registry.counter("agentcore_audit_write_failures_total").count())
                .isEqualTo(1.0);
    }
}
