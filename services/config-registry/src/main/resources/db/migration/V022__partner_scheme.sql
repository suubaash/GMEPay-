-- V022: partner_scheme child table — Slice 7 "Scheme Enablement"
-- (docs/PARTNER_SETUP_PLAN.md §Slice 7).
--
-- WHY
-- ---
-- A partner_scheme row states WHICH payment scheme a partner is wired to and
-- HOW: the direction/role of the participation plus the scheme-side
-- identifiers needed to actually route (ZeroPay merchant ids, the KFTC
-- institution code, CPM/MPM approval methods). Until now scheme_id was a
-- free-form string on partner_rule (V017); this table is the Slice 7 scheme
-- registry those rows anticipated.
--
-- WHY BITEMPORAL (ADR-010)
-- ------------------------
-- A wrong merchant id or a scheme flipped off at the wrong moment is a routing
-- incident: the regulator asks "what was the scheme wiring when this
-- transaction routed?". Same SCD-6 column pairs as partners (V004) /
-- partner_prefunding_config (V015) / partner_rule (V017):
--
--   * valid_from  / valid_to       — business time (when the wiring is true)
--   * recorded_at / superseded_at  — transaction time (when we wrote it; NULL
--                                     on rows that are still current)
--
-- Storage discipline: rows are NEVER UPDATEd in place. A wizard step-7 save is
-- a bulk replace — every current partner_scheme row of the partner gets
-- superseded_at = now and the new set is INSERTed with recorded_at = now, both
-- halves sharing one MICROS-truncated instant (see PartnerSchemeService).
--
-- INVARIANTS
-- ----------
-- The ZEROPAY cross-field rule (an ENABLED ZEROPAY row needs
-- zeropay_merchant_id + kftc_institution_code) is SERVICE-enforced, not a DB
-- CHECK — drafts may be incomplete mid-wizard and the columns must stay
-- nullable. The CHECKs below pin only what the row alone can prove: the
-- scheme / direction / role / approval-method / partner-type rosters.
--
-- COMPATIBILITY
-- -------------
-- Plain TIMESTAMP, not TIMESTAMPTZ (H2 PG-mode compat, same as V004..V021).
-- Engine-managed identity (GenerationType.IDENTITY).
-- ADR-013 Expand discipline: wholly-new table; no in-place ALTERs of existing
-- tables.

