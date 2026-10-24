package com.agentcore.server.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentcore.server.config.RateLimitProperties;
import com.agentcore.server.support.AbstractIntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

class RateLimitServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private StringRedisTemplate redis;

    @Test
    void allowsTheLimitAndRejectsTheNextRequestWithRetryAfter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RateLimitService service = service(registry, true, 2, 100, 10, Duration.ofSeconds(60));
        long userId = randomUserId();

        assertThat(service.checkUserRpm(userId).allowed()).isTrue();
        assertThat(service.checkUserRpm(userId).allowed()).isTrue();
        RateLimitDecision rejected = service.checkUserRpm(userId);

        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isBetween(1L, 60L);
        assertThat(counter(registry, "agentcore_rate_limit_checks_total",
                "scope", "user-rpm", "result", "allowed")).isEqualTo(2.0);
        assertThat(counter(registry, "agentcore_rate_limit_checks_total",
                "scope", "user-rpm", "result", "rejected")).isEqualTo(1.0);
        assertThat(counter(registry, "agentcore_rate_limit_rejections_total",
                "scope", "user-rpm")).isEqualTo(1.0);
    }

    @Test
    void windowResetAllowsRequestsAgain() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RateLimitService service = service(registry, true, 1, 100, 10, Duration.ofSeconds(1));
        long userId = randomUserId();

        assertThat(service.checkUserRpm(userId).allowed()).isTrue();
        RateLimitDecision rejected = service.checkUserRpm(userId);
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isEqualTo(1);

        Thread.sleep(1_100L);

        assertThat(service.checkUserRpm(userId).allowed()).isTrue();
    }

    @Test
    void tpmReadsAndAccumulatesTokensWithoutIncrementingOnCheck() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RateLimitService service = service(registry, true, 100, 100, 10, Duration.ofSeconds(60));
        long userId = randomUserId();

        service.addUserTokens(userId, 60);
        assertThat(service.checkUserTpm(userId).allowed()).isTrue();
        assertThat(service.checkUserTpm(userId).allowed()).isTrue();

        service.addUserTokens(userId, 50);
        RateLimitDecision rejected = service.checkUserTpm(userId);
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isBetween(1L, 60L);
        assertThat(counter(registry, "agentcore_rate_limit_checks_total",
                "scope", "user-tpm", "result", "rejected")).isEqualTo(1.0);
    }

    @Test
    void redisFailureFailsOpenAndCountsIt() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", 1),
                LettuceClientConfiguration.builder()
                        .commandTimeout(Duration.ofMillis(300))
                        .build());
        factory.afterPropertiesSet();
        StringRedisTemplate broken = new StringRedisTemplate(factory);
        broken.afterPropertiesSet();
        try {
            RedisRateLimitService service = new RedisRateLimitService(
                    broken,
                    new RateLimitProperties(true, 1, 100, 1, Duration.ofSeconds(60)),
                    registry);

            assertThat(service.checkUserRpm(randomUserId()).allowed()).isTrue();
            assertThat(counter(registry, "agentcore_rate_limit_fail_open_total",
                    "scope", "user-rpm")).isEqualTo(1.0);
        } finally {
            factory.destroy();
        }
    }

    @Test
    void disabledModeAllowsEverythingWithoutWritingRedis() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RateLimitService service = service(registry, false, 1, 1, 1, Duration.ofSeconds(60));
        long userId = randomUserId();

        assertThat(service.checkUserRpm(userId).allowed()).isTrue();
        assertThat(service.checkUserRpm(userId).allowed()).isTrue();
        assertThat(service.checkUserTpm(userId).allowed()).isTrue();
        assertThat(redis.hasKey("rl:rpm:user:" + userId)).isFalse();
    }

    private RateLimitService service(
            SimpleMeterRegistry registry,
            boolean enabled,
            int userRpm,
            int userTpm,
            int authIpRpm,
            Duration window) {
        return new RedisRateLimitService(
                redis,
                new RateLimitProperties(enabled, userRpm, userTpm, authIpRpm, window),
                registry);
    }

    private static double counter(
            SimpleMeterRegistry registry, String name, String... tags) {
        return registry.get(name).tags(tags).counter().count();
    }

    private static long randomUserId() {
        return ThreadLocalRandom.current().nextLong(10_000_000L, Long.MAX_VALUE);
    }
}
