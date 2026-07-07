package com.mayoclone.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * Per-account outbound webhook configuration. Exactly one row per tenant (unique
 * {@code account_id}). When a matching order event fires and the account has a
 * {@code webhookUrl} plus the relevant toggle on, a compact JSON payload is POSTed
 * to that URL asynchronously (see {@code NotificationDispatcher}).
 */
@Entity
@Table(
        name = "notification_setting",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_notification_setting_account", columnNames = "account_id"),
        indexes = @Index(name = "idx_notification_setting_account", columnList = "account_id")
)
public class NotificationSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning tenant. Never set from a request body — derived from the caller. */
    @Column(name = "account_id", nullable = false)
    private Long accountId;

    /** Destination webhook (nullable => notifications disabled for the tenant). */
    @Column(name = "webhook_url", length = 1024)
    private String webhookUrl;

    @Column(name = "on_new_order", nullable = false)
    private boolean onNewOrder = true;

    @Column(name = "on_assigned", nullable = false)
    private boolean onAssigned = false;

    @Column(name = "on_cancelled", nullable = false)
    private boolean onCancelled = false;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public NotificationSetting() {
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

    public String getWebhookUrl() {
        return webhookUrl;
    }

    public void setWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    public boolean isOnNewOrder() {
        return onNewOrder;
    }

    public void setOnNewOrder(boolean onNewOrder) {
        this.onNewOrder = onNewOrder;
    }

    public boolean isOnAssigned() {
        return onAssigned;
    }

    public void setOnAssigned(boolean onAssigned) {
        this.onAssigned = onAssigned;
    }

    public boolean isOnCancelled() {
        return onCancelled;
    }

    public void setOnCancelled(boolean onCancelled) {
        this.onCancelled = onCancelled;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
