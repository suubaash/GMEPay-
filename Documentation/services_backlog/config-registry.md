# config-registry  (backend)

**Scope:** Scheme/Partner/Rule/Treasury registries; config-driven onboarding

**Owned WBS work-packages:** 2.5, 3.2, 3.4  ·  **Tickets:** 96  ·  **Est:** 52.4h

## Service contract (MSA: own DB, API-only communication)

- **Datastore (owned by this service):** PostgreSQL `config` (schemes, partners, rules, treasury rates)
- **APIs / events I EXPOSE:** /v1/schemes, /v1/partners, /v1/rules, /v1/treasury-rates
- **APIs / events I CONSUME:** — (source of truth)
- **Integration rule:** never read another service's database or import its private entities — call its API or consume its event; stub consumed services with WireMock in tests.

> Self-contained backlog for this service. Build it as its own repo/module with its own DB + Flyway migrations, against the `shared-libs` contracts (lib-money / lib-errors / lib-events / lib-api-contracts only). Each ticket has a deliverable + acceptance checks.


## WBS 2.5 — Configuration framework (config-not-code)
### 2.5-T01 — Create Flyway migration V201: qr_scheme and scheme_country tables  _(30 min)_
**Context:** Config-not-code constraint (SAD-02 AD-04, BRD §10.3): adding a QR scheme must be a DB config insert with no code change. qr_scheme holds: id BIGINT PK, scheme_code VARCHAR(30) UNIQUE (e.g. ZEROPAY), name, payout_ccy CHAR(3), supported_modes VARCHAR(20) CHECK IN (MPM,CPM,BOTH), settlement_counterparty, merchant_fee_domestic_min/max DECIMAL(6,4), merchant_fee_crossborder_min/max, van_fee_domestic_min/max, van_fee_crossborder_min/max, gme_fee_share_pct DECIMAL(6,4), partner_b_quote_enabled BOOLEAN, partner_b_quote_deviation_pct DECIMAL(6,4) default 0.0100, sftp_host/sftp_path_outbound/sftp_path_inbound VARCHAR, api_endpoint VARCHAR(512), api_credential_ref VARCHAR(120), status VARCHAR(20) CHECK IN (TESTING,ACTIVE,INACTIVE), is_active BOOLEAN, created_at/updated_at TIMESTAMPTZ, created_by/updated_by VARCHAR. scheme_country: id, scheme_id FK, country_code CHAR(2), is_active BOOLEAN, standard audit; UNIQUE(scheme_id, country_code). Module: services/config-registry. Migrations live in services/config-registry/src/main/resources/db/migration/.
**Steps:** Create services/config-registry/src/main/resources/db/migration/V201__qr_scheme.sql; Define qr_scheme table with all columns, CHECK IN (MPM,CPM,BOTH) on supported_modes, CHECK IN (TESTING,ACTIVE,INACTIVE) on status; Define scheme_country table with FK to qr_scheme and UNIQUE(scheme_id, country_code); Add index on qr_scheme(scheme_code) and scheme_country(scheme_id, is_active); All monetary columns use NUMERIC not FLOAT
**Deliverable:** services/config-registry/src/main/resources/db/migration/V201__qr_scheme.sql
**Acceptance / logic checks:**
- Flyway applies V201 cleanly on a fresh PostgreSQL 16 Testcontainers instance
- scheme_code UNIQUE constraint: inserting a duplicate ZEROPAY row raises 23505 violation
- status CHECK constraint: inserting status=UNKNOWN raises 23514 violation
- scheme_country UNIQUE(scheme_id, country_code) prevents duplicate KR mapping for same scheme
- All DECIMAL columns declared as NUMERIC(6,4) or wider, not FLOAT8

### 2.5-T02 — Create Flyway migration V202: partner, partner_credential, partner_webhook tables  _(30 min)_
**Context:** partner: id BIGINT PK, partner_code VARCHAR(30) UNIQUE (e.g. GME_REMIT, SENDMN), name, partner_type VARCHAR(10) CHECK IN (LOCAL,OVERSEAS), collection_ccy CHAR(3), settle_a_ccy CHAR(3), webhook_url VARCHAR(512), rate_quote_ttl_seconds INT DEFAULT 300 CHECK BETWEEN 60 AND 1800, low_balance_threshold_usd DECIMAL(20,4) nullable, low_balance_alert_email VARCHAR(255), status VARCHAR(20) CHECK IN (ONBOARDING,ACTIVE,SUSPENDED,INACTIVE), is_active BOOLEAN, standard audit. partner_credential: id, partner_id FK, api_key VARCHAR(64) UNIQUE, api_secret_hash VARCHAR(128) (bcrypt), is_active BOOLEAN, expires_at TIMESTAMPTZ nullable, standard audit. partner_webhook: id, partner_id FK, webhook_url, event_types TEXT, signing_secret_hash VARCHAR(128), is_active BOOLEAN, standard audit. Module: services/config-registry.
**Steps:** Create V202__partner.sql in services/config-registry/src/main/resources/db/migration/; Define partner table with CHECK constraints on partner_type, status, and rate_quote_ttl_seconds range (60-1800); Define partner_credential table; UNIQUE on api_key; Define partner_webhook table; index on partner_id; Add comment on low_balance_threshold_usd: required only when partner_type=OVERSEAS (enforced at app layer)
**Deliverable:** services/config-registry/src/main/resources/db/migration/V202__partner.sql
**Acceptance / logic checks:**
- partner_type CHECK rejects values other than LOCAL or OVERSEAS
- rate_quote_ttl_seconds=59 raises CHECK violation; 60 and 1800 are both accepted
- api_key UNIQUE index prevents second credential with same key
- Flyway applies V201 then V202 in order without error on Testcontainers PostgreSQL 16
- partner_code UNIQUE prevents duplicate GME_REMIT insert
**Depends on:** 2.5-T01

### 2.5-T03 — Create Flyway migration V203: rule and service_charge_tier tables  _(35 min)_
**Context:** rule is the central config entity (partner x scheme x direction). Columns: id, partner_id FK, scheme_id FK, direction VARCHAR(10) CHECK IN (INBOUND,OUTBOUND,DOMESTIC,HUB), collection_ccy CHAR(3), settle_a_ccy CHAR(3), settle_b_ccy CHAR(3), payout_ccy CHAR(3), rate_coll_source VARCHAR(10) CHECK IN (IDENTITY,LIVE,MANUAL,PARTNER), rate_pay_source same enum, manual_rate_coll DECIMAL(20,8) nullable, manual_rate_pay DECIMAL(20,8) nullable, m_a DECIMAL(8,6), m_b DECIMAL(8,6), service_charge_amount DECIMAL(20,4), service_charge_ccy CHAR(3), is_same_ccy_shortcircuit BOOLEAN, status VARCHAR(20) CHECK IN (DRAFT,ACTIVE,SUSPENDED), effective_from TIMESTAMPTZ, standard audit. UNIQUE(partner_id, scheme_id, direction). service_charge_tier: id, rule_id FK, min_collection_usd DECIMAL(20,4), max_collection_usd DECIMAL(20,4) nullable (no cap), charge_amount DECIMAL(20,4), standard audit. Module: services/config-registry.
**Steps:** Create V203__rule.sql in services/config-registry/src/main/resources/db/migration/; Define rule table with FK constraints on partner_id and scheme_id; CHECK on direction, rate sources, status; Add UNIQUE(partner_id, scheme_id, direction); Define service_charge_tier with FK rule_id; Add index on rule(partner_id, scheme_id, direction, status) for runtime resolution
**Deliverable:** services/config-registry/src/main/resources/db/migration/V203__rule.sql
**Acceptance / logic checks:**
- direction=UNKNOWN raises CHECK violation
- UNIQUE(partner_id, scheme_id, direction) prevents duplicate rule for same combo
- m_a and m_b are NUMERIC(8,6); value 0.010000 stores and retrieves exactly
- service_charge_tier FK to non-existent rule_id raises 23503 violation
- Flyway applies V201-V203 in sequence cleanly on Testcontainers
**Depends on:** 2.5-T02

### 2.5-T04 — Create Flyway migration V204: treasury_rate table  _(25 min)_
**Context:** treasury_rate stores FX rates in canonical form: ccy_pair = usd_{ccy} (units of target currency per 1 USD). E.g. usd_krw = 1380.0 means 1 USD = 1380 KRW. Columns: id BIGINT PK, ccy_pair VARCHAR(10) e.g. usd_krw, rate DECIMAL(20,8), source VARCHAR(10) CHECK IN (LIVE,MANUAL), effective_at TIMESTAMPTZ, created_at TIMESTAMPTZ, created_by VARCHAR. Latest rate query: WHERE ccy_pair=? ORDER BY effective_at DESC LIMIT 1. Historical rows retained forever (BOK reconciliation). Phase 1 source=MANUAL only; Phase 2 adds LIVE (xe.com feed) with no schema change. Index on (ccy_pair, effective_at DESC) is critical for query performance. Module: services/config-registry.
**Steps:** Create V204__treasury_rate.sql; Define treasury_rate table with NUMERIC(20,8) rate, TIMESTAMPTZ effective_at, CHECK source IN (LIVE,MANUAL); Add index on (ccy_pair, effective_at DESC) named idx_treasury_rate_lookup; Add comment: rows are never deleted or updated after insert; append-only enforced at app layer; No FK from treasury_rate to other tables (rates are globally shared)
**Deliverable:** services/config-registry/src/main/resources/db/migration/V204__treasury_rate.sql
**Acceptance / logic checks:**
- Index idx_treasury_rate_lookup exists in information_schema after migration
- source=SPOT raises CHECK violation; MANUAL and LIVE accepted
- rate column is NUMERIC not FLOAT8
- Two rows for same ccy_pair with different effective_at both insert without conflict
- Flyway V204 applies cleanly after V203
**Depends on:** 2.5-T03

### 2.5-T05 — Create Flyway migration V205: audit_log table with immutability enforcement  _(30 min)_
**Context:** audit_log is the immutable append-only record for all config and payment state changes (SAD-02 §11.3, DAT-03 §9.1). Columns: id BIGINT PK, actor_id VARCHAR(120), actor_type VARCHAR(20) CHECK IN (OPERATOR,SYSTEM,PARTNER), action VARCHAR(20) CHECK IN (CREATE,UPDATE,DELETE,ACTIVATE,DEACTIVATE), entity_type VARCHAR(50) (table name), entity_id BIGINT, before_value JSONB nullable, after_value JSONB nullable, occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), ip_address INET, request_id VARCHAR(64). Retained >= 7 years (BOK/KYC). Immutability via PostgreSQL rule: CREATE RULE no_update_audit AS ON UPDATE TO audit_log DO INSTEAD NOTHING (or raise exception). Module: services/config-registry.
**Steps:** Create V205__audit_log.sql; Define audit_log table with all columns and CHECK constraints on actor_type and action; Add PostgreSQL rules: CREATE RULE prevent_update_audit AS ON UPDATE TO audit_log DO INSTEAD RAISE EXCEPTION; Add same rule for DELETE: prevent_delete_audit; Add indexes on (entity_type, entity_id), (occurred_at), (actor_id)
**Deliverable:** services/config-registry/src/main/resources/db/migration/V205__audit_log.sql
**Acceptance / logic checks:**
- INSERT with actor_type=OPERATOR and action=CREATE succeeds
- UPDATE on any row raises exception from DB rule
- DELETE on any row raises exception from DB rule
- actor_type=EXTERNAL raises CHECK violation
- Indexes on (entity_type, entity_id) and (occurred_at) visible in pg_indexes
**Depends on:** 2.5-T04

### 2.5-T06 — Create Flyway seed migration V206: ZeroPay scheme, bootstrap partners, initial treasury rate  _(25 min)_
**Context:** Config-not-code: ZeroPay is a config row, not code. V206 seeds: 1) qr_scheme: scheme_code=ZEROPAY, payout_ccy=KRW, supported_modes=BOTH, gme_fee_share_pct=0.70, partner_b_quote_deviation_pct=0.0100, status=TESTING, is_active=false, api_credential_ref=gmepay/dev/zeropay/api-key. 2) scheme_country: scheme_id from above, country_code=KR, is_active=true. 3) partner GME_REMIT: partner_type=LOCAL, collection_ccy=KRW, settle_a_ccy=KRW, rate_quote_ttl_seconds=300, status=ONBOARDING. 4) partner SENDMN: partner_type=OVERSEAS, collection_ccy=USD, settle_a_ccy=USD, rate_quote_ttl_seconds=60, status=ONBOARDING. 5) treasury_rate: ccy_pair=usd_krw, rate=1380.00000000, source=MANUAL. Use INSERT ... ON CONFLICT DO NOTHING for idempotency. Module: services/config-registry.
**Steps:** Create V206__seed_zeropay.sql with DO $$ BEGIN ... END $$ block for CTE-based inserts; INSERT qr_scheme ZEROPAY with all required columns; capture id into variable; INSERT scheme_country (scheme_id, country_code=KR, is_active=true); INSERT partners GME_REMIT and SENDMN with created_by=SYSTEM; INSERT treasury_rate usd_krw=1380.00000000 MANUAL
**Deliverable:** services/config-registry/src/main/resources/db/migration/V206__seed_zeropay.sql
**Acceptance / logic checks:**
- V206 applied twice is idempotent (ON CONFLICT DO NOTHING means second run inserts 0 rows)
- SELECT count(*) FROM qr_scheme returns 1 after fresh migration
- SELECT payout_ccy FROM qr_scheme WHERE scheme_code=ZEROPAY returns KRW
- partner GME_REMIT has partner_type=LOCAL and rate_quote_ttl_seconds=300
- treasury_rate row exists with rate=1380.00000000 for ccy_pair=usd_krw
**Depends on:** 2.5-T05

### 2.5-T07 — Define SchemeRegistryPort, PartnerRegistryPort, RuleRegistryPort interfaces in lib-api-contracts  _(35 min)_
**Context:** Consuming services (Rate Engine, Smart Router, Orchestrator) resolve config at runtime via these Java port interfaces. No if-partner or if-scheme conditionals in consuming code (AD-04). Interfaces live in lib-api-contracts/src/main/java/com/gme/pay/contracts/registry/. SchemeRegistryPort: findByCode(String schemeCode): Optional<SchemeConfig>; findActiveByCountry(String countryCode): List<SchemeConfig>. PartnerRegistryPort: findByCode(String partnerCode): Optional<PartnerConfig>; findById(Long id): Optional<PartnerConfig>. RuleRegistryPort: findActiveRule(Long partnerId, Long schemeId, String direction): Optional<RuleConfig>. DTOs are immutable Java records. SchemeConfig fields: schemeCode, payoutCcy, supportedModes, gme FeeSharePct BigDecimal, partnerBQuoteEnabled, partnerBQuoteDeviationPct BigDecimal. RuleConfig: partnerId, schemeId, direction, mA BigDecimal, mB BigDecimal, serviceChargeAmount BigDecimal, serviceChargeCcy, rateColl Source, ratePaySource, manualRateColl BigDecimal, manualRatePay BigDecimal, isSameCcyShortcircuit. Module: lib-api-contracts.
**Steps:** Create lib-api-contracts/src/main/java/com/gme/pay/contracts/registry/SchemeRegistryPort.java interface; Create PartnerRegistryPort.java interface; Create RuleRegistryPort.java interface; Create SchemeConfig.java, PartnerConfig.java, RuleConfig.java as Java records; Add Javadoc on each interface: implementations must serve cached values and evict on write
**Deliverable:** lib-api-contracts/src/main/java/com/gme/pay/contracts/registry/SchemeRegistryPort.java, PartnerRegistryPort.java, RuleRegistryPort.java plus SchemeConfig.java, PartnerConfig.java, RuleConfig.java
**Acceptance / logic checks:**
- All interfaces compile with Java 21 and no Spring dependencies on the classpath
- SchemeConfig is a Java record (no setters, final fields)
- RuleConfig has BigDecimal mA, mB, serviceChargeAmount; no double or float fields
- findActiveRule returns Optional.empty() not null for unknown combo (documented in Javadoc)
- RuleConfig.isSameCcyShortcircuit is a boolean field (not Boolean)
**Depends on:** 2.5-T03

### 2.5-T08 — Define TreasuryRatePort interface and TreasuryRateSnapshot record in lib-api-contracts  _(25 min)_
**Context:** The Rate Engine calls TreasuryRatePort to get cost_rate_coll and cost_rate_pay at quote time. TreasuryRatePort in lib-api-contracts/src/main/java/com/gme/pay/contracts/treasury/. Methods: getLatestRate(String ccyPair): TreasuryRateSnapshot throws RateNotFoundException; getLatestRateOrIdentity(String ccyPair): TreasuryRateSnapshot (returns rate=1.0 for identity legs where settle ccy = USD). Convention: ccyPair = usd_{ccy} lowercase, e.g. usd_krw. Identity leg: if settle_a_ccy=USD then cost_rate_coll=1.0 (no treasury lookup needed). TreasuryRateSnapshot record: ccyPair String, rate BigDecimal, source String, effectiveAt Instant, rateId Long (FK for commit-time lock). RateNotFoundException: unchecked, in lib-errors. Module: lib-api-contracts.
**Steps:** Create TreasuryRatePort.java interface in lib-api-contracts/src/main/java/com/gme/pay/contracts/treasury/; Create TreasuryRateSnapshot.java Java record with fields: ccyPair String, rate BigDecimal, source String, effectiveAt Instant, rateId Long; Create RateNotFoundException.java in lib-errors/src/main/java/com/gme/pay/errors/ extending RuntimeException; Add Javadoc on getLatestRateOrIdentity: returns TreasuryRateSnapshot(ccyPair, BigDecimal.ONE, IDENTITY, now, null) when settle ccy = USD
**Deliverable:** lib-api-contracts/src/main/java/com/gme/pay/contracts/treasury/TreasuryRatePort.java, TreasuryRateSnapshot.java; lib-errors/.../RateNotFoundException.java
**Acceptance / logic checks:**
- TreasuryRateSnapshot is a Java record with BigDecimal rate (not double)
- RateNotFoundException extends RuntimeException (unchecked)
- Interface compiles without Spring annotations or Spring dependencies
- getLatestRate method signature documented to throw RateNotFoundException
- ccyPair naming convention usd_{ccy} documented in Javadoc
**Depends on:** 2.5-T07

### 2.5-T09 — Scaffold services/config-registry Spring Boot module in Gradle multi-module build  _(35 min)_
**Context:** config-registry is a dedicated Spring Boot 3.x microservice (STACK.md service list). It owns Flyway migrations V201-V207, serves registry port implementations, and exposes admin REST endpoints. Gradle module path: services/config-registry. Required Gradle dependencies: spring-boot-starter-data-jpa, spring-boot-starter-web, spring-boot-starter-data-redis, spring-boot-starter-security, spring-boot-starter-oauth2-resource-server, flyway-core, postgresql driver, lib-api-contracts, lib-errors, lib-money. Main class: ConfigRegistryApplication under com.gme.pay.config. application.yml: spring.datasource.url=jdbc:postgresql://localhost:5433/gmepay, spring.flyway.enabled=true, spring.flyway.locations=classpath:db/migration, spring.data.redis.host=localhost.
**Steps:** Add services/config-registry to root settings.gradle includes; Create services/config-registry/build.gradle with all required dependencies including lib-api-contracts and lib-errors; Create ConfigRegistryApplication.java with @SpringBootApplication; Create src/main/resources/application.yml with datasource, flyway, and redis stubs; Run ./gradlew :services:config-registry:compileJava and confirm it passes
**Deliverable:** services/config-registry/build.gradle, services/config-registry/src/main/java/com/gme/pay/config/ConfigRegistryApplication.java, src/main/resources/application.yml
**Acceptance / logic checks:**
- ./gradlew :services:config-registry:compileJava completes without error
- Module appears in ./gradlew projects output
- application.yml has spring.flyway.locations=classpath:db/migration
- Flyway migrations V201-V206 are on the classpath at runtime
- ConfigRegistryApplication has @SpringBootApplication and correct base package
**Depends on:** 2.5-T06, 2.5-T08

### 2.5-T10 — Implement JPA entities and repositories: qr_scheme, scheme_country, partner  _(40 min)_
**Context:** JPA entities in services/config-registry/src/main/java/com/gme/pay/config/domain/. Map to PostgreSQL tables V201-V202. Use @Entity, @Table(name=...), @Column(nullable=false). BigDecimal for all DECIMAL columns. Status and type fields use @Enumerated(EnumType.STRING) never ORDINAL. Audit fields: @CreationTimestamp for created_at, @UpdateTimestamp for updated_at. Repositories: QrSchemeRepository.findBySchemeCode(String): Optional<QrScheme>; findByIsActiveTrue(): List. SchemeCountryRepository.findBySchemeIdAndIsActiveTrue(Long): List. PartnerRepository.findByPartnerCode(String): Optional<Partner>. Module: services/config-registry.
**Steps:** Create QrScheme.java @Entity in services/config-registry/src/main/java/com/gme/pay/config/domain/; Create SchemeCountry.java @Entity with @ManyToOne(fetch=LAZY) to QrScheme; Create Partner.java @Entity with PartnerType and PartnerStatus enums; Create QrSchemeRepository, SchemeCountryRepository, PartnerRepository as Spring Data JPA interfaces; Add @Column(updatable=false) on created_at; verify all DECIMAL fields annotated with correct precision/scale
**Deliverable:** services/config-registry/src/main/java/com/gme/pay/config/domain/QrScheme.java, SchemeCountry.java, Partner.java and three repository interfaces
**Acceptance / logic checks:**
- QrScheme.gmeFeeSha rePct field type is BigDecimal with @Column(precision=6,scale=4)
- @Enumerated(EnumType.STRING) on partner_type and status fields
- QrSchemeRepository.findBySchemeCode returns Optional<QrScheme>
- Spring context loads with Testcontainers PostgreSQL 16 and Flyway seeds V201-V206
- No field uses primitive double or float
**Depends on:** 2.5-T09

### 2.5-T11 — Implement JPA entities and repositories: rule, service_charge_tier, treasury_rate  _(40 min)_
**Context:** rule entity maps to V203 DDL. Fields m_a and m_b are BigDecimal. isSameCcyShortcircuit is a computed boolean stored in DB. treasury_rate maps to V204: ccyPair, rate BigDecimal, source (enum LIVE/MANUAL), effectiveAt Instant. RuleRepository: findByPartnerIdAndSchemeIdAndDirectionAndStatus(Long, Long, String, String): Optional<Rule>. TreasuryRateRepository: findFirstByCcyPairOrderByEffectiveAtDesc(String): Optional<TreasuryRate> (JPA derived query). ServiceChargeTierRepository: findByRuleIdOrderByMinCollectionUsdAsc(Long). Module: services/config-registry.
**Steps:** Create Rule.java @Entity with @UniqueConstraint(columnNames={partner_id,scheme_id,direction}) on @Table; Create ServiceChargeTier.java @Entity with @ManyToOne(fetch=LAZY) to Rule; Create TreasuryRate.java @Entity; Create RuleRepository with derived query findByPartnerIdAndSchemeIdAndDirectionAndStatus; Create TreasuryRateRepository with findFirstByCcyPairOrderByEffectiveAtDesc
**Deliverable:** services/config-registry/src/main/java/com/gme/pay/config/domain/Rule.java, ServiceChargeTier.java, TreasuryRate.java and three repository interfaces
**Acceptance / logic checks:**
- Rule.mA type is BigDecimal; no double
- TreasuryRateRepository.findFirstByCcyPairOrderByEffectiveAtDesc(usd_krw) returns row from V206 seed
- @UniqueConstraint on Rule enforced: second rule for same partner+scheme+direction raises DataIntegrityViolationException
- findByPartnerIdAndSchemeIdAndDirectionAndStatus with DRAFT status returns empty Optional for missing rule
- ServiceChargeTier FK to Rule cascades on delete
**Depends on:** 2.5-T10

### 2.5-T12 — Implement RedisConfigCache: cache-aside for scheme, partner, rule with TTL and eviction  _(45 min)_
**Context:** Config/Registry maintains a Redis hot cache (SAD-02 §11.3). Key pattern: config:scheme:{schemeCode}, config:partner:{partnerCode}, config:rule:{partnerId}:{schemeId}:{direction}. Cache-aside: on miss query PostgreSQL, serialize to JSON via Jackson, store in Redis with TTL=3600s. On any config write (create/update/activate/deactivate) evict the affected key. Use Spring Data Redis RedisTemplate<String,String> with Jackson ObjectMapper. Also rate cache: config:rate:{ccyPair} with TTL=300s. RedisConfigCache is a @Component in services/config-registry. Not a Spring @Cache proxy; use explicit get/set/evict methods for full observability.
**Steps:** Create RedisConfigCache.java @Component in services/config-registry/src/main/java/com/gme/pay/config/cache/; Inject RedisTemplate<String,String> and ObjectMapper; Implement getScheme(String schemeCode): Optional<SchemeConfig> with cache-aside (miss -> DB -> serialize -> SET EX 3600); Implement getPartner, getRule, getRate analogously with TTL 3600/3600/300 respectively; Implement evictScheme, evictPartner, evictRule, evictRate methods that call RedisTemplate.delete(key)
**Deliverable:** services/config-registry/src/main/java/com/gme/pay/config/cache/RedisConfigCache.java
**Acceptance / logic checks:**
- Second getScheme call for same code does not invoke QrSchemeRepository (verify via Mockito spy)
- evictScheme removes the key; TTL on evicted key returns -2 (key does not exist)
- JSON deserialization produces SchemeConfig with BigDecimal gmeFeeSha rePct intact (not rounded)
- Cache miss on unknown schemeCode returns Optional.empty() without NullPointerException
- Rate cache TTL <= 300 seconds confirmed via RedisTemplate.getExpire
**Depends on:** 2.5-T11

### 2.5-T13 — Implement SchemeRegistryService and PartnerRegistryService implementing port interfaces  _(40 min)_
**Context:** These @Service classes implement SchemeRegistryPort and PartnerRegistryPort from lib-api-contracts. SchemeRegistryService.findByCode: RedisConfigCache.getScheme hit; on miss query QrSchemeRepository, map QrScheme entity to SchemeConfig record, store in cache. findActiveByCountry(KR): query SchemeCountryRepository for is_active=true rows matching country_code, load QrScheme for each, filter qr_scheme.is_active=true. PartnerRegistryService.findByCode: same cache-aside pattern. Mapping must copy all BigDecimal fields exactly. Module: services/config-registry. These service beans are the only implementations of the port interfaces; consuming services inject them via interface type.
**Steps:** Create SchemeRegistryService.java @Service implements SchemeRegistryPort; Inject RedisConfigCache, QrSchemeRepository, SchemeCountryRepository; Implement findByCode: cache hit then DB fallback; map QrScheme -> SchemeConfig record; Implement findActiveByCountry: DB-only query joining scheme_country and qr_scheme on is_active; Create PartnerRegistryService.java @Service implements PartnerRegistryPort; Add private mapping methods qrSchemeToConfig and partnerToConfig
**Deliverable:** services/config-registry/src/main/java/com/gme/pay/config/service/SchemeRegistryService.java, PartnerRegistryService.java
**Acceptance / logic checks:**
- findByCode(ZEROPAY) returns non-empty Optional<SchemeConfig> after V206 seed
- findActiveByCountry(KR) returns list containing entry with schemeCode=ZEROPAY
- findByCode(UNKNOWN) returns Optional.empty()
- SchemeConfig.partnerBQuoteDeviationPct equals 0.0100 (BigDecimal compareTo 0)
- PartnerRegistryService.findByCode(GME_REMIT) returns PartnerConfig with partnerType=LOCAL
**Depends on:** 2.5-T12

### 2.5-T14 — Implement RuleRegistryService implementing RuleRegistryPort interface  _(35 min)_
**Context:** RuleRegistryService implements RuleRegistryPort.findActiveRule(partnerId, schemeId, direction). Steps: 1) check RedisConfigCache key config:rule:{partnerId}:{schemeId}:{direction}; 2) on miss query RuleRepository.findByPartnerIdAndSchemeIdAndDirectionAndStatus with ACTIVE; 3) map Rule JPA entity to RuleConfig record preserving all BigDecimal fields; 4) cache. Also findAllActiveRules(): RuleRepository.findByStatus(ACTIVE) for admin listing. Important: only ACTIVE rules are served; DRAFT and SUSPENDED return Optional.empty(). Config changes apply to new transactions only (SAD-02 §11.3). Module: services/config-registry.
**Steps:** Create RuleRegistryService.java @Service implements RuleRegistryPort; Implement findActiveRule: RedisConfigCache.getRule first; DB fallback for status=ACTIVE only; Map Rule entity to RuleConfig; ensure mA, mB, serviceChargeAmount are BigDecimal; Implement findAllActiveRules() returning List<RuleConfig>; Javadoc: rule changes apply to NEW transactions only; in-flight transactions use config at CommitTransaction
**Deliverable:** services/config-registry/src/main/java/com/gme/pay/config/service/RuleRegistryService.java
**Acceptance / logic checks:**
- findActiveRule for a DRAFT rule returns Optional.empty()
- findActiveRule for a SUSPENDED rule returns Optional.empty()
- RuleConfig.mA is BigDecimal; value 0.010000 preserved (scale 6)
- findAllActiveRules returns empty list when no ACTIVE rules exist without throwing
- After rule margin update and cache eviction, next findActiveRule returns updated mA value
**Depends on:** 2.5-T13

