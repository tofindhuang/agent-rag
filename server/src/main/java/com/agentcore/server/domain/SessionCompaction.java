package com.agentcore.server.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * One compaction summary projected from the gateway (ADR-0028). Session content:
 * never read by admin endpoints.
 */
@TableName("session_compactions")
public class SessionCompaction {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String sessionId;
    /** Message id the summary card is anchored to; null before any message exists. */
    private Long afterMessageId;
    private String reason;
    private String summary;
    private Boolean truncated;
    private Long tokensBefore;
    private Long tokensAfter;
    private LocalDateTime createdAt;

    public Long getId() { return id; }

    public void setId(Long id) { this.id = id; }

    public String getSessionId() { return sessionId; }

    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public Long getAfterMessageId() { return afterMessageId; }

    public void setAfterMessageId(Long afterMessageId) { this.afterMessageId = afterMessageId; }

    public String getReason() { return reason; }

    public void setReason(String reason) { this.reason = reason; }

    public String getSummary() { return summary; }

    public void setSummary(String summary) { this.summary = summary; }

    public Boolean getTruncated() { return truncated; }

    public void setTruncated(Boolean truncated) { this.truncated = truncated; }

    public Long getTokensBefore() { return tokensBefore; }

    public void setTokensBefore(Long tokensBefore) { this.tokensBefore = tokensBefore; }

    public Long getTokensAfter() { return tokensAfter; }

    public void setTokensAfter(Long tokensAfter) { this.tokensAfter = tokensAfter; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
