package com.agentcore.server.common.quota;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.config.QuotaProperties;
import com.agentcore.server.config.UsageProperties;
import com.agentcore.server.dao.UsageDao;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Monthly token quota (ADR-0032). Usage is aggregated from {@code runs} — the
 * same shape the usage page reads — with a short Redis cache to absorb bursts.
 * The database stays the source of truth; the cache only avoids repeating the
 * same sum within a few seconds.
 */
@Service
public class QuotaService {

    private static final Logger log = LoggerFactory.getLogger(QuotaService.class);
    private static final Duration CACHE_TTL = Duration.ofSeconds(30);
    private static final String CACHE_PREFIX = "quota:used:";

    private final QuotaProperties quota;
    private final UsageProperties usage;
    private final UsageDao usageDao;
    private final QuotaUsageCache cache;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public QuotaService(
            QuotaProperties quota,
            UsageProperties usage,
            UsageDao usageDao,
            QuotaUsageCache cache,
            MeterRegistry meterRegistry) {
        this(quota, usage, usageDao, cache, meterRegistry, Clock.systemUTC());
    }

    QuotaService(
            QuotaProperties quota,
            UsageProperties usage,
            UsageDao usageDao,
            QuotaUsageCache cache,
            MeterRegistry meterRegistry,
            Clock clock) {
        this.quota = quota;
        this.usage = usage;
        this.usageDao = usageDao;
        this.cache = cache;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    public boolean enforced() {
        return quota.enforced();
    }

    /** Current status for the usage page; cached for a few seconds, never throws. */
    public QuotaStatus status(long userId) {
        long used = usedTokens(userId, true);
        if (!quota.enforced()) {
            return new QuotaStatus(false, null, used, null, null);
        }
        long limit = quota.monthlyTokens();
        return new QuotaStatus(
                true, limit, used, Math.max(0, limit - used), resetsAt());
    }

    /**
     * Rejects a new turn once the month's budget is spent. In-flight turns are
     * never interrupted: the check only runs when a turn starts.
     */
    public void requireWithinQuota(long userId) {
        if (!quota.enforced()) {
            return;
        }
        // Enforcement always reads MySQL: a stale cache would silently let a
        // burst of turns overshoot the budget for the whole TTL.
        long used = usedTokens(userId, false);
        long limit = quota.monthlyTokens();
        if (used < limit) {
            return;
        }
        long retryAfter = Math.max(1L, Duration.between(clock.instant(), resetsAt()).toSeconds());
        Counter.builder("agentcore_quota_rejections_total")
                .register(meterRegistry)
                .increment();
        log.warn("monthly token quota exhausted for user {}: used={} limit={} resetsAt={}",
                userId, used, limit, resetsAt());
        throw new ApiException(
                ErrorCode.QUOTA_EXCEEDED,
                "monthly token quota exhausted; resumes at " + resetsAt(),
                retryAfter);
    }

    private long usedTokens(long userId, boolean allowCache) {
        LocalDateTime monthStart = monthStartUtc();
        YearMonth month = YearMonth.from(monthStart.atOffset(ZoneOffset.UTC)
                .atZoneSameInstant(usage.offset()));
        String key = CACHE_PREFIX + userId + ":" + month;
        if (allowCache) {
            try {
                String cached = cache.get(key);
                if (cached != null) {
                    return Long.parseLong(cached);
                }
            } catch (RuntimeException ex) {
                // A cache outage must not break the usage page.
                log.debug("quota cache read failed for user {}: {}", userId, ex.getMessage());
            }
        }

        long used = usageDao.sumTokensSince(userId, monthStart);
        if (allowCache) {
            try {
                cache.put(key, Long.toString(used), CACHE_TTL);
            } catch (RuntimeException ex) {
                log.debug("quota cache write failed for user {}: {}", userId, ex.getMessage());
            }
        }
        return used;
    }

    /** First instant of the current UTC month, as stored in {@code runs.started_at}. */
    private LocalDateTime monthStartUtc() {
        LocalDate today = LocalDate.now(clock.withZone(usage.offset()));
        return today.withDayOfMonth(1)
                .atStartOfDay()
                .atOffset(usage.offset())
                .withOffsetSameInstant(ZoneOffset.UTC)
                .toLocalDateTime();
    }

    /** Next month's first instant, in UTC. */
    private Instant resetsAt() {
        LocalDate today = LocalDate.now(clock.withZone(usage.offset()));
        return today.withDayOfMonth(1)
                .plusMonths(1)
                .atStartOfDay()
                .atOffset(usage.offset())
                .toInstant();
    }
}
