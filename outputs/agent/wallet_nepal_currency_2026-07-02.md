> 작업: wallet Nepal QR + currency / 출처: agent

# GMERemit wallet — cross-border Nepal (NPR) awareness

## Network detection
`QrNetwork.detect(qr)`: `fonepay.com` / `nepalpay.com` / EMVCo tag `5802NP` → NEPAL (NPR);
everything else → DOMESTIC (KRW, ZeroPay, unchanged).

## Nepal decode
`NepalQrClient` calls `sim-nepal-qr` `POST /qrscan-thirdparty/parse/` with `{qs}` →
real `merchantName` / `merchantCity` / `trxCurrency=NPR` / `trxAmount` (rupees, null=static).
`/scan` routes Nepal QRs here and returns `network=FONEPAY`, currency NPR, merchant, city,
mode, `krwPerNpr`, and (dynamic) `estKrwDebit`. Domestic QRs still decode via the scheme sim.
Sim-down fallback = "Unknown Nepal Merchant" (never a fake known merchant).

## Currency / FX display
`FxRates`: effective rate = `krw-per-npr × (1 + margin)`; KRW debit = `NPR × effRate + ₩500`,
rounded to whole KRW. UI shows NPR amount, a "Nepal · Fonepay" tag + city, and a live
"You pay ≈ ₩X (incl. fee) → merchant receives NPR Y". Production FX = rate-fx (noted in code
comment); this is a sim mock. Domestic KRW flow visually unchanged.

## Pay with currency
`WalletService.pay(userId, qr, amount)` where amount is in merchant currency. Insufficient-funds
checked against the computed KRW debit. Hub `/v1/pay` request now sends `amount` (merchant ccy)
+ `currency` (+ `amountKrw` still set for domestic). Wallet debits KRW; receipt + `WalletTransaction`
carry `currency` / `payAmount` / `payAmountKrw` / `chargedKrw`. `/pay` accepts `amount` (legacy
`amountKrw` still honored).

## Config keys
- `gmepay.sim.nepal-qr.base-url` (default `http://localhost:9103`)
- `gmepay.sim.fx.krw-per-npr` (default `1.05`)
- `gmepay.sim.fx.npr-margin` (default `0.02`)

## Test status
`./gradlew -p simulators/sim-gmeremit test` GREEN — 8 tests, 0 failures (5 original + 2 new
Nepal: Fonepay QR → detected Nepal, real merchant + NPR not "Unknown"; Nepal pay sends NPR
amount + currency=NPR, KRW debit 1571 from mock rate; domestic ZeroPay unchanged, never touches
Nepal sim). README + CHANGELOG updated (config keys + runtime note). Committed to
feat/wallet-nepal-currency.

## Remaining (≤3)
1. Hub NEPAL route must accept `currency=NPR` on `/v1/pay` (parallel work) — until then Nepal pay
   depends on the wallet's local KRW math fallback.
2. Nepal payments need `sim-nepal-qr` running on :9103 at runtime.
3. FX is a sim mock (fixed rate + margin); real corridor should source rate-fx.
