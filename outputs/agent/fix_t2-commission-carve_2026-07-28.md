> 작업: T2-10 commission carve booking / 출처: agent

# T2-10 — the partner commission carve, booked by substance

Scope: `services/revenue-ledger` only (+ the `GAP_REGISTER.md` T2-10 line). Branch
`feat/exec-gap-closure-2026-07-28`.
Tests: **141 in the module, 0 failures, 0 errors** (was 120); `gradlew testClasses` green fleet-wide.
Schema: **no migration**. `ledger_entries.account` is a plain `VARCHAR(64)` with no CHECK/enum
(`src/main/resources/db/migration/V002__create_ledger_entries.sql:9`), so new account codes need none.
Next free Flyway version in this module remains **V008**; there are no vendor-specific dirs here.

---

## 1. THE MONEY-FLOW FINDING — it is **(a)**, and it is not ambiguous

**GME collects the full commission and then pays the partner its share.** So per the owner's rule
(*"if it's our income then it should be booked a revenue, if this is payout cost of partner then it is
payable expense"*) the carve is a **cost paid to the partner** → **payable + commission expense**.

Four independent pieces of evidence, all in code/spec, none of them inferred from the existing journal:

### 1.1 GME is the one who bills and collects the whole merchant fee
`Documentation/SETTLEMENT_FLOW_SPEC.md:61` (decision D11):

> MDR billed to merchant, out-of-band — for ZeroPay the **scheme returns** the merchant fee (전문 field
> 41)… **GME bills the merchant directly (periodic), then shares a cut with ZeroPay** (configurable).

`Documentation/SETTLEMENT_FLOW_SPEC.md:25` names the merchant relationship as `merchant pays GME`. The
wallet partner has no billing relationship with the merchant at all
(`SETTLEMENT_FLOW_SPEC.md:23` — partner↔GME is prepaid float; D10, line 60 — the *service fee* is GME
charging the partner, the opposite direction). **Nobody but GME collects any part of the merchant fee.**

### 1.2 The carve is computed OFF GME's own cut — it only exists after GME's income is established
The waterfall, stated in the migration that defines its input
(`services/config-registry/src/main/resources/db/migration/V032__merchant_fee_schedule.sql:4-8`):

```
gross_merchant_fee = payout × merchant_fee_pct
net                = gross − (payout × van_fee_pct)
gme                = net × gme_share_pct
partner            = gme × partner_share_pct      ← the carve, a fraction of GME's cut
```

Same in `V031__commission_share.sql:11-16` — *"how **GME's resulting commission** is shared with the
wallet partner… partner_share_pct is the partner's fraction of **GME's cut**; **GME keeps the
remainder**"* — and in the code:
`services/revenue-ledger/src/main/java/com/gme/pay/ledger/fees/CommissionSplitCalculator.java:83-87`
(`partner = floor(gmeGross × partnerSharePct)`, `gmeNet = gmeGross − partner`).

The decisive detail is the **unconfigured default**:
`libs/lib-api-contracts/src/main/java/com/gme/pay/contracts/EffectiveCommissionView.java:31-33` —
*"the caller decides the fallback (e.g. **GME keeps 100% of its cut** when `partnerSharePct` is null)"*.
A baseline of "GME keeps all of it" is only coherent if the money is GME's to begin with. Under (b) an
unconfigured partner share would have to be undefined or blocking, not "GME keeps everything".

### 1.3 The receivable is **NOT** net of the carve — the register's counter-evidence was factually wrong
This was the one piece of evidence pointing at (b), and it does not survive reading the code. The `net`
in `DR RECEIVABLE_PARTNER net` is `netMerchantFeeKrw`, which is **net of the VAN intermediary fee only**:

- `LedgerPostingService.java:278` debits `RECEIVABLE_PARTNER` with `netMerchantFeeKrw`;
- `CommissionSplit.java:25` — `netMerchantFeeKrw = grossMerchantFeeKrw − vanFeeKrw`;
- `LedgerPostingService.java:267` enforces `gmeGross + scheme == net`.

In the worked example the receivable debit is **1800 = gmeGross 1260 + scheme 540**, and the carve
**378 ⊂ 1260**. GME's booked claim therefore *contains* the carve. Had the carve been netted off what GME
bills, the debit would have been 1422. It is 1800.

And the model already shows what a genuinely netted-off deduction looks like: the **VAN fee** is
subtracted *before* the split and **never journalled at all**. The carve is not modelled that way — it is
computed *after* GME's revenue is determined, from that revenue.

### 1.4 Nothing anywhere nets the carve off a bill or pays it direct
- No commission logic exists in `services/prefunding/src/main` or
  `services/settlement-reconciliation/src/main` (grep `commission`, case-insensitive: **zero hits**). No
  settlement or prefunding movement transfers the carve to the partner.
- The only readers of `partner_share_krw` platform-wide are the record itself
  (`CommissionSplitRecordEntity.java:76`), this module's reconciliation report
  (`RevenueJournalReconciliationService.java:212`) and payment-executor's day-close, which quotes that
  report. Nothing consumes it as a deduction.
- `PaymentOrchestrator.postCommissionSplit` (`services/payment-executor/.../PaymentOrchestrator.java:428`)
  only resolves the two shares from config and hands revenue-ledger the inputs — no money moves on it.

### 1.5 What about the scheme leg being (a)-shaped?
It is, and there is no conflict: the two carves are at **different points of the waterfall**. The scheme's
540 is credited **straight to `PAYABLE_SCHEME` and never to revenue** — a pass-through of collected money
that was never GME's income, so there is no expense to book. GME's 1260, by contrast, **is** credited to
`REVENUE_GME_FEE_SHARE` and is genuinely GME's income; paying 378 of it away afterwards is therefore a
cost, not a correction of the revenue figure. Applying the owner's rule to each leg on its own facts gives
exactly the two shapes now in the books.

---

## 2. What was booked

`LedgerPostingService.postPartnerCommissionCarveJournal(reference, partnerShareKrw)`:

```
DEBIT  EXPENSE_PARTNER_COMMISSION  partnerShareKrw  KRW
CREDIT PAYABLE_PARTNER             partnerShareKrw  KRW
```

Standard worked example (payout 100 000 KRW, merchant 2.00%, VAN 0.20%, GME 70%, partner 30%):
**DR 378 / CR 378 KRW**. Whole reference after both legs: DR 1800 + 378 = CR 1260 + 540 + 378 = 2178.

**Gross revenue is unchanged** — it was always right. `REVENUE_GME_FEE_SHARE` keeps its 1260, and GME's
**retained** commission now reads off the books as `1260 − 378 = 882 = gmeNetShareKrw`. The T2-4 note that
gross "overstates retained commission" was a *presentation* gap, not an overstatement of revenue; it is
closed by the expense line, not by reducing revenue.

### ⚠️ TWO new account codes, not one — say so plainly
The brief budgeted **one** new code (the expense). The ruling forces a second:

| Code | Why it had to be new |
|---|---|
| `EXPENSE_PARTNER_COMMISSION` | The anticipated one. No `EXPENSE_*` account existed in this module. |
| `PAYABLE_PARTNER` | The owner said "**payable** expense". `PAYABLE_SCHEME` is a **different counterparty** (the T2-4 note already rejected reusing it, and it would misstate who is owed). Crediting the existing `RECEIVABLE_PARTNER` would net a liability into an asset — it would understate both the receivable and the liability, and it is not a payable. |

Naming follows the existing taxonomy `<TYPE>_<QUALIFIER>` (`REVENUE_FX_MARGIN`, `RECEIVABLE_PARTNER`,
`PAYABLE_SCHEME`); `PAYABLE_PARTNER` is the exact mirror of `PAYABLE_SCHEME` for the other counterparty.
Both are declared in `persistence/ChartOfAccounts.java` with the reasoning inline. If the finance owner
would rather net the carve against the partner's running account than carry a distinct payable, **only the
credit account changes** — one line in `postPartnerCommissionCarveJournal`.

### Idempotency, atomicity, back-fill
- Posted by `CommissionSplitRecordService.journal(...)`, inside the **same `@Transactional`** as the
  `commission_splits` insert — record and carve journal commit or roll back together.
- Idempotent on `txnRef` via a **CREDIT to `PAYABLE_PARTNER`**: only an original carve credits that
  account (a reversal mirrors the sides and *debits* it), the same rule the other two capture-side posts
  use. So the probe survives a later reversal and a replay never double-books.
- Deliberately a **separate journal** from the scheme leg, not extra lines on it. A split journalled
  before T2-10 already carries the `REVENUE_GME_FEE_SHARE` credit that satisfies the scheme leg's probe —
  a shared probe would have left every pre-T2-10 row **permanently** unbooked. Separate probes mean any
  replay back-fills the carve alone.
- A zero carve (`partner_share_pct = 0`, i.e. GME keeps everything) posts **nothing** — no nominal zero
  journal, consistent with the other T2-4 posts. A negative carve is refused.

---

## 3. The self-check now reflects reality in BOTH directions

- `unmappedComponents[PARTNER_COMMISSION_SHARE]` no longer reports the period's whole recorded carve. It
  reports the carve that is **actually still unbooked**: splits carrying a carve with no
  `PAYABLE_PARTNER` credit (pre-T2-10 rows not yet replayed), with an exact count and its own reason /
  next-action text. It therefore clears **only because the money is on the books**, never because a
  decision was taken. The amount is **summed over the exception rows**, not derived as
  recorded-minus-journalled — otherwise a reversal's mirroring DEBIT of `PAYABLE_PARTNER` could make a
  properly booked carve reappear as "unmapped".
- New tie-out stream **`PARTNER_COMMISSION_CARVE`** (`PAYABLE_PARTNER`, KRW): recorded
  `partner_share_krw` vs what the journal credited for the same reference set. Only emitted when the
  period has a carve, so a 100%-GME period gets no phantom row.
- `clean` therefore goes true for a fully booked period and stays false, with the amount and count, while
  any recorded carve is genuinely missing. Both are pinned by tests from both sides.

The day-close report in `payment-executor` quotes `unmappedComponents` verbatim and never recomputes it,
so its `UNRESOLVED_DECISION` for T2-10 **clears itself at runtime**. The JSON contract is unchanged and
its tests use canned fixtures, so no cross-module edit was needed — but see §5(c).

---

## 4. T2-11 interaction (T2-11 stays open; nothing of it was changed)

`RevenueReversalService.reverseCapture` mirrors **every** non-rounding line for the txnRef
(`RevenueReversalService.java:110-127`), so the carve journal is unwound with everything else:
`DEBIT PAYABLE_PARTNER / CREDIT EXPENSE_PARTNER_COMMISSION`, netting both new accounts to zero. It is
balanced on its own two lines, so the mirror stays balanced. Neither new line is a `REVENUE_*` DEBIT, so
it neither trips nor is tripped by that service's already-reversed probe
(`RevenueReversalService.java:146-152`). Pinned by `reversalUnwindsTheCarveToZero`.

Precisely how they interact:

1. **T2-11(a), partial-refund pro-rating — the carve INHERITS it, adding no new question.** The mirror is
   not pro-rated, so a partial refund unwinds the *whole* carve exactly as it unwinds the *whole* revenue.
   That is the same defect T2-11 already describes, now with one more pair of lines in it. When T2-11
   chooses a pro-rating factor `f`, it must be applied to this leg too — and because the carve is a fixed
   fraction of `gmeGross`, scaling both by the same `f` preserves `partner + gmeNet == gmeGross`.
2. **T2-11(b), the `RECEIVABLE_PARTNER` double relief — untouched.** This change posts **no**
   `RECEIVABLE_PARTNER` line on either side, so the double relief is neither worsened nor fixed.

---

## 5. Still open (not mine / other owners)

a. **Nothing ever pays the payable.** `PAYABLE_PARTNER` accrues per transaction and no settlement,
   prefunding or billing path discharges it — because the MDR billing/receivables subsystem does not
   exist (CFO#5 / T2-1). The accrual is now correct; the **cash leg is unbuilt**. Same pre-existing
   situation as `PAYABLE_SCHEME`.
b. **The carve is still computed from a config-resolved merchant-fee rate**, not ZeroPay's actual field 41
   — unchanged T2-4 follow-up on the payment-executor / scheme-contract side.
c. **Retire the T2-10 branch of `DayCloseReportService`'s unresolved-decision list** (payment-executor,
   other owner). It clears itself from the data, but the code still special-cases T2-10 as an open
   decision; T2-11's branch must stay.
d. `REVENUE_REVERSAL` is still missing from `ChartOfAccounts` (pre-existing drift — `LedgerPostingService`
   defines it locally). Not touched: not a T2-10 concern, and adding it changes nothing behaviourally.
e. **Not exercised against a running fleet** — unit / slice level only (real H2 + Flyway via
   `@DataJpaTest`, no mocks for the ledger writes). No servers, no Docker.

---

## 6. Files

Modified (`services/revenue-ledger`):
- `domain/ledger/LedgerPostingService.java` — new `postPartnerCommissionCarveJournal`, the two account
  code constants, generalised the shared idempotency probe's doc. **No existing posting's accounts or
  amounts changed.**
- `persistence/ChartOfAccounts.java` — `EXPENSE_PARTNER_COMMISSION`, `PAYABLE_PARTNER`.
- `fees/CommissionSplitRecordService.java` — journals the partner leg in the same transaction.
- `persistence/CommissionSplitRecordRepository.java` — three additive finders for the unbooked carve
  (list capped, exact count, exact sum).
- `persistence/RevenueJournalReconciliationService.java` — carve tie-out + truthful unmapped component.
- `web/RevenueJournalReconciliationView.java` — javadoc only (contract shape unchanged).
- `CHANGELOG.md`.

Tests: `fees/CommissionSplitJournalTest` (carve booked with exact amounts; revenue not reduced; two
journals; zero carve posts nothing; back-fill for a scheme-leg-only row; negative refused; reversal nets
to zero and stays idempotent), `persistence/RevenueJournalReconciliationTest` (both legs tie; unmapped
clears only when booked; an unbooked row holds `clean` false and reports only its own amount; zero share
is not an exception), `persistence/TrialBalanceServiceTest` (trial balance still sums to zero with the two
new accounts, and they carry the movement), `web/TrialBalanceAndReconciliationApiTest` (fixture text only).

Outside the module: `Documentation/GAP_REGISTER.md` — the T2-10 line and its "OWNER DECISIONS TAKEN"
cross-reference only.
