package com.agentcore.server.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** One security-relevant action (ADR-0033); metadata only, never content or secrets. */
@TableName("audit_events")
public class AuditEvent {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long actorUserId;
    private String actorType;
    private String action;
    private String targetType;
    private String targetId;
    private String outcome;
    private String ip;
    private String userAgent;
    private String detail;
    private LocalDateTime createdAt;

    public Long getId() { return id; }

    public void setId(Long id) { this.id = id; }

    public Long getActorUserId() { return actorUserId; }

    public void setActorUserId(Long actorUserId) { this.actorUserId = actorUserId; }

    public String getActorType() { return actorType; }

    public void setActorType(String actorType) { this.actorType = actorType; }

    public String getAction() { return action; }

    public void setAction(String action) { this.action = action; }

    public String getTargetType() { return targetType; }

    public void setTargetType(String targetType) { this.targetType = targetType; }

    public String getTargetId() { return targetId; }

    public void setTargetId(String targetId) { this.targetId = targetId; }

    public String getOutcome() { return outcome; }

    public void setOutcome(String outcome) { this.outcome = outcome; }

    public String getIp() { return ip; }

    public void setIp(String ip) { this.ip = ip; }

    public String getUserAgent() { return userAgent; }

    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }

    public String getDetail() { return detail; }

    public void setDetail(String detail) { this.detail = detail; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
