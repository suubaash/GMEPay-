# shared-libs  (platform)

**Scope:** Money/errors/events/domain/api-contracts, config & i18n frameworks, DB conventions

**Owned WBS work-packages:** 2.1, 2.2, 2.4, 2.6, 3.1, 3.7, 8.7  ·  **Tickets:** 187  ·  **Est:** 111.9h

## Service contract (MSA: own DB, API-only communication)

- **Datastore (owned by this service):** none
- **APIs / events I EXPOSE:** build-time JARs: lib-money, lib-errors, lib-events (schemas), lib-api-contracts (DTOs/clients)
- **APIs / events I CONSUME:** — (no runtime dependencies; contracts/utilities only)
- **Integration rule:** never read another service's database or import its private entities — call its API or consume its event; stub consumed services with WireMock in tests.

> Self-contained backlog for this service. Build it as its own repo/module with its own DB + Flyway migrations, against the `shared-libs` contracts (lib-money / lib-errors / lib-events / lib-api-contracts only). Each ticket has a deliverable + acceptance checks.


## WBS 2.1 — Finalize system architecture & tech stack
### 2.1-T01 — Initialise Gradle multi-module monorepo root with Java 21 and Spring Boot 3.x BOM  _(30 min)_
**Context:** GMEPay+ is built as a Gradle multi-module monorepo. The root settings.gradle declares all modules; the root build.gradle applies the Spring Boot 3.x dependency BOM and Java 21 toolchain to all subprojects. No module contains business logic yet - this ticket only sets up the skeleton so all subsequent tickets can add modules.
**Steps:** Create root settings.gradle listing all module paths: shared libs (lib-money, lib-errors, lib-api-contracts, lib-events, lib-persistence) and service stubs (services/qr-service, services/smart-router, services/rate-fx, services/auth-identity, services/payment-executor, services/txn-mgmt, services/prefunding, services/notification, services/settlement, services/revenue-ledger, services/reporting, services/merchant-qr, services/scheme-adapter-zeropay, services/config-registry, services/ops-bff, services/api-gateway); Create root build.gradle: apply io.spring.dependency-management plugin, set Spring Boot BOM to 3.3.x, java toolchain languageVersion = 21, common dependencies (spring-boot-starter, lombok, mapstruct) in subprojects block; Create gradle/wrapper/gradle-wrapper.properties pinned to Gradle 8.7; Add .gitignore, README stub, and gradle wrapper scripts; Run ./gradlew projects to verify all modules are recognised with zero compilation errors
**Deliverable:** Root settings.gradle, root build.gradle, gradle/wrapper/gradle-wrapper.properties listing all 21 modules (5 libs + 16 services)
**Acceptance / logic checks:**
- ./gradlew projects lists exactly 21 subprojects with no errors
- Java toolchain resolves to 21 (./gradlew -version shows JVM 21)
- Spring Boot BOM version 3.3.x appears in ./gradlew dependencies output for any subproject
- settings.gradle contains include statements for lib-money, lib-errors, lib-api-contracts, lib-events, lib-persistence and all 16 service modules
- No module contains src/main yet - this is a skeleton-only ticket

### 2.1-T02 — Create lib-money shared module: BigDecimal Money type with currency-aware scale  _(45 min)_
**Context:** GMEPay+ stores all monetary amounts as NUMERIC in PostgreSQL and uses BigDecimal in Java. lib-money must provide a Money value object (amount: BigDecimal, currency: String) that enforces per-currency scale: KRW = 0 decimal places, USD = 2. All arithmetic uses RoundingMode.HALF_UP. Division uses at least 10 significant figures internally before rounding to final scale. This library is a dependency for every service that touches money.
**Steps:** Create libs/lib-money/build.gradle with no Spring Boot starters (plain Java library, depends on jackson-databind for JSON ser/deser); Implement Money.java: final class, BigDecimal amount, String currency (ISO-4217), factory method Money.of(BigDecimal amount, String currency) that scales to currency precision; static SCALE_MAP: KRW->0, USD->2; Implement Money.add, Money.subtract, Money.multiply(BigDecimal factor), Money.divide(BigDecimal divisor, int internalScale) - divide uses MathContext(10, HALF_UP) internally, returns Money scaled to currency; Add CurrencyScale.java enum/registry so new currencies can be added by config without code change; Add MoneyDeserializer / MoneySerializer for Jackson
**Deliverable:** libs/lib-money/src/main/java/com/gme/pay/money/Money.java and CurrencyScale.java
**Acceptance / logic checks:**
- Money.of(new BigDecimal(50000.005), KRW).getAmount() equals 50000 (scale 0, HALF_UP)
- Money.of(new BigDecimal(1.005), USD).getAmount() equals 1.01 (scale 2, HALF_UP)
- Money.divide throws ArithmeticException if divisor is zero
- Money.add(Money with different currency) throws IllegalArgumentException
- Jackson round-trip: Money serialises to {amount:50000,currency:KRW} and deserialises back with correct BigDecimal scale
**Depends on:** 2.1-T01

### 2.1-T03 — Create lib-errors shared module: canonical error envelope and exception hierarchy  _(40 min)_
**Context:** All GMEPay+ services return structured error responses. The canonical error envelope is {error_code: string, message: string, txn_ref: string|null, details: object|null}. lib-errors defines the envelope DTO, a base GmePayException, and typed subclasses for domain errors including PARTNER_B_QUOTE_DEVIATION, PARTNER_B_QUOTE_UNAVAILABLE, INSUFFICIENT_PREFUNDING, RATE_QUOTE_EXPIRED, POOL_IDENTITY_VIOLATION. Services map these to HTTP status via a shared Spring @ControllerAdvice in this library.
**Steps:** Create libs/lib-errors/build.gradle depending on spring-webmvc (compileOnly) and jackson-databind; Define ErrorEnvelope.java record: String errorCode, String message, String txnRef, Object details; Define GmePayException.java extending RuntimeException with ErrorEnvelope payload and HTTP status; Define typed subclasses: RateQuoteExpiredException (404), PartnerBQuoteDeviationException (409), PartnerBQuoteUnavailableException (503), InsufficientPrefundingException (402), PoolIdentityViolationException (500), SchemeRejectException (422), IdempotencyConflictException (409); Add GlobalExceptionHandler.java annotated @ControllerAdvice @Order(Ordered.HIGHEST_PRECEDENCE) that maps GmePayException subclasses to ResponseEntity<ErrorEnvelope>
**Deliverable:** libs/lib-errors/src/main/java/com/gme/pay/errors/ErrorEnvelope.java, GmePayException.java, GlobalExceptionHandler.java, and all typed exception subclasses
**Acceptance / logic checks:**
- Throwing InsufficientPrefundingException results in HTTP 402 and errorCode INSUFFICIENT_PREFUNDING in the body
- ErrorEnvelope serialises as flat JSON with no null fields omitted (use @JsonInclude(NON_NULL) for optional fields)
- GlobalExceptionHandler is auto-configured via META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports so any service that imports lib-errors gets the handler automatically
- Unknown RuntimeException maps to HTTP 500 with errorCode INTERNAL_ERROR (catch-all in handler)
- All 7 typed exception subclasses have unique errorCode string constants defined in ErrorCodes.java
**Depends on:** 2.1-T01

### 2.1-T04 — Create lib-api-contracts module: OpenAPI spec scaffold and generated DTOs for Partner API v1  _(45 min)_
**Context:** lib-api-contracts holds the openapi/partner-api.yaml OpenAPI 3.1 spec and generates Java DTOs via the openapi-generator Gradle plugin. DTOs are used by all services that expose or consume the Partner API. The spec must define schemas for RateQuoteRequest, RateQuoteResponse (fields: quote_ref, offer_rate, send_amount, service_charge, collection_amount, collection_usd, payout_usd_cost, validUntil), PaymentCommitRequest, PaymentCommitResponse, CpmGenerateRequest, CpmTokenResponse, ErrorEnvelope. Internal margin fields m_a, m_b, cost_rate_coll, cost_rate_pay must NOT appear in the response schema.
**Steps:** Create libs/lib-api-contracts/build.gradle with openapi-generator-gradle-plugin 7.x configured to generate model classes only (generateModelTests=false, generateApiTests=false, library=spring) into build/generated; Create openapi/partner-api.yaml with info block (version 1.0.0), servers placeholder, and schemas for: RateQuoteRequest, RateQuoteResponse, PaymentCommitRequest, PaymentCommitResponse, CpmGenerateRequest, CpmTokenResponse, ErrorEnvelope - all fields typed as string/number with format annotations; Configure generation to use jackson, with BigDecimal for monetary fields and OffsetDateTime for timestamps; Wire compileJava to depend on openApiGenerate so generated sources are on the classpath; Verify ./gradlew :lib-api-contracts:build produces JAR with all DTO classes
**Deliverable:** libs/lib-api-contracts/openapi/partner-api.yaml and configured build producing DTOs in com.gme.pay.api.contracts package
**Acceptance / logic checks:**
- ./gradlew :lib-api-contracts:build succeeds with no compilation errors
- RateQuoteResponse class exists with fields: quoteRef (String), offerRate (BigDecimal), sendAmount (BigDecimal), serviceCharge (BigDecimal), collectionAmount (BigDecimal), collectionUsd (BigDecimal), payoutUsdCost (BigDecimal), validUntil (OffsetDateTime)
- Internal fields m_a, m_b, cost_rate_coll, cost_rate_pay are NOT present in RateQuoteResponse DTO
- Generated classes are in the JAR and importable from another module in the monorepo
- ErrorEnvelope DTO fields align with lib-errors ErrorEnvelope (errorCode, message, txnRef, details)
**Depends on:** 2.1-T01, 2.1-T03

### 2.1-T05 — Create lib-events module: domain event base class, EventPublisher interface, and 6 event schemas  _(40 min)_
**Context:** lib-events defines domain events used in the transactional Outbox pattern (Phase 1). Kafka is the deferred transport (Phase 2); Phase 1 uses an in-process EventPublisher interface backed by outbox-polling. Events: TransactionStateChangedEvent (txnRef, previousState, newState, partnerId, schemeId), PrefundingDeductedEvent, PrefundingLowBalanceEvent, WebhookDispatchRequestedEvent, SettlementBatchTriggerEvent, MerchantSyncInboundEvent. Each event has: eventId (UUID), eventType (String), occurredAt (Instant), aggregateId (String). Avro schemas mirror Java classes.
**Steps:** Create libs/lib-events/build.gradle with jackson-databind and avro 1.11 for .avsc schema files; Define DomainEvent.java abstract base: UUID eventId, String eventType, Instant occurredAt, String aggregateId; Implement 6 typed event subclasses with strongly-typed payload fields as listed in context; Define EventPublisher.java interface with single method: void publish(DomainEvent event); Implement InProcessEventPublisher.java @Component @Primary: stores events in a CopyOnWriteArrayList for test inspection; add getPublishedEvents() and clearPublishedEvents() test helpers; Create avro/ directory with 6 .avsc schema files mirroring Java class field names and types
**Deliverable:** libs/lib-events/src/main/java/com/gme/pay/events/ with DomainEvent.java, EventPublisher.java, InProcessEventPublisher.java, and 6 typed event classes; 6 .avsc schema files
**Acceptance / logic checks:**
- EventPublisher interface has exactly one method: void publish(DomainEvent event) - Kafka can be wired behind this interface without changing callers
- TransactionStateChangedEvent payload includes: txnRef, previousState, newState, partnerId, schemeId (all String)
- All 6 event classes are Jackson-serialisable to valid JSON (round-trip test with ObjectMapper)
- Each .avsc file matches the corresponding Java class field names and types
- InProcessEventPublisher.getPublishedEvents() returns all events published in a test; clearPublishedEvents() resets the list
**Depends on:** 2.1-T01

### 2.1-T06 — Create lib-persistence module: JPA auto-config, Flyway base path convention, and AuditableEntity  _(40 min)_
**Context:** lib-persistence is a shared Spring Boot auto-configuration library providing: JPA base configuration (PostgreSQL 16 dialect, Hibernate 6, NUMERIC for BigDecimal), Flyway migration base path convention (each service has classpath:db/migration), and AuditableEntity base class (@MappedSuperclass with createdAt, updatedAt, createdBy populated via Spring Data auditing). All money columns map to NUMERIC(38,10) via Hibernate custom type. Services import this lib and get consistent JPA/Flyway setup without extra annotations.
**Steps:** Create libs/lib-persistence/build.gradle depending on spring-boot-starter-data-jpa, flyway-core, postgresql driver; Define PersistenceAutoConfiguration.java @AutoConfiguration configuring HibernateJpaVendorAdapter with PostgreSQL16Dialect and spring.jpa.properties for NUMERIC column mapping; Define AuditableEntity.java @MappedSuperclass with @CreatedDate Instant createdAt, @LastModifiedDate Instant updatedAt, @CreatedBy String createdBy (from SecurityContext); Register auto-configuration in META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports; Add FlywayMigrationStrategy bean that logs migration count; services configure spring.flyway.locations=classpath:db/migration in their own application.yaml
**Deliverable:** libs/lib-persistence/src/main/java/com/gme/pay/persistence/PersistenceAutoConfiguration.java and AuditableEntity.java
**Acceptance / logic checks:**
- A service that imports lib-persistence and provides spring.datasource.* gets Hibernate + Flyway auto-configured with no additional @EnableJpa annotation
- AuditableEntity.createdAt is populated automatically on persist (verified in @DataJpaTest with Testcontainers Postgres 16)
- Money BigDecimal fields mapped as NUMERIC(38,10) in DDL (verify via Hibernate schema export)
- Flyway picks up V*.sql from classpath:db/migration without additional service config
- lib-persistence does NOT instantiate a DataSource itself; it only configures one when connection properties are present (conditional @Bean)
**Depends on:** 2.1-T01, 2.1-T02

### 2.1-T07 — Author ADR-001: Gradle multi-module monorepo and shared library strategy  _(25 min)_
**Context:** ADR-001 documents the decision to use a Gradle 8.x multi-module monorepo with Java 21 and Spring Boot 3.x BOM. The 5 shared libs (lib-money, lib-errors, lib-api-contracts, lib-events, lib-persistence) have defined boundaries. The constraint: services must not compile-depend on each other - only on shared libs or communicate via HTTP/domain events. OpenTelemetry auto-instrumentation is inherited by all services from the root BOM.
**Steps:** Create docs/adr/ADR-001-monorepo-gradle-shared-libs.md using standard ADR template: Title, Status (Accepted), Context, Decision, Consequences; State decision: Gradle 8.7 multi-module; Java 21 LTS; Spring Boot 3.3.x BOM at root; 5 shared libs with defined responsibilities; inter-service communication only via REST or domain events (no compile-time service-to-service dependency); List all 5 shared libs with their responsibilities in the Decision section; State consequences: single build graph enables atomic cross-module refactors; shared lib changes require all dependent services to be retested; lib-money is the sole source of monetary arithmetic truth; Include a module dependency graph showing libs -> services direction only (Mermaid or ASCII); Add entry to docs/adr/README.md ADR index
**Deliverable:** docs/adr/ADR-001-monorepo-gradle-shared-libs.md
**Acceptance / logic checks:**
- ADR status is Accepted
- Decision section names all 5 shared libs with one-line responsibilities
- Consequences section states the constraint: no compile-time service-to-service dependency
- Dependency graph shows arrows from services to libs only (not service to service)
- docs/adr/README.md lists ADR-001 with title and date
**Depends on:** 2.1-T01

### 2.1-T08 — Author ADR-002: Polyglot persistence strategy (PostgreSQL 16, MongoDB, Redis 7, Object Storage)  _(25 min)_
**Context:** GMEPay+ uses 4 datastores. PostgreSQL 16: all money-critical paths (transactions, ledger, prefunding, config, audit) using NUMERIC columns and SELECT FOR UPDATE for atomic prefunding deduction. MongoDB 7: merchant/QR store and CQRS read-model projections (variable schema, high read volume). Redis 7: ephemeral caching with TTL (rate quotes 60-300s, idempotency keys 24h, config hot cache 300s). S3-compatible/MinIO: ZP00xx batch file archival, BOK reports, settlement exports. ADR-002 documents service-to-store ownership and the constraint against cross-service DB access.
**Steps:** Create docs/adr/ADR-002-polyglot-persistence.md with standard ADR template; State decision with technology versions: PostgreSQL 16 (money path), MongoDB 7 (merchant/QR CQRS), Redis 7 (ephemeral cache), S3-compatible/MinIO (object storage); Document service-to-store ownership: payment-executor, txn-mgmt, prefunding, settlement, config-registry own PostgreSQL schemas; merchant-qr owns MongoDB; api-gateway and rate-fx own Redis; scheme-adapter-zeropay writes to Object Storage; State rationale for NUMERIC over FLOAT: monetary precision, pool identity invariant requires exact decimal arithmetic within 0.01 USD tolerance; State constraint: no service accesses another service data store directly (enforced at network level); Add to docs/adr/README.md index
**Deliverable:** docs/adr/ADR-002-polyglot-persistence.md
**Acceptance / logic checks:**
- All 4 store types named with concrete technology versions (PostgreSQL 16, Redis 7, MongoDB 7, S3-compatible/MinIO)
- Service ownership table is present and covers all 16 services
- Constraint against cross-service DB access is explicitly stated
- Rationale for NUMERIC (not FLOAT) is stated with reference to pool identity invariant (tolerance 0.01 USD)
- ADR cross-references ADR-001
**Depends on:** 2.1-T07

### 2.1-T09 — Author ADR-003: Transactional Outbox pattern for domain events; Kafka deferred to integration phase  _(25 min)_
**Context:** Phase 1 domain events are written to an outbox_events table in the same PostgreSQL transaction as the state change, then polled and dispatched via InProcessEventPublisher (lib-events). Kafka is introduced in the integration phase behind the EventPublisher interface without rework. Outbox table: id UUID PK, aggregate_id VARCHAR(64), event_type VARCHAR(128), payload JSONB, created_at TIMESTAMPTZ, processed_at TIMESTAMPTZ, retry_count INT. Ordering: by created_at. Delivery guarantee: at-least-once; consumers must be idempotent on eventId.
**Steps:** Create docs/adr/ADR-003-transactional-outbox-kafka-deferred.md with standard ADR template; State decision: Phase 1 uses transactional Outbox in PostgreSQL; a @Scheduled poller in each service reads unprocessed rows (WHERE processed_at IS NULL ORDER BY created_at) and calls EventPublisher.publish(); State Phase 2 migration path: replace InProcessEventPublisher with KafkaEventPublisher implementing the same interface; outbox becomes the Kafka producer buffer (Debezium CDC or polling relay); Include the outbox table DDL as a code block (CREATE TABLE outbox_events with all 7 columns) for reference; Document at-least-once guarantee and requirement that consumers are idempotent on eventId UUID; Add to ADR index
**Deliverable:** docs/adr/ADR-003-transactional-outbox-kafka-deferred.md with outbox table DDL snippet
**Acceptance / logic checks:**
- Phase 1 vs Phase 2 boundary is clearly stated with interface isolation point named (EventPublisher)
- Outbox table schema includes all 7 columns: id, aggregate_id, event_type, payload, created_at, processed_at, retry_count
- At-least-once delivery guarantee and idempotency requirement on consumers are explicitly stated
- Migration path to Kafka requires zero changes to event producers (only the EventPublisher implementation changes)
- ADR cross-references lib-events EventPublisher interface
**Depends on:** 2.1-T05, 2.1-T08

### 2.1-T10 — Author ADR-004: Spring Cloud Gateway as Northbound API edge with HMAC-SHA256 and rate limiting  _(25 min)_
**Context:** Spring Cloud Gateway (spring-cloud-starter-gateway) is the single entry point for all Partner API traffic. It enforces: TLS 1.2+ termination (by upstream load balancer), per-partner HMAC-SHA256 request signature validation (X-GME-Signature header), Idempotency-Key header on POST requests, per-partner rate limiting via Redis token bucket (Spring Cloud Gateway RequestRateLimiter), and structured access logging with txn_ref/partner_id MDC fields. ADR-004 documents the choice over Kong/AWS API GW and the filter chain order.
**Steps:** Create docs/adr/ADR-004-spring-cloud-gateway-hmac-auth.md with standard ADR template; State decision: Spring Cloud Gateway chosen to keep entire stack in-JVM, enabling shared lib-errors and lib-events without polyglot bridge; trade-off acknowledged (higher JVM memory vs Kong plugin ecosystem); Document Gateway filter chain order: 1) HmacSignatureFilter (custom GlobalFilter) validates X-GME-Signature, returns 401 on failure; 2) IdempotencyKeyFilter enforces Idempotency-Key on POST/PUT, returns 422 on absence; 3) RateLimitFilter (RequestRateLimiter with Redis backend); 4) route to downstream service; State security constraint: requests failing HMAC return 401 before any downstream service is called; Add to ADR index
**Deliverable:** docs/adr/ADR-004-spring-cloud-gateway-hmac-auth.md
**Acceptance / logic checks:**
- Decision rationale explicitly compares Spring Cloud Gateway vs Kong and states the in-JVM rationale
- Filter chain order lists all 3 filters with their sequence number
- HMAC-SHA256 is named as algorithm; X-GME-Signature as header name
- Redis is named as the rate-limit state store (RequestRateLimiter backend)
- ADR states downstream services trust the Gateway and do not re-validate HMAC
**Depends on:** 2.1-T07

### 2.1-T11 — Create Flyway migration V1 for outbox_events table in services/txn-mgmt  _(35 min)_
**Context:** The transactional Outbox table (ADR-003) lives in the txn-mgmt PostgreSQL schema. The outbox_events table has: id UUID DEFAULT gen_random_uuid() PRIMARY KEY, aggregate_id VARCHAR(64) NOT NULL, event_type VARCHAR(128) NOT NULL, payload JSONB NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), processed_at TIMESTAMPTZ, retry_count INT NOT NULL DEFAULT 0. A partial index on (created_at) WHERE processed_at IS NULL powers the poller query.
**Steps:** Create services/txn-mgmt/src/main/resources/db/migration/V1__create_outbox_events.sql; Write CREATE TABLE outbox_events with all 7 columns as specified in context; Add partial index: CREATE INDEX idx_outbox_pending ON outbox_events (created_at) WHERE processed_at IS NULL; Create TxnMgmtApplication.java @SpringBootApplication; Create FlywayMigrationIT.java using @SpringBootTest + Testcontainers postgres:16: assert migration runs cleanly, table exists, index exists; Assert inserting a row without id populates UUID via gen_random_uuid()
**Deliverable:** services/txn-mgmt/src/main/resources/db/migration/V1__create_outbox_events.sql and FlywayMigrationIT.java
**Acceptance / logic checks:**
- Flyway V1 applies without error on a clean PostgreSQL 16 Testcontainer
- outbox_events table has all 7 columns with correct types (UUID, VARCHAR, JSONB, TIMESTAMPTZ, INT)
- Partial index idx_outbox_pending covers only rows WHERE processed_at IS NULL (verified via psql meta-query)
- Inserting a row without explicit id results in non-null UUID (gen_random_uuid() default)
- Running Flyway migrate twice does not error (idempotent via checksum)
**Depends on:** 2.1-T06, 2.1-T09

### 2.1-T12 — Create Flyway migration V1 for config schema in services/config-registry: schemes, partners, rules, audit_log  _(40 min)_
**Context:** Config/Registry stores Scheme, Partner, Rule, and audit log in PostgreSQL 16. Scheme: id UUID PK, scheme_code VARCHAR UNIQUE, display_name VARCHAR, supported_modes VARCHAR[], payout_currency CHAR(3), active BOOLEAN, created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ. Partner: id UUID PK, partner_code VARCHAR UNIQUE, type VARCHAR CHECK IN (LOCAL,OVERSEAS), webhook_url TEXT, rate_quote_ttl_seconds INT CHECK 60..1800, active BOOLEAN, created_at, updated_at. Rule: id UUID PK, partner_id UUID FK, scheme_id UUID FK, direction VARCHAR CHECK IN (Inbound,Outbound,Domestic,Hub), margin_a NUMERIC(8,6), margin_b NUMERIC(8,6), service_charge_amount NUMERIC(18,6), service_charge_currency CHAR(3), rate_source_coll VARCHAR, rate_source_pay VARCHAR, active BOOLEAN, effective_from TIMESTAMPTZ, effective_to TIMESTAMPTZ (NULL = open-ended). Soft-delete via effective_to. config_audit_log: id UUID PK DEFAULT gen_random_uuid(), table_name VARCHAR, record_id UUID, actor VARCHAR, changed_at TIMESTAMPTZ DEFAULT now(), previous_value JSONB, new_value JSONB.
**Steps:** Create services/config-registry/src/main/resources/db/migration/V1__create_config_schema.sql; Write CREATE TABLE schemes with columns and UNIQUE on scheme_code; Write CREATE TABLE partners with CHECK (type IN (LOCAL,OVERSEAS)), CHECK (rate_quote_ttl_seconds BETWEEN 60 AND 1800); Write CREATE TABLE rules with FK to schemes and partners, CHECK (direction IN (Inbound,Outbound,Domestic,Hub)), CHECK (margin_a >= 0 AND margin_b >= 0); Write CREATE TABLE config_audit_log with all columns; Add indexes: rules(partner_id, scheme_id, direction), config_audit_log(record_id, changed_at)
**Deliverable:** services/config-registry/src/main/resources/db/migration/V1__create_config_schema.sql
**Acceptance / logic checks:**
- All 4 tables created without error on PostgreSQL 16 Testcontainer
- CHECK constraint on partners.type rejects insert with type=AGGREGATOR
- CHECK constraint on rules.direction rejects insert with direction=Unknown
- CHECK on partners.rate_quote_ttl_seconds rejects values < 60 and > 1800
- Soft-delete query SELECT * FROM rules WHERE effective_to IS NULL OR effective_to > now() returns only active rules
**Depends on:** 2.1-T06

### 2.1-T13 — Scaffold services/config-registry: ConfigRegistryApplication, Rule JPA entity, Redis cache layer  _(45 min)_
**Context:** services/config-registry is a Spring Boot 3.x service using lib-persistence (PostgreSQL 16 via JPA) and Redis 7 as hot cache. ConfigCacheService fetches Rule by (partnerId, schemeId, direction): checks Redis key rule:{partnerId}:{schemeId}:{direction} (TTL 300s) first, falls back to PostgreSQL via RuleRepository, writes through on cache miss. Config changes call redisTemplate.delete(cacheKey) to invalidate. On rule update, ConfigChangeService publishes a ConfigChangedEvent via EventPublisher (lib-events).
**Steps:** Create services/config-registry/build.gradle: spring-boot-starter-web, spring-boot-starter-data-jpa, spring-boot-starter-data-redis, lib-persistence, lib-errors, lib-events; Create ConfigRegistryApplication.java @SpringBootApplication; Create Rule.java @Entity mapping to rules table; RuleRepository.java extends JpaRepository with findByPartnerIdAndSchemeIdAndDirectionAndActiveTrueAndEffectiveToIsNull; Create ConfigCacheService.java @Service: getRuleOrLoad(partnerId, schemeId, direction) checks Redis, on miss loads from JPA and SETs with 300s TTL; Create ConfigChangeService.java @Service: on rule update/deactivate, delete Redis key and publish ConfigChangedEvent via EventPublisher; Add application.yaml: spring.datasource, spring.data.redis, spring.flyway.locations=classpath:db/migration
**Deliverable:** services/config-registry/src/main/java/com/gme/pay/configregistry/ConfigCacheService.java and ConfigChangeService.java
**Acceptance / logic checks:**
- Cache miss: ConfigCacheService queries PostgreSQL and writes result to Redis (verify with RedisTemplate.hasKey after first call in @SpringBootTest)
- Cache hit: second call does not hit DB (mock RuleRepository, assert zero interactions on cache hit)
- Redis TTL on rule cache key is 300 seconds (redisTemplate.getExpire returns 295-300s)
- Config change invalidates Redis key: after ConfigChangeService.deactivateRule(), redisTemplate.hasKey returns false
- ConfigRegistryApplication starts against Testcontainers Postgres 16 + Redis 7 with Flyway V1 applied
**Depends on:** 2.1-T12, 2.1-T03, 2.1-T05, 2.1-T06

### 2.1-T14 — Create Flyway migration V1 for treasury_rates table in services/rate-fx  _(35 min)_
**Context:** services/rate-fx hosts the Rate and FX Engine. The treasury_rates table stores treasury.usd_{ccy} rates (units of local currency per 1 USD). Schema: id UUID PK DEFAULT gen_random_uuid(), currency_code CHAR(3) NOT NULL, rate_key VARCHAR(32) NOT NULL UNIQUE e.g. usd_krw, units_per_usd NUMERIC(18,8) NOT NULL CHECK (units_per_usd > 0), source VARCHAR CHECK IN (MANUAL,LIVE), effective_at TIMESTAMPTZ NOT NULL, created_by VARCHAR(128) NOT NULL, created_at TIMESTAMPTZ DEFAULT now(). Example: rate_key=usd_krw, units_per_usd=1380.00 means 1 USD = 1380 KRW.
**Steps:** Create services/rate-fx/src/main/resources/db/migration/V1__create_treasury_rates.sql; Write CREATE TABLE treasury_rates with all columns; CHECK (units_per_usd > 0); CHECK (source IN (MANUAL,LIVE)); Add index on (rate_key, effective_at DESC) for latest-rate query; Create RateFxApplication.java @SpringBootApplication; Create TreasuryRate.java @Entity and TreasuryRateRepository.java with findTopByRateKeyOrderByEffectiveAtDesc(String rateKey); Create FlywayMigrationIT.java using Testcontainers Postgres 16: assert migration runs cleanly, index exists, CHECK constraint rejects units_per_usd=0
**Deliverable:** services/rate-fx/src/main/resources/db/migration/V1__create_treasury_rates.sql and TreasuryRateRepository.java
**Acceptance / logic checks:**
- V1 migration applies cleanly on PostgreSQL 16 Testcontainer
- CHECK constraint rejects insert with units_per_usd = 0 and units_per_usd = -1
- findTopByRateKeyOrderByEffectiveAtDesc(usd_krw) returns the row with the most recent effective_at when multiple rows exist
- rate_key usd_krw convention documented in a SQL comment in the migration file
- RateFxApplication starts against Testcontainers Postgres 16 with Flyway applied
**Depends on:** 2.1-T06, 2.1-T12

### 2.1-T15 — Create Flyway migration V1 for transactions table in services/payment-executor  _(40 min)_
**Context:** services/payment-executor hosts the Transaction Orchestrator. The transactions table stores the full payment lifecycle. Columns: id UUID PK, txn_ref VARCHAR(64) UNIQUE NOT NULL, partner_id UUID NOT NULL, scheme_id UUID NOT NULL, direction VARCHAR NOT NULL, state VARCHAR NOT NULL CHECK IN (QUOTED,PENDING_DEBIT,DEBITED,SCHEME_SENT,APPROVED,UNCERTAIN,FAILED,REVERSED,REFUNDED), quote_ref VARCHAR(64), idempotency_key VARCHAR(256) UNIQUE, target_payout_value NUMERIC(18,6), target_payout_currency CHAR(3), collection_usd NUMERIC(18,8), payout_usd_cost NUMERIC(18,8), collection_margin_usd NUMERIC(18,8), payout_margin_usd NUMERIC(18,8), send_amount_value NUMERIC(18,6), send_amount_currency CHAR(3), service_charge_value NUMERIC(18,6), service_charge_currency CHAR(3), collection_amount_value NUMERIC(18,6), collection_amount_currency CHAR(3), offer_rate_coll NUMERIC(18,8), cross_rate NUMERIC(18,8), rate_locked_at TIMESTAMPTZ, scheme_approval_code VARCHAR(64), created_at TIMESTAMPTZ DEFAULT now(), updated_at TIMESTAMPTZ.
**Steps:** Create services/payment-executor/src/main/resources/db/migration/V1__create_transactions.sql; Write CREATE TABLE transactions with all 28 columns; CHECK (state IN (QUOTED,PENDING_DEBIT,DEBITED,SCHEME_SENT,APPROVED,UNCERTAIN,FAILED,REVERSED,REFUNDED)); Add UNIQUE index on idempotency_key, index on (partner_id, state, created_at), index on scheme_approval_code; Create PaymentExecutorApplication.java @SpringBootApplication; Create Transaction.java @Entity mapping all columns; Create TransactionRepository.java with findByTxnRef and findByIdempotencyKey queries; Add FlywayMigrationIT.java with Testcontainers Postgres 16 asserting migration applies and all constraints hold
**Deliverable:** services/payment-executor/src/main/resources/db/migration/V1__create_transactions.sql and Transaction.java @Entity
**Acceptance / logic checks:**
- V1 migration applies cleanly on Postgres 16 Testcontainer
- CHECK constraint rejects insert with state=PROCESSING
- UNIQUE constraint on idempotency_key rejects duplicate insert
- Rate-lock columns (collection_usd, offer_rate_coll etc.) are NULL on a newly inserted QUOTED row
- All 9 valid state values (QUOTED through REFUNDED) individually pass the CHECK constraint
**Depends on:** 2.1-T06, 2.1-T12

### 2.1-T16 — Create Flyway migration V1 for prefunding tables and PrefundingService with SELECT FOR UPDATE  _(50 min)_
**Context:** services/prefunding manages OVERSEAS partner prefunding balances atomically. Tables: prefunding_balances (id UUID PK, partner_id UUID UNIQUE NOT NULL, balance_usd NUMERIC(18,8) NOT NULL DEFAULT 0 CHECK (balance_usd >= 0), low_balance_threshold_usd NUMERIC(18,8) NOT NULL DEFAULT 10000, last_deducted_at TIMESTAMPTZ, version BIGINT NOT NULL DEFAULT 0); prefunding_transactions (id UUID PK DEFAULT gen_random_uuid(), partner_id UUID NOT NULL, txn_ref VARCHAR(64) NOT NULL, operation VARCHAR CHECK IN (DEBIT,CREDIT,TOPUP), amount_usd NUMERIC(18,8) NOT NULL, balance_after_usd NUMERIC(18,8) NOT NULL, created_at TIMESTAMPTZ DEFAULT now()). PrefundingService.deductBalance uses @Lock(PESSIMISTIC_WRITE) = SELECT FOR UPDATE. Insufficient balance throws InsufficientPrefundingException (HTTP 402, errorCode INSUFFICIENT_PREFUNDING) from lib-errors.
**Steps:** Create services/prefunding/src/main/resources/db/migration/V1__create_prefunding.sql with both tables and CHECK (balance_usd >= 0); Create PrefundingApplication.java @SpringBootApplication; Create PrefundingBalance.java @Entity, PrefundingTransaction.java @Entity; Create PrefundingRepository.java with @Lock(LockModeType.PESSIMISTIC_WRITE) on findByPartnerId for the deduction path; Create PrefundingService.java @Service @Transactional: deductBalance(UUID partnerId, BigDecimal amountUsd) - lock row, subtract, assert >= 0 or throw InsufficientPrefundingException, save, write prefunding_transactions row with operation=DEBIT
**Deliverable:** services/prefunding/src/main/resources/db/migration/V1__create_prefunding.sql and PrefundingService.java
**Acceptance / logic checks:**
- deductBalance of 100.00 USD from 500.00 USD balance leaves balance_after = 400.00 (@DataJpaTest + Testcontainers Postgres 16)
- deductBalance of 600.00 USD from 500.00 USD throws InsufficientPrefundingException and rolls back (balance remains 500.00)
- CHECK (balance_usd >= 0) prevents direct negative balance insert at DB level
- prefunding_transactions row is written with operation=DEBIT, correct amount_usd and balance_after_usd
- @Lock(PESSIMISTIC_WRITE) annotation is present on the repository method (verify via reflection or code review)
**Depends on:** 2.1-T06, 2.1-T03, 2.1-T12

### 2.1-T17 — Scaffold services/scheme-adapter-zeropay: SchemeAdapter interface and ZeroPaySchemeAdapter skeleton  _(45 min)_
**Context:** services/scheme-adapter-zeropay implements the ZeroPay protocol behind a scheme-agnostic SchemeAdapter interface. Interface methods: CpmAuthResponse authoriseCpm(CpmAuthRequest), MpmSubmitResponse submitMpm(MpmSubmitRequest), void generateBatchFile(BatchType, LocalDate), BatchRecord[] parseBatchFile(BatchType, byte[]), AdapterHealth healthCheck(), String getSchemeCode(). ZeroPaySchemeAdapter implements all methods (stub throwing UnsupportedOperationException for batch methods - to be implemented in scheme-specific tickets). Credentials (cpmApiUrl, sftpHost, sftpUser, sftpPassword) injected from Vault/env via @ConfigurationProperties - never hardcoded.
**Steps:** Create services/scheme-adapter-zeropay/build.gradle: spring-boot-starter-web, commons-net (SFTP), lib-errors, lib-events; Define SchemeAdapter.java interface with all 6 method signatures including getSchemeCode(); Implement ZeroPaySchemeAdapter.java @Service implementing SchemeAdapter; getSchemeCode() returns ZEROPAY; stub remaining methods; Create ZeroPayProperties.java @ConfigurationProperties(prefix=zeropay): cpmApiUrl, sftpHost, sftpPort, sftpUser, sftpPassword (all String, no defaults for credentials); Create SchemeAdapterApplication.java @SpringBootApplication; Add WireMock @SpringBootTest test: mock 한결원 CPM endpoint returns 200 JSON; assert authoriseCpm parses CpmAuthResponse without exception
**Deliverable:** services/scheme-adapter-zeropay/src/main/java/com/gme/pay/zeropay/SchemeAdapter.java and ZeroPaySchemeAdapter.java
**Acceptance / logic checks:**
- SchemeAdapter interface has exactly 6 method signatures including getSchemeCode()
- ZeroPaySchemeAdapter.getSchemeCode() returns the string ZEROPAY
- ZeroPayProperties binds zeropay.cpm-api-url from application.yaml without error
- No SFTP credentials appear in source code or application.yaml (only env placeholder references like ${ZEROPAY_SFTP_PASSWORD})
- WireMock test: authoriseCpm with mocked 200 response parses to CpmAuthResponse without exception
**Depends on:** 2.1-T01, 2.1-T03, 2.1-T05

### 2.1-T18 — Scaffold services/api-gateway: Spring Cloud Gateway with HmacSignatureFilter and IdempotencyKeyFilter  _(50 min)_
**Context:** services/api-gateway is a Spring Cloud Gateway 4.x application. This ticket creates the gateway with route stubs and two global filters: HmacSignatureFilter validates X-GME-Signature = HMAC-SHA256(partnerSecret, canonicalString) where canonicalString = METHOD + LF + path + LF + sha256(body) + LF + X-GME-Timestamp; returns 401 ErrorEnvelope on mismatch. IdempotencyKeyFilter requires Idempotency-Key header on all POST/PUT requests; returns 422 with errorCode MISSING_IDEMPOTENCY_KEY if absent. For this ticket, use a hardcoded test secret; real lookup from auth-identity is wired in T19.
**Steps:** Create services/api-gateway/build.gradle: spring-cloud-starter-gateway, spring-boot-starter-data-redis, lib-errors; Create ApiGatewayApplication.java @SpringBootApplication; Create HmacSignatureFilter.java implements GlobalFilter, Ordered: extract X-GME-Signature and X-GME-Partner-Id; compute expected HMAC-SHA256 over canonical string; constant-time compare; return 401 on mismatch; Create IdempotencyKeyFilter.java implements GlobalFilter, Ordered: on POST/PUT, if Idempotency-Key absent return 422; Create application.yaml with spring.cloud.gateway.routes stubs pointing to localhost placeholders for each backend service; Write HmacSignatureFilterTest.java using MockServerWebExchange: valid signature passes, tampered signature returns 401, missing Idempotency-Key on POST returns 422
**Deliverable:** services/api-gateway/src/main/java/com/gme/pay/gateway/HmacSignatureFilter.java and IdempotencyKeyFilter.java
**Acceptance / logic checks:**
- Valid HMAC-SHA256 signature passes filter and request proceeds (mock downstream returns 200)
- Tampered signature (single character change) returns 401 with errorCode INVALID_SIGNATURE
- POST without Idempotency-Key returns 422 with errorCode MISSING_IDEMPOTENCY_KEY
- GET without Idempotency-Key passes IdempotencyKeyFilter without error
- HmacSignatureFilter.getOrder() returns lower integer than IdempotencyKeyFilter.getOrder() (HMAC checked first)
**Depends on:** 2.1-T03, 2.1-T04, 2.1-T10

### 2.1-T19 — Scaffold services/auth-identity: partner credential store and HMAC secret resolution endpoint  _(45 min)_
**Context:** services/auth-identity stores partner API credentials. Schema: partner_credentials (id UUID PK, partner_id UUID UNIQUE NOT NULL, api_key VARCHAR(64) UNIQUE NOT NULL, hmac_secret_hash VARCHAR(128) NOT NULL as BCrypt hash, active BOOLEAN DEFAULT true, created_at TIMESTAMPTZ DEFAULT now()). AuthIdentityService: generateCredentials(UUID partnerId) returns new api_key + raw hmac_secret (stored as BCrypt hash, never retrievable after generation); resolveHmacSecretHash(String apiKey) returns hash for HMAC validation; resolvePartnerId(String apiKey) returns partnerId UUID. Internal endpoints only - not exposed on public Partner API route. Wire api-gateway HmacSignatureFilter to call GET /internal/auth/resolve?apiKey=... via RestClient.
**Steps:** Create services/auth-identity/src/main/resources/db/migration/V1__create_partner_credentials.sql; Create AuthIdentityApplication.java @SpringBootApplication; Create PartnerCredentials.java @Entity mapping to partner_credentials table; Create AuthIdentityService.java @Service with generateCredentials, resolveHmacSecretHash, resolvePartnerId methods; Create AuthIdentityController.java @RestController: POST /internal/auth/credentials, GET /internal/auth/resolve?apiKey=...; Wire HmacSignatureFilter in api-gateway to call auth-identity via RestClient (inject URL from zeropay.auth-identity.url config)
**Deliverable:** services/auth-identity/src/main/java/com/gme/pay/auth/AuthIdentityService.java and AuthIdentityController.java
**Acceptance / logic checks:**
- generateCredentials stores BCrypt hash starting with $2a$ (never plaintext)
- resolveHmacSecretHash returns empty Optional for unknown apiKey (gateway treats as 401)
- api_key UNIQUE constraint prevents duplicate registration (PSQLException on duplicate insert)
- GET /internal/auth/resolve?apiKey=unknown returns 404 with errorCode CREDENTIAL_NOT_FOUND
- AuthIdentityController endpoints are NOT reachable through the public partner-facing Gateway route (only internal route)
**Depends on:** 2.1-T06, 2.1-T12, 2.1-T18

### 2.1-T20 — Implement RateEngine core: 5-step USD pool computation with same-currency short-circuit  _(55 min)_
**Context:** RateEngine in services/rate-fx executes payout-first RECEIVE-mode computation. Inputs: targetPayout (BigDecimal), payoutCurrency (String), costRatePay (BigDecimal = treasury.usd_{settle_b_ccy}), costRateColl (BigDecimal = treasury.usd_{settle_a_ccy}), marginA (BigDecimal fraction), marginB (BigDecimal fraction), serviceCharge (BigDecimal in settle_A ccy). Steps: 1) payoutUsdCost = targetPayout / costRatePay; 2) collectionUsd = payoutUsdCost / (1 - marginA - marginB); 3) collectionMarginUsd = collectionUsd * marginA; payoutMarginUsd = collectionUsd * marginB; 4) sendAmount = collectionUsd * costRateColl; 5) collectionAmount = sendAmount + serviceCharge. Pool identity: |collectionUsd - collectionMarginUsd - payoutMarginUsd - payoutUsdCost| <= 0.01 USD; if violated throw PoolIdentityViolationException. Same-currency short-circuit: if all currencies match, collectionAmount = targetPayout + serviceCharge (skip USD pool). BOK derived: offerRateColl = sendAmount / (collectionUsd - collectionMarginUsd); crossRate = targetPayout / sendAmount. All division uses MathContext(10, HALF_UP).
**Steps:** Create services/rate-fx/src/main/java/com/gme/pay/ratefx/engine/RateEngine.java @Service; Implement computeQuote(RateQuoteInput input) returning RateQuoteResult record with all computed fields; Add same-currency short-circuit: if all 4 currencies are equal, return collectionAmount = targetPayout + serviceCharge with USD pool fields null; Verify pool identity after step 3; throw PoolIdentityViolationException from lib-errors if |delta| > 0.01; Derive BOK fields offerRateColl and crossRate after step 4; Use MathContext(10, HALF_UP) for all BigDecimal division operations
**Deliverable:** services/rate-fx/src/main/java/com/gme/pay/ratefx/engine/RateEngine.java
**Acceptance / logic checks:**
- Cross-border test: targetPayout=50000, costRatePay=1.0, costRateColl=1380.0, marginA=0.015, marginB=0.01, serviceCharge=0; payoutUsdCost=50000.0, collectionUsd=50000/0.975=51282.0512..., sendAmount=51282.0512*1380=70769030...; pool identity holds within 0.01 USD
- Same-currency test: targetPayout=10000 KRW, all currencies KRW, serviceCharge=500; collectionAmount=10500, USD pool fields all null
- Pool identity violation: inject marginA=0.99; assert PoolIdentityViolationException is thrown
- Service charge outside pool: vary serviceCharge 0 vs 500; assert collectionUsd is identical in both cases
- Division uses MathContext(10, HALF_UP): no ArithmeticException on non-terminating decimal (1/3 etc.)
**Depends on:** 2.1-T14, 2.1-T02, 2.1-T03

### 2.1-T21 — Implement RateQuoteService in rate-fx: Redis TTL cache for quotes and atomic lockQuote for commit  _(45 min)_
**Context:** After computing a rate quote, RateQuoteService stores the RateQuoteResult in Redis under key rate_quote:{quoteRef} with TTL = rule.rateQuoteTtlSeconds (range 60-1800s; default 60s for aggregator-bound, 300s otherwise). On CommitTransaction, lockQuote(quoteRef) uses Redis GETDEL (atomic get-and-delete) to fetch and consume the quote in one operation. If key absent or expired: throw RateQuoteExpiredException (errorCode RATE_QUOTE_EXPIRED). getQuote(quoteRef) is a read-only GET (no delete) for idempotency checks.
**Steps:** Create services/rate-fx/src/main/java/com/gme/pay/ratefx/RateQuoteService.java @Service with RedisTemplate<String, String> (JSON serialized); Implement storeQuote(RateQuoteResult quote, int ttlSeconds): serialize to JSON; SET rate_quote:{quote.quoteRef} with EX ttlSeconds; Implement lockQuote(String quoteRef): execute Redis GETDEL; if null throw RateQuoteExpiredException; deserialize and return RateQuoteResult; Implement getQuote(String quoteRef): Redis GET without delete; return Optional<RateQuoteResult>; Write @SpringBootTest integration test using Testcontainers Redis 7: store, assert get, lockQuote returns and subsequent lockQuote throws, verify TTL on stored key
**Deliverable:** services/rate-fx/src/main/java/com/gme/pay/ratefx/RateQuoteService.java
**Acceptance / logic checks:**
- storeQuote with ttlSeconds=60 results in Redis key with TTL between 58-60 seconds (allow 2s test execution slack)
- lockQuote is atomic: two concurrent threads calling lockQuote on the same quoteRef result in exactly one success and one RateQuoteExpiredException (CountDownLatch test)
- lockQuote on expired key (ttl=1, wait 2s) throws RateQuoteExpiredException with errorCode RATE_QUOTE_EXPIRED
- getQuote does NOT delete the key (key still exists after call; verify with RedisTemplate.hasKey)
- RateQuoteResult BigDecimal fields survive JSON round-trip with no precision loss (compare field by field with compareTo)
**Depends on:** 2.1-T20, 2.1-T03

### 2.1-T22 — Author ADR-005: Config-not-code for schemes and partners; Rule resolution algorithm  _(25 min)_
**Context:** AD-04 (SAD-02) mandates zero deployments to add a scheme or partner. ADR-005 documents how: Scheme, Partner, and Rule are rows in PostgreSQL; SmartRouterService resolves the adapter at runtime via SchemeAdapterRegistry (Map<String,SchemeAdapter> populated from Spring beans keyed by getSchemeCode()); RateEngine resolves margins via ConfigCacheService by exact (partner_id, scheme_id, direction) lookup. Rule resolution: exact match with effective_from <= now() < effective_to (or effective_to IS NULL); no active rule returns 422 errorCode RULE_NOT_FOUND. No if/switch on partner_code or scheme_code in core code.
**Steps:** Create docs/adr/ADR-005-config-not-code-rule-resolution.md with standard ADR template; State decision: SchemeAdapterRegistry built from @Autowired List<SchemeAdapter> beans at startup; keyed by getSchemeCode(); no hardcoded scheme identifiers in routing logic; State Rule resolution algorithm: exact match on (partner_id, scheme_id, direction) with time-bounded effectivity; no wildcard fallback in Phase 1; State constraint: no if/switch on partner_code or scheme_code in Transaction Orchestrator, Rate Engine, or Smart Router source code; Document RULE_NOT_FOUND error response (HTTP 422) when no active rule exists; Add to ADR index
**Deliverable:** docs/adr/ADR-005-config-not-code-rule-resolution.md
**Acceptance / logic checks:**
- Decision names SchemeAdapterRegistry as Map<String,SchemeAdapter> Spring bean populated by @Autowired List<SchemeAdapter>
- Rule resolution algorithm states exact (partner_id, scheme_id, direction) match with time-bounded effectivity
- Constraint against hardcoded partner/scheme logic in core code is explicitly stated
- RULE_NOT_FOUND is named as the errorCode for missing active rule (HTTP 422)
- ADR cross-references config-registry service and ConfigCacheService
**Depends on:** 2.1-T13, 2.1-T07

### 2.1-T23 — Author ADR-006: Rate lock immutability at CommitTransaction; BOK reporting from locked values  _(25 min)_
**Context:** AD-08 (SAD-02): all 9 USD pool values and derived rates are permanently recorded at CommitTransaction and immutable afterward. The 9 locked columns in transactions table: collection_usd, payout_usd_cost, collection_margin_usd, payout_margin_usd, send_amount_value, service_charge_value, collection_amount_value, offer_rate_coll, cross_rate. Enforcement: PostgreSQL BEFORE UPDATE trigger raises exception if any locked column is modified after rate_locked_at IS NOT NULL. Application guard: TransactionService.lockRate() throws IllegalStateException if called on already-locked row. BOK FX1015 reports read these stored values directly - never recompute at report time.
**Steps:** Create docs/adr/ADR-006-rate-lock-immutability.md with standard ADR template; List all 9 immutable column names explicitly; Document DB enforcement: PostgreSQL BEFORE UPDATE trigger (protect_rate_lock_columns) raises SQLSTATE P0001 if any locked column changes after rate_locked_at IS NOT NULL; Document application enforcement: TransactionService.lockRate() throws IllegalStateException if rate_locked_at already set; State BOK reporting implication: Reporting service reads stored locked values; never recomputes rates at report time; Add to ADR index
**Deliverable:** docs/adr/ADR-006-rate-lock-immutability.md
**Acceptance / logic checks:**
- All 9 rate-lock column names are listed correctly (matches V1 migration schema from T15)
- DB trigger approach named as protect_rate_lock_columns BEFORE UPDATE trigger
- Application guard named as IllegalStateException if rate_locked_at already set
- BOK reporting section states reports use stored locked values, not live treasury rates
- ADR cross-references pool identity invariant (|collectionUsd - collectionMarginUsd - payoutMarginUsd - payoutUsdCost| <= 0.01 USD)
**Depends on:** 2.1-T08, 2.1-T15

### 2.1-T24 — Create Flyway migration V2 in payment-executor: rate-lock immutability trigger  _(40 min)_
**Context:** To enforce ADR-006 at DB level, a PostgreSQL BEFORE UPDATE trigger on transactions raises an exception if any of the 9 rate-lock columns are modified when rate_locked_at IS NOT NULL. The 9 protected columns: collection_usd, payout_usd_cost, collection_margin_usd, payout_margin_usd, send_amount_value, service_charge_value, collection_amount_value, offer_rate_coll, cross_rate. Non-rate-lock columns (state, updated_at, scheme_approval_code) must remain updatable after lock.
**Steps:** Create services/payment-executor/src/main/resources/db/migration/V2__rate_lock_trigger.sql; Write PL/pgSQL function protect_rate_lock_columns() RETURNS TRIGGER: IF OLD.rate_locked_at IS NOT NULL AND any of the 9 columns IS DISTINCT FROM OLD, RAISE EXCEPTION with SQLSTATE P0001 and message rate lock columns are immutable after commit; Write CREATE TRIGGER trg_protect_rate_lock BEFORE UPDATE ON transactions FOR EACH ROW EXECUTE FUNCTION protect_rate_lock_columns(); Add Testcontainers Postgres 16 test: insert APPROVED transaction with rate_locked_at set; attempt UPDATE on collection_usd; assert PSQLException with SQLSTATE P0001; Assert UPDATE on state column (APPROVED -> REVERSED) still succeeds after rate_locked_at is set
**Deliverable:** services/payment-executor/src/main/resources/db/migration/V2__rate_lock_trigger.sql
**Acceptance / logic checks:**
- UPDATE on collection_usd after rate_locked_at IS NOT NULL raises PSQLException with SQLSTATE P0001
- UPDATE on state column after rate_locked_at IS NOT NULL succeeds (non-rate-lock column)
- UPDATE on any rate-lock column while rate_locked_at IS NULL succeeds (rate not yet locked)
- Trigger is FOR EACH ROW (not statement-level)
- Flyway V1 (transactions) and V2 (trigger) both apply cleanly in sequence on Postgres 16
**Depends on:** 2.1-T15, 2.1-T23

### 2.1-T25 — Create infra/docker-compose.yml for local dev: Postgres 16, MongoDB, Redis 7, Kafka, Vault, MinIO  _(40 min)_
**Context:** Developers run docker-compose up -d to start all local dependencies. Services: postgres:16 on port 5433 (avoids clash with local 5432), mongo:7 on 27017, redis:7 on 6379, confluentinc/cp-kafka:7.6 in KRaft mode on 9092, vault:1.16 in dev mode on 8200, minio/minio on 9000 (console 9001). All credentials sourced from .env file. Each service has a healthcheck. Named volumes for persistence.
**Steps:** Create infra/docker-compose.yml with all 6 services and depends_on healthcheck conditions; postgres: image postgres:16, ports 5433:5432, healthcheck pg_isready -U ${POSTGRES_USER}; mongo: image mongo:7, ports 27017:27017, healthcheck mongosh --eval db.adminCommand(ping); redis: image redis:7, ports 6379:6379, healthcheck redis-cli ping; kafka: image confluentinc/cp-kafka:7.6, KRaft mode env vars, ports 9092:9092, healthcheck kafka-topics.sh --bootstrap-server localhost:9092 --list; vault: image vault:1.16, VAULT_DEV_ROOT_TOKEN_ID from .env, ports 8200:8200; minio: image minio/minio, command server /data --console-address :9001, ports 9000:9000 and 9001:9001; Create infra/.env.example with placeholder values only (no real credentials)
**Deliverable:** infra/docker-compose.yml and infra/.env.example
**Acceptance / logic checks:**
- docker compose config validates without errors
- Postgres healthcheck reports healthy within 30s (pg_isready)
- Redis healthcheck returns PONG
- All 6 services have named volumes (no anonymous volumes)
- infra/.env.example contains no real credentials (all values are CHANGE_ME placeholders)
**Depends on:** 2.1-T01

### 2.1-T26 — Configure Testcontainers shared base classes in lib-persistence for Postgres 16, Redis 7, and MongoDB  _(35 min)_
**Context:** All service integration tests that need Postgres 16, Redis 7, or MongoDB start a Testcontainers container. lib-persistence provides shared abstract base classes to avoid duplicate config: TestContainersPostgres (static PostgreSQLContainer postgres:16 with @DynamicPropertySource), TestContainersRedis (static GenericContainer redis:7), TestContainersMongo (MongoDBContainer mongo:7). Services extend the relevant base class in their @SpringBootTest or @DataJpaTest. WireMock is configured per-service.
**Steps:** Create libs/lib-persistence/src/testFixtures/java/com/gme/pay/persistence/testutil/TestContainersPostgres.java: @Testcontainers, static PostgreSQLContainer<> POSTGRES = new PostgreSQLContainer<>(postgres:16); @DynamicPropertySource sets spring.datasource.url/username/password; Create TestContainersRedis.java: static GenericContainer<> REDIS = new GenericContainer<>(redis:7).withExposedPorts(6379); @DynamicPropertySource sets spring.data.redis.host/port; Create TestContainersMongo.java: MongoDBContainer for mongo:7 with @DynamicPropertySource for spring.data.mongodb.uri; Add testcontainers-bom:1.19.x to lib-persistence build.gradle testFixtures scope; expose testFixtures to consuming services; Verify a dummy @DataJpaTest in lib-persistence uses TestContainersPostgres and a JPA entity round-trips successfully
**Deliverable:** libs/lib-persistence/src/testFixtures/java/com/gme/pay/persistence/testutil/TestContainersPostgres.java, TestContainersRedis.java, TestContainersMongo.java
**Acceptance / logic checks:**
- TestContainersPostgres starts postgres:16 container (verify POSTGRES.getDockerImageName() contains postgres:16)
- @DynamicPropertySource overrides spring.datasource.url to container JDBC URL
- TestContainersRedis @DynamicPropertySource sets spring.data.redis.host to 127.0.0.1 and port to mapped container port
- Container field is static (shared across all test methods; not restarted per test method)
- A service extending TestContainersPostgres + TestContainersRedis connects to both without additional config
**Depends on:** 2.1-T06

### 2.1-T27 — Author ADR-007: Transaction state machine transitions and UNCERTAIN resolution strategy  _(25 min)_
**Context:** SAD-02 section 5.2 defines 9 states: QUOTED, PENDING_DEBIT, DEBITED, SCHEME_SENT, APPROVED, UNCERTAIN, FAILED, REVERSED, REFUNDED. Only TransactionOrchestrator may transition state. UNCERTAIN resolution: if scheme response not received within SLA, set UNCERTAIN; Settlement Engine resolves within 24h via ZP0012/ZP0022 batch files; unresolved after 24h triggers ops alert and manual exception queue. Prefunding reversal rule: deduction is held during UNCERTAIN; reversed only if reconciliation confirms FAILED.
**Steps:** Create docs/adr/ADR-007-transaction-state-machine.md with standard ADR template; Include Mermaid stateDiagram-v2 block with all 9 states and allowed transitions as per SAD-02 section 5.2; State constraint: TransactionOrchestrator is the only component that writes transactions.state; all other services read-only; Document UNCERTAIN resolution: scheme timeout -> UNCERTAIN -> Settlement Engine resolves via ZP0012/ZP0022 within 24h; unresolved > 24h -> ops alert + manual queue; Document prefunding reversal rule: deduction held during UNCERTAIN; reversed only on reconciliation confirming FAILED (not reversed on APPROVED); Add to ADR index
**Deliverable:** docs/adr/ADR-007-transaction-state-machine.md
**Acceptance / logic checks:**
- Mermaid stateDiagram-v2 is syntactically valid (renderable)
- All 9 state names match exactly the CHECK constraint in V1 migration (T15)
- Both UNCERTAIN -> APPROVED and UNCERTAIN -> FAILED transitions are shown in the diagram
- Prefunding reversal rule explicitly states deduction is held (not reversed) during UNCERTAIN state
- Constraint that only TransactionOrchestrator writes state is explicitly stated
**Depends on:** 2.1-T15, 2.1-T07

### 2.1-T28 — Scaffold services/smart-router: SchemeAdapterRegistry and runtime route resolution  _(45 min)_
**Context:** services/smart-router resolves the correct Scheme Adapter for a payment request. SmartRouterService.resolve(schemeType, countryCode, direction, partnerId) queries config-registry (via RestClient) for an active Rule; if none throws RuleNotFoundException (HTTP 422, errorCode RULE_NOT_FOUND). Returns SchemeHandle containing schemeId (UUID) and the matching SchemeAdapter bean from SchemeAdapterRegistry. SchemeAdapterRegistry is a Map<String, SchemeAdapter> auto-built at startup from all SchemeAdapter Spring beans keyed by getSchemeCode(). Resolving an unregistered schemeType throws SchemeNotSupportedException (HTTP 422, errorCode SCHEME_NOT_SUPPORTED).
**Steps:** Create services/smart-router/build.gradle: lib-errors, lib-events, spring-boot-starter-web; Create SmartRouterApplication.java @SpringBootApplication; Create SchemeAdapterRegistry.java @Component: in @PostConstruct, iterate @Autowired List<SchemeAdapter> and put each into Map<String,SchemeAdapter> keyed by getSchemeCode(); Create SmartRouterService.java @Service: call config-registry REST endpoint to verify active Rule; lookup adapter in registry; return SchemeHandle record (schemeId, adapter); Define SchemeHandle.java record: UUID schemeId, SchemeAdapter adapter; Write unit test: registry with one ZeroPaySchemeAdapter stub; resolve ZEROPAY returns SchemeHandle; resolve UNKNOWN throws SchemeNotSupportedException
**Deliverable:** services/smart-router/src/main/java/com/gme/pay/smartrouter/SchemeAdapterRegistry.java and SmartRouterService.java
**Acceptance / logic checks:**
- Registry auto-populates at startup: one ZeroPaySchemeAdapter bean registered under key ZEROPAY
- resolve with schemeType=UNKNOWN throws SchemeNotSupportedException with errorCode SCHEME_NOT_SUPPORTED
- resolve with schemeType=ZEROPAY and valid Rule returns SchemeHandle with non-null schemeId and adapter
- SchemeAdapterRegistry uses getSchemeCode() as key - no hardcoded ZEROPAY string in registry logic
- Unit test has zero Testcontainers (mock config-registry via WireMock or Mockito)
**Depends on:** 2.1-T17, 2.1-T13, 2.1-T03, 2.1-T22

### 2.1-T29 — Scaffold services/notification: WebhookDispatchService with exponential back-off retry and dead-letter  _(50 min)_
**Context:** services/notification dispatches partner webhooks. Events: payment.pending_debit, payment.approved, payment.failed, payment.reversed. Payload signed with HMAC-SHA256 (webhook secret from config-registry). Retry schedule: 5s, 30s, 2min, 10min, 1h, 6h (max 6 attempts). Partner must return HTTP 200. Dead-letter after retry exhaustion: write to webhook_dead_letters table (id UUID, partner_id UUID, event_type VARCHAR, payload JSONB, last_error TEXT, exhausted_at TIMESTAMPTZ). @SpringRetry with @Retryable / @Recover for the retry/dead-letter pattern.
**Steps:** Create services/notification/build.gradle: spring-boot-starter-web, spring-retry, lib-errors, lib-events; Create NotificationApplication.java @SpringBootApplication @EnableRetry; Create Flyway V1 migration: webhook_dead_letters table with all columns; Create WebhookDispatchService.java @Service: dispatch(WebhookPayload, UUID partnerId) - fetch webhook URL from config-registry, sign with HMAC-SHA256, POST via RestClient; annotate @Retryable(maxAttempts=6, backoff=@Backoff(delay=5000, multiplier=6, maxDelay=21600000)); Create WebhookDeadLetterService.java @RecoverWith: write to webhook_dead_letters on retry exhaustion; WireMock integration test: endpoint returns 503 twice then 200; verify dispatch succeeds on 3rd attempt and no dead-letter row written
**Deliverable:** services/notification/src/main/java/com/gme/pay/notification/WebhookDispatchService.java and V1 migration for webhook_dead_letters
**Acceptance / logic checks:**
- Webhook with HTTP 200 response succeeds on first attempt with no retry
- Webhook always returning 503 exhausts all 6 retries and writes to webhook_dead_letters (WireMock test)
- X-GME-Webhook-Signature HMAC-SHA256 header present on every dispatch attempt
- webhook_dead_letters row contains event_type, partner_id, and last_error after exhaustion
- Service does not retry on HTTP 400 (client error is non-retriable; verify with WireMock stub returning 400)
**Depends on:** 2.1-T03, 2.1-T05, 2.1-T06, 2.1-T25

### 2.1-T30 — Write unit tests for RateEngine: canonical test vectors and all edge cases  _(50 min)_
**Context:** RateEngine (T20) needs exhaustive JUnit 5 unit tests. RateEngineTest.java is a plain unit test (no Spring context, no Testcontainers). Vectors: (a) cross-border KRW payout, costRatePay=1.0 (USD IDENTITY settle-B), costRateColl=1380.0; (b) same-currency KRW short-circuit; (c) marginA+marginB=1.9% cross-border rejected; (d) pool identity holds for 5 parameterised rate combinations; (e) serviceCharge not in USD pool (collectionUsd identical regardless of serviceCharge); (f) BOK derived fields offerRateColl and crossRate computed correctly.
**Steps:** Create services/rate-fx/src/test/java/com/gme/pay/ratefx/engine/RateEngineTest.java; Test (a): targetPayout=50000, costRatePay=1.0, costRateColl=1380.0, marginA=0.015, marginB=0.01, serviceCharge=0; assert sendAmount within 1 KRW of expected, pool identity holds within 0.01 USD; Test (b): targetPayout=10000 KRW, all currencies KRW, serviceCharge=500; assert collectionAmount=10500 exactly, all USD pool fields null; Test (c): marginA=0.010, marginB=0.009 (sum 0.019) with cross-border currencies; assert IllegalArgumentException mentioning combined margin; Test (d): @ParameterizedTest over 5 rate combinations; assert |collectionUsd - collectionMarginUsd - payoutMarginUsd - payoutUsdCost| <= 0.01 in each case; Test (e): same inputs with serviceCharge=0 vs serviceCharge=500; assert collectionUsd is identical in both; Test (f): assert offerRateColl = sendAmount / (collectionUsd - collectionMarginUsd); assert crossRate = targetPayout / sendAmount
**Deliverable:** services/rate-fx/src/test/java/com/gme/pay/ratefx/engine/RateEngineTest.java with at least 10 @Test methods
**Acceptance / logic checks:**
- All tests pass with ./gradlew :services:rate-fx:test
- Test (a) sendAmount is within 1 KRW of expected (rounding tolerance)
- Test (c) throws on marginA+marginB=0.019 cross-border but NOT for same-currency 0% margin
- Test (e) proves serviceCharge does not affect collectionUsd (pool stays clean)
- Test class has zero Spring annotations (no @SpringBootTest, no @ExtendWith(SpringExtension))
**Depends on:** 2.1-T20

### 2.1-T31 — Write integration test for prefunding atomic deduction under concurrency  _(50 min)_
**Context:** PrefundingService.deductBalance (T16) must be concurrency-safe under SELECT FOR UPDATE. Test: spawn 10 concurrent threads each deducting 60 USD from a starting balance of 500 USD. Expected: exactly 8 deductions succeed (8x60=480 <= 500); the 9th would require 540 > 500, so threads 9 and 10 throw InsufficientPrefundingException. Final balance must be exactly 500 - (successCount * 60) with no negative balance ever appearing.
**Steps:** Create services/prefunding/src/test/java/com/gme/pay/prefunding/PrefundingConcurrencyIT.java; Extend TestContainersPostgres from lib-persistence; @SpringBootTest, inject PrefundingService; Setup: insert prefunding_balances row for testPartnerId with balance_usd=500.00; Run 10 concurrent threads via ExecutorService.invokeAll() each calling deductBalance(testPartnerId, new BigDecimal(60)); Count successes vs InsufficientPrefundingException; assert successCount + failCount == 10; Assert final balance == 500.00 - (successCount * 60.00) using BigDecimal.compareTo; assert no prefunding_transactions row has negative balance_after_usd
**Deliverable:** services/prefunding/src/test/java/com/gme/pay/prefunding/PrefundingConcurrencyIT.java
**Acceptance / logic checks:**
- Test completes without deadlock or timeout within 30 seconds
- Final balance equals 500.00 - (successCount * 60.00) exactly (BigDecimal compareTo = 0)
- All 10 futures complete (no thread left hanging)
- No prefunding_transactions row has balance_after_usd < 0
- Test is deterministic: running 3 consecutive times gives the same successCount
**Depends on:** 2.1-T16, 2.1-T26

### 2.1-T32 — Add OpenTelemetry and MDC correlation filter to lib-errors for all services  _(40 min)_
**Context:** All GMEPay+ services must emit structured JSON logs with txn_ref, partner_id, scheme_id, request_id as MDC correlation fields, and expose distributed traces via OpenTelemetry. lib-errors provides CorrelationMdcFilter (OncePerRequestFilter, @Order HIGHEST_PRECEDENCE) that extracts X-Txn-Ref and X-Partner-Id headers and puts them in MDC, clearing on response. The opentelemetry-spring-boot-starter is added to the root build.gradle so all services inherit auto-instrumentation. A config template application-template.yaml documents the OTLP exporter endpoint and log pattern.
**Steps:** Add io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter:2.x to root build.gradle subprojects dependencies block; Create libs/lib-errors/src/main/java/com/gme/pay/errors/CorrelationMdcFilter.java implements OncePerRequestFilter @Order(Ordered.HIGHEST_PRECEDENCE): extracts X-Txn-Ref -> MDC key txn_ref, X-Partner-Id -> MDC key partner_id; clears both in finally block; Register CorrelationMdcFilter as @Bean in GlobalExceptionHandler auto-configuration class; Create docs/config-templates/application-template.yaml with otel.exporter.otlp.endpoint, otel.service.name placeholder, logging.pattern.console including %X{txn_ref} %X{partner_id}; Write unit test for CorrelationMdcFilter: mock request with X-Txn-Ref=TXN123; assert MDC.get(txn_ref)=TXN123 during filter; assert MDC is null after filter exits
**Deliverable:** libs/lib-errors/src/main/java/com/gme/pay/errors/CorrelationMdcFilter.java and docs/config-templates/application-template.yaml
**Acceptance / logic checks:**
- CorrelationMdcFilter clears MDC in finally block (no leakage to subsequent requests)
- MDC keys are exactly txn_ref and partner_id (lowercase underscore, not camelCase)
- opentelemetry-spring-boot-starter appears in ./gradlew :services:rate-fx:dependencies output
- application-template.yaml log pattern includes %X{txn_ref} and %X{partner_id}
- CorrelationMdcFilter is registered at HIGHEST_PRECEDENCE (runs before other filters)
**Depends on:** 2.1-T03, 2.1-T04

### 2.1-T33 — Create docs/architecture-baseline.md: WBS 2.1 Architecture Baseline deliverable  _(30 min)_
**Context:** WBS 2.1 parent deliverable is the Architecture Baseline document. It summarises the Gradle monorepo module layout, the 5 shared libraries and their key classes, the 7 ADRs and their decisions, database schemas per service (which Flyway migrations exist and what tables they create), key interface contracts (SchemeAdapter, EventPublisher, RateEngine.computeQuote), and the docker-compose local dev environment port map.
**Steps:** Create docs/architecture-baseline.md; Section 1: Monorepo layout - table of all 21 Gradle modules with path and one-line purpose; Section 2: Shared libraries - lib-money (Money.java, CurrencyScale.java), lib-errors (ErrorEnvelope, GmePayException, GlobalExceptionHandler), lib-api-contracts (partner-api.yaml, generated DTOs), lib-events (DomainEvent, EventPublisher, 6 event classes), lib-persistence (PersistenceAutoConfiguration, AuditableEntity, Testcontainers base classes); Section 3: ADR index - ADR-001 through ADR-007 with one-line decision summary and status; Section 4: Database schemas per service - list service, migration file(s), and tables created; Section 5: Key interfaces - SchemeAdapter (6 methods), EventPublisher (publish), RateEngine.computeQuote (input/output types); Section 6: Local dev - docker-compose service list with ports
**Deliverable:** docs/architecture-baseline.md
**Acceptance / logic checks:**
- All 21 Gradle module paths are listed (matches settings.gradle from T01)
- All 7 ADRs listed with Status: Accepted
- Each service Flyway migration listed (payment-executor: V1__create_transactions.sql, V2__rate_lock_trigger.sql etc.)
- SchemeAdapter interface shows exactly 6 method signatures matching T17
- RateEngine.computeQuote shows input parameter types and output RateQuoteResult type
- Docker-compose section lists all 6 services with their host ports
**Depends on:** 2.1-T01, 2.1-T07, 2.1-T08, 2.1-T09, 2.1-T10, 2.1-T22, 2.1-T23, 2.1-T29


## WBS 2.2 — Service/module structure & scaffolding
### 2.2-T01 — Create Gradle root project with settings.gradle and parent build.gradle  _(30 min)_
**Context:** GMEPay+ is a Gradle multi-module monorepo. The root settings.gradle must include every service module and shared lib. The parent build.gradle defines Java 21, Spring Boot 3.x BOM, and common dependency management so all subprojects inherit versions consistently.
**Steps:** Create root settings.gradle listing all modules: libs/lib-money, libs/lib-errors, libs/lib-api-contracts, libs/lib-events, libs/lib-persistence, libs/lib-observability, services/qr-service, services/smart-router, services/rate-fx, services/auth-identity, services/payment-executor, services/txn-mgmt, services/prefunding, services/notification-webhook, services/settlement-recon, services/revenue-ledger, services/reporting-compliance, services/merchant-qr, services/scheme-adapter-zeropay, services/config-registry, services/api-gateway, services/ops-bff; Create root build.gradle with plugins block (java, io.spring.dependency-management), Java 21 toolchain via java.toolchain.languageVersion = JavaLanguageVersion.of(21) in allprojects, and Spring Boot BOM import; Add gradle/wrapper/gradle-wrapper.properties pinned to Gradle 8.x; Verify ./gradlew projects lists all 22 modules without error
**Deliverable:** settings.gradle, build.gradle, gradle/wrapper/gradle-wrapper.properties at repo root
**Acceptance / logic checks:**
- ./gradlew projects lists all 22 modules with no resolution errors
- Java toolchain set to JavaLanguageVersion.of(21) in parent build.gradle
- Spring Boot BOM declared once in parent; subprojects do NOT redeclare it
- Wrapper properties pin a specific Gradle 8.x version
- All module paths use forward slashes and match the directory layout

### 2.2-T02 — Scaffold shared lib: lib-money (BigDecimal money/currency types)  _(40 min)_
**Context:** lib-money provides Money and CurrencyScale value types used across all services. Amounts must use BigDecimal (never double or float). Per-currency scale: KRW=0 decimals, USD=2. The module must expose a CurrencyScale registry and a Money.of(BigDecimal amount, String currency) factory that enforces scale on construction using RoundingMode.HALF_UP.
**Steps:** Create libs/lib-money/build.gradle with java-library plugin and Jackson annotations dependency; no Spring Boot main class; Create com.gme.pay.money.CurrencyScale with static scale(String currency) returning int: KRW->0, USD->2, default->2; Create com.gme.pay.money.Money immutable class with BigDecimal amount and String currency; static of() factory enforces scale rounding on construction; Add Money.add(Money) and Money.subtract(Money) that assert same currency and return scaled result; Add JUnit 5 unit tests in src/test: Money.of(1000.7, KRW) rounds to 1001; Money.of(1.005, USD) is 1.01; add with mismatched currencies throws IllegalArgumentException
**Deliverable:** libs/lib-money/src/main/java/com/gme/pay/money/Money.java and CurrencyScale.java; libs/lib-money/src/test/java/com/gme/pay/money/MoneyTest.java
**Acceptance / logic checks:**
- Money.of(BigDecimal.valueOf(1000.7), KRW) returns amount 1001 (scale 0, HALF_UP)
- Money.of(BigDecimal.valueOf(1.005), USD) returns amount 1.01 (scale 2, HALF_UP)
- Money.add with mismatched currencies throws IllegalArgumentException
- No float or double fields in Money or CurrencyScale
- Unit tests pass via ./gradlew :libs:lib-money:test
**Depends on:** 2.2-T01

### 2.2-T03 — Scaffold shared lib: lib-errors (canonical error model)  _(30 min)_
**Context:** lib-errors defines the shared error envelope used by all services in REST responses. Every error response carries: error_code (String), message (String), txn_ref (String nullable), and timestamp (Instant UTC). Required error codes: PARTNER_B_QUOTE_DEVIATION, PARTNER_B_QUOTE_UNAVAILABLE, RATE_QUOTE_EXPIRED, INSUFFICIENT_PREFUNDING, IDEMPOTENCY_CONFLICT, VALIDATION_ERROR, INTERNAL_ERROR.
**Steps:** Create libs/lib-errors/build.gradle with java-library plugin and Jackson annotations dependency only (no Spring); Create com.gme.pay.errors.ErrorResponse record with fields: errorCode (String), message (String), txnRef (String), timestamp (Instant); Create com.gme.pay.errors.ErrorCode enum with all 7 listed codes; Create com.gme.pay.errors.GmePayException extends RuntimeException carrying ErrorCode and optional txnRef; provide getErrorCode() accessor; Add JUnit 5 test: ErrorResponse serialises to JSON with all four fields; GmePayException carries correct ErrorCode
**Deliverable:** libs/lib-errors/src/main/java/com/gme/pay/errors/ErrorResponse.java, ErrorCode.java, GmePayException.java
**Acceptance / logic checks:**
- ErrorResponse serialises to JSON with timestamp as ISO-8601 UTC string
- GmePayException.getErrorCode() returns the injected ErrorCode
- All 7 named error codes present in ErrorCode enum
- No Spring dependencies in lib-errors compile scope
- Unit test passes via ./gradlew :libs:lib-errors:test
**Depends on:** 2.2-T01

### 2.2-T04 — Scaffold shared lib: lib-events (domain event schemas and EventPublisher interface)  _(35 min)_
**Context:** lib-events defines all domain event types as Java records and their JSON serialisation. Phase 1 transport is transactional outbox plus in-process polling; Kafka transport is wired in a later integration phase behind the EventPublisher interface. Events: TransactionStateChangedEvent, PrefundingDeductedEvent, LowBalanceAlertEvent, WebhookDispatchEvent, ConfigChangedEvent.
**Steps:** Create libs/lib-events/build.gradle with java-library and Jackson dependency; Create com.gme.pay.events.DomainEvent base interface with eventId (UUID), occurredAt (Instant), eventType (String); Create concrete event records: TransactionStateChangedEvent(txnRef, partnerId, schemeId, fromState, toState, occurredAt), PrefundingDeductedEvent(partnerId, amountUsd, balanceAfterUsd, occurredAt), LowBalanceAlertEvent(partnerId, currentBalanceUsd, thresholdUsd, occurredAt), WebhookDispatchEvent(partnerId, txnRef, eventType, payload, occurredAt), ConfigChangedEvent(entityType, entityId, actor, previousValueJson, newValueJson, occurredAt); Create com.gme.pay.events.EventPublisher interface with single method void publish(DomainEvent event); Add JUnit 5 test: serialise each event to JSON and assert eventType field is present and occurredAt is ISO-8601
**Deliverable:** libs/lib-events/src/main/java/com/gme/pay/events/DomainEvent.java, EventPublisher.java, and all 5 event record files
**Acceptance / logic checks:**
- All 5 event types implement DomainEvent interface
- EventPublisher has exactly one method publish(DomainEvent)
- Jackson serialisation of TransactionStateChangedEvent includes txnRef, fromState, toState, occurredAt as ISO-8601 UTC
- EventPublisher has zero Kafka imports (transport-agnostic)
- Unit tests pass via ./gradlew :libs:lib-events:test
**Depends on:** 2.2-T01

### 2.2-T05 — Scaffold shared lib: lib-persistence (JPA base entity and Outbox table)  _(40 min)_
**Context:** lib-persistence provides a @MappedSuperclass BaseEntity with UUID id and audit timestamps, plus the OutboxEvent @Entity and OutboxRepository for the transactional Outbox pattern. The Outbox ensures async events are committed atomically with business state in Phase 1 before Kafka is wired. Flyway migration V001 creates the outbox_events table.
**Steps:** Create libs/lib-persistence/build.gradle with java-library, spring-boot-starter-data-jpa, flyway-core dependencies; Create com.gme.pay.persistence.BaseEntity @MappedSuperclass with id UUID @GeneratedValue(UUID), createdAt Instant, updatedAt Instant using @PrePersist and @PreUpdate; Create com.gme.pay.persistence.OutboxEvent @Entity @Table(name=outbox_events) with columns: id UUID PK, eventType VARCHAR NOT NULL, payload TEXT NOT NULL, published BOOLEAN NOT NULL DEFAULT false, createdAt TIMESTAMPTZ NOT NULL; Create com.gme.pay.persistence.OutboxRepository extends JpaRepository<OutboxEvent, UUID> with List<OutboxEvent> findTop50ByPublishedFalseOrderByCreatedAtAsc(); Add libs/lib-persistence/src/main/resources/db/migration/V001__create_outbox_events.sql creating outbox_events with the listed columns and an index on (published, created_at)
**Deliverable:** libs/lib-persistence/src/main/java/com/gme/pay/persistence/BaseEntity.java, OutboxEvent.java, OutboxRepository.java; libs/lib-persistence/src/main/resources/db/migration/V001__create_outbox_events.sql
**Acceptance / logic checks:**
- V001 SQL creates outbox_events with published BOOLEAN NOT NULL DEFAULT false
- Index on (published, created_at) present in V001 SQL
- OutboxEvent extends BaseEntity and has @Entity @Table(name=outbox_events)
- findTop50ByPublishedFalseOrderByCreatedAtAsc() query method present on OutboxRepository
- ./gradlew :libs:lib-persistence:compileJava succeeds
**Depends on:** 2.2-T01, 2.2-T02

### 2.2-T06 — Scaffold shared lib: lib-api-contracts (OpenAPI DTO stubs)  _(25 min)_
**Context:** lib-api-contracts will contain DTOs generated from openapi/partner-api.yaml (OpenAPI generator wired in WBS 2.3). For WBS 2.2 scaffolding, create the module and a hand-written RateQuoteResponse DTO so downstream service modules can compile. A stub openapi/partner-api.yaml is created at repo root to reserve the path.
**Steps:** Create libs/lib-api-contracts/build.gradle with java-library and Jackson annotations; no Spring runtime dependency; Create com.gme.pay.api.dto.RateQuoteResponse record: quoteRef (String), offerRate (BigDecimal), sendAmount (BigDecimal), serviceCharge (BigDecimal), collectionAmount (BigDecimal), collectionUsd (BigDecimal), payoutUsdCost (BigDecimal), validUntil (Instant); Create com.gme.pay.api.dto.ErrorResponseDto matching lib-errors ErrorResponse shape for API boundary serialisation; Create openapi/partner-api.yaml stub at repo root with openapi: 3.0.3, info.title: GMEPay+ Partner API, info.version: 1.0.0, and an empty paths: block
**Deliverable:** libs/lib-api-contracts/src/main/java/com/gme/pay/api/dto/RateQuoteResponse.java, ErrorResponseDto.java; openapi/partner-api.yaml stub
**Acceptance / logic checks:**
- RateQuoteResponse has all 8 fields with correct types (BigDecimal for monetary values, Instant for validUntil)
- ./gradlew :libs:lib-api-contracts:compileJava succeeds
- openapi/partner-api.yaml is valid minimal YAML parseable by a YAML parser
- No Spring or JPA runtime dependency in lib-api-contracts build.gradle
- ErrorResponseDto has errorCode, message, txnRef, timestamp fields matching lib-errors ErrorResponse
**Depends on:** 2.2-T01, 2.2-T02

### 2.2-T07 — Scaffold shared lib: lib-observability (structured logging and MDC filter)  _(35 min)_
**Context:** All services must emit structured JSON logs with correlation fields (txn_ref, partner_id, scheme_id, request_id) via Logback JSON encoder (logstash-logback-encoder), and export OpenTelemetry traces via Micrometer Tracing bridge. A shared MdcFilter extracts X-Request-ID from HTTP headers and puts it into MDC for log correlation.
**Steps:** Create libs/lib-observability/build.gradle with java-library, spring-boot-starter-web (compileOnly), micrometer-tracing-bridge-otel, opentelemetry-exporter-otlp, net.logstash.logback:logstash-logback-encoder:7.4 dependencies; Create com.gme.pay.observability.MdcFilter @Component implements jakarta.servlet.Filter: extracts X-Request-ID header, puts into MDC as request_id, calls chain.doFilter(), then removes from MDC in finally block; Create libs/lib-observability/src/main/resources/logback-spring.xml with a ConsoleAppender using LogstashEncoder for JSON output including customFields placeholder for txn_ref and partner_id; Add JUnit 5 test asserting MdcFilter puts X-Request-ID into MDC and removes it after the chain completes
**Deliverable:** libs/lib-observability/src/main/java/com/gme/pay/observability/MdcFilter.java; libs/lib-observability/src/main/resources/logback-spring.xml
**Acceptance / logic checks:**
- MdcFilter puts X-Request-ID header value into MDC key request_id
- MDC.get(request_id) is null after filter chain completes (cleanup in finally)
- logback-spring.xml uses LogstashEncoder (JSON, not plain text pattern)
- micrometer-tracing-bridge-otel on compile classpath
- ./gradlew :libs:lib-observability:test passes
**Depends on:** 2.2-T01

### 2.2-T08 — Scaffold Spring Boot skeleton: services/config-registry  _(45 min)_
**Context:** config-registry is the single source of truth for Scheme, Partner, and Rule configuration, persisted in PostgreSQL 16 with a Redis hot cache. Three core tables: schemes, partners, rules. Rule columns include m_a NUMERIC(10,6), m_b NUMERIC(10,6), effective_from, effective_to (soft-delete). Redis cache is invalidated on any config change.
**Steps:** Create services/config-registry/build.gradle with spring-boot-starter-web, spring-boot-starter-data-jpa, spring-boot-starter-data-redis, flyway-core, lib-money, lib-errors, lib-persistence, lib-events, lib-observability dependencies; Create com.gme.pay.configregistry.ConfigRegistryApplication @SpringBootApplication main class with server.port=8081 in application.yml; Create services/config-registry/src/main/resources/db/migration/V001__create_config_tables.sql: tables schemes (id UUID PK, scheme_code VARCHAR UNIQUE NOT NULL, display_name VARCHAR, supported_modes VARCHAR, active BOOLEAN NOT NULL DEFAULT true, created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ), partners (id UUID PK, partner_code VARCHAR UNIQUE NOT NULL, partner_type VARCHAR NOT NULL CHECK(partner_type IN (LOCAL,OVERSEAS)), active BOOLEAN NOT NULL DEFAULT true, webhook_url VARCHAR, created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ), rules (id UUID PK, partner_id UUID NOT NULL REFERENCES partners(id), scheme_id UUID NOT NULL REFERENCES schemes(id), direction VARCHAR NOT NULL, m_a NUMERIC(10,6) NOT NULL, m_b NUMERIC(10,6) NOT NULL, service_charge NUMERIC(20,6) NOT NULL DEFAULT 0, service_charge_currency CHAR(3), rate_source_coll VARCHAR NOT NULL, rate_source_pay VARCHAR NOT NULL, rate_quote_ttl_seconds INT NOT NULL DEFAULT 300, active BOOLEAN NOT NULL DEFAULT true, effective_from TIMESTAMPTZ NOT NULL, effective_to TIMESTAMPTZ); Create stub @RestController RegistryController with GET /internal/health returning 200
**Deliverable:** services/config-registry/src/main/java/com/gme/pay/configregistry/ConfigRegistryApplication.java; services/config-registry/src/main/resources/db/migration/V001__create_config_tables.sql; application.yml
**Acceptance / logic checks:**
- ./gradlew :services:config-registry:bootJar succeeds
- V001 SQL: rules.m_a and m_b are NUMERIC(10,6) not FLOAT
- partners.partner_type CHECK constraint allows only LOCAL and OVERSEAS
- rules.effective_to is TIMESTAMPTZ nullable (soft-delete; NULL means currently active)
- Foreign keys rules.partner_id -> partners(id) and rules.scheme_id -> schemes(id) present in V001
**Depends on:** 2.2-T01, 2.2-T02, 2.2-T03, 2.2-T04, 2.2-T05, 2.2-T07

### 2.2-T09 — Scaffold Spring Boot skeleton: services/auth-identity  _(35 min)_
**Context:** auth-identity validates HMAC-SHA256 partner API keys and issues OAuth2/JWT tokens for Operator/Admin users. Partner credentials are stored hashed in PostgreSQL. The hmac_secret is stored encrypted (Vault/KMS field-level encryption); never in plaintext. Skeleton: app wiring, datasource config, Flyway migration for partner_credentials table.
**Steps:** Create services/auth-identity/build.gradle with spring-boot-starter-web, spring-boot-starter-security, spring-boot-starter-data-jpa, spring-boot-starter-oauth2-resource-server, flyway-core, lib-errors, lib-observability dependencies; Create com.gme.pay.authidentity.AuthIdentityApplication @SpringBootApplication main class with server.port=8082 in application.yml; Create services/auth-identity/src/main/resources/db/migration/V001__create_partner_credentials.sql: table partner_credentials (id UUID PK, partner_id UUID NOT NULL, api_key_hash VARCHAR NOT NULL UNIQUE, api_key_prefix VARCHAR(8) NOT NULL, hmac_secret_encrypted VARCHAR NOT NULL, active BOOLEAN NOT NULL DEFAULT true, created_at TIMESTAMPTZ, last_used_at TIMESTAMPTZ); Create stub @RestController HealthController with GET /internal/health
**Deliverable:** services/auth-identity/src/main/java/com/gme/pay/authidentity/AuthIdentityApplication.java; V001__create_partner_credentials.sql; application.yml
**Acceptance / logic checks:**
- ./gradlew :services:auth-identity:bootJar succeeds
- api_key_hash is VARCHAR NOT NULL UNIQUE (stored hashed, never the raw key)
- hmac_secret_encrypted column present (encrypted at rest, never plaintext)
- api_key_prefix VARCHAR(8) present for human-readable identification without secret exposure
- spring-boot-starter-oauth2-resource-server in build.gradle for JWT validation
**Depends on:** 2.2-T01, 2.2-T03, 2.2-T05, 2.2-T07

### 2.2-T10 — Scaffold Spring Boot skeleton: services/rate-fx (Rate and FX Engine)  _(45 min)_
**Context:** rate-fx implements the 5-step RECEIVE-mode USD pool computation. Treasury rates stored as NUMERIC(20,10) in PostgreSQL table treasury_rates (key convention: usd_krw, usd_usd=1). Rate quotes cached in Redis with TTL (default 60s aggregator-bound, 300s otherwise; range 60-1800s). Rate-lock values written to rate_quotes at CommitTransaction as immutable columns.
**Steps:** Create services/rate-fx/build.gradle with spring-boot-starter-web, spring-boot-starter-data-jpa, spring-boot-starter-data-redis, flyway-core, lib-money, lib-errors, lib-events, lib-api-contracts, lib-observability dependencies; Create com.gme.pay.ratefx.RateFxApplication @SpringBootApplication main class with server.port=8083 in application.yml; Create services/rate-fx/src/main/resources/db/migration/V001__create_treasury_rates.sql: table treasury_rates (id UUID PK, rate_key VARCHAR NOT NULL UNIQUE, currency_code CHAR(3) NOT NULL, rate_value NUMERIC(20,10) NOT NULL, effective_at TIMESTAMPTZ NOT NULL, entered_by VARCHAR, source VARCHAR NOT NULL DEFAULT MANUAL, created_at TIMESTAMPTZ); Create services/rate-fx/src/main/resources/db/migration/V002__create_rate_quotes.sql: table rate_quotes (id UUID PK, quote_ref VARCHAR NOT NULL UNIQUE, partner_id UUID, scheme_id UUID, direction VARCHAR, target_payout NUMERIC(20,6), payout_currency CHAR(3), offer_rate NUMERIC(20,10), send_amount NUMERIC(20,6), send_amount_currency CHAR(3), collection_amount NUMERIC(20,6), collection_currency CHAR(3), collection_usd NUMERIC(20,10), payout_usd_cost NUMERIC(20,10), collection_margin_usd NUMERIC(20,10), payout_margin_usd NUMERIC(20,10), service_charge NUMERIC(20,6), offer_rate_coll NUMERIC(20,10), cross_rate NUMERIC(20,10), valid_until TIMESTAMPTZ, committed BOOLEAN NOT NULL DEFAULT false, created_at TIMESTAMPTZ); Create stub @Service com.gme.pay.ratefx.service.RateEngineService with placeholder computeQuote() method signature
**Deliverable:** services/rate-fx/src/main/java/com/gme/pay/ratefx/RateFxApplication.java; V001__create_treasury_rates.sql; V002__create_rate_quotes.sql; application.yml
**Acceptance / logic checks:**
- ./gradlew :services:rate-fx:bootJar succeeds
- treasury_rates.rate_value is NUMERIC(20,10) not FLOAT
- rate_quotes stores all 5 USD pool columns (collection_usd, payout_usd_cost, collection_margin_usd, payout_margin_usd, send_amount) as NUMERIC(20,10)
- rate_quotes.offer_rate_coll and cross_rate present as NUMERIC(20,10) (BOK derived fields)
- committed BOOLEAN NOT NULL DEFAULT false in V002 (set true at rate-lock CommitTransaction)
**Depends on:** 2.2-T01, 2.2-T02, 2.2-T03, 2.2-T04, 2.2-T05, 2.2-T06, 2.2-T07

### 2.2-T11 — Scaffold Spring Boot skeleton: services/qr-service (QR Parser)  _(25 min)_
**Context:** qr-service decodes QR payloads in EMVCo and ZeroPay formats and extracts schemeType, merchantId, payoutCurrency, and payoutAmount (nullable, not always encoded). Stateless; reads scheme format definitions from config-registry via HTTP. No database of its own.
**Steps:** Create services/qr-service/build.gradle with spring-boot-starter-web, lib-errors, lib-api-contracts, lib-observability dependencies (no JPA/Flyway); Create com.gme.pay.qrservice.QrServiceApplication @SpringBootApplication main class with server.port=8084 in application.yml; Create com.gme.pay.qrservice.model.QrParseResult record with: schemeType (String), merchantId (String), payoutCurrency (String), payoutAmount (BigDecimal nullable), rawPayload (String); Create stub @RestController com.gme.pay.qrservice.controller.QrController with POST /internal/qr/parse accepting body {payload: String}, returning QrParseResult stub (all nulls)
**Deliverable:** services/qr-service/src/main/java/com/gme/pay/qrservice/QrServiceApplication.java, QrParseResult.java, QrController.java; application.yml
**Acceptance / logic checks:**
- ./gradlew :services:qr-service:bootJar succeeds
- QrParseResult.payoutAmount is BigDecimal (nullable) not primitive double
- POST /internal/qr/parse endpoint exists at the exact path
- No PostgreSQL or Flyway dependency in build.gradle (stateless parser)
- lib-errors on classpath for structured error responses
**Depends on:** 2.2-T01, 2.2-T03, 2.2-T06, 2.2-T07

### 2.2-T12 — Scaffold Spring Boot skeleton: services/smart-router  _(25 min)_
**Context:** smart-router selects the correct Scheme Adapter at runtime given (schemeType, countryCode, direction, partnerId) by calling config-registry via HTTP (WebClient). Returns a RoutingContext (schemeId, adapterId, adapterEndpoint, direction). Stateless; no own database.
**Steps:** Create services/smart-router/build.gradle with spring-boot-starter-web, spring-boot-starter-webflux (WebClient for config-registry calls), lib-errors, lib-api-contracts, lib-observability dependencies; Create com.gme.pay.smartrouter.SmartRouterApplication @SpringBootApplication main class with server.port=8085; Create com.gme.pay.smartrouter.model.RoutingContext record with: schemeId (String), adapterId (String), adapterEndpoint (String), direction (String), partnerId (String); Create stub @RestController com.gme.pay.smartrouter.controller.RouterController with POST /internal/route accepting JSON body {schemeType, countryCode, direction, partnerId}, returning RoutingContext stub; Add config-registry.base-url placeholder in application.yml
**Deliverable:** services/smart-router/src/main/java/com/gme/pay/smartrouter/SmartRouterApplication.java, RoutingContext.java, RouterController.java; application.yml
**Acceptance / logic checks:**
- ./gradlew :services:smart-router:bootJar succeeds
- No database or Flyway dependency in build.gradle
- RoutingContext has exactly the 5 named fields including adapterEndpoint
- spring-boot-starter-webflux present (WebClient for config-registry calls)
- config-registry.base-url placeholder in application.yml
**Depends on:** 2.2-T01, 2.2-T03, 2.2-T06, 2.2-T07

### 2.2-T13 — Scaffold Spring Boot skeleton: services/payment-executor (Transaction Orchestrator)  _(45 min)_
**Context:** payment-executor owns the transaction state machine: QUOTED -> PENDING_DEBIT -> DEBITED -> SCHEME_SENT -> APPROVED/UNCERTAIN/FAILED -> REVERSED/REFUNDED. PostgreSQL stores transactions with all 5 USD pool columns as NUMERIC. Redis stores idempotency keys (24h TTL). All pool columns are immutable after CommitTransaction (rate_locked_at set).
**Steps:** Create services/payment-executor/build.gradle with spring-boot-starter-web, spring-boot-starter-data-jpa, spring-boot-starter-data-redis, flyway-core, lib-money, lib-errors, lib-events, lib-persistence, lib-api-contracts, lib-observability dependencies; Create com.gme.pay.paymentexecutor.PaymentExecutorApplication @SpringBootApplication @EnableScheduling main class with server.port=8086; Create services/payment-executor/src/main/resources/db/migration/V001__create_transactions.sql: table transactions (id UUID PK, txn_ref VARCHAR NOT NULL UNIQUE, partner_id UUID NOT NULL, scheme_id UUID, direction VARCHAR NOT NULL, quote_ref VARCHAR, status VARCHAR NOT NULL, target_payout NUMERIC(20,6), payout_currency CHAR(3), send_amount NUMERIC(20,6), send_amount_currency CHAR(3), collection_amount NUMERIC(20,6), collection_currency CHAR(3), collection_usd NUMERIC(20,10), payout_usd_cost NUMERIC(20,10), collection_margin_usd NUMERIC(20,10), payout_margin_usd NUMERIC(20,10), service_charge NUMERIC(20,6), offer_rate_coll NUMERIC(20,10), cross_rate NUMERIC(20,10), rate_locked_at TIMESTAMPTZ, scheme_approval_code VARCHAR, idempotency_key VARCHAR UNIQUE, created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ, CONSTRAINT chk_status CHECK(status IN (QUOTED,PENDING_DEBIT,DEBITED,SCHEME_SENT,APPROVED,UNCERTAIN,FAILED,REVERSED,REFUNDED))); Create stub @Service com.gme.pay.paymentexecutor.service.TransactionOrchestratorService with placeholder commitTransaction(String quoteRef, String idempotencyKey)
**Deliverable:** services/payment-executor/src/main/java/com/gme/pay/paymentexecutor/PaymentExecutorApplication.java; V001__create_transactions.sql; application.yml
**Acceptance / logic checks:**
- ./gradlew :services:payment-executor:bootJar succeeds
- CHECK constraint on status includes all 9 states: QUOTED, PENDING_DEBIT, DEBITED, SCHEME_SENT, APPROVED, UNCERTAIN, FAILED, REVERSED, REFUNDED
- All 5 USD pool columns stored as NUMERIC(20,10) not FLOAT
- rate_locked_at TIMESTAMPTZ present (immutable after CommitTransaction)
- idempotency_key VARCHAR UNIQUE present
**Depends on:** 2.2-T01, 2.2-T02, 2.2-T03, 2.2-T04, 2.2-T05, 2.2-T06, 2.2-T07

### 2.2-T14 — Scaffold Spring Boot skeleton: services/txn-mgmt (Transaction Management/History)  _(35 min)_
**Context:** txn-mgmt provides transaction search, detail, and 8-step audit trail APIs. It is the CQRS read side: PostgreSQL for transactional audit trail; MongoDB for complex query projections. Admin-initiated actions (refund, reverse) flow through this service. Server port 8087.
**Steps:** Create services/txn-mgmt/build.gradle with spring-boot-starter-web, spring-boot-starter-data-jpa, spring-boot-starter-data-mongodb, flyway-core, lib-money, lib-errors, lib-api-contracts, lib-observability dependencies; Create com.gme.pay.txnmgmt.TxnMgmtApplication @SpringBootApplication main class with server.port=8087; Create services/txn-mgmt/src/main/resources/db/migration/V001__create_txn_audit_trail.sql: table txn_audit_trail (id UUID PK, txn_ref VARCHAR NOT NULL, event_step INT NOT NULL CHECK(event_step BETWEEN 1 AND 8), event_type VARCHAR NOT NULL, actor VARCHAR, details JSONB, occurred_at TIMESTAMPTZ NOT NULL); CREATE INDEX idx_txn_audit_trail_txn_ref ON txn_audit_trail(txn_ref); Create stub @RestController com.gme.pay.txnmgmt.controller.TransactionController with GET /internal/transactions/{txnRef} returning 200 placeholder; Add spring.data.mongodb.uri placeholder in application.yml
**Deliverable:** services/txn-mgmt/src/main/java/com/gme/pay/txnmgmt/TxnMgmtApplication.java; V001__create_txn_audit_trail.sql; application.yml
**Acceptance / logic checks:**
- ./gradlew :services:txn-mgmt:bootJar succeeds
- txn_audit_trail.event_step INT with CHECK(event_step BETWEEN 1 AND 8) present (8-step trail)
- Index on txn_ref column present in V001 SQL
- spring.data.mongodb.uri in application.yml (CQRS projection reads)
- Both spring-boot-starter-data-jpa and spring-boot-starter-data-mongodb in build.gradle
**Depends on:** 2.2-T01, 2.2-T03, 2.2-T05, 2.2-T06, 2.2-T07

### 2.2-T15 — Scaffold Spring Boot skeleton: services/prefunding (Prefunding/Balance)  _(40 min)_
**Context:** prefunding manages OVERSEAS partner USD prepaid balances. AD-06: deduction uses SELECT FOR UPDATE to prevent concurrent over-draws. LOW balance alert fires (via outbox EventPublisher) when balance_usd < low_balance_threshold_usd (default USD 10,000). prefunding_ledger records every debit and credit for audit. PostgreSQL only.
**Steps:** Create services/prefunding/build.gradle with spring-boot-starter-web, spring-boot-starter-data-jpa, flyway-core, lib-money, lib-errors, lib-events, lib-persistence, lib-observability dependencies; Create com.gme.pay.prefunding.PrefundingApplication @SpringBootApplication main class with server.port=8088; Create services/prefunding/src/main/resources/db/migration/V001__create_prefunding_tables.sql: table prefunding_accounts (id UUID PK, partner_id UUID NOT NULL UNIQUE, balance_usd NUMERIC(20,6) NOT NULL DEFAULT 0, low_balance_threshold_usd NUMERIC(20,6) NOT NULL DEFAULT 10000, currency CHAR(3) NOT NULL DEFAULT USD, version BIGINT NOT NULL DEFAULT 0, updated_at TIMESTAMPTZ); table prefunding_ledger (id UUID PK, partner_id UUID NOT NULL, txn_ref VARCHAR, event_type VARCHAR NOT NULL, amount_usd NUMERIC(20,6) NOT NULL, balance_after_usd NUMERIC(20,6) NOT NULL, occurred_at TIMESTAMPTZ NOT NULL); Create stub @Service com.gme.pay.prefunding.service.PrefundingService with placeholder @Transactional deductBalance(UUID partnerId, BigDecimal amountUsd, String txnRef) method
**Deliverable:** services/prefunding/src/main/java/com/gme/pay/prefunding/PrefundingApplication.java; V001__create_prefunding_tables.sql; application.yml
**Acceptance / logic checks:**
- ./gradlew :services:prefunding:bootJar succeeds
- prefunding_accounts.balance_usd is NUMERIC(20,6) not FLOAT
- low_balance_threshold_usd DEFAULT 10000 present (matches spec USD 10,000 alert threshold)
- version BIGINT NOT NULL DEFAULT 0 present (optimistic lock support for SELECT FOR UPDATE pattern)
- deductBalance stub is annotated @Transactional
**Depends on:** 2.2-T01, 2.2-T02, 2.2-T03, 2.2-T04, 2.2-T05, 2.2-T07

### 2.2-T16 — Scaffold Spring Boot skeleton: services/notification-webhook  _(35 min)_
**Context:** notification-webhook dispatches payment.pending_debit, payment.approved, payment.failed, payment.reversed webhooks to partner URLs with HMAC-SHA256 signature. Retry policy: exponential back-off at 5s, 30s, 2min, 10min, 1h, 6h (max 6 attempts). Dead-letter after final retry. Delivery log in PostgreSQL. Consumes WebhookDispatchEvent from outbox.
**Steps:** Create services/notification-webhook/build.gradle with spring-boot-starter-web, spring-boot-starter-data-jpa, spring-boot-starter-webflux (WebClient for outbound webhook calls), flyway-core, lib-errors, lib-events, lib-persistence, lib-observability dependencies; Create com.gme.pay.notification.NotificationWebhookApplication @SpringBootApplication @EnableScheduling main class with server.port=8089; Create services/notification-webhook/src/main/resources/db/migration/V001__create_webhook_delivery_log.sql: table webhook_delivery_log (id UUID PK, txn_ref VARCHAR, partner_id UUID NOT NULL, event_type VARCHAR NOT NULL, payload JSONB, attempt_count INT NOT NULL DEFAULT 0, last_attempt_at TIMESTAMPTZ, next_retry_at TIMESTAMPTZ, status VARCHAR NOT NULL, created_at TIMESTAMPTZ, CONSTRAINT chk_status CHECK(status IN (PENDING,DELIVERED,FAILED,DEAD_LETTER))); Create stub @Service com.gme.pay.notification.service.WebhookDispatchService with placeholder dispatch(String partnerId, String txnRef, String eventType, String payload) method
**Deliverable:** services/notification-webhook/src/main/java/com/gme/pay/notification/NotificationWebhookApplication.java; V001__create_webhook_delivery_log.sql; application.yml
**Acceptance / logic checks:**
- ./gradlew :services:notification-webhook:bootJar succeeds
- webhook_delivery_log.status CHECK includes DEAD_LETTER as a valid state
- attempt_count INT NOT NULL DEFAULT 0 present (max 6 per spec)
- next_retry_at TIMESTAMPTZ present (scheduler reads this to determine when to retry)
- spring-boot-starter-webflux present (WebClient for outbound HTTPS webhook calls)
**Depends on:** 2.2-T01, 2.2-T03, 2.2-T04, 2.2-T05, 2.2-T07

### 2.2-T17 — Scaffold Spring Boot skeleton: services/settlement-recon (Settlement and Reconciliation)  _(45 min)_
**Context:** settlement-recon generates ZP00xx batch files on cron schedule (KST windows: 02:00, 05:00, 14:00, 22:00 = UTC 17:00 prior, 20:00 prior, 05:00, 13:00) and processes inbound result files (ZP0012, ZP0022, ZP0062, ZP0064). Batch jobs must be idempotent: UNIQUE constraint on (batch_type, batch_date). Files archived to S3/MinIO. PostgreSQL + Object Storage.
**Steps:** Create services/settlement-recon/build.gradle with spring-boot-starter-web, spring-boot-starter-data-jpa, spring-boot-starter-quartz, flyway-core, software.amazon.awssdk:s3, lib-errors, lib-events, lib-persistence, lib-observability dependencies; Create com.gme.pay.settlement.SettlementReconApplication @SpringBootApplication main class with server.port=8090; Create services/settlement-recon/src/main/resources/application.yml with cron properties: settlement.cron.zp0011=0 0 17 * * ? (02:00 KST), settlement.cron.zp0061=0 0 20 * * ? (05:00 KST), settlement.cron.zp0063=0 0 5 * * ? (14:00 KST), settlement.cron.zp0065=0 0 13 * * ? (22:00 KST); Create services/settlement-recon/src/main/resources/db/migration/V001__create_settlement_tables.sql: table settlement_batches (id UUID PK, batch_type VARCHAR NOT NULL, batch_date DATE NOT NULL, status VARCHAR NOT NULL, file_key VARCHAR, record_count INT, total_amount NUMERIC(20,6), created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ, UNIQUE(batch_type, batch_date), CONSTRAINT chk_status CHECK(status IN (PENDING,GENERATED,TRANSMITTED,CONFIRMED,FAILED))); Create stub @Service com.gme.pay.settlement.service.SettlementBatchService with placeholder generateBatch(String batchType, LocalDate batchDate)
**Deliverable:** services/settlement-recon/src/main/java/com/gme/pay/settlement/SettlementReconApplication.java; V001__create_settlement_tables.sql; application.yml
**Acceptance / logic checks:**
- ./gradlew :services:settlement-recon:bootJar succeeds
- UNIQUE(batch_type, batch_date) in V001 ensures idempotent batch generation
- settlement_batches.status CHECK includes all 5 states including CONFIRMED
- Cron expressions in application.yml represent the correct UTC equivalents of KST batch windows
- software.amazon.awssdk:s3 in build.gradle for Object Storage file archiving
**Depends on:** 2.2-T01, 2.2-T03, 2.2-T04, 2.2-T05, 2.2-T07

### 2.2-T18 — Scaffold Spring Boot skeleton: services/scheme-adapter-zeropay  _(45 min)_
**Context:** scheme-adapter-zeropay is the Anti-Corruption Layer (ACL) for ZeroPay/KFTC. It implements SchemeAdapter interface: CPM relay via KFTC HTTPS REST, batch file SFTP exchange (all ZP00xx types), merchant/QR sync ingestion into MongoDB local store (AD-12: payment-time lookups query local DB, not ZeroPay live API).
**Steps:** Create services/scheme-adapter-zeropay/build.gradle with spring-boot-starter-web, spring-boot-starter-data-mongodb, spring-boot-starter-webflux, lib-errors, lib-events, lib-observability dependencies; add com.github.mwiede:jsch:0.2.17 for SFTP client; Create com.gme.pay.zeropay.ZeroPayAdapterApplication @SpringBootApplication main class with server.port=8091; Create com.gme.pay.zeropay.adapter.SchemeAdapter interface in services/scheme-adapter-zeropay with methods: CpmAuthResponse authoriseCpm(CpmAuthRequest), MpmSubmitResponse submitMpm(MpmSubmitRequest), void generateBatchFiles(String batchType, LocalDate date), List parseBatchFile(String batchType, byte[] content), void processMerchantSync(byte[] fileContent), Map healthCheck(); Create @Service com.gme.pay.zeropay.adapter.ZeroPayAdapterImpl implements SchemeAdapter with all methods throwing UnsupportedOperationException (stubs for WBS 4.x); Create application.yml with spring.data.mongodb.uri placeholder, zeropay.sftp.host, zeropay.sftp.port=22, zeropay.sftp.username placeholders (no credentials)
**Deliverable:** services/scheme-adapter-zeropay/src/main/java/com/gme/pay/zeropay/ZeroPayAdapterApplication.java, SchemeAdapter.java, ZeroPayAdapterImpl.java; application.yml
**Acceptance / logic checks:**
- ./gradlew :services:scheme-adapter-zeropay:bootJar succeeds
- SchemeAdapter interface has exactly 6 methods as listed
- ZeroPayAdapterImpl implements SchemeAdapter (no ZeroPay-specific base class on the interface side)
- spring-boot-starter-data-mongodb present (AD-12: merchant/QR in local MongoDB)
- SFTP library (jsch) present in build.gradle
**Depends on:** 2.2-T01, 2.2-T03, 2.2-T04, 2.2-T07

### 2.2-T19 — Scaffold Spring Boot skeleton: services/merchant-qr (Merchant and QR Data)  _(30 min)_
**Context:** merchant-qr stores merchant and QR data in MongoDB, synced daily from ZeroPay SFTP files ZP0041, ZP0043, ZP0045, ZP0047, ZP0051, ZP0053, ZP0055. Payment-time lookups query this local store (AD-12). Exposes GET /internal/merchants/{merchantId} for the Transaction Orchestrator. No PostgreSQL.
**Steps:** Create services/merchant-qr/build.gradle with spring-boot-starter-web, spring-boot-starter-data-mongodb, lib-errors, lib-events, lib-observability dependencies; Create com.gme.pay.merchantqr.MerchantQrApplication @SpringBootApplication main class with server.port=8092; Create @Document com.gme.pay.merchantqr.document.MerchantDocument: merchantId (String @Id), merchantName, merchantCategoryCode, schemeId, status, qrCodes (List<String>), franchiseCode, syncedAt (Instant), lastSyncFile (String); Create stub @RestController com.gme.pay.merchantqr.controller.MerchantLookupController with GET /internal/merchants/{merchantId} returning Optional<MerchantDocument> (empty optional placeholder); Add spring.data.mongodb.uri placeholder in application.yml
**Deliverable:** services/merchant-qr/src/main/java/com/gme/pay/merchantqr/MerchantQrApplication.java, MerchantDocument.java, MerchantLookupController.java; application.yml
**Acceptance / logic checks:**
- ./gradlew :services:merchant-qr:bootJar succeeds
- MerchantDocument annotated @Document (Spring Data MongoDB)
- syncedAt Instant field present (tracks last SFTP sync timestamp)
- No PostgreSQL or Flyway dependency (MongoDB only)
- GET /internal/merchants/{merchantId} endpoint present at exact path
**Depends on:** 2.2-T01, 2.2-T03, 2.2-T04, 2.2-T07

### 2.2-T20 — Scaffold Spring Boot skeleton: services/revenue-ledger  _(35 min)_
**Context:** revenue-ledger records GME revenue attribution per committed transaction: collection_margin_usd (m_a leg) and payout_margin_usd (m_b leg), plus service_charge revenue. revenue_entries rows are immutable (written at CommitTransaction rate-lock). Fee-share defaults 70% GME / 30% scheme per spec. PostgreSQL only.
**Steps:** Create services/revenue-ledger/build.gradle with spring-boot-starter-web, spring-boot-starter-data-jpa, flyway-core, lib-money, lib-errors, lib-events, lib-persistence, lib-observability dependencies; Create com.gme.pay.revenueledger.RevenueLedgerApplication @SpringBootApplication main class with server.port=8093; Create services/revenue-ledger/src/main/resources/db/migration/V001__create_revenue_entries.sql: table revenue_entries (id UUID PK, txn_ref VARCHAR NOT NULL UNIQUE, partner_id UUID NOT NULL, scheme_id UUID, direction VARCHAR, collection_margin_usd NUMERIC(20,10) NOT NULL, payout_margin_usd NUMERIC(20,10) NOT NULL, service_charge NUMERIC(20,6) NOT NULL, service_charge_currency CHAR(3) NOT NULL, fee_share_gme_pct NUMERIC(6,4) NOT NULL DEFAULT 0.7000, fee_share_scheme_pct NUMERIC(6,4) NOT NULL DEFAULT 0.3000, settled_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL); Create stub @Service com.gme.pay.revenueledger.service.RevenueEntryService with placeholder @Transactional recordRevenue(String txnRef, ...) method
**Deliverable:** services/revenue-ledger/src/main/java/com/gme/pay/revenueledger/RevenueLedgerApplication.java; V001__create_revenue_entries.sql; application.yml
**Acceptance / logic checks:**
- ./gradlew :services:revenue-ledger:bootJar succeeds
- txn_ref UNIQUE ensures one revenue row per committed transaction
- fee_share_gme_pct DEFAULT 0.7000 and fee_share_scheme_pct DEFAULT 0.3000 match 70:30 spec
- collection_margin_usd and payout_margin_usd are NUMERIC(20,10) not FLOAT
- service_charge_currency CHAR(3) present (service charge denominated in Settle-A currency per spec AD-10)
**Depends on:** 2.2-T01, 2.2-T02, 2.2-T03, 2.2-T04, 2.2-T05, 2.2-T07

### 2.2-T21 — Scaffold Spring Boot skeleton: services/reporting-compliance (Reporting and BOK)  _(30 min)_
**Context:** reporting-compliance generates BOK FX1014 and FX1015 regulatory reports from locked transaction values, and partner/period revenue summaries. Reads PostgreSQL (transactions and revenue_entries tables via JDBC or JPA). Phase 1: manual-trigger via POST. Output files archived to S3/MinIO. Server port 8094.
**Steps:** Create services/reporting-compliance/build.gradle with spring-boot-starter-web, spring-boot-starter-data-jpa, flyway-core, software.amazon.awssdk:s3, lib-money, lib-errors, lib-observability dependencies; Create com.gme.pay.reporting.ReportingComplianceApplication @SpringBootApplication main class with server.port=8094; Create services/reporting-compliance/src/main/resources/db/migration/V001__create_report_runs.sql: table report_runs (id UUID PK, report_type VARCHAR NOT NULL, period_start DATE NOT NULL, period_end DATE NOT NULL, status VARCHAR NOT NULL, output_file_key VARCHAR, record_count INT, generated_by VARCHAR, created_at TIMESTAMPTZ, completed_at TIMESTAMPTZ, CONSTRAINT chk_status CHECK(status IN (PENDING,RUNNING,COMPLETED,FAILED))); Create stub @RestController com.gme.pay.reporting.controller.ReportController with POST /internal/reports/bok/fx1015 and POST /internal/reports/bok/fx1014, both returning 202 Accepted
**Deliverable:** services/reporting-compliance/src/main/java/com/gme/pay/reporting/ReportingComplianceApplication.java; V001__create_report_runs.sql; application.yml
**Acceptance / logic checks:**
- ./gradlew :services:reporting-compliance:bootJar succeeds
- report_runs.report_type present (distinguishes FX1014, FX1015, REVENUE_SUMMARY)
- Both POST /internal/reports/bok/fx1015 and /fx1014 endpoints exist returning 202
- output_file_key column present for S3/MinIO archive key reference
- software.amazon.awssdk:s3 in build.gradle
**Depends on:** 2.2-T01, 2.2-T03, 2.2-T05, 2.2-T07

### 2.2-T22 — Scaffold Spring Boot skeleton: services/ops-bff (Ops/Partner BFF)  _(35 min)_
**Context:** ops-bff is the Backend-for-Frontend aggregation layer for the React/Next.js Admin and Partner Portals. It aggregates calls to config-registry, txn-mgmt, prefunding, and reporting-compliance. OAuth2/JWT for Ops users. No own database; uses WebClient for downstream service calls. Server port 8095.
**Steps:** Create services/ops-bff/build.gradle with spring-boot-starter-web, spring-boot-starter-oauth2-resource-server, spring-boot-starter-webflux, lib-errors, lib-api-contracts, lib-observability dependencies; Create com.gme.pay.opsbff.OpsBffApplication @SpringBootApplication main class with server.port=8095; Create @Configuration com.gme.pay.opsbff.config.WebClientConfig providing @Bean WebClient instances for config-registry, txn-mgmt, prefunding, reporting (URLs from application.yml properties bff.services.config-registry-url etc.); Create stub @RestController com.gme.pay.opsbff.controller.DashboardController with GET /bff/v1/dashboard returning 200 placeholder; Add bff.services.config-registry-url, bff.services.txn-mgmt-url, bff.services.prefunding-url, bff.services.reporting-url placeholders in application.yml
**Deliverable:** services/ops-bff/src/main/java/com/gme/pay/opsbff/OpsBffApplication.java, WebClientConfig.java, DashboardController.java; application.yml
**Acceptance / logic checks:**
- ./gradlew :services:ops-bff:bootJar succeeds
- spring-boot-starter-oauth2-resource-server in build.gradle (JWT/Ops auth)
- WebClientConfig declares 4 named @Bean WebClient instances
- No Flyway or PostgreSQL dependency (BFF owns no database)
- All 4 downstream URL placeholder properties in application.yml
**Depends on:** 2.2-T01, 2.2-T03, 2.2-T06, 2.2-T07

### 2.2-T23 — Scaffold Spring Cloud Gateway module with route definitions and HMAC filter stub  _(40 min)_
**Context:** The API Gateway (Spring Cloud Gateway) handles TLS termination, per-partner rate limiting, idempotency-key enforcement, and routing to all backend services. Partner API versioned at /v1/. A stub HmacSignatureFilter (GatewayFilter) logs the request and delegates signature validation to auth-identity in WBS 3.x.
**Steps:** Create services/api-gateway/build.gradle with spring-cloud-starter-gateway, spring-boot-starter-actuator, spring-boot-starter-data-redis (rate limiter), lib-errors, lib-observability dependencies; import Spring Cloud BOM aligned with Spring Boot 3.x; Create com.gme.pay.gateway.ApiGatewayApplication @SpringBootApplication main class with server.port=8080; Create src/main/resources/application.yml with spring.cloud.gateway.routes: /v1/rates/** -> http://rate-fx:8083, /v1/payments/** -> http://payment-executor:8086, /v1/payments/cpm/** -> http://payment-executor:8086, /admin/** -> http://ops-bff:8095, /internal/auth/** -> http://auth-identity:8082; Create @Component com.gme.pay.gateway.filter.HmacSignatureFilter implements GatewayFilter: logs X-Partner-ID and X-Timestamp headers, calls chain.filter(exchange) (full validation deferred); Add spring.cloud.gateway.default-filters: [RequestRateLimiter with redis-rate-limiter.replenish-rate=100 placeholder] in application.yml
**Deliverable:** services/api-gateway/src/main/java/com/gme/pay/gateway/ApiGatewayApplication.java, HmacSignatureFilter.java; src/main/resources/application.yml with routes
**Acceptance / logic checks:**
- ./gradlew :services:api-gateway:bootJar succeeds
- application.yml defines routes for /v1/rates/**, /v1/payments/**, /admin/**, /internal/auth/**
- HmacSignatureFilter implements GatewayFilter and is @Component
- Spring Cloud BOM imported (not manually versioned)
- spring-boot-starter-data-redis present for RequestRateLimiter backend
**Depends on:** 2.2-T01, 2.2-T03, 2.2-T07

### 2.2-T24 — Create docker-compose.yml for local dev infrastructure  _(40 min)_
**Context:** Local dev needs Postgres 16, MongoDB 7, Redis 7, MinIO (S3-compatible), and HashiCorp Vault dev server. Kafka is defined as an optional profile (Phase 1 uses outbox only). Each service's application.yml placeholder URLs must match the docker-compose service names and ports.
**Steps:** Create docker-compose.yml at repo root with services: postgres (postgres:16-alpine, port 5432, env POSTGRES_DB=gmepayplus), mongo (mongo:7, port 27017), redis (redis:7-alpine, port 6379), minio (minio/minio:latest, ports 9000 and 9001, command server /data --console-address :9001), vault (hashicorp/vault:1.17, port 8200, env VAULT_DEV_ROOT_TOKEN_ID=dev-root-token, cap_add: [IPC_LOCK]); Add a kafka block (confluentinc/cp-kafka:7.6.0 with zookeeper) using profiles: [kafka] so it is NOT started by default; Create .env.example at repo root with placeholder values: POSTGRES_USER, POSTGRES_PASSWORD, MINIO_ROOT_USER, MINIO_ROOT_PASSWORD, VAULT_DEV_ROOT_TOKEN_ID; Verify docker-compose config parses without error
**Deliverable:** docker-compose.yml and .env.example at repo root
**Acceptance / logic checks:**
- docker-compose config passes without YAML error
- postgres service uses image postgres:16-alpine mapped to port 5432
- kafka block has profiles: [kafka] so docker-compose up without --profile kafka does NOT start Kafka
- minio service exposes port 9001 for web console
- vault service has VAULT_DEV_ROOT_TOKEN_ID env var and cap_add: [IPC_LOCK]
**Depends on:** 2.2-T01

### 2.2-T25 — Wire Flyway migrations per-service and verify with Testcontainers (config-registry)  _(45 min)_
**Context:** Each Spring Boot service owns its own Flyway migration set under src/main/resources/db/migration/. The shared V001__create_outbox_events.sql from lib-persistence must also be applied per service. Verify with a Testcontainers integration test in services/config-registry that all migrations apply cleanly against a real Postgres 16 container.
**Steps:** Add to services/config-registry/build.gradle testImplementation: org.testcontainers:testcontainers-bom, org.testcontainers:postgresql, and spring-boot-starter-test; Create test class com.gme.pay.configregistry.FlywayMigrationTest annotated @SpringBootTest @Testcontainers; Declare @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(postgres:16-alpine); Annotate with @DynamicPropertySource to set spring.datasource.url/username/password from container properties; Assert tables exist after migration: query information_schema.tables for schemes, partners, rules, outbox_events; Run ./gradlew :services:config-registry:test to confirm green
**Deliverable:** services/config-registry/src/test/java/com/gme/pay/configregistry/FlywayMigrationTest.java
**Acceptance / logic checks:**
- Test uses Postgres 16 Testcontainer (not H2 or embedded DB)
- @DynamicPropertySource sets datasource from container (no hardcoded localhost)
- Test asserts tables schemes, partners, rules, outbox_events all exist post-migration
- ./gradlew :services:config-registry:test passes green
- No H2 dependency in services/config-registry/build.gradle
**Depends on:** 2.2-T08, 2.2-T05

### 2.2-T26 — Add transactional Outbox poller to services/payment-executor  _(40 min)_
**Context:** The transactional Outbox pattern: a @Scheduled poller queries outbox_events where published=false (up to 50 rows), calls EventPublisher.publish() for each, then marks published=true — all in a single @Transactional boundary. An InProcessEventPublisher logs the event (Phase 1 stub; Kafka impl added in integration phase without changing poller code).
**Steps:** Add @EnableScheduling to PaymentExecutorApplication; Create com.gme.pay.paymentexecutor.outbox.InProcessEventPublisher @Service implements EventPublisher: publish() logs event type and eventId at INFO level; Create com.gme.pay.paymentexecutor.outbox.OutboxPoller @Component: inject OutboxRepository and EventPublisher; @Scheduled(fixedDelay=5000) method pollAndPublish() annotated @Transactional; In pollAndPublish(): call outboxRepository.findTop50ByPublishedFalseOrderByCreatedAtAsc(), for each call eventPublisher.publish(), then set event.setPublished(true) and save; Set spring.scheduling.enabled=true in application.yml
**Deliverable:** services/payment-executor/src/main/java/com/gme/pay/paymentexecutor/outbox/OutboxPoller.java; InProcessEventPublisher.java
**Acceptance / logic checks:**
- OutboxPoller.pollAndPublish() annotated @Scheduled(fixedDelay=5000) (5-second poll)
- pollAndPublish() annotated @Transactional (publish + mark-published is atomic)
- Batch size capped at 50 rows per poll cycle
- InProcessEventPublisher implements EventPublisher from lib-events with zero Kafka imports
- ./gradlew :services:payment-executor:compileJava succeeds
**Depends on:** 2.2-T13, 2.2-T05, 2.2-T04

### 2.2-T27 — Unit test: Outbox poller atomicity on partial publish failure  _(35 min)_
**Context:** If EventPublisher.publish() throws on the 3rd event in a batch of 5, the @Transactional boundary must roll back all published=true updates so all 5 events remain unpublished and are retried on the next poll cycle. This guards against partial delivery causing silent data loss.
**Steps:** Create services/payment-executor/src/test/java/com/gme/pay/paymentexecutor/outbox/OutboxPollerAtomicityTest.java; Use @ExtendWith(MockitoExtension.class); inject mock OutboxRepository and a mock EventPublisher that throws RuntimeException on the 3rd invocation; In test fail_on_third_publish_rolls_back_all: seed 5 unpublished OutboxEvent stubs from repository, call pollAndPublish(), expect RuntimeException, then assert no outbox row had setPublished(true) persisted; In test all_succeed_marks_all_published: mock EventPublisher succeeds for all 5; assert setPublished(true) called 5 times and outboxRepository.saveAll() called once; Assert: no Kafka class imported anywhere in OutboxPoller or InProcessEventPublisher
**Deliverable:** services/payment-executor/src/test/java/com/gme/pay/paymentexecutor/outbox/OutboxPollerAtomicityTest.java
**Acceptance / logic checks:**
- fail_on_third_publish_rolls_back_all: after RuntimeException, verify zero calls to outboxRepository.saveAll() with published=true rows
- all_succeed_marks_all_published: verify outboxRepository.saveAll() called with 5 rows all published=true
- Mock EventPublisher used, not real InProcessEventPublisher
- No Testcontainers needed (pure unit test with mocks)
- ./gradlew :services:payment-executor:test passes
**Depends on:** 2.2-T26

### 2.2-T28 — Scaffold Helm chart stubs for all 16 services  _(45 min)_
**Context:** GitOps via Argo CD requires a Helm chart per service. Each chart must parameterise image.repository, image.tag, replicaCount, and service.port. api-gateway and payment-executor require replicaCount: 2 minimum (HA). A library base chart provides the shared deployment/service templates.
**Steps:** Create helm/base/ Chart.yaml (apiVersion v2, type: library) with shared templates/deployment.yaml (uses .Values.image.repository, .Values.image.tag, .Values.replicaCount, .Values.service.port) and templates/service.yaml; Create helm/<service>/ directories for all 16 services: api-gateway (port 8080, replicas 2), config-registry (8081), auth-identity (8082), rate-fx (8083), qr-service (8084), smart-router (8085), payment-executor (8086, replicas 2), txn-mgmt (8087), prefunding (8088), notification-webhook (8089), settlement-recon (8090), scheme-adapter-zeropay (8091), merchant-qr (8092), revenue-ledger (8093), reporting-compliance (8094), ops-bff (8095); each with Chart.yaml and values.yaml; In each values.yaml set replicaCount: 1 (or 2 for api-gateway and payment-executor), image.repository: ghcr.io/gme/<service>, image.tag: latest; Create helm/argocd-apps/api-gateway.yaml as a stub Argo CD Application CRD pointing to helm/api-gateway/
**Deliverable:** helm/ directory with base library chart and 16 service chart stubs; helm/argocd-apps/api-gateway.yaml
**Acceptance / logic checks:**
- api-gateway values.yaml has replicaCount: 2
- payment-executor values.yaml has replicaCount: 2
- helm/base/Chart.yaml has type: library
- helm/argocd-apps/api-gateway.yaml contains kind: Application (Argo CD CRD kind)
- All 16 service charts have a values.yaml with the correct service.port matching the assigned port
**Depends on:** 2.2-T01


## WBS 2.4 — Cross-cutting libs: money, currency, errors
### 2.4-T01 — Create lib-money Gradle module skeleton (Java 21, Spring Boot 3.x BOM)  _(20 min)_
**Context:** GMEPay+ is a Gradle multi-module monorepo (root settings.gradle, one module per service/lib). WBS 2.4 delivers cross-cutting libs: lib-money and lib-errors. lib-money will hold BigDecimal money types, ISO-4217 currency scale, and rounding helpers. This ticket bootstraps the empty module so subsequent tickets have a compile target. Package root: com.gme.pay.money.
**Steps:** Add 'libs/lib-money' entry to root settings.gradle; Create libs/lib-money/build.gradle: Java 21 toolchain, Spring Boot 3.x BOM dependencyManagement, no spring-boot-plugin (plain library jar), JUnit 5 test dependency; Add libs/lib-money/src/main/java/com/gme/pay/money/package-info.java; Run ./gradlew :libs:lib-money:compileJava and confirm exit 0
**Deliverable:** libs/lib-money/build.gradle and libs/lib-money/src/main/java/com/gme/pay/money/package-info.java
**Acceptance / logic checks:**
- ./gradlew :libs:lib-money:compileJava exits 0
- Module appears in ./gradlew projects output
- build.gradle declares no floating-point-friendly JSON libs without explicit exclusion
- No spring-boot-starter-web or spring-boot-starter-data-jpa in lib-money dependencies

### 2.4-T02 — Create lib-errors Gradle module skeleton (Java 21, Spring Boot 3.x BOM)  _(15 min)_
**Context:** lib-errors holds the canonical API-05 §8.1 error envelope used by every GMEPay+ service. Error JSON: {error:{code,message,request_id,details:[{field,issue}]}}. This ticket creates the empty Gradle module under libs/lib-errors so downstream tickets can add exception classes and the @RestControllerAdvice. Package root: com.gme.pay.errors.
**Steps:** Add 'libs/lib-errors' entry to root settings.gradle; Create libs/lib-errors/build.gradle: Java 21 toolchain, Spring Boot 3.x BOM, spring-boot-starter-web (compileOnly), JUnit 5 test dependency; Add libs/lib-errors/src/main/java/com/gme/pay/errors/package-info.java; Run ./gradlew :libs:lib-errors:compileJava and confirm exit 0
**Deliverable:** libs/lib-errors/build.gradle and libs/lib-errors/src/main/java/com/gme/pay/errors/package-info.java
**Acceptance / logic checks:**
- ./gradlew :libs:lib-errors:compileJava exits 0
- lib-errors does NOT declare implementation dependency on lib-money (no circular dep)
- spring-boot-starter-web is compileOnly so services control the web starter version
- Module appears in ./gradlew projects output

### 2.4-T03 — Implement CurrencyScale: ISO-4217 scale lookup (KRW=0, USD=4, intermediate=8)  _(25 min)_
**Context:** DAT-03 §2.1 mandates: KRW stored as BIGINT integer (0 decimal places), USD as DECIMAL(20,4) (4 dp), and all treasury/USD-pool intermediate amounts at DECIMAL(20,8) (8 dp). RATE-04 §A2: non-listed currencies fall back to java.util.Currency.getDefaultFractionDigits(). CurrencyScale is the single source of truth for scale in lib-money. Phase 1 currencies: KRW (0), USD (4), MNT (0).
**Steps:** Create libs/lib-money/src/main/java/com/gme/pay/money/CurrencyScale.java as a final utility class with private constructor; static int scaleOf(String iso4217): KRW->0, MNT->0, USD->4; fallback Currency.getInstance(code).getDefaultFractionDigits(); throw IllegalArgumentException for unknown code; static int intermediateScale() returns 8 (USD-pool calculations); Add class-level Javadoc citing DAT-03 §2.1: KRW=0, USD=4, intermediate=8; Create CurrencyScaleTest (JUnit 5) asserting all four cases
**Deliverable:** libs/lib-money/src/main/java/com/gme/pay/money/CurrencyScale.java and CurrencyScaleTest.java
**Acceptance / logic checks:**
- scaleOf('KRW') == 0
- scaleOf('USD') == 4
- scaleOf('EUR') == 2 (ISO 4217 default via java.util.Currency)
- intermediateScale() == 8
- scaleOf('NOTACCY') throws IllegalArgumentException containing the bad code
**Depends on:** 2.4-T01

### 2.4-T04 — Implement Money immutable value object: BigDecimal amount + ISO-4217 currency  _(35 min)_
**Context:** All monetary amounts in GMEPay+ use fixed-precision BigDecimal (never float/double). DAT-03 §2.1: KRW scale=0, USD scale=4. API-05 §2.5: money serialized as {amount: decimal string, currency: ISO-4217}. Money is immutable. Constructor enforces scale == CurrencyScale.scaleOf(currency). Factory Money.of(String, String) parses incoming decimal strings. Arithmetic methods are NOT in this ticket (T05).
**Steps:** Create libs/lib-money/src/main/java/com/gme/pay/money/Money.java as a final class (or Java record) with fields BigDecimal amount and String currency; Constructor validates: amount non-null, currency exactly 3 uppercase chars, amount.scale() == CurrencyScale.scaleOf(currency) — throws ArithmeticException if scale wrong; Factory Money.of(String amountStr, String currency): parse via new BigDecimal(amountStr); strip extra trailing zeros; re-scale to CurrencyScale.scaleOf(currency) using HALF_UP; Factory Money.ofIntermediate(BigDecimal amount, String currency): scale to intermediateScale() = 8; Implement equals/hashCode on (amount at canonical scale, currency); toString() = amount.toPlainString() + ' ' + currency; Add MoneyTest (JUnit 5) covering all factory variants and invariants
**Deliverable:** libs/lib-money/src/main/java/com/gme/pay/money/Money.java and MoneyTest.java
**Acceptance / logic checks:**
- Money.of('12500','KRW').amount().scale() == 0 and compareTo BigDecimal 12500 == 0
- Money.of('12500.00','KRW') also stores scale-0 12500
- Money.of('36.7500','USD').amount().scale() == 4
- Money.of('1.123456789','USD') rounds to scale 4 via HALF_UP — does NOT throw
- Money constructor with null amount throws NullPointerException
- Money constructor with 'US' (2-char) currency throws IllegalArgumentException
**Depends on:** 2.4-T03

### 2.4-T05 — Implement Money arithmetic: add, subtract, multiply, zero, roundToScale  _(40 min)_
**Context:** Rate engine steps require: addition (collection_amount = send_amount + service_charge), subtraction (pool identity check), and multiplication by margin (collection_margin_usd = collection_usd * m_a). Arithmetic must stay in BigDecimal. Rounding applied only at final Step 5 (DAT-03 §2.4). All operations require same currency; cross-currency throws IllegalArgumentException.
**Steps:** Add Money add(Money other): assert same currency; return new Money with sum at same scale; Add Money subtract(Money other): assert same currency; result may be negative (allow for pool identity delta); scale preserved; Add Money multiply(BigDecimal factor, int resultScale, RoundingMode mode): multiply amount by factor, set scale to resultScale with given mode; return new Money; Add Money roundToScale(): re-scale to CurrencyScale.scaleOf(currency) using HALF_UP; Add static Money zero(String currency): amount = BigDecimal.ZERO at CurrencyScale.scaleOf(currency); Add MoneyArithmeticTest (JUnit 5) with at least 6 assertions
**Deliverable:** Updated libs/lib-money/src/main/java/com/gme/pay/money/Money.java with arithmetic and MoneyArithmeticTest.java
**Acceptance / logic checks:**
- Money.of('10.0000','USD').add(Money.of('5.0000','USD')) = Money(15.0000,USD)
- Money.of('10.0000','USD').subtract(Money.of('3.0000','USD')) = Money(7.0000,USD)
- add with different currencies throws IllegalArgumentException
- Money.of('100.00000000','USD').multiply(new BigDecimal('0.01'),8,HALF_UP) = BigDecimal 1.00000000 at scale 8
- Money.zero('KRW').amount() equals BigDecimal.ZERO at scale 0
**Depends on:** 2.4-T04

### 2.4-T06 — Implement MoneyModule: Jackson serializer/deserializer for Money value objects  _(35 min)_
**Context:** API-05 §2.5: money serialized as {amount:'12500.00',currency:'KRW'} using decimal strings — never scientific notation, never floats. KRW '12500' and '12500.00' both accepted on input and stored as scale-0. Registered via META-INF/services Java SPI so Spring Boot auto-detects it without manual @Bean. Applies to all services that include lib-money.
**Steps:** Create libs/lib-money/src/main/java/com/gme/pay/money/MoneyModule.java extending com.fasterxml.jackson.databind.module.SimpleModule; Implement MoneySerializer extends JsonSerializer<Money>: writes {amount: amount.toPlainString(), currency: currency}; Implement MoneyDeserializer extends JsonDeserializer<Money>: reads {amount, currency} node; constructs Money.of(amountText, currency); Register MoneyModule via libs/lib-money/src/main/resources/META-INF/services/com.fasterxml.jackson.databind.Module (SPI file listing the fully-qualified class name); Add MoneyJsonTest (JUnit 5): serialize Money(12500,KRW) assert contains '12500' not '12500.00'; serialize Money(36.7500,USD) assert contains '36.7500'; deserialize round-trip equals original
**Deliverable:** libs/lib-money/src/main/java/com/gme/pay/money/MoneyModule.java, MoneySerializer.java, MoneyDeserializer.java, META-INF/services/com.fasterxml.jackson.databind.Module
**Acceptance / logic checks:**
- Serialized KRW 12500 JSON has amount field '12500' with no decimal point
- Serialized USD 36.7500 JSON has amount field '36.7500'
- No scientific notation in output for any supported currency
- Deserializing {amount:'12500.00',currency:'KRW'} produces Money(12500,KRW) at scale 0
- Missing currency field in JSON throws JsonMappingException on deserialization
**Depends on:** 2.4-T05

### 2.4-T07 — Implement @ValidCurrency Jakarta Bean Validation annotation and ConstraintValidator  _(30 min)_
**Context:** API-05 requires ISO-4217 currency codes on all money fields in request bodies. Spring Boot 3.x uses Jakarta Validation (jakarta.validation.*). The @ValidCurrency annotation rejects unknown codes at the API boundary before arithmetic. Supports optional allowedCodes attribute to restrict to a configured set (e.g. Phase 1: KRW, USD, MNT).
**Steps:** Create libs/lib-money/src/main/java/com/gme/pay/money/validation/ValidCurrency.java: @Target({FIELD,PARAMETER}), @Retention(RUNTIME), @Constraint(validatedBy=CurrencyValidator.class); attribute String[] allowedCodes() default {}; Create CurrencyValidator implements ConstraintValidator<ValidCurrency,String>: isValid returns false for null, non-3-char, or java.util.Currency.getInstance throws; if allowedCodes non-empty, also check membership; Add CurrencyValidatorTest: valid 'KRW' passes, 'XXX' fails, 'US' fails, @ValidCurrency(allowedCodes={'KRW','USD'}) rejects 'MNT', accepts 'USD'
**Deliverable:** libs/lib-money/src/main/java/com/gme/pay/money/validation/ValidCurrency.java and CurrencyValidator.java
**Acceptance / logic checks:**
- @ValidCurrency on 'KRW' passes validation
- @ValidCurrency on 'NOTACCY' fails with constraint violation
- @ValidCurrency(allowedCodes={'KRW','USD'}) on 'MNT' fails
- @ValidCurrency(allowedCodes={'KRW','USD'}) on 'USD' passes
- Annotation is applicable to both fields and method parameters
**Depends on:** 2.4-T03

### 2.4-T08 — Implement @ValidMoney Jakarta annotation and MoneyValidator for Money value objects  _(30 min)_
**Context:** POST request bodies carry Money objects deserialized from JSON. @ValidMoney wraps Money-level invariants: non-null, non-negative amount, 3-char currency, scale matches CurrencyScale. This is distinct from @ValidCurrency (T07) which operates on String fields. Used in rate/payment request DTOs in services like rate-fx and payment-executor.
**Steps:** Create libs/lib-money/src/main/java/com/gme/pay/money/validation/ValidMoney.java annotation with boolean allowNegative() default false; Create MoneyValidator implements ConstraintValidator<ValidMoney,Money>: checks amount non-null, currency 3 chars, amount.scale() == CurrencyScale.scaleOf(currency), and (allowNegative or amount.signum() >= 0); Add MoneyValidatorTest: zero KRW passes, negative USD fails by default, negative USD passes allowNegative=true, wrong-scale USD fails
**Deliverable:** libs/lib-money/src/main/java/com/gme/pay/money/validation/ValidMoney.java and MoneyValidator.java
**Acceptance / logic checks:**
- Money.of('0','KRW') passes @ValidMoney
- Money with negative amount fails @ValidMoney(allowNegative=false)
- Money with negative amount passes @ValidMoney(allowNegative=true)
- Money with amount.scale() != CurrencyScale.scaleOf(currency) fails @ValidMoney
- Constraint violation message includes the currency code
**Depends on:** 2.4-T07

### 2.4-T09 — Implement MoneyRounder: single-step currency-native rounding (never mid-pool)  _(30 min)_
**Context:** DAT-03 §2.4: intermediate USD-pool values kept at 8 dp; collection_amount rounded once at Step 5 to currency-native scale (KRW=0, USD=4) using HALF_UP. RATE-04 §A1: rounding must NOT be applied mid-pool to avoid compounding error. MoneyRounder enforces this discipline as the only rounding entry point.
**Steps:** Create libs/lib-money/src/main/java/com/gme/pay/money/MoneyRounder.java as a final utility class; Money roundToFinal(Money raw): apply CurrencyScale.scaleOf(raw.currency()) with RoundingMode.HALF_UP; return new Money at correct scale; Money roundToIntermediate(BigDecimal rawAmount, String currency): scale to intermediateScale()=8 using HALF_UP; return Money.ofIntermediate(rounded, currency); Add MoneyRounderTest: roundToFinal KRW 12500.49->12500, KRW 12500.50->12501; roundToFinal USD 36.75001->36.7500; roundToIntermediate preserves 8 dp without stripping trailing zeros
**Deliverable:** libs/lib-money/src/main/java/com/gme/pay/money/MoneyRounder.java and MoneyRounderTest.java
**Acceptance / logic checks:**
- roundToFinal(Money('12500.49','KRW')) returns Money(12500,KRW)
- roundToFinal(Money('12500.50','KRW')) returns Money(12501,KRW) — HALF_UP
- roundToFinal(Money('36.75001234','USD')) returns Money(36.7500,USD)
- roundToIntermediate returns BigDecimal at scale exactly 8
- Class is stateless — safe for concurrent calls
**Depends on:** 2.4-T05

### 2.4-T10 — Implement PoolIdentityChecker: assert collection_usd - margins == payout_usd_cost within 0.01 USD  _(30 min)_
**Context:** RATE-04 pool invariant: collection_usd - collection_margin_usd - payout_margin_usd == payout_usd_cost. Tolerance 0.01 USD (one cent). Breach is a hard error: transaction must NOT commit. GmeErrorCode.POOL_IDENTITY_FAILURE (HTTP 500, not retryable). This checker is called after Step 3 in the rate engine and before any prefunding deduction.
**Steps:** Create libs/lib-money/src/main/java/com/gme/pay/money/PoolIdentityChecker.java; POOL_TOLERANCE = new BigDecimal('0.01'); void check(BigDecimal collectionUsd, BigDecimal collectionMarginUsd, BigDecimal payoutMarginUsd, BigDecimal payoutUsdCost): lhs = collectionUsd - collectionMarginUsd - payoutMarginUsd; diff = lhs.subtract(payoutUsdCost).abs(); if diff.compareTo(POOL_TOLERANCE) > 0 throw GmeException(POOL_IDENTITY_FAILURE, 'Pool identity breach: deviation=' + diff); lib-money must declare compileOnly dependency on lib-errors for GmeException; lib-errors is always on the runtime classpath via services; Add PoolIdentityCheckerTest: exact match passes; diff=0.009 passes; diff=0.011 throws; diff=-0.011 (negative) throws
**Deliverable:** libs/lib-money/src/main/java/com/gme/pay/money/PoolIdentityChecker.java and PoolIdentityCheckerTest.java
**Acceptance / logic checks:**
- diff=0.000 passes without exception
- diff=0.009 USD passes (within 0.01 tolerance)
- diff=0.011 USD throws GmeException with errorCode POOL_IDENTITY_FAILURE
- Exception message contains actual deviation value
- PoolIdentityChecker is stateless (no instance fields)
**Depends on:** 2.4-T05, 2.4-T11

### 2.4-T11 — Define GmeErrorCode enum with all API-05 §8.2 canonical error codes  _(30 min)_
**Context:** API-05 §8.2 lists the full error code catalogue. Each code carries HTTP status and retryable flag. The enum is in lib-errors so all services share it. Codes: VALIDATION_ERROR(400,false), MISSING_IDEMPOTENCY_KEY(400,false), INVALID_SIGNATURE(401,false), INVALID_API_KEY(401,false), TIMESTAMP_OUT_OF_RANGE(401,false), FORBIDDEN(403,false), IP_NOT_ALLOWLISTED(403,false), PAYMENT_NOT_FOUND(404,false), MERCHANT_NOT_FOUND(404,false), MERCHANT_INACTIVE(404,false), SCHEME_NOT_FOUND(404,false), IDEMPOTENCY_KEY_REUSE(422,false), RATE_QUOTE_EXPIRED(422,false), RATE_QUOTE_INVALID(422,false), PARTNER_B_QUOTE_DEVIATION(422,false), PARTNER_B_QUOTE_UNAVAILABLE(422,true), SCHEME_UNAVAILABLE(422,true), PAYMENT_MODE_NOT_SUPPORTED(422,false), DIRECTION_NOT_ENABLED(422,false), CANCEL_NOT_PERMITTED(400,false), INSUFFICIENT_PREFUNDING(402,false), DUPLICATE_PARTNER_TXN_REF(409,false), QR_CODE_DEACTIVATED(422,false), NO_SCHEME_FOR_LOCATION(422,false), APPROVAL_TIMEOUT(422,false), RATE_LIMITED(429,true), INTERNAL_ERROR(500,true), SERVICE_UNAVAILABLE(503,true), POOL_IDENTITY_FAILURE(500,false).
**Steps:** Create libs/lib-errors/src/main/java/com/gme/pay/errors/GmeErrorCode.java as an enum; Each constant: GmeErrorCode(int httpStatus, boolean retryable) constructor; Add int httpStatus() and boolean retryable() accessors; Add all 29 codes as listed in context; Add GmeErrorCodeTest asserting 5+ specific codes with status and retryable values
**Deliverable:** libs/lib-errors/src/main/java/com/gme/pay/errors/GmeErrorCode.java
**Acceptance / logic checks:**
- All 29 error codes present
- RATE_QUOTE_EXPIRED.httpStatus() == 422 and retryable() == false
- PARTNER_B_QUOTE_UNAVAILABLE.retryable() == true
- INSUFFICIENT_PREFUNDING.httpStatus() == 402
- POOL_IDENTITY_FAILURE.httpStatus() == 500 and retryable() == false
- INTERNAL_ERROR.retryable() == true
**Depends on:** 2.4-T02

### 2.4-T12 — Implement ErrorDetail record and ErrorEnvelope: API-05 §8.1 canonical error JSON structure  _(30 min)_
**Context:** API-05 §8.1 error envelope: {error:{code,message,request_id,details:[{field,issue}]}}. Immutable Java records. error.code values come from GmeErrorCode enum (T11). request_id echoes the X-Request-ID response header. details array may be empty and should be omitted from JSON when empty. This ticket creates structural records; the @RestControllerAdvice is in T13.
**Steps:** Create libs/lib-errors/src/main/java/com/gme/pay/errors/ErrorDetail.java as Java record(String field, String issue); Create libs/lib-errors/src/main/java/com/gme/pay/errors/ErrorEnvelope.java: outer record wrapping an inner ErrorBody record(String code, String message, String requestId, List<ErrorDetail> details); top-level JSON key must be 'error'; Add @JsonProperty('request_id') on requestId, @JsonInclude(NON_EMPTY) on details list; Add static factory ErrorEnvelope.of(GmeErrorCode code, String message, String requestId) with empty details; Add static factory ErrorEnvelope.withDetails(GmeErrorCode code, String message, String requestId, List<ErrorDetail> details); Add ErrorEnvelopeJsonTest: serialize ErrorEnvelope.of and assert JSON structure
**Deliverable:** libs/lib-errors/src/main/java/com/gme/pay/errors/ErrorDetail.java and ErrorEnvelope.java
**Acceptance / logic checks:**
- Serialized JSON has top-level 'error' key wrapping all fields
- request_id serializes as 'request_id' not 'requestId'
- Empty details list omitted from JSON (@JsonInclude NON_EMPTY)
- ErrorEnvelope.of(RATE_QUOTE_EXPIRED, msg, reqId).error().code() == 'RATE_QUOTE_EXPIRED'
- Null code in factory throws NullPointerException
**Depends on:** 2.4-T11

### 2.4-T13 — Implement GmeException: RuntimeException carrying GmeErrorCode + optional ErrorDetails  _(25 min)_
**Context:** Services throw GmeException. Spring @RestControllerAdvice (T14) catches it and converts to ErrorEnvelope JSON. GmeException must carry a GmeErrorCode so the handler derives HTTP status without switch statements. Static factory GmeException.validation(details) produces VALIDATION_ERROR with a standard message matching API-05 §8.2.
**Steps:** Create libs/lib-errors/src/main/java/com/gme/pay/errors/GmeException.java extending RuntimeException; Constructors: GmeException(GmeErrorCode code, String message), GmeException(GmeErrorCode code, String message, List<ErrorDetail> details), GmeException(GmeErrorCode code, String message, Throwable cause); Add GmeErrorCode errorCode() and List<ErrorDetail> details() accessors; details() returns unmodifiable list; Static factory GmeException.validation(List<ErrorDetail> details) sets code=VALIDATION_ERROR, message='One or more request fields failed validation'; Add GmeExceptionTest asserting all constructors and factory
**Deliverable:** libs/lib-errors/src/main/java/com/gme/pay/errors/GmeException.java and GmeExceptionTest.java
**Acceptance / logic checks:**
- GmeException(RATE_QUOTE_EXPIRED, msg).errorCode() == RATE_QUOTE_EXPIRED
- GmeException.validation(details).errorCode() == VALIDATION_ERROR
- details() returns Collections.unmodifiableList
- getMessage() returns the message string
- GmeException is unchecked (extends RuntimeException)
**Depends on:** 2.4-T12

### 2.4-T14 — Implement GlobalExceptionHandler: @RestControllerAdvice mapping GmeException to ErrorEnvelope JSON  _(45 min)_
**Context:** Every Spring Boot service must return API-05 §8.1 error JSON on all error paths. The @RestControllerAdvice catches GmeException and maps to HTTP status from GmeErrorCode.httpStatus(). Also catches ConstraintViolationException and MethodArgumentNotValidException producing VALIDATION_ERROR 400 with details array. Generic exceptions produce INTERNAL_ERROR 500 with no stack trace in response body.
**Steps:** Create libs/lib-errors/src/main/java/com/gme/pay/errors/GlobalExceptionHandler.java annotated @RestControllerAdvice; Handle GmeException: extract X-Request-ID from HttpServletRequest (generate UUID if absent); build ErrorEnvelope.withDetails; return ResponseEntity with code.httpStatus(); Handle ConstraintViolationException and MethodArgumentNotValidException: map each violation to ErrorDetail(path, message); wrap via GmeException.validation; return HTTP 400; Handle Throwable: log at ERROR level with request_id and stack trace; return INTERNAL_ERROR 500 ErrorEnvelope; never expose stack trace in response body; Set Content-Type: application/json on all error responses
**Deliverable:** libs/lib-errors/src/main/java/com/gme/pay/errors/GlobalExceptionHandler.java
**Acceptance / logic checks:**
- GmeException(RATE_QUOTE_EXPIRED) -> HTTP 422 JSON with error.code='RATE_QUOTE_EXPIRED'
- ConstraintViolationException -> HTTP 400 with error.code='VALIDATION_ERROR' and non-empty details
- POOL_IDENTITY_FAILURE -> HTTP 500
- Generic RuntimeException -> HTTP 500 INTERNAL_ERROR with no Java class names in response body
- X-Request-ID header value appears in error.request_id field
**Depends on:** 2.4-T13

### 2.4-T15 — Implement LibErrorsAutoConfiguration: Spring Boot 3.x auto-config for GlobalExceptionHandler  _(35 min)_
**Context:** Services must not manually declare GlobalExceptionHandler. lib-errors provides a Spring Boot 3.x auto-configuration class registered in META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports. Activates only for servlet (not reactive) web contexts. Also customizes ObjectMapper for snake_case and non-null fields to keep error JSON consistent across services.
**Steps:** Create libs/lib-errors/src/main/java/com/gme/pay/errors/autoconfigure/LibErrorsAutoConfiguration.java: @AutoConfiguration, @ConditionalOnWebApplication(type=SERVLET); Declare @Bean GlobalExceptionHandler globalExceptionHandler(); Declare @Bean Jackson2ObjectMapperBuilderCustomizer errorMapperCustomizer() setting PropertyNamingStrategies.SNAKE_CASE and serializationInclusion NON_NULL; Register in libs/lib-errors/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports; Add LibErrorsAutoConfigurationTest: minimal @SpringBootApplication test asserting GlobalExceptionHandler bean is present without manual @Import
**Deliverable:** libs/lib-errors/src/main/java/com/gme/pay/errors/autoconfigure/LibErrorsAutoConfiguration.java and AutoConfiguration.imports
**Acceptance / logic checks:**
- GlobalExceptionHandler bean present in servlet context without manual @Import
- Auto-config does NOT activate for @ConditionalOnWebApplication(type=REACTIVE) context
- ObjectMapper uses SNAKE_CASE for error fields
- Null fields omitted from serialized ErrorEnvelope
- Spring Boot test context loads without BeanDefinitionOverrideException
**Depends on:** 2.4-T14

### 2.4-T16 — Add lib-money and lib-errors implementation dependencies to all service build.gradle files  _(25 min)_
**Context:** All GMEPay+ services (rate-fx, payment-executor, prefunding, transaction-mgmt, auth-identity, smart-router, qr-service, settlement-recon, notification-webhook, config-registry, scheme-adapter) need Money arithmetic and ErrorEnvelope. Dependencies must be wired once in each service's build.gradle. No new Java code; dependency-wiring only.
**Steps:** For each module under services/, open build.gradle and add implementation(project(':libs:lib-money')) and implementation(project(':libs:lib-errors')) inside the dependencies block; Confirm lib-errors and lib-money do NOT depend on any services/ module (no circular dep); Run ./gradlew build --dry-run to verify all modules resolve without error; Commit the build.gradle changes
**Deliverable:** Updated build.gradle for every module under services/
**Acceptance / logic checks:**
- ./gradlew :services:rate-fx:compileJava resolves Money and GmeErrorCode on classpath
- ./gradlew :services:payment-executor:compileJava resolves both libs
- ./gradlew :libs:lib-money:dependencies shows no services/ dependency
- No circular dependency errors in Gradle dependency insight report
- ./gradlew build --dry-run exits 0
**Depends on:** 2.4-T15, 2.4-T06

### 2.4-T17 — Flyway migration V001__currency_scale.sql: PostgreSQL 16 currency_scale config table  _(35 min)_
**Context:** DAT-03: for currencies not in the hardcoded CurrencyScale list, scale is stored in a currency_scale table maintained by GME Ops. Table lives in the shared config schema (lib-persistence). Phase 1 seed: KRW=0, USD=4, MNT=0. Flyway migrations are in libs/lib-persistence/src/main/resources/db/migration/. PostgreSQL 16 column types: CHAR(3) PK, SMALLINT NOT NULL CHECK(0..8), BOOLEAN DEFAULT true, TIMESTAMPTZ DEFAULT now().
**Steps:** Create libs/lib-persistence/src/main/resources/db/migration/V001__currency_scale.sql; CREATE TABLE currency_scale(currency_code CHAR(3) NOT NULL PRIMARY KEY, decimal_places SMALLINT NOT NULL CHECK(decimal_places >= 0 AND decimal_places <= 8), active BOOLEAN NOT NULL DEFAULT true, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()); INSERT seed rows: ('KRW',0,true), ('USD',4,true), ('MNT',0,true); Add Flyway + Testcontainers (PostgreSQLContainer) integration test CurrencyScaleMigrationIT in lib-persistence verifying migration applies cleanly and seed rows are queryable; No JPA entity in this ticket (T18)
**Deliverable:** libs/lib-persistence/src/main/resources/db/migration/V001__currency_scale.sql
**Acceptance / logic checks:**
- Migration applies cleanly against PostgreSQL 16 Testcontainer
- SELECT count(*) FROM currency_scale returns 3 after migration
- KRW row has decimal_places=0, USD has decimal_places=4
- INSERT ('KRW',2,true) fails with PK violation
- INSERT ('BAD',-1,true) fails with CHECK constraint
**Depends on:** 2.4-T01

### 2.4-T18 — Implement CurrencyScaleEntity JPA entity and CurrencyScaleRepository in lib-persistence  _(40 min)_
**Context:** The currency_scale PostgreSQL table (V001 from T17) needs a JPA entity and Spring Data JPA repository so services can load runtime scales beyond the Phase 1 hardcoded set. lib-persistence is the shared JPA/migration utilities module. Uses @Cacheable so Redis (or local Caffeine) can cache the result to avoid DB round-trips on every request.
**Steps:** Create libs/lib-persistence/src/main/java/com/gme/pay/persistence/currency/CurrencyScaleEntity.java: @Entity @Table(name='currency_scale'); @Id @Column(name='currency_code',length=3) String currencyCode; @Column SMALLINT decimalPlaces; boolean active; Create CurrencyScaleRepository extends JpaRepository<CurrencyScaleEntity,String>; add findAllByActiveTrue() annotated @Cacheable(value='currency_scale'); Update CurrencyScale.java in lib-money: add static void loadFromDb(Map<String,Integer> dbMap) so CurrencyScaleRepository results override the hardcoded map at startup; Add @DataJpaTest + @AutoConfigureTestDatabase(replace=NONE) + PostgreSQLContainer test CurrencyScaleRepositoryIT asserting findAllByActiveTrue returns 3 seeded rows
**Deliverable:** libs/lib-persistence/src/main/java/com/gme/pay/persistence/currency/CurrencyScaleEntity.java and CurrencyScaleRepository.java
**Acceptance / logic checks:**
- CurrencyScaleRepository.findAllByActiveTrue() returns 3 entities from seeded DB
- Entity maps currency_code as @Id
- findById('KRW').get().getDecimalPlaces() == 0
- @DataJpaTest passes against PostgreSQLContainer
- CurrencyScale.loadFromDb({EUR->2}) causes scaleOf('EUR') to return 2
**Depends on:** 2.4-T17, 2.4-T03

### 2.4-T19 — Implement MoneyAmountNormalizer: parse and normalize partner API decimal strings to Money  _(30 min)_
**Context:** API-05 §2.5: KRW amount '12500.00' and '12500' must both be accepted; stored as scale-0. USD '36.75' or '36.7500' both stored at scale 4. The normalizer is the API boundary entry point called before any arithmetic in rate-fx and payment-executor. Invalid or negative input throws GmeException(VALIDATION_ERROR) with an ErrorDetail naming the field.
**Steps:** Create libs/lib-money/src/main/java/com/gme/pay/money/MoneyAmountNormalizer.java; Money normalize(String amountStr, String currency, String fieldName): parse via new BigDecimal(amountStr.trim()) inside try-catch NumberFormatException; scale to CurrencyScale.scaleOf(currency) using HALF_UP; throw GmeException(VALIDATION_ERROR, List.of(new ErrorDetail(fieldName, 'Invalid or negative amount: ' + amountStr))) if null/empty/unparseable/negative; Add MoneyAmountNormalizerTest covering all edge cases
**Deliverable:** libs/lib-money/src/main/java/com/gme/pay/money/MoneyAmountNormalizer.java and MoneyAmountNormalizerTest.java
**Acceptance / logic checks:**
- normalize('12500.00','KRW','target_payout') returns Money(12500,KRW) scale 0
- normalize('36.75','USD','collection_amount') returns Money(36.7500,USD) scale 4
- normalize('-0.01','USD','amount') throws GmeException VALIDATION_ERROR with details[0].field='amount'
- normalize('not_a_number','KRW','amount') throws GmeException VALIDATION_ERROR
- normalize(null,'KRW','amount') throws GmeException VALIDATION_ERROR
**Depends on:** 2.4-T08, 2.4-T13

### 2.4-T20 — Unit test RATE-04 cross-border vector: SendMN KRW 50000 payout using Money + PoolIdentityChecker  _(40 min)_
**Context:** RATE-04 §5 worked example: SendMN (OVERSEAS, Settle A=USD), ZeroPay KRW merchant, target_payout=50000 KRW. treasury.usd_krw=1380.00, treasury.usd_usd=1.0 (IDENTITY leg). m_a=0.01, m_b=0.01. service_charge=0.36 USD. Step 1: payout_usd_cost=50000/1380=36.23188406 (8dp). Step 2: collection_usd=36.23188406/(1-0.01-0.01)=36.96111638. Step 3: margins=36.96111638*0.01=0.36961116 each. Step 4: send_amount=36.96111638*1.0=36.96111638. Step 5: collection_amount=36.96111638+0.36=37.32111638 -> round to USD scale 4 = 37.3211. Pool check: 36.96111638-0.36961116-0.36961116=36.23189406; diff from 36.23188406=0.000010 which is less than 0.01 tolerance.
**Steps:** Create libs/lib-money/src/test/java/com/gme/pay/money/RateVectorCrossBorderTest.java; Reproduce all 5 steps using BigDecimal at scale 8 with MoneyRounder.roundToIntermediate; Call PoolIdentityChecker.check with computed values; Assert collection_amount rounds to Money(37.3211,USD) via MoneyRounder.roundToFinal; Assert pool identity check passes with no exception; All computations use BigDecimal only — no float/double literals
**Deliverable:** libs/lib-money/src/test/java/com/gme/pay/money/RateVectorCrossBorderTest.java
**Acceptance / logic checks:**
- payout_usd_cost == 36.23188406 (8dp BigDecimal)
- collection_usd == 36.96111638 (8dp)
- collection_amount after roundToFinal == Money(37.3211,USD)
- PoolIdentityChecker.check does not throw
- Test contains zero float or double literals
**Depends on:** 2.4-T09, 2.4-T10

### 2.4-T21 — Unit test RATE-04 same-currency short-circuit: GME Remit domestic KRW 50000  _(30 min)_
**Context:** RATE-04 §7.2 same-currency short-circuit: when collection=settle_a=settle_b=payout all KRW, USD pool is bypassed. collection_amount = target_payout + service_charge. m_a=m_b=0 enforced. service_charge=500 KRW. So collection_amount = 50000+500 = 50500 KRW. PoolIdentityChecker must NOT be called on this path.
**Steps:** Create libs/lib-money/src/test/java/com/gme/pay/money/RateVectorDomesticTest.java; Compute: Money.of('50000','KRW').add(Money.of('500','KRW')), assert equals Money(50500,KRW); Assert result.amount().scale() == 0 (no decimal digits for KRW); Assert adding USD service_charge to KRW collection throws IllegalArgumentException (currency mismatch); Verify m_a=m_b=0 guard: MarginGuard.checkCrossBorder(ZERO, ZERO, true) passes without exception
**Deliverable:** libs/lib-money/src/test/java/com/gme/pay/money/RateVectorDomesticTest.java
**Acceptance / logic checks:**
- 50000 KRW + 500 KRW = Money(50500,KRW)
- Result scale == 0 (KRW integer)
- Adding USD service charge to KRW base throws IllegalArgumentException
- MarginGuard passes for same-currency with m_a=m_b=0
- No BigDecimal division or USD-pool arithmetic appears in test
**Depends on:** 2.4-T05, 2.4-T18

### 2.4-T22 — Implement MarginGuard: validate m_a+m_b >= 2% for cross-border, m_a=m_b=0 for same-currency  _(25 min)_
**Context:** RATE-04 and canonical facts: cross-border rules require m_a+m_b >= 0.02 (2%); same-currency rules require m_a=m_b=0. MarginGuard enforces these rules before rate calculation. Violation throws GmeException(VALIDATION_ERROR). Lives in lib-money under validation package so rate-fx can call it without service-layer logic.
**Steps:** Create libs/lib-money/src/main/java/com/gme/pay/money/validation/MarginGuard.java as final utility class; void checkCrossBorder(BigDecimal mA, BigDecimal mB, boolean isSameCurrency): if isSameCurrency then assert mA.compareTo(ZERO)==0 and mB.compareTo(ZERO)==0, else assert mA.add(mB).compareTo(new BigDecimal('0.02'))>=0; throw GmeException(VALIDATION_ERROR, appropriate message) on failure; Add MarginGuardTest: mA=0.01,mB=0.01 cross-border passes; mA=0.009,mB=0.01 cross-border throws with message mentioning '2.00%'; mA=0,mB=0 same-currency passes; mA=0.01,mB=0 same-currency throws
**Deliverable:** libs/lib-money/src/main/java/com/gme/pay/money/validation/MarginGuard.java and MarginGuardTest.java
**Acceptance / logic checks:**
- mA=0.01, mB=0.01 cross-border passes
- mA=0.009, mB=0.01 cross-border throws GmeException VALIDATION_ERROR
- mA=0, mB=0 same-currency passes
- mA=0.01, mB=0 same-currency throws (both must be 0)
- Exception message contains '2.00%' for cross-border violation
**Depends on:** 2.4-T13

### 2.4-T23 — Integration test: GlobalExceptionHandler HTTP status and JSON body for critical error codes  _(40 min)_
**Context:** API-05 §8.2 specifies exact HTTP status per error code. The GlobalExceptionHandler (T14) must be verified with Spring MockMvc covering all critical codes used in the money/error path. A minimal @RestController throws each GmeException on demand; tests assert HTTP status and JSON body structure.
**Steps:** Create libs/lib-errors/src/test/java/com/gme/pay/errors/GlobalExceptionHandlerIntegrationTest.java using @WebMvcTest + @Import(LibErrorsAutoConfiguration.class); Define @RestController TestController with endpoints throwing GmeException variants and a generic RuntimeException; Test /throw/rate-quote-expired -> HTTP 422, body error.code='RATE_QUOTE_EXPIRED'; Test /throw/insufficient-prefunding -> HTTP 402, body error.code='INSUFFICIENT_PREFUNDING'; Test /throw/pool-identity-failure -> HTTP 500, body error.code='POOL_IDENTITY_FAILURE'; Test /throw/validation with ErrorDetail -> HTTP 400, details array non-empty; Test /throw/generic-runtime -> HTTP 500, body error.code='INTERNAL_ERROR', no class name or stack trace in body
**Deliverable:** libs/lib-errors/src/test/java/com/gme/pay/errors/GlobalExceptionHandlerIntegrationTest.java
**Acceptance / logic checks:**
- RATE_QUOTE_EXPIRED -> HTTP 422
- INSUFFICIENT_PREFUNDING -> HTTP 402
- POOL_IDENTITY_FAILURE -> HTTP 500
- VALIDATION_ERROR -> HTTP 400 with non-empty error.details
- Generic RuntimeException -> HTTP 500 with no stack trace in response body
**Depends on:** 2.4-T15

### 2.4-T24 — Wire MoneyModule Jackson SPI in rate-fx and payment-executor service integration slice tests  _(30 min)_
**Context:** lib-money registers MoneyModule via Java SPI (T06). Services must auto-discover it via Spring Boot Jackson auto-configuration. This ticket verifies SPI registration in the rate-fx and payment-executor service contexts by injecting the service ObjectMapper and asserting correct Money serialization/deserialization.
**Steps:** In services/rate-fx/src/test/java/.../RateFxMoneySerializationTest.java: @SpringBootTest; inject ObjectMapper; assert objectMapper.writeValueAsString(Money.of('50000','KRW')) == '{"amount":"50000","currency":"KRW"}'; Assert deserialization: objectMapper.readValue('{"amount":"36.7500","currency":"USD"}', Money.class) equals Money.of('36.7500','USD'); In services/payment-executor/src/test/java/.../PaymentExecutorMoneySerializationTest.java: same two assertions; Confirm no duplicate MoneyModule registration warning in test logs
**Deliverable:** services/rate-fx/src/test/java/com/gme/pay/ratefx/RateFxMoneySerializationTest.java and services/payment-executor/src/test/java/com/gme/pay/paymentexecutor/PaymentExecutorMoneySerializationTest.java
**Acceptance / logic checks:**
- ObjectMapper in rate-fx context serializes Money(50000,KRW) to {amount:'50000',currency:'KRW'} with no decimal point
- ObjectMapper in payment-executor context serializes Money(36.7500,USD) correctly
- Deserialization of {amount:'36.7500',currency:'USD'} returns Money equal to Money.of('36.7500','USD')
- No ClassCastException or duplicate module warning in test output
- Tests pass without WireMock or Testcontainers (pure slice)
**Depends on:** 2.4-T16, 2.4-T06

### 2.4-T25 — Javadoc pass: annotate all public lib-money and lib-errors classes with stack-aligned references  _(25 min)_
**Context:** lib-money and lib-errors are used by all GMEPay+ service developers. Class-level Javadoc must state the governing spec section, the key invariant, and any gotcha. CurrencyScale must cite DAT-03 §2.1 tiers. Money must cite immutability contract. PoolIdentityChecker must cite RATE-04 pool invariant and 0.01 USD tolerance. GmeErrorCode must cite API-05 §8.2. ErrorEnvelope must cite API-05 §8.1 JSON structure.
**Steps:** Add class-level Javadoc to CurrencyScale.java citing DAT-03 §2.1: KRW=0, USD=4, intermediate=8; Add class-level Javadoc to Money.java citing immutability, BigDecimal-only rule, and DAT-03; Add class-level Javadoc to PoolIdentityChecker.java: formula, 0.01 USD tolerance, POOL_IDENTITY_FAILURE error on breach; Add class-level Javadoc to GmeErrorCode.java: references API-05 §8.2 catalogue source; Add class-level Javadoc to ErrorEnvelope.java: references API-05 §8.1 structure; Run ./gradlew :libs:lib-money:javadoc :libs:lib-errors:javadoc and fix any -Xdoclint errors
**Deliverable:** Javadoc on CurrencyScale, Money, PoolIdentityChecker, GmeErrorCode, ErrorEnvelope
**Acceptance / logic checks:**
- ./gradlew :libs:lib-money:javadoc exits 0 with 0 errors
- ./gradlew :libs:lib-errors:javadoc exits 0 with 0 errors
- CurrencyScale Javadoc mentions 'KRW=0', 'USD=4', 'intermediate=8'
- PoolIdentityChecker Javadoc mentions '0.01 USD tolerance' and 'POOL_IDENTITY_FAILURE'
- GmeErrorCode Javadoc references 'API-05 §8.2'
**Depends on:** 2.4-T23, 2.4-T20, 2.4-T21, 2.4-T22


## WBS 2.6 — Internationalization framework
### 2.6-T01 — Define CurrencyConfig record in lib-money: ISO 4217 code, scale, and display rules  _(35 min)_
**Context:** GMEPay+ handles multiple currencies (KRW, USD, MNT, THB) each with an ISO 4217 three-letter code and a fixed decimal scale. KRW scale=0 (no sub-unit); USD scale=2. This record is the single source of truth used by the rate engine, formatters, and validators to apply correct rounding and prevent fractional KRW output. Module: lib-money (shared Gradle module at libs/lib-money).
**Steps:** Create libs/lib-money/src/main/java/com/gme/pay/money/CurrencyConfig.java as a Java record with fields: isoCode (String), scale (int), displaySymbol (String).; Add CurrencyRegistry.java as a @Component class that loads CurrencyConfig entries from classpath:currencies.yml; expose getCurrency(String isoCode) throwing UnknownCurrencyException if not found, and listAll().; Add libs/lib-money/src/main/resources/currencies.yml with entries for KRW (scale=0), USD (scale=2), MNT (scale=2), THB (scale=2).; Add UnknownCurrencyException.java in libs/lib-errors extending RuntimeException with the unknown code in the message.; Write Javadoc on CurrencyConfig stating that scale drives output rounding; DECIMAL(20,8) is the storage type but output must honour this field.
**Deliverable:** libs/lib-money/src/main/java/com/gme/pay/money/CurrencyConfig.java, CurrencyRegistry.java, libs/lib-money/src/main/resources/currencies.yml
**Acceptance / logic checks:**
- CurrencyRegistry.getCurrency(KRW).scale() == 0
- CurrencyRegistry.getCurrency(USD).scale() == 2
- getCurrency(ZZZ) throws UnknownCurrencyException with message containing ZZZ
- listAll() returns exactly 4 entries after initial seeding
- YAML file loads without Spring context errors in a plain unit test

### 2.6-T02 — Add MoneyAmount value type to lib-money with BigDecimal, scale-aware rounding, and arithmetic methods  _(35 min)_
**Context:** All monetary values in GMEPay+ are stored as DECIMAL(20,8) and computed with Java BigDecimal to avoid floating-point error (NFR-10 §5.1). MoneyAmount is an immutable record carrying the ISO currency code and enforcing per-currency scale from CurrencyConfig when round() is called. KRW amounts must produce zero fractional output. Module: lib-money.
**Steps:** Create libs/lib-money/src/main/java/com/gme/pay/money/MoneyAmount.java as an immutable record: amount (BigDecimal), currency (String).; Add factory method of(BigDecimal amount, String isoCode) that validates isoCode via CurrencyRegistry.; Add round() returning a new MoneyAmount rounded to CurrencyConfig.scale() using RoundingMode.HALF_UP.; Add add(), subtract(), multiply(BigDecimal factor) operating on raw BigDecimal and returning unrounded MoneyAmount (caller calls round() explicitly at defined points only).; Implement toString() as {CURRENCY} {AMOUNT} formatted to scale, e.g. KRW 1000, USD 10.25.
**Deliverable:** libs/lib-money/src/main/java/com/gme/pay/money/MoneyAmount.java
**Acceptance / logic checks:**
- MoneyAmount.of(new BigDecimal(1000.5), KRW).round().amount() equals new BigDecimal(1001)
- MoneyAmount.of(new BigDecimal(10.256), USD).round().amount() equals new BigDecimal(10.26)
- add() does not auto-round; rounding only on explicit round() call
- multiply(BigDecimal.ZERO) returns MoneyAmount with amount=0 for same currency
- toString() for USD 10.25 returns USD 10.25
**Depends on:** 2.6-T01

### 2.6-T03 — Flyway V201__i18n_locale_config.sql: create locale_config and supported_currency tables in config-registry  _(30 min)_
**Context:** The i18n framework needs two new PostgreSQL tables in the gmepay database owned by services/config-registry. locale_config stores supported UI locales; supported_currency stores per-currency operational config with scale (KRW=0, USD=2). Migrations live at services/config-registry/src/main/resources/db/migration/. NFR-10 §9.1 requires DECIMAL(20,8) storage for amounts; this migration captures the scale metadata used by the app layer.
**Steps:** Create services/config-registry/src/main/resources/db/migration/V201__i18n_locale_config.sql.; Define locale_config: locale_code VARCHAR(10) PK, display_name_en VARCHAR(100) NOT NULL, display_name_ko VARCHAR(100) NOT NULL, is_default BOOLEAN NOT NULL DEFAULT FALSE, active BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(). Add partial unique index: CREATE UNIQUE INDEX uq_locale_default ON locale_config(is_default) WHERE is_default = TRUE.; Define supported_currency: iso_code CHAR(3) PK, scale SMALLINT NOT NULL CHECK (scale >= 0 AND scale <= 8), display_symbol VARCHAR(10), active BOOLEAN NOT NULL DEFAULT TRUE.; Insert seed rows: locale_code=ko-KR (is_default=TRUE, display_name_en=Korean, display_name_ko=한국어), locale_code=en-US (is_default=FALSE, display_name_en=English, display_name_ko=영어).; Insert seed currency rows: KRW (scale=0), USD (scale=2), MNT (scale=2), THB (scale=2).
**Deliverable:** services/config-registry/src/main/resources/db/migration/V201__i18n_locale_config.sql
**Acceptance / logic checks:**
- Flyway migrate runs cleanly on Testcontainers PostgreSQL 16 with no prior schema
- Inserting a second is_default=TRUE row into locale_config is rejected by the partial unique index
- Inserting scale=-1 into supported_currency is rejected by the CHECK constraint
- SELECT count(*) FROM supported_currency returns 4 after migration
- SELECT locale_code FROM locale_config WHERE is_default=TRUE returns ko-KR

### 2.6-T04 — Configure Spring MessageSource beans for ko/en message bundles in services/ops-bff  _(40 min)_
**Context:** The Admin Portal (services/ops-bff, Spring Boot BFF) requires all operator-facing UI labels, error messages, and help text in Korean (primary, ko-KR) and English (en-US) per NFR-10 §9.2. Spring ReloadableResourceBundleMessageSource provides this. Bundle files live at classpath:i18n/messages_ko.properties and messages_en.properties. The BFF uses AcceptHeaderLocaleResolver with default ko-KR. Module: services/ops-bff.
**Steps:** Create services/ops-bff/src/main/resources/i18n/messages_ko.properties with keys: common.save, common.cancel, error.insufficient_prefunding, error.scheme_unavailable, error.partner_b_quote_deviation, error.partner_b_quote_unavailable, payment.status.committed, payment.status.failed, rate.label.offer_rate, rate.label.cross_rate -- all with Korean values.; Create services/ops-bff/src/main/resources/i18n/messages_en.properties with the same 10 keys in English.; Create services/ops-bff/src/main/java/com/gme/pay/opsbff/config/I18nConfig.java as @Configuration defining a MessageSource @Bean (bean name messageSource) using ReloadableResourceBundleMessageSource, basenames=classpath:i18n/messages, defaultEncoding=UTF-8, defaultLocale=ko-KR.; Add AcceptHeaderLocaleResolver @Bean named localeResolver with supported locales [ko-KR, en-US] and default ko-KR.; Ensure the messageSource bean name does not conflict with Spring Boot autoconfiguration (declare @Primary or override explicitly).
**Deliverable:** services/ops-bff/src/main/resources/i18n/messages_ko.properties, messages_en.properties, services/ops-bff/src/main/java/com/gme/pay/opsbff/config/I18nConfig.java
**Acceptance / logic checks:**
- messageSource.getMessage(error.insufficient_prefunding, null, Locale.KOREAN) returns a non-empty Korean string
- messageSource.getMessage(error.insufficient_prefunding, null, Locale.ENGLISH) returns a non-empty English string
- Accept-Language: en-US resolves to Locale en-US via localeResolver
- All 10 keys present in both .properties files
- Spring Boot application context loads without NoSuchBeanDefinitionException or conflict

### 2.6-T05 — Configure Spring MessageSource beans for ko/en message bundles in services/partner-bff  _(35 min)_
**Context:** The Partner Portal (services/partner-bff) primary language is English; Korean is secondary per NFR-10 §9.2. Same AcceptHeaderLocaleResolver pattern as ops-bff but default locale is en-US. Bundle files: classpath:i18n/messages_en.properties (primary) and messages_ko.properties. Module: services/partner-bff.
**Steps:** Create services/partner-bff/src/main/resources/i18n/messages_en.properties with keys: common.save, common.cancel, error.insufficient_prefunding, error.scheme_unavailable, error.partner_b_quote_deviation, error.partner_b_quote_unavailable, payment.status.committed, payment.status.failed, rate.label.offer_rate, prefunding.alert.low_balance -- all in English.; Create services/partner-bff/src/main/resources/i18n/messages_ko.properties with the same 10 keys in Korean.; Create services/partner-bff/src/main/java/com/gme/pay/partnerbff/config/I18nConfig.java as @Configuration with MessageSource @Bean (ReloadableResourceBundleMessageSource, classpath:i18n/messages, UTF-8, defaultLocale=en-US).; Add AcceptHeaderLocaleResolver @Bean with supported locales [en-US, ko-KR] and default en-US.; Verify messages_ko.properties is saved as UTF-8 (Korean characters must not be stored as escaped unicode unless the file encoding is explicitly UTF-8).
**Deliverable:** services/partner-bff/src/main/resources/i18n/messages_en.properties, messages_ko.properties, services/partner-bff/src/main/java/com/gme/pay/partnerbff/config/I18nConfig.java
**Acceptance / logic checks:**
- Default locale resolves to en-US when no Accept-Language header is present
- Accept-Language: ko-KR resolves to Korean messages
- All 10 keys resolve in both locales without NoSuchMessageException
- messages_ko.properties Korean characters are valid UTF-8 (no garbled output)
- messageSource bean name does not conflict with Spring Boot autoconfigured bean

### 2.6-T06 — Add locale-aware TimestampFormatter utility to lib-money: UTC storage, KST display, ISO-8601 API format  _(30 min)_
**Context:** Per NFR-10 §9.3: all internal timestamps are stored in UTC; Admin Portal displays in KST (UTC+9, Asia/Seoul, no DST); Partner Portal displays in the partner-configured timezone (default KST). API responses always use ISO-8601 UTC (e.g. 2026-06-05T14:00:00Z). This stateless utility class lives in lib-money and is used by both BFFs and by the rate engine for quote validUntil formatting. Module: lib-money.
**Steps:** Create libs/lib-money/src/main/java/com/gme/pay/money/TimestampFormatter.java as a final utility class with only static methods.; Add constants: public static final ZoneId KST = ZoneId.of(Asia/Seoul); public static final ZoneId UTC = ZoneOffset.UTC.; Implement static String formatForDisplay(Instant ts, ZoneId zone): formats as yyyy-MM-dd HH:mm:ss z using DateTimeFormatter.ofPattern.; Implement static String toApiString(Instant ts): returns ISO-8601 UTC string using DateTimeFormatter.ISO_INSTANT (e.g. 2026-06-05T14:00:00Z).; Implement static Instant parseApi(String iso8601): returns Instant; throws DateTimeParseException on invalid input (do not catch).
**Deliverable:** libs/lib-money/src/main/java/com/gme/pay/money/TimestampFormatter.java
**Acceptance / logic checks:**
- formatForDisplay(Instant.parse(2026-06-05T05:00:00Z), KST) produces a string showing 2026-06-05 14:00:00 KST
- toApiString output ends with Z (UTC indicator)
- parseApi(toApiString(Instant.now())) round-trips to same Instant
- formatForDisplay with ZoneOffset.UTC on the same Instant shows 05:00:00 UTC
- parseApi(not-a-date) throws DateTimeParseException

### 2.6-T07 — Add KstCronHelper to services/settlement: convert KST batch deadlines to UTC @Scheduled cron expressions  _(30 min)_
**Context:** NFR-10 §2.3 specifies internal batch deadlines in KST: ZP0011 at 01:30 KST, ZP0061 at 04:30 KST, ZP0063 at 13:30 KST, ZP0065/66 at 21:30 KST. Spring @Scheduled(cron) runs in JVM timezone (UTC). KST = UTC+9, so these map to: 16:30 UTC, 19:30 UTC, 04:30 UTC (next day), 12:30 UTC. A KstCronHelper utility produces the correct cron strings and is used in BatchScheduler.java. Module: services/settlement.
**Steps:** Create services/settlement/src/main/java/com/gme/pay/settlement/scheduler/KstCronHelper.java with static method kstToCronUtc(int kstHour, int kstMinute): subtract 9 hours (modulo 24) to get UTC hour; return cron string 0 {utcMinute} {utcHour} * * ?.; Add static final String constants: CRON_ZP0011 = kstToCronUtc(1,30), CRON_ZP0061 = kstToCronUtc(4,30), CRON_ZP0063 = kstToCronUtc(13,30), CRON_ZP0065 = kstToCronUtc(21,30).; Create services/settlement/src/main/java/com/gme/pay/settlement/scheduler/BatchScheduler.java as a @Component with four stub @Scheduled methods using those constants, each with a Javadoc comment stating the KST deadline and UTC equivalent.; Add @EnableScheduling on the services/settlement Spring Boot application class if not already present.; Add a unit test for kstToCronUtc covering all four deadlines plus a midnight-crossover case (kstToCronUtc(1,0) = 0 0 16 * * ?).
**Deliverable:** services/settlement/src/main/java/com/gme/pay/settlement/scheduler/KstCronHelper.java and BatchScheduler.java
**Acceptance / logic checks:**
- kstToCronUtc(1,30) returns 0 30 16 * * ?
- kstToCronUtc(4,30) returns 0 30 19 * * ?
- kstToCronUtc(13,30) returns 0 30 4 * * ?
- kstToCronUtc(21,30) returns 0 30 12 * * ?
- BatchScheduler compiles and Spring context loads with @Scheduled annotations active

### 2.6-T08 — Flyway V202__partner_display_timezone.sql: add display_timezone column to partners table  _(30 min)_
**Context:** Per NFR-10 §9.3, the Partner Portal displays timestamps in each partner-configured timezone (default KST, Asia/Seoul). The partners table in PostgreSQL (services/config-registry migrations) needs a display_timezone column. The partner-bff reads this value to set ZoneId for TimestampFormatter. Default is Asia/Seoul. Module: services/config-registry.
**Steps:** Create services/config-registry/src/main/resources/db/migration/V202__partner_display_timezone.sql.; Add column: ALTER TABLE partners ADD COLUMN display_timezone VARCHAR(60) NOT NULL DEFAULT Asia/Seoul;; Add a regex CHECK constraint: CHECK (display_timezone ~ ^[A-Za-z][A-Za-z0-9/_+\-]+$) to reject obviously malformed IANA identifiers.; Add SQL comment: COMMENT ON COLUMN partners.display_timezone IS IANA timezone identifier for Partner Portal timestamp display. Default Asia/Seoul (KST, UTC+9, no DST) per NFR-10 s9.3.; Update PartnerEntity.java in services/config-registry/src/main/java/com/gme/pay/config/entity/PartnerEntity.java to add String displayTimezone field with @Column(name=display_timezone).
**Deliverable:** services/config-registry/src/main/resources/db/migration/V202__partner_display_timezone.sql and updated PartnerEntity.java
**Acceptance / logic checks:**
- V202 applies cleanly after V201 on Testcontainers Postgres 16
- Existing partner rows default to Asia/Seoul post-migration
- PartnerEntity.getDisplayTimezone() returns Asia/Seoul for a seeded test partner
- Inserting display_timezone=America/New_York persists and is readable
- Inserting display_timezone with an empty string fails the CHECK constraint
**Depends on:** 2.6-T03

### 2.6-T09 — Implement LocaleHeaderFilter GlobalFilter in Spring Cloud Gateway: propagate resolved locale as X-Locale header  _(40 min)_
**Context:** GMEPay+ uses Spring Cloud Gateway (services/gateway) as the edge. The gateway must resolve Accept-Language to a supported locale and propagate it downstream as X-Locale so ops-bff and partner-bff do not re-parse it. Supported locales: ko-KR and en-US. Default for /admin routes: ko-KR. Default for /partner routes: en-US. Unsupported locales fall back to the route default. Module: services/gateway.
**Steps:** Create services/gateway/src/main/java/com/gme/pay/gateway/filter/LocaleHeaderFilter.java implementing GlobalFilter, Ordered.; In filter(), parse Accept-Language header; resolve to ko-KR or en-US; for paths starting /admin default to ko-KR; for paths starting /partner default to en-US; for other paths default to en-US.; Mutate the request: exchange.getRequest().mutate().header(X-Locale, resolvedLocale).build().; Register as a @Bean in services/gateway/src/main/java/com/gme/pay/gateway/config/GatewayFilterConfig.java at order Ordered.HIGHEST_PRECEDENCE + 10.; Write a unit test using MockServerWebExchange covering all Accept-Language and path permutations.
**Deliverable:** services/gateway/src/main/java/com/gme/pay/gateway/filter/LocaleHeaderFilter.java and GatewayFilterConfig.java
**Acceptance / logic checks:**
- Accept-Language: ko-KR on /admin path sets X-Locale: ko-KR
- Accept-Language: en-US on /admin path sets X-Locale: en-US
- No Accept-Language on /admin path defaults X-Locale: ko-KR
- No Accept-Language on /partner path defaults X-Locale: en-US
- Accept-Language: fr-FR (unsupported) on /partner path defaults X-Locale: en-US

### 2.6-T10 — Implement LocalizedMessageService and ErrorCode enum in lib-errors: locale-aware error code resolution  _(35 min)_
**Context:** Partner API error codes are machine-readable English identifiers (e.g. INSUFFICIENT_PREFUNDING, SCHEME_UNAVAILABLE, PARTNER_B_QUOTE_DEVIATION, PARTNER_B_QUOTE_UNAVAILABLE, RATE_QUOTE_EXPIRED). UI-facing layers (ops-bff, partner-bff) resolve these codes to human-readable locale strings via LocalizedMessageService in lib-errors. The service wraps Spring MessageSource and uses LocaleContextHolder for implicit locale. Module: lib-errors (libs/lib-errors).
**Steps:** Create libs/lib-errors/src/main/java/com/gme/pay/errors/ErrorCode.java enum with values: INSUFFICIENT_PREFUNDING, SCHEME_UNAVAILABLE, PARTNER_B_QUOTE_DEVIATION, PARTNER_B_QUOTE_UNAVAILABLE, RATE_QUOTE_EXPIRED, INVALID_CURRENCY, POOL_IDENTITY_BREACH, RATE_LOCK_VIOLATION.; Create libs/lib-errors/src/main/java/com/gme/pay/errors/LocalizedMessageService.java as @Service with constructor injection of MessageSource.; Implement String resolve(String errorCode, Locale locale): looks up key error.{errorCode.toLowerCase()} from MessageSource; returns errorCode itself if key not found (graceful fallback, no exception).; Add overload String resolve(String errorCode) using LocaleContextHolder.getLocale() as locale.; Add Javadoc: Partner API responses always use ErrorCode.name() string (not localised message); localised messages are portal display only.
**Deliverable:** libs/lib-errors/src/main/java/com/gme/pay/errors/ErrorCode.java and LocalizedMessageService.java
**Acceptance / logic checks:**
- ErrorCode enum has exactly 8 values
- resolve(INSUFFICIENT_PREFUNDING, Locale.KOREAN) returns non-empty Korean string when messages_ko.properties has key error.insufficient_prefunding
- resolve(SCHEME_UNAVAILABLE, Locale.ENGLISH) returns non-empty English string
- resolve(NONEXISTENT_CODE, Locale.ENGLISH) returns NONEXISTENT_CODE (no exception)
- LocalizedMessageService is a @Service bean injectable in a Spring Boot test slice
**Depends on:** 2.6-T04, 2.6-T05

### 2.6-T11 — Add CurrencyScaleValidator to lib-money: reject fractional KRW in rate engine output  _(40 min)_
**Context:** NFR-10 §9.1 mandates KRW amounts are whole numbers (scale=0). The rate engine (services/rate-fx, RateEngine.java) computes amounts via the 5-step RECEIVE-mode formula using BigDecimal. A guard must fire after steps 1 and 4 on KRW outputs. CurrencyScaleValidator.assertScale(MoneyAmount) throws MoneyScaleException if the rounded amount still has fractional digits beyond CurrencyConfig.scale(). Module: lib-money (validator) + call sites in services/rate-fx.
**Steps:** Create libs/lib-money/src/main/java/com/gme/pay/money/CurrencyScaleValidator.java with static void assertScale(MoneyAmount amount) that calls amount.round() and checks the result has no fractional part beyond CurrencyConfig.scale(); throws MoneyScaleException if violated.; Add libs/lib-errors/src/main/java/com/gme/pay/errors/MoneyScaleException.java extending RuntimeException; include the currency code and actual scale in the message.; In services/rate-fx/src/main/java/com/gme/pay/ratefx/RateEngine.java, call assertScale on target_payout (after step 1) and on send_amount (after step 4) when the respective currency scale=0.; Add unit test: RateEngine with KRW payout_ccy and fractional target_payout (e.g. 1000.50 KRW) throws MoneyScaleException.; Add unit test: RateEngine with USD payout_ccy and target_payout 10.25 USD passes assertScale without exception.
**Deliverable:** libs/lib-money/src/main/java/com/gme/pay/money/CurrencyScaleValidator.java, libs/lib-errors/.../MoneyScaleException.java, updated services/rate-fx/.../RateEngine.java
**Acceptance / logic checks:**
- assertScale(MoneyAmount(KRW, 1000.50)) throws MoneyScaleException
- assertScale(MoneyAmount(KRW, 1001.00)) passes
- assertScale(MoneyAmount(USD, 10.256)) passes (USD allows 2 decimals; 10.256 rounds to 10.26 which is valid)
- MoneyScaleException message contains the currency code
- RateEngine unit test confirms the guard fires at the correct formula step
**Depends on:** 2.6-T01, 2.6-T02

### 2.6-T12 — Add CurrencyDisplayFormatter to lib-money: locale-aware monetary amount display with per-currency decimal places  _(30 min)_
**Context:** ops-bff and partner-bff display monetary amounts to users. Per NFR-10 §9.1 and UX notes: amounts display the ISO code and respect per-currency decimal places (KRW 1,000 not KRW 1,000.00; USD 10.25 not USD 10.3). Thousands-separator is comma for both ko-KR and en-US. Module: lib-money.
**Steps:** Create libs/lib-money/src/main/java/com/gme/pay/money/CurrencyDisplayFormatter.java as a final utility class with static methods only.; Implement static String format(MoneyAmount amount): look up CurrencyConfig.scale() for the currency; apply DecimalFormat with groupingSize=3, comma separator, and scale decimal places.; KRW example pattern: #,##0 with scale=0 produces 1,000,000 KRW. USD example pattern: #,##0.00 with scale=2 produces 10,234.56 USD. Output format: {FORMATTED_AMOUNT} {ISO_CODE}.; Add overload format(MoneyAmount amount, Locale locale) reserved for future use; in Phase 1 delegates to the single-arg overload.; Add Javadoc: rates use RateDisplayFormatter (separate class); this formatter is for monetary amounts only.
**Deliverable:** libs/lib-money/src/main/java/com/gme/pay/money/CurrencyDisplayFormatter.java
**Acceptance / logic checks:**
- format(MoneyAmount(KRW, 1000000)) returns 1,000,000 KRW
- format(MoneyAmount(KRW, 1001).round()) returns 1,001 KRW (no decimal)
- format(MoneyAmount(USD, 10234.56)) returns 10,234.56 USD
- format(MoneyAmount(USD, 10.2).round()) returns 10.20 USD (padded to scale=2)
- format(MoneyAmount(KRW, 0)) returns 0 KRW without exception
**Depends on:** 2.6-T01, 2.6-T02

### 2.6-T13 — Add RateDisplayFormatter to lib-money: 6-significant-figure formatting for offer_rate and cross_rate  _(30 min)_
**Context:** Per UX spec: rates are displayed to 6 significant figures. The rate engine produces offer_rate_coll = send_amount / (collection_usd - collection_margin_usd) and cross_rate = target_payout / send_amount as BigDecimal. RateDisplayFormatter formats these for portal display with thousands separators where integer part exceeds 999. Module: lib-money.
**Steps:** Create libs/lib-money/src/main/java/com/gme/pay/money/RateDisplayFormatter.java as a final utility class with static methods.; Implement static String formatRate(BigDecimal rate): apply new BigDecimal(rate, new MathContext(6, RoundingMode.HALF_UP)) to get 6 sig figs; format with comma separator in integer part if > 999; preserve trailing zeros to show exactly 6 significant digits.; Add overload formatRate(BigDecimal rate, int sigFigs) for future flexibility; current calls use default of 6.; Ensure the input BigDecimal is not mutated; return a new String.; Handle negative rates (sign preserved in output; used for display only).
**Deliverable:** libs/lib-money/src/main/java/com/gme/pay/money/RateDisplayFormatter.java
**Acceptance / logic checks:**
- formatRate(new BigDecimal(1234.56789)) returns 1,234.57
- formatRate(new BigDecimal(0.001234567)) returns 0.00123457
- formatRate(new BigDecimal(1.0)) returns 1.00000 (6 sig figs with trailing zeros)
- formatRate(new BigDecimal(-0.5)) returns -0.500000 (sign preserved)
- Input BigDecimal object is not mutated by the method call
**Depends on:** 2.6-T01

### 2.6-T14 — Add LocaleHeaderInterceptor to ops-bff: set LocaleContextHolder from X-Locale header per request  _(40 min)_
**Context:** The Gateway sets X-Locale header (2.6-T09). ops-bff (Spring Boot) must read this header and set LocaleContextHolder so that @Service and @RestController callers using LocalizedMessageService get the correct locale automatically via the implicit overload. Default fallback: ko-KR (Admin Portal primary language per NFR-10 §9.2). Module: services/ops-bff.
**Steps:** Create services/ops-bff/src/main/java/com/gme/pay/opsbff/interceptor/LocaleHeaderInterceptor.java implementing HandlerInterceptor.; In preHandle(): read header X-Locale; parse to Locale via Locale.forLanguageTag(); fall back to ko-KR if header absent or value does not match [ko-KR, en-US]; call LocaleContextHolder.setLocale(resolved).; In afterCompletion(): call LocaleContextHolder.resetLocaleContext() to prevent thread-local leak.; Register in services/ops-bff/src/main/java/com/gme/pay/opsbff/config/I18nWebConfig.java (WebMvcConfigurer) via addInterceptors().; Write a @WebMvcTest that injects LocalizedMessageService and sends requests with X-Locale: en-US and X-Locale: ko-KR to a test endpoint; assert correct locale message in response.
**Deliverable:** services/ops-bff/src/main/java/com/gme/pay/opsbff/interceptor/LocaleHeaderInterceptor.java and I18nWebConfig.java
**Acceptance / logic checks:**
- X-Locale: ko-KR sets LocaleContextHolder to ko-KR during the request
- X-Locale: en-US sets LocaleContextHolder to en-US
- Absent X-Locale header falls back to ko-KR
- afterCompletion resets locale context (no thread-local leak)
- @WebMvcTest with X-Locale: en-US returns English message text from LocalizedMessageService
**Depends on:** 2.6-T04, 2.6-T09, 2.6-T10

### 2.6-T15 — Add LocaleHeaderInterceptor to partner-bff: set LocaleContextHolder from X-Locale header per request  _(35 min)_
**Context:** Same locale-propagation pattern as ops-bff (2.6-T14) but for services/partner-bff. Default fallback locale is en-US (Partner Portal primary language is English per NFR-10 §9.2). Module: services/partner-bff.
**Steps:** Create services/partner-bff/src/main/java/com/gme/pay/partnerbff/interceptor/LocaleHeaderInterceptor.java implementing HandlerInterceptor.; In preHandle(): read X-Locale header; fall back to en-US if absent or unsupported; call LocaleContextHolder.setLocale(resolved).; In afterCompletion(): call LocaleContextHolder.resetLocaleContext().; Register in services/partner-bff/src/main/java/com/gme/pay/partnerbff/config/I18nWebConfig.java via addInterceptors().; Write @WebMvcTest asserting X-Locale: ko-KR returns Korean message and absent header returns English message.
**Deliverable:** services/partner-bff/src/main/java/com/gme/pay/partnerbff/interceptor/LocaleHeaderInterceptor.java and I18nWebConfig.java
**Acceptance / logic checks:**
- X-Locale: ko-KR resolves to Korean locale in LocaleContextHolder
- Absent X-Locale defaults to en-US (not ko-KR)
- afterCompletion resets locale context (no thread-local leak)
- @WebMvcTest confirms English message when X-Locale: en-US
- @WebMvcTest confirms Korean message when X-Locale: ko-KR
**Depends on:** 2.6-T05, 2.6-T09, 2.6-T10

### 2.6-T16 — Expose GET /v1/config/locales and GET /v1/config/currencies in config-registry service  _(45 min)_
**Context:** ops-bff and partner-bff enumerate supported locales and currencies for UI dropdowns. config-registry (services/config-registry) owns locale_config and supported_currency tables (V201). Expose two read-only @RestController endpoints returning active rows. DTOs defined in lib-api-contracts. These are internal BFF-to-service calls; no partner auth required. Module: services/config-registry.
**Steps:** Add LocaleConfigDto(String localeCode, String displayNameEn, String displayNameKo, boolean isDefault) and SupportedCurrencyDto(String isoCode, int scale, String displaySymbol) records to libs/lib-api-contracts/src/main/java/com/gme/pay/api/dto/config/.; Create LocaleConfigRepository and SupportedCurrencyRepository extending JpaRepository in services/config-registry.; Create services/config-registry/src/main/java/com/gme/pay/config/controller/I18nConfigController.java annotated @RestController @RequestMapping(/v1/config).; Implement GET /locales: query WHERE active=TRUE ORDER BY is_default DESC, locale_code; return List<LocaleConfigDto>.; Implement GET /currencies: query WHERE active=TRUE ORDER BY iso_code; return List<SupportedCurrencyDto>.
**Deliverable:** libs/lib-api-contracts/.../dto/config/LocaleConfigDto.java, SupportedCurrencyDto.java; services/config-registry/.../I18nConfigController.java; LocaleConfigRepository.java; SupportedCurrencyRepository.java
**Acceptance / logic checks:**
- GET /v1/config/locales returns HTTP 200 with 2 items after V201 migration
- ko-KR item has isDefault=true in JSON response
- GET /v1/config/currencies returns HTTP 200 with 4 items
- KRW item has scale=0 in JSON response
- Testcontainers integration test: Postgres 16 container with Flyway migrations; endpoints return correct JSON
**Depends on:** 2.6-T03, 2.6-T08

### 2.6-T17 — Cache /v1/config/locales and /v1/config/currencies in Redis 7 with 300-second TTL in config-registry  _(45 min)_
**Context:** Locale and currency config is static in Phase 1. Reading PostgreSQL on every BFF request is wasteful. config-registry caches these responses in Redis 7 using Spring Cache @Cacheable with TTL=300s. Cache keys: i18n:locales and i18n:currencies. Redis is shared infra (docker-compose: redis:7). On cache miss or Redis unavailability, fall back to the database query. Module: services/config-registry.
**Steps:** Add spring-boot-starter-data-redis and spring-boot-starter-cache to services/config-registry/build.gradle dependencies.; Create services/config-registry/src/main/java/com/gme/pay/config/config/CacheConfig.java: @EnableCaching @Configuration defining a RedisCacheManager @Bean with default TTL 300s and cache names [i18n:locales, i18n:currencies].; Annotate the service methods (LocaleConfigService.findAllActive() and SupportedCurrencyService.findAllActive()) with @Cacheable(cacheNames=i18n:locales) and @Cacheable(cacheNames=i18n:currencies).; Add POST /v1/config/cache/evict endpoint (internal use) with @CacheEvict(allEntries=true, cacheNames={i18n:locales, i18n:currencies}) to allow Ops to bust cache after config change.; Configure RedisCacheManager with CacheErrorHandler that logs and falls back to DB on Redis failure; do not let a Redis outage block the endpoint.
**Deliverable:** services/config-registry/src/main/java/com/gme/pay/config/config/CacheConfig.java, updated service classes with @Cacheable, cache evict endpoint
**Acceptance / logic checks:**
- Second call to GET /v1/config/locales does not hit the JPA repository (verified via Mockito spy in Testcontainers test)
- Redis key i18n:locales exists with TTL <= 300s after first call (checked via RedisTemplate in test)
- POST /v1/config/cache/evict causes next GET to re-query the database
- When Redis container is stopped during test, endpoint still returns HTTP 200 from DB fallback
- Testcontainers test uses both Postgres 16 and Redis 7 containers
**Depends on:** 2.6-T16

### 2.6-T18 — Add @ValidCurrency Bean Validation annotation to lib-money: validate ISO 4217 currency codes on DTO fields  _(40 min)_
**Context:** Multiple API endpoints accept currency code strings from partners (RateQuoteRequest.collectionCurrency, payoutCurrency; PaymentRequest.settlementCurrency). These must be validated against active CurrencyRegistry entries at request time. A custom Jakarta Validation annotation @ValidCurrency in lib-money provides reusable field-level validation. CurrencyValidator is Spring-managed so it can inject CurrencyRegistry. Module: lib-money.
**Steps:** Create libs/lib-money/src/main/java/com/gme/pay/money/validation/ValidCurrency.java as @Constraint(validatedBy=CurrencyValidator.class) annotation with message attribute defaulting to error.invalid_currency.; Create libs/lib-money/src/main/java/com/gme/pay/money/validation/CurrencyValidator.java as @Component implementing ConstraintValidator<ValidCurrency, String>; isValid() returns false for null; calls CurrencyRegistry.getCurrency(value) and returns true if found and active, false if UnknownCurrencyException.; Apply @ValidCurrency to collectionCurrency and payoutCurrency fields in libs/lib-api-contracts RateQuoteRequest and to settlementCurrency in PaymentRequest.; Write a JUnit 5 unit test using a Validator: @ValidCurrency on KRW passes; on ZZZ fails; on null fails.; Write a @WebMvcTest that POSTs a RateQuoteRequest with collectionCurrency=ZZZ; assert HTTP 400 response with error code INVALID_CURRENCY.
**Deliverable:** libs/lib-money/src/main/java/com/gme/pay/money/validation/ValidCurrency.java, CurrencyValidator.java, updated DTO fields in lib-api-contracts
**Acceptance / logic checks:**
- Validator.validate(RateQuoteRequest with collectionCurrency=KRW) returns empty violation set
- Validator.validate(RateQuoteRequest with collectionCurrency=ZZZ) returns one violation with message error.invalid_currency
- Validator.validate(RateQuoteRequest with collectionCurrency=null) returns a violation
- @WebMvcTest POST with unknown currency returns HTTP 400
- CurrencyValidator injected as Spring bean; no NPE when Spring context initializes
**Depends on:** 2.6-T01, 2.6-T10

### 2.6-T19 — Add Utf8ContentTypeFilter GlobalFilter to Spring Cloud Gateway: enforce charset=UTF-8 on JSON responses  _(30 min)_
**Context:** Per NFR-10 §9.2, Korean regulatory fields use UTF-8. All API JSON responses from the gateway must include Content-Type: application/json; charset=UTF-8. This prevents client charset negotiation issues and is required for BOK reporting fields containing Korean characters. Module: services/gateway.
**Steps:** Create services/gateway/src/main/java/com/gme/pay/gateway/filter/Utf8ContentTypeFilter.java implementing GlobalFilter, Ordered.; In filter(), apply to the response: if response Content-Type is application/json but lacks charset, set it to MediaType.APPLICATION_JSON with UTF-8 charset.; Use exchange.getResponse().getHeaders() to read and set Content-Type.; Set order to Ordered.LOWEST_PRECEDENCE - 10 (runs after routing, modifies response headers before flush).; Register as a @Bean in GatewayFilterConfig.java alongside LocaleHeaderFilter.
**Deliverable:** services/gateway/src/main/java/com/gme/pay/gateway/filter/Utf8ContentTypeFilter.java, updated GatewayFilterConfig.java
**Acceptance / logic checks:**
- Response with Content-Type: application/json is modified to application/json;charset=UTF-8
- Response already containing charset=UTF-8 is not double-modified
- Response with Content-Type: text/plain is not modified by this filter
- Filter order is lower than LocaleHeaderFilter order (runs later in the chain)
- Unit test using MockServerWebExchange passes for all three cases above
**Depends on:** 2.6-T09

### 2.6-T20 — Complete message bundle keys for all error codes and payment status labels in ops-bff and partner-bff  _(35 min)_
**Context:** 2.6-T04 and 2.6-T05 created bundles with 10 seed keys. All ErrorCode enum values (2.6-T10: INSUFFICIENT_PREFUNDING, SCHEME_UNAVAILABLE, PARTNER_B_QUOTE_DEVIATION, PARTNER_B_QUOTE_UNAVAILABLE, RATE_QUOTE_EXPIRED, INVALID_CURRENCY, POOL_IDENTITY_BREACH, RATE_LOCK_VIOLATION) plus payment status labels (payment.status.pending, payment.status.committed, payment.status.failed, payment.status.uncertain, payment.status.reversed) must now be in all four properties files with meaningful translations. Module: services/ops-bff and services/partner-bff.
**Steps:** Add all 8 error.* keys (one per ErrorCode) to services/ops-bff/src/main/resources/i18n/messages_ko.properties with Korean text.; Add the same 8 error.* keys to services/ops-bff/src/main/resources/i18n/messages_en.properties with English text.; Add all 5 payment.status.* keys to both ops-bff properties files.; Mirror all 13 new keys in both services/partner-bff/src/main/resources/i18n/messages_ko.properties and messages_en.properties.; Confirm no duplicate keys exist in any file and no Korean-text key in a _ko file is accidentally English.
**Deliverable:** Updated messages_ko.properties and messages_en.properties in services/ops-bff/src/main/resources/i18n/ and services/partner-bff/src/main/resources/i18n/
**Acceptance / logic checks:**
- All 8 error.* keys present in all 4 properties files
- All 5 payment.status.* keys present in all 4 properties files
- messages_ko.properties files contain Korean characters for all added keys (no English placeholder)
- No duplicate keys in any properties file
- messageSource.getMessage on every added key in both locales returns a non-empty non-key string
**Depends on:** 2.6-T04, 2.6-T05, 2.6-T10

### 2.6-T21 — Unit tests for CurrencyConfig, MoneyAmount, CurrencyScaleValidator, CurrencyDisplayFormatter, RateDisplayFormatter in lib-money  _(50 min)_
**Context:** lib-money is a foundational shared module. JUnit 5 unit tests must cover the KRW zero-decimal invariant, USD 2-decimal rounding, BigDecimal pool-identity arithmetic, display formatting, and rate formatting. No Spring context needed. Target: >=80% line coverage for lib-money (NFR-10 §8.2, N-15). Module: lib-money test.
**Steps:** Create CurrencyConfigTest.java: getCurrency(KRW).scale()==0, getCurrency(USD).scale()==2, getCurrency(ZZZ) throws UnknownCurrencyException.; Create MoneyAmountTest.java: round() for KRW 1000.5->1001, USD 10.256->10.26; add() does not auto-round; multiply(0)->zero; subtract preserves BigDecimal identity.; Create CurrencyScaleValidatorTest.java: assertScale(KRW, 1000.50) throws; assertScale(KRW, 1001.00) passes; assertScale(USD, 10.26) passes.; Create CurrencyDisplayFormatterTest.java: format(KRW 1000000)=1,000,000 KRW; format(USD 10.2)=10.20 USD; format(KRW 0)=0 KRW.; Create RateDisplayFormatterTest.java: formatRate(1234.56789)=1,234.57; formatRate(0.001234567)=0.00123457; formatRate(1.0)=1.00000.
**Deliverable:** libs/lib-money/src/test/java/com/gme/pay/money/ test classes: CurrencyConfigTest.java, MoneyAmountTest.java, CurrencyScaleValidatorTest.java, CurrencyDisplayFormatterTest.java, RateDisplayFormatterTest.java
**Acceptance / logic checks:**
- ./gradlew :lib-money:test passes with 0 failures
- At least 20 test cases across 5 test classes
- KRW zero-decimal invariant tested with at least 2 vectors (whole and fractional)
- USD 2-decimal rounding tested with at least 2 vectors
- All edge cases (zero amount, boundary scale) have explicit assertions
**Depends on:** 2.6-T01, 2.6-T02, 2.6-T11, 2.6-T12, 2.6-T13

### 2.6-T22 — Unit tests for TimestampFormatter: UTC-to-KST conversion, ISO-8601 round-trip, and parseApi error case  _(30 min)_
**Context:** TimestampFormatter (2.6-T06) is a pure utility class in lib-money. JUnit 5 tests must cover: UTC-to-KST offset (UTC+9, no DST), ISO-8601 API string format, parse-format round-trip, arbitrary timezone (e.g. UTC+5:30), and invalid input handling. Module: lib-money test.
**Steps:** Create libs/lib-money/src/test/java/com/gme/pay/money/TimestampFormatterTest.java.; Test formatForDisplay(Instant.parse(2026-06-05T05:00:00Z), KST): assert result contains 14:00 and KST (UTC+9 applied correctly).; Test formatForDisplay(Instant.parse(2026-06-05T15:30:00Z), ZoneOffset.UTC): assert result contains 15:30 and UTC.; Test toApiString(Instant.now()) ends with Z.; Test parseApi(toApiString(Instant.parse(2026-01-01T00:00:00Z))) returns the original Instant. Test parseApi(invalid-string) throws DateTimeParseException.
**Deliverable:** libs/lib-money/src/test/java/com/gme/pay/money/TimestampFormatterTest.java
**Acceptance / logic checks:**
- 2026-06-05T05:00:00Z converted to KST shows 14:00 (UTC+9 offset correctly applied)
- toApiString output ends with Z
- Round-trip parse yields original Instant
- Invalid date string throws DateTimeParseException (not NullPointerException or silent failure)
- ./gradlew :lib-money:test passes including these new cases
**Depends on:** 2.6-T06

### 2.6-T23 — Unit tests for LocaleHeaderFilter and Utf8ContentTypeFilter in services/gateway using MockServerWebExchange  _(40 min)_
**Context:** LocaleHeaderFilter (2.6-T09) and Utf8ContentTypeFilter (2.6-T19) are Spring Cloud Gateway GlobalFilters. Tests use Spring WebFlux MockServerWebExchange. No Testcontainers needed. Cover all path and Accept-Language permutations plus the UTF-8 charset injection. Module: services/gateway test.
**Steps:** Create services/gateway/src/test/java/com/gme/pay/gateway/filter/LocaleHeaderFilterTest.java using JUnit 5 and MockServerWebExchange.; Test /admin + Accept-Language: ko-KR -> X-Locale: ko-KR in mutated request.; Test /partner + no Accept-Language -> X-Locale: en-US.; Test /admin + Accept-Language: fr-FR -> X-Locale: ko-KR (unsupported fallback).; Create Utf8ContentTypeFilterTest.java: response with Content-Type: application/json -> modified to application/json;charset=UTF-8; response with Content-Type: text/plain -> unchanged.
**Deliverable:** services/gateway/src/test/java/com/gme/pay/gateway/filter/LocaleHeaderFilterTest.java and Utf8ContentTypeFilterTest.java
**Acceptance / logic checks:**
- All 3 LocaleHeaderFilter cases pass
- Both Utf8ContentTypeFilter cases pass
- No Testcontainers required (pure WebFlux mock)
- ./gradlew :gateway:test passes with 0 failures
- Test classes have no Spring application context dependency (unit-level)
**Depends on:** 2.6-T09, 2.6-T19

### 2.6-T24 — Integration test for I18nConfigController in config-registry using Testcontainers Postgres 16 and Redis 7  _(45 min)_
**Context:** I18nConfigController (2.6-T16) and its Redis cache (2.6-T17) need integration testing against real Postgres 16 and Redis 7. Use @Testcontainers with both containers; apply Flyway migrations; call /v1/config/locales and /v1/config/currencies via MockMvc; verify cache behaviour using RedisTemplate. Module: services/config-registry test.
**Steps:** Create services/config-registry/src/test/java/com/gme/pay/config/controller/I18nConfigControllerIntegrationTest.java annotated @SpringBootTest, @AutoConfigureMockMvc, @Testcontainers.; Declare static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(postgres:16) and static GenericContainer<?> redis = new GenericContainer<>(redis:7).withExposedPorts(6379).; Set datasource and Redis properties in @DynamicPropertySource from the containers.; Test GET /v1/config/locales: HTTP 200, 2 items, ko-KR has isDefault=true. Test GET /v1/config/currencies: HTTP 200, 4 items, KRW.scale=0.; Test cache: call GET /v1/config/locales twice; inject LocaleConfigRepository spy; assert repository was invoked only once (cache hit on second call).
**Deliverable:** services/config-registry/src/test/java/com/gme/pay/config/controller/I18nConfigControllerIntegrationTest.java
**Acceptance / logic checks:**
- GET /v1/config/locales returns 200 with 2 items
- ko-KR item has isDefault=true
- GET /v1/config/currencies returns 200 with 4 items, KRW scale=0
- Repository invoked once across 2 endpoint calls (cache hit verified)
- Test runs using real Flyway V201 migration applied by Spring Boot on startup
**Depends on:** 2.6-T16, 2.6-T17

### 2.6-T25 — Integration test for LocaleHeaderInterceptor in ops-bff: end-to-end X-Locale to message resolution  _(40 min)_
**Context:** LocaleHeaderInterceptor (2.6-T14) in ops-bff must be verified end-to-end: HTTP request with X-Locale header -> LocaleContextHolder set -> LocalizedMessageService returns correct locale message. Use @WebMvcTest with a minimal test controller. No Testcontainers required. Module: services/ops-bff test.
**Steps:** Create services/ops-bff/src/test/java/com/gme/pay/opsbff/interceptor/LocaleInterceptorIntegrationTest.java with @WebMvcTest.; Add a test-scope @RestController (TestMessageController) in the test source tree with GET /test/message that calls LocalizedMessageService.resolve(INSUFFICIENT_PREFUNDING) and returns the result as plain text.; Test 1: GET /test/message with X-Locale: ko-KR -> assert response body is Korean text (not the error code string INSUFFICIENT_PREFUNDING).; Test 2: GET /test/message with X-Locale: en-US -> assert response body is English text.; Test 3: GET /test/message with no X-Locale -> assert response body is Korean text (default ko-KR).
**Deliverable:** services/ops-bff/src/test/java/com/gme/pay/opsbff/interceptor/LocaleInterceptorIntegrationTest.java
**Acceptance / logic checks:**
- X-Locale: ko-KR returns Korean text for INSUFFICIENT_PREFUNDING
- X-Locale: en-US returns English text for INSUFFICIENT_PREFUNDING
- No X-Locale falls back to ko-KR (Korean text returned)
- Test uses @WebMvcTest (no DB or Testcontainers)
- ./gradlew :ops-bff:test passes with 0 failures
**Depends on:** 2.6-T14, 2.6-T20

### 2.6-T26 — Add i18n, currency handling, and timezone sections to ops-bff and partner-bff READMEs  _(30 min)_
**Context:** Per NFR-10 §8.3, each service must have a README covering purpose, dependencies, local run, and environment variable reference. The i18n framework adds locale resolution, message bundle structure, and timezone configuration. This ticket adds or updates the README for ops-bff and partner-bff with these sections. Do not create a new standalone file; append to or create a minimal README if one does not exist.
**Steps:** Locate or create services/ops-bff/README.md; add section I18n and Localisation documenting: supported locales (ko-KR default, en-US), X-Locale gateway header, message bundle paths (src/main/resources/i18n/messages_ko.properties, messages_en.properties), how to add a new message key, and note that internal timestamps are UTC stored and KST displayed.; Locate or create services/partner-bff/README.md; add section I18n and Localisation documenting: supported locales (en-US default, ko-KR optional), partner display_timezone column (default Asia/Seoul), X-Locale header, bundle file paths.; Add Currency Handling sub-section in both READMEs: ISO 4217 codes; KRW scale=0 (no fractional KRW output); use @ValidCurrency on DTO currency fields; DECIMAL(20,8) PostgreSQL storage.; Add Timezone sub-section in both READMEs: UTC storage everywhere; KST = Asia/Seoul (UTC+9, no DST); KST used for batch window deadlines (NFR-10 §2.3); partner timezone configurable via partners.display_timezone column.; Verify both README files are UTF-8 encoded and all section headings use Markdown ## or ### syntax.
**Deliverable:** services/ops-bff/README.md and services/partner-bff/README.md with I18n, Currency Handling, and Timezone sections
**Acceptance / logic checks:**
- ops-bff README documents default locale as ko-KR
- partner-bff README documents default locale as en-US
- Both READMEs mention the X-Locale gateway header
- Both READMEs mention KRW scale=0 and the @ValidCurrency annotation
- Both READMEs mention UTC storage and KST display with Asia/Seoul timezone identifier
**Depends on:** 2.6-T14, 2.6-T15, 2.6-T18


## WBS 3.1 — Database design & ERD implementation
### 3.1-T01 — Create Flyway baseline migration V001 and Gradle module structure for db migrations  _(30 min)_
**Context:** GMEPay+ uses a Gradle multi-module monorepo. All PostgreSQL 16 DDL is managed via Flyway migrations in a dedicated module. The Flyway naming convention is V{nnn}__{description}.sql. Migrations live in db-migrations/src/main/resources/db/migration/. A shared lib-persistence module provides common Flyway config beans. No floating-point types permitted; use NUMERIC/DECIMAL for money. All table and column names snake_case. BIGINT surrogate auto-increment PK on every table. TIMESTAMPTZ for all timestamps. Enums stored as VARCHAR with CHECK constraint (not DB enum type). ON DELETE RESTRICT default for all FKs. Every FK column must be indexed.
**Steps:** Add db-migrations subproject to root settings.gradle with path :db-migrations; Create db-migrations/build.gradle with flyway-core 10.x and postgresql 42.x JDBC driver dependencies; Create directory db-migrations/src/main/resources/db/migration/; Create V001__baseline_schema_conventions.sql with comment-only migration documenting naming, money type, and timestamp conventions as per DAT-03 sec 1.3 and sec 2; Verify Flyway can connect to PostgreSQL 16 on localhost:5433 by running ./gradlew :db-migrations:flywayInfo
**Deliverable:** db-migrations/build.gradle, db-migrations/src/main/resources/db/migration/V001__baseline_schema_conventions.sql, entry in settings.gradle
**Acceptance / logic checks:**
- ./gradlew :db-migrations:flywayInfo reports current version without error on local Postgres 16 port 5433
- V001 migration file exists and contains only comments (no DDL); Flyway marks it as success
- settings.gradle includes :db-migrations include statement
- No FLOAT or DOUBLE type appears in any migration file (grep check)

### 3.1-T02 — Create V002 migration: qr_scheme and scheme_country tables  _(35 min)_
**Context:** qr_scheme represents a QR payment network (e.g. ZEROPAY operated by KFTC). scheme_country maps active schemes to ISO 3166-1 alpha-2 country codes for CPM scheme selection. qr_scheme columns per DAT-03 sec 4.1: BIGINT PK, scheme_code VARCHAR(30) UNIQUE, payout_ccy CHAR(3), supported_modes VARCHAR(20) CHECK IN ('MPM','CPM','BOTH'), fee columns DECIMAL(6,4), partner_b_quote_deviation_pct DECIMAL(6,4) DEFAULT 0.0100, gme_fee_share_pct DECIMAL(6,4), status VARCHAR(20) CHECK IN ('TESTING','ACTIVE','INACTIVE'), is_active BOOLEAN NOT NULL DEFAULT TRUE, sftp_host/sftp_path_outbound/sftp_path_inbound/api_endpoint/api_credential_ref, standard audit cols (created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL, created_by VARCHAR(120), updated_by VARCHAR(120)). scheme_country: BIGINT PK, scheme_id BIGINT FK->qr_scheme ON DELETE RESTRICT, country_code CHAR(2), is_active BOOLEAN, audit cols; UNIQUE(scheme_id, country_code). Index every FK column.
**Steps:** Create db-migrations/src/main/resources/db/migration/V002__qr_scheme_and_scheme_country.sql; Add CREATE TABLE qr_scheme with all columns per DAT-03 sec 4.1; include CHECK constraints for supported_modes and status; Add CREATE TABLE scheme_country with FK, UNIQUE(scheme_id, country_code), index on scheme_id; Run ./gradlew :db-migrations:flywayMigrate against local Postgres 16 to verify clean apply; Verify with psql: \d qr_scheme and \d scheme_country show correct column types
**Deliverable:** db-migrations/src/main/resources/db/migration/V002__qr_scheme_and_scheme_country.sql
**Acceptance / logic checks:**
- Migration applies cleanly with exit 0 on Postgres 16
- qr_scheme.supported_modes CHECK IN ('MPM','CPM','BOTH') rejects 'INVALID' insert
- scheme_country UNIQUE(scheme_id, country_code) rejects duplicate insert
- All FK columns have indexes (check pg_indexes)
- No FLOAT/REAL/DOUBLE column types present
**Depends on:** 3.1-T01

### 3.1-T03 — Create V003 migration: partner and partner_credential tables  _(35 min)_
**Context:** partner stores payment app configurations. Columns per DAT-03 sec 4.3 and sec 10.8: BIGINT PK, partner_code VARCHAR(30) UNIQUE NOT NULL, name VARCHAR(100) NOT NULL, partner_type VARCHAR(10) NOT NULL CHECK IN ('LOCAL','OVERSEAS'), collection_ccy CHAR(3) NOT NULL, settle_a_ccy CHAR(3) NOT NULL, webhook_url VARCHAR(512) NOT NULL, rate_quote_ttl_seconds INT NOT NULL DEFAULT 300 CHECK BETWEEN 60 AND 1800, low_balance_threshold_usd DECIMAL(20,4) NULL (NULL for LOCAL partners), low_balance_alert_email VARCHAR(255) NULL, status VARCHAR(20) NOT NULL CHECK IN ('ONBOARDING','ACTIVE','SUSPENDED','INACTIVE'), is_active BOOLEAN NOT NULL DEFAULT FALSE, standard audit cols. partner_credential: BIGINT PK, partner_id BIGINT FK->partner ON DELETE RESTRICT, api_key VARCHAR(64) UNIQUE, api_secret_hash VARCHAR(128) (bcrypt hash), is_active BOOLEAN, expires_at TIMESTAMPTZ NULL, audit cols; only one active credential per partner enforced via partial unique index.
**Steps:** Create V003__partner_and_partner_credential.sql; Add CREATE TABLE partner with all columns, CHECK constraints, and NOT NULL defaults per DAT-03 sec 10.8; Add CREATE TABLE partner_credential with FK, api_key UNIQUE index, and partial unique index: CREATE UNIQUE INDEX uq_partner_credential_active ON partner_credential(partner_id) WHERE is_active = TRUE; Run migration against local Postgres 16 and verify with psql; Insert test row for LOCAL partner (GME_REMIT) and OVERSEAS partner (SENDMN) to confirm constraints
**Deliverable:** db-migrations/src/main/resources/db/migration/V003__partner_and_partner_credential.sql
**Acceptance / logic checks:**
- rate_quote_ttl_seconds CHECK BETWEEN 60 AND 1800 rejects value 30
- partner_type CHECK IN ('LOCAL','OVERSEAS') rejects 'AGENT'
- Partial unique index prevents two active credentials for same partner_id
- partner.is_active DEFAULT FALSE confirmed via \d+ partner
- LOCAL partner with NULL low_balance_threshold_usd accepted without error
**Depends on:** 3.1-T01

### 3.1-T04 — Create V004 migration: partner_webhook and treasury_rate tables  _(30 min)_
**Context:** partner_webhook stores webhook endpoint config per partner (primary + fallback) per DAT-03 sec 4.5: BIGINT PK, partner_id FK->partner, webhook_url VARCHAR(512), event_types TEXT, signing_secret_hash VARCHAR(128) (HMAC-SHA256 signing secret hashed), is_active BOOLEAN, audit cols. treasury_rate stores canonical FX rates in form usd_{ccy} = units of ccy per 1 USD (e.g. usd_krw=1380.00000000 means 1 USD = 1380 KRW). Columns per DAT-03 sec 4.8 and sec 10.3: BIGINT PK, ccy_pair VARCHAR(10) NOT NULL, rate DECIMAL(20,8) NOT NULL CHECK (rate > 0), source VARCHAR(10) NOT NULL CHECK IN ('LIVE','MANUAL'), effective_at TIMESTAMPTZ NOT NULL, audit cols. Latest rate query: WHERE ccy_pair='usd_krw' ORDER BY effective_at DESC LIMIT 1. Historical rows retained for BOK audit. Composite index on (ccy_pair, effective_at DESC).
**Steps:** Create V004__partner_webhook_and_treasury_rate.sql; Add CREATE TABLE partner_webhook with FK index on partner_id; Add CREATE TABLE treasury_rate with DECIMAL(20,8) rate column, CHECK (rate > 0), and composite index on (ccy_pair, effective_at DESC); Run migration and verify with psql; Insert treasury_rate rows for usd_krw=1380.00000000 and usd_mnt=3440.00000000 as test data to confirm 8dp precision
**Deliverable:** db-migrations/src/main/resources/db/migration/V004__partner_webhook_and_treasury_rate.sql
**Acceptance / logic checks:**
- treasury_rate.rate CHECK > 0 rejects insert of rate=0.0
- DECIMAL(20,8) stores 1380.00000000 with 8 decimal places without truncation
- Composite index on (ccy_pair, effective_at DESC) appears in pg_indexes
- treasury_rate.source CHECK IN ('LIVE','MANUAL') rejects 'XE_FEED'
- Multiple rows for same ccy_pair allowed (historical retention confirmed by successful dual insert)
**Depends on:** 3.1-T03

### 3.1-T05 — Create V005 migration: rule and service_charge_tier tables  _(40 min)_
**Context:** rule is the central config entity linking partner x scheme x direction with margin params. Columns per DAT-03 sec 4.6 and sec 10.2: BIGINT PK; partner_id FK->partner; scheme_id FK->qr_scheme; direction VARCHAR(10) NOT NULL CHECK IN ('INBOUND','OUTBOUND','DOMESTIC','HUB'); collection_ccy, settle_a_ccy, settle_b_ccy, payout_ccy CHAR(3) NOT NULL (auto-derived from partner/scheme, stored for query performance); rate_coll_source/rate_pay_source VARCHAR(10) CHECK IN ('IDENTITY','LIVE','MANUAL','PARTNER'); manual_rate_coll/manual_rate_pay DECIMAL(20,8) NULL; m_a DECIMAL(8,6) NOT NULL DEFAULT 0 CHECK >=0; m_b DECIMAL(8,6) NOT NULL DEFAULT 0 CHECK >=0; CHECK (is_same_ccy_shortcircuit = TRUE OR (m_a + m_b) >= 0.020000) for 2% minimum combined margin on cross-border; service_charge_amount DECIMAL(20,4) NOT NULL DEFAULT 0; service_charge_ccy CHAR(3) NOT NULL; is_same_ccy_shortcircuit BOOLEAN NOT NULL (computed: TRUE when collection=settle_a=settle_b=payout ccy); status VARCHAR(20) CHECK IN ('DRAFT','ACTIVE','SUSPENDED'); effective_from TIMESTAMPTZ NOT NULL; audit cols. UNIQUE(partner_id, scheme_id, direction). service_charge_tier: BIGINT PK, rule_id FK->rule, min_collection_usd DECIMAL(20,4), max_collection_usd DECIMAL(20,4) NULL (no cap), charge_amount DECIMAL(20,4), audit cols.
**Steps:** Create V005__rule_and_service_charge_tier.sql; Add CREATE TABLE rule with all columns; add UNIQUE(partner_id, scheme_id, direction) and CHECK (is_same_ccy_shortcircuit OR (m_a + m_b) >= 0.020000); Add FK indexes on partner_id, scheme_id; add index on status for active-rule lookups; Add CREATE TABLE service_charge_tier with FK->rule and index on rule_id; Run migration; verify CHECK constraint fires for cross-border rule with m_a=0.005, m_b=0.005 (sum=0.01 < 0.02); Verify same-ccy rule with is_same_ccy_shortcircuit=TRUE and m_a=m_b=0 is accepted
**Deliverable:** db-migrations/src/main/resources/db/migration/V005__rule_and_service_charge_tier.sql
**Acceptance / logic checks:**
- UNIQUE(partner_id, scheme_id, direction) rejects duplicate insert
- CHECK (m_a+m_b>=0.02) rejects cross-border rule insert with m_a=0.005, m_b=0.005
- Same-currency rule with is_same_ccy_shortcircuit=TRUE and m_a=m_b=0 is accepted
- service_charge_tier.max_collection_usd accepts NULL (no cap)
- rate_coll_source CHECK rejects 'REALTIME'
**Depends on:** 3.1-T03, 3.1-T02

### 3.1-T06 — Create V006 migration: rate_quote table  _(35 min)_
**Context:** rate_quote captures the output of the rate engine at GET /v1/rates or CPM pending_debit. All USD pool values and derived rates stored per DAT-03 sec 5.1. Key columns: quote_ref VARCHAR(64) UNIQUE; rule_id FK->rule; partner_id FK->partner; scheme_id FK->qr_scheme; payment_mode VARCHAR(5) CHECK IN ('MPM','CPM'); target_payout DECIMAL(20,4) NOT NULL; payout_ccy CHAR(3); USD pool fields (payout_usd_cost, collection_usd, collection_margin_usd, payout_margin_usd) DECIMAL(20,8) NULL (NULL for same-ccy short-circuit); send_amount DECIMAL(20,4) NOT NULL; send_amount_ccy CHAR(3); service_charge DECIMAL(20,4) NOT NULL; collection_amount DECIMAL(20,4) NOT NULL (= send_amount + service_charge); collection_ccy CHAR(3); offer_rate_coll DECIMAL(20,8) NULL (derived: send_amount / (collection_usd - collection_margin_usd); NULL same-ccy); cross_rate DECIMAL(20,8) NULL (derived: target_payout / send_amount); cost_rate_coll/cost_rate_pay DECIMAL(20,8) NULL; treasury_rate_id_coll/treasury_rate_id_pay BIGINT FK->treasury_rate NULL; quote_issued_at TIMESTAMPTZ NOT NULL; valid_until TIMESTAMPTZ NOT NULL (= quote_issued_at + rate_quote_ttl_seconds); is_used BOOLEAN NOT NULL DEFAULT FALSE; created_at/updated_at.
**Steps:** Create V006__rate_quote.sql; Add CREATE TABLE rate_quote with all columns and types exactly per DAT-03 sec 5.1; Add CHECK: payment_mode IN ('MPM','CPM'); Add indexes: (partner_id, quote_issued_at), (rule_id, is_used), (valid_until) for TTL expiry queries; Add FK indexes on rule_id, partner_id, scheme_id, treasury_rate_id_coll, treasury_rate_id_pay; Run migration and verify column nullability with psql \d rate_quote
**Deliverable:** db-migrations/src/main/resources/db/migration/V006__rate_quote.sql
**Acceptance / logic checks:**
- payout_usd_cost, collection_usd, collection_margin_usd, payout_margin_usd columns are nullable (confirmed \d rate_quote)
- offer_rate_coll and cross_rate are nullable
- quote_ref UNIQUE constraint rejects duplicate quote_ref insert
- payment_mode CHECK IN ('MPM','CPM') rejects 'QR'
- valid_until and quote_issued_at columns are TIMESTAMPTZ NOT NULL
**Depends on:** 3.1-T05, 3.1-T04

### 3.1-T07 — Create V007 migration: transaction table (central fact table)  _(45 min)_
**Context:** transaction is the central fact table. All rate fields are copied from rate_quote at commit and are immutable thereafter. Columns per DAT-03 sec 5.2 and sec 10.1: txn_ref VARCHAR(64) UNIQUE NOT NULL; hub_txn_ref VARCHAR(64) NOT NULL; scheme_ref VARCHAR(128) NULL (populated after scheme approval); partner_id/scheme_id/rule_id/rate_quote_id/merchant_id/qr_code_id BIGINT FK NOT NULL; payment_mode CHECK IN ('MPM','CPM'); direction CHECK IN ('INBOUND','OUTBOUND','DOMESTIC','HUB'); status VARCHAR(20) NOT NULL CHECK IN ('INITIATED','PREFUND_DEDUCTED','SUBMITTED','APPROVED','DECLINED','UNCERTAIN','FAILED','CANCELLED','REFUNDED'); target_payout DECIMAL(20,4) NOT NULL; payout_ccy/collection_ccy/settle_a_ccy/settle_b_ccy CHAR(3) NOT NULL; USD pool fields DECIMAL(20,8) NULL; send_amount/service_charge/collection_amount DECIMAL(20,4) NOT NULL; offer_rate_coll/cross_rate/cost_rate_coll/cost_rate_pay DECIMAL(20,8) NULL; committed_at/completed_at TIMESTAMPTZ NULL; is_same_ccy_shortcircuit BOOLEAN NOT NULL DEFAULT FALSE; prefunding_deducted_usd DECIMAL(20,4) NULL (OVERSEAS only); standard audit cols. Composite indexes: (partner_id, committed_at), (scheme_id, completed_at), (status, committed_at), index on scheme_ref, index on hub_txn_ref.
**Steps:** Create V007__transaction.sql; Add CREATE TABLE transaction with all columns and nullability per DAT-03 sec 10.1; Add CHECK constraints for payment_mode, direction, and all 9 status values; Add composite indexes: (partner_id, committed_at), (scheme_id, completed_at), (status, committed_at), (scheme_ref), (hub_txn_ref); Add all FK indexes (partner_id, scheme_id, rule_id, rate_quote_id, merchant_id, qr_code_id); Run migration; verify 9 status values accepted and 'PENDING' rejected
**Deliverable:** db-migrations/src/main/resources/db/migration/V007__transaction.sql
**Acceptance / logic checks:**
- txn_ref UNIQUE constraint confirmed
- status CHECK accepts all 9 values: INITIATED/PREFUND_DEDUCTED/SUBMITTED/APPROVED/DECLINED/UNCERTAIN/FAILED/CANCELLED/REFUNDED and rejects 'PENDING'
- collection_usd NULL accepted for is_same_ccy_shortcircuit=TRUE insert
- Composite index (partner_id, committed_at) appears in pg_indexes
- prefunding_deducted_usd is nullable (LOCAL=NULL, OVERSEAS=populated)
**Depends on:** 3.1-T06

### 3.1-T08 — Create V008 migration: transaction_event and refund tables  _(30 min)_
**Context:** transaction_event stores the 8-step event trail per transaction per DAT-03 sec 5.3. 8 steps per DAT-03 Assumption A3: (1) RATE_QUOTE_ISSUED, (2) PREFUND_DEDUCTED, (3) SCHEME_SUBMITTED, (4) SCHEME_APPROVED or SCHEME_DECLINED, (5) TRANSACTION_COMMITTED, (6) SETTLEMENT_BATCHED, (7) WEBHOOK_QUEUED, (8) WEBHOOK_DELIVERED. Columns: id BIGINT PK, txn_id BIGINT FK->transaction NOT NULL, step INT NOT NULL CHECK BETWEEN 1 AND 8, event_type VARCHAR(50) NOT NULL, occurred_at TIMESTAMPTZ NOT NULL, duration_ms INT NULL (milliseconds since previous step), detail JSONB NULL (step-specific payload), created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(). refund per DAT-03 sec 5.4: id BIGINT PK, txn_id FK->transaction, refund_ref VARCHAR(64) UNIQUE, refund_amount DECIMAL(20,4) NOT NULL (in payout_ccy of original txn), refund_ccy CHAR(3) NOT NULL, reason VARCHAR(255) NULL, status VARCHAR(20) CHECK IN ('PENDING','SUBMITTED','CONFIRMED','FAILED'), scheme_refund_ref VARCHAR(128) NULL, initiated_by VARCHAR(120), audit cols. Phase 1: refunds via Admin System only.
**Steps:** Create V008__transaction_event_and_refund.sql; Add CREATE TABLE transaction_event with step CHECK BETWEEN 1 AND 8, JSONB detail column, composite index on (txn_id, step); Add CREATE TABLE refund with all columns, refund_ref UNIQUE, status CHECK constraint, index on txn_id; Run migration and verify JSONB detail column accepts JSON payload; Verify step constraint rejects step=9 and step=0
**Deliverable:** db-migrations/src/main/resources/db/migration/V008__transaction_event_and_refund.sql
**Acceptance / logic checks:**
- transaction_event.step CHECK BETWEEN 1 AND 8 rejects step=9 and step=0
- detail JSONB column accepts JSON object payload
- refund.status CHECK IN ('PENDING','SUBMITTED','CONFIRMED','FAILED') rejects 'PROCESSING'
- refund_ref UNIQUE constraint confirmed
- Composite index on (txn_id, step) appears in pg_indexes
**Depends on:** 3.1-T07

### 3.1-T09 — Create V009 migration: prefunding_account and prefunding_ledger_entry tables  _(30 min)_
**Context:** prefunding_account: one row per OVERSEAS partner; balance maintained in USD; updated atomically via SELECT FOR UPDATE (no application-level locking). Columns per DAT-03 sec 6.1: id BIGINT PK, partner_id BIGINT FK->partner UNIQUE NOT NULL (one account per partner), currency CHAR(3) NOT NULL DEFAULT 'USD', balance DECIMAL(20,4) NOT NULL DEFAULT 0.0000, low_balance_threshold DECIMAL(20,4) NOT NULL DEFAULT 10000.0000, audit cols. prefunding_ledger_entry is append-only immutable per DAT-03 sec 6.2 and sec 10.4: id BIGINT PK, account_id BIGINT FK->prefunding_account NOT NULL, txn_ref VARCHAR(64) NULL (NULL for top-ups), entry_type VARCHAR(20) NOT NULL CHECK IN ('DEBIT_PAYMENT','DEBIT_REVERSAL','CREDIT_TOPUP','CREDIT_REVERSAL'), amount DECIMAL(20,4) NOT NULL CHECK (amount > 0), balance_after DECIMAL(20,4) NOT NULL, note VARCHAR(255) NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW() (NO updated_at; append-only), created_by VARCHAR(120) NULL. Composite index (account_id, created_at) for balance history queries. Scheme is never called without prior committed deduction.
**Steps:** Create V009__prefunding_account_and_ledger.sql; Add CREATE TABLE prefunding_account with UNIQUE on partner_id and DECIMAL(20,4) balance; Add CREATE TABLE prefunding_ledger_entry with NO updated_at column; add entry_type CHECK; add CHECK (amount > 0); Add composite index (account_id, created_at) on ledger table; Run migration; verify ledger amount=0 insert is rejected
**Deliverable:** db-migrations/src/main/resources/db/migration/V009__prefunding_account_and_ledger.sql
**Acceptance / logic checks:**
- prefunding_account.partner_id UNIQUE rejects second account for same partner
- prefunding_ledger_entry.amount CHECK > 0 rejects amount=0 insert
- prefunding_ledger_entry has no updated_at column (confirmed \d+ prefunding_ledger_entry)
- entry_type CHECK rejects 'DEBIT_FEE'
- Composite index (account_id, created_at) present in pg_indexes
**Depends on:** 3.1-T03

### 3.1-T10 — Create V010 migration: low_balance_alert_config table  _(20 min)_
**Context:** low_balance_alert_config stores per-partner configurable low-balance alert thresholds per DAT-03 sec 6.3. One row per OVERSEAS partner (UNIQUE on partner_id). Columns: id BIGINT PK, partner_id BIGINT FK->partner UNIQUE NOT NULL, threshold_usd DECIMAL(20,4) NOT NULL DEFAULT 10000.0000, alert_email VARCHAR(255) NOT NULL, is_active BOOLEAN NOT NULL DEFAULT TRUE, standard audit cols. Used by Notification and Webhook service when prefunding_account.balance drops below threshold after a DEBIT_PAYMENT ledger entry. This is OVERSEAS-partner-only; LOCAL partners (GME Remit, all KRW) have no prefunding requirement.
**Steps:** Create V010__low_balance_alert_config.sql; Add CREATE TABLE low_balance_alert_config with UNIQUE constraint on partner_id; Add DEFAULT threshold_usd = 10000.0000 and is_active DEFAULT TRUE; Add FK index on partner_id; Run migration; attempt duplicate insert for same partner_id to confirm UNIQUE rejection; Confirm FK to partner.id is enforced with a non-existent partner_id insert attempt
**Deliverable:** db-migrations/src/main/resources/db/migration/V010__low_balance_alert_config.sql
**Acceptance / logic checks:**
- UNIQUE(partner_id) rejects second row for same partner
- threshold_usd DEFAULT is 10000.0000 (confirmed by INSERT without explicit threshold)
- is_active DEFAULT TRUE confirmed
- FK to partner.id enforced (insert with non-existent partner_id rejected)
- Migration applies cleanly in sequence after V009
**Depends on:** 3.1-T03

### 3.1-T11 — Create V011 migration: settlement_batch and settlement_file tables  _(35 min)_
**Context:** settlement_batch: one row per daily batch run for a given scheme and file type per DAT-03 sec 7.1 and sec 10.5. Columns: id BIGINT PK, scheme_id BIGINT FK->qr_scheme, file_type VARCHAR(10) NOT NULL (e.g. ZP0011, ZP0061 for ZeroPay), direction VARCHAR(20) NOT NULL CHECK IN ('GME_TO_ZP','ZP_TO_GME'), settlement_date DATE NOT NULL (KST business date), window VARCHAR(20) NOT NULL CHECK IN ('MORNING','AFTERNOON','DETAIL','NIGHTLY'), status VARCHAR(20) NOT NULL CHECK IN ('PENDING','GENERATED','TRANSMITTED','RECEIVED','RECONCILED','ERROR'), transaction_count INT NOT NULL DEFAULT 0, total_amount DECIMAL(20,4) NOT NULL DEFAULT 0, total_amount_ccy CHAR(3) NOT NULL, file_checksum VARCHAR(64) NULL (SHA-256), transmitted_at/received_at/reconciled_at TIMESTAMPTZ NULL, error_detail TEXT NULL, audit cols. UNIQUE(scheme_id, file_type, settlement_date, window). settlement_file: BIGINT PK, batch_id FK->settlement_batch, filename VARCHAR(255), sftp_path VARCHAR(512), file_size_bytes BIGINT, file_checksum VARCHAR(64), direction VARCHAR(20) CHECK IN ('OUTBOUND','INBOUND'), transmitted_at TIMESTAMPTZ NULL, created_at/updated_at.
**Steps:** Create V011__settlement_batch_and_file.sql; Add CREATE TABLE settlement_batch with all CHECK constraints and UNIQUE(scheme_id, file_type, settlement_date, window); Add CREATE TABLE settlement_file with FK->settlement_batch and direction CHECK IN ('OUTBOUND','INBOUND'); Add index on (settlement_batch.scheme_id, settlement_date) for daily lookups; Run migration; verify UNIQUE constraint rejects duplicate batch entry
**Deliverable:** db-migrations/src/main/resources/db/migration/V011__settlement_batch_and_file.sql
**Acceptance / logic checks:**
- UNIQUE(scheme_id, file_type, settlement_date, window) rejects duplicate batch
- status CHECK accepts 'RECONCILED' and rejects 'COMPLETE'
- window CHECK IN ('MORNING','AFTERNOON','DETAIL','NIGHTLY') rejects 'EVENING'
- settlement_file.direction CHECK IN ('OUTBOUND','INBOUND') rejects 'BOTH'
- total_amount DEFAULT 0 and transaction_count DEFAULT 0 confirmed
**Depends on:** 3.1-T02

### 3.1-T12 — Create V012 migration: reconciliation_item table  _(25 min)_
**Context:** reconciliation_item: one row per transaction line in a reconciliation comparison per DAT-03 sec 7.3. Auto-generated when ZP006x result files are received and compared against GME internal aggregation. Columns: id BIGINT PK, batch_id BIGINT FK->settlement_batch NOT NULL, txn_ref VARCHAR(64) NULL (NULL for MISSING_GME scenario where scheme has txn but GME does not), scheme_ref VARCHAR(128) NULL, match_status VARCHAR(20) NOT NULL CHECK IN ('MATCHED','DISCREPANCY','MISSING_GME','MISSING_SCHEME'), gme_amount DECIMAL(20,4) NULL, scheme_amount DECIMAL(20,4) NULL, discrepancy_amount DECIMAL(20,4) NULL, ccy CHAR(3) NOT NULL, resolution_status VARCHAR(20) NOT NULL DEFAULT 'UNRESOLVED' CHECK IN ('UNRESOLVED','RESOLVED','ESCALATED'), resolved_by VARCHAR(120) NULL, resolved_at TIMESTAMPTZ NULL, resolution_note TEXT NULL, audit cols. Index on (batch_id, match_status) for discrepancy queries.
**Steps:** Create V012__reconciliation_item.sql; Add CREATE TABLE reconciliation_item with CHECK constraints for match_status and resolution_status; Add DEFAULT 'UNRESOLVED' for resolution_status; Add composite index (batch_id, match_status); Run migration; confirm txn_ref NULL accepted (MISSING_GME scenario); Confirm match_status CHECK rejects 'PARTIAL_MATCH'
**Deliverable:** db-migrations/src/main/resources/db/migration/V012__reconciliation_item.sql
**Acceptance / logic checks:**
- txn_ref NULL accepted (MISSING_GME scenario insert succeeds)
- match_status CHECK rejects 'PARTIAL_MATCH'
- resolution_status DEFAULT 'UNRESOLVED' confirmed by INSERT without resolution_status
- Composite index (batch_id, match_status) present in pg_indexes
- FK to settlement_batch enforced (non-existent batch_id rejected)
**Depends on:** 3.1-T11

### 3.1-T13 — Create V013 migration: merchant and qr_code tables  _(35 min)_
**Context:** merchant is a local cache of ZeroPay merchant records per DAT-03 sec 7.4 and sec 10.6. Updated by daily ZP0041/ZP0045/ZP0047/ZP0051/ZP0055 sync. Columns: id BIGINT PK, merchant_id VARCHAR(50) UNIQUE NOT NULL (ZeroPay merchant ID), merchant_type VARCHAR(30) NOT NULL (e.g. GENERAL, FRANCHISE), name VARCHAR(200) NOT NULL, business_registration_no VARCHAR(30) NULL (Korean BRN), franchise_code VARCHAR(30) NULL, category_code VARCHAR(20) NULL, bank_code VARCHAR(10) NULL (ZeroPay settlement bank), account_no VARCHAR(30) NULL (AES-256 encrypted at rest by application layer), fee_tier VARCHAR(20) NOT NULL CHECK IN ('DOMESTIC','CROSSBORDER'), status VARCHAR(20) NOT NULL CHECK IN ('ACTIVE','SUSPENDED','DEACTIVATED'), is_active BOOLEAN NOT NULL DEFAULT TRUE, synced_at TIMESTAMPTZ NOT NULL, created_at/updated_at. qr_code per DAT-03 sec 7.5: id BIGINT PK, merchant_id BIGINT FK->merchant NOT NULL, qr_code_value VARCHAR(512) UNIQUE NOT NULL (raw QR payload), qr_code_type VARCHAR(10) NOT NULL CHECK IN ('STATIC_MPM','DYNAMIC_CPM'), status VARCHAR(20) NOT NULL CHECK IN ('ACTIVE','DEACTIVATED'), is_active BOOLEAN NOT NULL DEFAULT TRUE, synced_at TIMESTAMPTZ NOT NULL, created_at/updated_at.
**Steps:** Create V013__merchant_and_qr_code.sql; Add CREATE TABLE merchant with all columns and CHECK constraints per DAT-03 sec 10.6; add COMMENT ON COLUMN merchant.account_no noting AES-256 app-level encryption; Add CREATE TABLE qr_code with FK->merchant, UNIQUE on qr_code_value, and CHECK constraints; Add index on merchant.status; index on qr_code.merchant_id; Run migration; verify UNIQUE on qr_code_value rejects duplicate
**Deliverable:** db-migrations/src/main/resources/db/migration/V013__merchant_and_qr_code.sql
**Acceptance / logic checks:**
- merchant.fee_tier CHECK IN ('DOMESTIC','CROSSBORDER') rejects 'HYBRID'
- qr_code_value UNIQUE rejects duplicate QR payload insert
- qr_code.qr_code_type CHECK IN ('STATIC_MPM','DYNAMIC_CPM') rejects 'DYNAMIC_MPM'
- merchant.status CHECK IN ('ACTIVE','SUSPENDED','DEACTIVATED') rejects 'BLOCKED'
- merchant.is_active DEFAULT TRUE confirmed
**Depends on:** 3.1-T01

### 3.1-T14 — Create V014 migration: merchant_sync_log table  _(20 min)_
**Context:** merchant_sync_log records each ZeroPay merchant/QR sync batch run per DAT-03 sec 7.6. Columns: id BIGINT PK, file_type VARCHAR(10) NOT NULL (e.g. ZP0041), sync_date DATE NOT NULL, records_received INT NOT NULL DEFAULT 0, records_inserted INT NOT NULL DEFAULT 0, records_updated INT NOT NULL DEFAULT 0, records_deactivated INT NOT NULL DEFAULT 0, error_count INT NOT NULL DEFAULT 0, status VARCHAR(20) NOT NULL CHECK IN ('SUCCESS','PARTIAL','FAILED'), detail TEXT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(). No updated_at (append-only log). Composite index (file_type, sync_date) for last-sync queries. This table is consumed by the Scheme Adapter Layer ZeroPay adapter in module services/scheme-adapter/.
**Steps:** Create V014__merchant_sync_log.sql; Add CREATE TABLE merchant_sync_log with all INT DEFAULT 0 columns; Add status CHECK IN ('SUCCESS','PARTIAL','FAILED'); Add composite index (file_type, sync_date); Run migration; insert a PARTIAL sync record and verify; Confirm no updated_at column exists (\d merchant_sync_log)
**Deliverable:** db-migrations/src/main/resources/db/migration/V014__merchant_sync_log.sql
**Acceptance / logic checks:**
- status CHECK IN ('SUCCESS','PARTIAL','FAILED') rejects 'ERROR'
- All count columns (records_received/inserted/updated/deactivated/error_count) DEFAULT 0 confirmed
- No updated_at column present (append-only log; \d check)
- Composite index (file_type, sync_date) in pg_indexes
- detail NULL accepted
**Depends on:** 3.1-T01

### 3.1-T15 — Create V015 migration: bok_report_record and revenue_record tables  _(35 min)_
**Context:** bok_report_record: one row per cross-border transaction requiring BOK FX reporting per DAT-03 sec 8.1. Domestic/same-currency transactions are exempt. FX1014 = collection side (customer paying overseas); FX1015 = payout side (payment to Korean merchant). Columns: id BIGINT PK, txn_id BIGINT FK->transaction NOT NULL, report_type VARCHAR(10) NOT NULL CHECK IN ('FX1014','FX1015'), report_date DATE NOT NULL, partner_id BIGINT FK->partner NOT NULL, collection_amount DECIMAL(20,4) NULL, collection_ccy CHAR(3) NULL, payout_amount DECIMAL(20,4) NULL, payout_ccy CHAR(3) NULL, offer_rate_coll DECIMAL(20,8) NULL (BOK FX1015 field #14: send_amount / (collection_usd - collection_margin_usd)), usd_amount DECIMAL(20,4) NULL, submission_status VARCHAR(20) NOT NULL CHECK IN ('PENDING','SUBMITTED','CONFIRMED','FAILED'), submitted_at TIMESTAMPTZ NULL, created_at/updated_at. revenue_record per DAT-03 sec 8.2: id BIGINT PK, txn_id FK->transaction, partner_id FK->partner, scheme_id FK->qr_scheme, revenue_date DATE NOT NULL, fx_margin_usd DECIMAL(20,4) (= collection_margin_usd + payout_margin_usd), service_charge_amount DECIMAL(20,4), service_charge_ccy CHAR(3), fee_share_pct DECIMAL(6,4) (0.7000 for ZeroPay), estimated_fee_share_usd DECIMAL(20,4), created_at/updated_at. Index on (partner_id, revenue_date).
**Steps:** Create V015__bok_report_record_and_revenue_record.sql; Add CREATE TABLE bok_report_record with FKs to transaction and partner; add report_type and submission_status CHECK constraints; Add CREATE TABLE revenue_record with FKs; add index on (partner_id, revenue_date); Add index on (bok_report_record.txn_id) and (submission_status) for pending-report queries; Run migration; verify offer_rate_coll NULL accepted (same-ccy exemption)
**Deliverable:** db-migrations/src/main/resources/db/migration/V015__bok_report_record_and_revenue_record.sql
**Acceptance / logic checks:**
- bok_report_record.report_type CHECK IN ('FX1014','FX1015') rejects 'FX1016'
- submission_status CHECK IN ('PENDING','SUBMITTED','CONFIRMED','FAILED') accepted for all 4 values
- offer_rate_coll DECIMAL(20,8) accepts NULL for same-ccy rows
- revenue_record.fee_share_pct DECIMAL(6,4) stores 0.7000 without truncation
- Index on (partner_id, revenue_date) present in pg_indexes
**Depends on:** 3.1-T07

### 3.1-T16 — Create V016 migration: tax_invoice table  _(25 min)_
**Context:** tax_invoice: monthly merchant fee tax invoice for cross-border transactions per DAT-03 sec 8.3 (UC-04-04). All KRW amounts stored as BIGINT (integer; KRW has no minor-unit subdivision in payment practice per DAT-03 sec 2.1). Columns: id BIGINT PK, merchant_id BIGINT FK->merchant NOT NULL, invoice_period DATE NOT NULL (first day of invoice month), invoice_ref VARCHAR(64) UNIQUE NOT NULL, total_transaction_amount_krw BIGINT NOT NULL, fee_rate DECIMAL(6,4) NOT NULL, merchant_fee_krw BIGINT NOT NULL (= total_transaction_amount_krw x fee_rate; rounded to integer), vat_krw BIGINT NOT NULL (= merchant_fee_krw x 0.10; 10% VAT; rounded to integer), invoice_amount_krw BIGINT NOT NULL (= merchant_fee_krw + vat_krw), zeropay_share_krw BIGINT NOT NULL (= merchant_fee_krw x 0.0021; ZeroPay 0.21% of subtotal), status VARCHAR(20) NOT NULL CHECK IN ('DRAFT','ISSUED','COLLECTED','FAILED'), issued_at TIMESTAMPTZ NULL, collected_at TIMESTAMPTZ NULL, audit cols. UNIQUE(merchant_id, invoice_period).
**Steps:** Create V016__tax_invoice.sql; Add CREATE TABLE tax_invoice with BIGINT for all KRW amount columns; Add CHECK IN ('DRAFT','ISSUED','COLLECTED','FAILED') for status; Add UNIQUE(merchant_id, invoice_period) to prevent duplicate monthly invoices; Add index on (status, invoice_period) for billing queries; Run migration; confirm BIGINT type rejects decimal 123.50 for merchant_fee_krw
**Deliverable:** db-migrations/src/main/resources/db/migration/V016__tax_invoice.sql
**Acceptance / logic checks:**
- UNIQUE(merchant_id, invoice_period) rejects second invoice for same merchant+month
- status CHECK IN ('DRAFT','ISSUED','COLLECTED','FAILED') rejects 'SENT'
- All KRW amount columns are BIGINT type (confirmed by \d tax_invoice)
- issued_at and collected_at are nullable TIMESTAMPTZ
- zeropay_share_krw NOT NULL confirmed
**Depends on:** 3.1-T13

### 3.1-T17 — Create V017 migration: audit_log and api_request_log tables  _(35 min)_
**Context:** audit_log: immutable record of all create/update/delete actions on configuration entities per DAT-03 sec 9.1 and sec 10.7. Retention >=7 years (BOK/KYC). Columns: id BIGINT PK, actor_id VARCHAR(120) NOT NULL, actor_type VARCHAR(20) NOT NULL CHECK IN ('OPERATOR','SYSTEM','PARTNER'), action VARCHAR(20) NOT NULL CHECK IN ('CREATE','UPDATE','DELETE','ACTIVATE','DEACTIVATE'), entity_type VARCHAR(50) NOT NULL (table name e.g. rule, partner, treasury_rate), entity_id BIGINT NOT NULL, before_value JSONB NULL (NULL for CREATE), after_value JSONB NULL (NULL for DELETE), occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), ip_address INET NULL, request_id VARCHAR(64) NULL (Trace ID). NO UPDATE or DELETE permitted; enforce at DB level via PostgreSQL RULE. api_request_log per DAT-03 sec 9.3: id BIGINT PK, request_id VARCHAR(64) UNIQUE NOT NULL, partner_id BIGINT FK->partner NULL, method VARCHAR(10), path VARCHAR(255), status_code INT, request_body_hash VARCHAR(64) (SHA-256; not plaintext), response_time_ms INT, occurred_at TIMESTAMPTZ NOT NULL, ip_address INET NULL. Indexes: audit_log(entity_type, entity_id), audit_log(occurred_at DESC); api_request_log(partner_id, occurred_at).
**Steps:** Create V017__audit_log_and_api_request_log.sql; Add CREATE TABLE audit_log with CHECK constraints; no updated_at column; Add RULE pg_audit_log_no_update AS ON UPDATE TO audit_log DO INSTEAD NOTHING and RULE pg_audit_log_no_delete AS ON DELETE TO audit_log DO INSTEAD NOTHING; Add CREATE TABLE api_request_log with request_id UNIQUE and INET ip_address columns; Add composite indexes on both tables per spec; Run migration; verify UPDATE on audit_log is silently ignored (immutability rule fires)
**Deliverable:** db-migrations/src/main/resources/db/migration/V017__audit_log_and_api_request_log.sql
**Acceptance / logic checks:**
- UPDATE on audit_log row changes 0 rows (immutability RULE fires silently)
- actor_type CHECK IN ('OPERATOR','SYSTEM','PARTNER') rejects 'ADMIN'
- action CHECK IN ('CREATE','UPDATE','DELETE','ACTIVATE','DEACTIVATE') rejects 'PATCH'
- before_value JSONB NULL accepted for CREATE action insert
- api_request_log.request_id UNIQUE rejects duplicate request ID
**Depends on:** 3.1-T03

### 3.1-T18 — Create V018 migration: hub_user and hub_role tables with role seeds  _(25 min)_
**Context:** hub_user and hub_role implement internal Admin System RBAC per DAT-03 sec 9.2. hub_role: id BIGINT PK, role_code VARCHAR(30) UNIQUE NOT NULL CHECK IN ('SUPER_ADMIN','OPS_ADMIN','OPS_VIEWER','FINANCE'), permissions JSONB NOT NULL DEFAULT '{}', created_at/updated_at. hub_user: id BIGINT PK, email VARCHAR(255) UNIQUE NOT NULL, name VARCHAR(100) NOT NULL, role_id BIGINT FK->hub_role NOT NULL, is_active BOOLEAN NOT NULL DEFAULT FALSE, last_login_at TIMESTAMPTZ NULL, created_at/updated_at/created_by. Passwords are NOT stored here; authentication is handled by Auth and Identity service (OAuth2/JWT). Index hub_user on role_id (email already unique-indexed). Seed the 4 canonical role rows with permissions='{}'.
**Steps:** Create V018__hub_user_and_hub_role.sql; Add CREATE TABLE hub_role with role_code CHECK constraint and JSONB permissions DEFAULT '{}'; Add CREATE TABLE hub_user with FK->hub_role; add UNIQUE on email; add is_active DEFAULT FALSE; Add index on hub_user.role_id; Add INSERT INTO hub_role (role_code, permissions) VALUES for SUPER_ADMIN, OPS_ADMIN, OPS_VIEWER, FINANCE with ON CONFLICT (role_code) DO NOTHING; Run migration and verify SELECT count(*) FROM hub_role = 4
**Deliverable:** db-migrations/src/main/resources/db/migration/V018__hub_user_and_hub_role.sql
**Acceptance / logic checks:**
- role_code CHECK IN ('SUPER_ADMIN','OPS_ADMIN','OPS_VIEWER','FINANCE') rejects 'DEVELOPER'
- hub_user.is_active DEFAULT FALSE confirmed
- hub_user.email UNIQUE rejects duplicate email insert
- SELECT count(*) FROM hub_role = 4 after migration
- FK to hub_role.id enforced (insert user with invalid role_id rejected)
**Depends on:** 3.1-T01

### 3.1-T19 — Create V019 migration: transactional outbox table  _(20 min)_
**Context:** The transactional Outbox pattern is used for reliable async domain event publishing (Phase 1: in-process outbox poller; Phase 2: Kafka transport behind EventPublisher interface per STACK.md). The outbox table is in PostgreSQL alongside the business tables so domain events and business writes are in the same DB transaction. Columns: id BIGINT PK, aggregate_type VARCHAR(50) NOT NULL (e.g. 'transaction', 'prefunding_account'), aggregate_id VARCHAR(64) NOT NULL (business key e.g. txn_ref), event_type VARCHAR(100) NOT NULL (e.g. 'TransactionApproved', 'PrefundingDeducted'), payload JSONB NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK IN ('PENDING','PUBLISHED','FAILED'), created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), published_at TIMESTAMPTZ NULL, retry_count INT NOT NULL DEFAULT 0. Index on (status, created_at) for outbox poller to SELECT PENDING rows. Index on (aggregate_type, aggregate_id) for aggregate-level queries. lib-events module defines the Avro/JSON event schemas that populate payload.
**Steps:** Create V019__outbox.sql; Add CREATE TABLE outbox with all columns; add status CHECK constraint; Add index on (status, created_at) for efficient PENDING row polling; Add index on (aggregate_type, aggregate_id); Run migration; insert a PENDING row and verify status DEFAULT; Confirm published_at NULL accepted and retry_count DEFAULT 0
**Deliverable:** db-migrations/src/main/resources/db/migration/V019__outbox.sql
**Acceptance / logic checks:**
- status DEFAULT 'PENDING' confirmed by INSERT without explicit status
- status CHECK IN ('PENDING','PUBLISHED','FAILED') rejects 'SENT'
- Index on (status, created_at) present in pg_indexes
- payload JSONB column accepts nested JSON object
- retry_count DEFAULT 0 confirmed
**Depends on:** 3.1-T01

### 3.1-T20 — Create Flyway config bean in lib-persistence for multi-service reuse  _(45 min)_
**Context:** lib-persistence is a shared Gradle module (libs/lib-persistence/) providing common JPA/Flyway configuration. Each Spring Boot service (services/payment-executor, services/config-registry, services/prefunding-balance, etc.) includes lib-persistence as a compile dependency and gets a consistent Flyway DataSource setup pointing to PostgreSQL 16. DataSource uses spring.datasource.url=jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5433}/${DB_NAME:gmepay}, driver-class-name=org.postgresql.Driver, HikariCP pool min=2 max=10. Flyway locations set per service via spring.flyway.locations configuration. Spring Boot 3.x auto-configuration via META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports.
**Steps:** In libs/lib-persistence/build.gradle add dependencies: spring-boot-autoconfigure, flyway-core, postgresql JDBC, spring-data-jpa; Create libs/lib-persistence/src/main/java/com/gme/pay/persistence/FlywayConfig.java as @Configuration with DataSource @Bean using HikariCP properties; Create libs/lib-persistence/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports to register FlywayConfig; Write libs/lib-persistence/src/test/java/com/gme/pay/persistence/FlywayConfigTest.java using @Testcontainers with PostgreSQLContainer("postgres:16") to verify Flyway.migrate() runs cleanly; Run ./gradlew :lib-persistence:test
**Deliverable:** libs/lib-persistence/src/main/java/com/gme/pay/persistence/FlywayConfig.java, libs/lib-persistence/src/test/java/com/gme/pay/persistence/FlywayConfigTest.java
**Acceptance / logic checks:**
- FlywayConfigTest passes with Testcontainers postgres:16 image
- Flyway.migrate() returns MigrateResult with migrationsExecuted >= 0 and no errors
- HikariPool max pool size = 10 confirmed in test logs
- AutoConfiguration.imports file present so dependent services auto-configure Flyway
- ./gradlew :lib-persistence:test exits 0
**Depends on:** 3.1-T01

### 3.1-T21 — Testcontainers integration test: V001-V010 apply cleanly in sequence  _(40 min)_
**Context:** All Flyway migrations V001-V010 must apply in order against a clean Postgres 16 container. The test uses JUnit 5 with @Testcontainers annotation and @Container PostgreSQLContainer("postgres:16") from org.testcontainers:postgresql. After Flyway.migrate(), verify table presence and critical column types. Expected tables after V010: qr_scheme, scheme_country, partner, partner_credential, partner_webhook, treasury_rate, rule, service_charge_tier, rate_quote, prefunding_account, prefunding_ledger_entry, low_balance_alert_config (12 tables). This test acts as the integration gate before service-layer code is wired.
**Steps:** Add testImplementation testcontainers:postgresql and junit-jupiter to db-migrations/build.gradle; Create db-migrations/src/test/java/com/gme/pay/db/MigrationV001toV010Test.java; In @Test run Flyway.configure().dataSource(container.getJdbcUrl(),...).locations("classpath:db/migration").load().migrate(); Assert migrateResult.migrationsExecuted == 10; Query information_schema.tables and assert all 12 tables present; query information_schema.columns to spot-check rate DECIMAL(20,8) on treasury_rate
**Deliverable:** db-migrations/src/test/java/com/gme/pay/db/MigrationV001toV010Test.java
**Acceptance / logic checks:**
- migrationsExecuted == 10 with no validation errors
- All 12 expected tables present in information_schema.tables
- treasury_rate.rate column data_type = 'numeric' with numeric_precision=20 and numeric_scale=8
- prefunding_ledger_entry has no updated_at column in information_schema.columns
- Test completes under 60 seconds
**Depends on:** 3.1-T10, 3.1-T20

### 3.1-T22 — Testcontainers integration test: V011-V019 and full 19-migration schema validation  _(45 min)_
**Context:** Continuation of migration integration tests covering V011-V019. After all 19 migrations apply, verify: (1) total table count >= 19 (includes merchant, qr_code, merchant_sync_log, settlement_batch, settlement_file, reconciliation_item, bok_report_record, revenue_record, tax_invoice, audit_log, api_request_log, hub_user, hub_role, outbox, refund, transaction_event); (2) critical CHECK constraints fire correctly; (3) hub_role has 4 seeded rows; (4) outbox status DEFAULT is 'PENDING'. Live-fire constraint tests: rule m_a+m_b<0.02 with is_same_ccy_shortcircuit=FALSE; transaction_event step=9; prefunding_ledger_entry amount=0.
**Steps:** Create db-migrations/src/test/java/com/gme/pay/db/MigrationFullSchemaTest.java with Testcontainers PostgreSQLContainer; Run all 19 migrations; assert migrationsExecuted == 19; Execute INSERT statements that violate CHECK constraints and assert PSQLException is thrown for each; Assert SELECT count(*) FROM hub_role == 4 (seeded roles); Assert INSERT INTO outbox (aggregate_type, aggregate_id, event_type, payload) returns row with status='PENDING'
**Deliverable:** db-migrations/src/test/java/com/gme/pay/db/MigrationFullSchemaTest.java
**Acceptance / logic checks:**
- migrationsExecuted == 19 with no errors
- rule INSERT with m_a=0.005, m_b=0.005, is_same_ccy_shortcircuit=FALSE throws PSQLException
- transaction_event step=9 INSERT throws PSQLException (CHECK violation)
- prefunding_ledger_entry amount=0 INSERT throws PSQLException
- hub_role count == 4
**Depends on:** 3.1-T21, 3.1-T19, 3.1-T18

### 3.1-T23 — Money precision test: DECIMAL(20,8) USD pool values and pool-identity invariant  _(35 min)_
**Context:** DAT-03 sec 2.3 and sec 2.4 mandate DECIMAL(20,8) for USD intermediate values and DECIMAL(20,4) for final amounts. The pool identity invariant is: collection_usd - collection_margin_usd - payout_margin_usd = payout_usd_cost within 0.01 USD tolerance. Test vector: m_a=0.015, m_b=0.010, target_payout=1000 KRW, cost_rate_pay=1380.0 (usd_krw), cost_rate_coll=1.0 (USD identity). Step 1: payout_usd_cost = 1000/1380 = 0.72463768. Step 2: collection_usd = 0.72463768/(1-0.015-0.010) = 0.72463768/0.975 = 0.74321814. Step 3a: collection_margin_usd = 0.74321814*0.015 = 0.01114827. Step 3b: payout_margin_usd = 0.74321814*0.010 = 0.00743218. Pool check: 0.74321814-0.01114827-0.00743218 = 0.72463769 (diff = 0.00000001 < 0.01 USD tolerance).
**Steps:** Create db-migrations/src/test/java/com/gme/pay/db/MoneyPrecisionTest.java using Testcontainers; After full 19-migration schema, insert prerequisite rows: qr_scheme (ZEROPAY), partner (TEST_PARTNER), treasury_rate (usd_krw=1380.00000000), rule with m_a=0.015000, m_b=0.010000; Insert rate_quote row with the computed values above (8dp precision); SELECT stored values back via JDBC as BigDecimal and assert each to 8dp; Compute pool identity from DB values: abs(collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost) and assert < 0.01
**Deliverable:** db-migrations/src/test/java/com/gme/pay/db/MoneyPrecisionTest.java
**Acceptance / logic checks:**
- payout_usd_cost stored and retrieved as 0.72463768 (not rounded to 4dp)
- collection_usd stored and retrieved as 0.74321814 without truncation
- Pool identity check: abs value < 0.01 USD
- collection_amount DECIMAL(20,4) stores send_amount+0 correctly
- No ArithmeticException or precision loss in JDBC BigDecimal retrieval
**Depends on:** 3.1-T22

### 3.1-T24 — Concurrent SELECT FOR UPDATE test: prefunding_account balance deduction atomicity  _(45 min)_
**Context:** Prefunding deductions MUST be atomic. Application pattern: BEGIN; SELECT balance FROM prefunding_account WHERE partner_id=? FOR UPDATE; check balance >= amount; UPDATE prefunding_account SET balance = balance - amount WHERE id=?; INSERT INTO prefunding_ledger_entry ...; COMMIT. Two concurrent threads each attempting to deduct 6000 USD from a 10000 USD balance must result in exactly one successful deduction (final balance = 4000 USD) because the second thread must wait for the first to commit, then re-check and find insufficient balance. Without FOR UPDATE, both deductions could succeed leaving balance = -2000.
**Steps:** Create db-migrations/src/test/java/com/gme/pay/db/PrefundingAtomicityTest.java using Testcontainers PostgreSQLContainer; Insert prefunding_account with balance=10000.0000 for a test OVERSEAS partner; Spawn two threads each in separate JDBC connections executing the SELECT FOR UPDATE + UPDATE + INSERT ledger flow, each deducting 6000 USD; Use CountDownLatch to start both threads simultaneously; Assert exactly one thread succeeds; assert final balance = 4000.0000; assert ledger entry count == 1
**Deliverable:** db-migrations/src/test/java/com/gme/pay/db/PrefundingAtomicityTest.java
**Acceptance / logic checks:**
- Final prefunding_account.balance = 4000.0000 (exactly one successful deduction)
- prefunding_ledger_entry count == 1 after concurrent test
- Second thread fails with insufficient-balance check (application-level guard after FOR UPDATE releases)
- No negative balance results from concurrent deductions
- Test is deterministic across 10 repeated runs (no flakiness)
**Depends on:** 3.1-T22

### 3.1-T25 — Spring Data JPA entities for config/registry tables in services/config-registry  _(50 min)_
**Context:** The services/config-registry Spring Boot service manages qr_scheme, partner, rule, treasury_rate, and related config tables. JPA entity classes must use BigDecimal for all DECIMAL/NUMERIC money columns to prevent JPA from defaulting to DOUBLE. Use @Column(precision=X, scale=Y) matching DAT-03 types exactly. Package: services/config-registry/src/main/java/com/gme/pay/configregistry/entity/. Hibernate schema validation (spring.jpa.hibernate.ddl-auto=validate) must pass against the Flyway-migrated schema. Do NOT use @Enumerated; all status/type fields are String (CHECK is at DB level).
**Steps:** Create QrSchemeEntity.java: @Entity @Table(name='qr_scheme') with all columns; fee columns as BigDecimal with @Column(precision=6,scale=4); gme_fee_share_pct BigDecimal(6,4); Create PartnerEntity.java with partner_type String; rate_quote_ttl_seconds Integer; low_balance_threshold_usd BigDecimal(20,4) nullable; Create RuleEntity.java with m_a/m_b as BigDecimal @Column(precision=8,scale=6); is_same_ccy_shortcircuit Boolean; Create TreasuryRateEntity.java with rate as BigDecimal @Column(precision=20,scale=8); Write @DataJpaTest slice tests using @AutoConfigureTestDatabase(replace=NONE) with Testcontainers Postgres; verify Hibernate validate passes and insert/retrieve m_a=0.015000 without rounding
**Deliverable:** services/config-registry/src/main/java/com/gme/pay/configregistry/entity/QrSchemeEntity.java, PartnerEntity.java, RuleEntity.java, TreasuryRateEntity.java
**Acceptance / logic checks:**
- Hibernate schema validate passes against Flyway-migrated schema (no SchemaManagementException)
- BigDecimal(8,6) field m_a stores and retrieves 0.015000 exactly
- No @Enumerated annotation in any entity (all status/type = String)
- @DataJpaTest for RuleEntity inserts cross-border rule with m_a=0.005, m_b=0.005 and gets DataIntegrityViolationException (DB CHECK fires)
- TreasuryRateEntity.rate BigDecimal(20,8) stores 1380.00000000 correctly
**Depends on:** 3.1-T22, 3.1-T05

### 3.1-T26 — Spring Data JPA entities for transaction and rate_quote in services/payment-executor  _(50 min)_
**Context:** The services/payment-executor Spring Boot service owns the transaction lifecycle. JPA entities for transaction and rate_quote are needed. Package: services/payment-executor/src/main/java/com/gme/pay/executor/entity/. All DECIMAL(20,8) columns mapped as BigDecimal nullable (Java wrapper, not primitive) so they can hold null for same-ccy short-circuit. TransactionEntity uses @Version Long version for optimistic locking on status transitions. TransactionEventEntity maps detail as JSONB (mapped as String with @Column(columnDefinition='jsonb')). RateQuoteEntity maps valid_until and quote_issued_at as Instant.
**Steps:** Create TransactionEntity.java with @Entity @Table(name='transaction'); map all columns per DAT-03 sec 10.1; nullable USD pool fields as BigDecimal (wrapper); Add @Version Long version field to TransactionEntity for optimistic locking; Create RateQuoteEntity.java with is_used Boolean; valid_until as Instant; all nullable USD fields as BigDecimal; Create TransactionEventEntity.java with detail as @Column(columnDefinition='jsonb') String; Write @DataJpaTest verifying TransactionEntity insert with is_same_ccy_shortcircuit=TRUE and null collection_usd succeeds; verify OptimisticLockingFailureException on stale update
**Deliverable:** services/payment-executor/src/main/java/com/gme/pay/executor/entity/TransactionEntity.java, RateQuoteEntity.java, TransactionEventEntity.java
**Acceptance / logic checks:**
- @DataJpaTest inserts TransactionEntity with collection_usd=null and is_same_ccy_shortcircuit=TRUE without error
- @Version field triggers OptimisticLockingFailureException when two concurrent in-test loads both try to update
- RateQuoteEntity.valid_until stored and retrieved as Instant correctly
- TransactionEventEntity.detail stored as JSONB string and retrieved intact
- Hibernate validate passes against Flyway V007/V006 schema
**Depends on:** 3.1-T22, 3.1-T07

### 3.1-T27 — Spring Data JPA entities for prefunding in services/prefunding-balance  _(45 min)_
**Context:** The services/prefunding-balance Spring Boot service manages prefunding_account and prefunding_ledger_entry. Package: services/prefunding-balance/src/main/java/com/gme/pay/prefunding/entity/. PrefundingAccountEntity uses @Lock(LockModeType.PESSIMISTIC_WRITE) in repository for SELECT FOR UPDATE deduction flow. PrefundingLedgerEntryEntity is append-only: no updatedAt field mapped, entry_type as String. balance is BigDecimal DECIMAL(20,4). PrefundingAccountRepository extends JpaRepository with custom method findByPartnerIdWithLock annotated @Lock(PESSIMISTIC_WRITE). LowBalanceAlertConfigEntity maps low_balance_alert_config.
**Steps:** Create PrefundingAccountEntity.java with balance as BigDecimal(20,4); add @Version Long version; Create PrefundingLedgerEntryEntity.java as @Entity; do NOT map any updatedAt field; entry_type as String; Create LowBalanceAlertConfigEntity.java mapping low_balance_alert_config table; Create PrefundingAccountRepository.java extending JpaRepository with @Query + @Lock(PESSIMISTIC_WRITE) method for deduction; Write @DataJpaTest verifying pessimistic lock is acquired: second concurrent transaction.getBalance() blocks until first commits
**Deliverable:** services/prefunding-balance/src/main/java/com/gme/pay/prefunding/entity/PrefundingAccountEntity.java, PrefundingLedgerEntryEntity.java, LowBalanceAlertConfigEntity.java, services/prefunding-balance/src/main/java/com/gme/pay/prefunding/repository/PrefundingAccountRepository.java
**Acceptance / logic checks:**
- PrefundingLedgerEntryEntity has no updatedAt field (compile-time check via reflection in test)
- @DataJpaTest confirms PESSIMISTIC_WRITE lock: second transaction on same row blocks until first commits
- PrefundingAccountEntity.balance BigDecimal(20,4) stores 10000.0000 exactly
- PrefundingLedgerEntryEntity amount=0 insert throws DataIntegrityViolationException (DB CHECK fires)
- Hibernate validate passes against Flyway V009 schema
**Depends on:** 3.1-T22, 3.1-T09

### 3.1-T28 — V020 seed migration: ZeroPay scheme, KR country, GME_REMIT partner, treasury rates  _(25 min)_
**Context:** V020 inserts the minimum idempotent seed data needed for local dev and integration tests. Seed: (1) qr_scheme row for ZEROPAY: scheme_code='ZEROPAY', payout_ccy='KRW', supported_modes='BOTH', status='TESTING', partner_b_quote_deviation_pct=0.0100, gme_fee_share_pct=0.7000, is_active=TRUE, sftp_host/paths/api_endpoint/api_credential_ref set to placeholder values. (2) scheme_country row: ZEROPAY + country_code='KR', is_active=TRUE. (3) partner row: partner_code='GME_REMIT', partner_type='LOCAL', collection_ccy='KRW', settle_a_ccy='KRW', rate_quote_ttl_seconds=300, is_active=FALSE, status='ONBOARDING'. (4) treasury_rate rows: usd_krw=1380.00000000 and usd_mnt=3440.00000000, source='MANUAL', effective_at=NOW(). All INSERTs use ON CONFLICT DO NOTHING for idempotency.
**Steps:** Create db-migrations/src/main/resources/db/migration/V020__seed_zeropay_and_gme_remit.sql; INSERT INTO qr_scheme with ON CONFLICT (scheme_code) DO NOTHING; INSERT INTO scheme_country using subquery for scheme_id with ON CONFLICT (scheme_id, country_code) DO NOTHING; INSERT INTO partner for GME_REMIT with ON CONFLICT (partner_code) DO NOTHING; INSERT two rows into treasury_rate (usd_krw=1380.00000000, usd_mnt=3440.00000000) with source='MANUAL'; Run migration twice; confirm second run applies cleanly with 0 new rows (verify via SELECT counts before/after)
**Deliverable:** db-migrations/src/main/resources/db/migration/V020__seed_zeropay_and_gme_remit.sql
**Acceptance / logic checks:**
- Second run of V020 produces no duplicate key errors (idempotent ON CONFLICT DO NOTHING)
- qr_scheme.gme_fee_share_pct = 0.7000 after seed
- treasury_rate row for usd_krw has rate=1380.00000000 (8dp precision confirmed)
- partner GME_REMIT has is_active=FALSE and status='ONBOARDING'
- scheme_country KR row is linked to ZEROPAY scheme_id (verified by JOIN query)
**Depends on:** 3.1-T22, 3.1-T04, 3.1-T02, 3.1-T03

### 3.1-T29 — Index presence validation test and SCHEMA.md for db-migrations module  _(40 min)_
**Context:** A final integration test validates that all performance-critical indexes defined in DAT-03 exist in the migrated schema by querying pg_indexes. Critical indexes per DAT-03: transaction (partner_id, committed_at), (scheme_id, completed_at), (status, committed_at), scheme_ref, hub_txn_ref; prefunding_ledger_entry (account_id, created_at); treasury_rate (ccy_pair, effective_at DESC); rate_quote (partner_id, quote_issued_at), (rule_id, is_used), valid_until; settlement_batch (scheme_id, settlement_date); reconciliation_item (batch_id, match_status); outbox (status, created_at). Additionally produce SCHEMA.md documenting migration list, money type rules, and domain groupings.
**Steps:** Create db-migrations/src/test/java/com/gme/pay/db/IndexPresenceTest.java querying pg_indexes for each required index by indexdef column content; Assert each critical index present; log missing indexes as AssertionError with name; Verify EXPLAIN (FORMAT TEXT) for SELECT * FROM transaction WHERE partner_id=1 AND committed_at > NOW()-interval '1 day' uses Index Scan; Create db-migrations/SCHEMA.md listing all 20 migrations with descriptions, money type conventions (DECIMAL(20,8) for USD pool, DECIMAL(20,4) for final amounts, BIGINT for KRW), and table groupings by domain; Run ./gradlew :db-migrations:test to confirm all tests green
**Deliverable:** db-migrations/src/test/java/com/gme/pay/db/IndexPresenceTest.java, db-migrations/SCHEMA.md
**Acceptance / logic checks:**
- IndexPresenceTest asserts all 12 critical composite/single-column indexes present (test fails listing missing names if any absent)
- EXPLAIN for partner_id+committed_at filter on transaction shows Index Scan not Seq Scan
- SCHEMA.md documents DECIMAL(20,8) for USD pool and BIGINT for KRW
- SCHEMA.md lists all 20 migration files (V001-V020) in order with one-line description each
- ./gradlew :db-migrations:test exits 0 with all tests passing
**Depends on:** 3.1-T22


## WBS 3.7 — Migration framework & seed data
### 3.7-T01 — Add Flyway dependency and configure migration scanning in lib-persistence  _(30 min)_
**Context:** GMEPay+ is a Gradle multi-module monorepo (Java 21, Spring Boot 3.x). All schema migrations use Flyway (OPS-13). The shared module lib-persistence owns common JPA/migration utils. Flyway must scan classpath:db/migration, set spring.flyway.validate-on-migrate=true, out-of-order=false, and read DB URL/user/password from env vars SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME, SPRING_DATASOURCE_PASSWORD. Migration history lands in table flyway_schema_history. No credentials in committed config.
**Steps:** Add flyway-core and flyway-database-postgresql to lib-persistence/build.gradle (implementation scope).; In lib-persistence/src/main/resources/META-INF/spring/flyway-defaults.yaml set spring.flyway.locations=classpath:db/migration, validate-on-migrate=true, out-of-order=false, baseline-on-migrate=false.; Expose a @Bean FlywayMigrationStrategy in lib-persistence that logs version+description+duration for each applied script.; Add lib-persistence as an api dependency to services/merchant-qr, services/config-registry, and services/settlement.; Verify app starts against an empty local PostgreSQL on port 5433 and flyway_schema_history is auto-created.
**Deliverable:** lib-persistence/build.gradle with flyway deps; lib-persistence/src/main/resources/META-INF/spring/flyway-defaults.yaml; FlywayMigrationStrategy @Bean
**Acceptance / logic checks:**
- Application starts on a clean DB and flyway_schema_history table is created with zero rows.
- Mutating a migration file checksum causes startup to fail with FlywayValidateException (revert to fix).
- DB credentials are read exclusively from env vars; no credentials appear in committed YAML or properties files.
- Flyway logs version, description, and execution duration for each applied migration at startup.

### 3.7-T02 — V001 Flyway migration: PostgreSQL extensions and money-type comments  _(25 min)_
**Context:** All monetary amounts in GMEPay+ are stored as DECIMAL(20,4) (transaction amounts, service charges) or DECIMAL(20,8) (USD pool values, FX rates); never floating-point. Every amount column pairs with a CHAR(3) currency column. Extensions needed from day one: uuid-ossp (UUID generation) and pgcrypto (account_no encryption). Standard audit columns on every table: created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), created_by VARCHAR(60), updated_by VARCHAR(60). All timestamps stored as UTC.
**Steps:** Create db/migration/V001__baseline_extensions.sql under lib-persistence/src/main/resources/.; Add CREATE EXTENSION IF NOT EXISTS uuid-ossp; CREATE EXTENSION IF NOT EXISTS pgcrypto;; Add a schema comment documenting the money-storage convention (DECIMAL(20,4) for amounts, DECIMAL(20,8) for rates, CHAR(3) ccy pairing, UTC timestamps).; Run flyway migrate locally against port 5433 DB gmepay_dev; confirm V001 row in flyway_schema_history.; Add a Testcontainers integration test in lib-persistence that starts PostgreSQL 16, runs all migrations, and asserts both extensions exist.
**Deliverable:** lib-persistence/src/main/resources/db/migration/V001__baseline_extensions.sql; Testcontainers migration smoke test
**Acceptance / logic checks:**
- SELECT extname FROM pg_extension WHERE extname IN ('uuid-ossp','pgcrypto') returns both rows after migration.
- flyway_schema_history has exactly one row: version=001, success=true.
- Re-running flyway migrate is idempotent (no error, no duplicate row in history).
- Testcontainers test passes in CI with PostgreSQL 16 image.
**Depends on:** 3.7-T01

### 3.7-T03 — V002 Flyway migration: qr_scheme and scheme_country tables  _(35 min)_
**Context:** qr_scheme represents a QR payment network. Columns: id BIGINT GENERATED ALWAYS AS IDENTITY PK, scheme_code VARCHAR(30) UNIQUE NOT NULL, name VARCHAR(100), payout_ccy CHAR(3), supported_modes VARCHAR(10) CHECK IN ('MPM','CPM','BOTH'), status VARCHAR(20) CHECK IN ('TESTING','ACTIVE','INACTIVE') DEFAULT 'TESTING', merchant_fee_crossborder_min DECIMAL(6,4), merchant_fee_crossborder_max DECIMAL(6,4), gme_fee_share_pct DECIMAL(6,4) DEFAULT 0.7000, partner_b_quote_deviation_pct DECIMAL(6,4) DEFAULT 0.0100, is_active BOOLEAN NOT NULL DEFAULT true, plus standard audit columns. scheme_country: id, scheme_id FK qr_scheme, country_code CHAR(2), is_active BOOLEAN, audit cols; UNIQUE(scheme_id, country_code). Module: lib-persistence (migration file); services/merchant-qr and services/config-registry consume these tables via JPA.
**Steps:** Create db/migration/V002__create_qr_scheme.sql.; Write CREATE TABLE qr_scheme with all columns, CHECK constraints on status and supported_modes, index on (scheme_code) and (status).; Write CREATE TABLE scheme_country with FK, UNIQUE(scheme_id, country_code), index on (country_code).; Run flyway migrate locally; confirm V002 row in flyway_schema_history.; Add Testcontainers test asserting column types via JDBC metadata and constraint violations on bad status value.
**Deliverable:** lib-persistence/src/main/resources/db/migration/V002__create_qr_scheme.sql
**Acceptance / logic checks:**
- INSERT with status='INVALID' raises a CHECK constraint violation.
- UNIQUE(scheme_id, country_code) rejects duplicate insert on scheme_country.
- flyway_schema_history has version=002 with success=true.
- Index on qr_scheme(scheme_code) confirmed via pg_indexes query in Testcontainers test.
**Depends on:** 3.7-T02

### 3.7-T04 — V003 Flyway migration: partner and partner_credential tables  _(35 min)_
**Context:** partner: id BIGINT PK, partner_code VARCHAR(30) UNIQUE NOT NULL, name VARCHAR(100), partner_type VARCHAR(10) CHECK IN ('LOCAL','OVERSEAS') NOT NULL, collection_ccy CHAR(3), settle_a_ccy CHAR(3), rate_quote_ttl_seconds INT DEFAULT 300 CHECK BETWEEN 60 AND 1800, low_balance_threshold_usd DECIMAL(20,4) nullable (OVERSEAS only), status VARCHAR(20) CHECK IN ('ONBOARDING','ACTIVE','SUSPENDED','INACTIVE'), webhook_url VARCHAR(512), is_active BOOLEAN DEFAULT true, plus standard audit columns. partner_credential: api_key VARCHAR(64) UNIQUE, api_secret_hash VARCHAR(128), partner_id FK partner, is_active BOOLEAN, expires_at TIMESTAMPTZ nullable; partial UNIQUE index on (partner_id) WHERE is_active=true enforces one active credential per partner. Services: services/config-registry (writes), all payment services (reads via Redis cache).
**Steps:** Create db/migration/V003__create_partner.sql.; Write CREATE TABLE partner with all CHECK constraints, index on (partner_code), (status), (partner_type).; Write CREATE TABLE partner_credential with FK, UNIQUE on api_key, partial UNIQUE index CREATE UNIQUE INDEX uq_partner_cred_active ON partner_credential(partner_id) WHERE is_active=true.; Run flyway migrate; verify V003 in flyway_schema_history.; Add Testcontainers test: insert two active credentials for same partner_id; assert unique violation; insert one active + one inactive; assert success.
**Deliverable:** lib-persistence/src/main/resources/db/migration/V003__create_partner.sql
**Acceptance / logic checks:**
- partner_type='INVALID' raises CHECK constraint violation.
- Two active credentials for same partner_id raise unique index violation; one active + one inactive is allowed.
- rate_quote_ttl_seconds defaults to 300; value 59 raises CHECK violation.
- flyway_schema_history version=003 success=true.
**Depends on:** 3.7-T03

### 3.7-T05 — V004 Flyway migration: rule, service_charge_tier, and treasury_rate tables  _(40 min)_
**Context:** rule: UNIQUE(partner_id, scheme_id, direction); direction CHECK IN ('INBOUND','OUTBOUND','DOMESTIC','HUB'); m_a and m_b DECIMAL(8,6); status CHECK IN ('DRAFT','ACTIVE','SUSPENDED'); is_same_ccy_shortcircuit BOOLEAN; effective_from/to TIMESTAMPTZ. service_charge_tier: FK rule_id, min_collection_usd DECIMAL(20,4), max_collection_usd DECIMAL(20,4) nullable, charge_amount DECIMAL(20,4). treasury_rate: ccy_pair VARCHAR(10) NOT NULL, rate DECIMAL(20,8) NOT NULL, source VARCHAR(10) CHECK IN ('LIVE','MANUAL'), effective_at TIMESTAMPTZ; no unique on ccy_pair (historical rows); index on (ccy_pair, effective_at DESC) for fast latest-rate lookup. Services: services/rate-fx (rate-engine reads), services/config-registry (writes).
**Steps:** Create db/migration/V004__create_rule_treasury_rate.sql.; Write CREATE TABLE rule with FKs, UNIQUE(partner_id, scheme_id, direction), CHECK constraints on direction and status.; Write CREATE TABLE service_charge_tier with FK rule_id, CHECK min_collection_usd >= 0.; Write CREATE TABLE treasury_rate; CREATE INDEX idx_treasury_rate_ccy_pair ON treasury_rate(ccy_pair, effective_at DESC).; Run flyway migrate; verify V004.; Testcontainers test: assert UNIQUE(partner_id, scheme_id, direction) rejects duplicate; assert latest rate query uses index (EXPLAIN plan).
**Deliverable:** lib-persistence/src/main/resources/db/migration/V004__create_rule_treasury_rate.sql
**Acceptance / logic checks:**
- UNIQUE(partner_id, scheme_id, direction) rejects second DOMESTIC rule for same partner+scheme.
- direction='INVALID' raises CHECK constraint violation.
- Two treasury_rate rows with same ccy_pair but different effective_at are both stored; SELECT ... ORDER BY effective_at DESC LIMIT 1 returns the later row.
- flyway_schema_history version=004 success=true.
**Depends on:** 3.7-T04

### 3.7-T06 — V005 Flyway migration: rate_quote and transaction tables  _(45 min)_
**Context:** rate_quote stores rate-engine output per GET /v1/rates call. Key columns: quote_ref VARCHAR(64) UNIQUE, all USD-pool values DECIMAL(20,8) nullable for same-ccy (payout_usd_cost, collection_usd, collection_margin_usd, payout_margin_usd), send_amount DECIMAL(20,4), service_charge DECIMAL(20,4), collection_amount DECIMAL(20,4), offer_rate_coll DECIMAL(20,8), cross_rate DECIMAL(20,8), cost_rate_coll DECIMAL(20,8), cost_rate_pay DECIMAL(20,8), valid_until TIMESTAMPTZ, is_used BOOLEAN DEFAULT false. transaction: hub_txn_ref VARCHAR(64) UNIQUE, status CHECK IN ('QUOTED','PENDING_DEBIT','DEBITED','SCHEME_SENT','APPROVED','DECLINED','UNCERTAIN','FAILED','CANCELLED','REFUNDED'), immutable locked_* copies of all rate fields. Service: services/payment-executor, services/transaction-mgmt.
**Steps:** Create db/migration/V005__create_rate_quote_transaction.sql.; Write CREATE TABLE rate_quote with all columns; add indexes on (rule_id, valid_until), (partner_id, is_used), (quote_ref).; Write CREATE TABLE transaction with FK to rate_quote, partner, scheme, rule; status CHECK constraint; indexes on (partner_id, created_at DESC), (status, created_at), (hub_txn_ref), (scheme_ref).; Run flyway migrate; verify V005.; Testcontainers test: assert status='BAD' raises CHECK; assert hub_txn_ref is UNIQUE.
**Deliverable:** lib-persistence/src/main/resources/db/migration/V005__create_rate_quote_transaction.sql
**Acceptance / logic checks:**
- status='UNKNOWN' on transaction raises CHECK constraint violation.
- hub_txn_ref UNIQUE constraint rejects duplicate insert.
- rate_quote.valid_until column type is TIMESTAMPTZ (verify via JDBC metadata in Testcontainers test).
- flyway_schema_history version=005 success=true.
- EXPLAIN on transaction WHERE status='QUOTED' AND created_at > now()-interval '1 day' uses the (status, created_at) index.
**Depends on:** 3.7-T05

### 3.7-T07 — V006 Flyway migration: transaction_event, prefunding, and settlement tables  _(40 min)_
**Context:** transaction_event: FK transaction_id, event_type VARCHAR(40) CHECK IN ('RATE_QUOTE_ISSUED','PREFUND_DEDUCTED','SCHEME_SUBMITTED','SCHEME_APPROVED','SCHEME_DECLINED','TRANSACTION_COMMITTED','SETTLEMENT_BATCHED','WEBHOOK_DELIVERED'), payload JSONB, occurred_at TIMESTAMPTZ; index on (transaction_id, occurred_at). prefunding_account: partner_id FK UNIQUE (one per partner), balance_usd DECIMAL(20,4) DEFAULT 0, low_balance_threshold_usd DECIMAL(20,4). prefunding_ledger_entry: FK prefunding_account, amount_usd DECIMAL(20,4), entry_type CHECK IN ('DEBIT','CREDIT','REVERSAL'). settlement_batch, settlement_file, reconciliation_item tables. Services: services/payment-executor (events), services/prefunding (balance/ledger), services/settlement.
**Steps:** Create db/migration/V006__create_txn_events_prefunding_settlement.sql.; Write CREATE TABLE transaction_event with event_type CHECK, JSONB payload, index (transaction_id, occurred_at).; Write CREATE TABLE prefunding_account UNIQUE(partner_id); CREATE TABLE prefunding_ledger_entry with entry_type CHECK; index on (prefunding_account_id, created_at).; Write CREATE TABLE settlement_batch, settlement_file (status CHECK IN ('PENDING','GENERATED','TRANSMITTED','RECEIVED','RECONCILED','ERROR')), reconciliation_item (status CHECK IN ('MATCHED','MISMATCH','MISSING')).; Run flyway migrate; verify V006; Testcontainers test for event_type CHECK and prefunding_account UNIQUE.
**Deliverable:** lib-persistence/src/main/resources/db/migration/V006__create_txn_events_prefunding_settlement.sql
**Acceptance / logic checks:**
- event_type='STEP_99' raises CHECK constraint violation on transaction_event.
- UNIQUE(partner_id) on prefunding_account rejects second account for same partner.
- prefunding_ledger_entry.entry_type='ADJUSTMENT' raises CHECK violation.
- settlement_file.status='INVALID' raises CHECK violation.
- flyway_schema_history version=006 success=true.
**Depends on:** 3.7-T06

### 3.7-T08 — V007 Flyway migration: merchant and qr_code tables (PostgreSQL copy for payment-path reads)  _(35 min)_
**Context:** Payment-time merchant and QR validation queries GMEPay+'s local DB (synced from ZeroPay via SFTP), not ZeroPay live API. merchant: merchant_id VARCHAR(50) UNIQUE, merchant_type VARCHAR(30), name VARCHAR(200), business_registration_no VARCHAR(30), franchise_code VARCHAR(30), category_code VARCHAR(20), bank_code VARCHAR(10), account_no VARCHAR(30) AES-256 encrypted at rest via pgcrypto, fee_tier VARCHAR(20) CHECK IN ('DOMESTIC','CROSSBORDER'), status VARCHAR(20) CHECK IN ('ACTIVE','SUSPENDED','DEACTIVATED'), is_active BOOLEAN DEFAULT true, synced_at TIMESTAMPTZ. qr_code: FK merchant_id, qr_code_value VARCHAR(512) UNIQUE, qr_code_type VARCHAR(15) CHECK IN ('STATIC_MPM','DYNAMIC_CPM'), status VARCHAR(20) CHECK IN ('ACTIVE','DEACTIVATED'), is_active BOOLEAN, synced_at TIMESTAMPTZ. Service: services/merchant-qr.
**Steps:** Create db/migration/V007__create_merchant_qr_code.sql.; Write CREATE TABLE merchant with all columns; AES-256 note in column comment for account_no; index on (merchant_id), (is_active, status), (name varchar_pattern_ops for prefix search).; Write CREATE TABLE qr_code with FK merchant, UNIQUE(qr_code_value), CHECK constraints, index on (qr_code_value), (is_active).; Run flyway migrate; verify V007.; Testcontainers test: assert fee_tier CHECK, status CHECK, and qr_code_value UNIQUE constraint.
**Deliverable:** lib-persistence/src/main/resources/db/migration/V007__create_merchant_qr_code.sql
**Acceptance / logic checks:**
- fee_tier='INVALID' raises CHECK violation on merchant.
- qr_code_value UNIQUE constraint rejects duplicate QR string.
- status='UNKNOWN' raises CHECK violation on qr_code.
- Index on qr_code(qr_code_value) confirmed via pg_indexes query.
- flyway_schema_history version=007 success=true.
**Depends on:** 3.7-T07

### 3.7-T09 — V008 Flyway migration: audit_log, feature_flags, and sftp_file_log tables  _(30 min)_
**Context:** audit_log: entity_type VARCHAR(40), entity_id BIGINT, actor VARCHAR(60), action VARCHAR(40), previous_value JSONB, new_value JSONB, applied_at TIMESTAMPTZ; append-only (no update/delete by app). feature_flags: flag_name VARCHAR(60) UNIQUE, is_enabled BOOLEAN DEFAULT false, description VARCHAR(255), updated_at, updated_by. sftp_file_log: physical metadata for each SFTP file exchanged with ZeroPay: file_type VARCHAR(10), direction VARCHAR(4) CHECK IN ('IN','OUT'), filename VARCHAR(255), file_size_bytes BIGINT, checksum VARCHAR(64), object_storage_path VARCHAR(512), status VARCHAR(20) CHECK IN ('PENDING','TRANSMITTED','RECEIVED','ARCHIVED','ERROR'), transmitted_at TIMESTAMPTZ nullable, received_at TIMESTAMPTZ nullable. Service: services/settlement (sftp_file_log), services/config-registry (audit_log, feature_flags).
**Steps:** Create db/migration/V008__create_audit_log_feature_flags_sftp.sql.; Write CREATE TABLE audit_log; indexes on (entity_type, entity_id), (actor), (applied_at DESC).; Write CREATE TABLE feature_flags with UNIQUE(flag_name).; Write CREATE TABLE sftp_file_log with direction CHECK, status CHECK; index on (file_type, received_at), (status).; Run flyway migrate; verify V008.; Testcontainers test: assert audit_log allows NULL previous_value (new-entity creates); assert feature_flags UNIQUE constraint.
**Deliverable:** lib-persistence/src/main/resources/db/migration/V008__create_audit_log_feature_flags_sftp.sql
**Acceptance / logic checks:**
- audit_log accepts previous_value=NULL (no error on INSERT with NULL JSONB).
- feature_flags UNIQUE(flag_name) rejects duplicate flag name.
- sftp_file_log.direction='INVALID' raises CHECK violation.
- sftp_file_log.status='INVALID' raises CHECK violation.
- flyway_schema_history version=008 success=true.
**Depends on:** 3.7-T08

### 3.7-T10 — Flyway seed migration V009: reference data (treasury rates, ZeroPay scheme, partners, rules, feature flags)  _(35 min)_
**Context:** OPS-13 requires seed data applied automatically before app start in dev/test. A dedicated Flyway seed migration V009 inserts idempotent reference rows (INSERT ... ON CONFLICT DO NOTHING). Rows: treasury_rate: usd_krw=1350.00000000, usd_mnt=3500.00000000, usd_usd=1.00000000 (source=MANUAL). qr_scheme: ZEROPAY (payout_ccy=KRW, gme_fee_share_pct=0.7000, status=TESTING). scheme_country: ZEROPAY/KR. partner: GME_REMIT (LOCAL/KRW, ttl=300), SENDMN (OVERSEAS/USD, ttl=300, low_balance_threshold=10000). prefunding_account: SENDMN balance=50000.00 USD. rule: GME_REMIT+ZEROPAY+DOMESTIC (same-ccy, m_a=0, m_b=0), GME_REMIT+ZEROPAY+INBOUND (same-ccy, m_a=0.01, m_b=0.005), SENDMN+ZEROPAY+INBOUND (cross-border, m_a=0.015, m_b=0.010). feature_flags: five Phase-1 flags all is_enabled=false.
**Steps:** Create db/migration/V009__seed_reference_data.sql; add comment SEED-ONLY DO NOT RUN IN PRODUCTION WITHOUT REVIEW.; INSERT treasury_rate rows with ON CONFLICT (ccy_pair ... partial) DO NOTHING; effective_at=now(), created_by='SEED'.; INSERT qr_scheme, scheme_country, partner, prefunding_account with ON CONFLICT DO NOTHING and subqueries for FK resolution.; INSERT three rule rows using subqueries; ON CONFLICT(partner_id, scheme_id, direction) DO NOTHING.; INSERT five feature_flag rows ON CONFLICT(flag_name) DO NOTHING.
**Deliverable:** lib-persistence/src/main/resources/db/migration/V009__seed_reference_data.sql
**Acceptance / logic checks:**
- After flyway migrate from clean DB: treasury_rate has 3 rows, qr_scheme=1, scheme_country=1, partner=2, prefunding_account=1, rule=3, feature_flags=5.
- Running flyway migrate a second time (no schema change) is idempotent; V009 is not re-executed (already in flyway_schema_history).
- SELECT rate FROM treasury_rate WHERE ccy_pair='usd_usd' returns 1.00000000.
- SENDMN rule m_a+m_b=0.025 >= 0.02 (cross-border minimum margin satisfied): SELECT m_a+m_b FROM rule r JOIN partner p ON p.id=r.partner_id WHERE p.partner_code='SENDMN' returns 0.025000.
- All five feature_flags rows have is_enabled=false.
**Depends on:** 3.7-T09

### 3.7-T11 — Testcontainers integration test: full Flyway migration suite V001-V009  _(45 min)_
**Context:** lib-persistence module must have a Testcontainers JUnit 5 integration test that spins up a PostgreSQL 16 container, applies all migrations V001-V009 via the Flyway bean, and asserts the final schema and seed data. Test class: MigrationSuiteIntegrationTest in lib-persistence/src/test/java/com/gme/pay/persistence/. Uses @Testcontainers and PostgreSQLContainer from org.testcontainers:postgresql. Spring Boot test slice: @DataJpaTest with replaced datasource pointing at the container.
**Steps:** Add testcontainers BOM and postgresql artifact to lib-persistence/build.gradle (testImplementation scope).; Write MigrationSuiteIntegrationTest.java with @Testcontainers, @SpringBootTest(classes=MigrationTestConfig.class), PostgreSQLContainer<> field.; Assert flyway_schema_history has exactly 9 rows all with success=true.; Assert extension uuid-ossp exists; assert treasury_rate row count=3; assert feature_flags count=5.; Assert SENDMN rule m_a+m_b=0.025 via JDBC query.
**Deliverable:** lib-persistence/src/test/java/com/gme/pay/persistence/MigrationSuiteIntegrationTest.java
**Acceptance / logic checks:**
- Test passes with PostgreSQL 16 Testcontainers image in CI (no local DB required).
- flyway_schema_history has exactly 9 rows with success=true.
- Deliberate checksum mutation of V003 causes test to fail with FlywayValidateException.
- seed row usd_krw rate=1350.00000000 is present in treasury_rate.
- Test runtime under 60 seconds on a standard CI runner.
**Depends on:** 3.7-T10

### 3.7-T12 — MongoDB configuration: Spring Data MongoDB setup and collection naming conventions  _(30 min)_
**Context:** GMEPay+ uses MongoDB for merchant/QR read-models and CQRS projections (services/merchant-qr). The locked stack specifies MongoDB. Spring Data MongoDB is configured via spring.data.mongodb.uri (injected from env var MONGODB_URI, e.g. mongodb://localhost:27017/gmepay_dev). Collections are named with snake_case, prefixed by domain: merchant_read_model, qr_read_model, transaction_summary_projection, partner_balance_snapshot. Module: services/merchant-qr depends on lib-persistence; MongoDB config lives in services/merchant-qr/src/main/java/com/gme/pay/merchantqr/config/MongoConfig.java. Local dev: docker-compose mongodb service on port 27017.
**Steps:** Add spring-boot-starter-data-mongodb to services/merchant-qr/build.gradle.; Create MongoConfig.java annotated @Configuration with @EnableMongoRepositories(basePackages='com.gme.pay.merchantqr.repository') and a MongoMappingContext bean setting auto-index creation to false (create indexes explicitly).; Add MONGODB_URI to application.yml as spring.data.mongodb.uri: ${MONGODB_URI:mongodb://localhost:27017/gmepay_dev}.; Create MongoCollections.java constants class with static final Strings for each collection name (MERCHANT_READ_MODEL, QR_READ_MODEL, TRANSACTION_SUMMARY_PROJECTION, PARTNER_BALANCE_SNAPSHOT).; Write a Testcontainers test MongoConfigTest using MongoDBContainer that asserts the connection is alive and listCollectionNames() returns no error.
**Deliverable:** services/merchant-qr/src/main/java/com/gme/pay/merchantqr/config/MongoConfig.java; MongoCollections.java constants; Testcontainers MongoConfigTest.java
**Acceptance / logic checks:**
- Application context starts with a MongoDBContainer Testcontainers instance (no local Mongo needed).
- MONGODB_URI env var overrides the default URI in application.yml.
- MongoMappingContext auto-index creation is false (indexes created by explicit ensureIndex calls, not auto).
- MongoCollections.MERCHANT_READ_MODEL equals 'merchant_read_model' (verified in unit test).
**Depends on:** 3.7-T01

### 3.7-T13 — MongoDB document model: MerchantReadModel document class for merchant-qr service  _(40 min)_
**Context:** The merchant_read_model MongoDB collection is a denormalized read-model of the PostgreSQL merchant table, maintained by projections updated on ZeroPay SFTP sync events. Document fields mirror the merchant table but include the nested list of active QR codes. Document: _id (String = merchant_id), merchantType, name, businessRegistrationNo, franchiseCode, categoryCode, feeTier (DOMESTIC|CROSSBORDER), status (ACTIVE|SUSPENDED|DEACTIVATED), isActive (Boolean), syncedAt (Instant), activeQrCodes (List<QrCodeEmbed> with qrCodeValue String, qrCodeType String, isActive Boolean). Stored in collection merchant_read_model. Service: services/merchant-qr.
**Steps:** Create MerchantReadModel.java in services/merchant-qr/src/main/java/com/gme/pay/merchantqr/document/ annotated @Document(collection='merchant_read_model').; Add @Id String merchantId field and all merchant fields; add @Indexed on merchantId (unique=true), isActive, status.; Create nested static class QrCodeEmbed with qrCodeValue, qrCodeType, isActive fields.; Create MerchantReadModelRepository.java extending MongoRepository<MerchantReadModel, String> with custom query findByMerchantIdAndIsActiveTrue(String merchantId).; Write unit test with embedded MongoDBContainer asserting save, findById, and findByMerchantIdAndIsActiveTrue return correct results.
**Deliverable:** services/merchant-qr/src/main/java/com/gme/pay/merchantqr/document/MerchantReadModel.java; MerchantReadModelRepository.java
**Acceptance / logic checks:**
- MerchantReadModel with two embedded QrCodeEmbed objects saves and retrieves correctly from MongoDBContainer.
- findByMerchantIdAndIsActiveTrue returns present for active merchant, empty for isActive=false merchant.
- @Document collection name is 'merchant_read_model' (verified via reflection in unit test).
- Saving a second document with same merchantId (unique=true on @Indexed) throws DuplicateKeyException.
**Depends on:** 3.7-T12

### 3.7-T14 — MongoDB document model: QrReadModel document and repository for QR-code fast lookup  _(35 min)_
**Context:** The qr_read_model collection stores one document per QR code string for sub-millisecond lookup at payment time. A GET /v1/merchants/{qr} call hits this collection first (Redis cache miss falls back to Mongo). Document: _id (String = qrCodeValue), merchantId (String), merchantName, merchantStatus (ACTIVE|SUSPENDED|DEACTIVATED), feeTier, isActive (Boolean), qrCodeType (STATIC_MPM|DYNAMIC_CPM), syncedAt (Instant). Index: unique on _id (implicit), compound on (merchantId, isActive). Service: services/merchant-qr.
**Steps:** Create QrReadModel.java annotated @Document(collection='qr_read_model') with all fields; @Id is qrCodeValue.; Create QrReadModelRepository extending MongoRepository<QrReadModel, String>; add findByQrCodeValueAndIsActiveTrue method.; In MongoConfig.java add explicit index creation for compound (merchantId, isActive) on startup using MongoTemplate.indexOps('qr_read_model').ensureIndex(...).; Write Testcontainers test: save two QR docs for same merchantId, call findByQrCodeValueAndIsActiveTrue, assert correct result for active vs inactive.; Assert that a deactivated QR code (isActive=false) is NOT returned by findByQrCodeValueAndIsActiveTrue.
**Deliverable:** services/merchant-qr/src/main/java/com/gme/pay/merchantqr/document/QrReadModel.java; QrReadModelRepository.java
**Acceptance / logic checks:**
- findByQrCodeValueAndIsActiveTrue returns empty Optional when isActive=false even if qrCodeValue matches.
- Compound index (merchantId, isActive) exists after ensureIndex call (verified via MongoTemplate.indexOps().getIndexInfo()).
- Saving duplicate _id (qrCodeValue) throws DuplicateKeyException.
- Document round-trip: save, findById, assert all fields preserved including syncedAt Instant precision.
**Depends on:** 3.7-T13

### 3.7-T15 — MongoDB document model: TransactionSummaryProjection for read-side transaction search  _(40 min)_
**Context:** The transaction_summary_projection collection provides fast transaction search for the Ops portal (services/transaction-mgmt or services/reporting-compliance) without hitting PostgreSQL. Projection document: _id (String = txnRef), hubTxnRef, partnerId, partnerCode, schemeCode, paymentMode, direction, status, targetPayout (BigDecimal), payoutCcy, collectionAmount, collectionCcy, offerRateColl, committedAt (Instant), updatedAt. Indexes: (partnerId, committedAt DESC), (status), (hubTxnRef unique). This is a CQRS read-model updated by domain events from the payment Outbox. Service: services/transaction-mgmt (writes via EventPublisher), services/reporting-compliance (reads).
**Steps:** Create TransactionSummaryProjection.java annotated @Document(collection='transaction_summary_projection'); @Id = txnRef String.; Add @Indexed(unique=true) on hubTxnRef; @CompoundIndex on (partnerId, committedAt) and (status).; Create TransactionSummaryProjectionRepository with findByPartnerIdAndStatusOrderByCommittedAtDesc and findByHubTxnRef methods.; Add explicit ensureIndex calls in MongoConfig for compound indexes.; Write Testcontainers test: insert 3 projections for same partnerId with different statuses and committedAt; assert sort order and status filter.
**Deliverable:** services/transaction-mgmt/src/main/java/com/gme/pay/txnmgmt/document/TransactionSummaryProjection.java; TransactionSummaryProjectionRepository.java
**Acceptance / logic checks:**
- findByPartnerIdAndStatusOrderByCommittedAtDesc returns documents in descending committedAt order.
- Unique index on hubTxnRef throws DuplicateKeyException on duplicate insert.
- Compound index (partnerId, committedAt) confirmed via indexOps().getIndexInfo().
- BigDecimal fields (targetPayout, collectionAmount, offerRateColl) round-trip without precision loss.
**Depends on:** 3.7-T14

### 3.7-T16 — MongoDB document model: PartnerBalanceSnapshot projection for prefunding dashboard  _(35 min)_
**Context:** The partner_balance_snapshot collection provides a real-time read-model of OVERSEAS partner prefunding balances for the Ops portal and low-balance alert logic. Document: _id (String = partnerCode), partnerName, partnerType, balanceUsd (BigDecimal), lowBalanceThresholdUsd (BigDecimal), lastDebitAt (Instant), lastCreditAt (Instant), updatedAt (Instant). Updated by the prefunding service on every debit/credit event via the Outbox -> EventPublisher interface. Index: unique on _id (implicit). Service: services/prefunding (writes), services/reporting-compliance (reads).
**Steps:** Create PartnerBalanceSnapshot.java annotated @Document(collection='partner_balance_snapshot'); @Id = partnerCode String.; Add all fields with proper types (BigDecimal for monetary, Instant for timestamps).; Create PartnerBalanceSnapshotRepository extending MongoRepository<PartnerBalanceSnapshot,String>; add findByBalanceUsdLessThanEqualAndPartnerType for low-balance alerting.; Write Testcontainers test: save snapshot, update balance, assert updated value persisted; assert low-balance query returns partner when balance < threshold.; Verify balanceUsd BigDecimal stores 4 decimal places (50000.0000) without rounding.
**Deliverable:** services/prefunding/src/main/java/com/gme/pay/prefunding/document/PartnerBalanceSnapshot.java; PartnerBalanceSnapshotRepository.java
**Acceptance / logic checks:**
- findByBalanceUsdLessThanEqualAndPartnerType returns partner when balance=9999.00 and threshold=10000.00.
- findByBalanceUsdLessThanEqualAndPartnerType returns empty when balance=10000.00 (equal, not less than).
- BigDecimal 50000.0000 round-trips without conversion to Double or Float.
- Document update (save over existing _id) replaces all fields atomically.
**Depends on:** 3.7-T15

### 3.7-T17 — Redis configuration: Spring Data Redis setup and keyspace naming conventions  _(35 min)_
**Context:** GMEPay+ uses Redis 7 (Cluster mode in prod, single node for dev) for: rate-quote TTL cache (key rate_quote:{quote_ref}, TTL=partner's rate_quote_ttl_seconds, default 60s aggregator-bound / 300s otherwise, range 60-1800s), idempotency key store (key idempotency:{partner_id}:{idempotency_key}, TTL=86400s / 24h), config/registry hot cache (key config:partner:{partner_code}, config:rule:{rule_id}, config:treasury_rate:{ccy_pair}, no fixed TTL, invalidated on config change), distributed lock (key lock:batch:{job_name}, via SETNX). Module: services/rate-fx (rate-quote cache), services/payment-executor (idempotency), services/config-registry (config cache). Spring Boot autoconfigures RedisTemplate via spring.data.redis.url from env var REDIS_URL.
**Steps:** Add spring-boot-starter-data-redis to services/rate-fx, services/payment-executor, services/config-registry build.gradle.; Create RedisConfig.java in each service's config package with @EnableRedisRepositories and a StringRedisTemplate bean plus a typed RedisTemplate<String, byte[]> bean using JdkSerializationRedisSerializer for values.; Create RedisKeySpace.java constants class with static final String templates: RATE_QUOTE (rate_quote:{0}), IDEMPOTENCY (idempotency:{0}:{1}), CONFIG_PARTNER (config:partner:{0}), CONFIG_RULE (config:rule:{0}), CONFIG_TREASURY (config:treasury_rate:{0}), BATCH_LOCK (lock:batch:{0}).; Add spring.data.redis.url: ${REDIS_URL:redis://localhost:6379} to each service application.yml.; Write Testcontainers test using GenericContainer with redis:7-alpine image; assert PING returns PONG; assert key construction using RedisKeySpace.format() matches expected strings.
**Deliverable:** services/rate-fx/src/main/java/com/gme/pay/ratefx/config/RedisConfig.java; shared RedisKeySpace.java in lib-persistence; Testcontainers RedisConfigTest.java
**Acceptance / logic checks:**
- RedisKeySpace.format(RATE_QUOTE, 'abc123') returns 'rate_quote:abc123'.
- RedisKeySpace.format(IDEMPOTENCY, 'GME_REMIT', 'key1') returns 'idempotency:GME_REMIT:key1'.
- Testcontainers Redis container responds to PING with PONG via StringRedisTemplate.
- REDIS_URL env var overrides the default localhost URI.
**Depends on:** 3.7-T01

### 3.7-T18 — Redis rate-quote cache: store and retrieve RateQuoteCache DTO with TTL  _(40 min)_
**Context:** When the rate engine issues a quote (GET /v1/rates), the result is stored in Redis under key rate_quote:{quote_ref} with TTL = partner's rate_quote_ttl_seconds (default 300s; 60s if aggregator-bound; configured on partner.rate_quote_ttl_seconds, range 60-1800s). The validUntil = quote_issued_at + TTL. On POST /v1/payments (commit), the orchestrator calls lockRateQuote(quote_ref): if key is absent (expired) the transaction is FAILED with RATE_QUOTE_EXPIRED. Value: JSON-serialized RateQuoteCache DTO with quoteRef, ruleId, partnerId, all five USD-pool DECIMAL values, send_amount, service_charge, collection_amount, offerRateColl, crossRate, validUntil. Service: services/rate-fx.
**Steps:** Create RateQuoteCacheDto.java in services/rate-fx/src/main/java/com/gme/pay/ratefx/cache/ with all required fields using BigDecimal for monetary values.; Create RateQuoteCacheService.java @Service with methods: store(RateQuoteCacheDto dto, int ttlSeconds) using RedisTemplate.opsForValue().set(key, serialized, ttlSeconds, SECONDS); Optional<RateQuoteCacheDto> get(String quoteRef); boolean lockAndInvalidate(String quoteRef) using RedisTemplate.delete().; Use Jackson ObjectMapper for serialization; store as JSON byte[].; Write RateQuoteCacheServiceTest.java with Testcontainers Redis 7: store a quote with ttl=2s, assert get() returns present within 1s, assert get() returns empty after 2.1s sleep.; Assert lockAndInvalidate deletes key and returns true on first call, false on second call (key already gone).
**Deliverable:** services/rate-fx/src/main/java/com/gme/pay/ratefx/cache/RateQuoteCacheService.java; RateQuoteCacheDto.java; RateQuoteCacheServiceTest.java
**Acceptance / logic checks:**
- get(quoteRef) returns present within TTL; returns empty after TTL expiry (Testcontainers test with 2s TTL).
- lockAndInvalidate returns true first call (key present), false second call (key already deleted).
- BigDecimal fields (collection_usd with DECIMAL(20,8) scale) serialize and deserialize without precision loss via Jackson.
- TTL of 60s is accepted (lower bound); TTL of 1800s is accepted (upper bound); any value below 60 is rejected with IllegalArgumentException before store.
**Depends on:** 3.7-T17

### 3.7-T19 — Redis idempotency key store: write and read idempotency responses with 24h TTL  _(40 min)_
**Context:** All POST endpoints require an Idempotency-Key header. GMEPay+ stores (partner_id, idempotency_key) -> (status_code, response_body) for 24 hours (86400s TTL). On duplicate POST within 24h: return stored response without re-processing. If body differs from first call: return HTTP 422 IDEMPOTENCY_BODY_MISMATCH. Key pattern: idempotency:{partner_code}:{idempotency_key}. Value: JSON with statusCode (int), responseBody (String), requestBodyHash (SHA-256 hex of original request body). Service: services/payment-executor (Spring @Service IdempotencyService, called from @RestController via Spring Cloud Gateway filter that enforces Idempotency-Key header presence).
**Steps:** Create IdempotencyRecord.java DTO with statusCode, responseBody, requestBodyHash fields.; Create IdempotencyService.java @Service with: Optional<IdempotencyRecord> get(String partnerCode, String idempotencyKey); void store(String partnerCode, String idempotencyKey, IdempotencyRecord record) with TTL=86400s; boolean isBodyMatch(IdempotencyRecord stored, String incomingBodyHash).; Implement isBodyMatch: return stored.getRequestBodyHash().equals(incomingBodyHash).; Write IdempotencyServiceTest with Testcontainers Redis: store a record, assert get() returns it; store again with different hash, assert isBodyMatch=false; assert key expires after TTL.; Assert storing the same key twice with same hash is idempotent (no overwrite, same TTL preserved using SET NX mode).
**Deliverable:** services/payment-executor/src/main/java/com/gme/pay/executor/idempotency/IdempotencyService.java; IdempotencyRecord.java; IdempotencyServiceTest.java
**Acceptance / logic checks:**
- get() returns present for stored key within 24h; absent after TTL (tested with 2s TTL in test).
- isBodyMatch returns false when incomingBodyHash differs from stored hash.
- SET NX semantics: second store() call with same key does NOT overwrite the existing record.
- Key format is 'idempotency:{partnerCode}:{idempotencyKey}' (assert via RedisKeySpace.format).
**Depends on:** 3.7-T18

### 3.7-T20 — Redis config cache: store and invalidate partner and rule config with pub/sub eviction  _(45 min)_
**Context:** services/config-registry maintains a Redis hot cache of active partner, rule, and treasury_rate config. Keys: config:partner:{partnerCode} (no fixed TTL, invalidated on change), config:rule:{ruleId} (no fixed TTL), config:treasury_rate:{ccyPair} (no fixed TTL). On any config entity update via the Admin API, the config-registry @Service publishes a cache-invalidation event via RedisTemplate.convertAndSend('config-cache-invalidation', key) and deletes the key. Consumer services (rate-fx, payment-executor) subscribe with @RedisListener on channel config-cache-invalidation and evict local in-memory caches (Spring Caffeine L1 cache). Distributed locking: batch jobs use SETNX lock:batch:{jobName} with TTL=300s.
**Steps:** Create ConfigCacheService.java @Service in services/config-registry with get/store/invalidate methods using RedisTemplate; invalidate deletes key AND publishes to channel 'config-cache-invalidation'.; Create ConfigCacheInvalidationListener.java in services/rate-fx implementing MessageListener for channel config-cache-invalidation; on message evict matching Caffeine cache entry.; Create BatchLockService.java @Service in services/settlement with acquireLock(jobName, ttlSeconds): uses RedisTemplate.opsForValue().setIfAbsent(key, hostname, ttl, SECONDS); returns true if acquired.; Write Testcontainers tests: assert invalidate deletes key; assert pub/sub message received by listener; assert SETNX acquireLock returns true first call and false second call.; Assert lock expires after TTL (test with ttl=1s; assert second acquireLock returns true after 1.1s).
**Deliverable:** services/config-registry/src/main/java/com/gme/pay/config/cache/ConfigCacheService.java; services/settlement/src/main/java/com/gme/pay/settlement/lock/BatchLockService.java; ConfigCacheInvalidationListener.java
**Acceptance / logic checks:**
- invalidate(key) deletes Redis key and publishes message to config-cache-invalidation channel.
- acquireLock returns true on first call; false on second call before TTL expires.
- acquireLock returns true after TTL expiry (lock released by timeout).
- ConfigCacheInvalidationListener receives pub/sub message and evicts cache entry (verified in Testcontainers test with embedded listener).
**Depends on:** 3.7-T19

### 3.7-T21 — Redis Testcontainers integration test: rate-quote TTL, idempotency, config cache end-to-end  _(40 min)_
**Context:** A combined integration test validates all three Redis keyspace behaviors in a single Testcontainers Redis 7 container. This ensures keyspace isolation (keys from different domains do not collide), TTL semantics work correctly, and the KeySpace naming convention is enforced. Test class: RedisKeyspaceIntegrationTest in lib-persistence/src/test/java/com/gme/pay/persistence/. Uses GenericContainer with redis:7-alpine.
**Steps:** Write RedisKeyspaceIntegrationTest.java with @Testcontainers, GenericContainer redis:7-alpine.; Test 1 (rate-quote): store quote with TTL=2s; assert present at t=0.5s; assert absent at t=2.5s.; Test 2 (idempotency): store record with SET NX TTL=2s; assert second store() does NOT overwrite; assert absent at t=2.5s.; Test 3 (config cache): store config entry (no TTL), invalidate it, assert absent; assert pub/sub message received.; Test 4 (batch lock): acquire lock, assert second acquire fails; wait TTL, assert re-acquire succeeds.
**Deliverable:** lib-persistence/src/test/java/com/gme/pay/persistence/RedisKeyspaceIntegrationTest.java
**Acceptance / logic checks:**
- All four test scenarios pass in CI with Redis 7 Testcontainers image.
- Key collision test: rate_quote:abc123 and idempotency:GME_REMIT:abc123 are independent keys (deleting one does not affect the other).
- Test 1 TTL expiry is verified with actual time-based assertion (not just key deletion).
- Test runs in under 30 seconds total.
**Depends on:** 3.7-T20

### 3.7-T22 — Object Storage configuration: Spring Cloud AWS S3 client setup for MinIO/S3-compatible storage  _(40 min)_
**Context:** GMEPay+ uses S3-compatible object storage (MinIO for local/on-prem, AWS S3 ap-northeast-2 for prod) for: SFTP batch file archive (/gmepay/batch/{direction}/{file-type}/YYYY-MM-DD/{filename}), settlement reports (/gmepay/reports/{type}/YYYY/MM/{filename}), WAL archives, and compliance exports. Buckets: gmepay-batch-files (versioning enabled, immutable after write, 7-year retention), gmepay-reports (versioning enabled), gmepay-archives (versioning enabled). Credentials from env: AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, AWS_REGION (default ap-northeast-2), S3_ENDPOINT_URL (for MinIO override). Service: services/settlement (batch files), services/reporting-compliance (reports). Module: lib-persistence provides shared ObjectStorageConfig.
**Steps:** Add software.amazon.awssdk:s3 and software.amazon.awssdk:url-connection-client to lib-persistence/build.gradle.; Create ObjectStorageConfig.java @Configuration in lib-persistence with @Bean S3Client built from env vars AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, AWS_REGION, and optional S3_ENDPOINT_URL override (for MinIO).; Create ObjectStorageBuckets.java constants with static final Strings: BATCH_FILES='gmepay-batch-files', REPORTS='gmepay-reports', ARCHIVES='gmepay-archives'.; Create ObjectStoragePathBuilder.java @Component with buildBatchPath(direction, fileType, date, filename) returning /gmepay/batch/{direction}/{fileType}/YYYY-MM-DD/{filename}.; Write Testcontainers test using localstack/localstack image with S3 service; assert bucket creation and PutObject/GetObject round-trip.
**Deliverable:** lib-persistence/src/main/java/com/gme/pay/persistence/storage/ObjectStorageConfig.java; ObjectStorageBuckets.java; ObjectStoragePathBuilder.java
**Acceptance / logic checks:**
- ObjectStoragePathBuilder.buildBatchPath('OUT','ZP0011', LocalDate.of(2026,6,5), 'ZP0011_20260605.txt') returns '/gmepay/batch/OUT/ZP0011/2026-06-05/ZP0011_20260605.txt'.
- S3Client uses S3_ENDPOINT_URL when set (MinIO override) and ignores it when absent (AWS default endpoint).
- PutObject to localstack bucket succeeds; GetObject returns identical bytes.
- Bucket name constants match: gmepay-batch-files, gmepay-reports, gmepay-archives.
**Depends on:** 3.7-T01

### 3.7-T23 — Object Storage service: store and retrieve SFTP batch files with checksum verification  _(45 min)_
**Context:** All inbound and outbound SFTP files (ZP00xx types) are stored in object storage immediately after transmission or receipt at path /gmepay/batch/{direction}/{file-type}/YYYY-MM-DD/{filename}. Object versioning is enabled; files are immutable once written. After upload, the SHA-256 checksum is computed and stored as S3 object metadata (x-amz-meta-checksum). On daily integrity check: retrieve the stored file and verify checksum matches the value in sftp_file_log.file_checksum. Service: services/settlement (BatchFileStorageService). Uses lib-persistence ObjectStorageConfig S3Client.
**Steps:** Create BatchFileStorageService.java @Service in services/settlement with methods: store(String direction, String fileType, LocalDate date, String filename, byte[] content): computes SHA-256, calls S3Client.putObject with metadata; retrieve(String path): returns byte[]; verifyChecksum(String path, String expectedChecksum): computes SHA-256 of retrieved bytes and compares.; Use MessageDigest SHA-256 for checksum; encode as hex string.; Write BatchFileStorageServiceTest with localstack Testcontainers: store a file, assert object exists, assert retrieved bytes match original, assert verifyChecksum returns true.; Assert verifyChecksum returns false when expected checksum differs from actual.; Assert duplicate store to same path succeeds (object versioning — no overwrite error; latest version is retrieved).
**Deliverable:** services/settlement/src/main/java/com/gme/pay/settlement/storage/BatchFileStorageService.java; BatchFileStorageServiceTest.java
**Acceptance / logic checks:**
- store() uploads to correct path /gmepay/batch/OUT/ZP0011/2026-06-05/ZP0011_20260605.txt.
- retrieve() returns byte[] identical to stored content.
- verifyChecksum returns true when checksum matches; false when it differs (test with tampered expectedChecksum).
- SHA-256 is computed server-side and stored as x-amz-meta-checksum object metadata.
- Testcontainers localstack S3 test passes in CI.
**Depends on:** 3.7-T22

### 3.7-T24 — Object Storage service: store settlement reports and compliance exports  _(40 min)_
**Context:** The gmepay-reports bucket stores BOK reports (FX1014, FX1015), revenue reports by partner/scheme/period, and reconciliation exports. Path convention: /gmepay/reports/{reportType}/YYYY/MM/{filename} (e.g. /gmepay/reports/BOK_FX1015/2026/06/bok_fx1015_20260605.csv). Report files are write-once immutable. Retention: 7 years (SEC-09). The gmepay-archives bucket stores WAL archives and long-term compliance data. Service: services/reporting-compliance (ReportStorageService).
**Steps:** Create ReportStorageService.java @Service in services/reporting-compliance with: storeReport(String reportType, YearMonth period, String filename, byte[] content): uploads to gmepay-reports bucket with correct path; String getReportUrl(reportType, period, filename): returns presigned URL with 1h expiry for Ops portal download.; Generate presigned URL via S3Presigner with expiry=Duration.ofHours(1).; Write ReportStorageServiceTest with localstack Testcontainers: upload a report, assert presigned URL is valid (HTTP 200 GET), assert path matches /gmepay/reports/BOK_FX1015/2026/06/bok_fx1015_20260605.csv.; Assert storeReport is idempotent (second upload of same key succeeds without error; versioning preserves both).; Assert report bytes retrieved via presigned URL are identical to uploaded bytes.
**Deliverable:** services/reporting-compliance/src/main/java/com/gme/pay/reporting/storage/ReportStorageService.java; ReportStorageServiceTest.java
**Acceptance / logic checks:**
- storeReport uploads to gmepay-reports bucket at path /gmepay/reports/{reportType}/{YYYY}/{MM}/{filename}.
- Presigned URL expires after 1h (verified by expiry timestamp in URL query params).
- Second upload to same key succeeds (versioning enabled; no exception).
- Testcontainers localstack test passes in CI.
**Depends on:** 3.7-T23

### 3.7-T25 — Object Storage bucket initialization: ensure-buckets startup bean with versioning and lifecycle  _(45 min)_
**Context:** On application startup (services/settlement and services/reporting-compliance), the system must ensure that the three S3 buckets (gmepay-batch-files, gmepay-reports, gmepay-archives) exist and have versioning enabled. If the bucket does not exist, create it. If it exists but versioning is disabled, enable it. Lifecycle rule: gmepay-batch-files objects transition to GLACIER after 90 days and expire after 7 years (2555 days). Bucket location: ap-northeast-2 (Korea data residency per OPS-13 sec 10.4) when AWS_REGION=ap-northeast-2; for MinIO (S3_ENDPOINT_URL set) skip location constraint.
**Steps:** Create BucketInitializer.java @Component in lib-persistence implementing ApplicationRunner with run() method.; For each bucket name in ObjectStorageBuckets: call S3Client.headBucket(); if NoSuchBucketException create it; call PutBucketVersioning with Status=ENABLED.; Add PutBucketLifecycleConfiguration for gmepay-batch-files: transition to GLACIER after 90 days, expiration after 2555 days.; Skip lifecycle configuration when S3_ENDPOINT_URL is set (MinIO does not support lifecycle fully).; Write BucketInitializerTest with localstack Testcontainers: assert all three buckets exist after run(); assert versioning=ENABLED on each; run again (idempotent) — no error.
**Deliverable:** lib-persistence/src/main/java/com/gme/pay/persistence/storage/BucketInitializer.java; BucketInitializerTest.java
**Acceptance / logic checks:**
- After startup all three buckets exist (headBucket() returns 200).
- getBucketVersioning() returns Status=ENABLED for all three buckets.
- Running BucketInitializer twice raises no error (idempotent: createBucket returns existing gracefully).
- Lifecycle rule on gmepay-batch-files has transition to GLACIER at day 90 and expiration at day 2555 (verified via GetBucketLifecycleConfiguration).
**Depends on:** 3.7-T24

### 3.7-T26 — MongoDB Testcontainers integration test: merchant read-model and QR lookup end-to-end  _(45 min)_
**Context:** A combined Testcontainers integration test validates MongoDB document persistence, index behavior, and query correctness for MerchantReadModel, QrReadModel, TransactionSummaryProjection, and PartnerBalanceSnapshot. Uses MongoDBContainer from testcontainers:mongodb. Test class: MongoReadModelIntegrationTest in services/merchant-qr/src/test/java/. Covers the CQRS read-model update flow: simulate a merchant sync event updating MerchantReadModel and QrReadModel, then assert lookup results match.
**Steps:** Write MongoReadModelIntegrationTest with @Testcontainers and MongoDBContainer.; Test 1: save MerchantReadModel with 2 active QrCodeEmbed; findByMerchantIdAndIsActiveTrue returns the document; update isActive=false; assert not returned.; Test 2: save QrReadModel for active QR; findByQrCodeValueAndIsActiveTrue returns it; save deactivated QR with same merchantId; assert deactivated QR not returned.; Test 3: save 3 TransactionSummaryProjection with same partnerId, different status and committedAt; assert findByPartnerIdAndStatusOrderByCommittedAtDesc returns correct subset in correct order.; Test 4: save PartnerBalanceSnapshot for SENDMN with balance=9000; assert findByBalanceUsdLessThanEqualAndPartnerType('OVERSEAS',10000) returns it.
**Deliverable:** services/merchant-qr/src/test/java/com/gme/pay/merchantqr/MongoReadModelIntegrationTest.java
**Acceptance / logic checks:**
- All four test scenarios pass with MongoDBContainer (no local Mongo required).
- Deactivated merchant (isActive=false) not returned by findByMerchantIdAndIsActiveTrue.
- TransactionSummaryProjection query returns results in descending committedAt order.
- PartnerBalanceSnapshot balance=9000 is returned by less-than-threshold query at threshold=10000; balance=10001 is not returned.
- Test runs in under 45 seconds in CI.
**Depends on:** 3.7-T16

### 3.7-T27 — docker-compose local dev infra: add MongoDB and Redis services  _(30 min)_
**Context:** The GMEPay+ docker-compose.yml (at repo root or infra/docker-compose.yml) provides local dev infrastructure per the STACK.md locked stack. It must include: MongoDB 7 on port 27017 with volume gmepay_mongo_data, Redis 7 on port 6379 with volume gmepay_redis_data (and appendonly yes for persistence), MinIO (latest) on port 9000 (API) and 9001 (console) with env MINIO_ROOT_USER and MINIO_ROOT_PASSWORD and volumes gmepay_minio_data. PostgreSQL 16 on port 5433 is assumed already present. All services on network gmepay-dev.
**Steps:** Open infra/docker-compose.yml (or create if absent); add mongodb service: image mongo:7, ports 27017:27017, volumes gmepay_mongo_data:/data/db, network gmepay-dev.; Add redis service: image redis:7-alpine, command redis-server --appendonly yes, ports 6379:6379, volumes gmepay_redis_data:/data, network gmepay-dev.; Add minio service: image minio/minio:latest, command server /data --console-address :9001, env MINIO_ROOT_USER=minioadmin MINIO_ROOT_PASSWORD=minioadmin, ports 9000:9000 and 9001:9001, volumes gmepay_minio_data:/data, network gmepay-dev.; Add named volumes gmepay_mongo_data, gmepay_redis_data, gmepay_minio_data to the volumes section.; Run docker-compose up -d and verify all services are healthy; run docker-compose ps and confirm each is Up.
**Deliverable:** infra/docker-compose.yml with MongoDB 7, Redis 7, and MinIO services added
**Acceptance / logic checks:**
- docker-compose up -d starts all services without error.
- mongosh --host localhost:27017 --eval 'db.runCommand({ping:1})' returns ok:1.
- redis-cli -h localhost -p 6379 PING returns PONG.
- curl http://localhost:9000/minio/health/live returns HTTP 200.
- docker-compose down removes containers; docker-compose up recreates them with data preserved in named volumes.
**Depends on:** 3.7-T01

### 3.7-T28 — CI integration: add MongoDB and Redis Testcontainers configuration to CI pipeline  _(45 min)_
**Context:** The CI pipeline (OPS-13 pipeline stages) runs integration tests with Testcontainers. However, the CI environment must be configured to allow Testcontainers to pull images for mongodb:7 and redis:7-alpine (and localstack/localstack for S3 tests). The Gradle build must distinguish unit tests (no containers) from integration tests (with containers) using a custom test source set or JUnit 5 tag @Tag('integration'). Integration tests run in a dedicated Gradle task integrationTest that is gated after unit tests. Affected modules: lib-persistence, services/merchant-qr, services/rate-fx, services/payment-executor, services/settlement.
**Steps:** In root build.gradle add a Gradle sourceSets configuration for integrationTest or configure JUnit 5 tag filtering: unitTest excludes @Tag('integration'), integrationTest includes only @Tag('integration').; Annotate all Testcontainers test classes with @Tag('integration').; In .github/workflows/ci.yml (or equivalent) add a step run: ./gradlew integrationTest after the unitTest step; set TESTCONTAINERS_RYUK_DISABLED=true and DOCKER_HOST env vars for the CI Docker socket.; Add Testcontainers reuse configuration (testcontainers.reuse.enable=true in .testcontainers.properties) to speed up CI.; Verify CI run: unit tests pass without Docker; integration tests pass with containers; deliberately break one migration (typo in SQL) and assert integrationTest step fails.
**Deliverable:** .github/workflows/ci.yml updated with integrationTest step; Gradle integrationTest task configured in root build.gradle; @Tag('integration') on all Testcontainers test classes
**Acceptance / logic checks:**
- ./gradlew test (unit tests) passes without Docker daemon running.
- ./gradlew integrationTest passes when Docker is available and pulls mongodb:7, redis:7-alpine, localstack images.
- A deliberate SQL syntax error in V003 migration causes integrationTest to fail with meaningful error message.
- CI pipeline step integrationTest appears after unitTest and before the artifact build step.
**Depends on:** 3.7-T21, 3.7-T26, 3.7-T25


## WBS 8.7 — Error model & codes
### 8.7-T01 — Define ApiError and ErrorDetail Java records in lib-errors  _(30 min)_
**Context:** WBS 8.7: lib-errors is a Gradle shared module (libs/lib-errors/). The canonical error envelope from API-05 §8.1 is: {error:{code:string,message:string,request_id:string,details:[{field:string,issue:string}]}}. All services must return this structure on every non-2xx response. Records must be Jackson-serializable and carry no Spring dependencies so lib-errors can be used in gateway filters and adapters.
**Steps:** In libs/lib-errors/src/main/java/com/gme/pay/errors/ create immutable record ApiError(String code, String message, String requestId, List<ErrorDetail> details) annotated @JsonInclude(NON_EMPTY).; Create record ErrorDetail(String field, String issue).; Add @JsonProperty aliases code, message, request_id, details (snake_case for API consumers).; Add factory method ApiError.of(GmeErrorCode code, String requestId) using code.getMessage() as default message.; Add factory method ApiError.withDetails(GmeErrorCode code, String requestId, List<ErrorDetail> details) for validation errors.; Add libs/lib-errors/build.gradle with jackson-databind only; no spring-web.
**Deliverable:** libs/lib-errors/src/main/java/com/gme/pay/errors/ApiError.java and ErrorDetail.java
**Acceptance / logic checks:**
- ApiError serialises to {error:{code:...,message:...,request_id:...,details:[...]}} with vanilla Jackson ObjectMapper (no Spring context).
- Empty details list is omitted from JSON (NON_EMPTY).
- ApiError.of(GmeErrorCode.RATE_QUOTE_EXPIRED, req1) produces code string RATE_QUOTE_EXPIRED.
- ApiError.withDetails uses the provided list verbatim.
- Module compiles independently without spring-web on classpath.

### 8.7-T02 — Define GmeErrorCode enum with HTTP status and retryable flag for all 37 catalog entries  _(35 min)_
**Context:** WBS 8.7: Every error code from API-05 §8.2 plus ZeroPay mapping table SCH-06 §10.2 must live in a single enum in lib-errors. Each constant carries (1) int httpStatus, (2) boolean retryable, (3) String defaultMessage. Full catalog: VALIDATION_ERROR(400,false), MISSING_IDEMPOTENCY_KEY(400,false), CANCEL_NOT_PERMITTED(400,false), INVALID_SIGNATURE(401,false), INVALID_API_KEY(401,false), TIMESTAMP_OUT_OF_RANGE(401,false), INVALID_CREDENTIALS(401,false), INSUFFICIENT_PREFUNDING(402,false), FORBIDDEN(403,false), IP_NOT_ALLOWLISTED(403,false), PAYMENT_NOT_FOUND(404,false), MERCHANT_NOT_FOUND(404,false), SCHEME_NOT_FOUND(404,false), DUPLICATE_PARTNER_TXN_REF(409,false), DUPLICATE_TRANSACTION(409,false), IDEMPOTENCY_KEY_REUSE(422,false), RATE_QUOTE_EXPIRED(422,false), RATE_QUOTE_INVALID(422,false), PARTNER_B_QUOTE_DEVIATION(422,false), PARTNER_B_QUOTE_UNAVAILABLE(422,true), SCHEME_UNAVAILABLE(422,true), PAYMENT_MODE_NOT_SUPPORTED(422,false), DIRECTION_NOT_ENABLED(422,false), MERCHANT_INACTIVE(422,false), QR_DEACTIVATED(422,false), QR_EXPIRED(422,false), AMOUNT_TOO_LOW(422,false), AMOUNT_TOO_HIGH(422,false), QR_INVALID_CHECKSUM(422,false), QR_MALFORMED(422,false), NO_SCHEME_FOR_LOCATION(422,false), RATE_LIMITED(429,true), SCHEME_ERROR(502,true), SCHEME_TIMEOUT(504,true), INTERNAL_ERROR(500,true), SERVICE_UNAVAILABLE(503,true). Also add PARTNER_B_QUOTE_DEVIATION note: no prefunding deduction occurs; partner must re-quote with new Idempotency-Key.
**Steps:** Create enum GmeErrorCode(int httpStatus, boolean retryable, String defaultMessage) in libs/lib-errors/src/main/java/com/gme/pay/errors/.; Add all 36 constants listed in context with correct values.; Add accessors getHttpStatus(), isRetryable(), getMessage().; Annotate @JsonValue on name() so enum serialises as the code string (e.g. RATE_QUOTE_EXPIRED).
**Deliverable:** libs/lib-errors/src/main/java/com/gme/pay/errors/GmeErrorCode.java
**Acceptance / logic checks:**
- GmeErrorCode.PARTNER_B_QUOTE_UNAVAILABLE.isRetryable() == true.
- GmeErrorCode.INSUFFICIENT_PREFUNDING.getHttpStatus() == 402 and isRetryable() == false.
- GmeErrorCode.RATE_LIMITED.getHttpStatus() == 429 and isRetryable() == true.
- GmeErrorCode.INVALID_SIGNATURE.isRetryable() == false.
- Enum values().length == 36.
- Jackson serialises GmeErrorCode.RATE_QUOTE_EXPIRED as the string RATE_QUOTE_EXPIRED.
**Depends on:** 8.7-T01

### 8.7-T03 — Create GmeException runtime exception wrapping GmeErrorCode  _(20 min)_
**Context:** WBS 8.7: Services throw GmeException(GmeErrorCode, optional message override, optional List<ErrorDetail>) which the global exception handler translates to the ApiError envelope. The exception lives in lib-errors with no Spring dependency.
**Steps:** Create class GmeException extends RuntimeException in libs/lib-errors/src/main/java/com/gme/pay/errors/.; Add fields: GmeErrorCode errorCode, String messageOverride, List<ErrorDetail> details.; Add constructors: (GmeErrorCode), (GmeErrorCode,String), (GmeErrorCode,List<ErrorDetail>), (GmeErrorCode,String,List<ErrorDetail>).; Add getErrorCode(), getDetails(), resolveMessage() — returns messageOverride if non-null else errorCode.getMessage().; Add static factories of(GmeErrorCode) and withDetails(GmeErrorCode,List<ErrorDetail>).
**Deliverable:** libs/lib-errors/src/main/java/com/gme/pay/errors/GmeException.java
**Acceptance / logic checks:**
- GmeException(GmeErrorCode.RATE_QUOTE_EXPIRED).resolveMessage() returns the enum default message.
- GmeException(RATE_QUOTE_EXPIRED,custom).resolveMessage() returns custom.
- getErrorCode() returns the code passed at construction.
- Static of() returns a GmeException instance with correct code.
- Compiles without Spring or Jackson on classpath.
**Depends on:** 8.7-T02

### 8.7-T04 — Implement GlobalExceptionHandler @RestControllerAdvice in lib-errors  _(40 min)_
**Context:** WBS 8.7: Every Spring Boot service must map GmeException and standard Spring exceptions to the API-05 §8.1 envelope. The handler lives in lib-errors as a Spring-autoconfigured @RestControllerAdvice so each service auto-registers it. It reads X-Request-ID from the request to populate error.request_id. Handles: (1) GmeException — HTTP status from errorCode.getHttpStatus(); (2) MethodArgumentNotValidException — 400 VALIDATION_ERROR with per-field ErrorDetail list; (3) fallback Exception — 500 INTERNAL_ERROR.
**Steps:** Create class GlobalExceptionHandler annotated @RestControllerAdvice in libs/lib-errors/src/main/java/com/gme/pay/errors/web/.; @ExceptionHandler(GmeException.class): build ApiError from exception, set HTTP status from errorCode.getHttpStatus(), populate request_id from HttpServletRequest X-Request-ID header.; @ExceptionHandler(MethodArgumentNotValidException.class): map BindingResult field errors to ErrorDetail list, return VALIDATION_ERROR 400.; @ExceptionHandler(Exception.class): log at ERROR level (no stack trace in response body), return INTERNAL_ERROR 500 with request_id.; Register via META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports.; Add spring-web and spring-boot-autoconfigure as compileOnly/api in libs/lib-errors/build.gradle.
**Deliverable:** libs/lib-errors/src/main/java/com/gme/pay/errors/web/GlobalExceptionHandler.java and META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
**Acceptance / logic checks:**
- MockMvc: controller throws GmeException(RATE_QUOTE_EXPIRED) -> HTTP 422, body error.code == RATE_QUOTE_EXPIRED.
- MockMvc: MethodArgumentNotValidException on field amount -> HTTP 400, body error.details[0].field == amount.
- Fallback Exception -> HTTP 500, code == INTERNAL_ERROR, response body contains no stack trace text.
- error.request_id equals X-Request-ID header value from the request.
- Handler auto-registers in a service with lib-errors dependency without any @ComponentScan change.
**Depends on:** 8.7-T03

### 8.7-T05 — Implement Spring Cloud Gateway WebExceptionHandler for API-05 error envelope  _(45 min)_
**Context:** WBS 8.7: The Spring Cloud Gateway (module services/gateway/) uses reactive WebFlux. When a downstream service is unreachable or returns an unexpected error, the Gateway must wrap it in the API-05 envelope. A per-request ULID is generated as X-Request-ID, injected downstream, and echoed in the error response. Downstream 4xx/5xx responses that already match the envelope are passed through unchanged.
**Steps:** Create GatewayErrorWebExceptionHandler implements WebExceptionHandler in services/gateway/src/main/java/com/gme/pay/gateway/error/.; On ConnectException or TimeoutException return ApiError with GmeErrorCode.SERVICE_UNAVAILABLE (503).; On WebClientResponseException: if body parses as API-05 envelope pass through; otherwise wrap as GmeErrorCode.INTERNAL_ERROR.; Generate ULID request_id per request using java.util.UUID.randomUUID() as interim; propagate as X-Request-ID header to downstream and into error response.; Register as @Bean with Ordered.HIGHEST_PRECEDENCE - 1 to outrank DefaultErrorWebExceptionHandler.; Emit Micrometer counter gateway_errors_total with tags error_code and http_status.
**Deliverable:** services/gateway/src/main/java/com/gme/pay/gateway/error/GatewayErrorWebExceptionHandler.java
**Acceptance / logic checks:**
- WireMock test: downstream returns 500 raw text -> Gateway responds with {error:{code:INTERNAL_ERROR}} HTTP 500.
- WireMock test: downstream returns valid API-05 422 body -> Gateway passes it through unchanged with same HTTP status.
- X-Request-ID is present in both downstream request header and error response body.
- Micrometer counter gateway_errors_total incremented with correct error_code tag.
- Bean ordering verified: GatewayErrorWebExceptionHandler.getOrder() < DefaultErrorWebExceptionHandler.getOrder().
**Depends on:** 8.7-T03

### 8.7-T06 — Flyway migration V901__error_audit_log.sql in db/migrations  _(25 min)_
**Context:** WBS 8.7: PostgreSQL 16 compliance table error_audit_log persists every 4xx/5xx on money-path endpoints. DDL: id BIGSERIAL PRIMARY KEY, request_id VARCHAR(36) NOT NULL, partner_id UUID, error_code VARCHAR(80) NOT NULL, http_status SMALLINT NOT NULL CHECK(http_status BETWEEN 400 AND 599), endpoint VARCHAR(200), retryable BOOLEAN NOT NULL DEFAULT false, created_at TIMESTAMPTZ NOT NULL DEFAULT now(). Index idx_error_audit_partner ON (partner_id, created_at DESC). Migration lives under db/migrations/ and is consumed by lib-persistence's Flyway location list so all money-path services apply it.
**Steps:** Create db/migrations/V901__error_audit_log.sql with CREATE TABLE and index as specified.; Add CHECK constraint http_status BETWEEN 400 AND 599.; Verify idempotency: migration is Flyway-checksum-protected (do not use IF NOT EXISTS; let Flyway manage).; Register db/migrations/ as an additional Flyway location in libs/lib-persistence/src/main/resources/application-persistence.yaml.
**Deliverable:** db/migrations/V901__error_audit_log.sql
**Acceptance / logic checks:**
- Flyway applies migration against Testcontainers Postgres 16 without error.
- INSERT with http_status 200 violates CHECK constraint and is rejected.
- Index idx_error_audit_partner exists after migration (query information_schema.statistics).
- Column error_code has NOT NULL constraint confirmed via information_schema.columns.
- Re-running Flyway against same DB does not re-apply (checksum match).

### 8.7-T07 — Create ErrorAuditLog JPA entity and Spring Data repository in lib-persistence  _(30 min)_
**Context:** WBS 8.7: The error_audit_log table (V901 migration) needs a JPA @Entity and Spring Data JPA repository in libs/lib-persistence/ so money-path services share the ORM without duplication. Fields: id Long, requestId String(36), partnerId UUID, errorCode String(80), httpStatus Short, endpoint String(200), retryable boolean, createdAt Instant. Repository wraps save in REQUIRES_NEW propagation so audit write survives a business transaction rollback.
**Steps:** Create @Entity class ErrorAuditLog with @Table(name=error_audit_log) in libs/lib-persistence/src/main/java/com/gme/pay/persistence/error/.; Map all columns per V901 DDL with @Column constraints (nullable, length).; Create interface ErrorAuditLogRepository extends JpaRepository<ErrorAuditLog,Long>.; Add derived query findByPartnerIdAndCreatedAtBetween(UUID,Instant,Instant).; Add @Service class ErrorAuditService wrapping repository.save() in @Transactional(propagation=REQUIRES_NEW).
**Deliverable:** libs/lib-persistence/src/main/java/com/gme/pay/persistence/error/ErrorAuditLog.java, ErrorAuditLogRepository.java, ErrorAuditService.java
**Acceptance / logic checks:**
- @DataJpaTest with Testcontainers Postgres 16: persist and retrieve ErrorAuditLog row, all fields round-trip correctly.
- findByPartnerIdAndCreatedAtBetween returns only rows within the time window.
- Audit save in REQUIRES_NEW completes even when caller transaction is rolled back (verified via Testcontainers test).
- Entity field count matches V901 column count (8 columns).
**Depends on:** 8.7-T06

### 8.7-T08 — Wire error audit logging into GlobalExceptionHandler  _(35 min)_
**Context:** WBS 8.7: GlobalExceptionHandler (T04) must persist every handled exception to error_audit_log via ErrorAuditService (T07). The audit write must not fail the HTTP response even if the DB write throws. Use @ConditionalOnBean(ErrorAuditService.class) so services without persistence skip the step. Extract partner_id from Spring Security JWT claim partner_id or from request attribute injected by Gateway.
**Steps:** Inject ErrorAuditService (optional: @Autowired(required=false)) into GlobalExceptionHandler.; After building ApiError, invoke auditService.save(requestId, partnerId, code, status, endpoint, retryable) inside try-catch(Exception e){log.warn(...)}.; Extract partner_id from SecurityContextHolder JWT claims; accept null if unauthenticated.; Annotate audit wiring with @ConditionalOnBean(ErrorAuditService.class) so it is a no-op when persistence is absent.; Add libs/lib-errors/build.gradle optional dependency on libs/lib-persistence.
**Deliverable:** Updated libs/lib-errors/src/main/java/com/gme/pay/errors/web/GlobalExceptionHandler.java (audit wiring diff)
**Acceptance / logic checks:**
- Unit test: mock ErrorAuditService throws RuntimeException -> response still returns 422 envelope, no 500 wrapping.
- Integration test with Testcontainers Postgres: GmeException(INSUFFICIENT_PREFUNDING) thrown -> error_audit_log row exists with error_code=INSUFFICIENT_PREFUNDING and retryable=false.
- Services without ErrorAuditService bean start without error.
- partner_id stored from JWT claim; null accepted when claim absent.
**Depends on:** 8.7-T04, 8.7-T07

### 8.7-T09 — Implement ZeroPayErrorMapper: ZP scheme code to GmeErrorCode translation  _(30 min)_
**Context:** WBS 8.7: The Scheme Adapter Layer (services/scheme-adapter-zeropay/) receives raw ZeroPay result codes and must translate them to GmeErrorCode before surfacing to Hub Core. Mapping from spec SCH-06 §10.2: ZP 1001->MERCHANT_NOT_FOUND, 1002->MERCHANT_INACTIVE, 1003->QR_DEACTIVATED, 1004->QR_EXPIRED, 2001->AMOUNT_TOO_LOW, 2002->AMOUNT_TOO_HIGH, 3001->SCHEME_TIMEOUT, 3002->SCHEME_ERROR, 4001->DUPLICATE_TRANSACTION. Unknown codes -> SCHEME_ERROR. Extended error body must include scheme_error_code (raw ZP code) per spec §10.2: the scheme_error_code field in the extended error body contains the raw ZeroPay code for partner diagnostics.
**Steps:** Create ZeroPayErrorMapper in services/scheme-adapter-zeropay/src/main/java/com/gme/pay/adapter/zeropay/error/.; Implement static GmeErrorCode map(String zpCode) using a static final Map<String,GmeErrorCode> (not switch).; Implement GmeException toException(String zpCode) producing GmeException whose message includes the raw zpCode.; Create record ExtendedSchemeError(ApiError error, String schemeErrorCode) with @JsonProperty(scheme_error_code).; Map unknown ZP codes to SCHEME_ERROR as fallback.
**Deliverable:** services/scheme-adapter-zeropay/src/main/java/com/gme/pay/adapter/zeropay/error/ZeroPayErrorMapper.java and ExtendedSchemeError.java
**Acceptance / logic checks:**
- map(1001) == MERCHANT_NOT_FOUND; map(3001) == SCHEME_TIMEOUT; map(9999) == SCHEME_ERROR (unknown fallback).
- toException(3002).getErrorCode() == SCHEME_ERROR and message contains 3002.
- ExtendedSchemeError Jackson serialisation includes scheme_error_code field alongside error object.
- Unit test covers all 9 defined ZP codes plus unknown fallback (10 cases).
**Depends on:** 8.7-T02

### 8.7-T10 — Redis-backed idempotency error replay in lib-errors  _(45 min)_
**Context:** WBS 8.7: API-05 §C-16 and §8.2: idempotent retry with same Idempotency-Key and same request body must return the stored response (including stored error responses). Different body with same key returns IDEMPOTENCY_KEY_REUSE(409). Error responses are also idempotent: a retry of a 422 RATE_QUOTE_EXPIRED must return the same 422 without reprocessing. Store: Redis (Spring Data Redis StringRedisTemplate) with TTL 24 h. Store key = SHA-256(partner_id + Idempotency-Key). Stored value = JSON(ApiError) + HTTP status int.
**Steps:** Create IdempotencyStore in libs/lib-errors/src/main/java/com/gme/pay/errors/idempotency/ using StringRedisTemplate.; On error response, serialise {statusCode, body} JSON and SETEX key ttl=86400.; Create IdempotencyInterceptor implements HandlerInterceptor: preHandle checks Redis; if hit and body SHA-256 matches, write stored response and return false.; If key exists with different body SHA-256, throw GmeException(IDEMPOTENCY_KEY_REUSE).; Register interceptor via Spring auto-configuration (WebMvcConfigurer).
**Deliverable:** libs/lib-errors/src/main/java/com/gme/pay/errors/idempotency/IdempotencyStore.java and IdempotencyInterceptor.java
**Acceptance / logic checks:**
- Testcontainers Redis 7: first POST returns 422 RATE_QUOTE_EXPIRED and stores entry; second POST with same key returns identical 422 without handler invocation (spy count == 1).
- Same key different body returns 409 IDEMPOTENCY_KEY_REUSE.
- Redis TTL for stored key is between 86380 and 86400 s immediately after storage.
- Two partners with same raw Idempotency-Key value produce different Redis keys (no cross-partner collision).
**Depends on:** 8.7-T03

### 8.7-T11 — Gradle task generateErrorCodeYaml to keep openapi/partner-api.yaml enum in sync  _(35 min)_
**Context:** WBS 8.7: The partner-facing OpenAPI spec openapi/partner-api.yaml must enumerate all GmeErrorCode values under components/schemas/ErrorCode/enum. A Gradle task reflects GmeErrorCode.values() post-compile and replaces only that enum block in the YAML. A CI check (./gradlew generateErrorCodeYaml && git diff --exit-code openapi/partner-api.yaml) fails the build if the spec drifts from the enum.
**Steps:** Add task generateErrorCodeYaml to libs/lib-errors/build.gradle depending on compileJava.; Load GmeErrorCode via URLClassLoader on the compiled classes dir; collect names().; Read openapi/partner-api.yaml; replace the enum: [...] list under components/schemas/ErrorCode with the collected names.; Write back only the changed block; validate updated YAML with SnakeYAML parser inside the task.; Document CI usage in task description.
**Deliverable:** Updated libs/lib-errors/build.gradle (generateErrorCodeYaml task) and openapi/partner-api.yaml with generated ErrorCode enum block
**Acceptance / logic checks:**
- Adding a new GmeErrorCode constant and running the task adds it to the YAML enum.
- CI diff check fails if enum is manually edited without running the task (git diff exits non-zero).
- Generated YAML parses without error via SnakeYAML.
- All 36 constants appear in the generated enum block.
**Depends on:** 8.7-T02

### 8.7-T12 — Define OpenAPI error components in openapi/partner-api.yaml  _(40 min)_
**Context:** WBS 8.7: The OpenAPI spec must define reusable components: ErrorBody (code ref ErrorCode, message, request_id, details array), ErrorDetail (field, issue), ErrorEnvelope response wrapping ErrorBody under key error. All 4xx/5xx responses across every endpoint must $ref #/components/responses/ErrorEnvelope rather than use inline schemas. This enables schemathesis to validate all error paths.
**Steps:** Add components/schemas/ErrorBody: {type:object, required:[code,message,request_id], properties:{code:{$ref:ErrorCode},message:{type:string},request_id:{type:string},details:{type:array,items:{$ref:ErrorDetail}}}}.; Add components/schemas/ErrorDetail: {type:object, required:[field,issue], properties:{field:{type:string},issue:{type:string}}}.; Add components/responses/ErrorEnvelope: {description:Error,content:{application/json:{schema:{type:object,required:[error],properties:{error:{$ref:ErrorBody}}}}}}.; Replace all existing inline 4xx/5xx response schemas in every endpoint with $ref:#/components/responses/ErrorEnvelope.
**Deliverable:** openapi/partner-api.yaml (ErrorEnvelope, ErrorBody, ErrorDetail components + all endpoint error $ref updates)
**Acceptance / logic checks:**
- swagger-parser validates the YAML with zero schema errors.
- grep -c inline_error_schema openapi/partner-api.yaml returns 0 (no inline error schemas remain).
- ErrorCode enum block populated by generateErrorCodeYaml task contains all 36 constants.
- schemathesis smoke run against sandbox returns zero schema-mismatch findings on any error path.
**Depends on:** 8.7-T11

### 8.7-T13 — Add Micrometer error-rate metrics to GlobalExceptionHandler  _(30 min)_
**Context:** WBS 8.7: Each error response must emit Micrometer counter api_errors_total with tags: error_code (GmeErrorCode name), http_status (int as string), retryable (true/false), service (spring.application.name). Metric exported via Actuator /actuator/prometheus to Prometheus/Grafana. Auto-configured in lib-errors so all services get it without per-service change.
**Steps:** Inject MeterRegistry @Autowired(required=false) into GlobalExceptionHandler.; After building ApiError call meterRegistry.counter(api_errors_total, tags...).increment() inside null-check.; Tags: error_code=code.name(), http_status=String.valueOf(status), retryable=String.valueOf(retryable), service=env.getProperty(spring.application.name,unknown).; Verify no NPE when MeterRegistry is absent (no Micrometer on classpath).
**Deliverable:** Updated libs/lib-errors/src/main/java/com/gme/pay/errors/web/GlobalExceptionHandler.java (Micrometer wiring)
**Acceptance / logic checks:**
- Unit test with SimpleMeterRegistry: throw GmeException(RATE_LIMITED) -> counter api_errors_total with tag error_code=RATE_LIMITED count == 1.
- Tags retryable=true confirmed for RATE_LIMITED; retryable=false for RATE_QUOTE_EXPIRED.
- Service without MeterRegistry bean starts and handles exceptions without NPE.
- Actuator /actuator/prometheus in @SpringBootTest shows api_errors_total counter line after test error.
**Depends on:** 8.7-T04

### 8.7-T14 — Unit tests: GmeErrorCode invariants and ApiError Jackson serialisation  _(30 min)_
**Context:** WBS 8.7: Explicit unit-test ticket. All 36 GmeErrorCode constants must satisfy spec invariants; ApiError Jackson round-trip must be verified. Use JUnit 5 + Jackson ObjectMapper only (no Spring context). Test class in libs/lib-errors/src/test/.
**Steps:** Write @ParameterizedTest over GmeErrorCode.values() asserting httpStatus is in {400,401,402,403,404,409,422,429,500,502,503,504}, message is non-blank, retryable matches spec table for spot-checked codes.; Write test: ApiError.of(RATE_QUOTE_EXPIRED,req1) -> serialise -> deserialise -> assert code==RATE_QUOTE_EXPIRED and request_id==req1.; Write test: details==empty list omitted from JSON output (NON_EMPTY check).; Write test: GmeException with override message -> resolveMessage returns override.; Write test: GmeException without override -> resolveMessage returns enum defaultMessage.
**Deliverable:** libs/lib-errors/src/test/java/com/gme/pay/errors/GmeErrorCodeTest.java and ApiErrorSerializationTest.java
**Acceptance / logic checks:**
- All 36 constants pass HTTP-status validity assertion.
- PARTNER_B_QUOTE_UNAVAILABLE retryable==true and RATE_QUOTE_EXPIRED retryable==false verified parametrically.
- Round-trip test passes with default ObjectMapper (no custom config).
- Empty details list is absent from serialised JSON string.
- All tests complete in under 5 s (no I/O).
**Depends on:** 8.7-T02, 8.7-T01, 8.7-T03

### 8.7-T15 — Integration tests: GlobalExceptionHandler with @WebMvcTest MockMvc  _(35 min)_
**Context:** WBS 8.7: Spring Boot test-slice tests for GlobalExceptionHandler (T04) using @WebMvcTest. Stub controller triggers each exception category. Verify correct HTTP status, error_code, and request_id echo. No Testcontainers needed.
**Steps:** Create @WebMvcTest(GlobalExceptionHandlerIT.TestController.class) in libs/lib-errors/src/test/.; Add inner @RestController with endpoints: /throw-gme throwing GmeException(INSUFFICIENT_PREFUNDING), /throw-validation throwing MethodArgumentNotValidException, /throw-rate-limited throwing GmeException(RATE_LIMITED), /throw-generic throwing RuntimeException.; Assert HTTP 402 + code INSUFFICIENT_PREFUNDING on /throw-gme.; Assert HTTP 400 + code VALIDATION_ERROR + non-empty details on /throw-validation.; Assert HTTP 429 + code RATE_LIMITED + retryable confirmed in response body.; Assert HTTP 500 + code INTERNAL_ERROR on /throw-generic and confirm body does not contain StackTrace text.; Assert error.request_id equals X-Request-ID header in all cases.
**Deliverable:** libs/lib-errors/src/test/java/com/gme/pay/errors/web/GlobalExceptionHandlerIT.java
**Acceptance / logic checks:**
- HTTP 402 and INSUFFICIENT_PREFUNDING confirmed.
- HTTP 429 and RATE_LIMITED confirmed.
- HTTP 500 body contains no stack trace text.
- error.request_id equals X-Request-ID header.
- MethodArgumentNotValidException produces details array with at least one ErrorDetail.
**Depends on:** 8.7-T04

### 8.7-T16 — Integration tests: ZeroPayErrorMapper unit and ExtendedSchemeError serialisation  _(25 min)_
**Context:** WBS 8.7: Unit tests for ZeroPayErrorMapper (T09) covering all 9 defined ZP code mappings, unknown code fallback, toException message content, and ExtendedSchemeError JSON shape. JUnit 5 + Jackson only; no Spring context.
**Steps:** Write @ParameterizedTest with arguments: 1001->MERCHANT_NOT_FOUND, 1002->MERCHANT_INACTIVE, 1003->QR_DEACTIVATED, 1004->QR_EXPIRED, 2001->AMOUNT_TOO_LOW, 2002->AMOUNT_TOO_HIGH, 3001->SCHEME_TIMEOUT, 3002->SCHEME_ERROR, 4001->DUPLICATE_TRANSACTION.; Write test: map(9999) -> SCHEME_ERROR (unknown fallback).; Write test: toException(3001).getErrorCode() == SCHEME_TIMEOUT and message contains string 3001.; Write test: ExtendedSchemeError serialised JSON contains scheme_error_code field with value equal to raw ZP string.
**Deliverable:** services/scheme-adapter-zeropay/src/test/java/com/gme/pay/adapter/zeropay/error/ZeroPayErrorMapperTest.java
**Acceptance / logic checks:**
- All 9 defined ZP codes assert correct GmeErrorCode.
- map(9999) returns SCHEME_ERROR.
- toException message includes raw ZP code string.
- JSON path $.scheme_error_code present and equals raw ZP code.
- Tests complete in under 3 s.
**Depends on:** 8.7-T09

### 8.7-T17 — Integration tests: idempotency error replay with Testcontainers Redis 7  _(45 min)_
**Context:** WBS 8.7: Integration tests for IdempotencyStore and IdempotencyInterceptor (T10). Verify: (1) same key + same body returns stored 422 without handler re-invocation; (2) same key + different body returns 409 IDEMPOTENCY_KEY_REUSE; (3) Redis TTL is within 86380-86400 s; (4) partner isolation (two partners with same Idempotency-Key value use different Redis keys).
**Steps:** Create @SpringBootTest IdempotencyInterceptorIT using @Testcontainers with GenericContainer redis:7-alpine.; Configure StringRedisTemplate with Testcontainers port.; Test 1: POST with key K1 body B1 -> 422; POST again K1+B1 -> 422; assert handler spy invoked exactly once.; Test 2: POST K2+B1 then POST K2+B2 -> 409 IDEMPOTENCY_KEY_REUSE.; Test 3: after first POST assert Redis TTL for key is between 86380 and 86400.; Test 4: POST with partner1+K3 and partner2+K3 -> two distinct Redis keys (verify via RedisTemplate.hasKey).
**Deliverable:** libs/lib-errors/src/test/java/com/gme/pay/errors/idempotency/IdempotencyInterceptorIT.java
**Acceptance / logic checks:**
- Handler spy count == 1 after two identical requests.
- HTTP 409 IDEMPOTENCY_KEY_REUSE on body mismatch.
- Redis TTL within 86380-86400 s range.
- Two partners with same Idempotency-Key have distinct Redis keys (no collision).
**Depends on:** 8.7-T10

### 8.7-T18 — Wire lib-errors as dependency in all money-path services and remove ad-hoc error handlers  _(40 min)_
**Context:** WBS 8.7: lib-errors must be the sole source of error envelope logic across: services/rate-fx, services/payment-executor, services/prefunding-balance, services/transaction-mgmt, services/auth-identity, services/gateway. Any existing ad-hoc @ControllerAdvice, ErrorResponse POJO, or ApiError class in those services must be removed and replaced with GmeException throws.
**Steps:** Add implementation(project(:libs:lib-errors)) to build.gradle of each of the 6 listed services.; Search each service src tree for @ControllerAdvice, @ExceptionHandler, ErrorResponse, ApiError classes; delete them.; Replace any direct JSON error construction in service code with throw new GmeException(appropriate_code).; Run ./gradlew :services:rate-fx:test :services:payment-executor:test :services:prefunding-balance:test to confirm green.; Add @SpringBootTest smoke test in each service confirming exactly one ExceptionHandler bean exists (the lib-errors one).
**Deliverable:** Updated build.gradle files for 6 services; deleted ad-hoc error-handler classes; updated service code to throw GmeException
**Acceptance / logic checks:**
- ./gradlew build passes with no compilation errors across all 6 services.
- grep -r @ControllerAdvice services/ returns only lib-errors GlobalExceptionHandler entry.
- Each service smoke test ApplicationContext contains exactly one ExceptionHandler bean.
- MockMvc slice test in services/rate-fx confirms a thrown GmeException(RATE_QUOTE_EXPIRED) returns the canonical envelope with HTTP 422.
**Depends on:** 8.7-T04, 8.7-T08

<!-- wbs-v3-gap-closure -->

---

## WBS v3 gap-closure tickets (re-baseline, 2026-06-10)

These tickets convert this service's PARTIAL audit findings into DONE and add work discovered during the build. Statuses live on the `Backlog` sheet of `GMEPay+_Task_Backlog.xlsx`; phase sequencing on the `Completion Plan v3` sheet of `GMEPay+_WBS.xlsx`.

### 17.4-G01 — KafkaEventPublisher implementing lib-events
*Completion phase:* **R1** · *Est:* 140 min · *Role:* Backend · *Deps:* 17.1-G03

**Context.** lib-events has EventPublisher + LogEventPublisher only. Implement Kafka producer with JSON serialization of DomainEvent (BigDecimal as string per MONEY_CONVENTION).

**Steps.**
- spring-kafka producer, acks=all, idempotent
- Topic naming gmepay.<aggregate>.<event>
- @Primary when spring.kafka.bootstrap-servers set

**Deliverable.** Kafka producer behind EventPublisher

**Acceptance.**
- Event visible via kafka-console-consumer in compose stack
- Fallback to LogEventPublisher when no broker configured

