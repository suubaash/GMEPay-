-- V007 — committed-FX projection columns (Phase-2 cross-service wiring).
--
-- Captures the rate-locked FX fields the committed-FX projection needs, so
-- transaction-mgmt can answer GET /v1/transactions/fx-committed (BOK FX1015 #14
-- source) and emit the transaction.committed event WITHOUT a downstream consumer
-- (reporting-compliance / settlement-reconciliation / scheme-adapter) reading this
-- DB directly. All columns are NULLABLE and populated best-effort at commit-time
-- (APPROVED transition) — never on the create path, never failing the commit.
--
-- Field semantics (per subash-fx calculation-model.md):
--   offer_rate_coll       = send_amount / (collection_usd - collection_margin_usd)  [FX1015 #14]
--   cross_rate            = target_payout / send_amount
--   usd_amount            = send_usd_cost (the USD pool / margin base, ~= prefund_deducted_usd)
--   same_ccy_shortcircuit = collection_ccy == payout_ccy (no FX leg; rates NULL)
-- H2 (PostgreSQL mode) needs one ALTER per column.

ALTER TABLE transactions ADD COLUMN IF NOT EXISTS offer_rate_coll        NUMERIC(20,8);
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS cross_rate             NUMERIC(20,8);
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS collection_margin_usd  NUMERIC(20,8);
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS payout_margin_usd      NUMERIC(20,8);
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS usd_amount             NUMERIC(20,8);
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS same_ccy_shortcircuit  BOOLEAN;
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS settlement_date        DATE;
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS committed_at           TIMESTAMP;

-- Refund enrichment (so scheme-adapter / settlement can read refund detail off the
-- projection). Nullable; populated when a refund is recorded against the txn.
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS refund_amount_krw      NUMERIC(20,8);
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS qr_code_id             VARCHAR(64);
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS refunded_at            TIMESTAMP;
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS original_payment_txn_ref VARCHAR(128);

-- Index the projection / refund query predicates.
CREATE INDEX IF NOT EXISTS idx_txn_committed_at ON transactions(committed_at);
CREATE INDEX IF NOT EXISTS idx_txn_refunded_at  ON transactions(refunded_at);
