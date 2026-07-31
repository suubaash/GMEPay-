# GMEPay+ Test Platform

An **independent** use-case acceptance test platform for GMEPay+. It lives
**separately** from the Pay+ codebase (`D:\GMEPay+`) and is built on a lighter,
faster stack — **Node.js + TypeScript + Fastify + React/Vite** — because it only
ever talks to the platform over HTTP, so it doesn't need the Java toolchain.

> **Path note.** This project now lives *inside* the platform repo at
> `D:\GMEPay+\code\gmepay-test-platform` and is tracked by its git. It was originally
> kept at `D:\gmepay-test-platform` (no `+` in the path) because Vite/Vitest break on
> a `+` in the project path — the dev server URL-decodes it. That standalone copy no
> longer exists on disk. Only `npm run build` (Vite) is affected by the `+`; the API,
> the CLI, `npm test` and `npm run plan` all run fine from here.

## Authentication (read this first)

On **2026-07-28** the platform became **default-deny**. Three things this tester used
to rely on now grant nothing:

| Retired | What replaced it |
|---|---|
| `POST /v1/auth/login` + `password=demo` | Keycloak realm `gmepay`; the endpoint is **deleted** (401 unauth / 404 with a token) |
| `X-Gme-Permissions` / `X-Partner-Id` / `X-Gme-Principal-Id` headers | JWT `permissions` and `partner_id` claims |
| stub partner pair `pk_test_abc` / `sk_test_xyz` | a real key in **both** auth-identity *and* `gateway.partner-credentials.partners[]` |

So the suite needs **three credentials**, all read from the environment — copy
`.env.example` to `.env` (gitignored) and fill it in. **No credential has a default
and there is no `demo` bypass**; an unset one makes the affected cases report
`BLOCKED` naming the exact variable, rather than failing with an opaque 401.

```bash
npm run plan     # which credential each case needs, and whether you have it.
                 # Sends NO HTTP — works with nothing running.
```

| Credential | Env | Opens |
|---|---|---|
| OIDC operator | `GMEPAY_ADMIN_TOKEN` *or* `GMEPAY_ADMIN_USERNAME`+`GMEPAY_ADMIN_PASSWORD` | ops-partner-bff `/v1/admin/**` |
| OIDC partner | `GMEPAY_PARTNER_TOKEN` *or* `GMEPAY_PARTNER_USERNAME`+`GMEPAY_PARTNER_PASSWORD` | ops-partner-bff `/v1/portal/**` |
| Internal token | `GMEPAY_INTERNAL_AUTH_SECRET` | all `/internal/**`, prefunding `/v1/prefunding/**`, auth-identity `/v1/rbac/**` + `/v1/approvals/**`, kyb-adapter `/v1/kyb/screen` |
| Partner API key | `GMEPAY_PARTNER_API_KEY` + `GMEPAY_PARTNER_HMAC_SECRET` | api-gateway HMAC edge |

There is **no client-credentials grant** — both seeded SPA clients are public + PKCE
with `serviceAccountsEnabled=false`, so the only non-browser path is the password
grant with a realm user, or a token minted elsewhere and passed in.

A `401`/`403` is never reported vaguely: the step log states which credential was
presented, what the platform objected to, and which variable supplies it.

## What it does

Two layers of tests, all in the same registry/runner:

- **36 use-case acceptance tests** (`src/engine/registry.ts`) — the PRD/business-scenario matrix.
- **69 feature tests** (`src/usecases/features.ts`) — endpoint-level coverage of every
  *built* service surface across all 16 services, including: the FX/quote engine,
  prefunding lifecycle, config-registry rules + onboarding wizard + 4-eyes (both the
  self-approval reject and the different-actor happy path), transaction state-machine
  transitions (legal + illegal), real-time scheme authorize/CPM/cancel, QR parsing,
  **api-gateway HMAC sign-accept + tamper-reject** (real HMAC-SHA256 signing),
  **auth-identity RBAC + approvals + API-key issue/revoke**, BFF portal + admin pages,
  webhook config lifecycle, revenue capture/journals, and negative/validation paths.
  Filter the dashboard with **All / Use Cases / Features**.

> **Stale result line removed.** The previous README quoted *57 PASS · 35 BLOCKED · 1
> TODO · 0 FAIL* against a 22/22 fleet. That number predates the 2026-07-28 hardening
> and has **not** been re-measured — the suite has not been run end-to-end since, so
> quoting it would be misleading. Run `npm run cli` against a configured fleet to get
> a current figure.

The tests previously surfaced real platform defects (see the step logs): the wallet
`/v1/pay` orchestration 400s even though its parts pass individually; prefunding
`deduct` returns 500 (not 4xx) on insufficient funds; `revenue-ledger`
rounding-residual posts 406. Those assertions are unchanged and still probe for them.

### Expectations deliberately updated (the product changed, the test was wrong)

Several behaviours changed on 2026-07-28 in ways that make the *old* assertion the
buggy one. Each of these is labelled `EXPECTATION CHANGED` in its `intent`, so
`grep "EXPECTATION CHANGED"` lists them:

