-- scheme-adapter-zeropay: GME's prepaid float held WITH the ZeroPay scheme.
--
-- Replaces the static sim-prepaid-balance config constant that the pre-submit balance-check
-- (SETTLEMENT_FLOW_SPEC §7.2) compared against. This is a REAL decrementing ledger: seeded from
-- an opening balance, credited on top-up, and DEBITED on every committed payout. The authorize
-- gate now declines once the running float is short — not only when a single payout exceeds a
-- fixed number. KRW amounts are NUMERIC(20,0) per docs/MONEY_CONVENTION.md (never minor units).
--
-- gme_scheme_balance holds the single running balance (one row, keyed by scheme_code).
-- gme_scheme_balance_entry is the append-only audit + idempotency journal: each OPENING / CREDIT /
-- DEBIT is recorded once, keyed uniquely by (scheme_code, entry_type, txn_ref) so a retried payout
-- or top-up never double-applies.

CREATE TABLE gme_scheme_balance (
    scheme_code   VARCHAR(16)   NOT NULL,
    currency      VARCHAR(3)    NOT NULL,
    balance       NUMERIC(20,0) NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_gme_scheme_balance PRIMARY KEY (scheme_code)
);

CREATE TABLE gme_scheme_balance_entry (
    id            BIGINT GENERATED ALWAYS AS IDENTITY,
    scheme_code   VARCHAR(16)   NOT NULL,
    entry_type    VARCHAR(8)    NOT NULL,   -- OPENING | CREDIT | DEBIT
    txn_ref       VARCHAR(64)   NOT NULL,   -- payout/top-up reference; idempotency key
    amount        NUMERIC(20,0) NOT NULL,   -- always positive magnitude; direction is entry_type
    balance_after NUMERIC(20,0) NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_gme_scheme_balance_entry PRIMARY KEY (id),
    CONSTRAINT uq_gme_scheme_balance_entry UNIQUE (scheme_code, entry_type, txn_ref),
    CONSTRAINT ck_gme_scheme_balance_entry_type CHECK (entry_type IN ('OPENING', 'CREDIT', 'DEBIT'))
);

CREATE INDEX idx_gme_scheme_balance_entry ON gme_scheme_balance_entry (scheme_code, created_at);
