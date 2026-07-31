-- V001 - ops-partner-bff's own ops-alert read model (gap T3-3 consumer half; replica ceiling).
--
-- THIS IS THE FIRST TABLE THIS SERVICE HAS EVER OWNED, and that is a deliberate, narrow exception
-- to the BFF's "owns no data" rule (see services/ops-partner-bff/build.gradle). The BFF is still a
-- pure aggregator of upstream services for every other surface; what it owns here is only the state
-- that is BORN in this service and has nowhere else to live:
--
--   * the ops-alert read model -- alerts arrive on gmepay.ops.alert, and the paging record + the
--     operator ACK are produced here and by nothing else;
--   * the operator-action audit trail (V002).
--
-- WHY A TABLE AND NOT REDIS. The store this replaces was a 200-entry per-JVM ArrayDeque, which
-- capped this service at ONE replica: each replica held only the alerts its own Kafka consumer
-- received, so GET /v1/admin/ops/alerts answered differently per replica and an ACK recorded on A
-- was invisible on B (whose escalation sweep therefore kept escalating an alert a human had already
-- acknowledged). Redis was rejected for a reason that is about SHAPE, not effort: update(seq, ...)
-- is a read-modify-write over a row with two independently-written fields -- the paging stamp
-- (Kafka thread + escalation sweep) and the ack (request thread) -- so on a Redis hash it needs
-- WATCH/Lua optimistic concurrency, or an ack silently overwrites a concurrent paging stamp and the
-- alert displays as never-paged. Add seq allocation, eviction and filtered newest-first queries and
-- what is being described is this table. Here the same update is one SELECT ... FOR UPDATE inside a
-- transaction, and restart durability + an ack audit trail come for free.
--
-- MIRRORS payment-executor's ops_alerts (its V006, gap T3-3) rather than inventing a second shape:
-- same alert_type / severity / subject_ref / detail / occurred_at core, same notify-outcome-on-the-
-- same-row idea (here paging_*), same "bounded reads + a retention pruner" contract. The columns
-- differ only where the two sides genuinely differ: payment-executor is an EMITTER (it owns
-- occurred_at as a real instant from its own clock and can constrain severity), while this is a
-- CONSUMER of arbitrary producers.
--
-- Engine-neutral (PostgreSQL 16 in production, H2 in PostgreSQL mode for unit slices): BIGSERIAL /
-- VARCHAR / TEXT / TIMESTAMP only, exactly as payment-executor V006 and transaction-mgmt V013.

CREATE TABLE IF NOT EXISTS ops_alerts (
    -- The client-visible alert id, and the reason this is the fix rather than a workaround: it used
    -- to be a per-JVM AtomicLong starting at 1 on every replica and every restart, so at N>1
    -- POST /v1/admin/ops/alerts/{id}/ack could acknowledge a DIFFERENT alert than the operator
    -- clicked. One database sequence makes the id fleet-wide unique.
    seq             BIGSERIAL     PRIMARY KEY,
    alert_type      VARCHAR(64)   NOT NULL,
    severity        VARCHAR(16)   NOT NULL,
    subject_ref     VARCHAR(128),
    detail          TEXT,
    -- STORED VERBATIM AS TEXT, NOT AS A TIMESTAMP, and that is deliberate. This is the producer's
    -- own occurredAt string off the wire. Not every producer sends an ISO-8601 instant (the
    -- escalation sweep has always carried an explicit non-ISO fallback path), so parsing it here
    -- would either reject an alert or silently rewrite what the producer said. An alert must never
    -- be lost to a format disagreement; ordering and retention use the columns below instead.
    occurred_at     VARCHAR(64),
    -- Our clock, when the row was written. Ordering tiebreak + the retention pruner's cutoff.
    created_at      TIMESTAMP     NOT NULL,
    -- The paging delivery record for THIS alert, so "did anyone get paged?" is answerable from the
    -- same row as the alert (the shape payment-executor's notify_* columns established).
    -- paging_status is NULL until a paging decision is made; DELIVERED | FAILED | SUPPRESSED.
    paging_status   VARCHAR(16),
    paging_channel  VARCHAR(64),
    paging_attempts INT           NOT NULL DEFAULT 0,
    paging_last_at  VARCHAR(64),
    paging_detail   VARCHAR(1024),
    -- The operator acknowledgement. ack_at non-NULL == acknowledged == escalation stops. Previously
    -- this lived only in one replica's heap, which is why an acked alert kept being escalated.
    ack_operator    VARCHAR(128),
    ack_note        VARCHAR(1024),
    ack_at          VARCHAR(64)
);

-- NO CHECK CONSTRAINT ON severity, unlike payment-executor's ops_alerts. There it is right: that
-- service EMITS the alert and owns the vocabulary. Here we consume whatever any producer publishes,
-- and a CHECK would turn an unexpected severity spelling into a REJECTED INSERT -- i.e. a dropped
-- alert, on the path whose entire purpose is that alerts stop being dropped. The read filters are
-- case-insensitive and unknown severities simply never match a filter.

-- The ops query pattern: newest-first (seq DESC == insertion order, which is what the deque gave),
-- optionally narrowed by severity and/or type. seq is included in the index so the ordering is
-- served by the index rather than a sort.
CREATE INDEX IF NOT EXISTS ix_ops_alerts_seq_desc        ON ops_alerts (seq DESC);
CREATE INDEX IF NOT EXISTS ix_ops_alerts_severity        ON ops_alerts (severity, seq DESC);
CREATE INDEX IF NOT EXISTS ix_ops_alerts_type_severity   ON ops_alerts (alert_type, severity, seq DESC);
-- The retention pruner's range delete.
CREATE INDEX IF NOT EXISTS ix_ops_alerts_created_at      ON ops_alerts (created_at);