| Case | Now asserts |
|---|---|
| `F-BFF-01` | login endpoint **deleted** — 401 unauth / 404 with a token (was: `password=demo` → 200) |
| `F-KYB-01` | verdict must **not** be a fabricated `CLEAR` while no vendor is contracted |
| `F-KYB-03` | partner activation **refuses** with `422 SANCTIONS_NOT_SCREENED` |
| `F-REFUND-01` | partial refund **refused** with `422 PARTIAL_REFUND_UNSUPPORTED` |
| `F-REFUND-02` | cross-border refund **refused** with `422 SCHEME_OPERATION_UNSUPPORTED` |
| `UC-NEPAL-CORRIDOR` | Nepal pay **refuses** with `503 CORRIDOR_PRICING_NOT_CONFIGURED` until priced |
| `UC-09-02` | onboarding cannot complete — activation is fail-closed |
| `UC-10-01/02/03` | portal is **real data** now, not stubs; blocked on data + token |
| `UC-API-AUTH` | real split credential store; replay/allowlist/rate-limit fail-closed |
| `UC-OPS-RBAC` | Keycloak is the login path; `password=demo` is gone |
| `UC-CANCEL-PAYMENT` | refusals replace the old "prefund return ZERO" approximation |
| `UC-SANDBOX-E2E` | `REMOVED` — the runner is 404-by-default and the admin-ui `/e2e/*` rewrite is deleted |

Each test either:

- **runs a real test** — fires HTTP at the live services and asserts the business
  outcome (e.g. UC-01-01 posts `/v1/pay` and checks `APPROVED` + a KRW 500 fee), or
- reports **BLOCKED** with the real reason (scheduler off, stub client, empty data),
  or **TODO** if not yet automated.

So "do all intended functions work?" becomes "is the matrix green?" — and the
amber/red rows are exactly your remaining work.

| Status | Meaning |
|---|---|
| `PASS` | Business outcome verified end-to-end |
| `FAIL` | Reached the service but the outcome was wrong — **or a credential was presented and refused** |
| `BLOCKED` | Precondition missing (service down, no data, feature disabled, **credential not configured**) |
| `REMOVED` (`UNSUPPORTED`) | The capability was **deliberately withdrawn** from the platform. Never runs, never fails — kept in the matrix so the withdrawal is recorded rather than silently absent. |
| `TODO` | No automated test written yet (matrix placeholder) |

The `BLOCKED` vs `FAIL` split is the point: *"you did not configure the tester"* and
*"the platform said no"* are different problems and must never look the same.

## Run it

```bash
cd D:\gmepay-test-platform
npm install
npm run dev          # starts the API (:4000) + dashboard (:5173)
# open http://localhost:5173
```

For tests to PASS, the GMEPay+ fleet must be running. In another terminal:

```powershell
cd D:\GMEPay+\code
.\run-fleet.ps1 -Subset money     # boots the core payment cascade
```

Then click the **fleet badge** in the dashboard to refresh health, and **Run MVP**.

### Headless / CI

```bash
npm run cli            # run every use case, print a table, exit 1 on any FAIL
npm run cli -- --mvp   # Phase-1 MVP subset only
npm run cli -- --plan  # dry run: credential per case, no HTTP
```

### Static verification (no fleet required)

```bash
npm run verify         # typecheck + unit tests + credential plan
npm run typecheck      # tsc --noEmit over src, web/src and vite.config.ts
npm test               # node:test units — credential layer + registry invariants
```

The unit tests need no fleet, no Keycloak and no Docker. They assert the things that
would otherwise only surface at runtime: that no credential has a hardcoded default,
that no status output leaks a secret value, that the retired stub key and the
`password=demo` bypass cannot come back, that every case touching a gated surface
declares the credential it needs, and that a declared credential is actually
presented. One test guards the guards — the runner transpiles before stringifying
functions, so a quote-sensitive source assertion would pass vacuously.

## Layout

```
.env.example           credential template (copy to .env — gitignored)
src/
  config.ts            service ports (from run-fleet.ps1) + fixtures + credential env
  plan.ts              dry-run: credential per case, no HTTP
  shared/types.ts      shared result/metadata types
  engine/
    http.ts            fetch wrapper
    assert.ts          step recorder + assertions (PASS/FAIL/BLOCKED)
    credentials.ts     ← the three credential paths + diagnostic 401/403 messages
    client.ts          HTTP client; presents credentials, turns 401/403 into a reason
    registry.ts        ← the PRD use cases (add/extend tests here)
    runner.ts          executes one use case, classifies the outcome
    health.ts          pings every service
    *.test.ts          node:test units (no fleet needed)
  server.ts            Fastify API (/api/usecases, /api/health, /api/plan, /api/run, SSE)
  cli.ts               headless runner (--mvp, --plan)
web/                   React + Vite dashboard
```

### Adding a test that needs a credential

Declare it and present it — the two are cross-checked by a unit test:

```ts
feat('F-X-01', 'prefunding', 'title', ['prefunding'], 'intent',
  async ({ client, check }) => {
    const res = await client.call('prefunding', 'GET', '/v1/prefunding/X/balance',
      undefined, { as: 'internal' });      // ← presents X-Gme-Internal
    check.equal(res.status, 200, 'ok');
  },
  ['internal']);                            // ← declares it, so --plan can report it
```

To assert that a gate *exists*, probe it unauthenticated with
`{ expectAuthFailure: true }` and assert the 401 yourself.

## Adding a test

Edit `src/engine/registry.ts`. Give a use case a `run({ client, check })`:

```ts
async run({ client, check }) {
  const res = await client.pay({ qrPayload, amountKrw: '50000', partner: 'GMEREMIT', userRef });
  check.equal(res.status, 201, 'HTTP 201 Created');
  check.equal(res.json.status, 'APPROVED', 'payment APPROVED');
}
```

`check.*` writes a pass/fail step into the log the dashboard shows; throwing means
FAIL; `check.blockedIf(cond, reason)` marks BLOCKED.
