package com.agentcore.server.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * Knowledge-base document metadata (FR-2). The original lives in object
 * storage, passages/vectors are derived, and this row is the single source of
 * truth for task status.
 */
@TableName("kb_documents")
public class KbDocument {

    public static final String STATUS_QUEUED = "queued";
    public static final String STATUS_PROCESSING = "processing";
    public static final String STATUS_READY = "ready";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_DELETING = "deleting";
    public static final String STATUS_STALE = "stale";

    public static final String SOURCE_UPLOAD = "upload";
    public static final String SOURCE_URL = "url";

    @TableId(type = IdType.INPUT)
    private String id;
    private Long userId;
    private String kbId;
    private String title;
    private String sourceType;
    private String sourceUrl;
    private String originalKey;
    private String originalSha256;
    private String mime;
    private Long sizeBytes;
    private String status;
    private Integer attempts;
    private LocalDateTime nextAttemptAt;
    private LocalDateTime leaseUntil;
    private LocalDateTime enqueuedAt;
    private Integer chunkCount;
    private Integer ocrPages;
    private String errorCode;
    private String errorMessage;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getKbId() { return kbId; }

    public void setKbId(String kbId) { this.kbId = kbId; }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public String getOriginalKey() {
        return originalKey;
    }

    public void setOriginalKey(String originalKey) {
        this.originalKey = originalKey;
    }

    public String getOriginalSha256() {
        return originalSha256;
    }

    public void setOriginalSha256(String originalSha256) {
        this.originalSha256 = originalSha256;
    }

    public String getMime() {
        return mime;
    }

    public void setMime(String mime) {
        this.mime = mime;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getAttempts() {
        return attempts;
    }

    public void setAttempts(Integer attempts) {
        this.attempts = attempts;
    }

    public LocalDateTime getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(LocalDateTime nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public LocalDateTime getLeaseUntil() {
        return leaseUntil;
    }

    public void setLeaseUntil(LocalDateTime leaseUntil) {
        this.leaseUntil = leaseUntil;
    }

    public LocalDateTime getEnqueuedAt() {
        return enqueuedAt;
    }

    public void setEnqueuedAt(LocalDateTime enqueuedAt) {
        this.enqueuedAt = enqueuedAt;
    }

    public Integer getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(Integer chunkCount) {
        this.chunkCount = chunkCount;
    }

    /** Pages recognized by OCR in the last successful ingestion; 0 = text layer only. */
    public Integer getOcrPages() {
        return ocrPages;
    }

    public void setOcrPages(Integer ocrPages) {
        this.ocrPages = ocrPages;
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

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
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
