> 작업: T3-11 concrete capacity defects / 출처: agent

# T3-11 — the five concrete capacity defects

Gap: **T3-11. Concrete capacity defects found by the T3-5 analysis.** Evidence with file:line in
`outputs/agent/fix_t3-load-and-capacity_2026-07-28.md` §4, from a real 200-payment run.

> **SECOND PASS — read §9 first.** Everything from `§0` to `§8` is the first pass. A later
> verification pass re-derived each claim from the code and found **three of the five verdicts below
> were wrong**, in the same direction each time: a fix had been written, proved by a test that could
> not see the gap, and reported as closed. §9 lists what was actually still broken and what was done
> about it. The original text is left intact rather than edited into looking right, because *how* each
> claim came to be believed is the useful part.

**Headline (first pass):** four of the five are closed in code. Kafka is half-closed, and the remaining
half is an operational migration rather than anything a config change can do. The webhook drain's
ceiling is raised ~16× but its underlying design defect is still open and is described rather than
papered over.

**One previously-unknown bug was found while proving the timeout fix**, and it was worse than the gap
it was found under — see §1.2.

Nothing was run against a live fleet. Docker was not started, no server was started. Everything below
was proved by tests, several of which drive a real unresponsive TCP socket and a real H2 + Flyway
database.

---

## 0. Scoreboard

First-pass verdict, and what the second pass found (§9):

| # | Defect | First-pass verdict | Second pass |
|---|---|---|---|
| 1 | Money-path HTTP clients have no read timeouts | **CLOSED** — fleet-wide floor + per-hop nesting + a real bug fixed underneath it | **WRONG.** The "fleet-wide floor" is a `RestClientCustomizer`, which Boot applies only to the `RestClient.Builder` **bean**. 29 clients build from the **static** `RestClient.builder()` factory and never saw it. 4 in scope fixed; 25 out of scope, now a guarded baseline. §9.1 |
| 2 | `@Scheduled` pool size 1, 3–7 jobs stacked | **CLOSED** — sized per service, and a lag metric so it can never be silent again | **DRIFTED.** payment-executor had grown to 7 jobs against a pool of 6; zeropay 6 against 3; prefunding and rate-fx were never sized at all. Fixed + a fleet guard. §9.2 |
| 3 | Schedulers without ShedLock | **CLOSED in scope** (4 services) — `revenue-ledger` is a named follow-up | **Confirmed, and the follow-up is done** — revenue-ledger was locked in a later commit (V007). §9.5 |
| 4 | Kafka 1 partition × concurrency 1 | **HALF** — concurrency is now real and configurable; repartitioning is an ops migration | **Confirmed.** All four factories now read the property; alignment with the broker's partition count is now asserted from compose rather than restated. Repartitioning is still a migration. §9.4 |
| 5 | Webhook drain does not keep up at 1× | **PARTIAL** — ~16× more headroom | **WRONG — it still did not keep up at 1×.** "16×" was a multiple, never a rate; the rate was 17.8 deliveries/s against an admission ceiling of 50 payments/s. Now 100/s. §9.3 |

---

## 1. Timeouts (defect 1)

### 1.1 The shape of the fix

The defect was not "one client is missing a timeout". It was that **no** inter-service client had a
read timeout, because they all take Boot's shared `RestClient.Builder`, which carries none. A
per-class fix is a fix that ends up 95% applied, and the one hop nobody remembered is the one that
hangs. So the floor is installed where it cannot be reached by omission:

`libs/lib-errors/.../http/HttpClientTimeoutAutoConfiguration.java` registers a `RestClientCustomizer`
for every service in the fleet — the same ride-along pattern the trace, RBAC, correlation and
outbox-lag auto-configurations already use.

Two details are load-bearing:

- **`Ordered.LOWEST_PRECEDENCE`.** payment-executor and ops-partner-bff already registered a
  customizer that installs a bare `JdkClientHttpRequestFactory` for PATCH support. Customizers that
  both call `builder.requestFactory(..)` are a race decided by bean ordering, and the loser is
  discarded silently — the failure mode is not an error but an unbounded money-path client that looks
  configured. Running last makes the floor un-removable by accident. payment-executor's competing
  customizer was **deleted** rather than reordered, so there is one mechanism, not two.
- **The JDK transport is named, not detected.** `HttpClientTimeouts.requestFactory(..)` builds a
  `JdkClientHttpRequestFactory` explicitly, which preserves PATCH (`HttpURLConnection` rejects the
  verb, and `PATCH /v1/transactions/{ref}/status` is on the money path) and makes the timeout's
  exception type deterministic. See §1.2 for why that second property turned out to matter a lot.

### 1.2 The bug found underneath

`RestSchemeClient` and four siblings already set 2 s/5 s timeouts via Boot's
`ClientHttpRequestFactories.get(settings)`. That helper picks a transport by **classpath scan**, and
with no Apache/Jetty/Reactor client present it falls back to `SimpleClientHttpRequestFactory`
(`HttpURLConnection`). Driven against a real socket that accepts and then answers nothing, the read
timeout surfaced as a **`RestClientException` from body extraction**, not the `ResourceAccessException`
the catch block expects.

The consequence is a money-visibility defect, not a cosmetic one:

```
ResourceAccessException  → SchemeTimeoutException → orchestrator commits UNCERTAIN, hold retained
RestClientException      → PaymentException       → NOT caught by PaymentOrchestrator
```

So a hung scheme did not merely *manufacture* an `UNCERTAIN` row, as T3-11 assumed. **It wrote no row
at all.** The payment kept whatever status it had and disappeared from the ops queue that exists to
catch exactly this.

All five clients (`RestSchemeClient`, `NepalRestSchemeClient`, `SendmnRestSchemeClient`,
`RestOperationalStatusClient`, `RestSchemeOperatingHoursClient`) now name the JDK transport, and
`InternalHttpTimeoutTest#hungSchemeSubmitBecomesTimeoutAndIsNotRetried` pins the exception type.

This was invisible to every existing test because they all use `MockRestServiceServer`, which never
times out.

### 1.3 The numbers, and why each one

Values are engineering parameters. None is a service-level objective and no partner is promised any of
them; all are properties precisely so a hop with a real, owned budget can declare it.

