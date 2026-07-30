# RUNBOOK — Load, Soak & Capacity

> Closes (partially) gap **T3-5** (no SLA measurement, no load test, no capacity plan) in
> `Documentation/GAP_REGISTER.md`. Evidence: `outputs/agent/audit_coo-ops_2026-07-28.md` §8 (no
> SLA/SLO definition or measurement) and §9 (no load testing, capacity plan, or backpressure
> analysis). Implementation notes: `outputs/agent/fix_t3-load-and-capacity_2026-07-28.md`.
>
> **Read §7 before you rely on this, and §6 before you sign anything.** T3-5 splits into a half that
> is now built (the measurement) and a half that is a business decision and has deliberately *not*
> been guessed at (the targets). Nothing here entitles anyone to promise a partner a latency or an
> availability number.

---

## 0. What this is, in one paragraph

There is now a load/soak harness that drives the real money path — the wallet scan→pay flow and the
orchestrated authorize→confirm flow — against an **already-running local fleet**, at a configurable
rate and concurrency; it measures end-to-end p50/p95/p99, achieved throughput, and error rate broken
down by the platform's own structured error codes (so a `TRANSACTION_LIMIT_EXCEEDED` decline is
distinguishable from a 503); it scrapes each money-path service's `/actuator/prometheus` before and
after so JVM and pool saturation come from the platform's own instrumentation rather than a second,
disagreeing set of numbers; and it writes a machine-readable `result.json` plus a short human
`summary.md`. It **refuses to start the fleet**, **refuses any non-local target**, and **never runs in
CI**. Alongside it, §4 is a capacity analysis done by *reading the code* — every ceiling with a
file/line citation, and an explicit "unknown without running" wherever a number genuinely cannot be
derived statically.

**It has never been run.** It shipped unexecuted: the fleet was deliberately not started as part of
this work. Every number in a report it produces will be the first of its kind.

---

## 1. Running it

### 1.1 Prerequisites — you start the fleet, the harness does not

The harness has **no** `bootJar` dependency and no fleet lifecycle. Booting 20 JVMs is
`run-fleet.ps1`'s job. A generator that also owned its target's lifecycle would time cold JIT and
Flyway migrations as if they were payment latency, and would make *"the fleet was already unhealthy"*
indistinguishable from *"the load broke it"*.

```powershell
# 1. Start the fleet yourself (18xxx port band). First run needs -Build.
powershell -File run-fleet.ps1 -Build
```

If a target is not answering, the harness stops before sending anything and prints exactly that
command. It probes `GET /v1/_probe` and treats *any* HTTP status as alive — the same heuristic
`SchemeFleet` and `run-fleet.ps1` use, so a 401 from a gated route still counts as up.

### 1.2 The command

```powershell
cd D:\GMEPay+\code
.\gradlew.bat :e2e-tests:loadTest --args="--i-know-this-is-not-prod --rate=10 --duration=2m"
```

With no `--args` the task prints usage and exits 0. It never runs a default-shaped run at whatever
happens to be listening.

### 1.3 The two safety interlocks

Both must hold. Not either.

| | |
|---|---|
| **Every** target URL is local | `localhost`, `127.0.0.1`, `0.0.0.0`, `::1`, `host.docker.internal` / `kubernetes.docker.internal` / `gateway.docker.internal`, or a host ending `.localhost` / `.local` / `.internal.test`. One non-local URL aborts the whole run — there is no "mostly local" mode, because a partial run still lands real traffic somewhere. A private LAN address (`192.168.*`, `10.*`) is **refused**: "not the internet" is not the same as "this machine". |
| `--i-know-this-is-not-prod` was passed | A hostname check alone is not proof. A Cloudflare tunnel, an SSH forward or a `hosts` entry can make a production ingress answer on `localhost` — and this repo's own deploy plan is a tunnel fronting a laptop. The flag is the human saying "I know what this box is wired to". |

Additionally refused: a non-`http(s)` scheme, a bare `host:port` (ambiguous), a URL with embedded
credentials, and **any URL containing a production marker** (`prod`, `prd`, `live`, `gmepay.com`,
`gmeremit.com`) *even on a local host* — `prod.localhost` and a forwarded `localhost:8080` are exactly
how this goes wrong in practice.

Refusal exits **3** and sends nothing. All reasons are printed at once, not just the first.

### 1.4 Why it can never run in CI

The entry point is a `main`, not a JUnit test — there is no tag to include and no class to discover,
so `gradlew build`, `integrationTest` and `:e2e-tests:e2eTest` (the three things
`.github/workflows/ci.yml` runs) cannot reach it. The only path in is an explicit
`:e2e-tests:loadTest`, a `JavaExec` deliberately left out of `check` and `build`.
`scripts/check_load_harness_wiring.py` fails if a workflow ever mentions the task, if anything
`dependsOn` it, or if the harness grows a `@Test`/`@Tag`.

The *guard* is tested in the ordinary `test` task (untagged), so every `gradlew build` re-proves that
the harness refuses a non-local target. That is the inverse arrangement to the harness itself, and it
is the point.

### 1.5 Options

