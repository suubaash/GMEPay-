-- V009: partner_contact child table — Slice 2 "Contacts" (docs/PARTNER_SETUP_PLAN.md §Slice 2).
--
-- WHY BITEMPORAL (ADR-010)
-- ------------------------
-- Contacts are regulated data: the is_authorized_signatory flag gates the
-- 2-signatory approval rule on bank-account changes (Slice 4) and the MLRO /
-- compliance contact set is part of what the regulator inspects at audit time.
-- The table therefore carries the same SCD-6 column pairs the partners table
-- gained in V004:
--
--   * valid_from  / valid_to       — business time (when the contact fact is true)
--   * recorded_at / superseded_at  — transaction time (when we wrote it; NULL on
--                                     rows that are still current)
--
-- Storage discipline: rows are NEVER UPDATEd in place. The wizard's step-2 save
-- is a bulk replace — one transaction that (UPDATE all current rows SET
-- superseded_at = now()) + (INSERT the new set with recorded_at = now()), both
-- halves sharing the same MICROS-truncated instant (see PartnerContactService).
--
-- INDEXING
-- --------
-- V004 needed a partial-unique emulation (generated column + UNIQUE index)
-- because partners enforce "one current row per partner_code". Contacts have NO
-- uniqueness constraint — a partner legitimately holds N current contacts — so a
-- plain composite index on (partner_id, superseded_at) is sufficient: the hot
-- query is `WHERE partner_id = ? AND superseded_at IS NULL` and the composite
-- serves it on both PostgreSQL and H2 without any partial-index emulation.
--
-- COMPATIBILITY
-- -------------
-- PostgreSQL-compatible DDL that also runs under H2 in PostgreSQL mode (the
-- engine the @DataJpaTest slices use):
--   * Plain TIMESTAMP, not TIMESTAMPTZ — H2 PG-mode does not recognise the
--     TIMESTAMPTZ alias (same portable spelling rationale as V004/V006). The JPA
--     boundary stores java.time.Instant, marshalled as UTC under both engines.
--   * BIGSERIAL surrogate id, managed by the engine (same pattern as audit_log
--     V006) — contacts have no migration story needing application-minted ids.
--
-- ADR-013 Expand discipline: wholly-new table; every nullable-able column is
-- nullable or defaulted; no in-place ALTERs on existing tables.

CREATE TABLE partner_contact (
    -- BIGSERIAL surrogate, engine-managed (GenerationType.IDENTITY on the
    -- entity) — same id strategy as audit_log (V006).
    id                      BIGSERIAL    NOT NULL,

    -- FK to the partners surrogate (V003/V004 BIGINT PK). NOTE: this references
    -- the partner AGGREGATE via whichever row was current when the contact was
    -- written; consumers resolve partners by partner_code for the current view,
    -- so the FK's job here is referential integrity, not current-row identity.
    partner_id              BIGINT       NOT NULL,

    -- Functional role of the contact. CHECK-constrained to the Slice 2 roster;
    -- the activation gate (Slice 8) requires >= 4 distinct roles covered.
    role                    VARCHAR(20)  NOT NULL,

    name                    VARCHAR(120) NOT NULL,

    -- RFC 5321 maximum path length is 254 characters.
    email                   VARCHAR(254) NOT NULL,

    -- E.164 international format: '+' followed by up to 15 digits, first digit
    -- non-zero. 17 chars leaves headroom; format enforced server-side by
    -- PartnerContactService (^\+[1-9]\d{1,14}$), not by the DB.
    phone_e164              VARCHAR(17),

    -- TRUE when this person may approve bank-account changes (Slice 4 requires
    -- two authorized-signatory approvals; DB-enforced there).
    is_authorized_signatory BOOLEAN      NOT NULL DEFAULT FALSE,

    notes                   VARCHAR(500),

    -- Business time (ADR-010). Half-open [valid_from, valid_to); NULL valid_to
    -- = open-ended.
    valid_from              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    valid_to                TIMESTAMP,

    -- Transaction time (ADR-010). recorded_at is defaulted as a safety net but
    -- the application sets it explicitly (MICROS-truncated) so paired writes
    -- share one instant; superseded_at is NULL on current rows.
    recorded_at             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    superseded_at           TIMESTAMP,

    CONSTRAINT pk_partner_contact PRIMARY KEY (id),

    CONSTRAINT fk_partner_contact_partner
        FOREIGN KEY (partner_id) REFERENCES partners (id),

    CONSTRAINT ck_partner_contact_role CHECK (
        role IN ('OPS_24X7', 'FINANCE', 'COMPLIANCE_MLRO', 'TECH', 'LEGAL', 'INCIDENT')
    )
);

-- Hot-path lookup: "all current contacts for partner P" filters on
-- partner_id = ? AND superseded_at IS NULL. The composite serves both the filter
-- and the historical walk (superseded_at ordered) without partial-index
-- emulation — contacts carry no per-partner uniqueness, so a plain index is the
-- cross-engine-correct choice (see header).
CREATE INDEX idx_partner_contact_current
    ON partner_contact (partner_id, superseded_at);
