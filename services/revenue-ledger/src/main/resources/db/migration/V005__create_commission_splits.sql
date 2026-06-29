-- V005__create_commission_splits.sql
-- One row per committed transaction capturing the TWO-SIDED commission split
-- (CommissionSplitCalculator, V031 configurable shares — Step 7 / task #102). Distinct from
-- revenue_records (V004, FX margin + service charge) and the double-entry journals (V001/V002):
-- this is the per-transaction record of how one transaction's net merchant fee was split between
-- the QR scheme, GME, and the wallet partner. All amounts are KRW whole units (ZeroPay settles
-- and returns the merchant fee in KRW). Compatible with PostgreSQL and H2 PostgreSQL mode.

CREATE TABLE commission_splits (
    id                     BIGSERIAL PRIMARY KEY,
    -- transaction-mgmt business reference; the idempotency key (one split row per txn).
    txn_ref                VARCHAR(64)    NOT NULL UNIQUE,
    partner_id             BIGINT         NOT NULL,
    scheme_id              BIGINT         NOT NULL,
    revenue_date           DATE           NOT NULL,

    -- snapshot of the inputs the split was computed from (audit / recompute).
    payout_amount_krw      BIGINT         NOT NULL,
    merchant_fee_rate      NUMERIC(7, 4)  NOT NULL,
    van_fee_rate           NUMERIC(7, 4)  NOT NULL,
    gme_share_pct          NUMERIC(6, 4)  NOT NULL,
    partner_share_pct      NUMERIC(6, 4)  NOT NULL,

    -- the computed split (CommissionSplit, all KRW whole units). Invariants:
    --   gme_gross_share_krw + scheme_share_krw == net_merchant_fee_krw
    --   partner_share_krw   + gme_net_share_krw == gme_gross_share_krw
    gross_merchant_fee_krw BIGINT         NOT NULL,
    van_fee_krw            BIGINT         NOT NULL,
    net_merchant_fee_krw   BIGINT         NOT NULL,
    scheme_share_krw       BIGINT         NOT NULL,
    gme_gross_share_krw    BIGINT         NOT NULL,
    partner_share_krw      BIGINT         NOT NULL,
    gme_net_share_krw      BIGINT         NOT NULL,

    created_at             TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Reporting indexes: per-partner and per-scheme date-range aggregates.
CREATE INDEX idx_cs_partner_date ON commission_splits(partner_id, revenue_date);
CREATE INDEX idx_cs_scheme_date  ON commission_splits(scheme_id, revenue_date);
