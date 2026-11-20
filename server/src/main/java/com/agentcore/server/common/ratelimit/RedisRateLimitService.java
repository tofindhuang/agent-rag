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
 * Redis token bucket (ADR-0030). One Lua script refills by elapsed time and
 * either peeks, consumes or debits the bucket atomically, so the same numbers
 * hold across instances. Redis failures fail open and are counted.
 */
@Service
public class RedisRateLimitService implements RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimitService.class);
    private static final long WARN_INTERVAL_MS = 10_000L;
    private static final String BUCKET_SCRIPT_TEXT = """
            local capacity = tonumber(ARGV[1])
            local ratePerSec = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local cost = tonumber(ARGV[4])
            local ttl = tonumber(ARGV[5])
            local floor = tonumber(ARGV[6])
            local mode = ARGV[7]
            local data = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
            local tokens = tonumber(data[1])
            local ts = tonumber(data[2])
            if tokens == nil or ts == nil then
              tokens = capacity
              ts = now
            end
            if now > ts then
              tokens = math.min(capacity, tokens + (now - ts) * ratePerSec / 1000.0)
              ts = now
            end
            local allowed = 1
            if mode == 'peek' then
              if tokens <= 0 then allowed = 0 end
            elseif mode == 'consume' then
              if tokens < cost then
                allowed = 0
              else
                tokens = tokens - cost
              end
            else
              tokens = tokens - cost
              if tokens < floor then tokens = floor end
            end
            redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(ts))
            redis.call('PEXPIRE', KEYS[1], ttl)
            local retryMs = 0
            if allowed == 0 and ratePerSec > 0 then
              retryMs = math.ceil((cost - tokens) * 1000.0 / ratePerSec)
            end
            return {allowed, math.floor(tokens), retryMs}
            """;
    private static final DefaultRedisScript<List> BUCKET_SCRIPT =
            new DefaultRedisScript<>(BUCKET_SCRIPT_TEXT, List.class);

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
        return consume("user-rpm", "rl:bucket:user-rpm:" + userId, properties.userRpm());
    }

    @Override
    public RateLimitDecision checkAuthIpRpm(String clientIp) {
        return consume("ip-rpm", "rl:bucket:ip-rpm:" + clientIp, properties.authIpRpm());
    }

    @Override
    public RateLimitDecision checkUserTpm(long userId) {
        return peek("user-tpm", "rl:bucket:user-tpm:" + userId, properties.userTpm());
    }

    @Override
    public void addUserTokens(long userId, long tokens) {
        if (!enabled() || tokens <= 0) {
            return;
        }
        try {
            apply("user-tpm", "rl:bucket:user-tpm:" + userId, properties.userTpm(), tokens, Mode.DEBIT);
        } catch (RuntimeException ex) {
            recordFailOpen("user-tpm");
            warnThrottled("fail-open:user-tpm",
                    "rate-limit token accounting failed; dropping tokens: " + ex.getMessage());
        }
    }

    private boolean enabled() {
        return properties.enabled();
    }

    private RateLimitDecision consume(String scope, String key, int limit) {
        return check(scope, key, limit, 1, Mode.CONSUME);
    }

    private RateLimitDecision peek(String scope, String key, int limit) {
        return check(scope, key, limit, 1, Mode.PEEK);
    }

    private RateLimitDecision check(
            String scope, String key, int limit, long cost, Mode mode) {
        if (!enabled()) {
            return RateLimitDecision.permit();
        }
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            Bucket bucket = apply(scope, key, limit, cost, mode);
            boolean allowed = bucket.allowed();
            long retryAfter = allowed ? 0 : retryAfterSeconds(bucket.retryAfterMs());
            recordCheck(scope, allowed);
            if (!allowed) {
                recordRejection(scope);
                warnThrottled(scope, "rate limit exceeded scope=" + scope
                        + " limit=" + limit + " retryAfter=" + retryAfter + "s");
            }
            return new RateLimitDecision(allowed, retryAfter);
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

    private Bucket apply(String scope, String key, int limit, long cost, Mode mode) {
        long windowMs = Math.max(1_000L, properties.window().toMillis());
        double ratePerSec = (double) limit * 1_000.0 / windowMs;
        List<?> result = redis.execute(
                BUCKET_SCRIPT,
                List.of(key),
                String.valueOf(limit),
                String.valueOf(ratePerSec),
                String.valueOf(System.currentTimeMillis()),
                String.valueOf(cost),
                String.valueOf(windowMs),
                String.valueOf(-limit),
                mode.name().toLowerCase());
        boolean allowed = result != null && !result.isEmpty() && number(result.get(0)) > 0;
        long retryAfterMs = result != null && result.size() > 2 ? number(result.get(2)) : 0;
        return new Bucket(allowed, Math.max(0, retryAfterMs));
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

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(value.toString());
    }

    private static long retryAfterSeconds(long retryAfterMs) {
        return Math.max(1L, (retryAfterMs + 999L) / 1_000L);
    }

    private enum Mode {
        /** Report whether any budget is left without spending it (TPM pre-check). */
        PEEK,
        /** Spend {@code cost} when the bucket has it (RPM and auth IP). */
        CONSUME,
        /** Account real usage even past empty; debt is capped at one capacity. */
        DEBIT
    }

    private record Bucket(boolean allowed, long retryAfterMs) {}
}