| Hop | connect | read | Reasoning |
|---|---|---|---|
| Anything not named below (fleet floor) | 2 s | **10 s** | Deliberately the loosest value, because it applies to hops nobody has considered individually — reports, admin reads, batch. Its job is to convert *unbounded* into *bounded*. A tight global default would start failing legitimately slow non-money calls on day one, and the pressure to raise it back would land on the money path too. |
| payment-executor → internal (rate, txn, prefunding, qr, ledger, router) | 2 s | **5 s** | Every hop here is a step inside one live payment, and the payment's own ceiling is the scheme leg's 5 s. An internal hop permitted 10 s could outlive the payment it belongs to. Not tighter, because a timeout on `prefunding.capture` or `transaction.commitStatus` creates an *unknown* that becomes reconciliation work. |
| payment-executor → adapter (scheme leg) | 2 s | 5 s | Unchanged; it is the outer bound the others nest inside. |
| adapter → scheme (ZeroPay / Nepal / SendMN / 9Pay) | 2 s | **4 s** | Strictly inside the caller's 5 s. If the inner leg were the looser one, payment-executor would abandon first and infer "unknown" from its own socket timeout. At 4 s the adapter gives up first and answers a definite `SCHEME_UNAVAILABLE` that carries a reason. |
| zeropay → transaction-mgmt (batch enrichment) | 2 s | **30 s** | The one deliberate exception. Not on the live payment path: a nightly window reading a business day of refunds and committed FX. A live-payment budget here would make the 02:00 run fail on a healthy-but-slow day-range query. Still bounded, and every method already degrades to an empty map. |
| config-registry reads (operating hours, ops status) | 0.5 s | 0.5 s | Pre-existing and correct — an internal reference-data read with a documented degraded path. |
| webhook → partner endpoint | 5 s | **5 s** (was 10 s) | See §5. |

**The nesting rule is asserted, not just written down.**
`InternalHttpTimeoutTest#shippedTimeoutsNestInsideTheSchemeBudget` reads the shipped
`application.properties` and fails if the internal budget ever exceeds the scheme budget;
`SchemeOutboundTimeoutTest#shippedOutboundBudgetNestsInsideTheCallersBudget` does the same on the
adapter side. If someone raises one "just for one slow report", a test fails.

### 1.4 The anti-double-charge contract (ADR-016 §4) is intact

Explicitly checked, and asserted:

- A read timeout produces `ResourceAccessException`, which every money-path client already maps to the
  **technical/ambiguous** outcome (`SchemeTimeoutException`, `SCHEME_UNAVAILABLE`,
  `NinepayTransportException` "AMBIGUOUS: 9Pay may have executed the request", SendMN's documented
  "poll PaymentStatus, never blind-retry").
- No retry was added anywhere. Two tests count the requests the fake unresponsive scheme actually
  received and assert **exactly one**: `InternalHttpTimeoutTest` (submit) and
  `SchemeOutboundTimeoutTest` (commit).
- Shortening a timeout *increases* how often the probe path is taken, which is why the values above
  are bounds on the ambiguity window rather than aggressive fail-fast numbers.

### 1.5 A trap this created, and how it is contained

`MockRestServiceServer.bindTo(builder)` works by **installing a request factory**. A production
constructor that overwrites the factory after binding silently detaches the test from the mock and
starts opening real sockets. Two existing tests hit this immediately (`NepalSchemeAdapterTest`,
`NinepayVerifyResponsesDefaultTest`).

Resolution: the scheme adapters take their outbound budget from **configuration**
(`gmepay.http.client.read-timeout=4s` in each adapter's own config file, with the reasoning stated
there) rather than from a per-client `.requestFactory(..)` call. Constructors are untouched, the mock
seam is undisturbed, and the value is still reviewable in one obvious place. Where a per-hop override
genuinely is needed (zeropay's enrichment port), it is set on a client whose test uses a pre-built
`RestClient`, and the hazard is documented in the code.

---

## 2. Scheduler pool + the lag metric (defect 2)

`spring.task.scheduling.pool.size` was set in **no** file in the repository, so Spring's default of one
thread applied everywhere.

| Service | Jobs | Pool | The specific starvation |
|---|---|---|---|
| transaction-mgmt | 3 | **4** | The runbook's named case: the 1 s outbox tick lengthens under load and, holding the only thread, stops the 10 s expiry sweeper and the 60 s stuck-transaction alerter — the T3-3 safety nets go quiet exactly when volume makes them matter. |
| payment-executor | 4 | **4** | A replay sweep stalled on an unresponsive revenue-ledger holds the only thread, leaving partner float reserved against authorizations that expired hours ago. |
| settlement-reconciliation | 7 | **4** | 4, not 7: the three generation windows are hours apart and cannot contend, so real demand is the always-on outbox drain + at most one cron + the heartbeat, with one spare. |
| notification-webhook | 2 | **3** | The worst pairing in the fleet: the drain is the job that blocks for minutes on partner endpoints, and the backlog monitor is the job whose entire purpose is to alert that the drain is behind. |
| scheme-adapter-zeropay | 6 | **3** | ZP0011/ZP0021 and ZP0065/ZP0066 are two minutes apart; on one thread a slow ZP0065 simply postpones ZP0066. |

These are latency-insensitive background jobs; a thread is a rounding error against the heap. This is a
correctness lever, not a throughput one, because the starved jobs are the safety nets.

**The metric.** `libs/lib-errors/.../metrics/SchedulerLagProbe.java` registers one extra fixed-rate task
on the *same* registrar every `@Scheduled` method uses, and records how late it starts against a
monotonic baseline (`System.nanoTime`, so an NTP step cannot masquerade as a stall). Five gauges on the
T3-2 Micrometer registry:

| Meter | Meaning |
|---|---|
| `gmepay.scheduler.lag` | How late the last heartbeat was. **The one to alert on.** |
| `gmepay.scheduler.lag.max` | High-water since start — a starvation episode shorter than a scrape interval is invisible to the instantaneous gauge. |
| `gmepay.scheduler.pool.size` | `corePoolSize`, so "pool size 1" is a dashboard fact rather than an unnoticed default. |
| `gmepay.scheduler.active` / `.queued` | Saturation and backlog. |

It attaches via `SchedulingConfigurer`, which only exists where `@EnableScheduling` does — so it lands
on exactly the services that have a pool to starve and contributes nothing anywhere else. Pool gauges
read `NaN`, never `0`, when the executor is not visible: "I cannot see the pool" and "the pool is
empty" are different facts.

**Proof, not assertion.** `SchedulerLagProbeTest` boots a real Spring context with two real
`@Scheduled` jobs — one that blocks, one that must keep firing — and shows the sibling **never runs**
at `pool.size=1` and **does** run at `pool.size=2`. The lag gauge is shown to rise on a stall, recover,
and keep its high-water mark.

---

## 3. ShedLock (defect 3)

Added to four services, each mirroring prefunding's working pattern exactly (same provider, same
`usingDbTime()`, same table shape) — three subtly different lock implementations across one fleet is
how one of them ends up wrong.

