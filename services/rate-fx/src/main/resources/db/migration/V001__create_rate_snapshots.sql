-- rate-fx: treasury cost-rate snapshots captured at quote-issuance time (RATE-04 §3.2).
-- Convention: usd_rate = units of currency_code per 1 USD.
-- PostgreSQL 16 in production; H2 in PostgreSQL mode for unit slices.
-- Rate columns are NUMERIC(20,8) per docs/MONEY_CONVENTION.md — never float/double.
-- Applied migrations are immutable (checksum-stable): never edit, add a new version.

CREATE TABLE rate_snapshots (
    snapshot_id   VARCHAR(64)   NOT NULL,
    currency_code VARCHAR(3)    NOT NULL,
    usd_rate      NUMERIC(20,8) NOT NULL,
    source        VARCHAR(16)   NOT NULL,
    effective_at  TIMESTAMP     NOT NULL,
    captured_at   TIMESTAMP     NOT NULL,
    CONSTRAINT pk_rate_snapshots PRIMARY KEY (snapshot_id),
    CONSTRAINT ck_rate_snapshots_source CHECK (source IN ('IDENTITY', 'LIVE', 'MANUAL', 'PARTNER')),
    CONSTRAINT ck_rate_snapshots_rate_positive CHECK (usd_rate > 0)
);

-- LIVE resolution looks up the latest snapshot per currency with effective_at <= now.
CREATE INDEX idx_rate_snapshots_ccy_effective ON rate_snapshots(currency_code, effective_at);
