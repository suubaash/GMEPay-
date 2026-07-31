> 작업: T3-2/T3-3 monitoring + alerting / 출처: agent

# T3-2 / T3-3 — Metrics endpoint + alerting that survives a restart

Closes (partially) gaps **T3-2** and **T3-3** in `Documentation/GAP_REGISTER.md`, COO audit findings
**#3** and **#4** (`outputs/agent/audit_coo-ops_2026-07-28.md` §3, §4). Operator-facing companion doc:
`Documentation/RUNBOOK_MONITORING.md`. Documentation style mirrors
`outputs/agent/fix_t3-backup-dr_2026-07-28.md` (the backup half of Tier 3).

**Constraint honoured:** `services/ops-partner-bff`, `apps/partner-portal-ui`, `services/auth-identity`,
`services/notification-webhook`, `services/transaction-mgmt` and `services/config-registry` were owned
by a concurrent workstream and were **not touched**. That shaped several decisions below and left four
follow-ups (§7). No server, container or scraper was started.

---

## 1. What the audit found, and what was actually true

Verified against the code before changing anything:

| Claim | Verified | Detail |
|---|---|---|
| `/actuator/prometheus` doesn't exist | **Yes, worse than stated** | No Micrometer registry in any of 20 builds *and* no service named `prometheus` in its `management.endpoints.web.exposure.include` — Boot's exposure filter is an allow-list, so two independent things were missing. Meanwhile `api-gateway/build.gradle:21` claimed the endpoint in a comment, `api-gateway/application.yml:34` claimed it had been "dropped", and `SecurityConfig:126` **`permitAll`-ed it on the one internet-reachable service**. Three files disagreeing with each other and all with reality. |
| OTEL endpoint in Helm is dead config | **Yes, in 4 files not 1** | `values.yaml` + all three overlays, plus an `otel:` block in `api-gateway/application.yml`, plus a `docs/DEPLOYMENT.md` "Observability … OTLP" row and an ADR-015 "Telemetry" ABI row advertising it. No OTel SDK, no OTLP exporter, no `micrometer-tracing` anywhere; no collector in compose or the chart. |
| `DeclineSpikeMonitor` default-OFF | Yes | `@ConditionalOnProperty(havingValue = "true")` with no `matchIfMissing`, **and** `application.properties` shipped `gmepay.decline-spike.enabled=false` — belt and braces against it ever running. |
| `StuckTransactionAlertSweeper` default-OFF | Yes | `gmepay.txn.stuck-alert.enabled=false`. Lives in `transaction-mgmt` — **not** in `payment-executor` as the task brief assumed. |
| `OpsAlertStore` is a volatile deque | Yes | `services/ops-partner-bff/.../alert/OpsAlertStore.java` — `ArrayDeque`, capacity 200, no DataSource. |
| No paging target configured | Yes, **but the sink already existed** | `PagingPort` + `LogPagingAdapter` (default) + `WebhookPagingAdapter` (URL-only, vendor-agnostic, retry/timeout, never throws) were already built in the BFF. Nothing was configured to use them. |

**Two things the audit did not say, found while working:**

1. **payment-executor publishes `ops.alert` to a log, not a broker.** It has no `lib-events-kafka` on
   its classpath, so its `EventPublisher` is `LogEventPublisher`. Even with the monitor ON and a broker
   running, a `DECLINE_SPIKE` never reached the BFF's consumer — so the *entire* documented chain
   (monitor → Kafka → `OpsAlertStore` → `PagingPort`) was broken at the very first hop, not just at the
   ends.
2. **payment-executor has no datasource in the Helm chart.** `values.yaml` set no
   `SPRING_DATASOURCE_URL` and listed no DB credentials, so a Helm-deployed payment-executor fell
   through to the in-code H2 in-memory default and **silently lost `execution_attempts`,
   `idempotency_keys` and `payment_authorizations` on every restart**. `docker-compose.yml` has always
   set it; the chart never did.

---

## 2. T3-2 — the metrics endpoint, made real in two shared places

