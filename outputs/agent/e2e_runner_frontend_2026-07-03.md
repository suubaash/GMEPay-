> 작업: E2E runner sandbox tab / 출처: agent

# E2E Test sandbox tab (admin-ui)

## Files added
- `apps/admin-ui/src/app/sandbox/E2eTestConsole.jsx` — native React console (mirrors NepalQrConsole conventions: MUI, same-origin fetch, Paper/Grid layout).
  - Run form: Country + Partner selects (from `/e2e/options`, silent fallback to NP/KR + GMEREMIT/SENDMN), MPM radio ("Static (I enter the amount)" / "Dynamic (amount is in the QR)"), Amount number field (always shown; helper text adapts to MPM). Prominent "Run test" button with spinner + disabled-while-running.
  - Result panel: big PASS/FAIL banner ("✅ Payment journey passed" / "❌ Failed at: <failedStep>") + step list (green check / red X / grey dash icon, seq+name, human detail, latency + HTTP status). Failed step gets red-tinted background + bold.
  - History table below (Time, Country, Partner, Amount, MPM, Result chip, Failed step), newest-first as returned; row click lazy-loads `/e2e/runs/{id}` and expands its steps inline (Collapse). Refetches on load + after each run.
  - Loading + error states, incl. "Couldn't reach the test runner — is the fleet running?".
- `apps/admin-ui/src/app/sandbox/E2eTestConsole.test.jsx` — Vitest + Testing Library (mirrors sandbox.test.jsx).

## Files changed
- `apps/admin-ui/src/app/sandbox/page.jsx` — imported E2eTestConsole; added a native `component` tab labeled "E2E Test" (sim: 'payment-executor') into TABS *before* Service Trace (Service Trace renders at index=TABS.length, so array-append places E2E Test ahead of it). Extended the top Alert journey text with step (7) describing the E2E Test tab.
- `apps/admin-ui/next.config.mjs` — added `const paymentExecutorUrl = process.env.PAYMENT_EXECUTOR_URL || 'http://127.0.0.1:18084';` and rewrite `{ source: '/e2e/:path*', destination: `${paymentExecutorUrl}/v1/sandbox/e2e/:path*` }`.

## Proxy wiring
Console fetches same-origin relative paths under `/e2e` (`/e2e/options`, `/e2e/run`, `/e2e/runs`, `/e2e/runs/{id}`). The Next node server rewrites `/e2e/*` → `${PAYMENT_EXECUTOR_URL}/v1/sandbox/e2e/*` (server-side, IPv4 loopback default) — same pattern as `/sim-nepal-qr`, so it works over the Cloudflare tunnel with no client-side localhost.

## Test status (actual)
`vitest run src/app/sandbox/` → **10 passed (2 files)**. New file E2eTestConsole.test.jsx = 4/4 pass (form inputs render; Run posts to `/e2e/run` with the form body {country:NP,partner:GMEREMIT,mpmType:STATIC,amount:100} and renders PASS banner + steps; FAIL run shows "❌ Failed at: Pay" + red "Wallet declined" step; history table renders rows from `/e2e/runs`). Existing sandbox.test.jsx still 6/6 pass — additive, no regression.

## Deviations
- Repo uses **Vitest**, not Jest (task said "Jest" / `npm test -- E2eTestConsole`). Mirrored the real sandbox.test.jsx (Vitest) and ran `vitest run`.
- This worktree has **no node_modules** and the `D:\GMEPay+\` path triggers the known vitest `+`-URL-decode bug (memory: gmepay-vitest-plus-path-bug). Verified by copying admin-ui to a `+`-free scratch path, `npm ci`, and running vitest there. Source committed unchanged in the worktree.
- Committed on branch feat/e2e-runner-fe.
