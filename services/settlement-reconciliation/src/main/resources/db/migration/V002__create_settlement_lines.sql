-- Settlement lines: one row per transaction included in a settlement batch.
-- matched flag tracks reconciliation outcome against scheme-confirmed amounts.
CREATE TABLE settlement_lines (
    id         BIGSERIAL     NOT NULL,
    batch_id   VARCHAR(64)   NOT NULL,
    txn_ref    VARCHAR(64),
    amount     NUMERIC(20,8) NOT NULL,
    currency   VARCHAR(3)    NOT NULL,
    matched    BOOLEAN       NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_settlement_lines PRIMARY KEY (id)
);

CREATE INDEX idx_settlement_lines_batch_id
    ON settlement_lines (batch_id);

CREATE INDEX idx_settlement_lines_txn_ref
    ON settlement_lines (txn_ref);
