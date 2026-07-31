-- V003 - ShedLock table for distributed scheduler locking.
--
-- WHAT IS LOCKED HERE, AND WHAT DELIBERATELY IS NOT. This service now has exactly two @Scheduled
-- jobs, and they get OPPOSITE treatment on purpose:
--
--   * OpsAlertRetentionSweeper  -> LOCKED. It issues a bulk DELETE over ops_alerts. The delete is
--     idempotent, so a duplicate prune is harmless in outcome; it is locked anyway for the reason
--     T3-11 settled on in payment-executor's ShedLockConfig -- N replicas each issuing the same bulk
--     DELETE is N times the contention for zero benefit, and "is this one locked?" must not be a
--     per-job judgement someone re-makes whenever a job body changes.
--
--   * OpsPagingEscalationScheduler -> NOT LOCKED, AND MUST NOT BE. It re-pages un-acked CRITICAL
--     alerts. A lock can only ever SUBTRACT escalations: a stuck lock row, a lock provider outage or
--     an over-long lockAtMostFor means NO replica sweeps, and the failure of a lock is then a MISSED
--     PAGE during an incident -- the exact failure the mechanism exists to prevent. The duplicate
--     that a lock would prevent is already prevented at the pager, by the shared PagingCooldown that
--     is claimed atomically before every page. So: every replica sweeps, at most one page goes out
--     per dedupe window. See OpsPagingEscalationScheduler's javadoc, which reverses an earlier note
--     in that class recommending exactly the ShedLock this migration makes possible.
--
-- The existence of this table therefore does NOT mean "lock the schedulers"; it means one job needed
-- a lock and the other's lack of one is now a recorded decision rather than an absence.
--
-- Canonical ShedLock JdbcTemplate schema - column names and types are fixed by the provider:
--   name       -- lock name (the @SchedulerLock name), PK: one row per named job
--   lock_until -- held until this instant; a tick may run only when now >= lock_until
--   locked_at  -- when the current holder acquired it
--   locked_by  -- holder identity (hostname), for diagnostics only
--
-- Engine-neutral (PostgreSQL + H2 in PostgreSQL mode): plain TIMESTAMP + VARCHAR, no vendor types.
-- Identical in shape to payment-executor's V011, prefunding's V008 and transaction-mgmt's V010 so
-- the services cannot drift apart.

CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
