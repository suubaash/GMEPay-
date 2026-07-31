> 작업: COO ops audit / 출처: agent

# GMEPay+ COO Operations Audit — 2026-07-28

Question asked: can an operations team run this product for paying customers today?
Short answer: no — the money path is well built, but the platform has no always-on home, no backups, no production monitoring, and its alerting chain is default-off and in-memory. Ranked gaps below (14, most severe first).

---

## 1. No always-on production environment — the platform lives on one Windows laptop [BLOCKER-for-operations]

**Evidence**
- `D:\GMEPay+\code\docker-compose.yml` header: "GMEPay+ — local development stack"; all 36+ containers on one Docker bridge network inside the WSL2 distro `gmepay-docker` (memory: WSL idle-shutdown kills the containers).
- Deploy plan is a Cloudflare Tunnel fronting that same laptop (docs + memory `gmepay-railway-deploy`); no `cloudflared` config exists anywhere in the repo (grep for `cloudflared` across `*.md/*.ps1/*.yml/*.json` = 0 hits), so even the tunnel is not codified.
- `D:\GMEPay+\code\docs\DEPLOYMENT.md` describes a Helm chart (`deploy/helm/gmepay`, 18 deployables) but there is no evidence it has ever been applied to a real cluster; secrets are placeholders; kyb-adapter "ships no Dockerfile yet and is therefore not a deployable" (DEPLOYMENT.md line ~24).
- `run-fleet.ps1` is explicitly a dev launcher (H2/in-memory fallbacks, "NO Docker infra is required for a tracer demo").

**Impact**: single-machine SPOF for a payments product — a Windows update, disk failure, or WSL idle shutdown takes down every partner. No staging/prod separation.

**Done =** a named always-on environment (cloud VM or k8s per DEPLOYMENT.md) running the `core` profile 24/7, survives host reboot (restart policies / systemd / k8s), reachable without a developer laptop, with a documented promote-to-prod procedure and a staging twin.

---

## 2. Zero backup / restore / disaster recovery for 15 PostgreSQL databases + Mongo + MinIO + Kafka [BLOCKER-for-operations]

**Evidence**
- `docker-compose.yml` declares 15 postgres instances (pg-config, pg-txn, pg-executor, pg-qr, pg-ratefx, pg-reporting, pg-prefunding, pg-ledger, pg-settlement, pg-notify, pg-authid, pg-scheme, pg-sendmn, pg-ninepay, pg-keycloak) plus `mongo-data`, `minio-data` — all as local named Docker volumes.
- Repo-wide search for `pg_dump` / backup / restore scripts: nothing outside test docs (`docs/TEST_CASES.md`) and node_modules. No WAL archiving, no snapshot schedule, no restore drill, no DR/RPO/RTO doc.

**Impact**: losing one disk loses the ledger (revenue-ledger), settlement state, prefunding balances, and partner credentials simultaneously. For a payments company this is existential, and regulators will ask.

**Done =** nightly automated dumps (or WAL-G/pgBackRest) for every DB shipped off-host, Mongo/MinIO included; one performed and documented restore drill; a one-page DR doc stating RPO/RTO and the rebuild order of the 15 DBs.

---

## 3. No production monitoring stack — metrics endpoint doesn't even exist despite the comment claiming it [BLOCKER-for-operations]

**Evidence**
- `services/api-gateway/build.gradle` line 21: comment "Actuator for /actuator/health + /actuator/prometheus" — but **no** `micrometer-registry-prometheus` dependency in any of the 19 `build.gradle` files (repo-wide grep: only that comment matches). `/actuator/prometheus` therefore does not exist on any service.
- No Prometheus/Grafana/Alertmanager/Loki container in `docker-compose.yml` or in `deploy/helm/gmepay/` templates. `values.yaml` line 62 sets `OTEL_EXPORTER_OTLP_ENDPOINT: http://otel-collector:4317` but no service has an OTel/micrometer-tracing dependency and no collector is deployed — dead config.
- No `logback*.xml` / JSON log encoder anywhere; logs are per-container stdout with no aggregation.
- `.smoke/trace-console.js` is explicitly a dev transparency tap (memory: 8-edge partial tap, `gmepay.trace.enabled=true`), not monitoring.

**Impact**: in production nobody can answer "is the platform up?", "what's the approval rate right now?", or "why is 9Pay slow?" without SSH + docker logs on the one machine.

**Done =** micrometer-registry-prometheus on all services, a Prometheus+Grafana+Alertmanager (or hosted equivalent) profile in compose/helm, log shipping (Loki/CloudWatch) with txnRef-searchable logs, and one dashboard covering the money path (auth rate, decline rate, scheme latency, outbox lag).

---

## 4. Alerting/on-call chain is default-OFF end-to-end and terminates in a 200-entry in-memory deque [BLOCKER-for-operations]

