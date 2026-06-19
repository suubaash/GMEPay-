-- auth-identity V003: RBAC core (WBS 18.x — comprehensive RBAC platform, Phase 1).
--
-- Builds the permission/menu layer on top of the V002 identity tables
-- (principals / roles / principal_roles). All RBAC tables live in auth-identity's
-- own DB (MSA rule 1 — no cross-service FKs). Conventions match V002 exactly:
-- BIGSERIAL PK, TIMESTAMP WITH TIME ZONE, snake_case, named constraints. H2's
-- PostgreSQL-compat mode accepts every construct here (proven by V002).
--
-- tenant_id is NULL for platform/hub-global rows and the partner BIGINT surrogate
-- (matching principals.partner_id, V002) for partner-scoped rows. Partner data
-- isolation (multi-tenancy) keys off this column in later phases.

-- ── permissions: resource.action catalogue, DB-driven (no deploy to add one) ──
CREATE TABLE permissions (
    id          BIGSERIAL                PRIMARY KEY,
    code        VARCHAR(128)             NOT NULL,   -- e.g. 'settlement.resolve_exception'
    resource    VARCHAR(64)              NOT NULL,
    action      VARCHAR(64)              NOT NULL,
    description VARCHAR(255),
    tenant_id   BIGINT,                              -- NULL = global
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_permissions_code UNIQUE (code)
);

-- ── role_permissions: which permissions a role grants ──
CREATE TABLE role_permissions (
    role_id       BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    tenant_id     BIGINT,
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_role_permissions_role       FOREIGN KEY (role_id)       REFERENCES roles (id),
    CONSTRAINT fk_role_permissions_permission FOREIGN KEY (permission_id) REFERENCES permissions (id)
);

-- ── menus: data-driven navigation tree (parent_id self-FK) ──
CREATE TABLE menus (
    id          BIGSERIAL                PRIMARY KEY,
    code        VARCHAR(64)              NOT NULL,
    parent_id   BIGINT,
    label       VARCHAR(128)             NOT NULL,
    route       VARCHAR(255),
    icon        VARCHAR(64),
    sort_order  INTEGER                  NOT NULL DEFAULT 0,
    menu_type   VARCHAR(16)              NOT NULL DEFAULT 'ADMIN',  -- ADMIN | PARTNER
    tenant_id   BIGINT,
    active      BOOLEAN                  NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_menus_code   UNIQUE (code),
    CONSTRAINT fk_menus_parent FOREIGN KEY (parent_id) REFERENCES menus (id)
);

-- ── menu_permissions: a menu is visible iff the principal holds any linked permission ──
CREATE TABLE menu_permissions (
    menu_id       BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    PRIMARY KEY (menu_id, permission_id),
    CONSTRAINT fk_menu_permissions_menu       FOREIGN KEY (menu_id)       REFERENCES menus (id),
    CONSTRAINT fk_menu_permissions_permission FOREIGN KEY (permission_id) REFERENCES permissions (id)
);

-- ── principal extension: fields RBAC needs, added without rewriting V002 ──
-- (tenancy already carried by principals.partner_id from V002.)
ALTER TABLE principals ADD COLUMN email         VARCHAR(255);
ALTER TABLE principals ADD COLUMN last_login_at TIMESTAMP WITH TIME ZONE;

-- ── user_roles: temporal role assignment with expiry (Expand phase alongside
--    principal_roles per ADR-013; resolution reads this, principal_roles dropped
--    in a later Contract migration). granted_by/revoked_at give an assignment trail. ──
CREATE TABLE user_roles (
    id           BIGSERIAL                PRIMARY KEY,
    principal_id BIGINT                   NOT NULL,
    role_id      BIGINT                   NOT NULL,
    tenant_id    BIGINT,
    valid_from   TIMESTAMP WITH TIME ZONE NOT NULL,
    valid_to     TIMESTAMP WITH TIME ZONE,            -- NULL = no expiry (permanent)
    granted_by   VARCHAR(128)             NOT NULL,
    granted_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at   TIMESTAMP WITH TIME ZONE,            -- NULL = active
    CONSTRAINT fk_user_roles_principal FOREIGN KEY (principal_id) REFERENCES principals (id),
    CONSTRAINT fk_user_roles_role      FOREIGN KEY (role_id)      REFERENCES roles (id),
    CONSTRAINT ck_user_roles_validity  CHECK (valid_to IS NULL OR valid_to > valid_from)
);

CREATE INDEX idx_permissions_resource     ON permissions (resource, action);
CREATE INDEX idx_role_permissions_role    ON role_permissions (role_id);
CREATE INDEX idx_menus_parent             ON menus (parent_id, sort_order);
CREATE INDEX idx_user_roles_principal     ON user_roles (principal_id, revoked_at);
CREATE INDEX idx_user_roles_active_window ON user_roles (principal_id, valid_from, valid_to);

-- ── seed: permission catalogue aligned with the admin-ui rbacApi.js fixtures ──
INSERT INTO permissions (code, resource, action, description, created_at) VALUES
    ('partner.view',                 'partner',    'view',              'View partners',              CURRENT_TIMESTAMP),
    ('partner.activate',             'partner',    'activate',          'Activate partners',          CURRENT_TIMESTAMP),
    ('settlement.resolve_exception', 'settlement', 'resolve_exception', 'Resolve settlement exception', CURRENT_TIMESTAMP),
    ('report.generate',              'report',     'generate',          'Generate reports',           CURRENT_TIMESTAMP),
    ('txn.view',                     'txn',        'view',              'View transactions',          CURRENT_TIMESTAMP),
    ('rbac.manage',                  'rbac',       'manage',            'Manage roles & permissions', CURRENT_TIMESTAMP),
    ('inspector.view',               'inspector',  'view',              'Live request inspector',     CURRENT_TIMESTAMP);

-- ── grant the full catalogue to HUB_ADMIN (seeded in V002) ──
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p WHERE r.code = 'HUB_ADMIN';

-- ── grant the read-only subset to HUB_OPERATOR ──
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'HUB_OPERATOR' AND p.code IN ('partner.view', 'txn.view', 'report.generate');
