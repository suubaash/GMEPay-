# Changelog — sim-gmeremit

## Unreleased — Cross-border Nepal (NPR) awareness

Made the GMERemit wallet simulator cross-border-aware so a user "in Nepal" can scan a
Fonepay QR, see the real Nepal merchant, and pay in NPR (KRW debited).

### Added
- **QR network detection** (`QrNetwork.detect`): `fonepay.com` / `nepalpay.com` /
  `5802NP` → Nepal (NPR); everything else → domestic ZeroPay (KRW).
- **Nepal QR decode** (`NepalQrClient`): decodes Fonepay/NepalPay QRs via `sim-nepal-qr`
  `POST /qrscan-thirdparty/parse/` → real merchant name/city, currency NPR, amount
  (rupees; null for static). No more "Unknown Merchant" for Nepal.
- **Sim FX** (`FxRates`): KRW debit for NPR = `NPR × krw-per-npr × (1 + margin) + ₩500`.
  Production FX is via `rate-fx`; this is a sim-only mock rate.
- Currency-aware UI: NPR/KRW labels, "Nepal · Fonepay" tag + merchant city, live
  "You pay ≈ ₩X (incl. fee) → merchant receives NPR Y" estimate, currency-aware
  receipt + recent-transactions list.
- Two new tests: Fonepay QR → detected Nepal, decoded to a real merchant + NPR (not
  "Unknown"); Nepal pay sends the NPR amount + `currency=NPR` to the hub and debits the
  computed KRW.

### Changed
- Hub `/v1/pay` request now sends `amount` (merchant currency) + `currency`
  (`amountKrw` still populated for domestic KRW). Response and wallet receipt carry
  `currency` / `payAmount` / `payAmountKrw`.
- `WalletTransaction` now records `currency` + merchant-currency `payAmount`.
- `WalletService.pay` / `HubClient.pay` take the merchant-currency amount + currency;
  insufficient-funds is checked against the computed KRW debit.

### New config keys
- `gmepay.sim.nepal-qr.base-url` (default `http://localhost:9103`)
- `gmepay.sim.fx.krw-per-npr` (default `1.05`)
- `gmepay.sim.fx.npr-margin` (default `0.02`)

### Runtime note
Nepal payments require `sim-nepal-qr` on port 9103 and the hub's NEPAL route wired to
accept `currency=NPR`. Domestic ZeroPay is unchanged and needs neither.
