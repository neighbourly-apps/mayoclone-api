-- Optional per-item description (e.g. what a thali contains). Nullable; older rows stay null.
ALTER TABLE order_item ADD COLUMN description VARCHAR(512);
