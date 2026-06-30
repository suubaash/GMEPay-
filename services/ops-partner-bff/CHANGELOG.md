# ops-partner-bff — CHANGELOG

All notable changes to the Ops/Partner BFF. Newest first.

## 2026-06-30 — REST clients for revenue-ledger + system-health (P0 gap-closure)

Closes the last two synthetic-data gaps among the operational upstream adapters.
Before this change `RestTransactionMgmtClient` and `RestSettlementClient` already
existed; only revenue and system-health were stub-only.

### Added
- `RestRevenueLedgerClient` (`gmepay.revenue-ledger.client=rest`, `@Primary`,
  `@ConditionalOnProperty`). Maps revenue-ledger `GET /v1/revenue`
  (`RevenueSummaryResponse`) onto the BFF `RevenueSummary`:
  `totalServiceChargeAmount` → fee, `totalFxMarginUsd` → margin, sum → total.
  Upstream is per-(numeric)partnerId, so the system-wide summary is backed by a
  single configured partner (`gmepay.revenue-ledger.aggregate-partner-id`); when
  unset it degrades to an HONEST zero summary + empty breakdown (no synthetic
  data). Tolerates a future `totalRoundingUsd` field via `@JsonIgnoreProperties`.
- `RestSystemHealthClient` (`gmepay.system-health.client=rest`, `@Primary`,
  `@ConditionalOnProperty`). Fans out concurrently to each of the 17 backend
  services' `/actuator/health`, resolving each base URL from
  `gmepay.<service>.base-url` (compose-DNS default). Maps actuator
  `UP`/`DOWN`/`OUT_OF_SERVICE` → `UP`/`DOWN`/`DEGRADED`; unreachable/erroring →
  `DOWN`; indeterminate body → `UNKNOWN`. Per-probe 3s timeout; a slow/failed
  probe never fails the whole snapshot.
- Tests: `RestRevenueLedgerClientTest` (5), `RestSystemHealthClientTest` (3) —
  `MockRestServiceServer`-bound `RestClient`, no live upstream.

### Changed
- `StubRevenueLedgerClient` / `StubSystemHealthClient` now `@ConditionalOnProperty`
  (`havingValue=stub`, `matchIfMissing=true`) so exactly one bean wins, matching
  the transaction-mgmt / settlement adapter pattern.
- `application.properties`: added `gmepay.revenue-ledger.client`,
  `gmepay.revenue-ledger.aggregate-partner-id`, `gmepay.system-health.client`
  selectors; removed the stale "revenue-ledger has no rest client" note.

### Outstanding (INTEGRATION REQUESTS — see build report)
1. revenue-ledger: add `total_rounding_usd` to `RevenueSummaryResponse`.
2. revenue-ledger / config-registry: partner CODE → numeric id mapping so the BFF
   can query per-partner revenue without a hand-configured numeric id.
3. revenue-ledger: a system-wide / multi-axis (by-partner/scheme/currency) revenue
   endpoint to back the Admin dashboard summary + breakdown without stubs.
