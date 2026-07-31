-- payment-executor: durable record of revenue-ledger postings that FAILED to reach revenue-ledger
-- (gap T2-1 / CFO#6 "ledger posting is fire-and-forget with no replay job").
--
-- Why this table exists: every revenue-ledger call on the commit path is deliberately non-blocking —
-- a ledger outage must never fail a payment that already moved money. Until now that meant a failed
-- POST was written to a log line and lost: the revenue for that transaction simply never existed, and
-- the only way to find it was log-mining. Each failure is now persisted here with the exact request
-- payload, so an ops job (or an operator re-POSTing the payload) can replay it. All revenue-ledger
-- endpoints are idempotent on their reference/txnRef, so a replay can never double-book.
--
-- This is intentionally NOT a transactional outbox / Kafka topic — payment-executor has neither today
-- (its event publisher is still LogEventPublisher) and inventing that infrastructure here is out of
-- scope. This is the minimum durable, replayable record; the proper outbox remains register item T2-5.
--
-- PostgreSQL 16 in production; H2 in PostgreSQL mode for unit slices. Portable types only
-- (BIGSERIAL / VARCHAR / TEXT / TIMESTAMP), consistent with V001-V004.

CREATE TABLE revenue_posting_failures (
    id            BIGSERIAL     PRIMARY KEY,
    -- The reference the posting was keyed on (txnRef for capture/residual/split, the cancelled
    -- reference for a reversal). This plus posting_type is the replay identity.
    reference     VARCHAR(128)  NOT NULL,
    posting_type  VARCHAR(32)   NOT NULL,
    -- The exact JSON request body that failed, so a replay needs no other source.
    payload       TEXT,
    -- Number of times this posting has been observed to fail (incremented on re-failure).
    attempts      INT           NOT NULL DEFAULT 1,
    last_error    VARCHAR(1024),
    status        VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    created_at    TIMESTAMP     NOT NULL,
    updated_at    TIMESTAMP     NOT NULL,
    -- One row per (reference, posting_type): repeated failures update the row rather than piling up,
    -- so the PENDING set is exactly "postings still missing from revenue-ledger".
    CONSTRAINT uq_revenue_posting_failures UNIQUE (reference, posting_type),
    CONSTRAINT ck_revenue_posting_failures_type
        CHECK (posting_type IN ('REVENUE_CAPTURE', 'ROUNDING_RESIDUAL',
                                'COMMISSION_SPLIT', 'REVERSAL_JOURNAL')),
    CONSTRAINT ck_revenue_posting_failures_status
        CHECK (status IN ('PENDING', 'REPLAYED', 'ABANDONED'))
);

-- The replay job's working set: oldest PENDING first.
CREATE INDEX idx_revenue_posting_failures_status
    ON revenue_posting_failures (status, created_at);
