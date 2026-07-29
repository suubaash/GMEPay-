-- V002: screening PROVENANCE on the adapter-side run log (gap T1-4).
--
-- WHY
-- ---
-- V001 stored a screening_status of CLEAR | HIT | NEEDS_REVIEW and a collapsed
-- decision of PASS | FAIL | MANUAL_REVIEW, with no record of WHO produced the
-- verdict. Until the Octa Solution sandbox credentials land (ADR-014) the only
-- provider that answers is lib-kyb's StubKybAdapter, which keyword-matches the
-- subject's own names and consults no sanctions, PEP or adverse-media source at
-- all. Every such run therefore wrote screening_status = 'CLEAR' and
-- decision = 'PASS' for any partner whose name lacked the word "SANCTIONED" —
-- a row indistinguishable from a completed vendor screening, in the table that
-- GET /v1/kyb/result/{ref} serves back to the onboarding wizard.
--
-- WHAT CHANGES
-- ------------
--   1. screening_status widens to VARCHAR(32) to hold the new honest terminal
--      value NOT_SCREENED_NO_PROVIDER (lib-kyb ScreeningResult.Status), which is
--      what a non-authoritative provider now records instead of CLEAR. The
--      coercion is enforced in the Java type itself (ScreeningResult's compact
--      constructor), so no code path — including a JSON payload from an older
--      producer — can put a non-authoritative CLEAR back in this column.
--   2. Provenance columns record which provider produced the run, whether it is
--      an authority, and the caveat that must travel with a non-authoritative
--      verdict.
--   3. Existing stub-derived rows are RECLASSIFIED, never deleted: their prior
--      value is preserved in reclassified_from / reclassification_note so the
--      audit trail shows exactly what was claimed before and why it was changed.
--
-- COMPATIBILITY
-- -------------
-- ALTER ... SET DATA TYPE and ADD COLUMN are engine-neutral (PostgreSQL + H2 in
-- PostgreSQL mode, which the unit slices run on). No JSONB, no PG-only types.
-- Mirrors the reporting-compliance V003 filing-honesty migration (T5-2).

ALTER TABLE kyb_screening
    ALTER COLUMN screening_status SET DATA TYPE VARCHAR(32);

-- Stable id of the producing provider: 'stub' (in-process StubKybAdapter),
-- 'unknown' (a result that declared no producer), or a vendor id (e.g. 'octa').
ALTER TABLE kyb_screening
    ADD COLUMN screening_provider_id VARCHAR(32);

-- TRUE only when a real screening provider actually consulted screening sources.
-- Defaults FALSE: an unstated provenance is never treated as authority.
ALTER TABLE kyb_screening
    ADD COLUMN screening_authoritative BOOLEAN DEFAULT FALSE NOT NULL;

-- Why the run is not authoritative (NULL on an authoritative run). Surfaced on
-- the verify/result response so the limitation cannot be dropped downstream.
ALTER TABLE kyb_screening
    ADD COLUMN screening_caveat VARCHAR(512);

-- Audit of the reclassification below: the value this row claimed before V002.
ALTER TABLE kyb_screening
    ADD COLUMN reclassified_from VARCHAR(64);

ALTER TABLE kyb_screening
    ADD COLUMN reclassification_note VARCHAR(512);

-- ---------------------------------------------------------------------------
-- Backfill provenance for rows written before this migration existed. The
-- provider_ref prefix is the reliable discriminator: StubKybAdapter mints
-- 'stub-<12 hex>' (and the full-run ref 'stub-<hex>-full').
-- ---------------------------------------------------------------------------
UPDATE kyb_screening
   SET screening_provider_id = 'stub',
       screening_authoritative = FALSE,
       screening_caveat = 'NOT A SANCTIONS SCREENING: produced in-process by StubKybAdapter, which'
                       || ' keyword-matches the subject''s own names. No sanctions, PEP or'
                       || ' adverse-media source was consulted (no KYB vendor is configured —'
                       || ' ADR-014, Octa sandbox credentials pending).'
 WHERE provider_ref LIKE 'stub-%';

UPDATE kyb_screening
   SET screening_provider_id = 'unknown',
       screening_authoritative = FALSE,
       screening_caveat = 'PROVENANCE ABSENT: the producer of this screening result did not declare'
                       || ' itself, so it cannot be presented as a completed check. Treated as'
                       || ' non-authoritative.'
 WHERE screening_provider_id IS NULL;

-- ---------------------------------------------------------------------------
-- RECLASSIFY the two states that could masquerade as a completed check.
-- Order matters: the decision reclass reads the (already reclassified) status,
-- so it keys off screening_authoritative, which both branches share.
-- ---------------------------------------------------------------------------
UPDATE kyb_screening
   SET reclassified_from = screening_status,
       reclassification_note = 'V002 (T1-4): screening_status ''' || screening_status
                            || ''' was produced by a non-authoritative provider ('
                            || screening_provider_id || ') that consulted no screening source;'
                            || ' recorded as NOT_SCREENED_NO_PROVIDER because no screening happened.',
       screening_status = 'NOT_SCREENED_NO_PROVIDER'
 WHERE screening_authoritative = FALSE
   AND screening_status = 'CLEAR';

-- A PASS collapsed off an unscreened run is the activation-facing form of the
-- same lie. Downgraded to MANUAL_REVIEW (never to FAIL — the stub inventing a
-- rejection would be its own defect), with the original verdict preserved.
UPDATE kyb_screening
   SET reclassification_note = COALESCE(reclassification_note || ' ', '')
                            || 'Decision ''PASS'' downgraded to MANUAL_REVIEW: a pass cannot be'
                            || ' collapsed from a screening that never ran.',
       reclassified_from = COALESCE(reclassified_from, 'PASS'),
       decision = 'MANUAL_REVIEW',
       decision_reason = 'no authoritative sanctions screening was performed'
                      || ' (reclassified by V002, T1-4)'
 WHERE screening_authoritative = FALSE
   AND decision = 'PASS';
