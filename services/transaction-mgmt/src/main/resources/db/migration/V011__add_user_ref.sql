-- V011 (CS quick-wins) — end-customer / wallet identifier for customer-support lookup.
--
-- Adds user_ref: the end-customer or wallet id carried on the wallet payment (captured from the
-- create request). Customer support can look a payment up by what the CUSTOMER holds, alongside the
-- partner's own reference (partner_txn_ref, already persisted since V003). Nullable — legacy rows and
-- creates that omit it leave it null. Indexed for the search filter.
-- Additive only — new migration, no in-place edit. H2 (PostgreSQL mode) needs one ALTER per column.

ALTER TABLE transactions ADD COLUMN IF NOT EXISTS user_ref VARCHAR(128);

CREATE INDEX IF NOT EXISTS idx_txn_user_ref        ON transactions(user_ref);
CREATE INDEX IF NOT EXISTS idx_txn_partner_txn_ref ON transactions(partner_txn_ref);
