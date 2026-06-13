-- V015: partner_prefunding_config child table — Slice 5 "Prefunding"
-- (docs/PARTNER_SETUP_PLAN.md §Slice 5).
--
-- WHY BITEMPORAL (ADR-010)
-- ------------------------
-- The funding model / thresholds / credit limit decide whether a partner's
-- transactions are allowed to flow at all — a mis-stated threshold is a credit
-- incident and the regulator asks "what did the config say when the breach
-- happened?". Same SCD-6 column pairs as partners (V004), partner_contact
-- (V009), partner_kyb (V011), partner_settlement_config (V013):
--
--   * valid_from  / valid_to       — business time (when the config is true)
--   * recorded_at / superseded_at  — transaction time (when we wrote it; NULL
--                                     on rows that are still current)
--
-- Storage discipline: rows are NEVER UPDATEd in place. A wizard step-5 save is
-- a paired write — (UPDATE current row SET superseded_at = now()) + (INSERT
-- fresh row with recorded_at = now()), both halves sharing one MICROS-truncated
-- instant (see PrefundingConfigService).
--
-- MONEY (docs/MONEY_CONVENTION.md)
-- --------------------------------
-- All *_usd columns are NUMERIC(19,4) in major units, BigDecimal in Java,
-- decimal STRING on the wire. The service normalises to scale 4 before
-- persisting so stored == in-memory on both engines.
--
-- ONE CURRENT ROW PER PARTNER
-- ---------------------------
-- One prefunding config is current per partner at a time, but (as with V009 /
-- V011 / V013) we do not partial-index-enforce it — the service serialises
-- writes per partner in one transaction, and the composite
-- (partner_id, superseded_at) index serves the hot
-- `WHERE partner_id = ? AND superseded_at IS NULL` lookup on both engines.
--
-- COMPATIBILITY
-- -------------
-- Plain TIMESTAMP, not TIMESTAMPTZ (H2 PG-mode compat, same as V004..V014).
-- BIGSERIAL surrogate id, engine-managed (GenerationType.IDENTITY).
--
-- ADR-013 Expand discipline: wholly-new table; no in-place ALTERs.

CREATE TABLE partner_prefunding_config (
    -- BIGSERIAL surrogate, engine-managed — same id strategy as V011/V013:
    -- rows are minted fresh on every SCD-6 write and nothing joins on their
    -- ids from outside this service.
    id                            BIGSERIAL     NOT NULL,

    -- FK to the partners surrogate (V003/V004 BIGINT PK). References the
    -- partner AGGREGATE via whichever row was current at write time; consumers
    -- resolve partners by partner_code (same note as V009/V011/V013).
    partner_id                    BIGINT        NOT NULL,

    -- How the partner funds payouts (PARTNER_SETUP_PLAN §Slice 5): PREFUNDED
    -- (float held with GME), POSTPAID (settled in arrears against a credit
    -- limit) or HYBRID (float first, credit line as overflow).
    funding_model                 VARCHAR(10)   NOT NULL,

    -- Initial float the partner wires before go-live; NULL until treasury
    -- confirms the figure. Major USD units (MONEY_CONVENTION).
    opening_balance_usd           NUMERIC(19,4),

    -- Balance level that arms the low-balance alerting (the 70/85/95 tiers
    -- are percentages OF this threshold's headroom). Must be positive —
    -- enforced both here and in PrefundingConfigService.
    low_balance_threshold_usd     NUMERIC(19,4) NOT NULL DEFAULT 10000,

    -- Which alert tiers are armed (consumption of the threshold). Defaults ON:
    -- a partner that opts out of an alert tier does so explicitly.
    alert_tier_70                 BOOLEAN       NOT NULL DEFAULT TRUE,
    alert_tier_85                 BOOLEAN       NOT NULL DEFAULT TRUE,
    alert_tier_95                 BOOLEAN       NOT NULL DEFAULT TRUE,

    -- Credit line for POSTPAID/HYBRID models. NULL = no limit configured.
    credit_limit_usd              NUMERIC(19,4),

    -- When TRUE a balance breach proposes a change_request
    -- (aggregate=partner, payload=status:SUSPENDED, proposed_by='system' —
    -- the ADR-008 carve-out); operator approval is required to lift.
    auto_suspend_on_breach        BOOLEAN       NOT NULL DEFAULT TRUE,

    -- Loose reference to the partner_bank_account row (V012) the partner tops
    -- their float up from — must point at a CURRENT row of THIS partner with
    -- purpose = FLOAT_TOPUP, validated by PrefundingConfigService at write
    -- time. No FK constraint: under SCD-6 the referenced row id is superseded
    -- on every bank-account bulk replace (same loose-reference choice as
    -- V012's verification_evidence_doc_id).
    float_top_up_bank_account_id  BIGINT,

    -- Wire-reference template the partner must quote on top-up transfers so
    -- the incoming wire auto-reconciles to their ledger. {partner_code} is
    -- mandatory (service-validated); {yyyyMMdd} stamps the transfer date.
    top_up_reference_pattern      VARCHAR(60)   DEFAULT 'GMP-{partner_code}-{yyyyMMdd}',

    -- Optional collateral the partner posts against a POSTPAID credit line.
    collateral_amount_usd         NUMERIC(19,4),

    -- Business time (ADR-010). Half-open [valid_from, valid_to); NULL valid_to
    -- = open-ended.
    valid_from                    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    valid_to                      TIMESTAMP,

    -- Transaction time (ADR-010). recorded_at defaulted as a safety net but
    -- set explicitly (MICROS-truncated) by the application so paired writes
    -- share one instant; superseded_at is NULL on current rows.
    recorded_at                   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    superseded_at                 TIMESTAMP,

    CONSTRAINT pk_partner_prefunding_config PRIMARY KEY (id),

    CONSTRAINT fk_partner_prefunding_config_partner
        FOREIGN KEY (partner_id) REFERENCES partners (id),

    CONSTRAINT ck_partner_prefunding_config_model CHECK (
        funding_model IN ('PREFUNDED', 'POSTPAID', 'HYBRID')
    ),

    CONSTRAINT ck_partner_prefunding_config_threshold CHECK (
        low_balance_threshold_usd > 0
    )
);

-- Hot-path lookup: "current prefunding config for partner P" filters on
-- partner_id = ? AND superseded_at IS NULL — the composite serves it on both
-- engines without partial-index emulation (same choice as V009/V011/V013).
CREATE INDEX idx_partner_prefunding_config_current
    ON partner_prefunding_config (partner_id, superseded_at);