Deliberately **not** 19 near-identical edits. Two mechanisms, one file each:

| Concern | Where | Why there |
|---|---|---|
| Registry on the classpath | `build.gradle` — `subprojects { plugins.withId('org.springframework.boot') { … micrometer-registry-prometheus } }` | Hits exactly the 20 deployables and skips the `java-library` libs and `:e2e-tests`. Version comes from the Boot BOM each service already applies. Also covers the six concurrently-owned services **without touching their directories**. |
| Endpoint exposed | `libs/lib-errors/.../platform/MetricsExposureEnvironmentPostProcessor.java` + `META-INF/spring.factories` | Every service ships its own `include` allow-list, so a lowest-precedence default would lose. This reads whatever the service resolved and re-contributes it **plus** `prometheus` (`addFirst`), ordered `LOWEST_PRECEDENCE` so it runs after `ConfigDataEnvironmentPostProcessor` and has something to merge with. Strictly additive; honours an explicit `exclude`; opt-out `gmepay.metrics.prometheus.expose=false`. lib-errors already hosts `PlatformDefaultsEnvironmentPostProcessor`, so the pattern and the registration file existed. |

It also **defaults `management.metrics.tags.application` from `spring.application.name`** when a
service sets no tag — otherwise one Prometheus holding 20 services gets 20 indistinguishable JVM metric
sets. Only defaulted, never overridden.

### 2.1 Authentication — anonymous surface went DOWN

The brief required not opening a new unauthenticated surface. Net effect is a **reduction**:

| Service | Before | After |
|---|---|---|
| **api-gateway** (only internet-reachable service) | `permitAll("/actuator/prometheus")` | new `@Order(-1)` chain scoped to that one path, requiring `X-Gme-Internal` with a `MessageDigest.isEqual` comparison; **fail-closed** — a blank secret matches nothing, so 401 for everyone. Removed from the `@Order(1)` chain's `permitAll`. |
| **payment-executor** | `/actuator/metrics/**` gated | `+ /actuator/prometheus` in `INTROSPECTION_PATTERNS`; also `+ /internal/**` gated *unconditionally* for the new alert-history API |
| **prefunding**, **rate-fx**, **scheme-adapter-zeropay** | `/actuator/metrics/**` in `path-patterns` | `+ /actuator/prometheus` |
| health / liveness / readiness | anonymous | unchanged — gating metrics must never break a probe |

The 9 services that never gated `/actuator/metrics` (`merchant-qr-data`, `smart-router`,
`revenue-ledger`, `reporting-compliance`, `settlement-reconciliation`, `qr-service`, the three non-ZeroPay
scheme adapters) now expose `/actuator/prometheus` anonymously **in-cluster** as well. Marginal
disclosure is zero — `/actuator/metrics` already served the same numbers as JSON on those exact services,
and none is routed through the ingress — but it is stated plainly in the runbook §6.1 rather than
glossed over, with the one-line fix recipe. Gating them properly means turning their internal-auth
filter on, which makes `GMEPAY_INTERNAL_AUTH_SECRET` mandatory for their boot: a deployment change, not
a monitoring change.

### 2.2 The dead OTEL config: deleted, not "wired"

Removed from `values.yaml` + `values-{aws,azure,onprem}.yaml` and `api-gateway/application.yml`, each
replaced by a comment saying what was there, why it never worked, and where the honest gap now lives.
The two documentation rows that advertised it are corrected as well:

- `docs/DEPLOYMENT.md` Observability row → "scrape `/actuator/prometheus`; none — the endpoint IS the contract".
- `docs/adr/ADR-015` Telemetry ABI row → `*(none)*` + why (metrics are **pulled**, so no injected endpoint is needed at all).

Wiring it for real means a tracer in 20 services plus a collector deployment — real work with a real
operational cost, explicitly out of scope, and faking it with a config line is what created the gap.

