package com.agentcore.server.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * Per-user embedding/rerank settings (FR-2). API keys are AES-GCM encrypted
 * with the owning user as AAD (ADR-0010); a missing row means deployment
 * defaults apply.
 */
@TableName("kb_user_settings")
public class KbUserSettings {

    @TableId(value = "user_id", type = IdType.INPUT)
    private Long userId;
    private String embeddingBaseUrl;
    private String embeddingApiKeyCipher;
    private String embeddingModel;
    private Integer embeddingDim;
    private Integer embeddingBatchSize;
    private String embeddingPath;
    private Boolean rerankEnabled;
    private String rerankBaseUrl;
    private String rerankApiKeyCipher;
    private String rerankModel;
    private String rerankPath;
    private LocalDateTime updatedAt;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getEmbeddingBaseUrl() {
        return embeddingBaseUrl;
    }

    public void setEmbeddingBaseUrl(String embeddingBaseUrl) {
        this.embeddingBaseUrl = embeddingBaseUrl;
    }

    public String getEmbeddingApiKeyCipher() {
        return embeddingApiKeyCipher;
    }

    public void setEmbeddingApiKeyCipher(String embeddingApiKeyCipher) {
        this.embeddingApiKeyCipher = embeddingApiKeyCipher;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public Integer getEmbeddingDim() {
        return embeddingDim;
    }

    public void setEmbeddingDim(Integer embeddingDim) {
        this.embeddingDim = embeddingDim;
    }

    public Integer getEmbeddingBatchSize() {
        return embeddingBatchSize;
    }

    public void setEmbeddingBatchSize(Integer embeddingBatchSize) {
        this.embeddingBatchSize = embeddingBatchSize;
    }

    public String getEmbeddingPath() {
        return embeddingPath;
    }

    public void setEmbeddingPath(String embeddingPath) {
        this.embeddingPath = embeddingPath;
    }

    public Boolean getRerankEnabled() {
        return rerankEnabled;
    }

    public void setRerankEnabled(Boolean rerankEnabled) {
        this.rerankEnabled = rerankEnabled;
    }

    public String getRerankBaseUrl() {
        return rerankBaseUrl;
    }

    public void setRerankBaseUrl(String rerankBaseUrl) {
        this.rerankBaseUrl = rerankBaseUrl;
    }

    public String getRerankApiKeyCipher() {
        return rerankApiKeyCipher;
    }

    public void setRerankApiKeyCipher(String rerankApiKeyCipher) {
        this.rerankApiKeyCipher = rerankApiKeyCipher;
    }

    public String getRerankModel() {
        return rerankModel;
    }

    public void setRerankModel(String rerankModel) {
        this.rerankModel = rerankModel;
    }

    public String getRerankPath() {
        return rerankPath;
    }

    public void setRerankPath(String rerankPath) {
        this.rerankPath = rerankPath;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
