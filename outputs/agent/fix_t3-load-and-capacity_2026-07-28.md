> 작업: T3-5 load harness + capacity / 출처: agent

# T3-5 — load/soak harness + capacity analysis

Gap: **T3-5. No SLA measurement, no load test, no capacity plan.** Evidence:
`outputs/agent/audit_coo-ops_2026-07-28.md` §8 (no SLA/SLO definition or measurement) and §9 (no load
testing, capacity plan, or backpressure analysis).

**The harness ships unexecuted.** The fleet was deliberately not started, Docker was not started, and
no load run was performed as part of this work. Everything below was verified statically. There is no
baseline result, no 10x result, and no measured number anywhere in this repo. §4 of the runbook is
analysis by reading; it is not measurement.

---

## 0. How the gap was split

T3-5 is two problems wearing one register line, and conflating them is how a platform ends up
promising numbers nobody chose.

| Half | Verdict |
|---|---|
| **Measurement** — can we observe p50/p95/p99, throughput, error rate, saturation on the money path? | **Buildable. Built.** |
| **Targets** — what latency and availability does GMEPay+ *promise a partner*? | **A commercial commitment with contractual and regulatory consequences. Not invented. A place to declare it, and a harness that reports `NO_TARGETS_DECLARED` until someone does.** |
| **Capacity** — what breaks first at 10x? | **Buildable by reading. Done, with file/line evidence, and with an explicit "unknown without running" list rather than estimates.** |
| **The UNCERTAIN/stuck-payment ops loop** (the trailing clause of the register line) | **Untouched.** Out of scope here; still fully open. |

---

## 1. What was built

| File | What |
|---|---|
| `e2e-tests/src/test/java/com/gme/pay/e2e/load/LoadTargetGuard.java` | The safety interlock. Pure, no I/O, so it is unit-testable. |
| `.../LoadHarness.java` | `main` + open-model driver + preflight + before/after scrape + the results accumulator. |
| `.../LoadOptions.java` | `--key=value` CLI. Defaults target the `run-fleet.ps1` 18xxx band. |
| `.../Scenario.java` | The interface plus both scenarios (`WalletPay`, `AuthorizeConfirm`) and the shared HTTP plumbing. |
| `.../ResponseCodes.java` | Structured-code extraction and the decline-vs-error decision. |
| `.../Outcome.java`, `.../Latencies.java` | The four-way outcome record; exact nearest-rank percentiles. |
| `.../PrometheusSnapshot.java` | `/actuator/prometheus` parse, reduced to the saturation families, plus before/after diffing. |
| `.../SloTargets.java` | The declared-target file and the three-state verdict. |
| `.../LoadReport.java` | `result.json` (`schemaVersion 1`) + `summary.md`. |
| `.../LoadTargetGuardTest.java`, `.../SloTargetsTest.java`, `.../LoadMetricsTest.java` | 54 assertions, **untagged** — they run in the ordinary `test` task. |
| `e2e-tests/build.gradle` | `tasks.register('loadTest', JavaExec)`, +40 lines. |
| `Documentation/SLO_TARGETS.properties` | The owner's file. Every value empty. |
| `Documentation/RUNBOOK_LOAD_AND_CAPACITY.md` | Runbook + the capacity analysis (§4) + what it does not cover (§7). |
| `scripts/check_load_harness_wiring.py` | Static guard, in the style of the existing four. |

No new dependency. `e2e-tests` already had `jackson-databind`; everything else is `java.net.http` +
Java 21 virtual threads, which is the toolchain the whole repo targets.

Nothing outside `e2e-tests` / `scripts/**` / `Documentation/**` was touched. `services/**`, `libs/**`
and `apps/**` are unmodified.

---

## 2. Decisions that were not obvious

