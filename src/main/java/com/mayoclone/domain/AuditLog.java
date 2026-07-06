package com.mayoclone.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Append-only audit record. Immutability is enforced in Postgres by a trigger
 * that raises on UPDATE/DELETE (see the audit migration); the app only ever
 * inserts. {@link #detailsJson} MUST NOT contain secrets/tokens/passwords.
 */
@Entity
@Table(name = "audit_log", indexes = {
        @Index(name = "idx_audit_log_account", columnList = "account_id, created_at"),
        @Index(name = "idx_audit_log_action", columnList = "action, created_at")
})
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning tenant; nullable for pre-auth events (e.g. failed login, register). */
    @Column(name = "account_id", nullable = true)
    private Long accountId;

    @Column(name = "actor_email", nullable = true)
    private String actorEmail;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(name = "target_type", nullable = true, length = 64)
    private String targetType;

    @Column(name = "target_id", nullable = true, length = 128)
    private String targetId;

    @Column(nullable = true, length = 64)
    private String ip;

    @Column(name = "user_agent", nullable = true, length = 512)
    private String userAgent;

    @Column(name = "details_json", nullable = true, length = 2000)
    private String detailsJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public AuditLog() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public String getActorEmail() {
        return actorEmail;
    }

    public void setActorEmail(String actorEmail) {
        this.actorEmail = actorEmail;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public String getTargetId() {
        return targetId;
    }

    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public String getDetailsJson() {
        return detailsJson;
    }

    public void setDetailsJson(String detailsJson) {
        this.detailsJson = detailsJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
