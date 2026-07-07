-- Collapse the order lifecycle to exactly 5 states:
--   NEW, ACCEPTED, OUT_FOR_DELIVERY, BILL_PENDING, CANCELLED
--
-- Remaps every legacy status that no longer exists so that no row can hold a
-- value outside the new set (a stray value would break JPA enum reads).
--   PREPARING / READY -> ACCEPTED         (in-progress pre-dispatch)
--   DELIVERED         -> BILL_PENDING     (delivered, bill pending)
--   UNDELIVERED       -> CANCELLED        (failed delivery)
--
-- Idempotent: only touches rows still holding a legacy value.

UPDATE irctc_order SET status = 'ACCEPTED'     WHERE status IN ('PREPARING', 'READY');
UPDATE irctc_order SET status = 'BILL_PENDING' WHERE status = 'DELIVERED';
UPDATE irctc_order SET status = 'CANCELLED'    WHERE status = 'UNDELIVERED';

UPDATE order_status_event SET status = 'ACCEPTED'     WHERE status IN ('PREPARING', 'READY');
UPDATE order_status_event SET status = 'BILL_PENDING' WHERE status = 'DELIVERED';
UPDATE order_status_event SET status = 'CANCELLED'    WHERE status = 'UNDELIVERED';
