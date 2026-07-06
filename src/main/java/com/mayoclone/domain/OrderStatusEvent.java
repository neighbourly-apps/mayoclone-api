package com.mayoclone.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One entry in an order's status timeline: every lifecycle transition (including
 * the initial NEW on creation) appends an immutable row here. Exposed to clients
 * as {@code statusHistory:[{status, at}]}.
 */
@Entity
@Table(
        name = "order_status_event",
        indexes = @Index(name = "idx_status_event_order", columnList = "order_id")
)
public class OrderStatusEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OrderStatus status;

    /** When the transition happened. Column is {@code event_at} ("at" is dialect-sensitive). */
    @Column(name = "event_at", nullable = false)
    private Instant at;

    @Column(length = 500)
    private String note;

    public OrderStatusEvent() {
    }

    public OrderStatusEvent(Long orderId, OrderStatus status, Instant at, String note) {
        this.orderId = orderId;
        this.status = status;
        this.at = at;
        this.note = note;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public Instant getAt() {
        return at;
    }

    public void setAt(Instant at) {
        this.at = at;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
