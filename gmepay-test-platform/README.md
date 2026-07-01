# GMEPay+ Test Platform

An **independent** use-case acceptance test platform for GMEPay+. It lives
**separately** from the Pay+ codebase (`D:\GMEPay+`) and is built on a lighter,
faster stack — **Node.js + TypeScript + Fastify + React/Vite** — because it only
ever talks to the platform over HTTP, so it doesn't need the Java toolchain.

> Located at `D:\gmepay-test-platform` (no `+` in the path) on purpose — Vite/Vitest
> break on a `+` in the project path because the dev server URL-decodes it.

## What it does

Two layers of tests, all in the same registry/runner:

- **34 use-case acceptance tests** (`src/engine/registry.ts`) — the PRD/business-scenario matrix.
- **59 feature tests** (`src/usecases/features.ts`) — endpoint-level coverage of every
  *built* service surface across all 16 services, including: the FX/quote engine,
  prefunding lifecycle, config-registry rules + onboarding wizard + 4-eyes (both the
  self-approval reject and the different-actor happy path), transaction state-machine
  transitions (legal + illegal), real-time scheme authorize/CPM/cancel, QR parsing,
  **api-gateway HMAC sign-accept + tamper-reject** (real HMAC-SHA256 signing),
  **auth-identity RBAC + approvals + API-key issue/revoke**, BFF portal + admin pages,
  webhook config lifecycle, revenue capture/journals, and negative/validation paths.
  Filter the dashboard with **All / Use Cases / Features**.

Against the full live fleet (22/22 up) the current result is **57 PASS · 35 BLOCKED · 1 TODO · 0 FAIL**.
The tests also surfaced real platform defects (see the step logs): the wallet `/v1/pay`
orchestration 400s even though its parts pass individually; prefunding `deduct` returns
500 (not 4xx) on insufficient funds; `revenue-ledger` rounding-residual posts 406.

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
| `FAIL` | Reached the service but the outcome was wrong |
| `BLOCKED` | Precondition missing (service down, no data, feature stubbed/disabled) |
| `TODO` | No automated test written yet (matrix placeholder) |

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
```

## Layout

```
src/
  config.ts            service ports (from run-fleet.ps1) + test fixtures
  shared/types.ts      shared result/metadata types
  engine/
    http.ts            fetch wrapper
    assert.ts          step recorder + assertions (PASS/FAIL/BLOCKED)
    client.ts          typed HTTP client over the GMEPay+ REST surface
    registry.ts        ← the 34 use cases (add/extend tests here)
    runner.ts          executes one use case, classifies the outcome
    health.ts          pings every service
  server.ts            Fastify API (/api/usecases, /api/health, /api/run, SSE)
  cli.ts               headless runner
web/                   React + Vite dashboard
```

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
