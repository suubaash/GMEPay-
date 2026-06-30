-- qr-service: CPM prepare-session persistence (WBS 5.3-T01, scoped to this service's own DB).
-- Tracks the life of one CPM QR token issued by /v1/qr/cpm/generate: ISSUED -> SCANNED ->
-- COMPLETED/EXPIRED/FAILED. Prefunding reservation + scheme-side state are owned by other
-- services (INTEGRATION REQUEST); qr-service persists the token contract it issues.
-- PostgreSQL 16 in production / Testcontainers ITs; H2 in PostgreSQL mode for unit slices.

CREATE TABLE cpm_prepare_session (
    cpm_token_id          VARCHAR(64)              NOT NULL,
    payment_id            VARCHAR(64)              NOT NULL,
    scheme_id             VARCHAR(32)              NOT NULL,
    direction             VARCHAR(10)              NOT NULL,
    country_code          VARCHAR(2)               NOT NULL,
    customer_ref          VARCHAR(255)             NOT NULL,
    partner_txn_ref       VARCHAR(64)              NOT NULL,
    prepare_token         VARCHAR(128)             NOT NULL,
    qr_content            VARCHAR(512)             NOT NULL,
    status                VARCHAR(20)              NOT NULL,
    scheme_issued         BOOLEAN                  NOT NULL,
    prefund_reserved_usd  NUMERIC(20,8),
    issued_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_cpm_prepare_session PRIMARY KEY (cpm_token_id),
    CONSTRAINT uq_cpm_payment_id UNIQUE (payment_id),
    CONSTRAINT uq_cpm_partner_txn_ref UNIQUE (partner_txn_ref),
    CONSTRAINT ck_cpm_status CHECK (status IN ('ISSUED','SCANNED','COMPLETED','EXPIRED','FAILED'))
);

CREATE INDEX idx_cpm_status_expires ON cpm_prepare_session(status, expires_at);
CREATE INDEX idx_cpm_partner_txn_ref ON cpm_prepare_session(partner_txn_ref);
