package com.mayoclone.domain;

import jakarta.persistence.Embeddable;

import java.math.BigDecimal;

/**
 * A single line item on an order. Persisted as part of an
 * {@link jakarta.persistence.ElementCollection} on {@link OrderRecord},
 * which yields clean per-item rows and clean JSON output.
 */
@Embeddable
public class OrderItem {

    private String name;
    private int qty;
    private BigDecimal price;

    public OrderItem() {
    }

    public OrderItem(String name, int qty, BigDecimal price) {
        this.name = name;
        this.qty = qty;
        this.price = price;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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
