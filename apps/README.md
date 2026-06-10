# GMEPay+ Front-end Apps

This directory holds the two TypeScript / Next.js front-ends that talk to the
GMEPay+ platform through the Ops/Partner BFF aggregation service. Together they
implement the **Clients / Actors** box on the Phase 1 architecture diagram
(`docs/SERVICE_MAP.md`).

| App | Who uses it | What it does | Default port |
|---|---|---|---|
| [`admin-ui`](./admin-ui) | GME Ops & Admin operators | Internal Ops portal: partner onboarding (incl. per-partner settlement rounding), schemes, transactions, settlement, revenue, dashboards | 3000 |
| [`partner-portal-ui`](./partner-portal-ui) | Sending partners (GMEREMIT, SENDMN, …) | Partner self-service: prefunding balance, transaction history, statements, webhook config | 3100 |

Both apps follow the **same stack and conventions** — see
[`docs/UI_DEVELOPMENT.md`](../docs/UI_DEVELOPMENT.md) for the design-system
playbook (folder layout, theming, money/rounding helpers, error/empty/loading
patterns, testing).

## Stack (locked)

- **Framework:** Next.js 14 (App Router) + React 18 + TypeScript (strict)
- **UI kit:** MUI 6 (`@mui/material` + `@mui/icons-material`) with a custom
  GMEPay+ theme (`src/theme/theme.ts`)
- **State:** Redux Toolkit + React-Redux (one slice per backend resource)
- **Forms:** React Hook Form + Yup (`@hookform/resolvers`)
- **Animation:** Lottie (`lottie-react`) for empty-state and success illustrations
- **Tests:** Vitest + React Testing Library + jsdom
- **Build/lint:** `next build`, `next lint`, `vitest run`

Neither app talks to backend microservices directly. **All requests go through
the Ops/Partner BFF** (`services/ops-partner-bff`) on `NEXT_PUBLIC_BFF_BASE_URL`;
Next.js rewrites `/api/*` to `${NEXT_PUBLIC_BFF_BASE_URL}/*`.

## Architecture context

```
+--------------------+        +--------------------+
|   admin-ui (3000)  |        | partner-portal-ui  |
|   GME Ops & Admin  |        |   (3100) partners  |
+---------+----------+        +---------+----------+
          |   /api/*   (HTTPS, JWT/Bearer)         |
          v                                        v
                +-----------------------+
                |  ops-partner-bff      |  <-- aggregates the read-side of
                |  (Spring Boot)        |      config-registry, transaction-mgmt,
                +-----------+-----------+      prefunding, settlement, revenue, etc.
                            |
                            v
                  GMEPay+ microservices
```

The boundary is enforced: front-ends import **no** backend types directly —
they consume the BFF's REST surface and shape responses into TypeScript types
under `src/api/types.ts`.

## Dev quickstart (three terminals)

> Prereqs: Node 20+, JDK 21, the local PostgreSQL on `:5433` running
> (see `docker-compose.yml`).

**Terminal 1 — BFF (backs both UIs):**

```bash
./gradlew :services:ops-partner-bff:bootRun
# BFF listens on http://localhost:8080
```

**Terminal 2 — admin-ui:**

```bash
cd apps/admin-ui
cp .env.example .env.local        # sets NEXT_PUBLIC_BFF_BASE_URL=http://localhost:8080
npm install
npm run dev                       # http://localhost:3000
```

**Terminal 3 — partner-portal-ui:**

```bash
cd apps/partner-portal-ui
cp .env.example .env.local
npm install
npm run dev                       # http://localhost:3100
```

## Login (Phase 1 stub creds)

Auth is intentionally stubbed in Phase 1 so the UIs can be built end-to-end
before `auth-identity` is wired in. **These are dev-only credentials** and will
be replaced by real OAuth2 + JWT through `auth-identity` during Phase 4
hardening.

| App | Username / Partner ID | Password |
|---|---|---|
| admin-ui | `admin` | `demo` |
| partner-portal-ui | `GMEREMIT` *or* `SENDMN` | `demo` |

Tokens live in `localStorage` and are auto-injected into the `Authorization`
header by `src/api/client.ts`. The `AuthGate` component redirects to `/login`
when the token is missing or expired.

## Common scripts (per app)

```bash
npm run dev      # local Next.js dev server (HMR)
npm run lint     # eslint + next/core-web-vitals rules
npm run test     # vitest run (CI mode)
npm run build    # production build (next build)
npm start        # serve the production build
```

CI runs `lint`, `test`, and `build` for both apps in parallel via the
`ui-build` job in `.github/workflows/ci.yml`.

## See also

- [`docs/UI_DEVELOPMENT.md`](../docs/UI_DEVELOPMENT.md) — design system,
  conventions, what's deferred to Phase 2
- [`docs/MONEY_CONVENTION.md`](../docs/MONEY_CONVENTION.md) — money +
  per-partner rounding (surfaced in the admin Partner form)
- [`docs/INTER_SERVICE_CONTRACTS.md`](../docs/INTER_SERVICE_CONTRACTS.md) —
  service boundaries the BFF aggregates over
- [`docs/STACK.md`](../docs/STACK.md) — the locked platform stack
