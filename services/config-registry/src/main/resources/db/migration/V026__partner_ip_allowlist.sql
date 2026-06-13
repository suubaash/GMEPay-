-- V026: partner_ip_allowlist child table — Slice 8 Lane B "Credentials"
-- (docs/PARTNER_SETUP_PLAN.md §Slice 8).
--
-- WHY
-- ---
-- Per-partner CIDR allowlist consulted by the api-gateway before HMAC
-- verification: requests from source addresses outside the partner's
-- registered ranges are rejected at the edge. Scoped per environment so a
-- partner can allow its office ranges for SANDBOX while production traffic is
-- pinned to its datacenter egress IPs.
--
-- WHY NOT BITEMPORAL
-- ------------------
-- Unlike the pricing/commercial child tables (V017..V023) the allowlist is
-- operational reachability config, not a regulated commercial fact — the
-- "what did the allowlist look like when request R was rejected?" question is
-- answered by the ADR-007 audit log (one PARTNER_IP_ALLOWLIST_REPLACED event
-- per write with BEFORE/AFTER snapshots), not by row versioning. Plain
-- created_at/created_by lineage columns suffice; a save is DELETE + INSERT
-- inside one transaction (see PartnerIpAllowlistService).
--
-- INVARIANTS
-- ----------
-- * cidr shape: full validation (octet ranges, prefix bounds, IPv6 grouping)
--   is SERVICE-enforced (PartnerIpAllowlistService) — the CHECK below pins
--   only the trivially-checkable shape: non-empty, contains '/'.
-- * Hard ceiling of 10 CIDRs per (partner_id, environment): SERVICE-enforced,
--   409 CIDR_LIMIT_EXCEEDED — a COUNT-per-group ceiling is not expressible as
--   a row-local CHECK on either engine.
--
-- COMPATIBILITY
-- -------------
-- Plain TIMESTAMP, not TIMESTAMPTZ (H2 PG-mode compat, same as V004..V024).
-- BIGINT GENERATED ALWAYS AS IDENTITY surrogate id, engine-managed
-- (GenerationType.IDENTITY) — both engines parse the standard SQL spelling.
-- ADR-013 Expand discipline: wholly-new table; no in-place ALTERs.

CREATE TABLE partner_ip_allowlist (
    -- Identity surrogate, engine-managed: rows are replaced wholesale on every
    -- step-8 save and nothing outside this service joins on their ids.
    id           BIGINT GENERATED ALWAYS AS IDENTITY,

    -- FK to the partners surrogate (V003/V004 BIGINT PK); consumers resolve
    -- partners by partner_code (same note as V009..V024).
    partner_id   BIGINT       NOT NULL,

    -- The allowed source range in CIDR notation. Width 43 = longest IPv6
    -- literal (39) + "/128" (4). Full shape validation is service-layer; the
    -- CHECK pins only "non-empty and carries a prefix".
    cidr         VARCHAR(43)  NOT NULL,

    -- Operator-facing label ("Seoul office", "AWS NAT egress").
    label        VARCHAR(120),

    -- Which credential environment the range applies to.
    environment  VARCHAR(20)  NOT NULL,

    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by   VARCHAR(100) NOT NULL,

    CONSTRAINT pk_partner_ip_allowlist PRIMARY KEY (id),

    CONSTRAINT fk_partner_ip_allowlist_partner
        FOREIGN KEY (partner_id) REFERENCES partners (id),

    CONSTRAINT ck_partner_ip_allowlist_env CHECK (
        environment IN ('SANDBOX', 'PRODUCTION')
    ),

    -- Trivially-checkable shape only: at least a 1-char address, a slash, a
    -- 1..3-char prefix. Octet/grouping/prefix-bound validation lives in
    -- PartnerIpAllowlistService (no regex CHECK — H2 PG-mode has no '~').
    CONSTRAINT ck_partner_ip_allowlist_cidr_shape CHECK (
        LENGTH(cidr) >= 4 AND POSITION('/' IN cidr) >= 2
    ),

    -- The same range listed twice for one environment is an operator error.
    CONSTRAINT uq_partner_ip_allowlist UNIQUE (partner_id, environment, cidr)
);

-- Hot-path lookup: "allowlist for partner P in environment E" — the gateway's
-- edge check and the step-8 GET both filter on this pair.
CREATE INDEX idx_partner_ip_allowlist_partner
    ON partner_ip_allowlist (partner_id, environment);
