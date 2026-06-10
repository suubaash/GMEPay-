# @gmepay/partner-portal-ui

GMEPay+ **Partner Self-Service Portal** — Next.js 14 (App Router) + React 18 + MUI 6 + Redux Toolkit.

Per the architecture diagram this app is **P2 / read-only** in Phase 1: partners can view
balance, transaction history, webhook configuration, and their profile, but cannot mutate state.
Write operations (webhook edits, settings) are reserved for Phase 2.

## Stack
- Next.js 14 (App Router) — TypeScript strict mode
- React 18 + MUI 6 (Emotion)
- Redux Toolkit 2 + react-redux 9
- React Hook Form 7 + Yup (forms; minimal in Phase 1)
- lottie-react (balance-healthy animation on the balance page)
- Vitest + React Testing Library

## Architecture
All API calls go through the BFF aggregator (`services/bff` — port 8095). Next.js rewrites
`/api/*` to `${NEXT_PUBLIC_BFF_BASE_URL}/*` so the same code runs in dev and behind the ingress.

Endpoints consumed (all under `/v1/portal/{partnerId}/...`):

| Route | Purpose |
|---|---|
| `GET /v1/portal/{partnerId}/balance` | current balance + low-balance threshold |
| `GET /v1/portal/{partnerId}/transactions?page=&size=` | paginated transaction history |
| `GET /v1/portal/{partnerId}/transactions/{txnId}` | single transaction detail |
| `GET /v1/portal/{partnerId}/webhooks` | webhook configuration listing |
| `GET /v1/portal/{partnerId}/profile` | partner id, type, settlement currency, rounding mode |

Per `docs/INTER_SERVICE_CONTRACTS.md` the BFF is the *only* backend this UI talks to — it
fans out to `prefunding`, `transaction-mgmt`, `notification-webhook`, and `config-registry`.

## Money / rounding
Money values are rendered by `src/components/MoneyDisplay.tsx` which respects ISO-4217 scale
(KRW/JPY/VND = 0 decimals, default = 2) per `docs/MONEY_CONVENTION.md`. The partner's
`settlementRoundingMode` is shown read-only on the profile page.

## Local development

```bash
cp .env.example .env.local
# edit NEXT_PUBLIC_PARTNER_ID to a partner that exists in your local config-registry
npm install   # NOT run by the scaffolding agent — run manually
npm run dev   # http://localhost:3001
```

Tests:

```bash
npm run test
```

## Phase 2 (not in this scaffold)
- Webhook edit / rotate-secret flows
- Settlement currency / threshold updates
- Partner-user SSO (replace `X-Partner-Id` dev header with real OAuth2 session)
