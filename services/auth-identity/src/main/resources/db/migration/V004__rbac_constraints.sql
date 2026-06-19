-- auth-identity V004: RBAC constraints (WBS 18.x — Phase 3 constraint engine storage).
--
-- Typed, DB-driven constraints attached to a role / permission / assignment. The engine
-- (lib-errors com.gme.pay.rbac.constraint) evaluates them at request time with cascading-AND
-- semantics. Adding/changing a constraint is a DB write — no code deploy.
--
-- config_json is stored as TEXT (a flat JSON object) for Postgres + H2 compatibility — the
-- engine reads a String→String map, so jsonb querying is unnecessary here (constraints are
-- loaded by scope and evaluated in-process). Conventions match V002/V003.

CREATE TABLE permission_constraints (
    id              BIGSERIAL                PRIMARY KEY,
    scope_type      VARCHAR(20)              NOT NULL,   -- ROLE | PERMISSION | ROLE_PERMISSION | USER_ROLE
    scope_id        BIGINT                   NOT NULL,   -- id of the scoped row (e.g. role id / permission id)
    constraint_type VARCHAR(16)              NOT NULL,   -- TIME | LOCATION | AMOUNT | DATA_FILTER | APPROVAL
    config_json     TEXT                     NOT NULL,   -- flat JSON config, e.g. {"timezone":"Asia/Tokyo","startHour":"9"}
    tenant_id       BIGINT,                              -- NULL = platform-global
    active          BOOLEAN                  NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_perm_constraints_scope CHECK (scope_type IN ('ROLE','PERMISSION','ROLE_PERMISSION','USER_ROLE')),
    CONSTRAINT ck_perm_constraints_type  CHECK (constraint_type IN ('TIME','LOCATION','AMOUNT','DATA_FILTER','APPROVAL'))
);

CREATE INDEX idx_perm_constraints_scope ON permission_constraints (scope_type, scope_id, active);