Helm now stamps `prometheus.io/{scrape,path,port}` on every pod (`monitoring.podAnnotations` /
`monitoring.scrapePath`), so a `kubernetes_sd` Prometheus discovers the fleet. The chart still deploys
**no** monitoring stack — said so in values, the runbook and the register.

---

## 3. T3-3 — the alert chain: armed, durable, and it can page

### 3.1 Armed

- `DeclineSpikeMonitor` → `matchIfMissing = true`, and `application.properties` flipped to
  `enabled=true` **in place** (the pre-existing block, not a duplicate). Thresholds reviewed: 60s
  window, ≥20 in-window samples, >0.50 rate → `WARN`, ≥0.80 → `CRITICAL`, 300s per-subject cooldown.
  All four are `@Value` with in-code defaults *and* restated in config, so they are tunable and
  auditable without a rebuild.
- `StuckTransactionAlertSweeper` lives in `transaction-mgmt` (**forbidden**). Armed instead via
  `GMEPAY_TXN_STUCK_ALERT_ENABLED=true` in **both** `docker-compose.yml` and
  `deploy/helm/gmepay/values.yaml`, with thresholds pinned alongside — so every *shipped* deployment has
  it on. The code default stays `false`; that one-line flip is follow-up §7.1 and the guard script
  enforces the manifests in the meantime.

### 3.2 Durable — the actual fix for "terminates in memory"

`OpsAlertStore` is in the BFF and could not be touched, so durability was added **at the emitter**,
which is also where the chain was broken first (§1, finding 1):

- **Flyway `V006__create_ops_alerts.sql`** (verified next free version; V001–V005 flat, no vendor
  subdirectories to mirror). Portable types only, consistent with V001–V005.
- `OpsAlertEntity` / `OpsAlertRepository` / `OpsAlertArchive` — the same shape as the existing
  `RevenuePostingFailureEntity` / `…Repository` / `RevenuePostingFailureStore` trio (V005), the
  service's established "durable record of something that used to be only a log line" pattern,
  including its **never-throws** write contract.
- **`OpsAlertPipeline`** — persist → publish → notify, each leg independently guarded. Persisting is
  **first**, so evidence exists before anything that can fail over a network, and the notification
  outcome is stamped back onto the same row (`notify_status` / `notify_channel` / `notify_error`): one
  row answers both *"what fired?"* and *"did anyone find out?"*.
- **Bounded reads**: `GET /internal/ops/alerts` — newest-first, severity/alertType filters, `limit`
  defaulting to 50 and hard-capped at 500, **no offset parameter** so nobody can walk the table.
- **Time-bounded retention** (90d, 6h pruner) rather than count-bounded, on purpose: bounding by count
  is precisely how the old 200-entry deque discarded a busy hour.

### 3.3 A real notification sink

No vendor integration invented, no credential embedded. `AlertSink` port + `LogAlertSink` (safe
default) + `WebhookAlertSink` (needs only `GMEPAY_ALERT_SINK_WEBHOOK_URL`) — deliberately the same
shape as the BFF's existing `PagingPort` / `LogPagingAdapter` / `WebhookPagingAdapter` (read, not
modified) so the platform has one paging idiom. Timeout + bounded retry on 5xx/transport, 4xx treated as
a permanent config error and not retried, never throws.

**One deliberate divergence from the BFF's shape.** The BFF selects its adapter with
`@ConditionalOnProperty("…webhook-url")`, which **matches a present-but-empty value** — so
`GMEPAY_ALERT_SINK_WEBHOOK_URL=` (or `${VAR:-}` in a manifest, the natural way to write "unset") would
activate a webhook sink pointed at nothing and fail every page silently. That is the same shape of
accident as the default-OFF monitors this gap is about, so the choice is made inside one `@Bean` in
`AlertSinkConfig`, treating blank as *not configured*, and logging loudly at startup which sink won.

---

## 4. Files changed

### Created

