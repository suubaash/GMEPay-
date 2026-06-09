-- Settlement batches owned by settlement-reconciliation service.
-- One row per (partner_id, business_date, window) ZP006x batch lifecycle entry.
-- PostgreSQL-compatible SQL that also runs under H2 (MODE=PostgreSQL).
CREATE TABLE settlement_batches (
    batch_id        VARCHAR(64)   NOT NULL,
    partner_id      VARCHAR(32)   NOT NULL,
    business_date   DATE          NOT NULL,
    status          VARCHAR(16)   NOT NULL,
    total_amount    NUMERIC(20,8),
    total_currency  VARCHAR(3),
    created_at      TIMESTAMP     NOT NULL,
    CONSTRAINT pk_settlement_batches PRIMARY KEY (batch_id)
);

CREATE INDEX idx_settlement_batches_partner_date
    ON settlement_batches (partner_id, business_date);

CREATE INDEX idx_settlement_batches_status
    ON settlement_batches (status);
