> 작업: T5-2 BFF consumer truthfulness / 출처: agent

# T5-2 — the BFF can no longer restate a filing more favourably than the service

**Scope:** `services/ops-partner-bff` only (+ the one-line GAP_REGISTER note). `services/reporting-compliance` and `apps/**` untouched.
**Build:** `gradlew :services:ops-partner-bff:test` → **BUILD SUCCESSFUL, 366 tests, 0 failures, 0 skipped**, of which **49 were added here** (44 across three new test classes, +5 in two existing ones).

The service-side fix made a fabricated acceptance structurally impossible. That only holds end-to-end if the layer above stops overwriting the truth on the way to the UI. Two leaks did exactly that.

---

## 1. Leak 1 — `RestReportingClient` hardcoded the status

`client/rest/RestReportingClient.java:88` passed the literal `"GENERATED"` into every `ReportRun` it built, discarding the `filing_status` / `filing_channel_unavailable_reason` / `filing_channels[]` the new envelope carries. Now:

- the run status is **read from the envelope** and mapped through the new `compliance/FilingStatuses`;
- `filing_channel_unavailable_reason` is carried, and the per-lane `filing_channels[]` board is projected into the new `web/dto/FilingChannelState`;
- **absent** upstream status → `UNKNOWN` **plus** a reason saying the service predates the change and nothing may be assumed filed. Not `GENERATED` (that is a real capability claim — aggregated + artifact produced), not `SUBMITTED`;
- the **retired** vocabulary (`SUBMITTED`/`CONFIRMED`/`ACCEPTED`/`FILED`) is reclassified to `NOT_FILED_CHANNEL_UNAVAILABLE` with a reason naming the raw value — the same decision Flyway `V003` made for the historical rows — rather than echoed;
- an unrecognised value → `UNKNOWN`, raw value named in the reason;
- the CSV download's `submission_status` column gets the **same** normalisation, because that file is an artifact an operator may hand onward. Every other column stays verbatim.

`ReportRun` was extended additively (`filingChannelUnavailableReason`, `filingChannels`); its `status` now *is* the upstream filing status, documented as such. The javadoc status union was the stale `PENDING | GENERATED | SUBMITTED | FAILED` — replaced with the new vocabulary + `UNKNOWN`. `ReportingClient` now states the honesty rule as an interface contract, so a third implementation cannot quietly re-introduce the hardcode.

## 2. Leak 2 — `*Set` flags counted placeholders as configuration

`web/dto/RegulatoryConfigSummary.java:31-33` used plain `!= null`, so the shipped Hometax cert `stub-cert-id` **and** the shipped BOK code `TODO_OI03` both rendered a green "configured" tick on a lane holding no credential. **All three sibling flags had the same bug** (`bokSet`, `hometaxSet`, `kofiuSet`) — fixed consistently via one shared helper, `compliance/ConfiguredValues.isConfigured()`, which rejects blank plus known placeholder markers (`stub-*`, `TODO*`, `changeme`, `placeholder`, `dummy-*`, `n/a`, `-`, `xxx`, …), case-insensitively. It mirrors reporting-compliance's `FilingChannelRegistry` rule by value, not by dependency (the BFF must not depend on a service module). `travelRuleSet` is enum-valued so it cannot carry a placeholder; `NONE` was already excluded.

## 3. Other synthesized statuses found in the sweep

