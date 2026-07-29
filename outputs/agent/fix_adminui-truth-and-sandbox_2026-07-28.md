> 작업: admin-ui compliance truth + sandbox rewrite / 출처: agent

# admin-ui — the last place a filing that never happened rendered as done, plus the browser proxy to the payment runner

**Scope:** `apps/admin-ui/**` only. Nothing under `services/**`, `apps/partner-portal-ui`,
`docker-compose.yml`, `deploy/helm/**` or `run-fleet.ps1` was touched. Built on top of the OIDC
agent's in-flight changes (its `BFF_PROXY_TARGET` block in `next.config.mjs`, `api/auth.js`,
`api/client.js`, login page) — none reverted; the `next.config.mjs` edit is surgical.

**Verification**
- `cd apps/admin-ui && npx next build` → **compiled successfully**, all 40 routes emitted.
- Unit tests: `npx vitest run` from a plus-free **copy** of the app (`D:\gmepay-adminui-vitest`, the
  vitest `'+'`-path bug; a junction is not enough — vite resolves the real path and still fails to
  load `vitest.setup.js`) → **90 files / 792 tests, 0 failures**. The ~8 wizard `userEvent` flakes
  did not fire on this run; no new failure was introduced either way.
- Field names taken from the landed BFF work (`fix_t5-bff-compliance-truth`): `ReportRun.status`,
  `filingChannelUnavailableReason`, `filingChannels[] = {lane, channelLive, reachableStatus, reason}`,
  and the `regulatoryConfig.{bok,hometax,kofiu,travelRule}Set` booleans. No new BFF endpoint assumed —
  the channel board is read off `GET /v1/admin/reports`, exactly as that report intended.

---

## 1. Filing status — before vs now

| Surface | Before | Now |
|---|---|---|
| `src/api/reportsApi.js` JSDoc | `'PENDING' \| 'GENERATED' \| 'SUBMITTED' \| 'FAILED'` | the reachable vocabulary + `VALIDATED` / `NOT_FILED_CHANNEL_UNAVAILABLE` / `TRANSMITTED` / `ACKNOWLEDGED` / `UNKNOWN`, plus the two new fields, plus a paragraph stating that `TRANSMITTED`/`ACKNOWLEDGED` are unreachable today and why `SUBMITTED` is gone |
| `reportsApi.js` fixtures (4 rows) | `status: 'SUBMITTED'` — "delivered to the regulator" for reports that never existed | `NOT_FILED_CHANNEL_UNAVAILABLE` / `GENERATED` / `VALIDATED` / `PENDING` / `FAILED`, each with `filingChannelUnavailableReason` and the 3-lane dark board — the same story the BFF's `StubReportingClient` tells offline |
| `reportsApi.generateReport()` 202 path | synthesized a `PENDING` run | `UNKNOWN` + a reason saying reports are recomputed on read, so there is no queued job to poll (the "UI synthesizes PENDING" item the BFF report flagged) |
| `reports/ReportStatusChip.jsx` | `GENERATED` **and** `SUBMITTED` both green; comment claimed `SUBMITTED` = "file delivered to regulator" | green **only** for `TRANSMITTED`/`ACKNOWLEDGED`; local states are outlined/info; every chip carries a tooltip with the meaning + the unavailable-channel reason |
| `reports/page.jsx` | column "Status"; download gated on `GENERATED \|\| SUBMITTED` | column "**Filing status**"; download gated on "a local artifact exists" (`hasLocalArtifact`), so the not-filed rows keep their download; a `not-filed-banner` states *"Nothing on this page has been filed"* with the per-lane board — and disappears by itself if a run ever reports a real `TRANSMITTED` |
| `compliance/page.jsx` (341–347) | `SetBadge` → green **"Set"** chip per lane; columns "BOK / Hometax / KoFIU / Travel Rule" | `ConfigBadge` → neutral outlined **"Configured"** / warning **"Not configured"**, columns renamed "**BOK config**" etc., tooltip spelling out that config files nothing |
| `compliance/page.jsx` (new) | nothing about channels; an operator could only read "green = live" | a banner naming the **three distinct facts** (configured / filing channel available / filed) + `FilingChannelBoard` rendering `filingChannels[]` verbatim. Title reads *"No regulatory filing channel is live — nothing has been filed"* while every lane is dark |
| `compliance/DrillDownPanel.jsx` | raw config key/values, no framing | caption: values are stored config shown verbatim, placeholders like `stub-cert-id`/`TODO_OI03` are config text only, no lane has a live channel |
| `reports/ReportTypeFilter.jsx` | doc comments read as platform capability ("submitted to BOK by the 10th") | same taxonomy, prefaced that these describe the statutory **obligation**, not something this platform transmits |

**New shared pieces** (so the rule lives in one place, not per page):
- `src/api/filingStatus.js` — the vocabulary + `filingStatusMeta()`, `isFiled()`, `hasLocalArtifact()`,
  `notFiledSummary()`. Two invariants: green means filed and nothing else; a **retired**
  (`SUBMITTED`/`CONFIRMED`/`ACCEPTED`/`FILED`) or unrecognised value renders as
  *"— not a valid state"* / *"— unrecognised"* in warning colour. That is not re-deriving backend
  truth — it is refusing to upgrade an unknown claim, so a stale service or cached response cannot
  paint a green tick.
