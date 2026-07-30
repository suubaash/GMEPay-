-- V004: transaction_screening — the per-transaction, per-PARTY record of what was screened on the
-- payment path, by whom, when, and what the payment path did about it. Gap T5-3.
--
-- WHY
-- ---
-- V001/V002 record ONBOARDING screening: one row per KYB run against a partner entity and its UBOs,
-- keyed by the provider's deterministic ref. That is a different duty from the one this table serves.
-- The CISO audit found that nothing anywhere screens the parties to a PAYMENT — a repo-wide grep of
-- payment-executor, smart-router and transaction-mgmt for sanctions/screening/watchlist/PEP returned no
-- consumer at all, only the four numeric "AML cumulative cap" comparisons, which screen nobody. So the
-- question "was the beneficiary of transaction TXN-123 checked against a sanctions list" had no row to
-- answer it, in any table, in any service.
--
-- ONE ROW PER (txn_ref, party). Screening is a duty owed per PARTY, not per transaction: a regulator
-- asks whether the originator was screened AND whether the beneficiary was screened, and an institution
-- that screened one of them has not discharged the obligation. A single per-transaction row would make
-- "we screen the merchant but never the payer" and "we screen nobody" the same undifferentiated state.
--
-- WHAT THIS TABLE IS NOT
-- ----------------------
-- It is not a control and it does not make one exist. With no vendor wired (the platform's current
-- state, ADR-014) every row this table takes says NOT_SCREENED_NO_PROVIDER, and that is the point: the
-- absence becomes a countable, queryable, dated fact instead of an unexamined assumption. It also
-- contains no thresholds, scores or rules — those are compliance policy and none are invented here.
--
-- NO SUBJECT PII
-- --------------
-- There is deliberately no name, date-of-birth, address or nationality column. Those are precisely the
-- values this platform does not encrypt at rest (gap T5-5), and a screening-evidence table full of
-- payer identities would be a new PII store created by a control meant to reduce risk. What is recorded
-- is subject_attributes: WHICH attributes the party carried, never their values (see
-- PaymentScreeningSubject.attributeSummary). That is also what answers the regulator's real question —
-- what the provider was able to see.
--
-- THE HONESTY INVARIANT IS IN THE TYPE, NOT ONLY IN A CHECK
-- ---------------------------------------------------------
-- A CHECK constraint can stop a bad INSERT; it cannot stop a bad READ. The rule that a CLEAR without
-- authoritative provenance is not a clean result is therefore enforced in
-- com.gme.pay.kyb.TransactionScreeningEvidence's constructor, which is the only way a row is built AND
-- the only way a row is read back — so a row that a migration, a backfill or a hand-edit left as
-- (status='CLEAR', provider_authoritative=FALSE) comes back out as NOT_SCREENED_NO_PROVIDER rather than
-- as a completed check. The CHECK below is the belt for the write path; the type is the braces for
-- both. Note there is NO completed_screening column: that property is derived on read, precisely so
-- there is no column anyone can flip.
--
-- H2 (MODE=PostgreSQL) + PostgreSQL portable, matching V001/V002. No vendor split in this module.

