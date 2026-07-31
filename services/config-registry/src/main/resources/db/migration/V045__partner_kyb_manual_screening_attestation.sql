-- V045: the MANUAL KYB SOP screening authority on partner_kyb (gap T1-4, owner
-- decision 2026-07-28).
--
-- WHY
-- ---
-- V042 made an unscreened partner unactivatable: a non-authoritative CLEAR is
-- coerced to NOT_SCREENED_NO_PROVIDER, ck_partner_kyb_clear_requires_authority
-- makes a clean-row-without-authority unrepresentable, and the activation gate
-- raises SANCTIONS_NOT_SCREENED. Correct — and it left every partner in every
-- environment unactivatable, because the ADR-014 vendor has never been available
-- and OctaKybAdapter still throws.
--
-- The owner's decision is NOT to wait for the vendor and NOT to use the
-- non-production gmepay.activation.allow-unscreened-kyb escape hatch. It is that
-- compliance signs a written manual screening procedure, a named human performs
-- it, and the platform records that attestation as a REAL screening authority.
--
-- WHAT CHANGES
-- ------------
--   1. Five columns record the attestation as first-class data: WHO attested (a
--      verified human in the T5-1 AuditActors vocabulary — never a service
--      identity, never an unverified claim), WHEN, WHICH SOP document and
--      version were followed, and WHAT was checked (the lists/sources the
--      attester names, free text — this repository does not know which lists
--      GME's procedure covers and will not invent a roster).
--   2. screening_status gains CLEAR_MANUAL_ATTESTATION. Deliberately a distinct
--      value rather than reusing CLEAR: a partner cleared by a human under an
--      SOP must stay distinguishable from one cleared by a vendor at every hop
--      that carries only the status string (this column, the wire DTO, the
--      compliance board, the wizard chip). lib-kyb's ScreeningResult coerces a
--      manual CLEAR *up* to this value and coerces this value *down* to
--      NOT_SCREENED_NO_PROVIDER for any producer with no attestation, so neither
--      direction can be defeated by a payload.
--   3. Two new CHECKs bind the status to the evidence. The V042 CHECK
--      ck_partner_kyb_clear_requires_authority is left EXACTLY as it was — it
--      constrains 'CLEAR' and nothing here relaxes it.
--
-- NOTHING HISTORICAL IS RECLASSIFIED. V042/V002 already corrected the stub-derived
-- rows and appended their own explanation to the audit chain (T5-1(c)). This
-- migration touches no existing row: the attestation is a new act by a named human
-- and cannot be back-dated onto partners nobody screened. Every column added here
-- is nullable and every partner_kyb row keeps the value it had.
--
-- ADR-013 EXPAND: additive nullable columns + one replaced roster CHECK + two new
-- CHECKs. Engine-neutral (PostgreSQL and H2 in PostgreSQL mode, which the
-- @DataJpaTest slices run on): ADD COLUMN / DROP CONSTRAINT / ADD CONSTRAINT only.
-- No vendor-specific variant is needed — db/vendor/{h2,postgresql} carry only
-- V004, V023 and V044, and nothing here uses vendor syntax. V044 exists only as a
-- vendor pair, so V045 is the next free version across every Flyway location.

-- The verified human who performed the screening and is accountable for it. Width
-- matches audit_log.actor_id (VARCHAR(64)) because it holds the SAME value — the
-- actor of the PARTNER_KYB_MANUAL_SCREENING_ATTESTED audit row — so the two can be
-- joined without a cast or a truncation mismatch.
ALTER TABLE partner_kyb
    ADD COLUMN manual_attester_actor_id VARCHAR(64);

-- When the attestation was made. A screening is a point-in-time statement about
-- list contents that change daily, so an attestation with no instant can never be
-- aged out or scheduled for renewal.
ALTER TABLE partner_kyb
    ADD COLUMN manual_attested_at TIMESTAMP;

-- The compliance-signed SOP document that was followed, as compliance controls it
-- (document id / title / URI). Without it the row says only "somebody checked",
-- which is the unfalsifiable claim T1-4 exists to remove.
ALTER TABLE partner_kyb
    ADD COLUMN manual_sop_document_ref VARCHAR(128);

-- The revision of that document the attester followed. Procedures change; an
-- attestation that does not say which revision it followed cannot be reviewed.
ALTER TABLE partner_kyb
    ADD COLUMN manual_sop_version VARCHAR(32);

-- What was actually checked, in the attester's own words. FREE TEXT on purpose:
-- constraining it to an enum of list names would put list names in the record that
-- nobody consulted — the same class of lie as the stub's CLEAR.
ALTER TABLE partner_kyb
    ADD COLUMN manual_sources_consulted VARCHAR(2000);

-- ---------------------------------------------------------------------------
-- The status roster gains the manual value. The other four are unchanged, and
-- NOT_SCREENED_NO_PROVIDER remains the honest terminal state of an unscreened run.
-- ---------------------------------------------------------------------------
ALTER TABLE partner_kyb
    DROP CONSTRAINT ck_partner_kyb_screening_status;

ALTER TABLE partner_kyb
    ADD CONSTRAINT ck_partner_kyb_screening_status CHECK (
        screening_status IN ('CLEAR', 'CLEAR_MANUAL_ATTESTATION', 'HIT', 'NEEDS_REVIEW',
                             'NOT_SCREENED_NO_PROVIDER')
    );

-- ---------------------------------------------------------------------------
-- The evidence binding, in both directions. Same structural intent as V042's
-- ck_partner_kyb_clear_requires_authority: lib-kyb's constructors already make
-- these states unreachable from the application, and these CHECKs also catch a
-- direct psql UPDATE, a backfill and a test fixture that hand-writes a clean row.
--
-- COALESCE is load-bearing for the same reason it was in V042 — a bare
-- `screening_authoritative = TRUE` yields NULL for an unset column, and SQL treats
-- a NULL CHECK predicate as "not violated", i.e. exactly the row being forbidden.
-- ---------------------------------------------------------------------------

-- (a) A manual clean claim must carry the complete attestation. Missing attester,
--     instant, SOP reference, SOP version or sources => the row is refused.
ALTER TABLE partner_kyb
    ADD CONSTRAINT ck_partner_kyb_manual_clear_requires_attestation CHECK (
        screening_status <> 'CLEAR_MANUAL_ATTESTATION'
        OR (COALESCE(screening_authoritative, FALSE) = TRUE
            AND screening_provider_id = 'manual-sop'
            AND manual_attester_actor_id IS NOT NULL
            AND manual_attested_at IS NOT NULL
            AND manual_sop_document_ref IS NOT NULL
            AND manual_sop_version IS NOT NULL
            AND manual_sources_consulted IS NOT NULL)
    );

-- (b) The converse: attestation columns belong only to a manual run, and a manual
--     run is never partially attested. This is what stops the columns being used
--     to decorate a stub run with a human's name (which would make a machine run
--     look human-verified), and stops a manual HIT / NEEDS_REVIEW row — both legal
--     statuses for a manual screening — from carrying half an attestation.
ALTER TABLE partner_kyb
    ADD CONSTRAINT ck_partner_kyb_manual_attestation_consistent CHECK (
        (manual_attester_actor_id IS NULL
            AND manual_attested_at IS NULL
            AND manual_sop_document_ref IS NULL
            AND manual_sop_version IS NULL
            AND manual_sources_consulted IS NULL
            AND (screening_provider_id IS NULL OR screening_provider_id <> 'manual-sop'))
        OR (manual_attester_actor_id IS NOT NULL
            AND manual_attested_at IS NOT NULL
            AND manual_sop_document_ref IS NOT NULL
            AND manual_sop_version IS NOT NULL
            AND manual_sources_consulted IS NOT NULL
            AND screening_provider_id = 'manual-sop'
            AND COALESCE(screening_authoritative, FALSE) = TRUE)
    );

-- Attestations are queried by attester (a compliance review of what one officer
-- signed off) and by SOP version (which partners rest on a superseded procedure) —
-- both are the questions a reviewer actually asks of this table.
CREATE INDEX idx_partner_kyb_manual_attester
    ON partner_kyb (manual_attester_actor_id);
