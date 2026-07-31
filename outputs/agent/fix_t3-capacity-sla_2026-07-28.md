> 작업: T3-5 capacity + SLA measurement / 출처: agent

# T3-5 — measured per-transaction footprint, payment SLIs, capacity note

**The IT team is sizing AWS and was given estimates. Here are the measurements.**

---

## 1. MEASURED per-transaction footprint — the answer to the sizing question

Driven through the **real money-path fleet** (payment-executor → merchant-qr-data →
scheme-adapter-zeropay → sim-scheme → transaction-mgmt → revenue-ledger), **200 consecutive
successful wallet payments**, 2026-07-30.

| Quantity | Per transaction | Basis |
|---|---:|---|
| **DB rows written** | **12.00** | **MEASURED** — exact `COUNT(*)` deltas across 5 databases |
| **Row payload (logical)** | **2.65 KB** | **MEASURED** — real column values at real widths |
| DB retained (heap + indexes) | 8.62 KB | DERIVED — PostgreSQL 16 layout over measured rows |
| WAL, excluding full-page writes | 9.08 KB | DERIVED |
| WAL, including full-page writes | 10.9 – 27.2 KB | DERIVED **range** — depends on checkpoint config |
| **Application logs** | **2.58 KB** | **MEASURED** — real stdout bytes from the fleet |
| Kafka | **not measured** | no broker in this fleet |
| **Retained (DB + logs)** | **≈ 11.2 KB** | |
| **Retained incl. WAL archiving** | **≈ 22 – 38 KB** | |

`rows/txn` was **identical at N=5 and N=200**, so the row count is a stable property, not a sample
artefact.

### Estimate vs measurement

| Quantity | Estimate given to IT | Measured / derived | Verdict |
|---|---:|---:|---|
| DB rows/txn | ~15 | **12.00** (**11** healthy — see §2) | **Corrected down** |
| DB bytes/txn | ~7 KB | 8.62 KB | Close, slightly low |
| WAL/txn | 10–15 KB | 9.08 excl. FPI / 10.9–27.2 incl. | Estimate at the low end |
| Kafka/txn | ~3 KB | not measured | **Unverified** |
| **Logs/txn** | **10–12 KB** | **2.58 KB** | **Overstated ~4×** |
| Retained/txn | ~20 KB | ≈ 11.2 KB | **~44 % lower** |
| **At 1 000 txn/day** | **~20 MB/day, ~7 GB/yr** | **11.0 MB/day, ~3.9 GB/yr** | **~45 % lower** |

**Bottom line for IT: the estimate was conservative in the safe direction. Nothing needs more
storage than planned.** Carry forward two corrections — **12 rows, not 15**, and **2.6 KB of logs,
not 10–12 KB**.

### Projection

| Volume | rows/day | DB/day | DB/year | WAL/day (no FPI) | Logs/day |
|---|---:|---:|---:|---:|---:|
| 1 000 txn/day | 12 000 | 8.42 MB | **3.00 GB** | 8.87 MB | 2.52 MB |
| 10 000 txn/day | 120 000 | 84.21 MB | **30.0 GB** | 88.68 MB | 25.18 MB |
| 100 000 txn/day | 1 200 000 | 842 MB | **300 GB** | 887 MB | 252 MB |

### Where it goes

| Database | Table | rows/txn | payload/txn | idx |
|---|---|---:|---:|---:|
| `transaction-mgmt` | `transactions` | 1.00 | 356 B | 9 |
| `revenue-ledger` | `ledger_entries` | 2.00 | 243 B | 4 |
| `transaction-mgmt` | `outbox` | 3.00 | 1.04 KB | 1 |
| `payment-executor` | `revenue_posting_failures` | 1.00 | 404 B | 4 |
| `payment-executor` | `execution_attempts` | 1.00 | 160 B | 3 |
| `scheme-adapter-zeropay` | `zp_committed_txns` | 1.00 | 123 B | 4 |
| `revenue-ledger` | `journals` | 1.00 | 82 B | 2 |
| `revenue-ledger` | `outbox` | 1.00 | 195 B | 2 |
| `revenue-ledger` | `rounding_residual_keys` | 1.00 | 82 B | 1 |

