# CAPACITY & SLA — measured per-transaction footprint

> Closes the measurement half of gap **T3-5** in `Documentation/GAP_REGISTER.md`.
> Implementation notes: `outputs/agent/fix_t3-capacity-sla_2026-07-28.md`.
> Companion document: `Documentation/RUNBOOK_LOAD_AND_CAPACITY.md` (throughput, latency, ceilings).
>
> **This document contains no SLO targets, and that is deliberate.** See §7.

---

## 1. The headline — what one successful payment costs

This is what the AWS sizing exercise asked for. It **replaces the earlier estimate**, which was
arrived at by reading code and counting tables.

| Quantity | Per transaction | Basis |
|---|---:|---|
| **DB rows written** | **12.00** | **MEASURED** — exact `COUNT(*)` deltas across 5 service databases |
| **Row payload (logical)** | **2.65 KB** | **MEASURED** — real column values at real widths |
| DB retained (heap + indexes) | 8.62 KB | DERIVED — PostgreSQL 16 layout applied to the measured rows |
| WAL, excluding full-page writes | 9.08 KB | DERIVED |
| WAL, including full-page writes | 10.9 – 27.2 KB | DERIVED range — depends on checkpoint config, not on row shape |
| **Application logs** | **2.58 KB** | **MEASURED** — real stdout bytes from the fleet |
| Kafka | not measured | no broker in the harness fleet — §6 |
| **Retained total (DB + logs)** | **≈ 11.2 KB** | measured rows, derived bytes |
| **Retained total incl. WAL archiving** | **≈ 22 – 38 KB** | the WAL range dominates the spread |

Measured over **200 consecutive successful wallet payments** through the real money-path fleet on
2026-07-30. `rows/txn` was identical at N=5 and N=200, so the row count is stable, not a sample
artefact.

### 1.1 Estimate versus measurement

| Quantity | Earlier estimate | Measured / derived | Verdict |
|---|---:|---:|---|
| DB rows per txn | ~15 | **12.00** | **Corrected down.** And see §1.2 — the honest steady-state figure is **11**. |
| DB bytes per txn (heap+idx) | ~7 KB | 8.62 KB | Estimate was close; slightly low |
| WAL per txn | 10–15 KB | 9.08 KB (excl. FPI); 10.9–27.2 KB (incl.) | Estimate sat at the low end of the real range |
| Kafka per txn | ~3 KB | not measured | Unverified |
| Application logs per txn | 10–12 KB | **2.58 KB** | **Overstated ~4×** — the single biggest correction |
| Retained per txn | ~20 KB | ≈ 11.2 KB | **~44 % lower** |
| Retained per txn incl. WAL | ~35 KB | ≈ 22–38 KB | Estimate lands inside the measured range |
| At 1 000 txn/day | ~20 MB/day, ~7 GB/yr | **11.0 MB/day, ~3.9 GB/yr** | **~45 % lower** |

**The estimate was directionally sound and conservative.** Nothing here says the platform needs
more storage than planned; it needs somewhat less. The two figures worth carrying forward are the
**row count (12, not 15)** and the **log volume (2.6 KB, not 10–12 KB)**.

### 1.2 One of the 12 rows is an error-path row — a defect found by running this

`payment-executor.revenue_posting_failures` grew by **exactly 1.00 rows per payment**. That is a
durable *failure* sink. On every single payment, `POST /v1/journals/rounding-residual` to
revenue-ledger returns **406 Not Acceptable**, the ₩500 rounding-residual journal never posts, and
payment-executor persists the request for later replay (by design — T2-1 — the payment itself is
unaffected and still APPROVED).

Consequences for this document:

- **A healthy deployment should write 11 rows per transaction, not 12.** The 12 above is what the
  platform does *today*.
- Capacity is the smaller problem here. The rounding-residual ledger entry is **not being booked**,
  and nothing was asserting that it was. Tracked separately; it is not fixed by this change.

---

## 2. Where the rows and bytes go

