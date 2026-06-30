-- V006__rounding_residual_idempotency.sql
-- Idempotency backstop for postRoundingResidual(reference, ...).
--
-- Rounding residuals are posted exactly once per `reference` (a settlement BATCH id from
-- settlement-reconciliation, or a per-TXN ref from payment-executor). A retry must NOT create a
-- second journal. LedgerPostingService pre-checks via findRoundingResidualByReference; this table is
-- the DATABASE backstop against a concurrent double-post racing the pre-check: its PRIMARY KEY on
-- `reference` makes the second concurrent rounding post fail loudly rather than double-book.
--
-- Why a dedicated key table instead of a partial / expression UNIQUE INDEX on ledger_entries:
-- H2 in PostgreSQL MODE (used by the no-Docker @DataJpaTest suite) supports neither `CREATE UNIQUE
-- INDEX ... WHERE` nor expression indexes, so a portable guard that works on BOTH H2 and PostgreSQL
-- must be an ordinary PK. This table only constrains REVENUE_ROUNDING posts; revenue-capture,
-- fee-share and reversal journals (which carry the SAME `reference` on OTHER accounts) are untouched.
--
-- Compatible with PostgreSQL and H2 PostgreSQL mode (no engine-specific types).
CREATE TABLE rounding_residual_keys (
    reference   VARCHAR(64) PRIMARY KEY,
    journal_id  VARCHAR(64) NOT NULL,
    posted_at   TIMESTAMP   NOT NULL
);
