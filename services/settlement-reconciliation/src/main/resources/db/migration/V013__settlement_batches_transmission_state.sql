-- GAP T4-5: transmission is its own axis, and it must not be inferable from the recon status.
--
-- Before this, "did we send the settlement file?" was answered by settlement_batches.status ==
-- 'TRANSMITTED', which ReconDiffEngine set as a bookkeeping hop on its way to 'RECEIVED'. Nothing had
-- ever been sent: the platform's only SftpTransport bean is LocalDirSftpTransport, which copies the
-- file into a local temp directory, and real ZeroPay/KFTC SFTP needs scheme credentials plus a
-- certification run (an external gate). So a batch that was never transmitted read as transmitted.
--
-- transmission_state records the truth on its own column, with its own vocabulary:
--   NOT_TRANSMITTED                     - not sent (yet)
--   NOT_TRANSMITTED_CHANNEL_UNAVAILABLE - no transmission channel exists in this deployment; the
--                                         standing state of every batch in every environment today
--   TRANSMISSION_FAILED                 - attempted over a live channel and failed
--   TRANSMITTED                         - genuinely handed to the scheme
--
-- H2 (MODE=PostgreSQL) + PostgreSQL portable; nullable/defaulted so existing rows migrate (Expand
-- discipline, ADR-013). This module's migrations are flat — no vendor subdirectories to mirror.

ALTER TABLE settlement_batches
    ADD COLUMN transmission_state VARCHAR(40) DEFAULT 'NOT_TRANSMITTED' NOT NULL;

ALTER TABLE settlement_batches ADD COLUMN transmission_channel VARCHAR(64);
ALTER TABLE settlement_batches ADD COLUMN transmission_detail VARCHAR(512);

-- Reclassify the historical rows that the recon fast-forward had moved to/through 'TRANSMITTED'.
-- The audit trail is kept: status is NOT rewritten (rewriting it would make an honest correction
-- indistinguishable from someone editing settlement history), only the new, separate transmission
-- column is set — and it is set to the truth, which is that no channel existed to send them over.
UPDATE settlement_batches
   SET transmission_state = 'NOT_TRANSMITTED_CHANNEL_UNAVAILABLE',
       transmission_detail = 'Reclassified by V013 (GAP T4-5): this batch predates the '
           || 'transmission-state column. Its status had been advanced by the reconciliation '
           || 'fast-forward, not by a transmission - no settlement transmission channel has ever '
           || 'been configured, so the file was never sent.'
 WHERE status IN ('TRANSMITTED', 'RECEIVED', 'RECONCILED');

-- Anything else pre-dating this column is simply not sent.
UPDATE settlement_batches
   SET transmission_state = 'NOT_TRANSMITTED'
 WHERE transmission_state IS NULL;

-- transmitted_at was previously a free-standing nullable timestamp with a public setter — which is
-- how a never-sent batch acquires a send timestamp. It is now writable only together with the state.
-- The CHECK makes the invariant hold against a hand-written UPDATE too, not just against the
-- application: a timestamp without TRANSMITTED, or TRANSMITTED without a timestamp, is rejected.
UPDATE settlement_batches
   SET transmitted_at = NULL
 WHERE transmitted_at IS NOT NULL
   AND transmission_state <> 'TRANSMITTED';

ALTER TABLE settlement_batches
    ADD CONSTRAINT ck_settlement_batches_transmitted_at
    CHECK ((transmission_state = 'TRANSMITTED' AND transmitted_at IS NOT NULL)
        OR (transmission_state <> 'TRANSMITTED' AND transmitted_at IS NULL));

-- Operators and partners filter statements by "was this actually sent?".
CREATE INDEX ix_settlement_batches_transmission_state
    ON settlement_batches (transmission_state);
