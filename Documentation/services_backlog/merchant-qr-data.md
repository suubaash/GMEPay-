# merchant-qr-data  (backend)

**Scope:** Merchant/QR store + sync (Mongo), local validation

**Owned WBS work-packages:** 9.3  ·  **Tickets:** 30  ·  **Est:** 18.9h

## Service contract (MSA: own DB, API-only communication)

- **Datastore (owned by this service):** MongoDB `merchant` (merchant + QR projections)
- **APIs / events I EXPOSE:** GET /v1/merchants/{qr}
- **APIs / events I CONSUME:** scheme-adapter (receives sync files -> updates store)
- **Integration rule:** never read another service's database or import its private entities — call its API or consume its event; stub consumed services with WireMock in tests.

> Self-contained backlog for this service. Build it as its own repo/module with its own DB + Flyway migrations, against the `shared-libs` contracts (lib-money / lib-errors / lib-events / lib-api-contracts only). Each ticket has a deliverable + acceptance checks.


## WBS 9.3 — Merchant & QR synchronization
### 9.3-T01 — Flyway migration V1: merchant, qr_code, franchise_groups, merchant_sync_log tables  _(35 min)_
**Context:** WBS 9.3 Merchant and QR Synchronisation. The merchant-qr-data service (Gradle module services/merchant-qr-data) owns a PostgreSQL 16 canonical store. Tables: merchant (id BIGSERIAL PK, merchant_id VARCHAR(50) UNIQUE NOT NULL, merchant_type VARCHAR(30), name VARCHAR(200), business_registration_no VARCHAR(30), franchise_code VARCHAR(30), category_code VARCHAR(20), bank_code VARCHAR(10), account_no VARCHAR(30) [AES-256 encrypted at rest via app layer], fee_tier VARCHAR(20), status VARCHAR(20) CHECK IN (ACTIVE,SUSPENDED,DEACTIVATED), is_active BOOLEAN NOT NULL DEFAULT TRUE, is_franchise BOOLEAN NOT NULL DEFAULT FALSE, synced_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()); qr_code (id BIGSERIAL PK, qr_code_id VARCHAR(20) UNIQUE NOT NULL, merchant_id BIGINT FK->merchant(id), qr_type CHAR(1) CHECK IN (M,C), status VARCHAR(20) CHECK IN (ACTIVE,DEACTIVATED), is_active BOOLEAN NOT NULL DEFAULT TRUE, issue_date DATE, deactivation_date DATE, synced_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()); franchise_groups (id BIGSERIAL PK, franchise_code CHAR(6) UNIQUE NOT NULL, franchise_name VARCHAR(100), franchise_type CHAR(2), head_merchant_id VARCHAR(50), status CHAR(1), created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()); merchant_sync_log (id BIGSERIAL PK, file_type VARCHAR(10), sync_date DATE, records_received INT, records_inserted INT, records_updated INT, records_deactivated INT, error_count INT, status VARCHAR(20) CHECK IN (SUCCESS,PARTIAL,FAILED), detail TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT now()). account_no encryption is at the application layer; column type is VARCHAR(30).
**Steps:** Create services/merchant-qr-data/src/main/resources/db/migration/V1__merchant_qr_schema.sql; Add merchant table DDL with all columns and CHECK constraints as described; Add qr_code table DDL with FK to merchant(id), UNIQUE on qr_code_id; Add franchise_groups table DDL with UNIQUE on franchise_code; Add merchant_sync_log table DDL with CHECK on status; Add indexes: merchant(status), merchant(franchise_code), qr_code(merchant_id), qr_code(status); Validate with Flyway in Testcontainers PostgreSQL 16 via ./gradlew :merchant-qr-data:flywayValidate
**Deliverable:** services/merchant-qr-data/src/main/resources/db/migration/V1__merchant_qr_schema.sql
**Acceptance / logic checks:**
- Flyway applies V1 on a fresh PostgreSQL 16 Testcontainers instance with zero errors
- merchant table has UNIQUE constraint on merchant_id and CHECK constraint on status
- qr_code.merchant_id FK references merchant(id) and is NOT NULL
- merchant_sync_log.status column rejects values outside SUCCESS/PARTIAL/FAILED
- account_no is VARCHAR(30) - no plain-text storage constraint; encryption enforced at service layer

### 9.3-T02 — Flyway migration V2: merchant_staging, qr_code_staging, franchise_groups_staging, staging_run tables  _(25 min)_
**Context:** WBS 9.3. ZP0051 (full merchant), ZP0055 (franchise full), ZP0053 (full QR) use staging+diff+atomic-promote to prevent partial overwrites of live data. Staging tables mirror their live counterparts plus a staging_run_id BIGINT NOT NULL column. staging_run table: (id BIGSERIAL PK, file_type VARCHAR(10) NOT NULL, business_date DATE NOT NULL, record_count INT, status VARCHAR(20) DEFAULT PENDING, created_at TIMESTAMPTZ NOT NULL DEFAULT now()). Full-sync failure must rollback entirely leaving live tables intact; staging tables are cleared on rollback. All in services/merchant-qr-data.
**Steps:** Create services/merchant-qr-data/src/main/resources/db/migration/V2__merchant_qr_staging.sql; Add merchant_staging with same non-PK columns as merchant plus staging_run_id BIGINT NOT NULL; Add qr_code_staging with same non-PK columns as qr_code plus staging_run_id BIGINT NOT NULL; Add franchise_groups_staging mirroring franchise_groups plus staging_run_id BIGINT NOT NULL; Add staging_run table as specified; Confirm V2 applies after V1 in Testcontainers Flyway sequence
**Deliverable:** services/merchant-qr-data/src/main/resources/db/migration/V2__merchant_qr_staging.sql
**Acceptance / logic checks:**
- V2 applies cleanly after V1 with no errors on PostgreSQL 16 Testcontainers
- merchant_staging has staging_run_id NOT NULL and same non-PK columns as merchant
- staging_run table exists with status DEFAULT PENDING
- Rolling back a transaction that inserted into merchant_staging leaves staging table empty (JUnit 5 @Transactional rollback test)
- merchant table unaffected by merchant_staging inserts that are rolled back
**Depends on:** 9.3-T01

### 9.3-T03 — Flyway migration V3: merchant_sync_log index, V4: outbox_events table  _(25 min)_
**Context:** WBS 9.3. V3 adds a composite index on merchant_sync_log(file_type, sync_date) to support the idempotency guard query. V4 adds the transactional outbox table for domain events (per STACK.md Phase 1 outbox pattern, Kafka behind interface). outbox_events: (id UUID PK DEFAULT gen_random_uuid(), event_type VARCHAR(100) NOT NULL, aggregate_id VARCHAR(100), payload JSONB NOT NULL, status VARCHAR(20) NOT NULL DEFAULT PENDING, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), processed_at TIMESTAMPTZ). Index on outbox_events(status, created_at) for polling. All in services/merchant-qr-data.
**Steps:** Create V3__merchant_sync_log_index.sql: CREATE INDEX CONCURRENTLY idx_merchant_sync_log_file_date ON merchant_sync_log(file_type, sync_date); Create V4__outbox_events.sql: CREATE TABLE outbox_events with id UUID PK, event_type, aggregate_id, payload JSONB, status VARCHAR(20) DEFAULT PENDING, created_at, processed_at; Add index idx_outbox_events_status_created on outbox_events(status, created_at); Confirm V3 then V4 apply in sequence on Testcontainers
**Deliverable:** services/merchant-qr-data/src/main/resources/db/migration/V3__merchant_sync_log_index.sql and V4__outbox_events.sql
**Acceptance / logic checks:**
- V3 creates idx_merchant_sync_log_file_date visible in pg_indexes
- V4 creates outbox_events with payload JSONB type (not TEXT)
- outbox_events.id column type is UUID with DEFAULT gen_random_uuid()
- Both migrations apply in sequence without error on Testcontainers PostgreSQL 16
- outbox_events status column has DEFAULT PENDING
**Depends on:** 9.3-T01

