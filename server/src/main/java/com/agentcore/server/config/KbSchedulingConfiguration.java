package com.agentcore.server.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables the KB outbox reconciler only while the KB feature is on. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "app.kb", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KbSchedulingConfiguration {}
