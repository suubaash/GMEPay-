# sim-gmeremit — GMERemit Wallet Simulator

A standalone Spring Boot simulator that demonstrates a consumer wallet paying via
QR codes through the GMEPay+ hub — both **domestic ZeroPay (KRW)** and, when a
GMERemit user is "in Nepal", **cross-border Fonepay / NepalPay (NPR)**.

## What it is

Three seeded users (Alice, Bob, Chloe — ₩500,000 each) can scan / paste QR
payloads, preview the merchant, and pay. The wallet always debits the user's **KRW**
balance; the ₩500 service fee is added on top. A green confirmation card shows on
success; a red card on decline.

### Cross-border (Nepal) awareness

The wallet detects the QR network from the payload:

- Contains `fonepay.com` / `nepalpay.com` / the EMVCo Nepal country tag `5802NP`
  → **Nepal (NPR)**. Decoded via the Nepal QR partner sim (`sim-nepal-qr`,
  `POST /qrscan-thirdparty/parse/`), which returns the **real merchant** (name / city)
  and the NPR amount (rupees; `null` for a static QR → the user enters the NPR amount).
- Anything else → **domestic ZeroPay (KRW)**, decoded via the scheme sim (unchanged).

For a Nepal payment the wallet shows the amount in **NPR**, computes the **KRW debit**
= `NPR × krw-per-npr × (1 + margin) + ₩500 fee` using a **sim FX rate**, and displays
_"You pay ≈ ₩X (incl. fee) → merchant receives NPR Y"_. It calls the hub `/v1/pay`
with the **NPR amount** and `currency=NPR`. (Production FX comes from `rate-fx`; the
rate here is a sim-only mock.)

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

> **For Nepal (NPR) payments** you also need the Nepal QR partner sim running:
> ```
> ./gradlew -p simulators/sim-nepal-qr bootRun   # port 9103
> ```
> and the hub's **NEPAL route** must be wired to accept `currency=NPR` on `/v1/pay`
> (being added in parallel). Domestic ZeroPay works without either.

## Configuration

| Key | Default | Meaning |
|-----|---------|---------|
| `gmepay.sim.gmeremit.gmepay-base-url` | `http://localhost:8084` | Payment-executor hub (`POST /v1/pay`) |
| `gmepay.sim.gmeremit.scheme-base-url` | `http://localhost:9102` | ZeroPay scheme sim (domestic QR decode) |
| `gmepay.sim.nepal-qr.base-url` | `http://localhost:9103` | Nepal QR partner sim (Fonepay/NepalPay decode) |
| `gmepay.sim.fx.krw-per-npr` | `1.05` | Sim FX: KRW per 1 NPR (mock; prod uses `rate-fx`) |
| `gmepay.sim.fx.npr-margin` | `0.02` | Sim FX margin added on top of the mid rate (2%) |

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

The request now carries `amount` (in the **merchant currency**) plus a `currency` field.
`amountKrw` is still populated for domestic KRW payments (backward compatibility).

```json
// Request — domestic (KRW)
{ "qrPayload":"<QR>", "amount":"50000", "amountKrw":"50000", "currency":"KRW", "partner":"GMEREMIT", "userRef":"user-001" }

// Request — Nepal (NPR)
{ "qrPayload":"<Fonepay QR>", "amount":"1000", "currency":"NPR", "partner":"GMEREMIT", "userRef":"user-001" }

// Response APPROVED (201) — hub may echo currency/payAmount; the wallet computes the KRW debit
{
  "status":"APPROVED", "schemeTxnRef":"TXN-...", "merchantName":"Sudan Merchant",
  "currency":"NPR", "payAmount":"1000", "payAmountKrw":"1071",
  "feeKrw":"500", "chargedKrw":"1571", "committedAt":"2026-07-02T15:00:00+09:00"
}
```

The wallet's own `/pay` **receipt** carries `currency`, `payAmount` (merchant currency),
`payAmountKrw` (KRW value of the payment leg), `feeKrw`, and `chargedKrw` (KRW debited).
