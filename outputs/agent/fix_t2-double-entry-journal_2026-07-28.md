> 작업: T2-4 double-entry journal / 출처: agent

# T2-4 — the main P&L now reaches the double-entry journal

Scope: `services/revenue-ledger` only. Branch `feat/exec-gap-closure-2026-07-28`.
Tests: **114 in the module, 0 failures**; `gradlew testClasses` green fleet-wide.
Schema: **no migration added** — nothing new needed a table or column. Next free Flyway version in this
module remains **V007**; there are no vendor-specific migration dirs here to mirror.

---

## 1. What was actually wrong (diagnosis confirmed)

The audit's claim held up exactly, and the grep is the whole story:

- `LedgerPostingService.postRevenueCapture` and `postFeeShareSplit` — the balanced FX-margin /
  service-charge / fee-share journals — had **zero production callers**. Every hit outside the module was
  payment-executor's **client** method of the same name (`RestRevenueLedgerClient.postRevenueCapture`),
  which POSTs to `/v1/revenue/capture` — i.e. the **single-entry record store** — plus docs and tests.
- Both ingestion surfaces said so in their own Javadoc: `RevenueCaptureService` — "**Not double-entry.** …
  it does NOT post balanced journals via `LedgerPostingService` (mixing the two would double-count)";
  `CommissionSplitController` — "it does NOT post the double-entry journal".
- So in production the journal received **only** rounding residuals and cancel/refund reversals.
  `RevenueReversalService`/`postReversalJournal` credited `RECEIVABLE_PARTNER` for reversals whose
  originating revenue had never debited it. The journal, read alone, was not a book of account, and **no
  trial balance was possible.**

Journalled before this change: rounding residual, reversal.
Recorded but never journalled: FX margin, service charge, the entire commission split.

## 2. What is now journalled

All of it reuses account codes and DR/CR sides that **already existed** in the module. **No account code
was added and no accounting policy was chosen.**

| Revenue type | Journal | Currency |
|---|---|---|
| FX margin | `DEBIT RECEIVABLE_PARTNER` / `CREDIT REVENUE_FX_MARGIN` | USD |
| Service charge | `DEBIT RECEIVABLE_PARTNER` / `CREDIT REVENUE_SERVICE_CHARGE` | record's ccy (KRW on the domestic path) |
| Commission split — **scheme leg** | `DEBIT RECEIVABLE_PARTNER net` / `CREDIT REVENUE_GME_FEE_SHARE gmeGross` / `CREDIT PAYABLE_SCHEME schemeShare` | KRW |
| Rounding residual | *(unchanged)* | as posted |
| Reversal | *(unchanged)* | as posted |

Both new postings are exactly the DR/CR shapes `postRevenueCapture` / `postFeeShareSplit` have always
used. The old methods are **untouched** (existing tests keep exercising them); new production wiring goes
through the two new idempotent methods.

### Atomicity
`RevenueCaptureService.capture` is now `@Transactional` and journals inside the same transaction as the
`revenue_records` insert; `CommissionSplitRecordService.recordIfAbsent` was already `@Transactional` and
journals inside it. Record and journal therefore commit together or roll back together — a record without
a journal (the exact state T2-4 is about) cannot be created by a partial write.
`RevenueCaptureAtomicityTest` proves the rollback with **real data**, not a mock: the ledger amount column
is `NUMERIC(20,8)` while the record column is `NUMERIC(20,4)`, so a 14-integer-digit margin fits the
record and overflows the ledger, failing only the journal insert. The record does not survive.

### Idempotency
Keyed the way the module already keys these things, so nothing new had to be invented:

