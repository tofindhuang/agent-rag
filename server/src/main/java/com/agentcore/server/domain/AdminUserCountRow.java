package com.agentcore.server.domain;

/** Per-user count aggregate (executions or searches). */
public class AdminUserCountRow {
    private Long userId;
    private Long amount;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getAmount() { return amount; }
    public void setAmount(Long amount) { this.amount = amount; }
}
