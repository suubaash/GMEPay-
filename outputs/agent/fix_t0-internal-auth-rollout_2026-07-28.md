> 작업: T0-2 internal auth rollout / 출처: agent

# T0-2 — rolling the internal-auth gate out across the fleet

Continues T0-5 (`outputs/agent/fix_t0-internal-endpoints_2026-07-28.md`). Same mechanism, no redesign:
the shared `libs/lib-errors` `com.gme.pay.internalauth` gate (`InternalAuthFilter`,
`InternalAuthProperties`, header `X-Gme-Internal`), which is auto-configured for every service
depending on lib-errors but opt-in. **`libs/lib-errors` was not modified.**

Services touched: `auth-identity`, `scheme-adapter-zeropay`, `transaction-mgmt`, `config-registry`,
`rate-fx`, `qr-service`, `api-gateway`, `payment-executor`.
Not touched (other agents': `ops-partner-bff`, `settlement-reconciliation`, `scheme-adapter-sendmn`,
`scheme-adapter-ninepay`, `notification-webhook`, `apps/**`, `docker-compose.yml`,
`deploy/helm/**`, `run-fleet.ps1`, `e2e-tests`) — listed as follow-ups in §5.

---

## 1. Before / after, per service

| service | surface | before | after |
|---|---|---|---|
| **auth-identity** | `/internal/auth/token/**` (JWT **minting**), `/internal/auth/keys/**` (partner API-key + webhook-secret **issuance**, T1-1), `/internal/auth/verify`, `/v1/rbac/**`, `/v1/approvals/**` | gate existed but `enabled: ${GMEPAY_INTERNAL_AUTH_ENABLED:false}` ⇒ **anonymous in any unconfigured deploy** (bare `bootRun`, hand-rolled compose, a new env that forgot one var). Compose/Helm happening to set it was the only protection | `enabled: true` **pinned**; secret `${GMEPAY_INTERNAL_AUTH_SECRET:}` (no default); explicit `path-patterns`; **`InternalAuthEnforcedConfig` refuses to boot** if the gate is off / secret blank / a required pattern dropped. 17 routes × 3 credential states proven over real HTTP |
| **auth-identity** | `/actuator/metrics`, `/v3/api-docs`, `/swagger-ui/**` | anonymous | gated. `/actuator/health/**` + `/actuator/info` stay anonymous (probes) |
| **scheme-adapter-zeropay** | `/internal/scheme/zeropay/{submit,cpm}` = **authorise + commit real money at KFTC**, `/cancel` = reverse it, `/balance-check`, `/health` | **no `gmepay.internal-auth` config at all** — fully anonymous on port 8090 | gated; `enabled=true` pinned, secret no default, `InternalAuthEnforcedConfig` fail-closed boot |
| **scheme-adapter-zeropay** | `/internal/scheme/zeropay/registration-status` — the settlement prerequisite gate `settlement-reconciliation` consults before generating ZP0061/ZP0063 | anonymous (forging a positive answer un-blocks settlement generation) | gated |
| **scheme-adapter-zeropay** | `/__data/**` (dump of `zp_committed_txns`, `zp_batch_files`), `/v3/api-docs` | flag-gated, auth-free when on | in the gated pattern list ⇒ **401 anonymously even when the flag is off** (not even fingerprintable); 404 to a trusted caller |
| **rate-fx** | `POST /v1/rates/snapshots` — treasury-rate override; the latest snapshot **wins at resolution**, so one write re-prices every subsequent quote and payment in that currency | unauthenticated. Its own javadoc claimed it "is intended to sit behind the internal-auth gate"; it was not, and the gateway does not front it either (the gateway route is the **exact** path `/v1/rates`, not `/v1/rates/**`) | gated (`/v1/rates/snapshots` + `/v1/rates/snapshots/**`); `enabled=true` pinned; fail-closed boot |
| **rate-fx** | `POST /v1/rates`, `/v1/quotes/**` (public partner surface) | anonymous here, authenticated at the gateway | **deliberately unchanged**, with two tests pinning that they are not swept in (a test also parses the shipped `path-patterns` and asserts it contains neither) |
| **transaction-mgmt** | `/__data/**` — read-only dump of every table = **the transaction ledger** (payer/merchant ids, amounts, QR payloads, scheme refs, refunds) | `@ConditionalOnProperty(gmepay.devtools.enabled)` (off by default) but **no auth when on** | token-gated when on + **boot failure if on without a secret** (`DevSurfaceInternalAuthConfig`) |
| **transaction-mgmt** | `/actuator/metrics`, `/v3/api-docs`, `/swagger-ui/**` | anonymous | gated whenever a secret is configured |
| **transaction-mgmt** | `/v1/transactions/**` | gateway-authenticated | unchanged (test pins it is not gated) |
| **config-registry** | `/__data/**` — dump of the **partner catalogue + credential metadata** (partners, commercial terms, bank accounts, mTLS certs, IP allowlists, webhook subscriptions, api-key rows) | flag-gated, auth-free when on | token-gated when on + boot failure if on without a secret |
| **config-registry** | `/actuator/metrics`, `/v3/api-docs`, `/swagger-ui/**` | anonymous | gated whenever a secret is configured |
| **config-registry** | outbound → prefunding `PUT /internal/v1/prefunding/{id}/credit-limit` | sent **no** token ⇒ would 401 after T0-5 | sends `X-Gme-Internal` (`RestPrefundingCreditLimitClient`) |
| **config-registry** | outbound → auth-identity `/internal/auth/keys` (T1-1) | read only `gmepay.auth-identity.internal-secret`, undeclared in `application.properties` — an env that set only `GMEPAY_INTERNAL_AUTH_SECRET` sent **no** token ⇒ every credential issuance would 401 now that auth-identity enforces | declared with a fallback chain `${GMEPAY_AUTH_IDENTITY_INTERNAL_SECRET:${GMEPAY_INTERNAL_AUTH_SECRET:}}`; the service-specific var still wins where set |
| **qr-service** | outbound → prefunding `POST /internal/v1/prefunding/{id}/{reserve,release}` (CPM) | sent `X-Internal-Token: ${internal.api.token:changeme-internal-token}` — an **invented header nothing on the platform reads**, with a checked-in default literal: zero authentication that looked like some | sends the real `X-Gme-Internal` from `gmepay.internal-auth.secret`, **no default**; the `internal.api.token` key is deleted from `application.yml` (not aliased, so a stale reference fails loudly) |
| **payment-executor** | outbound → scheme-adapter-zeropay `/internal/scheme/zeropay/**` | sent no token ⇒ would 401 now | `RestSchemeClient` sends `X-Gme-Internal`; blank secret ⇒ no header + WARN (fail-closed, not a bypass) |
| **payment-executor** | `GET /v1/balance` | **IDOR** — `X-Partner-Id` default `1`, `X-Partner-Type` default `OVERSEAS`, and an unreachable config-registry silently fell back to the caller's own type claim. Anonymous GET returned partner 1's float; any other partner was one header away. A regression test **pinned** this ("must not be 401") | gated + tenancy fixed — see §2. Pinning test replaced |
| **api-gateway** | route `GET|POST /v1/prefunding/**` → prefunding | published the entire partner-float API (deduct/credit/reverse/reserve/capture/release/cumulative-charge/credit-limit + balance/alerts/deductions) **to the internet** | **route removed**, plus the now-unused `prefundingUri` field/ctor param. Route-table test fails if any route matches `POST /v1/prefunding/1/deduct` |

