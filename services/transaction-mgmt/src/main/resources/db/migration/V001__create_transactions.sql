-- transaction-mgmt: core transaction table.
-- PostgreSQL 16 in production; H2 in PostgreSQL mode for unit/integration tests.
-- All money columns are NUMERIC(20,8) per MONEY_CONVENTION.md.
-- The three rate-lock columns (booked_settlement_amount, settlement_rounding_mode,
-- rounding_residual) are populated at commit-time and immutable thereafter
-- (immutability enforced by the application, not by DDL, to keep H2/Postgres parity).

CREATE TABLE transactions (
    txn_ref                   VARCHAR(64)   NOT NULL,
    partner_ref               VARCHAR(64)   NOT NULL,
    send_amount               NUMERIC(20,8) NOT NULL,
    send_ccy                  VARCHAR(3)    NOT NULL,
    target_payout             NUMERIC(20,8) NOT NULL,
    target_ccy                VARCHAR(3)    NOT NULL,
    status                    VARCHAR(24)   NOT NULL,
    booked_settlement_amount  NUMERIC(20,8),
    settlement_rounding_mode  VARCHAR(16),
    rounding_residual         NUMERIC(20,8),
    created_at                TIMESTAMP     NOT NULL,
    updated_at                TIMESTAMP     NOT NULL,
    CONSTRAINT pk_transactions PRIMARY KEY (txn_ref)
);

CREATE INDEX idx_txn_partner ON transactions(partner_ref);
