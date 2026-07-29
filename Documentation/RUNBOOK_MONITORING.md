# RUNBOOK — Monitoring & Alerting

> Closes (partially) gaps **T3-2** (no production monitoring) and **T3-3** (alerting default-OFF,
> terminating in memory) in `Documentation/GAP_REGISTER.md`. Evidence:
> `outputs/agent/audit_coo-ops_2026-07-28.md` §3 and §4. Implementation notes:
> `outputs/agent/fix_t3-monitoring-alerting_2026-07-28.md`.
>
> **Read §6 before you rely on this.** Several things an operator would reasonably assume exist still
> do not, and they are listed there rather than glossed over.

---

## 0. What changed, in one paragraph

Before this: `/actuator/prometheus` **did not exist on any service** (no Micrometer registry in any of
19 builds) even though a build comment claimed it and api-gateway's security config `permitAll`-ed it;
the Helm OTLP endpoint pointed at a collector nobody deploys; and both production safety-net monitors
(decline spike, stuck/UNCERTAIN transactions) were **off by default**, with alerts landing in a
200-entry in-memory deque that emptied on restart and no paging target configured anywhere.

Now: every service really serves a Prometheus scrape endpoint, gated by the platform internal token on
the services that gate introspection at all; the dead OTLP config is **deleted** (tracing is honestly
an open gap, not a config line); both monitors are armed in every shipped manifest; every alert
payment-executor raises is written to a **durable table before** it is published; and one environment
variable turns on real paging to a pager/Slack/Opsgenie webhook.

---

## 1. Metrics — what exists and what to scrape

### 1.1 The endpoint

| | |
|---|---|
| Path | `GET /actuator/prometheus` on **every** service, on the normal app port (there is no separate management port) |
| Format | Prometheus text exposition (`# TYPE …`) |
| Registry | `io.micrometer:micrometer-registry-prometheus`, added once for all 20 deployables in the root `build.gradle` (`plugins.withId('org.springframework.boot')`) |
| Exposure | `prometheus` is appended to `management.endpoints.web.exposure.include` for every service by `com.gme.pay.platform.MetricsExposureEnvironmentPostProcessor` (in `libs/lib-errors`, registered via `META-INF/spring.factories`) |
| Opt-out | `GMEPAY_METRICS_PROMETHEUS_EXPOSE=false` per instance |

**Do not add `prometheus` to a service's own `management.endpoints.web.exposure.include`.** The
fleet-wide post-processor is the mechanism that cannot drift; per-service lists are what produced T3-2
in the first place (a comment claiming an endpoint that 19 build files never enabled).

### 1.2 What metrics you get

These are Spring Boot / Micrometer built-ins — real, immediately useful, and **not** hand-rolled
business metrics (see §6):

| Family | Use it for |
|---|---|
| `http_server_requests_seconds_{count,sum,max}` (tags `uri`, `method`, `status`, `outcome`) | request rate, error rate and latency per route — the raw material for authorization latency and 5xx alerts |
| `jvm_memory_used_bytes`, `jvm_gc_*`, `process_cpu_usage`, `system_cpu_usage` | saturation, OOM prediction (every service runs `-Xmx320m` in compose) |
| `hikaricp_connections_{active,idle,pending,timeout_total}` | the DB pool exhaustion predicted as first-break in COO#9; `pending > 0` sustained means you are out of connections |
| `spring_data_repository_invocations_seconds` | slow repository calls |
| `resilience4j_circuitbreaker_state`, `…_calls` (payment-executor) | per-scheme breaker open/closed — "why is 9Pay slow?" |
| `kafka_consumer_*` (services with spring-kafka) | consumer lag proxies |

Every series carries `application="<service>"`, defaulted from `spring.application.name` by the same
post-processor, so one Prometheus can hold the whole fleet and every series is attributable.

### 1.3 Authentication — the scrape needs a token

The scrape exposes per-URI request volumes and latency histograms, i.e. per-partner traffic shape. It
is therefore **not anonymous** where the service gates introspection:

