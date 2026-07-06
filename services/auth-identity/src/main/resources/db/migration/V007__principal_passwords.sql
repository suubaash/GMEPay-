-- auth-identity V007: human password credentials on principals (real-auth slice).
--
-- Adds the salted-PBKDF2 password columns for OPERATOR principals that sign in
-- through POST /v1/auth/login (proxied by ops-partner-bff). Column types mirror
-- the api_keys secret columns from V002 exactly:
--   password_hash       - hex PBKDF2-HMAC-SHA256 of the password (never plaintext)
--   password_salt       - per-principal random salt (hex)
--   password_iterations - derivation iteration count persisted per row so
--                         SecretHasher.CURRENT_ITERATIONS can be raised later
--                         without invalidating existing passwords
--
-- All columns are nullable: PARTNER / SERVICE principals (and operators who only
-- authenticate via Keycloak in prod) simply have no local password.
--
-- The dev 'admin' operator principal is NOT seeded here — a PBKDF2 hash cannot
-- be computed in SQL. See com.gme.pay.auth.config.DevAdminSeeder, which
-- creates-if-absent the 'admin' principal at startup using the runtime
-- SecretHasher (gated on gmepay.auth.seed-dev-admin.enabled, default true).

ALTER TABLE principals ADD COLUMN password_hash       VARCHAR(128);
ALTER TABLE principals ADD COLUMN password_salt       VARCHAR(64);
ALTER TABLE principals ADD COLUMN password_iterations INTEGER;