**Verified nothing legitimate depended on the gateway prefunding route** before removing it: every
consumer (`payment-executor`, `qr-service`, `config-registry`, `ops-partner-bff`) uses
`gmepay.prefunding.base-url` directly, and `e2e-tests` / `gmepay-test-platform` call prefunding's own
port. Repo-wide grep over `*.java`/`*.ts`/`*.tsx`/`*.mjs`/`*.yml` found no caller going through the
gateway.

---

## 2. The `GET /v1/balance` decision

**Choice: internal-only (require `X-Gme-Internal`), not a new public auth model.** Why that is the
most restrictive option that keeps every legitimate caller working:

- **The gateway does not route it.** `GatewayRoutingConfig` publishes `/v1/payments/**` for
  payment-executor; nothing matches `/v1/balance`. So it is not, today, a partner-reachable endpoint —
  the T0-5 note that it was "partner-facing, gateway-fronted" was wrong on the second half.
- **No caller exists.** Repo-wide grep across services, `apps/**`, `ops-partner-bff`, `e2e-tests` and
  `gmepay-test-platform`: the only references are payment-executor's own controller/DTO and its tests.
  Requiring the token therefore breaks nothing.
- **Inventing a public model here would be wrong-layer.** Partner authentication is the gateway's
  HMAC/JWT boundary; duplicating it inside payment-executor would create a second, weaker copy. And
  the wallet-vs-partner-vs-internal ambiguity resolves toward internal: the endpoint reads *another
  service's* store via `PrefundingClient`.