| Service | Posture | Credential the scraper must send |
|---|---|---|
| **api-gateway** (only internet-reachable service) | **fail-closed**: 401 to everyone when no secret is set | `X-Gme-Internal: $GMEPAY_INTERNAL_AUTH_SECRET` |
| **payment-executor** | gated whenever a secret is configured (always, in a real deployment) | `X-Gme-Internal: …` |
| **prefunding**, **rate-fx**, **scheme-adapter-zeropay** | gated (their `gmepay.internal-auth.path-patterns` lists it) | `X-Gme-Internal: …` |
| **ops-partner-bff** | default-deny OAuth2 resource server; only `/actuator/health**` is anonymous | a Keycloak **bearer token** |
| **auth-identity**, **transaction-mgmt**, **config-registry** | **anonymous — open follow-up**, see §6.1 | none |
| the other 9 services (`merchant-qr-data`, `smart-router`, `revenue-ledger`, `reporting-compliance`, `settlement-reconciliation`, `qr-service`, `scheme-adapter-{nepal,sendmn,ninepay}`) | **anonymous**, exactly as their pre-existing `/actuator/metrics` already was | none |

`/actuator/health`, `/actuator/health/liveness` and `/actuator/health/readiness` stay anonymous
everywhere — gating metrics must never break a container probe.

### 1.4 Prometheus scrape config

Static targets (docker-compose stack). The token is the same `GMEPAY_INTERNAL_AUTH_SECRET` the fleet
shares; put it in a file Prometheus can read, not in the repo:

```yaml
scrape_configs:
  - job_name: gmepay
    metrics_path: /actuator/prometheus
    scheme: http
    # One shared internal token for the whole fleet (docker-compose.yml: *internal-auth-secret).
    # Prometheus >= 2.26 supports reading a bearer/header value from a file.
    http_headers:
      X-Gme-Internal:
        values: [ "REPLACE_WITH_GMEPAY_INTERNAL_AUTH_SECRET" ]
    static_configs:
      - targets:
          - api-gateway:8080
          - payment-executor:8080
          - transaction-mgmt:8080
          - prefunding:8080
          - rate-fx:8080
          - config-registry:8080
          - qr-service:8080
          - merchant-qr-data:8080
          - smart-router:8080
          - revenue-ledger:8080
          - settlement-reconciliation:8080
          - reporting-compliance:8080
          - notification-webhook:8080
          - auth-identity:8080
          - scheme-adapter-zeropay:8080
          - scheme-adapter-nepal:8080
          - scheme-adapter-sendmn:8080
          - scheme-adapter-ninepay:8080
```

Kubernetes: the chart stamps `prometheus.io/scrape: "true"`, `prometheus.io/path`,
`prometheus.io/port` on every pod (`monitoring.podAnnotations`, `monitoring.scrapePath` in
`deploy/helm/gmepay/values.yaml`), so a `kubernetes_sd_configs` job discovers the fleet. The chart
**does not deploy Prometheus** — see §6.2.

### 1.5 Starter alert rules

Nothing ships these; they are the minimum worth adding to your Prometheus:

```yaml
groups:
  - name: gmepay-money-path
    rules:
      - alert: GmepayServiceDown
        expr: up{job="gmepay"} == 0
        for: 2m
      - alert: GmepayHigh5xx
        expr: |
          sum by (application) (rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
            / sum by (application) (rate(http_server_requests_seconds_count[5m])) > 0.05
        for: 5m
      - alert: GmepayAuthorizeLatencyP99
        expr: |
          histogram_quantile(0.99,
            sum by (le) (rate(http_server_requests_seconds_bucket{application="payment-executor"}[5m]))) > 5
        for: 10m
      - alert: GmepayDbPoolExhausted
        expr: hikaricp_connections_pending > 0
        for: 5m
      - alert: GmepayCircuitBreakerOpen
        expr: resilience4j_circuitbreaker_state{state="open"} == 1
        for: 1m
```

---

## 2. Alerts — what fires and what it means

Two application-level monitors. **Both are now on by default**; before T3-3 both were off, so the money
path had zero active safety nets.

