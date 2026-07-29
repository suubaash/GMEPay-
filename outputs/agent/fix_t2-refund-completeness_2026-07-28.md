> 작업: T2-6/T2-9 refund completeness / 출처: agent

# T2-6 / T2-9 — the refund money path, end to end

Scope: `services/payment-executor`, `services/transaction-mgmt`, `services/revenue-ledger`,
`services/notification-webhook`, `services/settlement-reconciliation`. Branch
`feat/exec-gap-closure-2026-07-28`.

Tests: **942 across the five modules, 0 failures** (payment-executor 343 · transaction-mgmt 154 ·
revenue-ledger 120 · notification-webhook 139 · settlement-reconciliation 186), forced re-run;
`gradlew testClasses` green fleet-wide.

**Schema: no migration added, in any of the five modules.** Nothing needed a table or a column —
`transactions.refund_amount_krw` / `refunded_at` / `original_payment_txn_ref` already existed (V007) and were
simply never written. Next free Flyway version is therefore unchanged: payment-executor **V007**,
transaction-mgmt **V012**, revenue-ledger **V007**, notification-webhook **V008**,
settlement-reconciliation **V012**. None of these modules has vendor-specific migration dirs to mirror.

---

## 1. Each defect, verified before it was fixed

All six claims held up. Two of them were understated, and one was diagnosed wrongly — those corrections
matter more than the fixes, so they are called out.

| # | Claim | Verdict |
|---|---|---|
| 1 | No partial refunds | **Confirmed.** `CancelPaymentRequest`/`WalletRefundRequest` carried no amount; `refundPayment` always called `prefundingClient.reverse(txnRef)` (all-or-nothing); `RefundPaymentResponse` had no amount field. |
| 2 | `refundAmountKrw` never populated | **Confirmed, and there was a SECOND cause nobody had spotted** — see §3. |
| 3 | `REFUNDED` emits no event | **Confirmed.** `TransactionStateMachine` published `PaymentReversedEvent` only for `to == REVERSED`. |
| 4 | No refund webhook | **Confirmed.** `notification-webhook`'s consumer package contained exactly one handler, for `payment.approved`. |
| 5 | Wallet refund posts a zero residual, patches `REVERSED` | **Confirmed.** Literally `postRoundingResidual(schemeTxnRef + "-REFUND", BigDecimal.ZERO, "KRW")` and `StatusPatch(REVERSED, …)`. |
| 6 | T2-9 reversal fails on a rounding residual | **Confirmed as a defect, WRONG about the failure mode** — see §6. It corrupted rather than failed. |

---

## 2. Partial refunds

An `amount` (+ optional `currency`) now rides `CancelPaymentRequest` and `WalletRefundRequest`, through
`PaymentOrchestrator.refundPayment(…, requestedAmount, requestedCurrency)`, and back out on
`RefundPaymentResponse` / `WalletRefundResponse` as `refunded_amount`, `refunded_currency`,
`cumulative_refunded_amount`, `fully_refunded` — so a partner driving a sequence of partial refunds does not
have to keep its own drift-prone tally. **Omitting `amount` is a full refund and is unchanged**, including for
callers that cannot answer the new read at all.

### Validation, all of it before anything moves

`refundPayment` reads the original via a new `TransactionClient.findRefundBasis` (`GET /v1/transactions/{ref}`)
and hands it to `planRefund`, which is the whole of the arithmetic and refuses in three distinct, separately
coded ways:

- `REFUND_AMOUNT_EXCEEDS_ORIGINAL` (422, not retryable) — the request **plus everything already refunded**
  exceeds the original. This is the cumulative guard: 20 000 + 20 000 + 20 000 against a 50 000 payment sees
  the third rejected, and the message states what *is* still refundable.
- `REFUND_AMOUNT_INVALID` (422, not retryable) — non-positive, or a currency other than the original
  collection currency. **Refused, never converted**: a refund reverses the original booking, and any rate we
  picked to convert with would not be the locked one.
- `REFUND_BASIS_UNAVAILABLE` (503, **retryable**) — the original could not be read. This blocks a PARTIAL
  refund only; a FULL refund proceeds exactly as before, so a transaction-mgmt hiccup does not break the path
  this gap is not about. `findRefundBasis` deliberately fails soft to `Optional.empty()` and lets the caller
  decide the consequence.