### 2.5-T15 — Implement TreasuryRateService implementing TreasuryRatePort interface  _(35 min)_
**Context:** TreasuryRateService implements TreasuryRatePort. getLatestRate(ccyPair): check Redis key config:rate:{ccyPair}; on miss query TreasuryRateRepository.findFirstByCcyPairOrderByEffectiveAtDesc; throw RateNotFoundException if no row; cache with TTL=300s; return TreasuryRateSnapshot including rateId for audit FK. getLatestRateOrIdentity(ccyPair): if ccyPair equals usd_usd (identity leg where settle_ccy=USD) return TreasuryRateSnapshot(usd_usd, BigDecimal.ONE, IDENTITY, Instant.now(), null) without DB call. Treasury rate updates take effect immediately for new quotes (rate cache TTL=300s max staleness). Module: services/config-registry.
**Steps:** Create TreasuryRateService.java @Service implements TreasuryRatePort; Implement getLatestRate: Redis cache lookup then TreasuryRateRepository fallback; throw RateNotFoundException if absent; Implement getLatestRateOrIdentity: check if ccyPair matches usd_usd pattern; return BigDecimal.ONE snapshot; Evict Redis rate cache key in same @Transactional as TreasuryRateAdminService.createRate (see 2.5-T20); Return TreasuryRateSnapshot with rateId = treasury_rate.id for commit-time lock reference
**Deliverable:** services/config-registry/src/main/java/com/gme/pay/config/service/TreasuryRateService.java
**Acceptance / logic checks:**
- getLatestRate(usd_krw) returns snapshot with rate.compareTo(new BigDecimal(1380)) == 0 after V206 seed
- getLatestRate(usd_xyz) throws RateNotFoundException
- getLatestRateOrIdentity(usd_usd) returns BigDecimal.ONE without any DB call (verify via Mockito verify no interactions)
- TreasuryRateSnapshot.rateId matches the PK of the treasury_rate row
- Rate cache evicted after new rate insert; subsequent getLatestRate returns new value
**Depends on:** 2.5-T14

### 2.5-T16 — Implement AuditLogService: append-only config change logging within same transaction  _(40 min)_
**Context:** Every config write (scheme/partner/rule create/update/activate/deactivate, treasury rate insert) must write to audit_log atomically in the same DB transaction as the change. AuditLogService.logChange(String entityType, Long entityId, String action, Object before, Object after): serialize before/after to JSONB via Jackson, extract actor from Spring Security SecurityContextHolder.getContext().getAuthentication().getName(), persist AuditLog entity. AuditLog entity maps to audit_log table (V205). No UPDATE/DELETE on audit_log (enforced by DB rule in V205). Module: services/config-registry. Actor roles: OPERATOR (human), SYSTEM (automated), PARTNER.
**Steps:** Create AuditLog.java @Entity in services/config-registry/src/main/java/com/gme/pay/config/audit/ (no setters after construction); Create AuditLogRepository extending JpaRepository<AuditLog, Long>; add findByEntityTypeAndEntityId; Create AuditLogService.java @Service; Implement logChange: extract actor from SecurityContextHolder; serialize before/after via ObjectMapper; call AuditLogRepository.save; Add @Transactional(propagation=MANDATORY) to logChange to enforce it is always called within an existing transaction
**Deliverable:** services/config-registry/src/main/java/com/gme/pay/config/audit/AuditLogService.java, AuditLog.java
**Acceptance / logic checks:**
- logChange for CREATE action: before_value=null, after_value contains entity JSON
- logChange for UPDATE: both before_value and after_value populated with JSON snapshots
- actor_id field populated from SecurityContextHolder principal name
- @Transactional(propagation=MANDATORY) causes TransactionRequiredException when called outside a transaction
- AuditLogRepository has no deleteBy methods; direct DB DELETE on audit_log row fails (V205 rule)
**Depends on:** 2.5-T11

### 2.5-T17 — Implement SchemeAdminService: CRUD and lifecycle for qr_scheme and scheme_country  _(45 min)_
**Context:** SchemeAdminService manages the QR scheme registry. Operations: createScheme (new row, status=TESTING), activateScheme (TESTING->ACTIVE: verify treasury_rate exists for scheme payout_ccy before activating; set is_active=true), deactivateScheme (ACTIVE->INACTIVE), updateScheme (name, fees only; not scheme_code), addCountry, removeCountry. Each write: @Transactional + call AuditLogService.logChange + call RedisConfigCache.evictScheme. activateScheme must be separate from createScheme (two-step safety). Reject activation if no treasury_rate row found for scheme payout_ccy. Module: services/config-registry.
**Steps:** Create SchemeAdminService.java @Service in services/config-registry/src/main/java/com/gme/pay/config/service/; Implement createScheme(@Valid SchemeCreateRequest req): persist QrScheme status=TESTING, call logChange action=CREATE; Implement activateScheme(Long schemeId): check TreasuryRateRepository has row for scheme payout_ccy; set status=ACTIVE is_active=true; logChange action=ACTIVATE; evictScheme; Implement updateScheme and deactivateScheme with snapshot-before-change pattern for audit; Implement addCountry(Long schemeId, String countryCode) and removeCountry with audit
**Deliverable:** services/config-registry/src/main/java/com/gme/pay/config/service/SchemeAdminService.java
**Acceptance / logic checks:**
- createScheme returns persisted entity with status=TESTING and is_active=false
- activateScheme throws ConfigValidationException with code MISSING_TREASURY_RATE when no treasury_rate for payout_ccy
- audit_log row created after each operation with correct entity_type=qr_scheme
- Redis cache evicted after activateScheme (next cache.getScheme returns fresh value from DB)
- activateScheme called twice on same id is idempotent (no exception on second call)
**Depends on:** 2.5-T16

### 2.5-T18 — Implement PartnerAdminService: onboarding lifecycle, credential generation for partners  _(45 min)_
**Context:** PartnerAdminService operations: createPartner (status=ONBOARDING), activatePartner (ONBOARDING->ACTIVE: requires at least one active partner_credential; OVERSEAS requires low_balance_threshold_usd not null), suspendPartner, generateCredential (UUID api_key + random 32-byte secret + bcrypt hash; return plaintext ONCE), revokeCredential. Security: api_secret stored as bcrypt hash only; plaintext returned only from generateCredential return value and never retrievable again. HMAC signing secret for Northbound auth is referenced via api_credential_ref pointing to Vault (not stored in DB column). Module: services/config-registry.
**Steps:** Create PartnerAdminService.java @Service; Implement createPartner: validate partner_type; persist with status=ONBOARDING; call logChange CREATE; Implement generateCredential(Long partnerId): generate UUID api_key, SecureRandom 32-byte secret, BCryptPasswordEncoder.encode; persist PartnerCredential; return SecretIssuance(apiKey, plaintextSecret); Implement activatePartner: check active credential exists; if OVERSEAS check low_balance_threshold_usd not null; set status=ACTIVE; logChange ACTIVATE; evictPartner; Implement suspendPartner: set status=SUSPENDED; logChange DEACTIVATE; evictPartner
**Deliverable:** services/config-registry/src/main/java/com/gme/pay/config/service/PartnerAdminService.java
**Acceptance / logic checks:**
- generateCredential returns plaintext secret; api_secret_hash in DB starts with $2a$ (bcrypt)
- activatePartner for OVERSEAS partner with null low_balance_threshold_usd throws ConfigValidationException
- activatePartner with no active credential throws ConfigValidationException
- audit_log has CREATE action with after_value.partner_code for createPartner
- suspendPartner evicts Redis key config:partner:{partnerCode}
**Depends on:** 2.5-T17

### 2.5-T19 — Implement RuleAdminService: rule lifecycle with margin constraint enforcement  _(45 min)_
**Context:** RuleAdminService: createRule (status=DRAFT; compute is_same_ccy_shortcircuit = collection_ccy=settle_a_ccy=settle_b_ccy=payout_ccy), activateRule (DRAFT->ACTIVE: validate m_a+m_b >= 0.02 for cross-border i.e. isSameCcyShortcircuit=false; for same-currency 0.0 is allowed; validate MANUAL rate source has non-null manual_rate populated; set effective_from=NOW()), updateMargins (re-validate constraint, evict cache), suspendRule, addServiceChargeTier (validate non-overlapping ranges), removeServiceChargeTier. Each write: @Transactional + audit + cache evict. Module: services/config-registry.
**Steps:** Create RuleAdminService.java @Service; Implement createRule: derive isSameCcyShortcircuit; persist status=DRAFT; logChange CREATE; Implement activateRule: for cross-border rules assert mA.add(mB).compareTo(new BigDecimal(0.02)) >= 0; for MANUAL source assert manualRateColl not null; set effective_from=NOW(); status=ACTIVE; logChange ACTIVATE; evictRule; Implement updateMargins: capture before snapshot; re-validate constraint; update; audit; evictRule; Implement addServiceChargeTier: check no range overlap with existing tiers for rule_id
**Deliverable:** services/config-registry/src/main/java/com/gme/pay/config/service/RuleAdminService.java
**Acceptance / logic checks:**
- activateRule cross-border m_a=0.009 m_b=0.010 (sum 1.9%) throws ConfigValidationException mentioning 2%
- activateRule cross-border m_a=0.010 m_b=0.010 (sum 2.0%) succeeds
- Same-currency rule m_a=0.0 m_b=0.0 activates without exception
- activateRule with MANUAL source and null manualRateColl throws ConfigValidationException
- is_same_ccy_shortcircuit=true when all four ccys equal KRW (GME_REMIT DOMESTIC scenario)
**Depends on:** 2.5-T18

### 2.5-T20 — Implement TreasuryRateAdminService: manual rate entry with immediate cache invalidation  _(35 min)_
**Context:** In Phase 1 GME Ops manually enters treasury rates (SAD-02 §4.7, BRD §10.4). TreasuryRateAdminService.createRate(String ccyPair, BigDecimal rate, String source): validate ccyPair matches usd_[a-z]{3} (e.g. usd_krw); rate > 0; insert new treasury_rate row (append-only, never update); evict Redis key config:rate:{ccyPair}; call AuditLogService.logChange(treasury_rate, id, CREATE, previousSnapshot, newSnapshot) where previousSnapshot = current latest row. Rates take effect immediately for new rate quotes. Historical rows are retained forever (BOK). Module: services/config-registry.
**Steps:** Create TreasuryRateAdminService.java @Service; Validate ccyPair matches regex usd_[a-z]{3} using Pattern.matches; Validate rate.compareTo(BigDecimal.ZERO) > 0; Capture previousSnapshot via TreasuryRateRepository.findFirstByCcyPairOrderByEffectiveAtDesc; Persist new TreasuryRate with source=MANUAL, effectiveAt=Instant.now() inside @Transactional; Evict Redis key and call logChange in same transaction
**Deliverable:** services/config-registry/src/main/java/com/gme/pay/config/service/TreasuryRateAdminService.java
**Acceptance / logic checks:**
- createRate(usd_krw, 1385.0) inserts new row; TreasuryRateService.getLatestRate(usd_krw) returns 1385.0
- createRate with rate=0 throws ConfigValidationException
- createRate with ccyPair=krw_usd throws ConfigValidationException (wrong direction)
- Old usd_krw row with rate=1380 still exists after createRate (no delete)
- AuditLog row created with entity_type=treasury_rate and after_value.rate=1385.0
**Depends on:** 2.5-T15, 2.5-T16

### 2.5-T21 — Create Flyway migration V207: config_outbox table for transactional outbox  _(20 min)_
**Context:** Config changes publish domain events via transactional Outbox pattern (STACK.md: outbox now, Kafka behind interface). config_outbox: id BIGINT PK, aggregate_type VARCHAR(50), aggregate_id BIGINT, event_type VARCHAR(100), payload JSONB, created_at TIMESTAMPTZ DEFAULT NOW(), published BOOLEAN DEFAULT FALSE. Index on (published, created_at) for efficient poll query: SELECT ... WHERE published=FALSE ORDER BY created_at LIMIT 50. Module: services/config-registry. Published rows are never deleted in Phase 1 (retained for replay/debug); cleanup job is out of scope.
**Steps:** Create V207__config_outbox.sql in services/config-registry/src/main/resources/db/migration/; Define config_outbox table with columns per context; Add index idx_config_outbox_unpublished ON config_outbox(published, created_at) WHERE published=FALSE; Add comment: this table implements the transactional outbox pattern; Phase 2 adds Kafka transport
**Deliverable:** services/config-registry/src/main/resources/db/migration/V207__config_outbox.sql
**Acceptance / logic checks:**
- Flyway V207 applies cleanly after V206
- Index idx_config_outbox_unpublished visible in pg_indexes
- INSERT with published DEFAULT inserts as FALSE
- payload column is JSONB type (not TEXT)
- Flyway V201-V207 all apply in sequence without error on fresh Testcontainers PostgreSQL 16
**Depends on:** 2.5-T06

### 2.5-T22 — Implement ConfigChangeEventPublisher and OutboxPoller for domain event dispatch  _(45 min)_
**Context:** ConfigChangeEventPublisher inserts rows into config_outbox in the same DB transaction as config writes (guaranteed delivery). Event types: SchemeActivatedEvent, PartnerActivatedEvent, RuleUpdatedEvent, TreasuryRateUpdatedEvent. Schemas defined in lib-events. OutboxPoller: Spring @Scheduled(fixedDelay=5000) polls for published=FALSE rows, calls EventPublisher.publish(event) from lib-events interface (in-process impl in Phase 1; Kafka wired in Phase 2), marks published=TRUE. Module: services/config-registry. Kafka is NOT wired in this ticket; only the outbox INSERT and poller.
**Steps:** Create ConfigOutbox.java @Entity mapping config_outbox table; Create ConfigOutboxRepository extending JpaRepository with findTop50ByPublishedFalseOrderByCreatedAtAsc; Create ConfigChangeEventPublisher.java @Component with publish(ConfigChangeEvent event): INSERT into config_outbox in calling @Transactional; Create OutboxPoller.java @Component with @Scheduled(fixedDelay=5000): fetch unpublished, call EventPublisher.publish, set published=true; Wire SchemeAdminService and RuleAdminService to call ConfigChangeEventPublisher after each write
**Deliverable:** services/config-registry/src/main/java/com/gme/pay/config/events/ConfigChangeEventPublisher.java, OutboxPoller.java, ConfigOutbox.java
**Acceptance / logic checks:**
- Config change and outbox INSERT are in same DB transaction (rollback of config write removes outbox row)
- OutboxPoller marks rows published=true after EventPublisher.publish without error
- SchemeActivatedEvent payload contains fields schemeId and schemeCode
- OutboxPoller does not republish rows already marked published=true
- findTop50 limits poll batch to 50 rows to prevent large in-memory spikes
**Depends on:** 2.5-T21, 2.5-T19, 2.5-T20

### 2.5-T23 — Implement ConfigRegistryRestController: scheme, partner, rule, treasury-rate admin endpoints  _(45 min)_
**Context:** REST API for Admin Portal BFF. @RestController @RequestMapping(/internal/v1/config) in services/config-registry. Endpoints: POST /schemes (create), PATCH /schemes/{id}/activate, GET /schemes. POST /partners, PATCH /partners/{id}/activate, PATCH /partners/{id}/suspend, POST /partners/{id}/credentials. POST /rules, PATCH /rules/{id}/activate, PUT /rules/{id}/margins. POST /treasury-rates, GET /treasury-rates. Secured by @PreAuthorize: write ops require SUPER_ADMIN or OPS_ADMIN; read ops allow OPS_VIEWER. Returns lib-errors ApiError on failure. Response codes: 201 create, 200 update/read, 422 validation, 403 auth, 401 unauth. Module: services/config-registry.
**Steps:** Create ConfigRegistryRestController.java @RestController in services/config-registry/src/main/java/com/gme/pay/config/api/; Inject all admin services; Map POST /schemes -> schemeAdminService.createScheme(@Valid @RequestBody); return 201; Map PATCH /schemes/{id}/activate; return 200 with updated SchemeConfig; Map POST /partners/{id}/credentials; return 201 with body {apiKey, secret} (plaintext returned once); Add @PreAuthorize(hasAnyRole(SUPER_ADMIN,OPS_ADMIN)) on write methods; hasAnyRole(SUPER_ADMIN,OPS_ADMIN,OPS_VIEWER) on GET methods
**Deliverable:** services/config-registry/src/main/java/com/gme/pay/config/api/ConfigRegistryRestController.java
**Acceptance / logic checks:**
- POST /schemes with valid body returns 201 and JSON with id and status=TESTING
- PATCH /schemes/{id}/activate when treasury_rate missing returns 422 with errorCode=MISSING_TREASURY_RATE
- POST /partners/{id}/credentials returns 201 with apiKey and secret fields
- OPS_VIEWER JWT calling POST /schemes returns 403
- GET /schemes returns 200 with JSON array even when empty
**Depends on:** 2.5-T17, 2.5-T18, 2.5-T19, 2.5-T20

### 2.5-T24 — Implement GET list endpoints for rules, partners, and treasury-rate history  _(40 min)_
**Context:** Admin Portal UI needs list views for Schemes/Partners/Rules/Margins sections (PRD-07). Add to ConfigRegistryRestController: GET /internal/v1/config/rules?status=ACTIVE, GET /internal/v1/config/partners?status=ACTIVE, GET /internal/v1/config/treasury-rates?ccyPair=usd_krw. Lists served from DB (not Redis, to ensure completeness). Default page size 50, max 200 via @RequestParam. OPS_VIEWER is sufficient. status param validated against allowed enum values; invalid status returns 400. Module: services/config-registry.
**Steps:** Add GET /rules endpoint to ConfigRegistryRestController; inject RuleRegistryService.findAllRulesByStatus; Implement RuleRegistryService.findAllRulesByStatus(String status) using RuleRepository.findByStatus; Add GET /partners using PartnerRegistryService.findAllByStatus; Add GET /treasury-rates with optional ccyPair filter; return list ordered by effectiveAt DESC; Add @RequestParam validation: status must be one of DRAFT, ACTIVE, SUSPENDED (rule) or ONBOARDING, ACTIVE, SUSPENDED, INACTIVE (partner)
**Deliverable:** services/config-registry/src/main/java/com/gme/pay/config/api/ConfigRegistryRestController.java (updated), updated RuleRegistryService.java and PartnerRegistryService.java
**Acceptance / logic checks:**
- GET /rules?status=ACTIVE returns 200 with array (empty array OK)
- GET /rules?status=INVALID returns 400 with errorCode=INVALID_PARAM
- GET /treasury-rates?ccyPair=usd_krw returns both historical rows newest-first after createRate adds second row
- GET /partners without status param defaults to ACTIVE filter
- OPS_VIEWER can call all GET endpoints; POST returns 403 for OPS_VIEWER
**Depends on:** 2.5-T23

