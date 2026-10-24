package com.agentcore.server.config;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import java.time.Duration;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Wiring for the run_code executor: Docker client, execution pool, heartbeats. */
@Configuration
@EnableScheduling
public class SandboxConfiguration {

    @Bean(destroyMethod = "close")
    public DockerClient sandboxDockerClient(SandboxProperties properties) {
        DefaultDockerClientConfig.Builder builder =
                DefaultDockerClientConfig.createDefaultConfigBuilder();
        if (properties.dockerHost() != null && !properties.dockerHost().isBlank()) {
            builder.withDockerHost(properties.dockerHost());
        }
        DockerClientConfig config = builder.build();
        DockerHttpClient httpClient = new ZerodepDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .maxConnections(properties.globalConcurrency() + 4)
                .connectionTimeout(Duration.ofSeconds(5))
                .responseTimeout(properties.maxTimeout().plusSeconds(30))
                .build();
        return DockerClientImpl.getInstance(config, httpClient);
    }

    @Bean(destroyMethod = "shutdown")
    public RedissonClient sandboxRedissonClient(RedisProperties redis) {
        Config config = new Config();
        // A Redis outage must not block chat/KB startup (FR-3).
        config.setLazyInitialization(true);
        SingleServerConfig server = config.useSingleServer()
                .setAddress("redis://" + redis.getHost() + ":" + redis.getPort())
                .setDatabase(redis.getDatabase())
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(8);
        if (redis.getPassword() != null && !redis.getPassword().isBlank()) {
            server.setPassword(redis.getPassword());
        }
        if (redis.getConnectTimeout() != null) {
            server.setConnectTimeout((int) redis.getConnectTimeout().toMillis());
        }
        if (redis.getTimeout() != null) {
            server.setTimeout((int) redis.getTimeout().toMillis());
        }
        return Redisson.create(config);
    }

    @Bean(name = "sandboxExecutor")
    public TaskExecutor sandboxExecutor(SandboxProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.globalConcurrency());
        executor.setMaxPoolSize(properties.globalConcurrency());
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("sandbox-exec-");
        executor.initialize();
        return executor;
    }

    @Bean(name = "sandboxHeartbeatScheduler")
    public TaskScheduler sandboxHeartbeatScheduler(
            @Value("${app.sandbox.heartbeat-pool-size:1}") int poolSize) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(1, poolSize));
        scheduler.setThreadNamePrefix("sandbox-heartbeat-");
        scheduler.setDaemon(true);
        scheduler.initialize();
        return scheduler;
    }
}
