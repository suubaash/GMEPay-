# deploy — CHANGELOG

## 2026-07-02 — Wire Nepal corridor into deploy manifests

### Added
- **`scheme-adapter-nepal` + `sim-nepal-qr` now in `docker-compose.yml`** (profile
  `full`). Previously the Nepal rail had a Dockerfile but was in no manifest, so a
  deploy booted everything *except* Nepal — a scanned Fonepay QR had no rail.
  - New `simulators/sim-nepal-qr/Dockerfile` (standalone Gradle build via root
    wrapper `-p`; runs on 8080 in-container for the shared TCP health probe).
  - `scheme-adapter-nepal` → `sim-nepal-qr` via `GMEPAY_SCHEME_NEPAL_BASE_URL`.
  - `payment-executor` gets `GMEPAY_SCHEME_ADAPTERS_NEPAL_BASE_URL` (env only, **no**
    `depends_on` — keeps the Korea-only `core` profile bootable).
- **Helm chart**: `scheme-adapter-nepal` added to `values.yaml` (real service ships
  to cloud; the `sim-nepal-qr` simulator does **not** — override
  `GMEPAY_SCHEME_NEPAL_BASE_URL` per overlay to the live Nepal partner endpoint).
- Both `bootJar`s verified green; both YAML manifests parse.

### Fixed
- **Local fleet (`run-fleet.ps1`) never started the Nepal corridor** — the launcher
  predates it, so a Nepal QR paid from the GMERemit wallet failed with `HUB_ERROR`.
  Root cause: `payment-executor`'s Nepal adapter URL defaulted to `localhost:18091`,
  which in the fleet is **smart-router** (port collision), and neither
  `scheme-adapter-nepal` nor `sim-nepal-qr` were launched.
  - Added `scheme-adapter-nepal` (18094) + `sim-nepal-qr` (9106) to the fleet.
  - `payment-executor` → `--gmepay.scheme-adapters.NEPAL.base-url=http://localhost:18094`.
  - `scheme-adapter-nepal` → `--gmepay.scheme.nepal.base-url=http://localhost:9106`.
  - `sim-gmeremit` decode → `--gmepay.sim.nepal-qr.base-url=http://localhost:9106`
    (default 9103 collided with `sim-wallet`). Both added to the `money` subset.

## 2026-06-30 — Cloud-agnostic Helm umbrella chart + overlays (agent/cloud-deploy)

### Added
- **Helm umbrella chart `deploy/helm/gmepay/`.** One portable chart deploys the
  whole platform to on-prem / AWS / Azure Kubernetes, differing only by value
  overlay + env. Renders a `Deployment` + `Service` for **18 deployables** (16
  backend services incl. ops-partner-bff + admin-ui + partner-portal-ui) from a
  single `services:` map ranged over by one `_deployment.tpl` helper (DRY — no
  per-service template files). kyb-adapter excluded (no Dockerfile).
  - `Chart.yaml`, `values.yaml` (schema + provider-neutral defaults).
  - `templates/`: `_helpers.tpl`, `_deployment.tpl`, `deployments.yaml`,
    `configmap.yaml` (non-secret ABI), `secret.yaml` (values-supplied,
    `create:false` for prod), `ingress.yaml` (api-gateway + 2 UIs), `NOTES.txt`.
  - Probes: HTTP `/actuator/health` for api-gateway + auth-identity (the only
    actuator-bearing modules); TCP socket probes elsewhere (mirrors compose
    `x-tcp-health`).
- **Portability ABI injected as env** using the EXACT names the services read
  (harvested from `docker-compose.yml` + `application.*`): per-service
  `SPRING_DATASOURCE_*`, `SPRING_DATA_REDIS_*`, `SPRING_KAFKA_BOOTSTRAP_SERVERS`,
  `SPRING_DATA_MONGODB_URI`, `GMEPAY_VAULT_*` (ACCESSKEY/SECRETKEY/REGION/
  PATH_STYLE/ENDPOINT), `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI`
  (alias `OIDC_ISSUER_URI`), `OTEL_EXPORTER_OTLP_ENDPOINT`. Cross-service secret
  aliases (RBAC stamp/verify, internal-auth) surfaced via `envSecretAliases`.
- **Three value overlays (placeholders only):** `values-onprem.yaml`
  (in-cluster / MinIO path-style / Keycloak), `values-aws.yaml` (RDS / MSK /
  ElastiCache / S3 virtual-hosted / ECR / ALB), `values-azure.yaml` (PostgreSQL
  Flexible Server / Event Hubs / Azure Cache / S3-gateway path-style / ACR /
  App Gateway).
- **Docs:** `docs/adr/ADR-015-cloud-agnostic-deployment.md` (principle,
  portability ABI, no-provider-SDK rule, layering, managed-service mapping) and
  `docs/DEPLOYMENT.md` (per-target runbook + mapping table; notes compose remains
  the single-host quickstart).

### Notes
- All endpoints/creds are placeholders. `helm` CLI was unavailable in this
  environment; templates validated by YAML-lint of all value files (18 services
  parsed) and a brace/block-balance pass across all templates.
