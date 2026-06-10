-- auth-identity service: identity & RBAC tables (WBS 17.2-G09).
--
-- principals      : operator / partner / service identities owned by this service.
-- roles           : RBAC role catalogue (seeded below); feeds JWT roles claims (18.4-G01).
-- principal_roles : many-to-many join between principals and roles.
-- api_keys        : API-key credentials owned by principals.
--
-- SECURITY CONVENTION (SEC-09 §4): HMAC/API secret material is NEVER stored in
-- plaintext. api_keys stores only:
--   secret_hash     - hex PBKDF2-HMAC-SHA256 of the secret, derived with secret_salt
--   secret_salt     - per-key random salt (hex)
--   hash_algorithm / hash_iterations
--                   - the derivation parameters, persisted per row so they can be
--                     upgraded over time without invalidating existing keys
-- The plaintext secret is shown to the caller exactly once at issuance time and
-- is not recoverable from this table. See com.gme.pay.auth.domain.SecretHasher.
--
-- Timestamps are TIMESTAMP WITH TIME ZONE so PostgreSQL stores absolute instants
-- (Hibernate maps java.time.Instant as UTC); H2's PostgreSQL compatibility mode
-- accepts the identical syntax for local unit slices.

CREATE TABLE roles (
    id          BIGSERIAL                PRIMARY KEY,
    code        VARCHAR(64)              NOT NULL,
    description VARCHAR(255),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_roles_code UNIQUE (code)
);

CREATE TABLE principals (
    id             BIGSERIAL                PRIMARY KEY,
    principal_type VARCHAR(16)              NOT NULL,   -- OPERATOR | PARTNER | SERVICE
    username       VARCHAR(128)             NOT NULL,
    display_name   VARCHAR(255),
    partner_id     BIGINT,                              -- set for PARTNER principals
    status         VARCHAR(16)              NOT NULL,   -- ACTIVE | LOCKED | DISABLED
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_principals_username UNIQUE (username)
);

CREATE TABLE principal_roles (
    principal_id BIGINT NOT NULL,
    role_id      BIGINT NOT NULL,
    PRIMARY KEY (principal_id, role_id),
    CONSTRAINT fk_principal_roles_principal FOREIGN KEY (principal_id) REFERENCES principals (id),
    CONSTRAINT fk_principal_roles_role      FOREIGN KEY (role_id)      REFERENCES roles (id)
);

CREATE TABLE api_keys (
    id              BIGSERIAL                PRIMARY KEY,
    api_key         VARCHAR(64)              NOT NULL,  -- public key identifier (not secret)
    principal_id    BIGINT                   NOT NULL,
    secret_hash     VARCHAR(128)             NOT NULL,  -- hex PBKDF2 output, NEVER plaintext
    secret_salt     VARCHAR(64)              NOT NULL,  -- hex per-key random salt
    hash_algorithm  VARCHAR(40)              NOT NULL,
    hash_iterations INTEGER                  NOT NULL,
    status          VARCHAR(16)              NOT NULL,  -- ACTIVE | PENDING_EXPIRY | REVOKED
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at      TIMESTAMP WITH TIME ZONE,
    revoked_at      TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_api_keys_api_key UNIQUE (api_key),
    CONSTRAINT fk_api_keys_principal FOREIGN KEY (principal_id) REFERENCES principals (id)
);

CREATE INDEX idx_api_keys_principal ON api_keys (principal_id);
CREATE INDEX idx_principals_partner ON principals (partner_id);

-- RBAC role catalogue seed (consumed by 18.4-G01 JWT roles claims).
INSERT INTO roles (code, description, created_at) VALUES
    ('HUB_ADMIN',    'Full administrative access to the GMEPay+ hub',        CURRENT_TIMESTAMP),
    ('HUB_OPERATOR', 'Day-to-day hub operations (read / ack / retry)',       CURRENT_TIMESTAMP),
    ('PARTNER_API',  'Machine-to-machine partner API access (client creds)', CURRENT_TIMESTAMP);
