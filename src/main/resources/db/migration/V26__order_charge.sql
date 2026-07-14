-- Extra named aggregator bill lines (gateway/platform fees, etc.) beyond subtotal/GST/
-- delivery/discount, kept verbatim so the breakdown reconciles to the order total.
CREATE TABLE order_charge (
    order_id BIGINT NOT NULL REFERENCES irctc_order(id) ON DELETE CASCADE,
    label    VARCHAR(120),
    amount   NUMERIC(12, 2)
);
CREATE INDEX idx_order_charge_order ON order_charge(order_id);
