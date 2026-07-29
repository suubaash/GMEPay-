> 작업: T0 auth boundary fix / 출처: agent

# T0-1..T0-4 + T0-8 — identity boundary on `services/ops-partner-bff`

Scope: `services/ops-partner-bff` only. **No changes** to `libs/**` (lib-rbac types were consumed, not modified), `api-gateway`, `payment-executor`, `revenue-ledger`, `config-registry`, `docker-compose.yml`, or Helm values. `apps/**` deliberately untouched — see "What remains open".

Before: the BFF had **zero** authentication. 36 controllers (partner lifecycle, KYB screening, commercial terms, commission shares, credential rotation/reveal, prefunding, platform settings, the ops kill-switch) answered anyone who could reach port 8095, both SPAs proxy to it through an unauthenticated Next `/api/*` rewrite, `POST /v1/auth/login {"password":"demo"}` minted an unsigned `role:ADMIN` token, authorization was decided by the caller's own `X-Gme-Permissions` header, and `/v1/portal/{partnerId}/**` trusted the path segment.

---

## 1. Dev-login stub — DELETED (not gated)

**Deleted:** `web/AuthController.java`, `web/dto/LoginRequest.java`, `web/dto/LoginResponse.java`, `web/dto/RefreshRequest.java`, `test/.../web/AuthControllerTest.java`.

Chose deletion over a default-off flag, for three reasons: (a) the CISO "Done when" requires deletion, not gating; (b) a gated stub would be **useless anyway** — the token it minted (`"mock.eyJ" + base64(...)`, no signature) is rejected by the new resource server, so no dev-login path can produce a working credential any more; (c) both SPAs already contain an OIDC/PKCE path (`admin-ui/src/api/oidc.js`). No Java or config file anywhere else in the repo referenced the endpoint or its DTOs (repo-wide grep).

Regression test: `BffSecurityFilterChainTest.devLoginEndpointIsGone` asserts 401 unauthenticated and **404 with a valid token** — proof there is no handler, not merely a blocked one.

## 2. SecurityFilterChain — OAuth2 resource server

**New:** `config/BffSecurityConfig.java`. Mirrors `api-gateway/.../config/SecurityConfig.java` (ADR-011) in property style and role mapping, differing only in stack (servlet MVC vs WebFlux):

- `spring.security.oauth2.resourceserver.jwt.issuer-uri=${OIDC_ISSUER_URI:http://localhost:8090/realms/gmepay}` — identical env name to api-gateway; decoder is lazy, so a wrong issuer fails the *request* (401), not startup.
- default deny (`anyRequest().authenticated()`); **only** `/actuator/health`, `/actuator/health/**` anonymous. `/actuator/metrics`, `/v3/api-docs`, `/swagger-ui/**` are now authenticated (closes "full internal API surface published to anyone who can reach the port").
- stateless, CSRF off, `HttpStatusEntryPoint(401)` — no login redirect.
- claim → authority mapping: `realm_access.roles[]` → `ROLE_*` (same as api-gateway), `permissions[]` → `PERM_*`, plus default `SCOPE_*`.
- `@ConditionalOnWebApplication(SERVLET)` so `BffApplicationTest` (`webEnvironment = NONE`) still boots.

**New:** `security/TokenClaims.java` — projects the verified `Jwt` onto lib-rbac's `PermissionContext`: `sub` → principal, `partner_id` (fallbacks `tenant_id`, `partnerId`) → tenant, `permissions` (array **or** CSV) → permissions, `realm_access.roles[]` → roles. A token with no `permissions` claim authenticates but authorizes nothing.

**build.gradle:** `+spring-boot-starter-oauth2-resource-server`, `+spring-security-test` (test only).

## 3. Client-supplied authority headers — no longer an authorization source

`web/OpsRbacGuard.java` rewritten: the `requireOps(String)` / `requireTxnView(String)` **overloads are deleted** (raw-header path gone) and every check reads `TokenClaims.current()`. The component's constructor signature (`OpsRbacGuard(boolean enforce)`) and the `gmepay.ops.rbac.enforce` dev flag are unchanged, so the 6 existing controllers and all their test wiring kept working. Added: `requireAdminRead()`, `requireAdminWrite()`, `requirePartnerScope(String)`, `actor(String)`, `permissions()`.

