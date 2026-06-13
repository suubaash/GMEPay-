# sim-wallet — Unified Wallet App Simulator

Simulates the customer-facing wallet app for both **GMERemit** (domestic KRW) and
**SendMN** (overseas MNT) as a single Spring Boot service.  The two partners share
the same code path; behaviour differs only by the partner profile selected
per-request (`?partner=SENDMN|GMEREMIT`).

**Port:** 9103  
**Depends on (optional):** sim-scheme @ 9102, sim-rate-provider @ 9101  
Both downstream sims are tolerated as down — the wallet returns HTTP 503 with a
friendly JSON error rather than crashing.

---

## Partner Profiles

| | GMERemit | SendMN |
|---|---|---|
| Use-case | UC-01 / UC-03-01 (domestic) | UC-02 / UC-03-02 (overseas) |
| Wallet currency | KRW | MNT |
| Prefunding model | No | Yes |
| FX applied | No | Yes — 2% margin over mid-rate |
| Service fee | KRW 500 | KRW 500 (shown separately) |

---

## Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/v1/wallet/cpm/generate` | Customer requests a CPM token to present at a merchant terminal |
| `POST` | `/v1/wallet/mpm/scan` | Decode a merchant-presented QR payload (preview before paying) |
| `POST` | `/v1/wallet/pay` | Execute payment — CPM or MPM, either partner |
| `GET`  | `/v1/wallet/receipts/{id}` | Retrieve a stored receipt by ID |

---

## Request / Response Shapes

### POST /v1/wallet/cpm/generate
```json
// Request
{ "partner": "GMEREMIT", "customerId": "CUST-001", "amount": "5000", "currency": "KRW" }

// Response
{ "partner": "GMEREMIT", "customerId": "CUST-001", "cpmToken": "CPM-TOKEN-XYZ" }
```

### POST /v1/wallet/mpm/scan
```json
// Request
{ "partner": "GMEREMIT", "qrPayload": "STATIC:CU Convenience:KRW" }

// Response
{ "merchantName": "CU Convenience", "mode": "static", "amount": null, "currency": "KRW" }
```

### POST /v1/wallet/pay
```json
// Request
{
  "partner": "SENDMN",
  "mode": "mpm",
  "qrPayload": "DYNAMIC:GS25:15000:KRW",
  "payAmountKrw": "15000"
}

// Response (SendMN — FX applied)
{
  "id": "uuid",
  "partner": "SENDMN",
  "mode": "mpm",
  "payAmountKrw": "15000",
  "serviceFeeKrw": "500",
  "fxApplied": true,
  "chargeCurrency": "MNT",
  "chargeAmount": "53550",
  "fxRate": "3.570000",
  "schemeTxnRef": "TXN-XXXX",
  "committedAt": "2026-06-13T10:30:00+09:00"
}

// Response (GMERemit — no FX)
{
  "id": "uuid",
  "partner": "GMEREMIT",
  "mode": "mpm",
  "payAmountKrw": "15000",
  "serviceFeeKrw": "500",
  "fxApplied": false,
  "chargeCurrency": "KRW",
  "chargeAmount": "15500",
  "fxRate": null,
  "schemeTxnRef": "TXN-XXXX",
  "committedAt": "2026-06-13T10:30:00+09:00"
}
```

### GET /v1/wallet/receipts/{id}
```json
// 200 OK — same receipt shape as /pay response
// 404 Not Found when id unknown
```

---

## FX Calculation (SendMN)

```
midRate      = GET sim-rate-provider /rates/KRW/MNT
mntCharge    = payAmountKrw × midRate × (1 + 0.02)   [rounded half-up to 0 dp]
effectiveFxRate = mntCharge / payAmountKrw             [6 dp]
```

The KRW 500 service fee is shown separately on the receipt; it is added to the
KRW authorization amount sent to the scheme.

---

## How to Run

```powershell
# From repo root — uses the root Gradle wrapper with -p to target the standalone project
C:/Users/GME/.claude/GMEPay+/code/gradlew -p simulators/sim-wallet bootRun

# Or build a fat JAR first
C:/Users/GME/.claude/GMEPay+/code/gradlew -p simulators/sim-wallet build
java -jar simulators/sim-wallet/build/libs/sim-wallet-0.0.1-SNAPSHOT.jar
```

Web UI: http://localhost:9103/

---

## Example curl

```bash
# CPM — generate customer token
curl -s -X POST http://localhost:9103/v1/wallet/cpm/generate \
  -H 'Content-Type: application/json' \
  -d '{"partner":"GMEREMIT","customerId":"CUST-001","amount":"10000","currency":"KRW"}'

# MPM — scan a static merchant QR
curl -s -X POST http://localhost:9103/v1/wallet/mpm/scan \
  -H 'Content-Type: application/json' \
  -d '{"partner":"GMEREMIT","qrPayload":"STATIC:CU Convenience:KRW"}'

# MPM pay — GMERemit (no FX)
curl -s -X POST http://localhost:9103/v1/wallet/pay \
  -H 'Content-Type: application/json' \
  -d '{"partner":"GMEREMIT","mode":"mpm","qrPayload":"STATIC:CU:KRW","payAmountKrw":"20000"}'

# MPM pay — SendMN (FX 2% margin, MNT charge)
curl -s -X POST http://localhost:9103/v1/wallet/pay \
  -H 'Content-Type: application/json' \
  -d '{"partner":"SENDMN","mode":"mpm","qrPayload":"DYNAMIC:GS25:15000:KRW","payAmountKrw":"15000"}'

# Retrieve receipt
curl -s http://localhost:9103/v1/wallet/receipts/<id-from-pay-response>
```