### 9.3-T04 — Flyway migration V5: ShedLock table for distributed sync-job locking  _(25 min)_
**Context:** WBS 9.3. The daily merchant sync Spring @Scheduled job in services/zeropay-adapter must not run concurrently across multiple pod replicas (Kubernetes). ShedLock with PostgreSQL JdbcTemplateLockProvider prevents this. ShedLock requires table: shedlock (name VARCHAR(64) NOT NULL, lock_until TIMESTAMP(3) NOT NULL, locked_at TIMESTAMP(3) NOT NULL, locked_by VARCHAR(255) NOT NULL, CONSTRAINT PK_shedlock PRIMARY KEY (name)). Migration lives in services/zeropay-adapter or services/merchant-qr-data (whichever DB the adapter uses); use merchant-qr-data DB for consolidation.
**Steps:** Create services/merchant-qr-data/src/main/resources/db/migration/V5__shedlock.sql; Add shedlock table DDL exactly as ShedLock library requires; Add ShedLock dependency to services/zeropay-adapter/build.gradle: net.javacrumbs.shedlock:shedlock-spring and shedlock-provider-jdbc-template; Add LockProvider @Bean in ZeroPayAdapterConfig.java using JdbcTemplateLockProvider pointing to DataSource; Confirm V5 applies after V4
**Deliverable:** services/merchant-qr-data/src/main/resources/db/migration/V5__shedlock.sql and services/zeropay-adapter/src/main/java/com/gme/pay/zeropay/config/ZeroPayAdapterConfig.java (LockProvider bean)
**Acceptance / logic checks:**
- V5 applies cleanly after V4 on Testcontainers
- shedlock table has PRIMARY KEY on name column
- LockProvider bean is of type JdbcTemplateLockProvider
- Two concurrent Spring context test instances cannot both acquire lock zeropay-merchant-sync simultaneously (ShedLock integration test)
- ShedLock dependency appears in services/zeropay-adapter/build.gradle
**Depends on:** 9.3-T03

### 9.3-T05 — Define SyncRecord POJOs and ZpFileType enum for ZP0041/43/45/47/51/53/55 in zeropay-adapter  _(30 min)_
**Context:** WBS 9.3. Parsed file records are typed Java records. Place in services/zeropay-adapter/src/main/java/com/gme/pay/zeropay/batch/model/. MerchantSyncRecord fields: merchantId (String 10), merchantName (String 100), businessRegNo (String 10), merchantTypeCode (String 2), statusCode (char: A/S/T), address (String 200), bankCode (String 3), accountNo (String 20), effectiveDate (LocalDate), changeType (char: I/U/D). FranchiseMerchantSyncRecord: same plus franchiseCode (String 6). FranchiseGroupSyncRecord: franchiseCode (String 6), franchiseName (String 100), franchiseType (String 2), headMerchantId (String 10), statusCode (char: A/S/T), changeType (char: I/U/D). QrSyncRecord: qrCodeId (String 20), merchantId (String 10), qrType (char: M/C), statusCode (char: A/D), issueDate (LocalDate), deactivationDate (LocalDate nullable), changeType (char: I/D). All implement marker interface SyncRecord. ZpFileType enum: ZP0041, ZP0043, ZP0045, ZP0047, ZP0051, ZP0053, ZP0055 with boolean isFullSync().
**Steps:** Create SyncRecord.java marker interface in the batch/model package; Create MerchantSyncRecord.java as Java record implementing SyncRecord; Create FranchiseMerchantSyncRecord.java as Java record implementing SyncRecord; Create FranchiseGroupSyncRecord.java as Java record implementing SyncRecord; Create QrSyncRecord.java as Java record implementing SyncRecord with nullable deactivationDate; Create ZpFileType.java enum with isFullSync() returning true for ZP0051/ZP0053/ZP0055
**Deliverable:** services/zeropay-adapter/src/main/java/com/gme/pay/zeropay/batch/model/ (SyncRecord.java, MerchantSyncRecord.java, FranchiseMerchantSyncRecord.java, FranchiseGroupSyncRecord.java, QrSyncRecord.java, ZpFileType.java)
**Acceptance / logic checks:**
- All 4 record types compile in zeropay-adapter Gradle module
- ZpFileType.ZP0051.isFullSync() returns true; ZP0041.isFullSync() returns false
- MerchantSyncRecord is immutable (Java record - no setters)
- QrSyncRecord.deactivationDate is nullable; record with statusCode=A constructs without deactivationDate
- FranchiseMerchantSyncRecord has franchiseCode field not present in MerchantSyncRecord

### 9.3-T06 — Implement ZeroPayDeltaFileParser for ZP0041/ZP0043/ZP0045/ZP0047 in zeropay-adapter  _(45 min)_
**Context:** WBS 9.3. Class ZeroPayDeltaFileParser (@Component) in services/zeropay-adapter/src/main/java/com/gme/pay/zeropay/batch/parser/. Method: ParseResult parse(byte[] rawBytes, ZpFileType fileType). File layout: header line (file_type, business_date YYYYMMDD, institution_code, record_count), N detail lines, trailer line (record_count repeat, control_sum). Header file_type must match expected ZpFileType.name(). Trailer record_count must equal actual detail line count. For each detail line: map fields to typed SyncRecord using a per-fileType LineMapper strategy map. Validation per record: required fields non-blank, changeType in {I,U,D}, statusCode in {A,S,T} for merchant or {A,D} for QR. Returns ParseResult: List<SyncRecord> records, int headerCount, String businessDate, List<String> parseErrors (collects errors; never throws). On header/trailer mismatch ParseResult.isCountMismatch=true.
**Steps:** Create ParseResult.java as a value object in parser package with records, headerCount, businessDate, parseErrors, isCountMismatch fields; Create LineMapper interface with SyncRecord map(String[] fields) and register implementations for each ZpFileType in a Map<ZpFileType, LineMapper>; Implement ZeroPayDeltaFileParser.parse: split lines, parse header, iterate detail lines via LineMapper, parse trailer, validate counts; Collect per-record validation errors into parseErrors without throwing; invalid records are excluded from records list; Add @Component annotation; parser is stateless and thread-safe
**Deliverable:** services/zeropay-adapter/src/main/java/com/gme/pay/zeropay/batch/parser/ZeroPayDeltaFileParser.java (and ParseResult.java, LineMapper.java)
**Acceptance / logic checks:**
- 3-record ZP0041 file with matching header/trailer count returns ParseResult.records.size()==3 and parseErrors empty
- Trailer count mismatch (header says 5, actual detail 4) returns isCountMismatch=true and does not throw
- Line with blank merchantId appends entry to parseErrors; other valid lines still parse
- ZP0043 file with statusCode=D produces QrSyncRecord with deactivationDate populated
- Unknown ZpFileType passed to parse throws IllegalArgumentException before processing
**Depends on:** 9.3-T05

### 9.3-T07 — Implement FullSyncFileParser for ZP0051/ZP0053/ZP0055 in zeropay-adapter  _(35 min)_
**Context:** WBS 9.3. Full sync files (ZP0051 full merchant, ZP0055 franchise full, ZP0053 full QR) use the same line layout as delta files but contain ALL active records. FullSyncFileParser (@Component) in zeropay-adapter wraps ZeroPayDeltaFileParser. Method: FullSyncParseResult parseFullSync(byte[] rawBytes, ZpFileType fileType, Set<String> knownMerchantIds). FullSyncParseResult extends ParseResult adding: boolean isFullSync=true, boolean isRecordCountMismatch (header count != actual detail count). For ZP0053 only: validate each QrSyncRecord.merchantId is in knownMerchantIds; collect orphans in orphanQrIds list; orphan records are excluded from records list and counted in parseErrors. Full-sync rule: if isRecordCountMismatch=true, caller must NOT apply the file.
**Steps:** Create FullSyncParseResult.java extending ParseResult with isFullSync, isRecordCountMismatch, orphanQrIds fields; Create FullSyncFileParser.java injecting ZeroPayDeltaFileParser; Implement parseFullSync: delegate to ZeroPayDeltaFileParser, wrap result, set isRecordCountMismatch if headerCount != records.size()+parseErrors.size(); For ZP0053: iterate records, check merchantId in knownMerchantIds, add orphans to orphanQrIds and parseErrors; FullSyncParseResult.isFullSync always true
**Deliverable:** services/zeropay-adapter/src/main/java/com/gme/pay/zeropay/batch/parser/FullSyncFileParser.java and FullSyncParseResult.java
**Acceptance / logic checks:**
- ZP0051 file with 1000 detail lines and header count 1000 returns isRecordCountMismatch=false
- ZP0051 file with header count 999 but 1000 detail lines returns isRecordCountMismatch=true
- ZP0053 with QrSyncRecord referencing merchantId not in knownMerchantIds populates orphanQrIds list
- FullSyncParseResult.isFullSync is always true
- Orphan QR records excluded from records list and counted in parseErrors
**Depends on:** 9.3-T06

