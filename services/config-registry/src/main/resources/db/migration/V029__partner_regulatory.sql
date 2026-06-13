-- V029: partner_regulatory_config child table — Slice 8 Lane C
-- "Regulatory attributes" (docs/PARTNER_SETUP_PLAN.md §Slice 8).
--
-- WHY
-- ---
-- Activation (LIVE) is a regulatory event: from the first live transaction GME
-- must be able to (a) file BOK 외환거래보고 foreign-exchange reports, (b) issue
-- Hometax e-tax-invoices for its fees, (c) feed KoFIU CTR/STR, (d) document
-- the PIPA cross-border PII jurisdictions, and (e) run Travel-Rule
-- (TRP/Sygna/IVMS101) exchange on transfers >= KRW 1,000,000. This table is
-- where the wizard's step-8 regulatory panel captures that metadata, one
-- CURRENT row per partner. Lane A's ActivationGateService treats "a current
-- partner_regulatory_config row exists" as a hard pre-condition for LIVE.
--
-- WHY BITEMPORAL (ADR-010)
-- ------------------------
-- "What CTR threshold / travel-rule routing was configured when transaction T
-- ran?" is a KoFIU / BOK examiner question — the regulatory configuration that
-- answered it must be reconstructable. Same SCD-6 column pairs as V004..V023:
--
--   * valid_from  / valid_to       — business time (when the regulatory fact is true)
--   * recorded_at / superseded_at  — transaction time (when we wrote it; NULL
--                                     on rows that are still current)
--
-- plus the change-provenance pair this slice introduces for regulatory rows:
--
--   * changed_by         — the operator (X-Actor) who wrote this row version
--   * change_request_id  — the V005 change_request that authorised a
--                          post-activation change; NULL for direct ONBOARDING
--                          writes (the Slice 8 FSM wires this up)
--
-- Storage discipline: rows are NEVER UPDATEd in place. A step-8 save is a
-- full-state replace — the current row (if any) is superseded
-- (superseded_at = now) and a fresh row inserted (recorded_at = now), both
-- halves sharing one MICROS-truncated instant (see
-- PartnerRegulatoryConfigService).
--
-- ONE CURRENT ROW PER PARTNER (V017 pattern, NOT V023)
-- ----------------------------------------------------
-- PostgreSQL would express this as `CREATE UNIQUE INDEX ... WHERE
-- superseded_at IS NULL`, but H2 (the @DataJpaTest engine) supports neither a
-- WHERE clause on CREATE INDEX nor expression-based unique indexes, and the
-- stored GENERATED spelling has no cross-engine form (PG requires STORED, H2
-- rejects it — the V004/V023 vendor split). To keep this table in the
-- engine-neutral chain we use the V017 APPLICATION-maintained emulation:
-- current_partner_key carries the partner_id on the CURRENT row and NULL once
-- superseded (PartnerRegulatoryConfigEntity stamps it on INSERT and clears it
-- on the supersede UPDATE), and a plain UNIQUE index over it enforces "one
-- current row per partner" on both engines (NULLs are distinct under UNIQUE).
--
-- MONEY (docs/MONEY_CONVENTION.md)
-- --------------------------------
-- ctr_threshold_krw / travel_rule_threshold_krw are NUMERIC(18,2) major-KRW
-- amounts — BigDecimal in Java, decimal STRING on the wire, never float.
--
-- COMPATIBILITY
-- -------------
-- Plain TIMESTAMP, not TIMESTAMPTZ (H2 PG-mode compat, same as V004..V024).
-- TEXT (not JSONB/BYTEA) for the CSV allowlist — the service layer parses and
-- validates the ISO-3166 codes. ADR-013 Expand discipline: wholly-new table.

