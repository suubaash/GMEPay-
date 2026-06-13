# platform-infra  (platform)

**Scope:** CI/CD, K8s, IaC, observability, environments, deploy/go-live

**Owned WBS work-packages:** 2.3, 14.1, 14.2, 14.3, 14.4, 14.5, 14.6, 14.7, 14.8, 16.1, 16.4, 16.6  ·  **Tickets:** 352  ·  **Est:** 258.4h

## Service contract (MSA: own DB, API-only communication)

- **Datastore (owned by this service):** n/a
- **APIs / events I EXPOSE:** CI/CD, K8s, IaC, observability, environments for all services
- **APIs / events I CONSUME:** — (platform)
- **Integration rule:** never read another service's database or import its private entities — call its API or consume its event; stub consumed services with WireMock in tests.

> Self-contained backlog for this service. Build it as its own repo/module with its own DB + Flyway migrations, against the `shared-libs` contracts (lib-money / lib-errors / lib-events / lib-api-contracts only). Each ticket has a deliverable + acceptance checks.


## WBS 2.3 — Coding standards, repo, branching, CI scaffold
### 2.3-T01 — Scaffold Gradle monorepo root: settings.gradle, root build.gradle, Gradle wrapper  _(30 min)_
**Context:** GMEPay+ is a Gradle multi-module Java 21 monorepo. The root settings.gradle must declare every service and shared-lib module; the root build.gradle sets the global Java 21 toolchain, Spring Boot 3.x BOM import, and Spotless plugin declaration (not yet configured). All downstream modules inherit from this root. Gradle wrapper pinned to Gradle 8.x.
**Steps:** Create root settings.gradle listing all modules: lib-money, lib-errors, lib-api-contracts, lib-events, lib-persistence, services/auth-identity, services/qr-service, services/smart-router, services/rate-fx, services/payment-executor, services/transaction-mgmt, services/prefunding-balance, services/notification-webhook, services/settlement-recon, services/revenue-accounting, services/reporting-compliance, services/merchant-qr, services/scheme-adapter-zeropay, services/config-registry, services/gateway, services/ops-bff; Create root build.gradle: allprojects { group='com.gme.pay'; version='0.1.0-SNAPSHOT' }; subprojects block importing Spring Boot 3.x BOM via platform() and applying java plugin with languageVersion = JavaLanguageVersion.of(21); Apply the Spotless plugin (version pinned, e.g. 6.25.0) in root build.gradle without configuration yet; Add gradle/wrapper/gradle-wrapper.properties pinned to Gradle 8.x; Verify: ./gradlew projects lists all declared modules with zero resolution errors
**Deliverable:** settings.gradle, build.gradle, gradle/wrapper/gradle-wrapper.properties at repo root
**Acceptance / logic checks:**
- ./gradlew projects outputs all 21 declared module names without errors
- Java toolchain block specifies languageVersion = JavaLanguageVersion.of(21)
- Spring Boot 3.x BOM is imported via platform() so individual Spring deps need no version
- Spotless plugin is declared in root build.gradle (no rules yet, just the plugin application)

### 2.3-T02 — Create build.gradle for each shared library module (lib-*)  _(35 min)_
**Context:** Five shared library modules are used across all services: lib-money (BigDecimal money helpers, JSR-354 Moneta), lib-errors (canonical error model + LoggingContextFilter), lib-api-contracts (OpenAPI-generated DTOs from openapi/partner-api.yaml), lib-events (domain events + Avro/JSON schemas), lib-persistence (common JPA/Flyway utils). Each is a plain java-library (no Spring Boot application plugin). All versions resolved from the root BOM.
**Steps:** Create libs/lib-money/build.gradle: apply java-library; depend on org.javamoney:moneta for JSR-354; no Spring dependency; Create libs/lib-errors/build.gradle: apply java-library; depend on jackson-databind and ch.qos.logback:logstash-logback-encoder:7.x; Create libs/lib-api-contracts/build.gradle: apply java-library; add openapi-generator Gradle plugin configured to generate from openapi/partner-api.yaml into build/generated/src/main/java; Create libs/lib-events/build.gradle: apply java-library; add Avro codegen plugin; jackson-databind; Create libs/lib-persistence/build.gradle: apply java-library; depend on spring-data-jpa, flyway-core, postgresql driver
**Deliverable:** libs/lib-money/build.gradle, libs/lib-errors/build.gradle, libs/lib-api-contracts/build.gradle, libs/lib-events/build.gradle, libs/lib-persistence/build.gradle
**Acceptance / logic checks:**
- ./gradlew :lib-money:build compiles with zero errors
- ./gradlew :lib-api-contracts:generateOpenApiCode produces Java DTOs under build/generated from openapi/partner-api.yaml
- lib-persistence depends on flyway-core and spring-data-jpa but does NOT apply org.springframework.boot application plugin
- No version numbers are hardcoded in any lib build.gradle (all resolved from root BOM)
**Depends on:** 2.3-T01

### 2.3-T03 — Create build.gradle template and Application stub for each Spring Boot service module  _(45 min)_
**Context:** Each of the 15 Spring Boot microservices needs a build.gradle that applies org.springframework.boot and io.spring.dependency-management plugins, declares spring-boot-starter-web, spring-boot-starter-actuator, spring-boot-starter-data-jpa (where DB is used), micrometer-registry-prometheus, and relevant lib-* project dependencies. A shared services/service-conventions.gradle captures common blocks. services/gateway applies spring-cloud-gateway instead of starter-web.
**Steps:** Create services/service-conventions.gradle with: apply plugin org.springframework.boot, apply java, import Spring Boot BOM, declare common dependencies (starter-web, starter-actuator, micrometer-registry-prometheus, lib-errors, lib-api-contracts, JUnit 5, Testcontainers BOM for integrationTestImplementation scope); For each service create services/<name>/build.gradle applying service-conventions.gradle plus service-specific extras (rate-fx adds lib-money, lib-events; gateway applies spring-cloud-gateway; all DB-using services add lib-persistence); Add src/main/java/com/gme/pay/<service>/<Service>Application.java stub with @SpringBootApplication in each module; Verify ./gradlew :services:rate-fx:bootJar produces a non-zero JAR
**Deliverable:** services/service-conventions.gradle; services/rate-fx/build.gradle as reference; @SpringBootApplication stub for each service
**Acceptance / logic checks:**
- ./gradlew :services:rate-fx:bootJar succeeds and produces a non-zero-byte JAR
- All services resolve lib-money, lib-errors, lib-api-contracts, lib-events, lib-persistence with no version conflicts
- services/gateway build.gradle applies spring-cloud-gateway NOT spring-boot-starter-web
- micrometer-registry-prometheus appears in the runtime classpath of every service
**Depends on:** 2.3-T02

### 2.3-T04 — Configure Spotless with Google Java Format and licence header enforcement  _(30 min)_
**Context:** OPS-13 §4.1 stage-1 gate: zero lint errors. Spotless (already declared in 2.3-T01) must enforce: Google Java Format 1.22.0 for all *.java files; trailing-newline and no-trailing-whitespace for *.gradle and *.yaml; a GMEPay+ licence header (from config/licence-header.txt) prepended to every Java source. The spotlessCheck task must fail on any violation to block the CI build stage.
**Steps:** Create config/licence-header.txt containing the one-line copyright header: // Copyright (c) GME Remittance. GMEPay+ Platform. All rights reserved.; In root build.gradle spotless {} block: java { googleJavaFormat('1.22.0'); licenseHeaderFile('config/licence-header.txt') }; add groovyGradle { trimTrailingWhitespace(); endWithNewline() } and yaml { trimTrailingWhitespace(); endWithNewline() }; Run ./gradlew spotlessApply on all existing stubs to auto-fix violations; Run ./gradlew spotlessCheck to confirm exit 0; Verify: removing the licence header from one file causes spotlessCheck to exit non-zero
**Deliverable:** Spotless configuration block in root build.gradle; config/licence-header.txt
**Acceptance / logic checks:**
- ./gradlew spotlessCheck exits 0 after spotlessApply on all initial stubs
- Removing the licence header from any .java file causes ./gradlew spotlessCheck to exit non-zero
- Introducing a non-Google-Java-Format indent causes ./gradlew spotlessCheck to exit non-zero
- A YAML file with trailing spaces fails spotlessCheck
**Depends on:** 2.3-T01

### 2.3-T05 — Define branching strategy: documentation and branch protection rules  _(25 min)_
**Context:** OPS-13 §4.2 mandates trunk-based development. Four branch types: feature/<ticket> (PR to main; dev-only manual deploy), main (always deployable; auto-deploy to int on merge), release/<version> (cut from main; auto-deploy to staging), hotfix/<ticket> (emergency; fast-track to prod via two senior engineer approvals). OPS-13 §4.3: main and release/* require PR approval and passing status checks; direct push to main is blocked.
**Steps:** Create docs/BRANCHING.md documenting the four branch types, naming conventions (feature/2.3-T05, release/1.0.0, hotfix/GME-999), deploy targets, and hotfix fast-track rule (2 senior engineers + on-call approval; post-deployment review mandatory); Configure branch protection on main: require 1 PR approval, require status checks spotlessCheck and unit-test, no direct push, no force-push; Configure branch protection on release/*: same as main plus require linear history; Add a CI step named branch-name-check that rejects any push/PR from a branch not matching feature/*, release/*, hotfix/*, or main using a regex assertion
**Deliverable:** docs/BRANCHING.md; branch protection rules on main and release/*; CI branch-name-check step
**Acceptance / logic checks:**
- Direct push to main is rejected by the remote with protected-branch error
- A PR to main with a failing spotlessCheck status is blocked from merging
- A branch named my-fix (not matching convention) causes the CI branch-name-check step to fail and report the violation
- docs/BRANCHING.md lists all four branch types with deploy targets and the hotfix approval rule
**Depends on:** 2.3-T01

### 2.3-T06 — Create infra/docker-compose.yml: PostgreSQL 16 and MongoDB 7 for local dev  _(30 min)_
**Context:** STACK.md: PostgreSQL 16 for the money path (transactions, prefunding, config, audit) and MongoDB 7 for the merchant/QR store and CQRS projections. docker-compose exposes Postgres on host port 5433 (container 5432) to avoid conflicts; MongoDB on 27017. Named volumes ensure data persists across restarts. Health checks are mandatory so dependent containers wait correctly.
**Steps:** Create infra/docker-compose.yml with postgres service: image postgres:16-alpine, POSTGRES_DB=gmepayplus, POSTGRES_USER=gmepay, POSTGRES_PASSWORD=${POSTGRES_PASSWORD:-localdev}, ports 5433:5432, volume pgdata:/var/lib/postgresql/data, healthcheck: pg_isready -U gmepay -d gmepayplus with interval 5s retries 10; Add mongo service: image mongo:7, ports 27017:27017, volume mongodata:/data/db, healthcheck: mongosh --eval 'db.adminCommand({ping:1})' with interval 5s retries 10; Create infra/.env.example listing POSTGRES_PASSWORD=localdev MONGO_INITDB_ROOT_PASSWORD=localdev; Verify: docker compose up -d postgres mongo; docker compose ps shows both Healthy within 30 seconds
**Deliverable:** infra/docker-compose.yml with postgres and mongo services; infra/.env.example
**Acceptance / logic checks:**
- docker compose up -d exits 0 and both containers reach healthy status within 30 seconds
- psql -h localhost -p 5433 -U gmepay gmepayplus -c 'SELECT 1' succeeds
- mongosh --port 27017 --eval 'db.adminCommand({ping:1})' returns {ok:1}
- Named volumes pgdata and mongodata are declared so data survives docker compose restart

### 2.3-T07 — Add Redis 7, Kafka 3.7 (KRaft), and Schema Registry to docker-compose.yml  _(35 min)_
**Context:** STACK.md: Redis for rate-quote TTL (default 60s), config cache, and idempotency keys. Apache Kafka (KRaft mode, no ZooKeeper) for the integration-phase outbox-to-Kafka transport; Schema Registry for Avro event schemas in lib-events. Phase 1 uses in-process outbox but Kafka must be in docker-compose so developers can wire the integration phase without infra changes. Bitnami Kafka 3.7 image.
**Steps:** Add redis service to infra/docker-compose.yml: image redis:7-alpine, ports 6379:6379, healthcheck: redis-cli ping with interval 5s retries 10; Add kafka service: image bitnami/kafka:3.7, KRaft env vars (KAFKA_CFG_NODE_ID=1, KAFKA_CFG_PROCESS_ROLES=controller+broker, KAFKA_CFG_LISTENERS=PLAINTEXT://:9092 CONTROLLER://:9093, KAFKA_CFG_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092), ports 9092:9092, healthcheck: kafka-topics.sh --bootstrap-server localhost:9092 --list with retries 15; Add schema-registry service: image confluentinc/cp-schema-registry:7.6, SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS=kafka:9092, ports 8081:8081, depends_on kafka; Verify all five services (postgres, mongo, redis, kafka, schema-registry) show healthy in docker compose ps
**Deliverable:** infra/docker-compose.yml updated with redis, kafka, schema-registry services
**Acceptance / logic checks:**
- redis-cli -p 6379 ping returns PONG
- kafka-topics.sh --bootstrap-server localhost:9092 --list exits 0 (no error)
- curl -s http://localhost:8081/subjects returns [] on fresh start
- All five services show healthy in docker compose ps within 90 seconds of docker compose up
**Depends on:** 2.3-T06

### 2.3-T08 — Add HashiCorp Vault 1.16 and MinIO with bucket init to docker-compose.yml  _(35 min)_
**Context:** STACK.md: Vault/KMS for secrets and field-level encryption (all services read partner credentials via Vault). MinIO (S3-compatible) for ZeroPay SFTP file archive (/gmepay/batch/), BOK reports, and tax invoice archives. Vault runs in dev mode (VAULT_DEV_ROOT_TOKEN_ID=localdev) on port 8200. MinIO on ports 9000 (API) and 9001 (console). A minio-init service creates required buckets at startup.
**Steps:** Add vault service: image hashicorp/vault:1.16, VAULT_DEV_ROOT_TOKEN_ID=localdev, ports 8200:8200, cap_add [IPC_LOCK], healthcheck: vault status -address http://localhost:8200 with retries 10; Add minio service: image minio/minio:latest, command: server /data --console-address :9001, MINIO_ROOT_USER=minioadmin, MINIO_ROOT_PASSWORD=minioadmin, ports 9000:9000 9001:9001, healthcheck: curl -f http://localhost:9000/minio/health/live; Add minio-init service (image minio/mc) that runs after minio healthy and executes: mc alias set local http://minio:9000 minioadmin minioadmin; mc mb local/gmepay-batch-files local/gmepay-reports local/gmepay-archives; Update infra/.env.example with VAULT_DEV_ROOT_TOKEN_ID=localdev MINIO_ROOT_PASSWORD=minioadmin and note these are never used outside local dev
**Deliverable:** infra/docker-compose.yml with vault and minio services plus minio-init bucket creator; updated infra/.env.example
**Acceptance / logic checks:**
- vault status -address http://localhost:8200 shows Sealed: false
- curl -s http://localhost:9000/minio/health/live returns HTTP 200
- mc ls local shows three buckets: gmepay-batch-files, gmepay-reports, gmepay-archives after minio-init completes
- infra/.env.example includes a comment: VAULT_DEV_ROOT_TOKEN_ID is for local dev ONLY - never use in non-dev environments
**Depends on:** 2.3-T07

### 2.3-T09 — Write infra/README.md: port map, startup, credentials, and reset procedure  _(20 min)_
**Context:** Developers need a single reference for the local dev stack. All seven services (Postgres, Mongo, Redis, Kafka, Schema Registry, Vault, MinIO) must be covered with ports, health-check one-liners, default credentials, and the reset procedure. OPS-13 §5.3 notes the dev environment uses DEBUG log level, manual rate updates, and Vault in dev mode.
**Steps:** Create infra/README.md with a Prerequisites section (Docker 24+, docker compose v2); Add a port map table: Postgres 5433, Mongo 27017, Redis 6379, Kafka 9092, Schema Registry 8081, Vault 8200, MinIO API 9000, MinIO console 9001; Document startup: cd infra; cp .env.example .env; docker compose up -d; docker compose ps; Document per-service health-check one-liners for copy-paste verification; Add a Vault local-dev-only warning: 'Token localdev is for local development only. Never use in int/staging/prod.'; Add reset procedure: docker compose down -v destroys all volumes; use only to start fresh
**Deliverable:** infra/README.md
**Acceptance / logic checks:**
- README contains a port map table covering all 8 ports across the 7 services
- Startup section shows the docker compose up -d command with --env-file or cp .env.example .env step
- Vault local-dev-only warning is present and prominent
- Reset procedure with docker compose down -v is documented with a warning about data loss
**Depends on:** 2.3-T08

### 2.3-T10 — Create Flyway baseline migration V001__create_schema_baseline.sql in lib-persistence  _(35 min)_
**Context:** OPS-13 §4.6: all schema changes use versioned Flyway migrations in db/migrations/, applied automatically before the new app version starts, backward-compatible and additive-only. lib-persistence holds the shared Flyway configuration and migration directory. The baseline creates the gmepay schema namespace and the audit_log table (used by all services to record actor, event_type, entity_id, previous_value, new_value per the audit mandate).
**Steps:** Create libs/lib-persistence/src/main/resources/db/migration/V001__create_schema_baseline.sql: CREATE SCHEMA IF NOT EXISTS gmepay; CREATE TABLE gmepay.audit_log (id UUID PRIMARY KEY DEFAULT gen_random_uuid(), actor VARCHAR(255) NOT NULL, event_type VARCHAR(100) NOT NULL, entity_type VARCHAR(100), entity_id VARCHAR(255), previous_value JSONB, new_value JSONB, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()); Create libs/lib-persistence/src/main/java/com/gme/pay/persistence/FlywayConfig.java: @Configuration class setting spring.flyway.locations=classpath:db/migration, baseline-on-migrate=true, table=flyway_schema_history; Add a META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports entry for FlywayConfig so any service including lib-persistence auto-runs migrations; Document the naming rule: migrations must use V{NNN}__ prefix and be additive-only; never rename or drop a column in the same release as the referencing code change
**Deliverable:** libs/lib-persistence/src/main/resources/db/migration/V001__create_schema_baseline.sql; libs/lib-persistence/src/main/java/com/gme/pay/persistence/FlywayConfig.java
**Acceptance / logic checks:**
- After Flyway runs against local Postgres, SELECT * FROM gmepay.audit_log returns 0 rows (table exists, empty)
- flyway_schema_history table contains exactly one row for V001 with success=true
- INSERT INTO gmepay.audit_log(actor,...) with actor=NULL raises NOT NULL constraint violation
- Running Flyway migrate twice on the same DB produces no error (idempotent)
**Depends on:** 2.3-T06

### 2.3-T11 — Configure Spring Boot application.yml template and profile strategy for all services  _(30 min)_
**Context:** OPS-13 §4.7 and §5.3: runtime config injected via env vars (no secrets in images). Each service needs a base application.yml (default profile) and environment overrides for dev and prod. Common settings: spring.datasource (Postgres on localhost:5433 for dev), spring.data.mongodb, spring.data.redis, spring.flyway.locations, management.endpoints. Secrets referenced as ${ENV_VAR} placeholders in prod profile.
**Steps:** Create services/service-conventions/src/main/resources/application.yml: server.port=8080; management.endpoints.web.exposure.include=health,info,prometheus; management.tracing.sampling.probability=1.0; spring.flyway.locations=classpath:db/migration; spring.application.name=${SERVICE_NAME:unnamed}; Create application-dev.yml: logging.level.root=DEBUG; spring.datasource.url=jdbc:postgresql://localhost:5433/gmepayplus; spring.datasource.username=gmepay; spring.datasource.password=localdev; spring.data.mongodb.uri=mongodb://localhost:27017/gmepayplus; spring.data.redis.host=localhost; spring.data.redis.port=6379; Create application-prod.yml: all values as ${GMEPAY_DB_URL}, ${GMEPAY_DB_PASSWORD}, ${GMEPAY_MONGO_URI}, ${GMEPAY_REDIS_HOST} (zero literal secrets); Each service's own application.yml sets spring.application.name to the service slug (e.g. rate-fx)
**Deliverable:** services/service-conventions/src/main/resources/application.yml; application-dev.yml; application-prod.yml
**Acceptance / logic checks:**
- Running any service with --spring.profiles.active=dev picks up spring.datasource.url=jdbc:postgresql://localhost:5433/gmepayplus
- application-prod.yml contains zero literal credential values (all ${...} placeholders)
- management.endpoints.web.exposure.include contains prometheus in all profiles
- spring.flyway.locations is set to classpath:db/migration in the base application.yml
**Depends on:** 2.3-T03

### 2.3-T12 — Create CI pipeline: stage 1 (build + Spotless lint) and stage 2 (unit tests, Jacoco 80%)  _(40 min)_
**Context:** OPS-13 §4.1 stage-1 gate: zero lint errors. Stage-2 gate: all tests pass, coverage >= 80%. Pipeline targets GitHub Actions (or equivalent CI). Java 21 (Temurin) from actions/setup-java. Gradle build cache reduces repeat compile times. JUnit XML reports uploaded as artifacts for test result visibility.
**Steps:** Create .github/workflows/ci.yml with trigger on push to feature/*, main, release/*, hotfix/*; Add job build-and-lint: actions/setup-java@v4 (java-version: 21, distribution: temurin); cache ~/.gradle/caches and ~/.gradle/wrapper; run ./gradlew spotlessCheck compileJava --build-cache; Add job unit-test: depends on build-and-lint; run ./gradlew test jacocoTestReport jacocoTestCoverageVerification; configure jacocoTestCoverageVerification with minimum 0.80 per module; upload JUnit XML reports as artifacts; Add job branch-name-check: runs on push; validates branch name against regex ^(feature|release|hotfix)/.+$|^main$ and fails with a descriptive message if not matching
**Deliverable:** .github/workflows/ci.yml with build-and-lint, unit-test, and branch-name-check jobs
**Acceptance / logic checks:**
- A PR introducing a Spotless violation causes build-and-lint to fail before unit-test runs
- A module with coverage below 80% causes jacocoTestCoverageVerification to fail the unit-test job
- A branch named my-fix causes branch-name-check to fail with a message listing valid patterns
- JUnit XML artifacts are uploaded and visible in the CI run summary
**Depends on:** 2.3-T04, 2.3-T03

### 2.3-T13 — Add CI stage 3: SAST (SpotBugs + find-sec-bugs), OWASP CVE scan, and Gitleaks secrets scan  _(40 min)_
**Context:** OPS-13 §4.1 stage-3 gate: no critical/high CVEs; no secrets detected. Three tools: SpotBugs with com.h3xstream.findsecbugs:findsecbugs-plugin:1.12.0 for SAST (fail on MEDIUM+ security bugs), OWASP Dependency-Check Gradle plugin (failBuildOnCVSS=7.0), and Gitleaks for secrets-leak detection. All three must pass before the artifact-build stage.
**Steps:** Add spotbugs Gradle plugin to root build.gradle with find-sec-bugs plugin at version 1.12.0; configure spotbugsMain { effort = 'max'; reportLevel = 'medium' }; set ignoreFailures = false; Add org.owasp.dependencycheck Gradle plugin; set dependencyCheck { failBuildOnCVSS = 7.0; format = 'HTML'; outputDirectory = 'build/reports/dependency-check' }; Add security-scan job to .github/workflows/ci.yml depending on unit-test; steps: (1) download gitleaks binary; (2) run gitleaks detect --source . --no-git --exit-code 1; (3) ./gradlew spotbugsMain; (4) ./gradlew dependencyCheckAnalyze; upload OWASP HTML report as artifact; Test: introduce a dummy AWS_SECRET_ACCESS_KEY = 'AKIAIOSFODNN7EXAMPLE' in a temp file; confirm gitleaks step fails
**Deliverable:** .github/workflows/ci.yml security-scan job; SpotBugs and OWASP Dependency-Check plugin config in root build.gradle
**Acceptance / logic checks:**
- Committing a dummy AWS access key pattern causes the gitleaks step to fail and block the pipeline
- A dependency with CVE CVSS >= 7.0 causes ./gradlew dependencyCheckAnalyze to exit non-zero
- SpotBugs with find-sec-bugs flags a simulated SQL injection (string concatenation in a JDBC query) as MEDIUM and fails the build
- OWASP HTML report artifact is downloadable from the security-scan CI run
**Depends on:** 2.3-T12

### 2.3-T14 — Add CI stage 4: integration test job using Testcontainers (Postgres, Mongo, Redis, Kafka)  _(45 min)_
**Context:** OPS-13 §4.1 stage 4: ephemeral env with DB + dependencies; all integration tests pass. Testcontainers starts PostgreSQL 16, MongoDB 7, Redis 7, and Kafka 3.7 containers inside the CI runner. Tests live in a separate integrationTest Gradle source set (src/integration-test/java). An AbstractIntegrationTest base class wires Testcontainers @DynamicPropertySource into Spring context.
**Steps:** Add integrationTest source set to service-conventions.gradle: sourceSets { integrationTest { java.srcDir 'src/integration-test/java'; compileClasspath += main.output; runtimeClasspath += main.output } }; task integrationTest(type: Test) { useJUnitPlatform() }; Add Testcontainers BOM org.testcontainers:testcontainers-bom:1.19.x to root build.gradle for integrationTestImplementation scope; add testcontainers, postgresql, mongodb, kafka sub-artifacts; Create libs/lib-persistence/src/integration-test/java/com/gme/pay/persistence/AbstractIntegrationTest.java: @Testcontainers class starting PostgreSQLContainer postgres:16-alpine and MongoDBContainer mongo:7; @DynamicPropertySource injects spring.datasource.url and spring.data.mongodb.uri; Add integration-test job to .github/workflows/ci.yml: depends on security-scan; runs ./gradlew integrationTest; requires Docker socket (uses: docker/setup-buildx-action or service container); uploads test reports
**Deliverable:** integrationTest source set in service-conventions.gradle; AbstractIntegrationTest.java; CI integration-test job
**Acceptance / logic checks:**
- ./gradlew :lib-persistence:integrationTest starts a real postgres:16-alpine container via Testcontainers, runs Flyway V001 migration against it, and the test passes
- Integration test job in CI completes without any manually installed Postgres or Mongo on the runner
- AbstractIntegrationTest uses @DynamicPropertySource to inject the Testcontainers JDBC URL into spring.datasource.url
- A failing integration test blocks the artifact-build stage
**Depends on:** 2.3-T13, 2.3-T10

### 2.3-T15 — Add CI stage 5: multi-stage Docker image build, tag, sign, and push to artifact registry  _(45 min)_
**Context:** OPS-13 §4.1 stage 5: build container image tagged <git-sha>+<branch>+<semver>, push to artifact registry, sign image. Multi-stage Dockerfile: stage 1 eclipse-temurin:21-jdk-alpine runs ./gradlew bootJar; stage 2 eclipse-temurin:21-jre-alpine copies the JAR. Non-root user, cosign keyless signing. Registry: ghcr.io/gme-pay/<service>:<tag>.
**Steps:** Create infra/Dockerfile.service as a multi-stage template: FROM eclipse-temurin:21-jdk-alpine AS build; COPY . .; RUN ./gradlew :services:${SERVICE}:bootJar -x test; FROM eclipse-temurin:21-jre-alpine; RUN addgroup -S app && adduser -S app -G app; COPY --from=build services/${SERVICE}/build/libs/*.jar app.jar; USER app; ENTRYPOINT [java, -jar, app.jar]; Add artifact-build job to .github/workflows/ci.yml: depends on integration-test; matrix over all service names; uses docker/build-push-action with --build-arg SERVICE=<name>; tags: ghcr.io/gme-pay/$SERVICE:$GIT_SHA, ghcr.io/gme-pay/$SERVICE:$BRANCH-latest (and :<semver> if on release/*); Add cosign signing step after push: cosign sign --yes ghcr.io/gme-pay/$SERVICE@$DIGEST (uses OIDC keyless signing); Verify: docker run --rm ghcr.io/gme-pay/rate-fx:$GIT_SHA java -version outputs Java 21
**Deliverable:** infra/Dockerfile.service; .github/workflows/ci.yml artifact-build job with matrix and cosign signing
**Acceptance / logic checks:**
- Final image base is eclipse-temurin:21-jre-alpine (not JDK) reducing image size
- Image tagged with the git SHA is pushed to ghcr.io for every commit to main
- cosign verify ghcr.io/gme-pay/rate-fx@$DIGEST succeeds using the OIDC-issued certificate
- Container runs as non-root user (USER app is set in Dockerfile stage 2)
**Depends on:** 2.3-T14

### 2.3-T16 — Add CI stage 6: automated deploy to int environment via Argo CD and smoke tests  _(40 min)_
**Context:** OPS-13 §4.1 stage 6: automated deploy to int on merge to main; gate is smoke tests passing. OPS-13 §4.2: main -> int is fully automated, no human gate. Deployment via Argo CD GitOps: CI updates image tag in helm/values-int.yaml; Argo CD syncs. Smoke tests hit /actuator/health on each service via the int ingress. Gate fails if any service returns non-200.
**Steps:** Create helm/values-int.yaml with image.tag: __IMAGE_TAG__ placeholder per service; Add deploy-to-int job to .github/workflows/ci.yml: triggered only on push to main; depends on artifact-build; uses yq to substitute __IMAGE_TAG__ with the git SHA in helm/values-int.yaml; commits and pushes the updated file to trigger Argo CD sync; Add smoke-test step: wait for argocd app wait gmepay-int --health --timeout 300; then curl --fail http://<service>.int.gme.internal/actuator/health for each service; Gate: job fails if any /actuator/health returns non-200 within 60 seconds of Argo CD sync
**Deliverable:** helm/values-int.yaml; CI deploy-to-int job with Argo CD sync and smoke-test gate
**Acceptance / logic checks:**
- Merging a feature branch to main triggers deploy-to-int automatically without manual approval
- Argo CD sync updates the running image in the gmepay-int namespace to the new git SHA within 5 minutes
- Smoke test step returns HTTP 200 from /actuator/health for rate-fx and auth-identity services
- A service that fails to start causes /actuator/health to return non-200 and fails the deploy-to-int job
**Depends on:** 2.3-T15

### 2.3-T17 — Add CI stage 7: staging deploy on release/* with manual approval gate  _(35 min)_
**Context:** OPS-13 §4.1 stage 7: automated deploy to staging on release/* cut; UAT tests run; manual approval gate required before prod. OPS-13 §4.3: staging requires stakeholder sign-off. The GitHub Actions environment named staging has required reviewers configured, so the deploy job pauses until approved.
**Steps:** Create helm/values-staging.yaml with the same structure as values-int.yaml; Add deploy-to-staging job to .github/workflows/ci.yml: triggered only on push to release/*; environment: staging (which has required-reviewers protection); depends on artifact-build; updates helm/values-staging.yaml with the git SHA via yq; commits and pushes; Add Argo CD sync wait for gmepay-staging app after the approval gate passes; Add a run-smoke-tests step hitting /actuator/health on the staging ingress for each service after Argo CD sync; Configure the staging GitHub Actions environment with at least one required reviewer (Release Manager role)
**Deliverable:** helm/values-staging.yaml; CI deploy-to-staging job with environment-based approval gate
**Acceptance / logic checks:**
- Cutting a release/1.0.0 branch triggers the deploy-to-staging job
- The job pauses at the approval gate and does not update helm/values-staging.yaml until a reviewer approves
- Rejecting the approval gate leaves helm/values-staging.yaml unchanged and does not trigger Argo CD sync
- Smoke tests run after successful Argo CD sync and fail the job if any service is unhealthy
**Depends on:** 2.3-T16

### 2.3-T18 — Implement automated rollback on failed smoke tests after deployment  _(35 min)_
**Context:** OPS-13 §4.4: if post-deployment smoke tests fail within 5 minutes, the pipeline automatically redeploys the previous image tag. Rollback reads the prior deployed SHA from Argo CD app history. DB migrations are forward-only (per §4.6); rollback only affects the application image. A Slack/Teams notification is sent on auto-rollback.
**Steps:** In deploy-to-int and deploy-to-staging CI jobs, capture the previous image SHA before applying the new values file: run argocd app history gmepay-int --output json | jq -r '.[0].revision' and store as PREV_SHA; Add an on-failure composite step after the smoke-test step: (1) restore helm/values-<env>.yaml to PREV_SHA using yq; (2) commit and push the reverted file; (3) wait for Argo CD re-sync; (4) send a notification via webhook with environment name, new-SHA, previous-SHA, and failure reason; Document in code comments that the rollback does NOT revert DB migrations; if the old code is incompatible with the migrated schema, a hotfix is required (OPS-13 §4.4 procedure); Add a skip condition: if PREV_SHA is empty (first deploy to this environment), log a warning and skip the rollback step
**Deliverable:** Rollback on-failure step in deploy-to-int and deploy-to-staging jobs; rollback notification webhook call
**Acceptance / logic checks:**
- A deliberate /actuator/health failure triggers the on-failure rollback step and reverts the values file to PREV_SHA
- Argo CD re-syncs to the previous image after the reverted values file is pushed
- Slack/Teams notification message includes both old and new SHA
- If no previous deployment exists (PREV_SHA is empty), the rollback step skips with a warning rather than failing
**Depends on:** 2.3-T17

### 2.3-T19 — Create Helm chart skeleton for GMEPay+ services with Deployment, HPA, and Ingress templates  _(40 min)_
**Context:** STACK.md: Kubernetes deployment via GitOps with Argo CD, Helm charts. A shared helm/charts/gmepay-service/ chart provides common Deployment, Service, HPA, and Ingress templates reused by all 15 services via per-service values files. Liveness probe: /actuator/health/liveness; readiness: /actuator/health/readiness. HPA: minReplicas=2, maxReplicas=10, target CPU 70%. runAsNonRoot: true in all pods.
**Steps:** Create helm/charts/gmepay-service/Chart.yaml: apiVersion v2, name gmepay-service, version 0.1.0; Create templates/deployment.yaml: image from values.image.repository:values.image.tag; env from ConfigMap + secretKeyRef; securityContext.runAsNonRoot: true; livenessProbe.httpGet.path=/actuator/health/liveness; readinessProbe.httpGet.path=/actuator/health/readiness; Create templates/hpa.yaml: minReplicas: 2, maxReplicas: 10, targetCPUUtilizationPercentage: 70; Create templates/service.yaml and templates/ingress.yaml with standard annotations; Create helm/values-base.yaml with defaults; add per-service stub values files under helm/values-<service>-int.yaml; Run helm lint helm/charts/gmepay-service/ and confirm exit 0
**Deliverable:** helm/charts/gmepay-service/ chart directory; helm/values-base.yaml
**Acceptance / logic checks:**
- helm lint helm/charts/gmepay-service/ exits 0 with no warnings
- helm template output includes a Deployment with securityContext.runAsNonRoot: true
- HPA resource has minReplicas: 2 and maxReplicas: 10
- Liveness probe path is /actuator/health/liveness and readiness probe path is /actuator/health/readiness
**Depends on:** 2.3-T01

### 2.3-T20 — Create Argo CD Application manifests for int, staging, and prod environments  _(30 min)_
**Context:** STACK.md and OPS-13: GitOps via Argo CD. Each environment (int, staging, prod) needs an Argo CD Application CRD. int: automated sync with prune and selfHeal. staging: manual sync (requires approval). prod: fully manual. All three point to the same Helm chart and repo, differing only in values files and destination namespace.
**Steps:** Create deploy/argocd/gmepay-int.yaml: Application spec.source.repoURL=<monorepo>, spec.source.helm.valueFiles=['helm/values-base.yaml','helm/values-int.yaml'], destination.namespace=gmepay-int, syncPolicy.automated: {prune: true, selfHeal: true}; Create deploy/argocd/gmepay-staging.yaml: same structure for gmepay-staging namespace; syncPolicy: {} (no automated); Create deploy/argocd/gmepay-prod.yaml: gmepay-prod namespace; syncPolicy: {} (no automated); add ignoreDifferences for image.tag so accidental drift does not auto-apply; Document: kubectl apply -f deploy/argocd/ on the Argo CD management cluster registers all three environments
**Deliverable:** deploy/argocd/gmepay-int.yaml; deploy/argocd/gmepay-staging.yaml; deploy/argocd/gmepay-prod.yaml
**Acceptance / logic checks:**
- gmepay-int Application has syncPolicy.automated.prune: true and selfHeal: true
- gmepay-staging Application has no syncPolicy.automated block (manual trigger only)
- gmepay-prod Application has no syncPolicy.automated block and adds ignoreDifferences for image.tag
- All three Application manifests reference the same spec.source.repoURL and chart path
**Depends on:** 2.3-T19

### 2.3-T21 — Add OpenTelemetry auto-instrumentation and Prometheus actuator endpoint to all services  _(35 min)_
**Context:** STACK.md observability: OpenTelemetry -> Prometheus+Grafana (metrics), ELK (logs), Jaeger (tracing). OPS-13 §7.3: trace_id generated at the load balancer, propagated through every service call and batch job step. Each service exports OTLP spans and exposes /actuator/prometheus. The OTel exporter endpoint defaults to localhost:4318 for local dev.
**Steps:** Add to service-conventions.gradle: implementation 'io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter:2.x' and implementation 'io.micrometer:micrometer-tracing-bridge-otel'; Add to application.yml: management.tracing.sampling.probability=1.0; management.otlp.tracing.endpoint=${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318/v1/traces}; management.endpoints.web.exposure.include: health,info,prometheus,metrics; Create libs/lib-errors/src/main/java/com/gme/pay/errors/LoggingContextFilter.java: @Component OncePerRequestFilter that reads the W3C traceparent header, extracts trace_id and span_id, puts them in MDC, puts X-Partner-Id into MDC as partner_id, and calls MDC.clear() in finally; Verify GET /actuator/prometheus returns Prometheus-formatted metrics
**Deliverable:** OTel and Micrometer dependencies in service-conventions.gradle; LoggingContextFilter.java in lib-errors; updated application.yml
**Acceptance / logic checks:**
- GET /actuator/prometheus returns text/plain Prometheus metrics including http_server_requests_seconds_count
- A request with a valid W3C traceparent header results in trace_id appearing in MDC and JSON log output
- otel.service.name is set equal to spring.application.name in application.yml
- OTEL_EXPORTER_OTLP_ENDPOINT defaults to http://localhost:4318/v1/traces so local dev works without a collector
**Depends on:** 2.3-T11

### 2.3-T22 — Configure structured JSON logging via logstash-logback-encoder with all OPS-13 required fields  _(35 min)_
**Context:** OPS-13 §7.2: all services emit JSON-structured logs with mandatory fields: timestamp (ISO-8601 UTC), level, service, env, trace_id, span_id, partner_id (when present), transaction_id (when present), event, message. Use Logback with ch.qos.logback:logstash-logback-encoder:7.x. In prod profile, sensitive fields (collection_amount, prefund_balance, rate components) must NOT appear in log output; only IDs are logged.
**Steps:** Add ch.qos.logback:logstash-logback-encoder:7.x to service-conventions.gradle runtimeClasspath; Create libs/lib-errors/src/main/resources/logback-spring.xml: STDOUT appender using LogstashEncoder with customFields for service=${spring.application.name} and env=${spring.profiles.active}; root level DEBUG in dev, INFO in prod; LoggingContextFilter (from 2.3-T21) also sets MDC keys transaction_id (from X-Transaction-Id header if present) and clears all MDC keys in a finally block; Create a prod-specific logback-spring-prod.xml that removes any appender encoder fields matching collection_amount, prefund_balance, cost_rate_coll, cost_rate_pay to prevent sensitive data leakage
**Deliverable:** logback-spring.xml in lib-errors resources; prod-specific appender config; LoggingContextFilter updated with transaction_id MDC
**Acceptance / logic checks:**
- A log line from any service running locally is valid JSON containing all 8 required fields: timestamp, level, service, env, trace_id, span_id, event, message
- trace_id in the log matches the trace_id from the W3C traceparent request header
- Running with prod profile: the string collection_amount does not appear in any log line
- MDC is cleared after each request so partner_id from one request does not leak into a subsequent request's log entries
**Depends on:** 2.3-T21

### 2.3-T23 — Create Prometheus alert rules YAML for API SLO, prefunding, and pool integrity alerts  _(35 min)_
**Context:** OPS-13 §7.5: alert catalog defines P1-P4 alert rules. Implement as infra/prometheus/alert-rules.yaml. Key rules: API p95 latency >500ms for 5m (severity warning); 5xx error rate >1% for 5m (severity critical); prefunding balance <10000 USD (warning); <2000 USD (critical); pool identity assertion failure (critical, any increase in gmepay_pool_identity_violations_total). Validate with promtool.
**Steps:** Create infra/prometheus/alert-rules.yaml with three groups: api-slo, prefunding, financial-integrity; api-slo group: ApiP95LatencyBreach expr histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[5m])) > 0.5 for 5m severity warning; ApiErrorRateHigh expr rate(http_server_requests_seconds_count{status=~'5..'}[5m]) / rate(http_server_requests_seconds_count[5m]) > 0.01 for 5m severity critical; prefunding group: LowPrefundingBalance expr gmepay_prefunding_balance_usd < 10000 severity warning; CriticalPrefundingBalance expr gmepay_prefunding_balance_usd < 2000 severity critical; labels include partner_id; financial-integrity group: PoolIdentityBreach expr increase(gmepay_pool_identity_violations_total[5m]) > 0 for 0m (instant) severity critical; Run promtool check rules infra/prometheus/alert-rules.yaml and confirm exit 0
**Deliverable:** infra/prometheus/alert-rules.yaml with api-slo, prefunding, financial-integrity alert groups
**Acceptance / logic checks:**
- promtool check rules infra/prometheus/alert-rules.yaml exits 0 with no errors
- ApiErrorRateHigh has severity: critical and for: 5m
- CriticalPrefundingBalance threshold is 2000 matching OPS-13 §7.5.2
- PoolIdentityBreach fires on any increase in gmepay_pool_identity_violations_total (no delay, for: 0m)
**Depends on:** 2.3-T21

### 2.3-T24 — Write Gradle TestKit tests for CI pipeline rules: Spotless, Jacoco threshold, branch-name check  _(40 min)_
**Context:** The CI build logic itself needs test coverage. Gradle TestKit tests in buildSrc assert: spotlessCheck fails deterministically on a malformatted Java file; jacocoTestCoverageVerification fails when coverage drops below 80%; the branch-name regex correctly accepts and rejects inputs. These tests live in buildSrc/src/test/java/com/gme/pay/build/ and run as part of ./gradlew :buildSrc:test.
**Steps:** Create buildSrc/src/test/java/com/gme/pay/build/SpotlessCheckTest.java: uses GradleRunner to run spotlessCheck on a temp project containing a .java file missing the licence header; assert BuildResult outcome is FAILED; Create buildSrc/src/test/java/com/gme/pay/build/JacocoCoverageTest.java: uses GradleRunner on a project with a class at 0% coverage; configure 80% minimum threshold; assert jacocoTestCoverageVerification is FAILED; Create buildSrc/src/test/java/com/gme/pay/build/BranchNameValidationTest.java: unit-test the branch-name regex (^(feature|release|hotfix)/.+$|^main$) with @ParameterizedTest vectors: feature/2.3-T01 -> accept; release/1.0.0 -> accept; main -> accept; my-fix -> reject; FEATURE/abc -> reject; Run ./gradlew :buildSrc:test to confirm all three test classes pass
**Deliverable:** buildSrc/src/test/java/com/gme/pay/build/ with SpotlessCheckTest, JacocoCoverageTest, BranchNameValidationTest
**Acceptance / logic checks:**
- SpotlessCheckTest GradleRunner returns FAILED outcome when the .java file is missing the licence header
- JacocoCoverageTest confirms 80% threshold blocks a 0-coverage project (FAILED outcome)
- BranchNameValidationTest: my-fix is rejected; feature/2.3-T24-build-tests is accepted; main is accepted; FEATURE/foo is rejected
- ./gradlew :buildSrc:test exits 0 (all three test classes pass)
**Depends on:** 2.3-T04, 2.3-T12

### 2.3-T25 — Write integration test verifying Flyway V001 migration against Testcontainers Postgres 16  _(35 min)_
**Context:** OPS-13 §4.6 and STACK.md: all schema changes via Flyway, applied automatically before app start, additive-only. The integration test in lib-persistence verifies V001 applies cleanly to a fresh postgres:16-alpine container, that flyway_schema_history records V001, and that the audit_log table enforces the NOT NULL constraint on actor. This test runs in the integrationTest Gradle source set.
**Steps:** Create libs/lib-persistence/src/integration-test/java/com/gme/pay/persistence/FlywayMigrationIT.java annotated @Testcontainers; Declare @Container PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse('postgres:16-alpine')); Configure a DataSource from postgres.getJdbcUrl() and run Flyway.configure().dataSource(...).load().migrate(); Assert: MigrationInfoService shows 1 applied migration (V001); SELECT COUNT(*) FROM gmepay.audit_log returns 0 (table exists, empty); INSERT with all fields succeeds; INSERT with actor=null raises DataIntegrityViolationException; Verify ./gradlew :lib-persistence:integrationTest passes with no locally installed Postgres
**Deliverable:** libs/lib-persistence/src/integration-test/java/com/gme/pay/persistence/FlywayMigrationIT.java
**Acceptance / logic checks:**
- Test passes using Testcontainers postgres:16-alpine with no local Postgres required
- flyway_schema_history table contains exactly one row for V001 with success=true
- INSERT INTO gmepay.audit_log with actor=null raises a constraint violation caught as DataIntegrityViolationException
- Running the migration twice on the same container raises no error (idempotent)
**Depends on:** 2.3-T14, 2.3-T10

### 2.3-T26 — Write local stack smoke integration test for Postgres, Mongo, Redis, and Kafka connectivity  _(35 min)_
**Context:** Developers need confidence that the docker-compose local stack is reachable from a Spring Boot test. This test uses @SpringBootTest(webEnvironment=NONE) with the dev profile and asserts DataSource (Postgres on port 5433), MongoTemplate (port 27017), StringRedisTemplate (port 6379), and KafkaTemplate (port 9092) all connect and respond successfully. Test is skipped when SKIP_LOCAL_STACK_TESTS=true.
**Steps:** Create services/integration-smoke-test/src/integration-test/java/com/gme/pay/smoke/LocalStackSmokeIT.java; Annotate @SpringBootTest(webEnvironment=NONE) @ActiveProfiles('dev'); inject DataSource, MongoTemplate, StringRedisTemplate, KafkaTemplate<String,String>; Test methods: assertPostgresConnects (jdbcTemplate.queryForObject('SELECT 1', Integer.class) == 1); assertMongoConnects (mongoTemplate.getDb().runCommand(new Document('ping',1)).get('ok')==1.0); assertRedisConnects (redisTemplate.opsForValue().set('smoke','ok'); then get returns 'ok'); assertKafkaConnects (kafkaTemplate.send('smoke-test','ping').get(5, SECONDS) does not throw); Annotate class @DisabledIfEnvironmentVariable(named='SKIP_LOCAL_STACK_TESTS', matches='true')
**Deliverable:** services/integration-smoke-test/src/integration-test/java/com/gme/pay/smoke/LocalStackSmokeIT.java
**Acceptance / logic checks:**
- With docker-compose stack running, all four assertions pass (Postgres SELECT 1, Mongo ping, Redis set/get, Kafka send)
- With SKIP_LOCAL_STACK_TESTS=true the entire test class is skipped rather than failed
- Test uses dev profile so spring.datasource.url resolves to jdbc:postgresql://localhost:5433/gmepayplus
- KafkaTemplate assertion uses .get(5, SECONDS) (blocking with timeout) to avoid false positives from async completion
**Depends on:** 2.3-T11, 2.3-T08

### 2.3-T27 — Create CONTRIBUTING.md: coding standards, branch workflow, local dev setup, no-code-for-config rule  _(20 min)_
**Context:** OPS-13 §5.1 generic-build mandate: adding a scheme, partner, or rule requires only a DB config entry - zero code change, zero redeployment. STACK.md and Spotless/Flyway rules must be captured for developer onboarding. CONTRIBUTING.md is the single authoritative onboarding reference for every developer joining the project.
**Steps:** Create CONTRIBUTING.md at repo root with sections: Prerequisites (Java 21 JDK, Docker 24+, docker compose v2; no local Postgres or Mongo required), Local Dev Setup (cd infra; cp .env.example .env; docker compose up -d; cd ..; ./gradlew :services:rate-fx:bootRun --args='--spring.profiles.active=dev'), Branch Naming (table with feature/, release/, hotfix/, main types and deploy targets); PR Checklist section: ./gradlew spotlessCheck must exit 0; ./gradlew test must pass all modules; if schema changes, a new V{NNN}__*.sql migration must be present and additive-only; no hardcoded scheme, partner, margin, or routing values in application code; No-code-for-config rule (verbatim): Adding a QR Scheme, Partner, margin rule, service charge, or routing rule requires only a DB config entry via the Admin System. It must never require a code change or redeployment. A deployment is required only for: bug fixes, new API capabilities, new batch file types, or infrastructure changes.; Module map table listing all modules (lib-* and services/*), their language, persistence layer, and one-line purpose; link to infra/README.md for port map
**Deliverable:** CONTRIBUTING.md at repo root
**Acceptance / logic checks:**
- CONTRIBUTING.md has a Prerequisites section listing Java 21 and Docker 24+ (no local Postgres required)
- PR Checklist includes ./gradlew spotlessCheck and the Flyway additive-only migration rule
- No-code-for-config rule is stated verbatim and references Admin System as the change mechanism
- Module map table lists all 21 modules with their persistence layer (PostgreSQL / MongoDB / Redis / none)
**Depends on:** 2.3-T09, 2.3-T04, 2.3-T05


## WBS 14.1 — Environment provisioning
### 14.1-T01 — Define environment inventory and naming conventions in IaC repo  _(30 min)_
**Context:** GMEPay+ requires four canonical environment tiers: dev (E1), int (E2), staging (E3), prod (E4). Short names dev/int/staging/prod are the canonical labels used in CI/CD pipeline variables, DNS labels, and secrets-manager paths throughout the system. Per OPS-13 §2.1 and §4.7, secret paths follow gmepay/<env>/<service>/<secret-name>. Before any infrastructure is provisioned, the IaC repository structure and naming contract must be established so all subsequent tickets build consistently.
**Steps:** Create an IaC repository (or top-level directory infra/) with subdirectories: environments/dev, environments/int, environments/staging, environments/prod.; Add a README or CONVENTIONS.md that lists the four canonical tier names, their short codes E1-E4, purposes, and the secret path pattern gmepay/<env>/<service>/<secret-name>.; Add a shared variables file (e.g., variables.tf or vars.yaml) that defines env_name as a required input and validates it against the allowed set [dev, int, staging, prod].; Commit the skeleton to the main branch; confirm CI pipeline can parse it without errors.
**Deliverable:** IaC repository skeleton with environment directory structure and a CONVENTIONS file defining the four tiers, short names, and secret path pattern.
**Acceptance / logic checks:**
- Repository contains exactly four environment directories: environments/dev, environments/int, environments/staging, environments/prod.
- CONVENTIONS file explicitly names short codes dev, int, staging, prod and the secret path pattern gmepay/<env>/<service>/<secret-name>.
- Shared variables file rejects any env_name value outside the allowed set (e.g., passing 'uat' causes a validation error).
- CI pipeline parses the IaC skeleton with zero lint errors on initial commit.

### 14.1-T02 — Provision network zones for dev environment (VPC, subnets, security groups)  _(60 min)_
**Context:** OPS-13 §3.2 defines five network zones: Public DMZ (LB, WAF, TLS termination), API Zone (stateless services), Worker Zone (batch/SFTP, egress-only), Data Zone (PostgreSQL, Redis, queue - no external access), and Management Zone (Admin System, Secrets Mgr, observability - VPN/bastion only). All cross-zone traffic must be encrypted in transit. The dev tier uses synthetic data only; no real PII. Pool sizes for dev: DB connection pool = 5 (per OPS-13 §5.3).
**Steps:** Define a VPC (or equivalent) for the dev environment with five subnets mapped to the five zones.; Configure security groups / firewall rules: Public DMZ accepts inbound HTTPS (443) only; API Zone accepts inbound only from DMZ; Worker Zone egress-only to ZeroPay SFTP stub and partner webhook URLs; Data Zone accepts connections only from API Zone and Worker Zone; Management Zone accepts connections only from VPN/bastion.; Tag all resources with env=dev and zone=<zone-name>.; Output the VPC ID and subnet IDs as IaC module outputs for consumption by downstream tickets.
**Deliverable:** IaC module environments/dev/network that provisions VPC, five zone subnets, and security groups with correct ingress/egress rules for the dev tier.
**Acceptance / logic checks:**
- Security group rules block direct inbound to Data Zone from the public internet; a simulated external probe to the DB subnet is rejected.
- Worker Zone security group has no inbound rules (egress-only); a test connection from the internet to the Worker Zone is rejected.
- Management Zone subnet accepts SSH/bastion only from the designated VPN CIDR; no direct internet access.
- All resources carry env=dev and correct zone tags.
- IaC plan produces zero drift on a second apply (idempotent).
**Depends on:** 14.1-T01

### 14.1-T03 — Provision network zones for int environment (VPC, subnets, security groups)  _(45 min)_
**Context:** The integration environment (E2/int) has the same five-zone network topology as dev (OPS-13 §3.2) but connects to the 한결원 ZeroPay test SFTP server (not a mock stub). Worker Zone egress must allow outbound to the 한결원 test SFTP host/port (confirmed via OI-OPS-02). The int environment is used by Dev and QA teams; data is synthetic plus ZeroPay sandbox data. DB connection pool = 20 (OPS-13 §5.3).
**Steps:** Clone the dev network IaC module, parameterize for env=int.; Update Worker Zone egress rules to allow outbound SFTP (port 22) to the 한결원 test SFTP host (placeholder variable zeropay_sftp_test_host; fill once OI-OPS-02 is resolved).; Apply and verify the plan creates five isolated subnets with correct security group rules.; Output subnet IDs and security group IDs for downstream service modules.
**Deliverable:** IaC module environments/int/network provisioning the int VPC, five zone subnets, and security groups with Worker Zone egress to 한결원 test SFTP.
**Acceptance / logic checks:**
- Worker Zone security group has an explicit egress rule for port 22 to zeropay_sftp_test_host; all other egress from Worker Zone is blocked.
- Data Zone inbound allows only API Zone and Worker Zone source security groups.
- Resources carry env=int tags throughout.
- IaC plan is idempotent (no changes on second apply).
- Variable zeropay_sftp_test_host is required and causes a plan error if left empty.
**Depends on:** 14.1-T02

### 14.1-T04 — Provision network zones for staging environment (VPC, subnets, security groups)  _(45 min)_
**Context:** Staging (E3) mirrors production topology for UAT and load tests. It connects to the 한결원 test SFTP server (same as int). Staging DB connection pool = 80 (OPS-13 §5.3). Direct DB access is restricted to DBA role; deployments require PR merge approval (OPS-13 §2.5). Staging must support multi-AZ placement (API tier active-active, DB primary+replica across two AZs) per OPS-13 §3.4.
**Steps:** Create environments/staging/network IaC module parameterized for env=staging.; Configure two availability zones for API and Data subnets (primary and secondary AZ).; Security group rules identical to int but with stricter DB access: DB security group ingress allows only the API service account and the designated DBA IP range (variable dba_cidr_staging).; Tag all resources env=staging.; Output multi-AZ subnet IDs for downstream DB and API modules.
**Deliverable:** IaC module environments/staging/network with multi-AZ VPC, five-zone subnets, and production-parity security groups for staging.
**Acceptance / logic checks:**
- Two AZ subnets exist for both API Zone and Data Zone; single AZ for Public DMZ is acceptable.
- DB security group allows inbound only from API service security group and dba_cidr_staging variable; no wider CIDR.
- Management Zone subnet has no internet-facing inbound rule.
- IaC plan is idempotent; env=staging tags are on all resources.
- A dry-run validation (terraform validate or equivalent) completes with zero errors.
**Depends on:** 14.1-T02

### 14.1-T05 — Provision network zones for prod environment (VPC, subnets, security groups)  _(50 min)_
**Context:** Production (E4) requires full multi-AZ deployment (OPS-13 §3.4): all stateless services and both PostgreSQL nodes must span at least two AZs. Production DB connection pool = 200 (OPS-13 §5.3). The production environment connects to the 한결원 production SFTP server (not the test SFTP). Production DB and secrets are accessible only via break-glass procedure (OPS-13 §2.5). Worker Zone egress target is zeropay_sftp_prod_host (confirmed by 한결원 before go-live per OI-OPS-02).
**Steps:** Create environments/prod/network IaC module parameterized for env=prod.; Configure minimum two AZs for API Zone subnets, Data Zone subnets, and Management Zone subnets.; Worker Zone egress rule targets zeropay_sftp_prod_host (required variable) on port 22.; DB security group ingress: API service security group only; no DBA CIDR at rest (break-glass requires a separate runbook-controlled security group rule addition).; Tag all resources env=prod.
**Deliverable:** IaC module environments/prod/network with multi-AZ VPC, five-zone subnets, and hardened security groups for production.
**Acceptance / logic checks:**
- At least two AZ subnets exist for API Zone, Data Zone, and Management Zone.
- DB security group has no DBA CIDR in the base module; break-glass access requires a separate emergency SG rule (confirm by inspecting the base plan output).
- Worker Zone egress explicitly permits port 22 to zeropay_sftp_prod_host and denies all other outbound to ZeroPay test hosts.
- env=prod tags present on all resources; no env=staging or env=int tags.
- IaC plan is idempotent on second apply.
**Depends on:** 14.1-T04

### 14.1-T06 — Provision PostgreSQL primary+replica for dev and int environments  _(55 min)_
**Context:** OPS-13 §3.2 places PostgreSQL in the Data Zone. Dev uses a single instance (no replica needed for dev); int uses primary+replica. DB connection pool sizes: dev=5, int=20 (OPS-13 §5.3). The managed PostgreSQL service (RDS, Cloud SQL, or equivalent - A-OPS-02) is preferred. Database migration tool is Flyway or Liquibase (OPS-13 §4.6); migration scripts live in db/migrations/ in the repo. Seed data only in dev; synthetic data in int.
**Steps:** Define an IaC module shared/modules/postgresql with inputs: env, instance_class, storage_gb, multi_az (bool), connection_pool_size.; Instantiate for dev: single instance, multi_az=false, connection_pool_size=5.; Instantiate for int: primary + synchronous replica, multi_az=false (single-AZ for int is acceptable), connection_pool_size=20.; Output connection endpoint and port as module outputs; do not embed credentials (credentials go to secrets manager per 14.1-T10).; Document in module README: migration scripts must be in db/migrations/, applied via Flyway/Liquibase at deploy time before app start.
**Deliverable:** IaC module shared/modules/postgresql plus instantiated configurations for dev and int environments, outputting DB endpoints.
**Acceptance / logic checks:**
- Dev PostgreSQL instance is a single node; applying the plan creates exactly one DB instance tagged env=dev.
- Int configuration creates a primary and one replica; both are tagged env=int.
- Module outputs include db_endpoint and db_port; no passwords are in module outputs (credentials must be fetched from secrets manager).
- Connection pool size variable is set to 5 for dev and 20 for int in the respective instantiations.
- IaC plan is idempotent on second apply for both dev and int.
**Depends on:** 14.1-T03

### 14.1-T07 — Provision PostgreSQL primary+replica for staging and prod with multi-AZ  _(60 min)_
**Context:** Staging uses primary+replica with pool=80; prod uses primary+synchronous standby with pool=200, spanning two AZs (OPS-13 §3.4, §10.2). Prod requires continuous WAL archiving + daily full snapshots, retained 30 days full / 7 years WAL (OPS-13 §9.1). Failover in prod must complete in < 10 min (RTO per OPS-13 §9.3). Prod replica must be in the secondary AZ (different from primary AZ) to satisfy the DR requirement.
**Steps:** Extend shared/modules/postgresql to support wal_archiving_enabled (bool) and backup_retention_days inputs.; Instantiate for staging: primary+replica, multi_az=true (two AZs), pool=80, wal_archiving_enabled=false, backup_retention_days=7.; Instantiate for prod: primary+replica across two explicit AZs, pool=200, wal_archiving_enabled=true, backup_retention_days=30, wal_retention_years=7 (via object storage archiving config).; Enable automated failover (Patroni or managed-service equivalent) for prod; output the replica endpoint separately.; Enable PgBouncer (or cloud-native pooler) for prod at the database tier (OPS-13 §10.2).
**Deliverable:** IaC configurations for staging and prod PostgreSQL, including multi-AZ placement, WAL archiving for prod, and PgBouncer for prod.
**Acceptance / logic checks:**
- Prod primary and replica are in different AZs; verify via resource attribute az or availability_zone.
- Prod has wal_archiving_enabled=true and backup_retention_days=30; staging has these at false/7.
- PgBouncer resource exists in the prod data zone IaC plan.
- Connection pool sizes are 80 for staging and 200 for prod in module instantiations.
- IaC plan is idempotent for both staging and prod.
**Depends on:** 14.1-T06, 14.1-T04, 14.1-T05

### 14.1-T08 — Provision Redis cache and message queue for all four environments  _(50 min)_
**Context:** OPS-13 §3.2 places Redis and a message queue in the Data Zone. Redis in prod has persistence disabled (cache is ephemeral and rebuilt on restart - OPS-13 §9.1); in dev/int/staging it can mirror this setting. The message queue handles webhook dispatch and async batch job triggers (OPS-13 §10.3). Queue depth alert thresholds: P2 at >500 for 5 min, P1 at >2000 for 2 min. Queue must support at minimum two consumer groups: webhook_dispatcher and batch_trigger.
**Steps:** Add Redis module to shared/modules/redis with inputs: env, node_type, persistence_enabled.; Instantiate for dev/int: small node, persistence_enabled=false.; Instantiate for staging/prod: appropriately sized node, persistence_enabled=false (per spec).; Add message queue module (SQS, Pub/Sub, or RabbitMQ per A-OPS-04) with two named queues: gmepay-<env>-webhook-dispatch and gmepay-<env>-batch-trigger.; Tag all resources with env=<env> and zone=data.
**Deliverable:** IaC modules for Redis and message queue provisioned across all four environments, with queue names following the canonical pattern.
**Acceptance / logic checks:**
- Redis persistence_enabled=false in all four environment instantiations.
- Two message queues exist per environment: gmepay-<env>-webhook-dispatch and gmepay-<env>-batch-trigger (verify four pairs = eight queues total).
- Redis and queue resources are in the Data Zone subnet (verify subnet association or VPC placement).
- All resources carry env=<env> and zone=data tags.
- IaC plan is idempotent across all four environments.
**Depends on:** 14.1-T06

### 14.1-T09 — Provision object storage buckets for batch file archival in all environments  _(45 min)_
**Context:** OPS-13 §6.5 requires all inbound and outbound SFTP batch files to be archived in object storage at path /gmepay/batch/<direction>/<file-id>/YYYY-MM-DD/<filename>. Files are retained for 7 years. Versioning must be enabled; files are immutable once written. For prod: geo-redundant bucket with versioning (OPS-13 §9.1). Two prefixes per bucket: incoming/ (inbound from ZeroPay) and outgoing/ (outbound to ZeroPay).
**Steps:** Define IaC module shared/modules/object-storage with inputs: env, versioning_enabled, geo_redundant, retention_years.; Instantiate for dev/int/staging: single-region, versioning=true, retention_years=7.; Instantiate for prod: geo_redundant=true, versioning=true, retention_years=7.; Apply a bucket lifecycle policy to transition objects to cold storage after 90 days and enforce deletion after 7 years + 1 day.; Set bucket ACL / IAM policy so only the batch-worker service account has write access; read access to the ops-admin service account.
**Deliverable:** IaC module shared/modules/object-storage and four environment instantiations, each with versioning, 7-year retention lifecycle, and correct IAM bindings.
**Acceptance / logic checks:**
- Each environment has exactly one bucket following naming pattern gmepay-batch-<env>; four buckets total.
- Versioning is enabled on all four buckets (verify via IaC attribute or CLI inspection).
- Prod bucket has geo_redundant=true; dev/int/staging have it false or single-region.
- Lifecycle policy enforces delete after 7 years (2555 days) - verify the lifecycle rule expiration_in_days >= 2555.
- IAM binding: batch-worker write-only; ops-admin read-only; no public access.
**Depends on:** 14.1-T01

### 14.1-T10 — Configure secrets manager hierarchy and seed initial secret paths for all environments  _(55 min)_
**Context:** OPS-13 §3.5 and §4.7 mandate a dedicated secrets manager (HashiCorp Vault, AWS Secrets Manager, or Azure Key Vault) as the single source of truth. Secret path convention: gmepay/<env>/<service>/<secret-name>. Example: gmepay/prod/batch-worker/zeropay-sftp-key. Services retrieve secrets at startup; secrets are never stored in env var files or Docker images. CI/CD has read-only access to gmepay/ci/* for test credentials only - it never touches production secrets (OPS-13 §4.7).
**Steps:** Provision secrets manager namespace/engine for each environment tier: gmepay/dev, gmepay/int, gmepay/staging, gmepay/prod, gmepay/ci.; Create placeholder secret entries (empty or mock values) for all services in dev and int: hub-api/db-password, hub-api/jwt-secret, batch-worker/zeropay-sftp-key, batch-worker/zeropay-sftp-host, admin/db-password.; Apply IAM policies: each service role has read access to gmepay/<env>/<service>/* only; CI role has read access to gmepay/ci/* only; no cross-env read access.; Document the rotation schedule placeholders (rotation is defined in SEC-09; the IaC just provisions the paths).; Output the secrets manager ARNs or paths as IaC outputs for use in container environment injection.
**Deliverable:** Secrets manager IAC configuration with gmepay/<env>/<service>/<secret-name> path hierarchy for all four envs plus ci, with least-privilege IAM policies per service.
**Acceptance / logic checks:**
- Five top-level paths exist: gmepay/dev, gmepay/int, gmepay/staging, gmepay/prod, gmepay/ci.
- CI service account can read from gmepay/ci/* but receives a permission denied error when attempting to read gmepay/prod/*.
- hub-api service role in prod can read gmepay/prod/hub-api/* but not gmepay/prod/batch-worker/*.
- Placeholder secrets exist at gmepay/dev/batch-worker/zeropay-sftp-key and gmepay/int/batch-worker/zeropay-sftp-key.
- Secrets manager paths contain no actual production credentials at this stage (values are placeholders or empty).
**Depends on:** 14.1-T01

### 14.1-T11 — Provision container registry and define image tagging convention  _(45 min)_
**Context:** OPS-13 §4.1 Stage 5 requires container images tagged with <git-sha> + <branch> + <semver> and pushed to an artifact registry. Images must be signed. The artifact registry must support geo-replication for prod (OPS-13 §9.1: container images retained 1 year). Services: hub-api, batch-worker, admin-system, partner-portal, webhook-dispatcher. All environments share the same registry; the environment is determined by the runtime config, not the image.
**Steps:** Provision a container registry (ECR, GCR, or ACR) with geo-replication enabled.; Create repositories for each service: gmepay/hub-api, gmepay/batch-worker, gmepay/admin-system, gmepay/partner-portal, gmepay/webhook-dispatcher.; Define and document the image tagging convention in CI/CD docs: <git-sha>-<branch>-<semver> (e.g., a1b2c3d-main-1.2.3).; Configure image signing (cosign or cloud-native signing) and add a policy that unsigned images are rejected in staging and prod deployments.; Set image lifecycle policy: retain the last 30 tags per repository; delete untagged images after 7 days; retain any tag matching semver pattern for 1 year.
**Deliverable:** Container registry with five service repositories, image signing policy, and a documented tagging convention (git-sha+branch+semver).
**Acceptance / logic checks:**
- Five repositories exist: gmepay/hub-api, gmepay/batch-worker, gmepay/admin-system, gmepay/partner-portal, gmepay/webhook-dispatcher.
- Pushing an unsigned test image and attempting to deploy it to staging triggers a policy rejection.
- Lifecycle policy deletes untagged images after 7 days (verify by inspecting the lifecycle rule).
- Geo-replication is enabled (at least two regions) - verify via registry settings.
- A test image tagged a1b2c3d-main-1.0.0 can be pushed and pulled from the registry.
**Depends on:** 14.1-T01

### 14.1-T12 — Deploy Hub API service skeleton to dev environment (Kubernetes manifests)  _(55 min)_
**Context:** OPS-13 §3.2 places the Northbound API (hub-api) in the API Zone. It is stateless and horizontally scalable; min 2 replicas in prod (OPS-13 §10.2). For dev, 1 replica is sufficient. Runtime config is injected via environment variables sourced from secrets manager at container start (OPS-13 §4.7). No config files are bundled in the image. The service must emit structured JSON logs with fields: timestamp, level, service (hub-api), env, trace_id, span_id, partner_id, transaction_id, event, message (OPS-13 §7.2).
**Steps:** Write a Kubernetes Deployment manifest for hub-api in environments/dev/k8s/hub-api/deployment.yaml: image from registry (placeholder tag), replicas=1, resource limits, env vars sourced from secrets manager (EXTERNAL_SECRET or similar operator).; Write a Service manifest exposing port 8080 within the API Zone subnet.; Add a liveness probe (GET /health, period 10s, failure threshold 3) and readiness probe (GET /ready, period 5s).; Apply manifests to the dev cluster; confirm pod reaches Running state.; Verify the pod logs emit a startup log line in JSON format containing service=hub-api and env=dev.
**Deliverable:** Kubernetes Deployment and Service manifests for hub-api in dev, with secrets injection, health probes, and a running pod that logs structured JSON.
**Acceptance / logic checks:**
- kubectl get pods -n gmepay-dev shows hub-api pod in Running state.
- GET /health returns HTTP 200 within 5 seconds.
- A sample log line from the running pod contains JSON fields service, env, level, timestamp (validate with: kubectl logs <pod> | head -1 | python -m json.tool returns no error).
- Pod environment variables are populated from secrets manager paths (not from a mounted config file or hardcoded values in the manifest).
- Deleting the pod results in a replacement pod reaching Running state within 60 seconds (liveness recovery).
**Depends on:** 14.1-T10, 14.1-T11, 14.1-T02

### 14.1-T13 — Deploy batch-worker and webhook-dispatcher skeletons to dev and int environments  _(55 min)_
**Context:** OPS-13 §3.2 places the Batch/SFTP Worker in the Worker Zone. It runs as a single-instance CronJob (A-OPS-07); never auto-scaled. In dev and int, batch jobs are triggered manually (not on KST schedule) per OPS-13 §5.3. The webhook-dispatcher is in the API Zone (handles async event dispatch), horizontally scalable (OPS-13 §10.1 min=2 pods). For dev, 1 replica of webhook-dispatcher. Batch worker must NOT run two simultaneous instances (duplicate SFTP submissions would result).
**Steps:** Write a Kubernetes CronJob manifest for batch-worker (schedule: manual-trigger only; set schedule to a never-firing cron 0 0 31 2 * for dev/int): environments/dev/k8s/batch-worker/cronjob.yaml.; Add a concurrencyPolicy: Forbid to the CronJob spec to prevent parallel runs.; Write a Deployment manifest for webhook-dispatcher: 1 replica for dev, connected to the gmepay-dev-webhook-dispatch queue.; Apply both to dev cluster; confirm batch-worker CronJob and webhook-dispatcher pod reach correct states.; Repeat for int environment with the same structure (schedule still non-firing KST for int per OPS-13 §5.3).
**Deliverable:** Kubernetes CronJob manifest for batch-worker (concurrencyPolicy: Forbid) and Deployment manifest for webhook-dispatcher, applied to dev and int environments.
**Acceptance / logic checks:**
- kubectl get cronjobs -n gmepay-dev shows batch-worker with schedule 0 0 31 2 * (never fires automatically).
- CronJob spec has concurrencyPolicy: Forbid; attempting to create a second Job from the same CronJob while one is active is blocked.
- webhook-dispatcher pod in dev is in Running state and is connected to gmepay-dev-webhook-dispatch queue (verify queue consumer count = 1).
- Manually triggering the CronJob (kubectl create job --from=cronjob/batch-worker test-run -n gmepay-dev) creates exactly one Job pod.
- All pods carry label env=dev (or env=int for int counterparts).
**Depends on:** 14.1-T12, 14.1-T08

### 14.1-T14 — Deploy admin-system and partner-portal skeletons to dev environment  _(45 min)_
**Context:** OPS-13 §3.2 places Admin System in the Management Zone (VPN/bastion access only). Partner Portal is also internal-facing. Auto-scaling for admin: CPU>70% for 3 min scale-out, CPU<30% for 10 min scale-in, min=2 pods, max=8 pods (OPS-13 §10.1). For dev, 1 replica is sufficient. Both services emit structured JSON logs with service=admin-system or service=partner-portal and env=dev.
**Steps:** Write Kubernetes Deployment and Service manifests for admin-system in environments/dev/k8s/admin-system/; place Service in the Management Zone subnet (internal-only, no public ingress).; Write Deployment and Service manifests for partner-portal similarly in the Management Zone.; Add liveness (GET /health) and readiness probes to both deployments.; Apply to dev cluster; confirm pods reach Running state.; Verify that a request to admin-system from outside the Management Zone subnet is rejected (simulate via kubectl exec from an API Zone pod using the service ClusterIP).
**Deliverable:** Kubernetes Deployment and Service manifests for admin-system and partner-portal in dev, in the Management Zone with no public ingress.
**Acceptance / logic checks:**
- Both admin-system and partner-portal pods are in Running state in namespace gmepay-dev.
- GET /health on each service returns HTTP 200.
- A curl from an API Zone pod to admin-system's ClusterIP returns a connection or network policy rejection (access control enforced).
- Log output from each pod contains JSON with service=admin-system (or service=partner-portal) and env=dev.
- Manifests specify no NodePort or LoadBalancer type (type: ClusterIP only).
**Depends on:** 14.1-T12

### 14.1-T15 — Configure environment-specific runtime config matrix for all four tiers  _(40 min)_
**Context:** OPS-13 §5.3 defines a configuration matrix. Key values per environment: ZeroPay SFTP host (dev=stub/mock, int=han-gyeol-won test SFTP, staging=han-gyeol-won test SFTP, prod=han-gyeol-won production SFTP), DB connection pool (dev=5, int=20, staging=80, prod=200), prefunding low-balance threshold (dev=$100, int=$1000, staging=$10000, prod=$10000 per partner config), rate quote TTL (dev=300s, int=300s, staging=per partner config, prod=per partner config), webhook retry max (dev=3, int=3, staging=5, prod=5), log level (dev=DEBUG, int=DEBUG, staging=INFO, prod=INFO), batch job schedule (dev=manual, int=manual, staging=KST production windows, prod=KST production windows).
**Steps:** Create a ConfigMap or secrets-manager config entry per environment encapsulating all values from the OPS-13 §5.3 matrix.; For dev: ZEROPAY_SFTP_HOST=mock-sftp-stub, DB_POOL_SIZE=5, PREFUND_ALERT_THRESHOLD_USD=100, RATE_QUOTE_TTL_SECONDS=300, WEBHOOK_RETRY_MAX=3, LOG_LEVEL=DEBUG, BATCH_SCHEDULE=MANUAL.; For int: ZEROPAY_SFTP_HOST=<zeropay-test-sftp-host-variable>, DB_POOL_SIZE=20, PREFUND_ALERT_THRESHOLD_USD=1000, RATE_QUOTE_TTL_SECONDS=300, WEBHOOK_RETRY_MAX=3, LOG_LEVEL=DEBUG, BATCH_SCHEDULE=MANUAL.; For staging: DB_POOL_SIZE=80, WEBHOOK_RETRY_MAX=5, LOG_LEVEL=INFO, BATCH_SCHEDULE=KST_PRODUCTION.; For prod: DB_POOL_SIZE=200, PREFUND_ALERT_THRESHOLD_USD=10000, WEBHOOK_RETRY_MAX=5, LOG_LEVEL=INFO, BATCH_SCHEDULE=KST_PRODUCTION.; Reference these config values from the service Deployment manifests via environment variable injection.
**Deliverable:** Per-environment runtime configuration files (ConfigMap or secrets-manager entries) covering all seven parameters from the OPS-13 §5.3 matrix, referenced by service manifests.
**Acceptance / logic checks:**
- Dev config has LOG_LEVEL=DEBUG and DB_POOL_SIZE=5; confirm by inspecting the dev ConfigMap/secret.
- Prod config has LOG_LEVEL=INFO and DB_POOL_SIZE=200; confirm similarly.
- Staging and prod BATCH_SCHEDULE=KST_PRODUCTION; dev and int BATCH_SCHEDULE=MANUAL.
- Webhook retry max is 3 for dev/int and 5 for staging/prod - verify in respective config entries.
- No production config values (especially ZEROPAY_SFTP_HOST for prod) appear in the dev or int config entries.
**Depends on:** 14.1-T12, 14.1-T10

### 14.1-T16 — Configure SFTP stub/mock for dev environment to simulate 한결원 ZeroPay SFTP  _(50 min)_
**Context:** In the dev environment, the ZeroPay SFTP host must be a mock/stub (OPS-13 §5.3: dev=stub/mock). The batch worker must be able to connect, upload, and download test ZP00xx files without contacting 한결원. The stub must accept SSH key authentication using the dev mock key pair and present a directory structure: incoming/ and outgoing/ matching the pattern used in object storage: /gmepay/batch/<direction>/<file-id>/YYYY-MM-DD/<filename>. This is required so batch-worker integration tests can run in dev without the external dependency.
**Steps:** Deploy a lightweight SFTP stub container (e.g., atmoz/sftp or equivalent) in the Worker Zone of the dev environment as a Kubernetes Deployment.; Configure the stub to accept a dev mock SSH public key (generated locally, stored in gmepay/dev/batch-worker/zeropay-sftp-key in secrets manager).; Pre-create directories incoming/ and outgoing/ in the stub.; Set ZEROPAY_SFTP_HOST=mock-sftp-stub-service (Kubernetes service name) in the dev ConfigMap (aligns with 14.1-T15).; Test by SSHing from a batch-worker pod using the dev key pair and uploading a test file to outgoing/; confirm the file is present in the stub.
**Deliverable:** SFTP stub Deployment and Service in the dev Worker Zone, accepting dev mock SSH key, with incoming/ and outgoing/ directories accessible from the batch-worker pod.
**Acceptance / logic checks:**
- sftp -i dev-mock-key user@mock-sftp-stub-service from within the Worker Zone pod connects successfully (exit code 0).
- Uploading a test file to outgoing/ZP0011/2026-01-01/test.dat succeeds and the file persists in the stub.
- Downloading from incoming/ZP0012/2026-01-01/test.dat succeeds after manually placing a file there.
- The stub does not have a public ingress; connection attempts from outside the Worker Zone are rejected.
- The dev mock private key is stored only in gmepay/dev/batch-worker/zeropay-sftp-key in secrets manager, not in the container image or ConfigMap.
**Depends on:** 14.1-T13, 14.1-T15

### 14.1-T17 — Obtain and configure 한결원 ZeroPay test environment SFTP credentials for int environment  _(40 min)_
**Context:** OPS-13 §2.2 states that separate SFTP credentials are issued per environment (test vs production); never share credentials across tiers. The 한결원 test environment must be accessible by mid-May 2026 (OI-OPS-01). Once credentials are received from 한결원, they must be stored at gmepay/int/batch-worker/zeropay-sftp-key (private key) and gmepay/int/batch-worker/zeropay-sftp-host (host/port). The int environment ZEROPAY_SFTP_HOST in the ConfigMap (14.1-T15) must be updated from placeholder to the real test host. Credentials must never appear in any code file, manifest, or log.
**Steps:** Submit the SSH public key for the int environment to 한결원 via GME Tech / the GME account manager (track via OI-OPS-01).; Upon receipt of 한결원 test SFTP host, port, and confirmation of public key loading: store private key in secrets manager at gmepay/int/batch-worker/zeropay-sftp-key.; Store the test SFTP host in gmepay/int/batch-worker/zeropay-sftp-host; update the ZEROPAY_SFTP_HOST value in the int ConfigMap to reference this secret.; Perform a connectivity test: from the int batch-worker pod, run sftp -i int-key user@<zeropay-test-host> - connect only, do not upload live files.; Confirm the connection log shows Authenticated and the session can list the remote directory.
**Deliverable:** Confirmed SFTP connectivity from int batch-worker to 한결원 test SFTP, with credentials stored in secrets manager and ZEROPAY_SFTP_HOST updated in int config.
**Acceptance / logic checks:**
- sftp connection from int batch-worker pod to 한결원 test SFTP exits with code 0 (authenticated).
- gmepay/int/batch-worker/zeropay-sftp-key exists in secrets manager and contains the private key (verify path exists; do not log the key value).
- ZEROPAY_SFTP_HOST env var in the int batch-worker pod resolves to the 한결원 test host (not the mock stub or prod host).
- No SFTP credentials appear in any Kubernetes manifest, ConfigMap, or application log line.
- The dev environment ZEROPAY_SFTP_HOST still points to mock-sftp-stub-service (no cross-env contamination).
**Depends on:** 14.1-T15, 14.1-T13

### 14.1-T18 — Configure feature flags for all environments  _(35 min)_
**Context:** OPS-13 §5.2 defines five feature flags stored in secrets/config manager and loaded at service startup. Default Phase 1 values: FEATURE_LIVE_FX_FEED=false, FEATURE_PARTNER_REFUND_API=false, FEATURE_OUTBOUND_PAYMENTS=false, FEATURE_BOK_REPORTING=false, FEATURE_MULTI_SCHEME_ROUTING=false. Changing a flag requires a config update and service restart (no full deployment). Flags must be consistent across all four environments at Phase 1 launch (all false).
**Steps:** Add a feature-flags config entry in secrets manager or ConfigMap for each environment: gmepay/<env>/hub-api/feature-flags.; Set all five flags to false for dev, int, staging, and prod.; Inject flags as environment variables into the hub-api Deployment: FEATURE_LIVE_FX_FEED, FEATURE_PARTNER_REFUND_API, FEATURE_OUTBOUND_PAYMENTS, FEATURE_BOK_REPORTING, FEATURE_MULTI_SCHEME_ROUTING.; Verify the hub-api startup log emits a line listing active feature flags.; Document the procedure for flipping a flag: update secrets manager value, then trigger a rolling restart of hub-api (kubectl rollout restart deployment/hub-api -n gmepay-<env>).
**Deliverable:** Feature flag config entries for all four environments with all five flags set to false, injected into hub-api, with documented flip procedure.
**Acceptance / logic checks:**
- kubectl exec into hub-api pod and echo $FEATURE_LIVE_FX_FEED returns false for all four environments.
- All five flag env vars are present in the hub-api pod (verify with kubectl describe pod showing all five vars).
- Feature flag values in dev, int, staging, and prod are all false at initial provisioning (no environment has any flag set to true).
- A simulated flag flip (update config to true + kubectl rollout restart) causes the new pod to pick up the updated value without a full image redeploy.
- Feature flag values are sourced from secrets manager paths, not hardcoded in Kubernetes manifests.
**Depends on:** 14.1-T15

### 14.1-T19 — Configure CI/CD pipeline: build and unit-test stages (Stage 1 and 2)  _(55 min)_
**Context:** OPS-13 §4.1 defines 8 pipeline stages. Stage 1 (Build): compile, lint, type-check - gate: zero lint errors or type errors. Stage 2 (Unit test): unit + component tests - gate: all tests pass, coverage >= 80%. The pipeline targets GitHub Actions or GitLab CI (SAD-02). Every code change to main branches passes through all stages in order. The pipeline must not proceed past Stage 2 if coverage is below 80%.
**Steps:** Create pipeline definition file (.github/workflows/ci.yml or .gitlab-ci.yml) with jobs: build and unit-test.; build job: checkout code, run linter (checkstyle or equivalent for Java), run type-check / compilation; fail on any lint error or compilation error.; unit-test job (depends on build): run unit test suite via Gradle/Maven; capture coverage report (JaCoCo or equivalent); fail the job if line coverage < 80%.; Publish coverage report as a pipeline artifact.; Add a branch protection rule on main: require build and unit-test jobs to pass before merge is allowed.
**Deliverable:** CI pipeline stages 1 (build) and 2 (unit-test) in .github/workflows/ci.yml (or equivalent), with coverage gate at 80%.
**Acceptance / logic checks:**
- A PR with a compilation error fails the build job; the PR cannot be merged.
- A PR that drops coverage below 80% fails the unit-test job; coverage percentage is visible in the pipeline log.
- A PR with all tests passing and coverage >= 80% passes both stages and is merge-eligible.
- The pipeline does not advance to Stage 3 if Stage 2 fails (job dependency enforced).
- Coverage artifact (HTML or XML report) is available as a downloadable pipeline artifact after each run.
**Depends on:** 14.1-T11

### 14.1-T20 — Configure CI/CD pipeline: security scan and integration test stages (Stage 3 and 4)  _(60 min)_
**Context:** OPS-13 §4.1 Stage 3: SAST (static analysis), dependency vulnerability scan, secrets leak scan - gate: no critical/high CVEs; no secrets detected. Stage 4: spin up ephemeral env with DB + dependencies; run integration test suite - gate: all integration tests pass. The integration test stage uses test credentials from gmepay/ci/* secrets (CI has read-only access to gmepay/ci/* only, never production secrets per OPS-13 §4.7).
**Steps:** Add security-scan job (depends on unit-test): run SAST tool (e.g., SpotBugs or SonarQube), run dependency vulnerability scanner (OWASP Dependency-Check or Snyk), run secrets leak scanner (truffleHog or gitleaks); fail on any critical or high CVE or detected secret.; Add integration-test job (depends on security-scan): spin up ephemeral Docker Compose env with PostgreSQL and Redis; inject test credentials from gmepay/ci/hub-api/db-password and gmepay/ci/hub-api/jwt-secret; run integration test suite; tear down on completion.; Ensure the integration-test job authenticates to secrets manager using a CI service account with access only to gmepay/ci/*.; Publish security scan report as a pipeline artifact.; Confirm that attempting to access gmepay/prod/* from the CI service account returns a permission denied error.
**Deliverable:** CI pipeline stages 3 (security-scan) and 4 (integration-test) in the pipeline definition, with SAST, CVE scan, secrets scan, and ephemeral integration environment.
**Acceptance / logic checks:**
- A PR containing a hardcoded API key in source code is rejected by the secrets leak scan in Stage 3.
- A PR that introduces a critical CVE dependency is rejected by the vulnerability scan.
- The integration-test job successfully spins up PostgreSQL (ephemeral), runs a sample API integration test (e.g., POST /v1/rates returns 200), and tears down the environment.
- The CI service account cannot read gmepay/prod/hub-api/db-password (permission denied error in CI log confirms least-privilege).
- Pipeline does not advance past Stage 3 if any critical/high CVE is detected.
**Depends on:** 14.1-T19, 14.1-T10

### 14.1-T21 — Configure CI/CD pipeline: artifact build and image signing stage (Stage 5)  _(55 min)_
**Context:** OPS-13 §4.1 Stage 5: build container image; tag with <git-sha>+<branch>+<semver>; push to artifact registry; sign image. The image tag format is <git-sha>-<branch>-<semver> (e.g., a1b2c3d-main-1.2.3). Signed images are required; unsigned images are rejected in staging and prod (per 14.1-T11). Only Stage 5 creates and pushes images; no other stage should push images. The pipeline builds five images: hub-api, batch-worker, admin-system, partner-portal, webhook-dispatcher.
**Steps:** Add artifact job (depends on integration-test): for each service, build Docker image with tag <GIT_SHA>-<BRANCH>-<SEMVER>; push to the registry repositories created in 14.1-T11.; Implement image signing using cosign or the cloud-native signing mechanism configured in 14.1-T11.; Store the signing key reference in gmepay/ci/registry/signing-key.; Fail the job if any service image fails to push or fails to sign.; Output the pushed image tags as job artifacts for consumption by Stage 6.
**Deliverable:** CI pipeline Stage 5 artifact job that builds five signed container images with <git-sha>-<branch>-<semver> tags and pushes them to the registry.
**Acceptance / logic checks:**
- After a successful Stage 5 run, five images are present in the registry with the correct tag format (e.g., a1b2c3d-main-1.0.0 for each service).
- Each pushed image has a valid cosign signature; verifying with cosign verify returns success.
- An attempt to deploy an image from this stage to staging without a valid signature is rejected by the admission policy.
- The image tag is derived from actual git SHA + branch name + semver (confirm in pipeline log: the tag is not a static string like latest).
- Stage 5 does not run if Stage 4 fails (job dependency enforced).
**Depends on:** 14.1-T20, 14.1-T11

### 14.1-T22 — Configure CI/CD pipeline: automated deploy to int and smoke tests (Stage 6)  _(55 min)_
**Context:** OPS-13 §4.1 Stage 6: automated deploy to integration environment triggered by merge to main; smoke tests must pass. The int environment auto-deploys on every merge to main. Smoke tests verify /health endpoint and basic connectivity. Per OPS-13 §2.3 and §5.3, int uses the 한결원 test SFTP (requires OI-OPS-01) and DB pool=20. Rollback: if smoke tests fail within 5 minutes, the pipeline automatically redeploys the previous image tag (OPS-13 §4.4).
**Steps:** Add deploy-int job (depends on artifact, runs only on main branch): apply updated image tags to int Kubernetes deployments using kubectl set image or Helm upgrade.; Add smoke-test job (depends on deploy-int, timeout 5 minutes): call GET /health on hub-api, admin-system, and webhook-dispatcher in the int namespace; verify all return HTTP 200.; Add auto-rollback step: if smoke-test job fails, the pipeline redeploys the previous image tag (retrieved from the prior successful Stage 6 run artifact).; Store the last-known-good image tags per service in a pipeline variable or CI artifact after each successful Stage 6 run.; Alert the dev team via Slack/email if auto-rollback is triggered.
**Deliverable:** CI pipeline Stage 6 job: automated deploy to int on main merge with smoke tests and auto-rollback on smoke test failure.
**Acceptance / logic checks:**
- A merge to main triggers the deploy-int job automatically (no manual approval required).
- After deployment, GET /health on hub-api in int namespace returns HTTP 200 within 60 seconds.
- If the smoke-test job fails, the pipeline automatically redeploys the previous image tag (verify by checking the Kubernetes deployment image tag after a simulated failure).
- The last-known-good image tag for hub-api is stored and retrievable as a pipeline artifact after each successful Stage 6 run.
- An alert message appears in the configured Slack channel when auto-rollback is triggered.
**Depends on:** 14.1-T21, 14.1-T12, 14.1-T13

### 14.1-T23 — Configure CI/CD pipeline: deploy to staging on release cut and manual approval gate (Stage 7)  _(55 min)_
**Context:** OPS-13 §4.1 Stage 7: automated deploy triggered on merge to release/*; run load and UAT test suites; gate is manual approval before prod promotion. OPS-13 §4.3: staging deployment requires stakeholder sign-off before prod promotion. OPS-13 §4.2: release branches are named release/<version>. Staging uses production-window KST batch schedules (OPS-13 §5.3). Staging DB pool=80.
**Steps:** Add deploy-staging job: triggered only on release/* branch merge; apply updated image tags to staging Kubernetes deployments.; Add load-test job (depends on deploy-staging): run synthetic load test at minimum 500 TPS against the staging hub-api for 5 minutes; fail if p95 latency > 500ms or error rate > 1%.; Add uat-suite job (depends on deploy-staging): run full integration and UAT test suite in staging.; Add manual-approval gate: after load-test and uat-suite pass, require a named approver (Release Manager role) to approve before Stage 8 is unblocked.; Confirm the manual approval identity is logged in the pipeline audit trail.
**Deliverable:** CI pipeline Stage 7 job: deploy to staging on release/* cut, load test, UAT suite, and manual approval gate before prod.
**Acceptance / logic checks:**
- A merge to main does NOT trigger deploy-staging (only release/* triggers it).
- After staging deploy, load test runs at 500 TPS for 5 minutes; p95 latency under 500ms causes the job to pass.
- If p95 latency exceeds 500ms during load test, the job fails and blocks Stage 8.
- The manual approval gate requires a named approver; approving without the Release Manager role should be rejected.
- The approver identity (name/email) is visible in the pipeline run log.
**Depends on:** 14.1-T22, 14.1-T04

### 14.1-T24 — Configure CI/CD pipeline: gated production deployment (Stage 8)  _(60 min)_
**Context:** OPS-13 §4.1 Stage 8: human-approved deployment, signed-off by Release Manager. OPS-13 §4.3 prod gate requires: Release Manager approval in CI/CD tool, change record in change management system, QA sign-off on staging results, pre-deployment checklist completed. OPS-13 §4.4: if post-deployment smoke tests fail within 5 minutes, pipeline auto-redeploys previous image. Database migrations (if any) are applied before the new app version starts (OPS-13 §4.6).
**Steps:** Add deploy-prod job (depends on staging manual approval, only on release/* branch): apply updated image tags to prod Kubernetes deployments.; Before pod rollout: execute database migration script via Flyway/Liquibase with a pre-deploy hook; fail the job if any migration fails.; After rollout: run prod smoke tests (GET /health, POST /v1/rates with test GME Remit parameters, GET /v1/partners/<id>/prefunding-balance); timeout 3 minutes.; Implement auto-rollback: if smoke tests fail within 5 minutes, redeploy the previous image tag; do NOT reverse applied migrations.; Log the deploying user identity, image tags, migration versions applied, and smoke test results to the CI/CD audit trail.
**Deliverable:** CI pipeline Stage 8 job: gated prod deployment with migration pre-hook, smoke tests, auto-rollback (image only, not migrations), and audit logging.
**Acceptance / logic checks:**
- Stage 8 does not run without the Stage 7 manual approval being granted.
- A simulated migration failure in the pre-deploy hook causes Stage 8 to halt before pods are updated.
- After a successful deploy, POST /v1/rates with GME Remit domestic params returns HTTP 200 with a collection_amount field in the response.
- If the 3-minute smoke test times out, the pipeline auto-redeploys the previous image tag but does NOT roll back the applied migration.
- Deployer identity appears in the CI/CD audit trail for the Stage 8 run.
**Depends on:** 14.1-T23, 14.1-T05

### 14.1-T25 — Configure branching strategy and branch protection rules  _(40 min)_
**Context:** OPS-13 §4.2 defines trunk-based development: feature/<ticket> branches PR to main; main auto-deploys to int; release/<version> auto-deploys to staging; hotfix/<ticket> goes to prod via fast-track approval. Branch protection rules enforce pipeline gates. Hotfix fast-track requires two senior engineers + on-call approval and a mandatory post-deployment review. Hotfix branches must be cherry-picked to both main and the active release branch.
**Steps:** Configure branch protection on main: require PR review (min 1 reviewer), require build + unit-test + security-scan + integration-test jobs to pass.; Configure branch protection on release/*: require min 2 reviewers, require all CI stages 1-7 to pass.; Configure branch protection on hotfix/*: require 2 senior-engineer approvals + on-call acknowledgement; tag hotfix merges with a hotfix label for audit.; Document the hotfix procedure in RUNBOOK.md: cherry-pick to main AND the active release/* branch; post-deployment review within 48 hours mandatory.; Verify that a direct push to main (without PR) is rejected.
**Deliverable:** Branch protection rules configured for main, release/*, and hotfix/* with documented hotfix procedure in RUNBOOK.md.
**Acceptance / logic checks:**
- A direct push to main is rejected with a protected branch error.
- A PR to main with failing unit-test job cannot be merged (the merge button is disabled or blocked).
- A PR to release/* requires 2 approvers; attempting to merge with 1 approval is blocked.
- A hotfix/* PR merge creates an audit label visible in the PR history.
- RUNBOOK.md contains the hotfix cherry-pick procedure with explicit steps for cherry-picking to both main and active release.
**Depends on:** 14.1-T19

### 14.1-T26 — Provision prod deployment components: load balancer, WAF, TLS certificates  _(60 min)_
**Context:** OPS-13 §3.2 places the load balancer and WAF in the Public DMZ. TLS termination happens at the load balancer. OPS-13 §12.1.1 pre-go-live checklist requires: load balancer, WAF, and TLS certificates validated. The load balancer must health-check each AZ independently (OPS-13 §3.4). Blue-green deployment uses weighted target groups (A-OPS-06). TLS certificates must be auto-renewed. The WAF must block common web attacks before they reach the API Zone.
**Steps:** Provision a load balancer in the prod Public DMZ subnet across both AZs; configure health checks on each AZ target group independently.; Provision a WAF with a managed ruleset (OWASP Top 10 or cloud-provider equivalent) and associate it with the load balancer.; Provision or import a TLS certificate for the gmepay+ API domain; configure auto-renewal (ACM, Let's Encrypt, or equivalent).; Configure the load balancer listener: HTTPS:443 to hub-api target group; redirect HTTP:80 to HTTPS.; Implement blue-green weighted target groups: initially 100% to blue target group; a separate green target group is available for deployments.
**Deliverable:** Load balancer, WAF, and auto-renewed TLS certificate in the prod Public DMZ, with blue-green weighted target groups configured.
**Acceptance / logic checks:**
- GET https://<prod-domain>/health returns HTTP 200 with a valid TLS certificate (no browser warning).
- HTTP:80 to the same domain returns a 301 redirect to HTTPS (not a direct response).
- WAF blocks a test SQL injection request (e.g., GET /v1/rates?partner_id=1' OR 1=1) with HTTP 403.
- Both blue and green target groups exist in the load balancer config; blue is at 100% weight, green at 0%.
- TLS certificate expiry date is at least 90 days from provisioning and auto-renewal is enabled.
**Depends on:** 14.1-T05

### 14.1-T27 — Provision prod deployment components: Hub API replicas, auto-scaling, and PodDisruptionBudget  _(55 min)_
**Context:** OPS-13 §10.1: Hub API min=3 pods, max=20 pods in prod; scale-out trigger CPU>70% for 3 min or p95 latency>300ms; scale-in trigger CPU<30% for 10 min. OPS-13 §10.2 (high availability): minimum 2 replicas in production for the API tier (N+1). Replicas must be spread across AZs. A PodDisruptionBudget ensures at least 2 pods are always available during rolling deployments to prevent downtime.
**Steps:** Update the hub-api Deployment manifest for prod to replicas=3 with a PodAntiAffinity rule distributing pods across AZs.; Create a HorizontalPodAutoscaler (HPA) targeting hub-api: minReplicas=3, maxReplicas=20, CPU metric threshold=70%, custom p95 latency metric threshold=300ms (via Prometheus adapter or KEDA).; Create a PodDisruptionBudget for hub-api: minAvailable=2.; Apply the same pattern for webhook-dispatcher: minReplicas=2, maxReplicas=10, queue depth metric trigger >200 (OPS-13 §10.1).; Simulate a rolling deployment and verify that at least 2 hub-api pods remain available throughout.
**Deliverable:** Prod Kubernetes manifests for hub-api HPA (min=3, max=20, CPU 70%), PodDisruptionBudget (minAvailable=2), and multi-AZ anti-affinity for hub-api and webhook-dispatcher.
**Acceptance / logic checks:**
- kubectl get hpa hub-api -n gmepay-prod shows minReplicas=3 and maxReplicas=20.
- PodDisruptionBudget hub-api shows minAvailable=2; deleting one pod while two others are present succeeds; attempting to simultaneously delete two pods when only three exist is rejected by the PDB.
- Pods are distributed across at least two AZs (kubectl get pods -o wide shows different node zones).
- kubectl get hpa webhook-dispatcher shows minReplicas=2 and maxReplicas=10.
- Rolling deployment test: kubectl rollout status confirms all 3 pods updated with no point where fewer than 2 were Running.
**Depends on:** 14.1-T24, 14.1-T26

### 14.1-T28 — Configure blue-green deployment procedure and rollback for prod  _(60 min)_
**Context:** OPS-13 §4.5: blue-green for major releases and schema-changing deployments. Blue-green uses weighted target groups (provisioned in 14.1-T26): provision parallel environment (green), run smoke tests, then cut traffic. Rollback is a DNS/LB pointer flip. The batch worker drains before redeploy and must never run two versions simultaneously (OPS-13 §4.5). Automated rollback triggers if post-deployment smoke tests fail within 5 minutes (OPS-13 §4.4).
**Steps:** Write a deployment script deploy-blue-green.sh: takes parameters SERVICE, NEW_IMAGE_TAG, ENV=prod.; Script steps: (1) deploy new image to green target group at 0% weight; (2) wait for all green pods to pass readiness probes; (3) run smoke tests against green; (4) if pass, shift LB weight to 100% green; (5) if fail within 5 min, auto-shift back to 100% blue.; For batch-worker: drain-and-replace script that waits for any running CronJob instance to complete before updating the image (checks kubectl get jobs).; Document the rollback command for manual use: set LB target group blue weight to 100% (aws elbv2 modify-listener or equivalent one-liner).; Add a 5-minute timeout guard: if smoke tests do not complete within 5 minutes, auto-rollback is triggered.
**Deliverable:** deploy-blue-green.sh script implementing zero-downtime blue-green cutover with 5-minute smoke-test timeout and auto-rollback, plus a batch-worker drain-and-replace script.
**Acceptance / logic checks:**
- Running deploy-blue-green.sh with a valid new image tag shifts traffic from blue to green after smoke tests pass (confirm via LB target group weight API).
- If smoke tests fail within 5 minutes, the LB weight is automatically restored to 100% blue (test by deploying a version that fails /health).
- A manual rollback (execute the rollback command) shifts traffic back to 100% blue in under 30 seconds.
- Batch-worker script waits for a running Job to complete before updating the image; a concurrent Job blocks the update until it finishes.
- No downtime is observed during green cutover: continuous health-check polling during the cutover shows 0 non-2xx responses.
**Depends on:** 14.1-T27

### 14.1-T29 — Configure database migration pipeline integration (Flyway/Liquibase) for all environments  _(55 min)_
**Context:** OPS-13 §4.6: all schema changes use Flyway or Liquibase; every migration has a unique sequential version number; migrations run automatically at deploy time before the new app version starts; must be backward-compatible (additive-only in the same release as code); migration scripts in db/migrations/; all runs logged with version, timestamp, duration, operator identity. No down scripts in production; reversibility achieved via blue-green. OPS-13 §4.6 also: removing a column requires two releases: first release removes code references, second release drops the column.
**Steps:** Integrate Flyway (or Liquibase) into the build system; migration scripts must live in db/migrations/ and be versioned as V<number>__<description>.sql.; Add a pre-deploy migration job to the CI/CD pipeline (called before pod rollout in each environment): flyway migrate -url=<db-url> -user=<db-user> -password=<secret>.; Configure a migration log table (flyway_schema_history) in each environment DB; verify migration version, timestamp, duration, and execution user are recorded.; Add a CI check that rejects any migration script that contains DROP COLUMN or RENAME COLUMN if the current release also modifies application code referencing that column (two-release rule enforcement as a lint check).; Test the migration pipeline: apply V001__create_test_table.sql to dev; verify flyway_schema_history records it correctly.
**Deliverable:** Flyway/Liquibase integration in CI/CD pipeline, pre-deploy migration job per environment, and a lint check enforcing the two-release rule for destructive column changes.
**Acceptance / logic checks:**
- After running the pipeline against dev, flyway_schema_history contains an entry for V001__create_test_table with version=1, checksum populated, installed_by set to the CI service account.
- The CI lint check fails if a migration script contains DROP COLUMN and the same PR contains Java/code changes referencing that column.
- The pre-deploy job fails and halts the deployment if a migration script contains a SQL syntax error.
- Migrations applied to int are identical to those applied to dev (same version numbers and checksums).
- Running flyway migrate a second time with the same scripts results in no new entries in flyway_schema_history (idempotent).
**Depends on:** 14.1-T24, 14.1-T06

### 14.1-T30 — Configure environment-specific database seed data for dev and int  _(40 min)_
**Context:** OPS-13 §2.4: dev uses seed data only (no real transaction or personal data); int uses synthetic merchants, synthetic partner credentials, and ZeroPay sandbox data. The seed data must populate: at least one Scheme (ZeroPay, status=ACTIVE), at least two Partners (GME Remit as LOCAL/KRW, SendMN as OVERSEAS/USD), and their Rules (GME Remit: KRW/KRW same-currency, service_charge=500 KRW; SendMN: cross-border, m_a=0.01, m_b=0.01, min combined=2%). Seed scripts must be idempotent (safe to re-run).
**Steps:** Create db/seeds/dev/001_seed_schemes.sql: INSERT INTO schemes (id, name, status) VALUES ('zeropay', 'ZeroPay', 'ACTIVE') ON CONFLICT DO NOTHING.; Create db/seeds/dev/002_seed_partners.sql: insert GME Remit (type=LOCAL, settlement_ccy=KRW) and SendMN (type=OVERSEAS, settlement_ccy=USD, prefunding_balance_usd=50000) with ON CONFLICT DO NOTHING.; Create db/seeds/dev/003_seed_rules.sql: insert Rule for GME Remit (direction=Domestic, collection_ccy=KRW, settle_a_ccy=KRW, settle_b_ccy=KRW, service_charge=500, m_a=0.0, m_b=0.0) and Rule for SendMN (direction=Inbound, collection_ccy=KRW, settle_a_ccy=USD, settle_b_ccy=USD, service_charge=500, m_a=0.01, m_b=0.01).; Run seeds as part of the post-migration step in dev and int CI deploy jobs.; Verify idempotency: running the seeds twice produces identical row counts (no duplicates).
**Deliverable:** Idempotent SQL seed scripts in db/seeds/dev/ populating ZeroPay scheme, GME Remit and SendMN partners, and their rules in dev and int environments.
**Acceptance / logic checks:**
- After seeding dev, SELECT COUNT(*) FROM schemes returns 1; SELECT name FROM schemes returns ZeroPay.
- SELECT type, settlement_ccy FROM partners returns rows: (LOCAL, KRW) for GME Remit and (OVERSEAS, USD) for SendMN.
- SendMN rule has m_a=0.01 and m_b=0.01 (combined=2.0%, satisfying the min 2% rule for cross-border); GME Remit rule has m_a=0.0 and m_b=0.0 (same-currency, 0% allowed).
- Running the seed scripts a second time does not change row counts (ON CONFLICT DO NOTHING ensures idempotency).
- No real partner API keys, SFTP credentials, or PII appear in any seed file.
**Depends on:** 14.1-T29

### 14.1-T31 — Configure staging environment data masking and monthly refresh procedure  _(50 min)_
**Context:** OPS-13 §2.4: staging uses an anonymised copy of the production schema with no real names and masked account numbers. Refresh cycle: monthly or on demand. The masked dataset must preserve referential integrity and realistic row counts for load testing, but all PII must be anonymised (names, account numbers, phone numbers). GDPR/PIPA constraints apply (SEC-09 §5). The refresh procedure must be documented and executable by an Ops DBA.
**Steps:** Write a data-masking script mask_staging_data.sh: (1) dump latest production schema-only snapshot; (2) apply masking transformations: partner.name -> 'PARTNER_<id>', merchant.name -> 'MERCHANT_<id>', transaction.collection_amount remains (not PII), customer_reference -> UUID v4 random.; Document the refresh procedure in RUNBOOK.md: stop staging services, restore masked dump, re-run Flyway migrations if schema version differs, restart services.; Schedule the refresh as a monthly CI/CD job or calendar event with a manual trigger option.; After a refresh, verify that SELECT name FROM merchants LIMIT 5 returns MERCHANT_<id> patterns and no real names.; Confirm that the staging DB row counts are within 10% of prod row counts for the transactions table (realistic load).
**Deliverable:** Data masking script mask_staging_data.sh, refresh procedure in RUNBOOK.md, and a verification query confirming all PII fields are anonymised after refresh.
**Acceptance / logic checks:**
- After running mask_staging_data.sh and restoring to staging, SELECT name FROM merchants returns only MERCHANT_<id> patterns (no real merchant names).
- SELECT customer_reference FROM transactions LIMIT 10 returns values matching UUID v4 format (no real customer identifiers).
- Referential integrity is intact after the masked restore: all transactions have valid partner_id and scheme_id foreign keys.
- The refresh procedure in RUNBOOK.md includes all steps and is executable in under 2 hours (documented estimated duration).
- The masking script exits with a non-zero code if it encounters a table that has PII fields not covered by the masking rules (fail-safe).
**Depends on:** 14.1-T07, 14.1-T04

### 14.1-T32 — Configure access control and RBAC for each environment tier  _(50 min)_
**Context:** OPS-13 §2.5: dev/int - any team member with repo access; staging - PR merge approval required for deployment, direct DB access restricted to DBA role; prod - deployments gated by change-approval workflow, production DB and secrets accessible only via break-glass (SEC-09 §6), all access logged and alerted. Kubernetes RBAC and secrets-manager IAM policies must enforce these rules. Four roles: developer, qa, dba, ops-admin.
**Steps:** Define four Kubernetes ClusterRoles: developer (read pods/logs in dev/int namespaces), qa (read pods/logs/exec in dev/int/staging), dba (exec into DB pods in dev/int/staging only), ops-admin (full access in dev/int/staging; read+exec in prod via break-glass procedure).; Bind roles to namespaces: developer bound to gmepay-dev and gmepay-int only; qa to dev/int/staging; dba to dev/int/staging only; ops-admin with prod break-glass procedure documented.; Configure secrets-manager IAM: developer role can read gmepay/dev/* and gmepay/int/*; staging access requires PR approval; prod requires break-glass approval workflow.; Create a break-glass runbook entry in RUNBOOK.md: request via change management system, approve by two ops-admins, access expires after 1 hour, all actions logged.; Test: attempt to kubectl exec into a prod pod as a developer role; confirm permission denied.
**Deliverable:** Kubernetes RBAC ClusterRoles and bindings for four roles across four namespaces, with break-glass runbook for prod access.
**Acceptance / logic checks:**
- kubectl auth can-i exec pods -n gmepay-prod as developer service account returns no.
- kubectl auth can-i get pods -n gmepay-dev as developer service account returns yes.
- DBA role can exec into the DB pod in staging (kubectl auth can-i exec pods -n gmepay-staging as dba returns yes) but not in prod (returns no).
- Break-glass runbook in RUNBOOK.md includes: request step, dual-approval step, 1-hour expiry, and audit log confirmation.
- A simulated break-glass access event generates a log entry in the secrets-manager audit trail with operator identity and timestamp.
**Depends on:** 14.1-T10, 14.1-T14, 14.1-T27

### 14.1-T33 — Provision observability stack: Prometheus and Grafana for all environments  _(60 min)_
**Context:** OPS-13 §7.1 requires RED metrics (api_request_rate, api_error_rate, api_latency_p50/p95/p99) and USE metrics for infrastructure. OPS-13 §7.4 defines six dashboards: Operations Overview, API SLO, Batch Health, Prefunding Monitor, Revenue, Security. All metrics emitted by services in Prometheus format. Prometheus scrapes from hub-api, batch-worker, admin-system, webhook-dispatcher. Grafana visualizes. Per-environment deployment: dev/int can use a lightweight single-node; staging/prod should use persistent storage.
**Steps:** Deploy Prometheus to the Management Zone (Kubernetes namespace gmepay-<env>-monitoring) for dev and int as lightweight deployments; scrape targets: hub-api, batch-worker, admin-system, webhook-dispatcher service endpoints /metrics.; Deploy Grafana with default admin credentials injected from secrets manager (gmepay/<env>/grafana/admin-password).; Create the six Grafana dashboards as JSON dashboard definitions in infra/grafana/dashboards/: ops-overview.json, api-slo.json, batch-health.json, prefunding-monitor.json, revenue.json, security.json (skeleton panels - full metric queries are a separate ticket).; For staging and prod: configure Prometheus with persistent volume (30-day metric retention for staging, 90-day for prod).; Test: verify Prometheus scrapes hub-api /metrics and api_request_rate gauge is visible in Grafana.
**Deliverable:** Prometheus and Grafana deployments in Management Zone for all four environments, with six skeleton Grafana dashboard JSON files and confirmed metric scraping from hub-api.
**Acceptance / logic checks:**
- Prometheus UI at http://prometheus.management.gmepay-dev/ (or equivalent internal URL) shows hub-api as a scrape target with state=UP.
- api_request_rate metric appears in Grafana Explore for the dev environment after at least one request to /health.
- All six dashboard JSON files exist in infra/grafana/dashboards/ and can be imported into Grafana without errors.
- Grafana admin password is sourced from secrets manager (not hardcoded in the Deployment manifest).
- Prod Prometheus PersistentVolumeClaim shows storage provisioned with at least 50Gi and a 90-day retention policy configured.
**Depends on:** 14.1-T14, 14.1-T12

### 14.1-T34 — Configure OpenTelemetry distributed tracing for all services  _(60 min)_
**Context:** OPS-13 §7.3: OpenTelemetry instrumentation across all services. trace_id generated at load balancer (W3C TraceContext format) and propagated through every service call, DB query, and batch job step. The trace covers the 8-step transaction trail: rate_quote_issued, payment_initiated, prefund_deducted, scheme_request_sent, scheme_response_received, transaction_committed, webhook_dispatched, webhook_delivered. Tracing backend: Jaeger or AWS X-Ray or Tempo. Structured logs must include trace_id and span_id fields (OPS-13 §7.2).
**Steps:** Deploy Jaeger (or equivalent) to the Management Zone of dev and int; for staging/prod use a managed backend or Jaeger with persistent storage.; Configure OpenTelemetry Collector as a DaemonSet or sidecar in each namespace; set OTLP exporter endpoint to Jaeger.; Instrument hub-api application (Java) with OpenTelemetry SDK: add trace_id and span_id to MDC (mapped diagnostic context) so all log lines include them.; Define the 8 named spans in code: rate_quote_issued, payment_initiated, prefund_deducted, scheme_request_sent, scheme_response_received, transaction_committed, webhook_dispatched, webhook_delivered.; Verify end-to-end trace: make a POST /v1/rates request and confirm all spans appear in Jaeger UI for that trace_id.
**Deliverable:** OpenTelemetry SDK instrumentation in hub-api with 8 named spans, OpenTelemetry Collector DaemonSet, and Jaeger backend with a confirmed end-to-end trace visible in the UI.
**Acceptance / logic checks:**
- A POST /v1/rates request produces a trace in Jaeger with trace_id and at least the span rate_quote_issued visible.
- Hub-api log lines contain trace_id and span_id fields as JSON (verify with kubectl logs <hub-api-pod> | head -5 | python -m json.tool and inspect fields).
- The 8-step span names (rate_quote_issued through webhook_delivered) appear as span names in the Jaeger trace for a complete payment flow.
- trace_id from the API response X-Trace-Id header matches the trace_id in Jaeger (W3C TraceContext propagation working).
- OpenTelemetry Collector DaemonSet is running in all four environment namespaces with no CrashLoopBackOff.
**Depends on:** 14.1-T33, 14.1-T12

### 14.1-T35 — Configure structured logging and log aggregation for all environments  _(55 min)_
**Context:** OPS-13 §7.2: all services emit JSON-structured logs with required fields: timestamp (ISO-8601 UTC), level, service, env, trace_id, span_id, partner_id, transaction_id, event, message. In prod: log structured references (IDs) only for sensitive fields; do not log monetary amounts in unencrypted streams. Log level: dev/int=DEBUG, staging/prod=INFO (DB driver=WARN in prod). Logs must be shipped to a centralised aggregator (SEC-09 references SIEM). Log aggregation stack: e.g., Fluent Bit + Elasticsearch/OpenSearch or cloud-native log service.
**Steps:** Deploy Fluent Bit (or cloud log agent) as a DaemonSet in each environment namespace; configure it to parse JSON log lines and forward to the central log store.; Configure Fluent Bit filter to detect log lines missing required fields (timestamp, level, service, env, trace_id) and tag them as MALFORMED_LOG.; Set log level env vars per the config matrix: LOG_LEVEL=DEBUG for dev/int, LOG_LEVEL=INFO for staging/prod (see 14.1-T15).; Configure a prod log filter that redacts monetary amount fields (collection_amount, prefund_balance) by replacing values with REDACTED before shipping to the log store.; Verify: trigger a POST /v1/rates in dev; confirm the resulting log line appears in the log store with all required JSON fields.
**Deliverable:** Fluent Bit DaemonSet log aggregation for all four environments with JSON field validation, log level configuration per environment, and prod monetary field redaction.
**Acceptance / logic checks:**
- A log line from hub-api in dev contains all ten required JSON fields (timestamp, level, service, env, trace_id, span_id, partner_id, transaction_id, event, message) - verified by querying the log store.
- In prod, searching logs for a payment transaction does NOT return any line containing collection_amount or prefund_balance with a numeric value (field is REDACTED).
- A deliberately malformed log line (missing trace_id) is tagged MALFORMED_LOG in Fluent Bit output.
- LOG_LEVEL=DEBUG in dev means DEBUG-level log entries appear in the log store; LOG_LEVEL=INFO in prod means DEBUG entries are absent.
- Fluent Bit DaemonSet pods are in Running state across all four namespaces with no restart loops.
**Depends on:** 14.1-T34, 14.1-T15

### 14.1-T36 — Configure alert rules for API SLO, prefunding, and batch window breaches  _(60 min)_
**Context:** OPS-13 §7.5 defines the full alert catalog with PagerDuty severity levels P1-P4. Key rules to configure: API p95 latency > 500ms for 5 min = P2; 5xx error rate > 1% over 5 min = P1; prefunding balance < $10000 per OVERSEAS partner = P2; prefunding balance < $2000 = P1; ZP0011/ZP0021 not submitted by 01:45 KST = P1; ZP0012/ZP0022 not received by 05:30 KST = P1; ZP0061 not submitted by 05:15 KST = P1; pool identity assertion failure = P1 (tolerance 0.01 USD). Alert channels: P1 = PagerDuty + Ops Slack; P2 = Ops Slack.
**Steps:** Define Prometheus alerting rules in infra/alerting/rules.yaml covering at minimum: API p95 latency breach (p2), 5xx error spike (p1), prefunding low (p2), prefunding critical (p1), pool identity failure (p1).; Define batch window alerting rules with KST-window cron expressions: JOB-ZP-01/02 not submitted alert fires at 01:45 KST if job_run_status{job=JOB-ZP-01} != success (p1).; Configure Alertmanager routing: severity=p1 routes to PagerDuty + Ops Slack; severity=p2 routes to Ops Slack only; severity=p3 routes to Ops email.; Apply rules to staging and prod environments (dev/int can use a simplified alert set).; Test: set prefunding balance to $500 in staging; confirm P1 alert fires within 2 minutes and reaches the Ops Slack channel.
**Deliverable:** Prometheus alert rules YAML (infra/alerting/rules.yaml) covering API SLO, prefunding, batch window, and pool identity breaches, with Alertmanager routing to PagerDuty and Ops Slack.
**Acceptance / logic checks:**
- Prefunding balance set to $500 triggers a P1 alert within 2 minutes; the alert appears in Ops Slack with severity=p1 and the partner name.
- API p95 latency sustained above 500ms for 5 minutes triggers a P2 alert in Ops Slack.
- Pool identity failure alert fires when a test transaction has collection_usd - collection_margin_usd - payout_margin_usd != payout_usd_cost by more than $0.01.
- P1 alerts route to both PagerDuty and Ops Slack; P2 alerts route to Ops Slack only (verify Alertmanager config routes).
- Alerting rules YAML is valid Prometheus YAML (promtool check rules infra/alerting/rules.yaml returns OK).
**Depends on:** 14.1-T33, 14.1-T15

### 14.1-T37 — Configure backup policy and WAL archiving for prod PostgreSQL  _(55 min)_
**Context:** OPS-13 §9.1: PostgreSQL continuous WAL archiving + daily full snapshot; retained 30 days full, 7 years WAL. WAL archives written to object storage in a separate AZ from the primary DB. Object storage for batch files is geo-redundant with 7-year retention (provisioned in 14.1-T09). Restore testing: monthly (OPS-13 §9.2) - restore to dedicated restore-test environment, run integrity suite, document result, destroy restore-test environment after test. RTO target for primary DB failure: < 10 min (OPS-13 §9.3).
**Steps:** Configure continuous WAL archiving on the prod PostgreSQL primary: archive_command writes WAL segments to a separate-AZ object storage path s3://<wal-archive-bucket>/gmepay/prod/wal/.; Configure daily full snapshot via managed service automated backup or pg_basebackup cron; retain for 30 days.; Create a monthly CI/CD pipeline job restore-test: spins up a restore-test-env namespace, restores latest snapshot, applies WAL to most recent consistent point, runs SELECT COUNT(*) FROM transactions and compares to a known checkpoint, destroys the environment, records pass/fail in the DR log.; Verify the WAL archive bucket is in a different AZ than the primary DB (check resource placement).; Document the manual restore procedure in RUNBOOK.md (steps from OPS-13 §9.4.1).
**Deliverable:** Prod PostgreSQL WAL archiving to a separate-AZ object storage bucket, daily snapshot configuration, monthly restore-test CI job, and manual restore procedure in RUNBOOK.md.
**Acceptance / logic checks:**
- WAL archive object storage path s3://<wal-archive-bucket>/gmepay/prod/wal/ receives a new file within 5 minutes of a committed transaction (verify by checking bucket object count before and after a write).
- WAL archive bucket is in a different AZ than the prod primary DB (verify AZ attribute on both resources).
- Daily snapshot backup shows in the managed service backup list with retention=30 days.
- Monthly restore-test CI job successfully completes a test restore and records the result; the restore-test namespace is destroyed after the job completes.
- RUNBOOK.md contains the step-by-step pg_ctl promote procedure from OPS-13 §9.4.1.
**Depends on:** 14.1-T07, 14.1-T09

### 14.1-T38 — Provision DR environment and configure full-region failover runbook  _(60 min)_
**Context:** OPS-13 §9.4.2: DR requires a pre-provisioned environment in a secondary region. Failover: update DNS to DR load balancer, restore PostgreSQL from WAL archive in secondary region, confirm object storage (batch files) accessible in DR region (geo-redundant bucket from 14.1-T09), update secrets in DR secrets manager, verify batch worker reaches ZeroPay SFTP from DR region. RTO < 4 hours for full region failure (OPS-13 §9.3). Annual DR drill required (OPS-13 §9.5).
**Steps:** Provision a minimal DR environment in the secondary cloud region: load balancer (no traffic until failover), hub-api Deployment (0 replicas until failover), PostgreSQL instance (empty; populated from WAL restore during failover), secrets manager paths mirrored.; Write a DR failover script failover-to-dr.sh: (1) update DNS to DR load balancer, (2) trigger WAL restore from geo-redundant bucket to DR PostgreSQL, (3) update DR secrets with production values (manual step requiring ops-admin), (4) scale up hub-api replicas in DR, (5) run smoke tests.; Document the DR drill schedule in RUNBOOK.md: annual drill, fail over to DR in non-production window, run smoke tests and simulated batch job, measure actual RTO, update runbook.; Create a DR log table or record sheet for documenting drill results: date, RTO achieved, test outcome, action items.; Test by running failover-to-dr.sh against a staging-equivalent DR environment (not production).
**Deliverable:** DR environment IaC in secondary region, failover-to-dr.sh script, and DR drill procedure with log template in RUNBOOK.md.
**Acceptance / logic checks:**
- failover-to-dr.sh completes the DR environment activation steps in sequence; a dry-run against staging-equivalent DR exits with code 0.
- DNS update step in the script changes the A/CNAME record for the API domain to point to the DR load balancer endpoint.
- After WAL restore step, SELECT COUNT(*) FROM transactions in DR PostgreSQL matches the production count at time of WAL cutoff (within expected lag).
- RUNBOOK.md DR drill procedure includes: annual schedule, steps to measure RTO, and the log template with fields date, RTO_minutes, test_outcome, action_items.
- The geo-redundant object storage bucket (from 14.1-T09) is accessible from the DR region (test with aws s3 ls or equivalent from the DR network).
**Depends on:** 14.1-T37, 14.1-T26

### 14.1-T39 — Execute go-live infrastructure pre-check checklist (Phase 3 production readiness)  _(60 min)_
**Context:** OPS-13 §12.1 defines the pre-go-live checklist that must be signed off by Release Manager, Engineering Lead, Ops Lead, and Security Lead before live traffic is accepted. This ticket covers the infrastructure checklist items: §12.1.1 (all production infrastructure provisioned and hardened, multi-AZ confirmed, LB/WAF/TLS validated, auto-scaling tested at 500 TPS, DR drill completed, backup tested in preceding 30 days) and §12.1.3 (ZeroPay SFTP credentials for prod loaded, SFTP connectivity test to production server successful, all ZP00xx file types validated, 한결원 confirmed receipt of one full batch cycle in test, batch scheduler on KST production windows, batch alerting tested).
**Steps:** Run the infrastructure verification script verify-prod-infra.sh: checks multi-AZ pod distribution, LB health, TLS cert validity (must be >90 days to expiry), WAF rule active, DR drill completed in last 365 days, backup tested in last 30 days.; Run the ZeroPay connectivity check: sftp -i prod-sftp-key user@<zeropay-prod-host> (connect-only test; do not upload files).; Verify KST batch scheduler is active in prod: kubectl get cronjobs -n gmepay-prod shows correct cron expressions for JOB-ZP-01 through JOB-ZP-13.; Verify all six Grafana dashboards are readable and show live data (not empty panels).; Create a checklist record in the change management system with each §12.1.1 and §12.1.3 item ticked and sign-off by all four roles.
**Deliverable:** Executed infrastructure pre-go-live checklist with all §12.1.1 and §12.1.3 items verified, signed-off in the change management system, and a passing verify-prod-infra.sh script output.
**Acceptance / logic checks:**
- verify-prod-infra.sh exits with code 0 and outputs PASS for: multi-AZ, TLS >90 days, WAF active, DR drill <365 days, backup <30 days.
- sftp connectivity to 한결원 production SFTP exits with code 0 (authenticated connection).
- kubectl get cronjobs -n gmepay-prod shows all 13 batch job CronJobs with correct KST cron expressions (e.g., JOB-ZP-01 schedule = 0 17 * * * for 02:00 KST = 17:00 UTC).
- All six Grafana dashboards load without errors and the Operations Overview dashboard shows live api_request_rate data.
- Change management system record shows all §12.1.1 and §12.1.3 items checked with sign-off from Release Manager, Engineering Lead, Ops Lead, and Security Lead.
**Depends on:** 14.1-T28, 14.1-T38, 14.1-T36, 14.1-T35

### 14.1-T40 — Validate environment parity: confirm identical container images deploy across all tiers  _(45 min)_
**Context:** OPS-13 §2.3: all environments deploy the same container images from the same artifact registry; only runtime configuration (env vars, secrets) differs. Database schema migrations run identically across all tiers. This validation ticket confirms parity by checking image digests across dev, int, staging, and prod for a given release tag, and confirming migration checksums in flyway_schema_history are identical across environments.
**Steps:** After a release pipeline completes staging promotion (Stage 7), capture the image digest (SHA256) for each of the five services from the registry.; Compare image digests between dev, int, staging, and prod namespaces using kubectl get deployment <svc> -n gmepay-<env> -o jsonpath='{.spec.template.spec.containers[0].image}' and confirm the digest portion is identical.; Query flyway_schema_history in dev, int, staging, and prod: SELECT version, checksum FROM flyway_schema_history ORDER BY version; confirm the version and checksum lists are identical.; Generate a parity report as a pipeline artifact: parity-report.json containing fields: release_tag, per-service image digests, migration versions, and a pass/fail verdict.; Fail the pipeline if any digest or migration checksum mismatch is detected.
**Deliverable:** Automated parity check step in CI/CD pipeline that compares image digests and migration checksums across all four environments and outputs parity-report.json.
**Acceptance / logic checks:**
- After a successful release, parity-report.json shows identical image digests for hub-api across dev, int, staging, and prod (exact SHA256 match).
- flyway_schema_history version and checksum lists are identical across all four environments (no extra migrations in dev or missing migrations in staging).
- If an environment has a different image tag (e.g., int is one commit behind), the parity check fails with a non-zero exit code.
- parity-report.json is available as a downloadable CI/CD pipeline artifact after each run.
- The parity check runs as part of Stage 7 (before manual approval gate), ensuring parity is confirmed before prod promotion.
**Depends on:** 14.1-T29, 14.1-T22, 14.1-T23


## WBS 14.2 — CI/CD pipeline
### 14.2-T01 — Define CI/CD pipeline YAML schema and stage contracts  _(45 min)_
**Context:** GMEPay+ uses an 8-stage pipeline per OPS-13 §4.1: (1) Build/lint/type-check, (2) Unit tests >=80% coverage, (3) SAST+dependency+secrets scan, (4) Integration tests with ephemeral env, (5) Artifact: tag image <git-sha>+<branch>+<semver> and sign, (6) Deploy to int + smoke tests, (7) Deploy to staging + manual approval gate, (8) Deploy to prod + release manager sign-off. Branching: feature/<ticket> -> main (auto-deploy int), release/<version> -> staging, hotfix/<ticket> -> prod via fast-track. All stages are gates: failure halts promotion.
**Steps:** Create pipeline-schema.yaml (or equivalent IaC schema file) defining all 8 stage names, their gate conditions, input/output artifacts, and environment targets; Document gate rules: int=fully automated, staging=stakeholder sign-off required, prod=Release Manager approval + change record + QA sign-off; Define branch-to-environment mapping table: feature->dev(manual), main->int(auto), release/*->staging(auto on cut), hotfix/*->prod(fast-track: 2 senior + on-call); Define image tag format: <git-sha>-<branch-slug>-<semver> and sign requirement; Add comments for each stage referencing OPS-13 section
**Deliverable:** pipeline-schema.yaml (or .gitlab-ci.yml / Jenkinsfile skeleton) with all 8 stages stubbed, gate conditions annotated, and branch-mapping table
**Acceptance / logic checks:**
- Schema file contains exactly 8 named stages in order matching OPS-13 §4.1
- Gate condition for prod stage requires 3 explicit approvals: Release Manager, QA sign-off, change record ID
- Branch-to-env mapping correctly maps main->int (auto), release/*->staging (auto), hotfix/*->prod (fast-track)
- Image tag format includes all three components: git-sha, branch-slug, semver
- File is parseable by the target CI tool without errors

### 14.2-T02 — Implement Stage 1: compile, lint, and type-check step  _(40 min)_
**Context:** Stage 1 of the GMEPay+ CI pipeline (OPS-13 §4.1) must compile the Java application, run the linter, and run type-checks. Gate to Stage 2: zero lint errors and zero type errors. The project is built in Java + PostgreSQL (see memory/remit-platform-stack.md). The build tool is Maven or Gradle. Lint includes Checkstyle or SpotBugs. This stage runs on every push to every branch.
**Steps:** Add a CI job named 'build' that runs: compile (mvn compile or ./gradlew compileJava), checkstyle (mvn checkstyle:check), SpotBugs (mvn spotbugs:check); Configure the job to fail fast on first lint/type error and surface the error line number in CI output; Ensure the job caches the dependency download layer (e.g. ~/.m2 or ~/.gradle) to keep runtime under 5 minutes; Set the gate: job exit code 0 = proceed; non-zero = block all downstream stages; Add a job-level timeout of 10 minutes
**Deliverable:** CI job definition 'build' (in pipeline YAML) that compiles, lints, and type-checks the Java codebase with dependency caching
**Acceptance / logic checks:**
- Introducing a deliberate unused-import lint error causes the build stage to fail and all subsequent stages to be skipped
- A clean commit on a feature branch passes the build stage within 8 minutes including cache warm-up
- Cache hit on second run reduces compile+lint runtime by at least 30%
- Zero-tolerance gate: even a single checkstyle warning at ERROR severity blocks promotion
**Depends on:** 14.2-T01

### 14.2-T03 — Implement Stage 2: unit and component test gate (>=80% coverage)  _(40 min)_
**Context:** Stage 2 of the CI pipeline (OPS-13 §4.1) runs unit and component tests and enforces a coverage gate of >=80% line/branch coverage. Gate to Stage 3: all tests pass AND coverage >=80%. Test framework: JUnit 5. Coverage: JaCoCo. Tests include rate-engine unit tests (RATE-04 formulas) and prefunding atomic deduction tests. If coverage falls below 80%, the stage fails and blocks artifact creation.
**Steps:** Add CI job 'unit-test' that runs: mvn test (or ./gradlew test) with JaCoCo plugin enabled; Configure JaCoCo to generate an XML/HTML report and fail the build if overall line coverage < 80%; Upload test results and coverage report as CI artifacts for review; Set job dependency: only runs after 'build' job passes; Add timeout: 15 minutes
**Deliverable:** CI job 'unit-test' with JaCoCo coverage gate at 80%, uploading test-results.xml and jacoco-report/ as artifacts
**Acceptance / logic checks:**
- Deleting a test class that covers a key module drops coverage below 80% and fails the stage
- All existing unit tests pass on the main branch before this ticket is merged
- Coverage report artifact is downloadable from the CI UI after the job completes
- Stage 2 does not start if Stage 1 failed (dependency enforced in pipeline YAML)
- A test that asserts rate-engine pool identity (collection_usd - collection_margin_usd - payout_margin_usd == payout_usd_cost within 0.01 USD tolerance) is present and passing
**Depends on:** 14.2-T02

### 14.2-T04 — Implement Stage 3: SAST, dependency CVE scan, and secrets leak scan  _(50 min)_
**Context:** Stage 3 (OPS-13 §4.1, §4.4) runs three security scans: (a) SAST via SpotBugs Security plugin or OWASP Find Security Bugs, (b) dependency vulnerability scan via OWASP Dependency-Check or Trivy, (c) secrets leak scan via detect-secrets or truffleHog. Gate: no critical/high CVEs and no secrets detected. SEC-09 mandates that all security-relevant findings are forwarded to the SIEM in JSON format. Stage fails on any critical or high finding.
**Steps:** Add CI job 'security-scan' with three parallel sub-steps: SAST (./gradlew spotbugsMain with security ruleset), Dependency-Check (dependency-check --project gmepayplus --scan . --failOnCVSS 7), secrets scan (detect-secrets scan . --all-files); Configure Dependency-Check to fail on CVSS score >= 7.0 (high/critical); Configure detect-secrets to use a baseline file .secrets.baseline; any NEW secret pattern triggers failure; Collect all scan reports as CI artifacts (dependency-check-report.html, spotbugs-report.xml, secrets-scan.json); Gate: job fails if any sub-step returns non-zero exit code
**Deliverable:** CI job 'security-scan' running SAST + Dependency-Check + secrets scan in parallel, with artifact upload and hard gate on critical/high findings
**Acceptance / logic checks:**
- Adding a test file containing an AWS key pattern (AKIA...) causes secrets scan to fail and block Stage 4
- A dependency with a known critical CVE (CVSS>=9) introduced into pom.xml causes Dependency-Check to fail
- SAST report is available as a downloadable artifact within 10 minutes of job start
- Stage 3 does not start if Stage 2 failed
- Clean baseline passes all three scans and proceeds to Stage 4
**Depends on:** 14.2-T03

### 14.2-T05 — Define ephemeral integration-test environment spec (Docker Compose / Helm values)  _(50 min)_
**Context:** Stage 4 (OPS-13 §4.1) spins up an ephemeral environment with the application, a PostgreSQL instance, Redis, and a ZeroPay SFTP stub/mock. The env must be disposable (created per CI run, torn down after). All environments deploy the same container images (OPS-13 §2.3). The ephemeral env uses dev-tier config: DB pool=5, rate-quote-TTL=300s, ZeroPay SFTP=stub/mock, log-level=DEBUG (OPS-13 §5.3). Secrets are injected from gmepay/ci/* paths (read-only CI access per OPS-13 §4.7).
**Steps:** Write docker-compose.integration.yml (or Helm values file) with services: hub-api, admin-service, postgres:15-alpine, redis:7-alpine, sftp-stub (e.g. atmoz/sftp image with test key); Set environment variables per OPS-13 §5.3 dev tier: DB_POOL_SIZE=5, RATE_QUOTE_TTL=300, LOG_LEVEL=DEBUG, ZEROPAY_SFTP_HOST=sftp-stub; Add a health-check wait loop: wait until hub-api /health returns 200 before running tests (max 90s); Document the teardown step: docker-compose down -v after test run; Set secrets injection pattern: gmepay/ci/<service>/<secret-name> with read-only CI token
**Deliverable:** docker-compose.integration.yml (or equivalent) defining the full ephemeral integration stack with health checks, dev-tier config, and teardown script
**Acceptance / logic checks:**
- docker-compose up starts all 5 services and hub-api /health returns 200 within 90 seconds
- All environment variables match OPS-13 §5.3 dev tier values (DB_POOL_SIZE=5, RATE_QUOTE_TTL=300, LOG_LEVEL=DEBUG)
- docker-compose down -v removes all volumes and containers (no state leak between runs)
- SFTP stub is reachable at sftp-stub:22 from the hub-api container
- Secrets follow path convention gmepay/ci/<service>/<secret-name>; no plaintext credentials in the compose file
**Depends on:** 14.2-T01

### 14.2-T06 — Implement Stage 4: integration test execution in ephemeral environment  _(45 min)_
**Context:** Stage 4 (OPS-13 §4.1) spins up the ephemeral environment defined in T05, runs the integration test suite, then tears down. Gate: all integration tests pass. Integration tests cover end-to-end API flows: POST /v1/rates (rate quote), POST /v1/payments (MPM), prefunding deduction atomicity, and health check endpoints. Tests use the dev-tier ephemeral env from T05. Failure blocks Stage 5 (artifact creation).
**Steps:** Add CI job 'integration-test' that: (a) runs docker-compose -f docker-compose.integration.yml up -d, (b) waits for health check, (c) runs mvn verify -P integration-tests (or ./gradlew integrationTest), (d) runs docker-compose down -v; Configure the job to capture integration test results as JUnit XML artifacts; Set job timeout: 20 minutes; Ensure the job depends on 'security-scan' passing; Pass the git-sha as IMAGE_TAG env var so the compose file pulls the correct freshly-built image
**Deliverable:** CI job 'integration-test' that orchestrates ephemeral env lifecycle and runs integration test suite, with test-results artifact
**Acceptance / logic checks:**
- A failing integration test (e.g. POST /v1/rates returns wrong status) blocks Stage 5 artifact creation
- All containers are removed (docker ps shows none from this run) after job completion regardless of test outcome
- Test results XML artifact is present after job run
- Stage 4 does not run if Stage 3 (security scan) failed
- Passing run completes within 18 minutes end-to-end
**Depends on:** 14.2-T04, 14.2-T05

### 14.2-T07 — Implement Stage 5: container image build, tag, sign, and push  _(45 min)_
**Context:** Stage 5 (OPS-13 §4.1) builds the Docker image, tags it with <git-sha>-<branch-slug>-<semver>, pushes to the artifact registry, and signs the image. Image signing ensures only CI-produced images are deployed (SEC-09 requirement). No config files or secrets are bundled in the image (OPS-13 §4.7). The registry is a cloud artifact registry (e.g. AWS ECR, GCR, or Azure ACR). Signing uses cosign or similar.
**Steps:** Add CI job 'build-image' that runs: docker build -t gmepayplus/hub-api:<git-sha>-<branch-slug>-<semver> .; Validate no secrets or .env files are included: run docker run --rm <image> find / -name '.env' should return empty; Push image to artifact registry: docker push <registry-host>/gmepayplus/hub-api:<tag>; Sign the image with cosign: cosign sign <image-digest> using the CI signing key from gmepay/ci/cosign-key; Record the image digest and tag in a pipeline artifact file (image-manifest.json) for downstream stages
**Deliverable:** CI job 'build-image' producing a signed, tagged image in the artifact registry plus image-manifest.json with digest and tag
**Acceptance / logic checks:**
- Image tag matches format <git-sha>-<branch-slug>-<semver> (regex: ^[0-9a-f]{7,40}-[a-z0-9-]+-[0-9]+\.[0-9]+\.[0-9]+$)
- cosign verify passes against the pushed image using the CI public key
- docker inspect image shows no .env, credentials.json, or secrets-manager config files in any layer
- image-manifest.json artifact contains fields: image_digest, image_tag, build_timestamp, git_sha
- Stage 5 only runs if all of Stages 1-4 have passed
**Depends on:** 14.2-T06

### 14.2-T08 — Implement Stage 6: automated deploy to int environment with smoke tests  _(50 min)_
**Context:** Stage 6 (OPS-13 §4.1, §2.1) auto-deploys the signed image to the integration environment (E2) on every merge to main. The int env uses the 한결원 ZeroPay test SFTP, DB pool=20, log-level=DEBUG. Smoke tests verify /health, a rate quote, and a prefunding balance check. Stage gate: smoke tests pass. No human approval is required for int (OPS-13 §4.3). Deployment uses kubectl rollout or equivalent, pulling the image tag from image-manifest.json.
**Steps:** Add CI job 'deploy-int' triggered on merge to main, reading IMAGE_TAG from image-manifest.json; Run kubectl set image deployment/hub-api hub-api=<registry>/<tag> -n gmepay-int (or Helm upgrade); Wait for rollout: kubectl rollout status deployment/hub-api -n gmepay-int --timeout=300s; Run smoke test script smoke-tests/int.sh: (a) GET /health -> 200, (b) POST /v1/rates with GME Remit domestic payload -> 200 with collection_amount field, (c) GET /v1/partners/{sendmn_id}/balance -> 200; Fail the stage and alert if smoke tests do not pass within 5 minutes of deploy
**Deliverable:** CI job 'deploy-int' that deploys to int environment and runs smoke tests, failing the stage on smoke test failure
**Acceptance / logic checks:**
- Deploying a build where /health returns 500 fails the smoke test and the job is marked failed
- The int environment uses gmepay/int/* secrets (not dev or prod paths)
- kubectl rollout status confirms all pods are Running before smoke tests execute
- Smoke test for rate quote confirms response body contains collection_amount (any numeric value) for a GME Remit domestic request
- Stage 6 runs automatically on merge to main with no manual approval
**Depends on:** 14.2-T07

### 14.2-T09 — Implement Stage 7: automated deploy to staging with manual approval gate  _(50 min)_
**Context:** Stage 7 (OPS-13 §4.1, §4.3) auto-deploys to staging (E3) when a release/* branch is cut from main. After deployment and automated test suite, a manual approval gate blocks promotion to prod. Staging uses production-like config: DB pool=80, rate-quote-TTL=per-partner-config, batch schedule=KST production windows, log-level=INFO (OPS-13 §5.3). A stakeholder sign-off is required in the CI tool before Stage 8 can proceed. Direct DB access in staging is restricted to DBA role (OPS-13 §2.5).
**Steps:** Add CI job 'deploy-staging' triggered on push to release/* branch; Deploy using kubectl set image or Helm upgrade in gmepay-staging namespace with staging secrets (gmepay/staging/*); Run extended test suite: load test (locust or k6, 500 TPS for 5 min) and UAT smoke tests; After test suite passes, create a manual approval gate in the CI tool (GitLab environment approval or GitHub environment protection rule) requiring stakeholder sign-off; Set gate timeout: 48 hours (after which the release branch expires without prod promotion)
**Deliverable:** CI job 'deploy-staging' with automated test suite and a manual approval gate in the CI tool, blocking Stage 8 until approved
**Acceptance / logic checks:**
- Pushing to release/1.2.0 triggers the staging deploy without any manual action
- After deploy, the staging environment DB_POOL_SIZE is 80 and LOG_LEVEL is INFO (verified from service /debug/env endpoint or config endpoint)
- The manual approval gate is visible in the CI UI and prod deploy cannot proceed until approval is granted
- Approval gate expires after 48 hours: an unreviewed release branch does not auto-promote
- Load test run artifact (locust/k6 HTML report) is attached to the CI run
**Depends on:** 14.2-T08

### 14.2-T10 — Implement Stage 8: human-gated deploy to production with change record  _(55 min)_
**Context:** Stage 8 (OPS-13 §4.1, §4.3, §8.1) is the prod deployment. It requires: (1) Release Manager approval in CI tool, (2) change record ID entered as a CI variable, (3) QA sign-off already captured in Stage 7 gate. The deploying user identity must be logged. Post-deploy: run smoke tests for ~3 minutes (/health, rate quote, prefunding balance check). If smoke tests fail within 5 minutes, trigger automated rollback to previous image tag (OPS-13 §4.4). Operator runs: kubectl rollout restart (or Helm upgrade) in gmepay-prod namespace.
**Steps:** Add CI job 'deploy-prod' with environment protection requiring Release Manager role approval and a mandatory CHANGE_RECORD_ID variable input; Log deploying user identity and CHANGE_RECORD_ID to the deployment audit log table: INSERT INTO deployment_audit (git_sha, image_tag, operator_id, change_record_id, deployed_at) VALUES (...); Run prod deploy: kubectl set image deployment/hub-api hub-api=<registry>/<tag> -n gmepay-prod; Run post-deploy smoke tests (smoke-tests/prod.sh): GET /health, POST /v1/rates (GME Remit domestic), GET /v1/partners/{id}/balance — must all pass within 5 minutes; If smoke tests fail: auto-trigger rollback job (deploy previous image tag), create ROLLBACK entry in deployment_audit
**Deliverable:** CI job 'deploy-prod' with release-manager approval gate, change record logging, prod smoke tests, and auto-rollback on smoke failure
**Acceptance / logic checks:**
- Attempting to run deploy-prod without Release Manager role in CI tool results in access denied
- An empty or missing CHANGE_RECORD_ID variable causes the job to fail at validation before deploying
- deployment_audit table has a row with correct git_sha, operator_id, change_record_id, and deployed_at after a successful deploy
- Smoke test failure within 5 minutes triggers auto-rollback and inserts a ROLLBACK row in deployment_audit
- Post-deploy smoke test for GME Remit rate quote returns collection_amount = target_payout + 500 (KRW) for a KRW/KRW domestic request (same-currency short-circuit: collection_amount = target_payout + service_charge)
**Depends on:** 14.2-T09

### 14.2-T11 — Implement automated rollback: redeploy previous image on smoke test failure  _(45 min)_
**Context:** OPS-13 §4.4 specifies two rollback mechanisms: (1) automated rollback if post-deployment smoke tests fail within 5 minutes — pipeline redeploys the previous image tag; (2) manual rollback via the §8.2 runbook. The previous image tag must be retrievable from the deployment_audit table (T10). Database migrations are forward-only; if a migration was applied with the bad deploy, the rollback deploys old code against the new schema (old code must be backward-compatible per OPS-13 §4.6 additive-only rule). Rollback requires two engineers approval for prod (OPS-13 §8.2).
**Steps:** Create CI job 'rollback-prod' that reads the previous successful image tag from deployment_audit: SELECT image_tag FROM deployment_audit WHERE env='prod' AND status='SUCCESS' ORDER BY deployed_at DESC LIMIT 1 OFFSET 1; Require two-engineer approval in CI tool before executing the rollback deploy; Run kubectl set image with the retrieved prior image tag; Insert a ROLLBACK row into deployment_audit with rollback_reason field; Run smoke tests after rollback to confirm baseline is restored; alert if smoke tests fail again (P1 escalation)
**Deliverable:** CI job 'rollback-prod' that retrieves prior image tag from deployment_audit, requires 2-engineer approval, redeploys, and logs outcome
**Acceptance / logic checks:**
- rollback-prod job with only 1 approver is blocked by CI gate
- After rollback, kubectl get pods -n gmepay-prod shows the old image tag in all pod images
- deployment_audit has a ROLLBACK row with rollback_reason populated
- If no prior SUCCESS row exists in deployment_audit, the job fails with error 'no prior successful deployment found' rather than deploying an empty tag
- Post-rollback smoke tests pass and the CI job reports success
**Depends on:** 14.2-T10

### 14.2-T12 — Implement hotfix fast-track pipeline branch and approval flow  _(50 min)_
**Context:** OPS-13 §4.2 defines hotfix/<ticket> branches for production emergencies. Hotfix flow: PR to main AND cherry-pick to the active release branch, then deploy to prod via fast-track approval: two senior engineers + on-call approval (not Release Manager). A post-deployment review is mandatory. The hotfix pipeline skips staging load tests but still runs stages 1-4 (build, unit test, security scan, integration test). A post-deployment review ticket must be auto-created in the issue tracker.
**Steps:** Add a pipeline trigger condition: if branch matches hotfix/*, run stages 1-4 (build, unit, security, integration), skip load-test stage, then require fast-track approval gate (2 senior + on-call role); After fast-track approval, deploy directly to prod using the same deploy-prod job with HOTFIX=true variable; On successful hotfix deploy, auto-create a post-deployment review issue in the project tracker (via API) with title: 'Post-deploy review: hotfix/<ticket> <git-sha>'; Cherry-pick step: add a CI step that cherry-picks the hotfix commit onto the active release/* branch and opens a PR; Log hotfix deploys in deployment_audit with deploy_type='HOTFIX'
**Deliverable:** CI pipeline hotfix trigger and fast-track approval gate, plus auto-created post-deployment review issue on success
**Acceptance / logic checks:**
- Pushing to hotfix/fix-rate-bug triggers stages 1-4 but skips the load-test stage
- Fast-track approval gate requires exactly 3 approvals: 2 senior-engineer role + 1 on-call role; missing any one blocks the deploy
- A post-deployment review issue appears in the issue tracker within 2 minutes of successful hotfix deploy, containing the git SHA and hotfix branch name
- deployment_audit row for the hotfix deploy has deploy_type='HOTFIX'
- Cherry-pick PR to the active release/* branch is opened automatically after hotfix merges to main
**Depends on:** 14.2-T10

### 14.2-T13 — Create database migration tooling setup (Flyway/Liquibase) and conventions  _(50 min)_
**Context:** OPS-13 §4.6 mandates versioned migrations using Flyway or Liquibase. Rules: (a) sequential version numbers with descriptive names, e.g. V001__create_transactions_table.sql; (b) migrations run automatically at deploy time BEFORE the app starts; (c) migrations must be additive-only (add columns/tables, never rename/drop in same release as code change); (d) no 'down' scripts in production; (e) all migration runs logged with version, timestamp, duration, operator identity in the flyway_schema_history / DATABASECHANGELOG table; (f) migration scripts stored in db/migrations/ in the repo.
**Steps:** Add Flyway (or Liquibase) dependency to the build file (pom.xml or build.gradle); Create db/migrations/ directory with a baseline migration: V001__baseline.sql containing the initial schema (transactions, prefunding_ledger, audit_log, batch_job_runs, deployment_audit tables); Configure Flyway to run in validateOnMigrate=true mode, locations=classpath:db/migrations, outOfOrder=false; Add a pre-start migration step to the container entrypoint: flyway migrate before the Spring Boot (or equivalent) app starts; Add a CI check (in Stage 1 or Stage 4) that validates all migration scripts are additive-only: script must not contain DROP TABLE, DROP COLUMN, RENAME COLUMN, or ALTER COLUMN TYPE statements (grep check)
**Deliverable:** db/migrations/ directory with V001 baseline migration, Flyway configured in the app and container entrypoint, and CI additive-only guard
**Acceptance / logic checks:**
- Starting the app against an empty database applies V001 automatically and flyway_schema_history has 1 row with success=true
- A migration file containing DROP TABLE causes the CI additive-only guard to fail the build
- flyway_schema_history row for V001 contains: installed_by (operator/ci identity), installed_on (timestamp), execution_time (ms), success=true
- outOfOrder=false: adding V003 when V002 is missing causes Flyway to reject with OutOfOrderMigrationDetected exception
- db/migrations/ is in the same git repo as application code and is code-reviewed like application code (PR required)
**Depends on:** 14.2-T01

### 14.2-T14 — Implement migration execution step in CI deploy stages (int, staging, prod)  _(45 min)_
**Context:** Per OPS-13 §4.6, migrations run automatically at deploy time before the new application version starts. In the CI pipeline, the migration step runs as a pre-deploy init container or a separate CI step immediately before kubectl rollout. Migration runs must be logged with version, timestamp, duration, and operator identity. If a migration fails, the deploy is halted and no application pods start. This applies to int (Stage 6), staging (Stage 7), and prod (Stage 8).
**Steps:** Add a 'run-migrations' CI step in deploy-int, deploy-staging, and deploy-prod jobs, executed BEFORE the kubectl rollout; Implement as a Kubernetes Job (db-migrate) using the same image as the app but running flyway migrate as the command, with DB credentials from gmepay/<env>/hub-api/db-password; Add a wait step: only proceed with kubectl rollout if the db-migrate Job completes with exit code 0; On migration failure: halt the stage, do NOT rollout the new app version, and fail the CI job with message including the Flyway error output; Migration run result is logged: INSERT INTO migration_run_log (migration_version, env, run_at, duration_ms, status, operator) VALUES (...)
**Deliverable:** run-migrations CI step added to deploy-int, deploy-staging, and deploy-prod jobs, halting deploy on migration failure
**Acceptance / logic checks:**
- A migration with a syntax error (e.g. missing semicolon) causes the deploy-int job to fail at the migration step with no app pod restart
- Successful migration in deploy-prod creates a migration_run_log row with correct env='prod', status='SUCCESS', and operator set to the CI service account identity
- If flyway_schema_history shows the migration was already applied (e.g. re-running the same version), Flyway logs 'Already applied' and exits 0, allowing the deploy to proceed
- Migrations are applied before any new app pod is started: kubectl get pods shows old pods still Running while the db-migrate Job is in progress
- A failed migration in staging does NOT propagate to prod (each environment runs independently)
**Depends on:** 14.2-T13, 14.2-T09

### 14.2-T15 — Implement backward-compatible migration pattern: two-phase column removal  _(40 min)_
**Context:** OPS-13 §4.6 states: to remove a column, first release removes all code references (column stays in DB), second release drops the column. This is required because rollback deploys old code against the migrated schema — old code must work against the post-migration schema. This ticket implements the convention as a developer guide and a CI enforcement check. Example: to drop column transactions.legacy_ref, Phase 1 removes code references (migration: none), Phase 2 adds migration V00N__drop_transactions_legacy_ref.sql with ALTER TABLE transactions DROP COLUMN legacy_ref.
**Steps:** Document the two-phase removal procedure in db/migrations/README.md: (1) Phase 1 PR removes all code references to the column, no migration file; (2) Phase 2 PR (at least one release later) adds the DROP COLUMN migration; Add a CI check that cross-references migration files with the Java source: if a migration drops column X, grep the Java source for column X and fail if any reference is found (this catches single-release violations); Add a migration naming convention check: DROP COLUMN migrations must be named V00N__drop_<table>_<column>.sql; Write a sample migration pair: V010__drop_transactions_legacy_ref_phase1.sql (empty, comment only) and V011__drop_transactions_legacy_ref_phase2.sql (ALTER TABLE transactions DROP COLUMN legacy_ref); Add this check to the Stage 1 build job
**Deliverable:** Two-phase removal CI enforcement check in Stage 1, plus db/migrations/README.md documenting the convention with the example pair
**Acceptance / logic checks:**
- A single-release PR that both removes Java references and adds a DROP COLUMN migration triggers the CI cross-reference check and fails the build
- A two-phase PR where Phase 1 removes code (no migration) and Phase 2 drops the column passes both CI checks
- The README.md contains a worked example using transactions.legacy_ref as the column name
- DROP COLUMN migration file name not matching V00N__drop_<table>_<column>.sql pattern fails the naming check
- Stage 1 failure on this check blocks all downstream stages
**Depends on:** 14.2-T13

### 14.2-T16 — Implement blue-green deployment for major and schema-changing releases  _(55 min)_
**Context:** OPS-13 §4.5 specifies blue-green deployment for major releases and schema-changing deployments: provision a parallel environment, run smoke tests, cut traffic via DNS/LB pointer flip. Zero downtime; rollback is a DNS/LB pointer flip back to blue. The batch/SFTP worker runs as a single-instance job and must be drained before blue-green swap (never run two versions simultaneously against the same SFTP credentials — OPS-13 §4.5). This applies to prod only.
**Steps:** Add a deploy-blue-green CI job (manual trigger only, for releases tagged with migration or major label); Provision the green environment: kubectl apply -f k8s/green/ which creates a parallel Deployment (hub-api-green) in gmepay-prod namespace with the new image tag; Run smoke tests against the green service (internal endpoint, not live traffic): POST /v1/rates, GET /health; Drain the batch worker before traffic cut: kubectl scale deployment/batch-worker --replicas=0 -n gmepay-prod; wait for in-flight jobs to complete (poll batch_job_runs table for RUNNING state to be empty, max 10 min timeout); Flip traffic: update LB target group or ingress to route to hub-api-green; scale hub-api-blue to 0; restart batch-worker pointing to new image
**Deliverable:** deploy-blue-green CI job (manual trigger) for prod, with batch-worker drain, green smoke test, and LB traffic flip
**Acceptance / logic checks:**
- Blue-green job fails if batch_job_runs shows a RUNNING job after 10-minute drain timeout (does not force-kill)
- Smoke tests against the green endpoint pass before any live traffic is redirected
- LB flip is a single idempotent command (update-target-group or kubectl annotate ingress) that can be reversed by re-pointing to blue
- Rollback from green to blue (before blue is scaled down) takes under 60 seconds
- deploy-blue-green job is only triggerable manually (not on auto-push to release/*)
**Depends on:** 14.2-T10, 14.2-T14

### 14.2-T17 — Implement canary deployment for high-risk API changes (5% traffic split)  _(55 min)_
**Context:** OPS-13 §4.5 specifies canary for high-risk API changes: route 5% of traffic to the new version, monitor error rates for 15 minutes, promote to 100% or auto-rollback on SLO breach. The SLO from NFR-10: API error rate < 1% (5xx), p95 latency < 500ms. A canary that breaches either threshold during the 15-minute window is automatically rolled back. This applies to prod API tier (hub-api) only; not applicable to batch-worker.
**Steps:** Add a deploy-canary CI job (manual trigger) that sets the new hub-api image on hub-api-canary Deployment with 1 replica while hub-api-stable has N replicas (traffic split ~5% via ingress weight or Istio VirtualService); Configure a 15-minute monitoring window: query Prometheus metrics api_error_rate{deployment='hub-api-canary'} and api_latency_p95{deployment='hub-api-canary'} every 30 seconds; If error_rate > 1% OR p95 > 500ms at any sample: automatically scale hub-api-canary to 0 replicas and mark canary FAILED in deployment_audit; If 15 minutes elapse with all samples within SLO: promote canary to 100% (kubectl scale hub-api-canary --replicas=N; kubectl scale hub-api-stable --replicas=0); Log every monitoring sample and the final promotion/rollback decision in deployment_audit
**Deliverable:** deploy-canary CI job with 15-minute Prometheus-monitored traffic split and auto-rollback on SLO breach
**Acceptance / logic checks:**
- Canary with a deliberately injected 2% error rate (mock endpoint returning 500 for 2% of requests) triggers auto-rollback within 1 minute of threshold breach
- Canary that passes 15 minutes within SLO is promoted to 100% and hub-api-stable is scaled to 0
- deployment_audit shows all monitoring samples and the final PROMOTED or ROLLED_BACK status
- Traffic to canary is exactly ~5%: ingress weight annotation is 5 (or equivalent)
- deploy-canary is only triggerable manually; it cannot be triggered by a CI auto-push
**Depends on:** 14.2-T10

### 14.2-T18 — Implement secrets injection convention and CI secrets-access guardrails  _(50 min)_
**Context:** OPS-13 §4.7 requires: (a) runtime config injected via environment variables from secrets manager at container start, no config files in image; (b) secrets path: gmepay/<env>/<service>/<secret-name> e.g. gmepay/prod/batch-worker/zeropay-sftp-key; (c) CI has read-only access to gmepay/ci/* only, never touches prod secrets; (d) secret rotation triggers automated re-deployment of affected services. Secrets manager: HashiCorp Vault, AWS Secrets Manager, or Azure Key Vault (OPS-13 §3.5).
**Steps:** Create secrets-path-convention.md documenting the naming convention with examples: gmepay/dev/hub-api/db-password, gmepay/int/batch-worker/zeropay-sftp-key, gmepay/prod/hub-api/jwt-secret; Add a CI guardrail: CI service account token has Vault policy allowing read of gmepay/ci/* only; policy file vault-policy-ci.hcl with path 'gmepay/ci/*' { capabilities = [read] }; Add a pre-flight check to each deploy job: validate that the CI token cannot read gmepay/prod/* (attempt read and expect 403; fail the check if 200 returned); Configure k8s ExternalSecrets or Vault Agent Injector to mount secrets as environment variables at pod start using the path convention; Add a CI check: scan all Kubernetes deployment manifests for hardcoded secret values (grep for password=, secret=, key= patterns with non-variable values); fail the build if found
**Deliverable:** vault-policy-ci.hcl defining CI read scope, secrets-path-convention.md, CI guardrail check, and ExternalSecrets/Vault injector config for hub-api
**Acceptance / logic checks:**
- CI service account attempting to read gmepay/prod/hub-api/db-password receives 403 (permission denied)
- Hub-api pod environment variable DB_PASSWORD is populated from gmepay/<env>/hub-api/db-password at container start, not from a config file in the image
- Hardcoded password string in a deployment YAML (e.g. env: value: mysecretpassword) fails the CI manifest scan
- secrets-path-convention.md documents all required secrets for hub-api, batch-worker, and admin-service with correct path format
- After a secret rotation (new value stored in vault), a kubectl rollout restart picks up the new value within one pod restart cycle
**Depends on:** 14.2-T07

### 14.2-T19 — Implement feature-flag configuration table and startup loading  _(45 min)_
**Context:** OPS-13 §5.2 defines five feature flags: FEATURE_LIVE_FX_FEED (default false), FEATURE_PARTNER_REFUND_API (default false), FEATURE_OUTBOUND_PAYMENTS (default false), FEATURE_BOK_REPORTING (default false), FEATURE_MULTI_SCHEME_ROUTING (default false). Flags are stored in the secrets/config manager and loaded at service startup. Changing a flag requires a config update and service restart — no full deployment needed. Phase 1 go-live: all five flags must be false (OPS-13 §12.1.2).
**Steps:** Create database migration V002__create_feature_flags_table.sql: CREATE TABLE feature_flags (flag_name VARCHAR(64) PRIMARY KEY, enabled BOOLEAN NOT NULL DEFAULT false, description TEXT, updated_at TIMESTAMPTZ, updated_by VARCHAR(128)); Seed migration V003__seed_feature_flags.sql: INSERT five rows with all enabled=false; Implement FeatureFlagService.java: loads flags from DB at startup into an in-memory map; exposes isEnabled(String flagName) -> boolean; Add an /admin/feature-flags GET endpoint returning current flag states (Admin Service, authenticated); Add a startup validation: if FEATURE_LIVE_FX_FEED=true on prod, log a WARN and refuse to start (Phase 1 safety guard)
**Deliverable:** V002/V003 migrations, FeatureFlagService.java, /admin/feature-flags endpoint, and Phase 1 startup guard for FEATURE_LIVE_FX_FEED
**Acceptance / logic checks:**
- On fresh startup, all five flags are false in the feature_flags table
- FeatureFlagService.isEnabled('FEATURE_LIVE_FX_FEED') returns false on a freshly seeded database
- Setting FEATURE_LIVE_FX_FEED=true in the DB on a prod-profile startup causes the app to log WARN and refuse to start
- GET /admin/feature-flags returns a JSON array with all five flags and their enabled state
- Changing a flag value in the DB and restarting the service loads the new value without a full redeployment
**Depends on:** 14.2-T13

### 14.2-T20 — Implement environment configuration matrix validation at startup  _(40 min)_
**Context:** OPS-13 §5.3 defines per-environment configuration: DB pool sizes (dev=5, int=20, staging=80, prod=200), rate-quote TTL (dev/int=300s, staging/prod=per-partner-config), log level (dev/int=DEBUG, staging/prod=INFO), webhook retry max (dev/int=3, staging/prod=5), batch schedule (dev/int=manual, staging/prod=KST windows). The app must validate its config against the expected matrix for its ENV profile at startup and refuse to start if critical mismatches are found.
**Steps:** Implement StartupConfigValidator.java that reads ENV environment variable and validates key config values against the OPS-13 §5.3 matrix; Validation rules: if ENV=prod, DB_POOL_SIZE must be >=100 (warn if <200); LOG_LEVEL must be INFO or WARN; WEBHOOK_RETRY_MAX must be >=5; If ENV=prod and LOG_LEVEL=DEBUG: refuse to start with error 'DEBUG logging is forbidden in production per OPS-13 §5.3'; Log all config values at startup (at INFO level) with sensitive values redacted: DB_PASSWORD=[REDACTED], ZEROPAY_SFTP_KEY=[REDACTED]; Add a unit test for StartupConfigValidator covering: prod+DEBUG->fail, prod+INFO->pass, dev+DEBUG->pass, staging+DEBUG->warn-only
**Deliverable:** StartupConfigValidator.java with startup validation rules, redacted config logging, and unit tests for all 4 ENV-level scenarios
**Acceptance / logic checks:**
- Starting with ENV=prod, LOG_LEVEL=DEBUG throws a ConfigurationException before the HTTP server binds
- Starting with ENV=prod, LOG_LEVEL=INFO, DB_POOL_SIZE=200, WEBHOOK_RETRY_MAX=5 passes validation and starts normally
- Startup log contains all config key names with sensitive values replaced by [REDACTED]
- Unit test for ENV=staging with DB_POOL_SIZE=5 logs a WARN but does not refuse to start
- StartupConfigValidator runs before Spring context finishes loading (using @PostConstruct or ApplicationListener<ApplicationReadyEvent>)
**Depends on:** 14.2-T19

### 14.2-T21 — Implement deployment_audit table and logging for all CI deployments  _(40 min)_
**Context:** OPS-13 §4.3 and §8.1 require that all production actions are logged with operator identity, timestamp, and outcome. The deployment_audit table (referenced in T10, T11, T12, T17) is the persistent record. It must capture: git_sha, image_tag, env, deploy_type (STANDARD/HOTFIX/BLUE_GREEN/CANARY/ROLLBACK), operator_id, change_record_id, deployed_at, status (IN_PROGRESS/SUCCESS/FAILED/ROLLED_BACK), smoke_test_result (JSON), rollback_reason (nullable). This table is append-only (no UPDATE; status changes are new rows with previous_deploy_id FK).
**Steps:** Create migration V004__create_deployment_audit_table.sql with columns: id UUID PK, git_sha VARCHAR(40), image_tag VARCHAR(200), env VARCHAR(20), deploy_type VARCHAR(20), operator_id VARCHAR(128), change_record_id VARCHAR(128), deployed_at TIMESTAMPTZ, status VARCHAR(20), smoke_test_result JSONB, rollback_reason TEXT, previous_deploy_id UUID FK REFERENCES deployment_audit(id); Add NOT NULL constraints on: git_sha, image_tag, env, deploy_type, operator_id, deployed_at, status; Implement DeploymentAuditService.java with methods: createDeploymentRecord(...) -> UUID, updateStatus(UUID id, String status, JsonNode smokeResult), logRollback(UUID priorId, String reason); Add a CHECK constraint: status IN ('IN_PROGRESS','SUCCESS','FAILED','ROLLED_BACK'); Create index on (env, deployed_at DESC) for rollback image-tag lookups
**Deliverable:** V004 migration with deployment_audit table, DeploymentAuditService.java, and index on (env, deployed_at DESC)
**Acceptance / logic checks:**
- INSERT with status='INVALID' violates the CHECK constraint
- A rollback lookup query SELECT image_tag FROM deployment_audit WHERE env='prod' AND status='SUCCESS' ORDER BY deployed_at DESC LIMIT 1 OFFSET 1 returns the second-most-recent successful prod image tag
- previous_deploy_id FK correctly references the prior deployment row for rollback rows
- deployment_audit is append-only: no UPDATE statements exist in DeploymentAuditService.java
- Index on (env, deployed_at DESC) is present in flyway_schema_history after V004 runs
**Depends on:** 14.2-T13

### 14.2-T22 — Implement post-deploy smoke test suite (prod and staging)  _(45 min)_
**Context:** OPS-13 §8.1 specifies a post-deploy smoke test suite taking ~3 minutes: (a) GET /health -> 200, (b) POST /v1/rates for GME Remit domestic -> 200 with valid collection_amount (must equal target_payout + 500 KRW for same-currency short-circuit), (c) GET /v1/partners/{sendmn_id}/balance -> 200 with balance field. These smoke tests run automatically after every prod and staging deploy. Failure within 5 minutes triggers automated rollback (T11). The test inputs and expected outputs must be hardcoded test vectors (not live partner data).
**Steps:** Create smoke-tests/ directory with smoke_prod.sh and smoke_staging.sh; In smoke_prod.sh: (1) curl -f GET /health, (2) curl -f POST /v1/rates body={partner_id:'GME_REMIT_TEST',target_payout:15000,payout_ccy:'KRW',settle_a_ccy:'KRW',settle_b_ccy:'KRW',m_a:0,m_b:0,service_charge:500} and assert response.collection_amount == 15500, (3) curl -f GET /v1/partners/SENDMN_TEST/balance and assert response.balance is a number; Add a 5-minute timeout wrapper: if all three checks do not complete within 300 seconds, exit with code 1; Create smoke test partner configs in the seed data: GME_REMIT_TEST (KRW/KRW same-currency, service_charge=500 KRW) and SENDMN_TEST (OVERSEAS, USD prefunding); Add results JSON output: {health:true/false, rate_quote:true/false, balance_check:true/false} written to smoke-results.json
**Deliverable:** smoke-tests/smoke_prod.sh and smoke_staging.sh with hardcoded test vectors, 5-minute timeout, and smoke-results.json output
**Acceptance / logic checks:**
- Smoke test for GME Remit rate quote asserts collection_amount == 15500 KRW (15000 + 500 service_charge, same-currency short-circuit, no USD pool)
- If /health returns 503, smoke test exits code 1 within 5 seconds
- If the balance endpoint returns an object missing the balance field, the smoke test exits code 1
- smoke-results.json is written regardless of pass/fail (useful for CI artifact)
- Smoke test suite completes within 180 seconds (3 minutes) on a healthy environment
**Depends on:** 14.2-T10, 14.2-T19

### 14.2-T23 — Unit tests for pipeline gate logic: coverage gate, security scan thresholds, smoke-test assertions  _(40 min)_
**Context:** Automated pipeline gate logic must be unit-tested to ensure gates are not silently bypassed. Tests cover: (a) JaCoCo coverage gate at exactly 80% boundary, (b) Dependency-Check CVSS threshold at exactly 7.0, (c) smoke-test assertion math (collection_amount = target_payout + service_charge for same-currency; collection_amount = send_amount + service_charge for cross-border). These are unit tests of the CI scripts and helper utilities, not integration tests.
**Steps:** Write JUnit 5 tests in CoverageGateTest.java: assert gate passes at 80.0%, fails at 79.9%, passes at 100.0%; Write DependencyCheckThresholdTest.java: assert CVSS 6.9 passes, CVSS 7.0 fails, CVSS 9.9 fails; Write SmokeTestAssertionTest.java: same-currency case: input target_payout=15000, service_charge=500, expected collection_amount=15500; cross-border case: verify collection_amount = send_amount + service_charge (not target_payout + service_charge); Write RollbackTriggerTest.java: assert rollback is triggered when smoke test returns {health:false,...}, not triggered when all fields are true; Place all tests in src/test/java/com/gmepayplus/cicd/ package
**Deliverable:** Four test classes (CoverageGateTest, DependencyCheckThresholdTest, SmokeTestAssertionTest, RollbackTriggerTest) in src/test/java/com/gmepayplus/cicd/
**Acceptance / logic checks:**
- CoverageGateTest: coverage=79.9 -> AssertionError; coverage=80.0 -> no error; coverage=100.0 -> no error
- DependencyCheckThresholdTest: CVSS=6.9 -> pass; CVSS=7.0 -> fail; CVSS=9.9 -> fail (boundary inclusive)
- SmokeTestAssertionTest same-currency: collection_amount=15500 passes; collection_amount=15000 fails
- SmokeTestAssertionTest cross-border: verifies collection_amount = send_amount + service_charge (USD pool math preserved; service_charge does NOT enter USD pool)
- All four test classes pass in Stage 2 on the main branch
**Depends on:** 14.2-T22, 14.2-T03

### 14.2-T24 — Unit tests for deployment_audit logging and rollback image-tag retrieval  _(40 min)_
**Context:** The deployment_audit table (T21) and rollback logic (T11) must be unit-tested. Tests use an in-memory H2 database or a Testcontainers PostgreSQL instance. Key invariants: (a) deployment_audit is append-only, (b) rollback retrieves the second-most-recent SUCCESS row for the correct env, (c) status CHECK constraint is enforced, (d) operator_id is always set (never null) for prod deploys.
**Steps:** Write DeploymentAuditServiceTest.java using @DataJpaTest with Testcontainers PostgreSQL; Test 1: createDeploymentRecord sets status=IN_PROGRESS; subsequent updateStatus sets status=SUCCESS in a new row with previous_deploy_id pointing to the IN_PROGRESS row; Test 2: logRollback with a valid priorId creates a ROLLED_BACK row; the rollback_reason field is not null; Test 3: getRollbackTarget(env='prod') with two SUCCESS rows returns the second-most-recent image tag (OFFSET 1); Test 4: createDeploymentRecord with operator_id=null throws ConstraintViolationException
**Deliverable:** DeploymentAuditServiceTest.java with 4 test methods covering append-only, rollback lookup, rollback logging, and null-operator guard
**Acceptance / logic checks:**
- Test 1 passes: two rows exist after create+update; prior row has status=IN_PROGRESS, new row has status=SUCCESS
- Test 2 passes: ROLLED_BACK row has non-null rollback_reason and correct previous_deploy_id FK
- Test 3 passes: given prod SUCCESS rows with deployed_at T1 and T2 (T1 < T2), getRollbackTarget returns the row at T1 (OFFSET 1)
- Test 4 passes: operator_id=null throws an exception before the INSERT
- All 4 tests run as part of Stage 2 unit test suite and are counted in JaCoCo coverage
**Depends on:** 14.2-T21, 14.2-T03

### 14.2-T25 — Unit tests for DB migration additive-only guard and two-phase removal CI check  _(35 min)_
**Context:** The CI additive-only guard (T15) and two-phase removal cross-reference check (T15) must be unit-tested. These checks run in Stage 1. Tests use synthetic migration file contents and synthetic Java source snippets to verify the guard logic catches violations correctly. Critical: a single-release DROP COLUMN violation must be caught before reaching prod.
**Steps:** Write MigrationAdditiveGuardTest.java with test inputs as strings (simulated file contents); Test 1: migration content containing 'DROP TABLE transactions' -> guard fails with clear error message; Test 2: migration content containing 'ALTER TABLE transactions DROP COLUMN legacy_ref' -> guard fails; Test 3: migration content containing 'ALTER TABLE transactions ADD COLUMN new_field VARCHAR(64)' -> guard passes; Test 4 (two-phase cross-reference): migration drops column legacy_ref AND Java source contains 'legacy_ref' -> cross-reference check fails; Test 5 (two-phase cross-reference): migration drops column legacy_ref AND Java source does NOT contain 'legacy_ref' -> cross-reference check passes
**Deliverable:** MigrationAdditiveGuardTest.java with 5 test methods covering DROP TABLE, DROP COLUMN, ADD COLUMN, and two-phase cross-reference scenarios
**Acceptance / logic checks:**
- Test 1 passes: DROP TABLE pattern triggers guard failure with message containing 'additive-only violation'
- Test 2 passes: DROP COLUMN triggers guard failure
- Test 3 passes: ADD COLUMN passes the guard with no error
- Test 4 passes: DROP COLUMN with matching Java reference triggers cross-reference failure
- Test 5 passes: DROP COLUMN without matching Java reference passes cross-reference check
**Depends on:** 14.2-T15, 14.2-T03

### 14.2-T26 — Unit tests for feature-flag startup guard and config validation  _(35 min)_
**Context:** FeatureFlagService (T19) and StartupConfigValidator (T20) must be unit-tested. Critical Phase 1 invariant: all five feature flags must be false at go-live; FEATURE_LIVE_FX_FEED=true on prod must refuse startup. Config validation: prod+DEBUG must refuse startup; prod+INFO must pass. These are pure unit tests with mocked dependencies.
**Steps:** Write FeatureFlagServiceTest.java: test isEnabled returns false for all five flags on seeded DB; test that setting FEATURE_LIVE_FX_FEED=true in the mock DB causes the prod startup guard to throw ConfigurationException; Write StartupConfigValidatorTest.java: test 4 scenarios: (prod, DEBUG) -> ConfigurationException; (prod, INFO) -> passes; (dev, DEBUG) -> passes; (staging, DEBUG) -> logs WARN but passes; Use Mockito to mock DB layer in FeatureFlagServiceTest; use @SpringBootTest with test profiles in StartupConfigValidatorTest; Add a test that verifies all five canonical flag names are present in the seeded DB: FEATURE_LIVE_FX_FEED, FEATURE_PARTNER_REFUND_API, FEATURE_OUTBOUND_PAYMENTS, FEATURE_BOK_REPORTING, FEATURE_MULTI_SCHEME_ROUTING; Ensure tests run in Stage 2 and contribute to JaCoCo coverage
**Deliverable:** FeatureFlagServiceTest.java and StartupConfigValidatorTest.java covering all flag defaults, prod startup guard, and config validation scenarios
**Acceptance / logic checks:**
- FeatureFlagServiceTest: isEnabled('FEATURE_LIVE_FX_FEED') returns false on fresh seed
- FeatureFlagServiceTest: FEATURE_LIVE_FX_FEED=true on prod profile throws ConfigurationException
- StartupConfigValidatorTest: (prod, DEBUG) throws ConfigurationException with message referencing OPS-13 §5.3
- StartupConfigValidatorTest: (prod, INFO) passes without exception
- All five canonical flag names are present in the seeded test DB
**Depends on:** 14.2-T20, 14.2-T03

### 14.2-T27 — Implement structured JSON logging with required fields across all services  _(50 min)_
**Context:** OPS-13 §7.2 requires all services to emit JSON-structured logs with required fields: timestamp (ISO-8601 UTC), level (DEBUG/INFO/WARN/ERROR), service (e.g. hub-api), env (dev/int/staging/prod), trace_id (UUID), span_id (UUID), partner_id (present on payment-related logs), transaction_id (UUID, present on payment logs), event (semantic name), message (human-readable). In production, sensitive monetary fields (collection_amount, prefund_balance, rate components) must NOT be logged as plain values — log IDs only. Log level in prod: INFO; in dev/int: DEBUG (OPS-13 §5.3).
**Steps:** Configure Logback (or Log4j2) with a JSON encoder (logstash-logback-encoder or equivalent) outputting all required fields; Add a LoggingContextFilter.java (Servlet filter) that extracts trace_id and span_id from incoming W3C TraceContext headers (traceparent header) and stores them in MDC; Add a PaymentLogSanitizer.java that redacts sensitive fields: collection_amount, prefund_balance, payout_usd_cost, collection_margin_usd, cost_rate_pay, cost_rate_coll from log messages when ENV=prod (replaces with [REDACTED]); Configure log level per environment via LOG_LEVEL env var: DEBUG for dev/int, INFO for staging/prod; Write a test that sends a payment request in prod-profile and asserts the log output contains transaction_id and partner_id but does NOT contain a raw collection_amount value
**Deliverable:** Logback JSON config file, LoggingContextFilter.java, PaymentLogSanitizer.java, and a unit test for prod-profile log sanitization
**Acceptance / logic checks:**
- A log line emitted by hub-api in INFO level contains all 9 required fields: timestamp, level, service, env, trace_id, span_id, partner_id, transaction_id, event
- In prod profile, a payment log event does not contain the literal collection_amount value (e.g. 37.33) but instead contains [REDACTED]
- In dev profile, collection_amount IS present in the log for debugging
- trace_id in log output matches the traceparent header value from the HTTP request (W3C format: 00-<traceId>-<spanId>-<flags>)
- A log line without a trace_id (e.g. a startup log) contains trace_id='' (empty string, not null, to avoid JSON parse errors)
**Depends on:** 14.2-T20

### 14.2-T28 — Implement CI pipeline for Admin System and Partner Portal services  _(50 min)_
**Context:** OPS-13 §1.2 scope includes Hub Core backend, Admin System, and Partner Portal. The CI/CD pipeline (Stages 1-8) must be replicated for the Admin System (admin-service) and Partner Portal (partner-portal-service) components, not just hub-api. Each service has its own Dockerfile and image tag. Deployments are coordinated: hub-api, admin-service, and partner-portal-service should be deployable independently but the smoke test suite validates inter-service communication. The same branching strategy and environment matrix applies.
**Steps:** Add pipeline jobs for admin-service and partner-portal-service, parameterised by SERVICE_NAME variable, reusing the same Stage 1-8 template (matrix build or include/extends in CI YAML); Add admin-service-specific smoke test: GET /admin/health -> 200, GET /admin/feature-flags -> 200 with 5 flags all false on fresh deploy; Add partner-portal-service smoke test: GET /portal/health -> 200, GET /portal/partners/SENDMN_TEST/balance -> 200; Ensure each service has its own image tag in image-manifest.json (hub-api-<sha>, admin-service-<sha>, partner-portal-<sha>); Configure deploy jobs to deploy all three services in parallel on merge to main (int) and release/* (staging), but prod deploy requires explicit per-service approval
**Deliverable:** Parameterised CI pipeline supporting admin-service and partner-portal-service with service-specific smoke tests and independent image tags
**Acceptance / logic checks:**
- A lint error in admin-service fails only the admin-service pipeline, not hub-api
- All three services produce separate image tags in the artifact registry after a merge to main
- Admin-service smoke test checks GET /admin/feature-flags returns JSON with FEATURE_LIVE_FX_FEED: false
- partner-portal-service can be deployed to prod independently of hub-api (separate approval gate per service)
- Deploying admin-service to int does not trigger a hub-api pod restart
**Depends on:** 14.2-T07, 14.2-T08

### 14.2-T29 — Implement pre-go-live CI checklist automation (12.1 checklist items)  _(50 min)_
**Context:** OPS-13 §12.1 defines a pre-go-live checklist of 8 categories. Selected items must be automated as CI/CD checks that run as part of the go-live release pipeline (triggered on release/go-live-* branch). Automated checks: (a) all feature flags are false in prod DB, (b) pool identity assertion is enabled (config check), (c) ZeroPay SFTP connectivity test from prod worker zone, (d) all prod secrets present in secrets manager (not empty), (e) migrations applied and flyway_schema_history shows no pending migrations. Non-automatable items (partner sign-off, DR drill) remain manual in the checklist document.
**Steps:** Create CI job 'go-live-checks' triggered only on release/go-live-* branches; Check 1: query prod DB feature_flags table and assert all 5 flags have enabled=false; Check 2: read prod config and assert POOL_IDENTITY_ASSERTION_ENABLED=true; Check 3: run SFTP connectivity test: sftp -o BatchMode=yes -o ConnectTimeout=10 <prod-sftp-host> exit 0; Check 4: for each required secret path in secrets-inventory.txt, assert vault kv get <path> returns non-empty value; Check 5: flyway info --url=<prod-db> output must show 0 pending migrations; fail if any migration is in PENDING state
**Deliverable:** CI job 'go-live-checks' with 5 automated pre-go-live assertions, runnable on release/go-live-* branches
**Acceptance / logic checks:**
- Setting FEATURE_LIVE_FX_FEED=true in prod DB causes check 1 to fail with message listing the offending flag
- Check 2 fails if POOL_IDENTITY_ASSERTION_ENABLED is missing or false
- Check 3 fails if the ZeroPay prod SFTP host is unreachable (connection timeout after 10s)
- Check 5 fails if there is 1 pending migration; passes when 0 pending migrations
- go-live-checks job is only triggered on release/go-live-* branches, not on every main merge
**Depends on:** 14.2-T19, 14.2-T14, 14.2-T10

### 14.2-T30 — Implement CI job for batch-worker single-instance enforcement and drain check  _(45 min)_
**Context:** OPS-13 §4.5 and §10.1 state: the batch-worker must never run as more than one instance simultaneously (duplicate file submissions to ZeroPay SFTP would cause settlement errors). Max replicas = 1. Before deploying a new batch-worker version, the pipeline must drain the current job: wait for batch_job_runs to have no RUNNING rows, with a 10-minute timeout. After drain, scale to 0, deploy new image, scale back to 1. This applies to all environments.
**Steps:** Add a 'drain-batch-worker' CI step in deploy-int, deploy-staging, and deploy-prod before the batch-worker rollout; Drain logic: poll SELECT COUNT(*) FROM batch_job_runs WHERE status='RUNNING' every 15s; if count=0 proceed; if count>0 after 600s timeout, fail the deploy with error 'batch worker drain timed out'; After drain: kubectl scale deployment/batch-worker --replicas=0 -n gmepay-<env>; After new image deploy: kubectl scale deployment/batch-worker --replicas=1 -n gmepay-<env>; Add a PodDisruptionBudget (pdb-batch-worker.yaml) with maxUnavailable=0 and minAvailable=1 to prevent Kubernetes from evicting the batch worker pod while a job is running
**Deliverable:** drain-batch-worker CI step (polling batch_job_runs), PodDisruptionBudget manifest pdb-batch-worker.yaml, and scale-to-0/scale-to-1 deploy sequence
**Acceptance / logic checks:**
- When batch_job_runs has 1 RUNNING row, drain step waits and does not proceed with deploy
- After 600 seconds with a still-RUNNING job, drain step fails the CI job with timeout message
- With 0 RUNNING jobs, drain completes immediately and scale-to-0 runs within 5 seconds
- After deploy, kubectl get deployment/batch-worker shows DESIRED=1, READY=1, no more than 1 pod at any time
- PodDisruptionBudget manifest is present in k8s/ directory and applied to gmepay-prod namespace
**Depends on:** 14.2-T10, 14.2-T13

### 14.2-T31 — Write CI/CD operational runbook section for pipeline failures and rollbacks  _(40 min)_
**Context:** OPS-13 §8.1 and §8.2 define production deployment and rollback procedures. This ticket produces the developer-facing runbook section covering: (a) how to manually trigger a rollback when automated rollback did not fire, (b) what to do if a migration was applied and old code is incompatible (forward-fix hotfix path), (c) how to retrieve the prior image tag from deployment_audit, (d) how to approve a prod deployment in the CI tool. Format: Markdown in docs/runbooks/cicd-pipeline.md. Must be self-contained for an on-call engineer with no prior context.
**Steps:** Create docs/runbooks/cicd-pipeline.md with sections: (1) Manual Rollback Procedure, (2) Migration-Applied-Rollback-Incompatible Procedure, (3) Retrieving Prior Image Tag, (4) Approving a Prod Deployment, (5) Hotfix Fast-Track Procedure; Section 1: step-by-step using the CI tool UI, referencing rollback-prod job; include the SQL query: SELECT image_tag FROM deployment_audit WHERE env='prod' AND status='SUCCESS' ORDER BY deployed_at DESC LIMIT 1 OFFSET 1; Section 2: if migration was applied and old code is incompatible, DO NOT roll back the migration; instead prepare a fast-forward hotfix; escalate to Engineering Lead immediately; Section 3: include the full SQL query and how to feed the result into the CI rollback job; Section 4: name the required approver roles (Release Manager, 2x senior engineer for hotfix); Section 5: mirror the hotfix pipeline flow from T12 with step-by-step approval instructions
**Deliverable:** docs/runbooks/cicd-pipeline.md with 5 sections covering all manual intervention scenarios
**Acceptance / logic checks:**
- Section 1 contains the exact SQL query for retrieving the prior image tag from deployment_audit
- Section 2 explicitly states: do NOT roll back the migration; prepare a fast-forward hotfix instead
- Section 4 lists the three approval roles for prod: Release Manager, QA sign-off, change record ID (and two-senior+on-call for hotfix)
- The runbook does not reference 'see the spec' or 'see OPS-13' without quoting the relevant rule inline
- A junior engineer with no prior context can follow Section 1 to execute a manual rollback using only this document
**Depends on:** 14.2-T11, 14.2-T12

### 14.2-T32 — Integration test: full pipeline end-to-end run on a feature branch with intentional failures  _(55 min)_
**Context:** This integration test validates the full 8-stage pipeline by running it against a controlled test scenario. The test uses a dedicated test-branch (test/pipeline-validation) with two commits: (a) a commit that introduces a deliberate lint error (to validate Stage 1 gate), (b) a clean commit (to validate all 8 stages pass). This is a CI pipeline test, not an application test. It should be run in the int environment using synthetic data only. Expected: lint commit fails at Stage 1, clean commit reaches Stage 6 (int deploy) automatically.
**Steps:** Create branch test/pipeline-validation in the repository; Commit 1: introduce a deliberate Checkstyle violation (unused import) in a non-critical class; push and observe CI run stops at Stage 1; Fix the violation and push Commit 2; observe all 8 stages run (stop before prod gate since this is not a release/* branch — expect Stage 6 int deploy to complete); Document the results: Stage 1 failure time, Stage 6 success time, total pipeline duration, image tag created; Verify: deployment_audit has a SUCCESS row for env=int after Commit 2 pipeline completes
**Deliverable:** Test run evidence (pipeline run logs/screenshots) and a pipeline-validation-results.md documenting pass/fail at each stage for both commits
**Acceptance / logic checks:**
- Commit 1 fails at Stage 1 (lint) and no Stage 2-8 jobs are triggered
- Commit 2 passes all stages 1-6 and deploys to int automatically
- Total pipeline duration for Commit 2 (Stages 1-6) is under 25 minutes
- deployment_audit has a SUCCESS row for env=int with the Commit 2 git SHA
- image tag in the artifact registry matches the format <git-sha>-test-pipeline-validation-<semver>
**Depends on:** 14.2-T08, 14.2-T21


## WBS 14.3 — IaC & networking/zones
### 14.3-T01 — Define IaC module structure and repository layout for GMEPay+ infra  _(30 min)_
**Context:** GMEPay+ IaC (Terraform or Pulumi) must target five logical network zones: Public DMZ, API Zone, Worker Zone, Data Zone, and Management Zone (OPS-13 §3.2). The repo layout must support four environments: dev, int, staging, prod. Cloud provider is agnostic; all service boundaries are expressed as container workloads. Secret path convention: gmepay/<env>/<service>/<secret-name>.
**Steps:** Create infra/ directory at repo root with subdirectories: modules/, environments/, scripts/; Create modules/ subdirs: network/, compute/, data/, secrets/, monitoring/; Create environments/ subdirs: dev/, int/, staging/, prod/ each with main.tf (or equivalent) and a vars file; Add a top-level README.md (infra/README.md) listing module boundaries and environment names; Commit the skeleton with no-op placeholder files and verify the directory tree is correct
**Deliverable:** infra/ directory skeleton committed to the repository with correct module and environment layout
**Acceptance / logic checks:**
- Directory tree contains infra/modules/{network,compute,data,secrets,monitoring}/ and infra/environments/{dev,int,staging,prod}/
- Each environment directory contains a main entry point and a variables file
- No credentials, secrets, or hardcoded IPs appear in any committed file
- A developer can identify which module governs each of the 5 zones from the README

### 14.3-T02 — Author network zone variable schema (zones, CIDRs, allowed ports)  _(35 min)_
**Context:** The five zones (Public DMZ, API Zone, Worker Zone, Data Zone, Management Zone) each have distinct traffic rules per OPS-13 §3.2 and SEC-09 §2.1. Public DMZ: inbound HTTPS 443 from internet. API Zone: no direct public; only from DMZ LB. Worker Zone: egress-only to ZeroPay SFTP port 22 and partner webhook HTTPS 443; no inbound. Data Zone: no external access; only from API Zone and Worker Zone on DB port 5432, Redis 6379, MQ. Management Zone: inbound VPN/bastion only (port 22 or VPN UDP 51820). All cross-zone traffic encrypted in transit (TLS 1.2+; internal mTLS).
**Steps:** In infra/modules/network/, create variables.tf defining: vpc_cidr (string), zone_cidrs (map of zone_name to CIDR string), allowed_ingress_rules (list of objects: zone, from_port, to_port, protocol, source_cidr), mgmt_vpn_cidr (string); Add locals.tf with canonical zone names as constants: dmz, api, worker, data, mgmt; Add outputs.tf exporting zone subnet IDs and security-group IDs; Write a validate script or use variable validation blocks to assert zone_cidrs map has exactly 5 keys; Document each variable with a description field
**Deliverable:** infra/modules/network/variables.tf, locals.tf, and outputs.tf defining the zone schema
**Acceptance / logic checks:**
- variables.tf defines vpc_cidr, zone_cidrs (map(string)), allowed_ingress_rules (list of objects), and mgmt_vpn_cidr
- locals.tf exports exactly 5 canonical zone name constants: dmz, api, worker, data, mgmt
- Validation block or script rejects a zone_cidrs map with fewer than 5 keys
- outputs.tf exports at minimum subnet_ids (map) and security_group_ids (map) keyed by zone name
**Depends on:** 14.3-T01

### 14.3-T03 — Implement Public DMZ zone: LB, WAF, TLS termination resources  _(50 min)_
**Context:** The Public DMZ zone (OPS-13 §3.2) contains: Load Balancer (HTTPS 443 inbound from internet), WAF, and TLS termination. It must not allow any inbound traffic on any port other than 443. All TLS must be 1.2 minimum (TLS 1.3 preferred) per SEC-09 §2.4. The DMZ forwards traffic only to the API Zone (no direct path to Worker, Data, or Management zones). A static egress NAT IP is required for ZeroPay SFTP allow-listing (sad-02 §10.4 A-04); the NAT Gateway lives in the DMZ or a dedicated egress subnet.
**Steps:** In infra/modules/network/dmz.tf, declare: load balancer resource (application/HTTP type), WAF policy resource attached to the LB, TLS listener on port 443 with minimum TLS 1.2 policy, NAT Gateway with an elastic/static IP; Add security group for DMZ: inbound 443 from 0.0.0.0/0 and ::/0; outbound to API Zone CIDR only; Output the NAT Gateway static IP as nat_egress_ip for use in ZeroPay SFTP allow-list docs; Tag all resources with env, zone=dmz, project=gmepay
**Deliverable:** infra/modules/network/dmz.tf defining LB, WAF, TLS listener, NAT Gateway, and DMZ security group
**Acceptance / logic checks:**
- DMZ security group allows inbound 443 only; all other inbound ports are absent or explicitly denied
- TLS policy specifies minimum TLS 1.2 (e.g. ELBSecurityPolicy-TLS13-1-2-2021-06 or equivalent)
- NAT Gateway has a static public IP and its ID is exported as nat_egress_ip output
- WAF resource is attached to the LB resource in the same module; plan shows dependency
- No inbound rule in DMZ SG allows ports 22, 80, 3306, 5432, or 6379
**Depends on:** 14.3-T02

### 14.3-T04 — Implement API Zone resources: subnets, SGs, internal mTLS annotation  _(45 min)_
**Context:** The API Zone (OPS-13 §3.2) hosts the Northbound API service and CPM Token service. It is stateless and horizontally scalable (min 3 pods, max 20 per OPS-13 §10.1). Traffic rules: inbound only from DMZ LB (HTTPS 443); outbound to Data Zone on ports 5432 (PostgreSQL), 6379 (Redis), and MQ port; internal service-to-service traffic uses mTLS (SEC-09 §2.4). No public internet access. Subnet must span at least 2 AZs (OPS-13 §3.4).
**Steps:** In infra/modules/network/api_zone.tf, declare: private subnets in 2+ AZs tagged zone=api, security group allowing inbound 443 from DMZ SG only and outbound to Data Zone SG on 5432, 6379, and MQ port 5672; Add a placeholder annotation/comment block for mTLS certificate provisioning (certificate ARN variable to be set at deploy time); Declare the auto-scaling group or k8s HPA config reference with min=3, max=20 parameters; Export api_zone_subnet_ids (list) and api_sg_id
**Deliverable:** infra/modules/network/api_zone.tf with API Zone subnets, security group, scaling config reference, and mTLS annotation
**Acceptance / logic checks:**
- API Zone SG allows inbound 443 from DMZ SG only; no inbound from 0.0.0.0/0
- Outbound rules from API Zone cover Data Zone ports 5432, 6379, and 5672 (or configurable MQ port); no other outbound is open
- Subnets are declared in at least 2 distinct availability zones
- min_capacity=3 and max_capacity=20 appear in the scaling config or as exported variables
- api_zone_subnet_ids and api_sg_id are exported outputs
**Depends on:** 14.3-T03

### 14.3-T05 — Implement Worker Zone resources: egress-only SG and fixed-IP egress path  _(45 min)_
**Context:** The Worker Zone (OPS-13 §3.2) hosts the Batch/SFTP Worker, Webhook Dispatcher, and FX Rate Updater. Key rule: egress-only; no inbound connections from any zone or the internet. Specific egress destinations: ZeroPay SFTP on port 22 to 한결원 SFTP host (fixed IP, allow-listed); partner webhook HTTPS 443 to external URLs. The worker must route SFTP traffic through the NAT Gateway with static IP (from 14.3-T03) for allow-listing. Worker is single-instance for batch jobs (never auto-scaled to prevent duplicate SFTP submissions).
**Steps:** In infra/modules/network/worker_zone.tf, declare: private subnet in at least 1 AZ tagged zone=worker; Security group: no inbound rules; outbound rules: port 22 to variable zeropay_sftp_cidr, port 443 to 0.0.0.0/0 (partner webhooks), and Data Zone ports 5432 and 5672; Attach NAT Gateway route so all worker egress uses the static IP from 14.3-T03; Add a variable sftp_destination_ip (string) with description noting it must be supplied per environment from 한결원 allowlist; Export worker_subnet_id and worker_sg_id
**Deliverable:** infra/modules/network/worker_zone.tf with Worker Zone subnet, egress-only SG, and NAT-routed egress
**Acceptance / logic checks:**
- Worker SG has zero inbound rules
- Outbound rule for port 22 targets only the zeropay_sftp_cidr variable (not 0.0.0.0/0)
- Outbound rule for port 443 allows partner webhook delivery
- All egress routes through the NAT Gateway (route table shows 0.0.0.0/0 -> nat_gateway_id from DMZ module)
- Single-instance constraint is documented as a comment or variable with max_count=1
**Depends on:** 14.3-T03

### 14.3-T06 — Implement Data Zone resources: DB, Redis, MQ subnets and most-restricted SG  _(50 min)_
**Context:** The Data Zone (OPS-13 §3.2) hosts PostgreSQL primary + replica, Redis cache, and Message Queue. It has NO external internet access. Inbound allowed only from API Zone and Worker Zone on specific ports: 5432 (PostgreSQL), 6379 (Redis), and MQ port 5672. Multi-AZ required (OPS-13 §3.4): both PostgreSQL nodes and Redis in 2 AZs. DB connection pool prod=200, staging=80, int=20 (OPS-13 §10.2). All DB connections encrypted in transit (TLS, SEC-09 §2.4). Encryption at rest: AES-256 at storage layer (SEC-09 §2.6).
**Steps:** In infra/modules/network/data_zone.tf, declare: private subnets in 2 AZs tagged zone=data; Security group: inbound 5432 from api_sg_id and worker_sg_id only; inbound 6379 from api_sg_id only; inbound 5672 from api_sg_id and worker_sg_id only; zero internet egress; Declare DB subnet group using both AZ subnets for managed PostgreSQL; Declare Redis subnet group (multi-AZ) and MQ subnet group; Add variable db_connection_pool_size with per-env defaults: prod=200, staging=80, int=20; Export data_zone_subnet_ids, data_sg_id, db_subnet_group_name, cache_subnet_group_name
**Deliverable:** infra/modules/network/data_zone.tf with Data Zone subnets, most-restricted SG, and subnet groups
**Acceptance / logic checks:**
- Data Zone SG has no inbound from 0.0.0.0/0 or internet-facing zones
- Inbound port 5432 is restricted to api_sg_id and worker_sg_id source security groups
- Subnet group spans at least 2 distinct AZs
- db_connection_pool_size defaults match spec: prod=200, staging=80, int=20
- Data Zone SG has no outbound rules except DNS (53) if needed for managed services
**Depends on:** 14.3-T04, 14.3-T05

### 14.3-T07 — Implement Management Zone resources: VPN/bastion-only SG and monitoring subnet  _(40 min)_
**Context:** The Management Zone (OPS-13 §3.2) hosts the Admin System, Secrets Manager agent, and Observability Stack (Prometheus, Grafana, Jaeger/Tempo). Access rule: VPN or bastion only (not public internet). Admin System runs behind the LB but only accessible from VPN CIDR. Secrets Manager agent must be reachable by all other zones for secret injection at container start. Secret path convention: gmepay/<env>/<service>/<secret-name> (OPS-13 §4.7). Admin system scaling: min 2, max 8 pods (OPS-13 §10.1).
**Steps:** In infra/modules/network/mgmt_zone.tf, declare: private subnet tagged zone=mgmt; Security group: inbound on admin HTTPS 443 from mgmt_vpn_cidr variable only; inbound on bastion SSH 22 from mgmt_vpn_cidr only; outbound to all internal zones on HTTPS 443 and secrets port 8200 (Vault); Declare Vault/secrets agent access policy: all zones can pull secrets via HTTPS 443 from mgmt zone; Export mgmt_subnet_id, mgmt_sg_id, secrets_agent_endpoint; Add admin_system_min_capacity=2, admin_system_max_capacity=8 as variables
**Deliverable:** infra/modules/network/mgmt_zone.tf with Management Zone subnet, VPN-only SG, and secrets agent access policy
**Acceptance / logic checks:**
- Mgmt SG allows inbound 443 and 22 from mgmt_vpn_cidr only; no rules allow 0.0.0.0/0
- Secrets agent endpoint (port 8200) allows inbound from all internal zone SGs
- admin_system_min_capacity=2 and admin_system_max_capacity=8 are present as outputs or variables
- mgmt_subnet_id and secrets_agent_endpoint are exported
**Depends on:** 14.3-T02

### 14.3-T08 — Wire zone modules into a composable network root module with cross-zone SG references  _(55 min)_
**Context:** Each zone module (DMZ, API, Worker, Data, Management) is independently declared. A root network module must wire them together: passing each zone module's SG IDs as inputs to adjacent zones so security group rules reference SG IDs (not CIDRs) for internal traffic. This avoids hardcoded CIDRs for inter-zone rules, follows SEC-09 zero-trust intent. All cross-zone traffic must be encrypted (TLS/mTLS). The root module also outputs combined values for compute modules to consume.
**Steps:** Create infra/modules/network/main.tf instantiating all 5 zone sub-modules; Pass dmz_sg_id into api_zone module as allowed_inbound_sg, api_sg_id and worker_sg_id into data_zone module; Pass dmz nat_egress_ip output into worker_zone as the NAT reference; Declare a local map all_zone_outputs consolidating subnet IDs and SG IDs for all 5 zones; Add an outputs.tf in the root network module exporting all_zone_outputs
**Deliverable:** infra/modules/network/main.tf wiring all 5 zone modules with correct cross-zone SG references
**Acceptance / logic checks:**
- API Zone SG inbound rule references dmz_sg_id, not a hardcoded CIDR
- Data Zone SG inbound rules reference api_sg_id and worker_sg_id by ID
- Worker Zone egress routes through the NAT IP output from the DMZ module
- terraform validate (or pulumi preview) passes with zero errors on the composed network module
- all_zone_outputs map contains keys: dmz, api, worker, data, mgmt with subnet_id and sg_id per key
**Depends on:** 14.3-T03, 14.3-T04, 14.3-T05, 14.3-T06, 14.3-T07

### 14.3-T09 — Author per-environment network variable files (dev, int, staging, prod)  _(35 min)_
**Context:** Each of the four environments (dev, int, staging, prod) requires distinct network configuration per OPS-13 §2 and §5.3. Key differences: VPC CIDR and zone CIDRs (non-overlapping per env), mgmt_vpn_cidr (corp VPN range), zeropay_sftp_cidr (dev=mock, int/staging=한결원 test SFTP IP, prod=한결원 production SFTP IP, to be confirmed per OI-OPS-02), db_connection_pool_size (dev=5, int=20, staging=80, prod=200). Prod must use a distinct CIDR space and separate static egress IP.
**Steps:** In infra/environments/dev/terraform.tfvars (or equivalent), set vpc_cidr=10.10.0.0/16, per-zone CIDRs from 10.10.x.0/24, zeropay_sftp_cidr=127.0.0.1/32 (stub/mock), db_connection_pool_size=5; Repeat for int (10.20.x.0/24, zeropay_sftp_cidr=placeholder for 한결원 test IP), staging (10.30.x.0/24), prod (10.40.x.0/24); Add a comment in each prod tfvars noting: zeropay_sftp_cidr must be confirmed with 한결원 before go-live (OI-OPS-02); allow 2 weeks for firewall changes; Ensure no real credentials appear in these files; all secret values are referenced as variables injected at runtime
**Deliverable:** infra/environments/{dev,int,staging,prod}/terraform.tfvars files with correct per-environment network variables
**Acceptance / logic checks:**
- All four environment VPC CIDRs are distinct and non-overlapping
- dev zeropay_sftp_cidr is set to a loopback or mock value (not a live SFTP IP)
- prod tfvars contains the OI-OPS-02 confirmation comment
- db_connection_pool_size matches spec: dev=5, int=20, staging=80, prod=200
- No secrets, API keys, or private keys appear in any tfvars file
**Depends on:** 14.3-T08

### 14.3-T10 — Implement TLS certificate provisioning and rotation IaC resource  _(40 min)_
**Context:** All inbound TLS must be 1.2 minimum (TLS 1.3 preferred) per SEC-09 §2.4. TLS certificates must be automatically renewed; rotation period is 90 days via ACME/Let's Encrypt or PKI, with an alert 14 days before expiry (SEC-09 §2.7). The DMZ LB listener requires a certificate ARN or managed cert. Internal service-to-service uses mTLS (SEC-09 §2.4, §3.4.3). Unencrypted HTTP is not permitted on any connection carrying credentials, payment data, or PII.
**Steps:** In infra/modules/secrets/certs.tf, declare a managed TLS certificate resource for the public DMZ domain (variable public_domain_name); Configure auto-renewal with ACME or cloud-managed certificate service; Add a monitoring alarm resource that fires an alert 14 days before expiry (expiry_threshold_days=14); Declare an internal CA or certificate resource for mTLS between API Zone services (internal_ca_cert variable reference); Export cert_arn (public), internal_ca_arn for use by compute modules
**Deliverable:** infra/modules/secrets/certs.tf declaring public TLS cert with auto-renewal and expiry alert, plus internal CA reference for mTLS
**Acceptance / logic checks:**
- Public cert resource specifies minimum TLS 1.2 policy on the LB listener
- Auto-renewal is configured (not manual); certificate validity is managed by the provider
- Expiry alarm threshold is 14 days and alerts to the monitoring channel
- internal_ca_arn is exported for injection into API Zone service containers
- HTTP (port 80) listener is absent or redirects to HTTPS; no plaintext HTTP resource declared
**Depends on:** 14.3-T03

### 14.3-T11 — Implement Secrets Manager path structure and access policies in IaC  _(50 min)_
**Context:** Secret paths follow gmepay/<env>/<service>/<secret-name> per OPS-13 §4.7. Example: gmepay/prod/batch-worker/zeropay-sftp-key. Services retrieve secrets at startup; secrets are never in env files or Docker images. The CI/CD pipeline has read-only access to gmepay/ci/* only; it never touches production secrets (OPS-13 §4.7). Each service has a dedicated IAM role or Vault policy granting least-privilege read access to its own path prefix only. Secrets rotation triggers automated re-deployment of affected services (OPS-13 §4.7).
**Steps:** In infra/modules/secrets/vault_policies.tf (or IAM policies), declare per-service policies: hub-api reads gmepay/<env>/hub-api/*, batch-worker reads gmepay/<env>/batch-worker/*, admin reads gmepay/<env>/admin/*, ci reads gmepay/ci/* read-only; Declare a secrets_path_prefix local = gmepay/${var.env} and require all secret declarations to use this prefix; Add a policy preventing any service from reading another service path (e.g. hub-api cannot read batch-worker/* path); Declare rotation notification: when a secret is rotated, publish to a deployment trigger topic; Export per-service policy ARNs or Vault policy names
**Deliverable:** infra/modules/secrets/vault_policies.tf with per-service least-privilege secret access policies and rotation notification
**Acceptance / logic checks:**
- hub-api policy grants read on gmepay/<env>/hub-api/* only; an attempt to read gmepay/<env>/batch-worker/* must be denied by the policy
- ci policy is read-only and scoped to gmepay/ci/* only; it has no access to gmepay/prod/*
- secrets_path_prefix local enforces the gmepay/<env>/<service>/<name> convention
- Rotation notification resource is declared and linked to the redeployment trigger
- No wildcard (*) policy grants exist that would allow cross-service secret access
**Depends on:** 14.3-T07

### 14.3-T12 — Implement object storage module for batch file archive with versioning and 7-year retention  _(45 min)_
**Context:** All ZeroPay batch files (inbound and outbound) must be stored in object storage at path /gmepay/batch/<direction>/<file-id>/YYYY-MM-DD/<filename> (OPS-13 §6.5). Retention: 7 years (regulatory). Versioning must be enabled; files are immutable once written (SEC-09 §5, OPS-13 §6.5). Storage must be geo-redundant. Bucket is accessible only from the Worker Zone and Management Zone; no public access. Worker Zone uses egress-only outbound to object storage endpoint (HTTPS 443).
**Steps:** In infra/modules/data/object_storage.tf, declare a bucket/container resource with versioning=enabled and public_access_block=true; Set lifecycle policy: retain all versions for 7 years (2555 days) minimum; add a rule transitioning versions older than 90 days to cheaper storage tier; Add a bucket policy: allow PutObject and GetObject only from worker_sg_id and mgmt_sg_id IAM roles; deny all other principals; Declare bucket encryption: AES-256 server-side encryption (per SEC-09 §2.6); Export bucket_name and bucket_arn
**Deliverable:** infra/modules/data/object_storage.tf with versioned, 7-year retention, encrypted batch file bucket and restrictive access policy
**Acceptance / logic checks:**
- versioning=enabled is set on the bucket resource
- Lifecycle rule retains objects for at least 2555 days (7 years)
- Bucket policy denies s3:PutObject and s3:GetObject from any principal not in the allowed worker/mgmt role list
- Server-side encryption is AES-256 (SSE-S3 or SSE-KMS)
- public_access_block (or equivalent) is set to block all public access
**Depends on:** 14.3-T06, 14.3-T07

### 14.3-T13 — Implement PostgreSQL multi-AZ IaC resource with WAL archiving and backup config  _(55 min)_
**Context:** PostgreSQL primary + replica must span 2 AZs in the Data Zone (OPS-13 §3.4). Backup policy: continuous WAL archiving + daily full snapshot; retention: 30 days full snapshots, 7-year WAL archive (OPS-13 §9.1). WAL archives written to object storage in a separate AZ from the primary. Restore target: RPO < 1 min WAL lag, RTO < 10 min replica promotion (OPS-13 §9.3). Encryption at rest: AES-256 at storage layer (SEC-09 §2.6). DB connection via TLS (SEC-09 §2.4). Connection pool sizes: prod=200, staging=80, int=20 (OPS-13 §10.2). PgBouncer recommended at DB tier.
**Steps:** In infra/modules/data/postgres.tf, declare a managed PostgreSQL primary instance in AZ-1 and a read replica in AZ-2 using the db_subnet_group from 14.3-T06; Enable automated backups: backup_retention_period=30 (days), enable_performance_insights=true, storage_encrypted=true (AES-256); Configure WAL archiving to the object storage bucket (from 14.3-T12) in a separate AZ; set archive path convention; Declare PgBouncer resource (container or managed pooler) with max_connections variable defaulting to env-specific pool size; Export db_primary_endpoint, db_replica_endpoint, db_port=5432
**Deliverable:** infra/modules/data/postgres.tf with multi-AZ PostgreSQL, 30-day backup, WAL archiving to separate-AZ bucket, and PgBouncer
**Acceptance / logic checks:**
- Primary and replica are in different AZs (confirmed by AZ attribute on each resource)
- backup_retention_period=30 and storage_encrypted=true (AES-256) are set
- WAL archive destination is the object storage bucket from 14.3-T12 in a distinct AZ from the primary
- PgBouncer max_connections matches: prod=200, staging=80, int=20 for the respective environment
- db_primary_endpoint and db_replica_endpoint are exported; both use TLS (ssl_mode=require or equivalent)
**Depends on:** 14.3-T06, 14.3-T12

### 14.3-T14 — Implement Redis (cache) and Message Queue IaC resources in Data Zone  _(45 min)_
**Context:** Redis cache in the Data Zone serves as the hot config cache and rate-lock store (OPS-13 §3.2; SAD-02 §11.3). Redis persistence is disabled in prod (cache is ephemeral, rebuilt on restart per OPS-13 §9.1). Multi-AZ configuration required. Redis is accessible only from API Zone on port 6379. Message Queue (SQS, Pub/Sub, or RabbitMQ) handles webhook dispatch and async batch triggers; accessible from API Zone and Worker Zone on MQ port 5672. Webhook queue depth alert P2 at 500, P1 at 2000 unprocessed (OPS-13 §10.3).
**Steps:** In infra/modules/data/cache.tf, declare a Redis cluster resource using cache_subnet_group from 14.3-T06, multi_az_enabled=true, at_rest_encryption_enabled=true, transit_encryption_enabled=true, snapshot_retention_limit=0 (no persistence); In infra/modules/data/queue.tf, declare a message queue resource with dead-letter queue configured, visibility timeout 30s, max receive count 3 (aligns with OPS-13 §6.3 retry policy); Declare queue depth alarms: warning at depth>500 (P2) and critical at depth>2000 (P1); Export redis_endpoint, redis_port=6379, queue_url, dlq_url
**Deliverable:** infra/modules/data/cache.tf and queue.tf with multi-AZ Redis (no persistence) and MQ with depth alarms
**Acceptance / logic checks:**
- Redis snapshot_retention_limit=0 (persistence disabled in prod environment)
- Redis multi_az_enabled=true and transit_encryption_enabled=true
- MQ dead-letter queue is declared and max_receive_count=3 matches the 3-retry policy
- Queue depth alarm at threshold=500 has severity P2 label; alarm at threshold=2000 has severity P1 label
- redis_endpoint, queue_url, and dlq_url are all exported outputs
**Depends on:** 14.3-T06

### 14.3-T15 — Implement observability stack IaC: Prometheus, Grafana, distributed tracing in Management Zone  _(50 min)_
**Context:** The Observability Stack lives in the Management Zone (OPS-13 §3.2): Prometheus for metrics, Grafana for dashboards, OpenTelemetry collector + Jaeger/Tempo for distributed tracing (OPS-13 §7.3). All services emit JSON logs; log aggregation ships to a central store. Key metrics: api_request_rate, api_error_rate, api_latency_p50/p95/p99 (RED), plus USE metrics for PostgreSQL, Redis, MQ, SFTP worker (OPS-13 §7.1). Distributed tracing propagates trace_id (W3C TraceContext) across all 8 transaction steps.
**Steps:** In infra/modules/monitoring/main.tf, declare: Prometheus deployment config (or managed workspace), Grafana instance with managed storage, OpenTelemetry collector DaemonSet config reference, Jaeger or Tempo backend resource; All monitoring components in mgmt_subnet from 14.3-T07; SG allows inbound scrape from all zone SGs on port 9090 (Prometheus) and 4317/4318 (OTEL gRPC/HTTP); Declare log aggregation shipper config (Fluent Bit or equivalent) as a DaemonSet sidecar reference with JSON output format; Export prometheus_endpoint, grafana_endpoint, otel_collector_endpoint; Add variable retention_days defaulting to 90 for metrics and traces
**Deliverable:** infra/modules/monitoring/main.tf declaring Prometheus, Grafana, OTEL collector, and tracing backend in Management Zone
**Acceptance / logic checks:**
- All monitoring resources are in mgmt_subnet and access-controlled by mgmt_sg_id
- Prometheus scrape port 9090 is open inbound from all internal zone SGs but not from 0.0.0.0/0
- OTEL collector endpoints (4317 gRPC, 4318 HTTP) are reachable from API Zone and Worker Zone only
- Log format is JSON with required fields: timestamp, level, service, env, trace_id, span_id (per OPS-13 §7.2)
- prometheus_endpoint, grafana_endpoint, and otel_collector_endpoint are all exported
**Depends on:** 14.3-T07

### 14.3-T16 — Declare feature flag configuration resource in IaC  _(30 min)_
**Context:** Five Phase 1 feature flags must be managed as config (not code), loaded at service startup from the config/secrets manager (OPS-13 §5.2): FEATURE_LIVE_FX_FEED=false, FEATURE_PARTNER_REFUND_API=false, FEATURE_OUTBOUND_PAYMENTS=false, FEATURE_BOK_REPORTING=false, FEATURE_MULTI_SCHEME_ROUTING=false. All default to false for Phase 1. Changing a flag requires a config update and service restart, not a deployment. Flags are stored in the secrets/config manager under path gmepay/<env>/feature-flags/<flag-name>.
**Steps:** In infra/modules/secrets/feature_flags.tf, declare each of the 5 flags as a parameter or secret resource under path gmepay/${var.env}/feature-flags/<flag-name> with default value false; Add a variable allow_flag_override (bool, default=false) that must be explicitly set to true in non-prod envs to change a flag to true; Output a map feature_flag_arns with flag names as keys; Add a comment block documenting that FEATURE_BOK_REPORTING requires OI-03 resolution before enabling; Verify that none of the flags are hardcoded in any application Dockerfile or image config
**Deliverable:** infra/modules/secrets/feature_flags.tf declaring all 5 Phase 1 feature flags at gmepay/<env>/feature-flags/ with false defaults
**Acceptance / logic checks:**
- All 5 flags (FEATURE_LIVE_FX_FEED, FEATURE_PARTNER_REFUND_API, FEATURE_OUTBOUND_PAYMENTS, FEATURE_BOK_REPORTING, FEATURE_MULTI_SCHEME_ROUTING) are declared
- Default value for every flag is false
- Flag paths follow gmepay/<env>/feature-flags/<flag-name> convention
- feature_flag_arns map is exported with all 5 keys
- FEATURE_BOK_REPORTING resource includes the OI-03 comment
**Depends on:** 14.3-T11

### 14.3-T17 — Implement auto-scaling and HPA IaC for stateless service pods  _(45 min)_
**Context:** Scaling rules per OPS-13 §10.1: Hub API min=3 max=20, scale-out at CPU>70% for 3 min or p95 latency>300ms, scale-in at CPU<30% for 10 min. Admin System min=2 max=8, CPU>70% for 3 min. Webhook Dispatcher min=2 max=10, scale-out at queue depth>200, scale-in at depth<20. Batch Worker: always single-instance (max=1, manual only) to prevent duplicate SFTP submissions to ZeroPay. All scaling config in IaC, not in app code.
**Steps:** In infra/modules/compute/scaling.tf, declare HPA or auto-scaling group for hub-api: min_replicas=3, max_replicas=20, cpu_threshold=70, scale_in_stabilization=600s (10 min), custom metric for p95 latency threshold 300ms; Declare HPA for admin-system: min_replicas=2, max_replicas=8, cpu_threshold=70; Declare HPA for webhook-dispatcher: min_replicas=2, max_replicas=10, with SQS/queue depth metric: scale_out_threshold=200, scale_in_threshold=20; Declare batch-worker as a single-replica deployment or CronJob with max_replicas=1; add comment: single-instance required to prevent duplicate ZeroPay SFTP submissions; Export scaling policy IDs for each service
**Deliverable:** infra/modules/compute/scaling.tf with HPA/ASG configs for all 4 services with correct thresholds and single-instance constraint for batch worker
**Acceptance / logic checks:**
- hub-api HPA: min_replicas=3, max_replicas=20, cpu scale-out threshold=70%, scale-in stabilization=600 seconds
- admin-system HPA: min_replicas=2, max_replicas=8
- webhook-dispatcher HPA: scale-out queue depth threshold=200, scale-in threshold=20
- batch-worker max_replicas=1 and the single-instance comment is present
- No service has max_replicas exceeding the spec (hub-api>20, admin>8, webhook>10 would be violations)
**Depends on:** 14.3-T04, 14.3-T05, 14.3-T07

### 14.3-T18 — Implement CI/CD pipeline stage gates in IaC (pipeline-as-code)  _(55 min)_
**Context:** The CI/CD pipeline has 8 ordered stages per OPS-13 §4.1: Build (zero lint errors), Unit Test (all pass, coverage >= 80%), Security scan (no critical/high CVEs, no secrets), Integration test, Artifact (signed container image tagged <git-sha>+<branch>+<semver>), Deploy to int (auto), Deploy to staging (auto on release/*, manual approval gate), Deploy to prod (Release Manager approval + Change Record + QA sign-off). Automated rollback if smoke tests fail within 5 minutes post-deploy (OPS-13 §4.4).
**Steps:** In infra/ci/pipeline.yaml (or equivalent pipeline-as-code file), declare 8 sequential stages matching OPS-13 §4.1 with explicit gate conditions; Stage 3 security scan: add SAST tool (e.g. Semgrep), dependency scan (e.g. Trivy), secrets scan (e.g. Gitleaks); gate: fail on critical/high CVE or any detected secret; Stage 5 artifact: image tag format <git-sha>-<branch>-<semver>; add image signing step; push to artifact registry; Stage 7 deploy-to-staging: add manual approval gate with required approver role = release-manager; Stage 8 deploy-to-prod: add change record creation step and dual-approval gate; add post-deploy 5-minute smoke test with auto-rollback on failure
**Deliverable:** infra/ci/pipeline.yaml (or equivalent) declaring all 8 pipeline stages with correct gate conditions, approval steps, and auto-rollback
**Acceptance / logic checks:**
- Stages are declared in order 1-8; a stage cannot proceed if its gate condition is not met
- Stage 3 fails the pipeline on any critical/high CVE finding or detected secret
- Stage 5 produces an image tag containing git-sha, branch name, and semver; image is signed
- Stage 7 has an explicit manual approval gate before execution
- Stage 8 includes a post-deploy wait of 5 minutes and an automatic rollback trigger if smoke tests fail
**Depends on:** 14.3-T01

### 14.3-T19 — Implement database migration IaC integration (Flyway/Liquibase config and gate)  _(40 min)_
**Context:** All schema changes use a versioned migration tool (Flyway or Liquibase) per OPS-13 §4.6. Rules: unique sequential version number per migration, applied automatically at deploy time before the new app version starts, must be backward-compatible (additive-only in same release as code change), stored in db/migrations/ in repo, no down-migration scripts in prod. Migration runs logged with version, timestamp, duration, operator identity. Reversibility via blue-green strategy only (not rollback scripts).
**Steps:** In infra/modules/compute/migrations.tf, declare a one-shot Kubernetes Job (or equivalent init container) that runs the migration tool against the prod DB before the new app version starts; Configure the migration tool to read scripts from db/migrations/ with flyway.locations=filesystem:db/migrations or equivalent; Add a post-migration validation step: query the migration history table and assert the expected version is the latest applied; Gate the main app deployment on successful completion of the migration job (depends_on or pipeline condition); Declare a migrations_log_table variable (default=flyway_schema_history) exported for audit queries
**Deliverable:** infra/modules/compute/migrations.tf with Flyway/Liquibase Job resource and pre-app-start gate, plus migration log config
**Acceptance / logic checks:**
- Migration job runs before the new app deployment starts (depends_on or pipeline ordering enforces this)
- Migration script directory is db/migrations/ (not bundled in the image)
- Post-migration validation asserts the latest migration version matches the expected value
- No down migration or rollback script resource is declared (reversibility is blue-green only)
- migrations_log_table variable is exported and defaults to flyway_schema_history or equivalent
**Depends on:** 14.3-T06, 14.3-T18

### 14.3-T20 — Implement blue-green and canary deployment IaC config for zero-downtime releases  _(50 min)_
**Context:** Blue-green deployment for major releases and schema-changing deployments: provision a parallel environment, run smoke tests, then cut traffic via DNS/LB pointer flip (OPS-13 §4.5). Canary for high-risk API changes: route 5% of traffic to new version, monitor error rates for 15 minutes, promote to 100% or auto-rollback on SLO breach (OPS-13 §4.5). Batch worker: drain current job, then redeploy; never run two batch worker versions simultaneously (duplicate SFTP prevention). Blue-green rollback = DNS/LB pointer flip back.
**Steps:** In infra/modules/compute/deploy_strategy.tf, declare a weighted target group or traffic split resource: blue_weight=95, green_weight=5 for canary; blue_weight=100 for stable; Add a canary_monitor resource: check api_error_rate and api_latency_p95 for 15 minutes; if 5xx > 1% or p95 > 500ms, trigger rollback action (weight back to 100/0); Declare a blue-green swap resource: flip LB target group from blue to green on manual approval or auto-trigger; For batch-worker, declare a drain_wait resource: waits for running job to complete (max_drain_seconds=3600) before new deployment starts; Export canary_traffic_percent, active_color (blue/green)
**Deliverable:** infra/modules/compute/deploy_strategy.tf with canary (5%/95% split + 15-min SLO monitor) and blue-green swap resources, plus batch-worker drain
**Acceptance / logic checks:**
- Canary initial split is green_weight=5 (5%) to new version
- Canary monitor fires rollback if 5xx error rate exceeds 1% or p95 latency exceeds 500ms within 15 minutes
- Blue-green swap resource requires explicit approval or a passing smoke test gate before traffic cut
- Batch-worker drain_wait prevents new deployment until running job finishes; max drain time is 3600 seconds
- active_color and canary_traffic_percent are exported outputs
**Depends on:** 14.3-T04, 14.3-T05, 14.3-T17, 14.3-T19

### 14.3-T21 — Implement DR environment IaC for full-region failover (secondary region)  _(55 min)_
**Context:** DR environment must be pre-provisioned in a secondary region per OPS-13 §9.4.2. On failover: DNS updated to DR LB, PostgreSQL restored from WAL archive, object storage accessible (geo-redundant bucket), secrets updated, batch worker can reach ZeroPay SFTP from DR region. RTO target: < 4 hours full region failure (OPS-13 §9.3). DR drill annually (OPS-13 §9.5). The DR env does not run live traffic; it is a warm standby.
**Steps:** In infra/environments/dr/, create a main.tf that instantiates the same network and compute modules as prod but with dr_region variable, scaled-down compute (min pods only), and is_dr=true flag; Declare a DNS failover record resource: primary weight=100 to prod LB, secondary weight=0 to DR LB; flip is a variable dr_active=false/true; Declare a DR secrets sync resource that copies prod secrets to DR secrets manager on each rotation event; Add a DR drill runbook IaC resource (null_resource or similar) that triggers smoke tests against the DR environment; Output dr_lb_endpoint, dr_db_endpoint, dr_active flag
**Deliverable:** infra/environments/dr/main.tf with warm standby DR environment, DNS failover record, and secrets sync resource
**Acceptance / logic checks:**
- DR network topology mirrors prod zone structure (same 5 zones) but in a different region variable
- DNS failover resource declares primary=prod LB and secondary=DR LB; dr_active=false by default
- DR secrets sync resource is triggered by secret rotation events, not manual only
- Smoke test null_resource runs the same checks as the prod post-deploy smoke test
- dr_lb_endpoint and dr_active are exported; dr_active=false in default state
**Depends on:** 14.3-T08, 14.3-T11, 14.3-T12, 14.3-T13

### 14.3-T22 — Implement alert catalog IaC resources for all P1/P2 zone-level alerts  _(50 min)_
**Context:** Alert catalog from OPS-13 §7.5 must be declared in IaC. Key network/infra alerts: API p95 latency > 500ms for 5 min (P2), 5xx error rate > 1% for 5 min (P1), payment endpoint health check failure > 60s (P1), SFTP connection failure after 3 retries (P1), prefunding critical < $2000 (P1), prefunding low < $10000 (P2), pool identity failure (P1, block commit), batch job FAILED after 3 retries (P1). All alerts PagerDuty-compatible. P1 = PagerDuty + Ops Slack; P2 = Ops Slack; P3 = Ops email.
**Steps:** In infra/modules/monitoring/alerts.tf, declare alarm resources for each catalog entry, referencing metric names from OPS-13 §7.1: api_latency_p95, api_error_rate, api_request_rate; Configure notification targets: p1_sns_topic (PagerDuty webhook + Ops Slack), p2_sns_topic (Ops Slack), p3_sns_topic (Ops email); Declare SFTP connectivity alarm: triggers if SFTP connection fails 3 consecutive times (error count >= 3 within 5 min); Declare prefunding balance alarms: low_balance (< 10000 USD, P2) and critical_balance (< 2000 USD, P1) per partner; Export alert_arns map keyed by alert name
**Deliverable:** infra/modules/monitoring/alerts.tf declaring all catalog P1/P2/P3 alarms with correct thresholds and notification routing
**Acceptance / logic checks:**
- api_latency_p95 alarm threshold=500ms, period=5min, severity=P2, routes to p2_sns_topic
- api_error_rate alarm threshold=1%, period=5min, severity=P1, routes to p1_sns_topic
- SFTP alarm fires after 3 consecutive connection failures and routes to P1 topic
- prefunding critical alarm fires at balance < 2000 USD and routes to P1 topic; low alarm at < 10000 USD routes to P2
- payment endpoint health check alarm fires after 60 seconds of failure, routes to P1 topic
**Depends on:** 14.3-T15, 14.3-T14

### 14.3-T23 — Implement backup schedule and WAL retention IaC resources  _(45 min)_
**Context:** Backup policy per OPS-13 §9.1: PostgreSQL continuous WAL archiving + daily full snapshot, 30 days full, 7-year WAL archive. WAL archives to object storage in separate AZ. Redis: no backup (persistence disabled). Object storage: versioned geo-redundant, 7 years (same bucket as 14.3-T12). Container images: artifact registry with geo-replication, 1-year retention. Encryption: all backups AES-256, backup encryption key stored separately from DEK (SEC-09 §2.6). Monthly restore test drill (OPS-13 §9.2).
**Steps:** In infra/modules/data/backups.tf, declare automated backup schedule for PostgreSQL: backup_window=daily, retention_period=30 days; Declare WAL archive lifecycle rule in the object storage bucket: archive path gmepay/wal/, retention 2555 days (7 years); Declare artifact registry replication config: geo-replicate to secondary region, image retention policy 365 days; Declare a monthly restore test job: a scheduled Lambda or CronJob that restores latest snapshot to a test instance, runs integrity checks, and emails results to the DR log; Export backup_bucket_arn, wal_archive_path, restore_test_schedule
**Deliverable:** infra/modules/data/backups.tf with PostgreSQL daily backup, WAL 7-year archive, image registry replication, and monthly restore test job
**Acceptance / logic checks:**
- PostgreSQL backup retention_period=30 days and backup_window is set
- WAL archive lifecycle rule in object storage retains for 2555 days minimum
- Artifact registry replication targets a different region from primary
- Monthly restore test CronJob schedule is set to run once per month (e.g. cron(0 2 1 * *))
- All backup resources reference AES-256 encryption (storage_encrypted=true or kms_key_id set)
**Depends on:** 14.3-T12, 14.3-T13

### 14.3-T24 — Write unit tests for network zone SG rule correctness (no cross-zone leakage)  _(55 min)_
**Context:** Security group rules must enforce zero cross-zone leakage: Worker Zone has no inbound; Data Zone accepts only API/Worker Zone SGs on specific ports; Management Zone accepts only VPN CIDR. These are verifiable with IaC unit tests (e.g. Terratest, Pulumi test framework, or OPA/Conftest policies). Test vectors: Worker SG with any inbound rule should fail. Data SG allowing 0.0.0.0/0 on port 5432 should fail. DMZ SG allowing port 80 inbound should fail. API SG allowing inbound from non-DMZ source should fail.
**Steps:** Create infra/tests/network_sg_test.go (or .py / conftest policy file); Test 1: assert Worker Zone SG has zero inbound rules; Test 2: assert Data Zone SG port 5432 source is api_sg_id only (not 0.0.0.0/0); Test 3: assert DMZ SG has no inbound rule for port 80; Test 4: assert Management Zone SG inbound is from mgmt_vpn_cidr only (not any SG from public zone); Test 5: assert API Zone SG inbound 443 source is dmz_sg_id only; run all tests with terraform plan output or conftest
**Deliverable:** infra/tests/network_sg_test.go (or equivalent) with 5 automated zone SG rule assertions
**Acceptance / logic checks:**
- Test 1 passes only when Worker SG inbound_rules list is empty
- Test 2 fails if Data SG port 5432 rule has source 0.0.0.0/0
- Test 3 fails if DMZ SG has any inbound rule with from_port=80 or to_port=80
- Test 4 passes only when Mgmt SG inbound source matches mgmt_vpn_cidr variable value
- All 5 tests pass on the IaC declared in T02-T08 and fail on intentionally misconfigured inputs
**Depends on:** 14.3-T08

### 14.3-T25 — Write unit tests for secrets path convention and cross-service access denial  _(45 min)_
**Context:** Secret paths must follow gmepay/<env>/<service>/<secret-name>. CI pipeline must not access prod paths. hub-api must not read batch-worker paths. These are testable via policy simulation or IaC test framework. Test vectors: hub-api role accessing gmepay/prod/batch-worker/zeropay-sftp-key should be denied. CI role accessing gmepay/prod/hub-api/db-password should be denied. Any role accessing gmepay/prod/hub-api/db-password with a path outside its declared prefix should be denied.
**Steps:** Create infra/tests/secrets_policy_test.go (or .py / OPA test); Test 1: simulate hub-api IAM/Vault role attempting GetSecretValue on gmepay/prod/batch-worker/zeropay-sftp-key -- expect DENY; Test 2: simulate ci role attempting GetSecretValue on gmepay/prod/hub-api/db-password -- expect DENY; Test 3: simulate batch-worker role on gmepay/prod/batch-worker/zeropay-sftp-key -- expect ALLOW; Test 4: simulate any role attempting PutSecretValue on gmepay/prod/* -- expect DENY (read-only from app; write via IaC only); Run all tests in CI against the policy declarations from 14.3-T11
**Deliverable:** infra/tests/secrets_policy_test.go with 4 secrets access policy assertions covering cross-service denial and CI prod isolation
**Acceptance / logic checks:**
- Test 1 asserts hub-api access to batch-worker path returns DENY
- Test 2 asserts ci role access to prod path returns DENY
- Test 3 asserts batch-worker access to its own prod path returns ALLOW
- Test 4 asserts no application role can write secrets (PutSecretValue is denied)
- All tests are runnable in CI without live cloud credentials (use mock/IAM simulator)
**Depends on:** 14.3-T11

### 14.3-T26 — Write unit tests for pipeline gate conditions (security scan failure, approval gates)  _(50 min)_
**Context:** The CI/CD pipeline must gate on: security scan finding a critical CVE (stage 3 must fail the build), a missing manual approval for staging deploy, and auto-rollback firing after post-deploy smoke test failure (stage 8). These must be tested with pipeline mock or test framework. Test vectors: injecting a known-vulnerable dependency version should cause stage 3 to fail. Removing the manual approval step from staging should cause the pipeline definition to be rejected by lint. Injecting a failing smoke test should trigger rollback and set deployment state to ROLLED_BACK.
**Steps:** Create infra/tests/pipeline_gate_test.yaml (or .py CI mock test); Test 1: inject a dependency with a known critical CVE into a test requirements file; assert pipeline stage 3 exits non-zero; Test 2: assert that the pipeline definition without a manual approval gate on deploy-to-staging fails pipeline lint (using yamllint schema or OPA); Test 3: mock a smoke test that returns HTTP 500 after deploy; assert the pipeline triggers rollback and the final deployment state is ROLLED_BACK; Test 4: assert that the artifact stage produces an image tag matching the pattern <git-sha>-<branch>-<semver> via regex; Run all tests in CI; all must pass before any infrastructure is applied to prod
**Deliverable:** infra/tests/pipeline_gate_test.yaml (or equivalent) with 4 pipeline gate assertions covering CVE fail, approval enforcement, rollback, and image tagging
**Acceptance / logic checks:**
- Test 1 causes stage 3 to fail when critical CVE dependency is present; build does not reach stage 4
- Test 2 rejects a pipeline definition that lacks manual approval on the staging deploy stage
- Test 3 confirms deployment state is ROLLED_BACK after smoke test 500 response
- Test 4 validates image tag format with regex matching <40-char-sha>-<branch-name>-<semver>
- All 4 tests are runnable in CI without a live cloud environment
**Depends on:** 14.3-T18

### 14.3-T27 — Write integration test: full network zone plan produces correct cross-zone rules  _(55 min)_
**Context:** An integration-level IaC test (terraform plan or pulumi preview in CI ephemeral env) must verify that the composed network module produces the correct security group rules for all 5 zones without requiring live cloud credentials. This test uses the plan output (JSON) and asserts on resource attributes. It validates the full wiring from 14.3-T08 in a single test run.
**Steps:** Create infra/tests/network_integration_test.go (or Python test using terraform-exec or Pulumi automation API); Run terraform plan -out=plan.json on infra/modules/network/ with test variable values; Parse plan.json and assert: (a) exactly 5 subnet resources, one per zone; (b) Worker SG has 0 ingress rules; (c) Data SG ingress rules reference SG IDs not CIDRs; (d) NAT Gateway static IP is non-null; (e) all subnets span at least 2 distinct AZ values; Assert that no security group rule has cidr_blocks = 0.0.0.0/0 for inbound on Data or Management zones; Confirm plan has no resource with encryption disabled (storage_encrypted=false or similar)
**Deliverable:** infra/tests/network_integration_test.go asserting correct plan output for composed network module across all 5 zones
**Acceptance / logic checks:**
- Test parses real terraform plan JSON (not mocked) in CI ephemeral environment
- Assertion (b) confirms Worker SG ingress_rules count == 0
- Assertion (c) confirms Data SG port 5432 rule source is a security_group_id reference, not a CIDR block
- Assertion (e) confirms no managed resource in Data Zone has storage_encrypted=false
- Test runs in under 5 minutes in CI (plan-only, no apply)
**Depends on:** 14.3-T08, 14.3-T24

### 14.3-T28 — Author go-live infrastructure checklist IaC verification script  _(55 min)_
**Context:** OPS-13 §12.1.1 requires a signed pre-go-live checklist for infrastructure: multi-AZ confirmed, LB+WAF+TLS validated, auto-scaling tested at 500 TPS synthetic load, DR env provisioned, DR drill completed, PostgreSQL backup+restore tested in last 30 days. These checks must be automatable. A pre-go-live script queries IaC state and live cloud resources to verify each checklist item and outputs a signed JSON report.
**Steps:** Create infra/scripts/golive_infra_check.py (or shell script); Check 1: query IaC state and assert both API Zone subnets are in distinct AZs; Check 2: assert DMZ LB has a WAF policy attached and TLS listener minimum version = TLS 1.2; Check 3: assert auto-scaling min/max thresholds match spec (hub-api 3/20, admin 2/8, webhook 2/10); Check 4: assert DR environment exists in secondary region with dr_active=false; Check 5: query PostgreSQL backup metadata and assert last successful backup was within 30 days; Output a JSON report with each check name, status (PASS/FAIL), and timestamp; exit non-zero if any check FAILs
**Deliverable:** infra/scripts/golive_infra_check.py outputting a JSON checklist report with PASS/FAIL per item and non-zero exit on any failure
**Acceptance / logic checks:**
- Script exits 0 when all 5 checks pass and exits 1 when any check fails
- Check 1 output states AZ names for both API Zone subnets and fails if they are identical
- Check 2 fails if LB TLS policy is weaker than TLS 1.2 (e.g. TLS 1.0 policy name is detected)
- Check 5 fails if last_backup_time is more than 30 days before script run time
- Output JSON contains keys: check_name, status, detail, run_at for each of the 5 checks
**Depends on:** 14.3-T03, 14.3-T04, 14.3-T13, 14.3-T17, 14.3-T21, 14.3-T23

### 14.3-T29 — Document IaC module usage, variable reference, and runbook integration in infra/README.md  _(40 min)_
**Context:** A developer with zero project context must be able to provision any environment from the IaC alone. The README must cover: directory layout, how to run plan/apply per environment, all required input variables, the secret path convention gmepay/<env>/<service>/<secret-name>, the 5 network zones and their traffic rules, how to trigger the DR failover (dr_active flag), and cross-references to OPS-13 runbook procedures (deploy, rollback, SFTP key rotation). This is the operational handoff document.
**Steps:** Update infra/README.md with sections: Overview, Directory Layout, Network Zones (table from OPS-13 §3.2), Required Variables per Environment, Secret Path Convention, How to Apply (commands), DR Failover Instructions (setting dr_active=true), Links to OPS-13 runbook sections; Add a variables reference table listing every required input variable, its type, default, and which environment(s) override it; Add a known open items section referencing OI-OPS-02 (ZeroPay SFTP IP confirmation) and OI-OPS-03 (scaling threshold validation in staging); Ensure all runbook cross-references use section numbers from OPS-13 (e.g. DR failover = OPS-13 §9.4.2, SFTP key rotation = OPS-13 §8.4)
**Deliverable:** infra/README.md with complete IaC usage guide, zone table, variable reference, secret path convention, DR instructions, and open items
**Acceptance / logic checks:**
- Network zones table lists all 5 zones with contents and traffic rules matching OPS-13 §3.2
- Variables reference table includes vpc_cidr, zone_cidrs, mgmt_vpn_cidr, zeropay_sftp_cidr, db_connection_pool_size at minimum
- DR failover section explains setting dr_active=true and expected outcome
- Open items section calls out OI-OPS-02 and OI-OPS-03 by reference code
- A developer unfamiliar with the project can apply the dev environment from the README instructions alone without reading any other doc
**Depends on:** 14.3-T08, 14.3-T21, 14.3-T28


## WBS 14.4 — Database HA, cache, queue
### 14.4-T01 — Define Terraform module skeleton for Data Zone infrastructure  _(30 min)_
**Context:** GMEPay+ Data Zone (OPS-13 §3.2) contains PostgreSQL primary+replica, Redis cache, and a message queue. All three components are cloud-agnostic container workloads. The Terraform (or Pulumi) IaC lives in infra/modules/data-zone/. No actual resources are provisioned yet; this ticket creates the module skeleton and variable contract. Secret paths follow gmepay/<env>/<service>/<secret-name> convention (OPS-13 §4.7).
**Steps:** Create directory infra/modules/data-zone/ with main.tf, variables.tf, outputs.tf, and versions.tf.; Declare required input variables: env (string, enum dev/int/staging/prod), region (string), az_primary (string), az_secondary (string), db_instance_class (string), redis_node_type (string), mq_engine (string, enum rabbitmq/kafka), tags (map).; Declare output stubs (null resources) for: db_primary_endpoint, db_replica_endpoint, redis_primary_endpoint, mq_broker_endpoint.; Add a versions.tf pinning Terraform >= 1.6 and provider versions.; Commit with a descriptive header comment referencing OPS-13 §3.2 and NFR-10 §4.2.
**Deliverable:** infra/modules/data-zone/ skeleton with variables.tf and outputs.tf; terraform validate passes with zero errors.
**Acceptance / logic checks:**
- terraform validate inside infra/modules/data-zone/ returns success.
- All 8 input variables are declared with type constraints and descriptions.
- All 4 output stubs are declared.
- No hardcoded environment-specific values (endpoint strings, credentials) appear anywhere in the module.
- Running terraform init downloads no providers (outputs are stubs only).

### 14.4-T02 — Provision PostgreSQL primary instance via Terraform module  _(45 min)_
**Context:** NFR-10 §4.2 requires PostgreSQL primary + at least 1 synchronous replica. OPS-13 §3.4 requires multi-AZ. Phase 1 sizing: 4 vCPU / 16 GB RAM primary (NFR-10 §3.4). Connection pool baseline: 200 total connections across all API pods in prod, 20 for batch worker, 20 for admin (OPS-13 §10.2). The managed PostgreSQL service (RDS/Cloud SQL/Azure DB) must support WAL streaming for continuous archiving. DECIMAL(20,8) column type is required for all monetary fields per NFR-10 §5.1.
**Steps:** Inside infra/modules/data-zone/main.tf, add a PostgreSQL primary resource block using the cloud provider's managed DB resource.; Set instance class from var.db_instance_class; availability zone from var.az_primary.; Enable automated backups with retention = 30 days; enable WAL archiving to object storage path /gmepay/<env>/wal/.; Set max_connections parameter = 300 (headroom above 200+20+20+20 pool baseline).; Set deletion_protection = true for prod (conditional on var.env == prod).; Export db_primary_endpoint output.
**Deliverable:** Terraform resource for PostgreSQL primary in infra/modules/data-zone/main.tf with backup and WAL archiving configured.
**Acceptance / logic checks:**
- terraform plan in staging workspace shows exactly one DB instance resource with multi_az = false (primary AZ only at this step).
- Backup retention is 30 days in the plan output.
- max_connections parameter is 300 in the DB parameter group.
- deletion_protection = true only when env = prod in the plan diff.
- WAL archive destination path matches /gmepay/<env>/wal/ pattern.
**Depends on:** 14.4-T01

### 14.4-T03 — Provision PostgreSQL synchronous replica in same AZ and async DR replica in secondary AZ  _(45 min)_
**Context:** NFR-10 §4.2: primary + at least 1 synchronous read replica in same AZ, plus 1 async replica in secondary AZ for DR. NFR-10 §6.1: RPO = 0 for payment processing (synchronous replication). OPS-13 §9.4.1: replica is promoted via pg_ctl promote or cloud-equivalent. Replication lag must stay < 1 second under load (NFR-10 §8.4). The synchronous replica serves read queries (partner portal transaction history, NFR-10 §3.2) via db_replica_endpoint output.
**Steps:** Add a synchronous read replica resource block in infra/modules/data-zone/main.tf, bound to az_primary, replicate_source_db pointing to primary.; Set replication mode to synchronous (synchronous_commit = on in PostgreSQL parameter group).; Add a second async read replica resource bound to var.az_secondary for DR use.; Export db_replica_endpoint (synchronous replica endpoint) and db_dr_endpoint (async replica endpoint) as outputs.; Tag both replicas with role = sync-replica and role = dr-replica respectively.
**Deliverable:** Two replica resources in infra/modules/data-zone/main.tf; db_replica_endpoint and db_dr_endpoint outputs populated.
**Acceptance / logic checks:**
- terraform plan shows two replica resources; first in az_primary, second in az_secondary.
- synchronous_commit = on parameter is applied to the primary parameter group (sync writes confirmed).
- db_replica_endpoint output resolves to the synchronous replica host in staging plan.
- Both replicas have source_db_instance_identifier pointing to the primary.
- No replica has deletion_protection enabled (replicas are disposable; primary is protected).
**Depends on:** 14.4-T02

### 14.4-T04 — Configure PgBouncer connection pooler in front of PostgreSQL primary  _(45 min)_
**Context:** OPS-13 §10.2 recommends PgBouncer at the DB tier to manage burst connection demand. Prod pool: 200 total API connections + 20 batch + 20 admin = 240 connections; PgBouncer sits between application services and PostgreSQL, so PostgreSQL max_connections = 300 provides headroom. PgBouncer runs in transaction pooling mode (required for SELECT FOR UPDATE used in prefunding deduction, NFR-10 §5.2 — note: session-level locks require session mode; use session mode for write path, transaction mode for read path). Secret path for DB credentials: gmepay/<env>/hub-api/db-password (OPS-13 §4.7).
**Steps:** Add a PgBouncer Kubernetes Deployment manifest in infra/k8s/pgbouncer/ (pgbouncer-deployment.yaml, pgbouncer-configmap.yaml, pgbouncer-service.yaml).; Configure pgbouncer.ini: pool_mode = session for write-path (hub-api-write pool, max_client_conn=220, default_pool_size=50), pool_mode = transaction for read-path (hub-api-read pool, points to replica endpoint, max_client_conn=100, default_pool_size=30).; Reference DB host from Terraform outputs via environment variable injection.; Add a Kubernetes Service of type ClusterIP exposing port 5432 for each pool (pgbouncer-write-svc, pgbouncer-read-svc).; Document the two DSNs in infra/k8s/pgbouncer/README.md.
**Deliverable:** infra/k8s/pgbouncer/ with Deployment, ConfigMap, and Service manifests for write-path (session mode) and read-path (transaction mode) pools.
**Acceptance / logic checks:**
- kubectl apply --dry-run=client on all manifests succeeds with no errors.
- pgbouncer.ini shows pool_mode = session for write pool pointing to primary endpoint.
- pgbouncer.ini shows pool_mode = transaction for read pool pointing to replica endpoint.
- max_client_conn across both pools does not exceed 320 (< PostgreSQL max_connections = 300 + safety margin).
- Service names pgbouncer-write-svc and pgbouncer-read-svc are present in the manifests.
**Depends on:** 14.4-T03

### 14.4-T05 — Create Flyway baseline migration and db/migrations/ directory structure  _(30 min)_
**Context:** OPS-13 §4.6 mandates all schema changes use Flyway (or Liquibase) with sequential versioned scripts stored in db/migrations/. Every migration has a unique version, runs automatically at deploy time before the new application version starts, and is logged with version, timestamp, duration, and operator identity. There are no down-migration scripts in production; reversibility is achieved via blue-green deployment. This ticket establishes the baseline so all subsequent schema tickets can add versioned files.
**Steps:** Create directory db/migrations/ in the repository root.; Add V1__baseline.sql as the Flyway baseline migration; it creates the flyway_schema_history table comment and a placeholder schema_version table with columns: version VARCHAR(20), applied_at TIMESTAMPTZ, applied_by VARCHAR(100).; Add flyway.conf (or flyway.toml) with placeholders: flyway.url = ${DB_URL}, flyway.user = ${DB_USER}, flyway.password = ${DB_PASSWORD}, flyway.locations = filesystem:db/migrations.; Add a CI step in .github/workflows/db-migrate.yml (or equivalent) that runs flyway migrate on integration and staging deploy events.; Add db/migrations/README.md with the naming convention: V<N>__<description>.sql where N is zero-padded 4 digits.
**Deliverable:** db/migrations/V1__baseline.sql, flyway.conf, CI migration step, and db/migrations/README.md.
**Acceptance / logic checks:**
- flyway info against a clean local PostgreSQL instance shows V1 as Pending then Success after flyway migrate.
- flyway_schema_history table is created with migration V1 recorded.
- flyway.conf contains no hardcoded credentials (only ${env-var} placeholders).
- CI workflow step name contains migrate and references flyway migrate command.
- README.md documents the V<N>__<description>.sql naming rule.
**Depends on:** 14.4-T03

### 14.4-T06 — Write Flyway migration for core financial tables (DECIMAL(20,8) monetary columns)  _(45 min)_
**Context:** NFR-10 §5.1 mandates all monetary amounts use DECIMAL(20,8), not floating-point. Tables needed for payment processing: transactions (id UUID PK, partner_id UUID, scheme_id UUID, direction VARCHAR(20), collection_amount DECIMAL(20,8), collection_ccy CHAR(3), send_amount DECIMAL(20,8), settle_a_ccy CHAR(3), collection_usd DECIMAL(20,8), payout_usd_cost DECIMAL(20,8), collection_margin_usd DECIMAL(20,8), payout_margin_usd DECIMAL(20,8), offer_rate_coll DECIMAL(20,8), cross_rate DECIMAL(20,8), cost_rate_coll DECIMAL(20,8), cost_rate_pay DECIMAL(20,8), service_charge DECIMAL(20,8), target_payout DECIMAL(20,8), payout_ccy CHAR(3), status VARCHAR(30), settlement_batch_id UUID, created_at TIMESTAMPTZ, committed_at TIMESTAMPTZ, trace_id UUID). KRW amounts: scale 0 decimals enforced at application layer, stored as DECIMAL(20,8). This migration must be additive only.
**Steps:** Create db/migrations/V2__core_financial_tables.sql.; Define the transactions table with all columns listed in context, plus an idempotency_key VARCHAR(36) UNIQUE NOT NULL column.; Add index: CREATE INDEX idx_transactions_partner_status ON transactions(partner_id, status);; Add index: CREATE INDEX idx_transactions_settlement_batch ON transactions(settlement_batch_id) WHERE settlement_batch_id IS NOT NULL;; Add index: CREATE INDEX idx_transactions_committed_at ON transactions(committed_at) WHERE committed_at IS NOT NULL; (for daily pool-identity reconciliation job).; Add a CHECK constraint ensuring status IN ('QUOTED','INITIATED','PREFUND_DEDUCTED','SCHEME_SENT','SCHEME_RESPONDED','COMMITTED','FAILED','UNCERTAIN').
**Deliverable:** db/migrations/V2__core_financial_tables.sql with transactions table, 3 indexes, and status CHECK constraint.
**Acceptance / logic checks:**
- flyway migrate applies V2 cleanly on a fresh schema (no errors).
- \d transactions in psql shows all monetary columns as numeric(20,8).
- \d transactions shows idempotency_key with UNIQUE constraint.
- INSERT of a transaction with status='INVALID' raises a CHECK constraint violation.
- EXPLAIN SELECT * FROM transactions WHERE partner_id='x' AND status='COMMITTED' uses idx_transactions_partner_status.
**Depends on:** 14.4-T05

### 14.4-T07 — Write Flyway migration for prefunding_balances and prefunding_ledger tables  _(35 min)_
**Context:** OVERSEAS partners (e.g., SendMN, T-Bank) maintain a prepaid USD prefunding balance. Deductions must be atomic using SELECT FOR UPDATE (NFR-10 §5.2). Tables needed: prefunding_balances (partner_id UUID PK, balance DECIMAL(20,8) NOT NULL DEFAULT 0, alert_threshold DECIMAL(20,8) NOT NULL DEFAULT 10000, updated_at TIMESTAMPTZ); prefunding_ledger (id UUID PK, partner_id UUID NOT NULL REFERENCES prefunding_balances, txn_id UUID REFERENCES transactions, entry_type VARCHAR(20) CHECK IN ('TOPUP','DEDUCTION','REVERSAL'), amount_usd DECIMAL(20,8) NOT NULL, balance_after DECIMAL(20,8) NOT NULL, operator_id VARCHAR(100), created_at TIMESTAMPTZ NOT NULL DEFAULT now()). Negative balance must be rejected at DB level: CHECK (balance >= 0) on prefunding_balances.
**Steps:** Create db/migrations/V3__prefunding_tables.sql.; Define prefunding_balances with the columns in context plus CHECK (balance >= 0) and CHECK (alert_threshold > 0).; Define prefunding_ledger as an append-only audit table with the columns listed.; Add FK: prefunding_ledger.partner_id REFERENCES prefunding_balances(partner_id).; Add index: CREATE INDEX idx_prefunding_ledger_partner ON prefunding_ledger(partner_id, created_at DESC);; Verify flyway migrate applies V3 after V2 cleanly.
**Deliverable:** db/migrations/V3__prefunding_tables.sql with both tables, FK, CHECK constraints, and index.
**Acceptance / logic checks:**
- flyway migrate applies V1 through V3 cleanly on a fresh schema.
- UPDATE prefunding_balances SET balance = -1 WHERE partner_id='x' raises a CHECK constraint violation.
- INSERT into prefunding_ledger with entry_type='TRANSFER' raises a CHECK constraint violation.
- SELECT * FROM prefunding_ledger WHERE partner_id='x' ORDER BY created_at DESC uses idx_prefunding_ledger_partner.
- EXPLAIN (SELECT balance FROM prefunding_balances WHERE partner_id=? FOR UPDATE) shows a row-level lock on a single row.
**Depends on:** 14.4-T06

### 14.4-T08 — Write Flyway migration for batch_job_runs and config_audit tables  _(30 min)_
**Context:** OPS-13 §6.3 requires each batch job run to have a unique job_run_id (UUID) persisted to detect duplicates; idempotency is checked before re-running. OPS-13 §4.6 migration log. TICKET_BRIEF canonical facts: config changes log actor, timestamp, previous value. Tables: batch_job_runs (job_run_id UUID PK, job_id VARCHAR(30) NOT NULL, run_date DATE NOT NULL, status VARCHAR(20) CHECK IN ('RUNNING','COMPLETED','FAILED','SKIPPED'), started_at TIMESTAMPTZ, completed_at TIMESTAMPTZ, error_message TEXT, UNIQUE(job_id, run_date)); config_audit (id UUID PK, entity_type VARCHAR(50), entity_id UUID, field_name VARCHAR(100), old_value TEXT, new_value TEXT, actor VARCHAR(100) NOT NULL, applied_at TIMESTAMPTZ NOT NULL DEFAULT now()).
**Steps:** Create db/migrations/V4__batch_and_audit_tables.sql.; Define batch_job_runs with columns and the UNIQUE(job_id, run_date) constraint.; Define config_audit with all columns; add index on (entity_type, entity_id, applied_at DESC).; Add index on batch_job_runs(job_id, run_date) for fast idempotency lookup.; Run flyway migrate to confirm V4 applies after V1-V3.
**Deliverable:** db/migrations/V4__batch_and_audit_tables.sql with both tables, constraints, and indexes.
**Acceptance / logic checks:**
- flyway migrate applies V1 through V4 cleanly.
- INSERT a second batch_job_run with job_id='JOB-ZP-01' and run_date='2026-06-05' after the first raises UNIQUE constraint violation.
- status='UNKNOWN' in batch_job_runs raises a CHECK constraint violation.
- EXPLAIN SELECT * FROM batch_job_runs WHERE job_id='JOB-ZP-01' AND run_date='2026-06-05' uses the index on (job_id, run_date).
- config_audit table has no ON DELETE CASCADE (audit rows must be immutable).
**Depends on:** 14.4-T07

### 14.4-T09 — Provision Redis Cluster (3 primary + 3 replica nodes) via Terraform module  _(40 min)_
**Context:** NFR-10 §4.2 and SAD-02: Redis deployed in cluster mode, minimum 3 primary + 3 replica nodes in production; Phase 1 minimum 1 node (2 GB). Redis stores: rate quote cache (key rate_quote:{txn_ref}, TTL 60s aggregator-bound / 300s otherwise, configurable 60-1800s), idempotency key store (TTL 24h), config/registry hot cache. OPS-13 §9.1: Redis persistence is disabled in prod (cache is ephemeral and rebuilt on restart). Automatic failover via Redis Sentinel or managed cluster failover. Sentinel/cluster handles automatic primary re-election within seconds.
**Steps:** In infra/modules/data-zone/main.tf, add a Redis Cluster resource with num_node_groups=3, replicas_per_node_group=1 (6 nodes total); for dev/int environments use a single-node resource (conditional on var.env).; Set snapshot_retention_limit=0 (persistence disabled per OPS-13 §9.1).; Enable at-rest encryption and in-transit encryption (TLS).; Configure automatic_failover_enabled=true and multi_az_enabled=true.; Export redis_primary_endpoint output (cluster configuration endpoint).
**Deliverable:** Redis Cluster Terraform resource in infra/modules/data-zone/main.tf with 6-node prod config, single-node dev/int config, and redis_primary_endpoint output.
**Acceptance / logic checks:**
- terraform plan for prod workspace shows num_node_groups=3, replicas_per_node_group=1.
- terraform plan for dev workspace shows a single-node resource (count=1, not cluster).
- snapshot_retention_limit=0 in prod plan output.
- automatic_failover_enabled=true in prod plan.
- at_rest_encryption_enabled=true and transit_encryption_enabled=true in prod plan.
**Depends on:** 14.4-T01

### 14.4-T10 — Implement RedisClient wrapper with TTL helpers and connection pooling  _(50 min)_
**Context:** Redis is used for: (1) rate_quote:{txn_ref} cache TTL 60-1800s (TICKET_BRIEF: default 60s aggregator-bound / 300s otherwise), (2) idempotency key (partner_id:idempotency_key) TTL 86400s (24h, SEC-09), (3) nonce cache (X-Nonce UUID) TTL 600s (SEC-09 §3.3), (4) config hot-cache with no TTL (invalidated on config change). Redis Cluster mode requires a cluster-aware client (e.g., Jedis Cluster / Lettuce Cluster in Java). Connection pool per service: Hub Core API 10-20 connections (NFR-10 §3.3).
**Steps:** Create src/main/java/com/gmepay/infra/redis/RedisClient.java (or equivalent package).; Implement methods: setWithTTL(String key, String value, int ttlSeconds), get(String key), delete(String key), setIfAbsent(String key, String value, int ttlSeconds) (for nonce dedup using SETNX).; Configure connection pool using LettuceConnectionFactory or JedisPoolConfig with maxTotal=20, minIdle=2, testOnBorrow=true.; Read REDIS_URL, REDIS_PASSWORD from environment variables (never hardcoded); use TLS connection string.; Add unit tests in src/test/java/com/gmepay/infra/redis/RedisClientTest.java using an embedded Redis (e.g., embedded-redis or Testcontainers Redis).
**Deliverable:** RedisClient.java with 4 methods + connection pool config; RedisClientTest.java with at least 5 test cases.
**Acceptance / logic checks:**
- setWithTTL(key, value, 60) followed by Thread.sleep(61s) then get(key) returns null in the TTL expiry test.
- setIfAbsent on existing key returns false (nonce replay scenario returns false).
- get on missing key returns null (not exception).
- Connection pool maxTotal is 20 as configured; pool exhaustion throws a specific exception (not a hang).
- All 5+ unit tests pass with the embedded Redis.
**Depends on:** 14.4-T09

### 14.4-T11 — Implement RateQuoteCache service using RedisClient  _(40 min)_
**Context:** Rate quote flow: GET /v1/rates issues a quote stored in Redis with key rate_quote:{txn_ref}, TTL = partner.rate_quote_ttl_seconds (default 60s if aggregator-bound, 300s otherwise; range 60-1800s). On POST /v1/payments commit, all USD-pool values are rate-locked (collection_usd, payout_usd_cost, collection_margin_usd, payout_margin_usd, offer_rate_coll, cross_rate, send_amount, service_charge, collection_amount, cost_rate_coll, cost_rate_pay); after commit these values must never be updated even if Redis TTL has not expired. validUntil = quote_issued_at + ttl_seconds.
**Steps:** Create src/main/java/com/gmepay/payment/cache/RateQuoteCache.java.; Implement storeQuote(String txnRef, RateQuote quote, int ttlSeconds): serialise quote to JSON, call redisClient.setWithTTL(rate_quote:{txnRef}, json, ttlSeconds).; Implement fetchQuote(String txnRef): call redisClient.get(rate_quote:{txnRef}), deserialise; return Optional.empty() if missing or expired.; Implement evictQuote(String txnRef): call redisClient.delete(rate_quote:{txnRef}) (called after rate-lock commit to prevent stale quote re-use).; Add RateQuoteCacheTest.java with 4 test cases: store+fetch round-trip, TTL expiry returns empty, evict then fetch returns empty, fetch missing key returns empty.
**Deliverable:** RateQuoteCache.java with 3 methods and RateQuoteCacheTest.java with 4 passing tests.
**Acceptance / logic checks:**
- storeQuote followed by fetchQuote within TTL returns the original quote object with all rate fields intact.
- fetchQuote after TTL expiry (using embedded Redis TTL simulation) returns Optional.empty().
- fetchQuote after evictQuote returns Optional.empty() immediately.
- fetchQuote for unknown txnRef returns Optional.empty() (not exception).
- Stored key format in Redis is exactly rate_quote:{txnRef} (verified by redisClient.get(rate_quote:TEST-REF) in test).
**Depends on:** 14.4-T10

### 14.4-T12 — Implement IdempotencyKeyStore service using RedisClient  _(40 min)_
**Context:** SEC-09 §5.2 and API-05: all mutating POST calls require an Idempotency-Key (UUID v4) header. Server caches the response keyed by (partner_id, idempotency_key) for 24 hours (86400s TTL). Duplicate requests within the cache window return the original response without re-processing. Redis key pattern: idempotency:{partner_id}:{idempotency_key}. Nonce cache (X-Nonce UUID v4, TTL 600s) uses key pattern: nonce:{nonce_value} with SETNX semantics (if key exists, it is a replay).
**Steps:** Create src/main/java/com/gmepay/infra/idempotency/IdempotencyKeyStore.java.; Implement storeResponse(String partnerId, String idempotencyKey, String responseJson): redisClient.setWithTTL(idempotency:{partnerId}:{idempotencyKey}, responseJson, 86400).; Implement fetchResponse(String partnerId, String idempotencyKey): redisClient.get(idempotency:{partnerId}:{idempotencyKey}); return Optional.; Implement checkAndStoreNonce(String nonce): redisClient.setIfAbsent(nonce:{nonce}, 1, 600); returns true if stored (first use), false if already exists (replay).; Add IdempotencyKeyStoreTest.java: test storeResponse+fetchResponse round-trip; duplicate request returns cached response; nonce first use returns true; nonce replay returns false; expired nonce (TTL 600s) returns true again.
**Deliverable:** IdempotencyKeyStore.java with 4 methods and IdempotencyKeyStoreTest.java with 5 passing tests.
**Acceptance / logic checks:**
- storeResponse then fetchResponse with same (partner_id, idempotency_key) returns the original JSON string.
- fetchResponse with different idempotency_key returns Optional.empty().
- checkAndStoreNonce('abc') called twice in rapid succession returns true then false (replay detected).
- Redis key pattern for idempotency entry is exactly idempotency:{partnerId}:{idempotencyKey} (verified by direct redisClient.get in test).
- All 5 test cases pass with embedded Redis.
**Depends on:** 14.4-T10

### 14.4-T13 — Implement ConfigRegistryCache service for hot-caching Scheme/Partner/Rule config  _(40 min)_
**Context:** SAD-02 §Config/Registry: config changes are propagated to runtime Redis hot cache on any config change. The cache stores serialised records for schemes, partners, and rules. On cache miss, the service reads from the DB and repopulates. On any Admin System config change (scheme, partner, rule update), the cache entry is invalidated (delete then repopulate). TTL: no expiry (cache invalidated explicitly only). Config cache key patterns: config:scheme:{scheme_id}, config:partner:{partner_id}, config:rule:{rule_id}.
**Steps:** Create src/main/java/com/gmepay/config/cache/ConfigRegistryCache.java.; Implement generic get(String key, Class<T> type, Supplier<T> dbLoader): fetch from Redis; on miss, call dbLoader, store result with setWithTTL(key, json, Integer.MAX_VALUE or a long TTL like 86400*7).; Implement invalidate(String key): redisClient.delete(key).; Implement invalidateAndReload(String key, Object value): delete then store.; Add ConfigRegistryCacheTest.java: cache miss triggers dbLoader; second call returns cached value (dbLoader not called again); invalidate then get triggers dbLoader again.
**Deliverable:** ConfigRegistryCache.java with 3 methods and ConfigRegistryCacheTest.java with 3 passing tests.
**Acceptance / logic checks:**
- On first get for config:partner:P1, dbLoader lambda is invoked exactly once.
- On second get for config:partner:P1 within TTL, dbLoader lambda is NOT invoked (cache hit confirmed by mock spy).
- After invalidate(config:partner:P1), next get invokes dbLoader again.
- Key pattern follows config:{entity_type}:{entity_id} format (verified in test by direct Redis key check).
- No exception is thrown when the underlying Redis cluster is unavailable and fallback to dbLoader occurs (graceful degradation).
**Depends on:** 14.4-T10

### 14.4-T14 — Provision message queue (RabbitMQ or Kafka) via Terraform module  _(40 min)_
**Context:** SAD-02 and OPS-13 §3.2: message queue in Data Zone handles webhook dispatch and async batch job triggers. NFR-10 §4.2: message broker deployed in clustered mode with replication factor >= 2. OPS-13 §A-OPS-04: managed service (SQS, Pub/Sub, RabbitMQ). OPS-13 §10.3: webhook queue depth alert P2 at >500, P1 at >2000 messages. Queues needed: webhook-dispatch (for partner webhook callbacks), batch-job-trigger (for async batch job scheduling). Recommended engine: RabbitMQ (simpler if event volume is moderate per SAD-02 §Message Broker note).
**Steps:** In infra/modules/data-zone/main.tf, add a RabbitMQ (or cloud-native MQ) resource with engine_version, multi_az=true, replication_factor=2.; Declare two virtual host queues: webhook-dispatch (durable=true, x-message-ttl=86400000 ms) and batch-job-trigger (durable=true).; Enable TLS in-transit encryption.; Export mq_broker_endpoint output (AMQP/TLS endpoint).; For dev/int environments, use a single-node deployment (conditional on var.env).
**Deliverable:** Message queue Terraform resource in infra/modules/data-zone/main.tf with 2 queues declared, multi-AZ prod config, and mq_broker_endpoint output.
**Acceptance / logic checks:**
- terraform plan for prod workspace shows multi_az=true and replication_factor=2 (or engine equivalent).
- terraform plan for dev workspace shows single-node resource.
- webhook-dispatch queue has durable=true and x-message-ttl=86400000 in the plan.
- batch-job-trigger queue has durable=true in the plan.
- mq_broker_endpoint output is populated in the plan output.
**Depends on:** 14.4-T01

### 14.4-T15 — Implement MessageQueueClient wrapper with publish and subscribe methods  _(50 min)_
**Context:** The message queue is used for: (1) webhook-dispatch queue: Hub Core publishes a WebhookEvent after transaction_committed (step 7 of 8-step trail); Webhook Dispatcher consumes and delivers to partner. (2) batch-job-trigger queue: Batch Worker publishes job triggers on cron schedule. OPS-13 §10.3: webhook queue depth > 500 = P2 alert, > 2000 = P1. Durable queues (survive broker restart). Messages are JSON. Use AMQP client (e.g., Spring AMQP / RabbitTemplate in Java).
**Steps:** Create src/main/java/com/gmepay/infra/mq/MessageQueueClient.java.; Implement publish(String queueName, String messageJson): send durable message (delivery_mode=2) to named queue; throw MQPublishException on failure.; Implement subscribe(String queueName, MessageHandler handler): register a consumer callback with manual acknowledgement (ack on successful processing, nack with requeue=false on permanent failure).; Read AMQP_URL from environment variable; configure connection factory with heartbeat=60s, connection timeout=5s.; Add MessageQueueClientTest.java using Testcontainers RabbitMQ: publish then subscribe round-trip; subscriber receives message within 2 seconds; nack on handler exception moves message to DLQ.
**Deliverable:** MessageQueueClient.java with publish/subscribe and MessageQueueClientTest.java with 3 integration tests.
**Acceptance / logic checks:**
- publish to webhook-dispatch then subscribe receives the JSON message with all original fields intact.
- Message delivery_mode=2 (durable) confirmed via RabbitMQ management API or test assertion.
- When MessageHandler throws an exception, the message is nack'd and moves to the dead-letter queue (not requeued indefinitely).
- Connection factory heartbeat = 60s in the configuration.
- All 3 integration tests pass with Testcontainers RabbitMQ.
**Depends on:** 14.4-T14

### 14.4-T16 — Implement distributed lock service using Redis SETNX for batch job deduplication  _(45 min)_
**Context:** NFR-10 §4.2: Settlement Engine batch jobs use distributed locking (Redis SETNX or Kubernetes leader election) to prevent duplicate execution. OPS-13 §6.3: each job run has a unique job_run_id (UUID) persisted; duplicate runs for same logical date are no-op'd. Lock key pattern: batch_lock:{job_id}:{run_date} (e.g., batch_lock:JOB-ZP-01:2026-06-05). Lock TTL: 3600s (1 hour; longer than any expected job duration). Lock must be released on job completion or failure.
**Steps:** Create src/main/java/com/gmepay/batch/lock/BatchDistributedLock.java.; Implement tryAcquire(String jobId, LocalDate runDate, String jobRunId): call redisClient.setIfAbsent(batch_lock:{jobId}:{runDate}, jobRunId, 3600); return true if acquired.; Implement release(String jobId, LocalDate runDate, String jobRunId): fetch current lock value; if it matches jobRunId, delete the key (prevents releasing another job's lock).; Implement isLocked(String jobId, LocalDate runDate): return redisClient.get(batch_lock:{jobId}:{runDate}) != null.; Add BatchDistributedLockTest.java: two concurrent threads call tryAcquire for same job+date; only one succeeds; release by correct owner succeeds; release by wrong owner (different jobRunId) does not release the lock.
**Deliverable:** BatchDistributedLock.java with 3 methods and BatchDistributedLockTest.java with 4 tests.
**Acceptance / logic checks:**
- Two concurrent tryAcquire calls for JOB-ZP-01 on 2026-06-05: exactly one returns true, one returns false.
- release with the correct jobRunId removes the lock (subsequent isLocked returns false).
- release with wrong jobRunId (different UUID) does NOT remove the lock (isLocked still true).
- Lock TTL is 3600s (verified by Redis TTL command in test).
- All 4 tests pass with embedded Redis.
**Depends on:** 14.4-T10

### 14.4-T17 — Write Flyway migration for transaction_events (8-step trail) table  _(30 min)_
**Context:** OPS-13 §7.3 and SEC-09 §6.1: every transaction has an 8-step event trail: rate_quote_issued, payment_initiated, prefund_deducted, scheme_request_sent, scheme_response_received, transaction_committed, webhook_dispatched, webhook_delivered. Each event is immutable (append-only). Table: transaction_events (id UUID PK, transaction_id UUID NOT NULL REFERENCES transactions(id), event_type VARCHAR(50) NOT NULL CHECK IN ('rate_quote_issued','payment_initiated','prefund_deducted','scheme_request_sent','scheme_response_received','transaction_committed','webhook_dispatched','webhook_delivered','webhook_failed'), trace_id UUID, span_id UUID, event_at TIMESTAMPTZ NOT NULL DEFAULT now(), metadata JSONB). No UPDATE or DELETE on this table (enforced by row-level security or application convention).
**Steps:** Create db/migrations/V5__transaction_events.sql.; Define transaction_events table with all columns and the CHECK constraint on event_type (9 values including webhook_failed).; Add FK transaction_id REFERENCES transactions(id) ON DELETE RESTRICT (preserve audit trail even if transaction is soft-deleted).; Add index: CREATE INDEX idx_txn_events_txn_id ON transaction_events(transaction_id, event_at ASC); for the 8-step trail query.; Run flyway migrate V5 and verify with \d transaction_events.
**Deliverable:** db/migrations/V5__transaction_events.sql with table, FK, CHECK constraint, and index.
**Acceptance / logic checks:**
- flyway migrate applies V1 through V5 cleanly.
- INSERT of event_type='invalid_event' raises a CHECK constraint violation.
- SELECT from transaction_events WHERE transaction_id=? ORDER BY event_at ASC uses idx_txn_events_txn_id (EXPLAIN confirms index scan).
- FK ON DELETE RESTRICT: attempt to DELETE a transactions row that has events raises a foreign key constraint violation.
- JSONB metadata column accepts arbitrary JSON without error.
**Depends on:** 14.4-T06

### 14.4-T18 — Implement TransactionEventRepository for appending and querying the 8-step trail  _(40 min)_
**Context:** transaction_events is append-only (V5 migration). The 8-step trail is linked to trace_id for end-to-end correlation (OPS-13 §7.3). Querying the trail is required by the Admin System (step 9 of OPS-13 §8.9 runbook). The repository must use the read-replica (db_replica_endpoint via pgbouncer-read-svc) for SELECT queries and the primary (pgbouncer-write-svc) for INSERTs. Structured log format for each event insert: {timestamp, level:INFO, service, trace_id, span_id, partner_id, transaction_id, event} (OPS-13 §7.2).
**Steps:** Create src/main/java/com/gmepay/payment/repository/TransactionEventRepository.java.; Implement appendEvent(TransactionEvent event): INSERT INTO transaction_events; emit a structured INFO log with all mandatory fields.; Implement getTrail(UUID transactionId): SELECT * FROM transaction_events WHERE transaction_id=? ORDER BY event_at ASC; returns List<TransactionEvent>.; Inject write DataSource (pgbouncer-write-svc) for appendEvent and read DataSource (pgbouncer-read-svc) for getTrail.; Add TransactionEventRepositoryTest.java: append 3 events; getTrail returns them in ascending order; appending duplicate event_type for same transaction succeeds (no uniqueness constraint on event_type per transaction).
**Deliverable:** TransactionEventRepository.java with 2 methods and TransactionEventRepositoryTest.java with 3 passing tests.
**Acceptance / logic checks:**
- appendEvent inserts a row with correct transaction_id, event_type, trace_id, and event_at set to now().
- getTrail for a transaction with 3 events returns them sorted ascending by event_at.
- appendEvent uses the write DataSource (primary); getTrail uses the read DataSource (replica) — verified by mock DataSource injection in test.
- The structured log emitted by appendEvent contains fields: trace_id, transaction_id, event_type (inspected via log capture in test).
- Two appendEvent calls with the same event_type for the same transaction_id both succeed (no UNIQUE constraint on event_type).
**Depends on:** 14.4-T17, 14.4-T04

### 14.4-T19 — Implement PrefundingRepository with atomic SELECT FOR UPDATE deduction  _(55 min)_
**Context:** NFR-10 §5.2: prefunding deduction must be atomic using SELECT FOR UPDATE row-level lock. Pattern (from spec): BEGIN; SELECT balance FROM prefunding_balances WHERE partner_id=? FOR UPDATE; check balance >= deduction_amount; UPDATE prefunding_balances SET balance = balance - deduction_amount WHERE partner_id=?; INSERT INTO prefunding_ledger ...; COMMIT. Insufficient balance must reject BEFORE any scheme call. Uses write DataSource (primary via pgbouncer-write-svc). OVERSEAS partners only (partner.type = OVERSEAS). Reversal: entry_type = REVERSAL, amount_usd = original deduction amount (positive), balance_after = new balance.
**Steps:** Create src/main/java/com/gmepay/payment/repository/PrefundingRepository.java.; Implement deduct(UUID partnerId, BigDecimal amountUsd, UUID txnId): run in @Transactional; SELECT FOR UPDATE; if balance < amountUsd throw InsufficientPrefundingException; UPDATE balance; INSERT ledger row (DEDUCTION); return updated balance.; Implement reverse(UUID partnerId, BigDecimal amountUsd, UUID txnId): run in @Transactional; SELECT FOR UPDATE; UPDATE balance + amountUsd; INSERT ledger row (REVERSAL).; Implement getBalance(UUID partnerId): SELECT balance FROM prefunding_balances WHERE partner_id=? (uses read DataSource).; Implement recordTopUp(UUID partnerId, BigDecimal amountUsd, String operatorId): @Transactional; SELECT FOR UPDATE; UPDATE balance; INSERT ledger row (TOPUP, operator_id set).
**Deliverable:** PrefundingRepository.java with 4 methods using correct DataSource routing and transaction semantics.
**Acceptance / logic checks:**
- deduct with balance=100 and amountUsd=150 throws InsufficientPrefundingException and does NOT insert a ledger row.
- deduct with balance=100 and amountUsd=50 succeeds; getBalance returns 50; ledger has one DEDUCTION entry of 50.
- reverse with amountUsd=50 after deduct returns balance to 100; ledger has REVERSAL entry.
- Two concurrent deduct calls for same partner with balance=100 and amountUsd=80 each: exactly one succeeds and one throws InsufficientPrefundingException (atomicity test using two threads).
- balance CHECK constraint (>= 0) in DB is never violated by any repository method.
**Depends on:** 14.4-T07, 14.4-T04

### 14.4-T20 — Implement BatchJobRunRepository for idempotency and status tracking  _(40 min)_
**Context:** OPS-13 §6.3: each batch job run has a unique job_run_id (UUID) persisted; duplicate runs for same logical (job_id, run_date) are no-op'd via the UNIQUE(job_id, run_date) DB constraint. Job status transitions: RUNNING -> COMPLETED or RUNNING -> FAILED. The batch_job_runs table (V4 migration) stores all runs. The distributed lock (14.4-T16) prevents concurrent execution; the DB constraint prevents duplicate completions. Admin System batch rerun UI reads from this table.
**Steps:** Create src/main/java/com/gmepay/batch/repository/BatchJobRunRepository.java.; Implement startRun(String jobId, LocalDate runDate): generate UUID jobRunId; INSERT INTO batch_job_runs (job_run_id, job_id, run_date, status='RUNNING', started_at=now()); handle UniqueConstraintException by returning Optional.empty() (already running/completed).; Implement completeRun(UUID jobRunId): UPDATE batch_job_runs SET status='COMPLETED', completed_at=now() WHERE job_run_id=?.; Implement failRun(UUID jobRunId, String errorMessage): UPDATE batch_job_runs SET status='FAILED', completed_at=now(), error_message=? WHERE job_run_id=?.; Implement getLastRun(String jobId, LocalDate runDate): SELECT * FROM batch_job_runs WHERE job_id=? AND run_date=? ORDER BY started_at DESC LIMIT 1.
**Deliverable:** BatchJobRunRepository.java with 4 methods and BatchJobRunRepositoryTest.java with 4 tests.
**Acceptance / logic checks:**
- startRun for JOB-ZP-01 on 2026-06-05 twice: second call returns Optional.empty() (UNIQUE constraint detected).
- After startRun + completeRun: getLastRun returns status=COMPLETED.
- After startRun + failRun('SFTP error'): getLastRun returns status=FAILED and error_message='SFTP error'.
- completeRun for non-existent jobRunId updates 0 rows (no exception thrown).
- All 4 tests pass using an in-memory PostgreSQL (Testcontainers or H2 with PostgreSQL dialect).
**Depends on:** 14.4-T08, 14.4-T04

### 14.4-T21 — Configure Prometheus metrics exporter for DB connection pool utilisation  _(45 min)_
**Context:** NFR-10 §7.1 and OPS-13 §7.1: required metric db_connection_pool_utilisation (Gauge, label: service) to be scraped by Prometheus. Alert P2: pool utilisation > 90% for 2 minutes (NFR-10 §7.5). PgBouncer exposes SHOW POOLS and SHOW STATS via its admin console. Use the prometheus-pgbouncer-exporter sidecar or the pgbouncer_exporter Docker image. Redis metrics: redis_connected_clients, redis_used_memory_bytes, redis_keyspace_hits, redis_keyspace_misses (for cache hit rate dashboard). RabbitMQ: rabbitmq_queue_messages (for queue depth alert at 500/2000).
**Steps:** Add infra/k8s/monitoring/pgbouncer-exporter.yaml: a sidecar or separate Deployment using pgbouncer/pgbouncer-exporter image, scraping pgbouncer admin console; annotate with prometheus.io/scrape: 'true' and prometheus.io/port.; Add infra/k8s/monitoring/redis-exporter.yaml: Deployment using oliver006/redis_exporter image; configure REDIS_ADDR from redis_primary_endpoint env var.; Add infra/k8s/monitoring/rabbitmq-servicemonitor.yaml: ServiceMonitor pointing to the RabbitMQ management plugin metrics endpoint.; In infra/k8s/monitoring/alerts.yaml, add PrometheusRule for: db_connection_pool_utilisation > 0.9 for 2m (severity=P2), rabbitmq_queue_messages{queue='webhook-dispatch'} > 500 for 5m (severity=P2), rabbitmq_queue_messages{queue='webhook-dispatch'} > 2000 for 2m (severity=P1).; Verify all manifests pass kubectl apply --dry-run=client.
**Deliverable:** infra/k8s/monitoring/ with pgbouncer-exporter.yaml, redis-exporter.yaml, rabbitmq-servicemonitor.yaml, and alerts.yaml PrometheusRules.
**Acceptance / logic checks:**
- kubectl apply --dry-run=client on all 4 manifests returns no errors.
- alerts.yaml contains a PrometheusRule with expr matching db_connection_pool_utilisation > 0.9 and for: 2m.
- alerts.yaml contains a P1 rule with rabbitmq_queue_messages threshold 2000.
- alerts.yaml contains a P2 rule with rabbitmq_queue_messages threshold 500.
- pgbouncer-exporter.yaml contains prometheus.io/scrape: 'true' annotation.
**Depends on:** 14.4-T04, 14.4-T09, 14.4-T14

### 14.4-T22 — Configure Prometheus alert for pool_identity_breaches_total  _(50 min)_
**Context:** NFR-10 §5.5 and OPS-13 §7.5.5: pool identity assertion checks collection_usd - collection_margin_usd - payout_margin_usd == payout_usd_cost within tolerance 0.01 USD for every cross-border transaction at commit time. The metric pool_identity_breaches_total (Counter, no labels) must ALWAYS be 0 in production. Alert P1: pool_identity_breaches_total > 0, action: block transaction commit + engineering alert (OPS-13 §7.5.5). Also add a daily reconciliation job that checks all committed cross-border transactions from the previous 24 hours.
**Steps:** In infra/k8s/monitoring/alerts.yaml, add PrometheusRule: alert name PoolIdentityBreach, expr increase(pool_identity_breaches_total[5m]) > 0, severity=P1, annotations including a summary and the runbook URL pointing to OPS-13 §8.10.; Add a @Scheduled daily job class in src/main/java/com/gmepay/payment/reconcile/PoolIdentityReconcileJob.java that queries: SELECT id, collection_usd, collection_margin_usd, payout_margin_usd, payout_usd_cost FROM transactions WHERE status='COMMITTED' AND direction != 'Domestic' AND committed_at >= now() - interval '24 hours'.; For each row, assert ABS((collection_usd - collection_margin_usd - payout_margin_usd) - payout_usd_cost) <= 0.01; on breach: increment the metric counter and log a P1-level structured alert with transaction_id.; Add PoolIdentityReconcileJobTest.java with a test vector: collection_usd=1000.00, collection_margin_usd=10.00, payout_margin_usd=10.00, payout_usd_cost=980.00: assert passes (0.00 delta). Test vector 2: payout_usd_cost=979.98 vs computed 980.00: delta=0.02 > 0.01, assert triggers breach counter increment.
**Deliverable:** PrometheusRule in alerts.yaml for pool_identity_breaches_total and PoolIdentityReconcileJob.java with PoolIdentityReconcileJobTest.java.
**Acceptance / logic checks:**
- Test vector 1 (delta=0.00): reconcile job processes the row without incrementing the breach counter.
- Test vector 2 (delta=0.02): reconcile job increments pool_identity_breaches_total by 1 and logs at ERROR level.
- The PrometheusRule expr uses increase(pool_identity_breaches_total[5m]) > 0 (not just > 0 on a gauge).
- The PrometheusRule severity label is P1.
- PoolIdentityReconcileJob queries only transactions where direction != 'Domestic' (same-currency transactions are exempt from the USD pool identity check per TICKET_BRIEF same-currency short-circuit rule).
**Depends on:** 14.4-T21, 14.4-T06

### 14.4-T23 — Implement PostgreSQL failover smoke test and runbook documentation  _(45 min)_
**Context:** NFR-10 §8.4: DB primary failover must complete within 5 minutes. OPS-13 §9.4.1: promote replica via pg_ctl promote or cloud-equivalent; update connection secret; rolling restart of API services. OPS-13 §9.4.1 step-by-step procedure. The application must reconnect automatically after failover via the connection string resolving to new primary (PgBouncer needs to be updated to point to the promoted replica endpoint). This ticket creates a testable failover smoke test script and the operational runbook entry.
**Steps:** Create scripts/failover-test.sh: a shell script that (1) identifies current primary endpoint, (2) simulates primary failure by stopping the primary container/instance, (3) waits up to 5 minutes polling the pgbouncer-write-svc endpoint for a successful SELECT 1, (4) records elapsed time, (5) verifies the replica endpoint is now accepting writes.; Add the failover procedure as a Markdown section in docs/runbooks/db-failover.md covering: detect, promote, update secret (gmepay/prod/hub-api/db-primary-host), rolling restart (kubectl rollout restart deployment/hub-api), verify.; Add a check: after failover, INSERT INTO transactions... must succeed (write path alive).; Add a check: prefunding SELECT FOR UPDATE deduction must succeed post-failover (verifying transactional integrity).; Document RTO target: < 5 minutes from primary failure to first successful write.
**Deliverable:** scripts/failover-test.sh and docs/runbooks/db-failover.md.
**Acceptance / logic checks:**
- failover-test.sh is executable (chmod +x) and contains all 5 steps described.
- db-failover.md contains the secret update step referencing path gmepay/prod/hub-api/db-primary-host.
- db-failover.md references kubectl rollout restart deployment/hub-api command.
- db-failover.md states RTO target < 5 minutes.
- scripts/failover-test.sh script exits 0 when elapsed time < 300 seconds and exits 1 if > 300 seconds (automated RTO check).
**Depends on:** 14.4-T04, 14.4-T19

### 14.4-T24 — Implement WAL archiving verification and monthly restore-test script  _(45 min)_
**Context:** NFR-10 §6.2: primary DB full backup daily at 02:00 KST, WAL incremental continuous streaming, retention 90 days full / 7 days WAL. OPS-13 §9.2: monthly restore test — restore most recent snapshot to a restore-test environment, apply WAL archives to latest consistent point, run integrity tests (transaction counts, prefunding totals, settlement figures), document result in DR log. OPS-13 §9.1: WAL archives to object storage in separate AZ, path /gmepay/<env>/wal/.
**Steps:** Create scripts/restore-test.sh: (1) fetch latest full snapshot ARN/name from object storage path /gmepay/prod/backups/; (2) spin up a temporary restore-test DB instance; (3) restore snapshot; (4) apply WAL archives from /gmepay/prod/wal/ to bring to latest consistent point; (5) run integrity SQL checks: SELECT COUNT(*) FROM transactions WHERE status='COMMITTED'; SELECT SUM(balance) FROM prefunding_balances; compare counts against a stored checkpoint file.; Create scripts/checkpoint-integrity.sql with the 3 integrity queries.; Add a DR drill log template at docs/dr-drill-log-template.md with fields: date, restore_duration_minutes, transaction_count_match (bool), prefunding_total_match (bool), outcome, operator.; Verify scripts/restore-test.sh is executable and contains all 5 steps.; Document in docs/runbooks/backup-restore.md the WAL archive path, retention policy, and monthly drill schedule.
**Deliverable:** scripts/restore-test.sh, scripts/checkpoint-integrity.sql, docs/dr-drill-log-template.md, docs/runbooks/backup-restore.md.
**Acceptance / logic checks:**
- restore-test.sh contains a step referencing WAL archive path /gmepay/prod/wal/.
- checkpoint-integrity.sql contains SELECT COUNT(*) FROM transactions WHERE status='COMMITTED' and SELECT SUM(balance) FROM prefunding_balances.
- dr-drill-log-template.md includes all 5 listed fields.
- backup-restore.md states 90-day full backup retention and 7-day WAL retention.
- restore-test.sh exits 1 if transaction count from restored DB does not match the checkpoint value.
**Depends on:** 14.4-T03, 14.4-T19

### 14.4-T25 — Write Flyway migration for read-replica routing view and DB indexes for query-heavy paths  _(30 min)_
**Context:** NFR-10 §3.2: database scaling uses read replicas for query-heavy paths (Partner Portal transaction history). OPS-13 §10.2: read replica endpoint is used for SELECT-only workloads. Additional indexes needed for transaction history queries: (partner_id, created_at DESC) for Partner Portal pagination; (status, created_at) for batch job settlement query; (settlement_batch_id) already added in V2. Also need a composite index for UNCERTAIN resolution query used by Settlement Engine.
**Steps:** Create db/migrations/V6__query_indexes.sql.; Add: CREATE INDEX CONCURRENTLY idx_transactions_partner_created ON transactions(partner_id, created_at DESC);; Add: CREATE INDEX CONCURRENTLY idx_transactions_status_created ON transactions(status, created_at DESC);; Add: CREATE INDEX CONCURRENTLY idx_transactions_uncertain ON transactions(status, created_at) WHERE status = 'UNCERTAIN';; Add a comment at top of V6 stating these indexes support read-replica query paths and must be applied to both primary (replicated automatically) and read replica.; Run flyway migrate and verify all 3 indexes appear in \d+ transactions.
**Deliverable:** db/migrations/V6__query_indexes.sql with 3 CONCURRENTLY-built partial/composite indexes.
**Acceptance / logic checks:**
- flyway migrate applies V6 cleanly; all 3 indexes appear in \d+ transactions.
- EXPLAIN SELECT * FROM transactions WHERE partner_id='P1' ORDER BY created_at DESC LIMIT 100 uses idx_transactions_partner_created.
- EXPLAIN SELECT * FROM transactions WHERE status='UNCERTAIN' AND created_at < now()-interval '24h' uses idx_transactions_uncertain (partial index scan).
- CONCURRENTLY keyword is present in all 3 CREATE INDEX statements (non-blocking build).
- V6 contains no table-level locks (no ALTER TABLE statements).
**Depends on:** 14.4-T06

### 14.4-T26 — Unit test: PrefundingRepository concurrent deduction atomicity  _(50 min)_
**Context:** NFR-10 §5.2 and TICKET_BRIEF: deduction is ATOMIC (SELECT FOR UPDATE). Two concurrent deductions must not both succeed if combined they exceed the balance. Test vector: partner P1 balance = USD 100.00; concurrent deductions of USD 80.00 each; expected: exactly one succeeds (balance = 20.00) and one throws InsufficientPrefundingException; final balance must be exactly 20.00 (not negative). This is a dedicated test ticket for the concurrency invariant — a developer must be able to run it standalone.
**Steps:** Create test class PrefundingConcurrencyTest.java in src/test/java/com/gmepay/payment/repository/.; Set up a Testcontainers PostgreSQL with the V1-V3 Flyway migrations applied; insert partner P1 with balance=100.00.; Launch 2 threads simultaneously calling prefundingRepository.deduct(P1, 80.00, txnId1) and deduct(P1, 80.00, txnId2).; Use CountDownLatch to synchronise thread start; collect results (success or InsufficientPrefundingException).; Assert: exactly 1 success + 1 exception; final SELECT balance FROM prefunding_balances WHERE partner_id=P1 = 20.00; prefunding_ledger has exactly 1 DEDUCTION row of 80.00.
**Deliverable:** PrefundingConcurrencyTest.java with 1 test case covering the concurrent deduction invariant.
**Acceptance / logic checks:**
- Test passes deterministically (run 5 times in CI; all pass).
- Exactly 1 of the 2 threads receives InsufficientPrefundingException.
- Final balance in DB is exactly 20.00 after the test.
- prefunding_ledger has exactly 1 DEDUCTION row (the successful one only).
- The test uses a real PostgreSQL instance (Testcontainers), not an in-memory mock, to exercise the actual SELECT FOR UPDATE.
**Depends on:** 14.4-T19

### 14.4-T27 — Unit test: RateQuoteCache TTL boundary and eviction  _(40 min)_
**Context:** TICKET_BRIEF: rate quote TTL default 60s aggregator-bound / 300s otherwise, configurable 60-1800s. validUntil = quote_issued_at + ttl_seconds. On commit, evictQuote must be called. This test ticket verifies: (1) quote expires after TTL, (2) quote is evictable before TTL, (3) TTL boundaries (exactly at TTL second returns empty), (4) minimum TTL enforcement (TTL < 60 should be rejected or clamped to 60). Test with embedded Redis controlling clock via key TTL manipulation.
**Steps:** Create RateQuoteCacheBoundaryTest.java in src/test/java/com/gmepay/payment/cache/.; Test 1: storeQuote(ttl=1), sleep 2s, fetchQuote returns Optional.empty().; Test 2: storeQuote(ttl=300), fetchQuote immediately returns present; evictQuote; fetchQuote returns Optional.empty().; Test 3: storeQuote(ttl=1800) succeeds (max boundary). Attempt storeQuote(ttl=1801) should either clamp to 1800 or throw IllegalArgumentException.; Test 4: storeQuote(ttl=59) should either clamp to 60 or throw IllegalArgumentException (min boundary). Verify the stored TTL in Redis using redisClient.get TTL command is >= 60.; Assert validUntil field in the stored quote = quote_issued_at + ttl_seconds.
**Deliverable:** RateQuoteCacheBoundaryTest.java with 4 test cases.
**Acceptance / logic checks:**
- Test 1 passes: fetch after TTL expiry returns empty.
- Test 2 passes: evict before TTL expiry then fetch returns empty.
- Test 3: ttl=1801 is rejected or clamped; stored TTL <= 1800.
- Test 4: ttl=59 is rejected or clamped; stored TTL >= 60.
- validUntil field in the round-tripped quote object equals quote_issued_at plus the effective TTL seconds.
**Depends on:** 14.4-T11

### 14.4-T28 — Unit test: BatchDistributedLock race-condition and stale-lock expiry  _(40 min)_
**Context:** 14.4-T16 implements BatchDistributedLock with tryAcquire (SETNX), release (conditional delete), and isLocked. Edge cases to test: (1) two concurrent acquires for same job+date — only one wins; (2) lock release by wrong owner fails; (3) stale lock expiry: if a job dies without releasing, TTL=3600s ensures the lock auto-expires; (4) acquire after TTL expiry succeeds. These edge cases are critical for preventing duplicate ZeroPay batch file submissions (OPS-13 §6.3).
**Steps:** Create BatchDistributedLockEdgeCaseTest.java in src/test/java/com/gmepay/batch/lock/.; Test 1: two simultaneous threads call tryAcquire(JOB-ZP-01, 2026-06-05, UUID1) and tryAcquire(JOB-ZP-01, 2026-06-05, UUID2); assert exactly one returns true.; Test 2: acquire with UUID1; attempt release with UUID2; isLocked still returns true; release with UUID1; isLocked returns false.; Test 3: store lock key directly in Redis with TTL=1s; sleep 2s; tryAcquire returns true (stale lock has expired).; Test 4: verify that the lock key stored by tryAcquire has a TTL of 3600s (within +/- 5s margin using Redis TTL command).
**Deliverable:** BatchDistributedLockEdgeCaseTest.java with 4 test cases.
**Acceptance / logic checks:**
- Test 1: exactly 1 of 2 concurrent threads returns true from tryAcquire (run 10 times to confirm determinism).
- Test 2: release by wrong UUID does not remove lock (isLocked = true after wrong-owner release attempt).
- Test 3: lock key with 1s TTL expires; tryAcquire succeeds after 2s sleep.
- Test 4: Redis TTL on the lock key is between 3595 and 3600 seconds immediately after acquisition.
- All 4 tests pass with embedded Redis.
**Depends on:** 14.4-T16

### 14.4-T29 — Unit test: ConfigRegistryCache invalidation and graceful degradation on Redis unavailability  _(45 min)_
**Context:** ConfigRegistryCache (14.4-T13) must invalidate and reload on config change, and fall back gracefully to the DB loader if Redis is unavailable (OPS-13: Redis is ephemeral; cache is rebuilt on restart). Edge cases: (1) invalidate during in-flight request returns stale value for that request but fresh value for the next; (2) Redis down returns DB-loaded value without crashing; (3) two concurrent get calls on a miss both trigger dbLoader (acceptable: the second may overwrite the first, which is idempotent for config reads).
**Steps:** Create ConfigRegistryCacheResilienceTest.java in src/test/java/com/gmepay/config/cache/.; Test 1: mock Redis to throw RedisException on get; verify get(key, type, dbLoader) calls dbLoader and returns value without exception.; Test 2: configure a real embedded Redis; store config:partner:P1; verify cache hit (dbLoader not called); stop embedded Redis; verify next get calls dbLoader (graceful fallback).; Test 3: invalidate(config:partner:P1) followed immediately by get calls dbLoader; second get (after reload) does NOT call dbLoader.; Implement graceful-degradation: wrap all Redis calls in try-catch; on exception, log WARN and fall through to dbLoader.
**Deliverable:** ConfigRegistryCacheResilienceTest.java with 3 test cases; graceful-degradation try-catch added to ConfigRegistryCache.java.
**Acceptance / logic checks:**
- Test 1: RedisException in get causes dbLoader to be called; no exception propagated to caller.
- Test 2: after Redis is stopped, get returns DB-loaded value (not null, not exception).
- Test 3: after invalidate, dbLoader is called on next get; cached thereafter (second get does not call dbLoader).
- ConfigRegistryCache.java contains a try-catch around redisClient.get with WARN log on exception.
- All 3 tests pass.
**Depends on:** 14.4-T13

### 14.4-T30 — Write integration test: end-to-end data zone connectivity (DB + Redis + MQ)  _(55 min)_
**Context:** This integration test verifies that all three Data Zone components are reachable from an application service using their configured connection strings. It uses Testcontainers to spin up PostgreSQL, Redis, and RabbitMQ. It validates the full write+read cycle for each component. This test should run in the CI integration test stage (OPS-13 §4.1 stage 4). Verifies: DB write via pgbouncer-equivalent, Redis TTL store/fetch, and MQ publish/subscribe round-trip.
**Steps:** Create DataZoneIntegrationTest.java in src/test/java/com/gmepay/infra/integration/.; Use Testcontainers: PostgreSQL (latest), Redis (7.x), RabbitMQ (3.x) in a shared network.; Apply Flyway migrations V1-V6 to the Testcontainers PostgreSQL instance.; Test 1 (DB): INSERT a transaction row; SELECT it back; verify all monetary fields are DECIMAL(20,8) precision.; Test 2 (Redis): call redisClient.setWithTTL(test:key, hello, 30); assert get returns hello; verify TTL <= 30.; Test 3 (MQ): publish a JSON message to webhook-dispatch queue; subscribe and receive it within 5 seconds.
**Deliverable:** DataZoneIntegrationTest.java with 3 integration tests (DB, Redis, MQ).
**Acceptance / logic checks:**
- All 3 tests pass in a single mvn test run with Testcontainers.
- Test 1: the selected transaction row has collection_usd matching the inserted value with full DECIMAL(20,8) precision (no floating-point truncation).
- Test 2: Redis TTL on test:key is between 25 and 30 seconds (accounting for test execution time).
- Test 3: MQ round-trip completes within 5 seconds; received JSON matches published JSON exactly.
- Flyway migrations V1 through V6 apply without errors in the test setup.
**Depends on:** 14.4-T06, 14.4-T10, 14.4-T15

### 14.4-T31 — Document data-zone environment variable reference and connection string contract  _(30 min)_
**Context:** OPS-13 §4.7: runtime configuration injected via environment variables sourced from secrets manager at container start. Secret path convention: gmepay/<env>/<service>/<secret-name>. Services that need DB/Redis/MQ connections: hub-api, admin-backend, partner-portal-backend, batch-worker. NFR-10 §3.3 connection pool sizes must be externally configurable (not hardcoded). This ticket documents the env var contract so every service developer can wire their service correctly without reading OPS-13 from scratch.
**Steps:** Create docs/data-zone-env-vars.md.; Document all DB env vars: DB_WRITE_URL, DB_READ_URL, DB_USER, DB_PASSWORD, DB_POOL_MAX (default values per service per OPS-13 §10.2).; Document Redis env vars: REDIS_URL (cluster config endpoint), REDIS_PASSWORD, REDIS_POOL_MAX (default 20 for hub-api, 5 for batch-worker).; Document MQ env vars: AMQP_URL (TLS endpoint), AMQP_USER, AMQP_PASSWORD.; Document Flyway env vars: FLYWAY_URL, FLYWAY_USER, FLYWAY_PASSWORD, FLYWAY_LOCATIONS.; Include a table mapping each env var to its secrets manager path pattern (e.g., DB_PASSWORD -> gmepay/{env}/hub-api/db-password).
**Deliverable:** docs/data-zone-env-vars.md with all env vars, defaults, and secrets manager path mapping table.
**Acceptance / logic checks:**
- DB_WRITE_URL and DB_READ_URL are both documented with distinct descriptions (write to primary, read to replica).
- DB_POOL_MAX defaults table shows 20-50 for hub-api, 5-10 for batch-worker (matching NFR-10 §3.3).
- REDIS_POOL_MAX defaults table shows 10-20 for hub-api, 2-5 for batch-worker (matching NFR-10 §3.3).
- All env vars have a secrets path example following gmepay/{env}/{service}/{secret-name} pattern.
- AMQP_URL is documented with TLS scheme (amqps://) noted.
**Depends on:** 14.4-T04, 14.4-T10, 14.4-T15

### 14.4-T32 — Write Flyway migration for settlement_batches table and exactly-once settlement guard  _(35 min)_
**Context:** NFR-10 §5.3: each payment transaction must appear in exactly one ZeroPay settlement file. settlement_batch_id on the transaction record is set atomically with batch generation. Re-run of a failed batch must regenerate the same file content for the same transaction set. Table: settlement_batches (batch_id UUID PK, job_id VARCHAR(30), run_date DATE, batch_type VARCHAR(20) CHECK IN ('ZP0061','ZP0063'), status VARCHAR(20) CHECK IN ('GENERATING','SUBMITTED','ACKED','FAILED'), created_at TIMESTAMPTZ DEFAULT now(), submitted_at TIMESTAMPTZ, ack_at TIMESTAMPTZ, UNIQUE(job_id, run_date, batch_type)). FK on transactions.settlement_batch_id REFERENCES settlement_batches(batch_id).
**Steps:** Create db/migrations/V7__settlement_batches.sql.; Define settlement_batches table with all columns and constraints.; Add FK: ALTER TABLE transactions ADD COLUMN settlement_batch_id UUID REFERENCES settlement_batches(batch_id) (if not already present from V2; otherwise add only the FK constraint).; Add partial index: CREATE INDEX idx_transactions_unsettled ON transactions(status, created_at) WHERE settlement_batch_id IS NULL AND status = 'COMMITTED'; (for efficient batch query of un-settled committed transactions).; Run flyway migrate V7 after V1-V6; verify \d settlement_batches and the FK on transactions.
**Deliverable:** db/migrations/V7__settlement_batches.sql with settlement_batches table, UNIQUE constraint, partial index, and FK to transactions.
**Acceptance / logic checks:**
- flyway migrate applies V1 through V7 cleanly.
- UNIQUE(job_id, run_date, batch_type): inserting two settlement_batches for JOB-ZP-05 / 2026-06-05 / ZP0061 raises a UNIQUE constraint violation.
- batch_type='ZP0099' raises a CHECK constraint violation.
- EXPLAIN SELECT * FROM transactions WHERE settlement_batch_id IS NULL AND status='COMMITTED' uses idx_transactions_unsettled.
- FK from transactions.settlement_batch_id to settlement_batches.batch_id is enforced (INSERT of transaction with non-existent batch_id raises FK violation).
**Depends on:** 14.4-T06

### 14.4-T33 — Unit test: settlement batch idempotency and exactly-once guard  _(50 min)_
**Context:** NFR-10 §5.3: re-run of failed batch must regenerate the same file content for the same transaction set (idempotent). The guard: once a settlement_batch row exists for (job_id, run_date, batch_type), new batch generation for the same key must reuse the existing batch_id and the same transaction set. The exactly-once property: a transaction with settlement_batch_id already set must not be included in a new batch (preventing duplicate submission to ZeroPay). Test vectors: 3 committed transactions; first batch generation assigns all 3 to batch B1; re-run returns same B1 with same 3 transactions.
**Steps:** Create SettlementBatchIdempotencyTest.java in src/test/java/com/gmepay/batch/.; Setup: Testcontainers PostgreSQL with V1-V7 migrations; insert 3 transactions (status=COMMITTED, settlement_batch_id=NULL).; Test 1 (first generation): call generateBatch(JOB-ZP-05, 2026-06-05, ZP0061); assert 3 transactions returned; assert settlement_batches row created; assert all 3 transactions.settlement_batch_id = new batch_id.; Test 2 (idempotent re-run): call generateBatch again with same args; assert same batch_id returned; assert 3 transactions still assigned to same batch_id (no new rows created).; Test 3 (no double-include): insert a 4th transaction (COMMITTED, settlement_batch_id = B1 from prior run); call generateBatch; assert only the non-B1 transactions are eligible (4th excluded).
**Deliverable:** SettlementBatchIdempotencyTest.java with 3 test cases.
**Acceptance / logic checks:**
- Test 1: generateBatch returns 3 transactions and sets settlement_batch_id on all 3.
- Test 2: second generateBatch call returns same batch_id as first (idempotency confirmed).
- Test 3: transaction already assigned to B1 is excluded from subsequent generateBatch results.
- settlement_batches UNIQUE(job_id, run_date, batch_type) is never violated by the implementation (no exception in tests 1-3).
- All 3 tests pass with Testcontainers PostgreSQL.
**Depends on:** 14.4-T32, 14.4-T20


## WBS 14.5 — Secrets manager & config injection
### 14.5-T01 — Define secrets inventory interface and secret-path convention  _(30 min)_
**Context:** GMEPay+ uses a centralized secrets manager (HashiCorp Vault or AWS Secrets Manager) as the single source of truth for all credentials. Secret paths follow: gmepay/<env>/<service>/<secret-name>, e.g. gmepay/prod/batch-worker/zeropay-sftp-key. Environments are dev, int, staging, prod. Services include hub-api, batch-worker, admin-api, partner-portal-api, webhook-dispatcher. Secret types per SEC-09 §2.3: DB passwords, partner HMAC signing keys, ZeroPay SFTP private keys, ZeroPay API credentials, internal service tokens, partner API secret hashes.
**Steps:** Create a Java interface SecretsProvider with methods: getSecret(String path): String, getSecretBytes(String path): byte[], refreshSecret(String path): void.; Define an enum SecretPath with constants covering all known paths; each constant stores the template gmepay/{env}/{service}/{name}.; Define a constants class SecretPaths documenting every canonical path: DB_PASSWORD=gmepay/{env}/hub-api/db-password, ZEROPAY_SFTP_KEY=gmepay/{env}/batch-worker/zeropay-sftp-key, ZEROPAY_SFTP_USERNAME=gmepay/{env}/batch-worker/zeropay-sftp-username, PARTNER_HMAC_KEY=gmepay/{env}/hub-api/partner-hmac-key-{partner_id}, INTERNAL_JWT_SIGNING=gmepay/{env}/hub-api/jwt-signing-key.; Add Javadoc on each path constant stating which service consumes it and its secret type.; Write a unit test asserting that every path constant matches the regex gmepay/(dev|int|staging|prod)/[a-z-]+/[a-z0-9-{}]+.
**Deliverable:** SecretsProvider interface, SecretPaths constants class, and passing unit test in src/main/java/com/gmepay/infra/secrets/
**Acceptance / logic checks:**
- SecretsProvider compiles with no external dependencies; it is a pure interface.
- SecretPaths constants cover all 6 secret types listed in SEC-09 §2.3 (DB password, HMAC key, SFTP private key, ZeroPay API cred, JWT signing key, partner API secret hash ref).
- Path regex test passes for all constants; no path deviates from gmepay/{env}/{service}/{name} structure.
- Each constant has a Javadoc comment stating the consuming service.

### 14.5-T02 — Implement HashiCorp Vault secrets provider (KV v2)  _(45 min)_
**Context:** The preferred secrets manager is HashiCorp Vault with a KV secrets engine v2. The Vault address is injected as VAULT_ADDR env var; the service authenticates via Kubernetes ServiceAccount JWT (Vault Kubernetes auth method) or an AppRole token for non-K8s environments. Vault path prefix is gmepay/<env>/<service>/<name>. If Vault is unreachable at startup the service must fail fast with a clear error, never start with missing credentials. OPS-13 §3.5 states secrets are never stored in env var files or Docker images.
**Steps:** Add dependencies: vault-java-driver (BetterCloud) or Spring Vault (spring-cloud-vault-config), version pinned in pom.xml.; Implement VaultSecretsProvider implements SecretsProvider: read VAULT_ADDR and VAULT_TOKEN (or use K8s auth) from JVM env vars at construction time.; Implement getSecret(path): call vault.logical().read(path) on KV v2 mount; return data.get(value) field; throw SecretNotFoundException if path absent.; On startup call a health-check getSecret on a known canary path (gmepay/{env}/{service}/health-canary); if it throws, log ERROR and rethrow so the Spring context fails to start.; Write unit test with a mock Vault client: verify that a missing path throws SecretNotFoundException, and that a connection refusal at startup propagates (does not swallow the exception).
**Deliverable:** VaultSecretsProvider.java in src/main/java/com/gmepay/infra/secrets/vault/ with unit tests
**Acceptance / logic checks:**
- VaultSecretsProvider correctly reads gmepay/dev/hub-api/db-password from a mock KV v2 response {data:{value:testpass}} and returns testpass.
- A missing path throws SecretNotFoundException; the message includes the attempted path.
- Vault unreachable at construction throws VaultConnectionException; the service does not start.
- No credentials appear in log output (test that getSecret result is not logged at any level).
**Depends on:** 14.5-T01

### 14.5-T03 — Implement AWS Secrets Manager provider (fallback/cloud)  _(45 min)_
**Context:** When running on AWS infrastructure, AWS Secrets Manager is the alternative to Vault (OPS-13 §3.5, SEC-09 §2.3 assumption). The provider reads AWS_REGION from env and uses the AWS SDK SecretsManagerClient with IAM role credentials (no hardcoded keys). Secret names map 1:1 to the gmepay/<env>/<service>/<name> convention. The provider must be selectable at runtime via the SECRETS_BACKEND env var: vault (default) or aws-sm.
**Steps:** Add AWS SDK v2 dependency: software.amazon.awssdk:secretsmanager, version pinned.; Implement AwsSecretsManagerProvider implements SecretsProvider: instantiate SecretsManagerClient with DefaultCredentialsProvider and region from AWS_REGION env var.; Implement getSecret(path): call GetSecretValueRequest with SecretId=path; return SecretString; throw SecretNotFoundException on ResourceNotFoundException.; Create a SecretsProviderFactory @Bean that reads SECRETS_BACKEND env var and returns VaultSecretsProvider when vault, AwsSecretsManagerProvider when aws-sm, else throws IllegalStateException.; Write unit test: mock SecretsManagerClient; verify correct mapping of path to SecretId, and SecretNotFoundException on ResourceNotFoundException.
**Deliverable:** AwsSecretsManagerProvider.java and SecretsProviderFactory.java with unit tests
**Acceptance / logic checks:**
- getSecret with SecretId=gmepay/prod/hub-api/db-password returns the SecretString value from a mocked AWS response.
- ResourceNotFoundException from AWS SDK is caught and rethrown as SecretNotFoundException.
- SecretsProviderFactory with SECRETS_BACKEND=aws-sm returns AwsSecretsManagerProvider; with SECRETS_BACKEND=vault returns VaultSecretsProvider.
- SECRETS_BACKEND not set defaults to vault (or throws if desired — must be documented and tested explicitly).
**Depends on:** 14.5-T01

### 14.5-T04 — Implement in-memory secret cache with TTL and refresh  _(40 min)_
**Context:** Services retrieve secrets at startup and can refresh on rotation (OPS-13 §3.5). To avoid hammering the secrets manager on every request, cache secrets in memory with a configurable TTL (default 300 seconds). On TTL expiry, refresh lazily on next access. On explicit refreshSecret(path) call (triggered by rotation webhook), evict and re-fetch immediately. Never cache secrets to disk or logs.
**Steps:** Implement CachingSecretsProvider implements SecretsProvider, wrapping a delegate SecretsProvider.; Store cache as ConcurrentHashMap<String, CachedSecret> where CachedSecret holds value:String and expiresAt:Instant.; In getSecret(path): return cached value if expiresAt is in the future; otherwise delegate and cache with expiresAt = now + TTL.; Implement refreshSecret(path): evict from cache, delegate immediately, re-cache.; Read SECRETS_CACHE_TTL_SECONDS from env (default 300, min 60, max 1800); validate on construction and throw if out of range.; Write unit tests: cache hit avoids delegate call; expired entry triggers delegate call; refreshSecret forces re-fetch even within TTL.
**Deliverable:** CachingSecretsProvider.java with unit tests covering cache hit, expiry, and forced refresh
**Acceptance / logic checks:**
- With TTL=300, two calls to getSecret within 1 second invoke the delegate exactly once.
- After TTL expires (mocked clock), the next call invokes the delegate again.
- refreshSecret(path) evicts and re-fetches even if the cached entry has not expired.
- SECRETS_CACHE_TTL_SECONDS=30 throws IllegalArgumentException (below min 60).
- Cached values are never written to any log at any log level (assert no logger calls with secret value).
**Depends on:** 14.5-T02, 14.5-T03

### 14.5-T05 — Define environment config matrix as typed configuration classes  _(40 min)_
**Context:** OPS-13 §5.3 defines a config matrix across four environments (dev, int, staging, prod) covering: ZeroPay SFTP host, DB connection pool size (5/20/80/200), prefunding low-balance threshold (100/1000/10000/10000 USD), rate quote TTL (300s in dev/int, per-partner-config in staging/prod), webhook retry max (3/3/5/5), log level (DEBUG/DEBUG/INFO/INFO), batch job schedule (manual/manual/KST-cron/KST-cron), FX rate update mode (manual for all Phase-1 envs). Config is injected at runtime, not bundled in images.
**Steps:** Create @ConfigurationProperties(prefix=gmepay) class GmepayConfig with nested classes: DatabaseConfig (poolSize:int), ZeroPayConfig (sftpHost:String, sftpPort:int=22), PrefundingConfig (lowBalanceThresholdUsd:BigDecimal), WebhookConfig (retryMax:int), BatchConfig (schedule:String), and RateConfig (defaultQuoteTtlSeconds:int).; Annotate each field with @NotNull / @Min / @Max JSR-303 constraints: poolSize 1-500; retryMax 1-10; defaultQuoteTtlSeconds 60-1800.; Create src/main/resources/application.yml with placeholder values that must be overridden (set to REQUIRED or left intentionally invalid so startup fails if not injected).; Create src/test/resources/application-test.yml with dev-tier values: pool 5, sftpHost=localhost, prefundThreshold=100, retryMax=3, quoteTtl=300.; Write a Spring Boot @SpringBootTest with @ActiveProfiles(test) that loads the config and asserts each field equals the dev-tier value from the matrix.
**Deliverable:** GmepayConfig.java @ConfigurationProperties class and application-test.yml with a passing integration config test
**Acceptance / logic checks:**
- @SpringBootTest with application-test.yml starts without errors and GmepayConfig.database.poolSize == 5.
- Missing required field (e.g., sftpHost not set) causes BindException at startup — test this with a deliberately incomplete YAML.
- poolSize=501 fails @Max(500) constraint with a descriptive message.
- retryMax=0 fails @Min(1) constraint; quoteTtlSeconds=59 fails @Min(60).

### 14.5-T06 — Wire DB credentials injection from secrets manager into DataSource  _(35 min)_
**Context:** Database credentials must be fetched from the secrets manager at runtime. The path is gmepay/{env}/hub-api/db-password and gmepay/{env}/hub-api/db-username. These are injected into the HikariCP DataSource. The DB connection pool size comes from GmepayConfig (T05): dev=5, int=20, staging=80, prod=200. No DB credentials may appear in application.yml or any config file committed to source control (OPS-13 §4.7, SEC-09 §11.1).
**Steps:** Create @Bean DataSource dataSource(SecretsProvider secrets, GmepayConfig config, @Value(${gmepay.env}) String env) in DatabaseConfig.java.; Resolve paths: dbUrl = plain env var DB_JDBC_URL (non-secret); username = secrets.getSecret(gmepay/+env+/hub-api/db-username); password = secrets.getSecret(gmepay/+env+/hub-api/db-password).; Configure HikariCP: setJdbcUrl, setUsername, setPassword, setMaximumPoolSize(config.getDatabase().getPoolSize()), setConnectionTimeout(30_000), setLeakDetectionThreshold(60_000).; Ensure that if SecretsProvider throws SecretNotFoundException, the DataSource bean creation fails and the application context does not start.; Write a unit test with a mock SecretsProvider returning testuser/testpass; assert HikariDataSource is configured with those values and pool size 5.
**Deliverable:** DatabaseConfig.java @Configuration class injecting DB credentials from SecretsProvider with unit test
**Acceptance / logic checks:**
- DataSource is created with username=testuser and password=testpass when SecretsProvider returns those values for the correct paths.
- DB_JDBC_URL env var is used for the JDBC URL; no URL appears in any .yml or .properties file.
- SecretNotFoundException from SecretsProvider propagates and prevents DataSource bean from being created.
- HikariCP maximumPoolSize equals GmepayConfig.database.poolSize (e.g., 5 for dev).
- No credential value is logged in the DataSource creation flow.
**Depends on:** 14.5-T01, 14.5-T04, 14.5-T05

### 14.5-T07 — Wire partner HMAC signing key injection for API authentication  _(40 min)_
**Context:** Each partner's HMAC-SHA256 signing key is stored in the secrets vault at path gmepay/{env}/hub-api/partner-hmac-key-{partner_id} (SEC-09 §2.3). The Hub API gateway verifies signatures on every inbound request: HMAC-SHA256(key=partner_hmac_secret, data=METHOD+newline+path+newline+timestamp+newline+SHA256(body)). The key must be fetched from SecretsProvider (with cache), not from the DB. On rotation, refreshSecret is triggered via the rotation webhook (T12).
**Steps:** Create PartnerHmacKeyStore @Component with method getHmacKey(UUID partnerId): byte[].; Implement by calling secrets.getSecret(SecretPaths.partnerHmacKey(env, partnerId)) and decoding from hex; throw PartnerHmacKeyNotFoundException if absent.; Inject into HmacSignatureVerifier which already computes HMAC: replace any hardcoded or DB-backed key lookup with PartnerHmacKeyStore.getHmacKey(partnerId).; Add a method refreshKey(UUID partnerId) that calls secrets.refreshSecret(path) to allow forced eviction on rotation.; Write unit test: mock SecretsProvider returning a 64-char hex key for partner UUID aabbcc...; assert HmacSignatureVerifier.verify() returns true for a correctly signed request using that key.
**Deliverable:** PartnerHmacKeyStore.java and updated HmacSignatureVerifier.java with unit tests
**Acceptance / logic checks:**
- getHmacKey(partnerId) decodes the hex secret and returns a 32-byte array when SecretsProvider returns a valid 64-char hex string.
- HmacSignatureVerifier returns false for a request signed with the wrong key.
- PartnerHmacKeyNotFoundException is thrown when SecretsProvider throws SecretNotFoundException for the given partner ID.
- refreshKey(partnerId) calls secrets.refreshSecret with the exact path gmepay/{env}/hub-api/partner-hmac-key-{partnerId}.
**Depends on:** 14.5-T04

### 14.5-T08 — Wire ZeroPay SFTP private key injection into SFTP client  _(40 min)_
**Context:** The ZeroPay SFTP private key is stored at gmepay/{env}/batch-worker/zeropay-sftp-key (bytes, Ed25519 or RSA-4096). The SFTP username is at gmepay/{env}/batch-worker/zeropay-sftp-username. The SFTP host and port come from GmepayConfig (ZeroPayConfig). The private key must never be written to disk; all key material is kept in memory as a byte[]. OPS-13 §8.4 describes key rotation: update the secret path, restart the batch worker.
**Steps:** Create ZeroPaySftpClientFactory @Component accepting SecretsProvider, GmepayConfig, and String env.; Implement buildClient(): fetch private key bytes via secrets.getSecretBytes(gmepay/+env+/batch-worker/zeropay-sftp-key), fetch username via secrets.getSecret(gmepay/+env+/batch-worker/zeropay-sftp-username).; Build JSch (or Apache MINA SSHD) session from in-memory key bytes (addIdentity with byte[] overload); set host and port from ZeroPayConfig; do not write key to any temp file.; After session creation, zero-fill the raw key byte array: Arrays.fill(keyBytes, (byte)0).; Write unit test mocking SecretsProvider and asserting that buildClient() calls the correct secret paths and does not write any file to the filesystem.
**Deliverable:** ZeroPaySftpClientFactory.java with unit tests verifying in-memory key handling
**Acceptance / logic checks:**
- buildClient() fetches private key from path gmepay/{env}/batch-worker/zeropay-sftp-key (not from a file or env var).
- The key byte array is zeroed after use; any subsequent access to the local variable returns all zeros.
- SecretNotFoundException for the private key path causes buildClient() to throw SftpConfigurationException (not a generic NPE).
- SFTP host and port are sourced from GmepayConfig.zeropay (not from secrets), confirming correct separation of config vs. secret.
**Depends on:** 14.5-T04, 14.5-T05

### 14.5-T09 — Implement feature flag injection from config/secrets manager  _(35 min)_
**Context:** OPS-13 §5.2 defines five feature flags stored in the secrets/config manager, loaded at service startup. Flags: FEATURE_LIVE_FX_FEED (default false), FEATURE_PARTNER_REFUND_API (default false), FEATURE_OUTBOUND_PAYMENTS (default false), FEATURE_BOK_REPORTING (default false), FEATURE_MULTI_SCHEME_ROUTING (default false). Changing a flag requires a config update and service restart, not a full deployment. Flags are boolean strings (true/false) at path gmepay/{env}/hub-api/feature-{flag-name}.
**Steps:** Create FeatureFlagConfig @Component with @PostConstruct init() reading each flag from SecretsProvider; provide @Value fallback default (false) if SecretNotFoundException is thrown.; Expose isEnabled(FeatureFlag flag): boolean method where FeatureFlag is an enum with values LIVE_FX_FEED, PARTNER_REFUND_API, OUTBOUND_PAYMENTS, BOK_REPORTING, MULTI_SCHEME_ROUTING.; Map each enum to its path: gmepay/{env}/hub-api/feature-live-fx-feed etc.; Add a @RefreshScope-compatible reload() method that re-reads all flags from the secrets manager (for post-rotation restart scenario).; Write unit tests: (a) SecretsProvider returns true for FEATURE_PARTNER_REFUND_API -> isEnabled returns true; (b) SecretNotFoundException -> isEnabled returns false (safe default); (c) value is neither true nor false -> throws InvalidFlagValueException.
**Deliverable:** FeatureFlagConfig.java, FeatureFlag enum, and unit tests
**Acceptance / logic checks:**
- isEnabled(LIVE_FX_FEED) returns false when SecretsProvider throws SecretNotFoundException (safe default, not exception propagation).
- isEnabled(PARTNER_REFUND_API) returns true when SecretsProvider returns the string true for that path.
- An unexpected value (e.g., yes) throws InvalidFlagValueException with the flag name and offending value in the message.
- All 5 flags from the OPS-13 matrix are represented as enum values; no flag is missing.
**Depends on:** 14.5-T04

### 14.5-T10 — Implement secret-path environment resolution utility  _(30 min)_
**Context:** All secret paths use the template gmepay/{env}/{service}/{name} where {env} is one of dev, int, staging, prod. The active environment is injected as GMEPAY_ENV env var (required). Services must never construct raw path strings ad-hoc; they must use a resolver that validates env at startup and builds paths consistently to prevent misrouting secrets across environments (e.g. dev code accidentally reading prod secrets).
**Steps:** Create SecretPathResolver @Component reading GMEPAY_ENV at construction time; throw UnknownEnvironmentException if value is not in {dev, int, staging, prod}.; Expose resolve(String service, String name): String method returning gmepay/{env}/{service}/{name}.; Add a convenience overload resolveForCurrentService(String name): String that uses a service name constant (GMEPAY_SERVICE_NAME env var).; Add a validate() method that asserts the constructed path matches the regex ^gmepay/(dev|int|staging|prod)/[a-z0-9-]+/[a-z0-9-{}]+$ and throws InvalidSecretPathException on mismatch.; Write unit tests: GMEPAY_ENV=prod, service=hub-api, name=db-password -> gmepay/prod/hub-api/db-password; GMEPAY_ENV=unknown -> UnknownEnvironmentException; service containing uppercase -> InvalidSecretPathException.
**Deliverable:** SecretPathResolver.java with unit tests validating all four environments and invalid-input rejection
**Acceptance / logic checks:**
- resolve(hub-api, db-password) with GMEPAY_ENV=prod returns exactly gmepay/prod/hub-api/db-password.
- GMEPAY_ENV not set (null) throws UnknownEnvironmentException at construction time, not at first resolve() call.
- resolve(Hub-API, db-password) with uppercase in service name throws InvalidSecretPathException.
- resolve() with an empty name throws InvalidSecretPathException.
- The resolver rejects a service name containing a slash character.

### 14.5-T11 — Implement config matrix loader and environment validation at startup  _(35 min)_
**Context:** OPS-13 §5.3 mandates that all environment-tier config values (DB pool, SFTP host, batch schedule, log level, etc.) differ between dev/int/staging/prod and are injected at runtime. The application must validate that no cross-environment contamination is possible: specifically, the staging/prod batch schedule must be KST cron expressions, while dev/int must be manual-trigger only; and the log level must be DEBUG for dev/int and INFO for staging/prod. Misconfiguration should be caught at startup, not at runtime.
**Steps:** Create ConfigMatrixValidator @Component with a @PostConstruct validate() method.; Read GMEPAY_ENV and GmepayConfig.batch.schedule; if env is staging or prod and schedule equals manual or empty, throw BatchScheduleMisconfigurationException.; Read log level from GmepayConfig or logging config; if env is prod and level is DEBUG, throw InsecureLogLevelException (DEBUG logs can leak sensitive data per OPS-13 §7.2).; Read GmepayConfig.database.poolSize; if env is prod and poolSize < 100, emit a WARN log (not an exception — pool size is advisory).; Write unit tests for each validation rule: staging+manual-schedule throws; prod+DEBUG throws; prod+poolSize=50 logs WARN but does not throw.
**Deliverable:** ConfigMatrixValidator.java with unit tests covering all three validation rules
**Acceptance / logic checks:**
- env=prod + schedule=manual throws BatchScheduleMisconfigurationException at startup.
- env=staging + schedule=manual throws BatchScheduleMisconfigurationException.
- env=prod + logLevel=DEBUG throws InsecureLogLevelException.
- env=dev + logLevel=DEBUG does not throw (DEBUG is correct for dev).
- env=prod + poolSize=50 logs a WARN message containing the pool size value but does not halt startup.
**Depends on:** 14.5-T05, 14.5-T10

### 14.5-T12 — Implement secret rotation webhook endpoint  _(35 min)_
**Context:** OPS-13 §4.7 states that rotation triggers an automated re-deployment of affected services. As a lighter-weight alternative for HMAC keys and feature flags, a rotation-notification webhook POST /internal/secrets/rotate/{service}/{name} allows the SecretsProvider cache to be invalidated immediately without a full restart. This endpoint is on the internal (management zone) port only, not exposed to the public DMZ. It must be authenticated via a shared internal token stored at gmepay/{env}/hub-api/internal-rotation-token.
**Steps:** Create SecretRotationController @RestController @RequestMapping(/internal/secrets) on management port (default 8081).; Implement POST /rotate/{service}/{name}: verify Authorization: Bearer <token> against SecretsProvider.getSecret(gmepay/{env}/hub-api/internal-rotation-token); if mismatch return 401.; Call cachingSecretsProvider.refreshSecret(pathResolver.resolve(service, name)) and return 200 {refreshed: true, path: ...}.; Return 404 if SecretNotFoundException is thrown during refresh (path does not exist in vault).; Write unit tests: valid token -> 200 with correct path; wrong token -> 401; unknown path -> 404; verify refreshSecret is called with the correct resolved path.
**Deliverable:** SecretRotationController.java on management port with unit tests
**Acceptance / logic checks:**
- POST /internal/secrets/rotate/hub-api/db-password with correct Bearer token returns HTTP 200 and JSON {refreshed:true}.
- POST with an incorrect token returns HTTP 401; no cache eviction occurs (mock asserts refreshSecret not called).
- POST for a path not present in vault returns HTTP 404.
- The endpoint is bound to management port 8081 and NOT accessible on the main API port 8080.
**Depends on:** 14.5-T04, 14.5-T10

### 14.5-T13 — Add secret-scan CI pipeline gate to block committed credentials  _(40 min)_
**Context:** SEC-09 §5.6 mandates that the CI pipeline includes a secrets-leak scan on every commit. Any detected credential blocks the merge and triggers immediate rotation. OPS-13 §4.1 Stage 3 includes secret scanning as a pipeline gate. The scan must cover: API keys (pk_test_/sk_test_ prefixes per API-05 §10.2), JWT tokens, Vault tokens, AWS access keys, and database connection strings. The tool must be integrated as a CI step that exits non-zero on any finding.
**Steps:** Add a .github/workflows/secrets-scan.yml (or GitLab equivalent) job running on every pull request and push to main/release/* branches.; Use TruffleHog or gitleaks: install via the official Docker image; scan all commits in the PR diff using trufflehog git --since-commit <base-sha> --fail.; Add a .gitleaks.toml or trufflehog config that adds custom regexes for GMEPay+ patterns: pk_test_[a-f0-9]{32}, sk_test_[a-f0-9]{64}, gmepay/prod/.* (catches hardcoded prod paths).; Ensure the job fails (exit code != 0) on any finding; the merge is blocked by branch protection requiring this job to pass.; Document the procedure: if a secret is accidentally committed, the runbook step is (1) rotate immediately, (2) force-push to remove from history or use git-filter-repo, (3) file a SEC incident record (SEC-09 §7).
**Deliverable:** secrets-scan.yml CI workflow file and .gitleaks.toml custom rules file, with runbook note in RUNBOOK.md
**Acceptance / logic checks:**
- CI workflow runs trufflehog or gitleaks on every PR; a test commit containing sk_test_aaaa... causes the job to exit 1.
- Custom regex detects pk_test_ and sk_test_ prefixes from API-05 §10.2 test credential format.
- The workflow job is listed in branch protection required-status-checks so that its failure blocks merge.
- The runbook snippet documents rotate-first-then-rewrite order for an accidentally committed secret.

### 14.5-T14 — Inject feature flags into Spring Boot context via @ConditionalOnFeatureFlag  _(40 min)_
**Context:** Feature flags (T09) must gate actual code paths. Rather than if-statements scattered through business code, create a @ConditionalOnFeatureFlag annotation and a BeanRegistrationAwareBeanFactoryPostProcessor that deregisters beans when their flag is disabled. Flags FEATURE_PARTNER_REFUND_API and FEATURE_OUTBOUND_PAYMENTS guard entire controller endpoints that must not be reachable when false. This is distinct from security - it prevents accidental exposure of Phase 2 features in Phase 1 production.
**Steps:** Create annotation @ConditionalOnFeatureFlag(FeatureFlag value) that is meta-annotated with @Conditional(FeatureFlagCondition.class).; Implement FeatureFlagCondition implements Condition: read GMEPAY_ENV and the flag value from environment (System.getenv), not from SecretsProvider (condition runs before beans are created); use the same default-false rule.; Annotate PartnerRefundController with @ConditionalOnFeatureFlag(PARTNER_REFUND_API) and OutboundPaymentController with @ConditionalOnFeatureFlag(OUTBOUND_PAYMENTS).; Write a Spring Boot test with FEATURE_PARTNER_REFUND_API=false: assert ApplicationContext does not contain PartnerRefundController bean.; Write a second test with FEATURE_PARTNER_REFUND_API=true: assert the bean is present.
**Deliverable:** @ConditionalOnFeatureFlag annotation, FeatureFlagCondition, and two @SpringBootTest integration tests
**Acceptance / logic checks:**
- With FEATURE_PARTNER_REFUND_API=false, PartnerRefundController bean is absent from context; GET /v1/payments/refunds returns 404.
- With FEATURE_PARTNER_REFUND_API=true, PartnerRefundController bean is present; endpoint is routable.
- FeatureFlagCondition falls back to false (disabled) when the env var is not set, making Phase 1 deployment safe by default.
- The @ConditionalOnFeatureFlag annotation can be applied to any @Component or @Bean declaration, not only controllers.
**Depends on:** 14.5-T09

### 14.5-T15 — Write unit tests for VaultSecretsProvider - error paths and TTL  _(45 min)_
**Context:** VaultSecretsProvider (T02) and CachingSecretsProvider (T04) are logic-bearing classes that protect all secrets access. A dedicated test ticket with precise input/expected-output vectors ensures correctness of error handling, TTL expiry, and concurrent access. Test vectors must use a mock Vault client, not a live Vault instance.
**Steps:** Create VaultSecretsProviderTest using Mockito to mock the underlying Vault client.; Test 1 (happy path): mock returns {data:{value:s3cr3t}} for path gmepay/dev/hub-api/db-password; assert getSecret returns s3cr3t.; Test 2 (missing path): mock throws VaultException(HTTP 404); assert SecretNotFoundException is thrown with message containing the path.; Test 3 (vault down): mock throws VaultException(connection refused); assert VaultConnectionException is thrown.; Test 4 (TTL expiry via CachingSecretsProvider): use a fixed clock; call getSecret at t=0 (cache miss -> delegate called), at t=100s (cache hit, TTL=300s -> delegate NOT called), at t=301s (cache miss -> delegate called again). Assert delegate call count is exactly 2.; Test 5 (concurrent access): fire 10 threads simultaneously on an expired cache entry; assert delegate is called exactly once (no thundering herd).
**Deliverable:** VaultSecretsProviderTest.java and CachingSecretsProviderTest.java with 5 test cases each
**Acceptance / logic checks:**
- Test 1 passes: getSecret returns s3cr3t for the correct KV v2 data structure.
- Test 2 passes: SecretNotFoundException message contains gmepay/dev/hub-api/db-password.
- Test 4 passes: delegate call count is exactly 2 across t=0, t=100, t=301 with a mocked clock.
- Test 5 passes: under 10 concurrent threads with an expired entry, the delegate is called exactly once (lock-based or compare-and-swap refresh).
**Depends on:** 14.5-T02, 14.5-T04

### 14.5-T16 — Write unit tests for AWS Secrets Manager provider and factory selection  _(35 min)_
**Context:** AwsSecretsManagerProvider (T03) and SecretsProviderFactory (T03) must be tested with mock AWS SDK clients. Test vectors cover the happy path, ResourceNotFoundException mapping, and the SECRETS_BACKEND env var factory decision logic.
**Steps:** Create AwsSecretsManagerProviderTest with a mocked SecretsManagerClient.; Test 1: GetSecretValueResponse with SecretString=prodpass for SecretId=gmepay/prod/hub-api/db-password -> getSecret returns prodpass.; Test 2: client throws ResourceNotFoundException -> getSecret throws SecretNotFoundException with path in message.; Test 3: client throws SdkClientException (network issue) -> getSecret throws VaultConnectionException (or AwsSmConnectionException extending the same base).; Create SecretsProviderFactoryTest: SECRETS_BACKEND=vault -> returns VaultSecretsProvider; SECRETS_BACKEND=aws-sm -> returns AwsSecretsManagerProvider; SECRETS_BACKEND=unrecognised -> throws IllegalStateException; SECRETS_BACKEND not set -> defaults to vault.
**Deliverable:** AwsSecretsManagerProviderTest.java and SecretsProviderFactoryTest.java with 7 test cases total
**Acceptance / logic checks:**
- Test 1 passes: SecretString value is returned verbatim without modification.
- Test 2 passes: ResourceNotFoundException maps to SecretNotFoundException; path appears in exception message.
- Test 3 passes: SdkClientException maps to a connection-related exception, not propagated as an unchecked SDK type.
- SecretsProviderFactory returns the correct concrete type for each SECRETS_BACKEND value; the unknown value test throws IllegalStateException with the invalid value in the message.
**Depends on:** 14.5-T03

### 14.5-T17 — Write integration tests for config matrix validation rules  _(40 min)_
**Context:** ConfigMatrixValidator (T11) enforces that the correct config tier is active. Integration tests must verify these rules fire correctly in a Spring Boot context without a real secrets manager (use mock or env var injection). Test vectors cover each failing case and the WARN-only case.
**Steps:** Create ConfigMatrixValidatorIntegrationTest as @SpringBootTest with @TestPropertySource.; Test 1 (prod + manual schedule): set GMEPAY_ENV=prod and gmepay.batch.schedule=manual; assert ApplicationContext fails to start with BatchScheduleMisconfigurationException in cause.; Test 2 (staging + manual schedule): same but GMEPAY_ENV=staging; assert same exception.; Test 3 (prod + DEBUG log level): set GMEPAY_ENV=prod and root log level to DEBUG; assert InsecureLogLevelException in startup failure cause.; Test 4 (dev + DEBUG): GMEPAY_ENV=dev + DEBUG; assert ApplicationContext starts successfully.; Test 5 (prod + poolSize=50): GMEPAY_ENV=prod + poolSize=50; assert context starts but a WARN log message containing poolSize=50 is emitted.
**Deliverable:** ConfigMatrixValidatorIntegrationTest.java with 5 test cases
**Acceptance / logic checks:**
- Test 1 and 2 both throw BatchScheduleMisconfigurationException at context startup.
- Test 3 throws InsecureLogLevelException at context startup; the exception message includes the word prod.
- Test 4 starts successfully with no exceptions.
- Test 5 starts successfully and the captured log output contains a WARN line with the numeric value 50.
**Depends on:** 14.5-T11

### 14.5-T18 — Write integration tests for secret rotation webhook endpoint  _(40 min)_
**Context:** SecretRotationController (T12) must be tested end-to-end in a Spring Boot test slice. The test must verify authentication enforcement, correct cache eviction, and 404 handling for unknown paths. Use MockMvc on the management port and a mock SecretsProvider.
**Steps:** Create SecretRotationControllerTest as @WebMvcTest(SecretRotationController.class) with a @MockBean SecretsProvider.; Test 1 (valid token): mock getSecret for rotation token path returns correct-token; POST /internal/secrets/rotate/hub-api/db-password with Authorization: Bearer correct-token; expect 200 and {refreshed:true, path:gmepay/env/hub-api/db-password}; verify refreshSecret called once.; Test 2 (wrong token): same POST with Authorization: Bearer wrong-token; expect 401; verify refreshSecret never called.; Test 3 (no auth header): POST without Authorization; expect 401.; Test 4 (unknown path): valid token; mock refreshSecret to throw SecretNotFoundException; expect 404.; Test 5 (service name injection): verify path in response body equals gmepay/{env}/hub-api/db-password with the actual env value, not a literal placeholder.
**Deliverable:** SecretRotationControllerTest.java with 5 MockMvc test cases
**Acceptance / logic checks:**
- Test 1 returns HTTP 200 JSON with refreshed:true and the fully-resolved path.
- Test 2 and 3 return HTTP 401 and do not call refreshSecret.
- Test 4 returns HTTP 404 when refreshSecret throws SecretNotFoundException.
- Test 5 asserts the path in the response body is gmepay/dev/hub-api/db-password (using GMEPAY_ENV=dev in test properties), not a template string.
**Depends on:** 14.5-T12

### 14.5-T19 — Add Helm chart values files for each environment tier  _(45 min)_
**Context:** OPS-13 §4.7: runtime configuration is injected via environment variables from the secrets manager at container start; no config files are bundled in the image. Kubernetes deployments use Helm charts. Each environment tier (dev, int, staging, prod) needs a values file that specifies env var names and Vault/AWS SM secret references (not actual values). The actual secret values are never in the Helm chart; only the path references are.
**Steps:** Create helm/gmepay/values-dev.yaml, values-int.yaml, values-staging.yaml, values-prod.yaml.; Each file sets: env.GMEPAY_ENV (dev/int/staging/prod), env.SECRETS_BACKEND (vault or aws-sm), env.VAULT_ADDR (Vault URL for that tier, not a secret), secretRefs listing Vault dynamic injection annotations or ExternalSecret CRDs for each secret path.; For prod values: set env.DB_POOL_SIZE=200, env.WEBHOOK_RETRY_MAX=5, env.LOG_LEVEL=INFO, env.BATCH_SCHEDULE=0 30 1 * * KST (cron for 01:30 KST = 16:30 UTC).; For dev values: set env.DB_POOL_SIZE=5, env.WEBHOOK_RETRY_MAX=3, env.LOG_LEVEL=DEBUG, env.BATCH_SCHEDULE=manual.; Add a Helm lint step to the CI pipeline (helm lint helm/gmepay -f helm/gmepay/values-prod.yaml) and verify it exits 0.
**Deliverable:** Four Helm values files (values-dev.yaml, values-int.yaml, values-staging.yaml, values-prod.yaml) and CI lint step
**Acceptance / logic checks:**
- values-prod.yaml contains GMEPAY_ENV=prod, DB_POOL_SIZE=200, LOG_LEVEL=INFO, BATCH_SCHEDULE set to a KST cron expression.
- values-dev.yaml contains GMEPAY_ENV=dev, DB_POOL_SIZE=5, LOG_LEVEL=DEBUG, BATCH_SCHEDULE=manual.
- No values file contains any secret value; only Vault path references or ExternalSecret resource names are present.
- helm lint passes for all four values files with exit code 0.
- The VAULT_ADDR for prod and staging differ from dev and int (separate Vault instances or namespaces), confirming environment isolation.
**Depends on:** 14.5-T05, 14.5-T10

### 14.5-T20 — Implement Vault dynamic secret injection via Vault Agent sidecar annotation  _(50 min)_
**Context:** OPS-13 §3.5 and SEC-09 §2.3: database credentials are injected at runtime. In Kubernetes, Vault Agent Injector is the preferred pattern for dynamic injection: the sidecar reads secrets from Vault and writes them to a shared tmpfs volume; the application reads them as env vars via envFrom. This avoids storing secrets in Kubernetes Secrets objects. The annotation prefix is vault.hashicorp.com/. Services that need this are hub-api, batch-worker, admin-api.
**Steps:** Update helm/gmepay/templates/deployment-hub-api.yaml to add Vault Agent Injector annotations: vault.hashicorp.com/agent-inject=true, vault.hashicorp.com/role=hub-api, vault.hashicorp.com/agent-inject-secret-db-password=gmepay/{env}/hub-api/db-password with template writing export DB_PASSWORD={{ .Data.data.value }}.; Repeat for batch-worker deployment: inject gmepay/{env}/batch-worker/zeropay-sftp-key and gmepay/{env}/batch-worker/zeropay-sftp-username.; Create Vault policy files vault/policies/hub-api.hcl and vault/policies/batch-worker.hcl granting read capability on each service's paths only (path gmepay/{env}/hub-api/* { capabilities = [read] }).; Create vault/policies/ci.hcl with read-only on gmepay/ci/* and explicitly deny on gmepay/prod/* and gmepay/staging/*.; Write a test: render the Helm templates with helm template and assert that no deployment manifest contains a DB password as a literal env value; only the Vault annotation and template are present.
**Deliverable:** Updated Helm deployment templates with Vault annotations, three Vault policy HCL files, and a Helm template rendering test
**Acceptance / logic checks:**
- Rendered hub-api Deployment YAML contains vault.hashicorp.com/agent-inject=true annotation.
- Rendered hub-api Deployment YAML does NOT contain any literal DB password string.
- vault/policies/hub-api.hcl grants read on gmepay/{env}/hub-api/* and has no capabilities on gmepay/{env}/batch-worker/* or gmepay/prod/* from a different env.
- vault/policies/ci.hcl explicitly denies read on gmepay/prod/* (deny capability).
- Vault policy for batch-worker grants read on gmepay/{env}/batch-worker/* only.
**Depends on:** 14.5-T19

### 14.5-T21 — Document secret rotation procedures in operational runbook  _(35 min)_
**Context:** OPS-13 §8.3 (partner API key rotation) and §8.4 (ZeroPay SFTP key rotation) define the runbook steps. The rotation procedures must also document how the secrets manager secret path is updated and which services must be restarted. The runbook must be a standalone section in RUNBOOK.md that a developer or ops engineer with no prior context can execute.
**Steps:** Create or extend docs/RUNBOOK.md with a section 'Secret Rotation Procedures'.; Sub-section 1 (DB password rotation): steps: (1) generate new password, (2) update Vault path gmepay/{env}/hub-api/db-password, (3) POST /internal/secrets/rotate/hub-api/db-password on each hub-api instance OR restart pods, (4) verify new connections succeed in DB pool metrics, (5) revoke old password, (6) log in SEC audit trail.; Sub-section 2 (ZeroPay SFTP key rotation): steps from OPS-13 §8.4: generate Ed25519 key pair, send public key to 한결원, await confirmation, update gmepay/{env}/batch-worker/zeropay-sftp-key with new private key, restart batch-worker (kubectl rollout restart deployment/batch-worker -n gmepay-{env}), verify test SFTP connection, zero-fill old key material, log rotation.; Sub-section 3 (partner HMAC key rotation): generate new 32-byte random key, store hex-encoded at gmepay/{env}/hub-api/partner-hmac-key-{partner_id}, call refreshKey(partnerId) via rotation webhook, coordinate 24-72h transition window with partner, deactivate old key.; Sub-section 4 (emergency rotation): if secret is confirmed compromised: rotate first (steps 1-3 above), then follow SEC-09 §7 incident response.
**Deliverable:** docs/RUNBOOK.md secret rotation section covering DB password, SFTP key, and partner HMAC key rotation
**Acceptance / logic checks:**
- DB password rotation runbook includes the exact Vault path gmepay/{env}/hub-api/db-password and the rotation webhook URL pattern.
- SFTP key rotation runbook includes the kubectl restart command kubectl rollout restart deployment/batch-worker -n gmepay-{env}.
- Partner HMAC key rotation runbook includes the 24-72h transition window requirement from OPS-13 §8.3.
- Emergency rotation section explicitly states rotate-then-incident-report order (not the reverse).
- Each sub-section is numbered and can be followed as a checklist by an operator with no prior project context.
**Depends on:** 14.5-T07, 14.5-T08, 14.5-T12

### 14.5-T22 — Add container image secret-scanning step to CI pipeline  _(35 min)_
**Context:** SEC-09 §5.6 requires container images to be scanned before deployment. The scan must check for secrets baked into image layers (e.g., accidentally COPYed .env files, credentials in ENV instructions, secrets in build ARGs). This is distinct from dependency CVE scanning. The tool is Trivy with the --scanners secret flag. Any HIGH or CRITICAL finding blocks the deployment gate.
**Steps:** Add a CI job image-secret-scan after the Artifact stage (Stage 5 in OPS-13 §4.1).; Job command: trivy image --scanners secret --severity HIGH,CRITICAL --exit-code 1 <image>:<tag>.; Add a negative test: build a test Docker image with a deliberate fake secret (ENV FAKE_SECRET=sk_test_aabbcc...) and assert that the scan job exits 1 for this image.; Add a positive test: build the real application image and assert the scan job exits 0 (no secrets found).; Configure the CI gate so that a non-zero exit from image-secret-scan blocks the Deploy to int stage.
**Deliverable:** image-secret-scan CI job in the pipeline YAML, with documented negative and positive test scenarios
**Acceptance / logic checks:**
- trivy image --scanners secret on an image containing ENV FAKE_SECRET=sk_test_aabbcc exits 1 (finding detected).
- trivy image --scanners secret on the clean application image exits 0.
- The CI pipeline stage order places image-secret-scan after Artifact build and before Deploy to int.
- A non-zero exit from the scan job causes the pipeline to halt; Deploy to int does not proceed.
**Depends on:** 14.5-T13

### 14.5-T23 — Validate no secrets in structured logs - log-scrubbing filter  _(40 min)_
**Context:** OPS-13 §7.2 states that sensitive fields (collection_amount, prefund_balance, rate components) are logged at INFO only in non-prod; in prod, log structured references (IDs) only. SEC-09 §2.3 states HMAC keys and SFTP credentials are never logged. Implement a Logback filter that redacts any log argument matching known secret patterns before they reach the appender.
**Steps:** Create SecretRedactionFilter extends TurboFilter in the logging package.; In decide(): inspect all formatted arguments and the formatted message for patterns: 64-char hex strings (potential HMAC keys), strings matching sk_test_.* or pk_test_.*, and strings matching BEGIN.*PRIVATE KEY.; Replace matched substrings with [REDACTED-SECRET] in the message before passing to the appender.; Register the filter in logback-spring.xml as a <turboFilter> active in all environments.; Write a unit test: log a message containing a 64-char hex string via SLF4J; capture log output; assert the output contains [REDACTED-SECRET] and does not contain the original hex string.
**Deliverable:** SecretRedactionFilter.java and logback-spring.xml update with unit test
**Acceptance / logic checks:**
- Log message containing a 64-char hex string aabbcc...ff (exactly 64 chars) is redacted to [REDACTED-SECRET] in the appender output.
- Log message containing sk_test_ prefix is redacted.
- Log message containing -----BEGIN PRIVATE KEY----- is redacted.
- A normal log message (e.g., Payment processed for partner X amount USD 100) is NOT redacted.
- The filter is active in the production log configuration (logback-spring.xml <springProfile name=prod> includes the filter).

### 14.5-T24 — End-to-end smoke test: secrets injection, config matrix, and feature flags  _(55 min)_
**Context:** A final integration smoke test verifies that the full secrets injection pipeline works together: SecretsProvider -> CachingSecretsProvider -> DataSource creation -> FeatureFlagConfig -> ConfigMatrixValidator. The test uses a WireMock stub as a mock Vault server, not a mock in-process bean, to validate the HTTP interaction. This confirms that the real HTTP client path works in a test environment before deployment to dev/int.
**Steps:** Create SecretsInjectionSmokeTest as @SpringBootTest with a WireMockServer on a random port.; Set VAULT_ADDR=http://localhost:{wiremockPort} and GMEPAY_ENV=dev and SECRETS_BACKEND=vault.; Stub WireMock to return valid KV v2 responses for all required paths: db-password, db-username, jwt-signing-key, internal-rotation-token, all 5 feature flags (all false).; Start the Spring context; assert it starts without errors.; Call GET /actuator/health and assert status=UP (confirming DataSource and all required beans initialized).; Assert FeatureFlagConfig.isEnabled(PARTNER_REFUND_API) == false and FeatureFlagConfig.isEnabled(LIVE_FX_FEED) == false.
**Deliverable:** SecretsInjectionSmokeTest.java as a @SpringBootTest integration test with WireMock Vault stub
**Acceptance / logic checks:**
- Spring context starts successfully with all WireMock stubs in place.
- Removing the WireMock stub for db-password causes the context to fail to start (proving secrets are truly fetched at startup).
- All 5 feature flags return false when stubbed with false.
- GET /actuator/health returns HTTP 200 {status:UP}.
- The test completes in under 30 seconds (WireMock is fast; this guards against indefinite vault connection hangs).
**Depends on:** 14.5-T06, 14.5-T09, 14.5-T11, 14.5-T14, 14.5-T15


## WBS 14.6 — Observability: metrics/logs/tracing
### 14.6-T01 — Define Prometheus metric names, types, labels, and registration contract  _(30 min)_
**Context:** GMEPay+ uses Prometheus for metrics. Required metrics (NFR-10 §7.1, OPS-13 §7.1) include: payment_requests_total (Counter, labels: partner_id/scheme/direction/status), payment_latency_ms (Histogram, labels: partner_id/scheme/endpoint), prefunding_balance_usd (Gauge, label: partner_id), scheme_api_errors_total (Counter, labels: scheme/error_code), circuit_breaker_state (Gauge 0=closed 1=open, label: dependency), batch_job_duration_ms (Histogram, labels: job_name/status), batch_file_delivery_lag_seconds (Gauge, label: file_type), rate_engine_calculations_total (Counter, labels: rule_id/direction), pool_identity_breaches_total (Counter, no labels, must always be 0), webhook_delivery_attempts_total (Counter, labels: partner_id/status), db_connection_pool_utilisation (Gauge, label: service). RED metrics: api_request_rate, api_error_rate, api_latency_p50/p95/p99. USE metrics for API pods, PostgreSQL, Redis, message queue, SFTP worker.
**Steps:** Create MetricsRegistry interface/class in observability module listing all required metric names as constants; Define registration method per metric (name, type, help string, label names); Write a MetricDefinitions catalog file or enum that serves as the single source of truth; Document histogram bucket boundaries: payment_latency_ms (10,50,100,250,500,1000,2500,5000ms), batch_job_duration_ms (1000,5000,15000,30000,60000,120000ms); Add validation: pool_identity_breaches_total must have no labels and initial value 0
**Deliverable:** MetricsRegistry interface/class in src/main/java/com/gmepay/observability/MetricsRegistry.java (or language-equivalent) with all 11+ named metrics, types, label names, and histogram buckets as constants
**Acceptance / logic checks:**
- All 11 metric names match the NFR-10 §7.1 table exactly (no typos, no extras)
- payment_requests_total has exactly 4 labels: partner_id, scheme, direction, status
- pool_identity_breaches_total is registered with zero label names
- Histogram buckets for payment_latency_ms include 500 (the p95 SLO boundary)
- A unit test confirms the registry initialises without exception and all metrics are queryable by name

### 14.6-T02 — Implement Prometheus metric emission in Hub API service (payment path)  _(45 min)_
**Context:** Hub API handles POST /v1/payments (Fixed MPM) and POST /v1/payments/cpm/generate (CPM). For every inbound request the service must increment payment_requests_total{partner_id, scheme, direction, status} and record payment_latency_ms{partner_id, scheme, endpoint}. Status label values: APPROVED, FAILED, REJECTED, UNCERTAIN. Direction values: Inbound, Outbound, Domestic, Hub. Rate engine calls increment rate_engine_calculations_total{rule_id, direction}. Pool identity assertion failure increments pool_identity_breaches_total. Metrics registry defined in 14.6-T01.
**Steps:** Inject MetricsRegistry into PaymentOrchestrator (or equivalent service class); Wrap payment handler: start timer on request entry, record payment_latency_ms on exit; Increment payment_requests_total with correct labels on every terminal outcome; Increment rate_engine_calculations_total after each rate-engine invocation; Increment pool_identity_breaches_total when pool identity assertion fires (collection_usd - collection_margin_usd - payout_margin_usd != payout_usd_cost beyond 0.01 USD); Add /actuator/prometheus endpoint exposure (or equivalent scrape endpoint)
**Deliverable:** PaymentOrchestrator updated with metric instrumentation; integration test confirming /actuator/prometheus returns correctly labelled counter and histogram samples after a test payment call
**Acceptance / logic checks:**
- After one APPROVED domestic payment for partner GME_REMIT: payment_requests_total{partner_id=GME_REMIT,scheme=ZeroPay,direction=Domestic,status=APPROVED} == 1
- After one FAILED payment: payment_requests_total{status=FAILED} incremented and payment_latency_ms has a sample
- pool_identity_breaches_total starts at 0 and increments to 1 when artificially injecting a 0.02 USD pool mismatch
- rate_engine_calculations_total incremented on every rate calculation regardless of outcome
- Scrape endpoint returns valid Prometheus text format (content-type text/plain; version=0.0.4)
**Depends on:** 14.6-T01

### 14.6-T03 — Implement Prometheus metric emission in Hub API service (prefunding and rate-quote paths)  _(40 min)_
**Context:** Prefunding metrics: prefunding_balance_usd{partner_id} is a Gauge that must be updated after every deduction and every top-up (OVERSEAS partners only). GME Remit is LOCAL and has no prefunding gauge. Rate-quote endpoint /v1/rates failures should be surfaced via api_error_rate and api_request_rate. Webhook dispatch increments webhook_delivery_attempts_total{partner_id, status} where status is DELIVERED or FAILED.
**Steps:** Update PrefundingService: after each atomic deduction, set prefunding_balance_usd gauge to new balance; Update PrefundingService: after each top-up recording, set prefunding_balance_usd gauge to new balance; Instrument /v1/rates endpoint with api_request_rate (counter) and api_latency_p50/p95/p99 (histogram); Instrument WebhookDispatcher: increment webhook_delivery_attempts_total after each attempt with final status label; Write integration test: deduct USD 1000 from SendMN (starting balance USD 50000); verify gauge drops to 49000.00
**Deliverable:** PrefundingService and WebhookDispatcher updated with metric instrumentation; integration test file covering gauge accuracy
**Acceptance / logic checks:**
- prefunding_balance_usd{partner_id=SendMN} reflects exact post-deduction balance within 1 second of deduction
- Gauge is not emitted for LOCAL partners (GME Remit must not appear in prefunding_balance_usd samples)
- webhook_delivery_attempts_total{partner_id=SendMN,status=DELIVERED} increments on 2xx webhook response
- webhook_delivery_attempts_total{partner_id=SendMN,status=FAILED} increments after max-retry exhaustion
- Gauge value uses USD scale (2 decimal places); KRW-equivalent is never written to this metric
**Depends on:** 14.6-T01, 14.6-T02

### 14.6-T04 — Implement Prometheus metric emission in Batch/SFTP Worker service  _(45 min)_
**Context:** Batch worker executes JOB-ZP-01 through JOB-ZP-13. Required metrics: batch_job_duration_ms{job_name, status} Histogram, batch_file_delivery_lag_seconds{file_type} Gauge (seconds between internal window deadline and actual transmission), scheme_api_errors_total{scheme=ZeroPay, error_code} Counter, circuit_breaker_state{dependency=ZeroPay_SFTP} Gauge (0=closed,1=open). job_name label values: JOB-ZP-01 through JOB-ZP-13. status label: SUCCESS, FAILED, SKIPPED.
**Steps:** Inject MetricsRegistry into BatchJobRunner (or equivalent); Wrap each job execution: record start time, record batch_job_duration_ms on completion with job_name and status; After each SFTP file upload, compute lag as actual_upload_unix - window_deadline_unix (in seconds); set batch_file_delivery_lag_seconds gauge; Increment scheme_api_errors_total on any ZeroPay SFTP connection error or API error, using ZeroPay error code as label; Toggle circuit_breaker_state gauge when the SFTP circuit breaker changes state (closed=0, open=1); Add unit test: simulate JOB-ZP-01 success in 45s with zero SFTP errors; verify histogram and gauge values
**Deliverable:** BatchJobRunner updated with full metric instrumentation; unit test file BatchJobMetricsTest
**Acceptance / logic checks:**
- JOB-ZP-01 completing in 45s records batch_job_duration_ms{job_name=JOB-ZP-01,status=SUCCESS} with a sample in the 30000-60000ms bucket
- A 3-retry SFTP failure records scheme_api_errors_total{scheme=ZeroPay,error_code=SFTP_CONN_REFUSED} == 3
- circuit_breaker_state{dependency=ZeroPay_SFTP} transitions from 0 to 1 when circuit opens after 3 failures
- batch_file_delivery_lag_seconds{file_type=ZP0011} is positive when job ran after the 02:00 KST window, zero or negative when on time
- SKIPPED jobs (idempotency no-op) record status=SKIPPED in the histogram
**Depends on:** 14.6-T01

### 14.6-T05 — Define structured JSON log schema and implement base log appender  _(40 min)_
**Context:** OPS-13 §7.2 specifies all services emit JSON-structured logs. Required fields on every line: timestamp (ISO-8601 UTC), level (DEBUG/INFO/WARN/ERROR), service (hub-api/batch-worker/admin-api/webhook-dispatcher), env (dev/int/staging/prod), trace_id (UUID), span_id (UUID), partner_id (string, present on payment-related logs), transaction_id (UUID, present on payment-related logs), event (semantic name), message (human-readable). Sensitive fields (collection_amount, prefund_balance, rate components) must NOT appear in production logs; in non-production they are allowed only at INFO level.
**Steps:** Define LogEvent POJO/record with all required fields and optional payment-path fields; Configure Logback (or equivalent) JSON appender: every log line serialises to one JSON object per line; Set env field from SPRING_PROFILES_ACTIVE or equivalent env var at service startup; Implement SensitiveFieldFilter: strips collection_amount, prefund_balance, cost_rate_coll, cost_rate_pay, offer_rate_coll, cross_rate from log output when env==prod; Write unit test: log a payment event in prod mode; assert sensitive fields absent; log same event in dev mode; assert sensitive fields present
**Deliverable:** Logback JSON appender config (logback-spring.xml) plus SensitiveFieldFilter class; unit test LogSchemaTest
**Acceptance / logic checks:**
- Every log line is valid JSON parseable by standard JSON parser
- timestamp field is ISO-8601 UTC (e.g. 2026-06-05T10:00:00.000Z), never epoch millis
- In prod profile: log line for a payment event contains partner_id and transaction_id but NOT collection_amount
- In dev profile: same event contains collection_amount
- service field equals hub-api for Hub API service logs; batch-worker for batch worker logs

### 14.6-T06 — Implement trace context propagation using OpenTelemetry (W3C TraceContext)  _(50 min)_
**Context:** NFR-10 §7.3 and OPS-13 §7.3 require all inbound API requests to generate a trace_id in W3C TraceContext format (traceparent header). The trace_id propagates through all internal service calls, database queries, and batch job steps. The 8-step transaction trail (rate_quote_issued, payment_initiated, prefund_deducted, scheme_request_sent, scheme_response_received, transaction_committed, webhook_dispatched, webhook_delivered/webhook_failed) must link to the trace_id. Tracing data is exported to Jaeger (or compatible OTLP backend).
**Steps:** Add OpenTelemetry Java SDK and OTLP exporter dependencies (io.opentelemetry:opentelemetry-sdk, opentelemetry-exporter-otlp); Configure OtelConfig bean: set service.name, OTLP endpoint from env var OTEL_EXPORTER_OTLP_ENDPOINT, sampler (AlwaysOn for dev/int; ParentBased 10% for staging/prod); Register W3C TraceContext propagator as default propagator; Add server-side filter/interceptor to extract traceparent from inbound HTTP headers; create root span if absent; Inject trace_id and span_id into SLF4J MDC so all log lines include them (see 14.6-T05 schema); Write integration test: send POST /v1/rates; verify response does not include traceparent; query Jaeger API to confirm trace recorded with correct service name
**Deliverable:** OtelConfig.java bean and HTTP trace propagation filter; integration test TraceContextTest confirming trace appears in Jaeger for a complete /v1/rates call
**Acceptance / logic checks:**
- Inbound request with no traceparent header: a new trace_id (32 hex chars) is generated and appears in response MDC
- Inbound request with valid traceparent: same trace_id propagates to downstream span parent
- Logback MDC contains trace_id and span_id matching the active OpenTelemetry span on the same thread
- OTLP exporter does not block request path; exporter failures are logged at WARN and do not throw to caller
- service.name attribute on spans equals the value set in OTEL_SERVICE_NAME env var
**Depends on:** 14.6-T05

### 14.6-T07 — Instrument 8-step transaction trail spans in OpenTelemetry  _(55 min)_
**Context:** OPS-13 §7.3 defines 8 trace steps per transaction: (1) rate_quote_issued, (2) payment_initiated, (3) prefund_deducted (OVERSEAS only), (4) scheme_request_sent, (5) scheme_response_received, (6) transaction_committed, (7) webhook_dispatched, (8) webhook_delivered or webhook_failed. Each step must create a child span under the root trace, tagged with transaction_id and partner_id. These spans are queryable in the Admin System (PRD-07 §6.5) and in Jaeger. Trace context from 14.6-T06 must be active before this ticket begins.
**Steps:** In RateQuoteService: create span rate_quote_issued with attributes transaction_id, partner_id, rule_id, ttl_seconds; In PaymentOrchestrator: create spans for payment_initiated and transaction_committed with terminal status attribute (APPROVED/FAILED/UNCERTAIN); In PrefundingService: create span prefund_deducted with attribute deduction_amount_usd (omitted in prod — see 14.6-T05 sensitivity rules); In SchemeAdapter: create spans scheme_request_sent (with ZeroPay endpoint URL) and scheme_response_received (with HTTP status code); In WebhookDispatcher: create spans webhook_dispatched and webhook_delivered/webhook_failed with attempt_number attribute; Write integration test: process a full domestic payment; retrieve trace from Jaeger; assert all 7 spans (step 3 absent for LOCAL) present in correct parent-child order
**Deliverable:** Span instrumentation across RateQuoteService, PaymentOrchestrator, PrefundingService, SchemeAdapter, WebhookDispatcher; integration test TransactionTrailSpanTest
**Acceptance / logic checks:**
- For an OVERSEAS payment (e.g. SendMN), all 8 spans appear in Jaeger under one root trace_id
- For a LOCAL payment (GME Remit), 7 spans appear (prefund_deducted is absent)
- Each span carries transaction_id attribute matching the transaction record in the DB
- Span order in Jaeger reflects chronological sequence (rate_quote_issued start < payment_initiated start < ... < webhook_delivered start)
- transaction_committed span has a status attribute of APPROVED, FAILED, or UNCERTAIN matching the DB transaction status
**Depends on:** 14.6-T06

### 14.6-T08 — Add structured payment-path log events with semantic event names  _(40 min)_
**Context:** OPS-13 §7.2 requires structured logs with an event field (semantic event name). The 8 transaction steps must each emit a log line at INFO level with event names matching the trace spans: rate_quote_issued, payment_initiated, prefund_deducted, scheme_request_sent, scheme_response_received, transaction_committed, webhook_dispatched, webhook_delivered, webhook_failed. Non-payment INFO events: batch_job_started, batch_job_completed, batch_job_failed, sftp_connected, sftp_disconnected. WARN events: rate_quote_expired (client used expired quote), prefunding_low_balance_warning. ERROR events: pool_identity_breach, scheme_unavailable, db_query_error.
**Steps:** Define EventNames constants class with all semantic event name strings; In each service (RateQuoteService, PaymentOrchestrator, PrefundingService, SchemeAdapter, WebhookDispatcher, BatchJobRunner): replace freeform log.info messages at key lifecycle points with structured log calls that include the event field; For each event, include the minimum required context fields (partner_id, transaction_id where applicable); Add WARN log with event=rate_quote_expired when system detects partner submitting a payment against an expired validUntil (current_time > quote_issued_at + ttl); Write unit test: invoke payment flow; capture log output; assert event field values appear in correct sequence
**Deliverable:** EventNames.java constants class; updated log calls across 5 service classes; unit test EventNamesTest asserting event field sequence for a domestic payment
**Acceptance / logic checks:**
- Log line for event=transaction_committed contains fields: timestamp, level=INFO, service, trace_id, partner_id, transaction_id, event=transaction_committed, message
- Log line for event=pool_identity_breach has level=ERROR
- No log line in the payment path is missing the event field
- event=rate_quote_expired is emitted at WARN when validUntil < now (e.g. quote TTL=300s, payment submitted 301s after quote)
- batch_job_started and batch_job_completed events include job_name and run_date fields
**Depends on:** 14.6-T05, 14.6-T07

### 14.6-T09 — Configure Prometheus scrape config and Grafana datasource provisioning  _(45 min)_
**Context:** Prometheus must scrape metrics from Hub API pods, Batch Worker, Admin API, and Webhook Dispatcher. Grafana must be pre-provisioned with Prometheus as a datasource and Jaeger as a tracing datasource. OPS-13 §7.4 requires 6 dashboards available before Phase 3 go-live. Infrastructure is Kubernetes (kubectl/Helm assumed). Scrape interval 15s for API services, 60s for batch worker (lower frequency needed). Prometheus and Grafana run in the gmepay-monitoring namespace.
**Steps:** Write prometheus.yml (or Helm values override) defining scrape_configs for hub-api (port 8080 /actuator/prometheus, 15s), batch-worker (port 8081, 60s), admin-api (port 8082, 15s), webhook-dispatcher (port 8083, 15s); Add Kubernetes service monitor CRDs (if using Prometheus Operator) for each service, with label gmepay_scrape: true; Write Grafana datasource provisioning YAML: Prometheus datasource named GMEPay-Prometheus (url from env), Jaeger datasource named GMEPay-Jaeger (url from env); Set Grafana datasources as default per type (Prometheus default for metrics, Jaeger default for tracing); Validate: deploy to dev environment; confirm each scrape target shows State=UP in Prometheus /targets UI
**Deliverable:** prometheus-scrape-config.yaml or prometheus-operator ServiceMonitor manifests for all 4 services; grafana/provisioning/datasources/gmepay.yaml; README note in ops/monitoring/ on how to verify scrape targets
**Acceptance / logic checks:**
- prometheus /targets shows hub-api, batch-worker, admin-api, webhook-dispatcher all in State=UP in dev environment
- Grafana datasource list shows GMEPay-Prometheus (type prometheus) and GMEPay-Jaeger (type jaeger) with green status
- Scrape interval for hub-api is 15s (verified in Prometheus config or ServiceMonitor spec)
- Scrape interval for batch-worker is 60s
- payment_requests_total metric is queryable in Grafana Explore against GMEPay-Prometheus datasource
**Depends on:** 14.6-T02, 14.6-T03, 14.6-T04

### 14.6-T10 — Build Operations Overview Grafana dashboard (OPS-13 §7.4)  _(55 min)_
**Context:** OPS-13 §7.4 defines Operations Overview dashboard for GME Ops: panels = API success rate (per partner), active transactions count, prefunding levels per partner, batch job status. SLO targets: api_error_rate < 1% (5xx over 5 min), p95 latency < 500ms. Alert thresholds: API error rate > 1% = P1, API p95 > 500ms = P2. Time range default: last 1 hour. Use Prometheus datasource GMEPay-Prometheus from 14.6-T09. Dashboard must be provisioned as code (JSON model in grafana/dashboards/).
**Steps:** Create Grafana dashboard JSON with 4 row panels: (1) API Success Rate gauge per partner using rate(payment_requests_total{status=APPROVED}[5m])/rate(payment_requests_total[5m])*100, (2) Active Transactions stat panel using count of transactions in non-terminal states from a recording rule, (3) Prefunding Balance per Partner using prefunding_balance_usd gauge with threshold lines at 10000 and 2000 USD, (4) Batch Job Status table using last value of batch_job_duration_ms grouped by job_name and status; Add threshold colouring: success rate < 98% = red, < 99% = yellow, >= 99% = green; prefunding < 2000 = red, < 10000 = yellow; Set dashboard variable $partner_id as multi-value dropdown populated from label_values(payment_requests_total, partner_id); Add dashboard UID ops-overview and tag gmepay; Place JSON file at grafana/dashboards/ops-overview.json and reference in Grafana dashboard provisioning config
**Deliverable:** grafana/dashboards/ops-overview.json provisioned Grafana dashboard; screenshot or smoke-test confirming it loads without errors in dev Grafana
**Acceptance / logic checks:**
- Dashboard loads in Grafana without panel errors when Prometheus has at least 5 minutes of scraped data
- Prefunding panel shows SendMN balance in red when balance is set to USD 1500 (below 2000 threshold)
- API success rate panel shows red for a partner when 5xx rate exceeds 1% over 5-minute window (simulate by injecting errors)
- $partner_id variable dropdown is populated with at least GME_REMIT and SendMN in the dev environment
- Dashboard JSON validates against Grafana dashboard schema (no unknown fields, valid panel types)
**Depends on:** 14.6-T09

### 14.6-T11 — Build API SLO Grafana dashboard (OPS-13 §7.4)  _(50 min)_
**Context:** OPS-13 §7.4 API SLO dashboard is for Engineering audience: panels = p95 latency vs SLO line (500ms), error rate by endpoint, partner breakdown. Source metrics: api_latency_p50/p95/p99 (histogram_quantile over payment_latency_ms), api_error_rate (rate of 5xx per endpoint over 5-min window). Endpoints to break out: POST /v1/payments, POST /v1/payments/cpm/generate, GET /v1/rates, GET /v1/payments/{id}. Alert annotations: mark timestamps when p95 > 500ms alert fired.
**Steps:** Create Grafana dashboard JSON with panels: (1) p95 latency time-series using histogram_quantile(0.95, rate(payment_latency_ms_bucket[5m])) with a constant reference line at 500ms, (2) p50/p99 latency time-series for context, (3) 5xx error rate by endpoint bar chart using rate(api_error_rate{status=~'5..'}[5m]) grouped by endpoint label, (4) Requests per second by partner time-series; Add alert annotation query to mark p95 breaches > 500ms as vertical lines on the latency panel; Set dashboard variable $endpoint as dropdown; default All; Set dashboard UID api-slo; tag gmepay, slo; Add to grafana/dashboards/api-slo.json and grafana provisioning config
**Deliverable:** grafana/dashboards/api-slo.json; provisioned and verified loading in dev Grafana
**Acceptance / logic checks:**
- p95 panel shows a horizontal reference line at 500ms
- Simulating 200 requests with 600ms latency causes p95 panel to show value above the 500ms threshold line
- Error rate panel distinguishes between endpoints (POST /v1/payments shows separately from GET /v1/rates)
- $endpoint variable set to POST /v1/payments filters all panels to that endpoint only
- Dashboard renders without NaN panels when no traffic has occurred in the selected time range (graceful empty state)
**Depends on:** 14.6-T09

### 14.6-T12 — Build Batch Health Grafana dashboard (OPS-13 §7.4)  _(55 min)_
**Context:** OPS-13 §7.4 Batch Health dashboard audience: Ops/Engineering. Key panels: job timeline (last run status per job), last run timestamp per job, file receipt confirmation by window, SFTP connectivity state. Metrics: batch_job_duration_ms{job_name,status}, batch_file_delivery_lag_seconds{file_type}, circuit_breaker_state{dependency=ZeroPay_SFTP}, scheme_api_errors_total{scheme=ZeroPay}. Jobs to display: JOB-ZP-01 through JOB-ZP-13. Critical jobs: JOB-ZP-01,02,03,04,05,06 shown in red if status=FAILED.
**Steps:** Create Grafana dashboard JSON: (1) Job Status table panel with one row per JOB-ZP-xx showing last status (SUCCESS/FAILED/SKIPPED), last run duration, and last run timestamp using last_over_time(batch_job_duration_ms_count{status!=''}[24h]), (2) Batch File Delivery Lag bar chart using batch_file_delivery_lag_seconds per file_type, (3) SFTP Circuit Breaker state panel using circuit_breaker_state{dependency=ZeroPay_SFTP} (green=0, red=1), (4) SFTP Error Rate panel using rate(scheme_api_errors_total{scheme=ZeroPay}[15m]); Apply threshold colouring: FAILED status rows in Job Status table coloured red; lag > 0 seconds coloured yellow; lag > 900 seconds (15 min) coloured red; Set dashboard UID batch-health; tag gmepay, batch; Default time range: last 24 hours
**Deliverable:** grafana/dashboards/batch-health.json; provisioned in dev Grafana
**Acceptance / logic checks:**
- Job Status table shows all 13 JOB-ZP-xx rows even when some jobs have not run yet (shows No Data or N/A, not an error)
- JOB-ZP-01 row turns red when last recorded status=FAILED
- SFTP Circuit Breaker panel shows red when circuit_breaker_state{dependency=ZeroPay_SFTP} == 1
- Delivery lag panel shows positive value for ZP0011 when batch_file_delivery_lag_seconds{file_type=ZP0011} is set to 600 (10 min late)
- Dashboard loads in under 3 seconds with 24h of sample data in Prometheus
**Depends on:** 14.6-T09

### 14.6-T13 — Build Prefunding Monitor Grafana dashboard (OPS-13 §7.4)  _(50 min)_
**Context:** OPS-13 §7.4 Prefunding Monitor dashboard audience: Ops/Finance. Panels: USD balance per partner vs threshold (default USD 10,000 low / USD 2,000 critical), deduction rate (24h rolling sum), top-up history. Metrics: prefunding_balance_usd{partner_id} Gauge, webhook_delivery_attempts_total (for context), payment_requests_total{direction!=Domestic} (proxy for deduction events). Alert thresholds per partner are configurable but default to USD 10,000 (low) and USD 2,000 (critical) per OPS-13 §7.5.2. Only OVERSEAS partners (SendMN, T-Bank) appear; LOCAL partners (GME Remit) have no prefunding.
**Steps:** Create Grafana dashboard JSON: (1) Balance per partner stat panel (big number) using last(prefunding_balance_usd) per partner_id with thresholds 2000=red, 10000=yellow, above=green, (2) Balance over time line chart per partner (last 7 days), (3) 24h Deduction Rate panel using increase(payment_requests_total{status=APPROVED,direction!=Domestic}[24h]) as proxy for deduction count, (4) Stat panel showing Lowest Balance Across All Partners for quick P1 scan; Add dashboard variable $partner_id; filter all panels; Set dashboard UID prefunding-monitor; tag gmepay, finance; Add reference lines to time-series at y=10000 (yellow) and y=2000 (red)
**Deliverable:** grafana/dashboards/prefunding-monitor.json; provisioned in dev Grafana
**Acceptance / logic checks:**
- SendMN balance panel shows red (critical) when prefunding_balance_usd{partner_id=SendMN} is set to 1500
- GME Remit does not appear in any panel (no prefunding_balance_usd metric emitted for LOCAL partner)
- Balance over time chart correctly tracks a step-down deduction of USD 5000 followed by a top-up of USD 20000
- Lowest Balance stat panel shows the minimum balance across all OVERSEAS partners
- Dashboard is visible to Finance-role Grafana users (verify Grafana folder permissions are set to gmepay-finance team)
**Depends on:** 14.6-T09

### 14.6-T14 — Build Revenue Reporting Grafana dashboard (OPS-13 §7.4)  _(55 min)_
**Context:** OPS-13 §7.4 Revenue dashboard audience: Finance/Ops. Required panels: collection margin (collection_margin_usd), payout margin (payout_margin_usd), service fees (service_charge in KRW or USD equivalent), scheme fee share — all broken down by partner and by period (daily/weekly). Rate-engine fields come from the transaction DB not Prometheus. Approach: expose revenue summary via a Prometheus pushgateway job or a custom /actuator/metrics endpoint updated by a nightly aggregation job. Metric names: revenue_collection_margin_usd_total{partner_id}, revenue_service_charge_krw_total{partner_id}, revenue_payout_margin_usd_total{partner_id}.
**Steps:** Define 3 new Prometheus counter-equivalent metrics (pushed via Pushgateway): revenue_collection_margin_usd_total, revenue_service_charge_krw_total, revenue_payout_margin_usd_total with label partner_id; Implement DailyRevenueAggregationJob that runs at 00:05 KST: queries DB for previous day committed transactions, sums collection_margin_usd and payout_margin_usd per partner_id, pushes to Prometheus Pushgateway; Create Grafana dashboard panels: (1) Daily Revenue Bar Chart (increase per day), (2) Revenue by Partner table (7-day total), (3) Service Fee total in KRW by partner; Set dashboard UID revenue-reporting; tag gmepay, finance
**Deliverable:** DailyRevenueAggregationJob.java + Pushgateway push logic; grafana/dashboards/revenue-reporting.json
**Acceptance / logic checks:**
- DailyRevenueAggregationJob for date 2026-06-04 with SendMN having 100 transactions each with collection_margin_usd=0.50 pushes revenue_collection_margin_usd_total{partner_id=SendMN} delta of 50.00
- Pushgateway job label includes job=gmepay-revenue and date=2026-06-04 for idempotent re-push
- Revenue bar chart shows correct daily totals when 5 days of pushed data are present
- Service charge total for GME Remit (KRW 500 per txn, 200 txns) displays 100000 KRW
- Dashboard shows zero/empty state gracefully if no transactions occurred on a given day
**Depends on:** 14.6-T09

### 14.6-T15 — Configure Prometheus alerting rules for API and SLO alerts (OPS-13 §7.5.1)  _(45 min)_
**Context:** OPS-13 §7.5.1 defines 5 API/SLO alerts to be configured as Prometheus alerting rules (PrometheusRule CRD or rules file): (1) API p95 latency breach: histogram_quantile(0.95) > 500ms for 5min on any endpoint, severity=P2; (2) API error rate elevated: rate(5xx over 5min) > 1%, severity=P1; (3) API error rate warning: rate(5xx over 10min) > 0.5%, severity=P2; (4) Rate quote failure spike: rate(/v1/rates errors over 2min) > 5%, severity=P1; (5) Payment endpoint unavailable: health check fails > 60s, severity=P1. Alerts route to Alertmanager which forwards P1 to PagerDuty+Slack, P2 to Slack.
**Steps:** Create prometheus-rules/api-slo.yaml with 5 alerting rules using exact PromQL expressions and for durations from OPS-13; Define labels: severity: p1 or p2, team: ops, dashboard: api-slo; Define annotations: summary (one line) and runbook_url pointing to ops/runbooks/api-slo.md; Validate PromQL syntax using promtool check rules api-slo.yaml; Create Alertmanager routing config: route P1 alerts to pagerduty+ops-slack receiver, P2 to ops-slack receiver; inhibit P2 latency-warning if P1 latency-breach is already firing
**Deliverable:** prometheus-rules/api-slo.yaml with 5 validated alerting rules; alertmanager/config.yaml routing section for P1/P2; promtool check output showing 0 errors
**Acceptance / logic checks:**
- promtool check rules api-slo.yaml returns exit code 0
- API error rate alert fires (pending then firing) when injecting 5xx responses at 2% rate for 5+ minutes in a test Prometheus environment
- API p95 latency alert fires when histogram_quantile(0.95, rate(payment_latency_ms_bucket[5m])) exceeds 0.5 (500ms) for 5 continuous minutes
- Inhibit rule prevents P2 api_error_rate_warning from appearing in Alertmanager when P1 api_error_rate_elevated is firing
- All 5 rules have non-empty summary and runbook_url annotations
**Depends on:** 14.6-T02

### 14.6-T16 — Configure Prometheus alerting rules for prefunding alerts (OPS-13 §7.5.2)  _(40 min)_
**Context:** OPS-13 §7.5.2 defines 4 prefunding alerts: (1) Low prefunding balance: any OVERSEAS partner prefunding_balance_usd < 10000, severity=P2, action=email partner + notify Ops; (2) Critical prefunding balance: < 2000, severity=P1, action=suspend partner payments; (3) Prefunding balance zero: = 0, severity=P1, action=suspend all that partner's payments; (4) Prefunding deduction anomaly: single deduction > USD 50000 (not directly in Prometheus — requires a custom metric deduction_amount_usd gauge set per deduction in PrefundingService). Thresholds are configurable per partner but rules use default values.
**Steps:** Create prometheus-rules/prefunding.yaml with 4 alerting rules; For alerts 1-3: use prefunding_balance_usd{partner_id=~'.+'} with thresholds 10000, 2000, 0; For alert 4 (deduction anomaly): require PrefundingService to emit deduction_size_usd gauge (set to deduction amount, then reset to 0 after 60s); alert when deduction_size_usd > 50000; Add inhibit rule: suppress low-balance P2 alert when critical-balance P1 is already active for same partner_id; Validate with promtool check rules prefunding.yaml; add unit test simulating gauge transitions
**Deliverable:** prometheus-rules/prefunding.yaml with 4 rules; PrefundingService updated to emit deduction_size_usd gauge; promtool check clean
**Acceptance / logic checks:**
- Low balance alert fires when prefunding_balance_usd{partner_id=SendMN} is set to 9999
- Critical balance alert fires when balance set to 1999; low-balance alert is suppressed by inhibit rule
- Zero balance alert fires within 15s of prefunding_balance_usd{partner_id=SendMN} reaching 0
- Deduction anomaly alert fires when deduction_size_usd{partner_id=SendMN} is set to 50001 for 10 seconds
- promtool check rules prefunding.yaml returns exit code 0
**Depends on:** 14.6-T03, 14.6-T15

### 14.6-T17 — Configure Prometheus alerting rules for batch window breach and settlement alerts (OPS-13 §7.5.3, §7.5.5)  _(45 min)_
**Context:** OPS-13 §7.5.3 batch window alerts: ZP0011/ZP0021 missed (not submitted by 01:45 KST) = P1; ZP0012/ZP0022 not received by 05:30 KST = P1; ZP0061 missed by 05:15 KST = P1; ZP0062 not received by 10:30 KST = P1; ZP0063/ZP0065/ZP0066 missed +15min = P2; merchant sync not received by 08:00 KST = P3; any job in FAILED state = P1. Settlement/financial alerts (§7.5.5): pool_identity_breaches_total > 0 = P1; settlement reconciliation mismatch = P1; revenue ledger mismatch = P1. Batch window alerts use batch_file_delivery_lag_seconds and a job-state Gauge (job_state{job_name} = 0=idle,1=running,2=success,3=failed).
**Steps:** Create prometheus-rules/batch-windows.yaml: for each critical job, alert when batch_file_delivery_lag_seconds{file_type=ZP0011} > 0 AND time() > expected_deadline (encode deadline offset as a recording rule or constant); Create alert for any FAILED job: job_state{job_name=~'.+'} == 3, severity=P1; Create prometheus-rules/settlement.yaml: pool_identity_breaches_total > 0, severity=P1 with no for duration (fires immediately); Add alert for revenue_ledger_mismatch_total (a counter incremented by daily reconciliation job when mismatch detected) > 0; Validate all rules with promtool; add runbook_url annotations referencing OPS-13 §8.5 and §8.10
**Deliverable:** prometheus-rules/batch-windows.yaml and prometheus-rules/settlement.yaml; promtool check clean on both
**Acceptance / logic checks:**
- pool_identity_breaches_total > 0 alert has no for clause (fires immediately on first breach)
- job_state{job_name=JOB-ZP-01} == 3 fires P1 alert within default evaluation interval (15s)
- batch window breach alerts have severity=p1 for JOB-ZP-01,02,03,04,05,06 and severity=p2 for JOB-ZP-07,08,09
- All alert rules have runbook_url annotation referencing OPS-13 sections
- promtool check rules batch-windows.yaml and settlement.yaml both return exit code 0
**Depends on:** 14.6-T04, 14.6-T15

### 14.6-T18 — Configure Prometheus alerting rules for scheme connectivity and webhook alerts (OPS-13 §7.5.4, §7.5.6)  _(45 min)_
**Context:** OPS-13 §7.5.4 scheme/connectivity: SFTP connection failure (ZeroPay SFTP unreachable > 3 retries) = P1 alert using circuit_breaker_state{dependency=ZeroPay_SFTP} == 1; Scheme API timeout (ZeroPay latency > 5s p95 over 5min) = P2; Partner B quote API down (error rate > 1% over 5min) = P2; Partner B quote deviation high (divergence > 1% on > 5% of quotes) = P2. OPS-13 §7.5.6 webhook: delivery failure after max retries = P2; queue depth > 500 = P2; dispatcher p95 > 30s = P3. New metric needed: partner_b_quote_deviation_ratio Gauge set by rate engine when PARTNER_B_QUOTE_DEVIATION error occurs.
**Steps:** Create prometheus-rules/scheme-connectivity.yaml: circuit_breaker_state{dependency=ZeroPay_SFTP} == 1 for 0s = P1; histogram_quantile(0.95, rate(scheme_api_latency_ms_bucket[5m])) > 5000 for 5m = P2; Add partner_b_quote_error_total{partner_id, error_type} counter in rate engine; add alert when rate > 1% over 5min; Create prometheus-rules/webhooks.yaml: webhook_delivery_attempts_total with status=FAILED > threshold; webhook queue depth (exposed via message_queue_depth gauge) > 500 for 5m = P2; Validate all rules with promtool; Cross-check: ensure circuit_breaker_state metric is already emitted by batch worker (14.6-T04); add scheme_api_latency_ms histogram to SchemeAdapter if not already present
**Deliverable:** prometheus-rules/scheme-connectivity.yaml and prometheus-rules/webhooks.yaml; SchemeAdapter updated with scheme_api_latency_ms histogram; promtool check clean
**Acceptance / logic checks:**
- circuit_breaker_state{dependency=ZeroPay_SFTP} == 1 fires P1 alert with no for duration (immediate)
- partner_b_quote_error_total alert fires when 2 out of 10 quote calls return PARTNER_B_QUOTE_DEVIATION (20% > 1% threshold)
- Webhook queue depth P2 alert fires when message_queue_depth{queue=webhooks} is set to 501 for 5 minutes
- Webhook P3 latency alert has severity=p3 label
- promtool check rules on both files returns exit code 0
**Depends on:** 14.6-T04, 14.6-T15

### 14.6-T19 — Implement Alertmanager routing config and PagerDuty/Slack notification templates  _(40 min)_
**Context:** OPS-13 §7.5 requires PagerDuty-compatible severity levels. Routing: P1 -> PagerDuty + Ops Slack channel #gmepay-alerts-p1; P2 -> #gmepay-alerts-p2 Slack only; P3 -> #gmepay-alerts-p3 Slack (business hours); P4 -> no route. Alertmanager config must group alerts by alertname + partner_id to avoid notification storms. Repeat interval: P1 = 15min, P2 = 60min. Templates must include: alert name, severity, condition summary, link to runbook_url, link to relevant Grafana dashboard UID.
**Steps:** Write alertmanager/config.yaml: define receivers pagerduty-p1-slack, slack-p2, slack-p3; define route tree matching on severity label; Configure PagerDuty receiver using integration key from env var PAGERDUTY_INTEGRATION_KEY; Configure Slack receivers using webhook URL from env var SLACK_WEBHOOK_OPS_P1 / P2 / P3; Write alertmanager/templates/gmepay.tmpl: define notification body including alert name, severity, labels summary, runbook_url value, grafana URL (constructed from GRAFANA_BASE_URL env var + dashboard UID from annotations); Set group_by: [alertname, partner_id]; group_wait: 30s; group_interval: 5min; repeat_interval P1=15min P2=60min; Test with amtool check-config alertmanager/config.yaml
**Deliverable:** alertmanager/config.yaml (full routing tree); alertmanager/templates/gmepay.tmpl (notification template); amtool check-config passing
**Acceptance / logic checks:**
- amtool check-config alertmanager/config.yaml returns exit code 0
- P1 alert routes to both pagerduty and slack-p1 receivers (verify with amtool test-routes)
- P3 alert routes only to slack-p3 receiver and does not page PagerDuty
- Notification template renders grafana dashboard link when annotations.dashboard_uid is set (e.g. ops-overview maps to GRAFANA_BASE_URL/d/ops-overview)
- group_by includes partner_id so SendMN and GME_REMIT alerts are separate notification groups
**Depends on:** 14.6-T15, 14.6-T16, 14.6-T17, 14.6-T18

### 14.6-T20 — Implement /health and /ready endpoints with deep dependency checks  _(40 min)_
**Context:** OPS-13 §8.1 smoke test checks /health after each deployment. NFR-10 requires payment endpoint unavailable alert when health check fails > 60s. Hub API must expose: GET /health (liveness, checks JVM up, returns 200/503), GET /ready (readiness, checks DB connection pool, Redis connectivity, Prometheus registry). Admin API and Webhook Dispatcher must expose same pattern. Health check failure must fire the payment_endpoint_unavailable P1 alert (14.6-T15). Response schema: {status: UP|DOWN, checks: [{name, status, details}]}.
**Steps:** Implement HealthController with GET /health (liveness: always 200 if JVM alive) and GET /ready (readiness: queries SELECT 1 from DB, pings Redis, checks circuit breaker state); Return 200 with {status:UP} when all checks pass; 503 with {status:DOWN, checks:[...]} when any check fails; Include check names: database (SELECT 1), redis (PING), prometheus_registry (metric count > 0); Add Kubernetes liveness probe on /health and readiness probe on /ready in deployment YAML (failureThreshold=3, periodSeconds=20); Write integration test HealthEndpointTest: shut down test DB; verify /ready returns 503 with database check status=DOWN
**Deliverable:** HealthController.java in hub-api service; corresponding deployment.yaml liveness/readiness probe config; integration test HealthEndpointTest
**Acceptance / logic checks:**
- GET /health returns 200 {status:UP} when all dependencies are healthy
- GET /ready returns 503 {status:DOWN, checks:[{name:database, status:DOWN, details:connection refused}]} when DB is unreachable
- GET /ready returns 200 within 200ms under normal conditions (DB query SELECT 1 must complete in < 150ms)
- Kubernetes readiness probe config: path=/ready, port=8080, failureThreshold=3, periodSeconds=20
- Liveness probe does not check external dependencies (avoids false pod restarts on transient DB issues)
**Depends on:** 14.6-T02

### 14.6-T21 — Implement OTEL trace export to Jaeger and verify end-to-end trace for /v1/payments  _(55 min)_
**Context:** 14.6-T06 set up OpenTelemetry SDK and W3C propagation. This ticket wires the OTLP exporter to a Jaeger backend and verifies a complete payment trace. Jaeger endpoint from env var OTEL_EXPORTER_OTLP_ENDPOINT (default http://jaeger:4317 in dev). Sampler: AlwaysOn in dev/int, ParentBased(root=TraceIdRatioBased(0.1)) in staging/prod. The 8 transaction trail spans (14.6-T07) must all appear in Jaeger within 5s of transaction completion.
**Steps:** Set OTEL_EXPORTER_OTLP_ENDPOINT in dev docker-compose/helm values to point to local Jaeger instance; Add OTEL_SERVICE_NAME env var per service (hub-api, batch-worker, admin-api, webhook-dispatcher); Configure sampler via OTEL_TRACES_SAMPLER env var: always_on for dev/int; parentbased_traceidratio for staging/prod with OTEL_TRACES_SAMPLER_ARG=0.1; Write integration test OtelJaegerExportTest: POST /v1/payments with a domestic GME Remit payment; poll Jaeger HTTP API (GET /api/traces?service=hub-api) until trace with all 7 expected spans appears or 10s timeout; Assert span names match exactly: rate_quote_issued, payment_initiated, scheme_request_sent, scheme_response_received, transaction_committed, webhook_dispatched, webhook_delivered
**Deliverable:** docker-compose.dev.yaml updated with Jaeger service; OTEL env var config per service in helm/values-dev.yaml; integration test OtelJaegerExportTest
**Acceptance / logic checks:**
- OtelJaegerExportTest passes: trace with 7 spans for domestic payment appears in Jaeger within 10s
- All 7 span names exactly match the canonical list from OPS-13 §7.3 (no typos)
- Span attributes include transaction_id (UUID format) and partner_id
- AlwaysOn sampler is active in dev: 100 requests to POST /v1/payments produce 100 traces in Jaeger
- In staging with 10% sampler: 1000 requests produce approximately 100 traces (90-110 acceptable range)
**Depends on:** 14.6-T07

### 14.6-T22 — Unit tests: verify Prometheus metric correctness for payment success and failure paths  _(45 min)_
**Context:** Test ticket. Logic under test: metric emission in PaymentOrchestrator (14.6-T02, 14.6-T03). Covers APPROVED, FAILED, UNCERTAIN outcomes for both LOCAL (GME Remit, Domestic, same-currency) and OVERSEAS (SendMN, cross-border). Uses Prometheus TestRegistry (io.micrometer.core.instrument.simple.SimpleMeterRegistry or Prometheus CollectorRegistry in test mode) to read metric values without a running Prometheus instance.
**Steps:** Set up PaymentOrchestratorTest with SimpleMeterRegistry injected; Test case 1 (APPROVED domestic): trigger domestic GME Remit payment; assert payment_requests_total{partner_id=GME_REMIT,status=APPROVED}==1, payment_latency_ms count==1, rate_engine_calculations_total{rule_id=GME_REMIT_ZP_DOMESTIC,direction=Domestic}==1; Test case 2 (FAILED OVERSEAS insufficient prefunding): trigger SendMN payment with balance=0; assert payment_requests_total{partner_id=SendMN,status=REJECTED}==1, pool_identity_breaches_total==0; Test case 3 (UNCERTAIN): simulate ZeroPay timeout; assert payment_requests_total{status=UNCERTAIN}==1; Test case 4 (pool identity breach): inject collection_usd=100, collection_margin_usd=3, payout_margin_usd=3, payout_usd_cost=94.02 (breach by 0.02 > 0.01 tolerance); assert pool_identity_breaches_total==1
**Deliverable:** PaymentOrchestratorMetricsTest.java with 4 test cases; all 4 tests pass
**Acceptance / logic checks:**
- Test case 1: payment_requests_total{status=APPROVED} == 1 and rate_engine_calculations_total incremented
- Test case 2: REJECTED (not FAILED) label used when payment blocked before reaching scheme; pool_identity_breaches_total remains 0
- Test case 3: UNCERTAIN label used for timeout outcome
- Test case 4: pool_identity_breaches_total increments to 1; payment is NOT committed when breach detected
- All 4 tests pass with no real database or Prometheus server required (in-memory metrics registry)
**Depends on:** 14.6-T02, 14.6-T03

### 14.6-T23 — Unit tests: verify structured log schema completeness and sensitive field filtering  _(40 min)_
**Context:** Test ticket. Logic under test: log schema (14.6-T05) and SensitiveFieldFilter. Tests use Logback CapturingAppender or equivalent to capture log output in-memory. Tests verify: all required fields present, sensitive fields absent in prod, sensitive fields present in non-prod, payment-path logs include partner_id and transaction_id.
**Steps:** Create LogSchemaTest with a CapturingAppender registered on the root logger; Test case 1 (required fields): trigger any INFO log; parse JSON; assert presence of timestamp, level, service, env, trace_id, span_id, event, message; Test case 2 (sensitive field filtering prod): set env=prod; log a payment event including collection_amount=1000000, prefund_balance=50000; assert output JSON does NOT contain keys collection_amount or prefund_balance; Test case 3 (sensitive fields allowed non-prod): set env=dev; log same event; assert collection_amount IS present; Test case 4 (payment-path fields): log event=transaction_committed with partner_id=GME_REMIT, transaction_id=some-uuid; assert both fields present in JSON; Test case 5 (invalid JSON check): parse every captured log line with a strict JSON parser; assert no parse errors
**Deliverable:** LogSchemaTest.java with 5 test cases; all pass
**Acceptance / logic checks:**
- Test case 1 passes: all 8 required fields present on every log line
- Test case 2 passes: collection_amount not present in prod-profile log JSON
- Test case 3 passes: collection_amount present in dev-profile log JSON at INFO level
- Test case 4 passes: partner_id=GME_REMIT and transaction_id=some-uuid both serialised in JSON
- Test case 5 passes: no JSON parse errors across 50 test log emissions
**Depends on:** 14.6-T05

### 14.6-T24 — Unit tests: verify alert rule PromQL expressions fire correctly using promtool unit tests  _(50 min)_
**Context:** Test ticket. Logic under test: Prometheus alerting rules in api-slo.yaml, prefunding.yaml, batch-windows.yaml, settlement.yaml (14.6-T15 through 14.6-T17). Uses promtool test rules (YAML-based unit test files) to assert firing/not-firing states without a live Prometheus instance. Tests must cover: boundary conditions (e.g. 0.999% error rate = no alert; 1.001% = alert), inhibition rules, and immediate-fire rules.
**Steps:** Write test/prometheus/api-slo.test.yaml: series for api_error_rate at 0.5%, 0.9%, 1.0%, 1.1%; assert api_error_rate_warning fires at 0.5% (P2 only), api_error_rate_elevated fires at 1.1% (P1), neither fires at 0.0%; Write test/prometheus/prefunding.test.yaml: series for prefunding_balance_usd{partner_id=SendMN} at values 15000, 10001, 9999, 2001, 1999, 0; assert each alert fires at the correct threshold boundary; Write test/prometheus/settlement.test.yaml: pool_identity_breaches_total increments to 1 at t=1m; assert pool_identity_breach alert fires immediately at t=1m (no for clause); Write test/prometheus/batch.test.yaml: job_state{job_name=JOB-ZP-01} transitions to 3 (FAILED); assert batch_job_failed P1 alert fires; Run promtool test rules on all 4 test files; assert 0 failures
**Deliverable:** test/prometheus/*.test.yaml files (4 files); promtool test rules output showing 0 failures
**Acceptance / logic checks:**
- api_error_rate_warning fires at 0.6% error rate sustained 10 minutes; does NOT fire at 0.4%
- prefunding low-balance alert fires at 9999 USD; does NOT fire at 10001 USD
- pool_identity_breach alert fires at evaluation interval t=15s after counter increments to 1 (no for duration)
- Inhibit rule for prefunding: when critical-balance (< 2000) fires for SendMN, low-balance (< 10000) alert for SendMN is suppressed
- promtool test rules returns exit 0 for all 4 test files
**Depends on:** 14.6-T15, 14.6-T16, 14.6-T17

### 14.6-T25 — Document observability runbook: alert response procedures and dashboard guide  _(45 min)_
**Context:** OPS-13 §7 and §8 require an operational runbook covering alert response and dashboard usage. The runbook is a developer-maintained markdown file in the repository under ops/runbooks/observability.md. It must be self-contained enough for an on-call engineer who has never worked on GMEPay+ to take action within 15 minutes of a P1 alert. Links to Grafana dashboards must use dashboard UIDs defined in this work-package.
**Steps:** Create ops/runbooks/observability.md with sections: (1) Dashboard Inventory (UID, URL pattern, audience, key panels for each of 6 dashboards), (2) Alert Response Procedures for each alert in OPS-13 §7.5.1 through §7.5.6 with condition, severity, first response action, and Grafana/query link, (3) How to query a transaction trace in Jaeger (by transaction_id or trace_id), (4) How to add a new metric (update MetricsRegistry, add Prometheus scrape, add panel), (5) Contact list placeholder (on-call rotation, partner contacts, 한결원 ops contact); Cross-reference: pool identity breach P1 response must reference OPS-13 §8.9 procedure; Keep each alert response to <= 5 bullet steps; Review for completeness against all 22 alerts in OPS-13 §7.5.1-7.5.6
**Deliverable:** ops/runbooks/observability.md covering all 22 alert responses, 6 dashboard descriptions, Jaeger query guide, and metric extension guide
**Acceptance / logic checks:**
- File contains a section for every named alert in OPS-13 §7.5.1 through §7.5.6 (22 alerts total)
- Pool identity breach entry specifies: condition is pool_identity_breaches_total > 0, severity P1, first action is block transaction commit and page engineering
- Jaeger query section shows exact query: search by tag transaction_id={uuid} to retrieve the 8-step trace
- Dashboard inventory table lists all 6 dashboards with correct UIDs (ops-overview, api-slo, batch-health, prefunding-monitor, revenue-reporting, security)
- Document does not say see the spec — all thresholds are stated inline (e.g. prefunding critical threshold USD 2000)
**Depends on:** 14.6-T10, 14.6-T11, 14.6-T12, 14.6-T13, 14.6-T14, 14.6-T19


## WBS 14.7 — Alerting catalog
### 14.7-T01 — Define AlertSeverity enum and AlertRecord domain model  _(30 min)_
**Context:** GMEPay+ alert catalog (OPS-13 §7.5) uses PagerDuty-compatible severity levels P1 (wake someone now), P2 (Ops acknowledges within 30 min), P3 (next business day), P4 (informational). Every alert has: alert_code (machine-readable string), severity (P1-P4), condition_expr (human-readable), action_summary, category (SLO|PREFUNDING|BATCH_WINDOW|SCHEME_CONNECTIVITY|SETTLEMENT_FINANCIAL|WEBHOOK_INTEGRATION), and routing_channel. This ticket creates the core domain types only; no persistence or firing logic yet.
**Steps:** Create AlertSeverity enum with values P1, P2, P3, P4 and a numeric urgency() helper returning 1-4.; Create AlertCategory enum with values SLO, PREFUNDING, BATCH_WINDOW, SCHEME_CONNECTIVITY, SETTLEMENT_FINANCIAL, WEBHOOK_INTEGRATION.; Create immutable AlertRecord value object with fields: alert_code (String), category (AlertCategory), severity (AlertSeverity), condition_expr (String), action_summary (String).; Add equals/hashCode on alert_code (codes must be unique).; Write a unit test confirming P1.urgency() < P2.urgency() and that duplicate alert_code throws on construction.
**Deliverable:** AlertSeverity enum, AlertCategory enum, AlertRecord value object, unit test class AlertRecordTest
**Acceptance / logic checks:**
- AlertSeverity.P1.urgency() returns 1; P4.urgency() returns 4.
- Two AlertRecord instances with the same alert_code are equal regardless of other fields.
- Constructing AlertRecord with a null alert_code throws IllegalArgumentException.
- AlertCategory.values() contains exactly 6 members: SLO, PREFUNDING, BATCH_WINDOW, SCHEME_CONNECTIVITY, SETTLEMENT_FINANCIAL, WEBHOOK_INTEGRATION.

### 14.7-T02 — Create alert_catalog DB table and seed migration for all OPS-13 alerts  _(45 min)_
**Context:** OPS-13 §7.5 defines 22 named alerts across 6 categories. They must be stored in a DB table so that routing channels and thresholds can be updated by Ops without code deploys. Table columns: alert_code VARCHAR PK, category VARCHAR NOT NULL, severity CHAR(2) NOT NULL, condition_expr TEXT NOT NULL, action_summary TEXT NOT NULL, routing_channel VARCHAR NOT NULL (e.g. pagerduty-p1, pagerduty-p2, email-ops), enabled BOOLEAN DEFAULT TRUE, created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ.
**Steps:** Write Flyway migration V14_7_001__create_alert_catalog.sql creating the alert_catalog table with the columns above.; Write V14_7_002__seed_alert_catalog.sql inserting all 22 alerts from OPS-13 §7.5 using exact alert_code values (e.g. SLO_P95_LATENCY_BREACH, PREFUNDING_LOW_BALANCE, BATCH_ZP0011_MISSED, SFTP_CONNECTION_FAILURE, POOL_IDENTITY_FAILURE, WEBHOOK_DELIVERY_FAILURE).; Add a DB constraint CHECK (severity IN ('P1','P2','P3','P4')).; Verify migrations run cleanly on a local PostgreSQL instance (port 5433 per project convention).
**Deliverable:** Two Flyway migration files V14_7_001 and V14_7_002; alert_catalog table with 22 seeded rows
**Acceptance / logic checks:**
- SELECT COUNT(*) FROM alert_catalog returns 22.
- SELECT COUNT(*) FROM alert_catalog WHERE severity = 'P1' returns at least 8 (all P1 alerts from OPS-13 §7.5).
- INSERT of a row with severity = 'P5' is rejected by the check constraint.
- alert_code = 'POOL_IDENTITY_FAILURE' row has severity = 'P1' and category = 'SETTLEMENT_FINANCIAL'.
- alert_code = 'PREFUNDING_LOW_BALANCE' row has severity = 'P2' and routing_channel targeting partner email + GME Ops.
**Depends on:** 14.7-T01

### 14.7-T03 — Implement AlertCatalogRepository with CRUD and enabled-filter query  _(45 min)_
**Context:** The alert_catalog table (created in 14.7-T02) must be accessible via a repository interface used by the alert-evaluation engine and Admin UI. Required operations: findAll(), findByCategory(AlertCategory), findByCode(String), findAllEnabled(), enable(String alertCode), disable(String alertCode). Uses Spring Data JDBC or JPA on PostgreSQL (port 5433). All mutations must write updated_at = now() and be audit-logged (actor, timestamp, previous value).
**Steps:** Define AlertCatalogRepository interface with the 6 methods above.; Implement using Spring Data JDBC; map alert_catalog rows to AlertCatalogEntry entity.; Implement enable() and disable() as UPDATE alert_catalog SET enabled = ?, updated_at = now() WHERE alert_code = ?.; Add audit logging on enable/disable: write to the existing audit_log table with entity_type='ALERT_CATALOG', entity_id=alert_code, actor, changed_field='enabled', old_value, new_value.; Write integration test AlertCatalogRepositoryIT covering findAllEnabled(), enable/disable round-trip, and audit log entry creation.
**Deliverable:** AlertCatalogRepository interface + implementation, AlertCatalogEntry JPA/JDBC entity, integration test class AlertCatalogRepositoryIT
**Acceptance / logic checks:**
- findAllEnabled() returns 22 rows after seed migration (all enabled by default).
- disable('SFTP_CONNECTION_FAILURE') then findAllEnabled() returns 21 rows; findByCode('SFTP_CONNECTION_FAILURE').enabled == false.
- enable() after disable() restores the row; audit_log contains two entries for that alert_code.
- findByCategory(AlertCategory.BATCH_WINDOW) returns exactly 7 rows matching OPS-13 §7.5.3.
- Calling enable() on a non-existent alert_code throws AlertNotFoundException.
**Depends on:** 14.7-T02

### 14.7-T04 — Define AlertEvaluationContext and AlertCondition interface  _(35 min)_
**Context:** Each alert in the catalog has a condition that must be evaluated against live metric values. The evaluation engine needs a uniform interface: AlertCondition.evaluate(AlertEvaluationContext ctx) returns boolean. AlertEvaluationContext carries the live metric snapshot needed to evaluate any alert: it is a value object holding named metric values (e.g. p95_latency_ms, error_rate_5min, prefunding_balance_usd by partner_id, pool_identity_breaches_total, webhook_queue_depth, batch_file_delivery_lag_seconds by file_type). This ticket creates the context and condition interface; concrete implementations come in subsequent tickets.
**Steps:** Create AlertEvaluationContext as an immutable value object with a Map<String, Object> payload and typed accessor methods: getLong(String key), getDouble(String key), getString(String key).; Add partner-scoped accessor getDoubleForPartner(String metricKey, String partnerId) returning Optional<Double>.; Define AlertCondition as a functional interface: boolean evaluate(AlertEvaluationContext ctx).; Add a ThresholdCondition implementation that evaluates: getDouble(metricKey) > threshold.; Write unit tests: ThresholdCondition with key='p95_latency_ms', threshold=500.0 returns true when ctx has p95_latency_ms=600.0 and false when 400.0.
**Deliverable:** AlertEvaluationContext class, AlertCondition interface, ThresholdCondition implementation, unit test AlertConditionTest
**Acceptance / logic checks:**
- ThresholdCondition(p95_latency_ms, 500.0).evaluate(ctx with p95_latency_ms=501.0) returns true.
- ThresholdCondition(p95_latency_ms, 500.0).evaluate(ctx with p95_latency_ms=500.0) returns false (strictly greater-than).
- getLong() on a missing key throws MetricNotFoundException; getDoubleForPartner() on missing partner returns Optional.empty().
- AlertEvaluationContext is immutable: a second call to evaluate on the same ctx produces the same result.
**Depends on:** 14.7-T01

### 14.7-T05 — Implement SLO alert conditions (API p95 latency, error rate, rate-quote failure, health check)  _(40 min)_
**Context:** OPS-13 §7.5.1 defines 5 API/SLO alerts. Conditions: (1) SLO_P95_LATENCY_BREACH: api_latency_p95_ms > 500 sustained 5 min on any endpoint. (2) SLO_ERROR_RATE_P1: 5xx_error_rate_5min > 1.0% (P1). (3) SLO_ERROR_RATE_P2: 5xx_error_rate_10min > 0.5% (P2). (4) RATE_QUOTE_FAILURE_SPIKE: rates_error_rate_2min > 5.0% (P1). (5) PAYMENT_ENDPOINT_UNAVAILABLE: health_check_failed_seconds > 60 (P1). Each AlertCondition is associated with its alert_code from the catalog. NFR-10 confirms: payment API p99 < 2000ms, success rate >= 98% monthly; OPS-13 sets the p95 < 500ms alerting threshold.
**Steps:** Create SloAlertConditions class with 5 static factory methods each returning an AlertCondition for the codes above.; Each condition reads from AlertEvaluationContext using the metric keys: api_latency_p95_ms, error_rate_5xx_5min_pct, error_rate_5xx_10min_pct, rates_error_rate_2min_pct, health_check_failed_seconds.; Bind each condition to its alert_code via an AlertConditionRegistry Map<String, AlertCondition>.; Register all 5 SLO conditions in AlertConditionRegistry.; Write unit tests for all 5 conditions with boundary values at exact threshold (not firing) and threshold+epsilon (firing).
**Deliverable:** SloAlertConditions class, alert_code-to-AlertCondition entries in AlertConditionRegistry, unit test SloAlertConditionsTest
**Acceptance / logic checks:**
- SLO_P95_LATENCY_BREACH fires when api_latency_p95_ms=500.1 and does NOT fire when api_latency_p95_ms=500.0.
- SLO_ERROR_RATE_P1 fires when error_rate_5xx_5min_pct=1.01 and does not fire at 1.00.
- RATE_QUOTE_FAILURE_SPIKE fires when rates_error_rate_2min_pct=5.1 and does not fire at 5.0.
- PAYMENT_ENDPOINT_UNAVAILABLE fires when health_check_failed_seconds=61 and does not fire at 60.
- AlertConditionRegistry.get('SLO_P95_LATENCY_BREACH') returns non-null; all 5 codes are registered.
**Depends on:** 14.7-T04, 14.7-T02

### 14.7-T06 — Implement prefunding alert conditions (low balance, critical balance, zero balance, deduction anomaly)  _(40 min)_
**Context:** OPS-13 §7.5.2 defines 4 prefunding alerts for OVERSEAS partners (SendMN, T-Bank). Conditions (default thresholds, configurable per partner): (1) PREFUNDING_LOW_BALANCE: partner prefunding_balance_usd < 10000.0 after a deduction (P2, informational - does not block txn). (2) PREFUNDING_CRITICAL_BALANCE: balance < 2000.0 (P1 - trigger partner payment suspension). (3) PREFUNDING_BALANCE_ZERO: balance = 0 (P1 - all partner payments suspended). (4) PREFUNDING_DEDUCTION_ANOMALY: single deduction_amount_usd > 50000.0 (P2 - manual review before commit). Thresholds must be read from the partner config field low_balance_threshold_usd (default 10000.0); critical threshold = 2000.0 (hardcoded default). LOCAL partners (GME Remit) must never trigger these alerts.
**Steps:** Create PrefundingAlertConditions class with 4 AlertCondition implementations.; Conditions read from AlertEvaluationContext keys: prefunding_balance_usd_{partner_id}, prefunding_deduction_usd_{partner_id}, partner_type_{partner_id}.; For PREFUNDING_LOW_BALANCE: threshold sourced from ctx key low_balance_threshold_usd_{partner_id}; default 10000.0 if absent.; Add a guard: if partner_type == LOCAL, all 4 conditions return false immediately.; Write unit tests for LOCAL partner guard, exact threshold boundaries for all 4 conditions, and a scenario where balance=1999.0 fires both PREFUNDING_CRITICAL_BALANCE and PREFUNDING_LOW_BALANCE simultaneously.
**Deliverable:** PrefundingAlertConditions class with 4 implementations, unit test PrefundingAlertConditionsTest
**Acceptance / logic checks:**
- PREFUNDING_LOW_BALANCE fires for OVERSEAS partner when balance=9999.99 and threshold=10000.0; does NOT fire at 10000.00.
- PREFUNDING_CRITICAL_BALANCE fires at balance=1999.99 and does NOT fire at 2000.0.
- PREFUNDING_BALANCE_ZERO fires only when balance == 0.0 exactly (not at 0.01).
- PREFUNDING_DEDUCTION_ANOMALY fires when deduction=50000.01 and does not fire at 50000.0.
- All 4 conditions return false when partner_type=LOCAL regardless of balance value.
**Depends on:** 14.7-T04, 14.7-T02

### 14.7-T07 — Implement batch-window breach alert conditions for all ZP00xx file types  _(50 min)_
**Context:** OPS-13 §7.5.3 defines 7 batch-window alerts. ZeroPay batch deadlines (KST, which is UTC+9): ZP0011/ZP0021 outbound must be submitted by 01:45 KST (P1); ZP0012/ZP0022 inbound must be received by 05:30 KST (P1); ZP0061 outbound by 05:15 KST (P1); ZP0062 inbound by 10:30 KST (P1); ZP0063/ZP0065/ZP0066 outbound by window+15 min (P2); ZP0041/ZP0043 merchant sync inbound by 08:00 KST (P3); any batch job in FAILED state after 3 retries (P1). Alert codes: BATCH_ZP0011_MISSED, BATCH_ZP0012_NOT_RECEIVED, BATCH_ZP0061_MISSED, BATCH_ZP0062_NOT_RECEIVED, BATCH_ZP0063_MISSED, BATCH_MERCHANT_SYNC_OVERDUE, BATCH_JOB_FAILED. All timestamps stored and compared in UTC internally.
**Steps:** Create BatchWindowAlertConditions class with 7 AlertCondition implementations.; Context keys: batch_file_delivery_lag_seconds_{file_type} (positive = late), batch_job_state_{job_name} (e.g. FAILED/RUNNING/COMPLETED), batch_job_retry_count_{job_name}.; For deadline-based alerts: condition fires when current_utc_time > deadline_utc AND file_delivery_confirmed_{file_type} == false.; For BATCH_JOB_FAILED: fires when batch_job_state_{job_name} == FAILED AND batch_job_retry_count >= 3.; Write unit tests for each alert, including a time-boundary test: ZP0011 deadline is 16:45 UTC (01:45 KST); condition fires at 16:46 UTC and not at 16:44 UTC.
**Deliverable:** BatchWindowAlertConditions class with 7 implementations, unit test BatchWindowAlertConditionsTest
**Acceptance / logic checks:**
- BATCH_ZP0011_MISSED fires when current_utc=16:46 and file_delivery_confirmed_ZP0011=false; does NOT fire when delivery confirmed.
- BATCH_ZP0012_NOT_RECEIVED fires when current_utc=20:31 (05:31 KST) and inbound not received.
- BATCH_JOB_FAILED fires when state=FAILED AND retry_count=3; does NOT fire when retry_count=2 (still retrying).
- BATCH_MERCHANT_SYNC_OVERDUE fires at 23:01 UTC (08:01 KST) when ZP0041 not received; is P3 severity (informational).
- Each deadline is converted from KST to UTC correctly (subtract 9 hours).
**Depends on:** 14.7-T04, 14.7-T02

### 14.7-T08 — Implement scheme and connectivity alert conditions (SFTP, ZeroPay API, Partner B quote)  _(40 min)_
**Context:** OPS-13 §7.5.4 defines 4 scheme/connectivity alerts. Conditions: (1) SFTP_CONNECTION_FAILURE: ZeroPay SFTP unreachable after 3 retries (P1). (2) SCHEME_API_TIMEOUT: ZeroPay API latency p95 > 5000ms over 5 min (P2). (3) PARTNER_B_QUOTE_API_DOWN: Partner B quote API error_rate > 1% over 5 min (P2) — affected transactions must return PARTNER_B_QUOTE_UNAVAILABLE (no fallback per RATE-04). (4) PARTNER_B_QUOTE_DEVIATION_HIGH: deviation > 1% on > 5% of quotes over any rolling window (P2). Circuit breaker state (circuit_breaker_state=1 means open) also surfaces as SCHEME_CIRCUIT_BREAKER_OPEN (P1, from NFR-10 §7.5).
**Steps:** Create SchemeConnectivityAlertConditions class with 5 AlertCondition implementations (including circuit breaker).; Context keys: sftp_retry_count, sftp_reachable (bool), zeropay_api_p95_latency_ms_5min, partner_b_error_rate_5min_pct_{partner_id}, partner_b_deviation_rate_pct_{partner_id}, circuit_breaker_state_{scheme}.; SFTP_CONNECTION_FAILURE fires when sftp_reachable=false AND sftp_retry_count >= 3.; PARTNER_B_QUOTE_DEVIATION_HIGH fires when partner_b_deviation_rate_pct > 5.0 (5% of quotes have >1% deviation).; Write unit tests for all 5 conditions including boundary values.
**Deliverable:** SchemeConnectivityAlertConditions class with 5 implementations, unit test SchemeConnectivityAlertConditionsTest
**Acceptance / logic checks:**
- SFTP_CONNECTION_FAILURE fires when sftp_reachable=false AND retry_count=3; does NOT fire when retry_count=2.
- SCHEME_API_TIMEOUT fires when zeropay_api_p95_latency_ms_5min=5001 and not at 5000.
- PARTNER_B_QUOTE_API_DOWN fires when partner_b_error_rate_5min_pct=1.01 and not at 1.0.
- PARTNER_B_QUOTE_DEVIATION_HIGH fires when partner_b_deviation_rate_pct=5.1 and not at 5.0.
- SCHEME_CIRCUIT_BREAKER_OPEN fires when circuit_breaker_state_ZeroPay=1 and is P1 severity.
**Depends on:** 14.7-T04, 14.7-T02

### 14.7-T09 — Implement settlement and financial alert conditions (pool identity, reconciliation, revenue, BOK)  _(35 min)_
**Context:** OPS-13 §7.5.5 defines 4 settlement/financial alerts. Conditions: (1) POOL_IDENTITY_FAILURE: pool_identity_breaches_total > 0 (P1) - the pool identity invariant is collection_usd - collection_margin_usd - payout_margin_usd == payout_usd_cost within 0.01 USD; any breach must block transaction commit and fire this alert. (2) SETTLEMENT_RECONCILIATION_MISMATCH: any line-item discrepancy in ZP0062/ZP0064 vs. GME internal records (P1). (3) REVENUE_LEDGER_MISMATCH: computed FX margin + service fee != recorded revenue in daily close (P1). (4) BOK_REPORT_GENERATION_FAILURE: automated BOK submission fails (P2, Phase 2+). NFR-10 §7.5 confirms pool_identity_breaches_total must always be 0.
**Steps:** Create SettlementAlertConditions class with 4 AlertCondition implementations.; Context keys: pool_identity_breaches_total, reconciliation_mismatch_count_{date}, revenue_ledger_delta_usd_{date}, bok_report_failed (bool).; POOL_IDENTITY_FAILURE fires when pool_identity_breaches_total > 0; value of 0 must never fire.; SETTLEMENT_RECONCILIATION_MISMATCH fires when reconciliation_mismatch_count > 0 for any date key.; Write unit tests including a zero-breach baseline test and a scenario with reconciliation_mismatch_count=1.
**Deliverable:** SettlementAlertConditions class with 4 implementations, unit test SettlementAlertConditionsTest
**Acceptance / logic checks:**
- POOL_IDENTITY_FAILURE fires when pool_identity_breaches_total=1 and does NOT fire at 0.
- SETTLEMENT_RECONCILIATION_MISMATCH fires when reconciliation_mismatch_count_2026-06-05=1; is P1 severity.
- REVENUE_LEDGER_MISMATCH fires when revenue_ledger_delta_usd_2026-06-05=0.02 (any non-zero delta).
- BOK_REPORT_GENERATION_FAILURE fires when bok_report_failed=true; is P2 severity.
- AlertConditionRegistry contains all 4 settlement alert codes after wiring.
**Depends on:** 14.7-T04, 14.7-T02

### 14.7-T10 — Implement webhook and integration alert conditions (delivery failure, queue depth, latency)  _(35 min)_
**Context:** OPS-13 §7.5.6 defines 3 webhook/integration alerts. Conditions: (1) WEBHOOK_DELIVERY_FAILURE: partner webhook returns non-2xx after max retries (P2) - API-05 spec states 5 retry attempts with exponential backoff; delivery within 30 seconds of transaction commit. (2) WEBHOOK_QUEUE_DEPTH: queue depth > 500 unprocessed webhooks (P2). (3) WEBHOOK_LATENCY_BREACH: webhook dispatcher p95 > 30 seconds (P3). The 8-step transaction event trail includes webhook_dispatched and webhook_delivered (or webhook_failed) steps; alert fires on webhook_failed after all retries exhausted.
**Steps:** Create WebhookAlertConditions class with 3 AlertCondition implementations.; Context keys: webhook_delivery_failed_{partner_id} (bool, true when all 5 retries exhausted), webhook_queue_depth, webhook_dispatcher_p95_seconds.; WEBHOOK_DELIVERY_FAILURE is per-partner: fires when webhook_delivery_failed_{partner_id}=true; requires partner_id label in alert payload.; WEBHOOK_QUEUE_DEPTH fires when webhook_queue_depth > 500 (strictly greater).; WEBHOOK_LATENCY_BREACH fires when webhook_dispatcher_p95_seconds > 30.; Write unit tests for all 3 conditions and a multi-partner scenario where only one partner's webhook has failed.
**Deliverable:** WebhookAlertConditions class with 3 implementations, unit test WebhookAlertConditionsTest
**Acceptance / logic checks:**
- WEBHOOK_DELIVERY_FAILURE fires for partner SendMN when webhook_delivery_failed_SendMN=true and does not fire when false.
- WEBHOOK_QUEUE_DEPTH fires at depth=501 and does NOT fire at 500.
- WEBHOOK_LATENCY_BREACH fires at p95=30.001 and not at 30.0; is P3 severity (informational).
- In a 2-partner context, WEBHOOK_DELIVERY_FAILURE for SendMN fires without affecting T-Bank state.
- Alert payload for WEBHOOK_DELIVERY_FAILURE includes partner_id field.
**Depends on:** 14.7-T04, 14.7-T02

### 14.7-T11 — Build AlertConditionRegistry: bind all 22+ alert codes to their condition implementations  _(40 min)_
**Context:** The AlertConditionRegistry is the central wiring component that maps each alert_code (from alert_catalog table) to its AlertCondition implementation. It must be populated at application startup and validated: every enabled alert in the catalog must have a registered condition, or startup fails with a ConfigurationException listing the unregistered codes. Registry is read-only after initialization. All 5 condition groups (SLO, Prefunding, Batch, Scheme, Settlement, Webhook) plus SCHEME_CIRCUIT_BREAKER_OPEN contribute their entries.
**Steps:** Create AlertConditionRegistry as a Spring @Component holding an immutable Map<String, AlertCondition> populated via constructor injection of all 5 AlertConditions @Configuration classes.; On init, load all enabled alert codes from AlertCatalogRepository and assert each has a registered condition; log a startup ERROR and throw ConfigurationException listing missing codes if any are absent.; Expose get(String alertCode) returning AlertCondition and listRegistered() returning Set<String>.; Write a unit test AlertConditionRegistryTest that: mocks the catalog returning all 22 codes, verifies all 22 are registered, then adds a 23rd catalog code without a condition and verifies ConfigurationException is thrown.
**Deliverable:** AlertConditionRegistry Spring component, startup validation logic, unit test AlertConditionRegistryTest
**Acceptance / logic checks:**
- Registry starts successfully when all 22 catalog codes have registered conditions.
- Startup throws ConfigurationException if catalog contains POOL_IDENTITY_FAILURE but no condition is registered for it.
- get('WEBHOOK_DELIVERY_FAILURE') returns a non-null WebhookAlertConditions instance.
- listRegistered() returns a set of size >= 22.
- Disabling an alert in the catalog (enabled=false) means it is excluded from the startup validation check.
**Depends on:** 14.7-T05, 14.7-T06, 14.7-T07, 14.7-T08, 14.7-T09, 14.7-T10, 14.7-T03

### 14.7-T12 — Create AlertFiring event class and AlertNotificationChannel interface  _(30 min)_
**Context:** When an alert condition evaluates to true, the system must emit an AlertFiring event and route it to the appropriate notification channel. AlertFiring fields: alert_code, severity (P1-P4), fired_at (UTC Instant), context_snapshot (Map<String, Object> with the metric values that caused firing), partner_id (nullable, for partner-scoped alerts), message (human-readable). AlertNotificationChannel is an interface with: void send(AlertFiring event). Multiple channels exist (PagerDuty, email, Slack/ops-channel). This ticket defines the event and interface; channel implementations come in T13-T15.
**Steps:** Create AlertFiring as an immutable value object with the fields above; add a factory method forPartner(alertCode, severity, partnerId, contextSnapshot) and a global forSystem(alertCode, severity, contextSnapshot).; Define AlertNotificationChannel interface with send(AlertFiring event) and supports(AlertSeverity severity) predicate.; Create AlertRoutingPolicy value object: given an alert severity, returns the list of channel names to notify (e.g. P1 -> [pagerduty, ops-slack, email]; P2 -> [pagerduty, email]; P3 -> [ops-slack]; P4 -> [log-only]).; Write a unit test verifying AlertFiring built via forPartner contains partner_id and fired_at is not null.
**Deliverable:** AlertFiring class, AlertNotificationChannel interface, AlertRoutingPolicy class, unit test AlertFiringTest
**Acceptance / logic checks:**
- AlertFiring.forPartner('PREFUNDING_LOW_BALANCE', P2, 'SendMN', ctx) has partner_id='SendMN' and severity=P2.
- AlertFiring.forSystem('POOL_IDENTITY_FAILURE', P1, ctx) has partner_id=null.
- AlertRoutingPolicy for P1 returns a list containing at least 'pagerduty' and 'email'.
- AlertRoutingPolicy for P3 does not include 'pagerduty'.
- AlertFiring is immutable: context_snapshot map cannot be mutated after construction.
**Depends on:** 14.7-T01

### 14.7-T13 — Implement PagerDuty notification channel adapter (P1/P2 alerts)  _(50 min)_
**Context:** OPS-13 §7.5 states PagerDuty (or compatible on-call tool) is used for alert routing and escalation. The PagerDutyChannel adapter must call the PagerDuty Events API v2 (POST https://events.pagerduty.com/v2/enqueue) with a dedup_key = alert_code + date (to suppress duplicate firings on the same calendar day for the same alert), severity mapped as: P1->critical, P2->error, P3->warning, P4->info. The adapter handles network failures with a single retry (no infinite loop). The PagerDuty integration key is read from secrets at runtime (secret path: gmepay/prod/pagerduty/integration_key).
**Steps:** Create PagerDutyChannel implementing AlertNotificationChannel; supports() returns true for P1 and P2 only.; Build PagerDuty Events v2 payload: routing_key, event_action=trigger, dedup_key, payload.summary=alert_code + message, payload.severity, payload.timestamp.; HTTP POST with 5-second timeout; on non-2xx or IOException, log ERROR and retry once after 2 seconds; if second attempt also fails, log CRITICAL and return (do not throw - alerting must not crash the caller).; Inject integration_key via @ConfigurationProperties reading from secrets manager; never hardcode.; Write PagerDutyChannelTest using MockWebServer (OkHttp) to verify correct payload structure and dedup_key format.
**Deliverable:** PagerDutyChannel class, PagerDutyChannelTest with MockWebServer
**Acceptance / logic checks:**
- On first 500 response, channel retries exactly once; on second 500, logs CRITICAL and does not throw.
- dedup_key for alert SFTP_CONNECTION_FAILURE on 2026-06-05 is 'SFTP_CONNECTION_FAILURE_2026-06-05'.
- P1 alert maps to PagerDuty severity=critical; P2 maps to error.
- supports(P3) returns false; supports(P1) returns true.
- Integration key is read from config, not hardcoded; test uses a mock key value.
**Depends on:** 14.7-T12

### 14.7-T14 — Implement email notification channel adapter for partner and ops alerts  _(45 min)_
**Context:** OPS-13 §7.5.2 requires email to the partner's configured alert contacts when a prefunding alert fires (e.g. PREFUNDING_LOW_BALANCE sends email to low_balance_alert_email on the partner record in DAT-03 table prefunding_account.low_balance_alert_email / partner.alert_contacts). OPS-13 §7.5 also routes P2 alerts to GME Ops email. The EmailChannel adapter resolves recipient addresses: for partner-scoped alerts use partner.alert_contacts; for system alerts use the configured ops_alert_email list. Uses Spring Mail (JavaMailSender). Email subject: [GMEPay+ {severity}] {alert_code}. Body: condition_expr, action_summary, fired_at, partner_id (if applicable), metric snapshot values.
**Steps:** Create EmailChannel implementing AlertNotificationChannel; supports() returns true for P1, P2, P3.; Inject JavaMailSender and two config properties: ops_alert_email (List<String>) and a PartnerAlertContactResolver that looks up partner.alert_contacts by partner_id from DB.; For partner-scoped AlertFiring (partner_id != null): send to partner alert contacts AND ops_alert_email.; For system-scoped AlertFiring: send to ops_alert_email only.; Write EmailChannelTest using GreenMail (in-memory SMTP) verifying subject format, recipient list for partner vs system alert, and that body includes the metric snapshot.
**Deliverable:** EmailChannel class, PartnerAlertContactResolver, EmailChannelTest with GreenMail
**Acceptance / logic checks:**
- PREFUNDING_LOW_BALANCE firing for partner SendMN sends email to SendMN alert contacts AND ops list.
- POOL_IDENTITY_FAILURE (system alert) sends email to ops list only; not to any partner.
- Email subject for P1 alert starts with '[GMEPay+ P1]'.
- Email body includes fired_at timestamp in ISO-8601 UTC format.
- If PartnerAlertContactResolver finds no contacts for a partner, email is sent to ops_alert_email fallback only (no failure thrown).
**Depends on:** 14.7-T12, 14.7-T03

### 14.7-T15 — Implement Ops Slack/channel notification adapter (P1/P2/P3 alerts)  _(40 min)_
**Context:** OPS-13 §7.5 routes alerts to an ops Slack channel (or equivalent messaging platform). The SlackChannel adapter posts to a configured webhook URL (Incoming Webhooks). P1 alerts must include @here mention in the message. P2 alerts: normal notification. P3 alerts: plain informational message. Message format: {severity-emoji} [{severity}] {alert_code} | {message} | fired_at UTC | partner: {partner_id or SYSTEM}. The Slack webhook URL is read from secrets: gmepay/prod/slack/ops_webhook_url. Network failures: single retry; if both fail, log WARN and continue (Slack is non-critical channel).
**Steps:** Create SlackChannel implementing AlertNotificationChannel; supports() returns true for P1, P2, P3.; Build Slack Blocks payload (or simple text) with the message format above; P1 uses <!here> prefix.; HTTP POST to webhook URL with 5-second timeout; single retry on failure; logs WARN on double failure without throwing.; Inject webhook_url from config; must not be hardcoded.; Write SlackChannelTest using MockWebServer verifying: P1 message contains '<!here>', P4 alert is not sent (supports() = false), retry occurs on first 500.
**Deliverable:** SlackChannel class, SlackChannelTest with MockWebServer
**Acceptance / logic checks:**
- P1 alert message body contains '<!here>'.
- P4 alert: supports(P4) returns false; send() is never called.
- On first HTTP 500, channel retries once; on second 500, logs WARN and returns without throwing.
- Message contains the exact alert_code string (e.g. 'PREFUNDING_CRITICAL_BALANCE').
- partner_id is included in the message for partner-scoped alerts; 'SYSTEM' appears for non-partner alerts.
**Depends on:** 14.7-T12

### 14.7-T16 — Build AlertEvaluationEngine: evaluate all enabled alerts on a metric snapshot  _(55 min)_
**Context:** The AlertEvaluationEngine is the core orchestrator. Given a fresh AlertEvaluationContext (metric snapshot), it evaluates all enabled alerts from the registry, collects those whose condition returns true, and for each creates an AlertFiring event and dispatches it through the AlertRoutingPolicy to the appropriate notification channels. It must be idempotent: if the same alert fires twice within a deduplication window (default 5 minutes), the second firing is suppressed (logged at DEBUG). Uses Redis for dedup state (key: alert:{alert_code}:{partner_id_or_SYSTEM}, TTL = dedup_window_seconds).
**Steps:** Create AlertEvaluationEngine with constructor injection of AlertConditionRegistry, AlertCatalogRepository, AlertRoutingPolicy, List<AlertNotificationChannel>, and a Redis client.; evaluate(AlertEvaluationContext ctx) method: iterate all enabled catalog entries, call condition.evaluate(ctx), for truthy results check Redis dedup key (SETNX with TTL), if not deduped build AlertFiring and dispatch.; Dispatch: call AlertRoutingPolicy.channelsFor(severity), then send(alertFiring) on each matching channel.; Log every firing at WARN level with alert_code, severity, fired_at, partner_id.; Write AlertEvaluationEngineTest using mocked registry (5 alert codes), mocked channels, and an embedded Redis (Testcontainers) verifying: first call fires all 5, second call within dedup window fires 0.
**Deliverable:** AlertEvaluationEngine class, AlertEvaluationEngineTest with Testcontainers Redis
**Acceptance / logic checks:**
- evaluate() with 3 conditions true fires exactly 3 AlertFiring events.
- Second evaluate() call within dedup window (1 min TTL) fires 0 events (all deduped).
- evaluate() after dedup TTL expires fires all 3 again.
- P1 alert is dispatched to PagerDuty and email channels; P3 alert dispatched to Slack only.
- If a notification channel throws, the exception is caught and logged; other channels still receive the alert.
**Depends on:** 14.7-T11, 14.7-T12, 14.7-T13, 14.7-T14, 14.7-T15

### 14.7-T17 — Implement MetricSnapshotCollector: aggregate live metrics into AlertEvaluationContext  _(50 min)_
**Context:** The AlertEvaluationEngine needs a fresh AlertEvaluationContext on each evaluation cycle. The MetricSnapshotCollector queries the metrics platform (Prometheus or equivalent) and the database to assemble the context. Required metric sources per OPS-13 §7.1 and NFR-10 §7.1: api_latency_p95_ms (from Prometheus histogram api_latency_p95), error rates (computed from api_request_rate counters), prefunding_balance_usd per partner (from DB table prefunding_account.current_balance_usd), pool_identity_breaches_total (Prometheus counter), webhook_queue_depth (message queue API), batch_file_delivery_lag_seconds per file_type (DB table batch_job_runs), circuit_breaker_state per scheme, sftp_retry_count and sftp_reachable.
**Steps:** Create MetricSnapshotCollector with injected PrometheusQueryClient, PrefundingAccountRepository, BatchJobRunRepository.; Implement collect() returning AlertEvaluationContext with all keys needed by alert conditions (see T05-T10 for key names).; Prefunding balances: query prefunding_account WHERE partner_type='OVERSEAS' and populate prefunding_balance_usd_{partner_id} for each row.; Batch lag: query batch_job_runs for latest run per job_name; compute lag as now()-expected_completion_utc; negative = on time.; Write MetricSnapshotCollectorTest with mocked dependencies; verify that an OVERSEAS partner with balance=9000.00 appears as prefunding_balance_usd_SendMN=9000.00 in the context.
**Deliverable:** MetricSnapshotCollector class, MetricSnapshotCollectorTest
**Acceptance / logic checks:**
- collect() populates prefunding_balance_usd_SendMN from DB prefunding_account row for SendMN.
- collect() does NOT populate prefunding keys for LOCAL partner GME_Remit.
- pool_identity_breaches_total is read from Prometheus counter metric of the same name.
- batch_file_delivery_lag_seconds_ZP0011 is positive when job ran late, negative when on time.
- If Prometheus is unreachable, collect() logs WARN and returns a partial context (DB-sourced metrics still present).
**Depends on:** 14.7-T04, 14.7-T03

### 14.7-T18 — Wire AlertEvaluationScheduler: run evaluation every 60 seconds via Spring @Scheduled  _(35 min)_
**Context:** The alert evaluation cycle must run continuously in production. OPS-13 implies near-real-time alerting (SLO alerts have 5-minute windows, but the evaluation tick should be much shorter to detect the window breach promptly). Default evaluation interval: 60 seconds (configurable via alerting.evaluation.interval_seconds, min 30s, max 300s). The scheduler calls MetricSnapshotCollector.collect() then AlertEvaluationEngine.evaluate(ctx). Errors in collect() or evaluate() must be caught, logged at ERROR, and must NOT stop the scheduler thread.
**Steps:** Create AlertEvaluationScheduler as a Spring @Component with @Scheduled(fixedDelayString = '${alerting.evaluation.interval_seconds:60}000').; Inject MetricSnapshotCollector and AlertEvaluationEngine.; In the scheduled method: call collect(), call evaluate(); wrap in try-catch; on exception log ERROR with stack trace and increment an alerting_scheduler_errors_total Prometheus counter.; Expose a triggerNow() method for manual/test invocation (not scheduled).; Write AlertEvaluationSchedulerTest verifying: scheduler error increments counter and does not propagate exception, triggerNow() calls both collect() and evaluate() in order.
**Deliverable:** AlertEvaluationScheduler Spring component, application.yml property alerting.evaluation.interval_seconds=60, AlertEvaluationSchedulerTest
**Acceptance / logic checks:**
- Scheduler catches RuntimeException from evaluate() and does not rethrow; alerting_scheduler_errors_total increments by 1.
- application.yml contains alerting.evaluation.interval_seconds=60.
- Setting interval to 29 triggers a validation error at startup (min 30s constraint).
- triggerNow() invokes collect() exactly once then evaluate() exactly once.
- Scheduler method has @Scheduled annotation with fixedDelayString referencing the config property.
**Depends on:** 14.7-T16, 14.7-T17

### 14.7-T19 — Implement prefunding low-balance alert auto-clear logic  _(45 min)_
**Context:** OPS-13 §8.5 (Procedure: Record a Prefunding Top-Up) states: if the partner low-balance alert was previously triggered, it must auto-clear once the balance exceeds the threshold. Auto-clear means: remove the Redis dedup key for PREFUNDING_LOW_BALANCE (and PREFUNDING_CRITICAL_BALANCE if applicable) for that partner, AND send a clearance notification (email to partner contacts and ops: subject [GMEPay+ CLEARED] PREFUNDING_LOW_BALANCE - {partner_id}). This must be triggered by the PrefundingService when a top-up is recorded and the new balance exceeds the threshold.
**Steps:** Add AlertAutoClears service with method clearIfResolved(String partnerId, BigDecimal newBalance).; Method checks: if newBalance >= partner.low_balance_threshold_usd, delete Redis key alert:PREFUNDING_LOW_BALANCE:{partnerId}; if newBalance >= 2000.0, also delete alert:PREFUNDING_CRITICAL_BALANCE:{partnerId}; if newBalance > 0, delete alert:PREFUNDING_BALANCE_ZERO:{partnerId}.; Send clearance email via EmailChannel with special subject prefix [GMEPay+ CLEARED] and body noting new balance and threshold.; Call clearIfResolved() from PrefundingService.recordTopUp() after balance update commits.; Write AlertAutoClearsTest: after top-up sets SendMN balance to 15000.0 (threshold 10000.0), all three Redis keys are deleted and one clearance email is sent.
**Deliverable:** AlertAutoClearsService class, PrefundingService.recordTopUp() wired to call it, AlertAutoClearsTest
**Acceptance / logic checks:**
- Top-up bringing SendMN balance from 1500 to 15000 deletes Redis keys for PREFUNDING_LOW_BALANCE, PREFUNDING_CRITICAL_BALANCE, and PREFUNDING_BALANCE_ZERO.
- Top-up bringing balance from 1500 to 1800 (still below 2000 critical threshold) only deletes PREFUNDING_BALANCE_ZERO key (balance > 0).
- Clearance email subject starts with '[GMEPay+ CLEARED]' and includes partner_id.
- If no alert was previously fired (Redis key absent), clearIfResolved() is a no-op (no email sent).
- clearIfResolved() is called inside the same DB transaction as the top-up balance update.
**Depends on:** 14.7-T14, 14.7-T16, 14.7-T06

### 14.7-T20 — Implement pool-identity alert inline guard in rate engine transaction commit path  _(50 min)_
**Context:** OPS-13 §7.5.5 and QA-12 §14.6 state: the pool identity assertion (collection_usd - collection_margin_usd - payout_margin_usd == payout_usd_cost within 0.01 USD tolerance) must be embedded in the production code path and fire for every committed cross-border transaction. If the invariant is breached, the transaction must be BLOCKED (not committed), pool_identity_breaches_total Prometheus counter incremented, and a P1 POOL_IDENTITY_FAILURE alert fired immediately (bypassing the 60-second evaluation cycle). This is an inline synchronous alert, not a scheduled one. LOCAL partner same-currency payments skip the USD pool check entirely.
**Steps:** In the rate engine commit path (RateEngine.commitTransaction or TransactionOrchestrator), add an inline check: abs(collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost) > 0.01 USD.; If check fails: increment pool_identity_breaches_total counter, call AlertEvaluationEngine.fireImmediate(AlertFiring.forSystem('POOL_IDENTITY_FAILURE', P1, ctx)) bypassing dedup, throw PoolIdentityException to block commit.; Add fireImmediate() method to AlertEvaluationEngine that dispatches without Redis dedup check.; Skip the check entirely when same-currency short-circuit applies (collection = settle_A = settle_B = payout).; Write PoolIdentityInlineGuardTest: example cross-border txn where collection_usd=36.98, collection_margin_usd=0.74, payout_margin_usd=0.37, payout_usd_cost=35.87 should fail (36.98-0.74-0.37=35.87; 35.87==35.87 so this is a PASS example - use 35.86 for payout_usd_cost to create a breach of 0.01).
**Deliverable:** Inline pool-identity guard in rate engine commit, fireImmediate() on AlertEvaluationEngine, PoolIdentityInlineGuardTest
**Acceptance / logic checks:**
- abs(36.98-0.74-0.37-35.86)=0.01 is NOT > 0.01, so no breach; abs(36.98-0.74-0.37-35.85)=0.02 > 0.01, breach fires P1.
- PoolIdentityException is thrown when breach detected; transaction is not persisted.
- pool_identity_breaches_total counter increments by 1 on each breach.
- fireImmediate() dispatches to PagerDuty and email without checking Redis dedup (P1 fires every time).
- Same-currency txn (KRW->KRW, no USD pool) does not invoke the check; no breach counter incremented.
**Depends on:** 14.7-T09, 14.7-T16

### 14.7-T21 — Implement batch-window monitoring job: check file delivery status and fire breach alerts  _(45 min)_
**Context:** A dedicated scheduled job (BatchWindowMonitorJob, running every 5 minutes from 01:00-12:00 KST) queries the batch_job_runs table and compares the latest run timestamps and delivery statuses against the KST deadline schedule (OPS-13 §7.5.3): ZP0011/ZP0021 by 01:45 KST, ZP0061 by 05:15 KST, ZP0012/ZP0022 received by 05:30 KST, ZP0062 received by 10:30 KST. The job builds an AlertEvaluationContext with batch delivery state keys and calls AlertEvaluationEngine.evaluate(). It also transitions any batch job in FAILED state after 3 retries to trigger BATCH_JOB_FAILED alert.
**Steps:** Create BatchWindowMonitorJob as a Spring @Component with @Scheduled(cron='0 */5 16-03 * * *' in UTC, matching 01:00-12:00 KST window).; Query batch_job_runs for today's runs; for each expected file type compute delivery_confirmed and lag_seconds.; Build AlertEvaluationContext with keys per T07 (batch_file_delivery_lag_seconds_{file_type}, file_delivery_confirmed_{file_type}, batch_job_state_{job_name}, batch_job_retry_count_{job_name}).; Call AlertEvaluationEngine.evaluate(ctx).; Write BatchWindowMonitorJobTest with mocked repositories and engine; verify that at 16:50 UTC (01:50 KST) when ZP0011 is not confirmed, BATCH_ZP0011_MISSED fires.
**Deliverable:** BatchWindowMonitorJob Spring component, unit test BatchWindowMonitorJobTest
**Acceptance / logic checks:**
- At simulated time 16:50 UTC with ZP0011 delivery_confirmed=false, the engine is called with batch_file_delivery_lag_seconds_ZP0011 > 0.
- At simulated time 16:44 UTC (before 01:45 KST deadline), no BATCH_ZP0011_MISSED context key is in a breach state.
- A job in FAILED state with retry_count=3 results in BATCH_JOB_FAILED context key being set.
- Job uses UTC cron and correctly corresponds to 01:00-12:00 KST operating window.
- Job errors are caught and logged; scheduler thread is not interrupted.
**Depends on:** 14.7-T07, 14.7-T16, 14.7-T17

### 14.7-T22 — Implement UNCERTAIN transaction age alert: fire ops alert after 4h, enter manual queue after 24h  _(50 min)_
**Context:** SAD-02 §7.5 states: UNCERTAIN transactions unresolved > 4 hours trigger an ops alert (P2). UNCERTAIN transactions unresolved > 24 hours enter the manual exception queue. Resolution source is ZP0012/ZP0022 batch files received by ~05:00 KST. OPS-13 confirms: alert threshold is 4 hours from SCHEME_SENT timestamp. The alert code is UNCERTAIN_TXN_UNRESOLVED_4H (P2). The 24-hour escalation is separate: UNCERTAIN_TXN_UNRESOLVED_24H (P1) which also calls ExceptionQueueService.enqueue(transactionId).
**Steps:** Create UncertainTransactionMonitorJob as a Spring @Scheduled job running every 15 minutes.; Query transactions WHERE status='UNCERTAIN' AND scheme_sent_at < now() - interval '4 hours'; for each, fire UNCERTAIN_TXN_UNRESOLVED_4H alert (per transaction, with transaction_id in context).; Query transactions WHERE status='UNCERTAIN' AND scheme_sent_at < now() - interval '24 hours'; for each, fire UNCERTAIN_TXN_UNRESOLVED_24H alert and call ExceptionQueueService.enqueue(txnId).; Use dedup key alert:UNCERTAIN_TXN_UNRESOLVED_4H:{transaction_id} with 1-hour TTL to avoid re-alerting every cycle.; Write UncertainTransactionMonitorJobTest: a transaction with scheme_sent_at = now()-5h fires 4h alert; one with now()-25h fires both 4h and 24h alerts.
**Deliverable:** UncertainTransactionMonitorJob, two new alert catalog entries (UNCERTAIN_TXN_UNRESOLVED_4H P2, UNCERTAIN_TXN_UNRESOLVED_24H P1), unit test
**Acceptance / logic checks:**
- Transaction UNCERTAIN for 5 hours fires UNCERTAIN_TXN_UNRESOLVED_4H and is deduped on next 15-min cycle.
- Transaction UNCERTAIN for 25 hours fires UNCERTAIN_TXN_UNRESOLVED_24H and appears in exception queue.
- Transaction UNCERTAIN for 3 hours 59 min fires no alert.
- ExceptionQueueService.enqueue() is called exactly once per transaction per 24h escalation (dedup prevents double-enqueue).
- Add V14_7_003__seed_uncertain_alerts.sql migration adding the 2 new alert_catalog rows.
**Depends on:** 14.7-T16, 14.7-T02

### 14.7-T23 — Implement prefunding deduction anomaly check in payment commit path (inline P2 alert)  _(45 min)_
**Context:** OPS-13 §7.5.2 defines PREFUNDING_DEDUCTION_ANOMALY: a single deduction > USD 50,000 triggers a P2 alert for manual review before commit. This is an inline pre-commit check in the payment processing path at the point where collection_usd is computed. If collection_usd > 50000.0, the system must: (1) fire P2 alert immediately (inline, not waiting for the 60s scheduler), (2) pause the transaction and return HTTP 422 with error code PREFUNDING_DEDUCTION_REVIEW_REQUIRED to the caller. The threshold 50000.0 is configurable via alerting.prefunding.anomaly_threshold_usd (default 50000.0). The alert is informational to Ops - Ops must manually approve or reject via Admin portal (out of scope for this ticket).
**Steps:** In PrefundingService.deductForPayment(), before executing the deduction, check collection_usd against configurable threshold.; If collection_usd > threshold: fire AlertFiring via AlertEvaluationEngine.fireImmediate() with alert_code=PREFUNDING_DEDUCTION_ANOMALY, severity=P2, context containing collection_usd and partner_id.; Return a PrefundingDeductionResult.anomalyBlocked() causing the payment endpoint to return HTTP 422 PREFUNDING_DEDUCTION_REVIEW_REQUIRED.; The prefunding balance is NOT deducted; the scheme is never called.; Write PrefundingDeductionAnomalyTest: collection_usd=50001.0 triggers alert and returns anomalyBlocked; collection_usd=50000.0 proceeds normally.
**Deliverable:** Inline anomaly guard in PrefundingService, HTTP 422 response mapping, PrefundingDeductionAnomalyTest
**Acceptance / logic checks:**
- collection_usd=50000.01 fires P2 PREFUNDING_DEDUCTION_ANOMALY and returns HTTP 422 with error code PREFUNDING_DEDUCTION_REVIEW_REQUIRED.
- collection_usd=50000.00 does NOT trigger the anomaly check (strictly greater-than).
- Prefunding balance is unchanged after anomaly block (SELECT balance before and after confirms no deduction).
- Anomaly alert reaches PagerDuty and email channels (P2 routing).
- Setting alerting.prefunding.anomaly_threshold_usd=100000 in config raises the threshold to 100000.0.
**Depends on:** 14.7-T06, 14.7-T16

### 14.7-T24 — Unit tests: SLO alert condition boundary vectors (parametrized)  _(40 min)_
**Context:** Explicit test ticket for the SLO conditions from T05. Tests must use JUnit 5 @ParameterizedTest with @CsvSource covering exact boundary, below boundary, and above boundary for each of the 5 SLO alert conditions. Numeric examples: p95=499.9 (no fire), p95=500.0 (no fire), p95=500.1 (fire); error_rate=0.999% (no fire), 1.0% (no fire), 1.001% (fire); health_check_failed_seconds=59 (no fire), 60 (no fire), 61 (fire). Total 15 test vectors minimum.
**Steps:** Create SloAlertBoundaryVectorsTest with @ParameterizedTest for each of the 5 SLO conditions.; Add @CsvSource with at least 3 rows per condition (below, at, above threshold).; Assert evaluate() returns expected boolean for each row.; Add a cross-condition test: inject a context where all 5 conditions are simultaneously true and verify all 5 fire.; Add a clean-state test: all metrics well within SLO; verify 0 alerts fire.
**Deliverable:** SloAlertBoundaryVectorsTest with >= 15 parametrized test vectors and 2 integration-style tests
**Acceptance / logic checks:**
- At least 5 x 3 = 15 parametrized vectors pass.
- p95=500.0 does NOT fire (strictly greater-than, not >=).
- health_check_failed_seconds=60 does NOT fire; 61 fires.
- All-conditions-true test fires exactly 5 conditions.
- All-conditions-safe test fires exactly 0 conditions.
**Depends on:** 14.7-T05

### 14.7-T25 — Unit tests: prefunding alert condition boundary vectors (parametrized)  _(35 min)_
**Context:** Explicit test ticket for prefunding conditions from T06. Test vectors must cover: LOCAL vs OVERSEAS partner distinction, exact thresholds for all 4 conditions, and concurrent firing scenarios. Key numeric boundaries: low_balance_threshold=10000.0 (configurable), critical=2000.0, zero=0.0, anomaly=50000.0. Scenario: SendMN balance drops from 2500 to 1999 in a single deduction of 501 -- this should fire PREFUNDING_LOW_BALANCE and PREFUNDING_CRITICAL_BALANCE simultaneously (but NOT PREFUNDING_BALANCE_ZERO).
**Steps:** Create PrefundingAlertBoundaryVectorsTest with @ParameterizedTest covering at-threshold and epsilon-above/below for all 4 conditions.; Add a LOCAL-partner guard test: GME_Remit with balance=0 fires no alerts.; Add a multi-condition simultaneous-fire test: balance=1999.0, threshold=10000.0 should fire both LOW and CRITICAL.; Add a custom-threshold test: partner with low_balance_threshold_usd=5000.0 fires at balance=4999.99 but not at 5000.0.; Verify that PREFUNDING_BALANCE_ZERO fires ONLY at exactly 0.0, not at 0.01.
**Deliverable:** PrefundingAlertBoundaryVectorsTest with >= 12 parametrized vectors
**Acceptance / logic checks:**
- GME_Remit (LOCAL) with balance=0.0 fires 0 conditions.
- SendMN (OVERSEAS) with balance=10000.00 fires 0 conditions; at 9999.99 fires PREFUNDING_LOW_BALANCE.
- balance=1999.99 fires PREFUNDING_LOW_BALANCE and PREFUNDING_CRITICAL_BALANCE but NOT PREFUNDING_BALANCE_ZERO.
- balance=0.0 fires all 3 balance conditions.
- Custom threshold test: partner with threshold=5000 fires LOW at balance=4999.99 but not at 5000.00.
**Depends on:** 14.7-T06

### 14.7-T26 — Unit tests: batch window alert conditions with simulated KST clock  _(40 min)_
**Context:** Explicit test ticket for BatchWindowAlertConditions from T07. Tests must inject a mock Clock to simulate specific KST times and verify correct UTC deadline mapping. ZP0011 deadline: 01:45 KST = 16:45 UTC. ZP0012 deadline: 05:30 KST = 20:30 UTC. ZP0061: 05:15 KST = 20:15 UTC. ZP0062: 10:30 KST = 01:30 UTC next day. Test with both a day that has all files on time and a day where ZP0011 is late but ZP0061 is on time.
**Steps:** Create BatchWindowAlertVectorsTest with a Clock mock injected into BatchWindowAlertConditions.; Test ZP0011_MISSED: simulate clock at 16:44 UTC with delivery=false (no fire), 16:46 UTC with delivery=false (fire), 16:46 UTC with delivery=true (no fire).; Test BATCH_JOB_FAILED: retry_count=2 (no fire), retry_count=3 (fire), retry_count=3 but state=COMPLETED (no fire).; Test KST/UTC conversion: explicitly assert ZP0062 deadline is 01:30 UTC the following day (not same day).; Add a full-day scenario: all files delivered on time, all 7 conditions return false.
**Deliverable:** BatchWindowAlertVectorsTest with >= 12 test vectors
**Acceptance / logic checks:**
- ZP0011_MISSED fires at 16:46 UTC with delivery_confirmed=false.
- ZP0011_MISSED does NOT fire at 16:44 UTC regardless of delivery_confirmed value.
- ZP0011_MISSED does NOT fire at 16:46 UTC when delivery_confirmed=true.
- BATCH_JOB_FAILED requires both state=FAILED AND retry_count>=3.
- ZP0062 deadline correctly maps to 01:30 UTC (UTC = KST - 9h; 10:30 KST = 01:30 UTC).
**Depends on:** 14.7-T07

### 14.7-T27 — Integration test: full alert evaluation cycle with embedded services  _(60 min)_
**Context:** End-to-end integration test that runs the full alerting stack: MetricSnapshotCollector -> AlertEvaluationEngine -> notification channels. Uses Testcontainers for PostgreSQL (port 5433 pattern) and Redis; uses MockWebServer for PagerDuty and Slack webhooks; uses GreenMail for email. Seeds the alert_catalog with the 22 alerts (via migrations). Injects a metric snapshot where PREFUNDING_LOW_BALANCE and BATCH_ZP0011_MISSED are true; verifies PagerDuty and email are called for BATCH (P1) and email for PREFUNDING_LOW_BALANCE (P2), and PagerDuty for P1 batch only.
**Steps:** Create AlertCatalogIntegrationTest as a @SpringBootTest with Testcontainers (PostgreSQL + Redis) and MockWebServer instances for PagerDuty and Slack.; Run Flyway migrations to seed alert_catalog; verify 22 rows loaded.; Construct AlertEvaluationContext with: prefunding_balance_usd_SendMN=9000.0 (low_balance_threshold=10000.0), batch_file_delivery_lag_seconds_ZP0011=120, file_delivery_confirmed_ZP0011=false; current_utc_time past ZP0011 deadline.; Call AlertEvaluationEngine.evaluate(ctx) and assert: PagerDuty MockWebServer received 1 request (BATCH_ZP0011_MISSED P1); email GreenMail received 2 messages (batch P1 + prefunding P2); Slack MockWebServer received 2 messages.; On second evaluate() within dedup TTL, verify 0 new requests to any channel.
**Deliverable:** AlertCatalogIntegrationTest class covering full evaluate() cycle with 2 simultaneous alert firings
**Acceptance / logic checks:**
- 22 alert rows in DB after migration.
- PagerDuty MockWebServer receives exactly 1 POST (for P1 batch alert) in first evaluate().
- GreenMail receives exactly 2 email messages (one P1, one P2) in first evaluate().
- Second evaluate() within dedup window sends 0 new requests to any channel.
- BATCH_ZP0011_MISSED dedup key exists in Redis with TTL > 0 after first evaluate().
**Depends on:** 14.7-T16, 14.7-T17, 14.7-T18, 14.7-T19

### 14.7-T28 — Admin API: GET /internal/v1/alert-catalog endpoint (list all alerts with enabled status)  _(45 min)_
**Context:** GME Ops needs to view and toggle alerts via the Admin portal. The internal API endpoint GET /internal/v1/alert-catalog returns all 22+ alerts with their current enabled status, severity, category, condition_expr, and routing_channel. This is an authenticated internal endpoint (JWT with role ADMIN or OPS; see SEC-09). Response is pageable; default page size 50. Used by the Admin portal §7.5 alert management view (PRD-07).
**Steps:** Create AlertCatalogAdminController in the admin-api module with GET /internal/v1/alert-catalog.; Request params: category (optional filter), enabled (optional boolean filter), page, size.; Response body: Page<AlertCatalogDto> where AlertCatalogDto maps all alert_catalog fields plus last_fired_at (looked up from Redis dedup key existence).; Secure with @PreAuthorize('hasAnyRole(ADMIN, OPS)').; Write AlertCatalogAdminControllerTest with MockMvc: unauthenticated request returns 401, ADMIN token returns 200 with all 22 alerts, category=PREFUNDING filter returns 4 rows.
**Deliverable:** AlertCatalogAdminController, AlertCatalogDto, AlertCatalogAdminControllerTest
**Acceptance / logic checks:**
- GET /internal/v1/alert-catalog without auth returns HTTP 401.
- GET with valid ADMIN JWT returns HTTP 200 with total >= 22 alerts.
- ?category=PREFUNDING filter returns exactly 4 records.
- ?enabled=false filter returns only disabled alerts; after seed migration (all enabled=true), returns 0.
- last_fired_at field is non-null when Redis dedup key is present; null when absent.
**Depends on:** 14.7-T03, 14.7-T16, 14.7-T11

### 14.7-T29 — Admin API: PATCH /internal/v1/alert-catalog/{alertCode}/enable and /disable  _(40 min)_
**Context:** GME Ops must be able to disable an alert (e.g. during planned maintenance) and re-enable it via the Admin API. PATCH /internal/v1/alert-catalog/{alertCode}/disable sets enabled=false; PATCH /internal/v1/alert-catalog/{alertCode}/enable sets enabled=true. Both actions are audit-logged (actor from JWT sub, timestamp, previous enabled value). Disabling a P1 alert requires role ADMIN (not OPS); disabling P2/P3/P4 requires ADMIN or OPS. An alert that is disabled will be skipped by AlertEvaluationEngine in the next evaluation cycle.
**Steps:** Add PATCH /internal/v1/alert-catalog/{alertCode}/enable and /disable to AlertCatalogAdminController.; In the disable handler: check if alert severity is P1; if yes, require ADMIN role; if OPS role, return HTTP 403 with error CANNOT_DISABLE_P1_WITH_OPS_ROLE.; Call AlertCatalogRepository.disable(alertCode) or enable(); the method writes audit log entry.; Return HTTP 200 with updated AlertCatalogDto.; Write test: disable P2 alert with OPS role succeeds (200); disable P1 alert with OPS role returns 403; disable P1 alert with ADMIN role succeeds; audit_log contains the entry after each successful operation.
**Deliverable:** PATCH enable/disable endpoints, P1 role guard, AlertCatalogAdminControllerTest additions
**Acceptance / logic checks:**
- PATCH /disable on P2 alert with OPS JWT returns 200; alert_catalog.enabled = false.
- PATCH /disable on P1 alert with OPS JWT returns 403 CANNOT_DISABLE_P1_WITH_OPS_ROLE.
- PATCH /disable on P1 alert with ADMIN JWT returns 200; enabled = false.
- audit_log row created with entity_type=ALERT_CATALOG, changed_field=enabled, old_value=true, new_value=false.
- PATCH /enable after disable sets enabled=true and creates second audit_log entry.
**Depends on:** 14.7-T28, 14.7-T03

### 14.7-T30 — Admin API: GET /internal/v1/alerts/recent - list recent alert firings from Redis  _(40 min)_
**Context:** GME Ops needs to see which alerts have recently fired (last 24 hours) in the Admin portal. Since fired alert state is tracked in Redis dedup keys (format: alert:{alert_code}:{partner_id_or_SYSTEM}), the recent-alerts endpoint scans Redis for all keys matching alert:* and returns a list of RecentAlertDto: alert_code, partner_id (or SYSTEM), severity, fired_at (derived from Redis key TTL and dedup_window), action_summary. Sorted by fired_at descending. Max 100 entries returned. Authenticated: ADMIN or OPS role.
**Steps:** Create GET /internal/v1/alerts/recent in AlertCatalogAdminController.; Scan Redis for keys matching pattern 'alert:*'; parse alert_code and partner_id from key name.; For each key, look up alert_catalog row to get severity and action_summary; estimate fired_at as now - (dedup_window - remaining_ttl).; Return List<RecentAlertDto> sorted by estimated fired_at descending, limited to 100 entries.; Write RecentAlertsEndpointTest: pre-populate Redis with 3 dedup keys; verify response contains 3 entries with correct alert_code and partner_id parsed from key names.
**Deliverable:** GET /internal/v1/alerts/recent endpoint, RecentAlertDto, RecentAlertsEndpointTest
**Acceptance / logic checks:**
- Endpoint with Redis key alert:PREFUNDING_LOW_BALANCE:SendMN present returns entry with alert_code=PREFUNDING_LOW_BALANCE and partner_id=SendMN.
- Endpoint with Redis key alert:POOL_IDENTITY_FAILURE:SYSTEM present returns entry with partner_id=SYSTEM.
- Response is sorted by estimated fired_at descending.
- Unauthenticated request returns HTTP 401.
- Response never exceeds 100 entries even when > 100 Redis dedup keys exist.
**Depends on:** 14.7-T28, 14.7-T16

### 14.7-T31 — Docs: alert catalog reference table for OPS-13 runbook (Markdown)  _(35 min)_
**Context:** OPS-13 §7.5 requires the alert catalog to be documented in the operational runbook. The runbook must include for each alert: alert_code, category, severity, condition (with numeric threshold), action, routing channel, and which runbook section to follow. This ticket produces the runbook reference table as a Markdown file committed to the repo at docs/ops/alert-catalog-reference.md. The file is generated from the seeded alert_catalog DB records (22 rows) to ensure it stays in sync with the implementation.
**Steps:** Write a build-time Gradle/Maven task or test that queries alert_catalog and generates docs/ops/alert-catalog-reference.md.; Table columns: Alert Code | Category | Severity | Condition | Action | Channel | Runbook Ref.; Include all 22 seeded rows plus the 2 UNCERTAIN_TXN alerts added in T22 (total 24 rows).; Add a CI check: if alert_catalog seed data changes without regenerating the doc, the CI step fails.; Commit the generated docs/ops/alert-catalog-reference.md with the 24-row table to the repository.
**Deliverable:** docs/ops/alert-catalog-reference.md with 24-row table, build task to regenerate, CI check
**Acceptance / logic checks:**
- docs/ops/alert-catalog-reference.md exists in the repo and contains >= 24 rows.
- Every row includes numeric threshold in the Condition column (e.g. 'p95 > 500ms for 5 min').
- POOL_IDENTITY_FAILURE row shows severity=P1, channel=pagerduty+email, runbook ref=8.9.
- CI check fails when a new alert is added to the seed migration but doc is not regenerated.
- PREFUNDING_LOW_BALANCE row shows default threshold 'balance < USD 10,000 (configurable per partner)'.
**Depends on:** 14.7-T22, 14.7-T02


## WBS 14.8 — Backup & DR (RTO/RPO) + drill
### 14.8-T01 — Define DR configuration schema for backup and failover settings  _(45 min)_
**Context:** GMEPay+ is a financial settlement hub requiring formal DR config. OPS-13 specifies: daily full DB backup at 02:00 KST, continuous WAL archiving, 90-day full retention, 7-year WAL archive, secrets vault daily encrypted backup with 1-year retention, audit log daily snapshot with 7-year immutable retention, batch-file 7-year retention. RTO targets: payment processing 4 hours, admin/partner portals 8 hours. RPO: payment processing = 0 (synchronous replication), portals = 15 minutes. Config must be environment-aware (prod/staging/int).
**Steps:** Create dr_config table migration with columns: asset_name VARCHAR(64) PK, backup_method VARCHAR(32), frequency_cron VARCHAR(64), retention_days INT, retention_years INT, storage_type VARCHAR(32) (ENUM: LOCAL_AZ, SECONDARY_AZ, OFFSITE, IMMUTABLE_OFFSITE), encryption VARCHAR(16) DEFAULT AES_256, environment VARCHAR(16); Create DrConfig Java POJO and matching Spring @ConfigurationProperties class DrBackupProperties with nested sections for database, auditLog, batchFiles, secretsVault; Add Flyway migration V14_8_001__dr_config_schema.sql seeding the 5 asset rows from OPS-13 spec; Write DrConfigRepository JPA interface and DrConfigService.getConfig(assetName) returning DrConfig; Document each field with Javadoc citing OPS-13 section references
**Deliverable:** Flyway migration V14_8_001__dr_config_schema.sql + DrConfig entity + DrConfigService with getConfig()
**Acceptance / logic checks:**
- Migration seeds exactly 5 rows: postgresql_full, postgresql_wal, audit_log, batch_files, secrets_vault
- postgresql_full row has retention_days=90, storage_type=OFFSITE, encryption=AES_256
- audit_log row has retention_years=7, storage_type=IMMUTABLE_OFFSITE
- DrConfigService.getConfig(unknown_asset) throws ResourceNotFoundException with asset name in message
- Unit test: load all 5 configs and assert retention values match spec

### 14.8-T02 — Implement PostgreSQL WAL archiving configuration and monitoring  _(50 min)_
**Context:** OPS-13 requires continuous WAL streaming to a secondary AZ object storage bucket. WAL archives are written to object storage in a separate AZ from primary DB. Retention: 30 days full snapshot, 7-year WAL archive. The system uses PostgreSQL on port 5433 in dev (prod on primary + synchronous standby via Patroni). Monitoring alert P1 fires when primary DB connection is refused. WAL lag RPO for primary DB failure is < 1 minute per OPS-13 section 9.3.
**Steps:** Create infrastructure config file infra/postgres/wal_archiving.conf with: wal_level=replica, archive_mode=on, archive_command pointing to the WAL archive bucket, max_wal_senders=5, wal_keep_size=1GB; Create a Spring @Scheduled job WalArchiveMonitor that queries pg_stat_replication and logs replication_lag_bytes and sent_lsn every 60 seconds; Add Prometheus gauge metric db_wal_replication_lag_bytes emitted by WalArchiveMonitor; Add alert rule wal_lag_alert: fire P1 when db_wal_replication_lag_bytes > 104857600 (100 MB) for > 2 minutes; Write unit test WalArchiveMonitorTest verifying metric is emitted and P1 fires on threshold breach
**Deliverable:** infra/postgres/wal_archiving.conf + WalArchiveMonitor Spring component + Prometheus alert rule
**Acceptance / logic checks:**
- wal_archiving.conf contains archive_mode=on and archive_command referencing SECONDARY_AZ bucket path
- WalArchiveMonitor emits db_wal_replication_lag_bytes gauge every 60 seconds in test
- Alert rule fires when lag > 100 MB for 2 minutes and clears when lag returns to 0
- P1 alert includes DB instance identifier and lag value in message body
- Integration test: simulate replication lag > 100 MB and verify alert state transitions to firing
**Depends on:** 14.8-T01

### 14.8-T03 — Implement automated PostgreSQL full backup scheduler  _(55 min)_
**Context:** OPS-13 backup policy: daily full PostgreSQL snapshot at 02:00 KST (= 17:00 UTC previous day; Korea is UTC+09:00, no DST). Backup stored in separate AZ / off-site, encrypted AES-256. Retention: 90 days (delete backups older than 90 days). Backup restoration tested monthly. The backup job must emit a structured log entry and Prometheus metric on completion. Backup failure is a P1 alert.
**Steps:** Create BackupSchedulerService with @Scheduled(cron = '0 0 17 * * *') for 17:00 UTC (= 02:00 KST next day); Implement triggerFullBackup() that invokes pg_basebackup (or cloud-managed equivalent) writing encrypted snapshot to offsite object storage path /backups/postgresql/full/{YYYY-MM-DD}/; Set 90-day retention by calling deleteBackupsOlderThan(90) after successful backup; Emit Prometheus counter db_full_backup_total{status=success|failure} and structured log with duration_seconds, backup_size_bytes, storage_path; Add AlertRule: db_full_backup_total{status=failure} > 0 within 1 hour fires P1 alert BACKUP_FAILURE
**Deliverable:** BackupSchedulerService.java with triggerFullBackup() + retention cleanup + Prometheus metric + P1 alert rule
**Acceptance / logic checks:**
- @Scheduled cron expression resolves to 17:00 UTC verified with CronExpression.parse(...).next(ZonedDateTime.now(UTC))
- triggerFullBackup() writes to path matching pattern /backups/postgresql/full/YYYY-MM-DD/
- deleteBackupsOlderThan(90) removes directories where backup date < today - 90 days; test with 89-day-old backup (kept) and 91-day-old backup (deleted)
- Prometheus metric db_full_backup_total increments with status=success on happy path and status=failure on exception
- P1 alert fires when status=failure and clears after next successful backup
**Depends on:** 14.8-T01

### 14.8-T04 — Implement audit log daily snapshot with 7-year immutable retention  _(50 min)_
**Context:** OPS-13 backup policy: audit log store receives a daily snapshot, retained 7 years (2555 days), stored in off-site immutable storage (object storage with object lock / WORM policy enabled). Audit records capture all config changes with actor, timestamp, previous value. A missed audit log backup is a P2 alert. The audit log is in the audit_events table in PostgreSQL.
**Steps:** Add AuditLogBackupService with @Scheduled(cron = '0 30 16 * * *') (16:30 UTC = 01:30 KST) to run before the main DB backup; Implement exportAuditSnapshot() that queries audit_events WHERE created_at >= TRUNC(now() - interval 1 day) and serialises to NDJSON file audit_log_{YYYY-MM-DD}.ndjson.gz; Upload file to immutable offsite bucket path /backups/audit/{YYYY-MM-DD}/ with object-lock retention set to 2555 days (7 years); Emit metric audit_backup_total{status=success|failure} and structured log entry; Add P2 alert: audit_backup_total{status=failure} fires if any daily backup misses
**Deliverable:** AuditLogBackupService.java + exportAuditSnapshot() + P2 alert rule for missed audit backup
**Acceptance / logic checks:**
- @Scheduled cron fires at 16:30 UTC verified by unit test with mock clock
- exportAuditSnapshot() output file is NDJSON.gz containing only audit_events rows from the previous calendar day (UTC)
- Object-lock retention header on upload is set to exactly 2555 days
- audit_backup_total{status=success} increments after successful upload
- Test: if upload throws IOException, status=failure is recorded and P2 alert fires
**Depends on:** 14.8-T01

### 14.8-T05 — Implement SFTP batch file archiving with 7-year retention  _(55 min)_
**Context:** OPS-13: ZeroPay batch files (ZP0011, ZP0021, ZP0061, ZP0063, ZP0065, ZP0066 and their inbound counterparts) are retained on generation and archived daily to off-site storage for 7 years (2555 days). OPS-13 also states batch files can be regenerated from committed transactions if lost. Object storage is geo-redundant (versioned bucket). Files are stored in encrypted storage volume; SCH-06 specifies file naming conventions.
**Steps:** Create BatchFileArchiveService with @Scheduled(cron = '0 0 18 * * *') (18:00 UTC = 03:00 KST); Implement archiveTodaysBatchFiles() scanning the local batch output directory for any ZP*.txt or ZP*.dat files generated today; For each file: compute SHA-256 checksum, upload to geo-redundant offsite bucket /backups/batch/{YYYY-MM-DD}/{filename} with server-side AES-256 encryption, record batch_file_archive entry (filename, checksum, upload_ts, storage_path) in DB; Enforce 2555-day retention with object-lock on upload; local batch files remain available for 90 days per OPS-13 file_retention_days config; Add P1 alert: batch_file_archive{status=failure} fires if daily archive job fails
**Deliverable:** BatchFileArchiveService.java + batch_file_archive DB table + P1 alert rule
**Acceptance / logic checks:**
- archiveTodaysBatchFiles() correctly identifies all ZP-prefixed files created after 00:00 UTC today
- SHA-256 checksum stored in batch_file_archive matches actual file content (verify with test vector)
- Object-lock retention on upload is 2555 days; AES-256 SSE header present
- If a file already exists at target path (re-run), the job skips without error (idempotent)
- P1 alert fires when upload throws exception; alert clears after successful next run
**Depends on:** 14.8-T01

### 14.8-T06 — Implement secrets vault daily encrypted backup  _(45 min)_
**Context:** OPS-13 secrets vault backup: daily encrypted backup, retained 1 year (365 days), stored in a separate secure location from the primary vault. The vault holds ZeroPay SFTP credentials, partner API credentials, and DB passwords. Managed-service vaults (AWS Secrets Manager, HashiCorp Vault) have built-in redundancy per OPS-13 table 9.1; this job creates an additional cross-account/cross-region encrypted export as a safety net. Backup failure is P2 alert.
**Steps:** Create SecretsVaultBackupService with @Scheduled(cron = '0 0 15 * * *') (15:00 UTC = 00:00 KST); Implement exportVaultSnapshot() listing all secret ARNs/paths in the configured namespace and exporting each secret metadata (NOT plaintext values) plus encrypted ciphertext blob to vault_backup_{YYYY-MM-DD}.json.enc; Store backup to separate secure bucket /backups/secrets/{YYYY-MM-DD}/ with 365-day retention and delete-on-expire lifecycle policy; Record vault_backup_log entry: backup_date, secret_count, status, storage_path in DB; Add P2 alert if secrets_backup_total{status=failure} in past 25 hours
**Deliverable:** SecretsVaultBackupService.java + vault_backup_log DB table + P2 alert
**Acceptance / logic checks:**
- @Scheduled fires at 15:00 UTC; unit test with fixed clock confirms trigger time
- exportVaultSnapshot() lists all secrets in the gmepayplus/ namespace; test with mocked vault client returning 3 secrets verifies backup file contains 3 entries
- Backup file extension is .json.enc (encrypted ciphertext, not plaintext); test asserts no raw secret values appear in file content
- 365-day retention lifecycle policy set on bucket upload call
- P2 alert fires when mock vault client throws VaultException; clears on next success
**Depends on:** 14.8-T01

### 14.8-T07 — Create PostgreSQL replica promotion runbook script for primary DB failure  _(55 min)_
**Context:** OPS-13 section 9.4.1 PostgreSQL Primary Failure: (1) monitoring alert fires when primary DB connection refused; (2) confirm primary is truly down via cloud console; (3) promote replica with pg_ctl promote or cloud-managed equivalent; (4) update DB connection secret in secrets manager to new primary endpoint; (5) trigger rolling restart of all API and Worker services; (6) verify DB connection pool metrics recover; (7) provision new replica from promoted primary. Expected total downtime < 10 minutes (RTO). RPO < 1 minute (WAL lag).
**Steps:** Create shell script ops/dr/promote_primary.sh accepting args: --replica-endpoint, --secret-name, --service-namespace; Script step 1: pg_isready -h $PRIMARY_HOST; if responds, exit with ABORT - primary not down; Script step 2: call cloud DB promote API (aws rds failover-db-cluster or pg_ctl promote) on replica; Script step 3: update secrets manager secret --secret-id $SECRET_NAME with new primary endpoint; Script step 4: kubectl rollout restart deployment -n $SERVICE_NAMESPACE to trigger rolling restart; Script step 5: poll pg_stat_activity on new primary; exit success when connection count > 0 within 5 minutes; Add OPS_DR_001 runbook document to docs/runbooks/DR_Primary_DB_Failure.md citing each step and expected duration
**Deliverable:** ops/dr/promote_primary.sh + docs/runbooks/DR_Primary_DB_Failure.md
**Acceptance / logic checks:**
- Script exits non-zero with message PRIMARY_NOT_DOWN when pg_isready returns 0 on primary host
- Script correctly updates secrets manager with --endpoint argument value (test with mock AWS CLI)
- kubectl rollout restart is called for all 3 service deployments: hub-api, admin-service, batch-worker
- Script polls for connection recovery with 5-minute timeout and exits non-zero on timeout with message PROMOTION_TIMEOUT
- Runbook document lists all 7 steps with expected durations summing to < 10 minutes
**Depends on:** 14.8-T02

### 14.8-T08 — Create full-region DR failover runbook script  _(55 min)_
**Context:** OPS-13 section 9.4.2 Full Region DR Failover: (1) declare DR event, notify Engineering Lead/Ops Lead/GME Management; (2) activate DR environment by updating DNS to DR load balancer; (3) restore PostgreSQL in DR region from latest WAL archive; (4) confirm geo-redundant object storage accessible in DR region; (5) update secrets in DR secrets manager; (6) verify batch worker reaches ZeroPay SFTP from DR region; (7) run smoke tests; (8) declare service restored and communicate to partners. RTO: < 4 hours. Partner communication must go out within 30 minutes of confirmation.
**Steps:** Create ops/dr/full_region_failover.sh with --dr-region, --dns-zone-id, --dr-lb-endpoint, --partner-email-list arguments; Implement step 1: write DR event to incident_log table and print notification message for Engineering Lead/Ops Lead/GME Management; Implement step 2: call DNS update API (Route53 change-resource-record-sets or equivalent) pointing $DNS_ZONE_ID A record to $DR_LB_ENDPOINT; Implement steps 3-6 as documented sub-steps with clear STEP_N: ... log output; Add smoke_test.sh invocation and parse exit code; on success print FAILOVER_COMPLETE with timestamp; Create partner communication template docs/runbooks/DR_Partner_Notification_Template.txt
**Deliverable:** ops/dr/full_region_failover.sh + docs/runbooks/DR_Partner_Notification_Template.txt + incident_log table migration
**Acceptance / logic checks:**
- Script requires all 4 arguments; missing arg causes immediate exit with usage message
- DNS update API call uses the exact DR LB endpoint argument value (verified via mock)
- Script logs each numbered step with timestamp to stdout in format [STEP_N] HH:MM:SS message
- smoke_test.sh exit non-zero causes script to exit with SMOKE_TEST_FAILED before declaring service restored
- Partner notification template includes placeholder for [TIMESTAMP] and service restoration status; file exists at expected path
**Depends on:** 14.8-T07

### 14.8-T09 — Implement DB restore-test environment automation script  _(55 min)_
**Context:** OPS-13 section 9.2: monthly restore test must restore most recent full PostgreSQL snapshot to a dedicated restore-test environment (not production, not staging), apply WAL archives to bring DB to most recent consistent point, run automated integrity test suite verifying transaction counts, prefunding ledger totals, and settlement figures match known checkpoint, document result in DR log, then immediately destroy the restore-test environment. This is a scheduled monthly test, not a real DR event.
**Steps:** Create ops/dr/monthly_restore_test.sh accepting --snapshot-id, --wal-archive-path, --checkpoint-json arguments; Implement spin-up: create ephemeral PostgreSQL instance tagged env=restore-test using infrastructure-as-code (Terraform or cloud CLI); Restore snapshot to ephemeral instance, then apply WAL archives via pg_restore / point-in-time recovery to latest consistent point; Run integrity checks: SELECT COUNT(*) FROM transactions WHERE committed_at IS NOT NULL; SELECT SUM(balance) FROM prefunding_ledger; SELECT SUM(settlement_amount) FROM settlement_batches - compare each value against checkpoint JSON; Write result to dr_restore_log table (test_date, snapshot_id, restore_duration_seconds, integrity_status PASS/FAIL, notes); Destroy ephemeral instance unconditionally in a finally block
**Deliverable:** ops/dr/monthly_restore_test.sh + dr_restore_log DB table migration V14_8_002
**Acceptance / logic checks:**
- Script creates instance with tag env=restore-test and destroys it in finally block even if integrity checks fail
- Integrity check compares exactly 3 values: transaction_count, prefunding_total_usd, settlement_total_krw against checkpoint JSON fields with same names
- FAIL result is written to dr_restore_log with integrity_status=FAIL and notes containing the mismatched field names and actual vs expected values
- PASS result is written to dr_restore_log with restore_duration_seconds > 0
- Test with mock checkpoint JSON where prefunding_total_usd differs: verify status=FAIL and notes mention prefunding_total_usd
**Depends on:** 14.8-T03, 14.8-T04

### 14.8-T10 — Implement DR restore log API endpoint for Ops Admin portal  _(40 min)_
**Context:** OPS-13 requires DR restore test results to be documented in the DR log. GMEPay+ has an Ops Admin portal (PRD-07). DR restore test results are stored in dr_restore_log table (14.8-T09). Ops team needs to view restore history and confirm monthly tests are passing. Admin portal endpoints are authenticated via RBAC; only users with OPS_ADMIN role can access DR log data.
**Steps:** Create DrRestoreLogController with GET /api/admin/dr/restore-log?page=0&size=20 returning paginated list of DrRestoreLogDto (test_date, snapshot_id, restore_duration_seconds, integrity_status, notes); Create GET /api/admin/dr/restore-log/latest returning the most recent entry or 404 if no entries exist; Secure both endpoints with @PreAuthorize(hasRole(OPS_ADMIN)); Add DrRestoreLogService.findLatest() and findAll(Pageable) backed by DrRestoreLogRepository; Return HTTP 200 with empty page when no records exist; return 404 from /latest when table is empty
**Deliverable:** DrRestoreLogController.java + DrRestoreLogService.java + DrRestoreLogDto with endpoints GET /api/admin/dr/restore-log and GET /api/admin/dr/restore-log/latest
**Acceptance / logic checks:**
- GET /api/admin/dr/restore-log with PARTNER_ADMIN token returns HTTP 403
- GET /api/admin/dr/restore-log with OPS_ADMIN token and empty table returns HTTP 200 with content=[] and totalElements=0
- GET /api/admin/dr/restore-log/latest with OPS_ADMIN token and empty table returns HTTP 404
- GET /api/admin/dr/restore-log/latest returns the entry with the most recent test_date when multiple records exist
- Pagination: inserting 25 records and requesting page=1&size=20 returns 5 records
**Depends on:** 14.8-T09

### 14.8-T11 — Implement Redis failover configuration and health monitoring  _(50 min)_
**Context:** NFR-10 section 4.2: Redis must have at least 2 nodes (primary + replica) with automatic failover. OPS-13 section 10.2 deploys Redis in cluster mode with 3 primary + 3 replica nodes in production (minimum). Redis cache is ephemeral and rebuilt on restart (persistence disabled in prod). Circuit breakers for ZeroPay API and SFTP are backed by Redis state. Failover RTO for Redis < 60 seconds per load balancer health check routing.
**Steps:** Configure Redis Sentinel or Redis Cluster in Spring application.yml with spring.redis.cluster.nodes listing 3 primary nodes; add spring.redis.cluster.max-redirects=3; Create RedisHealthIndicator implementing HealthIndicator, querying CLUSTER INFO via RedisTemplate; surface cluster_state:ok vs fail; Add Prometheus gauge redis_cluster_nodes_ok emitting count of nodes with status=online; Add alert rule: redis_cluster_nodes_ok < 3 for > 30 seconds fires P2 alert; redis_cluster_nodes_ok < 2 fires P1; Add integration test RedisFailoverTest that starts embedded Redis, stops one node, and verifies RedisHealthIndicator transitions from UP to DOWN and back within 60 seconds
**Deliverable:** Redis cluster Spring config + RedisHealthIndicator + Prometheus alert rules for Redis node count
**Acceptance / logic checks:**
- spring.redis.cluster.nodes in application-prod.yml lists 3 entries in host:port format
- RedisHealthIndicator returns Health.up() when CLUSTER INFO returns cluster_state:ok and Health.down() when cluster_state:fail
- redis_cluster_nodes_ok gauge emits a numeric value between 0 and 6 (for 3 primary + 3 replica)
- P1 alert fires when redis_cluster_nodes_ok < 2; P2 fires when < 3 but >= 2
- Integration test: stopping one node and waiting 30 seconds results in Health.down() from indicator
**Depends on:** 14.8-T01

### 14.8-T12 — Implement application-instance failover health checks  _(50 min)_
**Context:** NFR-10 section 4.2: no single point of failure in the payment processing path; all components run as at least 2 active instances. OPS-13: application instance failure is handled by load balancer health check routing traffic to healthy instances; failed instance replaced automatically; expected RTO < 60 seconds. Health check endpoint must verify DB connectivity, Redis connectivity, and secrets vault accessibility. Kubernetes liveness and readiness probes must be configured.
**Steps:** Create HealthCheckController with GET /actuator/health/liveness (liveness probe: process alive) and GET /actuator/health/readiness (readiness: DB up, Redis up, vault reachable); Implement ReadinessHealthIndicator that checks: (1) DataSource.getConnection() timeout 2s; (2) RedisTemplate.ping() timeout 1s; (3) SecretsManagerClient.describeSecret() timeout 2s; Configure Kubernetes deployment yaml (deploy/k8s/hub-api-deployment.yaml) with livenessProbe: httpGet /actuator/health/liveness, initialDelaySeconds=30, periodSeconds=10 and readinessProbe: httpGet /actuator/health/readiness, periodSeconds=5; Set minReadySeconds=10 and maxUnavailable=0 in rolling update strategy to ensure zero-downtime deployment; Test: mock DB connection failure and verify readiness returns HTTP 503; liveness returns HTTP 200
**Deliverable:** HealthCheckController.java + ReadinessHealthIndicator.java + deploy/k8s/hub-api-deployment.yaml with liveness/readiness probes
**Acceptance / logic checks:**
- GET /actuator/health/liveness always returns HTTP 200 when JVM is running (even if DB is down)
- GET /actuator/health/readiness returns HTTP 200 only when DB, Redis, and vault checks all pass within timeout
- GET /actuator/health/readiness returns HTTP 503 with {status: DOWN, details: {db: DOWN}} when DataSource.getConnection() throws SQLException
- Kubernetes deployment yaml has maxUnavailable: 0 and minReadySeconds: 10
- Integration test: simulate DB unavailability for 10 seconds; readiness probe fails; restore DB; readiness recovers within 15 seconds
**Depends on:** 14.8-T11

### 14.8-T13 — Implement SFTP failover to standby batch processor  _(55 min)_
**Context:** NFR-10 section 4.2: SFTP client runs on redundant batch processor instances; failover to standby within 5 minutes. ZeroPay batch files must be uploaded with a fixed static egress IP (NAT Gateway) per SAD-02 assumption A-04. The batch worker is single-instance (never auto-scaled) to prevent duplicate ZeroPay submissions. Standby is a warm standby that takes over via distributed lock (Redis SETNX or Kubernetes leader election) if primary batch worker fails.
**Steps:** Configure Kubernetes leader election for BatchWorkerService using a Lease resource in batch-worker-leader-election namespace; Implement BatchLeaderElection component: acquire lease on startup, renew every 10 seconds, release on shutdown; only leader executes SFTP jobs; Add SFTP connectivity health check SftpConnectivityCheck.test() that creates a test connection to ZeroPay SFTP host and closes it; timeout 30 seconds; Add alert: batch_sftp_connectivity{status=failure} fires P1 if SFTP unreachable; includes instruction to verify static egress IP is allowlisted by KFTC; Add integration test: start 2 batch worker instances, kill leader, verify standby acquires leader lease within 5 minutes and resumes SFTP jobs
**Deliverable:** BatchLeaderElection.java + SftpConnectivityCheck.java + Kubernetes Lease config in deploy/k8s/batch-worker-deployment.yaml
**Acceptance / logic checks:**
- Only the leader instance executes scheduled SFTP batch jobs; follower instance logs FOLLOWER_SKIP for each scheduled trigger
- If leader pod is deleted, standby acquires the Kubernetes Lease within 5 minutes (300 seconds)
- SftpConnectivityCheck.test() returns false (not throws) when host is unreachable, within 30-second timeout
- P1 alert fires when batch_sftp_connectivity{status=failure} and message contains static egress IP allowlist check instruction
- batch-worker Kubernetes deployment has maxUnavailable: 0 and replicas: 2 (primary + standby)
**Depends on:** 14.8-T12

### 14.8-T14 — Implement secrets vault fallback to cached credentials  _(50 min)_
**Context:** OPS-13 section 6.3 Failover: if secrets vault is unavailable, application falls back to cached credentials for short-term operation; manual vault recovery procedure governs restoration; expected RTO < 2 hours. The vault stores ZeroPay SFTP credentials, partner API credentials, and DB passwords. Cache must be in-memory (not persisted to disk). Cache TTL must be bounded so stale credentials expire after vault recovery. Cache hit/miss must be observable via metrics.
**Steps:** Create SecretsCacheService wrapping the vault client with a Caffeine cache: maximumSize=100, expireAfterWrite=7200 seconds (2-hour TTL matching RTO); Implement SecretsCacheService.getSecret(String secretName) that first checks cache; on cache miss calls vault; on vault exception returns cached value if present, else throws VaultUnavailableException; Add Prometheus counter secrets_cache_requests_total{result=hit|miss|vault_error|cache_fallback}; Add structured log when cache_fallback is used: warn level with secretName (sanitised, no values) and vault error class name; Write unit test: verify that when vault throws exception and cache has value, getSecret() returns cached value and increments cache_fallback counter
**Deliverable:** SecretsCacheService.java with Caffeine-backed cache + Prometheus counter + unit test for fallback path
**Acceptance / logic checks:**
- SecretsCacheService.getSecret() returns vault value on first call and increments cache_miss counter
- SecretsCacheService.getSecret() returns cached value on second call within TTL and increments cache_hit counter
- When vault throws VaultException and cache has a non-expired entry, returns cached value and increments cache_fallback counter
- When vault throws VaultException and cache is empty, throws VaultUnavailableException with secretName in message
- Cache expireAfterWrite is 7200 seconds; test with mock clock verifying entry expires after 7200s and next call re-fetches from vault
**Depends on:** 14.8-T08

### 14.8-T15 — Implement prefunding balance recovery verification after DR restore  _(55 min)_
**Context:** OPS-13 DR drill requirements (QA-12 section 8.3): after DB restore from backup, prefunding balances must be correctly recovered to last committed state. Prefunding is deducted atomically using SELECT FOR UPDATE on the prefunding_ledger table. RPO for payment processing = 0 (synchronous replication means no committed transactions lost). After failover, the system must verify: (1) prefunding_ledger balances match sum of committed debits for each OVERSEAS partner; (2) no PENDING_DEBIT transactions are left in an ambiguous state.
**Steps:** Create DrIntegrityVerificationService.verifyPrefundingIntegrity() that runs: SELECT partner_id, balance FROM prefunding_ledger FOR UPDATE and cross-checks against SELECT partner_id, SUM(deduction_amount) FROM payment_transactions WHERE status=DEBITED GROUP BY partner_id; Report discrepancies where |ledger_balance - expected_balance| > 0.01 USD as INTEGRITY_BREACH events in dr_integrity_log table; Create GET /api/admin/dr/integrity/prefunding endpoint returning per-partner verification results with status PASS|FAIL and discrepancy_usd; Run verifyPrefundingIntegrity() automatically on application startup if a DR_RESTORE_IN_PROGRESS flag is set in the dr_config table; Add unit test with SendMN partner: seed ledger balance=100.00 USD, seed 2 DEBITED transactions of 40.00 and 55.00 USD; verify discrepancy=5.00 USD detected and status=FAIL
**Deliverable:** DrIntegrityVerificationService.java + dr_integrity_log table + GET /api/admin/dr/integrity/prefunding endpoint
**Acceptance / logic checks:**
- verifyPrefundingIntegrity() with matching balances returns all partners with status=PASS and discrepancy_usd=0.00
- verifyPrefundingIntegrity() with ledger balance 100.00 and DEBITED sum 95.00 returns discrepancy_usd=5.00 and status=FAIL
- INTEGRITY_BREACH event is written to dr_integrity_log with partner_id, expected_balance, actual_balance, discrepancy_usd
- GET /api/admin/dr/integrity/prefunding returns HTTP 403 for non-OPS_ADMIN tokens
- Startup auto-run: when DR_RESTORE_IN_PROGRESS=true in dr_config, verifyPrefundingIntegrity() is called before application accepts traffic (SmartLifecycle with phase < 0)
**Depends on:** 14.8-T09, 14.8-T10

### 14.8-T16 — Create DR drill execution plan and checklist document  _(35 min)_
**Context:** OPS-13 section 9.5: annual DR drill involves (1) failing over to DR region in non-production window; (2) running all smoke tests and a simulated batch job run; (3) measuring actual RTO and comparing against 4-hour target; (4) updating runbook if deviations found. QA-12 section 8.3: DR test must be performed in staging before go-live UAT sign-off. Drill must confirm: recovery from primary DB failure within RTO; ZeroPay SFTP credentials and batch-job state restored; prefunding balances recovered to last committed state.
**Steps:** Create docs/runbooks/DR_Drill_Execution_Plan.md with dated section for annual drill; Document pre-drill prerequisites: DR environment provisioned, WAL archives accessible from DR region, smoke test suite passing in staging; Document drill steps in order: (1) Announce maintenance window; (2) Run promote_primary.sh in staging; (3) Run full_region_failover.sh against staging; (4) Execute monthly_restore_test.sh; (5) Run verifyPrefundingIntegrity(); (6) Run smoke test suite; (7) Execute simulated ZP0011 batch; (8) Record actual RTO start/end timestamps; (9) Restore normal state; (10) File DR_Drill_Report; Create DR_Drill_Report_Template.md with fields: drill_date, participants, rto_start, rto_end, actual_rto_minutes, target_rto_minutes (240), status PASS|FAIL, deviations, runbook_updates_required; Add acceptance criteria: drill PASS if actual_rto_minutes <= 240 and all 3 QA-12 confirmations are met
**Deliverable:** docs/runbooks/DR_Drill_Execution_Plan.md + docs/runbooks/DR_Drill_Report_Template.md
**Acceptance / logic checks:**
- DR_Drill_Execution_Plan.md contains exactly 10 numbered drill steps in order matching OPS-13 section 9.4.2
- DR_Drill_Report_Template.md has field actual_rto_minutes and target_rto_minutes=240
- Template includes all 3 QA-12 confirmation checkboxes: DB recovery within RTO, SFTP credentials restored, prefunding balances verified
- Execution plan references promote_primary.sh and full_region_failover.sh scripts by exact file path
- Execution plan specifies non-production window requirement and instructs recording RTO start timestamp at step 2 and end at step 6
**Depends on:** 14.8-T07, 14.8-T08, 14.8-T15

### 14.8-T17 — Implement DR drill smoke test suite  _(55 min)_
**Context:** OPS-13 section 9.5 DR drill requires running all smoke tests after failover. QA-12 section 8.3 requires DR test in staging before go-live. Smoke tests must verify: (1) GET /v1/rates returns HTTP 200 for a valid rule (GME Remit KRW/KRW); (2) POST /v1/payments returns HTTP 201 for a minimal valid MPM payment; (3) Admin portal GET /api/admin/partners returns HTTP 200; (4) ZeroPay SFTP connectivity from DR region succeeds; (5) Prefunding integrity check returns all PASS. Tests must run in < 10 minutes total.
**Steps:** Create src/test/java/smoke/DrSmokeSuite.java as a JUnit 5 test class tagged @Tag(smoke) and @Tag(dr); Implement test_rateQuote: GET /v1/rates?partner_id=GME_REMIT&direction=DOMESTIC&target_payout=10000&payout_ccy=KRW expects HTTP 200 and collection_amount > 0; Implement test_mpmPayment: POST /v1/payments with minimal MPM payload for GME_REMIT partner expects HTTP 201 and txn_ref matching pattern GME-[0-9]+-[A-Z0-9]+; Implement test_adminHealth: GET /api/admin/partners with OPS_ADMIN token expects HTTP 200 and non-empty array; Implement test_sftpConnectivity: instantiate SftpConnectivityCheck and call test() expecting true; Implement test_prefundingIntegrity: GET /api/admin/dr/integrity/prefunding with OPS_ADMIN token expects all partners status=PASS; Suite must complete in under 10 minutes; add @Timeout(600) on class
**Deliverable:** src/test/java/smoke/DrSmokeSuite.java with 5 smoke test methods
**Acceptance / logic checks:**
- @Tag(smoke) and @Tag(dr) annotations present on class; @Timeout(600) present on class
- test_rateQuote asserts HTTP 200 and response body contains field collection_amount with numeric value > 0
- test_mpmPayment asserts HTTP 201 and txn_ref matches regex GME-[0-9]+-[A-Z0-9]+
- test_sftpConnectivity calls SftpConnectivityCheck.test() and fails test if it returns false
- test_prefundingIntegrity fails the test if any partner in the response has status != PASS
**Depends on:** 14.8-T15, 14.8-T13

### 14.8-T18 — Implement RTO measurement and recording for DR drills  _(45 min)_
**Context:** OPS-13 section 9.5 DR drill requires measuring actual RTO and comparing against target (4 hours = 240 minutes for payment processing). The DR drill execution plan (14.8-T16) instructs recording rto_start at step 2 (invoke promote_primary.sh) and rto_end at step 6 (smoke tests pass). RTO is defined as elapsed time from failover initiation to confirmed service restoration. Results must be stored in dr_drill_log table for audit and trend tracking. Drill history must be viewable from the Ops Admin portal.
**Steps:** Create dr_drill_log table migration V14_8_003: (id BIGSERIAL PK, drill_date DATE, rto_start_utc TIMESTAMPTZ, rto_end_utc TIMESTAMPTZ, actual_rto_seconds INT GENERATED ALWAYS AS EXTRACT(EPOCH FROM rto_end_utc - rto_start_utc), target_rto_seconds INT DEFAULT 14400, status VARCHAR(8) CHECK IN (PASS, FAIL), deviations TEXT, participants TEXT, created_by VARCHAR(128)); Create DrDrillLogService.startDrill(participants) returning drill_id and recording rto_start_utc=now(); Create DrDrillLogService.completeDrill(drillId, deviations) recording rto_end_utc=now(); auto-compute status: PASS if actual_rto_seconds <= 14400; Create GET /api/admin/dr/drills endpoint returning paginated drill history with fields: drill_date, actual_rto_seconds, target_rto_seconds, status, deviations; Secure endpoint with @PreAuthorize(hasRole(OPS_ADMIN))
**Deliverable:** V14_8_003__dr_drill_log.sql migration + DrDrillLogService.java + GET /api/admin/dr/drills endpoint
**Acceptance / logic checks:**
- actual_rto_seconds generated column equals EXTRACT(EPOCH FROM rto_end_utc - rto_start_utc) verified with test inserting rto_start=T0 and rto_end=T0+3600s expecting actual_rto_seconds=3600
- completeDrill() sets status=PASS when actual_rto_seconds=14399 and status=FAIL when actual_rto_seconds=14401
- GET /api/admin/dr/drills returns HTTP 403 for PARTNER_ADMIN token
- GET /api/admin/dr/drills returns HTTP 200 with correct pagination for OPS_ADMIN token
- Unit test: startDrill() and completeDrill() within 1 second returns status=PASS and actual_rto_seconds between 0 and 5
**Depends on:** 14.8-T16

### 14.8-T19 — Implement pool identity reconciliation job for DR verification  _(50 min)_
**Context:** NFR-10 section 5.5: a daily reconciliation job checks the pool identity invariant for all cross-border transactions committed in the previous 24 hours. Pool identity: collection_usd - collection_margin_usd - payout_margin_usd = payout_usd_cost within tolerance 0.01 USD. Any breach is logged as a P1 alert. This job must also run as part of the DR drill integrity checks (14.8-T09 includes settlement_total but not pool-level invariant). OPS-13 section 5.5 states breach triggers immediate investigation.
**Steps:** Create PoolIdentityReconciliationJob with @Scheduled(cron = '0 0 6 * * *') (06:00 UTC = 15:00 KST daily); Query: SELECT txn_ref, collection_usd, collection_margin_usd, payout_margin_usd, payout_usd_cost FROM transactions WHERE direction != DOMESTIC AND committed_at >= NOW() - INTERVAL 24 HOURS; For each row compute: lhs = collection_usd - collection_margin_usd - payout_margin_usd; tolerance check: ABS(lhs - payout_usd_cost) > 0.01; Write breaching rows to pool_identity_breach_log table: (txn_ref, collection_usd, collection_margin_usd, payout_margin_usd, payout_usd_cost, computed_lhs, deviation_usd, detected_at); Fire P1 alert pool_identity_breach{count=N} if any breaches found; include txn_refs in alert details
**Deliverable:** PoolIdentityReconciliationJob.java + pool_identity_breach_log table migration V14_8_004 + P1 alert rule
**Acceptance / logic checks:**
- Job runs at 06:00 UTC; cron expression verified with CronExpression.parse('0 0 6 * * *').next(now)
- Test with txn: collection_usd=105.00, collection_margin_usd=3.00, payout_margin_usd=2.00, payout_usd_cost=100.00; lhs=100.00; ABS(100.00-100.00)=0.00 <= 0.01 - no breach recorded
- Test with txn: collection_usd=105.00, collection_margin_usd=3.00, payout_margin_usd=2.00, payout_usd_cost=99.98; lhs=100.00; ABS(100.00-99.98)=0.02 > 0.01 - breach recorded with deviation_usd=0.02
- P1 alert message contains count of breaching transactions and first 5 txn_refs
- DOMESTIC direction transactions (same-currency short-circuit) are excluded from the query
**Depends on:** 14.8-T09

### 14.8-T20 — Unit tests for backup scheduler services (BackupSchedulerService, AuditLogBackupService)  _(50 min)_
**Context:** 14.8-T03 implements BackupSchedulerService (daily full DB backup at 17:00 UTC). 14.8-T04 implements AuditLogBackupService (daily audit snapshot at 16:30 UTC). Both must emit Prometheus metrics and fire P1/P2 alerts on failure. Tests must use fixed clocks and mocked storage clients. Test vectors: (1) successful backup completes in < 300 seconds; (2) storage upload IOException causes status=failure metric; (3) 91-day-old backup is deleted; 89-day-old backup is kept.
**Steps:** Create BackupSchedulerServiceTest with @MockBean for the object storage client and a fixed Clock at 17:00 UTC; Test triggerFullBackup_success: mock upload returns success; assert db_full_backup_total{status=success} incremented by 1; assert structured log contains duration_seconds and backup_size_bytes; Test triggerFullBackup_uploadFails: mock upload throws IOException; assert db_full_backup_total{status=failure} incremented; assert P1 alert mock was called; Test deleteBackupsOlderThan_boundary: create directory list with dates today-89 and today-91; assert only today-91 directory is deleted; Create AuditLogBackupServiceTest testing exportAuditSnapshot_onlyYesterdayRows: seed 3 audit rows (2 yesterday, 1 today); assert output NDJSON contains exactly 2 rows
**Deliverable:** BackupSchedulerServiceTest.java + AuditLogBackupServiceTest.java
**Acceptance / logic checks:**
- triggerFullBackup_success test passes with db_full_backup_total{status=success} = 1 and db_full_backup_total{status=failure} = 0
- triggerFullBackup_uploadFails test passes with db_full_backup_total{status=failure} = 1
- deleteBackupsOlderThan_boundary: directory for today-89 exists after cleanup; directory for today-91 does not exist
- exportAuditSnapshot_onlyYesterdayRows: output file contains 2 NDJSON lines and NOT the row from today
- All tests run without real DB or storage connections (all mocked)
**Depends on:** 14.8-T03, 14.8-T04

### 14.8-T21 — Unit tests for DR failover runbook scripts  _(45 min)_
**Context:** 14.8-T07 implements promote_primary.sh. 14.8-T08 implements full_region_failover.sh. Scripts invoke cloud CLI commands and must be testable. Tests use bats-core (bash test framework) or equivalent. Key logic: promote_primary.sh must abort if primary is still healthy; full_region_failover.sh must abort if smoke tests fail. These are safety invariants that must not regress.
**Steps:** Create ops/dr/test/test_promote_primary.bats using bats-core; Test abort_if_primary_alive: mock pg_isready to return 0 (primary alive); run promote_primary.sh and assert exit code non-zero and output contains PRIMARY_NOT_DOWN; Test proceeds_when_primary_down: mock pg_isready to return 1 (connection refused); mock pg_ctl and aws CLI to succeed; assert exit code 0 and output contains PROMOTION_COMPLETE; Create ops/dr/test/test_full_region_failover.bats; Test aborts_on_smoke_test_failure: mock smoke_test.sh to exit 1; assert full_region_failover.sh exits non-zero with SMOKE_TEST_FAILED; Test proceeds_on_smoke_test_pass: mock all cloud CLI calls and smoke_test.sh exit 0; assert FAILOVER_COMPLETE in output
**Deliverable:** ops/dr/test/test_promote_primary.bats + ops/dr/test/test_full_region_failover.bats (6 bats tests total)
**Acceptance / logic checks:**
- abort_if_primary_alive test: exit code is non-zero and stdout matches PRIMARY_NOT_DOWN
- proceeds_when_primary_down test: exit code is 0 and stdout matches PROMOTION_COMPLETE
- aborts_on_smoke_test_failure test: exit code non-zero and stdout matches SMOKE_TEST_FAILED
- proceeds_on_smoke_test_pass test: exit code 0 and stdout matches FAILOVER_COMPLETE
- All 6 tests pass when run with: bats ops/dr/test/
**Depends on:** 14.8-T07, 14.8-T08

### 14.8-T22 — Unit tests for pool identity reconciliation and prefunding integrity jobs  _(50 min)_
**Context:** 14.8-T15 implements DrIntegrityVerificationService.verifyPrefundingIntegrity(). 14.8-T19 implements PoolIdentityReconciliationJob. Both perform financial invariant checks; test coverage is mandatory per the ticket brief. Test vectors from RATE-04 brief: m_a=2%, m_b=1%, collection_usd=105.00, collection_margin_usd=2.10 (2%*105), payout_margin_usd=1.05 (1%*105), payout_usd_cost=101.85 - check: 105.00-2.10-1.05=101.85 deviation=0.00 (PASS). Breach vector: deviation=0.02 USD (FAIL).
**Steps:** Create PoolIdentityReconciliationJobTest with in-memory H2 DB; Test no_breach_vector: insert txn with collection_usd=105.00, collection_margin_usd=2.10, payout_margin_usd=1.05, payout_usd_cost=101.85; run job; assert pool_identity_breach_log is empty; Test breach_vector: insert txn with payout_usd_cost=101.83 (deviation=0.02); run job; assert pool_identity_breach_log has 1 row with deviation_usd=0.02 and P1 alert was triggered; Test domestic_excluded: insert DOMESTIC txn with any values; run job; assert breach_log is still empty; Create DrIntegrityVerificationServiceTest; Test sendmn_discrepancy: seed prefunding_ledger balance=100.00 for SENDMN, seed DEBITED transactions summing to 95.00; assert verifyPrefundingIntegrity() returns SENDMN with status=FAIL and discrepancy_usd=5.00
**Deliverable:** PoolIdentityReconciliationJobTest.java + DrIntegrityVerificationServiceTest.java
**Acceptance / logic checks:**
- no_breach_vector test passes: breach_log empty, P1 alert not triggered
- breach_vector test passes: breach_log has 1 row with deviation_usd=0.02, txn_ref matches inserted row, P1 alert mock called once
- domestic_excluded test passes: DOMESTIC direction txn is not checked, breach_log empty
- sendmn_discrepancy test passes: verifyPrefundingIntegrity returns FAIL with discrepancy_usd=5.00 for SENDMN
- All 5 tests use decimal arithmetic (BigDecimal); no floating-point comparison in assertions
**Depends on:** 14.8-T15, 14.8-T19

### 14.8-T23 — Integration test: monthly restore test flow end-to-end  _(55 min)_
**Context:** 14.8-T09 implements monthly_restore_test.sh and the dr_restore_log DB table. The integrity check verifies transaction counts, prefunding ledger totals, and settlement figures against a checkpoint JSON. This integration test runs the full monthly restore test against a local PostgreSQL test container (Testcontainers) to validate the logic without cloud infrastructure. It must verify PASS when data matches and FAIL when data diverges.
**Steps:** Create MonthlyRestoreTestIntegrationTest using @Testcontainers and PostgreSQLContainer; Setup: create schema, insert 5 committed transactions with known amounts, insert prefunding_ledger with balance=200.00 for SENDMN, insert settlement_batch with total_krw=500000; Create checkpoint JSON file src/test/resources/dr_checkpoint.json: {transaction_count:5, prefunding_total_usd:200.00, settlement_total_krw:500000}; Test restore_pass: call MonthlyRestoreTestService.runIntegrityChecks(checkpointJson) and assert DrRestoreLog status=PASS; Test restore_fail_transaction_count: insert 1 extra transaction; update checkpoint to transaction_count=5; run checks; assert status=FAIL and notes contains transaction_count
**Deliverable:** MonthlyRestoreTestIntegrationTest.java with restore_pass and restore_fail_transaction_count tests
**Acceptance / logic checks:**
- restore_pass test passes: DrRestoreLog status=PASS and no mismatch fields in notes
- restore_fail_transaction_count test passes: status=FAIL and notes string contains the word transaction_count
- Both tests run against real PostgreSQL (Testcontainers) not H2 to ensure WAL/decimal behavior is accurate
- Test setup verifies that prefunding_total_usd=200.00 matches sum of DEBITED transactions (200.00 total from seeded data)
- Test completes in under 60 seconds total
**Depends on:** 14.8-T09

### 14.8-T24 — Add DR/backup monitoring dashboard configuration  _(45 min)_
**Context:** OPS-13 section 7 (Observability) and section 12.1.6 require all alert catalog entries configured, dashboards reviewed by Ops team, and log aggregation confirmed. For DR/backup monitoring, the Grafana dashboard must surface: (1) backup job status history per asset; (2) WAL replication lag; (3) Redis cluster node count; (4) pool identity breach count last 7 days; (5) last DR drill RTO vs target. Prometheus is the metrics backend per SAD-02/OPS-13.
**Steps:** Create monitoring/grafana/dashboards/dr_backup_dashboard.json as a Grafana dashboard JSON with 5 panels; Panel 1 backup_job_status: table panel querying db_full_backup_total and audit_backup_total grouped by status; last 24 hours; Panel 2 wal_replication_lag: time-series of db_wal_replication_lag_bytes with red threshold line at 104857600 (100 MB); Panel 3 redis_cluster_nodes: stat panel showing current redis_cluster_nodes_ok value; colour green >= 3, amber 2, red < 2; Panel 4 pool_identity_breaches: bar chart of pool_identity_breach count last 7 days; Panel 5 dr_drill_rto: table of last 3 drill results with actual_rto_seconds and target_rto_seconds=14400 (4 hours); Set dashboard UID=gmepayplus-dr-backup and tag with dr,backup,ops
**Deliverable:** monitoring/grafana/dashboards/dr_backup_dashboard.json with 5 panels and UID gmepayplus-dr-backup
**Acceptance / logic checks:**
- JSON is valid Grafana dashboard format with uid=gmepayplus-dr-backup and tags containing dr and backup
- Panel 1 datasource references Prometheus and uses db_full_backup_total metric
- Panel 2 threshold is set to 104857600 bytes and marked red
- Panel 3 green threshold at 3, amber at 2, red at 1 (reflects NFR-10 minimum 2-node Redis)
- Dashboard imports successfully into Grafana (validate with grafana dashboard JSON schema or by importing into local Grafana instance)
**Depends on:** 14.8-T02, 14.8-T03, 14.8-T11, 14.8-T19

### 14.8-T25 — Write OPS-13 runbook section for Backup and DR procedures  _(45 min)_
**Context:** OPS-13 is the operational runbook for GMEPay+. Section 9 covers Backup and Disaster Recovery. Runbook must be self-contained for an on-call engineer with zero prior context. It must cover: backup schedule/retention table, monthly restore test procedure, RTO/RPO table, PostgreSQL primary failure runbook (< 10 min RTO), full-region failover runbook (< 4 hour RTO), DR drill annual schedule, and references to script paths. Per OPS-13 section 7.5 alert catalog, P1 alerts require 15-minute response.
**Steps:** Create docs/runbooks/OPS_13_Section9_Backup_DR.md as the official runbook section; Include backup policy table with all 6 assets from NFR-10: postgresql_full (daily 02:00 KST, 90d, offsite), postgresql_wal (continuous, 7yr), audit_log (daily, 7yr immutable), batch_files (daily, 7yr), secrets_vault (daily, 1yr), container_images (on push, 1yr); Include RTO/RPO table from NFR-10: single AZ (RPO=0, RTO<5min), primary DB (RPO<1min, RTO<10min), full region (RPO<15min, RTO<4hr), batch file loss (RPO=0, RTO<30min); Include step-by-step PostgreSQL failure procedure referencing ops/dr/promote_primary.sh with expected output; Include full-region failover procedure referencing ops/dr/full_region_failover.sh with partner communication template path; Include annual DR drill section: schedule (pre go-live UAT + annually), checklist reference to DR_Drill_Execution_Plan.md, pass criteria actual_rto_minutes <= 240
**Deliverable:** docs/runbooks/OPS_13_Section9_Backup_DR.md (the authoritative Backup and DR runbook section)
**Acceptance / logic checks:**
- Document contains backup policy table with all 6 assets and correct retention values
- Document contains RTO/RPO table with all 4 scenarios and numeric targets
- PostgreSQL failure procedure references exact script path ops/dr/promote_primary.sh
- Full-region failover procedure references ops/dr/full_region_failover.sh and docs/runbooks/DR_Partner_Notification_Template.txt
- DR drill section states pass criterion as actual_rto_minutes <= 240 and references DR_Drill_Execution_Plan.md by name
**Depends on:** 14.8-T07, 14.8-T08, 14.8-T16, 14.8-T18


## WBS 16.1 — Go-live readiness & cutover plan
### 16.1-T01 — Define go-live readiness checklist schema (DB table + domain model)  _(45 min)_
**Context:** WBS 16.1 — Go-live readiness and cutover plan. OPS-13 §12.1 defines 8 checklist categories (Infrastructure, Application, ZeroPay Integration, Partners, Merchant Data, Monitoring, Security, Operational Readiness) each with multiple boolean items. The checklist must record signoffs by Release Manager, Engineering Lead, Ops Lead, and Security Lead before live traffic is accepted. Store in DB so progress is auditable.
**Steps:** Create migration db/migrations/V9001__create_golive_checklist.sql with table go_live_checklist_items (id UUID PK, category VARCHAR(64), item_code VARCHAR(64) UNIQUE, description TEXT, is_checked BOOLEAN DEFAULT false, checked_by VARCHAR(128), checked_at TIMESTAMPTZ, notes TEXT, created_at TIMESTAMPTZ DEFAULT now()).; Create table go_live_signoffs (id UUID PK, role VARCHAR(64) NOT NULL, signer_name VARCHAR(128), signed_at TIMESTAMPTZ, checklist_snapshot JSONB) for the four mandatory signatories.; Define Java record GoLiveChecklistItem and enum ChecklistCategory with values INFRASTRUCTURE, APPLICATION, ZEROPAY_INTEGRATION, PARTNERS, MERCHANT_DATA, MONITORING, SECURITY, OPERATIONAL_READINESS.; Add repository interface GoLiveChecklistRepository with methods findAll(), findByCategory(ChecklistCategory), markChecked(itemCode, actor), and countUnchecked().; Seed the 8 categories and all items from OPS-13 §12.1 via a Flyway repeatable seed migration R__seed_golive_checklist_items.sql.
**Deliverable:** Migration V9001 + repeatable seed R__seed_golive_checklist_items.sql + GoLiveChecklistItem domain record + GoLiveChecklistRepository interface
**Acceptance / logic checks:**
- Migration applies cleanly on a fresh schema; SELECT COUNT(*) FROM go_live_checklist_items returns >= 30 rows (covering all OPS-13 §12.1 items).
- Each of the 8 categories has at least 3 items seeded; no item_code is duplicated.
- markChecked('INFRA_MULTI_AZ', 'alice') sets is_checked=true, checked_by='alice', checked_at to current timestamp; a second call is idempotent (no duplicate row, updated timestamp).
- countUnchecked() returns 0 only when every item has is_checked=true.
- go_live_signoffs table enforces UNIQUE constraint on role (only one live signoff per role at a time).

### 16.1-T02 — Implement GoLiveChecklistService — mark, unmark, and category-summary logic  _(40 min)_
**Context:** OPS-13 §12.1 checklist items are checked individually. The service must support per-item mark/unmark (with actor identity for audit), a category-complete check (all items in a category are checked), an overall-complete check (all 8 categories complete), and a summary view (category -> checked_count / total_count). Config changes must log actor, timestamp, previous value per the canonical audit rule.
**Steps:** Create GoLiveChecklistService with markItem(String itemCode, String actor) calling GoLiveChecklistRepository.markChecked and writing an audit log entry (entity=GOLIVE_CHECKLIST, field=itemCode, prev=false, new=true, actor, timestamp).; Add unmarkItem(String itemCode, String actor) similarly with prev=true, new=false.; Add isCategoryComplete(ChecklistCategory cat) returning true iff countUnchecked in that category == 0.; Add isReadyForGoLive() returning true iff all 8 categories are complete AND go_live_signoffs has a row for each of the four required roles: RELEASE_MANAGER, ENGINEERING_LEAD, OPS_LEAD, SECURITY_LEAD.; Add getCategorySummary() returning Map<ChecklistCategory, ChecklistProgress> where ChecklistProgress holds checkedCount and totalCount.
**Deliverable:** GoLiveChecklistService.java with methods: markItem, unmarkItem, isCategoryComplete, isReadyForGoLive, getCategorySummary
**Acceptance / logic checks:**
- markItem writes audit log entry with prev=false, new=true, actor='ops_user', and matching itemCode.
- isReadyForGoLive() returns false if any item is unchecked, even if all four signoffs exist.
- isReadyForGoLive() returns false if all items are checked but fewer than 4 signoffs exist.
- getCategorySummary() for INFRASTRUCTURE returns checkedCount <= totalCount and totalCount >= 3.
- Calling markItem on an already-checked item is idempotent (no duplicate audit entry, updated checked_at).
**Depends on:** 16.1-T01

### 16.1-T03 — Build pre-go-live checklist REST endpoints (GET summary, PATCH item, POST signoff)  _(45 min)_
**Context:** OPS-13 §12.1 requires the Release Manager, Engineering Lead, Ops Lead, and Security Lead to sign off before live traffic. Admin System must expose: GET /admin/v1/golive/checklist (category summary + all items), PATCH /admin/v1/golive/checklist/{itemCode} (mark/unmark), POST /admin/v1/golive/signoff (record role signoff). Only users with role OPS_ADMIN or above may call these endpoints (SEC-09 auth model).
**Steps:** Create GoLiveChecklistController at /admin/v1/golive with @PreAuthorize('hasRole(OPS_ADMIN)').; GET /checklist: call getCategorySummary() and return list of {category, checkedCount, totalCount, items[]} with HTTP 200.; PATCH /checklist/{itemCode}: accept JSON body {checked: bool, notes: string}, call markItem or unmarkItem with authenticated actor, return updated item DTO with HTTP 200; return 404 if itemCode not found.; POST /signoff: accept {role, signerName} (role must be one of RELEASE_MANAGER, ENGINEERING_LEAD, OPS_LEAD, SECURITY_LEAD), persist to go_live_signoffs, return 201; return 409 if that role already has a signoff.; GET /status: return {isReadyForGoLive: bool, missingSignoffs: [], uncheckedItemCount: int} for dashboard use.
**Deliverable:** GoLiveChecklistController.java with 4 endpoints; OpenAPI annotations for each endpoint
**Acceptance / logic checks:**
- GET /checklist returns 200 with all 8 categories and at least 30 items total after seeding.
- PATCH /checklist/INFRA_MULTI_AZ with {checked:true} returns 200 and item shows checked_by = authenticated user.
- POST /signoff with role RELEASE_MANAGER twice returns 201 on first call and 409 on second.
- Unauthenticated call to any endpoint returns 401; call with role PARTNER_USER returns 403.
- GET /status returns {isReadyForGoLive:false} when any item is unchecked; returns true only when all items checked AND all 4 signoffs present.
**Depends on:** 16.1-T02

### 16.1-T04 — Implement infrastructure readiness checks (OPS-13 §12.1.1) — automated verifiers  _(50 min)_
**Context:** OPS-13 §12.1.1 Infrastructure checklist includes: multi-AZ deployment confirmed, load balancer and WAF+TLS validated, auto-scaling tested at 500 TPS, DR environment provisioned, DR drill completed, PostgreSQL backup restored in preceding 30 days. For automated items, the system should probe and auto-check; for manual attestations it must require human confirmation with notes.
**Steps:** Create InfraReadinessProbe service with probeMultiAz() calling the Kubernetes/cloud API to count available zones for hub-api deployment; returns ProbeResult{pass, detail}.; Implement probeTlsCertificates() making a TLS handshake to the production API base URL (https://api.gmepayplus.com) and checking notAfter > now + 30 days.; Implement probeDbBackupAge() querying a metadata table backup_events (backup_type, completed_at) for the most recent full snapshot; returns pass iff completed_at > now - 30 days.; Create AutoCheckScheduler that runs all three probes on demand and on deploy, then calls GoLiveChecklistService.markItem for INFRA_MULTI_AZ, INFRA_TLS_CERT, INFRA_DB_BACKUP if probe passes, or writes a WARN log if not.; For manual-attestation items (DR_DRILL_COMPLETED, AUTOSCALE_TESTED), leave those as human-only PATCH calls; ensure probe does not auto-mark them.
**Deliverable:** InfraReadinessProbe.java + AutoCheckScheduler.java with three automated probes
**Acceptance / logic checks:**
- probeMultiAz() returns ProbeResult{pass=true} when Kubernetes deployment replicas span >= 2 AZs; returns false with detail explaining the shortage.
- probeTlsCertificates() returns pass=false and detail containing the cert expiry when pointed at a test server with a cert expiring in 5 days.
- probeDbBackupAge() returns pass=false when the most recent backup_events row has completed_at older than 31 days.
- AutoCheckScheduler auto-marks INFRA_MULTI_AZ only when probeMultiAz() passes; does NOT auto-mark DR_DRILL_COMPLETED.
- Probe failures are logged at WARN with the itemCode and detail string; checklist item remains unchecked.
**Depends on:** 16.1-T02

### 16.1-T05 — Implement application readiness checks (OPS-13 §12.1.2) — feature flags and rate engine  _(55 min)_
**Context:** OPS-13 §12.1.2 Application checklist: all Phase 1 features deployed, DB migrations applied, feature flags configured (FEATURE_LIVE_FX_FEED=false, FEATURE_PARTNER_REFUND_API=false per §5.2), rate engine test vectors confirmed (domestic + cross-border), pool identity assertion enabled, idempotency keys validated. Feature flags live in config/secrets manager. Rate engine vectors: domestic (same-currency short-circuit) and cross-border (5-step USD pool).
**Steps:** Create AppReadinessChecker with checkFeatureFlags(): reads current flag values from config and verifies FEATURE_LIVE_FX_FEED=false, FEATURE_PARTNER_REFUND_API=false, FEATURE_OUTBOUND_PAYMENTS=false; returns FlagCheckResult{pass, wrongFlags[]}.; Implement checkRateEngineVectors(): runs the two canonical test vectors inline — (a) domestic KRW 50000 target, service_charge 500 -> expects collection_amount=50500, no USD pool fields; (b) cross-border KRW 13500 target, cost_rate_pay=1350.0, m_a=0.01, m_b=0.01 -> expects payout_usd_cost=10.0, collection_usd=10.204, collection_amount computed by formula. Returns pass/fail with diff details.; Implement checkMigrationsApplied(): queries Flyway schema_version table for all expected migration versions; returns list of missing versions.; Implement checkPoolIdentityGuard(): queries a config or system property to confirm the pool identity guard is active (throws on violation).; Wire all four checks into AutoCheckScheduler post-deploy hook; auto-mark APP_FEATURE_FLAGS, APP_RATE_ENGINE, APP_MIGRATIONS if pass.
**Deliverable:** AppReadinessChecker.java with four check methods; integrated into AutoCheckScheduler
**Acceptance / logic checks:**
- checkFeatureFlags() returns pass=false and wrongFlags=['FEATURE_LIVE_FX_FEED'] when that flag is set to true in test config.
- checkRateEngineVectors() domestic: collection_amount = 50500 KRW (50000 + 500 service charge), no payout_usd_cost field populated.
- checkRateEngineVectors() cross-border: payout_usd_cost = 13500/1350 = 10.0 USD; collection_usd = 10.0/(1-0.01-0.01) = 10.2041 USD; pool identity holds within 0.01 USD.
- checkMigrationsApplied() returns empty missing list when all expected V-prefixed migrations are present in schema_version.
- checkPoolIdentityGuard() returns pass=false and logs WARN when the guard property is missing or set to disabled.
**Depends on:** 16.1-T02

### 16.1-T06 — Implement ZeroPay integration readiness checks (OPS-13 §12.1.3)  _(50 min)_
**Context:** OPS-13 §12.1.3 ZeroPay Integration checklist: production SFTP credentials loaded in secrets manager (path gmepay/prod/batch-worker/zeropay-sftp-key), SFTP connectivity test to hankgyeolwon production server, all ZP00xx file types validated (ZP0011, ZP0021, ZP0061, ZP0063, ZP0065, ZP0066), hankgyeolwon confirmed full batch cycle in staging, batch scheduler on KST windows, batch alerting tested. SFTP test = connect only (no file upload). Credentials never shared across tiers.
**Steps:** Create ZeroPayReadinessChecker with checkSftpCredentialsPresent(): reads the secret at gmepay/prod/batch-worker/zeropay-sftp-key and verifies it is non-empty and PEM-formatted (starts with '-----BEGIN'); does NOT log the key value.; Implement checkSftpConnectivity(): opens a JSch SFTP connection to the production SFTP host/port (from config) using the loaded key and tests that the connection handshake succeeds (no file operations); closes connection immediately; timeout 10s.; Implement checkBatchJobSchedules(): reads all CronJob definitions for JOB-ZP-01 through JOB-ZP-09 from the batch scheduler and verifies the cron expressions match KST production windows (02:00, 02:00, 05:00, 05:00, 05:00, 10:00, 14:00, 19:00, 22:00 KST).; Implement checkFileTypesCoverage(): queries batch_job_configs for the set of active file_types and verifies ZP0011, ZP0021, ZP0061, ZP0063, ZP0065, ZP0066 are all present.; Wire into AutoCheckScheduler; auto-mark ZP_SFTP_CREDENTIALS, ZP_SFTP_CONNECTIVITY, ZP_BATCH_SCHEDULES, ZP_FILE_TYPES. ZP_HANKGYEOLWON_CONFIRMED remains a manual attestation.
**Deliverable:** ZeroPayReadinessChecker.java with four automated checks; checklist items auto-marked on pass
**Acceptance / logic checks:**
- checkSftpCredentialsPresent() returns pass=false when the secret path returns a 404 from secrets manager.
- checkSftpConnectivity() returns pass=false and detail 'Connection refused' when pointed at a non-existent SFTP host; succeeds and logs 'SFTP handshake OK' against a live test server.
- checkBatchJobSchedules() returns pass=false if JOB-ZP-01 cron is '0 2 * * *' in UTC instead of KST offset ('0 17 * * *' UTC = 02:00 KST).
- checkFileTypesCoverage() returns missing=['ZP0065','ZP0066'] when those file types are absent from batch_job_configs.
- No credential value is written to logs at any level; only 'credential present: true/false' is logged.
**Depends on:** 16.1-T02

### 16.1-T07 — Implement partner readiness checks (OPS-13 §12.1.4) — GME Remit and SendMN config validation  _(45 min)_
**Context:** OPS-13 §12.1.4 Partners checklist: GME Remit config registered (LOCAL type, KRW/KRW same-currency, service_charge KRW 500, webhook URL), GME Remit API integration test completed, GME Remit webhook delivery confirmed, SendMN config registered (OVERSEAS type, USD prefunding, m_a+m_b>=2%, service_charge KRW 500), SendMN initial prefunding balance confirmed, SendMN low-balance alert configured at USD 10000. Schema fields: partner.type, partner.settle_a_ccy, partner.settle_b_ccy, rule.m_a, rule.m_b, rule.service_charge, partner.low_balance_threshold_usd.
**Steps:** Create PartnerReadinessChecker with checkGmeRemitConfig(): queries partner table for partner_code='GME_REMIT'; verifies type=LOCAL, settle_a_ccy=KRW, settle_b_ccy=KRW, webhook_url non-null, and a rule with direction=Domestic and service_charge=500.00 KRW exists.; Implement checkSendMnConfig(): queries partner table for partner_code='SENDMN'; verifies type=OVERSEAS, and the primary cross-border rule has m_a+m_b >= 0.02 (2.0%) and service_charge=500 KRW; prefunding balance > 0 USD.; Implement checkSendMnLowBalanceAlert(): queries partner_alerts config for SENDMN and verifies low_balance_threshold_usd >= 10000.; Implement checkGmeRemitWebhookEndpoint(): issues an HTTP OPTIONS or HEAD request to the GME Remit webhook URL from config; returns pass if HTTP response is any 2xx or 4xx (endpoint reachable, not 5xx/timeout).; Wire into AutoCheckScheduler; auto-mark PARTNER_GME_REMIT_CONFIG, PARTNER_SENDMN_CONFIG, PARTNER_SENDMN_ALERT, PARTNER_GME_REMIT_WEBHOOK if pass. PARTNER_GME_REMIT_INTEGRATION_TEST remains manual.
**Deliverable:** PartnerReadinessChecker.java with four check methods auto-marking four checklist items
**Acceptance / logic checks:**
- checkGmeRemitConfig() returns pass=false if service_charge for the Domestic rule is 0 (missing) and detail shows 'expected 500 KRW'.
- checkSendMnConfig() returns pass=false if m_a=0.01, m_b=0.005 (sum=1.5% < 2%) and detail shows 'combined margin 1.50% below minimum 2.00%'.
- checkSendMnLowBalanceAlert() returns pass=false if threshold=5000 USD and detail shows 'threshold 5000 USD below required 10000 USD'.
- checkGmeRemitWebhookEndpoint() returns pass=false with detail 'Connection timeout' when webhook URL is unreachable within 5s.
- Both GME Remit (LOCAL) and SendMN (OVERSEAS) checks are independent; failure of one does not affect the other check result.
**Depends on:** 16.1-T02

### 16.1-T08 — Implement merchant data readiness checks (OPS-13 §12.1.5) — ZeroPay sync validation  _(40 min)_
**Context:** OPS-13 §12.1.5 Merchant Data checklist: production ZeroPay merchant sync (ZP0041/ZP0045) run and validated, full merchant list (ZP0051) loaded, QR sync (ZP0043/ZP0053) run, at least one live ZeroPay merchant validated per merchant type. ZP0041=daily delta merchant sync, ZP0045=delta merchant fee sync, ZP0051=full weekly merchant list, ZP0043=daily QR delta, ZP0053=full weekly QR list. Batch job runs tracked in batch_job_runs table with job_id, run_date, status (COMPLETE/FAILED), file_type.
**Steps:** Create MerchantDataReadinessChecker with checkMerchantSyncRun(): queries batch_job_runs for file_type IN ('ZP0041','ZP0045') with run_date = current_date (or prior business day) and status=COMPLETE; returns pass if both present.; Implement checkFullMerchantListLoaded(): queries batch_job_runs for file_type='ZP0051' with status=COMPLETE and run_date within the last 7 days; returns pass if found.; Implement checkQrSyncRun(): queries batch_job_runs for file_type IN ('ZP0043','ZP0053') with a completed run within current week; returns pass if both found.; Implement checkAtLeastOneMerchantPerType(): queries merchants table grouped by merchant_type; returns pass iff count > 0 for each distinct merchant_type that ZeroPay sends (at minimum: STORE, MOBILE).; Wire checks into AutoCheckScheduler; auto-mark MERCHANT_SYNC_RUN, MERCHANT_FULL_LIST, MERCHANT_QR_SYNC, MERCHANT_TYPES_COVERED.
**Deliverable:** MerchantDataReadinessChecker.java with four checks auto-marking four checklist items
**Acceptance / logic checks:**
- checkMerchantSyncRun() returns pass=false when batch_job_runs has ZP0041 with status=FAILED for today; returns true when status=COMPLETE.
- checkFullMerchantListLoaded() returns pass=false when the most recent ZP0051 run was 10 days ago (outside 7-day window).
- checkQrSyncRun() returns pass=false when only ZP0043 is present but ZP0053 has no completed run in the current week.
- checkAtLeastOneMerchantPerType() returns pass=false and detail 'merchant_type MOBILE has 0 records' when merchants table has only STORE type rows.
- All checks use run_date comparison in KST timezone (Asia/Seoul), not UTC.
**Depends on:** 16.1-T02

### 16.1-T09 — Implement monitoring and alerting readiness checks (OPS-13 §12.1.6)  _(50 min)_
**Context:** OPS-13 §12.1.6 Monitoring checklist: all alert catalog entries (§7.5) configured and tested with synthetic alerts, PagerDuty on-call rotation configured, dashboards confirmed readable, log aggregation shipping confirmed from all services, distributed tracing (8-step trail) confirmed end-to-end. The 8 trace steps are: rate_quote_issued, payment_initiated, prefund_deducted, scheme_request_sent, scheme_response_received, transaction_committed, webhook_dispatched, webhook_delivered.
**Steps:** Create MonitoringReadinessChecker with checkAlertRulesConfigured(): calls the Prometheus/Alertmanager API (GET /api/v1/rules) and verifies that at minimum 20 alert rules are active; checks for the presence of rule names: API_ERROR_RATE_P1, PREFUND_LOW_BALANCE, BATCH_WINDOW_BREACH, POOL_IDENTITY_FAILURE.; Implement checkLogShipping(): queries the log aggregation backend (e.g., Loki or Elasticsearch) for log lines from each service (hub-api, batch-worker, admin-svc, webhook-dispatcher) with timestamp within the last 5 minutes; returns pass iff all 4 services have emitted logs.; Implement checkDistributedTracing(): queries the tracing backend for a recent trace containing all 8 span names; if no real trace exists, checks that the OpenTelemetry instrumentation points are registered in the application context.; For PagerDuty and dashboard checks: create manual-attestation items MONITORING_PAGERDUTY and MONITORING_DASHBOARDS (require human PATCH).; Wire automated checks into AutoCheckScheduler; auto-mark MONITORING_ALERT_RULES, MONITORING_LOG_SHIPPING, MONITORING_TRACING.
**Deliverable:** MonitoringReadinessChecker.java with three automated checks; two manual-only items
**Acceptance / logic checks:**
- checkAlertRulesConfigured() returns pass=false when the Prometheus API returns fewer than 20 active rules; returns false with missing=['POOL_IDENTITY_FAILURE'] when that specific rule is absent.
- checkLogShipping() returns pass=false with detail 'No logs from batch-worker in last 5 minutes' when that service is silent.
- checkDistributedTracing() returns pass=false if any of the 8 required span names are absent from registration.
- Auto-mark writes a INFO log: 'Auto-marked MONITORING_LOG_SHIPPING: pass=true, actor=system'.
- MONITORING_PAGERDUTY cannot be auto-marked; PATCH to mark it requires authenticated actor.
**Depends on:** 16.1-T02

### 16.1-T10 — Implement security readiness checks (OPS-13 §12.1.7)  _(45 min)_
**Context:** OPS-13 §12.1.7 Security checklist: security review sign-off (penetration test or equivalent for Phase 1 per SEC-09 §9), all production secrets rotated from staging values, break-glass access procedure documented and tested, audit logging confirmed for all Admin System actions. Secrets rotation check: verify that prod secrets have a last_rotated_at timestamp newer than the staging-to-prod promotion date. Break-glass: a dedicated break_glass_access_log table with at least one test entry.
**Steps:** Create SecurityReadinessChecker with checkSecretsRotated(): reads secret metadata (last_rotated_at) for a defined list of prod secrets (zeropay-sftp-key, hub-api-key, db-password) from secrets manager; returns pass iff all have last_rotated_at after the promotion date (read from a config property golive.staging_promotion_date).; Implement checkAuditLogActive(): inserts a synthetic admin action 'GOLIVE_AUDIT_PROBE' into the audit log and immediately queries for it; returns pass iff the record is retrievable with correct actor, timestamp, and action.; Implement checkBreakGlassDocumented(): queries break_glass_access_log for at least one entry with access_type='TEST' to confirm the procedure has been exercised.; For penetration test sign-off (SECURITY_PENTEST_SIGNOFF): manual-attestation only; cannot be auto-marked. Require notes field to be non-empty when marking.; Wire automated checks into AutoCheckScheduler; auto-mark SECURITY_SECRETS_ROTATED, SECURITY_AUDIT_LOG, SECURITY_BREAK_GLASS.
**Deliverable:** SecurityReadinessChecker.java with three automated checks and one manual-only item
**Acceptance / logic checks:**
- checkSecretsRotated() returns pass=false for zeropay-sftp-key if last_rotated_at is 6 months ago and staging_promotion_date is 1 month ago.
- checkAuditLogActive() returns pass=false if the probe record is not retrievable within 2 seconds.
- checkBreakGlassDocumented() returns pass=false when break_glass_access_log has zero TEST entries.
- PATCH to mark SECURITY_PENTEST_SIGNOFF with empty notes returns HTTP 400 with error 'notes field required for manual security attestation'.
- checkSecretsRotated() never logs secret values; logs only {secretName, lastRotatedAt, pass}.
**Depends on:** 16.1-T02

### 16.1-T11 — Implement operational readiness checks (OPS-13 §12.1.8) — training and contacts  _(35 min)_
**Context:** OPS-13 §12.1.8 Operational Readiness checklist: Ops team trained on Admin System (partner setup, exception processing, FX rate update), runbook reviewed with Ops Lead sign-off, partner contacts (GME Remit, SendMN, hankgyeolwon) confirmed in contact register, change management system access confirmed for go-live team. These are all manual attestations with supporting data checks.
**Steps:** Create OperationalReadinessChecker with checkPartnerContactsRegistered(): queries a partner_contacts table (partner_id, contact_name, email, phone, role) for entries covering GME_REMIT, SENDMN, and HANKGYEOLWON with at least one technical and one operations contact each; returns pass iff all three are present with non-null email and phone.; Implement checkChangeManagementAccess(): reads a config property change_mgmt.system_url and issues an authenticated HTTP HEAD request; returns pass if 2xx response within 5s.; For OPS_TEAM_TRAINED, OPS_RUNBOOK_SIGNOFF, OPS_CHANGE_MGMT_ACCESS: require these as manual attestations (PATCH endpoint) with notes field mandated for TRAINED.; Wire checkPartnerContactsRegistered and checkChangeManagementAccess into AutoCheckScheduler; auto-mark OPS_CONTACTS_REGISTERED, OPS_CHANGE_MGMT_URL.; On PATCH for OPS_TEAM_TRAINED require notes to be non-empty (validation in controller).
**Deliverable:** OperationalReadinessChecker.java with two automated checks; three manual-only items with validation rules
**Acceptance / logic checks:**
- checkPartnerContactsRegistered() returns pass=false with detail 'Missing contacts for HANKGYEOLWON' when that entry is absent from partner_contacts.
- checkPartnerContactsRegistered() returns pass=false with detail 'Missing phone for GME_REMIT technical contact' when phone is null.
- checkChangeManagementAccess() returns pass=false when the configured URL returns a 5xx or is unreachable.
- PATCH /golive/checklist/OPS_TEAM_TRAINED with empty notes body returns HTTP 400.
- Auto-mark logs 'Auto-marked OPS_CONTACTS_REGISTERED: pass=true' at INFO; manual items not auto-marked.
**Depends on:** 16.1-T02, 16.1-T03

### 16.1-T12 — Build go-live readiness summary dashboard endpoint  _(40 min)_
**Context:** OPS-13 §12 requires the go-live team to have a single-pane view of all 8 checklist categories and the 4 signoff roles before traffic is accepted. GET /admin/v1/golive/dashboard must return: overall readiness bool, per-category progress, list of blockers (unchecked items), signoff status per role, and warnings from the most recent automated probe run. Target audience: Release Manager, Ops Lead, Engineering Lead.
**Steps:** Create GoLiveDashboardService aggregating results from GoLiveChecklistService.getCategorySummary(), isReadyForGoLive(), and the latest AutoCheckScheduler probe results.; Build GoLiveDashboardResponse DTO: {isReadyForGoLive, overallProgress:{checked, total}, categories:[{name, checkedCount, totalCount, isComplete, blockers:[]}], signoffs:[{role, signerName, signedAt}], missingSignoffs:[], lastProbeRunAt, probeWarnings:[]}.; Add GET /admin/v1/golive/dashboard endpoint in GoLiveChecklistController returning the response DTO with HTTP 200.; Populate blockers array with item descriptions (not item_codes alone) for each unchecked item per category to make the dashboard self-explanatory.; Cache the dashboard response for 30 seconds (configurable) to prevent probe queries from overloading the observability backends.
**Deliverable:** GoLiveDashboardService.java + GoLiveDashboardResponse DTO + GET /admin/v1/golive/dashboard endpoint
**Acceptance / logic checks:**
- Response body contains all 8 category entries even when all items are unchecked.
- isReadyForGoLive=false when any category has unchecked items; true only when all 8 categories complete AND 4 signoffs present.
- blockers array for INFRASTRUCTURE category lists human-readable descriptions (not codes) for each unchecked item.
- missingSignoffs lists exactly the roles not yet present in go_live_signoffs.
- Cache: two calls within 30 seconds return identical lastProbeRunAt timestamps; a call after 31 seconds may have a newer timestamp.
**Depends on:** 16.1-T03, 16.1-T04, 16.1-T05, 16.1-T06, 16.1-T07, 16.1-T08, 16.1-T09, 16.1-T10, 16.1-T11

### 16.1-T13 — Define cutover procedure domain objects and CutoverRunRecord entity  _(35 min)_
**Context:** OPS-13 §12.2 Cutover Procedure has 7 sequential steps: (1) confirm all checklist items checked, (2) set go-live window (Tuesday/Wednesday 06:00 KST post-overnight batch), (3) announce to partners 48h in advance, (4) T-1h Engineering+Ops join call with dashboards, (5) T-0 flip LB + enable partner API keys, (6) run 5-min smoke tests, (7) declare go-live confirmed and start hypercare. A CutoverRunRecord must track which step was completed, by whom, and when.
**Steps:** Create migration V9002__create_cutover_run_records.sql with table cutover_run_records (id UUID PK, run_id VARCHAR(32) UNIQUE NOT NULL, go_live_window TIMESTAMPTZ, status VARCHAR(32) DEFAULT 'PENDING', current_step INT DEFAULT 0, created_by VARCHAR(128), created_at TIMESTAMPTZ DEFAULT now(), completed_at TIMESTAMPTZ).; Create table cutover_step_events (id UUID PK, run_id VARCHAR(32) REFERENCES cutover_run_records(run_id), step_number INT, step_name VARCHAR(128), completed_by VARCHAR(128), completed_at TIMESTAMPTZ, notes TEXT).; Define enum CutoverStep: CHECKLIST_VERIFIED(1), WINDOW_SET(2), PARTNERS_NOTIFIED(3), TMIN1_CALL_JOINED(4), TRAFFIC_ENABLED(5), SMOKE_TESTS_PASSED(6), GOLIVE_DECLARED(7).; Create CutoverRunRecord JPA entity and CutoverStepEvent JPA entity with corresponding repositories.; Add constraint: a new cutover_run_records row can only be created if isReadyForGoLive() returns true (enforced in service layer, not DB).
**Deliverable:** Migration V9002 + CutoverRunRecord entity + CutoverStepEvent entity + both JPA repositories
**Acceptance / logic checks:**
- V9002 applies cleanly; cutover_run_records has UNIQUE constraint on run_id.
- cutover_step_events has FK to cutover_run_records(run_id); inserting a step event with a non-existent run_id fails with FK violation.
- CutoverStep enum ordinal matches step_number (CHECKLIST_VERIFIED=1, GOLIVE_DECLARED=7).
- cutover_run_records.status default is PENDING; allowed values enforced: PENDING, IN_PROGRESS, COMPLETED, ROLLED_BACK.
- No cutover record can be created in service layer when isReadyForGoLive() returns false (IllegalStateException thrown with message 'Pre-go-live checklist is not complete').
**Depends on:** 16.1-T02

### 16.1-T14 — Implement CutoverService — step progression, window validation, and partner notification trigger  _(55 min)_
**Context:** OPS-13 §12.2: go-live window should be Tuesday or Wednesday 06:00 KST post-overnight-batch. Partners must be notified 48 hours in advance. At T-0, the load balancer traffic flip and partner API key enablement are the critical actions. CutoverService manages advancing through the 7 CutoverStep states sequentially — each step must be completed in order.
**Steps:** Create CutoverService with initiateCutover(LocalDateTime goLiveWindow, String actor): validates isReadyForGoLive()=true, validates goLiveWindow is a Tuesday or Wednesday in Asia/Seoul timezone and time = 06:00 KST (within 15-minute window 05:45-06:15), creates CutoverRunRecord with status=PENDING.; Implement completeStep(String runId, CutoverStep step, String actor, String notes): validates step = current_step + 1 (sequential); records CutoverStepEvent; advances current_step; updates status to IN_PROGRESS for steps 1-6 and COMPLETED for step 7.; Implement validatePartnersNotified(String runId): checks that the go_live_window on the record is at least 48 hours from now (now < goLiveWindow - 48h); returns pass/fail for STEP 3.; Implement enablePartnerApiKeys(String runId, String actor): for STEP 5, calls PartnerService.enableApiKey() for GME_REMIT and SENDMN; records the key enablement in the audit log; returns the set of enabled partner IDs.; Enforce: if current_step < 4 and now > goLiveWindow - 1h, auto-trigger a WARN alert 'T-1 hour: all team members should be on live call'.
**Deliverable:** CutoverService.java with initiateCutover, completeStep, validatePartnersNotified, enablePartnerApiKeys
**Acceptance / logic checks:**
- initiateCutover with a Thursday go-live date throws IllegalArgumentException 'Go-live window must be Tuesday or Wednesday'.
- initiateCutover with goLiveWindow at 09:00 KST (outside 05:45-06:15 window) throws IllegalArgumentException.
- completeStep for CutoverStep.TRAFFIC_ENABLED before PARTNERS_NOTIFIED is completed throws IllegalStateException 'Step 3 (PARTNERS_NOTIFIED) must be completed before step 5'.
- enablePartnerApiKeys enables both GME_REMIT and SENDMN and writes two audit log entries with action=PARTNER_API_KEY_ENABLED.
- validatePartnersNotified returns false when now is only 24 hours before goLiveWindow.
**Depends on:** 16.1-T13

### 16.1-T15 — Implement go-live smoke tests runner (OPS-13 §12.2 step 6)  _(55 min)_
**Context:** OPS-13 §12.2 smoke tests (5-min window): (1) rate quote for GME Remit domestic — confirm collection_amount = target_payout + 500 KRW service charge (same-currency short-circuit, no USD pool); (2) rate quote for SendMN cross-border — confirm USD pool math (m_a=0.01, m_b=0.01, cost_rate_pay from treasury); (3) verify webhook delivery to GME Remit test endpoint; (4) verify SendMN prefunding balance is readable and > 0. These are automated and run as part of CutoverService.completeStep(SMOKE_TESTS_PASSED).
**Steps:** Create GoLiveSmokeTestRunner with runAll(): executes all 4 tests and returns SmokeTestReport{allPassed, results:[SmokeTestResult{name, passed, detail, durationMs}]}.; Implement testGmeRemitRateQuote(): calls POST /v1/rates internally with partner=GME_REMIT, target_payout=50000 KRW, direction=Domestic; asserts collection_amount=50500, no payout_usd_cost in response, validUntil > now.; Implement testSendMnRateQuote(): calls POST /v1/rates with partner=SENDMN, target_payout=135000 KRW; asserts payout_usd_cost = 135000/cost_rate_pay (from treasury.usd_krw), collection_usd = payout_usd_cost/(1-0.01-0.01), pool identity holds within 0.01 USD.; Implement testGmeRemitWebhookDelivery(): calls the webhook dispatcher test endpoint with event=payment.approved for a synthetic txn_id; polls webhook delivery log for 30s; returns pass if delivery confirmed.; Implement testSendMnPrefundingReadable(): calls GET /admin/v1/partners/SENDMN/prefunding; asserts HTTP 200 and balance_usd > 0.; Wire into CutoverService.completeStep: completeStep(SMOKE_TESTS_PASSED) only succeeds if runAll() returns allPassed=true; otherwise throws with the failing test details.
**Deliverable:** GoLiveSmokeTestRunner.java with 4 test methods; integrated into CutoverService step 6
**Acceptance / logic checks:**
- testGmeRemitRateQuote: collection_amount = target_payout + 500 = 50500 KRW; no collection_margin_usd or payout_margin_usd fields in response (same-currency short-circuit).
- testSendMnRateQuote: pool identity check collection_usd - collection_margin_usd - payout_margin_usd = payout_usd_cost within 0.01 USD.
- testGmeRemitWebhookDelivery returns pass=false with detail 'Webhook not delivered within 30s' when the partner endpoint is down.
- completeStep(SMOKE_TESTS_PASSED) throws SmokeTestFailureException listing all failed test names when any smoke test fails.
- SmokeTestReport includes durationMs per test; total run time logged; a 5-minute timeout is applied to the full suite.
**Depends on:** 16.1-T14

### 16.1-T16 — Build cutover procedure REST endpoints (POST initiate, POST advance-step, GET status)  _(45 min)_
**Context:** OPS-13 §12.2 cutover is orchestrated by the Release Manager. Admin System must expose: POST /admin/v1/golive/cutover (initiate, sets window), POST /admin/v1/golive/cutover/{runId}/step (advance to next step), GET /admin/v1/golive/cutover/{runId} (current status and step events). Only OPS_ADMIN or RELEASE_MANAGER role may call these. Step advancement for TRAFFIC_ENABLED (step 5) requires a second-operator confirmation.
**Steps:** Create CutoverController at /admin/v1/golive/cutover with @PreAuthorize('hasAnyRole(OPS_ADMIN, RELEASE_MANAGER)').; POST /cutover: accept {goLiveWindow: ISO-8601 string} body, call CutoverService.initiateCutover, return CutoverRunRecord DTO with run_id, go_live_window, status=PENDING, HTTP 201.; POST /cutover/{runId}/step: accept {stepName: string, notes: string, confirmedBy: string (required for step 5)}; call completeStep; return updated CutoverRunRecord DTO; return 409 if step is out of order.; GET /cutover/{runId}: return full CutoverRunRecord with embedded step_events array sorted by step_number.; GET /cutover/active: return the most recent record with status IN_PROGRESS or PENDING; return 204 if none active.
**Deliverable:** CutoverController.java with 5 endpoints; CutoverRunRecordDto and CutoverStepEventDto mapped from entities
**Acceptance / logic checks:**
- POST /cutover returns 400 if isReadyForGoLive()=false with body {error: 'Pre-go-live checklist is not complete', uncheckedCount: N}.
- POST /cutover/{runId}/step with stepName=TRAFFIC_ENABLED and missing confirmedBy returns HTTP 422 'Second operator confirmation required for traffic enablement step'.
- POST /cutover/{runId}/step with a step that is already completed returns 409 with error 'Step already recorded'.
- GET /cutover/{runId} returns step_events in ascending step_number order with completed_by and completed_at for each recorded step.
- Unauthenticated call to any cutover endpoint returns 401.
**Depends on:** 16.1-T14, 16.1-T15

### 16.1-T17 — Implement rollback plan — RollbackService and rollback decision logic (OPS-13 §12.3)  _(50 min)_
**Context:** OPS-13 §12.3 Rollback Plan: if blocking defect discovered during/after go-live, (1) assess severity, (2) if blocking: suspend partner API keys to stop new payments (in-flight allowed to complete), (3) rollback deployment per §8.2, (4) notify GME Remit and SendMN, (5) investigate fix and re-run checklist §12.1 before retrying. RollbackService must: suspend partner keys atomically, mark cutover record as ROLLED_BACK, and create a rollback event with reason. A rollback can only be initiated during an active cutover (status=IN_PROGRESS or COMPLETED within 72h of go_live_window).
**Steps:** Create RollbackService with initiateRollback(String runId, String actor, String reason, boolean isCritical): loads CutoverRunRecord; validates status is IN_PROGRESS or (COMPLETED and completed_at within 72h); calls PartnerService.suspendApiKey() for all enabled partners atomically in a single transaction; updates cutover_run_records.status='ROLLED_BACK'; inserts a CutoverStepEvent with step_name='ROLLBACK_INITIATED', notes containing reason.; Implement notifyPartnersOfRollback(String runId): composes a notification event for each active partner (GME_REMIT, SENDMN) with message template from OPS-13 §12.3; dispatches via the webhook notification queue; returns notification IDs.; Implement getReEntryCriteria(String runId): returns list of checklist items that need re-verification before re-attempting cutover (all items that were auto-marked by probes that may have changed since rollback).; Add rollback guard: if isCritical=false and current error rate is below 1% (from metrics), log WARN 'Rollback initiated for non-critical defect — confirm intent'; proceed regardless (human decision).; Enforce: once status=ROLLED_BACK, further step advancement is blocked (completeStep throws).
**Deliverable:** RollbackService.java with initiateRollback, notifyPartnersOfRollback, getReEntryCriteria
**Acceptance / logic checks:**
- initiateRollback suspends both GME_REMIT and SENDMN API keys atomically; if PartnerService.suspendApiKey fails for SENDMN, the GME_REMIT suspension is also rolled back (single transaction).
- initiateRollback on a CutoverRunRecord with status=COMPLETED and completed_at 80 hours ago throws IllegalStateException 'Rollback window (72h) has expired'.
- notifyPartnersOfRollback dispatches exactly 2 notification events (one per partner); each contains the runId and ETA placeholder.
- After initiateRollback, completeStep call throws IllegalStateException 'Cutover has been rolled back; re-run checklist before retrying'.
- getReEntryCriteria returns at least the probe-auto-marked items so the team knows which checks to re-verify.
**Depends on:** 16.1-T14

### 16.1-T18 — Build rollback REST endpoints and partner notification template (OPS-13 §12.3)  _(40 min)_
**Context:** OPS-13 §12.3 rollback must be triggerable from the Admin System by a Release Manager or Engineering Lead. Endpoints: POST /admin/v1/golive/cutover/{runId}/rollback (initiate rollback), GET /admin/v1/golive/cutover/{runId}/reentry-criteria (list items to re-verify). Partner notification message per OPS-13 §12.3: 'GMEPay+ go-live has been temporarily rolled back due to a critical issue. ETA for resolution: [time]. No partner action required.'
**Steps:** Add POST /admin/v1/golive/cutover/{runId}/rollback to CutoverController: accept {reason: string, isCritical: bool, etaResolutionMinutes: int}; require @PreAuthorize('hasAnyRole(OPS_ADMIN, ENGINEERING_LEAD, RELEASE_MANAGER)'); call RollbackService.initiateRollback; return 200 with rollback event summary.; Add GET /admin/v1/golive/cutover/{runId}/reentry-criteria: return list of {itemCode, description, category, requiresProbe} sorted by category.; Create RollbackNotificationTemplate with method buildPartnerMessage(String partnerName, String etaDescription): returns the standardised message string from OPS-13 §12.3 with partnerName and etaDescription substituted.; Validate etaResolutionMinutes > 0 and < 10080 (7 days) in the request; return 422 otherwise.; Ensure PATCH /admin/v1/golive/checklist/{itemCode} clears (unmarked) the relevant probe-auto-marked items when called after a rollback, forcing re-verification.
**Deliverable:** Rollback endpoints in CutoverController.java + RollbackNotificationTemplate.java
**Acceptance / logic checks:**
- POST /rollback with missing reason field returns HTTP 400 'reason is required'.
- POST /rollback by a user with role PARTNER_USER returns 403.
- GET /reentry-criteria returns at least one item per category that was auto-marked; requiresProbe=true for automated items, false for manual attestations.
- buildPartnerMessage('GME Remit', '2 hours') returns exactly the OPS-13 template string with substitutions applied and no unresolved placeholders.
- POST /rollback returns 409 when called on a cutover with status=ROLLED_BACK (already rolled back).
**Depends on:** 16.1-T17, 16.1-T16

### 16.1-T19 — Implement hypercare period tracker (OPS-13 §12.4)  _(45 min)_
**Context:** OPS-13 §12.4: hypercare = 14 days after go-live (Oct 10-24 2026). Rules: extended on-call (2 engineers 24/7), twice-daily Ops check-in (09:00 and 18:00 KST), any P2 alert treated as P1, hypercare ends at day 14 IF: zero P1 incidents, SLOs met, at least one full batch cycle completed on each of 10 consecutive days. Track hypercare state in DB; surface via API so the on-call dashboard can show hypercare status and exit criteria progress.
**Steps:** Create migration V9003__create_hypercare_periods.sql with table hypercare_periods (id UUID PK, cutover_run_id VARCHAR(32) FK, start_at TIMESTAMPTZ NOT NULL, end_at TIMESTAMPTZ, status VARCHAR(32) DEFAULT 'ACTIVE', p1_incident_count INT DEFAULT 0, consecutive_clean_batch_days INT DEFAULT 0, last_clean_batch_day DATE, exited_early BOOLEAN DEFAULT false, created_at TIMESTAMPTZ DEFAULT now()).; Create HypercarePeriod entity + HypercareService with startHypercare(String cutovertRunId): creates a hypercare_periods record with start_at=now; called automatically by CutoverService.completeStep(GOLIVE_DECLARED).; Implement recordP1Incident(String hyperccareId, String incidentId): increments p1_incident_count; this resets exit eligibility.; Implement recordCleanBatchDay(String hypercareId, LocalDate date): increments consecutive_clean_batch_days if date = last_clean_batch_day + 1 business day; resets to 1 if non-consecutive; updates last_clean_batch_day.; Implement checkExitCriteria(String hypercareId): returns ExitCriteriaStatus{canExit, daysElapsed, p1Count, consecutiveCleanBatchDays, slosMet}; canExit=true iff daysElapsed>=14 AND p1Count=0 AND consecutiveCleanBatchDays>=10 AND slosMet=true.
**Deliverable:** Migration V9003 + HypercarePeriod entity + HypercareService with 4 methods
**Acceptance / logic checks:**
- startHypercare creates a row with status=ACTIVE and p1_incident_count=0; calling it twice for the same cutover_run_id throws DuplicateHypercareException.
- recordP1Incident increments p1_incident_count from 0 to 1 and logs audit entry {entity=HYPERCARE, event=P1_INCIDENT_RECORDED, incidentId}.
- recordCleanBatchDay on three consecutive business days sets consecutive_clean_batch_days=3; a gap day resets it to 1.
- checkExitCriteria returns canExit=false when daysElapsed=14, p1Count=1 (incident occurred).
- checkExitCriteria returns canExit=false when daysElapsed=14, p1Count=0, but consecutiveCleanBatchDays=9 (one day short of 10).
**Depends on:** 16.1-T14

### 16.1-T20 — Build hypercare REST endpoints and on-call dashboard integration (OPS-13 §12.4)  _(40 min)_
**Context:** OPS-13 §12.4 hypercare requires the Ops team to check in twice daily and track exit criteria. Expose: GET /admin/v1/golive/hypercare/active (current hypercare status + exit criteria), POST /admin/v1/golive/hypercare/{id}/batch-day (record a clean batch day), POST /admin/v1/golive/hypercare/{id}/incident (record a P1 incident), POST /admin/v1/golive/hypercare/{id}/exit (manually exit hypercare when criteria met). The twice-daily check-in at 09:00 and 18:00 KST should be surfaced as a reminder in the response.
**Steps:** Create HypercareController at /admin/v1/golive/hypercare with @PreAuthorize('hasRole(OPS_ADMIN)').; GET /hypercare/active: returns HypercarePeriodDto {id, startAt, daysElapsed, status, p1IncidentCount, consecutiveCleanBatchDays, canExit, nextCheckInAt, exitCriteria[]}; nextCheckInAt = next 09:00 or 18:00 KST from now.; POST /hypercare/{id}/batch-day: accept {date: YYYY-MM-DD}; call HypercareService.recordCleanBatchDay; return updated dto.; POST /hypercare/{id}/incident: accept {incidentId: string, severity: P1|P2}; only P1 is counted in exit criteria; return updated dto.; POST /hypercare/{id}/exit: validates checkExitCriteria().canExit=true; sets status=COMPLETED, end_at=now; returns final summary. If canExit=false, returns 409 with detail.
**Deliverable:** HypercareController.java with 5 endpoints + HypercarePeriodDto
**Acceptance / logic checks:**
- GET /hypercare/active returns 204 when no active hypercare period exists.
- POST /hypercare/{id}/batch-day with date=2026-10-15 when last_clean_batch_day=2026-10-14 increments consecutiveCleanBatchDays by 1.
- POST /hypercare/{id}/incident with severity=P2 does NOT increment p1_incident_count (P2 incidents are tracked but do not block exit).
- POST /hypercare/{id}/exit returns 409 with detail showing which exit criterion is not met when canExit=false.
- nextCheckInAt in GET /hypercare/active is always 09:00 or 18:00 KST (Asia/Seoul) and is always in the future.
**Depends on:** 16.1-T19

### 16.1-T21 — Unit tests — pre-go-live checklist service logic  _(35 min)_
**Context:** Test GoLiveChecklistService and the readiness check aggregation logic. Key invariants: isReadyForGoLive() requires all 8 categories complete AND all 4 signoffs; markItem is idempotent; countUnchecked reflects real state; getCategorySummary returns accurate progress per category. Use an in-memory H2 database seeded with the standard checklist items.
**Steps:** Create GoLiveChecklistServiceTest with @SpringBootTest(webEnvironment=NONE) and test DB seeded via R__seed_golive_checklist_items.sql.; Test: testMarkItemSetsCheckedAndAudited — mark item 'INFRA_MULTI_AZ', assert is_checked=true, audit log has 1 entry with prev=false, new=true, actor='test_actor'.; Test: testMarkItemIsIdempotent — mark 'INFRA_MULTI_AZ' twice, assert audit log has exactly 1 entry (not 2 duplicate entries).; Test: testIsReadyForGoLiveRequiresAllItemsAndSignoffs — mark all items, assert isReadyForGoLive()=false; add 3 of 4 signoffs, assert still false; add 4th signoff, assert true.; Test: testGetCategorySummaryShowsProgress — mark 2 of N INFRASTRUCTURE items, assert getCategorySummary()[INFRASTRUCTURE].checkedCount=2 and totalCount=N.
**Deliverable:** GoLiveChecklistServiceTest.java with 5 unit tests all passing
**Acceptance / logic checks:**
- All 5 tests pass with zero failures in CI.
- testMarkItemIsIdempotent confirms the audit table has exactly 1 entry after two calls to markItem with the same itemCode.
- testIsReadyForGoLiveRequiresAllItemsAndSignoffs: intermediate assertion (3 signoffs, all items checked) returns false; only returns true with 4 signoffs and zero unchecked items.
- Test execution time < 5 seconds total (no external I/O).
- Test coverage of GoLiveChecklistService: all 5 public methods covered.
**Depends on:** 16.1-T02

### 16.1-T22 — Unit tests — AppReadinessChecker rate engine vectors  _(30 min)_
**Context:** Test AppReadinessChecker.checkRateEngineVectors() with both canonical test cases from OPS-13 §12.1.2 and RATE-04. Domestic: target_payout=50000 KRW, service_charge=500 KRW -> collection_amount=50500, no USD pool. Cross-border: target_payout=135000 KRW, cost_rate_pay=1350.0 (treasury.usd_krw), m_a=0.01, m_b=0.01 -> payout_usd_cost=100.0 USD, collection_usd=102.0408 USD, collection_margin_usd=1.0204, payout_margin_usd=1.0204, pool identity holds within 0.01 USD.
**Steps:** Create AppReadinessCheckerTest.java.; Test: testDomesticShortCircuit — configure GME_REMIT rule with same-currency short-circuit, call checkRateEngineVectors(); assert result.domesticTestPassed=true and collectionAmount=50500 KRW.; Test: testCrossBorderUsdPool — configure SENDMN rule with cost_rate_pay=1350.0, m_a=0.01, m_b=0.01; call checkRateEngineVectors(); assert payout_usd_cost=100.0, collection_usd=102.0408 (within 0.001), pool identity: |collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost| < 0.01.; Test: testFlagCheckFailsWhenFeedEnabled — set FEATURE_LIVE_FX_FEED=true; assert checkFeatureFlags().pass=false and wrongFlags contains 'FEATURE_LIVE_FX_FEED'.; Test: testPoolIdentityBreachDetected — inject a mock rate engine that returns collection_usd=102.0 but payout_usd_cost=100.0 with margins summing to only 1.9 USD (pool breaks); assert checkRateEngineVectors().crossBorderTestPassed=false.
**Deliverable:** AppReadinessCheckerTest.java with 4 unit tests all passing
**Acceptance / logic checks:**
- testDomesticShortCircuit: collection_amount = 50500 KRW exactly; assertion fails if collection_amount = 50000 (missing service_charge).
- testCrossBorderUsdPool: pool identity assertion passes when |collection_usd - sum_of_margins - payout_usd_cost| < 0.01 USD.
- testPoolIdentityBreachDetected confirms the check returns pass=false (not an exception) when pool identity breaks.
- testFlagCheckFailsWhenFeedEnabled: wrongFlags list contains exactly 'FEATURE_LIVE_FX_FEED' and no other flags.
- All 4 tests run in < 3 seconds with no network calls.
**Depends on:** 16.1-T05

### 16.1-T23 — Unit tests — CutoverService step sequencing and window validation  _(35 min)_
**Context:** Test CutoverService invariants: go-live window must be Tuesday/Wednesday 06:00 KST, steps must advance in order 1->7, enablePartnerApiKeys must be atomic, rollback blocks further step advancement. Use a mock GoLiveChecklistService that returns isReadyForGoLive()=true.
**Steps:** Create CutoverServiceTest.java with mock GoLiveChecklistService returning isReadyForGoLive()=true and mock PartnerService.; Test: testWindowValidation — assert initiateCutover with Monday 06:00 KST throws IllegalArgumentException; with Thursday 06:00 KST throws; with Wednesday 06:00 KST succeeds.; Test: testStepsMustBeSequential — create a cutover, try to completeStep(TRAFFIC_ENABLED) without completing steps 1-4 first; assert IllegalStateException thrown with message containing the missing step number.; Test: testEnablePartnerApiKeysAudit — completeStep TRAFFIC_ENABLED; assert PartnerService.enableApiKey called for GME_REMIT and SENDMN and 2 audit entries written.; Test: testRollbackBlocksStepAdvancement — initiate rollback on an IN_PROGRESS cutover; assert completeStep throws IllegalStateException.; Test: testWindowAtEdge — Wednesday 05:45 KST and 06:15 KST should both succeed; 05:44 KST and 06:16 KST should fail.
**Deliverable:** CutoverServiceTest.java with 5 unit tests all passing
**Acceptance / logic checks:**
- testWindowValidation covers Monday (fail), Thursday (fail), Wednesday 06:00 KST (pass).
- testStepsMustBeSequential: exception message identifies which step number is missing.
- testEnablePartnerApiKeysAudit: exactly 2 audit entries, each with action=PARTNER_API_KEY_ENABLED and distinct partner_id.
- testRollbackBlocksStepAdvancement: completeStep after rollback throws and does NOT modify the DB record.
- testWindowAtEdge: boundary times 05:45 and 06:15 pass; 05:44 and 06:16 fail.
**Depends on:** 16.1-T14

### 16.1-T24 — Unit tests — RollbackService atomicity and notification  _(30 min)_
**Context:** Test RollbackService: partner key suspension must be atomic (if one fails, both roll back), rollback window expires after 72 hours, re-entry criteria lists probe-auto-marked items. Use mock PartnerService that can be configured to fail on SENDMN suspension.
**Steps:** Create RollbackServiceTest.java with mock PartnerService and mock CutoverRunRecordRepository.; Test: testAtomicSuspension — mock PartnerService to throw on suspendApiKey('SENDMN'); call initiateRollback; assert GME_REMIT key was NOT suspended (transaction rolled back) and cutover status remains IN_PROGRESS.; Test: testRollbackWindowExpired — create a CutoverRunRecord with status=COMPLETED and completed_at=now()-73h; call initiateRollback; assert IllegalStateException with message 'Rollback window (72h) has expired'.; Test: testRollbackWindowValid — completed_at=now()-71h; assert initiateRollback succeeds.; Test: testNotificationDispatch — call notifyPartnersOfRollback; assert 2 notification events dispatched, each containing the partner name and run_id.; Test: testReEntryCriteriaIncludesProbeItems — assert getReEntryCriteria returns items with requiresProbe=true for INFRA_MULTI_AZ, APP_RATE_ENGINE, ZP_SFTP_CONNECTIVITY.
**Deliverable:** RollbackServiceTest.java with 5 unit tests all passing
**Acceptance / logic checks:**
- testAtomicSuspension: after exception in SENDMN suspension, GME_REMIT partner.api_key_status remains ENABLED (not suspended).
- testRollbackWindowExpired: 73h past completed_at throws; 71h past does not.
- testNotificationDispatch: exactly 2 events; each event body contains runId and is not empty string.
- testReEntryCriteriaIncludesProbeItems: list is non-empty; all items with requiresProbe=true have an associated AutoCheckScheduler probe method.
- All 5 tests pass with no real DB calls (pure unit test with mocks).
**Depends on:** 16.1-T17

### 16.1-T25 — Unit tests — HypercareService exit criteria logic  _(25 min)_
**Context:** Test HypercareService.checkExitCriteria with all combinations of the three exit conditions: daysElapsed>=14, p1Count=0, consecutiveCleanBatchDays>=10. Boundary cases: exactly 14 days, exactly 10 consecutive batch days, 1 P1 incident.
**Steps:** Create HypercareServiceTest.java with in-memory repository.; Test: testCanExitAllCriteriaMet — create period with start_at=now()-14days, p1_incident_count=0, consecutive_clean_batch_days=10; assert canExit=true.; Test: testCannotExitDaysShort — start_at=now()-13days; assert canExit=false and detail lists 'daysElapsed=13 < 14'.; Test: testCannotExitP1Occurred — 14 days elapsed, consecutive=10, p1_incident_count=1; assert canExit=false.; Test: testCannotExitBatchDaysShort — 14 days elapsed, p1Count=0, consecutiveCleanBatchDays=9; assert canExit=false.; Test: testCleanBatchDayGapResetsCount — record days Oct-10, Oct-11, Oct-13 (gap on Oct-12); assert consecutive_clean_batch_days=1 (only Oct-13 counts after gap).
**Deliverable:** HypercareServiceTest.java with 5 unit tests all passing
**Acceptance / logic checks:**
- testCanExitAllCriteriaMet: checkExitCriteria().canExit=true with no other conditions.
- testCannotExitDaysShort: ExitCriteriaStatus.canExit=false; detail explicitly states daysElapsed=13.
- testCannotExitP1Occurred: canExit=false; detail explicitly states p1Count=1 > 0.
- testCleanBatchDayGapResetsCount: after recording Oct-10, Oct-11, Oct-13 in order, consecutive_clean_batch_days=1 (reset by gap), not 3.
- All 5 tests complete in < 2 seconds.
**Depends on:** 16.1-T19

### 16.1-T26 — Integration test — end-to-end go-live readiness to cutover flow  _(55 min)_
**Context:** Integration test covering the full happy path: complete all 8 checklist categories, add 4 signoffs, initiate cutover for next Wednesday 06:00 KST, advance through all 7 steps (with mocked smoke tests passing), confirm hypercare period auto-starts. Uses a real test DB (Testcontainers PostgreSQL). Validates that the system correctly gates the flow at each step.
**Steps:** Create GoLiveCutoverIntegrationTest with @SpringBootTest and Testcontainers PostgreSQL.; Setup: seed all checklist items, mark all as checked (simulate probes passing), add 4 signoffs via service layer.; Test: testFullHappyPath — call POST /admin/v1/golive/cutover with next Wednesday 06:00 KST; advance through steps 1-7 in sequence; on step 6 confirm smoke test runner is called; verify cutover_run_records.status=COMPLETED and a hypercare_periods row exists with status=ACTIVE.; Assert at each step: GET /admin/v1/golive/cutover/{runId} returns current_step incremented.; Assert isReadyForGoLive()=true before initiation and that POST /cutover before all signoffs returns 400.
**Deliverable:** GoLiveCutoverIntegrationTest.java with 1 end-to-end test passing on Testcontainers
**Acceptance / logic checks:**
- POST /cutover before all 4 signoffs returns HTTP 400 with uncheckedCount or missing signoff detail.
- Steps 1-7 advance in order; step 5 (TRAFFIC_ENABLED) requires confirmedBy field.
- After step 7, cutover_run_records.status=COMPLETED and completed_at is set.
- hypercare_periods row is auto-created with start_at within 1 second of step 7 completion.
- End-to-end test completes in < 60 seconds on a standard CI runner.
**Depends on:** 16.1-T16, 16.1-T20

### 16.1-T27 — Integration test — rollback during active cutover  _(50 min)_
**Context:** Integration test: initiate a cutover, advance to step 5 (traffic enabled), then trigger a rollback. Verify: both partner API keys suspended atomically, cutover status=ROLLED_BACK, hypercare period not started, further step advancement rejected, re-entry criteria returned.
**Steps:** Create GoLiveRollbackIntegrationTest with Testcontainers PostgreSQL and real PartnerService.; Setup: same as T26 (all checklist items checked, 4 signoffs, cutover initiated), advance to step 5.; Call POST /admin/v1/golive/cutover/{runId}/rollback with {reason:'Critical rate engine bug detected', isCritical:true, etaResolutionMinutes:120}.; Assert: partner GME_REMIT and SENDMN both have api_key_status=SUSPENDED in DB.; Assert: GET /admin/v1/golive/cutover/{runId} returns status=ROLLED_BACK; step_events includes a ROLLBACK_INITIATED event.; Assert: POST /admin/v1/golive/cutover/{runId}/step to advance to step 6 returns 409.; Assert: GET /admin/v1/golive/cutover/{runId}/reentry-criteria returns non-empty list.
**Deliverable:** GoLiveRollbackIntegrationTest.java with 1 end-to-end rollback test passing on Testcontainers
**Acceptance / logic checks:**
- After rollback: both GME_REMIT and SENDMN partners have api_key_status=SUSPENDED in partners table.
- cutover_run_records.status=ROLLED_BACK; the ROLLBACK_INITIATED step_event has notes containing 'Critical rate engine bug detected'.
- No hypercare_periods row exists (rollback happened before step 7).
- Subsequent POST /step returns 409 with 'Cutover has been rolled back'.
- reentry-criteria endpoint returns >= 5 items; at least 3 with requiresProbe=true.
**Depends on:** 16.1-T18, 16.1-T26

### 16.1-T28 — Ops runbook document — go-live cutover procedure for Admin System (OPS-13 §12.2)  _(40 min)_
**Context:** OPS-13 §12 requires an operational runbook for the go-live day. The runbook must be embedded in the Admin System as a help page and also exported as a Markdown file at docs/runbooks/go-live-cutover.md. It must cover: pre-go-live checklist sign-off procedure, go-live window selection (Tuesday/Wednesday 06:00 KST), 48h partner notification, T-1h setup, T-0 traffic flip, smoke test steps, declare go-live, and rollback decision tree.
**Steps:** Create docs/runbooks/go-live-cutover.md covering the 7-step cutover procedure from OPS-13 §12.2 with: step number, actor, action, success criterion, and reference to the Admin System URL for each step.; Include in the runbook: smoke test expected values — domestic rate quote collection_amount = target_payout + 500 KRW; cross-border pool identity formula; sendmn prefunding check command.; Add a rollback decision tree section: condition (error rate > 1%, payment blocked, rate engine failure), decision (rollback vs. hotfix), and the Admin System action path for each.; Include the hypercare exit criteria table: 14 days elapsed, 0 P1 incidents, 10 consecutive clean batch days, SLOs met.; Add the runbook as an embedded help article in the Admin System Help module, referencing the /admin/v1/golive endpoints.
**Deliverable:** docs/runbooks/go-live-cutover.md (new file) + Admin System help article entry pointing to the document
**Acceptance / logic checks:**
- Runbook has exactly 7 numbered steps for the cutover procedure matching OPS-13 §12.2 order.
- Smoke test section includes the exact numeric check: collection_amount = target_payout + 500 for domestic; pool identity formula for cross-border.
- Rollback decision tree has at least 3 trigger conditions with corresponding Admin System actions.
- Hypercare exit criteria table matches all 4 criteria from OPS-13 §12.4 (14 days, 0 P1, 10 batch days, SLOs met).
- Admin System help article link resolves to the runbook document URL.
**Depends on:** 16.1-T16, 16.1-T18, 16.1-T20

### 16.1-T29 — Ops runbook document — rollback playbook and hypercare daily check-in procedure  _(35 min)_
**Context:** OPS-13 §12.3 and §12.4 require documented rollback playbook and hypercare operating procedures. The rollback playbook must be usable by an on-call engineer who has never seen the system before. The hypercare check-in procedure covers the twice-daily (09:00 and 18:00 KST) review process.
**Steps:** Create docs/runbooks/rollback-playbook.md covering: severity assessment criteria (blocking vs. cosmetic), the 5-step rollback procedure from OPS-13 §12.3, partner notification message template, and re-entry checklist.; Include command examples in the rollback playbook: Admin System URL path to suspend partner keys, the POST /admin/v1/golive/cutover/{runId}/rollback curl example, and GET /reentry-criteria path.; Create docs/runbooks/hypercare-checkin.md covering: twice-daily check-in agenda, batch cycle review (check JOB-ZP-01 through JOB-ZP-09 status), metrics review (p95 latency < 500ms, error rate < 1%, prefunding above threshold), and escalation criteria (P2 treated as P1 during hypercare).; Include the hypercare exit procedure: how to run checkExitCriteria, what to verify, and how to call POST /admin/v1/golive/hypercare/{id}/exit.; Add both documents to the Admin System Help module.
**Deliverable:** docs/runbooks/rollback-playbook.md + docs/runbooks/hypercare-checkin.md (two new files)
**Acceptance / logic checks:**
- rollback-playbook.md contains the exact partner notification message template from OPS-13 §12.3 with [time] placeholder clearly marked.
- rollback-playbook.md lists all 5 rollback steps in OPS-13 §12.3 order; none omitted.
- hypercare-checkin.md specifies both check-in times as 09:00 KST and 18:00 KST explicitly.
- hypercare-checkin.md escalation section states 'P2 alerts treated as P1 during hypercare period'.
- Both documents reference the correct Admin System endpoint paths for each action.
**Depends on:** 16.1-T18, 16.1-T20


## WBS 16.4 — Production smoke tests & monitoring
### 16.4-T01 — Define smoke-test suite contract: endpoint list, expected responses, and pass/fail criteria  _(30 min)_
**Context:** WBS 16.4 covers post-deploy production smoke tests and monitoring validation per OPS-13. The smoke suite must run in ~3 minutes (OPS-13 §8.1) and gate the prod deployment. It covers at minimum: GET /health, POST /v1/rates (GME Remit domestic), POST /v1/rates (SendMN cross-border), prefunding balance check for one OVERSEAS partner, and one webhook reachability ping. A smoke failure within 5 minutes triggers automated rollback (OPS-13 §4.4).
**Steps:** List every HTTP endpoint and batch-status check that must be exercised in the smoke suite.; For each check, specify: HTTP method, URL template, required request body (if any), expected HTTP status code, and one JSON field to assert on the response body.; Define overall pass condition: all checks return expected status within 10s each; any failure = suite FAILED.; Document the criteria in a SMOKE_TEST_CONTRACT.md file under docs/ops/.
**Deliverable:** docs/ops/SMOKE_TEST_CONTRACT.md listing all checks with method, URL, expected status, and body assertion.
**Acceptance / logic checks:**
- Contract lists GET /health -> 200, body contains {status: UP}.
- Contract lists POST /v1/rates for GME Remit (KRW/KRW, same-currency) -> 200, body contains collection_amount field.
- Contract lists POST /v1/rates for SendMN (USD prefunded cross-border) -> 200, body contains collection_usd and cross_rate fields.
- Contract lists GET /v1/partners/{partner_id}/prefunding/balance for at least one OVERSEAS partner -> 200, body contains available_balance_usd.
- Contract states total suite timeout = 3 minutes; any single check timeout > 10s counts as FAILED.

### 16.4-T02 — Implement /health endpoint returning structured JSON with component statuses  _(45 min)_
**Context:** OPS-13 §8.1 requires the smoke suite to verify GET /health. The endpoint must return HTTP 200 with a JSON body indicating service health and the status of critical dependencies: database connectivity, Redis cache connectivity, and secrets manager reachability. Response body must include: {status: UP|DEGRADED|DOWN, db: UP|DOWN, cache: UP|DOWN, secrets: UP|DOWN, version: string, env: string}. Any critical dependency DOWN should return HTTP 503.
**Steps:** Add a GET /health handler in the Hub API service.; Check DB connectivity by executing SELECT 1 against the primary PostgreSQL connection pool; record latency.; Check Redis connectivity by issuing a PING command; record latency.; Check secrets manager reachability by fetching a known non-sensitive test key (e.g. gmepay/{env}/health-probe/ping).; Return 200 if all dependencies are UP; return 503 if any dependency is DOWN; include version (from build tag) and env fields.
**Deliverable:** GET /health endpoint in Hub API returning {status, db, cache, secrets, version, env} with correct HTTP status codes.
**Acceptance / logic checks:**
- With all dependencies healthy: GET /health returns 200 and {status: UP, db: UP, cache: UP, secrets: UP}.
- With DB connection string deliberately broken: GET /health returns 503 and {status: DOWN, db: DOWN}.
- Response includes version matching the deployed image tag (e.g. v1.2.3-abc1234) and env=prod.
- Response time is < 1 second under normal conditions.
- Endpoint does not expose secrets, connection strings, or PII in its response body.
**Depends on:** 16.4-T01

### 16.4-T03 — Implement smoke-test rate-quote check for GME Remit domestic (KRW same-currency)  _(30 min)_
**Context:** Per OPS-13 §8.1 and §12.2, the smoke suite must issue a rate quote for GME Remit (LOCAL partner, KRW/KRW/KRW, same-currency short-circuit). For same-currency rules the rate engine short-circuits: collection_amount = target_payout + service_charge (KRW 500). Test vector: target_payout=10000 KRW, service_charge=500 KRW -> collection_amount=10500 KRW, cross_rate=1.0, offer_rate_coll=1.0. The smoke test calls POST /v1/rates with partner_id=GME_REMIT, scheme_id=ZEROPAY, direction=Domestic, target_payout=10000.
**Steps:** Add smoke-test step: POST /v1/rates with body {partner_id: GME_REMIT, scheme_id: ZEROPAY, direction: Domestic, target_payout: 10000, payout_ccy: KRW}.; Assert HTTP 200 is returned.; Assert response body contains collection_amount=10500, cross_rate=1.0, offer_rate_coll=1.0, validUntil is a future ISO-8601 timestamp.; Record the quote_id returned for potential use in a downstream smoke check.; Mark this step PASSED or FAILED in the smoke-test output log.
**Deliverable:** Smoke-test step smoke_rate_domestic in the smoke-test runner that verifies the GME Remit KRW rate-quote short-circuit.
**Acceptance / logic checks:**
- POST /v1/rates with target_payout=10000 KRW and GME_REMIT partner returns collection_amount=10500 KRW.
- cross_rate field equals 1.0 for same-currency domestic rule.
- validUntil is at least 60 seconds in the future (rate quote TTL).
- Test step completes in < 5 seconds.
- Test step logs PASSED/FAILED with the actual vs expected collection_amount value.
**Depends on:** 16.4-T01, 16.4-T02

### 16.4-T04 — Implement smoke-test rate-quote check for SendMN cross-border (USD/KRW)  _(35 min)_
**Context:** Per OPS-13 §12.2, go-live smoke tests must verify the SendMN cross-border rate quote (OVERSEAS partner, USD prefunded). Rate engine 5-step RECEIVE mode: cost_rate_pay = treasury.usd_krw (e.g. 1381.50), cost_rate_coll = 1.0 (settle_A=USD). Steps: payout_usd_cost = 100/1381.50 = 0.07238 USD; collection_usd = 0.07238/(1-0.01-0.01) = 0.07386 USD; collection_margin_usd = 0.07386*0.01; payout_margin_usd = 0.07386*0.01; send_amount = 0.07386*1.0 = 0.07386 USD; collection_amount = 0.07386 + 0 service_charge (USD). Pool identity: collection_usd - collection_margin_usd - payout_margin_usd = payout_usd_cost within 0.01 USD. The smoke test uses a live or cached treasury rate and asserts on structure, not exact amounts.
**Steps:** Add smoke-test step: POST /v1/rates with body {partner_id: SENDMN, scheme_id: ZEROPAY, direction: Inbound, target_payout: 10000, payout_ccy: KRW}.; Assert HTTP 200 is returned.; Assert response contains fields: collection_usd, payout_usd_cost, collection_margin_usd, payout_margin_usd, send_amount, collection_amount, offer_rate_coll, cross_rate, validUntil.; Assert pool identity: collection_usd - collection_margin_usd - payout_margin_usd = payout_usd_cost (tolerance 0.01 USD).; Assert cross_rate > 0 and collection_amount > 0; mark step PASSED or FAILED.
**Deliverable:** Smoke-test step smoke_rate_crossborder in the smoke-test runner verifying SendMN USD rate-quote structure and pool identity.
**Acceptance / logic checks:**
- POST /v1/rates for SENDMN returns HTTP 200 with all 9 rate-engine fields present.
- pool identity assertion: |collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost| <= 0.01 USD holds on the smoke response.
- validUntil is a future ISO-8601 timestamp at least 60 seconds ahead.
- collection_amount is denominated in USD (scale 2 decimal places).
- Test step marks FAILED if any field is missing or pool identity is violated.
**Depends on:** 16.4-T01, 16.4-T02

### 16.4-T05 — Implement smoke-test prefunding balance check for OVERSEAS partner  _(25 min)_
**Context:** Per OPS-13 §8.1 and §12.2, the smoke suite must verify prefunding balance is readable for an OVERSEAS partner (e.g. SendMN). The endpoint GET /v1/partners/{partner_id}/prefunding/balance should return {partner_id, available_balance_usd, reserved_balance_usd, low_balance_threshold_usd, alert_active: bool}. Smoke test must assert the endpoint returns 200 and available_balance_usd is a non-negative number. It does NOT assert a specific dollar value (that varies); it asserts the field is present and >= 0.
**Steps:** Add smoke-test step: GET /v1/partners/SENDMN/prefunding/balance using a smoke-test read-only API credential.; Assert HTTP 200 is returned.; Assert response body contains available_balance_usd as a numeric value >= 0.; Assert response body contains low_balance_threshold_usd (default 10000.00 per OPS-13 §7.5.2).; Mark step PASSED or FAILED; log the current available_balance_usd value for observability.
**Deliverable:** Smoke-test step smoke_prefunding_balance verifying OVERSEAS partner balance endpoint reachability and response structure.
**Acceptance / logic checks:**
- GET /v1/partners/SENDMN/prefunding/balance returns HTTP 200.
- Response contains available_balance_usd >= 0.
- Response contains low_balance_threshold_usd = 10000.00 (default config).
- Step fails with clear error message if available_balance_usd field is absent.
- Smoke step completes in < 5 seconds.
**Depends on:** 16.4-T01, 16.4-T02

### 16.4-T06 — Implement smoke-test webhook reachability ping to configured partner endpoints  _(35 min)_
**Context:** Per OPS-13 §12.2, go-live smoke tests must verify webhook delivery to GME Remit test endpoint. The webhook dispatcher sends payment.pending_debit and payment.approved events. For smoke testing, a synthetic POST to a known partner health-check webhook URL (configured per partner as webhook_smoke_url) is issued; the partner endpoint must return 200. This is distinct from a real payment webhook - it uses a reserved event type smoke.ping with body {event: smoke.ping, timestamp: ISO-8601}.
**Steps:** Read the webhook_smoke_url for each active partner from the partner config table.; Issue a POST to each webhook_smoke_url with body {event: smoke.ping, timestamp: <now ISO-8601>} and header X-GMEPay-Smoke: true.; Assert each URL returns HTTP 200 within 10 seconds.; Log the partner_id, webhook_smoke_url, response code, and latency for each check.; Mark step PASSED if all active partners return 200; FAILED if any return non-200 or time out.
**Deliverable:** Smoke-test step smoke_webhook_ping that issues synthetic pings to all configured partner webhook_smoke_url endpoints and asserts 200.
**Acceptance / logic checks:**
- A partner with a valid, reachable webhook_smoke_url returns HTTP 200 and step is PASSED.
- A partner whose webhook_smoke_url returns 500 causes the step to be marked FAILED with the partner_id logged.
- A partner whose webhook_smoke_url times out after 10s causes step to be FAILED.
- Step skips gracefully if webhook_smoke_url is not configured for a partner (logs WARNING, does not FAIL).
- Result log shows per-partner outcome (partner_id, status_code, latency_ms).
**Depends on:** 16.4-T01, 16.4-T02

### 16.4-T07 — Build smoke-test runner CLI that executes all steps and emits a structured result  _(45 min)_
**Context:** Per OPS-13 §4.4, post-deployment smoke tests must run in ~3 minutes and gate the pipeline. A CLI tool (smoke-runner) must execute all smoke steps (T02-T06) in order, collect pass/fail per step, and emit a structured JSON result to stdout. Exit code 0 = all passed; exit code 1 = any failed. The CI/CD pipeline reads the exit code to decide whether to trigger automated rollback. Runner accepts --env (dev/int/staging/prod) and --timeout-seconds (default 180) flags.
**Steps:** Create smoke-runner CLI entry point (e.g. scripts/smoke_runner.py or smoke-runner.sh) accepting --env and --timeout-seconds flags.; Invoke steps in order: health, rate_domestic, rate_crossborder, prefunding_balance, webhook_ping.; Collect per-step result: {step_name, status: PASSED|FAILED|SKIPPED, duration_ms, detail: string}.; Emit final JSON to stdout: {suite_status: PASSED|FAILED, env, run_at, duration_ms, steps: [...]}.; Exit with code 0 if suite_status=PASSED, code 1 if FAILED.
**Deliverable:** scripts/smoke_runner CLI that runs all 5 smoke steps, emits structured JSON result, and exits 0/1 based on suite outcome.
**Acceptance / logic checks:**
- Running smoke_runner --env prod with all mocked steps passing exits with code 0 and suite_status=PASSED.
- Running smoke_runner with one step returning a simulated failure exits with code 1 and suite_status=FAILED.
- Output JSON contains a steps array with 5 entries each having step_name, status, duration_ms.
- Runner terminates after --timeout-seconds regardless of step status (default 180s).
- All step failures include a human-readable detail string in the output JSON.
**Depends on:** 16.4-T03, 16.4-T04, 16.4-T05, 16.4-T06

### 16.4-T08 — Integrate smoke-runner into CI/CD pipeline post-prod-deploy stage  _(45 min)_
**Context:** Per OPS-13 §4.1 (stage 6: smoke tests pass as gate to next stage) and §4.4 (automated rollback if smoke fails within 5 minutes). The CI/CD pipeline (GitHub Actions or GitLab CI) must invoke smoke_runner after every prod deployment. If smoke_runner exits 1, the pipeline automatically re-deploys the previous image tag. The previous image tag is stored in a pipeline variable PREV_IMAGE_TAG set before the deployment step.
**Steps:** In the pipeline definition file (.github/workflows/deploy.yml or .gitlab-ci.yml), add a smoke-test job that runs after the prod deploy job.; Set the job to call: smoke-runner --env prod --timeout-seconds 180.; On exit code 1: trigger a rollback job that runs: kubectl set image deployment/hub-api hub-api=${PREV_IMAGE_TAG} -n gmepay-prod (or cloud-equivalent).; Archive the smoke_runner JSON output as a pipeline artifact.; Configure the pipeline so the smoke-test job fails the pipeline on exit code 1, preventing further stages from running.
**Deliverable:** Pipeline job smoke_test_prod in the CI/CD config that gates post-deploy, triggers automated rollback on failure, and archives JSON output.
**Acceptance / logic checks:**
- Pipeline definition contains a job smoke_test_prod that depends on deploy_prod and runs smoke-runner.
- On simulated smoke failure (mocked step returning exit 1), the rollback job executes and redeploys PREV_IMAGE_TAG.
- On smoke success (exit 0), pipeline continues and smoke JSON artifact is saved.
- Rollback job requires no human intervention (fully automated per OPS-13 §4.4).
- Pipeline aborts any post-smoke stages when smoke exits 1.
**Depends on:** 16.4-T07

### 16.4-T09 — Configure Prometheus metrics scraping for Hub API service (RED metrics)  _(45 min)_
**Context:** Per OPS-13 §7.1, all API services must expose RED metrics: api_request_rate, api_error_rate, api_latency_p50/p95/p99. These are used for SLO dashboards and alerting. In Java (Spring Boot + Micrometer), expose a /actuator/prometheus endpoint. Metrics must be labeled with endpoint, partner_id, http_status_code. Prometheus scrape config must target all Hub API pods via Kubernetes service discovery.
**Steps:** Add Micrometer Prometheus dependency to Hub API pom.xml and expose /actuator/prometheus.; Register a custom timer for each API endpoint tagged with tags: endpoint, partner_id, http_status.; Add Prometheus scrape config (prometheus.yml or Kubernetes ServiceMonitor) to target hub-api service on port 8080, path /actuator/prometheus, interval 15s.; Verify that after a test request, metrics http_server_requests_seconds_count and http_server_requests_seconds_sum appear in Prometheus with correct labels.; Confirm that api_error_rate can be computed as rate(http_server_requests_seconds_count{status=~5..}[5m]) / rate(http_server_requests_seconds_count[5m]).
**Deliverable:** Prometheus scrape config (ServiceMonitor or prometheus.yml) and verified metric labels for Hub API RED metrics.
**Acceptance / logic checks:**
- GET /actuator/prometheus returns HTTP 200 with text/plain content containing http_server_requests_seconds_count.
- After calling POST /v1/rates, the metric appears with labels endpoint=/v1/rates, http_status=200.
- Prometheus scrape job hub-api shows state=UP in Prometheus targets UI.
- A 5xx response from any endpoint increments the metric with http_status=500 label.
- api_latency_p99 is queryable as histogram_quantile(0.99, rate(http_server_requests_seconds_bucket[5m])).
**Depends on:** 16.4-T01

### 16.4-T10 — Configure Prometheus metrics for Batch/SFTP Worker (batch job health metrics)  _(45 min)_
**Context:** Per OPS-13 §7.1 (USE metrics for SFTP worker: job duration, job queue depth, SFTP connection errors) and §6.4 (alerts on missed batch windows). The batch worker must expose: batch_job_duration_seconds (labeled by job_id), batch_job_status (gauge: 0=idle, 1=running, 2=failed, labeled by job_id), sftp_connection_errors_total (counter), and batch_job_last_success_timestamp (gauge, epoch seconds, labeled by job_id). These feed the Batch Health dashboard and missed-window alerts.
**Steps:** Add Micrometer Prometheus metrics to the batch worker service.; Register batch_job_duration_seconds as a Timer, tagged with job_id (e.g. JOB-ZP-01).; Register batch_job_status as a Gauge (0=idle,1=running,2=failed), updated at each job state transition.; Register sftp_connection_errors_total as a Counter, incremented on every failed SFTP connect attempt.; Register batch_job_last_success_timestamp as a Gauge set to System.currentTimeMillis()/1000 on each successful job completion.
**Deliverable:** Prometheus metrics instrumentation in the batch worker service exposing batch_job_duration_seconds, batch_job_status, sftp_connection_errors_total, and batch_job_last_success_timestamp.
**Acceptance / logic checks:**
- After a successful JOB-ZP-01 run, batch_job_last_success_timestamp{job_id=JOB-ZP-01} updates to the current epoch time.
- After 3 consecutive SFTP failures, sftp_connection_errors_total >= 3.
- batch_job_status{job_id=JOB-ZP-01} transitions from 0 to 1 when the job starts and to 2 when it fails.
- GET /actuator/prometheus on the batch worker includes all 4 metric families.
- batch_job_duration_seconds{job_id=JOB-ZP-01} records the wall-clock duration of the most recent run.
**Depends on:** 16.4-T09

### 16.4-T11 — Configure Prometheus metrics for prefunding ledger (balance and deduction metrics)  _(40 min)_
**Context:** Per OPS-13 §7.1 (USE metrics: prefunding balance per partner) and §7.5.2 (alerts: balance < USD 10,000 = P2; < USD 2,000 = P1). The prefunding service must expose: prefunding_balance_usd (gauge, labeled by partner_id, current available USD balance) and prefunding_deduction_usd_total (counter, labeled by partner_id, cumulative USD deducted). These feed the Prefunding Monitor dashboard and low-balance alerts.
**Steps:** In the prefunding ledger service, after every SELECT...FOR UPDATE deduction, update the gauge prefunding_balance_usd{partner_id} to the new balance.; Register prefunding_deduction_usd_total as a Counter; increment by the deducted USD amount on each successful deduction.; Expose both metrics at /actuator/prometheus on the prefunding service.; Add a Prometheus scrape target for the prefunding service.; Verify by manually deducting a test amount in the integration environment and querying Prometheus to confirm the gauge and counter updated.
**Deliverable:** prefunding_balance_usd and prefunding_deduction_usd_total Prometheus metrics exposed by the prefunding ledger service, with per-partner labels.
**Acceptance / logic checks:**
- prefunding_balance_usd{partner_id=SENDMN} matches the value returned by GET /v1/partners/SENDMN/prefunding/balance after a deduction.
- After a USD 100 deduction for SENDMN, prefunding_deduction_usd_total{partner_id=SENDMN} increases by 100.
- prefunding_balance_usd never goes negative (any deduction that would result in < 0 is rejected at the application layer before the metric is updated).
- Gauge is updated within 1 second of a deduction completing.
- Metrics are present for all configured OVERSEAS partners, not only SENDMN.
**Depends on:** 16.4-T09

### 16.4-T12 — Configure Prometheus alerting rules for API SLO alerts (P1/P2)  _(50 min)_
**Context:** Per OPS-13 §7.5.1: P1 alert if 5xx error rate > 1% over 5 min; P2 if > 0.5% over 10 min; P2 if p95 latency > 500ms for 5 min; P1 if payment endpoint health check fails > 60s. These rules are defined as Prometheus alerting rules (alerts.yml) and routed to PagerDuty (P1) or Ops Slack (P2) via Alertmanager. Alert labels must include severity, env, and service.
**Steps:** Create prometheus/alerts/api_slo.yml with four alerting rules: APIErrorRateCritical (expr: rate(http_server_requests_seconds_count{status=~5..}[5m]) / rate(http_server_requests_seconds_count[5m]) > 0.01, for: 5m, severity: P1), APIErrorRateWarning (>0.005, for: 10m, severity: P2), APILatencyP95Breach (histogram_quantile(0.95,...) > 0.5, for: 5m, severity: P2), PaymentEndpointDown (up{job=hub-api} == 0, for: 1m, severity: P1).; Add label env: prod and service: hub-api to each rule.; Load the rules file into Prometheus via rule_files configuration.; Verify rules appear in the Prometheus Alerts UI with state=inactive.; Configure Alertmanager routes: P1 -> PagerDuty receiver; P2 -> Ops Slack receiver.
**Deliverable:** prometheus/alerts/api_slo.yml with 4 alert rules loaded into Prometheus, and Alertmanager routing P1->PagerDuty, P2->Ops Slack.
**Acceptance / logic checks:**
- All 4 rules appear in Prometheus Alerts UI with state=inactive under normal conditions.
- Injecting a synthetic 5xx spike > 1% for 5+ minutes causes APIErrorRateCritical to fire (state=firing) and a PagerDuty notification is sent.
- APILatencyP95Breach fires when p95 exceeds 500ms for 5 continuous minutes.
- Each alert label includes severity (P1 or P2), env=prod, service=hub-api.
- PaymentEndpointDown fires within 1 minute of the Hub API health check returning DOWN.
**Depends on:** 16.4-T09

### 16.4-T13 — Configure Prometheus alerting rules for prefunding low-balance alerts (P1/P2)  _(45 min)_
**Context:** Per OPS-13 §7.5.2: P2 alert when any OVERSEAS partner balance < USD 10,000; P1 when < USD 2,000; P1 when = 0. Thresholds are configurable per partner (default 10,000). The alert must fire per-partner, labeled with partner_id, and route: P1 -> PagerDuty + Ops Slack; P2 -> email to partner contact + Ops Slack.
**Steps:** Create prometheus/alerts/prefunding.yml with rules: PrefundingLowBalance (prefunding_balance_usd < 10000, for: 1m, severity: P2), PrefundingCritical (prefunding_balance_usd < 2000, for: 1m, severity: P1), PrefundingZero (prefunding_balance_usd == 0, for: 30s, severity: P1).; Include label partner_id from the metric label in each alert.; Configure Alertmanager route for prefunding.severity=P1: PagerDuty + Ops Slack; P2: partner-email receiver + Ops Slack.; Test by setting prefunding_balance_usd{partner_id=SENDMN} to a test value of 1500 in a staging Prometheus and confirming PrefundingCritical fires.; Verify alert auto-resolves when balance rises above threshold.
**Deliverable:** prometheus/alerts/prefunding.yml with 3 prefunding alert rules and Alertmanager routes for P1 and P2 channels.
**Acceptance / logic checks:**
- PrefundingLowBalance fires within 1 minute when partner balance drops below 10000 USD.
- PrefundingCritical fires within 1 minute when partner balance drops below 2000 USD.
- PrefundingZero fires within 30 seconds when balance reaches exactly 0.
- All alerts carry label partner_id identifying the affected OVERSEAS partner.
- Alert resolves automatically and sends a resolution notification when balance exceeds the threshold.
**Depends on:** 16.4-T11, 16.4-T12

### 16.4-T14 — Configure Prometheus alerting rules for batch window breach alerts  _(50 min)_
**Context:** Per OPS-13 §6.4 and §7.5.3: P1 alert if JOB-ZP-01/02 not submitted by 01:45 KST (15 min before 02:00 window); P1 if ZP0012/ZP0022 not received by 05:30 KST; P1 if ZP0061 not submitted by 05:15 KST; P1 if ZP0062 not received by 10:30 KST; P2 for afternoon cycle (ZP0063 by 14:15, ZP0064 by 19:30, ZP0065/66 by 22:15). Alert condition: batch_job_last_success_timestamp{job_id=X} is more than N seconds old at the scheduled check time. KST = UTC+9.
**Steps:** Create prometheus/alerts/batch_windows.yml with alerting rules for each of the 8 critical/high batch windows.; Use the condition: (time() - batch_job_last_success_timestamp{job_id=JOB-ZP-01}) > threshold_seconds where threshold_seconds is the number of seconds past midnight UTC when the alert should fire.; Add a BatchJobFailed rule: batch_job_status{job_id=~JOB-ZP-.*} == 2 with for: 1m, severity: P1.; Route P1 rules to PagerDuty + Ops Slack; P2 rules to Ops Slack only.; Document the UTC equivalent of each KST window in a comment above each rule.
**Deliverable:** prometheus/alerts/batch_windows.yml with alerting rules for all 8 ZeroPay batch window breaches and a generic BatchJobFailed rule.
**Acceptance / logic checks:**
- BatchJobFailed fires within 1 minute when any job enters status=2 (FAILED after 3 retries).
- JOB-ZP-01 missed-window alert is defined with the correct UTC threshold (01:45 KST = 16:45 UTC previous day).
- All P1 batch alerts are routed to PagerDuty; P2 batch alerts are routed to Ops Slack only.
- Rules file loads into Prometheus without syntax errors (promtool check rules prometheus/alerts/batch_windows.yml passes).
- At least one alert includes the job_id label so operators know which job triggered the alert.
**Depends on:** 16.4-T10, 16.4-T12

### 16.4-T15 — Configure Prometheus alerting rules for settlement financial integrity (pool identity and reconciliation)  _(45 min)_
**Context:** Per OPS-13 §7.5.5: P1 alert if pool identity assertion fails (|collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost| > 0.01 USD); P1 if settlement reconciliation mismatch detected; P1 if revenue ledger mismatch. The rate engine must emit a counter pool_identity_violation_total incremented whenever a violation is detected during transaction commit. A reconciliation job emits settlement_reconciliation_mismatch_count per settlement date.
**Steps:** Define a Prometheus counter pool_identity_violation_total in the rate engine service, incremented on every pool identity check failure at transaction commit.; Define a Prometheus gauge settlement_reconciliation_mismatch_count labeled by settlement_date, set by the daily reconciliation job.; Create prometheus/alerts/financial_integrity.yml with: PoolIdentityViolation (rate(pool_identity_violation_total[5m]) > 0, for: 0m, severity: P1), ReconciliationMismatch (settlement_reconciliation_mismatch_count > 0, for: 1m, severity: P1).; Route all P1 financial integrity alerts to PagerDuty + Ops Slack + Engineering Slack.; Test by injecting a synthetic pool_identity_violation_total increment in staging and confirming the alert fires immediately.
**Deliverable:** pool_identity_violation_total counter, settlement_reconciliation_mismatch_count gauge, and prometheus/alerts/financial_integrity.yml with P1 rules.
**Acceptance / logic checks:**
- PoolIdentityViolation fires immediately (for: 0m) on the first pool identity violation increment.
- ReconciliationMismatch fires within 1 minute when settlement_reconciliation_mismatch_count > 0 for any settlement_date.
- Financial integrity alerts route to all three channels: PagerDuty, Ops Slack, Engineering Slack.
- pool_identity_violation_total is never decremented (counter semantics only).
- Alert annotations include a runbook_url pointing to the OPS-13 §8.10 reconciliation runbook.
**Depends on:** 16.4-T12

### 16.4-T16 — Configure Prometheus alerting rules for scheme connectivity and SFTP alerts  _(45 min)_
**Context:** Per OPS-13 §7.5.4: P1 if ZeroPay SFTP unreachable after 3 retries; P2 if ZeroPay API latency > 5s p95 over 5 min; P2 if Partner B quote API error rate > 1% over 5 min; P2 if Partner B quote deviation > 1% on > 5% of quotes. Metrics required: sftp_connection_errors_total (from T10), zeropay_api_latency_seconds histogram, partner_b_quote_error_rate, partner_b_quote_deviation_rate.
**Steps:** Register zeropay_api_latency_seconds as a histogram in the scheme adapter, recording per-call HTTP latency to ZeroPay API.; Register partner_b_quote_errors_total counter and partner_b_quote_deviation_total counter (incremented when deviation > 1%).; Create prometheus/alerts/scheme_connectivity.yml with rules: SFTPConnectionFailure (sftp_connection_errors_total increases by 3 within a window, severity: P1), ZeroPayAPILatency (histogram_quantile(0.95,...) > 5, for: 5m, severity: P2), PartnerBQuoteDown (rate(partner_b_quote_errors_total[5m]) > 0.01, severity: P2), PartnerBQuoteDeviation (rate(partner_b_quote_deviation_total[5m]) / rate(partner_b_quote_errors_total[5m]) > 0.05, severity: P2).; Route P1 to PagerDuty + Ops Slack; P2 to Ops Slack.; Verify rules load cleanly with promtool.
**Deliverable:** prometheus/alerts/scheme_connectivity.yml and new metric registrations for ZeroPay API latency and Partner B quote errors.
**Acceptance / logic checks:**
- SFTPConnectionFailure fires when sftp_connection_errors_total increments 3+ times within the evaluation window.
- ZeroPayAPILatency fires after 5 consecutive minutes of p95 ZeroPay API latency > 5 seconds.
- PartnerBQuoteDown fires when partner B quote error rate exceeds 1% over a 5-minute window.
- promtool check rules prometheus/alerts/scheme_connectivity.yml exits 0 with no errors.
- All P1 alerts in this file route to PagerDuty.
**Depends on:** 16.4-T10, 16.4-T12

### 16.4-T17 — Configure Prometheus alerting rules for webhook delivery failures  _(40 min)_
**Context:** Per OPS-13 §7.5.6: P2 alert if partner webhook returns non-2xx after max retries; P2 if webhook queue depth > 500; P3 if webhook dispatcher p95 > 30s. Metrics: webhook_delivery_failures_total (counter, labeled by partner_id), webhook_queue_depth (gauge), webhook_dispatch_latency_seconds (histogram). Max retries = 5 in production (OPS-13 §5.3).
**Steps:** Register webhook_delivery_failures_total counter in webhook dispatcher (increment after all retries exhausted, labeled by partner_id).; Register webhook_queue_depth gauge updated in real-time from the message queue consumer lag metric.; Register webhook_dispatch_latency_seconds histogram timing from dispatch trigger to final delivery (or failure).; Create prometheus/alerts/webhooks.yml with: WebhookDeliveryFailure (rate(webhook_delivery_failures_total[5m]) > 0, for: 1m, severity: P2), WebhookQueueDepth (webhook_queue_depth > 500, for: 5m, severity: P2), WebhookLatencyBreach (histogram_quantile(0.95,...) > 30, for: 5m, severity: P3).; Route P2 to Ops Slack; P3 to Ops email.
**Deliverable:** prometheus/alerts/webhooks.yml and webhook dispatcher metric registrations (webhook_delivery_failures_total, webhook_queue_depth, webhook_dispatch_latency_seconds).
**Acceptance / logic checks:**
- WebhookDeliveryFailure fires within 1 minute when a webhook exhausts all 5 retries without a 2xx response.
- WebhookQueueDepth fires when queue_depth exceeds 500 for 5 continuous minutes.
- WebhookLatencyBreach fires when p95 dispatch latency exceeds 30 seconds for 5 minutes; routes to Ops email (P3).
- webhook_delivery_failures_total includes partner_id label so the affected partner is identifiable.
- promtool check rules prometheus/alerts/webhooks.yml exits 0.
**Depends on:** 16.4-T12

### 16.4-T18 — Build Grafana Operations Overview dashboard (API success rate, active transactions, prefunding, batch status)  _(55 min)_
**Context:** Per OPS-13 §7.4, the Operations Overview dashboard targets GME Ops and must display: API success rate per partner, active transaction count, prefunding USD balance per OVERSEAS partner vs threshold, and batch job status (last run + outcome per job). This dashboard is reviewed at the twice-daily Ops check-in (09:00 and 18:00 KST per OPS-13 §12.4).
**Steps:** Create Grafana dashboard JSON file dashboards/operations_overview.json.; Add panel 1: API success rate (1 - api_error_rate) per partner, time series, last 1h, alert threshold line at 99%.; Add panel 2: Active transactions count (transactions in non-terminal state) from a DB-backed metric.; Add panel 3: Prefunding balance per OVERSEAS partner, bar gauge, with threshold markers at 10000 USD (yellow) and 2000 USD (red).; Add panel 4: Batch job status table - columns: job_id, last_run_at (from batch_job_last_success_timestamp), status (from batch_job_status), last_duration_s.
**Deliverable:** dashboards/operations_overview.json Grafana dashboard with 4 panels: API success rate, active transactions, prefunding balances, batch job status.
**Acceptance / logic checks:**
- Dashboard JSON loads into Grafana without validation errors.
- Prefunding panel shows red threshold at 2000 USD and yellow at 10000 USD for each OVERSEAS partner row.
- Batch job status table shows one row per JOB-ZP-* with last_run_at and status values populated.
- API success rate panel shows per-partner breakdown, not an aggregate only.
- Dashboard time range defaults to last 1 hour, auto-refresh every 30 seconds.
**Depends on:** 16.4-T09, 16.4-T10, 16.4-T11

### 16.4-T19 — Build Grafana API SLO dashboard (p95 latency vs SLO, error rate by endpoint and partner)  _(50 min)_
**Context:** Per OPS-13 §7.4, the API SLO dashboard targets Engineering and must show: p95 latency vs 500ms SLO (NFR-10), error rate by endpoint, and partner breakdown. The p95 latency SLO is 500ms per OPS-13 §7.1. p99 latency is also displayed for visibility. Time window: last 1 hour by default, with 5-minute granularity.
**Steps:** Create dashboards/api_slo.json.; Panel 1: p95 and p99 latency time series (histogram_quantile from http_server_requests_seconds_bucket), with a horizontal reference line at 500ms SLO threshold.; Panel 2: 5xx error rate percentage by endpoint over time.; Panel 3: Per-partner API success rate heatmap or time series.; Panel 4: Rate quote endpoint (POST /v1/rates) success rate specifically, since OPS-13 §7.5.1 flags a separate alert at > 5% failure rate.
**Deliverable:** dashboards/api_slo.json Grafana dashboard with p95/p99 latency, error rate by endpoint, per-partner success rate, and rate-quote success rate panels.
**Acceptance / logic checks:**
- p95 latency panel includes a 500ms reference line and visually indicates SLO breach.
- Error rate panel is broken down by endpoint path label, not aggregated.
- Rate-quote panel (POST /v1/rates) is shown as a dedicated panel, not combined with other endpoints.
- Dashboard variable allows filtering by partner_id so Ops can isolate one partner.
- JSON loads without Grafana validation errors.
**Depends on:** 16.4-T09

### 16.4-T20 — Build Grafana Batch Health dashboard (job timeline, last run, file receipt per window)  _(55 min)_
**Context:** Per OPS-13 §7.4, the Batch Health dashboard targets Ops and Engineering. It must show: ZeroPay batch job timeline (expected vs actual completion time per KST window), last run status and timestamp per job, and file receipt confirmation. Windows per OPS-13 §6.2: JOB-ZP-01 by 02:00 KST, JOB-ZP-03 expected by 05:00, JOB-ZP-05 by 05:00, JOB-ZP-06 expected by 10:00, JOB-ZP-07 by 14:00, JOB-ZP-08 by 19:00, JOB-ZP-09 by 22:00.
**Steps:** Create dashboards/batch_health.json.; Panel 1: Status table - columns job_id, expected_window_kst, last_success_at (from batch_job_last_success_timestamp converted to KST), status, duration_s. Row colour: green=success within window, orange=success but late, red=failed/missing.; Panel 2: Job duration trend (bar chart), one bar per job per day.; Panel 3: SFTP connection error rate time series (sftp_connection_errors_total rate).; Panel 4: Text panel listing the batch critical path dependency chain for quick reference.
**Deliverable:** dashboards/batch_health.json with job status table, duration trend, SFTP error rate, and critical path reference panel.
**Acceptance / logic checks:**
- Status table shows all 13 JOB-ZP-* jobs with expected KST window and last success timestamp.
- A job that completed after its window shows orange (late), not green.
- A job in FAILED state shows red in the status table.
- SFTP error rate panel uses sftp_connection_errors_total metric rate.
- Dashboard JSON loads into Grafana without errors.
**Depends on:** 16.4-T10

### 16.4-T21 — Build Grafana Prefunding Monitor dashboard (USD balance per partner, deduction rate, alert status)  _(50 min)_
**Context:** Per OPS-13 §7.4, the Prefunding Monitor dashboard targets Ops and Finance. Must show: current USD balance per OVERSEAS partner vs configured threshold, 24h rolling deduction rate (USD/hour), and whether any partner has an active low-balance alert. Partners in Phase 3+4: SendMN (default threshold USD 10,000), T-Bank (Phase 4). Balance source: prefunding_balance_usd gauge; deduction rate: rate(prefunding_deduction_usd_total[24h]).
**Steps:** Create dashboards/prefunding_monitor.json.; Panel 1: Stat panel for each OVERSEAS partner showing current available_balance_usd, with colour thresholds: green >= 10000, yellow 2000-9999, red < 2000.; Panel 2: 24-hour rolling deduction rate (USD/hour) per partner as a time series.; Panel 3: Low-balance alert status table: partner_id, current_balance, threshold, alert_active (from Grafana alert state).; Panel 4: Historical balance trend over last 7 days per partner.
**Deliverable:** dashboards/prefunding_monitor.json with current balance stat panels, deduction rate, alert status table, and 7-day trend.
**Acceptance / logic checks:**
- Balance stat panel turns red when prefunding_balance_usd < 2000 and yellow when < 10000.
- Deduction rate panel shows rate(prefunding_deduction_usd_total[24h]) correctly segmented by partner_id.
- Alert status table reflects live Grafana alert state for each partner.
- Dashboard JSON loads without errors.
- Panel refresh interval <= 30 seconds so Ops sees near-real-time balances.
**Depends on:** 16.4-T11

### 16.4-T22 — Configure structured JSON log shipping from all services to log aggregation backend  _(50 min)_
**Context:** Per OPS-13 §7.2, all services must emit JSON-structured logs with required fields: timestamp (ISO-8601 UTC), level, service, env, trace_id, span_id, partner_id, transaction_id, event, message. In production, monetary fields (collection_amount, prefund_balance, rate components) must NOT appear in logs (only IDs). Log shipping uses a sidecar or agent (e.g. Fluent Bit or Logstash) per pod sending to the log aggregation backend (e.g. OpenSearch or Loki).
**Steps:** Confirm each service (hub-api, batch-worker, admin-api, webhook-dispatcher) emits logs in the required JSON schema via its logging framework (Logback/structlog).; Deploy a Fluent Bit DaemonSet (or per-pod sidecar) in gmepay-prod namespace configured to tail container stdout and forward to the log aggregation endpoint.; Add a filter in Fluent Bit to drop any log lines containing the field names collection_amount, prefund_balance, cost_rate_pay, cost_rate_coll from production streams.; Verify that a test INFO log from hub-api with all required fields appears in the aggregation backend within 30 seconds.; Confirm a log line with collection_amount is dropped and does not appear in the aggregation backend.
**Deliverable:** Fluent Bit (or equivalent) DaemonSet config shipping structured JSON logs from all 4 services to the aggregation backend, with monetary field filtering in prod.
**Acceptance / logic checks:**
- A hub-api log line for a POST /v1/rates call appears in the backend with all 9 required fields (timestamp, level, service, env, trace_id, span_id, partner_id, transaction_id, event).
- A log line containing collection_amount is absent from the production aggregation index.
- All 4 services (hub-api, batch-worker, admin-api, webhook-dispatcher) have active log shipping confirmed in the Fluent Bit status.
- Log lines appear in the aggregation backend within 30 seconds of emission.
- env field is correctly set to prod in all production log lines.
**Depends on:** 16.4-T01

### 16.4-T23 — Configure OpenTelemetry distributed tracing across Hub API and downstream services  _(55 min)_
**Context:** Per OPS-13 §7.3, all services must be instrumented with OpenTelemetry. A trace_id is generated at the load balancer and propagated through Hub API -> DB queries -> scheme adapter -> batch worker job steps. The 8-step transaction trail (rate_quote_issued, payment_initiated, prefund_deducted, scheme_request_sent, scheme_response_received, transaction_committed, webhook_dispatched, webhook_delivered) must be visible as spans in the tracing backend (Jaeger or equivalent). trace_id must appear in all structured log lines.
**Steps:** Add OpenTelemetry Java agent (or Spring Boot OTel autoconfiguration) to hub-api, batch-worker, and webhook-dispatcher.; Configure the OTel exporter to send traces to the Jaeger (or OTLP collector) endpoint in the management zone.; Verify that each of the 8 transaction trail steps emits a named span: e.g. span name rate_quote_issued, payment_initiated, etc.; Confirm trace_id propagation: the same trace_id appears in hub-api logs, DB query spans, and scheme adapter spans for one end-to-end transaction.; In the Admin System transaction detail view, confirm the 8-step trail is queryable (per OPS-13 §7.3 and PRD-07 §6.5).
**Deliverable:** OpenTelemetry instrumentation on hub-api, batch-worker, and webhook-dispatcher with 8 named transaction spans visible in Jaeger, and trace_id in all log lines.
**Acceptance / logic checks:**
- A test payment transaction produces a Jaeger trace with 8 named spans matching the step names in OPS-13 §7.3.
- The same trace_id appears in hub-api log lines and in the corresponding Jaeger trace.
- Spans include span.kind=SERVER for inbound API calls and span.kind=CLIENT for outbound ZeroPay calls.
- The Admin System transaction detail view displays all 8 steps for the test transaction with timestamps.
- No PII or monetary amounts appear in trace attributes.
**Depends on:** 16.4-T22

### 16.4-T24 — Configure Alertmanager routing, PagerDuty integration, and Ops Slack integration  _(50 min)_
**Context:** Per OPS-13 §7.5, all P1 alerts route to PagerDuty (wake someone now) and Ops Slack; P2 alerts route to Ops Slack; P3 alerts route to Ops email; P4 are informational. Alertmanager must be deployed and configured with receivers for PagerDuty (integration key from PagerDuty account), Ops Slack (webhook URL), and Ops email. Alert grouping: group by alertname + env; group_wait 30s; group_interval 5m; repeat_interval 4h for P1, 12h for P2.
**Steps:** Deploy Alertmanager in the management zone alongside Prometheus.; Configure alertmanager.yml with three receivers: pagerduty (using PagerDuty Events API v2 integration key), slack-ops (Ops Slack channel webhook URL), email-ops (SMTP config to ops@gmeremit.com).; Define routing tree: P1 -> pagerduty + slack-ops; P2 -> slack-ops; P3 -> email-ops; P4 -> null receiver.; Set inhibition rules: P1 alerts suppress P2 alerts for the same service to reduce noise during major incidents.; Test by firing a synthetic P1 test alert and confirming PagerDuty incident is created and Slack message appears.
**Deliverable:** alertmanager.yml with configured PagerDuty, Slack, and email receivers and routing tree; verified with synthetic test alert.
**Acceptance / logic checks:**
- Firing a test P1 alert creates a PagerDuty incident within 2 minutes.
- Firing a test P2 alert posts a Slack message in the Ops channel but does NOT create a PagerDuty incident.
- A P1 alert for service X suppresses the concurrent P2 alert for the same service X (inhibition rule active).
- Alertmanager UI shows all 3 receivers as active (no config errors).
- Alert notification includes alert name, severity, env=prod, and a runbook_url annotation where configured.
**Depends on:** 16.4-T12, 16.4-T13, 16.4-T14, 16.4-T15, 16.4-T16, 16.4-T17

### 16.4-T25 — Validate synthetic alert firing end-to-end for each alert catalog category  _(55 min)_
**Context:** Per OPS-13 §12.1.6 go-live checklist: all alert catalog entries (§7.5) must be configured and tested with synthetic alerts before go-live. This test ticket systematically fires one synthetic alert per category (API SLO, prefunding, batch, scheme, settlement, webhook) in the staging environment and verifies the correct channel receives the notification. Test is documented as a checklist in the go-live pre-check record.
**Steps:** In staging Prometheus, use the PromQL amtool alert add command to fire one test alert per catalog category: APIErrorRateCritical, PrefundingCritical, BatchJobFailed, SFTPConnectionFailure, PoolIdentityViolation, WebhookDeliveryFailure.; For each fired alert, confirm: PagerDuty incident created (P1 alerts), Slack message posted (P1+P2), email sent (P3).; Confirm each alert resolves when amtool alert expire is called, and a resolution notification is sent.; Record pass/fail per alert in a staging sign-off document smoke_alert_validation_YYYY-MM-DD.md.; Ensure no test alerts fire against production Alertmanager (staging Alertmanager is separate).
**Deliverable:** smoke_alert_validation_YYYY-MM-DD.md sign-off document with pass/fail result for each of 6 synthetic alert categories.
**Acceptance / logic checks:**
- All 6 categories produce a PagerDuty incident when fired as P1 severity.
- All 6 P1 alerts also post to Ops Slack.
- Resolution notifications are received for all 6 alerts after expiry.
- Test is run against staging Alertmanager only; no production alerts fired during testing.
- Sign-off document is countersigned by Ops Lead before go-live.
**Depends on:** 16.4-T24

### 16.4-T26 — Implement automated rollback trigger test in staging pipeline  _(45 min)_
**Context:** Per OPS-13 §4.4, if post-deployment smoke tests fail within 5 minutes, the pipeline automatically redeploys the previous image tag. This ticket validates that the rollback mechanism actually works in the staging pipeline by deliberately deploying a broken image (returns 503 on /health), confirming smoke fails, and confirming the rollback to the previous good image occurs without human intervention.
**Steps:** In the staging CI/CD pipeline, add a test job inject_smoke_failure that deploys a stub image that returns HTTP 503 on GET /health.; Trigger a staging deployment with this stub image.; Confirm the smoke-test job runs, detects the 503, exits with code 1, and the pipeline triggers the rollback job.; Confirm the rollback job redeploys the PREV_IMAGE_TAG and subsequent smoke tests pass (suite_status=PASSED).; Record the total elapsed time from smoke failure to rollback complete; assert it is < 5 minutes per OPS-13 §4.4.
**Deliverable:** Staging pipeline test job inject_smoke_failure that validates end-to-end automated rollback is functional and completes in < 5 minutes.
**Acceptance / logic checks:**
- Deploying the 503-returning stub image causes smoke_test job to exit with code 1 and suite_status=FAILED.
- Rollback job executes automatically (no human approval required) and redeploys PREV_IMAGE_TAG.
- Post-rollback smoke tests pass (suite_status=PASSED).
- Total time from smoke failure to rollback completion is < 5 minutes.
- PREV_IMAGE_TAG in the rollback deployment matches the image tag deployed before the broken image.
**Depends on:** 16.4-T08

### 16.4-T27 — Write unit tests for smoke-runner: mock HTTP responses and verify pass/fail logic  _(40 min)_
**Context:** Per the ticket brief, test tickets are required for logic-bearing work. The smoke-runner (T07) contains logic for: collecting step results, evaluating suite_status (all PASSED -> PASSED, any FAILED -> FAILED), enforcing --timeout-seconds, and producing structured JSON output. Unit tests use mocked HTTP clients to simulate step responses.
**Steps:** Create test file tests/test_smoke_runner.py (or equivalent JUnit test).; Test case 1: all 5 steps return mocked 200/PASSED -> assert suite_status=PASSED, exit code 0, steps array has 5 entries.; Test case 2: step smoke_rate_domestic returns mocked 500 -> assert suite_status=FAILED, exit code 1, that step has status=FAILED and detail contains the HTTP status.; Test case 3: total elapsed exceeds --timeout-seconds=5 (mocked sleep) -> assert runner terminates and suite_status=FAILED with timeout_exceeded detail.; Test case 4: smoke_prefunding_balance returns 200 but body is missing available_balance_usd -> assert step status=FAILED.
**Deliverable:** tests/test_smoke_runner.py with 4 unit test cases covering pass, partial failure, timeout, and missing-field failure scenarios.
**Acceptance / logic checks:**
- test_all_pass: suite_status=PASSED, exit code 0, steps array length = 5.
- test_one_step_fails: suite_status=FAILED, exit code 1, failed step identified by name in output.
- test_timeout_exceeded: runner exits with FAILED when elapsed > timeout_seconds regardless of remaining steps.
- test_missing_field: step is FAILED when response body lacks expected field (available_balance_usd).
- All 4 tests pass with mvn test or pytest with no mocked external calls reaching a real server.
**Depends on:** 16.4-T07

### 16.4-T28 — Write unit tests for Prometheus alert rule expressions (promtool rule check + PromQL unit tests)  _(45 min)_
**Context:** Per the ticket brief, alert rules are logic-bearing and require explicit test coverage. Prometheus supports unit testing via YAML test files (promtool test rules). Tests must cover: APIErrorRateCritical threshold boundary (exactly 1% = no fire, 1.01% = fire), PrefundingCritical threshold (2001 USD = no fire, 1999 USD = fire), BatchJobFailed (status=2 fires, status=1 does not), PoolIdentityViolation (counter increment fires immediately).
**Steps:** Create tests/prometheus/alert_tests.yml using Prometheus unit test format (interval, input_series, alert_rule_test).; Test 1: set api error rate to 0.0095 (< 1%) for 6 minutes -> APIErrorRateCritical should not fire.; Test 2: set api error rate to 0.011 (> 1%) for 6 minutes -> APIErrorRateCritical should fire.; Test 3: set prefunding_balance_usd{partner_id=SENDMN} to 1999 -> PrefundingCritical fires; to 2001 -> does not fire.; Test 4: set batch_job_status{job_id=JOB-ZP-01} to 2 -> BatchJobFailed fires after 1 minute.; Run promtool test rules tests/prometheus/alert_tests.yml and confirm all tests pass.
**Deliverable:** tests/prometheus/alert_tests.yml with 4+ unit test cases for alert rule thresholds; all pass under promtool test rules.
**Acceptance / logic checks:**
- promtool test rules tests/prometheus/alert_tests.yml exits 0 with SUCCESS.
- APIErrorRateCritical does NOT fire at exactly 1.00% error rate (boundary condition).
- APIErrorRateCritical fires at 1.01% error rate sustained for 5+ minutes.
- PrefundingCritical fires at balance=1999 USD and does not fire at balance=2001 USD.
- BatchJobFailed fires exactly when batch_job_status transitions to 2.
**Depends on:** 16.4-T12, 16.4-T13, 16.4-T14, 16.4-T15

### 16.4-T29 — Validate monitoring stack end-to-end in staging: confirm dashboards, metrics, and log correlation  _(55 min)_
**Context:** Per OPS-13 §12.1.6, before go-live: dashboards must be reviewed by Ops, log aggregation from all services confirmed, and distributed tracing confirmed end-to-end (8-step trail visible). This ticket is a structured validation run in staging that exercises a real payment flow and confirms all observability layers capture it correctly.
**Steps:** In the staging environment, execute a full payment flow: POST /v1/rates (GME Remit domestic, target_payout=10000 KRW) -> POST /v1/payments -> confirm APPROVED status.; In Prometheus, confirm api_request_rate and api_latency_p99 metrics updated for the /v1/payments endpoint.; In Jaeger (or tracing backend), locate the trace for the payment transaction_id; confirm all 8 spans are present with correct span names.; In the log aggregation backend, search by transaction_id; confirm log lines from hub-api and webhook-dispatcher appear with matching trace_id.; In Grafana Operations Overview dashboard, confirm the transaction appears in the active-to-completed count, and batch status panel shows correct job states.
**Deliverable:** Staging validation checklist doc (staging_observability_validation_YYYY-MM-DD.md) with pass/fail for each of 5 verification steps, countersigned by Ops Lead.
**Acceptance / logic checks:**
- Jaeger shows exactly 8 spans for the test transaction, all with correct names matching OPS-13 §7.3 step list.
- Log aggregation search by transaction_id returns log lines from at least 2 services (hub-api + webhook-dispatcher) with the same trace_id.
- Prometheus metrics for /v1/payments show the test request in api_request_rate within 30 seconds.
- Grafana Operations Overview dashboard displays updated API success rate and batch status without manual refresh.
- Validation document is signed off by Ops Lead before the go-live pre-check is marked complete.
**Depends on:** 16.4-T18, 16.4-T19, 16.4-T22, 16.4-T23

### 16.4-T30 — Document smoke-test and monitoring runbook section in OPS-13 (post-deploy procedure update)  _(35 min)_
**Context:** Per OPS-13 §8.1, the deploy-to-production procedure references the smoke-test suite. The existing procedure in §8.1 lists smoke tests as step 7 (runs ~3 minutes). This ticket updates the internal runbook to reference the concrete smoke_runner CLI, list the 5 steps by name, define pass/fail criteria, and document the automated rollback trigger. The runbook lives in docs/ops/runbook.md (or is a section of the Confluence/wiki page mirroring OPS-13).
**Steps:** In docs/ops/runbook.md, add or update section 8.1 Deploy to Production with a reference to smoke_runner CLI: smoke-runner --env prod --timeout-seconds 180.; List the 5 smoke steps by name: health, rate_domestic, rate_crossborder, prefunding_balance, webhook_ping.; State pass condition: all steps PASSED within 180 seconds.; State automated rollback trigger: if smoke exits 1, pipeline auto-redeploys PREV_IMAGE_TAG (see §4.4).; Add a reference table: each alert category (§7.5.1-7.5.6), Prometheus rule file, and routing channel.
**Deliverable:** Updated docs/ops/runbook.md section 8.1 referencing smoke-runner CLI, 5 step names, pass criteria, rollback trigger, and alert routing reference table.
**Acceptance / logic checks:**
- Section 8.1 names the CLI command smoke-runner --env prod --timeout-seconds 180 explicitly.
- All 5 smoke step names are listed (health, rate_domestic, rate_crossborder, prefunding_balance, webhook_ping).
- Automated rollback trigger is documented: exit code 1 within 5 minutes triggers PREV_IMAGE_TAG redeploy.
- Alert routing reference table covers all 6 alert categories from OPS-13 §7.5 with their Prometheus file names.
- Runbook section reviewed and approved by Ops Lead before go-live (sign-off recorded in the doc header).
**Depends on:** 16.4-T07, 16.4-T24, 16.4-T25


## WBS 16.6 — Operational runbook handover & training
### 16.6-T01 — Create runbook table of contents and section skeleton document  _(30 min)_
**Context:** WBS 16.6 covers operational runbook handover and training for GMEPay+ (OPS-13). The runbook must be a standalone operations reference covering: environment strategy (4 tiers: dev/int/staging/prod), CI/CD pipeline (8 stages), batch job schedule (JOB-ZP-01 to JOB-ZP-13), observability, incident procedures (P1-P4), and go-live checklist. It is the primary source for Ops staff and must be usable without accessing spec documents.
**Steps:** Create runbook document (e.g., ops-runbook.md or Confluence page) with all section headings matching OPS-13 structure; Add a cover page: document title GMEPay+ Operational Runbook, version 1.0, date, owner (Ops Lead), audience table: DevOps (secs 2-5,9-10), Backend engineers (secs 3-5,8), GME Ops (secs 6-8,11), Security (secs 4.4,8.2,9,11), Release Manager (sec 12); Add a one-paragraph scope statement: covers Hub Core backend, Admin System, Partner Portal, Northbound Partner API, Southbound ZeroPay SFTP; Phase 1 go-live October 10 2026 and forward; Add a cross-reference table linking to: SEC-09 (security controls), NFR-10 (SLA/RTO/RPO), SCH-06 (ZeroPay batch spec), PM-14 (project timeline/RAID); Mark all sections as DRAFT - content TBD
**Deliverable:** Runbook skeleton document with all section headings, cover page, scope statement, and cross-reference table
**Acceptance / logic checks:**
- Document contains all 12 top-level sections matching OPS-13 (Purpose, Environment Strategy, Reference Infrastructure, Build and CI/CD, Configuration Management, Batch Job Orchestration, Observability, Operational Runbook, Backup and DR, Capacity and Scaling, Incident and On-Call, Release and Go-Live Runbook)
- Cover page lists all 5 audience rows with correct primary sections
- Scope statement explicitly names all 4 systems covered and the October 10 2026 go-live date
- Cross-reference table contains at least 4 entries pointing to SEC-09, NFR-10, SCH-06, PM-14

### 16.6-T02 — Document environment strategy and access control in runbook section 2  _(40 min)_
**Context:** GMEPay+ has 4 environment tiers: E1 dev (synthetic data, developers only), E2 int (ZeroPay 한결원 sandbox, dev+QA), E3 staging (production-like masked PII, QA/Ops/partner tech leads), E4 prod (real data, Ops + approved engineers via break-glass). Canonical short names dev/int/staging/prod are used in CI/CD pipeline variables, DNS labels, and secret-manager paths. Secrets paths follow gmepay/<env>/<service>/<secret-name> convention. Production access is gated by break-glass procedure per SEC-09 section 6.
**Steps:** Fill in runbook section 2.1: add the 4-row environment tier table with columns Tier, Name, Purpose, Data, Access; Fill in section 2.2: document 한결원 ZeroPay test environment - separate SFTP credentials per env, critical path mid-May 2026, never share credentials across tiers; Fill in section 2.3: environment parity rules - all envs deploy same container images, same DB migrations, batch scheduler active in all envs (dev/int may use manual trigger instead of KST schedule); Fill in section 2.4: data handling table per tier (dev = seed only, int = synthetic+sandbox, staging = anonymised PII refreshed monthly, prod = real data with GDPR/PIPA constraints); Fill in section 2.5: access control - dev/int any team member; staging requires PR merge approval and DBA role for direct DB; prod requires break-glass procedure, all access logged and alerted
**Deliverable:** Runbook section 2 (Environment Strategy) fully populated with tier table, 한결원 test env notes, parity rules, data handling table, and access control rules
**Acceptance / logic checks:**
- Tier table has exactly 4 rows with correct short names: dev, int, staging, prod
- Secrets path convention gmepay/<env>/<service>/<secret-name> is stated with a concrete example (e.g., gmepay/prod/batch-worker/zeropay-sftp-key)
- Section explicitly states that batch scheduler uses manual trigger in dev/int but KST production windows in staging and prod
- Section states staging data is anonymised and refreshed monthly or on demand
- Production access break-glass requirement is linked to SEC-09 section 6
**Depends on:** 16.6-T01

### 16.6-T03 — Document reference infrastructure topology and network zones in runbook section 3  _(35 min)_
**Context:** GMEPay+ topology has 5 network zones: Public DMZ (LB, WAF, TLS termination), API Zone (Northbound API + CPM token service, stateless/horizontally scalable), Worker Zone (Batch/SFTP worker + webhook dispatcher, egress-only to ZeroPay SFTP and partner webhook URLs), Data Zone (PostgreSQL primary+replica, Redis cache, message queue - no external access), Management Zone (Admin System, Secrets Manager, observability stack, VPN/bastion only). All cross-zone traffic encrypted in transit. Batch worker is single-instance to prevent duplicate SFTP submissions. Multi-AZ: minimum 2 AZs for all stateless services and both PostgreSQL nodes.
**Steps:** Fill in runbook section 3 with the 5-zone network table: zone name, contents, external traffic Yes/No; Include the ASCII topology diagram showing zones and data flows between them (LB to API Zone to Data Zone, Worker Zone egress to ZeroPay SFTP and partner webhooks, Management Zone via VPN); Document multi-AZ requirement: minimum 2 AZs, PostgreSQL active-passive, API tier active-active, load balancer health-checks each AZ independently; Document Secrets Manager requirement: gmepay/<env>/<service>/<secret-name> hierarchical path, services retrieve secrets at startup and on rotation, never stored in env var files or Docker images; Add note: Batch Worker is single-instance (never auto-scaled) to prevent duplicate file submissions to ZeroPay SFTP
**Deliverable:** Runbook section 3 (Reference Infrastructure) with zone table, ASCII topology diagram, multi-AZ note, secrets manager convention, and batch worker single-instance note
**Acceptance / logic checks:**
- Zone table has exactly 5 rows: Public DMZ, API Zone, Worker Zone, Data Zone, Management Zone
- Worker Zone row shows egress-only traffic to ZeroPay SFTP and partner webhook URLs
- Section states batch worker runs as single instance and must NEVER be scaled horizontally
- Secrets path example gmepay/prod/batch-worker/zeropay-sftp-key appears in section
- Multi-AZ note states minimum 2 AZs with active-passive DB and active-active API tier
**Depends on:** 16.6-T01

### 16.6-T04 — Document CI/CD pipeline stages and branching strategy in runbook section 4.1-4.3  _(35 min)_
**Context:** GMEPay+ has an 8-stage CI/CD pipeline: 1-Build (compile/lint), 2-Unit test (coverage >= 80%), 3-Security scan (no critical/high CVEs, no secrets), 4-Integration test (ephemeral env), 5-Artifact (container image tagged with git-sha+branch+semver, signed), 6-Deploy to int (automated, smoke tests), 7-Deploy to staging (on release/* merge, manual approval gate), 8-Deploy to prod (human-approved, Release Manager sign-off). Branching: feature/<ticket> to main, main auto-deploys to int, release/<version> auto-deploys to staging, hotfix/<ticket> to prod via fast-track. Gated approvals: int is fully automated; staging requires stakeholder sign-off; prod requires Release Manager approval + change record + QA sign-off + pre-deployment checklist.
**Steps:** Fill in runbook section 4.1: add the 8-stage pipeline table with columns Stage, Step, Gate to next stage; Fill in section 4.2: branching strategy table with columns Branch pattern, Purpose, Deploy target - including feature/<ticket>, main, release/<version>, hotfix/<ticket>; Fill in section 4.3: document gated approvals - int fully automated; staging requires stakeholder sign-off before prod promotion; prod requires Release Manager approval in CI/CD tool + change record + QA sign-off + pre-deployment checklist; Add hotfix approval path: two senior engineers + on-call approval, post-deployment review mandatory; State unit test coverage gate: all tests pass, coverage >= 80%
**Deliverable:** Runbook sections 4.1 through 4.3 (Pipeline Stages, Branching Strategy, Gated Approvals) fully populated
**Acceptance / logic checks:**
- Pipeline table has exactly 8 rows numbered 1-8 in order
- Stage 5 (Artifact) states image is tagged with git-sha + branch + semver and signed
- Stage 7 states deploy is triggered on merge to release/* branch with manual approval gate
- Branching table shows main auto-deploys to int and release/<version> auto-deploys to staging
- Prod deployment requirements list all 4 items: Release Manager approval, change record, QA sign-off, pre-deployment checklist
**Depends on:** 16.6-T01

### 16.6-T05 — Document rollback strategy and database migration rules in runbook sections 4.4-4.6  _(40 min)_
**Context:** GMEPay+ rollback has two mechanisms: automated (pipeline redeploys prior image if smoke tests fail within 5 minutes post-deploy) and manual (operator redeploys specific prior image tag with change record). Database migrations are forward-only in production using Flyway or Liquibase. Migration rules: unique sequential version number, applied automatically at deploy time before new app version starts, must be backward-compatible with running version (additive-only: add columns/tables, never rename or drop in same release). Removing a column requires two releases: first remove code references, second drop the column. Migration scripts stored in db/migrations/ in repo. No down migration scripts in production - reversibility achieved by blue-green strategy. Blue-green for major/schema-changing releases; canary (5% traffic, 15 min monitoring) for high-risk API changes.
**Steps:** Fill in runbook section 4.4: automated rollback (5-min window, smoke test failure trigger) and manual rollback (prior image tag, change record). Add critical note: if DB migration was applied and old code is incompatible, do NOT roll back the migration - instead prepare a hotfix forward; Fill in section 4.5: blue-green and canary strategies. Blue-green for major releases - provision parallel env, run smoke tests, cut traffic. Canary for high-risk API changes - 5% traffic, monitor error rates for 15 min, promote or roll back on SLO breach; Fill in section 4.6: database migration rules. List all 6 rules: unique sequential version, auto-applied at deploy, backward-compatible, two-release column removal, stored in db/migrations/, all runs logged; Add note: batch/SFTP worker must drain before redeployment - never run two versions simultaneously against the same SFTP credential set; Confirm: no down migration scripts exist in production
**Deliverable:** Runbook sections 4.4 through 4.6 (Rollback Strategy, Blue-Green/Canary, Database Migrations) fully populated
**Acceptance / logic checks:**
- Automated rollback section states the 5-minute smoke-test window explicitly
- Canary deployment section states 5% traffic and 15-minute monitoring window with SLO-breach trigger
- DB migration rules list additive-only constraint with example: add columns/tables but never rename or drop in same release
- Two-release column removal procedure is documented step by step
- Section contains explicit warning: never run two versions of the batch worker simultaneously against the same SFTP credential set
**Depends on:** 16.6-T04

### 16.6-T06 — Document configuration management and feature flags in runbook section 5  _(40 min)_
**Context:** GMEPay+ is built generically: no QR Scheme, Partner, margin rule, fee rate, or routing rule is hardcoded. Adding a new scheme/partner or changing margins requires only an Admin System config entry - no code change, no deployment. A deployment is required only for: bug fixes, new API capabilities, new batch file types, or infrastructure changes. Feature flags (Phase 1 defaults): FEATURE_LIVE_FX_FEED=false, FEATURE_PARTNER_REFUND_API=false, FEATURE_OUTBOUND_PAYMENTS=false, FEATURE_BOK_REPORTING=false, FEATURE_MULTI_SCHEME_ROUTING=false. Flags are stored in the secrets/config manager and loaded at service startup; changing a flag requires a config update and service restart (no full deployment required).
**Steps:** Fill in runbook section 5.1: generic-build mandate. State explicitly that adding a new QR Scheme requires only Ops config entry, adding a Partner requires only Ops registration, and changing m_a/m_b margins or service charges requires only Admin System update. List what DOES require a deployment: bug fixes, new API capabilities, new batch file types, infrastructure changes; Fill in section 5.2: feature flags table with 5 rows: FEATURE_LIVE_FX_FEED (default false, controls xe.com FX feed), FEATURE_PARTNER_REFUND_API (false, partner-facing refund endpoint), FEATURE_OUTBOUND_PAYMENTS (false), FEATURE_BOK_REPORTING (false, pending OI-03), FEATURE_MULTI_SCHEME_ROUTING (false, Phase 1 single scheme); Document flag change procedure: update config/secrets manager, then restart the affected service. No full deployment required; Fill in section 5.3: environment configuration matrix table - ZeroPay SFTP host (stub/한결원 test/prod), DB connection pool sizes (5/20/80/200), prefunding low-balance threshold ($100/$1000/$10000/$10000), rate quote TTL (300s/300s/per config/per config), webhook retry max (3/3/5/5), log level (DEBUG/DEBUG/INFO/INFO-WARN for DB driver), batch schedule (manual/manual/KST/KST); Add note that FX rate update is manual in all environments for Phase 1
**Deliverable:** Runbook section 5 (Configuration Management) with generic-build mandate, feature flag table, flag change procedure, and environment config matrix
**Acceptance / logic checks:**
- Section 5.1 states that adding a new QR Scheme requires ONLY an Ops config entry and no code change or deployment
- Feature flag table has exactly 5 rows with correct Phase 1 default (all false)
- FEATURE_BOK_REPORTING row notes dependency on OI-03 resolution
- Environment config matrix shows prod DB pool of 200 and staging pool of 80
- Section states that a flag change requires only a config update and service restart, not a full deployment
**Depends on:** 16.6-T01

### 16.6-T07 — Document ZeroPay batch job schedule and retry policy in runbook section 6  _(45 min)_
**Context:** The Batch/SFTP Worker runs 13 job types (JOB-ZP-01 to JOB-ZP-13) in KST (UTC+9). Critical daily chain: JOB-ZP-01 (ZP0011 payment result, submit by 02:00 KST) -> JOB-ZP-02 (ZP0021 refund result, 02:00 KST) -> ZeroPay processes -> JOB-ZP-03 (ZP0012 payment reg result, receive by 05:00 KST) -> JOB-ZP-04 (ZP0022 refund reg result) -> JOB-ZP-05 (ZP0061 settlement request, submit by 05:00 KST) -> JOB-ZP-06 (ZP0062 settlement result morning, receive by 10:00 KST) -> JOB-ZP-07 (ZP0063 settlement request afternoon, submit by 14:00 KST) -> JOB-ZP-08 (ZP0064 settlement result afternoon, receive by 19:00 KST) -> JOB-ZP-09 (ZP0065/ZP0066 settlement detail, submit by 22:00 KST). Retry policy: up to 3 automatic retries with exponential back-off (30s, 120s, 300s). After 3 failures, job enters FAILED state, fires P1 alert, and halts dependency chain.
**Steps:** Fill in runbook section 6.2: SFTP job schedule table with columns Job ID, Files, Direction, Window, Dependency, Criticality for all 13 jobs; Add the critical path dependency chain diagram: [Transactions close] -> JOB-ZP-01/02 (02:00) -> ZeroPay processes -> JOB-ZP-03/04 (05:00 receive) -> Validate -> JOB-ZP-05 (05:00 submit) -> ZeroPay credits merchants -> JOB-ZP-06 (10:00 receive) -> Reconcile morning -> JOB-ZP-07 (14:00 submit) -> JOB-ZP-08 (19:00 receive) -> JOB-ZP-09 (22:00 detail); Fill in section 6.3: retry policy (up to 3 retries, exponential back-off 30s/120s/300s, FAILED state after 3 failures, P1 alert, dependency chain halted). Add idempotency rules: SFTP uploads check for remote ack file or DB run-state record; SFTP downloads use staging path + checksum validation before atomic commit; each job run has unique job_run_id (UUID); Fill in section 6.5: file archival path /gmepay/batch/<direction>/<file-id>/YYYY-MM-DD/<filename>, 7-year retention (regulatory), object storage versioning enabled (immutable once written), daily integrity check; Note: never run two batch worker versions simultaneously (single-instance job)
**Deliverable:** Runbook section 6 (Batch Job Orchestration) with full job schedule table, critical path diagram, retry/idempotency rules, and file archival policy
**Acceptance / logic checks:**
- Job schedule table has exactly 13 rows (JOB-ZP-01 through JOB-ZP-13)
- JOB-ZP-01 row shows direction GME to ZeroPay, window 02:00 KST, criticality Critical
- Retry section states exactly 3 retries with back-off values 30s, 120s, 300s and P1 alert on FAILED
- Archival path format /gmepay/batch/<direction>/<file-id>/YYYY-MM-DD/<filename> is shown
- 7-year file retention is stated as a regulatory requirement
**Depends on:** 16.6-T01

### 16.6-T08 — Document batch window breach alerts in runbook section 6.4  _(25 min)_
**Context:** Batch window breach alerts trigger when jobs miss their submission or receipt windows. Alert severity levels and channels: P1 alerts go to PagerDuty + Ops Slack; P2 to Ops Slack; P3 to Ops email. Key missed-window thresholds: JOB-ZP-01/02 not submitted by 01:45 KST = P1; ZP0012/ZP0022 not received by 05:30 KST = P1; ZP0061 not submitted by 05:15 KST = P1; ZP0062 not received by 10:30 KST = P1; ZP0063 not submitted by 14:15 KST = P2; ZP0064 not received by 19:30 KST = P2; ZP0065/ZP0066 not submitted by 22:15 KST = P2; merchant/QR sync file not received by 08:00 KST = P3. All times are KST (UTC+9).
**Steps:** Fill in runbook section 6.4: create missed-window alert table with columns: Condition, Alert Severity, Channel for all 8 alert rows; Explicitly note that all times are KST (UTC+9); Cross-reference section 8.7 (reprocess runbook) as the response procedure for FAILED batch jobs; Add note that P1 alerts fire via PagerDuty (wakes on-call) + Ops Slack; P2 via Ops Slack only; P3 via Ops email only; Verify that the 01:45 KST early-warning for ZP0011/ZP0021 gives 15-minute buffer before the 02:00 KST deadline
**Deliverable:** Runbook section 6.4 (Alerting on Missed Windows) with complete alert table, timezone note, severity-channel mapping, and cross-reference to reprocess runbook
**Acceptance / logic checks:**
- Alert table has exactly 8 rows covering all batch window conditions
- JOB-ZP-01/02 missed-window threshold is stated as 01:45 KST (15 min before 02:00 KST deadline)
- All P1 alerts list both PagerDuty and Ops Slack as channels
- P3 merchant sync alert references using previous day data as interim action
- Section explicitly states all times are KST (UTC+9)
**Depends on:** 16.6-T07

### 16.6-T09 — Document observability metrics, logging format, and distributed tracing in runbook section 7  _(40 min)_
**Context:** Observability uses RED metrics for API services (api_request_rate, api_error_rate <1%, api_latency_p50/p95/p99 with p95<500ms) and USE metrics for infrastructure (API pods, PostgreSQL, Redis, message queue, SFTP worker). All services emit JSON-structured logs with required fields: timestamp (ISO-8601 UTC), level (DEBUG/INFO/WARN/ERROR), service, env, trace_id (UUID), span_id (UUID), partner_id, transaction_id, event, message. In production, sensitive fields (collection_amount, prefund_balance, rate components) must NOT be logged in plaintext - log structured references (IDs) only. Distributed tracing uses OpenTelemetry; trace_id generated at load balancer and propagated through all service calls. 8-step transaction trail events: rate_quote_issued, payment_initiated, prefund_deducted, scheme_request_sent, scheme_response_received, transaction_committed, webhook_dispatched, webhook_delivered/webhook_failed.
**Steps:** Fill in runbook section 7.1: RED metrics table (api_request_rate, api_error_rate, api_latency_p50/p95/p99) with SLO column; USE metrics table for 5 infrastructure components (API pods, PostgreSQL, Redis, message queue, SFTP worker) with Utilization/Saturation/Error columns; Fill in section 7.2: JSON log format table with all 10 required fields (timestamp, level, service, env, trace_id, span_id, partner_id, transaction_id, event, message). Add production rule: do NOT log collection_amount, prefund_balance, or rate components in production log streams; Fill in section 7.3: distributed tracing with OpenTelemetry, trace_id generated at load balancer. List all 8 transaction trail event names in order; Fill in section 7.4: dashboards table (Operations Overview, API SLO, Batch Health, Prefunding Monitor, Revenue, Security) with audience and key panels; Note that all 8 trail steps are queryable in Admin System transaction detail view
**Deliverable:** Runbook section 7 (Observability) with RED/USE metrics tables, JSON log format with production PII rules, 8-step trace event list, and dashboard catalog
**Acceptance / logic checks:**
- RED metrics table shows api_error_rate SLO as <1% and api_latency p95 SLO as <500ms
- Log format table has exactly 10 required fields including both trace_id and span_id
- Section explicitly states that collection_amount, prefund_balance, and rate components must not be logged in production
- 8-step transaction trail lists all events in correct order: rate_quote_issued through webhook_delivered
- Dashboards table has 6 rows and correctly identifies Prefunding Monitor audience as Ops/Finance
**Depends on:** 16.6-T01

### 16.6-T10 — Document full alert catalog in runbook section 7.5  _(45 min)_
**Context:** The GMEPay+ alert catalog has 6 categories: API/SLO (5 alerts), Prefunding (4 alerts), Batch Window Breach (7 alerts, covered in 6.4), Scheme/Connectivity (4 alerts), Settlement/Financial (4 alerts), Webhook/Integration (3 alerts). Key thresholds: API p95>500ms for 5 min = P2; 5xx error rate>1% for 5 min = P1; /v1/rates error rate>5% for 2 min = P1; health check fails>60s = P1; prefunding balance<$10,000 = P2 (email partner + notify Ops); balance<$2,000 = P1 (suspend payments); pool identity failure (collection_usd - collection_margin_usd - payout_margin_usd != payout_usd_cost beyond $0.01) = P1 (block commit); settlement reconciliation mismatch = P1; webhook queue depth>500 = P2; webhook p95>30s = P3. Severity levels: P1=wake someone now (15 min response), P2=acknowledge within 30 min, P3=next business day, P4=informational.
**Steps:** Fill in runbook section 7.5 with severity level definitions table (P1-P4) showing definition, response time, and examples; Add section 7.5.1 API and SLO alerts table (5 rows): API p95 latency breach, API error rate elevated, API error rate warning, Rate quote failure spike, Payment endpoint unavailable; Add section 7.5.2 Prefunding alerts table (4 rows): Low balance <$10,000 (P2), Critical balance <$2,000 (P1 suspend), Zero balance (P1 all payments suspended), Deduction anomaly >$50,000 (P2 manual review); Add section 7.5.4 Scheme/Connectivity alerts (4 rows): SFTP connection failure (P1), Scheme API timeout (P2), Partner B quote API down (P2), Partner B quote deviation >1% on >5% of quotes (P2); Add section 7.5.5 Settlement/Financial alerts (4 rows): Pool identity assertion failure P1 (formula: collection_usd - collection_margin_usd - payout_margin_usd != payout_usd_cost beyond $0.01 tolerance, action: block transaction commit), Settlement reconciliation mismatch P1, Revenue ledger mismatch P1, BOK report failure P2; Add section 7.5.6 Webhook alerts (3 rows)
**Deliverable:** Runbook section 7.5 (Alert Catalog) with severity level table and all 6 alert category sub-sections
**Acceptance / logic checks:**
- P1 severity response time is stated as 15 minutes
- Critical prefunding threshold is stated as USD 2,000 with action to suspend partner payments immediately
- Pool identity alert includes the exact formula: collection_usd - collection_margin_usd - payout_margin_usd must equal payout_usd_cost within USD 0.01 tolerance
- Prefunding deduction anomaly threshold is stated as >USD 50,000 requiring manual review
- Partner B quote deviation alert threshold is >1% divergence on >5% of quotes
**Depends on:** 16.6-T09

### 16.6-T11 — Document deploy-to-production and rollback procedures in runbook section 8.1-8.2  _(40 min)_
**Context:** These are step-by-step procedures for GME Ops and engineers. Deploy to Production (section 8.1): 10 steps including change record creation (with git SHA, release notes, migration list, rollback plan), pod Running state confirmation, DB migration verification in migration log table, smoke test suite (~3 minutes: /health + rate quote + prefunding balance check), and SLO confirmation for 5 minutes post-deploy. Rollback (section 8.2): automated rollback if smoke tests fail within 5 minutes; manual rollback via prior image tag; critical exception if DB migration was applied - cannot roll back migration, must prepare a hotfix forward; blameless post-mortem required within 48 hours.
**Steps:** Write runbook section 8.1 as numbered steps 1-10: (1) confirm release/* branch passed staging with QA sign-off, (2) create change record with git SHA + release notes + migration list + rollback plan, (3) notify on-call and Ops channel with service/version/ETA, (4) approve prod gate in CI/CD tool with operator identity logged, (5) monitor pod startup to Running state, (6) verify DB migration in migration log table (check for locked tables), (7) run smoke tests: /health + rate quote call + prefunding balance check, (8) confirm p95 latency and error rate within SLO for 5 minutes, (9) close change record as Successful, (10) announce in Ops channel; Write runbook section 8.2 as numbered steps: automated rollback check, locate prior image tag, create change record for emergency rollback, trigger rollback deployment (two-engineer approval), monitor pod restart, special case if DB migration was applied (do NOT roll back migration - prepare hotfix forward, escalate to Engineering Lead), run smoke tests, notify Ops channel, conduct blameless post-mortem within 48 hours; Add warning box: never roll back a database migration in production - use a hotfix forward instead; Add note: all production actions must be logged in the change management system with operator identity, timestamp, and outcome
**Deliverable:** Runbook sections 8.1 and 8.2 as numbered step-by-step procedures with all key decision points and warnings
**Acceptance / logic checks:**
- Section 8.1 has exactly 10 numbered steps
- Step 7 in 8.1 names all 3 smoke test checks: /health endpoint, rate quote call, prefunding balance check
- Section 8.2 contains explicit warning not to roll back DB migrations and instructs preparing a hotfix forward instead
- Rollback procedure requires approval by two engineers (not one)
- Post-mortem requirement in 8.2 specifies 48-hour deadline
**Depends on:** 16.6-T04, 16.6-T05

### 16.6-T12 — Document partner API key rotation procedure in runbook section 8.3  _(30 min)_
**Context:** Partner API key rotation is performed when a key is suspected compromised or on the scheduled rotation cycle. The procedure uses the Admin System (Partners > [Partner Name] > Credentials). New key is generated in PENDING state (old key still active). A transition window of 24-72 hours allows both keys to be accepted simultaneously. Deactivation requires partner confirmation or window expiry. If rotation is due to suspected compromise: revoke old key immediately (no transition window), notify partner contact by phone/secure channel, create a SEC incident record per SEC-09 section 7. Audit log entry must include operator ID, partner ID, timestamp, and reason.
**Steps:** Write runbook section 8.3 as numbered steps: (1) log into Admin System > Partners > [Partner Name] > Credentials, (2) generate new API key/secret pair - system presents secret once, copy to secure credential-sharing channel, (3) confirm new key is in PENDING state (old key still active), (4) coordinate with partner technical contact to update their integration - set transition window 24-72 hours, (5) during window both keys accepted - monitor auth logs for successful new key use, (6) once partner confirms or window expires deactivate old key, (7) verify in auth log no successful calls using old key for 30 minutes, (8) log rotation in audit trail with operator ID, partner ID, timestamp, reason; Add compromise scenario as a sub-procedure: immediately revoke old key (skip transition window), notify partner contact by phone and secure channel, create SEC incident record per SEC-09 section 7; Note that the system presents the new secret ONCE at generation time - if lost, a new key must be generated
**Deliverable:** Runbook section 8.3 (Partner API Key Rotation) with standard 8-step procedure and compromise fast-track sub-procedure
**Acceptance / logic checks:**
- Section has exactly 8 numbered steps for standard rotation
- Step 2 states the secret is presented exactly once at generation - must be copied immediately
- Transition window is stated as 24-72 hours with both old and new keys accepted simultaneously
- Compromise scenario sub-procedure instructs immediate revocation without a transition window
- Audit log entry requirements include all 4 fields: operator ID, partner ID, timestamp, reason
**Depends on:** 16.6-T01

### 16.6-T13 — Document ZeroPay SFTP key rotation procedure in runbook section 8.4  _(30 min)_
**Context:** ZeroPay SFTP key rotation involves coordination with 한결원 (KFTC) and updating the secrets manager. The new SSH key pair must be generated on a hardened workstation (never a shared server). The new public key is sent to 한결원 via secure channel. After 한결원 confirms the key is loaded, the secrets manager path gmepay/prod/batch-worker/zeropay-sftp-key is updated and the batch worker service is restarted (kubectl rollout restart deployment/batch-worker -n gmepay-prod). A manual test SFTP connection (connect only, do NOT upload live files) is run to confirm authentication. The next scheduled JOB-ZP-01 the following morning confirms end-to-end. All copies of the old private key must be destroyed and destruction confirmed in the audit log per SEC-09 section 4.
**Steps:** Write runbook section 8.4 as numbered steps: (1) generate new SSH key pair on hardened workstation - NEVER on shared server, (2) send new public key to 한결원 operations contact via secure channel (confirm email address with partner manager first), (3) receive confirmation from 한결원 that new public key is loaded on their SFTP server, (4) update secret at gmepay/prod/batch-worker/zeropay-sftp-key in secrets manager to new private key, (5) restart batch worker: kubectl rollout restart deployment/batch-worker -n gmepay-prod (or equivalent), (6) trigger manual test SFTP connection - connect only, do NOT upload live files - check logs for successful authentication, (7) confirm next JOB-ZP-01 the following morning completes successfully, (8) destroy all copies of old private key and confirm destruction in audit log, (9) log rotation in SEC audit trail per SEC-09 section 4; Add warning: do not upload live files during the test connection - connection test only; Highlight the hardened workstation requirement as a security control (key generation must never occur on a shared/multi-user system)
**Deliverable:** Runbook section 8.4 (ZeroPay SFTP Key Rotation) as a 9-step procedure with security warnings and audit requirements
**Acceptance / logic checks:**
- Step 1 explicitly states key generation must be on hardened workstation, NEVER on shared server
- Step 4 shows the exact secrets manager path: gmepay/prod/batch-worker/zeropay-sftp-key
- Step 5 shows the exact restart command: kubectl rollout restart deployment/batch-worker -n gmepay-prod
- Step 6 states connect-only test (no live file upload)
- Step 8 requires destruction confirmation in the audit log
**Depends on:** 16.6-T01

### 16.6-T14 — Document prefunding top-up and insufficient-prefunding spike procedures in runbook sections 8.5-8.6  _(40 min)_
**Context:** Prefunding applies to OVERSEAS partners only (e.g., SendMN, T-Bank); LOCAL partners (GME Remit) need no prefunding. Top-up procedure (section 8.5): initiated after GME Finance confirms the inbound wire has settled. Admin System > Partners > [Partner Name] > Prefunding > Record Top-Up. Entry is immutable ledger entry with operator ID and timestamp. Balance updates immediately (real-time deduction model). Default low-balance alert threshold is USD 10,000 (configurable per partner); critical threshold is USD 2,000 (trigger auto-suspend). Insufficient-prefunding spike procedure (section 8.6): P1 critical alert fires when balance < USD 2,000. Suspend partner via Admin System > Partner > Status > Suspend. Already in-flight transactions are NOT affected. Re-enable via Status > Active once top-up confirmed. Monitor for 15 minutes post-reinstatement.
**Steps:** Write runbook section 8.5 as numbered steps: (1) receive Finance confirmation of settled wire with partner name, USD amount, value date, internal reference, (2) log into Admin System > Partners > [Partner Name] > Prefunding, (3) click Record Top-Up - enter amount USD, value date, GME bank reference, Finance internal reference, (4) system creates immutable ledger entry with operator ID and timestamp, (5) confirm new balance visible in Prefunding Monitor dashboard, (6) if low-balance alert was previously triggered confirm it auto-clears once balance > threshold; if not auto-clear manually acknowledge in monitoring system, (7) notify partner contact via email or portal notification of credited top-up and new balance, (8) retain bank confirmation in Finance records per audit requirements; Write runbook section 8.6 as numbered steps: (1) confirm alert in Admin System Prefunding Monitor - verify current balance and depletion rate, (2) if balance < USD 2,000 immediately suspend via Admin System > Partner > Status > Suspend (in-flight transactions unaffected), (3) notify partner contact by phone AND email with current balance and suspension status, (4) notify GME Finance for emergency top-up outreach to partner, (5) record all actions in incident log, (6) once top-up confirmed and recorded re-enable via Status > Active, (7) monitor for 15 minutes post-reinstatement, (8) post-event review - was threshold too low? Update partner config if agreed; Add note: LOCAL partners (GME Remit) are never subject to prefunding requirements
**Deliverable:** Runbook sections 8.5 and 8.6 (Prefunding Top-Up and Insufficient Prefunding Spike) as numbered procedures
**Acceptance / logic checks:**
- Section 8.5 step 4 states the ledger entry is immutable with operator ID and timestamp
- Section 8.5 states the partner balance updates immediately (real-time deduction model)
- Section 8.6 step 2 states the critical threshold is USD 2,000 by default
- Section 8.6 step 2 explicitly states that already in-flight transactions are NOT affected by suspension
- Section 8.6 step 3 specifies notifying the partner by BOTH phone and email
**Depends on:** 16.6-T01

### 16.6-T15 — Document batch reprocessing procedures for ZP0011/ZP0012 and ZP0061/ZP0062 in runbook section 8.7  _(45 min)_
**Context:** Batch reprocessing is needed when a job enters FAILED state, a file was transmitted late, or 한결원 reports non-receipt. Key DB query to check job state: SELECT * FROM batch_job_runs WHERE job_id = 'JOB-ZP-01' AND run_date = '<date>' ORDER BY started_at DESC LIMIT 5. Manual rerun via Admin System > Batch Jobs > [Job ID] > Rerun for Date, or CLI: batch-cli rerun --job JOB-ZP-01 --date <YYYY-MM-DD> --force. Idempotency: worker checks if file was already successfully received (confirmed via ZP0012) before regenerating. CRITICAL for ZP0061: do NOT re-submit for the same date without 한결원 confirmation - double submission could result in duplicate merchant crediting. If window is missed and past 05:00 KST without ZP0012: contact 한결원 operations immediately.
**Steps:** Write runbook section 8.7.1 (ZP0011/ZP0012 reprocessing) as numbered steps: (1) check job run log with SQL: SELECT * FROM batch_job_runs WHERE job_id = 'JOB-ZP-01' AND run_date = '<date>' ORDER BY started_at DESC LIMIT 5, (2) identify failure reason (SFTP connection failure / file generation error / 한결원 non-receipt), (3) if SFTP: verify credentials and host availability, check network egress from worker zone, (4) if file generation error: examine worker logs for failed run, fix root cause before regenerating, (5) trigger manual rerun via Admin System or CLI: batch-cli rerun --job JOB-ZP-01 --date <YYYY-MM-DD> --force, (6) worker checks idempotency - if already successfully received by 한결원 it logs already processed and no-ops, (7) if past 05:00 KST without ZP0012: contact 한결원 operations immediately with file name, expected window, contact details, (8) update batch job status in Admin System, log all actions; Write section 8.7.2 (ZP0061/ZP0062): confirm ZP0012 was received first; critical warning - do NOT re-submit ZP0061 for same date without 한결원 confirmation (risk of duplicate merchant crediting); if ZP0062 not received by 10:30 KST contact 한결원 immediately; Write section 8.7.3 (afternoon ZP0063/ZP0064): follows same pattern as morning cycle; note missing afternoon cycle creates reconciliation gap but does not block next day morning cycle
**Deliverable:** Runbook section 8.7 (Batch Reprocessing) with three sub-procedures for payment registration, morning settlement, and afternoon settlement
**Acceptance / logic checks:**
- Section 8.7.1 includes the exact SQL query with job_id = 'JOB-ZP-01' and LIMIT 5
- CLI command batch-cli rerun --job JOB-ZP-01 --date <YYYY-MM-DD> --force is documented
- Section 8.7.2 contains a prominent warning: do NOT re-submit ZP0061 without 한결원 confirmation due to duplicate merchant crediting risk
- Section 8.7.1 step 7 specifies contacting 한결원 if past 05:00 KST without ZP0012
- Section 8.7.3 states missing afternoon cycle creates reconciliation gap but does not block next day's morning cycle
**Depends on:** 16.6-T07, 16.6-T08

### 16.6-T16 — Document scheme outage and stuck transaction procedures in runbook sections 8.8-8.9  _(45 min)_
**Context:** Scheme outage (section 8.8): when ZeroPay SFTP/API is unreachable. For real-time payment API calls: Hub returns SCHEME_UNAVAILABLE to the calling partner (reject cleanly, do not queue). For batch jobs: retries per policy (3 retries, 30s/120s/300s back-off); after 3 retries job enters FAILED and fires P1 alert. Do not force-complete batch jobs while scheme is down. After ZeroPay restores: confirm with manual SFTP test before resuming batch jobs. Stuck transaction (section 8.9): investigate using the 8-step event trail (rate_quote_issued, payment_initiated, prefund_deducted, scheme_request_sent, scheme_response_received, transaction_committed, webhook_dispatched, webhook_delivered). UNCERTAIN state at step 4/5 can wait up to 24 hours for batch reconciliation. NEVER manually alter a terminal state (APPROVED/FAILED) without dual-operator approval and audit log entry.
**Steps:** Write runbook section 8.8 (Scheme Outage) as numbered steps: (1) confirm outage from 2 independent sources (monitoring alert + manual SFTP test + partner complaints), (2) post service status update in Ops channel with time KST, (3) for real-time API calls Hub returns SCHEME_UNAVAILABLE - do not queue payments against unavailable scheme (prevents prefunding deductions without scheme confirmation), (4) for batch jobs: let retries run; if FAILED fires P1 alert, do not force-complete, (5) contact 한결원 operations contact to obtain ETA, (6) if multi-hour outage: notify partner contacts and update status page, (7) when ZeroPay restores: confirm with manual SFTP test, restart batch worker, re-run failed batch jobs per section 8.7, monitor for 15 minutes, (8) log incident; create postmortem if outage exceeded 30 minutes or caused missed batch windows; Write runbook section 8.9 (Stuck Transaction) as numbered steps with decision tree: (1) obtain transaction_id from partner or Admin System, (2) open Admin System > Transactions > [id] > Event Trail, (3) check which of 8 steps are recorded - list all 8 event names in order, (4) if stalled at step 2: check prefunding balance and DB lock state, (5) if stalled at step 4 (UNCERTAIN): wait up to 24 hours for batch reconciliation, (6) if UNCERTAIN for >24 hours: query ZeroPay via Admin System ZeroPay Query tool or contact 한결원, (7) if stalled at step 7: check webhook dispatcher queue depth, (8) if stalled at step 8 after max retries: log failure, notify partner support, offer manual re-trigger from Admin System; Add critical warning box: NEVER manually alter a terminal state (APPROVED/FAILED) without dual-operator approval and an audit log entry
**Deliverable:** Runbook sections 8.8 and 8.9 (Scheme Outage and Stuck Transaction Investigation) as numbered procedures with decision tree
**Acceptance / logic checks:**
- Section 8.8 step 3 states Hub returns SCHEME_UNAVAILABLE and explains this prevents prefunding deductions without scheme confirmation
- Section 8.8 states postmortem is required if outage exceeded 30 minutes or caused missed batch windows
- Section 8.9 lists all 8 transaction trail event names in the correct order
- Section 8.9 states UNCERTAIN state can wait up to 24 hours for batch reconciliation
- Critical warning in 8.9 states that altering terminal states requires dual-operator approval and audit log entry
**Depends on:** 16.6-T07, 16.6-T09

### 16.6-T17 — Document settlement reconciliation mismatch and FX rate update procedures in runbook sections 8.10-8.11  _(40 min)_
**Context:** Settlement reconciliation mismatch (section 8.10): triggered when automated reconciliation detects discrepancy between GME internal aggregation and ZeroPay settlement result files (ZP0062/ZP0064). Three discrepancy types: Missing item (in GME, not in ZP file - check ZP0012 registration result), Amount mismatch (check same-day refunds/cancellations and merchant fee rate applied), Duplicate item (ZeroPay recorded twice - contact 한결원). Resolution options: accept ZeroPay figure, adjust GME ledger with dual-operator approval, or request corrected file from 한결원. FX Rate Update (section 8.11, Phase 1 manual): update via Admin System > FX Rates. Format: treasury.usd_{ccy} = units of {ccy} per 1 USD. Example: treasury.usd_krw = 1381.50. If rate change exceeds 2%: four-eyes control required (second-operator approval). Rate is effective for all NEW transactions from that moment; committed transactions are never affected (rate-lock at commit time). Cache TTL is <=1 second.
**Steps:** Write runbook section 8.10 (Settlement Reconciliation Mismatch) as numbered steps: (1) open Admin System > Settlement > Exceptions for the date in question, (2) for each flagged item record: transaction ID, GME internal amount KRW, ZeroPay reported amount KRW, discrepancy value, (3) classify discrepancy type (Missing item / Amount mismatch / Duplicate item) with resolution guidance for each, (4) document finding, proposed resolution, and approval in Admin System exception record, (5) resolution options: accept ZeroPay figure, adjust GME ledger with dual-operator approval, or request corrected file from 한결원, (6) if discrepancy affects settlement payment amount notify GME Finance before finalising, (7) re-run reconciliation report after resolution to confirm zero exceptions, (8) log all manual adjustments with operator ID, timestamp, before/after values, and approver; Write runbook section 8.11 (FX Rate Update Phase 1 Manual) as numbered steps: (1) obtain latest USD/KRW rate from xe.com or agreed source, (2) log into Admin System > FX Rates, (3) enter rate in format treasury.usd_krw = 1381.50 (units of KRW per 1 USD), (4) review system-computed diff vs previous rate - if change exceeds 2% second-operator approval required (four-eyes control), (5) click Apply - new rate effective immediately for all NEW transactions, (6) verify in Admin System > Rates Audit Log: new entry with value, timestamp, operator identity, (7) run rate engine test vectors for GME Remit (domestic same-currency) and SendMN (cross-border) to confirm collection_amount values are reasonable; Add note: rate update latency to transaction effect is <=1 second (in-memory cache TTL)
**Deliverable:** Runbook sections 8.10 and 8.11 (Reconciliation Mismatch and FX Rate Update) as numbered procedures
**Acceptance / logic checks:**
- Section 8.10 lists exactly 3 discrepancy types with resolution guidance for each
- Section 8.10 step 8 requires logging all manual adjustments with operator ID, timestamp, before/after values, and approver
- Section 8.11 step 3 shows the correct rate format: treasury.usd_krw = 1381.50 (units of KRW per 1 USD)
- Section 8.11 states that changes exceeding 2% require four-eyes control (second-operator approval)
- Section 8.11 states that committed transactions are NEVER affected by rate updates - rates are locked at commit time
**Depends on:** 16.6-T09, 16.6-T10

### 16.6-T18 — Document backup schedule, restore testing, and DR failover procedures in runbook section 9  _(45 min)_
**Context:** Backup policy: PostgreSQL uses continuous WAL archiving + daily full snapshot (30 days full retention, 7 years WAL archive). Object storage batch files are versioned and geo-redundant (7-year retention, regulatory requirement). Redis persistence is disabled in prod (ephemeral cache, rebuilt on restart). DR targets from NFR-10: single AZ failure RPO=0, RTO<5 min (automatic failover); primary DB failure RPO<1 min WAL lag, RTO<10 min replica promotion; full region failure RPO<15 min, RTO<4 hours. PostgreSQL primary failure procedure: monitoring alert fires > confirm truly down > promote replica (pg_ctl promote or cloud-managed equivalent) > update DB connection secret in secrets manager to new endpoint > rolling restart of all services > provision new replica immediately. Monthly restore test: restore full snapshot to dedicated restore-test env, apply WAL archives, run integrity suite (verify transaction counts, prefunding ledger totals, settlement figures), document result, destroy environment after test.
**Steps:** Fill in runbook section 9.1: backup schedule table with columns Asset, Method, Frequency, Retention for 7 asset types (PostgreSQL primary, replica, Redis, object storage, secrets manager, container images, migration scripts). Note Redis persistence is DISABLED in production; Fill in section 9.2: monthly restore test procedure as 5 numbered steps including restore to dedicated test env (not staging or prod), WAL archive application, integrity suite checks (transaction counts, prefunding ledger totals, settlement figures), documentation, and environment destruction; Fill in section 9.3: RTO/RPO targets table with 4 rows (single AZ failure, primary DB failure, full region failure, batch file loss); Write section 9.4.1 PostgreSQL primary failure failover as 8 numbered steps including pg_ctl promote, secrets manager endpoint update, rolling service restart, and new replica provisioning. State expected downtime <10 minutes; Write section 9.4.2 Full region DR failover as 8 numbered steps including DNS update to DR load balancer, WAL restore, object storage confirmation, secrets update, batch worker SFTP verification, smoke tests, and partner communication
**Deliverable:** Runbook section 9 (Backup and Disaster Recovery) with backup table, monthly restore test procedure, RTO/RPO table, and two failover runbooks
**Acceptance / logic checks:**
- Backup table shows PostgreSQL WAL retention as 7 years and full snapshot retention as 30 days
- Backup table shows Redis persistence as DISABLED in production
- RTO/RPO table shows primary DB failure RTO as <10 minutes
- Section 9.4.1 includes the exact command pg_ctl promote or cloud-managed equivalent
- Full region DR failover section 9.4.2 includes partner communication step confirming service restored
**Depends on:** 16.6-T01

### 16.6-T19 — Document capacity scaling triggers and incident on-call procedures in runbook sections 10-11  _(40 min)_
**Context:** Auto-scaling rules: Hub API scales out when CPU >70% for 3 min or p95 latency >300ms (min 3 pods, max 20); Admin System: CPU >70% for 3 min (min 2, max 8); Webhook Dispatcher: queue depth >200 (min 2, max 10); Batch Worker: manual only, never auto-scaled (1 pod only). DB connection pools: prod API=200, batch worker=20, admin=20. Webhook queue P2 alert at depth >500 for 5 min; P1 alert at depth >2,000 for >2 min. Incident severity: P1=production down/data integrity/batch window missed/prefunding zero (15-min response), P2=SLO at risk/partial degradation (30-min response), P3=non-critical/background warnings (next business day), P4=informational. Escalation: on-call engineer acknowledges > if not resolved in 15 min (P1)/30 min (P2) escalate to Engineering Lead > if customer impact or financial risk notify GME Ops Manager > if ZeroPay interaction needed GME account manager contacts 한결원 ops > if security follow SEC-09 section 7.
**Steps:** Fill in runbook section 10.1: auto-scaling table with columns Service, Scale-out trigger, Scale-in trigger, Min pods, Max pods for 4 services. Note Batch Worker is NEVER auto-scaled; Fill in section 10.2: DB connection pool table for prod/staging/int environments; Fill in section 10.3: queue depth alert thresholds - P2 at depth >500 for 5 min, P1 at depth >2,000 for >2 min; Fill in section 11.1: severity level definitions table (P1-P4) with Definition, Response Time, Examples columns; Write section 11.2: escalation path as 5-step numbered procedure; Write section 11.3: incident communication rules (PagerDuty declaration, 15-min status updates during P1, partner-facing email within 30 min of confirmation, post-incident summary to GME Management and affected partners within 24 hours); Write section 11.4: postmortem process with required document sections (incident timeline UTC, root cause analysis, impact assessment, what went well/wrong, action items with owner/due date/tracking ticket); state postmortem is required for all P1 and for P2 incidents that breached published SLOs
**Deliverable:** Runbook sections 10 (Capacity/Scaling) and 11 (Incident/On-Call) fully populated
**Acceptance / logic checks:**
- Auto-scaling table has exactly 4 rows and Batch Worker row states min=max=1 with manual-only scaling
- Prod DB connection pool shows 200 for API service
- Escalation path section 11.2 has exactly 5 numbered steps including ZeroPay/한결원 contact path
- Postmortem section states it is required for ALL P1 incidents and for P2 incidents that breached published SLOs
- Incident communication section states partner-facing email must be sent within 30 minutes of incident confirmation
**Depends on:** 16.6-T09, 16.6-T10

### 16.6-T20 — Document pre-go-live checklist in runbook section 12.1  _(50 min)_
**Context:** The Phase 3 go-live target is October 10 2026 (GME Remit on ZeroPay). The pre-go-live checklist has 8 sub-sections and must be signed off by Release Manager, Engineering Lead, Ops Lead, and Security Lead before live traffic is accepted. Sub-sections: 12.1.1 Infrastructure (multi-AZ, WAF/TLS, auto-scaling at 500 TPS, DR drill, DB backup tested), 12.1.2 Application (all Phase 1 features, feature flags configured: FEATURE_LIVE_FX_FEED=false FEATURE_PARTNER_REFUND_API=false, rate engine test vectors confirmed, pool identity assertion enabled, idempotency keys validated end-to-end), 12.1.3 ZeroPay Integration (production SFTP credentials loaded, SFTP connectivity test successful, all ZP00xx file types validated, 한결원 confirmed full batch cycle receipt, batch scheduler on KST windows), 12.1.4 Partners (GME Remit: API credentials + webhook URL + rule KRW/KRW same-currency + service charge KRW 500; SendMN: OVERSEAS + USD prefunding + 2% FX margin + KRW 500 service charge + low-balance alert USD 10,000), 12.1.5 Merchant Data (ZP0041/ZP0045 sync run, ZP0051 full merchant list loaded, ZP0043/ZP0053 QR sync run), 12.1.6 Monitoring/Alerting (all alert catalog entries configured and tested, PagerDuty on-call rotation, dashboards reviewed), 12.1.7 Security (SEC-09 section 9 sign-off, production secrets rotated from staging values, break-glass procedure documented and tested, audit logging confirmed), 12.1.8 Operational Readiness (Ops team trained, this runbook reviewed and signed off by Ops Lead, partner contacts in contact register, change management system access confirmed).
**Steps:** Write runbook section 12.1 with all 8 sub-sections as checkboxes. Each checkbox must have enough detail to be actionable without referring to other documents; Sub-section 12.1.2 must list the exact feature flags with their Phase 1 defaults: FEATURE_LIVE_FX_FEED=false, FEATURE_PARTNER_REFUND_API=false, FEATURE_OUTBOUND_PAYMENTS=false, FEATURE_BOK_REPORTING=false, FEATURE_MULTI_SCHEME_ROUTING=false; Sub-section 12.1.4 must specify GME Remit config: partner type LOCAL, direction Domestic, settlement currency KRW, service charge KRW 500, no prefunding. SendMN: partner type OVERSEAS, USD prefunding, m_a+m_b=2%, service charge KRW 500, low-balance alert threshold USD 10,000; Sub-section 12.1.6 must reference the alert catalog in section 7.5 and state that alerts must be tested with synthetic alerts before go-live; Add sign-off table at end of 12.1: 4 rows for Release Manager, Engineering Lead, Ops Lead, Security Lead with columns Name, Role, Signature, Date
**Deliverable:** Runbook section 12.1 (Pre-Go-Live Checklist) with all 8 sub-sections as checkboxes and 4-person sign-off table
**Acceptance / logic checks:**
- Checklist has exactly 8 sub-sections (12.1.1 through 12.1.8)
- Sub-section 12.1.2 lists all 5 feature flags with their Phase 1 default (all false)
- Sub-section 12.1.4 specifies GME Remit service charge as KRW 500 and SendMN low-balance alert threshold as USD 10,000
- Sub-section 12.1.6 states all alert catalog entries must be tested with synthetic alerts before go-live
- Sign-off table includes all 4 required approvers: Release Manager, Engineering Lead, Ops Lead, Security Lead
**Depends on:** 16.6-T06, 16.6-T07, 16.6-T09, 16.6-T10, 16.6-T18

### 16.6-T21 — Document go-live cutover, rollback plan, and hypercare period in runbook sections 12.2-12.4  _(40 min)_
**Context:** Cutover procedure (section 12.2): recommended Tuesday or Wednesday morning KST after overnight batch window completes (~06:00 KST). 48-hour advance notice to GME Remit and SendMN technical contacts. At T-0: flip production load balancer, enable partner API keys in Admin System. Go-live smoke tests (5 min): (1) rate quote for GME Remit domestic - confirm collection_amount = target_payout + 500 KRW (same-currency short-circuit: collection_amount = target_payout + service_charge), (2) rate quote for SendMN cross-border - confirm USD pool math holds, (3) webhook delivery to GME Remit test endpoint, (4) prefunding balance readable for SendMN. Hypercare period: 14 days (October 10-24 2026). Extended on-call: two engineers 24/7. Twice-daily Ops check-in: 09:00 and 18:00 KST. Daily postmortem review. Daily partner call for first 5 business days. P2 alerts treated as P1 during hypercare. Hypercare ends if: zero P1 incidents + SLOs met + 10 consecutive days with successful morning and afternoon batch cycles.
**Steps:** Write runbook section 12.2 (Cutover Procedure) as numbered steps: (1) confirm all pre-go-live checklist items checked, (2) set go-live window (recommend Tuesday/Wednesday morning KST after overnight batch ~06:00 KST), (3) notify GME Remit and SendMN technical contacts at least 48 hours in advance, (4) T-1 hour: Engineering Lead and Ops Lead join live call, all monitoring dashboards open, (5) T-0: flip production load balancer to accept traffic, enable partner API keys in Admin System, confirm requests flowing in monitoring, (6) run 5-minute go-live smoke tests: rate quote GME Remit (domestic, confirm collection_amount = target_payout + 500), rate quote SendMN (cross-border, confirm USD pool math), webhook delivery to GME Remit test endpoint, prefunding balance check for SendMN, (7) declare Go-Live Confirmed in team channel, start hypercare period; Write runbook section 12.3 (Rollback Plan Go-Live): if blocking defect found suspend partner API keys in Admin System (in-flight transactions complete), rollback deployment per section 8.2, notify GME Remit and SendMN by phone/email, investigate fix and redeploy, re-run full go-live checklist 12.1 before retrying cutover; Write runbook section 12.4 (Hypercare): list all 6 hypercare rules (extended on-call 24/7 two engineers, twice-daily check-in 09:00 and 18:00 KST, daily postmortem review, daily partner call for 5 business days, P2 treated as P1). State hypercare exit criteria: zero P1 incidents + SLOs met + 10 consecutive days with successful morning and afternoon batch cycles
**Deliverable:** Runbook sections 12.2 through 12.4 (Cutover, Go-Live Rollback Plan, Hypercare Period)
**Acceptance / logic checks:**
- Cutover procedure section 12.2 has exactly 7 numbered steps
- Smoke test step (step 6) includes all 4 checks: GME Remit rate quote with collection_amount = target_payout + 500, SendMN USD pool math, webhook delivery, prefunding balance
- Section 12.3 states partner API keys must be suspended before rollback to stop new payment attempts (in-flight transactions allowed to complete)
- Hypercare section states P2 alerts are treated as P1 during the 14-day period
- Hypercare exit criteria requires 10 consecutive days with successful morning AND afternoon batch cycles
**Depends on:** 16.6-T20, 16.6-T11

### 16.6-T22 — Prepare runbook walkthrough training slide deck outline  _(45 min)_
**Context:** WBS 16.6 requires a runbook walkthrough for the GME Ops team before go-live. The walkthrough covers the complete OPS-13 operational runbook. Target audience: GME Operations team (primary sections 6-8, 11) and Release Manager (section 12). The walkthrough must translate the runbook into an interactive training format with scenario-based exercises. Key training objectives: (1) Ops staff can independently execute all 11 procedures in section 8 without reading the spec, (2) Staff can identify the correct alert severity (P1-P4) for a given scenario, (3) Staff can navigate the Admin System to perform each runbook action, (4) Staff know the escalation path for every incident type.
**Steps:** Create slide deck outline document (e.g., ops-training-slides-outline.md) with sections matching the walkthrough agenda; Agenda sections: (A) GMEPay+ platform overview and roles - 10 min, (B) Environment strategy and access controls - 10 min, (C) Batch job schedule and critical path - 20 min, (D) Alert catalog and severity levels - 15 min, (E) Core runbook procedures walkthrough (sections 8.1-8.11) - 60 min, (F) Go-live checklist and cutover procedure - 20 min, (G) Incident management and escalation paths - 15 min, (H) Hands-on Admin System navigation exercise - 30 min; For each section list: key slides needed, trainer notes, and any live demo steps in the Admin System; Add a prerequisites list: attendees must have read OPS-13 runbook, have Admin System access in the staging environment, and have PagerDuty accounts set up; Add post-training competency checklist: 10 observable competencies (e.g., can execute batch reprocess for JOB-ZP-01, can record a prefunding top-up, can rotate a partner API key, can escalate a P1 incident, can apply an FX rate update)
**Deliverable:** Walkthrough training slide deck outline document with agenda, section-by-section trainer notes, Admin System demo steps, prerequisites, and post-training competency checklist
**Acceptance / logic checks:**
- Agenda covers all 8 sections and sums to approximately 180 minutes total
- Each agenda section lists at least 2 key slides or demo steps
- Post-training competency checklist has exactly 10 observable measurable competencies
- Prerequisites list includes all 3 items: runbook read, staging Admin System access, PagerDuty account setup
- Section (E) on core runbook procedures covers all 11 procedures from sections 8.1 through 8.11
**Depends on:** 16.6-T21

### 16.6-T23 — Create scenario-based training exercises for batch job and alert handling  _(55 min)_
**Context:** Training exercises for the GME Ops team must be scenario-based to ensure staff can apply runbook knowledge to real situations. The batch job schedule critical path (JOB-ZP-01 through JOB-ZP-09) and alert catalog (P1-P4 across 6 categories) are the most operationally critical areas. Exercises must use real Admin System screens and real DB queries from the runbook. Scenario 1: JOB-ZP-01 enters FAILED state at 02:30 KST. Scenario 2: SendMN prefunding balance drops to USD 1,800. Scenario 3: API error rate spikes to 3% at 11:00 KST. Scenario 4: ZeroPay settlement reconciliation mismatch for 5 transactions in ZP0062. Scenario 5: Partner API key suspected compromised.
**Steps:** Write exercise document with 5 training scenarios, each structured as: Situation (what the trainee observes), Task (what they must do), Expected Actions (step-by-step from the runbook), Evaluation Criteria (how trainer scores the exercise); Scenario 1 (Batch failure): Trainee sees JOB-ZP-01 in FAILED state at 02:30 KST. Expected actions: run SELECT * FROM batch_job_runs WHERE job_id = 'JOB-ZP-01' AND run_date = '<date>' ORDER BY started_at DESC LIMIT 5; identify failure reason; execute batch-cli rerun --job JOB-ZP-01 --date <YYYY-MM-DD> --force; check idempotency; if past 05:00 KST without ZP0012 contact 한결원. Evaluation: correct runbook section cited (8.7.1), correct SQL executed, correct CLI command, correct escalation trigger; Scenario 2 (Prefunding crisis): SendMN balance drops to USD 1,800 triggering P1 critical alert. Expected: suspend via Admin System > Partner > Status > Suspend; notify SendMN by phone AND email; notify GME Finance; record in incident log; re-enable after top-up confirmed. Evaluation: suspension executed before contacting partner, both notification channels used, incident log updated; Scenario 3 (API error rate spike): 5xx error rate shows 3% over 5 min on monitoring dashboard. Expected: P1 classification, check app logs, consider rollback per section 8.2, verify not scheme-side. Evaluation: P1 severity correctly identified (>1% threshold); Scenario 4 (Reconciliation mismatch): 5 transactions in ZP0062 show discrepancies. Expected: open Admin System > Settlement > Exceptions; classify each discrepancy type; document resolution; log with dual-operator approval if adjusting GME ledger. Evaluation: all 3 discrepancy types considered; Scenario 5 (Compromised API key): Suspected compromised API key for GME Remit. Expected: IMMEDIATE revocation (no transition window); notify partner by phone/secure channel; create SEC incident record per SEC-09 section 7. Evaluation: zero-delay revocation (no 24-72h window), SEC incident record created
**Deliverable:** Training exercise document with 5 complete scenarios including situation, task, expected actions (with exact SQL/CLI commands), and evaluation criteria
**Acceptance / logic checks:**
- Scenario 1 includes the exact SQL query and CLI command from the runbook
- Scenario 2 states the balance threshold (USD 1,800 < USD 2,000 critical threshold) and requires both phone and email notification
- Scenario 3 correctly identifies 3% error rate as P1 (>1% threshold) and references section 8.2 rollback
- Scenario 4 lists all 3 discrepancy types (missing item, amount mismatch, duplicate item)
- Scenario 5 distinguishes compromised key procedure (immediate revocation, no transition window) from standard rotation (24-72h transition window)
**Depends on:** 16.6-T15, 16.6-T14, 16.6-T16, 16.6-T17, 16.6-T12

### 16.6-T24 — Create Admin System navigation guide for Ops training  _(45 min)_
**Context:** The Admin System is the primary tool for GME Ops staff to execute runbook procedures. Ops staff must be able to navigate to all required screens without external guidance. Key Admin System navigation paths used in runbook procedures: Partners > [Partner Name] > Credentials (API key rotation), Partners > [Partner Name] > Prefunding (top-up and monitoring), Partner > Status (Suspend/Active toggle), FX Rates (rate update), FX Rates > Rates Audit Log (rate verification), Settlement > Exceptions (reconciliation mismatch), Transactions > [transaction_id] > Event Trail (stuck transaction), Batch Jobs > [Job ID] > Rerun for Date (batch reprocessing), ZeroPay Query Tool (manual ZeroPay transaction query). All actions in the Admin System are logged with operator identity and timestamp for audit purposes.
**Steps:** Create Admin System navigation guide document with screen-by-screen instructions for each runbook procedure; For each of the 9 navigation paths, document: menu path, what the screen shows, what actions are available, and which runbook procedure it supports; Add a screen map diagram showing the main menu structure and how to reach each critical screen (Partners, FX Rates, Settlement, Transactions, Batch Jobs, ZeroPay Query Tool); Add notes for each screen about audit implications: which actions create immutable audit log entries, which require second-operator approval, which generate notifications to partners; Add a troubleshooting FAQ: what to do if a screen is not visible (check RBAC permissions), what to do if Record Top-Up button is greyed out (Finance confirmation not received), what to do if Rerun for Date shows already processed message (idempotency check passed - no further action needed)
**Deliverable:** Admin System navigation guide with 9 screen paths, screen map diagram, audit implication notes, and troubleshooting FAQ
**Acceptance / logic checks:**
- Navigation guide covers all 9 Admin System paths used in runbook sections 8.1-8.11
- Screen map diagram shows all 6 top-level menu sections: Partners, FX Rates, Settlement, Transactions, Batch Jobs, ZeroPay Query Tool
- Each screen entry notes which actions create immutable audit entries
- Troubleshooting FAQ contains at least 3 entries
- The guide notes that all Admin System actions are logged with operator identity and timestamp
**Depends on:** 16.6-T11, 16.6-T12, 16.6-T13, 16.6-T14, 16.6-T15, 16.6-T17

### 16.6-T25 — Create on-call quick-reference card for common P1 and P2 scenarios  _(35 min)_
**Context:** An on-call quick reference card is a 1-2 page printable/bookmarkable document giving the on-call engineer the fastest path to resolution for the most common high-severity alerts. It supplements but does not replace the full runbook. It must cover: P1 alert types and their immediate first actions, escalation phone numbers/contacts (template), and the most common 5 resolution paths. Key P1 scenarios: API unavailable (health check fails >60s), API error rate >1%, ZP0011/ZP0021 batch missed by 01:45 KST, prefunding balance <USD 2,000, pool identity assertion failure (collection_usd - collection_margin_usd - payout_margin_usd != payout_usd_cost beyond USD 0.01), ZeroPay SFTP unreachable. The card must include the 8-step transaction event trail names for quick reference when investigating stuck transactions.
**Steps:** Create quick-reference card document (1-2 pages) with the title GMEPay+ On-Call Quick Reference; Add P1 alert response table: 3 columns (Alert, Immediate First Action, Runbook Section Reference) for 6 P1 alert types: API unavailable, API error rate >1%, Batch ZP0011/ZP0021 missed, Prefunding <USD 2,000, Pool identity assertion failure, SFTP unreachable; Add P2 alert response summary for 3 P2 types: API p95 >500ms, Prefunding <USD 10,000, Partner B quote deviation; Add escalation contacts table (template): rows for Engineering Lead, Ops Lead, GME Ops Manager, 한결원 Operations Contact - all with name/phone/email fields to be filled in; Add 8-step transaction trail quick reference list in order: (1) rate_quote_issued (2) payment_initiated (3) prefund_deducted (4) scheme_request_sent (5) scheme_response_received (6) transaction_committed (7) webhook_dispatched (8) webhook_delivered; Add key thresholds box: API error rate P1 >1%, prefunding critical USD 2,000, prefunding low USD 10,000, pool identity tolerance USD 0.01, canary traffic 5%, smoke test window 5 min post-deploy, UNCERTAIN transaction wait time 24 hours
**Deliverable:** On-call quick reference card (1-2 pages) with P1/P2 alert table, escalation contacts template, 8-step transaction trail, and key thresholds box
**Acceptance / logic checks:**
- P1 alert table has exactly 6 rows covering all critical P1 alert types
- Pool identity assertion failure row includes the exact formula: collection_usd - collection_margin_usd - payout_margin_usd must equal payout_usd_cost within USD 0.01
- Transaction trail list has exactly 8 steps in correct order
- Key thresholds box includes prefunding critical (USD 2,000), low (USD 10,000), and pool identity tolerance (USD 0.01)
- Escalation contacts table has 4 rows with 한결원 Operations Contact included
**Depends on:** 16.6-T10, 16.6-T16, 16.6-T19

### 16.6-T26 — Create knowledge transfer checklist and sign-off document for handover  _(45 min)_
**Context:** WBS 16.6 requires a formal knowledge transfer and handover from the development/DevOps team to the GME Operations team before go-live. The handover must be documented and signed off. Knowledge transfer covers: runbook procedures (all 11 in section 8), Admin System operation, alert response and escalation, batch job monitoring, FX rate update procedure, DR and backup procedures, go-live and hypercare procedures, incident postmortem process. Acceptance criteria: Ops team can independently execute all section 8 procedures without developer assistance, and can independently execute the go-live checklist. The handover document must record which staff members attended which training sessions and demonstrate competency on the 10 training scenarios.
**Steps:** Create knowledge transfer handover document with sections: (1) Handover scope and objectives, (2) Training sessions delivered (table: session name, date, trainer, attendees, topics covered), (3) Competency assessment results (table: staff member name, scenario number, pass/fail, date, assessor), (4) Outstanding items / known gaps, (5) Sign-off table; Training sessions table must cover at least 6 sessions: Runbook Walkthrough, Alert Catalog and Severity, Batch Job Monitoring, Admin System Navigation, Incident Management, Go-Live Checklist; Competency assessment table must reference the 10 competencies from 16.6-T22 and the 5 scenarios from 16.6-T23; each GME Ops team member must pass at least 8 of 10 competencies before sign-off; Outstanding items section must include: open items from OPS-13 section 13.2 (OI-OPS-01 through OI-OPS-04) that affect Operations, and any deferred training items; Sign-off table: 5 rows - Ops Lead, Engineering Lead, Release Manager, GME Operations Manager, and head of the dev vendor team - with columns Role, Name, Signature, Date, Remarks
**Deliverable:** Knowledge transfer handover document with training session records, competency assessment results, outstanding items from OPS-13, and 5-person sign-off table
**Acceptance / logic checks:**
- Training sessions table has at least 6 session rows
- Competency assessment table requires each Ops staff member to pass at least 8 of 10 competencies before sign-off
- Outstanding items section references all 4 open items OI-OPS-01 through OI-OPS-04 from OPS-13 section 13.2
- Sign-off table has exactly 5 rows including dev vendor team lead
- Document states that handover is not complete until all sign-off signatures are obtained and competency threshold is met
**Depends on:** 16.6-T22, 16.6-T23, 16.6-T24, 16.6-T25

### 16.6-T27 — Review and validate runbook completeness against OPS-13 checklist  _(55 min)_
**Context:** Before the handover is finalised, the completed operational runbook must be reviewed against OPS-13 to confirm all required content is present and accurate. The runbook must be reviewed by: Engineering Lead (sections 2-5, 9-10), Ops Lead (sections 6-8, 11), Security Lead (sections 4.4, 8.2, 9, 11), Release Manager (section 12). Every procedure in section 8 must have been tested in the staging environment. Key validation points: all 13 batch job IDs present with correct windows; all 27 alert catalog entries present with correct thresholds; pool identity formula correctly stated; all 8 transaction trail event names present; go-live checklist covers all 8 sub-sections; all 9 Admin System navigation paths documented.
**Steps:** Create runbook review checklist with one checkbox per major content item across all 12 sections of OPS-13; For section 6 (Batch Jobs): verify all 13 job IDs present (JOB-ZP-01 through JOB-ZP-13), correct KST windows, correct dependency chain, correct retry policy (3 retries, 30s/120s/300s back-off); For section 7.5 (Alert Catalog): count all alert entries - expected minimum 27 (API 5 + prefunding 4 + batch 7 + scheme/connectivity 4 + settlement/financial 4 + webhook 3 = 27). Verify P1 thresholds are correct; For section 8 (Runbook Procedures): verify all 11 procedures are present (8.1 deploy to prod, 8.2 rollback, 8.3 partner API key rotation, 8.4 SFTP key rotation, 8.5 prefunding top-up, 8.6 insufficient prefunding, 8.7 batch reprocessing (3 sub-procedures), 8.8 scheme outage, 8.9 stuck transaction, 8.10 reconciliation mismatch, 8.11 FX rate update); Obtain reviewer sign-off from all 4 roles (Engineering Lead, Ops Lead, Security Lead, Release Manager) on the review checklist; Record any deficiencies found and create remediation tasks; re-review after remediation
**Deliverable:** Runbook completeness review checklist with section-by-section validation results and 4-reviewer sign-off, plus remediation log for any deficiencies found
**Acceptance / logic checks:**
- Review checklist covers all 12 top-level OPS-13 sections
- Section 6 validation confirms exactly 13 job IDs with correct KST windows
- Alert catalog count confirms at least 27 alert entries are present with correct thresholds
- Section 8 validation confirms all 11 procedures are present and complete
- Review checklist has been signed off by all 4 required reviewers: Engineering Lead, Ops Lead, Security Lead, Release Manager
**Depends on:** 16.6-T21, 16.6-T08, 16.6-T10, 16.6-T17, 16.6-T18, 16.6-T19, 16.6-T20


<!-- wbs-v3-gap-closure -->

---

## WBS v3 gap-closure tickets (re-baseline, 2026-06-10)

These tickets convert this service's PARTIAL audit findings into DONE and add work discovered during the build. Statuses live on the `Backlog` sheet of `GMEPay+_Task_Backlog.xlsx`; phase sequencing on the `Completion Plan v3` sheet of `GMEPay+_WBS.xlsx`.

### 17.1-G01 — Provision Docker-capable build host/runner
*Completion phase:* **R0** · *Est:* 90 min · *Role:* DevOps

**Context.** Build env so far had no Docker — every Testcontainers acceptance check failed to PARTIAL. A Linux runner (or WSL2/self-hosted) with Docker is the single biggest unlock.

**Steps.**
- Provision runner with Docker Engine ≥24
- Register as GitHub Actions self-hosted runner (or use ubuntu-latest)
- Smoke: docker run hello-world in CI

**Deliverable.** CI runner executing Docker

**Acceptance.**
- CI job logs show containers starting
- Documented in docs/UI_DEVELOPMENT.md sibling runbook

### 17.1-G02 — CI job: compose stack up + Testcontainers ITs
*Completion phase:* **R0** · *Est:* 120 min · *Role:* DevOps · *Deps:* 17.1-G01

**Context.** ci.yml currently builds Gradle + both UIs only. Add a job that boots docker-compose.yml and runs ./gradlew integrationTest with Testcontainers.

**Steps.**
- Add integration job to .github/workflows/ci.yml gated on the Docker runner
- Cache Gradle + image layers
- Publish IT report artifact

**Deliverable.** Green integration CI job

**Acceptance.**
- Job passes on a PR
- Failure on a deliberately broken migration

### 17.1-G03 — Make docker-compose.yml actually boot
*Completion phase:* **R0** · *Est:* 180 min · *Role:* DevOps · *Deps:* 17.1-G01

**Context.** docker-compose.yml (7 postgres, mongo, redis, zookeeper, kafka, services 8080-8095) was authored but never executed; expect port/env/healthcheck fixes.

**Steps.**
- docker compose up on the runner
- Fix env vars, healthchecks, depends_on ordering
- Add profiles: core / full

**Deliverable.** Compose stack boots clean

**Acceptance.**
- All service healthchecks green within 3 min
- docs updated with profile usage

### 17.4-G05 — Schema Registry + event schema governance
*Completion phase:* **R1** · *Est:* 120 min · *Role:* DevOps · *Deps:* 17.4-G01

**Context.** Architecture diagram mandates Schema Registry. Stand up confluent schema-registry in compose; register JSON schemas for the 6 domain events; compatibility=BACKWARD.

**Steps.**
- Add schema-registry to compose
- Register schemas in CI step
- Producer validates against registry when configured

**Deliverable.** Schemas registered + CI-checked

**Acceptance.**
- Incompatible schema change fails CI

### 17.8-G01 — MinIO object storage in stack + lib-storage
*Completion phase:* **R1** · *Est:* 120 min · *Role:* Backend · *Deps:* 17.1-G03

**Context.** Recon/settlement/statement files need S3-compatible storage per architecture diagram.

**Steps.**
- minio service in compose + buckets bootstrap
- Small lib-storage put/get/list wrapper (AWS SDK v2)
- Creds via env (Vault later)

**Deliverable.** lib-storage against MinIO

**Acceptance.**
- put/get/list IT green in compose stack

### 18.2-G01 — Nginx reverse proxy fronting stack
*Completion phase:* **R3** · *Est:* 120 min · *Role:* DevOps · *Deps:* 18.7-G01,17.1-G03

**Context.** Architecture tile board mandates Nginx at the edge (ADR 18.7 confirms shape vs Spring Cloud Gateway).

**Steps.**
- nginx service in compose; routes /api→api-gateway, /admin→admin-ui, /portal→portal-ui
- TLS termination with self-signed dev cert
- Gzip, timeouts, request-id header

**Deliverable.** Single edge entrypoint

**Acceptance.**
- All UI+API traffic flows through nginx in compose stack

### 18.2-G02 — WAF ruleset + rate limiting at edge
*Completion phase:* **R3** · *Est:* 160 min · *Role:* DevOps · *Deps:* 18.2-G01

**Context.** Baseline OWASP CRS via ModSecurity (or nginx-native rules if CRS unavailable on Rocky base image).

**Steps.**
- Enable CRS paranoia 1; tune false positives on the 19 BFF endpoints
- limit_req zones per API key/IP
- Block test: sqli probe returns 403

**Deliverable.** WAF active at edge

**Acceptance.**
- OWASP ZAP baseline scan shows WAF blocks; legit E2E still green