### 9.3-T08 — Define MerchantDocument and QrCodeDocument MongoDB models in merchant-qr-data  _(30 min)_
**Context:** WBS 9.3. MongoDB is the CQRS read-model for payment-time merchant/QR lookup (low-latency reads). Module services/merchant-qr-data uses Spring Data MongoDB. MerchantDocument: @Document(collection=merchants), _id=merchantId (String, ZeroPay CHAR(10)), fields: merchantType (String), name (String), businessRegistrationNo (String), franchiseCode (String nullable), categoryCode (String), feeType (String: DOMESTIC/CROSSBORDER), status (String), isActive (boolean), isQrActive (boolean), syncedAt (Instant), schemeId (String DEFAULT ZEROPAY). QrCodeDocument: @Document(collection=qr_codes), _id=qrCodeId (String, ZeroPay CHAR(20)), fields: merchantId (String), qrType (String: M/C), status (String), isActive (boolean), issueDate (LocalDate), deactivationDate (LocalDate nullable), syncedAt (Instant), schemeId (String DEFAULT ZEROPAY). Spring Data repositories: MerchantMongoRepository and QrCodeMongoRepository.
**Steps:** Create services/merchant-qr-data/src/main/java/com/gme/pay/merchantqr/domain/MerchantDocument.java as @Document(collection=merchants) with all fields and @Indexed annotations; Create QrCodeDocument.java as @Document(collection=qr_codes) with all fields; Add @Indexed(unique=true) on merchantId in MerchantDocument (redundant with _id but explicit); Add @Indexed on MerchantDocument.status, MerchantDocument.franchiseCode, MerchantDocument.isActive; Add @Indexed on QrCodeDocument.merchantId, QrCodeDocument.isActive; Create MerchantMongoRepository and QrCodeMongoRepository extending MongoRepository
**Deliverable:** services/merchant-qr-data/src/main/java/com/gme/pay/merchantqr/domain/MerchantDocument.java, QrCodeDocument.java, MerchantMongoRepository.java, QrCodeMongoRepository.java
**Acceptance / logic checks:**
- Both documents compile and are discovered by Spring Data MongoDB repository scan
- Indexes created on MongoDB startup (verify via Testcontainers + MongoClient.listIndexes on collection)
- MerchantDocument.status is String not enum so unknown values from ZeroPay files do not cause deserialization errors
- QrCodeDocument._id is qrCodeId String field (ZeroPay CHAR(20))
- MerchantMongoRepository.findByMerchantIdAndIsActiveTrue(String merchantId) query method resolves without error

### 9.3-T09 — Define domain events MerchantSyncedEvent, QrSyncedEvent, SyncAbortedEvent, SyncReconciliationMismatchEvent in lib-events  _(25 min)_
**Context:** WBS 9.3. Per STACK.md all domain events live in libs/lib-events. Each event is a Java record implementing DomainEvent marker interface with eventId (String UUID), occurredAt (Instant), plus event-specific fields. MerchantSyncedEvent: merchantId (String), changeType (String), isActive (boolean), schemeId (String). QrSyncedEvent: qrCodeId (String), merchantId (String), changeType (String), isActive (boolean). SyncAbortedEvent: fileType (String), businessDate (LocalDate), reason (String). SyncReconciliationMismatchEvent: fileType (String), businessDate (LocalDate), expectedCount (int), actualCount (int). DomainEvent is a zero-method marker interface. These events are also written to the outbox_events table (V4 migration) for future Kafka wiring.
**Steps:** Create libs/lib-events/src/main/java/com/gme/pay/events/DomainEvent.java as marker interface; Create libs/lib-events/src/main/java/com/gme/pay/events/merchant/MerchantSyncedEvent.java as Java record; Create QrSyncedEvent.java, SyncAbortedEvent.java, SyncReconciliationMismatchEvent.java in same package; All four implement DomainEvent; Confirm lib-events module compiles with ./gradlew :lib-events:compileJava
**Deliverable:** libs/lib-events/src/main/java/com/gme/pay/events/merchant/MerchantSyncedEvent.java (and siblings), libs/lib-events/src/main/java/com/gme/pay/events/DomainEvent.java
**Acceptance / logic checks:**
- All four event records compile in lib-events module
- DomainEvent interface is zero-method (marker only)
- MerchantSyncedEvent.merchantId is String (not Long) to match ZeroPay CHAR(10)
- SyncAbortedEvent.businessDate is LocalDate
- lib-events module has no dependency on services (no circular dependency)

### 9.3-T10 — Implement MerchantSyncService: delta upsert for ZP0041/ZP0045/ZP0047 into PostgreSQL  _(45 min)_
**Context:** WBS 9.3. MerchantSyncService (@Service in services/merchant-qr-data) applies parsed delta records to PostgreSQL merchant and franchise_groups tables. Per-record logic: changeType=I -> INSERT; changeType=U -> UPDATE; changeType=D -> set status=DEACTIVATED, is_active=false. Use INSERT ... ON CONFLICT (merchant_id) DO UPDATE (upsert). For ZP0045 records: set is_franchise=true, franchise_code. For ZP0047 records: upsert franchise_groups table. account_no must be AES-256 encrypted via SecretService bean before INSERT/UPDATE. After all records applied, write one merchant_sync_log row. All within a single Spring @Transactional method. Publish MerchantSyncedEvent via ApplicationEventPublisher for each upserted record (using @TransactionalEventListener in downstream projection writer). Inject IdempotencyGuard; return empty SyncResult if already processed.
**Steps:** Create services/merchant-qr-data/src/main/java/com/gme/pay/merchantqr/service/MerchantSyncService.java as @Service; Inject JdbcTemplate (for bulk upsert), SecretService (account_no encrypt), ApplicationEventPublisher, IdempotencyGuard; Implement applyMerchantDelta(List<MerchantSyncRecord> records, String fileType, LocalDate syncDate) -> SyncResult within @Transactional; Upsert via INSERT INTO merchant (...) VALUES (...) ON CONFLICT (merchant_id) DO UPDATE SET ... for each record; Encrypt account_no before insert; set synced_at=now() on every upsert; Write merchant_sync_log row after loop; publish MerchantSyncedEvent per record via ApplicationEventPublisher
**Deliverable:** services/merchant-qr-data/src/main/java/com/gme/pay/merchantqr/service/MerchantSyncService.java
**Acceptance / logic checks:**
- changeType=D record sets is_active=false and status=DEACTIVATED in merchant table
- Duplicate merchant_id with changeType=I performs UPDATE not duplicate INSERT (upsert semantics)
- account_no stored encrypted (raw DB value differs from plaintext input - verify via JdbcTemplate raw query)
- @Transactional rollback on exception: no merchant rows and no merchant_sync_log row committed
- SyncResult.deactivated count equals number of D records in input
**Depends on:** 9.3-T01, 9.3-T05, 9.3-T09

### 9.3-T11 — Implement QrSyncService: delta upsert for ZP0043 into PostgreSQL  _(40 min)_
**Context:** WBS 9.3. QrSyncService (@Service in services/merchant-qr-data) applies ZP0043 QrSyncRecord list to the qr_code PostgreSQL table. changeType=I: INSERT; changeType=D: set status=DEACTIVATED, is_active=false, deactivation_date=record.deactivationDate. Deactivated QR codes must block payment at next resolution call. Validate: referenced merchant_id must exist as ACTIVE in merchant table; if not, log error and skip that record (do not fail whole batch). Write merchant_sync_log row (file_type=ZP0043) after processing. All within @Transactional. Publish QrSyncedEvent per record. Inject IdempotencyGuard; return empty SyncResult if already processed.
**Steps:** Create services/merchant-qr-data/src/main/java/com/gme/pay/merchantqr/service/QrSyncService.java as @Service; Inject QrCodeRepository (Spring Data JPA), MerchantRepository, ApplicationEventPublisher, IdempotencyGuard; Implement applyQrDelta(List<QrSyncRecord> records, LocalDate syncDate) -> SyncResult within @Transactional; For each record: verify merchantId exists and is ACTIVE; if not add to error count, skip; changeType=I: upsert qr_code row; changeType=D: set is_active=false, status=DEACTIVATED, deactivation_date; Publish QrSyncedEvent per record; write merchant_sync_log(file_type=ZP0043) after loop
**Deliverable:** services/merchant-qr-data/src/main/java/com/gme/pay/merchantqr/service/QrSyncService.java
**Acceptance / logic checks:**
- QrSyncRecord with changeType=D sets is_active=false and deactivation_date on qr_code row
- QrSyncRecord referencing non-existent merchantId skipped and counted in errorCount
- Duplicate qr_code_id I record performs UPDATE not duplicate INSERT
- merchant_sync_log status=PARTIAL when errorCount>0, SUCCESS when errorCount=0
- @Transactional boundary: exception mid-batch rolls back all QR writes for that invocation
**Depends on:** 9.3-T01, 9.3-T05, 9.3-T09

