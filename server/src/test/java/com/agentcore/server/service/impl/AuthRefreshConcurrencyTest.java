package com.agentcore.server.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.domain.AuthResult;
import com.agentcore.server.service.AuthService;
import com.agentcore.server.support.AbstractIntegrationTest;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Pins the row-lock semantics of refresh rotation (ADR-0007). */
class AuthRefreshConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private AuthService authService;

    @Test
    void concurrentRotationHasOneWinnerAndRevokesTheFamily() throws Exception {
        String email = "concurrent-" + UUID.randomUUID() + "@example.com";
        authService.register(email, "correct-horse");
        String presented = authService.login(email, "correct-horse").refreshToken();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<AuthResult> attempt = () -> {
            start.await();
            try {
                return authService.refresh(presented);
            } catch (ApiException ex) {
                return null;
            }
        };

        Future<AuthResult> first = pool.submit(attempt);
        Future<AuthResult> second = pool.submit(attempt);
        start.countDown();
        AuthResult firstResult = first.get(15, TimeUnit.SECONDS);
        AuthResult secondResult = second.get(15, TimeUnit.SECONDS);
        pool.shutdownNow();

        List<AuthResult> winners =
                java.util.stream.Stream.of(firstResult, secondResult).filter(Objects::nonNull).toList();
        assertThat(winners).hasSize(1);

        // The loser treated the replay as compromise and revoked the family,
        // so even the winner's fresh token is no longer usable.
        assertThatThrownBy(() -> authService.refresh(winners.get(0).refreshToken()))
                .isInstanceOf(ApiException.class);
    }
}
