-- V029.1: per-corridor STR-enable flag — Slice 8 Lane C "Regulatory
-- attributes", companion to V029 (the plan doc names this migration "V029b";
-- Flyway versions cannot carry letters, so it ships as V029_1 — still inside
-- Lane C's negotiated V029 slot, sorting after V029 and before Lane-external
-- V030+).
--
-- WHY
-- ---
-- KoFIU Suspicious Transaction Reporting is corridor-sensitive: GME enables
-- the STR feed lane-by-lane (e.g. KR→MN on, KR→SG off while the counterparty
-- FIU integration is pending). The partner-level KoFIU attributes live on
-- partner_regulatory_config (V029); this flag is the per-corridor switch the
-- reporting-compliance service reads alongside the corridor row.
--
-- WHY AN IN-PLACE ALTER IS SAFE HERE (ADR-013)
-- --------------------------------------------
-- Single additive ALTER TABLE ADD COLUMN with a constant default — the
-- Expand-phase shape both PostgreSQL and H2 accept verbatim, no vendor split
-- needed (the V023 vendor pair exists only for the STORED generated column;
-- this column is a plain BOOLEAN). Existing rows backfill to FALSE, which is
-- the correct historical truth: no corridor had STR enabled before this
-- migration existed.
--
-- The flag rides the existing SCD-6 row lifecycle of partner_corridor:
-- toggling STR on a lane is a step-7 bulk replace minting a fresh row, never
-- an in-place UPDATE, so "was STR filing on for corridor X on date D?" stays
-- answerable (ADR-010).

ALTER TABLE partner_corridor
    ADD COLUMN str_enabled BOOLEAN DEFAULT FALSE NOT NULL;