**Evidence**
- `services/payment-executor/.../alert/DeclineSpikeMonitor.java` line 41: `@ConditionalOnProperty(name = "gmepay.decline-spike.enabled", havingValue = "true")` — default off; with no broker it degrades to a log line.
- `services/transaction-mgmt/.../service/StuckTransactionAlertSweeper.java`: `gmepay.txn.stuck-alert.enabled:false` — default off.
- Consumer side: `services/ops-partner-bff/.../alert/OpsAlertStore.java` lines 32-44 — a plain `ArrayDeque` capped at 200 (`gmepay.ops.alerts.capacity`), no DataSource: **all alerts are lost on BFF restart** and invisible to anyone not watching the admin UI.
- Paging: `WebhookPagingAdapter.java` activates only when `gmepay.ops.paging.webhook-url` is set (no default target configured anywhere); `OpsPagingEscalationScheduler.java` is default off AND documented "single-replica-only — no DataSource/ShedLock".
- No on-call rota, escalation policy, or paging-target config exists in any doc or env file.

**Impact**: a 100% decline spike at 3am pages nobody. The plumbing exists but every valve is closed by default and nothing persists.

**Done =** prod profile enables decline-spike + stuck-txn sweepers, sets a real `gmepay.ops.paging.webhook-url` (Slack/PagerDuty), persists ops alerts (give the BFF a store or route via notification-webhook's DB), and a one-page on-call doc naming humans and escalation timing.

---

## 5. Dev secrets and default credentials are the fleet's actual security posture [BLOCKER-for-operations]

**Evidence**
- `docker-compose.yml`: every postgres is `gmepay/gmepay` (keycloak `keycloak/keycloak`); lines 359/459/466/714/826/853/856 default internal-auth, RBAC-stamp and webhook-signing secrets to `dev-internal-svc-secret-not-for-prod` / `dev-rbac-edge-secret-not-for-prod` / `dev-webhook-secret-not-for-prod`.
- `docs/DEPLOYMENT.md`: Helm `Secret` carries "placeholders in the overlays — replace before any real traffic"; external secret store described but not wired.
- Memory: dev-login bypass still present in the UI path (prod-readiness blocker list in `gmepay-railway-deploy`).

**Impact**: partner webhook signatures, RBAC edge trust, and service-to-service auth are all forgeable by anyone who has read the repo. Cannot take paying customers.

**Done =** all `*-not-for-prod` defaults fail-fast at boot in the prod profile (refuse to start), secrets injected from a store (Vault/SOPS/cloud secret manager), DB passwords rotated per-instance, dev-login removed from prod builds.

---

## 6. Batch/settlement scheduler failures are log-and-swallow; no rerun tooling, no missed-run detection, holidays ignored [MAJOR]

**Evidence**
- `services/scheme-adapter-zeropay/.../batch/ZeroPayBatchScheduler.java`: six KST crons (02:00 ZP0011, 02:02 ZP0021, 05:00, 14:00, 22:00, 22:02); every one ends `catch (Exception e) { log.error(...) }` — no ops.alert emission, no retry, no dead-run marker. No manual batch-trigger controller exists in the adapter (`api/` has only ZeroPaySchemeController, RegistrationStatusController, DevDataController).
- `services/settlement-reconciliation/.../scheduler/SettlementGenerationScheduler.java`: same log-only catch blocks (lines ~83/94). Recon has rerun endpoints (`ReconRerunController` `/rerun`, `ReConExceptionController` `/{id}/re-run`) but generation does not.
- `config-registry` has a `BusinessDayCalendarEntity`/`SettlementScheduleCalculator` (holiday-aware) yet the batch crons fire every day regardless — Korean holidays will produce files ZeroPay may reject or double-count.

**Impact**: a failed 02:00 ZP0011 run is a silent settlement discrepancy discovered by the counterparty.

**Done =** every scheduler catch publishes an `ops.alert` (topic already exists), a POST admin endpoint to regenerate/retransmit any batch for a business date, a "did last night's runs happen" check (last-success timestamp exposed via actuator/alert), and generation consults the business-day calendar.

---

## 7. No incident or scheme-outage runbooks; 9Pay's documented escalation path is a manual email nobody owns [MAJOR]

**Evidence**
- Repo-wide grep for runbook/incident/on-call in docs: hits only inside backlog wishlists (`Documentation/services_backlog/*.md`, `docs/WBS_STATUS.md`) — no actual runbook file exists.
- `Documentation/schemes/digest_9pay-payout-api_2026-07-27.md` line 175: on prolonged timeout "escalate by email to 9Pay technical team for manual status confirmation" — no tooling, template, contact registry, or tracking queue backs this.
- No ZeroPay TCP/SFTP-outage procedure, no SendMN outage procedure, no severity matrix, no comms templates.

**Done =** per-scheme outage runbook (detection signal → circuit-breaker state → customer impact → who to contact → recovery/replay steps) for ZeroPay, SendMN, 9Pay; an incident-severity matrix; contact registry stored in config-registry, not in a docx.

---

## 8. No SLA/SLO definition or measurement [MAJOR]

**Evidence**
- Repo-wide search for SLA/percentile/p99 instrumentation: nothing beyond timeout exceptions (`SchemeTimeoutException.java`). No SLO doc, no per-partner uptime or latency tracking, no error budget. Follows directly from gap #3 (no metrics), but is a distinct contractual problem: partner contracts will promise availability the platform cannot measure.

**Done =** written SLOs (e.g. authorization p99 < Xs, availability 99.9%, webhook delivery < Ys), measured from gateway metrics, surfaced on an ops dashboard and in partner-facing reporting.

---

## 9. No load testing, capacity plan, or backpressure analysis — 10x behavior unknown [MAJOR]

**Evidence**
- No gatling/k6/jmeter/locust anywhere (source hits were only binary jars). `e2e-tests/` and `gmepay-test-platform` are functional, single-transaction testers.
- Kafka is a single broker (`docker-compose.yml` line 241, one `cp-kafka` node); no consumer-lag monitoring (the only backlog monitor is webhook-specific `WebhookBacklogMonitor`).
- No Hikari sizing anywhere in `src/main/resources` (defaults = 10 connections); `run-fleet.ps1` dev launcher caps Hikari at 5/1 and Tomcat at 20 threads — no prod equivalents exist. 15 postgres instances on one host compete for the same disk.
- Educated guess at first break: outbox publishers' poll loops + default 10-conn pools on transaction-mgmt/payment-executor saturate, then the single Kafka broker's disk.

**Done =** one scripted load test of the money path (scan→authorize→confirm) at target and 10x TPS, published results, per-service Hikari/Tomcat sizing in the prod profile, and Kafka consumer-lag alerting.

---

## 10. UNCERTAIN/stuck-payment ops loop is half-wired [MAJOR]

**Evidence**
- Good: `transaction-mgmt/.../api/TransactionController.java` line 408 — `POST /v1/transactions/{txnRef}/resolve` (ops force-resolve UNCERTAIN, idempotent, audited) exists; `StuckTransactionAlertSweeper` (UNCERTAIN_AGED / STUCK_TXN) exists.
- Gaps: the sweeper is default-off (gap #4); no evidence the resolve action is surfaced in admin-ui as an ops screen with the required evidence fields (scheme lookup result) and RBAC/approval gating; 9Pay's own recovery advice (poll lookup by request_id before resolving) is not built into the flow.

**Done =** sweeper on in prod, an admin-ui "uncertain queue" screen showing scheme-side lookup evidence next to a resolve button, resolve gated behind the existing approval-workflow RBAC.

---

## 11. Partner onboarding is a plan document, not an operational process [MAJOR]

**Evidence**
- `docs/PARTNER_SETUP_PLAN.md` — "Partner Setup Re-baseline Plan (v1)", 8 vertical slices (identity, contacts, KYB, banking, prefunding, commercial terms, schemes/corridors, credentials/lifecycle) explicitly re-baselining what exists; includes "Cross-cutting bug fixes (Slice 1, prerequisite)".
- Pieces exist (credential rotation: `config-registry/.../PartnerCredentialRotationScheduler.java`; IP allowlist: `api-gateway/.../PartnerIpAllowlistFilter.java` + `PartnerIpAllowlistEntity`), but there is no end-to-end onboarding checklist an ops person can execute, and no go-live gate (sandbox conformance → credentials issued → allowlist set → webhook verified → limits set).

**Done =** the 8 slices landed, plus a single "onboard partner X" checklist in admin-ui (or one doc) whose every step maps to a working screen/API, ending in an automated go-live conformance check.

---

## 12. Webhook dead-letter/backlog ops lack a UI surface and retention policy [MINOR]

**Evidence**
- Strong bones: `notification-webhook` has `RetryPolicy.java`, `WebhookDispatcher`, `WebhookBacklogMonitor` (emits ops.alert), and `api/WebhookReplayController.java` (replay). Gap: replay/dead-letter inspection appears API-only (no admin-ui screen found), and no documented retention/cleanup for exhausted deliveries.

**Done =** admin-ui page listing failed/exhausted deliveries per partner with one-click replay, plus a retention policy for the delivery table.

---

## 13. kyb-adapter is not deployable with the fleet [MINOR]

**Evidence**: `docs/DEPLOYMENT.md` — "kyb-adapter ships no Dockerfile yet and is therefore not a deployable"; it is absent from the Helm `services:` map. Onboarding KYB checks would silently depend on a service that cannot ship.

**Done =** Dockerfile + compose entry + Helm map entry, or an explicit ADR stating KYB runs elsewhere.

---

## 14. Container health probes are TCP-only even though actuator is now on every service [MINOR]

**Evidence**: `docker-compose.yml` header + `x-tcp-health` anchor — "Only api-gateway ships spring-boot-starter-actuator, so every other Spring service gets a plain TCP probe" — but grep shows `spring-boot-starter-actuator` is now in all 19 service `build.gradle` files. A service that is up-but-broken (DB down, Flyway failed after listen) passes the TCP probe.

**Done =** switch compose healthchecks and Helm liveness/readiness to `/actuator/health/liveness|readiness` per service.

---

## Count
- BLOCKER-for-operations: 5 (gaps 1–5)
- MAJOR: 6 (gaps 6–11)
- MINOR: 3 (gaps 12–14)
