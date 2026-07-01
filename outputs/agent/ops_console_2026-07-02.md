> 작업: admin-ui Operations console / 출처: agent

# Operations console (branch `feat/ops-console`)

The #1 Ops sign-off blocker — backend existed, no operating screen. Built native
React inside admin-ui; all calls go through the api client at `/api/...`, which
next.config server-side-rewrites to the ops-BFF, so it works over the Cloudflare
tunnel. Edits scoped to `apps/admin-ui/` only.

## Nav item
`AppShell.jsx` — added `{ label: 'Operations', href: '/operations', icon: <HealthAndSafetyIcon /> }`
placed second (right after Dashboard) as a top-level control surface.

## Page sections + endpoints wired (`src/app/operations/page.jsx`, MUI Tabs)
- **Control Tower** (default): `GET /v1/admin/ops/control-tower`. Rollup cards
  (inFlight, uncertainOrAgedCount, webhookBacklog, health, openReconExceptions),
  float-headroom table (at-risk rows flagged/highlighted), operational-status
  banner (paused/maintenance/suspended + reason/since), recent-alerts strip.
  Auto-poll ~12s + manual refresh. `degradedSections[]` render as "unavailable".
- **Kill-switch**: `POST .../pause` `.../resume` `.../maintenance` `.../suspend`
  `.../unsuspend`. Each behind a confirm dialog (reason required where dangerous),
  success/error toast, status banner re-fetched after each action.
- **Alerts**: `GET /v1/admin/ops/alerts?severity=&type=&limit=` with filters.
- **Transactions & Recovery**: `GET /v1/admin/transactions/search`, per-row
  `POST .../{ref}/resolve` {resolution COMPLETED|REVERSED, reason} dialog; plus
  `POST /v1/admin/webhooks/{id}/replay` and `POST /v1/admin/settlements/recon/rerun`
  {batchId|settlementDate}. Loading/error/empty states throughout.

## RBAC header handling
`src/api/opsApi.js` (standalone, mirrors complianceApi.js). Read calls send only
the Bearer token; money-affecting ACTION calls additionally send
`X-Gme-Permissions: ops:operate` (via an `operate:true` flag on the internal
request helper) because the BFF now fails closed. Code comment states that in prod
this permission is derived server-side from the operator's verified token / PDP
(lib-rbac), not a client-supplied header.

## Vitest
`npx vitest run src/app/operations` + AppShell test — **8/8 passed** (4 Operations:
control-tower render, status banner, kill-switch Pause POST after confirm, alerts
list; 4 AppShell incl. new Operations nav assertion). Worktree had no node_modules;
used a temp junction to `/d/GMEPay+/code/apps/admin-ui/node_modules`, ran, removed it.

## Reached over the tunnel
Browser hits `/operations` → api client calls `/api/v1/admin/ops/...` → next.config
rewrite → ops-BFF. Same proxy path already tunneled for the rest of admin-ui.

## Remaining (≤3)
1. Live smoke against a running BFF (no server/docker per constraints) to confirm
   exact field shapes (e.g. txn `state` vs `status`, float decimal strings).
2. Transaction search results are not paginated (single page shown); add
   TablePagination if the BFF returns large result sets.
3. Prod: replace the dev `ops:operate` header with the real PDP-derived permission.
