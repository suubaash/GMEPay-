-- payment-executor: execution_attempts records every orchestration outcome (17.2-G08).
-- One row per orchestration attempt. APPROVED rows snapshot the rate-locked per-partner
-- settlement booking exactly as committed to transaction-mgmt: booked amount, the
-- RoundingMode actually applied, and the residual (precise - booked) that was posted
-- to revenue-ledger REVENUE_ROUNDING. See docs/MONEY_CONVENTION.md.
--
-- PostgreSQL 16 in production and docker-tagged ITs; H2 in PostgreSQL mode for unit
-- slices (schema kept intentionally portable: BIGSERIAL, TEXT-free, no JSONB).
-- All money columns are NUMERIC(20,8) per MONEY_CONVENTION.md.

CREATE TABLE execution_attempts (
    id                        BIGSERIAL     PRIMARY KEY,
    txn_ref                   VARCHAR(64)   NOT NULL,
    payment_id                VARCHAR(64),
    partner_id                BIGINT        NOT NULL,
    partner_txn_ref           VARCHAR(64)   NOT NULL,
    scheme_id                 VARCHAR(32)   NOT NULL,
    payment_mode              VARCHAR(5)    NOT NULL,
    direction                 VARCHAR(16),
    outcome                   VARCHAR(24)   NOT NULL,
    failure_reason            VARCHAR(255),
    scheme_txn_ref            VARCHAR(64),
    prefund_deducted_usd      NUMERIC(20,8),
    booked_settlement_amount  NUMERIC(20,8),
    settlement_rounding_mode  VARCHAR(16),
    rounding_residual         NUMERIC(20,8),
    settlement_currency       VARCHAR(3),
    created_at                TIMESTAMP     NOT NULL,
    completed_at              TIMESTAMP,
    CONSTRAINT ck_execution_attempts_mode
        CHECK (payment_mode IN ('MPM', 'CPM')),
    CONSTRAINT ck_execution_attempts_outcome
        CHECK (outcome IN ('PENDING', 'APPROVED', 'FAILED', 'UNCERTAIN',
                           'CANCELLED', 'REVERSED', 'REFUNDED'))
);

CREATE INDEX idx_exec_attempts_txn_ref ON execution_attempts (txn_ref);
CREATE INDEX idx_exec_attempts_partner ON execution_attempts (partner_id, partner_txn_ref);
