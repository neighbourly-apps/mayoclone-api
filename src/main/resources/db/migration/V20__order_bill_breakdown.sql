-- V20__order_bill_breakdown.sql — persist the bill breakdown parsed from the
-- aggregator order email so an order carries its Sub Total / GST / Delivery Charge
-- / Discount alongside the grand total (the existing `amount` column stays the
-- grand total). Each is nullable: many emails omit one or more of these lines
-- (e.g. no discount, no delivery fee). Flyway owns the schema; Hibernate validates.
ALTER TABLE irctc_order ADD COLUMN subtotal_amount NUMERIC(19, 2);
ALTER TABLE irctc_order ADD COLUMN gst_amount      NUMERIC(19, 2);
ALTER TABLE irctc_order ADD COLUMN delivery_fee    NUMERIC(19, 2);
ALTER TABLE irctc_order ADD COLUMN discount_amount NUMERIC(19, 2);
