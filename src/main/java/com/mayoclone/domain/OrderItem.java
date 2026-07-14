package com.mayoclone.domain;

import jakarta.persistence.Embeddable;

import java.math.BigDecimal;

/**
 * A single line item on an order. Persisted as part of an
 * {@link jakarta.persistence.ElementCollection} on {@link IrctcOrder},
 * which yields clean per-item rows and clean JSON output.
 */
@Embeddable
public class OrderItem {

    private String name;
    /** Optional item description (e.g. the thali's contents); null when the email had none. */
    @jakarta.persistence.Column(length = 512)
    private String description;
    private int qty;
    private BigDecimal price;

    public OrderItem() {
    }

    public OrderItem(String name, int qty, BigDecimal price) {
        this(name, null, qty, price);
    }

    public OrderItem(String name, String description, int qty, BigDecimal price) {
        this.name = name;
        this.description = description;
        this.qty = qty;
        this.price = price;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public int getQty() {
        return qty;
    }

    public void setQty(int qty) {
        this.qty = qty;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }
}
