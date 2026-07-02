> 작업: Nepal QR decode verify+harden / 출처: agent

# Nepal QR simulator — decode verify + harden

## The QR
`00020101021126350011fonepay.com071640897200000017835204541253035245802NP5914SudanMerchant6015AathraiTriveni62060702316304d60f`

## Actual decoded output (from `QrParser.parse`, asserted green)
| field | value |
|---|---|
| network | `fonepay` |
| initMethod | `static` (POI tag 01 = 11) |
| merchantId | `4089720000001783` (MAI template 26, sub-tag 07) |
| merchantInfoExtra (GUID) | `fonepay.com` (template 26, sub-tag 00) |
| merchantCategoryCode | `5412` (tag 52) |
| trxCurrency | `NPR` (tag 53 = 524) |
| merchantCountry | `NP` (tag 58) |
| merchantName | `SudanMerchant` (tag 59, declared len 14 → trimmed to 13) |
| merchantCity | `AathraiTriveni` (tag 60, declared len 15 → trimmed to 14) |
| trxAmount / amountPaisa | `null` (no tag 54 — static) |

## Was it correct?
**The decoder was already correct** for every field. The `walk()` resync heuristic
correctly trimmed the off-by-one trailing digit on tags 59/60 (kept the next tag in
sync rather than eating it), and the MAI sub-tag fallback picked sub-tag 07 for the
merchant id. No change to `QrParser` was required. Kept it general (no hardcoding).

## What I changed (surfacing gaps, not decode bugs)
- `POST /qrscan-thirdparty/parse/` now also returns `network` and `merchantId`
  (previously only the GUID via `merchantInfoExtra`). All required fields now present:
  format, initMethod, network, merchantId, merchantInfoExtra, merchantCategoryCode,
  trxCurrency, trxAmount, merchantCountry, merchantName, merchantCity, merchantData.
- `GET /sim/nepal-qr/records` gained an optional `?endpoint=` filter so read-only
  (reference-less) parse calls can be located in the store.

## Response confirmation
- **parse** (`/qrscan-thirdparty/parse/`, raw `{qs}` body → 200): initMethod=static,
  network=fonepay, merchantId=4089720000001783, merchantInfoExtra=fonepay.com,
  merchantName=SudanMerchant, merchantCity=AathraiTriveni, merchantCountry=NP,
  trxCurrency=NPR, trxAmount absent (null), MCC=5412. Request+response recorded.
- **validate** (`/api/qr/validate/` → 200): `{network:"fonepay", name:"SudanMerchant",
  merchant_id:"4089720000001783", amount:null, currency:"NPR", purpose:<non-empty>,
  extra:{merchant_city:"AathraiTriveni", mcc:"5412", country:"NP"}}`.

## Tests
- `QrParserTest`: added `decodesExactRealFonepayQr` (all fields incl. merchantId /
  initMethod / null amount). 4 tests, 0 failures.
- `NepalQrControllerTest`: extended **T05** (full parse shape + record-store capture)
  and added **T05b** (validate merchant-network shape). 12 tests, 0 failures.
- Module: `./gradlew -p simulators/sim-nepal-qr test` → BUILD SUCCESSFUL, 16 tests, 0 failures.
