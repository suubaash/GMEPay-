> 작업: T3-11 concrete capacity defects / 출처: agent

# T3-11 — the five concrete capacity defects

Gap: **T3-11. Concrete capacity defects found by the T3-5 analysis.** Evidence with file:line in
`outputs/agent/fix_t3-load-and-capacity_2026-07-28.md` §4, from a real 200-payment run.

**Headline:** four of the five are closed in code. Kafka is half-closed, and the remaining half is an
operational migration rather than anything a config change can do. The webhook drain's ceiling is
raised ~16× but its underlying design defect is still open and is described rather than papered over.

**One previously-unknown bug was found while proving the timeout fix**, and it was worse than the gap
it was found under — see §1.2.

Nothing was run against a live fleet. Docker was not started, no server was started. Everything below
was proved by tests, several of which drive a real unresponsive TCP socket and a real H2 + Flyway
database.

---

## 0. Scoreboard

| # | Defect | Verdict |
|---|---|---|
| 1 | Money-path HTTP clients have no read timeouts | **CLOSED** — fleet-wide floor + per-hop nesting + a real bug fixed underneath it |
| 2 | `@Scheduled` pool size 1, 3–7 jobs stacked | **CLOSED** — sized per service, and a lag metric so it can never be silent again |
| 3 | Schedulers without ShedLock | **CLOSED in scope** (4 services) — `revenue-ledger` is a named follow-up, not forgotten |
| 4 | Kafka 1 partition × concurrency 1 | **HALF** — concurrency is now real and configurable; repartitioning is an ops migration |
| 5 | Webhook drain does not keep up at 1× | **PARTIAL** — ~16× more headroom; the per-partner coupling is a design change, stated as open |

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