`@RequestHeader(X-Gme-Permissions)` parameters were removed from all 8 call sites (`AdminDashboard`, `ApprovalAdmin`, `OpsAction`, `OpsAlertAck`, `OpsTransaction`, `OpsWebhookAction`, `PlatformSettings`, `SchemeStatement`).

Identity for **audit** also moved to the token: `OpsRbacGuard.actor(fallback)` returns the token subject and only falls back to `X-Gme-Principal-Id` when unauthenticated, so an operator cannot write an audit row (or a `platform_setting.updated_by`, or an approval decision) under someone else's name. `ApprovalAdminController` now forwards `rbac.actor(null)` + `rbac.permissions()` to auth-identity instead of the caller's headers — previously a single caller could satisfy maker-checker twice under two invented principal ids, or simply claim `approval.cfo_override`.

Dev flag semantics restated: `gmepay.ops.rbac.enforce=false` now only lets an **authenticated** token with an empty permission set through; it can no longer admit an anonymous caller (the filter chain 401s first). A non-empty but wrong permission set is always denied.

## 4. Cross-partner IDOR

All 10 `/v1/portal/{partnerId}/**` handlers call `rbac.requirePartnerScope(partnerId)` first. Rule: path must equal the token's `partner_id` claim → allow; a caller holding `partner.view` (or `ops:operate`) may cross-read; otherwise **403**. Hub tokens with no partner claim need `partner.view` too. `X-Partner-Id` is not read anywhere server-side.

## 5. Onboarding admin surface — RBAC-guarded

**New:** `config/AdminSurfaceRbacInterceptor.java` + `config/BffWebMvcConfig.java`, registered on `/v1/admin/**` — which is **every** controller except `PartnerPortalController`. Safe methods → `requireAdminRead()` (any platform-operator permission; a partner-scoped token holds none, so a partner can never read the admin surface); mutating methods → `requireAdminWrite()` (`ops:operate`, `partner.activate`, `rbac.manage`, `approval.cfo_override`, `settlement.resolve_exception` — the read-only HUB_OPERATOR set is deliberately insufficient).

Chosen over adding a guard call to ~60 handler methods across 14 controllers: coverage becomes the default, so an endpoint added under `/v1/admin/**` tomorrow is gated the moment it exists. It layers *under* the existing fine-grained per-action checks, which still run — both must pass. Permission codes are the ones actually seeded in `auth-identity` V003/V005/V006 (`partner.view`, `partner.activate`, `txn.view`, `rbac.manage`, `approval.*`, `report.generate`, `settlement.resolve_exception`, `inspector.view`) plus the pre-existing `ops:operate`.

## 6. Tests

`gradlew.bat :services:ops-partner-bff:test` → **BUILD SUCCESSFUL, 317 tests, 0 failures** (302 pre-existing + 15 new). `:libs:lib-errors:test` (which hosts `com.gme.pay.rbac`) → BUILD SUCCESSFUL, untouched.

**New `security/BffSecurityFilterChainTest`** — `@SpringBootTest(MOCK)` through the real filter chain, real MVC mappings and the real interceptor, with a stubbed `JwtDecoder` (base64url `sub|partnerId|perms`; only the crypto step is substituted). Covers exactly the requested matrix:

| case | result |
|---|---|
| unauthenticated → `/v1/admin/partners`, `/v1/admin/dashboard`, `POST /v1/admin/ops/pause`, `/v1/portal/{id}/balance` | 401 |
| forged `X-Gme-Permissions` + `X-Gme-Principal-Id`, no token | 401 |
| invalid/unverifiable bearer token | 401 |
| `POST /v1/auth/login` with `password=demo` | 401 unauth / **404** with a token |
| `/actuator/health`, `/actuator/health/readiness` | 200 · `/actuator/metrics`, `/v3/api-docs` 401 |
| partner token reading **another** partner's balance (and with forged `X-Gme-Permissions`/`X-Partner-Id`) | 403 |
| partner token minting sandbox keys for another partner | 403 |
| partner token reading **its own** balance | 200 |
| operator with `partner.view` cross-reading a partner | 200 · hub token without it 403 |
| admin read with no hub permission / with `partner.view` | 403 / 200 |
| admin write with read-only permissions / with `ops:operate` | 403 / 200 |
| partner-scoped token on the admin surface | 403 |
| `POST /v1/admin/partners/{code}/kyb/screen` with only `partner.view` | 403 |

