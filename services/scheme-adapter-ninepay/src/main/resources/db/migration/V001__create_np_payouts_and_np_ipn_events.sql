-- scheme-adapter-ninepay: payout idempotency registry + IPN audit trail.
--
-- np_payouts is the local source of truth for every 9Pay disbursement this adapter has
-- attempted. The UNIQUE request_id is the idempotency spine: 9Pay treats request_id as
-- globally unique (error 1062 on reuse) and transfers CANNOT be cancelled once submitted,
-- so a request_id must never be reused for a different payment. On an ambiguous timeout
-- the row stays SUBMITTED/UNKNOWN until a /service/transfer/info poll resolves it.
--
-- np_ipn_events records every inbound 9Pay IPN push verbatim (raw payload + whether the
-- RSA signature verified), including reversal events (code 009) that flip a SUCCESS
-- payout to REVERSED after the fact — the event row IS the reversal audit record.
--
-- VND amounts are integer (zero-decimal currency), stored NUMERIC(20,0) per
-- docs/MONEY_CONVENTION.md (never double/float/minor units). Portable DDL: runs on
-- PostgreSQL (production) and H2 in PostgreSQL mode (unit/slice tests).

CREATE TABLE np_payouts (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    -- Partner-generated idempotency key, Str(50), recommended PartnerID+9P+YYYYMMDD+UniqueId.
    request_id          VARCHAR(50)   NOT NULL,
    -- 9Pay transaction code (Str 20); null until 9Pay has accepted the transfer.
    transaction_id      VARCHAR(20),
    bank_no             VARCHAR(20)   NOT NULL,
    account_no          VARCHAR(22)   NOT NULL,
    account_type        SMALLINT      NOT NULL DEFAULT 0,  -- 0=bank account, 1=bank card
    account_name        VARCHAR(164)  NOT NULL,
    amount_vnd          NUMERIC(20,0) NOT NULL,            -- requested amount, integer VND, min 2000
    fee_vnd             NUMERIC(20,0),                     -- 9Pay fee (from transfer response / IPN)
    -- What 9Pay debits from the prefunded balance. Fee-inclusion semantics are contradictory
    -- in the spec (4.2 vs 4.3, open issue O6) — informational only, no recon on it yet.
    transfer_amount_vnd NUMERIC(20,0),
    content             VARCHAR(150)  NOT NULL,            -- unaccented alphanumerics only
    sender_uid          VARCHAR(100),                      -- per-sender payout-limit key (2071-2073)
    status              VARCHAR(10)   NOT NULL,            -- PayoutStatus enum name
    last_ipn_code       VARCHAR(3),                        -- most recent IPN message code (000-009)
    scheme_message      VARCHAR(200),                      -- bank approval/failure message
    scheme_created_at   VARCHAR(20),                       -- 9Pay created_at (Y-m-d H:i:s, GMT+7)
    reversed_at         TIMESTAMP WITH TIME ZONE,          -- set when IPN code 009 lands
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_np_payouts PRIMARY KEY (id),
    CONSTRAINT uq_np_payouts_request_id UNIQUE (request_id),
    CONSTRAINT ck_np_payouts_status CHECK (status IN
        ('SUBMITTED', 'PENDING', 'PROCESSING', 'SUCCESS', 'FAILED', 'HELD', 'REVERSED', 'UNKNOWN')),
    CONSTRAINT ck_np_payouts_account_type CHECK (account_type IN (0, 1)),
    -- 9Pay minimum transfer is 2,000 VND (also validated in the adapter before submit).
    CONSTRAINT ck_np_payouts_min_amount CHECK (amount_vnd >= 2000)
);

-- Reconciliation / ops queries scan by status; IPN correlation looks up by transaction_id.
CREATE INDEX idx_np_payouts_status ON np_payouts (status);
CREATE INDEX idx_np_payouts_transaction_id ON np_payouts (transaction_id);

CREATE TABLE np_ipn_events (
    id              BIGINT GENERATED ALWAYS AS IDENTITY,
    request_id      VARCHAR(50)  NOT NULL,   -- original payout request_id from the IPN
    trans_id        VARCHAR(20),             -- 9Pay txn code (IPN field name drift: trans_id)
    code            VARCHAR(3),              -- message code 000-009; NOT part of the signed string
    status          VARCHAR(50),             -- PENDING/PROCESSING/FAIL/SUCCESS as pushed
    raw_payload     TEXT         NOT NULL,   -- verbatim request body for audit/replay
    signature_valid BOOLEAN      NOT NULL,   -- result of RSA verify against 9Pay's public key
    received_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_np_ipn_events PRIMARY KEY (id)
);

CREATE INDEX idx_np_ipn_events_request_id ON np_ipn_events (request_id);
