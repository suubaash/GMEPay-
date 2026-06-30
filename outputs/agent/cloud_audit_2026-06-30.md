> 작업: cloud-agnostic portability audit / 출처: agent

# GMEPay+ cloud-agnostic portability audit — 2026-06-30

**Branch:** `cloud/audit` (worktree `wt2/cloud-audit`)
**Goal:** guarantee the 19-service platform deploys on AWS, Azure, or on-prem with no code change — only env/config differs. Open protocols only (JDBC/Redis/Kafka/S3-API/OIDC/OTLP); no cloud-provider SDK on any classpath.

## Build status
- `:libs:lib-vault:compileJava` + `:libs:lib-vault:test` — GREEN
- `:services:api-gateway:compileJava` + `:test` — GREEN
- `:services:config-registry:compileJava` — GREEN
- `./gradlew portabilityGuard` (all 19 services + libs) — GREEN (0 SDK hits)
- Negative test: injecting `software.amazon.awssdk:s3` into lib-vault → guard FAILS with the transitive deps listed; reverted, GREEN again.

## What was provider-coupled vs already-clean

**Already clean (confirmed, no change needed):**
- **No cloud-provider SDK anywhere.** AWS v1/v2, Azure, GCP all absent from every runtime classpath. Object storage uses `io.minio:minio` (an S3-API HTTP client, not an SDK).
- **Datasource / Redis / Kafka / Mongo all env-injected.** Code/YAML carry only dev defaults (H2, localhost) overridden by `SPRING_DATASOURCE_*`, `SPRING_DATA_REDIS_*`, `SPRING_KAFKA_BOOTSTRAP_SERVERS`, `SPRING_DATA_MONGODB_URI`. docker-compose sets the real hosts via env. No hardcoded backing hosts in code.
- **OTLP** endpoint already `${OTEL_EXPORTER_OTLP_ENDPOINT:...}`.

**Was coupled / hardened:**
1. **Vault storage seam (lib-vault).** Region + path-style were not configurable — only endpoint/credentials were. The `MinioClient` was built without a region (S3 Sig-V4 needs one) and with no declared addressing style, so an AWS/Azure-gateway target was not cleanly reachable. Credential env names in docs were `GMEPAY_VAULT_ACCESSKEY/SECRETKEY` (off-contract).
2. **OIDC issuer (api-gateway).** Issuer URI defaulted to a literal Keycloak URL. It was *already* overridable via Spring relaxed binding, but not surfaced under the provider-neutral `OIDC_ISSUER_URI` name the ABI mandates.

## Fixes

**Storage (lib-vault):**
- `VaultProperties` gains `region` (`GMEPAY_VAULT_REGION`, default `us-east-1`) and `pathStyle` (`GMEPAY_VAULT_PATH_STYLE`, default `true`). Doc/env names normalized to `GMEPAY_VAULT_ACCESS_KEY` / `GMEPAY_VAULT_SECRET_KEY`.
- `MinioVaultAutoConfiguration` now applies `.region(...)` always, logs the resolved endpoint/region/style, and asserts the declared `path-style` against MinIO's host-based detection at boot (warns on mismatch). The `io.minio` client has no public path-style setter — it auto-selects path-style for non-AWS hosts and virtual-host for AWS hosts — so the flag is the declared contract validated against that detection. **No provider SDK introduced; MinIO + path-style=true stays the on-prem/compose default.**
- config-registry `application.properties` vault comment updated with the full env contract.

**OIDC (api-gateway):** `issuer-uri` now `${OIDC_ISSUER_URI:http://localhost:8090/realms/gmepay}`; local Keycloak is dev-only default. `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` still works (takes precedence). JWKS pinnable via `..._JWK_SET_URI`. Accepts any OIDC issuer. (auth-identity's `JwtHelper` is its own machine-token issuer — not an OIDC resource server — so not in scope.)

**Guard:** `portabilityGuard` Gradle task on every subproject, wired into `check`. Resolves `runtimeClasspath`/`productionRuntimeClasspath` and fails if any module group starts with `com.amazonaws`, `software.amazon.awssdk`, `com.azure`, or `com.google.cloud` (catches transitive coupling). `io.minio` deliberately allowed (S3-API, not an SDK).

## Finalized portability env ABI
- Postgres: `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD`
- Redis: `SPRING_DATA_REDIS_HOST` / `_PORT` / `_PASSWORD`
- Kafka: `SPRING_KAFKA_BOOTSTRAP_SERVERS`
- Mongo: `SPRING_DATA_MONGODB_URI`
- Object storage (S3-compat): `GMEPAY_VAULT_ENDPOINT`, `GMEPAY_VAULT_REGION`, `GMEPAY_VAULT_ACCESS_KEY`, `GMEPAY_VAULT_SECRET_KEY`, `GMEPAY_VAULT_PATH_STYLE`
- OIDC: `OIDC_ISSUER_URI` (+ optional `..._JWK_SET_URI`)
- Telemetry: `OTEL_EXPORTER_OTLP_ENDPOINT`

## Remaining (≤3)
1. **SSE per-partner keys (ADR-006/R3, pre-existing TODO):** vault objects stored plain until HashiCorp Vault lands; PIPA crypto-shred deferred. Provider-neutral when added (SSE-C/SSE-KMS headers, not an SDK).
2. **compose does not set `GMEPAY_VAULT_ENDPOINT`** for config-registry, so compose still runs the in-memory vault. Intentional (zero-infra dev); flip on per-env. Left as-is to not change the compose flow.
3. **ops-partner-bff still has `DEMO_PASSWORD`** dev login (ADR-011 deferred hardening) — orthogonal to cloud portability, not a Keycloak-URL coupling.
