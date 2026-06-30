# reporting-compliance — Changelog

All notable changes to the reporting-compliance service.
Format loosely follows [Keep a Changelog](https://keepachangelog.com/).

## [Unreleased]

### Added — Phase 2: REST committed-FX adapter sourcing FX1015 #14 from real data (2026-06-30)

Phase 1 populated BOK FX1015 field #14 (`offer_rate_coll`) via an in-process fixture
behind `CommittedFxTransactionPort`. This change swaps the fixture for a real REST
adapter so #14 + `cross_rate` flow from transaction-mgmt's committed-FX projection.

- **`RestCommittedFxTransactionPort`** — production REST adapter for
  `CommittedFxTransactionPort`. Calls transaction-mgmt
  `GET /v1/transactions/fx-committed?from&to[&partnerId]` (returns
  `List<com.gme.pay.contracts.CommittedFxView>`) and maps each view to the domain
  `CommittedTransaction`. The wire `direction` is a String → mapped via
  `TransactionDirection.valueOf` (upper-cased; unknown/blank directions are skipped,
  not fatal). All rate-locked fields — `offerRateColl` (FX1015 #14), `crossRate`,
  margins, USD amount, partnerId — are carried verbatim. Two-constructor Spring-6
  wiring with `@Autowired` on the `@Value` ctor (test ctor takes a pre-built `RestClient`).
- **Gating:** `@ConditionalOnProperty(gmepay.transaction-mgmt.fx-committed.enabled=true)`
  (default false in `application.yml`). When off, the in-process
  `FixtureCommittedFxTransactionPort` (`@ConditionalOnMissingBean`) remains the
  default/test fallback, so local boots and tests stay offline.
- **End-to-end:** `CommittedFxView.offerRateColl/crossRate` now flow through the
  adapter into the existing `BokRecordPersistenceService`, persisting `offer_rate_coll`
  + `cross_rate` on `bok_report_record` from real projection data.
- **Tests (mock HTTP via `MockRestServiceServer`, transaction-mgmt NOT running):**
  `RestCommittedFxTransactionPortTest` asserts URI, `CommittedFxView` JSON
  deserialisation, String-direction `valueOf` mapping, verbatim FX1015 #14, partnerId
  query param, and unknown-direction skip. `CommittedFxEndToEndPersistenceTest`
  (`@DataJpaTest`) asserts FX1015 #14 + `cross_rate` persisted end-to-end from the JSON.

### Added — owned-datastore persistence + FX1015 #14 (2026-06-30)

The service previously generated BOK/KoFIU files in-memory with no persisted
record of what was produced, and BOK FX1015 field #14 (`offer_rate_coll`) was
unpopulated because the canonical `GET /v1/transactions` endpoint does not expose
the rate-locked margin fields. This change closes both gaps.

- **Owned datastore (JPA + Flyway + H2):** added `spring-boot-starter-data-jpa`,
  `flyway-core` (+ `flyway-database-postgresql`), `postgresql`, `h2`, and
  `spring-boot-starter-validation` to the build. Replaced `application.properties`
  with `application.yml` carrying datasource/JPA/Flyway config (H2 PostgreSQL-mode
  default; override `SPRING_DATASOURCE_*` for the owned PostgreSQL in production).
- **Migrations:**
  - `V001__create_report_filing.sql` — `report_filing` table: one idempotent row
    per `(lane, report_type, report_date)` covering BOK / KoFIU / Hometax filings,
    with lifecycle `PENDING → GENERATED → SUBMITTED → CONFIRMED/FAILED` and a UNIQUE
    natural key for scheduler re-run idempotency.
  - `V002__create_bok_report_record.sql` — `bok_report_record` table: one persisted
    FX1014/FX1015 record per cross-border transaction, UNIQUE on `txn_id`, with
    `offer_rate_coll DECIMAL(20,8)` (BOK FX1015 field #14).
- **Entities/repositories:** `ReportFiling`, `BokReportRecordEntity` and their
  Spring Data repositories.
- **`ReportFilingService`** — idempotent `openFiling` (natural-key reuse, no
  duplicates), `recordGenerated`/`recordSubmission`/`recordFailure` lifecycle
  transitions, and a double-submit guard (`IDEMPOTENCY_CONFLICT`).
- **`CommittedFxTransactionPort` + `FixtureCommittedFxTransactionPort`** — a
  rate-locked committed-FX source port carrying `offerRateColl` (FX1015 #14) and
  `crossRate`, with an in-process fixture so the FX1015 #14 path is exercised
  end-to-end. Replaces the `offer_rate_coll = null` limitation of the canonical
  GET client. See INTEGRATION REQUEST #1 (transaction-mgmt committed-FX endpoint).
- **`BokRecordPersistenceService`** — persists cross-border transactions as
  `bok_report_record` rows under per-type `report_filing` runs; idempotent at both
  filing and per-txn levels; carries `offer_rate_coll` verbatim.
- **Scheduler wiring (gated, unchanged default OFF):**
  - `BokReportScheduler` now prefers the FX port for content and persists filings +
    records (best-effort) when persistence is wired; the legacy file-only
    constructor is retained for unit tests.
  - `KofiuFeedScheduler` now records idempotent CTR/STR `report_filing` rows.
- **Tests (+12):** `ReportFilingServiceTest` (idempotent open, lifecycle,
  double-submit guard), `BokRecordPersistenceServiceTest` (FX1015 #14 populated +
  persisted, typed filings, idempotent re-run, 8-dp scale), and
  `FixtureCommittedFxTransactionPortTest` (date/partner filtering, offer-rate carry).

### Externally blocked (unchanged)

- BOK OI-03 gov format/SFTP/mTLS submission channel — clients remain gated stubs.
- Hometax OI-02 NTS e-tax-invoice channel — remains a gated stub.
- KoFIU live feed submission channel — remains a gated stub.
