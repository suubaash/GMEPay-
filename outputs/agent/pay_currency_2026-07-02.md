> 작업: hub /v1/pay pay-currency / 출처: agent

# Hub `/v1/pay` — pay-currency support

## Goal
Let the wallet `POST /v1/pay` endpoint accept a **pay currency** so a Nepal (Fonepay)
payment is executed in **NPR**, not mis-treated as KRW. Additive; the ZeroPay/KRW path is
identical to before.

## Changes (all in `services/payment-executor/`)

### Request — `WalletPaymentRequest`
- New **optional `currency`** field (ISO-4217). Defaults to **`KRW`** when absent → full
  back-compat. `amountKrw` is now interpreted as the amount **in `currency`** (name kept for
  wire compat).
- Added `payCurrency()` accessor (upper-cases, KRW fallback) + a 3-letter-code validation.

### Routing — `WalletPayController` + `FailoverPaymentRouter`
- Controller threads `req.payCurrency()` into a new 5-arg overload
  `FailoverPaymentRouter.pay(qr, amount, userRef, direction, payCurrency)`.
- Router `resolveCurrency(payCurrency, schemeId)`: the wallet-supplied currency is
  authoritative; falls back to the scheme-derived currency (`currencyFor`) when null. The
  resolved currency drives the `MpmSubmitRequest` sent to the adapter and the
  transaction-mgmt record (OVERSEAS/NPR). A Fonepay scan with `currency=NPR` now submits the
  amount **as NPR** (adapter converts NPR→paisa), not KRW.
- The old **4-arg `pay(...)` overload is retained** (delegates with `payCurrency=null`), so
  existing callers/tests are untouched.

### Response — `WalletPaymentResponse` + `WalletResult`
- Additive `payCurrency` + `payAmount` (`@JsonInclude(NON_NULL)`), populated for a non-KRW
  scheme via the new `WalletResult.approvedInCurrency(...)` factory. The domestic KRW path
  leaves them null → response shape byte-for-byte unchanged.

## ZeroPay / GMEREMIT domestic — UNCHANGED
Currency absent or `KRW` → same routing, amount treated as KRW, **₩500 fixed fee**, no
`payCurrency`/`payAmount` in the response. Confirmed by the existing ZeroPay slice test
(`verifyNoInteractions(failoverPaymentRouter)`) + the `GmeremitPaymentService` path being
untouched.

## No FX here
The hub does NOT do KRW→NPR conversion. The wallet computes the KRW debit; the hub executes
the payment in the given currency. Production corridor FX via rate-fx remains a separate,
documented item.

## Tests — `./gradlew :services:payment-executor:test` GREEN
- `WalletPayControllerTest`: **13** tests, 0 fail. New: Fonepay QR + `currency=NPR` → router
  receives the **NPR** amount + currency, response carries `payCurrency=NPR` / `payAmount`.
  Existing no-currency Fonepay test updated to assert the KRW default flows through.
- `FailoverPaymentRouterTest`: **7** tests, 0 fail (unchanged — exercise the 4-arg overload).

## Remaining / deferred
1. KRW→NPR corridor FX in the hub (rate-fx) — intentionally out of scope.
2. `merchantName` for the Nepal path still resolved adapter-side, not surfaced.
3. `NepalPaymentService` (direct, non-failover) still hardcodes NPR pass-through; the
   failover router is the live path, so this is untouched but could take `payCurrency` later.

Committed to `feat/pay-currency`; CHANGELOG updated.
