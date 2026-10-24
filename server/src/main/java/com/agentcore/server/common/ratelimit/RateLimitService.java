package com.agentcore.server.common.ratelimit;

/** Fixed-window rate limiting (FR-6); disabled mode always allows. */
public interface RateLimitService {

    RateLimitDecision checkUserRpm(long userId);

    RateLimitDecision checkAuthIpRpm(String clientIp);

    RateLimitDecision checkUserTpm(long userId);

    void addUserTokens(long userId, long tokens);

    boolean enabled();
}
