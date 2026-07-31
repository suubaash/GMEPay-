> 작업: gmepay-test-platform auth repair / 출처: agent

# gmepay-test-platform — re-authenticated against the hardened platform

**Status:** repaired, statically verified, **uncommitted**. The suite has **not** been run
end-to-end — starting Docker, the fleet or Keycloak was out of scope, so every runtime
claim below is derived from the platform's own config/code, not observed.

## 0. Two facts that change how you read this

**(a) The project is not where the brief says it is.** `D:\gmepay-test-platform` **does not
exist on disk**. The tester is the **git-tracked `gmepay-test-platform/` subtree of this
repo** (`D:\GMEPay+\code\gmepay-test-platform`, 38 tracked files, on
`feat/exec-gap-closure-2026-07-28`). `fix_fleet-config-convergence_2026-07-28.md:199-202`
half-anticipated this — *"a separate Node/TS project (`D:\gmepay-test-platform` + a copy
in-repo)"* — but only the in-repo copy survives.

This collides with the instruction *"must NOT modify `D:\GMEPay+\code`"*: the thing to fix
lives inside the thing not to touch. Resolution taken — **all edits confined to
`gmepay-test-platform/**`** plus the one permitted `GAP_REGISTER.md` line. `git status`
confirms nothing outside that subtree changed. Because that constraint is the more
specific one, and because committing would inject a commit into this branch's lineage,
**the changes are left uncommitted** (one of the two outcomes the brief anticipated).

**(b) One gated surface appears in no report.** `kyb-adapter` sets
`gmepay.internal-auth.enabled=true` with
`path-patterns=/v1/kyb/screen,/v1/kyb/verify,/v1/kyb/result/**,/v1/screening/**,…`
(`services/kyb-adapter/src/main/resources/application.properties:55-57`). The rollout
reports list 11 services and do not include it, so **`F-KYB-01` would have 401'd with no
warning anywhere**. Found by reading the properties rather than trusting the reports.

## 1. Use case × blocker — the blast radius

`GET`/`POST` targets below are the tester's own calls. "Now rejects" is derived from each
service's shipped `path-patterns` / security config.

### A. `X-Gme-Internal` missing → **401** `{"code":"UNAUTHORIZED","message":"internal service authentication required"}`

| Case | Endpoint | Why it now rejects |
|---|---|---|
| `UC-06-01` | prefunding `GET /v1/prefunding/{code}/balance` | prefunding gates **`/v1/prefunding/**`** *and* `/internal/**` |
| `F-PREFUND-01..04` | prefunding provision/deduct/credit/reverse/alerts/balance | same — the whole money API |
| `F-SCHEME-01..04` | zeropay `/internal/scheme/zeropay/{submit,cpm,cancel,health}` | `/internal/**` + `/__data/**` gated |
| `F-AUTH-01` | auth-identity `POST /internal/auth/verify` | auth-identity has **no** public surface |
| `F-KEY-01` | auth-identity `POST /internal/auth/keys` (+ revoke) | same |
| `F-RBAC-01/02` | auth-identity `/v1/rbac/**` | gated; the old `X-Gme-Permissions` header grants nothing |
| `F-APPROVAL-01` | auth-identity `POST /v1/approvals` | gated; `X-Gme-Principal-Id` is not authorization |
| **`F-KYB-01`** | kyb-adapter `POST /v1/kyb/screen` | **gated — unreported anywhere (see §0b)** |

### B. No bearer token → **401 with an empty body** (`HttpStatusEntryPoint`), or **403**

| Case | Endpoint | Why |
|---|---|---|
| `F-BFF-01` | bff `POST /v1/auth/login` | **endpoint deleted.** 401 unauth / 404 with a token |
| `F-BFF-02/03/04` | bff `/v1/admin/{partners,dashboard,system/health}` | default-deny; needs a `permissions` claim |
| `F-ADMIN-01/02/03` | bff `/v1/admin/{transactions,settlement/recent,revenue/summary}` | same |
| `F-PORTAL-01/02/03` | bff `/v1/portal/partner_test_001/**` | **two faults**: no token, *and* `partner_test_001` was never a real partner code — `PartnerDirectory` fails closed on it and issues no upstream call |

### C. Partner edge — the stub pair is dead

