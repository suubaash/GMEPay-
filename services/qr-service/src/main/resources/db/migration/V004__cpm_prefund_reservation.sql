-- qr-service Phase 2: carry the prefunding reservation handle on the CPM session so the expiry
-- sweep / decline path can RELEASE it (POST /internal/v1/prefunding/{partnerId}/release).
-- prefund_reserved_usd already exists (V003); add the partner + reservation handle. All nullable:
-- LOCAL / no-prefunding / local-issuance sessions never reserve, so these stay NULL.
-- One column per ALTER so the script runs unchanged on both PostgreSQL 16 and H2 (PG mode).

ALTER TABLE cpm_prepare_session ADD COLUMN prefund_partner_id BIGINT;

ALTER TABLE cpm_prepare_session ADD COLUMN prefund_reservation_id VARCHAR(64);
