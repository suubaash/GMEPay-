# sim-nepal-qr — Nepal QR Partner Simulator (Khalti / Fonepay)

Standalone MOCK of the Nepal QR-scheme partner (Khalti / Fonepay), analogous to how
`simulators/sim-scheme` mocks ZeroPay. It lets GMEPay+'s wallet/scheme flow create a
transaction against the Nepal partner over the real API shapes, and **stores every
request/response** so you can inspect exactly what GMEPay+ sent when creating a txn.

Standalone Gradle build (its own `settings.gradle` + `build.gradle`; JDK 21; Spring Boot
3.3.4 — mirrors sim-scheme). It is **not** part of the root `settings.gradle`.

- **Port: 9103** (sim-scheme uses 9102).
- Base package: `com.gme.sim.nepalqr`.
- Authoritative contracts: `API-DOCS/validate.txt`, `API-DOCS/issuance-extension.txt`.

## What it mocks

Two Khalti contracts:

### (A) QR Validate API — `validate.txt`
Resolve a scanned Nepali QR into receiver details. Read-only / idempotent.

- `POST /api/qr/validate/` (aliases `/api/v2/qr/validate/`, `/api/v1/qr/validate/`)
- Auth header `Authorization: Token <t>` — **any non-empty token accepted**. IP-allowlist
  check is **OFF by default** (config toggle).
- Request `{"qr":"<string>"}`. Response 200 flat JSON keyed by `network`:
  - `khalti` → `{network,name,mobile}`
  - `mobank` → `{network,name,account_number,bank:{swift_code,name}}`
  - merchant (`fonepay`/`nepalpay`/`unionpay`/`smartqr`) →
    `{network,name,merchant_id,amount(int paisa|null=static),currency:"NPR",purpose,extra:{…}}`
- Errors `{error:{code,message,detail}}`: `invalid_qr`(400), `unsupported_qr`(400),
  `receiver_not_found`(422), `receiver_not_eligible`(422). Missing token → 403; bad token → 401.

### (B) Issuance Extension / Scan & Pay — `issuance-extension.txt` (signed API)
Auth headers `Authorization: Key <k>` + `X-KhaltiNonce:<unix ts>`. Body is two params
`{"data":"<base64(json)>","signature":"<base64>"}`. The mock **base64-decodes `data`**;
the signature is **accepted as-is (soft-logged, no real RSA verification)**. Nonce-window
check is a config toggle, **off/lenient by default**.

1. `POST /qrscan-thirdparty/parse/` — data `{qs}`. **Not encrypted** — also accepts a raw
   `{"qs":...}` body. Returns
   `{format,initMethod,merchantInfoExtra,merchantCategoryCode,trxCurrency,trxAmount(rupees),merchantCountry,merchantName,merchantCity,merchantData}`.
   Invalid QR → 400 `{detail:"Invalid QR",error_key:"khalti_error"}`.
2. `POST /qrscan-thirdparty/pay/` — data `{nonce,qs,amount(paisa),mobile,reference,purpose,remarks}`.
   **This creates the txn.** `reference` is globally unique → **dedup** (repeat → 400
   `{reference:"Duplicate reference.<val>",error_key:"validation_error"}`). Success 200
   `{idx,amount,type:"ScanandPay",detail,meta:{balance:{primary,secondary,on_hold}}}`.
   Default outcome APPROVED; overridable per-request via `"outcome":"approve|pending|reject"`
   in the payload, or globally via `gmepay.sim.nepalqr.default-pay-outcome`.
   Empty fields → `validation_error`; reject outcome → `khalti_error`.
3. `POST /qrscan-thirdparty/status/` — data `{nonce,amount,reference}`. HTTP 200
   `{detail,state}` where state ∈ `APPROVED | PENDING | REJECTED | REVERSED | Error`
   (`Error` = reference not found).

## Record store & inspection (the whole point)

