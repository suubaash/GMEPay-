-- V018: partner_fee_schedule child table — Slice 6 "Commercial Terms"
-- (docs/PARTNER_SETUP_PLAN.md §Slice 6).
--
-- WHY BITEMPORAL (ADR-010)
-- ------------------------
-- The fee schedule prices every transaction the partner sends — a mis-stated
-- bps fee is a revenue (or partner-relations) incident, and the regulator /
-- auditor asks "what did the fee say when this transaction was priced?".
-- Same SCD-6 column pairs as partners (V004), partner_contact (V009),
-- partner_kyb (V011), partner_settlement_config (V013),
-- partner_prefunding_config (V015):
--
--   * valid_from  / valid_to       — business time (when the fee is true)
--   * recorded_at / superseded_at  — transaction time (when we wrote it; NULL
--                                     on rows that are still current)
--
-- Storage discipline: rows are NEVER UPDATEd in place. A wizard step-6 save is
-- a BULK REPLACE — every current row of the partner is superseded and the new
-- set inserted, both halves sharing one MICROS-truncated instant (the V012
-- partner_bank_account discipline; see FeeScheduleService).
--
-- KEY SHAPE — per (partner × scheme × direction)
-- ----------------------------------------------
-- One row per (partner_id, scheme_id, direction) combination. scheme_id and
-- direction are NULLABLE: NULL means "applies to all" (a partner-wide default
-- row); a row with both populated is the most specific match. The service
-- rejects duplicate (scheme_id, direction) pairs within one save; resolution
-- precedence (specific over wildcard) is the pricing engine's concern.
--
-- MONEY (docs/MONEY_CONVENTION.md)
-- --------------------------------
-- fixed_fee_usd is NUMERIC(19,4) major USD units. bps_fee is NUMERIC(7,4) —
-- basis points with 4 decimal places (max 999.9999 bps = 10%, far above any
-- sane fee). BigDecimal in Java, decimal STRING on the wire.
--
-- TIER TABLE
-- ----------
-- tier_table_json is TEXT carrying a canonical JSON array of
-- {fromVolumeUsd, bpsOverride} objects ordered by ascending fromVolumeUsd:
-- "from this rolling volume upward, this bps applies instead of bps_fee".
-- TEXT (not JSONB) for the same H2-compat reason as V011's ubo_set_jsonb; the
-- service writes it canonically (FeeTierTableJson) so audit bytes stay
-- deterministic. NULL = flat pricing, no tiers.
--
-- COMPATIBILITY
-- -------------
-- Plain TIMESTAMP, not TIMESTAMPTZ (H2 PG-mode compat, same as V004..V015).
-- BIGSERIAL surrogate id, engine-managed (GenerationType.IDENTITY).
--
-- ADR-013 Expand discipline: wholly-new table; no in-place ALTERs.

CREATE TABLE partner_fee_schedule (
    -- BIGSERIAL surrogate, engine-managed — same id strategy as V012/V015:
    -- rows are minted fresh on every SCD-6 bulk replace and nothing joins on
    -- their ids from outside this service.
    id                 BIGSERIAL     NOT NULL,

    -- FK to the partners surrogate (V003/V004 BIGINT PK). References the
    -- partner AGGREGATE via whichever row was current at write time; consumers
    -- resolve partners by partner_code (same note as V009..V015).
    partner_id         BIGINT        NOT NULL,

    -- Scheme this row prices (e.g. 'zeropay_kr'); NULL = all schemes.
    scheme_id          VARCHAR(40),

    -- Direction this row prices — the same CHECK roster as the V017 rule
    -- rows (BOTH covers INBOUND + OUTBOUND); NULL = all directions.
    direction          VARCHAR(10),

    -- Flat per-transaction fee, major USD units (MONEY_CONVENTION).
    fixed_fee_usd      NUMERIC(19,4) NOT NULL DEFAULT 0,

    -- Variable fee in basis points of the transaction amount.
    bps_fee            NUMERIC(7,4)  NOT NULL DEFAULT 0,

    -- Canonical JSON array of {fromVolumeUsd, bpsOverride} volume bands,
    -- ascending by fromVolumeUsd (see header). NULL = no tiers.
    tier_table_json    TEXT,

    -- Business time (ADR-010). Half-open [valid_from, valid_to); NULL valid_to
    -- = open-ended.
    valid_from         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    valid_to           TIMESTAMP,

    -- Transaction time (ADR-010). recorded_at defaulted as a safety net but
    -- set explicitly (MICROS-truncated) by the application so paired writes
    -- share one instant; superseded_at is NULL on current rows.
    recorded_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    superseded_at      TIMESTAMP,

    CONSTRAINT pk_partner_fee_schedule PRIMARY KEY (id),

    CONSTRAINT fk_partner_fee_schedule_partner
        FOREIGN KEY (partner_id) REFERENCES partners (id),

    -- Same roster as the V017 rule rows (ck_partner_rule_direction).
    CONSTRAINT ck_partner_fee_schedule_direction CHECK (
        direction IN ('INBOUND', 'OUTBOUND', 'BOTH')
    ),

    CONSTRAINT ck_partner_fee_schedule_fixed_fee CHECK (fixed_fee_usd >= 0),

    CONSTRAINT ck_partner_fee_schedule_bps_fee CHECK (bps_fee >= 0)
);

-- Hot-path lookup: "current fee schedule for partner P" filters on
-- partner_id = ? AND superseded_at IS NULL — the composite serves it on both
-- engines without partial-index emulation (same choice as V009..V015).
CREATE INDEX idx_partner_fee_schedule_current
    ON partner_fee_schedule (partner_id, superseded_at);
