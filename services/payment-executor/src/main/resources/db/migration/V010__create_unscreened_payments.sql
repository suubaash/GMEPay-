-- payment-executor: DURABLE, QUERYABLE record of payments that went through WITHOUT a sanctions/PEP
-- screening of their counterparties (gap T5-3 / CISO major "No AML transaction monitoring, and no real
-- sanctions/PEP screening in the payment path").
--
-- WHY THIS TABLE IS AN AGGREGATE AND NOT A ROW-PER-PAYMENT
-- --------------------------------------------------------
-- The question this must answer is the regulator's first question: "how many payments did you process
-- without screening the parties, and why?". That is a COUNT, and a count is what this table stores --
-- one row per (UTC date, cause, party role, provider) with a monotonically incremented counter, plus
-- the first/last occurrence timestamps and payment references as evidence anchors.
--
-- A row per payment was considered and deliberately rejected here: today EVERY payment is unscreened,
-- so a per-payment table would be an exact, ever-growing duplicate of the transaction table carrying no
-- information the aggregate does not, while adding a second insert to the hot authorize path. When a
-- real provider is wired the per-payment decision record becomes genuinely necessary -- and it belongs
-- in THAT provider's decision log with the vendor's own reference, screened attributes and analyst
-- disposition (see Documentation/GAP_REGISTER.md T5-3 and outputs/agent/fix_t5-aml-seam_2026-07-28.md
-- for the handoff). This table is the honest minimum that makes the current gap measurable instead of
-- unknown; it is not a substitute for that log and its header says so.
--
-- WHAT A ROW MEANS
-- ----------------
-- One row = "on this UTC date, N payments were accepted in which the <party_role> party was not
-- screened, because <reason_code>". A row's existence is a finding, not a success record. Reading this
-- table empty means one of two OPPOSITE things and the caller must know which: either every party was
-- screened by an authoritative provider, or the gate is not wired / the service never took a payment.
-- GET /internal/ops/screening-coverage answers that by returning the configured provider and its
-- authoritativeness ALONGSIDE the counts, so an empty result can never be read as coverage.
--
-- reason_code is the UnscreenedReason roster (libs/lib-kyb) and is CHECK-constrained so a future
-- reason cannot be silently written as free text:
--   NO_PROVIDER               -- no provider configured (the platform default; owner = compliance/vendor)
--   NO_SUBJECT_IDENTITY       -- the payment carried no screenable identity (no name) for this party;
--                                owner = the API contract. This is the reason a vendor purchase alone
--                                would NOT produce coverage: payment-executor never receives a payer name.
--   PROVIDER_ERROR            -- a configured provider failed to answer (owner = operations, on-call)
--   PROVIDER_NOT_AUTHORITATIVE-- it answered but not authoritatively (owner = whoever wired it)
--
-- party_role is the PaymentParty roster (PAYER / BENEFICIARY / MERCHANT). Screening is owed per party,
-- so "we screen the merchant but never the payer" must be a visibly different state from "we screen
-- nobody" -- hence the role in the key rather than a single undifferentiated counter.
--
-- NO PII HERE, ON PURPOSE
-- -----------------------
-- No names, no dates of birth, no national ids -- only counts, opaque payment references and the
-- partner code. This platform has zero column encryption (gap T5-5) and no log aggregation (T3-2), so a
-- coverage table that accumulated counterparty names would create a new plaintext PII store as a side
-- effect of measuring a compliance gap. Presence-vs-absence of an attribute is recorded in the
-- aggregate's reason_code; the values are not recorded at all.
--
-- Retention: NONE. Nothing prunes this table, unlike ops_alerts (90 days). Deliberate: these counts are
-- the evidence base for a period a regulator may examine years later, they are tiny (a few rows per
-- day, bounded by reason x role x provider), and deleting them would destroy the only record that the
-- gap existed and how large it was. Note this is a gap-measurement record, not an audit-trail control:
-- it is not hash-chained (ADR-007 / lib-audit) and T1-6's WORM/INSERT-only role question applies here
-- exactly as it does to audit_log.
--
-- PostgreSQL 16 in production; H2 in PostgreSQL mode for the unit slices. Portable types only
-- (BIGSERIAL / VARCHAR / BIGINT / DATE / TIMESTAMP), consistent with V001-V009. No vendor-specific
-- upsert: the increment is done in JPA (UnscreenedPaymentCounter) precisely so this file stays portable.

CREATE TABLE unscreened_payments (
    id             BIGSERIAL     PRIMARY KEY,
    -- UTC date the payments were accepted. UTC, not a business date: this is a coverage measure, not a
    -- settlement figure, and it must not shift with a corridor's local calendar.
    gap_date       DATE          NOT NULL,
    -- Why the party was not screened (UnscreenedReason).
    reason_code    VARCHAR(32)   NOT NULL,
    -- Which party was not screened (PaymentParty).
    party_role     VARCHAR(16)   NOT NULL,
    -- The screening provider in force when this was counted; 'none' when no provider is configured.
    -- Kept in the key so a period that spans a vendor cutover does not blur into one row.
    provider_id    VARCHAR(64)   NOT NULL,
    -- How many payments. Incremented, never overwritten.
    payment_count  BIGINT        NOT NULL DEFAULT 0,
    -- Evidence anchors: when the condition was first and last seen on this date, and the opaque payment
    -- reference at each end (partner_txn_ref where the entry point has one; NULL where it does not --
    -- the wallet POST /v1/pay carries no caller-supplied reference at gate time, which is itself a
    -- finding recorded in the fix report).
    first_seen_at  TIMESTAMP     NOT NULL,
    last_seen_at   TIMESTAMP     NOT NULL,
    first_payment_ref VARCHAR(128),
    last_payment_ref  VARCHAR(128),
    -- Partner code the traffic arrived under, when known ('unknown' otherwise) -- lets an operator see
    -- whether the exposure is concentrated in one corridor.
    partner_ref    VARCHAR(128)  NOT NULL DEFAULT 'unknown',
    CONSTRAINT ck_unscreened_payments_reason
        CHECK (reason_code IN ('NO_PROVIDER', 'NO_SUBJECT_IDENTITY', 'PROVIDER_ERROR',
                              'PROVIDER_NOT_AUTHORITATIVE')),
    CONSTRAINT ck_unscreened_payments_party
        CHECK (party_role IN ('PAYER', 'BENEFICIARY', 'MERCHANT')),
    -- A negative or zero-defaulted count that never got incremented would understate the gap; the
    -- counter only ever writes >= 1.
    CONSTRAINT ck_unscreened_payments_count
        CHECK (payment_count >= 0)
);

-- The identity of an aggregate row. UNIQUE so the increment path is race-safe: two concurrent payments
-- either both find the row and both increment it under the UPDATE, or the insert loser is rejected here
-- and retries the UPDATE. Without this, a race would silently create two rows and the coverage figure
-- would still be right in SUM() -- but the retry logic in UnscreenedPaymentCounter relies on the
-- constraint to detect the race at all.
CREATE UNIQUE INDEX ux_unscreened_payments_key
    ON unscreened_payments (gap_date, reason_code, party_role, provider_id, partner_ref);

-- The two read patterns: "what happened recently" and "total exposure for a period, by cause".
CREATE INDEX idx_unscreened_payments_date ON unscreened_payments (gap_date DESC);
CREATE INDEX idx_unscreened_payments_reason ON unscreened_payments (reason_code, gap_date DESC);
