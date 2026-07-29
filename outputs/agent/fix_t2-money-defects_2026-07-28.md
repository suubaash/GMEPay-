> 작업: T2 money defects fix / 출처: agent

# T2-1 (SENDMN revenue misbooked/lossy) + T2-7 (cross-border refunds misroute to ZeroPay)

Scope: `services/payment-executor` **only**. `services/revenue-ledger` was **not touched** — the correct
posting API already existed there. No changes to ops-partner-bff, config-registry, docker-compose, Helm,
`SandboxE2eController`, or limits enforcement.

Tests: `gradlew :services:payment-executor:test` → **BUILD SUCCESSFUL**, 0 failures / 0 errors / 0 skipped.
`:services:revenue-ledger:test` not run — that module has no changes.

---

## TASK A — T2-1: SENDMN revenue booking

### Accounts / surfaces: before → after

| What | Before | After |
|---|---|---|
| FX margin (₩200 on a ₩10,000 pay) | `postRoundingResidual(txnRef, 200.00, "KRW")` → journal on **`REVENUE_ROUNDING`** | `postRevenueCapture(... payoutMarginUsd = 0.1481 USD ...)` → revenue record's `fxMarginUsd` (**REVENUE_FX_MARGIN**-equivalent) |
| Service fee (₩500) | `postRoundingResidual(txnRef + "-FEE", 500, "KRW")` → journal on **`REVENUE_ROUNDING`** | same call's `serviceChargeAmount = 500`, `serviceChargeCcy = "KRW"` (**REVENUE_SERVICE_CHARGE**-equivalent) |
| `REVENUE_ROUNDING` | received the corridor's entire P&L | **never touched by the SENDMN path** |
| txn revenue fields | 5-arg `StatusPatch` → `collectionMarginUsd`/`payoutMarginUsd` **null** → revenue record 0 | 13-arg `StatusPatch` with `payoutMarginUsd = fxMarginUsd`, `collectionMarginUsd = 0`, `collectionUsd = chargedUsd` |
| failed posting | `log.warn`, lost | row in `revenue_posting_failures` (status `PENDING`) with the replayable payload |

**Which API and why.** Per the task's instruction I mirrored the orchestrated (GMEREMIT/ZeroPay) confirm
path rather than adding anything: `PaymentOrchestrator.confirmMpm` books margin+fee via
`RevenueLedgerClient.postRevenueCapture(...)` → `POST /v1/revenue/capture` →
`RevenueCaptureService` (idempotent on `txnRef`). SENDMN now calls the identical method.
**No new method was added to revenue-ledger or its client.**

Deliberate non-goal: `LedgerPostingService.postRevenueCapture` (the balanced
`REVENUE_FX_MARGIN` / `REVENUE_SERVICE_CHARGE` **journal**) still has no production caller — that is
register item **T2-4**, and `RevenueCaptureService`'s own contract warns that wiring both surfaces would
double-count. So SENDMN revenue is now correctly *categorised and non-zero* in the revenue records/summary
surface; getting it into the double-entry journal remains T2-4 for every scheme, not a SENDMN defect.

**Margin leg + rate basis.** The margin is booked on the **payout** leg: the customer's KRW is collected
at the live rate (no collection spread) while the merchant is paid MNT at `offerRate = mid × (1 − margin)`.
`fxMarginUsd = fxMarginKrw / krwPerUsd` at 4dp, using the **same** `krwPerUsd` that priced the prefunding
deduction, so the booked margin and the deducted float sit on one rate basis. `feeSharePct = 0` (SENDMN
pays GME no share of a scheme merchant fee; recording the ZeroPay 0.70 default would misstate the corridor).

### Durability approach

No outbox, scheduler, or dead-letter exists in payment-executor (its event publisher is still
`LogEventPublisher`), so — as instructed — **no Kafka topic or new infrastructure was invented**. Instead:

- `V005__create_revenue_posting_failures.sql` — `(reference, posting_type)` unique, `payload` TEXT
  (the exact request body), `attempts`, `last_error`, `status PENDING|REPLAYED|ABANDONED`, index on
  `(status, created_at)`. Repeated failures bump `attempts` on the same row, so the PENDING set is
  exactly "postings still missing from revenue-ledger".
- `RevenuePostingFailureEntity` / `…Repository` / `…Store`. `record(...)` **never throws** (money has
  already moved; a DB hiccup must not turn a good payment into an error — it logs at ERROR instead).
- Critically, the recording lives inside **`RestRevenueLedgerClient`**, covering **all four** surfaces
  (capture, rounding residual, commission split, reversal journal). Without this the fix would be
  cosmetic: that client swallows its own transport failures by design, so `SendmnPaymentService`'s
  `try/catch` would never fire in production. `SendmnPaymentService` also records defensively for any
  client implementation that does throw. The store is optional (`@Nullable`), so minimal/test contexts
  behave exactly as before.