### 9.3-T12 — Implement IdempotencyGuard using merchant_sync_log for sync-job deduplication  _(30 min)_
**Context:** WBS 9.3. Sync jobs must be idempotent: second run for same file_type + sync_date with status=SUCCESS is a no-op. IdempotencyGuard (@Component in merchant-qr-data) queries merchant_sync_log via MerchantSyncLogRepository. Before any sync, check for SUCCESS row; if found return already-processed signal. alreadyProcessed returns false when prior row has status=PARTIAL or FAILED (allows retry). Requires index on merchant_sync_log(file_type, sync_date) added in V3 migration (9.3-T03).
**Steps:** Create services/merchant-qr-data/src/main/java/com/gme/pay/merchantqr/guard/IdempotencyGuard.java as @Component; Create MerchantSyncLogRepository as Spring Data JPA repository with findByFileTypeAndSyncDateAndStatus query method; Implement alreadyProcessed(String fileType, LocalDate date) -> boolean: query for SUCCESS row, return true if found; Inject IdempotencyGuard into MerchantSyncService and QrSyncService; return empty SyncResult(alreadyProcessed=true) if guard returns true; Log INFO message ALREADY_PROCESSED with fileType and syncDate when skipped
**Deliverable:** services/merchant-qr-data/src/main/java/com/gme/pay/merchantqr/guard/IdempotencyGuard.java and MerchantSyncLogRepository.java
**Acceptance / logic checks:**
- Second call to applyMerchantDelta with same fileType+syncDate after SUCCESS returns empty SyncResult without any DB upsert (verify via Mockito verify(merchantRepo, never()))
- alreadyProcessed returns false when prior row has status=PARTIAL (allows retry)
- alreadyProcessed returns true only for status=SUCCESS rows
- No DB write to merchant table occurs when alreadyProcessed=true
- IdempotencyGuard query uses merchant_sync_log(file_type, sync_date) index (EXPLAIN shows index scan)
**Depends on:** 9.3-T03, 9.3-T10

### 9.3-T13 — Implement MerchantFullSyncService: staging+diff+atomic-promote for ZP0051/ZP0055  _(50 min)_
**Context:** WBS 9.3. ZP0051 (full merchant) and ZP0055 (franchise full) use staging+diff+atomic-promote to prevent partial overwrites. Steps: (1) validate FullSyncParseResult.isRecordCountMismatch is false, else throw SyncAbortedException; (2) open @Transactional; (3) bulk-insert all records into merchant_staging with staging_run_id; (4) diff staging vs live: merchants in live absent from staging get status=DEACTIVATED, is_active=false; new merchants inserted; existing updated; (5) commit; (6) write merchant_sync_log. Rollback on any exception; live table untouched. Inject IdempotencyGuard.
**Steps:** Create services/merchant-qr-data/src/main/java/com/gme/pay/merchantqr/service/MerchantFullSyncService.java as @Service; Inject JdbcTemplate, SecretService, ApplicationEventPublisher, IdempotencyGuard; Implement applyMerchantFullSync(FullSyncParseResult result, LocalDate syncDate) -> SyncResult within @Transactional; Check result.isRecordCountMismatch; if true throw SyncAbortedException(fileType, syncDate, RECORD_COUNT_MISMATCH); Bulk INSERT into merchant_staging; diff to live; apply changes; commit; Write merchant_sync_log(status=SUCCESS) on commit; publish SyncAbortedEvent on abort; Implement applyFranchiseFullSync targeting franchise_groups and is_franchise=true merchant rows
**Deliverable:** services/merchant-qr-data/src/main/java/com/gme/pay/merchantqr/service/MerchantFullSyncService.java
**Acceptance / logic checks:**
- SyncAbortedException thrown when isRecordCountMismatch=true; live merchant table unchanged verified by SELECT COUNT
- Merchants in live table absent from full-sync file are set DEACTIVATED in same transaction
- SyncResult.inserted+updated+deactivated count equals total live merchant count after promote
- Rollback on DataAccessException leaves staging empty and live table at prior state (Testcontainers JUnit 5 test)
- applyFranchiseFullSync targets only is_franchise=true merchants
**Depends on:** 9.3-T02, 9.3-T10, 9.3-T07

### 9.3-T14 — Implement QrFullSyncService: staging+diff+atomic-promote for ZP0053  _(45 min)_
**Context:** WBS 9.3. ZP0053 (full QR list) follows the same staging+diff+atomic-promote pattern as MerchantFullSyncService. Additional: referential integrity check before staging load - every QrSyncRecord.merchantId must be in activemerchantIds (Set<String> passed by caller from live merchant table); orphan records from FullSyncParseResult.orphanQrIds are excluded before staging load. QR codes in live qr_code table absent from full file are set DEACTIVATED. Full QR sync must not interleave with merchant full sync; caller sequences them. Inject IdempotencyGuard.
**Steps:** Create services/merchant-qr-data/src/main/java/com/gme/pay/merchantqr/service/QrFullSyncService.java as @Service; Inject JdbcTemplate, ApplicationEventPublisher, IdempotencyGuard; Implement applyQrFullSync(FullSyncParseResult result, LocalDate syncDate, Set<String> activeMerchantIds) -> SyncResult within @Transactional; Throw SyncAbortedException if result.isRecordCountMismatch=true; Exclude orphanQrIds before bulk insert into qr_code_staging; diff to live; set absent QRs DEACTIVATED; commit; Write merchant_sync_log(file_type=ZP0053); publish SyncAbortedEvent on abort
**Deliverable:** services/merchant-qr-data/src/main/java/com/gme/pay/merchantqr/service/QrFullSyncService.java
**Acceptance / logic checks:**
- QR codes absent from full file set DEACTIVATED in live qr_code table after promote
- Orphan records (merchantId not in activeMerchantIds) skipped and counted in merchant_sync_log.error_count
- SyncAbortedException on record count mismatch; live qr_code table unchanged
- Transaction rollback on DB exception: staging empty and live at prior state
- SyncResult.deactivated count equals soft-deleted qr_code rows
**Depends on:** 9.3-T02, 9.3-T11, 9.3-T07

### 9.3-T15 — Implement MerchantProjectionWriter: sync PostgreSQL changes to MongoDB read-model  _(40 min)_
**Context:** WBS 9.3. After each successful PostgreSQL upsert, the MongoDB read-model must be updated. MerchantProjectionWriter (@Service in merchant-qr-data) uses @TransactionalEventListener(phase=AFTER_COMMIT) so MongoDB write occurs only after PostgreSQL transaction commits. Handles MerchantSyncedEvent -> upsert MerchantDocument; QrSyncedEvent -> upsert QrCodeDocument. Uses MongoTemplate.upsert with criteria by _id (merchantId or qrCodeId). MongoDB write failure: log WARN, publish MongoSyncFailureEvent, do NOT rollback PostgreSQL. MongoDB is read-model only; PostgreSQL is authoritative.
**Steps:** Create services/merchant-qr-data/src/main/java/com/gme/pay/merchantqr/projection/MerchantProjectionWriter.java as @Service; Inject MongoTemplate, ApplicationEventPublisher; Implement onMerchantSynced(@TransactionalEventListener(phase=AFTER_COMMIT) MerchantSyncedEvent): upsert MerchantDocument via MongoTemplate.upsert; Implement onQrSynced(@TransactionalEventListener(phase=AFTER_COMMIT) QrSyncedEvent): upsert QrCodeDocument; Wrap each mongo op in try/catch MongoException: log warn, publish MongoSyncFailureEvent(eventId, errorMsg), no rethrow; Set MerchantDocument/QrCodeDocument.syncedAt=Instant.now() on every upsert
**Deliverable:** services/merchant-qr-data/src/main/java/com/gme/pay/merchantqr/projection/MerchantProjectionWriter.java
**Acceptance / logic checks:**
- After MerchantSyncService.applyMerchantDelta call, MerchantDocument appears in MongoDB with matching merchantId
- MerchantDocument.isActive=false after D-type sync event
- MongoDB write failure does NOT cause PostgreSQL rollback (verify: PostgreSQL row exists, MongoDB row absent, no exception propagated)
- @TransactionalEventListener(phase=AFTER_COMMIT) annotation present - event not fired on rollback
- QrCodeDocument.isActive=false after QrSyncedEvent with isActive=false
**Depends on:** 9.3-T08, 9.3-T10, 9.3-T11, 9.3-T09

