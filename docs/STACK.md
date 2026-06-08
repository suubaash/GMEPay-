# GMEPay+ — Locked Tech Stack (authoritative; tickets MUST name these concretely)

This is the final, approved stack. When re-authoring tickets, replace generic phrasing with these
CONCRETE technologies, file paths, and framework constructs so a developer builds the right thing.

## Build & repo
- **Monorepo**, **Gradle multi-module** (root `settings.gradle`, parent `build.gradle`, one module per service + shared libs).
- **Java 21 (LTS)**, **Spring Boot 3.x**, Spring Framework 6.
- Shared modules: `lib-money` (BigDecimal money/currency), `lib-errors` (canonical error model), `lib-api-contracts` (OpenAPI-generated DTOs), `lib-events` (domain event + Avro/JSON schemas), `lib-persistence` (common JPA/migration utils).

## Services (Spring Boot microservices)
QR Service, Smart Router, Rate & FX Engine, Auth & Identity, Payment Executor, Transaction Mgmt,
Prefunding/Balance, Notification & Webhook, Settlement & Reconciliation, Revenue & Accounting Ledger,
Reporting & Compliance, Merchant & QR Data, Scheme Adapter Layer (ZeroPay adapter first), Config & Registry,
plus **Ops/Partner BFF**. Edge: **Spring Cloud Gateway** (WAF/CDN/TLS in front).

## Persistence (polyglot)
- **PostgreSQL 16** (HA, multi-AZ) — money path: transactions, ledger, prefunding, config, audit. Use **NUMERIC** for money, `SELECT ... FOR UPDATE` for atomic prefunding deduction. Migrations via **Flyway**.
- **MongoDB** — merchant/QR store and CQRS read-models/projections.
- **Redis** — rate-quote TTL, config cache, idempotency keys.
- **Object Storage (S3-compatible)** — ZeroPay SFTP files, BOK/settlement reports, tax invoices, archives.

## Messaging / async (D2: Kafka deferred to integration)
- **Phase 1:** implement the **transactional Outbox table** + domain events + a `EventPublisher` interface (in-process/outbox-polling impl). Define all **event schemas now** in `lib-events`.
- **Integration phase:** wire **Apache Kafka + Schema Registry** as the transport (outbox → Kafka), with **DLQ**. Tickets should isolate Kafka behind the publisher/consumer interfaces so it can be enabled without rework.

## Security
- Partner API: **HMAC-SHA256 request signing + Idempotency-Key**; **Vault/KMS** for secrets and **field-level encryption**.
- Operator/Admin: **OAuth2 / JWT + RBAC**. **mTLS** for scheme connectivity certs.

## Connectivity
- **SFTP Gateway** service for ZeroPay PPF egress (`ZP00xx` files). **Scheme Adapter Layer = Anti-Corruption Layer**: each scheme protocol normalized to an internal contract; ZeroPay adapter = REST + SFTP.

## Frontend
- **React + Next.js (TypeScript)** for Ops/Admin Portal and Partner Self-Service Portal; BFF aggregation API.

## Platform / deploy
- **Docker**; **Kubernetes**; **GitOps via Argo CD**; **Helm** (or Kustomize) charts; CI/CD pipeline (build→test→scan→image→deploy, rollback).
- **docker-compose** for local dev infra (Postgres, Mongo, Redis, Kafka, Vault, MinIO).

## Observability
- **OpenTelemetry** (traces/metrics/logs) → **Prometheus + Grafana** (metrics/alerts), **ELK** (logs), **Jaeger** (tracing).

## Testing
- **JUnit 5**, **Testcontainers** (Postgres, Kafka, Mongo, Redis), **WireMock** for scheme/partner APIs, Spring Boot test slices, contract tests vs the OpenAPI (`openapi/partner-api.yaml`).

## Re-authoring instruction
Keep each ticket's <=60-min scope, self-containment, deliverable, and logic checks. Update: name the exact
module/service, the concrete datastore (Postgres/Mongo/Redis/Object Storage), Spring constructs
(@RestController, @Service, Flyway migration Vnnn__*.sql, Spring Cloud Gateway filter, Testcontainers test),
and the messaging approach (outbox now, Kafka behind interface). Deliverables become real file paths
(e.g. `services/rate-fx/src/main/java/.../RateEngine.java`, `db/migration/V3__treasury_rates.sql`).
