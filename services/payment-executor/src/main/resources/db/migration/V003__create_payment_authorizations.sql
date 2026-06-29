-- V003: payment_authorizations holds the state of a two-phase payment between POST /v1/payments/authorize
-- and POST /v1/payments/{authId}/confirm (SETTLEMENT_FLOW_SPEC §7.1). At authorize we freeze the quote-derived
-- amounts + reserve the partner float; at confirm we capture + submit to the scheme. One row per authorization.
CREATE TABLE payment_authorizations (
    auth_id              VARCHAR(40)    NOT NULL,
    partner_id           BIGINT         NOT NULL,
    partner_code         VARCHAR(64),
    partner_type         VARCHAR(16)    NOT NULL,
    partner_txn_ref      VARCHAR(128)   NOT NULL,
    quote_id             VARCHAR(64)    NOT NULL,
    scheme_id            VARCHAR(32)    NOT NULL,
    direction            VARCHAR(16),
    merchant_qr          VARCHAR(512),
    customer_ref         VARCHAR(128),
    merchant_id          VARCHAR(64),
    merchant_name        VARCHAR(128),
    target_payout        NUMERIC(20, 8) NOT NULL,
    payout_currency      VARCHAR(3)     NOT NULL,
    collection_amount    NUMERIC(20, 8) NOT NULL,
    collection_currency  VARCHAR(3)     NOT NULL,
    collection_usd       NUMERIC(20, 8),
    collection_margin_usd NUMERIC(20, 8),
    payout_margin_usd    NUMERIC(20, 8),
    service_charge       NUMERIC(20, 8),
    merchant_fee_rate    NUMERIC(7, 4),
    reserved_usd         NUMERIC(20, 8),
    txn_ref              VARCHAR(64)    NOT NULL,
    payment_id           VARCHAR(64)    NOT NULL,
    status               VARCHAR(24)    NOT NULL,
    wallet_charge_ref    VARCHAR(128),
    created_at           TIMESTAMP      NOT NULL,
    expires_at           TIMESTAMP      NOT NULL,
    confirmed_at         TIMESTAMP,
    CONSTRAINT pk_payment_authorizations PRIMARY KEY (auth_id)
);

-- A given (partner, partner_txn_ref) authorizes at most once (idempotent authorize).
CREATE UNIQUE INDEX uq_authz_partner_txnref ON payment_authorizations (partner_id, partner_txn_ref);