### 9.3-T16 — Implement SyncFilePresenceChecker: Redis gate to suppress delta on full-sync night  _(35 min)_
**Context:** WBS 9.3. Spec rule: full files supersede delta files on the nights they arrive; delta upsert for same entity type must be suppressed. SyncFilePresenceChecker (@Service in services/zeropay-adapter) uses Redis (StringRedisTemplate) to signal full-sync completion. Key pattern: merchant-sync:full:{ZpFileType}:{YYYYMMDD} TTL 25 hours. markFullSyncComplete(ZpFileType, LocalDate) sets the key. isFullSyncComplete(ZpFileType, LocalDate) checks key existence. Redis unavailability is fail-open: if Redis throws, log REDIS_CHECK_FAILED and proceed with delta (do not suppress). MerchantSyncOrchestrator calls markFullSyncComplete after successful full-sync; MerchantSyncSftpPoller checks before dispatching delta files.
**Steps:** Create services/zeropay-adapter/src/main/java/com/gme/pay/zeropay/sync/SyncFilePresenceChecker.java as @Service; Inject StringRedisTemplate; Implement markFullSyncComplete(ZpFileType fileType, LocalDate date): set key merchant-sync:full:{fileType}:{date} with TTL Duration.ofHours(25); Implement isFullSyncComplete(ZpFileType fileType, LocalDate date) -> boolean: return hasKey; catch RedisConnectionException and return false (fail-open); Log REDIS_CHECK_FAILED at WARN when Redis unavailable
**Deliverable:** services/zeropay-adapter/src/main/java/com/gme/pay/zeropay/sync/SyncFilePresenceChecker.java
**Acceptance / logic checks:**
- After markFullSyncComplete(ZP0051, today), Redis key merchant-sync:full:ZP0051:{today} exists with TTL between 24h and 25h
- isFullSyncComplete returns false when key absent
- isFullSyncComplete returns false when Redis throws RedisConnectionException (fail-open - not suppress)
- Key TTL 25h ensures expiry before next nightly run (24h cadence + 1h buffer)
- isFullSyncComplete returns true when key present: ZP0041 delta dispatched only when ZP0051 key absent
**Depends on:** 9.3-T05

### 9.3-T17 — Implement SyncReconciler: post-delta count check and Ops alert on mismatch  _(35 min)_
**Context:** WBS 9.3. After each delta sync, compare GMEPay+ DB record count against the file header count. SyncReconciler (@Service in merchant-qr-data) queries PostgreSQL: for ZP0041 count merchant rows synced_at=syncDate; compare with ParseResult.headerCount. If abs(dbCount - headerCount) > 0: publish SyncReconciliationMismatchEvent, call NotificationService.alertOps(SYNC_COUNT_MISMATCH, fileType, expected, actual), set merchant_sync_log.status=PARTIAL. Mismatch does NOT rollback applied records; it flags for Ops review. Called from MerchantSyncOrchestrator after each successful delta apply.
**Steps:** Create services/merchant-qr-data/src/main/java/com/gme/pay/merchantqr/service/SyncReconciler.java as @Service; Inject JdbcTemplate (for count query), NotificationService, ApplicationEventPublisher; Implement reconcile(String fileType, LocalDate syncDate, int headerCount) -> ReconcileResult; Query: SELECT COUNT(*) FROM merchant WHERE DATE(synced_at)=syncDate for ZP0041/ZP0045; from qr_code for ZP0043; If count != headerCount: publish SyncReconciliationMismatchEvent, call alertOps, return ReconcileResult(MISMATCH, expected, actual); Update merchant_sync_log.status=PARTIAL on MISMATCH via MerchantSyncLogRepository
**Deliverable:** services/merchant-qr-data/src/main/java/com/gme/pay/merchantqr/service/SyncReconciler.java
**Acceptance / logic checks:**
- headerCount=100, actual DB synced count=98: SyncReconciliationMismatchEvent published and alertOps called
- headerCount=100, actual count=100: ReconcileResult(OK), no alert
- merchant_sync_log.status set to PARTIAL on mismatch (not SUCCESS)
- Reconciler does NOT rollback applied records on mismatch
- ReconcileResult is immutable value object with status (OK/MISMATCH), expected (int), actual (int) fields
**Depends on:** 9.3-T10, 9.3-T11, 9.3-T09

### 9.3-T18 — Implement MerchantSyncOrchestrator: route parsed bytes to correct sync service  _(40 min)_
**Context:** WBS 9.3. MerchantSyncOrchestrator (@Service in services/merchant-qr-data) receives raw decrypted bytes and ZpFileType then routes: ZP0041 -> ZeroPayDeltaFileParser + MerchantSyncService.applyMerchantDelta; ZP0045 -> same (franchise merchant delta); ZP0047 -> parse to FranchiseGroupSyncRecord + MerchantSyncService.applyFranchiseGroupDelta; ZP0043 -> QrSyncService.applyQrDelta; ZP0051 -> FullSyncFileParser + MerchantFullSyncService.applyMerchantFullSync; ZP0055 -> FullSyncFileParser + MerchantFullSyncService.applyFranchiseFullSync; ZP0053 -> FullSyncFileParser + QrFullSyncService.applyQrFullSync (passing activeMerchantIds queried from live table). After each delta: call SyncReconciler.reconcile. After each full-sync: call SyncFilePresenceChecker.markFullSyncComplete. On SyncAbortedException: publish SyncAbortedEvent, call NotificationService.alertOps, do not propagate exception to caller.
**Steps:** Create services/merchant-qr-data/src/main/java/com/gme/pay/merchantqr/orchestrator/MerchantSyncOrchestrator.java as @Service; Inject ZeroPayDeltaFileParser, FullSyncFileParser, MerchantSyncService, QrSyncService, MerchantFullSyncService, QrFullSyncService, SyncReconciler, SyncFilePresenceChecker, ApplicationEventPublisher, NotificationService, MerchantRepository; Implement sync(byte[] rawBytes, ZpFileType fileType, LocalDate businessDate) -> SyncResult; Routing switch/map; for full-sync: load activeMerchantIds from merchant table before calling FullSyncFileParser; Catch SyncAbortedException: publish event, call alertOps, return SyncResult(aborted=true); Catch RuntimeException: wrap as SyncProcessingException with fileType and businessDate; rethrow
**Deliverable:** services/merchant-qr-data/src/main/java/com/gme/pay/merchantqr/orchestrator/MerchantSyncOrchestrator.java
**Acceptance / logic checks:**
- ZP0041 bytes routed to MerchantSyncService.applyMerchantDelta (Mockito verify)
- ZP0053 bytes routed to FullSyncFileParser then QrFullSyncService.applyQrFullSync (Mockito verify)
- SyncAbortedException results in SyncAbortedEvent published (no exception propagated to caller)
- Unknown ZpFileType throws IllegalArgumentException before any service call
- SyncReconciler.reconcile called after ZP0041 delta apply (Mockito verify)
**Depends on:** 9.3-T06, 9.3-T07, 9.3-T10, 9.3-T11, 9.3-T13, 9.3-T14, 9.3-T16, 9.3-T17

### 9.3-T19 — Implement MerchantSyncSftpPoller: scheduled SFTP download of ZP004x/ZP005x files  _(50 min)_
**Context:** WBS 9.3. MerchantSyncSftpPoller (@Component in services/zeropay-adapter) polls the KFTC SFTP inbound directory for merchant/QR sync files on a configurable cron schedule (default: 0 30 6 * * * zone=Asia/Seoul). Flow per file type: (1) list inbound dir, (2) match filename ZP{fileCode}_{YYYYMMDD}_01.dat.pgp for today KST, (3) download to temp dir, (4) verify file size/checksum, (5) PGP-decrypt using GME private key from SchemeConfig.pgpPrivateKeyRef, (6) hand raw bytes to MerchantSyncOrchestrator.sync, (7) archive to {archiveDir}/{fileType}/{YYYYMMDD}/. Retry: up to 3 attempts with exponential backoff 30s/120s/600s via Spring Retry @Retryable or manual loop. Final failure: call NotificationService.alertOps(SFTP_FETCH_FAILED, fileType, date). Uses Apache SSHD or JSch SFTP client (SftpGateway bean). Annotated @ScheduledLock(name=zeropay-merchant-sync, lockAtMostFor=PT30M).
**Steps:** Create services/zeropay-adapter/src/main/java/com/gme/pay/zeropay/sftp/MerchantSyncSftpPoller.java as @Component; Inject SftpGateway, SchemeConfigService, MerchantSyncOrchestrator, SyncFilePresenceChecker, NotificationService, ZeroPayMerchantSyncProperties; Annotate poll() with @Scheduled(cron=${zeropay.merchant-sync.cron:0 30 6 * * *}, zone=Asia/Seoul) and @ScheduledLock(name=zeropay-merchant-sync, lockAtMostFor=PT30M); For each ZpFileType [ZP0041,ZP0043,ZP0045,ZP0047,ZP0051,ZP0053,ZP0055]: check isFullSyncComplete gate for delta files; download, verify, decrypt, call orchestrator.sync; Implement retry loop: 3 attempts with sleep 30s/120s/600s between; on 3rd failure call alertOps; Archive decrypted file after successful orchestrator call
**Deliverable:** services/zeropay-adapter/src/main/java/com/gme/pay/zeropay/sftp/MerchantSyncSftpPoller.java
**Acceptance / logic checks:**
- Successful download + decrypt hands correct byte[] to orchestrator.sync with ZpFileType.ZP0041 and today KST date (Mockito verify)
- Checksum mismatch triggers retry; after 3rd failure calls NotificationService.alertOps exactly once
- @ScheduledLock annotation present with lockAtMostFor=PT30M
- Archived file exists at {archiveDir}/ZP0041/{YYYYMMDD}/ZP0041_{YYYYMMDD}_01.dat after successful run
- ZP0041 dispatch skipped when SyncFilePresenceChecker.isFullSyncComplete(ZP0051, today) returns true
**Depends on:** 9.3-T04, 9.3-T16, 9.3-T18