CREATE TABLE transaction_screening (
    id                     BIGSERIAL    PRIMARY KEY,

    -- The transaction whose parties these are. Also the audit_log aggregate_id, so one transaction's
    -- screening history is one hash chain.
    txn_ref                VARCHAR(64)  NOT NULL,

    -- The partner whose traffic it is. NULL on a wallet payment, which has no partner.
    partner_id             VARCHAR(32),

    -- PAYER | BENEFICIARY | MERCHANT (com.gme.pay.kyb.PaymentParty). Deliberately excludes the partner
    -- institution itself — that subject is screened at onboarding on a different path (V001/V002 plus
    -- config-registry's activation gate), and conflating the two is the confusion T5-3 removes.
    party                  VARCHAR(16)  NOT NULL,

    -- Opaque correlation handle for the party (wallet user ref / partner customer_ref). NOT an identity:
    -- no sanctions, PEP or adverse-media list is keyed by a counterparty's internal customer id, so a
    -- provider handed only this can only ever answer "not found".
    subject_reference      VARCHAR(128),

    -- WHICH attributes the party carried, never their values. See the PII note above.
    subject_attributes     VARCHAR(256),

    -- Who answered: 'none' (no provider is wired), 'stub', 'unknown' (a producer that did not declare
    -- itself), or a real vendor id. Never blank.
    provider_id            VARCHAR(32)  NOT NULL,

    -- TRUE only when a real provider actually consulted sanctions/PEP/adverse-media sources. Defaults
    -- FALSE: an unstated provenance is never treated as authority.
    provider_authoritative BOOLEAN      NOT NULL DEFAULT FALSE,

    -- CLEAR | HIT | NEEDS_REVIEW | NOT_SCREENED_NO_PROVIDER (ScreeningResult.Status).
    status                 VARCHAR(32)  NOT NULL,

    -- Why this is not a completed screening. NOT NULL whenever provider_authoritative is FALSE.
    caveat                 VARCHAR(512),

    -- NO_PROVIDER | NO_SUBJECT_IDENTITY | PROVIDER_ERROR | PROVIDER_NOT_AUTHORITATIVE
    -- (com.gme.pay.kyb.UnscreenedReason). NULL on a completed screening. Kept as its own column rather
    -- than parsed out of the caveat because the four causes have four different owners and four
    -- different fixes, and "how many payments went through unscreened, and why" must be one GROUP BY.
    unscreened_reason      VARCHAR(32),

    -- What the payment path did: PROCEED_NOT_REQUIRED | PROCEED_SCREENED_CLEAR |
    -- PROCEED_UNAVAILABLE_OVERRIDDEN | REFUSE_SCREENING_UNAVAILABLE | REFUSE_SCREENING_HIT |
    -- REFUSE_SCREENING_NEEDS_REVIEW (TransactionScreeningPolicy.Posture). Stored because the outcome and
    -- the decision are different facts: the same NOT_SCREENED_NO_PROVIDER status proceeds when no party
    -- is required (the shipped default) and refuses when one is.
    posture                VARCHAR(48)  NOT NULL,

    -- When the PROVIDER answered (its own completion instant), and when WE wrote the row. Two columns
    -- because a cached or asynchronous vendor answer can predate the payment by a long way, and a
    -- reviewer needs to know the age of the verdict that was relied on.
    screened_at            TIMESTAMP    NOT NULL,
    recorded_at            TIMESTAMP    NOT NULL,

    -- Write-path belt for the read-path invariant enforced in TransactionScreeningEvidence: a CLEAR is
    -- only insertable with authoritative provenance, and a non-authoritative row must say why.
    CONSTRAINT chk_txn_screening_clear_is_authoritative
        CHECK (status <> 'CLEAR' OR provider_authoritative = TRUE),
    CONSTRAINT chk_txn_screening_caveat_required
        CHECK (provider_authoritative = TRUE OR caveat IS NOT NULL),
    -- 'stub' and 'none' can never be an authority — the same rule ScreeningProvenance enforces in Java.
    CONSTRAINT chk_txn_screening_reserved_provider
        CHECK (provider_authoritative = FALSE OR provider_id NOT IN ('stub', 'none', 'unknown'))
);

-- One row per (transaction, party). A re-screen of the same party on the same transaction UPDATEs in
-- place rather than appending, so "was this party screened" has exactly one answer; the append-only
-- history of how that answer changed lives in audit_log, which is hash-chained and this table is not.
CREATE UNIQUE INDEX ux_txn_screening_txn_party
    ON transaction_screening (txn_ref, party);

-- "How many payments went through unscreened, why, and for how long" — the coverage question, per
-- partner and over a window.
CREATE INDEX idx_txn_screening_coverage
    ON transaction_screening (partner_id, unscreened_reason, recorded_at);

-- Recent-activity scan for the ops surface.
CREATE INDEX idx_txn_screening_recorded_at
    ON transaction_screening (recorded_at DESC);
