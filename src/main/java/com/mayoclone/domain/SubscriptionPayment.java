package com.mayoclone.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * An immutable record of a captured subscription payment. The
 * {@code (provider, providerPaymentId)} unique constraint keeps the verify/webhook
 * flows idempotent — a replayed payment id can never extend the period twice.
 * {@code amount} is in the currency's minor unit (paise for INR).
 */
@Entity
@Table(name = "subscription_payment",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_subscription_payment_provider_pid",
                columnNames = {"provider", "provider_payment_id"}))
public class SubscriptionPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(nullable = false, length = 32)
    private String provider;

    @Column(name = "provider_payment_id", nullable = false, length = 128)
    private String providerPaymentId;

    /** Minor units (paise for INR). */
    @Column(nullable = false)
    private long amount;

    @Column(nullable = false, length = 8)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public SubscriptionPayment() {
    }

    public SubscriptionPayment(Long accountId, String provider, String providerPaymentId,
                               long amount, String currency, Instant createdAt) {
        this.accountId = accountId;
        this.provider = provider;
        this.providerPaymentId = providerPaymentId;
        this.amount = amount;
        this.currency = currency;
        this.createdAt = createdAt;
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

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getProviderPaymentId() {
        return providerPaymentId;
    }

    public void setProviderPaymentId(String providerPaymentId) {
        this.providerPaymentId = providerPaymentId;
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
