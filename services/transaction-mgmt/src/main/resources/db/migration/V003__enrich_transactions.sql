-- transaction-mgmt Phase-4 enrichment columns.
-- H2 (PostgreSQL mode) does not support multi-column ALTER TABLE ADD COLUMN;
-- each column must be in its own statement.

ALTER TABLE transactions ADD COLUMN IF NOT EXISTS partner_id            BIGINT;
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS partner_txn_ref       VARCHAR(128);
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS scheme_id             VARCHAR(64);
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS direction             VARCHAR(32);
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS payment_mode          VARCHAR(32);
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS payout_currency       VARCHAR(3);
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS collection_amount     NUMERIC(20,8);
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS collection_currency   VARCHAR(3);
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS merchant_id           VARCHAR(128);
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS quote_id              VARCHAR(128);

ALTER TABLE transactions ADD COLUMN IF NOT EXISTS scheme_txn_ref        VARCHAR(128);
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS scheme_approval_code  VARCHAR(64);
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS prefund_deducted_usd  NUMERIC(20,8);
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS approved_at           TIMESTAMP;
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS payment_id            VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_txn_created_at ON transactions(created_at);
CREATE INDEX IF NOT EXISTS idx_txn_status     ON transactions(status);
CREATE INDEX IF NOT EXISTS idx_txn_partner_id ON transactions(partner_id);