`rows/txn`, `payload/txn`, `cols` and `idx` are **MEASURED**. `heap/txn`, `index/txn` and `WAL/txn`
are **DERIVED** from them by `PostgresSizeModel`.

| Database | Table | rows/txn | payload/txn | cols | idx | heap/txn | index/txn | WAL/txn |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| `transaction-mgmt` | `transactions` | 1.00 | 356 B | 50 | 9 | 410 B | 2.28 KB | 2.35 KB |
| `revenue-ledger` | `ledger_entries` | 2.00 | 243 B | 7 | 4 | 315 B | 1.31 KB | 1.65 KB |
| `transaction-mgmt` | `outbox` | 3.00 | 1.04 KB | 6 | 1 | 1.14 KB | 87 B | 1.54 KB |
| `payment-executor` | `revenue_posting_failures` | 1.00 | 404 B | 12 | 4 | 455 B | 577 B | 1.05 KB |
| `payment-executor` | `execution_attempts` | 1.00 | 160 B | 18 | 3 | 200 B | 537 B | 754 B |
| `scheme-adapter-zeropay` | `zp_committed_txns` | 1.00 | 123 B | 17 | 4 | 167 B | 346 B | 635 B |
| `revenue-ledger` | `journals` | 1.00 | 82 B | 3 | 2 | 117 B | 265 B | 445 B |
| `revenue-ledger` | `outbox` | 1.00 | 195 B | 6 | 2 | 234 B | 81 B | 441 B |
| `revenue-ledger` | `rounding_residual_keys` | 1.00 | 82 B | 3 | 1 | 117 B | 75 B | 281 B |
| | **TOTAL** | **12.00** | **2.65 KB** | | | **3.11 KB** | **5.51 KB** | **9.08 KB** |

Three things stand out, and all three are actionable:

1. **Indexes cost more than the data — 5.51 KB against 3.11 KB of heap.** `transactions` alone
   carries **9 indexes over 50 columns**, so its index volume is 5.7× its heap. Index review is a
   larger storage lever here than anything to do with row content.
2. **The outbox is the single largest payload contributor.** 4 outbox rows per transaction
   (3 in transaction-mgmt, 1 in revenue-ledger) holding 1.24 KB of event JSON — **47 % of all row
   payload written by a payment**. See §5.
3. `transactions` at 50 columns and 356 B of payload is modest; the row count, not row width, is
   what drives this footprint.

### 2.1 Logs, per service (MEASURED)

| Service | Per transaction |
|---|---:|
| `payment-executor` | 1.32 KB |
| `transaction-mgmt` | 777 B |
| `revenue-ledger` | 515 B |
| `config-registry`, `merchant-qr-data`, `scheme-adapter-zeropay`, `sim-scheme` | 0 B |
| **TOTAL** | **2.58 KB** |

Four of seven components log **nothing** per payment at the default level. That is why the 10–12 KB
estimate was 4× high — and it is also a finding in the other direction: **scheme-adapter-zeropay
emits no log line for a scheme call it made**, so a per-payment scheme interaction leaves no trace
in that service's log. Note the 1.32 KB from payment-executor is inflated by the §1.2 defect, which
logs two WARN lines with stack-trace text on every payment.

---

## 3. Projection

Derived from the measured per-transaction figures. **DB/year assumes nothing is ever deleted**,
which — see §5 — is currently true.

| Volume | rows/day | DB/day | DB/year | WAL/day (no FPI) | Logs/day | Logs/year |
|---|---:|---:|---:|---:|---:|---:|
| **1 000 txn/day** | 12 000 | 8.42 MB | **3.00 GB** | 8.87 MB | 2.52 MB | 0.90 GB |
| **10 000 txn/day** | 120 000 | 84.21 MB | **30.0 GB** | 88.68 MB | 25.18 MB | 9.0 GB |
| **100 000 txn/day** | 1 200 000 | 842 MB | **300 GB** | 887 MB | 252 MB | 90 GB |