| Option | Default | Notes |
|---|---|---|
| `--scenario=wallet-pay\|authorize-confirm` | `wallet-pay` | §2.1 |
| `--rate=<req/s>` | `5` | Open-model arrival rate, not "requests in flight" |
| `--concurrency=<n>` | `16` | In-flight cap. Arrivals past it are **shed and counted**, never queued |
| `--duration=<90s\|5m\|1h>` | `60s` | Measured window. Use `1h`+ for a soak |
| `--warmup=<10s>` | `10s` | Discarded window before it |
| `--timeout=<20s>` | `20s` | Per-request; a timeout is an ERROR, never a decline |
| `--payment-executor-url=` | `http://localhost:18084` | `run-fleet.ps1:152` |
| `--rate-fx-url=` | `http://localhost:18101` | `run-fleet.ps1:198`; only used by `authorize-confirm` |
| `--internal-secret=` | `$GMEPAY_INTERNAL_AUTH_SECRET`, else the documented `run-fleet.ps1` dev marker | Presented as `X-Gme-Internal` on every request and every scrape |
| `--scrape=name=url,…` | payment-executor, transaction-mgmt, merchant-qr-data, scheme-adapter-zeropay | `--scrape=` (empty) disables |
| `--targets=<file>` | `Documentation/SLO_TARGETS.properties` | §6 |
| `--out=<dir>` | `e2e-tests/build/load-results` | |
| `--qr` `--amount` `--currency` `--partner` `--scheme` `--direction` | match `WalletScanPayE2ETest` | The default QR is the same full EMVCo payload (valid tag-63 CRC) the E2E test pays, so it is already proven to round-trip through `sim-scheme`'s `/qr/decode` |

Exit codes: `0` all declared targets met **or nothing declared** (which is *not* a pass — read the
summary), `2` bad arguments, `3` refused target, `4` preflight failed, `5` a declared target was
missed.

---

## 2. What it measures, and the choices behind each number

### 2.1 The two money paths

**`wallet-pay`** — one `POST /v1/pay` on payment-executor, and five services of work behind it:
merchant-qr-data (resolve + ACTIVE check) → scheme-adapter-zeropay → `sim-scheme` (authorize +
commit) → transaction-mgmt (persist) → revenue-ledger (fee journal). One client call, the whole
cascade in the measured latency — which is what makes it the right shape for a capacity run: the first
pool to saturate *anywhere* in it shows up as this number growing.

**`authorize-confirm`** — the two-phase flow of `Documentation/SETTLEMENT_FLOW_SPEC.md` §4/§7.1:

```
POST rate-fx       /v1/quotes/partner        → TTL-locked quote_id
POST payment-exec  /v1/payments/authorize    → float reserve + PENDING txn (nothing irreversible)
POST payment-exec  /v1/payments/{id}/confirm → the scheme submit + float capture
```

