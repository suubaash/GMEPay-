# Merchant Feed Contract — sim-scheme (:9102)

> **FROZEN** — the merchant-app lane depends on this verbatim.
> Do not change field names, types, or HTTP status codes without a breaking-change review.

---

## 1. Store-QR Endpoint

### `GET /v1/scheme/merchants/{merchantId}/store-qr`

Returns the pre-printed counter QR for a merchant (MPM Static, no amount embedded).

#### Path Parameters

| Parameter    | Type   | Required | Description         |
|--------------|--------|----------|---------------------|
| `merchantId` | string | yes      | Registered merchant id |

#### Success Response — `200 OK`

```json
{
  "merchantId":   "ZP-M001",
  "merchantName": "Seoul Noodle House",
  "mode":         "MPM_STATIC",
  "qrPayload":    "<EMVCo TLV string with CRC-16/CCITT>",
  "schemeId":     "ZEROPAY",
  "currency":     "KRW"
}
```

| Field          | JSON type | Notes                                              |
|----------------|-----------|----------------------------------------------------|
| `merchantId`   | string    | Echoes path param                                  |
| `merchantName` | string    | Merchant display name                              |
| `mode`         | string    | Always `"MPM_STATIC"` for this endpoint            |
| `qrPayload`    | string    | Full EMVCo QR string, CRC-16/CCITT appended        |
| `schemeId`     | string    | Active scheme: `"ZEROPAY"` or `"KHQR"`             |
| `currency`     | string    | ISO 4217 — `"KRW"` for ZEROPAY, `"KHR"` for KHQR  |

#### Error Response — `404 Not Found`

Empty body. Returned when `merchantId` is not registered.

---

## 2. Merchant Payment-Notification Feed

### `GET /v1/scheme/merchants/{merchantId}/payments?since={seq}`

Returns payment events for a merchant, filtered by sequence cursor.
**Always returns `200 OK`** — never `404`, even for an unknown merchantId (returns empty list).

#### Path Parameters

| Parameter    | Type   | Required | Description         |
|--------------|--------|----------|---------------------|
| `merchantId` | string | yes      | Registered merchant id |

#### Query Parameters

| Parameter | Type | Required | Default | Description                                    |
|-----------|------|----------|---------|------------------------------------------------|
| `since`   | long | no       | `0`     | Return only events with `seq > since` (cursor) |

#### Success Response — `200 OK`

```json
{
  "merchantId": "ZP-M001",
  "events": [
    {
      "seq":          1,
      "authId":       "AUTH-3F7A2B1C9D4E",
      "schemeTxnRef": null,
      "status":       "APPROVED",
      "amount":       "10000",
      "currency":     "KRW",
      "payerRef":     "WALLET-ABC123",
      "at":           "2026-06-13T14:23:01+09:00"
    },
    {
      "seq":          2,
      "authId":       "AUTH-3F7A2B1C9D4E",
      "schemeTxnRef": "TXN-A1B2C3D4E5",
      "status":       "CAPTURED",
      "amount":       "10000",
      "currency":     "KRW",
      "payerRef":     "WALLET-ABC123",
      "at":           "2026-06-13T14:23:05+09:00"
    }
  ],
  "latestSeq": 2
}
```

| Field        | JSON type | Notes                                                                 |
|--------------|-----------|-----------------------------------------------------------------------|
| `merchantId` | string    | Echoes path param                                                     |
| `events`     | array     | Ordered by `seq` ascending; empty array `[]` when no events          |
| `latestSeq`  | long      | Highest seq stored for this merchant; `0` when no events             |

#### Event Object

| Field          | JSON type        | Notes                                                         |
|----------------|------------------|---------------------------------------------------------------|
| `seq`          | long (integer)   | Monotonically increasing per merchant, starting at 1          |
| `authId`       | string           | Scheme authorization id, format `AUTH-<12 HEX chars>`        |
| `schemeTxnRef` | string or `null` | Scheme transaction ref, format `TXN-<10 HEX chars>`; `null` for APPROVED events |
| `status`       | string (enum)    | One of: `"APPROVED"`, `"CAPTURED"`, `"REFUNDED"`             |
| `amount`       | **string**       | BigDecimal serialized as JSON string, e.g. `"10000"` or `"9999.50"` |
| `currency`     | string           | ISO 4217, e.g. `"KRW"`                                        |
| `payerRef`     | string           | Opaque payer identifier provided by the wallet at authorization |
| `at`           | string           | KST ISO-8601 offset datetime, e.g. `"2026-06-13T14:23:01+09:00"` |

#### Event Lifecycle (per payment)

```
authorize()  → appends status="APPROVED"  (schemeTxnRef=null)
commit()     → appends status="CAPTURED"  (schemeTxnRef="TXN-...")
refund()     → appends status="REFUNDED"  (schemeTxnRef carries TXN of original capture)
```

Each lifecycle step appends a **new** event; previous events are immutable.

---

## 3. New Merchant Fields (POST /v1/scheme/merchants)

These fields are all **optional** in the request body. Existing callers that omit them continue to work unchanged.

| Field                 | JSON type        | Notes                                                            |
|-----------------------|------------------|------------------------------------------------------------------|
| `businessRegNo`       | string or `null` | 사업자등록번호, e.g. `"123-45-67890"`                              |
| `subMerchantId`       | string or `null` | ZeroPay sub-merchant id assigned by KFTC                         |
| `kftcInstitutionCode` | string or `null` | KFTC institution code                                            |
| `settlementBankCode`  | string or `null` | Bank code for next-business-day settlement credits               |
| `settlementAccountNo` | string or `null` | Account number for settlement credits                            |
| `merchantType`        | string or `null` | Enum: `"SMALL_BIZ"` (feeRate=`"0.0000"`) or `"GENERAL"` (feeRate=`"0.0080"`) |

The response includes:
- `merchantType` — echoed back (string enum or `null`)
- `feeRate` — **string** (BigDecimal as JSON string), derived from `merchantType`; `null` when `merchantType` is null

---

## 4. Notes

- **Amount serialization:** `amount` is always a JSON **string** (not a JSON number). This prevents floating-point precision loss for KRW amounts.
- **Timestamps:** All `at` fields are KST (Asia/Seoul, UTC+09:00) ISO-8601 offset datetimes.
- **Polling pattern:** POS counter display polls `GET .../payments?since={latestSeq}` at its own cadence. Store `latestSeq` from each response and pass it as `since` on the next call to receive only new events.
- **No pagination:** The feed is unbounded in-memory. For simulator use only.
- **Base URL:** `http://localhost:9102`
