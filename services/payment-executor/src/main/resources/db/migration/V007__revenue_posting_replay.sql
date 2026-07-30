-- payment-executor: make revenue_posting_failures DRAINABLE (gap T2-5 / CFO#6).
--
-- V005 created this table so a swallowed revenue-ledger posting stopped being only a log line, and said
-- so in its own header: "nothing in payment-executor drains this table yet ... draining them is the next
-- step". It never happened, so booked revenue could sit here forever and be permanently missing from the
-- ledger. This migration adds exactly what a bounded, backed-off, idempotent replay needs, and nothing
-- else -- the replay identity stays (reference, posting_type) and the existing `attempts` counter stays
-- the bound.
--
-- Three columns and one new status:
--   * next_attempt_at -- when the replay job may next touch this row. NULL means "as soon as possible".
--     The backoff lives in a column rather than in the job so a restart cannot reset every row's
--     schedule and re-storm a revenue-ledger that is still down.
--   * last_attempt_at -- when the replay job last called revenue-ledger for this row (distinct from
--     updated_at, which also moves when the HOT path re-records the same failure).
--   * replayed_at     -- when it actually landed. Kept separate from status so "replayed how long
--     after the payment?" is answerable, which is the finance-facing question.
--   * status POISON   -- attempts exhausted (or the row is unreplayable, e.g. no payload was captured).
--     Deliberately NOT the same as ABANDONED: ABANDONED is an operator decision, POISON is the machine
--     giving up and it ALERTS. A POISON row is never retried again, so it cannot hammer a broken
--     downstream forever, and it is never silently forgotten either.
--
-- PostgreSQL 16 in production; H2 in PostgreSQL mode for unit slices. Portable syntax only, consistent
-- with V001-V006.

ALTER TABLE revenue_posting_failures ADD COLUMN last_attempt_at TIMESTAMP;
ALTER TABLE revenue_posting_failures ADD COLUMN next_attempt_at TIMESTAMP;
ALTER TABLE revenue_posting_failures ADD COLUMN replayed_at     TIMESTAMP;

-- Widen the status domain. The constraint is replaced rather than added to, because a CHECK cannot be
-- extended in place; IF EXISTS keeps this migration re-runnable against a hand-patched database.
ALTER TABLE revenue_posting_failures DROP CONSTRAINT IF EXISTS ck_revenue_posting_failures_status;
ALTER TABLE revenue_posting_failures ADD CONSTRAINT ck_revenue_posting_failures_status
    CHECK (status IN ('PENDING', 'REPLAYED', 'ABANDONED', 'POISON'));

-- Any row already sitting here predates the replay job and is due immediately: it has been waiting
-- since V005 shipped, which is the whole point of the gap.
UPDATE revenue_posting_failures
   SET next_attempt_at = created_at
 WHERE status = 'PENDING'
   AND next_attempt_at IS NULL;

-- The replay job's working set: PENDING rows whose backoff has elapsed, oldest first. Replaces the
-- V005 (status, created_at) index as the hot query; that one is kept for the ops "what is outstanding"
-- read, which orders by age rather than by due time.
CREATE INDEX idx_revenue_posting_failures_due
    ON revenue_posting_failures (status, next_attempt_at);
