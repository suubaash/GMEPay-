-- V002: append-only ledger of debits/credits against partner_balance.
-- Rows are never updated or deleted. Inserted in the same TX as the balance update.
CREATE TABLE ledger_entry (
    id          BIGSERIAL      PRIMARY KEY,
    partner_id  VARCHAR(32)    NOT NULL,
    txn_ref     VARCHAR(64),
    entry_type  VARCHAR(16)    NOT NULL,
    amount      NUMERIC(20, 8) NOT NULL,
    currency    VARCHAR(3)     NOT NULL,
    created_at  TIMESTAMP      NOT NULL
);

CREATE INDEX idx_ledger_entry_partner_created
    ON ledger_entry (partner_id, created_at);

CREATE INDEX idx_ledger_entry_txn_ref
    ON ledger_entry (txn_ref);
