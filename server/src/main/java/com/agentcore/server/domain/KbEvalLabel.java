package com.agentcore.server.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * One evaluation label: the question whose answer should be the passage at
 * {@code (documentId, seq)}, with the fingerprint captured when the label was
 * created (ADR-0022).
 */
@TableName("kb_eval_labels")
public class KbEvalLabel {

    public static final String EXPECTATION_ANSWER = "answer";
    public static final String EXPECTATION_NO_ANSWER = "no_answer";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String kbId;
    private String query;
    /** {@code answer} labels point at a passage; {@code no_answer} labels do not. */
    private String expectation;
    private String documentId;
    private Integer seq;
    private String passageSha256;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

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

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public String getExpectation() {
        return expectation;
    }

    public void setExpectation(String expectation) {
        this.expectation = expectation;
    }

    public boolean expectsNoAnswer() {
        return EXPECTATION_NO_ANSWER.equals(expectation);
    }

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public Integer getSeq() {
        return seq;
    }

    public void setSeq(Integer seq) {
        this.seq = seq;
    }

    public String getPassageSha256() {
        return passageSha256;
    }

    public void setPassageSha256(String passageSha256) {
        this.passageSha256 = passageSha256;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