**Gap noted:** nothing drains the table yet — there is no replay job. The rows are the queryable,
self-contained evidence an operator or a future job needs (revenue-ledger is idempotent on
reference/txnRef, so replay cannot double-book). Building the drain belongs to **T2-5**.

## TASK B — T2-7: cancel/refund routing

`SchemeClientRouter.cancelPayment` discarded the scheme entirely ("cancel is a ZeroPay two-phase
concept"), so every Nepal/SENDMN cancel or refund posted to `/internal/scheme/zeropay/cancel` and came
back as a ZeroPay decline.

- **`SchemeClient`**: new `record CancelRequest(schemeTxnRef, reason, schemeId)` + a **default**
  `cancelPayment(CancelRequest)` that delegates to the existing two-arg method — so every per-scheme
  client and every hand-written test fake stays valid untouched.
- **`SchemeClientRouter`**: overrides it and routes with the same `route(request.schemeId())` used by
  `submitMpm`. The legacy two-arg form still defaults to ZeroPay, unchanged.
- **`ResilientSchemeClient`**: overrides it and guards on the **target** scheme's own breaker/bulkhead
  (a dead SendMN adapter must not trip ZeroPay's).
- **Unsupported outcome**: new `SchemeOperationNotSupportedException` (extends `PaymentException`,
  stable code `SCHEME_OPERATION_UNSUPPORTED`, carries scheme + operation), thrown by
  `NepalRestSchemeClient` / `SendmnRestSchemeClient` in place of the bare `PaymentException`. Mapped in
  `PaymentExceptionHandler` to **422, `retryable=false`** via the `ApiError(code, …)` string ctor (same
  pattern as `OperationalGateException`; lib-errors is frozen and was not touched). Added to
  `SchemeFailureRecordPredicate`'s ignore list so refund attempts on a single-shot corridor cannot trip
  that scheme's breaker and take its PAY path offline.
- **Callers**: `PaymentOrchestrator.cancelPayment/refundPayment` gained a `schemeId` parameter (old
  6-arg signatures kept as delegates → null → legacy ZeroPay routing, so no existing caller broke).
  Because the scheme call is the **first** step, an unsupported corridor reverses **no** float and writes
  **no** status — no half-applied refund. `PaymentController` reads `X-Scheme-Id` (or `schemeId` in the
  body) on `/cancel` and `/refund`. `WalletPayController.refund` accepts `schemeId` in the body and
  returns the structured `errorCode` on `WalletRefundResponse` (new field, `NON_NULL`, 5-arg compat ctor)
  — `SCHEME_OPERATION_UNSUPPORTED` vs `SCHEME_REFUND_FAILED`. Nothing is silently swallowed.

Out of scope as instructed and untouched: partial refunds, refund webhooks, `refundAmountKrw`, and the
wallet refund's zero-residual/`REVERSED` behaviour (**T2-6**).

## Tests added

`SendmnPaymentServiceTest` (+5): margin+fee booked via `postRevenueCapture` with the exact 9 args
(schemeId 9, `payoutMarginUsd` 0.1481, ₩500/KRW, feeSharePct 0); `postRoundingResidual` **never** called;
`StatusPatch` carries a non-null real margin + `collectionUsd`; a failed posting is persisted with a
replayable payload while the payment stays APPROVED; no-store degrades gracefully.
`RestRevenueLedgerClientTest` (+3): 5xx capture and 4xx residual persist for replay; no-store keeps the
old behaviour. `PaymentPersistenceH2SliceTest` (+2): V005 is H2-compatible, payload round-trips,
re-failure dedupes to one row with `attempts=2`, distinct types are distinct rows.
`SchemeClientRouterTest` (+4): SENDMN and NEPAL cancels reach ZeroPay **never** and surface
`SCHEME_OPERATION_UNSUPPORTED`; ZeroPay cancel still posts to its endpoint; scheme-less cancel keeps the
default. `PaymentOrchestratorTest` (+3): the scheme code reaches the client; unsupported cancel/refund
reverse nothing and commit nothing. `WalletPayControllerTest` (+2, 3 stubs migrated): SENDMN refund → 422
with `errorCode`, `transactionClient`/`revenueLedgerClient` untouched; no-schemeId keeps legacy routing.

## What remains

1. **No replay job** for `revenue_posting_failures` (→ T2-5). The rows exist; nothing drains them.
2. **Callers must pass the scheme code.** Omitting `X-Scheme-Id` / `schemeId` still falls back to
   ZeroPay. ops-partner-bff and admin-ui must send it (another agent's files).
3. **No backfill/reclass journal** for margin/fee already sitting in `REVENUE_ROUNDING`.
4. **Journal still unreached** — revenue records are right, the double-entry book is not (T2-4).
5. SENDMN ₩500/2% still hardcoded (CFO#11); MNT rounding residual still absorbed (CFO#15); the actual
   cross-border reversal is still a manual SendMN/Nepal process (T2-6).