| Case | Endpoint | Why |
|---|---|---|
| `F-GW-02` | gateway `GET /v1/route` signed | `pk_test_abc`/`sk_test_xyz` → **401 `INVALID_API_KEY`**; `StubPartnerCredentialService` deleted from `src/main`. Also `X-Nonce` now mandatory (absent → **400**) and `X-Partner-Id` must match (→ **403 `PARTNER_ID_MISMATCH`**) — the old `gatewayHeaders()` sent **neither** |
| `F-GW-03` | tampered signature | would still "pass", **for the wrong reason** — a dead key rejects everything, so the assertion proved nothing |
| `F-GW-01` | unsigned request | genuinely unaffected — the one edge case that was already right |

### D. Verified NOT gated (no change needed)

`rate-fx` `POST /v1/rates` + `/v1/quotes/**` (only `/v1/rates/snapshots**` is gated) ·
`config-registry` `/v1/partners/**`, `/v1/schemes`, `/v1/rules/validate`,
`/v1/change-requests/**` · `transaction-mgmt` `/v1/transactions/**` · `qr-service` ·
`merchant-qr-data` · `smart-router` · `revenue-ledger` · `settlement-reconciliation` ·
`reporting-compliance` · `notification-webhook` `/v1/webhook-configs` (its gate covers
`/v1/webhooks/endpoints/**` and is default-off) · `kyb-adapter` `/v1/kyb/health` · all sims.

**Totals: 105 cases — 30 credential-dependent (15 internal · 7 operator · 5 partner-scoped · 3 partner-key), 74 need none, 1 unsupported.**

## 2. Expectations changed because the product is now intentionally different

These are the cases where **the old assertion had become the bug**. Each is tagged
`EXPECTATION CHANGED` in its `intent`, so the list is greppable.

| Case | Was | Now asserts |
|---|---|---|
| `F-BFF-01` | `password=demo` → 200 + token | **401 unauth / 404 with a valid token.** Both statuses deliberately — together they prove *deleted*, not merely *blocked* |
| `F-KYB-03` *(new)* | — | activation **refuses 422 `SANCTIONS_NOT_SCREENED`**; the precondition precheck is still 200 |
| `F-KYB-01` | any verdict string | verdict must **not** be `CLEAR` — an uncontracted provider clearing a partner is the failure mode |
| `F-REFUND-01` *(new)* | — | partial refund **422 `PARTIAL_REFUND_UNSUPPORTED`**, `retryable=false`, nothing mutated |
| `F-REFUND-02` *(new)* | — | cross-border refund **422 `SCHEME_OPERATION_UNSUPPORTED`**; sends `schemeId` **explicitly**, because omitting it silently falls back to ZeroPay routing and yields a foreign decline instead |
| `UC-NEPAL-CORRIDOR` *(new)* | — | Nepal pay **503 `CORRIDOR_PRICING_NOT_CONFIGURED`** (vs `CORRIDOR_RATE_UNAVAILABLE`, retryable). Asserting a *successful* Nepal payment would assert unpriced money movement |
| `UC-09-02` | "wizard exists, tables empty" | onboarding **cannot complete** — activation is fail-closed by design |
| `UC-10-01/02/03` | "stub-backed" | **real data** (`Rest*Client` wired). Blocked on data + token, not stubs. Records that absent fields are **null by design**, so no test may assert them populated |
| `UC-API-AUTH` | "2-key stub, no replay filter" | real split credential store; replay/allowlist/rate-limit all **fail-closed** |
| `UC-OPS-RBAC` | "`password=demo` mock, Keycloak unwired" | Keycloak **is** the login path; header-asserted permissions grant nothing |
| `UC-CANCEL-PAYMENT` | "prefund return ZERO approximation" | real reversal booking; **refusal** replaces approximation |
| `UC-SANDBOX-E2E` *(new)* | — | **`UNSUPPORTED`** — see §5 |

## 3. What was built

- **`src/engine/credentials.ts`** (new) — the three credential paths, `MissingCredentialError`
  (→ BLOCKED, names the env var) vs `AuthRejectedError` (→ FAIL, names the credential and
  what the platform objected to), lazy token acquisition with caching, and claim decoding.
  It records that **client-credentials is impossible** (both seeded clients are public+PKCE,
  `serviceAccountsEnabled=false`), so nobody wastes an afternoon on it.