Measured end-to-end **and per step**, because the three steps stress different ceilings: the quote
hits rate-fx's snapshot store, the authorize takes prefunding's per-partner row lock (§4, ceiling #2),
and only the confirm makes the scheme call (§4, ceiling #3). One blended number could not tell you
which of the three moved.

Every iteration mints a fresh `partner_txn_ref`. This is load-bearing, not tidiness: `authorize` is
idempotent per `(partner, partner_txn_ref)` (`PaymentController.java:120-136`), so a reused ref would
replay the first authorization and the run would report beautiful latencies for a primary-key lookup.

### 2.2 Decline ≠ error

Before the platform had structured error codes, a load report could only say "18% non-2xx", which
conflates *the platform correctly refusing money* with *the platform falling over* — and at 10x volume
the first is expected and the second is the finding. So every attempt lands in exactly one bucket:

| Bucket | What it is | Counted in error rate? |
|---|---|---|
| **OK** | 2xx | no |
| **DECLINED** | 4xx (`400/402/403/404/409/422`) carrying a structured code — `TRANSACTION_LIMIT_EXCEEDED`, `SCHEME_CLOSED` (409, T3-6 operating hours), `SCHEME_OPERATION_UNSUPPORTED`, `MERCHANT_INACTIVE`, … from the `ApiError` envelope `{code, message, retryable, requestId}` or the wallet endpoint's `{status, declineReason}` | **no** — working as designed |
| **ERROR** | 5xx, timeout, connection failure, **429**, or a 4xx with *no* recognisable code | yes |
| **SHED** | the harness hit its own `--concurrency` cap and never sent the request | no — reported separately and loudly |

Two deliberate asymmetries:

- **An unstructured 4xx is an ERROR.** Calling it a decline would make a broken run (wrong payload,
  wrong contract, wrong port) look healthy.
- **429 is an ERROR named `RATE_LIMITED`.** It is structured, but being throttled means the platform
  could not take the offered load, which is precisely what a capacity run is looking for. See §4
  ceiling #4 — the gateway's limit is 50 payments/s **per partner per replica**.

The classifier keys on the *shape* of the envelope, not on an enumerated list of codes, so a code added
to `libs/lib-errors` `ErrorCode` after this was written is tallied correctly with **no harness change** —
it simply appears as its own row in the outcome-code table. That includes the AML-screening codes being
built under register item **T5-3** (a `*_NOT_SCREENED`-class outcome), which are **not** in `ErrorCode`
at the time of writing. Two cautions for whoever finishes T5-3 and then runs this: if the seam defaults
to *not* blocking unscreened payments (as its design intends), those payments arrive here as **OK** and
are invisible in this report — the unscreened count has to be read from T5-3's own counter, not from
this tally; and if a later profile *does* block, the harness will report it as a decline, which is
correct but will move the success rate without any capacity change.

### 2.3 Latency

- **Nearest-rank percentiles, no interpolation.** A reported p99 is a latency some request really had.
  An interpolated percentile can report a number no request ever experienced, which is a poor basis
  for a contractual commitment.
- **Exact, not sketched.** Every sample is kept (a few thousand `long`s). A t-digest would add a
  dependency and an approximation error to save memory nobody needs.
- **Errors are counted, not timed.** A 3 ms `CONNECTION_REFUSED` would otherwise drag p50 down and
  make a collapsing run look fast. Declines *are* timed — a declined payment is still a completed
  round trip the caller waited for.
- **Nothing is ever reported as 0 when it means "not measured".** An unmeasured percentile is `n/a` in
  the text and `null` in the JSON.

### 2.4 Throughput, and the open model

Arrivals are scheduled at fixed intervals from a monotonic baseline (`start + i/rate`), **not** by
sleeping between requests. The latter is a closed model whose offered rate silently drops as the
system slows — hiding exactly the degradation being looked for.

When `--concurrency` requests are already in flight, the arrival is **shed and counted**, never
queued. A queued arrival would add client-side waiting to the next request's measured latency
(coordinated omission) and report the harness's own backlog as the platform's. **A non-zero shed count
means the run did not achieve the offered rate**, so every percentile describes a lighter load than
requested — the summary says so in a call-out box rather than leaving it in a field nobody reads.

Reported throughput counts **completed** payments only. Counting attempts would let a run that shed
half its arrivals report the rate it was *asked* for.

### 2.5 Platform metrics, before and after

The harness instruments **nothing** itself. T3-2 put a real Micrometer registry on all 20 deployables
(root `build.gradle`) and exposed `/actuator/prometheus` fleet-wide
(`MetricsExposureEnvironmentPostProcessor` in `libs/lib-errors`), so heap, HikariCP and the Tomcat
pool are already instrumented by the platform; re-measuring them client-side would produce a second,
disagreeing set of numbers. Scraping the platform's own endpoint means the load report and the
production dashboard read the same series.

Retained families (everything else is dropped — a full scrape is hundreds of series per service):
`hikaricp_connections_{active,idle,pending,max,timeout_total,acquire_seconds_*}`,
`tomcat_threads_{busy,current,config_max}`, `jvm_memory_{used,max}_bytes`, `jvm_threads_*`,
`jvm_gc_pause_seconds_{count,sum}`, `process_cpu_usage`, `http_server_requests_seconds_count`,
`executor_{active_threads,queued_tasks,pool_max_threads}`,
`kafka_consumer_fetch_manager_records_lag_max`.

The two rows to read first:

- **`hikaricp_connections_pending > 0`** — threads queued waiting for a DB connection. This is the
  first-break the COO audit predicted; `hikaricp_connections_timeout_total` rising means some gave up
  (HikariCP's default `connection-timeout` is 30 s — see §4 ceiling #8).
- **`tomcat_threads_busy_threads` at `tomcat_threads_config_max_threads`** — the HTTP ceiling instead
  of the DB one. Note `run-fleet.ps1:337` caps this at **20**, so a local run hits the HTTP wall far
  earlier than a default deployment would.

A failed scrape is **recorded, never fatal**, and the distinction matters: per
`RUNBOOK_MONITORING.md` §1.3 the endpoint is token-gated on api-gateway / payment-executor /
prefunding / rate-fx / scheme-adapter-zeropay and anonymous on twelve others, so a 401 means "wrong
`--internal-secret`", whereas a connection failure on a service that just passed preflight is itself a
finding. Aborting a run that has already generated real load would be worse than reporting both.

---

## 3. Output

`e2e-tests/build/load-results/` (override with `--out`):

- **`result.json`** — `schemaVersion: 1`. Run parameters, counts, rates, latency percentiles
  (`null` where unmeasured), per-step percentiles, the full outcome-code tally, the SLO verdict with
  every check, the before/after metric deltas, scrape failures, and a `caveats` array. This is what
  you diff between a baseline run and a 10x run.
- **`summary.md`** — the same run as a page you can paste into a ticket, ending in its own "what this
  run does NOT tell you". Also printed to stdout.

Both are written rather than one derived from the other at read time: re-doing percentile maths in a
spreadsheet is how two different numbers for the same run start circulating.

---

## 4. Capacity: what breaks first at 10x

**This section is derived by reading the code, not by running the harness.** Every row carries a
file/line citation. Where a limit genuinely cannot be determined without running, it says
**UNKNOWN WITHOUT RUNNING** rather than an estimate.

The single most important structural fact frames everything below:

> **Every service ships at exactly one replica.** `deploy/helm/gmepay/values.yaml:59` sets
> `defaultReplicas: 1`, `templates/_deployment.tpl:61` falls back to it, and **no service in any of
> the four values files overrides it**. There is no HorizontalPodAutoscaler and no
> PodDisruptionBudget anywhere in `deploy/**`. So the only way to absorb 10x is to scale out — and
> §4.2 is the list of things that *break when you do*.

### 4.1 Ordered: the ceilings a 10x volume increase hits, soonest first

| # | Ceiling | Evidence (file:line) | Why it is first |
|---|---|---|---|
| **1** | **Kafka: 1 partition × concurrency 1.** Every consumer group is a single thread. | `docker-compose.yml:332` `KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"` with **`KAFKA_NUM_PARTITIONS` never set** → broker default **1 partition**; no `NewTopic`/`TopicBuilder`/`spring.kafka.admin` anywhere; `.smoke/infra-up.sh:24` is the only explicit creation and uses `--partitions 1`. Consumer factories built bare (Spring default concurrency = 1): `notification-webhook/.../WebhookKafkaConsumerConfig.java:98-108`, `revenue-ledger/.../RevenueLedgerKafkaConsumerConfig.java:94-104`, `prefunding/.../PrefundingKafkaConsumerConfig.java:84-94`, `ops-partner-bff/.../OpsAlertKafkaConsumerConfig.java:85-95`. `spring.kafka.listener.concurrency` is set in **no** file. | One thread per group must finish its DB work per record before the next poll (`ENABLE_AUTO_COMMIT=false` + `AckMode.MANUAL`, e.g. `WebhookKafkaConsumerConfig.java:66,105`), and each failure burns 3 synchronous attempts (`FixedBackOff(0L, 2)`, same files). **Adding replicas adds only idle consumers** — with one partition the extra consumers get no assignment. Fixing this is a topic repartition, i.e. an operational migration, not a config bump. |
| **2** | **prefunding: a per-partner exclusive row lock plus 4 aggregate reads on *every* transaction.** | `prefunding/.../service/PrefundingService.java:300` `lockOrThrow(partnerId)` → `persistence/PartnerBalanceRepository.java:19` `@Lock(PESSIMISTIC_WRITE)`; then `:320` `findByPartnerIdAndTxnRef`, `:330-332` `sumDaily`/`sumMonthly`/`sumAnnual`, `:339` `netDailyCount`, `:347` `save`. Queries: `persistence/CumulativeUsageLedgerRepository.java:21-40` — `SUM(amountUsd)` over an **append-only** table. Same lock on reserve/capture/release (`api/internal/PrefundingInternalController.java:22,101`) and on reverse (`PrefundingService.java:381,392-396`). | This **serialises all of one partner's traffic**: per-partner throughput is 1 / (lock hold time), and the lock is held across four aggregate queries. The annual `SUM` scans a year of that partner's rows, so the cost **grows through the calendar year** — the same load is slower in December than in January. GMEPay+'s traffic is concentrated in a handful of partners, so this bites long before any global limit. |
| **3** | **Scheme call bulkhead: 16 concurrent, wait 0.** | `payment-executor/src/main/resources/application.properties:130-131` `resilience4j.bulkhead.configs.default.max-concurrent-calls=16`, `max-wait-duration=0`; applied in `client/rest/ResilientSchemeClient.java:145-150` (bulkhead outside breaker). | The sharpest *numeric* ceiling on the outbound leg: the 17th concurrent scheme call is rejected immediately, not queued. With a scheme read timeout of 5 s (`application.properties:136-137`) the theoretical ceiling is ~3.2 confirms/s per scheme at full latency. This is the one ceiling a load run can confirm precisely, and the only service with resilience4j at all (`payment-executor/build.gradle:15`). |
| **4** | **Gateway rate limit: 50 payments/s per partner, per replica, in memory.** | `api-gateway/.../filter/RateLimitFilter.java:55,84-88,117-118` (`WINDOW = 1s`, key `partnerId:scope`); `ratelimit/RateLimitProperties.java:38,41,44` `globalPerSecond=100`, `ratesPerSecond=20`, `paymentsPerSecond=50`; `src/main/resources/application.yml:104-113` states the per-replica multiplication itself; `fail-open: false`. | At 10x this is a **hard 429 wall** at the edge, and the harness will report it as `RATE_LIMITED` errors rather than declines. Raising it means either a bigger number (with no distributed store behind it) or the T0-7 fix. See §4.2 #1. |
| **5** | **`@Scheduled` pool size 1 per service; several services stack 3–7 jobs on it.** | `spring.task.scheduling.pool.size` is set in **no** `application*` file → Spring default **1**. `scheme-adapter-zeropay/.../batch/ZeroPayBatchScheduler.java:82` says so in a comment ("`@Scheduled` methods share Spring's scheduler pool and adjacent windows are only two minutes apart") with six crons at `:138,144,154,160,170,176`. transaction-mgmt: `ExpirySweeperService.java:93` (10 s), `StuckTransactionAlertSweeper.java:106` (60 s), `OutboxPublisher.java:60` (**1 s**) — 3 jobs, 1 thread. settlement-reconciliation: 7 jobs (`ReconScheduler.java:97,115`, `SettlementGenerationScheduler.java:80,86,92`, `CorridorReconScheduler.java:50`, `OutboxPublisher.java:43`). reporting-compliance: `BokReportScheduler.java:107` and `KofiuFeedScheduler.java:101` are on the **same second** (`0 0 2` KST), plus `HometaxInvoiceScheduler.java:107`. payment-executor: 5 jobs (`AuthorizationExpirySweeper.java:55`, `RevenuePostingReplayScheduler.java:48`, `OpsAlertRetentionSweeper.java:37`, `DayCloseScheduler.java:57`, `FxExposureScheduler.java:50`). | At 10x, transaction-mgmt's 1-second outbox tick lengthens; because it shares the single thread, it **starves the 10-second expiry sweeper and the stuck-transaction alerter** — i.e. the very safety nets T3-3 armed stop firing exactly when volume makes them matter. This degrades silently: no metric exists for scheduler lag. |
| **6** | **Webhook drain arithmetic already does not close at 1x.** | `notification-webhook/.../dispatcher/WebhookDispatcher.java:87` `batch-size:200`, `:104-107` `fixedDelay 30000`; `client/rest/RestWebhookHttpClient.java:49-50` `CONNECT_TIMEOUT=5s`, `READ_TIMEOUT=10s`. | 200 sequential deliveries × up to 10 s = up to **2000 s** of work on a 30 s interval, on the single scheduler thread of #5. One slow partner endpoint stalls every other partner's webhooks. `WebhookBacklogMonitor` will raise `WEBHOOK_BACKLOG`; nothing drains faster. |
| **7** | **Untimeouted inter-service HTTP on the money path.** | `payment-executor/.../client/rest/ClientBeans.java:43-46` sets **only** `new JdkClientHttpRequestFactory()` — no connect/read timeout — and `RestClientSupport.java:23-27` adds only a Jackson converter. Inherited by `RestPrefundingClient` (float debit on every payment), `RestPartnerConfigClient` (the per-txn limit read), `RestRateClient`, `RestTransactionClient`, `RestRevenueLedgerClient`, `RestQrClient`, `RestSmartRouterClient`. Same posture in `smart-router` (`client/RestPartnerSchemeResolver.java:58`, `resolve/RestPartnerSchemeRegistry.java:63`, `resolve/RestSchemeOperatingHoursSource.java:57`) and `ops-partner-bff` (`client/rest/ClientBeans.java:38-41`), and in **every scheme adapter's outbound leg** (`scheme-adapter-zeropay/.../ZeroPaySchemeApiClient.java:52`, `-nepal/.../NepalSchemeApiClient.java:62`, `-ninepay/.../NinepayApiClient.java:78`, `-sendmn/.../SendmnSchemeApiClient.java`). No retry policy on any hop (no `spring-retry`, no `@Retryable`). | A slow peer holds a Tomcat worker until the OS closes the socket. The 2 s/5 s discipline exists on the *hub→adapter* hop (`application.properties:136-137`) but **not on the adapter→scheme hop**, so an adapter can outlive payment-executor's 5 s read timeout — which manufactures `UNCERTAIN` transactions under load, i.e. load turns into manual ops work. |
| **8** | **HikariCP is at its default of 10 on every service, and nothing anywhere configures it.** | A repo-wide search for `hikari` in `services/*/src/main/resources/application*`, `docker-compose.yml`, `deploy/helm/**` and all Java returns **nothing**. 15 services declare a JDBC datasource: `config-registry/…application.properties:6`, `notification-webhook:8`, `payment-executor:7`, `prefunding:3`, `qr-service:8`, `rate-fx:7`, `revenue-ledger:5`, `scheme-adapter-sendmn:7`, `scheme-adapter-zeropay:8`, `transaction-mgmt:7`, `kyb-adapter:12` (H2), `auth-identity/…application.yml:8`, `reporting-compliance:7`, `settlement-reconciliation:5`, `scheme-adapter-ninepay:10`. Defaults therefore: `maximum-pool-size=10`, `minimum-idle=10`, `connection-timeout=30000`, leak detection **off**. `run-fleet.ps1:337` overrides to **5/1** for local runs only; there is no prod equivalent. | **Not** the DB-connection *exhaustion* the COO audit feared — the databases are one-per-service (`docker-compose.yml:9-10`, 15 separate `postgres:16-alpine` at `:123…:256`; `values-aws.yaml:107-121` / `values-azure.yaml:109-123` keep the same posture), and at 1 replica × 10 connections there is enormous headroom against postgres's default `max_connections=100`. The ceiling is **pool saturation inside one service**: 10 concurrent DB operations, then callers queue up to 30 s and fail (`.github/workflows/ci.yml:103` already records Hikari timing out at 30 s in CI). Watch `hikaricp_connections_pending`. |
| **9** | **`-Xmx320m` with `-XX:+ExitOnOutOfMemoryError` on every compose service, against several unbounded in-memory maps.** | `docker-compose.yml:452,510,536,558,573,599,644,685,762,779,866,906,928,959,986,1006,1045,1080,1168` — `JAVA_TOOL_OPTIONS: "-Xmx320m -XX:+ExitOnOutOfMemoryError"`. Unbounded maps: `api-gateway/.../ratelimit/InMemoryRateLimitStore.java:32,66` (sweeps only *after* 50 000 keys), `api-gateway/.../registry/IpAllowlistCache.java:39,49` ("entries are only ever overwritten on refresh, **never evicted**", consulted on every partner request), `api-gateway/.../filter/WebClientRbacClaimResolver.java:12,15` (no size bound), `auth-identity/.../rbac/RbacResolutionService.java:47,58` (no size bound), `ops-partner-bff/.../client/PartnerDirectory.java:68-76` (resolved map "cached for the JVM's life"), `ops-partner-bff/.../alert/paging/OpsPagingDispatcher.java:53`. | The JVM **dies rather than degrades**. At 10x partner cardinality the rate-limit map can hold 50 000 keys before its first sweep, in a 320 MB heap. In Kubernetes the heap is worse and **UNKNOWN WITHOUT RUNNING**: Helm sets no `JAVA_TOOL_OPTIONS` at all, so the JVM defaults to ~25 % of the 640 Mi limit (`values.yaml:63-68`) ≈ 160 Mi. |
| **10** | **auth-identity's nonce store does a full-table scan per authenticated request.** | `auth-identity/.../domain/InMemoryNonceStore.java:18,26` — unbounded `ConcurrentHashMap` with `entrySet().removeIf(...)` on **every** `checkAndSet`. Its own javadoc (`:10`) says "local development and unit tests only". | O(n) per request against a map that grows with request rate — i.e. **quadratic** in offered load. This is the clearest "breaks first" candidate on the auth edge, and it is a class the code already labels as not production-grade. |

### 4.2 Ordered: what breaks when you scale out to absorb 10x

Because everything ships at 1 replica, absorbing 10x means adding replicas. These are the things that
*become wrong* — not slow — when you do. T0-7 flagged the first two; the rest are the same class of
defect found while doing this analysis.

| # | Breaks | Evidence | Consequence of N replicas |
|---|---|---|---|
| **1** | **Rate limiting** | `api-gateway/.../ratelimit/InMemoryRateLimitStore.java:28,32` — the **only** `RateLimitStore` implementation, `@Primary`, `matchIfMissing = true`; `application.yml:104-107` states it | The effective cap becomes **N × 50** payments/s per partner. The limit stops being a limit. |
| **2** | **Replay protection** | `api-gateway/.../replay/InMemoryNonceStore.java:26,30,32` — only `NonceStore` impl, `@Primary`, `matchIfMissing = true`; `application.yml:97-102` `nonce-ttl-seconds: 300` and states it: a captured request "can be replayed once per replica inside the 5-minute window" | A signed request is replayable **N times**. This is a money-path integrity defect, not a performance one. |
| **3** | **Idempotency on transaction-mgmt** | `transaction-mgmt/.../idempotency/InMemoryIdempotencyStore.java:22`; `application.properties:33-35` — the Redis store activates **only** when `spring.data.redis.host` is present, and `docker-compose.yml:1171` sets `SPRING_DATA_REDIS_HOST` on **api-gateway only** | In the shipped compose stack the 24 h idempotency window is **per replica**, so the same partner retry can create N transactions. |
| **4** | **Scheduled jobs without ShedLock** | Only prefunding has it (`prefunding/build.gradle:38-39`, `outbox/OutboxConfig.java:34` `@EnableSchedulerLock`, `outbox/OutboxPublisher.java:50`). transaction-mgmt declares a config (`config/ShedLockConfig.java:14`) but revenue-ledger's and settlement-reconciliation's outbox publishers have none, and `notification-webhook/.../dispatcher/WebhookDispatcher.java:44` states "A distributed lock (e.g. ShedLock) is a follow-up" | Every un-locked sweeper, publisher and batch **runs N times**: duplicate webhooks, duplicate settlement files, duplicate ZeroPay batches. This caps those services at 1 replica *regardless of throughput*, which is the real reason #1 and #6 in §4.1 cannot be fixed by scaling. |
| **5** | **Ops paging** | `deploy/helm/gmepay/values.yaml:756-757` — the escalation scheduler is documented single-replica-only with no ShedLock, "so arming it under >1 replica would double-page"; `ops-partner-bff/.../alert/paging/OpsPagingDispatcher.java:53` de-dup map is per-JVM | Pins ops-partner-bff to 1 replica when escalation is on. |
| **6** | **The aggregate ops alert view** | `ops-partner-bff/.../alert/OpsAlertStore.java:35,40-51` — `ArrayDeque` capped at 200 under one `synchronized` block; `values.yaml:752` calls it "STILL OPEN (not fixable from a values file)" | At 10x alert volume a 200-entry window covers minutes, the mutex is contended by consume *and* every read, and the whole thing is lost on restart. (payment-executor persists what *it* raises — `ops_alerts`, Flyway V006 — but nothing else does.) |
| **7** | **Operating-hours alert de-dup** | `payment-executor/.../domain/SchemeOperatingHoursGate.java:96` — `unverifiedAlerted` map is per-instance | N replicas raise N `SCHEME_HOURS_UNVERIFIED` alerts per scheme per day. |

### 4.3 The 10-minute operating-hours cache (T3-6), specifically

Asked about directly, so recorded precisely:

| | |
|---|---|
| TTL | **600 000 ms / 10 min** — `payment-executor/.../client/rest/RestSchemeOperatingHoursClient.java:72` (`@Value("${gmepay.scheme-hours.cache-ttl-millis:600000}")`), shipped at `payment-executor/src/main/resources/application.properties:67` |
| Storage | `RestSchemeOperatingHoursClient.java:66` — a per-instance `ConcurrentHashMap<String, Cached>`, keyed by scheme, no size cap |
| Hot-path reads | `:97-101` (`weeklySchedule`), reached from `domain/OperationalGate.java:87,114,126-128` → `SchemeOperatingHoursGate.checkNewPayment`. `OperationalGate.java:33-37` is composed into `checkNewAuthorization`, the single call made by **both** `POST /v1/payments/authorize` and the wallet `POST /v1/pay`. `domain/FailoverPaymentRouter.java:257,366` additionally evaluates it **per resolved failover candidate**, so one cross-border payment can hit the cache several times. |
| Miss cost | connect 500 ms / read 500 ms (`RestSchemeOperatingHoursClient.java:73-74`, `application.properties:68-69`); on failure `degraded()` (`:125`) serves last-known-good or empty → UNVERIFIED, which **permits** |
| Memory | not a ceiling — bounded by the ~9-scheme roster |
| Real ceiling | **cold-miss stampede**: N replicas × 9 schemes × one config-registry call every 10 min, and every replica restart is a synchronous miss on the pay path. At 10x with rolling restarts this is a config-registry read spike, not a payment-executor problem. |
| **A second, un-timeouted copy** | `smart-router/.../resolve/RestSchemeOperatingHoursSource.java:56` has the same 10-minute TTL and its own map, but is built via a bare `RestClient.builder().baseUrl(baseUrl).build()` (`:57`) — **no connect or read timeout at all**. Called from `resolve/LocationSchemeResolver.java:233-247,261`. A hung config-registry hangs the resolve path with no upper bound. |

### 4.4 Things that are genuinely UNKNOWN WITHOUT RUNNING

Stated rather than estimated:

1. **The in-flight limit of every inter-service HTTP client.** No connection-pool configuration exists
   anywhere (no `PoolingHttpClientConnectionManager`, no `maxConnPerRoute`, no Apache httpclient5 or
   OkHttp on any classpath); every client is `JdkClientHttpRequestFactory` or
   `SimpleClientHttpRequestFactory`, and the JDK `HttpClient`'s pool is unbounded per destination
   unless `jdk.httpclient.connectionPoolSize` is set — which it is not. So the practical limit is the
   caller's thread count, which is itself unset (see 2). Only ceiling #3's bulkhead is a real number.
2. **Tomcat's actual thread ceiling in a deployment.** `server.tomcat.threads.max` is set in **no**
   `application*` file → Spring default 200; `run-fleet.ps1:337` uses 20 locally. Which of the two a
   real deployment gets, and whether 200 threads fit in the heap of ceiling #9, cannot be derived
   statically.
3. **The heap the Helm chart actually gives a JVM.** No `JAVA_TOOL_OPTIONS` in any values file; the
   only guidance is `requests.memory: 384Mi` / `limits.memory: 640Mi` (`values.yaml:63-68`) with
   **no `limits.cpu` at all**.
4. **PostgreSQL `max_connections` in production.** Configured nowhere — no `postgresql.conf`, no
   `command:` override, no `POSTGRES_INITDB_ARGS`, and `values.yaml:20-33` ships no
   StatefulSet/PVC/datastore at all. Compose gets postgres 16's default (100); a managed RDS /
   Flexible Server gets whatever the instance class dictates.
5. **GC behaviour under sustained load.** `-XX:+UseSerialGC` is used by the *E2E launcher* only
   (`SchemeFleet.java`), not by compose or Helm; the collector, pause distribution and allocation rate
   under real load are exactly what a soak run is for.
6. **Whether the annual `SUM` of ceiling #2 is the binding constraint or merely a contributor.** Its
   cost depends on rows-per-partner-per-year, which only a real volume profile answers.

---

## 5. Verifying it works (no server required, nothing started)

```bash
# 1. Static wiring guard: local-only, acknowledgement-gated, never automatic, no invented SLOs.
python scripts/check_load_harness_wiring.py

# 2. The interlock really refuses. 54 assertions, in the ORDINARY test task (so `gradlew build` and
#    CI re-prove it on every change).
gradlew.bat :e2e-tests:test
gradlew.bat :e2e-tests:test --tests *LoadTargetGuardTest*
gradlew.bat :e2e-tests:test --tests *SloTargetsTest*
gradlew.bat :e2e-tests:test --tests *LoadMetricsTest*

# 3. The harness prints usage rather than running anything.
gradlew.bat :e2e-tests:loadTest
```

Two live demonstrations that send **no** money-path request:

```powershell
# Refuses a non-local target and exits 3, before opening a socket.
.\gradlew.bat :e2e-tests:loadTest --args="--payment-executor-url=https://api.gmepay.com --scrape= --i-know-this-is-not-prod"

# With no fleet running: fails preflight, exits 4, and tells you to start the fleet yourself.
.\gradlew.bat :e2e-tests:loadTest --args="--i-know-this-is-not-prod --scrape= --duration=1s"
```

---

## 6. What an owner must decide before this can pass or fail

`Documentation/SLO_TARGETS.properties` ships with **every value empty**, and the harness reports
`NO_TARGETS_DECLARED` — a third state that is neither a pass nor a failure. That emptiness is pinned
by `SloTargetsTest#shippedPlaceholderDeclaresNothing` and by
`scripts/check_load_harness_wiring.py`, so it cannot quietly rot into invented defaults.

An owner must declare, and put their name in `slo.owner`:

| Key | The question behind it |
|---|---|
| `slo.availability.min-success-rate` | What fraction of attempted payments must complete? |
| `slo.reliability.max-error-rate` | What fraction may fail for a *platform* reason? |
| `slo.latency.p50/p95/p99.max-ms` | What end-to-end latency does a partner get to expect? |
| `slo.throughput.min-tps` | What sustained rate must the platform carry? |

**Answer this first, because two of the four keys depend on it:** *does a declined payment count
against availability?* As measured, a structured decline is **not** a success (the payment did not
complete) but is **not** an error either (the platform behaved correctly). If your availability SLO
should exclude declines, declare `slo.reliability.max-error-rate` and leave
`slo.availability.min-success-rate` undeclared — do not paper over the question by picking a number
that happens to accommodate both.

Behaviour of the file, all deliberate:

- **Empty ⇒ NO_TARGETS_DECLARED**, exit 0, and the summary says in words that this is not a pass.
- **Partially filled ⇒ only what is declared is evaluated**, and the report lists what is still
  undeclared. This is a legitimate intermediate state.
- **Present but blank ⇒ undeclared**, so a half-finished edit cannot silently become `0` and fail
  every run.
- **Declared but unmeasurable by the run ⇒ `undecidable` and the verdict is FAIL.** An undecidable
  target is exactly the situation T3-5 is about; it must not pass quietly.
- **Non-numeric ⇒ a loud error**, naming the key.

Also still owed, and not an engineering decision either: **an SLO derived from a *local* run is not a
production commitment.** See §7.

---

## 7. NOT covered — read this before promising an SLA

This section is deliberately blunt. Everything here is real; none of it is implemented.

1. **The harness has never been run.** It ships unexecuted — the fleet was not started as part of this
   work. There is no baseline, no 10x run, and no published result. §4 is analysis; there are no
   measurements yet.
2. **A local run is not a production forecast.** The fleet, the simulators *and* the harness share one
   Windows host's CPU, disk and page cache, with `-Xmx256m`/`-Xmx320m` JVMs and (under
   `run-fleet.ps1`) Tomcat capped at 20 threads and Hikari at 5. Absolute numbers from such a run are
   good for **relative** comparison (baseline vs 10x, before vs after a fix) and for nothing else.
   There is no always-on environment to run it against — that is register item **T3-1**.
3. **Scheme calls hit simulators, not schemes.** ZeroPay/SendMN/9Pay latency distributions, their rate
   limits, their queueing and their outages are entirely absent. A p99 measured against `sim-scheme`
   says nothing about a p99 against KFTC.
4. **Nothing here measures a per-partner SLA.** The harness drives one partner code. Per-partner
   uptime, per-partner latency and an error budget per partner are what a contract is written against,
   and none of them exists — there is no partner-scoped SLI anywhere in the platform.
5. **No continuous SLI.** This is a point-in-time run someone starts by hand. There is no ongoing
   measurement, no error-budget burn, no dashboard panel, and no partner-facing report.
   `RUNBOOK_MONITORING.md` §1.5 gives the Prometheus rules an SLO *could* be computed from — but
   nothing deploys Prometheus (§6.2 there), so today there is nowhere for a continuous SLI to live.
6. **No business metrics to measure against.** Per `RUNBOOK_MONITORING.md` §6.6 there are no custom
   counters: no `gmepay_authorizations_total`, no approval/decline-rate metric, no per-scheme latency
   timer, no outbox-lag gauge. This harness computes approval and decline rates **client-side**, which
   is fine for a load run and useless for production monitoring.
7. **No scheduler-lag metric.** Ceiling #5 in §4.1 degrades silently: nothing measures how late a
   `@Scheduled` job ran, so the starvation it predicts would be invisible even with Prometheus.
8. **The 10x runs themselves are not scripted.** There is no "baseline then 10x then diff" wrapper and
   no committed baseline `result.json`. Run the harness twice with different `--rate` and diff the two
   JSONs by hand.
9. **Soak is possible but unproven.** `--duration=8h` works by construction; nothing has run long
   enough to surface a leak, and the unbounded maps of §4.1 #9 are precisely what a soak would find.
10. **No backpressure design, only backpressure observation.** The harness reports 429s and shed
    arrivals; nothing in the platform sheds load deliberately, queues fairly, or degrades gracefully.
    There is no admission control on the money path.
11. **§4 is static analysis.** Where it says a ceiling will bind first, that is reasoning from code,
    not a measurement. §4.4 lists what cannot be known without running at all.
12. **`apps/**` is not exercised.** The admin and partner UIs are not driven; this is an API-level
    load test only.

---

## 8. Cross-references

| Item | Where |
|---|---|
| Gap register (T3-5, and the T3-1 "no always-on environment" that blocks a real run) | `Documentation/GAP_REGISTER.md` |
| COO evidence for both halves of the gap | `outputs/agent/audit_coo-ops_2026-07-28.md` §8, §9 |
| Implementation notes / decisions / the ceiling table's provenance | `outputs/agent/fix_t3-load-and-capacity_2026-07-28.md` |
| Metrics the harness scrapes, and their auth | `Documentation/RUNBOOK_MONITORING.md` §1 |
| Alerts a load run will trigger (`DECLINE_SPIKE`, `UNCERTAIN_AGED`, `WEBHOOK_BACKLOG`, `FLOAT_LOW`) | `Documentation/RUNBOOK_MONITORING.md` §2 |
| Declared SLO targets (empty by default) | `Documentation/SLO_TARGETS.properties` |
| Static wiring guard | `scripts/check_load_harness_wiring.py` |
| The harness | `e2e-tests/src/test/java/com/gme/pay/e2e/load/` |
| The fleet it expects | `run-fleet.ps1`, `docker-compose.yml` |
| The two-phase money model the `authorize-confirm` scenario drives | `Documentation/SETTLEMENT_FLOW_SPEC.md` §4, §7.1 |
| The functional E2E harness this reuses conventions from | `e2e-tests/README.md`, `e2e-tests/src/test/java/com/gme/pay/e2e/SchemeFleet.java` |
