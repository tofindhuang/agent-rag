package com.agentcore.server.domain;

/** Window token aggregate per user and model. */
public class AdminUserTokenRow {
    private Long userId;
    private String modelId;
    private Long runs;
    private Long tokensIn;
    private Long tokensOut;
    private Long cacheReadTokens;
    private Long cacheWriteTokens;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
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
