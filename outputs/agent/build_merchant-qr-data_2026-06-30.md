> 작업: merchant-qr-data backlog 완성 / 출처: agent

# merchant-qr-data — build report (2026-06-30)

Branch: `agent/merchant-qr-data` · worktree `D:/GMEPay+/wt/merchant-qr-data`

## Build status
`./gradlew :services:merchant-qr-data:test` — **GREEN**. 59 unit tests pass
(compile + tests). No Docker / Mongo / SFTP required; in-memory + temp-dir scope.

## Assessment of actual state (vs stale backlog)
The published backlog claims ~early-stage, but the real code is **far ahead**. Already
present and working before this run: pipe-delimited parsers for ZP0041/43/45/47/51/53
(`ZeroPayMerchantFileParser`, `ZeroPayQrFileParser`), `MerchantSyncService` (delta
upsert + MD/QD deactivation + full-list upsert), config-gated `MerchantSyncScheduler`
(`gmepay.merchant-sync.enabled`, daily KST cron), Mongo persistence behind
`spring.data.mongodb.uri` with in-memory fallback (`MongoBackedMerchantRepository`,
`@Primary` + `@ConditionalOnProperty`), `MerchantLookupService` + `GET /v1/merchants/{qr}`
controller, and a full parse→persist→deactivate round-trip test suite with fixtures.

So this run targeted the **genuinely-missing** items from the brief rather than redoing
parsers/scheduler.

## Tickets / items done this run (4)
1. **Strict-mode merchant resolution** (lenient fake-merchant bypass removal at lookup
   layer). New flag `gmepay.merchant.strict-mode` (default `false`). When on,
   `GET /v1/merchants/{qr}` rejects non-operational merchants (status != ACTIVE or
   active=false) with `MERCHANT_NOT_FOUND` + reason instead of returning HTTP 200.
   Default preserves the golden path. (`MerchantLookupService`)
2. **SFTP delivery abstraction** — `MerchantFileSource` port + default
   `LocalDirectoryFileSource` (list recognised files, sorted; optional archive to
   `archive-dir/{date}/` with idempotent ack). `MerchantSyncScheduler` refactored to
   depend on the port and acknowledge processed files. Real SFTP transport drops in
   behind the same interface (`@ConditionalOnMissingBean`).
3. **Full-list orphan reconciliation** for ZP0051 (merchant) + ZP0053 (QR) — the prior
   explicit `TODO(reconcile-orphans)`. Flag `gmepay.merchant-sync.reconcile-orphans`
   (default `false`); active records absent from the authoritative list are soft-deleted.
   Added `ReconcilableMerchantRepository` (`findAll`) impl in both in-memory + Mongo repos.
4. **CHANGELOG.md** created; `application.yml` documents all new keys; `ZeroPayFileType`
   Javadoc updated (orphan reconciliation no longer a TODO).

### Tests added
- Strict-mode: deactivated rejected, active returned, lenient fallback still returns
  inactive (`MerchantLookupServiceTest`).
- ZP0051/ZP0053 orphan deactivation round-trip, on and off (`MerchantSyncServiceTest`).
- `LocalDirectoryFileSourceTest`: list/sort/filter, missing dir, archive move,
  archiving-disabled no-op, idempotent ack.

## % estimate
Service is functionally **~85–90%** of its realistically-buildable (non-externally-blocked)
scope. Remaining work is largely externally-gated (real SFTP/PGP, official ZeroPay file
layouts) or cross-service (dedicated error codes, Smart-Router strict wiring).

## Externally-blocked items
- **Real SFTP + PGP-decrypt delivery**: no KFTC/ZeroPay credentials, no IDD sign-off.
  Abstracted behind `MerchantFileSource`; a `SftpMerchantFileSource` can be added later
  with zero changes to scheduler/sync service.
- **Final ZeroPay file layouts** (ZP0041/43/.. column order/widths): current parsers use
  documented internal approximations marked `TODO(spec)`; must be validated against the
  official Merchant Data Interface Specification.

## INTEGRATION REQUESTS
1. **lib-errors**: add `MERCHANT_SUSPENDED` and `MERCHANT_DEACTIVATED` error codes
   (suggest HTTP 422, non-retryable). Strict-mode currently reuses `MERCHANT_NOT_FOUND`
   (404) with the reason in the message because lib-errors is frozen and lacks these codes.
2. **Smart-Router / payment path**: the lenient fake-merchant bypass that synthesises an
   UNKNOWN merchant lives in the payment/router side (not this service). To fully close
   the bypass, the router should honour `gmepay.merchant.strict-mode` (or stop synthesising
   on lookup 404). This service now rejects inactive merchants when strict-mode is on.

## Remaining (top items)
- Wire strict-mode ON in deployed profiles once Smart-Router strict handling lands
  (INTEGRATION REQUEST #2) — currently default-off to avoid breaking the golden path.
- Replace `LocalDirectoryFileSource` with a real `SftpMerchantFileSource` when SFTP/PGP
  access + IDD layouts are available (externally blocked).
- Optional: Mongo/Testcontainers integration test for orphan reconciliation over the real
  `MongoBackedMerchantRepository.findAll()` (CI-only; not runnable locally without Docker).
