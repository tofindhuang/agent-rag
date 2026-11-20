package com.agentcore.server.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** Ledger row for one archived pi session (ADR-0025). */
@TableName("session_archives")
public class SessionArchive {

    @TableId(type = IdType.INPUT)
    private String sessionId;
    private Long userId;
    private String objectPrefix;
    private String jsonlSha256;
    private String metaSha256;
    private Long sizeBytes;
    private String status;
    private Integer attempts;
    private String lastError;
    private LocalDateTime archivedAt;
    private LocalDateTime updatedAt;

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getObjectPrefix() { return objectPrefix; }
    public void setObjectPrefix(String objectPrefix) { this.objectPrefix = objectPrefix; }
    public String getJsonlSha256() { return jsonlSha256; }
    public void setJsonlSha256(String jsonlSha256) { this.jsonlSha256 = jsonlSha256; }
    public String getMetaSha256() { return metaSha256; }
    public void setMetaSha256(String metaSha256) { this.metaSha256 = metaSha256; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getAttempts() { return attempts; }
    public void setAttempts(Integer attempts) { this.attempts = attempts; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public LocalDateTime getArchivedAt() { return archivedAt; }
    public void setArchivedAt(LocalDateTime archivedAt) { this.archivedAt = archivedAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
