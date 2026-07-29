-- V012 (GAP T3-4): the durable batch-run ledger.
--
-- Before this table, a failed settlement-generation or recon run left NOTHING behind. The generation
-- path runs inside one @Transactional (SettlementBatchJobService.runWindow), so a failure rolled the
-- whole thing back — no batch row, no lines, no ERROR marker — and the scheduler's catch block only
-- wrote a log line. Ops therefore had no way to learn that 05:00 had not happened; the first signal
-- was the counterparty noticing a missing file.
--
-- Every run of every window now writes exactly one row here, in its OWN transaction
-- (BatchRunRecorder, REQUIRES_NEW) so it SURVIVES the rollback of the run it is describing. That
-- makes the table answer three separate operational questions from one place:
--   1. "did last night's runs happen?"      -> newest row per (file_type, settlement_window, business_date)
--   2. "why did 05:00 fail?"                -> failure_class / failure_message / failure_trace
--   3. "did anyone find out?"               -> alert_status / alert_error
--
-- calendar_verdict records what the configured business-day calendar said about business_date at the
-- time of the run (BUSINESS_DAY / NON_BUSINESS_DAY / UNVERIFIED). UNVERIFIED is the shipped default
-- because the calendar starts empty and populating it is an operator/business input — so the row
-- proves whether a given file was produced on a day anybody had actually verified was a banking day.
--
-- Rows are an append-only audit trail: a re-run adds a row, it never mutates the failed one.
-- PostgreSQL + H2 (MODE=PostgreSQL) portable; no vendor-specific types (matches V001-V011, which are
-- flat with no vendor subdirectories to mirror).

CREATE TABLE batch_runs (
    id                 BIGSERIAL     NOT NULL,
    -- GENERATION (outbound ZP0061/0063/0065/0066) | RECON (inbound ZP0062/0064)
    run_kind           VARCHAR(24)   NOT NULL,
    file_type          VARCHAR(16)   NOT NULL,
    settlement_window  VARCHAR(16)   NOT NULL,   -- MORNING | AFTERNOON | DETAIL
    business_date      DATE          NOT NULL,
    -- SUCCESS | FAILED | SKIPPED_NON_BUSINESS_DAY | SKIPPED_DISABLED
    outcome            VARCHAR(32)   NOT NULL,
    trigger_source     VARCHAR(24)   NOT NULL,   -- SCHEDULER | OPERATOR_RERUN
    -- BUSINESS_DAY | NON_BUSINESS_DAY | UNVERIFIED  (see BusinessCalendar)
    calendar_verdict   VARCHAR(24)   NOT NULL,
    batch_id           VARCHAR(64),              -- set on SUCCESS; null when the run never got that far
    batch_status       VARCHAR(24),
    record_count       INTEGER,
    failure_class      VARCHAR(255),             -- exception class name, e.g. BatchPrerequisiteException
    failure_message    VARCHAR(1000),
    failure_trace      VARCHAR(4000),            -- truncated stack excerpt: enough to diagnose, bounded
    alert_status       VARCHAR(16),              -- RAISED | FAILED | NOT_APPLICABLE
    alert_error        VARCHAR(500),
    operator_id        VARCHAR(64),              -- OPERATOR_RERUN only
    reason             VARCHAR(500),             -- OPERATOR_RERUN only
    started_at         TIMESTAMP     NOT NULL,
    finished_at        TIMESTAMP     NOT NULL,
    CONSTRAINT pk_batch_runs PRIMARY KEY (id)
);

-- "did the 05:00 ZP0061 for this date run, and how did it end?"
CREATE INDEX idx_batch_runs_date_type_window
    ON batch_runs (business_date, file_type, settlement_window);

-- "show me every failure, newest first" (the ops query behind GET /v1/settlements/batch-runs)
CREATE INDEX idx_batch_runs_outcome_finished
    ON batch_runs (outcome, finished_at);
