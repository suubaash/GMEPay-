> 작업: Kafka factories + ops-run observability + webhook fairness / 출처: agent

# Three engineering follow-ups closed

**Branch:** `feat/exec-gap-closure-2026-07-28` · **Touched:** `services/notification-webhook`,
`services/revenue-ledger`, `services/prefunding`, `services/payment-executor`,
`libs/lib-events-kafka` (test only — the fleet-wide guard), plus `Documentation/GAP_REGISTER.md`, four
CHANGELOGs and this report.

`services/ops-partner-bff`, `services/api-gateway`, `services/transaction-mgmt`,
`docker-compose.yml` and `deploy/helm/**` were **not touched** — a second agent owns them. The one
place that costs something is recorded precisely in §1.3 and §5.

No Docker, no server, no fleet was started.

---

## 0. Scoreboard

| # | Item | Verdict |
|---|---|---|
| 1 | Three bare Kafka consumer factories | **2 of 3 CLOSED** + a fleet-wide guard; the third is one line in a file this agent must not touch |
| 2 | `ledger_ops_runs` has no missed-run detection and no retention pruner | **CLOSED** — both, both configurable, both defaults flagged for an owner |
| 3 | Webhook drain per-endpoint fairness | **CLOSED** — and it needed three mechanisms, not one |

**A shipped-config defect was found while proving item 3, and it is the most immediately consequential
finding in this report:** T3-11's headline "the cycle is now 5 s" was **not in effect anywhere**. See
§3.5.

---

## 1. Item 1 — the Kafka consumer factories

### 1.1 Why this was worth more than one line each

The defect is not "the value was 1". It is that the value was **unreadable**.

Boot binds `spring.kafka.listener.concurrency` onto its *auto-configured* container factory only. All
four services in this fleet hand-build their own (to pin `AckMode.MANUAL` and a DLT error handler), and
a hand-built factory that never calls `setConcurrency(..)` runs one consumer thread whatever the
property says. So an operator could set it, watch it resolve in `/actuator/env`, restart, and change
nothing at all — while `deploy/helm/gmepay/values.yaml` advertises `SPRING_KAFKA_LISTENER_CONCURRENCY`
in the ABI ConfigMap as a fleet-wide lever.

That is worse than a bad default. A bad default is visible in code review; a lever that does nothing
survives review, survives an incident, and gets raised again next time.

### 1.2 What changed

| Service | Factory | Now |
|---|---|---|
| revenue-ledger | `RevenueLedgerKafkaConsumerConfig` | reads the property, default 3, clamped at 1 |
| prefunding | `PrefundingKafkaConsumerConfig` | reads the property, default 3, clamped at 1 |
| notification-webhook | `WebhookKafkaConsumerConfig` | already fixed by T3-11 |
| **ops-partner-bff** | `OpsAlertKafkaConsumerConfig` | **still bare** — see §1.3 |

Deliberately a copy of notification-webhook's fix rather than an improvement on it: four factories with
one defect should be fixed by one shape, or the next reader has to work out which of four variants is
the right one.

The clamp is not defensive noise. `concurrency=0` from a config typo would mean *no consumer threads*,
which on revenue-ledger silently stops revenue capture and on prefunding silently stops float being
released — money held, not lost, with no error anywhere. Clamping to 1 makes a typo a performance
question instead of an outage.

Default **3** is not a tuning guess: it is `KAFKA_NUM_PARTITIONS` in `docker-compose.yml` and
`SPRING_KAFKA_LISTENER_CONCURRENCY` in the Helm ABI. Kafka assigns whole partitions, so threads beyond
the partition count sit idle — the same reason extra replicas gained nothing. Each service's test
asserts the default equals 3 so the two cannot drift apart silently. **The partition migration itself
is untouched and remains an ops migration, not a config change** (T3-11 report §4).

Ordering is unaffected on both listeners: the producer keys by aggregate id, so every event for one
payment lands on one partition and is still handled in order by one thread. Concurrency reorders across
payments only, and a float release is scoped to its own authorization.

### 1.3 The guard, and the one file it cannot fix

Two layers, because they prove different things:

- **Per-service behavioural test** (`RevenueLedgerKafkaConcurrencyTest`,
  `PrefundingKafkaConcurrencyTest`, mirroring the existing `WebhookKafkaConcurrencyTest`). Builds the
  factory from a real property source and reads the concurrency **back off the built factory** by
  reflection. This is the only way to distinguish "unset" from "unreadable" — asserting on
  configuration would have passed on the broken code.
