> 작업: DevOps/SRE audit / 출처: agent

# GMEPay+ — DevOps / SRE / Platform-Engineering Production-Operability Audit
**Date:** 2026-07-01 · **Author:** Head of DevOps/SRE/Platform (agent) · **Lens:** the pipes, pipelines and production plumbing needed to ship, run and keep the platform up safely.

## Scope note
Audited the *actual* repo state (`code/.github/workflows/`, `code/deploy/helm/`, `code/docker-compose.yml`, service `build.gradle`/`application.yml`, `docs/DEPLOYMENT.md`) against the `platform-infra` backlog (WBS 2.3 / 14.x / 16.x). The backlog is excellent and detailed — but most of it is **planned, not built**. This audit flags the delta between the plan and what a fresh `git clone` actually gives you. Not repeating CTO items (scheme SDK, HA topology, tracing design, data platform) or Ops items (control tower, dispute workflow). Only genuine gaps.

Two framing facts that shape everything below:
- **CI does build+test only.** `ci.yml` runs `./gradlew build`, Testcontainers ITs, a compose-smoke, and UI lint/build. There is **no** security-scan stage, **no** image build/push, **no** deploy job, **no** coverage gate. The one file named "publish" (`maven-publish.yml`) is a dead scaffold — JDK 11 + Maven on a Gradle/JDK-21 monorepo, triggered on GitHub releases that are never cut.
- **The app Helm chart is solid; everything around it is undone.** `Chart.yaml` declares **zero** dependencies; there is no monitoring, no DB/broker/cache IaC, no migration Job, no HPA/PDB/NetworkPolicy/quota. Backing stores are assumed to "already exist."

---

## TOP DevOps / SRE gaps (priority order)

### 1. No Continuous Deployment — deployment is a human running `helm upgrade` from a laptop
`docs/DEPLOYMENT.md` documents manual `helm upgrade --install` with a hand-supplied `my-secrets.yaml`. There is no GitOps controller (Argo CD / Flux), no pipeline that builds an image, tags it, pushes to a registry, and rolls the cluster. **Why it matters:** every production change to a money-movement platform is a manual, unaudited, unrepeatable act with no git-as-source-of-truth, no drift reconciliation, and no record of "what is actually running right now." This is the single largest go-live blocker.

### 2. No supply-chain security in CI — images ship unscanned, unsigned, no SBOM
The backlog (2.3-T13) specifies SpotBugs/find-sec-bugs SAST, OWASP dependency-check (fail on CVSS≥7), and Gitleaks — **none are in `build.gradle` or `ci.yml`** (grep: 0 hits). No image scan (Trivy/Grype), no SBOM (Syft/CycloneDX), no signing (cosign), no base-image patch cadence. (Note: base images are `eclipse-temurin:21`, not the Rocky images the brief assumed — good, but still unscanned and un-pinned to digests.) **Why it matters:** a payment hub handling partner credentials must be able to answer "are we exposed to CVE-X?" and prove image provenance for audit; today it cannot, and vulnerable deps reach prod silently.

### 3. DB migrations run in-app on boot — no pre-deploy migration gate, no checksum-drift guard in CI
Flyway runs inside each service at startup (`FlywayConfig`, `baseline-on-migrate=true`). There is **no Helm pre-upgrade migration Job/hook** and no CI check that migrations are additive / that a committed migration's checksum hasn't drifted. The V022 in-place-edit checksum incident is exactly this class of failure and it can recur undetected. **Why it matters:** at scale, N replicas racing to migrate on boot, or a bad migration, takes the service down *after* the rollout has started — the opposite of a safe expand→migrate→deploy→contract sequence. A blocked migration = a down service, not a failed gate.

