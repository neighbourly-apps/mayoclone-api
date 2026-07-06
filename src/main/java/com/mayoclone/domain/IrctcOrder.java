package com.mayoclone.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
                columnNames = {"aggregator_id", "externalOrderId"}
        )
)
public class IrctcOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "aggregator_id")
    private Aggregator aggregator;

    /** Owning vendor; nullable — demo orders may have none. */
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
    private String status;

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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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
}
