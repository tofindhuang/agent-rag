package com.agentcore.server.domain;

/** Daily token aggregate for one model; populated by MyBatis. */
public class UsageDailyRow {

    private String day;
    private String modelId;
    private Long runs;
    private Long tokensIn;
    private Long tokensOut;
    private Long cacheReadTokens;
    private Long cacheWriteTokens;

    public String getDay() { return day; }
    public void setDay(String day) { this.day = day; }
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
