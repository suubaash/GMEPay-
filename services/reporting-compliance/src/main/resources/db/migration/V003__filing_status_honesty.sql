-- V003: filing-status honesty (GAP T5-2 / CISO audit §11).
--
-- WHY THIS MIGRATION EXISTS
-- The three regulatory lanes in this service do real work: KoFIU CTR/STR threshold
-- computation, BOK FX1014/FX1015 mapping + fixed-width file generation, Hometax monthly
-- VAT aggregation. None of them has ever transmitted anything. The BOK SFTP endpoint and
-- the Hometax NTS mTLS onboarding are externally gated (OI-03 / OI-02) and the KoFIU
-- electronic feed endpoint is unconfirmed.
--
-- Until 2026-07-28 the no-channel clients fabricated acknowledgements:
--   * StubHometaxClient returned literal status 'ACCEPTED' plus a spec-shaped fake
--     24-character NTS confirmation number;
--   * StubKofiuFeedClient returned a fake receipt id 'STUB-<uuid>'.
-- report_filing could therefore be advanced to SUBMITTED / CONFIRMED, and external_receipt_id
-- populated, for a filing that never left the JVM. Anyone reading this table, the API or the
-- logs would reasonably conclude a regulatory filing had been accepted. None was. Putting a
-- fabricated 'ACCEPTED' in front of a regulator is materially worse than an honestly empty
-- filing register, so the states themselves are being made unable to say it.
--
-- WHAT CHANGES
--   1. The status vocabulary now separates the four distinct facts:
--        GENERATED  - we aggregated the data and produced the artifact  (real today)
--        VALIDATED  - the artifact passed OUR OWN local format checks   (real today)
--        TRANSMITTED   - a real channel accepted the bytes             (impossible today)
--        ACKNOWLEDGED  - the authority returned a receipt              (impossible today)
--      plus the honest terminal state that is actually reachable:
--        NOT_FILED_CHANNEL_UNAVAILABLE - generated, never sent, and here is why.
--   2. Historical fabricated SUBMITTED / CONFIRMED rows are RECLASSIFIED to
--      NOT_FILED_CHANNEL_UNAVAILABLE. Nothing is deleted: the prior status is kept in
--      reclassified_from and the full explanation (including the fabricated receipt id and
--      timestamp) in reclassification_note. external_receipt_id and submitted_at are cleared
--      because those columns must only ever hold facts produced by a real channel.
--
-- Application-side counterpart: ReportFilingService.recordTransmission/recordAcknowledgement
-- refuse to write TRANSMITTED/ACKNOWLEDGED unless FilingChannelRegistry reports a live
-- channel for the lane, and no lane can become live without explicit endpoint/credential
-- configuration. That is what stops the fabricated states from coming back.

-- ---------------------------------------------------------------------------
-- report_filing
-- ---------------------------------------------------------------------------

ALTER TABLE report_filing DROP CONSTRAINT ck_report_filing_status;

-- 'NOT_FILED_CHANNEL_UNAVAILABLE' is 29 chars; the column was VARCHAR(16).
ALTER TABLE report_filing ALTER COLUMN submission_status SET DATA TYPE VARCHAR(32);

ALTER TABLE report_filing ADD COLUMN channel_unavailable_reason VARCHAR(512);
ALTER TABLE report_filing ADD COLUMN reclassified_from          VARCHAR(32);
ALTER TABLE report_filing ADD COLUMN reclassification_note      VARCHAR(512);

-- Reclassify (never delete) every fabricated acceptance.
UPDATE report_filing
   SET reclassified_from = submission_status,
       reclassification_note =
           'T5-2 V003: reclassified from ' || submission_status
           || '. No regulatory transmission channel has ever existed for lane ' || lane
           || ' (BOK SFTP / KoFIU feed / Hometax NTS mTLS all externally gated), so this row'
           || ' could not have been filed. Fabricated external_receipt_id was: '
           || COALESCE(external_receipt_id, '(none)')
           || '; fabricated submitted_at was: '
           || COALESCE(CAST(submitted_at AS VARCHAR), '(none)') || '.',
       channel_unavailable_reason =
           'No filing channel configured for lane ' || lane
           || ' — status reclassified by migration V003 because the previous value was'
           || ' produced by a stub client, not by a regulator.',
       external_receipt_id = NULL,
       submitted_at = NULL,
       submission_status = 'NOT_FILED_CHANNEL_UNAVAILABLE',
       updated_at = CURRENT_TIMESTAMP
 WHERE submission_status IN ('SUBMITTED', 'CONFIRMED');

ALTER TABLE report_filing ADD CONSTRAINT ck_report_filing_status
    CHECK (submission_status IN (
        'PENDING',
        'GENERATED',
        'VALIDATED',
        'NOT_FILED_CHANNEL_UNAVAILABLE',
        'TRANSMITTED',
        'ACKNOWLEDGED',
        'FAILED'));

COMMENT ON COLUMN report_filing.submission_status IS
    'PENDING|GENERATED|VALIDATED|NOT_FILED_CHANNEL_UNAVAILABLE|TRANSMITTED|ACKNOWLEDGED|FAILED. TRANSMITTED/ACKNOWLEDGED require a live channel (FilingChannelRegistry) and are unreachable today.';
COMMENT ON COLUMN report_filing.external_receipt_id IS
    'Receipt id issued BY THE AUTHORITY. Must be NULL unless a real channel returned one.';
COMMENT ON COLUMN report_filing.channel_unavailable_reason IS
    'Why this filing could not be transmitted (missing endpoint/credential config).';
COMMENT ON COLUMN report_filing.reclassified_from IS
    'Prior submission_status when V003 reclassified a fabricated acceptance. Audit trail.';
COMMENT ON COLUMN report_filing.reclassification_note IS
    'Full explanation of the V003 reclassification, incl. the discarded fabricated values.';

-- ---------------------------------------------------------------------------
-- bok_report_record (per-transaction BOK rows share the same vocabulary)
-- ---------------------------------------------------------------------------

ALTER TABLE bok_report_record DROP CONSTRAINT ck_bok_record_status;

ALTER TABLE bok_report_record ALTER COLUMN submission_status SET DATA TYPE VARCHAR(32);

UPDATE bok_report_record
   SET submission_status = 'NOT_FILED_CHANNEL_UNAVAILABLE',
       submitted_at = NULL,
       updated_at = CURRENT_TIMESTAMP
 WHERE submission_status IN ('SUBMITTED', 'CONFIRMED');

ALTER TABLE bok_report_record ADD CONSTRAINT ck_bok_record_status
    CHECK (submission_status IN (
        'PENDING',
        'NOT_FILED_CHANNEL_UNAVAILABLE',
        'TRANSMITTED',
        'ACKNOWLEDGED',
        'FAILED'));

COMMENT ON COLUMN bok_report_record.submission_status IS
    'PENDING until a BOK channel exists; NOT_FILED_CHANNEL_UNAVAILABLE once a run settles. TRANSMITTED/ACKNOWLEDGED require a live BOK SFTP channel (OI-03).';