### "At the original locked rate", without reading a rate

The refunded USD is the captured `prefundDeductedUsd` pro-rated by the refunded fraction. **No rate is read**,
so no rate can have moved since the payment — the locked-rate guarantee is structural rather than a lookup.
A refund that *completes* a payment takes the exact remaining USD (`capturedUsd − priorUsd`) rather than a
re-derived fraction, so a sequence of partials cannot strand a rounding crumb on the float.

### The float leg — composed, not invented, and needing no prefunding change

prefunding has no partial reverse and is owned elsewhere, and its `POST /credit` carries no `txnRef` so it is
not idempotent — using it on a money path would double-credit on retry. Instead the float leg composes
prefunding's **existing idempotent** primitives:

1. `reverse(txnRef)` — restores the whole original deduction. Idempotent by design, so a later partial does
   not re-credit it.
2. `reverse(txnRef + "#REFUND-RETAINED@<priorCumulative>")` — gives back what a PRIOR partial re-retained.
3. `deduct(txnRef + "#REFUND-RETAINED@<newCumulative>", retainedUsd)` — re-takes the portion **not** being
   refunded.

Keyed by the cumulative refunded amount, which strictly increases, so every step has a distinct idempotency
key and any replay is a no-op. Net float movement is always `captured − cumulativeRefunded`. The re-deduction
can never overdraw: it always follows a credit of at least as much on the same partner. **A plain full refund
performs step 1 only** — byte-for-byte the single `reverse` that existed before.

Step 2 is the case the first cut of this fix got wrong: it took the "full refund" shortcut whenever the
refund *completed* the payment, and so left the earlier partial's retained slice deducted forever.
`partialThenRemainder_leavesNoRoundingCrumb` and `fullRefundAfterPartial_releasesEverything` both pin it now,
the latter through `planRefund`'s null-amount branch (the natural "refund the rest" API shape, a different
code path).

### The scheme leg is FAIL-CLOSED, and that is the point

The partial amount rides `SchemeClient.CancelRequest.partialAmount`, and `RestSchemeClient` **refuses it with
422 `PARTIAL_REFUND_UNSUPPORTED`, making no HTTP call at all**. The ZeroPay adapter's cancel body is
`{schemeTxnRef, reason}` and its 전문 cancel message carries no amount; SENDMN and NEPAL have no refund
round-trip whatsoever. So the only instruction we could actually send for a partial refund is a **full
cancel** — which would refund the customer more at the scheme than our books recorded, an unreconcilable
silent over-refund. Refusing beats guessing. Registered as **T2-12**.

*Found in passing and fixed:* `SchemeClientRouter.cancelPayment(CancelRequest)` **unpacked the request into
the two-arg form** (`route(id).cancelPayment(req.schemeTxnRef(), req.reason())`), silently discarding any
field added to it — so the partial amount, whose whole purpose is to be refused, would have been dropped and
the refund would have gone out as a full cancel. It now forwards the whole request.

---

## 3. `refundAmountKrw` — and the second reason the claw-back netted nothing

`TransactionClient.StatusPatch` → `RestTransactionClient.StatusPatchRequest` →
transaction-mgmt's `StatusPatchRequest` → `TransactionService.patchStatus` all gained the field, applied
**before** the FSM transition for two reasons that both bite: the `REFUNDED` transition re-stamps the refund
enrichment fields off the aggregate (a value applied afterwards would be overwritten by the null it replaced),
and the emitted event reads the amount off the aggregate (so event and row cannot disagree). Null-skipped, so
an omitted field never reads as "set it to nothing", and cumulative rather than per-refund because the
transaction row is the only refund record that exists today.

**The second cause:** `SettlementBatchJobService.isCrossDateClawbackEligible` rejects any refund leg whose
`originalTxnRef` is **blank** — and `original_payment_txn_ref` was never written either. So even a leg that
somehow carried an amount would have been skipped. A refund is recorded ON the original row today, so the
leg's "original payment" is itself; `patchStatus` now defaults it to the txnRef.

**Non-KRW is left NULL, not mislabelled.** Settlement treats that column as KRW; writing MNT or VND into it
would corrupt the settlement file. `nonKrwRefund_doesNotWriteAForeignAmountIntoTheKrwColumn` pins it.

