# e2e-tests — black-box end-to-end harness

The automated test that proves **a wallet scans a merchant QR and the payment succeeds**,
across the real services (not mocks).

## What it does

`WalletScanPayE2ETest` boots the money-path fleet as separate JVM processes — all on their
H2 / in-memory fallback, **no Docker, no Kafka, no Mongo** — and drives the genuine cascade:

```
wallet ──POST /v1/pay──▶ payment-executor
   → GET /v1/merchants/{qr}             → merchant-qr-data      (resolve + ACTIVE check)
   → POST /internal/scheme/zeropay/...  → scheme-adapter-zeropay → sim-scheme (EMVCo/jeonmun)
   → POST /v1/transactions (+ PATCH)    → transaction-mgmt      (persist APPROVED)
   → POST /v1/journals/rounding-...     → revenue-ledger        (book ₩500 fee)
```

Two cases:

1. **Happy path** — scan an ACTIVE merchant's EMVCo QR → asserts `201 APPROVED`, scheme
   `TXN…` ref, ₩500 fee, ₩50,500 charged, **and independently queries transaction-mgmt to
   confirm the transaction was really persisted as APPROVED.** The GME-Remit path swallows
   downstream failures (logs + continues), so a green receipt alone does not prove the write
   happened — this independent assertion is the point of the test.
2. **Negative control** — scan a DEACTIVATED merchant → asserts `422 DECLINED /
   MERCHANT_INACTIVE`, proving merchant validation is genuinely wired, not a stub.

## Run it

```bash
./gradlew :e2e-tests:e2eTest
```

- Builds the five service boot jars automatically (task dependency) and builds `sim-scheme`
  on demand (it lives in the separate `simulators` Gradle build).
- Needs ~2 GB free RAM for the 6 JVMs. First run is slower (jar builds); afterwards ~12 s.
- It is `@Tag("e2e")`, so a normal `./gradlew build` does **not** run it.
- Per-service logs land in `e2e-tests/build/e2e-logs/`; on a startup failure the harness
  dumps the last 40 lines of each.

## Known limitations (deliberate scope)

- Runs against **sim-scheme**, not the real ZeroPay/KFTC TCP endpoint.
- Kafka is off, so the `payment.approved` **webhook delivery leg is not exercised**. To
  cover it, add a Kafka profile + a stub webhook receiver and assert delivery.
- The ₩500 fee posting to revenue-ledger is fire-and-forget and not independently asserted
  (revenue-ledger has no query-by-reference endpoint). Add one to close this gap.
- Only the **domestic GME-Remit MPM** flow is covered. SENDMN (FX + prefunding) and CPM are
  natural next cases on the same harness.
