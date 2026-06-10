# GMEPay+ Ops/Admin Portal

Next.js 14 + MUI + Redux Toolkit front-end for the GMEPay+ Ops/Admin team.

This is the "Ops/Admin Portal — React/Next.js — GME Ops & Admin" box in the
Phase 1 architecture diagram. It talks ONLY to the Ops/Partner BFF
(`NEXT_PUBLIC_BFF_BASE_URL`) — never to backend microservices directly.

## Stack

- Next.js 14 (App Router) + React 18 + TypeScript (strict)
- MUI 6 (`@mui/material` + `@mui/icons-material`)
- Redux Toolkit + React-Redux
- React Hook Form + Yup (`@hookform/resolvers`)
- Lottie (`lottie-react`)
- Vitest + Testing Library

## Run locally

```bash
cp .env.example .env.local   # set NEXT_PUBLIC_BFF_BASE_URL if BFF is not on :8095
npm install
npm run dev
```

The dev server listens on http://localhost:3000.
All `/api/*` requests are rewritten to `$NEXT_PUBLIC_BFF_BASE_URL/*` (default
`http://localhost:8095` — see `next.config.mjs`).

### Pointing at a running BFF

The Ops/Partner BFF is a separate Spring Boot service in this monorepo. Start
it (e.g. `gradlew :services:bff:bootRun`) on port 8095 BEFORE running
`npm run dev` so the rewrites have somewhere to land. To use a different
host/port:

```bash
NEXT_PUBLIC_BFF_BASE_URL=https://ops.dev.gmepay.internal npm run dev
```

### Login

Visit http://localhost:3000 — the AuthGate will bounce you to `/login` if no
token is stored. Dev credentials:

- **Username:** `admin`
- **Password:** `demo`

A successful login stores the JWT under the `gmepay.adminToken` localStorage
key; every subsequent fetch through `src/api/client.ts` attaches
`Authorization: Bearer <token>`. Click the "Logout" button in the top app
bar to clear the token and return to `/login`.

## Build & test

```bash
npm run lint
npm run test
npm run build
```

## Key features wired

- **Login** (`/login`) — username/password form (RHF + Yup) calling
  `POST /v1/auth/login`. Token persisted to localStorage; `AuthGate`
  redirects unauthenticated users.
- **Dashboard** (`/`) — 4 MUI cards backed by `GET /v1/admin/dashboard` with
  Skeleton / ErrorAlert / EmptyState (Lottie) UX states.
- **Partners**
  - `/partners` — table from `GET /v1/admin/partners`, row click → detail.
  - `/partners/new` — form posting to `POST /v1/admin/partners`. Surfaces
    the per-partner settlement rounding mode selector (HALF_UP default +
    6 others) per `docs/MONEY_CONVENTION.md`.
  - `/partners/[id]` — detail card + inline "Edit rounding mode" dialog
    PUTting `/v1/admin/partners/{id}/rounding-mode`.
- **Schemes** (`/schemes`) — `GET /v1/admin/schemes` table with status chip.
- **Transactions**
  - `/transactions` — filter form (partner, scheme, status, dates) +
    paginated table from `GET /v1/admin/transactions` (Spring Page).
  - `/transactions/[txnId]` — full transaction detail, including the
    rounding-lock fields (booked, mode, residual).
- **Settlement**
  - `/settlement` — recent batches from `GET /v1/admin/settlement/recent`.
  - `/settlement/[batchId]` — header + lines table with matched chips.
- **Revenue** (`/revenue`) — date-range picker + dimension dropdown,
  summary cards (revenue + rounding gain/loss) and breakdown table.

## Shared components

Reusable building blocks under `src/components/`:

| Component             | Purpose                                                  |
|-----------------------|----------------------------------------------------------|
| `MoneyDisplay`        | Decimal-string Money formatter w/ tooltip, negative red. |
| `RoundingModeSelect`  | The 7 java.math.RoundingMode values.                     |
| `StatusChip`          | TxnStatus → coloured MUI Chip.                           |
| `ErrorAlert`          | Error banner with Retry button.                          |
| `EmptyState`          | Centered Lottie + heading + optional CTA.                |
| `LoadingSkeleton`     | `card` / `table` / `page` MUI Skeleton presets.          |
| `ConfirmDialog`       | MUI Dialog confirm prompt for destructive ops.           |
| `SnackbarProvider`    | App-wide `useSnackbar()` hook (success/error/info/warn). |
| `DateRangePicker`     | Two `<input type="date">` fields.                        |
| `AuthGate`            | Redirects unauthenticated users to `/login`.             |
| `AppShellChrome`      | Conditionally renders the app shell (skips on /login).   |

## Redux slices

`src/store/`:

- `authSlice` — login flow (token, username, roles).
- `dashboardSlice` — KPI cards.
- `partnersSlice` — list + detail cache + create/update flags.
- `schemesSlice` — list.
- `transactionsSlice` — paginated search + detail cache.
- `settlementSlice` — list + detail cache.
- `revenueSlice` — summary + breakdown + date range.

## API client

`src/api/client.ts` exposes one typed wrapper per BFF endpoint and
automatically injects the `Authorization: Bearer <token>` header when a
token is present. A 401 clears the token; the AuthGate then bounces the
user back to `/login`. All DTO shapes live in `src/api/types.ts`.
