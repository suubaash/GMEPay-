-- auth-identity V007: seed OPERATOR users for the user-management page (WBS — Operator User Mgmt).
--
-- Additive seed only (no schema change). The user-management surface (/v1/users) reads OPERATOR
-- principals out of the existing principals table (V002) plus their principal_roles grants — there
-- is no separate "users" table. This migration seeds ~5 realistic OPERATOR principals so the Users
-- page renders live data against the real store, and grants each one real role codes from the
-- catalogue (HUB_ADMIN / HUB_OPERATOR seeded in V002, SUPPORT in V006).
--
-- Status/last_login conventions mirror the /v1/users status derivation:
--   ACTIVE + last_login_at set   → "ACTIVE"
--   ACTIVE + last_login_at NULL  → "INVITED" (outstanding invite)
--   DISABLED                     → "DISABLED"
--
-- Idempotent (guarded WHERE NOT EXISTS inserts, keyed on the unique username) and portable
-- (H2 PostgreSQL-compat + real PostgreSQL 16). Conventions match V002/V005/V006 (snake_case,
-- CURRENT_TIMESTAMP, named refs, guarded joins).

-- ── seed the OPERATOR principals (idempotent; uq_principals_username also backstops duplicates) ──
INSERT INTO principals (principal_type, username, display_name, status, email, last_login_at, created_at)
SELECT 'OPERATOR', 'subash@gmeremit.com', 'Subash Sharma', 'ACTIVE',
       'subash@gmeremit.com', TIMESTAMP '2026-06-15 09:12:00+09:00', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM principals WHERE username = 'subash@gmeremit.com');

INSERT INTO principals (principal_type, username, display_name, status, email, last_login_at, created_at)
SELECT 'OPERATOR', 'jiyeon.park@gmeremit.com', 'Ji-yeon Park', 'ACTIVE',
       'jiyeon.park@gmeremit.com', TIMESTAMP '2026-06-14 17:45:00+09:00', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM principals WHERE username = 'jiyeon.park@gmeremit.com');

INSERT INTO principals (principal_type, username, display_name, status, email, last_login_at, created_at)
SELECT 'OPERATOR', 'arjun.thapa@gmeremit.com', 'Arjun Thapa', 'ACTIVE',
       'arjun.thapa@gmeremit.com', TIMESTAMP '2026-06-13 11:00:00+09:00', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM principals WHERE username = 'arjun.thapa@gmeremit.com');

-- Invited (never logged in) → last_login_at NULL, status ACTIVE derives to INVITED.
INSERT INTO principals (principal_type, username, display_name, status, email, last_login_at, created_at)
SELECT 'OPERATOR', 'mei.lin@gmeremit.com', 'Mei Lin', 'ACTIVE',
       'mei.lin@gmeremit.com', NULL, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM principals WHERE username = 'mei.lin@gmeremit.com');

-- Deactivated operator → status DISABLED.
INSERT INTO principals (principal_type, username, display_name, status, email, last_login_at, created_at)
SELECT 'OPERATOR', 'carlos.reyes@gmeremit.com', 'Carlos Reyes', 'DISABLED',
       'carlos.reyes@gmeremit.com', TIMESTAMP '2026-05-30 08:00:00+09:00', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM principals WHERE username = 'carlos.reyes@gmeremit.com');

-- ── grant each operator its role codes (guarded so re-running cannot duplicate a join row) ──
-- Subash: full admin.
INSERT INTO principal_roles (principal_id, role_id)
SELECT pr.id, r.id
FROM principals pr
CROSS JOIN roles r
WHERE pr.username = 'subash@gmeremit.com'
  AND r.code IN ('HUB_ADMIN', 'HUB_OPERATOR')
  AND NOT EXISTS (
      SELECT 1 FROM principal_roles j WHERE j.principal_id = pr.id AND j.role_id = r.id
  );

-- Ji-yeon: hub operator + support.
INSERT INTO principal_roles (principal_id, role_id)
SELECT pr.id, r.id
FROM principals pr
CROSS JOIN roles r
WHERE pr.username = 'jiyeon.park@gmeremit.com'
  AND r.code IN ('HUB_OPERATOR', 'SUPPORT')
  AND NOT EXISTS (
      SELECT 1 FROM principal_roles j WHERE j.principal_id = pr.id AND j.role_id = r.id
  );

-- Arjun: hub operator.
INSERT INTO principal_roles (principal_id, role_id)
SELECT pr.id, r.id
FROM principals pr
CROSS JOIN roles r
WHERE pr.username = 'arjun.thapa@gmeremit.com'
  AND r.code IN ('HUB_OPERATOR')
  AND NOT EXISTS (
      SELECT 1 FROM principal_roles j WHERE j.principal_id = pr.id AND j.role_id = r.id
  );

-- Mei (invited): support only.
INSERT INTO principal_roles (principal_id, role_id)
SELECT pr.id, r.id
FROM principals pr
CROSS JOIN roles r
WHERE pr.username = 'mei.lin@gmeremit.com'
  AND r.code IN ('SUPPORT')
  AND NOT EXISTS (
      SELECT 1 FROM principal_roles j WHERE j.principal_id = pr.id AND j.role_id = r.id
  );

-- Carlos (disabled): hub operator + support (retained; role set survives deactivation).
INSERT INTO principal_roles (principal_id, role_id)
SELECT pr.id, r.id
FROM principals pr
CROSS JOIN roles r
WHERE pr.username = 'carlos.reyes@gmeremit.com'
  AND r.code IN ('HUB_OPERATOR', 'SUPPORT')
  AND NOT EXISTS (
      SELECT 1 FROM principal_roles j WHERE j.principal_id = pr.id AND j.role_id = r.id
  );
