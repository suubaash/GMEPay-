-- payment-executor: DURABLE record of every ops alert this service emits
-- (gap T3-3 / COO#4 "alerting terminates in a 200-entry in-memory deque").
--
-- Why this table exists: DECLINE_SPIKE is one of only two production safety nets on the money path,
-- and until now the whole chain was volatile. The monitor published the alert onto the EventPublisher
-- seam, which in payment-executor is still LogEventPublisher (this service has no lib-events-kafka on
-- its classpath), so the alert became a single log line -- and there is no log aggregation anywhere in
-- the platform (gap T3-2). Even when a broker IS wired, the consumer side (ops-partner-bff's
-- OpsAlertStore) is an in-memory ArrayDeque capped at 200 entries that empties on every restart. So a
-- 3am decline spike left no queryable evidence whatsoever the next morning.
--
-- Every emitted alert is now written here FIRST, before it is published or pushed to a notification
-- sink, so the record survives a restart of this service, of the broker, and of the BFF. This is the
-- emitter-side half of T3-3; persisting the BFF's consumer-side read model is tracked separately
-- (that service was owned by another workstream when this landed).
--
-- Deliberately NOT a transactional outbox: payment-executor has no outbox and no Kafka publisher
-- today, and inventing that infrastructure here is register item T2-5. This is the minimum durable,
-- queryable record -- one row per emitted alert, read back through
-- GET /internal/ops/alerts (internal-token gated, bounded).
--
-- Retention: bounded by the pruner in OpsAlertArchive
-- (gmepay.ops.alerts.retention-days, default 90) -- alerts are small and low-volume (the monitor
-- has a per-subject cooldown), so 90 days of history costs little and covers a quarterly audit.
--
-- PostgreSQL 16 in production; H2 in PostgreSQL mode for unit slices. Portable types only
-- (BIGSERIAL / VARCHAR / TEXT / TIMESTAMP), consistent with V001-V005.

CREATE TABLE ops_alerts (
    id            BIGSERIAL     PRIMARY KEY,
    -- Alert classification, matching the OpsAlertPayload contract carried on gmepay.ops.alert.
    alert_type    VARCHAR(64)   NOT NULL,
    severity      VARCHAR(16)   NOT NULL,
    -- The dimension the spike was attributed to (partner code or scheme id); 'global' when unscoped.
    subject_ref   VARCHAR(128)  NOT NULL,
    detail        TEXT,
    -- When the monitor decided the alert fired (its clock), NOT when the row was written.
    occurred_at   TIMESTAMP     NOT NULL,
    created_at    TIMESTAMP     NOT NULL,
    -- Outcome of the outbound notification sink for THIS alert, so "did anyone get paged?" is
    -- answerable from the same row as the alert. PENDING is only ever seen mid-flight.
    notify_status VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    notify_channel VARCHAR(32),
    notify_error  VARCHAR(1024),
    CONSTRAINT ck_ops_alerts_severity
        CHECK (severity IN ('INFO', 'WARN', 'CRITICAL')),
    CONSTRAINT ck_ops_alerts_notify_status
        CHECK (notify_status IN ('PENDING', 'DELIVERED', 'FAILED', 'SKIPPED'))
);

-- The ops query pattern: newest-first, optionally narrowed by severity / type.
CREATE INDEX idx_ops_alerts_occurred_at ON ops_alerts (occurred_at DESC);
CREATE INDEX idx_ops_alerts_type_severity ON ops_alerts (alert_type, severity, occurred_at DESC);
