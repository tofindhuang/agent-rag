package com.agentcore.server.common.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.config.SandboxProperties;
import com.agentcore.server.support.AbstractIntegrationTest;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;

/** Real Redis coverage for FR-3 AC4 (queue rather than fail) and permit leases. */
class SandboxAdmissionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private RedissonClient redisson;

    @Test
    void perUserRequestQueuesUntilAPermitIsReleased() throws Exception {
        long userId = randomUserId();
        SandboxAdmission admission = new RedisSandboxAdmission(
                redisson, properties(1, Duration.ofSeconds(5), Duration.ofSeconds(30)));
        SandboxPermit first = admission.acquire(userId);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<SandboxPermit> second = executor.submit(() -> admission.acquire(userId));
            Thread.sleep(200);
            assertThat(second.isDone()).isFalse();

            first.release();
            SandboxPermit secondPermit = second.get(3, TimeUnit.SECONDS);
            assertThat(secondPermit).isNotNull();
            secondPermit.release();
        } finally {
            first.release();
            executor.shutdownNow();
        }
    }

    @Test
    void queueBudgetExpiryReturnsRetryableTimeout() {
        long userId = randomUserId();
        SandboxAdmission admission = new RedisSandboxAdmission(
                redisson, properties(1, Duration.ofMillis(200), Duration.ofSeconds(30)));
        SandboxPermit first = admission.acquire(userId);
        try {
            assertThatThrownBy(() -> admission.acquire(userId))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).code())
                            .isEqualTo(ErrorCode.SANDBOX_QUEUE_TIMEOUT));
        } finally {
            first.release();
        }
    }

    @Test
    void differentUsersDoNotBlockEachOther() {
        long firstUser = randomUserId();
        long secondUser = randomUserId();
        SandboxAdmission admission = new RedisSandboxAdmission(
                redisson, properties(1, Duration.ofSeconds(2), Duration.ofSeconds(30)));
        SandboxPermit first = admission.acquire(firstUser);
        try {
            SandboxPermit second = admission.acquire(secondUser);
            assertThat(second).isNotNull();
            second.release();
        } finally {
            first.release();
        }
    }

    @Test
    void expiredLeaseReleasesCapacityAfterACrash() throws Exception {
        long userId = randomUserId();
        SandboxAdmission admission = new RedisSandboxAdmission(
                redisson, properties(1, Duration.ofSeconds(2), Duration.ofMillis(300)));
        SandboxPermit leaked = admission.acquire(userId);
        try {
            Thread.sleep(700);
            SandboxPermit recovered = admission.acquire(userId);
            assertThat(recovered).isNotNull();
            recovered.release();
        } finally {
            leaked.release();
        }
    }

    private static SandboxProperties properties(int perUser, Duration queue, Duration lease) {
        return new SandboxProperties(
                null, null, null, null, null, null, null, 8, perUser, queue, lease, null);
    }

    private static long randomUserId() {
        return ThreadLocalRandom.current().nextLong(1_000_000L, Long.MAX_VALUE);
    }
}
