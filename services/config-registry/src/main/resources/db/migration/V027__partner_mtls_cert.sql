-- V027: partner_mtls_cert child table — Slice 8 Lane B "Credentials"
-- (docs/PARTNER_SETUP_PLAN.md §Slice 8).
--
-- WHY
-- ---
-- The client certificate a partner presents on the mutual-TLS channel, per
-- environment. The gateway terminates TLS and matches the presented leaf's
-- SHA-256 fingerprint against the CURRENT row here; the wizard's step-8
-- uploads the PEM, config-registry parses it (java.security X509Certificate),
-- computes the fingerprint over the DER encoding and stores both.
--
-- WHY BITEMPORAL (ADR-010)
-- ------------------------
-- "Which certificate authenticated this partner's traffic on date D?" is a
-- security-incident / regulator question — the cert chain that answered it
-- must be reconstructable. Same SCD-6 column pairs as V004..V024:
--
--   * valid_from  / valid_to       — business time (when the binding is true)
--   * recorded_at / superseded_at  — transaction time (when we wrote it; NULL
--                                     on rows that are still current)
--
-- Storage discipline: rows are NEVER UPDATEd in place. A re-upload supersedes
-- the prior current row for the (partner, environment) and inserts the new
-- one; a revoke supersedes the ACTIVE row and inserts a REVOKED successor —
-- both halves sharing one MICROS-truncated instant (see PartnerMtlsCertService).
--
-- ONE CURRENT ROW PER (PARTNER, ENVIRONMENT, FINGERPRINT)
-- -------------------------------------------------------
-- Enforced with the V017/V022 APPLICATION-MAINTAINED current-row key (the
-- codebase's preferred cross-engine spelling — a stored GENERATED column has
-- no syntax both PG (requires STORED) and H2 (rejects STORED) parse, and a
-- vendor split (V004/V023 pattern) is only warranted when the DB must
-- recompute the key itself): current_cert_key carries
-- 'partner_id:environment:fingerprint' on the current row, NULL once
-- superseded, with a plain UNIQUE index. PartnerMtlsCertEntity stamps it on
-- INSERT and clears it on the supersede UPDATE.
--
-- COMPATIBILITY
-- -------------
-- Plain TIMESTAMP, not TIMESTAMPTZ (H2 PG-mode compat, same as V004..V024).
-- BIGINT GENERATED ALWAYS AS IDENTITY surrogate id, engine-managed.
-- ADR-013 Expand discipline: wholly-new table; no in-place ALTERs.

CREATE TABLE partner_mtls_cert (
    -- Identity surrogate, engine-managed: rows are minted fresh on every SCD-6
    -- write and nothing outside this service joins on their ids.
    id                 BIGINT       GENERATED ALWAYS AS IDENTITY,

    -- FK to the partners surrogate (V003/V004 BIGINT PK); consumers resolve
    -- partners by partner_code (same note as V009..V024).
    partner_id         BIGINT       NOT NULL,

    -- Which credential environment the certificate authenticates.
    environment        VARCHAR(20)  NOT NULL,

    -- The uploaded leaf certificate, PEM-encoded, exactly as received (the
    -- DER bytes are recoverable from it; the fingerprint below is the lookup
    -- key so the TEXT column is never scanned on the hot path).
    cert_pem           TEXT         NOT NULL,

    -- SHA-256 over the DER encoding (X509Certificate.getEncoded()), lowercase
    -- hex, 64 chars. The gateway matches presented leafs on this.
    fingerprint_sha256 CHAR(64)     NOT NULL,

    -- RFC 2253 subject / issuer DNs, parsed at upload for operator display.
    subject_dn         VARCHAR(255),
    issuer_dn          VARCHAR(255),

    -- The certificate's own validity window, parsed at upload. Distinct from
    -- the SCD-6 business time below: not_before/not_after describe the X.509
    -- artifact, valid_from/valid_to describe the partner-binding fact.
    not_before         TIMESTAMP,
    not_after          TIMESTAMP,

    -- Lifecycle of the binding. EXPIRED is stamped by the (Lane D) sweep when
    -- not_after passes; REVOKED rides an explicit operator action.
    status             VARCHAR(20)  NOT NULL,

    -- Business time (ADR-010). Half-open [valid_from, valid_to); NULL valid_to
    -- = open-ended.
    valid_from         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    valid_to           TIMESTAMP,

    -- Transaction time (ADR-010). recorded_at defaulted as a safety net but
    -- set explicitly (MICROS-truncated) by the application so paired writes
    -- share one instant; superseded_at is NULL on current rows.
    recorded_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    superseded_at      TIMESTAMP,

    -- Partial-unique emulation key (see header):
    -- 'partner_id:environment:fingerprint' on the CURRENT row, NULL once
    -- superseded. Width: 20 (BIGINT digits) + 1 + 10 (environment) + 1 + 64
    -- (fingerprint) = 96 → 120 with headroom.
    current_cert_key   VARCHAR(120),

    CONSTRAINT pk_partner_mtls_cert PRIMARY KEY (id),

    CONSTRAINT fk_partner_mtls_cert_partner
        FOREIGN KEY (partner_id) REFERENCES partners (id),

    CONSTRAINT ck_partner_mtls_cert_env CHECK (
        environment IN ('SANDBOX', 'PRODUCTION')
    ),

    CONSTRAINT ck_partner_mtls_cert_status CHECK (
        status IN ('ACTIVE', 'EXPIRED', 'REVOKED')
    )
);

-- Hot-path lookup: "current cert(s) for partner P [in environment E]" filters
-- on partner_id = ? AND superseded_at IS NULL (same choice as V009..V024).
CREATE INDEX idx_partner_mtls_cert_current
    ON partner_mtls_cert (partner_id, superseded_at);

-- Gateway-side reverse lookup: presented-leaf fingerprint → binding row.
CREATE INDEX idx_partner_mtls_cert_fingerprint
    ON partner_mtls_cert (fingerprint_sha256, superseded_at);

-- Partial-unique enforcement: at most one CURRENT row per
-- (partner_id, environment, fingerprint_sha256) — see header note. Both
-- engines treat multiple NULLs in a UNIQUE index as distinct, so historical
-- rows never collide; current rows collide on the key value.
CREATE UNIQUE INDEX partner_mtls_cert_current
    ON partner_mtls_cert (current_cert_key);
