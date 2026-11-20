package com.agentcore.server.domain;

/** Execution count per terminal status. */
public class ExecutionStatusRow {
    private String status;
    private Long amount;

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getAmount() { return amount; }
    public void setAmount(Long amount) { this.amount = amount; }
}