- **`config.ts`** — credentials read through **getters**, so state reflects the live
  environment; a dependency-free `.env` loader; **no secret has a default** (unit-enforced).
- **`client.ts`** — `call(..., { as, expectAuthFailure })`. Resolves the credential *before*
  the request, logs what is presented (claims, never secrets), converts an unexpected
  401/403 into a diagnostic.
- **`testkit.ts`** — `gatewayHeaders()` now refuses to sign without real credentials and
  sends `X-Nonce` + `X-Partner-Id`; the `rbacHeaders()` helper is **deleted** so no future
  test is written against an authorization input that no longer exists.
- **New `UNSUPPORTED` status** through types/runner/CLI/dashboard, rendered as `REMOVED` in
  a neutral colour — it is a product decision, not an alarm.
- **`--plan` dry run** (`npm run plan`, also `GET /api/plan`) — per-case credential and
  readiness, **zero HTTP**, so it answers "what will 401 and why" with nothing running.
- **`.env.example`**, `.gitignore` hardened (`.env`, `.env.*`), README rewritten.

**Nothing outside `gmepay-test-platform/` was touched** except the one `GAP_REGISTER.md` line.

## 4. Verification — and its limits

`npm run verify` (typecheck → 30 unit tests → plan) **passes**, as does `npm run build`.

| Command | Result |
|---|---|
| `npm run typecheck` | **0 errors** (fixed a **pre-existing** failure: `web/src/api.ts` duplicates the shared types and had drifted — `kind` was missing) |
| `npm test` | **30/30** (`node:test`, no new dependency) |
| `npm run plan` | 105 cases · 74 ready · 30 blocked on credentials · 1 unsupported |
| `npm run build` | clean (the `+`-in-path bug affects the dev server, not the build) |

The units assert what would otherwise only surface at runtime: no credential has a
hardcoded default; no status output leaks a secret **value**; the stub key and the
`password=demo` bypass **cannot come back**; every case touching a gated surface **declares**
the credential; a declared credential is **actually presented**.

**Two honest caveats.** (1) The runner transpiles before stringifying functions, so my first
source-matching assertions were quote-sensitive — one passed **vacuously**. There is now a
`test('the source-matching helpers actually match transpiled output')` guarding the guards,
plus a `checked > 10` counter. (2) **No case was executed against a real service.** Whether
`GMEPAY_INTERNAL_AUTH_SECRET` actually opens prefunding, whether the seeded realm issues a
token with the right claims, and whether the four refusal codes come back exactly as coded
are all **unverified**. The 105-case suite is wired, not proven.

## 5. Known blocked path — confirmed gone, and harmless here

The sandbox E2E runner is `@ConditionalOnProperty(gmepay.sandbox.e2e.enabled)`, pinned
`false` everywhere → `/v1/sandbox/e2e/**` **404s with and without a token**. I verified the
admin-ui rewrite deletion directly (`apps/admin-ui/next.config.mjs:30-31` now carries a
`REMOVED (GAP T0-5)` comment where the `/e2e/:path*` block was) — one report was unsure
whether it had actually been removed. **It had.**

**The tester never routed through that path**, so nothing regressed. `UC-SANDBOX-E2E` was
added purely so the matrix records the capability as *deliberately withdrawn* rather than
silently absent — deleting it would quietly shrink the traceability list.

## 6. Left open

1. **Uncommitted** (§0a). If you want it committed, say so — it means a commit in this repo.
2. **Never executed.** The only way to close this is a configured fleet + `npm run cli`.
3. **No credentials provisioned.** All 30 credential-dependent cases report BLOCKED until an
   operator supplies them. The partner-key path needs a **two-sided** provision (auth-identity
   *and* `gateway.partner-credentials.partners[]`) — a key in one half fails.
4. **`F-REFUND-02` may not reach its assertion** — it uses a synthetic reference, so
   payment-executor may 404 on lookup before resolving the scheme. It reports BLOCKED with
   that exact reason rather than pretending; a real completed SENDMN payment would close it.
5. **The "57 PASS · 35 BLOCKED" README figure is withdrawn, not re-measured.** It predates the
   hardening. I removed it rather than leave a stale number a sales conversation might quote.
6. **`kyb-adapter` should be added to the canonical gated-service list** (§0b) — the rollout
   reports say 11 services; the code says 12.
