> 작업: 9Pay payout API digest / 출처: agent

# 9Pay Disbursement (Pay-Out to Banks) — Integration Digest

Source: `D:\GMEPay+\API Documents\1. (Pay-Out) Guiding document for Integration of Disbursement Service_9Pay.pdf` (58 pages, "Integration specification payout to banks – Ver 3.13", 9Pay JSC, 2025)

---

## 1. What this service is

- **9Pay Joint Stock Company** (Vietnam) merchant API for **payout/disbursement to Vietnamese bank accounts, ATM cards, and e-wallets** (Momo, ZaloPay, VNPAY, VNPT Money).
- Currency: **VND** (amounts are integer VND; min transfer 2,000 VND; virtual-account deposits min 10,000 VND).
- Actors: PARTNER (us) → 9PAY → BANK. Partner prefunds a balance at 9Pay; transfers are debited from that balance plus fee.
- Partner back-office ("Merchant site"): test `https://stg-console.9pay.mobi`, prod `https://console.9pay.vn`.

## 2. Transport & security

| Item | Value |
|---|---|
| Architecture | RESTful, JSON bodies, HTTP POST (bank-list is GET) |
| Base URL (test) | `https://stg-api-console.9pay.mobi` |
| Base URL (prod) | `https://api-console.9pay.vn` |
| Signature | **RSA 2048-bit**, PKCS1 or PKCS8; digest algorithms supported: SHA1/SHA224/SHA256/SHA384/SHA512 — **partner tells 9Pay which one it uses** |
| Signing model | Sign a **pipe-delimited concatenation of specific fields** (order matters, per-endpoint), base64-encode into `signature` field. Key exchange: partner gives 9Pay its public key; 9Pay gives partner 9Pay's public key to verify responses/IPN |
| Transport security | SSL/HTTPS |
| IP whitelisting | **Both directions.** Partner must send its IPs to 9Pay for API access. Partner should whitelist 9Pay IPs: test `35.221.251.138`; prod `35.240.219.196`, `35.187.225.236` |
| Language header | Add `hl=en` in the request header to get messages/errors in English (default is Vietnamese) |
| Auth | No OAuth/token — identity = `partner_id` + RSA signature + source-IP whitelist |

## 3. API endpoints

All requests carry `request_id` (String 50, unique, recommended format `PartnerID + 9P + YYYYMMDD + UniqueId`), `partner_id` (String 20, issued by 9Pay), and `signature`.

### 3.1 Account authentication (name verify) — `POST {DOMAIN}/service/account/verify`
Request:

| Field | Type | Req | Notes |
|---|---|---|---|
| request_id | Str(50) | Y | unique |
| partner_id | Str(20) | Y | |
| bank_no | Str(20) | Y | 9Pay bank code (section 8 list) |
| account_no | Str(22) | Y | account or card number |
| account_type | Int(1) | Y | 0=bank account, 1=bank card |
| signature | Str(200) | Y | sign `request_id|partner_id|bank_no|account_no|account_type` |

Response (`success` bool; on false: `error.code`/`error.message`/`error.errors`): `data.request_id, partner_id, bank_no, account_no, account_type, account_name` (Str 164 — resolved beneficiary name), `data.signature` signed over `request_id|partner_id|bank_no|account_no|account_type|account_name`.

### 3.2 Money transfer (create payout) — `POST {DOMAIN}/service/transfer`
Notes: **cannot cancel after submission**; check First/Last name order to avoid bank rejection.

Request:

