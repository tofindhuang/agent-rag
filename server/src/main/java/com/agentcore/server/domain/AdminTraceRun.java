package com.agentcore.server.domain;

import java.time.LocalDateTime;

/** Joined run/user row used to assemble an admin trace timeline. */
public class AdminTraceRun {
    private String runId;
    private String sessionId;
    private String turnId;
    private String requestId;
    private String modelId;
    private String status;
    private String errorCode;
    private String errorMessage;
    private Long tokensIn;
    private Long tokensOut;
    private Long cacheReadTokens;
    private Long cacheWriteTokens;
    private LocalDateTime startedAt;
    private LocalDateTime settledAt;
    private Long userId;
    private String email;

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getTurnId() { return turnId; }
    public void setTurnId(String turnId) { this.turnId = turnId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Long getTokensIn() { return tokensIn; }
    public void setTokensIn(Long tokensIn) { this.tokensIn = tokensIn; }
    public Long getTokensOut() { return tokensOut; }
    public void setTokensOut(Long tokensOut) { this.tokensOut = tokensOut; }
    public Long getCacheReadTokens() { return cacheReadTokens; }
    public void setCacheReadTokens(Long cacheReadTokens) { this.cacheReadTokens = cacheReadTokens; }
    public Long getCacheWriteTokens() { return cacheWriteTokens; }
    public void setCacheWriteTokens(Long cacheWriteTokens) { this.cacheWriteTokens = cacheWriteTokens; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getSettledAt() { return settledAt; }
    public void setSettledAt(LocalDateTime settledAt) { this.settledAt = settledAt; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
}
