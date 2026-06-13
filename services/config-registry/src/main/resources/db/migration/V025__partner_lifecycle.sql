-- V025: full partner lifecycle FSM columns (Slice 8 — Lifecycle, ADR-011).
--
-- WHY
-- ---
-- Slice 1 (V008) landed the status column with the nine-value roster the
-- PartnerStatus enum carried from day one; Slice 8 widens the FSM head with
-- DRAFT (ten values total) and adds the lifecycle bookkeeping columns the
-- UAT → LIVE / LIVE → SUSPENDED / → TERMINATED transitions stamp:
--
--   * go_live_at / activated_by    — set ONCE, on the FIRST UAT → LIVE
--                                    transition. go_live_at non-NULL is also
--                                    the post-activation immutability lock
--                                    marker (partner_code, type, currencies,
--                                    country_of_incorporation freeze — see
--                                    PartnerImmutabilityGuard).
--   * suspension_reason / _notes / suspended_at
--                                  — stamped by LIVE → SUSPENDED; cleared by
--                                    the SUSPENDED → LIVE reactivation.
--   * terminated_at / termination_reason
--                                  — stamped by → TERMINATED (terminal state).
--
-- All columns are nullable: every pre-Slice-8 row (and every row that never
-- reaches LIVE) simply carries NULLs — the ADR-013 Expand discipline, no
-- backfill required.
--
-- STATUS CHECK WIDENING
-- ---------------------
-- V008 documented this exact move: "when Slice 8 adds a new value the
-- migration will be ALTER TABLE … DROP CONSTRAINT … ADD CONSTRAINT to widen
-- the set". DROP CONSTRAINT <name> on a named CHECK is engine-neutral
-- (PostgreSQL and H2 2.x both honour it), so no db/vendor split is needed —
-- the whole file is plain portable DDL (TIMESTAMP, VARCHAR; ADR rule: no
-- TIMESTAMPTZ / JSONB).
--
-- SUSPENSION REASON ROSTER
-- ------------------------
-- The CHECK mirrors the lib-api-contracts SuspensionReason enum verbatim —
-- keep the two in lock-step (same discipline as ck_partner_contact_role /
-- ContactRole).
--
-- partner_contract.signed_at
-- --------------------------
-- The Slice 8 activation gate requires "a contract row effective today AND
-- signed". V021 carried the effective window but no signature instant; the
-- nullable signed_at column lands here (same Expand discipline). The wizard's
-- step-6 contract save populates it via ContractCommand.signedAt.

ALTER TABLE partners ADD COLUMN go_live_at TIMESTAMP;
ALTER TABLE partners ADD COLUMN activated_by VARCHAR(100);
ALTER TABLE partners ADD COLUMN suspension_reason VARCHAR(40);
ALTER TABLE partners ADD COLUMN suspension_notes VARCHAR(500);
ALTER TABLE partners ADD COLUMN suspended_at TIMESTAMP;
ALTER TABLE partners ADD COLUMN terminated_at TIMESTAMP;
ALTER TABLE partners ADD COLUMN termination_reason VARCHAR(500);

ALTER TABLE partners ADD CONSTRAINT ck_partners_suspension_reason CHECK (
    suspension_reason IS NULL OR suspension_reason IN (
        'LIMIT_BREACH', 'SANCTIONS_HIT', 'CREDENTIAL_COMPROMISE',
        'KYB_LAPSED', 'CONTRACT_EXPIRED', 'OPERATOR_INITIATED')
);

-- Widen the status roster to the full ten-state FSM (V008 promised this move).
ALTER TABLE partners DROP CONSTRAINT ck_partners_status;

ALTER TABLE partners ADD CONSTRAINT ck_partners_status CHECK (
    status IN ('DRAFT', 'ONBOARDING', 'KYB_PENDING', 'KYB_APPROVED',
               'CONTRACT_SIGNED', 'SANDBOX', 'UAT', 'LIVE',
               'SUSPENDED', 'TERMINATED')
);

-- Activation gate input: when was the paper contract countersigned.
ALTER TABLE partner_contract ADD COLUMN signed_at TIMESTAMP;
