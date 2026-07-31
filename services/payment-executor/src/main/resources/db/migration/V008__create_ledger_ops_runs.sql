-- payment-executor: durable run ledger for the LEDGER-OPS jobs (gap T2-5).
--
-- Same problem, same shape, same reasoning as settlement-reconciliation's `batch_runs` (its Flyway V012,
-- gap T3-4): a scheduled job that fails inside a @Transactional unit rolls its own evidence back, so ops
-- cannot tell a failed run from a run that never happened. Rows here are written in their OWN transaction
-- (Propagation.REQUIRES_NEW) so a FAILED run survives the rollback of the work it describes, and the
-- notification outcome is stamped onto the SAME row so "what failed?" and "did anyone find out?" are one
-- query.
--
-- Why a second table instead of reusing batch_runs: batch_runs lives in settlement-reconciliation's
-- schema and its columns are settlement-file-shaped (file_type ZP0061..., settlement_window
-- MORNING/AFTERNOON, calendar_verdict). These jobs are payment-executor's, run against payment-executor's
-- datasource, and have no settlement window. This is the same PATTERN, not a second mechanism -- there is
-- no way to share one table across two services' databases without payment-executor reaching into
-- settlement's schema, which INTER_SERVICE_CONTRACTS.md forbids.
--
-- Deliberate difference from batch_runs, stated rather than papered over: there is NO calendar_verdict
-- column, because payment-executor has no business-day calendar (T3-4's BusinessCalendar is
-- settlement-local) and these three jobs are calendar-INDEPENDENT by design -- money moves on Korean
-- banking holidays, so a missing revenue posting must still be replayed and a day-close must still be
-- produced for a holiday. If a calendar is ever shared as a library, this table gains the column then.
--
-- PostgreSQL 16 in production; H2 in PostgreSQL mode for unit slices. Portable types only.

CREATE TABLE ledger_ops_runs (
    id              BIGSERIAL     PRIMARY KEY,
    -- Which job ran: REVENUE_POSTING_REPLAY | DAY_CLOSE | FX_EXPOSURE.
    job             VARCHAR(48)   NOT NULL,
    -- The business date the run was FOR (null for the replay sweeper, which is not date-scoped).
    business_date   DATE,
    outcome         VARCHAR(24)   NOT NULL,
    -- SCHEDULER | OPERATOR.
    trigger_source  VARCHAR(16)   NOT NULL,
    -- Operator attribution, set only for an operator-triggered run.
    operator_id     VARCHAR(64),
    -- One-line machine-readable summary of what the run produced (e.g. "replayed=3 poison=1").
    summary         VARCHAR(1000),
    -- How many units of work the run touched, for trend/alert thresholds.
    record_count    INT,
    failure_class   VARCHAR(255),
    failure_message VARCHAR(1000),
    failure_trace   VARCHAR(4000),
    -- Outcome of the ops.alert raised for a FAILED run (NOT_APPLICABLE when nothing was raised).
    alert_status    VARCHAR(20),
    alert_error     VARCHAR(500),
    started_at      TIMESTAMP     NOT NULL,
    finished_at     TIMESTAMP     NOT NULL,
    CONSTRAINT ck_ledger_ops_runs_job
        CHECK (job IN ('REVENUE_POSTING_REPLAY', 'DAY_CLOSE', 'FX_EXPOSURE')),
    CONSTRAINT ck_ledger_ops_runs_outcome
        CHECK (outcome IN ('SUCCESS', 'FAILED', 'SKIPPED_DISABLED')),
    CONSTRAINT ck_ledger_ops_runs_trigger
        CHECK (trigger_source IN ('SCHEDULER', 'OPERATOR'))
);

-- "Did last night's run happen, and did it work?" -- newest first, per job.
CREATE INDEX idx_ledger_ops_runs_job_started ON ledger_ops_runs (job, started_at DESC);
-- "Show me every failure" -- the ops incident query.
CREATE INDEX idx_ledger_ops_runs_outcome ON ledger_ops_runs (outcome, started_at DESC);