| Path | Purpose |
|---|---|
| `Documentation/RUNBOOK_MONITORING.md` | Operator contract: what metrics exist, what to scrape + how to authenticate (with a `scrape_configs` snippet and starter alert rules), which alerts fire and what each means, exactly what to set for paging, and a long honest "NOT covered" section |
| `scripts/check_monitoring_wiring.py` | Static guard, 36/36 passing. Derives requirements from code/shipped config; asserts registry + post-processor registration, no dead OTEL anywhere, scrape gated where metrics is gated, api-gateway not `permitAll`-ing it, monitors armed in **both** manifests, `ops_alerts` migration present, Flyway versions unique, no blank sink URL shipped |
| `libs/lib-errors/.../platform/MetricsExposureEnvironmentPostProcessor.java` (+ test) | Fleet-wide endpoint exposure + `application` tag default |
| `services/payment-executor/.../alert/{AlertSink,AlertDelivery,LogAlertSink,WebhookAlertSink,AlertSinkConfig,OpsAlertPipeline}.java` | The pluggable sink and the three-leg fan-out |
| `services/payment-executor/.../persistence/{OpsAlertEntity,OpsAlertRepository,OpsAlertArchive}.java` | Durable archive + bounded query + retention |
| `services/payment-executor/.../sweeper/OpsAlertRetentionSweeper.java` | Time-bounded pruner |
| `services/payment-executor/.../web/OpsAlertQueryController.java` | `GET /internal/ops/alerts` |
| `services/payment-executor/.../db/migration/V006__create_ops_alerts.sql` | The table |

### Modified

`build.gradle` (registry, one block) · `libs/lib-errors/.../META-INF/spring.factories` ·
`services/payment-executor/{application.properties, DeclineSpikeMonitor, SandboxSurfaceInternalAuthConfig}` ·
`services/api-gateway/{SecurityConfig.java, application.yml}` ·
`services/{prefunding,rate-fx,scheme-adapter-zeropay}/application.properties` (scrape gating) ·
`docker-compose.yml` (stuck sweeper armed + sink documented) ·
`deploy/helm/gmepay/{values.yaml, values-aws.yaml, values-azure.yaml, values-onprem.yaml, templates/_deployment.tpl}` ·
`docs/DEPLOYMENT.md` · `docs/adr/ADR-015-cloud-agnostic-deployment.md` · `Documentation/GAP_REGISTER.md`.

---

## 5. Tests

| Test | Proves |
|---|---|
| `PrometheusEndpointTest` (payment-executor, real server, 2 postures) | the endpoint **exists** and serves real exposition output (`jvm_memory_used_bytes`, `# TYPE`), carries `application="payment-executor"`, requires the token when a secret is configured, and that `/internal/ops/alerts` is fail-closed **even with no secret** |
| `PrometheusScrapeGateTest` (api-gateway, real reactive server) | the edge serves the scrape **only** with the token; wrong/absent token → 401; blank secret → 401 for everyone; probes stay anonymous |
| `DeclineSpikeMonitorDefaultOnTest` | `matchIfMissing = true` on the annotation **and** the shipped `application.properties` enables it and is not env-defeatable — mirroring how auth-identity pinned its gate. Also asserts thresholds are present and in sane ranges, and that **no blank sink URL ships** |
| `OpsAlertDurabilityTest` | alerts **survive a simulated restart**: writes + `@Commit`, `@DirtiesContext` destroys the context (EMF, pool, every bean that could hold state), then a fresh context and a fresh `OpsAlertArchive` still read them back. Plus filter/cap behaviour and retention pruning |
| `OpsAlertPipelineTest` | the sink **is** invoked on alert; a sink that reports failure, **throws**, or returns `null` does not break the monitor and is recorded `FAILED`; a throwing publisher still leaves the alert persisted and notified; a failed DB write still publishes and notifies |
| `WebhookAlertSinkTest` | right shape POSTed to the configured URL; 5xx retried; exhausted retries → `FAILED` without throwing; 4xx **not** retried; log fallback reports delivered |
| `MetricsExposureEnvironmentPostProcessorTest` | additive merge (never drops `health`), precedence, wildcard/exclude respect, escape hatch, idempotence, `spring.factories` registration, application-tag defaulting without clobbering |