- **Fleet-wide guard** (`libs/lib-events-kafka/.../KafkaListenerConcurrencyWiringGuardTest`). Scans
  every `services/**/*.java` that constructs a `ConcurrentKafkaListenerContainerFactory` and fails if
  it never calls `setConcurrency`. It lives in the Kafka lib because each factory is in its own module
  and no module can see another's classes — a per-module guard would have to be copied into every
  service, which is exactly the copying that let three factories drift. It also asserts that each fixed
  factory *has* a service-level concurrency test, since a source scan cannot tell
  `setConcurrency(3)` from `setConcurrency(configuredValue)`.

`KNOWN_UNFIXED` contains exactly one entry:
`services/ops-partner-bff/src/main/java/com/gme/pay/bff/alert/OpsAlertKafkaConsumerConfig.java`. It is a
**shrinking baseline, not an exemption list**: the test fails both when a file outside the list ignores
the property and when a listed file has been fixed without its entry being deleted. So the entry can
only ever be removed.

**The exact handoff for whoever owns ops-partner-bff** — `opsAlertKafkaListenerContainerFactory`:

```java
@Value("${spring.kafka.listener.concurrency:3}") int concurrency   // add the parameter
factory.setConcurrency(Math.max(1, concurrency));                  // add before the return
```

...then delete the `KNOWN_UNFIXED` entry (the failing test will say so) and add an
`OpsAlertKafkaConcurrencyTest` copied from either of the two above. No config key needs adding anywhere:
the property already exists in the Helm ABI and would simply start working.

**Verified negatively.** Removing `setConcurrency` from prefunding makes the guard fail (`2 tests
completed, 1 failed`); restored, it passes. A guard nobody has watched fail is a guard nobody knows
works.

---

## 2. Item 2 — `ledger_ops_runs` had no reader for a writer going quiet

### 2.1 The shape of the hole

Everything `ledger_ops_runs` could tell you was produced **by a run that started**. `LedgerOpsRunExecutor`
persists a FAILED row in its own transaction and raises `LEDGER_OPS_RUN_FAILED`; the row survives the
rollback of the work it describes; the alert outcome is stamped on the same row. All of that is good, and
all of it requires the run to begin.

A run that never begins produces none of it. No row, no exception, no alert — the table just stops
growing. From inside the service that is indistinguishable from a quiet night, and the things that cause
it are ordinary: a scheduler thread that died, a ShedLock row held by a pod that was killed mid-run, an
`enabled` flag turned off in one environment and forgotten, a cron edited into a window that never
fires.

For a scheduled financial job the silent absence *is* the failure mode that matters. And the POISON
requeue work had just added a fourth writer to this table without adding any reader that notices a
writer going quiet.

### 2.2 `MissedLedgerOpsRunMonitor`

Every `check-interval-ms` (default 15 min) it asks, per expected job, "how long since this job recorded
**any** run?" and raises `LEDGER_OPS_RUN_MISSED` past that job's configured maximum silence — through
`LedgerOpsRunExecutor.alert(..)`, i.e. the **existing T3-3 `OpsAlertPipeline`**: persist to
`ops_alerts` → publish → notify. No second alerting path, no new table, no new event type.

The check cadence is deliberately much shorter than the tightest window (15 min against 30 min): total
detection latency is check-interval + max-silence, and a detector that checks as rarely as the thing it
checks doubles its own delay.

Four decisions worth stating, because each of them is the difference between an alert people act on and
an alert people mute:

| Decision | Why |
|---|---|
| **Outcome is ignored.** A FAILED run counts as "ran". | It *happened*, and it already raised its own alert. This detector is only ever about silence — conflating the two would double-page one incident and still miss the other. |
| **`REVENUE_POSTING_REQUEUE` is never expected** (`NOT_SCHEDULED`). | It is an operator act. "Nobody requeued anything today" is the normal state; alerting on it would train people to ignore the alert type, which costs more than the coverage gains. |
| **A job whose own `enabled` flag is false is not monitored.** | Its scheduler bean does not exist, so its silence is intended. The monitor reads the *same three flags* the schedulers are gated on, so the two cannot disagree. |
| **"Never ran" is measured from process start, not the epoch.** | Otherwise every cold start pages about a day-close that was simply not due yet. A deployment that then never runs its jobs still alerts — one window after boot. The `detail` text distinguishes "NO run has ever been recorded" from "last run at …", because those are different incidents with different first questions. |

