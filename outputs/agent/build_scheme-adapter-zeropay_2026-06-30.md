> 작업: scheme-adapter-zeropay backlog 완성 / 출처: agent

# scheme-adapter-zeropay — build report (2026-06-30)

## Build status
- `./gradlew :services:scheme-adapter-zeropay:test` — **BUILD SUCCESSFUL**, green.
- 144 unit/slice tests across 21 classes pass (17 new this run). H2 (PostgreSQL-mode) scope; Docker-tagged Postgres ITs remain CI-only.
- 2 incremental commits on `agent/scheme-adapter-zeropay`.

## Actual code state assessed first (docs were stale)
Most of the service was already far ahead of the published backlog:
- Real-time MPM/CPM/refund path against sim-scheme — LIVE (`ZeroPaySchemeAdapter`, controller).
- All ZP00xx fixed-width formatters/parsers — present + unit-tested (ZP0011/0012/0021/0022/0061/0063/0065/0066, settlement req/result).
- 전문/TCP transport, jeonmun codec, error-code mapper — present.
- SFTP port abstraction (`SftpTransport` + `LocalDirSftpTransport`) — present.
- Persistence registry **schema** (V001 `zp_batch_files`, V002 `zp_staged_records`) + entities/repos + contract test — present.
- KST batch scheduler (6 windows, config-gated `adapter.zeropay.batch-enabled`) — present.

**Two genuine high-value gaps found and closed** (both fully in-service, no frozen-service edits):

## Tickets done this run

1. **Real batch data source (replaces zero-record `ZpStubBatchDataPort`).** This was the headline PRD gap ("batch path produces zero-record files").
   - New `zp_committed_txns` table (V003) capturing every committed MPM/CPM payment and completed refund at commit time, in KST business-date scope, KRW `NUMERIC(20,0)`.
   - `ZpCommittedTxnEntity` + `ZpCommittedTxnRepository`.
   - `ZpCommittedTxnRecorder` — best-effort capture on the real-time path (duplicate/transient errors logged + swallowed; the partner response is never affected). Wired into `submitMpm` (REST), `authoriseCpm`, and `cancelPayment` in `ZeroPaySchemeAdapter` (via a second `@Autowired`/legacy two-ctor pair so existing unit slices keep compiling).
   - `ZpPersistenceBatchDataPort` (`@Primary`, `adapter.zeropay.batch-data-source=persistence`, default) — emits non-empty ZP0011/ZP0021/ZP0061/ZP0063/ZP0065/ZP0066 records with per-merchant settlement aggregation (fees net of refund reversals). `batch-data-source=stub` falls back to the empty-file port.

2. **Batch-file registry wiring (registry tables were dead on the live path).**
   - `ZpBatchRegistrar` — persists `zp_batch_files` (status `GENERATED`) + stages ZP0011 detail lines (`zp_staged_records`), then flips to `TRANSMITTED` after a successful SFTP put. Idempotent on the `(file_type, business_date, sequence_no)` natural key; staged fields defensively truncated to column widths.
   - `ZeroPayBatchScheduler` now register → transfer → mark-transmitted (best-effort; registry failure never blocks the transfer). Second registrar-less constructor preserves the existing scheduler test.

### Tests added (17)
- `ZpCommittedTxnPersistenceH2SliceTest` (4) — payment/refund round-trip, finder ordering, unique-ref idempotency guard.
- `ZpPersistenceBatchDataPortTest` (5) — payment/refund/settlement/detail mapping, fee netting, per-merchant aggregation (mocked repo).
- `ZpPersistenceBatchDataPortRoundTripTest` (2) — captured txns → data port → ZP0011 formatter → parser, non-empty records + control-sum tie-out; empty-day header/trailer-only.
- `ZpBatchRegistrarH2SliceTest` (2) — registry GENERATED→staged→TRANSMITTED + natural-key idempotency.

## % estimate
Service ~90% of buildable-without-external-blockers scope. Real-time path, all formatters/parsers, persistence (registry + committed-txn capture + wiring), config-gated scheduler, SFTP abstraction all complete and tested. Remaining is externally-blocked or contract-gated (below).

## Externally blocked (NOT attempted, per instructions)
- Real KFTC/ZeroPay SFTP endpoint, PGP encrypt/decrypt, mTLS — certification calendar.
- Final IDD field widths (`van_fee`, `txn_time`, exact reserved widths) — gated on the formal ZeroPay BS-04/BS-07 spec; current widths carry `TODO` markers in the record classes.
- Live KFTC 전문 balance inquiry (currently a configurable sim balance in the controller).

## INTEGRATION REQUESTS
1. **transaction-management → scheme-adapter-zeropay: refund amount/merchant enrichment.** The `/internal/scheme/zeropay/cancel` contract passes only the original `authId`; the refund leg captured into `zp_committed_txns` therefore has amount=0 and null merchant. Please publish a refund contract carrying `{ originalSchemeTxnRef, refundAmountKrw, merchantId, qrCodeId, merchantFeeKrw, vanFeeKrw }` so ZP0021/ZP0066 refund records and settlement netting reflect real refund amounts. (Until then the refund linkage is captured but amounts are zero.)
2. **merchant-fee/commission source → scheme-adapter-zeropay: per-txn fee values.** Committed payments are captured with `merchantFeeKrw=0`/`vanFeeKrw=0` because the adapter does not receive computed fees at commit time. Please publish the merchant-fee and VAN-fee values (or a fee table keyed by merchant/partner type) so ZP0011/ZP0065 detail fee columns and ZP0061/ZP0063 aggregate fees are populated. (Ties to backlog #98 commission-sharing wiring.)
3. **transaction-management → scheme-adapter-zeropay: settlement value date (`settlement_date`).** `zp_committed_txns.settlement_date` is currently null and the data port falls back to business_date for ZP0065/ZP0066. Please publish the T+n settlement value date so the detail files carry the correct settlement date.
4. **merchant-qr-data ← scheme-adapter-zeropay: inbound merchant-sync forwarding.** `processMerchantSync` counts record types but does not upsert (correctly out of scope here). Please confirm/publish the merchant-qr-data ingest contract so parsed merchant-sync records can be forwarded once inbound sync is enabled.

## Remaining (next, not blocked)
- Inbound fetch jobs + registry: register fetched ZP0012/ZP0022/ZP0062/ZP0064 as INBOUND (`RECEIVED`→`PARSED`/`PROCESSED`) and stage ZP0012 results for reconciliation against the staged ZP0011 lines (the match-key finder already exists). A reconciliation tie-out (ZP0011 staged vs ZP0012 registered amounts) would round out SCH-06 §5.3.
- Stage ZP0021/ZP0065/ZP0066 detail lines in the registry too (currently only ZP0011 is staged; the staging table’s CHECK constraint allows ZP0011/ZP0012 only — a V004 widening + entity factory would be needed).
- Enrich captured fees/amounts once integration requests 1–3 land.