### 9.3-T20 — Implement MerchantLookupService: payment-time merchant and QR resolution from MongoDB  _(40 min)_
**Context:** WBS 9.3. At payment time, the Smart Router calls MerchantLookupService (in services/merchant-qr-data) to validate merchantId and qrCodeId from the parsed QR payload. Lookup uses MongoDB only (not PostgreSQL) for low latency. Checks: (1) merchant.isActive=true; (2) qrCode.isActive=true; (3) qrCode.merchantId == passed merchantId. Return MerchantResolutionResult: merchantId, name, merchantType, feeType, qrType, or typed error codes: MERCHANT_NOT_FOUND, MERCHANT_SUSPENDED, MERCHANT_DEACTIVATED, QR_NOT_FOUND, QR_DEACTIVATED, QR_MERCHANT_MISMATCH. Expose via @RestController MerchantLookupController at GET /internal/v1/merchants/{merchantId}/qrcodes/{qrCodeId}. Secured OAuth2/JWT scope ops:read.
**Steps:** Create MerchantLookupService.java as @Service injecting MerchantMongoRepository and QrCodeMongoRepository; Implement resolve(String merchantId, String qrCodeId) -> MerchantResolutionResult with typed error codes as described; Create MerchantLookupController.java as @RestController at /internal/v1/merchants/{merchantId}/qrcodes/{qrCodeId} mapped to GET; Return 200 MerchantResolutionResult or 422 with lib-errors canonical error body; Annotate controller with @PreAuthorize(hasAuthority(SCOPE_ops:read)); MerchantResolutionResult is a Java record with nullable error field
**Deliverable:** services/merchant-qr-data/src/main/java/com/gme/pay/merchantqr/service/MerchantLookupService.java and MerchantLookupController.java
**Acceptance / logic checks:**
- Deactivated merchant returns error code MERCHANT_DEACTIVATED not MERCHANT_NOT_FOUND
- Deactivated QR returns QR_DEACTIVATED even when merchant is ACTIVE
- QR owned by different merchantId returns QR_MERCHANT_MISMATCH not QR_NOT_FOUND
- Active merchant + active QR with correct merchantId: 200 with feeType and merchantType populated
- MongoDB query uses indexed fields (verify via Testcontainers explain plan - no COLLSCAN)
**Depends on:** 9.3-T08, 9.3-T15

### 9.3-T21 — Add Redis caching to MerchantLookupService with 300s TTL and deactivation eviction  _(35 min)_
**Context:** WBS 9.3. MerchantLookupService is on the hot payment path (sub-10ms p99 required). Cache MerchantResolutionResult in Redis key merchant-resolve:{merchantId}:{qrCodeId} with TTL 300s. Cache NOT_FOUND results with TTL 60s to avoid stale negatives. On cache hit return immediately. When sync deactivates merchant or QR (MerchantSyncedEvent/QrSyncedEvent isActive=false), immediately evict Redis keys via scan+delete pattern merchant-resolve:{merchantId}:*. Uses Spring Cache @Cacheable with RedisCacheManager (two cache configs: merchant-resolve TTL 300s, merchant-resolve-not-found TTL 60s).
**Steps:** Create CacheConfig.java @Configuration in merchant-qr-data: configure RedisCacheManager with two named caches merchant-resolve (TTL 300s) and merchant-resolve-not-found (TTL 60s); Annotate MerchantLookupService.resolve with @Cacheable based on result: error NOT_FOUND -> merchant-resolve-not-found cache; success -> merchant-resolve cache; Create CacheEvictionService.java: inject RedisTemplate, implement evictMerchant(String merchantId) using scan for keys matching merchant-resolve:{merchantId}:* and delete; Add @TransactionalEventListener(phase=AFTER_COMMIT) in MerchantProjectionWriter calling CacheEvictionService.evictMerchant on MerchantSyncedEvent/QrSyncedEvent with isActive=false
**Deliverable:** services/merchant-qr-data/src/main/java/com/gme/pay/merchantqr/cache/CacheConfig.java and CacheEvictionService.java (and updated MerchantLookupService.java)
**Acceptance / logic checks:**
- Second call with same merchantId+qrCodeId returns without MongoDB query (Mockito verify mongoRepo called once)
- Redis key expires after 300s (verify TTL via Testcontainers Redis)
- NOT_FOUND result cached with TTL between 55s-65s (not 300s)
- Deactivation event causes Redis key eviction: next resolve call hits MongoDB and returns DEACTIVATED
- MerchantResolutionResult round-trips through Jackson/JSON in Redis without data loss
**Depends on:** 9.3-T20, 9.3-T15

### 9.3-T22 — Add Ops Admin API: GET /internal/v1/sync-logs for merchant sync history  _(35 min)_
**Context:** WBS 9.3. Ops visibility into sync job history. Spring @RestController in services/merchant-qr-data at GET /internal/v1/sync-logs with query params: fileType (optional String), from (LocalDate required), to (LocalDate required), status (optional String), plus Spring Pageable (page, size). Response: Page<MerchantSyncLogDto> with content array and pagination fields. Validation: date range to-from must be <= 90 days; return 400 with lib-errors body if exceeded. Secured with OAuth2/JWT scope ops:read.
**Steps:** Create MerchantSyncLogController.java as @RestController at /internal/v1/sync-logs; Implement GET handler with @RequestParam and Pageable; validate date range <= 90 days, return 400 if not; Create MerchantSyncLogRepository custom query: findByOptionalFilters(fileType, from, to, status, Pageable); Create MerchantSyncLogDto Java record with all merchant_sync_log columns; Annotate with @PreAuthorize(hasAuthority(SCOPE_ops:read)); Return ResponseEntity<Page<MerchantSyncLogDto>>
**Deliverable:** services/merchant-qr-data/src/main/java/com/gme/pay/merchantqr/api/MerchantSyncLogController.java and MerchantSyncLogDto.java
**Acceptance / logic checks:**
- GET with valid date range returns 200 with correct merchant_sync_log rows in content array
- Date range > 90 days returns 400 with error code INVALID_DATE_RANGE in lib-errors format
- Request without valid JWT returns 401; without ops:read scope returns 403
- Response pagination fields page, size, totalElements are present
- fileType filter correctly narrows results (only ZP0041 rows returned when fileType=ZP0041)
**Depends on:** 9.3-T10, 9.3-T12

### 9.3-T23 — Add Ops Admin API: POST /internal/v1/merchants/{merchantId}/deactivate (manual override)  _(40 min)_
**Context:** WBS 9.3. Per SCH-06 Assumption A-07: Ops must force-deactivate a merchant outside the batch cycle. POST /internal/v1/merchants/{merchantId}/deactivate. Request body: {reason: String required max 500 chars, actorId: String required}. Actions: (1) @Transactional: set merchant.status=DEACTIVATED, is_active=false, updated_at=now(); set all linked qr_code.is_active=false; write audit_log entry (actor, timestamp, merchantId, prevStatus, newStatus=DEACTIVATED, reason); (2) after commit: evict Redis cache; (3) publish MerchantSyncedEvent(changeType=D, isActive=false). Returns 200 {merchantId, status, deactivatedAt}; 404 if not found. Secured ops:write.
**Steps:** Create MerchantAdminController.java as @RestController at /internal/v1/merchants; Implement POST /{merchantId}/deactivate with @Valid request body validation (@NotBlank reason, @NotBlank actorId); Create MerchantAdminService.java: @Service with deactivate(String merchantId, String reason, String actorId) @Transactional; Update merchant row, deactivate linked qr_code rows, write audit_log row within @Transactional; After commit: call CacheEvictionService.evictMerchant; publish MerchantSyncedEvent via ApplicationEventPublisher; Return 200 response or 404 via lib-errors if merchant not found
**Deliverable:** services/merchant-qr-data/src/main/java/com/gme/pay/merchantqr/api/MerchantAdminController.java and MerchantAdminService.java
**Acceptance / logic checks:**
- POST /deactivate for ACTIVE merchant: merchant.is_active=false and all linked qr_code.is_active=false in PostgreSQL
- audit_log row written with correct actorId, merchantId, prevStatus, newStatus=DEACTIVATED, reason
- Redis cache for merchant-resolve:{merchantId}:* evicted after deactivation
- 404 response for non-existent merchantId
- Request without ops:write scope returns 403
**Depends on:** 9.3-T21, 9.3-T20

