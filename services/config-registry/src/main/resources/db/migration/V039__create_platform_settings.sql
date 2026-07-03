-- V039: Generic platform-settings store (owner Goal #3 — operators change
-- platform tunables from the admin UI without a redeploy).
--
-- WHY
-- ---
-- Several real platform values are hard-coded across services (prefunding float
-- alert tiers, the domestic wallet fee, the FX quote TTL). This table is a
-- single, generic key/value store so those tunables can be edited at runtime by
-- an operator via GET/PUT /v1/admin/settings and consumed by services with a
-- code-side fallback default. It is intentionally schema-light — one row per
-- setting, a coarse value_type used only for input validation (NUMBER must
-- parse), and audit metadata (updated_at/updated_by). Every PUT is hash-chain
-- audited (ADR-007) by PlatformSettingService, same as the ops kill-switch.
--
-- DDL DISCIPLINE
-- --------------
-- Plain portable DDL (VARCHAR, TIMESTAMP) — no TIMESTAMPTZ / JSONB (ADR rule);
-- PostgreSQL and H2 (PostgreSQL mode) both honour every statement. Additive
-- only: a new table + seed INSERTs, no ALTER of an applied migration.

-- "key" and "value" are reserved words in both PostgreSQL and H2, so those
-- identifiers are double-quoted here (portable ANSI quoting) and mapped as quoted
-- columns in PlatformSettingEntity (Hibernate backtick → dialect-specific quoting).
CREATE TABLE platform_settings (
    "key"       VARCHAR(96)  NOT NULL PRIMARY KEY,
    "value"     VARCHAR(512) NOT NULL,
    value_type  VARCHAR(16)  NOT NULL DEFAULT 'STRING',
    description VARCHAR(256),
    updated_at  TIMESTAMP    NOT NULL,
    updated_by  VARCHAR(96)
);

-- Seed the real platform tunables so the admin editor has content on first boot.
-- These document the platform's tunables even where the consuming service is not
-- yet wired to read them (wallet.fee.krw / fx.quote.ttl.seconds).
INSERT INTO platform_settings ("key", "value", value_type, description, updated_at, updated_by) VALUES
    ('prefunding.alert.tier1.pct', '95',  'NUMBER', 'Float low-balance alert: first warning tier (%)',                                 CURRENT_TIMESTAMP, 'system'),
    ('prefunding.alert.tier2.pct', '85',  'NUMBER', 'Float low-balance alert: second warning tier (%)',                                CURRENT_TIMESTAMP, 'system'),
    ('prefunding.alert.tier3.pct', '70',  'NUMBER', 'Float low-balance alert: third warning tier (%)',                                 CURRENT_TIMESTAMP, 'system'),
    ('wallet.fee.krw',             '500', 'NUMBER', 'Domestic wallet transaction fee (KRW). NOTE: consumed by payment-executor (pending wiring).', CURRENT_TIMESTAMP, 'system'),
    ('fx.quote.ttl.seconds',       '900', 'NUMBER', 'FX quote lock validity (seconds).',                                               CURRENT_TIMESTAMP, 'system');
