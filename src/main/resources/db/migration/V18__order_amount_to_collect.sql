-- V18__order_amount_to_collect.sql — persist the COD "cash to collect" figure
-- parsed from the aggregator order email (e.g. RailRestro "Amount to collect",
-- RELFOOD "Payment to collect", Zoop "Balance to pay"). Nullable: many prepaid
-- emails give no such figure. The existing payment_mode enum column (added in V5)
-- is now driven by the parser (COD / PREPAID) instead of a body-text heuristic.
-- Flyway owns the schema; Hibernate runs in validate mode against it.
ALTER TABLE irctc_order ADD COLUMN amount_to_collect NUMERIC(19, 2);
