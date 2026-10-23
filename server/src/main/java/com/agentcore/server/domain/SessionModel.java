package com.agentcore.server.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** Model snapshot for one session; survives provider deletion via label. */
@TableName("session_models")
public class SessionModel {

    @TableId(type = IdType.INPUT)
    private String sessionId;
    private Long providerModelId;
    private String modelLabel;
    private String modelSource;
    private LocalDateTime updatedAt;

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public Long getProviderModelId() { return providerModelId; }
    public void setProviderModelId(Long providerModelId) { this.providerModelId = providerModelId; }
    public String getModelLabel() { return modelLabel; }
    public void setModelLabel(String modelLabel) { this.modelLabel = modelLabel; }
    public String getModelSource() { return modelSource; }
    public void setModelSource(String modelSource) { this.modelSource = modelSource; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