### 4. No zero-downtime rollout strategy and no one-click rollback runbook
Deployments use the default RollingUpdate with no `maxSurge/maxUnavailable` tuning, no PodDisruptionBudget, no blue-green/canary, and no documented `helm rollback` procedure tied to a known-good revision. With migrations running in-app (gap #3), rollback is actively dangerous (new schema, old code). **Why it matters:** you cannot safely ship on a live payment path without a rehearsed, fast, forward-and-back path — and right now neither exists.

### 5. Metrics are effectively dark — `micrometer-registry-prometheus` is in 0 of 19 services
Only api-gateway (and two scheme adapters) even declare a `management:` block; the prometheus registry dependency the backlog mandated (2.3-T03/T11) is **wired in zero services**. There is no Prometheus/Grafana/Alertmanager deployed (Chart.yaml has no monitoring subchart), no ServiceMonitor, no host/cluster/DB/broker dashboards. `OTEL_EXPORTER_OTLP_ENDPOINT` is declared in the ABI but points at a collector nobody deploys. **Why it matters:** 16 of 18 deployables emit no scrapeable metrics — you are running a real-time cross-border payment system with no golden-signal visibility and no way to alert before customers notice.

### 6. No alerting / paging / incident-management / on-call plumbing
No Alertmanager routes, no PagerDuty/Opsgenie integration, no escalation policy, no status page, no postmortem process. Grep for on-call/SLO/RTO/RPO/escalation across docs → only backlog mentions, nothing operational. **Why it matters:** when settlement fails at 02:00 KST, nothing wakes anyone. Detection→page→escalation is the core SRE loop and it is absent end-to-end.

### 7. Batch/scheduled jobs are multi-replica-unsafe and unmonitored — no distributed lock, no missed-run alerting
Real schedulers exist (BOK/KOFIU/Hometax reporting, settlement generation, XE rate fetch, authorization-expiry sweeper, credential rotation, webhook dispatcher). **ShedLock is explicitly NOT implemented** — code comments in `SettlementGenerationScheduler` and `WebhookDispatcher` call it a "documented follow-up." There is no run-registry, no missed-run/failure alert, no idempotent-rerun tooling, no dead-letter for the outbox. **Why it matters:** run these at ≥2 replicas and settlement/regulatory jobs **double-fire** (duplicate BOK filings, double clawbacks); run at 1 replica and there's no HA. Either way a silently missed regulatory run is a compliance breach nobody is paged about.

### 8. No Infrastructure-as-Code for anything below the app chart
There are **no** Terraform/Pulumi/Ansible artifacts (grep: 0). Postgres (per-service DBs), Kafka/MSK, Redis, Mongo, object store, DNS, TLS, VPC, IAM, registries — all assumed pre-existing and provisioned by hand. `DEPLOYMENT.md` literally says "backing stores provisioned (or on-prem, deployed in-cluster)." **Why it matters:** dev/stage/prod parity is impossible to guarantee, environment rebuild after a disaster is a multi-day manual scramble, and there is no reviewable record of production infra.

### 9. No backup / PITR / restore automation and no DR drills
Zero backup tooling anywhere (grep: no `pg_dump`, wal-g, barman, Velero, pgBackRest). No point-in-time-recovery config, no tested restore, no DR runbook, no drill cadence. For a DB-per-service topology that's ~10 Postgres instances plus Mongo with **no** backup story. **Why it matters:** the ledger, prefunding balances and transaction records are the business. An unbackuped, never-restore-tested payment platform is one bad migration or disk failure from irrecoverable financial-record loss.

### 10. No connection pooling / DB failover posture
No PgBouncer (grep: 0). Each of ~16 services opens its own Hikari pool straight at Postgres; at replica scale this exhausts `max_connections` fast. No documented read-replica / failover / promotion posture. **Why it matters:** connection exhaustion is a classic self-inflicted payment-platform outage, and there's no plan for a primary DB going down.

### 11. Secrets & TLS-certificate operations are bootstrap-grade, no rotation/expiry alerting
Chart ships a Secret full of `CHANGE_ME_*` placeholders; `DEPLOYMENT.md` correctly points at ESO/CSI/sealed-secrets for prod but there is **no wired implementation, no rotation schedule, no secret-drift detection, and no TLS-cert lifecycle** (no cert-manager, no expiry alerting, ingress `tls: []` is empty). Per-scheme mTLS / IP-allowlist config has no managed lifecycle. **Why it matters:** a silently expired scheme mTLS cert or partner-webhook signing secret = a sudden, total settlement outage with no early warning.

### 12. Kubernetes hardening gaps — no NetworkPolicy, autoscaling, quotas, or upgrade plan
The chart sets sane resource requests/limits (good) but has **no** HPA (fixed `replicas: 1` everywhere), no PodDisruptionBudget, no NetworkPolicy (every pod can talk to every pod — flat network for a system holding card-adjacent/PII data), no ResourceQuota/LimitRange, and no node/cluster-upgrade runbook. **Why it matters:** a single replica per service = no HA and no load headroom; a flat network fails PCI-adjacent segmentation expectations; unbounded namespaces let one service starve the cluster.

### 13. No environment/config management or feature-flag delivery mechanics
There is one set of Helm overlays (onprem/aws/azure) but no dev/stage/prod environment overlays, no promotion path between environments, and no runtime feature-flag delivery (flags like `GMEPAY_RBAC_ENABLED` are static env requiring a redeploy to flip). No config-drift detection between what's in git and what's live. **Why it matters:** you can't safely dark-launch, canary a scheme, or kill-switch a misbehaving corridor without a full redeploy — and you can't tell if prod config has drifted from source.

### 14. No centralized log aggregation or audit-log WORM shipping ops
Services use structured logging (logstash-encoder) but nothing ships/aggregates it — no Loki/ELK/Fluent-bit deployed. Separately, the audit stream has no operational sink: no shipping to object-store, no WORM/immutability, no retention enforcement. **Why it matters:** during an incident you're SSH-ing into pods reading `kubectl logs`; for compliance you cannot prove audit records are tamper-proof and retained.

### 15. No edge protection (WAF / rate-limit / DDoS) and no FinOps/capacity hooks
The api-gateway does HMAC/OIDC (good) but there's no WAF, no gateway rate-limiting config (grep: 0), no CDN/DDoS posture in front of the public ingress. Separately there is no cost/capacity tagging, budget alerting, or right-sizing feedback loop. **Why it matters:** a public payment API with no rate-limit/WAF is a fraud-and-DDoS magnet; and unmanaged cloud spend across ~10 DBs + MSK + N replicas will surprise finance.

---

## Summary
The **application** is well-engineered and the Helm chart's portability design is genuinely good. The gap is the **production operating envelope around it**: CI stops at build+test, there is no CD, no security scanning, no monitoring/alerting/paging, no IaC for backing stores, no backup/DR, unsafe batch-job concurrency, and no secret/cert/DB-ops lifecycle. Most of this was *planned* in the `platform-infra` backlog (WBS 2.3/14.x/16.x) but **not executed** — the backlog reads as done-looking, the repo shows it isn't. Nothing here should ship to a live money path until at least gaps #1–#9 are closed. Recommend a dedicated platform-hardening wave before go-live, sequenced: CI security stages → image build+registry+GitOps CD → migration gate + rollback runbook → monitoring/alerting/paging → backup/DR drill → batch-job locking → IaC + secret/cert ops.