**Gated unconditionally, without making a secret mandatory for boot.** `/v1/balance` is in the filter's
pattern list whether or not a secret is configured, and the registration is always enabled. With a
blank secret `InternalAuthFilter.secretMatches` can match nothing, so the endpoint answers **401 to
every caller** — fail-closed — while a bare local payment-executor still boots and still serves
payments. (Making the secret a boot requirement was rejected as a heavier deployment change than this
gap needs.)

**Tenancy fixed as well**, because authenticating the caller is not the same as scoping it:

1. `X-Partner-Code` **required**, no default → `400 VALIDATION_ERROR`. A header-less request is a
   caller bug, never "partner 1".
2. Partner type resolved **only** from config-registry (the owner). `X-Partner-Type` **removed** — it
   was an unverified claim that could present a LOCAL partner as OVERSEAS and walk past the 403.
3. Config-registry unreachable → `500 INTERNAL_ERROR` (retryable), **never** a fallback to the
   caller's assertion. Unknown code → `400`.
4. `partner_id` in the response is echoed from the registry view; a caller-supplied `X-Partner-Id` is
   ignored, and the prefunding lookup is keyed on the code.

Not claimed as done: per-partner *scoping* (a token claim proving "this partner may only see itself")
if the endpoint is ever published — that is T0-3.

---

## 3. Fail-closed matrix

| service | condition | outcome |
|---|---|---|
| auth-identity, scheme-adapter-zeropay, rate-fx, prefunding | secret absent / blank / whitespace | **refuses to boot** (`<service> refuses to start: … GMEPAY_INTERNAL_AUTH_SECRET … no default`) |
| same | `gmepay.internal-auth.enabled` not `true` | **refuses to boot** |
| same | a required `path-patterns` entry dropped | **refuses to boot** (patterns may be added, never removed) |
| transaction-mgmt, config-registry | `gmepay.devtools.enabled=true` with a blank secret | **refuses to boot** |
| transaction-mgmt, config-registry | flag off, no secret | boots; filter registered **disabled** |
| payment-executor | blank secret | boots; `GET /v1/balance` refuses **every** caller (401); dev surfaces already 404 |
| all client-side (`payment-executor`, `config-registry`, `qr-service`) | blank secret | **no header sent** + startup WARN ⇒ the gated callee answers 401. Never a fabricated credential |

**No new secret literal in main source or config.** The only literals added are test fixtures in test
source sets (`test-fixture-internal-token-not-a-deployment-secret`,
`fixture-token-not-a-deployment-secret`), and one literal was **removed** from main config
(`internal.api.token: changeme-internal-token` in `qr-service/application.yml`).

Two tests read the *shipped* config and fail if the hardening is undone: auth-identity asserts its
`application.yml` no longer mentions `GMEPAY_INTERNAL_AUTH_ENABLED` and still pins `enabled: true`
with an empty-defaulted secret; rate-fx parses the shipped `path-patterns` and asserts it covers the
snapshot path and contains neither `/v1/rates` nor `/v1/quotes/**`.

---

## 4. Required env vars

| var | services that now need it | consequence if missing |
|---|---|---|
| `GMEPAY_INTERNAL_AUTH_SECRET` | **`auth-identity`** | **refuses to start** (was: boots and mints tokens anonymously) |
| | **`scheme-adapter-zeropay`** | **refuses to start** |
| | **`rate-fx`** | **refuses to start** |
| | **`prefunding`** (T0-5) | **refuses to start** |
| | **`payment-executor`** | boots, but its prefunding + zeropay calls are refused ⇒ payments decline; `GET /v1/balance` 401s for everyone |
| | **`config-registry`** | credit-limit push 401s; T1-1 credential issuance 401s (unless `GMEPAY_AUTH_IDENTITY_INTERNAL_SECRET` is set) |
| | **`qr-service`** | CPM reserve/release 401s ⇒ CPM issuance declines |
| | **`transaction-mgmt`** | boots (dev surface off); needed only if `gmepay.devtools.enabled=true` or to gate introspection |
| | `ops-partner-bff`, `settlement-reconciliation` | see §5 — their calls 401 |
| `GMEPAY_AUTH_IDENTITY_INTERNAL_SECRET` | `config-registry`, `ops-partner-bff` | now optional for config-registry (falls back to the platform var) |
| `GMEPAY_INTERNAL_AUTH_ENABLED` | — | **no longer read by any service.** Setting it `false` on auth-identity no longer disables the gate; it is simply ignored |
| `GMEPAY_DEVTOOLS_ENABLED` / `gmepay.devtools.enabled` | transaction-mgmt, config-registry, payment-executor, zeropay | **do not set** in any shared/tunnelled environment |