**Settlement now nets the DELTA, not a boolean.** `foldCrossDateRefunds` used
`existsByTxnRefAndAmountLessThan(refundRef, 0)` as an already-clawed-back gate. Since `refundAmountKrw` is
cumulative, that gate would net the FIRST refund of a transaction and **silently swallow every later
increment**. It now subtracts the new `sumClawedBackByTxnRef` and claws back only the unnetted difference —
which is also still perfectly idempotent across the morning/afternoon windows (a fully netted leg yields a
zero delta and is skipped).

---

## 4. `REFUNDED` now emits `payment.reversed`

`PaymentReversedEvent.fromRefund` + a `to == REFUNDED` branch in `TransactionStateMachine`, mirroring the
existing `REVERSED` branch including its `partnerId` guard. It reuses the **existing**
`PaymentReversedPayload` contract unchanged (lib-api-contracts is outside this pass's scope) and is
distinguished by `source=REFUND` (new constant alongside `SOURCE_OPERATOR`), so an auditor can tell a customer
refund from an operator force-resolve of a stuck transaction.

- `reversedAmount`/`currency` — the **refunded** magnitude, so a partial refund does not look like a full one;
  falls back to the whole collection amount when no amount was recorded.
- `reversedUsd` — the USD credited back for this refund, which is what prefunding's `releaseReversedFloat`
  credits. It must be the refunded USD, not the original deduction.

Because payment-executor restores the float **synchronously before** committing the status, and
`releaseReversedFloat` is idempotent on "a CREDIT for this txnRef already exists" (which `reverse` writes),
the event's float leg is a safe no-op rather than a double credit.

A 2nd+ partial refund updates `refundAmountKrw` but emits **no additional** event: `REFUNDED` is
FSM-terminal, and every consumer of that topic is idempotent per txnRef so a second event would be skipped
anyway. Per-refund downstream notification needs a refund-leg entity — one row per refund — which is a
modelling change, not a patch. Stated in the register rather than hidden.

---

## 5. The refund webhook — and NOT touching the signing model

`notification-webhook` gains `PaymentReversedEventHandler` + `PaymentReversedKafkaConsumer`, registered in the
existing `WebhookKafkaConsumerConfig` and sharing its container factory, DLT error handler and **consumer
group** with the approvals listener. Arming refund webhooks therefore needs no configuration beyond the
bootstrap servers that already gate that class.

It is the structural twin of the approvals handler and adds **a producer of delivery rows and nothing else**.
The existing `WebhookDispatcher` drains PENDING rows regardless of event type, and
`DefaultWebhookTargetResolver` resolves the endpoint and re-derives its own HKDF secret from the `partnerId`
in the payload. **The T5-4 signing model is untouched** — per-endpoint derived secret, verified against the
stored digest, fail-closed.

`RefundWebhookSigningTest` proves that with the real resolver, real `WebhookSigningService` and real
`WebhookSender` (only the outbound socket doubled): the refund verifies under the secret derived for *that*
endpoint, does **not** verify under another partner's, and a resolver holding the wrong root key **refuses to
deliver** rather than signing with something unprovable. `PaymentReversedEventHandlerTest` additionally asserts
the enqueued body still carries `partnerId` — lose it and the row is undeliverable, so it is asserted rather
than assumed. Idempotency is keyed `(webhookId, eventType)`, and `payment.reversed` ≠ `payment.approved`, so a
refund does not collide with the approval delivery for the same transaction while a Kafka redelivery is
skipped.

---

## 6. T2-9 — the diagnosis needed a correction

The claim was "`JpaJournalStore` retries a `rounding_residual_keys` PK insert and the reversal errors". The
defect is real but **it was not reliably an error**: `roundingKeys.save(…)` is Spring Data `save()` on an
entity with an **assigned** `@Id`, so `isNew()` was `id == null` → false → `EntityManager.merge()`. Merge on an
existing row is a SELECT + **UPDATE**. The reversal therefore **silently re-pointed the residual's guard row at
the reversing journal**, after which `findRoundingResidualByReference` returned the reversal and the residual's
own idempotency guard was corrupt. The documented "second concurrent rounding post fails on the PK constraint"
had never actually been true.

Two minimal changes:

1. **`RevenueReversalService` excludes whole rounding-residual journals from the mirror** — per-JOURNAL, not
   per-line, so both legs (`RECEIVABLE_PARTNER` + `REVENUE_ROUNDING`) drop together and the reversing journal
   stays balanced. This matches the service's own documented contract: it backs out the FX-margin /
   service-charge / fee-share **capture**. A residual is a settlement-booking artefact whose `reference` may
   even be a settlement BATCH id, with its own idempotent lifecycle. **No existing posting's accounts or
   amounts change — only which lines a *reversal* mirrors.** The predicate is deliberately identical to the
   store's, so the two cannot drift: a journal the service refuses to mirror is exactly a journal the store
   would try to re-key.
2. **`RoundingResidualKeyEntity` implements `Persistable`** with `isNew()` true until loaded or persisted, so
   the guard is a real INSERT and the primary key is the backstop V006's comment claims.

`RevenueReversalRoundingResidualTest` pins six cases: the reversal succeeds; the guard still resolves to the
residual journal; the capture nets to zero **while the residual survives** (deliberately — it is not the
revenue being reversed); a residual-only transaction has nothing to reverse; idempotency holds with a residual
present; and a genuine concurrent double-residual now trips the PK instead of merging.

---

## 7. DECISION NEEDED — no account was invented (T2-11)

**No account code is missing.** The refund books `DEBIT REVENUE_REVERSAL / CREDIT RECEIVABLE_PARTNER` —
both pre-existing, the exact shape `postReversalJournal` has always used. What is missing is **policy**, in two
parts that are really one decision, registered as **T2-11**:

**(a) A partial refund reverses 100% of the captured revenue.** `RevenueReversalService.reverseCapture(txnRef)`
is amount-blind — it mirrors the whole capture — and `payment.reversed` now fires for `REFUNDED`. So refunding
1% of a payment gives back all of GME's FX margin, service charge and commission split on it. I did **not**
pro-rate it: whether revenue is pro-rated or **retained** on a partial refund is a commercial policy (many PSPs
keep the service fee in full and pro-rate only the FX margin), and choosing changes reported revenue. The
correcting code additionally needs the ORIGINAL amount on the `payment.reversed` contract to know a reversal is
partial at all — `libs/lib-api-contracts`, outside this pass's five services.

**(b) `RECEIVABLE_PARTNER` is relieved twice per reversal.** Two journals fire: payment-executor's gross contra
(`DEBIT REVENUE_REVERSAL / CREDIT RECEIVABLE_PARTNER`) *and* revenue-ledger's capture mirror (which also
CREDITs `RECEIVABLE_PARTNER`). Each is internally balanced so the trial balance still balances, but the
receivable drifts negative by the reversed amount. **Pre-existing** — both postings have coexisted since T2-4
and it applies to the cancel path too — and per the guardrail no existing posting was changed to paper over it.
T2-6 does widen its reach: the LOCAL-partner and wallet refund paths now post the gross contra where they
previously posted nothing / a zero residual, so they join the double-credit rather than escaping it. That is a
deliberate trade: booking *something* real and visible beats booking a zero that reads as booked, and both
artefacts are visible in `GET /v1/journals/trial-balance` and `GET /v1/revenue/journal-reconciliation`.

