package com.mayoclone.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A persisted per-aggregator settlement snapshot for a delivery-date window,
 * tenant-scoped. Reconciliation figures (orders/gross/commission/net) are frozen
 * at creation time; the aggregator code/name/rate are denormalized so the snapshot
 * survives later edits to the aggregator catalog.
 */
@Entity
@Table(
        name = "settlement",
        indexes = {
                @Index(name = "idx_settlement_account", columnList = "account_id"),
                @Index(name = "idx_settlement_account_created", columnList = "account_id, created_at")
        }
)
public class Settlement {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SETTLED = "SETTLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning tenant. Never set from a request body — derived from the caller. */
    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "aggregator_id")
    private Long aggregatorId;

    private String aggregatorCode;
    private String aggregatorName;

    @Column(nullable = false)
    private LocalDate periodStart;

    @Column(nullable = false)
    private LocalDate periodEnd;

    @Column(nullable = false)
    private long orders;

    @Column(nullable = false)
    private BigDecimal grossAmount = BigDecimal.ZERO;

    @Column(precision = 5, scale = 4, nullable = false)
    private BigDecimal commissionRate = BigDecimal.ZERO;

    @Column(nullable = false)
    private BigDecimal commissionAmount = BigDecimal.ZERO;

    @Column(nullable = false)
    private BigDecimal netPayable = BigDecimal.ZERO;

    @Column(nullable = false, length = 16)
    private String status = STATUS_PENDING;

    @Column(length = 1000)
    private String note;

    private Instant createdAt;
    private Instant settledAt;

    public Settlement() {
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

    public Long getAggregatorId() {
        return aggregatorId;
    }

    public void setAggregatorId(Long aggregatorId) {
        this.aggregatorId = aggregatorId;
    }

    public String getAggregatorCode() {
        return aggregatorCode;
    }

    public void setAggregatorCode(String aggregatorCode) {
        this.aggregatorCode = aggregatorCode;
    }

    public String getAggregatorName() {
        return aggregatorName;
    }

    public void setAggregatorName(String aggregatorName) {
        this.aggregatorName = aggregatorName;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public void setPeriodStart(LocalDate periodStart) {
        this.periodStart = periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public void setPeriodEnd(LocalDate periodEnd) {
        this.periodEnd = periodEnd;
    }

    public long getOrders() {
        return orders;
    }

    public void setOrders(long orders) {
        this.orders = orders;
    }

    public BigDecimal getGrossAmount() {
        return grossAmount;
    }

    public void setGrossAmount(BigDecimal grossAmount) {
        this.grossAmount = grossAmount;
    }

    public BigDecimal getCommissionRate() {
        return commissionRate;
    }

    public void setCommissionRate(BigDecimal commissionRate) {
        this.commissionRate = commissionRate;
    }

    public BigDecimal getCommissionAmount() {
        return commissionAmount;
    }

    public void setCommissionAmount(BigDecimal commissionAmount) {
        this.commissionAmount = commissionAmount;
    }

    public BigDecimal getNetPayable() {
        return netPayable;
    }

    public void setNetPayable(BigDecimal netPayable) {
        this.netPayable = netPayable;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getSettledAt() {
        return settledAt;
    }

    public void setSettledAt(Instant settledAt) {
        this.settledAt = settledAt;
    }
}
