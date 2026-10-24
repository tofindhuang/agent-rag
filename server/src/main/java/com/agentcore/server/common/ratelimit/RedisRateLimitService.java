package com.agentcore.server.common.ratelimit;

import com.agentcore.server.config.RateLimitProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/**
 * Redis fixed-window limiter (ADR-0018). One Lua script increments and sets the
 * remaining-window TTL atomically; failures fail open and are counted.
 */
@Service
public class RedisRateLimitService implements RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimitService.class);
    private static final long WARN_INTERVAL_MS = 10_000L;
    private static final String INCR_SCRIPT_TEXT = """
            local count = redis.call('INCRBY', KEYS[1], ARGV[1])
            local ttl = redis.call('PTTL', KEYS[1])
            if ttl < 0 then
              redis.call('PEXPIRE', KEYS[1], ARGV[2])
              ttl = tonumber(ARGV[2])
            end
            return {count, ttl}
            """;
    private static final String READ_SCRIPT_TEXT = """
            local value = tonumber(redis.call('GET', KEYS[1]) or '0')
            return {value, redis.call('PTTL', KEYS[1])}
            """;
    private static final DefaultRedisScript<List> INCR_SCRIPT =
            new DefaultRedisScript<>(INCR_SCRIPT_TEXT, List.class);
    private static final DefaultRedisScript<List> READ_SCRIPT =
            new DefaultRedisScript<>(READ_SCRIPT_TEXT, List.class);

    private final StringRedisTemplate redis;
    private final RateLimitProperties properties;
    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<String, AtomicLong> lastWarnings = new ConcurrentHashMap<>();

    public RedisRateLimitService(
            StringRedisTemplate redis,
            RateLimitProperties properties,
            MeterRegistry meterRegistry) {
        this.redis = redis;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public RateLimitDecision checkUserRpm(long userId) {
        return check("user-rpm", "rl:rpm:user:" + userId, properties.userRpm(), true);
    }

    @Override
    public RateLimitDecision checkAuthIpRpm(String clientIp) {
        return check("ip-rpm", "rl:rpm:ip:" + clientIp, properties.authIpRpm(), true);
    }

    @Override
    public RateLimitDecision checkUserTpm(long userId) {
        return check("user-tpm", "rl:tpm:user:" + userId, properties.userTpm(), false);
    }

    @Override
    public void addUserTokens(long userId, long tokens) {
        if (!enabled() || tokens <= 0) {
            return;
        }
        Window window = window();
        try {
            redis.execute(
                    INCR_SCRIPT,
                    List.of(window.key("rl:tpm:user:" + userId)),
                    String.valueOf(tokens),
                    String.valueOf(window.ttlMillis()));
        } catch (RuntimeException ex) {
            recordFailOpen("user-tpm");
            warnThrottled("fail-open:user-tpm",
                    "rate-limit token accounting failed; dropping tokens: " + ex.getMessage());
        }
    }

    @Override
    public boolean enabled() {
        return properties.enabled();
    }

    private RateLimitDecision check(String scope, String keyPrefix, int limit, boolean increment) {
        if (!enabled()) {
            return RateLimitDecision.permit();
        }
        Window window = window();
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            List<?> result = increment
                    ? redis.execute(
                            INCR_SCRIPT,
                            List.of(window.key(keyPrefix)),
                            "1",
                            String.valueOf(window.ttlMillis()))
                    : redis.execute(READ_SCRIPT, List.of(window.key(keyPrefix)));
            long count = result == null || result.isEmpty() ? 0 : number(result.get(0));
            long ttl = result == null || result.size() < 2 ? window.ttlMillis() : number(result.get(1));
            if (ttl <= 0) {
                ttl = window.ttlMillis();
            }
            boolean allowed = increment ? count <= limit : count < limit;
            long retryAfter = retryAfterSeconds(ttl);
            recordCheck(scope, allowed);
            if (!allowed) {
                recordRejection(scope);
                warnThrottled(scope, "rate limit exceeded scope=" + scope
                        + " count=" + count + " limit=" + limit + " retryAfter=" + retryAfter + "s");
            }
            return new RateLimitDecision(allowed, allowed ? 0 : retryAfter);
        } catch (RuntimeException ex) {
            recordFailOpen(scope);
            warnThrottled("fail-open:" + scope,
                    "rate-limit check failed; allowing request (" + scope + "): " + ex.getMessage());
            return RateLimitDecision.permit();
        } finally {
            sample.stop(Timer.builder("agentcore_rate_limit_check_seconds")
                    .tag("scope", scope)
                    .register(meterRegistry));
        }
    }

    private void recordCheck(String scope, boolean allowed) {
        Counter.builder("agentcore_rate_limit_checks_total")
                .tag("scope", scope)
                .tag("result", allowed ? "allowed" : "rejected")
                .register(meterRegistry)
                .increment();
    }

    private void recordRejection(String scope) {
        Counter.builder("agentcore_rate_limit_rejections_total")
                .tag("scope", scope)
                .register(meterRegistry)
                .increment();
    }

    private void recordFailOpen(String scope) {
        Counter.builder("agentcore_rate_limit_fail_open_total")
                .tag("scope", scope)
                .register(meterRegistry)
                .increment();
    }

    private void warnThrottled(String key, String message) {
        long now = System.currentTimeMillis();
        AtomicLong last = lastWarnings.computeIfAbsent(key, ignored -> new AtomicLong());
        long previous = last.get();
        if (now - previous >= WARN_INTERVAL_MS && last.compareAndSet(previous, now)) {
            log.warn(message);
        }
    }

    private Window window() {
        long windowMs = Math.max(1_000L, properties.window().toMillis());
        long now = System.currentTimeMillis();
        long start = now - (now % windowMs);
        long index = start / windowMs;
        long ttl = Math.max(1L, start + windowMs - now);
        return new Window(index, ttl);
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(value.toString());
    }

    private static long retryAfterSeconds(long ttlMillis) {
        return Math.max(1L, (ttlMillis + 999L) / 1_000L);
    }

    private record Window(long index, long ttlMillis) {

        String key(String prefix) {
            return prefix + ":" + index;
        }
    }
}