**What the owner must decide:** which of the two postings is the system of record for a reversal (they should
not both post), and whether a partial refund pro-rates or retains each revenue component.

---

## 8. Still open (not closed here)

1. **T2-12 — no scheme adapter can transmit a partial refund.** In production a partial refund 422s
   `PARTIAL_REFUND_UNSUPPORTED`. The hub side is complete and tested behind that gate; unblocking it is
   scheme-adapter work plus an IDD/certification item. **Sales must not claim partial refunds** (register
   updated).
2. **T2-11** — the finance decision above.
3. **Per-refund events.** A 2nd+ partial refund emits no additional `payment.reversed` (see §4). Needs a
   refund-leg entity.
4. **`applyStatusPatch` still clobbers.** It writes `schemeTxnRef`/`schemeApprovalCode`/`prefundDeductedUsd`/
   `approvedAt` unconditionally, including nulls, so a caller that omits `approvedAt` **wipes** it. The refund
   path avoids this by using the new `StatusPatch.refund` factory (which never restates the original commit's
   values), but the sharp edge remains for other callers. Pre-existing; not touched.
5. **Nothing has been exercised against a running fleet.** Verification is unit/contract level only — no
   server and no docker were started. In particular the `payment.reversed` → prefunding
   `releaseReversedFloat` interaction is reasoned from that method's code, not observed.