| Where | Finding | Action |
|---|---|---|
| `client/stub/StubReportingClient.java:43` | CSV wrote `submission_status=SUBMITTED` — the **same untruth as the admin-ui mocks**, and worse than the Rest hardcode since nothing upstream exists at all in this path | **Fixed** → `NOT_FILED_CHANNEL_UNAVAILABLE` |
| `client/stub/StubReportingClient.java:49` | every offline run reported `GENERATED`, claiming a local aggregation that never ran | **Fixed** → `NOT_FILED_CHANNEL_UNAVAILABLE` + a reason naming the stub + all three lanes reported dark on the channel board |
| `web/ComplianceOverviewController.java` | `kybStatus` defaults to `PENDING` and `sanctionsResult` to `null` on a 404/unsupported partner | already honest — left as is |
| `client/stub/StubConfigRegistryClient.java:339` | bank-account `verificationStatus` defaults to `UNVERIFIED` | already honest — left as is |
| `web/ReportAdminController.java:59` | `generate` returns `202` with no body and the comment says "UI synthesizes PENDING". "Generate" recomputes on read and enqueues nothing, so a `PENDING` job does not exist — but this is an admin-ui rendering choice and not a filing claim | **not changed** (apps/** owned elsewhere); listed below |

No other compliance/filing/regulatory status in the BFF is defaulted or hardcoded — verified by sweeping for `SUBMITTED`/`GENERATED`/`ACCEPTED`/`CONFIRMED`/`COMPLIANT`, `*Set` flags, and every `compliance|kyb|hometax|kofiu|bok` mention across `src/main/java`.

## 4. Tests (all green, authenticated not bypassed)

- **`compliance/ConfiguredValuesTest` (21)** — `stub-cert-id` (+ upper/padded), `TODO_OI03`, `stub`, `tbd`, `changeme`, `placeholder`, blank, null → not configured; `HT-9981`, a 16-digit NTS id, `101`, `KOFIU-GME-001`, `gme-prod-cert-2026` → configured.
- **`compliance/FilingStatusesTest` (16)** — all 7 honest states pass through unchanged with no reason; upstream reason always wins; retired vocabulary reclassified; absent/blank → `UNKNOWN` with an explanation; unrecognised → `UNKNOWN` naming the value.
- **`client/rest/RestReportingClientTest` (7, new)** — `NOT_FILED_CHANNEL_UNAVAILABLE` + reason + the 3-lane board pass through unchanged **and record counts stay grouped per type**; an honest `GENERATED` comes from upstream; a **missing** status is `UNKNOWN` and asserted *not in* `{GENERATED, SUBMITTED, CONFIRMED, ACCEPTED, FILED}`, with `filingChannels` null rather than an empty board; `SUBMITTED` reclassified; CSV asserted to contain no `SUBMITTED`; upstream 500 → empty list, not a fabricated run.
- **`web/ReportAdminControllerTest` (6)** — now runs behind the **real `AdminSurfaceRbacInterceptor` with `OpsRbacGuard(true)` (enforcing)**, authenticating via `TestTokens.hubOperator("ops:operate","report.generate")`; adds an offline-run honesty assertion, a CSV `SUBMITTED`-absence assertion, and a **partner-scoped token → 403** case.
- **`web/ComplianceOverviewControllerTest` (7)** — same enforcing interceptor + `TestTokens`; adds the `TODO_OI03`/`stub-cert-id`/blank → all-false matrix, a real-credential → all-true case, and a partner-token 403 case.

No security work from `fix_t0-auth-boundary_2026-07-28.md` was weakened — the two controller tests moved *from* no-RBAC standalone setups *to* the enforcing interceptor, so admin-surface authorization is now covered on both.

## 5. Follow-ups NOT done here (owned by other agents)

**`apps/**` (SPA agent) — the last place a filing that never happened still renders as submitted:**
- `apps/admin-ui/src/api/reportsApi.js:23, 76, 103, 130` — mock/fallback rows hardcode `status: 'SUBMITTED'` and the JSDoc status union is the retired vocabulary. Should be `NOT_FILED_CHANNEL_UNAVAILABLE`, and the union should become `PENDING | GENERATED | VALIDATED | NOT_FILED_CHANNEL_UNAVAILABLE | TRANSMITTED | ACKNOWLEDGED | FAILED | UNKNOWN`.
- `apps/admin-ui/src/app/compliance/page.jsx:341–347` (+ `src/api/complianceApi.js:22–24, 128–152`) — `SetBadge` renders `bokSet`/`hometaxSet`/`kofiuSet` as green ticks, reading as "lane live" when it only ever meant "config entered". Now that placeholders are excluded the tick is at least truthful about config, but channel-live vs config-entered still needs to be two distinct indicators.
- `apps/admin-ui` Reports page should surface the new `filingChannelUnavailableReason` / `filingChannels[]` fields the BFF now returns (they are additive, so nothing breaks until it does).
- `ReportAdminController` `generate` → 202 → UI-synthesized `PENDING`: consider rendering "recomputed on read" instead of a nonexistent pending job.

**Deferred by choice:** no BFF passthrough for `GET /v1/reports/filing-channels` was added — the per-lane board already rides on every `ReportRun`, and a new `/v1/admin/**` endpoint with no UI consumer would be untested surface. Worth adding when the compliance page gets its channel-live indicator.

**Unchanged external gates:** BOK SFTP endpoint + real `bok_txn_code`/`bok_fx_reporting_category` (OI-03), NTS mTLS cert + XML signing (OI-02), KoFIU endpoint + file layout, and the empty `StubKofiuTransactionPort`. T5-2 stays `[~]`.
