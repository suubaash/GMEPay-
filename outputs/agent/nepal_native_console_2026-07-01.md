> 작업: Nepal QR native admin console / 출처: agent

# Nepal QR — native admin console (over Cloudflare tunnel)

## Problem
The admin "Nepal QR" sandbox tab was an `<iframe src="http://localhost:9103">`. Remotely
(over a Cloudflare tunnel) the CLIENT browser resolves `localhost` to the viewer's own
machine, so the iframe showed nothing.

## Fix
Turned it into a native admin-ui React page whose data calls go through a same-origin
server-side proxy. `next.config.mjs` already runs as a node server (`npm start`) and proxies
`/api/*` to the BFF; we mirror that for the sim.

### 1. next.config rewrite (`apps/admin-ui/next.config.mjs`)
Added a second rewrite:

```
source:      '/sim-nepal-qr/:path*'
destination: `${simNepalQrUrl}/:path*`
const simNepalQrUrl = process.env.SIM_NEPAL_QR_URL || 'http://127.0.0.1:9103'
```

- Server-side env only (NOT `NEXT_PUBLIC`).
- Defaults to IPv4 loopback (`127.0.0.1`) per the existing comment (Node resolves
  `localhost` → `::1` first, sim binds IPv4). Existing `/api` rewrite untouched.

### 2. Native component (`apps/admin-ui/src/app/sandbox/NepalQrConsole.jsx`)
MUI port of the sim's `static/{index.html,app.js}`:
- **QR** — textarea prefilled with the sample Fonepay QR; **Decode** →
  `POST /sim-nepal-qr/qrscan-thirdparty/parse/` → renders network / merchantName / merchantId /
  city / country / MCC / currency / initMethod / amount (paisa→NPR). Prefills pay amount for
  dynamic QRs.
- **Pay** — Amount (NPR→paisa), auto-unique reference, optional mobile/purpose/remarks, outcome
  select → `POST /sim-nepal-qr/sim/nepal-qr/ui/pay` → idx / status pill / amount + full JSON.
- **Records** — `GET /sim-nepal-qr/sim/nepal-qr/records`, newest-first, expandable
  request/decoded-payload/response; auto-refreshes after Pay.
- All fetches are SAME-ORIGIN relative paths under `/sim-nepal-qr/...`. Loading + error states
  handled (unreachable-sim, non-2xx, empty records). Reference generated in `useEffect` to avoid
  SSR hydration drift.

### 3. Tab wiring (`apps/admin-ui/src/app/sandbox/page.jsx`)
Refactored `TABS`: entries are either an iframe sim (`url`) or a native panel (`component`). The
3 ZeroPay tabs stay iframes; the Nepal QR entry now carries `component: NepalQrConsole` and
renders it natively. Removed the Nepal iframe and its `NEXT_PUBLIC_SIM_NEPAL_QR_URL` usage. The
Nepal panel advertises "native console (same-origin proxy)" instead of a localhost URL.

### 4. Tests (`apps/admin-ui/src/app/sandbox/sandbox.test.jsx`)
Nepal tab test now asserts the native console UI (heading `1 · QR`, QR payload field, Decode/Pay
buttons) and that `iframe-3` is absent. Merchant/wallet/rate iframe assertions kept. The
caption/URL test now asserts Nepal shows the proxy label and NOT `localhost:9103`.

## How remote access works now
Client browser → same-origin `/sim-nepal-qr/*` (served by the admin-ui Next node server over the
tunnel) → Next server-side rewrite → `SIM_NEPAL_QR_URL` (127.0.0.1:9103). No client-side
localhost dependency, so it works over the Cloudflare tunnel.

## vitest
`npx vitest run src/app/sandbox` from `apps/admin-ui` → **6 passed**. (Junctioned node_modules
from the main checkout to run; temp junction removed afterward.)

## Deployment env
Set **`SIM_NEPAL_QR_URL`** (server-side, non-public) to the sim's URL reachable from the admin-ui
node server, e.g. `http://sim-nepal-qr:9103` in Docker, or leave unset to use
`http://127.0.0.1:9103`.

## Remaining
- The sim must be reachable from the admin-ui node process (network/DNS), not the browser.
- The sim's `outcome` field is passed through but not documented in the task's endpoint list;
  kept it since the sim's app.js sends it.
