package com.agentcore.server.domain;

/** One sandbox timing sample for percentile calculation. */
public class SandboxSampleRow {
    private Integer queueMs;
    private Integer durationMs;

    public Integer getQueueMs() { return queueMs; }
    public void setQueueMs(Integer queueMs) { this.queueMs = queueMs; }
    public Integer getDurationMs() { return durationMs; }
    public void setDurationMs(Integer durationMs) { this.durationMs = durationMs; }
}
