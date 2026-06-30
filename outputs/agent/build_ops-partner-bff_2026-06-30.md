> 작업: ops-partner-bff backlog 완성 / 출처: agent

# ops-partner-bff — build report (2026-06-30)

## Build status
`./gradlew :services:ops-partner-bff:test` → **BUILD SUCCESSFUL** (26s). All unit
tests green. The two new test classes ran: `RestRevenueLedgerClientTest` (5/5),
`RestSystemHealthClientTest` (3/3), 0 failures/errors. Only pre-existing
deprecation warnings (`ConfigRegistryClient.PartnerSummary`, unrelated).

Committed on `agent/ops-partner-bff` as `ce9441d`.

## Assessment vs. the prompt's premise
The prompt's premise was STALE. The P0 said "only Stub*Client exist" for
transaction-mgmt / revenue / settlement / system-health. In the actual code:
- `RestTransactionMgmtClient` — ALREADY existed (full `GET /v1/transactions` +
  `/{ref}` mapping, partnerId-numeric handling, paged envelope). Untouched.
- `RestSettlementClient` — ALREADY existed (`GET /v1/settlements` → batch summaries,
  client-side partner filter, detail→null with documented limitation). Untouched.
- `RestRevenueLedgerClient` — DID NOT exist (an explicit code comment in
  `application.properties` deferred it). **Built this run.**
- `RestSystemHealthClient` — DID NOT exist (stub-only). **Built this run.**

So the real remaining P0 work was the revenue + system-health adapters, which are
now done. The service already had ~10 Rest adapters wired (config-registry,
prefunding, approval-queue, audit-trail, rbac-admin, reporting, transaction-mgmt,
settlement). With these two, the operational read surface is fully REST-capable.

## Tickets advanced
- **17.5-G02 (Flip BFF clients Stub→REST)** — materially advanced. The two missing
  operational Rest adapters (revenue-ledger, system-health) now exist, gated
  `@ConditionalOnProperty` + `@Primary`, with stubs as `matchIfMissing` fallback.
  Remaining clients without a Rest variant are non-operational/low-value
  (ApiKey, Rates, Statement, Audit-legacy) — see "Remaining".

## Rest clients now present (this run added the last two operational ones)
NEW:
- `client/rest/RestRevenueLedgerClient.java` — `gmepay.revenue-ledger.client=rest`.
  Maps revenue-ledger `GET /v1/revenue` (`RevenueSummaryResponse`):
  `totalServiceChargeAmount`→fee, `totalFxMarginUsd`→margin, sum→total. Upstream is
  per-(numeric)partnerId, so the system-wide `getSummary`/`summaryRange` are backed
  by ONE configured partner (`gmepay.revenue-ledger.aggregate-partner-id`); unset →
  honest zero summary + empty `breakdown` (no synthetic data). `WireRevenue` carries
  a tolerated `totalRoundingUsd` field (`@JsonIgnoreProperties`) so the surface
  lights up the moment upstream adds it — no code change here.
- `client/rest/RestSystemHealthClient.java` — `gmepay.system-health.client=rest`.
  Concurrent fan-out to all 17 backend `/actuator/health`, per-service base URL from
  `gmepay.<service>.base-url` (compose-DNS default), actuator status map
  (UP/DOWN/OUT_OF_SERVICE→UP/DOWN/DEGRADED, unreachable→DOWN, indeterminate→UNKNOWN),
  3s per-probe timeout, snapshot never fails on a single bad probe.

Pre-existing (verified, untouched): RestTransactionMgmtClient, RestSettlementClient,
RestConfigRegistryClient, RestPrefundingClient, RestApprovalQueueClient,
RestAuditTrailClient, RestRbacAdminClient, RestReportingClient.

## Changed (supporting)
- `StubRevenueLedgerClient`, `StubSystemHealthClient` → `@ConditionalOnProperty`
  (`havingValue=stub`, `matchIfMissing=true`) so exactly one bean wins.
- `application.properties` — added `gmepay.revenue-ledger.client`,
  `gmepay.revenue-ledger.aggregate-partner-id`, `gmepay.system-health.client`;
  removed the stale "revenue-ledger has no rest client" note.
- `CHANGELOG.md` — created.

## % estimate
ops-partner-bff operational read surface: ~95% (all UI-facing operational pages can
now be backed by REST; residual is upstream-gated revenue aggregation + the
optional non-operational adapters). Service overall ~92%.

## INTEGRATION REQUESTS
1. **revenue-ledger** — add `total_rounding_usd` (camelCase `totalRoundingUsd`) to
   `RevenueSummaryResponse` on `GET /v1/revenue`. The BFF P0 explicitly asked to
   surface it, but the frozen `RevenueSummaryResponse`
   (`services/revenue-ledger/.../web/RevenueSummaryResponse.java`) does NOT include
   it today. `RestRevenueLedgerClient.WireRevenue` already declares the field and
   ignores-if-absent, so adding it upstream lights up the BFF with no BFF change.
2. **revenue-ledger / config-registry** — provide a partner CODE → numeric partnerId
   mapping (or accept partner code on `GET /v1/revenue`). Upstream requires a numeric
   `partnerId`; the BFF holds partner CODES. Until then per-partner revenue requires a
   hand-configured numeric id (`gmepay.revenue-ledger.aggregate-partner-id`).
3. **revenue-ledger** — add a SYSTEM-WIDE / multi-axis revenue endpoint
   (e.g. `GET /v1/revenue/summary?from&to` and `GET /v1/revenue/breakdown?from&to`
   returning by-partner / by-scheme / by-currency USD totals). The current endpoint is
   per-partner only, so the BFF's `summaryRange`/`breakdown` cannot be backed
   system-wide; `RestRevenueLedgerClient.breakdown` returns empty maps until this lands.
4. **settlement-reconciliation** (pre-existing, carried forward) — no per-batch detail
   endpoint or date-range query, so `RestSettlementClient.detail` returns null and
   `recent` surfaces only the current settlement date.

## Remaining (next runs, in priority order)
1. **18.4-G02 / 18.1-G02 BFF auth** — `AuthController` still fakes login; no JWT
   resource-server validation, no `@PreAuthorize` role gates, no partner-scoping
   (any caller can read any partner via path id). This is the largest remaining
   security gap. Deferred this run (cross-cuts every controller + needs auth-identity
   JWKS wiring; out of the two-rest-client scope).
2. **17.5-G02 tail** — optional non-operational adapters still stub-only:
   `ApiKeyClient`, `RatesClient`, `StatementClient`, legacy `AuditClient`. Lower
   value; build Rest variants if/when their upstreams stabilize.
3. **18.1-G01** — document the 19 BFF endpoints + DTO field tables in
   `docs/INTER_SERVICE_CONTRACTS.md` (springdoc is live; just needs the doc section).