---

## 4. What actually constrains cost at low volume

**At the volumes GMEPay+ will realistically launch on, data growth is not the cost driver — the
idle fleet is.** This is the most important sentence in the document for an AWS sizing exercise.

At **1 000 transactions/day the platform generates 3 GB of database per year.** That is a rounding
error: it fits in the free tier of essentially any managed database, and a year of it costs single-digit
dollars of storage. Meanwhile the platform, **once it is deployed**, costs the same 24/7 regardless
of whether a single payment happens. (Today it is deployed nowhere: one Windows laptop under Docker
Compose, and the Helm chart has never been applied — T1-6, T3-9. The table below is therefore a
*sizing model of the intended topology*, not a bill anyone is currently paying.)

| Fixed cost item | Count | Source |
|---|---:|---|
| Deployable services | **20** | root `build.gradle`; `deploy/helm/gmepay/values.yaml` |
| PostgreSQL databases (one per service) | **15** | `docker-compose.yml` — 15 separate `postgres:16-alpine` |
| Kafka + Zookeeper | 2 | `docker-compose.yml` |
| Redis, MinIO, Keycloak, MongoDB | 4 | `docker-compose.yml` |
| Replicas per service | **1** | `values.yaml:59` `defaultReplicas: 1`, overridden nowhere |

So the bill **would be** **~20 always-on compute units plus ~15 always-on database instances**, and
it is **identical at 10 transactions/day and 10 000**. Concretely:

- **Going from 1 000 to 10 000 txn/day adds ~27 GB of storage per year** — a few dollars a month.
  It does not, on these measurements, require a single additional instance.
- **The 15-database topology is the dominant line item at low volume.** Fifteen managed instances
  at any provider's smallest production-grade class will exceed the storage cost by one to two
  orders of magnitude. If cost matters more than blast-radius isolation before real volume arrives,
  *consolidating schemas onto fewer instances is the single biggest saving available* — and it is a
  deployment topology decision that changes no application code, since every service takes its
  datasource from `SPRING_DATASOURCE_URL`.
- **Right-sizing beats scaling.** Per `RUNBOOK_LOAD_AND_CAPACITY.md` §4.1 #9 each compose service is
  capped at `-Xmx320m`, and the Helm chart sets `requests.memory: 384Mi` / `limits.memory: 640Mi`
  with **no CPU limit at all**. Twenty services × 640 Mi is ~12.5 GiB of memory reservation before
  any traffic.

**Where growth does bite first:** WAL and logs, not table data. At 100 000 txn/day WAL (887 MB/day
before full-page writes) exceeds table growth (842 MB/day) and, with archiving on, can be several
times it. WAL retention and log retention are the two knobs that matter at scale, and neither is a
database-size question.

---

## 5. Two hard retention constraints, and one easy reclaim

### 5.1 The hash-chained audit log CANNOT be pruned (hard)