6. **`OpsRbacGuard`/BFF and admin-ui do not expose a refund amount.** The operator surfaces still call the
   full-refund shape, so partial refunds are API-only today.

---

## 9. Files

**`services/payment-executor`**
- `domain/PaymentOrchestrator.java` — amount-carrying `refundPayment` overload, `planRefund`,
  `restorePartnerFloat`, `retainedKey`, `RefundPlan`, `USD_SCALE`; `RefundResult` gains the amount fields
  (old 4-arg shape kept). The 7-arg overload now delegates immediately instead of half-doing the work.
- `domain/PartialRefundNotSupportedException.java`, `domain/RefundAmountInvalidException.java` — **new**,
  stable codes, `retryable` where it is genuinely an availability failure.
- `domain/client/TransactionClient.java` — `findRefundBasis` (defaulted, so every fake stays valid) +
  `RefundBasis`; `StatusPatch.refundAmountKrw` + the `StatusPatch.refund` factory.
- `domain/client/SchemeClient.java` — `CancelRequest.partialAmount`/`partialCurrency` + `isPartial()`.
- `client/rest/RestSchemeClient.java` — the fail-closed partial-refund refusal.
- `client/rest/SchemeClientRouter.java` — forwards the whole `CancelRequest` (was unpacking it).
- `client/rest/RestTransactionClient.java` — sends `refundAmountKrw`; implements `findRefundBasis`.
- `client/rest/SchemeFailureRecordPredicate.java` — a refused partial refund is not a breaker fault.
- `web/PaymentController.java`, `web/PaymentExceptionHandler.java`, `web/WalletPayController.java`
  (+ `resolveWalletRefundBasis`/`WalletRefundBasis`), `web/dto/{CancelPaymentRequest,RefundPaymentResponse,
  WalletRefundRequest,WalletRefundResponse}.java`.
- Tests: `domain/RefundMoneyPathTest.java` (**new**, 12 cases), additions to `web/WalletPayControllerTest.java`
  (7 new) and `client/rest/RestSchemeClientTest.java` (2 new).

**`services/transaction-mgmt`**
- `api/dto/StatusPatchRequest.java` (+ back-compat 13-arg ctor), `api/dto/TransactionResponse.java`
  (`refundAmountKrw`), `api/TransactionController.java`, `service/TransactionService.java` (15-arg
  `patchStatus` overload; the 14-arg one delegates), `outbox/PaymentReversedEvent.java` (`fromRefund`,
  `SOURCE_REFUND`), `domain/statemachine/TransactionStateMachine.java` (the `REFUNDED` branch).
- Tests: `service/RefundEventAndAmountTest.java` (**new**, 9 cases).

**`services/revenue-ledger`**
- `domain/ledger/RevenueReversalService.java`, `persistence/RoundingResidualKeyEntity.java`,
  `persistence/JpaJournalStore.java` (comment only — the behaviour change is in the entity).
- Tests: `persistence/RevenueReversalRoundingResidualTest.java` (**new**, 6 cases).

**`services/notification-webhook`**
- `consumer/PaymentReversedEventHandler.java`, `consumer/PaymentReversedKafkaConsumer.java` (**new**),
  `consumer/WebhookKafkaConsumerConfig.java` (one bean).
- Tests: `consumer/PaymentReversedEventHandlerTest.java`, `dispatcher/RefundWebhookSigningTest.java` (**new**).

**`services/settlement-reconciliation`**
- `persistence/SettlementLineRepository.java` (`sumClawedBackByTxnRef`),
  `batch/SettlementBatchJobService.java` (delta-based claw-back).
- Tests: 4 new cases in `batch/SettlementBatchJobServiceTest.java`.

One existing assertion was **narrowed, not weakened**: `WalletPayControllerTest`'s T2-7 case used
`verifyNoInteractions(transactionClient)`; the refund now READS the original before the scheme call, so it
asserts `verify(transactionClient, never()).commitStatus(…)` — a read is not a mutation, and "no status write"
is the exact guarantee T2-7 is about.
