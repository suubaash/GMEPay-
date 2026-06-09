-- V001__create_journals.sql
-- Creates the journals table for the revenue-ledger service.
-- One row per posted double-entry journal. journal_id is the immutable business id (UUID string).
-- Compatible with PostgreSQL and H2 PostgreSQL mode (no Postgres-only types, no JSONB).

CREATE TABLE journals (
    journal_id  VARCHAR(64) PRIMARY KEY,
    reference   VARCHAR(64),
    posted_at   TIMESTAMP NOT NULL
);

CREATE INDEX idx_journals_reference ON journals(reference);
