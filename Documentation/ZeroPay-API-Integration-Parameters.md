# ZeroPay API — Integration Parameters for GMEPay+

> Source: `Zeropay API Document 2026.pdf` (KFTC / ZeroPay 전문 규격, §3.5 업무처리 + §4.3 일괄전송 FILE LAYOUT).
> Extracted 2026-06-16. This maps the real ZeroPay protocol to what `scheme-adapter-zeropay`
> must send/receive to integrate GMEPay+ as a **해외페이 결제사업자** (overseas payment operator)
> via the KFTC **중계센터** (relay centre).

## 0. The headline (it is NOT a REST API)

The real ZeroPay scheme interface is a **fixed-length ISO-8583-style "전문" (message) over a TCP/IP
socket** — **1,000 bytes per online message**, positional fields, EUC-KR/byte-padded. Field **No.0
is literally `TCP/IP Header (AN, 7)`**. This is fundamentally different from the current
`scheme-adapter-zeropay`, which speaks REST/JSON to `sim-scheme`.

**Integration gap:** the adapter needs a **TCP socket client + a 전문 codec** (fixed-field
encode/decode, byte-padding, 1000-byte framing), not the REST `ZeroPaySchemeApiClient`. The batch
side (ZP0011/0012) is already partially modelled in code (`Zp0011*Record`, `ZeroPayResultRecord`);
the **online real-time 전문 is the missing piece**.

## 1. Message catalog (거래구분코드 + 전문종별코드)

| Flow | 거래구분코드 | 전문종별코드 | GMEPay+ usage |
|---|---|---|---|
| **CPM payment / cancel** | `400000` | `0200`/`0210` (pay req/resp), `0400`/`0410` (cancel req/resp) | customer-presented QR (wallet scans merchant... no — customer shows token) |
| **변동형 MPM payment / cancel** (dynamic) | `420000` | `0200`/`0210`, `0400`/`0410` | merchant-presented **dynamic** QR (amount embedded) |
| **고정형 MPM 결제결과 등록** (static, result registration) | `500000` | `0200`/`0201`/`0210` | merchant-presented **static** QR — payment is computed locally then *registered* |
| **변동형 CPM 결제결과 등록** | `520000` | — | (referenced; result-registration variant) |

For GMEPay+ Phase-1 (Korea ZeroPay, merchant-presented MPM via `payment-executor`), the primary
messages are **변동형 MPM `420000`** (dynamic) and **고정형 MPM `500000`** (static result-reg);
**CPM `400000`** covers the consumer-presented path.

## 2. Common envelope (every message) — fields 0–26

| No | Field (KO) | Meaning | TYPE | Len | GMEPay+ supplies (request) |
|---|---|---|---|---|---|
| 0 | TCP/IP Header | transport header | AN | 7 | socket framing layer |
| 1 | 시스템구분코드 | system id | A | 3 | **constant `"ZPY"`** |
| 2 | 전문종별코드 | message type | N | 4 | `0200`/`0400` (req), echoed on resp |
| 3 | 거래구분코드 | txn division | N | 6 | `400000`/`420000`/`500000` |
| 4 | 송수신FLAG | send/recv flag | N | 1 | `0`=send (request) |
| 5 | STATUS | status | N | 3 | response-only |
| 6 | 응답코드 | response code | AN | 3 | **response-only** (e.g. `511`=부분환불 불가) |
| 7 | 응답코드 부여기관 | resp-code issuer | AN | 3 | response-only |
| 8 | 전문전송일 | send date | N | 8 | `yyyyMMdd` (now, KST) |
| 9 | 전문전송시각 | send time | N | 6 | `HHmmss` (now, KST) |
| 10 | 전문추적번호 | trace no. | N | 8 | GMEPay+ generates a unique trace per message |
| 11 | 공통정보부 FILLER | filler | any | 35 | spaces |
| 21 | 거래발생일 | txn date | N | 8 | KST business date of the txn |
| 22 | 거래고유번호 | txn unique no. | AN | 13 | **GMEPay+ partner txn ref** (≤13, AN) ← `cmd.partnerTxnRef` |
| 23 | 요청기관코드 | requesting org | AN | 3 | **GMEPay+'s KFTC 결제사업자 institution code** (config) |
| 24 | 응답기관코드 | responding org | AN | 3 | `"099"` (중계센터) on request |
| 25 | 기관정의 에러메세지 | org error msg | any | 100 | response-only |
| 26 | 업무공통부 FILLER | filler | any | 93 | spaces |

## 3. Business body — per message (fields 31+)

### CPM payment (400000 / 0200) — fields 31–51, 1,000 bytes
| No | Field | TYPE | Len | GMEPay+ supplies |
|---|---|---|---|---|
| 31 | 거래금액 (amount, **incl. merchant fee + VAT**) | N | 12 | `quote.targetPayout` (KRW, no decimals) |
| 32 | 봉사료 (service charge/tip) | N | 12 | usually `0` |
| 33 | 부가가치세 (VAT) | N | 12 | VAT portion (0 if not split) |
| 34 | QR구분 | AN | 1 | `"3"`=고객제시 변동형QR, `"B"`=바코드 |
| 35 | (QR)결제시스템ID | AN | 2 | from the scanned customer QR/token |
| 36 | (QR)결제토큰 | AN | 50 | **the CPM token** ← `cmd.cpmToken` |
| 37 | (QR)체크문자 | any | 4 | from QR (init value for barcode) |
| 38 | 가맹점ID | AN | 20 | **merchant id** ← merchant resolution |
| 39 | 단말기 관리번호 | any | 20 | terminal mgmt no. (optional) |
| 40 | 가맹점 추가 정보 | any | 50 | optional (echoed by scheme; no PII) |
| 41 | 가맹점 수수료 | N | 12 | **response-only** (scheme returns merchant fee) |
| 42–46 | 펌뱅킹 (firm-banking) fields | N/AN | 3–20 | init values for 해외페이 (see §4) |
| 47 | 선불복합결제 구분코드 | AN | 1 | **`"O"` for 해외페이** (결제사 자체선불 전액) |
| 48 | 결제사 자체선불 거래금액 | Nsp | 12 | **= 거래금액 (field 31) for 해외페이** |
| 49 | 직선불 거래금액 | Nsp | 12 | `0` for 해외페이 |
| 50 | 자원순환 보증금 (deposit) | Nsp | 5 | spaces (n/a for overseas) |
| 51 | FILLER | any | 402 | spaces |

