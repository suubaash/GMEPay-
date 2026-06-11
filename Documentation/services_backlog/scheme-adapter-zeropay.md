# scheme-adapter-zeropay  (backend)

**Scope:** ACL adapter: ZeroPay REST+SFTP, ZP00xx files, KFTC cert

**Owned WBS work-packages:** 9.1, 9.2, 9.4, 9.5, 9.6, 9.7, 9.9, 9.10  ·  **Tickets:** 234  ·  **Est:** 157.8h

## Service contract (MSA: own DB, API-only communication)

- **Datastore (owned by this service):** Object storage (SFTP files)
- **APIs / events I EXPOSE:** /internal/scheme/zeropay/submit; SFTP batch; events scheme.result
- **APIs / events I CONSUME:** merchant-qr-data (sync); emits results to payment-executor via event
- **Integration rule:** never read another service's database or import its private entities — call its API or consume its event; stub consumed services with WireMock in tests.

> Self-contained backlog for this service. Build it as its own repo/module with its own DB + Flyway migrations, against the `shared-libs` contracts (lib-money / lib-errors / lib-events / lib-api-contracts only). Each ticket has a deliverable + acceptance checks.


## WBS 9.1 — Scheme Adapter abstraction
### 9.1-T01 — Define SchemeAdapter Java interface in services/scheme-adapter  _(30 min)_
**Context:** WBS 9.1 — Scheme Adapter ACL. The Hub Core must never hold scheme-specific logic; all scheme protocols are hidden behind a fixed Java interface. SAD-02 §5.3 defines the logical contract: authoriseCpm, submitMpm, generateBatchFiles, parseBatchFile, processMerchantSync, healthCheck. SCH-06 §1.3.1 expands to: parseMerchantQR, prepareCPM, commitPayment, cancelPayment, generatePaymentResultFile, generateRefundResultFile, generateSettlementRequestFile, parseInboundFile, validateInboundFile, transferOutbound, fetchInbound, getSupportedFiletypes, getSchemeConfig. The Transaction Orchestrator calls only this interface; it never references ZeroPay or any scheme class directly.
**Steps:** Create Gradle module services/scheme-adapter if not present; add to root settings.gradle; Define interface SchemeAdapter in services/scheme-adapter/src/main/java/com/gme/pay/scheme/adapter/SchemeAdapter.java with all 13 method signatures from SCH-06 §1.3.1; Define all supporting record/DTO types (CpmAuthRequest, CpmAuthResponse, MpmSubmitRequest, MpmSubmitResponse, BatchFile, BatchType, BatchRecord, SyncResult, AdapterHealth, MerchantIdentifier, PrepareToken, SchemeResult, CancelResult, TransferResult, FetchResult, FileTypeConfig, SchemeConfig) as Java records or sealed classes in the same package; Annotate the interface with Javadoc; no Spring annotations on the interface itself; Add lib-errors and lib-money as compileOnly dependencies in module build.gradle
**Deliverable:** services/scheme-adapter/src/main/java/com/gme/pay/scheme/adapter/SchemeAdapter.java plus all supporting types in com.gme.pay.scheme.adapter.model
**Acceptance / logic checks:**
- Interface compiles with Java 21 and zero Spring dependencies (pure Java contract)
- All 13 method signatures present and match SCH-06 §1.3.1 capability groups
- Every DTO uses BigDecimal from lib-money for monetary fields; no primitive double
- Interface has no @Component or @Service — wiring is done by implementations
- Module builds with ./gradlew :services:scheme-adapter:compileJava without errors

### 9.1-T02 — Define SchemeConfig and FileTypeConfig value objects with validation  _(35 min)_
**Context:** SCH-06 §1.3.2 defines SchemeConfig fields: scheme_id, scheme_name, operator_name, payout_currency, supported_modes (MPM/CPM), supported_countries (ISO 3166-1), realtime_api_base_url, sftp_host, sftp_port (default 22), sftp_username, sftp_private_key_ref, pgp_public_key_ref, pgp_private_key_ref, inbound_dir, outbound_dir, file_retention_days, merchant_fee_table (JSON, merchant_type to rate%), van_fee_table, gme_fee_share_pct. FileTypeConfig holds: file_id (e.g. ZP0011), direction (OUTBOUND/INBOUND), nominal_kst_time, retention_days. All credential fields are references into secrets store — never plaintext.
**Steps:** Create SchemeConfig as a Java record in services/scheme-adapter/src/main/java/com/gme/pay/scheme/adapter/model/SchemeConfig.java; Add Bean Validation (@NotBlank, @NotNull, @Min, @Max, @Pattern) to all fields; sftp_port @Min(1) @Max(65535); gme_fee_share_pct @DecimalMin(0.0) @DecimalMax(100.0); Create FileTypeConfig record with fields: fileId, direction (enum INBOUND/OUTBOUND/BOTH), nominalKstTime (LocalTime), retentionDays; Create enum SupportedMode { MPM, CPM } and enum PaymentDirection { INBOUND, OUTBOUND, DOMESTIC, HUB }; Add a static factory SchemeConfig.fromJson(String) using Jackson; merchant_fee_table and van_fee_table deserialise to Map<String,BigDecimal>
**Deliverable:** services/scheme-adapter/src/main/java/com/gme/pay/scheme/adapter/model/SchemeConfig.java and FileTypeConfig.java plus SupportedMode.java and related enums
**Acceptance / logic checks:**
- SchemeConfig.fromJson rejects JSON missing required field scheme_id with ConstraintViolationException
- sftp_port defaults to 22 when absent from JSON
- merchant_fee_table values are BigDecimal (not double) after deserialisation
- gme_fee_share_pct = 70 round-trips correctly through JSON
- FileTypeConfig nominalKstTime parses HH:mm string to LocalTime
**Depends on:** 9.1-T01

### 9.1-T03 — Create SchemeAdapterRegistry Spring @Service for adapter lookup by scheme_id  _(25 min)_
**Context:** The Smart Router resolves the correct SchemeAdapter at runtime using scheme_id (e.g. ZEROPAY). SAD-02 §5.3: the Config/Registry component holds scheme registrations. The registry must be a Spring @Service that accepts all SchemeAdapter @Bean implementations via constructor injection (Spring collects them as a List<SchemeAdapter>). Each adapter exposes its scheme_id via getSchemeConfig(). On startup, the registry indexes adapters by scheme_id and fails fast if duplicate scheme_ids are registered.
**Steps:** Create SchemeAdapterRegistry in services/scheme-adapter/src/main/java/com/gme/pay/scheme/adapter/SchemeAdapterRegistry.java annotated @Service; Inject List<SchemeAdapter> adapters in constructor; build a Map<String,SchemeAdapter> index keyed on adapter.getSchemeConfig().schemeId(); Add method SchemeAdapter resolve(String schemeId) throwing SchemeAdapterNotFoundException (from lib-errors) when not found; Add method List<String> registeredSchemeIds() for health/ops use; On @PostConstruct validate no duplicate schemeId entries; throw IllegalStateException with message listing the duplicate if found
**Deliverable:** services/scheme-adapter/src/main/java/com/gme/pay/scheme/adapter/SchemeAdapterRegistry.java
**Acceptance / logic checks:**
- resolve(ZEROPAY) returns the registered ZeroPaySchemeAdapter bean
- resolve(UNKNOWN) throws SchemeAdapterNotFoundException
- Duplicate registration of the same schemeId on startup throws IllegalStateException naming the duplicate
- registeredSchemeIds() returns exactly the count of injected adapters
- Registry is a Spring singleton (@Service scope = default)
**Depends on:** 9.1-T01

### 9.1-T04 — Define ZeroPaySchemeAdapter skeleton @Service with @ConditionalOnProperty guard  _(30 min)_
**Context:** ZeroPay is the first concrete adapter. Its class must implement SchemeAdapter and be a Spring @Service so it is auto-discovered by SchemeAdapterRegistry. The bean must be guarded by @ConditionalOnProperty(name=adapter.zeropay.enabled, havingValue=true) so it can be disabled for test slices. In Phase 1 all real-time and batch method bodies throw UnsupportedOperationException with TODO markers; the interface contract is the deliverable, not live ZeroPay calls.
**Steps:** Create services/scheme-adapter/src/main/java/com/gme/pay/scheme/adapter/zeropay/ZeroPaySchemeAdapter.java annotated @Service @ConditionalOnProperty(name=adapter.zeropay.enabled, havingValue=true, matchIfMissing=false); Implement SchemeAdapter; getSchemeConfig() loads config from application context (injected ZeroPayAdapterProperties @ConfigurationProperties); Implement healthCheck() to return AdapterHealth.UP stub; All other methods throw UnsupportedOperationException(TODO: ZeroPay Phase 2 - <method name>) with method name interpolated; Create ZeroPayAdapterProperties @ConfigurationProperties(prefix=adapter.zeropay) binding to SchemeConfig fields
**Deliverable:** services/scheme-adapter/src/main/java/com/gme/pay/scheme/adapter/zeropay/ZeroPaySchemeAdapter.java and ZeroPayAdapterProperties.java
**Acceptance / logic checks:**
- With adapter.zeropay.enabled=true the bean is present in context; with false it is absent
- getSchemeConfig().schemeId() returns ZEROPAY
- healthCheck() returns AdapterHealth with status UP
- parseMerchantQR() throws UnsupportedOperationException with message containing TODO
- Module compiles and Spring context loads without a live SFTP or HTTP connection
**Depends on:** 9.1-T02, 9.1-T03

### 9.1-T05 — Flyway migration V900__scheme_adapter_config.sql for scheme_config table  _(30 min)_
**Context:** All SchemeConfig parameters are stored in PostgreSQL (money path, per STACK.md). The scheme_config table persists one row per registered scheme. Credential fields (sftp_private_key_ref, pgp_public_key_ref, pgp_private_key_ref) store only Vault/KMS references — never plaintext. merchant_fee_table and van_fee_table are stored as JSONB. gme_fee_share_pct is NUMERIC(5,4). The table lives in the shared config database accessed by Config and Registry service (Gradle module services/config-registry).
**Steps:** Create db/migration/V900__scheme_adapter_config.sql in services/config-registry/src/main/resources/db/migration/; Define scheme_config table with columns: id UUID PK default gen_random_uuid(), scheme_id VARCHAR(20) UNIQUE NOT NULL, scheme_name VARCHAR(100) NOT NULL, operator_name VARCHAR(100), payout_currency CHAR(3) NOT NULL, supported_modes TEXT[] NOT NULL, supported_countries TEXT[] NOT NULL, realtime_api_base_url VARCHAR(500), sftp_host VARCHAR(255), sftp_port INT DEFAULT 22, sftp_username VARCHAR(100), sftp_private_key_ref VARCHAR(255), pgp_public_key_ref VARCHAR(255), pgp_private_key_ref VARCHAR(255), inbound_dir VARCHAR(500), outbound_dir VARCHAR(500), file_retention_days INT NOT NULL DEFAULT 90, merchant_fee_table JSONB, van_fee_table JSONB, gme_fee_share_pct NUMERIC(5,4), status VARCHAR(20) NOT NULL DEFAULT ACTIVE, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(); Add index: CREATE UNIQUE INDEX ux_scheme_config_scheme_id ON scheme_config(scheme_id); Insert seed row for ZeroPay with status=INACTIVE (activated by Ops); Add trigger updated_at_trigger on scheme_config using a shared function set_updated_at()
**Deliverable:** services/config-registry/src/main/resources/db/migration/V900__scheme_adapter_config.sql
**Acceptance / logic checks:**
- Flyway applies migration without error on a fresh PostgreSQL 16 Testcontainers instance
- UNIQUE constraint on scheme_id rejects a duplicate INSERT
- gme_fee_share_pct NUMERIC(5,4) stores 0.7000 (70%) without rounding
- sftp_private_key_ref column is VARCHAR not TEXT — prevents accidental plaintext key storage
- ZeroPay seed row has status=INACTIVE and merchant_fee_table is valid JSONB

### 9.1-T06 — Flyway migration V901__file_type_config.sql for batch file schedule table  _(25 min)_
**Context:** Each scheme has a configurable set of batch file types with direction, nominal KST time, and retention rules (SCH-06 §7.3 and §8.1). A separate file_type_config table holds one row per (scheme_id, file_id). ZeroPay has 16 file types: ZP0011-ZP0066 per SCH-06 §7.3 schedule. The batch scheduler reads this table to know what to generate and when to poll.
**Steps:** Create db/migration/V901__file_type_config.sql in services/config-registry/src/main/resources/db/migration/; Define file_type_config: id UUID PK, scheme_id VARCHAR(20) NOT NULL FK scheme_config(scheme_id), file_id VARCHAR(10) NOT NULL, direction VARCHAR(10) NOT NULL CHECK (direction IN (OUTBOUND,INBOUND)), nominal_kst_time TIME, is_full_sync BOOLEAN NOT NULL DEFAULT FALSE, frequency VARCHAR(20) NOT NULL DEFAULT DAILY CHECK (frequency IN (DAILY,WEEKLY,ON_DEMAND)), retention_days INT NOT NULL DEFAULT 90, enabled BOOLEAN NOT NULL DEFAULT TRUE, UNIQUE(scheme_id, file_id); INSERT all 16 ZeroPay file type rows: ZP0011 OUTBOUND 02:00, ZP0012 INBOUND 05:00, ZP0021 OUTBOUND 02:00, ZP0022 INBOUND 05:00, ZP0041 INBOUND NULL DAILY, ZP0043 INBOUND NULL DAILY, ZP0045 INBOUND NULL DAILY, ZP0047 INBOUND NULL DAILY, ZP0051 INBOUND NULL WEEKLY is_full_sync=TRUE, ZP0053 INBOUND NULL WEEKLY is_full_sync=TRUE, ZP0055 INBOUND NULL WEEKLY is_full_sync=TRUE, ZP0061 OUTBOUND 05:00, ZP0062 INBOUND 10:00, ZP0063 OUTBOUND 14:00, ZP0064 INBOUND 19:00, ZP0065 OUTBOUND 22:00, ZP0066 OUTBOUND 22:00; Add FK constraint on scheme_id referencing scheme_config; Add Flyway checksum annotation in comment header
**Deliverable:** services/config-registry/src/main/resources/db/migration/V901__file_type_config.sql
**Acceptance / logic checks:**
- Migration applies after V900 on clean Testcontainers PostgreSQL 16 instance
- UNIQUE(scheme_id, file_id) rejects a duplicate ZP0011 row for ZEROPAY
- All 16 ZeroPay rows are present; SELECT COUNT(*) FROM file_type_config WHERE scheme_id=ZEROPAY returns 16
- ZP0051, ZP0053, ZP0055 have is_full_sync=TRUE and frequency=WEEKLY
- ZP0061 has nominal_kst_time = 05:00:00
**Depends on:** 9.1-T05

### 9.1-T07 — Implement SchemeConfigRepository JPA entity and Spring Data repository  _(40 min)_
**Context:** Config and Registry service (Gradle module services/config-registry) owns the scheme_config and file_type_config tables in PostgreSQL. A Spring Data JPA repository provides CRUD for the adapter layer. The JPA entity must map JSONB columns to Map<String,BigDecimal> using a custom AttributeConverter (Jackson-based). Monetary fields use NUMERIC which maps to BigDecimal.
**Steps:** Create @Entity SchemeConfigEntity in services/config-registry/src/main/java/com/gme/pay/configregistry/entity/SchemeConfigEntity.java mapping all columns from V900; Create JsonbConverter implements AttributeConverter<Map<String,BigDecimal>,String> using ObjectMapper for merchant_fee_table and van_fee_table columns; Create @Repository SchemeConfigRepository extends JpaRepository<SchemeConfigEntity,UUID> with method Optional<SchemeConfigEntity> findBySchemeId(String schemeId); Create @Entity FileTypeConfigEntity mapping V901 columns; FK to SchemeConfigEntity via @ManyToOne; Create FileTypeConfigRepository with List<FileTypeConfigEntity> findBySchemeIdAndEnabledTrue(String schemeId)
**Deliverable:** services/config-registry/src/main/java/com/gme/pay/configregistry/entity/SchemeConfigEntity.java, FileTypeConfigEntity.java, SchemeConfigRepository.java, FileTypeConfigRepository.java, JsonbConverter.java
**Acceptance / logic checks:**
- findBySchemeId(ZEROPAY) returns the seeded row after V900/V901 migrations run in Testcontainers
- merchant_fee_table deserialises to Map<String,BigDecimal> with no precision loss
- findBySchemeIdAndEnabledTrue(ZEROPAY) returns 16 rows
- SchemeConfigEntity.updatedAt is populated by DB trigger (not application code) — JPA column is @Column(insertable=false, updatable=false)
- Saving a new entity with duplicate schemeId throws DataIntegrityViolationException
**Depends on:** 9.1-T05, 9.1-T06

### 9.1-T08 — Implement SchemeConfigService @Service wrapping config retrieval with Redis cache  _(45 min)_
**Context:** The Smart Router and adapter layer read SchemeConfig frequently (per-request path). Redis (STACK.md) is used as a config cache with a TTL of 300 seconds (configurable via adapter.config.cache.ttl-seconds). The service must use Spring Cache with @Cacheable(value=scheme-config, key=#schemeId) backed by RedisCache. Cache eviction occurs when an Ops user updates the scheme record (via a dedicated evict method called from the update endpoint).
**Steps:** Create SchemeConfigService in services/config-registry/src/main/java/com/gme/pay/configregistry/service/SchemeConfigService.java annotated @Service; Annotate getBySchemeId(String) with @Cacheable(value=scheme-config, key=#schemeId); return SchemeConfig domain object (mapped from entity); Annotate updateSchemeConfig(String schemeId, SchemeConfigUpdateRequest req) with @CacheEvict(value=scheme-config, key=#schemeId); apply update; log change with actor+timestamp into scheme_config_audit table (see 9.1-T09); Configure RedisCacheManager bean in CacheConfig @Configuration with TTL = ${adapter.config.cache.ttl-seconds:300}; Add SchemeConfigMapper @Component converting SchemeConfigEntity to SchemeConfig record from lib-api-contracts
**Deliverable:** services/config-registry/src/main/java/com/gme/pay/configregistry/service/SchemeConfigService.java and CacheConfig.java
**Acceptance / logic checks:**
- Second call to getBySchemeId(ZEROPAY) is served from Redis; repo.findBySchemeId is invoked only once (verify with Mockito spy)
- After updateSchemeConfig the cache key is evicted; next call hits DB
- TTL default 300s is configurable via application property
- getBySchemeId for unknown scheme_id throws SchemeAdapterNotFoundException
- RedisTemplate key serialiser is StringRedisSerializer (human-readable keys in Redis)
**Depends on:** 9.1-T07

### 9.1-T09 — Flyway migration V902__scheme_config_audit.sql for immutable config audit log  _(25 min)_
**Context:** SAD-02 and canonical facts: config changes log actor, timestamp, previous value, and apply only to new transactions. The audit log must be append-only; no UPDATE or DELETE is permitted on this table. One row per change on scheme_config, storing previous_value and new_value as JSONB blobs.
**Steps:** Create db/migration/V902__scheme_config_audit.sql in services/config-registry/src/main/resources/db/migration/; Define scheme_config_audit: id UUID PK default gen_random_uuid(), scheme_id VARCHAR(20) NOT NULL, actor_email VARCHAR(255) NOT NULL, change_type VARCHAR(20) NOT NULL CHECK (change_type IN (CREATE,UPDATE,DEACTIVATE)), previous_value JSONB, new_value JSONB NOT NULL, changed_at TIMESTAMPTZ NOT NULL DEFAULT now(); Add a PostgreSQL RULE: CREATE RULE no_update_audit AS ON UPDATE TO scheme_config_audit DO INSTEAD NOTHING and similarly for DELETE to enforce immutability; Add index: CREATE INDEX ix_scheme_config_audit_scheme_id ON scheme_config_audit(scheme_id, changed_at DESC); Document in SQL comment: this table is append-only; use INSERT only; UPDATE/DELETE are silently dropped by RULE
**Deliverable:** services/config-registry/src/main/resources/db/migration/V902__scheme_config_audit.sql
**Acceptance / logic checks:**
- Migration applies cleanly after V901 in Testcontainers
- UPDATE on scheme_config_audit has no effect (RULE drops it); row count unchanged
- DELETE on scheme_config_audit has no effect
- INSERT with missing actor_email is rejected by NOT NULL constraint
- ix_scheme_config_audit_scheme_id index is present in pg_indexes
**Depends on:** 9.1-T05

### 9.1-T10 — Implement SchemeAdapterPort @Component in payment-executor wiring to SchemeAdapterRegistry  _(30 min)_
**Context:** The Payment Executor service (Gradle module services/payment-executor) calls scheme adapters via the SchemeAdapterRegistry. To avoid coupling the executor directly to the scheme-adapter module, a Port class (hexagonal pattern) wraps the registry call. SchemeAdapterPort is injected into PaymentOrchestrator. All calls go through SchemeAdapterPort.getAdapter(schemeId) which returns the SchemeAdapter interface — never a concrete class.
**Steps:** Add services/scheme-adapter as a compileOnly/api dependency in services/payment-executor/build.gradle; Create SchemeAdapterPort in services/payment-executor/src/main/java/com/gme/pay/executor/port/SchemeAdapterPort.java annotated @Component; Inject SchemeAdapterRegistry (from services/scheme-adapter) via constructor; Expose methods: authoriseCpm(String schemeId, CpmAuthRequest), submitMpm(String schemeId, MpmSubmitRequest), parseMerchantQR(String schemeId, String rawPayload) — each delegating to registry.resolve(schemeId).<method>; Add @Slf4j logging at DEBUG level: entry/exit for each call including schemeId and method name for tracing
**Deliverable:** services/payment-executor/src/main/java/com/gme/pay/executor/port/SchemeAdapterPort.java
**Acceptance / logic checks:**
- SchemeAdapterPort.submitMpm(ZEROPAY, req) calls ZeroPaySchemeAdapter.submitMpm without casting to concrete type
- SchemeAdapterPort.submitMpm(UNKNOWN_SCHEME, req) propagates SchemeAdapterNotFoundException
- No import of ZeroPaySchemeAdapter in SchemeAdapterPort
- SchemeAdapterPort is injectable via @Autowired in unit tests with a Mockito mock of SchemeAdapterRegistry
- DEBUG log entry is emitted on each method call
**Depends on:** 9.1-T03, 9.1-T04

### 9.1-T11 — Implement parseMerchantQR EMVCo CRC-16/CCITT validation in ZeroPaySchemeAdapter  _(45 min)_
**Context:** SCH-06 §3.4: parseMerchantQR must verify CRC-16/CCITT checksum in EMVCo tag 63; confirm tag 00 = 01; extract tag 53 (must be 410 for KRW); locate ZeroPay MAI slot (configurable tag in 26-51 range via adapter.zeropay.mai-tag, default configurable); extract merchant_id, qr_code_id, merchant_name. Error codes: QR_INVALID_CHECKSUM (CRC fail), QR_UNKNOWN_SCHEME (MAI tag not found), QR_MALFORMED (missing mandatory tag), QR_CURRENCY_MISMATCH (tag 53 != 410). The MAI tag must be configurable so it can be updated when KFTC confirms the official tag (OI-A-05).
**Steps:** Implement EmvcoQrParser in services/scheme-adapter/src/main/java/com/gme/pay/scheme/adapter/zeropay/qr/EmvcoQrParser.java as a package-private class; Implement CRC-16/CCITT calculation over all QR string bytes before tag 63; compare with stored value; throw SchemeAdapterException(QR_INVALID_CHECKSUM) on mismatch; Parse TLV string: split on 2-char tag + 2-char length + value pattern; build Map<String,String> tagMap; Validate tag 00 = 01, tag 53 = 410; throw appropriate error codes from lib-errors; Extract MAI slot using configurable tag (injected from ZeroPayAdapterProperties.maiTag); extract sub-tags for merchant_id and qr_code_id; return MerchantIdentifier record; Wire EmvcoQrParser into ZeroPaySchemeAdapter.parseMerchantQR()
**Deliverable:** services/scheme-adapter/src/main/java/com/gme/pay/scheme/adapter/zeropay/qr/EmvcoQrParser.java
**Acceptance / logic checks:**
- Valid EMVCo QR string with correct CRC returns MerchantIdentifier with non-null merchant_id and qr_code_id
- Tampered last byte of QR string throws SchemeAdapterException with code QR_INVALID_CHECKSUM
- QR with tag 53 = 840 (USD) throws SchemeAdapterException with code QR_CURRENCY_MISMATCH
- QR missing tag 00 throws QR_MALFORMED
- mai-tag configured as 29 correctly extracts sub-tags from MAI slot 29
**Depends on:** 9.1-T04

### 9.1-T12 — Implement merchant and QR resolution against PostgreSQL local DB in ZeroPaySchemeAdapter  _(40 min)_
**Context:** SCH-06 §3.5: after QR parsing, GMEPay+ validates merchant_id and qr_code_id against the local PostgreSQL merchant and qr_codes tables (populated by daily sync). At payment time the local DB is authoritative — no live ZeroPay lookup. Steps: look up merchant by merchant_id; confirm status = ACTIVE; look up qr_code_id; confirm status = ACTIVE and linked merchant_id matches; retrieve merchant_type_code for fee rate selection. Failures: MERCHANT_NOT_FOUND (422), MERCHANT_INACTIVE (422), QR_DEACTIVATED (422).
**Steps:** Create MerchantResolutionService in services/scheme-adapter/src/main/java/com/gme/pay/scheme/adapter/zeropay/merchant/MerchantResolutionService.java annotated @Service; Inject MerchantRepository and QrCodeRepository (JPA, PostgreSQL via services/merchant-qr-data module); Implement resolveMerchant(String merchantId, String qrCodeId): MerchantRecord following SCH-06 §3.5 five steps; Throw SchemeAdapterException with canonical lib-errors code for each failure case: MERCHANT_NOT_FOUND, MERCHANT_INACTIVE, QR_DEACTIVATED, QR_MALFORMED (qr linked to different merchant_id); Wire MerchantResolutionService into ZeroPaySchemeAdapter; call from parseMerchantQR after EmvcoQrParser succeeds
**Deliverable:** services/scheme-adapter/src/main/java/com/gme/pay/scheme/adapter/zeropay/merchant/MerchantResolutionService.java
**Acceptance / logic checks:**
- resolveMerchant with valid IDs returns MerchantRecord with correct merchant_type_code
- Inactive merchant (status=SUSPENDED) throws SchemeAdapterException code MERCHANT_INACTIVE
- QR code linked to a different merchant_id throws QR_MALFORMED
- Terminated merchant (status=TERMINATED) also throws MERCHANT_INACTIVE
- Test uses Testcontainers PostgreSQL 16 with V900 migration pre-loaded and test fixtures inserted
**Depends on:** 9.1-T07, 9.1-T11

### 9.1-T13 — Define AdapterHealth model and implement healthCheck() for ZeroPaySchemeAdapter  _(35 min)_
**Context:** SchemeAdapterRegistry exposes health state for each registered adapter; this feeds the Ops portal and monitoring (OpenTelemetry). AdapterHealth must report: status (UP/DEGRADED/DOWN), last_checked_at (Instant), sftp_reachable (boolean), realtime_api_reachable (boolean), last_error (String nullable). ZeroPaySchemeAdapter.healthCheck() performs a lightweight SFTP no-op (list root directory) and an HTTP HEAD to realtime_api_base_url with 5s timeout. In Phase 1 with no live ZeroPay environment, both checks return false with error=SFTP/API not yet configured.
**Steps:** Create AdapterHealth Java record in services/scheme-adapter/src/main/java/com/gme/pay/scheme/adapter/model/AdapterHealth.java: status(HealthStatus), lastCheckedAt(Instant), sftpReachable(boolean), realtimeApiReachable(boolean), lastError(String); Create enum HealthStatus { UP, DEGRADED, DOWN }; Implement ZeroPaySchemeAdapter.healthCheck(): if sftp_host is blank return DOWN with lastError=SFTP not configured; otherwise attempt Apache SSHD SFTP stat with 5s connect timeout; catch all exceptions and set sftpReachable=false; Similarly for realtime_api_base_url: HTTP HEAD via RestClient with 5s timeout; catch exceptions; set realtimeApiReachable=false; Return DEGRADED if one channel fails, DOWN if both fail, UP if both succeed
**Deliverable:** services/scheme-adapter/src/main/java/com/gme/pay/scheme/adapter/model/AdapterHealth.java and updated ZeroPaySchemeAdapter.healthCheck()
**Acceptance / logic checks:**
- healthCheck() with blank sftp_host returns HealthStatus.DOWN and lastError containing not configured
- healthCheck() where SFTP is reachable but REST HEAD fails returns DEGRADED
- healthCheck() never throws — all exceptions are caught and reflected in the return value
- lastCheckedAt is set to Instant.now() within the method (not zero/null)
- AdapterHealth is serialisable to JSON (Jackson) for the health endpoint
**Depends on:** 9.1-T04

### 9.1-T14 — Implement SFTP client SftpGatewayClient using Apache SSHD in ZeroPaySchemeAdapter  _(55 min)_
**Context:** SCH-06 §2.3 and §2.4: SFTP uses SSH key authentication (RSA-4096 or ECDSA P-256); private key retrieved from Vault/KMS via sftp_private_key_ref (never from disk). Outbound: generate file in memory, write to temp file in secure scratch dir, PGP-encrypt, SFTP PUT, verify remote size/checksum, archive local copy on success, retry 3x with back-off (30s, 2min, 10min) on failure. File naming: {FILE_ID}_{YYYYMMDD}_{SEQ}.dat.pgp (SCH-06 §2.3.3). Inbound: poll remote inbound dir, download to scratch, verify, PGP-decrypt, parse. All PGP ops in memory — no cleartext intermediate files.
**Steps:** Create SftpGatewayClient in services/scheme-adapter/src/main/java/com/gme/pay/scheme/adapter/zeropay/sftp/SftpGatewayClient.java using Apache MINA SSHD (org.apache.sshd:sshd-sftp:2.x); Implement connect(SchemeConfig cfg): retrieve private key bytes from VaultSecretsClient using cfg.sftpPrivateKeyRef(); create SshClient; add HostKeyVerifier pinning host key from config; connect and authenticate; Implement transferOutbound(BatchFile file, String remotePath): write to temp file under ${adapter.zeropay.scratch-dir}/out/; PGP-encrypt using BouncyCastle with scheme PGP public key; PUT to cfg.outboundDir()+remotePath; verify remote file size equals local encrypted size; on failure retry 3x with back-off 30s/120s/600s; Implement fetchInbound(String remotePath, Path localPath): GET from cfg.inboundDir()+remotePath; verify checksum; PGP-decrypt using GME private key from cfg.pgpPrivateKeyRef(); return decrypted bytes; Add SftpTransferException (extends SchemeAdapterException) with SFTP_TRANSFER_FAILED error code
**Deliverable:** services/scheme-adapter/src/main/java/com/gme/pay/scheme/adapter/zeropay/sftp/SftpGatewayClient.java
**Acceptance / logic checks:**
- transferOutbound retries exactly 3 times then throws SftpTransferException on persistent SFTP failure (mocked SshClient)
- File naming pattern matches {FILE_ID}_{YYYYMMDD}_01.dat.pgp for sequence 1
- Temp file is deleted from scratch dir after successful transfer
- No cleartext file is retained after PGP encryption step (verify scratch dir is empty post-transfer)
- Private key is fetched from VaultSecretsClient, not from classpath or file system
**Depends on:** 9.1-T04

### 9.1-T15 — Implement PgpCryptoService using BouncyCastle for file encrypt/decrypt/sign/verify  _(50 min)_
**Context:** SCH-06 §2.3.2: outbound files are PGP-encrypted with KFTC public key and GME-signed with GME private key. Inbound files are encrypted with GME public key; GMEPay+ decrypts with GME private key and verifies KFTC signature. All PGP operations run entirely in memory — no cleartext intermediate files written to disk (security requirement). Key references stored in secrets store.
**Steps:** Create PgpCryptoService in services/scheme-adapter/src/main/java/com/gme/pay/scheme/adapter/zeropay/crypto/PgpCryptoService.java; Add BouncyCastle dependency (org.bouncycastle:bcpg-jdk18on) in services/scheme-adapter/build.gradle; Implement encryptAndSign(byte[] plaintext, PGPPublicKey recipientKey, PGPSecretKey signerKey, char[] signerPassphrase): byte[] — use AES-256 symmetric cipher, SHA-256 digest for signature; Implement decryptAndVerify(byte[] ciphertext, PGPSecretKey decryptKey, char[] passphrase, PGPPublicKey senderPublicKey): byte[] — throw PgpSignatureException if signature fails verification; Implement loadPublicKey(byte[] armouredBytes): PGPPublicKey and loadSecretKey(byte[] armouredBytes): PGPSecretKey; Add PgpSignatureException (lib-errors) with code PGP_SIGNATURE_INVALID
**Deliverable:** services/scheme-adapter/src/main/java/com/gme/pay/scheme/adapter/zeropay/crypto/PgpCryptoService.java
**Acceptance / logic checks:**
- decryptAndVerify(encryptAndSign(plaintext, recipPubKey, signerSecKey)) returns original plaintext unchanged
- decryptAndVerify with a tampered ciphertext throws PgpSignatureException
- encryptAndSign output contains no plaintext bytes (verify no substring match of input text in output)
- Encryption uses AES-256 (verify cipher algo in PGPEncryptedDataGenerator config)
- decryptAndVerify with wrong secret key throws PGPException from BouncyCastle (propagated, not swallowed)
**Depends on:** 9.1-T04

### 9.1-T16 — Implement ZP0011 outbound batch file generator (payment result registration)  _(50 min)_
**Context:** SCH-06 §5.2: ZP0011 is transmitted by 02:00 KST daily; scope = all transactions in status APPROVED for the prior business day (KST) not yet in a prior batch. Record layout (illustrative per A-11): CHAR(1) record_type=D, CHAR(20) gme_txn_id, CHAR(20) zeropay_txn_ref, CHAR(10) merchant_id, CHAR(20) qr_code_id, DATE(8) txn_date, TIME(6) txn_time, NUM(12) payout_amount_krw, NUM(12) merchant_fee_amt, NUM(10) van_fee_amt, CHAR(1) partner_type (D/I), CHAR(12) approval_code, CHAR(1) status_code=A. File header: file_type ZP0011, business_date, gme_institution_code, total_record_count, total_payout_amount_krw. File trailer: total_record_count, control_sum. All KRW amounts are integers (0 decimal places per canonical facts).
**Steps:** Create Zp0011FileGenerator in services/scheme-adapter/src/main/java/com/gme/pay/scheme/adapter/zeropay/batch/Zp0011FileGenerator.java; Inject TransactionQueryPort (interface backed by services/transaction-mgmt PostgreSQL) to fetch APPROVED transactions for a given business date not yet assigned a batch_file_id; Generate fixed-width record lines; pad/truncate all CHAR fields to exact width; right-align NUM fields with leading zeros; Compute control_sum as sum of payout_amount_krw across all records; write to trailer; Return BatchFile record containing: fileId=ZP0011, businessDate, sequenceNo (01 for first run), contentBytes (UTF-8 fixed-width), recordCount, controlSum; File must be idempotent: re-running for the same date produces identical output if no new transactions
**Deliverable:** services/scheme-adapter/src/main/java/com/gme/pay/scheme/adapter/zeropay/batch/Zp0011FileGenerator.java
**Acceptance / logic checks:**
- Header record contains exactly ZP0011 in the file_type field position
- payout_amount_krw in detail records is NUM(12) right-padded to 12 digits with leading zeros
- control_sum in trailer equals sum of all detail record payout_amount_krw values
- Zero-transaction day produces a file with header + trailer only (record_count=0)
- Re-running generator for same date+same transactions returns byte-for-byte identical output (idempotent)
**Depends on:** 9.1-T07

### 9.1-T17 — Implement ZP0012 inbound file parser and reconciliation service  _(50 min)_
**Context:** SCH-06 §5.3 and §5.4: ZP0012 arrives by 05:00 KST; contains per-record registration result. Match key: zeropay_txn_ref + txn_date. result_code 00 = success; non-zero = REGISTRATION_FAILED. GMEPay+ must perform line-by-line match of ZP0012 vs ZP0011; any record in ZP0011 absent from ZP0012 = REGISTRATION_UNKNOWN; any record in ZP0012 absent from ZP0011 = anomaly/Ops alert. Amount discrepancy (registered_amount != submitted payout_amount_krw) = REGISTRATION_AMOUNT_MISMATCH. All amounts in KRW (integer, 0 decimal).
**Steps:** Create Zp0012FileParser in services/scheme-adapter/src/main/java/com/gme/pay/scheme/adapter/zeropay/batch/Zp0012FileParser.java; Implement parse(byte[] content): List<Zp0012Record> parsing fixed-width lines; return Zp0012Record(zeroPayTxnRef, gmeTxnId, merchantId, txnDate, resultCode, resultMessage, registeredAmount, settlementDate); Create Zp0012ReconciliationService that joins Zp0012Record list vs Zp0011 submitted records from DB; produces ReconciliationResult containing lists: SETTLEMENT_REGISTERED, REGISTRATION_FAILED, REGISTRATION_UNKNOWN, REGISTRATION_AMOUNT_MISMATCH, ANOMALY; For each exception category emit a domain event via EventPublisher (lib-events, transactional Outbox) so Ops alert can be triggered; Update transaction status in PostgreSQL within a @Transactional method; batch update using Spring Data JPA saveAll
**Deliverable:** services/scheme-adapter/src/main/java/com/gme/pay/scheme/adapter/zeropay/batch/Zp0012FileParser.java and Zp0012ReconciliationService.java
**Acceptance / logic checks:**
- Record with result_code=00 and matching amount transitions transaction to SETTLEMENT_REGISTERED
- Record with result_code=9002 transitions to REGISTRATION_AMOUNT_MISMATCH and emits exception event
- ZP0011 record absent from ZP0012 produces REGISTRATION_UNKNOWN entry in ReconciliationResult
- ZP0012 record absent from ZP0011 produces ANOMALY entry
- ReconciliationService is @Transactional; partial failure rolls back all status updates for that file processing run
**Depends on:** 9.1-T16

### 9.1-T18 — Implement ZP0061 outbound morning settlement request file generator  _(50 min)_
**Context:** SCH-06 §7.2: ZP0061 must be transmitted by 05:00 KST; scope = settlement totals for all transactions with SETTLEMENT_REGISTERED status for the prior business day. Summary level: one row per merchant_id. Fields: merchant_id CHAR(10), settlement_date DATE(8), gross_txn_count NUM(6), gross_txn_amount NUM(14), refund_count NUM(6), refund_amount NUM(14), merchant_fee_total NUM(12), net_settlement_amount NUM(14), settlement_type CHAR(1) N=Net/G=Gross. Net settlement (domestic): net_settlement_amount = gross_txn_amount - refund_amount - merchant_fee_total. Gross settlement (international): net_settlement_amount = gross_txn_amount - refund_amount (full gross; fee invoiced monthly). Prerequisite per §8.2: ZP0011 success AND ZP0012 received.
**Steps:** Create Zp0061FileGenerator in services/scheme-adapter/src/main/java/com/gme/pay/scheme/adapter/zeropay/batch/Zp0061FileGenerator.java; Query PostgreSQL for all SETTLEMENT_REGISTERED transactions grouped by (merchant_id, settlement_type) for business_date; Apply net vs gross logic: for settlement_type=N: net_settlement_amount = gross_txn_amount - refund_amount - merchant_fee_total; for settlement_type=G: net_settlement_amount = gross_txn_amount - refund_amount; Produce fixed-width records; header includes ZP0061, business_date, total merchant count, grand total net_settlement_amount; Guard: throw BatchPrerequisiteException if ZP0011 and ZP0012 have not both completed successfully for the business_date; All amounts are NUMERIC (BigDecimal) internally; serialised as integer KRW strings in file
**Deliverable:** services/scheme-adapter/src/main/java/com/gme/pay/scheme/adapter/zeropay/batch/Zp0061FileGenerator.java
**Acceptance / logic checks:**
- Domestic merchant with gross_txn_amount=100000 KRW, refund=0, merchant_fee=500: net_settlement_amount=99500
- International merchant: net_settlement_amount = gross_txn_amount - refund_amount (fee NOT deducted)
- BatchPrerequisiteException thrown when ZP0012 has not been processed for the business_date
- Generator is idempotent: same inputs produce same file bytes
- File header grand_total equals sum of all merchant net_settlement_amount values
**Depends on:** 9.1-T17

### 9.1-T19 — Implement batch job scheduler using Spring @Scheduled for ZP00xx daily cron windows  _(45 min)_
**Context:** SCH-06 §7.3 and §8.1: outbound batch jobs run at fixed KST cron times: ZP0011/ZP0021 at 02:00, ZP0061 at 05:00, ZP0063 at 14:00, ZP0065/ZP0066 at 22:00. Inbound polls: ZP0012/ZP0022 poll from 05:00, ZP0062 ~10:00, ZP0064 ~19:00. Merchant sync inbound polls hourly 01:00-08:00 KST. All jobs are idempotent. Jobs run in the settlement-service Spring Boot app (services/settlement). The cron expressions use Asia/Seoul timezone. Job dependency chain (§8.2): ZP0061 requires successful ZP0011 + ZP0012; ZP0063 requires morning cycle complete.
**Steps:** Create ZeroPayBatchScheduler in services/settlement/src/main/java/com/gme/pay/settlement/scheduler/ZeroPayBatchScheduler.java annotated @Component; Add @Scheduled(cron=0 0 2 * * *, zone=Asia/Seoul) for ZP0011 and ZP0021 generation+transmission; Add @Scheduled(cron=0 0 5 * * *, zone=Asia/Seoul) for ZP0012/ZP0022 poll+process and ZP0061 generation+transmission (ZP0061 called after ZP0012 processor completes within the same scheduled method); Add @Scheduled for ZP0062 poll (10:00), ZP0063 (14:00), ZP0064 poll (19:00), ZP0065/ZP0066 (22:00); Add @Scheduled(cron=0 0 1-8 * * *, zone=Asia/Seoul) for hourly merchant sync poll 01:00-08:00; Each method delegates to corresponding BatchJobService; wraps in try-catch; emits BatchJobCompletedEvent or BatchJobFailedEvent via EventPublisher to transactional Outbox
**Deliverable:** services/settlement/src/main/java/com/gme/pay/settlement/scheduler/ZeroPayBatchScheduler.java
**Acceptance / logic checks:**
- @Scheduled cron for ZP0011 produces next execution at 02:00 KST when calculated against Asia/Seoul timezone
- ZP0061 method checks BatchPrerequisiteService before calling generator; logs WARNING and skips if ZP0012 not received
- Each scheduled method is idempotent: second execution within same business day is a no-op (detected by batch_job_log table)
- BatchJobFailedEvent is emitted on any exception; event carries jobName, businessDate, errorMessage
- Unit test: @SpringBootTest slice with TestSchedulingConfig disabling auto-start; manually invoke each method and assert delegated service called
**Depends on:** 9.1-T18

### 9.1-T20 — Implement merchant delta sync processor for ZP0041/ZP0045/ZP0047 inbound files  _(50 min)_
**Context:** SCH-06 §4.2: ZP0041 (merchant delta), ZP0045 (franchise merchant delta), ZP0047 (franchise group delta) — each delivers daily incremental changes. GME handling: upsert into merchants table (keyed on merchant_id) using change_type I=Insert, U=Update, D=Deactivate. D records set status=INACTIVE immediately — deactivated merchants must be blocked at payment time from the moment of DB update. Log insert/update/deactivation counts. Reconcile record count vs file header; mismatch = ops alert. All ops are in a single @Transactional batch.
**Steps:** Create MerchantDeltaSyncProcessor in services/scheme-adapter/src/main/java/com/gme/pay/scheme/adapter/zeropay/sync/MerchantDeltaSyncProcessor.java annotated @Service; Parse ZP0041 fixed-width records (field layout: merchant_id CHAR(10), merchant_name VARCHAR(100), business_reg_no CHAR(10), merchant_type_code CHAR(2), status_code CHAR(1), address VARCHAR(200), bank_code CHAR(3), account_no VARCHAR(20), effective_date DATE(8), change_type CHAR(1)); Upsert using PostgreSQL INSERT ... ON CONFLICT(merchant_id) DO UPDATE; for change_type=D set status=INACTIVE; After upsert validate that DB merchant count change matches file header record count; mismatch emits SyncReconciliationMismatchEvent; Apply same processor pattern for ZP0045 (franchise merchants, is_franchise=TRUE, franchise_code FK) and ZP0047 (franchise_groups table); Wrap all upserts in @Transactional; rollback on any exception; retain previous data on failure and alert Ops via EventPublisher
**Deliverable:** services/scheme-adapter/src/main/java/com/gme/pay/scheme/adapter/zeropay/sync/MerchantDeltaSyncProcessor.java
**Acceptance / logic checks:**
- change_type=I inserts new merchant row; second run with same record is idempotent (upsert, no duplicate)
- change_type=D sets merchant status=INACTIVE; subsequent parseMerchantQR for that merchant_id throws MERCHANT_INACTIVE
- change_type=U updates merchant_name without affecting status
- Record count mismatch between file header and parsed records emits SyncReconciliationMismatchEvent
- Exception during upsert rolls back entire batch (Testcontainers PostgreSQL 16 integration test)
**Depends on:** 9.1-T07, 9.1-T12

### 9.1-T21 — Implement full merchant sync (ZP0051/ZP0055/ZP0053) staging-diff-atomic-promote processor  _(55 min)_
**Context:** SCH-06 §4.1 and ZP0051/ZP0055/ZP0053: full sync files contain all active records; processed weekly. GMEPay+ must: download+validate checksum+record count; load into staging table; diff staging vs live; apply inserts, updates, soft-deletes for absent records; confirm record count matches ZeroPay header; promote staging to live atomically within a PostgreSQL transaction. Full-sync failures must not partially overwrite live data — rollback on any error. SCH-06 §4.3: when a full file is present on the same night, suppress the delta upsert for that entity type.
**Steps:** Create V903__merchant_sync_staging.sql: table merchant_staging mirrors merchants table plus sync_batch_id UUID column; same for qr_codes_staging; Create FullMerchantSyncProcessor in services/scheme-adapter/src/main/java/com/gme/pay/scheme/adapter/zeropay/sync/FullMerchantSyncProcessor.java; Implement processFull(byte[] fileContent, LocalDate businessDate): truncate merchant_staging for this sync_batch_id; bulk INSERT all parsed records; diff vs live using NOT EXISTS subquery; apply inserts/updates/soft-deletes in merchants table inside @Transactional; if record count != header_count rollback and throw FullSyncIntegrityException; Add isMerchantFullSyncPresent(LocalDate d): boolean method; delta processor checks this before running and skips if true; QR full sync (ZP0053) additionally validates every qr_codes staging row has a valid merchant_id in merchants table (referential integrity check before promote)
**Deliverable:** services/scheme-adapter/src/main/java/com/gme/pay/scheme/adapter/zeropay/sync/FullMerchantSyncProcessor.java and db/migration/V903__merchant_sync_staging.sql
**Acceptance / logic checks:**
- processFull with staging count != header count throws FullSyncIntegrityException and merchants table is unchanged
- Merchant absent from full file but present in live table is soft-deleted (status=INACTIVE) during promote
- Delta processor skips when isMerchantFullSyncPresent returns true for that business date
- processFull is idempotent: re-running with same file produces same DB state
- QR full sync rejects file if any qr_code row references a merchant_id not in merchants table
**Depends on:** 9.1-T20

### 9.1-T22 — Implement SchemeAdapterHealthIndicator Spring Boot Actuator for adapter status  _(30 min)_
**Context:** Ops portal and OpenTelemetry monitoring require a /actuator/health endpoint that includes SchemeAdapter health. Spring Boot Actuator custom HealthIndicator reads AdapterHealth from SchemeAdapterRegistry for all registered adapters and maps to Spring Health (UP/DOWN/UNKNOWN). Exposed via management.endpoint.health.show-details=always for internal monitoring; separate port 8081 for management (not exposed externally per WAF/CDN/TLS at Spring Cloud Gateway edge).
**Steps:** Create SchemeAdapterHealthIndicator in services/scheme-adapter/src/main/java/com/gme/pay/scheme/adapter/actuator/SchemeAdapterHealthIndicator.java implementing HealthIndicator; Inject SchemeAdapterRegistry; in health() call registry.registeredSchemeIds() and for each call adapter.healthCheck(); Map AdapterHealth.status UP -> Health.up(), DEGRADED -> Health.unknown(), DOWN -> Health.down() with detail entries sftpReachable and realtimeApiReachable; Add component to services/payment-executor and services/settlement Spring Boot apps by including scheme-adapter module; Verify management server port configured: management.server.port=8081 in application.yml
**Deliverable:** services/scheme-adapter/src/main/java/com/gme/pay/scheme/adapter/actuator/SchemeAdapterHealthIndicator.java
**Acceptance / logic checks:**
- When ZeroPaySchemeAdapter.healthCheck() returns UP, /actuator/health includes component scheme-adapter with status UP
- When healthCheck() returns DOWN, overall application health is DOWN
- Health detail map contains keys sftpReachable and realtimeApiReachable as boolean
- HealthIndicator bean present in Spring context without explicit @Import when scheme-adapter module is on classpath
- Management endpoint is on port 8081 (not 8080)
**Depends on:** 9.1-T13, 9.1-T03

### 9.1-T23 — Define transactional Outbox domain events for adapter layer in lib-events  _(45 min)_
**Context:** STACK.md: Phase 1 uses transactional Outbox + EventPublisher interface; Kafka wired in integration phase. All domain events emitted by the adapter layer (BatchJobCompleted, BatchJobFailed, SyncReconciliationMismatch, RegistrationException) must be defined in lib-events as Avro/JSON schemas and published via EventPublisher. The outbox table (in PostgreSQL, same transaction as the state change) ensures no event is lost if the process crashes after DB commit.
**Steps:** Define event schemas in lib-events/src/main/avro/: BatchJobCompletedEvent.avsc, BatchJobFailedEvent.avsc, SyncReconciliationMismatchEvent.avsc, RegistrationExceptionEvent.avsc — each with fields: eventId UUID, occurredAt Instant, schemeId String, businessDate LocalDate, plus event-specific payload fields; Create OutboxEventPublisher @Service in lib-events implementing EventPublisher interface; persists event JSON to outbox table (id UUID, event_type VARCHAR, payload JSONB, created_at TIMESTAMPTZ, published_at TIMESTAMPTZ nullable) in same @Transactional as the calling service; Create V904__outbox.sql Flyway migration for the outbox table in services/settlement and services/scheme-adapter (each service has its own outbox); Add EventPublisher interface: void publish(DomainEvent event) — single method; Kafka impl dropped in transparently later; Wire OutboxEventPublisher into Zp0012ReconciliationService and MerchantDeltaSyncProcessor
**Deliverable:** lib-events/src/main/avro/*.avsc (4 schemas), lib-events/src/main/java/com/gme/pay/events/EventPublisher.java, lib-events/src/main/java/com/gme/pay/events/OutboxEventPublisher.java, db/migration/V904__outbox.sql
**Acceptance / logic checks:**
- BatchJobFailedEvent emitted by scheduler is persisted to outbox table in same transaction as batch_job_log INSERT (Testcontainers test)
- If OutboxEventPublisher.publish() is called outside @Transactional context it throws IllegalStateException
- published_at column is null until the outbox poller marks it sent
- Event payload JSONB for RegistrationExceptionEvent deserialises back to RegistrationExceptionEvent with zero field loss
- Kafka wiring requires only a new OutboxEventPublisher implementation; no change to callers (interface contract test)
**Depends on:** 9.1-T17, 9.1-T19, 9.1-T20

### 9.1-T24 — Add OpenTelemetry span instrumentation to SchemeAdapterPort and batch generators  _(40 min)_
**Context:** STACK.md: OpenTelemetry traces/metrics/logs export to Jaeger (tracing) and Prometheus+Grafana (metrics). All scheme adapter calls must be traced so Ops can correlate payment latency with adapter calls. Each SchemeAdapterPort method and each batch generator method must create a child span with attributes: scheme.id, adapter.method, batch.file_id, batch.business_date. Metrics: Counter scheme_adapter_calls_total{scheme_id, method, outcome} and Histogram scheme_adapter_call_duration_seconds.
**Steps:** Add OpenTelemetry Java SDK and Micrometer bridge dependencies to services/scheme-adapter/build.gradle; Inject io.opentelemetry.api.trace.Tracer into SchemeAdapterPort; wrap each method body in tracer.spanBuilder(scheme.adapter.<methodName>).startSpan(); set span attributes scheme.id and adapter.method; end span in finally block; Add Micrometer Counter and Timer beans in SchemeAdapterMetrics @Component; increment counter and record timer in SchemeAdapterPort for each call outcome (SUCCESS/ERROR); Add the same span creation pattern to Zp0011FileGenerator.generate() and Zp0061FileGenerator.generate() with batch.file_id and batch.business_date attributes; Configure OTLP exporter endpoint via management.otlp.tracing.endpoint in application.yml
**Deliverable:** services/scheme-adapter/src/main/java/com/gme/pay/scheme/adapter/actuator/SchemeAdapterMetrics.java and updated SchemeAdapterPort.java with OTel spans
**Acceptance / logic checks:**
- Each SchemeAdapterPort method produces a trace span visible in Jaeger test exporter (InMemorySpanExporter in test)
- scheme_adapter_calls_total counter increments by 1 on each call
- scheme_adapter_call_duration_seconds histogram records a non-zero observation
- SchemeAdapterPort.submitMpm exception sets span status to ERROR and records exception
- Span attribute scheme.id = ZEROPAY is present on all spans produced by ZeroPaySchemeAdapter calls
**Depends on:** 9.1-T10, 9.1-T16

### 9.1-T25 — Unit tests for EmvcoQrParser with exact input-output vectors  _(40 min)_
**Context:** SCH-06 §3.4: CRC-16/CCITT over all QR bytes before tag 63; tag 00=01; tag 53=410 for KRW; ZeroPay MAI slot configurable. Tests must cover: valid parse, checksum mismatch, wrong currency, missing mandatory tag, unknown scheme MAI, configurable MAI tag. Use JUnit 5 with no Spring context (pure unit test, fast). All test inputs are hand-crafted TLV strings.
**Steps:** Create EmvcoQrParserTest in services/scheme-adapter/src/test/java/com/gme/pay/scheme/adapter/zeropay/qr/EmvcoQrParserTest.java; Test 1 valid QR: build TLV with tag00=01, tag52=5411, tag53=410, tag58=KR, tag59=TestMerchant, tag60=Seoul, MAI slot29 with merchant_id=M0000000001 and qr_code_id=QR00000000001234567890, compute and append correct CRC in tag63; assert parseMerchantQR returns MerchantIdentifier(merchantId=M0000000001, qrCodeId=QR00000000001234567890); Test 2 CRC mismatch: use valid QR string but flip last character; assert SchemeAdapterException code = QR_INVALID_CHECKSUM; Test 3 tag53 = 840 (USD): assert SchemeAdapterException code = QR_CURRENCY_MISMATCH; Test 4 missing tag00: assert QR_MALFORMED; Test 5 MAI tag 29 absent: assert QR_UNKNOWN_SCHEME; Test 6 configure mai-tag=31, use MAI slot31: assert correct merchant_id extracted
**Deliverable:** services/scheme-adapter/src/test/java/com/gme/pay/scheme/adapter/zeropay/qr/EmvcoQrParserTest.java (6 tests)
**Acceptance / logic checks:**
- All 6 tests pass in ./gradlew :services:scheme-adapter:test
- No Spring context loaded (test is pure unit, no @SpringBootTest)
- Test 1 merchant_id equals M0000000001 exactly
- Test 2 exception message contains QR_INVALID_CHECKSUM
- Test runtime < 500ms for all 6 tests combined
**Depends on:** 9.1-T11

### 9.1-T26 — Unit tests for Zp0011FileGenerator with exact numeric field assertions  _(35 min)_
**Context:** ZP0011 generator produces fixed-width batch records. Test vectors: 3 approved transactions for business date 2026-10-14 KST. Tx1: gme_txn_id=TX00000000000000000001, zeropay_txn_ref=ZP00000000000000000001, merchant_id=M000000001, payout_amount_krw=50000, merchant_fee_amt=250, van_fee_amt=100, partner_type=D, approval_code=AP000000000001. Tx2: international, payout_amount_krw=120000, partner_type=I. Tx3: domestic refunded — should NOT appear (status=REFUNDED, not APPROVED). Assertions target exact byte positions in output.
**Steps:** Create Zp0011FileGeneratorTest in services/scheme-adapter/src/test/java/com/gme/pay/scheme/adapter/zeropay/batch/Zp0011FileGeneratorTest.java; Stub TransactionQueryPort to return Tx1 and Tx2 for business_date 2026-10-14; Tx3 excluded by query; Assert header line starts with ZP0011 at position 0-5; business_date=20261014 at correct offset; Assert Tx1 detail line: payout_amount_krw field = 000000050000 (12 chars, zero-padded); Assert Tx2 partner_type field = I; Assert trailer control_sum = 000000000170000 (50000 + 120000 formatted to 15 digits); Assert file is idempotent: calling generate() twice with same stub returns byte-equal output
**Deliverable:** services/scheme-adapter/src/test/java/com/gme/pay/scheme/adapter/zeropay/batch/Zp0011FileGeneratorTest.java
**Acceptance / logic checks:**
- Test passes with correct payout_amount_krw field value 000000050000
- Trailer control_sum = 170000 (sum of Tx1+Tx2) padded to file width
- Tx3 (REFUNDED) is absent from output
- Idempotency assertion passes (byte-equal)
- Test uses Mockito; no DB or Spring context
**Depends on:** 9.1-T16

### 9.1-T27 — Unit tests for Zp0012ReconciliationService with all five mismatch scenarios  _(35 min)_
**Context:** SCH-06 §5.4: reconciliation must detect five conditions. Test vectors: outbound ZP0011 had 3 records: R1(zeropay_txn_ref=ZPR001, payout=50000), R2(ZPR002, payout=120000), R3(ZPR003, payout=30000). ZP0012 response: R1 result_code=00 registered_amount=50000; R2 result_code=9002 registered_amount=119000 (mismatch); R3 absent from ZP0012; ZP0012 contains extra record ZPR004 (anomaly not in ZP0011).
**Steps:** Create Zp0012ReconciliationServiceTest in services/scheme-adapter/src/test/java/com/gme/pay/scheme/adapter/zeropay/batch/Zp0012ReconciliationServiceTest.java; Build ZP0011 submitted record list (R1, R2, R3) and ZP0012 parsed record list (R1 ok, R2 amount mismatch, R4 anomaly; R3 absent); Call reconcile(submitted, received): ReconciliationResult; Assert result.settled contains R1 with status SETTLEMENT_REGISTERED; Assert result.failed contains R2 with status REGISTRATION_AMOUNT_MISMATCH and note showing expected=120000 actual=119000; Assert result.unknown contains R3 with status REGISTRATION_UNKNOWN; Assert result.anomalies contains ZPR004; Assert EventPublisher.publish was called with RegistrationExceptionEvent for R2, R3, ZPR004 (exactly 3 times via Mockito verify)
**Deliverable:** services/scheme-adapter/src/test/java/com/gme/pay/scheme/adapter/zeropay/batch/Zp0012ReconciliationServiceTest.java
**Acceptance / logic checks:**
- R1 in result.settled (count=1)
- R2 in result.failed with REGISTRATION_AMOUNT_MISMATCH (count=1)
- R3 in result.unknown (count=1)
- ZPR004 in result.anomalies (count=1)
- EventPublisher.publish invoked exactly 3 times (Mockito verify)
**Depends on:** 9.1-T17

### 9.1-T28 — Unit tests for Zp0061FileGenerator net vs gross settlement logic  _(35 min)_
**Context:** SCH-06 §7.1 settlement logic: Net (domestic, settlement_type=N): net_settlement_amount = gross_txn_amount - refund_amount - merchant_fee_total. Gross (international, settlement_type=G): net_settlement_amount = gross_txn_amount - refund_amount (merchant fee billed monthly separately). Test vector: Merchant A domestic, gross_txn_amount=500000, refund_amount=20000, merchant_fee_total=2400 => net=477600. Merchant B international, gross_txn_amount=300000, refund_amount=0 => net=300000 (fee NOT deducted). BatchPrerequisiteException test: ZP0012 not processed.
**Steps:** Create Zp0061FileGeneratorTest in services/scheme-adapter/src/test/java/com/gme/pay/scheme/adapter/zeropay/batch/Zp0061FileGeneratorTest.java; Stub TransactionQueryPort and BatchPrerequisiteService; Test 1 Merchant A net: assert net_settlement_amount field = 000000000477600; Test 2 Merchant B gross: assert net_settlement_amount = 000000000300000 and merchant_fee_total field = 000000000000 (zero, not deducted); Test 3 prerequisite not met: BatchPrerequisiteService.isZp0012Processed(date) returns false; assert generate() throws BatchPrerequisiteException; Test 4 file header grand total: Merchant A net 477600 + Merchant B gross 300000 = 777600 in header field
**Deliverable:** services/scheme-adapter/src/test/java/com/gme/pay/scheme/adapter/zeropay/batch/Zp0061FileGeneratorTest.java
**Acceptance / logic checks:**
- Merchant A net_settlement_amount = 477600 (exact integer)
- Merchant B net_settlement_amount = 300000; merchant_fee_total = 0
- BatchPrerequisiteException thrown when ZP0012 not processed
- File header grand total = 777600
- Tests use Mockito mocks; no Spring context; all pass in < 1s
**Depends on:** 9.1-T18

### 9.1-T29 — Integration test for SchemeAdapterRegistry with ZeroPaySchemeAdapter using Testcontainers  _(50 min)_
**Context:** End-to-end wiring test: Spring Boot test slice loads SchemeAdapterRegistry, ZeroPaySchemeAdapter (adapter.zeropay.enabled=true), SchemeConfigService backed by a real PostgreSQL 16 Testcontainers instance with Flyway V900-V904 applied. Test verifies registry resolves ZEROPAY adapter; healthCheck() returns DOWN (no live SFTP configured); parseMerchantQR with a valid test QR string (EMVCo, KRW, MAI slot configured) returns MerchantIdentifier after merchant is seeded in DB.
**Steps:** Create SchemeAdapterIntegrationTest in services/scheme-adapter/src/test/java/com/gme/pay/scheme/adapter/SchemeAdapterIntegrationTest.java annotated @SpringBootTest @Testcontainers; Declare @Container PostgreSQLContainer postgres = new PostgreSQLContainer(postgres:16-alpine); configure datasource in @DynamicPropertySource; Seed scheme_config with ZEROPAY row (status=ACTIVE, adapter.zeropay.mai-tag=29) and V900-V904 migrations; Seed merchants and qr_codes tables with test merchant M000000001 (status=ACTIVE) and QR QR00000000001234567890; Test 1: registry.resolve(ZEROPAY) does not throw; Test 2: adapter.healthCheck().status == DOWN (no SFTP host configured); Test 3: adapter.parseMerchantQR(validQrString) returns MerchantIdentifier with merchantId=M000000001
**Deliverable:** services/scheme-adapter/src/test/java/com/gme/pay/scheme/adapter/SchemeAdapterIntegrationTest.java
**Acceptance / logic checks:**
- Test 1 passes: registry.resolve(ZEROPAY) returns non-null
- Test 2: healthCheck status = DOWN with lastError containing not configured
- Test 3: parseMerchantQR returns merchantId = M000000001
- Testcontainers PostgreSQL 16 container starts without manual Docker setup
- All 3 assertions pass in ./gradlew :services:scheme-adapter:test
**Depends on:** 9.1-T12, 9.1-T13, 9.1-T22

### 9.1-T30 — Integration test for ZP0011-ZP0012 batch round-trip with Testcontainers PostgreSQL  _(55 min)_
**Context:** SCH-06 §5.2-5.4 full cycle: seed 3 APPROVED transactions in PostgreSQL; run Zp0011FileGenerator; parse output with Zp0011FileParser; simulate ZP0012 response (2 successes, 1 REGISTRATION_AMOUNT_MISMATCH); run Zp0012ReconciliationService; verify DB transaction statuses. Confirms generator-parser-reconciler pipeline in one test without mocking DB. Uses Testcontainers PostgreSQL 16.
**Steps:** Create BatchRoundTripIntegrationTest in services/scheme-adapter/src/test/java/com/gme/pay/scheme/adapter/zeropay/batch/BatchRoundTripIntegrationTest.java annotated @SpringBootTest @Testcontainers; Seed transactions: TxA(payout=50000 KRW, status=APPROVED), TxB(payout=120000 KRW, status=APPROVED), TxC(payout=30000 KRW, status=APPROVED); Run Zp0011FileGenerator.generate(businessDate) — capture BatchFile; Assert BatchFile has 3 detail records and trailer control_sum = 200000; Build synthetic ZP0012 response: TxA result_code=00 registered=50000; TxB result_code=00 registered=120000; TxC result_code=9002 registered=25000 (mismatch); Run Zp0012ReconciliationService.reconcile() — verify DB: TxA=SETTLEMENT_REGISTERED, TxB=SETTLEMENT_REGISTERED, TxC=REGISTRATION_AMOUNT_MISMATCH; Assert outbox table has 1 RegistrationExceptionEvent row for TxC
**Deliverable:** services/scheme-adapter/src/test/java/com/gme/pay/scheme/adapter/zeropay/batch/BatchRoundTripIntegrationTest.java
**Acceptance / logic checks:**
- ZP0011 file has 3 records and control_sum = 200000
- TxA DB status = SETTLEMENT_REGISTERED after reconciliation
- TxC DB status = REGISTRATION_AMOUNT_MISMATCH after reconciliation
- outbox table has exactly 1 RegistrationExceptionEvent row
- Test is repeatable: running twice produces same DB state (idempotency)
**Depends on:** 9.1-T17, 9.1-T23

### 9.1-T31 — Add Javadoc and MODULE.md for SchemeAdapter ACL boundary documentation  _(30 min)_
**Context:** The Scheme Adapter ACL is a key architectural boundary. Future developers adding a new scheme (SCH-06 §11) must understand: what methods to implement, that Hub Core may not import any concrete adapter class, how to register a new adapter via Spring @Service + @ConditionalOnProperty, and the file naming and PGP conventions. Documentation lives in code (Javadoc on SchemeAdapter interface) and a MODULE.md in services/scheme-adapter/. No emoji; plain ASCII.
**Steps:** Add comprehensive Javadoc to SchemeAdapter.java: interface-level comment explaining ACL purpose, one-paragraph warning IMPORTANT: Hub Core must not import any class from the zeropay sub-package, @see links to SCH-06 sections for each method group; Add @param and @return Javadoc to all 13 methods citing the SCH-06 section e.g. @see SCH-06 section 3.4 for parseMerchantQR; Create services/scheme-adapter/MODULE.md (plain ASCII, no emoji): sections: Purpose, ACL boundary rule, How to add a new scheme (5-step checklist matching SCH-06 §11.2 Steps 3-5), Spring wiring pattern (@ConditionalOnProperty), file naming convention, PGP key config; Add @apiNote Javadoc on ZeroPaySchemeAdapter: This adapter is Phase 2 pending KFTC spec (OI-SCH-01, OI-SCH-02); methods throw UnsupportedOperationException until then; Verify MODULE.md contains all five checklist steps
**Deliverable:** Updated SchemeAdapter.java Javadoc and services/scheme-adapter/MODULE.md
**Acceptance / logic checks:**
- SchemeAdapter.java Javadoc contains the ACL boundary warning on the interface declaration
- Each of the 13 methods has @param and @return Javadoc
- MODULE.md file exists at services/scheme-adapter/MODULE.md and is non-empty
- MODULE.md checklist Step 3 references @ConditionalOnProperty naming pattern
- ZeroPaySchemeAdapter class Javadoc references OI-SCH-01
**Depends on:** 9.1-T04, 9.1-T14, 9.1-T15


## WBS 9.2 — SFTP connectivity, keys, PGP
### 9.2-T01 — Gradle module setup: services/sftp-gateway build.gradle and application bootstrap  _(25 min)_
**Context:** STACK.md: Gradle multi-module monorepo. The sftp-gateway service handles ZeroPay PPF egress via SFTP. Module must be declared in root settings.gradle with build.gradle specifying: spring-boot-starter-web, spring-boot-starter-security, spring-boot-starter-data-jpa, spring-cloud-vault-config, software.amazon.awssdk:s3, org.bouncycastle:bcpg-jdk18on, org.apache.sshd:sshd-sftp (Apache SSHD 2.x for Java 21 compatibility), spring-retry, micrometer-registry-prometheus, opentelemetry-api, lib-money, lib-errors, lib-events, lib-persistence. Test: spring-boot-testcontainers, testcontainers:postgresql, testcontainers:localstack, wiremock-standalone, junit-jupiter. Java 21 toolchain.
**Steps:** Add include('services/sftp-gateway') to root settings.gradle if not present.; Create services/sftp-gateway/build.gradle with all dependencies; apply plugins: java, org.springframework.boot, io.spring.dependency-management; set java.toolchain.languageVersion = JavaLanguageVersion.of(21).; Create services/sftp-gateway/src/main/java/com/gme/pay/sftp/SftpGatewayApplication.java with @SpringBootApplication.; Create services/sftp-gateway/src/main/resources/application.yml with placeholder zeropay.sftp.* and zeropay.pgp.* config namespaces.; Run ./gradlew :services:sftp-gateway:compileJava and confirm zero errors; run ./gradlew :services:sftp-gateway:test with placeholder test.
**Deliverable:** services/sftp-gateway/build.gradle and services/sftp-gateway/src/main/java/com/gme/pay/sftp/SftpGatewayApplication.java
**Acceptance / logic checks:**
- ./gradlew :services:sftp-gateway:compileJava exits 0 with zero errors.
- All declared dependencies resolve (no 'Could not resolve' in build output).
- Java toolchain = 21 confirmed in build output.
- SftpGatewayApplication annotated @SpringBootApplication in package com.gme.pay.sftp.
- ./gradlew :services:sftp-gateway:test exits 0.

### 9.2-T02 — Define SftpGatewayProperties @ConfigurationProperties with Vault-backed secret refs  _(30 min)_
**Context:** SCH-06 §2.3 and SEC-09: all SFTP and PGP credentials are Vault references, never plaintext. Config namespace zeropay.sftp.*: host (String), port (int default 22), username (String), private_key_ref (Vault path), known_hosts_ref (Vault path for host-key pin), inbound_dir (default /gmepay/inbound/), outbound_dir (default /gmepay/outbound/), file_retention_days (int default 90), connect_timeout_ms (int default 30000), pool_max_connections (int default 3). Config namespace zeropay.pgp.*: scheme_public_key_ref (Vault path for hangulwon PGP public key), gme_private_key_ref (Vault path for GME PGP private key), gme_public_key_ref, use_armor (boolean default false), compression (ZLIB/ZIP/UNCOMPRESSED default ZLIB). All *_ref fields @NotBlank. No plaintext credential ever in application.yml or env vars.
**Steps:** Create SftpGatewayProperties.java (@ConfigurationProperties(prefix=zeropay.sftp)) with all 10 fields and @Validated.; Create PgpProperties.java (@ConfigurationProperties(prefix=zeropay.pgp)) with all 5 fields and @Validated.; Add @Configuration SftpGatewayPropertiesConfig that @EnableConfigurationProperties for both classes.; Write SftpGatewayPropertiesTest: bind a properties file with valid values, assert all fields populated; bind with blank host, assert ConstraintViolationException.; Verify sftp_port defaults to 22 and file_retention_days defaults to 90 when not set.
**Deliverable:** services/sftp-gateway/src/main/java/com/gme/pay/sftp/config/SftpGatewayProperties.java and PgpProperties.java
**Acceptance / logic checks:**
- @NotBlank on host/username/private_key_ref/known_hosts_ref/pgp *_ref fields prevents startup with blank values.
- sftp_port defaults to 22; file_retention_days defaults to 90.
- ConstraintViolationException thrown when sftp_host is empty string.
- PgpProperties.useArmor defaults to false and compression defaults to ZLIB.
- Test passes with ./gradlew :services:sftp-gateway:test -Dtest=SftpGatewayPropertiesTest.
**Depends on:** 9.2-T01

### 9.2-T03 — Flyway migrations V201-V203: sftp_transfer_log, sftp_file_archive, outbox_events tables  _(30 min)_
**Context:** PostgreSQL 16 schema for SFTP Gateway. sftp_transfer_log: id BIGSERIAL PK, file_name VARCHAR(60) NOT NULL, direction VARCHAR(10) CHECK(IN('OUTBOUND','INBOUND')), file_type VARCHAR(10) NOT NULL, business_date DATE NOT NULL, sequence_no SMALLINT NOT NULL DEFAULT 1, transferred_at TIMESTAMPTZ, bytes_transferred BIGINT, checksum_sha256 CHAR(64), status VARCHAR(20) NOT NULL DEFAULT 'PENDING', retry_count SMALLINT NOT NULL DEFAULT 0, object_storage_key VARCHAR(512), error_message TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT now(). UNIQUE(file_type, business_date, sequence_no, direction). sftp_file_archive: id BIGSERIAL PK, transfer_log_id BIGINT NOT NULL REFERENCES sftp_transfer_log(id), bucket VARCHAR(100), object_key VARCHAR(512) NOT NULL, archived_at TIMESTAMPTZ NOT NULL DEFAULT now(), expires_at TIMESTAMPTZ, compliance_hold BOOLEAN NOT NULL DEFAULT FALSE, pgp_encrypted BOOLEAN NOT NULL DEFAULT TRUE. outbox_events: id UUID PK DEFAULT gen_random_uuid(), event_type VARCHAR(100) NOT NULL, aggregate_id VARCHAR(100), payload JSONB, status VARCHAR(20) NOT NULL DEFAULT 'PENDING', created_at TIMESTAMPTZ NOT NULL DEFAULT now(), published_at TIMESTAMPTZ.
**Steps:** Create db/migration/V201__sftp_transfer_log.sql with sftp_transfer_log DDL including UNIQUE constraint and idx_stl_file_type_date index on (file_type, business_date).; Create db/migration/V202__sftp_file_archive.sql with sftp_file_archive DDL including FK to sftp_transfer_log.; Create db/migration/V203__sftp_outbox_events.sql with outbox_events DDL.; Run all three migrations via Flyway in order; verify clean apply on fresh Postgres 16 container.; Write Testcontainers migration test verifying V201-V203 apply in sequence with zero checksum errors.
**Deliverable:** db/migration/V201__sftp_transfer_log.sql, V202__sftp_file_archive.sql, V203__sftp_outbox_events.sql
**Acceptance / logic checks:**
- UNIQUE(file_type, business_date, sequence_no, direction) prevents duplicate transfer log entries.
- FK sftp_file_archive.transfer_log_id -> sftp_transfer_log(id) enforced by DB.
- Testcontainers migration test green (./gradlew :services:sftp-gateway:test -Dtest=SftpMigrationTest).
- CHECK constraint on direction accepts only OUTBOUND or INBOUND.
- outbox_events.payload column is JSONB type (not TEXT).
**Depends on:** 9.2-T01

### 9.2-T04 — Implement Ed25519 SSH key-pair generation and Vault storage via SshKeyService  _(40 min)_
**Context:** SEC-09 §2.5 and SCH-06 §2.3.1: GMEPay+ generates an Ed25519 key pair per environment (sandbox, production). Public key registered with hangulwon/KFTC for SFTP authentication. Private key stored in Vault at path zeropay/sftp/private_key - NEVER written to disk in plaintext. Key generation triggered by Admin API. Rotation: new pair written to zeropay/sftp/private_key_pending; old key at zeropay/sftp/private_key stays active until KFTC confirms new key. Rotation requires minimum 5 business-days lead time with KFTC (SEC-09 §9.3). Bouncy Castle used for key generation. RSA-4096 fallback if config use_rsa=true.
**Steps:** Add SshKeyService.java (@Service) with generateKeyPair(KeyAlgorithm algo): KeyPairResult - stores private key PEM to Vault via VaultTemplate at zeropay/sftp/private_key_pending, returns public key PEM string.; Add KeyAlgorithm enum: ED25519 (default), RSA_4096.; Add getCurrentPublicKey(): String - reads from active Vault path zeropay/sftp/private_key, returns public key PEM.; Expose GET /internal/sftp/ssh-public-key (ADMIN role) returning current public key PEM for Ops to send to KFTC.; Write SshKeyServiceTest: generate Ed25519 pair, sign test data with private key, verify with public key; assert private key PEM never appears in log output.
**Deliverable:** services/sftp-gateway/src/main/java/com/gme/pay/sftp/security/SshKeyService.java
**Acceptance / logic checks:**
- Ed25519 key pair: private key is 32-byte scalar; public key is 32 bytes (verified via BouncyCastle API).
- Private key PEM never written to logs (log capture assertion in test).
- GET /internal/sftp/ssh-public-key returns 403 without ADMIN role (Spring Security mock).
- generateKeyPair writes to _pending path; active path zeropay/sftp/private_key unchanged.
- Sign-verify round-trip test passes with generated key pair.
**Depends on:** 9.2-T02

### 9.2-T05 — Implement PGP key management service: import, Vault storage, and cache  _(40 min)_
**Context:** SCH-06 §2.3.2: outbound files PGP-encrypted with hangulwon public key + signed with GME private key. Inbound files encrypted with GME public key + signed by hangulwon. GME PGP key pair: RSA-4096 preferred, RSA-2048 minimum. Key material stored in Vault (paths: zeropay/pgp/scheme_public_key, zeropay/pgp/gme_private_key, zeropay/pgp/gme_public_key). PgpKeyService loads keys from Vault on first call and caches in memory (refreshable). Ops uploads keys via Admin API. Library: org.bouncycastle:bcpg-jdk18on. No key material written to disk.
**Steps:** Add PgpKeyService.java (@Service) with: importSchemePublicKey(byte[] armoredKey): void - stores to Vault zeropay/pgp/scheme_public_key; importGmeKeyPair(byte[] armoredPrivate, byte[] armoredPublic): void - stores to respective Vault paths; getSchemePublicKey(): PGPPublicKey; getGmeSecretKey(): PGPSecretKey.; Cache resolved keys in ConcurrentHashMap; expose reloadKeys(): void (flushes cache, forces next call to re-read Vault).; Expose POST /internal/sftp/pgp-keys/scheme-public (multipart, ADMIN role) and POST /internal/sftp/pgp-keys/gme-keypair (multipart, ADMIN role) endpoints.; Write PgpKeyServiceTest: import test RSA-4096 key pair, call getSchemePublicKey(), call getGmeSecretKey(), verify both non-null; test getSchemePublicKey() throws PgpKeyNotConfiguredException when Vault path empty.; Verify no key material bytes appear in application logs.
**Deliverable:** services/sftp-gateway/src/main/java/com/gme/pay/sftp/security/PgpKeyService.java
**Acceptance / logic checks:**
- getSchemePublicKey() throws PgpKeyNotConfiguredException when Vault path is empty.
- After importSchemePublicKey, getSchemePublicKey() returns parsed PGPPublicKey with correct key ID.
- POST endpoints return 403 without ADMIN role.
- reloadKeys() flushes cache; subsequent getSchemePublicKey() re-reads Vault.
- No armored key bytes appear in log output (log capture test).
**Depends on:** 9.2-T02

### 9.2-T06 — Implement PGP encrypt+sign and decrypt+verify operations in PgpCryptoService  _(40 min)_
**Context:** SCH-06 §2.3.2 and §2.4: outbound: PGP-encrypt with hangulwon public key AND sign with GME private key; inbound: decrypt with GME private key AND verify hangulwon signature. All operations in memory only - no cleartext intermediate files on disk. Bouncy Castle. Output: binary PGP packet (ASCII armor optional via PgpProperties.useArmor). Compression: ZLIB by default (PgpProperties.compression). The encrypted+signed output gets file extension .pgp appended (handled by naming tier T07).
**Steps:** Add PgpCryptoService.java (@Service) with encryptAndSign(byte[] plaintext, String filename, PGPPublicKey recipientKey, PGPSecretKey signerKey, char[] passphrase): byte[] using BouncyCastle PGPEncryptedDataGenerator + PGPSignatureGenerator.; Add decryptAndVerify(byte[] pgpData, PGPSecretKey recipientKey, char[] passphrase, PGPPublicKey expectedSigner): byte[] - throws PgpVerificationException on signer mismatch or invalid signature.; Apply compression (PgpProperties.compression) and armor (PgpProperties.useArmor) based on injected PgpProperties.; Write PgpCryptoServiceTest: round-trip 50KB random bytes (encrypt+sign then decrypt+verify, assert input equals output); tamper 1 byte in ciphertext and assert PgpVerificationException.; Write negative tests: wrong recipient private key throws PgpDecryptionException; wrong expected signer public key throws PgpVerificationException.
**Deliverable:** services/sftp-gateway/src/main/java/com/gme/pay/sftp/security/PgpCryptoService.java
**Acceptance / logic checks:**
- 50KB round-trip test passes with RSA-4096 key pair.
- Tampering 1 byte at offset 100 in ciphertext throws PgpVerificationException.
- decryptAndVerify with wrong signer public key throws PgpVerificationException.
- pgp_use_armor=true produces output starting with -----BEGIN PGP MESSAGE-----.
- Empty payload (byte[0]) round-trips to byte[0] without exception.
**Depends on:** 9.2-T05

### 9.2-T07 — Implement ZpFileName builder and parser for SCH-06 naming convention  _(25 min)_
**Context:** SCH-06 §2.3.3: file naming pattern {FILE_ID}_{YYYYMMDD}_{SEQ}.dat.pgp where FILE_ID = ZeroPay file type code (e.g. ZP0011), YYYYMMDD = business date KST (UTC+9), SEQ = 2-digit zero-padded sequence starting at 01 (retransmission increments). Full example: ZP0011_20261015_01.dat.pgp. Parser must extract all three parts. ZpFileType enum: ZP0011, ZP0012, ZP0021, ZP0022, ZP0041, ZP0043, ZP0045, ZP0047, ZP0051, ZP0053, ZP0055, ZP0061, ZP0062, ZP0063, ZP0064, ZP0065, ZP0066 (17 codes total). Business date always in KST (ZoneId Asia/Seoul) regardless of server timezone.
**Steps:** Create ZpFileType.java enum with all 17 codes and direction field (OUTBOUND/INBOUND per SCH-06 §8.1 table).; Create ZpFileName.java (immutable record): ZpFileType fileType, LocalDate businessDate, int sequenceNo; static of(ZpFileType, LocalDate, int), static parse(String filename), String toFileName().; Builder validates: sequenceNo 1..99; throws ZpFileNameException for sequenceNo=0 or >99.; Parser validates regex ^(ZP[0-9]{4})_(\d{8})_(\d{2})\.dat\.pgp$ throws ZpFileNameException for non-match.; Write ZpFileNameTest: build ZP0011/2026-10-15/seq=1 -> ZP0011_20261015_01.dat.pgp; parse it back; verify retransmit(name) increments seq to 02; parse malformed name throws exception.
**Deliverable:** services/sftp-gateway/src/main/java/com/gme/pay/sftp/naming/ZpFileName.java and ZpFileType.java
**Acceptance / logic checks:**
- ZP0011_20261015_01.dat.pgp built correctly for fileType=ZP0011, date=2026-10-15, seq=1.
- parse(ZP0011_20261015_01.dat.pgp).sequenceNo = 1, fileType = ZP0011, businessDate = 2026-10-15.
- sequenceNo=0 throws ZpFileNameException; sequenceNo=100 throws ZpFileNameException.
- ZpFileType enum has exactly 17 entries.
- parse(ZP0011_2026101_01.dat.pgp) throws ZpFileNameException (wrong date length).

### 9.2-T08 — Implement SFTP client with Apache SSHD, host-key pinning, and KnownHostsService  _(50 min)_
**Context:** SEC-09 §2.5 and SCH-06 §2.3.1 and §2.4: GMEPay+ initiates all SFTP connections outbound-only to hangulwon SFTP server. Host key must be pinned (Vault path zeropay/sftp/known_hosts, OpenSSH known_hosts format). Mismatch throws SftpConnectionException(HOST_KEY_MISMATCH). Library: org.apache.sshd:sshd-sftp (Java 21 compatible). Connection pool: max 3 connections (zeropay.sftp.pool_max_connections). Connect timeout 30 000 ms (zeropay.sftp.connect_timeout_ms). Private key loaded from Vault (zeropay/sftp/private_key) via SshKeyService at session open time.
**Steps:** Add KnownHostsService.java (@Service) with loadPinnedHostKey(): HostKeyEntry; isValid(String hostname, PublicKey serverKey): boolean; expose POST /internal/sftp/known-hosts (ADMIN) to update pin in Vault.; Add SftpClientFactory.java (@Component) building Apache SSHD SftpClient authenticated with Ed25519 key from SshKeyService; on connect verify host key via KnownHostsService.isValid; throw SftpConnectionException(HOST_KEY_MISMATCH) on mismatch.; SftpSession.java (AutoCloseable) wraps SftpClient; exposes put(InputStream, String remotePath): void; get(String remotePath): InputStream; exists(String remotePath): boolean; size(String remotePath): long.; Write SftpClientFactoryTest using Testcontainers (openssh container or WireMock SFTP stub): connect, put 1KB, get back, assert bytes equal; present wrong host key, assert HOST_KEY_MISMATCH exception.; Write KnownHostsServiceTest: valid known_hosts line -> isValid=true; modified key -> isValid=false.
**Deliverable:** services/sftp-gateway/src/main/java/com/gme/pay/sftp/client/SftpClientFactory.java and KnownHostsService.java
**Acceptance / logic checks:**
- Wrong host key throws SftpConnectionException with code HOST_KEY_MISMATCH (not a generic IOException).
- put then size returns same byte count as uploaded stream.
- Session is closed after try-with-resources (AutoCloseable).
- POST /internal/sftp/known-hosts without ADMIN role returns 403.
- loadPinnedHostKey() throws SftpGatewayException(KNOWN_HOSTS_NOT_CONFIGURED) when Vault path empty.
**Depends on:** 9.2-T04, 9.2-T02

### 9.2-T09 — Implement ObjectStorageService: S3-compatible client, bucket lifecycle, and archival  _(35 min)_
**Context:** STACK.md: Object Storage (S3-compatible) for ZeroPay SFTP files. Local dev: MinIO (docker-compose, endpoint http://minio:9000, bucket gme-zeropay). Prod: AWS S3 gme-zeropay-prod in ap-northeast-2 (Seoul, Korea data-residency per SAD-02 §10.4). AWS SDK v2 (software.amazon.awssdk:s3). Retention: 90 days configurable (file_retention_days), 7-year minimum for settlement evidence (SEC-09). Lifecycle: transition to STANDARD_IA after 90 days, GLACIER after 365, expire after 7*365=2555 days. Files stored PGP-encrypted (pgp_encrypted=true in sftp_file_archive). Archive key pattern: zeropay/batch/{direction}/{yyyy}/{MM}/{dd}/{filename}.
**Steps:** Add ObjectStorageConfig.java (@Configuration) creating S3Client @Bean from zeropay.object-storage.* config (endpoint-override for MinIO, region, bucket).; Add ObjectStorageService.java (@Service) with: put(String key, byte[] data, Map<String,String> metadata): void; get(String key): byte[]; exists(String key): boolean.; On @PostConstruct: if bucket lifecycle not yet set, apply lifecycle rule via S3Client.putBucketLifecycleConfiguration (STANDARD_IA at 90d, GLACIER at 365d, expire at 2555d).; Add to docker-compose.yml: MinIO service with bucket creation init script for gme-zeropay if not present.; Write ObjectStorageServiceTest (Testcontainers MinIO): put then get round-trip for 1MB payload; exists returns false for absent key; bucket lifecycle rule set on startup.
**Deliverable:** services/sftp-gateway/src/main/java/com/gme/pay/sftp/storage/ObjectStorageService.java
**Acceptance / logic checks:**
- put then get returns byte-identical payload for 1MB test data.
- exists returns false for non-existent key.
- Bucket lifecycle rule: expire after 2555 days verified via S3Client.getBucketLifecycleConfiguration.
- S3Client uses endpoint-override when zeropay.object-storage.endpoint-override is configured.
- No AWS credentials appear in ObjectStorageConfig or application context log.
**Depends on:** 9.2-T01

### 9.2-T10 — Implement outbound SFTP transfer pipeline: encrypt, upload, verify, archive to Object Storage  _(55 min)_
**Context:** SCH-06 §2.4: outbound process: (1) content generated in memory, (2) PGP-encrypt with hangulwon public key + sign with GME private key, (3) SFTP put to /gmepay/outbound/{filename}, (4) verify by comparing remote file size with local encrypted size, (5) archive encrypted bytes to Object Storage at zeropay/batch/outbound/{yyyy}/{MM}/{dd}/{filename}, (6) persist sftp_transfer_log row (PostgreSQL) with status SUCCESS. Retry policy: up to 3 attempts with 5-minute intervals (Spring Retry); after 3 failures emit SftpTransferFailed Outbox event to outbox_events table in same @Transactional as sftp_transfer_log update. Sequence_no incremented on retransmission per SCH-06 §9.4.
**Steps:** Add OutboundTransferService.java (@Service) with transferOutbound(ZpFileType, LocalDate businessDate, byte[] plaintext): TransferResult; builds ZpFileName, calls PgpCryptoService.encryptAndSign, calls SftpClientFactory.; On SFTP success: verify remote size == local encrypted size; archive to ObjectStorageService; persist sftp_transfer_log status=SUCCESS in @Transactional.; On failure: persist sftp_transfer_log status=FAILED, increment retry_count; RetryTemplate (Spring Retry, maxAttempts=3, fixedBackoff=300_000ms) wraps SFTP put+verify; after 3 failures publish SftpTransferFailed event to outbox_events in same @Transactional.; Write OutboundTransferServiceTest (Testcontainers Postgres + MinIO + WireMock SFTP): success path creates sftp_transfer_log SUCCESS row and Object Storage object; failure after 3 retries emits SftpTransferFailed event.; Verify @Transactional: mock exception after sftp_transfer_log insert, assert no Outbox row created (atomicity).
**Deliverable:** services/sftp-gateway/src/main/java/com/gme/pay/sftp/transfer/OutboundTransferService.java
**Acceptance / logic checks:**
- sftp_transfer_log row: direction=OUTBOUND, status=SUCCESS, object_storage_key set on happy path.
- Remote size mismatch throws SftpTransferVerificationException and marks status=FAILED.
- After 3 retries (retry_count=3) SftpTransferFailed event in outbox_events; no 4th SFTP attempt.
- Object Storage key matches zeropay/batch/outbound/{yyyy}/{MM}/{dd}/{ZpFileName}.
- Outbox write and sftp_transfer_log update are in one DB transaction (rollback test passes).
**Depends on:** 9.2-T06, 9.2-T07, 9.2-T08, 9.2-T09, 9.2-T03

### 9.2-T11 — Implement inbound SFTP polling, download, decrypt+verify, archive  _(45 min)_
**Context:** SCH-06 §2.4: inbound process: poll /gmepay/inbound/ for expected file, (1) download to in-memory buffer (no cleartext disk write), (2) verify size > 0, (3) PGP-decrypt with GME private key + verify hangulwon signature, (4) archive encrypted original to Object Storage at zeropay/batch/inbound/{yyyy}/{MM}/{dd}/{filename}, (5) persist sftp_transfer_log direction=INBOUND, (6) return plaintext bytes. If file not yet present: return Optional.empty() (no error; caller handles timing). On PgpVerificationException: persist status=FAILED, emit SftpInboundVerificationFailed Outbox event, return Optional.empty(). Poll schedule per SCH-06 §8.1: ZP0012 start 04:50 KST every 5 min until 06:00; ZP0062 from 09:00 every 10 min until 12:00; ZP0064 from 18:00 every 10 min until 21:00; merchant/QR sync hourly 01:00-08:00 KST.
**Steps:** Add InboundTransferService.java (@Service) with fetchInbound(ZpFileType, LocalDate, int seqNo): Optional<byte[]>; uses SftpClientFactory.exists then get if present.; Archive encrypted bytes to ObjectStorageService; persist sftp_transfer_log direction=INBOUND in @Transactional.; PGP decrypt+verify via PgpCryptoService; on PgpVerificationException: persist FAILED, publish SftpInboundVerificationFailed to outbox_events.; Write InboundTransferServiceTest: mock SFTP returning encrypted test file (built with PgpCryptoService); assert decrypted plaintext correct; assert sftp_transfer_log INBOUND SUCCESS; assert tampered file emits verification failed event.; Verify Optional.empty() returned when SFTP exists() returns false.
**Deliverable:** services/sftp-gateway/src/main/java/com/gme/pay/sftp/transfer/InboundTransferService.java
**Acceptance / logic checks:**
- Returns Optional.empty() when file absent on remote (no exception thrown).
- sftp_transfer_log: direction=INBOUND, status=SUCCESS, object_storage_key set on success.
- Tampered ciphertext -> SftpInboundVerificationFailed in outbox_events and status=FAILED.
- Decrypted plaintext equals original test payload.
- Archive Object Storage key matches zeropay/batch/inbound/{yyyy}/{MM}/{dd}/{filename}.
**Depends on:** 9.2-T06, 9.2-T08, 9.2-T10

### 9.2-T12 — Implement domain events in lib-events for SFTP Gateway  _(30 min)_
**Context:** STACK.md messaging: transactional Outbox in PostgreSQL; Kafka deferred. All domain event schemas defined in lib-events (shared Gradle module). Events needed for WBS 9.2: SftpTransferFailedEvent (outbound after 3 retries), SftpInboundVerificationFailedEvent (PGP verify failure), BatchDeadlineMissedEvent (batch not completed by KST window), RegistrationExceptionEvent (ZP0012/ZP0022 result failure), AnomalousZp0012RecordEvent (ZP0012 record absent from GME DB), SettlementDiscrepancyEvent (ZP0062/ZP0064 amount mismatch), SettlementCompletedEvent (settlement cycle done, feeds partner webhook per API-05 settlement.completed), PrefundingReversalRequestedEvent (OVERSEAS refund confirmed). Each event implements DomainEvent interface (lib-events): String eventId (UUID), String eventType (constant), Instant occurredAt, String aggregateId, Map<String,Object> payload.
**Steps:** Create lib-events/src/main/java/com/gme/pay/events/sftp/ package with one Java record per event (8 total), each implementing DomainEvent.; Each record: String eventId (auto UUID via static factory), String eventType (static final constant e.g. SFTP_TRANSFER_FAILED), Instant occurredAt, String aggregateId, Map<String,Object> payload.; Write JSON serialisation test for each event verifying ObjectMapper round-trip.; Register all 8 event type constants in EventTypeRegistry if it exists in lib-events.; Run ./gradlew :lib-events:test to confirm all tests green.
**Deliverable:** lib-events/src/main/java/com/gme/pay/events/sftp/ (8 event records)
**Acceptance / logic checks:**
- All 8 event records compile and serialise to valid JSON (ObjectMapper round-trip test).
- Each has a distinct static eventType constant string (no collision; verify with set-deduplication test).
- DomainEvent interface implemented by all 8 records.
- ./gradlew :lib-events:test exits 0.
- No two eventType constants are equal (uniqueness test on all 8 values).

### 9.2-T13 — Implement OutboxEventPublisher writing to outbox_events table  _(30 min)_
**Context:** STACK.md: Phase 1 uses transactional Outbox in PostgreSQL. EventPublisher interface (lib-events) abstracts the transport. sftp-gateway publishes via OutboxEventPublisher which inserts rows into outbox_events (V203 migration) in the same @Transactional as the primary DB operation. outbox_events columns: id UUID, event_type, aggregate_id, payload JSONB, status=PENDING, created_at. Kafka wiring is deferred; this ticket adds only the outbox-insertion publisher. An outbox-polling relay (separate concern) will later promote rows to Kafka.
**Steps:** Add OutboxEventPublisher.java (@Service implementing EventPublisher from lib-events) that inserts into outbox_events via JdbcTemplate or Spring Data JPA repository in caller's existing @Transactional.; Serialize DomainEvent.payload Map to JSONB string via ObjectMapper.; Write OutboxEventPublisherTest (Testcontainers Postgres): publish SftpTransferFailedEvent; assert outbox_events row with status=PENDING and correct event_type.; Atomicity test: call publisher inside @Transactional, then throw RuntimeException; assert zero outbox_events rows (rollback confirmed).; Verify EventPublisher interface is the only import in callers (no direct Kafka or outbox_events table reference in OutboundTransferService).
**Deliverable:** services/sftp-gateway/src/main/java/com/gme/pay/sftp/events/OutboxEventPublisher.java
**Acceptance / logic checks:**
- outbox_events row created with status=PENDING, event_type correct, payload valid JSONB.
- Rollback test: exception after publish -> zero outbox rows (atomic with primary operation).
- EventPublisher interface (not OutboxEventPublisher directly) is what callers inject.
- No Kafka dependency imported in sftp-gateway build.gradle.
- Testcontainers test green.
**Depends on:** 9.2-T12, 9.2-T03

### 9.2-T14 — Implement BatchJobService: aggregate transactions and generate ZP0011 payment result file  _(55 min)_
**Context:** SCH-06 §5.2: ZP0011 (GME->ZP ~02:00 KST) fixed-width file. Header: file_type=ZP0011, business_date, GME institution_code, total_record_count, total_payout_amount_krw (KRW integer, 0 decimals). Detail per transaction (layout per SCH-06 §5.2 field list; exact widths TBD per OI-SCH-01 - implement via configurable FixedWidthFileBuilder): zeropay_txn_ref CHAR(20), gme_txn_id CHAR(20), merchant_id CHAR(10), txn_date DATE (YYYYMMDD), amount_krw NUMERIC(15,0), approval_code CHAR(10). Trailer: record_count repeated, control_sum = sum(amount_krw). Source: transactions table (PostgreSQL) WHERE status='APPROVED' AND settlement_date=:businessDate AND settlement_batch_id IS NULL. Exactly-once: set settlement_batch_id atomically in same @Transactional using SELECT...FOR UPDATE before SFTP upload.
**Steps:** Add BatchJobService.java (@Service) with generateZp0011(LocalDate businessDate): byte[]; query transactions via TransactionRepository using SELECT t.* FROM transactions t WHERE t.settlement_date=:date AND t.status='APPROVED' AND t.settlement_batch_id IS NULL FOR UPDATE.; Claim rows: set settlement_batch_id (UUID for this batch run) on all included transactions in @Transactional; commit before SFTP upload.; Build header/detail/trailer via FixedWidthFileBuilder (new utility class same module); control_sum = sum(payout_amount_krw).; Call OutboundTransferService.transferOutbound(ZP0011, businessDate, bytes); persist settlement_batch row.; Write BatchJobServiceTest (Testcontainers Postgres): seed 5 transactions, call generateZp0011, assert header.record_count=5, trailer.control_sum=sum; re-run asserts 0 records (idempotent).
**Deliverable:** services/sftp-gateway/src/main/java/com/gme/pay/sftp/batch/BatchJobService.java (generateZp0011 method)
**Acceptance / logic checks:**
- header.total_payout_amount_krw = sum of all 5 detail record amounts.
- trailer.control_sum = same sum value as header total.
- Idempotency: second generateZp0011 call for same businessDate includes 0 transactions.
- SELECT...FOR UPDATE prevents concurrent double-inclusion (Testcontainers concurrency test with 2 threads).
- settlement_batch_id set on transaction rows in same @Transactional as file generation (mock SFTP failure - verify DB settlement_batch_id still set).
**Depends on:** 9.2-T10, 9.2-T03

### 9.2-T15 — Implement BatchJobService: generate ZP0021 refund result file  _(40 min)_
**Context:** SCH-06 §6.2: ZP0021 (GME->ZP ~02:00 KST) mirrors ZP0011 structure for refunds. Header: file_type=ZP0021, business_date, institution_code, record_count, total_refund_amount_krw. Detail: original_zeropay_txn_ref CHAR(20), gme_refund_id CHAR(20), merchant_id CHAR(10), refund_date DATE, refund_amount_krw NUMERIC(15,0) (stored as positive value in DB, written as negative in file per convention), refund_reason_code CHAR(4). Trailer: control_sum = sum(abs(refund_amount_krw)). Source: refunds table WHERE status='REFUND_PENDING' AND refund_date=:businessDate AND refund_batch_id IS NULL. State transition: REFUND_PENDING -> REFUND_SUBMITTED atomically with batch claim. Empty file (0 refunds) still transmitted with valid header+trailer (record_count=0, control_sum=0).
**Steps:** Add generateZp0021(LocalDate businessDate): byte[] in BatchJobService; SELECT r.* FROM refunds r WHERE r.status='REFUND_PENDING' AND r.refund_date=:date AND r.refund_batch_id IS NULL FOR UPDATE.; Set refund_batch_id and status=REFUND_SUBMITTED in same @Transactional; build file via FixedWidthFileBuilder.; control_sum = sum(abs(refund_amount_krw)) for all detail records.; Call OutboundTransferService.transferOutbound(ZP0021, businessDate, bytes).; Write test: 3 REFUND_PENDING refunds; call generateZp0021; assert status=REFUND_SUBMITTED; control_sum correct; re-run asserts 0; assert empty file path (0 refunds) produces valid header record_count=0.
**Deliverable:** services/sftp-gateway/src/main/java/com/gme/pay/sftp/batch/BatchJobService.java (generateZp0021 method)
**Acceptance / logic checks:**
- REFUND_PENDING -> REFUND_SUBMITTED transition in same @Transactional.
- Empty day (0 refunds): valid file with header file_type=ZP0021, record_count=0, trailer control_sum=0.
- control_sum = sum(abs(refund_amount_krw)) for all detail records.
- Idempotency: second call for same businessDate includes 0 records.
- detail record refund_amount_krw values written as negative numbers in file bytes.
**Depends on:** 9.2-T14

### 9.2-T16 — Implement ZP0012 parser and payment registration reconciliation  _(50 min)_
**Context:** SCH-06 §5.3-§5.4: ZP0012 (ZP->GME ~05:00 KST) contains registration status per ZP0011 record. Match key: zeropay_txn_ref + txn_date. Outcomes: result_code=00 and amounts match -> SETTLEMENT_REGISTERED; non-zero result_code -> REGISTRATION_FAILED + RegistrationExceptionEvent; absent in ZP0012 (in ZP0011 but missing from ZP0012) -> REGISTRATION_UNKNOWN + RegistrationExceptionEvent; in ZP0012 but absent from GME DB -> AnomalousZp0012RecordEvent. Tolerance: zero (every record must match). After reconciliation: if any REGISTRATION_FAILED or REGISTRATION_UNKNOWN exist for businessDate, ZP0061 generation for that date is blocked. All transitions @Transactional.
**Steps:** Add Zp0012Parser.java (@Component) parsing fixed-width ZP0012 bytes into List<Zp0012Record>; validate header file_type=ZP0012 and trailer record_count == parsed count (throws Zp0012ParseException on mismatch).; Add Zp0012ReconciliationService.java (@Service) with reconcile(LocalDate, byte[] zp0012Bytes): ReconciliationResult; line-by-line match against transactions with settlement_batch_id for businessDate.; Apply status transitions in batch UPDATE within single @Transactional; publish RegistrationExceptionEvent per FAILED and UNKNOWN record; publish AnomalousZp0012RecordEvent for ZP0012-extra records.; ReconciliationResult.blockedForSettlement = true if any REGISTRATION_FAILED count > 0.; Write Zp0012ReconciliationServiceTest (Testcontainers Postgres): 5 txns in ZP0011, ZP0012 has 4 (code=00) + 1 (code=01) + 1 extra; assert 4 SETTLEMENT_REGISTERED, 1 REGISTRATION_FAILED, 1 REGISTRATION_UNKNOWN (record 5 absent from ZP0012), 1 AnomalousZp0012RecordEvent, blockedForSettlement=true.
**Deliverable:** services/sftp-gateway/src/main/java/com/gme/pay/sftp/batch/reconcile/Zp0012ReconciliationService.java
**Acceptance / logic checks:**
- 5-txn test: 4 SETTLEMENT_REGISTERED, 1 REGISTRATION_FAILED, 1 REGISTRATION_UNKNOWN, 1 AnomalousZp0012RecordEvent in outbox.
- Trailer record_count mismatch throws Zp0012ParseException.
- ReconciliationResult.blockedForSettlement=true when REGISTRATION_FAILED count > 0.
- All status transitions in one @Transactional (partial failure rollback test).
- Amount mismatch in matching record -> REGISTRATION_AMOUNT_MISMATCH status + RegistrationExceptionEvent.
**Depends on:** 9.2-T14, 9.2-T11, 9.2-T13

### 9.2-T17 — Implement ZP0022 parser and refund registration reconciliation  _(40 min)_
**Context:** SCH-06 §6.3-§6.4: ZP0022 (ZP->GME ~05:00 KST) mirrors ZP0012 for refunds. Match key: original_zeropay_txn_ref + refund_date. Outcomes: result_code=00 -> REFUND_CONFIRMED; non-zero -> REFUND_FAILED + RefundRegistrationFailedEvent; absent -> REFUND_UNKNOWN. For OVERSEAS partners (partner.type=OVERSEAS): on REFUND_CONFIRMED, publish PrefundingReversalRequestedEvent (NOT direct DB update to prefunding - that is in Prefunding/Balance service). Unresolved refund failure blocking settlement is P1 incident.
**Steps:** Add Zp0022Parser.java (@Component) parsing ZP0022 bytes; validate header file_type=ZP0022 and trailer count (throws Zp0022ParseException).; Add Zp0022ReconciliationService.java (@Service) with reconcile(LocalDate, byte[] zp0022Bytes): void; match refunds with refund_batch_id for date.; Transitions: REFUND_SUBMITTED -> REFUND_CONFIRMED or REFUND_FAILED; publish events via OutboxEventPublisher in same @Transactional.; PrefundingReversalRequestedEvent only when partner.type=OVERSEAS and result=CONFIRMED; LOCAL partners do not get this event.; Write Zp0022ReconciliationServiceTest: 3 REFUND_SUBMITTED (2 OVERSEAS + 1 LOCAL), ZP0022 has 2 success + 1 failure; assert 2 REFUND_CONFIRMED + 1 REFUND_FAILED + 1 RefundRegistrationFailedEvent + 2 PrefundingReversalRequestedEvents (both successful OVERSEAS refunds) + 0 for LOCAL.
**Deliverable:** services/sftp-gateway/src/main/java/com/gme/pay/sftp/batch/reconcile/Zp0022ReconciliationService.java
**Acceptance / logic checks:**
- OVERSEAS REFUND_CONFIRMED -> PrefundingReversalRequestedEvent in outbox (not direct DB update to prefunding table).
- LOCAL REFUND_CONFIRMED -> NO PrefundingReversalRequestedEvent.
- REFUND_FAILED + RefundRegistrationFailedEvent per failed record.
- Zp0022ParseException on trailer count mismatch.
- All transitions @Transactional (rollback test).
**Depends on:** 9.2-T15, 9.2-T11, 9.2-T13

### 9.2-T18 — Implement ZP0061 morning settlement request file generation  _(50 min)_
**Context:** SCH-06 §7.2 ZP0061 and NFR §5.3: ZP0061 (GME->ZP ~05:00 KST) after ZP0012 confirmed. Settlement totals for SETTLEMENT_REGISTERED transactions for prior business day. Header: file_type=ZP0061, business_date, institution_code, record_count, total_payout_krw. Detail per merchant: merchant_id CHAR(10), transaction_count INT, total_payout_amount NUMERIC(15,0), net_or_gross_flag CHAR(1) (N=net/domestic LOCAL partner, G=gross/international OVERSEAS partner). Trailer: control_sum. Prerequisite: ZP0011 status=SUCCESS AND ZP0012 received AND ReconciliationResult.blockedForSettlement=false. Exactly-once: set morning_batch_id on transaction rows in same @Transactional. Each payment transaction in exactly one settlement file (ZP0061 or ZP0063) - NFR §5.3.
**Steps:** Add generateZp0061(LocalDate businessDate): byte[] in BatchJobService; SELECT transactions WHERE status='SETTLEMENT_REGISTERED' AND settlement_date=:date AND morning_batch_id IS NULL FOR UPDATE.; Validate prerequisite: sftp_transfer_log ZP0012 status=SUCCESS for businessDate AND Zp0012ReconciliationService.blockedForSettlement=false; else throw BatchPrerequisiteException.; Group by merchant_id; apply net/gross flag per partner.type; compute merchant totals.; Set morning_batch_id in same @Transactional; insert settlement_batch row; call OutboundTransferService.transferOutbound(ZP0061, ...).; Write test: 3 LOCAL + 2 OVERSEAS transactions across 2 merchants; assert net_or_gross_flag correct per merchant; assert prerequisite throws when ZP0012 absent.
**Deliverable:** services/sftp-gateway/src/main/java/com/gme/pay/sftp/batch/BatchJobService.java (generateZp0061 method)
**Acceptance / logic checks:**
- net_or_gross_flag=N for LOCAL partner merchant rows; G for OVERSEAS.
- BatchPrerequisiteException when ZP0012 not status=SUCCESS in sftp_transfer_log.
- total_payout_krw in header = sum of all merchant total_payout_amount values.
- morning_batch_id set on all included transactions in same @Transactional.
- Idempotency: second generateZp0061 call returns 0 records for same businessDate.
**Depends on:** 9.2-T16, 9.2-T14

### 9.2-T19 — Implement ZP0062/ZP0064 parsers and settlement result reconciliation  _(50 min)_
**Context:** SCH-06 §7.2 ZP0062 (ZP->GME ~10:00 KST) and ZP0064 (~19:00 KST): settlement result files confirming per-merchant settlement amounts. Reconciliation rule: for each merchant, sum(target_payout_krw) of SETTLEMENT_REGISTERED transactions in GME DB must equal merchant total in ZP0062/ZP0064. Tolerance: ZERO (1 KRW mismatch = SETTLEMENT_DISCREPANCY). On full match: SETTLEMENT_CONFIRMED + SettlementCompletedEvent (feeds partner settlement.completed webhook; payload must include batch_file_ref e.g. ZP0062-{businessDate}). On mismatch: SETTLEMENT_DISCREPANCY + SettlementDiscrepancyEvent per merchant. ZP0064 uses identical logic for afternoon batch (ZP0063 subset).
**Steps:** Add Zp0062Parser.java and Zp0064Parser.java (@Component) parsing per-merchant result records; validate header file_type and trailer count (throw ZpSettlementResultParseException on mismatch).; Add SettlementResultReconciliationService.java (@Service) with reconcile(ZpFileType resultFileType, LocalDate, byte[] bytes): ReconciliationSummary.; Compute GME per-merchant totals from DB; compare to file totals; apply transitions in batch UPDATE + publish events in single @Transactional.; SettlementCompletedEvent.payload must include batch_file_ref, total_payout_krw, business_date (required by API-05 settlement.completed webhook).; Write test: Merchant A GME 500 000 KRW / ZP0062 500 000 (match) + Merchant B GME 200 000 / ZP0062 199 999 (1 KRW mismatch); assert 1 SettlementDiscrepancyEvent (B) + SettlementCompletedEvent present.
**Deliverable:** services/sftp-gateway/src/main/java/com/gme/pay/sftp/batch/reconcile/SettlementResultReconciliationService.java
**Acceptance / logic checks:**
- 1 KRW discrepancy triggers SETTLEMENT_DISCREPANCY + SettlementDiscrepancyEvent for that merchant.
- Full match -> SETTLEMENT_CONFIRMED transitions and SettlementCompletedEvent published.
- SettlementCompletedEvent.payload contains batch_file_ref=ZP0062-{businessDate}.
- ZpSettlementResultParseException on file_type mismatch.
- Idempotent: processing ZP0062 twice for same businessDate does not emit duplicate events.
**Depends on:** 9.2-T18, 9.2-T11, 9.2-T13

### 9.2-T20 — Implement ZP0063 afternoon settlement request and ZP0065/ZP0066 detail files  _(50 min)_
**Context:** SCH-06 §7.2: ZP0063 (14:00 KST) covers transactions approved after ZP0061 cutoff (04:30 KST) for same business day; same structure as ZP0061. ZP0065 (22:00 KST): transaction-level detail for ZP0061+ZP0063; one row per transaction: txn_id, merchant_id, txn_date, payout_amount_krw, zeropay_txn_ref, approval_code, settlement_batch_ref (link back to ZP0061 or ZP0063 file name). ZP0066 (22:00 KST): mirrors ZP0065 for refunds. Prerequisite ZP0063: morning cycle complete (ZP0062 received per sftp_transfer_log). Prerequisite ZP0065/ZP0066: both ZP0062 and ZP0064 received. Exactly-once for ZP0063: afternoon_batch_id on transactions. Each transaction in exactly one ZP006x detail file.
**Steps:** Add generateZp0063(LocalDate): byte[] in BatchJobService; SELECT SETTLEMENT_REGISTERED WHERE morning_batch_id IS NULL AND afternoon_batch_id IS NULL FOR UPDATE; set afternoon_batch_id; guard on ZP0062 received.; Add generateZp0065(LocalDate): byte[] expanding all ZP0061+ZP0063 transactions to line-item detail; settlement_batch_ref = ZP0061 or ZP0063 file name per transaction.morning_batch_id/afternoon_batch_id.; Add generateZp0066(LocalDate): byte[] same structure for refunds in settlement batches.; Guard ZP0065/ZP0066: both ZP0062 and ZP0064 in sftp_transfer_log with status=SUCCESS; else BatchPrerequisiteException.; Write test: 5 morning + 2 afternoon transactions; assert ZP0065 has 7 detail rows; settlement_batch_ref correct per row; idempotency check.
**Deliverable:** services/sftp-gateway/src/main/java/com/gme/pay/sftp/batch/BatchJobService.java (generateZp0063, generateZp0065, generateZp0066 methods)
**Acceptance / logic checks:**
- ZP0063 includes only transactions with morning_batch_id IS NULL (no ZP0061 overlap).
- ZP0065 detail record count = ZP0061 + ZP0063 transaction count for businessDate.
- settlement_batch_ref in ZP0065 correctly references ZP0061 or ZP0063 file name per transaction.
- ZP0063 throws BatchPrerequisiteException when ZP0062 not received.
- Re-run of ZP0065 produces byte-identical output (regenerable from committed records).
**Depends on:** 9.2-T19

### 9.2-T21 — Implement batch scheduler cron jobs for all ZP00xx outbound and inbound poll windows  _(45 min)_
**Context:** SCH-06 §7.3 and §8.1: Spring @Scheduled cron jobs in zone Asia/Seoul (KST = UTC+9). Outbound triggers: ZP0011+ZP0021 at 01:30 KST, ZP0061 at 04:30 KST, ZP0063 at 13:30 KST, ZP0065+ZP0066 at 21:30 KST. Inbound pollers: ZP0012+ZP0022 start 04:50 KST every 5 min until 06:00 KST; ZP0062 start 09:00 KST every 10 min until 12:00 KST; ZP0064 start 18:00 KST every 10 min until 21:00 KST; merchant/QR sync ZP0041-ZP0055 hourly 01:00-08:00 KST. BatchWindowMonitor checks SLA deadlines and emits BatchDeadlineMissedEvent if job not completed in time. ZP0061 guarded: only runs if ZP0011 SUCCESS AND ZP0012 received AND not blocked.
**Steps:** Add BatchScheduler.java (@Component) with @Scheduled(cron=..., zone=Asia/Seoul) methods for each outbound file; each delegates to BatchJobService and logs start/end/error.; Add inbound pollers as @Scheduled(fixedDelay) methods calling InboundTransferService.fetchInbound; each polls within its KST window, stops when file received or window expires.; ZP0061 scheduler: check sftp_transfer_log for ZP0011 SUCCESS + ZP0012 received + Zp0012ReconciliationService.blockedForSettlement=false before calling generateZp0061; if guard fails publish BatchDeadlineMissedEvent.; Add BatchWindowMonitor.java (@Component) with deadline check scheduled 5 min after each window close; emits BatchDeadlineMissedEvent if sftp_transfer_log status!=SUCCESS.; Write BatchSchedulerTest (unit, mock BatchJobService): verify ZP0011 cron = 01:30 Asia/Seoul; verify ZP0061 blocked when ZP0012 absent; verify BatchDeadlineMissedEvent published when ZP0011 not SUCCESS by 02:05 KST.
**Deliverable:** services/sftp-gateway/src/main/java/com/gme/pay/sftp/batch/BatchScheduler.java and BatchWindowMonitor.java
**Acceptance / logic checks:**
- ZP0011 cron expression evaluates to 01:30 Asia/Seoul (CronExpression.parse test).
- ZP0061 NOT triggered when ZP0012 not received (mock sftp_transfer_log lookup returns null).
- BatchDeadlineMissedEvent in outbox when ZP0011 not SUCCESS by 02:05 KST (mock clock).
- Idempotent trigger: if sftp_transfer_log ZP0011 already SUCCESS for businessDate, scheduleZp0011Zp0021 logs WARN and skips.
- Inbound poller stops polling once InboundTransferService returns non-empty Optional.
**Depends on:** 9.2-T14, 9.2-T15, 9.2-T18, 9.2-T20, 9.2-T13

### 9.2-T22 — Implement file retention cleanup and compliance_hold guard  _(35 min)_
**Context:** SCH-06 §2.3.5: files retained 90 days (configurable file_retention_days). SEC-09: SFTP batch file logs retained 7 years (settlement evidence). sftp_file_archive.expires_at is set at archive write time as archived_at + file_retention_days days. Compliance hold (compliance_hold=true) prevents expiry marking regardless of age. A scheduled cleanup job daily at 03:00 KST marks expired rows status=EXPIRED in sftp_file_archive where expires_at <= now() AND compliance_hold=false. Object Storage lifecycle rule (set in T09) handles physical deletion. RetentionCleanupService does NOT delete from Object Storage directly.
**Steps:** Add RetentionCleanupService.java (@Service) with markExpiredArchives(): int - UPDATE sftp_file_archive SET status='EXPIRED' WHERE expires_at <= now() AND compliance_hold = FALSE; return rows affected.; Add status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' column to sftp_file_archive via db/migration/V204__sftp_file_archive_status.sql.; Schedule markExpiredArchives() daily at 03:00 KST via @Scheduled(cron=0 0 3 * * *, zone=Asia/Seoul).; In OutboundTransferService and InboundTransferService: at archive write time, set sftp_file_archive.expires_at = archived_at + SftpGatewayProperties.fileRetentionDays.; Write RetentionCleanupServiceTest (Testcontainers Postgres): 3 past-expires_at rows + 1 compliance_hold + 1 future; run markExpiredArchives(); assert 3 EXPIRED, compliance_hold untouched, future untouched.
**Deliverable:** db/migration/V204__sftp_file_archive_status.sql and services/sftp-gateway/src/main/java/com/gme/pay/sftp/storage/RetentionCleanupService.java
**Acceptance / logic checks:**
- compliance_hold=true rows never marked EXPIRED.
- expires_at = archived_at + file_retention_days set correctly at archive time.
- @Scheduled cron Asia/Seoul 03:00 KST (cron expression test).
- Testcontainers test: 3 expired rows -> 3 EXPIRED; compliance_hold row status unchanged.
- V204 migration applies cleanly on top of V203.
**Depends on:** 9.2-T09, 9.2-T10, 9.2-T11

### 9.2-T23 — Implement SSH key rotation service: initiate, promote, retire procedure  _(45 min)_
**Context:** SEC-09 §9.3: SFTP key rotation requires 5 business-day lead time with KFTC. Steps: (1) generate new Ed25519 pair stored at Vault zeropay/sftp/private_key_pending, (2) Ops extracts public key and registers with KFTC (manual), (3) on KFTC confirmation Ops promotes: _pending moves to active zeropay/sftp/private_key; old active archived to zeropay/sftp/private_key_previous for overlap window, (4) Ops retires: _previous deleted. Old key NOT revoked until KFTC confirms. Each step audit-logged: event_type=SSH_KEY_ROTATION_INITIATED/PROMOTED/RETIRED, actor, key_fingerprint, timestamp in audit_log table.
**Steps:** Add SshKeyRotationService.java (@Service) with initiateRotation(): String publicKeyPem (generates new Ed25519 to _pending, returns public key); promoteRotation(String actorId): void (moves _pending to active, archives active to _previous); retirePreviousKey(String actorId): void (deletes _previous).; Guard: promoteRotation throws SshKeyRotationException(NO_PENDING_KEY) if _pending path empty; retirePreviousKey throws SshKeyRotationException(PENDING_KEY_EXISTS) if _pending still exists.; Persist audit_log row for each step with event_type, actor, key_fingerprint (SHA256 of public key bytes), timestamp.; Expose POST /internal/sftp/ssh-keys/rotate (initiateRotation), POST /internal/sftp/ssh-keys/promote, POST /internal/sftp/ssh-keys/retire-previous - all require ADMIN role.; Write SshKeyRotationServiceTest: full 3-step sequence; verify audit_log rows; verify promoting without initiating throws; verify retiring before promoting throws.
**Deliverable:** services/sftp-gateway/src/main/java/com/gme/pay/sftp/security/SshKeyRotationService.java
**Acceptance / logic checks:**
- initiateRotation: active Vault path unchanged; _pending path written.
- promoteRotation: active path = new key; _previous path = old key; _pending deleted.
- retirePreviousKey: throws SshKeyRotationException(PENDING_KEY_EXISTS) if _pending not empty.
- 3 audit_log rows created with correct event_types and non-null key_fingerprints.
- promoteRotation without prior initiate throws SshKeyRotationException(NO_PENDING_KEY).
**Depends on:** 9.2-T04, 9.2-T08

### 9.2-T24 — Expose /internal/sftp/transfer-log and /internal/sftp/batch-status REST endpoints  _(35 min)_
**Context:** Ops needs visibility into SFTP transfer state. GET /internal/sftp/transfer-log?businessDate=YYYY-MM-DD&fileType=ZP0011 returns List<SftpTransferLogDto> with all transfer attempts for that file on that date. GET /internal/sftp/batch-status?businessDate=YYYY-MM-DD returns BatchStatusSummary: one row per ZP file type showing expected direction, scheduled KST time, current status (PENDING/SUCCESS/FAILED/NOT_APPLICABLE), and lateness flag. Both endpoints secured by ADMIN or OPS role (Spring Security @PreAuthorize). Used by Admin portal Settlement batch view (PRD-07 §11.2.2).
**Steps:** Add SftpTransferLogController.java (@RestController, @RequestMapping(/internal/sftp)) with getTransferLog(@RequestParam ZpFileType, @RequestParam LocalDate): List<SftpTransferLogDto>.; Add getBatchStatus(@RequestParam LocalDate): BatchStatusSummaryDto - queries sftp_transfer_log for the date and builds a summary row per all 17 ZpFileType entries (PENDING for those not yet created).; Add SftpTransferLogRepository (Spring Data JPA) with findByFileTypeAndBusinessDate.; Secure both methods with @PreAuthorize(hasAnyRole('ADMIN','OPS')).; Write SftpTransferLogControllerTest (@WebMvcTest): ADMIN role returns 200 with correct JSON; no auth returns 401; malformed businessDate returns 400; PARTNER role returns 403.
**Deliverable:** services/sftp-gateway/src/main/java/com/gme/pay/sftp/api/SftpTransferLogController.java
**Acceptance / logic checks:**
- GET with ADMIN JWT returns 200 and correct List<SftpTransferLogDto>.
- GET without JWT returns 401.
- Malformed businessDate (e.g. 20261015) returns 400 with error body.
- getBatchStatus returns exactly 17 entries (one per ZpFileType) for the date.
- PARTNER role JWT returns 403.
**Depends on:** 9.2-T03, 9.2-T07

### 9.2-T25 — Implement SFTP connectivity test endpoint for Ops scheme configuration  _(30 min)_
**Context:** PRD-07 §4.3.2 Connectivity Configuration: when Ops saves ZeroPay SFTP config, a Connectivity Test button verifies the connection. POST /internal/sftp/connectivity-test (ADMIN role) loads current SftpGatewayProperties, attempts SFTP connect with current Vault-stored credentials and known_hosts pin, attempts to list /gmepay/outbound/, returns ConnectivityTestResult {success: boolean, errorCode: String, errorMessage: String, latencyMs: long}. Timeout: 10 seconds. Any exception mapped to errorCode field (HOST_KEY_MISMATCH, CONNECTION_TIMEOUT, AUTH_FAILED, DIRECTORY_NOT_FOUND).
**Steps:** Add ConnectivityTestService.java (@Service) with testConnectivity(): ConnectivityTestResult; opens SftpSession via SftpClientFactory, lists outbound directory, closes session; measures latencyMs; catches all exceptions and maps to ConnectivityTestResult.errorCode.; Apply 10-second timeout via CompletableFuture.get(10, TimeUnit.SECONDS) wrapping the SFTP operation.; Add ConnectivityTestController.java (@RestController) with POST /internal/sftp/connectivity-test; @PreAuthorize(hasRole('ADMIN')).; Write ConnectivityTestControllerTest (@WebMvcTest + WireMock SFTP): success returns {success:true, latencyMs>0}; wrong host key returns {success:false, errorCode:HOST_KEY_MISMATCH}; timeout returns {success:false, errorCode:CONNECTION_TIMEOUT}.; Verify ConnectivityTestService never throws unchecked exception to caller.
**Deliverable:** services/sftp-gateway/src/main/java/com/gme/pay/sftp/api/ConnectivityTestController.java
**Acceptance / logic checks:**
- Success: {success:true, latencyMs > 0} returned within 10 s.
- HOST_KEY_MISMATCH error surfaced in ConnectivityTestResult.errorCode.
- Timeout after 10 s: {success:false, errorCode:CONNECTION_TIMEOUT}.
- OPS role (not ADMIN) returns 403 for POST /internal/sftp/connectivity-test.
- No exception propagates out of ConnectivityTestService.testConnectivity(); all errors mapped to result.
**Depends on:** 9.2-T08, 9.2-T23

### 9.2-T26 — Add OpenTelemetry and Prometheus metrics instrumentation for SFTP batch operations  _(35 min)_
**Context:** STACK.md observability: OpenTelemetry traces/metrics/logs -> Prometheus + Grafana + Jaeger. Key SFTP metrics per SAD-02: SFTP batch delivery success rate, batch job duration, transfer byte count. Prometheus counters: sftp_transfers_total{fileType, direction, status} (incremented per transfer); sftp_transfer_bytes_total{fileType, direction} (total bytes). Gauge: sftp_batch_pending_count (count of PENDING rows in sftp_transfer_log for today). OTel span on each batch job: span name = batch.{fileType}.{direction}, attributes: fileType, businessDate, direction, bytes_transferred. Alert threshold per NFR: ZeroPay SFTP delivery failure triggers BatchDeadlineMissedEvent (already T21); Prometheus rule: sftp_transfers_total{status=FAILED} > 0 triggers alert.
**Steps:** Add SftpMetrics.java (@Component) using Micrometer MeterRegistry: register Counter sftp_transfers_total with tags fileType, direction, status; register Counter sftp_transfer_bytes_total; register Gauge sftp_batch_pending_count.; Instrument OutboundTransferService.transferOutbound and InboundTransferService.fetchInbound with @WithSpan (opentelemetry-instrumentation-annotations) setting span attributes.; Increment counters in OutboundTransferService (SUCCESS/FAILED) and InboundTransferService; increment byte counter with bytes_transferred.; Register Prometheus scrape endpoint via spring-boot-actuator (management.endpoints.web.exposure.include=health,prometheus).; Write SftpMetricsTest: run OutboundTransferService mock success; assert sftp_transfers_total{fileType=ZP0011,direction=OUTBOUND,status=SUCCESS}.count() = 1; assert byte counter > 0.
**Deliverable:** services/sftp-gateway/src/main/java/com/gme/pay/sftp/observability/SftpMetrics.java
**Acceptance / logic checks:**
- sftp_transfers_total incremented with correct fileType, direction, status tags on each transfer.
- sftp_transfer_bytes_total incremented with correct bytes_transferred value.
- @WithSpan produces span with fileType and businessDate attributes (in-memory OTel SDK assertion).
- sftp_batch_pending_count gauge reflects current count from sftp_transfer_log for today.
- No PII or credential values in span attributes or metric tag values.
**Depends on:** 9.2-T10, 9.2-T11

### 9.2-T27 — Unit tests: PGP edge cases - wrong key, empty payload, large payload, armored mode  _(35 min)_
**Context:** Full edge-case coverage for PgpCryptoService beyond the basic round-trip in T06. Test cases: (1) decrypt with wrong private key -> PgpDecryptionException; (2) verify with wrong expected signer public key -> PgpVerificationException; (3) empty payload (byte[0]) round-trips to byte[0]; (4) 10 MB random payload round-trip completes under 5 seconds (no disk I/O); (5) corrupted PGP packet (random 100 bytes input to decryptAndVerify) -> PgpDecryptionException; (6) pgp_use_armor=true produces ASCII-armored output; (7) passphrase-protected GME private key (char[] passphrase supplied): decrypt succeeds with correct passphrase, PgpDecryptionException with wrong passphrase.
**Steps:** Write PgpCryptoServiceEdgeCaseTest class in services/sftp-gateway test source.; Add testWrongPrivateKey, testSignerMismatch, testEmptyPayload, testLargePayload, testCorruptedInput, testArmoredOutput, testPassphraseProtection test methods.; Each test is fully independent (generates its own key pair; no static shared state).; Assert no temp files created during 10 MB test (File.listFiles on temp dir before vs after).; Run with ./gradlew :services:sftp-gateway:test -Dtest=PgpCryptoServiceEdgeCaseTest.
**Deliverable:** services/sftp-gateway/src/test/java/com/gme/pay/sftp/security/PgpCryptoServiceEdgeCaseTest.java
**Acceptance / logic checks:**
- Wrong private key -> PgpDecryptionException.
- Wrong signer key -> PgpVerificationException.
- Empty payload round-trips to byte[0] without exception.
- 10 MB round-trip under 5 seconds with zero temp files created.
- Armored output starts with -----BEGIN PGP MESSAGE-----.
- Correct passphrase succeeds; wrong passphrase throws PgpDecryptionException.
**Depends on:** 9.2-T06

### 9.2-T28 — Unit tests: ZP0011/ZP0021 file generation vectors with concrete KRW amounts  _(35 min)_
**Context:** Concrete acceptance vectors for FixedWidthFileBuilder output. ZP0011 vector: 3 transactions with payout_amount_krw of 150000, 75000, 200000; business_date 2026-10-15; institution_code GME01. Expected: header record_count=3, total_payout_amount_krw=425000; 3 detail records with correct field values in correct byte positions; trailer control_sum=425000. File must be deterministic: same inputs always produce byte-identical output. ZP0021 vector: 2 refunds with refund_amount_krw 50000 and 30000; expected control_sum=80000 (sum of abs); detail values written as negative (-50000, -30000).
**Steps:** Write Zp0011FileGenerationTest (Testcontainers Postgres): seed 3 transactions with amounts 150000/75000/200000, business_date=2026-10-15.; Call BatchJobService.generateZp0011(2026-10-15); capture returned byte[].; Parse bytes via Zp0011Parser; assert header record_count=3, total_payout_amount_krw=425000; assert detail[0].amount=150000; assert trailer control_sum=425000.; Assert determinism: reset settlement_batch_id on all 3 rows (test utility), call generateZp0011 again; assert output byte-identical to first call.; Write Zp0021FileGenerationTest: 2 refunds 50000/30000; assert control_sum=80000; assert detail amounts are -50000 and -30000.
**Deliverable:** services/sftp-gateway/src/test/java/com/gme/pay/sftp/batch/Zp0011FileGenerationTest.java and Zp0021FileGenerationTest.java
**Acceptance / logic checks:**
- ZP0011 header.total_payout_amount_krw = 425000.
- ZP0011 trailer.control_sum = 425000.
- ZP0011 3 detail records with amounts 150000, 75000, 200000 at correct field positions.
- Byte-identical output on repeat generation from same DB state.
- ZP0021 control_sum = 80000; detail amounts written as -50000 and -30000.
**Depends on:** 9.2-T14, 9.2-T15

### 9.2-T29 — Unit tests: ZP0012 reconciliation vectors covering all 4 outcome codes  _(35 min)_
**Context:** Concrete test vectors for Zp0012ReconciliationService covering all 4 outcomes per SCH-06 §5.4: (A) result_code=00 amounts match -> SETTLEMENT_REGISTERED; (B) result_code=01 non-zero -> REGISTRATION_FAILED + RegistrationExceptionEvent; (C) record in ZP0011 absent from ZP0012 -> REGISTRATION_UNKNOWN + RegistrationExceptionEvent; (D) extra record in ZP0012 absent from GME DB -> AnomalousZp0012RecordEvent. Test vector: ZP0011 has 4 transactions (T1/T2/T3/T4); ZP0012 response has T1(code=00 match), T2(code=01 fail), T4_extra(no GME txn); T3 absent from ZP0012. Expected: 1 SETTLEMENT_REGISTERED (T1), 1 REGISTRATION_FAILED (T2), 1 REGISTRATION_UNKNOWN (T3), 1 AnomalousZp0012RecordEvent (T4_extra); blockedForSettlement=true.
**Steps:** Seed DB with 4 APPROVED transactions in settlement_batch_id=batch1 for businessDate 2026-10-15 (Testcontainers Postgres).; Build synthetic ZP0012 bytes using Zp0012Builder (test utility): T1(code=00), T2(code=01), T4_extra(zeropay_txn_ref=UNKNOWN999, code=00).; Call Zp0012ReconciliationService.reconcile(2026-10-15, zp0012Bytes).; Assert T1.status=SETTLEMENT_REGISTERED; T2.status=REGISTRATION_FAILED; T3.status=REGISTRATION_UNKNOWN; T4_extra generates AnomalousZp0012RecordEvent in outbox_events.; Assert ReconciliationResult.blockedForSettlement=true (REGISTRATION_FAILED count>0).
**Deliverable:** services/sftp-gateway/src/test/java/com/gme/pay/sftp/batch/reconcile/Zp0012ReconciliationTest.java
**Acceptance / logic checks:**
- T1 -> SETTLEMENT_REGISTERED.
- T2 -> REGISTRATION_FAILED + RegistrationExceptionEvent in outbox_events.
- T3 -> REGISTRATION_UNKNOWN + RegistrationExceptionEvent in outbox_events.
- T4_extra -> AnomalousZp0012RecordEvent in outbox_events; T4 GME row status unchanged.
- blockedForSettlement=true when REGISTRATION_FAILED count > 0.
**Depends on:** 9.2-T16

### 9.2-T30 — Unit tests: SettlementDiscrepancy zero-tolerance and SettlementCompleted event vector  _(35 min)_
**Context:** Concrete test vectors for SettlementResultReconciliationService. Two merchants: Merchant A GME total 500000 KRW / ZP0062 total 500000 KRW (exact match -> SETTLEMENT_CONFIRMED); Merchant B GME total 200000 KRW / ZP0062 total 199999 KRW (1 KRW mismatch -> SETTLEMENT_DISCREPANCY). Expected: SettlementCompletedEvent published with batch_file_ref=ZP0062-2026-10-15 and total_payout_krw=500000 (confirmed merchant only); SettlementDiscrepancyEvent for Merchant B with difference_krw=1. Verify idempotency: processing same ZP0062 bytes twice does not create duplicate events.
**Steps:** Seed DB with morning batch transactions for Merchant A (total 500000 KRW) and Merchant B (total 200000 KRW) in status=SETTLEMENT_REGISTERED (Testcontainers Postgres).; Build synthetic ZP0062 bytes: Merchant A total=500000, Merchant B total=199999.; Call SettlementResultReconciliationService.reconcile(ZP0062, 2026-10-15, bytes).; Assert Merchant A -> SETTLEMENT_CONFIRMED; Merchant B -> SETTLEMENT_DISCREPANCY.; Assert exactly 1 SettlementDiscrepancyEvent in outbox_events with payload.difference_krw=1; assert SettlementCompletedEvent present with correct batch_file_ref; call reconcile again and assert no additional events (idempotency).
**Deliverable:** services/sftp-gateway/src/test/java/com/gme/pay/sftp/batch/reconcile/SettlementDiscrepancyTest.java
**Acceptance / logic checks:**
- 1 KRW discrepancy -> SETTLEMENT_DISCREPANCY for Merchant B; SettlementDiscrepancyEvent.payload.difference_krw=1.
- Merchant A -> SETTLEMENT_CONFIRMED.
- SettlementCompletedEvent.payload.batch_file_ref=ZP0062-2026-10-15.
- SettlementCompletedEvent.payload.total_payout_krw=500000 (Merchant A only).
- Second reconcile call produces 0 new outbox events (idempotent).
**Depends on:** 9.2-T19

### 9.2-T31 — Integration test: full ZP0011->ZP0012->ZP0061 batch pipeline round-trip  _(55 min)_
**Context:** End-to-end integration test for the core payment batch pipeline using Testcontainers (Postgres 16, MinIO) and WireMock SFTP server. Test scenario: (1) seed 5 APPROVED transactions; (2) trigger generateZp0011 (WireMock SFTP accepts put); (3) inject synthetic ZP0012 all success (result_code=00); (4) call Zp0012ReconciliationService.reconcile; (5) trigger generateZp0061; (6) assert full DB state chain. Verifies the primary settlement pipeline works end-to-end in isolation from the scheduler.
**Steps:** Set up @SpringBootTest with @Container Testcontainers Postgres 16 and MinIO; configure WireMock SFTP stub to accept put and return success.; Seed 5 APPROVED transactions via TransactionRepository.saveAll; call batchJobService.generateZp0011(today).; Build ZP0012 bytes (all 5 result_code=00); call inboundTransferService.fetchInbound mock; call reconciliationService.reconcile.; Call batchJobService.generateZp0061(today).; Assert: all 5 transactions SETTLEMENT_REGISTERED; sftp_transfer_log has ZP0011 and ZP0061 rows both status=SUCCESS; Object Storage (MinIO) contains both files; settlement_batch row created; 0 events in outbox_events.
**Deliverable:** services/sftp-gateway/src/test/java/com/gme/pay/sftp/integration/SftpBatchPipelineIntegrationTest.java
**Acceptance / logic checks:**
- All 5 transactions have settlement_batch_id set after generateZp0011.
- All 5 transactions SETTLEMENT_REGISTERED after reconcile.
- sftp_transfer_log ZP0011 and ZP0061 both status=SUCCESS.
- Object Storage (MinIO) contains 2 objects at expected keys.
- outbox_events table has 0 rows (no failures).
**Depends on:** 9.2-T18, 9.2-T22, 9.2-T10, 9.2-T11

### 9.2-T32 — Integration test: retry exhaustion and SftpTransferFailed Outbox event  _(45 min)_
**Context:** Test that OutboundTransferService emits SftpTransferFailed after 3 failed SFTP attempts and that transactions still have settlement_batch_id set (file can be regenerated from committed records per NFR §6.5). WireMock SFTP configured to reject all put() calls. After failure, simulate SFTP recovery and re-run generateZp0011: verify same settlement_batch_id used (no double-inclusion since transactions already claimed).
**Steps:** Configure WireMock SFTP to return IOException on all put() calls.; Seed 3 APPROVED transactions; call batchJobService.generateZp0011(today); assert 3 SFTP attempts made (WireMock verify 3 invocations).; Assert sftp_transfer_log: status=FAILED, retry_count=3.; Assert SftpTransferFailed event in outbox_events with payload.fileType=ZP0011.; Configure WireMock to accept put; clear failed transfer log row; call generateZp0011 again; verify file generated with same 3 transactions (idempotent re-inclusion allowed since settlement_batch_id was not preserved on FAILED path - re-query finds IS NULL still); assert sftp_transfer_log new SUCCESS row.
**Deliverable:** services/sftp-gateway/src/test/java/com/gme/pay/sftp/integration/SftpTransferFailureTest.java
**Acceptance / logic checks:**
- Exactly 3 SFTP put() attempts (WireMock verify(exactly(3), putRequest)).
- sftp_transfer_log retry_count=3, status=FAILED.
- SftpTransferFailed Outbox event in outbox_events with payload.fileType=ZP0011.
- After SFTP recovery + re-run: new sftp_transfer_log row status=SUCCESS.
- Testcontainers Postgres and WireMock SFTP used (no mocks via Mockito for infrastructure).
**Depends on:** 9.2-T10, 9.2-T13, 9.2-T31

### 9.2-T33 — Security tests: PGP signature rejection and SFTP path traversal guard  _(40 min)_
**Context:** SEC-09 §3 threat model T-06: settlement file tampering. OWASP API10: defensive validation on all inbound batch files. Two security tests: (1) inbound file with tampered ciphertext (1 byte mutated) must trigger PgpVerificationException -> SftpInboundVerificationFailed Outbox event -> NO DB status updates (transaction status unchanged); (2) SFTP path traversal: for all 17 ZpFileType values, constructed remote paths must start with /gmepay/ and contain no ../ sequence; InboundTransferService must reject any injected path with ../ characters.
**Steps:** Write PgpSignatureRejectionTest: build valid ZP0012 file encrypted+signed with test keys; mutate byte at offset 100 in ciphertext; call InboundTransferService.fetchInbound with tampered bytes; assert Optional.empty() returned; assert SftpInboundVerificationFailed in outbox_events; assert zero transaction status changes.; Write SftpPathTraversalTest: for each ZpFileType, call the remote path construction logic and assert result starts with /gmepay/inbound/ or /gmepay/outbound/ and contains no ../.; Write SftpPathInjectionTest: directly call InboundTransferService with a crafted remotePath containing ../ and assert SftpPathTraversalException thrown.; Assert SftpInboundVerificationFailed event payload.verificationError is non-empty.; Run tests with ./gradlew :services:sftp-gateway:test.
**Deliverable:** services/sftp-gateway/src/test/java/com/gme/pay/sftp/security/PgpSignatureRejectionTest.java and SftpPathTraversalTest.java
**Acceptance / logic checks:**
- Tampered ciphertext -> SftpInboundVerificationFailed event in outbox; no DB transaction status changes.
- All 17 ZpFileType remote paths start with /gmepay/ (path traversal impossible via file type enum).
- ../ in injected remotePath throws SftpPathTraversalException.
- SftpInboundVerificationFailed payload.verificationError is non-empty string.
- No SFTP credentials or key material in exception messages.
**Depends on:** 9.2-T11, 9.2-T06, 9.2-T12

### 9.2-T34 — Document SFTP Gateway ops runbook: key rotation, batch SLAs, escalation, retention  _(25 min)_
**Context:** SEC-09 §9.3, SCH-06 §9.4, and NFR §2.3: Ops requires a runbook covering: (1) SSH key rotation (Ed25519 generation, 5 business-day KFTC lead time, promote/retire steps via Admin portal /internal/sftp/ssh-keys/* endpoints); (2) PGP key refresh (upload new hangulwon public key and GME key pair via /internal/sftp/pgp-keys/* endpoints; all operations in-memory only); (3) batch window SLA table (all 10 outbound file types with KST times and P1/P2 incident priorities per SCH-06 §9.4); (4) SFTP failure escalation (retry logic: 3x at 5-min intervals, then P1 alert + manual re-transmit steps); (5) file retention policy (90-day default configurable via file_retention_days, 7-year settlement archive, compliance_hold flag prevents deletion). This is an explicitly requested ops documentation artifact.
**Steps:** Create docs/ops/sftp-gateway-runbook.md with sections: 1. SSH Key Rotation, 2. PGP Key Refresh, 3. Batch Window SLA Table, 4. SFTP Failure Escalation, 5. File Retention Policy.; Section 1: 3-step procedure (initiate, promote, retire); note 5-business-day KFTC lead time; warn do not revoke old key until KFTC confirms.; Section 3: table of all outbound file types with KST scheduled time, P1/P2 priority, and internal deadline (ZP0011 internal complete by 01:30 KST, due 02:00 KST).; Section 4: retry policy (3x, 5-min interval); manual re-transmit using POST /internal/sftp/transfer-log/{id}/retransmit (stub described, to be wired later).; Section 5: compliance_hold=true use case; note 7-year minimum for settlement evidence files.
**Deliverable:** docs/ops/sftp-gateway-runbook.md
**Acceptance / logic checks:**
- File exists at docs/ops/sftp-gateway-runbook.md.
- Section 1 includes the 5-business-day KFTC lead time warning explicitly.
- Section 3 table covers all 10 outbound ZP file types with correct KST times and priorities.
- Section 2 states all PGP operations in-memory only (no cleartext temp files on disk).
- Section 5 states 7-year minimum retention for settlement evidence files.
**Depends on:** 9.2-T23, 9.2-T24, 9.2-T25


## WBS 9.4 — Payment result exchange (ZP0011/0012)
### 9.4-T01 — Flyway migration: payment_batch_file and batch_exception tables  _(30 min)_
**Context:** WBS 9.4 Payment result exchange (ZP0011/ZP0012). The ZeroPay Scheme Adapter (Gradle module: services/scheme-adapter-zeropay) records every outbound/inbound batch file in payment_batch_file and raises exception records in batch_exception. payment_batch_file: id BIGSERIAL PK, scheme_id BIGINT FK qr_scheme, file_type VARCHAR(10) e.g. ZP0011/ZP0012, direction VARCHAR(20) CHECK IN ('GME_TO_ZP','ZP_TO_GME'), settlement_date DATE NOT NULL (KST business date), sequence_no SMALLINT NOT NULL DEFAULT 1, status VARCHAR(30) NOT NULL (PENDING/TRANSMITTED/RECEIVED/FAILED), record_count INT, control_sum NUMERIC(16,0), object_storage_key TEXT, created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ, UNIQUE(scheme_id,file_type,settlement_date,sequence_no). batch_exception: id BIGSERIAL PK, batch_file_id BIGINT FK payment_batch_file, payment_id BIGINT FK payments NULLABLE, exception_type VARCHAR(40) NOT NULL CHECK IN ('REGISTRATION_FAILED','REGISTRATION_UNKNOWN','REGISTRATION_AMOUNT_MISMATCH','ANOMALY','SFTP_FAILURE','PARSE_ERROR'), zeropay_txn_ref CHAR(20), details JSONB, status VARCHAR(20) DEFAULT 'OPEN', resolved_by BIGINT, resolved_at TIMESTAMPTZ, created_at TIMESTAMPTZ. PostgreSQL 16 on port 5433 local dev. Use Flyway naming Vnnn__*.sql under services/scheme-adapter-zeropay/src/main/resources/db/migration/.
**Steps:** Find the current highest Flyway version under services/scheme-adapter-zeropay/src/main/resources/db/migration/ and use the next integer.; Write migration SQL creating payment_batch_file with all listed columns, UNIQUE constraint, and index on (scheme_id, file_type, settlement_date).; Add batch_exception table with all listed columns, FK to payment_batch_file, index on (batch_file_id), partial index on (payment_id) WHERE payment_id IS NOT NULL, partial index on (status) WHERE status='OPEN'.; Apply migration against local PostgreSQL on port 5433 and verify both tables with correct column types using psql.
**Deliverable:** services/scheme-adapter-zeropay/src/main/resources/db/migration/V{N}__payment_batch_tables.sql
**Acceptance / logic checks:**
- Both tables exist after migration with no errors.
- payment_batch_file.direction CHECK rejects any value outside ('GME_TO_ZP','ZP_TO_GME').
- batch_exception.exception_type CHECK rejects 'BOGUS'; accepts all 6 defined values.
- NUMERIC(16,0) used for control_sum (not BIGINT, not FLOAT).
- UNIQUE(scheme_id, file_type, settlement_date, sequence_no) exists on payment_batch_file.

### 9.4-T02 — Flyway migration: add batch_registration_status columns to payments table  _(25 min)_
**Context:** WBS 9.4. The payments table (PostgreSQL 16, Gradle module services/payment-executor) needs columns tracking ZP0011/ZP0012 lifecycle per SCH-06 §5.4. Add: batch_registration_status VARCHAR(30) NOT NULL DEFAULT 'PENDING' CHECK IN ('PENDING','REGISTERED','SETTLEMENT_REGISTERED','REGISTRATION_FAILED','REGISTRATION_UNKNOWN','REGISTRATION_AMOUNT_MISMATCH'); zeropay_batch_file_id BIGINT NULLABLE FK payment_batch_file (the ZP0011 file row that included this payment); registered_amount_krw NUMERIC(14,0) NULLABLE (confirmed by ZP0012); settlement_date DATE NULLABLE (from ZP0012.settlement_date); zp0012_result_code CHAR(2) NULLABLE; zp0012_result_message VARCHAR(100) NULLABLE. Add partial index on (batch_registration_status) WHERE batch_registration_status NOT IN ('SETTLEMENT_REGISTERED'). Flyway migrations live in services/payment-executor/src/main/resources/db/migration/. NUMERIC for all monetary columns.
**Steps:** Determine next Flyway version under services/payment-executor/src/main/resources/db/migration/.; Write ALTER TABLE payments ADD COLUMN statements for all six fields with correct types, defaults, and CHECK constraint.; Add partial index on batch_registration_status for non-settled rows.; Apply against local PostgreSQL port 5433; verify columns with \d payments in psql.
**Deliverable:** services/payment-executor/src/main/resources/db/migration/V{N}__payments_batch_registration_status.sql
**Acceptance / logic checks:**
- All six columns present after migration with correct types.
- batch_registration_status CHECK rejects 'BOGUS'; accepts all 6 defined values.
- NUMERIC(14,0) used for registered_amount_krw (not BIGINT or FLOAT).
- Existing payment rows default to batch_registration_status='PENDING' after migration.
- Partial index on batch_registration_status is present in pg_indexes.
**Depends on:** 9.4-T01

### 9.4-T03 — ZP0011 record domain model and fixed-width record builder  _(40 min)_
**Context:** WBS 9.4. The ZeroPay Adapter (Gradle module services/scheme-adapter-zeropay, package com.gme.pay.zeropay.batch.zp0011) assembles the ZP0011 Payment Result file. Per SCH-06 §5.2 illustrative layout (fixed-width, confirm exact widths from 한결원 final spec): detail record: record_type CHAR(1)='D', gme_txn_id CHAR(20), zeropay_txn_ref CHAR(20), merchant_id CHAR(10), qr_code_id CHAR(20), txn_date DATE(8) YYYYMMDD KST, txn_time TIME(6) HHMMSS KST, payout_amount_krw NUM(12) KRW 0 decimals zero-padded, merchant_fee_amt NUM(12), van_fee_amt NUM(10), partner_type CHAR(1) D or I, approval_code CHAR(12), status_code CHAR(1)='A'. Header: record_type='H', file_type='ZP0011', business_date DATE(8), institution_code CHAR(10), total_record_count NUM(6), total_payout_krw NUM(16). Trailer: record_type='T', total_record_count NUM(6), control_sum NUM(16) = sum of payout_amount_krw. Use lib-money BigDecimal utilities (module lib-money); KRW scale=0. String fields space-padded, numeric fields zero-padded.
**Steps:** Create immutable Java record Zp0011DetailRecord in com.gme.pay.zeropay.batch.zp0011 with all detail fields (BigDecimal for amounts, LocalDate/LocalTime, String for IDs).; Create Zp0011HeaderRecord and Zp0011TrailerRecord similarly.; Implement Zp0011RecordBuilder @Component with methods buildDetailLine(Zp0011DetailRecord), buildHeaderLine(Zp0011HeaderRecord), buildTrailerLine(Zp0011TrailerRecord) -> String each; use String.format for fixed-width padding.; Implement buildFile(List<Zp0011DetailRecord>, LocalDate businessDate, String institutionCode) computing header totalRecordCount and trailer control_sum from the list.; Add validation: zeropay_txn_ref and gme_txn_id must be non-blank; payout_amount_krw must be > 0; throw IllegalArgumentException otherwise.
**Deliverable:** services/scheme-adapter-zeropay/src/main/java/com/gme/pay/zeropay/batch/zp0011/Zp0011RecordBuilder.java (plus Zp0011DetailRecord.java, Zp0011HeaderRecord.java, Zp0011TrailerRecord.java)
**Acceptance / logic checks:**
- buildDetailLine formats payout_amount_krw=150000 as '000000150000' (12 chars, zero-padded right-aligned).
- gme_txn_id shorter than 20 chars is space-padded on the right to exactly 20 chars.
- Trailer control_sum equals sum of payout_amount_krw across all detail records.
- Header total_record_count equals trailer total_record_count equals detail list size.
- Blank zeropay_txn_ref throws IllegalArgumentException before file assembly.
**Depends on:** 9.4-T01

### 9.4-T04 — Internal REST endpoint: fetch prior-day APPROVED payments pending batch registration  _(40 min)_
**Context:** WBS 9.4. The ZP0011 batch (Gradle module services/scheme-adapter-zeropay) must query payments eligible for inclusion: status=APPROVED, txn_date=prior business date KST, batch_registration_status='PENDING'. Per architecture (Anti-Corruption Layer / microservice boundary), the scheme adapter calls the Payment Executor service (services/payment-executor) via an internal REST endpoint, not a direct cross-DB join. Expose GET /internal/v1/payments/pending-batch-registration?businessDate=YYYYMMDD returning List<Zp0011PaymentProjection>. Projection fields needed for ZP0011 detail record: paymentId (Long), gmeTxnId (String CHAR(20)), zeropayTxnRef (String CHAR(20)), merchantId (String CHAR(10)), qrCodeId (String CHAR(20)), txnDate (LocalDate KST), txnTime (LocalTime KST), payoutAmountKrw (BigDecimal), merchantFeeAmt (BigDecimal), vanFeeAmt (BigDecimal), partnerType (String: D for LOCAL partner, I for OVERSEAS), approvalCode (String CHAR(12)). @RestController in com.gme.pay.executor.api.internal; secured so Spring Cloud Gateway blocks /internal/** from external traffic.
**Steps:** Add Zp0011PaymentProjection DTO in lib-api-contracts module under com.gme.pay.contracts.internal.batch with all listed fields.; Add JPA @Query in PaymentRepository (services/payment-executor) selecting payments WHERE status=APPROVED AND txn_date=:businessDate AND batch_registration_status='PENDING' mapped to Zp0011PaymentProjection.; Implement GET /internal/v1/payments/pending-batch-registration in PaymentExecutorInternalController @RestController; return ResponseEntity<List<Zp0011PaymentProjection>>.; Add Spring Security config rule blocking /internal/** from Gateway-origin requests (e.g. via header check or network policy comment).; In services/scheme-adapter-zeropay implement Zp0011BatchQueryClient @Service using Spring WebClient to call the endpoint; set timeout 30s.
**Deliverable:** services/payment-executor/src/main/java/com/gme/pay/executor/api/internal/PaymentExecutorInternalController.java and services/scheme-adapter-zeropay/src/main/java/com/gme/pay/zeropay/batch/zp0011/Zp0011BatchQueryClient.java
**Acceptance / logic checks:**
- Query returns only payments where status=APPROVED AND txn_date=businessDate AND batch_registration_status='PENDING'.
- Payments with batch_registration_status='REGISTERED' or 'SETTLEMENT_REGISTERED' are excluded.
- partnerType='D' for LOCAL partner type and 'I' for OVERSEAS.
- Endpoint returns HTTP 200 with empty list (not 404) when no qualifying payments exist.
- Spring Security config blocks /internal/** from requests arriving via the Gateway path.
**Depends on:** 9.4-T02

### 9.4-T05 — ZP0011 file assembler and Object Storage upload  _(45 min)_
**Context:** WBS 9.4. After fetching prior-day APPROVED payments via Zp0011BatchQueryClient (T04), the ZeroPay Adapter (services/scheme-adapter-zeropay) assembles a ZP0011 file, encrypts it PGP (public key from Vault/KMS), and uploads to S3-compatible Object Storage (MinIO in docker-compose local dev). Bucket: zeropay-batch-files. Key prefix: outbound/ZP0011/YYYY/MM/. File naming: ZP0011_{YYYYMMDD}_{NN}.dat.pgp where NN is 2-digit sequence (01 for first run, 02 for retransmit). After upload, insert payment_batch_file row (status=PENDING, file_type=ZP0011, direction=GME_TO_ZP, record_count, control_sum, object_storage_key) and update each payment's zeropay_batch_file_id and batch_registration_status='REGISTERED'. Insert and payment updates must be in one @Transactional block. S3Client configured via application.yml (zeropay.object-storage.*) pointing to MinIO for local.
**Steps:** Implement Zp0011FileAssembler @Service in com.gme.pay.zeropay.batch.zp0011: calls Zp0011BatchQueryClient.fetch(date), maps projections to Zp0011DetailRecord list, calls Zp0011RecordBuilder.buildFile(), returns assembled byte[].; Implement PgpEncryptionService @Service (or reuse if exists) fetching ZeroPay public PGP key from Vault at runtime and encrypting byte[] -> byte[].; Implement ObjectStorageClient @Service using AWS SDK v2 S3Client (configured for MinIO); method upload(String bucket, String key, byte[] data) -> String returns final key.; In Zp0011BatchJob @Service: fetch -> assemble -> encrypt -> upload -> compute sequence_no as MAX(sequence_no)+1 for (file_type=ZP0011, settlement_date) or 1 if none -> persist payment_batch_file (status=PENDING) and bulk-update payments.batch_registration_status='REGISTERED' and zeropay_batch_file_id=batchFileId in one @Transactional.; If no payments returned by query, log INFO and do not create file or payment_batch_file row.
**Deliverable:** services/scheme-adapter-zeropay/src/main/java/com/gme/pay/zeropay/batch/zp0011/Zp0011FileAssembler.java and Zp0011BatchJob.java
**Acceptance / logic checks:**
- payment_batch_file row inserted with status=PENDING before SFTP transmission.
- sequence_no=1 for first run; sequence_no=2 for second run on same settlement_date.
- Assembled byte[] contains header record_type='H', N detail records record_type='D', trailer record_type='T' with correct counts.
- Object storage key matches outbound/ZP0011/YYYY/MM/ZP0011_{date}_{seq}.dat.pgp pattern.
- No file created and no DB row inserted when zero payments match the query.
**Depends on:** 9.4-T03, 9.4-T04

### 9.4-T06 — SFTP transmission of ZP0011 via SftpGatewayClient to /gmepay/outbound/  _(40 min)_
**Context:** WBS 9.4. After file assembly and Object Storage upload (T05), the ZP0011 file must be placed on ZeroPay's SFTP server. Remote directory: /gmepay/outbound/ (per SCH-06 §2.3.4). SSH key authentication with RSA-4096 private key stored in Vault (path from zeropay.sftp.private-key-vault-path config). The SftpGatewayClient @Service in services/scheme-adapter-zeropay uses Apache MINA SSHD or JSch (pick one; prefer Apache MINA SSHD 2.x). Retry: Spring Retry @Retryable maxAttempts=3, exponential backoff starting 5s x2 multiplier. On permanent failure: update payment_batch_file.status=FAILED, call BatchExceptionService.raise(batchFileId, null, SFTP_FAILURE, null, details). On success: update payment_batch_file.status=TRANSMITTED.
**Steps:** Implement SftpGatewayClient @Service in com.gme.pay.zeropay.sftp with method transmit(String objectStorageKey, String remoteFileName): downloads bytes from Object Storage then streams to SFTP /gmepay/outbound/{remoteFileName}.; Add @Retryable(maxAttempts=3, backoff=@Backoff(delay=5000,multiplier=2)) on the SFTP put operation; add @Recover method to handle SftpException after exhaustion.; In @Recover handler: call BatchExceptionService.raise with SFTP_FAILURE; update payment_batch_file.status=FAILED.; On success: update payment_batch_file.status=TRANSMITTED and updated_at.; Private key retrieved from Vault via VaultTemplate at runtime; never on disk or in application.yml.
**Deliverable:** services/scheme-adapter-zeropay/src/main/java/com/gme/pay/zeropay/sftp/SftpGatewayClient.java
**Acceptance / logic checks:**
- On successful SFTP put, payment_batch_file.status=TRANSMITTED.
- On 3 consecutive SFTP failures, status=FAILED and batch_exception row with exception_type=SFTP_FAILURE is inserted.
- Private key is fetched from Vault at runtime; not hardcoded or stored on filesystem.
- Remote file placed at path /gmepay/outbound/ZP0011_{date}_{seq}.dat.pgp.
- Retransmit (sequence_no=02) places ZP0011_{date}_02.dat.pgp without altering the original sequence_no=01 row.
**Depends on:** 9.4-T05

### 9.4-T07 — Spring Scheduler: ZP0011 nightly batch cron at 02:00 KST with idempotency guard  _(35 min)_
**Context:** WBS 9.4. The ZP0011 batch fires daily at ~02:00 KST per SCH-06 §7.3. Module: services/scheme-adapter-zeropay. Use Spring @Scheduled with cron='${zeropay.batch.zp0011.cron}' (default '0 0 2 * * *') zone='Asia/Seoul'. The prior business date = LocalDate.now(ZoneId.of('Asia/Seoul')).minusDays(1). Idempotency: if payment_batch_file already has a non-FAILED row for (file_type=ZP0011, settlement_date=priorBusinessDate), skip. Wrap in try-catch; on uncaught exception call NotificationService.alertOps('BATCH_ZP0011_FAILED', detail) and update status=FAILED. Per SAD-02 §5.4: all settlement scheduler jobs must be idempotent.
**Steps:** Add @Configuration class ZeropayBatchSchedulerConfig with @EnableScheduling.; Implement Zp0011ScheduledJob @Component in com.gme.pay.zeropay.batch with @Scheduled(cron='${zeropay.batch.zp0011.cron}', zone='Asia/Seoul').; Compute priorBusinessDate in Asia/Seoul; query payment_batch_file for existing non-FAILED row; if found log INFO and return immediately.; Delegate to Zp0011BatchJob.run(priorBusinessDate) which orchestrates query-assemble-encrypt-upload (T05) then SftpGatewayClient.transmit (T06).; Wrap full flow in try-catch; on exception call NotificationService.alertOps and re-throw after marking status=FAILED.
**Deliverable:** services/scheme-adapter-zeropay/src/main/java/com/gme/pay/zeropay/batch/Zp0011ScheduledJob.java
**Acceptance / logic checks:**
- @Scheduled cron fires at 02:00 KST with zone=Asia/Seoul (not UTC).
- Second invocation on same settlement_date with existing TRANSMITTED row skips without duplicate file creation.
- priorBusinessDate is computed in Asia/Seoul timezone so UTC midnight does not shift the date.
- On uncaught exception NotificationService.alertOps is called with 'BATCH_ZP0011_FAILED'.
- cron expression is externalized to application.yml key zeropay.batch.zp0011.cron.
**Depends on:** 9.4-T05, 9.4-T06

### 9.4-T08 — SFTP inbound poller: detect and download ZP0012 from /gmepay/inbound/  _(40 min)_
**Context:** WBS 9.4. ZeroPay places ZP0012 (Payment Registration Result) at /gmepay/inbound/ by ~05:00 KST. GMEPay+ polls every 5 min from 05:00-06:00 KST (cron '0 */5 5 * * *' zone Asia/Seoul). File naming pattern: ZP0012_{YYYYMMDD}_{NN}.dat.pgp. After download: decrypt with GME private PGP key from Vault; upload decrypted bytes to Object Storage (bucket zeropay-batch-files, key inbound/ZP0012/YYYY/MM/ZP0012_{date}_{seq}.dat); insert payment_batch_file row (file_type=ZP0012, direction=ZP_TO_GME, status=RECEIVED). Idempotent: if row for (file_type=ZP0012, settlement_date, sequence_no) already exists, skip. Late-file alert: separate @Scheduled at 06:05 KST checks if no RECEIVED row exists for the date and calls NotificationService.alertOps('BATCH_FILE_LATE_ZP0012'). Module services/scheme-adapter-zeropay.
**Steps:** Implement Zp0012InboundPoller @Component in com.gme.pay.zeropay.batch.zp0012 with @Scheduled(cron='${zeropay.batch.zp0012.poll-cron}', zone='Asia/Seoul') polling /gmepay/inbound/ via SftpGatewayClient.listFiles(dir) for ZP0012_{date}_*.; Check idempotency: query payment_batch_file for existing RECEIVED row for that (file_type, settlement_date, sequence_no); skip if present.; Download bytes via SftpGatewayClient.download(remoteFile); decrypt via PgpEncryptionService.decrypt(bytes); upload to Object Storage; insert payment_batch_file(status=RECEIVED).; Add separate @Scheduled(cron='${zeropay.batch.zp0012.late-alert-cron}', zone='Asia/Seoul') at 06:05 KST: if no RECEIVED row for settlement_date=today KST, call NotificationService.alertOps('BATCH_FILE_LATE_ZP0012').; GME private PGP key retrieved from Vault at runtime.
**Deliverable:** services/scheme-adapter-zeropay/src/main/java/com/gme/pay/zeropay/batch/zp0012/Zp0012InboundPoller.java
**Acceptance / logic checks:**
- Polling runs every 5 min starting 05:00 KST; second poll finding same filename skips re-download (idempotent - no duplicate payment_batch_file row).
- Decrypted file bytes stored to Object Storage before payment_batch_file row inserted.
- payment_batch_file row gets direction=ZP_TO_GME, file_type=ZP0012, status=RECEIVED.
- BATCH_FILE_LATE_ZP0012 alert fires if no RECEIVED row by 06:05 KST.
- Private PGP key retrieved from Vault; not stored in application.yml.
**Depends on:** 9.4-T01, 9.4-T06

### 9.4-T09 — ZP0012 file parser: parse fixed-width result records into Zp0012ResultRecord objects  _(40 min)_
**Context:** WBS 9.4. After download and decryption (T08), ZP0012 bytes are parsed record-by-record. SCH-06 §5.3 illustrative layout: header record_type='H' (file_type='ZP0012', business_date DATE(8), institution_code); detail record_type='D': zeropay_txn_ref CHAR(20) (match key), gme_txn_id CHAR(20) (echo from ZP0011), merchant_id CHAR(10), txn_date DATE(8) YYYYMMDD, result_code CHAR(2) ('00'=success, non-zero=failure), result_message VARCHAR(100), registered_amount NUM(12) KRW NUMERIC scale=0, settlement_date DATE(8) YYYYMMDD; trailer record_type='T': total_record_count NUM(6), control_sum NUM(16). Parser validates: (1) header file_type='ZP0012'; (2) trailer record_count == actual detail count; (3) trailer control_sum == sum of registered_amount (BigDecimal.compareTo). On parse error throw Zp0012ParseException; caller creates batch_exception type=PARSE_ERROR. Module services/scheme-adapter-zeropay.
**Steps:** Create immutable Zp0012ResultRecord in com.gme.pay.zeropay.batch.zp0012 with all detail fields; use BigDecimal for registered_amount (scale=0), LocalDate for dates.; Create Zp0012ParseResult (header + List<Zp0012ResultRecord> + trailer metadata) and Zp0012ParseException.; Implement Zp0012FileParser @Service: method parse(byte[] fileBytes, LocalDate expectedBusinessDate) -> Zp0012ParseResult; split lines, dispatch by record_type.; Validate header.fileType='ZP0012'; throw Zp0012ParseException if mismatch. Validate header.businessDate == expectedBusinessDate.; Validate trailer.totalRecordCount == detail list size; validate trailer.controlSum.compareTo(sum of registered_amount) == 0; throw Zp0012ParseException on mismatch.
**Deliverable:** services/scheme-adapter-zeropay/src/main/java/com/gme/pay/zeropay/batch/zp0012/Zp0012FileParser.java (plus Zp0012ResultRecord.java, Zp0012ParseResult.java, Zp0012ParseException.java)
**Acceptance / logic checks:**
- Parser correctly extracts zeropay_txn_ref and result_code from a hand-crafted test byte array.
- Trailer record_count mismatch throws Zp0012ParseException (not NullPointerException).
- Control sum mismatch throws Zp0012ParseException.
- Header file_type='ZP0021' throws Zp0012ParseException (wrong file type).
- registered_amount represented as BigDecimal with scale=0 (KRW).
**Depends on:** 9.4-T08

### 9.4-T10 — ZP0012 line-by-line matcher: reconcile ZP0012 results against ZP0011 submission  _(45 min)_
**Context:** WBS 9.4. After parsing ZP0012 (T09), line-by-line matching runs against ZP0011 records. Matching key: zeropay_txn_ref + txn_date (per SCH-06 §5.2). SCH-06 §5.4 conditions: (1) result_code='00' AND registered_amount.compareTo(payout_amount_krw)==0 -> batch_registration_status=SETTLEMENT_REGISTERED; (2) result_code non-'00' -> REGISTRATION_FAILED + batch_exception; (3) ZP0011 record absent from ZP0012 -> REGISTRATION_UNKNOWN + batch_exception; (4) ZP0012 record not in ZP0011 -> ANOMALY batch_exception, no payment update; (5) result_code='00' but registered_amount != payout_amount_krw -> REGISTRATION_AMOUNT_MISMATCH + batch_exception. All amount comparisons use BigDecimal.compareTo (not .equals). Entire match+update runs inside one @Transactional block. Module services/scheme-adapter-zeropay, class Zp0012MatchingService @Service.
**Steps:** Implement Zp0012MatchingService @Service in com.gme.pay.zeropay.batch.zp0012; method match(Long zp0011BatchFileId, Long zp0012BatchFileId, List<Zp0012ResultRecord> results).; Load ZP0011 payments from DB: query payments WHERE zeropay_batch_file_id=zp0011BatchFileId; build Map<String,Payment> keyed by (zeropay_txn_ref+'_'+txn_date).; For each Zp0012ResultRecord look up map; apply 5-way conditional logic; call payment update and/or BatchExceptionService.raise accordingly.; After iterating all ZP0012 records, find ZP0011 payments with no matching ZP0012 record (set difference) and mark REGISTRATION_UNKNOWN + exception.; Annotate match() with @Transactional(rollbackFor=Exception.class); DB updates and exception inserts are atomic.
**Deliverable:** services/scheme-adapter-zeropay/src/main/java/com/gme/pay/zeropay/batch/zp0012/Zp0012MatchingService.java
**Acceptance / logic checks:**
- Payment with result_code='00' and registered_amount==payout_amount_krw gets batch_registration_status=SETTLEMENT_REGISTERED; no exception raised.
- Payment with result_code='09' gets batch_registration_status=REGISTRATION_FAILED; BatchExceptionService.raise called with REGISTRATION_FAILED.
- ZP0011 record absent from ZP0012 gets REGISTRATION_UNKNOWN; exception raised.
- ZP0012 record with no ZP0011 match creates batch_exception with exception_type=ANOMALY; no payment updated.
- Amount comparison uses BigDecimal.compareTo (not .equals).
**Depends on:** 9.4-T09, 9.4-T04

### 9.4-T11 — Outbox event: PaymentBatchRegistrationResultEvent for state changes  _(35 min)_
**Context:** WBS 9.4. After Zp0012MatchingService updates batch_registration_status (T10), a domain event must be published via the transactional Outbox (Phase 1 approach per STACK.md: no Kafka yet; EventPublisher inserts into outbox_events in same transaction; Outbox poller delivers to Kafka in integration phase). Define PaymentBatchRegistrationResultEvent in lib-events module (com.gme.pay.events.batch) with fields: paymentId (Long), zeropayTxnRef (String), batchFileId (Long), batchRegistrationStatus (String), registeredAmountKrw (BigDecimal), settlementDate (LocalDate), eventTimestamp (Instant). outbox_events table schema: id BIGSERIAL PK, aggregate_type VARCHAR(50), aggregate_id BIGINT, event_type VARCHAR(80), payload JSONB, created_at TIMESTAMPTZ, published_at TIMESTAMPTZ NULLABLE. Outbox migration may already exist from earlier WBS; check first.
**Steps:** Define PaymentBatchRegistrationResultEvent as a Java record in lib-events/src/main/java/com/gme/pay/events/batch/ with all listed fields.; Check if outbox_events Flyway migration exists in services/payment-executor or services/scheme-adapter-zeropay migrations; create V{N}__outbox_events.sql only if absent.; Implement EventPublisher @Service (or reuse if exists from earlier WBS) in services/scheme-adapter-zeropay that inserts into outbox_events within the caller's @Transactional.; Call EventPublisher.publish(new PaymentBatchRegistrationResultEvent(...)) from Zp0012MatchingService.match() for each payment status change, within the same @Transactional block.; Verify lib-events has no dependency on any service module (no circular deps); it is a pure schema/DTO module.
**Deliverable:** lib-events/src/main/java/com/gme/pay/events/batch/PaymentBatchRegistrationResultEvent.java and outbox insert logic in Zp0012MatchingService.java
**Acceptance / logic checks:**
- PaymentBatchRegistrationResultEvent contains all listed fields including eventTimestamp (Instant).
- Outbox row inserted within same @Transactional as payment status update; if transaction rolls back, no outbox row persists.
- outbox_events.event_type='PAYMENT_BATCH_REGISTRATION_RESULT' for these events.
- lib-events module build.gradle has no dependency on services/* modules.
- Published event payload serializes to valid JSON with all fields present.
**Depends on:** 9.4-T10

### 9.4-T12 — BatchExceptionService: persist exception records and dispatch P1 ops alerts  _(35 min)_
**Context:** WBS 9.4. All mismatch and error conditions during ZP0011/ZP0012 processing must persist batch_exception rows and alert Ops for P1 cases (per SCH-06 §9.2). BatchExceptionService @Service in services/scheme-adapter-zeropay encapsulates this. P1 types: SFTP_FAILURE, PARSE_ERROR, REGISTRATION_FAILED, REGISTRATION_UNKNOWN, REGISTRATION_AMOUNT_MISMATCH. P2 types: ANOMALY. For P1: insert batch_exception row then call NotificationService.alertOps(alertCode, details) which publishes an OPS_ALERT outbox event within the same @Transactional. For P2 (ANOMALY): insert row, log WARN, do NOT call alertOps. Expose countOpenExceptions(Long batchFileId) -> int for the settlement gate check (T13). Expose resolve(Long exceptionId, Long resolvedBy, String note) -> void setting status='RESOLVED'.
**Steps:** Define ExceptionType enum in com.gme.pay.zeropay.batch.exception with values REGISTRATION_FAILED, REGISTRATION_UNKNOWN, REGISTRATION_AMOUNT_MISMATCH, ANOMALY, SFTP_FAILURE, PARSE_ERROR; annotate each with P1/P2 priority.; Implement BatchExceptionService @Service with method raise(Long batchFileId, Long paymentId, ExceptionType type, String zeropayTxnRef, Map<String,Object> details).; Inside raise(): insert batch_exception row (status='OPEN') via JPA; if type.priority==P1 call NotificationService.alertOps within same @Transactional.; Add countOpenExceptions(Long batchFileId) querying count WHERE batch_file_id=batchFileId AND status='OPEN'.; Add resolve(Long id, Long resolvedBy, String note): set status='RESOLVED', resolved_by, resolved_at=now().
**Deliverable:** services/scheme-adapter-zeropay/src/main/java/com/gme/pay/zeropay/batch/exception/BatchExceptionService.java
**Acceptance / logic checks:**
- REGISTRATION_FAILED type calls NotificationService.alertOps (P1 path).
- ANOMALY type inserts batch_exception row but does NOT call alertOps.
- batch_exception row has status='OPEN' on creation.
- countOpenExceptions returns correct count after multiple raise() calls for the same batchFileId.
- DB insert and alertOps outbox insert are atomic: if DB insert fails, alertOps is not invoked.
**Depends on:** 9.4-T01, 9.4-T11

### 9.4-T13 — SettlementGate: block ZP0061 generation for payments with open registration exceptions  _(30 min)_
**Context:** WBS 9.4. SCH-06 §5.4 and §8.2: registration failures not resolved before ZP0061 transmission window block settlement for affected transactions; non-affected transactions proceed normally. The ZP0061 batch assembler (services/scheme-adapter-zeropay) must check each payment's eligibility. SettlementGate @Service provides: isEligibleForSettlement(Long paymentId) -> boolean checking payments.batch_registration_status='SETTLEMENT_REGISTERED'; getBlockedPaymentCount(LocalDate settlementDate) -> int counting payments where settlement_date=arg AND batch_registration_status IN ('REGISTRATION_FAILED','REGISTRATION_UNKNOWN','REGISTRATION_AMOUNT_MISMATCH'). The ZP0061 assembly loop calls isEligibleForSettlement per payment and skips ineligible ones. If getBlockedPaymentCount > 0, log WARN and call NotificationService.alertOps('SETTLEMENT_BLOCKED_PAYMENTS', count).
**Steps:** Implement SettlementGate @Service in com.gme.pay.zeropay.batch.settlement.; Method isEligibleForSettlement(Long paymentId): query payments.batch_registration_status; return true only if='SETTLEMENT_REGISTERED'.; Method getBlockedPaymentCount(LocalDate settlementDate): COUNT payments WHERE settlement_date=arg AND batch_registration_status IN ('REGISTRATION_FAILED','REGISTRATION_UNKNOWN','REGISTRATION_AMOUNT_MISMATCH').; Wire SettlementGate into ZP0061 batch assembler: call isEligibleForSettlement per payment in the assembly loop; skip if false.; After loop, call getBlockedPaymentCount; if >0 call NotificationService.alertOps('SETTLEMENT_BLOCKED_PAYMENTS', count).
**Deliverable:** services/scheme-adapter-zeropay/src/main/java/com/gme/pay/zeropay/batch/settlement/SettlementGate.java
**Acceptance / logic checks:**
- isEligibleForSettlement returns true only for batch_registration_status='SETTLEMENT_REGISTERED'.
- isEligibleForSettlement returns false for REGISTRATION_FAILED status.
- getBlockedPaymentCount returns correct integer count for given settlement date.
- alertOps('SETTLEMENT_BLOCKED_PAYMENTS') called when blockedCount > 0.
- Gate check runs per-payment inside ZP0061 assembly loop (not once as a pre-check).
**Depends on:** 9.4-T10, 9.4-T12

### 9.4-T14 — IdempotencyGuard: prevent duplicate file generation per business date  _(35 min)_
**Context:** WBS 9.4. SAD-02 §5.4 mandates all settlement scheduler jobs be idempotent. For ZP0011 (and by pattern for ZP0021/ZP0061/ZP0065/ZP0066), idempotency means: a re-run must not produce a second file with the same sequence_no for the same business date. For intentional retransmits (SFTP failure), sequence_no increments. IdempotencyGuard @Service in services/scheme-adapter-zeropay queries payment_batch_file and returns RunDecision enum: FIRST_RUN (no row, sequence_no=1), RETRANSMIT (status=FAILED, sequence_no=previous+1), SKIP (status=TRANSMITTED). Maximum sequence_no=99; beyond that throw BatchSequenceExhaustedException. Zp0011ScheduledJob calls checkRun first.
**Steps:** Define RunDecision enum: FIRST_RUN, RETRANSMIT, SKIP (each carrying int sequenceNo).; Implement IdempotencyGuard @Service in com.gme.pay.zeropay.batch with method checkRun(String fileType, LocalDate settlementDate) -> RunDecision.; Query payment_batch_file for (file_type, settlement_date); if no row -> FIRST_RUN(1); if status='TRANSMITTED' -> SKIP; if status='FAILED' -> compute next_seq = maxSequenceNo+1; if next_seq > 99 throw BatchSequenceExhaustedException; return RETRANSMIT(next_seq).; Wire into Zp0011ScheduledJob: call checkRun first; if SKIP return immediately; pass sequenceNo to Zp0011BatchJob.; Add same guard call to ZP0021/ZP0061/ZP0065/ZP0066 batch jobs (guard is generic on fileType).
**Deliverable:** services/scheme-adapter-zeropay/src/main/java/com/gme/pay/zeropay/batch/IdempotencyGuard.java
**Acceptance / logic checks:**
- No payment_batch_file row -> checkRun returns FIRST_RUN with sequenceNo=1.
- Existing row with status='TRANSMITTED' -> checkRun returns SKIP.
- Existing row with status='FAILED' and sequence_no=1 -> checkRun returns RETRANSMIT with sequenceNo=2.
- sequence_no=99 + status='FAILED' -> checkRun throws BatchSequenceExhaustedException.
- Zp0011ScheduledJob calls fileAssembler 0 times when guard returns SKIP (verifiable via Mockito.verify).
**Depends on:** 9.4-T05, 9.4-T07

### 9.4-T15 — UNCERTAIN transaction resolution via ZP0012 ingestion  _(45 min)_
**Context:** WBS 9.4. Per SAD-02 AD-09: transactions left in UNCERTAIN state (network failure after scheme call) are resolved within 24h via ZP0012. After Zp0012MatchingService processes the file (T10), any payment with status=UNCERTAIN that now has a ZP0012 record must be transitioned: result_code='00' -> APPROVED; result_code non-'00' -> FAILED. For OVERSEAS+FAILED: reverse prefunding deduction via PrefundingLedgerService (per SAD-02 prefunding atomicity note: deduction held during UNCERTAIN, reversed on confirmed FAILED). The scheme adapter calls services/payment-executor internal endpoint POST /internal/v1/payments/{id}/resolve-uncertain body {outcome:APPROVED|FAILED, zeropayResultCode, batchFileId}. Orchestrator transitions state and (if OVERSEAS FAILED) calls PrefundingLedgerService.reverseDeduction(paymentId) within one @Transactional. Publishes PaymentStateChangedEvent via Outbox.
**Steps:** Define ResolveUncertainRequest DTO in lib-api-contracts with fields outcome (String APPROVED|FAILED), zeropayResultCode (String), batchFileId (Long).; In services/payment-executor implement POST /internal/v1/payments/{id}/resolve-uncertain in PaymentExecutorInternalController: load payment, validate status=UNCERTAIN (409 if not), transition state.; If outcome=FAILED AND partner.type=OVERSEAS call PrefundingLedgerService.reverseDeduction(paymentId) in same @Transactional.; Publish PaymentStateChangedEvent (new state) via EventPublisher/Outbox within same transaction.; In Zp0012MatchingService, after detecting an UNCERTAIN payment in matching logic, call TransactionOrchestratorInternalClient.resolveUncertain(paymentId, outcome, resultCode, batchFileId).
**Deliverable:** services/payment-executor/src/main/java/com/gme/pay/executor/api/internal/UncertainResolutionHandler.java and services/scheme-adapter-zeropay/src/main/java/com/gme/pay/zeropay/batch/zp0012/TransactionOrchestratorInternalClient.java
**Acceptance / logic checks:**
- UNCERTAIN + result_code='00' transitions payment to APPROVED.
- UNCERTAIN + result_code='09' transitions payment to FAILED.
- OVERSEAS + FAILED triggers PrefundingLedgerService.reverseDeduction exactly once.
- LOCAL + FAILED does NOT call reverseDeduction.
- resolveUncertain called on non-UNCERTAIN payment returns HTTP 409 Conflict.
**Depends on:** 9.4-T10, 9.4-T11

### 9.4-T16 — Unit tests: Zp0011RecordBuilder fixed-width formatting and control sum  _(30 min)_
**Context:** WBS 9.4. JUnit 5 unit tests for Zp0011RecordBuilder (T03). Pure logic tests; no Spring context, no Testcontainers needed. Test class: Zp0011RecordBuilderTest in services/scheme-adapter-zeropay test sources (src/test/java/com/gme/pay/zeropay/batch/zp0011/).
**Steps:** Test formatPayoutAmount: input payout_amount_krw=BigDecimal(150000), expect detail line substring '000000150000' (12-char zero-padded field).; Test padGmeTxnId: gme_txn_id='TXN001' (6 chars) produces a 20-char field 'TXN001              ' (right-padded spaces).; Test controlSum: build 3 detail records with payout_amount_krw=100000, 200000, 300000; trailer control_sum=600000.; Test recordCounts: file with 5 detail records -> header.totalRecordCount=5 and trailer.totalRecordCount=5.; Test blankZeropayTxnRef: calling buildFile with a Zp0011DetailRecord having blank zeropay_txn_ref throws IllegalArgumentException.
**Deliverable:** services/scheme-adapter-zeropay/src/test/java/com/gme/pay/zeropay/batch/zp0011/Zp0011RecordBuilderTest.java
**Acceptance / logic checks:**
- All 5 test methods pass with ./gradlew :services:scheme-adapter-zeropay:test --tests Zp0011RecordBuilderTest.
- payout_amount_krw=150000 formats as exactly '000000150000' (12 chars, no spaces, no decimal).
- controlSum of [100000,200000,300000] = 600000 in trailer.
- header.totalRecordCount == trailer.totalRecordCount == 5.
- Blank zeropay_txn_ref raises IllegalArgumentException before any I/O occurs.
**Depends on:** 9.4-T03

### 9.4-T17 — Unit tests: Zp0012FileParser validation and parse error cases  _(30 min)_
**Context:** WBS 9.4. JUnit 5 unit tests for Zp0012FileParser (T09). Use hand-crafted byte arrays representing synthetic ZP0012 files. No Spring context, no Testcontainers. Test class: Zp0012FileParserTest in services/scheme-adapter-zeropay src/test/java/com/gme/pay/zeropay/batch/zp0012/.
**Steps:** Test parseValidFile: hand-craft header(ZP0012)+2 detail records+trailer(count=2,controlSum=sum); assert parse returns 2 Zp0012ResultRecord objects.; Test trailerCountMismatch: trailer says 3 but file has 2 detail records; assert Zp0012ParseException thrown.; Test controlSumMismatch: trailer control_sum does not equal sum of registered_amount; assert Zp0012ParseException.; Test wrongFileType: header file_type='ZP0021'; assert Zp0012ParseException.; Test resultCodeParsed: detail record with result_code='00' parsed to resultCode='00' on Zp0012ResultRecord.
**Deliverable:** services/scheme-adapter-zeropay/src/test/java/com/gme/pay/zeropay/batch/zp0012/Zp0012FileParserTest.java
**Acceptance / logic checks:**
- All 5 test methods pass.
- trailerCountMismatch throws Zp0012ParseException with non-null message.
- controlSumMismatch throws Zp0012ParseException (not ArithmeticException).
- wrongFileType throws Zp0012ParseException (not NullPointerException).
- Valid 2-record file returns exactly 2 Zp0012ResultRecord objects.
**Depends on:** 9.4-T09

### 9.4-T18 — Unit tests: Zp0012MatchingService all 5 SCH-06 §5.4 mismatch conditions  _(40 min)_
**Context:** WBS 9.4. JUnit 5 + Mockito tests for Zp0012MatchingService (T10). Mock PaymentRepository and BatchExceptionService. Cover all 5 conditions from SCH-06 §5.4. Test class: Zp0012MatchingServiceTest in services/scheme-adapter-zeropay test sources. Input vectors: ZP0011 payments TXN001 (payout=150000), TXN002 (payout=200000), TXN003 (payout=180000), TXN004 (payout=90000); ZP0012 results: TXN001 result_code='00' amount=150000; TXN002 result_code='07'; TXN003 result_code='00' amount=145000 (mismatch); ZP0012 extra TXN999 (no ZP0011 match); TXN004 absent from ZP0012.
**Steps:** Setup mocks: PaymentRepository returns map of 4 payments; BatchExceptionService is a Mockito mock.; Test condition1_success: TXN001 result_code='00' amount matches -> payment.batchRegistrationStatus=SETTLEMENT_REGISTERED; verify BatchExceptionService.raise never called for TXN001.; Test condition2_registrationFailed: TXN002 result_code='07' -> REGISTRATION_FAILED; verify raise called once with REGISTRATION_FAILED.; Test condition3_registrationUnknown: TXN004 absent from ZP0012 -> REGISTRATION_UNKNOWN; verify raise called with REGISTRATION_UNKNOWN.; Test condition4_anomaly: TXN999 in ZP0012 but no ZP0011 match -> raise called with ANOMALY; no payment updated.; Test condition5_amountMismatch: TXN003 result_code='00' but amount=145000 vs 180000 -> REGISTRATION_AMOUNT_MISMATCH; verify raise called.
**Deliverable:** services/scheme-adapter-zeropay/src/test/java/com/gme/pay/zeropay/batch/zp0012/Zp0012MatchingServiceTest.java
**Acceptance / logic checks:**
- All 6 test scenarios pass.
- Condition 1: BatchExceptionService.raise NOT called for TXN001.
- Condition 2: raise called once with ExceptionType.REGISTRATION_FAILED for TXN002.
- Condition 3: raise called once with ExceptionType.REGISTRATION_UNKNOWN for TXN004.
- Condition 5: amount comparison uses BigDecimal(145000).compareTo(BigDecimal(180000))!=0 path, not .equals.
**Depends on:** 9.4-T10

### 9.4-T19 — Unit tests: IdempotencyGuard run-decision logic  _(30 min)_
**Context:** WBS 9.4. JUnit 5 + Mockito tests for IdempotencyGuard (T14). Mock PaymentBatchFileRepository. Cover FIRST_RUN, SKIP, RETRANSMIT, and sequence overflow. Test class: IdempotencyGuardTest in services/scheme-adapter-zeropay test sources.
**Steps:** Test firstRun: no row for (ZP0011, 2026-06-01) -> checkRun returns FIRST_RUN with sequenceNo=1.; Test skip: row exists with status='TRANSMITTED' -> checkRun returns SKIP.; Test retransmit: row exists with status='FAILED' and sequence_no=1 -> checkRun returns RETRANSMIT with sequenceNo=2.; Test sequenceOverflow: row exists with status='FAILED' and sequence_no=99 -> checkRun throws BatchSequenceExhaustedException.; Test schedulerSkipsAssembly: inject mock Zp0011FileAssembler into Zp0011ScheduledJob; when guard returns SKIP verify assembler.assemble never called.
**Deliverable:** services/scheme-adapter-zeropay/src/test/java/com/gme/pay/zeropay/batch/IdempotencyGuardTest.java
**Acceptance / logic checks:**
- All 5 test methods pass.
- FIRST_RUN returns sequenceNo=1.
- RETRANSMIT returns sequenceNo=2 when existing row has sequence_no=1.
- sequence_no=99 + FAILED throws BatchSequenceExhaustedException.
- Zp0011FileAssembler.assemble is never invoked when guard returns SKIP.
**Depends on:** 9.4-T14

### 9.4-T20 — Unit tests: UNCERTAIN resolution with OVERSEAS prefunding reversal  _(30 min)_
**Context:** WBS 9.4. JUnit 5 + Mockito tests for UncertainResolutionHandler (T15). Mock PrefundingLedgerService and PaymentRepository. Test all 4 branches. Test class: UncertainResolutionHandlerTest in services/payment-executor test sources.
**Steps:** Test overseasFailed: payment status=UNCERTAIN partnerType=OVERSEAS outcome=FAILED -> payment.status=FAILED; verify PrefundingLedgerService.reverseDeduction called once.; Test localFailed: payment status=UNCERTAIN partnerType=LOCAL outcome=FAILED -> payment.status=FAILED; verify reverseDeduction never called.; Test approved: payment status=UNCERTAIN outcome=APPROVED -> payment.status=APPROVED; verify reverseDeduction never called.; Test notUncertain: payment status=APPROVED -> handler throws PaymentStateException; verify no DB update.
**Deliverable:** services/payment-executor/src/test/java/com/gme/pay/executor/api/internal/UncertainResolutionHandlerTest.java
**Acceptance / logic checks:**
- OVERSEAS FAILED: reverseDeduction called exactly once.
- LOCAL FAILED: reverseDeduction never called.
- APPROVED: payment status set to APPROVED; no prefunding call.
- Non-UNCERTAIN payment: PaymentStateException thrown; payment repo save not called.
- All 4 test methods pass.
**Depends on:** 9.4-T15

### 9.4-T21 — Integration test: ZP0011 assembly + ZP0012 match full cycle with Testcontainers Postgres  _(50 min)_
**Context:** WBS 9.4. Full integration test of the generate->upload->match cycle using Testcontainers PostgreSQL 16. @SpringBootTest(webEnvironment=NONE). WireMock stubs the payment-executor internal endpoint. Object Storage stubbed via LocalStack or in-memory S3Mock. SFTP not tested here (covered in T22). Class: Zp0011Zp0012IntegrationTest in services/scheme-adapter-zeropay src/test/java/com/gme/pay/zeropay/batch/.
**Steps:** Configure @Container PostgreSQLContainer('postgres:16'); apply Flyway migrations in test context.; WireMock stub: GET /internal/v1/payments/pending-batch-registration?businessDate=20260601 returns 2 payment projections (TXN001 payout=150000 OVERSEAS, TXN002 payout=200000 LOCAL).; Call Zp0011BatchJob.run(LocalDate.of(2026,6,1)); assert payment_batch_file row inserted with record_count=2 control_sum=350000.; Build synthetic ZP0012 bytes: TXN001 result_code='00' amount=150000; TXN002 result_code='09'. Call Zp0012MatchingService.match(zp0011FileId, zp0012FileId, parsedResults).; Assert: payments.batch_registration_status for TXN001=SETTLEMENT_REGISTERED; TXN002=REGISTRATION_FAILED; batch_exception row for TXN002 with exception_type=REGISTRATION_FAILED.
**Deliverable:** services/scheme-adapter-zeropay/src/test/java/com/gme/pay/zeropay/batch/Zp0011Zp0012IntegrationTest.java
**Acceptance / logic checks:**
- Test passes entirely via Testcontainers (no local DB or SFTP server required).
- payment_batch_file row has record_count=2 and control_sum=350000.
- After match: TXN001 batch_registration_status=SETTLEMENT_REGISTERED.
- After match: TXN002 batch_registration_status=REGISTRATION_FAILED.
- batch_exception table has 1 row with exception_type=REGISTRATION_FAILED for TXN002.
**Depends on:** 9.4-T05, 9.4-T10, 9.4-T12

### 9.4-T22 — Integration test: SFTP outbound transmit and inbound poll with embedded Apache MINA SSHD  _(50 min)_
**Context:** WBS 9.4. Integration test for SftpGatewayClient (T06) and Zp0012InboundPoller (T08) using Apache MINA SSHD 2.x embedded SFTP server as test double. Test class: SftpTransmissionIntegrationTest in services/scheme-adapter-zeropay. @SpringBootTest(webEnvironment=NONE). Vault replaced with a test @Configuration providing fixed test RSA key pair (generated in @BeforeAll, not committed). Object Storage uses LocalStack S3 or in-memory stub.
**Steps:** Add Apache MINA SSHD (sshd-core, sshd-sftp 2.x) to testImplementation in services/scheme-adapter-zeropay/build.gradle.; Stand up SshServer with SftpSubsystemFactory in @BeforeAll; virtualFileSystemFactory pointing to tmp dir with /gmepay/outbound and /gmepay/inbound.; Override SftpGatewayClient config: host=localhost, port=randomPort, SSH key = test key pair from @BeforeAll.; Test transmit: call client.transmit(objectStorageKey, 'ZP0011_20260601_01.dat.pgp'); assert file present in tmp/outbound/.; Test inbound poll idempotency: place synthetic ZP0012 file in tmp/inbound/; call poller.pollOnce(date) twice; assert exactly 1 payment_batch_file row (no duplicate on second call).
**Deliverable:** services/scheme-adapter-zeropay/src/test/java/com/gme/pay/zeropay/sftp/SftpTransmissionIntegrationTest.java
**Acceptance / logic checks:**
- ZP0011 file appears in embedded SFTP /gmepay/outbound/ after transmit call.
- ZP0012 file in /gmepay/inbound/ is downloaded and payment_batch_file row inserted with status=RECEIVED.
- Second pollOnce call for same file does NOT create a duplicate payment_batch_file row.
- Test SSH key used (no Vault call); injected via test @Configuration overriding VaultTemplate bean.
- Test runs without any real SFTP server (embedded MINA SSHD only).
**Depends on:** 9.4-T06, 9.4-T08

### 9.4-T23 — Batch exception queue API: GET and resolve endpoints for Ops Admin portal  _(40 min)_
**Context:** WBS 9.4. SCH-06 §5.4: all exception records must be surfaced in the Ops Admin portal exception queue. Expose endpoints in services/scheme-adapter-zeropay (consumed by Ops/Partner BFF). GET /internal/v1/batch-exceptions?status=OPEN&fileType=ZP0012&page=0&size=20 returns Page<BatchExceptionDto>. BatchExceptionDto: id, batchFileId, paymentId, exceptionType, zeropayTxnRef, details (JsonNode), status, createdAt. POST /internal/v1/batch-exceptions/{id}/resolve body {resolvedBy: Long, resolutionNote: String} marks exception status='RESOLVED'. @RestController BatchExceptionController in com.gme.pay.zeropay.batch.exception. @PreAuthorize('hasRole(ROLE_OPS)') on both endpoints. /internal/** blocked from Spring Cloud Gateway as with other internal endpoints.
**Steps:** Add BatchExceptionDto to lib-api-contracts in com.gme.pay.contracts.internal.batch with all listed fields.; Implement BatchExceptionQueryService @Service: findOpen(fileType, Pageable) -> Page<BatchExceptionDto>; resolve(id, resolvedBy, note) -> void.; Implement BatchExceptionController @RestController with GET and POST endpoints; add @PreAuthorize('hasRole(ROLE_OPS)').; Add ResolveExceptionRequest DTO with resolvedBy (Long) and resolutionNote (String) for POST body.; Confirm /internal/** Spring Security rule blocks non-internal access (consistent with T04 security config).
**Deliverable:** services/scheme-adapter-zeropay/src/main/java/com/gme/pay/zeropay/batch/exception/BatchExceptionController.java
**Acceptance / logic checks:**
- GET returns only exceptions with status='OPEN' when ?status=OPEN filter applied.
- POST /resolve sets status='RESOLVED', resolved_by, resolved_at on the exception row.
- Request without ROLE_OPS receives HTTP 403.
- Page results respect size parameter: size=2 returns at most 2 results.
- fileType=ZP0012 filter returns only exceptions linked to ZP0012 batch files.
**Depends on:** 9.4-T12

### 9.4-T24 — OpenTelemetry + Micrometer instrumentation for ZP0011/ZP0012 batch operations  _(40 min)_
**Context:** WBS 9.4. NFR-10 and OPS-13 require observability. Add OpenTelemetry tracing spans and Micrometer metrics to the batch flow in services/scheme-adapter-zeropay. Spans: zeropay.batch.zp0011.generate (root), child spans for file_assemble, sftp_transmit; zeropay.batch.zp0012.ingest (root), child spans for file_parse, line_match. Micrometer metrics (via MeterRegistry; Spring Boot 3 auto-configures Prometheus endpoint): Counter zeropay_batch_exception_total tags=[file_type, exception_type]; Gauge zeropay_batch_open_exceptions tags=[file_type]; Timer zeropay_batch_processing_seconds tags=[file_type, operation]. Use @WithSpan annotation (OpenTelemetry Spring instrumentation) or programmatic Tracer spans. All metric names underscore-separated per Prometheus convention.
**Steps:** Confirm io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter is in scheme-adapter-zeropay/build.gradle (add if absent).; Add @WithSpan('zeropay.batch.zp0011.generate') on Zp0011BatchJob.run(); add child @WithSpan on Zp0011FileAssembler.assemble() and SftpGatewayClient.transmit().; Add @WithSpan('zeropay.batch.zp0012.ingest') on Zp0012InboundPoller.processFile(); child spans on Zp0012FileParser.parse() and Zp0012MatchingService.match().; In BatchExceptionService.raise(): increment Counter zeropay_batch_exception_total with tags file_type and exception_type via meterRegistry.counter(...).increment().; Register Gauge zeropay_batch_open_exceptions in BatchExceptionService @PostConstruct: Gauge.builder(...).register(meterRegistry) backed by countOpenExceptions() for each known file_type.; Wrap Zp0012FileParser.parse() and Zp0012MatchingService.match() in Timer.Sample to record zeropay_batch_processing_seconds.
**Deliverable:** Updated Zp0011BatchJob.java, Zp0012InboundPoller.java, Zp0012FileParser.java, Zp0012MatchingService.java, BatchExceptionService.java with span and metric instrumentation
**Acceptance / logic checks:**
- zeropay_batch_exception_total counter increments by 1 per BatchExceptionService.raise() call (verify with SimpleMeterRegistry in unit test).
- zeropay_batch_open_exceptions gauge reflects current DB count when queried.
- Root span zeropay.batch.zp0011.generate is created and closed (no unclosed spans) during Zp0011BatchJob.run().
- Timer zeropay_batch_processing_seconds recorded after each Zp0012MatchingService.match() call.
- All metric names use underscores not dots.
**Depends on:** 9.4-T07, 9.4-T10

### 9.4-T25 — application.yml: ZP0011/ZP0012 batch and SFTP configuration with @ConfigurationProperties  _(35 min)_
**Context:** WBS 9.4. Externalize all runtime config for the ZP0011/ZP0012 batch into services/scheme-adapter-zeropay/src/main/resources/application.yml. Keys: zeropay.batch.zp0011.cron (default '0 0 2 * * *'), zeropay.batch.zp0012.poll-cron (default '0 */5 5 * * *'), zeropay.batch.zp0012.late-alert-cron (default '0 5 6 * * *'), zeropay.sftp.host, zeropay.sftp.port (default 22), zeropay.sftp.username, zeropay.sftp.outbound-dir (default /gmepay/outbound), zeropay.sftp.inbound-dir (default /gmepay/inbound), zeropay.sftp.private-key-vault-path (Vault path for private key; @NotBlank), zeropay.object-storage.endpoint, zeropay.object-storage.bucket (default zeropay-batch-files), zeropay.institution-code (@NotBlank). Sensitive values reference Vault. Create @ConfigurationProperties(prefix='zeropay') class ZeropayBatchProperties with nested SftpProperties and ObjectStorageProperties. application-local.yml for docker-compose MinIO+test SFTP.
**Steps:** Create ZeropayBatchProperties @ConfigurationProperties(prefix='zeropay') @Validated in com.gme.pay.zeropay.config with nested static classes BatchProperties, SftpProperties, ObjectStorageProperties.; Add @NotBlank on zeropay.sftp.private-key-vault-path and zeropay.institution-code; bind all other fields with defaults.; Add @EnableConfigurationProperties(ZeropayBatchProperties.class) to ZeropayAdapterApplication or a @Configuration class.; Populate src/main/resources/application.yml with all keys, defaults, and Vault path placeholders for sensitive fields.; Create src/main/resources/application-local.yml with docker-compose overrides: MinIO endpoint, test SFTP host/port; add a binding test @SpringBootTest(properties=...) verifying no BindException.
**Deliverable:** services/scheme-adapter-zeropay/src/main/java/com/gme/pay/zeropay/config/ZeropayBatchProperties.java and src/main/resources/application.yml
**Acceptance / logic checks:**
- ZeropayBatchProperties binds without BindException in @SpringBootTest slice providing all required fields.
- zeropay.batch.zp0011.cron defaults to '0 0 2 * * *' when not overridden.
- Missing zeropay.sftp.private-key-vault-path causes ConstraintViolationException at startup (bean validation).
- No credential or key material hardcoded in application.yml (only Vault path references).
- application-local.yml provides working MinIO endpoint and SFTP test host overrides for docker-compose local dev.
**Depends on:** 9.4-T07, 9.4-T08


## WBS 9.5 — Refund result exchange (ZP0021/0022)
### 9.5-T01 — Flyway migration: add ZP0021/ZP0022 columns to refund table  _(25 min)_
**Context:** Module: services/settlement-reconciliation. The refund table (PostgreSQL 16, DAT-03 §5.4) currently has: id BIGINT PK, txn_id BIGINT FK, refund_ref VARCHAR(30), refund_amount NUMERIC(20,4), refund_ccy CHAR(3), reason VARCHAR(255), status VARCHAR(30), scheme_refund_ref VARCHAR(30), initiated_by VARCHAR(60). For the ZP0021/ZP0022 batch round-trip, three new columns are required: zp_batch_id BIGINT NULL FK -> settlement_batch(id) (links refund to its ZP0021 outbound batch row); zp_result_code CHAR(2) NULL (raw result_code from ZP0022: 00=Success, non-zero=failure); adjustment_settlement_date DATE NULL (the date ZeroPay will apply the merchant adjustment, from ZP0022). The refund.status CHECK constraint must accept all five lifecycle states: REFUND_PENDING (created, awaiting batch), REFUND_SUBMITTED (ZP0021 sent), REFUND_CONFIRMED (ZP0022 result_code=00), REFUND_FAILED (ZP0022 non-zero), REFUNDED (prefunding reversal complete for OVERSEAS; terminal).
**Steps:** In services/settlement-reconciliation/src/main/resources/db/migration/ create VN__refund_zp_batch_columns.sql (N = next sequence number after existing migrations); Add: ALTER TABLE refund ADD COLUMN zp_batch_id BIGINT NULL REFERENCES settlement_batch(id); Add: ALTER TABLE refund ADD COLUMN zp_result_code CHAR(2) NULL; Add: ALTER TABLE refund ADD COLUMN adjustment_settlement_date DATE NULL; Drop existing status CHECK constraint and recreate accepting exactly: REFUND_PENDING, REFUND_SUBMITTED, REFUND_CONFIRMED, REFUND_FAILED, REFUNDED; Add index: CREATE INDEX idx_refund_zp_batch_id ON refund(zp_batch_id); CREATE INDEX idx_refund_status ON refund(status)
**Deliverable:** services/settlement-reconciliation/src/main/resources/db/migration/VN__refund_zp_batch_columns.sql
**Acceptance / logic checks:**
- Migration applies cleanly on a Testcontainers PostgreSQL 16 instance via Flyway with no existing refund rows
- zp_batch_id FK insert referencing a non-existent settlement_batch(id) raises FK violation
- status CHECK rejects a value not in the five listed states
- Inserting a refund row with zp_batch_id=NULL, status='REFUND_PENDING' succeeds
- idx_refund_status is visible in pg_indexes for the refund table after migration

### 9.5-T02 — Define ZP0021Record, ZP0021Header, ZP0021Trailer, ZP0022Record Java model classes  _(35 min)_
**Context:** Module: services/settlement-reconciliation (or lib-events for shared batch models). ZP0021 is the outbound refund result file (GME -> ZeroPay PPF, deadline ~02:00 KST). Detail record fields (SCH-06 §6.2): record_type CHAR(1) fixed 'D'; gme_refund_id CHAR(20); original_zeropay_txn_ref CHAR(20); gme_original_txn_id CHAR(20); merchant_id CHAR(10); refund_date LocalDate (YYYYMMDD KST); refund_time LocalTime (HHMMSS KST); refund_amount_krw BigDecimal (NUM(12), KRW 0 decimals); merchant_fee_adj_amt BigDecimal (NUM(12)); partner_type CHAR(1) D=Domestic/I=International; refund_reason_code CHAR(2); status_code CHAR(1) fixed 'R'. ZP0021Header: file_type CHAR(6) fixed 'ZP0021'; business_date LocalDate; gme_institution_code String(10); total_record_count int; total_refund_amount_krw BigDecimal. ZP0021Trailer: total_record_count int; control_sum BigDecimal. ZP0022 (ZeroPay -> GME, ~05:00 KST) detail record (SCH-06 §6.3): original_zeropay_txn_ref CHAR(20) (match key with refund_date); gme_refund_id CHAR(20); result_code CHAR(2) (00=Success); result_message String(100); registered_refund_amount BigDecimal NUM(12); adjustment_settlement_date LocalDate. All monetary fields use BigDecimal; all char fields validated in compact constructors.
**Steps:** Create com/gme/pay/settlement/zeropay/batch/model/ZP0021Record.java as Java record with all detail fields; Create ZP0021Header.java as Java record; compact constructor validates file_type equals 'ZP0021'; Create ZP0021Trailer.java as Java record; Create ZP0022Record.java as Java record with all ZP0022 detail fields from context; In compact constructors: validate gme_refund_id.length() <= 20, original_zeropay_txn_ref.length() <= 20, merchant_id.length() <= 10; throw IllegalArgumentException on violation; Annotate all records with @JsonInclude(NON_NULL) from lib-api-contracts for optional serialization
**Deliverable:** services/settlement-reconciliation/src/main/java/com/gme/pay/settlement/zeropay/batch/model/ZP0021Record.java, ZP0021Header.java, ZP0021Trailer.java, ZP0022Record.java
**Acceptance / logic checks:**
- ZP0021Record construction with refund_amount_krw=BigDecimal('50000'), partner_type='I', status_code='R' succeeds
- ZP0021Record construction with gme_refund_id of 21 chars throws IllegalArgumentException
- ZP0022Record with result_code='00' and registered_refund_amount=BigDecimal('50000') constructs without error
- ZP0021Header with file_type='ZP0011' throws IllegalArgumentException
- All monetary fields are BigDecimal -- no int or long for amounts in any of the four classes
**Depends on:** 9.5-T01

### 9.5-T03 — Implement RefundBatchQueryService: query REFUND_PENDING refunds for a KST business date  _(40 min)_
**Context:** Module: services/settlement-reconciliation. Before generating ZP0021, the Settlement Engine queries refunds eligible for the batch. Rules: (1) refund.status = 'REFUND_PENDING'; (2) DATE(refund.created_at AT TIME ZONE 'Asia/Seoul') = kstDate (prior business day, e.g. 2026-06-04); (3) transaction.scheme_id resolves to ZeroPay (join refund -> transaction -> scheme where scheme.scheme_id = 'ZEROPAY'); (4) transaction.status IN ('APPROVED','SCHEME_APPROVED') -- excludes same-day cancels. Returns List<RefundBatchItem> DTO containing: refund_id (Long), refund_ref (String), refund_amount_krw (BigDecimal KRW), merchant_id (String), original_zeropay_txn_ref (= transaction.zeropay_txn_ref, String), gme_original_txn_id (Long), partner_type (String: 'D' for LOCAL, 'I' for OVERSEAS), refund_reason_code (String), refund_created_at (ZonedDateTime). Use lib-persistence JPA utilities. Annotate query method @Transactional(readOnly=true).
**Steps:** Create com/gme/pay/settlement/zeropay/batch/RefundBatchItem.java as Java record with all fields listed in context; Create com/gme/pay/settlement/zeropay/batch/RefundBatchQueryService.java as @Service; Implement List<RefundBatchItem> findPendingForDate(LocalDate kstDate) using Spring Data @Query (JPQL or native) with join to transaction and partner; Filter: refund.status='REFUND_PENDING', DATE(created_at AT TIME ZONE 'Asia/Seoul')=:kstDate, scheme.scheme_id='ZEROPAY'; Map partner.type='LOCAL' -> partner_type='D'; partner.type='OVERSEAS' -> partner_type='I'; Log a WARN for any row where transaction.zeropay_txn_ref IS NULL (data integrity anomaly) and exclude that row from results
**Deliverable:** services/settlement-reconciliation/src/main/java/com/gme/pay/settlement/zeropay/batch/RefundBatchQueryService.java and RefundBatchItem.java
**Acceptance / logic checks:**
- findPendingForDate on a date with zero REFUND_PENDING refunds returns an empty list (not null)
- findPendingForDate returns only rows with status=REFUND_PENDING, not REFUND_SUBMITTED or REFUND_CONFIRMED
- A refund created at 2026-06-04T15:30:00 UTC (= 2026-06-05T00:30:00 KST) is returned by findPendingForDate(2026-06-05) and not by findPendingForDate(2026-06-04)
- partner_type in RefundBatchItem is 'D' for LOCAL partner, 'I' for OVERSEAS partner
- Rows where transaction.zeropay_txn_ref IS NULL are excluded from results and a WARN is logged
**Depends on:** 9.5-T01

### 9.5-T04 — Implement ZP0021FileBuilder: assemble ZP0021File DTO from eligible refunds and mark REFUND_SUBMITTED  _(50 min)_
**Context:** Module: services/settlement-reconciliation. ZP0021FileBuilder queries REFUND_PENDING refunds for the prior KST business date, maps each to ZP0021Record, and transitions their status to REFUND_SUBMITTED within the same @Transactional boundary. merchant_fee_adj_amt is the fee amount to be reversed: (refund_amount_krw * merchant_fee_rate) where merchant_fee_rate comes from the scheme merchant_fee_table config (JSON field by merchant type and partner_type). The assembled ZP0021File DTO holds: ZP0021Header (file_type='ZP0021', business_date=kstDate, gme_institution_code from SchemeConfig, total_record_count, total_refund_amount_krw=SUM), List<ZP0021Record>, ZP0021Trailer (total_record_count, control_sum=total_refund_amount_krw). After assembly, all included refund rows are updated to status='REFUND_SUBMITTED' and zp_batch_id is set (the settlement_batch row created in a subsequent step -- pass the batch id in). KRW amounts have 0 decimal places; reject any refund where refund_ccy != 'KRW' with IllegalArgumentException.
**Steps:** Create com/gme/pay/settlement/zeropay/batch/ZP0021FileBuilder.java as @Service; Inject RefundBatchQueryService, SchemeConfigRepository, RefundRepository; Implement ZP0021File buildForDate(LocalDate kstDate, Long batchId) within @Transactional; Call RefundBatchQueryService.findPendingForDate(kstDate); if empty return ZP0021File with zero records; For each RefundBatchItem validate refund_ccy='KRW'; map to ZP0021Record including merchant_fee_adj_amt computation; Assemble header and trailer; bulk-update matching refund rows to status='REFUND_SUBMITTED' and zp_batch_id=batchId
**Deliverable:** services/settlement-reconciliation/src/main/java/com/gme/pay/settlement/zeropay/batch/ZP0021FileBuilder.java
**Acceptance / logic checks:**
- buildForDate returns ZP0021File with zero records when findPendingForDate is empty; header.total_record_count=0 and total_refund_amount_krw=0
- buildForDate for 3 REFUND_PENDING refunds of 10000, 20000, 30000 KRW returns header.total_refund_amount_krw=60000 and total_record_count=3
- All 3 included refunds are updated to status='REFUND_SUBMITTED' and zp_batch_id set to the passed batchId after the call
- partner_type is 'D' for LOCAL partner refunds and 'I' for OVERSEAS refunds
- A refund with refund_ccy='USD' causes IllegalArgumentException before any status update
**Depends on:** 9.5-T02, 9.5-T03

### 9.5-T05 — Implement ZP0021Serializer: fixed-width UTF-8 serialization and SHA-256 checksum  _(45 min)_
**Context:** Module: services/settlement-reconciliation. ZeroPay batch files use fixed-width ASCII/UTF-8, CRLF line endings. Field widths from SCH-06 §6.2: record_type CHAR(1); gme_refund_id right-padded to 20; original_zeropay_txn_ref right-padded to 20; gme_original_txn_id right-padded to 20; merchant_id right-padded to 10; refund_date DATE formatted YYYYMMDD (8); refund_time TIME formatted HHMMSS (6); refund_amount_krw zero-padded left to 12; merchant_fee_adj_amt zero-padded left to 12; partner_type CHAR(1); refund_reason_code right-padded to 2; status_code CHAR(1). Header: file_type right-padded to 6; business_date YYYYMMDD (8); gme_institution_code right-padded to 10; total_record_count zero-padded to 6; total_refund_amount_krw zero-padded to 14. Trailer: total_record_count zero-padded to 6; control_sum zero-padded to 14. Encoding: StandardCharsets.UTF_8. Checksum: SHA-256 hex over full file bytes.
**Steps:** Create com/gme/pay/settlement/zeropay/batch/ZP0021Serializer.java as @Component; Implement byte[] serialize(ZP0021File file): write header line, each detail line, trailer line, each terminated with CRLF; Numeric fields: String.format('%0Nd', value.longValue()) for zero-padded widths; Char/string fields: String.format('%-Ns', value) for right-padded widths (space-pad); Implement String computeChecksum(byte[] content): MessageDigest.getInstance('SHA-256'), return hex-encoded 64-char lowercase string; Add a private helper padRight(String val, int width) and padLeft(String val, int width, char pad)
**Deliverable:** services/settlement-reconciliation/src/main/java/com/gme/pay/settlement/zeropay/batch/ZP0021Serializer.java
**Acceptance / logic checks:**
- Serializing a ZP0021File with 0 records produces exactly 2 CRLF-terminated lines (header + trailer)
- refund_amount_krw=50000 serializes as '000000050000' (12 chars zero-padded)
- gme_refund_id='REF001' serializes as 'REF001              ' (20 chars, 14 trailing spaces)
- control_sum in trailer equals total_refund_amount_krw in header for a single-record file
- computeChecksum produces a 64-character lowercase hex SHA-256 string
**Depends on:** 9.5-T04

### 9.5-T06 — Implement RefundBatchRepository: settlement_batch row lifecycle for ZP0021 and ZP0022  _(35 min)_
**Context:** Module: services/settlement-reconciliation. The settlement_batch table (PostgreSQL 16, DAT-03 §7.1) has: id BIGINT PK, scheme_id VARCHAR, file_type VARCHAR (ZP0021/ZP0022), direction VARCHAR (GME_TO_ZP/ZP_TO_GME), settlement_date DATE KST, status VARCHAR (PENDING/GENERATED/TRANSMITTED/RECEIVED/RECONCILED/ERROR), transaction_count INT, total_amount NUMERIC, total_amount_ccy CHAR(3), file_checksum VARCHAR(64), transmitted_at TIMESTAMPTZ, received_at TIMESTAMPTZ, reconciled_at TIMESTAMPTZ, error_detail TEXT. ZP0021 outbound: created with status=GENERATED before file upload, updated to TRANSMITTED on SFTP success. ZP0022 inbound: created with status=RECEIVED on file fetch, updated to RECONCILED after line-by-line match, or ERROR on parse failure. Use Spring Data JDBC (@Repository) -- no JPA for this performance-sensitive batch path.
**Steps:** Create com/gme/pay/settlement/repository/RefundBatchRepository.java as Spring Data JDBC @Repository; Implement Long createOutboundBatch(LocalDate settlementDate, int recordCount, BigDecimal totalAmount, String fileChecksum): inserts row with file_type='ZP0021', direction='GME_TO_ZP', scheme_id='ZEROPAY', status='GENERATED'; returns generated id; Implement void markTransmitted(Long batchId, Instant transmittedAt): UPDATE status='TRANSMITTED', transmitted_at=:transmittedAt WHERE id=:batchId; assert 1 row updated; Implement Long createInboundBatch(LocalDate settlementDate, String fileChecksum): inserts ZP0022, ZP_TO_GME, RECEIVED; Implement void markReconciled(Long batchId, Instant reconciledAt): idempotent (UPDATE only if status != RECONCILED); Implement void markError(Long batchId, String errorDetail): UPDATE status='ERROR', error_detail=:errorDetail
**Deliverable:** services/settlement-reconciliation/src/main/java/com/gme/pay/settlement/repository/RefundBatchRepository.java
**Acceptance / logic checks:**
- createOutboundBatch inserts a row with file_type='ZP0021', direction='GME_TO_ZP', status='GENERATED' (verified via Testcontainers PostgreSQL 16)
- markTransmitted with a non-existent batchId throws IncorrectUpdateSemanticDataAccessException or equivalent (not silent)
- createInboundBatch inserts a row with file_type='ZP0022', direction='ZP_TO_GME', status='RECEIVED'
- markReconciled called twice for the same batchId is idempotent: reconciled_at is not overwritten on second call
- file_checksum stored on the row matches the 64-char hex SHA-256 passed in
**Depends on:** 9.5-T01

### 9.5-T07 — Implement ZP0022Parser: parse inbound ZP0022 fixed-width refund registration result file  _(45 min)_
**Context:** Module: services/settlement-reconciliation. ZP0022 (ZeroPay -> GME PPF, ~05:00 KST) is a fixed-width file mirroring ZP0012 structure. Header: file_type CHAR(6) must equal 'ZP0022'; business_date DATE(8); total_record_count NUM(6). Detail record: original_zeropay_txn_ref CHAR(20) (match key together with refund_date from ZP0021); gme_refund_id CHAR(20); result_code CHAR(2) (00=Success, any non-zero=failure; do NOT coerce to boolean); result_message right-padded to 100; registered_refund_amount NUM(12) BigDecimal; adjustment_settlement_date DATE(8). Trailer: total_record_count NUM(6); control_sum NUM(14). Validation rules (reject entire file on failure, throw SchemeBatchException from lib-errors): (1) header file_type != 'ZP0022'; (2) parsed record count != trailer total_record_count; (3) SUM(registered_refund_amount) != trailer control_sum. SchemeBatchException must carry fileType='ZP0022' and a descriptive message.
**Steps:** Create com/gme/pay/settlement/zeropay/batch/ZP0022Parser.java as @Component; Create ZP0022ParseResult.java record: ZP0021Header-like header, List<ZP0022Record> records; Implement ZP0022ParseResult parse(byte[] fileContent): decode UTF-8, split by CRLF; Validate header line: fileType substring == 'ZP0022'; throw SchemeBatchException if wrong; Parse each detail line by fixed-width offsets matching field widths in context into ZP0022Record; After all records parsed: compare count vs trailer total_record_count; compare SUM vs trailer control_sum; throw SchemeBatchException with message 'record count mismatch' or 'control sum mismatch' as applicable
**Deliverable:** services/settlement-reconciliation/src/main/java/com/gme/pay/settlement/zeropay/batch/ZP0022Parser.java and ZP0022ParseResult.java
**Acceptance / logic checks:**
- Well-formed file with 2 records returns ZP0022ParseResult with exactly 2 ZP0022Record objects with correct field values
- Trailer total_record_count=3 but only 2 detail records throws SchemeBatchException with message containing 'record count'
- control_sum=99999 but SUM(registered_refund_amount)=50000 throws SchemeBatchException with message containing 'control sum'
- Header file_type='ZP0021' throws SchemeBatchException before any detail record is parsed
- result_code='03' on a record is returned as the raw string '03' with no coercion
**Depends on:** 9.5-T02

### 9.5-T08 — Implement ZP0021BatchJob: Spring @Scheduled cron job to generate and transmit ZP0021  _(55 min)_
**Context:** Module: services/settlement-reconciliation. The batch scheduler runs ZP0021BatchJob at 02:00 KST daily (@Scheduled cron='0 0 2 * * *', zone='Asia/Seoul'). Flow: (1) compute priorBusinessDay = LocalDate.now(ZoneId.of('Asia/Seoul')).minusDays(1); (2) idempotency guard -- if settlement_batch row with file_type='ZP0021', settlement_date=priorBusinessDay, status IN ('GENERATED','TRANSMITTED') already exists, log WARN and return; (3) call RefundBatchQueryService.findPendingForDate; if empty list, log INFO and return without creating batch row; (4) call RefundBatchRepository.createOutboundBatch to get batchId; (5) call ZP0021FileBuilder.buildForDate(priorBusinessDay, batchId) -- this also marks refunds REFUND_SUBMITTED; (6) call ZP0021Serializer.serialize; (7) upload via ZeroPaySftpGateway.uploadOutbound(filename, bytes) where filename = 'ZP0021_'+institutionCode+'_'+date.format(YYYYMMDD)+'.dat'; (8) on success call RefundBatchRepository.markTransmitted; (9) on SchemeConnectivityException call markError + send Ops alert via OpsAlertPublisher.
**Steps:** Create com/gme/pay/settlement/batch/ZP0021BatchJob.java as @Component; Annotate with @Scheduled(cron='0 0 2 * * *', zone='Asia/Seoul'); ensure @EnableScheduling on application @Configuration; Inject RefundBatchQueryService, ZP0021FileBuilder, ZP0021Serializer, RefundBatchRepository, ZeroPaySftpGateway, OpsAlertPublisher, SchemeConfigRepository (for institutionCode); Implement idempotency guard using RefundBatchRepository.findByFileTypeAndDate(ZP0021, priorBusinessDay); Wrap SFTP upload in try-catch SchemeConnectivityException; propagate other runtime exceptions; Log structured JSON at INFO: job_name=ZP0021BatchJob, settlement_date, record_count, total_amount_krw on completion
**Deliverable:** services/settlement-reconciliation/src/main/java/com/gme/pay/settlement/batch/ZP0021BatchJob.java
**Acceptance / logic checks:**
- Existing TRANSMITTED batch for priorBusinessDay causes job to log WARN and return without calling SFTP or creating new batch row
- findPendingForDate returning empty list causes job to return without creating any settlement_batch row or calling SFTP
- SchemeConnectivityException on SFTP upload results in settlement_batch.status=ERROR and OpsAlertPublisher.publishBatchError called
- Cron expression '0 0 2 * * *' with zone 'Asia/Seoul' confirmed to fire at 02:00:00 KST by CronExpression.parse unit assertion
- After normal run all included refunds have status='REFUND_SUBMITTED' and zp_batch_id set to the new batch id
**Depends on:** 9.5-T04, 9.5-T05, 9.5-T06

### 9.5-T09 — Implement ZP0022InboundProcessor: fetch ZP0022 from SFTP, parse, reconcile, and mark batch  _(50 min)_
**Context:** Module: services/settlement-reconciliation. ZP0022InboundProcessor.processForDate(LocalDate kstDate) is called by the morning cron at ~05:00 KST. Flow: (1) idempotency guard -- if settlement_batch with file_type='ZP0022', settlement_date=kstDate, status NOT IN ('ERROR') already exists, log WARN and return; (2) call ZeroPaySftpGateway.fetchInbound(ZP0022, kstDate) -> Optional<byte[]>; if empty, log WARN 'ZP0022 not yet available for kstDate' and return (no batch row yet; retry on next poll); (3) create inbound batch row via RefundBatchRepository.createInboundBatch; (4) call ZP0022Parser.parse(bytes); on SchemeBatchException call markError(batchId, message) and send Ops alert, rethrow; (5) call ZP0022ReconciliationService.reconcile(batchId, parseResult.records(), kstDate); (6) call markReconciled(batchId, Instant.now()). Entire process is @Transactional except the SFTP fetch.
**Steps:** Create com/gme/pay/settlement/batch/ZP0022InboundProcessor.java as @Service; Implement processForDate(LocalDate kstDate): public entry point; Add idempotency check via RefundBatchRepository.findByFileTypeAndDate('ZP0022', kstDate); Call ZeroPaySftpGateway.fetchInbound; handle Optional.empty() as described; Create batch row, parse, reconcile, mark RECONCILED in sequence; Wrap parse and reconcile in try-catch; on any exception call markError + OpsAlertPublisher.publishBatchError(ZP0022, kstDate, message)
**Deliverable:** services/settlement-reconciliation/src/main/java/com/gme/pay/settlement/batch/ZP0022InboundProcessor.java
**Acceptance / logic checks:**
- processForDate when RECONCILED batch already exists logs WARN and makes no DB changes or SFTP calls
- processForDate when SFTP returns Optional.empty() logs WARN and returns without creating batch row
- SchemeBatchException from parse sets batch status=ERROR and triggers OpsAlertPublisher call; reconciliation is not attempted
- processForDate on valid file creates exactly one inbound batch row and transitions it to RECONCILED
- Two concurrent calls to processForDate for the same date result in exactly one batch row (idempotency guard prevents second insert)
**Depends on:** 9.5-T06, 9.5-T07

### 9.5-T10 — Implement ZP0022ReconciliationService: line-by-line ZP0022 vs ZP0021 match with all five mismatch branches  _(55 min)_
**Context:** Module: services/settlement-reconciliation. After ZP0022 is parsed, each ZP0022Record is matched against REFUND_SUBMITTED refunds in PostgreSQL using match key (original_zeropay_txn_ref, refund_date). Five outcomes from SCH-06 §6.4 (same logic as ZP0012/ZP0011 for payments): (1) result_code='00' and registered_refund_amount = refund.refund_amount_krw -> mark refund status='REFUND_CONFIRMED', store zp_result_code='00', store adjustment_settlement_date; (2) result_code non-zero -> mark refund status='REFUND_FAILED', store zp_result_code, insert reconciliation_item (match_status='DISCREPANCY', resolution_status='UNRESOLVED'); (3) REFUND_SUBMITTED refund with no matching ZP0022Record -> insert reconciliation_item (match_status='MISSING_SCHEME'); (4) ZP0022Record not matching any GME refund -> insert reconciliation_item (match_status='MISSING_GME'); (5) result_code='00' but registered_refund_amount != refund.refund_amount_krw (zero tolerance per SCH-06 §9.1) -> insert reconciliation_item (match_status='DISCREPANCY', sub-type REGISTRATION_AMOUNT_MISMATCH). reconciliation_item table fields: id, batch_id, txn_ref (gme_refund_id), scheme_ref (original_zeropay_txn_ref), match_status, gme_amount NUMERIC, scheme_amount NUMERIC, discrepancy_amount NUMERIC, ccy='KRW', resolution_status, resolved_by, resolved_at, resolution_note.
**Steps:** Create com/gme/pay/settlement/zeropay/batch/ZP0022ReconciliationService.java as @Service; Implement @Transactional void reconcile(Long inboundBatchId, List<ZP0022Record> zp0022Records, LocalDate settlementDate); Load all REFUND_SUBMITTED refunds linked to the ZP0021 batch for settlementDate into a Map<String, Refund> keyed by original_zeropay_txn_ref; For each ZP0022Record: look up in map; apply branch logic from context; update refund fields via RefundRepository; insert reconciliation_item rows via ReconciliationItemRepository if applicable; After processing ZP0022Records: for each remaining (unmatched) REFUND_SUBMITTED refund in the map, insert reconciliation_item with match_status='MISSING_SCHEME'; Inject PrefundingReversalService but do NOT call it here -- REFUND_CONFIRMED -> REFUNDED wiring is in a separate ticket (9.5-T12)
**Deliverable:** services/settlement-reconciliation/src/main/java/com/gme/pay/settlement/zeropay/batch/ZP0022ReconciliationService.java
**Acceptance / logic checks:**
- ZP0022Record result_code='00' with matching amount -> refund.status='REFUND_CONFIRMED' and zp_result_code='00' and adjustment_settlement_date stored; no reconciliation_item
- ZP0022Record result_code='03' -> refund.status='REFUND_FAILED' and one reconciliation_item with match_status='DISCREPANCY' and resolution_status='UNRESOLVED'
- REFUND_SUBMITTED refund absent from ZP0022 -> reconciliation_item with match_status='MISSING_SCHEME' inserted
- ZP0022Record with original_zeropay_txn_ref not matching any GME refund -> reconciliation_item with match_status='MISSING_GME'
- ZP0022Record result_code='00' but registered_refund_amount=49999 vs submitted 50000 -> reconciliation_item match_status='DISCREPANCY' inserted and refund.status is NOT REFUND_CONFIRMED
**Depends on:** 9.5-T07, 9.5-T06, 9.5-T03

### 9.5-T11 — Implement PrefundingReversalService: credit OVERSEAS prefunding balance on REFUND_CONFIRMED  _(50 min)_
**Context:** Module: services/prefunding-balance. For OVERSEAS partners (partner.type='OVERSEAS'), when a refund is confirmed by ZeroPay (status='REFUND_CONFIRMED'), the partner's prepaid USD balance must be credited (reversed) by transaction.prefunding_deducted_usd (the USD amount debited at original payment time, stored at rate-lock). The credit is atomic: SELECT ... FOR UPDATE on prefunding_account WHERE partner_id=:partnerId; insert prefunding_ledger_entry (entry_type='CREDIT_REVERSAL', amount=transaction.prefunding_deducted_usd, txn_ref=original txn_ref, refund_ref=refund.refund_ref, created_at=now()); increment prefunding_account.balance by prefunding_deducted_usd. For LOCAL partners (partner.type='LOCAL'): no prefunding account exists; skip ledger entry. In both cases: update refund.status from 'REFUND_CONFIRMED' to 'REFUNDED'. Guard: if refund.status != 'REFUND_CONFIRMED' throw IllegalStateException (prevents double-reversal). All operations within @Transactional.
**Steps:** Create com/gme/pay/prefunding/PrefundingReversalService.java as @Service in services/prefunding-balance module; Implement @Transactional void reverseForRefund(Long refundId); Load refund, guard status == 'REFUND_CONFIRMED' else throw IllegalStateException('Expected REFUND_CONFIRMED but was: ' + status); Load transaction, load partner; if partner.type='OVERSEAS': SELECT FOR UPDATE on prefunding_account; insert prefunding_ledger_entry; UPDATE balance = balance + :amount; Update refund.status = 'REFUNDED'; Publish domain event RefundReversedEvent via EventPublisher interface (lib-events Outbox pattern) with refund_ref, partner_id, amount_usd, timestamp
**Deliverable:** services/prefunding-balance/src/main/java/com/gme/pay/prefunding/PrefundingReversalService.java
**Acceptance / logic checks:**
- OVERSEAS partner with prefunding_deducted_usd=36.9714: after reverseForRefund, prefunding_account.balance increases by exactly 36.9714 (BigDecimal equality, no floating-point loss)
- OVERSEAS partner: one prefunding_ledger_entry row inserted with entry_type='CREDIT_REVERSAL' and amount=36.9714
- LOCAL partner: no prefunding_ledger_entry row inserted; refund.status becomes 'REFUNDED'
- Calling reverseForRefund with refund.status='REFUNDED' throws IllegalStateException containing 'REFUNDED'
- Concurrent calls to reverseForRefund for same refundId result in exactly one ledger entry (SELECT FOR UPDATE prevents double credit)
**Depends on:** 9.5-T01

### 9.5-T12 — Wire REFUND_CONFIRMED -> prefunding reversal -> REFUNDED within ZP0022ReconciliationService  _(40 min)_
**Context:** Module: services/settlement-reconciliation. After ZP0022ReconciliationService marks a refund 'REFUND_CONFIRMED' (result_code='00', amounts match), PrefundingReversalService.reverseForRefund must be called immediately. Both operations must be within the same @Transactional boundary so a reversal failure rolls back the REFUND_CONFIRMED update and leaves the refund in 'REFUND_SUBMITTED' for retry. If PrefundingReversalService throws RuntimeException, catch it, re-throw to trigger rollback. ZP0022InboundProcessor (the caller) does not need to know about prefunding internals. The REFUND_FAILED path must NOT call reverseForRefund. LOCAL partners: reverseForRefund is a no-op for prefunding but still transitions status to REFUNDED, so call it for both partner types.
**Steps:** Inject PrefundingReversalService into ZP0022ReconciliationService (already wired in 9.5-T10 stub); In the result_code='00' + amounts-match branch, after updating refund.status='REFUND_CONFIRMED', immediately call prefundingReversalService.reverseForRefund(refund.id); PrefundingReversalService.reverseForRefund will internally transition REFUND_CONFIRMED -> REFUNDED -- the reconciliation service should not also set REFUNDED; Wrap the reverseForRefund call in a try-catch RuntimeException; re-throw to ensure @Transactional rollback propagates; Add integration test asserting atomicity: stub PrefundingReversalService to throw; verify refund remains REFUND_SUBMITTED
**Deliverable:** Updated ZP0022ReconciliationService.java with PrefundingReversalService injection and atomic call in the success branch
**Acceptance / logic checks:**
- Processing successful ZP0022Record for OVERSEAS partner results in refund.status='REFUNDED' (not REFUND_CONFIRMED) after reconcile() returns
- Processing successful ZP0022Record for LOCAL partner results in refund.status='REFUNDED' and no prefunding_ledger_entry row
- PrefundingReversalService throwing RuntimeException causes full rollback; refund remains 'REFUND_SUBMITTED' after the exception
- Processing REFUND_FAILED branch (result_code non-zero) does not call reverseForRefund
- reconcile() calls reverseForRefund exactly once per successfully confirmed record
**Depends on:** 9.5-T10, 9.5-T11

### 9.5-T13 — Implement ReconciliationItemRepository: insert and query reconciliation exception items  _(30 min)_
**Context:** Module: services/settlement-reconciliation. The reconciliation_item table (PostgreSQL 16, DAT-03 §7.3) stores batch exception records. Columns: id BIGINT PK, batch_id BIGINT FK -> settlement_batch(id), txn_ref VARCHAR (gme_refund_id for refund exceptions), scheme_ref VARCHAR (original_zeropay_txn_ref), match_status VARCHAR (MATCHED/DISCREPANCY/MISSING_GME/MISSING_SCHEME), gme_amount NUMERIC(20,4), scheme_amount NUMERIC(20,4), discrepancy_amount NUMERIC(20,4) = ABS(gme_amount - scheme_amount), ccy CHAR(3), resolution_status VARCHAR (UNRESOLVED/RESOLVED/ESCALATED), resolved_by VARCHAR, resolved_at TIMESTAMPTZ, resolution_note TEXT, created_at TIMESTAMPTZ DEFAULT now(). Use Spring Data JDBC. ReconciliationItem is a Java record.
**Steps:** Create com/gme/pay/settlement/repository/ReconciliationItemRepository.java as Spring Data JDBC @Repository; Create ReconciliationItem.java record with all table columns; Implement void insertItem(ReconciliationItem item): inserts row; Implement List<ReconciliationItem> findUnresolvedForBatch(Long batchId): returns all rows with batch_id=:batchId and resolution_status='UNRESOLVED'; Implement void markResolved(Long itemId, String resolvedBy, Instant resolvedAt, String resolutionNote): UPDATE resolution_status='RESOLVED' etc.; Compute discrepancy_amount in insertItem as ABS(item.gmeAmount().subtract(item.schemeAmount()))
**Deliverable:** services/settlement-reconciliation/src/main/java/com/gme/pay/settlement/repository/ReconciliationItemRepository.java and ReconciliationItem.java
**Acceptance / logic checks:**
- insertItem for a DISCREPANCY item with gme_amount=50000 and scheme_amount=49999 stores discrepancy_amount=1
- findUnresolvedForBatch returns only UNRESOLVED items for the given batch_id
- markResolved sets resolution_status='RESOLVED' and resolved_by and resolved_at on the target row
- insertItem with match_status='MISSING_GME' inserts row with txn_ref=NULL (no GME record to link)
- Calling insertItem twice with same batch_id + scheme_ref does not throw (upsert or allow duplicate -- reconciliation may process same file twice)
**Depends on:** 9.5-T01

### 9.5-T14 — Implement OpsAlertPublisher: send reconciliation failure alerts via Outbox domain event  _(40 min)_
**Context:** Module: services/settlement-reconciliation (publishes); services/notification-webhook (consumes). When ZP0022 reconciliation has UNRESOLVED items or a batch job errors, OpsAlertPublisher emits a domain event to the transactional Outbox table (lib-events pattern). Event type: BatchReconciliationAlert with fields: alert_type (REFUND_REGISTRATION_FAILURE/BATCH_JOB_ERROR/INBOUND_PROCESSOR_FAILED), scheme_id='ZEROPAY', file_type (ZP0021/ZP0022), settlement_date, unresolved_count (int), error_detail (String), created_at. The Outbox row is inserted in the same @Transactional scope as the reconciliation update so the alert is never lost. The EventPublisher interface from lib-events is used (in-process/outbox-polling impl for Phase 1; Kafka wired later without code changes). SCH-06 §9.5: ZP0021/ZP0022 outbound cutoff is 02:00 KST; inbound wait cutoff is 06:00 KST; exceeding these triggers P1 Ops notification.
**Steps:** Create com/gme/pay/settlement/alert/OpsAlertPublisher.java as @Service in services/settlement-reconciliation; Inject EventPublisher from lib-events; Implement void publishBatchError(String fileType, LocalDate settlementDate, String errorDetail): build BatchReconciliationAlert event, call eventPublisher.publish(event); Implement void publishRefundReconciliationExceptions(Long batchId, LocalDate settlementDate, int unresolvedCount): build BatchReconciliationAlert with alert_type='REFUND_REGISTRATION_FAILURE'; In ZP0022ReconciliationService.reconcile(), after processing all records, call publishRefundReconciliationExceptions if unresolvedCount > 0; Outbox insert must be within the same @Transactional scope as refund status updates
**Deliverable:** services/settlement-reconciliation/src/main/java/com/gme/pay/settlement/alert/OpsAlertPublisher.java wired to lib-events EventPublisher Outbox
**Acceptance / logic checks:**
- publishRefundReconciliationExceptions with unresolvedCount=3 inserts one Outbox row of type BatchReconciliationAlert within the same transaction
- publishRefundReconciliationExceptions with unresolvedCount=0 does NOT insert an Outbox row
- publishBatchError inserts Outbox row with alert_type='BATCH_JOB_ERROR' and correct file_type and error_detail
- If the enclosing @Transactional is rolled back, the Outbox row is also rolled back (same transaction scope)
- The BatchReconciliationAlert event schema is defined in lib-events/src/main/avro/ or equivalent JSON schema resource
**Depends on:** 9.5-T10, 9.5-T13

### 9.5-T15 — Implement ZeroPaySftpGateway: uploadOutbound and fetchInbound for ZP0021/ZP0022  _(55 min)_
**Context:** Module: services/scheme-adapter-zeropay. The SFTP Gateway service handles all ZeroPay SFTP I/O. File naming (SCH-06 §2.3.3): {FILE_ID}_{YYYYMMDD}_{SEQUENCE}.dat (e.g. ZP0021_20261015_01.dat). Remote directory structure (SCH-06 §2.3.4): outbound files go to SchemeConfig.outbound_dir; inbound files are fetched from SchemeConfig.inbound_dir. SFTP authentication uses the RSA private key from Vault/KMS (schemeConfig.sftp_private_key_ref). Transfer process from SCH-06 §2.4: upload -> verify remote checksum/size; retry up to 3 times with exponential back-off (30s, 2min, 10min) before throwing SchemeConnectivityException. fetchInbound: SFTP list inbound_dir for filename matching fileType+businessDate pattern; if absent return Optional.empty(); if present download bytes; PGP decrypt using GME private key (pgp_private_key_ref from Vault). Use JSch or Apache MINA SSHD for SFTP.
**Steps:** In services/scheme-adapter-zeropay create com/gme/pay/scheme/zeropay/sftp/ZeroPaySftpGateway.java as @Service; Implement byte[] uploadOutbound(String fileType, LocalDate businessDate, byte[] content, SchemeConfig config): generates filename, PGP-encrypts with schemeConfig.pgp_public_key_ref, SFTPs to outbound_dir, verifies transfer; Implement Optional<byte[]> fetchInbound(String fileType, LocalDate businessDate, SchemeConfig config): list inbound_dir, if absent return Optional.empty(), download, PGP-decrypt, return bytes; Add retry logic: 3 attempts with 30s/2min/10min intervals; throw SchemeConnectivityException after all retries exhausted; Retrieve private keys from Vault using VaultTemplate injected from Spring Vault (spring-vault-core dependency); Log transfer metadata at INFO: filename, bytes, SHA-256 checksum, timestamp on each successful transfer
**Deliverable:** services/scheme-adapter-zeropay/src/main/java/com/gme/pay/scheme/zeropay/sftp/ZeroPaySftpGateway.java
**Acceptance / logic checks:**
- uploadOutbound with a valid file succeeds and returns normally with no exception (verified by WireMock SFTP stub or embedded SFTP server in test)
- uploadOutbound when all 3 retry attempts fail throws SchemeConnectivityException
- fetchInbound when file is not in inbound_dir returns Optional.empty() with no exception
- fetchInbound when file is present returns Optional.of(decrypted bytes)
- Filename generated by uploadOutbound follows pattern ZP0021_{YYYYMMDD}_01.dat for the given businessDate
**Depends on:** 9.5-T08

### 9.5-T16 — Wire ZP0022 inbound poll into morning BatchScheduler cron at 05:00 KST  _(30 min)_
**Context:** Module: services/settlement-reconciliation. The BatchScheduler runs multiple cron jobs. ZP0022 poll must run at ~05:00 KST independently from ZP0012 (payment result) so a ZP0022 failure does not block ZP0012 processing. Both ZP0022InboundProcessor.processForDate and ZP0012InboundProcessor.processForDate are called in the same @Scheduled method runMorningInboundBatch() but each in its own try-catch. ZP0022 and ZP0012 each report errors independently. The prior business day in KST is: LocalDate.now(ZoneId.of('Asia/Seoul')).minusDays(1). Spring @EnableScheduling must be on the main application @Configuration. Each processor call is logged at INFO with structured fields: processor, settlement_date, elapsed_ms.
**Steps:** Create or update com/gme/pay/settlement/batch/BatchScheduler.java as @Component in services/settlement-reconciliation; Annotate runMorningInboundBatch() with @Scheduled(cron='0 0 5 * * *', zone='Asia/Seoul'); Within method: in try-catch #1 call ZP0012InboundProcessor.processForDate(priorDay); catch Exception, log ERROR, call OpsAlertPublisher.publishBatchError; In try-catch #2 call ZP0022InboundProcessor.processForDate(priorDay); catch Exception, log ERROR, call OpsAlertPublisher.publishBatchError; Add MDC context: job_name=morningInboundBatch, settlement_date for structured logging (OpenTelemetry-compatible); Ensure @EnableScheduling is on the services/settlement-reconciliation application @SpringBootApplication class
**Deliverable:** services/settlement-reconciliation/src/main/java/com/gme/pay/settlement/batch/BatchScheduler.java with independent try-catch for ZP0012 and ZP0022 processors in the 05:00 KST cron
**Acceptance / logic checks:**
- ZP0022InboundProcessor.processForDate throwing RuntimeException is caught; ZP0012 processing still proceeds (verified by unit test that stubs ZP0022 to throw)
- Cron '0 0 5 * * *' with zone 'Asia/Seoul' fires at 05:00:00 KST (verified by CronExpression.parse assertion)
- OpsAlertPublisher.publishBatchError is called with fileType='ZP0022' when ZP0022 throws
- Structured log on entry to runMorningInboundBatch includes settlement_date field
- ZP0012 failure does not prevent ZP0022 call (symmetric independence)
**Depends on:** 9.5-T09

### 9.5-T17 — Unit tests: ZP0021FileBuilder -- empty batch, normal batch, status filter, currency guard  _(35 min)_
**Context:** Module: services/settlement-reconciliation. Test class: ZP0021FileBuilderTest using JUnit 5 and Mockito. Test vectors: (1) RefundBatchQueryService.findPendingForDate returns empty list -> ZP0021File.records is empty, header.total_record_count=0, header.total_refund_amount_krw=0, RefundRepository.bulkUpdateStatus never called. (2) Three REFUND_PENDING refunds of 10000/20000/30000 KRW, partner_types D/I/I -> header.total_refund_amount_krw=60000, total_record_count=3, all three ZP0021Records have correct partner_type and status_code='R'. (3) Mix: query only returns REFUND_PENDING rows (mock returns 2 of 3 rows) -> only 2 records in file. (4) RefundBatchItem with refund_ccy='USD' -> IllegalArgumentException thrown before any records assembled or statuses updated.
**Steps:** Create services/settlement-reconciliation/src/test/java/com/gme/pay/settlement/zeropay/batch/ZP0021FileBuilderTest.java; Use @ExtendWith(MockitoExtension.class); mock RefundBatchQueryService and RefundRepository; Test vector 1: empty query result; Test vector 2: three refunds, assert header sums and ZP0021Record fields; Test vector 3: two of three returned by query mock, assert only 2 records in output; Test vector 4: one item has refund_ccy='USD', assert IllegalArgumentException and verify RefundRepository.bulkUpdateStatus is never invoked
**Deliverable:** services/settlement-reconciliation/src/test/java/com/gme/pay/settlement/zeropay/batch/ZP0021FileBuilderTest.java with four test scenarios
**Acceptance / logic checks:**
- Vector 1 passes: ZP0021File.records().isEmpty() and header total counts both zero
- Vector 2 passes: header.total_refund_amount_krw = BigDecimal('60000') and exactly 3 ZP0021Records
- Vector 3 passes: only 2 records in result when query returns 2 items
- Vector 4 passes: IllegalArgumentException thrown; verify(refundRepository, never()).bulkUpdateStatus(any())
- All four tests pass under mvn test with no Mockito warnings
**Depends on:** 9.5-T04

### 9.5-T18 — Unit tests: ZP0021Serializer -- field padding, zero-record file, checksum  _(30 min)_
**Context:** Module: services/settlement-reconciliation. Test class: ZP0021SerializerTest using JUnit 5. Test vectors: (1) Single record with gme_refund_id='REF001' (6 chars) -> field substring at offset in serialized bytes equals 'REF001              ' (20 chars, 14 trailing spaces). (2) refund_amount_krw=50000 -> substring '000000050000' (12 chars zero-padded). (3) Zero-record file: serialize ZP0021File with empty records list -> output split by CRLF has exactly 2 non-empty lines (header + trailer). (4) computeChecksum on byte[] {0x41, 0x42} ('AB') -> known SHA-256 = '58bb119c35513a451d24dc20ef0e9031ec85b35bfc919d263e7e5d9868909cb5' (verify with MessageDigest). (5) header total_refund_amount_krw=50000 serialized as '00000000050000' (14 chars). (6) Trailer control_sum for two records of 30000+20000 = '00000000050000'.
**Steps:** Create ZP0021SerializerTest.java in test source; Instantiate ZP0021Serializer directly (no Spring context needed); Build ZP0021File test fixtures using the model classes from 9.5-T02; Assert exact substring values at known byte offsets for vectors 1 and 2; Assert line count for vector 3; Assert computeChecksum result for vector 4 equals the known constant; Assert header and trailer numeric field values for vectors 5 and 6
**Deliverable:** services/settlement-reconciliation/src/test/java/com/gme/pay/settlement/zeropay/batch/ZP0021SerializerTest.java with six assertions
**Acceptance / logic checks:**
- Vector 1: gme_refund_id field in output is exactly 'REF001              ' (20-char right-padded)
- Vector 2: refund_amount_krw=50000 serializes as '000000050000'
- Vector 3: 0-record file output has exactly 2 non-empty lines when split by CRLF
- Vector 4: computeChecksum('AB' bytes) equals known SHA-256 constant (64 lowercase hex chars)
- Vector 5 and 6: header and trailer 14-char numeric fields are '00000000050000' for total=50000
**Depends on:** 9.5-T05

### 9.5-T19 — Unit tests: ZP0022Parser -- success, count mismatch, control sum mismatch, wrong file type, mixed result codes  _(35 min)_
**Context:** Module: services/settlement-reconciliation. Test class: ZP0022ParserTest using JUnit 5. Build a helper buildZP0022Bytes(header, List<ZP0022Record>, trailer) to generate test file bytes. Test vectors: (1) Well-formed 2-record file (result_code='00'/'03') -> parse returns 2 ZP0022Records with correct fields. (2) Trailer total_record_count=3 but 2 detail records -> SchemeBatchException message contains 'record count'. (3) Trailer control_sum=99999 but SUM(registered_refund_amount)=50000 -> SchemeBatchException message contains 'control sum'. (4) Header file_type='ZP0021' -> SchemeBatchException thrown before any detail record parsed. (5) Mixed result codes: first record '00', second record '03' -> both returned as raw strings; no coercion.
**Steps:** Create ZP0022ParserTest.java in test source; Implement private byte[] buildZP0022Bytes(Map header, List<Map> records, Map trailer) producing valid fixed-width UTF-8 file bytes; Vector 1: well-formed file, assert 2 records with expected field values; Vector 2: manipulate trailer count, assert SchemeBatchException; Vector 3: manipulate control_sum, assert SchemeBatchException; Vector 4: wrong file_type in header, assert SchemeBatchException; Vector 5: two records with different result_codes, assert both present and raw
**Deliverable:** services/settlement-reconciliation/src/test/java/com/gme/pay/settlement/zeropay/batch/ZP0022ParserTest.java with five test vectors
**Acceptance / logic checks:**
- Vector 1: ZP0022ParseResult.records().size()==2 and first record result_code=='00'
- Vector 2: SchemeBatchException.getMessage() contains 'record count'
- Vector 3: SchemeBatchException.getMessage() contains 'control sum'
- Vector 4: SchemeBatchException thrown when file_type='ZP0021'
- Vector 5: second record result_code=='03' returned as raw String without coercion
**Depends on:** 9.5-T07

### 9.5-T20 — Unit tests: ZP0022ReconciliationService -- all five mismatch branches  _(45 min)_
**Context:** Module: services/settlement-reconciliation. Test class: ZP0022ReconciliationServiceTest using JUnit 5 and Mockito. Test vectors (exact amounts): (1) ZP0022Record original_zeropay_txn_ref='ZPREF001', result_code='00', registered_refund_amount=50000, matching REFUND_SUBMITTED OVERSEAS refund of 50000 KRW -> refund.status='REFUNDED' (after PrefundingReversalService called), no reconciliation_item. (2) ZP0022Record result_code='03' for 'ZPREF002' -> refund.status='REFUND_FAILED', one reconciliation_item match_status='DISCREPANCY'. (3) REFUND_SUBMITTED refund 'ZPREF003' with no ZP0022Record -> reconciliation_item match_status='MISSING_SCHEME', resolution_status='UNRESOLVED'. (4) ZP0022Record 'ZPREF999' not matching any GME refund -> reconciliation_item match_status='MISSING_GME'. (5) result_code='00' but registered_refund_amount=49999 vs submitted 50000 -> reconciliation_item match_status='DISCREPANCY' and refund NOT REFUNDED.
**Steps:** Create ZP0022ReconciliationServiceTest.java; Mock RefundRepository, ReconciliationItemRepository, PrefundingReversalService; Build test ZP0022Record objects and RefundBatchItem stubs for each vector; Vector 1: assert PrefundingReversalService.reverseForRefund called and refund updated to REFUNDED; Vector 2: assert refund REFUND_FAILED and reconciliation_item with DISCREPANCY; Vector 3: assert reconciliation_item MISSING_SCHEME and resolution_status UNRESOLVED; Vector 4: assert reconciliation_item MISSING_GME; Vector 5: assert reconciliation_item DISCREPANCY and verify reverseForRefund NOT called
**Deliverable:** services/settlement-reconciliation/src/test/java/com/gme/pay/settlement/zeropay/batch/ZP0022ReconciliationServiceTest.java with five test vectors
**Acceptance / logic checks:**
- Vector 1: verify(prefundingReversalService).reverseForRefund(refund.id) called exactly once and refund.status captured as REFUNDED
- Vector 2: refund.status captured as REFUND_FAILED; one ReconciliationItem with match_status=DISCREPANCY inserted
- Vector 3: one ReconciliationItem with match_status=MISSING_SCHEME and resolution_status=UNRESOLVED inserted
- Vector 4: one ReconciliationItem with match_status=MISSING_GME inserted; no refund update
- Vector 5: verifyNoInteractions(prefundingReversalService) for this vector; one ReconciliationItem with DISCREPANCY inserted
**Depends on:** 9.5-T10, 9.5-T12

### 9.5-T21 — Unit tests: PrefundingReversalService -- OVERSEAS credit, LOCAL no-op, double-call guard  _(35 min)_
**Context:** Module: services/prefunding-balance. Test class: PrefundingReversalServiceTest using JUnit 5 and Mockito. Test vectors: (1) OVERSEAS partner, refund.status='REFUND_CONFIRMED', transaction.prefunding_deducted_usd=36.9714 BigDecimal -> after call: prefunding_account.balance += 36.9714 (SELECT FOR UPDATE confirmed by verify on PrefundingAccountRepository), one prefunding_ledger_entry inserted with entry_type='CREDIT_REVERSAL', refund.status='REFUNDED'. (2) LOCAL partner, refund.status='REFUND_CONFIRMED' -> refund.status='REFUNDED', PrefundingLedgerRepository.insert never called. (3) refund.status='REFUNDED' -> IllegalStateException message contains 'REFUNDED'. (4) refund.status='REFUND_PENDING' -> IllegalStateException thrown.
**Steps:** Create PrefundingReversalServiceTest.java in services/prefunding-balance test source; Mock RefundRepository, TransactionRepository, PartnerRepository, PrefundingAccountRepository, PrefundingLedgerRepository; Stub loadForUpdate on PrefundingAccountRepository to return account with current balance; Vector 1: OVERSEAS, assert balance delta = BigDecimal('36.9714') and CREDIT_REVERSAL ledger entry; Vector 2: LOCAL, assert PrefundingLedgerRepository.insert never invoked; Vector 3 and 4: wrong initial status, assert IllegalStateException
**Deliverable:** services/prefunding-balance/src/test/java/com/gme/pay/prefunding/PrefundingReversalServiceTest.java with four test vectors
**Acceptance / logic checks:**
- Vector 1: balance increment equals BigDecimal('36.9714') with exact scale preserved
- Vector 2: verify(prefundingLedgerRepository, never()).insert(any()) for LOCAL partner
- Vector 3: IllegalStateException message contains 'REFUNDED'
- Vector 4: IllegalStateException thrown for REFUND_PENDING status
- Vector 1: one CREDIT_REVERSAL ledger entry inserted with amount=36.9714 and refund_ref set
**Depends on:** 9.5-T11

### 9.5-T22 — Unit tests: ZP0021BatchJob -- idempotency, empty-batch skip, SFTP error, normal run  _(40 min)_
**Context:** Module: services/settlement-reconciliation. Test class: ZP0021BatchJobTest using JUnit 5 and Mockito. Test vectors: (1) No REFUND_PENDING refunds -> no settlement_batch row created, no SFTP call. (2) Normal run with 2 refunds -> createOutboundBatch called once, uploadOutbound called once, markTransmitted called once with the batch id. (3) Existing TRANSMITTED batch for priorBusinessDay -> idempotency guard triggers, createOutboundBatch NOT called, SFTP NOT called. (4) SFTP uploadOutbound throws SchemeConnectivityException -> markError called with exception message, OpsAlertPublisher.publishBatchError called, markTransmitted NOT called. (5) ZP0021FileBuilder.buildForDate throws RuntimeException -> exception propagates, no markTransmitted call.
**Steps:** Create ZP0021BatchJobTest.java; mock all injected dependencies; Stub RefundBatchRepository.findByFileTypeAndDate to return empty Optional (vectors 1,2,5) or present batch (vector 3); Vector 1: stub findPendingForDate to return empty list, assert createOutboundBatch never called; Vector 2: stub findPendingForDate with 2 items, verify full success path; Vector 3: stub findByFileTypeAndDate to return existing TRANSMITTED batch, assert createOutboundBatch and uploadOutbound never called; Vector 4: stub uploadOutbound to throw SchemeConnectivityException, assert markError and publishBatchError called, markTransmitted not called; Vector 5: stub buildForDate to throw RuntimeException, assert exception propagates
**Deliverable:** services/settlement-reconciliation/src/test/java/com/gme/pay/settlement/batch/ZP0021BatchJobTest.java with five test vectors
**Acceptance / logic checks:**
- Vector 1: verify(refundBatchRepository, never()).createOutboundBatch(any(), anyInt(), any(), any())
- Vector 2: verify(sftpGateway).uploadOutbound(contains('ZP0021'), any(), any(), any()) called once
- Vector 3: when existing TRANSMITTED batch found, neither createOutboundBatch nor uploadOutbound is called
- Vector 4: verify(refundBatchRepository).markError(any(), contains('SchemeConnectivityException')) and verify(opsAlertPublisher).publishBatchError(eq('ZP0021'), any(), any())
- Vector 5: RuntimeException propagates and markTransmitted is never called
**Depends on:** 9.5-T08

### 9.5-T23 — Testcontainers integration test: ZP0021 generation -> ZP0022 processing happy-path round-trip  _(60 min)_
**Context:** Module: services/settlement-reconciliation (integration test). Use @SpringBootTest with Testcontainers PostgreSQL 16. Seed: OVERSEAS partner (prefunding_account.balance=1000.00 USD, original payment with prefunding_deducted_usd=36.9714), LOCAL partner; two refund rows REFUND_PENDING for 2026-06-04 (OVERSEAS refund_amount_krw=50000, LOCAL refund_amount_krw=15000). Stub ZeroPaySftpGateway via WireMock or @MockBean. Step 1: invoke ZP0021BatchJob (or ZP0021FileBuilder.buildForDate directly) for 2026-06-04; assert settlement_batch created, both refunds REFUND_SUBMITTED. Step 2: build synthetic ZP0022 bytes using ZP0021Serializer helper for both records with result_code='00' and matching amounts. Step 3: stub SFTP fetchInbound to return synthetic bytes; invoke ZP0022InboundProcessor.processForDate(2026-06-04). Assert: both refunds REFUNDED; OVERSEAS prefunding balance = 1000.00 + 36.9714 = 1036.9714; one CREDIT_REVERSAL ledger entry; ZP0022 batch RECONCILED; zero reconciliation_item rows.
**Steps:** Create RefundBatchRoundTripIntegrationTest.java in src/test; annotate @SpringBootTest, @Testcontainers; Define @Container PostgreSQLContainer<> postgres = new PostgreSQLContainer<>('postgres:16'); Seed DB in @BeforeEach using JdbcTemplate; @MockBean ZeroPaySftpGateway; stub uploadOutbound to succeed, stub fetchInbound for ZP0022 to return synthetic bytes; Execute step 1 (ZP0021 build) and step 2 (build synthetic ZP0022 bytes using ZP0021Serializer) and step 3 (ZP0022InboundProcessor.processForDate); Assert all state post-conditions from context using JdbcTemplate queries
**Deliverable:** services/settlement-reconciliation/src/test/java/com/gme/pay/settlement/integration/RefundBatchRoundTripIntegrationTest.java
**Acceptance / logic checks:**
- Both refund rows have status='REFUNDED' in DB after test
- OVERSEAS prefunding_account.balance = 1036.9714 (seed 1000.00 + reversal 36.9714) exactly
- Exactly one prefunding_ledger_entry with entry_type='CREDIT_REVERSAL' and amount=36.9714
- settlement_batch for ZP0022 has status='RECONCILED'
- Zero reconciliation_item rows exist (no mismatches in happy-path)
**Depends on:** 9.5-T08, 9.5-T09, 9.5-T10, 9.5-T11, 9.5-T12

### 9.5-T24 — Testcontainers integration test: ZP0022 mixed-outcome mismatch and exception record creation  _(55 min)_
**Context:** Module: services/settlement-reconciliation (integration test). Use @SpringBootTest with Testcontainers PostgreSQL 16. Seed 3 REFUND_SUBMITTED refunds for 2026-06-04 (already linked to a ZP0021 batch): ZPREF001 OVERSEAS 50000 KRW; ZPREF002 OVERSEAS 20000 KRW; ZPREF003 LOCAL 15000 KRW. Build synthetic ZP0022 with: ZPREF001 result_code='00' registered_refund_amount=50000 (success); ZPREF002 result_code='03' (failure); ZPREF003 absent from ZP0022 (MISSING_SCHEME); bonus record ZPREF999 (MISSING_GME). After ZP0022InboundProcessor.processForDate(2026-06-04): ZPREF001 REFUNDED + prefunding reversed; ZPREF002 REFUND_FAILED; ZPREF003 has reconciliation_item MISSING_SCHEME; ZPREF999 has reconciliation_item MISSING_GME; OpsAlertPublisher called with unresolvedCount >= 3.
**Steps:** Create RefundBatchMismatchIntegrationTest.java; annotate @SpringBootTest, @Testcontainers; Seed: 3 refunds REFUND_SUBMITTED with correct zp_batch_id; OVERSEAS partner prefunding_account.balance=500.00; Build synthetic ZP0022 bytes for the mixed set from context; @MockBean ZeroPaySftpGateway; stub fetchInbound to return mixed ZP0022 bytes; Invoke ZP0022InboundProcessor.processForDate(2026-06-04); Assert each refund status, reconciliation_item rows, and OpsAlertPublisher calls via @MockBean verify
**Deliverable:** services/settlement-reconciliation/src/test/java/com/gme/pay/settlement/integration/RefundBatchMismatchIntegrationTest.java
**Acceptance / logic checks:**
- ZPREF001 refund status='REFUNDED' and prefunding balance increased by prefunding_deducted_usd
- ZPREF002 refund status='REFUND_FAILED' and one reconciliation_item with match_status='DISCREPANCY'
- reconciliation_item with match_status='MISSING_SCHEME' exists for ZPREF003
- reconciliation_item with match_status='MISSING_GME' exists for ZPREF999
- OpsAlertPublisher.publishRefundReconciliationExceptions called with unresolvedCount >= 3
**Depends on:** 9.5-T10, 9.5-T12, 9.5-T13, 9.5-T14, 9.5-T23

### 9.5-T25 — Admin API: GET /internal/v1/refund-batches returns daily refund batch summary  _(45 min)_
**Context:** Module: services/ops-partner-bff. PRD-07 §10.3.5 specifies a Refund Batch View showing: batch date, ZP0021 file transmission status and timestamp, ZP0022 result receipt status, per-refund status breakdown, total count, total KRW. Endpoint: GET /internal/v1/refund-batches?settlementDate=YYYY-MM-DD. Response DTO (RefundBatchSummaryResponse): settlement_date, zp0021_batch_id (Long nullable), zp0021_status (String), zp0021_transmitted_at (OffsetDateTime nullable), zp0022_batch_id (Long nullable), zp0022_status (String), zp0022_reconciled_at (OffsetDateTime nullable), total_refund_count (int), total_refund_amount_krw (BigDecimal), refunds: List of {refund_ref, refund_amount_krw (BigDecimal), status, zp_result_code (String nullable)}. Security: @PreAuthorize('hasAnyRole(GME_OPS, GME_FINANCE)') via Spring Security OAuth2+RBAC. Returns 200 for dates with no batches (empty response with nulls); not 404.
**Steps:** Create com/gme/pay/bff/ops/api/RefundBatchController.java as @RestController in services/ops-partner-bff; Implement @GetMapping('/internal/v1/refund-batches') with @RequestParam @DateTimeFormat LocalDate settlementDate; Inject RefundBatchQueryFacade (new @Service that queries settlement_batch and refund via JDBC or Feign to settlement-reconciliation service); Apply @PreAuthorize('hasAnyRole(ROLE_GME_OPS, ROLE_GME_FINANCE)'); Map query results to RefundBatchSummaryResponse DTO (in lib-api-contracts or local DTO package); Return 200 with response; if no batches found return response with null batch ids and empty refunds list
**Deliverable:** services/ops-partner-bff/src/main/java/com/gme/pay/bff/ops/api/RefundBatchController.java and RefundBatchSummaryResponse.java
**Acceptance / logic checks:**
- GET /internal/v1/refund-batches?settlementDate=2026-06-04 returns 200 with correct zp0021_status and zp0022_status
- Request with GME_PARTNER role (not GME_OPS or GME_FINANCE) returns 403
- Response for a date with no batches returns 200 with empty refunds list and null batch ids (not 404)
- zp0022_reconciled_at is non-null when ZP0022 batch status=RECONCILED
- Response refunds array contains one entry per refund with correct status and zp_result_code
**Depends on:** 9.5-T06, 9.5-T10

### 9.5-T26 — Admin API: GET and POST exception queue endpoints for ZP0022 reconciliation failures  _(45 min)_
**Context:** Module: services/ops-partner-bff. Ops must be able to view and resolve UNRESOLVED reconciliation_item rows for ZP0022 refund registration failures. GET /internal/v1/exceptions?type=REFUND_RECONCILIATION&status=UNRESOLVED returns List<ExceptionItemResponse>: {id, batch_date, scheme_ref (original_zeropay_txn_ref), txn_ref (gme_refund_id), match_status, gme_amount NUMERIC, scheme_amount NUMERIC, discrepancy_amount NUMERIC, ccy, resolution_status}. Filters: join reconciliation_item -> settlement_batch WHERE settlement_batch.file_type='ZP0022' AND resolution_status='UNRESOLVED'. POST /internal/v1/exceptions/{id}/resolve body {resolution_note: String}: update resolution_status='RESOLVED', resolved_by=authenticated operator email, resolved_at=now(), resolution_note; return 200. Both endpoints require GME_OPS role (not GME_FINANCE -- this is an ops action). Audit log on resolve: actor, timestamp, exception item id, resolution_note (via Outbox domain event or direct audit table insert).
**Steps:** Create or extend ExceptionQueueController.java in services/ops-partner-bff; Add @GetMapping('/internal/v1/exceptions') with @RequestParam type and status; filter file_type='ZP0022' for type=REFUND_RECONCILIATION; Add @PostMapping('/internal/v1/exceptions/{id}/resolve') with @RequestBody resolution_note and @AuthenticationPrincipal for resolved_by; Apply @PreAuthorize('hasRole(ROLE_GME_OPS)') on both methods; On resolve, insert audit record or publish ExceptionResolvedEvent via EventPublisher (Outbox); Return 404 if exception item id not found on POST
**Deliverable:** services/ops-partner-bff/src/main/java/com/gme/pay/bff/ops/api/ExceptionQueueController.java with GET list and POST resolve for REFUND_RECONCILIATION type
**Acceptance / logic checks:**
- GET returns only reconciliation_item rows linked to ZP0022 batches when type=REFUND_RECONCILIATION
- POST /exceptions/{id}/resolve sets resolution_status=RESOLVED and resolved_by=authenticated operator email
- POST with GME_FINANCE role (not GME_OPS) returns 403
- POST /exceptions/{nonExistentId}/resolve returns 404
- After POST resolve, a subsequent GET for status=UNRESOLVED does not include the resolved item
**Depends on:** 9.5-T13, 9.5-T25

### 9.5-T27 — Ops runbook: ZP0022 mismatch resolution, P1 escalation criteria, and retry procedure  _(25 min)_
**Context:** Module: services/settlement-reconciliation docs. SCH-06 §6.4 states an unresolved refund registration failure blocking settlement is a P1 ops incident. The runbook must document: (1) daily schedule (ZP0021 generated 02:00 KST, ZP0022 expected by 05:00 KST, cutoff for ZP0022 inbound wait = 06:00 KST per SCH-06 §9.5); (2) how to view refund batch status via Admin portal Refund Batch View (/admin/settlement/refund-batches); (3) action for each exception type (DISCREPANCY=check result_code and amounts, correct data, re-submit ZP0021 for affected refunds; MISSING_SCHEME=ZeroPay did not acknowledge, contact 한결원; MISSING_GME=rogue record, investigate; REGISTRATION_AMOUNT_MISMATCH=amount dispute, escalate); (4) settlement block consequence: REFUND_FAILED refunds excluded from ZP0066 settlement refund detail until resolved; (5) P1 criteria: any UNRESOLVED exception after 06:00 KST (ZP0022 inbound cutoff) escalate immediately to 한결원 operations contact; (6) JIRA escalation template with fields: Priority, Affected refund_refs, batch_date, file_type=ZP0022, 한결원 contact.
**Steps:** Create services/settlement-reconciliation/src/docs/ops/OPS_REFUND_BATCH.md; Section 1: Daily schedule with all times in KST and file directions; Section 2: Admin portal navigation path and key fields in Refund Batch View; Section 3: Exception type table with action column for each match_status; Section 4: Settlement block consequence for REFUND_FAILED items; Section 5: P1 escalation criteria and timeline; Section 6: JIRA escalation template with all required fields from context
**Deliverable:** services/settlement-reconciliation/src/docs/ops/OPS_REFUND_BATCH.md covering schedule, monitoring, exception actions, settlement block, P1 criteria, and JIRA template
**Acceptance / logic checks:**
- Document lists all four match_status types (DISCREPANCY, MISSING_SCHEME, MISSING_GME, REGISTRATION_AMOUNT_MISMATCH) with action for each
- P1 escalation criteria states: unresolved after 06:00 KST ZP0022 inbound cutoff triggers P1 escalation to 한결원
- Document includes Admin portal path /admin/settlement/refund-batches
- Settlement block consequence states REFUND_FAILED items are excluded from ZP0066 refund detail until resolved
- JIRA template includes: Priority=P1, Affected refund_refs placeholder, batch_date placeholder, file_type=ZP0022, 한결원 contact field
**Depends on:** 9.5-T10, 9.5-T26


## WBS 9.6 — Settlement files (ZP0061–0066)
### 9.6-T01 — Add settlement_batch DB migration: ZP006x window/status constraints  _(30 min)_
**Context:** The settlement_batch table (DAT-03 §10.5) tracks one row per daily batch run per file type. Columns: id BIGINT PK, scheme_id BIGINT FK qr_scheme, file_type VARCHAR(10), direction VARCHAR(20) CHECK IN ('GME_TO_ZP','ZP_TO_GME'), settlement_date DATE (KST), window VARCHAR(20) CHECK IN ('MORNING','AFTERNOON','DETAIL','NIGHTLY'), status VARCHAR(20) CHECK IN ('PENDING','GENERATED','TRANSMITTED','RECEIVED','RECONCILED','ERROR'), transaction_count INT DEFAULT 0, total_amount DECIMAL(20,4) DEFAULT 0, total_amount_ccy CHAR(3), file_checksum VARCHAR(64), transmitted_at TIMESTAMPTZ, received_at TIMESTAMPTZ, reconciled_at TIMESTAMPTZ, error_detail TEXT, standard audit cols. WBS 9.6 adds support for ZP0061-ZP0066; prior migrations may not have all window/status values.
**Steps:** Write a new Flyway migration V9_6_001 that adds a unique constraint on (scheme_id, file_type, settlement_date, window) to prevent duplicate batch rows; Ensure CHECK constraints on direction, window, and status columns match the exact value lists above; Add a non-unique index on (settlement_date, file_type) for batch scheduler queries; Add a non-unique index on (status, settlement_date) for monitoring queries; Document migration rollback SQL in a comment block at the top of the file
**Deliverable:** Flyway migration file V9_6_001__settlement_batch_zp006x_constraints.sql
**Acceptance / logic checks:**
- Unique constraint (scheme_id, file_type, settlement_date, window) rejects a duplicate insert for the same file_type and date
- CHECK on window rejects the value 'WEEKLY' and accepts 'MORNING','AFTERNOON','DETAIL','NIGHTLY'
- CHECK on direction rejects 'OUTBOUND' and accepts 'GME_TO_ZP','ZP_TO_GME'
- Migration applies cleanly on an empty DB and on a DB with pre-existing payment/refund batch rows
- Rollback SQL successfully removes the new indexes and constraints without data loss

### 9.6-T02 — Add settlement_file and reconciliation_item migration for ZP006x  _(30 min)_
**Context:** settlement_file (DAT-03 §7.2) stores physical SFTP file metadata: id BIGINT PK, batch_id FK settlement_batch, filename VARCHAR(255), sftp_path VARCHAR(512), file_size_bytes BIGINT, file_checksum VARCHAR(64), direction VARCHAR(20) CHECK IN ('OUTBOUND','INBOUND'), transmitted_at TIMESTAMPTZ, created_at/updated_at. reconciliation_item (DAT-03 §7.3) stores line-level reconciliation: id PK, batch_id FK settlement_batch, txn_ref VARCHAR(64) NULLable, scheme_ref VARCHAR(128), match_status VARCHAR(20) CHECK IN ('MATCHED','DISCREPANCY','MISSING_GME','MISSING_SCHEME'), gme_amount/scheme_amount/discrepancy_amount DECIMAL(20,4), ccy CHAR(3), resolution_status VARCHAR(20) CHECK IN ('UNRESOLVED','RESOLVED','ESCALATED'), resolved_by/resolved_at/resolution_note, standard audit. Both tables must pre-exist for ZP006x batch processing.
**Steps:** Write Flyway migration V9_6_002 creating settlement_file if not already present with all listed columns and constraints; Create reconciliation_item table with all listed columns, CHECK constraints, and FK to settlement_batch; Add index on reconciliation_item(batch_id, match_status) for exception-queue queries; Add index on reconciliation_item(txn_ref) for transaction-level drill-down; Verify FK cascade behavior: deleting a settlement_batch row should be blocked (RESTRICT) not cascade
**Deliverable:** Flyway migration V9_6_002__settlement_file_reconciliation_item.sql
**Acceptance / logic checks:**
- settlement_file.direction CHECK rejects 'GME_TO_ZP' and accepts 'OUTBOUND'
- reconciliation_item.match_status CHECK rejects 'UNKNOWN' and accepts 'MISSING_SCHEME'
- FK on reconciliation_item.batch_id → settlement_batch is RESTRICT (delete of settlement_batch with child rows fails)
- Index on (batch_id, match_status) exists and is used by EXPLAIN for WHERE batch_id=X AND match_status='DISCREPANCY'
- Inserting a reconciliation_item with NULL txn_ref succeeds (NULLable column for missing-GME cases)
**Depends on:** 9.6-T01

### 9.6-T03 — Add settlement_batch_id FK column on transaction table  _(25 min)_
**Context:** Per SCH-06 §5.3, each payment transaction must appear in exactly one settlement file (ZP0061 or ZP0063). The transaction table needs a settlement_batch_id column (BIGINT NULLable, FK settlement_batch) set atomically during batch generation to enforce exactly-once settlement. A matching settlement_window column (VARCHAR(20) NULLable) records 'MORNING' or 'AFTERNOON'. A transaction with settlement_batch_id NOT NULL has been included in a submitted file and must not be re-selected for a new batch.
**Steps:** Write Flyway migration V9_6_003 adding settlement_batch_id BIGINT NULL FK settlement_batch(id) DEFERRABLE INITIALLY DEFERRED to the transaction table; Add settlement_window VARCHAR(20) NULL column with CHECK IN ('MORNING','AFTERNOON') on the same table; Add index on transaction(settlement_batch_id) for reverse-lookup from batch to transactions; Add partial index on transaction(settlement_date) WHERE settlement_batch_id IS NULL for efficient unsettled-transaction queries; Verify existing transaction rows are unaffected (NULLs only)
**Deliverable:** Flyway migration V9_6_003__transaction_settlement_batch_fk.sql
**Acceptance / logic checks:**
- transaction.settlement_batch_id is NULLable; existing rows keep NULL after migration
- Inserting a transaction row with a non-existent settlement_batch_id raises a FK violation
- Partial index WHERE settlement_batch_id IS NULL reduces scan size vs full table scan when querying unsettled transactions (EXPLAIN)
- CHECK on settlement_window rejects 'DETAIL' and accepts 'MORNING'
- Rollback removes both columns and indexes cleanly
**Depends on:** 9.6-T01

### 9.6-T04 — Define SettlementBatch and SettlementFile Java domain entities  _(45 min)_
**Context:** The Hub Core settlement engine (SAD-02 §5.4) manages settlement_batch and settlement_file DB records. Java JPA entities are needed for both tables. SettlementBatch maps to the settlement_batch table (see T01 for all fields). SettlementFile maps to settlement_file (T02). Key enum types: BatchStatus {PENDING, GENERATED, TRANSMITTED, RECEIVED, RECONCILED, ERROR}, BatchWindow {MORNING, AFTERNOON, DETAIL, NIGHTLY}, BatchDirection {GME_TO_ZP, ZP_TO_GME}. Money amounts stored as BigDecimal with scale 4 (DECIMAL(20,4)). KST date stored as java.time.LocalDate.
**Steps:** Create SettlementBatch.java JPA entity with all columns mapped, enums for status/window/direction, and standard audit fields; Create SettlementFile.java JPA entity with batch_id ManyToOne relationship to SettlementBatch; Create corresponding enums BatchStatus, BatchWindow, BatchDirection; Create SettlementBatchRepository and SettlementFileRepository (Spring Data JPA) with finder methods: findBySchemeIdAndFileTypeAndSettlementDateAndWindow, findByStatusIn; Write unit tests confirming entity creation, enum mapping, and unique-constraint violation throws DataIntegrityViolationException
**Deliverable:** SettlementBatch.java, SettlementFile.java, BatchStatus/BatchWindow/BatchDirection enums, two JPA repositories, one test class
**Acceptance / logic checks:**
- SettlementBatch.totalAmount is BigDecimal; KRW amounts with 0 decimal places are stored as e.g. 1500000.0000 without floating error
- Creating two SettlementBatch rows with identical (scheme_id, file_type, settlement_date, window) throws DataIntegrityViolationException
- findBySchemeIdAndFileTypeAndSettlementDateAndWindow returns empty Optional when none exists
- BatchStatus.from(String) throws IllegalArgumentException for unrecognised string 'DONE'
- SettlementFile.direction enum correctly persists OUTBOUND as VARCHAR 'OUTBOUND'
**Depends on:** 9.6-T01, 9.6-T02

### 9.6-T05 — Define ReconciliationItem Java entity and repository  _(35 min)_
**Context:** reconciliation_item (DAT-03 §7.3) stores per-transaction line comparisons after ZP0062/ZP0064 result files are processed. Key fields: id, batch_id FK settlement_batch, txn_ref VARCHAR(64) NULLable, scheme_ref VARCHAR(128), match_status enum {MATCHED, DISCREPANCY, MISSING_GME, MISSING_SCHEME}, gme_amount/scheme_amount/discrepancy_amount BigDecimal, ccy CHAR(3), resolution_status enum {UNRESOLVED, RESOLVED, ESCALATED}, resolved_by/resolved_at/resolution_note, standard audit. The exception queue in Ops Admin queries by batch_id and match_status != MATCHED.
**Steps:** Create ReconciliationItem.java JPA entity with all columns, enums MatchStatus and ResolutionStatus; Add ManyToOne to SettlementBatch; Create ReconciliationItemRepository with methods: findByBatchIdAndMatchStatusNot(Long batchId, MatchStatus status), countByBatchIdAndMatchStatus, saveAll; Write unit tests for entity persistence and repository finders with in-memory H2 DB
**Deliverable:** ReconciliationItem.java, MatchStatus/ResolutionStatus enums, ReconciliationItemRepository, unit test class
**Acceptance / logic checks:**
- Persisting a ReconciliationItem with txn_ref NULL succeeds (MISSING_GME case)
- findByBatchIdAndMatchStatusNot returns only DISCREPANCY and MISSING_SCHEME rows when MATCHED rows also exist in the same batch
- discrepancy_amount stores gme_amount minus scheme_amount; a 1 KRW mismatch persists as 1.0000
- ResolutionStatus.ESCALATED persists as VARCHAR 'ESCALATED'
- Deleting a SettlementBatch with attached ReconciliationItems throws DataIntegrityViolationException (RESTRICT)
**Depends on:** 9.6-T02, 9.6-T04

### 9.6-T06 — Implement SettlementWindowSelector: assign transactions to MORNING or AFTERNOON window  _(45 min)_
**Context:** ZP0061 (MORNING, due ~05:00 KST) covers all transactions registered in ZP0011/ZP0012 for the prior business day where ZP0012 status = SETTLEMENT_REGISTERED and settlement_batch_id IS NULL. ZP0063 (AFTERNOON, due ~14:00 KST) covers supplementary same-day transactions approved after the ZP0061 cutoff. Business rule: at the time the ZP0061 batch job runs (~04:30 KST), all eligible unsettled transactions with settlement_date = prior KST business day and status SETTLEMENT_REGISTERED are assigned window MORNING. Transactions that arrive after ZP0061 is transmitted but before ~13:30 KST are assigned AFTERNOON. Transactions with unresolved registration failures (status != SETTLEMENT_REGISTERED) are EXCLUDED from both windows until Ops resolves the exception.
**Steps:** Implement SettlementWindowSelector.selectForMorning(LocalDate settlementDate): queries transaction table WHERE settlement_date=settlementDate AND settlement_batch_id IS NULL AND batch_status='SETTLEMENT_REGISTERED', returns List<Long> transaction IDs; Implement selectForAfternoon(LocalDate settlementDate): same filter but settlement_date = today (same-day transactions approved after morning cutoff); Both methods must use SELECT FOR UPDATE on the settlement_batch_id column rows to prevent concurrent assignment; Write unit tests with mocked repository covering: normal case (3 eligible txns returned), exclusion of already-settled txns (settlement_batch_id NOT NULL), exclusion of non-SETTLEMENT_REGISTERED txns
**Deliverable:** SettlementWindowSelector.java service class with selectForMorning and selectForAfternoon methods plus unit tests
**Acceptance / logic checks:**
- selectForMorning with 5 eligible + 2 already settled returns exactly 5 IDs
- selectForMorning excludes a transaction with status REGISTRATION_FAILED (not in result set)
- selectForAfternoon for same-day date T returns txns with settlement_date=T and settlement_batch_id IS NULL
- Calling selectForMorning twice concurrently (two threads) does not return the same transaction ID in both result sets (lock test)
- Passing a Sunday date (non-business day) returns empty list (ZeroPay operates Mon-Sat)
**Depends on:** 9.6-T03, 9.6-T04

### 9.6-T07 — Implement net vs gross settlement amount computation per transaction  _(30 min)_
**Context:** Settlement amount for ZP0061/ZP0063 differs by partner type (SCH-06 §7.1, SAD-02 §5.4). Net settlement (domestic, partner type LOCAL, e.g. GME Remit): net_settlement_amount = target_payout (KRW). GME retains its fee share; ZeroPay credits exactly target_payout to the merchant. Gross settlement (international, partner type OVERSEAS, e.g. SendMN): net_settlement_amount = target_payout (KRW) — the full payout amount is remitted; GME separately invoices the merchant monthly. In both cases net_settlement_amount = target_payout (KRW, 0 decimals). The settlement_type CHAR is 'N' for domestic, 'G' for international. merchant_fee_total (ZP0061 field) = 0 for gross, = service_charge (KRW) for net domestic. service_charge is flat per-transaction (e.g. KRW 500) and is NOT shared with ZeroPay.
**Steps:** Implement SettlementAmountCalculator.compute(Transaction txn): returns SettlementLineItem record with fields net_settlement_amount (KRW BigDecimal scale 0), merchant_fee_total (KRW BigDecimal scale 0), settlement_type (char N or G); For LOCAL partner: settlement_type='N', net_settlement_amount=txn.targetPayoutKrw, merchant_fee_total=txn.serviceCharge; For OVERSEAS partner: settlement_type='G', net_settlement_amount=txn.targetPayoutKrw, merchant_fee_total=BigDecimal.ZERO; Write unit tests with numeric examples
**Deliverable:** SettlementAmountCalculator.java + SettlementLineItem record + unit tests
**Acceptance / logic checks:**
- LOCAL txn with target_payout=10000 KRW, service_charge=500 KRW: net_settlement_amount=10000, merchant_fee_total=500, type='N'
- OVERSEAS txn with target_payout=50000 KRW: net_settlement_amount=50000, merchant_fee_total=0, type='G'
- net_settlement_amount is always equal to target_payout regardless of partner type
- Result uses BigDecimal scale 0 for KRW; no floating-point arithmetic
- Passing a null partner type throws IllegalArgumentException with message containing 'partner type'
**Depends on:** 9.6-T04

### 9.6-T08 — Implement ZP0061MorningSettlementRequestBuilder: aggregate and format file  _(50 min)_
**Context:** ZP0061 (GME→ZeroPay, due ~05:00 KST) contains one summary record per merchant. Fields per record (SCH-06 §7.2): merchant_id CHAR(10), settlement_date DATE(8) YYYYMMDD, gross_txn_count NUM(6), gross_txn_amount NUM(14) (sum of target_payout KRW for payment txns), refund_count NUM(6), refund_amount NUM(14) (sum of refund KRW), merchant_fee_total NUM(12), net_settlement_amount NUM(14), settlement_type CHAR(1) N or G. The builder aggregates all transactions in the MORNING window for a settlement_date, groups by merchant_id, sums amounts. File naming convention: ZP0061_{YYYYMMDD}_{SEQ}.dat.pgp where SEQ starts at 01. The batch is idempotent: re-running with the same inputs produces byte-identical output (fixed ordering: merchant_id ASC).
**Steps:** Implement ZP0061MorningSettlementRequestBuilder.build(LocalDate settlementDate, List<SettlementLineItem> lines): groups by merchant_id, sums gross_txn_amount and refund_amount, computes net_settlement_amount per merchant, returns a structured ZP0061File object with header, records list, and trailer (total record count); Use SettlementAmountCalculator (T07) for per-line amounts before aggregation; Enforce merchant_id padded to CHAR(10), amounts formatted as zero-padded NUM fields; Return byte[] content as fixed-width flat file (encoding UTF-8); SHA-256 checksum computed over the bytes; Write unit test: 2 payment txns + 1 refund txn for merchant M001 produces one summary record with correct sums
**Deliverable:** ZP0061MorningSettlementRequestBuilder.java + ZP0061File record + unit tests
**Acceptance / logic checks:**
- Two payment txns for merchant M001 (10000 KRW each) + one refund (5000 KRW): gross_txn_amount=20000, refund_amount=5000, gross_txn_count=2, refund_count=1 in the output record
- Merchants sorted by merchant_id ASC; same input always produces same byte[] (idempotent)
- merchant_id='MERCH1' is right-padded to 10 chars in the output
- gross_txn_amount field is zero-padded to NUM(14): value 20000 renders as '00000000020000'
- Zero-transaction merchant is not included in the file (no empty records)
**Depends on:** 9.6-T06, 9.6-T07

### 9.6-T09 — Implement ZP0063AfternoonSettlementRequestBuilder  _(35 min)_
**Context:** ZP0063 (GME→ZeroPay, due ~14:00 KST) is the afternoon supplement. It covers transactions approved after the ZP0061 cutoff that require same-day merchant crediting (SCH-06 §7.2, Assumption A-08). The file layout is identical to ZP0061 (same fields, same format). The only differences are: file type code in the filename is ZP0063, the window value on settlement_batch is AFTERNOON, and the source transaction set comes from SettlementWindowSelector.selectForAfternoon. The builder must not include any transactions already assigned to MORNING window (settlement_batch_id IS NOT NULL for morning batch).
**Steps:** Implement ZP0063AfternoonSettlementRequestBuilder.build(LocalDate settlementDate, List<SettlementLineItem> lines) — reuses the same field formatting logic as ZP0061 builder (extract a shared ZeropaySettlementFileFormatter utility); Filename pattern: ZP0063_{YYYYMMDD}_{SEQ}.dat.pgp; Verify via unit test that a txn already in the MORNING window is not present in the afternoon file; Write unit test with 1 afternoon txn for merchant M002 producing a valid single-record file
**Deliverable:** ZP0063AfternoonSettlementRequestBuilder.java + ZeropaySettlementFileFormatter utility + unit tests
**Acceptance / logic checks:**
- Afternoon builder produces file_type='ZP0063' in the batch record (not ZP0061)
- A transaction with settlement_batch_id pointing to a MORNING batch is absent from the afternoon file
- Single afternoon txn for merchant M002 with target_payout=30000 KRW: one record, gross_txn_amount=30000
- Shared formatter produces NUM(14) formatting identical to ZP0061 builder for same input amounts
- Empty afternoon transaction list produces a valid file with zero records (header + trailer only, no data records)
**Depends on:** 9.6-T08

### 9.6-T10 — Implement SettlementBatchGenerationService: orchestrate batch creation with atomic txn assignment  _(55 min)_
**Context:** Creating a ZP0061 or ZP0063 batch must be atomic: all selected transactions must have their settlement_batch_id set in the same DB transaction that inserts the settlement_batch row, so no transaction can appear in two batches (SCH-06 §5.3). The service must: 1) open a DB transaction, 2) INSERT a settlement_batch row with status=GENERATED, 3) call SettlementWindowSelector to get eligible txn IDs (using SELECT FOR UPDATE), 4) UPDATE transaction.settlement_batch_id = new_batch.id and settlement_window='MORNING'/'AFTERNOON' for all selected IDs, 5) build the file bytes using the appropriate builder, 6) store file_checksum on settlement_batch, 7) write the SettlementFile row, 8) COMMIT. On any failure, the entire DB transaction rolls back.
**Steps:** Implement SettlementBatchGenerationService.generateMorning(LocalDate settlementDate, Long schemeId) -> SettlementBatch; Implement generateAfternoon(LocalDate settlementDate, Long schemeId) -> SettlementBatch; Wrap all steps in @Transactional; use programmatic transaction management for the SELECT FOR UPDATE; If a GENERATED batch already exists for (schemeId, file_type, settlement_date, window), return the existing batch without re-generating (idempotency); Write unit tests: normal path, idempotency (second call returns same batch), concurrent call safety (use two threads, verify only one batch row is created)
**Deliverable:** SettlementBatchGenerationService.java + integration test class (uses embedded Postgres or H2)
**Acceptance / logic checks:**
- Normal path: settlement_batch row inserted with status=GENERATED; all selected txn IDs have settlement_batch_id set to the new batch ID
- Calling generateMorning twice for same date returns the same settlement_batch.id without inserting a second row
- A transaction already assigned to a batch is not re-selected in a concurrent generateMorning call
- If file builder throws, DB transaction rolls back: no settlement_batch row and no settlement_batch_id updates on transactions
- settlement_batch.transaction_count equals the count of transactions updated in the same call
**Depends on:** 9.6-T06, 9.6-T08, 9.6-T09

### 9.6-T11 — Implement SFTP transmission for ZP0061/ZP0063 outbound files  _(50 min)_
**Context:** The ZeroPay SFTP adapter (SCH-06 §2) transmits outbound files to the /gmepay/outbound/ directory on 한결원's SFTP server. File naming: ZP0061_{YYYYMMDD}_{SEQ}.dat.pgp; SEQ=01 for first transmission, incremented on retransmit (e.g. 02 for the first retry). The adapter must: encrypt the file bytes with ZeroPay's PGP public key before transmission, put the file via SFTP, record transmitted_at and update settlement_batch.status to TRANSMITTED. On SFTP failure, retry up to 3 times with exponential backoff (1s, 2s, 4s); after 3 failures, status=ERROR and an Ops alert is published. Outbound SFTP host key must be pinned (SEC-09 T-06).
**Steps:** Implement SftpOutboundTransmitter.transmit(SettlementBatch batch, byte[] fileBytes, String filename): encrypts bytes with PGP public key loaded from config, puts file to SFTP path, updates batch status to TRANSMITTED, records transmitted_at; Add retry logic (3 attempts, exponential backoff) using Spring Retry or equivalent; On final failure: set status=ERROR, set error_detail, publish OpsAlertEvent(priority=P1, batchId, message); Write unit tests with mocked SFTP client: success path, single retry then success, three failures then ERROR status; Ensure sequence number increments correctly: second call with same batch id uses SEQ=02
**Deliverable:** SftpOutboundTransmitter.java + retry config + unit tests
**Acceptance / logic checks:**
- Successful transmission updates settlement_batch.status to TRANSMITTED and sets transmitted_at to current UTC timestamp
- After 3 SFTP failures, status=ERROR and OpsAlertEvent is published exactly once
- SEQ number in filename is 01 for first transmission, 02 for first retransmit (same batch, second call)
- PGP encryption is applied before SFTP put; raw plaintext bytes are never written to SFTP
- transmitted_at is only set when SFTP put returns success, not on retry attempts
**Depends on:** 9.6-T10

### 9.6-T12 — Implement ZP0062/ZP0064 inbound settlement result file parser  _(40 min)_
**Context:** ZP0062 (morning settlement result, expected ~10:00 KST) and ZP0064 (afternoon settlement result, expected ~19:00 KST) are inbound files from ZeroPay confirming per-merchant settlement. Each record confirms the merchant_id, settlement_date, credited_amount KRW, and a result_code. A result_code of '0000' means SETTLEMENT_CONFIRMED; any other code means SETTLEMENT_DISCREPANCY. The parser must be tolerant of record count mismatches (partial file = P1 exception). Field widths match ZP0061 format (same layout family). Files arrive on the SFTP inbound directory /gmepay/inbound/. After polling, the parser: decrypts PGP, parses fixed-width records, returns a List<SettlementResultRecord> with fields: merchant_id, credited_amount_krw, result_code, scheme_result_message.
**Steps:** Implement ZP0062ResultFileParser.parse(byte[] encryptedBytes): decrypt PGP, parse fixed-width records, return List<SettlementResultRecord>; Handle file parse error (corrupt/truncated) by throwing SettlementFileParseException which triggers status=ERROR + OpsAlert; Implement ZP0064ResultFileParser extending or delegating to ZP0062ResultFileParser (same format, different file type label); Write unit tests with sample byte arrays representing a valid 3-record file and a truncated file; Verify that result_code != '0000' sets SettlementResultRecord.discrepancyFlag=true
**Deliverable:** ZP0062ResultFileParser.java, ZP0064ResultFileParser.java, SettlementResultRecord.java + unit tests
**Acceptance / logic checks:**
- Valid 3-record file parses to exactly 3 SettlementResultRecord objects with correct credited_amount_krw values
- Record with result_code='9999' has discrepancyFlag=true; '0000' has discrepancyFlag=false
- Truncated file (missing last record mid-byte) throws SettlementFileParseException
- credited_amount_krw is BigDecimal with scale 0 (KRW integer)
- Parser is stateless: calling parse twice on the same bytes returns equal (not same) List instances
**Depends on:** 9.6-T04

### 9.6-T13 — Implement settlement result reconciliation: ZP0061 vs ZP0062, ZP0063 vs ZP0064  _(50 min)_
**Context:** After parsing ZP0062 or ZP0064, the Reconciliation Engine (SAD-02 §5.4) must compare each merchant total in the result file against the amount in the corresponding outbound file (ZP0061 or ZP0063). Zero-tolerance: every merchant total must match (SCH-06 §9.1). Discrepancy detection: if result credited_amount differs from outbound net_settlement_amount for the same merchant_id, create a reconciliation_item with match_status=DISCREPANCY; if merchant appears in result but not in outbound, match_status=MISSING_GME; if merchant appears in outbound but not in result, match_status=MISSING_SCHEME. On any discrepancy, publish OpsAlertEvent(priority=P1). On clean reconciliation, set settlement_batch.status=RECONCILED.
**Steps:** Implement SettlementReconciliationService.reconcile(SettlementBatch outboundBatch, List<SettlementResultRecord> resultRecords): compares by merchant_id, persists ReconciliationItem rows, updates batch status; For clean match: status=RECONCILED, reconciled_at=now; For any mismatch: status=RECONCILED with exception items inserted; publish OpsAlertEvent per discrepancy; Write unit test: 3 merchants match -> status=RECONCILED, 0 items created; Write unit test: 2 match, 1 has amount mismatch -> RECONCILED + 1 DISCREPANCY item + 1 OpsAlertEvent
**Deliverable:** SettlementReconciliationService.java + unit tests
**Acceptance / logic checks:**
- Clean reconciliation of 3 merchants (all amounts match) produces status=RECONCILED, 0 reconciliation_item rows, 0 OpsAlerts
- Amount mismatch of 1 KRW for merchant M001: 1 reconciliation_item with match_status=DISCREPANCY, discrepancy_amount=1.0000, resolution_status=UNRESOLVED
- Merchant in ZP0062 result not in ZP0061 outbound: match_status=MISSING_GME, txn_ref=NULL
- Merchant in ZP0061 outbound not in ZP0062 result: match_status=MISSING_SCHEME, scheme_ref=NULL
- OpsAlertEvent published exactly once per discrepancy item, priority P1
**Depends on:** 9.6-T05, 9.6-T12

### 9.6-T14 — Implement ZP0065 payment detail file builder  _(45 min)_
**Context:** ZP0065 (GME→ZeroPay, due ~22:00 KST) provides transaction-level detail for all settled payments. Each record (SCH-06 §7.2): merchant_id CHAR(10), zeropay_txn_ref CHAR(20) (scheme approval code from real-time authorisation), txn_date DATE(8), txn_time TIME(6) HHMMSS, payout_amount_krw NUM(12), merchant_fee_amt NUM(12), van_fee_amt NUM(10) (zero for Phase 1), partner_type CHAR(1) D for domestic / I for international, settlement_batch_ref CHAR(20) (reference to ZP0061 or ZP0063 batch). The scope is ALL settled payments for the settlement_date (both MORNING and AFTERNOON windows). Detail reconciliation: ZP0065 line-item totals must tie back to ZP0061+ZP0063 merchant totals (zero tolerance, SCH-06 §9.1).
**Steps:** Implement ZP0065PaymentDetailBuilder.build(LocalDate settlementDate, Long schemeId): queries transactions with settlement_batch_id NOT NULL for the date, formats each as a fixed-width detail record; settlement_batch_ref field is set to the filename of the linked settlement_batch (ZP0061 or ZP0063 filename); partner_type='D' for LOCAL partner transactions, 'I' for OVERSEAS; van_fee_amt is always zero-filled (NUM(10) = '0000000000') in Phase 1; Write unit test: 2 MORNING transactions for merchant M001 + 1 AFTERNOON for merchant M002 produces 3 records in merchant_id+txn_date+txn_time order
**Deliverable:** ZP0065PaymentDetailBuilder.java + unit tests
**Acceptance / logic checks:**
- Three transactions produce three records; records are sorted by merchant_id ASC then txn_date ASC then txn_time ASC
- payout_amount_krw for OVERSEAS txn with target_payout=50000 renders as '000000050000' (NUM(12) zero-padded)
- partner_type='D' for LOCAL partner txn, 'I' for OVERSEAS partner txn
- settlement_batch_ref matches the filename of the transaction's settlement_batch (e.g. ZP0061_20260605_01)
- A payment transaction with settlement_batch_id IS NULL is excluded from the file
**Depends on:** 9.6-T08, 9.6-T09

### 9.6-T15 — Implement ZP0066 refund detail file builder  _(40 min)_
**Context:** ZP0066 (GME→ZeroPay, due ~22:00 KST) provides transaction-level detail for all settled refunds. Record layout (SCH-06 §7.2): merchant_id CHAR(10), original_zeropay_txn_ref CHAR(20) (reference to the original payment), refund_date DATE(8), refund_amount_krw NUM(12) (positive value representing the refunded KRW amount), merchant_fee_adj_amt NUM(12) (fee adjustment; 0 for gross/international, positive for net/domestic), settlement_batch_ref CHAR(20). Source: refund records with status SETTLEMENT_REGISTERED and settlement_batch_id pointing to the same settlement_date batch. Note: only refunds processed after the business day of the original payment are included (same-day cancels via real-time API are excluded per SCH-06 §6.1).
**Steps:** Implement ZP0066RefundDetailBuilder.build(LocalDate settlementDate, Long schemeId): queries refund table for records with settlement_date=settlementDate and status=SETTLEMENT_REGISTERED; Format each refund as a fixed-width record using the ZeropaySettlementFileFormatter utility from T09; merchant_fee_adj_amt = service_charge for LOCAL refunds, = 0 for OVERSEAS refunds; Exclude any refund with the same settlement_date as its original payment transaction (same-day cancel exclusion); Write unit tests: LOCAL refund with service_charge=500 produces merchant_fee_adj_amt='000000000500'; OVERSEAS refund produces '000000000000'
**Deliverable:** ZP0066RefundDetailBuilder.java + unit tests
**Acceptance / logic checks:**
- LOCAL refund with refund_amount=10000 KRW, service_charge=500 KRW: refund_amount_krw='000000010000', merchant_fee_adj_amt='000000000500'
- OVERSEAS refund: merchant_fee_adj_amt='000000000000'
- A same-day cancel (refund.settlement_date == original_txn.settlement_date) is excluded from the file
- original_zeropay_txn_ref is populated from the original payment's zeropay_txn_ref field (not a new reference)
- Empty refund day (no refunds) produces a valid file with zero records (header + trailer only)
**Depends on:** 9.6-T14

### 9.6-T16 — Implement ZP0065/ZP0066 batch generation and SFTP transmission (22:00 cron)  _(45 min)_
**Context:** The 22:00 KST cron job generates ZP0065 and ZP0066 for the current settlement_date and transmits both via SFTP. Internal deadline: complete by 21:30 KST (NFR-10 §2.3). The job is idempotent: if a GENERATED or TRANSMITTED batch already exists for (scheme_id, ZP0065, settlement_date, DETAIL), return existing batch. Batch job dependencies: ZP0065/ZP0066 have no hard dependency on ZP0062/ZP0064 but must cover only transactions whose settlement_batch_id IS NOT NULL (i.e. included in ZP0061 or ZP0063). File naming: ZP0065_{YYYYMMDD}_01.dat.pgp, ZP0066_{YYYYMMDD}_01.dat.pgp.
**Steps:** Implement DetailSettlementBatchJob.run(LocalDate settlementDate, Long schemeId): calls ZP0065PaymentDetailBuilder, ZP0066RefundDetailBuilder, inserts two settlement_batch rows (window=DETAIL), calls SftpOutboundTransmitter for each; Annotate with @Scheduled(cron='0 0 22 * * MON-SAT', zone='Asia/Seoul') or equivalent cron expression; Idempotency: check for existing GENERATED/TRANSMITTED DETAIL batches before regenerating; Publish OpsAlertEvent(P1) if either file's SFTP transmission fails after 3 retries; Write unit tests for idempotency and for the case where ZP0065 succeeds but ZP0066 SFTP fails (ZP0065 remains TRANSMITTED; ZP0066 becomes ERROR)
**Deliverable:** DetailSettlementBatchJob.java + unit tests
**Acceptance / logic checks:**
- Running the job twice for the same date creates exactly 2 settlement_batch rows total (one ZP0065, one ZP0066), not 4
- ZP0065 batch window is DETAIL; ZP0066 batch window is DETAIL
- If ZP0066 SFTP fails, ZP0065 batch is unaffected (status=TRANSMITTED); ZP0066 status=ERROR
- Cron expression evaluates to 22:00 KST on a Tuesday (business day) and does NOT fire on a Sunday
- OpsAlertEvent priority=P1 is published for SFTP failure; no alert on success
**Depends on:** 9.6-T11, 9.6-T14, 9.6-T15

### 9.6-T17 — Implement ZP0061 morning cron job and batch dependency gate  _(45 min)_
**Context:** The ZP0061 morning settlement cron job runs at ~04:30 KST (internal deadline) and transmits by ~05:00 KST. Pre-conditions (SCH-06 §8.2): ZP0011 batch for the prior day must be status=TRANSMITTED and ZP0012 must be status=RECEIVED (confirming ZeroPay acknowledged registrations). If ZP0012 has not been received by 04:30, the job must alert Ops (P1) and abort. If ZP0012 contains registration failures for some transactions, those transactions are excluded from ZP0061; non-affected transactions proceed. Cron fires Mon-Sat only. Job is idempotent.
**Steps:** Implement MorningSettlementBatchJob.run(LocalDate settlementDate, Long schemeId); Check pre-condition: settlement_batch for (schemeId, ZP0012, settlementDate-1, NIGHTLY).status == RECEIVED; if not, publish OpsAlert(P1) and abort; Check pre-condition: settlement_batch for (schemeId, ZP0011, settlementDate-1, NIGHTLY).status == TRANSMITTED; if not, publish OpsAlert(P1) and abort; Call SettlementBatchGenerationService.generateMorning, then SftpOutboundTransmitter; Annotate @Scheduled(cron='0 30 4 * * MON-SAT', zone='Asia/Seoul')
**Deliverable:** MorningSettlementBatchJob.java + unit tests
**Acceptance / logic checks:**
- If ZP0012 status is PENDING (not yet received), job aborts without creating any ZP0061 batch row and publishes 1 OpsAlertEvent(P1)
- If ZP0012 is RECEIVED but contains 2 registration failures, those 2 txn IDs are excluded from ZP0061; remaining transactions proceed
- If ZP0011 is not TRANSMITTED, job aborts without generating ZP0061
- Second invocation with ZP0061 already in GENERATED status returns the existing batch (idempotency)
- Job does not fire on Sunday (cron MON-SAT constraint verified in test)
**Depends on:** 9.6-T10, 9.6-T11

### 9.6-T18 — Implement SFTP inbound poller for ZP0062 and ZP0064  _(50 min)_
**Context:** ZP0062 (morning settlement result) is expected ~10:00 KST; ZP0064 (afternoon settlement result) ~19:00 KST. The inbound SFTP poller (SAD-02 §5.4) polls /gmepay/inbound/ every 5 minutes from 09:00-12:00 KST for ZP0062 and from 18:00-21:00 KST for ZP0064. On file detection: download, decrypt PGP, validate filename pattern, check file size > 0. Record settlement_file row with direction=INBOUND. Update settlement_batch.status=RECEIVED and received_at. If file not received by cutoff (ZP0062: 12:00 KST, ZP0064: 21:00 KST), publish OpsAlertEvent(P2, 'file not received within window'). Files are processed exactly once (idempotency via settlement_file.filename unique check).
**Steps:** Implement SftpInboundPoller.pollForZP0062(LocalDate settlementDate) and pollForZP0064(LocalDate settlementDate): list SFTP inbound directory, filter by filename pattern ZP006{2|4}_{YYYYMMDD}_*.dat.pgp; On file found: download, store settlement_file row, update settlement_batch status=RECEIVED, trigger ZP006xResultFileParser; Schedule via @Scheduled(fixedDelay=300000) with active window guards (ZP0062: 09:00-12:00 KST, ZP0064: 18:00-21:00 KST); Idempotency: before downloading, check settlement_file table for filename; skip if already processed; At 12:00 KST if ZP0062 not received, publish OpsAlertEvent(P2); at 21:00 KST for ZP0064
**Deliverable:** SftpInboundPoller.java + unit tests (mocked SFTP)
**Acceptance / logic checks:**
- File ZP0062_20260605_01.dat.pgp found at 10:04 KST: settlement_batch.status=RECEIVED, received_at set, settlement_file row inserted
- Same filename found on second poll cycle: not re-processed (idempotency; settlement_file already exists)
- ZP0062 not found by 12:00 KST: OpsAlertEvent(P2) published exactly once (not once per poll cycle)
- Poll does not attempt to download files outside its active time window (09:00-12:00 for ZP0062)
- A zero-byte file is rejected with SettlementFileParseException and OpsAlert; not persisted to settlement_file
**Depends on:** 9.6-T12, 9.6-T11

### 9.6-T19 — Implement ZP0063 afternoon cron job with morning cycle dependency  _(40 min)_
**Context:** The ZP0063 afternoon cron job runs at ~13:30 KST (internal deadline) and transmits by ~14:00 KST. Pre-condition (SCH-06 §8.2): the morning settlement cycle must be complete, meaning ZP0061 batch status=TRANSMITTED. ZP0063 should not block on ZP0062 result (which arrives at ~10:00 and may still be reconciling). ZP0063 covers supplementary same-day transactions not included in ZP0061 — i.e. transactions with settlement_date=today and settlement_batch_id IS NULL and status=SETTLEMENT_REGISTERED. Cron fires Mon-Sat. Job is idempotent.
**Steps:** Implement AfternoonSettlementBatchJob.run(LocalDate settlementDate, Long schemeId); Pre-condition check: ZP0061 for (schemeId, settlement_date=today, MORNING).status = TRANSMITTED; if not, OpsAlert(P2) and abort; Call SettlementBatchGenerationService.generateAfternoon and SftpOutboundTransmitter; Annotate @Scheduled(cron='0 30 13 * * MON-SAT', zone='Asia/Seoul'); Write unit tests: normal flow, ZP0061 not yet transmitted (abort), idempotency, empty afternoon transaction set (produces empty file, still transmitted)
**Deliverable:** AfternoonSettlementBatchJob.java + unit tests
**Acceptance / logic checks:**
- ZP0061 status=TRANSMITTED: afternoon job proceeds and ZP0063 batch created with window=AFTERNOON
- ZP0061 status=GENERATED (not yet transmitted): afternoon job aborts, OpsAlert(P2) published, no ZP0063 batch created
- Empty afternoon txn set: ZP0063 file generated with 0 records, status=TRANSMITTED (empty file is valid)
- Idempotency: second invocation when ZP0063 already GENERATED returns existing batch without re-generating
- Cron expression fires at 13:30 on Wednesday KST; does not fire on Saturday (wait - Sat IS a business day); does not fire on Sunday
**Depends on:** 9.6-T09, 9.6-T17, 9.6-T18

### 9.6-T20 — Implement ZP0064 receipt processing and afternoon reconciliation trigger  _(40 min)_
**Context:** After ZP0064 (afternoon settlement result) is received and parsed (~19:00 KST), the system must: 1) update settlement_batch for ZP0063 with status=RECEIVED for the companion ZP0064 row, 2) call SettlementReconciliationService.reconcile(ZP0063 outbound batch, ZP0064 result records), 3) update reconciled_at on the ZP0063 batch on clean match. Any discrepancy creates reconciliation_item rows and publishes OpsAlertEvent(P1). This mirrors the morning cycle (ZP0062 triggers reconciliation of ZP0061) but for the afternoon window.
**Steps:** Implement AfternoonResultProcessor.process(LocalDate settlementDate, Long schemeId, List<SettlementResultRecord> zp0064Records); Look up ZP0063 settlement_batch for (schemeId, settlementDate, AFTERNOON); if not found, throw IllegalStateException (ZP0064 arrived without a corresponding ZP0063 outbound); Delegate reconciliation to SettlementReconciliationService (T13); Publish settlement_completed event for downstream consumers (e.g. settlement.completed webhook to partners) after reconciliation; Write unit tests: clean reconciliation, amount mismatch, missing ZP0063 outbound
**Deliverable:** AfternoonResultProcessor.java + unit tests
**Acceptance / logic checks:**
- Clean ZP0064 with 2 merchants matching ZP0063: ZP0063 batch status=RECONCILED, reconciled_at set, 0 reconciliation_item rows
- ZP0064 merchant amount differs by 1000 KRW from ZP0063: 1 DISCREPANCY reconciliation_item, OpsAlert(P1) published
- ZP0064 received with no corresponding ZP0063 batch: IllegalStateException thrown, no data written
- settlement_completed event is published after successful reconciliation with total_payout_krw = sum of credited amounts
- ZP0064 reprocessed (idempotency): reconciliation_item rows not duplicated; existing RECONCILED batch returned
**Depends on:** 9.6-T13, 9.6-T19

### 9.6-T21 — Implement detail reconciliation: ZP0065 vs internal ledger  _(45 min)_
**Context:** Detail reconciliation (SCH-06 §9.1) compares ZP0065 line items against the internal transaction ledger. For each transaction in ZP0065: look up the transaction by zeropay_txn_ref, compare payout_amount_krw with txn.target_payout (KRW). If match, no action. If mismatch, create reconciliation_item with match_status=DISCREPANCY. If zeropay_txn_ref in ZP0065 not found in GME DB, match_status=MISSING_GME. If transaction in GME DB (settlement_batch_id NOT NULL, settlement_date = target) not in ZP0065, match_status=MISSING_SCHEME. Tolerance: zero (every line item must match). This check is run after ZP0065 is transmitted, as a self-verification step against the ledger.
**Steps:** Implement ZP0065DetailReconciler.reconcile(Long zp0065BatchId, List<ZP0065DetailRecord> lines): for each line, look up transaction by zeropay_txn_ref, compare payout_amount_krw vs target_payout; For GME-side: query all transactions with settlement_date=targetDate and settlement_batch_id IN (morning_batch_id, afternoon_batch_id); Persist reconciliation_item rows for any mismatch; publish OpsAlert(P1) per discrepancy; Write unit tests: clean case (3 lines, 3 match), 1 line amount mismatch, 1 line missing from GME DB, 1 GME txn missing from file
**Deliverable:** ZP0065DetailReconciler.java + unit tests
**Acceptance / logic checks:**
- 3 matching lines produce 0 reconciliation_item rows and no OpsAlert
- Line with zeropay_txn_ref='ZP20260605001' not in GME DB: reconciliation_item match_status=MISSING_GME, scheme_ref='ZP20260605001', txn_ref=NULL
- GME txn with zeropay_txn_ref='ZP20260605002' absent from ZP0065: reconciliation_item match_status=MISSING_SCHEME, txn_ref set, scheme_ref=NULL
- payout_amount_krw mismatch of 500 KRW: discrepancy_amount=500.0000 in reconciliation_item
- Reconciler is idempotent: running twice on same data does not create duplicate reconciliation_item rows
**Depends on:** 9.6-T05, 9.6-T14

### 9.6-T22 — Implement ZP0066 vs internal ledger detail reconciliation  _(35 min)_
**Context:** Mirrors ZP0065DetailReconciler (T21) but for refund records. ZP0066 line items are compared against GME refund records by original_zeropay_txn_ref. GME refund fields: original_zeropay_txn_ref (FK to original payment's zeropay_txn_ref), refund_amount_krw = refund.amount_krw. Tolerance: zero. If ZP0066 refund_amount_krw differs from GME refund.amount_krw for the same original_zeropay_txn_ref: DISCREPANCY. If ZP0066 line has no matching GME refund: MISSING_GME. If GME refund not in ZP0066: MISSING_SCHEME.
**Steps:** Implement ZP0066DetailReconciler.reconcile(Long zp0066BatchId, List<ZP0066DetailRecord> lines) with analogous logic to T21; Look up refund by original_zeropay_txn_ref; compare refund_amount_krw vs refund.amount_krw; Persist reconciliation_item rows; publish OpsAlert(P1) on any mismatch; Write unit tests identical in structure to T21 but using refund records; Verify same-day cancel exclusion: refunds excluded from ZP0066 generation (T15) are also absent from reconciliation scope
**Deliverable:** ZP0066DetailReconciler.java + unit tests
**Acceptance / logic checks:**
- Matching refund: 0 reconciliation_item rows, no OpsAlert
- ZP0066 record with original_zeropay_txn_ref not in GME refund table: MISSING_GME reconciliation_item
- GME refund not appearing in ZP0066 file: MISSING_SCHEME reconciliation_item
- refund_amount_krw mismatch of 200 KRW: DISCREPANCY item with discrepancy_amount=200.0000
- Idempotent: second call on same data does not create duplicate items
**Depends on:** 9.6-T21, 9.6-T15

### 9.6-T23 — Implement batch retransmission with incremented SEQ number  _(40 min)_
**Context:** On SFTP failure or Ops-initiated retransmit, the outbound file must be retransmitted with an incremented sequence number in the filename (e.g. ZP0061_20260605_02.dat.pgp for the first retransmit). The file content must be regenerated idempotently for the same transaction set (same transactions already assigned to the batch via settlement_batch_id). The settlement_batch row retains the same id; a new settlement_file row is inserted with the new filename. Status transitions: ERROR -> GENERATED -> TRANSMITTED on successful retransmit.
**Steps:** Implement SettlementBatchRetransmitService.retransmit(Long batchId): loads existing SettlementBatch, increments SEQ (queries MAX(seq) from settlement_file for this batch + 1), regenerates file bytes from the already-assigned transactions, calls SftpOutboundTransmitter with new filename; Status change: set status=GENERATED before transmission, then TRANSMITTED on success; Insert new settlement_file row with incremented filename; do not delete old settlement_file rows (audit trail); Write unit tests: first retransmit produces SEQ=02; second retransmit produces SEQ=03; file bytes are byte-identical to original for same transaction set (idempotency); Verify retransmit is blocked if current status is RECONCILED (no need to retransmit a reconciled file)
**Deliverable:** SettlementBatchRetransmitService.java + unit tests
**Acceptance / logic checks:**
- First retransmit: new settlement_file row with filename ZP0061_20260605_02.dat.pgp; original _01 row retained
- File bytes for retransmit are byte-identical to original (same sorted merchant order, same amounts)
- Retransmitting a RECONCILED batch throws IllegalStateException('cannot retransmit reconciled batch')
- SEQ=03 on third retransmit (two prior settlement_file rows with seq 01 and 02)
- After successful retransmit, settlement_batch.status=TRANSMITTED and transmitted_at updated to new timestamp
**Depends on:** 9.6-T11, 9.6-T10

### 9.6-T24 — Implement late-file monitoring and cutoff alerts for ZP0062/ZP0064  _(35 min)_
**Context:** SCH-06 §9.5 defines escalation cutoffs: ZP0062 must be received by 12:00 KST (P2 alert if missed); ZP0064 by 21:00 KST (P2 alert). A scheduled monitor checks at those cutoff times whether the corresponding settlement_batch row exists with status=RECEIVED. If not, it publishes OpsAlertEvent(P2, details). The monitor must fire exactly once per cutoff (not repeatedly). The cutoff monitor is independent of the polling job (T18) — it is a hard deadline check.
**Steps:** Implement SettlementCutoffMonitor.checkZP0062Cutoff(LocalDate settlementDate) and checkZP0064Cutoff; Check settlement_batch for (schemeId, ZP0062 or ZP0064, settlementDate, MORNING or AFTERNOON).status; if not RECEIVED by cutoff, publish OpsAlertEvent(P2); Schedule ZP0062 check at 12:00 KST Mon-Sat; ZP0064 check at 21:00 KST Mon-Sat; Use a flag (DB column or idempotency table) to ensure OpsAlert is published exactly once per date per file type; Write unit tests: file received before cutoff = no alert; file not received = 1 alert; monitor called twice = 1 alert (idempotency)
**Deliverable:** SettlementCutoffMonitor.java + unit tests
**Acceptance / logic checks:**
- ZP0062 received at 10:04 (before 12:00 cutoff): monitor at 12:00 publishes no OpsAlert
- ZP0062 not received by 12:00: monitor publishes exactly 1 OpsAlertEvent(P2) containing file_type='ZP0062' and settlement_date
- Monitor called twice for same date and file (retry scenario): OpsAlert published exactly once
- ZP0064 cutoff at 21:00 KST for the same settlement_date is independent of ZP0062 check
- Monitor does not fire on Sunday (cron MON-SAT constraint)
**Depends on:** 9.6-T18

### 9.6-T25 — Implement Ops exception queue service for settlement discrepancies  _(40 min)_
**Context:** Settlement discrepancies (DISCREPANCY, MISSING_GME, MISSING_SCHEME reconciliation_items) are surfaced in the Ops Admin portal exception queue (PRD-07 §UC-04-03). The service provides the query and resolution operations. Resolution: Ops can mark an item as RESOLVED with a resolution_note; the action is audit-logged. ESCALATED status is set when Ops cannot resolve and escalates to GME Finance. All resolution actions use the standard audit trail (actor, timestamp, previous_value).
**Steps:** Implement ExceptionQueueService.listUnresolved(Long batchId): returns all reconciliation_items with resolution_status=UNRESOLVED ordered by match_status (DISCREPANCY first); Implement resolve(Long itemId, String operatorId, String resolutionNote): sets resolution_status=RESOLVED, resolved_by, resolved_at, persists audit_log entry with previous resolution_status=UNRESOLVED; Implement escalate(Long itemId, String operatorId, String note): sets resolution_status=ESCALATED; Write unit tests for list, resolve, and escalate; verify audit_log entry is created on resolve
**Deliverable:** ExceptionQueueService.java + unit tests
**Acceptance / logic checks:**
- listUnresolved for a batch with 2 DISCREPANCY + 1 MISSING_SCHEME returns 3 items, DISCREPANCY items first
- resolve() sets resolution_status=RESOLVED, resolved_at is non-null, resolution_note persisted
- resolve() creates an audit_log row with entity_type='reconciliation_item', field='resolution_status', previous_value='UNRESOLVED', new_value='RESOLVED', actor=operatorId
- Attempting to resolve an already-RESOLVED item throws IllegalStateException('item already resolved')
- escalate() sets resolution_status=ESCALATED and creates audit_log entry; does not set resolved_at
**Depends on:** 9.6-T05, 9.6-T13

### 9.6-T26 — Unit tests: ZP0061/ZP0063 generation with net vs gross settlement numeric vectors  _(45 min)_
**Context:** Explicit numeric test vectors for ZP0061/ZP0063 generation covering both settlement types. Test data: (A) Domestic txn: partner=GME_Remit (LOCAL), target_payout=10000 KRW, service_charge=500 KRW, settlement_type='N'; expected ZP0061 record: gross_txn_amount=10000, merchant_fee_total=500, net_settlement_amount=10000. (B) International txn: partner=SendMN (OVERSEAS), target_payout=50000 KRW, settlement_type='G'; expected ZP0061 record: gross_txn_amount=50000, merchant_fee_total=0, net_settlement_amount=50000. (C) Mixed merchant: 1 domestic + 1 international txn for same merchant should be in separate records (domestic partners do not mix with international in the same merchant settlement record — assert this separation).
**Steps:** Write ZP0061GenerationVectorTest.java with test cases A, B, and C above; For each test: set up transaction objects, call ZP0061MorningSettlementRequestBuilder.build(), parse output bytes, assert field values; Test C: verify that a domestic txn and international txn for merchant M001 do NOT merge into one record (they have different settlement_type; assert two separate records or verify the spec intention and document it); Add a fourth test: zero transactions for a settlement_date produces an empty file (0 data records, header + trailer present); Run tests; all must pass green
**Deliverable:** ZP0061GenerationVectorTest.java (all tests passing)
**Acceptance / logic checks:**
- Test A: ZP0061 record for domestic txn has net_settlement_amount=10000, merchant_fee_total=500, settlement_type='N'
- Test B: ZP0061 record for international txn has net_settlement_amount=50000, merchant_fee_total=0, settlement_type='G'
- Test C: two records present for merchant M001 (one per settlement_type) or single record with spec-compliant merging — behaviour must be explicitly documented in test
- Zero-transaction test: output byte array parses to 0 data records with valid header and trailer
- All 4 tests pass in CI without external dependencies (in-memory only)
**Depends on:** 9.6-T08, 9.6-T07

### 9.6-T27 — Unit tests: reconciliation engine vectors for ZP0062 result processing  _(40 min)_
**Context:** Test vectors for SettlementReconciliationService (T13) covering all match_status outcomes. Test data: settlement_date=2026-06-05. ZP0061 outbound has 3 merchants: M001 KRW 10000, M002 KRW 20000, M003 KRW 5000 (total 35000). ZP0062 result scenarios: (A) All 3 match exactly -> RECONCILED, 0 items. (B) M001 amount=9000 (mismatch -1000) -> 1 DISCREPANCY item, discrepancy_amount=1000. (C) M004 appears in ZP0062 but not in ZP0061 -> 1 MISSING_GME item. (D) M003 absent from ZP0062 -> 1 MISSING_SCHEME item. (E) All four anomalies combined: 1 DISCREPANCY + 1 MISSING_GME + 1 MISSING_SCHEME -> 3 items, OpsAlert published 3 times.
**Steps:** Write SettlementReconciliationVectorTest.java with test scenarios A through E; Mock ZP0061 outbound data and ZP0062 parsed result records for each scenario; Assert reconciliation_item count, match_status values, discrepancy_amount, and OpsAlertEvent publish count; Verify settlement_batch.status=RECONCILED in all scenarios (even those with discrepancies); Run all 5 tests; all must pass
**Deliverable:** SettlementReconciliationVectorTest.java (all 5 tests passing)
**Acceptance / logic checks:**
- Scenario A: 0 reconciliation_item rows, status=RECONCILED, 0 OpsAlerts
- Scenario B: 1 DISCREPANCY item, discrepancy_amount=1000.0000 (gme_amount=10000, scheme_amount=9000), 1 OpsAlert(P1)
- Scenario C: 1 MISSING_GME item with scheme_ref='M004', txn_ref=NULL
- Scenario D: 1 MISSING_SCHEME item with txn_ref set to M003 transactions, scheme_ref=NULL
- Scenario E: 3 reconciliation_items total (1 DISCREPANCY, 1 MISSING_GME, 1 MISSING_SCHEME), 3 OpsAlerts(P1)
**Depends on:** 9.6-T13

### 9.6-T28 — Unit tests: ZP0065 detail builder and detail reconciliation vectors  _(35 min)_
**Context:** Numeric test vectors for ZP0065 detail file and its reconciliation. Setup: settlement_date=2026-06-05; 2 transactions in MORNING batch (M001 KRW 10000, M002 KRW 30000) + 1 transaction in AFTERNOON batch (M001 KRW 20000). ZP0065 should have 3 records. Reconciliation vector: (A) all 3 lines match internal ledger -> 0 items. (B) ZP0065 line with zeropay_txn_ref='ZP001' has payout_amount_krw=9000 but GME txn has target_payout=10000 -> DISCREPANCY, discrepancy_amount=1000. (C) ZP0065 contains zeropay_txn_ref='ZP999' not in GME DB -> MISSING_GME. (D) GME txn ZP002 not in ZP0065 -> MISSING_SCHEME.
**Steps:** Write ZP0065DetailVectorTest.java: test ZP0065 output has 3 records with correct payout_amount_krw, merchant_id, settlement_batch_ref values; Write ZP0065ReconciliationVectorTest.java: scenarios A through D using ZP0065DetailReconciler (T21); For the detail builder test: verify settlement_batch_ref in each record matches the correct batch filename (MORNING txns -> ZP0061 ref, AFTERNOON -> ZP0063 ref); Run all tests; all must pass
**Deliverable:** ZP0065DetailVectorTest.java + ZP0065ReconciliationVectorTest.java (all tests passing)
**Acceptance / logic checks:**
- ZP0065 output has exactly 3 records for the described input; sorted by merchant_id ASC then txn_time ASC
- MORNING transaction settlement_batch_ref='ZP0061_20260605_01'; AFTERNOON='ZP0063_20260605_01'
- Scenario B: DISCREPANCY item with discrepancy_amount=1000.0000
- Scenario C: MISSING_GME item with scheme_ref='ZP999'
- Scenario D: MISSING_SCHEME item with txn_ref set to GME transaction reference for ZP002
**Depends on:** 9.6-T14, 9.6-T21

### 9.6-T29 — Integration test: full daily settlement batch lifecycle (ZP0061 through ZP0065/ZP0066)  _(60 min)_
**Context:** End-to-end integration test covering the complete daily settlement cycle for 2026-06-05. Scope: 3 domestic txns (GME Remit, KRW 10000 each) + 2 international txns (SendMN, KRW 50000 each) all with status=SETTLEMENT_REGISTERED and settlement_batch_id=NULL. Steps: run morning job -> verify ZP0061 generated with 5 txns across merchants -> simulate ZP0062 receipt with matching amounts -> verify RECONCILED status -> run afternoon job (1 new txn) -> ZP0063 generated -> simulate ZP0064 -> RECONCILED -> run 22:00 job -> ZP0065 has 6 records, ZP0066 has 0 (no refunds). All DB changes verified. No real SFTP; use in-memory stub.
**Steps:** Set up embedded Postgres (Testcontainers) with schema from migrations T01-T03; Insert 5 transactions in SETTLEMENT_REGISTERED status; Call MorningSettlementBatchJob.run(); assert ZP0061 batch GENERATED, 5 txns assigned; Inject ZP0062 file stub (all amounts match); call AfternoonResultProcessor.process equivalent for morning; assert RECONCILED; Insert 1 afternoon txn; call AfternoonSettlementBatchJob.run(); assert ZP0063 GENERATED; Inject ZP0064 stub; assert ZP0063 RECONCILED; call DetailSettlementBatchJob.run(); assert ZP0065 has 6 records, ZP0066 has 0
**Deliverable:** SettlementBatchIntegrationTest.java (all assertions passing with Testcontainers)
**Acceptance / logic checks:**
- After morning job: 5 transactions have settlement_batch_id = ZP0061 batch id, settlement_window='MORNING'
- ZP0061 settlement_batch.transaction_count=5, total_amount = sum of 5 net_settlement_amounts
- After ZP0062 injection: ZP0061 batch status=RECONCILED, 0 reconciliation_item rows
- ZP0065 contains exactly 6 records (5 morning + 1 afternoon) with correct settlement_batch_ref values
- ZP0066 file generated with 0 data records (valid empty file, header + trailer only)
**Depends on:** 9.6-T16, 9.6-T17, 9.6-T19, 9.6-T20, 9.6-T23

### 9.6-T30 — Integration test: idempotency and retransmission scenarios  _(55 min)_
**Context:** Validates idempotency guarantees and retransmission for WBS 9.6 components. Scenarios: (1) Run morning batch job twice for same date -> assert exactly 1 settlement_batch row, 5 (not 10) txns assigned. (2) ZP0061 SFTP fails 3 times -> status=ERROR, OpsAlert(P1) published, 0 ZP0065 txns re-selected. (3) Retransmit from ERROR state -> new settlement_file row with SEQ=02, same batch id, same file content. (4) Reprocess ZP0062 result file twice -> 0 duplicate reconciliation_items.
**Steps:** Write IdempotencyIntegrationTest.java using Testcontainers Postgres and mocked SFTP; Scenario 1: call generateMorning twice; assert unique constraint holds, 1 batch row, transaction assignment is idempotent; Scenario 2: mock SFTP to fail 3x; assert OpsAlert count=1, settlement_batch.status=ERROR; Scenario 3: call retransmit(batchId); assert new settlement_file row, status=TRANSMITTED, SEQ=02 in filename; Scenario 4: call reconcile with same ZP0062 parsed result twice; assert reconciliation_item count does not increase on second call
**Deliverable:** IdempotencyIntegrationTest.java (all 4 scenarios passing)
**Acceptance / logic checks:**
- Scenario 1: SELECT COUNT(*) FROM settlement_batch WHERE file_type='ZP0061' AND settlement_date='2026-06-05' = 1 after two job runs
- Scenario 2: OpsAlertEvent published exactly once; settlement_batch.status='ERROR'
- Scenario 3: settlement_file rows for the batch = 2 (seq 01 failed, seq 02 transmitted); batch status='TRANSMITTED'
- Scenario 4: calling reconcile twice produces the same number of reconciliation_item rows as calling it once
- Transaction assignment after scenario 1: COUNT of txns with settlement_batch_id NOT NULL = 5, not 10
**Depends on:** 9.6-T29, 9.6-T23

### 9.6-T31 — Add settlement_batch_id and settlement_window to transaction event trail (step 7)  _(35 min)_
**Context:** The 8-step transaction event trail (SAD-02 §6.1 / Admin portal §8.5.3) records key lifecycle timestamps. Step 7 is settlement_recorded (UTC, delta ms). When a transaction is assigned to a settlement_batch (T10), a transaction_event row must be inserted: step=7, event_type='settlement_recorded', metadata={settlement_batch_id: X, file_type: 'ZP0061', window: 'MORNING'}. This enables the Admin portal settlement linkage panel (PRD-07 §8.5.5) showing which ZP0061/ZP0065 batch a transaction is in.
**Steps:** In SettlementBatchGenerationService (T10), after the bulk UPDATE on transaction.settlement_batch_id, insert transaction_event rows (step=7, event_type='settlement_recorded') for each assigned transaction in the same DB transaction; Metadata JSON: {settlement_batch_id, file_type, window, settlement_date}; Write unit tests: after generateMorning, 5 txns each have 1 transaction_event with step=7 and correct metadata; Verify that re-running the job (idempotency path) does NOT insert duplicate step-7 events; Verify existing step-6 (transaction_committed) events are not affected
**Deliverable:** Updated SettlementBatchGenerationService.java + unit tests for event trail step 7
**Acceptance / logic checks:**
- Each of 5 assigned transactions has exactly 1 transaction_event with step=7, event_type='settlement_recorded'
- transaction_event.metadata contains settlement_batch_id matching the new batch, file_type='ZP0061', window='MORNING'
- Second invocation (idempotency): no additional transaction_event rows inserted (step-7 event count per txn remains 1)
- Step-6 (transaction_committed) event count is unchanged after settlement batch generation
- transaction_event.created_at is within 1 second of the batch generation timestamp
**Depends on:** 9.6-T10

### 9.6-T32 — Add settlement batch status to Admin portal settlement dashboard (batch status view)  _(45 min)_
**Context:** The Admin portal Settlement & Revenue screen (PRD-07 §4.9 / §11.2.2) shows a Batch Status View with one row per settlement date per file type. Required columns: Settlement Date, Transaction Count, Gross Payout (KRW), GME Fee Share (KRW), Net Settlement (KRW), ZP0061 Status (Sent/Matched/Discrepancy), ZP0062 Status (Received/Not yet received), Reconciliation Status (Matched/Discrepancy/Pending). The backend API endpoint GET /internal/v1/settlement/batches?date={date}&schemeId={id} must return this data from settlement_batch and reconciliation_item tables.
**Steps:** Implement GET /internal/v1/settlement/batches handler: query settlement_batch rows for the given date and scheme, join with reconciliation_item count per batch, compute aggregates (sum total_amount, count transactions); Map BatchStatus to UI labels: TRANSMITTED='Sent', RECONCILED='Matched', ERROR='Discrepancy' for ZP0061 column; RECEIVED='Received', null='Not yet received' for ZP0062 column; Include Domestic and International net settlement totals separately (domestic=net, international=gross); Secure endpoint with ROLE_OPS_OPERATOR or ROLE_FINANCE_ANALYST (read-only); Write unit tests for the controller with mocked service
**Deliverable:** SettlementDashboardController.java + SettlementBatchSummaryDto.java + unit tests
**Acceptance / logic checks:**
- ZP0061 status=TRANSMITTED maps to 'Sent' in response; RECONCILED maps to 'Matched'; ERROR maps to 'Discrepancy'
- ZP0062 not yet received (no RECEIVED batch row): ZP0062_status='Not yet received' in response
- Response includes transaction_count=5 and gross_payout_krw=70000 for the test settlement date
- Endpoint returns 403 for a request with ROLE_PARTNER (unauthorized)
- Reconciliation status = 'Discrepancy' when any reconciliation_item with match_status != MATCHED exists for the batch
**Depends on:** 9.6-T04, 9.6-T05, 9.6-T25

### 9.6-T33 — Add settlement linkage panel to transaction detail view (Admin portal)  _(40 min)_
**Context:** The Admin portal transaction detail screen (PRD-07 §8.5.5) must show a Settlement Linkage panel: which ZeroPay batch files the transaction was included in (ZP0011/ZP0061/ZP0065 reference, batch date, registration result). The backend endpoint GET /internal/v1/transactions/{txn_id}/settlement-linkage must return: settlement_batch_id, file_type (ZP0061 or ZP0063), settlement_date, batch_status, filename from settlement_file, and registration_status from the transaction row (SETTLEMENT_REGISTERED or REGISTRATION_FAILED etc).
**Steps:** Implement GET /internal/v1/transactions/{txn_id}/settlement-linkage handler: join transaction -> settlement_batch (via settlement_batch_id) -> settlement_file (latest file for batch); Also include the ZP0011 batch reference (from the existing batch_file_log or settlement_batch for ZP0011) and ZP0065 batch reference (from settlement_batch for ZP0065 where this txn's zeropay_txn_ref appears); Return 404 if transaction not settled yet (settlement_batch_id IS NULL); Enforce partner_id ownership: transaction must belong to the requesting operator's visible scope; Write unit tests covering: settled txn, unsettled txn (404), unauthorized access (403)
**Deliverable:** TransactionSettlementLinkageController.java + SettlementLinkageDto.java + unit tests
**Acceptance / logic checks:**
- Settled transaction returns dto with file_type='ZP0061', settlement_date='2026-06-05', batch_status='RECONCILED', filename='ZP0061_20260605_01.dat.pgp'
- Unsettled transaction (settlement_batch_id IS NULL) returns HTTP 404
- Requesting settlement linkage for a txn_id belonging to a different partner returns 403
- Response includes all three file references: ZP0011_ref, ZP0061_or_ZP0063_ref, ZP0065_ref
- registration_status='SETTLEMENT_REGISTERED' for a successfully registered transaction
**Depends on:** 9.6-T31, 9.6-T32

### 9.6-T34 — Ops runbook entry: settlement file delivery procedures and cutoff escalation  _(30 min)_
**Context:** OPS-13 references a settlement processing runbook. A concise in-code runbook document is required covering the daily ZP006x batch procedures, cutoff times, escalation paths, and manual intervention steps. This ensures a developer or ops engineer can execute the batch without project context. Content must match the spec exactly: ZP0061 internal deadline 04:30 KST (due ~05:00), ZP0063 13:30 KST (due ~14:00), ZP0065/ZP0066 21:30 KST (due ~22:00). Escalation: ZP0061 late = P1 (affects merchant crediting). Exception queue access path. Retransmit procedure.
**Steps:** Create docs/runbooks/settlement-zp006x.md with sections: Overview, Daily Timeline, Batch Job Reference, Pre-condition Checks, Exception Handling, Retransmission Procedure, Escalation Matrix; Include exact cron expressions and their KST equivalents for all 5 batch jobs; Include the SettlementBatchRetransmitService API call sequence for manual retransmit; List all OpsAlertEvent priority codes and their escalation owners; Review for accuracy against spec; add to the repository alongside other runbooks
**Deliverable:** docs/runbooks/settlement-zp006x.md
**Acceptance / logic checks:**
- Document states ZP0061 internal deadline as 04:30 KST and ZeroPay deadline as ~05:00 KST
- Document lists P1 escalation for ZP0061 late delivery with note 'affects same-day merchant crediting'
- Retransmission procedure includes step to call SettlementBatchRetransmitService.retransmit(batchId) and verify new SEQ filename
- Exception queue access path references ExceptionQueueService.listUnresolved(batchId) and Admin portal UC-04-03 screen
- Escalation matrix distinguishes P1 (ZP0061/ZP0063 late, SFTP failure, registration failure blocking settlement) from P2 (ZP0062/ZP0064 late, ZP0065/ZP0066 late)
**Depends on:** 9.6-T17, 9.6-T19, 9.6-T16, 9.6-T23, 9.6-T25


## WBS 9.7 — Batch scheduler & window monitoring
### 9.7-T01 — Create batch_job_definition table migration  _(40 min)_
**Context:** GMEPay+ needs a config-driven batch scheduler for ZeroPay ZP00xx jobs. Each job (JOB-ZP-01 through JOB-ZP-13) has a fixed file type, direction, KST window, criticality, and dependency chain. All times are stored in UTC; KST = UTC+9. The scheduler reads job definitions from DB so that new schemes can be added without code changes.
**Steps:** Create Flyway migration V9_7_001__batch_job_definition.sql; Define table batch_job_definition with columns: id BIGINT PK, job_id VARCHAR(20) UNIQUE NOT NULL, scheme_id BIGINT FK qr_scheme, file_types VARCHAR(100) NOT NULL, direction VARCHAR(20) NOT NULL CHECK(GME_TO_ZP,ZP_TO_GME), window_utc TIME NOT NULL, alert_threshold_utc TIME NOT NULL, criticality VARCHAR(10) NOT NULL CHECK(CRITICAL,HIGH,MEDIUM,LOW), dependency_job_id VARCHAR(20) FK SELF NULLABLE, is_weekly BOOLEAN NOT NULL DEFAULT FALSE, enabled BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ; Add CHECK constraint: direction IN ('GME_TO_ZP','ZP_TO_GME'); Add index idx_bjd_scheme_enabled on (scheme_id, enabled); Seed the 13 ZeroPay jobs per OPS-13 schedule: JOB-ZP-01 ZP0011 GME_TO_ZP 17:00UTC(02:00KST) alert 16:45UTC CRITICAL; JOB-ZP-02 ZP0021 GME_TO_ZP 17:00UTC alert 16:45UTC CRITICAL; JOB-ZP-03 ZP0012 ZP_TO_GME 20:00UTC(05:00KST) alert 20:30UTC CRITICAL dep JOB-ZP-01; JOB-ZP-04 ZP0022 ZP_TO_GME 20:00UTC alert 20:30UTC CRITICAL dep JOB-ZP-02; JOB-ZP-05 ZP0061 GME_TO_ZP 20:00UTC alert 20:15UTC CRITICAL dep JOB-ZP-03; JOB-ZP-06 ZP0062 ZP_TO_GME 01:00UTC(10:00KST) alert 01:30UTC CRITICAL dep JOB-ZP-05; JOB-ZP-07 ZP0063 GME_TO_ZP 05:00UTC(14:00KST) alert 05:15UTC HIGH; JOB-ZP-08 ZP0064 ZP_TO_GME 10:30UTC(19:30KST) alert 10:30UTC HIGH dep JOB-ZP-07; JOB-ZP-09 ZP0065,ZP0066 GME_TO_ZP 13:00UTC(22:00KST) alert 13:15UTC HIGH; JOB-ZP-10 ZP0041,ZP0045,ZP0047 ZP_TO_GME 23:00UTC(08:00KST) alert 23:00UTC MEDIUM is_weekly FALSE; JOB-ZP-11 ZP0043 ZP_TO_GME 23:00UTC MEDIUM; JOB-ZP-12 ZP0051,ZP0055 ZP_TO_GME 23:00UTC MEDIUM is_weekly TRUE; JOB-ZP-13 ZP0053 ZP_TO_GME 23:00UTC MEDIUM is_weekly TRUE
**Deliverable:** Flyway migration file db/migrations/V9_7_001__batch_job_definition.sql with table DDL and 13 seed rows for ZeroPay jobs
**Acceptance / logic checks:**
- Migration applies cleanly on a fresh schema with no errors
- SELECT COUNT(*) FROM batch_job_definition WHERE scheme_id = (zeropay id) returns 13
- JOB-ZP-05 row has dependency_job_id = 'JOB-ZP-03' and criticality = 'CRITICAL'
- JOB-ZP-12 row has is_weekly = TRUE and direction = 'ZP_TO_GME'
- window_utc for JOB-ZP-01 is 17:00:00 (confirming 02:00 KST prev-day UTC equiv)

### 9.7-T02 — Create batch_job_run table migration  _(30 min)_
**Context:** Every execution of a batch job must persist a run record for idempotency checks and audit. The table batch_job_run tracks each execution: job definition, business date (KST), run status, start/end times, retry count, and error detail. A UNIQUE constraint on (job_id, business_date, run_seq) prevents duplicate runs for the same logical date. run_seq starts at 1; retransmissions increment it.
**Steps:** Create Flyway migration V9_7_002__batch_job_run.sql; Define table batch_job_run: id UUID PK DEFAULT gen_random_uuid(), job_definition_id BIGINT FK batch_job_definition NOT NULL, job_id VARCHAR(20) NOT NULL, business_date DATE NOT NULL, run_seq INT NOT NULL DEFAULT 1, status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK(PENDING,RUNNING,SUCCESS,FAILED,SKIPPED), started_at TIMESTAMPTZ, completed_at TIMESTAMPTZ, retry_count INT NOT NULL DEFAULT 0, error_code VARCHAR(50), error_detail TEXT, settlement_batch_id BIGINT FK settlement_batch NULLABLE, created_at TIMESTAMPTZ NOT NULL DEFAULT now(); Add UNIQUE constraint uq_bjr_job_date_seq on (job_id, business_date, run_seq); Add index idx_bjr_job_date on (job_id, business_date) for idempotency lookups; Add index idx_bjr_status_created on (status, created_at) for monitoring queries
**Deliverable:** Flyway migration file db/migrations/V9_7_002__batch_job_run.sql
**Acceptance / logic checks:**
- Migration applies cleanly after V9_7_001
- INSERT of two rows with same (job_id, business_date, run_seq) raises unique violation
- Status CHECK constraint rejects value 'DONE'
- SELECT with (job_id='JOB-ZP-01', business_date='2026-06-05', run_seq=1) returns at most 1 row
- Foreign key to batch_job_definition is enforced on INSERT
**Depends on:** 9.7-T01

### 9.7-T03 — Create batch_window_alert table migration  _(25 min)_
**Context:** When a batch job misses its alert threshold time, the system must log an alert record and prevent duplicate firing. Table batch_window_alert captures: job_id, business_date, alert_severity (P1/P2/P3), alert_fired_at, channels notified (JSON array of 'PAGERDUTY','SLACK','EMAIL'), and acknowledged_at. One row per job per business_date per severity level; prevents re-alerting on every polling cycle.
**Steps:** Create Flyway migration V9_7_003__batch_window_alert.sql; Define table batch_window_alert: id BIGINT GENERATED ALWAYS AS IDENTITY PK, job_id VARCHAR(20) NOT NULL, business_date DATE NOT NULL, alert_severity VARCHAR(5) NOT NULL CHECK('P1','P2','P3'), alert_fired_at TIMESTAMPTZ NOT NULL DEFAULT now(), channels_notified JSONB NOT NULL DEFAULT '[]', acknowledged_at TIMESTAMPTZ, acknowledged_by VARCHAR(120), created_at TIMESTAMPTZ NOT NULL DEFAULT now(); Add UNIQUE constraint uq_bwa_job_date_severity on (job_id, business_date, alert_severity); Add index idx_bwa_unack on (business_date, acknowledged_at) WHERE acknowledged_at IS NULL; Add FK batch_window_alert(job_id) REFERENCES batch_job_definition(job_id) ON DELETE RESTRICT
**Deliverable:** Flyway migration file db/migrations/V9_7_003__batch_window_alert.sql
**Acceptance / logic checks:**
- Migration applies cleanly after V9_7_002
- Inserting same (job_id, business_date, alert_severity) twice raises unique violation, preventing double-alert
- channels_notified JSONB accepts '["PAGERDUTY","SLACK"]' value
- Partial index on unacknowledged alerts is present in pg_indexes
- FK constraint rejects job_id not present in batch_job_definition
**Depends on:** 9.7-T02

### 9.7-T04 — Define BatchJobDefinition and BatchJobRun JPA entities  _(45 min)_
**Context:** The batch scheduler is in the Batch/SFTP Worker service (Java/Spring). Two JPA entities map to the tables created in T01 and T02. BatchJobDefinition holds static config (job_id, scheme, file_types, direction, window_utc, alert_threshold_utc, criticality, dependency_job_id, is_weekly, enabled). BatchJobRun is the per-execution audit record. Use Hibernate; columns match exact DB names via @Column annotations.
**Steps:** Create BatchJobDefinition.java in package com.gmepay.batch.domain; Annotate with @Entity, @Table(name='batch_job_definition'); map all columns; add @OneToOne self-ref for dependency; add getters/setters or use @Data; Create BatchJobRun.java in same package; Annotate BatchJobRun; add @ManyToOne to BatchJobDefinition; status field as enum BatchJobStatus{PENDING,RUNNING,SUCCESS,FAILED,SKIPPED}; Create BatchWindowAlert.java entity for the alert table (from T03); Add repositories BatchJobDefinitionRepository (findBySchemeIdAndEnabled), BatchJobRunRepository (findByJobIdAndBusinessDate), BatchWindowAlertRepository (existsByJobIdAndBusinessDateAndAlertSeverity)
**Deliverable:** Three entity classes and three Spring Data JPA repositories under com.gmepay.batch.domain and com.gmepay.batch.repository
**Acceptance / logic checks:**
- Spring context loads without Hibernate schema validation errors against the migrated DB
- BatchJobDefinitionRepository.findBySchemeIdAndEnabled(schemeId, true) returns 13 rows for ZeroPay
- BatchJobRunRepository.findByJobIdAndBusinessDate('JOB-ZP-01', LocalDate.of(2026,6,5)) returns empty list on a clean DB
- BatchWindowAlertRepository.existsByJobIdAndBusinessDateAndAlertSeverity('JOB-ZP-01', date, 'P1') returns false before first alert
- BatchJobStatus enum covers all 5 status values and toString matches DB CHECK constraint values
**Depends on:** 9.7-T03

### 9.7-T05 — Implement BatchScheduleService: cron trigger registration  _(50 min)_
**Context:** The Batch/SFTP Worker must register Spring @Scheduled cron expressions for each enabled job at application startup. Cron expressions are derived from window_utc in batch_job_definition. Because the worker runs as a single Kubernetes CronJob (OPS-13 §10.1), it must also support manual trigger via an internal endpoint. All cron expressions are in UTC (e.g. JOB-ZP-01 window_utc=17:00 -> '0 0 17 * * MON-SAT'). ZeroPay runs Mon-Sat; Sunday and holidays use is_weekly flag logic.
**Steps:** Create BatchScheduleService.java in com.gmepay.batch.scheduler; On @PostConstruct, load all enabled BatchJobDefinition rows for the active scheme; For each definition, compute cron expression: '0 {min} {hour} * * MON-SAT' from window_utc; weekly jobs use '0 {min} {hour} * * SUN'; Register cron tasks dynamically using TaskScheduler.schedule(Runnable, CronTrigger); Expose internal method triggerJobManually(String jobId, LocalDate businessDate) for admin rerun; Log each registered cron at INFO: 'Registered cron for JOB-ZP-01: 0 0 17 * * MON-SAT (UTC)'
**Deliverable:** BatchScheduleService.java with dynamic cron registration and manual trigger support
**Acceptance / logic checks:**
- On startup, 13 cron tasks are registered (verify via log output or ScheduledTaskHolder bean)
- JOB-ZP-12 (weekly, window_utc=23:00) registers cron '0 0 23 * * SUN'
- JOB-ZP-01 (window_utc=17:00, Mon-Sat) registers cron '0 0 17 * * MON-SAT'
- triggerJobManually('JOB-ZP-01', LocalDate.now()) invokes the job handler without throwing
- Disabling a job (enabled=false) in DB and restarting causes that cron to not register
**Depends on:** 9.7-T04

### 9.7-T06 — Implement BatchJobRunner: idempotency guard and run-record lifecycle  _(55 min)_
**Context:** Before executing any job, the runner must check if a SUCCESS run already exists for (job_id, business_date). If so, log 'already processed' and return without action. If a RUNNING record exists for > 30 minutes, treat it as stale and reset to FAILED before retrying. The run sequence (run_seq) starts at 1 and increments on each retry attempt. OPS-13 §6.3: up to 3 automatic retries with exponential back-off 30s, 120s, 300s.
**Steps:** Create BatchJobRunner.java in com.gmepay.batch.scheduler; Method executeJob(String jobId, LocalDate businessDate): check BatchJobRunRepository for existing SUCCESS -> return SKIPPED; check for RUNNING > 30 min -> update to FAILED; Compute next run_seq = max existing run_seq + 1 (default 1 if none); Insert new BatchJobRun with status=RUNNING, started_at=now(); Delegate to BatchJobDispatcher.dispatch(jobId, businessDate, runId); On success update status=SUCCESS, completed_at=now(); on exception update status=FAILED, error_detail=exception.getMessage(); Implement retry loop: catch exception, sleep back-off (30s/120s/300s for retry 1/2/3), retry up to 3 times; after 3 failures leave status=FAILED
**Deliverable:** BatchJobRunner.java with idempotency check, run-record lifecycle management, and retry loop
**Acceptance / logic checks:**
- Calling executeJob twice for same (job_id, date) where first run has SUCCESS returns SKIPPED on second call with no new run record inserted
- A RUNNING record with started_at 35 minutes ago is reset to FAILED before a new run starts
- run_seq increments: first attempt=1, first retry=2, second retry=3
- After 3 failures the record has status=FAILED and retry_count=3
- Back-off delays are 30s, 120s, 300s (verify with a test clock mock)
**Depends on:** 9.7-T05

### 9.7-T07 — Implement dependency-chain enforcement in batch dispatcher  _(45 min)_
**Context:** OPS-13 §6.2 dependency chain: JOB-ZP-05 (ZP0061) must not run unless JOB-ZP-03 (ZP0012) and JOB-ZP-01 (ZP0011) both have SUCCESS for that business_date. Similarly JOB-ZP-03 depends on JOB-ZP-01. The dependency_job_id column in batch_job_definition carries the immediate prerequisite. If the prerequisite is not SUCCESS, the current job must wait (status=PENDING) and log a warning. SCH-06 §8.2: if ZP0012 or ZP0022 indicate registration failures, settlement generation for affected transactions must be held.
**Steps:** Create BatchJobDispatcher.java in com.gmepay.batch.scheduler; Method dispatch(String jobId, LocalDate businessDate, UUID runId): load BatchJobDefinition for jobId; If definition.dependencyJobId is not null, check BatchJobRunRepository for SUCCESS of dependency on same businessDate; If dependency not SUCCESS: update run to status=PENDING, log WARN 'JOB-ZP-05 waiting on JOB-ZP-03 for 2026-06-05'; throw DependencyNotMetException; If dependency SUCCESS: proceed to delegate to scheme-specific handler via BatchJobHandler interface; Expose a resolvePending(LocalDate businessDate) method: re-check all PENDING runs whose dependency is now SUCCESS and re-trigger them
**Deliverable:** BatchJobDispatcher.java with dependency enforcement and pending-job resolution
**Acceptance / logic checks:**
- dispatch('JOB-ZP-05', date, runId) where JOB-ZP-03 has no SUCCESS for that date throws DependencyNotMetException and sets run status=PENDING
- dispatch('JOB-ZP-05', date, runId) where JOB-ZP-03 has SUCCESS proceeds without exception
- resolvePending(date) re-triggers JOB-ZP-05 after JOB-ZP-03 succeeds
- JOB-ZP-07 (no dependency) dispatches immediately without dependency check
- JOB-ZP-01 (no dependency) dispatches immediately
**Depends on:** 9.7-T06

### 9.7-T08 — Implement BatchWindowMonitor: missed-window detection  _(50 min)_
**Context:** OPS-13 §6.4 defines alert thresholds: JOB-ZP-01/02 not submitted by 01:45 KST (16:45 UTC prev day) -> P1; ZP0012/22 not received by 05:30 KST (20:30 UTC) -> P1; ZP0061 not submitted by 05:15 KST (20:15 UTC) -> P1; ZP0062 not received by 10:30 KST (01:30 UTC next day) -> P1; ZP0063 not submitted by 14:15 KST (05:15 UTC) -> P2; ZP0064 not received by 19:30 KST (10:30 UTC) -> P2; ZP0065/66 not submitted by 22:15 KST (13:15 UTC) -> P2; merchant/QR sync not received by 08:00 KST (23:00 UTC) -> P3. The monitor polls every 5 minutes.
**Steps:** Create BatchWindowMonitor.java in com.gmepay.batch.monitor; @Scheduled(fixedRate=300000) method checkWindows(): compute current UTC time and current KST business date; For each enabled BatchJobDefinition, check if alert_threshold_utc has passed for today; Query BatchJobRunRepository: does a SUCCESS run exist for (job_id, businessDate)?; If threshold passed and no SUCCESS: check BatchWindowAlertRepository for existing alert for (job_id, businessDate, severity); if none exists, fire alert and insert record; Fire alert via AlertingService.sendAlert(jobId, businessDate, severity, channels) where channels come from criticality mapping: CRITICAL->P1->[PAGERDUTY,SLACK], HIGH->P2->[SLACK], MEDIUM->P3->[EMAIL]
**Deliverable:** BatchWindowMonitor.java with 5-minute polling, threshold evaluation, and deduplication via batch_window_alert
**Acceptance / logic checks:**
- At 16:50 UTC with no SUCCESS for JOB-ZP-01 today, monitor fires P1 alert and inserts batch_window_alert row
- Second polling cycle 5 min later does NOT fire a second alert (idempotent via unique constraint)
- JOB-ZP-07 missed at 05:20 UTC fires P2 alert to SLACK only (not PagerDuty)
- JOB-ZP-10 merchant sync missed at 23:05 UTC fires P3 alert to EMAIL only
- A job that has SUCCESS before threshold fires no alert
**Depends on:** 9.7-T07

### 9.7-T09 — Implement AlertingService: PagerDuty + Slack + Email dispatch  _(45 min)_
**Context:** Alerts from BatchWindowMonitor must be sent to configured channels. OPS-13 §7.5.3: P1 -> PagerDuty + Ops Slack; P2 -> Ops Slack only; P3 -> Ops email. AlertingService is a lightweight adapter that reads channel config from environment variables (PAGERDUTY_ROUTING_KEY, SLACK_WEBHOOK_URL, OPS_EMAIL_SMTP_*). Each channel call is fire-and-forget with a local exception catch to prevent one channel failure from blocking others. All sent alerts are logged as structured JSON.
**Steps:** Create AlertingService.java in com.gmepay.batch.monitor; Method sendAlert(String jobId, LocalDate businessDate, String severity, List<String> channels): build alert payload with job_id, business_date, severity, message, fired_at_utc; For PAGERDUTY channel: POST to https://events.pagerduty.com/v2/enqueue with routing_key from env; payload: event_action=trigger, summary=message, severity=critical|error|warning; For SLACK channel: POST JSON {text: message} to SLACK_WEBHOOK_URL; For EMAIL channel: send via JavaMailSender to OPS_EMAIL_ADDRESS; Catch exceptions per channel; log ERROR if channel fails but continue; update channels_notified JSONB in batch_window_alert with successfully-sent channels; Log structured JSON at WARN: {event:'BATCH_WINDOW_ALERT', job_id, business_date, severity, channels_sent}
**Deliverable:** AlertingService.java with PagerDuty, Slack, and email dispatch; partial failure tolerance
**Acceptance / logic checks:**
- P1 alert sends to both PAGERDUTY and SLACK; if PagerDuty fails, SLACK still sends and channels_notified=["SLACK"]
- P2 alert sends to SLACK only and does not attempt PagerDuty
- P3 alert sends email only
- Alert payload includes job_id, business_date, and severity fields
- Structured log line emitted on every sendAlert call with event='BATCH_WINDOW_ALERT'
**Depends on:** 9.7-T08

### 9.7-T10 — Implement full-file suppression logic for weekly sync jobs  _(40 min)_
**Context:** SCH-06 §4: when a full-sync file (ZP0051 full merchant, ZP0055 franchise full, ZP0053 full QR) arrives on the same night as a delta file (ZP0041, ZP0045, ZP0043), the scheduler must detect the full file and suppress the delta upsert for that entity type. In batch_job_definition, ZP0051/ZP0055/ZP0053 are marked is_weekly=TRUE. The suppression check: if a SUCCESS run for the full-file job (JOB-ZP-12 or JOB-ZP-13) exists for tonight's processing date, skip the corresponding delta job (JOB-ZP-10 or JOB-ZP-11).
**Steps:** Add method shouldSuppressDeltaJob(String deltaJobId, LocalDate businessDate) to BatchJobDispatcher; Map delta-to-full job: ZP0041/ZP0045/ZP0047 (JOB-ZP-10) suppressed by JOB-ZP-12; ZP0043 (JOB-ZP-11) suppressed by JOB-ZP-13; Query BatchJobRunRepository: if SUCCESS for corresponding full-file job on same businessDate -> return true; In BatchJobRunner.executeJob, call shouldSuppressDeltaJob before dispatch; if true, set status=SKIPPED and log INFO 'Delta job JOB-ZP-10 suppressed: full file JOB-ZP-12 already processed for 2026-06-08'; Add a unit test covering the Sunday case where both jobs arrive
**Deliverable:** shouldSuppressDeltaJob method in BatchJobDispatcher and corresponding suppression path in BatchJobRunner
**Acceptance / logic checks:**
- On a Sunday with JOB-ZP-12 SUCCESS, JOB-ZP-10 is set to SKIPPED without executing
- On a weekday with no JOB-ZP-12 run, JOB-ZP-10 runs normally
- SKIPPED status is persisted in batch_job_run with no error fields set
- Log line 'Delta job JOB-ZP-10 suppressed' appears in INFO output
- JOB-ZP-11 suppression by JOB-ZP-13 follows the same logic independently
**Depends on:** 9.7-T07

### 9.7-T11 — Implement KST business-date resolver  _(35 min)_
**Context:** All batch jobs operate on KST (UTC+9) business dates. The nightly jobs (ZP0011, ZP0021 submit at 02:00 KST = 17:00 UTC previous day) process transactions for the prior KST business date. The batch scheduler must correctly resolve: given a UTC execution timestamp, what is the KST business date to process? Rule: if UTC wall-clock time is before 15:00 UTC (before midnight KST), business date = UTC date - 1 day in KST; if >= 15:00 UTC, business date = UTC date in KST. ZeroPay runs Mon-Sat; Sunday is not a settlement day.
**Steps:** Create KstBusinessDateResolver.java in com.gmepay.batch.util; Method resolveBusinessDate(Instant utcNow): convert to ZoneId.of('Asia/Seoul'); extract LocalDate in KST; that is the business date; Method isPreviousDayJob(String jobId): return true for JOB-ZP-01 and JOB-ZP-02 (they process prior-day transactions even though they run after midnight KST); Method isSettlementDay(LocalDate kstDate): return kstDate.getDayOfWeek() != SUNDAY (Mon-Sat); Method getProcessingDate(String jobId, Instant utcNow): for prior-day jobs return resolveBusinessDate(utcNow).minusDays(1); for same-day jobs return resolveBusinessDate(utcNow); Add constant KST_ZONE = ZoneId.of('Asia/Seoul')
**Deliverable:** KstBusinessDateResolver.java utility class
**Acceptance / logic checks:**
- resolveBusinessDate(Instant.parse('2026-06-04T17:30:00Z')) returns 2026-06-05 (17:30 UTC = 02:30 KST next day)
- resolveBusinessDate(Instant.parse('2026-06-04T14:30:00Z')) returns 2026-06-04 (14:30 UTC = 23:30 KST same day)
- isSettlementDay(LocalDate.of(2026,6,7)) returns false (Sunday)
- isSettlementDay(LocalDate.of(2026,6,6)) returns true (Saturday)
- getProcessingDate('JOB-ZP-01', Instant.parse('2026-06-05T17:30:00Z')) returns 2026-06-05 (prior-day job: processes June 5 txns at 02:30 KST June 6)
**Depends on:** 9.7-T05

### 9.7-T12 — Implement settlement-day guard: skip jobs on Sunday and holidays  _(40 min)_
**Context:** ZeroPay operates Mon-Sat (Assumption A-09, SCH-06 §7.3). Batch jobs must not run on Sundays. Korean public holidays behaviour is TBD but the scheduler must support a configurable holiday skip list. Store holidays in a new DB table batch_holiday (scheme_id, holiday_date, description). On job execution, check if business date is Sunday or in batch_holiday; if so, set run status=SKIPPED with reason='NON_SETTLEMENT_DAY' and do not proceed.
**Steps:** Create Flyway migration V9_7_004__batch_holiday.sql: table batch_holiday (id BIGINT PK, scheme_id BIGINT FK, holiday_date DATE NOT NULL, description VARCHAR(200), UNIQUE(scheme_id, holiday_date)); Create BatchHoliday.java JPA entity and BatchHolidayRepository; Add method isHoliday(Long schemeId, LocalDate date) to BatchHolidayRepository using EXISTS query; In BatchJobRunner.executeJob, after idempotency check: call KstBusinessDateResolver.isSettlementDay(businessDate) and batchHolidayRepository.isHoliday(schemeId, businessDate); if either false, insert SKIPPED run and return; Weekly sync jobs (is_weekly=TRUE) are exempted from settlement-day check (merchant data arrives on Sundays)
**Deliverable:** V9_7_004__batch_holiday.sql migration, BatchHoliday entity/repository, and settlement-day guard in BatchJobRunner
**Acceptance / logic checks:**
- executeJob('JOB-ZP-01', LocalDate.of(2026,6,7)) returns SKIPPED with reason='NON_SETTLEMENT_DAY' (Sunday)
- Inserting (zeropay_scheme_id, 2026-10-03) into batch_holiday causes executeJob for that date to return SKIPPED
- Weekly job JOB-ZP-12 on a holiday date still executes (is_weekly exemption)
- Unique constraint on (scheme_id, holiday_date) prevents duplicate holiday entries
- Log line at INFO includes 'reason=NON_SETTLEMENT_DAY' on skip
**Depends on:** 9.7-T11

### 9.7-T13 — Implement manual job rerun endpoint in Admin API  _(50 min)_
**Context:** OPS-13 §8.7: Ops must be able to manually rerun a failed batch job from the Admin System. The rerun accepts job_id and business_date and invokes BatchJobRunner.executeJob. The endpoint must enforce: only FAILED or SKIPPED runs can be rerun (not SUCCESS); rerun is idempotent (check for existing SUCCESS before proceeding). Accessible only to Ops role via the Admin System backend API. Logs operator identity and reason.
**Steps:** Create BatchJobController.java in the Admin System backend (com.gmepay.admin.controller); POST /api/v1/admin/batch/rerun with body {jobId: String, businessDate: String(YYYY-MM-DD), reason: String}; Validate: jobId must exist in batch_job_definition; businessDate must be parseable; reason required; Check: if SUCCESS run exists for (jobId, businessDate) return 409 Conflict with message 'Job already succeeded for this date'; If no FAILED/SKIPPED run exists return 404; Invoke BatchJobRunner.executeJob(jobId, LocalDate.parse(businessDate)) asynchronously via @Async; Return 202 Accepted with {runId, jobId, businessDate, status:'QUEUED'}; log structured {event:'MANUAL_RERUN', operator, job_id, business_date, reason}
**Deliverable:** BatchJobController.java POST /api/v1/admin/batch/rerun endpoint with role guard, idempotency check, and async dispatch
**Acceptance / logic checks:**
- POST with valid FAILED job returns 202 and a new batch_job_run row appears with RUNNING status
- POST for a job with existing SUCCESS returns 409 Conflict
- POST with unknown jobId returns 400 Bad Request
- POST without Ops role returns 403 Forbidden
- Structured log entry with event='MANUAL_RERUN' and operator identity is emitted
**Depends on:** 9.7-T06

### 9.7-T14 — Implement batch job status query endpoint  _(40 min)_
**Context:** Admin System needs to display current batch job health on the Batch Health dashboard (OPS-13 §7.4). Endpoint GET /api/v1/admin/batch/status returns all jobs for a given business_date with their latest run status, last success time, and any unacknowledged alerts. This is a read-only query across batch_job_definition, batch_job_run, and batch_window_alert tables.
**Steps:** Add GET /api/v1/admin/batch/status?businessDate=YYYY-MM-DD to BatchJobController; Query: for each BatchJobDefinition (scheme=ZeroPay, enabled=true), fetch latest BatchJobRun for the given businessDate ordered by run_seq DESC; fetch unacknowledged BatchWindowAlert for same date; Build response DTO BatchJobStatusResponse: {jobs: [{jobId, fileTypes, direction, windowKst, latestRunStatus, latestRunSeq, lastSuccessAt, alertSeverity(nullable), alertFiredAt(nullable)}]}; windowKst is computed as window_utc + 9 hours for display only; Return 200 with DTO; if businessDate is missing default to today KST
**Deliverable:** GET /api/v1/admin/batch/status endpoint returning per-job status and active alerts for a given business date
**Acceptance / logic checks:**
- Response for today contains 13 job entries for ZeroPay
- A job with SUCCESS run shows latestRunStatus='SUCCESS' and lastSuccessAt populated
- A job with FAILED run and an active P1 alert shows alertSeverity='P1' and alertFiredAt populated
- businessDate defaults to today KST when param absent
- Endpoint returns 400 if businessDate is not valid YYYY-MM-DD format
**Depends on:** 9.7-T04

### 9.7-T15 — Implement alert acknowledgement endpoint  _(30 min)_
**Context:** When Ops acknowledges a missed-window alert (e.g. after manually resolving), the alert record must be updated with acknowledged_at and acknowledged_by. This clears the alert from the unacknowledged view and prevents repeated PagerDuty noise. PATCH /api/v1/admin/batch/alerts/{id}/acknowledge accepts operator identity from JWT claims.
**Steps:** Add PATCH /api/v1/admin/batch/alerts/{id}/acknowledge to BatchJobController; Load BatchWindowAlert by id; if not found return 404; If already acknowledged return 409 with message 'Alert already acknowledged'; Set acknowledged_at=now() and acknowledged_by=principal.getName() (from Spring Security context); Save and return 200 with updated alert DTO {id, jobId, businessDate, alertSeverity, alertFiredAt, acknowledgedAt, acknowledgedBy}; Log structured {event:'ALERT_ACKNOWLEDGED', alert_id, job_id, business_date, operator}
**Deliverable:** PATCH /api/v1/admin/batch/alerts/{id}/acknowledge endpoint with idempotency check and audit log
**Acceptance / logic checks:**
- PATCH on unacknowledged alert sets acknowledged_at to within 1s of now() and acknowledged_by to caller's identity
- Second PATCH on same alert returns 409
- PATCH with unknown id returns 404
- Structured log event='ALERT_ACKNOWLEDGED' includes operator identity
- After acknowledgement, GET /batch/status shows no active alert for that job/date
**Depends on:** 9.7-T14

### 9.7-T16 — Implement Prometheus metrics for batch job health  _(40 min)_
**Context:** OPS-13 §7.1 and §7.4: the Batch Health dashboard requires Prometheus metrics. Required metrics: batch_job_last_run_status (gauge, labels: job_id, status, 1=SUCCESS/0=else), batch_job_last_success_seconds (gauge, labels: job_id, unix epoch of last success), batch_window_alert_active (gauge, labels: job_id, severity, 1=unacknowledged alert exists). Micrometer is the metrics library; MeterRegistry injected via Spring.
**Steps:** Create BatchMetricsExporter.java in com.gmepay.batch.monitor; Inject MeterRegistry and BatchJobRunRepository and BatchWindowAlertRepository; @Scheduled(fixedRate=60000) method exportMetrics(): for each enabled job_id, query latest run status for today and last SUCCESS time; Register/update Gauge batch_job_last_run_status with tag job_id and value 1.0 if SUCCESS else 0.0; Register/update Gauge batch_job_last_success_seconds with value of lastSuccessAt.toEpochSecond() or 0 if never; Register/update Gauge batch_window_alert_active with value 1.0 if unacknowledged alert exists else 0.0; Metrics exposed via /actuator/prometheus endpoint (existing Spring Boot Actuator)
**Deliverable:** BatchMetricsExporter.java exporting 3 Prometheus gauge metrics updated every 60 seconds
**Acceptance / logic checks:**
- GET /actuator/prometheus includes batch_job_last_run_status{job_id='JOB-ZP-01'} line
- After a SUCCESS run for JOB-ZP-01, batch_job_last_run_status{job_id='JOB-ZP-01'} = 1.0
- After an unacknowledged P1 alert, batch_window_alert_active{job_id='JOB-ZP-01',severity='P1'} = 1.0
- After alert acknowledgement, batch_window_alert_active drops to 0.0 within 60s
- Metrics endpoint returns HTTP 200 with content-type text/plain;version=0.0.4
**Depends on:** 9.7-T08

### 9.7-T17 — Unit tests: KstBusinessDateResolver edge cases  _(35 min)_
**Context:** The KstBusinessDateResolver (T11) is logic-bearing: its output determines which transactions are included in each batch file. Must test midnight-KST boundary, day-before-sunday, and all day-of-week settlement checks. Use JUnit 5 + parameterized tests. Exact UTC timestamps and expected results provided.
**Steps:** Create KstBusinessDateResolverTest.java in src/test; Parameterized test for resolveBusinessDate: input 2026-06-04T14:59:59Z expect 2026-06-04; input 2026-06-04T15:00:00Z expect 2026-06-05; input 2026-06-05T17:30:00Z expect 2026-06-06; input 2026-06-06T15:00:00Z expect 2026-06-07 (Saturday); Parameterized test for isSettlementDay: Monday 2026-06-01 true; Saturday 2026-06-06 true; Sunday 2026-06-07 false; Test getProcessingDate for JOB-ZP-01 at 2026-06-06T17:30:00Z: expect 2026-06-06 (processes June 6 txns at 02:30 KST June 7); Test getProcessingDate for JOB-ZP-06 at 2026-06-06T01:30:00Z: expect 2026-06-06 (same-day job at 10:30 KST)
**Deliverable:** KstBusinessDateResolverTest.java with >= 12 parameterized test cases covering boundary conditions
**Acceptance / logic checks:**
- All 12+ test cases pass with no failures
- The 15:00 UTC boundary (midnight KST) is covered with before/at/after cases
- Sunday detection is explicitly tested (isSettlementDay Sunday -> false)
- Prior-day job vs same-day job distinction is tested for getProcessingDate
- Test class has zero external dependencies beyond JUnit 5 and the class under test
**Depends on:** 9.7-T11

### 9.7-T18 — Unit tests: BatchJobRunner idempotency and retry logic  _(45 min)_
**Context:** BatchJobRunner (T06) has two critical behaviors: idempotency guard (no re-run on existing SUCCESS) and retry with back-off (up to 3 attempts, 30s/120s/300s delays). Use Mockito to mock BatchJobRunRepository and BatchJobDispatcher. Use a fixed TestClock to control time and verify back-off without real sleeps.
**Steps:** Create BatchJobRunnerTest.java in src/test; Mock BatchJobRunRepository: when findByJobIdAndBusinessDate returns a SUCCESS run, verify executeJob returns SKIPPED and dispatch is never called; Mock: stale RUNNING run (started 35 min ago) -> verify status reset to FAILED then new run inserted; Test retry: mock dispatch to throw RuntimeException on first 2 calls, succeed on 3rd; verify run_seq=3 at success and retry_count=2; Test failure after 3 retries: mock dispatch always throws; verify final status=FAILED, retry_count=3, error_detail contains exception message; Inject TestClock; verify back-off sleeps are 30000ms, 120000ms (not real sleeps)
**Deliverable:** BatchJobRunnerTest.java with >= 8 test cases covering idempotency, stale-run reset, retry success, and retry exhaustion
**Acceptance / logic checks:**
- Idempotency test: dispatch mock is called zero times when SUCCESS exists
- Stale-run test: repository.save called with status=FAILED for the stale record
- Retry-success test: run_seq is 3 on success, retry_count is 2
- Retry-exhaustion test: final status=FAILED with non-null error_detail
- Back-off durations match 30000/120000/300000 ms precisely via clock verification
**Depends on:** 9.7-T06

### 9.7-T19 — Unit tests: BatchWindowMonitor threshold firing and deduplication  _(40 min)_
**Context:** BatchWindowMonitor (T08) must fire exactly one alert per (job_id, businessDate, severity) even if polled many times. Use Mockito: mock BatchJobRunRepository (no SUCCESS), mock BatchWindowAlertRepository (first call returns false, subsequent calls return true), mock AlertingService. Verify alert is sent once and not again.
**Steps:** Create BatchWindowMonitorTest.java in src/test; Setup: mock current UTC time as 16:50 UTC (JOB-ZP-01 threshold=16:45 UTC passed); mock no SUCCESS run for today; mock alertRepository.exists returns false; Verify: AlertingService.sendAlert called once with jobId='JOB-ZP-01', severity='P1', channels=[PAGERDUTY,SLACK]; Second invocation of checkWindows() with alertRepository.exists now returning true: verify sendAlert NOT called again; Test P2 threshold: JOB-ZP-07 at 05:20 UTC -> P2 alert to [SLACK] only; Test no alert when job has SUCCESS before threshold
**Deliverable:** BatchWindowMonitorTest.java with >= 6 test cases covering P1/P2 firing, deduplication, and no-alert-on-success scenarios
**Acceptance / logic checks:**
- Deduplication test: sendAlert is called exactly once across two checkWindows() invocations
- P1 test: channels list includes both PAGERDUTY and SLACK
- P2 test: channels list contains SLACK only, not PAGERDUTY
- No-alert test: sendAlert never called when SUCCESS run exists before threshold
- All test cases pass without real time sleeps or real HTTP calls
**Depends on:** 9.7-T08

### 9.7-T20 — Unit tests: dependency chain enforcement in BatchJobDispatcher  _(40 min)_
**Context:** BatchJobDispatcher (T07) must block JOB-ZP-05 if JOB-ZP-03 is not SUCCESS, and allow it when JOB-ZP-03 is SUCCESS. Also verify that JOB-ZP-03 depends on JOB-ZP-01. Test all three cases: dependency missing, dependency FAILED, and dependency SUCCESS. Also test full-file suppression (T10) for delta vs full sync.
**Steps:** Create BatchJobDispatcherTest.java in src/test; Test 1: dispatch JOB-ZP-05 with no run for JOB-ZP-03 -> throws DependencyNotMetException, run status=PENDING; Test 2: dispatch JOB-ZP-05 with JOB-ZP-03 status=FAILED -> throws DependencyNotMetException; Test 3: dispatch JOB-ZP-05 with JOB-ZP-03 status=SUCCESS -> delegates to handler, no exception; Test 4: dispatch JOB-ZP-01 (no dependency) -> delegates immediately; Test 5: shouldSuppressDeltaJob('JOB-ZP-10', sunday) with JOB-ZP-12 SUCCESS -> true; Test 6: shouldSuppressDeltaJob('JOB-ZP-10', weekday) with no JOB-ZP-12 run -> false
**Deliverable:** BatchJobDispatcherTest.java with >= 6 test cases covering dependency states and delta suppression
**Acceptance / logic checks:**
- Test 1 and 2 both throw DependencyNotMetException and run is left as PENDING
- Test 3 invokes the job handler mock exactly once
- Test 4 invokes handler without any dependency lookup
- Test 5 returns true (suppress delta)
- Test 6 returns false (do not suppress)
**Depends on:** 9.7-T07, 9.7-T10

### 9.7-T21 — Unit tests: full-file suppression on Sunday sync scenario  _(35 min)_
**Context:** On Sunday nights, ZP0051 (full merchant) and ZP0053 (full QR) may arrive alongside delta files. The suppressDelta logic must correctly identify Sunday, check for full-file SUCCESS, and suppress delta execution. Edge case: if the full file job FAILS (e.g. corrupt file), the delta should NOT be suppressed (fail-open: delta runs as fallback). Test both success and failure states of the full-file job.
**Steps:** Create BatchJobRunnerSuppressTest.java in src/test; Mock BatchJobRunRepository: JOB-ZP-12 SUCCESS for 2026-06-07 (Sunday) -> executeJob('JOB-ZP-10', 2026-06-07) returns SKIPPED; Mock: JOB-ZP-12 FAILED for same date -> executeJob('JOB-ZP-10', 2026-06-07) runs normally (not suppressed); Mock: JOB-ZP-12 no run for same date -> executeJob('JOB-ZP-10', 2026-06-07) runs normally; Test JOB-ZP-13 (full QR) SUCCESS -> JOB-ZP-11 (delta QR) suppressed; Test JOB-ZP-12 SUCCESS but JOB-ZP-13 FAILED -> JOB-ZP-11 still runs (only its own full-file suppresses it)
**Deliverable:** BatchJobRunnerSuppressTest.java with 5 test cases for Sunday full-file suppression edge cases
**Acceptance / logic checks:**
- Full-file SUCCESS suppresses delta: SKIPPED status and no dispatch call
- Full-file FAILED does NOT suppress delta: dispatch called
- Full-file absent does NOT suppress delta: dispatch called
- JOB-ZP-11 suppression is independent of JOB-ZP-10 suppression status
- All tests pass without modifying BatchJobRunner production code (tests use existing extension points)
**Depends on:** 9.7-T10, 9.7-T17

### 9.7-T22 — Integration test: daily batch cycle happy path  _(55 min)_
**Context:** End-to-end integration test for the full Mon-Sat daily batch cycle. Uses an embedded H2 DB (or Testcontainers PostgreSQL) seeded with the 13 ZeroPay job definitions. Mocks the BatchJobHandler for each job (records calls and returns success). Triggers jobs in chronological order and verifies the dependency chain is respected and all run records reach SUCCESS.
**Steps:** Create BatchCycleIntegrationTest.java using @SpringBootTest with Testcontainers PostgreSQL; Seed batch_job_definition with 13 ZeroPay rows from the migration; Mock BatchJobHandler to return SUCCESS immediately for all jobs; Trigger jobs in order: JOB-ZP-01, JOB-ZP-02, then ZP-03, ZP-04, ZP-05 (verify ZP-05 waits for ZP-03/ZP-01), then ZP-06 through ZP-09; Verify each batch_job_run row has status=SUCCESS, completed_at populated, retry_count=0; Verify final alert table has zero unacknowledged rows (no thresholds missed in happy path); Assert full sequence completes without DependencyNotMetException
**Deliverable:** BatchCycleIntegrationTest.java verifying happy-path daily cycle with all 9 settlement jobs reaching SUCCESS
**Acceptance / logic checks:**
- All 9 daily settlement jobs (JOB-ZP-01 through JOB-ZP-09) have SUCCESS in batch_job_run
- JOB-ZP-05 is only triggered after JOB-ZP-03 succeeds (verify call order via mock)
- No batch_window_alert rows exist after successful cycle
- batch_job_run rows have retry_count=0 for all jobs
- Test completes in under 30 seconds using mocked handlers
**Depends on:** 9.7-T07, 9.7-T12

### 9.7-T23 — Integration test: missed ZP0011 window triggers P1 alert  _(55 min)_
**Context:** Simulate the scenario where JOB-ZP-01 fails after 3 retries and the monitoring cycle fires at 16:50 UTC (past the 16:45 UTC alert threshold). Verify P1 alert is inserted in batch_window_alert, AlertingService is called with P1 + channels [PAGERDUTY,SLACK], and downstream JOB-ZP-03 and JOB-ZP-05 are blocked in PENDING state until Ops manually reruns JOB-ZP-01.
**Steps:** Create BatchMissedWindowIntegrationTest.java using Testcontainers + mocked AlertingService; Configure clock mock to 16:50 UTC for monitoring cycle; Mock JOB-ZP-01 handler to always throw RuntimeException (simulates SFTP failure); Execute JOB-ZP-01: verify 3 retries, final status=FAILED in batch_job_run; Advance clock to 16:51 UTC; trigger checkWindows(): verify batch_window_alert row inserted with severity=P1; Trigger JOB-ZP-05: verify DependencyNotMetException (JOB-ZP-03 not yet run); Manually rerun JOB-ZP-01 (mock handler now succeeds); verify cascade: JOB-ZP-03 can now proceed via resolvePending
**Deliverable:** BatchMissedWindowIntegrationTest.java covering SFTP failure -> P1 alert -> dependency block -> manual rerun -> cascade resolution
**Acceptance / logic checks:**
- batch_job_run for JOB-ZP-01 shows retry_count=3 and status=FAILED
- batch_window_alert row exists with severity='P1' and job_id='JOB-ZP-01'
- AlertingService mock called once with severity='P1' and channels containing 'PAGERDUTY'
- JOB-ZP-05 dispatch returns DependencyNotMetException before JOB-ZP-01 success
- After manual rerun success, resolvePending() triggers JOB-ZP-03 to RUNNING state
**Depends on:** 9.7-T09, 9.7-T22

### 9.7-T24 — Integration test: idempotent rerun does not duplicate batch records  _(50 min)_
**Context:** OPS-13 §6.3 and SCH-06 §9.4: reprocessing the same job for the same date must not create duplicate settlement records or double-debit. This test verifies that running JOB-ZP-01 twice for the same business_date (once SUCCESS, once manual rerun) results in a SKIPPED second run and no new settlement_batch row is created for that date+file_type.
**Steps:** Create BatchIdempotencyIntegrationTest.java; Seed: JOB-ZP-01, business_date=2026-06-05 with existing SUCCESS run and one settlement_batch row (ZP0011, status=TRANSMITTED); Invoke BatchJobRunner.executeJob('JOB-ZP-01', LocalDate.of(2026,6,5)) again; Verify: returns SKIPPED; no new batch_job_run row with status != SKIPPED for same date; settlement_batch row count remains 1 for (ZP0011, 2026-06-05); Trigger via manual rerun endpoint POST /api/v1/admin/batch/rerun: expect 409 Conflict response; Verify handler mock was never called on the second attempt
**Deliverable:** BatchIdempotencyIntegrationTest.java verifying no duplicate settlement records or double-dispatch on rerun of a succeeded job
**Acceptance / logic checks:**
- Second executeJob call returns status=SKIPPED
- Total batch_job_run rows for (JOB-ZP-01, 2026-06-05) is 2 (original SUCCESS + new SKIPPED)
- settlement_batch row count for (ZP0011, 2026-06-05) remains 1
- Handler mock invocation count remains 1 (from original run only)
- POST /batch/rerun returns 409 Conflict for a job with existing SUCCESS
**Depends on:** 9.7-T13, 9.7-T22

### 9.7-T25 — Runbook doc: batch scheduler configuration and KST window reference  _(35 min)_
**Context:** OPS-13 §6 and go-live checklist §12.1.3 require that batch alerting and missed-window alerts are documented. Ops must be able to add a new holiday, change an alert threshold, or disable a job without touching code. The runbook must explain: how to add a holiday, how to change window_utc or alert_threshold_utc in batch_job_definition, how to manually rerun a failed job via Admin UI and CLI, and what each alert severity means.
**Steps:** Create file docs/runbook/batch-scheduler.md; Section 1: KST window reference table (all 13 jobs with file, direction, window KST, alert threshold KST, criticality, dependencies); Section 2: Adding a public holiday (SQL: INSERT INTO batch_holiday (scheme_id, holiday_date, description) VALUES (...); note weekly jobs are exempt); Section 3: Adjusting a window threshold (UPDATE batch_job_definition SET alert_threshold_utc = '20:15' WHERE job_id = 'JOB-ZP-05'; service restart not required for monitor but scheduler cron requires restart); Section 4: Manual job rerun (Admin UI path + CLI: batch-cli rerun --job JOB-ZP-01 --date YYYY-MM-DD --force); Section 5: Alert severity meanings (P1 = wake-on-call, P2 = Ops Slack within 30 min, P3 = next business day)
**Deliverable:** docs/runbook/batch-scheduler.md with complete KST window table and operational procedures
**Acceptance / logic checks:**
- KST window table lists all 13 jobs (JOB-ZP-01 through JOB-ZP-13) with correct times from OPS-13 §6.2
- Holiday INSERT example uses correct table name batch_holiday and column names
- Manual rerun CLI command syntax is accurate (matches BatchJobController endpoint)
- Section on alert severities maps P1->PagerDuty+Slack, P2->Slack, P3->Email
- Document is under 300 lines (concise runbook, not prose)
**Depends on:** 9.7-T13, 9.7-T15


## WBS 9.9 — Scheme status/reason code mapping
### 9.9-T01 — Define ZeroPaySchemeCode enum: all illustrative ZP real-time result codes  _(25 min)_
**Context:** SCH-06 §10.1 lists illustrative ZeroPay real-time API result codes that GMEPay+ must handle: 0000 (Success), 1001 (Merchant ID not found), 1002 (Merchant inactive/suspended), 1003 (QR code deactivated), 1004 (QR code expired - CPM token), 2001 (Amount below minimum), 2002 (Amount above maximum), 3001 (Scheme processing timeout), 3002 (ZeroPay internal error), 4001 (Duplicate transaction reference). Assumption A-10 states these are illustrative; exact codes come from the official 한결원 spec. The enum must be extensible and carry the raw string code so unknown codes received at runtime can be represented without a code change.
**Steps:** Create enum ZeroPaySchemeCode in com.gmepayplus.scheme.zeropay.codes; Add enum constants: SUCCESS(0000), MERCHANT_NOT_FOUND(1001), MERCHANT_INACTIVE(1002), QR_DEACTIVATED(1003), QR_EXPIRED(1004), AMOUNT_TOO_LOW(2001), AMOUNT_TOO_HIGH(2002), SCHEME_TIMEOUT(3001), SCHEME_ERROR(3002), DUPLICATE_TRANSACTION(4001), UNKNOWN(-1); Each constant holds a String rawCode field (not int, since codes may gain leading zeros or letters after 한결원 confirmation); Add static factory method fromRawCode(String raw): returns matching constant or UNKNOWN if not recognised; Add isSuccess() method: returns true only for SUCCESS
**Deliverable:** ZeroPaySchemeCode.java enum in com.gmepayplus.scheme.zeropay.codes
**Acceptance / logic checks:**
- ZeroPaySchemeCode.fromRawCode(0000) returns SUCCESS
- ZeroPaySchemeCode.fromRawCode(9999) returns UNKNOWN (not exception)
- ZeroPaySchemeCode.SUCCESS.isSuccess() == true; ZeroPaySchemeCode.MERCHANT_NOT_FOUND.isSuccess() == false
- All 10 named constants are present; verify via ZeroPaySchemeCode.values().length == 11 (10 + UNKNOWN)
- rawCode field on MERCHANT_NOT_FOUND equals the string 1001

### 9.9-T02 — Define ZeroPayBatchResultCode enum: batch-level result codes from ZP0012/ZP0022  _(20 min)_
**Context:** SCH-06 §10.1 lists three batch-specific ZeroPay result codes returned in ZP0012 (payment registration result) and ZP0022 (refund registration result) fields result_code CHAR(2): 00 (Success - registration accepted), 9001 (Batch registration failure), 9002 (Amount mismatch on registration), 9003 (Record not found for registration). These differ from real-time codes and must be modelled separately to avoid confusion. The enum must support fromRawCode(String) similar to ZeroPaySchemeCode, with UNKNOWN for unrecognised values.
**Steps:** Create enum ZeroPayBatchResultCode in com.gmepayplus.scheme.zeropay.codes; Add constants: BATCH_SUCCESS(00), BATCH_REGISTRATION_FAILURE(9001), BATCH_AMOUNT_MISMATCH(9002), BATCH_RECORD_NOT_FOUND(9003), BATCH_UNKNOWN(-1); Each constant carries String rawCode field; Add fromRawCode(String raw) factory returning BATCH_UNKNOWN for unrecognised input; Add isSuccess() returning true only for BATCH_SUCCESS
**Deliverable:** ZeroPayBatchResultCode.java enum
**Acceptance / logic checks:**
- fromRawCode(00) returns BATCH_SUCCESS
- fromRawCode(9002) returns BATCH_AMOUNT_MISMATCH
- fromRawCode(9999) returns BATCH_UNKNOWN without throwing
- BATCH_SUCCESS.isSuccess() == true; BATCH_REGISTRATION_FAILURE.isSuccess() == false
- rawCode on BATCH_AMOUNT_MISMATCH equals the string 9002

### 9.9-T03 — Define GmeApiErrorCode enum: all Northbound API-05 error codes with HTTP status and retryable flag  _(35 min)_
**Context:** API-05 §8.2 defines the complete set of GMEPay+ partner-facing error codes. Each code maps to an HTTP status and a retryable flag. Full table: VALIDATION_ERROR(400,false), MISSING_IDEMPOTENCY_KEY(400,false), INVALID_SIGNATURE(401,false), INVALID_API_KEY(401,false), TIMESTAMP_OUT_OF_RANGE(401,false), FORBIDDEN(403,false), IP_NOT_ALLOWLISTED(403,false), PAYMENT_NOT_FOUND(404,false), MERCHANT_NOT_FOUND(404,false), SCHEME_NOT_FOUND(404,false), IDEMPOTENCY_KEY_REUSE(422,false), RATE_QUOTE_EXPIRED(422,false), RATE_QUOTE_INVALID(422,false), PARTNER_B_QUOTE_DEVIATION(422,false), PARTNER_B_QUOTE_UNAVAILABLE(422,true), SCHEME_UNAVAILABLE(422,true), PAYMENT_MODE_NOT_SUPPORTED(422,false), DIRECTION_NOT_ENABLED(422,false), CANCEL_NOT_PERMITTED(400,false), INSUFFICIENT_PREFUNDING(402,false), DUPLICATE_PARTNER_TXN_REF(409,false), RATE_LIMITED(429,true), INTERNAL_ERROR(500,true), SERVICE_UNAVAILABLE(503,true). Plus scheme-specific codes: QR_INVALID_CHECKSUM(422,false), QR_MALFORMED(422,false), QR_UNKNOWN_SCHEME(422,false), QR_CURRENCY_MISMATCH(422,false), QR_DEACTIVATED(422,false), QR_EXPIRED(422,false), MERCHANT_INACTIVE(422,false), AMOUNT_TOO_LOW(422,false), AMOUNT_TOO_HIGH(422,false), SCHEME_TIMEOUT(504,true), SCHEME_ERROR(502,true), DUPLICATE_TRANSACTION(409,false), NO_SCHEME_FOR_LOCATION(422,false).
**Steps:** Create enum GmeApiErrorCode in com.gmepayplus.api.error with fields: int httpStatus, boolean retryable; Add all 37 constants listed in context with their httpStatus and retryable values; Add getHttpStatus() and isRetryable() accessors; Add a partnerMessage() method returning the canonical partner-facing English message string per API-05 §10.2 mapping table (e.g. MERCHANT_NOT_FOUND -> Merchant not found); Ensure the enum is in a shared module importable by both the Northbound API layer and the ZeroPay adapter code-mapping layer
**Deliverable:** GmeApiErrorCode.java enum with all 37 constants, HTTP status, retryable flag, and partnerMessage()
**Acceptance / logic checks:**
- GmeApiErrorCode.MERCHANT_NOT_FOUND.getHttpStatus() == 404
- GmeApiErrorCode.SCHEME_TIMEOUT.getHttpStatus() == 504 and isRetryable() == true
- GmeApiErrorCode.INSUFFICIENT_PREFUNDING.getHttpStatus() == 402 and isRetryable() == false
- GmeApiErrorCode.PARTNER_B_QUOTE_UNAVAILABLE.isRetryable() == true
- GmeApiErrorCode.values().length == 37 (compile-time constant guards against accidental deletion)

### 9.9-T04 — Define SchemeCodeMappingRule record: one row in the ZeroPay-to-GME mapping table  _(25 min)_
**Context:** The mapping from ZeroPay scheme codes to GMEPay+ API error codes is data, not hard-coded logic. Each mapping rule has: sourceCode (String - the raw ZP code or internal signal like QR_PARSE_FAIL), channel (REALTIME or BATCH), gmeErrorCode (GmeApiErrorCode), httpStatus (int - from GmeApiErrorCode), partnerMessage (String - the canonical English text for partners), includeRawCode (boolean - whether to expose scheme_error_code in the extended error body). The mapping table is loaded at startup from a config source (DB or YAML) so new ZeroPay codes can be mapped without a code redeploy (Assumption A-10: exact codes TBD until 한결원 spec confirmed).
**Steps:** Create enum Channel: REALTIME, BATCH; Create record SchemeCodeMappingRule with fields: String sourceCode, Channel channel, GmeApiErrorCode gmeErrorCode, boolean includeRawCode; Add a computed accessor partnerMessage() delegating to gmeErrorCode.partnerMessage(); Add a computed accessor httpStatus() delegating to gmeErrorCode.getHttpStatus(); Create SchemeCodeMappingRuleBuilder (or use record compact constructor) that validates sourceCode is non-blank and gmeErrorCode is non-null
**Deliverable:** SchemeCodeMappingRule.java record and Channel.java enum in com.gmepayplus.scheme.mapping
**Acceptance / logic checks:**
- SchemeCodeMappingRule with sourceCode=1001, channel=REALTIME, gmeErrorCode=MERCHANT_NOT_FOUND, includeRawCode=true constructs without error
- rule.httpStatus() == 404 (delegated from GmeApiErrorCode.MERCHANT_NOT_FOUND)
- rule.partnerMessage() == Merchant not found
- Constructing with null gmeErrorCode throws NullPointerException or IllegalArgumentException
- Constructing with blank sourceCode throws IllegalArgumentException
**Depends on:** 9.9-T03

### 9.9-T05 — Define canonical ZeroPay-to-GME mapping table: all 13 real-time code mappings as config  _(30 min)_
**Context:** SCH-06 §10.2 provides the full illustrative mapping for ZeroPay real-time codes to GMEPay+ API error codes. The 13 rows (including success and internal signals): 0000->success(200), 1001->MERCHANT_NOT_FOUND(422), 1002->MERCHANT_INACTIVE(422), 1003->QR_DEACTIVATED(422), 1004->QR_EXPIRED(422), 2001->AMOUNT_TOO_LOW(422), 2002->AMOUNT_TOO_HIGH(422), 3001->SCHEME_TIMEOUT(504), 3002->SCHEME_ERROR(502), 4001->DUPLICATE_TRANSACTION(409), QR_PARSE_FAIL/QR_INVALID_CHECKSUM->QR_INVALID_CHECKSUM(422), QR_PARSE_FAIL/QR_MALFORMED->QR_MALFORMED(422), internal:NO_SCHEME->NO_SCHEME_FOR_LOCATION(422), internal:PREFUNDING->INSUFFICIENT_PREFUNDING(402). All non-zero real-time codes must include scheme_error_code (raw ZP code) in the extended error body (includeRawCode=true). Internal signals (QR parse, prefunding) do not include a scheme code (includeRawCode=false).
**Steps:** Create class ZeroPayRealtimeCodeMappings in com.gmepayplus.scheme.zeropay.codes; Define a static final List<SchemeCodeMappingRule> ALL_MAPPINGS containing one entry per row described in context; Use SchemeCodeMappingRule records; channel=REALTIME for all entries in this class; Add a static Map<String, SchemeCodeMappingRule> BY_SOURCE_CODE index built from ALL_MAPPINGS for O(1) lookup; Verify list size is exactly 14 entries (13 error mappings + 1 success row)
**Deliverable:** ZeroPayRealtimeCodeMappings.java with 14 static mapping entries and an index map
**Acceptance / logic checks:**
- ZeroPayRealtimeCodeMappings.BY_SOURCE_CODE.get(1001).getGmeErrorCode() == GmeApiErrorCode.MERCHANT_NOT_FOUND
- Entry for 3001 has includeRawCode=true and gmeErrorCode=SCHEME_TIMEOUT
- Entry for internal signal QR_INVALID_CHECKSUM has includeRawCode=false
- Entry for 0000 maps to httpStatus 200 (success, no error code)
- ALL_MAPPINGS.size() == 14
**Depends on:** 9.9-T01, 9.9-T03, 9.9-T04

### 9.9-T06 — Define canonical ZeroPay-to-GME mapping table: batch result code mappings for ZP0012/ZP0022  _(30 min)_
**Context:** SCH-06 §10.3 defines internal batch status codes assigned to each registration result. The mapping from ZeroPay ZP0012/ZP0022 result_code CHAR(2) to internal GMEPay+ batch status: 00 -> SETTLEMENT_REGISTERED (success), 9001 -> REGISTRATION_FAILED, 9002 -> REGISTRATION_AMOUNT_MISMATCH, 9003 -> REGISTRATION_UNKNOWN. These batch statuses are stored on the transaction record and drive the exception queue in the Ops portal. They do not map to API-05 HTTP errors (batch errors are internal); they map to BatchTransactionStatus enum values. A BATCH_UNKNOWN catch-all handles unrecognised result codes as EXCEPTION_PENDING.
**Steps:** Create enum BatchTransactionStatus: SETTLEMENT_REGISTERED, REGISTRATION_FAILED, REGISTRATION_UNKNOWN, REGISTRATION_AMOUNT_MISMATCH, SETTLEMENT_CONFIRMED, SETTLEMENT_DISCREPANCY, EXCEPTION_PENDING, EXCEPTION_RESOLVED; Create class ZeroPayBatchCodeMappings in com.gmepayplus.scheme.zeropay.codes; Add static Map<String, BatchTransactionStatus> RESULT_CODE_TO_STATUS: 00->SETTLEMENT_REGISTERED, 9001->REGISTRATION_FAILED, 9002->REGISTRATION_AMOUNT_MISMATCH, 9003->REGISTRATION_UNKNOWN; Add static method resolve(String resultCode): returns mapped status or EXCEPTION_PENDING for unknown codes; Document that SETTLEMENT_CONFIRMED and SETTLEMENT_DISCREPANCY are set by ZP0062/ZP0064 processing, not ZP0012/ZP0022
**Deliverable:** BatchTransactionStatus.java enum and ZeroPayBatchCodeMappings.java in com.gmepayplus.scheme.zeropay.codes
**Acceptance / logic checks:**
- ZeroPayBatchCodeMappings.resolve(00) returns BatchTransactionStatus.SETTLEMENT_REGISTERED
- ZeroPayBatchCodeMappings.resolve(9002) returns BatchTransactionStatus.REGISTRATION_AMOUNT_MISMATCH
- ZeroPayBatchCodeMappings.resolve(9999) returns BatchTransactionStatus.EXCEPTION_PENDING (unknown code fallback)
- BatchTransactionStatus enum has exactly 8 values as listed
- resolve(null) returns EXCEPTION_PENDING without throwing NullPointerException
**Depends on:** 9.9-T02

### 9.9-T07 — Implement SchemeCodeMapper service: translate ZeroPay real-time codes to GmeApiError  _(40 min)_
**Context:** SchemeCodeMapper is the central service the ZeroPay Adapter and Hub Core Orchestrator call to convert a raw ZeroPay result code into a GmeApiError response object. GmeApiError (API-05 §8.1 envelope) has fields: code (GmeApiErrorCode), message (String), request_id (String), details (List), scheme_error_code (String, nullable - the raw ZP code included only when includeRawCode=true on the mapping rule). The mapper looks up ZeroPayRealtimeCodeMappings.BY_SOURCE_CODE; if the code is absent (runtime code not in illustrative table), falls back to SCHEME_ERROR(502) with a warning log and includes the unknown raw code for diagnostics.
**Steps:** Create class SchemeCodeMapper as a Spring @Service in com.gmepayplus.scheme.zeropay; Inject ZeroPayRealtimeCodeMappings (or load statically); Implement GmeApiError mapRealtimeCode(String zpCode, String requestId): lookup in BY_SOURCE_CODE; build GmeApiError with code, message, request_id, and scheme_error_code=zpCode if includeRawCode=true; For unknown zpCode: log WARN with the raw code; return GmeApiError with code=SCHEME_ERROR, httpStatus=502, scheme_error_code=zpCode (always include for unknowns); Add GmeApiError mapInternalSignal(String signalKey, String requestId): same lookup but for internal keys like QR_INVALID_CHECKSUM and INSUFFICIENT_PREFUNDING; these never include scheme_error_code
**Deliverable:** SchemeCodeMapper.java Spring service and GmeApiError.java record
**Acceptance / logic checks:**
- mapRealtimeCode(1001, req-123) returns GmeApiError with code=MERCHANT_NOT_FOUND, httpStatus=422, scheme_error_code=1001, message=Merchant not found
- mapRealtimeCode(3001, req-456) returns httpStatus=504, code=SCHEME_TIMEOUT, scheme_error_code=3001
- mapRealtimeCode(9999, req-789) returns httpStatus=502, code=SCHEME_ERROR, scheme_error_code=9999 (unknown code always exposes raw code)
- mapInternalSignal(QR_INVALID_CHECKSUM, req-abc) returns httpStatus=422, code=QR_INVALID_CHECKSUM, scheme_error_code=null (no raw scheme code for internal signals)
- mapRealtimeCode(1002, req-xyz) returns message=Merchant is not accepting payments
**Depends on:** 9.9-T05, 9.9-T03

### 9.9-T08 — Implement BatchCodeMapper service: translate ZP0012/ZP0022 result codes to BatchTransactionStatus  _(40 min)_
**Context:** BatchCodeMapper translates ZeroPay batch result codes (from ZP0012 result_code CHAR(2) and ZP0022 result_code CHAR(2)) to internal BatchTransactionStatus values and produces ExceptionRecord entries for non-success outcomes. An ExceptionRecord has: gme_txn_id, zeropay_txn_ref, batch_file_type (ZP0012 or ZP0022), result_code (raw), resolved_status (BatchTransactionStatus), message (human-readable), raised_at (Instant). For REGISTRATION_AMOUNT_MISMATCH, ExceptionRecord must also carry submitted_amount and registered_amount (both BigDecimal, KRW scale 0).
**Steps:** Create BatchCodeMapper as a Spring @Service in com.gmepayplus.scheme.zeropay; Create ExceptionRecord record with fields: gme_txn_id, zeropay_txn_ref, batch_file_type, result_code, resolved_status, message, raised_at, submitted_amount (nullable), registered_amount (nullable); Implement BatchTransactionStatus resolveStatus(String resultCode): delegate to ZeroPayBatchCodeMappings.resolve(); Implement ExceptionRecord buildException(PaymentRegistrationResult result, String batchFileType): map result_code to status; build ExceptionRecord; for REGISTRATION_AMOUNT_MISMATCH include amounts; Add boolean requiresOpsAttention(BatchTransactionStatus status): returns true for REGISTRATION_FAILED, REGISTRATION_UNKNOWN, REGISTRATION_AMOUNT_MISMATCH, SETTLEMENT_DISCREPANCY, EXCEPTION_PENDING
**Deliverable:** BatchCodeMapper.java Spring service and ExceptionRecord.java record
**Acceptance / logic checks:**
- resolveStatus(00) returns SETTLEMENT_REGISTERED
- resolveStatus(9001) returns REGISTRATION_FAILED
- buildException for result_code=9002, submitted_amount=50000, registered_amount=49000 produces ExceptionRecord with resolved_status=REGISTRATION_AMOUNT_MISMATCH and both amounts set
- requiresOpsAttention(SETTLEMENT_REGISTERED) == false; requiresOpsAttention(REGISTRATION_FAILED) == true
- buildException for result_code=00 produces ExceptionRecord with resolved_status=SETTLEMENT_REGISTERED and no amounts set (amounts are null)
**Depends on:** 9.9-T06

### 9.9-T09 — Implement settlement result code resolution: ZP0062/ZP0064 per-merchant status mapping  _(35 min)_
**Context:** ZP0062 (morning settlement result) and ZP0064 (afternoon settlement result) confirm whether ZeroPay processed the settlement request (ZP0061/ZP0063). Each per-merchant record in ZP0062/ZP0064 carries a result_code (CHAR(2) from ZeroPay). A result_code=00 means the merchant was settled; any non-zero code means settlement failed for that merchant. GMEPay+ must map these to BatchTransactionStatus: 00 -> SETTLEMENT_CONFIRMED; non-zero -> SETTLEMENT_DISCREPANCY (triggers P1 ops exception). The full settlement_batch record in the DB must also be updated: status, settled_amount, settlement_date. SettlementBatchRecord carries: settlement_batch_id, merchant_id, file_type, result_code, resolved_status, settled_amount_krw, settlement_date.
**Steps:** Create SettlementBatchRecord record with fields listed in context; Implement SettlementCodeMapper.resolveSettlementStatus(String resultCode): returns SETTLEMENT_CONFIRMED for 00, SETTLEMENT_DISCREPANCY otherwise; Implement SettlementCodeMapper.processSettlementResult(List<SettlementBatchRecord> records): iterate; set resolved_status via resolveSettlementStatus; collect SETTLEMENT_DISCREPANCY records as ExceptionRecord list for Ops queue; Add a method buildSettlementException(SettlementBatchRecord record): creates ExceptionRecord with batch_file_type=ZP0062 or ZP0064, resolved_status=SETTLEMENT_DISCREPANCY, message containing merchant_id and amounts; Write unit test: 3 merchant records (result_code=00, 00, non-zero) -> 2 SETTLEMENT_CONFIRMED and 1 SETTLEMENT_DISCREPANCY exception
**Deliverable:** SettlementCodeMapper.java Spring service and SettlementBatchRecord.java record with unit tests
**Acceptance / logic checks:**
- resolveSettlementStatus(00) returns SETTLEMENT_CONFIRMED
- resolveSettlementStatus(01) returns SETTLEMENT_DISCREPANCY
- processSettlementResult with 1 non-zero record produces exactly 1 ExceptionRecord in the returned exception list
- ExceptionRecord for SETTLEMENT_DISCREPANCY has requiresOpsAttention=true (verify via BatchCodeMapper.requiresOpsAttention)
- processSettlementResult with all-zero result codes produces empty exception list
**Depends on:** 9.9-T06, 9.9-T08

### 9.9-T10 — Wire SchemeCodeMapper into ZeroPayAdapter.commitPayment: translate scheme result before throwing  _(40 min)_
**Context:** ZeroPayAdapter.commitPayment (WBS 9.1-T14) currently maps ZeroPay result codes directly to SchemeAdapterException subtypes. It must now route through SchemeCodeMapper so the raw ZP code is captured in the exception and available for the Northbound API error response. The SchemeResult returned on success must carry the raw ZP code (0000) and the approval_code. On failure, the thrown SchemeAdapterException must carry: raw_scheme_error_code (the ZP string), gme_error_code (GmeApiErrorCode from mapping), and http_status (int). The Hub Core Orchestrator will use these to build the GmeApiError response envelope without knowing ZeroPay details.
**Steps:** Inject SchemeCodeMapper into ZeroPayAdapter; In commitPayment, after receiving ZeroPay response, call SchemeCodeMapper.mapRealtimeCode(zpResultCode, requestId); If GmeApiError.code == success (0000), return SchemeResult with approval_code and raw ZP code; If not success, enrich the thrown SchemeAdapterException with gme_error_code and http_status from GmeApiError; preserve raw_scheme_error_code; Update SchemeAdapterException base class or create SchemeResultException(GmeApiError gmeApiError) to carry the full error context; Update existing unit tests for commitPayment to assert that caught SchemeAdapterException carries gme_error_code
**Deliverable:** ZeroPayAdapter.commitPayment updated to use SchemeCodeMapper, with updated unit tests
**Acceptance / logic checks:**
- commitPayment receiving ZP code 1001 throws SchemeAdapterException with gme_error_code=MERCHANT_NOT_FOUND and http_status=422
- commitPayment receiving ZP code 3001 throws SchemeAdapterException with gme_error_code=SCHEME_TIMEOUT and http_status=504
- commitPayment receiving unknown code 8888 throws SchemeAdapterException with gme_error_code=SCHEME_ERROR and raw_scheme_error_code=8888
- commitPayment receiving ZP code 0000 returns SchemeResult without throwing
- Existing 9.1-T14 unit tests still pass after refactoring
**Depends on:** 9.9-T07, 9.1-T14

### 9.9-T11 — Wire SchemeCodeMapper into ZeroPayAdapter.prepareCPM: translate CPM token errors  _(30 min)_
**Context:** prepareCPM (WBS 9.1-T14) issues a CPM QR token via the ZeroPay real-time API. Failure codes for prepareCPM are the same ZeroPay real-time table (SCH-06 §10.1). CPM-specific codes to handle: 1003 (QR_DEACTIVATED), 1004 (QR_EXPIRED for a reused token), 3001 (SCHEME_TIMEOUT), 3002 (SCHEME_ERROR). A failed prepareCPM must never deduct prefunding (the Hub Core deducts at generate time; the adapter must signal failure before the Orchestrator deducts). The enriched exception carries gme_error_code and http_status so the Orchestrator can return the correct API-05 error envelope.
**Steps:** Inject SchemeCodeMapper into ZeroPayAdapter (already injected after 9.9-T10; verify); In prepareCPM, apply mapRealtimeCode(zpResultCode, requestId) after receiving ZeroPay response; On non-success, throw SchemeAdapterException with gme_error_code from GmeApiError; Add unit test for prepareCPM with ZP code 1003: assert SchemeAdapterException with gme_error_code=QR_DEACTIVATED, httpStatus=422; Add unit test for prepareCPM with ZP code 3001: assert SchemeAdapterException with gme_error_code=SCHEME_TIMEOUT, httpStatus=504
**Deliverable:** ZeroPayAdapter.prepareCPM updated to use SchemeCodeMapper, plus 2 new unit tests
**Acceptance / logic checks:**
- prepareCPM receiving 1003 throws SchemeAdapterException with gme_error_code=QR_DEACTIVATED
- prepareCPM receiving 3001 throws SchemeAdapterException with gme_error_code=SCHEME_TIMEOUT and http_status=504
- prepareCPM receiving 0000 returns PrepareToken without throwing
- Existing prepareCPM unit test from 9.1-T14 still passes
- SchemeCodeMapper is injected once in ZeroPayAdapter constructor, not duplicated
**Depends on:** 9.9-T07, 9.1-T14, 9.9-T10

### 9.9-T12 — Wire BatchCodeMapper into ZP0012 reconciliation: assign BatchTransactionStatus per record  _(45 min)_
**Context:** After parseInboundFile(ZP0012) returns a list of PaymentRegistrationResult records, the settlement reconciliation processor must call BatchCodeMapper to assign a BatchTransactionStatus to each transaction and raise ExceptionRecord entries for non-00 result codes. The five SCH-06 §5.4 mismatch conditions must be detected: (1) result_code=00 + amounts match = SETTLEMENT_REGISTERED, (2) non-zero result_code = REGISTRATION_FAILED, (3) ZP0011 record absent from ZP0012 = REGISTRATION_UNKNOWN (set by cross-file diff logic, not BatchCodeMapper), (4) ZP0012 has record not in ZP0011 = anomaly log, (5) amounts differ = REGISTRATION_AMOUNT_MISMATCH. BatchCodeMapper handles conditions 1, 2, 5; the cross-file diff (conditions 3, 4) is in ZP0012ReconciliationService (9.9-T13).
**Steps:** Create RegistrationReconciliationResult record: List<TransactionStatusUpdate> updates, List<ExceptionRecord> exceptions; TransactionStatusUpdate carries: gme_txn_id, zeropay_txn_ref, new_status (BatchTransactionStatus); Implement BatchCodeMapper.processZp0012Results(List<PaymentRegistrationResult> results, Map<String, BigDecimal> submittedAmounts): for each result, call resolveStatus; for SETTLEMENT_REGISTERED verify registered_amount == submittedAmounts.get(zeropay_txn_ref), if mismatch override to REGISTRATION_AMOUNT_MISMATCH and build ExceptionRecord; Return RegistrationReconciliationResult with all updates and all exceptions; Write unit test: 3 results (00 matching amount, 9001 failure, 00 with amount mismatch 50000 vs 49500) -> 1 SETTLEMENT_REGISTERED, 1 REGISTRATION_FAILED, 1 REGISTRATION_AMOUNT_MISMATCH
**Deliverable:** BatchCodeMapper.processZp0012Results with RegistrationReconciliationResult and unit tests
**Acceptance / logic checks:**
- Result with result_code=00 and registered_amount=50000 matching submitted=50000 produces update with status=SETTLEMENT_REGISTERED
- Result with result_code=9001 produces ExceptionRecord with resolved_status=REGISTRATION_FAILED
- Result with result_code=00 but registered_amount=49500 vs submitted=50000 produces status=REGISTRATION_AMOUNT_MISMATCH with both amounts in ExceptionRecord
- processZp0012Results with empty input returns empty updates and empty exceptions
- Amount comparison uses BigDecimal.compareTo, not equals, to handle scale differences (49500.00 == 49500)
**Depends on:** 9.9-T08

### 9.9-T13 — Implement ZP0012ReconciliationService: cross-file diff for conditions 3 and 4  _(45 min)_
**Context:** SCH-06 §5.4 specifies two mismatch conditions that require cross-file comparison: Condition 3 - a record in GME's outbound ZP0011 file has no matching entry in the received ZP0012 (REGISTRATION_UNKNOWN); Condition 4 - ZP0012 contains a record whose zeropay_txn_ref was not in GME's ZP0011 (anomaly - log only, raise ExceptionRecord for Ops review). The match key is zeropay_txn_ref + txn_date (SCH-06 §5.2). ZP0012ReconciliationService accepts the submitted ZP0011 records (from DB) and the parsed ZP0012 records, performs a two-way diff, and returns a CrossFileReconciliationResult.
**Steps:** Create CrossFileReconciliationResult record: List<TransactionStatusUpdate> unknownUpdates (condition 3), List<ExceptionRecord> anomalies (condition 4), int matchedCount, int unknownCount, int anomalyCount; Implement ZP0012ReconciliationService.reconcile(List<ZP0011Record> submitted, List<PaymentRegistrationResult> received): index received by zeropay_txn_ref+txn_date; for each submitted record absent from received, create update with REGISTRATION_UNKNOWN; for each received record absent from submitted, create anomaly ExceptionRecord; Set REGISTRATION_UNKNOWN status only if the transaction has not already been assigned a status by BatchCodeMapper (check new_status == null); Log anomaly condition 4 at WARN with zeropay_txn_ref; Write unit test: 3 submitted, 2 in ZP0012 (1 absent), 1 extra in ZP0012 -> 1 REGISTRATION_UNKNOWN, 1 anomaly, 2 matched
**Deliverable:** ZP0012ReconciliationService.java Spring service and CrossFileReconciliationResult.java record with unit tests
**Acceptance / logic checks:**
- 1 submitted record absent from ZP0012 produces 1 TransactionStatusUpdate with status=REGISTRATION_UNKNOWN
- 1 ZP0012 record not in submitted produces 1 ExceptionRecord in anomalies list (not thrown exception)
- matchedCount=2, unknownCount=1, anomalyCount=1 for the 3-submitted/2-received/1-extra scenario
- reconcile with both lists equal (all records matched both ways) produces empty unknownUpdates and empty anomalies
- Matching key uses both zeropay_txn_ref AND txn_date to avoid cross-day collisions
**Depends on:** 9.9-T08

### 9.9-T14 — Implement ZP0022ReconciliationService: ZP0021/ZP0022 refund registration cross-file diff  _(40 min)_
**Context:** SCH-06 §6.4 states refund registration reconciliation follows identical logic to ZP0012 (§5.4) applied to refund records. Match key for ZP0022 is original_zeropay_txn_ref + refund_date. ZP0022 result field is result_code CHAR(2) (same batch codes: 00, 9001, 9002, 9003). ZP0022ReconciliationService must reuse BatchCodeMapper.processZp0012Results adapted for RefundRegistrationResult objects, and ZP0022ReconciliationService.reconcile for the cross-file diff. Unresolved refund registration failures before ZP0061 generation are P1 incidents (SCH-06 §6.4).
**Steps:** Create RefundRegistrationResult record: original_zeropay_txn_ref, gme_refund_id, result_code, result_message, registered_refund_amount, adjustment_settlement_date; Extend BatchCodeMapper with processZp0022Results(List<RefundRegistrationResult> results, Map<String, BigDecimal> submittedAmounts): same pattern as processZp0012Results using original_zeropay_txn_ref+refund_date as key; Create ZP0022ReconciliationService.reconcile(List<ZP0021Record> submitted, List<RefundRegistrationResult> received): same two-way diff logic as ZP0012ReconciliationService using original_zeropay_txn_ref+refund_date key; Write unit test for ZP0022 reconciliation: 2 submitted refunds, 1 in ZP0022 response, 1 absent -> 1 REGISTRATION_UNKNOWN; Add requiresOpsAttention flag to unresolved refund failures (reuse BatchCodeMapper.requiresOpsAttention)
**Deliverable:** ZP0022ReconciliationService.java, RefundRegistrationResult.java, BatchCodeMapper.processZp0022Results with unit tests
**Acceptance / logic checks:**
- processZp0022Results with result_code=00 and matching amount returns SETTLEMENT_REGISTERED for the refund transaction
- processZp0022Results with result_code=9001 returns REGISTRATION_FAILED for the refund
- ZP0022ReconciliationService.reconcile with 1 absent refund produces 1 TransactionStatusUpdate with status=REGISTRATION_UNKNOWN
- requiresOpsAttention(REGISTRATION_FAILED) returns true (verified via BatchCodeMapper)
- Cross-file diff key uses original_zeropay_txn_ref AND refund_date (not payment txn_date)
**Depends on:** 9.9-T12, 9.9-T13

### 9.9-T15 — Implement QR parse error-to-API-error mapping in the Northbound error translation layer  _(40 min)_
**Context:** When parseMerchantQR throws QrParseException (WBS 9.1-T04), the Hub Core Orchestrator must translate it to a GmeApiError before returning the HTTP response. The mapping uses the internal signal keys defined in ZeroPayRealtimeCodeMappings: QrParseErrorCode.QR_INVALID_CHECKSUM -> GmeApiErrorCode.QR_INVALID_CHECKSUM(422), QR_UNKNOWN_SCHEME -> QR_UNKNOWN_SCHEME(422) or NO_SCHEME_FOR_LOCATION(422), QR_MALFORMED -> QR_MALFORMED(422), QR_CURRENCY_MISMATCH -> QR_CURRENCY_MISMATCH(422). These are internal signals with includeRawCode=false (no scheme_error_code in the response). SchemeCodeMapper.mapInternalSignal(signalKey, requestId) already handles this; this ticket wires the mapping into the Orchestrator error-handling block.
**Steps:** Locate the Hub Core Orchestrator error-handling block (or create an ErrorTranslationService); Add a case for QrParseException: call SchemeCodeMapper.mapInternalSignal(exception.getErrorCode().name(), requestId); Return the resulting GmeApiError as the HTTP response body with the GmeApiError.httpStatus as the response status; Add a case for SchemeAdapterException carrying gme_error_code: use the pre-mapped gme_error_code and http_status directly (no re-mapping needed); Write unit test for ErrorTranslationService: QrParseException(QR_INVALID_CHECKSUM) -> GmeApiError code=QR_INVALID_CHECKSUM, httpStatus=422, scheme_error_code=null
**Deliverable:** ErrorTranslationService.java (or Orchestrator update) handling QrParseException and SchemeAdapterException, with unit tests
**Acceptance / logic checks:**
- QrParseException with errorCode=QR_INVALID_CHECKSUM produces HTTP 422 response with error.code=QR_INVALID_CHECKSUM and no scheme_error_code field
- QrParseException with errorCode=QR_MALFORMED produces HTTP 422 with error.code=QR_MALFORMED
- SchemeAdapterException with gme_error_code=SCHEME_TIMEOUT produces HTTP 504 response
- scheme_error_code is absent (null or not serialised) for QR parse errors
- SchemeAdapterException with gme_error_code=MERCHANT_NOT_FOUND produces HTTP 422 with error.code=MERCHANT_NOT_FOUND and scheme_error_code=1001
**Depends on:** 9.9-T07, 9.9-T10

### 9.9-T16 — Implement ExceptionQueueService: persist ExceptionRecord to DB and surface in Ops portal  _(50 min)_
**Context:** All ExceptionRecord entries raised by BatchCodeMapper, ZP0012ReconciliationService, ZP0022ReconciliationService, and SettlementCodeMapper must be persisted to a DB table batch_exception and made queryable by the Ops Admin portal exception queue (PRD-07). The batch_exception table schema: id UUID PK, gme_txn_id VARCHAR, zeropay_txn_ref VARCHAR, batch_file_type VARCHAR(8), result_code VARCHAR(4), resolved_status VARCHAR(40), message TEXT, raised_at TIMESTAMP WITH TIME ZONE, submitted_amount DECIMAL(14,0) nullable, registered_amount DECIMAL(14,0) nullable, resolved_at TIMESTAMP nullable, resolved_by VARCHAR nullable, ops_notes TEXT nullable, requires_ops_attention BOOLEAN.
**Steps:** Create Flyway migration V9_9_01: batch_exception table with all columns above; Create ExceptionQueueService @Service with ExceptionQueueRepository (JdbcTemplate or JPA); Implement persist(ExceptionRecord record): insert into batch_exception; set requires_ops_attention from BatchCodeMapper.requiresOpsAttention(record.resolved_status()); Implement findPending(): SELECT all rows where resolved_at IS NULL AND requires_ops_attention=true ORDER BY raised_at ASC; Implement resolve(UUID id, String resolvedBy, String notes): set resolved_at=now(), resolved_by, ops_notes, resolved_status=EXCEPTION_RESOLVED; Write integration test: persist 2 records, findPending returns 2, resolve one, findPending returns 1
**Deliverable:** Flyway migration V9_9_01, ExceptionQueueService.java, ExceptionQueueRepository.java
**Acceptance / logic checks:**
- persist saves ExceptionRecord with all fields; gme_txn_id and raised_at are non-null
- findPending excludes records where resolved_at IS NOT NULL
- resolve sets resolved_at to a non-null timestamp and resolved_by to the provided actor string
- requires_ops_attention=true for REGISTRATION_FAILED records; false for SETTLEMENT_REGISTERED
- Two concurrent persist calls for different gme_txn_ids both succeed without conflict (no unique constraint on gme_txn_id - same txn can have multiple exceptions from different batch cycles)
**Depends on:** 9.9-T08, 9.9-T09

### 9.9-T17 — Implement GmeApiErrorSerializer: JSON serialisation of GmeApiError envelope per API-05 §8.1  _(30 min)_
**Context:** API-05 §8.1 specifies the exact JSON structure for error responses: {error: {code: string, message: string, request_id: string, details: array}}. The scheme_error_code field is an extension field present only when includeRawCode was true on the mapping rule. The serialiser must produce exactly this structure with no additional fields. The error.code must be the GmeApiErrorCode name as a string (e.g. MERCHANT_NOT_FOUND). The error.message must be the canonical partner-facing message from GmeApiErrorCode.partnerMessage(). The details array must be empty (not absent) when there are no field-level details. scheme_error_code appears as a top-level field inside the error object when non-null.
**Steps:** Create GmeApiErrorSerializer using Jackson (or equivalent) or a manual toJson() method on GmeApiError; Ensure error.code serialises as the enum name string (not ordinal); Ensure scheme_error_code is omitted from JSON when null (use @JsonInclude(NON_NULL) or equivalent); Ensure details serialises as [] (empty array) not null when the list is empty; Write unit test: serialise GmeApiError(code=MERCHANT_NOT_FOUND, message=..., request_id=req-1, scheme_error_code=1001) and assert JSON string matches expected structure exactly
**Deliverable:** GmeApiError.java with Jackson annotations for correct API-05 §8.1 serialisation, plus serialisation unit test
**Acceptance / logic checks:**
- Serialised JSON has top-level key error with nested keys code, message, request_id, details
- error.code value is the string MERCHANT_NOT_FOUND (not a number or qualified name)
- scheme_error_code=1001 appears inside the error object when set
- scheme_error_code key is absent from JSON when null (not present as null)
- details serialises as [] (empty array) when empty list provided
**Depends on:** 9.9-T07

### 9.9-T18 — Unit tests for SchemeCodeMapper: all 14 real-time code mappings including success and unknown fallback  _(35 min)_
**Context:** SchemeCodeMapper.mapRealtimeCode must correctly handle all 14 rows in ZeroPayRealtimeCodeMappings including the success row and the unknown fallback. Test vectors from SCH-06 §10.2: 0000->success(200), 1001->MERCHANT_NOT_FOUND(422), 1002->MERCHANT_INACTIVE(422), 1003->QR_DEACTIVATED(422), 1004->QR_EXPIRED(422), 2001->AMOUNT_TOO_LOW(422), 2002->AMOUNT_TOO_HIGH(422), 3001->SCHEME_TIMEOUT(504), 3002->SCHEME_ERROR(502), 4001->DUPLICATE_TRANSACTION(409), QR_INVALID_CHECKSUM->422(internal), QR_MALFORMED->422(internal), INSUFFICIENT_PREFUNDING->402(internal), unknown 8888->SCHEME_ERROR(502,includeRawCode=true).
**Steps:** Create SchemeCodeMapperTest; For each of the 14 test vectors, write a named test method calling mapRealtimeCode or mapInternalSignal; Assert httpStatus, code name, and scheme_error_code presence/absence for each vector; For the 0000 success row, assert that the returned GmeApiError has no error code (or a designated SUCCESS sentinel) and httpStatus=200; For unknown code 8888, assert SCHEME_ERROR, httpStatus=502, and scheme_error_code=8888
**Deliverable:** SchemeCodeMapperTest.java with 14 passing test methods
**Acceptance / logic checks:**
- All 14 test methods pass (green)
- mapRealtimeCode(3001, req) returns httpStatus=504 and code=SCHEME_TIMEOUT
- mapRealtimeCode(4001, req) returns httpStatus=409 and code=DUPLICATE_TRANSACTION
- mapInternalSignal(INSUFFICIENT_PREFUNDING, req) returns httpStatus=402, code=INSUFFICIENT_PREFUNDING, scheme_error_code=null
- mapRealtimeCode(8888, req) returns httpStatus=502, code=SCHEME_ERROR, scheme_error_code=8888
**Depends on:** 9.9-T07

### 9.9-T19 — Unit tests for BatchCodeMapper.processZp0012Results: 5 mismatch condition vectors  _(35 min)_
**Context:** BatchCodeMapper.processZp0012Results (9.9-T12) must correctly identify all 5 mismatch conditions from SCH-06 §5.4. Test vectors: (1) result_code=00, submitted_amount=50000, registered_amount=50000 -> SETTLEMENT_REGISTERED, no exception; (2) result_code=9001 -> REGISTRATION_FAILED, ExceptionRecord raised; (3) result_code=9002 -> REGISTRATION_AMOUNT_MISMATCH (code-based, not amount comparison), ExceptionRecord raised; (4) result_code=9003 -> REGISTRATION_UNKNOWN (from batch code alone), ExceptionRecord raised; (5) result_code=00, submitted_amount=50000, registered_amount=49000 -> REGISTRATION_AMOUNT_MISMATCH (amount-based mismatch), ExceptionRecord with submitted_amount=50000 and registered_amount=49000.
**Steps:** Create BatchCodeMapperTest; Implement test_condition_1: result_code=00, amounts match -> SETTLEMENT_REGISTERED, exceptions empty; Implement test_condition_2: result_code=9001 -> REGISTRATION_FAILED, 1 ExceptionRecord in list; Implement test_condition_3: result_code=9002 -> REGISTRATION_AMOUNT_MISMATCH via code-to-status mapping; Implement test_condition_4: result_code=9003 -> REGISTRATION_UNKNOWN (code-based); Implement test_condition_5: result_code=00, submitted=50000 KRW, registered=49000 KRW -> REGISTRATION_AMOUNT_MISMATCH, ExceptionRecord.submittedAmount=50000, ExceptionRecord.registeredAmount=49000
**Deliverable:** BatchCodeMapperTest.java with 5 passing test methods for all mismatch conditions
**Acceptance / logic checks:**
- test_condition_1: updates list has 1 entry with status=SETTLEMENT_REGISTERED; exceptions empty
- test_condition_2: exceptions list has 1 ExceptionRecord with resolved_status=REGISTRATION_FAILED
- test_condition_5: ExceptionRecord.submittedAmount=50000 and registeredAmount=49000 (BigDecimal exact, KRW 0 decimals)
- test_condition_4: resolved_status=REGISTRATION_UNKNOWN (result_code=9003 maps to REGISTRATION_UNKNOWN, not REGISTRATION_FAILED)
- All 5 tests pass with no test depending on ordering of results list
**Depends on:** 9.9-T12

### 9.9-T20 — Unit tests for SettlementCodeMapper: ZP0062/ZP0064 success and discrepancy vectors  _(30 min)_
**Context:** SettlementCodeMapper.processSettlementResult (9.9-T09) handles per-merchant settlement result records. Test vectors from SCH-06 §7.2: (1) result_code=00 for merchant M1, settled_amount_krw=487750 -> SETTLEMENT_CONFIRMED, no exception; (2) result_code=01 (non-zero) for merchant M2 -> SETTLEMENT_DISCREPANCY, ExceptionRecord raised; (3) mixed batch: M1(00, 487750), M2(00, 199100), M3(01, 295000) -> 2 SETTLEMENT_CONFIRMED, 1 SETTLEMENT_DISCREPANCY exception; (4) all-success batch with 10 merchants -> 10 SETTLEMENT_CONFIRMED, empty exception list; (5) settlement amount negative -100 KRW for M4 (invalid) -> SETTLEMENT_DISCREPANCY (caught by validateInboundFile from 9.1-T20 before reaching mapper, but mapper also guards).
**Steps:** Create SettlementCodeMapperTest; Implement test_single_success: M1 result_code=00 -> SETTLEMENT_CONFIRMED, no exception; Implement test_single_discrepancy: M2 result_code=01 -> SETTLEMENT_DISCREPANCY, ExceptionRecord.merchant_id=M2; Implement test_mixed_batch: M1+M2 confirmed, M3 discrepancy -> 2 confirmed updates, 1 exception; Implement test_all_success: 10 records all 00 -> empty exception list; Implement test_negative_amount: M4 settled_amount=-100 -> SETTLEMENT_DISCREPANCY (guard in mapper)
**Deliverable:** SettlementCodeMapperTest.java with 5 passing test methods
**Acceptance / logic checks:**
- test_mixed_batch: processSettlementResult returns 1 ExceptionRecord for M3 with resolved_status=SETTLEMENT_DISCREPANCY
- test_all_success: exception list is empty
- ExceptionRecord for M3 discrepancy contains merchant_id=M3 in message or as a field
- test_negative_amount: resolved_status=SETTLEMENT_DISCREPANCY for negative settled_amount
- All 5 tests pass; no test requires DB access (pure unit tests)
**Depends on:** 9.9-T09

### 9.9-T21 — Integration test: full ZP0012 reconciliation pipeline from parse to ExceptionRecord persistence  _(55 min)_
**Context:** Verify the end-to-end ZP0012 reconciliation pipeline: (1) parseInboundFile(ZP0012 bytes) decodes 3 records, (2) BatchCodeMapper.processZp0012Results assigns statuses, (3) ZP0012ReconciliationService.reconcile cross-checks against submitted ZP0011 records (4 submitted, 1 absent from ZP0012), (4) ExceptionQueueService.persist saves all ExceptionRecord entries, (5) ExceptionQueueService.findPending returns all unresolved exceptions. Use an in-memory H2 database (or TestContainers PostgreSQL) for persistence. The test scenario: 4 submitted transactions, ZP0012 contains: txn1(00, amount match), txn2(9001), txn3(00, amount mismatch 50000 vs 48000), txn4 absent. Expected: txn1=SETTLEMENT_REGISTERED, txn2=REGISTRATION_FAILED, txn3=REGISTRATION_AMOUNT_MISMATCH, txn4=REGISTRATION_UNKNOWN; 3 ExceptionRecords persisted.
**Steps:** Create ZP0012ReconciliationIntegrationTest using @SpringBootTest or @DataJpaTest with test DB; Seed 4 submitted ZP0011 records (in-memory or test DB); Build synthetic ZP0012 file bytes with 3 records (txn1 success, txn2 9001, txn3 amount mismatch); txn4 absent; Call pipeline: parseInboundFile -> BatchCodeMapper.processZp0012Results -> ZP0012ReconciliationService.reconcile -> ExceptionQueueService.persist each exception; Call ExceptionQueueService.findPending(); Assert all statuses and exception details
**Deliverable:** ZP0012ReconciliationIntegrationTest.java with full pipeline test
**Acceptance / logic checks:**
- findPending() returns exactly 3 ExceptionRecord entries after the pipeline run
- txn1 update has status=SETTLEMENT_REGISTERED and no ExceptionRecord
- txn3 ExceptionRecord has submitted_amount=50000 and registered_amount=48000
- txn4 ExceptionRecord has resolved_status=REGISTRATION_UNKNOWN
- All 3 persisted exceptions have requires_ops_attention=true
**Depends on:** 9.9-T12, 9.9-T13, 9.9-T16

### 9.9-T22 — Document the ZeroPay status/reason code mapping: code-level Javadoc and mapping table reference  _(30 min)_
**Context:** The mapping table (SCH-06 §10.1 and §10.2) is illustrative pending 한결원 official spec (Assumption A-10). The implementation must be easy to update when the confirmed code list arrives. This ticket adds code-level documentation so a maintainer can update the mapping table without reading the full spec: Javadoc on ZeroPayRealtimeCodeMappings explaining that ALL_MAPPINGS is the single source of truth and must be reconciled against the official 한결원 ZeroPay API and PPF spec before Phase 2 go-live; a NOTE comment on each enum constant in ZeroPaySchemeCode marking it as illustrative; and a FIXME/TODO linking to OI-SCH-01 (the open item for obtaining the KFTC PPF file spec).
**Steps:** Add class-level Javadoc to ZeroPayRealtimeCodeMappings: state that entries are illustrative per SCH-06 Assumption A-10, and must be verified against 한결원 official spec (OI-SCH-01/OI-SCH-02) before Phase 2 launch; Add // ILLUSTRATIVE - verify against 한결원 spec before Phase 2 comment on each ZeroPaySchemeCode constant; Add a FIXME comment in ZeroPayBatchCodeMappings referencing OI-SCH-01 and Assumption A-10; Add class-level Javadoc to ZeroPayBatchResultCode: note batch codes 9001/9002/9003 are from the ZP PPF interface spec which is pending (OI-SCH-01); Add updateMapping(String zpCode, GmeApiErrorCode gmeCode, boolean includeRaw) method to ZeroPayRealtimeCodeMappings to support runtime config updates (so Ops can add a new code without redeploy - store overrides in DB, load at startup after static defaults)
**Deliverable:** Updated Javadoc and comments on ZeroPayRealtimeCodeMappings, ZeroPaySchemeCode, ZeroPayBatchResultCode, ZeroPayBatchCodeMappings; plus updateMapping() method
**Acceptance / logic checks:**
- ZeroPayRealtimeCodeMappings class-level Javadoc contains the text OI-SCH-01 and illustrative
- Each ZeroPaySchemeCode constant (except UNKNOWN) has the ILLUSTRATIVE comment
- updateMapping(8888, SCHEME_ERROR, true) adds an entry for 8888 that SchemeCodeMapper.mapRealtimeCode(8888) can then resolve to SCHEME_ERROR
- ZeroPayBatchCodeMappings has a FIXME referencing OI-SCH-01
- No existing tests are broken by adding updateMapping (it adds to the map, does not replace existing entries if called with a duplicate - logs WARN instead)
**Depends on:** 9.9-T05, 9.9-T06


## WBS 9.10 — 한결원/KFTC test env integration & cert
### 9.10-T01 — Obtain and store 한결원 UAT SFTP credentials and test-env parameters  _(45 min)_
**Context:** WBS 9.10 gate: before any SFTP-based test can run, GMEPay+ must have 한결원 UAT SFTP credentials (host, port, username, RSA private key, inbound/outbound dirs) stored in the secrets manager. Per SCH-06 §2.3.1, GMEPay+ authenticates to 한결원 SFTP using SSH key (RSA-4096 or ECDSA P-256); the private key must never be stored in plaintext on disk. The scheme config record (scheme_id=ZEROPAY, environment=UAT) must reference the secrets-store key names, not the raw values.
**Steps:** Receive UAT SFTP host, port, username, and private key from 한결원 (OI-SCH-03).; Store private key in the project secrets manager under key ref zeropay_uat_sftp_private_key.; Store PGP public key (한결원) under zeropay_uat_pgp_pub and GME PGP private key under zeropay_uat_pgp_priv.; Update the ZeroPay scheme config record (environment=UAT) with sftp_host, sftp_port, sftp_username, sftp_private_key_ref, pgp_public_key_ref, pgp_private_key_ref, inbound_dir=/gmepay/inbound/, outbound_dir=/gmepay/outbound/.; Confirm no plaintext credential is written to any config file or source-control artifact.
**Deliverable:** ZeroPay UAT scheme config record with all credential refs populated; secrets stored in secrets manager.
**Acceptance / logic checks:**
- SSH connection to 한결원 UAT SFTP host succeeds using the stored key ref (sftp-test CLI or equivalent).
- Scheme config record retrieved via Admin API returns sftp_private_key_ref as an opaque ref string, not a key value.
- Secrets manager holds exactly zeropay_uat_sftp_private_key, zeropay_uat_pgp_pub, zeropay_uat_pgp_priv with non-null values.
- Source control contains zero plaintext credentials (git grep for BEGIN RSA, BEGIN PGP returns no hits in repo).

### 9.10-T02 — Generate and register UAT SSH key pair with 한결원  _(30 min)_
**Context:** Per SCH-06 §2.3.1, GMEPay+ generates a per-environment RSA-4096 (or ECDSA P-256) SSH key pair. The public key must be registered with 한결원 for SFTP allow-listing. Key rotation policy: annually or on suspected compromise. The private key is stored in the secrets manager (ref zeropay_uat_sftp_private_key per T01). This ticket covers key generation, submission of the public key to 한결원, and confirmation of allow-listing.
**Steps:** Generate RSA-4096 SSH key pair for UAT environment (ssh-keygen -t rsa -b 4096 or equivalent in secrets tooling).; Store private key in secrets manager under ref zeropay_uat_sftp_private_key (overwrites placeholder from T01 if needed).; Send public key to 한결원 account contact for SFTP allow-listing registration.; Await confirmation from 한결원 that the public key has been registered (email/ticket confirmation).; Document the key fingerprint and registration date in the ops runbook (OPS-13).
**Deliverable:** RSA-4096 SSH key pair registered with 한결원 UAT SFTP; private key in secrets manager; confirmation recorded.
**Acceptance / logic checks:**
- SFTP connection using the new key succeeds (ssh -i <key> sftp_username@sftp_host) without password prompt.
- Private key is NOT present in any file on disk outside secrets manager after generation.
- Key fingerprint in ops runbook matches fingerprint returned by ssh-keygen -l.
- 한결원 confirmation artifact (email or ticket ref) is attached to this ticket.
**Depends on:** 9.10-T01

### 9.10-T03 — Exchange PGP keys with 한결원 and configure file encryption for UAT  _(40 min)_
**Context:** Per SCH-06 §2.3.2 (Assumption A-02): outbound batch files must be PGP-encrypted with 한결원's public key and signed with GME's private key; inbound files from 한결원 are encrypted with GME's public key. Keys are RSA-2048 minimum (preferably RSA-4096). All PGP operations are performed in-memory; no cleartext intermediate file is written to disk. Key refs zeropay_uat_pgp_pub (한결원 public key) and zeropay_uat_pgp_priv (GME private key) must be loaded in the ZeroPay Adapter's encryption module.
**Steps:** Generate GME PGP key pair (RSA-4096) for UAT environment; store private key under zeropay_uat_pgp_priv in secrets manager.; Export GME PGP public key and send to 한결원 for inbound file encryption.; Receive 한결원 PGP public key; store under zeropay_uat_pgp_pub in secrets manager.; Update ZeroPay Adapter encryption config to load both key refs at startup.; Write a smoke test: encrypt a sample string with zeropay_uat_pgp_pub, then decrypt with zeropay_uat_pgp_priv and verify round-trip.
**Deliverable:** PGP key exchange complete; ZeroPay Adapter encryption module uses stored key refs; round-trip smoke test passes.
**Acceptance / logic checks:**
- Smoke test: PGP-encrypt 'HELLO' with 한결원 public key and sign with GME private key; decrypt and verify signature returns 'HELLO'.
- Adapter startup logs show key refs resolved (not raw key material).
- No PGP private key material present in any log line or config file.
- Inbound test file from 한결원 (sample) successfully decrypted and signature verified using stored keys.
**Depends on:** 9.10-T01

### 9.10-T04 — Verify SFTP connectivity and directory structure against 한결원 UAT server  _(30 min)_
**Context:** Per SCH-06 §2.3.4 (Assumption A-04), the remote SFTP directory structure is /gmepay/outbound/ (GME places files) and /gmepay/inbound/ (ZeroPay places files). GMEPay+ connects outbound-only; no inbound SFTP port is opened. The SFTP client must verify transfer success by comparing file size/checksum after upload (SCH-06 §2.4). This ticket confirms connectivity, directory presence, and upload/download round-trip using the credentials established in T01-T02.
**Steps:** Use the SFTP client module to connect to the 한결원 UAT SFTP host using the stored key ref.; List contents of /gmepay/outbound/ and /gmepay/inbound/ directories; confirm they exist and are accessible.; Upload a test file (test_connectivity_YYYYMMDD.txt) to /gmepay/outbound/.; Verify the transfer by comparing remote file size with local; log checksum.; Download a sample file from /gmepay/inbound/ (if 한결원 has placed one); verify size and checksum.; Record actual directory names if they differ from assumptions and update the scheme config.
**Deliverable:** Connectivity verification report documenting SFTP host, actual directory paths, and upload/download round-trip result.
**Acceptance / logic checks:**
- SFTP connect returns session established within 10 seconds.
- Upload of test_connectivity file to /gmepay/outbound/ succeeds; remote size == local size.
- checksum comparison (MD5 or SHA-256) of uploaded file matches local copy.
- Scheme config inbound_dir and outbound_dir fields reflect actual confirmed directory paths.
**Depends on:** 9.10-T02, 9.10-T03

### 9.10-T05 — Configure ZeroPay UAT scheme registry entry in Admin portal  _(30 min)_
**Context:** Per SCH-06 §1.3.2, all scheme-specific parameters are held in the scheme config record managed via the Ops Admin portal. The scheme registry entry for ZeroPay UAT must include: scheme_id=ZEROPAY, operator_name=한결원, payout_currency=KRW, supported_modes=[MPM,CPM], supported_countries=[KR], realtime_api_base_url (한결원 test API URL), sftp connectivity refs from T01-T04, merchant_fee_table, van_fee_table, gme_fee_share_pct=70, file_retention_days=90. Adding a scheme is CONFIG only per the generic-build constraint (SCH-06 §11.3).
**Steps:** Log into Admin portal as Ops Operator role.; Navigate to Scheme Management; create new scheme record with scheme_id=ZEROPAY, environment=UAT.; Enter all required fields per SCH-06 §1.3.2: operator_name, payout_currency=KRW, supported_modes, supported_countries=[KR], realtime_api_base_url from 한결원 test spec.; Enter merchant_fee_table (e.g. individual=0.80%, franchise=1.20%, cross-border=1.70%), van_fee_table, gme_fee_share_pct=70.; Enter SFTP params referencing secrets-store key names from T01; set file_retention_days=90.; Activate scheme status=ACTIVE; verify audit log captures actor, timestamp.
**Deliverable:** ZeroPay UAT scheme registry entry in Admin portal, status ACTIVE, all fields populated.
**Acceptance / logic checks:**
- GET scheme config API returns scheme_id=ZEROPAY with payout_currency=KRW, supported_modes=[MPM,CPM], gme_fee_share_pct=70.
- Audit log entry created for scheme creation with correct actor and timestamp.
- Scheme registry lists ZeroPay as ACTIVE.
- Credential fields (sftp_private_key_ref, pgp_public_key_ref, pgp_private_key_ref) are returned as opaque refs, not plaintext values.
**Depends on:** 9.10-T04

### 9.10-T06 — Seed test merchants and QR codes via ZP0051 full-list file from 한결원 UAT  _(45 min)_
**Context:** Per SCH-06 §4, GMEPay+ maintains a local merchant and QR DB that is the sole source of truth for payment-time validation. For UAT, 한결원 provides test merchant data. ZP0051 is the periodic full merchant list (same field layout as ZP0041): merchant_id CHAR(10), merchant_name VARCHAR(100), business_reg_no CHAR(10), merchant_type_code CHAR(2), status_code CHAR(1) [A/S/T], address VARCHAR(200), bank_code CHAR(3), account_no VARCHAR(20), effective_date DATE(8), change_type CHAR(1) [I/U/D]. Processing: download -> validate checksum + record count -> load to staging -> diff -> atomic promote. QA-12 §3.2 defines required test merchants: M-TEST-0001 (Active), M-TEST-0002 (Inactive), M-TEST-0003 (Franchise-Active), M-TEST-0004 (CrossBorder-Active), M-TEST-0005 (Active, QR deactivated).
**Steps:** Request ZP0051 test file from 한결원 UAT environment (or generate a synthetic ZP0051 file using QA-12 §3.2 merchant fixtures if 한결원 file not yet available).; PGP-decrypt the file using zeropay_uat_pgp_priv; verify 한결원 signature using zeropay_uat_pgp_pub.; Validate file header: record count and control total match body.; Load records into merchants_staging table; diff against live merchants table.; Atomically promote staging to live in a single DB transaction.; Log result: total count, inserts, updates, deactivations.
**Deliverable:** merchants table in UAT DB seeded with at least 5 test merchants (M-TEST-0001 through M-TEST-0005); full-sync log entry recorded.
**Acceptance / logic checks:**
- merchants table contains M-TEST-0001 with status=ACTIVE and M-TEST-0002 with status=INACTIVE after sync.
- Record count in settlement_batch log entry matches ZP0051 file header control total.
- Rollback test: if staging load is interrupted mid-way, live merchants table is unchanged (atomicity).
- Duplicate-run idempotency: reprocessing the same ZP0051 file produces identical merchant table state without duplicate rows.
**Depends on:** 9.10-T05

### 9.10-T07 — Seed test QR codes via ZP0053 full QR list from 한결원 UAT  _(40 min)_
**Context:** Per SCH-06 §4.2 (ZP0053), the full QR list uses the same layout as ZP0043: qr_code_id CHAR(20), merchant_id CHAR(10), qr_type CHAR(1) [M=MPM static, C=CPM-capable], status_code CHAR(1) [A=Active, D=Deactivated], issue_date DATE(8), deactivation_date DATE(8), change_type CHAR(1) [I/D]. Processing follows staging/diff/atomic-promote pattern as ZP0051. Referential integrity check: every QR must have a valid, active merchant_id in the merchants table. QA-12 §3.2: QR-TEST-0001 (Active, merchant M-TEST-0001), QR-TEST-0005 (Deactivated, merchant M-TEST-0005).
**Steps:** Request ZP0053 test file from 한결원 UAT or generate synthetic file using QA-12 QR fixtures.; PGP-decrypt and verify signature.; Validate file header checksum and record count.; Load into qr_codes_staging; run referential integrity check: every merchant_id must exist and be ACTIVE in merchants table.; Atomically promote to qr_codes live table.; Confirm QR-TEST-0005 has status=INACTIVE (deactivated) in the live qr_codes table.
**Deliverable:** qr_codes table seeded with QR-TEST-0001 through QR-TEST-0005; referential integrity verified.
**Acceptance / logic checks:**
- qr_codes table contains QR-TEST-0001 with status=ACTIVE linked to M-TEST-0001.
- QR-TEST-0005 has status=INACTIVE; any payment attempt using QR-TEST-0005 is rejected with QR_DEACTIVATED at merchant resolution.
- Referential integrity: no qr_codes row exists with a merchant_id absent from the merchants table.
- Duplicate-run idempotency: reprocessing ZP0053 produces identical qr_codes table state.
**Depends on:** 9.10-T06

### 9.10-T08 — Configure test partner rules for domestic (GME Remit) and inbound (SendMN) against ZeroPay UAT  _(40 min)_
**Context:** Per SCH-06 §11.2 Step 4 and RATE-04 canonical facts: a Rule = (partner x scheme x direction). Domestic rule (P-TEST-001 TestRemit, LOCAL, KRW/KRW/KRW): same-currency short-circuit applies (collection=settle_A=settle_B=payout=KRW), m_a=0, m_b=0, service_charge=500 KRW flat, no prefunding. Inbound rule (P-TEST-002 TestSendMN, OVERSEAS, MNT/USD/KRW): cost_rate_coll=treasury.usd_mnt=3500.00, cost_rate_pay=treasury.usd_krw=1350.00, m_a=0.015 (1.5%), m_b=0.010 (1.0%), service_charge=500 MNT, prefunding required; combined margin 2.5% >= 2.0% minimum. Treasury test rates per QA-12 §3.3.
**Steps:** In Admin portal, create rule for P-TEST-001 x ZEROPAY x Domestic: set currency sections (coll=KRW, settle_A=KRW, settle_B=KRW, payout=KRW), m_a=0, m_b=0, service_charge=500 KRW.; Create rule for P-TEST-002 x ZEROPAY x Inbound: currency sections (coll=MNT, settle_A=USD, settle_B=KRW, payout=KRW), m_a=0.015, m_b=0.010, service_charge=500 MNT.; Load treasury test rates: treasury.usd_krw=1350.00, treasury.usd_mnt=3500.00 via Admin FX rate management screen.; Seed P-TEST-002 prefunding balance=50000.00 USD in test DB per QA-12 §3.4.; Activate both rules; verify audit log entries created.
**Deliverable:** Two live rules in UAT: domestic (KRW short-circuit, zero margin) and inbound (MNT->KRW via USD, 2.5% margin); treasury rates and prefunding loaded.
**Acceptance / logic checks:**
- GET rule for P-TEST-001 x ZEROPAY x Domestic returns same_currency_short_circuit=true, m_a=0.000, m_b=0.000, service_charge=500 KRW.
- GET rule for P-TEST-002 x ZEROPAY x Inbound returns m_a=0.015, m_b=0.010, service_charge=500 MNT.
- Admin rejects attempt to save P-TEST-002 rule with m_a=0.010, m_b=0.009 (combined 1.9% < 2.0% minimum) with validation error.
- treasury.usd_krw=1350.00 and treasury.usd_mnt=3500.00 returned by GET /v1/rates test call.
**Depends on:** 9.10-T05

### 9.10-T09 — Execute end-to-end MPM domestic test payment against 한결원 ZeroPay test API  _(50 min)_
**Context:** MPM = merchant presents static QR; customer scans and enters amount. Flow: (1) partner GET /v1/rates with scheme_id=zeropay, direction=domestic, target_payout=13500 KRW; (2) partner POST /v1/payments with quote_id, merchant_qr=QR-TEST-0001; (3) ZeroPay Adapter calls 한결원 real-time API to commit; (4) transaction reaches APPROVED; (5) payment.approved webhook delivered. Domestic short-circuit: collection_amount = 13500 + 500 = 14000 KRW, no USD pool. Per QA-12 HC-001 and RV-04 (same-currency: USD pool skipped, collection_amount = target_payout + service_charge).
**Steps:** Call POST /v1/rates as P-TEST-001 with target_payout=13500, payout_currency=KRW, scheme_id=zeropay, direction=domestic, merchant_qr=QR-TEST-0001.; Verify response: collection_amount=14000 KRW, no payout_usd_cost field, validUntil set.; Call POST /v1/payments with quote_id, merchant_qr=QR-TEST-0001, Idempotency-Key=<uuid>.; Verify ZeroPay Adapter transmits commit to 한결원 test API and receives approval code.; Verify transaction status=APPROVED and payment.approved webhook is fired.; Verify no prefunding deduction occurred (P-TEST-001 is LOCAL).
**Deliverable:** One end-to-end MPM domestic transaction APPROVED on 한결원 ZeroPay test environment; event trail recorded.
**Acceptance / logic checks:**
- POST /v1/rates response: collection_amount=14000 KRW (=13500+500), no payout_usd_cost or collection_usd fields (short-circuit).
- Transaction status=APPROVED in DB with zeropay_txn_ref populated from 한결원 real-time response.
- 8-step event trail in Admin portal shows SCHEME_SENT and SCHEME_APPROVED events with timestamps.
- No prefunding deduction event for P-TEST-001 in prefunding ledger.
- Idempotency: replaying same POST /v1/payments with same Idempotency-Key returns same txn_ref; no second scheme call.
**Depends on:** 9.10-T07, 9.10-T08

### 9.10-T10 — Execute end-to-end MPM inbound test payment (MNT->KRW) against 한결원 ZeroPay test API  _(50 min)_
**Context:** Inbound OVERSEAS flow: P-TEST-002 (TestSendMN, MNT, prefunding USD). Rate engine 5-step RECEIVE mode: cost_rate_pay=treasury.usd_krw=1350.00, cost_rate_coll=treasury.usd_mnt=3500.00, m_a=0.015, m_b=0.010, service_charge=500 MNT, target_payout=13500 KRW. Expected per QA-12 RV-01: payout_usd_cost=10.0000 USD, collection_usd=10.2564 USD, send_amount=35897.44 MNT, collection_amount=36397.44 MNT. Prefunding deducted atomically (SELECT FOR UPDATE) at POST /v1/payments before scheme call. Pool identity: 10.2564 - 0.1538 - 0.1026 = 10.0000 (tolerance <= 0.01 USD).
**Steps:** Call POST /v1/rates as P-TEST-002: target_payout=13500, payout_currency=KRW, scheme_id=zeropay, direction=inbound.; Verify response fields: payout_usd_cost=10.0000, collection_usd=10.2564, send_amount=35897.44, collection_amount=36397.44 MNT (all within 0.01 of expected).; Call POST /v1/payments with quote_id and merchant_qr=QR-TEST-0001.; Verify prefunding balance for P-TEST-002 is reduced by collection_usd=10.2564 USD before scheme call is made.; Verify transaction reaches APPROVED; payment.approved webhook fired.; Verify pool identity assertion: |collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost| <= 0.01 USD passes.
**Deliverable:** One end-to-end MPM inbound transaction APPROVED on 한결원 ZeroPay test environment; prefunding deducted atomically; all RV-01 values match.
**Acceptance / logic checks:**
- collection_amount=36397.44 MNT (+/-0.01 tolerance) in rate quote response.
- Prefunding ledger for P-TEST-002 shows debit of 10.2564 USD with timestamp preceding SCHEME_SENT event.
- Pool identity assertion: |10.2564 - 0.1538 - 0.1026 - 10.0000| <= 0.01 USD.
- Transaction record stores cost_rate_pay=1350.00, cost_rate_coll=3500.00, m_a=0.015, m_b=0.010 permanently (rate-lock).
- offer_rate_coll derived correctly: 35897.44 / (10.2564 - 0.1538) = 35897.44 / 10.1026 = ~3553 MNT/USD (+/-1 unit).
**Depends on:** 9.10-T08, 9.10-T09

### 9.10-T11 — Execute end-to-end CPM test payment (domestic and inbound) against 한결원 ZeroPay test API  _(55 min)_
**Context:** CPM = customer shows dynamic QR; merchant POS scans. Flow: partner POST /v1/payments/cpm/generate -> ZeroPay Adapter calls 한결원 real-time API to get a one-time token (expires default 60s) -> token returned to partner -> merchant scans -> 한결원 relays approval request to ZeroPay Adapter -> commit. For OVERSEAS (P-TEST-002): prefunding deducted at /cpm/generate step, BEFORE merchant scans. For LOCAL (P-TEST-001): no prefunding. Per SCH-06 §3.3 and QA-12 HC-002, HC-004.
**Steps:** CPM domestic: POST /v1/payments/cpm/generate as P-TEST-001; verify CPM token returned; simulate merchant scan via 한결원 test env POS emulator; verify payment.approved.; CPM inbound: POST /v1/payments/cpm/generate as P-TEST-002 with target_payout=13500 KRW; verify prefunding deducted at generate-time; simulate merchant scan; verify payment.approved.; Verify CPM token TTL: attempt to commit after 60 seconds; expect QR_EXPIRED error from 한결원.; Verify CPM token is one-time-use: attempt second commit with same token after first approval; expect rejection.; Confirm both CPM transactions appear in the transaction table with mode=CPM.
**Deliverable:** Two end-to-end CPM transactions APPROVED (one domestic, one inbound); prefunding deduction timing verified; TTL and one-time-use edge cases confirmed.
**Acceptance / logic checks:**
- P-TEST-002 prefunding deduction timestamp is earlier than SCHEME_SENT event timestamp in event trail.
- P-TEST-001 CPM transaction APPROVED with no prefunding deduction event.
- Expired CPM token (>60s) results in QR_EXPIRED error; no prefunding deducted on the attempt.
- Second commit with same CPM token returns DUPLICATE_TRANSACTION or scheme rejection; no double-deduction of prefunding.
- Both transactions have mode=CPM in transaction table.
**Depends on:** 9.10-T10

### 9.10-T12 — Test same-day payment cancel (real-time) against 한결원 ZeroPay test API  _(45 min)_
**Context:** Per SCH-06 §2.2, same-day cancels are processed via ZeroPay real-time API (cancelPayment(scheme_txn_ref)). Per QA-12 HC-007: POST /v1/payments/{id}/cancel within same day -> transaction status=CANCELLED; for OVERSEAS partners, prefunding balance restored. The cancel is NOT included in ZP0021 (refund batch) -- that is only for post-day refunds. Admin-portal refunds (post-day) are out of scope for this ticket.
**Steps:** Create a fresh APPROVED MPM transaction for P-TEST-002 (inbound) with target_payout=13500 KRW; record txn_id and prefunding balance after deduction.; Call POST /v1/payments/{txn_id}/cancel within same business day KST.; Verify ZeroPay Adapter calls cancelPayment on 한결원 real-time API with scheme_txn_ref.; Verify transaction status=CANCELLED in DB.; Verify prefunding balance for P-TEST-002 is restored by collection_usd amount.; Attempt cancel on already-CANCELLED transaction; verify 422 error response.
**Deliverable:** Same-day cancel flow verified against 한결원 UAT; prefunding restored; double-cancel rejected.
**Acceptance / logic checks:**
- Transaction status transitions from APPROVED to CANCELLED; event trail shows CANCEL_SENT and CANCEL_CONFIRMED events.
- Prefunding balance after cancel = balance before original payment (restored to within 0.01 USD).
- POST /v1/payments/{id}/cancel on already-CANCELLED transaction returns HTTP 422 with appropriate error code.
- Cancelled transaction NOT included in subsequent ZP0011 batch file generated for the same business date.
**Depends on:** 9.10-T11

### 9.10-T13 — Test insufficient-prefunding rejection path for OVERSEAS partner  _(45 min)_
**Context:** Per RATE-04 canonical facts and QA-12 HC-009: if OVERSEAS partner balance < collection_usd at payment time, system must reject with INSUFFICIENT_PREFUNDING before any scheme call. Deduction is atomic (SELECT FOR UPDATE). Balance is set to 0.00 USD in test DB per QA-12 §3.4 (P-TEST-002 Depleted fixture). Scheme must NEVER be called when balance is insufficient. For CPM, rejection happens at POST /v1/payments/cpm/generate. Low-balance alert: balance falls below partner threshold -> email alert fired (HC-010, QA-12 §3.4 Low fixture = 9500.00 USD).
**Steps:** Set P-TEST-002 prefunding balance to 0.00 USD in test DB.; Attempt POST /v1/payments (MPM) as P-TEST-002 with target_payout=13500 KRW.; Verify INSUFFICIENT_PREFUNDING error returned (HTTP 422); verify zero scheme calls made.; Set P-TEST-002 balance to 9500.00 USD (low-balance fixture); make a payment that causes balance to drop below threshold.; Verify low-balance alert email is dispatched to partner contact.; Verify the low-balance payment itself succeeds (alert does not block transaction).
**Deliverable:** Insufficient-prefunding rejection and low-balance alert paths verified; no scheme call on zero-balance.
**Acceptance / logic checks:**
- POST /v1/payments with balance=0.00 USD returns HTTP 422 INSUFFICIENT_PREFUNDING; ZeroPay Adapter call count = 0 for this attempt.
- Prefunding ledger shows no deduction entry for the rejected payment attempt.
- Low-balance alert email sent when balance falls below configured threshold (9500 USD test fixture); transaction still APPROVED.
- Concurrent-safety test: two simultaneous payments against a balance of 10.2564 USD (exactly one collection_usd); only one succeeds; the other returns INSUFFICIENT_PREFUNDING.
**Depends on:** 9.10-T10

### 9.10-T14 — Test merchant and QR validation edge cases against local DB  _(40 min)_
**Context:** Per SCH-06 §3.5: merchant validation uses local DB only (no live ZeroPay API call at payment time). Failure cases: (1) merchant status != ACTIVE -> MERCHANT_INACTIVE (QA-12 HC-011, M-TEST-0002); (2) QR status != ACTIVE -> QR_DEACTIVATED (QA-12 HC-012, QR-TEST-0005); (3) QR merchant_id does not match resolved merchant_id -> QR_MALFORMED; (4) merchant absent from DB -> MERCHANT_NOT_FOUND. Error code mapping per SCH-06 §10.2.
**Steps:** Attempt POST /v1/payments with merchant_qr=QR-TEST-0002 (M-TEST-0002, Inactive merchant); expect MERCHANT_INACTIVE.; Attempt POST /v1/payments with merchant_qr=QR-TEST-0005 (QR status=INACTIVE); expect QR_DEACTIVATED.; Attempt POST /v1/payments with a merchant_id that does not exist in the local merchants table; expect MERCHANT_NOT_FOUND.; Attempt POST /v1/payments with a QR code whose merchant_id does not match the merchant_id in the payment request; expect QR_MALFORMED.; For each rejection, verify no scheme call is made and no prefunding deduction occurs.
**Deliverable:** All four merchant/QR validation error paths return correct error codes with zero scheme calls and zero prefunding impact.
**Acceptance / logic checks:**
- QR-TEST-0002 payment returns MERCHANT_INACTIVE (HTTP 422); ZeroPay adapter call count = 0.
- QR-TEST-0005 payment returns QR_DEACTIVATED (HTTP 422); ZeroPay adapter call count = 0.
- Unknown merchant_id returns MERCHANT_NOT_FOUND (HTTP 422).
- Mismatched QR-to-merchant returns QR_MALFORMED (HTTP 422).
**Depends on:** 9.10-T07, 9.10-T08

### 9.10-T15 — Test EMVCo QR parse error paths (checksum, malformed, currency mismatch)  _(40 min)_
**Context:** Per SCH-06 §3.4: parseMerchantQR must verify CRC-16/CCITT in tag 63, confirm tag 00=01 (EMVCo format indicator), extract tag 53 (currency, must be 410 for KRW), locate ZeroPay MAI slot in tags 26-51. Parse errors must be categorised: QR_INVALID_CHECKSUM (CRC mismatch), QR_MALFORMED (missing mandatory tag), QR_CURRENCY_MISMATCH (currency != 410 KRW), QR_UNKNOWN_SCHEME (unknown MAI slot). All parse failures must be rejected before merchant DB lookup; no scheme call.
**Steps:** Craft a QR payload with an invalid CRC-16/CCITT checksum in tag 63; call GET /v1/merchants/{qr} or POST /v1/payments; expect QR_INVALID_CHECKSUM.; Craft a QR payload missing mandatory tag 52 (MCC); expect QR_MALFORMED.; Craft a QR payload with tag 53=840 (USD, not KRW 410); expect QR_CURRENCY_MISMATCH.; Craft a QR payload with an MAI slot not matching the ZeroPay registered range; expect QR_UNKNOWN_SCHEME.; Verify all four errors are returned as HTTP 422 with correct error codes per SCH-06 §10.2 mapping.
**Deliverable:** QR parse error handler unit/integration test covering all 4 parse failure codes; all return HTTP 422.
**Acceptance / logic checks:**
- Invalid CRC payload returns QR_INVALID_CHECKSUM (HTTP 422); no DB lookup performed.
- Missing tag 52 payload returns QR_MALFORMED (HTTP 422).
- Tag 53=840 (USD) payload returns QR_CURRENCY_MISMATCH (HTTP 422).
- Unknown MAI slot returns QR_UNKNOWN_SCHEME (HTTP 422); no scheme call attempted.
**Depends on:** 9.10-T07

### 9.10-T16 — Generate and transmit ZP0011 payment result file to 한결원 UAT SFTP  _(50 min)_
**Context:** Per SCH-06 §5.2: ZP0011 is the daily payment result file (GME->ZeroPay), deadline ~02:00 KST. Scope: all transactions in status APPROVED for the prior business day not yet registered. Record layout (illustrative per A-11): record_type CHAR(1)=D, gme_txn_id CHAR(20), zeropay_txn_ref CHAR(20), merchant_id CHAR(10), qr_code_id CHAR(20), txn_date DATE(8) YYYYMMDD KST, txn_time TIME(6) HHMMSS KST, payout_amount_krw NUM(12), merchant_fee_amt NUM(12), van_fee_amt NUM(10), partner_type CHAR(1) [D=Domestic, I=International], approval_code CHAR(12), status_code CHAR(1)=A. File header: file_type=ZP0011, business_date, GME institution code, record_count, total_payout_amount_krw. Filename: ZP0011_{YYYYMMDD}_01.dat.pgp. PGP-encrypt with 한결원 public key; sign with GME private key.
**Steps:** Trigger the ZP0011 batch job for the test business date (covering transactions from T09 and T10).; Verify file content: header record_count matches detail record count; trailer control_sum = sum of payout_amount_krw.; PGP-encrypt file with zeropay_uat_pgp_pub; sign with zeropay_uat_pgp_priv; write to /gmepay/outbound/ZP0011_{YYYYMMDD}_01.dat.pgp.; Verify SFTP upload success: compare remote file size with local; log checksum.; Verify all included transactions have partner_type=D for P-TEST-001 records and partner_type=I for P-TEST-002 records.; Confirm transactions already registered in a prior batch are excluded.
**Deliverable:** ZP0011 file for test business date successfully transmitted to 한결원 UAT SFTP /gmepay/outbound/; settlement_batch log entry created.
**Acceptance / logic checks:**
- File header record_count equals the number of detail records in the file body.
- Trailer control_sum = sum of all payout_amount_krw values; matches header total_payout_amount_krw.
- SFTP remote file size matches local encrypted file size.
- settlement_batch table row: file_type=ZP0011, status=TRANSMITTED, checksum populated, transmitted_at timestamp set.
- Cancelled transactions (from T12) are NOT included in ZP0011.
**Depends on:** 9.10-T12, 9.10-T13

### 9.10-T17 — Receive and process ZP0012 payment registration result from 한결원 UAT  _(50 min)_
**Context:** Per SCH-06 §5.3: ZP0012 is ZeroPay's registration confirmation, expected by ~05:00 KST. Record layout: zeropay_txn_ref CHAR(20), gme_txn_id CHAR(20), merchant_id CHAR(10), txn_date DATE(8), result_code CHAR(2) [00=Success], result_message VARCHAR(100), registered_amount NUM(12), settlement_date DATE(8). Mismatch handling per §5.4: result_code=00 and amounts match -> SETTLEMENT_REGISTERED; non-zero result_code -> REGISTRATION_FAILED; record in ZP0011 absent from ZP0012 -> REGISTRATION_UNKNOWN; amount discrepancy -> REGISTRATION_AMOUNT_MISMATCH. Zero-tolerance: every record must match.
**Steps:** Poll /gmepay/inbound/ on 한결원 UAT SFTP for ZP0012_{YYYYMMDD}_01.dat.pgp.; PGP-decrypt using zeropay_uat_pgp_priv; verify 한결원 signature using zeropay_uat_pgp_pub.; Parse ZP0012 records; perform line-by-line match against ZP0011 using zeropay_txn_ref+txn_date as key.; Verify all result_code=00 records update transaction status to SETTLEMENT_REGISTERED.; Simulate a non-zero result_code record: verify REGISTRATION_FAILED status set and exception record created in Ops queue.; Simulate a ZP0011 record absent from ZP0012: verify REGISTRATION_UNKNOWN status and exception raised.
**Deliverable:** ZP0012 inbound file parsed and reconciled; all test transactions reach SETTLEMENT_REGISTERED; exception paths verified.
**Acceptance / logic checks:**
- All transactions with result_code=00 have status=SETTLEMENT_REGISTERED in DB after processing.
- A simulated result_code=9001 record creates an exception in the Ops exception queue with priority P1.
- A ZP0011 record absent from ZP0012 creates a REGISTRATION_UNKNOWN exception.
- settlement_batch table updated: file_type=ZP0012, status=PROCESSED, record_count matches file header.
- UNCERTAIN transactions (from any test timed-out scenario) are resolved to APPROVED or FAILED based on ZP0012 result_code.
**Depends on:** 9.10-T16

### 9.10-T18 — Generate and transmit ZP0021 refund result file to 한결원 UAT SFTP  _(45 min)_
**Context:** Per SCH-06 §6.2: ZP0021 covers admin-portal-initiated refunds (post-day only; same-day cancels via real-time API are excluded). Deadline ~02:00 KST. Record layout: record_type=D, gme_refund_id CHAR(20), original_zeropay_txn_ref CHAR(20), gme_original_txn_id CHAR(20), merchant_id CHAR(10), refund_date DATE(8), refund_time TIME(6), refund_amount_krw NUM(12), merchant_fee_adj_amt NUM(12), partner_type CHAR(1), refund_reason_code CHAR(2), status_code=R. File header/trailer same pattern as ZP0011. Same-day cancels (T12) must NOT appear in ZP0021.
**Steps:** Via Admin portal, initiate a post-day refund for one of the T09/T10 test transactions; verify refund record created with status=REFUNDED.; Trigger ZP0021 batch job for the refund business date.; Verify file excludes same-day cancels (T12 transaction must not be present).; PGP-encrypt and transmit to /gmepay/outbound/ZP0021_{YYYYMMDD}_01.dat.pgp.; Verify SFTP upload checksum matches.; Confirm the refund record uses original_zeropay_txn_ref matching the original ZP0011 zeropay_txn_ref.
**Deliverable:** ZP0021 file transmitted for test refund; same-day cancel excluded; original_zeropay_txn_ref cross-reference correct.
**Acceptance / logic checks:**
- ZP0021 file contains the admin-initiated refund record with status_code=R and correct original_zeropay_txn_ref.
- Same-day cancelled transaction (T12) is absent from ZP0021.
- File header record_count equals number of detail records; trailer control_sum = sum of refund_amount_krw.
- settlement_batch row: file_type=ZP0021, status=TRANSMITTED.
**Depends on:** 9.10-T17

### 9.10-T19 — Receive and process ZP0022 refund registration result from 한결원 UAT  _(40 min)_
**Context:** Per SCH-06 §6.3: ZP0022 mirrors ZP0012 for refunds. Match key: original_zeropay_txn_ref + refund_date. Fields: original_zeropay_txn_ref CHAR(20), gme_refund_id CHAR(20), result_code CHAR(2) [00=Success], result_message VARCHAR(100), registered_refund_amount NUM(12), adjustment_settlement_date DATE(8). Mismatch handling identical to §5.4 (REGISTRATION_FAILED, REGISTRATION_UNKNOWN, REGISTRATION_AMOUNT_MISMATCH). An unresolved refund registration failure blocking settlement is a P1 ops incident per §6.4.
**Steps:** Poll /gmepay/inbound/ for ZP0022_{YYYYMMDD}_01.dat.pgp; PGP-decrypt and verify signature.; Parse and line-by-line match against ZP0021 using original_zeropay_txn_ref+refund_date.; Verify result_code=00 sets refund transaction status=SETTLEMENT_REGISTERED.; Simulate result_code=9001 (failure) and verify REGISTRATION_FAILED exception raised with P1 priority.; Verify the adjustment_settlement_date is stored on the refund record.
**Deliverable:** ZP0022 processed; refund transactions reach SETTLEMENT_REGISTERED; exception path for failure codes verified.
**Acceptance / logic checks:**
- Refund transaction status=SETTLEMENT_REGISTERED after result_code=00 processing.
- Simulated result_code=9001 creates P1 exception in Ops queue; settlement for affected refund is blocked.
- registered_refund_amount in ZP0022 matches refund_amount_krw in ZP0021 for successful records.
- settlement_batch row: file_type=ZP0022, status=PROCESSED.
**Depends on:** 9.10-T18

### 9.10-T20 — Generate and transmit ZP0061 morning settlement request to 한결원 UAT SFTP  _(50 min)_
**Context:** Per SCH-06 §7.2 and §8.2: ZP0061 is the primary morning settlement request, deadline ~05:00 KST. Prerequisites (dependency chain): ZP0011 success AND ZP0012 received (result_code=00 for all records). Scope: settlement totals for all transactions in SETTLEMENT_REGISTERED status for the prior business day. Record layout: merchant_id CHAR(10), settlement_date DATE(8), gross_txn_count NUM(6), gross_txn_amount NUM(14), refund_count NUM(6), refund_amount NUM(14), merchant_fee_total NUM(12), net_settlement_amount NUM(14), settlement_type CHAR(1) [N=Net domestic, G=Gross international]. Net settlement (domestic): GME deducts fee share, remits remainder. Gross settlement (international): GME remits full payout_amount_krw.
**Steps:** Confirm prerequisites: ZP0011 and ZP0012 both have status=PROCESSED in settlement_batch table.; Trigger ZP0061 generation; verify net settlement for domestic records (P-TEST-001): net_settlement_amount = gross_txn_amount - merchant_fee_total.; Verify gross settlement for international records (P-TEST-002): net_settlement_amount = gross_txn_amount (full payout, no deduction).; PGP-encrypt and transmit to /gmepay/outbound/ZP0061_{YYYYMMDD}_01.dat.pgp; verify SFTP checksum.; Confirm the batch job does NOT run if ZP0011 or ZP0012 prerequisites are not met.
**Deliverable:** ZP0061 transmitted to 한결원 UAT; net vs gross settlement logic verified; prerequisite gate enforced.
**Acceptance / logic checks:**
- Domestic merchant record: settlement_type=N; net_settlement_amount = gross_txn_amount - merchant_fee_total (verified by formula).
- International merchant record: settlement_type=G; net_settlement_amount = gross_txn_amount (no fee deduction in file).
- ZP0061 generation is blocked (batch job status=WAITING) when ZP0012 is not yet processed.
- settlement_batch row: file_type=ZP0061, status=TRANSMITTED; checksum matches local file.
**Depends on:** 9.10-T19

### 9.10-T21 — Receive and reconcile ZP0062 morning settlement result from 한결원 UAT  _(45 min)_
**Context:** Per SCH-06 §7.2 and §9.1: ZP0062 confirms ZeroPay's processing of ZP0061, expected ~10:00 KST. Per reconciliation rules: settlement reconciliation tolerance = zero (every merchant total must match). Status codes per §10.3: SETTLEMENT_CONFIRMED (amounts match), SETTLEMENT_DISCREPANCY (amounts differ). Discrepancy auto-detection must alert GME Ops within 15 minutes of batch receipt (PRD-07 G-5).
**Steps:** Poll /gmepay/inbound/ for ZP0062_{YYYYMMDD}_01.dat.pgp; PGP-decrypt and verify signature.; Parse ZP0062 records; reconcile per-merchant amounts against ZP0061 submitted values.; Update settlement_batch status and per-transaction settlement status to SETTLEMENT_CONFIRMED where amounts match.; Simulate a settlement discrepancy (ZP0062 merchant amount differs by 1 KRW from ZP0061): verify SETTLEMENT_DISCREPANCY status and exception raised.; Verify Ops alert is generated within 15 minutes of file receipt when discrepancy detected.
**Deliverable:** ZP0062 reconciled; SETTLEMENT_CONFIRMED set for matching records; discrepancy path triggers P1 exception and Ops alert.
**Acceptance / logic checks:**
- Per-merchant amounts in ZP0062 matching ZP0061 set transaction status=SETTLEMENT_CONFIRMED.
- Simulated 1-KRW discrepancy creates SETTLEMENT_DISCREPANCY exception in Ops queue.
- Ops alert (email or dashboard notification) triggered within 15 minutes of ZP0062 file receipt when discrepancy found.
- settlement_batch row: file_type=ZP0062, status=PROCESSED.
**Depends on:** 9.10-T20

### 9.10-T22 — Generate and transmit ZP0063 afternoon settlement request and process ZP0064 result  _(45 min)_
**Context:** Per SCH-06 §7.2 (Assumption A-08): ZP0063 covers transactions approved after the ZP0061 cutoff but needing same-day merchant crediting, deadline ~14:00 KST. ZP0064 (afternoon settlement result) expected ~19:00 KST. Same structure as ZP0061/ZP0062 pair. Dependency per §8.2: ZP0063 depends on morning cycle complete. The test scenario creates a supplementary transaction after the ZP0061 cutoff window.
**Steps:** Create an additional test transaction after the ZP0061 cutoff timestamp; confirm it is excluded from ZP0061.; Trigger ZP0063 generation for the afternoon cycle; verify it includes only the post-cutoff transaction.; PGP-encrypt and transmit ZP0063 to /gmepay/outbound/ZP0063_{YYYYMMDD}_01.dat.pgp.; Poll and process ZP0064 from /gmepay/inbound/; verify per-merchant reconciliation matches ZP0063.; Verify ZP0064 receipt sets affected transaction status to SETTLEMENT_CONFIRMED.
**Deliverable:** ZP0063 transmitted and ZP0064 received/reconciled; afternoon settlement cycle complete in 한결원 UAT.
**Acceptance / logic checks:**
- ZP0063 contains only transactions with approval timestamps after the ZP0061 cutoff; ZP0061 transaction is not duplicated.
- ZP0064 reconciliation: amounts match ZP0063 submission; SETTLEMENT_CONFIRMED set.
- settlement_batch rows for ZP0063 and ZP0064 both have status=PROCESSED.
- ZP0063 generation is blocked (WAITING) if morning ZP0062 receipt has not been processed.
**Depends on:** 9.10-T21

### 9.10-T23 — Generate and transmit ZP0065 payment detail and ZP0066 refund detail files  _(50 min)_
**Context:** Per SCH-06 §7.2: ZP0065 (payment detail, deadline ~22:00 KST) provides transaction-level detail underlying ZP0061/ZP0063 totals. ZP0066 (refund detail) mirrors ZP0065 for refunds. Record layout ZP0065: merchant_id CHAR(10), zeropay_txn_ref CHAR(20), txn_date DATE(8), txn_time TIME(6), payout_amount_krw NUM(12), merchant_fee_amt NUM(12), van_fee_amt NUM(10), partner_type CHAR(1), settlement_batch_ref CHAR(20) (link to ZP0061/ZP0063). ZP0066: merchant_id, original_zeropay_txn_ref CHAR(20), refund_date DATE(8), refund_amount_krw NUM(12), merchant_fee_adj_amt NUM(12), settlement_batch_ref CHAR(20). Detail reconciliation per §9.1: zero-tolerance, every line must match internal ledger.
**Steps:** Trigger ZP0065 generation; verify each detail record maps to a transaction in SETTLEMENT_CONFIRMED status; settlement_batch_ref links back to ZP0061 or ZP0063 batch row.; Trigger ZP0066 generation; verify each refund detail record links to ZP0021 gme_refund_id.; PGP-encrypt and transmit both files by 22:00 KST.; Run internal detail reconciliation: sum of ZP0065 payout_amount_krw per merchant must equal the gross_txn_amount in ZP0061/ZP0063 for that merchant.; Verify ZP0066 refund totals reconcile against ZP0021 submitted amounts.
**Deliverable:** ZP0065 and ZP0066 transmitted; line-item reconciliation against internal ledger passes with zero discrepancies.
**Acceptance / logic checks:**
- Sum of ZP0065 payout_amount_krw per merchant_id = gross_txn_amount in ZP0061 for that merchant (zero discrepancy).
- Each ZP0065 record has settlement_batch_ref matching a ZP0061 or ZP0063 batch row in settlement_batch table.
- ZP0066 record count = count of admin-initiated refunds in ZP0021; refund amounts match.
- settlement_batch rows for ZP0065 and ZP0066: status=TRANSMITTED, transmitted_at within 22:00 KST deadline.
**Depends on:** 9.10-T22

### 9.10-T24 — Test delta merchant sync via ZP0041 incremental file from 한결원 UAT  _(40 min)_
**Context:** Per SCH-06 §4.2 (ZP0041): daily delta file, direction ZeroPay->GME, arriving 01:00-06:00 KST. Fields: merchant_id CHAR(10), merchant_name VARCHAR(100), business_reg_no CHAR(10), merchant_type_code CHAR(2), status_code CHAR(1) [A/S/T], address VARCHAR(200), bank_code CHAR(3), account_no VARCHAR(20), effective_date DATE(8), change_type CHAR(1) [I=Insert, U=Update, D=Deactivate]. GME handling: upsert into merchants table keyed on merchant_id; D records set status=INACTIVE. Deactivated merchants must be blocked at payment time immediately. Idempotency: reprocessing same file must not create duplicates.
**Steps:** Request or generate a ZP0041 test file with: one new merchant (change_type=I), one update to M-TEST-0001 merchant_name (change_type=U), one deactivation of M-TEST-0003 (change_type=D).; PGP-decrypt and process via delta sync job.; Verify new merchant inserted; M-TEST-0001 name updated; M-TEST-0003 status=INACTIVE.; Attempt payment using M-TEST-0003 QR; expect MERCHANT_INACTIVE rejection.; Reprocess same ZP0041 file; verify no duplicate rows and merchant table state is unchanged.
**Deliverable:** ZP0041 delta sync processing verified: insert, update, and deactivation change types all processed correctly; immediate deactivation enforced.
**Acceptance / logic checks:**
- New merchant from ZP0041 I-record exists in merchants table with correct fields.
- M-TEST-0001 merchant_name updated to value in ZP0041 U-record.
- M-TEST-0003 status=INACTIVE after D-record processed; payment attempt returns MERCHANT_INACTIVE.
- Re-running ZP0041 produces identical merchant table state (idempotency); no duplicate rows.
**Depends on:** 9.10-T06

### 9.10-T25 — Test delta QR sync and intraday deactivation via ZP0043 from 한결원 UAT  _(40 min)_
**Context:** Per SCH-06 §4.2 (ZP0043): daily QR delta file. Fields: qr_code_id CHAR(20), merchant_id CHAR(10), qr_type CHAR(1) [M/C], status_code CHAR(1) [A/D], issue_date DATE(8), deactivation_date DATE(8), change_type CHAR(1) [I/D]. GME handling: upsert into qr_codes table; deactivated QR codes must be blocked immediately after DB update. Per Assumption A-07: QR deactivated after day's file has been delivered will not reflect until next day's batch; Ops manual override required (PRD-07). Reconciliation: after applying delta, compare record count against file header.
**Steps:** Request or generate ZP0043 test file with: one new QR registration (change_type=I) and one deactivation of QR-TEST-0001 (change_type=D).; Process ZP0043 via delta sync job; verify new QR inserted and QR-TEST-0001 status=INACTIVE.; Attempt payment using QR-TEST-0001; verify QR_DEACTIVATED error returned immediately.; Test Ops manual override: via Admin portal, force-deactivate a QR code (QR-TEST-0004) outside of batch cycle; verify payment blocked immediately.; Reprocess ZP0043; verify idempotency.
**Deliverable:** ZP0043 delta sync processes new and deactivated QR codes; payment blocked immediately on deactivation; Ops manual override functional.
**Acceptance / logic checks:**
- QR-TEST-0001 status=INACTIVE after ZP0043 D-record processed; payment returns QR_DEACTIVATED.
- New QR from ZP0043 I-record is ACTIVE and usable in a test payment.
- Admin portal manual deactivation of QR-TEST-0004 blocks payment within the same request cycle (no cache delay).
- Re-running ZP0043 is idempotent; qr_codes table unchanged.
**Depends on:** 9.10-T07, 9.10-T14

### 9.10-T26 — Test full merchant list sync (ZP0051) with staging diff and atomic promote  _(50 min)_
**Context:** Per SCH-06 §4.2 (ZP0051): periodic full merchant list, same field layout as ZP0041 but all active merchants. Processing: download -> validate checksum + record count -> load to merchants_staging -> diff vs live merchants -> apply inserts/updates/soft-deletes for absent merchants -> promote atomically. Failure must not partially overwrite live data (rollback on error). Full-sync failures retain previous data and alert Ops. The scheduler must detect whether a full file is present and suppress ZP0041 delta upsert for the same entity type on that processing run.
**Steps:** Request or generate a ZP0051 full merchant file with a known total record count and a merchant absent from the current live merchants table.; Process via full-sync job; verify staging table loaded, diff computed, live table atomically replaced.; Verify merchants absent from ZP0051 are soft-deleted (status=INACTIVE) in live merchants table.; Simulate mid-sync failure (kill job after staging load but before promote); verify live merchants table is unchanged.; Verify ZP0041 delta processing is suppressed on the same run when ZP0051 is present.
**Deliverable:** ZP0051 full-sync processing verified: atomic promote, rollback on failure, delta suppression, and record-count reconciliation.
**Acceptance / logic checks:**
- Live merchants table record count matches ZP0051 header control total after successful sync.
- Merchant present in live table but absent from ZP0051 has status=INACTIVE after sync.
- Simulated mid-sync interruption: live merchants table is identical to pre-sync state (rollback confirmed).
- ZP0041 processing is skipped (log entry: DELTA_SUPPRESSED_FULL_SYNC_PRESENT) when ZP0051 detected in same batch run.
**Depends on:** 9.10-T24

### 9.10-T27 — Test SFTP transfer failure retry and Ops alerting for outbound batch files  _(45 min)_
**Context:** Per SCH-06 §2.4 and §9.2: on SFTP transfer failure, retry up to 3 times with exponential back-off (30s, 2min, 10min), then alert Ops and halt. Subsequent dependent batch jobs must NOT be attempted after halt. Per §9.5 escalation table: ZP0011/ZP0021 outbound cutoff=02:00 KST; ZP0061 cutoff=05:00 KST. Retransmission uses incremented sequence number (e.g. ZP0011_{YYYYMMDD}_02.dat.pgp) with same content. Re-processing inbound files must be idempotent (upsert, no duplicates).
**Steps:** Mock SFTP failure on first two PUT attempts for a ZP0011 file (e.g. return connection-refused); verify retry attempts with back-off delays of 30s and 2min.; On third retry success, verify file transmitted with original sequence number _01.; Force all 3 retries to fail; verify Ops alert raised and batch job halts.; Verify that ZP0061 (dependent on ZP0011) is placed in WAITING status, not ERROR, when ZP0011 halts.; Simulate manual re-transmit with sequence number _02 for ZP0011; verify re-processed content is identical to _01; verify ZP0012 mismatch check handles the re-transmit correctly.
**Deliverable:** SFTP retry logic and failure alerting verified; exponential back-off confirmed; dependent job correctly WAITING; re-transmit idempotency confirmed.
**Acceptance / logic checks:**
- Retry log shows attempts at T+0, T+30s, T+2min with the correct delay pattern.
- After 3-retry failure, batch_job status=HALTED and Ops alert delivered.
- ZP0061 batch_job status=WAITING (not ERROR or RUNNING) when ZP0011 is in HALTED state.
- Re-transmit ZP0011_..._02 file with same records: ZP0012 reconciliation produces no REGISTRATION_UNKNOWN anomalies; idempotent upsert confirmed.
**Depends on:** 9.10-T16

### 9.10-T28 — Test late file detection and Ops alert for inbound ZP0012  _(40 min)_
**Context:** Per SCH-06 §9.3 and §9.5: if an expected inbound file has not arrived by deadline + 60 minutes, a BATCH_FILE_LATE alert is raised and dependent jobs enter WAITING state. For ZP0012: expected by ~05:00 KST; escalation if not received by 06:00 KST (05:00 + 60 min). Ops may manually trigger downstream jobs once the file is received. Escalation to 한결원 account contact if file not arrived by 08:00 KST.
**Steps:** Configure test: set ZP0012 expected window to 05:00 KST; advance test clock past 06:00 KST without placing ZP0012 in inbound SFTP dir.; Verify BATCH_FILE_LATE alert raised with file_type=ZP0012, expected_by=05:00 KST, alert_time=06:00 KST.; Verify ZP0061 batch job status=WAITING (not ERROR) pending ZP0012 arrival.; Place ZP0012 file in inbound SFTP dir; verify file is picked up and processed; ZP0061 batch job becomes eligible to run.; Verify Ops can manually trigger ZP0061 via Admin portal once ZP0012 is processed.
**Deliverable:** Late-file detection and WAITING-state dependency for ZP0012->ZP0061 chain verified; Ops alert and manual trigger functional.
**Acceptance / logic checks:**
- BATCH_FILE_LATE alert raised at deadline+60min with correct file_type and expected_by fields.
- ZP0061 batch_job status=WAITING when ZP0012 is overdue; ZP0061 does NOT execute automatically.
- After ZP0012 is placed and processed, ZP0061 becomes manually triggerable via Admin portal.
- Ops alert includes file_type=ZP0012, expected_at=05:00 KST, and alert escalation note at 08:00 KST threshold.
**Depends on:** 9.10-T17, 9.10-T27

### 9.10-T29 — Test corrupt/parse-error inbound file handling for ZP0012  _(40 min)_
**Context:** Per SCH-06 §9.2: file parse error or corrupt file is a P1 exception. Action: reject file, raise Ops alert, request retransmission from 한결원. Per §9.4 retransmission: ZeroPay replaces the file in the SFTP inbound directory. Reprocessing must be idempotent. Per §2.3.2 PGP: signature verification failure on inbound file must also be treated as corrupt (potential tampering).
**Steps:** Place a ZP0012 file with a corrupted record (invalid field length, truncated line) in the inbound SFTP directory.; Process via inbound job; verify parse fails; settlement_batch row status=PARSE_ERROR; Ops alert raised (P1).; Place a ZP0012 file with invalid PGP signature; verify signature verification failure raises P1 alert; file rejected.; Simulate retransmission: place a corrected ZP0012 file (same business date); verify it is processed successfully.; Verify idempotency: processing the valid retransmission file does not duplicate any DB updates from partial processing of the corrupt file.
**Deliverable:** Corrupt and PGP-invalid inbound file handling verified; P1 alerts raised; retransmission idempotency confirmed.
**Acceptance / logic checks:**
- Corrupt ZP0012 file: settlement_batch status=PARSE_ERROR; P1 Ops alert delivered; no partial DB updates applied.
- PGP signature failure: file rejected with PGP_SIGNATURE_INVALID error; P1 Ops alert delivered.
- Valid retransmission file processed successfully; all records match SETTLEMENT_REGISTERED expectations.
- DB state after retransmission processing is identical to state from a clean first-time processing (idempotency).
**Depends on:** 9.10-T17

### 9.10-T30 — Unit tests: ZP0011 file generator with header, detail records, trailer, and control-sum  _(45 min)_
**Context:** ZP0011 generator (SCH-06 §5.2) must produce: header (file_type=ZP0011, business_date, GME institution code, record_count, total_payout_amount_krw), detail records (record_type=D, gme_txn_id, zeropay_txn_ref, merchant_id, qr_code_id, txn_date, txn_time, payout_amount_krw, merchant_fee_amt, van_fee_amt, partner_type, approval_code, status_code=A), trailer (record_count repeat, control_sum). Matching key: zeropay_txn_ref+txn_date. Edge cases: zero transactions -> empty body but valid header/trailer; KRW amounts are integers (0 decimals per money-handling rules); txn_time in KST (UTC+9 conversion).
**Steps:** Write unit test: generate ZP0011 for 3 test transactions (2 domestic P-TEST-001, 1 international P-TEST-002); assert header record_count=3 and total_payout_amount_krw = sum of 3 payout_amount_krw values.; Write unit test: zero-transaction case; assert header record_count=0, trailer control_sum=0, file contains header + empty body + trailer only.; Write unit test: partner_type field = D for P-TEST-001 transactions and I for P-TEST-002 transactions.; Write unit test: txn_time is KST (UTC+9); a transaction at 2026-10-15T13:00:00Z appears as txn_time=220000 in file.; Write unit test: KRW amounts are integers (no decimal point in payout_amount_krw field).
**Deliverable:** JUnit/equivalent test class ZP0011GeneratorTest with 5 test methods; all pass.
**Acceptance / logic checks:**
- header record_count=3 for 3-transaction input; total_payout_amount_krw equals sum of individual payout amounts.
- Zero-transaction file is syntactically valid (header + trailer present); no detail lines.
- partner_type=D for domestic; partner_type=I for international transaction in same file.
- UTC 13:00:00 stored as KST 22:00:00 in txn_time field.
- payout_amount_krw=13500 stored as the integer string 13500 with no decimal point.
**Depends on:** 9.10-T16

### 9.10-T31 — Unit tests: ZP0012 inbound parser and mismatch reconciliation logic  _(45 min)_
**Context:** ZP0012 parser (SCH-06 §5.3-5.4) must: parse zeropay_txn_ref+txn_date as composite match key; map result_code=00 to SETTLEMENT_REGISTERED; non-zero to REGISTRATION_FAILED; absent-from-ZP0012 record to REGISTRATION_UNKNOWN; amount discrepancy (registered_amount != payout_amount_krw in ZP0011) to REGISTRATION_AMOUNT_MISMATCH; ZP0012 record absent from ZP0011 to anomaly log. Zero-tolerance: any mismatch generates an exception record.
**Steps:** Write unit test: ZP0012 with result_code=00 and matching amounts -> SETTLEMENT_REGISTERED.; Write unit test: ZP0012 with result_code=9001 -> REGISTRATION_FAILED; exception record created.; Write unit test: ZP0011 record not in ZP0012 -> REGISTRATION_UNKNOWN; exception record created.; Write unit test: amounts match but registered_amount differs by 1 KRW -> REGISTRATION_AMOUNT_MISMATCH; exception created.; Write unit test: ZP0012 contains a zeropay_txn_ref not present in ZP0011 -> anomaly log entry; no exception raised (just log).
**Deliverable:** JUnit/equivalent test class ZP0012ReconciliationTest with 5 test methods; all pass.
**Acceptance / logic checks:**
- result_code=00 with matching amounts returns ReconciliationStatus.SETTLEMENT_REGISTERED.
- result_code=9001 returns ReconciliationStatus.REGISTRATION_FAILED and creates one exception record.
- ZP0011 record absent from ZP0012 returns ReconciliationStatus.REGISTRATION_UNKNOWN and creates one exception record.
- amount discrepancy of 1 KRW returns ReconciliationStatus.REGISTRATION_AMOUNT_MISMATCH; exception record contains both submitted and registered amounts.
- ZP0012 extra record (not in ZP0011) creates anomaly log entry; exception count remains 0 for that record.
**Depends on:** 9.10-T17

### 9.10-T32 — Unit tests: ZP0061 settlement request generator (net vs gross logic)  _(45 min)_
**Context:** ZP0061 generator (SCH-06 §7.2, §7.1): aggregate per-merchant. Net settlement (domestic, settlement_type=N): net_settlement_amount = gross_txn_amount - merchant_fee_total - refund_amount. Gross settlement (international, settlement_type=G): net_settlement_amount = gross_txn_amount (full payout; GME invoices merchant separately monthly). Prerequisite gate: ZP0011 success AND ZP0012 processed with no REGISTRATION_FAILED or REGISTRATION_UNKNOWN exceptions.
**Steps:** Write unit test: domestic merchant M-TEST-0001 with gross_txn_amount=13500 KRW, merchant_fee_total=108 KRW (0.80% fee), refund_amount=0 -> net_settlement_amount=13392 KRW, settlement_type=N.; Write unit test: international merchant M-TEST-0004 with gross_txn_amount=13500 KRW -> net_settlement_amount=13500 KRW, settlement_type=G.; Write unit test: merchant with 2 payments and 1 refund; gross_txn_count=2, refund_count=1; net=sum_payments-refund-fees.; Write unit test: prerequisite gate not met (REGISTRATION_FAILED exception outstanding) -> generator throws exception; ZP0061 not produced.; Write unit test: zero transactions for a day -> ZP0061 file has header/trailer with count=0; no merchant detail records.
**Deliverable:** JUnit/equivalent test class ZP0061GeneratorTest with 5 test methods; all pass.
**Acceptance / logic checks:**
- Domestic net_settlement_amount = gross_txn_amount - merchant_fee_total for settlement_type=N.
- International net_settlement_amount = gross_txn_amount for settlement_type=G (fee not deducted in file).
- Multi-transaction merchant: gross_txn_count=2, gross_txn_amount=sum of both; refund correctly subtracted.
- REGISTRATION_FAILED outstanding exception causes generator to throw SettlementPrerequisiteException; ZP0061 not written to disk.
- Zero-transaction day produces valid ZP0061 with header record_count=0.
**Depends on:** 9.10-T20

### 9.10-T33 — Unit tests: EMVCo QR parser (CRC, tag extraction, ZeroPay MAI)  _(40 min)_
**Context:** Per SCH-06 §3.4: parseMerchantQR must verify CRC-16/CCITT in tag 63; confirm tag 00=01; extract tag 53 (must be 410 for KRW); locate ZeroPay MAI slot (tags 26-51); extract merchant_id, qr_code_id, merchant_name from MAI. Error codes: QR_INVALID_CHECKSUM, QR_MALFORMED (missing mandatory tag), QR_CURRENCY_MISMATCH (tag 53 != 410), QR_UNKNOWN_SCHEME (MAI slot not ZeroPay). The MAI slot tag is configurable (Assumption A-05). Return: normalised MerchantIdentifier object with merchant_id and qr_code_id.
**Steps:** Write unit test: valid ZeroPay EMVCo QR payload -> returns MerchantIdentifier with correct merchant_id and qr_code_id.; Write unit test: flip 1 bit in CRC (tag 63) -> QrParseException(QR_INVALID_CHECKSUM).; Write unit test: payload with tag 53=840 (USD) -> QrParseException(QR_CURRENCY_MISMATCH).; Write unit test: payload missing tag 52 (MCC) -> QrParseException(QR_MALFORMED).; Write unit test: MAI slot set to a non-ZeroPay GUID -> QrParseException(QR_UNKNOWN_SCHEME).
**Deliverable:** JUnit/equivalent test class EmvcoQrParserTest with 5 test methods; all pass.
**Acceptance / logic checks:**
- Valid payload returns MerchantIdentifier with merchant_id and qr_code_id matching payload values.
- 1-bit CRC flip throws QrParseException with code QR_INVALID_CHECKSUM.
- tag 53=840 throws QrParseException with code QR_CURRENCY_MISMATCH.
- Missing tag 52 throws QrParseException with code QR_MALFORMED.
- Non-ZeroPay MAI throws QrParseException with code QR_UNKNOWN_SCHEME.
**Depends on:** 9.10-T15

### 9.10-T34 — Unit tests: SFTP transfer module (upload, checksum verify, retry back-off)  _(40 min)_
**Context:** Per SCH-06 §2.4: SFTP transfer process for outbound files: generate in memory -> temp file -> PGP encrypt -> SFTP PUT -> verify checksum (compare remote file size) -> on success move to archive; on failure retry 3x with back-off (30s, 2min, 10min) -> alert and halt. The transfer module is invoked by every batch job. Test with mock SFTP server.
**Steps:** Write unit test: successful upload -> transferResult.status=SUCCESS; remote path = /gmepay/outbound/{filename}; local archive copy created.; Write unit test: first 2 attempts fail (mock throws SftpException); 3rd attempt succeeds -> result=SUCCESS after 2 retries; retry count=2 logged.; Write unit test: all 3 attempts fail -> transferResult.status=HALTED; Ops alert event fired; retryCount=3.; Write unit test: checksum mismatch after upload (remote size != local size) -> treated as failure; retry triggered.; Write unit test: verify retry delay sequence: delays[0]=30s, delays[1]=120s, delays[2]=600s from SftpTransferConfig defaults.
**Deliverable:** JUnit/equivalent test class SftpTransferModuleTest with 5 test methods; all pass using mock SFTP.
**Acceptance / logic checks:**
- Successful upload creates local archive copy and returns status=SUCCESS.
- 2-failure-then-success scenario: status=SUCCESS, retryCount=2 in transfer log.
- 3-failure scenario: status=HALTED, Ops alert event published exactly once.
- Checksum mismatch triggers retry; retryCount incremented.
- Retry delay config values: delays=[30,120,600] seconds as defaults.
**Depends on:** 9.10-T27

### 9.10-T35 — Execute full 24-hour ZeroPay batch cycle E2E test in 한결원 UAT  _(60 min)_
**Context:** This test runs the complete daily batch cycle end-to-end against 한결원 UAT to validate the integration as a whole. Batch schedule (SCH-06 §7.3, all times KST): ~02:00 ZP0011+ZP0021 outbound; ~05:00 ZP0012+ZP0022 inbound + ZP0061 outbound; ~10:00 ZP0062 inbound; ~14:00 ZP0063 outbound; ~19:00 ZP0064 inbound; ~22:00 ZP0065+ZP0066 outbound. Merchant/QR sync files arrive 01:00-06:00. Dependency chain per §8.2 must be enforced. Phase 2 gate criterion per PM-14 §5.2: all ZP00xx file types verified; discrepancy auto-detection alerts within SLA.
**Steps:** Seed the UAT environment with 3+ test transactions (MPM domestic, MPM inbound, admin refund) using credentials from T08-T11.; Execute batch jobs in order, respecting the dependency chain; use accelerated timing in test environment (no need to wait for real KST times).; Verify each outbound file transmitted successfully; each inbound file received and reconciled.; Verify final reconciliation: ZP0065 line items match ZP0061 totals; ZP0066 refund totals match ZP0021.; Run the UNCERTAIN transaction path: timeout one scheme call before batch; verify ZP0012 resolves it within 24h.; Confirm all settlement_batch rows end in status=PROCESSED or TRANSMITTED with zero exceptions outstanding.
**Deliverable:** Full 24-hour ZeroPay batch cycle E2E test completed successfully against 한결원 UAT; settlement_batch table shows all 10 file types processed with zero exceptions.
**Acceptance / logic checks:**
- All 10 outbound file types (ZP0011, ZP0021, ZP0061, ZP0063, ZP0065, ZP0066) have settlement_batch.status=TRANSMITTED.
- All 4 inbound file types (ZP0012, ZP0022, ZP0062, ZP0064) have settlement_batch.status=PROCESSED.
- Final ledger reconciliation: sum of ZP0065 payout_amount_krw per merchant = gross_txn_amount in ZP0061 (zero discrepancy).
- UNCERTAIN transaction resolved to APPROVED or FAILED via ZP0012 within the same batch cycle.
- Zero open exception records in the Ops exception queue at end of cycle.
**Depends on:** 9.10-T23, 9.10-T25, 9.10-T26, 9.10-T28, 9.10-T29

### 9.10-T36 — Obtain formal 한결원 KFTC scheme certification sign-off  _(60 min)_
**Context:** Per SCH-06 §11.2 Step 5 and PM-14 §5.2 Phase 2 gate: certification requires a successful end-to-end ZeroPay test transaction with the 한결원 test environment. 한결원/KFTC may require submission of test evidence (batch cycle logs, reconciliation reports, transaction records) before issuing production credentials. The certification deliverable is a written confirmation from 한결원 that GMEPay+ has passed integration testing and is approved to receive production SFTP credentials and real-time API production access.
**Steps:** Compile certification evidence package: E2E test cycle results from T35, reconciliation reports, settlement_batch table export, Ops exception queue (zero open items), SFTP transfer logs with checksums.; Submit evidence package to 한결원/KFTC account contact along with GME institution code and production IP address (NAT Gateway static IP per SAD-02 §10.4 Assumption A-04).; Participate in any 한결원-directed review calls or additional test scenarios they require.; Receive written certification confirmation from 한결원 (email or signed test completion certificate).; File certification artefact in the project repository (docs/certification/) and update the PM-14 RAID log entry for R-01 (한결원 test environment dependency) to CLOSED.
**Deliverable:** Signed/emailed certification confirmation from 한결원 KFTC; stored in docs/certification/; PM-14 RAID R-01 closed.
**Acceptance / logic checks:**
- Certification artefact is a written statement from 한결원 confirming GMEPay+ has passed integration testing.
- docs/certification/ directory contains the artefact with filename including the confirmation date.
- PM-14 RAID log R-01 status updated to CLOSED with certification date noted.
- Production SFTP credentials received from 한결원 (or confirmed to be forthcoming) as a result of passing certification.
**Depends on:** 9.10-T35

<!-- wbs-v3-gap-closure -->

---

## WBS v3 gap-closure tickets (re-baseline, 2026-06-10)

These tickets convert this service's PARTIAL audit findings into DONE and add work discovered during the build. Statuses live on the `Backlog` sheet of `GMEPay+_Task_Backlog.xlsx`; phase sequencing on the `Completion Plan v3` sheet of `GMEPay+_WBS.xlsx`.

### 17.2-G10 — scheme-adapter-zeropay: swap H2 for real PostgreSQL ITs
*Completion phase:* **R1** · *Est:* 120 min · *Role:* Backend · *Deps:* 17.1-G02

**Context.** Tests currently run on H2 in PostgreSQL mode. Acceptance requires real PG. Scope: batch file registry + ZP record staging.

**Steps.**
- Add Testcontainers postgres:16 to the service's ITs
- Run Flyway migrations against it; fix PG-only syntax drift
- Keep H2 only for pure unit slices

**Deliverable.** Repository/migration ITs green on PostgreSQL 16

**Acceptance.**
- ./gradlew :services:scheme-adapter-zeropay:test green with Testcontainers
- Migration checksum stable; no H2-mode workarounds left

---

<!-- ws-21-partner-setup-rebaseline -->

## Partner Setup re-baseline tickets (WS 21)

These tickets close Partner Setup audit gaps under the 8-slice vertical plan in `docs/PARTNER_SETUP_PLAN.md` (approved 2026-06-11). Each ticket id `21.{slice}-Pxx` maps to a wizard slice; ADR references point at `docs/adr/`. Tickets owned by **scheme-adapter-zeropay** live here; cross-service contributions are listed at the bottom for awareness.

> Note: legacy WP 10.3 entries on the WBS spreadsheet remain in place but are flagged *superseded by WS 21 — see docs/PARTNER_SETUP_PLAN.md*.

### Slice 7 tickets owned by this service

### 21.7-P03 — scheme-adapter-zeropay: rewrite SchemeRouter from hardcoded to data-driven
*Slice:* **7** · *Est:* 120 min · *Role:* Backend · *Owner:* scheme-adapter-zeropay · *ADR refs:* —

**Context.** Today SchemeRouter has `if (country == KR) route to ZeroPay`. Slice 7 makes it data-driven: routes by partner_scheme + partner_corridor joins. Adding a new corridor requires no code change.

**Steps.** Replace `services/scheme-adapter-zeropay/src/main/java/com/gme/pay/zeropay/SchemeRouter.java` hardcoded logic with `RuleSetEngine.resolve(partnerId, srcCountry, srcCcy, dstCountry, dstCcy): SchemeRoute` reading from config-registry; cache routes in Redis with TTL=300s; emit `gmepay.scheme.routed` audit event with the resolved route.

**Deliverable.** `services/scheme-adapter-zeropay/src/main/java/com/gme/pay/zeropay/SchemeRouter.java; services/scheme-adapter-zeropay/.../RuleSetEngine.java`

**Acceptance.**
- Second KR partner with different sub-merchant config routes correctly without code change
- Inactive corridor (is_active=false) returns CORRIDOR_INACTIVE 422
- Cache hit on repeat lookup (Redis MONITOR shows single DB call)
- Adding a brand-new corridor via SQL INSERT makes the route work next call

### Cross-service contributions touching this service

Tickets owned elsewhere but with code or schema touchpoints in this service. Listed here so this bundle remains the single read for a service developer.

- **21.4-P04** (shared-libs, Slice 4) — AccountVerificationProvider port + KftcVerificationAdapter + StubVerificationAdapter