| Field | Type | Req | Notes |
|---|---|---|---|
| request_id | Str(50) | Y | unique — also the idempotency key (error 1062 on reuse) |
| partner_id | Str(20) | Y | |
| bank_no | Str(20) | Y | |
| account_no | Str(22) | Y | |
| account_type | Int(1) | Y | 0=account, 1=card |
| account_name | Str(164) | Y | beneficiary name |
| amount | Int(9) | Y | VND, >= 2000 |
| content | Str(150) | Y | Vietnamese WITHOUT accents, letters+digits only, no special chars (`- _ | '` forbidden). Optionally `{Sender}+{Content}+{Recipient}` |
| refer_va_id | Str(20) | N | successful 9Pay Pay-in txn code (linking pay-in→pay-out) |
| sender_name | Str(50) | N | AML: sender full name / enterprise |
| sender_id | Str(50) | N | AML: sender ID/passport/business license no. |
| recipient_id | Str(50) | N | AML: recipient ID doc |
| transfer_purpose | Str(255) | N | AML: purpose |
| qr_str | Str(255) | N | required when bank_no=VNPAY (QR payload) |
| u_country | Str(50) | N | ISO 3166-1 alpha-2 (VN, US...) |
| sender_uid | Str(100) | N | `PartnerID + Sender_UID`, ALL CAPS, no special chars — drives per-sender payout limits (2071–2073) |
| signature | Str(200) | Y | sign `request_id|partner_id|bank_no|account_no|account_type|account_name|amount|content` |

Response `data`: `request_id, partner_id, transaction_id` (9Pay txn code, Str 20), `bank_no, account_no, account_type, account_name, request_amount` (Int10), `transfer_amount` (Int10 — amount deducted from balance; **"exclude fee" per 4.2, but 4.3 lookup says "including requested amount and fee" — inconsistent, confirm with 9Pay**), `content, status, created_at` (`Y-m-d H:i:s`), `message` (from bank), `fee` (numeric, 9Pay fee), `signature` over `request_id|partner_id|transaction_id|bank_no|account_no|account_type|account_name|request_amount|transfer_amount|status|created_at`.

### 3.3 Transaction lookup — `POST {DOMAIN}/service/transfer/info`
Request: `request_id` (new unique, Req), `partner_id` (Req), `content_type` (Str 32, optional: `TRANSACTION_ID` | `TRANSACTION_REQUEST_ID`), `transaction_id` (Str 50, Req — carries either the 9Pay txn id or your original request_id depending on content_type), `signature` over `request_id|partner_id|transaction_id`.

Response `data`: same shape as transfer response minus message/fee: `request_id, partner_id, transaction_id, bank_no, account_no, account_type, account_name, request_amount, transfer_amount, content, status, created_at`, `signature` over `request_id|partner_id|transaction_id|status|created_at`.

### 3.4 Balance inquiry — `POST {DOMAIN}/service/account/balance`
Request: `request_id`, `partner_id`, `request_time` (Str 20, `yyyy-mm-dd h:i:s` **GMT+7**, Req), `date` (Str 20 `Y-m-d`, optional — closing balance for a date), `signature` over `request_id|partner_id|request_time`.

Response `data`: `request_id, partner_id, response_time`, `balance_info` (array: Title, Balance, Unit VND/USD, Type — "available" = usable, Service {id,name,code}; covers master + sub merchants), `balance_available` (array: unit, value), `signature` over `request_id|partner_id|response_time`.

### 3.5 IPN callback (9Pay → partner `ipn_url`)
9Pay pushes final/updated status to partner's registered ipn_url. Fields:

| Field | Type | Notes |
|---|---|---|
| request_id | Str(50) | your original request id |
| partner_id | Str(20) | |
| trans_id | Str(20) | 9Pay txn code (note: `trans_id` here vs `transaction_id` elsewhere) |
| request_amount | Int(10) | requested amount |
| fee | Int(10) | fee charged |
| transfer_amount | Int(10) | amount taken from partner balance |
| type | Str(20) | `TRANSFER_BANK` = pay-out |
| status | Str(50) | PENDING/PROCESSING/FAIL/SUCCESS |
| created_at | Str(20) | `Y-m-d H:i:s` |
| signature | Str(200) | 9Pay signs `request_id|partner_id|trans_id|request_amount|fee|transfer_amount|type|status|created_at` |
| message | Str(200) | bank approval message |
| approved_at | Str(20) | approval time |
| code | Str(3) | message code (section 7: 000–009) — **NOT part of the signed string** |

### 3.6 Bank list — `GET https://api-console.9pay.vn/transfer-bank/bank-list`
No request params documented (no signature shown). Response: `data.banks` array. (~74 entries incl. banks, foreign branches, digital banks, wallets — see section 8 of PDF.)