Three findings fall out of the table: **indexes cost more than the data** (5.51 KB vs 3.11 KB heap;
`transactions` carries 9 indexes over 50 columns, 5.7× its own heap); **4 outbox rows per payment
hold 47 % of all row payload written**; and **four of seven components log nothing per payment**,
which is why the log estimate was 4× high — including scheme-adapter-zeropay, which leaves no trace
of a scheme call it made.

---

## 2. A defect the measurement found

`payment-executor.revenue_posting_failures` grew by **exactly 1.00 rows per payment**. On *every*
payment `POST /v1/journals/rounding-residual` returns **406 Not Acceptable**; the ₩500
rounding-residual journal never posts and the request is persisted for replay (by design, T2-1 — the
payment still APPROVES).

- **A healthy deployment writes 11 rows/txn, not 12.**
- The real issue is not capacity: **the rounding-residual entry is not being booked, and nothing
  asserted that it was.** `WalletScanPayE2ETest` checks the *fee* journal only, so this passed
  silently.
- **Not fixed here** — revenue-ledger is outside this task's allowed scope and the fix deserves its
  own change with a regression test. Raised as a separate task.

---

## 3. What was built

### 3.1 The footprint harness — `e2e-tests/src/test/java/com/gme/pay/e2e/footprint/`

| File | Role |
|---|---|
| `PerTxnFootprintE2ETest.java` | `@Tag("e2e")` — boots the fleet, drives N payments, reports |
| `DatabaseProbe.java` | Reads each service's own DB: rows, per-column payload widths, index defs |
| `PostgresSizeModel.java` | Converts measured row shape → PostgreSQL 16 bytes (the DERIVED half) |
| `TableFootprint.java` | Per-table record; field names carry the measured/derived split |
| `FootprintReport.java` | Renders `footprint.md` + `footprint.json` (`schemaVersion: 1`) |
| `PostgresSizeModelTest.java` | **Untagged** — the model is re-proved on every `gradlew build` |

**Reuses `SchemeFleet`** as instructed rather than inventing a harness: same subprocess boot, same
`/v1/_probe` readiness, same `X-Gme-Internal` header, same **strict teardown** (force-kill every
descendant, then *prove* the ports released; ports also asserted free before boot). Verified clean
after every run — no zombie JVMs.

**How it reads databases with no Docker and no database server.** Every service takes
`SPRING_DATASOURCE_URL` from the environment, so the harness starts each one on a **file-backed H2
with `AUTO_SERVER=TRUE`** — the owning JVM serves a second connection itself, and the test process
opens the same database. The services are unaware: same PostgreSQL-compat mode, same Flyway
migrations, same JPA mappings. Crucially **the schema is the real one**, so tables, columns and
index definitions are the objects a production PostgreSQL would hold.

**Five warm-up payments** run before the snapshot, so cold-start writes are not billed to
transaction #1 and then multiplied by 365 000.

### 3.2 Payment SLIs — `services/payment-executor/.../metrics/PaymentSliMetrics.java`

| Metric | Type | Tags |
|---|---|---|
| `gmepay_payment_duration_seconds` | Timer, **percentile histogram** | `entry`, `outcome` |
| `gmepay_payment_outcome_total` | Counter | `entry`, `outcome`, `reason` |

Wired into all three forward money-path entry points (`POST /v1/pay`,
`POST /v1/payments/authorize`, `POST /v1/payments/{id}/confirm`).

- **A decline is not an error** — the same three-way split the load harness uses. 429 is an
  **error**, because being throttled means offered load was not accepted.
- **Bounded cardinality**: `entry` ∈ 3, `outcome` ∈ 3, `reason` normalised and length-capped from
  the platform's own `ApiError` codes. Nothing partner- or merchant-tagged.
- **Percentile histogram, not client-side percentiles** — pre-computed percentiles are not
  aggregatable across replicas.
