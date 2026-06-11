# ADR-011 — Identity provider: Keycloak for humans, auth-identity for machines

**Status:** Accepted (user decision, 2026-06-11)
**Slice:** Partner Setup Slice 1 (foundation) — retires `password=demo` flow

## Context
The current admin-ui + partner-portal login is `password=demo` in the BFF (`AuthController.DEMO_PASSWORD`). It is explicitly marked "REPLACE WITH auth-identity integration in Phase 4 hardening". A 20-year system needs a real OIDC IdP with audit, SCIM/LDAP federation for enterprise partners' SSO, and clean separation between human and machine authentication. The existing `auth-identity` service has invested heavily in machine credentials (ApiKey, HMAC, mTLS, IP allowlist) — that work stays.

## Decision
Two-IdP split:
- **Keycloak** — human authentication. Deployed on Rocky 9 (ADR-004), PostgreSQL-backed, behind Nginx (ADR-002), exposed to admin-ui + partner-portal-ui as OIDC. Mature SCIM/LDAP/SAML federation for enterprise partner SSO. Runs as one container in compose; one Postgres DB.
- **auth-identity (existing service)** — machine authentication only. Continues to own `ApiKeyEntity`, `PrincipalEntity`, HMAC signing material, mTLS client cert exchange, IP allowlist enforcement, sandbox vs prod credential pairs. Becomes a pure credential issuer + verifier for API traffic.

The BFF (`api-gateway` + `ops-partner-bff`) becomes an OAuth2 resource server: human requests bear Keycloak-issued JWTs, machine requests bear `auth-identity`-issued API keys / signed HMAC headers.

## Consequences
- One new infrastructure component (Keycloak + its Postgres DB).
- `password=demo` flow retires in Slice 1. Demo operators are seeded as Keycloak users (`admin / demo` for dev, real passwords for staging+prod).
- Ory Hydra/Kratos and Auth0 ruled out: Ory has thinner Korean ecosystem; Auth0 is SaaS — incompatible with PIPA data-residency for a 20-year contract.
- `auth-identity`'s current operator-login endpoints are removed (Slice 1 ticket); existing tests update to assert the new resource-server pattern.
- Per-partner self-service portal users live in Keycloak as PARTNER_ADMIN / PARTNER_VIEWER roles; partner-the-org and partner-portal-user are distinct entities going forward.
