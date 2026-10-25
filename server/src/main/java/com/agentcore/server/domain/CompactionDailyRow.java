package com.agentcore.server.domain;

/** Daily compaction count and token deltas. */
public class CompactionDailyRow {
    private String day;
    private Long amount;
    private Long tokensBefore;
    private Long tokensAfter;

    public String getDay() { return day; }
    public void setDay(String day) { this.day = day; }
    public Long getAmount() { return amount; }
    public void setAmount(Long amount) { this.amount = amount; }
    public Long getTokensBefore() { return tokensBefore; }
    public void setTokensBefore(Long tokensBefore) { this.tokensBefore = tokensBefore; }
    public Long getTokensAfter() { return tokensAfter; }
    public void setTokensAfter(Long tokensAfter) { this.tokensAfter = tokensAfter; }
}