**A JavaExec `main`, not a JUnit test.** This is the mechanism that makes "never runs in CI" a
structural property rather than a promise: there is no tag to include and no class to discover, so
`gradlew build`, `integrationTest` and `:e2e-tests:e2eTest` — the three things `ci.yml` runs — cannot
reach it. The inverse arrangement applies to the guard: its tests *are* untagged, so every
`gradlew build` re-proves that the harness refuses a non-local target. The thing that must never run
automatically cannot; the thing that must always be checked always is.

**Two interlocks, both required.** A local hostname alone is not proof: this repo's own deploy plan is
a Cloudflare tunnel fronting a laptop, and an SSH forward or a `hosts` entry can put a production
ingress on `localhost`. So `--i-know-this-is-not-prod` is mandatory *in addition to* the URL check,
and a production marker (`prod`, `live`, `gmepay.com`, …) vetoes a target **even on a local host**.
Private LAN addresses are refused too — "not the internet" is not the same as "this machine". One
non-local URL aborts the whole run; there is no partial mode, because a partial run still lands real
traffic somewhere. Refusal throws rather than returning a boolean a caller could ignore.

**It refuses to start the fleet.** A generator that owned its target's lifecycle would time cold JIT
and Flyway migrations as if they were payment latency, and would make "the fleet was already unhealthy"
indistinguishable from "the load broke it". So `loadTest` has **no** `bootJar` dependency (the guard
script fails if one appears) and the preflight prints the exact `run-fleet.ps1` command instead.

**Open model with explicit shedding, not sleep-between-requests.** A closed model's offered rate
silently drops as the system slows, hiding exactly the degradation being looked for. Arrivals are paced
from a monotonic baseline; when the concurrency cap is full the arrival is **shed and counted**, never
queued — a queued arrival would add client-side waiting to the next request's latency (coordinated
omission) and report the harness's own backlog as the platform's. A non-zero shed count gets a call-out
box in the summary, because it means the percentiles describe a lighter load than requested.

**Decline ≠ error, and the asymmetries are deliberate.** The platform's structured codes are what make
this possible at all; before them a report could only say "18% non-2xx", which conflates the platform
correctly refusing money with the platform falling over. A structured 4xx (`TRANSACTION_LIMIT_EXCEEDED`,
`SCHEME_CLOSED`, `SCHEME_OPERATION_UNSUPPORTED`, `MERCHANT_INACTIVE`) is a decline. But an
**unstructured** 4xx is an ERROR — calling it a decline would make a broken run look healthy — and
**429 is an ERROR named `RATE_LIMITED`**, because being throttled means the platform could not take the
offered load, which is the finding. Classification keys on envelope *shape*, not an enumerated list, so
codes added later tally with no harness change; the runbook notes the T5-3 `*_NOT_SCREENED` case
explicitly, including that a non-blocking AML seam produces **OK** here and its unscreened count must
be read from T5-3's own counter, not from this tally.

**Errors are counted, not timed.** A 3 ms `CONNECTION_REFUSED` in the percentiles would pull p50 down
and make a collapsing run look fast. Declines *are* timed — a declined payment is a completed round
trip the caller waited for.

**Exact nearest-rank percentiles.** A few thousand `long`s is nothing; a t-digest would add a
dependency and an approximation error to save memory nobody needs. Nearest-rank (no interpolation)
means a reported p99 is a latency some request really had — an interpolated p99 can be a number no
request ever experienced, which is a poor basis for a contract.

**Nothing is 0 when it means "not measured".** NaN renders as `n/a` in text and `null` in JSON. Same
rule in the SLO block: an empty target file yields `NO_TARGETS_DECLARED`, not `PASS`.

**It scrapes rather than instruments.** T3-2 already put a real Micrometer registry on all 20
deployables and exposed `/actuator/prometheus` fleet-wide; measuring heap and pool depth client-side
would produce a second, disagreeing set of numbers. Scraping means the load report and the production
dashboard read the same series. A failed scrape is recorded, never fatal — per `RUNBOOK_MONITORING.md`
§1.3 a 401 means "wrong token" while a connection failure on a service that just passed preflight is
itself a finding, and aborting a run that has already generated real load would be worse than reporting
both.