Same value everywhere. It must be added per-service in `docker-compose.yml`,
`deploy/helm/gmepay/values.yaml` (`envSecretKeys`) and `run-fleet.ps1` — **not applied here** (other
agents own those files). Note the compose default is still the checked-in dev literal (`T0-6`).

---

## 5. Follow-ups in files owned by other agents

**(a) `ops-partner-bff` must present the token** — `client/rest/RestPrefundingClient.java` calls
`GET /v1/prefunding/{code}/{balance,alerts}` (the ops partner-balance panel) with no
`X-Gme-Internal`; it will 401 against the gated prefunding. Pattern to copy: this fix's
`RestPrefundingCreditLimitClient` / `RestPrefundingReservationClient` — a
`@Value("${gmepay.internal-auth.secret:}")` ctor param + `defaultHeader(InternalAuthHeaders.INTERNAL_TOKEN, …)`.
Its `Rest{ApprovalQueue,RbacAdmin,OperatorActionAudit,SandboxKey}Client` already send the token, so
auth-identity's newly enforced gate does not break them.

**(b) `settlement-reconciliation` must present the token** —
`client/RestRegistrationStatusClient.java` calls
`GET /internal/scheme/zeropay/registration-status`, now gated. That client already "fails CLOSED" on
error, so the visible symptom will be **settlement generation blocked**, not a silent wrong answer —
but it must be wired.

**(c) `scheme-adapter-sendmn`, `scheme-adapter-ninepay`, `scheme-adapter-nepal`** — `/internal/scheme/**`
still fully anonymous (`SendmnSchemeController` + `FxRateController`, the NinePay controller,
`NepalSchemeController`). sendmn/ninepay were being edited by other agents during this pass; nepal was
outside the brief. Each is a three-line property block plus a copy of
`InternalAuthEnforcedConfig`; `payment-executor`'s `SendmnRestSchemeClient` / `NepalRestSchemeClient`
then need the token the way `RestSchemeClient` now does.

**(d) deployment files** — add `GMEPAY_INTERNAL_AUTH_SECRET` per §4 to `docker-compose.yml`,
`deploy/helm/gmepay/values.yaml` and `run-fleet.ps1`. Without it, `auth-identity`,
`scheme-adapter-zeropay`, `rate-fx` and `prefunding` will **not start** — this is the intended
fail-closed behaviour, but it means the fleet scripts must be updated in the same change as any deploy.

**(e) `e2e-tests` + `gmepay-test-platform`** — both call the gated services directly
(`e2e-tests` hits `/v1/prefunding/**` and `/internal/scheme/**`; `gmepay-test-platform`'s
`features.ts` hits `/internal/scheme/zeropay/{submit,cpm,cancel,health}` and the prefunding API). They
must send `X-Gme-Internal`, or those cases will now fail with 401.

**(f) `PaymentController` header-trusted tenancy (T0-3, not T0-2)** —
`services/payment-executor/.../web/PaymentController.java:125,298,348,387,499` still resolves partner
type from `X-Partner-Type` with fail-open defaults and a fail-open fallback when config-registry is
unreachable. Unlike `/v1/balance` this **is** the gateway-fronted partner pay surface, so the fix is
claim-scoped tenancy at the gateway boundary, not an internal token — deliberately left alone.