`libs/lib-audit` implements a per-aggregate SHA-256 Merkle chain (ADR-007): each row's `row_hash` is
`SHA-256(prev_hash || canonicalised(event))`, and verification walks rows in `id` order **rejecting
the chain at the first mismatch** (`HashChain.java`). Deleting any row breaks the chain for every
subsequent row of that aggregate — the audit log stops verifying, which is precisely the property it
exists to provide. The table is append-only by design ("never UPDATEd or DELETEd by application
code", `V1__audit_log.sql`).

**Consequence for sizing:** audit storage grows monotonically forever and must be budgeted as such.
Archiving to cold storage is possible only if the archived segment is kept verifiable end-to-end;
"delete rows older than N days" is not available. (Not measured here — the audit log is not on the
payment path this harness drives.)

### 5.2 The partner document vault CANNOT be deleted early (hard)

`libs/lib-vault` initialises the MinIO `gmepay-partner-vault` bucket with object lock in
**COMPLIANCE mode, 10-year retention** (`VaultBucketInitializer.java:76-79`, ADR-006) —
deliberately not GOVERNANCE, so **even bucket administrators cannot delete during retention**, plus
versioning, so every version is separately retained.

**Consequence for sizing:** KYB/onboarding document storage is a 10-year monotonic commitment with
no early-deletion escape hatch, and mistakes are permanent. Budget it as accumulating, and note that
an accidental large upload cannot be reclaimed for a decade.

### 5.3 The same event JSON is stored twice, and nothing ever deletes it (easy reclaim)

`outbox.payload` and `webhook_delivery_log.payload` are both `TEXT` holding **the same event JSON** —
once as the transactional record of intent, once as the delivery record. A repo-wide search for
retention, prune, purge or delete logic against either table in transaction-mgmt, revenue-ledger or
notification-webhook returns **nothing**: there is no retention job, no TTL, no archive step. Rows
published years ago are still there.

This is the **only easy storage reclaim** in the platform:

- Measured outbox payload alone is **1.24 KB per transaction, 47 % of all row payload written**.
- Both tables are pure operational plumbing. Once an event is published and delivered, the durable
  business record lives in `transactions` / `journals` / `ledger_entries`.
- A retention job deleting `outbox WHERE published_at < now() - interval '30 days'` reclaims roughly
  **46 % of measured per-transaction row payload** with **no** effect on the books, no audit-chain
  implication (§5.1 does not apply — the outbox is not hash-chained) and no compliance implication.
- `webhook_delivery_log` deserves the same treatment on a longer window, since partner
  dispute-resolution may want the delivery history — that window length is a business decision.

**Not built here.** It is called out because it is cheap, safe and currently absent.

---

## 6. What is measured, what is derived, what is neither

Every number in this document carries one of three labels. Nothing is presented as a measurement
that is not one.

### MEASURED — from a real run

Row counts, per-column payload widths, index definitions, and log bytes. The harness drives real
payments through the real fleet and reads the services' own databases before and after. The schemas
are the real Flyway ones (PostgreSQL DDL), so the tables, columns and indexes reflected over are the
objects a production PostgreSQL would hold.

### DERIVED — a documented model over measured inputs

Heap, index and WAL bytes. **The E2E fleet runs on H2 so it needs no Docker**, so
`pg_total_relation_size` and `pg_current_wal_lsn` do not exist in it.
`PostgresSizeModel` converts the measured row shape into PostgreSQL 16 layout using documented
storage-format constants — 23-byte tuple header, 4-byte line pointer, MAXALIGN 8, 8 KB pages,
1-vs-4-byte varlena headers, 8-byte index-tuple headers, per-index WAL insert records. It is
unit-tested (`PostgresSizeModelTest`, untagged, runs on every `gradlew build`) and expected to land
within roughly ±15 % for narrow append-only tables. WAL is the weakest part: full-page writes after a
checkpoint are a function of checkpoint configuration on an instance nobody has provisioned, which is
why WAL is quoted as a **range**.

**To convert the derived half into a measurement**, point the fleet's `SPRING_DATASOURCE_URL` at the
compose PostgreSQL instances instead of H2 and read `pg_total_relation_size` and
`pg_current_wal_lsn` directly. The harness structure already supports it; **it needs Docker**, which
is why it was not done here.

### NOT MEASURED — stated rather than guessed

| Not measured | Why |
|---|---|
| **Kafka bytes** | No broker in the harness fleet. The outbox rows are the durable record of the same events and *are* measured; Kafka's copy is additional (the earlier ~3 KB/txn estimate stands unverified). |
| **prefunding** (`ledger_entry`, `cumulative_usage_ledger`) | Not booted. The domestic wallet path in this configuration does not reach it; the authorize/confirm and cross-border paths do, and would add rows per payment. |
| **notification-webhook** (`webhook_delivery_log`) | Driven by Kafka consumption, which this fleet has none of. |
| **settlement-reconciliation** (`settlement_lines`) | Produced by a batch window, not by a payment. |
| **Audit log and document vault** | Not on the measured payment path; both carry the §5 retention constraints and need their own sizing. |
| **Cross-border / SENDMN / Nepal corridors** | Only the domestic ZeroPay wallet path was measured. Cross-border adds prefunding and FX rows. |
| **Latency, throughput, saturation** | A different question with its own harness — `RUNBOOK_LOAD_AND_CAPACITY.md`. |

**A local run is not a production forecast.** The fleet, the simulator and the harness share one
Windows host, on H2, with `-Xmx256m` JVMs. Row counts and payload widths transfer unchanged — they
are properties of the code and schema. Absolute byte and timing figures do not.

---

## 7. SLIs, and why there are no SLOs here

### 7.1 What now exists (built with this change)

Until now the platform had a Prometheus registry on all 20 deployables (T3-2) but emitted **no
business metric at all** — no approval/decline counter, no per-entry-point latency, no queue signal
(`RUNBOOK_LOAD_AND_CAPACITY.md` §7.6 and §7.7). Added:

| Metric | Type | Tags | Where |
|---|---|---|---|
| `gmepay_payment_duration_seconds` | Timer, **percentile histogram** | `entry`, `outcome` | payment-executor |
| `gmepay_payment_outcome_total` | Counter | `entry`, `outcome`, `reason` | payment-executor |
| `gmepay_outbox_pending` | Gauge (rows) | — | every service with an `outbox` table |
| `gmepay_outbox_oldest_pending_age_seconds` | Gauge (seconds) | — | every service with an `outbox` table |

- `entry` ∈ `wallet_pay` | `authorize` | `confirm` — the three money-path entry points.
- `outcome` ∈ `approved` | `declined` | `error`. **A decline is not an error**: the platform
  correctly refusing money is normal operation. 429 is classified as an **error**, because being
  throttled means offered load was not accepted — a capacity failure.
- `reason` comes from the platform's own `ApiError` codes and decline reasons, normalised and
  length-capped so tag cardinality cannot grow without bound. Nothing is tagged per partner or per
  merchant (see §7.3).
- The outbox gauges register **automatically** via `lib-errors`, on any service whose datasource has
  an `outbox` table — today transaction-mgmt, revenue-ledger, prefunding and
  settlement-reconciliation, with no per-service change. Depth *and* age, because depth alone cannot
  distinguish a draining burst from a stalled publisher.

All four were **verified against a running fleet** — scraped from `/actuator/prometheus` after 200
real payments, not merely unit-tested. "Running fleet" means the local H2-backed fleet described in
§6 on one Windows host; **nothing here has run in a deployed environment, because there isn't one**
(T1-6). Do not quote this line as "verified in production".

### 7.2 The SLO template — deliberately blank

**No latency, availability or error-budget number appears in this document, and none should be
inferred from it.** Those are commercial commitments and must come from the product/business owner,
not from an engineer with a measurement tool. `Documentation/SLO_TARGETS.properties` is the template
and ships with **every value empty**; the load harness reports `NO_TARGETS_DECLARED`, a third state
that is explicitly neither a pass nor a failure.

An owner must fill in, and put their name in `slo.owner`:

| Key | The question behind it | Now computable from |
|---|---|---|
| `slo.availability.min-success-rate` | What fraction of attempted payments must complete? | `gmepay_payment_outcome_total{outcome="approved"}` / total |
| `slo.reliability.max-error-rate` | What fraction may fail for a *platform* reason? | `…{outcome="error"}` / total |
| `slo.latency.p50/p95/p99.max-ms` | What latency does a partner get to expect? | `histogram_quantile(…, gmepay_payment_duration_seconds_bucket)` |
| `slo.throughput.min-tps` | What sustained rate must the platform carry? | `rate(gmepay_payment_outcome_total[…])` |

**Decide this first, because two of the four keys depend on it:** *does a declined payment count
against availability?* As measured, a decline is **not** a success and **not** an error. If
availability should exclude declines, declare `max-error-rate` and leave `min-success-rate`
undeclared rather than picking a number that accommodates both.

### 7.3 What the SLIs still do not give you

1. **No per-partner SLI.** Nothing is tagged by partner, so per-partner uptime and error budgets —
   what a contract is actually written against — remain unavailable. Adding the tag multiplies every
   series by the partner count and is a deliberate decision, not an oversight.
2. **Nowhere to store them.** Nothing deploys Prometheus (`RUNBOOK_MONITORING.md` §6.2), so there is
   no continuous SLI, no error-budget burn and no dashboard — only a scrapeable endpoint.
3. **No scheduler-lag metric.** The outbox gauges cover outbox drains; the other `@Scheduled` jobs
   sharing that single-thread pool still degrade invisibly.
4. **Cancel and refund are not instrumented** — only the three forward money-path entry points.

---

## 8. Re-running the measurement

No Docker, no long-running server; the harness owns its own fleet and tears it down strictly.

```bash
# Default N = 50
gradlew.bat :e2e-tests:e2eTest --tests *PerTxnFootprintE2ETest*

# The run behind this document
gradlew.bat :e2e-tests:e2eTest --tests *PerTxnFootprintE2ETest* -Dgmepay.footprint.payments=200

# Gradle may consider the task up to date; force it
gradlew.bat :e2e-tests:e2eTest --tests *PerTxnFootprintE2ETest* --rerun
```

Output: `e2e-tests/build/footprint/footprint.md` (this document's tables) and `footprint.json`
(machine-readable, `schemaVersion: 1`) — diff the JSON between releases to catch a schema change
that quietly doubles the footprint. Fleet logs land in `e2e-tests/build/footprint-logs/`.

The derived model is unit-tested without any fleet:

```bash
gradlew.bat :e2e-tests:test --tests *PostgresSizeModelTest*
gradlew.bat :services:payment-executor:test --tests *PaymentSliMetricsTest*
```

### How it works, in three sentences

The harness boots the real money-path fleet as subprocesses (the `SchemeFleet` pattern the other
E2E tests use), but starts each service with `SPRING_DATASOURCE_URL` pointing at a **file-backed H2
with `AUTO_SERVER=TRUE`**, so the test JVM can open the same database the service is writing and
count rows directly — no database server is started and the services are unaware. Five warm-up
payments absorb cold-start writes before the snapshot is taken, so one-off rows are not billed to
transaction #1. Teardown force-kills every descendant and **verifies the ports released**; ports are
also asserted free before boot, so a zombie from a previous run fails loudly instead of silently
corrupting a measurement.

The harness is `@Tag("e2e")`, so `gradlew build` and CI never reach it.

---

## 9. Cross-references

| Item | Where |
|---|---|
| Gap register (T3-5) | `Documentation/GAP_REGISTER.md` |
| Throughput, latency, and the ceiling analysis at 10× | `Documentation/RUNBOOK_LOAD_AND_CAPACITY.md` |
| Declared SLO targets (empty by default) | `Documentation/SLO_TARGETS.properties` |
| Metric exposure and per-service scrape auth | `Documentation/RUNBOOK_MONITORING.md` |
| The footprint harness | `e2e-tests/src/test/java/com/gme/pay/e2e/footprint/` |
| The derived-bytes model and its tests | `PostgresSizeModel.java`, `PostgresSizeModelTest.java` |
| Payment SLIs | `services/payment-executor/.../metrics/PaymentSliMetrics.java` |
| Outbox lag gauges (fleet-wide) | `libs/lib-errors/.../metrics/OutboxLagGauges.java` |
| Audit hash chain (§5.1) | `libs/lib-audit/.../HashChain.java`, ADR-007 |
| Document vault object lock (§5.2) | `libs/lib-vault/.../VaultBucketInitializer.java`, ADR-006 |
| Backup/PITR sizing implications | `Documentation/RUNBOOK_BACKUP_DR.md` (T3-8) |
