package com.agentcore.server.common.sandbox;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.config.SandboxProperties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Redis-backed admission (ADR-0017): per-user first, then global, so a user's
 * queued request never holds a global slot. Permits carry a lease so a crashed
 * JVM cannot leak capacity.
 */
@Component
public class RedisSandboxAdmission implements SandboxAdmission {

    private static final Logger log = LoggerFactory.getLogger(RedisSandboxAdmission.class);
    private static final String PER_USER_PREFIX = "sem:sandbox:";
    private static final String GLOBAL_KEY = "sem:sandbox:global";

    private final RedissonClient redisson;
    private final SandboxProperties properties;

    public RedisSandboxAdmission(RedissonClient redisson, SandboxProperties properties) {
        this.redisson = redisson;
        this.properties = properties;
    }

    @Override
    public SandboxPermit acquire(long userId) {
        long deadline = System.nanoTime() + properties.queueTimeout().toNanos();
        RPermitExpirableSemaphore userSemaphore =
                redisson.getPermitExpirableSemaphore(PER_USER_PREFIX + userId);
        String userPermit = acquirePermit(
                userSemaphore, properties.perUserConcurrency(), deadline, "per-user");
        try {
            RPermitExpirableSemaphore globalSemaphore =
                    redisson.getPermitExpirableSemaphore(GLOBAL_KEY);
            String globalPermit = acquirePermit(
                    globalSemaphore, properties.globalConcurrency(), deadline, "global");
            return new CompositePermit(userSemaphore, userPermit, globalSemaphore, globalPermit);
        } catch (RuntimeException ex) {
            releaseQuietly(userSemaphore, userPermit);
            throw ex;
        }
    }

    private String acquirePermit(
            RPermitExpirableSemaphore semaphore, int permits, long deadline, String scope) {
        try {
            semaphore.trySetPermits(permits);
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remainingMs <= 0) {
                throw queueTimeout();
            }
            String permit = semaphore.tryAcquire(
                    remainingMs, properties.permitLease().toMillis(), TimeUnit.MILLISECONDS);
            if (permit == null) {
                throw queueTimeout();
            }
            return permit;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE,
                    "sandbox admission interrupted; retry later", ex);
        } catch (RedisException ex) {
            log.warn("sandbox admission unavailable ({}): {}", scope, ex.getMessage());
            throw new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE,
                    "sandbox admission unavailable; retry later", ex);
        }
    }

    private static ApiException queueTimeout() {
        return new ApiException(ErrorCode.SANDBOX_QUEUE_TIMEOUT,
                "sandbox queue wait exceeded the limit; retry later");
    }

    private static void releaseQuietly(RPermitExpirableSemaphore semaphore, String permit) {
        try {
            semaphore.tryRelease(permit);
        } catch (RuntimeException ex) {
            log.warn("failed to release sandbox admission permit (lease will expire)", ex);
        }
    }

    private static final class CompositePermit implements SandboxPermit {

        private final RPermitExpirableSemaphore userSemaphore;
        private final String userPermit;
        private final RPermitExpirableSemaphore globalSemaphore;
        private final String globalPermit;
        private final AtomicBoolean released = new AtomicBoolean();

        private CompositePermit(
                RPermitExpirableSemaphore userSemaphore,
                String userPermit,
                RPermitExpirableSemaphore globalSemaphore,
                String globalPermit) {
            this.userSemaphore = userSemaphore;
            this.userPermit = userPermit;
            this.globalSemaphore = globalSemaphore;
            this.globalPermit = globalPermit;
        }

        @Override
        public void release() {
            if (!released.compareAndSet(false, true)) {
                return;
            }
            releaseQuietly(globalSemaphore, globalPermit);
            releaseQuietly(userSemaphore, userPermit);
        }
    }
}