- `src/components/FilingChannelBoard.jsx` — per-lane chips straight from the backend; success colour
  only where `channelLive === true`. A **missing** board renders "not reported … nothing may be
  assumed filed", never as availability.
- `complianceApi.getFilingChannels()` + `complianceSlice.fetchFilingChannels` — reads the board off
  `GET /v1/admin/reports` (documented in-file, including *why* there is no filing-channels endpoint).
  Failure/absence leaves `filingChannels: null` = unknown.

The `*Set` flags are rendered exactly as the BFF reports them; no placeholder rule was duplicated
into the UI.

## 2. Sandbox runner — rewrite removed, console kept as an honest notice

`next.config.mjs`: the `/e2e/:path*` → `${paymentExecutorUrl}/v1/sandbox/e2e/:path*` rewrite and the
`paymentExecutorUrl` const are gone, replaced by a comment recording why (a same-origin
unauthenticated proxy from wherever the portal is reachable, tunnel included, straight to a runner
that performs a real authorize+capture, bypassing the BFF's OIDC boundary — and a path that would
forward a client-supplied `X-Gme-Internal` the moment the flag flips). The `/api` and
`/sim-nepal-qr` rewrites are untouched.

**Decision on the console: kept the tab, deleted the runner UI.** `E2eTestConsole.jsx` went from
~530 lines of fetch/run/history code to a ~95-line notice. Removing the tab entirely was the other
option; keeping a one-screen explanation is the smaller *net* change in reader-facing terms and it
keeps the follow-up discoverable — a tab that silently vanishes teaches nobody why. Leaving the
fetch code would have been the worst of the three: with no proxy it can only ever render
*"couldn't reach the test runner — is the fleet running?"*, which is a **false diagnosis** (the
fleet is fine; the surface is deliberately 404). The notice names the gate
(`gmepay.sandbox.e2e.enabled`, `X-Gme-Internal`), says the proxy was removed under T0-5, and gives
the three steps to drive the journey from a dev machine. It makes **no** network call.
`sandbox/page.jsx` no longer advertises "(7) run the whole payment journey automatically" or
"proxied same-origin (/e2e) so it works remotely" — the tab now labels itself
"developer tool — sandbox runner not enabled" and, via a new optional `sourceNote`, drops the
irrelevant `gradlew -p simulators/payment-executor bootRun` hint.

Tests were **inverted, not dropped**: `E2eTestConsole.test.jsx` now asserts the not-enabled wording,
the dev-only framing, the named gate, that **no fetch happens at all**, and that no run affordance
(button / amount / radio) exists — 5 tests, replacing 4 that drove a runner that no longer exists.

## 3. Test changes

| File | Change |
|---|---|
| `app/reports/__tests__/page.test.jsx` | fixture statuses moved off `SUBMITTED`; asserts `SUBMITTED` **cannot appear**, the honest labels do, the not-filed banner + lane board render, a stale `SUBMITTED` shows "not a valid state", and a real `TRANSMITTED` **removes** the banner (+3 tests) |
| `app/reports/__tests__/reportsSlice.test.js` | `RUN_A` uses the reachable vocabulary |
| `app/compliance/__tests__/page.test.jsx` | mock gains `getFilingChannels`; +3 tests: no green "Set" anywhere / 4 "Configured" + 8 "Not configured" + `* config` headers, the "no channel is live / nothing filed" banner with all three lanes dark, and an unreported board rendering as unknown |
| `app/sandbox/E2eTestConsole.test.jsx` | rewritten as above |

## 4. Follow-ups (not done here — outside `apps/admin-ui`)

1. **`ops-partner-bff`**: the compliance overview row still carries only the four `*Set` booleans, so
   `/compliance` has to read the channel board off `GET /v1/admin/reports`. If the board is wanted
   without a reports round-trip, add it to `ComplianceRow` (or expose
   `GET /v1/admin/compliance/filing-channels`); the UI call site is a single function
   (`complianceApi.getFilingChannels`).
2. **`ops-partner-bff` `ReportAdminController.generate`** still answers `202` with no body. The UI now
   says `UNKNOWN` + "recomputed on read" instead of inventing `PENDING`; returning the recomputed run
   (or `204` + a documented meaning) would remove the ambiguity at the source.
3. **`payment-executor` sandbox runner** has no authenticated path for operators at all now. If the
   E2E tab should return as a feature, it needs a BFF route (`/api/*`, OIDC + RBAC, internal token
   held server-side) — then this tab becomes a real console again.
4. **`/sim-nepal-qr/:path*` rewrite** deserves the same review T0-5 gave `/e2e` (flagged in that
   report, deliberately left alone here: the Nepal sim is not a money-moving surface, but it is still
   an unauthenticated same-origin proxy out of the portal).
5. Filing truth is now displayed on `/reports` and `/compliance`. Nothing on `/settlement`,
   `/journal` or `/scheme-statements` claims a regulatory filing — swept and confirmed clean
   (`SUBMITTED`/`ACCEPTED`/`FILED`/`CONFIRMED` and the `*Set` flags appear nowhere else in
   `apps/admin-ui/src` outside comments and tests).
