-- V006: append-only per-(partner, period) cumulative-usage ledger backing the AML daily/monthly/annual
-- caps (config-registry V020 partner_limits, carried to payment-executor as TxnLimits). Rows are NEVER
-- updated/deleted. A CUM_CHARGE is appended at AUTHORIZE under the SAME per-partner SELECT...FOR UPDATE lock
-- that serialises reserve()/capture()/release() (PartnerBalanceRepository.lockByPartnerId), so the
-- read-sum/compare/append cap check is race-free by construction. A CUM_REVERSE (signed negative, carrying
-- its charge's ORIGINAL period keys) is appended when a held-but-not-confirmed authorize is voided, so a
-- transaction that never completes does not permanently consume cap.
--
-- Period usage = SUM(amount_usd) for a (partner_id, <period>_key). KST-derived keys are STORED (not computed
-- in SQL) so bucketing is timezone-correct without query-time tz math, and a reverse nets into the charge's
-- original period regardless of when the reverse happens.
--
-- H2 (MODE=PostgreSQL) + PostgreSQL portable; money NUMERIC(19,4) major USD per MONEY_CONVENTION.md.
CREATE TABLE cumulative_usage_ledger (
    id          BIGSERIAL      PRIMARY KEY,
    partner_id  VARCHAR(32)    NOT NULL,
    txn_ref     VARCHAR(64)    NOT NULL,
    entry_type  VARCHAR(16)    NOT NULL,   -- CUM_CHARGE | CUM_REVERSE
    amount_usd  NUMERIC(19, 4) NOT NULL,   -- signed: + on charge, - on reverse
    daily_key   VARCHAR(10)    NOT NULL,   -- yyyy-MM-dd (KST)
    monthly_key VARCHAR(7)     NOT NULL,   -- yyyy-MM   (KST)
    annual_key  VARCHAR(4)     NOT NULL,   -- yyyy      (KST)
    created_at  TIMESTAMP      NOT NULL
);

CREATE INDEX idx_cum_usage_txn     ON cumulative_usage_ledger (partner_id, txn_ref);
CREATE INDEX idx_cum_usage_daily   ON cumulative_usage_ledger (partner_id, daily_key);
CREATE INDEX idx_cum_usage_monthly ON cumulative_usage_ledger (partner_id, monthly_key);
CREATE INDEX idx_cum_usage_annual  ON cumulative_usage_ledger (partner_id, annual_key);