A reflection test fails if a new `LedgerOpsJob` constant is added to **neither** the monitored map nor
`NOT_SCHEDULED`. That guard exists because "a writer with no reader" is precisely the failure this item
is closing; leaving the next job's coverage to memory would reproduce it.

**Honest limit:** the repeat-suppression cooldown is per-JVM in memory, so a restarting pod can
re-alert sooner than the cooldown. That matches how `DeclineSpikeMonitor`'s cooldown already behaves in
this service, and re-alerting is the safe direction for a detector whose whole job is to break silence.

### 2.3 `LedgerOpsRunRetentionSweeper`

Built to `OpsAlertRetentionSweeper`'s shape deliberately — same default-on convention, same
never-throw, same "a delete is idempotent but lock it anyway" reasoning.

The need is arithmetic: a row every five minutes from the replay sweeper is ~105 000 rows a year from
that job alone, each carrying up to 4 KB of stack excerpt on a failure, and nothing ever removed one. An
append-only audit table with no expiry is a slow disk-full, and the disk it fills belongs to the
database the money path writes to.

**The one non-obvious rule: the newest run of each job is never pruned, whatever its age.** Missed-run
detection reads exactly that row. Without the exception the two features in this item would cancel each
other out — a job silent for longer than the retention window would have its last trace deleted, and
"silent for 400 days" would become indistinguishable from "no history". The longer a job had been
broken, the less evidence would survive.

### 2.4 The numbers an owner must confirm

**None of these is a service-level objective and no partner or regulator is promised any of them.** Each
is a property with a defensible engineering default:

| Property | Default | Reasoning |
|---|---|---|
| `…missed-run.max-silence.revenue-posting-replay` | **PT30M** | The job runs every 5 min. Six missed cycles: long enough to ride out a restart or a long sweep, short enough that unbooked revenue is noticed the same hour. |
| `…missed-run.max-silence.day-close` | **PT26H** | Daily at 02:30 KST. A day plus two hours of slack, so a late run is not an alert but a skipped night is — found the same morning rather than at month-end. |
| `…missed-run.max-silence.fx-exposure` | **PT26H** | Daily at 03:00 KST; as above. |
| `…missed-run.cooldown` | **PT6H** | A 15-minute cadence against a 6-hour outage would otherwise raise 24 identical alerts. |
| `…missed-run.check-interval-ms` | **900000** | Half the tightest window, so detection latency is dominated by the window rather than the poll. |
| `gmepay.ledger-ops.runs.retention-days` | **365** | Longer than `ops_alerts`' 90 because this is an execution record for financial jobs, not an incident feed — "did the close run every night last year?" is a fair audit question 90 days cannot answer. **Not** a statutory multi-year horizon, because **this table is not a transaction record**: no amounts, no counterparties, no postings, only whether a job ran. If compliance concludes the run ledger is in scope for a statutory period, it is one property and the pruner honours it unchanged. |

**Ask for the owner, precisely:** confirm (a) how quickly finance needs to know that a day-close did not
happen, (b) what on-call will tolerate for the 5-minute replay job, and (c) whether the run ledger falls
under any record-retention obligation. Until (c) is answered, 365 days is an engineering choice, not a
compliance position.

### 2.5 Two things this needed that are easy to miss

- **Both new jobs are `@SchedulerLock`ed.** An alert raised once per replica is one nobody can
  threshold, and N replicas issuing the same bulk DELETE is contention for zero benefit. The existing
  reflection guard in `ShedLockTest` would have failed otherwise — which is the guard working.
- **`spring.task.scheduling.pool.size` 4 → 6.** The missed-run monitor is the job whose entire purpose
  is to notice that another job stopped. Starving it behind a slow sibling produces silence about
  silence. 6 covers the five always-on fixed-delay jobs plus the lib-errors lag heartbeat; the two
  nightly crons are 30 minutes apart and cannot contend.

**No Flyway migration was needed.** The monitor writes no run row — there was no run — and the pruner
only deletes. Verified the next free version anyway: payment-executor's head is V012, so V013 was
available and deliberately not used. `db/vendor/**` exists only in config-registry, so there was nothing
to mirror (checked, not assumed).

---

## 3. Item 3 — the webhook drain's per-endpoint coupling

### 3.1 Why concurrency did not fix it, in one sentence

T3-11 raised concurrency from 1 to 8 and said so plainly: that raised the number of simultaneously
stalled deliveries needed to stall everyone from 1 to 8. The coupling itself had **two** independent
causes, which is why it survived a change that addressed neither.

