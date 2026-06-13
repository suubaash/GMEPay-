-- V011: partner_kyb child table — Slice 3 "KYB" (docs/PARTNER_SETUP_PLAN.md §Slice 3).
--
-- WHY BITEMPORAL (ADR-010)
-- ------------------------
-- The KYB sub-resource is THE regulated record: risk rating + rationale drive
-- EDD depth (FATF R.10), the UBO set is what FATF R.24 transparency reviews
-- inspect, and the screening verdict is the evidence trail for KoFIU/FSS daily
-- rescreening obligations. Same SCD-6 column pairs as partners (V004) and
-- partner_contact (V009):
--
--   * valid_from  / valid_to       — business time (when the KYB facts are true)
--   * recorded_at / superseded_at  — transaction time (when we wrote it; NULL on
--                                     rows that are still current)
--
-- Storage discipline: rows are NEVER UPDATEd in place. Wizard step-3 save and
-- each screening run are paired writes — (UPDATE current row SET superseded_at
-- = now()) + (INSERT fresh row with recorded_at = now()), both halves sharing
-- one MICROS-truncated instant (see KybService). The exit-gate tamper check
-- ("UPDATE partner_kyb.risk_rating via psql → audit_log hash chain detects the
-- drift") relies on this: a direct UPDATE leaves the audit chain's AFTER bytes
-- disagreeing with the row.
--
-- UBO STORAGE
-- -----------
-- ubo_set_jsonb is TEXT carrying a canonical JSON array of
-- {name, ownershipPct, isPep, country} objects. TEXT (not JSONB) because the
-- @DataJpaTest slices run on H2 in PostgreSQL mode, which has no JSONB type;
-- PostgreSQL stores/returns the same text happily. Slice 3 never queries inside
-- the document (the set is read/written whole with its row), so nothing is
-- lost; a later migration can ALTER to JSONB + GIN if UBO-search lands
-- (ADR-013 Expand discipline keeps that as an additive follow-up).
--
-- INDEXING / COMPATIBILITY
-- ------------------------
-- One KYB row is current per partner at a time, but (as with contacts) we do
-- not partial-index-enforce it — the service serialises writes per partner in
-- one transaction, and the composite (partner_id, superseded_at) index serves
-- the hot `WHERE partner_id = ? AND superseded_at IS NULL` lookup on both
-- engines (same rationale as V009). Plain TIMESTAMP, not TIMESTAMPTZ (H2
-- PG-mode compat, same as V004/V006/V009). BIGSERIAL surrogate id,
-- engine-managed (GenerationType.IDENTITY on the entity).
--
-- ADR-013 Expand discipline: wholly-new table; no in-place ALTERs.

CREATE TABLE partner_kyb (
    -- BIGSERIAL surrogate, engine-managed — same id strategy as partner_contact
    -- (V009): KYB rows are minted fresh on every SCD-6 write and nothing joins
    -- on their ids from outside this service.
    id                     BIGSERIAL     NOT NULL,

    -- FK to the partners surrogate (V003/V004 BIGINT PK). References the
    -- partner AGGREGATE via whichever row was current at write time; consumers
    -- resolve partners by partner_code (same note as V009).
    partner_id             BIGINT        NOT NULL,

    -- Operator-assigned risk rating (FATF R.10 risk-based approach). NULL while
    -- the draft is incomplete; the Slice 8 activation gate requires it.
    risk_rating            VARCHAR(10),

    -- Why that rating — the regulator reads this verbatim at inspection.
    risk_rationale         VARCHAR(1000),

    -- When the periodic CDD review falls due (drives the review work queue).
    next_review_date       DATE,

    -- The partner's remittance / PSP license.
    license_type           VARCHAR(50),
    license_number         VARCHAR(50),
    license_authority      VARCHAR(100),
    license_expiry         DATE,

    -- Canonical JSON array of declared UBOs: [{name, ownershipPct, isPep,
    -- country}, ...]. TEXT for H2 compat — see header. NULL = not captured yet;
    -- '[]' = operator explicitly declared "no UBOs above threshold".
    ubo_set_jsonb          TEXT,

    -- Wolfsberg CBDDQ document in partner_document (ADR-006 vault). No FK
    -- constraint yet: partner_document lands in a sibling Slice 3 migration and
    -- ADR-013 forbids coupling this Expand to its ordering; the service layer
    -- guards the reference until a follow-up adds the constraint.
    cbddq_doc_id           BIGINT,

    -- Latest sanctions screening verdict (ADR-009), stored on the row by the
    -- screening run and carried forward across step-3 saves. NULL before the
    -- first run.
    screening_status       VARCHAR(15),
    screening_provider_ref VARCHAR(100),
    screened_at            TIMESTAMP,

    -- Business time (ADR-010). Half-open [valid_from, valid_to); NULL valid_to
    -- = open-ended.
    valid_from             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    valid_to               TIMESTAMP,

    -- Transaction time (ADR-010). recorded_at defaulted as a safety net but set
    -- explicitly (MICROS-truncated) by the application so paired writes share
    -- one instant; superseded_at is NULL on current rows.
    recorded_at            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    superseded_at          TIMESTAMP,

    CONSTRAINT pk_partner_kyb PRIMARY KEY (id),

    CONSTRAINT fk_partner_kyb_partner
        FOREIGN KEY (partner_id) REFERENCES partners (id),

    -- NULL passes a CHECK (UNKNOWN ≠ FALSE) on both engines, so drafts may
    -- leave these unset while garbage values are still rejected.
    CONSTRAINT ck_partner_kyb_risk_rating CHECK (
        risk_rating IN ('LOW', 'MEDIUM', 'HIGH')
    ),

    CONSTRAINT ck_partner_kyb_screening_status CHECK (
        screening_status IN ('CLEAR', 'HIT', 'NEEDS_REVIEW')
    )
);

-- Hot-path lookup: "current KYB row for partner P" filters on partner_id = ?
-- AND superseded_at IS NULL — the composite serves it on both engines without
-- partial-index emulation (same choice as idx_partner_contact_current, V009).
CREATE INDEX idx_partner_kyb_current
    ON partner_kyb (partner_id, superseded_at);