| Alert | Raised by | Fires when | Severity | What it means / first action |
|---|---|---|---|---|
| **`DECLINE_SPIKE`** | `payment-executor` `DeclineSpikeMonitor` | per partner code **and** per scheme id: decline rate over a 60s rolling window > **0.50**, with ≥ **20** in-window authorizations | `WARN`; **`CRITICAL`** at rate ≥ 0.80 | Something is refusing money at scale — scheme outage, expired partner credential, exhausted prefunding float, or a bad rate/limit config. Check the subject: a **partner code** points at that partner's config/float; a **scheme id** points at the scheme. Then `GET /internal/ops/alerts?alertType=DECLINE_SPIKE` for history, and the scheme's circuit-breaker metric. |
| **`UNCERTAIN_AGED`** | `transaction-mgmt` `StuckTransactionAlertSweeper` | a transaction sits in `UNCERTAIN` longer than **900s** (15 min) | `WARN`; `CRITICAL` at 4× (1h) | A scheme call timed out and we do not know whether the counterparty moved money. **Never guess.** Poll the scheme side first (9Pay: lookup by `request_id`), then force-resolve with `POST /v1/transactions/{txnRef}/resolve` (idempotent + audited). |
| **`STUCK_TXN`** | same sweeper | any other swept non-terminal state aged past the threshold | `WARN` / `CRITICAL` | An in-flight row never resolved. Same evidence-first rule. |

Other `ops.alert` producers already existed and are unchanged by this work:
`WEBHOOK_BACKLOG` (notification-webhook), `RECON_BREAK` (settlement-reconciliation),
`FLOAT_LOW` (prefunding).

### 2.1 Tuning (no rebuild required)

| Property / env var | Default | Notes |
|---|---|---|
| `GMEPAY_DECLINE_SPIKE_ENABLED` | `true` | Setting `false` must be a deliberate decision — pinned by `DeclineSpikeMonitorDefaultOnTest`, which reads the shipped `application.properties` |
| `GMEPAY_DECLINE_SPIKE_WINDOW_SECONDS` | `60` | |
| `GMEPAY_DECLINE_SPIKE_MIN_SAMPLES` | `20` | Stops one early decline reading as a 100% rate |
| `GMEPAY_DECLINE_SPIKE_THRESHOLD_RATE` | `0.5` | Majority-decline = a spike, not routine fraud/limit declines |
| `GMEPAY_DECLINE_SPIKE_COOLDOWN_SECONDS` | `300` | Per-subject suppression so one outage does not flood the sink |
| `GMEPAY_TXN_STUCK_ALERT_ENABLED` | `true` **in the manifests** | The service's *code* default is still `false` — see §6.1 |
| `GMEPAY_TXN_STUCK_ALERT_THRESHOLD_SECONDS` | `900` | |
| `GMEPAY_TXN_STUCK_ALERT_CRITICAL_MULTIPLIER` | `4` | |
| `GMEPAY_TXN_STUCK_ALERT_STATUSES` | `UNCERTAIN` | Add `PENDING_DEBIT,SCHEME_SENT` to also catch in-flight rows |

---

## 3. Alert durability — where an alert lives

Three legs, run in this order by `OpsAlertPipeline`, each independently guarded so no leg can break
another or the payment that triggered it:

1. **Persist** → `ops_alerts` (payment-executor, Flyway **V006**). Written **first**, so the evidence
   exists before anything that can fail over a network. Survives a restart of this service, the broker
   and the BFF. Proven by `OpsAlertDurabilityTest`, which commits alerts, tears the Spring context
   down, and re-reads them from a fresh context.
2. **Publish** → the existing `ops.alert` event seam (topic `gmepay.ops.alert`) so the BFF's control
   tower still sees it wherever a broker is wired.
3. **Notify** → the `AlertSink` (§4). The delivery outcome is stamped back onto the same row, so
   *"what fired?"* and *"did anyone find out?"* are one query.

### 3.1 Reading the history

```bash
curl -H "X-Gme-Internal: $GMEPAY_INTERNAL_AUTH_SECRET" \
     'http://payment-executor:8080/internal/ops/alerts?severity=CRITICAL&limit=50'
```

