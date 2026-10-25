package com.agentcore.server.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** One prompt execution (see CONTEXT.md: 执行). */
@TableName("runs")
public class Run {

    @TableId(type = IdType.INPUT)
    private String id;
    private String sessionId;
    private String turnId;
    private String gatewayRunId;
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

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getTurnId() {
        return turnId;
    }

    public void setTurnId(String turnId) {
        this.turnId = turnId;
    }

    public String getGatewayRunId() {
        return gatewayRunId;
    }

    public void setGatewayRunId(String gatewayRunId) {
        this.gatewayRunId = gatewayRunId;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    /** Model identifier snapshot taken when the run starts (ADR-0023). */
    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }

    public Long getCacheReadTokens() {
        return cacheReadTokens;
    }

    public void setCacheReadTokens(Long cacheReadTokens) {
        this.cacheReadTokens = cacheReadTokens;
    }

    public Long getCacheWriteTokens() {
        return cacheWriteTokens;
    }

    public void setCacheWriteTokens(Long cacheWriteTokens) {
        this.cacheWriteTokens = cacheWriteTokens;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Long getTokensIn() {
        return tokensIn;
    }

    public void setTokensIn(Long tokensIn) {
        this.tokensIn = tokensIn;
    }

    public Long getTokensOut() {
        return tokensOut;
    }

    public void setTokensOut(Long tokensOut) {
        this.tokensOut = tokensOut;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getSettledAt() {
        return settledAt;
    }

    public void setSettledAt(LocalDateTime settledAt) {
        this.settledAt = settledAt;
    }
}