**A fresh `partner_txn_ref` per iteration.** `POST /v1/payments/authorize` is idempotent per
`(partner, partner_txn_ref)` (`PaymentController.java:120-136`), so a reused ref would replay the first
authorization and the run would report excellent latencies for a primary-key lookup.

**`.properties`, not YAML/JSON, for the target file.** It needs `#` comments (the placeholder is mostly
explanation) and zero dependencies; neither SnakeYAML nor a JSON-with-comments parser is worth adding
for six numbers.

---

## 3. Why the SLO targets are empty

`Documentation/SLO_TARGETS.properties` ships with every value commented out, and that is the
deliverable, not an omission. The COO audit's §8 finding is that partner contracts will promise
availability the platform cannot measure; filling this file with plausible-looking numbers would
convert that into the worse version — a contract promising numbers nobody chose. So the harness has a
**third state**: `NO_TARGETS_DECLARED`, neither pass nor fail, with the summary saying in words that
this is not a pass.

Behaviour, all deliberate: partially-filled is honoured (only declared keys are evaluated, and the
report lists the rest); a key present but blank counts as undeclared, so a half-finished edit cannot
silently become `0` and fail every run; a declared target the run could not measure is `undecidable`
and **FAILS**, because an undecidable target is exactly what this gap is about; a non-numeric value is
a loud error naming the key. Pinned by `SloTargetsTest#shippedPlaceholderDeclaresNothing` and by the
guard script, which fails on any uncommented value and tells the reader to update both the guard and
the register entry in the same commit if the numbers are a real, owned declaration.

**What an owner must declare** (runbook §6): `slo.availability.min-success-rate`,
`slo.reliability.max-error-rate`, `slo.latency.p50/p95/p99.max-ms`, `slo.throughput.min-tps`, plus
`slo.owner` — a number with no owner is not a commitment, and the harness prints
"slo.owner NOT set — nobody owns these numbers" until it is set.

**And one prior question that two of those four depend on: does a declined payment count against
availability?** As measured, a structured decline is not a success (the payment did not complete) but
is not an error either (the platform behaved correctly). If availability should exclude declines,
declare `max-error-rate` and leave `min-success-rate` undeclared — rather than papering over the
question with a number that accommodates both.

---

## 4. Capacity: what breaks first at 10x

Full table with citations in `Documentation/RUNBOOK_LOAD_AND_CAPACITY.md` §4. Summary here.

The framing fact: **every service ships at exactly one replica** (`deploy/helm/gmepay/values.yaml:59`
`defaultReplicas: 1`, `templates/_deployment.tpl:61` falls back to it, no service in any of the four
values files overrides it, no HPA and no PDB anywhere in `deploy/**`). So absorbing 10x means scaling
out — and §4.2 is the list of things that *break when you do*.

### 4.1 Binding soonest at 10x volume

