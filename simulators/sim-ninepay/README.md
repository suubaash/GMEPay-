# sim-ninepay — 9Pay (Vietnam) Disbursement Simulator

Standalone MOCK of 9Pay's payout-to-banks API (integration spec ver 3.13) — the
counterpart of `services/scheme-adapter-ninepay`. The sim plays **9Pay's server**:
it verifies the partner's RSA request signatures, signs its responses and IPN pushes
with its OWN RSA-2048 key, walks each payout through PENDING → PROCESSING →
SUCCESS/FAIL, and pushes the terminal result as an **IPN** to the adapter's
`POST /scheme/ipn`.

Standalone Gradle build (its own `settings.gradle` + `build.gradle`; JDK 21; Spring Boot
3.3.4 — mirrors sim-nepal-qr). It is **not** part of the root `settings.gradle`.

- **Port: 9107** (sim-scheme 9102, sim-nepal-qr 9103, sim-gmeremit 9104).
- Base package: `com.gme.sim.ninepay`.
- Authoritative contract: `Documentation/schemes/digest_9pay-payout-api_2026-07-27.md`.

```
# build + test (from repo root)
./gradlew -p simulators/sim-ninepay test

# run
./gradlew -p simulators/sim-ninepay bootRun
```

## 9Pay API surface (what the adapter calls)

| Endpoint | Behavior |
|---|---|
| `POST /service/account/verify` | Name-verify against a seeded registry (spec section-9 test accounts: `1023020330000` OK, `2034030440000` → 1042, `66668888` business OK, `9704060129837294` ATM OK, `9704000000000018` → 1041; unknown accounts resolve leniently to `SIM ACCOUNT <last4>`) |
| `POST /service/transfer` | Create payout: signature check (1007), integer VND ≥ 2000 (1008), known bank (1023), unique `request_id` (dup → **1062**), balance check (**1024**). Debits `amount+fee` from the prefunded balance |
| `POST /service/transfer/info` | Lookup by `TRANSACTION_ID` or `TRANSACTION_REQUEST_ID`; also advances the lifecycle one step (`advance-on-poll`) |
| `POST /service/account/balance` | Seeded VND balance (decremented by payouts, restored on FAIL / 009 reversal) |
| `GET /transfer-bank/bank-list` | Seeded VN bank/wallet roster (14 entries) |
| `POST /service/exchange-rate-v2` | Seeded reference rates |
| `POST /service/v2/decode-qr` | Sim pipe formats `VIETQR\|bank\|acct[\|amount[\|name]]` / `VNPAY\|acct[\|amount...]`; real `000201...` EMV payloads get a canned VIETQR decode; else 1106 |

All responses use the 9Pay envelope `{success, data | error:{code,message}}` with HTTP
200; `data.signature` is RSA-signed over the documented per-endpoint pipe-string, so the
adapter can run `verify-responses: true` against this sim.

## Keys & signatures

- Sim keypair: generated at startup (or set `sim.ninepay.private-key-pem`, PKCS#8).
  `GET /sim/public-key` → PEM to paste into the adapter's `ninepay-public-key-pem`.
- Partner key: `sim.ninepay.partner-public-key-pem` or `POST /sim/partner-key
  {"public_key_pem": "..."}`. **Blank/unset = requests accepted unverified** (dev mode);
  once set, bad signatures answer 1007.
- Digest configurable SHA1/SHA224/SHA256/SHA384/SHA512 (`sim.ninepay.sign-algorithm`,
  default SHA256 — must match the adapter's `sign-algorithm`).

## Lifecycle & IPNs

PENDING → PROCESSING → terminal, one step per `sim.ninepay.lifecycle-step-ms` (~2s) and
per `transfer/info` poll. The terminal result is POSTed as an IPN to
`sim.ninepay.ipn-url` (default `http://localhost:8096/scheme/ipn`), signed over
`request_id|partner_id|trans_id|request_amount|fee|transfer_amount|type|status|created_at`
(`message`/`approved_at`/`code` unsigned, per spec). Every built IPN also lands in the
inspectable outbox `GET /sim/ipns`, delivered or not.

## Scenario toggles — `GET/POST /sim/scenario`

| Field | Values | Effect |
|---|---|---|
| `transferMode` | `NORMAL` / `FAIL_SYNC` / `TIMEOUT` | `FAIL_SYNC` → `/service/transfer` answers `syncErrorCode` (1024 insufficient, 1065/1066 declined...); `TIMEOUT` → stalls `timeout-hold-ms` then 1063 |
| `syncErrorCode` | e.g. `1024`, `1065`, `1066` | code used by FAIL_SYNC |
| `ipnOutcome` | `SUCCESS` / `FAIL` / `HELD` / `REVERSAL` | async terminal: `FAIL` → IPN code `ipnFailCode`, balance restored; `HELD` → IPN **008**, wire status parks at PROCESSING; `REVERSAL` → SUCCESS IPN (000) then a **delayed second IPN code 009** (bank reversal, `reversal-delay-ms`, balance restored) — the critical adapter scenario |
| `ipnFailCode` | `001`–`007` | code used by async FAIL |

Other sim endpoints: `GET /sim/transfers`, `GET /sim/ipns`, `GET /sim/balance`,
`POST /sim/reset` (wipes ledger/outbox, re-seeds balance, resets scenario).

## Sim decisions (spec ambiguities)

- `transfer_amount` = `amount + fee` (what's debited from the balance — matches the IPN
  field description; the spec contradicts itself between 4.2 and 4.3, open issue O6).
- 009 reversal keeps wire `status=SUCCESS` (009 is a message code, not a wire status);
  the adapter's `NinepayStatusMapper` maps code 009 → REVERSED regardless of status.
- Errors are HTTP 200 with `success:false` (spec defines only body-level semantics, O7).
