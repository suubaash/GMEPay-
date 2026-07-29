-- scheme-adapter-sendmn: SendMN (Mongolia) QR scheme state.
--
-- PostgreSQL 16 in production; H2 in PostgreSQL mode for local unit slices —
-- the DDL below is plain SQL valid on both (same convention as scheme-adapter-zeropay).
--
-- smn_fx_rates: buy rates SendMN registers by calling OUR partner-hosted endpoint
-- (POST /partner-hosted/fx-rate). The latest rate per currency pair drives Confirm's
-- FX_USD_BUY_RATE and SETTLEMENT_AMOUNT (= LOCAL_PAYMENT_AMOUNT / rate, scale 4) —
-- SendMN re-verifies server-side and rejects mismatches with error 307.
--
-- smn_payments: one row per payment attempt, keyed by the partner-generated
-- TX_TOKEN_NO (UNIQUE — the scheme idempotency key across VerifyQr/Confirm/
-- PaymentStatus; SendMN rejects replays with error 304).

CREATE TABLE smn_fx_rates (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    fx_ticker_no        VARCHAR(64)   NOT NULL,   -- SendMN-generated unique rate registration id
    notice_date         VARCHAR(32)   NOT NULL,   -- raw NOTICE_DATE string (format unconfirmed w/ SendMN)
    rate                NUMERIC(18,6) NOT NULL,   -- MNT per USD buy rate (e.g. 3373.000000)
    local_cur_code      VARCHAR(3)    NOT NULL,   -- 'MNT'
    settlement_cur_code VARCHAR(3)    NOT NULL,   -- 'USD'
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_smn_fx_rates PRIMARY KEY (id),
    CONSTRAINT uq_smn_fx_rates_ticker UNIQUE (fx_ticker_no)
);

CREATE INDEX idx_smn_fx_rates_pair ON smn_fx_rates (local_cur_code, settlement_cur_code, notice_date);

CREATE TABLE smn_payments (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    tx_token_no         VARCHAR(64)   NOT NULL,   -- partner-generated idempotency key (e.g. SMN20261027...)
    qr_code             VARCHAR(1024),            -- raw scanned EMVCo MPM static QR payload
    merchant_id         VARCHAR(64),              -- from VerifyQr (GUID)
    merchant_name       VARCHAR(128),
    local_cur_code      VARCHAR(3)    NOT NULL,   -- 'MNT'
    local_amount        NUMERIC(18,2),            -- MNT amount (Decimal(18,2) on the wire)
    fx_ticker_no        VARCHAR(64),              -- registered rate used at Confirm time
    fx_usd_buy_rate     NUMERIC(18,6),
    settlement_cur_code VARCHAR(3),               -- 'USD'
    settlement_amount   NUMERIC(18,4),            -- USD = local_amount / rate (Decimal(18,4) on the wire)
    status              VARCHAR(16)   NOT NULL,   -- VERIFIED|PENDING|APPROVED|REJECTED|UNKNOWN (never auto-fail)
    payment_no          VARCHAR(64),              -- SendMN API tracking number
    payment_receipt_no  VARCHAR(64),              -- SendMN control number (wire: PAYMENT_RECIPT_NO [sic])
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_smn_payments PRIMARY KEY (id),
    CONSTRAINT uq_smn_payments_tx_token UNIQUE (tx_token_no),
    CONSTRAINT ck_smn_payments_status CHECK (status IN ('VERIFIED', 'PENDING', 'APPROVED', 'REJECTED', 'UNKNOWN'))
);

CREATE INDEX idx_smn_payments_status ON smn_payments (status);
