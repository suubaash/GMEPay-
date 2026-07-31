-- scheme-adapter-sendmn V002: persist the HUB's stable partner reference on each payment.
--
-- payment-executor's ADR-016 anti-double-charge probe is keyed by ITS reference, not the
-- scheme-side TX_TOKEN_NO. Before this column the reference→token translation lived only
-- in an in-process map on the hub, so an executor restart degraded probes for in-flight
-- payments. The reference is captured at verify-qr time (i.e. committed BEFORE any Confirm
-- can be sent), making GET /internal/scheme/sendmn/status/by-reference/{reference} a
-- durable probe target.
--
-- NOT unique: a hub retry after a lost verify-qr response can legitimately mint a second
-- attempt row for the same reference (each with its own TX_TOKEN_NO).

ALTER TABLE smn_payments ADD COLUMN hub_reference VARCHAR(64);

CREATE INDEX idx_smn_payments_hub_reference ON smn_payments (hub_reference);
