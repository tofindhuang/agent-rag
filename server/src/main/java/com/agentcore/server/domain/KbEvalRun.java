package com.agentcore.server.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** Immutable summary of one evaluation run (ADR-0022). */
@TableName("kb_eval_runs")
public class KbEvalRun {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String kbId;
    private Boolean complete;
    private Integer labelCount;
    private Integer noAnswerCount;
    private Integer executedCount;
    private Integer rottenCount;
    private Integer skippedCount;
    private String embeddingModel;
    private Integer embeddingDim;
    private String rerankModel;
    private Integer durationMs;
    private Double recall5;
    private Double mrr5;
    private Double poolRecall10;
    private Double baselineRecall5;
    private Double baselineMrr5;
    private Double refusalAccuracy;
    private Double thresholdUsed;
    private String thresholdSource;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getKbId() {
        return kbId;
    }

    public void setKbId(String kbId) {
        this.kbId = kbId;
    }

    public Boolean getComplete() {
        return complete;
    }

    public void setComplete(Boolean complete) {
        this.complete = complete;
    }

    public Integer getLabelCount() {
        return labelCount;
    }

    public void setLabelCount(Integer labelCount) {
        this.labelCount = labelCount;
    }

    public Integer getNoAnswerCount() {
        return noAnswerCount;
    }

    public void setNoAnswerCount(Integer noAnswerCount) {
        this.noAnswerCount = noAnswerCount;
    }

    public Double getRefusalAccuracy() {
        return refusalAccuracy;
    }

    public void setRefusalAccuracy(Double refusalAccuracy) {
        this.refusalAccuracy = refusalAccuracy;
    }

    public Double getThresholdUsed() {
        return thresholdUsed;
    }

    public void setThresholdUsed(Double thresholdUsed) {
        this.thresholdUsed = thresholdUsed;
    }

    public String getThresholdSource() {
        return thresholdSource;
    }

    public void setThresholdSource(String thresholdSource) {
        this.thresholdSource = thresholdSource;
    }

    public Integer getExecutedCount() {
        return executedCount;
    }

    public void setExecutedCount(Integer executedCount) {
        this.executedCount = executedCount;
    }

    public Integer getRottenCount() {
        return rottenCount;
    }

    public void setRottenCount(Integer rottenCount) {
        this.rottenCount = rottenCount;
    }

    public Integer getSkippedCount() {
        return skippedCount;
    }

    public void setSkippedCount(Integer skippedCount) {
        this.skippedCount = skippedCount;
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

    public String getRerankModel() {
        return rerankModel;
    }

    public void setRerankModel(String rerankModel) {
        this.rerankModel = rerankModel;
    }

    public Integer getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Integer durationMs) {
        this.durationMs = durationMs;
    }

    public Double getRecall5() {
        return recall5;
    }

    public void setRecall5(Double recall5) {
        this.recall5 = recall5;
    }

    public Double getMrr5() {
        return mrr5;
    }

    public void setMrr5(Double mrr5) {
        this.mrr5 = mrr5;
    }

    public Double getPoolRecall10() {
        return poolRecall10;
    }

    public void setPoolRecall10(Double poolRecall10) {
        this.poolRecall10 = poolRecall10;
    }

    public Double getBaselineRecall5() {
        return baselineRecall5;
    }

    public void setBaselineRecall5(Double baselineRecall5) {
        this.baselineRecall5 = baselineRecall5;
    }

    public Double getBaselineMrr5() {
        return baselineMrr5;
    }

    public void setBaselineMrr5(Double baselineMrr5) {
        this.baselineMrr5 = baselineMrr5;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
