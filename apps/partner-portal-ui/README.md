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

## Auth (Keycloak OIDC only)

Partners sign in at **`/login`**, which has exactly one affordance: *Sign in with
Keycloak*. It starts an OIDC authorization-code + PKCE (S256) flow against realm
`gmepay`, client `partner-portal-ui` (see `docker/keycloak/README.md` for the
canonical realm/client/issuer/port table). Keycloak returns to `/auth/callback`,
which exchanges the code and persists:

- `gmepay.partnerToken` — Keycloak **access_token** (the BFF bearer)
- `gmepay.partnerRefreshToken` / `gmepay.partnerIdToken`
- `gmepay.partnerTokenExpiresAt` — ms-since-epoch, used by `isAuthenticated()`
- `gmepay.partnerId` — the token's **`partner_id` claim** (never a form field)

Every BFF request carries `Authorization: Bearer <access_token>`; a 401 triggers one
silent `refresh_token` exchange and a replay. `ops-partner-bff` is an OAuth2 resource
server that authorizes `/v1/portal/{partnerId}/**` by comparing the path segment with
the token's `partner_id` claim, so:

- the `X-Partner-Id` header is **not sent any more** — it was never an identity, and
  the BFF reads it nowhere (gap T0-4);
- `POST /v1/auth/login` and the `password=demo` form are **gone** — that endpoint was
  deleted from the BFF (gap T0-1);
- a Keycloak user with no `partner_id` attribute sees an explicit "your account is not
  linked to a partner" message instead of pages that all 403.

`AuthGate` (`components/AuthGate.jsx`) wraps the app: it redirects to Keycloak on
protected routes, or lands on `/login` when `NEXT_PUBLIC_ALLOW_DEV_LOGIN=true` (which
only suppresses the automatic redirect — it is not a password bypass).

### Seeded dev users (realm `gmepay`, dev only)

| username | password | scope |
|---|---|---|
| `partner-demo` | `demo` | `partner_id=GMEREMIT` |
| `partner-sendmn` | `demo` | `partner_id=SENDMN` |

`GMEREMIT` / `SENDMN` are the codes `config-registry`'s `PartnerSeeder` creates, so the
portal resolves real data. Production federates real users (SCIM/LDAP or the partner's
own IdP as an identity provider inside realm `gmepay`) and the token will move to an
httpOnly session cookie.

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
# Set BFF_PROXY_TARGET to your running BFF (18095 for run-fleet.ps1, 8095 for compose)
# and keep NEXT_PUBLIC_KEYCLOAK_URL/CLIENT_ID matching the realm seed.
# Do NOT set NEXT_PUBLIC_BFF_BASE_URL — it makes the browser call the BFF
# cross-origin and the BFF configures no CORS.
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
2. Start Keycloak (`docker compose --profile core up -d keycloak`) and the BFF with
   `OIDC_ISSUER_URI=http://localhost:8097/realms/gmepay`.
3. Open <http://localhost:3001> — AuthGate lands on `/login`; click *Sign in with
   Keycloak* and authenticate as `partner-demo` / `demo`.
4. Walk the nav: Overview → Balance → Transactions → click a row → Webhooks → Profile.
5. Stop the BFF and click a "Try again" button — the error UI + retry path should work.
6. `npm run test` — all Vitest suites green.

## Phase 2 (not in this scaffold)
- Webhook edit / rotate-secret flows
- Settlement currency / threshold updates
- Move the bearer from localStorage to an httpOnly session cookie (ADR-011 phase D)
- Partner-managed webhook secrets / settlement settings (write paths)
