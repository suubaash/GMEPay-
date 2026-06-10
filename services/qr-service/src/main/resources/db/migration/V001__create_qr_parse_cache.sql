-- qr-service: QR-parse result cache (17.2-G04).
-- PostgreSQL 16 in production / Testcontainers ITs; H2 in PostgreSQL mode for unit slices.
-- Keyed by SHA-256 hex of the raw payload (any byte change in the QR -> new cache row).
-- Money column encoded_amount is NUMERIC(20,8) per MONEY_CONVENTION.md (BigDecimal in Java).

CREATE TABLE qr_parse_cache (
    payload_hash     VARCHAR(64)              NOT NULL,
    raw_payload      VARCHAR(512)             NOT NULL,
    format_indicator INTEGER                  NOT NULL,
    currency_code    VARCHAR(3)               NOT NULL,
    merchant_name    VARCHAR(200)             NOT NULL,
    merchant_city    VARCHAR(100)             NOT NULL,
    mcc              VARCHAR(8)               NOT NULL,
    country_code     VARCHAR(2)               NOT NULL,
    mai_tag          INTEGER                  NOT NULL,
    merchant_id      VARCHAR(50)              NOT NULL,
    qr_code_id       VARCHAR(64)              NOT NULL,
    encoded_amount   NUMERIC(20,8),
    crc_verified     BOOLEAN                  NOT NULL,
    parsed_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_qr_parse_cache PRIMARY KEY (payload_hash)
);

CREATE INDEX idx_qr_parse_cache_qr_code_id ON qr_parse_cache(qr_code_id);
CREATE INDEX idx_qr_parse_cache_merchant_id ON qr_parse_cache(merchant_id);