- **Cannot affect a payment**: nullable registry (missing ⇒ pass-through), exceptions rethrown
  unchanged, injected by *setter* so no existing test constructor changes.

### 3.3 Outbox lag gauges — `libs/lib-errors/.../metrics/OutboxLagGauges.java`

| Metric | Meaning |
|---|---|
| `gmepay_outbox_pending` | Backlog depth |
| `gmepay_outbox_oldest_pending_age_seconds` | **Lag** — the thing to alert on |

Auto-configured from lib-errors, so **all four outbox-bearing services get it with no per-service
edit** (transaction-mgmt, revenue-ledger, prefunding, settlement-reconciliation — all four declare
the same table shape). Registers only where an `outbox` table actually exists.

Both, not either: depth alone cannot tell a draining burst from a stalled publisher; age alone
drops to zero when one old row publishes.

Two auto-config bugs were found and fixed **by running it**, both of which fail silently:
`@ConditionalOnBean` needed `@AutoConfigureAfter` (it was evaluating before Boot contributed the
DataSource and registry), and the bean needed **`@Lazy(false)`** (nothing injects it, so under
`spring.main.lazy-initialization=true` it was never instantiated and `@PostConstruct` never ran).

### 3.4 Everything verified on a live fleet, not just in unit tests

The harness scrapes `/actuator/prometheus` after its 200 payments and asserts the series exist. Both
passed:

```
VERIFIED: payment-executor exposes gmepay_payment_duration_seconds (histogram)
          and gmepay_payment_outcome_total tagged entry/outcome/reason
VERIFIED: transaction-mgmt exposes gmepay_outbox_pending
          and gmepay_outbox_oldest_pending_age_seconds
```

### 3.5 `Documentation/CAPACITY_AND_SLA.md`

Measured footprint + method + projection + re-run instructions, plus the two sections below.

---

## 4. What constrains cost at low volume (§4 of the capacity note)

**Data growth is not the cost driver at launch volumes — the idle fleet is.**

At 1 000 txn/day the platform generates **3 GB of database per year**: a rounding error. Meanwhile
**20 services and 15 PostgreSQL instances run 24/7 at 1 replica each** (`defaultReplicas: 1`,
overridden nowhere; no HPA), and that bill is **identical at 10 txn/day and 10 000**. Going from
1 000 → 10 000 txn/day adds ~27 GB/year and, on these numbers, not one additional instance.

**The 15-database topology is the dominant line item**, and consolidating schemas onto fewer
instances changes no application code (every service takes `SPRING_DATASOURCE_URL`). Where growth
*does* bite is **WAL and logs**, not table data — at 100 000 txn/day WAL exceeds table growth.

## 5. Retention constraints (§5 of the capacity note)

- **Hash-chained audit log — CANNOT be pruned.** `lib-audit` is a per-aggregate SHA-256 Merkle
  chain (ADR-007); deleting any row breaks verification for every later row of that aggregate.
  Append-only by design. Storage grows monotonically forever; "delete older than N days" is not
  available.
- **Partner document vault — CANNOT be deleted early.** `lib-vault` initialises MinIO with object
  lock in **COMPLIANCE mode, 10-year retention** (`VaultBucketInitializer.java:76-79`, ADR-006) —
  deliberately not GOVERNANCE, so *even bucket admins cannot delete during retention*, plus
  versioning. A 10-year monotonic commitment where mistakes are permanent.
- **The easy reclaim: `outbox.payload` and `webhook_delivery_log.payload` store the same event JSON
  twice, and NEITHER has a retention job.** A repo-wide search for retention/prune/purge/delete
  against either table returns nothing. Measured outbox payload alone is **1.24 KB/txn = 47 % of all
  row payload**. A 30-day retention job on published outbox rows reclaims ~46 % of per-transaction
  payload with no effect on the books, no audit-chain implication (the outbox is not hash-chained)
  and no compliance implication. **Not built here** — flagged because it is cheap, safe and absent.

---

## 6. GUARDRAIL — no SLO targets were invented

**No latency, availability or error-budget number appears anywhere in this work.** Those are
commercial commitments and must come from the **product/business owner**.

