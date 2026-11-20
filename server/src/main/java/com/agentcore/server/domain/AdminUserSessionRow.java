package com.agentcore.server.domain;

import java.time.LocalDateTime;

/** Per-user session count and last activity. */
public class AdminUserSessionRow {
    private Long userId;
    private Long sessions;
    private LocalDateTime lastActiveAt;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getSessions() { return sessions; }
    public void setSessions(Long sessions) { this.sessions = sessions; }
    public LocalDateTime getLastActiveAt() { return lastActiveAt; }
    public void setLastActiveAt(LocalDateTime lastActiveAt) { this.lastActiveAt = lastActiveAt; }
}
