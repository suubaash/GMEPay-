# prefunding — CHANGELOG

## 2026-06-30 (p2/prefunding — Phase-2 cross-service wiring)

### Added — deduction history (IR-pe-2)
- `GET /v1/prefunding/{code}/deductions?limit=N` on `PrefundingController` → canonical
  `PrefundingDeductionHistoryView` (lib-api-contracts) wrapping most-recent-first
  `BalanceDeductionEntry` rows (amountUsd / at / txnRef). Unblocks payment-executor balance
  `?include_history=true`. `limit` defaults to 20, clamped 1..500; unknown partner ⇒ empty list.
- `PrefundingService.recentDeductions(partnerId, limit)` + repo
  `findByPartnerIdAndEntryTypeOrderByCreatedAtDescIdDesc` (Pageable-bounded DEBIT query).

### Added — CPM reserve/release (IR-qr-3)
- `POST /internal/v1/prefunding/{partnerId}/reserve` (`PrefundingReserveRequest` →
  `PrefundingReserveResponse`) soft-holds funds at OVERSEAS CPM/QR token issuance; idempotent on
  `idempotencyKey` (CPM token/session id, falls back to txnRef then `Idempotency-Key` header).
  Returns the RESERVE-ledger-entry-id `reservationId` handle + availableUsd + total reservedUsd.
- `POST /internal/v1/prefunding/{partnerId}/release` (`PrefundingReleaseRequest`) frees the hold on
  expiry/decline; idempotent on `idempotencyKey`; a release with no active hold is a 0 no-op.
- Backed by the same per-partner `SELECT ... FOR UPDATE` lock + `available = balance + credit_limit −
  reserved` invariant as the existing two-phase reserve/capture/release; 402 INSUFFICIENT_PREFUNDING on
  overdraw, nothing held. New service methods `reserveForCpm` / `releaseForCpm` reuse the RESERVE/RELEASE
  ledger machinery without disturbing the existing txnRef-keyed `reserve`/`release`.

### Tests
- `PrefundingCpmReserveApiTest`: reserve holds + returns reservationId; idempotent reserve replay;
  release restores available; idempotent release; reserve-overdraw → 402; reserve+deduct interaction
  stays non-negative against available.
- `PrefundingDeductionHistoryApiTest`: most-recent-first + limit cap; default limit; unknown partner ⇒
  empty list.

## 2026-06-30 (agent/prefunding)

### Added — internal cross-service deduct/reverse (transaction-mgmt integration request)
- `POST /internal/v1/prefunding/{partnerId}/deduct` and `POST /internal/v1/prefunding/{partnerId}/reverse`
  on a new `PrefundingInternalController`, letting transaction-mgmt drive PENDING_DEBIT→DEBITED and
  reversal without cross-DB access (MSA rule).
  - Atomic: backed by the existing per-partner `SELECT ... FOR UPDATE` row lock.
  - Idempotent: deduct keys on `idempotencyKey` (request body) or the `Idempotency-Key` header,
    persisted as the ledger `txn_ref`; a replay returns the original DEBIT entry id + unchanged
    balance (`replayed=true`) and does NOT double-charge. Reverse is idempotent by `txnRef`.
  - Both responses carry the resulting `balance` (+`currency`) and the `ledgerEntryId` so the caller
    can record the concrete ledger reference. 402 INSUFFICIENT_PREFUNDING on overdraw; 400 when the
    idempotency key is missing from both body and header.
- `PrefundingService.deductIdempotent(...)` returning `DeductResult(balanceAfter, ledgerEntryId, replayed)`;
  `deduct(...)` now delegates to it (unchanged behaviour for existing callers).
- `PrefundingService.ReverseResult` now also carries `ledgerEntryId` (the reversal CREDIT entry id,
  or the existing one on a replay; null when there was nothing to reverse).

### Tests
- `PrefundingInternalApiTest` (MockMvc): deduct returns balance+ledgerEntryId; idempotent replay via
  body key and via header; insufficient→402; missing key→400; reverse restores balance + reports
  credit id; second reverse is a 0 no-op.
- `InternalDeductConcurrencyIT` (H2 PG mode, no Docker): 10 concurrent deducts on a 500-USD balance
  admit exactly 5 winners, balance ends at exactly 0 and never goes negative; a concurrent burst of
  replays for one idempotency key debits at most once.
- `PrefundingServiceIT`: added idempotent-replay-no-double-charge and reverse-reports-credit-id cases.
