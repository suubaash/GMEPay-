> 작업: harden revenue reversing journal / 출처: agent

# revenue-ledger — reversing journal on `payment.reversed`

## What changed (additive, `services/revenue-ledger/` only)

New async consumer on `gmepay.payment.reversed` consuming the canonical `PaymentReversedPayload`
(lib-api-contracts). It books a **reversing journal** that backs out the original revenue capture.

- `consumer/PaymentReversedEventHandler.java` — deserialize + validate the event, delegate to the
  reversal service, ack.
- `consumer/PaymentReversedKafkaConsumer.java` — `@KafkaListener` on `gmepay.payment.reversed`,
  MANUAL ack, reuses the existing container factory + DLT error handler.
- `consumer/RevenueLedgerKafkaConsumerConfig.java` — new bean, still gated on
  `spring.kafka.bootstrap-servers` (broker-free local default unchanged).
- `domain/ledger/RevenueReversalService.java` — the reversing-journal logic.

## Reversing-journal logic + idempotency

`reverseCapture(txnRef)`:
1. `JournalStore.findByReference(txnRef)` → all original capture journals (FX-margin + service-charge
   capture, fee-share split).
2. Post ONE balanced journal mirroring every original line with the DEBIT/CREDIT side flipped, same
   account/amount/currency/reference. Flipping every line of a set of balanced journals is itself
   balanced, so the **net across capture + reversal is exactly zero on every account and currency** —
   margin, service charge and fee-share all backed out.
3. **Idempotent on txnRef**: a reversing line is the only DEBIT ever posted to a `REVENUE_*` income
   account (capture only CREDITs them). If one already exists for the txnRef, return no-op — a repeat
   `payment.reversed` does not double-reverse.
4. **No original capture** → `Optional.empty()`, safe no-op: logged, acked, never dead-lettered.

## Operator-COMPLETED confirmation

Verified — **no new code needed**. transaction-mgmt emits the normal `payment.approved` for an operator
COMPLETED; `PaymentApprovedEventHandler.handle` gates only on `eventType == "payment.approved"` (it does
not inspect source/status), and books capture via the existing `RevenueCaptureService` path unchanged.

## Test status

`./gradlew :services:revenue-ledger:test` — **BUILD SUCCESSFUL** (green). New broker-free
`PaymentReversedEventHandlerTest` (+8) against the real `InMemoryJournalStore`: reversal nets to zero on
every account/currency; second reversal = idempotent no-op (no second contra); unknown txnRef = safe
no-op (nothing posted); record-key txnRef fallback; poison cases.

## Remaining (≤3)

1. `payment.reversed` idempotency detection is app-level (DEBIT-to-`REVENUE_*` scan); unlike the
   rounding-residual path it has no DB unique-key backstop against a concurrent double-reverse race.
2. Reversal is booked at the originally-captured amounts; a partial reversal (`reversedAmount` <
   original) is not modeled — full contra only. Revisit if partial reversals become a requirement.
3. `PaymentReversedPayload.partnerId/schemeId/reversedUsd` are consumed but unused by the ledger
   contra (amounts come from the original journals); wire into reporting if a reversal audit row is
   later wanted alongside the journal.
