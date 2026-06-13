# sim-scheme — EMVCo QR Scheme Simulator (KHQR / ZeroPay)

Simulates an external QR payment scheme partner using the EMVCo QR specification,
defaulting to the KHQR (National Bank of Cambodia) profile.

## What it fakes

- EMVCo Merchant-Presented Mode (MPM) QR generation — static and dynamic.
- EMVCo Customer-Presented Mode (CPM) token issuance (60-second TTL).
- Full CRC-16/CCITT payload signing and verification (polynomial 0x1021, init 0xFFFF).
- In-memory merchant registry with two demo merchants seeded on boot.
- Payment FSM: AUTHORIZED → CAPTURED → REFUNDED with illegal-transition enforcement.
- Configurable scheme profile: KHQR (KHR/KH) or ZEROPAY (KRW/KR).

## Profiles

| Profile  | scheme-id | Currency | ISO-4217 numeric | Country | Tag 29 |
|----------|-----------|----------|------------------|---------|--------|
| KHQR     | KHQR      | KHR      | 116              | KH      | 29     |
| ZEROPAY  | ZEROPAY   | KRW      | 410              | KR      | 29     |

Set via `gmepay.sim.scheme.profile` in `application.yml` or as an env var
`GMEPAY_SIM_SCHEME_PROFILE=ZEROPAY`.

## EMVCo tags implemented

| Tag | Name                        | Notes                                    |
|-----|-----------------------------|------------------------------------------|
| 00  | Payload Format Indicator    | Always "01"                              |
| 01  | Point of Initiation         | "11" = STATIC, "12" = DYNAMIC            |
| 29  | Merchant Account Info       | Sub-TLV: tag 00 = GUID, tag 01 = mid     |
| 52  | Merchant Category Code      | MCC string e.g. "5812"                   |
| 53  | Transaction Currency        | Numeric ISO-4217 e.g. "116" / "410"      |
| 54  | Transaction Amount          | DYNAMIC only; BigDecimal plain string     |
| 58  | Country Code                | Alpha-2 e.g. "KH" / "KR"                |
| 59  | Merchant Name               | Max 25 chars per EMVCo spec              |
| 60  | Merchant City               | Max 15 chars per EMVCo spec              |
| 63  | CRC                         | 4-hex-uppercase CRC-16/CCITT             |

## CRC-16/CCITT known vector

Input (ASCII): `000201010212345608036304`
Expected CRC : `6AE6`

(Polynomial 0x1021, initial value 0xFFFF, no input/output reflection, no final XOR.
The CRC is computed over the entire string including the "6304" tag-length prefix.)

The CRC is computed over the entire payload string up to and including the
`6304` tag-length prefix. The 4-hex result is appended as the value of tag 63.

## FSM states

```
AUTHORIZED  --/commit-->  CAPTURED  --/refund-->  REFUNDED
```

Any other transition returns **409 ILLEGAL_TRANSITION**.

## How to run

```bash
# From the repo root — uses the root Gradle wrapper, -p targets the simulator settings
./gradlew -p simulators/sim-scheme bootRun

# Or on Windows (PowerShell)
.\gradlew.bat -p simulators/sim-scheme bootRun
```

Server starts on **http://localhost:9102**.

Run tests only:

```bash
./gradlew -p simulators/sim-scheme test
```

## Endpoint list

| Method | Path                                   | Description                               |
|--------|----------------------------------------|-------------------------------------------|
| POST   | /v1/scheme/merchants                   | Register a merchant                       |
| POST   | /v1/scheme/qr/static                   | Generate a static MPM QR                 |
| POST   | /v1/scheme/qr/dynamic                  | Generate a dynamic MPM QR (amount in QR) |
| POST   | /v1/scheme/cpm/token                   | Issue a CPM token (60 s TTL)             |
| POST   | /v1/scheme/payments/authorize          | Authorize a payment (MPM or CPM)         |
| POST   | /v1/scheme/payments/{authId}/commit    | Capture an authorized payment            |
| POST   | /v1/scheme/payments/{authId}/refund    | Refund a captured payment                |
| GET    | /v1/scheme/payments/{authId}           | Query current payment state              |

Swagger UI: http://localhost:9102/swagger-ui.html

## Example curls

### Register a merchant
```bash
curl -s -X POST http://localhost:9102/v1/scheme/merchants \
  -H 'Content-Type: application/json' \
  -d '{"merchantId":"M-TEST","name":"Test Shop","city":"Phnom Penh","mcc":"5999"}' | jq
```

### Generate static QR
```bash
curl -s -X POST http://localhost:9102/v1/scheme/qr/static \
  -H 'Content-Type: application/json' \
  -d '{"merchantId":"KHQR-M001"}' | jq
```

### Generate dynamic QR
```bash
curl -s -X POST http://localhost:9102/v1/scheme/qr/dynamic \
  -H 'Content-Type: application/json' \
  -d '{"merchantId":"KHQR-M001","amount":"25000.00","currency":"KHR"}' | jq
```

### Issue a CPM token
```bash
curl -s -X POST http://localhost:9102/v1/scheme/cpm/token \
  -H 'Content-Type: application/json' \
  -d '{"customerId":"CUST-001","fundingRef":"WALLET-ABC"}' | jq
```

### Authorize (static MPM)
```bash
QR_PAYLOAD=$(curl -s -X POST http://localhost:9102/v1/scheme/qr/static \
  -H 'Content-Type: application/json' \
  -d '{"merchantId":"KHQR-M001"}' | jq -r '.qrPayload')

curl -s -X POST http://localhost:9102/v1/scheme/payments/authorize \
  -H 'Content-Type: application/json' \
  -d "{\"mode\":\"MPM_STATIC\",\"qrPayload\":\"$QR_PAYLOAD\",\"amount\":\"8000\",\"currency\":\"KHR\",\"payerRef\":\"PAYER-001\"}" | jq
```

### Commit
```bash
curl -s -X POST http://localhost:9102/v1/scheme/payments/{authId}/commit | jq
```

### Refund
```bash
curl -s -X POST http://localhost:9102/v1/scheme/payments/{authId}/refund \
  -H 'Content-Type: application/json' \
  -d '{"amount":"8000"}' | jq
```

### Query state
```bash
curl -s http://localhost:9102/v1/scheme/payments/{authId} | jq
```

## Demo merchants (KHQR profile, seeded on boot)

| merchantId  | name             | city        | mcc  |
|-------------|------------------|-------------|------|
| KHQR-M001   | Angkor Coffee    | Siem Reap   | 5812 |
| KHQR-M002   | Phnom Penh Mart  | Phnom Penh  | 5411 |
