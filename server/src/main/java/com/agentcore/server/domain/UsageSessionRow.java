package com.agentcore.server.domain;

import java.time.LocalDateTime;

/** Session + model token aggregate for the Top-session list. */
public class UsageSessionRow {

    private String sessionId;
    private String title;
    private LocalDateTime createdAt;
    private LocalDateTime lastActiveAt;
    private String modelId;
    private Long runs;
    private Long tokensIn;
    private Long tokensOut;
    private Long cacheReadTokens;
    private Long cacheWriteTokens;

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getLastActiveAt() { return lastActiveAt; }
    public void setLastActiveAt(LocalDateTime lastActiveAt) { this.lastActiveAt = lastActiveAt; }
    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public Long getRuns() { return runs; }
    public void setRuns(Long runs) { this.runs = runs; }
    public Long getTokensIn() { return tokensIn; }
    public void setTokensIn(Long tokensIn) { this.tokensIn = tokensIn; }
    public Long getTokensOut() { return tokensOut; }
    public void setTokensOut(Long tokensOut) { this.tokensOut = tokensOut; }
    public Long getCacheReadTokens() { return cacheReadTokens; }
    public void setCacheReadTokens(Long cacheReadTokens) { this.cacheReadTokens = cacheReadTokens; }
    public Long getCacheWriteTokens() { return cacheWriteTokens; }
    public void setCacheWriteTokens(Long cacheWriteTokens) { this.cacheWriteTokens = cacheWriteTokens; }
}
