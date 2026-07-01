# rate-fx — CHANGELOG

## 2026-06-30 — Wave-3: consume config-registry rule rate-source (w3/rate-fx)

### Changed
- **Rate-source is now data-driven from config-registry** (closes Wave-3 IR-1). The new
  `RuleView.rateCollSource`/`ratePaySource` contract fields (commit a36997e) are deserialized by
  `RestConfigRegistryClient` off `GET /v1/partners/{code}/rules` and mapped onto the per-leg
  `RateSource`. A leg whose rule says `PARTNER` is dispatched to `PartnerBQuotePort`;
  `MANUAL`/`IDENTITY`/`LIVE` and null/absent (⇒ LIVE, back-compat) take the treasury-snapshot /
  identity paths. Previously every leg defaulted LIVE because the source field was never consumed.
  (Field mapping already existed in `RestConfigRegistryClient`/`PartnerRule`; this commit proves and
  guards the end-to-end wire→leg routing.)
- `RestConfigRegistryClient(RestClient)` test-seam constructor widened to `public` so the wiring
  test can drive the real client over `MockRestServiceServer`.

### Tests
- `issue/ConfigRegistryRuleSourceWiringTest` (7 tests, MockRestServiceServer; config-registry NOT
  running): `getRules` deserializes the wire source strings and they resolve onto `RateSource`;
  end-to-end a `PARTNER` leg routes through Partner B (treasury never queried), `LIVE`/`MANUAL` read
  the snapshot store, `IDENTITY` USD legs yield null (engine forces 1.0), and absent ⇒ LIVE.

## 2026-06-30 — PARTNER source (WBS 4.6), durable TTL, manual override (agent/rate-fx)

### Added
- **PARTNER rate source (WBS 4.6)** — `partnerb/PartnerBQuotePort` + `PartnerBQuote` value
  object and the in-process `SnapshotPartnerBQuotePort` (reads the latest `source='PARTNER'` row
  from `rate_snapshots`). No live Partner B endpoint required in tests; a real HTTP client can be
  added later as a `@Primary` bean behind the same port.
- **PARTNER leg wiring** — `QuoteIssueService.buildRateInput` now resolves each leg by its
  configured `RateSource` (new enum: IDENTITY/LIVE/MANUAL/PARTNER). A `ratePaySource=PARTNER` /
  `rateCollSource=PARTNER` leg is priced by Partner B instead of the treasury snapshot store.
- **Commit-time deviation guard** — `QuoteIssueService.resolvePartnerCommitRate(...)` re-quotes the
  PARTNER leg and rejects with `PARTNER_B_QUOTE_DEVIATION` (422) when the drift from the locked rate
  exceeds tolerance (default 1%); within tolerance returns the fresh commit rate. Unavailable Partner
  B quotes surface `PARTNER_B_QUOTE_UNAVAILABLE` (503) — never a silent treasury fallback.
- **Per-leg source on the pricing rule** — `PartnerConfigPort.PartnerRule` gains nullable
  `rateCollSource`/`ratePaySource` (null/blank ⇒ LIVE). 5-arg constructor retained for back-compat;
  `RestConfigRegistryClient` maps the two new wire fields.
- **Durable, restart-safe quote TTL store** — `JpaQuoteTtlStore` over the `rate_quotes` audit table
  is now the default when no Redis host is configured (replaces the process-local in-memory default).
  Quote locks survive a service restart for the remainder of their TTL without Redis; expiry is
  governed by the row's `expires_at`. Redis-backed store still used when `spring.data.redis.host` set.
- **Manual override / PARTNER-seed endpoint** — `POST /v1/rates/snapshots`
  (`RateSnapshotAdminController` + `RateSnapshotAdminService`) appends a new effective-dated MANUAL or
  PARTNER snapshot row (LIVE is reserved for the XE feed; USD rejected as an identity leg).

### Tests (12 new; suite 28 green)
- `SnapshotPartnerBQuotePortTest` — latest PARTNER snapshot, missing-row → unavailable, blank ccy.
- `QuoteIssuePartnerSourceTest` — PARTNER pay leg uses Partner B not treasury; unavailable
  propagation; commit deviation within/over tolerance (1380→1390 ok, 1380→1394.8 rejected).
- `RateSnapshotAdminServiceTest` — MANUAL/PARTNER persist; USD/LIVE/non-positive rejected.
- `JpaQuoteTtlStoreTest` — retrievable while lock holds, expired → empty/`RATE_QUOTE_EXPIRED`,
  survives a "restart" (fresh store over same rows).
- `ContextLoadTest` — full app boots on H2 (no Redis) and wires `JpaQuoteTtlStore` +
  `SnapshotPartnerBQuotePort`.

### Notes
- lib-rate (engine) is frozen and already implements the same-currency short-circuit (WP 4.5) and
  IDENTITY 1.0 short-circuit; no change needed there.
- `InMemoryQuoteTtlStore` retained (still a valid `QuoteTtlStore`) but no longer the default bean.