Every inbound request to **all** endpoints is persisted with its response as a
`SimRecord`: `{id, endpoint, receivedAt, relevantRequestHeaders, rawRequestBody,
decodedPayload (base64-decoded when applicable), responseStatus, responseBody,
reference, idx, qs, amountPaisa, state}`. In-memory (`NepalQrStore`).

- `GET /sim/nepal-qr/records` — newest-first; optional `?reference=<ref>` filter.
- `GET /sim/nepal-qr/records/{id}` — one record incl. decoded payload.
- `GET /sim/nepal-qr/txns/{reference}` — the transaction created by `/pay/`.

## Config (`application.yml`, prefix `gmepay.sim.nepalqr`)

| Key                   | Default | Meaning                                            |
|-----------------------|---------|----------------------------------------------------|
| `ip-allowlist-enabled`| `false` | Enforce source-IP allowlist on validate API        |
| `allowed-ips`         | `[]`    | IPs accepted when allowlist enabled                |
| `nonce-window-enabled`| `false` | Enforce `ServerTs-100 <= nonce <= ServerTs+200`    |
| `default-pay-outcome` | `APPROVE` | `APPROVE` \| `PENDING` \| `REJECT` for `/pay/`   |

## Build & test

Standalone build from the worktree root (mirrors sim-scheme):

```
./gradlew -p simulators/sim-nepal-qr build --console=plain
```

13 JUnit/MockMvc tests: validate network shapes, parse merchant fields, pay creates
txn + stores record + dedups reference + APPROVED, status per reference, records
inspection incl. decoded payload, Fonepay TLV parsing.

## Pointing GMEPay+ at it

Set the Nepal scheme partner base URL to this sim (default `http://localhost:9103`):

- Validate base → `http://localhost:9103` (paths `/api/qr/validate/` etc.)
- Scan & Pay base → `http://localhost:9103` (paths `/qrscan-thirdparty/{parse,pay,status}/`)

Send `Authorization: Token <anything>` for validate, and
`Authorization: Key <anything>` + `X-KhaltiNonce: <ts>` for scan & pay. Then inspect what
was received at `GET http://localhost:9103/sim/nepal-qr/records`.

## Sample curls

```bash
# Validate a Fonepay QR
curl -s http://localhost:9103/api/qr/validate/ \
  -H 'Authorization: Token demo' -H 'Content-Type: application/json' \
  -d '{"qr":"00020101021126350011fonepay.com071640897200000017835204541253035245802NP5914SudanMerchant6015AathraiTriveni62060702316304d60f"}'

# Parse (raw body — parse is not encrypted)
curl -s http://localhost:9103/qrscan-thirdparty/parse/ \
  -H 'Content-Type: application/json' \
  -d '{"qs":"00020101021126350011fonepay.com...6304d60f"}'

# Pay (signed envelope: base64 the JSON payload into "data")
DATA=$(printf '{"nonce":"1712345678","qs":"00020101...","amount":"1000","mobile":"9800000000","reference":"pay-001","purpose":"ServicePayment","remarks":"NetTV"}' | base64 -w0)
curl -s http://localhost:9103/qrscan-thirdparty/pay/ \
  -H 'Authorization: Key demo' -H 'X-KhaltiNonce: 1712345678' -H 'Content-Type: application/json' \
  -d "{\"data\":\"$DATA\",\"signature\":\"fakesig==\"}"

# Status
curl -s http://localhost:9103/qrscan-thirdparty/status/ \
  -H 'Authorization: Key demo' -H 'Content-Type: application/json' \
  -d "{\"data\":\"$(printf '{"nonce":"1","amount":1000,"reference":"pay-001"}' | base64 -w0)\",\"signature\":\"x\"}"

# Inspect what GMEPay+ sent
curl -s 'http://localhost:9103/sim/nepal-qr/records?reference=pay-001'
curl -s http://localhost:9103/sim/nepal-qr/txns/pay-001
```
