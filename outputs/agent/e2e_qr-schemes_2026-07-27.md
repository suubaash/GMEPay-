> 작업: SendMN/9Pay adapter-sim E2E / 출처: agent

# E2E report — QR scheme accommodation Phase 4 (adapter ↔ simulator pairs, LIVE)

**Result: PASS — 9/9 live E2E tests green** (`BUILD SUCCESSFUL`, 2 runs; the only iteration was a
test-side assertion fix, no adapter/sim/hub source was touched). Both Phase-4 E2E checkboxes in
`Documentation/schemes/QR_SCHEME_ACCOMMODATION_PLAN.md` marked `[x]`.

## What was run (real boot-jar subprocesses over real HTTP, JUnit `@Tag("e2e")`)

New, committed under the existing `e2e-tests` harness pattern (same boot/teardown discipline as
`WalletScanPayE2ETest`, extracted into a small shared helper because the 9Pay pair needs per-process
env vars for RSA PEM material):

- `e2e-tests/src/test/java/com/gme/pay/e2e/SchemeFleet.java` — pair-fleet harness: boot-jar launch
  with env support, on-demand standalone-sim `bootJar` build, readiness probe, and **strict teardown**
  (force-kill + descendants, then *proves* the ports closed; fails loudly naming the PID if a zombie
  survives). Ports asserted free BEFORE boot too.
- `e2e-tests/src/test/java/com/gme/pay/e2e/SendmnAdapterSimE2ETest.java`
- `e2e-tests/src/test/java/com/gme/pay/e2e/NinepayPayoutE2ETest.java`
- `e2e-tests/build.gradle` — added `:services:scheme-adapter-sendmn:bootJar` / `...-ninepay:bootJar`
  task deps (sims build on demand via `gradlew.bat -p simulators/<name> bootJar`).

### Scenario A — SendMN pay-in (`scheme-adapter-sendmn` :8093 ↔ `sim-sendmn` :9106, plain envelope) — 5/5

1. **FX registration**: fresh rate 3391.50 set at sim → pushed SendMN→partner direction
   (`POST /partner-hosted/fx-rate`) → adapter `fx-rate/latest` serves exactly that rate + ticker.
2. **Happy path**: seeded amount-less EMVCo QR (`/sim/qr/{merchantId}`) → `verify-qr` (TX_TOKEN_NO
   minted, merchant GUID/name/qrType/currency round-trip) → `submit-mpm` 150000.00 MNT → APPROVED with
   PAYMENT_NO + receipt; `SETTLEMENT_AMOUNT == 150000.00/3391.50 @scale4 HALF_UP` and rate == registered;
   status poll reaches Approved; sim ledger cross-checked (one payment, same PAYMENT_NO/settlement).
3. **Duplicate submit**: same txTokenNo replayed → same PAYMENT_NO, sim still holds ONE payment.
4. **Wire 304** (forced): adapter resolves via PaymentStatus poll → PENDING, no PAYMENT_NO, sim record
   stays `Decrypted` (zero money moved, no resubmit).
5. **Wire 307** (forced): clean structured **4xx** decline carrying the 307 semantics — no 5xx/crash;
   adapter stays serviceable; sim confirms no payment created.

### Scenario B — 9Pay payout (`scheme-adapter-ninepay` :8096 ↔ `sim-ninepay` :9107) — 4/4

Full mutual RSA-2048: test generates both keypairs, provisions them via `SPRING_APPLICATION_JSON`
env (PEMs can't ride a Windows command line), boots the adapter with **`verify-responses=true`** —
so every request, response AND IPN signature is genuinely signed/verified on the wire.
`GET /sim/public-key` asserted to serve the provisioned trust anchor (the documented key-exchange surface).

1. **Happy path**: `POST /scheme/payout` (VIETCOMBANK / 1023020330000 / 50,000 VND) → PENDING →
   polled to SUCCESS; signed IPN 000 **delivered + ACKed** by the adapter (adapter persists
   `np_ipn_events` before ACK and 400s bad signatures, so `delivered=true` proves the inbound edge);
   prefund debited exactly amount+fee (54,000) once.
2. **Duplicate request_id (replay)**: same body again → stored SUCCESS + same transaction_id; sim holds
   exactly ONE transfer; balance unchanged.
3. **Wire 1062**: transfer pre-created at the sim out-of-band (test signs with the partner key) →
   adapter submit with the same request_id → sim answers 1062 → adapter **adopts** the existing transfer
   via transfer/info (same transaction_id, not FAILED/UNKNOWN), single transfer, single debit overall.
4. **Delayed 009 reversal**: scenario `ipnOutcome=REVERSAL` → payout reaches SUCCESS (IPN 000), then the
   delayed 009 arrives → adapter flips the payout to **REVERSED** (SUCCESS is poll-final, so REVERSED can
   only come from the recorded IPN); 009 IPN ACKed; sim balance fully restored.

## Integration bugs found

**None in adapter/sim/hub source.** Every wire contract (SendMN envelope/headers/RES_CODEs, 9Pay
pipe-string canonicals for transfer / transfer-info / response signatures / IPN signature) matched
live on first contact. One **test-side** fix during the pass: sim-sendmn serializes JSON `non_null`,
so an unset PAYMENT_NO is an *absent* field, not a JSON null — the wire-304 assertion now accepts
`isNull() || isMissingNode()` (run 1: 8/9; run 2: 9/9).

## Repro

```
cmd /c "cd /d D:\GMEPay+\code && gradlew.bat :e2e-tests:e2eTest --tests com.gme.pay.e2e.SendmnAdapterSimE2ETest --tests com.gme.pay.e2e.NinepayPayoutE2ETest"
```
(Adapter jars build as task deps; sims build on demand. Per-process logs:
`e2e-tests/build/e2e-logs-sendmn/`, `e2e-tests/build/e2e-logs-ninepay/`.)
Ports 9106/8093/9107/8096 verified free via netstat **before and after** the run — no zombie JVMs.

## Unresolved / out of scope

- Hub-through E2E (wallet `/v1/pay` → payment-executor → SENDMN adapter) not in this pass — the plan's
  Phase-4 line is the adapter↔sim edge; the hub leg is covered by Phase-2 wiring tests. Natural next
  case on the same `SchemeFleet` harness.
- RSA envelope mode for SendMN untested E2E (sim is plain-only pending O2); 9Pay tested with locally
  generated keys — real key exchange remains O8.
- Sequencing note: the sendmn adapter maps Confirm RES_CODE 0 → APPROVED while the sim's PaymentStatus
  reports Processing until the Nth poll; a status poll can transiently show PENDING after an APPROVED
  Confirm (observed benign here; only worth revisiting if SendMN confirms Confirm-success ≠ paid).
