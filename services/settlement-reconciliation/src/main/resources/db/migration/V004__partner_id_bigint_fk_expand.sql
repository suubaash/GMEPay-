-- V004: Slice 1 cross-cutting bug fix #1 — Partner ID schism resolution, Expand phase.
--
-- Per docs/PARTNER_SETUP_PLAN.md "Cross-cutting bug fixes" + ADR-013 Expand/Backfill/
-- Contract: every service that holds a partner foreign key switches the column type
-- from String to BIGINT, matching the new partner_id surrogate that V003 added to
-- config-registry's partners table.
--
-- This service's only partner FK lives on settlement_batches.partner_id, currently
-- VARCHAR(32). Expand discipline:
--   * Add a NEW column partner_id_new BIGINT, nullable. Application code (Slice 2+
--     onwards) writes both columns until every read path has migrated.
--   * Leave the existing partner_id VARCHAR(32) column untouched, populated, indexed.
--     ADR-013 forbids in-place ALTER NOT NULL on existing columns and forbids
--     destructive drops in the Expand phase.
--   * Backfill via partner_code lookup belongs to the cross-service join that
--     config-registry will publish as part of Slice 2; this migration only opens
--     the schema slot so application writes can start populating it.
--
-- The Contract migration (a future release) will drop partner_id (VARCHAR), rename
-- partner_id_new -> partner_id, and add the NOT NULL + FK constraint. Don't try to
-- do it here.
--
-- PostgreSQL-compatible SQL that also runs under H2 (MODE=PostgreSQL).

ALTER TABLE settlement_batches ADD COLUMN partner_id_new BIGINT;

-- Index the new column on the same access path as the old one so range scans by
-- partner stay fast as the application starts populating it.
CREATE INDEX idx_settlement_batches_partner_id_new
    ON settlement_batches (partner_id_new);
