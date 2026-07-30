-- V014 — ShedLock table for distributed scheduler locking (gap T3-11 defect 3).
--
-- Why settlement-reconciliation needs one, and why it is the most consequential of the four:
-- seven @Scheduled jobs run on EVERY instance and none was locked. Three of them
-- (SettlementGenerationScheduler: 05:00 / 14:00 / 22:00 KST) generate and TRANSMIT settlement files
-- to a scheme. A second replica does not duplicate a log line — it duplicates a settlement
-- instruction between institutions. The outbox drain, the two recon runs and the corridor recon each
-- write durable state a downstream treats as authoritative.
--
-- Canonical ShedLock JdbcTemplate schema — column names and types are fixed by the provider:
--   name       -- lock name (the @SchedulerLock name), PK: one row per named job
--   lock_until -- held until this instant; a tick may run only when now >= lock_until
--   locked_at  -- when the current holder acquired it
--   locked_by  -- holder identity (hostname), for diagnostics only
--
-- Engine-neutral (PostgreSQL + H2 in PostgreSQL mode): plain TIMESTAMP + VARCHAR, no vendor types.
-- Additive only — a new table, touching nothing in V001–V013. Identical in shape to prefunding's
-- V008 and transaction-mgmt's V010 so the services cannot drift apart.

CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
