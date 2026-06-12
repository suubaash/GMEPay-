-- V021: partner_contract child table — Slice 6 "Commercial Terms"
-- (docs/PARTNER_SETUP_PLAN.md §Slice 6).
--
-- WHY BITEMPORAL (ADR-010)
-- ------------------------
-- The contract window gates whether transactions may flow at all (the Slice 6
-- exit gate rejects transactions outside the effective dates with
-- CONTRACT_NOT_ACTIVE), and renewal/termination terms are what legal disputes
-- turn on. Same SCD-6 column pairs as V004..V020:
--
--   * valid_from  / valid_to       — business time (when this contract fact
--                                     is true — NOT the contract term itself;
--                                     that is effective_from/effective_to)
--   * recorded_at / superseded_at  — transaction time (when we wrote it; NULL
--                                     on rows that are still current)
--
-- Note the two date pairs are DIFFERENT axes: effective_from/effective_to is
-- the commercial contract term (a business FIELD, DATE-granular), while
-- valid_from/valid_to is the ADR-010 business-time axis of the row version.
-- A signed amendment moving the end date produces a NEW row (paired write)
-- whose effective_to differs — history shows both versions.
--
-- Storage discipline: rows are NEVER UPDATEd in place. A wizard step-6 save is
-- a paired write — supersede + insert sharing one MICROS-truncated instant
-- (see ContractService).
--
-- ONE CURRENT ROW PER PARTNER
-- ---------------------------
-- Same one-current-row-per-partner service discipline and composite-index
-- choice as V013/V015/V019/V020.
--
-- COMPATIBILITY
-- -------------
-- Plain TIMESTAMP, not TIMESTAMPTZ (H2 PG-mode compat, same as V004..V020).
-- BIGSERIAL surrogate id, engine-managed (GenerationType.IDENTITY).
--
-- ADR-013 Expand discipline: wholly-new table; no in-place ALTERs.

CREATE TABLE partner_contract (
    -- BIGSERIAL surrogate, engine-managed — same id strategy as V015/V018..V020.
    id                        BIGSERIAL    NOT NULL,

    -- FK to the partners surrogate (V003/V004 BIGINT PK); consumers resolve
    -- partners by partner_code (same note as V009..V020).
    partner_id                BIGINT       NOT NULL,

    -- Commercial contract term, DATE-granular (signed paper carries dates,
    -- not instants). effective_to NULL = open-ended / evergreen.
    effective_from            DATE         NOT NULL,
    effective_to              DATE,

    -- Evergreen auto-renewal at effective_to; notice_period_days is how many
    -- days before effective_to either side must give notice to break it.
    auto_renewal              BOOLEAN      NOT NULL DEFAULT FALSE,
    notice_period_days        INT,

    -- Who eats refunds / chargebacks (PARTNER_SETUP_PLAN §Slice 6 roster).
    refund_chargeback_policy  VARCHAR(20),

    -- Why the contract was terminated; populated by the Slice 8 lifecycle
    -- flow, carried here so the contract row is self-describing in history.
    termination_reason        VARCHAR(200),

    -- Business time (ADR-010). Half-open [valid_from, valid_to); NULL valid_to
    -- = open-ended.
    valid_from                TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    valid_to                  TIMESTAMP,

    -- Transaction time (ADR-010). recorded_at defaulted as a safety net but
    -- set explicitly (MICROS-truncated) by the application so paired writes
    -- share one instant; superseded_at is NULL on current rows.
    recorded_at               TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    superseded_at             TIMESTAMP,

    CONSTRAINT pk_partner_contract PRIMARY KEY (id),

    CONSTRAINT fk_partner_contract_partner
        FOREIGN KEY (partner_id) REFERENCES partners (id),

    CONSTRAINT ck_partner_contract_policy CHECK (
        refund_chargeback_policy IN ('PARTNER_BEARS', 'MERCHANT_BEARS', 'SHARED')
    ),

    -- A contract may start and end the same day, never end before it starts.
    CONSTRAINT ck_partner_contract_term CHECK (
        effective_to IS NULL OR effective_to >= effective_from
    ),

    CONSTRAINT ck_partner_contract_notice CHECK (notice_period_days >= 0)
);

-- Hot-path lookup: "current contract for partner P" — same composite-index
-- choice as V009..V020.
CREATE INDEX idx_partner_contract_current
    ON partner_contract (partner_id, superseded_at);
