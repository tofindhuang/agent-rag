package com.agentcore.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class SandboxPropertiesTest {

    private final ApplicationContextRunner contexts =
            new ApplicationContextRunner().withUserConfiguration(PoolBinding.class);

    @Test
    void sandboxDefaultsMatchTheM1Baseline() {
        SandboxProperties properties = SandboxProperties.defaults();

        assertThat(properties.globalConcurrency()).isEqualTo(12);
        assertThat(properties.perUserConcurrency()).isEqualTo(2);
        assertThat(properties.queueTimeout()).isEqualTo(Duration.ofSeconds(120));
        assertThat(properties.maxTimeout()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void warmPoolDefaultsMatchTheSpec() {
        SandboxPoolProperties properties = SandboxPoolProperties.defaults();

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.target()).isEqualTo(12);
        assertThat(properties.max()).isEqualTo(16);
        assertThat(properties.idleTtl()).isEqualTo(Duration.ofMinutes(10));
        assertThat(properties.replenishWorkers()).isEqualTo(2);
    }

    @Test
    void targetIsClampedToMax() {
        SandboxPoolProperties properties =
                new SandboxPoolProperties(true, 20, 8, null, null);

        assertThat(properties.target()).isEqualTo(8);
        assertThat(properties.max()).isEqualTo(8);
    }

    @Test
    void invalidTtlAndWorkerCountFallBackToDefaults() {
        SandboxPoolProperties properties = new SandboxPoolProperties(
                true, 2, 4, Duration.ZERO, 0);

        assertThat(properties.idleTtl()).isEqualTo(Duration.ofMinutes(10));
        assertThat(properties.replenishWorkers()).isEqualTo(2);
    }

    @Test
    void poolCanBeSwitchedOffFromConfiguration() {
        contexts.withPropertyValues("app.sandbox.pool.enabled=false")
                .run(context -> assertThat(
                        context.getBean(SandboxPoolProperties.class).enabled()).isFalse());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SandboxPoolProperties.class)
    static class PoolBinding {}
}