| Service | Migration | Jobs locked | What a second replica did before |
|---|---|---|---|
| settlement-reconciliation | **V014** | 7 | Three of them **generate and transmit settlement files to a scheme**. Duplicate settlement instructions between institutions. |
| scheme-adapter-zeropay | **V005** | 6 | Every ZP00xx window generates *and transfers* a file. ZeroPay receives each file twice; `zp_batch_files` holds two GENERATED rows per business date, so even the audit trail is ambiguous. |
| notification-webhook | **V008** | 2 | Duplicate webhooks — an instruction a partner's ledger and fulfilment act on. Plus N pages per incident from the backlog monitor, whose de-dup is per-JVM. |
| payment-executor | **V011** | 4 | `AuthorizationExpirySweeper` **releases prefunding holds**; two replicas credit a partner's float twice for one authorization. `RevenuePostingReplayScheduler` spends a bounded retry budget N times faster and can burn a row to POISON over one outage. |

`usingDbTime()` everywhere: lock expiry follows the **database** clock. Cron work is where wall-clock
skew does damage — two pods seconds apart would disagree about whether the 05:00 lock had lapsed, and
the fast one would start a second settlement run while the first was still writing its file.

`lockAtMostFor` is sized **long** (1 h for file-transmitting windows), deliberately. It is a crash
safety net, not a runtime budget, and expiring *early* admits the concurrent second run the lock
exists to prevent. A genuinely crashed window is recovered by the T3-4 re-run tooling.

**Corrections to the T3-5 note:** transaction-mgmt does not merely "declare a config" — all three of
its jobs were already fully `@SchedulerLock`ed. And idempotent jobs (`OpsAlertRetentionSweeper`) are
locked too, because "is this one safe unlocked?" should not be a judgement someone re-makes each time
a job body changes.

**Enforcement, not a one-off.** Each service has a reflection test that enumerates every `@Scheduled`
method and fails if one lacks a uniquely-named `@SchedulerLock` — a job added later without a lock
fails the build. Plus a behavioural test per service: a real H2 database with the **full Flyway
migration set applied** (so the new migration is proven to apply in sequence and to produce the table
ShedLock expects), then acquire → second acquire refused → unlock → acquire succeeds.

---

## 4. Kafka (defect 4) — and why the rest is a migration

**Done:**

- `docker-compose.yml`: `KAFKA_NUM_PARTITIONS: "3"` (never set before → broker default 1) and an
  explicit `KAFKA_DEFAULT_REPLICATION_FACTOR: "1"`, stated so the 1 is a decision about a
  single-broker dev stack rather than an unnoticed default someone copies to a real cluster.
- `deploy/helm/gmepay/values.yaml`: `SPRING_KAFKA_LISTENER_CONCURRENCY: "3"` in the ABI ConfigMap
  (merges into all three overlays), with operator notes added to the AWS and Azure overlays.
- `WebhookKafkaConsumerConfig` now **reads** it. This is the subtle half: the factory is hand-built, and
  Boot only binds `spring.kafka.listener.concurrency` on its *auto-configured* factory — so the
  property was not merely unset, it was **unreadable**. An operator could set it, watch it resolve in
  `/actuator/env`, and change nothing. That is worse than a bad default, because it looks like a lever.
  A value of `0` clamps to 1 rather than silently stopping every webhook.

**Not done, and it is not a config change.** Increasing partitions on an existing topic re-maps
keys→partitions, so per-key ordering does not hold across the change. It must be done with producers
quiesced, or by creating new topics and cutting over.

- **Local compose:** the practical route is to drop the `kafka-data` volume. Safe because each
  service's outbox table — not the broker — is the durable record of intent.
- **AWS MSK:** set `num.partitions` in the cluster configuration *before* the `gmepay.*` topics are
  auto-created, or create them explicitly with `--partitions 3`.
- **Azure Event Hubs (Standard):** partition count is **fixed at creation** and cannot be raised in
  place. An under-partitioned hub must be recreated and cut over.

Until that happens, concurrency 3 is an upper bound nothing reaches: Kafka assigns whole partitions, so
three threads against a one-partition topic leaves two idle — and so does an extra replica.

**Out of scope, listed in §7:** the other three consumer factories (revenue-ledger, prefunding,
ops-partner-bff) are still built bare and still hard-wired to concurrency 1.

---

## 5. The webhook drain (defect 5) — partial, and here is exactly what remains

The runbook's arithmetic: 200 rows delivered **sequentially**, each up to a 10 s read timeout, on a
30 s cycle → up to 2 000 s of work per cycle.

**Fixed:**

| Change | Effect |
|---|---|
| Bounded concurrent delivery (`gmepay.webhook.dispatcher.concurrency`, default 8) | ~8× the drain rate. Virtual threads because the work is a blocking socket read; the semaphore, not the thread type, is what bounds concurrency. |
| Cycle 30 s → 5 s | With `fixedDelay` the interval is dead time *after* a drain finishes; on a queue that never closes that was 30 s of every cycle spent not delivering. |
| Per-delivery read timeout 10 s → 5 s | At C workers the worst-case drain rate is C / read-timeout, so halving the timeout doubles the floor under it. 5 s is a budget for a partner endpoint to accept a small signed JSON body; an endpoint needing longer is doing synchronous work behind the webhook, and the right answer for that is the retry+DLQ this pipeline already has, not an open socket. |
| `@SchedulerLock` | Makes the concurrency safe to ship: a second replica no longer re-reads and re-POSTs the same rows. |

