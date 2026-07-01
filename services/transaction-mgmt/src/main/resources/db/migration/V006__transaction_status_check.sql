-- V006 — constrain transactions.status to the valid state-machine values (5.1-T08).
--
-- Without a DB-level CHECK a code bug could persist an arbitrary status string. The
-- value set matches the TransactionStatus enum actually used by this service:
--   CREATED, PENDING_DEBIT, SCHEME_SENT, APPROVED, UNCERTAIN, FAILED,
--   CANCELLED, REVERSED, REFUNDED
-- (SCHEME_SENT + UNCERTAIN added in this wave for the full scheme-dispatch /
-- reconciliation lifecycle; SAD-02 §5.2.)
--
-- H2 (PostgreSQL mode, used for tests) and PostgreSQL 16 both accept this form.
-- Versioned migration → Flyway runs it exactly once; the constraint name guards
-- against a duplicate add if the file is ever re-applied to an already-migrated DB.

ALTER TABLE transactions
    ADD CONSTRAINT chk_txn_status CHECK (status IN (
        'CREATED',
        'PENDING_DEBIT',
        'SCHEME_SENT',
        'APPROVED',
        'UNCERTAIN',
        'FAILED',
        'CANCELLED',
        'REVERSED',
        'REFUNDED'
    ));