| # | Ceiling | Key evidence | Why first |
|---|---|---|---|
| 1 | **Kafka: 1 partition × concurrency 1** | `docker-compose.yml:332` sets `KAFKA_AUTO_CREATE_TOPICS_ENABLE` but **never** `KAFKA_NUM_PARTITIONS` → broker default 1; no `NewTopic`/`TopicBuilder` anywhere; `.smoke/infra-up.sh:24` uses `--partitions 1`. All four consumer factories built bare (Spring default concurrency 1) with `ENABLE_AUTO_COMMIT=false` + `AckMode.MANUAL` + `FixedBackOff(0L, 2)`: `WebhookKafkaConsumerConfig.java:66,98-108`, `RevenueLedgerKafkaConsumerConfig.java:94-104`, `PrefundingKafkaConsumerConfig.java:84-94`, `OpsAlertKafkaConsumerConfig.java:85-95`. `spring.kafka.listener.concurrency` set in **no** file. | One thread per group must finish its DB work per record before the next poll, and each failure burns 3 synchronous attempts. **Adding replicas adds only idle consumers** — with one partition the extras get no assignment. Fixing it is a topic repartition, i.e. an operational migration. |
| 2 | **prefunding: per-partner exclusive row lock + 4 aggregate reads on every transaction** | `PrefundingService.java:300` `lockOrThrow` → `PartnerBalanceRepository.java:19` `@Lock(PESSIMISTIC_WRITE)`; then `:320` ref lookup, `:330-332` `sumDaily/sumMonthly/sumAnnual`, `:339` `netDailyCount`, `:347` save. `CumulativeUsageLedgerRepository.java:21-40` — `SUM(amountUsd)` over an **append-only** table. Same lock on reserve/capture/release (`PrefundingInternalController.java:22,101`) and reverse (`:381,392-396`). | **Serialises all of one partner's traffic**: per-partner throughput = 1/(lock hold time), held across four aggregate queries. The annual `SUM` scans a year of that partner's rows, so **the same load is slower in December than in January**. GMEPay+'s volume is concentrated in a few partners, so this bites before any global limit. |
| 3 | **Scheme bulkhead: 16 concurrent, max-wait 0** | `payment-executor/application.properties:130-131`; applied in `ResilientSchemeClient.java:145-150` (bulkhead outside breaker). Scheme read timeout 5 s at `:136-137`. | The 17th concurrent scheme call is rejected immediately, not queued → theoretical ~3.2 confirms/s per scheme at full latency. The sharpest *numeric* ceiling, and the only service with resilience4j at all. |
| 4 | **Gateway: 50 payments/s per partner, per replica, in memory** | `RateLimitFilter.java:55,84-88,117-118`; `RateLimitProperties.java:38,41,44`; `api-gateway/application.yml:104-113` states the per-replica multiplication itself; `fail-open: false`. | A hard 429 wall at the edge, reported as `RATE_LIMITED` errors. Raising it means a bigger number with no distributed store behind it, or the T0-7 fix. |
| 5 | **`@Scheduled` pool size 1 per service; 3–7 jobs stacked on it** | `spring.task.scheduling.pool.size` set in **no** file → Spring default 1. transaction-mgmt: `OutboxPublisher.java:60` (**1 s**), `ExpirySweeperService.java:93` (10 s), `StuckTransactionAlertSweeper.java:106` (60 s). settlement-reconciliation: 7 jobs. reporting-compliance: `BokReportScheduler.java:107` and `KofiuFeedScheduler.java:101` on the **same second**. zeropay: 6 crons, with `ZeroPayBatchScheduler.java:82` documenting the shared pool. payment-executor: 5 jobs. | At 10x the 1-second outbox tick lengthens and, sharing the single thread, **starves the 10 s expiry sweeper and the stuck-transaction alerter** — the very safety nets T3-3 armed stop firing exactly when volume makes them matter. Silent: **no scheduler-lag metric exists**. |
| 6 | **Webhook drain does not close even at 1x** | `WebhookDispatcher.java:87` `batch-size:200`, `:104-107` `fixedDelay 30000`; `RestWebhookHttpClient.java:49-50` read timeout 10 s. | 200 sequential deliveries × up to 10 s = up to **2000 s** of work on a 30 s interval, on the single thread of #5. One slow partner endpoint stalls every other partner's webhooks. |
| 7 | **No read timeout on any inter-service money-path client** | `payment-executor/.../ClientBeans.java:43-46` sets only `new JdkClientHttpRequestFactory()`; `RestClientSupport.java:23-27` adds only a converter. Inherited by the float debit, the per-txn limit read, rate, txn, ledger, QR and router clients. Same in `smart-router` (`RestPartnerSchemeResolver.java:58`, `RestPartnerSchemeRegistry.java:63`, `RestSchemeOperatingHoursSource.java:57`), `ops-partner-bff` (`ClientBeans.java:38-41`), and **every scheme adapter's outbound leg** (`ZeroPaySchemeApiClient.java:52`, `NepalSchemeApiClient.java:62`, `NinepayApiClient.java:78`, `SendmnSchemeApiClient.java`). No retry policy on any hop. | A slow peer holds a Tomcat worker until the OS closes the socket. The 2 s/5 s discipline exists on hub→adapter but **not adapter→scheme**, so an adapter can outlive payment-executor's 5 s read timeout — which **manufactures `UNCERTAIN` transactions under load**, i.e. load turns into manual ops work. |
| 8 | **HikariCP at its default 10 everywhere; `hikari` configured literally nowhere** | Repo-wide search for `hikari` across `services/*/src/main/resources/application*`, `docker-compose.yml`, `deploy/helm/**` and all Java: **nothing**. 15 services declare a JDBC datasource. Defaults: pool 10, min-idle 10, connection-timeout 30 s, leak detection off. `run-fleet.ps1:337` overrides to 5/1 locally only. | **Corrects the audit's guess.** Not connection *exhaustion*: databases are one-per-service (`docker-compose.yml:9-10`, 15 separate instances; `values-aws.yaml:107-121` / `values-azure.yaml:109-123` keep that posture), so at 1 replica × 10 there is large headroom against postgres's default `max_connections=100`. The ceiling is **pool saturation inside one service** — 10 concurrent DB ops, then callers queue up to 30 s and fail (`ci.yml:103` already records a 30 s Hikari timeout in CI). Watch `hikaricp_connections_pending`. |
| 9 | **`-Xmx320m -XX:+ExitOnOutOfMemoryError` against unbounded maps** | `docker-compose.yml` (19 services). Unbounded: `InMemoryRateLimitStore.java:32,66` (sweeps only *after* 50 000 keys), `IpAllowlistCache.java:39,49` ("never evicted", read on every partner request), `WebClientRbacClaimResolver.java:12,15`, `RbacResolutionService.java:47,58`, `PartnerDirectory.java:68-76`, `OpsPagingDispatcher.java:53`. | The JVM **dies rather than degrades**. In Kubernetes it is worse and **unknown**: Helm sets no `JAVA_TOOL_OPTIONS`, so the heap defaults to ~25% of the 640 Mi limit (`values.yaml:63-68`) ≈ 160 Mi. |
| 10 | **auth-identity nonce store: O(n) scan per authenticated request** | `auth-identity/.../InMemoryNonceStore.java:18,26` — unbounded map with `entrySet().removeIf(...)` on **every** `checkAndSet`; its own javadoc `:10` says "local development and unit tests only". | **Quadratic** in offered load, in a class the code already labels not production-grade. |

