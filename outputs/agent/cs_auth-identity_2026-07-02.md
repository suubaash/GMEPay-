> 작업: CS support role / 출처: agent

# SUPPORT role — auth-identity RBAC

## What was added
New Flyway migration **`V006__support_role.sql`** (additive seed; no schema change),
mirroring the V003/V005 role + role_permission seed pattern. Idempotent (`WHERE NOT
EXISTS` guards), portable (H2 PG-compat + PostgreSQL 16).

## SUPPORT role — permission set
| Granted | Why |
|---|---|
| `txn.view` | Look up + read transactions |
| `refund.approve_l1` | Act on refunds within the tier-1 band (safe refund) |

Reused the existing V005 `refund.approve_l1` rather than minting a new
`refund.request`/`refund.view` code. Rationale: the catalogue already gates refunds
via approval-tier permissions, and the SELF_SERVE (<$1k) band auto-approves with no
permission — so `refund.approve_l1` is the meaningful "handle a refund up to a limit"
grant. Reuse also keeps the DB-driven catalogue count (11) and its cross-test
invariants stable (avoids touching 5 `hasSize(11/12)` assertions across 3 test files).

## Explicitly NOT granted (dangerous)
`ops:operate`, `partner.activate`, `settlement.resolve_exception`, `rbac.manage`.
(`ops:operate` does not exist in this service's catalogue — asserted absent defensively.)

## Test
`RbacCoreMigrationTest.v006_seedsSupportRole_withScopedPermissions_andNoDangerousGrants`
— asserts SUPPORT resolves to exactly `{txn.view, refund.approve_l1}` and to none of
the four dangerous permissions. Existing RBAC tests use `anySatisfy` guarded on
HUB_ADMIN/HUB_OPERATOR, so the new role does not perturb them.

## Constraints honored
Additive (new migration only); only `services/auth-identity/` touched; libs + other
services untouched; no server/docker. CHANGELOG updated.
