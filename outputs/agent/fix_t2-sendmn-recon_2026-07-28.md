> 작업: T2-2 SENDMN reconciliation / 출처: agent

# T2-2 — SENDMN settlement reconciliation (internal three-way tie-out)

**Gap:** SendMN settles in USD against a rate *it* registered with us, while our prefunding deduction
was priced on a different USD basis (live USD/KRW, or the hardcoded 1350 fallback). Nobody compared
those two numbers, so a systematic rate-basis loss could accrue invisibly. Both sides are GME-owned,
so closing it needs **no partner file** — and none was invented.

**Scope built:** SENDMN only. `services/settlement-reconciliation` + a read-only query endpoint on
`services/scheme-adapter-sendmn`. payment-executor, prefunding, ops-partner-bff,
reporting-compliance, apps/**, docker-compose and helm were not touched.

---

## 1. What the three-way tie-out compares

One run per settlement date, per corridor. Everything joins on the **hub partner reference**
(`SENDMN-<uuid>`) — the one key all three legs already share: payment-executor keys the prefunding
deduct on it, transaction-mgmt stores it as `partnerRef`, and the adapter persists it as
`smn_payments.hub_reference` (V002).

| Leg | Source | Read via | Figures used |
|---|---|---|---|
| (a) our transaction record | transaction-mgmt | `GET /v1/transactions?status=APPROVED` filtered to `qrSchemeId=sendmn`, windowed on `approvedAt` | KRW charged (`sendAmount` + ₩500 fee), MNT paid (`targetPayout`), **`prefundingDeductedUsd`** |
| (b) our prefunding movement | prefunding | `GET /v1/prefunding/{code}/deductions` (existing endpoint) | USD actually taken off the partner float |
| (c) what the scheme confirmed | scheme-adapter-sendmn | **new** `GET /internal/scheme/sendmn/settlement/daily?date=` | MNT confirmed, **registered `fx_usd_buy_rate`**, USD `settlement_amount` owed |

Classification per transaction, in precedence order (a missing leg is never *also* reported as a
variance — there would be nothing to subtract):

- `MISSING_INTERNAL` — the scheme confirmed a payment, and/or float moved, with no APPROVED txn of ours
- `MISSING_SCHEME` — our APPROVED txn, no scheme confirmation that date
- `MISSING_PREFUNDING` *(new)* — txn + scheme confirmation, but the float was never charged
- `DISCREPANCY` — like-for-like amounts disagree: txn USD vs prefunding USD, or MNT paid vs MNT confirmed
- `RATE_BASIS_VARIANCE` *(new)* — everything agrees, but the USD collected is short of the USD owed
- `MATCHED` — otherwise

Multiple movements / scheme rows for one reference are **summed, never collapsed**, so a duplicate
deduct or a double-confirmed payment surfaces as an amount discrepancy instead of hiding.

## 2. How rate-basis variance is computed

```
rateBasisVarianceUsd = usdDeducted (leg a/b, hub's USD/KRW basis)
                     − usdOwedScheme (leg c, MNT ÷ SendMN's registered MNT/USD rate)
```

SIGNED, scale 8. Positive = USD retained (the intended ₩500 fee + FX margin). **Negative = GME owes
SendMN more USD than it collected — a real per-payment loss.** A line breaks when the variance is
below `−rate-basis-tolerance-usd` (default 0, i.e. any shortfall breaks).

Two secondary signals fall out of the same arithmetic:

- **`impliedKrwPerUsd` = chargedKrw / usdDeducted** — the rate basis the hub *actually* used,
  back-derived because the rate itself is not persisted on the transaction.
- **`fallbackRateBasis`** — true when that implied basis matches the hub's hardcoded 1350 constant
  (±0.5), i.e. the live rate provider was down and the deduction was priced off an unverified number.
  Counted per day as `fallback_rate_basis_count` (partially addresses CFO#9's fallback blindness).

Worked fixture from the tests: ₩100,000 + ₩500 fee = ₩100,500, priced off the 1350 fallback ⇒
**$74.44444444** collected; 255,000 MNT at the registered 3,280 ⇒ **$77.7439** owed ⇒
**−$3.29945556** shortfall, `fallbackRateBasis=true`, `impliedKrwPerUsd=1350.0000`. The clean fixture
(₩100,500 at a live 1,340 ⇒ $75.00 collected; 239,440 MNT at 3,280 ⇒ $73.00 owed) yields +$2.00 and
MATCHES — retained margin, not a break.

The day's variance is summed **wherever computable, including on lines that broke for another
reason**, so the signed daily figure is complete rather than filtered by break type.

## 3. Breaks and alerting reuse (no parallel subsystem)

- Breaks are written to the **existing `recon_exceptions` table** via the existing
  `ReconExceptionRepository`, so `GET /v1/settlement/exceptions`, resolve and re-run work unchanged.
  Flyway **V010** adds nullable `scheme` + `txn_ref` (the ZeroPay recon is merchant-level; this one is
  transaction-level). `ReconExceptionResponse` gains the same two fields, additively. Amounts on these
  rows are **USD**, documented on both the DTO and the migration.
- Alerts go through the **existing `ReconBreakAlerter`** (`RECON_BREAK`, same severity ladder,
  Kafka-or-log transport). One additive change: a currency-label overload so a USD corridor does not
  render dollars behind a ₩ sign. The 2-arg ZeroPay call site and its tests are untouched.
- `MatchStatus` gained two values; no consumer outside the module references the enum (verified by grep).
- Daily finance summary: new `corridor_recon_summary` (Flyway **V011**), one row per (date, scheme) with
  txn count, ΣKRW charged, ΣMNT paid, ΣUSD deducted, ΣUSD owed, signed daily variance, **cumulative
  signed variance**, fallback count, break count/value, and `scheme_feed_available`.
- Idempotent per date: prior breaks for the run's `batchId` (`SENDMN-3WAY-YYYYMMDD`) are deleted before
  re-insert, the summary row is upserted on its unique key, and the cumulative series is **restated
  oldest-first** so a re-run or a back-dated run leaves no stale later cumulative.
- Driver: `CorridorReconScheduler`, yesterday's date, gated `gmepay.settlement.corridor-recon.enabled`
  (default **false**, same discipline as the ZeroPay recon). Operator surface:
  `POST /v1/settlement/corridor/sendmn/recon?date=`, `GET /v1/settlement/corridor/sendmn/summary?date=`
  (or `from=&to=`).

## 4. Files

**settlement-reconciliation** — `corridor/` (`CorridorThreeWayReconciler`, `CorridorSpec`,
`ThreeWayLine`, `SchemeTransactionRecord`, `PrefundingMovement`, `SchemeSettlementRecord`,
`CorridorReconResult`, `CorridorReconSummaryResponse`, `CorridorReconController`,
`CorridorReconConfig`, `PendingFormatReconFeedParser`); `port/` (`SchemeTransactionPort`,
`PrefundingMovementPort`, `SchemeSettlementPort`, `SchemeReconFeedParser`); `client/`
(`RestSchemeTransactionClient`, `RestPrefundingMovementClient`, `RestSendmnSettlementClient`);
`persistence/` (`CorridorReconSummaryEntity` + repository, `ReconExceptionEntity` +scheme/+txnRef);
`scheduler/CorridorReconScheduler`; `alert/ReconBreakAlerter` (overload); `recon/MatchStatus`
(+2 values); `db/migration/V010`, `V011`; `application.yml`.

**scheme-adapter-sendmn** (read-only) — `api/SmnSettlementQueryController`,
`settlement/SmnSettlementQueryService`, `dto/DailySettlementResponse`, one repository finder.

## 5. Tests

`gradlew :services:settlement-reconciliation:test :services:scheme-adapter-sendmn:test` →
**BUILD SUCCESSFUL, 164 + 55 tests, 0 failures.**

New coverage: `CorridorThreeWayReconcilerTest` (9) — clean day reconciles with no break/alert;
MISSING_SCHEME; MISSING_INTERNAL; MISSING_PREFUNDING (and asserts no variance is claimed);
USD amount mismatch; MNT amount mismatch; rate-basis variance computed exactly with the 1350-fallback
fixture and surfaced on the exception row + summary; cumulative signed variance across two days;
idempotent re-run of the same date. `CorridorReconPersistenceIT` (3) — V010/V011 DDL ↔ JPA agreement,
the sign survives `NUMERIC(20,8)`, uniqueness per (date, scheme). `CorridorReconControllerTest` (5) —
including a **404 for `ninepay`**, so the absence of a 9Pay flow is asserted rather than described.
`RestSendmnSettlementClientTest` (3) — exact decimal parsing, adapter outage degrades to an empty day,
malformed row treated as absent. Adapter side: `SmnSettlementQueryServiceH2SliceTest` (2, KST-day +
APPROVED-only windowing), `SmnSettlementQueryControllerTest` (2).

## 6. What remains externally gated / open

1. **No partner recon file is parsed — external gate O4.** SendMN's API doc is silent on its recon
   file format, so the tie-out compares only GME-owned sources and every summary row carries
   `scheme_feed_available=false`. The plug-in point is `port/SchemeReconFeedParser`;
   `PendingFormatReconFeedParser` **throws** rather than returning a plausible empty parse, so nothing
   can silently read as "the partner confirmed nothing". When the format lands it becomes a fourth leg
   reusing the same `SchemeSettlementRecord` type and the same ops queue.
2. **9Pay not built, by decision.** No hub payout orchestration exists (T4-7 awaiting a product
   decision) ⇒ no hub-side payouts, float movements or transactions to reconcile. The plug-in point is
   a second `CorridorSpec` + ports + one `@Bean`; until then the API 404s instead of faking a clean day.
   Its amount semantics also still depend on O5/O6.
3. **prefunding's read surface is the weak leg.** `GET /v1/prefunding/{code}/deductions` has no date
   filter, clamps at 500 rows and does not expose reversals. Leg (b) is therefore windowed client-side
   and logs a WARN whenever the page comes back full (truncation possible ⇒ a MISSING_PREFUNDING break
   may be an artefact). A date-ranged movement endpoint on prefunding is the follow-up — not done here
   because that service is out of scope for this task.
4. **Detection only, no booking.** The variance is measured, classified and reported; no journal books
   it as an FX/rate-basis loss and no open-position or hedging view is derived (T2-5 / CFO#9).
5. **₩500 and 1350 are still observations**, not shared config: the reconciler reads them from its own
   properties and must be kept in step with payment-executor until those constants move to
   config-registry (CFO#11). A drift shows up as a wrong `impliedKrwPerUsd`, not as silent error.
6. Date semantics for leg (c) use the adapter row's `created_at` (VerifyQr→Confirm complete within
   seconds); if SendMN's eventual statement keys its day differently, the row's `updated_at` is already
   exposed so the window can be re-cut without a schema change.
