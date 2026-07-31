-- V004 (GAP T3-4): the ZeroPay batch-run ledger.
--
-- ZeroPayBatchScheduler fires six KST crons (02:00 ZP0011, 02:02 ZP0021, 05:00 ZP0061, 14:00 ZP0063,
-- 22:00 ZP0065, 22:02 ZP0066) and every one of them ended in `catch (Exception e) { log.error(...) }`.
-- Nothing was persisted, no alert was emitted, and there was no manual trigger to retry with — so a
-- failed 02:00 ZP0011 was invisible until KFTC noticed the missing registration file, which then also
-- blocks that day's ZP0061/ZP0063 settlement request via the §8.2 prerequisite gate.
--
-- One row per run of every window, written in its OWN transaction (ZpBatchRunRecorder, REQUIRES_NEW) so
-- a FAILED run survives the rollback of whatever it was doing. Mirrors settlement-reconciliation's
-- batch_runs (V012) column-for-column where the concepts coincide, so an operator reads one shape across
-- both services.
--
-- calendar_verdict records what the configured business-day calendar said about business_date at run
-- time. It is UNVERIFIED by default because the calendar ships empty and populating it is an
-- operator/business input -- see Documentation/RUNBOOK_BATCH_OPS.md.
--
-- Append-only: a re-run adds a row, it never mutates the failed one.
-- PostgreSQL + H2 (MODE=PostgreSQL) portable; no vendor-specific types (matches V001-V003, which are
-- flat with no vendor subdirectories to mirror).

CREATE TABLE zp_batch_runs (
    id                BIGSERIAL     NOT NULL,
    batch_type        VARCHAR(16)   NOT NULL,   -- ZP0011 | ZP0021 | ZP0061 | ZP0063 | ZP0065 | ZP0066
    business_date     DATE          NOT NULL,
    -- SUCCESS | FAILED | SKIPPED_NON_BUSINESS_DAY | SKIPPED_DISABLED
    outcome           VARCHAR(32)   NOT NULL,
    trigger_source    VARCHAR(24)   NOT NULL,   -- SCHEDULER | OPERATOR_RERUN
    -- BUSINESS_DAY | NON_BUSINESS_DAY | UNVERIFIED  (see BusinessCalendar)
    calendar_verdict  VARCHAR(24)   NOT NULL,
    record_count      INTEGER,                  -- records in the generated file, on SUCCESS
    transferred       BOOLEAN,                  -- whether transferOutbound reported success
    failure_class     VARCHAR(255),
    failure_message   VARCHAR(1000),
    failure_trace     VARCHAR(4000),            -- truncated stack excerpt: enough to diagnose, bounded
    alert_status      VARCHAR(16),              -- RAISED | FAILED | NOT_APPLICABLE
    alert_error       VARCHAR(500),
    operator_id       VARCHAR(64),              -- OPERATOR_RERUN only
    reason            VARCHAR(500),             -- OPERATOR_RERUN only
    started_at        TIMESTAMP     NOT NULL,
    finished_at       TIMESTAMP     NOT NULL,
    CONSTRAINT pk_zp_batch_runs PRIMARY KEY (id)
);

-- "did the 02:00 ZP0011 for this date run, and how did it end?"
CREATE INDEX idx_zp_batch_runs_date_type
    ON zp_batch_runs (business_date, batch_type);

-- "show me every failure, newest first"
CREATE INDEX idx_zp_batch_runs_outcome_finished
    ON zp_batch_runs (outcome, finished_at);
