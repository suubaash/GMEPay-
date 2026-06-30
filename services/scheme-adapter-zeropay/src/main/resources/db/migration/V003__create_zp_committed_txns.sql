-- scheme-adapter-zeropay: committed real-time transaction capture (WBS 17.2-G11).
-- Every MPM/CPM payment committed (and every refund cancelled) on the real-time path is
-- captured here at commit time. This is the local, in-service source of truth that the
-- batch data port (ZpPersistenceBatchDataPort) reads to build the daily ZP00xx outbound
-- files (ZP0011/ZP0021/ZP0061/ZP0063/ZP0065/ZP0066) with NON-EMPTY records — replacing
-- the zero-record ZpStubBatchDataPort.
--
-- The match key for refund-to-payment linkage is (approval_code) — a refund row carries the
-- ORIGINAL payment's approval code in original_approval_code. The reconciliation/business
-- date is the KST date the txn committed. KRW amounts are NUMERIC(20,0) per
-- docs/MONEY_CONVENTION.md (never double/float/minor units).

CREATE TABLE zp_committed_txns (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY,
    txn_kind              VARCHAR(8)    NOT NULL,   -- PAYMENT | REFUND
    gme_txn_id            VARCHAR(20),              -- GMEPay+ internal txn id (partner txn ref)
    zeropay_txn_ref       VARCHAR(20)   NOT NULL,   -- scheme txn reference returned at commit
    merchant_id           VARCHAR(10),
    qr_code_id            VARCHAR(20),
    business_date         DATE          NOT NULL,   -- KST business date of the commit
    txn_time              TIME          NOT NULL,   -- KST time of the commit / refund
    amount_krw            NUMERIC(20,0) NOT NULL,   -- payout (PAYMENT) or refund (REFUND) amount
    merchant_fee_krw      NUMERIC(20,0) NOT NULL DEFAULT 0,
    van_fee_krw           NUMERIC(20,0) NOT NULL DEFAULT 0,
    partner_type          VARCHAR(1)    NOT NULL DEFAULT 'D',  -- D=domestic, I=international
    approval_code         VARCHAR(12)   NOT NULL,   -- this txn's approval code (PAYMENT) / refund id (REFUND)
    original_approval_code VARCHAR(12),             -- REFUND only: approval code of the original payment
    settlement_date       DATE,                     -- value date (T+n); null until known
    status_code           VARCHAR(1)    NOT NULL,   -- A=approved/captured, R=refunded
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_zp_committed_txns PRIMARY KEY (id),
    CONSTRAINT uq_zp_committed_txns_kind_ref UNIQUE (txn_kind, zeropay_txn_ref),
    CONSTRAINT ck_zp_committed_txns_kind CHECK (txn_kind IN ('PAYMENT', 'REFUND'))
);

-- The batch data port queries by (business_date, txn_kind); composite index serves both.
CREATE INDEX idx_zp_committed_txns_date_kind ON zp_committed_txns (business_date, txn_kind);
CREATE INDEX idx_zp_committed_txns_merchant  ON zp_committed_txns (business_date, merchant_id);
