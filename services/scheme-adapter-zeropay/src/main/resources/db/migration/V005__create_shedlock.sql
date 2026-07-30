-- V005 — ShedLock table for distributed scheduler locking (gap T3-11 defect 3).
--
-- Why an adapter needs one: ZeroPayBatchScheduler's six KST windows do not merely compute and store
-- — each GENERATES a ZP00xx file and TRANSFERS it to ZeroPay. On a second replica both pods fire the
-- same cron in the same second and the scheme receives the file twice. A duplicate ZP0011 is a
-- duplicate payment-result registration; a duplicate ZP0061 is a duplicate settlement request.
-- Neither is recallable, and zp_batch_files would hold two GENERATED rows for one business date, so
-- the audit trail could not say what the scheme was actually sent either.
--
-- Canonical ShedLock JdbcTemplate schema — column names and types are fixed by the provider:
--   name       -- lock name (the @SchedulerLock name), PK: one row per named window
--   lock_until -- held until this instant; a tick may run only when now >= lock_until
--   locked_at  -- when the current holder acquired it
--   locked_by  -- holder identity (hostname), for diagnostics only
--
-- Engine-neutral (PostgreSQL + H2 in PostgreSQL mode): plain TIMESTAMP + VARCHAR, no vendor types.
-- Additive only — a new table, touching nothing in V001–V004. Identical in shape to prefunding's
-- V008 and transaction-mgmt's V010 so the services cannot drift apart.

CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
