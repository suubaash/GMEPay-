> 작업: reporting-compliance backlog 완성 / 출처: agent

# reporting-compliance — Build Report (2026-06-30)

## Build status
`./gradlew :services:reporting-compliance:test` — **BUILD SUCCESSFUL**.
15 test classes, all green. +12 new tests this run (all passing).
Commit: `a4a17b2` on `agent/reporting-compliance`.

## Assessment of actual code state (vs stale backlog %)
The service was far ahead of the published backlog. Already present and clean
BEFORE this run:
- BOK FX1014/FX1015 domain mapping (`BokFxMapper`/`BokFxRecord`/`CommittedTransaction`)
  with `offer_rate_coll` (FX1015 #14) carried verbatim from a committed-transaction
  value object — NOT recomputed.
- KoFIU CTR (FTRA Art.4 threshold aggregation) + STR (per-corridor gating) services,
  file builders, gated stubs and config ports.
- Hometax monthly e-tax-invoice service + VAT math + gated stub client.
- Gated schedulers (BOK / KoFIU / Hometax), all default OFF.
- Canonical-GET `RestTransactionClient` + ports + WireMock-style tests.

The genuine gaps (per the gap plan) were: **no persistence at all** (no JPA, no
migrations, no record of what was generated/filed), **no idempotent filing
records**, and **FX1015 #14 unpopulated** because the canonical
`GET /v1/transactions` endpoint cannot supply the rate-locked margin fields
(`RestTransactionClient` set `offer_rate_coll=null`, `partnerId=0`).

## Tickets done this run
1. **Owned datastore stood up** — added spring-data-jpa, flyway-core
   (+flyway-database-postgresql), postgresql, h2, validation to the build;
   replaced `application.properties` with `application.yml` (H2 PostgreSQL-mode
   default, JPA ddl-auto=none, Flyway enabled). (covers spirit of 7.5-T01-class
   persistence groundwork)
2. **V001 `report_filing`** migration — idempotent filing record per
   `(lane, report_type, report_date)`, lifecycle PENDING→GENERATED→SUBMITTED→
   CONFIRMED/FAILED, UNIQUE natural key, status CHECK, indexes.
3. **V002 `bok_report_record`** migration — per-txn FX1014/FX1015 record,
   UNIQUE(txn_id), `offer_rate_coll DECIMAL(20,8)` = FX1015 field #14, FK to filing.
   (covers 13.7-T03-class persistence)
4. **JPA entities + repositories** — `ReportFiling`, `BokReportRecordEntity`.
5. **`ReportFilingService`** — idempotent `openFiling`, lifecycle transitions,
   double-submit guard (`IDEMPOTENCY_CONFLICT`).
6. **`CommittedFxTransactionPort` + `FixtureCommittedFxTransactionPort`** — the
   rate-locked committed-FX source carrying `offer_rate_coll`/`cross_rate`, so
   FX1015 #14 is populated end-to-end. In-process fixture; HTTP adapter drops in
   once transaction-mgmt exposes the endpoint.
7. **`BokRecordPersistenceService`** — persists cross-border txns as
   `bok_report_record` rows under per-type filings; idempotent at filing AND
   per-txn level; carries `offer_rate_coll` verbatim.
8. **Scheduler wiring** — `BokReportScheduler` prefers the FX port + persists
   (best-effort, gated; legacy file-only ctor retained for tests);
   `KofiuFeedScheduler` records idempotent CTR/STR filings.
9. **Tests (+12)** — `ReportFilingServiceTest` (idempotent open / lifecycle /
   double-submit), `BokRecordPersistenceServiceTest` (FX1015 #14 populated +
   persisted from the txn source, typed filings, idempotent re-run, 8-dp scale),
   `FixtureCommittedFxTransactionPortTest` (date/partner filtering, offer-rate carry).

## % estimate
Service ≈ **90%** of buildable (non-externally-blocked) scope. Remaining 10% is
admin report query/CSV/RBAC endpoints (7.5-T06..T26) and revenue_record
persistence — owned by/overlapping the ops-BFF + revenue-ledger services, not
required for the compliance reporting core.

## Externally blocked (unchanged — gated stubs kept)
- **OI-03 BOK** real submission: gov FX1014/FX1015 wire format + SFTP/mTLS channel.
- **OI-02 Hometax** NTS e-tax-invoice: real NTS API format + mTLS issuer cert.
- **KoFIU** live feed submission channel (real endpoint + credentials).
All three remain config-gated stubs (default OFF); filings persist at GENERATED.

## INTEGRATION REQUESTS
1. **transaction-mgmt** — expose a committed-FX projection (e.g.
   `GET /v1/transactions/fx-committed?from&to&partnerId`) returning, per committed
   cross-border transaction: `txnId`, `txnRef`, `partnerId`, `direction`,
   `sameCcyShortcircuit`, and the rate-locked `offerRateColl`
   (= send_amount / (collection_usd − collection_margin_usd), BOK FX1015 field #14),
   `crossRate` (= target_payout / send_amount), `collectionAmount`/`collectionCcy`,
   `payoutAmount`/`payoutCcy`, `usdAmount`, `committedAt`. The canonical
   `GET /v1/transactions` omits the margin-derived fields, so FX1015 #14 cannot be
   populated from it. Coded against `CommittedFxTransactionPort` with an in-process
   fixture pending this endpoint.

## Remaining (next runs, in priority order)
1. Persist CTR/STR report *content* (not just filing summary) — `kofiu_report_record`
   table + entity, linked to the CTR/STR `report_filing`.
2. Admin report query/CSV/RBAC endpoints (7.5-T06..T26) — coordinate with ops-BFF /
   revenue-ledger to avoid duplicating the revenue_record source of truth.
3. HTTP adapter for `CommittedFxTransactionPort` once INTEGRATION REQUEST #1 lands.