### 4.2 What breaks when you scale out (T0-7 and neighbours)

| Breaks | Evidence | With N replicas |
|---|---|---|
| **Rate limiting** | `api-gateway/.../ratelimit/InMemoryRateLimitStore.java:28,32` — the **only** `RateLimitStore`, `@Primary`, `matchIfMissing = true` | cap becomes **N × 50** payments/s per partner; the limit stops being a limit |
| **Replay protection** | `api-gateway/.../replay/InMemoryNonceStore.java:26,30,32` — only impl, `@Primary`, `matchIfMissing = true`; `application.yml:97-102` states a captured request "can be replayed once per replica inside the 5-minute window" | a signed request is replayable **N times** — a money-path *integrity* defect, not a performance one |
| **Idempotency (transaction-mgmt)** | `InMemoryIdempotencyStore.java:22`; `application.properties:33-35` — the Redis store activates only when `spring.data.redis.host` exists, and `docker-compose.yml:1171` sets it on **api-gateway only** | the 24 h window is per replica → the same retry creates N transactions |
| **Schedulers without ShedLock** | **only** prefunding has it (`build.gradle:38-39`, `OutboxConfig.java:34`, `OutboxPublisher.java:50`); transaction-mgmt declares a config (`ShedLockConfig.java:14`) but revenue-ledger's and settlement-reconciliation's publishers have none; `WebhookDispatcher.java:44` says "a distributed lock is a follow-up" | duplicate webhooks, duplicate settlement files, duplicate ZeroPay batches. **This is the real reason ceilings #1 and #6 cannot be fixed by adding replicas.** |
| **Ops paging** | `values.yaml:756-757` documents single-replica-only, "arming it under >1 replica would double-page"; `OpsPagingDispatcher.java:53` de-dup map is per-JVM | pins ops-partner-bff to 1 replica |
| **Aggregate ops alert view** | `OpsAlertStore.java:35,40-51` — 200-entry `ArrayDeque` under one `synchronized`; `values.yaml:752` calls it "STILL OPEN (not fixable from a values file)" | at 10x, 200 entries covers minutes; the mutex is contended by consume *and* every read; lost on restart |
| **Operating-hours alert de-dup** | `SchemeOperatingHoursGate.java:96` `unverifiedAlerted` is per-instance | N `SCHEME_HOURS_UNVERIFIED` alerts per scheme per day |