`Documentation/SLO_TARGETS.properties` remains the template with **every value blank** (untouched by
this change, still pinned by `SloTargetsTest` and `check_load_harness_wiring.py`). §7.2 of the
capacity note is the fill-in table, listing for each key the PromQL the new SLIs now make it
computable from — and naming the prior question the owner must answer first: **does a declined
payment count against availability?**

What changed is only that the numbers are now *measurable*. Someone with the authority to promise
them still has to write them down.

---

## 7. Verification

```
gradlew.bat testClasses                                             green
gradlew.bat :e2e-tests:test :services:payment-executor:test
            :libs:lib-errors:test                                   green
gradlew.bat :e2e-tests:e2eTest --tests *PerTxnFootprintE2ETest*
            -Dgmepay.footprint.payments=200                         green (the run above)
python scripts/check_monitoring_wiring.py                           pass
python scripts/check_internal_auth_wiring.py                        pass
python scripts/check_helm_chart_wiring.py                           pass
python scripts/check_gitleaks_config.py                             pass
python scripts/check_load_harness_wiring.py                         pass
node docker/keycloak/check-topology.mjs                             pass
```

Ports verified free before and after every fleet run; no zombie JVMs.

---

## 8. Still open

1. **The DERIVED half is still derived.** Heap/index/WAL bytes come from a documented PostgreSQL
   storage model, not `pg_total_relation_size` / `pg_current_wal_lsn`, because the E2E fleet runs on
   H2 so it needs no Docker — **and Docker was not to be started**. Converting it to a measurement
   is bounded work: point the fleet's `SPRING_DATASOURCE_URL` at the compose PostgreSQL instances
   and read the `pg_*` functions. The harness structure already supports it.
2. **Kafka bytes remain unmeasured** — no broker in the fleet. The ~3 KB/txn estimate is unverified.
   The outbox rows carrying the same events *are* measured.
3. **prefunding, notification-webhook and settlement-reconciliation were not exercised**
   (`ledger_entry`, `cumulative_usage_ledger`, `webhook_delivery_log`, `settlement_lines`). The
   domestic wallet path does not reach prefunding in this configuration; the other two need Kafka or
   a batch window. Cross-border corridors would add rows.
4. **The audit log and document vault are unsized** — off the measured payment path, and both carry
   the §5 retention constraints, so they need their own exercise.
5. **The 406 rounding-residual defect is not fixed** (§2).
6. **No per-partner SLI**, and **nowhere for a continuous SLI to live** — nothing deploys Prometheus
   (`RUNBOOK_MONITORING.md` §6.2), so there is a scrapeable endpoint and no dashboard, no
   error-budget burn, no partner-facing report.
7. **Cancel and refund are not instrumented** — only the three forward entry points.
8. **Scheduler lag beyond the outbox is still invisible** — the other `@Scheduled` jobs sharing the
   single-thread pool have no metric.
9. **A local run is not a production forecast.** Row counts and payload widths transfer (they are
   properties of code and schema); absolute byte and timing figures do not.
10. **The UNCERTAIN/stuck-payment ops loop**, the other half of the T3-5 register line, is untouched
    by this work — as it was by the load-harness work before it.

---

## 9. Commit provenance — read this

**Part of this work is inside another agent's commit.** While these files were in the working tree,
commit **`b1b3850`** ("wip: second, parallel transaction-screening implementation (needs
reconciliation)") staged broadly and swept in
`e2e-tests/src/test/java/com/gme/pay/e2e/footprint/{PostgresSizeModel,DatabaseProbe,TableFootprint}.java`,
`e2e-tests/build.gradle` and `e2e-tests/.../SchemeFleet.java` — none of which has anything to do
with transaction screening, and none of which that commit message describes.

This is the **second occurrence** of the same failure mode; the register already documents commit
`0885f94` doing it to the T4-5 settlement work. Nothing was rewritten here: the remaining files are
committed separately under this task, and this note is the correction to the record, exactly as
`e521f4b` was for T4-5.