- Newest first; filters `severity` and `alertType` are exact and case-insensitive.
- **Bounded by construction**: `limit` defaults to 50 and is hard-capped at 500, and there is no
  offset parameter — no caller can walk the table.
- **Fail-closed**: `/internal/**` is gated unconditionally on payment-executor. With no secret
  configured, every caller gets 401 (a blank secret can never equal a presented one) rather than the
  endpoint being anonymous.
- Each row carries `notifyStatus` (`DELIVERED` / `FAILED` / `SKIPPED` / `PENDING`), `notifyChannel`
  and `notifyError`.
- Retention: `GMEPAY_OPS_ALERTS_RETENTION_DAYS` (default **90**), pruned every 6h by
  `OpsAlertRetentionSweeper` (`GMEPAY_OPS_ALERTS_PRUNE_ENABLED=false` to keep forever). Bounded by
  **time**, not by count — bounding by count is exactly how the old 200-entry deque lost a busy hour.

---

## 4. Paging — the one thing you must configure

There was **no paging target anywhere**. There now is a pluggable sink with a safe default:

- **default (nothing configured)** → `LogAlertSink`: logs at `WARN`, reports success. The alert path
  works out of the box and stays visible, but **nothing pages a human** — the service logs this loudly
  at startup.
- **configured** → `WebhookAlertSink`: a generic JSON `POST` of the stable `OpsAlertPayload` shape
  (`eventType, alertType, severity, subjectRef, detail, occurredAt`) to one URL.

### 4.1 What to set

```bash
# payment-executor (DECLINE_SPIKE). Slack incoming webhook / PagerDuty Events v2 /
# Opsgenie / MS Teams connector / your own receiver — no vendor code, no credential in the repo
# (the secret is the opaque token inside the URL you supply).
GMEPAY_ALERT_SINK_WEBHOOK_URL=https://hooks.slack.com/services/T000/B000/XXXX
GMEPAY_ALERT_SINK_TIMEOUT_MS=3000     # optional
GMEPAY_ALERT_SINK_MAX_ATTEMPTS=3      # optional

# ops-partner-bff (aggregate paging for ALL ops.alert producers — pre-existing PagingPort).
# Point it at the same URL.
GMEPAY_OPS_PAGING_WEBHOOK_URL=https://hooks.slack.com/services/T000/B000/XXXX
```

For the compose stack, put those in a `.env` file next to `docker-compose.yml` (nothing secret is
committed). For Helm, uncomment the documented line in the service's `env:` block in
`deploy/helm/gmepay/values.yaml`.

### 4.2 Delivery behaviour

- Explicit connect + read timeout; bounded retries on 5xx / transport error.
- **4xx is not retried** — a wrong URL or rejected body is a permanent configuration error, recorded as
  `FAILED` with `http 4xx`.
- **Never throws.** A dead webhook degrades to a `FAILED` row on the alert; it cannot break the monitor
  or the payment. Covered by `WebhookAlertSinkTest` and `OpsAlertPipelineTest` (including a sink that
  throws and a sink that returns `null`).
- A **blank** URL counts as *not configured*. `@ConditionalOnProperty` would have matched an empty
  value and activated a sink pointed at nothing, silently failing every page — so the choice is made in
  code (`AlertSinkConfig`), not by a condition. `${VAR:-}` in a manifest is therefore safe.

### 4.3 What is still missing operationally

There is **no on-call rota, no escalation policy and no named humans** anywhere in this repo. The
webhook is the transport; who answers it at 3am is an organisational decision that has not been made.
`gmepay.ops.paging.escalation.enabled` (BFF) stays **off** on purpose: it is documented
single-replica-only with no ShedLock, so arming it under more than one replica would double-page.

---

## 5. Verifying it works (no server required)

