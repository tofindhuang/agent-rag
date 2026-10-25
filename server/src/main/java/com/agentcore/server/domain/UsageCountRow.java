package com.agentcore.server.domain;

/** Generic "bucket → count" aggregate (day or session id). */
public class UsageCountRow {

    private String bucket;
    private Long amount;

    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    public Long getAmount() { return amount; }
    public void setAmount(Long amount) { this.amount = amount; }
}
