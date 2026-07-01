> 작업: Phase2 reporting-compliance wiring / 출처: agent

# Phase 2 — reporting-compliance: REST committed-FX adapter (CONSUMER)

## Build status
`./gradlew :services:reporting-compliance:test` → **BUILD SUCCESSFUL**. All tests green
(new + pre-existing). Edits confined to `services/reporting-compliance/`; libs + other
services untouched.

## Adapter wired + FX1015 #14 sourcing
- **`RestCommittedFxTransactionPort`** (new) implements existing `CommittedFxTransactionPort`.
  Calls transaction-mgmt `GET /v1/transactions/fx-committed?from&to[&partnerId]` →
  `List<com.gme.pay.contracts.CommittedFxView>`, maps each to domain `CommittedTransaction`.
  Wire `direction` is a String → `TransactionDirection.valueOf` (upper-cased; unknown/blank
  skipped, not fatal — mismatch #5 handled). `offerRateColl`/`crossRate`/margins/partnerId
  carried verbatim.
- **Gating:** `@ConditionalOnProperty(gmepay.transaction-mgmt.fx-committed.enabled=true)`,
  default false in `application.yml`. Off → in-process `FixtureCommittedFxTransactionPort`
  (`@ConditionalOnMissingBean`) stays the default/test fallback. Two-ctor Spring-6 wiring
  (`@Autowired` on `@Value` ctor; test ctor takes a pre-built `RestClient`).
- **End-to-end:** `CommittedFxView.offerRateColl/crossRate` flow through the adapter into the
  unchanged `BokRecordPersistenceService`, persisting `offer_rate_coll` (FX1015 #14) +
  `cross_rate` on `bok_report_record` from real projection data.
- **Tests (mock HTTP, no live upstream):** `RestCommittedFxTransactionPortTest` (URI,
  CommittedFxView JSON deserialisation, String→enum valueOf, verbatim #14, partnerId param,
  unknown-direction skip) + `CommittedFxEndToEndPersistenceTest` (`@DataJpaTest`: #14 +
  cross_rate persisted end-to-end). Used `MockRestServiceServer` (already on classpath via
  starter-test, matching the proven `RestTransactionClientTest`) instead of MockWebServer/
  WireMock to avoid touching frozen build deps; same intent (mock HTTP, upstream not running).

## Remaining (≤3)
1. Production flip of `gmepay.transaction-mgmt.fx-committed.enabled=true` is gated on
   transaction-mgmt actually shipping the `GET /v1/transactions/fx-committed` projection.
2. `RestTransactionClient` (canonical GET path) still sets `offerRateColl=null`/`partnerId=0`;
   left as-is since the gated FX adapter is now the preferred FX1015 source.
3. Real BOK OI-03 channel remains externally blocked (unchanged).
