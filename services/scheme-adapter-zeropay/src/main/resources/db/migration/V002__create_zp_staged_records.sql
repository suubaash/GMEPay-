-- scheme-adapter-zeropay: staged ZP0011/ZP0012 records (WBS 17.2-G10).
-- Detail lines staged from generated ZP0011 (outbound payment-result) files and
-- parsed ZP0012 (inbound registration-result) files, each linked to its batch file
-- row in zp_batch_files. The reconciliation match key is (zeropay_txn_ref, txn_date)
-- per SCH-06 §5.3. KRW amounts are NUMERIC(20,0) per docs/MONEY_CONVENTION.md.

CREATE TABLE zp_staged_records (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY,
    batch_file_id         BIGINT        NOT NULL,
    record_type           VARCHAR(8)    NOT NULL,   -- ZP0011 | ZP0012
    line_number           INTEGER       NOT NULL,   -- 1-based detail line position within the file
    gme_txn_id            VARCHAR(20),
    zeropay_txn_ref       VARCHAR(20)   NOT NULL,
    merchant_id           VARCHAR(10),
    qr_code_id            VARCHAR(20),
    txn_date              DATE          NOT NULL,
    txn_time              TIME,
    payout_amount_krw     NUMERIC(20,0),            -- ZP0011 detail
    merchant_fee_amt_krw  NUMERIC(20,0),            -- ZP0011 detail
    van_fee_amt_krw       NUMERIC(20,0),            -- ZP0011 detail
    partner_type          VARCHAR(1),               -- D=domestic, I=international
    approval_code         VARCHAR(12),
    status_code           VARCHAR(1),               -- A=approved
    result_code           VARCHAR(4),               -- ZP0012: 00=registered, non-zero=failure
    registered_amount_krw NUMERIC(20,0),            -- ZP0012: amount registered by the scheme
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_zp_staged_records PRIMARY KEY (id),
    CONSTRAINT fk_zp_staged_records_batch_file FOREIGN KEY (batch_file_id) REFERENCES zp_batch_files (id),
    CONSTRAINT uq_zp_staged_records_file_line UNIQUE (batch_file_id, line_number),
    CONSTRAINT ck_zp_staged_records_type CHECK (record_type IN ('ZP0011', 'ZP0012'))
);

CREATE INDEX idx_zp_staged_records_match_key ON zp_staged_records (zeropay_txn_ref, txn_date);
