-- V008 — ShedLock table for distributed scheduler locking (gap T3-11 defect 3).
--
-- Why notification-webhook needs one: WebhookDispatcher.drainPending() and WebhookBacklogMonitor
-- run on EVERY instance. Two replicas ticking in the same window both select the same PENDING
-- webhook_delivery_log rows and both POST them, so the partner receives the same webhook twice —
-- and a webhook is an instruction a partner's systems act on, not a notice they can ignore. The
-- dispatcher's own javadoc had carried "a distributed lock (e.g. ShedLock) is a follow-up" since it
-- was written; this table is the store that follow-up needs.
--
-- Canonical ShedLock JdbcTemplate schema — column names and types are fixed by the provider:
--   name       -- lock name (the @SchedulerLock name), PK: one row per named job
--   lock_until -- held until this instant; a tick may run only when now >= lock_until
--   locked_at  -- when the current holder acquired it
--   locked_by  -- holder identity (hostname), for diagnostics only
--
-- Engine-neutral (PostgreSQL + H2 in PostgreSQL mode): plain TIMESTAMP + VARCHAR, no vendor types.
-- Additive only — a new table, touching nothing in V001–V007. Identical in shape to prefunding's
-- V008 and transaction-mgmt's V010 so the three services cannot drift apart.

CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
