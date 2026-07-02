-- auth-identity V006: SUPPORT role (customer-support staff — scoped read + safe refund).
--
-- Additive seed only (no schema change). Adds a new RBAC role, SUPPORT, for
-- customer-support agents. The role is deliberately narrow: it can look up and read
-- transactions (txn.view) and act on refunds within the tier-1 band (refund.approve_l1 —
-- note: the SELF_SERVE <$1k band auto-approves with no permission, so refund.approve_l1
-- is the meaningful "handle a refund up to a limit" grant). It is EXPLICITLY NOT granted
-- any of the dangerous operator permissions: partner.activate, settlement.resolve_exception,
-- rbac.manage, or a broad ops:operate — support staff never touch partner lifecycle,
-- settlement exceptions, role administration, or hub operate actions.
--
-- We reuse the existing refund.approve_l1 permission (seeded in V005) rather than minting a
-- new refund.request/refund.view code: the catalogue already gates refunds via the
-- approval-tier permissions, and reuse keeps the DB-driven permission set (and its
-- catalogue-count invariants) stable. If a dedicated refund.request permission is later
-- warranted, it can be added in a further additive migration.
--
-- Idempotent (guarded WHERE NOT EXISTS inserts) and portable (H2 PostgreSQL-compat + real
-- PostgreSQL 16). Conventions match V002/V003/V005 (snake_case, CURRENT_TIMESTAMP, named refs).

-- ── seed the SUPPORT role (idempotent; uq_roles_code also backstops duplicates) ──
INSERT INTO roles (code, description, created_at)
SELECT 'SUPPORT', 'Customer-support staff (read transactions + tier-1 refunds)', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE code = 'SUPPORT');

-- ── grant SUPPORT its scoped permission set: txn.view + refund.approve_l1 ──
-- (guarded so re-running cannot duplicate a (role_id, permission_id) row).
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code = 'SUPPORT'
  AND p.code IN ('txn.view', 'refund.approve_l1')
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
