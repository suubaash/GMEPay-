-- V017: partner_rule child table — Slice 6 "Commercial Terms"
-- (docs/PARTNER_SETUP_PLAN.md §Slice 6).
--
-- WHY
-- ---
-- A Rule is the (partner × scheme × direction) join carrying the margins and
-- the flat service charge the rate engine prices with (lib-domain Rule).
-- Until now only POST /v1/rules/validate existed — rules were validated but
-- never persisted. This table gives them a bitemporal home so the wizard's
-- step-6 rule editor can save, and rate-fx can later read, the per-partner
-- rule set.
--
-- WHY BITEMPORAL (ADR-010)
-- ------------------------
-- A mis-stated margin is a pricing incident: the regulator asks "what did the
-- rule say when this transaction was priced?". Same SCD-6 column pairs as
-- partners (V004) / partner_settlement_config (V013) / partner_prefunding_config
-- (V015):
--
--   * valid_from  / valid_to       — business time (when the rule is true)
--   * recorded_at / superseded_at  — transaction time (when we wrote it; NULL
--                                     on rows that are still current)
--
-- Storage discipline: rows are NEVER UPDATEd in place. A wizard step-6 save is
-- a bulk replace — every current rule row of the partner is superseded
-- (superseded_at = now) and the new set inserted (recorded_at = now), both
-- halves sharing one MICROS-truncated instant (see RuleService).
--
-- INVARIANTS
-- ----------
-- The margin-floor invariant (cross-border m_a + m_b >= 2%, same-currency
-- margin == 0 — RATE-04 §11) is enforced at the service layer via the existing
-- lib-domain Rule.validate against the partner's V016 collection_ccy /
-- settle_a_ccy split; it cannot be a CHECK because it depends on the partner
-- row. The CHECKs below pin what the row alone can prove: the direction
-- roster and non-negative margins / service charge.
--
-- MONEY / MARGINS (docs/MONEY_CONVENTION.md)
-- ------------------------------------------
-- service_charge_usd is NUMERIC(19,4) major-USD-units money. m_a / m_b are
-- decimal FRACTIONS (0.0150 = 1.50%), NUMERIC(7,4) — same scale discipline,
-- BigDecimal in Java, decimal STRING on the wire.
--
-- COMPATIBILITY
-- -------------
-- Plain TIMESTAMP, not TIMESTAMPTZ (H2 PG-mode compat, same as V004..V015).
-- BIGSERIAL surrogate id, engine-managed (GenerationType.IDENTITY).
-- ADR-013 Expand discipline: wholly-new table; no in-place ALTERs of existing
-- tables.

CREATE TABLE partner_rule (
    -- BIGSERIAL surrogate, engine-managed — same id strategy as V012/V013/V015:
    -- rows are minted fresh on every SCD-6 write and nothing joins on their
    -- ids from outside this service.
    id                  BIGSERIAL     NOT NULL,

    -- FK to the partners surrogate (V003/V004 BIGINT PK). References the
    -- partner AGGREGATE via whichever row was current at write time; consumers
    -- resolve partners by partner_code (same note as V009..V015).
    partner_id          BIGINT        NOT NULL,

    -- Scheme this rule prices (e.g. ZEROPAY). Free-form business identifier
    -- until the scheme registry grows its own table in Slice 7.
    scheme_id           VARCHAR(40)   NOT NULL,

    -- Which transaction directions the rule applies to. BOTH covers
    -- INBOUND + OUTBOUND with one row.
    direction           VARCHAR(10)   NOT NULL,

    -- Partner-side and GME-side margins as decimal fractions (0.0150 = 1.50%).
    -- The cross-border floor m_a + m_b >= 0.02 is service-enforced via
    -- lib-domain Rule.validate (depends on the V016 currency split).
    m_a                 NUMERIC(7,4)  NOT NULL,
    m_b                 NUMERIC(7,4)  NOT NULL,

    -- Flat per-transaction service charge, major USD units (MONEY_CONVENTION).
    service_charge_usd  NUMERIC(19,4) NOT NULL DEFAULT 0,

    -- Business time (ADR-010). Half-open [valid_from, valid_to); NULL valid_to
    -- = open-ended.
    valid_from          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    valid_to            TIMESTAMP,

    -- Transaction time (ADR-010). recorded_at defaulted as a safety net but
    -- set explicitly (MICROS-truncated) by the application so paired writes
    -- share one instant; superseded_at is NULL on current rows.
    recorded_at         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    superseded_at       TIMESTAMP,

    -- Partial-unique emulation key: 'partner_id:scheme_id:direction' on the
    -- CURRENT row, NULL on superseded rows — see the index note below.
    -- Width: 20 (BIGINT digits) + 1 + 40 (scheme_id) + 1 + 10 (direction) = 72.
    current_rule_key    VARCHAR(80),

    CONSTRAINT pk_partner_rule PRIMARY KEY (id),

    CONSTRAINT fk_partner_rule_partner
        FOREIGN KEY (partner_id) REFERENCES partners (id),

    CONSTRAINT ck_partner_rule_direction CHECK (
        direction IN ('INBOUND', 'OUTBOUND', 'BOTH')
    ),

    CONSTRAINT ck_partner_rule_margins CHECK (
        m_a >= 0 AND m_b >= 0
    ),

    CONSTRAINT ck_partner_rule_service_charge CHECK (
        service_charge_usd >= 0
    )
);

-- Hot-path lookup: "current rule set for partner P" filters on
-- partner_id = ? AND superseded_at IS NULL — the composite serves it on both
-- engines without partial-index emulation (same choice as V009..V015).
CREATE INDEX idx_partner_rule_current
    ON partner_rule (partner_id, superseded_at);

-- Partial-unique enforcement: at most one CURRENT row per
-- (partner_id, scheme_id, direction), while allowing any number of historical
-- rows for the same key. PostgreSQL would express this as
-- `CREATE UNIQUE INDEX ... WHERE superseded_at IS NULL`, but H2 (the
-- @DataJpaTest engine) supports neither a WHERE clause on CREATE INDEX nor an
-- expression-based unique index. V004 emulated this with a stored GENERATED
-- column — but that spelling is NOT cross-engine either: PG requires the
-- STORED keyword and H2 (2.2/2.3, any mode) rejects it, which is why V004 now
-- lives as a per-vendor pair under db/vendor/{vendor}. To keep this table in
-- the engine-neutral chain, current_rule_key is a PLAIN column maintained by
-- the application instead: RuleService/RuleEntity stamp it with
-- 'partner_id:scheme_id:direction' on every INSERT and NULL it when the row
-- is superseded (both halves of the same SCD-6 transaction). Both engines
-- treat multiple NULLs in a UNIQUE index as distinct, so historical rows
-- never collide; current rows collide on the key value, giving the desired
-- "one current row per (partner, scheme, direction)" guarantee.
CREATE UNIQUE INDEX partner_rule_current ON partner_rule(current_rule_key);