`OpsRbacGuardTest` rewritten for token-sourced decisions (incl. "a forged header buys nothing", `actor()` precedence, admin read/write sets). Existing tests that granted themselves permissions via headers were converted to authenticate properly — `OpsActionControllerTest`, `OpsAlertAckControllerTest`, `OpsTransactionControllerTest`, `TransactionsControllerTest`, `PlatformSettingsControllerTest`, `ApprovalAdminControllerTest` (plus a new assertion there that spoofed headers no longer decide the approver). The 5 portal test classes authenticate as a cross-reading operator. New test helper: `test/.../security/TestTokens.java`. **No test was weakened or disabled.**

---

## Required compose / Helm entries (NOT applied — other agents hold those files)

For the `ops-partner-bff` service, add to `docker-compose.yml` and `deploy/helm/gmepay/values.yaml`:

| var | compose value | Helm/prod value |
|---|---|---|
| `OIDC_ISSUER_URI` | `http://keycloak:8080/realms/gmepay` | the real issuer URL, e.g. `https://auth.<host>/realms/gmepay` |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI` | *(omit)* | only if OIDC discovery is unreachable and JWKS must be pinned |

- **Do NOT set `GMEPAY_OPS_RBAC_ENFORCE=false`** anywhere — the default is `true` (enforce) and the flag now only affects permission-less tokens.
- The issuer must be reachable from the BFF container/pod, and its `iss` must match what the SPAs' tokens carry (this is the realm/port mismatch already tracked as T1-2).
- **Keycloak realm config (`docker/keycloak/realm-gmepay.json`) needs two protocol mappers** or nothing will be authorized: `permissions` (multivalued, permission codes from auth-identity's catalogue) and `partner_id` (partner-portal users only; omit for hub operators). Without them every request is 401→authenticated-but-403.

## What remains open

1. **Both SPAs still POST `/v1/auth/login`** (`admin-ui/src/api/client.js:164`, `store/authSlice.js`, `login/page.jsx`; `partner-portal-ui/src/api/auth.js:186`, `api/client.js:252`). They will now get 404 there and 401 everywhere else until they attach a Keycloak token — i.e. **the UIs are non-functional against this BFF until T1-2 lands**. Left untouched deliberately: `apps/**` is outside the stated scope, the vitest suites cannot be run in place (known `+`-in-path bug), and the OIDC swap is already tracked as T1-2/CPO P2. This is the single blocking follow-up.
2. **T0-2 platform-wide**: 19 other services still ship no authentication layer (`auth-identity`, `config-registry`, `prefunding`, `transaction-mgmt`, … ). Only `api-gateway` + this BFF are covered.
3. **T0-3 platform-wide**: `gmepay.rbac.enabled` is still `false` outside auth-identity, so `@RequiresPermission` (incl. `RbacAdminController`'s `rbac.manage`) is still inert; `RbacContextFilter`/`RbacClaimSigner` provenance verification is not installed in the BFF (it does not need it — it reads the token directly — but the gateway-stamped-claims pipeline remains non-functional end to end). admin-ui still *sends* `X-Gme-Permissions`; harmless now, but should be removed.
4. **T0-8**: no gateway route for `/v1/portal/**` or `/v1/admin/**`, so rate limiting, idempotency, IP allowlist and central edge audit still do not cover BFF traffic.
5. **Coarse vs fine-grained**: the admin surface gate is per-HTTP-method, not per-endpoint. A truly per-action permission map (e.g. `kyb.screen`, `commission.set`, `credential.rotate`) needs new permission codes seeded in auth-identity — a follow-up, not a regression.
6. Not in scope, still open in Tier 0: T0-5 (unauthenticated money-moving internal endpoints + sandbox runner), T0-6 (committed secrets are live defaults), T0-7 (published stub partner keys).
