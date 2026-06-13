-- V010: partner_document child table — Slice 3 "KYB" (docs/PARTNER_SETUP_PLAN.md §Slice 3).
--
-- WHAT THIS TABLE IS
-- ------------------
-- Metadata registry for KYB documents stored in the ADR-006 vault (MinIO bucket
-- gmepay-partner-vault, object-lock COMPLIANCE mode). The BYTES live in the
-- vault — this table records what was stored (vault_uri / version / sha256),
-- when it expires, and who verified it. Upload flow is always service-side via
-- lib-vault (never browser-direct), so every row pairs with an ADR-007 audit
-- event (aggregate_type = 'partner_document').
--
-- WHY BITEMPORAL (ADR-010)
-- ------------------------
-- Documents are the regulator's primary inspection target: "which license scan
-- was on file when you activated this partner?" requires the same SCD-6 column
-- pairs as partners (V004) and partner_contact (V009):
--
--   * valid_from  / valid_to       — business time (when the document fact holds)
--   * recorded_at / superseded_at  — transaction time (NULL superseded_at = current)
--
-- Storage discipline: rows are NEVER UPDATEd in place. Uploading a new version
-- of a doc type supersedes the prior current row for that (partner_id, doc_type)
-- and inserts a fresh row, both halves sharing one MICROS-truncated instant
-- (see PartnerDocumentService) — and the superseded row's vault_uri keeps
-- pointing at the immutable prior object (object-lock: nothing is ever deleted),
-- which is exactly what powers the document viewer's version history.
--
-- INDEXING
-- --------
-- Same shape as V009: no per-partner uniqueness (a partner holds N current
-- documents, one per doc_type), so a plain composite (partner_id, superseded_at)
-- serves the hot "current documents of partner P" query on both engines without
-- partial-index emulation.
--
-- COMPATIBILITY
-- -------------
-- PostgreSQL DDL that also runs under H2 in PostgreSQL mode (the @DataJpaTest
-- engine): plain TIMESTAMP (H2 PG-mode has no TIMESTAMPTZ alias — V004/V006/V009
-- precedent), BIGSERIAL engine-managed surrogate (GenerationType.IDENTITY).
--
-- ADR-013 Expand discipline: wholly-new table; no ALTERs on existing tables.

CREATE TABLE partner_document (
    -- BIGSERIAL surrogate, engine-managed (same id strategy as V006/V009).
    id            BIGSERIAL    NOT NULL,

    -- FK to the partners surrogate (V003/V004 BIGINT PK); referential integrity
    -- only — consumers resolve the current partner row via partner_code.
    partner_id    BIGINT       NOT NULL,

    -- KYB document class. CHECK-constrained to the Slice 3 roster (mirrors the
    -- DocumentType enum); the Slice 8 activation gate requires the mandatory
    -- subset present + verified.
    doc_type      VARCHAR(30)  NOT NULL,

    -- Original upload filename (display only — the vault key is derived from
    -- partner_code/doc_type/doc_id, not from this).
    filename      VARCHAR(255) NOT NULL,

    -- MIME type as uploaded; echoed on download passthrough.
    content_type  VARCHAR(100) NOT NULL,

    -- ADR-006 locator of the immutable object in the vault
    -- (s3://gmepay-partner-vault/<partner_code>/<doc_type>/<doc_id>/v<n>.<ext>).
    vault_uri     VARCHAR(500) NOT NULL,

    -- 1-based version of this doc_type for the partner — matches the v<n> path
    -- segment of vault_uri. The version counter is the vault's; this column
    -- denormalises it for list views without S3 round trips.
    version       INT          NOT NULL DEFAULT 1,

    -- Lowercase hex SHA-256 of the stored bytes, computed while streaming to
    -- the vault. Lets an examiner prove the served bytes are the stored bytes.
    sha256        CHAR(64),

    -- Document expiry as printed ON the document (license end date etc.) —
    -- business data, distinct from the bitemporal columns. NULL = non-expiring.
    expiry_date   DATE,

    -- 4-eyes verification stamp (an operator other than the uploader confirms
    -- the scan matches the registry extract). Wired by the verification flow in
    -- a later Slice-3 ticket; nullable until then.
    verified_by   VARCHAR(64),
    verified_at   TIMESTAMP,

    -- Business time (ADR-010). Half-open [valid_from, valid_to); NULL valid_to
    -- = open-ended.
    valid_from    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    valid_to      TIMESTAMP,

    -- Transaction time (ADR-010). recorded_at defaulted as a safety net but set
    -- explicitly (MICROS-truncated) by the application so paired writes share
    -- one instant; superseded_at is NULL on current rows.
    recorded_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    superseded_at TIMESTAMP,

    CONSTRAINT pk_partner_document PRIMARY KEY (id),

    CONSTRAINT fk_partner_document_partner
        FOREIGN KEY (partner_id) REFERENCES partners (id),

    CONSTRAINT ck_partner_document_doc_type CHECK (
        doc_type IN ('LICENSE', 'CERT_INCORPORATION', 'AOA', 'BOARD_RESOLUTION',
                     'UBO_DECLARATION', 'FINANCIALS', 'CBDDQ', 'OTHER')
    )
);

-- Hot path: "current documents of partner P" filters on partner_id = ? AND
-- superseded_at IS NULL; the composite also serves the version-history walk.
CREATE INDEX idx_partner_document_current
    ON partner_document (partner_id, superseded_at);