CREATE TABLE partner_regulatory_config (
    -- Identity surrogate, engine-managed (GenerationType.IDENTITY): rows are
    -- minted fresh on every SCD-6 write and nothing outside this service
    -- joins on their ids.
    id                          BIGINT GENERATED ALWAYS AS IDENTITY,

    -- FK to the partners surrogate (V003/V004 BIGINT PK); consumers resolve
    -- partners by partner_code (same note as V009..V023).
    partner_id                  BIGINT        NOT NULL,

    -- ---------------- BOK 외환거래보고 (foreign-exchange reporting) ----------
    -- BOK external-trade transaction code. TODO(OI-03): confirm the official
    -- BOK external-trade-code shape against the OI-03 reference; until then
    -- the service enforces the placeholder regex ^\d{3}$ (3 digits).
    bok_txn_code                VARCHAR(10),

    -- How this partner's flows aggregate into the BOK FX report.
    bok_fx_reporting_category   VARCHAR(40),

    -- Remitter classification on the BOK filing.
    bok_remitter_type           VARCHAR(40),

    -- ---------------- Hometax e-tax-invoice --------------------------------
    -- lib-vault document id of the issuer certificate (공인인증서) used to sign
    -- e-tax-invoices. Loose reference — the vault is a separate store, so the
    -- service validates presence/shape, not referential integrity.
    hometax_issuer_cert_id      VARCHAR(64),

    -- VAT treatment of GME's fee invoices to this partner.
    vat_treatment               VARCHAR(40),

    -- ---------------- KoFIU CTR / STR ---------------------------------------
    -- KoFIU-assigned reporting-entity identifier for the CTR/STR feed.
    kofiu_entity_id             VARCHAR(40),

    -- Currency Transaction Report threshold in major KRW; statutory default
    -- KRW 10,000,000. Service-enforced > 0 in addition to the CHECK below.
    ctr_threshold_krw           NUMERIC(18,2) NOT NULL DEFAULT 10000000,

    -- ---------------- PIPA cross-border PII --------------------------------
    -- CSV of ISO-3166 alpha-2 codes the partner may receive PII in (e.g.
    -- 'MN,VN,KH'). TEXT on purpose: the SERVICE layer parses + validates each
    -- element; the DB stores the documented allowlist verbatim.
    pipa_jurisdiction_allowlist TEXT,

    -- PIPA legal basis for the cross-border transfer.
    legal_basis_code            VARCHAR(40),

    -- ---------------- Travel Rule -------------------------------------------
    -- Protocol used to exchange originator/beneficiary data on transfers at or
    -- above travel_rule_threshold_krw. NONE = partner is out of scope.
    travel_rule_protocol        VARCHAR(40),

    -- Counterparty VASP endpoint for the chosen protocol; service-enforced
    -- REQUIRED whenever travel_rule_protocol is present and != NONE.
    travel_rule_endpoint_url    VARCHAR(500),

    -- Statutory Travel-Rule threshold, major KRW; default KRW 1,000,000.
    travel_rule_threshold_krw   NUMERIC(18,2) DEFAULT 1000000,

    -- ---------------- Bitemporal (ADR-010) ----------------------------------
    -- Business time. Half-open [valid_from, valid_to); NULL valid_to = open-ended.
    valid_from                  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    valid_to                    TIMESTAMP,

    -- Transaction time. recorded_at defaulted as a safety net but set
    -- explicitly (MICROS-truncated) by the application so paired writes share
    -- one instant; superseded_at is NULL on current rows.
    recorded_at                 TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    superseded_at               TIMESTAMP,

    -- Change provenance: who wrote this row version, and (post-activation)
    -- which V005 change_request authorised it.
    changed_by                  VARCHAR(120),
    change_request_id           BIGINT,

    -- Partial-unique emulation key (V017 pattern): the partner_id while this
    -- row is current, NULL once superseded. APPLICATION-maintained by
    -- PartnerRegulatoryConfigEntity's lifecycle hooks — see header note.
    current_partner_key         BIGINT,

    CONSTRAINT pk_partner_regulatory_config PRIMARY KEY (id),

    CONSTRAINT fk_partner_regulatory_partner
        FOREIGN KEY (partner_id) REFERENCES partners (id),

    CONSTRAINT ck_partner_regulatory_bok_category CHECK (
        bok_fx_reporting_category IN ('INDIVIDUAL_AGGREGATE', 'INSTITUTIONAL')
    ),

    CONSTRAINT ck_partner_regulatory_bok_remitter CHECK (
        bok_remitter_type IN
            ('INDIVIDUAL', 'CORPORATION', 'GOVERNMENT', 'FINANCIAL_INSTITUTION')
    ),

    CONSTRAINT ck_partner_regulatory_vat CHECK (
        vat_treatment IN ('ZERO_RATED_EXPORT', 'STANDARD', 'EXEMPT')
    ),

    CONSTRAINT ck_partner_regulatory_legal_basis CHECK (
        legal_basis_code IN ('CONSENT', 'CONTRACT', 'LEGAL_OBLIGATION',
                             'VITAL_INTEREST', 'PUBLIC_TASK', 'LEGITIMATE_INTEREST')
    ),

    CONSTRAINT ck_partner_regulatory_travel_protocol CHECK (
        travel_rule_protocol IN ('TRP', 'SYGNA', 'IVMS101', 'NONE')
    ),

    CONSTRAINT ck_partner_regulatory_ctr_threshold CHECK (
        ctr_threshold_krw > 0
    ),

    CONSTRAINT ck_partner_regulatory_travel_threshold CHECK (
        travel_rule_threshold_krw > 0
    )
);

-- Hot-path lookup: "current regulatory config for partner P" filters on
-- partner_id = ? AND superseded_at IS NULL — the composite serves it on both
-- engines (same choice as V009..V023). Lane A's activation gate runs the same
-- predicate through PartnerRegulatoryConfigRepository.existsCurrentByPartnerId.
CREATE INDEX idx_partner_regulatory_current
    ON partner_regulatory_config (partner_id, superseded_at);

-- Partial-unique enforcement: at most one CURRENT row per partner — see the
-- header note for why this is the app-maintained V017 emulation and not a
-- stored GENERATED column (no cross-engine spelling without a vendor split).
CREATE UNIQUE INDEX partner_regulatory_config_current
    ON partner_regulatory_config (current_partner_key);