```bash
# 1. Static wiring guard: registry present, endpoint exposed and gated, monitors armed in BOTH
#    docker-compose.yml and Helm, ops_alerts migration present, no dead OTEL config anywhere.
python scripts/check_monitoring_wiring.py

# 2. The endpoint really answers, and really requires the token.
gradlew.bat :services:payment-executor:test --tests *PrometheusEndpointTest*
gradlew.bat :services:api-gateway:test      --tests *PrometheusScrapeGateTest*

# 3. Alerting: default-on pinned against the shipped config, restart survival, sink behaviour.
gradlew.bat :services:payment-executor:test --tests *DeclineSpikeMonitorDefaultOnTest*
gradlew.bat :services:payment-executor:test --tests *OpsAlertDurabilityTest*
gradlew.bat :services:payment-executor:test --tests *OpsAlertPipelineTest*
gradlew.bat :services:payment-executor:test --tests *WebhookAlertSinkTest*
gradlew.bat :libs:lib-errors:test           --tests *MetricsExposure*
```

Live smoke check once a stack is up:

```bash
curl -s -o /dev/null -w '%{http_code}\n' localhost:8088/actuator/prometheus                      # 401 expected
curl -s -H "X-Gme-Internal: $GMEPAY_INTERNAL_AUTH_SECRET" localhost:8088/actuator/prometheus \
  | grep -c '^# TYPE'                                                                            # > 0
```

---

## 6. NOT covered — read this before promising an SLA

This section is deliberately blunt. Everything here is a real gap; none of it is implemented.

### 6.1 Residual items from this work

1. **Three services' scrape endpoints are anonymous in-cluster** — `auth-identity`,
   `transaction-mgmt`, `config-registry` gate `/actuator/metrics/**` but not `/actuator/prometheus`,
   because their source trees were owned by a concurrent workstream. No worse than their already
   anonymous `/actuator/metrics`, but it is debt. Fix = add `/actuator/prometheus` to each one's
   internal-auth pattern list (one line each). Tracked in `scripts/check_monitoring_wiring.py`
   (`SCRAPE_GATE_FOLLOWUPS`, which warns rather than fails).
2. **Nine services never gated `/actuator/metrics` at all**, so their `/actuator/prometheus` is
   anonymous too (`merchant-qr-data`, `smart-router`, `revenue-ledger`, `reporting-compliance`,
   `settlement-reconciliation`, `qr-service`, `scheme-adapter-{nepal,sendmn,ninepay}`). None is routed
   through the ingress. Fix = enable the internal-auth gate on their introspection paths, which also
   makes `GMEPAY_INTERNAL_AUTH_SECRET` mandatory for those services.
3. **`StuckTransactionAlertSweeper`'s code default is still `false`.** It is armed in
   `docker-compose.yml` and `deploy/helm/gmepay/values.yaml`, so every shipped deployment has it on and
   the guard script enforces that — but a hand-rolled deployment that copies neither manifest gets it
   off. Fix = `matchIfMissing`-equivalent default in `transaction-mgmt`.