### 3.2 Cause A — composition. The healthy partner's rows were never *selected*

Selection was one query: `WHERE status='PENDING' ORDER BY created_at LIMIT batch-size`. A partner with
300 queued rows fills every 200-row page. The healthy partner's three rows were not delivered late —
**they were not in the batch at all**, and no amount of concurrency reaches a row that was never
selected. This is the half a worker-pool change cannot touch, and it is where the defect actually lived.

The partner id was already in the row, inside the JSON payload, where SQL cannot page by it. Flyway
**V009** promotes it to a column:

- `ALTER TABLE webhook_delivery_log ADD COLUMN partner_id BIGINT` (nullable) plus
  `idx_webhook_delivery_log_status_partner_created (status, partner_id, created_at)`.
- **Deliberately not backfilled.** Backfilling means parsing JSON in portable SQL across PostgreSQL 16
  and H2-in-PostgreSQL-mode, which is how a migration works on one engine and quietly mangles the other.
  Pre-V009 rows keep a NULL and are handled as one *unattributed group* — still selected, still
  delivered, and they simply drain away. Invisible rows would be a far worse failure than unfair ones.
- `WebhookPersistenceService.enqueuePendingIfAbsent` stamps the column from the payload, once, at
  enqueue — so the drain never parses every candidate row's JSON just to decide whose share it is in.

`WebhookDispatcher.selectFairly()` then asks each endpoint *with work* for an equal share of
`batch-size` and **interleaves the shares round-robin**. The interleave matters as much as the share:
appending one partner's whole share before another's would put the healthy partner's rows behind a full
share of stalled deliveries, which is the same stall with extra steps.

Cost: one indexed `DISTINCT` (bounded by the partner count, not the backlog) plus one paged read per
endpoint-with-work, instead of one read overall. That is the price of fairness and it is bounded. A
single endpoint with work degenerates to exactly the old query, so nothing changes for a single-partner
deployment.

### 3.3 Cause B — capacity. A slow endpoint that *succeeds* trips no breaker

Every worker was shared globally. An endpoint that answers in 4.9 s and then returns 200 never fails, so
no failure-counting mechanism will ever isolate it — it just holds workers.

Hence a **per-endpoint in-flight cap**, independent of the breaker:
`ceil(concurrency / endpoints-with-work)`. One endpoint → all 8. Two → 4 each. Eight → 1 each.

Derived rather than a constant, deliberately. A fixed cap of, say, 2 would throttle a single-partner
deployment from 8 workers to 2 — a fairness mechanism causing a throughput regression in the exact case
where there is nobody to be fair to. `max-in-flight-per-endpoint` can pin it, and defaults to 0 = derive.

Permits are acquired endpoint-first, then global, always in that order, so there is no cycle: a task can
hold its endpoint permit while waiting for a global one, and that is precisely the queueing wanted — the
wait happens against the endpoint's own share, not against capacity another partner could be using.

### 3.4 Cause C — failure. `WebhookEndpointCircuitBreaker`

After `failure-threshold` (default 5) consecutive failed deliveries, an endpoint's rows are skipped
**entirely** for `open-duration` (default 1 min), then exactly one half-open probe is allowed; a success
closes it, a failed probe re-arms the window. So a *failing* endpoint spends no worker time at all.

Keyed on `partnerId`, which **is** the endpoint identity: T5-4 made the signing secret per endpoint and
`DefaultWebhookTargetResolver` resolves one active endpoint per `(partnerId, environment)` with the
environment fixed per deployment. Same key the resolver, the fair share and the DLQ alert already use —
an endpoint fair-shared under one identity and short-circuited under another would be two half
mechanisms.

**Not resilience4j**, and the reason is not preference: the decision has to be readable *before* a row is
handed to a worker (the whole point is not to spend the worker), and it is keyed on a value read from a
database row rather than on a method. Wrapping the send in a resilience4j breaker would still consume a
worker per short-circuited call and would still need this registry to pick the key.

**The retry-model interaction, stated rather than buried.** A skipped row is **not** a failed attempt:
`attempt` and `last_attempted_at` are untouched, so a partner outage no longer burns the ten-attempt
budget that exists to absorb transient failures. The consequence is real and is a trade, not a free win:

- DLQ promotion for a dead endpoint becomes **slower**, not impossible. The half-open probe is a real
  attempt and is recorded as one, so rows keep walking through `RetryPolicy`'s ten attempts toward
  `webhook_dlq` — the existing terminal state, unchanged and not duplicated.