### 9.3-T24 — Add Ops Admin API: POST /internal/v1/qrcodes/{qrCodeId}/deactivate (manual QR override)  _(40 min)_
**Context:** WBS 9.3. Per SCH-06 Assumption A-07: Ops must force-deactivate a single QR code outside the batch cycle. POST /internal/v1/qrcodes/{qrCodeId}/deactivate. Body: {reason: String required, actorId: String required}. Actions: @Transactional: set qr_code.status=DEACTIVATED, is_active=false, deactivation_date=today; write audit_log; after commit: upsert QrCodeDocument in MongoDB (isActive=false); evict Redis cache key. Idempotent: deactivating already-DEACTIVATED QR returns 200 with no state change. Secured ops:write.
**Steps:** Create QrAdminController.java as @RestController at /internal/v1/qrcodes; Implement POST /{qrCodeId}/deactivate with @Valid request body; Create QrAdminService.java @Service: deactivate(String qrCodeId, String reason, String actorId) @Transactional; Set qr_code row DEACTIVATED; write audit_log; if already DEACTIVATED return early (idempotent); After commit: upsert QrCodeDocument in MongoDB (isActive=false); evict Redis cache; Return 200 {qrCodeId, status, deactivatedAt}; 404 if not found
**Deliverable:** services/merchant-qr-data/src/main/java/com/gme/pay/merchantqr/api/QrAdminController.java and QrAdminService.java
**Acceptance / logic checks:**
- POST /deactivate on ACTIVE QR: qr_code.is_active=false in PostgreSQL and QrCodeDocument.isActive=false in MongoDB
- MerchantLookupService.resolve returns QR_DEACTIVATED after deactivation
- Idempotent: second POST on already-DEACTIVATED QR returns 200 with no additional DB writes (verify via query count)
- audit_log row has actorId, qrCodeId, prevStatus=ACTIVE, newStatus=DEACTIVATED
- Redis cache evicted: next resolve call hits MongoDB not cache (verify TTL gap)
**Depends on:** 9.3-T21, 9.3-T20, 9.3-T23

### 9.3-T25 — Add OpenTelemetry tracing and Micrometer metrics to sync pipeline  _(40 min)_
**Context:** WBS 9.3. Per STACK.md, OpenTelemetry for traces and Prometheus/Micrometer for metrics. Required: (1) trace span zeropay.merchant-sync.{fileType} per sync job in MerchantSyncSftpPoller.poll(); (2) counter merchant_sync_records_total{fileType, changeType, result} incremented per record in MerchantSyncService and QrSyncService; (3) gauge merchant_active_count{schemeId=ZEROPAY} refreshed every 60s from PostgreSQL COUNT; (4) histogram merchant_sync_duration_seconds{fileType} in MerchantSyncOrchestrator.sync. All via Spring Boot Actuator /actuator/prometheus.
**Steps:** Inject Tracer (OTel API) into MerchantSyncSftpPoller; create and end span named zeropay.merchant-sync.{fileType} around poll body; Inject MeterRegistry into MerchantSyncService and QrSyncService; increment merchant_sync_records_total counter with fileType, changeType, result tags per record; Create MerchantMetricsRefresher @Component with @Scheduled(fixedDelay=60000): query SELECT COUNT(*) FROM merchant WHERE is_active=true and register as Gauge; Add Timer.Sample in MerchantSyncOrchestrator.sync to record merchant_sync_duration_seconds with fileType tag; Confirm /actuator/prometheus endpoint exposes all four metric names in test
**Deliverable:** services/zeropay-adapter/src/main/java/com/gme/pay/zeropay/metrics/MerchantSyncMetrics.java and updated MerchantSyncService.java, MerchantSyncSftpPoller.java, MerchantSyncOrchestrator.java
**Acceptance / logic checks:**
- Span zeropay.merchant-sync.ZP0041 appears in OTLP trace output after test run
- Counter merchant_sync_records_total{fileType=ZP0041,changeType=I,result=success} increments by 2 after a 2-insert sync
- Gauge merchant_active_count reflects live PostgreSQL count of is_active=true merchants
- merchant_sync_duration_seconds histogram bucket visible at /actuator/prometheus
- All metric names use snake_case and include schemeId tag
**Depends on:** 9.3-T18, 9.3-T19