### 3.7 Exchange rate — `POST {apiUrl}/service/exchange-rate-v2`
Reference-only rates. Request: `request_id`, `partner_id`, `request_time`, `signature` over `request_id|partner_id|request_time|amount|currency_from|currency_to` — **gotcha: amount/currency_from/currency_to appear in the signature string but are not listed as request fields in the doc; confirm actual body with 9Pay**. Response: `data.rate_info` (array), `signature` over `request_id|partner_id|response_time`.

### 3.8 QR decode — `POST {Domain}/service/v2/decode-qr`
Request: `request_id`, `partner_id`, `str_qr` (Str 1000 — raw QR string), `signature` over `request_id|partner_id` (only two fields). Response `data`: `type` (VNPAY | VIETQR — if VNPAY, skip 4.1 verify), `bank_no` (VNPAY for VNPay), `account_number`, `amount`, `account_name`, `city`, `description`, `crc`, `service` (e.g. QRIBFTTA).

## 4. Disbursement flow sequence

1. Setup: exchange RSA public keys + signature algorithm choice, exchange IP lists, receive `partner_id`, register `ipn_url`, prefund balance.
2. (Optional, recommended) `POST /service/account/verify` → confirm beneficiary `account_name` before paying. (For VIETQR/VNPAY flows, `POST /service/v2/decode-qr` first; VNPAY QRs skip verify.)
3. `POST /service/transfer` → get `transaction_id` + initial `status`.
4. Status machine: `PENDING` (not yet sent to bank) → `PROCESSING` (at bank) → **final** `SUCCESS` or `FAIL`. Only SUCCESS/FAIL are final; **do not auto-finalize on non-final statuses**.
5. 9Pay pushes final status to `ipn_url` (verify 9Pay's RSA signature). Message `code` 000 = success; 009 = bank reversal (payment reversed **after** the fact); 008 = held pending merchant confirmation.
6. If IPN missed or response timed out: poll `POST /service/transfer/info` by request_id or transaction_id.
7. Monitor `POST /service/account/balance` for funding; reconcile via Merchant console + lookup API.

## 5. Error codes (synchronous API, `error.code`)

| Code | Meaning | Action |
|---|---|---|
| 1000 | App server error | contact 9Pay admin |
| 1001 | User (partner_id) not exists | check partner_id |
| 1005 | Request not found | check request_id |
| 1006 | Bank connection temporarily interrupted | retry later |
| 1007 | Signature invalid | check signature |
| 1008 | Params invalid | check params |
| 1017 | Invalid credential | check credentials |
| 1021 | Transaction not exists (lookup) | check transaction_id/request_id |
| 1023 | No bank information found | check bank_no |
| 1024 | Insufficient balance | amount > available balance |
| 1040 | Service not active | ask 9Pay to activate payout |
| 1041 | Bank account invalid (bank says data invalid) | fix beneficiary info |
| 1042 | Bank account invalid (not found at bank) | fix beneficiary info |
| 1060 | Bank account blocked | cannot receive money |
| 1061 | System maintenance | contact 9Pay |
| 1062 | request_id already taken (duplicate) | use a new unique request_id |
| 1063 | No response from bank | retry later |
| 1064 | Beneficiary bank temporarily interrupted | retry later |
| 1065 | Issuing bank declined | customer contacts issuing bank; **balance not deducted** |
| 1066 | Settlement bank declined | case-by-case; **balance not deducted** |
| 1106 | Invalid QRCode (decode-qr) | QR unsupported |
| 2071/2072/2073 | Amount exceeds payout limit per sender_uid per transaction / daily / monthly | rejected |

## 6. Message codes (IPN `code`, Str 3)

| Code | Meaning | Retryable with same data? |
|---|---|---|
| 000 | Successful transaction | — done |
| 001 | Beneficiary account cannot receive payment | No |
| 002 | Beneficiary account not exists/invalid | No |
| 003 | Beneficiary name mismatch | No |
| 004 | Not yet processed by bank | Yes (retry same info) |
| 005 | Account cannot be credited (restricted/locked/closed) | No |
| 006 | Beneficiary account info wrong | No |
| 007 | VA deposit < 10,000 VND | No (raise amount) |
| 008 | Held by 9Pay pending merchant confirmation (fraud/balance suspicion) | resolve via confirmation |
| 009 | **Bank reversal** — payment reversed by bank | acknowledge reversal |

## 7. Balance / funding / settlement / reconciliation

- Balance API (3.4 above) gives current available balance and per-service breakdown; optional `date` param returns closing balance for a day (reconciliation aid).
- Fee model: `fee` returned per transaction; `transfer_amount` = what 9Pay debits (see the fee-inclusion inconsistency flagged in 3.2 vs 3.3).
- No settlement-file / statement API in this doc — reconciliation is balance API + transaction lookup + Merchant console (stg-console.9pay.mobi / console.9pay.vn).
- Codes 1065/1066 explicitly do NOT deduct balance.

## 8. Idempotency, timeout, retry

- **Idempotency**: `request_id` must be globally unique; duplicate → error 1062. Safe-retry pattern: on timeout, re-query `/service/transfer/info` with the SAME request_id (content_type=TRANSACTION_REQUEST_ID) rather than re-submitting.
- **Timeout handling (section 5 of doc)**: on transmission timeout (either direction) you cannot know whether 9Pay received the transfer. (1) Poll lookup by your request_id and update from result; (2) if lookup also keeps timing out (prolonged outage), escalate by email to 9Pay technical team for manual status confirmation.
- **Retry**: only after confirming the transaction does NOT exist at 9Pay (else duplicate payout risk); errors 1006/1063/1064 = retry later; IPN code 004 = retryable with same data.
- **No cancel**: transfers cannot be cancelled once submitted.

## 9. Gotchas / unusual points

1. **Pipe-string signing, per-endpoint field lists** — signature covers only a subset of fields, in fixed order. Notably: transfer request signature EXCLUDES the optional AML fields, qr_str, sender_uid; IPN signature EXCLUDES `message`, `approved_at`, and `code`. Decode-qr signs only `request_id|partner_id` (not str_qr).
2. **Field-name drift**: IPN uses `trans_id`, everywhere else `transaction_id`; decode-qr uses `account_number` vs `account_no`.
3. **transfer_amount contradiction**: transfer response says "exclude fee", lookup says "including requested amount and fee" — clarify before building fee reconciliation.
4. **Exchange-rate signature includes fields (`amount|currency_from|currency_to`) not documented in the request table**; also section numbering typo ("4.9.2").
5. **content field is picky**: unaccented Vietnamese, alphanumerics only; special chars (`- _ | '`) cause rejection. Name order (First/Last) matters to banks.
6. **Bank reversal (IPN code 009) can flip a SUCCESS economically** — must handle post-success reversal in ledger design, even though SUCCESS/FAIL are called "final".
7. **Hold state (code 008)**: 9Pay may hold transactions pending merchant confirmation — build an ops path for confirm/reject.
8. **sender_uid limits**: per-sender transaction/daily/monthly payout limits (2071-2073) apply keyed on sender_uid — send it if you want per-sender limit isolation; format `PARTNERID+UID`, uppercase.
9. **hl=en header** needed for English errors; times are GMT+7 (`Y-m-d H:i:s`), amounts integer VND.
10. **Balance response may show USD units** in balance_info even though payouts are VND.
11. Doc references "transaction lookup (4.4)" in the timeout section but lookup is actually 4.3 — internal cross-reference typo.
12. Test accounts (section 9): success acct `1023020330000`, fail `2034030440000`, business success `66668888`, ATM success `9704060129837294`, ATM fail `9704000000000018` (bank codes BIDV/AGRIBANK/VIETCOMBANK etc.); wallet test on ZALOPAY/MOMO/9PAY.

## Open issues to confirm with 9Pay

- transfer_amount fee-inclusion semantics (3.2 vs 3.3 conflict).
- Exchange-rate-v2 actual request body (amount/currency_from/currency_to present or not).
- Whether bank-list GET requires signature/auth headers (none documented).
- HTTP status-code behavior (doc only defines body-level success/error), and whether IPN requires a specific ACK response from partner (not documented).
- ipn_url registration mechanism (portal vs contract-time config) — not described in the doc.
