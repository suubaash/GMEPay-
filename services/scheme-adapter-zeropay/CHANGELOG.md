# scheme-adapter-zeropay — CHANGELOG

All notable changes to the ZeroPay scheme adapter. Newest first.

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

### Tests
- `ZpCommittedTxnPersistenceH2SliceTest` — round-trips committed payment/refund rows + the
  unique `(txn_kind, zeropay_txn_ref)` guard, on H2 (PostgreSQL mode).
- `ZpPersistenceBatchDataPortTest` — payment/refund/settlement/detail mapping incl. fee netting
  and per-merchant aggregation, against a mocked repository.
- `ZpPersistenceBatchDataPortRoundTripTest` — captured txns → data port → ZP0011/ZP0021
  formatter → parser round-trip with non-empty records and matching control sums.

### Externally blocked (not attempted)
- Real KFTC/ZeroPay SFTP endpoint, PGP encryption, and final IDD field widths (van_fee,
  txn_time, exact reserved widths) — gated on certification calendar.
- Refund amount/merchant enrichment on the `/cancel` path — needs the transaction-management
  contract (the cancel call carries only the original authId).
