# GMEPay+ — Locked Tech Stack (authoritative; tickets MUST name these concretely)

This is the final, approved stack. When re-authoring tickets, replace generic phrasing with these
CONCRETE technologies, file paths, and framework constructs so a developer builds the right thing.

## Architecture decisions (ADRs — resolve all prior ambiguities)
Five stack contradictions between the source images were decided 2026-06-10 (ticket 18.7-G01).
A second batch of ADRs (006–014) was decided 2026-06-11 for the Partner Setup re-baseline.
The ADRs below are authoritative; this file reflects them.

Stack ADRs (2026-06-10):
- **ADR-001** (`docs/adr/ADR-001-message-broker-kafka.md`) — broker is **Apache Kafka + Confluent Schema Registry**. **RabbitMQ is rejected and removed from the stack.**
- **ADR-002** (`docs/adr/ADR-002-edge-nginx-plus-scg.md`) — **Nginx (TLS/WAF) layered in front of Spring Cloud Gateway**; both, never one instead of the other.
- **ADR-003** (`docs/adr/ADR-003-mongodb-merchant-mirror.md`) — **MongoDB kept, scoped strictly to `merchant-qr-data`**.
- **ADR-004** (`docs/adr/ADR-004-rocky-linux-base-images.md`) — our service images build on **Rocky Linux 9**.
- **ADR-005** (`docs/adr/ADR-005-elasticsearch-role.md`) — **Elasticsearch = ELK log store first**; transaction search is a deferred option.

Partner Setup re-baseline ADRs (2026-06-11):
- **ADR-006** (`docs/adr/ADR-006-document-vault-minio.md`) — **MinIO with object-lock (compliance, 10y) + versioning** for KYB documents and regulatory artefacts.
- **ADR-007** (`docs/adr/ADR-007-audit-log-three-tier.md`) — **three-tier audit**: dedicated `audit` Postgres (hash chain, INSERT-only role) + Kafka topics + MinIO cold archive.
- **ADR-008** (`docs/adr/ADR-008-four-eyes-change-request.md`) — **4-eyes maker-checker via `change_request` table + Spring State Machine** (no workflow engine for partner setup).
- **ADR-009** (`docs/adr/ADR-009-kyb-provider-port.md`) — vendor-agnostic `KybProvider` port for sanctions screening & KYB; concrete vendor recorded in ADR-014.
- **ADR-010** (`docs/adr/ADR-010-bitemporal-effective-dating.md`) — **bitemporal** (valid_time × transaction_time) on partner-domain aggregates; SCD Type 6 storage.
- **ADR-011** (`docs/adr/ADR-011-keycloak-and-auth-identity-split.md`) — **Keycloak for human auth**, **auth-identity for machine auth**; retires `password=demo`.
- **ADR-012** (`docs/adr/ADR-012-server-side-draft-persistence.md`) — **server-side wizard draft persistence**; draft *is* the row in `status=ONBOARDING`.
- **ADR-013** (`docs/adr/ADR-013-migration-expand-contract.md`) — **Flyway 10 + Expand/Backfill/Contract** discipline; three-release horizon for breaking schema changes.
- **ADR-014** (`docs/adr/ADR-014-kyb-vendor-octa-solution.md`) — **Octa Solution** is the concrete KYB vendor implementing the ADR-009 port.

## Build & repo
- **Monorepo**, **Gradle multi-module** (root `settings.gradle`, parent `build.gradle`, one module per service + shared libs).
- **Java 21 (LTS)**, **Spring Boot 3.x**, Spring Framework 6.
- Shared modules: `lib-money` (BigDecimal money/currency), `lib-errors` (canonical error model), `lib-api-contracts` (OpenAPI-generated DTOs), `lib-events` (domain event + Avro/JSON schemas), `lib-persistence` (common JPA/migration utils).

## Services (Spring Boot microservices)
QR Service, Smart Router, Rate & FX Engine, Auth & Identity, Payment Executor, Transaction Mgmt,
Prefunding/Balance, Notification & Webhook, Settlement & Reconciliation, Revenue & Accounting Ledger,
Reporting & Compliance, Merchant & QR Data, Scheme Adapter Layer (ZeroPay adapter first), Config & Registry,
plus **Ops/Partner BFF** (`services/ops-partner-bff`, 19-endpoint aggregation API for the two UIs) and the
**SFTP Gateway** (`services/sftp-gateway`, planned — WS 18.3).

**Edge (ADR-002 — layered, both):** **Nginx** is the single public entrypoint — TLS termination, WAF
(OWASP CRS), request-id injection, gzip, static/UI routing (`/admin`, `/portal`), coarse rate limiting.
**Spring Cloud Gateway** sits *behind* Nginx as the API gateway — partner-facing API routing, HMAC
verification, idempotency, per-key rate limits (existing WBS 8.x filters stand unchanged). SCG is never
internet-facing. See `docs/adr/ADR-002-edge-nginx-plus-scg.md`.

