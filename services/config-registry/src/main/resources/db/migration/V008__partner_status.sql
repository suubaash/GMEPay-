-- V008: lifecycle status column on the partners aggregate (Slice 1 — draft endpoints).
--
-- WHY
-- ---
-- The draft endpoints introduced in this slice (POST /v1/partners/draft, PATCH
-- /v1/partners/draft/{code}/step-1) create / mutate rows that must carry the
-- lifecycle status documented in ADR-011: every draft starts at ONBOARDING and
-- only walks forward through KYB_PENDING → KYB_APPROVED → … → LIVE under the
-- full FSM (Slice 8). Slice 1 only writes ONBOARDING; later slices add the
-- transitions, all guarded by change_request rows.
--
-- This column is intentionally separate from V007 (which lands the Identity
-- attributes) because status is *not* an Identity field — it is the row's
-- lifecycle gate that EVERY slice will read (banking, prefunding, schemes,
-- credentials all condition behaviour on status). Keeping it in its own
-- migration leaves a clean audit trail for "when did the FSM column land".
--
-- EXPAND DISCIPLINE (ADR-013)
-- ---------------------------
-- The column is NOT NULL but the DEFAULT 'ONBOARDING' fills every existing row
-- (the GMEREMIT / SENDMN seeds) inside the DDL statement itself, so no separate
-- UPDATE is needed and no transient NOT NULL violation can occur. This is the
-- ADR-013-sanctioned shape for NOT NULL on a new column.
--
-- CHECK CONSTRAINT
-- ----------------
-- A CHECK enforces the enum surface so a typo at the application layer cannot
-- write garbage. The value set matches the {@link com.gme.pay.contracts.PartnerStatus}
-- enum verbatim — when Slice 8 adds a new value (none currently planned beyond
-- the nine already listed) the migration will be ALTER TABLE … DROP CONSTRAINT
-- … ADD CONSTRAINT to widen the set.
--
-- COMPATIBILITY
-- -------------
-- Plain VARCHAR + DEFAULT + CHECK runs identically on PostgreSQL and on H2 in
-- PostgreSQL compatibility mode (the engine the @DataJpaTest slices use).

ALTER TABLE partners ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ONBOARDING';

ALTER TABLE partners ADD CONSTRAINT ck_partners_status CHECK (
    status IN ('ONBOARDING', 'KYB_PENDING', 'KYB_APPROVED', 'CONTRACT_SIGNED',
               'SANDBOX', 'UAT', 'LIVE', 'SUSPENDED', 'TERMINATED')
);