- Probe: **only an ORIGINAL posting ever CREDITs an income account** (a reversal mirrors the sides, which
  is the mirror image of `RevenueReversalService`'s existing "a DEBIT to a `REVENUE_*` account marks a
  reversal" rule). A CREDIT to `REVENUE_FX_MARGIN`/`REVENUE_SERVICE_CHARGE` means the capture is journalled;
  a CREDIT to `REVENUE_GME_FEE_SHARE` means the split is.
- DB backstop: the pre-existing `UNIQUE(txn_ref)` on `revenue_records` and `commission_splits`. Because the
  record and the journal share one transaction, a concurrent duplicate loses the record insert and its
  journal rolls back with it. **No new key table, no new migration** (unlike the rounding path, which needed
  `rounding_residual_keys` precisely because it had no record row to key against).
- Replays also **back-fill**: a record that has no journal gets one on any re-post, and only one. That is
  the repair path for rows captured before this change.
- Zero-revenue transactions now post **nothing** instead of the nominal zero journal the old method emits
  (CFO#14); they are reported as `zeroAmount` by the self-check rather than as ledger noise.

## 3. The two artifacts

Both follow the module's existing conventions (`/v1/journals` and `/v1/revenue` base paths, ISO dates,
money as decimal strings per `MONEY_CONVENTION.md`, `error_code` bodies on 400).

### `GET /v1/journals/trial-balance?startDate=&endDate=[&strict=true]`
Per `(account, currency)` debit/credit totals and line counts, plus per-currency **whole-book** totals with
`difference`, a top-level `balanced` flag and an `imbalances` list. Because every `Journal` is validated
balanced before storage, a non-zero difference means ledger rows exist that no balanced journal produced —
so it logs at **ERROR**, and `strict=true` returns **409** so an automated day-close check cannot ignore it
by not reading the body.

### `GET /v1/revenue/journal-reconciliation?startDate=&endDate=[&strict=true]`
Revenue records / commission splits **versus** journal lines for the same period:

- **coverage** per table: `total`, `journalled`, `notJournalled` with the offending `txnRef`s (capped at
  100 with an honest `truncated` flag), and `zeroAmount` (nothing to journal — not an exception);
- **tieOuts** per stream: recorded total vs journalled total with a signed `variance`, so a disagreement in
  AMOUNT is visible even where both books have rows. Scoped by **reference set**, not journal post date, so
  the skew between a business `revenue_date` and a wall-clock `posted_at` cannot masquerade as a variance;
  gross CREDITs only, because a revenue record is never reversed while a reversal DEBITs the income account
  (reversal activity stays visible in the trial balance);
- **unmappedComponents**: money that is recorded but cannot be journalled for want of an account code,
  with its period total, the reason, and whose decision it is.

The two reports are complementary and both are needed: the trial balance proves the journal is internally
**consistent**; the reconciliation proves it is **complete**. Before T2-4 the journal balanced perfectly
while containing none of the main P&L — which is why "it balances" was never the right question.

## 4. DECISION NEEDED — the one mapping deliberately not made

**The partner-side leg of the two-sided commission split is not journalled**, because the account does not
exist and I will not invent it.

- What: `commission_splits.partner_share_krw` — the wallet partner's carve out of GME's gross commission
  (split 2 of `CommissionSplitCalculator`: `partner + gmeNet == gmeGross`). In the worked example
  (payout 100 000 KRW, 2.00% merchant, 0.20% VAN, 70% GME, 30% partner): net 1800, gmeGross 1260,
  scheme 540, **partner 378**, gmeNet 882.
- Why it cannot be posted: the module defines exactly `REVENUE_FX_MARGIN`, `REVENUE_SERVICE_CHARGE`,
  `REVENUE_GME_FEE_SHARE`, `RECEIVABLE_PARTNER`, `PAYABLE_SCHEME`, `REVENUE_ROUNDING`, `REVENUE_REVERSAL`.
  None of them is a partner-commission payable or a commission expense. `PAYABLE_SCHEME` is the *scheme
  operator's* liability, not the partner's, so reusing it would be wrong, not merely imprecise.
- **Consequence while undecided:** `REVENUE_GME_FEE_SHARE` carries GME's **GROSS** commission, so it
  overstates GME's retained commission by exactly `partner_share_krw`. The scheme-leg journal is balanced
  and ties to the record — it is just not the whole story.
- How it is surfaced rather than hidden: reported every period as
  `unmappedComponents[PARTNER_COMMISSION_SHARE]` with the exact KRW amount, and it holds the
  reconciliation's `clean` flag **false** while it carries money (so `strict=true` 409s). This is
  intentional — the books genuinely are incomplete until the decision is made, and the report must not
  claim otherwise. `CommissionSplitJournalTest.partnerSideLegIsDeliberatelyNotJournalled_…` asserts its
  absence **on purpose**, so once the account exists that test fails and forces the mapping in instead of
  the gap being forgotten.

**What the finance owner must decide:** which account the partner's carve debits and which it credits —
i.e. a partner-commission **payable** (liability to the partner) plus its counterpart, and whether that
counterpart is a **cost of revenue / commission expense** or a **contra-revenue reduction** of
`REVENUE_GME_FEE_SHARE`. That choice changes reported gross revenue, so it is not a coding detail.

## 5. Still open in T2-4 (not closed here)

Out of scope by instruction (other owners / other services), or genuinely not a revenue-ledger concern:

1. **Scheme's actual fee (ZeroPay field 41) still unused.** The split is computed from a config-resolved
   merchant-fee rate, which can drift from what ZeroPay actually charged. The adapter *does* receive
   `merchantFeeKrw` off the wire, but the hub contract `SchemeClient.MpmSubmitResponse` has no
   merchant-fee field. **Follow-up on the caller side** (payment-executor + the scheme contract, which was
   concurrently owned): thread `merchantFeeKrw` through and post it, then this module's split computes off
   the scheme figure. No change is needed here to accept it — the endpoint already takes the amounts.
2. **The silent skip is still silent.** `PaymentOrchestrator.postCommissionSplit` no-ops when payout isn't
   KRW, no merchant-fee rate was snapshotted, or config-registry doesn't resolve both shares, and nothing
   counts those skips. The new self-check makes the *result* visible (a transaction with no split row at
   all is simply absent from `commission_splits`), but **not the reason** — a skipped-split counter belongs
   in payment-executor. **Follow-up.**
3. **No MDR billing/receivables subsystem** (CFO#5) — one of the three revenue streams still has no
   collection mechanism. Tracked as T2-1; nothing in this module can close it. Note `RECEIVABLE_PARTNER` is
   now genuinely debited by revenue, so the receivable side of the books is at least real.
4. **Neither report is scheduled.** Both are operator/day-close pull endpoints. A daily job that runs the
   trial balance `strict=true` and alerts on 409 is the natural next step (and belongs with T2-5's replay /
   day-close work).
5. **Neither endpoint is authenticated** — `revenue-ledger` is on the T0-2 "still unauthenticated" list, so
   these two reads inherit that gap. They are internal-only surfaces; gating them is a T0-2 rollout item,
   not a T2-4 one.

## 6. Found in passing — NOT fixed (pre-existing defect)

`RevenueReversalService.reverseCapture` mirrors **every** line for a txnRef, including a `REVENUE_ROUNDING`
line if one exists. `JpaJournalStore.save` then classifies the reversing journal as a rounding journal and
tries to insert a **second** `rounding_residual_keys` row for the same `reference` → PK violation. So
reversing a transaction that also carries a rounding residual fails. This predates T2-4 (both postings key
on the same txnRef today) and fixing it would mean changing an existing posting's behaviour, which the
guardrail forbids. Logged here so it is not lost; it belongs with T2-6 (refund money path) or T2-5.

## 7. Files

Modified (`services/revenue-ledger`):
- `domain/ledger/LedgerPostingService.java` — added `postCapturedRevenueJournal`,
  `postCommissionSplitJournal`, the shared `alreadyCreditedFor` idempotency probe. Existing methods'
  accounts and amounts **unchanged**.
- `revenue/RevenueCaptureService.java` — `@Transactional`, journals the capture.
- `fees/CommissionSplitRecordService.java` — journals the scheme leg.
- `persistence/LedgerEntryEntityRepository.java` — `trialBalanceRows`.
- `persistence/RevenueRecordJpaRepository.java`, `persistence/CommissionSplitRecordRepository.java` —
  additive date-ranged counts / sums / missing-journal finders.
- `CHANGELOG.md`.

Added:
- `persistence/TrialBalanceService.java`, `web/TrialBalanceView.java`, `web/TrialBalanceController.java`.
- `persistence/RevenueJournalReconciliationService.java`,
  `web/RevenueJournalReconciliationView.java`, `web/RevenueJournalReconciliationController.java`.
- Tests: `revenue/RevenueCaptureJournalTest`, `revenue/RevenueCaptureAtomicityTest`,
  `fees/CommissionSplitJournalTest`, `persistence/TrialBalanceServiceTest`,
  `persistence/RevenueJournalReconciliationTest`, `web/TrialBalanceAndReconciliationApiTest`.

Existing tests touched only to pass the new constructor dependency
(`PaymentApprovedEventHandlerTest`, `RevenueCaptureControllerTest`) — no assertion was weakened.
