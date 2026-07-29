> 작업: SMN QR API digest / 출처: agent

# SMN (SendMN) QR Payment API v1.0.2 — Integration Digest

Source: `D:\GMEPay+\API Documents\SMN_QRPayment_1.0.2.pdf` (17 pages, "QR Payment API — Technical Document for Inbound Remittance", author Urjindelger.Ch, last revised 2025/10/24).

---

## 1. What this is

- **Scheme:** SendMN (SMN) QR Payment — a Mongolian merchant-QR acquiring scheme. SendMN sits in front of **QPay** (the domestic Mongolian QR switch) and the merchant.
- **Country / local currency:** Mongolia / **MNT** (example QR is EMVCo MPM with country `MN`, city `ULAANBAATAR`, GUID `A000000843000101` = QPay).
- **Settlement currency:** **USD** (partner settles to SendMN in USD at SendMN's registered buy rate).
- **Use case:** Inbound remittance-style payment — our wallet user (abroad) scans a Mongolian merchant's QR; we (the "Partner", e.g. GME) pay the merchant via SendMN.
- **Model:** **MPM (Merchant-Presented Mode), Static QR only** (`QR_TYPE = 11`). Customer scans merchant QR in our app (CPM is not in scope of this doc).

## 2. Transport & security

- **Base URL:** NOT stated in the document (environment URLs must be obtained from SendMN). Only relative paths are given.
- **Protocol:** HTTPS + JSON (`Content-Type: application/json`). All methods are **POST**.
- **Auth model (2 layers):**
  1. **Session token:** `POST /api/Authentication` with credential **headers** (`Username`, `AgentCode`, `AuthKey`) → returns a `token` (opaque, base64-ish). Token is **valid for 90 minutes** — cache and refresh.
  2. **Per-request headers** on all subsequent calls (all Mandatory):
     - `Content-Type: application/json`
     - `Authorization: <token>` (raw token; no "Bearer" prefix shown in the example)
     - `Username: <partner username>`
     - `AgentCode: <partner agent code>`
- **IP whitelisting:** enforced — error `S216 Invalid credentials, access denied!` = "Add IP to whitelist".
- **Body encryption (RSA hybrid):** Every business request/response body is a single-field envelope:
  ```json
  { "encryptedData": "<base64>" }
  ```
  - Encrypt the request body with **SendMN's public key**, RSA, **4096-bit**, padding **PKCS#8**, in **hybrid mode** (i.e. AES session key wrapped by RSA — the doc's C# sample, pages 8–9, is an image; get the actual sample code from SendMN).
  - The doc literally says "Decrypt the response using Sendmn's **private key**" — this is contradictory as written (a partner cannot hold SendMN's private key). In practice this almost certainly means: response is encrypted toward the **partner's public key** and decrypted with the **partner's private key**. **Confirm key exchange with SendMN** (open issue).
  - No HMAC / request signature / message-level MAC is specified — confidentiality via RSA-hybrid, authn via token+headers.

## 3. API catalogue

All endpoints are **partner → SendMN** (we call them). There is **no webhook/callback from SendMN to us** for payment results — status is obtained synchronously from Confirm, or by **polling** `PaymentStatus`. The only reverse-direction integration is the FX Rate Registration API (§7 below), which **we must host and SendMN calls**.

### 3.1 Authentication — `POST /api/Authentication`
- **Request:** no body documented; credentials in headers: `Username` (M), `AgentCode` (M), `AuthKey` (M), `Content-Type` (M).
- **Response:** `code` (0 = success), `message`, `detail.token`, `detail.note` ("Token will only be Valid for 90 minutes."), `detail.processId` (GUID).
- Note: this endpoint's response is **plain JSON** (not the encryptedData envelope).

### 3.2 Verify QR — `POST /api/Partner/VerifyQr`
Decodes a scanned merchant QR into payment information (SendMN forwards to QPay to decode).

Request (all inside encryptedData envelope):

| Field | Type | M/O | Notes |
|---|---|---|---|
| `QR_CODE` | String | M | Raw scanned EMVCo MPM **static** QR payload |
| `TX_TOKEN_NO` | String | M | **Partner-generated** transaction identifier (e.g. `SMN202510241041`); reused across VerifyQr → Confirm → PaymentStatus |

Response:

| Field | Notes |
|---|---|
| `RES_CODE` | "0" = success, else error code |
| `RES_MSG` | message |
| `ADDITIVE_MSG` | extra message (nullable) |
| `QR_TYPE` | `11` = MPM Static QR |
| `MERCHANT_ID` | merchant identifier (GUID in example) |
| `MERCHANT_NAME` | business name |
| `MERCHANT_ADDRESS` | nullable |
| `TERMINAL_ID` | POS/terminal id, nullable |
| `TX_TOKEN_NO` | echoed |
| `LOCAL_PAYMENT_AMOUNT` | amount in MNT if QR carries one; **static QR may have no amount → user must key it in** |

### 3.3 Confirm Payment — `POST /api/Partner/Confirm`
Executes the payment to the merchant. This is the money-moving call.

Request — documented mandatory fields:

| Field | Type | M/O | Notes |
|---|---|---|---|
| `TX_TOKEN_NO` | String | M | same as VerifyQr |
| `MERCHANT_ID` | String | M | from VerifyQr response |
| `LOCAL_CUR_CODE` | String | M | `MNT` |
| `LOCAL_PAYMENT_AMOUNT` | String | M | Decimal(18,2), MNT amount |
| `FX_CUR_CODE` | (doc says Decimal; really String) | M | `USD` — **example JSON uses key `FX_CUR_CD`**, spec table says `FX_CUR_CODE` (open issue) |
| `FX_USD_BUY_RATE` | Decimal | M | SendMN's registered USD buy rate (e.g. 3373.00 MNT/USD) |
| `SETTLEMENT_CUR_CODE` | String | M | `USD` |
| `SETTLEMENT_AMOUNT` | String | M | Decimal(18,4) USD = LOCAL_PAYMENT_AMOUNT / FX_USD_BUY_RATE; **server re-checks it** (error 307 mismatch) |
| `PAYMENT_DATETIME` | String | M | **UTC**, format `yyyyMMddHHmmss` (e.g. `20240810025201`) |

Extra keys present in the example but not in the table (send empty string): `FX_USD_SELL_RATE`, `FX_BASIC_RATE`, `FX_TICKER_NO`, `SETTLEMENT_DATE`, `RECONCILE_DATE`. (`FX_TICKER_NO` presumably should reference the registered rate — confirm whether it must be populated; open issue.)

Response: `RES_CODE`, `RES_MSG`, `ADDITIVE_MSG`, `PAYMENT_NO` (SendMN-side API tracking number), `PAYMENT_RECIPT_NO` (SendMN control number, e.g. `GME1453767113` — note the doc misspells "RECIPT"), `MERCHANT_ID`, `MERCHANT_NAME`. Note: `MERCHANT_ID` in the Confirm response example is a numeric id, not the GUID sent — treat as informational only.

### 3.4 Get Payment Status — `POST /api/Partner/PaymentStatus`
- **Request:** `TX_TOKEN_NO` (M) only.
- **Response:** `RES_CODE`, `RES_MSG`, `ADDITIVE_MSG`, `RECONCILE_DATE`, `SETTLEMENT_DATE`, `TX_TOKEN_NO`, `PAYMENT_STATUS`, `PAYMENT_NO`, `PAYMENT_RECIPT_NO`, `MERCHANT_ID`, `MERCHANT_NAME`.
- **PAYMENT_STATUS values:**
  - `Decrypted` — QR received/verified, payment not yet made
  - `Processing` — payment request received, in flight
  - `Approved` — payment confirmed
  - (No explicit `Declined`/`Failed` terminal state documented — open issue: how failures surface in status polling.)

## 4. Flow sequence (from p.3 sequence diagram; actors: App user → Partner → SENDMN → QPAY → Merchant)

0. (Ongoing) SENDMN → **Partner's FX Rate API**: "Request set exchange rate" → Partner saves rate → returns success.
1. App user **scans merchant MPM static QR** in the partner app.
2. Partner → SendMN `VerifyQr` → SendMN → QPay "decode QR" → merchant/amount data returned to partner → shown to user.
3. If the static QR has **no amount**, user enters the amount; user confirms payment (password in-app).
4. Partner computes USD settlement at the registered buy rate and calls `Confirm` → SendMN generates payment → QPay pays and **notifies the merchant** → result returns to SendMN → SendMN sets status → synchronous result back to partner → user sees result.
5. Partner may poll `PaymentStatus` by `TX_TOKEN_NO` (e.g. on timeout/ambiguous Confirm). **No callback to partner exists.**

## 5. Error / status codes

| Code | Meaning | Action |
|---|---|---|
| `0` | Success | — |
| S101 | AuthKey is invalid | |
| S102 | Token is invalid | |
| S103 | AgentCode or Username is invalid | |
| S104 | Token is expired | Regenerate token |
| S201 | Username missing | put in header |
| S202 | AgentCode missing | put in header |
| S216 | Invalid credentials, access denied | **Add IP to whitelist** |
| 101 | Unauthorized — token missing in header | put token in header |
| 203 | QR_CODE missing | |
| 204 | TX_TOKEN_NO missing | |
| 205 | MERCHANT_ID missing | |
| 206 | LOCAL_CUR_CODE missing | |
| 207 | LOCAL_PAYMENT_AMOUNT missing | |
| 208 | FX_USD_BUY_RATE missing | |
| 210 | SETTLEMENT_AMOUNT missing | |
| 303 | TX_TOKEN_NO invalid | |
| 304 | **TX_TOKEN_NO duplicated** | idempotency key collision |
| 305 | MERCHANT_ID invalid | |
| 307 | **SETTLEMENT_AMOUNT does not match** | server-side FX recomputation check |
| 311 | LOCAL_CUR_CODE incorrect format | |
| 316 | PAYMENT_DATETIME incorrect format | |
| 991 | Exception error | contact SendMN IT |
| 997 | encryptedData missing | |
| 998 | Internal server error | contact SendMN IT |
| 999 | **QPay server error** | contact SendMN IT |

## 6. Settlement / reconciliation

- No batch settlement or recon **file/message** is defined in this doc. Recon hooks are field-level only: `RECONCILE_DATE` and `SETTLEMENT_DATE` appear in Confirm request (sent empty) and in the PaymentStatus response ("The reconciliation date for transactions with the Merchant Service provider").
- **FX Rate Registration API (§9, direction: SendMN → Partner):** the partner MUST expose an API (own design) that SendMN calls regularly to register its settled buy rate. Required fields SendMN will send: `FX_TICKER_NO` (unique rate registration id, generated by SendMN), `NOTICE_DATE`, `RATE`, `LOCAL_CUR_CODE`, `SETTLEMENT_CUR_CODE`. This registered rate is what must be used in `Confirm` (`FX_USD_BUY_RATE`), and mismatched `SETTLEMENT_AMOUNT` is rejected (307).

## 7. Idempotency / timeout / retry

- **Idempotency:** `TX_TOKEN_NO` is the de-facto idempotency key — partner-generated, must be unique; replays are rejected with `304 TX_TOKEN_NO is duplicated!`. On ambiguous Confirm outcomes (timeout/998/999), **do not re-Confirm with the same token expecting a retry** — poll `PaymentStatus` first; a duplicate error on retry actually tells you the first attempt reached SendMN.
- **Token lifetime:** 90 minutes; on `S102`/`S104` re-authenticate and replay.
- **Timeouts / retry policy:** none documented (open issue — agree SLAs with SendMN).

## 8. Gotchas & unusual points

1. **Response decryption key contradiction** (p.8): "decrypt the response using Sendmn's private key" — impossible as stated; confirm actual key ownership/exchange (likely partner keypair for responses).
2. **Field-name drift:** spec table `FX_CUR_CODE` vs example `FX_CUR_CD`; `PAYMENT_RECIPT_NO` (sic); Confirm example includes 5 undocumented empty fields; PaymentStatus response example includes `SETTLEMENT_DATE`/`PAYMENT_STATUS` not listed in its own parameter table. Build DTOs from the **examples**, not the tables, and verify against sandbox.
3. **Types are sloppy:** FX fields typed "Decimal" but sent as JSON strings; amounts are strings with fixed scale (18,2 local / 18,4 USD).
4. **`SETTLEMENT_AMOUNT` is validated server-side** (error 307) — rounding of LOCAL/RATE to 4 dp must match SendMN's rounding; get their rounding rule (open issue).
5. **No partner-facing webhook** — result is synchronous + polling only; design a status-poller for in-flight `Processing` transactions.
6. **Static MPM QR may omit the amount** — UI must support user-entered amount (flowchart: "If not include amt: Enter amount").
7. **Auth endpoint uses custom headers for credentials** (`AuthKey` in header, not body) and `Authorization` carries the raw token (no Bearer scheme shown).
8. **IP whitelist** is a hard prerequisite (S216) — supply egress IPs before testing.
9. **We must build an inbound rate-registration endpoint** for SendMN — an integration deliverable easy to miss since it lives outside the four SendMN endpoints.
10. **No base URLs, no sandbox info, no rate-limits, no TLS/cert pinning details** anywhere in the doc — all must come from SendMN onboarding.
11. `PAYMENT_DATETIME` is **UTC** compact format — do not send local (KST/ULAT) time.

## Open issues to resolve with SendMN

- Base URLs (test/prod), sandbox credentials, egress IP whitelisting procedure.
- RSA key exchange: whose keys encrypt which direction; exact hybrid envelope format (AES mode, key wrap layout) — sample C# code pages are images, request source.
- Canonical field name `FX_CUR_CODE` vs `FX_CUR_CD`; whether `FX_TICKER_NO` must be populated in Confirm.
- SETTLEMENT_AMOUNT rounding rule; failure/declined terminal status name; timeout & retry SLAs; settlement/recon file format and cycle.
