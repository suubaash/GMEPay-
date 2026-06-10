-- scheme-adapter-zeropay: ZeroPay batch file registry (WBS 17.2-G10).
-- One row per ZP00xx batch file generated for transmission (OUTBOUND) or fetched
-- from the scheme SFTP inbound directory (INBOUND).
--
-- PostgreSQL 16 in production (verified by the Testcontainers IT); H2 in PostgreSQL
-- mode only for local unit slices — the DDL below is plain SQL valid on both.
-- KRW control sums are NUMERIC(20,0): KRW has 0 decimals per docs/MONEY_CONVENTION.md.

CREATE TABLE zp_batch_files (
    id               BIGINT GENERATED ALWAYS AS IDENTITY,
    file_type        VARCHAR(8)    NOT NULL,   -- ZP0011, ZP0012, ... (BatchType enum name)
    direction        VARCHAR(8)    NOT NULL,   -- OUTBOUND | INBOUND
    business_date    DATE          NOT NULL,
    sequence_no      INTEGER       NOT NULL,   -- intra-day file sequence, 1-based
    file_name        VARCHAR(128)  NOT NULL,
    sha256_checksum  VARCHAR(64)   NOT NULL,   -- lowercase hex SHA-256 of the file bytes
    file_size_bytes  BIGINT        NOT NULL,
    record_count     INTEGER       NOT NULL,
    control_sum_krw  NUMERIC(20,0) NOT NULL,   -- trailer control sum (KRW, scale 0)
    status           VARCHAR(24)   NOT NULL,   -- GENERATED|TRANSMITTED|RECEIVED|PARSED|PROCESSED|PARSE_ERROR
    window_opens_at  TIMESTAMP WITH TIME ZONE NOT NULL,  -- SFTP transmission/pickup window (KST schedule, SCH-06 §7.3)
    window_closes_at TIMESTAMP WITH TIME ZONE NOT NULL,
    received_at      TIMESTAMP WITH TIME ZONE,            -- inbound files: fetch completion time
    sent_at          TIMESTAMP WITH TIME ZONE,            -- outbound files: SFTP PUT completion time
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_zp_batch_files PRIMARY KEY (id),
    CONSTRAINT uq_zp_batch_files_type_date_seq UNIQUE (file_type, business_date, sequence_no),
    CONSTRAINT ck_zp_batch_files_direction CHECK (direction IN ('OUTBOUND', 'INBOUND'))
);

CREATE INDEX idx_zp_batch_files_status ON zp_batch_files (status);
CREATE INDEX idx_zp_batch_files_business_date ON zp_batch_files (business_date);
