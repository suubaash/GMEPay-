-- rate-fx: issued rate quotes — the USD-pool outputs locked at quote time (RATE-04 §9).
-- TTL / rate-lock semantics live in the QuoteTtlStore (Redis key rq:{quote_id},
-- EXPIRE = quote TTL); this table is the durable audit copy of every issued quote.
-- All money/rate columns are NUMERIC(20,8) per docs/MONEY_CONVENTION.md — never float.
-- offer_rate_coll / cross_rate are nullable (NULL allowed for same-currency flows).
-- Applied migrations are immutable (checksum-stable): never edit, add a new version.

CREATE TABLE rate_quotes (
    quote_id              VARCHAR(64)   NOT NULL,
    collection_ccy        VARCHAR(3)    NOT NULL,
    settle_a_ccy          VARCHAR(3)    NOT NULL,
    settle_b_ccy          VARCHAR(3)    NOT NULL,
    payout_ccy            VARCHAR(3)    NOT NULL,
    target_payout         NUMERIC(20,8) NOT NULL,
    payout_usd_cost       NUMERIC(20,8) NOT NULL,
    collection_usd        NUMERIC(20,8) NOT NULL,
    collection_margin_usd NUMERIC(20,8) NOT NULL,
    payout_margin_usd     NUMERIC(20,8) NOT NULL,
    send_amount           NUMERIC(20,8) NOT NULL,
    collection_amount     NUMERIC(20,8) NOT NULL,
    offer_rate_coll       NUMERIC(20,8),
    cross_rate            NUMERIC(20,8),
    short_circuit         BOOLEAN       NOT NULL,
    created_at            TIMESTAMP     NOT NULL,
    expires_at            TIMESTAMP     NOT NULL,
    CONSTRAINT pk_rate_quotes PRIMARY KEY (quote_id)
);

-- Housekeeping / audit queries scan by expiry.
CREATE INDEX idx_rate_quotes_expires ON rate_quotes(expires_at);