Bounded at 8, not more: each worker holds a socket **and** a unit of demand on a connection pool whose
default size is 10 (§4.1 #8 of the runbook). An unbounded fan-out would move the queue from the drain
into the pool, which is shared with the API — a worse place for it. `concurrency=1` reproduces the old
sequential behaviour exactly, which is the escape hatch if a partner ever needs strict ordering.

The drain waits for every in-flight delivery before returning, deliberately: `fixedDelay` and the
ShedLock window both start counting when it returns, and returning early would let the next cycle
re-select rows whose attempt is still being recorded.

**Not fixed — this needs a design change, so it is stated rather than papered over.**
Rows are still selected in **one global `ORDER BY created_at` across all partners**. A partner whose
endpoint times out on every delivery still occupies workers that other partners' rows are waiting for.
Concurrency raises the number of simultaneously-slow deliveries needed to stall everyone from 1 to 8 —
a mitigation, not a cure.

Closing it properly needs **per-endpoint fairness**: a per-endpoint circuit breaker that stops
selecting rows for a failing endpoint, or per-endpoint queues so one partner's backlog cannot occupy
another's capacity. That is a change to the selection query and the retry model, not a parameter, and
it was deliberately not attempted here. It is recorded in `WebhookDispatcher`'s own javadoc so the next
reader of that class finds it.

---

## 6. The replica ceiling that remains

| Service | Safe at N>1? |
|---|---|
| payment-executor, settlement-reconciliation, notification-webhook, scheme-adapter-zeropay | **Yes, for scheduler correctness** (this work) |
| transaction-mgmt, prefunding | Yes — already were |
| **revenue-ledger** | **No** — outbox publisher unlocked (out of scope, §7) |
| **api-gateway** | **No** — T0-7: in-memory rate limiting (cap becomes N×50/s) and in-memory replay nonce (a signed request replayable N times — an integrity defect, not a performance one) |
| **transaction-mgmt idempotency** | **No** at N>1 unless Redis is configured — the in-memory store gives a per-replica 24 h window, so one retry creates N transactions |
| **ops-partner-bff** | **No** — per-JVM paging de-dup would double-page |

**So the fleet-wide answer is still 1 replica**, and this work did not change that — it removed
scheduler duplication as *one* of the reasons, which was the reason the T3-5 analysis identified as
"the real reason those services cannot be fixed by adding replicas". The remaining blockers are T0-7
and the two follow-ups below. Kafka consumers additionally gain nothing from extra replicas until the
topics are repartitioned (§4).

---

## 7. Follow-ups in files this agent must not touch

Recorded precisely rather than half-done. `services/revenue-ledger` was concurrently owned by another
agent; `api-gateway`, `ops-partner-bff`, `smart-router` and `prefunding` were outside the stated scope.

1. **`services/revenue-ledger` — ShedLock on the outbox publisher.** `revenue-ledger/.../outbox/OutboxPublisher.java:50`
   (`@Scheduled(fixedDelayString = "${gmepay.outbox.poll-ms:1000}")`) has no `@SchedulerLock` and the
   service has no `shedlock` table. Apply the identical pattern: add
   `net.javacrumbs.shedlock:shedlock-spring:5.16.0` + `shedlock-provider-jdbc-template:5.16.0` to
   `build.gradle`, a `ShedLockConfig` with `@EnableSchedulerLock` + `JdbcTemplateLockProvider`
   (`usingDbTime()`), a `V0xx__create_shedlock.sql` at the next free version, and
   `@SchedulerLock(name = "RevenueLedgerOutboxPublisher_publishPending", lockAtMostFor = "PT5M",
   lockAtLeastFor = "PT0S")`. Copy `services/settlement-reconciliation/src/test/java/.../config/ShedLockTest.java`
   for coverage. **This is the last thing keeping revenue-ledger at one replica.**
2. **`services/revenue-ledger` — scheduler pool size.** `spring.task.scheduling.pool.size` is unset;
   set it to at least (job count + 1) for the lag heartbeat.
3. **Three bare Kafka consumer factories.** `RevenueLedgerKafkaConsumerConfig.java:94-104`,
   `PrefundingKafkaConsumerConfig.java:84-94`, `OpsAlertKafkaConsumerConfig.java:85-95` still never call
   `setConcurrency`, so `spring.kafka.listener.concurrency` is unreadable there too. One line each,
   mirroring `WebhookKafkaConsumerConfig`.
4. **`services/ops-partner-bff/.../client/rest/ClientBeans.java:39`** — delete the
   `patchCapableRequestFactoryCustomizer` bean, exactly as payment-executor's was. It installs a bare
   factory; today the lib-errors customizer wins on order, but leaving two beans competing for the same
   builder slot is a latent regression whose failure mode is silent.
5. **`services/smart-router/.../RestSchemeOperatingHoursSource.java:57`** — built with a bare
   `RestClient.builder().baseUrl(..).build()`. It now inherits the 2 s/10 s fleet floor, so it is no
   longer unbounded, but it is on the resolve path and should declare a tighter budget like
   payment-executor's twin (500 ms/500 ms).
6. **`.smoke/infra-up.sh:24`** creates topics with `--partitions 1`; raise to 3 to match compose.
7. **`api-gateway` WebClient hops** (`WebClientRbacClaimResolver`, `AuthIdentityCredentialStatusClient`,
   `RestConfigRegistryClient`) are reactive and are **not** covered by the `RestClientCustomizer`. They
   need a `WebClientCustomizer` with a Reactor Netty response timeout. Not money-path, but unbounded.
8. **Per-endpoint webhook fairness** (§5) — the one genuine design change this work identified.

---

## 8. Verification performed

| Check | Result |
|---|---|
| `gradlew testClasses` (whole repo) | **BUILD SUCCESSFUL** |
| payment-executor / transaction-mgmt / settlement-reconciliation / notification-webhook / 4 scheme adapters / lib-errors | **1511 tests, 0 failures, 0 errors** |
| prefunding + revenue-ledger (neighbours, unmodified) | green — the lib-errors auto-configurations did not disturb them |
| `check_internal_auth_wiring.py` | 94/94 |
| `check_monitoring_wiring.py` | 37/37 |
| `check_helm_chart_wiring.py` | 193/193 |
| `check_gitleaks_config.py` | findings=0, missed=0, false-positives=0 |
| `check_load_harness_wiring.py` | OK |
| `node docker/keycloak/check-topology.mjs` | 101/101 |
| PyYAML parse: `docker-compose.yml` + all four Helm values | all parse; `KAFKA_NUM_PARTITIONS=3`, `SPRING_KAFKA_LISTENER_CONCURRENCY=3` |

**Not performed, deliberately:** no fleet was started, no Docker was started, no load run. The claimed
throughput improvements in §5 are measured by unit tests against a stubbed sender, not against a real
partner endpoint — they prove the batch no longer serialises, not a production number.

### New tests

| File | What it proves |
|---|---|
| `libs/lib-errors/.../http/HttpClientTimeoutTest.java` | A real socket that accepts and never answers times out as `ResourceAccessException`; the customizer runs last; the factory is still PATCH-capable; the fleet defaults are pinned. |
| `libs/lib-errors/.../metrics/SchedulerLagProbeTest.java` | Two real `@Scheduled` jobs: the sibling is starved at `pool.size=1` and runs at `pool.size=2`. Lag rises, recovers, keeps its peak. Pool gauges are `NaN` not `0` when blind. |
| `services/payment-executor/.../InternalHttpTimeoutTest.java` | A hung scheme → `SchemeTimeoutException` (the UNCERTAIN path), request sent **exactly once**; internal clients bounded; shipped budgets nest. |
| `services/payment-executor/.../config/ShedLockTest.java` | Second replica refused the expiry-sweeper lock; all four jobs locked; pool sized. |
| `services/settlement-reconciliation/.../config/ShedLockTest.java` | Second replica refused; per-job locks independent; all **seven** jobs locked. |
| `services/notification-webhook/.../WebhookDrainCapacityTest.java` | 16×200 ms deliveries at concurrency 8 finish in <½ sequential time; concurrency stays bounded; `concurrency=1` is strictly sequential; a bad row does not stall the batch; the drain waits for all workers; second replica locked out; the lock window exceeds the worst-case drain. |
| `services/notification-webhook/.../WebhookKafkaConcurrencyTest.java` | Concurrency is read from config; default 3 matches the partition count; `0` clamps to 1. |
| `services/scheme-adapter-zeropay/.../SchemeOutboundTimeoutTest.java` | A hung scheme → `SCHEME_UNAVAILABLE` (ambiguous), commit sent **once**; shipped 4 s nests inside the caller's 5 s. |
| `services/scheme-adapter-zeropay/.../config/ShedLockTest.java` | Second pod refused a batch window; all six windows locked under six distinct names. |
| `services/transaction-mgmt/.../SchedulerPoolSizeTest.java` | Pool size covers the job count + the heartbeat; adding a fourth job fails here. |

### Flyway

Next free version per module, verified: payment-executor **V011** (V001–V010 existed),
settlement-reconciliation **V014** (V001–V013), notification-webhook **V008** (V001–V007),
scheme-adapter-zeropay **V005** (V001–V004). All four are additive `CREATE TABLE IF NOT EXISTS` with
the canonical ShedLock schema, engine-neutral (PostgreSQL + H2 in PostgreSQL mode), and each is proved
to apply on top of its predecessors by a test that runs the full migration set. **No vendor
directories were touched** — only `config-registry` has `db/vendor/{h2,postgresql}`, and it is not in
this change.

---

# 9. Second pass — verifying the first pass against the code

The four defects were re-derived from the current tree rather than read off §0. Three verdicts were
wrong, and they were wrong in the same way each time: **a mechanism was built, a test was written that
could only see the mechanism, and the gap the mechanism was supposed to close was never measured.**
None of the three was a careless edit; each was a plausible fix whose reach was assumed.

## 9.1 Defect 1 — the fleet-wide timeout floor reached about half the fleet

`HttpClientTimeoutAutoConfiguration` registers a `RestClientCustomizer`. Spring Boot applies
customizers **only to the `RestClient.Builder` bean it auto-configures**. `RestClient.builder()` is a
*static factory* that returns a fresh, uncustomized builder.

So the rule that decides whether a client is bounded is not "which service is it in" but **one
character of difference at the call site**:

```java
// bounded — the injected BEAN carries every RestClientCustomizer, including the timeout floor
public FooClient(RestClient.Builder builder, @Value("${...}") String baseUrl) {
    this.restClient = builder.baseUrl(baseUrl).build();
}

// UNBOUNDED — a fresh builder; no connect timeout, no read timeout, ever
public FooClient(@Value("${...}") String baseUrl) {
    this.restClient = RestClient.builder().baseUrl(baseUrl).build();
}
```

**29 main-source clients used the static form.** Three of them install a request factory themselves and
are fine (`RestWebhookHttpClient`, `WebhookAlertSink`, `WebhookPagingAdapter`). The other **26 had no
read timeout at all** — including four on a live money path. §7 item 5 of the first pass states the
opposite in as many words ("It now inherits the 2 s/10 s fleet floor, so it is no longer unbounded");
that sentence was false when it was written.

This is the worst shape a timeout defect can take, because it is invisible from both ends.
`gmepay.http.client.read-timeout` resolves, it appears in `/actuator/env`, the auto-configuration's own
tests pass, and a reviewer reading either the client or the configuration sees a bounded system. Only
the two together reveal that the bound never applied. `HttpClientTimeoutTest` passes because it calls
the customizer by hand — the one thing production does not do for these clients.

**Fixed (in scope), all four to the injected builder:**

| Client | Why it matters |
|---|---|
| `payment-executor/.../client/rest/RestRevenueLedgerClient.java` | Revenue capture / commission split / residual, called **inside the payment path**. Its documented contract is to *swallow* failures into the durable failure store — and a hop that swallows is exactly the hop that must be bounded, because an unbounded one swallows nothing, it holds the payment's thread. |
| `prefunding/.../client/RestConfigRegistryClient.java` | Called while a balance deduction is in flight. The class javadoc already promised that a config-registry outage must never roll that transaction back; unbounded, the outage did not fail — it never returned. |
| `rate-fx/.../client/RestConfigRegistryClient.java` | On the quote path. |
| `rate-fx/.../xe/XeRateClient.java` | An **external** rate provider on the `XeRateFetchScheduler` thread. Unbounded, one hung fetch parked a scheduler thread permanently and the fleet stopped refreshing rates with no error raised. |

Every scheme adapter was checked and is correct — all four take the injected builder, so the 4 s
outbound budget really does apply.

**New guard:** `libs/lib-errors/.../http/OutboundHttpTimeoutWiringGuardTest.java` scans the fleet's
sources and fails when a main-source client uses the static factory without installing its own request
factory. The 25 remaining offenders are a **shrinking baseline**: the test fails both when a new one
appears *and* when a listed one is fixed without being delisted, so the list can only ever get shorter.
A second test forbids any in-scope service from ever appearing in it — in-scope debt recorded as
accepted debt is just debt that was not paid.

### The timeout values, and why each one

Unchanged from the first pass and re-verified; restated here because the question was asked directly.
None of these is a service-level objective and no partner is promised any of them.

| Hop | connect | read | Reasoning |
|---|---|---|---|
| Fleet floor (anything not named below) | 2 s | **10 s** | Deliberately the **loosest** value, because it applies to hops nobody has considered individually — reports, admin reads, batch. Its only job is to convert *unbounded* into *bounded*. A tight global default would start failing legitimately slow non-money calls on day one, and the pressure to raise it back would land on the money path too. |
| payment-executor → internal (rate, txn, prefunding, qr, ledger, router) | 2 s | **5 s** | Every hop is a step inside one live payment, and the payment's own ceiling is the scheme leg's 5 s. An internal hop permitted 10 s could outlive the payment it belongs to. Not tighter, because a timeout on `prefunding.capture` or `transaction.commitStatus` creates an *unknown* that becomes reconciliation work — the thing this gap is about. |
| payment-executor → adapter (scheme leg) | 2 s | 5 s | The outer bound the others nest inside. |
| adapter → scheme (ZeroPay / Nepal / SendMN / 9Pay) | 2 s | **4 s** | Strictly **inside** the caller's 5 s. If the inner leg were the looser one, payment-executor would abandon first and infer "unknown" from its own socket timeout; at 4 s the adapter gives up first and answers a definite `SCHEME_UNAVAILABLE` carrying a reason. **This is the nesting rule, and it is asserted** (`InternalHttpTimeoutTest#shippedTimeoutsNestInsideTheSchemeBudget`, `SchemeOutboundTimeoutTest#shippedOutboundBudgetNestsInsideTheCallersBudget`), so raising one "just for one slow report" fails the build. |
| zeropay → transaction-mgmt (batch enrichment) | 2 s | **30 s** | The one deliberate exception: a nightly window reading a business day of refunds, not a live payment. A live-payment budget here would fail the 02:00 run on a healthy-but-slow day-range query. Still bounded; every method degrades to an empty map. |
| config-registry reads (operating hours, ops status) | 0.5 s | 0.5 s | Reference-data reads with a documented degraded path. |
| webhook → partner endpoint | 5 s | **5 s** | A budget for a partner to *accept* a small signed JSON body. An endpoint needing longer is doing synchronous work behind the webhook, and the answer for that is the retry + DLQ this pipeline has, not an open socket. Also the term that sets the drain's worst case (§9.3). |

**An irreversible submit gets the longer read timeout, and that asymmetry is the point.** A lookup is
cheap to repeat and safe to abandon, so a tight bound on it costs nothing. A submit is neither. But
"longer" is not "unbounded", because the value of the bound is not speed — it is that **the ambiguity
window has a known size**. Shortening a submit timeout does not reduce risk; it *increases* how often
the ADR-016 probe path is taken. So these are bounds on ambiguity, not fail-fast numbers.

### The ADR-016 test that was missing

The instruction called this the most important test, and it did not exist. What existed was
`InternalHttpTimeoutTest` (a real socket → the client throws the right type, request sent once) and
`ResilientFailoverIntegrationTest` (a **mocked** client throws → the router probes). Neither joined the
two halves — and joining them is the whole point, because **the bug the first pass found was precisely a
client whose timeout surfaced as the wrong exception type, so the router's guard was never reached and
no ambiguous row was written at all.** A mock cannot reproduce that, because a mock is told what to
throw.

`services/payment-executor/.../domain/TimedOutSubmitResolvedByProbeTest.java` wires the **real**
`RestSchemeClient` against a **real** `HttpServer` into the **real** `FailoverPaymentRouter`. The server
accepts the submit and never answers it; the status endpoint answers normally. Three cases:

| Probe answers | Asserted |
|---|---|
| `APPROVED` (the charge landed) | payment is **approved**, submit sent **exactly once**, probe called once. Reporting FAILED here would lose money the customer really paid. |
| `PENDING` | **neither approved nor failed** — `declineReason == "PENDING"`, submit sent **exactly once**. PENDING is the one state where a re-send is a *guaranteed* double charge. |
| unknown to the scheme | submits == **2**. The control case: it proves the other two pass because the guard short-circuited, not because failover was unreachable. |

Two candidates are in the routing list on purpose, so "did it fail over?" is observable as a count
rather than inferred.

**This test caught its own first version being wrong**, which is worth recording. It initially used a
300 ms connect timeout and failed in the full suite with `submits == 0` — a *connect* timeout, not a
read timeout. Both produce `SchemeTimeoutException`, so the test would have passed for the wrong reason
had it not counted requests: a connect timeout means nothing was sent and nothing was charged, which is
the unambiguous case, not the one under test. The connect budget is now 10 s so only the read timeout
can fire, and the `submits` assertions are what keep it honest.

## 9.2 Defect 2 — the pool sizes had already drifted

| Service | Jobs | Pool was | Now | Why |
|---|---|---|---|---|
| **payment-executor** | **7** | **6** | **8** | The regression. 6 was derived from an *argument* — "the two nightly crons are 30 min apart and cannot contend" — which is an assumption about **runtime duration** that nothing measures. Day-close starts 02:30, FX-exposure 03:00; a day-close that runs past thirty minutes (what a high-volume day produces) has both live, needing 8 while 6 exist. The two that queue are chosen by arrival order, so the loser can be `AuthorizationExpirySweeper` — the job that releases prefunding holds. |
| **scheme-adapter-zeropay** | 6 | 3 | **7** | Same shape of argument: six crons at 02:00/02:02/05:00/14:00/22:00/22:02 "cannot overlap". Every one of those windows **generates and transfers a file**, so its duration depends on a business day's row count and a remote endpoint. The pairs are two minutes apart; one slow transfer overlaps its neighbour and the argument had two threads of slack. |
| **prefunding** | 1 | UNSET (=1) | **2** | One job is not a starvation risk *for the job*. The victim is the **monitor**: on one thread a 1-second outbox poller and the `SchedulerLagProbe` heartbeat compete, and a probe that cannot get a thread **reports no lag**. "The pool is fine" and "the pool is too busy to measure" became indistinguishable — the metric added to make this visible was itself the thing hidden. |
| **rate-fx** | 1 | UNSET (=1) | **2** | Its one job makes an outbound call to an external provider. Now bounded (§9.1), but bounded still means it can hold its thread for the whole read budget — during which the one metric that would report a stalled rate fetch cannot run. |

The general lesson, and the reason for the guard: **counting is auditable, arguing is not.** Both
under-sized pools were justified by correct-sounding reasoning about which jobs can overlap. A thread
costs ~1 MB of stack against a 320 MB heap; being wrong costs a settlement window that silently did not
run, or float left reserved overnight.

**New guard:** `libs/lib-errors/.../metrics/SchedulerPoolSizeWiringGuardTest.java` — pool size must be
**strictly greater** than the `@Scheduled` count (strictly, because the lag heartbeat shares the pool).
Shrinking baseline for the six out-of-scope services, each with what is wrong. Notably
`reporting-compliance` runs 3 jobs on one thread and the runbook already records that its BOK and KOFIU
schedulers fire **on the same second**, so two of the three contend by construction.

## 9.3 Defect 3 — "16×" was a multiple, and the rate was still below 1×

The register line says the drain "does not keep up even at 1×". The first pass answered with a
multiple: concurrency 1 → 8, cycle 30 s → 5 s, timeout 10 s → 5 s, "roughly a 16× ceiling increase".
A multiple cannot answer that question. **16× a rate three orders below the payment rate is still below
the payment rate**, and `WebhookDrainCapacityTest` could not tell, because it measures the drain
against *itself* (does the batch serialise?) rather than against an absolute rate.

Redone as a rate. There is no declared throughput SLO — `Documentation/SLO_TARGETS.properties` ships
blank on purpose (T3-5 §3: a number nobody owns is worse than an absent one) — so the only payment rate
this repository commits to is **api-gateway's admission ceiling, `rate-limit.payments-per-second: 50`**.
Ceiling versus ceiling is the right comparison for a queue: if the edge will admit 50 payments/s, the
pipeline behind it must emit more than 50 deliveries/s or the backlog grows without bound *inside the
platform's own stated limits*.

```
sustained rate = batch-size / (ceil(batch-size / concurrency) x per-delivery-latency + interval)

before:  200 / (ceil(200/8)=25  x 0.25s + 5s) = 200 / 11.25s =  17.8 /s   <-- 0.36x of 50/s
after:   500 / (ceil(500/32)=16 x 0.25s + 1s) = 500 /  5.00s = 100.0 /s   <-- 2.0x  of 50/s
```

The binding constraint was **not** the one the first pass fixed. With `fixedDelay` the interval is dead
time *after* the drain returns, so at realistic latency it was the **dominant term**: ~6 s of delivering
followed by 5 s of deliberately doing nothing, while the queue it exists to empty kept growing.

| Change | From | To | Why |
|---|---|---|---|
| `interval-ms` | 5000 | **1000** | The dominant term. Costs one COUNT + one SELECT per second on an empty queue — the same shape and price as transaction-mgmt's 1 s outbox poll. ShedLocked, so it does not multiply across replicas. |
| `batch-size` | 200 (a `@Value` default) | **500** | The per-cycle ceiling. Rows are independent by construction. |
| `concurrency` | 8 (a `@Value` default) | **32** | Virtual threads, so the thread is nearly free. It was capped at 8 out of concern for a 10-connection pool — but the DB write happens **after** the HTTP call returns, so a worker does not hold a connection across the socket, and concurrency was never pinned to the pool size. |
| `spring.datasource.hikari.maximum-pool-size` | unset (=10) | **40** | Raised *with* concurrency. Left at 10, 32 workers would queue on the pool — moving the backlog out of the dispatcher, where the queue-depth alert can see it, into a pool **shared with the HTTP API**, where it becomes unexplained API latency. |

Both `batch-size` and `concurrency` existed only as constructor defaults; they are now stated in
`application.properties`, because a capacity parameter that lives only in a `@Value` is one nobody
reviews.

**250 ms is an assumption and is named as one** in both the config comment and the test. It is not a
partner SLA and nothing measures it. The **worst** case is unchanged and not fixable by sizing: every
endpoint at its full 5 s timeout gives `concurrency / 5s` = 6.4/s — an estate where every partner is
timing out is an incident, which is what the per-endpoint circuit breaker is for.

**New test:** `services/notification-webhook/.../WebhookDrainThroughputTest.java` — reads all four
numbers from the shipped properties, reads `payments-per-second` out of **api-gateway's own YAML**
rather than restating it (so raising the admission ceiling without re-sizing the drain fails here), and
requires **2× headroom**: a queue that drains at exactly its arrival rate never recovers from a backlog,
it only stops adding to one. It also re-checks that the ShedLock window still covers the worst-case
drain, because raising `batch-size` raises that too — `ceil(500/32) × 5 s = 80 s` against a `PT10M`
lock.

**Per-endpoint fairness is genuinely done** (a later commit, not the first pass): fair round-robin
selection per endpoint, a per-endpoint in-flight cap derived as `ceil(concurrency / endpoints-with-work)`,
and a per-endpoint circuit breaker. §5's "not fixed" is now stale.

**Ordering:** per-endpoint signing (T5-4) is untouched — the secret and the optional rotation-overlap
secondary are resolved per row, so concurrency cannot cross-sign. **No delivery ordering is relied
upon and none is lost.** Each row is one delivery of one event to one endpoint and every path advances
only its own row; what the sequential loop guaranteed was ordering of *attempts started*, never
ordering of *arrivals* at the partner. `concurrency=1` restores strict sequencing if a partner ever
requires it.

## 9.4 Defect 4 — Kafka, and the ordering question answered

**Verified rather than assumed, because the instruction was explicit about it:** the producer
(`libs/lib-events-kafka/.../KafkaEventPublisher.java:88-96`) keys every record on
`DomainEvent.aggregateId()` and **refuses to publish a blank one**. Kafka hashes the key to a partition,
so all events of one aggregate land on one partition and stay totally ordered there. Raising the
partition count therefore reorders **across** aggregates only, and no consumer depends on that. This is
the precondition the instruction asked to check before increasing partitions, and it holds.

Consumer side is fully closed — all four hand-built factories now read
`spring.kafka.listener.concurrency` (the first pass fixed one; the other three were fixed in a later
commit, and `ops-partner-bff` too, so that guard's baseline is empty).

**Added:** `KafkaListenerConcurrencyWiringGuardTest#defaultConcurrencyMatchesTheBrokersPartitionCount`
reads `KAFKA_NUM_PARTITIONS` out of `docker-compose.yml` and asserts every consumer's default equals it.
Previously each service's test asserted `3` with a *comment* saying that matched compose. The two
numbers are not independent knobs — Kafka assigns whole partitions, so concurrency above the count
leaves threads permanently idle and below it leaves partitions unread — so re-partitioning the broker
without re-aligning the consumers now fails a test instead of producing a silently mis-threaded fleet.

**Still a migration, not a config change** (unchanged from §4): existing topics keep their current
partition count, and raising it re-maps keys→partitions, so it must be done with producers quiesced or
by creating new topics and cutting over. Locally: drop the `kafka-data` volume (safe — each service's
outbox table, not the broker, is the durable record of intent). AWS MSK: set `num.partitions` in the
cluster configuration *before* the `gmepay.*` topics are auto-created. Azure Event Hubs Standard:
partition count is **fixed at creation**; an under-partitioned hub must be recreated and cut over.

## 9.5 Defect 3 of the first pass (ShedLock) — the follow-up landed

`revenue-ledger`'s outbox publisher now carries `@SchedulerLock` with a `V007__create_shedlock.sql`, and
its pool is sized (1 job, pool 2). §7 item 1 is done.

## 9.6 What is left, precisely

**Infrastructure-blocked** (cannot be closed from this repository):

1. **Kafka repartitioning** — §9.4. `KAFKA_NUM_PARTITIONS: 3` governs *auto-created* topics only, so
   any topic that already exists keeps its count. Consumer concurrency is an upper bound nothing
   reaches until an operator performs the migration, and **extra replicas remain idle consumers** until
   then. On Azure Event Hubs Standard it is not even a migration but a recreate-and-cut-over.
2. **Explicit topic declaration was considered and rejected.** Declaring `NewTopic`/`TopicBuilder` beans
   would make the partition count a code fact rather than a broker default — but `KafkaAdmin` contacts
   the broker at context refresh, so it would put a broker dependency into every service's startup
   (including test slices), and it still cannot change an existing topic. The count is instead pinned
   where it is actually applied (compose) and asserted against every consumer (§9.4).
3. **`.smoke/infra-up.sh:24`** creates its smoke topic with `--partitions 1`. One character; the file is
   outside the stated scope.
4. **Replica count and HPA** — untouched by design (T1-6/hosting). Scheduler correctness no longer pins
   any service at one replica, but the fleet still is: T0-7 items were closed in later commits and
   `ops-partner-bff`'s per-JVM paging de-dup remains.

**Out of scope, verified still broken, and left as a guarded baseline** (a test now fails if any of
these is fixed without being delisted, and if a new one appears):

5. **25 clients with no read timeout** — §9.1. In priority order, because three are on a live money
   path: `smart-router`'s `RestSchemeOperatingHoursSource`, `RestPartnerSchemeRegistry` and
   `RestPartnerSchemeResolver` are all called while a payment is being routed, so a hung config-registry
   hangs the resolve step unboundedly — the exact mechanism that turns load into `UNCERTAIN` rows and
   manual ops work. Then `settlement-reconciliation` (2), `config-registry` (4), `auth-identity` (1),
   `reporting-compliance` (2), `ops-partner-bff` (12). Each is a one-line change to the injected builder.
6. **6 services running one scheduler thread or fewer threads than jobs** — §9.2, worst first:
   `reporting-compliance` (3 jobs, one thread, two of them firing on the same second),
   `settlement-reconciliation` (7 jobs, pool 4, three of them transmitting settlement files),
   `ops-partner-bff` (2 jobs including the paging sweep), `config-registry`, `merchant-qr-data`,
   `qr-service`.
7. **`ops-partner-bff`'s `patchCapableRequestFactoryCustomizer`** still competes with the lib-errors
   customizer for the same builder slot. Today lib-errors wins on order; two beans contending for one
   slot is a latent regression whose failure mode is silent. payment-executor's twin was deleted.
8. **api-gateway's WebClient hops** (`WebClientRbacClaimResolver`, `AuthIdentityCredentialStatusClient`,
   `RestConfigRegistryClient`) are reactive and are **not** covered by any `RestClientCustomizer`. They
   need a `WebClientCustomizer` with a Reactor Netty response timeout. Not money-path, but unbounded —
   and note the new guard does **not** catch these, because it scans for `RestClient.builder()`.

**Not blocked, just not measured:** every throughput number in §9.3 is arithmetic over shipped
configuration, not a measurement. No fleet was started, no Docker, no load run. T3-5's harness exists
and has still never been executed, so nothing here has been confirmed against a running system.

## 9.7 Verification (second pass)

| Check | Result |
|---|---|
| `gradlew testClasses` (whole repo) | **BUILD SUCCESSFUL** |
| `:services:payment-executor:test` | green (527 → 530 tests) |
| `:services:notification-webhook:test`, `:services:transaction-mgmt:test`, `:services:prefunding:test`, `:services:rate-fx:test`, `:services:scheme-adapter-zeropay:test` | green |
| `:libs:lib-errors:test`, `:libs:lib-events-kafka:test` | green |
| `check_internal_auth_wiring.py` / `check_monitoring_wiring.py` / `check_helm_chart_wiring.py` / `check_gitleaks_config.py` / `check_load_harness_wiring.py` | **all PASS** |
| PyYAML parse: `docker-compose.yml` + **all four** Helm values (incl. `values-onprem.yaml`) | all parse |
| New test asserting the ADR-016 probe loop end-to-end | 3 cases, incl. a control case that proves the other two are not vacuous |

No manifest was changed in this pass (`docker-compose.yml` and `deploy/helm/**` are untouched); the
guards were re-run anyway because service configuration moved.
