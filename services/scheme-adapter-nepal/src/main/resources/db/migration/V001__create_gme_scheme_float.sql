-- scheme-adapter-nepal: GME's prepaid float held WITH the Nepal QR scheme (Khalti/Fonepay).
--
-- The pre-submit balance-check (SETTLEMENT_FLOW_SPEC §7.2) previously did not exist for Nepal —
-- the payment-executor's checkBalance fell through to a no-op that always allowed the payout. This
-- is the REAL decrementing ledger backing it: seeded from an opening balance, credited on top-up,
-- and DEBITED on every committed payout, so the authorize gate declines once the float is short.
--
-- NPR carries a minor unit (1 NPR = 100 paisa); balances are held in NPR with two decimal places
-- (NUMERIC(20,2)). The adapter's /submit works in paisa and converts to NPR at the debit.
--
-- gme_scheme_balance holds the single running balance (one row, keyed by scheme_code).
-- gme_scheme_balance_entry is the append-only audit + idempotency journal, keyed uniquely by
-- (scheme_code, entry_type, txn_ref) so a retried payout or top-up never double-applies.

CREATE TABLE gme_scheme_balance (
    scheme_code   VARCHAR(16)   NOT NULL,
    currency      VARCHAR(3)    NOT NULL,
    balance       NUMERIC(20,2) NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_gme_scheme_balance PRIMARY KEY (scheme_code)
);

CREATE TABLE gme_scheme_balance_entry (
    id            BIGINT GENERATED ALWAYS AS IDENTITY,
    scheme_code   VARCHAR(16)   NOT NULL,
    entry_type    VARCHAR(8)    NOT NULL,   -- OPENING | CREDIT | DEBIT
    txn_ref       VARCHAR(64)   NOT NULL,   -- payout/top-up reference; idempotency key
    amount        NUMERIC(20,2) NOT NULL,   -- always positive magnitude; direction is entry_type
    balance_after NUMERIC(20,2) NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_gme_scheme_balance_entry PRIMARY KEY (id),
    CONSTRAINT uq_gme_scheme_balance_entry UNIQUE (scheme_code, entry_type, txn_ref),
    CONSTRAINT ck_gme_scheme_balance_entry_type CHECK (entry_type IN ('OPENING', 'CREDIT', 'DEBIT'))
);

CREATE INDEX idx_gme_scheme_balance_entry ON gme_scheme_balance_entry (scheme_code, created_at);
