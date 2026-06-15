# sim-gmeremit — GMERemit Wallet Simulator

A standalone Spring Boot simulator that demonstrates a consumer wallet paying via
ZeroPay QR codes through the GMEPay+ hub.

## What it is

Three seeded users (Alice, Bob, Chloe — ₩500,000 each) can scan / paste ZeroPay
QR payloads, preview the merchant, and pay. The hub charges ₩500 fee on top of the
payment amount. A green confirmation card shows on success; a red card on decline.

## How to run

Three processes are required. Open three terminals:

**Terminal 1 — ZeroPay scheme simulator (port 9102)**
```
cd C:/Users/GME/.claude/GMEPay+/code
./gradlew -p simulators/sim-scheme bootRun
```

**Terminal 2 — ZeroPay scheme adapter (port 8085)**
```
cd C:/Users/GME/.claude/GMEPay+/code
./gradlew :services:scheme-adapter-zeropay:bootRun
```

**Terminal 3 — Payment executor hub (port 8084, lenient mode)**
```
cd C:/Users/GME/.claude/GMEPay+/code
./gradlew :services:payment-executor:bootRun \
  --args='--gmepay.payment.merchant-validation=lenient --gmepay.scheme-adapter-zeropay.base-url=http://localhost:8085'
```

**Terminal 4 — This wallet simulator (port 9105)**
```
cd C:/Users/GME/.claude/GMEPay+/code
./gradlew -p simulators/sim-gmeremit bootRun
```

Then open **http://localhost:9105** in a browser.

## User journey

1. Pick a user from the dropdown — their live KRW balance is shown.
2. Paste a ZeroPay EMVCo QR payload into the textarea and click **Scan QR**.
   - The sim calls the hub's `/v1/scheme/qr/decode` to get merchant name and mode.
   - For a dynamic QR the embedded amount is shown (read-only).
   - For a static QR an amount input appears.
3. Click **Pay ₩**.
   - If `balance < amount + ₩500` the wallet rejects immediately (no hub call).
   - Otherwise the hub's `POST /v1/pay` is called with the exact contract shape.
4. **Success** → full-screen green confirmation card:
   - Big ✓, "Payment successful"
   - Merchant name, amount paid, ₩500 fee, total charged
   - ZeroPay TXN reference, committed-at timestamp
   - New wallet balance
5. **Declined / insufficient funds** → red card with the reason code.
6. Recent transactions list updates automatically.

## Build / test

```
cd C:/Users/GME/.claude/GMEPay+/code
./gradlew -p simulators/sim-gmeremit build --no-daemon
```

5 tests, 0 failures.

## API endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET  | /v1/gmeremit/users | List all seeded users with balances |
| POST | /v1/gmeremit/scan | Preview a QR payload (merchant name, mode, amount) |
| POST | /v1/gmeremit/users/{userId}/pay | Execute payment; debits balance on APPROVED |
| GET  | /v1/gmeremit/users/{userId}/transactions | User payment history |

## Hub contract (POST /v1/pay)

```json
// Request
{
  "qrPayload":  "<raw EMVCo string>",
  "amountKrw":  "50000",
  "partner":    "GMEREMIT",
  "userRef":    "user-001"
}

// Response APPROVED (201)
{
  "status":       "APPROVED",
  "schemeTxnRef": "TXN-AABB1122...",
  "merchantName": "Coffee Shop",
  "payAmountKrw": "50000",
  "feeKrw":       "500",
  "chargedKrw":   "50500",
  "committedAt":  "2026-06-13T11:23:45+09:00"
}

// Response DECLINED (422)
{
  "status":        "DECLINED",
  "merchantName":  "Coffee Shop",
  "declineReason": "MERCHANT_INACTIVE"
}
```
