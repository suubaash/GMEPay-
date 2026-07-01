> 작업: ops console alert-ack UI / 출처: agent

# Ops console — alert acknowledge + paging status

## What was added

### `src/api/opsApi.js`
- `ackAlert(id, { note })` → `POST /v1/admin/ops/alerts/{id}/ack` with body `{ note }`, sending the fail-closed `X-Gme-Permissions: ops:operate` header (`operate: true`), mirroring the other action calls.
- Documented the new `OpsAlert` fields on the wire (`id`, `pagingStatus`, `acked`, `open`, `ackedBy`, `ackedAt`, `ackNote`) and the ack endpoint in the module contract.

### `src/app/operations/page.jsx` (Alerts tab)
- **Severity chip** — reused `severityColor` (now also maps `WARN`).
- **Paging status** — new `pagingChip(status)` helper renders DELIVERED / FAILED / SUPPRESSED / NOT_PAGED (and null → "Not paged"). Rendered as an outlined chip in a new "Paging" column.
- **Acked vs open state** — new `isOpen(a)` helper (null-safe over `acked`/`open`). New "State" column: open alerts show a warning "Open" chip; acked alerts show an outlined "Acked by …" chip with a tooltip carrying operator + time + note. Acked rows render dimmed (`opacity 0.6`).
- **Acknowledge button** on open alerts → opens an optional-note dialog → calls `ackAlert(id, { note })` → success toast → reloads. Uses `id`, falling back to `subjectRef`.
- **Open-only toggle** (`Switch`) filters to open alerts; empty state adapts ("all acknowledged").
- Loading / error / empty and null-safety preserved (older alerts without paging/ack fields still render).

### Test (`__tests__/page.test.jsx`)
- Added `ackAlert` to the opsApi mock; enriched the ALERTS fixture with paging/ack fields (one open+delivered, one acked+failed).
- Extended the alerts render test (severity, paging chips, open/acked, button presence/absence).
- New test: clicking Acknowledge on an open alert calls `ackAlert('al-1', { note })`, toasts, reloads, and the row shows acked (button gone, "Acked by …" shown).

## Verify
`npx vitest run src/app/operations` → **5 passed** (1 file). node_modules supplied via a temporary junction from `/d/GMEPay+/code/apps/admin-ui/node_modules`, removed after the run (worktree left clean).

## Remaining / notes
1. Ack operator is derived server-side from the token; UI sends only `note` (matches BFF contract). If the BFF expects an explicit `operator`, extend the payload.
2. Paging-status label vocabulary assumes DELIVERED/FAILED/SUPPRESSED/NOT_PAGED — unknown values render verbatim.
3. No browser verification performed (constraint: no dev server); covered by unit tests only.
