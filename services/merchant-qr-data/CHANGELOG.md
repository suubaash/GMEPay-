# Changelog — merchant-qr-data

All notable changes to the `merchant-qr-data` service are documented here.
Format loosely follows [Keep a Changelog](https://keepachangelog.com/).

## [Unreleased] — 2026-06-30 (agent/merchant-qr-data)

### Added
- **Strict-mode merchant resolution** (`gmepay.merchant.strict-mode`, default `false`).
  When enabled, `GET /v1/merchants/{qr}` rejects non-operational merchants
  (status != `ACTIVE` or `active=false`) with `MERCHANT_NOT_FOUND` instead of
  returning them with HTTP 200 — removes the lenient fake-merchant bypass at the
  lookup layer. Default preserves the legacy golden-path behaviour.
- **`MerchantFileSource` transport port** + default `LocalDirectoryFileSource`
  implementation. Abstracts inbound batch-file delivery so the (externally blocked)
  real SFTP transport can be dropped in without changing the scheduler or sync
  service. `LocalDirectoryFileSource` lists recognised ZeroPay files from
  `gmepay.merchant-sync.inbound-dir` and optionally archives processed files to
  `gmepay.merchant-sync.archive-dir/{date}/`.
- **Full-list orphan reconciliation** for ZP0051 (merchant) and ZP0053 (QR)
  (`gmepay.merchant-sync.reconcile-orphans`, default `false`). When enabled, active
  records absent from the authoritative full list are soft-deleted
  (status `DEACTIVATED`, `active=false`). Only effective when the backing store can
  enumerate records (`ReconcilableMerchantRepository`).
- `ReconcilableMerchantRepository` capability interface, implemented by both
  `InMemoryMerchantRepository` and `MongoBackedMerchantRepository` (`findAll`).
- Tests: strict-mode accept/reject (active vs deactivated, lenient fallback);
  ZP0051/ZP0053 orphan deactivation round-trip (on/off); `LocalDirectoryFileSource`
  list/sort/filter + archive round-trip + idempotent ack.

### Changed
- `MerchantSyncScheduler` now depends on the `MerchantFileSource` port instead of
  scanning the filesystem directly, and acknowledges each successfully-processed
  file back to the source (archiving when configured).
- `MerchantSyncService` and `MerchantLookupService` gained `@Autowired` primary
  constructors with `@Value`-bound feature flags, retaining backwards-compatible
  constructors for unit tests.

### Notes / externally-blocked
- Real SFTP + PGP-decrypt delivery is externally blocked (no credentials / IDD
  sign-off); the `MerchantFileSource` seam is ready for it.
- lib-errors (frozen) lacks dedicated `MERCHANT_SUSPENDED` / `MERCHANT_DEACTIVATED`
  codes, so strict rejections reuse `MERCHANT_NOT_FOUND` (404) with the reason in the
  message. See INTEGRATION REQUEST #1.
