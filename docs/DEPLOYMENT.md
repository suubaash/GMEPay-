# GMEPay+ Deployment Guide

How to deploy the whole platform to **on-prem Kubernetes, AWS (EKS), or Azure
(AKS)** from one portable Helm chart, differing only by a value overlay. See
[ADR-015](adr/ADR-015-cloud-agnostic-deployment.md) for the principle and the
portability ABI.

> **Single-host quickstart:** for a laptop / single VM, `docker-compose.yml`
> still boots the money path (`docker compose --profile core up --build`) or
> everything (`--profile full`). The Helm chart below is the multi-node / cloud
> path; both consume the **same images** and the **same env-var ABI**.

## What gets deployed

The chart renders a `Deployment` + `Service` for **18 deployables**, enumerated
in the `services:` map of `values.yaml`:

- **16 backend services:** api-gateway, auth-identity, config-registry,
  merchant-qr-data, notification-webhook, ops-partner-bff, payment-executor,
  prefunding, qr-service, rate-fx, reporting-compliance, revenue-ledger,
  scheme-adapter-zeropay, settlement-reconciliation, smart-router,
  transaction-mgmt.
  *(kyb-adapter ships no Dockerfile yet and is therefore not a deployable.)*
- **2 UIs:** admin-ui (3000), partner-portal-ui (3001).

An **Ingress** fronts api-gateway + both UIs. A **ConfigMap** carries the
non-secret ABI; a **Secret** carries credentials (placeholders in the overlays —
replace before any real traffic).

## Chart layout

```
deploy/helm/gmepay/
├── Chart.yaml
├── values.yaml                # schema + provider-neutral defaults
├── values-onprem.yaml         # overlay: self-host / MinIO / Keycloak
├── values-aws.yaml            # overlay: RDS / MSK / ElastiCache / S3 / ECR
├── values-azure.yaml          # overlay: Flexible Server / Event Hubs / Azure Cache / ACR
└── templates/
    ├── _helpers.tpl           # names + labels
    ├── _deployment.tpl        # the ONE Deployment+Service helper (ranged over services)
    ├── deployments.yaml       # ranges .Values.services
    ├── configmap.yaml         # non-secret ABI
    ├── secret.yaml            # credentials (create:false in prod)
    ├── ingress.yaml           # api-gateway + 2 UIs
    └── NOTES.txt
```

## Prerequisites

- `kubectl` context pointing at the target cluster, `helm` 3.x.
- A namespace: `kubectl create namespace gmepay`.
- Images built + pushed to the target registry (local registry / ECR / ACR) with
  a consistent tag; set `global.imageRegistry` + `global.imageTag` in the overlay.
- The backing stores provisioned (or, on-prem, deployed in-cluster) — see the
  mapping table below.
- A **private** secrets file (e.g. `my-secrets.yaml`) supplying real values for
  the `secrets.data.*` keys — never committed. In production prefer
  `secrets.create=false` and an external secret store (below).

## Validate before deploy

```sh
helm lint deploy/helm/gmepay
helm template gmepay deploy/helm/gmepay -f deploy/helm/gmepay/values-onprem.yaml | kubectl apply --dry-run=client -f -
```

## Deploy — on-prem

Backing stores in-cluster (or external on-prem hosts named in the overlay).
Object store = MinIO (`GMEPAY_VAULT_PATH_STYLE=true`); OIDC = self-hosted Keycloak.

```sh
helm upgrade --install gmepay deploy/helm/gmepay \
  --namespace gmepay \
  -f deploy/helm/gmepay/values-onprem.yaml \
  -f my-onprem-secrets.yaml
```

## Deploy — AWS (EKS)

Postgres→RDS, Kafka→MSK, Redis→ElastiCache, object store→S3
(`GMEPAY_VAULT_ENDPOINT=https://s3.<region>.amazonaws.com`, `PATH_STYLE=false`,
`REGION` set), OIDC→Keycloak-on-EKS or Cognito issuer, registry→ECR. Fill in the
RDS endpoints per service (DB-per-service) and the ECR registry in
`values-aws.yaml`.

```sh
aws ecr get-login-password --region <region> | \
  helm registry login --username AWS --password-stdin <acct>.dkr.ecr.<region>.amazonaws.com   # if using OCI

helm upgrade --install gmepay deploy/helm/gmepay \
  --namespace gmepay \
  -f deploy/helm/gmepay/values-aws.yaml \
  -f aws-secrets.yaml      # or omit and use External Secrets Operator (see below)
```

## Deploy — Azure (AKS)