### 4.3 The 10-minute operating-hours cache, precisely

TTL **600 000 ms** (`RestSchemeOperatingHoursClient.java:72`, shipped at
`payment-executor/application.properties:67`), stored in a per-instance `ConcurrentHashMap` keyed by
scheme with no size cap (`:66`). Read on the hot path at `:97-101` via `OperationalGate.java:87,114,126-128`
— the single call **both** `POST /v1/payments/authorize` and the wallet `POST /v1/pay` make — and
additionally **per resolved failover candidate** in `FailoverPaymentRouter.java:257,366`, so one
cross-border payment can hit it several times. Miss costs 500 ms/500 ms
(`RestSchemeOperatingHoursClient.java:73-74`); on failure `degraded()` (`:125`) serves last-known-good
or UNVERIFIED, which permits. Memory is not a ceiling (~9 schemes). The real risk is **cold-miss
stampede**: N replicas × 9 schemes every 10 min, and every restart is a synchronous miss on the pay
path. **Separately: `smart-router/.../RestSchemeOperatingHoursSource.java:56` is a second copy with
the same TTL and its own map, built via a bare `RestClient.builder().baseUrl(...).build()` (`:57`) —
no connect or read timeout at all** — called from `LocationSchemeResolver.java:233-247,261`. A hung
config-registry hangs the resolve path unboundedly.

### 4.4 Unknown without running (stated, not estimated)

1. **In-flight limit of every inter-service HTTP client** — no pool config anywhere, no httpclient5/OkHttp on any classpath, `jdk.httpclient.connectionPoolSize` unset, so the JDK pool is effectively unbounded per destination and the practical limit is the caller's thread count (itself unset — see 2). Only ceiling #3 is a real number.
2. **Tomcat's actual thread ceiling** — `server.tomcat.threads.max` set in no file → default 200; `run-fleet.ps1:337` uses 20. Which a real deployment gets, and whether 200 threads fit in the heap of #9, cannot be derived statically.
3. **The heap Helm actually gives a JVM** — no `JAVA_TOOL_OPTIONS` in any values file; only `requests.memory: 384Mi` / `limits.memory: 640Mi` (`values.yaml:63-68`), with **no `limits.cpu` at all**.
4. **PostgreSQL `max_connections` in production** — configured nowhere; `values.yaml:20-33` ships no datastore.
5. **GC behaviour under sustained load** — `-XX:+UseSerialGC` is the E2E launcher's choice only, not compose's or Helm's; collector, pause distribution and allocation rate are what a soak is for.
6. **Whether #2's annual `SUM` is binding or merely contributing** — depends on rows-per-partner-per-year, which only a real volume profile answers.

---

## 5. Verification performed (static only)

