> 작업: cloud-agnostic Helm + docs / 출처: agent

# Cloud-agnostic deployment layer — GMEPay+

One portable Helm umbrella chart deploys the whole platform to on-prem / AWS /
Azure Kubernetes, differing only by a value overlay + env. Same container images
everywhere; every backing dependency injected as env (the portability ABI),
never baked.

## Chart layout — `deploy/helm/gmepay/`

```
Chart.yaml
values.yaml                # schema + provider-neutral defaults; the services: map
values-onprem.yaml         # overlay
values-aws.yaml            # overlay
values-azure.yaml          # overlay
templates/
  _helpers.tpl             # names + labels
  _deployment.tpl          # the ONE Deployment+Service helper (ranged over services — DRY)
  deployments.yaml         # ranges .Values.services
  configmap.yaml           # non-secret ABI (mounted via envFrom into every pod)
  secret.yaml              # credentials; create:false in prod (ESO/CSI/sealed)
  ingress.yaml             # api-gateway + admin-ui + partner-portal-ui
  NOTES.txt
```

Plus `deploy/CHANGELOG.md`, `docs/adr/ADR-015-cloud-agnostic-deployment.md`,
`docs/DEPLOYMENT.md`.

## Deployable count: 18

16 backend services (incl. ops-partner-bff) + admin-ui + partner-portal-ui.
kyb-adapter excluded — it ships no Dockerfile, so it is not containerized.

## Portability ABI (exact env names harvested from compose + application.*)

Per-service `SPRING_DATASOURCE_URL/_USERNAME/_PASSWORD`; `SPRING_DATA_REDIS_HOST/
_PORT/_PASSWORD`; `SPRING_KAFKA_BOOTSTRAP_SERVERS` (+ `GMEPAY_SCHEMA_REGISTRY_URL`);
`SPRING_DATA_MONGODB_URI`; `GMEPAY_VAULT_ENDPOINT/_REGION/_ACCESSKEY/_SECRETKEY/
_PATH_STYLE`; `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` (neutral
alias `OIDC_ISSUER_URI`); `OTEL_EXPORTER_OTLP_ENDPOINT`. Cross-service secret
aliases (RBAC stamp/verify, internal-auth, webhook) via `envSecretAliases`.

Non-secret values → one ConfigMap; credentials → one Secret (placeholders only,
never committed).

## The 3 overlays (placeholders only)

- **on-prem** — in-cluster/external on-prem stores; MinIO `PATH_STYLE=true`;
  Keycloak OIDC; private registry; nginx ingress.
- **aws** — RDS (per-service URLs), MSK, ElastiCache, S3 (`s3.<region>...`,
  `PATH_STYLE=false`, REGION set), Keycloak/Cognito OIDC, ECR, ALB ingress.
- **azure** — PostgreSQL Flexible Server, Event Hubs (Kafka API), Azure Cache,
  S3-gateway (`PATH_STYLE=true`), Keycloak/Entra OIDC, ACR, App Gateway ingress.

## Probes

api-gateway + auth-identity → HTTP `/actuator/health` (only modules with
actuator); all others → TCP socket probe (mirrors compose `x-tcp-health`).
Per-service overridable.

## Validation — PASS (real helm 3.16.2)

`helm lint` → 0 failed (only cosmetic "icon recommended" INFO).
`helm template` with each overlay renders identically: 18 Deployments + 18
Services + 1 Ingress + 1 ConfigMap + 1 Secret. Spot-checked AWS render:
deep-merge keeps base env while overlay rewrites datasource URL; S3 path-style
flag, OIDC issuer, and secret aliases resolve correctly.

## Deploy invocation (per target)

```
helm upgrade --install gmepay deploy/helm/gmepay -n gmepay \
  -f deploy/helm/gmepay/values-<onprem|aws|azure>.yaml \
  -f my-secrets.yaml            # private credentials, not committed
```