### 9.3-T26 — Create ZeroPayMerchantSyncProperties @ConfigurationProperties and application.yml config section  _(30 min)_
**Context:** WBS 9.3. All zeropay.merchant-sync.* config keys must be bound via a @ConfigurationProperties class and documented in application.yml. Class ZeroPayMerchantSyncProperties (prefix=zeropay.merchant-sync): cron (String, default 0 30 6 * * *), retryMaxAttempts (int, default 3), retryBackoffSeconds (List<Integer>, default [30,120,600]), archiveDir (String, default /data/zeropay/archive), fileRetentionDays (int, default 90), sftpHost (String), sftpPort (int, default 22), pgpPrivateKeyRef (String). Annotate with @Validated and @NotBlank on required fields (sftpHost, pgpPrivateKeyRef). Missing required field causes BindException at startup.
**Steps:** Create services/zeropay-adapter/src/main/java/com/gme/pay/zeropay/config/ZeroPayMerchantSyncProperties.java with @ConfigurationProperties(prefix=zeropay.merchant-sync) and @Validated; Add all fields with types and defaults as described; @NotBlank on sftpHost and pgpPrivateKeyRef; Update services/zeropay-adapter/src/main/resources/application.yml with zeropay.merchant-sync section and YAML comments for each key; Add @EnableConfigurationProperties(ZeroPayMerchantSyncProperties.class) to ZeroPayAdapterConfig.java; Add Spring Boot integration test ZeroPayMerchantSyncPropertiesTest confirming bean loads with default values
**Deliverable:** services/zeropay-adapter/src/main/java/com/gme/pay/zeropay/config/ZeroPayMerchantSyncProperties.java and updated services/zeropay-adapter/src/main/resources/application.yml
**Acceptance / logic checks:**
- Spring context loads with all default values without exceptions
- Missing sftpHost in yml causes BindException at startup (test with empty property)
- retryBackoffSeconds is List<Integer> with 3 elements [30,120,600] by default
- application.yml has YAML comments (# prefix) for each zeropay.merchant-sync.* key
- ZeroPayMerchantSyncProperties.fileRetentionDays default is 90
**Depends on:** 9.3-T04, 9.3-T19

### 9.3-T27 — Unit tests: ZeroPayDeltaFileParser with fixture files for ZP0041 and ZP0043  _(40 min)_
**Context:** WBS 9.3. JUnit 5 unit tests for ZeroPayDeltaFileParser. Test fixtures in services/zeropay-adapter/src/test/resources/fixtures/. Five test vectors: (1) 3-record ZP0041 file with header, 2 I and 1 D records, matching trailer -> records.size()==3, 2 changeType=I, 1 changeType=D, parseErrors empty; (2) ZP0041 with trailer count mismatch (says 5, actual 3) -> isCountMismatch=true, parseErrors non-empty, no exception thrown; (3) ZP0043 with 1 D record -> QrSyncRecord.deactivationDate non-null, isActive derived false; (4) ZP0043 line with unknown statusCode=X -> record excluded, parseErrors has 1 entry; (5) empty byte array -> ParseResult.records empty, parseErrors has EMPTY_FILE entry.
**Steps:** Create src/test/resources/fixtures/ZP0041_sample.dat as pipe-delimited fixture file with header, 2 I + 1 D detail lines, correct trailer; Create src/test/resources/fixtures/ZP0043_sample.dat with header, 1 D record, trailer; and ZP0043_bad_status.dat with statusCode=X; Create ZeroPayDeltaFileParserTest.java in zeropay-adapter test package with 5 @Test methods; Test (1): load ZP0041_sample.dat; assert records.size()==3, changeTypes, parseErrors.isEmpty(); Tests (2)-(5) as described in context; Run ./gradlew :zeropay-adapter:test to confirm all pass
**Deliverable:** services/zeropay-adapter/src/test/java/com/gme/pay/zeropay/batch/parser/ZeroPayDeltaFileParserTest.java (5 tests, all passing)
**Acceptance / logic checks:**
- All 5 tests pass via ./gradlew :zeropay-adapter:test
- Test (1) asserts exact field values (merchantId, changeType) on first record
- Test (2) asserts no records returned when isCountMismatch=true
- Test (3) asserts QrSyncRecord.deactivationDate equals date value in fixture
- Test (5) asserts parseErrors contains entry with EMPTY_FILE text
**Depends on:** 9.3-T06

### 9.3-T28 — Integration tests: MerchantSyncService and QrSyncService with Testcontainers PostgreSQL  _(50 min)_
**Context:** WBS 9.3. Spring @SpringBootTest integration tests using Testcontainers PostgreSQL 16 for MerchantSyncService and QrSyncService. Five test vectors: (1) 2 I + 1 D records applied -> 2 ACTIVE and 1 DEACTIVATED in merchant table; (2) duplicate merchant_id I records applied -> 1 merchant row (upsert); (3) D record for merchant that has linked qr_code rows -> merchant DEACTIVATED but qr_code rows NOT automatically deactivated (QR deactivation is separate ZP0043 job); (4) account_no encrypted: raw DB value != plaintext input; (5) @Transactional rollback on exception thrown by 3rd record -> 0 rows committed. Uses @DynamicPropertySource for Testcontainers DataSource URL.
**Steps:** Create services/merchant-qr-data/src/test/java/com/gme/pay/merchantqr/service/MerchantSyncServiceTest.java; Use @SpringBootTest and @Testcontainers with PostgreSQL 16 image; Pre-apply Flyway V1-V5 migrations against Testcontainers (auto via Flyway spring.flyway.enabled=true); Write 5 @Test methods matching the test vectors in context; Mock ApplicationEventPublisher and NotificationService to avoid side effects; Run ./gradlew :merchant-qr-data:test
**Deliverable:** services/merchant-qr-data/src/test/java/com/gme/pay/merchantqr/service/MerchantSyncServiceTest.java (5 tests, all passing)
**Acceptance / logic checks:**
- Test (1): merchant table has 2 rows with is_active=true and 1 with is_active=false after apply
- Test (2): SELECT COUNT(*) FROM merchant WHERE merchant_id=... returns 1 (no duplicate)
- Test (3): qr_code rows linked to deactivated merchant still have is_active=true
- Test (4): raw account_no in DB (via JdbcTemplate SELECT) != input plaintext string
- Test (5): SELECT COUNT(*) FROM merchant returns 0 after transactional rollback
**Depends on:** 9.3-T10, 9.3-T11, 9.3-T01

### 9.3-T29 — Integration tests: MerchantFullSyncService staging+promote with Testcontainers PostgreSQL  _(50 min)_
**Context:** WBS 9.3. Integration tests for MerchantFullSyncService. Five test vectors: (1) 5 merchants in full sync, 2 already in live table, 3 new -> all 5 ACTIVE in merchant table; (2) live table has 6 merchants, full sync has 5 (one omitted) -> omitted merchant set DEACTIVATED; (3) isRecordCountMismatch=true -> SyncAbortedException thrown, live table unchanged; (4) DataAccessException during staging insert -> rollback, live table unchanged, staging table empty; (5) successful sync -> merchant_sync_log row with status=SUCCESS and correct counts.
**Steps:** Create services/merchant-qr-data/src/test/java/com/gme/pay/merchantqr/service/MerchantFullSyncServiceTest.java; Use @SpringBootTest and @Testcontainers PostgreSQL 16; Pre-populate merchant table for tests (2) and (4) via JdbcTemplate in @BeforeEach; Write 5 @Test methods as described; Use Mockito to inject a throwing DataSource wrapper for test (4); Run ./gradlew :merchant-qr-data:test
**Deliverable:** services/merchant-qr-data/src/test/java/com/gme/pay/merchantqr/service/MerchantFullSyncServiceTest.java (5 tests, all passing)
**Acceptance / logic checks:**
- Test (2): absent merchant has status=DEACTIVATED and is_active=false after promote
- Test (3): assertThrows(SyncAbortedException.class); SELECT COUNT FROM merchant matches pre-test count
- Test (4): SELECT COUNT(*) FROM merchant_staging returns 0 after rollback
- Test (5): merchant_sync_log row has records_deactivated=1 and status=SUCCESS
- All 5 tests pass via ./gradlew :merchant-qr-data:test
**Depends on:** 9.3-T13, 9.3-T02

### 9.3-T30 — Integration tests: MerchantLookupService with Testcontainers MongoDB and Redis  _(50 min)_
**Context:** WBS 9.3. Integration tests for MerchantLookupService and Redis caching using Testcontainers MongoDB and Redis. Six test vectors: (1) active merchant + active QR matching merchantId -> 200 MerchantResolutionResult, feeType populated; (2) DEACTIVATED merchant -> error MERCHANT_DEACTIVATED; (3) ACTIVE merchant + DEACTIVATED QR -> QR_DEACTIVATED; (4) QR.merchantId != passed merchantId -> QR_MERCHANT_MISMATCH; (5) second call same args -> MongoDB not queried (cache hit via Redis - Mockito verify mongoRepo times(1)); (6) deactivation event evicts cache: MerchantSyncedEvent published -> Redis key deleted -> next resolve hits MongoDB.
**Steps:** Create services/merchant-qr-data/src/test/java/com/gme/pay/merchantqr/service/MerchantLookupServiceTest.java; Use @SpringBootTest and @Testcontainers with MongoDB and Redis images; Pre-insert MerchantDocument and QrCodeDocument fixtures via MongoTemplate in @BeforeEach; Write 6 @Test methods as described; For test (5): spy on MerchantMongoRepository and verify invocation count; For test (6): publish MerchantSyncedEvent manually and confirm Redis key absent after event; Run ./gradlew :merchant-qr-data:test
**Deliverable:** services/merchant-qr-data/src/test/java/com/gme/pay/merchantqr/service/MerchantLookupServiceTest.java (6 tests, all passing)
**Acceptance / logic checks:**
- Test (1): MerchantResolutionResult.error is null and feeType is non-null
- Test (2): errorCode == MERCHANT_DEACTIVATED (not MERCHANT_NOT_FOUND)
- Test (4): errorCode == QR_MERCHANT_MISMATCH (not QR_NOT_FOUND)
- Test (5): mongoRepo.findById called exactly once across two resolve calls
- Test (6): after MerchantSyncedEvent(isActive=false), Redis key merchant-resolve:{merchantId}:* absent
**Depends on:** 9.3-T21, 9.3-T20, 9.3-T08

<!-- wbs-v3-gap-closure -->

---

## WBS v3 gap-closure tickets (re-baseline, 2026-06-10)

These tickets convert this service's PARTIAL audit findings into DONE and add work discovered during the build. Statuses live on the `Backlog` sheet of `GMEPay+_Task_Backlog.xlsx`; phase sequencing on the `Completion Plan v3` sheet of `GMEPay+_WBS.xlsx`.

### 17.2-G12 — merchant-qr-data: swap H2 for real PostgreSQL ITs
*Completion phase:* **R1** · *Est:* 120 min · *Role:* Backend · *Deps:* 17.1-G02

**Context.** Tests currently run on H2 in PostgreSQL mode. Acceptance requires real PG. Scope: relational shadow of merchant mirror (audit trail).

**Steps.**
- Add Testcontainers postgres:16 to the service's ITs
- Run Flyway migrations against it; fix PG-only syntax drift
- Keep H2 only for pure unit slices

**Deliverable.** Repository/migration ITs green on PostgreSQL 16

**Acceptance.**
- ./gradlew :services:merchant-qr-data:test green with Testcontainers
- Migration checksum stable; no H2-mode workarounds left

### 17.7-G01 — MongoDB-backed merchant store
*Completion phase:* **R1** · *Est:* 140 min · *Role:* Backend · *Deps:* 17.1-G03,18.7-G01

**Context.** Lookup store is in-memory. Architecture diagram says MongoDB for the merchant/QR mirror (pending ADR 18.7 confirms keep).

**Steps.**
- spring-data-mongodb; merchants collection keyed by qr hash
- Migrate InMemory store behind same port interface
- IT via Testcontainers mongo

**Deliverable.** Mongo merchant lookup

**Acceptance.**
- GET /v1/merchants/{qr} served from Mongo; p95 <20ms local

### 17.7-G02 — Merchant sync job (file→Mongo)
*Completion phase:* **R1** · *Est:* 140 min · *Role:* Backend · *Deps:* 17.7-G01,17.8-G01

**Context.** KFTC distributes merchant master via batch file. Nightly sync parses + upserts; tombstones removed merchants.

**Steps.**
- Scheduled job reads file from MinIO (17.8)
- Upsert with change counts logged
- Dead-merchant tombstoning

**Deliverable.** Nightly merchant sync

**Acceptance.**
- Re-running same file is idempotent; counts reported

