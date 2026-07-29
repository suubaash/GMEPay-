-- V042: screening PROVENANCE on partner_kyb — the partner-side, regulator-facing
-- KYB record (gap T1-4).
--
-- WHY
-- ---
-- partner_kyb.screening_status held CLEAR | HIT | NEEDS_REVIEW with no record of
-- WHO produced the verdict. Until the Octa Solution sandbox credentials land
-- (ADR-014) the only provider that answers is lib-kyb's StubKybAdapter, which
-- keyword-matches the subject's own names and consults no sanctions, PEP or
-- adverse-media source. Worse, config-registry defaults to running that stub
-- IN-PROCESS (GMEPAY_KYB_ADAPTER_CLIENT was set in no deployment file, so
-- StubKybClient's matchIfMissing=true always won). Every partner whose legal
-- names lacked the word "SANCTIONED" therefore got screening_status = 'CLEAR' —
-- and the Slice 8 activation gate treated exactly that string as a satisfied
-- sanctions pre-condition. A partner could go LIVE on a screening that never ran.
--
-- WHAT CHANGES
-- ------------
--   1. screening_status widens VARCHAR(15) -> VARCHAR(32) and its CHECK gains
--      NOT_SCREENED_NO_PROVIDER — the honest terminal value a non-authoritative
--      provider records instead of CLEAR. The coercion is enforced in the Java
--      type (lib-kyb ScreeningResult's compact constructor), so no write path,
--      including a JSON payload from an older kyb-adapter, can put a
--      non-authoritative CLEAR back in this column.
--   2. Provenance columns record which provider produced the run, whether it is
--      an authority, and the caveat that must travel with the verdict. The
--      activation gate now requires screening_authoritative = TRUE, so even a
--      hand-INSERTed 'CLEAR' cannot satisfy the pre-condition without stating a
--      provider that vouched for it.
--   3. Existing stub-derived rows are RECLASSIFIED, never deleted: the prior
--      value is preserved in reclassified_from / reclassification_note so an
--      auditor sees what was claimed before and why it changed.
--
-- BITEMPORAL NOTE (ADR-010)
-- -------------------------
-- partner_kyb is SCD-6 and rows are never UPDATEd by the application. This
-- migration DOES update in place, deliberately and once: the point is to correct
-- the historical record's LABEL, not to assert a new business fact, and inserting
-- superseding rows would invent screening events that never occurred. The
-- ADR-007 audit chain will therefore show these rows disagreeing with their
-- sealed AFTER snapshots — that divergence is the migration's own evidence and is
-- explained by reclassification_note on every touched row. Nothing else about
-- the row (risk rating, UBO set, timestamps) is altered.
--
-- ADR-013 EXPAND: additive nullable columns + a widened column + a replaced
-- CHECK. Engine-neutral (PostgreSQL and H2 in PostgreSQL mode, which the
-- @DataJpaTest slices run on): ALTER ... SET DATA TYPE, ADD COLUMN, DROP/ADD
-- CONSTRAINT only. No vendor-specific variant is needed (db/vendor/{h2,postgresql}
-- carry only V004 and V023).

ALTER TABLE partner_kyb
    ALTER COLUMN screening_status SET DATA TYPE VARCHAR(32);

ALTER TABLE partner_kyb
    DROP CONSTRAINT ck_partner_kyb_screening_status;

-- Stable id of the producing provider: 'stub' (the in-process StubKybAdapter),
-- 'unknown' (a result that declared no producer), or a vendor id (e.g. 'octa').
ALTER TABLE partner_kyb
    ADD COLUMN screening_provider_id VARCHAR(32);

-- TRUE only when a real screening provider consulted screening sources. NULL on
-- rows written before this migration that had no screening at all; the
-- activation gate treats anything other than TRUE as "not screened".
ALTER TABLE partner_kyb
    ADD COLUMN screening_authoritative BOOLEAN;

-- Why the run is not authoritative (NULL on an authoritative run). Read by the
-- activation checklist so the operator sees the limitation, not just a status.
ALTER TABLE partner_kyb
    ADD COLUMN screening_caveat VARCHAR(512);

-- Audit of the reclassification below.
ALTER TABLE partner_kyb
    ADD COLUMN reclassified_from VARCHAR(64);

ALTER TABLE partner_kyb
    ADD COLUMN reclassification_note VARCHAR(512);

-- ---------------------------------------------------------------------------
-- Backfill provenance. screening_provider_ref is the reliable discriminator:
-- StubKybAdapter mints 'stub-<12 hex>' (and 'stub-<hex>-full' for a full run).
-- Rows that never screened (screening_status IS NULL) get no provenance — there
-- is no run to attribute.
-- ---------------------------------------------------------------------------
UPDATE partner_kyb
   SET screening_provider_id = 'stub',
       screening_authoritative = FALSE,
       screening_caveat = 'NOT A SANCTIONS SCREENING: produced in-process by StubKybAdapter, which'
                       || ' keyword-matches the subject''s own names. No sanctions, PEP or'
                       || ' adverse-media source was consulted (no KYB vendor is configured —'
                       || ' ADR-014, Octa sandbox credentials pending).'
 WHERE screening_status IS NOT NULL
   AND screening_provider_ref LIKE 'stub-%';

UPDATE partner_kyb
   SET screening_provider_id = 'unknown',
       screening_authoritative = FALSE,
       screening_caveat = 'PROVENANCE ABSENT: the producer of this screening result did not declare'
                       || ' itself, so it cannot be presented as a completed check. Treated as'
                       || ' non-authoritative.'
 WHERE screening_status IS NOT NULL
   AND screening_provider_id IS NULL;

-- ---------------------------------------------------------------------------
-- RECLASSIFY every stored 'CLEAR' that no authority produced. This is the row
-- shape the activation gate accepted as a passed sanctions check.
-- ---------------------------------------------------------------------------
UPDATE partner_kyb
   SET reclassified_from = screening_status,
       reclassification_note = 'V042 (T1-4): screening_status ''' || screening_status
                            || ''' was produced by a non-authoritative provider ('
                            || screening_provider_id || ') that consulted no screening source.'
                            || ' Recorded as NOT_SCREENED_NO_PROVIDER because no screening'
                            || ' happened; the activation gate no longer accepts it as a passed'
                            || ' sanctions check. Re-screen through a real provider.',
       screening_status = 'NOT_SCREENED_NO_PROVIDER'
 WHERE screening_status = 'CLEAR'
   AND screening_authoritative IS NOT TRUE;

-- A verify verdict of APPROVED / PASS collapsed off an unscreened run is the
-- activation-facing form of the same claim. Downgraded to MANUAL_REVIEW (never
-- to a rejection — the absence of a provider is not evidence against a partner).
UPDATE partner_kyb
   SET reclassification_note = COALESCE(reclassification_note || ' ', '')
                            || 'verification_decision ''' || verification_decision
                            || ''' downgraded to MANUAL_REVIEW: a KYB approval cannot be collapsed'
                            || ' from a screening that never ran.',
       reclassified_from = COALESCE(reclassified_from, verification_decision),
       verification_decision = 'MANUAL_REVIEW',
       verification_decision_reason = 'no authoritative sanctions screening was performed'
                                   || ' (reclassified by V042, T1-4)'
 WHERE verification_decision IN ('APPROVED', 'PASS')
   AND screening_authoritative IS NOT TRUE;

-- The new vocabulary. NOT_SCREENED_NO_PROVIDER joins the roster; the three
-- original dispositions are unchanged.
ALTER TABLE partner_kyb
    ADD CONSTRAINT ck_partner_kyb_screening_status CHECK (
        screening_status IN ('CLEAR', 'HIT', 'NEEDS_REVIEW', 'NOT_SCREENED_NO_PROVIDER')
    );

-- Structural backstop for the whole gap: a CLEAR must name the authority that
-- produced it. Belt and braces with lib-kyb's constructor coercion — this one
-- also catches a direct psql UPDATE (and a test or fixture that hand-writes a
-- clean screening without saying who performed it).
--
-- COALESCE is load-bearing: with a bare `screening_authoritative = TRUE` a NULL
-- would make the predicate NULL, which SQL treats as "not violated" — i.e. the
-- exact row we are trying to forbid (a CLEAR with no stated authority) would slip
-- through on three-valued logic.
ALTER TABLE partner_kyb
    ADD CONSTRAINT ck_partner_kyb_clear_requires_authority CHECK (
        screening_status <> 'CLEAR' OR COALESCE(screening_authoritative, FALSE) = TRUE
    );
