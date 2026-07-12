package com.mayoclone.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Binds a server-created Razorpay order to the plan / amount / account it was created
 * for. Written at checkout and read back at /verify so the activated plan is derived
 * from the ORDER (server-authoritative), never from the client-sent verify body — the
 * fix for the plan-upgrade payment fraud. Keyed by the Razorpay order id.
 *
 * <p>Rows are kept after a successful verify so a replayed /verify still resolves
 * (idempotency is enforced separately by the {@code subscription_payment} unique key).
 */
@Entity
@Table(name = "pending_checkout")
public class PendingCheckout {

    /** Razorpay order id (server-issued). Assigned, never generated. */
    @Id
    @Column(name = "order_id", length = 128)
    private String orderId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "plan_code", nullable = false, length = 64)
    private String planCode;

    /** Minor units (paise for INR). */
    @Column(nullable = false)
    private long amount;

    @Column(nullable = false, length = 8)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public PendingCheckout() {
    }

    public PendingCheckout(String orderId, Long accountId, String planCode,
                           long amount, String currency, Instant createdAt) {
        this.orderId = orderId;
        this.accountId = accountId;
        this.planCode = planCode;
        this.amount = amount;
        this.currency = currency;
        this.createdAt = createdAt;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public String getPlanCode() {
        return planCode;
    }

    public void setPlanCode(String planCode) {
        this.planCode = planCode;
    }

    public long getAmount() {
        return amount;
    }

    public void setAmount(long amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
