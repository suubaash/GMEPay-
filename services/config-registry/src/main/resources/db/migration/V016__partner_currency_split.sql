-- V016: collection/settle currency split on the partners aggregate — Slice 6
-- "Commercial Terms" (docs/PARTNER_SETUP_PLAN.md §Slice 6, DAT-03 §4.3 +
-- PRD-07 §5.3.2).
--
-- WHY
-- ---
-- The single settlement_currency column conflates two distinct facts:
--   * collection_ccy — the currency in which the partner COLLECTS from its
--     senders (e.g. KRW for a Korean outbound corridor), and
--   * settle_a_ccy   — the currency in which GME SETTLES with the partner
--     (e.g. USD through the USD pool).
-- A cross-border corridor (collect KRW / settle USD) cannot be expressed with
-- one column; the rate engine's same-currency short-circuit (lib-domain
-- Rule.sameCurrency) needs both sides to decide whether the m_a + m_b >= 2%
-- margin floor applies.
--
-- EXPAND PHASE (ADR-013)
-- ----------------------
-- This migration is the EXPAND + BACKFILL step of the three-release
-- Expand/Backfill/Contract plan:
--   1. (this release)  ADD both columns NULLABLE + backfill from
--      settlement_currency; settlement_currency KEEPS being written by every
--      code path (PartnerEntity mirrors it on persist).
--   2. (next release)   consumers (transaction-mgmt / rate-fx) switch their
--      reads to the split columns.
--   3. (release after)  a Contract migration drops settlement_currency.
-- Do NOT drop or stop populating settlement_currency here.
--
-- The backfill UPDATE below touches historical (superseded) rows too — that is
-- intentional and ADR-013-sanctioned: a schema backfill is a representation
-- change, not a business mutation, so the SCD-6 "never UPDATE a row's content"
-- discipline (which governs application writes) does not apply. As-of reads
-- against pre-V016 history must see the split columns populated the same way
-- the current rows are.
--
-- COMPATIBILITY
-- -------------
-- CHAR(3) (ISO-4217 alphabetic code) matches the settlement_currency column it
-- mirrors. Plain ALTER + UPDATE runs identically on PostgreSQL and H2 in
-- PostgreSQL mode (the @DataJpaTest engine).

-- 1) Expand: both sides of the split, NULL allowed (drafts may not have chosen
--    currencies yet; the Slice 6 commercial-terms step writes them).
ALTER TABLE partners ADD COLUMN collection_ccy CHAR(3);
ALTER TABLE partners ADD COLUMN settle_a_ccy   CHAR(3);

-- 2) Backfill: before the split existed, collection and settlement were the
--    same fact, so both sides start as the legacy value. Rows whose
--    settlement_currency is NULL (never configured) stay NULL on both sides.
UPDATE partners
   SET collection_ccy = settlement_currency,
       settle_a_ccy   = settlement_currency
 WHERE settlement_currency IS NOT NULL;