**(g) still-unauthenticated services** — `smart-router`, `merchant-qr-data`, `revenue-ledger`,
`reporting-compliance`, `kyb-adapter`, `notification-webhook`, `settlement-reconciliation`. Most also
ship anonymous `/actuator/metrics` + `/v3/api-docs` (the copy-pasted "no auth wiring assumed in the
sandbox" comment) — a free map of the internal API.

**(h) `scheme-adapter-zeropay` management port** — this service runs the actuator on
`management.server.port=8091`, a **child context**, so servlet filters registered in the app context
do not reach it. `/actuator/metrics` on 8091 therefore cannot be gated from application code; not
exposing that port remains a deployment concern. Recorded in a test comment rather than assumed away.

---

## 6. Tests

`gradlew.bat :services:auth-identity:test :services:scheme-adapter-zeropay:test
:services:transaction-mgmt:test :services:config-registry:test :services:rate-fx:test
:services:qr-service:test :services:api-gateway:test :services:payment-executor:test
:services:prefunding:test` → **BUILD SUCCESSFUL**

| module | tests | failures | errors |
|---|---|---|---|
| auth-identity | 201 | 0 | 0 |
| scheme-adapter-zeropay | 188 | 0 | 0 |
| transaction-mgmt | 145 | 0 | 0 |
| config-registry | 464 | 0 | 0 |
| rate-fx | 48 | 0 | 0 |
| qr-service | 68 | 0 | 0 |
| api-gateway | 89 | 0 | 0 |
| payment-executor | 263 | 0 | 0 |
| prefunding (regression) | 129 | 0 | 0 |
| **total** | **1595** | **0** | **0** |

(Module-scoped on purpose: `services/notification-webhook` is mid-fix by another agent and does not
compile, so a whole-repo build would fail for unrelated reasons.)

New / converted tests:

- `auth-identity/internalauth/InternalAuthLiveGateTest` — `@SpringBootTest(RANDOM_PORT)` +
  `TestRestTemplate` over the **real** filter chain, on the service's own shipped config (the only
  addition being the fixture secret). 17 internal routes × {no credential, tampered credential,
  correct credential}, blank-header case, an anonymous JWT-mint attempt asserted to leak no
  `accessToken`/`eyJ`, probes-open-but-introspection-gated. Assertions key on the gate's own refusal
  body, so the RBAC layer's legitimate 401 on `/v1/approvals/{id}/{approve,reject}` is not mistaken
  for a gate pass/fail. Runs on its own H2 URL because the "correct credential" cases really do mint
  `api_key` rows.
- `auth-identity/config/InternalAuthEnforcedConfigTest` — fail-closed matrix + the shipped-`application.yml` assertion.
- `scheme-adapter-zeropay/api/InternalAuthGateTest` — 6 internal scheme routes × 3 credential states,
  real HTTP; `registration-status` proven to return the real projection to a trusted caller and to
  leak neither `zp0011Succeeded` nor `zp0012Received` to an anonymous one; `/__data` 401-then-404.
- `scheme-adapter-zeropay/config/InternalAuthEnforcedConfigTest` — fail-closed + shipped-config.
- `rate-fx/web/RateSnapshotAdminGateTest` — anonymous override 401 and nothing persisted; trusted
  caller 201 with the persisted snapshot; **public surface not swept in**; introspection gated.
- `rate-fx/config/InternalAuthEnforcedConfigTest` — fail-closed + parses the shipped pattern list.
- `transaction-mgmt` + `config-registry` `config/DevSurfaceInternalAuthConfigTest` — driven through
  the real `InternalAuthFilter` the config registers: flag-on-without-secret ⇒ startup failure;
  default posture ⇒ filter disabled; `/__data` needs the token (tampered token refused, correct token
  passes); `/v1/transactions/**` and `/v1/partners/**` **never** gated. transaction-mgmt's version
  includes a sanity case proving the "not refused" helper really detects pass-through.
- `payment-executor/web/BalanceControllerTest` — **rewritten**, not weakened. The old cases still
  assert the business behaviour (below/not-below threshold, LOCAL 403, money-as-string, history
  on/off) but now go through config-registry as the type authority; six new cases pin the T0-2 rules,
  including `verifyNoInteractions(prefundingClient)` on the header-less request — the exact IDOR.
- `payment-executor/web/SandboxE2eSurfaceTest` — the assertion that pinned `/v1/balance` as *not*
  gated is **inverted** to assert it is; the fail-closed context case updated for the now
  always-enabled filter (asserting via filter behaviour that a blank secret refuses `/v1/balance` yet
  leaves `/v1/payments/**` untouched).
- `config-registry/.../RestPrefundingCreditLimitClientTest` + `qr-service/.../RestPrefundingReservationClientTest`
  — token-on-the-wire assertions bound to the **same builder the production constructor uses**
  (`builderFor(...)` was made package-private for exactly this), plus `headerDoesNotExist` cases
  proving a blank secret sends nothing rather than a fake credential.
- `api-gateway/config/GatewayRouteTableTest` — `prefundingIsNotRoutedToTheInternet()`: the id is gone
  **and** no route's predicate matches `POST /v1/prefunding/1/deduct` (so re-adding it under a
  different id also fails).

One incidental repair: `auth-identity/service/JwtTokenServiceTest.verify_tamperedSignature_isRejectedAsInvalid`
was **intermittently failing** (pre-existing, unrelated). It flipped the *last* base64url character of
the HS256 signature, but only 2 of that character's 6 bits are significant, so depending on the
random `jti`/`iat` the "tampered" token decoded to identical bytes and verified fine. Now flips the
first character of the signature segment — deterministic, and strictly stronger.
