package com.mayoclone.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * A unified IRCTC e-catering order parsed out of an aggregator confirmation
 * email. Carries the train/station/PNR/passenger/delivery-slot context needed
 * to fulfil (and invoice) the order.
 *
 * <p>Deduplication happens on two levels:
 * <ul>
 *   <li>a unique constraint on (aggregator_id, externalOrderId), and</li>
 *   <li>an application-level check on {@code sourceMessageId} before insert.</li>
 * </ul>
 */
@Entity
@Table(
        name = "irctc_order",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_order_aggregator_external",
                columnNames = {"aggregator_id", "external_order_id"}
        ),
        indexes = {
                @Index(name = "idx_order_account_placed", columnList = "account_id, placed_at"),
                @Index(name = "idx_order_account_station", columnList = "account_id, delivery_station_code"),
                @Index(name = "idx_order_account_date", columnList = "account_id, delivery_date"),
                @Index(name = "idx_order_account_status", columnList = "account_id, status"),
                @Index(name = "idx_order_account_rider", columnList = "account_id, rider_id"),
                @Index(name = "idx_order_account_review", columnList = "account_id, needs_review")
        }
)
public class IrctcOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning aggregator; nullable — DIRECT (walk-in/phone) orders have none. */
    @ManyToOne(fetch = FetchType.EAGER, optional = true)
    @JoinColumn(name = "aggregator_id", nullable = true)
    private Aggregator aggregator;

    /** Owning tenant/business. Never set from a request body — derived from the caller/vendor. */
    @Column(name = "account_id", nullable = false)
    private Long accountId;

    /** Owning vendor mailbox; nullable — demo orders have none. */
    @Column(nullable = true)
    private Long vendorId;

    private String externalOrderId;
    private String pnr;
    private String trainNumber;
    private String trainName;
    private String coach;
    private String berth;

    @Column(nullable = true)
    private String boardingStationCode;

    private String deliveryStationCode;
    private String deliveryStationName;
    private String passengerName;
    private String passengerPhone;

    private LocalDate deliveryDate;

    /** Scheduled delivery time text, e.g. "13:00-13:30". */
    private String deliverySlot;

    private BigDecimal amount;
    private String currency = "INR";

    /** Cash to collect on delivery (COD); null when the email gave no signal. */
    @Column(name = "amount_to_collect")
    private BigDecimal amountToCollect;

    /** Bill breakdown parsed from the email; each nullable when the line is absent. {@link #amount} stays the grand total. */
    @Column(name = "subtotal_amount")
    private BigDecimal subtotalAmount;

    @Column(name = "gst_amount")
    private BigDecimal gstAmount;

    @Column(name = "delivery_fee")
    private BigDecimal deliveryFee;

    @Column(name = "discount_amount")
    private BigDecimal discountAmount;

    /** How the order entered the system (default ONLINE for ingested orders). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OrderType orderType = OrderType.ONLINE;

    /** Prepaid vs cash-on-delivery. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PaymentMode paymentMode = PaymentMode.PREPAID;

    /** Fulfilment lifecycle state. Starts at NEW. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OrderStatus status = OrderStatus.NEW;

    /** Assigned rider (nullable FK to {@link Rider}); null when unassigned. */
    @Column(name = "rider_id")
    private Long riderId;

    private Instant assignedAt;
    private Instant deliveredAt;

    /** Free-text reason captured when the order transitions to CANCELLED; null otherwise. */
    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    @Column(length = 1000)
    private String subject;

    /** Stable id derived from the source email; used to dedup on re-sync. */
    @Column(length = 512)
    private String sourceMessageId;

    private Instant placedAt;
    private Instant createdAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "order_item",
            joinColumns = @JoinColumn(name = "order_id")
    )
    private List<OrderItem> items = new ArrayList<>();

    /** Extra named aggregator charges (gateway/platform fees, …) beyond the standard bill lines. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "order_charge",
            joinColumns = @JoinColumn(name = "order_id")
    )
    private List<OrderCharge> charges = new ArrayList<>();

    /**
     * The FULL, untruncated raw email body this order was parsed from. The ultimate
     * safety net: even if a field parsed wrong, the source is always recoverable and
     * re-parseable. Not exposed in the list DTO (too big) — see GET /api/orders/{id}/raw.
     */
    @Column(name = "raw_email", columnDefinition = "text")
    private String rawEmail;

    /**
     * True when the parse looked low-confidence (missing items/amount/train/station, or
     * a generated order id). The order is ALWAYS still saved — this flags it for an
     * operator to review instead of hiding it behind a bad parse.
     */
    @Column(name = "needs_review", nullable = false)
    private boolean needsReview = false;

    /** Concise, human-readable reason(s) the order was flagged; null when clean. */
    @Column(name = "review_reason", length = 500)
    private String reviewReason;

    public IrctcOrder() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Aggregator getAggregator() {
        return aggregator;
    }

    public void setAggregator(Aggregator aggregator) {
        this.aggregator = aggregator;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public Long getVendorId() {
        return vendorId;
    }

    public void setVendorId(Long vendorId) {
        this.vendorId = vendorId;
    }

    public String getExternalOrderId() {
        return externalOrderId;
    }

    public void setExternalOrderId(String externalOrderId) {
        this.externalOrderId = externalOrderId;
    }

    public String getPnr() {
        return pnr;
    }

    public void setPnr(String pnr) {
        this.pnr = pnr;
    }

    public String getTrainNumber() {
        return trainNumber;
    }

    public void setTrainNumber(String trainNumber) {
        this.trainNumber = trainNumber;
    }

    public String getTrainName() {
        return trainName;
    }

    public void setTrainName(String trainName) {
        this.trainName = trainName;
    }

    public String getCoach() {
        return coach;
    }

    public void setCoach(String coach) {
        this.coach = coach;
    }

    public String getBerth() {
        return berth;
    }

    public void setBerth(String berth) {
        this.berth = berth;
    }

    public String getBoardingStationCode() {
        return boardingStationCode;
    }

    public void setBoardingStationCode(String boardingStationCode) {
        this.boardingStationCode = boardingStationCode;
    }

    public String getDeliveryStationCode() {
        return deliveryStationCode;
    }

    public void setDeliveryStationCode(String deliveryStationCode) {
        this.deliveryStationCode = deliveryStationCode;
    }

    public String getDeliveryStationName() {
        return deliveryStationName;
    }

    public void setDeliveryStationName(String deliveryStationName) {
        this.deliveryStationName = deliveryStationName;
    }

    public String getPassengerName() {
        return passengerName;
    }

    public void setPassengerName(String passengerName) {
        this.passengerName = passengerName;
    }

    public String getPassengerPhone() {
        return passengerPhone;
    }

    public void setPassengerPhone(String passengerPhone) {
        this.passengerPhone = passengerPhone;
    }

    public LocalDate getDeliveryDate() {
        return deliveryDate;
    }

    public void setDeliveryDate(LocalDate deliveryDate) {
        this.deliveryDate = deliveryDate;
    }

    public String getDeliverySlot() {
        return deliverySlot;
    }

    public void setDeliverySlot(String deliverySlot) {
        this.deliverySlot = deliverySlot;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public BigDecimal getAmountToCollect() {
        return amountToCollect;
    }

    public void setAmountToCollect(BigDecimal amountToCollect) {
        this.amountToCollect = amountToCollect;
    }

    public BigDecimal getSubtotalAmount() {
        return subtotalAmount;
    }

    public void setSubtotalAmount(BigDecimal subtotalAmount) {
        this.subtotalAmount = subtotalAmount;
    }

    public BigDecimal getGstAmount() {
        return gstAmount;
    }

    public void setGstAmount(BigDecimal gstAmount) {
        this.gstAmount = gstAmount;
    }

    public BigDecimal getDeliveryFee() {
        return deliveryFee;
    }

    public void setDeliveryFee(BigDecimal deliveryFee) {
        this.deliveryFee = deliveryFee;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public void setDiscountAmount(BigDecimal discountAmount) {
        this.discountAmount = discountAmount;
    }

    public OrderType getOrderType() {
        return orderType;
    }

    public void setOrderType(OrderType orderType) {
        this.orderType = orderType;
    }

    public PaymentMode getPaymentMode() {
        return paymentMode;
    }

    public void setPaymentMode(PaymentMode paymentMode) {
        this.paymentMode = paymentMode;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public Long getRiderId() {
        return riderId;
    }

    public void setRiderId(Long riderId) {
        this.riderId = riderId;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    public void setAssignedAt(Instant assignedAt) {
        this.assignedAt = assignedAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public void setDeliveredAt(Instant deliveredAt) {
        this.deliveredAt = deliveredAt;
    }

    public String getCancellationReason() {
        return cancellationReason;
    }

    public void setCancellationReason(String cancellationReason) {
        this.cancellationReason = cancellationReason;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getSourceMessageId() {
        return sourceMessageId;
    }

    public void setSourceMessageId(String sourceMessageId) {
        this.sourceMessageId = sourceMessageId;
    }

    public Instant getPlacedAt() {
        return placedAt;
    }

    public void setPlacedAt(Instant placedAt) {
        this.placedAt = placedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public List<OrderItem> getItems() {
        return items;
    }

    public void setItems(List<OrderItem> items) {
        this.items = items;
    }

    public List<OrderCharge> getCharges() {
        return charges;
    }

    public void setCharges(List<OrderCharge> charges) {
        this.charges = charges;
    }

    public String getRawEmail() {
        return rawEmail;
    }

    public void setRawEmail(String rawEmail) {
        this.rawEmail = rawEmail;
    }

    public boolean isNeedsReview() {
        return needsReview;
    }

    public void setNeedsReview(boolean needsReview) {
        this.needsReview = needsReview;
    }

    public String getReviewReason() {
        return reviewReason;
    }

    public void setReviewReason(String reviewReason) {
        this.reviewReason = reviewReason;
    }
}