| Check | Result |
|---|---|
| `gradlew testClasses` (whole repo) | **BUILD SUCCESSFUL**, 101 tasks |
| `gradlew :e2e-tests:test` | **54 tests, 0 failures, 0 errors** |
| Guard refuses a non-local target | exit **3**, both reasons printed, **no socket opened** (`--payment-executor-url=https://api.gmepay.com`) |
| Preflight with no fleet running | exit **4**, prints the `run-fleet.ps1` command, nothing sent |
| `loadTest` with no `--args` | prints usage, exit 0 |
| `python scripts/check_load_harness_wiring.py` | **OK** |
| `check_internal_auth_wiring.py` / `check_monitoring_wiring.py` / `check_helm_chart_wiring.py` / `check_gitleaks_config.py` | **all PASS** |
| `python -m py_compile` on the new script | clean |

**Not performed, deliberately:** the fleet was not started, Docker was not started, and **the load test
was not run**. No `result.json` exists.

---

## 6. Still open

1. **The harness has never been run.** No baseline, no 10x run, no published result. This is the honest headline.
2. **A local run is not a production forecast** — fleet, simulators and harness share one host's CPU/disk/page-cache, with 256–320 MB heaps and (locally) Tomcat 20 / Hikari 5. Good for relative comparison only. There is no always-on environment to run it against — **T3-1**.
3. **Scheme calls hit simulators.** A p99 against `sim-scheme` says nothing about KFTC.
4. **No per-partner SLI.** The harness drives one partner code; per-partner uptime/latency and an error budget are what a contract is written against and none exists.
5. **No continuous measurement.** Point-in-time, hand-started. Nowhere for a continuous SLI to live until Prometheus is deployed (T3-2 §6.2).
6. **No business metrics** to measure against (T3-2 §6.6); this harness computes approval/decline rates client-side, which is fine for a load run and useless for production monitoring.
7. **No scheduler-lag metric**, so the starvation §4.1 #5 predicts is invisible even with Prometheus.
8. **Baseline-vs-10x is not scripted** and no baseline JSON is committed.
9. **Soak is unproven** — `--duration=8h` works by construction; nothing has run long enough to find the leaks §4.1 #9 predicts.
10. **No backpressure design**, only observation: nothing in the platform sheds load deliberately, queues fairly, or degrades gracefully. No admission control on the money path.
11. **`apps/**` is not exercised** — API-level only.
12. **The UNCERTAIN/stuck-payment ops loop** (the trailing clause of the T3-5 register line) is untouched by this work.
13. **No SLO target is declared**, by design. Until an owner fills in `Documentation/SLO_TARGETS.properties` and names themselves in `slo.owner`, no run can pass or fail — and **no availability or latency commitment should be signed**.

### Follow-ups in files this agent must not touch

Recorded rather than fixed (`services/payment-executor`, `libs/lib-kyb`, `libs/lib-api-contracts` are
concurrently owned; `services/**` was out of scope):

- **`smart-router/.../RestSchemeOperatingHoursSource.java:57`** — add connect/read timeouts; it is currently unbounded on the resolve path (§4.3).
- **`payment-executor/.../client/rest/ClientBeans.java:43-46`** — set connect/read timeouts on the shared `RestClient`; and give every scheme adapter's outbound leg a read timeout **shorter** than payment-executor's 5 s, so an adapter cannot outlive its caller and manufacture `UNCERTAIN` rows (§4.1 #7).
- **`spring.task.scheduling.pool.size`** — size it per service, or at minimum move transaction-mgmt's 1-second `OutboxPublisher` off the thread shared with the T3-3 safety-net sweepers (§4.1 #5).
- **Kafka partitions** — set `KAFKA_NUM_PARTITIONS` and `spring.kafka.listener.concurrency`; until then extra replicas are idle consumers (§4.1 #1).
- **ShedLock** on revenue-ledger / settlement-reconciliation outbox publishers, `WebhookDispatcher`, and the zeropay batch crons — this, not throughput, is what caps those services at one replica (§4.2).
- **A scheduler-lag metric** (`@Scheduled` last-run age / overrun) so §4.1 #5 stops being invisible.
- **`InMemoryNonceStore` (auth-identity)** — the O(n)-per-request scan (§4.1 #10).
