package com.agentcore.server.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * Per-label outcome of a run. {@code rankVector} is the real 1-based position
 * in the candidate pool (1–10); {@code rankFinal} is the position in the final
 * top-5. NULL means the expected passage was not present.
 */
@TableName("kb_eval_items")
public class KbEvalItem {

    public static final String STATUS_HIT = "hit";
    public static final String STATUS_MISS = "miss";
    public static final String STATUS_ROTTEN = "rotten";
    public static final String STATUS_SKIPPED = "skipped";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long runId;
    private Long labelId;
    private String status;
    private Integer rankFinal;
    private Integer rankVector;
    private Double scoreFinal;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getRunId() {
        return runId;
    }

    public void setRunId(Long runId) {
        this.runId = runId;
    }

    public Long getLabelId() {
        return labelId;
    }

    public void setLabelId(Long labelId) {
        this.labelId = labelId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getRankFinal() {
        return rankFinal;
    }

    public void setRankFinal(Integer rankFinal) {
        this.rankFinal = rankFinal;
    }

    public Integer getRankVector() {
        return rankVector;
    }

    public void setRankVector(Integer rankVector) {
        this.rankVector = rankVector;
    }

    public Double getScoreFinal() {
        return scoreFinal;
    }

    public void setScoreFinal(Double scoreFinal) {
        this.scoreFinal = scoreFinal;
    }
}
