-- payment-executor: sandbox End-to-End (E2E) payment test runner store.
--
-- Two tables backing the /v1/sandbox/e2e runner: one row per run, one row per ordered
-- step within a run. The runner drives the REAL wire path (POST /v1/pay/classify,
-- POST /v1/pay over loopback) and persists each run + steps so they can be reviewed later.
--
-- PostgreSQL 16 in production; H2 in PostgreSQL mode for unit slices. Types kept portable:
-- BIGSERIAL / TEXT / NUMERIC / TIMESTAMPTZ, all understood by H2's PostgreSQL mode.

CREATE TABLE sandbox_e2e_run (
    id           BIGSERIAL     PRIMARY KEY,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    country      TEXT,
    partner      TEXT,
    amount       NUMERIC,
    currency     TEXT,
    mpm_type     TEXT,
    status       TEXT,
    failed_step  TEXT,
    step_count   INT
);

CREATE TABLE sandbox_e2e_step (
    id           BIGSERIAL     PRIMARY KEY,
    run_id       BIGINT        NOT NULL REFERENCES sandbox_e2e_run(id),
    seq          INT,
    name         TEXT,
    status       TEXT,
    detail       TEXT,
    latency_ms   BIGINT,
    http_status  INT
);

CREATE INDEX idx_sandbox_e2e_step_run ON sandbox_e2e_step (run_id);
