package com.agentcore.server.common.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.config.QuotaProperties;
import com.agentcore.server.config.UsageProperties;
import com.agentcore.server.dao.UsageDao;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;


/** Quota arithmetic is time-sensitive; pin it with a fixed clock (ADR-0032). */
class QuotaServiceTest {

    private static final long USER_ID = 42L;
    private static final UsageProperties USAGE = new UsageProperties(
            "CNY", "+08:00", null, Map.of());

    private final UsageDao usageDao = mock(UsageDao.class);
    private final FakeCache cache = new FakeCache();

    private QuotaService service(long monthlyTokens, Instant now) {
        return new QuotaService(
                new QuotaProperties(monthlyTokens),
                USAGE,
                usageDao,
                cache,
                new SimpleMeterRegistry(),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    /** @see QuotaUsageCache */
    static final class FakeCache implements QuotaUsageCache {
        private final Map<String, String> values = new HashMap<>();

        @Override
        public String get(String key) {
            return values.get(key);
        }

        @Override
        public void put(String key, String value, java.time.Duration ttl) {
            values.put(key, value);
        }
    }

    @Test
    void doesNotTouchStorageWhenNoQuotaIsConfigured() {
        QuotaService quota = service(0, Instant.parse("2026-10-15T04:00:00Z"));

        quota.requireWithinQuota(USER_ID);

        verify(usageDao, never()).sumTokensSince(anyLong(), any());
    }

    @Test
    void allowsATurnBelowTheLimit() {
        QuotaService quota = service(1_000, Instant.parse("2026-10-15T04:00:00Z"));
        when(usageDao.sumTokensSince(anyLong(), any())).thenReturn(999L);

        quota.requireWithinQuota(USER_ID);

        verify(usageDao).sumTokensSince(anyLong(), any());
    }

    @Test
    void rejectsExactlyAtTheLimitWithAResetBasedRetryAfter() {
        QuotaService quota = service(1_000, Instant.parse("2026-10-15T04:00:00Z"));
        when(usageDao.sumTokensSince(anyLong(), any())).thenReturn(1_000L);

        assertThatThrownBy(() -> quota.requireWithinQuota(USER_ID))
                .isInstanceOf(ApiException.class)
                .satisfies((error) -> {
                    ApiException api = (ApiException) error;
                    assertThat(api.code()).isEqualTo(ErrorCode.QUOTA_EXCEEDED);
                    // 2026-10-15 04:00Z → 2026-10-31 16:00Z equals 16.5 days.
                    assertThat(api.retryAfterSeconds()).isEqualTo(16L * 24 * 3600 + 12 * 3600);
                });
    }

    @Test
    void countsFromTheFirstInstantOfTheMonthInTheConfiguredOffset() {
        // 2026-10-01 00:00 +08:00 == 2026-09-30T16:00Z.
        QuotaService quota = service(1_000, Instant.parse("2026-10-20T00:00:00Z"));
        when(usageDao.sumTokensSince(anyLong(), any())).thenReturn(0L);

        quota.requireWithinQuota(USER_ID);

        ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(usageDao).sumTokensSince(anyLong(), from.capture());
        assertThat(from.getValue()).isEqualTo(LocalDateTime.parse("2026-09-30T16:00:00"));
    }

    @Test
    void reportsUsedTokensEvenWithoutAQuota() {
        QuotaService quota = service(0, Instant.parse("2026-10-15T04:00:00Z"));
        when(usageDao.sumTokensSince(anyLong(), any())).thenReturn(1_234L);

        QuotaStatus status = quota.status(USER_ID);

        assertThat(status.enforced()).isFalse();
        assertThat(status.usedTokens()).isEqualTo(1_234L);
        assertThat(status.limitTokens()).isNull();
        assertThat(status.resetsAt()).isNull();
    }

    @Test
    void servesTheUsagePageFromTheCacheWhenWarm() {
        QuotaService quota = service(1_000, Instant.parse("2026-10-15T04:00:00Z"));
        cache.put("quota:used:42:2026-10", "900", java.time.Duration.ofSeconds(30));

        QuotaStatus status = quota.status(USER_ID);

        assertThat(status.usedTokens()).isEqualTo(900L);
        assertThat(status.remainingTokens()).isEqualTo(100L);
        verify(usageDao, never()).sumTokensSince(anyLong(), any());
    }
}