**A real bug the tests caught.** `PrometheusEndpointTest` initially failed with 404 despite correct
wiring. The condition-evaluation report showed a single cause: Spring Boot's test support injects
`management.defaults.metrics.export.enabled=false`, so `PrometheusMetricsExportAutoConfiguration` backs
off **inside `@SpringBootTest` only**. Fixed by `@AutoConfigureObservability(tracing = false)` —
restoring the production wiring, which is the thing under test — not by weakening the assertion. The
diagnosis is recorded in the test's javadoc so the next person does not re-derive it.

### Verification run

```
python scripts/check_monitoring_wiring.py                → 36/36 (+1 documented WARN)
python scripts/check_internal_auth_wiring.py             → 67/67
node docker/keycloak/check-topology.mjs                  → 101/101
PyYAML parse: docker-compose.yml (44 services) + all 4 Helm values → OK
gradlew :services:payment-executor:test :libs:lib-errors:test :services:api-gateway:test → BUILD SUCCESSFUL
gradlew :services:{prefunding,rate-fx,scheme-adapter-zeropay}:test                       → BUILD SUCCESSFUL
gradlew testClasses  (repo-wide)                                                          → BUILD SUCCESSFUL
```

One transient failure was observed and diagnosed: `PrometheusScrapeGateTest` timed out on
`WebTestClient`'s 5s default when two Gradle builds ran concurrently. Not a product issue, but the first
scrape materialising every meter is genuinely slow on a loaded machine, so the test's response timeout
is now 30s.

---

## 6. What now exists vs. what does not

**Exists:** a real, tagged, gated Prometheus endpoint on all 20 deployables, wired in two shared places
that a new service cannot forget; the built-in metric families needed for rate/error/latency, DB-pool
saturation and per-scheme breaker state; Kubernetes scrape discovery; both money-path monitors armed in
every shipped manifest with regression-pinned defaults; a durable, bounded, token-gated ops-alert
history that survives restarts; and a one-variable path from a decline spike to a pager.

**Does not exist:** any deployed Prometheus/Grafana/Alertmanager; **any log aggregation** (logs are not
`txnRef`-searchable across services — correlation ids are in every line, but nothing ships them); **any
tracing**; **any custom business metrics** (no `gmepay_authorizations_total`, no outbox-lag gauge — the
"money-path dashboard" is only partly derivable); **any SLO, error budget or load test** (T3-5); batch
scheduler failures still log-and-swallow (T3-4); and **no on-call rota, escalation policy or named
humans** — the webhook is transport, not process.

---

## 7. Follow-ups (all blocked on concurrently-owned code, all small)

1. `services/transaction-mgmt` — flip `gmepay.txn.stuck-alert.enabled` to default `true` (armed in both
   manifests today, so only a hand-rolled deployment is exposed).
2. `services/ops-partner-bff` — give `OpsAlertStore` a durable store. This is the remaining half of
   T3-3: the *aggregate* view (`WEBHOOK_BACKLOG`, `RECON_BREAK`, `FLOAT_LOW`, `UNCERTAIN_AGED` +
   acknowledgement state) is still lost on restart.
3. `docker-compose.yml` / ops-partner-bff — set `SPRING_KAFKA_BOOTSTRAP_SERVERS` so
   `OpsAlertKafkaConsumerConfig` activates; without it the BFF consumes **no** `ops.alert` in the
   compose stack (Helm is fine — the shared ABI ConfigMap supplies it).
4. `auth-identity`, `transaction-mgmt`, `config-registry` — add `/actuator/prometheus` next to
   `/actuator/metrics/**` in each one's internal-auth pattern list (one line each). Tracked as
   `SCRAPE_GATE_FOLLOWUPS` in the guard script, which **warns** rather than fails so the debt is
   visible but does not block CI.

Also worth doing, not blocked: `kyb-adapter` is the one Spring Boot module without
`spring-boot-starter-actuator`, so it has no endpoint at all (it also ships no Dockerfile and is not a
deployable — COO#13).