CREATE TABLE partner_scheme (
    -- Engine-managed identity — same id strategy as V012/V015/V017: rows are
    -- minted fresh on every SCD-6 write and nothing joins on their ids from
    -- outside this service.
    id                       BIGINT GENERATED ALWAYS AS IDENTITY,

    -- FK to the partners surrogate (V003/V004 BIGINT PK). References the
    -- partner AGGREGATE via whichever row was current at write time; consumers
    -- resolve partners by partner_code (same note as V009..V017).
    partner_id               BIGINT      NOT NULL,

    -- The scheme being enabled. Closed roster (the Slice 7 scheme registry);
    -- partner_rule.scheme_id (V017) stays free-form until a later Contract
    -- migration reconciles it against this table.
    scheme_id                VARCHAR(20) NOT NULL,

    -- Which transaction directions the participation covers. BOTH covers
    -- INBOUND + OUTBOUND with one row (same roster as V017).
    direction                VARCHAR(10) NOT NULL,

    -- The partner's role on the scheme: ACQUIRER (merchant side), ISSUER
    -- (payer side) or BOTH.
    role                     VARCHAR(10) NOT NULL,

    -- ZeroPay-side routing identifiers. Nullable: only meaningful for
    -- ZEROPAY rows, and a draft may not have them yet (service-enforced on
    -- enable — see INVARIANTS above).
    zeropay_merchant_id      VARCHAR(40),
    zeropay_sub_merchant_id  VARCHAR(40),

    -- KFTC institution code for KR rails (ZeroPay settlement runs through
    -- KFTC). Nullable for the same draft reason.
    kftc_institution_code    VARCHAR(20),

    -- Scheme-side partner classification: 'D' (direct) or 'I' (indirect).
    partner_type_char        CHAR(1),

    -- Opaque handle to the scheme API credential in the vault (ADR-006: the
    -- secret bytes never live in this DB, only the locator).
    vault_secret_id          VARCHAR(64),

    -- Approval method per presentation mode: CPM (customer-presented) and MPM
    -- (merchant-presented). CONFIRMATION requires an explicit user approval
    -- round-trip; SILENT auto-approves within scheme limits.
    approval_method_cpm      VARCHAR(20),
    approval_method_mpm      VARCHAR(20),

    -- Kill switch: a disabled row keeps its wiring but routes nothing.
    enabled                  BOOLEAN     NOT NULL DEFAULT TRUE,

    -- Business time (ADR-010). Half-open [valid_from, valid_to); NULL valid_to
    -- = open-ended.
    valid_from               TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    valid_to                 TIMESTAMP,

    -- Transaction time (ADR-010). recorded_at defaulted as a safety net but
    -- set explicitly (MICROS-truncated) by the application so paired writes
    -- share one instant; superseded_at is NULL on current rows.
    recorded_at              TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    superseded_at            TIMESTAMP,

    -- Partial-unique emulation key: 'partner_id:scheme_id' on the CURRENT
    -- row, NULL on superseded rows — see the index note below.
    -- Width: 20 (BIGINT digits) + 1 + 20 (scheme_id) = 41.
    current_scheme_key       VARCHAR(50),

    CONSTRAINT pk_partner_scheme PRIMARY KEY (id),

    CONSTRAINT fk_partner_scheme_partner
        FOREIGN KEY (partner_id) REFERENCES partners (id),

    CONSTRAINT ck_partner_scheme_scheme CHECK (
        scheme_id IN ('ZEROPAY', 'BAKONG', 'NAPAS_247', 'PROMPT_PAY',
                      'FAST_SG', 'QRIS', 'KHQR')
    ),

    CONSTRAINT ck_partner_scheme_direction CHECK (
        direction IN ('INBOUND', 'OUTBOUND', 'BOTH')
    ),

    CONSTRAINT ck_partner_scheme_role CHECK (
        role IN ('ACQUIRER', 'ISSUER', 'BOTH')
    ),

    CONSTRAINT ck_partner_scheme_type_char CHECK (
        partner_type_char IN ('D', 'I')
    ),

    CONSTRAINT ck_partner_scheme_approval_cpm CHECK (
        approval_method_cpm IN ('CONFIRMATION', 'SILENT')
    ),

    CONSTRAINT ck_partner_scheme_approval_mpm CHECK (
        approval_method_mpm IN ('CONFIRMATION', 'SILENT')
    )
);

-- Hot-path lookup: "current scheme set for partner P" filters on
-- partner_id = ? AND superseded_at IS NULL — the composite serves it on both
-- engines without partial-index emulation (same choice as V009..V017).
CREATE INDEX idx_partner_scheme_current
    ON partner_scheme (partner_id, superseded_at);

-- Partial-unique enforcement: at most one CURRENT row per
-- (partner_id, scheme_id), while allowing any number of historical rows for
-- the same key. PostgreSQL would express this as
-- `CREATE UNIQUE INDEX ... WHERE superseded_at IS NULL` (or a STORED generated
-- is-current column per V004), but neither spelling is cross-engine: H2 (the
-- @DataJpaTest engine) supports no WHERE clause on CREATE INDEX and rejects
-- the STORED keyword PG 14+ REQUIRES on GENERATED ALWAYS AS columns — which is
-- why V004 lives as a per-vendor pair under db/vendor/{vendor}. To keep this
-- table in the engine-neutral chain, current_scheme_key is a PLAIN column
-- maintained by the application instead (the V017 partner_rule pattern):
-- PartnerSchemeEntity stamps it with 'partner_id:scheme_id' on every INSERT
-- and NULLs it when the row is superseded (both halves of the same SCD-6
-- transaction). Both engines treat multiple NULLs in a UNIQUE index as
-- distinct, so historical rows never collide; current rows collide on the key
-- value, giving the desired "one current row per (partner, scheme)" guarantee.
CREATE UNIQUE INDEX partner_scheme_current ON partner_scheme(current_scheme_key);
