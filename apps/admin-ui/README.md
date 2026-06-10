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
cp .env.example .env.local
npm install
npm run dev
```

The dev server listens on http://localhost:3000.
All `/api/*` requests are rewritten to `$NEXT_PUBLIC_BFF_BASE_URL/*`.

## Build & test

```bash
npm run lint
npm run test
npm run build
```

## Key features wired

- **Partner CREATE form** (`/partners/new`) — surfaces the per-partner
  settlement rounding mode selector (HALF_UP default + 6 others) per
  `docs/MONEY_CONVENTION.md`. Posts to `POST /v1/admin/partners` on the BFF.
- **Partner DETAIL** (`/partners/[id]`) — supports editing the rounding mode.
- **Dashboard** (`/`) — 4 MUI cards backed by `GET /v1/admin/dashboard`.

Other pages (schemes, transactions, settlement, revenue) are skeletons that
establish the navigation structure; deep functionality lands in follow-up PRs.
