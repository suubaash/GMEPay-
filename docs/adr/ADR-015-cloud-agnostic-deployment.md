# ADR-015 — Cloud-agnostic deployment (one portable Helm chart)

- **Status:** Accepted
- **Date:** 2026-06-30
- **Deciders:** Platform Team
- **Supersedes/relates:** ADR-001 (Kafka + Schema Registry), ADR-006 (document vault, S3-compatible), ADR-011 (Keycloak + auth-identity split)

## Context

GMEPay+ must be deployable to **on-prem Kubernetes, AWS (EKS), and Azure (AKS)**
without forking the application. The platform is ~16 Spring Boot services (one
of which is `ops-partner-bff`) plus two Next.js UIs, each already shipping a
Dockerfile and a `docker-compose.yml` for the single-host quickstart. Every
service already reads its backing-store coordinates from environment variables
with sensible local defaults — we want to preserve exactly that property at the
cluster scale.

The risk to avoid: provider lock-in leaking into the application (an AWS SDK
call here, an Azure Blob client there), which would make "the same build" a
fiction and force per-cloud branches.

## Decision

### 1. Principle — portable app + thin per-target overlay

The **same container images** deploy everywhere. A single umbrella Helm chart
(`deploy/helm/gmepay/`) renders a `Deployment` + `Service` for every deployable,
driven by a `services:` map in values. Everything provider-specific lives in a
**thin value overlay** (`values-onprem.yaml` / `values-aws.yaml` /
`values-azure.yaml`) that overrides only *where the backing stores live*. No
template is duplicated per service — one `_deployment.tpl` helper is ranged over
the map.

### 2. The portability ABI (inject everything as env, bake nothing)

Each backing dependency is reached through a fixed set of environment variables —
the **portability ABI**. These names are the exact ones the services read
(harvested from `application.*` + `docker-compose.yml`):

| Dependency      | Env vars (the ABI)                                                                                          |
| --------------- | --------------------------------------------------------------------------------------------------------- |
| Postgres        | `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` (per service)          |
| Redis           | `SPRING_DATA_REDIS_HOST`, `SPRING_DATA_REDIS_PORT`, `SPRING_DATA_REDIS_PASSWORD`                           |
| Kafka           | `SPRING_KAFKA_BOOTSTRAP_SERVERS` (+ schema-registry URL `GMEPAY_SCHEMA_REGISTRY_URL`)                      |
| Mongo           | `SPRING_DATA_MONGODB_URI`                                                                                  |
| Object store    | `GMEPAY_VAULT_ENDPOINT`, `GMEPAY_VAULT_REGION`, `GMEPAY_VAULT_ACCESSKEY`, `GMEPAY_VAULT_SECRETKEY`, `GMEPAY_VAULT_PATH_STYLE` |
| OIDC            | `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` (provider-neutral alias `OIDC_ISSUER_URI`)         |
| Telemetry       | `OTEL_EXPORTER_OTLP_ENDPOINT`                                                                              |

Non-secret values (endpoints, region, flags, issuer) ride in one **ConfigMap**
mounted via `envFrom`. Credentials ride in a **K8s Secret** that is *never
committed* — overlays carry placeholders only.

> **Naming note (real vs. ideal):** lib-vault binds `gmepay.vault.accessKey` /
> `secretKey`, so the env names that actually take effect are
> `GMEPAY_VAULT_ACCESSKEY` / `GMEPAY_VAULT_SECRETKEY` (no underscore before
> KEY) — the chart uses those. The api-gateway resource-server reads the Spring
> property `spring.security.oauth2.resourceserver.jwt.issuer-uri`, so the
> binding var is `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI`;
> `OIDC_ISSUER_URI` is shipped as a documented neutral alias.
> `GMEPAY_VAULT_REGION` / `GMEPAY_VAULT_PATH_STYLE` are part of the declared ABI
> and carried for S3-client forward-compat even though today's MinIO client does
> not yet bind them.

### 3. The "no provider SDK / open-protocol-only" rule

Services may depend **only on open protocols**, never on a cloud provider's SDK:

- Object storage is the **S3 API** (lib-vault → MinIO client). AWS = native S3;
  Azure = an S3-compatible gateway (MinIO gateway / Blob S3 proxy). No
  `azure-storage-blob`, no provider-specific client.
- Messaging is the **Kafka protocol**. AWS = MSK; Azure = Event Hubs' Kafka
  endpoint. No `azure-messaging-eventhubs`.
- Identity is **OIDC**. Keycloak self-hosted, or Cognito / Entra ID via their
  OIDC issuer. No provider auth SDK.
- Telemetry is **OTLP**. Any collector (self-hosted Prometheus/Grafana via OTLP,
  ADOT, managed) terminates it.

If a feature is only reachable via a provider SDK, it does not enter the app — it
is fronted by an open-protocol shim instead.

### 4. Layering

```
container image (identical everywhere)
        +
Helm chart  ── services map + _deployment.tpl  (portable)
        +
value overlay  ── on-prem | aws | azure        (thin: only the ABI + registry + ingress)
        +
K8s Secret  ── credentials, provisioned out-of-band in prod
```

## Managed-service mapping

| Capability     | On-prem (self-host)        | AWS                         | Azure                                         |
| -------------- | -------------------------- | --------------------------- | --------------------------------------------- |
| Postgres       | in-cluster / VM Postgres   | RDS                         | Azure DB for PostgreSQL Flexible Server       |
| Kafka          | self-host Kafka            | MSK                         | Event Hubs (Kafka protocol)                   |
| Redis          | in-cluster Redis           | ElastiCache                 | Azure Cache for Redis                         |
| Object store   | MinIO (path-style)         | S3 (virtual-hosted)         | MinIO-gateway / Blob S3-proxy (path-style)    |
| OIDC           | Keycloak                   | Keycloak-on-EKS / Cognito   | Keycloak-on-AKS / Entra ID                    |
| Secrets        | K8s Secret / sealed-secret | Secrets Manager via ESO/CSI | Key Vault via CSI                             |
| Observability  | Prometheus/Grafana (OTLP)  | managed / ADOT (OTLP)       | managed (OTLP)                                |
| Image registry | private registry           | ECR                         | ACR                                           |

## Consequences

- **Positive:** one build, one chart; cloud migration is a `-f values-<x>.yaml`
  swap; the open-protocol rule keeps the codebase free of provider branches; the
  ABI doubles as living documentation of every external dependency.
- **Negative:** Azure object storage and Event Hubs require an S3/Kafka
  compatibility shim rather than the native SDK (a deliberate trade — portability
  over a few percent of native features). The DB-per-service model means each
  service carries its own `SPRING_DATASOURCE_URL`, so an overlay lists one URL
  per stateful service.
- **Neutral:** `docker-compose.yml` remains the **on-prem single-host
  quickstart**; the Helm chart is the multi-node / cloud path. Both consume the
  same images and the same ABI env names.
