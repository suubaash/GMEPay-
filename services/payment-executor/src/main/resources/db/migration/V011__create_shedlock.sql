-- V011 — ShedLock table for distributed scheduler locking (gap T3-11 defect 3).
--
-- Why payment-executor needs one: five @Scheduled jobs run on EVERY instance and none was locked.
-- Two of them matter on a second replica:
--   * AuthorizationExpirySweeper RELEASES prefunding holds on expired authorizations — two replicas
--     selecting the same expired rows both issue a release, crediting a partner's float twice for
--     one authorization.
--   * RevenuePostingReplayScheduler drains revenue_posting_failures against a BOUNDED attempt
--     budget whose "one attempt per row per sweep" property is enforced per JVM; N replicas spend
--     that budget N times faster and can burn a row to POISON over a single downstream outage.
-- FxExposureScheduler and OpsAlertRetentionSweeper are locked too — see ShedLockConfig for why the
-- idempotent ones are not treated as exceptions.
--
-- Canonical ShedLock JdbcTemplate schema — column names and types are fixed by the provider:
--   name       -- lock name (the @SchedulerLock name), PK: one row per named job
--   lock_until -- held until this instant; a tick may run only when now >= lock_until
--   locked_at  -- when the current holder acquired it
--   locked_by  -- holder identity (hostname), for diagnostics only
--
-- Engine-neutral (PostgreSQL + H2 in PostgreSQL mode): plain TIMESTAMP + VARCHAR, no vendor types.
-- Additive only — a new table, touching nothing in V001–V010. Identical in shape to prefunding's
-- V008 and transaction-mgmt's V010 so the services cannot drift apart.

CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
