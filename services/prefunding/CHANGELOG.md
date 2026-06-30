# prefunding — CHANGELOG

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