- The growing PENDING backlog stays visible through the existing `WEBHOOK_QUEUE_DEPTH` /
  `WebhookBacklogMonitor` alerts, so a partner that stays down surfaces without this class inventing an
  escalation of its own.
- Opening the breaker raises `WEBHOOK_ENDPOINT_CIRCUIT_OPEN` (an `alert_event` row, P2, deduped per
  partner over the existing 10-minute window, on the OPEN **transition** only). This is not decoration:
  the fix deliberately stops attempting deliveries, and suppressing work silently would trade one
  invisible failure — everyone stalled behind one dead partner — for another — one partner quietly not
  delivered to. No migration was needed; `alert_event.alert_type` has no CHECK constraint (verified).

Breaker state is per-JVM and in memory, which is correct rather than a shortcut: the drain is
`@SchedulerLock`ed so exactly one instance delivers at a time, and "are this endpoint's last few
attempts failing?" is an observation of the current drain, not a fact that must outlive it. A restart
re-probes immediately — the safe direction.

### 3.5 The defect found while proving this

`services/notification-webhook/src/main/resources/application.properties` shipped:

```
gmepay.webhook.dispatcher.interval-ms=${GMEPAY_WEBHOOK_DISPATCHER_INTERVAL_MS:30000}
```

That line **overrides** the `@Scheduled(fixedDelayString = "${…:5000}")` default T3-11 introduced. So
T3-11's "cycle 30 s → 5 s" — one of the two numbers its ~16× headroom claim rests on — was **not in
effect in any deployment**. The property file wins over the annotation default, and nothing tested the
shipped value.

Now `5000`, and pinned by a test that reads the shipped properties file, alongside `fair-selection`,
`breaker.enabled` and `max-in-flight-per-endpoint`. The general lesson is the same one T3-11's Kafka
finding produced: **a default in code that a shipped config file silently overrides is indistinguishable
from a default that was never changed.**

### 3.6 What was reused, and what this still does not do

Reused rather than paralleled: `webhook_dlq` stays the single terminal state; `RetryPolicy` is untouched;
endpoint identity is T5-4's `(partnerId, environment)`; alerting is `WebhookAlertService` writing
`alert_event`. New `WebhookPayloads` is the *single* copy of the partner-extraction rule — it existed
twice before (resolver, alert service) and fairness would have made it three. The resolver still reads
the **payload** rather than V009's column to decide where to deliver: if the two ever disagreed, the
consequence is a row in the wrong fair share (harmless) rather than a webhook POSTed to the wrong
partner (not).

The pre-existing `WebhookDispatcher` constructors keep the **old** global-FIFO selection and no breaker.
A constructor kept for backwards compatibility that quietly changed selection would not be backwards
compatible.

**Still not done, deliberately:** fairness is per partner, not per event type, so one partner's slow
endpoint still delays that partner's *other* events. Ordering is preserved only as "attempts started
oldest-first", exactly as before. Both are properties of the previous design this change did not alter.

---

## 4. Verification

| Check | Result |
|---|---|
| `:services:notification-webhook:test` | **161 tests, 0 failures, 0 errors** |
| `:services:payment-executor:test` | **513 tests, 0 failures, 0 errors** |
| `:services:revenue-ledger:test` | **135 tests, 0 failures, 0 errors** |
| `:services:prefunding:test` | **191 tests, 0 failures, 0 errors** |
| `:libs:lib-events-kafka:test` | **17 tests, 0 failures, 0 errors** |
| `gradlew testClasses` (whole repo) | **BUILD SUCCESSFUL** |
| Kafka guard, negative check | removing `setConcurrency` from prefunding → **1 failure**; restored → green |
| `check_internal_auth_wiring.py` | 95/95 |
| `check_monitoring_wiring.py` | 37/37 |
| `check_helm_chart_wiring.py` | 194/194 |
| `check_gitleaks_config.py` | findings=0, missed=0, false-positives=0, re2-bad=0 |
| `check_load_harness_wiring.py` | OK |
| `node docker/keycloak/check-topology.mjs` | 101/101 |
| PyYAML parse: `docker-compose.yml` + all four Helm values | all five parse as dicts |

**Not performed, deliberately:** no fleet, no Docker, no server, no load run. The fairness numbers in
§3 are measured by a real-threaded test against a stubbed sender — they prove the coupling is gone, not
a production throughput figure.

### New / changed tests