4. **ops-partner-bff's `OpsAlertStore` is still an in-memory `ArrayDeque`** (capacity
   `gmepay.ops.alerts.capacity`, default 200). payment-executor now persists what *it* raises, but the
   **aggregate** ops view — including `WEBHOOK_BACKLOG`, `RECON_BREAK`, `FLOAT_LOW`, `UNCERTAIN_AGED`
   and acknowledgement state — is still lost on a BFF restart. Fix = a durable store in the BFF (or
   route those producers' alerts through a persisted table of their own).
5. **payment-executor publishes `ops.alert` to a log, not a broker.** It has no `lib-events-kafka` on
   its classpath, so leg 2 of the pipeline is a log line and `DECLINE_SPIKE` never reaches the BFF
   control tower. That is why payment-executor has its own sink. Adding a Kafka publisher here would
   also start emitting `payment.approved` from a second producer — a money-path change, register item
   **T2-5**, not a monitoring change.
6. **Compose does not set `SPRING_KAFKA_BOOTSTRAP_SERVERS` on `ops-partner-bff`**, so
   `OpsAlertKafkaConsumerConfig` (`@ConditionalOnProperty("spring.kafka.bootstrap-servers")`) is
   inactive there and the BFF consumes **no** `ops.alert` in the compose stack. Helm is fine (the
   shared ABI ConfigMap supplies it to every pod). Not fixed here because it changes BFF runtime
   behaviour in a stack owned by another workstream.

### 6.2 No monitoring stack is deployed

There is **no Prometheus, Grafana, Alertmanager or Loki** in `docker-compose.yml` or in the Helm chart.
This work makes the platform *scrapeable* and gives Kubernetes the discovery annotations; standing up
the collector, the dashboards and the alert routing is still on the operator. Until that exists,
"is the platform up?" is still answered by hand.

### 6.3 No log aggregation

Logs are per-container stdout. There is no `logback-spring.xml`, no JSON encoder and no shipper
anywhere in the repo, so **logs are not searchable by `txnRef`** across services. The one thing that
does work: every log line carries the correlation id (`%X{correlationId}` via
`PlatformDefaultsEnvironmentPostProcessor`), and `X-Correlation-Id` propagates across services — so
once you have shipping, correlation works. Until then, log-mining means `docker logs` per container.

### 6.4 No tracing

No OpenTelemetry SDK, no OTLP exporter, no `micrometer-tracing` on any classpath, and no collector.
The `OTEL_EXPORTER_OTLP_ENDPOINT` that used to sit in all four Helm values files and in
`api-gateway/application.yml` was **dead config** and has been **removed**, along with the
`Observability` row in `docs/DEPLOYMENT.md` and the `Telemetry` row in ADR-015 that advertised it.
Correlation ids are the current substitute for trace ids. Wiring real tracing means adding a tracer to
20 services plus a collector deployment — real work with a real operational cost, deliberately not
faked with a config line.

### 6.5 No SLA/SLO measurement — register item **T3-5**

There is no written SLO, no error budget, no per-partner uptime or latency tracking, and nothing
partner-facing. §1.5 gives the raw `http_server_requests_seconds_bucket` histograms an SLO *could* be
computed from, but nobody has defined the targets, and no load test has ever run — so the numbers in
any partner contract would be guesses. **Do not sign an availability or latency commitment on the
strength of this runbook.** See `Documentation/GAP_REGISTER.md` **T3-5** (no SLA measurement, no load
test, no capacity plan).

### 6.6 No business metrics

Everything in §1.2 is a framework built-in. There are **no custom counters** for the money path —
no `gmepay_authorizations_total`, no approval/decline rate metric, no per-scheme latency timer, no
outbox lag gauge. The COO's "one dashboard covering the money path (auth rate, decline rate, scheme
latency, outbox lag)" is therefore only partly satisfiable: auth/decline rate can be approximated from
`http_server_requests_seconds_count` by URI and status, scheme latency from the resilience4j metrics,
but outbox lag has no metric at all. `DECLINE_SPIKE` covers the decline case as an *alert* rather than
as a dashboard series.

### 6.7 Batch/scheduler alerting is still log-and-swallow — register item **T3-4**

Every ZeroPay batch cron and the settlement-generation scheduler still end in
`catch (Exception e) { log.error(...) }` with no `ops.alert` emission, no rerun tooling, no missed-run
detection and no holiday awareness. A failed 02:00 ZP0011 run is still a silent settlement discrepancy
your counterparty discovers first. Nothing in this work changes that. See **T3-4**.

---

## 7. Cross-references

| Item | Where |
|---|---|
| Gap register (T3-2, T3-3, and the still-open T3-4 / T3-5) | `Documentation/GAP_REGISTER.md` |
| COO evidence for both gaps | `outputs/agent/audit_coo-ops_2026-07-28.md` §3, §4 |
| Implementation notes / decisions | `outputs/agent/fix_t3-monitoring-alerting_2026-07-28.md` |
| Backup / restore / DR (the other half of Tier 3) | `Documentation/RUNBOOK_BACKUP_DR.md` |
| Static wiring guard | `scripts/check_monitoring_wiring.py` |
| Cloud-agnostic ABI (no vendor SDKs, no embedded credentials) | `docs/adr/ADR-015-cloud-agnostic-deployment.md` |