### 2.5-T25 — Implement Spring Security 6 config: OAuth2/JWT RBAC for config-registry  _(40 min)_
**Context:** Admin endpoints (/internal/v1/config/**) secured by OAuth2/JWT (STACK.md). Roles in JWT claims: SUPER_ADMIN, OPS_ADMIN, OPS_VIEWER, FINANCE. Spring Security 6 SecurityFilterChain: .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults())). JwtAuthenticationConverter extracts roles from custom JWT claim roles as ROLE_ prefixed authorities. /actuator/health is public. Write ops: SUPER_ADMIN or OPS_ADMIN. Read ops: any of the four roles. application.yml: spring.security.oauth2.resourceserver.jwt.jwk-set-uri configurable via env var. Module: services/config-registry.
**Steps:** Create SecurityConfig.java @Configuration @EnableWebSecurity in services/config-registry/src/main/java/com/gme/pay/config/security/; Define SecurityFilterChain with .oauth2ResourceServer JWT config; Configure JwtAuthenticationConverter: extract roles claim as List<String> mapped to GrantedAuthority with ROLE_ prefix; Add requestMatchers: POST/PATCH/PUT on /internal/** -> hasAnyRole(SUPER_ADMIN,OPS_ADMIN); GET /internal/** -> hasAnyRole(SUPER_ADMIN,OPS_ADMIN,OPS_VIEWER,FINANCE); /actuator/health -> permitAll; Add application.yml spring.security.oauth2.resourceserver.jwt.jwk-set-uri=${JWT_JWK_SET_URI}
**Deliverable:** services/config-registry/src/main/java/com/gme/pay/config/security/SecurityConfig.java
**Acceptance / logic checks:**
- JWT with role=OPS_VIEWER: GET /internal/v1/config/schemes returns 200
- JWT with role=OPS_VIEWER: POST /internal/v1/config/schemes returns 403
- No JWT on POST returns 401 (not 403)
- GET /actuator/health returns 200 without Authorization header
- SecurityFilterChain does not use deprecated WebSecurityConfigurerAdapter
**Depends on:** 2.5-T23

### 2.5-T26 — Implement PartnerAuthService: HMAC-SHA256 Northbound API request validation  _(40 min)_
**Context:** Partners authenticate Northbound API calls via HMAC-SHA256 (STACK.md). PartnerAuthService.validateRequest(String apiKey, String signature, String requestBody, Instant timestamp): 1) lookup PartnerCredential by api_key from DB or cache; 2) retrieve raw HMAC signing secret from Vault via api_credential_ref; 3) compute expected = HMAC-SHA256(concat(apiKey, :, timestamp.toString(), :, requestBody), signingSecret) via javax.crypto.Mac; 4) compare with provided signature (constant-time comparison via MessageDigest.isEqual); 5) reject if abs(now - timestamp) > 5 minutes (replay protection). Returns resolved PartnerConfig on success. Module: services/config-registry. Vault client: Spring Cloud Vault or simple REST call to Vault KV API.
**Steps:** Create PartnerAuthService.java @Service in services/config-registry/src/main/java/com/gme/pay/config/auth/; Implement lookupCredential(String apiKey): PartnerCredential from DB via PartnerCredentialRepository; Implement verifyHmac: fetch raw secret from Vault via api_credential_ref; compute HMAC-SHA256 using Mac.getInstance(HmacSHA256); compare with MessageDigest.isEqual (constant-time); Add timestamp staleness check: throw AuthException(HMAC_TIMESTAMP_EXPIRED) if abs(now-ts) > 300 seconds; On success resolve and return PartnerConfig via PartnerRegistryService.findById
**Deliverable:** services/config-registry/src/main/java/com/gme/pay/config/auth/PartnerAuthService.java
**Acceptance / logic checks:**
- Valid HMAC with fresh timestamp returns non-null PartnerConfig
- Tampered request body results in HMAC mismatch returning false (not exception)
- Timestamp 6 minutes old throws AuthException with code HMAC_TIMESTAMP_EXPIRED
- Unknown api_key throws AuthException with code UNKNOWN_API_KEY
- HMAC computed via HmacSHA256 not MD5
**Depends on:** 2.5-T18

### 2.5-T27 — Unit tests: RuleAdminService margin validation vectors with Mockito  _(40 min)_
**Context:** Pure unit test for RuleAdminService margin constraint logic (no DB needed). Mock RuleRepository, AuditLogService, RedisConfigCache, SecurityContextHolder. Vectors: 1) cross-border m_a=0.010000 m_b=0.010000 -> activateRule succeeds. 2) cross-border m_a=0.009000 m_b=0.010000 (sum=0.019) -> ConfigValidationException message contains 2%. 3) same-currency m_a=0 m_b=0 isSameCcyShortcircuit=true -> succeeds. 4) MANUAL source manualRateColl=null -> ConfigValidationException before any DB write. 5) createRule with collection_ccy=KRW settle_a=KRW settle_b=KRW payout_ccy=KRW -> isSameCcyShortcircuit=true in saved Rule. Module: services/config-registry.
**Steps:** Create RuleAdminServiceTest.java in services/config-registry/src/test/java/com/gme/pay/config/service/; Annotate @ExtendWith(MockitoExtension.class); mock RuleRepository, AuditLogService, RedisConfigCache; Stub SecurityContextHolder to return actor=test-operator; Write 5 @Test methods per vectors above; For vector 2 assert exception.getMessage().contains(2%) or contains(2.0%); For vector 4 verify RuleRepository.save is never called (Mockito.verify(ruleRepository, never()).save(any()))
**Deliverable:** services/config-registry/src/test/java/com/gme/pay/config/service/RuleAdminServiceTest.java
**Acceptance / logic checks:**
- All 5 test methods pass with ./gradlew :services:config-registry:test
- Vector 2 exception message contains 2%
- Vector 3 passes without any exception
- Vector 4: RuleRepository.save never called (verified by Mockito)
- BigDecimal comparisons use compareTo(0) not .equals() for scale-safe assertions
**Depends on:** 2.5-T19

### 2.5-T28 — Unit tests: TreasuryRateService identity-leg and cache behavior with Mockito  _(35 min)_
**Context:** Unit tests for TreasuryRateService. Mock TreasuryRateRepository and RedisConfigCache. Vectors: 1) getLatestRate(usd_krw) returns rate=1380.0. 2) getLatestRate(usd_xyz) throws RateNotFoundException. 3) getLatestRateOrIdentity(usd_usd) returns BigDecimal.ONE; zero interactions with repository. 4) Cache hit: prime cache on first call; second call to getLatestRate skips repo (cache returns hit). 5) After eviction, third call goes back to repo. Assert rateId in snapshot matches stubbed TreasuryRate.id. Module: services/config-registry.
**Steps:** Create TreasuryRateServiceTest.java using @ExtendWith(MockitoExtension.class); Mock TreasuryRateRepository, RedisConfigCache with ArgumentCaptor where needed; Vector 1: stub findFirst... to return TreasuryRate(id=42, ccy=usd_krw, rate=1380); assert snapshot.rate.compareTo(1380)==0 and rateId==42; Vector 2: stub to return Optional.empty(); assertThrows(RateNotFoundException.class); Vector 3: call getLatestRateOrIdentity(usd_usd); verifyNoInteractions(treasuryRateRepository); Vector 4+5: stub cache to return empty then hit; verify repo call count
**Deliverable:** services/config-registry/src/test/java/com/gme/pay/config/service/TreasuryRateServiceTest.java
**Acceptance / logic checks:**
- All 5 test methods pass
- Vector 3: verifyNoInteractions passes (no DB call for identity leg)
- Vector 2: exception type is exactly RateNotFoundException
- Snapshot rate is BigDecimal with original scale preserved
- Cache eviction test: reposito ry call count is 1 for two getLatestRate calls with cache primed after first
**Depends on:** 2.5-T15

### 2.5-T29 — Unit tests: PartnerAuthService HMAC validation with known test vectors  _(40 min)_
**Context:** Unit tests for PartnerAuthService. Mock PartnerCredentialRepository and Vault client. Precomputed test vector: apiKey=testkey123, signingSecret=supersecret, requestBody={amount:100}, timestamp=2026-06-05T00:00:00Z. Compute expected HMAC-SHA256 in test setup using Mac.getInstance(HmacSHA256). Tests: 1) correct key+signature returns PartnerConfig. 2) wrong signing secret causes mismatch. 3) body tampered to {amount:101} causes mismatch. 4) timestamp = Instant.now().minus(6, MINUTES) throws HMAC_TIMESTAMP_EXPIRED. 5) unknown api_key throws UNKNOWN_API_KEY. Module: services/config-registry.
**Steps:** Create PartnerAuthServiceTest.java @ExtendWith(MockitoExtension.class); @BeforeEach: compute expectedHmac using Mac.getInstance(HmacSHA256) with the test vector; Vector 1: stub repo returns valid credential; stub Vault returns supersecret; assert non-null PartnerConfig; Vector 2: stub Vault returns wrongsecret; assert HMAC mismatch; Vector 3: call with body={amount:101} against expectedHmac for {amount:100}; assert false; Vector 4: call with timestamp = Instant.now().minus(Duration.ofMinutes(6)); assertThrows AuthException with code HMAC_TIMESTAMP_EXPIRED; Vector 5: stub repo returns Optional.empty(); assertThrows AuthException UNKNOWN_API_KEY
**Deliverable:** services/config-registry/src/test/java/com/gme/pay/config/auth/PartnerAuthServiceTest.java
**Acceptance / logic checks:**
- All 5 test methods pass
- Test uses fixed Instant (not Instant.now() in production code) to make replay test deterministic
- HMAC algorithm is HmacSHA256 asserted via Mac.getAlgorithm()
- Tampered body test uses correct expected HMAC (not just any string comparison)
- Exception codes are exact string constants HMAC_TIMESTAMP_EXPIRED and UNKNOWN_API_KEY
**Depends on:** 2.5-T26

### 2.5-T30 — Integration test: SchemeRegistryService cache-aside with Testcontainers PostgreSQL and Redis  _(45 min)_
**Context:** Integration test using @SpringBootTest with Testcontainers PostgreSQL 16 and Redis 7. Flyway V201-V207 apply automatically at startup. Test scenarios: A) findByCode(ZEROPAY) after V206 seed returns non-empty SchemeConfig with payoutCcy=KRW; B) second findByCode call served from Redis cache (spy on QrSchemeRepository: findBySchemeCode called exactly once); C) findActiveByCountry(KR) returns list with ZEROPAY; D) findByCode(UNKNOWN) returns empty without exception; E) activateScheme then findByCode returns status=ACTIVE (cache invalidation works). Module: services/config-registry.
**Steps:** Create ConfigRegistryIntegrationTest.java in services/config-registry/src/test/java/com/gme/pay/config/; Annotate @SpringBootTest @Testcontainers; declare @Container PostgreSQLContainer and @Container GenericContainer (Redis 7-alpine); Override datasource and redis host properties via @DynamicPropertySource; Inject SchemeRegistryService; use @SpyBean QrSchemeRepository to count DB calls; Write 5 @Test methods for scenarios A-E
**Deliverable:** services/config-registry/src/test/java/com/gme/pay/config/ConfigRegistryIntegrationTest.java
**Acceptance / logic checks:**
- All 5 tests pass with ./gradlew :services:config-registry:test
- Scenario B: QrSchemeRepository.findBySchemeCode invoked exactly once for two findByCode calls
- Scenario D: Optional.empty() returned, no exception
- Scenario E: findByCode returns SchemeConfig with status=ACTIVE after activateScheme (cache eviction validated)
- Test suite completes in under 60 seconds including Testcontainers startup
**Depends on:** 2.5-T17

### 2.5-T31 — Integration test: outbox publish and cache invalidation on config change end-to-end  _(45 min)_
**Context:** Integration test on Testcontainers PostgreSQL. Scenario A (outbox): call schemeAdminService.activateScheme; assert config_outbox row inserted with published=FALSE; wait up to 10 seconds for OutboxPoller tick; assert published=TRUE and EventPublisher.publish called with SchemeActivatedEvent. Scenario B (rollback): wrap in test @Transactional rollback; assert no outbox row after rollback (atomicity proof). Scenario C (cache evict + outbox): call ruleAdminService.updateMargins; assert both outbox row inserted AND Redis key evicted within same transaction. Module: services/config-registry.
**Steps:** Create OutboxIntegrationTest.java @SpringBootTest @Testcontainers; Declare PostgreSQLContainer; use @MockBean or @SpyBean on EventPublisher interface; Seed a TESTING scheme; insert its treasury_rate so activateScheme can succeed; Scenario A: call activateScheme; assert outbox row published=FALSE then true within 10s Awaitility wait; Scenario B: annotate sub-test with @Transactional(rollbackFor=Exception) and throw; assert 0 outbox rows; Scenario C: updateMargins; assert outbox row and Redis eviction in same logical unit
**Deliverable:** services/config-registry/src/test/java/com/gme/pay/config/events/OutboxIntegrationTest.java
**Acceptance / logic checks:**
- Scenario A: EventPublisher.publish called exactly once with SchemeActivatedEvent
- Scenario B: outbox row count=0 after rollback (outbox row was in same transaction)
- OutboxPoller does not republish rows already marked published=TRUE
- Scenario C: Redis key absent after updateMargins
- Test total runtime under 45 seconds
**Depends on:** 2.5-T22, 2.5-T30

### 2.5-T32 — Add Prometheus metrics and OpenTelemetry tracing to config-registry service  _(35 min)_
**Context:** STACK.md: OpenTelemetry traces+metrics+logs -> Prometheus + Grafana + Jaeger. For config-registry: 1) add micrometer-tracing-bridge-otel + opentelemetry-exporter-otlp to build.gradle; 2) metrics: config_registry_cache_hit_total and config_registry_cache_miss_total counters in RedisConfigCache (tags: entity_type=scheme/partner/rule/rate); config_registry_audit_write_seconds histogram in AuditLogService; 3) expose /actuator/prometheus; 4) add traceId to logback MDC (%X{traceId} in log pattern). OTEL endpoint via env var OTEL_EXPORTER_OTLP_ENDPOINT (no hardcode). Module: services/config-registry.
**Steps:** Add io.micrometer:micrometer-tracing-bridge-otel and io.opentelemetry:opentelemetry-exporter-otlp to services/config-registry/build.gradle; Inject MeterRegistry into RedisConfigCache; increment config_registry_cache_hit_total or _miss_total with tag entity_type on each get call; Inject MeterRegistry into AuditLogService; wrap logChange with Timer.record(config_registry_audit_write_seconds); Create src/main/resources/logback-spring.xml with pattern including %X{traceId}; Add management.endpoints.web.exposure.include=health,prometheus to application.yml; management.tracing.sampling.probability=1.0
**Deliverable:** services/config-registry/build.gradle (updated), services/config-registry/src/main/resources/logback-spring.xml
**Acceptance / logic checks:**
- GET /actuator/prometheus returns 200 with body containing config_registry_cache_hit_total
- Cache miss then hit: counter cache_miss=1 cache_hit=1 for entity_type=scheme
- config_registry_audit_write_seconds_bucket present in metrics endpoint output
- Log line for a cache operation contains traceId field in MDC
- OTEL endpoint is read from env var OTEL_EXPORTER_OTLP_ENDPOINT; app starts without it set (export disabled gracefully)
**Depends on:** 2.5-T25

### 2.5-T33 — Add docker-compose services: config-registry, PostgreSQL 16, Redis 7, Vault dev for local dev  _(35 min)_
**Context:** STACK.md: docker-compose for local dev infra. Root docker-compose.yml additions: postgres service (image postgres:16, POSTGRES_DB=gmepay, port 5433:5432 matching memory: local PostgreSQL on port 5433, named volume gmepay-pg-data), redis service (image redis:7-alpine, port 6379:6379), vault service (image hashicorp/vault, VAULT_DEV_ROOT_TOKEN_ID=dev-token, port 8200:8200, cap_add IPC_LOCK), config-registry service (build: ./services/config-registry, SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/gmepay, SPRING_DATA_REDIS_HOST=redis, VAULT_ADDR=http://vault:8200, port 8085:8080, depends_on postgres redis vault, healthcheck via /actuator/health). Module: root.
**Steps:** Open (or create) docker-compose.yml at repo root; Add postgres service with correct image, env, port 5433:5432, volume; Add redis service image redis:7-alpine port 6379:6379; Add vault service in dev mode with dev-token and IPC_LOCK cap; Add config-registry service with build context, env vars, depends_on, and healthcheck on http://localhost:8085/actuator/health; Run docker compose config to validate YAML syntax
**Deliverable:** docker-compose.yml (root) updated with postgres, redis, vault, and config-registry services
**Acceptance / logic checks:**
- docker compose config validates without error
- config-registry service depends_on list includes postgres and redis
- postgres port mapping is 5433:5432
- vault VAULT_DEV_ROOT_TOKEN_ID=dev-token set
- config-registry healthcheck curl http://localhost:8085/actuator/health returns 0 exit code after startup
**Depends on:** 2.5-T09


## WBS 3.2 — Core entities: Scheme/Partner/Rule/Direction
### 3.2-T01 — Create Flyway V010__create_qr_scheme.sql in services/config-registry  _(30 min)_
**Context:** WBS 3.2 delivers the core config-entity layer. qr_scheme is the root: represents a payment network (e.g. ZeroPay operated by KFTC). Adding a scheme is a config INSERT, never code. Naming convention: snake_case; PKs are BIGINT surrogate auto-increment; timestamps TIMESTAMPTZ; enums stored as VARCHAR with CHECK for portability; money as DECIMAL; soft-delete via is_active + deactivated_at.
**Steps:** In module services/config-registry create src/main/resources/db/migration/V010__create_qr_scheme.sql; Define: id BIGINT GENERATED ALWAYS AS IDENTITY PK, scheme_code VARCHAR(30) UNIQUE NOT NULL, name VARCHAR(100) NOT NULL, payout_ccy CHAR(3) NOT NULL, supported_modes VARCHAR(20) NOT NULL CHECK(supported_modes IN ('MPM','CPM','BOTH')), settlement_counterparty VARCHAR(120), merchant_fee_domestic_min DECIMAL(6,4), merchant_fee_domestic_max DECIMAL(6,4), merchant_fee_crossborder_min DECIMAL(6,4), merchant_fee_crossborder_max DECIMAL(6,4), van_fee_domestic_min DECIMAL(6,4), van_fee_domestic_max DECIMAL(6,4), van_fee_crossborder_min DECIMAL(6,4), van_fee_crossborder_max DECIMAL(6,4), gme_fee_share_pct DECIMAL(6,4), partner_b_quote_enabled BOOLEAN NOT NULL DEFAULT FALSE, partner_b_quote_deviation_pct DECIMAL(6,4) NOT NULL DEFAULT 0.0100, sftp_host VARCHAR(255), sftp_path_outbound VARCHAR(255), sftp_path_inbound VARCHAR(255), api_endpoint VARCHAR(512), api_credential_ref VARCHAR(120), status VARCHAR(20) NOT NULL DEFAULT 'TESTING' CHECK(status IN ('TESTING','ACTIVE','INACTIVE')), is_active BOOLEAN NOT NULL DEFAULT TRUE, deactivated_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), created_by VARCHAR(120), updated_by VARCHAR(120); Add CREATE INDEX idx_qr_scheme_status_active ON qr_scheme(status, is_active); Run Flyway migrate against local PostgreSQL 16 (docker-compose port 5433) and confirm checksum
**Deliverable:** services/config-registry/src/main/resources/db/migration/V010__create_qr_scheme.sql
**Acceptance / logic checks:**
- Flyway validate passes on PostgreSQL 16
- supported_modes CHECK rejects 'PUSH'; accepts 'MPM','CPM','BOTH'
- status CHECK rejects 'ENABLED'; accepts 'TESTING','ACTIVE','INACTIVE'
- scheme_code UNIQUE blocks duplicate insert
- partner_b_quote_deviation_pct defaults to 0.0100

### 3.2-T02 — Create Flyway V011__create_scheme_country.sql in services/config-registry  _(20 min)_
**Context:** scheme_country maps active QR schemes to ISO 3166-1 alpha-2 country codes for CPM location-based scheme selection. Business key UNIQUE(scheme_id, country_code). FK to qr_scheme ON DELETE RESTRICT (cannot drop a scheme with active country mappings). Standard audit columns required on all mutable tables per DAT-03 conventions.
**Steps:** Create services/config-registry/src/main/resources/db/migration/V011__create_scheme_country.sql; Define: id BIGINT GENERATED ALWAYS AS IDENTITY PK, scheme_id BIGINT NOT NULL REFERENCES qr_scheme(id) ON DELETE RESTRICT, country_code CHAR(2) NOT NULL, is_active BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), created_by VARCHAR(120), updated_by VARCHAR(120); Add CONSTRAINT uq_scheme_country UNIQUE(scheme_id, country_code); Add CREATE INDEX idx_scheme_country_scheme_id ON scheme_country(scheme_id); Run Flyway migrate and verify ordering after V010
**Deliverable:** services/config-registry/src/main/resources/db/migration/V011__create_scheme_country.sql
**Acceptance / logic checks:**
- Migration applies after V010 without error
- UNIQUE(scheme_id, country_code) rejects duplicate pair insert
- FK to qr_scheme: inserting non-existent scheme_id fails with FK violation
- Deleting a qr_scheme row referenced by scheme_country is blocked (ON DELETE RESTRICT)
- is_active defaults TRUE
**Depends on:** 3.2-T01

### 3.2-T03 — Create Flyway V012__create_partner.sql in services/config-registry  _(30 min)_
**Context:** partner represents payment apps (GME Remit=LOCAL/KRW, SendMN=OVERSEAS/USD). partner_type: LOCAL or OVERSEAS. OVERSEAS partners require prefunding; LOCAL do not. rate_quote_ttl_seconds range 60-1800 enforced by DB CHECK, default 300 (aggregator-bound=60, otherwise 300 per TICKET_BRIEF). Soft-delete only; hard deletes prohibited on tables referenced by transactions.
**Steps:** Create services/config-registry/src/main/resources/db/migration/V012__create_partner.sql; Define: id BIGINT GENERATED ALWAYS AS IDENTITY PK, partner_code VARCHAR(30) UNIQUE NOT NULL, name VARCHAR(100) NOT NULL, partner_type VARCHAR(10) NOT NULL CHECK(partner_type IN ('LOCAL','OVERSEAS')), collection_ccy CHAR(3) NOT NULL, settle_a_ccy CHAR(3) NOT NULL, webhook_url VARCHAR(512), rate_quote_ttl_seconds INT NOT NULL DEFAULT 300 CHECK(rate_quote_ttl_seconds BETWEEN 60 AND 1800), low_balance_threshold_usd DECIMAL(20,4), low_balance_alert_email VARCHAR(255), status VARCHAR(20) NOT NULL DEFAULT 'ONBOARDING' CHECK(status IN ('ONBOARDING','ACTIVE','SUSPENDED','INACTIVE')), is_active BOOLEAN NOT NULL DEFAULT TRUE, deactivated_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), created_by VARCHAR(120), updated_by VARCHAR(120); Add CREATE INDEX idx_partner_type_active ON partner(partner_type, is_active)
**Deliverable:** services/config-registry/src/main/resources/db/migration/V012__create_partner.sql
**Acceptance / logic checks:**
- partner_type CHECK rejects 'BANK'; accepts 'LOCAL','OVERSEAS'
- status CHECK rejects 'PENDING'; accepts all four values
- rate_quote_ttl_seconds CHECK rejects 59 and 1801; accepts 60 and 1800
- partner_code UNIQUE blocks duplicate
- Migration applies after V011
**Depends on:** 3.2-T02

### 3.2-T04 — Create Flyway V013__create_rule.sql with UNIQUE(partner,scheme,direction) and margin CHECK  _(35 min)_
**Context:** rule is the join of partner x qr_scheme x direction. Business key UNIQUE(partner_id, scheme_id, direction). Direction: INBOUND, OUTBOUND, DOMESTIC, HUB. Margin m_a (collection-side) and m_b (payout-side) are DECIMAL(8,6). Cross-border margin constraint: CHECK(is_same_ccy_shortcircuit=TRUE OR (m_a+m_b)>=0.02) enforced at DB level. Same-ccy rules are exempt (m_a=m_b=0 allowed). rate_coll_source and rate_pay_source: IDENTITY, LIVE, MANUAL, PARTNER. effective_from governs when rule changes apply to new txns.
**Steps:** Create services/config-registry/src/main/resources/db/migration/V013__create_rule.sql; Define: id BIGINT GENERATED ALWAYS AS IDENTITY PK, partner_id BIGINT NOT NULL REFERENCES partner(id) ON DELETE RESTRICT, scheme_id BIGINT NOT NULL REFERENCES qr_scheme(id) ON DELETE RESTRICT, direction VARCHAR(10) NOT NULL CHECK(direction IN ('INBOUND','OUTBOUND','DOMESTIC','HUB')), collection_ccy CHAR(3), settle_a_ccy CHAR(3), settle_b_ccy CHAR(3), payout_ccy CHAR(3), rate_coll_source VARCHAR(10) NOT NULL CHECK(rate_coll_source IN ('IDENTITY','LIVE','MANUAL','PARTNER')), rate_pay_source VARCHAR(10) NOT NULL CHECK(rate_pay_source IN ('IDENTITY','LIVE','MANUAL','PARTNER')), manual_rate_coll DECIMAL(20,8), manual_rate_pay DECIMAL(20,8), m_a DECIMAL(8,6) NOT NULL DEFAULT 0 CHECK(m_a>=0), m_b DECIMAL(8,6) NOT NULL DEFAULT 0 CHECK(m_b>=0), CONSTRAINT chk_rule_margin CHECK(is_same_ccy_shortcircuit=TRUE OR (m_a+m_b)>=0.02), service_charge_amount DECIMAL(20,4) NOT NULL DEFAULT 0, service_charge_ccy CHAR(3), is_same_ccy_shortcircuit BOOLEAN NOT NULL DEFAULT FALSE, status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','ACTIVE','SUSPENDED')), effective_from TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), created_by VARCHAR(120), updated_by VARCHAR(120); Add CONSTRAINT uq_rule UNIQUE(partner_id, scheme_id, direction); Add indexes on partner_id, scheme_id, and composite (partner_id, scheme_id, direction, status)
**Deliverable:** services/config-registry/src/main/resources/db/migration/V013__create_rule.sql
**Acceptance / logic checks:**
- UNIQUE(partner_id,scheme_id,direction) rejects duplicate triple insert
- direction CHECK rejects 'BILATERAL'; accepts all four values
- chk_rule_margin rejects is_same_ccy_shortcircuit=FALSE with m_a=0.005 m_b=0.005 (sum=0.01 less than 0.02)
- chk_rule_margin accepts is_same_ccy_shortcircuit=TRUE with m_a=0 m_b=0
- chk_rule_margin accepts is_same_ccy_shortcircuit=FALSE with m_a=0.01 m_b=0.01 (sum=0.02)
- Migration applies after V012
**Depends on:** 3.2-T03

### 3.2-T05 — Create Flyway V014__create_service_charge_tier.sql in services/config-registry  _(20 min)_
**Context:** service_charge_tier allows volume-based overrides for the flat rule.service_charge_amount. Tiers indexed by collection_usd (USD pool amount, DECIMAL(20,4)). min_collection_usd inclusive, max_collection_usd exclusive (NULL=no cap). If no tiers exist for a rule, flat service_charge_amount from rule applies. FK to rule ON DELETE RESTRICT (tiers protect the rule from deletion while active).
**Steps:** Create services/config-registry/src/main/resources/db/migration/V014__create_service_charge_tier.sql; Define: id BIGINT GENERATED ALWAYS AS IDENTITY PK, rule_id BIGINT NOT NULL REFERENCES rule(id) ON DELETE RESTRICT, min_collection_usd DECIMAL(20,4) NOT NULL CHECK(min_collection_usd>=0), max_collection_usd DECIMAL(20,4) CHECK(max_collection_usd IS NULL OR max_collection_usd>min_collection_usd), charge_amount DECIMAL(20,4) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), created_by VARCHAR(120), updated_by VARCHAR(120); Add CREATE INDEX idx_sct_rule_id ON service_charge_tier(rule_id)
**Deliverable:** services/config-registry/src/main/resources/db/migration/V014__create_service_charge_tier.sql
**Acceptance / logic checks:**
- CHECK rejects max_collection_usd <= min_collection_usd (e.g. min=100, max=50 fails)
- CHECK accepts max_collection_usd = NULL (no-cap tier)
- FK to rule: inserting non-existent rule_id fails
- Deleting a rule that has tiers is blocked (ON DELETE RESTRICT)
- Migration applies after V013
**Depends on:** 3.2-T04

### 3.2-T06 — Create Flyway V015__create_treasury_rate.sql in services/config-registry  _(25 min)_
**Context:** treasury_rate stores FX rates in canonical form: usd_{ccy} = units of target ccy per 1 USD (e.g. usd_krw=1380.0000 means 1 USD=1380 KRW). Type DECIMAL(20,8) for 8dp precision required for low-value currencies. source: LIVE (xe.com Phase 2) or MANUAL (Phase 1 Ops entry). Latest rate query: WHERE ccy_pair='usd_krw' ORDER BY effective_at DESC LIMIT 1. Historical rows retained for audit and BOK reconciliation. rate must be > 0.
**Steps:** Create services/config-registry/src/main/resources/db/migration/V015__create_treasury_rate.sql; Define: id BIGINT GENERATED ALWAYS AS IDENTITY PK, ccy_pair VARCHAR(10) NOT NULL, rate DECIMAL(20,8) NOT NULL CHECK(rate>0), source VARCHAR(10) NOT NULL CHECK(source IN ('LIVE','MANUAL')), effective_at TIMESTAMPTZ NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), created_by VARCHAR(120), updated_by VARCHAR(120); Add CREATE INDEX idx_treasury_rate_pair_time ON treasury_rate(ccy_pair, effective_at DESC) for latest-rate lookups
**Deliverable:** services/config-registry/src/main/resources/db/migration/V015__create_treasury_rate.sql
**Acceptance / logic checks:**
- CHECK(rate>0) rejects zero and negative rates
- source CHECK rejects 'AUTO'; accepts 'LIVE','MANUAL'
- Multiple rows for same ccy_pair coexist (no UNIQUE on pair alone)
- Index idx_treasury_rate_pair_time present in pg_indexes
- Migration applies after V014
**Depends on:** 3.2-T04

### 3.2-T07 — Define Direction enum in libs/lib-domain (Java 21)  _(20 min)_
**Context:** Direction is used across svc-config-registry, rate-fx engine, smart-router, and payment-executor. Values: INBOUND, OUTBOUND, DOMESTIC, HUB. Stored as VARCHAR in PostgreSQL via @Enumerated(EnumType.STRING). Placed in shared libs/lib-domain so all Gradle modules reference one definition. Must round-trip cleanly through Jackson JSON serialization.
**Steps:** In libs/lib-domain create src/main/java/com/gme/pay/domain/Direction.java; Define: public enum Direction { INBOUND, OUTBOUND, DOMESTIC, HUB }; Add Javadoc: INBOUND=overseas customer sends money to domestic merchant; OUTBOUND=domestic customer sends to overseas merchant; DOMESTIC=same-country; HUB=inter-hub routing; Create libs/lib-domain/src/test/java/com/gme/pay/domain/DirectionTest.java (JUnit 5): verify valueOf for all 4, values().length==4, Jackson round-trip of INBOUND
**Deliverable:** libs/lib-domain/src/main/java/com/gme/pay/domain/Direction.java and libs/lib-domain/src/test/java/com/gme/pay/domain/DirectionTest.java
**Acceptance / logic checks:**
- Direction.valueOf('INBOUND') returns INBOUND
- Direction.values().length == 4
- Jackson ObjectMapper().writeValueAsString(DOMESTIC) returns '"DOMESTIC"'
- Jackson readValue('"HUB"', Direction.class) returns Direction.HUB
- Gradle build for libs/lib-domain succeeds

### 3.2-T08 — Define PartnerType enum in libs/lib-domain (Java 21)  _(15 min)_
**Context:** PartnerType (LOCAL or OVERSEAS) drives prefunding logic and rate-engine short-circuit. LOCAL = GME Remit (KRW, no prefunding required). OVERSEAS = SendMN/T-Bank (USD prefunded; deduction required before scheme call). Stored as VARCHAR(10) in PostgreSQL. Placed in libs/lib-domain alongside Direction.
**Steps:** Create libs/lib-domain/src/main/java/com/gme/pay/domain/PartnerType.java; Define: public enum PartnerType { LOCAL, OVERSEAS }; Add Javadoc: LOCAL=no prefunding; OVERSEAS=USD prefunding required; Create PartnerTypeTest.java (JUnit 5): valueOf both values, values().length==2, Jackson round-trip
**Deliverable:** libs/lib-domain/src/main/java/com/gme/pay/domain/PartnerType.java and libs/lib-domain/src/test/java/com/gme/pay/domain/PartnerTypeTest.java
**Acceptance / logic checks:**
- PartnerType.valueOf('LOCAL') returns LOCAL
- PartnerType.values().length == 2
- Jackson round-trips LOCAL and OVERSEAS to/from JSON strings
- Gradle build for libs/lib-domain succeeds
**Depends on:** 3.2-T07

### 3.2-T09 — Implement QrScheme JPA entity in services/config-registry (Spring Boot 3.x / Hibernate 6)  _(35 min)_
**Context:** JPA entity for qr_scheme table (V010). Module services/config-registry. Use @Entity @Table(name='qr_scheme'). All TIMESTAMPTZ columns map to Instant. BigDecimal for all DECIMAL columns. VARCHAR CHECK-constrained columns map to String (not DB enum, per DAT-03 portability rule). Soft-delete: isActive Boolean, deactivatedAt Instant. Audit columns via @EntityListeners(AuditingEntityListener.class): @CreatedDate createdAt, @LastModifiedDate updatedAt, @CreatedBy createdBy, @LastModifiedBy updatedBy.
**Steps:** Create services/config-registry/src/main/java/com/gme/pay/configregistry/entity/QrScheme.java; Annotate @Entity @Table(name='qr_scheme') @EntityListeners(AuditingEntityListener.class); Map id with @Id @GeneratedValue(strategy=GenerationType.IDENTITY); Map schemeCode @Column(name='scheme_code', unique=true, nullable=false, length=30); Map all DECIMAL(6,4) fee columns as BigDecimal; all CHAR(3) ccy columns as String length=3; Set spring.jpa.hibernate.ddl-auto=validate in test profile to verify schema match
**Deliverable:** services/config-registry/src/main/java/com/gme/pay/configregistry/entity/QrScheme.java
**Acceptance / logic checks:**
- Hibernate ddl-auto=validate passes against V010 migration on PostgreSQL 16
- schemeCode annotated @Column(unique=true, nullable=false, length=30)
- supportedModes and status are String (not DB enum type)
- All DECIMAL(6,4) fee percentage columns are BigDecimal
- Audit fields createdAt, updatedAt, createdBy, updatedBy all present with correct JPA auditing annotations
**Depends on:** 3.2-T01

### 3.2-T10 — Implement Partner JPA entity in services/config-registry  _(30 min)_
**Context:** JPA entity for partner table (V012). partnerType maps to PartnerType enum from libs/lib-domain via @Enumerated(EnumType.STRING). status mapped as String (ONBOARDING, ACTIVE, SUSPENDED, INACTIVE). rateQuoteTtlSeconds: int, default 300. lowBalanceThresholdUsd: BigDecimal nullable (OVERSEAS only). Soft-delete: isActive Boolean + deactivatedAt Instant. Audit columns via @EntityListeners.
**Steps:** Create services/config-registry/src/main/java/com/gme/pay/configregistry/entity/Partner.java; Annotate @Entity @Table(name='partner') @EntityListeners(AuditingEntityListener.class); Import PartnerType from libs/lib-domain; annotate @Enumerated(EnumType.STRING); Map collectionCcy and settleACcy as @Column(name='collection_ccy', nullable=false, length=3); Map lowBalanceThresholdUsd as BigDecimal @Column(name='low_balance_threshold_usd', precision=20, scale=4) nullable; Verify ddl-auto=validate passes against V012
**Deliverable:** services/config-registry/src/main/java/com/gme/pay/configregistry/entity/Partner.java
**Acceptance / logic checks:**
- Hibernate ddl-auto=validate passes against V012 on PostgreSQL 16
- partnerType field uses @Enumerated(EnumType.STRING) with PartnerType from libs/lib-domain
- partnerCode annotated @Column(unique=true, nullable=false, length=30)
- lowBalanceThresholdUsd is nullable BigDecimal with precision=20 scale=4
- isActive annotated @Column(name='is_active', nullable=false)
**Depends on:** 3.2-T03, 3.2-T08

### 3.2-T11 — Implement Rule JPA entity in services/config-registry  _(35 min)_
**Context:** JPA entity for rule table (V013). Business key UNIQUE(partnerId, schemeId, direction) declared via @Table uniqueConstraints. direction maps to Direction enum from libs/lib-domain. rateCollSource and ratePaySource map as String (IDENTITY, LIVE, MANUAL, PARTNER). m_a and m_b: BigDecimal @Column(precision=8, scale=6). isIsSameCcyShortcircuit: Boolean. effectiveFrom: Instant nullable. ManyToOne LAZY to Partner and QrScheme.
**Steps:** Create services/config-registry/src/main/java/com/gme/pay/configregistry/entity/Rule.java; Annotate @Entity @Table(name='rule', uniqueConstraints=@UniqueConstraint(columnNames={'partner_id','scheme_id','direction'})) @EntityListeners(AuditingEntityListener.class); Add @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name='partner_id') Partner partner; Add @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name='scheme_id') QrScheme scheme; Map direction @Enumerated(EnumType.STRING) Direction direction from libs/lib-domain; Map m_a @Column(name='m_a', precision=8, scale=6, nullable=false) BigDecimal ma; same for m_b; Verify ddl-auto=validate passes against V013
**Deliverable:** services/config-registry/src/main/java/com/gme/pay/configregistry/entity/Rule.java
**Acceptance / logic checks:**
- Hibernate ddl-auto=validate passes against V013
- @Table uniqueConstraints lists partner_id, scheme_id, direction
- direction @Enumerated(EnumType.STRING) with Direction enum from libs/lib-domain
- m_a and m_b are BigDecimal with precision=8 scale=6
- partner and scheme are @ManyToOne(fetch=LAZY)
- effectiveFrom is Instant nullable
**Depends on:** 3.2-T04, 3.2-T07, 3.2-T10

### 3.2-T12 — Implement ServiceChargeTier and TreasuryRate JPA entities in services/config-registry  _(30 min)_
**Context:** ServiceChargeTier (V014): FK @ManyToOne LAZY to Rule; minCollectionUsd inclusive, maxCollectionUsd nullable (no cap), chargeAmount all BigDecimal DECIMAL(20,4). TreasuryRate (V015): ccyPair VARCHAR(10), rate BigDecimal DECIMAL(20,8) always positive, source String, effectiveAt Instant. Historical rows retained; no soft-delete needed. Both need standard audit columns via @EntityListeners.
**Steps:** Create services/config-registry/src/main/java/com/gme/pay/configregistry/entity/ServiceChargeTier.java with @ManyToOne(fetch=LAZY) Rule rule, BigDecimal minCollectionUsd (not null), maxCollectionUsd (nullable), chargeAmount (not null); Create services/config-registry/src/main/java/com/gme/pay/configregistry/entity/TreasuryRate.java with ccyPair String, rate BigDecimal @Column(precision=20,scale=8), source String, effectiveAt Instant; Add @EntityListeners(AuditingEntityListener.class) on both; Verify ddl-auto=validate passes against V014 and V015
**Deliverable:** services/config-registry/src/main/java/com/gme/pay/configregistry/entity/ServiceChargeTier.java and TreasuryRate.java
**Acceptance / logic checks:**
- ServiceChargeTier.maxCollectionUsd is nullable BigDecimal
- TreasuryRate.rate is BigDecimal with precision=20 scale=8
- Hibernate ddl-auto=validate passes both entities against V014 and V015 on PostgreSQL 16
- ServiceChargeTier.rule is @ManyToOne(fetch=LAZY) with name='rule_id'
- TreasuryRate.effectiveAt is Instant @Column(name='effective_at', nullable=false)
**Depends on:** 3.2-T05, 3.2-T06, 3.2-T11

### 3.2-T13 — Implement QrSchemeRepository and PartnerRepository (Spring Data JPA) in services/config-registry  _(25 min)_
**Context:** Spring Data JPA repositories for config entities. QrSchemeRepository needs: Optional findBySchemeCode(String), List findAllByIsActiveTrue(). PartnerRepository needs: Optional findByPartnerCode(String), List findAllByPartnerTypeAndIsActiveTrue(PartnerType), Optional findByIdAndIsActiveTrue(Long). Both extend JpaRepository. Use Spring Data method-name query derivation; no @Query annotations needed.
**Steps:** Create services/config-registry/src/main/java/com/gme/pay/configregistry/repository/QrSchemeRepository.java extending JpaRepository<QrScheme, Long> @Repository; Add: Optional<QrScheme> findBySchemeCode(String schemeCode); List<QrScheme> findAllByIsActiveTrue(); Create PartnerRepository.java extending JpaRepository<Partner, Long> @Repository; Add: Optional<Partner> findByPartnerCode(String partnerCode); List<Partner> findAllByPartnerTypeAndIsActiveTrue(PartnerType type); Optional<Partner> findByIdAndIsActiveTrue(Long id)
**Deliverable:** services/config-registry/src/main/java/com/gme/pay/configregistry/repository/QrSchemeRepository.java and PartnerRepository.java
**Acceptance / logic checks:**
- findBySchemeCode on unknown code returns Optional.empty()
- findAllByIsActiveTrue returns only active records after saving mix of active/inactive
- findAllByPartnerTypeAndIsActiveTrue(OVERSEAS) returns only active OVERSEAS partners
- Spring @DataJpaTest slice loads both repositories without error
- Method name query derivation validated by Spring Data on startup (no typo in method names)
**Depends on:** 3.2-T09, 3.2-T10

### 3.2-T14 — Implement RuleRepository, ServiceChargeTierRepository, TreasuryRateRepository in services/config-registry  _(25 min)_
**Context:** RuleRepository: findByPartnerIdAndSchemeIdAndDirection for exact business key lookup; existsByPartnerIdAndSchemeIdAndDirectionAndStatus for duplicate guard; findAllByPartnerIdAndStatus. TreasuryRateRepository: findTopByCcyPairOrderByEffectiveAtDesc (latest rate); findByCcyPairOrderByEffectiveAtDesc (full history). ServiceChargeTierRepository: findByRuleIdOrderByMinCollectionUsdAsc (tier lookup in ascending order).
**Steps:** Create RuleRepository.java @Repository: Optional<Rule> findByPartnerIdAndSchemeIdAndDirection(Long, Long, Direction); boolean existsByPartnerIdAndSchemeIdAndDirectionAndStatus(Long, Long, Direction, String); List<Rule> findAllByPartnerIdAndStatus(Long, String); Create TreasuryRateRepository.java @Repository: Optional<TreasuryRate> findTopByCcyPairOrderByEffectiveAtDesc(String); List<TreasuryRate> findByCcyPairOrderByEffectiveAtDesc(String); Create ServiceChargeTierRepository.java @Repository: List<ServiceChargeTier> findByRuleIdOrderByMinCollectionUsdAsc(Long)
**Deliverable:** services/config-registry/src/main/java/com/gme/pay/configregistry/repository/RuleRepository.java, TreasuryRateRepository.java, ServiceChargeTierRepository.java
**Acceptance / logic checks:**
- findByPartnerIdAndSchemeIdAndDirection returns correct Rule when all three match; Optional.empty() when direction differs
- existsByPartnerIdAndSchemeIdAndDirectionAndStatus returns true for exact match; false for status mismatch
- findTopByCcyPairOrderByEffectiveAtDesc returns row with highest effective_at for 'usd_krw'
- findByRuleIdOrderByMinCollectionUsdAsc returns tiers sorted ascending by min_collection_usd
- All three repositories load in @DataJpaTest slice with Testcontainers postgres:16
**Depends on:** 3.2-T11, 3.2-T12, 3.2-T13

### 3.2-T15 — Implement SameCcyShortcircuitDetector @Component in services/config-registry  _(20 min)_
**Context:** Rule.isIsSameCcyShortcircuit is TRUE when collection_ccy = settle_a_ccy = settle_b_ccy = payout_ccy (e.g. GME Remit on ZeroPay: all KRW). When TRUE: USD pool is bypassed; collection_amount = target_payout + service_charge; USD margin fields NULL/0. Computed and set by service layer before persist. When TRUE, margin constraint is exempt (m_a=m_b=0 allowed).
**Steps:** Create services/config-registry/src/main/java/com/gme/pay/configregistry/validation/SameCcyShortcircuitDetector.java @Component; Implement: boolean compute(String collectionCcy, String settleACcy, String settleBCcy, String payoutCcy) - returns true iff all four are equal after String.toUpperCase().trim(); Ensure null safety: any null input returns false; Wire into RuleService.createRule and RuleService.updateRule before margin validation
**Deliverable:** services/config-registry/src/main/java/com/gme/pay/configregistry/validation/SameCcyShortcircuitDetector.java
**Acceptance / logic checks:**
- compute('KRW','KRW','KRW','KRW') returns true
- compute('KRW','KRW','USD','KRW') returns false
- compute('USD','USD','USD','USD') returns true
- compute('krw','KRW','KRW','KRW') returns true (case normalization)
- compute(null,'KRW','KRW','KRW') returns false (null safety)
**Depends on:** 3.2-T07

### 3.2-T16 — Implement RuleMarginConstraintValidator @Component in services/config-registry  _(25 min)_
**Context:** Cross-border rules (isIsSameCcyShortcircuit=FALSE) require m_a + m_b >= 0.02 (2%). Same-ccy rules (isIsSameCcyShortcircuit=TRUE) require m_a = 0 AND m_b = 0. m_a >= 0 and m_b >= 0 always. All comparisons use BigDecimal to avoid floating-point drift. Throws MarginConstraintViolationException (canonical error from libs/lib-errors) with code RULE_MARGIN_BELOW_MIN on violation.
**Steps:** Create services/config-registry/src/main/java/com/gme/pay/configregistry/validation/RuleMarginConstraintValidator.java @Component; Implement validate(BigDecimal ma, BigDecimal mb, boolean isSameCcy): if isSameCcy assert ma.compareTo(ZERO)==0 AND mb.compareTo(ZERO)==0; else assert ma.add(mb).compareTo(new BigDecimal('0.02'))>=0; Throw MarginConstraintViolationException with message 'Cross-border rule requires m_a+m_b>=2%; got: '+sum and error code RULE_MARGIN_BELOW_MIN for cross-border violation; 'Same-ccy rule requires m_a=m_b=0' for same-ccy violation; Create libs/lib-errors/src/main/java/com/gme/pay/errors/MarginConstraintViolationException.java if not present, with String errorCode field
**Deliverable:** services/config-registry/src/main/java/com/gme/pay/configregistry/validation/RuleMarginConstraintValidator.java and libs/lib-errors/.../MarginConstraintViolationException.java
**Acceptance / logic checks:**
- m_a=0.01, m_b=0.01, cross-border: passes (sum=0.02)
- m_a=0.009, m_b=0.010, cross-border: throws MarginConstraintViolationException with code RULE_MARGIN_BELOW_MIN
- m_a=0.0000, m_b=0.0000, same-ccy=true: passes
- m_a=0.01, m_b=0.00, same-ccy=true: throws exception
- m_a=0.015, m_b=0.015, cross-border: passes (sum=0.03)
**Depends on:** 3.2-T15

### 3.2-T17 — Implement RuleService @Service in services/config-registry - createRule and activateRule  _(45 min)_
**Context:** RuleService orchestrates rule creation: (1) validate partner+scheme exist and active; (2) compute isIsSameCcyShortcircuit via SameCcyShortcircuitDetector; (3) enforce margin via RuleMarginConstraintValidator; (4) guard uniqueness via existsByPartnerIdAndSchemeIdAndDirectionAndStatus; (5) set effectiveFrom=NOW() if null; (6) persist via RuleRepository. activateRule sets status=ACTIVE and effectiveFrom=NOW() if null. Config changes apply to NEW transactions only; effectiveFrom is the guard.
**Steps:** Create services/config-registry/src/main/java/com/gme/pay/configregistry/service/RuleService.java @Service @Transactional; Inject RuleRepository, PartnerRepository, QrSchemeRepository, SameCcyShortcircuitDetector, RuleMarginConstraintValidator; Implement createRule(CreateRuleCommand cmd, String actorId): fetch partner+scheme (throw ResourceNotFoundException if absent or inactive), detect short-circuit, validate margins, check uniqueness (throw DuplicateRuleException if duplicate ACTIVE rule exists), set effectiveFrom, save; Implement activateRule(Long ruleId, String actorId): load rule (throw if not found), set status=ACTIVE, set effectiveFrom if null, save; Add DuplicateRuleException extends RuntimeException in libs/lib-errors
**Deliverable:** services/config-registry/src/main/java/com/gme/pay/configregistry/service/RuleService.java
**Acceptance / logic checks:**
- createRule with non-existent partnerId throws ResourceNotFoundException
- createRule with m_a=0.005 m_b=0.005 cross-border throws MarginConstraintViolationException
- createRule with duplicate active (partner,scheme,direction) throws DuplicateRuleException
- createRule with all-KRW ccys sets isIsSameCcyShortcircuit=true and margin check is skipped (m_a=m_b=0 accepted)
- activateRule sets status=ACTIVE; rule persisted with effectiveFrom set
**Depends on:** 3.2-T14, 3.2-T15, 3.2-T16

### 3.2-T18 — Implement RuleService @Service - updateRule with suspension-and-reinsert pattern  _(40 min)_
**Context:** Rule updates preserve history: old rule is set to status=SUSPENDED with updated_at=NOW(); a new Rule row is inserted with updated values, status=ACTIVE, effectiveFrom=NOW(). This ensures transactions in-flight use the rule snapshot at CommitTransaction time, not the new config. findActiveRuleFor must return only the new ACTIVE row after update. Both operations are in one @Transactional.
**Steps:** Add updateRule(Long ruleId, UpdateRuleCommand cmd, String actorId) to RuleService; Load existing rule by id; assert status=ACTIVE (throw if already SUSPENDED); Set existing rule status=SUSPENDED, updatedAt=NOW(), updatedBy=actorId; save (flush); Build new Rule entity from existing + overridden fields; set status=ACTIVE, effectiveFrom=NOW(), createdBy=actorId; Re-run SameCcyShortcircuitDetector and RuleMarginConstraintValidator on the new rule before save; Save new rule; return new rule id
**Deliverable:** Additional updateRule method in services/config-registry/src/main/java/com/gme/pay/configregistry/service/RuleService.java
**Acceptance / logic checks:**
- After updateRule, old rule row has status=SUSPENDED
- After updateRule, new rule row has status=ACTIVE and effectiveFrom=NOW()
- findAllByPartnerIdAndStatus(partnerId, 'ACTIVE') returns only the new row
- If new rule margins fail validation, neither suspension nor insertion is committed (transaction rollback)
- Updated m_a stored with scale 6 precision in new row
**Depends on:** 3.2-T17

### 3.2-T19 — Implement SchemeController @RestController in services/config-registry  _(40 min)_
**Context:** Admin REST API for qr_scheme CRUD. Base path: /v1/config/schemes. Endpoints: POST (create), GET /{schemeCode} (fetch one), GET (list all active), PATCH /{schemeCode} (update mutable fields). ROLE_ADMIN required on mutating operations (Spring Security @PreAuthorize). POST returns 201 + Location header. Duplicate schemeCode returns 409 Conflict. Invalid enum values return 400. Response DTOs from libs/lib-api-contracts.
**Steps:** Create services/config-registry/src/main/java/com/gme/pay/configregistry/controller/SchemeController.java @RestController @RequestMapping('/v1/config/schemes'); POST @PreAuthorize('hasRole(ADMIN)'): create scheme, return 201 ResponseEntity with Location=/v1/config/schemes/{schemeCode}; GET /{schemeCode}: findBySchemeCode, return 200 or 404 via ResponseStatusException; GET: findAllByIsActiveTrue, return 200 list; PATCH /{schemeCode} @PreAuthorize('hasRole(ADMIN)'): update mutable fields, return 200; Map DataIntegrityViolationException to 409 via @ExceptionHandler or global @ControllerAdvice from libs/lib-errors
**Deliverable:** services/config-registry/src/main/java/com/gme/pay/configregistry/controller/SchemeController.java
**Acceptance / logic checks:**
- POST with valid body returns 201 and Location header containing schemeCode
- POST with duplicate schemeCode returns 409 Conflict
- GET /v1/config/schemes/ZEROPAY returns 200 with payout_ccy='KRW'
- GET /v1/config/schemes/UNKNOWN returns 404
- PATCH setting status='INACTIVE' persists is_active=false
**Depends on:** 3.2-T09, 3.2-T13

### 3.2-T20 — Implement PartnerController @RestController in services/config-registry  _(40 min)_
**Context:** Admin REST API for partner management. Base path: /v1/config/partners. POST (onboard), GET /{partnerCode}, GET (list), PATCH /{partnerCode}. partnerType, collectionCcy, settleACcy are immutable after creation (PATCH must reject 400 if these fields appear in payload). rateQuoteTtlSeconds validated 60-1800 on create. ROLE_ADMIN on writes. PartnerValidationException mapped to 422. LOW_BALANCE fields required for OVERSEAS, forbidden for LOCAL.
**Steps:** Create services/config-registry/src/main/java/com/gme/pay/configregistry/controller/PartnerController.java @RestController @RequestMapping('/v1/config/partners'); POST: validate partnerType enum, ttl range 60-1800, OVERSEAS requires lowBalanceThresholdUsd; return 201; GET /{partnerCode}: 200 or 404; GET: list active partners; optional ?type=LOCAL|OVERSEAS query param; PATCH /{partnerCode}: reject 400 if partnerType/collectionCcy/settleACcy in payload; update remaining mutable fields; Map PartnerValidationException to 422; DuplicatePartnerCode to 409
**Deliverable:** services/config-registry/src/main/java/com/gme/pay/configregistry/controller/PartnerController.java
**Acceptance / logic checks:**
- POST with partnerType='INVALID' returns 400
- POST with rateQuoteTtlSeconds=59 returns 400
- POST OVERSEAS with null lowBalanceThresholdUsd returns 422
- PATCH attempting to change partnerType returns 400
- GET /v1/config/partners/GME_REMIT returns 200 with partner_type='LOCAL'
**Depends on:** 3.2-T10, 3.2-T13

### 3.2-T21 — Implement RuleController @RestController in services/config-registry  _(40 min)_
**Context:** Admin REST API for rule management. Base path: /v1/config/rules. POST (create), GET /{ruleId}, GET ?partnerId=&schemeCode= (list), PATCH /{ruleId} (update via suspension+reinsert), POST /{ruleId}/activate. ROLE_ADMIN on writes. MarginConstraintViolationException maps to 422 with body {error_code:'RULE_MARGIN_BELOW_MIN'}. DuplicateRuleException maps to 409.
**Steps:** Create services/config-registry/src/main/java/com/gme/pay/configregistry/controller/RuleController.java @RestController @RequestMapping('/v1/config/rules'); POST: call RuleService.createRule; return 201; catch MarginConstraintViolationException -> 422; catch DuplicateRuleException -> 409; GET /{ruleId}: 200 or 404; GET ?partnerId=: list rules for partner; PATCH /{ruleId}: call RuleService.updateRule; POST /{ruleId}/activate: call RuleService.activateRule; 200 on success
**Deliverable:** services/config-registry/src/main/java/com/gme/pay/configregistry/controller/RuleController.java
**Acceptance / logic checks:**
- POST with m_a=0.005 m_b=0.005 cross-border returns 422 with error_code='RULE_MARGIN_BELOW_MIN'
- POST with valid m_a=0.01 m_b=0.01 returns 201
- POST with duplicate (partner,scheme,direction) returns 409
- POST /{id}/activate returns 200 and status=ACTIVE in response
- GET ?partnerId=1 returns list of rules for that partner
**Depends on:** 3.2-T17, 3.2-T18, 3.2-T19

### 3.2-T22 — Implement TreasuryRateController @RestController and manual rate entry in services/config-registry  _(30 min)_
**Context:** Admin endpoint for Ops to enter FX rates manually (Phase 1). POST /v1/config/treasury-rates: body {ccy_pair:'usd_krw', rate:1380.0, effective_at:ISO8601}; rate must be > 0; source always set to MANUAL by endpoint. GET /v1/config/treasury-rates/{ccyPair}/current: returns latest rate (highest effective_at). Historical rows retained; no update/delete endpoint. ROLE_ADMIN on POST.
**Steps:** Create services/config-registry/src/main/java/com/gme/pay/configregistry/controller/TreasuryRateController.java @RestController @RequestMapping('/v1/config/treasury-rates'); POST @PreAuthorize('hasRole(ADMIN)'): validate rate>0 (400 if not), set source=MANUAL, save via TreasuryRateRepository, return 201; GET /{ccyPair}/current: call findTopByCcyPairOrderByEffectiveAtDesc; return 200 or 404 if no rate exists; GET /{ccyPair}/history: call findByCcyPairOrderByEffectiveAtDesc; return 200 list (for audit)
**Deliverable:** services/config-registry/src/main/java/com/gme/pay/configregistry/controller/TreasuryRateController.java
**Acceptance / logic checks:**
- POST with rate=0 returns 400
- POST with rate=1380.0 ccy_pair='usd_krw' returns 201
- GET /usd_krw/current returns the latest inserted rate (highest effective_at)
- GET /usd_zzz/current with no data returns 404
- All POSTs set source='MANUAL'
**Depends on:** 3.2-T12, 3.2-T14

### 3.2-T23 — Create Flyway V016__create_config_audit_log.sql and ConfigAuditListener in services/config-registry  _(40 min)_
**Context:** DAT-03 audit requirement: config changes log actor, timestamp, previous value, apply only to NEW transactions. config_audit_log table: id BIGINT PK, entity_type VARCHAR(50), entity_id BIGINT, action VARCHAR(20) CHECK(IN ('CREATE','UPDATE','ACTIVATE','DEACTIVATE','SUSPEND')), old_value JSONB nullable, new_value JSONB nullable, actor VARCHAR(120) NOT NULL, changed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), request_id VARCHAR(64). Append-only. Index on (entity_type, entity_id).
**Steps:** Create services/config-registry/src/main/resources/db/migration/V016__create_config_audit_log.sql with table definition above; Create services/config-registry/src/main/java/com/gme/pay/configregistry/audit/ConfigAuditListener.java as @Component; Implement @PreUpdate and @PrePersist lifecycle methods; capture old state via snapshot in @PostLoad; serialize to JSON via Jackson ObjectMapper; insert AuditLog row within same @Transactional; Annotate Rule, QrScheme, Partner entities with @EntityListeners({AuditingEntityListener.class, ConfigAuditListener.class}); Populate actor from Spring Security principal (SecurityContextHolder.getContext().getAuthentication())
**Deliverable:** services/config-registry/src/main/resources/db/migration/V016__create_config_audit_log.sql and services/config-registry/src/main/java/com/gme/pay/configregistry/audit/ConfigAuditListener.java
**Acceptance / logic checks:**
- PATCH on Rule m_a creates audit row with entity_type='rule', action='UPDATE', old_value and new_value populated
- POST (create) on QrScheme creates audit row with action='CREATE' and old_value=null
- actor field populated from Spring Security principal
- config_audit_log has no UPDATE or DELETE SQL in migration (append-only by convention)
- V016 applies after V015
**Depends on:** 3.2-T17, 3.2-T19, 3.2-T21

### 3.2-T24 — Create Flyway V017__create_outbox_table.sql and publish RuleActivatedEvent via transactional Outbox  _(40 min)_
**Context:** STACK.md Phase 1: transactional Outbox + EventPublisher interface; Kafka wired later. Domain events for config changes allow other services to react without polling. outbox table: id BIGINT PK, aggregate_type VARCHAR(50), aggregate_id VARCHAR(64), event_type VARCHAR(100), payload JSONB, created_at TIMESTAMPTZ DEFAULT NOW(), processed_at TIMESTAMPTZ nullable, status VARCHAR(20) DEFAULT 'PENDING'. RuleActivatedEvent fields: ruleId, partnerId, schemeId, direction, effectiveFrom. Event inserted within same @Transactional as rule activation.
**Steps:** Create services/config-registry/src/main/resources/db/migration/V017__create_outbox_table.sql with table above plus index on (status, created_at); Create libs/lib-events/src/main/java/com/gme/pay/events/config/RuleActivatedEvent.java as Java record: Long ruleId, Long partnerId, Long schemeId, String direction, Instant effectiveFrom; Define EventPublisher interface in libs/lib-events: void publish(Object event); Phase 1 impl: OutboxEventPublisher @Service inserts into outbox table within caller transaction; Wire EventPublisher.publish(new RuleActivatedEvent(...)) into RuleService.activateRule and RuleService.updateRule (for the new active rule); Verify: if rule activation rollbacks, outbox row is also rolled back (same transaction)
**Deliverable:** services/config-registry/src/main/resources/db/migration/V017__create_outbox_table.sql, libs/lib-events/src/main/java/com/gme/pay/events/config/RuleActivatedEvent.java, libs/lib-events/src/main/java/com/gme/pay/events/EventPublisher.java
**Acceptance / logic checks:**
- activateRule inserts both rule update AND outbox row in same transaction; rollback removes both
- Outbox row has status='PENDING' and event_type='RuleActivatedEvent' on insert
- RuleActivatedEvent payload contains ruleId, partnerId, schemeId, direction, effectiveFrom
- libs/lib-events compiles with event class
- V017 migration applies after V016
**Depends on:** 3.2-T17, 3.2-T23

### 3.2-T25 — Seed V018__seed_zeropay_gme_remit.sql - ZeroPay scheme, GME Remit partner, usd_krw treasury rate  _(20 min)_
**Context:** Baseline seed data for integration tests and local dev. ZeroPay: scheme_code='ZEROPAY', payout_ccy='KRW', supported_modes='BOTH', settlement_counterparty='KFTC', partner_b_quote_enabled=FALSE, partner_b_quote_deviation_pct=0.0100, status='ACTIVE', is_active=TRUE, created_by='SYSTEM'. scheme_country: (ZEROPAY, 'KR'). GME Remit: partner_code='GME_REMIT', partner_type='LOCAL', collection_ccy='KRW', settle_a_ccy='KRW', rate_quote_ttl_seconds=300, status='ACTIVE', created_by='SYSTEM'. treasury_rate: ccy_pair='usd_krw', rate=1380.00000000, source='MANUAL', effective_at=NOW(). Use ON CONFLICT DO NOTHING for idempotency.
**Steps:** Create services/config-registry/src/main/resources/db/migration/V018__seed_zeropay_gme_remit.sql; INSERT INTO qr_scheme for ZEROPAY using ON CONFLICT(scheme_code) DO NOTHING; INSERT INTO scheme_country for (ZEROPAY id, 'KR') using ON CONFLICT(scheme_id, country_code) DO NOTHING; INSERT INTO partner for GME_REMIT using ON CONFLICT(partner_code) DO NOTHING; INSERT INTO treasury_rate for usd_krw with rate=1380.00000000
**Deliverable:** services/config-registry/src/main/resources/db/migration/V018__seed_zeropay_gme_remit.sql
**Acceptance / logic checks:**
- After migration SELECT scheme_code FROM qr_scheme WHERE scheme_code='ZEROPAY' returns 1 row
- SELECT country_code FROM scheme_country returns 'KR' for ZeroPay
- SELECT partner_type FROM partner WHERE partner_code='GME_REMIT' returns 'LOCAL'
- SELECT rate FROM treasury_rate WHERE ccy_pair='usd_krw' ORDER BY effective_at DESC LIMIT 1 returns 1380.00000000
- Re-running migration is idempotent (ON CONFLICT DO NOTHING)
**Depends on:** 3.2-T06, 3.2-T03, 3.2-T02

### 3.2-T26 — Seed V019__seed_gme_remit_domestic_rule.sql - GME Remit / ZeroPay / DOMESTIC rule  _(20 min)_
**Context:** Baseline domestic rule: GME_REMIT + ZEROPAY + DOMESTIC. All KRW so is_same_ccy_shortcircuit=TRUE; m_a=0 m_b=0 (same-ccy exempt from 2% constraint); service_charge_amount=500 KRW flat; rate sources IDENTITY (cost_rate_coll=1.0, cost_rate_pay=1.0); status=ACTIVE; effective_from=NOW(). This rule is the first operational rule and baseline for integration tests.
**Steps:** Create services/config-registry/src/main/resources/db/migration/V019__seed_gme_remit_domestic_rule.sql; Use subquery to resolve partner_id and scheme_id by code; INSERT INTO rule: direction='DOMESTIC', collection_ccy='KRW', settle_a_ccy='KRW', settle_b_ccy='KRW', payout_ccy='KRW', rate_coll_source='IDENTITY', rate_pay_source='IDENTITY', m_a=0.000000, m_b=0.000000, service_charge_amount=500.0000, service_charge_ccy='KRW', is_same_ccy_shortcircuit=TRUE, status='ACTIVE', effective_from=NOW(), created_by='SYSTEM'; Use ON CONFLICT(partner_id, scheme_id, direction) DO NOTHING for idempotency
**Deliverable:** services/config-registry/src/main/resources/db/migration/V019__seed_gme_remit_domestic_rule.sql
**Acceptance / logic checks:**
- SELECT is_same_ccy_shortcircuit FROM rule WHERE direction='DOMESTIC' returns TRUE
- SELECT m_a+m_b FROM rule WHERE direction='DOMESTIC' returns 0.000000
- service_charge_amount=500.0000 and service_charge_ccy='KRW'
- DB CHECK chk_rule_margin not violated (is_same_ccy_shortcircuit=TRUE exempts it)
- Migration idempotent (ON CONFLICT DO NOTHING)
**Depends on:** 3.2-T04, 3.2-T25

### 3.2-T27 — Unit tests: RuleMarginConstraintValidator - boundary and same-ccy vectors (JUnit 5, no Spring)  _(25 min)_
**Context:** Pure-unit tests for RuleMarginConstraintValidator. No Spring context, no DB. All BigDecimal inputs to avoid floating-point drift. Tests must cover the exact boundary (0.02), just below (0.0199), same-ccy exemption, and both individual margin fields.
**Steps:** Create services/config-registry/src/test/java/com/gme/pay/configregistry/validation/RuleMarginConstraintValidatorTest.java; Instantiate RuleMarginConstraintValidator directly (new, no @SpringBootTest); Test vectors: (1) ma=0.01 mb=0.01 cross-border -> passes; (2) ma=0.009 mb=0.010 cross-border -> throws RULE_MARGIN_BELOW_MIN; (3) ma=0.0001 mb=0.0198 cross-border -> throws (sum=0.0199); (4) ma=0.0 mb=0.0 same-ccy=true -> passes; (5) ma=0.01 mb=0.00 same-ccy=true -> throws; (6) ma=0.015 mb=0.015 cross-border -> passes (sum=0.03); (7) ma=0.02 mb=0.00 cross-border -> passes (exactly at boundary); Use assertThrows(MarginConstraintViolationException.class, ...) for failing cases; assertDoesNotThrow for passing cases; Verify exception error code equals 'RULE_MARGIN_BELOW_MIN' in failing cross-border cases
**Deliverable:** services/config-registry/src/test/java/com/gme/pay/configregistry/validation/RuleMarginConstraintValidatorTest.java
**Acceptance / logic checks:**
- Vector 1 passes (sum=0.02 exactly at boundary)
- Vector 2 throws MarginConstraintViolationException with code RULE_MARGIN_BELOW_MIN
- Vector 3 throws (0.0001+0.0198=0.0199 < 0.02)
- Vector 4 passes (same-ccy exempt)
- Vector 7 passes (exactly 0.02 is allowed)
- Test class has zero Spring annotations (pure unit test)
**Depends on:** 3.2-T16

### 3.2-T28 — Unit tests: SameCcyShortcircuitDetector - all four positions and case normalization (JUnit 5)  _(20 min)_
**Context:** Pure-unit tests for SameCcyShortcircuitDetector.compute(). Seven vectors covering all four ccy positions, case normalization, null safety, and USD same-ccy case. No Spring context.
**Steps:** Create services/config-registry/src/test/java/com/gme/pay/configregistry/validation/SameCcyShortcircuitDetectorTest.java; Instantiate SameCcyShortcircuitDetector directly; Test: (1) all KRW -> true; (2) collectionCcy=USD rest KRW -> false; (3) settleACcy=USD rest KRW -> false; (4) settleBCcy=USD rest KRW -> false; (5) payoutCcy=USD rest KRW -> false; (6) all lowercase 'krw' -> true (case normalization); (7) all USD -> true; (8) null collectionCcy -> false (null safety); Use assertEquals for all assertions
**Deliverable:** services/config-registry/src/test/java/com/gme/pay/configregistry/validation/SameCcyShortcircuitDetectorTest.java
**Acceptance / logic checks:**
- All 8 test vectors produce expected boolean outcome
- Case normalization test (6) passes (lowercase input returns true)
- Null safety test (8) does not throw NullPointerException
- All-USD test (7) returns true
- Test class has no Spring annotations
**Depends on:** 3.2-T15

### 3.2-T29 — Unit tests: RuleService createRule - uniqueness guard, validation, and short-circuit (JUnit 5, Mockito)  _(35 min)_
**Context:** Mockito unit tests for RuleService.createRule. Mock RuleRepository, PartnerRepository, QrSchemeRepository, SameCcyShortcircuitDetector, RuleMarginConstraintValidator. Verify: (1) success path saves rule and calls validator; (2) non-existent partner throws ResourceNotFoundException and save is not called; (3) duplicate active rule throws DuplicateRuleException and save is not called; (4) all-KRW sets isIsSameCcyShortcircuit=true on persisted entity; (5) margin validation failure propagates through createRule.
**Steps:** Create services/config-registry/src/test/java/com/gme/pay/configregistry/service/RuleServiceTest.java; Use @ExtendWith(MockitoExtension.class) with @Mock and @InjectMocks; Test (1): stub repos to return valid partner+scheme, existsBy=false; assert save() called once; Test (2): stub PartnerRepository to return empty; assertThrows ResourceNotFoundException; verify save never called; Test (3): stub existsBy=true; assertThrows DuplicateRuleException; verify save never called; Test (4): stub detector to return true; assert saved rule.isIsSameCcyShortcircuit==true; Test (5): stub detector=false; stub validator to throw; assertThrows propagated; save not called
**Deliverable:** services/config-registry/src/test/java/com/gme/pay/configregistry/service/RuleServiceTest.java
**Acceptance / logic checks:**
- Test (1) verifies RuleRepository.save called exactly once on success path
- Test (2) asserts save never called on ResourceNotFoundException
- Test (3) asserts DuplicateRuleException with all three identifying fields in message
- Test (4) asserts persisted entity has isIsSameCcyShortcircuit=true
- Test (5) asserts MarginConstraintViolationException propagates and save not called
**Depends on:** 3.2-T17, 3.2-T27, 3.2-T28

### 3.2-T30 — @DataJpaTest integration tests: QrScheme and Partner DB constraints using Testcontainers postgres:16  _(40 min)_
**Context:** @DataJpaTest slice tests with Testcontainers postgres:16 to validate Flyway migrations V010-V012 and JPA entity mappings. Tests verify real PostgreSQL constraint enforcement (not H2 emulation). Run via @AutoConfigureTestDatabase(replace=NONE) and @DynamicPropertySource.
**Steps:** Create services/config-registry/src/test/java/com/gme/pay/configregistry/entity/QrSchemeRepositoryIT.java; Annotate @DataJpaTest @AutoConfigureTestDatabase(replace=NONE) @Testcontainers; Declare @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>('postgres:16'); Wire url/user/pass via @DynamicPropertySource; Test: save ZEROPAY scheme; findBySchemeCode('ZEROPAY') returns it; Test: duplicate schemeCode insert throws DataIntegrityViolationException; Test: findAllByIsActiveTrue returns only active records; Create PartnerRepositoryIT.java with same container setup: duplicate partnerCode throws; findAllByPartnerTypeAndIsActiveTrue(OVERSEAS) filters correctly
**Deliverable:** services/config-registry/src/test/java/com/gme/pay/configregistry/entity/QrSchemeRepositoryIT.java and PartnerRepositoryIT.java
**Acceptance / logic checks:**
- Tests run against Testcontainers postgres:16 (not H2)
- Duplicate schemeCode insert throws DataIntegrityViolationException
- findAllByIsActiveTrue returns only is_active=true records
- findAllByPartnerTypeAndIsActiveTrue(OVERSEAS) returns only OVERSEAS active partners
- Flyway migrations V010-V012 applied automatically before tests via spring.flyway.enabled=true
**Depends on:** 3.2-T09, 3.2-T10, 3.2-T13

### 3.2-T31 — @DataJpaTest integration tests: Rule and TreasuryRate DB constraints using Testcontainers postgres:16  _(40 min)_
**Context:** Testcontainers postgres:16 slice tests for Rule (V013) and TreasuryRate (V015) entities. Validates: DB-level UNIQUE(partner,scheme,direction), chk_rule_margin CHECK, direction CHECK, treasury rate > 0 CHECK, and findTopByCcyPairOrderByEffectiveAtDesc returns highest effective_at row.
**Steps:** Create services/config-registry/src/test/java/com/gme/pay/configregistry/entity/RuleRepositoryIT.java with @DataJpaTest + Testcontainers postgres:16; Test: insert valid DOMESTIC rule; findByPartnerIdAndSchemeIdAndDirection returns it; Test: duplicate (partner,scheme,direction) insert throws DataIntegrityViolationException; Test: direction='BILATERAL' insert throws ConstraintViolationException (DB CHECK); Test: cross-border m_a=0.005 m_b=0.005 insert throws (chk_rule_margin DB CHECK); Create TreasuryRateRepositoryIT.java: insert two usd_krw rows with different effective_at; assert findTop returns rate with higher effective_at; assert rate=0 insert throws
**Deliverable:** services/config-registry/src/test/java/com/gme/pay/configregistry/entity/RuleRepositoryIT.java and TreasuryRateRepositoryIT.java
**Acceptance / logic checks:**
- UNIQUE(partner_id,scheme_id,direction) test throws DataIntegrityViolationException on duplicate
- direction='BILATERAL' insert rejected by DB CHECK
- chk_rule_margin DB CHECK rejects m_a=0.005 m_b=0.005 cross-border
- findTopByCcyPairOrderByEffectiveAtDesc returns row with highest effective_at
- rate=0 treasury_rate insert throws constraint exception
**Depends on:** 3.2-T11, 3.2-T12, 3.2-T14, 3.2-T30

### 3.2-T32 — @WebMvcTest for RuleController - 422, 409, 201 HTTP contract (MockMvc, @MockBean RuleService)  _(35 min)_
**Context:** @WebMvcTest slice for RuleController validating HTTP status codes and response body contracts. Mock RuleService with @MockBean. No real DB or Spring Security enforcement needed (use @WithMockUser). Validates that exception-to-HTTP mapping in @ControllerAdvice (libs/lib-errors) works correctly: MarginConstraintViolationException -> 422 with error_code='RULE_MARGIN_BELOW_MIN'; DuplicateRuleException -> 409.
**Steps:** Create services/config-registry/src/test/java/com/gme/pay/configregistry/controller/RuleControllerTest.java; Annotate @WebMvcTest(RuleController.class); @MockBean RuleService ruleService; Test POST /v1/config/rules margin violation: stub ruleService.createRule to throw MarginConstraintViolationException('RULE_MARGIN_BELOW_MIN'); assert MockMvc returns 422 and JSON body contains error_code='RULE_MARGIN_BELOW_MIN'; Test POST duplicate: stub throws DuplicateRuleException; assert 409; Test POST success: stub returns Rule DTO; assert 201; Test POST /{id}/activate success: stub activateRule; assert 200; Use @WithMockUser(roles='ADMIN') on mutating test methods
**Deliverable:** services/config-registry/src/test/java/com/gme/pay/configregistry/controller/RuleControllerTest.java
**Acceptance / logic checks:**
- MarginConstraintViolationException mapped to 422 with error_code='RULE_MARGIN_BELOW_MIN' in JSON body
- DuplicateRuleException mapped to 409
- Valid POST returns 201
- Activate endpoint returns 200
- Tests use MockMvc (not Testcontainers; WebMvcTest slice)
**Depends on:** 3.2-T21, 3.2-T27

### 3.2-T33 — Define RuleQueryService interface and DTOs in libs/lib-api-contracts for cross-service use  _(30 min)_
**Context:** Rate FX Engine, Payment Executor, and Smart Router need to look up active rules, treasury rates, and partner config without coupling to svc-config-registry internals. Define a RuleQueryService Java interface in libs/lib-api-contracts with: Optional<RuleDto> findActiveRule(Long partnerId, Long schemeId, Direction direction); Optional<TreasuryRateDto> findLatestTreasuryRate(String ccyPair); Optional<PartnerDto> findPartnerByCode(String partnerCode). Phase 1 implementation uses REST call + Redis cache (60s TTL, matching rate-quote TTL). Interface isolates transport from callers.
**Steps:** Create libs/lib-api-contracts/src/main/java/com/gme/pay/contracts/config/RuleQueryService.java as Java interface; Create RuleDto.java record: Long id, Long partnerId, Long schemeId, Direction direction, BigDecimal ma, BigDecimal mb, boolean isSameCcyShortcircuit, BigDecimal serviceChargeAmount, String serviceChargeCcy, String status, String collectionCcy, String settleACcy, String settleBCcy, String payoutCcy; Create TreasuryRateDto.java record: String ccyPair, BigDecimal rate, Instant effectiveAt; Create PartnerDto.java record: Long id, String partnerCode, PartnerType partnerType, String collectionCcy, String settleACcy, Integer rateQuoteTtlSeconds
**Deliverable:** libs/lib-api-contracts/src/main/java/com/gme/pay/contracts/config/RuleQueryService.java, RuleDto.java, TreasuryRateDto.java, PartnerDto.java
**Acceptance / logic checks:**
- RuleDto includes ma, mb, isSameCcyShortcircuit, all four ccy fields, and status
- TreasuryRateDto.rate is BigDecimal (not double)
- PartnerDto.partnerType uses PartnerType enum from libs/lib-domain
- libs/lib-api-contracts Gradle build succeeds
- Interface Javadoc states Phase 1=REST+Redis-cached, Phase 2=Redis-only
**Depends on:** 3.2-T07, 3.2-T08, 3.2-T11, 3.2-T12

### 3.2-T34 — Implement Redis-cached RuleQueryServiceImpl in services/config-registry (Spring Cache + Redis TTL 60s)  _(40 min)_
**Context:** Phase 1 implementation of RuleQueryService that calls local repository methods (same service, no inter-service REST yet) with Redis caching at 60s TTL. @Cacheable(cacheNames='rule-cache', key='#partnerId+":"+#schemeId+":"+#direction') on findActiveRule. @Cacheable(cacheNames='rate-cache', key='#ccyPair') on findLatestTreasuryRate. @CacheEvict on RuleService.activateRule, updateRule. On TreasuryRate POST evict rate-cache for the ccyPair. spring.cache.type=redis in application.yml with TTL=60s for rule-cache.
**Steps:** Create services/config-registry/src/main/java/com/gme/pay/configregistry/service/RuleQueryServiceImpl.java implementing RuleQueryService @Service; Inject RuleRepository, TreasuryRateRepository, PartnerRepository; Annotate findActiveRule with @Cacheable(cacheNames='rule-cache', key='#partnerId+":"+#schemeId+":"+#direction.name()'); Annotate findLatestTreasuryRate with @Cacheable(cacheNames='rate-cache', key='#ccyPair'); Configure in application.yml: spring.cache.type=redis; rule-cache TTL=60s; rate-cache TTL=60s; Add @CacheEvict(cacheNames='rule-cache', key=...) to RuleService.activateRule and updateRule
**Deliverable:** services/config-registry/src/main/java/com/gme/pay/configregistry/service/RuleQueryServiceImpl.java and updated application.yml for cache config
**Acceptance / logic checks:**
- findActiveRule annotated @Cacheable with TTL 60s on rule-cache
- findLatestTreasuryRate annotated @Cacheable on rate-cache
- RuleService.activateRule calls @CacheEvict for the affected rule cache key
- application.yml sets spring.cache.type=redis with TTL configuration
- Testcontainers Redis integration: findActiveRule called twice for same args; second call served from cache (verify with SpyBean on repository showing 1 DB call)
**Depends on:** 3.2-T17, 3.2-T22, 3.2-T33

### 3.2-T35 — Full @SpringBootTest lifecycle integration test: create-activate rule flow in services/config-registry  _(55 min)_
**Context:** End-to-end slice test using @SpringBootTest + Testcontainers postgres:16 and redis:7. Tests the full Spring context of svc-config-registry. Flow: (1) seed ZEROPAY + GME_REMIT via REST; (2) create DOMESTIC rule DRAFT; (3) activate rule via POST /{id}/activate; (4) verify DB state; (5) verify config_audit_log row; (6) verify outbox row with RuleActivatedEvent; (7) verify Redis cache evicted post-activation.
**Steps:** Create services/config-registry/src/test/java/com/gme/pay/configregistry/integration/RuleLifecycleIT.java; Annotate @SpringBootTest(webEnvironment=RANDOM_PORT) @Testcontainers; postgres:16 and redis:7 containers via @DynamicPropertySource; Use TestRestTemplate to POST /v1/config/schemes (ZEROPAY) and POST /v1/config/partners (GME_REMIT) and POST /v1/config/rules (DOMESTIC all-KRW m_a=0 m_b=0); POST /v1/config/rules/{id}/activate; assert HTTP 200; Assert via JdbcTemplate: rule status='ACTIVE', config_audit_log has activation row, outbox has RuleActivatedEvent row with status='PENDING'; Assert Redis cache key absent after eviction (verify via RedisTemplate)
**Deliverable:** services/config-registry/src/test/java/com/gme/pay/configregistry/integration/RuleLifecycleIT.java
**Acceptance / logic checks:**
- Full create-activate flow: 201 on create then 200 on activate
- DB rule.status='ACTIVE' after activation
- config_audit_log row with entity_type='rule' and action='ACTIVATE' present
- Outbox row event_type='RuleActivatedEvent' with status='PENDING' present in same DB transaction
- Redis cache for rule key is evicted after activation (RedisTemplate check returns null)
**Depends on:** 3.2-T21, 3.2-T23, 3.2-T24, 3.2-T34

### 3.2-T36 — Add settlement_rounding_mode column to partner table (Flyway)  _(30 min)_
**Context:** Partners may book settlement liabilities under different rounding rules (e.g. round-DOWN to 2dp). config-registry owns the partner table; add a settlement_rounding_mode column (VARCHAR, e.g. HALF_UP) defaulting to HALF_UP.
**Steps:** Add Flyway migration Vnnn__partner_rounding_mode.sql adding settlement_rounding_mode VARCHAR(12) NOT NULL DEFAULT 'HALF_UP'; Backfill existing rows to HALF_UP
**Deliverable:** Flyway migration adding partner.settlement_rounding_mode
**Acceptance / logic checks:**
- column exists, NOT NULL, default HALF_UP
- existing partners backfilled to HALF_UP
**Depends on:** 3.2

### 3.2-T37 — Add settlementRoundingMode to Partner entity/DTO  _(30 min)_
**Context:** Expose the per-partner rounding mode (java.math.RoundingMode) on the Partner model and the partner DTO returned by the API. Default HALF_UP when null.
**Steps:** Add settlementRoundingMode to Partner entity + DTO; Default to HALF_UP if absent
**Deliverable:** Partner entity/DTO carrying settlementRoundingMode
**Acceptance / logic checks:**
- round-trips via API
- null defaults to HALF_UP

### 3.2-T38 — Validate settlement_rounding_mode against allowed enum  _(25 min)_
**Context:** On partner create/update, reject any value not in {HALF_UP,HALF_DOWN,HALF_EVEN,DOWN,UP,CEILING,FLOOR} with VALIDATION_ERROR.
**Steps:** Add enum validation in the partner create/update handler; Return ApiError VALIDATION_ERROR on bad value
**Deliverable:** Validation rule for settlement_rounding_mode
**Acceptance / logic checks:**
- valid modes accepted
- invalid mode -> 422 VALIDATION_ERROR

### 3.2-T39 — Expose settlement_rounding_mode in GET /v1/partners/{id}  _(20 min)_
**Context:** Other services (payment-executor) read the partner's rounding mode via config-registry's API, never its DB.
**Steps:** Include settlementRoundingMode in the partner response payload; Add a contract test
**Deliverable:** Partner API response includes settlementRoundingMode
**Acceptance / logic checks:**
- field present in response
- contract test passes


## WBS 3.4 — Treasury/FX rate tables (effective-dated)
### 3.4-T01 — Flyway migration V4__create_treasury_rate_table.sql in services/rate-fx  _(25 min)_
**Context:** WBS 3.4 delivers the effective-dated treasury rate store. The table treasury_rate holds usd_{ccy} rates (e.g. usd_krw = 1380.00000000 means 1 USD = 1380 KRW). Schema per RATE-04/DAT-03 section 10.3: id BIGINT PK auto-increment, ccy_pair VARCHAR(10) NOT NULL, rate DECIMAL(20,8) NOT NULL CHECK(rate>0), source VARCHAR(10) NOT NULL CHECK(source IN ('LIVE','MANUAL')), effective_at TIMESTAMPTZ NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL, created_by VARCHAR(120), updated_by VARCHAR(120). Rows are NEVER updated or deleted -- each new rate is a new INSERT (immutable append-only history). Flyway lives in services/rate-fx/src/main/resources/db/migration/.
**Steps:** Create services/rate-fx/src/main/resources/db/migration/V4__create_treasury_rate_table.sql; Write CREATE TABLE treasury_rate with all 9 columns and inline CHECK constraints; Add idx_treasury_rate_ccy_pair ON treasury_rate(ccy_pair); Add idx_treasury_rate_ccy_effective ON treasury_rate(ccy_pair, effective_at DESC) -- supports canonical latest-rate query; Confirm no UPDATE or DELETE DML in the migration
**Deliverable:** services/rate-fx/src/main/resources/db/migration/V4__create_treasury_rate_table.sql
**Acceptance / logic checks:**
- All 9 columns present with DECIMAL(20,8) for rate and TIMESTAMPTZ for effective_at
- CHECK(rate>0) rejects INSERT of rate=0 with constraint violation
- CHECK(source IN ('LIVE','MANUAL')) rejects source='FEED'
- Both indexes appear in pg_indexes after migration
- File contains no DROP, TRUNCATE, or UPDATE statements

### 3.4-T02 — Flyway migration V5__treasury_rate_no_overlap_unique.sql -- unique (ccy_pair, effective_at)  _(15 min)_
**Context:** Because the latest-rate query uses ORDER BY effective_at DESC LIMIT 1, two rows for the same ccy_pair with the same effective_at timestamp create non-deterministic rate selection. Add a UNIQUE constraint on (ccy_pair, effective_at) at DB level. This enforces the no-overlapping-effective-timestamp invariant independently of application code. File: services/rate-fx/src/main/resources/db/migration/V5__treasury_rate_no_overlap_unique.sql.
**Steps:** Create services/rate-fx/src/main/resources/db/migration/V5__treasury_rate_no_overlap_unique.sql; Write: ALTER TABLE treasury_rate ADD CONSTRAINT uq_treasury_rate_ccy_effective UNIQUE (ccy_pair, effective_at); Verify the migration does not conflict with any data inserted in V4 (V4 inserts no data rows)
**Deliverable:** services/rate-fx/src/main/resources/db/migration/V5__treasury_rate_no_overlap_unique.sql
**Acceptance / logic checks:**
- Attempting INSERT of two rows with same (ccy_pair, effective_at) raises SQLSTATE 23505
- Two rows for same ccy_pair with different effective_at are both accepted
- The constraint name uq_treasury_rate_ccy_effective appears in pg_constraint
- Migration applies without error on a fresh V4 schema
**Depends on:** 3.4-T01

### 3.4-T03 — Flyway migration V6__treasury_rate_seed_identity.sql -- seed usd_usd = 1.0  _(15 min)_
**Context:** The usd_usd identity rate (1 USD = 1 USD) must always be present so the rate engine can serve any ccy_pair lookup without special-casing. Seed with: ccy_pair='usd_usd', rate=1.00000000, source='MANUAL', effective_at='1970-01-01 00:00:00+00', created_by='SYSTEM_SEED', updated_by='SYSTEM_SEED'. Use INSERT ... ON CONFLICT DO NOTHING so the migration is idempotent. The rate engine will still short-circuit usd_usd to BigDecimal.ONE without a DB hit, but the row ensures the table is never empty.
**Steps:** Create services/rate-fx/src/main/resources/db/migration/V6__treasury_rate_seed_identity.sql; Write INSERT INTO treasury_rate (ccy_pair, rate, source, effective_at, created_at, updated_at, created_by, updated_by) VALUES ('usd_usd', 1.00000000, 'MANUAL', '1970-01-01 00:00:00+00', NOW(), NOW(), 'SYSTEM_SEED', 'SYSTEM_SEED') ON CONFLICT ON CONSTRAINT uq_treasury_rate_ccy_effective DO NOTHING; Verify migration runs cleanly after V4 and V5
**Deliverable:** services/rate-fx/src/main/resources/db/migration/V6__treasury_rate_seed_identity.sql
**Acceptance / logic checks:**
- After migration: SELECT count(*) FROM treasury_rate WHERE ccy_pair='usd_usd' = 1
- rate = 1.00000000 exactly (8 decimal places)
- Re-executing the INSERT (idempotency) does not throw duplicate-key error
- source = 'MANUAL' and created_by = 'SYSTEM_SEED'
**Depends on:** 3.4-T02

### 3.4-T04 — Define TreasuryRate JPA entity in lib-persistence  _(25 min)_
**Context:** lib-persistence is the shared Gradle module for JPA/migration utilities (lib-persistence/build.gradle declares spring-boot-starter-data-jpa). The TreasuryRate entity maps to treasury_rate. Fields: Long id, String ccyPair, BigDecimal rate, String source, Instant effectiveAt, Instant createdAt, Instant updatedAt, String createdBy, String updatedBy. Use @Column(name='rate', precision=20, scale=8) for the BigDecimal. Package: com.gme.pay.persistence.entity. Annotate @Entity, @Table(name='treasury_rate'), @Id, @GeneratedValue(strategy=IDENTITY). This entity is pure mapping -- no business logic.
**Steps:** Create lib-persistence/src/main/java/com/gme/pay/persistence/entity/TreasuryRate.java; Annotate class with @Entity and @Table(name="treasury_rate"); Map all 9 fields: use @Column(name="ccy_pair") etc for camelCase to snake_case; Set @Column(precision=20, scale=8) on BigDecimal rate field; Add @GeneratedValue(strategy=GenerationType.IDENTITY) on id; confirm no sequence strategy
**Deliverable:** lib-persistence/src/main/java/com/gme/pay/persistence/entity/TreasuryRate.java
**Acceptance / logic checks:**
- All 9 columns mapped with matching snake_case column names
- BigDecimal rate has precision=20, scale=8 in @Column
- @GeneratedValue uses IDENTITY not SEQUENCE
- Class compiles inside lib-persistence without requiring any service module
- No business logic or repository methods in this class
**Depends on:** 3.4-T01

### 3.4-T05 — Create TreasuryRateRepository (Spring Data JPA) in services/rate-fx  _(20 min)_
**Context:** The rate-fx service (services/rate-fx) owns all rate engine logic. TreasuryRateRepository extends JpaRepository<TreasuryRate, Long>. Required derived query methods: (1) Optional<TreasuryRate> findTopByCcyPairOrderByEffectiveAtDesc(String ccyPair) -- resolves to WHERE ccy_pair=? ORDER BY effective_at DESC LIMIT 1; (2) Page<TreasuryRate> findByCcyPairOrderByEffectiveAtDesc(String ccyPair, Pageable pageable) -- paginated history. Package: com.gme.pay.ratefx.repository. rate-fx/build.gradle must declare implementation project(':lib-persistence').
**Steps:** Create services/rate-fx/src/main/java/com/gme/pay/ratefx/repository/TreasuryRateRepository.java; Extend JpaRepository<TreasuryRate, Long>; Declare findTopByCcyPairOrderByEffectiveAtDesc(String ccyPair) returning Optional<TreasuryRate>; Declare findByCcyPairOrderByEffectiveAtDesc(String ccyPair, Pageable pageable) returning Page<TreasuryRate>; Confirm rate-fx/build.gradle includes implementation project(':lib-persistence')
**Deliverable:** services/rate-fx/src/main/java/com/gme/pay/ratefx/repository/TreasuryRateRepository.java
**Acceptance / logic checks:**
- Interface extends JpaRepository<TreasuryRate, Long>
- findTopByCcyPairOrderByEffectiveAtDesc returns Optional<TreasuryRate> (not List, not nullable)
- Spring Data derives the correct LIMIT 1 SQL without @Query needed
- Module compiles without missing-dependency errors
- No @Query annotations required -- method names drive query derivation
**Depends on:** 3.4-T04

### 3.4-T06 — Implement TreasuryRateService.getEffectiveRate() @Service in services/rate-fx  _(30 min)_
**Context:** TreasuryRateService is the @Service that the rate engine calls. Method: BigDecimal getEffectiveRate(String ccyPair, Instant asOf). Logic: if ccyPair equals 'usd_usd' return BigDecimal.ONE immediately (IDENTITY short-circuit, no DB hit). Otherwise call repository.findTopByCcyPairOrderByEffectiveAtDesc(ccyPair). If Optional is empty throw RateNotFoundException (lib-errors) with code RATE_NOT_FOUND. The asOf parameter is accepted for Phase 2 point-in-time lookup but in Phase 1 always returns the latest row. Package: com.gme.pay.ratefx.service.
**Steps:** Create services/rate-fx/src/main/java/com/gme/pay/ratefx/service/TreasuryRateService.java; Annotate @Service; Inject TreasuryRateRepository via constructor; Implement getEffectiveRate: return BigDecimal.ONE for 'usd_usd'; otherwise findTop... unwrap Optional or throw RateNotFoundException(code=RATE_NOT_FOUND); Add getHistory(String ccyPair, Pageable pageable) delegating to repository paged query for later use by admin controller
**Deliverable:** services/rate-fx/src/main/java/com/gme/pay/ratefx/service/TreasuryRateService.java
**Acceptance / logic checks:**
- getEffectiveRate('usd_usd', any) returns BigDecimal.ONE without any repository call
- getEffectiveRate('usd_krw', any) returns the BigDecimal from the row with highest effective_at for ccy_pair=usd_krw
- getEffectiveRate for unknown pair throws RateNotFoundException with code RATE_NOT_FOUND
- Service is stateless -- safe for concurrent calls without synchronisation
- No static fields holding rate values
**Depends on:** 3.4-T05

### 3.4-T07 — Implement TreasuryRateService.upsertRate() -- create new effective-dated row  _(35 min)_
**Context:** Rates are immutable: each update INSERTs a new row. Method: TreasuryRate upsertRate(String ccyPair, BigDecimal rate, String source, Instant effectiveAt, String actor, boolean confirmDeviation). Validates: rate > 0 (throw InvalidRateException code RATE_INVALID_VALUE), source in {LIVE,MANUAL} (RATE_INVALID_SOURCE), effectiveAt not more than 60s in the past (RATE_BACKDATE_REJECTED). Saves via repository.save(). Does NOT mutate existing rows. Calls auditLogService.log('CREATE', 'treasury_rate', saved.id, null, json(saved), actor) after save (3.4-T11).
**Steps:** Add upsertRate method to TreasuryRateService; Validate rate>0; source in {LIVE,MANUAL}; effectiveAt >= Instant.now().minusSeconds(60) -- throw typed exceptions with codes; Build a new TreasuryRate entity with all fields; call repository.save(); Call auditLogService.log with action=CREATE, before=null, after=saved entity JSON; Return saved entity
**Deliverable:** Updated services/rate-fx/src/main/java/com/gme/pay/ratefx/service/TreasuryRateService.java with upsertRate()
**Acceptance / logic checks:**
- rate=0 throws InvalidRateException code RATE_INVALID_VALUE
- source='FEED' throws InvalidRateException code RATE_INVALID_SOURCE
- effectiveAt 120s in the past throws InvalidRateException code RATE_BACKDATE_REJECTED
- Inserting a new rate creates a new row -- existing rows for same ccy_pair are untouched
- After insert, getEffectiveRate returns the new rate if its effective_at is >= all prior rows for the pair
**Depends on:** 3.4-T06, 3.4-T11

### 3.4-T08 — Add deviation guard to upsertRate() -- warn on >10% rate change  _(30 min)_
**Context:** Per PRD-07 section 7.3: if the new rate deviates more than +-10% from the previous stored rate for the same ccy_pair, throw RateDeviationWarningException(code=RATE_DEVIATION_WARNING, previousRate, percentageChange). The caller (admin controller) surfaces this as a 422 confirmation prompt. If the operator re-calls with confirmDeviation=true the check is skipped. This guards against typos (e.g. entering 13800 instead of 1380). percentageChange = abs(newRate - prevRate) / prevRate * 100. If no previous row exists for the pair skip the check entirely.
**Steps:** Retrieve the latest existing row for ccyPair via repository (may be absent); If present: compute percentageChange using BigDecimal arithmetic (no double); If percentageChange > 10.0 AND confirmDeviation==false: throw RateDeviationWarningException with previousRate and percentageChange fields; If confirmDeviation==true OR no previous row: proceed normally to insert; Verify BigDecimal.divide uses HALF_UP, scale=4 for percentage computation
**Deliverable:** Updated upsertRate() in services/rate-fx/src/main/java/com/gme/pay/ratefx/service/TreasuryRateService.java
**Acceptance / logic checks:**
- Rate 1485.00 vs prev 1380.00 (7.61% change) is accepted without warning
- Rate 1520.00 vs prev 1380.00 (10.14% change, confirmDeviation=false) throws RateDeviationWarningException with percentageChange >=10.1
- Same 1520.00 with confirmDeviation=true proceeds to INSERT
- First-ever rate for a new ccy_pair (no prior row) is accepted unconditionally
- percentageChange in exception is within 0.01% of the true calculated value
**Depends on:** 3.4-T07

### 3.4-T09 — Expose GET /internal/rates/{ccyPair}/latest in TreasuryRateController  _(30 min)_
**Context:** services/rate-fx exposes an internal REST endpoint consumed by payment-executor and smart-router to resolve cost rates at quote time. @RestController @RequestMapping('/internal/rates'). GET /{ccyPair}/latest calls TreasuryRateService.getEffectiveRate(ccyPair, Instant.now()) and returns TreasuryRateDto {ccyPair, rate (String to preserve 8dp), source, effectiveAt}. RateNotFoundException mapped to 404 {code:RATE_NOT_FOUND} via lib-errors GlobalExceptionHandler. No auth on internal endpoints -- mTLS enforced at infra/k8s network policy level. Package: com.gme.pay.ratefx.controller.
**Steps:** Create services/rate-fx/src/main/java/com/gme/pay/ratefx/controller/TreasuryRateController.java; Annotate @RestController, @RequestMapping("/internal/rates"); Implement @GetMapping("/{ccyPair}/latest") calling service and mapping to TreasuryRateDto; Define TreasuryRateDto as record(String ccyPair, String rate, String source, String effectiveAt) -- rate as String preserves all 8 decimals; Ensure RateNotFoundException is handled globally (GlobalExceptionHandler in lib-errors) returning 404 with {code:RATE_NOT_FOUND}
**Deliverable:** services/rate-fx/src/main/java/com/gme/pay/ratefx/controller/TreasuryRateController.java
**Acceptance / logic checks:**
- GET /internal/rates/usd_krw/latest returns 200 with correct ccyPair and rate when row exists
- GET /internal/rates/usd_usd/latest returns 200 with rate='1.00000000'
- GET /internal/rates/usd_xyz/latest returns 404 with body {code:'RATE_NOT_FOUND'}
- rate field in JSON response is a String (not a JSON number) to avoid floating-point loss
**Depends on:** 3.4-T06

### 3.4-T10 — Add Redis cache for latest rate in TreasuryRateService -- TTL = 1s  _(35 min)_
**Context:** Per PRD-07 section 7.5: rate update latency between Ops action and transaction effect must be <=1 second. Cache the latest rate per ccy_pair in Redis with TTL=1s using @Cacheable (Spring Cache abstraction) backed by RedisCacheManager. Cache name: treasury-rate-latest. Key = ccyPair. On upsertRate: @CacheEvict(value='treasury-rate-latest', key='#ccyPair') to invalidate immediately on write. usd_usd bypasses cache (returns BigDecimal.ONE before any cache lookup). Add spring-boot-starter-data-redis to services/rate-fx/build.gradle. Configure redis host/port in application.yml.
**Steps:** Add spring-boot-starter-data-redis to services/rate-fx/build.gradle; Create services/rate-fx/src/main/java/com/gme/pay/ratefx/config/RateFxCacheConfig.java with @Configuration @EnableCaching; Configure RedisCacheManager with TTL=Duration.ofSeconds(1) for cache 'treasury-rate-latest' using Jackson serialization; Annotate getEffectiveRate() with @Cacheable(value='treasury-rate-latest', key='#ccyPair', condition='!#ccyPair.equals("usd_usd")'); Annotate upsertRate() with @CacheEvict(value='treasury-rate-latest', key='#ccyPair')
**Deliverable:** services/rate-fx/src/main/java/com/gme/pay/ratefx/config/RateFxCacheConfig.java and updated TreasuryRateService.java
**Acceptance / logic checks:**
- After upsertRate for usd_krw, next getEffectiveRate call returns the new value (cache evicted on write)
- After 1s TTL expiry with no write, cache miss triggers a fresh DB read (confirmed by repository call count)
- Updating usd_krw does not evict usd_mnt cache entry (keys are independent)
- usd_usd short-circuit bypasses Redis entirely -- no cache key created for usd_usd
**Depends on:** 3.4-T06

### 3.4-T11 — AuditLogService interface and PostgresAuditLogService @Service in lib-persistence  _(35 min)_
**Context:** All treasury_rate mutations must emit an audit_log row (DAT-03): entity_type='treasury_rate', entity_id=rate.id, action=CREATE, before_value=JSONB null (for CREATE), after_value=JSONB serialized new state, actor=operator id, occurred_at=NOW(). Interface in lib-persistence: void log(String action, String entityType, Long entityId, String beforeJson, String afterJson, String actor). PostgresAuditLogService implements it with JdbcTemplate INSERT into audit_log. Uses @Transactional(propagation=REQUIRES_NEW) so audit insert never rolls back with the parent business transaction. audit_log table migration is in 3.4-T14.
**Steps:** Create lib-persistence/src/main/java/com/gme/pay/persistence/audit/AuditLogService.java as interface with void log(...); Create lib-persistence/src/main/java/com/gme/pay/persistence/audit/PostgresAuditLogService.java implementing AuditLogService; Annotate PostgresAuditLogService @Service; inject JdbcTemplate; Implement log() with @Transactional(propagation=REQUIRES_NEW) executing INSERT INTO audit_log; Cast beforeJson/afterJson to ::jsonb in the SQL to enforce JSONB storage
**Deliverable:** lib-persistence/src/main/java/com/gme/pay/persistence/audit/AuditLogService.java and PostgresAuditLogService.java
**Acceptance / logic checks:**
- log() inserts exactly one row in audit_log with matching entity_type, entity_id, action, actor
- before_value is NULL for CREATE actions (SQL NULL cast to ::jsonb)
- Method uses REQUIRES_NEW propagation -- audit row persists even if caller transaction rolls back
- lib-persistence compiles with no circular dependency on rate-fx or any service module

### 3.4-T12 — Flyway migration V7__create_audit_log_table.sql in services/rate-fx  _(25 min)_
**Context:** audit_log table per DAT-03: id BIGINT PK auto-increment, actor VARCHAR(120), actor_type VARCHAR(20) CHECK IN ('OPERATOR','SYSTEM','PARTNER'), action VARCHAR(20) CHECK IN ('CREATE','UPDATE','DELETE','ACTIVATE','DEACTIVATE'), entity_type VARCHAR(50), entity_id BIGINT, before_value JSONB, after_value JSONB, occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW(). No updated_at -- table is append-only. Indexes: on (entity_type, entity_id) for history lookup and on (occurred_at DESC) for time-range queries. File: services/rate-fx/src/main/resources/db/migration/V7__create_audit_log_table.sql. If a shared migration already creates this table, this ticket is a no-op; close it.
**Steps:** Check if audit_log DDL exists in any prior migration under services/rate-fx/src/main/resources/db/migration/; If absent, create V7__create_audit_log_table.sql with full DDL including JSONB columns for before/after; Add index idx_audit_entity ON audit_log(entity_type, entity_id); Add index idx_audit_occurred ON audit_log(occurred_at DESC); Confirm no updated_at column exists (table is immutable append-only)
**Deliverable:** services/rate-fx/src/main/resources/db/migration/V7__create_audit_log_table.sql
**Acceptance / logic checks:**
- audit_log has 9 columns with JSONB for before_value and after_value
- actor_type and action CHECK constraints enforced at DB level
- No updated_at column
- Index on (entity_type, entity_id) present
- occurred_at has DEFAULT NOW()
**Depends on:** 3.4-T03

### 3.4-T13 — Expose POST /admin/rates in TreasuryRateAdminController with OPS_RATE_MANAGER RBAC  _(35 min)_
**Context:** Admin System calls POST /admin/rates to insert a new effective-dated rate. Request body RateUpsertRequest: {ccyPair, rate (String), source, effectiveAt (ISO-8601), notes (optional String), confirmDeviation (boolean default false)}. Controller calls TreasuryRateService.upsertRate(). On RateDeviationWarningException return HTTP 422 with {code:RATE_DEVIATION_WARNING, previousRate, percentageChange}. On success return 201 with TreasuryRateDto. Requires @PreAuthorize("hasRole('OPS_RATE_MANAGER')") -- Spring Security RBAC. Package: com.gme.pay.ratefx.controller.admin.
**Steps:** Create services/rate-fx/src/main/java/com/gme/pay/ratefx/controller/admin/TreasuryRateAdminController.java; Annotate @RestController, @RequestMapping("/admin/rates"), @PreAuthorize("hasRole('OPS_RATE_MANAGER')"); Implement @PostMapping: deserialize RateUpsertRequest, call upsertRate(), return 201 with TreasuryRateDto; Handle RateDeviationWarningException with @ExceptionHandler returning 422 with deviation payload; Parse rate field as BigDecimal from String to prevent float precision loss
**Deliverable:** services/rate-fx/src/main/java/com/gme/pay/ratefx/controller/admin/TreasuryRateAdminController.java
**Acceptance / logic checks:**
- POST with valid payload and OPS_RATE_MANAGER role returns 201 with ccyPair and rate in body
- POST with >10% deviation and confirmDeviation=false returns 422 with code RATE_DEVIATION_WARNING and percentageChange field
- POST with >10% deviation and confirmDeviation=true returns 201
- POST without OPS_RATE_MANAGER role returns 403
- rate field in request is parsed as String-to-BigDecimal (never parsed as JSON number/float)
**Depends on:** 3.4-T08, 3.4-T11

### 3.4-T14 — Expose GET /admin/rates and GET /admin/rates/{ccyPair}/history in admin controller  _(35 min)_
**Context:** Ops portal needs: (1) GET /admin/rates -- all distinct ccy_pairs with their latest rate (SQL: SELECT DISTINCT ON (ccy_pair) * FROM treasury_rate ORDER BY ccy_pair, effective_at DESC); (2) GET /admin/rates/{ccyPair}/history?page=0&size=20 -- full history for one pair, newest first (Page<TreasuryRateDto>). Both require OPS_RATE_MANAGER. Add a @Query native SQL in TreasuryRateRepository for the DISTINCT ON query. Response includes source, effectiveAt, createdBy, updatedAt.
**Steps:** Add @GetMapping on /admin/rates handler returning List<TreasuryRateDto> (latest per pair); Add native @Query in TreasuryRateRepository: SELECT DISTINCT ON (ccy_pair) * FROM treasury_rate ORDER BY ccy_pair, effective_at DESC; Add @GetMapping on /admin/rates/{ccyPair}/history returning Page<TreasuryRateDto> delegating to TreasuryRateService.getHistory(); Both handlers carry @PreAuthorize("hasRole('OPS_RATE_MANAGER')"); Include source, effectiveAt, createdBy in TreasuryRateDto
**Deliverable:** Updated services/rate-fx/src/main/java/com/gme/pay/ratefx/controller/admin/TreasuryRateAdminController.java
**Acceptance / logic checks:**
- GET /admin/rates returns exactly one entry per ccy_pair showing the row with highest effective_at
- GET /admin/rates/usd_krw/history returns all rows for usd_krw newest first
- Page metadata (totalElements, totalPages) is correct
- Both endpoints return 403 for callers without OPS_RATE_MANAGER role
- GET /admin/rates includes the usd_usd seed row with source=MANUAL
**Depends on:** 3.4-T13

### 3.4-T15 — Add TreasuryRateUpdatedEvent to lib-events and OutboxEventPublisher to rate-fx  _(40 min)_
**Context:** Phase 1 messaging: transactional Outbox pattern (Kafka deferred to integration phase). After upsertRate saves treasury_rate, publish TreasuryRateUpdatedEvent in the SAME @Transactional. Event fields (Java record): String eventId (UUID), String ccyPair, String newRate, String previousRate (nullable), String source, String effectiveAt, String occurredAt, String actor. Outbox table: domain_event_outbox (id UUID PK, aggregate_type VARCHAR(50), aggregate_id VARCHAR(50), event_type VARCHAR(100), payload JSONB, created_at TIMESTAMPTZ DEFAULT NOW(), published_at TIMESTAMPTZ NULL). Create Flyway migration V8__create_domain_event_outbox.sql. EventPublisher interface in lib-events.
**Steps:** Create lib-events/src/main/java/com/gme/pay/events/ratefx/TreasuryRateUpdatedEvent.java as record with all 8 String fields; Create lib-events/src/main/java/com/gme/pay/events/EventPublisher.java interface: void publish(Object event); Create services/rate-fx/src/main/java/com/gme/pay/ratefx/messaging/OutboxEventPublisher.java implementing EventPublisher; inject JdbcTemplate; INSERT into domain_event_outbox within caller transaction; Create V8__create_domain_event_outbox.sql migration with columns listed in context; Inject EventPublisher into TreasuryRateService.upsertRate() and call after repository.save() inside same @Transactional
**Deliverable:** lib-events TreasuryRateUpdatedEvent.java, lib-events EventPublisher.java, services/rate-fx OutboxEventPublisher.java, V8__create_domain_event_outbox.sql
**Acceptance / logic checks:**
- treasury_rate INSERT and outbox INSERT either both commit or both roll back (atomicity)
- domain_event_outbox row has aggregate_type='treasury_rate', event_type='TreasuryRateUpdatedEvent', non-null payload JSONB after upsertRate
- EventPublisher interface in lib-events has no dependency on any service module
- OutboxEventPublisher can be swapped for KafkaEventPublisher later without changing TreasuryRateService
**Depends on:** 3.4-T07

### 3.4-T16 — Unit test: TreasuryRateServiceTest -- getEffectiveRate happy paths and RATE_NOT_FOUND  _(30 min)_
**Context:** JUnit 5 pure unit tests for TreasuryRateService.getEffectiveRate(). No Spring context, no DB. Use Mockito (@ExtendWith(MockitoExtension.class)) to mock TreasuryRateRepository. Test cases: (1) ccyPair=usd_usd returns BigDecimal.ONE; verify repository never called. (2) ccyPair=usd_krw with mocked row rate=1380.00000000 returns that BigDecimal. (3) unknown pair with Optional.empty() throws RateNotFoundException code RATE_NOT_FOUND. File: services/rate-fx/src/test/java/com/gme/pay/ratefx/service/TreasuryRateServiceTest.java.
**Steps:** Create TreasuryRateServiceTest.java under services/rate-fx/src/test/; @ExtendWith(MockitoExtension.class); @Mock TreasuryRateRepository; @Mock AuditLogService; @InjectMocks TreasuryRateService; Test 1: getEffectiveRate("usd_usd", now()); assert returns BigDecimal.ONE; verify(repo).findTopBy... never called; Test 2: when(repo.findTop...("usd_krw")).thenReturn(Optional.of(rateRow("1380.00000000"))); assert result equals new BigDecimal("1380.00000000"); Test 3: when(repo.findTop...("usd_xyz")).thenReturn(Optional.empty()); assertThrows(RateNotFoundException.class); check code==RATE_NOT_FOUND
**Deliverable:** services/rate-fx/src/test/java/com/gme/pay/ratefx/service/TreasuryRateServiceTest.java
**Acceptance / logic checks:**
- All 3 tests pass: ./gradlew :services:rate-fx:test
- No Spring context loaded -- tests complete in under 2 seconds
- Test 1 verifies zero Mockito interactions on repository
- Test 3 RateNotFoundException carries code RATE_NOT_FOUND
**Depends on:** 3.4-T06

### 3.4-T17 — Unit test: TreasuryRateServiceTest -- upsertRate validation and deviation guard vectors  _(30 min)_
**Context:** JUnit 5 Mockito unit tests for upsertRate() validation and deviation guard. Exact test vectors: (1) rate=BigDecimal.ZERO throws InvalidRateException RATE_INVALID_VALUE; (2) source='FEED' throws InvalidRateException RATE_INVALID_SOURCE; (3) effectiveAt=Instant.now().minusSeconds(120) throws RATE_BACKDATE_REJECTED; (4) prev=1380.00 new=1520.00 -> percentageChange=10.14%, confirmDeviation=false throws RateDeviationWarningException; (5) same deviation with confirmDeviation=true calls repository.save() exactly once. File: same TreasuryRateServiceTest.java as 3.4-T16.
**Steps:** Add 5 test methods to TreasuryRateServiceTest.java; Tests 1-3: no repository setup needed; pass invalid inputs; assert exception codes; Test 4: mock repo.findTop...(usd_krw) to return row(rate=1380.00); call upsertRate(usd_krw, 1520.00, MANUAL, now, actor, false); assertThrows RateDeviationWarningException; check percentageChange >=10.1; Test 5: same setup with confirmDeviation=true; assertDoesNotThrow; verify(repo).save(any()) once; Use BigDecimal string constructors throughout -- no double literals
**Deliverable:** Additional test methods in services/rate-fx/src/test/java/com/gme/pay/ratefx/service/TreasuryRateServiceTest.java
**Acceptance / logic checks:**
- All 5 tests pass
- Test 4 exception percentageChange is between 10.10 and 10.20
- Test 5 verifies repository.save() called exactly once via Mockito verify
- Tests 1-3 assert exact exception codes
- No DB or Spring context required
**Depends on:** 3.4-T08, 3.4-T16

### 3.4-T18 — Integration test: TreasuryRateRepositoryIT with Testcontainers PostgreSQL 16 and Flyway  _(40 min)_
**Context:** Verifies Flyway migrations V4-V8 and repository queries against a real PostgreSQL 16 container. @SpringBootTest(webEnvironment=NONE) + @Testcontainers. Test cases: (1) seed row usd_usd present after migrations with rate=1.00000000; (2) insert two usd_krw rows with effectiveAt t1 < t2; findTopByCcyPairOrderByEffectiveAtDesc returns t2 row; (3) insert duplicate (usd_krw, t2) throws DataIntegrityViolationException from unique constraint uq_treasury_rate_ccy_effective. File: services/rate-fx/src/test/java/com/gme/pay/ratefx/repository/TreasuryRateRepositoryIT.java.
**Steps:** Create TreasuryRateRepositoryIT.java under services/rate-fx/src/test/; @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16"); Configure datasource and flyway.locations via @DynamicPropertySource; Test 1: assert findByCcyPairOrderByEffectiveAtDesc("usd_usd", ...) returns row with rate compareTo(BigDecimal.ONE)==0; Test 2: save two rows; assert findTop returns row with higher effectiveAt; Test 3: save duplicate (ccyPair, effectiveAt); assertThrows DataIntegrityViolationException
**Deliverable:** services/rate-fx/src/test/java/com/gme/pay/ratefx/repository/TreasuryRateRepositoryIT.java
**Acceptance / logic checks:**
- All 3 tests pass against real Postgres 16 Testcontainer
- Flyway V4->V5->V6->V7->V8 run in order without error
- Test 3 confirms unique constraint blocks duplicate (ccy_pair, effective_at)
- Test 2 confirms the newer effectiveAt row is returned (ORDER BY effective_at DESC LIMIT 1)
**Depends on:** 3.4-T05, 3.4-T12, 3.4-T15

### 3.4-T19 — Integration test: TreasuryRateAdminControllerIT with Spring MVC test slice  _(35 min)_
**Context:** @WebMvcTest(TreasuryRateAdminController.class) + MockMvc. @MockBean TreasuryRateService. No DB needed. Tests: (1) POST /admin/rates valid payload + @WithMockUser(roles=OPS_RATE_MANAGER) -> 201 with TreasuryRateDto; (2) MockBean throws RateDeviationWarningException -> 422 with code RATE_DEVIATION_WARNING and percentageChange; (3) MockBean throws InvalidRateException RATE_INVALID_VALUE -> 400; (4) POST /admin/rates without auth -> 403; (5) GET /admin/rates with auth -> 200 list. File: services/rate-fx/src/test/java/com/gme/pay/ratefx/controller/admin/TreasuryRateAdminControllerIT.java.
**Steps:** Create TreasuryRateAdminControllerIT.java under services/rate-fx/src/test/; @WebMvcTest(TreasuryRateAdminController.class); @MockBean TreasuryRateService; Test 1: POST valid JSON; @WithMockUser(roles={"OPS_RATE_MANAGER"}); mockMvc.perform().andExpect(status().isCreated()); Test 2: when(service.upsertRate(...)).thenThrow(new RateDeviationWarningException(...)); andExpect(status().isUnprocessableEntity()); Test 4: POST without @WithMockUser; andExpect(status().isForbidden())
**Deliverable:** services/rate-fx/src/test/java/com/gme/pay/ratefx/controller/admin/TreasuryRateAdminControllerIT.java
**Acceptance / logic checks:**
- All 5 tests pass
- Test 2 response JSON contains percentageChange field
- Test 3 returns 400 not 500
- Test 4 returns 403 confirming @PreAuthorize is active
- No real DB or Redis required -- pure MockMvc with mocked service
**Depends on:** 3.4-T14, 3.4-T13

### 3.4-T20 — Integration test: Redis cache TTL and eviction via Testcontainers Redis 7  _(40 min)_
**Context:** Full-stack cache test using Testcontainers. @SpringBootTest(webEnvironment=NONE) with both PostgreSQLContainer (postgres:16) and GenericContainer (redis:7). Spy on TreasuryRateRepository. Test: (1) call getEffectiveRate('usd_krw') twice; assert repository spy shows findTop called once (second call from cache); (2) call upsertRate to insert new rate; then getEffectiveRate; assert repository called again (cache evicted); (3) Thread.sleep(1100) after a cached call; getEffectiveRate again; assert repository called (TTL expired). File: services/rate-fx/src/test/java/com/gme/pay/ratefx/service/TreasuryRateServiceCacheIT.java.
**Steps:** Create TreasuryRateServiceCacheIT.java under services/rate-fx/src/test/; Spin up Postgres 16 and Redis 7 containers; wire via @DynamicPropertySource; Spy on TreasuryRateRepository bean (@SpyBean); Test 1: call getEffectiveRate twice; verify findTop called exactly once total; Test 2: upsertRate; getEffectiveRate; verify findTop called again; Test 3: Thread.sleep(1100); getEffectiveRate; verify findTop called (TTL expired)
**Deliverable:** services/rate-fx/src/test/java/com/gme/pay/ratefx/service/TreasuryRateServiceCacheIT.java
**Acceptance / logic checks:**
- Test 1 passes: second identical call does not hit repository (0 additional spy invocations)
- Test 2 passes: upsertRate evicts cache; next getEffectiveRate hits repository
- Test 3 passes: after 1.1s sleep the cache miss causes a repository read
- usd_usd calls never increment repository spy count (IDENTITY short-circuit before cache)
**Depends on:** 3.4-T10, 3.4-T18

### 3.4-T21 — Outbox atomicity integration test: treasury_rate and outbox rows roll back together  _(40 min)_
**Context:** Verifies the transactional Outbox invariant: treasury_rate INSERT and domain_event_outbox INSERT are in the same @Transactional and either both commit or both roll back. Test: (1) Stub OutboxEventPublisher to throw RuntimeException after the outbox insert sql but before commit (use @SpyBean to intercept); call upsertRate; assert no new treasury_rate row; (2) Happy path: successful upsertRate produces exactly 1 new treasury_rate row and 1 new domain_event_outbox row. File: services/rate-fx/src/test/java/com/gme/pay/ratefx/service/TreasuryRateOutboxAtomicityIT.java.
**Steps:** Create TreasuryRateOutboxAtomicityIT.java under services/rate-fx/src/test/; @SpringBootTest(webEnvironment=NONE) + Postgres 16 Testcontainer; @SpyBean OutboxEventPublisher; configure spy to throw RuntimeException after first publish call; Call upsertRate(usd_krw, 1380.00, MANUAL, now, actor, false) -- expect exception propagation; Assert treasury_rate count for usd_krw unchanged and domain_event_outbox count unchanged; Restore spy; call upsertRate again; assert both counts increment by 1
**Deliverable:** services/rate-fx/src/test/java/com/gme/pay/ratefx/service/TreasuryRateOutboxAtomicityIT.java
**Acceptance / logic checks:**
- When publisher throws, SELECT count FROM treasury_rate WHERE ccy_pair='usd_krw' shows 0 new rows
- When publisher throws, SELECT count FROM domain_event_outbox shows 0 new rows
- Happy path: both counts increment by exactly 1
- Both rows share the same transaction commit (verified by count delta = 1 for both)
**Depends on:** 3.4-T15, 3.4-T18

### 3.4-T22 — Numeric precision unit test: BigDecimal arithmetic for KRW and MNT rate vectors  _(30 min)_
**Context:** Exact arithmetic checks using JUnit 5 (no Spring, no DB). Test vectors from RATE-04 section 4.3: usd_krw=1380.00000000, target_payout=50000 KRW, m_a=0.01, m_b=0.01 (2% total). (1) payout_usd_cost = 50000 / 1380.00000000 HALF_UP 8dp = 36.23188406; (2) collection_usd = 36.23188406 / (1-0.01-0.01) HALF_UP 8dp = 36.97131027; (3) collection_margin_usd = 36.97131027*0.01 = 0.36971310; payout_margin_usd = 0.36971310; (4) pool identity: 36.97131027 - 0.36971310 - 0.36971310 = 36.23188407 -- tolerance vs 36.23188406 <= 0.00000001. Verify BigDecimal.ONE used for IDENTITY leg gives exact equality. File: services/rate-fx/src/test/java/com/gme/pay/ratefx/service/RatePrecisionTest.java.
**Steps:** Create RatePrecisionTest.java under services/rate-fx/src/test/; Test 1: assert BigDecimal("50000").divide(new BigDecimal("1380.00000000"), 8, HALF_UP).equals(new BigDecimal("36.23188406")); Test 2: compute collection_usd from test 1 result; assert matches expected to 8dp; Test 3: compute pool identity remainder; assert abs(remainder - payout_usd_cost) <= new BigDecimal("0.00000001"); Test 4: usd_usd identity: BigDecimal("50").divide(BigDecimal.ONE, 8, HALF_UP) equals new BigDecimal("50.00000000"); Verify no double or float literals in computation -- BigDecimal string constructors throughout
**Deliverable:** services/rate-fx/src/test/java/com/gme/pay/ratefx/service/RatePrecisionTest.java
**Acceptance / logic checks:**
- Test 1: 50000/1380 rounded to 8dp = 36.23188406 exactly
- Test 3: pool identity holds within 0.00000001 tolerance (8dp internal computation tolerance, not the 0.01 USD display tolerance)
- Test 4: identity leg gives exact BigDecimal equality to 8dp
- No double or float types appear anywhere in the computation chain
- Tests run in under 100ms (no IO)
**Depends on:** 3.4-T06

### 3.4-T23 — Spring Boot application smoke test: rate-fx starts with all migrations and /internal/rates/usd_usd/latest returns 200  _(30 min)_
**Context:** @SpringBootTest(webEnvironment=RANDOM_PORT) test that starts the full rate-fx application context with Testcontainers (Postgres 16 + Redis 7). Verifies: (1) Spring context loads with no BeanCreationException; (2) Flyway migrations V4-V8 applied successfully; (3) GET /internal/rates/usd_usd/latest returns HTTP 200 with rate='1.00000000'. This is the CI/CD gate smoke test. File: services/rate-fx/src/test/java/com/gme/pay/ratefx/RateFxApplicationIT.java.
**Steps:** Create RateFxApplicationIT.java under services/rate-fx/src/test/; @SpringBootTest(webEnvironment=RANDOM_PORT) + @Testcontainers; Declare static Postgres 16 and Redis 7 containers; @DynamicPropertySource wires spring.datasource.* and spring.data.redis.*; Inject TestRestTemplate; call GET /internal/rates/usd_usd/latest; Assert status 200 and responseBody.rate equals '1.00000000'
**Deliverable:** services/rate-fx/src/test/java/com/gme/pay/ratefx/RateFxApplicationIT.java
**Acceptance / logic checks:**
- Application context starts without BeanCreationException or UnsatisfiedDependencyException
- Flyway log shows migrations V4 V5 V6 V7 V8 applied in order
- GET /internal/rates/usd_usd/latest returns HTTP 200
- Response body rate field = '1.00000000' (string, 8 decimal places)
**Depends on:** 3.4-T21, 3.4-T09, 3.4-T10

### 3.4-T24 — Document services/rate-fx treasury rate design in README -- schema, cache, outbox  _(20 min)_
**Context:** Developer README (not a spec) covering WBS 3.4 design decisions so a new developer can understand and maintain the treasury rate subsystem. Sections: (1) Table design -- immutable append-only rows, latest-rate query pattern (ORDER BY effective_at DESC LIMIT 1), unique constraint on (ccy_pair, effective_at); (2) Rate resolution -- getEffectiveRate, IDENTITY short-circuit for usd_usd; (3) Redis cache -- key=ccyPair, TTL=1s, eviction on write; (4) Outbox -- TreasuryRateUpdatedEvent in domain_event_outbox, Kafka wiring deferred to integration phase; (5) Admin endpoints -- POST /admin/rates, deviation guard, RBAC. Keep under 80 lines total. File: services/rate-fx/README.md.
**Steps:** Create or update services/rate-fx/README.md; Section 1 (table design): immutable rows, latest-rate query, no-overlap unique constraint; Section 2 (rate resolution): getEffectiveRate, usd_usd returns 1.0 without DB hit; Section 3 (Redis cache): TTL=1s, @Cacheable, @CacheEvict on write; Section 4 (outbox): TreasuryRateUpdatedEvent, Kafka deferred; Keep total under 80 lines; link to RATE-04 and DAT-03 for full math
**Deliverable:** services/rate-fx/README.md
**Acceptance / logic checks:**
- README is under 80 lines
- Explains immutable-row (append-only) rationale
- Mentions usd_usd identity convention (no DB hit)
- Documents 1s Redis TTL and eviction-on-write
- Does not duplicate full spec math -- references RATE-04 for 5-step formulas
**Depends on:** 3.4-T23

<!-- wbs-v3-gap-closure -->

---

## WBS v3 gap-closure tickets (re-baseline, 2026-06-10)

These tickets convert this service's PARTIAL audit findings into DONE and add work discovered during the build. Statuses live on the `Backlog` sheet of `GMEPay+_Task_Backlog.xlsx`; phase sequencing on the `Completion Plan v3` sheet of `GMEPay+_WBS.xlsx`.

### 17.2-G01 — config-registry: swap H2 for real PostgreSQL ITs
*Completion phase:* **R1** · *Est:* 120 min · *Role:* Backend · *Deps:* 17.1-G02

**Context.** Tests currently run on H2 in PostgreSQL mode. Acceptance requires real PG. Scope: partner/scheme/rule/treasury tables (V001+).

**Steps.**
- Add Testcontainers postgres:16 to the service's ITs
- Run Flyway migrations against it; fix PG-only syntax drift
- Keep H2 only for pure unit slices

**Deliverable.** Repository/migration ITs green on PostgreSQL 16

**Acceptance.**
- ./gradlew :services:config-registry:test green with Testcontainers
- Migration checksum stable; no H2-mode workarounds left

### 17.3-G03 — Redis config cache + invalidation
*Completion phase:* **R1** · *Est:* 100 min · *Role:* Backend · *Deps:* 17.1-G02

**Context.** Partner/scheme/rule reads are hot path for executor+router; cache in Redis with explicit invalidation on write.

**Steps.**
- Cache-aside on partner/scheme/rule GETs
- DEL on update endpoints
- Metrics: hit ratio

**Deliverable.** Config cache with invalidation

**Acceptance.**
- Update visible to readers <1s after write
- Hit-ratio metric exposed

### 17.6-G01 — Persist Scheme + Direction registries
*Completion phase:* **R1** · *Est:* 120 min · *Role:* Backend · *Deps:* 17.2-G01

**Context.** Scheme/direction data is seed/in-memory. Add entities + Flyway + CRUD with effective-dating.

**Steps.**
- schemes, scheme_directions tables
- CRUD endpoints with validation
- Seed ZeroPay row via migration

**Deliverable.** Persistent scheme registry

**Acceptance.**
- Restart-safe; effective-dated lookups correct at boundaries

### 17.6-G02 — Persist Routing-Rule registry
*Completion phase:* **R1** · *Est:* 120 min · *Role:* Backend · *Deps:* 17.6-G01

**Context.** smart-router reads rules from config; rules must be DB-backed with versioning + audit of changes.

**Steps.**
- routing_rules table w/ version, active flags
- GET /v1/routing-rules consumed by smart-router
- Rule change audit-logged

**Deliverable.** Persistent rule registry

**Acceptance.**
- Router picks up rule change without redeploy (cache invalidation via 17.3-G03)

### 17.6-G03 — Persist Treasury rate tables (effective-dated)
*Completion phase:* **R1** · *Est:* 140 min · *Role:* Backend · *Deps:* 17.6-G01

**Context.** Treasury/FX base tables (WBS 3.4) still pending: effective-dated rate rows feeding rate-fx sourcing.

**Steps.**
- treasury_rates table (ccy pair, effective_from/to, source)
- POST import endpoint + validation
- rate-fx sourcing reads via REST

**Deliverable.** Effective-dated treasury rates

**Acceptance.**
- rate-fx 5-step engine sources from persisted rows; boundary tests green

---

<!-- ws-21-partner-setup-rebaseline -->

## Partner Setup re-baseline tickets (WS 21)

These tickets close Partner Setup audit gaps under the 8-slice vertical plan in `docs/PARTNER_SETUP_PLAN.md` (approved 2026-06-11). Each ticket id `21.{slice}-Pxx` maps to a wizard slice; ADR references point at `docs/adr/`. Tickets owned by **config-registry** live here; cross-service contributions are listed at the bottom for awareness.

> Note: legacy WP 10.3 entries on the WBS spreadsheet remain in place but are flagged *superseded by WS 21 — see docs/PARTNER_SETUP_PLAN.md*.

### Slice 1 tickets owned by this service

### 21.1-P01 — Migration V210: add partner_id BIGSERIAL surrogate + partner_code VARCHAR(20) UNIQUE
*Slice:* **1** · *Est:* 45 min · *Role:* Backend · *Owner:* config-registry · *ADR refs:* ADR-013

**Context.** Cross-cutting bug #1 — Partner ID type schism. Partner.partnerId is String today but PrincipalEntity, WebhookEndpointEntity, settlement-reconciliation, PartnerCredentialPort use Long. Expand step (ADR-013): add the new columns, backfill, leave old String partner_id readable. Module: services/config-registry. Flyway path services/config-registry/src/main/resources/db/migration/V210__partner_id_surrogate.sql.

**Steps.** Add `partner_id BIGSERIAL` (new PK candidate, NOT NULL DEFAULT nextval); add `partner_code VARCHAR(20) NOT NULL UNIQUE` and backfill from the existing String `partner_id`; keep the old column under its current name so Release N+2 (21.1-P03) can drop it; index on `partner_code`; CHECK `partner_code ~ '^[A-Z][A-Z0-9_]{2,19}$'`.

**Deliverable.** `services/config-registry/src/main/resources/db/migration/V210__partner_id_surrogate.sql`

**Acceptance.**
- V210 applies cleanly on Testcontainers postgres:16 over the existing V201-V207 baseline
- After backfill, every existing partner row has a unique partner_id BIGINT and partner_code matching its old String id
- CHECK constraint rejects partner_code='ab' (too short) and partner_code='123ABC' (must start with letter)
- UNIQUE(partner_code) raises 23505 on duplicate insert

### 21.1-P02 — Flip PrincipalEntity/WebhookEndpointEntity/settlement-reconciliation joins to partner_id FK
*Slice:* **1** · *Est:* 90 min · *Role:* Backend · *Owner:* config-registry · *ADR refs:* ADR-013

**Context.** Expand/Contract step 2 (ADR-013) for the ID schism. Switch consumers from the String partner_id to the new BIGINT partner_id surrogate. Old String column stays read-only this release; consumers must compile against the new FK.

**Steps.** In services/auth-identity/.../PrincipalEntity.java change `partnerId` from String to Long with @JoinColumn(name="partner_id"); same in services/notification-webhook/.../WebhookEndpointEntity.java; same in services/settlement-reconciliation/.../*PartnerRef* repos; update all JPQL `where partnerId = :id` queries; update lib-api-contracts PartnerCredentialPort signatures to Long; @Deprecated the String-keyed overloads with a Javadoc pointer to ADR-013.

**Deliverable.** `services/auth-identity/src/main/java/com/gme/pay/auth/PrincipalEntity.java; services/notification-webhook/src/main/java/com/gme/pay/notify/WebhookEndpointEntity.java; services/settlement-reconciliation/src/main/java/com/gme/pay/recon/PartnerSettlementRepository.java; lib-api-contracts/src/main/java/com/gme/pay/contracts/auth/PartnerCredentialPort.java`

**Acceptance.**
- All four modules compile against Long partner_id without -Werror failures
- Existing repository-method tests still pass after the type change
- Deprecated String-keyed overloads carry a Javadoc reference to ADR-013 Release N+2
- Foreign-key constraints in V210 reference partners(partner_id) BIGINT, not the old String column

### 21.1-P03 — Migration V230 (Release N+2): drop old String partner_id column, rename partner_code remains
*Slice:* **1** · *Est:* 30 min · *Role:* Backend · *Owner:* config-registry · *ADR refs:* ADR-013

**Context.** Contract step (ADR-013) — only runs after Release N+1 has shipped and consumers are off the String column. Drop `partner_id_old_string` (the column V210 renamed it to) and remove the @Deprecated overloads from lib-api-contracts. Lands in a later release window; ticket exists now so the migration is planned.

**Steps.** Create V230__drop_partner_id_string.sql; ALTER TABLE partners DROP COLUMN partner_id_old_string; remove @Deprecated overloads from PartnerCredentialPort; update Javadoc.

**Deliverable.** `services/config-registry/src/main/resources/db/migration/V230__drop_partner_id_string.sql`

**Acceptance.**
- V230 succeeds only if no FK still references the dropped column (gate this in a Flyway callback)
- PartnerCredentialPort no longer has String-keyed methods on its interface
- All consuming services compile + test after V230 with no remaining String partner_id references

### 21.1-P04 — Collapse 5 Partner DTOs into PartnerView (read) + PartnerCommand (write)
*Slice:* **1** · *Est:* 90 min · *Role:* Backend · *Owner:* config-registry · *ADR refs:* ADR-013

**Context.** Cross-cutting bug #2. Today: Partner (lib-domain), PartnerEntity, PartnerProfile, PartnerSummary, PartnerCreateRequest. Five shapes, five drift surfaces. Introduce canonical PartnerView (Java record in lib-api-contracts) for read and PartnerCommand sealed interface (Create/Update variants) for write. Old shapes become @Deprecated thin adapters that delegate to PartnerView.

**Steps.** Define `lib-api-contracts/src/main/java/com/gme/pay/contracts/partner/PartnerView.java` as a Java record carrying every field on the partner aggregate; define `PartnerCommand` sealed interface with `PartnerCommand.Create` and `PartnerCommand.Update` records; mark the five legacy DTOs @Deprecated with `@Deprecated(forRemoval=true, since="WS-21-Slice-1")` and a Javadoc redirect; update mappers (Partner -> PartnerView, PartnerView -> PartnerEntity) in config-registry; ops-partner-bff RestConfigRegistryClient returns PartnerView only.

**Deliverable.** `lib-api-contracts/src/main/java/com/gme/pay/contracts/partner/PartnerView.java; lib-api-contracts/.../PartnerCommand.java; services/config-registry/.../PartnerMapper.java`

**Acceptance.**
- PartnerView is a Java record; no setters; BigDecimal fields not double
- All five legacy DTOs carry @Deprecated(forRemoval=true)
- ops-partner-bff and admin-ui handlers compile against PartnerView only (no compile-time reference to the deprecated five)
- Mapper round-trip test: PartnerEntity -> PartnerView -> PartnerCommand.Update -> PartnerEntity preserves every field

### 21.1-P05 — Migration V211: bitemporal partner table (valid_from/to + recorded_at/superseded_at)
*Slice:* **1** · *Est:* 60 min · *Role:* Backend · *Owner:* config-registry · *ADR refs:* ADR-010

**Context.** Per ADR-010 the partner aggregate must support bitemporal queries (SCD Type 6 with current-flag). Adds the temporal columns, the partial-unique index for current rows, and a trigger that on UPDATE inserts a new row and supersedes the old.

**Steps.** Create V211__partner_bitemporal.sql; add columns `valid_from TIMESTAMPTZ NOT NULL DEFAULT now()`, `valid_to TIMESTAMPTZ NULL`, `recorded_at TIMESTAMPTZ NOT NULL DEFAULT now()`, `superseded_at TIMESTAMPTZ NULL`, `is_current BOOLEAN NOT NULL DEFAULT true`; partial unique index `CREATE UNIQUE INDEX uq_partner_current ON partners(partner_code) WHERE is_current=true`; trigger `trg_partner_bitemporal_update` BEFORE UPDATE that copies the OLD row with `superseded_at=now(), is_current=false` and lets the new row become current.

**Deliverable.** `services/config-registry/src/main/resources/db/migration/V211__partner_bitemporal.sql`

**Acceptance.**
- Updating legal_name_local on a partner row produces two rows (current and superseded) — verify via `select count(*) from partners where partner_code=?`
- findCurrent(partner_code) returns the latest row only (uq_partner_current enforces this)
- findAsOf(partner_code, validAt, recordedAt) returns the historical view that was current at recordedAt and valid at validAt
- Concurrent updates from two transactions: second commit rolls back on unique-index violation, never two current rows

### 21.1-P06 — Migration V212: change_request table + Spring State Machine wiring
*Slice:* **1** · *Est:* 75 min · *Role:* Backend · *Owner:* config-registry · *ADR refs:* ADR-008

**Context.** Per ADR-008 every partner mutation goes through a change_request approval object with a finite state machine: DRAFT -> PROPOSED -> APPROVED -> APPLIED, REJECTED as terminal. Self-approval is DB-rejected by CHECK constraint (proposer_id != approver_id).

**Steps.** Create V212__change_request.sql; columns id BIGSERIAL PK, aggregate_type VARCHAR(50), aggregate_id BIGINT, payload JSONB, state VARCHAR(20) CHECK IN (DRAFT,PROPOSED,APPROVED,REJECTED,APPLIED), proposer_id VARCHAR(120), approver_id VARCHAR(120) NULL, proposed_at TIMESTAMPTZ, approved_at TIMESTAMPTZ NULL, reason TEXT NULL, CHECK (approver_id IS NULL OR approver_id != proposer_id); index on (state, aggregate_type, aggregate_id); Spring State Machine bean `partnerChangeRequestStateMachine` with the 5-state graph and transition guards.

**Deliverable.** `services/config-registry/src/main/resources/db/migration/V212__change_request.sql; services/config-registry/src/main/java/com/gme/pay/config/changerequest/ChangeRequestStateMachineConfig.java; services/config-registry/.../ChangeRequestEntity.java; .../ChangeRequestService.java`

**Acceptance.**
- Self-approval (approver_id = proposer_id) raises 23514 CHECK violation
- State transitions follow the FSM strictly — REJECTED cannot move to APPROVED
- ChangeRequestService.propose creates DRAFT; .submit moves to PROPOSED; .approve to APPROVED; .apply to APPLIED
- Audit log row written on every state transition

### 21.1-P07 — audit_log infrastructure: dedicated audit DB + INSERT-only role + hash chain + lib-audit
*Slice:* **1** · *Est:* 120 min · *Role:* Backend · *Owner:* config-registry · *ADR refs:* ADR-007

**Context.** Per ADR-007 audit_log is a three-tier system: (1) Postgres `audit` DB with an INSERT-only role and a hash chain, (2) Kafka topic `gmepay.audit.partner` for downstream consumers, (3) `lib-audit` Java library that services use to emit rows. Hash chain: each row stores sha256(previous_hash || row_canonical_json).

**Steps.** Add docker-compose service `postgres-audit` (separate from `postgres-config`); Flyway baseline migration V001 creating `audit_log(id, occurred_at, actor_id, actor_type, action, entity_type, entity_id, before_value JSONB, after_value JSONB, prev_hash BYTEA, row_hash BYTEA, request_id)`; PG role `audit_writer` with `INSERT` only (no UPDATE/DELETE); PG rules `prevent_update_audit_log` and `prevent_delete_audit_log`; new module `lib-audit/src/main/java/com/gme/pay/audit/AuditWriter.java` computing the hash chain and emitting to both DB and Kafka in the same Spring @Transactional; topic `gmepay.audit.partner` created via `lib-events-kafka`.

**Deliverable.** `docker/compose/docker-compose.yml; services/config-registry/src/main/resources/db/migration/V001_audit__init.sql; lib-audit/build.gradle; lib-audit/src/main/java/com/gme/pay/audit/AuditWriter.java; lib-audit/src/main/java/com/gme/pay/audit/HashChain.java`

**Acceptance.**
- UPDATE on audit_log raises exception from rule
- DELETE on audit_log raises exception from rule
- Two consecutive rows: row2.prev_hash == row1.row_hash
- Tampering: direct UPDATE attempt detected by lib-audit verifyChain() — returns first divergent row id
- Kafka topic `gmepay.audit.partner` carries the same canonical JSON as the DB row

### 21.1-P08 — Partner aggregate identity fields: tax_id with type discriminator, legal_form, structured address, LEI
*Slice:* **1** · *Est:* 60 min · *Role:* Backend · *Owner:* config-registry · *ADR refs:* ADR-010

**Context.** Add the Slice 1 identity fields onto the bitemporal partner table (Migration V213). Per the audit, first-prod blockers in the Identity & legal theme: 6 fields. Tax ID has a type discriminator since KR/KH/VN/SG/generic have different formats and validation.

**Steps.** Create V213__partner_identity_fields.sql; add columns `legal_name_local VARCHAR(200)`, `legal_name_romanized VARCHAR(200)`, `tax_id VARCHAR(40)`, `tax_id_type VARCHAR(15) CHECK IN (KR_BRN,KH_VAT,VN_MST,SG_UEN,GENERIC)`, `country_of_incorporation CHAR(2)` (ISO-3166), `legal_form VARCHAR(20) CHECK IN (CORP,LLC,MTO,EMI,BANK,OTHER)`, `registered_street1`, `registered_street2`, `registered_city`, `registered_state`, `registered_postcode`, `registered_country CHAR(2)`, same six for `operating_*`, `lei CHAR(20) NULL` (ISO 17442); add unique constraint on (tax_id, tax_id_type) where both NOT NULL.

**Deliverable.** `services/config-registry/src/main/resources/db/migration/V213__partner_identity_fields.sql`

**Acceptance.**
- tax_id_type=KR_BRN with malformed BRN (not 10 digits) rejected by app-layer validator in PartnerAdminService
- country_of_incorporation must be ISO-3166 alpha-2 (e.g. KR not KOR) — CHECK regex `^[A-Z]{2}$`
- legal_form CHECK rejects unknown values like CORP_LTD
- LEI when present must match the ISO 17442 format `^[A-Z0-9]{20}$` — validator-layer check
- UNIQUE(tax_id, tax_id_type) prevents two partners claiming the same KR_BRN

### 21.1-P09 — Validate KR_BRN / KH_VAT / VN_MST / SG_UEN tax ID formats in PartnerAdminService
*Slice:* **1** · *Est:* 45 min · *Role:* Backend · *Owner:* config-registry · *ADR refs:* —

**Context.** Application-layer tax ID validator per the type discriminator on partner. KR_BRN: 10 digits with checksum. SG_UEN: 9 or 10 chars per IRAS spec. VN_MST: 10 or 13 digits. KH_VAT: 9 digits with prefix. Throws ConfigValidationException with codes per type.

**Steps.** Create `services/config-registry/src/main/java/com/gme/pay/config/validation/TaxIdValidator.java` with `validate(String taxId, String type)`; KR_BRN checksum: weights [1,3,7,1,3,7,1,3,5]; last digit = (10 - sum%10)%10; wire into PartnerAdminService.createPartner before persist; unit tests with vectors for each type.

**Deliverable.** `services/config-registry/src/main/java/com/gme/pay/config/validation/TaxIdValidator.java; services/config-registry/src/test/java/com/gme/pay/config/validation/TaxIdValidatorTest.java`

**Acceptance.**
- KR_BRN '1234567890' (bad checksum) raises ConfigValidationException code TAX_ID_KR_BRN_CHECKSUM
- KR_BRN '1208147521' (valid checksum) passes
- SG_UEN 'T18LL1234A' passes; 'TLL1234A' (too short) rejected
- VN_MST '0123456789' passes; '012345' rejected with code TAX_ID_VN_MST_LENGTH

### Slice 2 tickets owned by this service

### 21.2-P01 — Migration V215: partner_contact child table (bitemporal)
*Slice:* **2** · *Est:* 45 min · *Role:* Backend · *Owner:* config-registry · *ADR refs:* ADR-010

**Context.** Slice 2 — Contacts. partner_contact is a child of partners, bitemporal per ADR-010. Roles match the audit themes: OPS_24X7, FINANCE, COMPLIANCE_MLRO, TECH, LEGAL, INCIDENT.

**Steps.** V215__partner_contact.sql: id BIGSERIAL PK, partner_id BIGINT FK, role VARCHAR(20) CHECK IN (OPS_24X7,FINANCE,COMPLIANCE_MLRO,TECH,LEGAL,INCIDENT), name VARCHAR(200), email VARCHAR(320), phone_e164 VARCHAR(20), is_authorized_signatory BOOLEAN DEFAULT false, notes TEXT NULL; bitemporal columns (valid_from/to, recorded_at/superseded_at, is_current); partial unique index on (partner_id, role, email) WHERE is_current=true (prevents duplicate active contact); CHECK phone_e164 matches `^\+[1-9]\d{1,14}$`.

**Deliverable.** `services/config-registry/src/main/resources/db/migration/V215__partner_contact.sql`

**Acceptance.**
- E.164 CHECK rejects '01234567' (missing +)
- Two active contacts with same role+email rejected by partial unique index
- Bitemporal update on a contact produces two rows (current + superseded)
- FK to partners(partner_id) cascades on partner soft-delete (we never hard-delete a partner — see Slice 8 FSM)

### Slice 3 tickets owned by this service

### 21.3-P01 — Migration V216: partner_document table (bitemporal, with vault_uri + expiry tracking)
*Slice:* **3** · *Est:* 45 min · *Role:* Backend · *Owner:* config-registry · *ADR refs:* ADR-006, ADR-010

**Context.** Slice 3 — KYB. partner_document stores document metadata; bytes live in MinIO (Vault) per ADR-006 with object-lock compliance mode. doc_type enum covers the audit's KYB themes.

**Steps.** V216__partner_document.sql: id BIGSERIAL PK, partner_id BIGINT FK, doc_type VARCHAR(30) CHECK IN (LICENSE,CERT_INCORPORATION,AOA,BOARD_RESOLUTION,UBO_DECLARATION,FINANCIALS,CBDDQ,OTHER), vault_uri VARCHAR(512) NOT NULL, version INT NOT NULL DEFAULT 1, expiry_date DATE NULL, verified_by VARCHAR(120) NULL, verified_at TIMESTAMPTZ NULL; bitemporal columns; index on (partner_id, doc_type, is_current).

**Deliverable.** `services/config-registry/src/main/resources/db/migration/V216__partner_document.sql`

**Acceptance.**
- doc_type CHECK rejects UNKNOWN_DOC
- version >= 1 enforced
- Two LICENSE rows for same partner allowed (versions); only one is_current=true per (partner_id, doc_type) — partial unique index
- FK to partners enforces partner exists

### 21.3-P02 — Migration V217: partner_kyb table (risk rating, UBO, screening, Wolfsberg CBDDQ FK)
*Slice:* **3** · *Est:* 60 min · *Role:* Backend · *Owner:* config-registry · *ADR refs:* ADR-009, ADR-010

**Context.** Slice 3 — KYB record. Stores the operator's risk decision plus the UBO set as JSONB. Wolfsberg CBDDQ document reference is FK to partner_document.

**Steps.** V217__partner_kyb.sql: id, partner_id FK UNIQUE (one current row per partner), risk_rating VARCHAR(10) CHECK IN (LOW,MEDIUM,HIGH), risk_rationale TEXT, next_review_date DATE, license_type VARCHAR(50), license_number VARCHAR(80), license_authority VARCHAR(150), license_expiry DATE, ubo_set JSONB (array of {name,ownership_pct,is_pep,country}), wolfsberg_cbddq_doc_id BIGINT FK partner_document(id) NULL, screening_status VARCHAR(20) CHECK IN (PENDING,CLEAR,HIT,OVERRIDDEN), last_screened_at TIMESTAMPTZ NULL; bitemporal columns; CHECK that sum of ownership_pct in ubo_set <= 100.0 (app-layer assert, comment doc'd).

**Deliverable.** `services/config-registry/src/main/resources/db/migration/V217__partner_kyb.sql`

**Acceptance.**
- INSERT with risk_rating=UNKNOWN raises CHECK
- screening_status=HIT requires risk_rationale to be non-null (app validator)
- Sum of ubo ownership_pct > 100 rejected by KybAdminService.upsert
- wolfsberg_cbddq_doc_id FK must point at a partner_document with doc_type=CBDDQ

### 21.3-P08 — Publish gmepay.kyb.screening event in same transaction as partner_kyb upsert
*Slice:* **3** · *Est:* 60 min · *Role:* Backend · *Owner:* config-registry · *ADR refs:* ADR-007

**Context.** Outbox pattern: KYB result write + Kafka event published atomically. Consumers (reporting-compliance for KoFIU STR feed, admin-ui for UI refresh) listen on this topic.

**Steps.** Extend KybAdminService.recordScreening: within @Transactional, upsert partner_kyb row + INSERT into kyb_outbox(payload JSONB, published BOOLEAN); reuse OutboxPoller (21.1-P22 / 2.5-T22 pattern) to publish to topic `gmepay.kyb.screening`; payload schema: {partnerId, partnerCode, decision, hitCount, screenedAt, vendorRef}.

**Deliverable.** `services/config-registry/src/main/java/com/gme/pay/config/kyb/KybAdminService.java; services/config-registry/src/main/resources/db/migration/V218__kyb_outbox.sql`

**Acceptance.**
- Rolling back the partner_kyb upsert also rolls back the outbox INSERT
- OutboxPoller publishes the row to topic gmepay.kyb.screening within 5 seconds
- Event payload validates against a schema registered in lib-events-kafka
- Idempotent: same vendorRef twice produces only one DB row + event

### Slice 4 tickets owned by this service

### 21.4-P01 — Migration V219: partner_bank_account (multi-row, bitemporal, with verification tracking)
*Slice:* **4** · *Est:* 60 min · *Role:* Backend · *Owner:* config-registry · *ADR refs:* ADR-010

**Context.** Slice 4 — Banking. Bank accounts are multi-row per partner (one per ccy + purpose). Bitemporal. Verification status separates the operator's claim from KFTC's confirmation.

**Steps.** V219__partner_bank_account.sql: id, partner_id FK, currency CHAR(3), bank_name, bic_swift CHAR(11), iban_or_account_number VARCHAR(34), account_holder_name VARCHAR(200), bank_country CHAR(2), intermediary_bic CHAR(11) NULL, verification_status VARCHAR(20) CHECK IN (UNVERIFIED,KFTC_VERIFIED,BANK_LETTER,MICRO_DEPOSIT), verification_evidence_doc_id BIGINT FK partner_document NULL, verification_date TIMESTAMPTZ NULL, is_primary BOOLEAN DEFAULT false, swift_charge_bearer VARCHAR(3) CHECK IN (OUR,BEN,SHA), purpose VARCHAR(20) CHECK IN (PAYOUT,FLOAT_TOPUP,REFUND); bitemporal columns; partial unique index (partner_id, currency, purpose, is_primary) WHERE is_primary=true AND is_current=true.

**Deliverable.** `services/config-registry/src/main/resources/db/migration/V219__partner_bank_account.sql`

**Acceptance.**
- BIC/SWIFT CHECK enforces 8 or 11 chars (rendered as 11 with X padding)
- Two primary PAYOUT accounts in same ccy rejected by partial unique index
- verification_status=KFTC_VERIFIED requires verification_evidence_doc_id (app-layer)
- purpose enum rejects 'OTHER'

### 21.4-P02 — Migration V220: partner_settlement_config (cycle, cutoff, method per partner)
*Slice:* **4** · *Est:* 45 min · *Role:* Backend · *Owner:* config-registry · *ADR refs:* ADR-010

**Context.** Slice 4 — per-partner cycle, cutoff, settlement method. settlement-reconciliation reads this at txn settle time.

**Steps.** V220__partner_settlement_config.sql: partner_id BIGINT PK FK partners, cycle_t_plus_n INT CHECK BETWEEN 0 AND 7, cutoff_time TIME, cutoff_timezone VARCHAR(40), settlement_method VARCHAR(30) CHECK IN (SWIFT_MT103,KR_FIRM_BANKING,BAKONG,NAPAS_247,PROMPT_PAY,FAST_SG,OTHER); bitemporal columns.

**Deliverable.** `services/config-registry/src/main/resources/db/migration/V220__partner_settlement_config.sql`

**Acceptance.**
- cycle_t_plus_n=8 raises CHECK
- settlement_method=UNKNOWN raises CHECK
- cutoff_timezone validated against ZoneId enum app-layer
- PK on partner_id ensures one settlement config per partner

### 21.4-P03 — Migration V221 + seed: business_day_calendar (KR + ASEAN, 2026-2028)
*Slice:* **4** · *Est:* 60 min · *Role:* Backend · *Owner:* config-registry · *ADR refs:* —

**Context.** Holiday calendar drives settlement preview ('Mon 11:30 KST pays out Wed 11:30 KST after Chuseok roll'). Seed two years of KR holidays + Tet (VN), Songkran (TH), Chinese New Year (regional), Cambodia (KH) holidays.

**Steps.** V221__business_day_calendar.sql: id, country_code CHAR(2), holiday_date DATE, holiday_name VARCHAR(100), is_full_day BOOLEAN, source VARCHAR(40); index on (country_code, holiday_date); seed file with public holidays for KR/VN/TH/SG/KH/MY for 2026 + 2027 + 2028 (compiled from official labour ministry sources, cited in SQL comments).

**Deliverable.** `services/config-registry/src/main/resources/db/migration/V221__business_day_calendar.sql`

**Acceptance.**
- KR Chuseok 2026 (Sep 24-26) present
- VN Tet 2027 (Feb 6-12) present
- TH Songkran 2026 (Apr 13-15) present
- Index allows lookup in < 10ms

### Slice 5 tickets owned by this service

### 21.5-P01 — Migration V222: partner_prefunding_config (funding model, thresholds, credit limit)
*Slice:* **5** · *Est:* 60 min · *Role:* Backend · *Owner:* config-registry · *ADR refs:* ADR-010

**Context.** Slice 5 — Prefunding (OVERSEAS only). Extends prefunding service with per-partner config.

**Steps.** V222__partner_prefunding_config.sql: partner_id BIGINT PK FK partners, funding_model VARCHAR(15) CHECK IN (PREFUNDED,POSTPAID,HYBRID), opening_balance_usd NUMERIC(20,4), low_balance_threshold_usd NUMERIC(20,4) DEFAULT 10000, alert_tier_70_pct NUMERIC(20,4), alert_tier_85_pct NUMERIC(20,4), alert_tier_95_pct NUMERIC(20,4), credit_limit_usd NUMERIC(20,4) NULL, auto_suspend_on_breach BOOLEAN DEFAULT true, float_top_up_bank_account_id BIGINT FK partner_bank_account NULL, top_up_reference_pattern VARCHAR(80) DEFAULT 'GMP-{partner_code}-{yyyyMMdd}', collateral_amount_usd NUMERIC(20,4) NULL; bitemporal cols.

**Deliverable.** `services/config-registry/src/main/resources/db/migration/V222__partner_prefunding_config.sql`

**Acceptance.**
- funding_model CHECK rejects UNKNOWN
- tier_70 < tier_85 < tier_95 (app validator)
- FK to partner_bank_account requires purpose=FLOAT_TOPUP (app validator)
- top_up_reference_pattern template can interpolate partner_code + date

### Slice 6 tickets owned by this service

### 21.6-P01 — Migration V223: split settlement_currency into collection_ccy + settle_a_ccy (Expand)
*Slice:* **6** · *Est:* 60 min · *Role:* Backend · *Owner:* config-registry · *ADR refs:* ADR-013

**Context.** Slice 6 — Commercial. Per DAT-03 §4.3 / PRD-07 §5.3.2 the single settlement_currency must split into collection_ccy (what we charge the end-customer) and settle_a_ccy (what we pay the partner in). Expand phase per ADR-013.

**Steps.** V223__partner_ccy_split.sql: add `collection_ccy CHAR(3)` and `settle_a_ccy CHAR(3)` columns to partners; backfill `collection_ccy = settle_a_ccy = settlement_currency`; CHECK both match `^[A-Z]{3}$`; leave settlement_currency column intact this release.

**Deliverable.** `services/config-registry/src/main/resources/db/migration/V223__partner_ccy_split.sql`

**Acceptance.**
- Backfill: every partner row has matching collection_ccy and settle_a_ccy after the migration
- CHECK rejects collection_ccy='krw' (must be uppercase)
- Existing read-side code paths still function reading settlement_currency
- V224 (Backfill verify) and V225 (Contract: drop old col) exist as planned future migrations

### 21.6-P02 — Rule CRUD: POST /v1/rules + GET /v1/rules + PUT /v1/rules/{id} (was: validate-only)
*Slice:* **6** · *Est:* 90 min · *Role:* Backend · *Owner:* config-registry · *ADR refs:* —

**Context.** Today only POST /v1/rules/validate exists. Slice 6 makes rules first-class CRUD. PUT goes through change_request (4-eyes).

**Steps.** Add to ConfigRegistryRestController POST /internal/v1/config/rules (creates DRAFT rule); GET /internal/v1/config/rules?partnerId=X (lists); PUT /internal/v1/config/rules/{id} (proposes change_request, requires approval to apply); reuse the existing m_a + m_b >= 0.02 invariant from RuleAdminService.

**Deliverable.** `services/config-registry/src/main/java/com/gme/pay/config/api/ConfigRegistryRestController.java; services/config-registry/.../RuleAdminService.java`

**Acceptance.**
- POST creates a Rule in DRAFT with status enforced
- PUT inserts a change_request with state=PROPOSED, payload=new Rule snapshot
- PUT body that would break m_a+m_b >= 2% returns 422 before even creating the CR
- GET lists rules sorted by (scheme_id, direction) for stable UI rendering

### 21.6-P03 — Migration V224: partner_fee_schedule (per partner x scheme x direction with tier table)
*Slice:* **6** · *Est:* 60 min · *Role:* Backend · *Owner:* config-registry · *ADR refs:* ADR-010

**Context.** Per the audit the commercial-terms theme covers fees (fixed + bps + tiered). Tier table is a child of fee schedule.

**Steps.** V224__partner_fee_schedule.sql: id, partner_id FK, scheme_id FK qr_scheme, direction CHECK IN (INBOUND,OUTBOUND,DOMESTIC,HUB), fixed_fee NUMERIC(20,4), bps_fee NUMERIC(8,4), currency CHAR(3); bitemporal; UNIQUE(partner_id, scheme_id, direction, is_current); child table `partner_fee_tier` with min_volume_usd, max_volume_usd, bps_fee_override.

**Deliverable.** `services/config-registry/src/main/resources/db/migration/V224__partner_fee_schedule.sql`

**Acceptance.**
- bps_fee 100.5000 stores and retrieves as NUMERIC(8,4)
- Tier ranges may not overlap (app validator) for the same fee schedule
- Direction CHECK rejects UNKNOWN
- FK to scheme enforces scheme exists

### 21.6-P04 — Migration V225: partner_fx_config (margin_bps, reference source, quote hold)
*Slice:* **6** · *Est:* 45 min · *Role:* Backend · *Owner:* config-registry · *ADR refs:* ADR-010

**Context.** FX margin is bitemporal. reference_rate_source enum drives which feed rate-fx queries.

**Steps.** V225__partner_fx_config.sql: partner_id PK FK partners, margin_bps NUMERIC(8,4), reference_rate_source VARCHAR(30) CHECK IN (SEOUL_FX_BROKER,PARTNER_PROVIDED,MID_MARKET), quote_hold_seconds INT CHECK BETWEEN 60 AND 1800 DEFAULT 300; bitemporal.

**Deliverable.** `services/config-registry/src/main/resources/db/migration/V225__partner_fx_config.sql`

**Acceptance.**
- quote_hold_seconds=59 raises CHECK
- reference_rate_source=UNKNOWN raises CHECK
- Bitemporal update: changing margin_bps creates new current row + superseded old
- Default 300 honored when column omitted on INSERT

### 21.6-P05 — Migration V226: partner_limits (per-txn, daily, monthly, annual caps + 소액해외송금업 enforcement)
*Slice:* **6** · *Est:* 60 min · *Role:* Backend · *Owner:* config-registry · *ADR refs:* —

**Context.** Caps per partner. 소액해외송금업 (small foreign-currency transfer license) hard-enforces per_txn ≤ 5000 USD and annual ≤ 50000 USD per end-user (Korea regulation).

**Steps.** V226__partner_limits.sql: partner_id PK FK, per_txn_min_usd NUMERIC, per_txn_max_usd NUMERIC, daily_cap_usd NUMERIC, monthly_cap_usd NUMERIC, annual_cap_usd NUMERIC, end_user_annual_cap_usd NUMERIC NULL; bitemporal; CHECK per_txn_max >= per_txn_min; CHECK daily <= monthly <= annual.

**Deliverable.** `services/config-registry/src/main/resources/db/migration/V226__partner_limits.sql`

**Acceptance.**
- per_txn_max=4000 with per_txn_min=5000 raises CHECK
- Setting monthly_cap < daily_cap raises CHECK
- Partners with license_type='SMALL_FX_TRANSFER' must have per_txn_max_usd ≤ 5000 (app validator)
- annual_cap_usd ≤ 50000 enforced for those partners

### 21.6-P06 — Migration V227: partner_contract (dates, refund/chargeback policy, auto-renewal)
*Slice:* **6** · *Est:* 45 min · *Role:* Backend · *Owner:* config-registry · *ADR refs:* ADR-010

**Context.** Contract dates gate every txn. Per the audit, txns outside contract.effective_to are refused.

**Steps.** V227__partner_contract.sql: id, partner_id FK, effective_from DATE NOT NULL, effective_to DATE NULL, auto_renewal BOOLEAN DEFAULT false, notice_period_days INT DEFAULT 30, refund_chargeback_policy VARCHAR(20) CHECK IN (PARTNER_BEARS,MERCHANT_BEARS,SHARED), termination_reason TEXT NULL; bitemporal; CHECK effective_to IS NULL OR effective_to > effective_from.

**Deliverable.** `services/config-registry/src/main/resources/db/migration/V227__partner_contract.sql`

**Acceptance.**
- effective_to before effective_from raises CHECK
- refund_chargeback_policy CHECK rejects UNKNOWN
- Only one current contract per partner (partial unique index on partner_id where is_current=true)
- Auto-renewal flag stored as boolean

### Slice 7 tickets owned by this service

### 21.7-P01 — Migration V228: partner_scheme (enablement matrix + ZeroPay specifics + D/I derivation)
*Slice:* **7** · *Est:* 60 min · *Role:* Backend · *Owner:* config-registry · *ADR refs:* ADR-010

**Context.** Slice 7 — per-partner scheme enablement. Per the audit Zp0011 partnerType D/I must be derived from per-partner config (not a global flag). Stores merchantId, sub-merchantId, institution code, role, direction, approval method.

**Steps.** V228__partner_scheme.sql: id, partner_id FK, scheme_id FK, direction CHECK IN (INBOUND,OUTBOUND,BOTH), role CHECK IN (ACQUIRER,ISSUER,BOTH), zeropay_merchant_id VARCHAR(50), zeropay_sub_merchant_id VARCHAR(50), kftc_institution_code VARCHAR(20), partner_type_char CHAR(1) CHECK IN ('D','I'), approval_method_cpm VARCHAR(15) CHECK IN (CONFIRMATION,SILENT), approval_method_mpm VARCHAR(15) CHECK IN (CONFIRMATION,SILENT), vault_secret_id VARCHAR(120) NULL; bitemporal; UNIQUE(partner_id, scheme_id, is_current).

**Deliverable.** `services/config-registry/src/main/resources/db/migration/V228__partner_scheme.sql`

**Acceptance.**
- partner_type_char='X' raises CHECK
- approval_method_cpm CHECK rejects UNKNOWN
- UNIQUE prevents two active rows for same (partner,scheme)
- vault_secret_id is a logical pointer; bytes never in DB

### 21.7-P02 — Migration V229: partner_corridor (src/dst country+ccy enablement matrix)
*Slice:* **7** · *Est:* 45 min · *Role:* Backend · *Owner:* config-registry · *ADR refs:* ADR-010

**Context.** Per the audit corridors are 5-blocker theme. KR->KH or KR->VN matrix. go_live_date + is_active gate the gateway routing.

**Steps.** V229__partner_corridor.sql: id, partner_id FK, src_country CHAR(2), src_ccy CHAR(3), dst_country CHAR(2), dst_ccy CHAR(3), go_live_date DATE, is_active BOOLEAN DEFAULT false; bitemporal; UNIQUE(partner_id, src_country, src_ccy, dst_country, dst_ccy, is_current).

**Deliverable.** `services/config-registry/src/main/resources/db/migration/V229__partner_corridor.sql`

**Acceptance.**
- UNIQUE prevents two active rows for same corridor
- go_live_date in the past + is_active=true allowed (back-dated activation)
- is_active default false (operator must explicitly enable)
- CHECK on country codes ISO-3166 format

### Slice 8 tickets owned by this service

### 21.8-P06 — Full FSM: Draft -> KYB-Pending -> KYB-Approved -> Contract-Signed -> Sandbox -> UAT -> Live -> Suspended -> Terminated
*Slice:* **8** · *Est:* 120 min · *Role:* Backend · *Owner:* config-registry · *ADR refs:* ADR-008

**Context.** Per the plan §Slice 8 the lifecycle FSM has 9 states. Each transition is change_request-gated. Go-Live and Suspend require 4-eyes.

**Steps.** Add `partner_status VARCHAR(20) CHECK IN (DRAFT,KYB_PENDING,KYB_APPROVED,CONTRACT_SIGNED,SANDBOX,UAT,LIVE,SUSPENDED,TERMINATED)` to partners (V234); replace existing 4-state enum; Spring State Machine bean `partnerLifecycleStateMachine` with explicit transition guards (e.g. KYB_APPROVED requires partner_kyb.screening_status=CLEAR); 4-eyes guard on Live and Suspend transitions.

**Deliverable.** `services/config-registry/src/main/resources/db/migration/V234__partner_status_fsm.sql; services/config-registry/src/main/java/com/gme/pay/config/partner/PartnerLifecycleStateMachineConfig.java`

**Acceptance.**
- Transition Draft -> Live in one shot is rejected (must walk the path)
- Transition to Live without 4-eyes approver returns 403
- Transition to KYB_APPROVED with screening_status=HIT (not overridden) returns 422
- Terminated is terminal (no transitions out)

### 21.8-P07 — Activation pre-condition gate: returns 422 with unmet[] until all gates satisfied
*Slice:* **8** · *Est:* 90 min · *Role:* Backend · *Owner:* config-registry · *ADR refs:* —

**Context.** Single source of truth for 'is this partner ready to go live'. UI consumes the `unmet[]` to highlight pending steps.

**Steps.** Add `ActivationGate.evaluate(Long partnerId): GateResult` to config-registry; checks: legal_name set, partner_kyb.screening_status IN (CLEAR,OVERRIDDEN), ≥1 verified bank account per settle_a_ccy, current contract not in past, prefunding row exists if OVERSEAS, ≥4 role contacts (OPS_24X7+FINANCE+COMPLIANCE_MLRO+TECH), ≥1 active scheme, sanctions clear; expose via GET /v1/admin/partners/{id}/activation-gate.

**Deliverable.** `services/config-registry/src/main/java/com/gme/pay/config/partner/ActivationGate.java`

**Acceptance.**
- Partial config returns 422 with unmet:[{code:MISSING_BANK_ACCOUNT,detail:settle_a_ccy=USD},...]
- All gates satisfied returns 200 with ready:true
- GateResult is the same shape for the activation transition attempt as for the gate endpoint
- OVERSEAS without prefunding row returns code MISSING_PREFUNDING

### 21.8-P08 — Post-activation immutability: lock partner_code + country_of_incorporation + partner_type + ccys
*Slice:* **8** · *Est:* 45 min · *Role:* Backend · *Owner:* config-registry · *ADR refs:* —

**Context.** After first Live transition these fields are immutable. Per ADR-008 attempts return 400 IMMUTABLE_AFTER_ACTIVATION.

**Steps.** Add `ImmutabilityGuard.java @Component` to config-registry intercepting PartnerCommand.Update; if partner.first_live_at IS NOT NULL and the diff includes any of the locked fields, throw ImmutableFieldException; add column `first_live_at TIMESTAMPTZ NULL` (V235) set on the LIVE transition.

**Deliverable.** `services/config-registry/src/main/java/com/gme/pay/config/partner/ImmutabilityGuard.java; services/config-registry/src/main/resources/db/migration/V235__first_live_at.sql`

**Acceptance.**
- Update on partner_code post-live returns 400 with code IMMUTABLE_AFTER_ACTIVATION
- Update on legal_name_local post-live succeeds (not in the immutable set)
- first_live_at is set exactly once (on first LIVE transition; further LIVE re-entries don't update it)
- Audit log records the rejected attempt

### 21.8-P09 — Suspension reason codes enum + audit linkage
*Slice:* **8** · *Est:* 30 min · *Role:* Backend · *Owner:* config-registry · *ADR refs:* —

**Context.** Per the plan suspension must carry a structured reason. Enum: LIMIT_BREACH, SANCTIONS_HIT, CREDENTIAL_COMPROMISE, KYB_LAPSED, CONTRACT_EXPIRED, OPERATOR_INITIATED.

**Steps.** Add `suspension_reason VARCHAR(30) CHECK IN (LIMIT_BREACH,SANCTIONS_HIT,CREDENTIAL_COMPROMISE,KYB_LAPSED,CONTRACT_EXPIRED,OPERATOR_INITIATED), suspension_at TIMESTAMPTZ NULL, suspension_by VARCHAR(120) NULL` to partners (V236); FSM Suspend transition requires reason; lift requires operator approval + new reason for the lift.

**Deliverable.** `services/config-registry/src/main/resources/db/migration/V236__suspension_reason.sql`

**Acceptance.**
- Suspend without reason returns 422
- Lift transition records the reason on the audit_log row
- Reason enum CHECK rejects UNKNOWN
- FSM transition guards verify reason matches the trigger

### 21.8-P10 — BOK 외환거래보고 attributes on partner aggregate
*Slice:* **8** · *Est:* 45 min · *Role:* Backend · *Owner:* config-registry · *ADR refs:* —

**Context.** BOK FX-trade reporting requires partner-level fields. Per the plan §Slice 8: bok_txn_code, bok_fx_reporting_category, bok_remitter_type. OI-03 (BOK code mapping) listed as open in WBS — this ticket creates the columns + null-tolerant validator; mapping comes from BOK source per OI-03.

**Steps.** V237__partner_bok_fields.sql: bok_txn_code VARCHAR(10) NULL, bok_fx_reporting_category VARCHAR(30) CHECK IN (INDIVIDUAL_AGGREGATE,INSTITUTIONAL) NULL, bok_remitter_type VARCHAR(30) NULL; CHECK that when fx_reporting_category IS NOT NULL the txn_code is also set; reporting-compliance reads these on report generation.

**Deliverable.** `services/config-registry/src/main/resources/db/migration/V237__partner_bok_fields.sql`

**Acceptance.**
- INSERT with category set + txn_code null raises CHECK
- category=UNKNOWN raises CHECK
- reporting-compliance reads these without NullPointerException when null
- BOK report generation routes by fx_reporting_category

### 21.8-P11 — Hometax e-tax-invoice settings on partner aggregate
*Slice:* **8** · *Est:* 45 min · *Role:* Backend · *Owner:* config-registry · *ADR refs:* —

**Context.** Hometax requires per-partner issuer cert + VAT treatment. issuer cert lives in lib-vault.

**Steps.** V238__partner_hometax_fields.sql: hometax_issuer_cert_id BIGINT FK partner_document NULL (doc_type=HOMETAX_CERT), vat_treatment VARCHAR(20) CHECK IN (ZERO_RATED_EXPORT,STANDARD,EXEMPT) NULL; reporting-compliance reads these for invoice generation.

**Deliverable.** `services/config-registry/src/main/resources/db/migration/V238__partner_hometax_fields.sql`

**Acceptance.**
- vat_treatment=UNKNOWN raises CHECK
- FK to partner_document constrained to doc_type=HOMETAX_CERT (app validator)
- Hometax invoice generation throws HOMETAX_CONFIG_MISSING when null on settlement
- Operator can set these via step-8 patch

### 21.8-P13 — PIPA cross-border PII transfer notice config
*Slice:* **8** · *Est:* 45 min · *Role:* Backend · *Owner:* config-registry · *ADR refs:* —

**Context.** PIPA requires partners to declare which jurisdictions PII may move to, plus a legal-basis code.

**Steps.** V240__partner_pipa_fields.sql: pipa_jurisdiction_allowlist VARCHAR(2)[] NOT NULL DEFAULT '{}', pipa_legal_basis_code VARCHAR(20) CHECK IN (CONSENT,CONTRACT_PERFORMANCE,LEGAL_OBLIGATION,VITAL_INTEREST,PUBLIC_INTEREST,LEGITIMATE_INTEREST) NULL; PII export hook rejects txn destinations not in allowlist.

**Deliverable.** `services/config-registry/src/main/resources/db/migration/V240__partner_pipa_fields.sql`

**Acceptance.**
- Txn to country not in allowlist returns 422 PII_JURISDICTION_NOT_ALLOWED
- Empty allowlist + LIVE partner returns 422 PIPA_NOT_CONFIGURED on first txn
- legal_basis_code=UNKNOWN raises CHECK
- Allowlist update requires 4-eyes approval

### 21.8-P14 — Travel Rule (IVMS101) endpoint config + ≥KRW 1M originator+beneficiary enforcement
*Slice:* **8** · *Est:* 75 min · *Role:* Backend · *Owner:* config-registry · *ADR refs:* —

**Context.** FATF Travel Rule. Per the plan, ≥1M KRW must include IVMS101 originator + beneficiary blocks.

**Steps.** V241__partner_travel_rule.sql: travel_rule_protocol VARCHAR(30) CHECK IN (TRP,SYGNA,IVMS101_DIRECT,NONE) DEFAULT 'NONE', travel_rule_endpoint VARCHAR(512) NULL, travel_rule_pubkey TEXT NULL; transaction-mgmt enforces presence of ivms101_block on txn amount * usd_krw >= 1M; reporting-compliance forwards block to the configured endpoint.

**Deliverable.** `services/config-registry/src/main/resources/db/migration/V241__partner_travel_rule.sql; services/transaction-mgmt/src/main/java/com/gme/pay/txn/TravelRuleEnforcer.java`

**Acceptance.**
- Txn ≥1M KRW without ivms101_block returns 422 TRAVEL_RULE_MISSING
- Protocol=NONE allows the txn (warns instead of blocks) for grandfathered partners
- IVMS101 block schema-validated (originator + beneficiary present)
- Forwarding to TRP endpoint signed with travel_rule_pubkey

### Cross-service contributions touching this service

Tickets owned elsewhere but with code or schema touchpoints in this service. Listed here so this bundle remains the single read for a service developer.

- **21.1-P14** (auth-identity, Slice 1) — Keycloak OAuth2 resource-server config on api-gateway and config-registry (retire password=demo)
- **21.5-P02** (prefunding, Slice 5) — Wire PartnerBalanceEntity into partner-create transaction (BFF orchestration)
- **21.5-P03** (prefunding, Slice 5) — Prefunding: tier alert publisher (70/85/95% + auto-suspend on breach)
- **21.6-P07** (transaction-mgmt, Slice 6) — transaction-mgmt: read collection_ccy / settle_a_ccy + enforce partner_limits
- **21.6-P08** (rate-fx, Slice 6) — rate-fx: read partner_fx_config + apply margin_bps + honor reference source
- **21.6-P09** (revenue-ledger, Slice 6) — revenue-ledger: evaluate partner_fee_schedule + tier table
- **21.7-P03** (scheme-adapter-zeropay, Slice 7) — scheme-adapter-zeropay: rewrite SchemeRouter from hardcoded to data-driven
- **21.7-P04** (merchant-qr-data, Slice 7) — merchant-qr-data: derive Zp0011 partnerType D/I from partner_scheme.partner_type_char
- **21.8-P02** (auth-identity, Slice 8) — partner_ip_allowlist table + api-gateway pre-signature 403 enforcement
- **21.8-P12** (reporting-compliance, Slice 8) — KoFIU STR/CTR feed config on partner + reporting-compliance consumer
- **21.8-P17** (ops-partner-bff, Slice 8) — BFF endpoint PATCH /v1/admin/partners/draft/{id}/step-8 + activation orchestration

