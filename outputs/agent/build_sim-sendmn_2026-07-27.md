> 작업: sim-sendmn build / 출처: agent

# sim-sendmn — SendMN (Mongolia QR) simulator build report

**Phase 4 of `Documentation/schemes/QR_SCHEME_ACCOMMODATION_PLAN.md` — DONE.**
Standalone Gradle project at `simulators/sim-sendmn/` (own `settings.gradle`, Spring Boot 3.3.4 / JDK 21, mirrors sim-nepal-qr). Port **9106** (verified free; matches the adapter's `sendmn.base-url` default). Root `settings.gradle` untouched.

## What it implements (plays SendMN's server side of SMN_QRPayment 1.0.2)

| Surface | Notes |
|---|---|
| `POST /api/Authentication` | header creds Username/AgentCode/AuthKey (defaults = adapter placeholders) → plain-JSON `{code:"0", detail:{token, note, processId}}`, 90-min token; S201/S202/S103/S101 errors |
| `POST /api/Partner/VerifyQr` | envelope in/out; 203/204 missing fields, 999 unknown QR; returns QR_TYPE 11, merchant GUID/name/address/terminal, TX echoed, LOCAL_PAYMENT_AMOUNT null for amount-less static QR |
| `POST /api/Partner/Confirm` | 204–210 field checks, 311 non-MNT, 316 bad PAYMENT_DATETIME (yyyyMMddHHmmss), **304** duplicate TX_TOKEN_NO, **307** on rate/settlement mismatch (LOCAL/RATE scale-4 HALF_UP — same rule as adapter FxRateService); returns PAYMENT_NO + PAYMENT_RECIPT_NO (sic) + numeric MERCHANT_ID |
| `POST /api/Partner/PaymentStatus` | 303 unknown token; Decrypted→Processing→Approved, approve-after-N-polls (default 1); sets SETTLEMENT/RECONCILE_DATE on approval |
| Token guard on business calls | raw `Authorization` + Username/AgentCode; RES_CODE **101** missing / **S102** invalid / **S104** expired — inside the envelope (HTTP 200), matching the adapter's re-auth-on-S102/S104 logic |
| Envelope | `{"encryptedData": base64(json)}` both directions — exact mirror of adapter `PlainJsonEnvelopeCodec` |

## Sim console (`/sim/*`, plain JSON, no auth)
- `GET /sim/merchants`, `GET /sim/qr/{merchantId}` — 3 seeded UB merchants with valid-CRC EMVCo MPM QRs (QPay GUID A000000843000101, MN/496); one carries a fixed amount (25000.00 MNT)
- `GET|POST /sim/fx-rate` (option b, default rate 3373.00, new FX_TICKER_NO per set) + `POST /sim/fx-rate/push` and startup auto-push toggle `fx-push.enabled` (option a → adapter's `/partner-hosted/fx-rate`, default :8093)
- `GET|POST /sim/scenario` (+ `/reset`): forceError304, forceError307, neverApprove, delayMillis, approveAfterPolls; `POST /sim/expire-tokens`; `GET /sim/payments/{tx}`

## Tests — GREEN
`cmd /c "cd /d D:\GMEPay+\code\simulators\sim-sendmn && ..\..\gradlew.bat test"` → **BUILD SUCCESSFUL, 15/15** (SendmnSimControllerTest T01–T12: auth + credential errors, verify→confirm→status happy path, Processing→Approved on N polls, 304 dup, 307 mismatch, 101/S102/S104 token paths incl. re-auth recovery, unknown QR/token, force-307 + reset, never-approve, fixed-amount QR; MerchantRegistryTest: QR structure + CRC-16/CCITT-FALSE vector).

## Unresolved / deferred
- RSA hybrid envelope not simulated (plain mode only) — real wire spec pending SendMN (adapter open issue O2).
- Adapter↔sim live E2E (adapter on 8093 → sim on 9106) not run here — sim honors the exact client contract per code review; wire it in the Phase-4 E2E pass.
- Unknown-QR error code (999) is an assumption; the doc defines none.

Plan file Phase 4 `sim-sendmn` checkbox marked [x].