| File | What it proves |
|---|---|
| `libs/lib-events-kafka/.../KafkaListenerConcurrencyWiringGuardTest` **(new)** | Every hand-built factory in `services/**` calls `setConcurrency`; the known-gap baseline can only shrink; each fixed factory has its own behavioural test. |
| `services/revenue-ledger/.../RevenueLedgerKafkaConcurrencyTest` **(new)** | Concurrency read back off the built factory; default 3 = the partition count; `0` clamps to 1. |
| `services/prefunding/.../PrefundingKafkaConcurrencyTest` **(new)** | The same three, with the float-release consequence stated in the assertions. |
| `services/payment-executor/.../MissedLedgerOpsRunMonitorTest` **(new)** | A stopped job alerts through the **real** `OpsAlertPipeline` with the right type/severity/subject/eventType; a healthy fleet and a single missed cycle stay silent; never-ran reads differently from long-silent; a cold start does not alert; the cooldown suppresses **and then releases while the job is still silent**; a disabled job is not monitored; every `LedgerOpsJob` constant is classified. |
| `services/payment-executor/.../LedgerOpsRunRetentionTest` **(new)** | Real H2 + full Flyway: only rows past the window are deleted (the row one day inside survives); the newest run of a 500-day-silent job survives; the exemption is per job, not one row overall; nothing expired = zero deleted; the shipped 365 and a nonsensical `0` are pinned. |
| `services/payment-executor/.../config/ShedLockTest` | Extended to 6 `@Scheduled` methods and pool ≥ 6. |
| `services/notification-webhook/.../WebhookEndpointFairnessTest` **(new)** | **The real timing test.** A healthy partner's deliveries finish while a stalled endpoint is still in flight, in well under half what the old shared pool needed; one endpoint still gets the whole pool; an endpoint never exceeds its fair share of workers; a failing endpoint trips after 5 failures without tripping anyone else, without opening further sockets, and **without spending the retry budget** (`markAttemptFailedOrDlq` called exactly 5 times); the OPEN alert fires once; the shipped configuration matches the design. |
| `services/notification-webhook/.../WebhookFairSelectionQueryTest` **(new)** | The composition half against a real database: the old global query provably excludes the healthy partner, the per-endpoint reads include all of its rows, oldest-first holds inside a share, the DISTINCT scan includes the unattributed group, and the enqueue path stamps both envelope shapes (flat and nested outbox). |
| `services/notification-webhook/.../WebhookDrainCapacityTest` | Updated for the new selection path; its concurrency arithmetic is unchanged. |

### Flyway

| Module | Version | Shape |
|---|---|---|
| notification-webhook | **V009** | `ALTER TABLE … ADD COLUMN partner_id BIGINT` + one index. Additive, nullable, no backfill, engine-neutral (PostgreSQL 16 + H2 in PG mode). V001–V008 existed; proved to apply on top of them by two tests that run the full migration set. |
| payment-executor | **none** | V012 is still the head. Nothing to migrate: the monitor writes no row, the pruner only deletes. |
| revenue-ledger / prefunding | **none** | One-line Java changes. |

`db/vendor/{h2,postgresql}` exists **only** in config-registry — verified, not assumed — so there was
nothing to mirror in any touched module.

---

## 5. Handoff / still open

1. **`services/ops-partner-bff` — `OpsAlertKafkaConsumerConfig` still ignores the concurrency
   property.** The exact two-line diff is in §1.3 and in the guard test's own `KNOWN_UNFIXED` comment.
   Deleting the baseline entry is part of the fix; the test will say so. **No new config key is needed
   in `docker-compose.yml` or `deploy/helm/**`** — `SPRING_KAFKA_LISTENER_CONCURRENCY` is already in the
   ABI ConfigMap and would simply start working.
2. **Kafka partitioning is still 1 in existing topics.** Unchanged from T3-11 §4 and still an
   operational migration (drop `kafka-data` locally; set `num.partitions` on MSK *before* topic
   creation; Azure Event Hubs Standard cannot be raised in place). Until then concurrency 3 is an upper
   bound nothing reaches. Also unchanged: `.smoke/infra-up.sh:24` still creates topics with
   `--partitions 1`.
3. **Two owner decisions, listed in §2.4:** the three missed-run thresholds and the 365-day run-ledger
   retention. Both ship as configurable engineering defaults; neither is a commitment.
4. **Per-event-type fairness inside one partner** (§3.6) is not implemented.
5. **Nothing here has been exercised against a running fleet.** The fairness proof is a real-threaded
   test against a stubbed sender; the missed-run alert is proved through the real pipeline with a
   capturing publisher, not against a live `ops_alerts` + notification sink.