## Persistence (polyglot)
- **PostgreSQL 16** (HA, multi-AZ) — money path: transactions, ledger, prefunding, config, audit. Use **NUMERIC** for money, `SELECT ... FOR UPDATE` for atomic prefunding deduction. Migrations via **Flyway**.
- **MongoDB** (ADR-003) — scoped **strictly to `merchant-qr-data`**: the KFTC merchant/QR mirror plus its sync staging. Read-mostly, rebuildable from KFTC files. **No other service may read Mongo directly** — access only via `GET /v1/merchants/{qr}`. Everything transactional/financial stays in per-service PostgreSQL. See `docs/adr/ADR-003-mongodb-merchant-mirror.md`.
- **Redis** — rate-quote TTL, config cache, idempotency keys.
- **Object Storage (S3-compatible)** — ZeroPay SFTP files, BOK/settlement reports, tax invoices, archives.

## Messaging / async (ADR-001: Apache Kafka — decided)
- Broker is **Apache Kafka + Confluent Schema Registry** (compatibility = BACKWARD). **RabbitMQ was considered and rejected** — any remaining RabbitMQ reference anywhere is superseded by `docs/adr/ADR-001-message-broker-kafka.md`.
- Conventions: topics `gmepay.<aggregate>.<event>`, per-aggregate keys for ordering, `acks=all` idempotent producer, DLQ per consumer.
- **Phase 1 (built):** **transactional Outbox table** + domain events behind the broker-agnostic `EventPublisher` interface in `lib-events` (in-process/outbox-polling impl). All **event schemas live in `lib-events`**.
- **Integration phase:** the Kafka transport lives in **`libs/lib-events-kafka`** (`KafkaEventPublisher`, outbox → Kafka drain, consumers — tickets 17.4-G01..G05). Services keep depending on the `lib-events` interfaces only.

## Security
- Partner API: **HMAC-SHA256 request signing + Idempotency-Key**; **Vault/KMS** for secrets and **field-level encryption**.
- Operator/Admin: **OAuth2 / JWT + RBAC**. **mTLS** for scheme connectivity certs.

## Connectivity
- **SFTP Gateway** service for ZeroPay PPF egress (`ZP00xx` files). **Scheme Adapter Layer = Anti-Corruption Layer**: each scheme protocol normalized to an internal contract; ZeroPay adapter = REST + SFTP.

## Frontend
- **React + Next.js (TypeScript)** for Ops/Admin Portal and Partner Self-Service Portal; BFF aggregation API.

## Platform / deploy
- **Docker**; **Kubernetes**; **GitOps via Argo CD**; **Helm** (or Kustomize) charts; CI/CD pipeline (build→test→scan→image→deploy, rollback).
- **Base images (ADR-004):** production/staging images for **our** services are built on **`rockylinux:9-minimal`** — + Temurin 21 JRE for Java services, + Node 20 for the UIs (Dockerfiles land with `platform-infra` 14.x). Off-the-shelf infra (postgres, kafka, redis, mongo, nginx) keeps official upstream images. See `docs/adr/ADR-004-rocky-linux-base-images.md`.
- **docker-compose** for local dev infra (Postgres, Mongo, Redis, Kafka + Schema Registry, Vault, MinIO, Nginx); local-dev compose may keep upstream convenience images.

## Observability
- **OpenTelemetry** (traces/metrics/logs) → **Prometheus + Grafana** (metrics/alerts), **ELK** (logs), **Jaeger** (tracing).
- **Elasticsearch role (ADR-005):** log store of the ELK stack only (Filebeat/Logstash → ES → Kibana) — observability, **never a system of record** (rebuildable from logs/events). A transaction-search read model (CQRS index fed from Kafka `payment.*` events for admin-UI free-text search) is a **deferred option**, only if PostgreSQL query performance proves insufficient — not scheduled in v3; admin transaction monitoring keeps querying `transaction-mgmt` via the BFF. See `docs/adr/ADR-005-elasticsearch-role.md`.

## Testing
- **JUnit 5**, **Testcontainers** (Postgres, Kafka, Mongo, Redis), **WireMock** for scheme/partner APIs, Spring Boot test slices, contract tests vs the OpenAPI (`openapi/partner-api.yaml`).

## Re-authoring instruction
Keep each ticket's <=60-min scope, self-containment, deliverable, and logic checks. Update: name the exact
module/service, the concrete datastore (Postgres/Mongo/Redis/Object Storage), Spring constructs
(@RestController, @Service, Flyway migration Vnnn__*.sql, Spring Cloud Gateway filter, Testcontainers test),
and the messaging approach (outbox now, Kafka behind interface). Deliverables become real file paths
(e.g. `services/rate-fx/src/main/java/.../RateEngine.java`, `db/migration/V3__treasury_rates.sql`).
