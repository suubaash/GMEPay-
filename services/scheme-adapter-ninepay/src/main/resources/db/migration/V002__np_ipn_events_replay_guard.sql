-- Gap T5-4: IPN replay protection for scheme-adapter-ninepay.
--
-- BEFORE: handleIpn() audited the push and then applied recordIpn() UNCONDITIONALLY. A
--         captured, validly-signed IPN could be resent to re-apply state. That matters
--         most for code 009 (bank reversal AFTER success) and code 000 (success): a
--         replayed 009 re-reversed a payout, and a stale 000 could be replayed on top of
--         a later 009 to make a reversed payout look successful again.
--
-- AFTER:  every ACCEPTED (signature-valid) IPN claims a unique event_key. 9Pay sends no
--         IPN id of its own, so the key is its own event identity — the
--         (request_id, trans_id, code) triple the CISO audit prescribed, normalised and
--         hashed into one column so a single UNIQUE constraint can enforce it on both
--         PostgreSQL and H2.
--
-- Why NOT dedupe on the signed content: 9Pay's IPN signature covers
-- request_id|partner_id|trans_id|request_amount|fee|transfer_amount|type|status|created_at
-- and NOT code. A genuine 009 reversal arrives with the SAME signed fields (status stays
-- SUCCESS) as the 000 that preceded it, so keying on the signed body would swallow the
-- real reversal. Keying on the triple keeps 000 -> 009 distinguishable while making an
-- exact resend of either one a no-op. (The flip side — that `code` is unsigned, so 9Pay's
-- own scheme cannot cryptographically distinguish a genuine 009 from a captured 000 with
-- code rewritten — is an EXTERNAL gap: it needs 9Pay to sign `code`, plus the IPN-edge IP
-- allowlist. Recorded in outputs/agent/fix_t5-webhook-ipn-integrity_2026-07-28.md.)
--
-- event_key is NULLABLE and left NULL for rows that must never block a later genuine
-- delivery: signature failures (9Pay retries after our 400) and replays we audited but
-- did not apply. Both PostgreSQL and H2 allow multiple NULLs under a UNIQUE constraint,
-- which is exactly the "unique when present" semantics wanted here.
--
-- applied  = did this event actually mutate np_payouts (vs audited-only)?
-- reject_reason = why not, when it did not (DUPLICATE / STALE_ORDER / STATUS_REGRESSION /
--                 SIGNATURE_INVALID / UNKNOWN_REQUEST_ID) — the forensic trail for a
--                 replay attempt.
--
-- Portable DDL: runs on PostgreSQL (production) and H2 in PostgreSQL mode (slice tests).

ALTER TABLE np_ipn_events ADD COLUMN event_key VARCHAR(64);

-- The IPN's own SIGNED timestamp (Y-m-d H:i:s, GMT+7). Previously only inside raw_payload;
-- promoted to a column because the staleness rule orders events by it — a 000 replayed
-- after a later 009 must lose, and only the signed timestamp is trustworthy for that.
ALTER TABLE np_ipn_events ADD COLUMN scheme_created_at VARCHAR(20);

ALTER TABLE np_ipn_events ADD COLUMN applied BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE np_ipn_events ADD COLUMN reject_reason VARCHAR(40);

-- The replay guard itself: at most ONE applied event per 9Pay event identity.
ALTER TABLE np_ipn_events
    ADD CONSTRAINT uq_np_ipn_events_event_key UNIQUE (event_key);

-- Ordering lookups for the monotonic-status / staleness rules (latest applied event for a
-- request_id) and for ops review of rejected replays.
CREATE INDEX idx_np_ipn_events_request_applied ON np_ipn_events (request_id, applied);
