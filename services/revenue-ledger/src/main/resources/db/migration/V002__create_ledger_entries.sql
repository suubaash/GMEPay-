-- V002__create_ledger_entries.sql
-- Creates the ledger_entries table: one row per debit/credit line in a journal.
-- A journal is composed of >= 2 ledger_entries that balance per currency.
-- Compatible with PostgreSQL and H2 PostgreSQL mode.

CREATE TABLE ledger_entries (
    id          BIGSERIAL PRIMARY KEY,
    journal_id  VARCHAR(64)    NOT NULL,
    account     VARCHAR(64)    NOT NULL,
    amount      NUMERIC(20, 8) NOT NULL,
    currency    VARCHAR(3)     NOT NULL,
    entry_type  VARCHAR(8)     NOT NULL,
    reference   VARCHAR(64)    NOT NULL
);

CREATE INDEX idx_le_journal ON ledger_entries(journal_id);
CREATE INDEX idx_le_ref     ON ledger_entries(reference);
CREATE INDEX idx_le_account ON ledger_entries(account);
