-- V009 (Ops) — operator force-resolution audit columns.
--
-- Adds the WHO/WHY/WHEN captured when an operator force-resolves an UNCERTAIN transaction to a
-- terminal state via POST /v1/transactions/{txnRef}/resolve (resolution = COMPLETED | REVERSED).
-- These records land in the transaction row so the drill-down / audit view shows the manual
-- intervention. All NULLABLE and only set on force-resolved rows; the normal lifecycle leaves
-- them null. Additive only — no in-place edit of an existing migration.
-- H2 (PostgreSQL mode) needs one ALTER per column.

ALTER TABLE transactions ADD COLUMN IF NOT EXISTS resolution_reason VARCHAR(256);
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS resolved_by       VARCHAR(128);
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS resolved_at       TIMESTAMP;
