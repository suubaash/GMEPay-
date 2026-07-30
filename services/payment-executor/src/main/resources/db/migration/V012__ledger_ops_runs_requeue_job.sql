-- V012 — allow the REVENUE_POSTING_REQUEUE job in the ledger-ops run ledger (gap T2-5 follow-up / T3-12).
--
-- Why a migration is needed for what looks like a code-only change:
-- V008 pinned the permitted job names in a CHECK constraint (ck_ledger_ops_runs_job) so the column and the
-- LedgerOpsJob constants cannot drift. That is the right design, and its price is that adding a job is a
-- schema change. Without this, the first operator requeue would write its audit row, violate the constraint,
-- and the run would be recorded as FAILED — or not at all.
--
-- What the new job is:
-- POST /internal/ops/revenue-posting-failures/requeue moves POISON rows back to PENDING with attempts=0,
-- after the reason they were poisoned has been fixed. T3-12 is the case that forced it: revenue-ledger
-- returned 406 on two journal endpoints, the replay client classified 406 as a permanent rejection, and
-- every rounding-residual and reversal posting was poisoned on the first sweep for a SERVER defect that was
-- later fixed. POISON is terminal by design, so there was no way back short of hand-editing the table.
--
-- Unlike the other three jobs this one is never scheduled — it is only ever an attributed human act. That is
-- what makes the audit row load-bearing rather than decorative: the requeue resets attempts to 0, discarding
-- the row's own record of how many times the posting had been pushed at the ledger, so ledger_ops_runs is
-- the only place "who un-poisoned these, when, and why" survives.
--
-- Additive and reversible: the constraint is widened, never narrowed, so every row written under V008
-- still satisfies it and this migration cannot fail on existing data.
--
-- PostgreSQL 16 in production; H2 in PostgreSQL mode for unit slices. Both support
-- ALTER TABLE ... DROP CONSTRAINT / ADD CONSTRAINT with these names.

ALTER TABLE ledger_ops_runs DROP CONSTRAINT ck_ledger_ops_runs_job;

ALTER TABLE ledger_ops_runs ADD CONSTRAINT ck_ledger_ops_runs_job
    CHECK (job IN ('REVENUE_POSTING_REPLAY', 'DAY_CLOSE', 'FX_EXPOSURE', 'REVENUE_POSTING_REQUEUE'));
