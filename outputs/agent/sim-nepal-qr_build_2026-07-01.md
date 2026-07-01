> 작업: sim-nepal-qr partner simulator / 출처: agent

# sim-nepal-qr — Nepal QR Partner Simulator (Khalti / Fonepay)

## Module path
`simulators/sim-nepal-qr/` — standalone Gradle build (own `settings.gradle` + `build.gradle`,
JDK 21, Spring Boot 3.3.4; **not** in the root `settings.gradle`), mirroring `simulators/sim-scheme`.
Base package `com.gme.sim.nepalqr`. Contracts kept in `API-DOCS/{validate,issuance-extension}.txt`.

## Port
**9103** (sim-scheme = 9102), set in `src/main/resources/application.yml`.

## Endpoints implemented

### (A) QR Validate API — `validate.txt` (`ValidateController`)
- `POST /api/qr/validate/` + aliases `/api/v2/qr/validate/`, `/api/v1/qr/validate/`.
- Auth `Authorization: Token <t>` (any non-empty token). Missing → 403, malformed → 401.
  IP-allowlist check config-toggle, OFF by default.
- Request `{"qr":"<string>"}`. Response 200 flat JSON keyed by `network`:
  - khalti `{network,name,mobile}`
  - mobank `{network,name,account_number,bank:{swift_code,name}}`
  - fonepay/nepalpay/unionpay/smartqr `{network,name,merchant_id,amount(int paisa|null),currency:"NPR",purpose,extra:{per-network keys}}`
- Errors `{error:{code,message,detail}}`: invalid_qr(400), unsupported_qr(400),
  receiver_not_found(422), receiver_not_eligible(422) supported.

### (B) Issuance Extension / Scan & Pay — `issuance-extension.txt` (`IssuanceExtensionController`)
Signed API: `Authorization: Key <k>` + `X-KhaltiNonce`; body `{data:base64(json),signature}`.
Mock base64-decodes `data`; signature accepted as-is (soft-log, no RSA verify). Nonce-window
check config-toggle, OFF/lenient by default.
- `POST /qrscan-thirdparty/parse/` — data `{qs}`; **also accepts raw `{"qs":...}` body**
  (parse is not encrypted). Returns
  `{format,initMethod,merchantInfoExtra,merchantCategoryCode,trxCurrency,trxAmount(rupees),merchantCountry,merchantName,merchantCity,merchantData}`.
  Invalid QR → 400 `{detail:"Invalid QR",error_key:"khalti_error"}`.
- `POST /qrscan-thirdparty/pay/` — **CREATES THE TXN**. data
  `{nonce,qs,amount(paisa),mobile,reference,purpose,remarks}`. `reference` globally unique →
  **dedup** (repeat → 400 `{reference:"Duplicate reference.<val>",error_key:"validation_error"}`).
  Success 200 `{idx,amount,type:"ScanandPay",detail,meta:{balance:{primary,secondary,on_hold}}}`.
  Empty fields → validation_error. Default outcome APPROVED; overridable per-request
  (`"outcome":"approve|pending|reject"`) or globally (`default-pay-outcome`); reject → khalti_error.
- `POST /qrscan-thirdparty/status/` — data `{nonce,amount,reference}`. HTTP 200 `{detail,state}`,
  state ∈ APPROVED | PENDING | REJECTED | REVERSED | Error(not found).

## Record store + inspection endpoints (`InspectionController`, `NepalQrStore`)
Every inbound request to ALL endpoints persisted as a `SimRecord`:
`{id,endpoint,receivedAt,relevantRequestHeaders,rawRequestBody,decodedPayload,responseStatus,responseBody,reference,idx,qs,amountPaisa,state}` (in-memory).
- `GET /sim/nepal-qr/records` — newest-first, optional `?reference=` filter.
- `GET /sim/nepal-qr/records/{id}`.
- `GET /sim/nepal-qr/txns/{reference}` — the created txn.

## QR decode
`QrParser` — best-effort EMVCo/Fonepay TLV parse (merchant id/name/city/amount/mcc/currency),
recognizes the fonepay QR format, with a resync heuristic for the doc sample's off-by-one
text-field lengths; falls back to mock values for unread tags.

## Build / test status
GREEN. `./gradlew -p simulators/sim-nepal-qr build --console=plain` → BUILD SUCCESSFUL,
**13 tests pass** (validate network shapes, parse merchant fields, pay creates txn + stores
record + dedup + APPROVED, status per reference, records inspection incl. decoded payload,
Fonepay TLV parsing).

## How GMEPay+ points at it
Set the Nepal scheme partner base URL to `http://localhost:9103`:
- Validate: `POST /api/qr/validate/` with `Authorization: Token <any>`.
- Scan & Pay: `POST /qrscan-thirdparty/{parse,pay,status}/` with `Authorization: Key <any>` +
  `X-KhaltiNonce: <ts>`; pay/status bodies are `{data:base64(json),signature}`.
Then inspect received traffic at `GET /sim/nepal-qr/records`.

## Remaining (≤3)
1. Real RSA-2048/PKCS#1 signature verification is intentionally NOT done (sim accepts any
   signature; soft-log only) — enable if a signature-negative test is later required.
2. `receiver_not_found` / `receiver_not_eligible` (422) exist in code paths but are not yet
   driven by a seeded eligibility registry (validate currently resolves any well-formed QR).
3. Live wiring: GMEPay+'s Nepal scheme adapter base-url must be pointed at :9103 (config only;
   not changed here per "create only under simulators/sim-nepal-qr/").
