# scheme-adapter-zeropay — CHANGELOG

All notable changes to the ZeroPay scheme adapter. Newest first.

## 2026-06-30 — Phase 2: cross-service batch enrichment (refund amount/merchant, settlement value date)

Enriches the locally-captured batch records from transaction-management committed/refund data,
behind a config gate. Resolves build-report INTEGRATION REQUESTS IR-1 (refund) and IR-3 (value date).

### Added
- **`ZpBatchEnrichmentPort`** — port for cross-service enrichment of batch records:
  `refundEnrichment(date)` (real `refundAmountKrw`/`merchantId`/`qrCodeId` keyed by scheme txnRef,
  IR-1) and `settlementValueDates(date)` (T+n settlement value date keyed by scheme txnRef, IR-3).
  Best-effort contract: implementations return empty maps and never throw.
- **`RestTransactionMgmtEnrichmentPort`** (`@ConditionalOnProperty adapter.zeropay.enrichment.enabled=true`)
  — gated REST client (mirrors `ZeroPaySchemeApiClient` gating: config base URL + package-private
  test ctor). Binds `GET /v1/transactions/refunded?refundedOn=` and
  `GET /v1/transactions/fx-committed?committedOn=`. On disable/unreachable/error it logs and
  degrades to pre-enrichment behaviour (the batch run never fails).
- **`NoOpZpBatchEnrichmentPort`** — default `@ConditionalOnMissingBean` fallback returning empty
  maps, so local/CI runs (transaction-management not running) keep the in-process behaviour.
- Config block `adapter.zeropay.enrichment.{enabled,transaction-mgmt-base-url}` (default OFF,
  base URL `http://localhost:8080`).
- Tests: `RestTransactionMgmtEnrichmentPortTest` (MockRestServiceServer — maps real
  amount/merchant/qrCode + value date by txnRef; empty-map fallback on upstream 5xx) and new
  `ZpPersistenceBatchDataPortTest` cases (refund enrichment fills ZP0021/ZP0066; upstream value
  date used in ZP0065/ZP0066; fallbacks preserved).

### Changed
- **`ZpPersistenceBatchDataPort`** now consumes `ZpBatchEnrichmentPort`: refund records
  (ZP0021/ZP0066) and settlement netting use the enriched `refundAmountKrw`/`merchantId`/`qrCodeId`
  when the captured row lacks them; ZP0065/ZP0066 settlement value date uses the committed
  `settlementDate` from upstream, falling back to the captured row's date then the business date.
  A package-private one-arg constructor (no-op enrichment) preserves existing unit-test slices.

### Remaining INTEGRATION REQUEST
- Per-txn **fee values** (`merchantFeeKrw`/`vanFeeKrw`) remain `0` — they belong to the
  commission/config side (backlog #98), not this adapter. TODO recorded on
  `ZpPersistenceBatchDataPort`; no fee table built here.

## 2026-06-30 — Real batch data source (non-empty ZP00xx files)

Replaces the zero-record `ZpStubBatchDataPort` with a real, in-service data source so the
daily ZeroPay batch files carry actual approved payments and refunds.

### Added
- **`zp_committed_txns` table** (`V003__create_zp_committed_txns.sql`) — captures every
  committed MPM/CPM payment and completed refund on the real-time path, in KST business-date
  scope, with KRW amounts as `NUMERIC(20,0)`. This is the local source of truth for the daily
  files; it does NOT read transaction-management / settlement-mgmt (frozen — see INTEGRATION
  REQUESTS in the build report).
- **`ZpCommittedTxnEntity` + `ZpCommittedTxnRepository`** — JPA mapping + finders by
  `(business_date, txn_kind)` and `(business_date, merchant_id)`.
- **`ZpCommittedTxnRecorder`** — best-effort capture component; wraps every write so a
  duplicate re-submit or transient error is logged and swallowed (the real-time partner
  response is never affected).
- **`ZpPersistenceBatchDataPort`** (`@Primary`, `adapter.zeropay.batch-data-source=persistence`,
  default) — reads `zp_committed_txns` and emits non-empty ZP0011 / ZP0021 / ZP0061 / ZP0063 /
  ZP0065 / ZP0066 records, with per-merchant settlement aggregation (fees net of refund
  reversals). Set `batch-data-source=stub` to fall back to the empty-file port.

### Changed
- `ZeroPaySchemeAdapter` now captures committed payments (MPM REST + CPM) and refunds via
  `ZpCommittedTxnRecorder`. A second (recorder-less) constructor preserves existing unit-test
  slices; the production constructor is `@Autowired`.

### Added — batch-file registry wiring
- **`ZpBatchRegistrar`** — persists the `zp_batch_files` registry row (status `GENERATED`)
  and stages ZP0011 detail lines (`zp_staged_records`) for each outbound file, then flips the
  row to `TRANSMITTED` after a successful SFTP put. Idempotent on the natural key
  `(file_type, business_date, sequence_no)`; staged fields are defensively truncated to their
  column widths. Closes the gap where the V001/V002 registry tables were written by nothing on
  the live path (so duplicate-generation / out-of-window detection and ZP0012 reconciliation
  had no data to work with).
- `ZeroPayBatchScheduler` now registers → transfers → marks-transmitted via the registrar
  (best-effort; a registry failure never blocks the transfer). A second (registrar-less)
  constructor preserves the existing scheduler unit test.

### Tests
- `ZpCommittedTxnPersistenceH2SliceTest` — round-trips committed payment/refund rows + the
  unique `(txn_kind, zeropay_txn_ref)` guard, on H2 (PostgreSQL mode).
- `ZpPersistenceBatchDataPortTest` — payment/refund/settlement/detail mapping incl. fee netting
  and per-merchant aggregation, against a mocked repository.
- `ZpPersistenceBatchDataPortRoundTripTest` — captured txns → data port → ZP0011/ZP0021
  formatter → parser round-trip with non-empty records and matching control sums.
- `ZpBatchRegistrarH2SliceTest` — registry round-trip (GENERATED → staged lines → TRANSMITTED)
  + natural-key idempotency, on H2.

### Externally blocked (not attempted)
- Real KFTC/ZeroPay SFTP endpoint, PGP encryption, and final IDD field widths (van_fee,
  txn_time, exact reserved widths) — gated on certification calendar.
- Refund amount/merchant enrichment on the `/cancel` path — needs the transaction-management
  contract (the cancel call carries only the original authId).
