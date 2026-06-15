# sim-merchant — ZeroPay Merchant POS / Counter-Display Simulator

Runs on **:9104**. Depends on **sim-scheme (:9102)** for all QR and payment operations.

## What it is

A standalone Spring Boot simulator that mimics a real ZeroPay merchant terminal and counter display.

- **Counter display** — fetches the merchant's permanent static QR (MPM_STATIC) from the scheme and renders it as a genuine scannable EMVCo QR code. The merchant sticks this printout on their counter.
- **POS / till** — the cashier enters an amount; the app mints a one-time dynamic QR (MPM_DYNAMIC) for the customer to scan.
- **Live payment feed** — polls the scheme's merchant-notification feed every 2 s and shows incoming `APPROVED → CAPTURED → REFUNDED` events in real time.

## How to run

### 1. Start sim-scheme first

```
gradlew -p simulators/sim-scheme bootRun --no-daemon
```

### 2. Start sim-merchant

```
gradlew -p simulators/sim-merchant bootRun --no-daemon
```

Open http://localhost:9104 in a browser.

### Build and test only

```
gradlew -p simulators/sim-merchant build --no-daemon
```

## The full journey

1. **Register** — open View 1, fill in shop name / city / MCC and optional ZeroPay fields (business reg no, merchant type SMALL_BIZ/GENERAL), click *Register Shop*. The app calls `POST /v1/scheme/merchants` on sim-scheme and stores the returned `merchantId`.

2. **Counter QR** — click View 2, then *Fetch / Refresh QR*. The app calls `GET /v1/scheme/merchants/{merchantId}/store-qr` and renders the EMVCo TLV payload as a real scannable QR code (Kazuhiko Arase's qrcode-generator, vendored as `qrcode.min.js`). Click *Copy payload* to paste into sim-wallet.

3. **POS charge** — click View 3, enter an amount, click *Charge*. The app calls `POST /v1/scheme/qr/dynamic` and shows the amount-embedded one-time QR.

4. **Customer pays** — use sim-wallet (:9103) to scan and pay. Authorize + commit flow runs through sim-scheme.

5. **Watch it land** — the right-hand "Payments received" panel polls `GET /v1/scheme/merchants/{merchantId}/payments?since={seq}` every 2 s. Each payment appears as `APPROVED`, then updates to `CAPTURED` in-place. Refunds show as `REFUNDED`.

## Scheme contract paths used

| Operation | Method | Path |
|---|---|---|
| Register merchant | POST | `/v1/scheme/merchants` |
| Store QR | GET | `/v1/scheme/merchants/{merchantId}/store-qr` |
| Dynamic QR (POS charge) | POST | `/v1/scheme/qr/dynamic` |
| Payment feed | GET | `/v1/scheme/merchants/{merchantId}/payments?since={seq}` |

## Backend endpoints

| Method | Path | Description |
|---|---|---|
| POST | `/v1/merchant/shops` | Register shop (proxies to scheme) |
| GET | `/v1/merchant/shops` | List registered shops |
| GET | `/v1/merchant/shops/{merchantId}/store-qr` | Proxy store-QR from scheme |
| POST | `/v1/merchant/shops/{merchantId}/charge` | POS charge — mint dynamic QR |
| GET | `/v1/merchant/shops/{merchantId}/payments?since={seq}` | Proxy payment feed |

When sim-scheme is down all endpoints return **503** with `{"code":"SCHEME_UNAVAILABLE","message":"..."}`.

## QR rendering

`static/qrcode.min.js` — Kazuhiko Arase's public-domain qrcode-generator (vendored, no npm, no build step). The EMVCo TLV payload string is fed to `qrcode(0,'M').addData(payload,'Byte')` and rendered via `createSvgTag` into the page. The result is a genuine ISO 18004 QR code that any ZeroPay-capable wallet can scan.
