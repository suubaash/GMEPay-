> 작업: scheme-adapter-ninepay build / 출처: agent

# Build report — services/scheme-adapter-ninepay (9Pay Vietnam payout adapter)

Plan Phase 3 (`Documentation/schemes/QR_SCHEME_ACCOMMODATION_PLAN.md`) — adapter service ONLY, no hub wiring (decision D4). Package `com.gme.pay.scheme.ninepay` (D1). Port **8096** (verified free across all module `server.port` configs; only used elsewhere as a docker-compose HOST mapping, which is a separate namespace — containers run SERVER_PORT=8080 internally).

## Test result

`gradlew :services:scheme-adapter-ninepay:test` → **BUILD SUCCESSFUL — 69 tests, 0 failures**

| Class | Tests |
|---|---|
| NinepaySignerTest (round-trip, tamper canonical+sig, wrong-key, SHA1–SHA512 variants, PEM PKCS#8 load, blank/PKCS#1 rejection) | 16 |
| NinepayStatusMapperTest (statuses + IPN codes 000–009, code-wins-over-status, finality) | 21 |
| NinepaySchemeAdapterTest (VND ≥2000 + content charset, replay idempotency, 1062→poll, timeout→poll gate incl. not-found→UNKNOWN + double-timeout→UNKNOWN, definitive-reject→FAILED+code, IPN 000/008/009-after-SUCCESS, forged-sig audited+rejected, unknown request_id audited) | 16 |
| NinepayApiClientTest (MockRestServiceServer: signed pipe-strings, hl=en, 1062, timeout/5xx=ambiguous, 400-with-error-body=definitive, transfer-info by TRANSACTION_REQUEST_ID, balance, decode-qr signs 2 fields only, bank-list, verify-responses=true good/forged) | 10 |
| NpPersistenceH2SliceTest (round-trip, UNIQUE request_id, CHECK amount≥2000, 000→009 reversal lifecycle, IPN event audit, findByStatusIn) | 6 |

## Files created (all under `services/scheme-adapter-ninepay/`)

- `build.gradle` (web/actuator/data-jpa/springdoc/flyway-core + flyway-database-postgresql/postgresql/h2 runtime, lib-errors), `Dockerfile` (EXPOSE 8096), `README.md`, `CHANGELOG.md`
- `src/main/resources/application.yml` — port 8096; H2 PG-mode default datasource (SPRING_DATASOURCE_* overrides, zeropay convention); `gmepay.scheme.ninepay.*` (base-url default sim-ninepay :9107; stg/prod 9Pay URLs in comments; partner-id, sign-algorithm SHA256, PEM placeholders, verify-responses=false)
- `src/main/resources/db/migration/V001__create_np_payouts_and_np_ipn_events.sql` — np_payouts (UNIQUE request_id, status CHECK incl. HELD/REVERSED/UNKNOWN, NUMERIC(20,0) VND, CHECK ≥2000, reversed_at) + np_ipn_events (raw_payload TEXT, code, signature_valid)
- Java: `SchemeAdapterNinepayApplication`; `sign/NinepaySigner` (canonical pipe-join, RSA sign/verify, lazy PEM); `client/NinepayApiClient` (+`TransferCommand/TransferResult/TransferInfo/VerifyResult/BalanceResult/DecodedQr`), `client/NinepayErrorException` (definitive, 1062/1005/1021 helpers), `client/NinepayTransportException` (ambiguous→must-poll); `status/PayoutStatus`+`NinepayStatusMapper`; `persistence/NpPayoutEntity/Repository`, `NpIpnEventEntity/Repository`; `adapter/NinepaySchemeAdapter`; `api/NinepaySchemeController` (`POST /scheme/payout`, `GET /scheme/payout/{requestId}`, `GET /scheme/balance`, `POST /scheme/decode-qr`, `POST /scheme/ipn`), `api/ApiExceptionHandler`; `config/OpenApiConfig`; DTOs (`PayoutRequest/Response`, `BalanceResponse`, `DecodeQr*`, `IpnRequest/IpnAck`)

Outside the service dir: ONLY the Phase-3 checkboxes flipped in `Documentation/schemes/QR_SCHEME_ACCOMMODATION_PLAN.md` (hub-wiring line + other phases untouched; file had concurrent Phase-1 edits from the sendmn build — my edit applied cleanly).

## Key design points

- **Idempotency spine**: np_payouts row persisted BEFORE the wire call (unique request_id; concurrent-dup race handled via DataIntegrityViolation→reload). 1062 → transfer-info poll, adopt state, never resubmit. Transport timeout/5xx → ALWAYS poll first; 9Pay-confirmed not-exists (1005/1021) or poll failure → status UNKNOWN (retry is the hub's decision; adapter never auto-resubmits/auto-fails). No cancel API — none exposed.
- **IPN**: raw body audited verbatim (event save deliberately outside the payout tx so it survives rejection); signature over the documented 9-field string (code/message/approved_at excluded); forged sig → audited signature_valid=false + 400 (9Pay redelivers). 009 → REVERSED + reversed_at (warn if prior ≠ SUCCESS); 008 → HELD; 004 → still PENDING.
- **Signer**: per-endpoint pipe canonical; SHA1–SHA512 configurable (default SHA256); missing keys fail loudly at use, not boot.

## Deviations / open questions

1. **Response-signature verification default OFF** (`verify-responses=false`) until real 9Pay keys exist; IPN verification is ALWAYS attempted (fails closed). Flip on at key exchange (O8).
2. **exchange-rate-v2** sends amount/currency_from/currency_to in body AND signature per the digest gotcha — real contract unconfirmed (O7-adjacent).
3. transfer_amount fee-inclusion (O6) stored informational-only; no fee recon built on it.
4. IPN ACK contract undocumented (O5) — returns 200 `{"status":"RECEIVED"}`, 4xx on bad signature; ipn_url registration + 9Pay-IP allowlisting left to network layer.
5. PKCS#1 private keys rejected with a convert-to-PKCS#8 hint (spec allows both; PKCS#8 only implemented).
6. No docker-compose/run-fleet entry, no sim-ninepay, no hub wiring — Phase 4/5 scope per plan.
