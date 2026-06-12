-- V020: partner_limits child table — Slice 6 "Commercial Terms"
-- (docs/PARTNER_SETUP_PLAN.md §Slice 6).
--
-- WHY BITEMPORAL (ADR-010)
-- ------------------------
-- Per-transaction and aggregate caps are REGULATORY limits — for a Korean
-- 소액해외송금업 (small-amount overseas remittance) licensee the per-txn and
-- annual-per-customer ceilings are statutory, and "what cap was configured
-- when this transaction was accepted?" is precisely what the FSS asks after a
-- breach. Same SCD-6 column pairs as V004..V019:
--
--   * valid_from  / valid_to       — business time (when the limits are true)
--   * recorded_at / superseded_at  — transaction time (when we wrote it; NULL
--                                     on rows that are still current)
--
-- Storage discipline: rows are NEVER UPDATEd in place. A wizard step-6 save is
-- a paired write — supersede + insert sharing one MICROS-truncated instant
-- (see LimitsService).
--
-- 소액해외송금업 HARD CAPS
-- ------------------------
-- license_type names the regulatory regime the partner operates under.
-- 'SOAEK_HAEOEMONG' (소액해외송금업, the Korean small-amount overseas
-- remittance licence) hard-caps per_txn_max_usd at 5,000 USD and
-- annual_cap_usd at 50,000 USD — enforced BOTH here (defence in depth: no
-- write path, including manual SQL, may exceed the statute) and in
-- LimitsService (which additionally REQUIRES both caps to be present for that
-- licence; the DB CHECK alone passes on NULL per SQL three-valued logic).
--
-- MONEY (docs/MONEY_CONVENTION.md)
-- --------------------------------
-- All *_usd columns are NUMERIC(19,4) in major units, BigDecimal in Java,
-- decimal STRING on the wire. NULL = that cap is not configured.
--
-- ONE CURRENT ROW PER PARTNER
-- ---------------------------
-- Same one-current-row-per-partner service discipline and composite-index
-- choice as V013/V015/V019.
--
-- COMPATIBILITY
-- -------------
-- Plain TIMESTAMP, not TIMESTAMPTZ (H2 PG-mode compat, same as V004..V019).
-- BIGSERIAL surrogate id, engine-managed (GenerationType.IDENTITY).
--
-- ADR-013 Expand discipline: wholly-new table; no in-place ALTERs.

CREATE TABLE partner_limits (
    -- BIGSERIAL surrogate, engine-managed — same id strategy as V015/V018/V019.
    id               BIGSERIAL     NOT NULL,

    -- FK to the partners surrogate (V003/V004 BIGINT PK); consumers resolve
    -- partners by partner_code (same note as V009..V019).
    partner_id       BIGINT        NOT NULL,

    -- Per-transaction floor/ceiling, major USD units. NULL = unconstrained.
    per_txn_min_usd  NUMERIC(19,4),
    per_txn_max_usd  NUMERIC(19,4),

    -- Rolling aggregate caps, major USD units. NULL = unconstrained.
    daily_cap_usd    NUMERIC(19,4),
    monthly_cap_usd  NUMERIC(19,4),
    annual_cap_usd   NUMERIC(19,4),

    -- Regulatory regime discriminator (e.g. 'SOAEK_HAEOEMONG' for the Korean
    -- 소액해외송금업 licence). Free-form VARCHAR — the roster grows per
    -- jurisdiction; only SOAEK_HAEOEMONG carries DB-enforced caps today.
    license_type     VARCHAR(30),

    -- Business time (ADR-010). Half-open [valid_from, valid_to); NULL valid_to
    -- = open-ended.
    valid_from       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    valid_to         TIMESTAMP,

    -- Transaction time (ADR-010). recorded_at defaulted as a safety net but
    -- set explicitly (MICROS-truncated) by the application so paired writes
    -- share one instant; superseded_at is NULL on current rows.
    recorded_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    superseded_at    TIMESTAMP,

    CONSTRAINT pk_partner_limits PRIMARY KEY (id),

    CONSTRAINT fk_partner_limits_partner
        FOREIGN KEY (partner_id) REFERENCES partners (id),

    -- No negative caps (NULL passes — not configured).
    CONSTRAINT ck_partner_limits_non_negative CHECK (
        per_txn_min_usd >= 0 AND per_txn_max_usd >= 0 AND daily_cap_usd >= 0
            AND monthly_cap_usd >= 0 AND annual_cap_usd >= 0
    ),

    -- 소액해외송금업 statutory ceilings (see header). NULL caps pass this
    -- CHECK by three-valued logic — LimitsService additionally requires both
    -- to be PRESENT for this licence type.
    CONSTRAINT ck_partner_limits_soaek CHECK (
        license_type <> 'SOAEK_HAEOEMONG'
            OR (per_txn_max_usd <= 5000 AND annual_cap_usd <= 50000)
    )
);

-- Hot-path lookup: "current limits for partner P" — same composite-index
-- choice as V009..V019.
CREATE INDEX idx_partner_limits_current
    ON partner_limits (partner_id, superseded_at);