Postgres→Azure DB for PostgreSQL Flexible Server, Kafka→Event Hubs (Kafka
protocol endpoint), Redis→Azure Cache for Redis, object store→S3-compatible via
MinIO-gateway / Blob S3-proxy (`PATH_STYLE=true`), OIDC→Keycloak-on-AKS or Entra
ID issuer, registry→ACR.

```sh
az acr login --name gmepayacr

helm upgrade --install gmepay deploy/helm/gmepay \
  --namespace gmepay \
  -f deploy/helm/gmepay/values-azure.yaml \
  -f azure-secrets.yaml    # or omit and use the Key Vault CSI driver (see below)
```

## Managed-service mapping

| Capability     | On-prem (self-host)        | AWS                         | Azure                                         | ABI env                                                                 |
| -------------- | -------------------------- | --------------------------- | --------------------------------------------- | ----------------------------------------------------------------------- |
| Postgres       | in-cluster / VM Postgres   | **RDS**                     | **Azure DB for PostgreSQL Flexible Server**   | `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` (per service)        |
| Kafka          | self-host Kafka            | **MSK**                     | **Event Hubs (Kafka API)**                    | `SPRING_KAFKA_BOOTSTRAP_SERVERS` (+ `GMEPAY_SCHEMA_REGISTRY_URL`)        |
| Redis          | in-cluster Redis           | **ElastiCache**             | **Azure Cache for Redis**                     | `SPRING_DATA_REDIS_HOST` / `_PORT` / `_PASSWORD`                        |
| Object store   | **MinIO**                  | **S3**                      | **Blob via S3 gateway / MinIO**               | `GMEPAY_VAULT_ENDPOINT` / `_REGION` / `_ACCESSKEY` / `_SECRETKEY` / `_PATH_STYLE` |
| Mongo          | in-cluster Mongo           | DocumentDB / self-host      | Cosmos DB for MongoDB                         | `SPRING_DATA_MONGODB_URI`                                                |
| OIDC           | **Keycloak**               | Keycloak-on-EKS / **Cognito** | Keycloak-on-AKS / **Entra ID**             | `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` (alias `OIDC_ISSUER_URI`) |
| Secrets        | **K8s Secret / sealed**    | Secrets Manager (ESO/CSI)   | Key Vault (CSI)                               | (the `secrets.data.*` keys)                                             |
| Observability  | Prometheus/Grafana (OTLP)  | managed / ADOT (OTLP)       | managed (OTLP)                                | `OTEL_EXPORTER_OTLP_ENDPOINT`                                            |
| Image registry | private registry           | **ECR**                     | **ACR**                                       | `global.imageRegistry` / `global.imageTag`                              |

### Path-style vs. virtual-hosted object storage

- **AWS S3 (native):** `GMEPAY_VAULT_PATH_STYLE=false`, `GMEPAY_VAULT_REGION`
  set, `GMEPAY_VAULT_ENDPOINT=https://s3.<region>.amazonaws.com`.
- **MinIO / S3 gateway (on-prem + Azure):** `GMEPAY_VAULT_PATH_STYLE=true`.

## Secrets in production (recommended: do NOT render the chart Secret)

Set `secrets.create=false` and provision a Secret named `<release>-credentials`
out-of-band, with the same keys. The Deployments bind to it unchanged:

- **AWS:** External Secrets Operator (or Secrets Store CSI) → AWS Secrets Manager.
- **Azure:** Secrets Store CSI driver → Azure Key Vault.
- **On-prem:** sealed-secrets or a HashiCorp Vault-backed Secret.

Required keys (see any overlay's `secrets.data`):
`SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`,
`SPRING_DATA_REDIS_PASSWORD`, `GMEPAY_VAULT_ACCESSKEY`, `GMEPAY_VAULT_SECRETKEY`,
`GMEPAY_RBAC_SECRET`, `GMEPAY_INTERNAL_AUTH_SECRET`,
`GMEPAY_WEBHOOK_SIGNING_SECRET`.

## Health probes

Only **api-gateway** and **auth-identity** ship `spring-boot-starter-actuator`,
so they use HTTP readiness/liveness probes on `/actuator/health`
(`services.<svc>.probe.type: http`). Every other service uses a **TCP socket
probe** on its listen port (`probe.type: tcp`), mirroring the docker-compose
`x-tcp-health` shape. Adding actuator to a module later is a one-line overlay
flip to `probe: { type: http }`.

## Post-deploy checks

```sh
kubectl -n gmepay get pods
kubectl -n gmepay get ingress
kubectl -n gmepay logs deploy/gmepay-api-gateway | tail
```
