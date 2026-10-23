package com.agentcore.server.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** Model enabled by the user for one provider. */
@TableName("provider_models")
public class ProviderModel {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long providerId;
    private String modelId;
    private String displayName;
    private Integer contextWindow;
    private Boolean supportsReasoning;
    private Boolean supportsVision;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getProviderId() { return providerId; }
    public void setProviderId(Long providerId) { this.providerId = providerId; }
    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public Integer getContextWindow() { return contextWindow; }
    public void setContextWindow(Integer contextWindow) { this.contextWindow = contextWindow; }
    public Boolean getSupportsReasoning() { return supportsReasoning; }
    public void setSupportsReasoning(Boolean supportsReasoning) { this.supportsReasoning = supportsReasoning; }
    public Boolean getSupportsVision() { return supportsVision; }
    public void setSupportsVision(Boolean supportsVision) { this.supportsVision = supportsVision; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
