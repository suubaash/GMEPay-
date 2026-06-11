-- Reconciliation exceptions: persisted LineMatcher outcomes for a settlement batch.
-- One row per recon line (MATCHED rows may also be persisted for audit; non-MATCHED
-- rows are the ops exception queue). Amounts are NUMERIC(20,8) per MONEY_CONVENTION.
-- PostgreSQL-compatible SQL that also runs under H2 (MODE=PostgreSQL).
CREATE TABLE recon_exceptions (
    id                  BIGSERIAL     NOT NULL,
    batch_id            VARCHAR(64)   NOT NULL,
    merchant_id         VARCHAR(64)   NOT NULL,
    gme_amount          NUMERIC(20,8) NOT NULL,
    scheme_amount       NUMERIC(20,8),
    discrepancy_amount  NUMERIC(20,8) NOT NULL,
    match_status        VARCHAR(32)   NOT NULL,
    created_at          TIMESTAMP     NOT NULL,
    CONSTRAINT pk_recon_exceptions PRIMARY KEY (id)
);

CREATE INDEX idx_recon_exceptions_batch_id
    ON recon_exceptions (batch_id);

CREATE INDEX idx_recon_exceptions_match_status
    ON recon_exceptions (match_status);
