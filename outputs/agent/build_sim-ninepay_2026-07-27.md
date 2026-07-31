> 작업: sim-ninepay build / 출처: agent

# sim-ninepay — 9Pay (Vietnam) payout simulator, Phase 4

## What was built

Standalone Gradle project `simulators/sim-ninepay` (own `settings.gradle`, Spring Boot 3.3.4, JDK 21, **port 9107** — verified free; sims use 9102–9104). Mirrors sim-nepal-qr's structure (build files, Dockerfile, README, MockMvc test style). Base package `com.gme.sim.ninepay`. NOT in root settings.gradle; build with `./gradlew -p simulators/sim-ninepay test`.

## 9Pay API surface (plays 9Pay's server for scheme-adapter-ninepay)

- `POST /service/account/verify` — seeded registry incl. spec §9 test accounts (`1023020330000` OK, `2034030440000`→1042, `9704000000000018`→1041, business `66668888`, blocked `9999999999999`→1060); unknown accounts resolve leniently to `SIM ACCOUNT <last4>`
- `POST /service/transfer` — signature gate (1007), partner_id (1001), integer VND ≥ 2000 (1008), content forbidden-chars, known bank (1023), **duplicate request_id → 1062**, balance → 1024; debits amount+fee
- `POST /service/transfer/info` — by TRANSACTION_ID / TRANSACTION_REQUEST_ID; 1021 unknown; advances lifecycle on poll (configurable)
- `POST /service/account/balance`, `GET /transfer-bank/bank-list` (14 seeded VN banks/wallets), `POST /service/exchange-rate-v2`, `POST /service/v2/decode-qr` (pipe formats + canned EMV fallback, 1106)

## Signatures / lifecycle / scenarios

- RSA-2048; canonical pipe-strings byte-identical to adapter `NinepaySigner`; digest configurable SHA1–512 (default SHA256). Sim keypair generated at startup (or PEM property); `GET /sim/public-key` → adapter's `ninepay-public-key-pem`. Partner key via property or `POST /sim/partner-key`; blank = lenient (unverified) dev mode.
- Lifecycle PENDING→PROCESSING→SUCCESS/FAIL on timer (`lifecycle-step-ms`, default 2s) + poll; terminal pushed as signed **IPN** to `sim.ninepay.ipn-url` (default `http://localhost:8096/scheme/ipn`); every IPN also kept in inspectable outbox `GET /sim/ipns`.
- Scenarios (`POST /sim/scenario`): FAIL_SYNC (1024/1065/1066), TIMEOUT (stall→1063), HELD (IPN **008**, status parks PROCESSING), **REVERSAL — SUCCESS IPN 000 then delayed second IPN code 009**, balance restored (`reversal-delay-ms`, default 3s). Also `/sim/transfers`, `/sim/balance`, `/sim/reset`.

## Verification

- `./gradlew -p simulators/sim-ninepay test` — **9/9 green** (2 consecutive clean runs): T01 sign round-trip vs adapter canonical, T02 tampered→1007, T03 happy path + SUCCESS-IPN payload/signature + balance debit, T04 dup 1062, T05 **delayed 009 reversal** + balance restore, T06 VND validation (1999, 2000.5), T07 forced 1024, T08 HELD 008, T09 bank-list/decode-qr.
- Live boot smoke on :9107: transfer → SUCCESS in ~4s, IPN built for :8096, balance 5,000,000,000→4,999,946,000 (50,000+4,000 fee). Fixed during build: cross-test IPN bleed (lifecycle now abandons records orphaned by `/sim/reset`).
- Plan Phase 4 `sim-ninepay` checkbox marked [x].

## Decisions / open

- `transfer_amount` = amount + fee (spec 4.2↔4.3 contradiction, O6); errors are HTTP 200 `success:false` (O7); 009 IPN keeps wire status SUCCESS (code wins in adapter mapper).
- Not done (out of scope): E2E adapter↔sim wiring line in Phase 4, docker-compose/run-fleet entries (Phase 5).
