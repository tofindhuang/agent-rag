package com.agentcore.server.domain;

/** Daily kb_search call/hit/passage aggregates. */
public class RetrievalDailyRow {
    private String day;
    private Long calls;
    private Long hitCalls;
    private Long passages;
    private Long failedCalls;

    public String getDay() { return day; }
    public void setDay(String day) { this.day = day; }
    public Long getCalls() { return calls; }
    public void setCalls(Long calls) { this.calls = calls; }
    public Long getHitCalls() { return hitCalls; }
    public void setHitCalls(Long hitCalls) { this.hitCalls = hitCalls; }
    public Long getPassages() { return passages; }
    public void setPassages(Long passages) { this.passages = passages; }
    public Long getFailedCalls() { return failedCalls; }
    public void setFailedCalls(Long failedCalls) { this.failedCalls = failedCalls; }
}
