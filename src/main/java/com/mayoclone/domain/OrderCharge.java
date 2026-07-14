package com.mayoclone.domain;

import jakarta.persistence.Embeddable;

import java.math.BigDecimal;

/**
 * One extra bill line the aggregator added beyond the food subtotal / GST / delivery /
 * discount — e.g. "Gateway Platform Fees", a convenience/packaging/service fee, etc. Kept
 * verbatim (label + amount, exactly as the email stated) so the bill breakdown shown to the
 * operator reconciles to the order total. Persisted via an {@code ElementCollection} on
 * {@link IrctcOrder}.
 */
@Embeddable
public class OrderCharge {

    private String label;
    private BigDecimal amount;

    public OrderCharge() {
    }

    public OrderCharge(String label, BigDecimal amount) {
        this.label = label;
        this.amount = amount;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }
}
