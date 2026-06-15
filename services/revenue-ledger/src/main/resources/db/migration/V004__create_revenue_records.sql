-- V004__create_revenue_records.sql
-- One row per committed transaction capturing FX margin + service-charge income (the
-- RevenueRecord store, P1-4). Distinct from the double-entry journals (V001/V002): this is the
-- per-transaction revenue audit trail the Admin Portal aggregates for GET /v1/revenue.
-- Compatible with PostgreSQL and H2 PostgreSQL mode.

CREATE TABLE revenue_records (
    id                     BIGSERIAL PRIMARY KEY,
    -- transaction-mgmt business reference; the idempotency key (one revenue row per txn).
    txn_ref                VARCHAR(64)    NOT NULL UNIQUE,
    partner_id             BIGINT         NOT NULL,
    scheme_id              BIGINT         NOT NULL,
    revenue_date           DATE           NOT NULL,
    -- fx_margin_usd = collectionMarginUsd + payoutMarginUsd; 0 for same-currency (domestic) txns.
    fx_margin_usd          NUMERIC(20, 4) NOT NULL,
    service_charge_amount  NUMERIC(20, 4) NOT NULL,
    service_charge_ccy     VARCHAR(3)     NOT NULL,
    fee_share_pct          NUMERIC(6, 4)  NOT NULL,
    created_at             TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Reporting indexes: per-partner and per-scheme date-range aggregates (GET /v1/revenue).
CREATE INDEX idx_rr_partner_date ON revenue_records(partner_id, revenue_date);
CREATE INDEX idx_rr_scheme_date  ON revenue_records(scheme_id, revenue_date);