### 변동형 MPM payment (420000 / 0200) — dynamic merchant QR
Same envelope; body differs at the QR fields:
- 34 QR구분 = `"2"` (가맹점제시 변동형); 35 (QR)등록기관ID (AN2); **36 (QR)거래일련번호 (AN50)** (the dynamic QR serial); 37 (QR)체크문자; 38 가맹점ID (AN20); 39 단말기 관리번호; 40 가맹점 추가 정보; 41 가맹점 수수료 (resp); 42–49 펌뱅킹/선불 (same 해외페이 rule); 50 FILLER (407).

### 고정형 MPM 결제결과 등록 (500000 / 0200) — static QR, *result registration*
The static-QR payment is computed by the operator then **registered** (not authorised online):
- 34 결제시간 (N6); 35 QR구분 = `"1"` (가맹점제시 고정형); 36 (QR)등록기관ID (AN2); **37 (QR)가맹점ID (AN20)**, **38 (QR)단말기ID (any20)** from the static QR; 40 가맹점 수수료 (N12); 49 **세금 환급액 Tax Refund (Nsp12)** — overseas/면세 use; 응답기관코드(24) request="099" → centre rewrites to HOST가맹점 code.

### QR구분 code table
`"1"`=가맹점제시 고정형(MPM static) · `"2"`=가맹점제시 변동형(MPM dynamic) · `"3"`=고객제시 변동형(CPM) · `"B"`=고객제시 바코드.

### Notable response code
`511` = 부분환불 불가 (partial refund not allowed — full refund only when a 자원순환 보증금 is present).

## 4. 해외페이 (overseas) specifics — directly relevant to GMEPay+
GMEPay+ is cross-border, so it is a **해외페이 결제사업자**. Per §3.5 (page 12 note):
- Firm-banking fields (42–46) → **init values**.
- 선불복합결제 구분코드 (47) → **`"O"`** (결제사 자체선불 전액).
- 결제사 자체선불 거래금액 (48) → **= 거래금액**; 직선불 거래금액 (49) → `0`.
- **세금 환급액 (Tax Refund)** field is the overseas-pay tax-free settlement field (general operators leave unused).

## 5. Batch file (일괄전송) — ZP0011 / ZP0012 (already partly modelled in code)
Daily payment-result registration: 결제사업자 → 중계센터 sends **ZP0011** (HEADER 700B / DATA 700B / TRAILER 700B); centre returns **ZP0012**. Covers 400000/420000/500000/520000 results. Key DATA-record fields: 원거래일자, 원거래고유번호, 거래금액/봉사료/부가가치세, QR구분/등록기관ID/가맹점ID/단말기, 가맹점 수수료, 펌뱅킹(부가정보 truncated to 10), 미완료여부(주6), 처리결과코드(주7), 중계센터 단독등록 여부(주8), 선불/직선불, 자원순환 보증금, Tax Refund. `Zp0011Record`/`Zp0012FileParser` in `scheme-adapter-zeropay` already model this — verify lengths against §4.3.

## 6. Config parameters GMEPay+ must supply (scheme-adapter-zeropay)
These are the integration parameters to add to `application.yml` (today's `gmepay.scheme.zeropay.*`):

| Parameter | Meaning |
|---|---|
| `zeropay.tcp.host` / `zeropay.tcp.port` | 중계센터 socket endpoint (replaces the REST base-url) |
| `zeropay.institution-code` | GMEPay+'s KFTC 결제사업자 / HOST가맹점 code → field 23 요청기관코드 |
| `zeropay.system-code` | `"ZPY"` → field 1 |
| `zeropay.charset` | EUC-KR (byte length basis for the 1,000-byte frame) |
| `zeropay.connect-timeout` / `read-timeout` | socket SLA (maps to current SchemeTimeoutException) |
| `zeropay.overseas-pay` | `true` → drives the 해외페이 field rules (§4) |
| (per-partner) ZeroPay merchant id / 단말기 관리번호 | from `config-registry` partner_scheme (`zeropayMerchantId`, `kftcInstitutionCode`) |

## 7. What to build (next steps)
1. **전문 codec** — a fixed-field layout descriptor (No/type/len/justify/pad) + encode/decode for the common envelope + each business body (CPM 400000, MPM 420000/500000), 1,000-byte framing, EUC-KR.
2. **TCP socket client** — replaces `ZeroPaySchemeApiClient`'s REST calls; request/response correlation by 전문추적번호 (field 10); timeout → `SchemeTimeoutException`, response code mapping → `SchemeDeclinedException` (e.g. `511`).
3. **Map `MpmSubmitRequest`/`CpmSubmitRequest` → 전문 fields** per §2–§4 (amount→31, token→36, merchantId→38, partnerTxnRef→22, etc.).
4. Keep `sim-scheme` for dev, but add a `zeropay.transport=tcp|sim` selector so the real codec is exercised against a 전문-speaking stub before KFTC certification.

> The KFTC certification (한결원) remains the external, calendar-bound gate identified in the
> go-live audit; this spec is the contract that certification will test against.
