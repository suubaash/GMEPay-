> 작업: SENDMN hub-through E2E / 출처: agent

## What this is

`e2e-tests/src/test/java/com/gme/pay/e2e/SendmnHubThroughE2ETest.java` — the first E2E that drives the SENDMN corridor through the REAL hub money path (not the adapter↔sim pair):

```
wallet ──POST /v1/pay (partner=SENDMN, KRW 50,000)──▶ payment-executor (:18084)
   → sim-rate-provider (:19101)      live KRW→MNT mid ×0.98 offer + USD/KRW
   → prefunding (:18185)             POST /v1/prefunding/2/deduct (USD, atomic)
   → scheme-adapter-sendmn (:18093)  verify-qr → submit-mpm (Confirm)
   →   └─ sim-sendmn (:19106)        plain envelope; settlement vs registered rate (307 check)
config-registry (:18181) anchors the fail-closed operational gate.
```

Fleet booted via the existing `SchemeFleet` harness (boot jars as detached JVMs, H2/in-memory, no Docker); merchant-qr-data deliberately NOT booted — SENDMN merchant truth lives at the scheme, `gmepay.payment.merchant-validation=lenient`. Strict teardown: ports asserted free before boot, kill + port-release verified after (`ALL PORTS FREE` re-checked with netstat post-run; only the Gradle daemon JVM remains).

## Scenarios (all PASS, 2026-07-27)

1. **FX registration** — sim registers MNT/USD 3391.50 (≠ default 3373.00) and pushes it to the adapter's `/partner-hosted/fx-rate`; adapter `fx-rate/latest` echoes it.
2. **Corridor discovery** — `POST /v1/pay/classify` on the sim's seeded QPay QR → `{supported, network=qpay, country=MN, currency=MNT, scheme=SENDMN}`.
3. **Happy path** — 201 APPROVED with FX fields: `feeKrw=500`, `chargedKrw=50500` (exact), `fxRate≈mid×0.98`, `payAmountMnt=amountKrw×fxRate` (±1); hub `execution_attempts` has EXACTLY one APPROVED sendmn row; adapter by-reference probe (restart-fallback leg, optional item 6) resolves the hub reference → APPROVED + txTokenNo; sim shows ONE Approved payment, `paymentNo == receipt.schemeTxnRef`, `localAmount ==` hub's MNT payout, `SETTLEMENT_AMOUNT == payAmountMnt/3391.50` (scale 4, exact); prefunding: exactly ONE USD DEBIT ledger entry keyed by the partner ref, `= chargedKrw/(USD/KRW)` = balance delta.
   (sim-rate-provider random-walks ±0.3%/60s → rate-derived values use 1.5% rel-tolerance; all internal money relations exact.)
4. **Negative** — KRW 200,000 (> remaining ~$63) → 422 DECLINED `INSUFFICIENT_PREFUNDING`; balance byte-identical; still one DEBIT; FAILED attempt recorded with null scheme ref; **adapter by-reference 404 on the declined ref proves ZERO scheme calls** (verify-qr persists the reference before any Confirm, so 404 ⇒ adapter never touched). sim has no payment-list endpoint — the 404 is the stronger equivalent of "payment count unchanged".

## REAL bugs found + fixed (all in payment-executor; full unit suite re-run green)

1. **QPay EMVCo AID unclassified** — sim-sendmn's QRs carry GUID `A000000843000101` (tag26 sub-00); `QrSchemeClassifier` only knew the `qpay`/`mn.qpay`/`sendmn` string markers, so the raw AID passed through, resolved to NO routing candidate → classify `supported=false`, pay `unsupported_qr`. Fix: RID family `a000000843*` → canonical `qpay` (`QrSchemeClassifier.QPAY_RID`; still a placeholder pending SendMN's confirmed sample QR). +1 classifier test.
2. **partner=SENDMN hijacked by the failover router** — `WalletPayController.execute` routed ANY known non-ZeroPay network via `FailoverPaymentRouter` (MNT pass-through, no FX/fee/prefunding) even when the wallet explicitly selected `partner=SENDMN`; with (1) fixed the KRW amount would have been sent as MNT. Pre-Phase-2 this was latent (SENDMN used ZeroPay QRs, which classify zeropay). Fix: explicit `partner=SENDMN` is excluded from `routeViaFailover` → always dispatches to `SendmnPaymentService` (its documented KRW→MNT corridor). +1 controller test (`verifyNoInteractions(failoverPaymentRouter)`). GMEREMIT-partner qpay scans still take the failover pass-through (NEPAL pattern, unchanged).
3. **prefunding deduct wire mismatch** — `RestPrefundingClient` sent `{txnRef, amountUsd}` and bound `{deductedUsd, balanceAfter}`; the real service (`PrefundingController`) expects `{txnRef, amount}` and answers `{partnerId, balance}` → `amount` bound null → EVERY hub deduct failed `400 "amount must be positive"` (surfaced as 500 at /v1/pay). The old unit test had mocked the fantasy shape. Fix: request field `amount`, response `{partnerId, balance}`, `deductedUsd` = requested amount (2xx ⇒ full debit, else 402). Unit test rewritten to pin the REAL wire shape both directions. Only `SendmnPaymentService` calls `deduct()`; reverse/reserve/capture/release/cumulative shapes verified matching.

`gradlew :services:payment-executor:test` → BUILD SUCCESSFUL (full suite incl. new/updated tests).

## Repro

```
cmd //c "cd /d D:\GMEPay+\code && gradlew.bat :e2e-tests:e2eTest --tests com.gme.pay.e2e.SendmnHubThroughE2ETest"
```
(~25 s warm; jars build via task deps, sims build on demand. Result: 4/4 pass.)

## Open / not covered

- Receipt `merchantName` is "Unknown Merchant" (lenient synth) — the SendMN-resolved merchant name is captured at the adapter but not carried back onto the hub receipt; product decision whether to surface it (verify-qr response already has it).
- `deduct` 2xx returns only the balance; if a partial-debit semantic ever appears, `deductedUsd`=requested becomes wrong (documented in code).
- transaction-mgmt / revenue-ledger legs are resilient/fire-and-forget for SENDMN and were not booted (mirrors the NEPAL corridor E2E's assertion scope); hub-side proof uses the `execution_attempts` trail via devtools.
- QPay identifiers (incl. the new `a000000843` RID) remain placeholders until SendMN supplies a confirmed sample QR (plan open issue O1/O2 family).
