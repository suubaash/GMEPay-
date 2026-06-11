# @gmepay/partner-portal-ui

GMEPay+ **Partner Self-Service Portal** — Next.js 14 (App Router) + React 18 + MUI 6 + Redux Toolkit.

Per the architecture diagram this app is **P2 / read-only** in Phase 1: partners can view
their overview, balance, transaction history, webhook configuration, and profile, but
cannot mutate state. Write operations (webhook edits, settings) are reserved for Phase 2.

## Stack
- Next.js 14 (App Router) — TypeScript strict mode
- React 18 + MUI 6 (Emotion)
- Redux Toolkit 2 + react-redux 9 (one focused slice per BFF resource)
- React Hook Form 7 + Yup (login form + future write flows)
- lottie-react (balance-celebration + empty-state animations)
- Vitest + React Testing Library

## Architecture
All API calls go through the BFF aggregator (`services/bff` — port 8095). Next.js rewrites
`/api/*` to `${NEXT_PUBLIC_BFF_BASE_URL}/*` so the same code runs in dev and behind the
ingress.

Endpoints consumed:

| Route | Purpose |
|---|---|
| `POST /v1/auth/login` | partner login (returns token + partnerId) |
| `GET /v1/portal/{partnerId}/overview` | balance + recent-activity count + last settlement |
| `GET /v1/portal/{partnerId}/balance` | current balance + low-balance threshold |
| `GET /v1/portal/{partnerId}/transactions?page=&size=&sort=createdAt,desc` | paginated history |
| `GET /v1/portal/{partnerId}/transactions/{txnId}` | single transaction detail |
| `GET /v1/portal/{partnerId}/webhooks` | webhook config listing (read-only) |
| `GET /v1/portal/{partnerId}/profile` | partner type, settlement currency, rounding mode |

Per `docs/INTER_SERVICE_CONTRACTS.md` the BFF is the *only* backend this UI talks to — it
fans out to `prefunding`, `transaction-mgmt`, `notification-webhook`, `config-registry`,
`settlement-reconciliation`, and `auth-identity`.

## Auth (Phase 1)

Partners sign in at **`/login`** with their partner id + password. The login form posts
to `POST /v1/auth/login`; on success we persist:

- `gmepay.partnerToken` — JWT bearer token in `localStorage`
- `gmepay.partnerId` — selected partner id in `localStorage`

The API client then sends `Authorization: Bearer <token>` and `X-Partner-Id: <id>` on
every BFF request. An `AuthGate` (`components/AuthGate.tsx`) wraps the app and redirects
unauthenticated users to `/login` for every non-public route.

### Phase 1 demo credentials

The BFF dev profile accepts:

| partnerId | password |
|---|---|
| `GMEREMIT` | `demo` |
| `SENDMN`   | `demo` |

These are placeholders — production wires real OAuth2 / partner SSO and the token will
move to an httpOnly session cookie. The `api/auth.ts` surface (login / logout / getToken /
isAuthenticated) is stable across that change.

## Money / rounding
Money values are rendered by `src/components/MoneyDisplay.tsx` which respects ISO-4217
scale (KRW/JPY/VND = 0 decimals, default = 2) per `docs/MONEY_CONVENTION.md`. Pass
`showRawTooltip` to surface the unrounded server-supplied value on hover (useful for
audit screens). The partner's `settlementRoundingMode` is highlighted on the profile
page since it determines how their liability is booked under
`lib-money/SettlementRounding.book(...)`.

## UI building blocks

Reused across every page so loading/error/empty states feel consistent:

| Component | Purpose |
|---|---|
| `LoadingSkeleton` | MUI Skeletons in `card` / `table` / `detail` / `stat` variants — reserves layout space while data loads |
| `ErrorAlert` | Standard error surface with an optional "Try again" button wired to a retry callback |
| `EmptyState` | Lottie + headline + optional action — drop-in for empty tables / lists |
| `SnackbarProvider` + `useSnackbar` | App-wide toast system (`showError`, `showSuccess`, `showInfo`) |

## Redux store layout

One focused slice per BFF resource so pages re-render only on the data they consume:

```
state.auth          // token, partnerId, login status/error
state.overview      // dashboard aggregate
state.balance       // balance + threshold
state.transactions  // .page (list) and .detail (single)
state.webhooks      // webhook subscriptions
state.profile       // partner profile incl. rounding mode
```

## Local development

```bash
cp .env.example .env.local
# edit NEXT_PUBLIC_PARTNER_ID for env-var-only dev (skip the login screen).
# Otherwise just navigate to http://localhost:3001/login and sign in.
npm install   # NOT run by the scaffolding agent — run manually
npm run dev   # http://localhost:3001
```

Tests:

```bash
npm run test          # one-shot run
npm run test:watch    # watch mode
```

### Verify locally

1. `npm install && npm run dev`
2. Open <http://localhost:3001> — AuthGate redirects to `/login`.
3. Sign in as `GMEREMIT` / `demo` (or `SENDMN` / `demo`).
4. Walk the nav: Overview → Balance → Transactions → click a row → Webhooks → Profile.
5. Stop the BFF and click a "Try again" button — the error UI + retry path should work.
6. `npm run test` — all Vitest suites green.

## Phase 2 (not in this scaffold)
- Webhook edit / rotate-secret flows
- Settlement currency / threshold updates
- Real OAuth2 partner SSO (replace localStorage bearer + `X-Partner-Id` dev header)
- Token refresh wiring (`portalApi.refreshToken` is a Phase-1 stub)
