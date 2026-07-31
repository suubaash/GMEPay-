# deploy — CHANGELOG

## 2026-07-31 — the chart can scale, and still ships one replica everywhere

The replica-ceiling work made N>1 *correct* (shared rate-limit / replay / idempotency / ops-alert
state, ShedLock on every scheduled job). Before it, N>1 was not a capacity choice but a defect: two
api-gateway replicas enforced 2x the configured rate limit and accepted a captured signed request
once per replica; two transaction-mgmt replicas turned one partner retry into two transactions.
The chart could not express scaling at all, so this adds the mechanism — and nothing else.

### Added
- **`templates/hpa.yaml`** — one `autoscaling/v2` HorizontalPodAutoscaler per service that opts in.
  Per-service `autoscaling.{enabled,minReplicas,maxReplicas,targetCPUUtilizationPercentage,
  targetMemoryUtilizationPercentage,metrics,behavior}`.
- **`gmepay.autoscalingEnabled`** in `_helpers.tpl` — resolves per-service over fleet-wide
  explicitly (`hasKey`, not `default`, so a per-service `false` is honoured against a `true` fleet
  switch). Read by BOTH `hpa.yaml` and `_deployment.tpl` so the two can never disagree.
- **`autoscaling:` block in `values.yaml`** — the master switch (`enabled: false`) plus the
  per-service safety list: which services are safe at N>1 and why, which were **never swept** (so
  the absence of a warning is not approval), and the Kafka caveat that a one-partition topic gains
  nothing from a second replica.
- **`scripts/check_helm_chart_wiring.py` §8b** — fails if the template disappears, if the fail
  guards are removed, if `_deployment.tpl` stops yielding `spec.replicas` to an HPA, if any values
  file enables autoscaling, or if any service ships more than one replica.

### Changed
- **`_deployment.tpl` omits `spec.replicas` when an HPA manages the Deployment.** A hardcoded
  replica count under an HPA fights it on every `helm upgrade`; the visible symptom is an
  autoscaler that "randomly" resets the pod count mid-load.
- **`values.yaml` pins `services.ops-partner-bff.replicas: 1` explicitly** — for this one service
  the 1 is not only a cost decision. Its ops-alert list, alert IDs and operator acks are fleet-wide
  only because they moved into its own `bff` database, and that database is a placeholder in the
  AWS/Azure overlays: it exists in no real environment yet.

### Deliberately not done
- **Nothing is enabled and no number is shipped.** `autoscaling.enabled: false`,
  `global.defaultReplicas: 1`, no service opts in. Enabling autoscaling **without** `maxReplicas`
  or a metric **fails the template** rather than falling back to a plausible-looking `maxReplicas:
  5` / `targetCPUUtilizationPercentage: 70` — same idiom as `ingress-ipn.yaml`'s empty
  `sourceRanges`. How many replicas to run, and what to scale on, are cost judgements that belong
  to the owner.
- No Helm and no Docker were run. Validation is static: PyYAML parse of all four values files, the
  wiring guard, and a Go-template action-balance check.


## 2026-07-28 — kyb-adapter becomes deployable; config-registry's vault points at MinIO (gap T1-4)

### Added
- **`services/kyb-adapter/Dockerfile`** — the exclusion note below ("kyb-adapter
  excluded (no Dockerfile)") is now obsolete. Same multi-stage shape as every other
  service image, including the T5-5 non-root runtime (`USER 10001:10001`, jar
  `root:10001 0640`, no writable path under `/app`).
- **`kyb-adapter` in `docker-compose.yml`** — profiles `core, full`, host port
  **9104** (the `8080..8099` band is fully allocated; `9103/9106/9107` are the
  scheme simulators). `GMEPAY_INTERNAL_AUTH_SECRET` from the shared anchor —
  **required to boot**: `/v1/kyb/screen`, `/v1/kyb/verify` and
  `/v1/kyb/result/**` carry partner UBO / tax-id data and are now behind the
  internal-auth gate, and `KybInternalAuthEnforcedConfig` refuses to start without
  an armed gate. `/v1/kyb/health` stays anonymous for probes.
- **`kyb-adapter` in `deploy/helm/gmepay/values.yaml`** (overlays inherit) with the
  same secret key. Guarded by `scripts/check_internal_auth_wiring.py` (94/94), which
  classifies it BOOT-CRITICAL and asserts it on all four surfaces.

### Fixed
- **`config-registry` now uses the MinIO that compose already runs.**
  `GMEPAY_VAULT_ENDPOINT` was set in the Helm values but in **no** compose service,
  so lib-vault fell back to `InMemoryVaultClient`: every uploaded KYB document
  (business registration, AOA, UBO declaration, Wolfsberg CBDDQ) was held on the
  heap and lost on restart, while its `partner_document` row survived and pointed at
  an object that no longer existed. Compose now sets `GMEPAY_VAULT_ENDPOINT`,
  `GMEPAY_VAULT_ACCESS_KEY`, `GMEPAY_VAULT_SECRET_KEY` (matching the `minio`
  service's own `MINIO_ROOT_*` defaults) and `depends_on: minio`. lib-vault also
  logs one startup WARN whenever the in-memory fallback is chosen, so this state can
  never again be silent. Covered by `ComposeVaultWiringTest`, which parses the real
  compose file and drives the auto-configuration with its values.
- **`config-registry` KYB seam now leaves the JVM**: `GMEPAY_KYB_ADAPTER_CLIENT=rest`
  + `GMEPAY_KYB_ADAPTER_BASE_URL` in compose **and** Helm. It was set nowhere, so
  `StubKybClient` (`matchIfMissing=true`) won in every environment. This changes the
  transport only — see the T1-4 honesty note next.

### Honesty note (do not misread this entry)
Deployable is **not** the same as screening. With `gmepay.kyb.provider=stub` (the
default until ADR-014's Octa Solution sandbox credentials land) kyb-adapter consults
no sanctions / PEP / adverse-media source. Every verdict now carries explicit
provenance, a stub run's clean branch is recorded as `NOT_SCREENED_NO_PROVIDER`
rather than `CLEAR`, a full verification can only reach `MANUAL_REVIEW`, and
config-registry's activation gate **refuses** to activate a partner on it
(`SANCTIONS_NOT_SCREENED`, not overridable by a risk rationale). Local/dev
onboarding needs `gmepay.activation.allow-unscreened-kyb=true`, which must stay
**false** in every deployed values file — it does not make the partner screened, it
records `PARTNER_ACTIVATED_UNSCREENED` in the audit log.

**Still open for kyb-adapter deployment:** it has **no PostgreSQL instance**, so its
own `kyb_screening` run log is in-memory H2 and `GET /v1/kyb/result/{ref}` history
dies with the pod/container. It was deliberately not given one because
`scripts/backup/inventory.env` and its drift guard enumerate exactly the 15
databases that exist; the 16th belongs with that inventory change. The
regulator-defensible record (`partner_kyb`) is durable in config-registry.

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
  per-service template files). kyb-adapter excluded (no Dockerfile). **[SUPERSEDED
  2026-07-28 — kyb-adapter now has a Dockerfile and IS in the chart; see the entry
  at the top of this file.]**
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
