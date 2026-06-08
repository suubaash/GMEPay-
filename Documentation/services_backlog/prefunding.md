# prefunding  (backend)

**Scope:** Prepaid USD balance, atomic deduction, low-balance alerts

**Owned WBS work-packages:** 3.5, 6.1, 6.2, 6.3, 6.4, 6.5  ·  **Tickets:** 160  ·  **Est:** 94.4h

> Self-contained backlog for this service. Build in its own module against `shared-libs` contracts. Each ticket has a deliverable + acceptance checks.


## WBS 3.5 — Prefunding ledger tables (double-entry)
### 3.5-T01 — Flyway migration: create prefunding_account table in svc-prefunding  _(25 min)_
**Context:** The svc-prefunding module (services/prefunding-balance) owns all prefunding state in PostgreSQL 16. One row per OVERSEAS partner holds the current USD balance. Schema: id BIGINT PK (sequence), partner_id BIGINT FK->partner UNIQUE NOT NULL, currency CHAR(3) NOT NULL DEFAULT 'USD', balance DECIMAL(20,4) NOT NULL DEFAULT 0 CHECK(balance>=0), low_balance_threshold DECIMAL(20,4) NOT NULL DEFAULT 10000.00 CHECK(low_balance_threshold>0), created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), created_by VARCHAR(120), updated_by VARCHAR(120). Balance updates must use SELECT FOR UPDATE; no application-level locking allowed.
**Steps:** Create services/prefunding-balance/src/main/resources/db/migration/V201__prefunding_account.sql; Add CREATE TABLE prefunding_account with all columns and constraints; Add UNIQUE constraint on partner_id; Add index idx_prefunding_account_partner on prefunding_account(partner_id); Add table comment stating SELECT FOR UPDATE is required for all balance mutations
**Deliverable:** services/prefunding-balance/src/main/resources/db/migration/V201__prefunding_account.sql
**Acceptance / logic checks:**
- Table DDL uses DECIMAL(20,4) for balance and low_balance_threshold
- balance has CHECK (balance >= 0) constraint
- partner_id has UNIQUE constraint enforcing one account per OVERSEAS partner
- currency DEFAULT 'USD' is present
- Flyway applies migration cleanly against a Testcontainers Postgres 16 instance
**Depends on:** 3.1-T01

### 3.5-T02 — Flyway migration: create prefunding_ledger_entry table (append-only double-entry)  _(25 min)_
**Context:** prefunding_ledger_entry is an immutable append-only double-entry ledger in PostgreSQL 16 (svc-prefunding). Every debit or credit to a prefunding_account creates exactly one row; rows are NEVER updated or deleted. Schema: id BIGINT PK (sequence), account_id BIGINT NOT NULL FK->prefunding_account, txn_ref VARCHAR(64) NULL (references transaction.txn_ref for payment debits; internal reference for top-ups), entry_type VARCHAR(20) NOT NULL CHECK IN ('DEBIT_PAYMENT','DEBIT_REVERSAL','CREDIT_TOPUP','CREDIT_REVERSAL'), amount DECIMAL(20,4) NOT NULL CHECK(amount>0), balance_after DECIMAL(20,4) NOT NULL, note VARCHAR(255) NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), created_by VARCHAR(120) NULL. No updated_at column.
**Steps:** Create services/prefunding-balance/src/main/resources/db/migration/V202__prefunding_ledger_entry.sql; Add CREATE TABLE prefunding_ledger_entry with all columns, PK, FK to prefunding_account, and CHECK constraints; Omit updated_at intentionally (append-only invariant); Add compound index idx_ple_account_time on prefunding_ledger_entry(account_id, created_at) for history queries; Add partial index idx_ple_txn_ref on prefunding_ledger_entry(txn_ref) WHERE txn_ref IS NOT NULL
**Deliverable:** services/prefunding-balance/src/main/resources/db/migration/V202__prefunding_ledger_entry.sql
**Acceptance / logic checks:**
- entry_type CHECK constraint lists exactly: DEBIT_PAYMENT, DEBIT_REVERSAL, CREDIT_TOPUP, CREDIT_REVERSAL
- amount has CHECK (amount > 0)
- No updated_at column in DDL
- Compound index (account_id, created_at) is present
- Flyway applies migration after V201 with no errors in Testcontainers Postgres 16
**Depends on:** 3.5-T01

### 3.5-T03 — Flyway migration: create low_balance_alert_config table  _(20 min)_
**Context:** low_balance_alert_config stores per-partner configurable low-balance alert settings in PostgreSQL 16 (svc-prefunding). Schema: id BIGINT PK (sequence), partner_id BIGINT NOT NULL FK->partner UNIQUE, threshold_usd DECIMAL(20,4) NOT NULL DEFAULT 10000.00 CHECK(threshold_usd>0), alert_email VARCHAR(255) NOT NULL, is_active BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), created_by VARCHAR(120), updated_by VARCHAR(120). The default alert threshold matches the alert threshold mentioned in the spec (USD 10,000 per partner). Changing threshold only affects future alert evaluations.
**Steps:** Create services/prefunding-balance/src/main/resources/db/migration/V203__low_balance_alert_config.sql; Add CREATE TABLE low_balance_alert_config with all columns, PK, FK, CHECK, and UNIQUE constraints; Add index idx_lbac_partner on low_balance_alert_config(partner_id)
**Deliverable:** services/prefunding-balance/src/main/resources/db/migration/V203__low_balance_alert_config.sql
**Acceptance / logic checks:**
- partner_id has UNIQUE constraint (one config per partner)
- threshold_usd has CHECK (threshold_usd > 0) and DEFAULT 10000.00
- is_active DEFAULT TRUE is present
- Flyway applies migration after V202 with no errors
- All three prefunding tables visible via \dt in psql after full V201-V203 migration run
**Depends on:** 3.5-T02

### 3.5-T04 — Flyway migration: create outbox_event table for transactional outbox in svc-prefunding  _(20 min)_
**Context:** Per STACK.md messaging strategy: Phase 1 uses a transactional Outbox table written in the same DB transaction as balance updates; Kafka transport wired in integration phase. The svc-prefunding service writes domain events (e.g. prefunding.low_balance) into outbox_event atomically. Schema in PostgreSQL 16: id BIGINT PK (sequence), aggregate_type VARCHAR(80) NOT NULL, aggregate_id VARCHAR(80) NOT NULL, event_type VARCHAR(120) NOT NULL, payload JSONB NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), published_at TIMESTAMPTZ NULL, status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK IN ('PENDING','PUBLISHED','FAILED'). Partial index on status='PENDING' for efficient polling.
**Steps:** Create services/prefunding-balance/src/main/resources/db/migration/V204__outbox_event.sql; Add CREATE TABLE outbox_event with all columns and CHECK on status; Add partial index idx_outbox_pending on outbox_event(created_at) WHERE status='PENDING'; Add comment: published_at set by outbox-poller when event forwarded to Kafka (integration phase)
**Deliverable:** services/prefunding-balance/src/main/resources/db/migration/V204__outbox_event.sql
**Acceptance / logic checks:**
- status CHECK constraint includes exactly PENDING, PUBLISHED, FAILED
- Partial index WHERE status='PENDING' is present in DDL
- payload is type JSONB
- published_at is nullable
- Flyway applies migration after V203 with no errors
**Depends on:** 3.5-T03

### 3.5-T05 — Flyway migration: seed default low_balance_alert_config for existing OVERSEAS partners  _(20 min)_
**Context:** After schema migrations V201-V204, data migration V205 inserts a default low_balance_alert_config row for every OVERSEAS partner that does not yet have one (threshold_usd=10000.00, is_active=TRUE). This ensures the low-balance check in AtomicPrefundingDeductService never fails due to missing config. Uses INSERT ... WHERE NOT EXISTS for idempotency. OVERSEAS partner type stored in partner.type column as 'OVERSEAS'.
**Steps:** Create services/prefunding-balance/src/main/resources/db/migration/V205__seed_low_balance_alert_config.sql; Write INSERT INTO low_balance_alert_config(partner_id, threshold_usd, alert_email, is_active, created_by) SELECT p.id, 10000.00, COALESCE(p.ops_email,'ops@gme.com'), TRUE, 'system' FROM partner p WHERE p.type='OVERSEAS' AND NOT EXISTS (SELECT 1 FROM low_balance_alert_config l WHERE l.partner_id=p.id); Confirm idempotency: running the SQL twice must produce no duplicate rows and no error
**Deliverable:** services/prefunding-balance/src/main/resources/db/migration/V205__seed_low_balance_alert_config.sql
**Acceptance / logic checks:**
- Running V205 twice does not produce duplicates or errors (idempotent via WHERE NOT EXISTS)
- Every OVERSEAS partner has exactly one low_balance_alert_config row after migration
- threshold_usd=10000.00 and is_active=TRUE for all seeded rows
- LOCAL partner rows are NOT inserted
- Flyway applies V205 cleanly after V204
**Depends on:** 3.5-T03

### 3.5-T06 — Define PrefundingAccount and PrefundingLedgerEntry JPA entities in svc-prefunding  _(35 min)_
**Context:** Module: services/prefunding-balance (:svc-prefunding). Spring Boot 3.x / Spring Data JPA. PrefundingAccount maps to prefunding_account; PrefundingLedgerEntry maps to prefunding_ledger_entry. All money fields DECIMAL mapped to java.math.BigDecimal with @Column(precision=20, scale=4). EntryType Java enum: DEBIT_PAYMENT, DEBIT_REVERSAL, CREDIT_TOPUP, CREDIT_REVERSAL persisted as VARCHAR. PrefundingLedgerEntry is immutable post-persist: no setters, all fields set via constructor, @Column(updatable=false) on every field. PrefundingAccount.balance is only mutated by the domain service via the atomic deduction/credit method.
**Steps:** Create PrefundingAccount.java at services/prefunding-balance/src/main/java/com/gme/pay/prefunding/domain/PrefundingAccount.java with @Entity and all columns; Create EntryType.java enum at com/gme/pay/prefunding/domain/EntryType.java with 4 values; Create PrefundingLedgerEntry.java at com/gme/pay/prefunding/domain/PrefundingLedgerEntry.java with @Entity, all fields @Column(updatable=false), constructor-only init; Annotate all money BigDecimal fields with @Column(precision=20, scale=4)
**Deliverable:** services/prefunding-balance/src/main/java/com/gme/pay/prefunding/domain/PrefundingAccount.java and PrefundingLedgerEntry.java
**Acceptance / logic checks:**
- BigDecimal used for balance, low_balance_threshold, amount, balance_after — no double or float
- PrefundingLedgerEntry has no setter methods (immutable by design)
- EntryType enum has exactly 4 values matching the DB CHECK constraint
- @Column(precision=20, scale=4) present on all money fields
- @DataJpaTest slice with Testcontainers Postgres 16: save PrefundingAccount then findById returns equal BigDecimal balance
**Depends on:** 3.5-T02

### 3.5-T07 — Define LowBalanceAlertConfig entity and Spring Data repositories  _(35 min)_
**Context:** Module: :svc-prefunding. LowBalanceAlertConfig JPA entity maps to low_balance_alert_config. Repositories needed: PrefundingAccountRepository (JpaRepository) with lockByPartnerId using @Lock(LockModeType.PESSIMISTIC_WRITE) — this is the SELECT FOR UPDATE path for atomic deductions; PrefundingLedgerEntryRepository (JpaRepository) with findByAccountIdOrderByCreatedAtDesc(Pageable); LowBalanceAlertConfigRepository with findByPartnerId and findByPartnerIdAndIsActiveTrue.
**Steps:** Create LowBalanceAlertConfig.java entity at com/gme/pay/prefunding/domain/LowBalanceAlertConfig.java; Create PrefundingAccountRepository.java interface with @Lock(PESSIMISTIC_WRITE) method: findByPartnerId annotated with @Lock(LockModeType.PESSIMISTIC_WRITE) and @QueryHints for timeout; Create PrefundingLedgerEntryRepository.java with findByAccountIdOrderByCreatedAtDesc(Pageable p); Create LowBalanceAlertConfigRepository.java with findByPartnerId and findByPartnerIdAndIsActiveTrue
**Deliverable:** services/prefunding-balance/src/main/java/com/gme/pay/prefunding/repository/ (3 repository interfaces + LowBalanceAlertConfig entity)
**Acceptance / logic checks:**
- PrefundingAccountRepository locking method carries @Lock(PESSIMISTIC_WRITE) annotation
- findByAccountIdOrderByCreatedAtDesc accepts Pageable parameter and returns Page
- LowBalanceAlertConfigRepository.findByPartnerIdAndIsActiveTrue returns only is_active=TRUE configs
- @DataJpaTest slice with Testcontainers Postgres 16 confirms lockByPartnerId issues SELECT FOR UPDATE (verify via pg_stat_activity in concurrent test)
**Depends on:** 3.5-T06

### 3.5-T08 — Implement AtomicPrefundingDeductService: SELECT FOR UPDATE deduction with ledger entry  _(45 min)_
**Context:** Module: :svc-prefunding. Core deduction rule from spec: deduction is ATOMIC using SELECT FOR UPDATE. Steps inside a single @Transactional method: (1) lock prefunding_account row via PrefundingAccountRepository @Lock(PESSIMISTIC_WRITE) by partnerId, (2) if balance < deductionAmountUsd throw InsufficientPrefundingException (error code INSUFFICIENT_PREFUNDING from lib-errors), (3) account.balance -= deductionAmountUsd, (4) insert PrefundingLedgerEntry(type=DEBIT_PAYMENT, amount=deductionAmountUsd, balanceAfter=newBalance, txnRef=txnRef), (5) if newBalance < account.lowBalanceThreshold insert OutboxEvent(aggregate_type='prefunding_account', event_type='prefunding.low_balance', payload={partnerId, newBalance, thresholdUsd}). Return DeductionResult(newBalance, ledgerEntryId). Scheme is never called without a prior committed deduction.
**Steps:** Create AtomicPrefundingDeductService.java at com/gme/pay/prefunding/service/AtomicPrefundingDeductService.java @Service; Inject PrefundingAccountRepository, PrefundingLedgerEntryRepository, OutboxEventRepository; Implement deduct(Long partnerId, BigDecimal amountUsd, String txnRef): DeductionResult annotated @Transactional; Throw InsufficientPrefundingException from lib-errors when balance < amount; Insert OutboxEvent in the same transaction when post-deduction balance < low_balance_threshold
**Deliverable:** services/prefunding-balance/src/main/java/com/gme/pay/prefunding/service/AtomicPrefundingDeductService.java
**Acceptance / logic checks:**
- @Transactional annotation present on the deduct method
- InsufficientPrefundingException thrown when balance=100.00 and deduction=100.01
- Ledger entry balance_after equals account balance after deduction (e.g. balance=500, deduct=36.9714, balance_after=463.0286)
- OutboxEvent with event_type='prefunding.low_balance' inserted when new balance < low_balance_threshold
- No OutboxEvent inserted when new balance >= threshold
**Depends on:** 3.5-T07, 3.5-T04

### 3.5-T09 — Implement PrefundingCreditService: CREDIT_TOPUP and CREDIT_REVERSAL with ledger entries  _(40 min)_
**Context:** Module: :svc-prefunding. Two credit operations use a common atomic pattern: CREDIT_TOPUP (manual top-up by ops) and CREDIT_REVERSAL (reverse a prior DEBIT_PAYMENT when scheme call fails after deduction per spec sec 5.2: UNCERTAIN resolution). Both lock prefunding_account with SELECT FOR UPDATE, increase balance, and insert a PrefundingLedgerEntry. Reversal guards: check no existing CREDIT_REVERSAL for the same txnRef (duplicate reversal must throw DuplicateReversalException). CREDIT_TOPUP reference is an internal ops reference, not a txnRef. Credit does not trigger low-balance check (balance increasing).
**Steps:** Create PrefundingCreditService.java at com/gme/pay/prefunding/service/PrefundingCreditService.java @Service; Implement topUp(Long partnerId, BigDecimal amountUsd, String reference, String actor): @Transactional — lock row, add amount, insert CREDIT_TOPUP ledger entry; Implement reverse(Long partnerId, BigDecimal amountUsd, String originalTxnRef, String actor): @Transactional — check no existing CREDIT_REVERSAL for txnRef, lock row, add amount, insert CREDIT_REVERSAL entry; Throw DuplicateReversalException (lib-errors) if CREDIT_REVERSAL for that txnRef already exists
**Deliverable:** services/prefunding-balance/src/main/java/com/gme/pay/prefunding/service/PrefundingCreditService.java
**Acceptance / logic checks:**
- topUp increases balance by exact BigDecimal amount and inserts CREDIT_TOPUP ledger row
- reverse inserts CREDIT_REVERSAL row and restores balance
- Second reverse call with same txnRef throws DuplicateReversalException; balance unchanged
- balance_after in ledger entry equals account.balance after operation
- Both topUp and reverse annotated @Transactional
**Depends on:** 3.5-T07

### 3.5-T10 — Implement PrefundingBalanceQueryService: current balance and paginated ledger history  _(30 min)_
**Context:** Module: :svc-prefunding. OVERSEAS partners can query current balance via GET /v1/balance. LOCAL partners (partner.type=LOCAL) receive HTTP 403 (PartnerTypeNotEligibleException). getBalance(Long partnerId): @Transactional(readOnly=true), load account, throw AccountNotFoundException if missing. getLedgerHistory(Long partnerId, Pageable pageable): @Transactional(readOnly=true), return Page<PrefundingLedgerEntry> ordered by created_at DESC. No SELECT FOR UPDATE needed — read-only. Partner type resolved from partner registry (Config & Registry service or local partner table).
**Steps:** Create PrefundingBalanceQueryService.java at com/gme/pay/prefunding/service/PrefundingBalanceQueryService.java @Service; Implement getBalance(Long partnerId): @Transactional(readOnly=true), throw AccountNotFoundException if no row found; Implement getLedgerHistory(Long partnerId, Pageable pageable): @Transactional(readOnly=true), delegate to PrefundingLedgerEntryRepository.findByAccountIdOrderByCreatedAtDesc; Validate partner type before serving: LOCAL -> throw PartnerTypeNotEligibleException(PREFUNDING_NOT_APPLICABLE)
**Deliverable:** services/prefunding-balance/src/main/java/com/gme/pay/prefunding/service/PrefundingBalanceQueryService.java
**Acceptance / logic checks:**
- getBalance returns correct BigDecimal for OVERSEAS partner
- getLedgerHistory returns entries in descending created_at order
- PartnerTypeNotEligibleException thrown for LOCAL partner
- @Transactional(readOnly=true) on both methods
- Testcontainers integration test: insert 5 ledger rows, getLedgerHistory page 0 size 3 returns 3 rows; page 1 size 3 returns 2 rows and hasNext=false
**Depends on:** 3.5-T07

### 3.5-T11 — Define PrefundingLowBalanceEvent record and OutboxEvent entity in lib-events and svc-prefunding  _(30 min)_
**Context:** Per STACK.md: define event schemas in lib-events now; Kafka wired in integration phase. PrefundingLowBalanceEvent(Long partnerId, BigDecimal currentBalanceUsd, BigDecimal thresholdUsd, Instant occurredAt) as a Java record in lib-events. OutboxEvent JPA entity in svc-prefunding: id BIGINT PK, aggregate_type VARCHAR(80), aggregate_id VARCHAR(80), event_type VARCHAR(120), payload stored as String (serialized JSON), created_at TIMESTAMPTZ, published_at TIMESTAMPTZ nullable, status OutboxStatus enum (PENDING, PUBLISHED, FAILED). OutboxEventRepository provides findTop100ByStatusOrderByCreatedAtAsc for outbox polling.
**Steps:** Create PrefundingLowBalanceEvent.java at libs/lib-events/src/main/java/com/gme/pay/events/prefunding/PrefundingLowBalanceEvent.java as a Java record with 4 fields; Create OutboxStatus.java enum at com/gme/pay/prefunding/outbox/OutboxStatus.java: PENDING, PUBLISHED, FAILED; Create OutboxEvent.java @Entity at com/gme/pay/prefunding/outbox/OutboxEvent.java mapping to outbox_event table; Create OutboxEventRepository.java extending JpaRepository with findTop100ByStatusOrderByCreatedAtAsc(OutboxStatus status)
**Deliverable:** libs/lib-events/src/main/java/com/gme/pay/events/prefunding/PrefundingLowBalanceEvent.java and services/prefunding-balance/src/main/java/com/gme/pay/prefunding/outbox/OutboxEvent.java
**Acceptance / logic checks:**
- PrefundingLowBalanceEvent is a Java record with 4 fields: partnerId, currentBalanceUsd, thresholdUsd, occurredAt
- OutboxEvent maps to outbox_event table with correct column names and JSONB-backed String payload
- OutboxStatus enum has exactly 3 values: PENDING, PUBLISHED, FAILED
- findTop100ByStatusOrderByCreatedAtAsc compiles and returns correct rows in @DataJpaTest slice with Testcontainers Postgres 16
**Depends on:** 3.5-T04

### 3.5-T12 — Implement OutboxEventPublisher interface and in-process polling publisher  _(35 min)_
**Context:** Module: :svc-prefunding. Per STACK.md: define EventPublisher interface now; Kafka is the integration-phase transport. EventPublisher interface: void publish(OutboxEvent event). InProcessEventPublisher implements EventPublisher: logs the event payload (Phase 1 placeholder). OutboxPollingPublisher @Component with @Scheduled(fixedDelay=5000): polls up to 100 PENDING rows via OutboxEventRepository, calls publisher, marks each PUBLISHED (sets published_at=NOW()); on exception marks FAILED. Each row processed in its own @Transactional to avoid all-or-nothing batch failure. @EnableScheduling in PrefundingBalanceApplication.
**Steps:** Create EventPublisher.java interface at com/gme/pay/prefunding/outbox/EventPublisher.java; Create InProcessEventPublisher.java @Component implementing EventPublisher — logs event type and aggregate_id, Phase 1 placeholder; Create OutboxPollingPublisher.java @Component with @Scheduled(fixedDelay=5000) calling OutboxEventRepository.findTop100ByStatusOrderByCreatedAtAsc(PENDING); Wrap each row's processing in @Transactional(propagation=REQUIRES_NEW) to isolate failures; Confirm @EnableScheduling in PrefundingBalanceApplication.java
**Deliverable:** services/prefunding-balance/src/main/java/com/gme/pay/prefunding/outbox/OutboxPollingPublisher.java
**Acceptance / logic checks:**
- OutboxPollingPublisher fetches at most 100 PENDING rows per polling cycle
- Status transitions PENDING -> PUBLISHED after successful publish call
- Status transitions PENDING -> FAILED on exception without affecting other rows in same batch
- @Transactional(REQUIRES_NEW) per-row processing confirmed by inspecting annotation
- @EnableScheduling present in application context
**Depends on:** 3.5-T11

### 3.5-T13 — Expose internal REST endpoints for Transaction Orchestrator (POST /internal/v1/prefunding)  _(40 min)_
**Context:** Module: :svc-prefunding. The Transaction Orchestrator (svc-payment-executor) calls svc-prefunding via internal REST. @RestController at /internal/v1/prefunding. Endpoints: POST /deduct body {partnerId, amountUsd, txnRef} -> 200 {newBalance, ledgerEntryId} or 402 INSUFFICIENT_PREFUNDING; POST /credit body {partnerId, amountUsd, txnRef, creditType: CREDIT_TOPUP|CREDIT_REVERSAL} -> 200 {newBalance, ledgerEntryId}; GET /{partnerId}/balance -> 200 {balance, currency:'USD'}. Spring Security: require ROLE_INTERNAL_SERVICE on all three endpoints. Map InsufficientPrefundingException to HTTP 402 via lib-errors GlobalExceptionHandler.
**Steps:** Create PrefundingInternalController.java at com/gme/pay/prefunding/api/internal/PrefundingInternalController.java @RestController @RequestMapping('/internal/v1/prefunding'); Wire AtomicPrefundingDeductService, PrefundingCreditService, PrefundingBalanceQueryService; Create request/response DTOs: DeductRequest, DeductResponse, CreditRequest, CreditResponse, BalanceResponse in com.gme.pay.prefunding.api.internal.dto; Add @PreAuthorize('hasRole(INTERNAL_SERVICE)') or Spring Security matcher for /internal/** -> ROLE_INTERNAL_SERVICE; Map InsufficientPrefundingException to HTTP 402 in lib-errors GlobalExceptionHandler or local @ExceptionHandler
**Deliverable:** services/prefunding-balance/src/main/java/com/gme/pay/prefunding/api/internal/PrefundingInternalController.java
**Acceptance / logic checks:**
- POST /internal/v1/prefunding/deduct with insufficient balance returns HTTP 402 and error code INSUFFICIENT_PREFUNDING
- POST /internal/v1/prefunding/deduct with sufficient balance returns 200 and correct newBalance
- GET /internal/v1/prefunding/{partnerId}/balance returns {balance, currency:'USD'}
- Request without ROLE_INTERNAL_SERVICE returns HTTP 403
- @WebMvcTest slice covers all three endpoints with MockMvc
**Depends on:** 3.5-T08, 3.5-T09, 3.5-T10

### 3.5-T14 — Expose partner-facing GET /v1/balance endpoint in svc-prefunding  _(35 min)_
**Context:** Module: :svc-prefunding. Per API-05 spec: GET /v1/balance returns calling OVERSEAS partner's current USD balance. LOCAL partners receive HTTP 403 with error code PREFUNDING_NOT_APPLICABLE. Partner identity extracted from JWT claim partner_id via SecurityContextHolder. Response: {balance: Decimal string, currency: 'USD', lowBalanceThreshold: Decimal string}. Balance serialized as string (not JSON number) to preserve DECIMAL(20,4) precision and avoid floating-point representation issues. Spring Security: require ROLE_PARTNER scope.
**Steps:** Create PrefundingPartnerController.java at com/gme/pay/prefunding/api/partner/PrefundingPartnerController.java @RestController @RequestMapping('/v1/balance'); Extract partner_id from JWT claims via SecurityContextHolder JWT authentication; Call PrefundingBalanceQueryService.getBalance and load low_balance_threshold from account entity; Map PartnerTypeNotEligibleException to HTTP 403 with PREFUNDING_NOT_APPLICABLE code; Add OpenAPI annotations; serialize BigDecimal fields as strings in DTO using @JsonSerialize
**Deliverable:** services/prefunding-balance/src/main/java/com/gme/pay/prefunding/api/partner/PrefundingPartnerController.java
**Acceptance / logic checks:**
- OVERSEAS partner JWT returns 200 with balance and currency='USD' and lowBalanceThreshold
- LOCAL partner JWT returns 403 with error code PREFUNDING_NOT_APPLICABLE
- Missing or expired JWT returns 401
- balance field is serialized as a string not a JSON number (e.g. '5000.0000' not 5000.0)
- @WebMvcTest slice confirms all three cases
**Depends on:** 3.5-T10, 3.5-T13

### 3.5-T15 — Gradle module setup and Spring Boot application bootstrap for svc-prefunding  _(30 min)_
**Context:** Module: services/prefunding-balance (:svc-prefunding). Create the Gradle sub-module, Spring Boot 3.x main class, and application.yml baseline. Dependencies: spring-boot-starter-data-jpa, spring-boot-starter-web, spring-boot-starter-security, spring-boot-starter-actuator, postgresql driver, flyway-core, micrometer-tracing-bridge-otel, opentelemetry-exporter-otlp, :lib-money, :lib-errors, :lib-api-contracts, :lib-events. application.yml: spring.datasource pointing to Postgres, spring.flyway.locations=classpath:db/migration, spring.jpa.hibernate.ddl-auto=validate, server.port=8085. Add module to root settings.gradle.
**Steps:** Create services/prefunding-balance/build.gradle with all required dependencies and Java 21 toolchain; Add ':svc-prefunding' (include 'services/prefunding-balance') to root settings.gradle; Create PrefundingBalanceApplication.java at com/gme/pay/prefunding/PrefundingBalanceApplication.java with @SpringBootApplication and @EnableScheduling; Create src/main/resources/application.yml with datasource, flyway, jpa, server, and actuator config; Run ./gradlew :svc-prefunding:compileJava to confirm compilation succeeds
**Deliverable:** services/prefunding-balance/build.gradle and services/prefunding-balance/src/main/java/com/gme/pay/prefunding/PrefundingBalanceApplication.java
**Acceptance / logic checks:**
- ./gradlew :svc-prefunding:compileJava succeeds
- @EnableScheduling present (required for outbox poller)
- spring.jpa.hibernate.ddl-auto=validate (Flyway is sole schema manager)
- Module listed in root settings.gradle
- lib-money and lib-errors listed as implementation dependencies
**Depends on:** 3.5-T01

### 3.5-T16 — Add OpenTelemetry tracing spans to deduction and credit service methods  _(35 min)_
**Context:** Module: :svc-prefunding. Per STACK.md: OpenTelemetry traces exported to Jaeger via OTLP. Add manual OTel spans wrapping the core business methods using Micrometer Tracing (Spring Boot auto-configured via micrometer-tracing-bridge-otel). Span names: 'prefunding.deduct', 'prefunding.credit.topup', 'prefunding.credit.reversal'. Span attributes: partner_id (Long), amount_usd (String). On InsufficientPrefundingException: record exception on span and set span status to ERROR. application.yml: management.otlp.tracing.endpoint=http://localhost:4318/v1/traces.
**Steps:** Add micrometer-tracing-bridge-otel and opentelemetry-exporter-otlp to build.gradle (if not already present from 3.5-T15); Inject io.micrometer.tracing.Tracer bean into AtomicPrefundingDeductService and PrefundingCreditService; Wrap deduction logic in Tracer.nextSpan('prefunding.deduct').start(); use try-with-resources (Tracer.SpanInScope); Tag spans with partner_id and amount_usd attributes; On InsufficientPrefundingException call span.error(exception) before rethrowing; Add management.otlp.tracing.endpoint to application.yml
**Deliverable:** Updated AtomicPrefundingDeductService.java and PrefundingCreditService.java with Micrometer Tracing spans
**Acceptance / logic checks:**
- Span 'prefunding.deduct' started and ended for every deduct invocation
- InsufficientPrefundingException causes span error flag to be set
- Span attributes include partner_id and amount_usd
- application.yml contains management.otlp.tracing.endpoint
- ./gradlew :svc-prefunding:compileJava succeeds
**Depends on:** 3.5-T08, 3.5-T09, 3.5-T15

### 3.5-T17 — Prometheus metrics: prefunding balance gauge and deduction/credit counters  _(35 min)_
**Context:** Module: :svc-prefunding. Per STACK.md: Prometheus + Grafana for metrics. Using Micrometer auto-configured by Spring Boot Actuator. Metrics: (1) Gauge 'prefunding.balance.usd' with tag partner_id reporting current balance for each OVERSEAS partner (polled every 30s via @Scheduled). (2) Counter 'prefunding.deduction.count' with tags partner_id, result (success|insufficient) in AtomicPrefundingDeductService. (3) Counter 'prefunding.credit.count' with tags partner_id, entry_type in PrefundingCreditService. Expose /actuator/prometheus. Alert threshold per spec: balance < 10000 USD per partner.
**Steps:** Inject MeterRegistry into AtomicPrefundingDeductService and PrefundingCreditService; Register Counter 'prefunding.deduction.count' with tags partner_id and result in deduct method; Register Counter 'prefunding.credit.count' with tags partner_id and entry_type in topUp and reverse; Create PrefundingMetricsCollector.java @Component at com/gme/pay/prefunding/metrics/PrefundingMetricsCollector.java with @Scheduled(fixedDelay=30000) reading all OVERSEAS balances and updating MultiGauge or individual Gauges; Add management.endpoints.web.exposure.include=health,prometheus to application.yml
**Deliverable:** services/prefunding-balance/src/main/java/com/gme/pay/prefunding/metrics/PrefundingMetricsCollector.java
**Acceptance / logic checks:**
- GET /actuator/prometheus includes prefunding_deduction_count_total with result and partner_id labels
- GET /actuator/prometheus includes prefunding_balance_usd gauge with partner_id label
- Counter incremented on both success and insufficient deduction with correct result tag
- PrefundingMetricsCollector @Scheduled method has fixedDelay=30000
- ./gradlew :svc-prefunding:compileJava succeeds
**Depends on:** 3.5-T08, 3.5-T09, 3.5-T15

### 3.5-T18 — Unit tests: AtomicPrefundingDeductService — deduction logic and invariants  _(45 min)_
**Context:** Module: :svc-prefunding. Test class using JUnit 5 + Testcontainers Postgres 16 (@SpringBootTest). Test vectors: (A) balance=1000.00, deduct=36.9714 -> newBalance=963.0286, DEBIT_PAYMENT ledger row with balance_after=963.0286. (B) balance=100.00, deduct=100.0001 -> InsufficientPrefundingException; balance unchanged; no ledger row inserted. (C) deduct exactly to threshold: balance=10000.00, deduct=10000.00, low_balance_threshold=10000.00 -> newBalance=0.00, OutboxEvent with event_type='prefunding.low_balance' and payload containing partnerId and currentBalanceUsd=0.00. (D) deduct below threshold but not to zero: balance=500.00, deduct=100.00, threshold=10000.00 -> OutboxEvent inserted (500.00 < 10000.00 threshold).
**Steps:** Create AtomicPrefundingDeductServiceTest.java at services/prefunding-balance/src/test/java/com/gme/pay/prefunding/service/AtomicPrefundingDeductServiceTest.java; Use @SpringBootTest + @Testcontainers + PostgreSQLContainer('postgres:16'); Seed prefunding_account and low_balance_alert_config rows in @BeforeEach; Implement test A, B, C, D as described in context; Assert exact BigDecimal values using compareTo(BigDecimal.ZERO)==0 style assertions
**Deliverable:** services/prefunding-balance/src/test/java/com/gme/pay/prefunding/service/AtomicPrefundingDeductServiceTest.java
**Acceptance / logic checks:**
- Test A: balance_after in DB equals 963.0286 (exact BigDecimal)
- Test B: InsufficientPrefundingException thrown; prefunding_ledger_entry row count unchanged
- Test C: outbox_event row has event_type='prefunding.low_balance' and payload.currentBalanceUsd=0.00
- Test D: OutboxEvent inserted when post-deduction balance 400.00 < threshold 10000.00
- All 4 tests pass with Testcontainers Postgres 16
**Depends on:** 3.5-T08, 3.5-T11

### 3.5-T19 — Unit tests: PrefundingCreditService — top-up and reversal logic  _(40 min)_
**Context:** Module: :svc-prefunding. Test class using JUnit 5 + Testcontainers Postgres 16. Test vectors: (A) topUp: balance=500.00, credit=1000.00 -> newBalance=1500.00, CREDIT_TOPUP ledger row with balance_after=1500.00. (B) reverse: start balance=500.00, deduct 36.97 (balance=463.03), then reverse txnRef -> newBalance=500.00, CREDIT_REVERSAL row referencing original txnRef with balance_after=500.00. (C) duplicate reversal: call reverse same txnRef twice -> second call throws DuplicateReversalException; balance remains 500.00 after second call. (D) topUp does NOT insert OutboxEvent (only deductions trigger low-balance check).
**Steps:** Create PrefundingCreditServiceTest.java at services/prefunding-balance/src/test/java/com/gme/pay/prefunding/service/PrefundingCreditServiceTest.java; Use @SpringBootTest + Testcontainers Postgres 16; Seed prefunding_account row in @BeforeEach; Implement tests A, B, C, D as specified; Verify ledger rows via PrefundingLedgerEntryRepository queries
**Deliverable:** services/prefunding-balance/src/test/java/com/gme/pay/prefunding/service/PrefundingCreditServiceTest.java
**Acceptance / logic checks:**
- Test A: balance=1500.0000 and CREDIT_TOPUP ledger row present with balance_after=1500.0000
- Test B: balance restored to 500.0000 and CREDIT_REVERSAL row txn_ref matches original
- Test C: DuplicateReversalException on second reverse; balance remains 500.0000
- Test D: outbox_event count=0 after topUp
- All 4 tests pass
**Depends on:** 3.5-T09

### 3.5-T20 — Unit tests: PrefundingBalanceQueryService — balance query and LOCAL partner rejection  _(35 min)_
**Context:** Module: :svc-prefunding. Test class using JUnit 5 + Testcontainers Postgres 16. Test vectors: (A) getBalance for OVERSEAS partner with balance=5000.00 returns BigDecimal(5000.0000). (B) getLedgerHistory: 5 ledger entries inserted, request page 0 size 3 returns 3 entries in descending created_at order; page 1 size 3 returns 2 entries with hasNext=false. (C) LOCAL partner type call to getBalance throws PartnerTypeNotEligibleException. (D) unknown partnerId throws AccountNotFoundException.
**Steps:** Create PrefundingBalanceQueryServiceTest.java at services/prefunding-balance/src/test/java/com/gme/pay/prefunding/service/PrefundingBalanceQueryServiceTest.java; Use @SpringBootTest + Testcontainers Postgres 16; Seed OVERSEAS and LOCAL partner rows plus prefunding_account and 5 ledger entries in @BeforeEach; Implement 4 test cases as specified
**Deliverable:** services/prefunding-balance/src/test/java/com/gme/pay/prefunding/service/PrefundingBalanceQueryServiceTest.java
**Acceptance / logic checks:**
- Test A: returned BigDecimal equals 5000.0000 (BigDecimal.compareTo)
- Test B: page 0 has 3 entries; page 1 has 2 entries; entries are in DESC created_at order
- Test C: PartnerTypeNotEligibleException thrown for LOCAL partner
- Test D: AccountNotFoundException thrown for unknown partnerId
- All 4 tests pass
**Depends on:** 3.5-T10

### 3.5-T21 — Unit tests: PrefundingInternalController — deduct, credit, balance endpoints  _(35 min)_
**Context:** Module: :svc-prefunding. @WebMvcTest slice with Mockito. Test vectors: (A) POST /internal/v1/prefunding/deduct valid body -> 200 with newBalance. (B) POST /internal/v1/prefunding/deduct -> service throws InsufficientPrefundingException -> HTTP 402 body error code INSUFFICIENT_PREFUNDING. (C) POST /internal/v1/prefunding/credit creditType=CREDIT_REVERSAL -> 200. (D) GET /internal/v1/prefunding/42/balance -> 200 jsonPath $.balance='5000.0000' $.currency='USD'. (E) Request without ROLE_INTERNAL_SERVICE -> 403.
**Steps:** Create PrefundingInternalControllerTest.java at services/prefunding-balance/src/test/java/com/gme/pay/prefunding/api/internal/PrefundingInternalControllerTest.java; Use @WebMvcTest(PrefundingInternalController.class) + @MockBean for service layer; Set up Spring Security test context with ROLE_INTERNAL_SERVICE for happy-path tests; Implement 5 test cases using MockMvc and jsonPath assertions; No real DB or Testcontainers — pure MockMvc with mocked services
**Deliverable:** services/prefunding-balance/src/test/java/com/gme/pay/prefunding/api/internal/PrefundingInternalControllerTest.java
**Acceptance / logic checks:**
- Test B returns HTTP 402 and jsonPath $.errorCode == 'INSUFFICIENT_PREFUNDING'
- Test D: jsonPath $.currency == 'USD' and $.balance is a JSON string '5000.0000'
- Test E returns HTTP 403
- All 5 tests pass with @WebMvcTest slice
- No Testcontainers required in this test class
**Depends on:** 3.5-T13

### 3.5-T22 — Unit tests: PrefundingPartnerController — GET /v1/balance partner-facing endpoint  _(30 min)_
**Context:** Module: :svc-prefunding. @WebMvcTest slice with Mockito. Test vectors: (A) Valid OVERSEAS JWT -> 200, {balance:'5000.0000', currency:'USD', lowBalanceThreshold:'10000.0000'} (all as strings). (B) LOCAL partner JWT -> 403, error code PREFUNDING_NOT_APPLICABLE. (C) No Authorization header -> 401. (D) Expired JWT -> 401. Balance fields must be serialized as strings (not JSON numbers) to preserve DECIMAL(20,4) precision.
**Steps:** Create PrefundingPartnerControllerTest.java at services/prefunding-balance/src/test/java/com/gme/pay/prefunding/api/partner/PrefundingPartnerControllerTest.java; Use @WebMvcTest(PrefundingPartnerController.class) + @MockBean for PrefundingBalanceQueryService; Create test JWTs with partner_id claim and partner type using Spring Security test support; Implement 4 test cases using MockMvc and jsonPath assertions
**Deliverable:** services/prefunding-balance/src/test/java/com/gme/pay/prefunding/api/partner/PrefundingPartnerControllerTest.java
**Acceptance / logic checks:**
- Test A: jsonPath $.balance is string '5000.0000' (not a numeric literal)
- Test A: jsonPath $.lowBalanceThreshold is string '10000.0000'
- Test B: HTTP 403 with PREFUNDING_NOT_APPLICABLE error code
- Tests C and D return HTTP 401
- All 4 tests pass
**Depends on:** 3.5-T14

### 3.5-T23 — Integration test: full deduction-then-reversal lifecycle with real Postgres  _(45 min)_
**Context:** Module: :svc-prefunding. End-to-end lifecycle test with Testcontainers Postgres 16 (no mocks for DB layer): (1) create account balance=1000.0000, (2) DEBIT_PAYMENT 36.9714 -> balance=963.0286, (3) simulate scheme failure then CREDIT_REVERSAL 36.9714 -> balance=1000.0000, (4) assert 2 ledger rows in order with correct balance_after chain: first row balance_after=963.0286, second row balance_after=1000.0000. Pool identity: DEBIT_PAYMENT.amount == CREDIT_REVERSAL.amount == 36.9714.
**Steps:** Create PrefundingLifecycleIntegrationTest.java at services/prefunding-balance/src/test/java/com/gme/pay/prefunding/PrefundingLifecycleIntegrationTest.java; Use @SpringBootTest + @Testcontainers + @Container PostgreSQLContainer('postgres:16'); Seed OVERSEAS partner and prefunding_account in @BeforeEach; Call AtomicPrefundingDeductService.deduct then PrefundingCreditService.reverse; Assert balance, ledger rows, and chain consistency
**Deliverable:** services/prefunding-balance/src/test/java/com/gme/pay/prefunding/PrefundingLifecycleIntegrationTest.java
**Acceptance / logic checks:**
- Post-deduction balance=963.0286 (BigDecimal.compareTo)
- Post-reversal balance=1000.0000
- Ledger has exactly 2 rows: DEBIT_PAYMENT then CREDIT_REVERSAL in created_at order
- Chain: row1.balance_after + row2.amount = row2.balance_after (963.0286 + 36.9714 = 1000.0000)
- No OutboxEvent inserted (threshold=10000, balance never drops below threshold in this path)
**Depends on:** 3.5-T08, 3.5-T09

### 3.5-T24 — Integration test: concurrent deductions — SELECT FOR UPDATE prevents overdraft  _(45 min)_
**Context:** Module: :svc-prefunding. Concurrency test with Testcontainers Postgres 16: two concurrent threads each attempt to deduct 600.00 USD from account balance=1000.00. The SELECT FOR UPDATE lock in AtomicPrefundingDeductService must serialize requests so exactly one succeeds (balance=400.00) and one throws InsufficientPrefundingException. Use CountDownLatch(1) to release both threads simultaneously. Final DB state must show balance=400.0000 and exactly 1 DEBIT_PAYMENT ledger row. Balance must never go negative.
**Steps:** Create ConcurrentDeductionTest.java at services/prefunding-balance/src/test/java/com/gme/pay/prefunding/ConcurrentDeductionTest.java; Use @SpringBootTest + Testcontainers Postgres 16; Create account with balance=1000.0000, low_balance_threshold=10000.00; Use CountDownLatch(1) + ExecutorService(2 threads) to fire deductions simultaneously; Assert final balance=400.0000, exactly 1 ledger row, 1 success + 1 exception
**Deliverable:** services/prefunding-balance/src/test/java/com/gme/pay/prefunding/ConcurrentDeductionTest.java
**Acceptance / logic checks:**
- Final balance in DB is exactly 400.0000
- Exactly 1 DEBIT_PAYMENT ledger entry exists
- Exactly 1 thread received InsufficientPrefundingException
- prefunding_account.balance is never negative at any point
- Test passes on 3 repeated runs without flakiness
**Depends on:** 3.5-T08


## WBS 6.1 — Prefunding balance & top-up recording
### 6.1-T01 — Migration: create prefunding_account table  _(25 min)_
**Context:** GMEPay+ Balance Service manages prepaid USD balances for OVERSEAS partners only. The prefunding_account table holds one row per OVERSEAS partner: id BIGINT PK, partner_id BIGINT FK->partner UNIQUE NOT NULL, currency CHAR(3) NOT NULL DEFAULT 'USD', balance DECIMAL(20,4) NOT NULL DEFAULT 0.0000 CHECK(balance >= 0), low_balance_threshold DECIMAL(20,4) NOT NULL DEFAULT 10000.0000, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL, created_by VARCHAR(120), updated_by VARCHAR(120). Balance updates must use SELECT FOR UPDATE; no application-level locking. LOCAL partners (partner_type='LOCAL') must never have a prefunding_account row.
**Steps:** Create Flyway (or Liquibase) migration file V6_1_001__create_prefunding_account.sql; Add table DDL with all columns, types, and constraints exactly as specified; Add index on partner_id (already UNIQUE, confirm it exists); Add FK constraint partner_id -> partner(id); Add CHECK constraint that currency = 'USD'
**Deliverable:** Migration file V6_1_001__create_prefunding_account.sql that applies cleanly on a fresh schema
**Acceptance / logic checks:**
- Migration runs without error on empty schema and on schema with existing partner rows
- SELECT * FROM prefunding_account returns 0 rows after migration (table starts empty)
- Attempt to INSERT a row with balance = -0.01 is rejected by CHECK constraint
- Attempt to INSERT two rows with the same partner_id is rejected by UNIQUE constraint
- Column currency DEFAULT is 'USD' and CHECK rejects any other value

### 6.1-T02 — Migration: create prefunding_ledger_entry table  _(25 min)_
**Context:** prefunding_ledger_entry is an append-only, immutable ledger. Every balance change (debit or credit) produces one row. Schema: id BIGINT PK auto-increment, account_id BIGINT NOT NULL FK->prefunding_account, txn_ref VARCHAR(64) NULL (populated for DEBIT_PAYMENT/DEBIT_REVERSAL; NULL for CREDIT_TOPUP/CREDIT_REVERSAL), entry_type VARCHAR(20) NOT NULL CHECK IN ('DEBIT_PAYMENT','DEBIT_REVERSAL','CREDIT_TOPUP','CREDIT_REVERSAL'), amount DECIMAL(20,4) NOT NULL CHECK(amount > 0) -- direction is indicated by entry_type, NOT sign, balance_after DECIMAL(20,4) NOT NULL, note VARCHAR(255) NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), created_by VARCHAR(120) NULL. No updated_at column. Index on (account_id, created_at) for history queries.
**Steps:** Create migration file V6_1_002__create_prefunding_ledger_entry.sql; Add table DDL with all columns exactly as specified; Add composite index idx_ple_account_created ON prefunding_ledger_entry(account_id, created_at); Add FK constraint account_id -> prefunding_account(id); Add CHECK constraint on entry_type and CHECK(amount > 0)
**Deliverable:** Migration file V6_1_002__create_prefunding_ledger_entry.sql
**Acceptance / logic checks:**
- Migration applies cleanly after V6_1_001
- INSERT with amount = 0 is rejected by CHECK constraint
- INSERT with amount = -5.00 is rejected by CHECK constraint
- INSERT with entry_type = 'UNKNOWN' is rejected by CHECK constraint
- Table has no updated_at column (immutability enforced at schema level)
- EXPLAIN on SELECT ... WHERE account_id=1 ORDER BY created_at uses the composite index
**Depends on:** 6.1-T01

### 6.1-T03 — Migration: create low_balance_alert_config table  _(20 min)_
**Context:** low_balance_alert_config stores per-partner alert settings. Schema: id BIGINT PK, partner_id BIGINT FK->partner UNIQUE NOT NULL, threshold_usd DECIMAL(20,4) NOT NULL DEFAULT 10000.0000, alert_email VARCHAR(255) NOT NULL, is_active BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL, created_by VARCHAR(120), updated_by VARCHAR(120). One row per partner. A partner may have at most one active alert config. The threshold defaults to 10000.00 (USD 10,000). This table is separate from prefunding_account.low_balance_threshold to allow the alert email to be managed independently.
**Steps:** Create migration file V6_1_003__create_low_balance_alert_config.sql; Add table DDL with all columns and constraints; Add UNIQUE constraint on partner_id; Add CHECK constraint threshold_usd > 0
**Deliverable:** Migration file V6_1_003__create_low_balance_alert_config.sql
**Acceptance / logic checks:**
- Migration applies cleanly after prior two migrations
- UNIQUE constraint prevents two rows with the same partner_id
- CHECK rejects threshold_usd = 0 or negative
- Default threshold_usd = 10000.0000 is applied when not supplied
- Default is_active = TRUE is applied when not supplied
**Depends on:** 6.1-T01

### 6.1-T04 — JPA entity: PrefundingAccount  _(30 min)_
**Context:** The Balance Service is a Spring Boot / Java service using JPA (Hibernate) + PostgreSQL. Map the prefunding_account table to a JPA entity. Fields: id (Long), partner (ManyToOne lazy to Partner entity, UNIQUE), currency (String, always USD), balance (BigDecimal scale 4), lowBalanceThreshold (BigDecimal scale 4), createdAt, updatedAt, createdBy, updatedBy. Use @Version for optimistic locking on the balance field as a secondary guard (primary guard is SELECT FOR UPDATE at the service layer). The entity must use DECIMAL arithmetic; never use double or float for monetary fields.
**Steps:** Create class PrefundingAccount.java in the balance service domain package; Annotate with @Entity, @Table(name='prefunding_account'); Map all columns using @Column with correct precision/scale (DECIMAL(20,4) maps to precision=20, scale=4); Add @Version Long version field for optimistic locking; Add @OneToOne(optional=false) or @ManyToOne to Partner entity; Add standard audit fields with @CreationTimestamp / @UpdateTimestamp
**Deliverable:** PrefundingAccount.java JPA entity
**Acceptance / logic checks:**
- Entity round-trips through save/findById in a @DataJpaTest with an in-memory or Testcontainers Postgres without data loss
- balance field retains 4 decimal places (e.g. 50000.1234 stored and retrieved exactly)
- Inserting two PrefundingAccount rows with the same partner_id throws a constraint violation
- Field types are BigDecimal, never double or float
- @Version field increments on each update, confirming optimistic locking is active
**Depends on:** 6.1-T01

### 6.1-T05 — JPA entity: PrefundingLedgerEntry  _(25 min)_
**Context:** Map the prefunding_ledger_entry table to an immutable JPA entity. Fields: id (Long), account (ManyToOne to PrefundingAccount, non-null), txnRef (String nullable, maps to txn_ref), entryType (enum: DEBIT_PAYMENT, DEBIT_REVERSAL, CREDIT_TOPUP, CREDIT_REVERSAL stored as VARCHAR(20)), amount (BigDecimal scale 4, always positive), balanceAfter (BigDecimal scale 4), note (String nullable), createdAt (Instant, @CreationTimestamp), createdBy (String nullable). No updatedAt. The entity must be effectively immutable after creation; do not expose setters for any field except via the constructor. Define a factory static method or constructor that validates amount > 0.
**Steps:** Create PrefundingLedgerEntry.java entity in domain package; Map all columns; use @Enumerated(EnumType.STRING) for entryType; Add all-args constructor and no-args protected constructor (JPA requirement); Add factory method PrefundingLedgerEntry.create(...) that asserts amount.compareTo(BigDecimal.ZERO) > 0; Ensure no setter methods are public (entity is append-only)
**Deliverable:** PrefundingLedgerEntry.java JPA entity
**Acceptance / logic checks:**
- Calling create() with amount=0 throws IllegalArgumentException
- Calling create() with amount=-1.00 throws IllegalArgumentException
- Entity can be persisted via JPA and retrieved with all fields intact
- entryType is stored as the string 'CREDIT_TOPUP' not an ordinal integer
- No public setter exists for id, amount, balanceAfter, entryType, or createdAt
**Depends on:** 6.1-T02, 6.1-T04

### 6.1-T06 — JPA entity: LowBalanceAlertConfig  _(20 min)_
**Context:** Map the low_balance_alert_config table to a JPA entity. Fields: id (Long), partner (ManyToOne to Partner, UNIQUE), thresholdUsd (BigDecimal scale 4, default 10000.0000), alertEmail (String, not null), isActive (Boolean, default true), standard audit fields. This entity is used by the alert-check logic in the balance service after each balance change.
**Steps:** Create LowBalanceAlertConfig.java entity in domain package; Map all columns with correct @Column annotations; Add validation annotation @Email on alertEmail field; Add @NotNull on alertEmail and partner fields; Add standard @CreationTimestamp / @UpdateTimestamp audit fields
**Deliverable:** LowBalanceAlertConfig.java JPA entity
**Acceptance / logic checks:**
- Entity persists and loads correctly in a @DataJpaTest
- alertEmail stored and retrieved exactly as entered
- thresholdUsd defaults to 10000.0000 when not set
- Two rows with the same partner_id cannot be inserted (constraint violation thrown)
- isActive defaults to true
**Depends on:** 6.1-T03

### 6.1-T07 — Repository interfaces for prefunding entities  _(25 min)_
**Context:** Create Spring Data JPA repository interfaces for the three prefunding entities. PrefundingAccountRepository must include: findByPartnerId(Long partnerId), findByPartnerIdWithLock (using @Lock(PESSIMISTIC_WRITE) + @QueryHints for SELECT FOR UPDATE), and findAll for dashboard listing. PrefundingLedgerEntryRepository must include: findByAccountIdOrderByCreatedAtDesc(Long accountId, Pageable pageable) for history paging. LowBalanceAlertConfigRepository must include: findByPartnerIdAndIsActiveTrue(Long partnerId).
**Steps:** Create PrefundingAccountRepository extending JpaRepository<PrefundingAccount, Long>; Add findByPartnerId and a @Lock(PESSIMISTIC_WRITE) variant named findByPartnerIdForUpdate; Create PrefundingLedgerEntryRepository with paged history query; Create LowBalanceAlertConfigRepository with active-config lookup; Add @Repository annotations and correct package placement
**Deliverable:** Three repository interfaces: PrefundingAccountRepository, PrefundingLedgerEntryRepository, LowBalanceAlertConfigRepository
**Acceptance / logic checks:**
- findByPartnerId returns empty Optional for unknown partner_id
- findByPartnerIdForUpdate acquires a row-level lock (verified via EXPLAIN in integration test or by observing lock wait in concurrent test)
- findByAccountIdOrderByCreatedAtDesc returns entries newest-first
- findByPartnerIdAndIsActiveTrue returns empty Optional when is_active=false
- All three interfaces load in Spring context without errors
**Depends on:** 6.1-T04, 6.1-T05, 6.1-T06

### 6.1-T08 — Service interface: PrefundingBalanceService  _(20 min)_
**Context:** Define the Java service interface (contract) for all prefunding balance operations. Methods needed: (1) PrefundingAccount createAccount(Long partnerId) -- creates a zero-balance account for an OVERSEAS partner; (2) PrefundingAccount getBalance(Long partnerId) -- reads current balance; (3) PrefundingAccount recordTopUp(TopUpCommand cmd) -- credits balance and writes CREDIT_TOPUP ledger entry; (4) PrefundingAccount deductBalance(DeductCommand cmd) -- atomically debits balance (SELECT FOR UPDATE) and writes DEBIT_PAYMENT entry; (5) Page<PrefundingLedgerEntry> getLedgerHistory(Long partnerId, Pageable pageable). Also define the command record/DTO classes TopUpCommand and DeductCommand with fields: partnerId, amountUsd, reference, note, actorId.
**Steps:** Create interface PrefundingBalanceService.java in the service package; Define all five method signatures with Javadoc; Create TopUpCommand record: partnerId (Long), amountUsd (BigDecimal), bankRef (String), valueDate (LocalDate), notes (String nullable), actorId (String); Create DeductCommand record: partnerId (Long), amountUsd (BigDecimal), txnRef (String), actorId (String); Ensure command records use BigDecimal, not double
**Deliverable:** PrefundingBalanceService.java interface plus TopUpCommand.java and DeductCommand.java record/DTO classes
**Acceptance / logic checks:**
- Interface compiles with zero warnings
- TopUpCommand and DeductCommand are immutable (final fields or records)
- All monetary fields in commands are BigDecimal
- Interface Javadoc describes the SELECT FOR UPDATE requirement for deductBalance
- No implementation code in this ticket -- interface only
**Depends on:** 6.1-T04, 6.1-T05

### 6.1-T09 — Implement createAccount: initialise zero-balance prefunding account  _(30 min)_
**Context:** PrefundingBalanceServiceImpl.createAccount(Long partnerId) must: (1) verify the partner exists and has partner_type='OVERSEAS' -- throw PartnerNotFoundException or InvalidPartnerTypeException otherwise; (2) check no prefunding_account already exists for this partner -- throw DuplicateAccountException if it does; (3) create a PrefundingAccount with balance=0.0000, currency='USD', low_balance_threshold=10000.0000; (4) persist and return. This is called once at partner onboarding. Partner entity has fields id, partnerCode, partnerType (enum LOCAL|OVERSEAS).
**Steps:** Implement createAccount in PrefundingBalanceServiceImpl; Add partner type guard: throw InvalidPartnerTypeException('Partner {partnerCode} is LOCAL; prefunding accounts are OVERSEAS only') if type != OVERSEAS; Add duplicate guard: if findByPartnerId returns non-empty, throw DuplicateAccountException; Set balance=BigDecimal.ZERO scaled to 4 decimal places, currency='USD', lowBalanceThreshold=10000.0000; Save and return the persisted entity
**Deliverable:** PrefundingBalanceServiceImpl.createAccount() method
**Acceptance / logic checks:**
- Calling createAccount for a LOCAL partner throws InvalidPartnerTypeException
- Calling createAccount twice for the same OVERSEAS partner throws DuplicateAccountException on second call
- Returned account has balance=0.0000, currency='USD', lowBalanceThreshold=10000.0000
- Persisted row is retrievable via findByPartnerId after creation
- createAccount for a valid OVERSEAS partner writes exactly one row to prefunding_account
**Depends on:** 6.1-T07, 6.1-T08

### 6.1-T10 — Implement getBalance: read current prefunding balance  _(20 min)_
**Context:** PrefundingBalanceServiceImpl.getBalance(Long partnerId) reads the current balance for an OVERSEAS partner. It must: (1) look up the prefunding_account for the given partnerId; (2) throw PrefundingAccountNotFoundException (with message 'No prefunding account for partner {id}') if not found; (3) return the PrefundingAccount entity (balance is the authoritative current value). This method does NOT use SELECT FOR UPDATE -- it is a read-only snapshot. Callers needing atomic read-modify-write must use deductBalance instead.
**Steps:** Implement getBalance in PrefundingBalanceServiceImpl using findByPartnerId; Throw PrefundingAccountNotFoundException if account is absent; Annotate method with @Transactional(readOnly=true); Return the PrefundingAccount entity directly; Add logging at DEBUG level: partner_id and balance returned
**Deliverable:** PrefundingBalanceServiceImpl.getBalance() method
**Acceptance / logic checks:**
- getBalance for unknown partner_id throws PrefundingAccountNotFoundException
- getBalance returns the current balance value without acquiring a write lock (verify via @Transactional(readOnly=true) annotation)
- getBalance for a partner with balance=12345.6789 returns exactly BigDecimal('12345.6789')
- Method is annotated @Transactional(readOnly=true)
- No INSERT or UPDATE SQL is executed during getBalance (verified via query count in test)
**Depends on:** 6.1-T09

### 6.1-T11 — Implement recordTopUp: credit balance and write CREDIT_TOPUP ledger entry  _(45 min)_
**Context:** PrefundingBalanceServiceImpl.recordTopUp(TopUpCommand cmd) is the core top-up operation. It must execute atomically in a single DB transaction: (1) acquire row lock via findByPartnerIdForUpdate; (2) validate amountUsd > 0 and bankRef is non-blank; (3) add amountUsd to balance: newBalance = account.balance + cmd.amountUsd; (4) update account.balance = newBalance, account.updatedBy = cmd.actorId; (5) create PrefundingLedgerEntry(entryType=CREDIT_TOPUP, amount=cmd.amountUsd, balanceAfter=newBalance, txnRef=null, note=cmd.notes, createdBy=cmd.actorId); (6) save both entities in the same transaction; (7) after commit, publish prefunding.balance_updated event (async, for alert check). Example: balance=30000.00 + topUp=20000.00 -> newBalance=50000.00; ledger entry amount=20000.00, balance_after=50000.00.
**Steps:** Implement recordTopUp annotated @Transactional(isolation=REPEATABLE_READ); Lock the account row using findByPartnerIdForUpdate; Validate cmd.amountUsd > 0 else throw InvalidAmountException; Validate cmd.bankRef is not blank else throw MissingReferenceException; Compute newBalance and update account.balance; Create and save PrefundingLedgerEntry with CREDIT_TOPUP; Publish prefunding.balance_updated ApplicationEvent after transaction (use @TransactionalEventListener)
**Deliverable:** PrefundingBalanceServiceImpl.recordTopUp() method
**Acceptance / logic checks:**
- recordTopUp(partnerId=1, amount=20000.00, bankRef='WIRE-001') with initial balance=30000.00 results in balance=50000.00
- A CREDIT_TOPUP ledger entry with amount=20000.00 and balance_after=50000.00 is persisted
- recordTopUp with amount=0 throws InvalidAmountException (no balance change, no ledger entry)
- recordTopUp with blank bankRef throws MissingReferenceException
- Concurrent calls from two threads with amounts 5000.00 and 3000.00 result in exactly one of {38000.00, 38000.00} -- never a lost update (run 10 iterations)
- Two ledger entries are written for two sequential top-ups; both have correct balance_after values
**Depends on:** 6.1-T08, 6.1-T09

### 6.1-T12 — Implement deductBalance: atomic SELECT FOR UPDATE debit with DEBIT_PAYMENT entry  _(45 min)_
**Context:** PrefundingBalanceServiceImpl.deductBalance(DeductCommand cmd) is called by the Transaction Orchestrator before each scheme call (AD-06 in SAD-02). Rules: (1) acquire row lock via findByPartnerIdForUpdate within @Transactional; (2) if account.balance < cmd.amountUsd throw InsufficientPrefundingException (error code INSUFFICIENT_PREFUNDING) -- scheme is NEVER called; (3) newBalance = account.balance - cmd.amountUsd; (4) update account.balance = newBalance; (5) create PrefundingLedgerEntry(entryType=DEBIT_PAYMENT, amount=cmd.amountUsd, balanceAfter=newBalance, txnRef=cmd.txnRef, createdBy=cmd.actorId); (6) save both; (7) publish prefunding.balance_updated event. GMEPay+ debits by collection_usd (the USD pool amount), not collection_amount. Example: balance=10000.00, deduct=1234.5678 -> newBalance=8765.4322; ledger entry amount=1234.5678, balance_after=8765.4322.
**Steps:** Implement deductBalance annotated @Transactional(isolation=REPEATABLE_READ); Lock account row using findByPartnerIdForUpdate; Check balance >= amountUsd; throw InsufficientPrefundingException with message matching 'INSUFFICIENT_PREFUNDING' if not; Compute newBalance = account.balance.subtract(cmd.amountUsd) -- use BigDecimal.subtract, never double; Persist updated account and DEBIT_PAYMENT ledger entry in same transaction; Publish balance_updated event after commit
**Deliverable:** PrefundingBalanceServiceImpl.deductBalance() method
**Acceptance / logic checks:**
- deductBalance(amount=1234.5678) from balance=10000.00 results in balance=8765.4322 and ledger entry amount=1234.5678, balance_after=8765.4322
- deductBalance(amount=10000.01) from balance=10000.00 throws InsufficientPrefundingException and balance remains 10000.00
- deductBalance(amount=10000.00) from balance=10000.00 succeeds with newBalance=0.00
- Two concurrent threads each deducting 6000.00 from a 10000.00 balance: exactly one succeeds and one throws InsufficientPrefundingException (run 20 iterations)
- txnRef on the ledger entry matches cmd.txnRef exactly
- No row is written to prefunding_ledger_entry if InsufficientPrefundingException is thrown
**Depends on:** 6.1-T08, 6.1-T09

### 6.1-T13 — Implement reverseDeduction: write DEBIT_REVERSAL ledger entry and restore balance  _(30 min)_
**Context:** When reconciliation confirms a previously UNCERTAIN transaction has FAILED, the Transaction Orchestrator calls reverseDeduction to restore the prefunding balance. Signature: reverseDeduction(ReverseDeductCommand cmd) where cmd has partnerId, amountUsd, originalTxnRef, actorId. Steps: (1) acquire row lock; (2) newBalance = account.balance + cmd.amountUsd; (3) update account; (4) create PrefundingLedgerEntry(entryType=DEBIT_REVERSAL, txnRef=cmd.originalTxnRef, amount=cmd.amountUsd, balanceAfter=newBalance, note='Reversal for UNCERTAIN resolution', createdBy=cmd.actorId). DEBIT_REVERSAL is a credit (restores balance) but entry_type name reflects its origin. The amount field stays positive per schema CHECK.
**Steps:** Add ReverseDeductCommand record to service layer; Implement reverseDeduction in PrefundingBalanceServiceImpl with @Transactional; Lock row, compute newBalance = balance + amountUsd; Persist updated account and DEBIT_REVERSAL ledger entry; Publish prefunding.balance_updated event post-commit; Add to PrefundingBalanceService interface
**Deliverable:** PrefundingBalanceServiceImpl.reverseDeduction() method and ReverseDeductCommand record
**Acceptance / logic checks:**
- reverseDeduction(amount=1000.00) on account with balance=5000.00 results in balance=6000.00
- DEBIT_REVERSAL entry has amount=1000.00 (positive), balance_after=6000.00, txnRef matching originalTxnRef
- reverseDeduction with unknown partnerId throws PrefundingAccountNotFoundException
- amount=0 in ReverseDeductCommand throws InvalidAmountException
- Ledger history for the account shows the reversal entry newest-first after subsequent getLedgerHistory call
**Depends on:** 6.1-T12

### 6.1-T14 — Implement getLedgerHistory: paged ledger entry retrieval  _(25 min)_
**Context:** PrefundingBalanceServiceImpl.getLedgerHistory(Long partnerId, Pageable pageable) returns the ledger history for a partner newest-first. Steps: (1) resolve account via findByPartnerId or throw PrefundingAccountNotFoundException; (2) delegate to PrefundingLedgerEntryRepository.findByAccountIdOrderByCreatedAtDesc(accountId, pageable); (3) return a Page<PrefundingLedgerEntry>. Typical page size for Admin Portal is 50 entries. The query uses the composite index (account_id, created_at) for efficiency.
**Steps:** Implement getLedgerHistory annotated @Transactional(readOnly=true); Resolve account_id from partnerId via findByPartnerId; Call ledger repository with provided Pageable; Return the Page result unchanged; Add method to PrefundingBalanceService interface
**Deliverable:** PrefundingBalanceServiceImpl.getLedgerHistory() method
**Acceptance / logic checks:**
- getLedgerHistory for unknown partnerId throws PrefundingAccountNotFoundException
- getLedgerHistory page 0 size 2 returns the 2 most recent entries in descending created_at order
- getTotalElements returns correct count across all pages
- getLedgerHistory returns an empty page (not null) for an account with no entries
- Query uses composite index (verified via EXPLAIN SELECT in integration test)
**Depends on:** 6.1-T10, 6.1-T11

### 6.1-T15 — Low-balance alert check: post-balance-change threshold evaluation  _(35 min)_
**Context:** After every successful balance change (deduct or top-up), the Balance Service must check whether an alert should be fired. Logic: (1) load LowBalanceAlertConfig where partner_id = X and is_active = true; (2) if not found, skip; (3) if account.balance < config.thresholdUsd AND balance was NOT below threshold before this change, publish event prefunding.low_balance (AlertType=LOW_BALANCE); (4) if account.balance <= 0, publish prefunding.low_balance (AlertType=CRITICAL_ZERO) regardless of previous state. Do NOT block the transaction for alert evaluation. Use @TransactionalEventListener(phase=AFTER_COMMIT) on a BalanceAlertChecker component that listens to the prefunding.balance_updated event emitted by recordTopUp and deductBalance.
**Steps:** Create BalanceAlertChecker @Component with @TransactionalEventListener(phase=AFTER_COMMIT) on BalanceUpdatedEvent; Receive BalanceUpdatedEvent(partnerId, newBalance, previousBalance) from recordTopUp/deductBalance; Load LowBalanceAlertConfig; if absent or isActive=false, return; Check newBalance <= 0 -> publish PrefundingAlertEvent(CRITICAL_ZERO); Check newBalance < thresholdUsd AND previousBalance >= thresholdUsd -> publish PrefundingAlertEvent(LOW_BALANCE); Log the alert event at WARN level with partner_id, newBalance, threshold
**Deliverable:** BalanceAlertChecker.java component and BalanceUpdatedEvent / PrefundingAlertEvent classes
**Acceptance / logic checks:**
- deductBalance that takes balance from 15000.00 to 9000.00 (threshold=10000.00) publishes exactly one LOW_BALANCE event
- deductBalance that takes balance from 9000.00 to 8000.00 (both below threshold=10000.00) does NOT publish LOW_BALANCE again
- deductBalance that takes balance to 0.00 publishes CRITICAL_ZERO
- recordTopUp that restores balance from 8000.00 to 15000.00 does NOT publish LOW_BALANCE
- Alert check failure (e.g. email config missing) does not roll back the balance transaction
**Depends on:** 6.1-T12, 6.1-T13, 6.1-T06

### 6.1-T16 — Email notification for low-balance and critical-zero alerts  _(30 min)_
**Context:** The NotificationService must listen to PrefundingAlertEvent and send an email to config.alertEmail. Email content per alert type: LOW_BALANCE: subject 'GMEPay+ Low Prefunding Balance Alert - {partnerName}', body includes partner name, current balance (formatted as USD X,XXX.XX), threshold, and suggested top-up = threshold * 2 - currentBalance. CRITICAL_ZERO: subject 'URGENT: GMEPay+ Prefunding Balance Exhausted - {partnerName}', body includes current balance = USD 0.00, all payments suspended, contact GME Finance immediately. Emails are sent via the platform EmailService bean (already exists). Phase 1: one alert_email per partner. Alert fires for both deductBalance and reverseDeduction events.
**Steps:** Create PrefundingAlertEmailListener @Component listening to PrefundingAlertEvent; Inject EmailService and LowBalanceAlertConfigRepository; For LOW_BALANCE: build subject and body with balance formatted to 2 decimal places, suggested top-up = threshold*2 - balance; For CRITICAL_ZERO: build urgent subject and body; Call emailService.send(to, subject, body); Log sent email at INFO level; catch and log exceptions without re-throwing (alert failure must not affect caller)
**Deliverable:** PrefundingAlertEmailListener.java component
**Acceptance / logic checks:**
- LOW_BALANCE event for SendMN (balance=9500.00, threshold=10000.00) triggers email to config.alertEmail with subject containing 'SendMN' and body showing current balance USD 9,500.00 and suggested top-up USD 10,500.00
- CRITICAL_ZERO event triggers email with URGENT in subject
- EmailService exception is swallowed and logged; no exception propagates to caller
- Alert email is NOT sent if LowBalanceAlertConfig.isActive = false
- LOW_BALANCE alert is sent exactly once per crossing (not on every subsequent deduction below threshold)
**Depends on:** 6.1-T15

### 6.1-T17 — Admin API endpoint: POST /internal/v1/prefunding/accounts  _(35 min)_
**Context:** The Balance Service exposes an internal REST API (not the partner-facing Northbound API) used by the Admin Portal and onboarding workflows. POST /internal/v1/prefunding/accounts creates a prefunding account for an OVERSEAS partner. Request body: { partnerId: Long }. Response 201: PrefundingAccountResponse { id, partnerId, currency, balance, lowBalanceThreshold, createdAt }. Error cases: 409 Conflict if account already exists, 422 if partner is LOCAL, 404 if partner not found. Only internal service accounts and Admin Portal backend-for-frontend may call this endpoint (enforced by service-mesh / API key check in a later security ticket; stub the auth check here).
**Steps:** Create PrefundingController @RestController at path /internal/v1/prefunding; Implement POST /accounts handler calling prefundingBalanceService.createAccount(cmd.partnerId); Map domain exceptions to HTTP status codes: PrefundingAccountNotFoundException->404, DuplicateAccountException->409, InvalidPartnerTypeException->422; Create PrefundingAccountResponse DTO with the listed fields; Add @Validated and @RequestBody validation (partnerId not null)
**Deliverable:** PrefundingController.java with POST /internal/v1/prefunding/accounts endpoint
**Acceptance / logic checks:**
- POST with valid OVERSEAS partnerId returns 201 and response body with balance=0.0000
- POST with same partnerId a second time returns 409 Conflict
- POST with LOCAL partnerId returns 422 with descriptive error message
- POST with unknown partnerId returns 404
- POST with null partnerId returns 400 Bad Request (validation)
**Depends on:** 6.1-T09

### 6.1-T18 — Admin API endpoint: GET /internal/v1/prefunding/accounts/{partnerId}  _(20 min)_
**Context:** GET /internal/v1/prefunding/accounts/{partnerId} returns the current prefunding account state for a partner. Response: PrefundingAccountResponse { id, partnerId, currency, balance (BigDecimal 4dp), lowBalanceThreshold, updatedAt }. 404 if no account. This endpoint is called by the Admin Portal Prefunding Dashboard to display each partner card. It is a non-locking read.
**Steps:** Add GET /accounts/{partnerId} handler to PrefundingController; Call prefundingBalanceService.getBalance(partnerId); Map PrefundingAccount to PrefundingAccountResponse DTO; Map PrefundingAccountNotFoundException to 404 with error body; Annotate method as read-only (no @Transactional needed at controller layer; service handles it)
**Deliverable:** GET /internal/v1/prefunding/accounts/{partnerId} handler in PrefundingController.java
**Acceptance / logic checks:**
- GET for existing account returns 200 with correct balance and currency=USD
- GET for unknown partnerId returns 404
- Response balance field has 4 decimal places in JSON (e.g. 50000.0000)
- Endpoint does not acquire DB write lock (verified by absence of FOR UPDATE in SQL log)
- Response includes updatedAt timestamp
**Depends on:** 6.1-T10, 6.1-T17

### 6.1-T19 — Admin API endpoint: POST /internal/v1/prefunding/accounts/{partnerId}/topups  _(35 min)_
**Context:** POST /internal/v1/prefunding/accounts/{partnerId}/topups records a manual Ops-entered top-up. Request body: { amountUsd: BigDecimal (> 0), bankRef: String (required, max 64 chars), valueDate: LocalDate (required, ISO date), notes: String (optional, max 255 chars) }. The actorId is extracted from the authenticated operator's JWT sub claim (stub: read from X-Actor-Id header in Phase 1). Response 201: TopUpResponse { ledgerEntryId, partnerId, amountUsd, newBalance, balanceAfter, entryType: CREDIT_TOPUP, bankRef, createdAt, createdBy }. Error cases: 400 if amountUsd <= 0 or bankRef blank, 404 if no account. The operation is atomic: balance update and ledger entry in one DB transaction.
**Steps:** Add POST /accounts/{partnerId}/topups handler to PrefundingController; Parse and validate request body: amountUsd > 0, bankRef not blank, valueDate not null; Extract actorId from X-Actor-Id header (Phase 1 stub); Build TopUpCommand and call prefundingBalanceService.recordTopUp(cmd); Map result to TopUpResponse DTO and return 201
**Deliverable:** POST /internal/v1/prefunding/accounts/{partnerId}/topups handler in PrefundingController.java
**Acceptance / logic checks:**
- POST with amountUsd=20000.00 and bankRef='WIRE-001' on account with balance=30000.00 returns 201 with newBalance=50000.00
- POST with amountUsd=0 returns 400 Bad Request
- POST with blank bankRef returns 400 Bad Request
- POST to unknown partnerId returns 404
- Response entryType is CREDIT_TOPUP
- Subsequent GET /accounts/{partnerId} returns balance=50000.00 immediately (no caching delay)
**Depends on:** 6.1-T11, 6.1-T18

### 6.1-T20 — Admin API endpoint: GET /internal/v1/prefunding/accounts/{partnerId}/ledger  _(25 min)_
**Context:** GET /internal/v1/prefunding/accounts/{partnerId}/ledger returns the paginated ledger history for a partner. Query params: page (default 0), size (default 50, max 200). Response: Page<LedgerEntryResponse> with entries newest-first. LedgerEntryResponse fields: id, accountId, entryType, amount, balanceAfter, txnRef (nullable), note (nullable), createdAt, createdBy. 404 if no account. This endpoint is used by the Admin Portal Prefunding Ledger view.
**Steps:** Add GET /accounts/{partnerId}/ledger handler to PrefundingController; Parse page and size query params; cap size at 200; Call prefundingBalanceService.getLedgerHistory(partnerId, PageRequest.of(page, size, Sort.by(DESC, 'createdAt'))); Map Page<PrefundingLedgerEntry> to PaginatedResponse<LedgerEntryResponse>; Return 200 with pagination metadata (totalElements, totalPages, currentPage)
**Deliverable:** GET /internal/v1/prefunding/accounts/{partnerId}/ledger handler in PrefundingController.java
**Acceptance / logic checks:**
- GET with page=0 size=2 for account with 5 entries returns 2 entries with totalElements=5
- Entries are in descending created_at order
- GET with size=300 is capped at 200 (returns 200 entries max)
- GET for unknown partnerId returns 404
- txnRef is null in the response for CREDIT_TOPUP entries and non-null for DEBIT_PAYMENT entries
**Depends on:** 6.1-T14, 6.1-T18

### 6.1-T21 — Admin API endpoint: GET /internal/v1/prefunding/accounts (dashboard summary)  _(35 min)_
**Context:** GET /internal/v1/prefunding/accounts returns a summary list of all OVERSEAS partner prefunding accounts for the Prefunding Dashboard. Each summary includes: partnerId, partnerCode, partnerName, currency, balance, lowBalanceThreshold, balancePct (balance / lowBalanceThreshold * 100, rounded to 1dp), balanceStatus (GREEN if >= 150%, AMBER if 100-149%, RED if < 100%), lastDeductionAt (nullable, from latest DEBIT_PAYMENT entry), lastTopUpAt (nullable, from latest CREDIT_TOPUP entry). No pagination -- typically < 20 OVERSEAS partners. Response is sorted by balancePct ASC (lowest first).
**Steps:** Add GET /accounts handler to PrefundingController; Query all PrefundingAccount rows via findAll(); For each account, compute balancePct and balanceStatus using the threshold rules; Fetch lastDeductionAt and lastTopUpAt via queries on ledger table (latest entry per type per account); Sort results by balancePct ASC and return 200
**Deliverable:** GET /internal/v1/prefunding/accounts dashboard handler
**Acceptance / logic checks:**
- Account with balance=15000.00, threshold=10000.00 has balancePct=150.0 and balanceStatus=GREEN
- Account with balance=12000.00, threshold=10000.00 has balancePct=120.0 and balanceStatus=AMBER
- Account with balance=9999.00, threshold=10000.00 has balancePct=99.9 and balanceStatus=RED
- Results are sorted with RED accounts appearing before AMBER and GREEN
- lastTopUpAt reflects the created_at of the most recent CREDIT_TOPUP entry
**Depends on:** 6.1-T14, 6.1-T17

### 6.1-T22 — Unit tests: recordTopUp core logic and balance arithmetic  _(40 min)_
**Context:** Write thorough unit tests for PrefundingBalanceServiceImpl.recordTopUp using Mockito (no DB). Test vectors: (A) balance=0 + topUp=50000.00 -> balance=50000.00, ledger amount=50000.00, balance_after=50000.00. (B) balance=30000.5678 + topUp=19999.4322 -> balance=50000.0000, ledger amount=19999.4322, balance_after=50000.0000 (exact BigDecimal arithmetic). (C) topUp=0 -> InvalidAmountException, no repo calls. (D) topUp=-1 -> InvalidAmountException. (E) blank bankRef -> MissingReferenceException. (F) unknown partnerId -> PrefundingAccountNotFoundException.
**Steps:** Create PrefundingBalanceServiceImplTopUpTest.java in test package; Mock PrefundingAccountRepository, PrefundingLedgerEntryRepository, ApplicationEventPublisher; Write test cases A through F as separate @Test methods; For cases A and B: use ArgumentCaptor to verify saved PrefundingAccount.balance and saved PrefundingLedgerEntry fields; Assert BalanceUpdatedEvent is published with correct newBalance and previousBalance for success cases
**Deliverable:** PrefundingBalanceServiceImplTopUpTest.java with >= 6 test cases all passing
**Acceptance / logic checks:**
- Test B confirms BigDecimal result 50000.0000 (not 50000.0000000001 from float drift)
- Test C verifies no call to accountRepository.save() when amount=0
- Test E verifies exception message contains 'bankRef'
- Test F verifies PrefundingAccountNotFoundException is thrown (not NullPointerException)
- All 6 tests pass with 'mvn test -pl balance-service -Dtest=PrefundingBalanceServiceImplTopUpTest'
**Depends on:** 6.1-T11

### 6.1-T23 — Unit tests: deductBalance atomicity, insufficient-balance, and edge cases  _(40 min)_
**Context:** Write unit tests for PrefundingBalanceServiceImpl.deductBalance. Test vectors: (A) balance=10000.00, deduct=1234.5678 -> balance=8765.4322, DEBIT_PAYMENT entry amount=1234.5678, balance_after=8765.4322. (B) balance=10000.00, deduct=10000.01 -> InsufficientPrefundingException, balance unchanged, no ledger entry. (C) balance=10000.00, deduct=10000.00 -> balance=0.0000 (exact zero is allowed). (D) deduct=0 -> InvalidAmountException. (E) deduct=-5 -> InvalidAmountException. (F) unknown partnerId -> PrefundingAccountNotFoundException. Verify txnRef on ledger entry matches cmd.txnRef.
**Steps:** Create PrefundingBalanceServiceImplDeductTest.java; Mock repositories and event publisher; Implement test vectors A through F; For test B: use verifyNoInteractions on ledger repository to confirm no entry is written; For test C: confirm returned balance is BigDecimal ZERO with scale 4; Verify BalanceUpdatedEvent is published only for successful deductions (not for exceptions)
**Deliverable:** PrefundingBalanceServiceImplDeductTest.java with >= 6 test cases all passing
**Acceptance / logic checks:**
- Test A: saved ledger entry has entryType=DEBIT_PAYMENT, amount=1234.5678, balance_after=8765.4322
- Test B: no ledger entry written AND no account save on InsufficientPrefundingException
- Test C: balance after deduction is 0.0000 (scale 4) not null
- Test D and E: InvalidAmountException thrown without touching repositories
- BalanceUpdatedEvent published exactly once for test A and test C; zero times for test B
**Depends on:** 6.1-T12

### 6.1-T24 — Unit tests: reverseDeduction logic  _(30 min)_
**Context:** Write unit tests for PrefundingBalanceServiceImpl.reverseDeduction. Vectors: (A) balance=5000.00, reverse=1000.00 -> balance=6000.00, DEBIT_REVERSAL entry amount=1000.00, balance_after=6000.00, txnRef=originalTxnRef. (B) balance=0.00, reverse=500.00 -> balance=500.00 (reversal can restore a zero balance). (C) reverse=0 -> InvalidAmountException. (D) unknown partnerId -> PrefundingAccountNotFoundException. DEBIT_REVERSAL is a credit to the balance, but the entry_type name reflects the origin (it reverses a DEBIT_PAYMENT).
**Steps:** Create PrefundingBalanceServiceImplReverseTest.java; Mock repositories and event publisher; Implement test vectors A through D; For test A: verify originalTxnRef is stored in ledger entry's txnRef field; For test B: confirm balance transitions correctly from 0.00 to 500.00; Verify BalanceUpdatedEvent published on success, not on exception
**Deliverable:** PrefundingBalanceServiceImplReverseTest.java with >= 4 test cases all passing
**Acceptance / logic checks:**
- Test A: DEBIT_REVERSAL ledger entry has txnRef='TXN-001', amount=1000.00, balance_after=6000.00
- Test B: balance transitions from 0.00 to 500.00 (verify BigDecimal equals zero then 500)
- Test C: InvalidAmountException thrown and no repo interactions
- Test D: PrefundingAccountNotFoundException thrown
- All 4 tests pass via mvn test
**Depends on:** 6.1-T13

### 6.1-T25 — Unit tests: low-balance alert check logic  _(30 min)_
**Context:** Write unit tests for BalanceAlertChecker. Vectors: (A) previousBalance=15000.00, newBalance=9500.00, threshold=10000.00 -> LOW_BALANCE event published. (B) previousBalance=9500.00, newBalance=8000.00, threshold=10000.00 -> NO event (both sides below threshold; do not spam). (C) newBalance=0.00 -> CRITICAL_ZERO event regardless of previous. (D) newBalance=9999.99, threshold=10000.00, isActive=false -> no event. (E) newBalance=15000.00, previousBalance=9000.00, threshold=10000.00 (balance restored above threshold) -> no event.
**Steps:** Create BalanceAlertCheckerTest.java; Mock LowBalanceAlertConfigRepository and ApplicationEventPublisher; Publish BalanceUpdatedEvent directly to checker (call handleBalanceUpdated method); Implement test vectors A through E; For test B: verifyNoInteractions on publisher; Verify event type (LOW_BALANCE vs CRITICAL_ZERO) in published event
**Deliverable:** BalanceAlertCheckerTest.java with >= 5 test cases all passing
**Acceptance / logic checks:**
- Test A publishes exactly one PrefundingAlertEvent with alertType=LOW_BALANCE
- Test B: ApplicationEventPublisher.publishEvent is never called
- Test C publishes alertType=CRITICAL_ZERO even when previousBalance > threshold
- Test D: no event when isActive=false
- Test E: no event when balance crosses threshold in the upward direction
**Depends on:** 6.1-T15

### 6.1-T26 — Integration test: top-up and ledger entry end-to-end  _(55 min)_
**Context:** Write a Spring Boot integration test using Testcontainers (PostgreSQL) covering the full recordTopUp flow. Test scenario: (1) create OVERSEAS partner and call createAccount; (2) call recordTopUp(amount=20000.00, bankRef='WIRE-001') twice; (3) assert prefunding_account.balance = 40000.00; (4) call getLedgerHistory(page 0, size 10): assert 2 entries, both CREDIT_TOPUP, amounts 20000.00 each, balance_after values 20000.00 and 40000.00 respectively, newest first. Also assert: (5) txnRef is NULL on both entries; (6) a third call with amount=0 returns 400 from the API endpoint; (7) balance remains 40000.00 after the failed call.
**Steps:** Create PrefundingTopUpIntegrationTest.java with @SpringBootTest + @Testcontainers; Spin up Postgres container, apply migrations via Flyway; Insert test OVERSEAS partner row; Call PrefundingController endpoints via MockMvc or TestRestTemplate; Assert DB state via direct JDBC or repository calls after each operation; Assert event publication using a test ApplicationEventListener stub
**Deliverable:** PrefundingTopUpIntegrationTest.java with the described scenario passing
**Acceptance / logic checks:**
- After two top-ups of 20000.00 each, DB balance = 40000.0000
- getLedgerHistory returns exactly 2 entries in descending order
- balance_after of first entry (oldest) = 20000.0000 and of second (newest) = 40000.0000
- POST with amount=0 returns 400 and balance is unchanged at 40000.0000
- Both ledger entries have txnRef = NULL and entryType = CREDIT_TOPUP
**Depends on:** 6.1-T19, 6.1-T20, 6.1-T22

### 6.1-T27 — Integration test: concurrent deduction race-condition test  _(55 min)_
**Context:** Write an integration test proving the SELECT FOR UPDATE prevents lost updates under concurrent deductions. Setup: OVERSEAS partner with balance=10000.00. Launch 10 threads each attempting deductBalance(amount=1500.00). Expected: exactly 6 threads succeed (6 * 1500 = 9000.00 <= 10000.00), the 7th fails with InsufficientPrefundingException (balance would be 10000.00 - 7*1500 = -500), and threads 8-10 also fail. Final balance = 10000.00 - (success_count * 1500). Verify ledger entry count equals success count. This test validates AD-06 (scheme is never called without prior successful deduction).
**Steps:** Create PrefundingConcurrencyTest.java with @SpringBootTest + Testcontainers Postgres; Create partner + account with balance=10000.00; Use ExecutorService with 10 threads each calling deductBalance(amount=1500.00); Collect results: count successes and InsufficientPrefundingException throws; Assert success_count + failure_count = 10 and success_count <= 6; Assert final DB balance = 10000.00 - (success_count * 1500.00) exactly; Assert ledger entry count = success_count
**Deliverable:** PrefundingConcurrencyTest.java passing
**Acceptance / logic checks:**
- Total thread count = successes + InsufficientPrefundingExceptions = 10 (no other exceptions)
- Final balance = 10000.00 - success_count * 1500.00 with zero floating-point error
- Ledger DEBIT_PAYMENT entries = success_count (never more)
- No balance goes negative (CHECK constraint never violated)
- Test passes on 3 consecutive runs (not intermittently flaky)
**Depends on:** 6.1-T12, 6.1-T26

### 6.1-T28 — Integration test: createAccount rejects LOCAL partner and duplicate creation  _(35 min)_
**Context:** Write integration tests for the createAccount guard conditions. Tests: (A) create account for LOCAL partner (e.g. GME_REMIT, partner_type='LOCAL') -> 422 response, no row in prefunding_account. (B) create account for OVERSEAS partner (SENDMN, partner_type='OVERSEAS') -> 201, row exists. (C) call createAccount again for SENDMN -> 409 Conflict, still exactly one row in prefunding_account. (D) call createAccount with non-existent partnerId 99999 -> 404. These guard tests verify the invariant that LOCAL partners never have prefunding accounts.
**Steps:** Create PrefundingAccountCreationTest.java with Testcontainers integration setup; Insert both LOCAL (GME_REMIT) and OVERSEAS (SENDMN) partner rows; Call POST /internal/v1/prefunding/accounts for each test case via MockMvc; Assert HTTP status and DB row count after each case; Run all 4 test cases in order
**Deliverable:** PrefundingAccountCreationTest.java with 4 passing test cases
**Acceptance / logic checks:**
- Test A returns 422 and SELECT COUNT(*) FROM prefunding_account WHERE partner_id={LOCAL_id} = 0
- Test B returns 201 and exactly one row exists with balance=0.0000
- Test C returns 409 and row count remains 1
- Test D returns 404
- Test D does not create any row in prefunding_account
**Depends on:** 6.1-T09, 6.1-T17

### 6.1-T29 — Validation: amountUsd scale and currency guards in service layer  _(25 min)_
**Context:** Add input validation guards to the service layer (not just the API layer) to enforce: (1) amountUsd must have scale <= 4 (DECIMAL(20,4) -- amounts with more than 4 decimal places are rejected); (2) amountUsd must be positive (> 0); (3) amountUsd must not exceed USD 10,000,000.00 per single top-up (max single top-up cap as a safety guard). These validations apply to both recordTopUp and deductBalance. Throw InvalidAmountException with a clear message for each violation. This prevents silent rounding and accidental over-crediting.
**Steps:** Add a private validateAmount(BigDecimal amount, String fieldName) helper in PrefundingBalanceServiceImpl; Check amount.compareTo(BigDecimal.ZERO) > 0 else throw InvalidAmountException('amount must be > 0'); Check amount.scale() <= 4 else throw InvalidAmountException('amount scale exceeds 4 decimal places'); Check amount.compareTo(new BigDecimal('10000000')) <= 0 else throw InvalidAmountException('amount exceeds single-operation cap of USD 10,000,000'); Call validateAmount at the start of both recordTopUp and deductBalance
**Deliverable:** validateAmount helper added to PrefundingBalanceServiceImpl; existing tests updated if needed
**Acceptance / logic checks:**
- recordTopUp with amount=1.00001 (scale 5) throws InvalidAmountException with 'scale' in message
- recordTopUp with amount=10000001.00 throws InvalidAmountException with 'cap' in message
- deductBalance with amount=0.00001 (scale 5) throws InvalidAmountException
- recordTopUp with amount=10000000.00 (exactly at cap) succeeds
- recordTopUp with amount=9999.9999 (scale 4) succeeds
**Depends on:** 6.1-T11, 6.1-T12

### 6.1-T30 — Audit log integration: emit audit events for top-up and account creation  _(30 min)_
**Context:** Per the platform audit spec, all config/balance changes must log actor, timestamp, previous value, and new value. Emit audit log entries (to the existing AuditLogService or AuditEvent publisher) for: (1) createAccount: action=PREFUNDING_ACCOUNT_CREATED, entity=PREFUNDING_ACCOUNT, entityId={accountId}, actorId, details={partnerId, currency, balance=0}; (2) recordTopUp: action=PREFUNDING_TOPUP_RECORDED, entityId={accountId}, actorId, details={previousBalance, newBalance, amountUsd, bankRef, valueDate}. The audit log is separate from the ledger; both are written in the same transaction where possible (or via @TransactionalEventListener for audit). Use the existing AuditLogService.record(AuditEntry entry) interface.
**Steps:** Inject AuditLogService into PrefundingBalanceServiceImpl; In createAccount: after persisting account, call auditLogService.record with action PREFUNDING_ACCOUNT_CREATED; In recordTopUp: call auditLogService.record with previousBalance and newBalance in details; Define AuditEntry details as a structured map or DTO, not a free-text string; Add unit tests asserting auditLogService.record is called with correct action and details for each operation
**Deliverable:** Audit log calls added to createAccount and recordTopUp in PrefundingBalanceServiceImpl
**Acceptance / logic checks:**
- createAccount calls auditLogService.record with action=PREFUNDING_ACCOUNT_CREATED and details containing partnerId
- recordTopUp calls auditLogService.record with previousBalance=30000.00 and newBalance=50000.00 when topping up 20000.00
- auditLogService.record is NOT called when recordTopUp throws InvalidAmountException
- Audit entry includes actorId from cmd.actorId
- In unit test, verify auditLogService.record called exactly once per successful operation
**Depends on:** 6.1-T11, 6.1-T09

### 6.1-T31 — OpenAPI spec: document prefunding balance and top-up endpoints  _(30 min)_
**Context:** Add OpenAPI 3.0 documentation for the four balance service endpoints: GET /internal/v1/prefunding/accounts, POST /internal/v1/prefunding/accounts, GET /internal/v1/prefunding/accounts/{partnerId}, POST /internal/v1/prefunding/accounts/{partnerId}/topups, and GET /internal/v1/prefunding/accounts/{partnerId}/ledger. Document request/response schemas, error responses (400, 404, 409, 422), and example values. Tag all endpoints with 'Prefunding'. Note in description that these are internal endpoints not exposed on the partner-facing Northbound API gateway.
**Steps:** Add @Operation, @ApiResponse, and @Schema annotations to PrefundingController methods; Document TopUpRequest schema with field constraints (amountUsd > 0, bankRef required max 64 chars); Document PrefundingAccountResponse and LedgerEntryResponse schemas with example values; Add 400/404/409/422 error response schemas using existing ErrorResponse component; Mark all endpoints with @Tag(name='Prefunding') and add 'Internal only' note in description
**Deliverable:** PrefundingController.java fully annotated with OpenAPI annotations; Swagger UI displays all 5 endpoints under Prefunding tag
**Acceptance / logic checks:**
- Swagger UI at /swagger-ui.html shows all 5 prefunding endpoints under Prefunding tag
- POST /topups request schema shows amountUsd as required with description '>0, max scale 4'
- All 4xx error responses are documented on POST /topups
- LedgerEntryResponse schema includes entryType enum values DEBIT_PAYMENT, DEBIT_REVERSAL, CREDIT_TOPUP, CREDIT_REVERSAL
- Internal-only note appears in endpoint descriptions
**Depends on:** 6.1-T19, 6.1-T20, 6.1-T21


## WBS 6.2 — Atomic deduction (SELECT FOR UPDATE)
### 6.2-T01 — Add Flyway migration: prefunding_account table  _(25 min)_
**Context:** GMEPay+ maintains a prefunding_account table (one row per OVERSEAS partner) in PostgreSQL. Columns: id BIGINT PK, partner_id BIGINT UNIQUE FK partner, currency CHAR(3) DEFAULT 'USD', balance DECIMAL(20,4) NOT NULL DEFAULT 0, low_balance_threshold DECIMAL(20,4) NOT NULL DEFAULT 10000.0000, created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ, created_by VARCHAR(120), updated_by VARCHAR(120). Balance updates use SELECT FOR UPDATE exclusively - no application-level locking. LOCAL partners never have a row here.
**Steps:** Create Flyway migration file V6_2_001__create_prefunding_account.sql; Add table DDL with all columns and constraints listed in context; Add UNIQUE constraint on partner_id; Add CHECK constraint: balance >= 0; Add CHECK constraint: low_balance_threshold >= 0; Verify migration runs cleanly with mvn flyway:migrate in local dev environment
**Deliverable:** Flyway migration file V6_2_001__create_prefunding_account.sql creating the prefunding_account table
**Acceptance / logic checks:**
- Migration runs without error on a clean schema and is idempotent when run twice (Flyway checksum passes)
- Table has UNIQUE constraint on partner_id - inserting two rows with same partner_id raises a unique violation
- CHECK (balance >= 0) prevents inserting a row with balance = -0.01
- CHECK (low_balance_threshold >= 0) is present
- Default balance is 0.0000 and default low_balance_threshold is 10000.0000 when values are omitted on INSERT

### 6.2-T02 — Add Flyway migration: prefunding_ledger_entry table  _(25 min)_
**Context:** Every debit or credit to a prefunding_account creates one immutable, append-only row in prefunding_ledger_entry. Columns: id BIGINT PK auto-increment, account_id BIGINT NOT NULL FK prefunding_account, txn_ref VARCHAR(64) NULL (FK ref to transaction.txn_ref; NULL for top-ups), entry_type VARCHAR(20) NOT NULL CHECK IN ('DEBIT_PAYMENT','DEBIT_REVERSAL','CREDIT_TOPUP','CREDIT_REVERSAL'), amount DECIMAL(20,4) NOT NULL CHECK amount > 0, balance_after DECIMAL(20,4) NOT NULL (running balance snapshot after entry), note VARCHAR(255) NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), created_by VARCHAR(120). Rows are NEVER updated or deleted. Index on (account_id, created_at) for balance history queries.
**Steps:** Create migration V6_2_002__create_prefunding_ledger_entry.sql; Add table DDL with all columns and constraints; Add FK to prefunding_account(id); Add CHECK constraint: entry_type IN ('DEBIT_PAYMENT','DEBIT_REVERSAL','CREDIT_TOPUP','CREDIT_REVERSAL'); Add CHECK constraint: amount > 0; Add composite index on (account_id, created_at)
**Deliverable:** Flyway migration file V6_2_002__create_prefunding_ledger_entry.sql
**Acceptance / logic checks:**
- Migration applies cleanly after T01 migration
- CHECK (amount > 0) rejects INSERT with amount = 0 or amount = -5.00
- CHECK (entry_type) rejects unknown value 'DEBIT_UNKNOWN'
- Composite index (account_id, created_at) exists in pg_indexes
- No UPDATE or DELETE grant is given on the table in the migration (table is append-only by convention)
**Depends on:** 6.2-T01

### 6.2-T03 — Add Flyway migration: low_balance_alert_config table  _(20 min)_
**Context:** Per-partner configurable low-balance alert settings live in low_balance_alert_config. Columns: id BIGINT PK, partner_id BIGINT UNIQUE FK partner, threshold_usd DECIMAL(20,4) NOT NULL DEFAULT 10000.0000, alert_email VARCHAR(255) NOT NULL, is_active BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ. One row per OVERSEAS partner. Alert is informational only - the triggering transaction is NOT blocked. Default threshold is USD 10,000 per partner.
**Steps:** Create migration V6_2_003__create_low_balance_alert_config.sql; Add table DDL with all columns; Add UNIQUE constraint on partner_id; Add CHECK constraint: threshold_usd > 0; Seed a default row for each existing OVERSEAS partner by joining partner table on partner_type = 'OVERSEAS'
**Deliverable:** Flyway migration V6_2_003__create_low_balance_alert_config.sql
**Acceptance / logic checks:**
- Table created with UNIQUE constraint on partner_id
- CHECK (threshold_usd > 0) rejects threshold_usd = 0
- Default is_active = TRUE when omitted on INSERT
- Existing OVERSEAS partners each get a seeded row with threshold_usd = 10000.0000
- Inserting a duplicate partner_id raises a unique violation
**Depends on:** 6.2-T01

### 6.2-T04 — Add prefunding_deducted_usd column to transactions table  _(20 min)_
**Context:** The transactions table must record how much USD was deducted from a partner's prefunding account per payment. Column: prefunding_deducted_usd DECIMAL(20,4) NULL (NULL for LOCAL partners or before deduction; set to collection_usd at commit for OVERSEAS partners). A companion boolean column is_same_ccy_shortcircuit BOOLEAN NOT NULL DEFAULT FALSE also needs to exist (add only if not already present from an earlier WBS migration).
**Steps:** Check whether prefunding_deducted_usd and is_same_ccy_shortcircuit already exist in the transactions DDL from WBS 5.x; Create migration V6_2_004__add_prefunding_deducted_usd_to_transactions.sql; Add column prefunding_deducted_usd DECIMAL(20,4) NULL to transactions table; Add is_same_ccy_shortcircuit BOOLEAN NOT NULL DEFAULT FALSE to transactions table if not already present; Add a partial index on (partner_id, created_at) WHERE prefunding_deducted_usd IS NOT NULL for reporting queries
**Deliverable:** Flyway migration V6_2_004__add_prefunding_deducted_usd_to_transactions.sql
**Acceptance / logic checks:**
- Column prefunding_deducted_usd is NULLable - existing rows are unaffected
- Column defaults to NULL on INSERT when omitted
- Partial index (partner_id, created_at) WHERE prefunding_deducted_usd IS NOT NULL exists after migration
- Migration is idempotent via Flyway checksum: running it twice does not error
- is_same_ccy_shortcircuit column is present and defaults to FALSE
**Depends on:** 6.2-T01

### 6.2-T05 — Define PrefundingAccount and PrefundingLedgerEntry JPA entities  _(30 min)_
**Context:** The Hub Core is Java 21 + Spring Boot 3.x with PostgreSQL 16. PrefundingAccount maps to prefunding_account (id, partner_id, currency, balance DECIMAL(20,4), lowBalanceThreshold DECIMAL(20,4)). PrefundingLedgerEntry maps to prefunding_ledger_entry (id, accountId, txnRef, entryType enum[DEBIT_PAYMENT,DEBIT_REVERSAL,CREDIT_TOPUP,CREDIT_REVERSAL], amount, balanceAfter, note, createdAt). Use BigDecimal for all monetary fields - never double or float. PrefundingLedgerEntry must be annotated as immutable (no setter methods; all fields set via constructor or builder). Place entities in package com.gmepayplus.prefunding.domain.
**Steps:** Create PrefundingAccount.java with @Entity, @Table(name='prefunding_account'), all columns mapped; Create EntryType.java enum with four values; Create PrefundingLedgerEntry.java with @Entity; no setter for any field; @Column(updatable=false) on all columns; Use BigDecimal for balance, amount, balanceAfter, lowBalanceThreshold; Add @Version on PrefundingAccount for optimistic-lock detection (belt-and-suspenders alongside SELECT FOR UPDATE)
**Deliverable:** PrefundingAccount.java, EntryType.java, PrefundingLedgerEntry.java in com.gmepayplus.prefunding.domain
**Acceptance / logic checks:**
- PrefundingAccount.balance field is BigDecimal, not double
- PrefundingLedgerEntry has no public setters; all fields annotated @Column(updatable=false, insertable=true)
- EntryType enum has exactly four values: DEBIT_PAYMENT, DEBIT_REVERSAL, CREDIT_TOPUP, CREDIT_REVERSAL
- mvn compile succeeds with no warnings on these classes
- @Version field is present on PrefundingAccount
**Depends on:** 6.2-T01, 6.2-T02

### 6.2-T06 — Implement PrefundingRepository with SELECT FOR UPDATE query  _(25 min)_
**Context:** The atomic deduction pattern mandates SELECT FOR UPDATE (row-level lock) on the prefunding_account row. Pseudo-SQL from NFR-10 §5.2: BEGIN; SELECT balance FROM prefunding_account WHERE partner_id = ? FOR UPDATE; check balance >= amount; UPDATE prefunding_account SET balance = balance - amount WHERE partner_id = ?; COMMIT. Spring Data JPA: use @Lock(LockModeType.PESSIMISTIC_WRITE) on a findByPartnerId method. This acquires a row-level exclusive lock preventing concurrent deductions from over-drawing. Repository interface: PrefundingAccountRepository extends JpaRepository.
**Steps:** Create PrefundingAccountRepository.java extending JpaRepository<PrefundingAccount, Long>; Add method: @Lock(LockModeType.PESSIMISTIC_WRITE) @Query('SELECT p FROM PrefundingAccount p WHERE p.partnerId = :partnerId') Optional<PrefundingAccount> findByPartnerIdForUpdate(@Param('partnerId') Long partnerId); Add plain findByPartnerId(Long partnerId) for read-only balance queries; Create PrefundingLedgerEntryRepository.java extending JpaRepository<PrefundingLedgerEntry, Long>; Add findTop10ByAccountIdOrderByCreatedAtDesc for recent-history queries
**Deliverable:** PrefundingAccountRepository.java and PrefundingLedgerEntryRepository.java
**Acceptance / logic checks:**
- findByPartnerIdForUpdate is annotated @Lock(LockModeType.PESSIMISTIC_WRITE)
- findByPartnerId (without lock) exists and is separate from the locking variant
- PrefundingLedgerEntryRepository.findTop10ByAccountIdOrderByCreatedAtDesc returns at most 10 rows
- Both repositories extend JpaRepository and compile cleanly
- No raw SQL strings are used for the UPDATE; balance update is done via entity mutation + save()
**Depends on:** 6.2-T05

### 6.2-T07 — Implement PrefundingDeductionService - core atomic deduct method  _(45 min)_
**Context:** PrefundingDeductionService.deductForPayment(Long partnerId, BigDecimal amountUsd, String txnRef) is the core atomic operation. Pattern (NFR-10 §5.2): (1) Acquire row lock via findByPartnerIdForUpdate inside @Transactional. (2) Check balance >= amountUsd; if not, throw InsufficientPrefundingException (maps to HTTP 402, error code INSUFFICIENT_PREFUNDING). (3) Subtract: account.setBalance(account.getBalance().subtract(amountUsd)). (4) Save account. (5) Append an immutable PrefundingLedgerEntry(entryType=DEBIT_PAYMENT, amount=amountUsd, balanceAfter=newBalance, txnRef=txnRef). (6) Return DeductionResult(previousBalance, newBalance, entryId). The entire operation runs in a single DB transaction. Scheme is NEVER called by this service.
**Steps:** Create InsufficientPrefundingException.java extending RuntimeException with fields partnerId, requiredUsd, availableUsd; Create DeductionResult.java record with fields previousBalance, newBalance, ledgerEntryId; Create PrefundingDeductionService.java with @Service; Implement deductForPayment annotated @Transactional(isolation=REPEATABLE_READ) calling findByPartnerIdForUpdate; Throw InsufficientPrefundingException when balance < amountUsd before deduction; Persist ledger entry and return DeductionResult
**Deliverable:** PrefundingDeductionService.java, InsufficientPrefundingException.java, DeductionResult.java
**Acceptance / logic checks:**
- deductForPayment is @Transactional - calling it twice concurrently with the same partner_id serialises, not races
- InsufficientPrefundingException is thrown (not committed) when balance=50.00 USD and amountUsd=51.00 USD
- On success with balance=100.00 and amount=35.77, returned newBalance=64.23 and ledger entry amount=35.77, balanceAfter=64.23
- Scheme call is absent from this service - deductForPayment only touches the DB
- ledger entry entry_type is DEBIT_PAYMENT
**Depends on:** 6.2-T06

### 6.2-T08 — Implement PrefundingDeductionService - reversal and top-up methods  _(40 min)_
**Context:** Three additional operations on PrefundingDeductionService: (1) reverseDeduction(Long partnerId, BigDecimal amountUsd, String txnRef, String note) - used when a payment FAILS after deduction or when reconciliation confirms FAILED for an UNCERTAIN txn. Creates DEBIT_REVERSAL entry type. (2) creditTopUp(Long partnerId, BigDecimal amountUsd, String internalRef, String note) - used when Ops tops up a partner's prepaid balance; creates CREDIT_TOPUP entry. Both methods use SELECT FOR UPDATE to prevent concurrent balance corruption. Balance may not exceed any configured maximum (no explicit max in spec; treat as unconstrained). reverseDeduction must be idempotent: if a ledger entry with txnRef + DEBIT_REVERSAL already exists, return existing result without double-crediting.
**Steps:** Add reverseDeduction(Long partnerId, BigDecimal amountUsd, String txnRef, String note) @Transactional to PrefundingDeductionService; Check for existing DEBIT_REVERSAL entry with same txnRef before applying reversal (idempotency guard); Add creditTopUp(Long partnerId, BigDecimal amountUsd, String internalRef, String note) @Transactional; Both methods append a ledger entry with appropriate EntryType and updated balanceAfter; Add findByAccountIdAndTxnRefAndEntryType query method to PrefundingLedgerEntryRepository for idempotency check
**Deliverable:** Updated PrefundingDeductionService.java with reverseDeduction and creditTopUp, updated PrefundingLedgerEntryRepository
**Acceptance / logic checks:**
- reverseDeduction with balance=64.23, amount=35.77 produces newBalance=100.00 and ledger DEBIT_REVERSAL entry
- reverseDeduction called twice with same txnRef returns same result without creating a second ledger entry (idempotency)
- creditTopUp with balance=100.00, amount=5000.00 produces newBalance=5100.00 and ledger CREDIT_TOPUP entry
- Both methods use @Transactional and acquire SELECT FOR UPDATE lock
- reverseDeduction ledger entry_type is DEBIT_REVERSAL; creditTopUp entry_type is CREDIT_TOPUP
**Depends on:** 6.2-T07

### 6.2-T09 — Implement partner-type guard in deduction service  _(20 min)_
**Context:** Only OVERSEAS partners (partner.partner_type = 'OVERSEAS') have a prefunding_account. Calling deductForPayment for a LOCAL partner (e.g. GME Remit) is a programming error. The service must fail fast with an IllegalArgumentException if invoked for a LOCAL partner. The Transaction Orchestrator handles LOCAL partners by skipping the deduction call entirely (state jumps directly to DEBITED). The guard prevents any accidental call from reaching the DB. Partner type is resolved from the Partner entity via the existing PartnerRepository.
**Steps:** Inject PartnerRepository into PrefundingDeductionService; At the start of deductForPayment, call partnerRepository.findById(partnerId); If partner.partnerType != 'OVERSEAS', throw IllegalArgumentException('Prefunding deduction called for LOCAL partner: ' + partnerId); Write the same guard in reverseDeduction and creditTopUp; Add a convenience helper isOverseasPartner(Long partnerId) returning boolean for use by callers
**Deliverable:** PrefundingDeductionService updated with partner-type guard and isOverseasPartner helper
**Acceptance / logic checks:**
- deductForPayment throws IllegalArgumentException immediately (no DB lock acquired) when called with a LOCAL partner id
- isOverseasPartner returns false for partner with partner_type='LOCAL' and true for 'OVERSEAS'
- Same guard applied in reverseDeduction and creditTopUp
- No prefunding_account row is created or read for a LOCAL partner in any code path
- Unit test confirms the exception message contains the partner id
**Depends on:** 6.2-T07

### 6.2-T10 — Implement low-balance alert trigger in deduction service  _(35 min)_
**Context:** After a successful deduction, if the new balance falls below the partner's configured low_balance_threshold_usd, a prefunding.low_balance internal event must be published. From spec (SAD-02 §7): event payload triggers a low-balance email alert to the partner's alert_email. The alert is informational only - the deduction already succeeded and must NOT be rolled back. Alert config is in low_balance_alert_config (threshold_usd, alert_email, is_active). Publish via Spring ApplicationEventPublisher as a PrefundingLowBalanceEvent(partnerId, currentBalance, thresholdUsd, alertEmail) after the deduction transaction commits (use @TransactionalEventListener(phase=AFTER_COMMIT) on the consumer side to avoid sending the alert if the transaction rolls back).
**Steps:** Create PrefundingLowBalanceEvent.java record with fields partnerId, currentBalanceUsd, thresholdUsd, alertEmail; Inject LowBalanceAlertConfigRepository and ApplicationEventPublisher into PrefundingDeductionService; After persisting the deduction in deductForPayment, check: if newBalance < threshold AND is_active, publish event; Publish event INSIDE the transaction but consume AFTER_COMMIT (note this in a comment); Create LowBalanceAlertConfigRepository.java with findByPartnerId(Long partnerId)
**Deliverable:** PrefundingLowBalanceEvent.java, LowBalanceAlertConfigRepository.java, updated PrefundingDeductionService
**Acceptance / logic checks:**
- Event is published when balance drops from 10500.00 to 9800.00 USD with threshold=10000.00
- Event is NOT published when new balance=10200.00 and threshold=10000.00
- Event is NOT published when is_active=false on the alert config row
- Event contains partnerId, currentBalanceUsd=9800.00, thresholdUsd=10000.00, and alertEmail
- Deduction is not rolled back if event publishing fails (event publishing is fire-and-forget)
**Depends on:** 6.2-T07, 6.2-T03

### 6.2-T11 — Integrate PrefundingDeductionService into Transaction Orchestrator - MPM flow  _(50 min)_
**Context:** Transaction Orchestrator manages the payment lifecycle state machine. For MPM (POST /v1/payments), the deduction must happen BEFORE the scheme call (AD-06, NFR-10 §5.2). Sequence for OVERSEAS partners: (1) State transition to PENDING_DEBIT. (2) Call PrefundingDeductionService.deductForPayment(partnerId, collection_usd, txnRef). (3) If InsufficientPrefundingException: set state=FAILED, error=INSUFFICIENT_PREFUNDING, do NOT call scheme, return HTTP 402. (4) On success: state=DEBITED, prefunding_deducted_usd=collection_usd set on transaction record. (5) Call scheme adapter. For LOCAL partners (partner_type='LOCAL'): skip step 2 entirely, transition QUOTED -> DEBITED directly. collection_usd is the deduction amount - it equals payout_usd_cost / (1 - m_a - m_b) from the rate engine output.
**Steps:** Locate TransactionOrchestrator.commitMpmPayment (or equivalent) in the existing codebase; Inject PrefundingDeductionService; Add partner-type branch: if OVERSEAS call deductForPayment; if LOCAL skip; Catch InsufficientPrefundingException and translate to FAILED state + HTTP 402 response; After successful deduction, set transaction.prefundingDeductedUsd = collection_usd and persist; Ensure scheme adapter is only called after deduction has committed (not in the same DB transaction as deduction)
**Deliverable:** Updated TransactionOrchestrator with MPM prefunding deduction integration
**Acceptance / logic checks:**
- OVERSEAS partner with sufficient balance: state transitions QUOTED -> PENDING_DEBIT -> DEBITED -> SCHEME_SENT in correct order
- OVERSEAS partner with insufficient balance (balance=20.00, deduction=35.77): state=FAILED, HTTP 402, scheme adapter NOT called
- LOCAL partner: state transitions QUOTED -> DEBITED (no PENDING_DEBIT), scheme adapter called without touching prefunding_account
- transaction.prefunding_deducted_usd is set to collection_usd (e.g. 35.77) on success
- Scheme adapter is called only after deduction DB transaction commits
**Depends on:** 6.2-T07, 6.2-T09

### 6.2-T12 — Integrate PrefundingDeductionService into Transaction Orchestrator - CPM generate flow  _(50 min)_
**Context:** For CPM (POST /v1/payments/cpm/generate), the deduction occurs at QR token issuance, not at scheme approval (AD-07). At generate time the final payout amount may not yet be known (merchant POS inputs it). A reservation amount is used: prefund_reserve_usd from the request body if supplied; otherwise the scheme's configured max transaction limit (default USD 500 per transaction if not configured). If deduction of the reservation amount fails (INSUFFICIENT_PREFUNDING), no CPM token is issued and HTTP 402 is returned. At CPM confirm time, if the actual deduction differs from the reservation, adjust via a supplemental deduction or reversal.
**Steps:** Locate TransactionOrchestrator.generateCpmToken (or equivalent); Determine reservation amount: use request.prefundReserveUsd if non-null, else load scheme max transaction limit from config; Call deductForPayment(partnerId, reservationAmountUsd, txnRef) for OVERSEAS partners only; On InsufficientPrefundingException: return HTTP 402, do not issue CPM token; On success: set transaction.prefundingDeductedUsd = reservationAmountUsd, issue CPM token; At CPM confirm: if actual collection_usd != reservation, call deductForPayment for surplus or reverseDeduction for excess
**Deliverable:** Updated TransactionOrchestrator with CPM generate + confirm prefunding integration
**Acceptance / logic checks:**
- OVERSEAS partner with balance=300.00, reserve=200.00: CPM token issued, balance becomes 100.00
- OVERSEAS partner with balance=150.00, reserve=200.00: HTTP 402, no CPM token, balance unchanged
- LOCAL partner: CPM token issued without touching prefunding_account
- At CPM confirm, if reservation was 200.00 but actual collection_usd=180.00, reverseDeduction(20.00) is called
- At CPM confirm, if reservation was 200.00 but actual=220.00, additional deductForPayment(20.00) is called (or fail if insufficient)
**Depends on:** 6.2-T11

### 6.2-T13 — Implement prefunding reversal on payment failure and UNCERTAIN resolution  _(45 min)_
**Context:** When a payment fails AFTER a successful deduction (e.g. scheme returns reject synchronously, or batch reconciliation confirms FAILED for an UNCERTAIN transaction), the deducted amount must be returned to the partner's prefunding balance. From spec: 'If reconciliation confirms FAILED, the deduction is reversed.' (SAD-02 §5.2). Use PrefundingDeductionService.reverseDeduction(partnerId, amountUsd, txnRef, note). Triggers: (1) scheme adapter returns failure immediately after deduction (state DEBITED -> FAILED). (2) Settlement/Reconciliation Engine resolves an UNCERTAIN transaction to FAILED after batch reconciliation (within 24h). Circuit breaker open (SCHEME_UNAVAILABLE) also triggers reversal since scheme was not called (spec §4.3: 'Prefunding balances are not deducted if the scheme call fails or circuit breaker is open').
**Steps:** In TransactionOrchestrator, after scheme adapter call, if scheme returns rejection: call reverseDeduction and transition to FAILED; In ReconciliationEngine, when UNCERTAIN resolves to FAILED: call reverseDeduction(partnerId, prefundingDeductedUsd, txnRef, 'reconciliation confirmed FAILED'); In circuit-breaker-open path: if OVERSEAS partner and deduction already occurred, call reverseDeduction; Ensure reverseDeduction idempotency: if called twice for same txnRef (e.g. duplicate reconciliation event), second call is a no-op; Record DEBIT_REVERSAL ledger entry and update transaction event trail
**Deliverable:** Updated TransactionOrchestrator and ReconciliationEngine with reversal on payment failure
**Acceptance / logic checks:**
- Scheme synchronous rejection: reverseDeduction called, partner balance restored, state=FAILED, DEBIT_REVERSAL ledger entry created
- UNCERTAIN resolved to FAILED by reconciliation: reverseDeduction called with correct amount=prefundingDeductedUsd
- Circuit breaker open path (scheme not called): balance NOT deducted in first place (no deduct, no reversal needed) - confirm via test
- Duplicate reversal call for same txnRef returns existing result without double-crediting
- Transaction event trail includes PREFUND_DEDUCTED step followed by a PREFUND_REVERSED step on failure
**Depends on:** 6.2-T08, 6.2-T11

### 6.2-T14 — Implement GET /v1/balance endpoint handler  _(35 min)_
**Context:** GET /v1/balance returns the current prefunding balance for the authenticated OVERSEAS partner (API-05 §4.8). Response fields: partner_id (string), balance_usd (string decimal), low_balance_threshold_usd (string decimal), is_below_threshold (boolean = balance_usd < low_balance_threshold_usd), as_of (ISO-8601 UTC), recent_deductions (array, only if ?include_history=true, last 10 DEBIT_PAYMENT entries from ledger). LOCAL partners return HTTP 403. This endpoint does NOT use SELECT FOR UPDATE - it is a read-only point-in-time query. Example response: balance_usd='48234.56', low_balance_threshold_usd='10000.00', is_below_threshold=false.
**Steps:** Create BalanceController.java with GET /v1/balance mapped method; Resolve partner from JWT/API-key context; reject LOCAL partners with HTTP 403 and error code PREFUNDING_NOT_APPLICABLE; Query prefunding_account via plain (non-locking) findByPartnerId; Optionally query last 10 ledger entries if include_history=true parameter; Map to BalanceResponse DTO with all fields from spec; format amounts as string decimals with 2dp; Return 200 with response body
**Deliverable:** BalanceController.java, BalanceResponse.java DTO
**Acceptance / logic checks:**
- LOCAL partner calling GET /v1/balance receives HTTP 403 with body containing PREFUNDING_NOT_APPLICABLE error code
- OVERSEAS partner with balance=48234.5600 receives balance_usd='48234.56'
- is_below_threshold=true when balance=9500.00 and threshold=10000.00; false when balance=10001.00
- With ?include_history=true, recent_deductions array contains up to 10 entries ordered by event_at descending
- Without ?include_history=true, recent_deductions field is absent from response body
**Depends on:** 6.2-T06, 6.2-T09

### 6.2-T15 — Unit tests: PrefundingDeductionService - happy path and insufficient funds  _(40 min)_
**Context:** Test class PrefundingDeductionServiceTest. Uses Mockito to mock PrefundingAccountRepository, PrefundingLedgerEntryRepository, LowBalanceAlertConfigRepository, ApplicationEventPublisher, and PartnerRepository. Test vectors from spec: balance=100.00 USD, deduction=35.77 USD -> newBalance=64.23; balance=50.00 USD, deduction=51.00 USD -> InsufficientPrefundingException. All amounts use BigDecimal. Verify that the ledger entry saved has amount=35.77, balanceAfter=64.23, entryType=DEBIT_PAYMENT.
**Steps:** Create PrefundingDeductionServiceTest.java in test/java/com/gmepayplus/prefunding; Mock all dependencies with @Mock / @InjectMocks; Test: deductForPayment with balance=100.00, amount=35.77 returns DeductionResult(previousBalance=100.00, newBalance=64.23); Verify PrefundingLedgerEntryRepository.save called with amount=35.77, balanceAfter=64.23, entryType=DEBIT_PAYMENT; Test: deductForPayment with balance=50.00, amount=51.00 throws InsufficientPrefundingException with availableUsd=50.00, requiredUsd=51.00; Verify repository.save NOT called when exception thrown
**Deliverable:** PrefundingDeductionServiceTest.java with at least 5 test methods
**Acceptance / logic checks:**
- Test 'deduct_success_reduces_balance' passes: newBalance=64.23 asserted via BigDecimal.compareTo
- Test 'deduct_throws_when_insufficient' passes: InsufficientPrefundingException with correct fields
- Verify (via Mockito.verify) that save is NOT called on either repository when exception is thrown (no partial state)
- Test 'deduct_creates_debit_payment_ledger_entry' verifies entryType=DEBIT_PAYMENT and amount=BigDecimal('35.77')
- All tests pass with mvn test
**Depends on:** 6.2-T07

### 6.2-T16 — Unit tests: PrefundingDeductionService - reversal idempotency and LOCAL partner guard  _(35 min)_
**Context:** Extended unit tests for PrefundingDeductionService. Test vectors: (1) reverseDeduction called twice with same txnRef must produce the same result without double-crediting. (2) deductForPayment called with a LOCAL partner id must throw IllegalArgumentException immediately without acquiring a DB lock. (3) creditTopUp(balance=100.00, amount=5000.00) -> newBalance=5100.00 with CREDIT_TOPUP ledger entry. Verify idempotency via mocked ledger repository that returns an existing DEBIT_REVERSAL entry on second call.
**Steps:** Add test 'reversal_is_idempotent': mock ledger repo to return existing DEBIT_REVERSAL entry on second call for same txnRef; Verify account.save NOT called on second reversal call (balance not re-credited); Add test 'deduct_throws_for_local_partner': mock partner repo to return partner with partnerType='LOCAL'; Verify IllegalArgumentException thrown before any lock acquisition (no findByPartnerIdForUpdate call); Add test 'creditTopUp_adds_balance': balance=100.00, top-up=5000.00, assert newBalance=5100.00 and CREDIT_TOPUP entry
**Deliverable:** Extended PrefundingDeductionServiceTest.java with at least 3 additional test methods
**Acceptance / logic checks:**
- 'reversal_is_idempotent' passes: second reverseDeduction call returns without modifying balance
- 'deduct_throws_for_local_partner' passes: IllegalArgumentException thrown, findByPartnerIdForUpdate NEVER called
- 'creditTopUp_adds_balance' passes: newBalance=BigDecimal('5100.00')
- 'creditTopUp_creates_credit_topup_entry' passes: entryType=CREDIT_TOPUP, amount=5000.00
- All tests pass with mvn test
**Depends on:** 6.2-T15, 6.2-T08, 6.2-T09

### 6.2-T17 — Unit tests: low-balance alert trigger  _(30 min)_
**Context:** Test that the low-balance event is published correctly after a successful deduction. Test vectors: (A) balance goes from 10500.00 to 9800.00 with threshold=10000.00 -> event published with currentBalanceUsd=9800.00. (B) balance goes from 11000.00 to 10200.00 with threshold=10000.00 -> no event (still above threshold). (C) balance goes from 10500.00 to 9800.00 but is_active=false on the alert config -> no event. Uses Mockito ArgumentCaptor to assert event fields.
**Steps:** Add test 'low_balance_event_published_when_below_threshold': mock config returns threshold=10000.00, is_active=true; deduct to 9800.00; Capture published event via ArgumentCaptor<PrefundingLowBalanceEvent>; Assert event.currentBalanceUsd()=9800.00, event.thresholdUsd()=10000.00; Add test 'no_event_when_above_threshold': deduct to 10200.00 with threshold=10000.00; verify event publisher NOT invoked; Add test 'no_event_when_alert_inactive': is_active=false; deduct below threshold; verify event NOT published
**Deliverable:** Low-balance alert unit tests added to PrefundingDeductionServiceTest.java
**Acceptance / logic checks:**
- Test A passes: ApplicationEventPublisher.publishEvent called once with correct PrefundingLowBalanceEvent payload
- Test B passes: ApplicationEventPublisher.publishEvent NEVER called
- Test C passes: ApplicationEventPublisher.publishEvent NEVER called when is_active=false
- Event partnerId matches the deducting partner's id
- All tests pass with mvn test
**Depends on:** 6.2-T10, 6.2-T15

### 6.2-T18 — Integration test: concurrent SELECT FOR UPDATE prevents over-deduction  _(55 min)_
**Context:** This is the core correctness test for WBS 6.2 (NFR-10 §5.2, AD-06). Use @SpringBootTest with an embedded PostgreSQL (Testcontainers). Scenario: partner has balance=50.00 USD. Launch 10 concurrent threads each calling deductForPayment(partnerId, 10.00, txnRef_N) simultaneously. Expected: exactly 5 threads succeed (total deduction=50.00, newBalance=0.00); exactly 5 threads throw InsufficientPrefundingException. Total ledger entries = 5 DEBIT_PAYMENT rows. Final balance must be exactly 0.0000 - never negative.
**Steps:** Add Testcontainers PostgreSQL dependency if not already present; Create PrefundingDeductionServiceIntegrationTest.java with @SpringBootTest; Seed a prefunding_account row with balance=50.00 for a test OVERSEAS partner; Submit 10 concurrent Callable tasks via ExecutorService.invokeAll; Count success results and InsufficientPrefundingException results; Query final balance and total ledger entry count from DB
**Deliverable:** PrefundingDeductionServiceIntegrationTest.java with concurrent over-deduction test
**Acceptance / logic checks:**
- Exactly 5 threads succeed and exactly 5 throw InsufficientPrefundingException (total must be 10)
- Final balance in DB is exactly 0.0000 (not negative, not positive)
- Exactly 5 DEBIT_PAYMENT ledger entries exist in prefunding_ledger_entry for the test partner
- Test is deterministic and passes on repeated runs (no flakiness from lock ordering)
- No DB deadlock exceptions observed in test output
**Depends on:** 6.2-T07

### 6.2-T19 — Integration test: MPM payment flow with prefunding - success path  _(55 min)_
**Context:** End-to-end integration test (Testcontainers, stub scheme adapter) for POST /v1/payments MPM flow. OVERSEAS partner SendMN: balance=1000.00 USD. Rate engine produces collection_usd=35.77 (payout KRW 50,000 / cost_rate_pay=1397.00 -> payout_usd_cost=35.78, then /( 1-0.01-0.01)=36.51 collection_usd approximate; use exact values from rate engine). Stub scheme adapter returns approval. Expected: state=APPROVED, prefundingDeductedUsd=collection_usd, balance reduced by collection_usd, 1 DEBIT_PAYMENT ledger entry.
**Steps:** Create MpmPaymentIntegrationTest.java with @SpringBootTest and TestRestTemplate; Seed partner SendMN with balance=1000.00 and a valid quote_id for a KRW 50,000 payment; POST /v1/payments with valid request body including quote_id; Stub ZeroPay scheme adapter to return ApprovalCode='ZP-APPROVAL-001'; Assert HTTP 200 or 201 response with status=APPROVED; Assert prefunding_account.balance = 1000.00 - collection_usd from DB
**Deliverable:** MpmPaymentIntegrationTest.java covering the OVERSEAS success path
**Acceptance / logic checks:**
- HTTP response is 200/201 with status=APPROVED
- prefunding_account.balance reduced by exactly collection_usd (BigDecimal equality)
- One DEBIT_PAYMENT ledger entry exists with amount=collection_usd, balanceAfter=1000.00-collection_usd
- transaction.prefunding_deducted_usd = collection_usd in DB
- Scheme adapter stub was called exactly once
**Depends on:** 6.2-T11, 6.2-T18

### 6.2-T20 — Integration test: MPM payment - insufficient funds returns HTTP 402  _(30 min)_
**Context:** POST /v1/payments for OVERSEAS partner with insufficient balance must return HTTP 402 INSUFFICIENT_PREFUNDING without calling the scheme. Test vector: partner balance=20.00 USD, rate engine produces collection_usd=35.77 USD. Expected: HTTP 402 response body {error:'INSUFFICIENT_PREFUNDING'}, transaction state=FAILED, scheme adapter NOT called, prefunding balance still 20.00 USD, no DEBIT_PAYMENT ledger entry created.
**Steps:** Add test method 'mpm_payment_insufficient_funds' to MpmPaymentIntegrationTest.java; Seed partner with balance=20.00 USD; Seed a quote for a payment requiring collection_usd=35.77; POST /v1/payments with valid body; Assert HTTP 402; Verify DB: prefunding_account.balance=20.00, zero DEBIT_PAYMENT entries, transaction.status=FAILED
**Deliverable:** Test method mpm_payment_insufficient_funds in MpmPaymentIntegrationTest.java
**Acceptance / logic checks:**
- HTTP response is 402
- Response body contains error code INSUFFICIENT_PREFUNDING
- Scheme adapter stub was called zero times
- DB: prefunding_account.balance still equals 20.0000 after request
- DB: no DEBIT_PAYMENT ledger entry for this txnRef
**Depends on:** 6.2-T19

### 6.2-T21 — Integration test: CPM generate and confirm with prefunding adjustment  _(55 min)_
**Context:** POST /v1/payments/cpm/generate with OVERSEAS partner: reservation=200.00 USD, balance=500.00 USD. Token issued, balance=300.00. At CPM confirm, actual collection_usd=180.00 (less than reservation). Expected: supplemental reversal of 20.00 called, final balance=320.00. Also test surplus case: actual collection_usd=210.00, additional deduction of 10.00 called, final balance=290.00.
**Steps:** Create CpmPaymentIntegrationTest.java; Test A: generate with reserve=200.00, confirm with actual=180.00; assert final balance=320.00 and one DEBIT_PAYMENT + one DEBIT_REVERSAL entry; Test B: generate with reserve=200.00, confirm with actual=210.00; assert final balance=290.00 and two DEBIT_PAYMENT entries (200+10); Test C: generate with balance=150.00, reserve=200.00; assert HTTP 402, no token, balance unchanged; Verify CPM token expires_at is approximately 60 seconds in the future
**Deliverable:** CpmPaymentIntegrationTest.java covering generate + confirm prefunding flows
**Acceptance / logic checks:**
- Test A: final balance=320.00 in DB; ledger has DEBIT_PAYMENT(200.00) and DEBIT_REVERSAL(20.00)
- Test B: final balance=290.00 in DB; ledger has two DEBIT_PAYMENT rows summing to 210.00
- Test C: HTTP 402 returned, balance=150.00 unchanged in DB
- expires_at in CPM token response is between 55 and 65 seconds from request time
- Scheme adapter not called in Test C (insufficient funds path)
**Depends on:** 6.2-T12, 6.2-T18

### 6.2-T22 — Integration test: prefunding reversal on scheme failure and UNCERTAIN resolution  _(50 min)_
**Context:** Test the reversal flow: (1) deduction succeeds then scheme adapter returns synchronous rejection -> balance restored. (2) deduction succeeds, scheme returns UNCERTAIN (timeout), reconciliation later marks FAILED -> balance restored. Vector: balance=100.00, deduction=35.77; scenario 1: balance restored to 100.00 after rejection; scenario 2: balance stays at 64.23 until reconciliation resolves to FAILED, then restored to 100.00.
**Steps:** In MpmPaymentIntegrationTest.java add test 'scheme_rejection_reverses_deduction': stub scheme to return rejection; Assert balance returns to 100.00 after rejection, DEBIT_PAYMENT + DEBIT_REVERSAL entries in ledger; Add test 'uncertain_resolution_to_failed_reverses_deduction': stub scheme to throw timeout (UNCERTAIN state); Call ReconciliationEngine.resolveUncertain(txnRef, 'FAILED'); Assert balance returns to 100.00, DEBIT_REVERSAL entry created; Verify reversal called only once even if resolveUncertain called twice (idempotency)
**Deliverable:** Tests scheme_rejection_reverses_deduction and uncertain_resolution_to_failed_reverses_deduction
**Acceptance / logic checks:**
- After scheme rejection: balance=100.0000, ledger has DEBIT_PAYMENT(35.77) + DEBIT_REVERSAL(35.77)
- After UNCERTAIN -> FAILED: balance=100.0000 restored, DEBIT_REVERSAL(35.77) present
- Double call to resolveUncertain('FAILED'): only one DEBIT_REVERSAL entry (idempotency)
- Transaction state=FAILED in both scenarios
- Scheme adapter was called exactly once in the rejection scenario (not retried)
**Depends on:** 6.2-T13, 6.2-T19

### 6.2-T23 — Unit tests: GET /v1/balance controller  _(35 min)_
**Context:** Unit test BalanceController using MockMvc. Test vectors: (1) OVERSEAS partner balance=48234.56, threshold=10000.00, is_below_threshold=false. (2) OVERSEAS partner balance=9500.00, threshold=10000.00, is_below_threshold=true. (3) LOCAL partner returns HTTP 403. (4) ?include_history=true returns recent_deductions array with mocked 3 entries. Amounts must be formatted as string decimals with exactly 2 decimal places (e.g. '48234.56' not '48234.5600').
**Steps:** Create BalanceControllerTest.java with @WebMvcTest(BalanceController.class); Mock PrefundingAccountRepository and PrefundingLedgerEntryRepository; Test 1: OVERSEAS partner, balance=48234.56; assert balance_usd='48234.56', is_below_threshold=false; Test 2: OVERSEAS partner, balance=9500.00; assert is_below_threshold=true; Test 3: LOCAL partner; assert HTTP 403 body contains PREFUNDING_NOT_APPLICABLE; Test 4: include_history=true; assert recent_deductions array length=3
**Deliverable:** BalanceControllerTest.java with at least 4 test methods
**Acceptance / logic checks:**
- Test 1 passes: balance_usd='48234.56' as JSON string
- Test 2 passes: is_below_threshold=true
- Test 3 passes: HTTP 403 with PREFUNDING_NOT_APPLICABLE in body
- Test 4 passes: recent_deductions present with 3 elements when include_history=true
- Test 5 (without include_history): recent_deductions key absent from response JSON
**Depends on:** 6.2-T14

### 6.2-T24 — Error mapping: InsufficientPrefundingException to HTTP 402 response body  _(30 min)_
**Context:** InsufficientPrefundingException must map to HTTP 402 with JSON body {error:'INSUFFICIENT_PREFUNDING', partner_id:'...', required_usd:'51.00', available_usd:'50.00'}. This is done via a Spring @ExceptionHandler or @ControllerAdvice. The error code INSUFFICIENT_PREFUNDING is defined in API-05. The response must NOT include stack traces. HTTP 403 for LOCAL partner calling /v1/balance uses error code PREFUNDING_NOT_APPLICABLE. Both must use consistent GMEPay+ error envelope: {error, message, request_id}.
**Steps:** Create or update GlobalExceptionHandler.java (@RestControllerAdvice); Add @ExceptionHandler(InsufficientPrefundingException.class) returning ResponseEntity with HTTP 402; Response body: {error:'INSUFFICIENT_PREFUNDING', required_usd: formatted BigDecimal, available_usd: formatted BigDecimal}; Add @ExceptionHandler for local-partner access on /v1/balance returning HTTP 403 + PREFUNDING_NOT_APPLICABLE; Ensure stack traces are excluded from response bodies in all environments; Write unit test verifying the 402 JSON body structure
**Deliverable:** GlobalExceptionHandler.java with InsufficientPrefundingException and local-partner handlers, unit test
**Acceptance / logic checks:**
- POST /v1/payments with insufficient funds returns HTTP 402 with body containing error='INSUFFICIENT_PREFUNDING'
- Body contains required_usd='51.00' and available_usd='50.00' (string decimal format)
- Body does NOT contain a stack trace field
- GET /v1/balance by LOCAL partner returns HTTP 403 with error='PREFUNDING_NOT_APPLICABLE'
- Unit test for GlobalExceptionHandler passes with mvn test
**Depends on:** 6.2-T07, 6.2-T14

### 6.2-T25 — Add prefunding_deducted_usd to transaction event trail (PREFUND_DEDUCTED event step)  _(30 min)_
**Context:** The 8-step transaction event trail requires step 2 = PREFUND_DEDUCTED (from spec assumption A3 in DAT-03). A TransactionEvent row must be inserted with event_type='PREFUND_DEDUCTED', step=2, occurred_at=now(), duration_ms=deduction_duration after a successful deduction. For LOCAL partners (no deduction), this step is skipped and step 3 begins at SCHEME_SUBMITTED. The transaction_event table is append-only. Each event stores txn_ref, step, event_type, occurred_at, duration_ms.
**Steps:** Verify TransactionEventRepository and TransactionEvent entity exist (from earlier WBS migrations); In PrefundingDeductionService or TransactionOrchestrator, after successful deductForPayment, persist TransactionEvent(step=2, event_type='PREFUND_DEDUCTED', duration_ms=measured); For LOCAL partner path in TransactionOrchestrator, skip step 2 and proceed to step 3; Add unit test asserting step 2 event is saved after successful deduction; Add unit test asserting step 2 is absent for LOCAL partner path
**Deliverable:** PREFUND_DEDUCTED event step emitted after successful deduction; tests for both OVERSEAS and LOCAL paths
**Acceptance / logic checks:**
- Successful OVERSEAS deduction: TransactionEvent with event_type='PREFUND_DEDUCTED' and step=2 is inserted
- duration_ms field is > 0 and represents actual deduction latency
- LOCAL partner MPM flow: no PREFUND_DEDUCTED event in transaction_event table for that txn_ref
- Event occurred_at is within 1 second of the deduction timestamp
- Unit tests pass with mvn test
**Depends on:** 6.2-T11, 6.2-T07

### 6.2-T26 — Performance test: deduction throughput under 50 TPS sustained load  _(55 min)_
**Context:** NFR-10 §2.2 requires 50 TPS sustained. The deduction path (SELECT FOR UPDATE) is the critical serialisation point per partner. Test with a single OVERSEAS partner at 50 concurrent deduction requests/sec sustained for 60 seconds. Assert: p95 latency of the deduction DB operation (not full API) < 50ms; no over-deductions; all requests return either success or INSUFFICIENT_PREFUNDING (no 5xx errors). Use k6 or JMeter against the local test environment with Testcontainers PostgreSQL.
**Steps:** Seed partner with balance=500000.00 USD (large enough that no failures from insufficient funds expected); Write k6 or JMeter script: 50 virtual users each calling POST /v1/payments (or the deduction service directly) at 1 req/sec each for 60 seconds; Measure p50/p95 of the DB deduction operation using Spring Micrometer timer; Assert p95 < 50ms for the deduction-only portion (excluding scheme stub latency); Assert final balance = 500000.00 - (success_count * deduction_amount_per_request); Assert zero 5xx responses
**Deliverable:** Performance test script (k6/JMeter) and results report as a text file in src/test/perf/
**Acceptance / logic checks:**
- p95 deduction latency < 50ms under 50 TPS
- Zero 5xx responses during 60-second run
- Final balance exactly equals 500000.00 minus (num_successful_deductions * amount) - no over-deduction
- No DB deadlock errors in application logs
- Test report file saved to src/test/perf/6.2-deduction-perf-results.txt
**Depends on:** 6.2-T18

### 6.2-T27 — Add Micrometer metrics for prefunding deduction observability  _(35 min)_
**Context:** OPS-13 and NFR-10 require observability metrics. Metrics to add: (1) prefunding.deduction.success counter (tags: partner_id) - incremented on each successful deduction. (2) prefunding.deduction.failure counter (tags: partner_id, reason=INSUFFICIENT_FUNDS) - incremented on InsufficientPrefundingException. (3) prefunding.balance gauge (tags: partner_id) - reports current balance (polled periodically, not per-deduction). (4) prefunding.deduction.duration timer (tags: partner_id) - records deduction latency. Use Micrometer MeterRegistry injected into PrefundingDeductionService.
**Steps:** Inject MeterRegistry into PrefundingDeductionService; Add Counter.increment for prefunding.deduction.success on success; Add Counter.increment for prefunding.deduction.failure on InsufficientPrefundingException; Add Timer.record wrapping the DB deduction for prefunding.deduction.duration; Register a Gauge for prefunding.balance queried from DB (use scheduled poller every 30s, not per-request); Write unit test verifying counters increment correctly
**Deliverable:** Updated PrefundingDeductionService.java with Micrometer metrics, unit test for counters
**Acceptance / logic checks:**
- prefunding.deduction.success counter increments by 1 on successful deduction (verified in unit test via MeterRegistry)
- prefunding.deduction.failure counter increments by 1 on InsufficientPrefundingException
- prefunding.deduction.duration timer records a value > 0 ms
- prefunding.balance gauge is registered with partner_id tag
- All existing unit tests still pass after instrumentation changes
**Depends on:** 6.2-T07

### 6.2-T28 — Document PrefundingDeductionService API and concurrency contract  _(30 min)_
**Context:** A developer implementing callers of PrefundingDeductionService needs to understand: when to call vs skip (OVERSEAS/LOCAL distinction), the locking contract (no application-level locks needed - DB handles it), the exception contract (InsufficientPrefundingException = reject, not retry), and the idempotency contract for reverseDeduction (safe to call twice). Document as Javadoc on the service class and as a single ADR (Architecture Decision Record) file capturing the SELECT FOR UPDATE choice over application-level locking or optimistic locking.
**Steps:** Add comprehensive Javadoc to PrefundingDeductionService class-level, deductForPayment, reverseDeduction, and creditTopUp; Javadoc must cover: thread-safety guarantee, exception semantics, when NOT to call (LOCAL partners), idempotency of reversal; Create docs/adr/ADR-006-prefunding-atomic-deduction.md (or next available number in ADR sequence); ADR content: context (concurrent payment requests risk over-draw), decision (SELECT FOR UPDATE), alternatives considered (optimistic locking, Redis-based lock), consequences; ADR must reference NFR-10 §5.2 and AD-06 from SAD-02
**Deliverable:** Javadoc on PrefundingDeductionService.java and docs/adr/ADR-006-prefunding-atomic-deduction.md
**Acceptance / logic checks:**
- Javadoc on deductForPayment describes: @param partnerId (must be OVERSEAS), @throws InsufficientPrefundingException with conditions
- Javadoc on reverseDeduction explicitly states 'safe to call twice for same txnRef (idempotent)'
- ADR includes Status: Accepted, Context, Decision, Alternatives Considered, Consequences sections
- ADR references NFR-10 §5.2 (SELECT FOR UPDATE mandate) and AD-06 (scheme never called without prior deduction)
- ADR file is committed and passes a markdown lint check
**Depends on:** 6.2-T07, 6.2-T08


## WBS 6.3 — Insufficient-balance rejection
### 6.3-T01 — Create prefunding_account table migration  _(25 min)_
**Context:** GMEPay+ maintains a prepaid USD balance per OVERSEAS partner. The prefunding_account table holds one row per OVERSEAS partner with fields: id BIGINT PK, partner_id BIGINT FK->partner UNIQUE, currency CHAR(3) always USD, balance DECIMAL(20,4), low_balance_threshold DECIMAL(20,4) default 10000.00, created_at/updated_at/created_by/updated_by. Balance is mutated only via SELECT FOR UPDATE; no application-level locking. LOCAL partners have no prefunding account.
**Steps:** Create a Flyway (or Liquibase) migration file V6_3_001__create_prefunding_account.sql; Define table with all columns per spec: id BIGINT GENERATED ALWAYS AS IDENTITY PK, partner_id BIGINT NOT NULL UNIQUE REFERENCES partner(id), currency CHAR(3) NOT NULL DEFAULT 'USD', balance DECIMAL(20,4) NOT NULL DEFAULT 0.0000, low_balance_threshold DECIMAL(20,4) NOT NULL DEFAULT 10000.0000, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), created_by VARCHAR(120), updated_by VARCHAR(120); Add a CHECK constraint balance >= 0; Add index on partner_id (already enforced by UNIQUE but confirm explicit index exists for FK lookup); Run migration in dev environment and verify schema with \d prefunding_account
**Deliverable:** Migration file V6_3_001__create_prefunding_account.sql applied cleanly to dev DB
**Acceptance / logic checks:**
- Migration applies without error; table exists with all 10 columns
- balance column is DECIMAL(20,4); currency defaults to USD
- UNIQUE constraint on partner_id prevents two accounts per partner
- CHECK constraint rejects INSERT with balance = -0.0001
- Attempting to set balance = -5.00 via direct UPDATE raises constraint violation

### 6.3-T02 — Create prefunding_ledger_entry table migration  _(25 min)_
**Context:** Every debit or credit to a prefunding_account creates an immutable append-only ledger row. Fields: id BIGINT PK, account_id BIGINT FK->prefunding_account, txn_ref VARCHAR(64) (payment txn_ref for debits; internal ref for top-ups), entry_type VARCHAR(20) in (DEBIT_PAYMENT, DEBIT_REVERSAL, CREDIT_TOPUP, CREDIT_REVERSAL), amount DECIMAL(20,4) positive value (direction indicated by entry_type), balance_after DECIMAL(20,4) running balance snapshot, note VARCHAR(255), created_at TIMESTAMPTZ immutable, created_by VARCHAR(120). No updated_at. Index on (account_id, created_at) for history queries.
**Steps:** Create migration file V6_3_002__create_prefunding_ledger_entry.sql; Define table with all columns; add CHECK (amount > 0); Add CHECK constraint entry_type IN ('DEBIT_PAYMENT','DEBIT_REVERSAL','CREDIT_TOPUP','CREDIT_REVERSAL'); Create composite index idx_prefunding_ledger_account_created ON prefunding_ledger_entry(account_id, created_at); Confirm no updated_at column exists (table is append-only)
**Deliverable:** Migration file V6_3_002__create_prefunding_ledger_entry.sql applied cleanly
**Acceptance / logic checks:**
- Table has exactly 10 columns; no updated_at column
- CHECK (amount > 0) rejects INSERT with amount = 0
- entry_type CHECK rejects value 'DEBIT_UNKNOWN'
- Composite index idx_prefunding_ledger_account_created exists and is used by EXPLAIN on (account_id, created_at DESC) query
- FK to prefunding_account enforced: INSERT with nonexistent account_id fails
**Depends on:** 6.3-T01

### 6.3-T03 — Create low_balance_alert_config table migration  _(20 min)_
**Context:** Per-partner configurable alert settings live in a separate table: id BIGINT PK, partner_id BIGINT FK->partner UNIQUE, threshold_usd DECIMAL(20,4) default 10000.00, alert_email VARCHAR(255), is_active BOOLEAN, created_at/updated_at/created_by/updated_by. This table drives when the prefunding.low_balance internal event fires after a debit; it is separate from prefunding_account so alert settings can be updated without touching the balance row.
**Steps:** Create migration file V6_3_003__create_low_balance_alert_config.sql; Define table with all 9 columns; threshold_usd NOT NULL DEFAULT 10000.0000; Add CHECK (threshold_usd >= 0); Confirm UNIQUE on partner_id; Run migration and verify with \d low_balance_alert_config
**Deliverable:** Migration file V6_3_003__create_low_balance_alert_config.sql applied cleanly
**Acceptance / logic checks:**
- Table exists with 9 columns including is_active BOOLEAN
- UNIQUE constraint on partner_id enforced
- CHECK (threshold_usd >= 0) rejects negative threshold
- threshold_usd defaults to 10000.0000 when not provided
- FK to partner(id) enforced; orphan partner_id insert fails

### 6.3-T04 — Define PrefundingAccount and PrefundingLedgerEntry JPA entities  _(30 min)_
**Context:** Java JPA entities for the prefunding_account and prefunding_ledger_entry tables created in T01/T02. PrefundingAccount: fields id, partnerId, currency (always USD), balance (BigDecimal scale 4), lowBalanceThreshold (BigDecimal scale 4), audit fields. PrefundingLedgerEntry: fields id, accountId, txnRef, entryType (enum: DEBIT_PAYMENT, DEBIT_REVERSAL, CREDIT_TOPUP, CREDIT_REVERSAL), amount, balanceAfter, note, createdAt, createdBy. LedgerEntry has no updatedAt (immutable). Use DECIMAL(20,4) column definitions in @Column annotations.
**Steps:** Create PrefundingAccount.java JPA entity mapped to prefunding_account; Create EntryType.java enum with four values; Create PrefundingLedgerEntry.java JPA entity mapped to prefunding_ledger_entry; annotate as immutable (no setter for id/createdAt); Add @Version on PrefundingAccount for optimistic locking (secondary guard alongside SELECT FOR UPDATE); Ensure balance field uses BigDecimal with @Column(precision=20, scale=4)
**Deliverable:** PrefundingAccount.java, EntryType.java, PrefundingLedgerEntry.java entity classes compiling cleanly
**Acceptance / logic checks:**
- PrefundingAccount.balance is BigDecimal with precision 20 scale 4
- PrefundingLedgerEntry has no updatedAt field
- EntryType enum contains exactly DEBIT_PAYMENT, DEBIT_REVERSAL, CREDIT_TOPUP, CREDIT_REVERSAL
- PrefundingLedgerEntry.amount is BigDecimal not double or float
- Unit: new PrefundingLedgerEntry with amount=BigDecimal.ZERO fails bean validation (@Positive or @DecimalMin('0.01'))
**Depends on:** 6.3-T01, 6.3-T02

### 6.3-T05 — Define PrefundingRepository and LedgerEntryRepository interfaces  _(25 min)_
**Context:** Spring Data JPA repositories for the two prefunding tables. PrefundingAccountRepository needs: findByPartnerId(Long), findByPartnerIdWithLock (using @Lock(PESSIMISTIC_WRITE) for the SELECT FOR UPDATE pattern). PrefundingLedgerEntryRepository needs: save(entry), findByAccountIdOrderByCreatedAtDesc (paged), findByTxnRef(String). The lock query is critical: it must use JPQL with LockModeType.PESSIMISTIC_WRITE so Hibernate emits SELECT ... FOR UPDATE.
**Steps:** Create PrefundingAccountRepository.java extending JpaRepository; Add @Lock(LockModeType.PESSIMISTIC_WRITE) @Query on findByPartnerIdWithLock method; Create PrefundingLedgerEntryRepository.java extending JpaRepository; Add findByAccountIdOrderByCreatedAtDesc(Long accountId, Pageable pageable); Add findByTxnRef(String txnRef) for idempotency lookups
**Deliverable:** PrefundingAccountRepository.java and PrefundingLedgerEntryRepository.java
**Acceptance / logic checks:**
- findByPartnerIdWithLock generates SQL containing FOR UPDATE (verified via Hibernate SQL log in test)
- findByAccountIdOrderByCreatedAtDesc returns entries newest-first
- findByTxnRef returns empty Optional for unknown ref
- Repository bean loads in Spring context integration test without errors
- LockModeType is PESSIMISTIC_WRITE not OPTIMISTIC
**Depends on:** 6.3-T04

### 6.3-T06 — Implement PrefundingService.deductBalance core method  _(45 min)_
**Context:** PrefundingService.deductBalance(Long partnerId, BigDecimal amountUsd, String txnRef) is the single atomic point for all prefunding debits. Algorithm: 1) Load account via findByPartnerIdWithLock (SELECT FOR UPDATE). 2) If balance < amountUsd throw InsufficientPrefundingException (do NOT call scheme). 3) Subtract: newBalance = balance - amountUsd. 4) Update account.balance = newBalance and save. 5) Append PrefundingLedgerEntry (type=DEBIT_PAYMENT, amount=amountUsd, balanceAfter=newBalance, txnRef=txnRef). 6) Return DeductionResult(newBalance, entryId). Entire method runs in a single @Transactional block. LOCAL partners must never call this method; guard: if partner.type != OVERSEAS throw IllegalStateException.
**Steps:** Create PrefundingService.java Spring @Service; Implement deductBalance(@Transactional, requires active TX) using repository lock query; Throw InsufficientPrefundingException (checked, carries partner_id and required/available amounts) when balance < amountUsd; Update account balance and persist ledger entry atomically in same transaction; Return DeductionResult record with fields newBalance, ledgerEntryId; Add guard: throw IllegalStateException if partner type is not OVERSEAS
**Deliverable:** PrefundingService.java with deductBalance method; InsufficientPrefundingException.java
**Acceptance / logic checks:**
- balance=100.00 amountUsd=100.00 -> deduction succeeds, newBalance=0.00, ledger entry created
- balance=99.99 amountUsd=100.00 -> InsufficientPrefundingException thrown, balance unchanged, no ledger entry written
- Concurrent calls: two threads each trying to deduct 60.00 from balance=100.00 -> exactly one succeeds, one throws InsufficientPrefundingException (test with two threads and CountDownLatch)
- LOCAL partner call -> IllegalStateException before any DB access
- Returned DeductionResult.newBalance equals account.balance in DB after commit
**Depends on:** 6.3-T05

### 6.3-T07 — Implement InsufficientPrefundingException and error mapping to HTTP 402  _(30 min)_
**Context:** GMEPay+ returns HTTP 402 with error code INSUFFICIENT_PREFUNDING when an OVERSEAS partner's balance is too low. Error envelope format: {error: {code: 'INSUFFICIENT_PREFUNDING', message: '...', request_id: '...', details: []}}. This error is not retryable; the partner must top up and use a new Idempotency-Key. The exception must carry enough info to build the error message: partner_id, required_amount_usd (the deduction that was attempted), available_balance_usd.
**Steps:** Create InsufficientPrefundingException.java extending RuntimeException with fields partnerId, requiredAmountUsd, availableBalanceUsd; Create or extend GlobalExceptionHandler (@RestControllerAdvice) to map InsufficientPrefundingException to HTTP 402; Build response body: error.code='INSUFFICIENT_PREFUNDING', error.message includes available and required amounts, error.details empty array; Ensure no scheme-call or further processing occurs after this exception propagates; Add the error code to the API error code reference (error-codes enum/constant class if one exists)
**Deliverable:** InsufficientPrefundingException.java; exception handler mapping to 402 response
**Acceptance / logic checks:**
- Handler returns HTTP 402 with Content-Type application/json
- Response body error.code == 'INSUFFICIENT_PREFUNDING'
- Response body contains request_id matching X-Request-ID header
- error.details is an empty array not null
- Exception thrown by deductBalance propagates through handler without being swallowed as 500
**Depends on:** 6.3-T06

### 6.3-T08 — Integrate prefunding deduction into MPM payment orchestration (POST /v1/payments)  _(45 min)_
**Context:** In the Transaction Orchestrator, POST /v1/payments flow for OVERSEAS partners must: transition state QUOTED->PENDING_DEBIT, call PrefundingService.deductBalance(partnerId, payout_usd_cost, txnRef), on success transition PENDING_DEBIT->DEBITED then proceed to scheme call; on InsufficientPrefundingException transition PENDING_DEBIT->FAILED and return HTTP 402. The deduction amount is payout_usd_cost = target_payout / cost_rate_pay (from the locked rate quote). The scheme is NEVER called if deduction fails. LOCAL partners skip PENDING_DEBIT and go directly QUOTED->DEBITED.
**Steps:** In the payment orchestration handler for POST /v1/payments, after quote validation and before scheme adapter call, add partner-type branch; For OVERSEAS: set txn state = PENDING_DEBIT, call deductBalance; catch InsufficientPrefundingException -> set state FAILED, rethrow (handler returns 402); For LOCAL: set txn state = DEBITED directly (no deduction); Confirm scheme adapter is only invoked after reaching DEBITED state; Record prefund_deducted_usd in the transaction record on successful deduction; include this field in the 201 response body
**Deliverable:** Updated payment orchestration handler with prefunding guard for MPM flow
**Acceptance / logic checks:**
- OVERSEAS partner with sufficient balance: state transitions QUOTED->PENDING_DEBIT->DEBITED->SCHEME_SENT; scheme is called
- OVERSEAS partner with balance=0.00 and amountUsd=35.77: HTTP 402 returned, state=FAILED, scheme adapter never invoked
- LOCAL partner (type=LOCAL): state goes QUOTED->DEBITED; deductBalance never called
- 201 response for OVERSEAS payment includes prefund_deducted_usd matching payout_usd_cost to 4 decimal places
- Failing deduction does not create a ledger entry (verified by querying prefunding_ledger_entry for that txn_ref)
**Depends on:** 6.3-T07, 6.3-T05

### 6.3-T09 — Integrate prefunding reservation into CPM token generation (POST /v1/payments/cpm/generate)  _(40 min)_
**Context:** For CPM flow, the prefunding deduction happens at POST /v1/payments/cpm/generate (QR token issuance), not when the merchant scans. Spec: the QR token represents a committed payment intent. For OVERSEAS partners, call PrefundingService.deductBalance with a reservation amount. The reserved amount is the payout_usd_cost derived from the rule (since no payout amount is known at generate time, use the rule's configured max amount or a configurable cap per partner). On insufficient balance return HTTP 402 and do not issue the token. Response field prefund_reserved_usd echoes the reserved amount.
**Steps:** In POST /v1/payments/cpm/generate handler, after partner/scheme validation, add OVERSEAS prefunding check; Determine reservation amount: use partner rule's configured cpm_reservation_usd or partner account max if set; document the field name used; Call PrefundingService.deductBalance(partnerId, reservationAmount, generatedCpmTxnRef); On InsufficientPrefundingException return HTTP 402 (same envelope) and do not generate token; On success include prefund_reserved_usd in the 201 response body
**Deliverable:** Updated CPM generate handler with prefunding guard
**Acceptance / logic checks:**
- OVERSEAS partner with balance=50.00, reservation=100.00: HTTP 402 returned, no CPM token issued, no ledger entry created
- OVERSEAS partner with balance=200.00, reservation=100.00: token issued, prefund_reserved_usd=100.00 in response
- LOCAL partner: deductBalance not called, token issued without any balance check
- State at token issue is PENDING_DEBIT transitioning to DEBITED after successful deduction
- Deduction txn_ref stored with CPM token record for reconciliation
**Depends on:** 6.3-T08

### 6.3-T10 — Implement post-deduction low-balance alert trigger  _(30 min)_
**Context:** After every successful prefunding deduction, if the new balance falls at or below the partner's low_balance_threshold_usd (from low_balance_alert_config), fire an internal event prefunding.low_balance. The Notification Service listens to this event and sends an email to alert_email. The alert should fire once per threshold crossing per debit (not on every transaction while below threshold). Use a simple approach: fire if newBalance <= threshold AND (previous balance > threshold OR this is a configurable re-alert interval). Phase 1 simplification: fire the alert on every debit where newBalance <= threshold and is_active=true.
**Steps:** After successful deductBalance, load low_balance_alert_config for the partner; If is_active=true and newBalance <= threshold_usd, publish ApplicationEvent PrefundingLowBalanceEvent(partnerId, newBalance, threshold_usd, alertEmail); Create PrefundingLowBalanceEvent.java record with those fields; Create NotificationService listener @EventListener that logs the event (email integration is a separate ticket or stub); at minimum write to audit log; Write unit test: deductBalance from 15000.00 to 9500.00 with threshold=10000.00 verifies event published
**Deliverable:** PrefundingLowBalanceEvent.java; event publish logic in PrefundingService; stub listener in NotificationService
**Acceptance / logic checks:**
- Deduct from 15000.00 to 9500.00 with threshold=10000.00: PrefundingLowBalanceEvent is published with newBalance=9500.00
- Deduct from 9000.00 to 8000.00 with threshold=10000.00 (already below threshold): event is still published (phase 1 simple rule)
- is_active=false: no event published regardless of balance
- LOCAL partner: no event published (deductBalance never called for LOCAL)
- Event fields partnerId, newBalance, thresholdUsd, alertEmail all non-null
**Depends on:** 6.3-T06

### 6.3-T11 — Implement PrefundingService.creditBalance for top-ups and reversals  _(30 min)_
**Context:** Balance credits occur in two cases: (1) CREDIT_TOPUP when GME Ops records a partner top-up via Admin System; (2) CREDIT_REVERSAL when an UNCERTAIN payment is reconciled as FAILED. Method signature: creditBalance(Long partnerId, BigDecimal amountUsd, EntryType entryType, String txnRef, String note). Algorithm: SELECT FOR UPDATE on account, newBalance = balance + amountUsd, save account, append ledger entry. entry_type must be CREDIT_TOPUP or CREDIT_REVERSAL; any other value throws IllegalArgumentException.
**Steps:** Add creditBalance method to PrefundingService.java annotated @Transactional; Load account with pessimistic write lock (findByPartnerIdWithLock); Validate entryType is CREDIT_TOPUP or CREDIT_REVERSAL; throw IllegalArgumentException otherwise; Add amountUsd to balance; persist ledger entry with type, txnRef, note, balanceAfter; Return CreditResult(newBalance, ledgerEntryId)
**Deliverable:** PrefundingService.creditBalance method
**Acceptance / logic checks:**
- creditBalance(partnerId, 5000.00, CREDIT_TOPUP, 'TOP-2026-001', 'Manual top-up'): balance increases by 5000.00, ledger entry created with entry_type=CREDIT_TOPUP
- CREDIT_REVERSAL entry: ledger entry type=CREDIT_REVERSAL and txnRef matches original payment txnRef
- Invalid entryType DEBIT_PAYMENT passed to creditBalance: IllegalArgumentException thrown
- Concurrent credit and debit: SELECT FOR UPDATE prevents lost updates
- balanceAfter in ledger entry equals account.balance after commit
**Depends on:** 6.3-T06

### 6.3-T12 — Implement UNCERTAIN->FAILED prefunding reversal in reconciliation path  _(40 min)_
**Context:** When the Settlement/Reconciliation Engine resolves a transaction from UNCERTAIN to FAILED, if the partner is OVERSEAS, the previously deducted prefunding amount must be reversed via creditBalance(partnerId, deductedAmount, CREDIT_REVERSAL, txnRef, 'Reconciliation reversal'). The deducted amount is stored in the transaction record field prefund_deducted_usd. The reversal must be idempotent: if a ledger entry with entry_type=CREDIT_REVERSAL and txnRef already exists, skip (do not double-credit).
**Steps:** In the reconciliation engine handler for UNCERTAIN->FAILED transition, after setting transaction state to FAILED, check partner type; If OVERSEAS: look up prefund_deducted_usd from transaction record; Check prefunding_ledger_entry for existing CREDIT_REVERSAL with same txnRef; if found skip; Call creditBalance(partnerId, prefund_deducted_usd, CREDIT_REVERSAL, txnRef, 'Reconciliation reversal'); Log audit event: PREFUNDING_REVERSAL with amount, txnRef, partnerId
**Deliverable:** Reconciliation path update with idempotent reversal call
**Acceptance / logic checks:**
- UNCERTAIN->FAILED for OVERSEAS partner with prefund_deducted_usd=42.50: balance increased by 42.50, CREDIT_REVERSAL ledger entry created
- UNCERTAIN->FAILED called twice for same txnRef (simulated duplicate): second call skips reversal, balance not doubled
- UNCERTAIN->FAILED for LOCAL partner: creditBalance not called
- UNCERTAIN->APPROVED: no reversal (balance stays deducted)
- Audit log contains PREFUNDING_REVERSAL event with correct amount and txnRef
**Depends on:** 6.3-T11

### 6.3-T13 — Expose GET /v1/prefunding/balance endpoint for partner self-service  _(30 min)_
**Context:** Partners can query their own prefunding balance via GET /v1/prefunding/balance (authenticated with X-API-Key). Response: {partner_id, balance_usd (DECIMAL string, 2dp for display), currency: 'USD', low_balance_threshold_usd, as_of (ISO-8601 UTC)}. Only OVERSEAS partners have a balance; LOCAL partners receive 404 with code PREFUNDING_NOT_APPLICABLE. Auth: partner can only see their own balance; no cross-partner access.
**Steps:** Create GET /v1/prefunding/balance controller method; Resolve partner from X-API-Key authentication context; If partner.type == LOCAL return 404 with error code PREFUNDING_NOT_APPLICABLE; Load PrefundingAccount by partnerId; if not found return 404; Return balance_usd formatted to 2 decimal places (display only; store with 4dp), currency, threshold, as_of=now()
**Deliverable:** GET /v1/prefunding/balance endpoint in PartnerPrefundingController.java
**Acceptance / logic checks:**
- OVERSEAS partner with balance=12345.6789: response balance_usd='12345.68' (rounded to 2dp for display)
- LOCAL partner: HTTP 404 error code PREFUNDING_NOT_APPLICABLE
- Partner A cannot see Partner B balance (auth guard enforced by partner resolution from API key)
- Response field currency always 'USD'
- as_of timestamp is within 2 seconds of server time
**Depends on:** 6.3-T05

### 6.3-T14 — Unit tests: PrefundingService.deductBalance happy path and boundary  _(35 min)_
**Context:** Explicit unit test ticket for PrefundingService.deductBalance. Use Mockito to mock PrefundingAccountRepository and PrefundingLedgerEntryRepository. Test vectors cover: exact balance match, insufficient by 1 cent, zero balance, large amount. All assertions must use BigDecimal.compareTo (not equals) for numeric checks.
**Steps:** Create PrefundingServiceDeductBalanceTest.java; Mock findByPartnerIdWithLock to return a PrefundingAccount stub; Write test: balance=100.00, deduct=100.00 -> success, newBalance=0.00, ledger entry saved; Write test: balance=100.00, deduct=100.01 -> InsufficientPrefundingException; verify save never called on account; Write test: balance=0.00, deduct=0.01 -> InsufficientPrefundingException; Write test: balance=1000000.00, deduct=999999.9999 -> success, newBalance=0.0001
**Deliverable:** PrefundingServiceDeductBalanceTest.java with 4+ passing test methods
**Acceptance / logic checks:**
- All 4 tests pass with mvn test
- InsufficientPrefundingException carries requiredAmountUsd=100.01 and availableBalanceUsd=100.00 in the second test
- Ledger entry save is called exactly once on success path
- Account save is called exactly once on success path; never called on failure path
- No test uses double or float arithmetic; all amounts are new BigDecimal(String)
**Depends on:** 6.3-T06

### 6.3-T15 — Unit tests: PrefundingService low-balance alert trigger logic  _(30 min)_
**Context:** Unit tests for the alert event publishing logic. Verify that PrefundingLowBalanceEvent is published under correct conditions and not published otherwise. Use ApplicationEventPublisher mock.
**Steps:** Create PrefundingServiceAlertTest.java; Mock ApplicationEventPublisher; Test: balance drops from 15000.00 to 9500.00, threshold=10000.00, is_active=true -> event published with newBalance=9500.00; Test: balance drops from 15000.00 to 10500.00, threshold=10000.00 -> no event published (still above threshold); Test: balance drops from 9500.00 to 8000.00, threshold=10000.00, is_active=true -> event published (already below threshold but phase 1 fires every time); Test: balance drops to 9000.00, is_active=false -> no event published
**Deliverable:** PrefundingServiceAlertTest.java with 4 passing test methods
**Acceptance / logic checks:**
- publishEvent called exactly once in test 1; 0 times in test 2
- Event.newBalance == 9500.00 in test 1 (BigDecimal.compareTo)
- is_active=false suppresses event regardless of balance
- All 4 tests pass with mvn test
- Test 3 publishes event confirming phase 1 simple rule (fire every debit below threshold)
**Depends on:** 6.3-T10

### 6.3-T16 — Unit tests: HTTP 402 response shape for INSUFFICIENT_PREFUNDING  _(30 min)_
**Context:** Unit tests for GlobalExceptionHandler mapping of InsufficientPrefundingException to HTTP 402. Use MockMvc or WebMvcTest slice. Verify the exact response envelope matches API spec: HTTP 402, Content-Type application/json, body {error: {code: 'INSUFFICIENT_PREFUNDING', message: ..., request_id: ..., details: []}}.
**Steps:** Create PrefundingErrorHandlerTest.java using @WebMvcTest on a minimal test controller that throws InsufficientPrefundingException; Call POST endpoint that triggers the exception; Assert HTTP status 402; Assert response JSON: error.code == 'INSUFFICIENT_PREFUNDING'; Assert error.details is an empty array; Assert error.request_id matches X-Request-ID header
**Deliverable:** PrefundingErrorHandlerTest.java with 3+ passing tests
**Acceptance / logic checks:**
- HTTP status is exactly 402 (not 400 or 422)
- Content-Type: application/json in response
- error.code field value is exactly the string 'INSUFFICIENT_PREFUNDING'
- error.details is [] not null
- error.request_id is non-empty and matches X-Request-ID response header
**Depends on:** 6.3-T07

### 6.3-T17 — Integration test: concurrent deduction atomicity under contention  _(55 min)_
**Context:** Integration test verifying SELECT FOR UPDATE prevents overdraft when two concurrent requests both attempt to deduct from the same OVERSEAS partner account. Start both threads simultaneously; assert exactly one succeeds and one throws InsufficientPrefundingException. Uses real DB (Testcontainers PostgreSQL) to validate the FOR UPDATE locking. Balance initial state: 100.00 USD. Thread A deducts 60.00, Thread B deducts 60.00 simultaneously.
**Steps:** Create PrefundingConcurrencyIT.java with @SpringBootTest and Testcontainers PostgreSQL; Seed partner and prefunding_account with balance=100.00; Launch two threads using ExecutorService, both calling deductBalance with amount=60.00 and distinct txnRefs; Collect results; assert exactly 1 DeductionResult and 1 InsufficientPrefundingException; Assert final DB balance = 40.00 (one successful deduction of 60.00); Assert exactly 1 row in prefunding_ledger_entry for this account
**Deliverable:** PrefundingConcurrencyIT.java integration test passing
**Acceptance / logic checks:**
- Exactly one thread succeeds with newBalance=40.00
- Exactly one thread receives InsufficientPrefundingException with availableBalanceUsd=100.00
- prefunding_account.balance in DB equals 40.00 after both threads complete
- prefunding_ledger_entry has exactly 1 DEBIT_PAYMENT row for these two txnRefs
- Test passes reliably across 5 repeated runs (no flakiness)
**Depends on:** 6.3-T06, 6.3-T02

### 6.3-T18 — Integration test: MPM payment rejected before scheme when balance insufficient  _(55 min)_
**Context:** End-to-end integration test for POST /v1/payments with an OVERSEAS partner. Scenario: partner has balance=10.00 USD; payment requires payout_usd_cost=35.77 USD. Assert HTTP 402 returned, scheme adapter never called, transaction state=FAILED, no ledger entry written. Uses Testcontainers PostgreSQL and a mock scheme adapter.
**Steps:** Create InsufficientPrefundingMpmIT.java with @SpringBootTest, Testcontainers, and a MockSchemeAdapter spy; Seed OVERSEAS partner with prefunding_account.balance=10.00; Create a valid rate quote with payout_usd_cost=35.77 (seed in Redis/DB); POST /v1/payments with valid quote_id, merchant_qr, collection_amount, etc.; Assert HTTP 402 response with error.code='INSUFFICIENT_PREFUNDING'; Assert MockSchemeAdapter.submitMpm never invoked; assert transaction state=FAILED in DB; assert 0 ledger entries
**Deliverable:** InsufficientPrefundingMpmIT.java integration test passing
**Acceptance / logic checks:**
- HTTP 402 with error.code='INSUFFICIENT_PREFUNDING'
- MockSchemeAdapter.submitMpm call count == 0
- Transaction record state == 'FAILED' in DB
- prefunding_ledger_entry count for this txnRef == 0
- prefunding_account.balance remains 10.00 (unchanged)
**Depends on:** 6.3-T08, 6.3-T17

### 6.3-T19 — Integration test: CPM token generation rejected before issue when balance insufficient  _(50 min)_
**Context:** End-to-end integration test for POST /v1/payments/cpm/generate with OVERSEAS partner. Balance=50.00 USD, reservation=100.00 USD. Assert HTTP 402 returned, no CPM token generated, no ledger entry. Contrast with successful scenario (balance=200.00).
**Steps:** Create InsufficientPrefundingCpmIT.java with @SpringBootTest and Testcontainers; Seed OVERSEAS partner with balance=50.00 and cpm_reservation_usd=100.00; POST /v1/payments/cpm/generate with valid request body; Assert HTTP 402 with error.code='INSUFFICIENT_PREFUNDING'; Reseed balance=200.00; repeat request with new Idempotency-Key; Assert HTTP 201 with prefund_reserved_usd='100.00'
**Deliverable:** InsufficientPrefundingCpmIT.java with 2 test scenarios passing
**Acceptance / logic checks:**
- Insufficient scenario: HTTP 402, no row in cpm_token table for this partner_txn_ref
- Sufficient scenario: HTTP 201, prefund_reserved_usd='100.00', ledger entry created with DEBIT_PAYMENT
- Insufficient scenario: prefunding_account.balance remains 50.00
- Sufficient scenario: prefunding_account.balance = 100.00 after deduction
- Both tests are independent and pass in either order
**Depends on:** 6.3-T09

### 6.3-T20 — Integration test: UNCERTAIN->FAILED reversal credits balance correctly  _(50 min)_
**Context:** Integration test for the reconciliation-triggered reversal path. Seed an APPROVED->UNCERTAIN transaction for an OVERSEAS partner with prefund_deducted_usd=42.50 and initial balance=500.00. Trigger UNCERTAIN->FAILED reconciliation transition. Assert balance=542.50 and CREDIT_REVERSAL ledger entry exists. Then trigger same transition again (idempotency check) and assert balance stays 542.50.
**Steps:** Create ReconciliationReversalIT.java with Testcontainers; Seed OVERSEAS partner, prefunding_account.balance=500.00, one transaction in UNCERTAIN state with prefund_deducted_usd=42.50; Invoke reconciliation UNCERTAIN->FAILED handler for the transaction; Assert balance=542.50, CREDIT_REVERSAL ledger entry with txnRef and amount=42.50; Invoke handler again for same transaction (idempotency); Assert balance still 542.50, still only 1 CREDIT_REVERSAL entry
**Deliverable:** ReconciliationReversalIT.java with 2 scenario passing
**Acceptance / logic checks:**
- After first FAILED transition: balance=542.50 (500.00+42.50)
- CREDIT_REVERSAL ledger entry exists with amount=42.50 and correct txnRef
- Second invocation does not create duplicate entry
- balance after second invocation is still 542.50
- UNCERTAIN->APPROVED transition does not create any CREDIT_REVERSAL entry (verify with third scenario)
**Depends on:** 6.3-T12

### 6.3-T21 — Add prefund_deducted_usd to transaction record and MPM response  _(35 min)_
**Context:** The transaction table must store prefund_deducted_usd DECIMAL(20,4) nullable (null for LOCAL). This field is populated from DeductionResult.amount at PENDING_DEBIT->DEBITED transition. It is immutable after commit. The POST /v1/payments 201 response includes prefund_deducted_usd as a string decimal (OVERSEAS only; omitted or null for LOCAL). Field is used by the reversal path (T12) and BOK reporting.
**Steps:** Create migration V6_3_004__add_prefund_deducted_usd_to_transaction.sql: ALTER TABLE transaction ADD COLUMN prefund_deducted_usd DECIMAL(20,4); Add prefund_deducted_usd field to Transaction JPA entity (nullable BigDecimal); In orchestration handler, after successful deductBalance, set transaction.prefundDeductedUsd = result.amount; In 201 response serialization, include prefund_deducted_usd for OVERSEAS (format as string with 4dp); Verify LOCAL partner response omits or returns null for prefund_deducted_usd
**Deliverable:** Migration V6_3_004, updated Transaction entity, updated response DTO
**Acceptance / logic checks:**
- OVERSEAS payment 201 response includes prefund_deducted_usd matching payout_usd_cost to 4dp (e.g. '35.7700')
- LOCAL payment 201 response: prefund_deducted_usd is null or field absent
- Column nullable in DB: existing transaction rows accept NULL
- prefund_deducted_usd in DB matches the ledger entry amount for the same txnRef
- Field is not modifiable after commit (no setter called post-DEBITED)
**Depends on:** 6.3-T08, 6.3-T01

### 6.3-T22 — Document INSUFFICIENT_PREFUNDING error in API reference (inline code comments and OpenAPI spec)  _(25 min)_
**Context:** The OpenAPI (Swagger) spec for POST /v1/payments and POST /v1/payments/cpm/generate must document the 402 response with error code INSUFFICIENT_PREFUNDING. The error code table in the spec lists: HTTP 402, not retryable, description 'OVERSEAS partner prefunding balance too low. Top up and retry with new key.' Partners must not retry on 402; they must top up and use a new Idempotency-Key.
**Steps:** In the OpenAPI YAML/annotation for POST /v1/payments, add responses.402 with schema ref to ErrorEnvelope and example error.code='INSUFFICIENT_PREFUNDING'; Repeat for POST /v1/payments/cpm/generate 402 response; In error code constants class (e.g. ErrorCodes.java), add INSUFFICIENT_PREFUNDING = 'INSUFFICIENT_PREFUNDING' if not present; Add JavaDoc comment on InsufficientPrefundingException referencing HTTP 402 and retry guidance; Verify Swagger UI renders both 402 responses correctly
**Deliverable:** Updated OpenAPI spec (YAML or annotations) for both payment endpoints; ErrorCodes constant
**Acceptance / logic checks:**
- Swagger UI shows 402 response documented for POST /v1/payments
- Swagger UI shows 402 response documented for POST /v1/payments/cpm/generate
- Error code value in ErrorCodes.java is exactly 'INSUFFICIENT_PREFUNDING'
- OpenAPI example response for 402 includes error.code='INSUFFICIENT_PREFUNDING' and error.details=[]
- No other endpoint documents 402 (only prefunding-related endpoints return 402)
**Depends on:** 6.3-T07


## WBS 6.4 — Low-balance alerting
### 6.4-T01 — Add low_balance_alert_config table migration  _(30 min)_
**Context:** GMEPay+ stores per-partner low-balance alert settings in the table low_balance_alert_config (DAT-03 §6.3). Columns: id BIGINT PK, partner_id BIGINT FK -> partner UNIQUE NOT NULL, threshold_usd DECIMAL(20,4) NOT NULL DEFAULT 10000.00, alert_email VARCHAR(255) NOT NULL, is_active BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ, created_by VARCHAR(120), updated_by VARCHAR(120). Only OVERSEAS partners have alert configs; LOCAL partners (partner_type=LOCAL) must never have a row here. The partner table already exists.
**Steps:** Write a Flyway/Liquibase migration file creating low_balance_alert_config with all columns above; Add FK constraint partner_id -> partner(id) ON DELETE RESTRICT; Add UNIQUE constraint on partner_id (one config per partner); Add CHECK constraint: threshold_usd > 0; Add index on partner_id for fast lookup; Verify migration runs cleanly on a fresh schema and on an existing schema via rollback/reapply
**Deliverable:** Migration file V6_4_001__create_low_balance_alert_config.sql (or equivalent)
**Acceptance / logic checks:**
- Migration applies without error on a clean DB and produces table with all 10 columns
- UNIQUE constraint on partner_id rejects a second INSERT for the same partner_id
- CHECK threshold_usd > 0 rejects INSERT with threshold_usd = 0.0000
- FK constraint rejects INSERT with a non-existent partner_id
- Rollback/down migration drops the table cleanly

### 6.4-T02 — Add low_balance_threshold and alert_email columns to partner migration  _(20 min)_
**Context:** The partner table (DAT-03 §4.3) carries denormalized alert fields for convenience: low_balance_threshold_usd DECIMAL(20,4) NULL (NULL for LOCAL partners only) and low_balance_alert_email VARCHAR(255) NULL. These mirror the canonical source in low_balance_alert_config but are used by the Admin API balance-inquiry response (§4.8). The partner table already exists; this is an additive migration only.
**Steps:** Write a Flyway/Liquibase migration adding low_balance_threshold_usd DECIMAL(20,4) NULL and low_balance_alert_email VARCHAR(255) NULL to the partner table; Add comment/notes on each column per spec; Verify migration is additive (no existing rows broken); Confirm that LOCAL partner rows can have NULL in both columns
**Deliverable:** Migration file V6_4_002__add_low_balance_cols_to_partner.sql (or equivalent)
**Acceptance / logic checks:**
- Migration applies without error; existing partner rows remain intact with NULL in both new columns
- A LOCAL partner row with NULL threshold_usd and NULL alert_email is valid (no NOT NULL constraint)
- An OVERSEAS partner row can store threshold_usd = 5000.0000 and a valid email address
- Rollback removes both columns without data loss to other columns
**Depends on:** 6.4-T01

### 6.4-T03 — Define LowBalanceAlertConfig domain entity and repository interface  _(30 min)_
**Context:** The service layer needs a typed domain model for low_balance_alert_config. Fields must mirror the DB: id (Long), partnerId (Long), thresholdUsd (BigDecimal), alertEmail (String), isActive (boolean), and audit timestamps. The repository must support: findByPartnerId(Long), save(LowBalanceAlertConfig), and upsert semantics (insert or update if exists for partnerId). Use Java with JPA/Spring Data or the project ORM. OVERSEAS-only invariant is enforced at service level, not here.
**Steps:** Create LowBalanceAlertConfig entity class with all fields matching DB columns and correct JPA annotations; Create LowBalanceAlertConfigRepository interface extending JpaRepository with findByPartnerId method; Ensure thresholdUsd is mapped as BigDecimal (not double/float) to preserve precision; Add @Column annotations with correct scale (20,4) for thresholdUsd
**Deliverable:** LowBalanceAlertConfig.java entity + LowBalanceAlertConfigRepository.java interface
**Acceptance / logic checks:**
- findByPartnerId returns Optional.empty() for unknown partner
- Entity maps to table low_balance_alert_config (verify via @Table annotation or naming convention)
- thresholdUsd field type is BigDecimal, not float/double
- All audit fields (createdAt, updatedAt, createdBy, updatedBy) are present and non-null after save
**Depends on:** 6.4-T01

### 6.4-T04 — Create AlertConfigService: create, read, and update alert config for a partner  _(45 min)_
**Context:** GME Ops creates and adjusts alert configs per OVERSEAS partner via the Admin portal. The AlertConfigService must: (1) createOrUpdate(partnerId, thresholdUsd, alertEmail) - insert if no config exists, update if one does; validate that partner exists and is OVERSEAS type (partner_type=OVERSEAS); validate thresholdUsd > 0; (2) getByPartnerId(partnerId) - return config or throw NotFoundException; (3) setActive(partnerId, boolean) - toggle is_active. All changes must be audit-logged (actor from security context, before/after snapshot).
**Steps:** Implement createOrUpdate: load partner, reject if LOCAL, validate thresholdUsd > 0, upsert via repository; Implement getByPartnerId: load or throw NotFoundException; Implement setActive: load, toggle, save, audit-log; Write audit log entry with before/after snapshot for every mutation; Inject AuditLogService (existing) and use for all mutations
**Deliverable:** AlertConfigService.java with createOrUpdate, getByPartnerId, setActive methods
**Acceptance / logic checks:**
- createOrUpdate on a LOCAL partner throws InvalidPartnerTypeException (partner_type=LOCAL)
- createOrUpdate with thresholdUsd = 0 throws ValidationException
- createOrUpdate on new partner inserts row; second call with new threshold updates it (upsert semantics)
- setActive(partnerId, false) sets is_active=false; getByPartnerId reflects new value
- Every mutation generates an audit log entry with actor, timestamp, before-value, and after-value
**Depends on:** 6.4-T03

### 6.4-T05 — Admin REST endpoint: GET and PUT /admin/v1/partners/{id}/alert-config  _(45 min)_
**Context:** GME Ops manages alert configs via the Admin API. Two endpoints are required: GET /admin/v1/partners/{id}/alert-config returns the current config (200) or 404 if none. PUT /admin/v1/partners/{id}/alert-config with body {threshold_usd: number, alert_email: string, is_active: boolean} creates or replaces the config and returns the saved config (200). Both require ROLE_OPS or ROLE_SUPER_ADMIN. Return 403 if caller tries to set config on a LOCAL partner. Response body: {partner_id, threshold_usd, alert_email, is_active, updated_at, updated_by}.
**Steps:** Add GET handler calling AlertConfigService.getByPartnerId; map to response DTO; Add PUT handler validating request body (threshold_usd > 0, valid email format, is_active boolean), calling AlertConfigService.createOrUpdate; Add @PreAuthorize or equivalent RBAC check for ROLE_OPS or ROLE_SUPER_ADMIN; Return 404 for missing partner or missing config on GET; 403 for LOCAL partner on PUT; Add input validation annotations on request DTO (e.g. @Positive for threshold_usd, @Email for alert_email)
**Deliverable:** AlertConfigController.java with GET and PUT handlers and request/response DTOs
**Acceptance / logic checks:**
- GET /admin/v1/partners/{unknown_id}/alert-config returns 404
- PUT with threshold_usd=-1 returns 400 with validation error
- PUT for a LOCAL partner returns 403
- GET after a successful PUT returns exactly the values just written
- Request without OPS/SUPER_ADMIN role returns 401 or 403
**Depends on:** 6.4-T04

### 6.4-T06 — Implement low-balance alert evaluation in PrefundingLedgerService post-deduction  _(45 min)_
**Context:** After every successful atomic deduction from prefunding_account (SELECT FOR UPDATE pattern), the PrefundingLedgerService must evaluate whether the new balance triggers an alert. Rules (spec §9.5): (1) LOW alert: new balance < threshold_usd AND is_active=true for that partner's low_balance_alert_config; (2) CRITICAL/ZERO alert: new balance <= 0 (suspend partner). The alert evaluation must happen AFTER the deduction transaction commits, not inside the DB transaction. Use an async event / message: publish a prefunding.low_balance domain event with fields: partner_id, new_balance_usd, threshold_usd, alert_email, alert_type (LOW or ZERO), triggered_at.
**Steps:** After committing deduction, load low_balance_alert_config for partner (cache or DB lookup); If is_active=true and new_balance < threshold_usd, publish prefunding.low_balance event with alert_type=LOW; If new_balance <= 0, publish prefunding.low_balance event with alert_type=ZERO; Publish event via application event bus or message queue (do NOT call email directly from this layer); Ensure alert evaluation failure never rolls back or blocks the payment transaction (wrap in try/catch; log errors)
**Deliverable:** Alert evaluation logic in PrefundingLedgerService.deduct() with event publishing
**Acceptance / logic checks:**
- Deduction leaving balance=8000 for threshold=10000 publishes LOW event with correct partner_id and new_balance_usd=8000
- Deduction leaving balance=0 publishes ZERO event (not LOW)
- Deduction leaving balance=15000 for threshold=10000 publishes no alert event
- Alert evaluation exception (e.g. config not found) does NOT roll back the payment transaction
- Event payload contains alert_type, partner_id, new_balance_usd, threshold_usd, alert_email, triggered_at
**Depends on:** 6.4-T04

### 6.4-T07 — Implement LowBalanceAlertHandler: consume prefunding.low_balance event and send email  _(45 min)_
**Context:** The Notification Service (or alert handler bean) consumes prefunding.low_balance events and sends email. Email content per spec §9.5 and §9.2: For LOW alert - subject 'GMEPay+ Low Prefunding Balance - [Partner Name]'; body includes partner name, current balance (USD X,XXX.XX), configured threshold (USD X,XXX.XX), suggested top-up amount (threshold - current balance), GME account team contact. For ZERO alert - subject 'URGENT: GMEPay+ Prefunding Balance Zero - [Partner Name]'; body states all overseas payments are suspended and includes same balance details. Recipient = alert_email from event payload. Email service: SMTP relay / SES abstracted behind EmailService.send(to, subject, body).
**Steps:** Create LowBalanceAlertHandler bean subscribing to prefunding.low_balance events; For LOW type: construct email subject and body with all required fields; compute suggestedTopUp = threshold_usd - new_balance_usd; For ZERO type: construct urgent email with suspension notice; Call EmailService.send(alertEmail, subject, body); Log the alert dispatch (partner_id, alert_type, recipient, dispatched_at) to structured log; Handle EmailService exceptions with retry or dead-letter; do not lose the event silently
**Deliverable:** LowBalanceAlertHandler.java (or equivalent) consuming prefunding.low_balance events
**Acceptance / logic checks:**
- LOW event for partner SendMN with balance=8000 threshold=10000 sends email to configured address with subject containing partner name and suggested top-up of USD 2000
- ZERO event sends email with URGENT subject and mentions payments suspended
- Email body includes current balance formatted as USD 8,000.00 (2 decimal places, USD prefix)
- EmailService failure is caught and logged; handler does not propagate exception to event bus (avoids requeue loop unless retry is intended)
- No email is sent if alert_email is null or empty (guard check)
**Depends on:** 6.4-T06

### 6.4-T08 — Implement zero-balance partner suspension in PrefundingLedgerService  _(45 min)_
**Context:** Per spec §9.5 and §7.5.2: when an OVERSEAS partner balance reaches 0 or below after a deduction, all new payments for that partner must be suspended immediately. Suspension is implemented by setting partner.status = SUSPENDED (or a separate is_payment_suspended flag). The payment path already checks partner.is_active or status before proceeding. Suspension must be atomic and must not affect in-flight transactions that already have a committed deduction. The suspension is lifted manually by Ops after a top-up is recorded (via partner status update, WBS 5.x).
**Steps:** In PrefundingLedgerService, after ZERO event detection (new_balance <= 0), call PartnerService.suspendForPrefunding(partnerId) with reason ZERO_BALANCE; PartnerService.suspendForPrefunding sets partner.status = SUSPENDED and audit-logs the change with reason=ZERO_BALANCE and actor=SYSTEM; Ensure the payment validation path (before deduction) rejects new payments when partner.status = SUSPENDED with error code PARTNER_SUSPENDED; Write a unit test confirming suspension does not affect transactions that have already committed their deduction; Ensure suspension is idempotent (second call when already SUSPENDED is a no-op)
**Deliverable:** suspendForPrefunding method in PartnerService + suspension check in payment validation path
**Acceptance / logic checks:**
- After balance reaches 0, partner.status becomes SUSPENDED in the DB
- A new payment attempt for a SUSPENDED partner returns error PARTNER_SUSPENDED before any deduction
- Suspending an already-SUSPENDED partner is a no-op (no duplicate audit log entry)
- In-flight transactions whose deduction committed before suspension proceed to scheme call normally
- Audit log entry for suspension contains reason=ZERO_BALANCE, actor=SYSTEM, and timestamp
**Depends on:** 6.4-T06

### 6.4-T09 — Admin portal in-portal banner for zero-balance partner  _(45 min)_
**Context:** Per spec §9.5: when a partner balance reaches USD 0, Ops must be notified in-portal via a dashboard banner. The Admin portal (React/frontend) must display a sticky banner or alert widget on the Prefunding Monitor dashboard when any OVERSEAS partner has balance = 0 (or SUSPENDED due to ZERO_BALANCE). The backend provides this via a new field is_below_zero (boolean) in the existing GET /admin/v1/prefunding/summary endpoint response (or a new /alerts endpoint).
**Steps:** Add is_below_zero boolean field to the prefunding summary response DTO (true when balance <= 0); Update GET /admin/v1/prefunding/summary query to include the new field per partner; In the Admin portal prefunding dashboard component, render a red dismissable banner if any partner has is_below_zero=true: 'ALERT: [Partner Name] prefunding balance is zero. Payments suspended.'; Banner must be visible above the partner balance table; Banner clears automatically on next data refresh if balance > 0
**Deliverable:** Backend DTO change + frontend banner component on Prefunding Monitor dashboard
**Acceptance / logic checks:**
- Partner with balance=0 causes is_below_zero=true in summary API response
- Partner with balance=0.01 causes is_below_zero=false
- Admin portal renders red banner for any partner where is_below_zero=true
- Banner is not rendered when all partners have balance > 0
- Banner text includes the partner name (e.g. T-Bank) and the word 'suspended'
**Depends on:** 6.4-T08

### 6.4-T10 — Admin portal: low-balance status badge on prefunding dashboard  _(40 min)_
**Context:** The Admin portal Prefunding Monitor dashboard (spec wireframe §4.1 / §4.7) shows a status badge per partner: green OK when balance >= threshold, amber WARNING / LOW when balance < threshold, red CRITICAL when balance < USD 2000 (default critical threshold from spec §7.5.2). The badge is derived from the existing GET /admin/v1/prefunding/summary response. The response already includes balance_usd and low_balance_threshold_usd per partner; add a balance_status field (OK / LOW / CRITICAL / ZERO) computed server-side.
**Steps:** Add balance_status computed field to prefunding summary response: ZERO if balance<=0, CRITICAL if balance < 2000, LOW if balance < threshold_usd, else OK; Update the query/service that builds the summary response to compute balance_status; In the frontend partner balance table, render a colour-coded badge using balance_status: green=OK, amber=LOW, red=CRITICAL or ZERO; Confirm the wireframe layout: partner name, balance, threshold, badge columns; Write a snapshot/unit test for the frontend badge component covering all four states
**Deliverable:** balance_status field in prefunding summary API + coloured badge component in Admin portal
**Acceptance / logic checks:**
- balance=45200, threshold=10000 -> balance_status=OK
- balance=8400, threshold=10000 -> balance_status=LOW
- balance=1500, threshold=10000 -> balance_status=CRITICAL
- balance=0 -> balance_status=ZERO
- Frontend badge for LOW renders amber colour and text LOW (matches wireframe spec)
**Depends on:** 6.4-T09

### 6.4-T11 — Partner API balance endpoint: expose is_below_threshold flag  _(35 min)_
**Context:** The Partner API GET /v1/balance endpoint (API-05 §4.8) returns balance data to OVERSEAS partners. Per spec §4.8 response fields, the response must include: partner_id, balance_usd (string decimal), low_balance_threshold_usd (string decimal), is_below_threshold (boolean: true if balance_usd < low_balance_threshold_usd), as_of (ISO-8601 UTC). LOCAL partners calling this endpoint must receive HTTP 403. The response must reflect the live balance at query time (not a cached value older than the last deduction).
**Steps:** In PartnerBalanceController GET /v1/balance, load the partner's prefunding_account row and low_balance_alert_config row; Compute is_below_threshold = balance < low_balance_threshold_usd (use BigDecimal.compareTo, not ==); Serialize balance_usd and low_balance_threshold_usd as strings with 2 decimal places (e.g. '8400.00'); Return 403 for LOCAL partner callers; Include as_of field as current UTC timestamp
**Deliverable:** Updated GET /v1/balance handler returning is_below_threshold and low_balance_threshold_usd fields
**Acceptance / logic checks:**
- OVERSEAS partner with balance=8400 threshold=10000 gets is_below_threshold=true
- OVERSEAS partner with balance=45200 threshold=10000 gets is_below_threshold=false
- LOCAL partner gets HTTP 403
- balance_usd serialized as string '8400.00' (not numeric 8400)
- as_of timestamp is UTC ISO-8601 format e.g. 2026-06-05T09:15:00Z
**Depends on:** 6.4-T03

### 6.4-T12 — Validation: prevent setting alert config on LOCAL partner (service + API layer)  _(30 min)_
**Context:** Alert configs are OVERSEAS-only. The system must reject any attempt to create or update a low_balance_alert_config for a partner with partner_type=LOCAL (e.g. GME Remit). This guard must exist in AlertConfigService.createOrUpdate (service layer) AND in the Admin API PUT /admin/v1/partners/{id}/alert-config controller (API layer). Error response: HTTP 422 with error_code INVALID_PARTNER_TYPE and message 'Low-balance alert config is only applicable to OVERSEAS partners'.
**Steps:** In AlertConfigService.createOrUpdate, load partner and check partner_type; throw InvalidPartnerTypeException if LOCAL; In the PUT controller handler, catch InvalidPartnerTypeException and return HTTP 422 with structured error body {error_code: INVALID_PARTNER_TYPE, message: ...}; Add an integration test calling PUT for a LOCAL partner and asserting 422 response with correct error_code; Confirm that existing LOCAL partner rows in the partner table cannot have a low_balance_alert_config row (add DB-level check or document service-only enforcement)
**Deliverable:** InvalidPartnerTypeException + 422 handling in controller + integration test
**Acceptance / logic checks:**
- PUT /admin/v1/partners/{LOCAL_partner_id}/alert-config returns HTTP 422 with error_code=INVALID_PARTNER_TYPE
- AlertConfigService.createOrUpdate for a LOCAL partner throws InvalidPartnerTypeException before any DB write
- No low_balance_alert_config row is created for a LOCAL partner after the rejected call
- GET /admin/v1/partners/{LOCAL_partner_id}/alert-config returns 404 (not 422) since no config will exist
- Error message in response body contains the word 'OVERSEAS'
**Depends on:** 6.4-T05

### 6.4-T13 — Validation: threshold_usd range and email format rules  _(35 min)_
**Context:** The threshold_usd field must satisfy: greater than 0.00, maximum 9,999,999.9999 (DECIMAL(20,4) but practically bounded), and must be a positive number with up to 4 decimal places. The alert_email must be a valid RFC-5322 email address (validated via @Email annotation or regex). Multiple recipients are stored comma-separated in alert_email per spec §9.5 and portal spec §9.1 (comma-separated emails; minimum 1). The service must parse and validate each individual address when multiple are provided.
**Steps:** Update LowBalanceAlertConfigRequest DTO: add @NotNull @Positive @DecimalMax on threshold_usd; add @NotBlank on alert_email; In AlertConfigService.createOrUpdate, split alert_email on comma, trim each part, and validate each as a valid email; throw ValidationException with list of invalid addresses if any fail; Add test cases for threshold=0 (reject), threshold=0.0001 (accept), alert_email='a@b.com,c@d.com' (accept), alert_email='notanemail' (reject); Ensure error response lists which email address(es) failed validation
**Deliverable:** Updated request DTO validation + multi-email parser in AlertConfigService + unit tests
**Acceptance / logic checks:**
- threshold_usd=0 returns 400 with field error on threshold_usd
- threshold_usd=0.0001 is accepted
- alert_email='ops@gme.com,finance@gme.com' is accepted and stored as-is
- alert_email='ops@gme.com,notanemail' returns 400 listing 'notanemail' as invalid
- alert_email='' (empty string) returns 400 with error message
**Depends on:** 6.4-T05

### 6.4-T14 — Audit logging for alert config mutations (create, update, deactivate)  _(40 min)_
**Context:** Per spec §11.1 and the canonical audit rule: all config changes must log actor, timestamp, previous value, and new value, and apply only to new transactions. The audit_log table (id, entity_type, entity_id, action, actor, before_snapshot JSON, after_snapshot JSON, created_at) must receive an entry for every createOrUpdate and setActive call on low_balance_alert_config. before_snapshot is null for the first INSERT.
**Steps:** Ensure AlertConfigService.createOrUpdate writes an audit_log row: entity_type='low_balance_alert_config', entity_id=config.id, action=CREATE or UPDATE, actor from SecurityContext, before_snapshot=null or prior JSON, after_snapshot=new values as JSON; Ensure AlertConfigService.setActive writes audit_log with action=DEACTIVATE or REACTIVATE and before/after is_active values; Confirm audit_log rows are written in the same DB transaction as the config change; Write a unit test that creates, updates, and deactivates a config and asserts 3 audit_log rows with correct action values
**Deliverable:** Audit log entries emitted by AlertConfigService for all mutations
**Acceptance / logic checks:**
- First createOrUpdate for a partner produces one audit_log row with action=CREATE and before_snapshot=null
- Second createOrUpdate (update) produces audit_log row with action=UPDATE, before_snapshot containing old threshold, after_snapshot containing new threshold
- setActive(false) produces audit_log row with action=DEACTIVATE and before is_active=true
- All audit_log rows carry actor matching the authenticated user's identity (not null/anonymous)
- Rolling back the config change also rolls back the audit_log row (same transaction)
**Depends on:** 6.4-T04

### 6.4-T15 — Top-up auto-clear: re-evaluate alert state after prefunding top-up  _(40 min)_
**Context:** Per spec §8.5 (runbook): when a top-up credits a partner's prefunding balance and the new balance exceeds the threshold, the low-balance condition auto-clears. The system must: after a successful TOPUP ledger entry, evaluate the new balance against threshold; if balance >= threshold and partner was SUSPENDED due to ZERO_BALANCE, Ops must manually re-activate (not auto); however the is_below_threshold signal on the balance API and the dashboard badge must reflect the updated balance immediately. No automatic unsuspend. Optionally send a confirmation email to alert_email confirming the top-up amount and new balance (spec §9.2 'Balance confirmed top-up' email).
**Steps:** After recording a TOPUP ledger entry and updating prefunding_account.balance, reload the alert config for the partner; If new balance >= threshold_usd, do NOT publish a low_balance event (no alert); If partner is SUSPENDED with reason ZERO_BALANCE, log a warning that manual re-activation by Ops is required but do NOT auto-unsuspend; Send top-up confirmation email to alert_email: subject 'GMEPay+ Prefunding Top-Up Confirmed - [Partner Name]', body includes amount credited, new balance, and threshold; Emit a structured log entry: partner_id, top_up_amount_usd, new_balance_usd, previous_balance_usd
**Deliverable:** Post-top-up alert evaluation logic in PrefundingLedgerService.recordTopUp + confirmation email
**Acceptance / logic checks:**
- Top-up that brings balance from 8000 to 20000 (threshold=10000): no low_balance event published; confirmation email sent with new balance USD 20,000.00
- Top-up that brings balance from 0 to 5000 (threshold=10000): no auto-unsuspend; warning log emitted; LOW alert still fires on next deduction if balance stays below threshold
- Top-up confirmation email subject contains partner name and 'Top-Up Confirmed'
- Confirmation email body shows old balance, amount credited, and new balance
- If alert_email is empty/null, skip email silently (no exception thrown)
**Depends on:** 6.4-T07

### 6.4-T16 — Unit tests: alert evaluation logic in PrefundingLedgerService  _(50 min)_
**Context:** The alert evaluation logic in PrefundingLedgerService.deduct() (6.4-T06) is business-critical. This ticket writes comprehensive unit tests using mocks for AlertConfigRepository and ApplicationEventPublisher. Test the decision table: new_balance vs threshold vs is_active vs alert_type. Key cases to cover: (1) balance crosses threshold from above, (2) balance already below threshold before deduction (no re-alert), (3) balance exactly at threshold (not below), (4) is_active=false config (no alert), (5) ZERO balance, (6) no config row exists.
**Steps:** Create PrefundingLedgerServiceAlertTest class; Mock AlertConfigRepository to return various configs; Mock ApplicationEventPublisher and capture published events; Test case 1: before=12000 deduct=5000 threshold=10000 -> LOW event published; Test case 2: before=8000 deduct=1000 threshold=10000 -> LOW event published (was already below); Test case 3: before=11000 deduct=1000 threshold=10000 -> balance=10000, NOT below threshold, no event; Test case 4: before=12000 deduct=5000 threshold=10000 is_active=false -> no event; Test case 5: before=500 deduct=500 threshold=10000 -> ZERO event (balance=0); Test case 6: no config row -> no event, no exception
**Deliverable:** PrefundingLedgerServiceAlertTest.java with at least 7 test methods
**Acceptance / logic checks:**
- Test case 1 passes: LOW event published with new_balance_usd=7000
- Test case 3 passes: balance exactly at threshold=10000 does NOT publish any event (strictly less-than check)
- Test case 4 passes: is_active=false suppresses event
- Test case 5 passes: ZERO event published not LOW event when balance=0
- Test case 6 passes: missing config row results in no event and no NullPointerException
- All 7+ test methods green with no mocking framework warnings
**Depends on:** 6.4-T06

### 6.4-T17 — Unit tests: LowBalanceAlertHandler email content and routing  _(40 min)_
**Context:** The LowBalanceAlertHandler (6.4-T07) must send correctly formatted emails. Unit tests mock EmailService.send and verify the subject, body content, and recipient for both LOW and ZERO alert types. Use partner name 'T-Bank', balance USD 1,500.00, threshold USD 10,000.00 for LOW test; use balance USD 0.00 for ZERO test.
**Steps:** Create LowBalanceAlertHandlerTest class; Build a LOW PrefundingLowBalanceEvent: partner_id=2, partner_name='T-Bank', new_balance_usd=1500.00, threshold_usd=10000.00, alert_email='ops@tbank.com', alert_type=LOW; Assert EmailService.send called with recipient='ops@tbank.com', subject containing 'T-Bank', body containing 'USD 1,500.00', 'USD 10,000.00', and suggested top-up 'USD 8,500.00'; Build a ZERO event: new_balance_usd=0.00, alert_type=ZERO; Assert email subject contains 'URGENT' and body contains 'suspended'; Test that empty alert_email skips send call entirely
**Deliverable:** LowBalanceAlertHandlerTest.java with at least 4 test methods
**Acceptance / logic checks:**
- LOW test: EmailService.send called exactly once with ops@tbank.com as recipient
- LOW test: email body contains suggested top-up USD 8,500.00 (10000.00 - 1500.00)
- ZERO test: email subject contains 'URGENT' and body contains 'suspended'
- Empty alert_email test: EmailService.send NOT called
- Body contains partner name 'T-Bank' in all non-empty-email cases
**Depends on:** 6.4-T07

### 6.4-T18 — Unit tests: AlertConfigService create, update, validation, and audit  _(45 min)_
**Context:** AlertConfigService (6.4-T04) has several business rules that need unit test coverage: LOCAL partner rejection, threshold > 0, upsert semantics, audit logging. Mock PartnerRepository, LowBalanceAlertConfigRepository, and AuditLogService. Test vectors: partner_type=LOCAL -> exception; threshold_usd=0 -> exception; first create -> INSERT + audit CREATE; second call -> UPDATE + audit UPDATE; setActive(false) -> audit DEACTIVATE.
**Steps:** Create AlertConfigServiceTest class; Test createOrUpdate with LOCAL partner -> expect InvalidPartnerTypeException; Test createOrUpdate with threshold_usd=0 -> expect ValidationException; Test createOrUpdate with new OVERSEAS partner -> verify repository.save called, AuditLogService.log called with action=CREATE; Test second createOrUpdate (existing config) -> verify repository.save called with updated threshold, AuditLogService.log called with action=UPDATE; Test setActive(false) -> verify is_active=false saved, AuditLogService.log called with action=DEACTIVATE
**Deliverable:** AlertConfigServiceTest.java with at least 5 test methods
**Acceptance / logic checks:**
- LOCAL partner test throws InvalidPartnerTypeException (not a generic RuntimeException)
- threshold_usd=0 test throws ValidationException with message referencing threshold
- First create test: AuditLogService.log called with action=CREATE and before_snapshot=null
- Update test: AuditLogService.log called with action=UPDATE, before_snapshot contains old threshold
- setActive test: saved entity has is_active=false; audit log action=DEACTIVATE
**Depends on:** 6.4-T04, 6.4-T14

### 6.4-T19 — Integration test: full low-balance alert flow (deduction -> event -> email)  _(55 min)_
**Context:** An end-to-end integration test verifying the full pipeline from deduction to email dispatch. Uses an in-memory or test DB with a seeded OVERSEAS partner (partner_id=99, partner_type=OVERSEAS) with prefunding_account.balance=12000 and low_balance_alert_config.threshold_usd=10000 is_active=true alert_email='test@example.com'. Performs a deduction of 5000 USD (leaving balance=7000) and asserts: (a) balance updated, (b) LOW event published, (c) email sent to test@example.com with correct content. Uses a mock EmailService (captured calls) and a real ApplicationEventPublisher (Spring test context).
**Steps:** Seed DB with partner_id=99 OVERSEAS, prefunding_account balance=12000, alert_config threshold=10000 alert_email=test@example.com is_active=true; Call PrefundingLedgerService.deduct(partnerId=99, amount=5000) inside a test transaction (committed); Assert prefunding_account.balance=7000 in DB after commit; Assert LowBalanceAlertHandler received one LOW event with new_balance_usd=7000; Assert mock EmailService captured one send call to test@example.com with subject containing 'T-Bank' or partner name; Assert no exception propagated to caller
**Deliverable:** PrefundingAlertIntegrationTest.java using Spring Boot test slice or full context with mock EmailService
**Acceptance / logic checks:**
- DB balance=7000 after deduction
- LowBalanceAlertHandler.handle() called exactly once
- EmailService.send called with recipient=test@example.com
- Email subject contains partner name
- No exception thrown from deduct() call; return value contains new balance 7000
**Depends on:** 6.4-T07, 6.4-T16, 6.4-T17

### 6.4-T20 — Integration test: zero-balance triggers suspension and urgent email  _(45 min)_
**Context:** Verifying the zero-balance path: OVERSEAS partner_id=100 with balance=500 and threshold=10000. Deduction of 500 USD leaves balance=0. Expected: ZERO event published, partner status set to SUSPENDED, urgent email sent. Uses same test setup as 6.4-T19 but with balance=500 and deduction=500.
**Steps:** Seed DB with partner_id=100 OVERSEAS, balance=500, alert_config threshold=10000 is_active=true; Call PrefundingLedgerService.deduct(partnerId=100, amount=500); Assert prefunding_account.balance=0; Assert partner.status=SUSPENDED in DB; Assert ZERO event was published (not LOW); Assert email sent with URGENT in subject and 'suspended' in body
**Deliverable:** Zero-balance integration test in PrefundingAlertIntegrationTest.java (additional test method)
**Acceptance / logic checks:**
- DB balance=0 after deduction
- partner.status=SUSPENDED after deduction
- ZERO alert event published (not LOW)
- Urgent email sent with URGENT in subject
- Subsequent payment attempt for partner_id=100 returns PARTNER_SUSPENDED error (call payment validation after deduction)
**Depends on:** 6.4-T08, 6.4-T19

### 6.4-T21 — Admin API endpoint: GET /admin/v1/prefunding/summary with balance_status field  _(45 min)_
**Context:** The Admin portal Prefunding Monitor (spec §4.1 and §7.4) polls GET /admin/v1/prefunding/summary to display all OVERSEAS partners with their balance and status. The response must be an array of objects: {partner_id, partner_name, balance_usd (string), low_balance_threshold_usd (string), balance_status (OK|LOW|CRITICAL|ZERO), is_below_zero (boolean)}. balance_status rules: ZERO if balance<=0; CRITICAL if 0 < balance < 2000; LOW if 2000 <= balance < threshold_usd; OK if balance >= threshold_usd. Requires ROLE_OPS or ROLE_SUPER_ADMIN. Only OVERSEAS partners are included.
**Steps:** Create or update PrefundingMonitorController with GET /admin/v1/prefunding/summary; Query joins prefunding_account, partner, and low_balance_alert_config for all OVERSEAS ACTIVE and SUSPENDED partners; Compute balance_status per row using the four-tier rule above; Return response array with all specified fields; Add @PreAuthorize for ROLE_OPS or ROLE_SUPER_ADMIN
**Deliverable:** PrefundingMonitorController.java with GET /admin/v1/prefunding/summary handler + response DTO
**Acceptance / logic checks:**
- Response includes only OVERSEAS partners (LOCAL partner GME Remit absent)
- Partner with balance=45200 threshold=10000 -> balance_status=OK, is_below_zero=false
- Partner with balance=8400 threshold=10000 -> balance_status=LOW, is_below_zero=false
- Partner with balance=1500 threshold=10000 -> balance_status=CRITICAL, is_below_zero=false
- Partner with balance=0 -> balance_status=ZERO, is_below_zero=true
**Depends on:** 6.4-T10

### 6.4-T22 — Admin API endpoint: PATCH /admin/v1/partners/{id}/alert-config/status to toggle is_active  _(30 min)_
**Context:** GME Ops must be able to temporarily disable low-balance alerts for a partner (e.g. during a known maintenance window) without deleting the config. Endpoint: PATCH /admin/v1/partners/{id}/alert-config/status with body {is_active: boolean}. Returns 200 with updated config or 404 if no config exists. Requires ROLE_OPS or ROLE_SUPER_ADMIN. All changes are audit-logged via AlertConfigService.setActive.
**Steps:** Add PATCH handler in AlertConfigController for /admin/v1/partners/{id}/alert-config/status; Validate request body: is_active must be a boolean (required field); Call AlertConfigService.setActive(partnerId, isActive); Return 200 with full updated config DTO on success; 404 if no config for partner; Add @PreAuthorize for ROLE_OPS
**Deliverable:** PATCH /admin/v1/partners/{id}/alert-config/status handler in AlertConfigController.java
**Acceptance / logic checks:**
- PATCH with {is_active: false} sets config to inactive; subsequent deduction does not fire email
- PATCH with {is_active: true} re-enables alerts
- PATCH for unknown partner returns 404
- PATCH without ROLE_OPS returns 401/403
- After PATCH, GET /admin/v1/partners/{id}/alert-config reflects updated is_active value
**Depends on:** 6.4-T05

### 6.4-T23 — Structured logging for all alert events with correlation fields  _(35 min)_
**Context:** Per spec §11.2: all services must emit structured JSON logs with correlation fields txn_ref, partner_id, scheme_id, request_id. For the alerting subsystem specifically, every alert event (LOW, ZERO, email sent, email failed, suspension triggered) must emit a structured log entry at INFO or WARN level. Log fields: event_type (LOW_BALANCE_ALERT / ZERO_BALANCE_ALERT / ALERT_EMAIL_SENT / ALERT_EMAIL_FAILED / PARTNER_SUSPENDED), partner_id, balance_usd, threshold_usd, alert_email (masked: show first 3 chars and domain only, e.g. ops***@gme.com), txn_ref (if triggered by a payment), timestamp.
**Steps:** In PrefundingLedgerService, emit structured log entry after publishing LOW or ZERO event; In LowBalanceAlertHandler, emit ALERT_EMAIL_SENT log after successful EmailService.send call; In LowBalanceAlertHandler, emit ALERT_EMAIL_FAILED log on exception from EmailService.send; In PartnerService.suspendForPrefunding, emit PARTNER_SUSPENDED log entry; Mask alert_email in all log entries: replace local-part characters 4+ with *** keeping first 3 chars and full domain
**Deliverable:** Structured log statements in PrefundingLedgerService, LowBalanceAlertHandler, PartnerService matching the field spec above
**Acceptance / logic checks:**
- LOW_BALANCE_ALERT log entry contains partner_id and balance_usd after a deduction crossing threshold
- ALERT_EMAIL_SENT log entry contains masked alert_email (e.g. ops***@tbank.com not ops@tbank.com)
- PARTNER_SUSPENDED log entry contains reason=ZERO_BALANCE
- ALERT_EMAIL_FAILED log is emitted at WARN level when EmailService throws an exception
- No raw/unmasked email addresses appear in any log line for the alerting subsystem
**Depends on:** 6.4-T07, 6.4-T08

### 6.4-T24 — Admin portal: Adjust Threshold UI in Prefunding screen  _(50 min)_
**Context:** The Admin portal Prefunding screen (spec wireframe §4.7) has an [Adjust Threshold] button that opens a modal. The modal allows Ops to change threshold_usd and alert_email for a partner. On submit, it calls PUT /admin/v1/partners/{id}/alert-config. Fields: Threshold (USD, required, positive number input), Alert Email (text, required, multi-address comma-separated). Display current values pre-populated. Show success toast on save, show validation error inline (e.g. invalid email, zero threshold). Requires ROLE_OPS session.
**Steps:** Add AdjustThresholdModal component to the Prefunding screen; Pre-populate threshold_usd and alert_email fields from GET /admin/v1/partners/{id}/alert-config response; On submit, call PUT /admin/v1/partners/{id}/alert-config with updated values; Display inline validation error if threshold <= 0 or email invalid before API call; Display success toast 'Alert config updated' and close modal on 200 response; Display API error message on 422/400 responses
**Deliverable:** AdjustThresholdModal React component integrated into the Prefunding screen
**Acceptance / logic checks:**
- Modal pre-populates current threshold (e.g. USD 10,000.00) and email on open
- Submit with threshold=0 shows inline error before API call (client-side validation)
- Submit with valid values calls PUT endpoint and closes modal on success
- API 422 INVALID_PARTNER_TYPE response shows error in modal (not crash)
- Adjusted threshold is reflected immediately in the Prefunding screen threshold field after modal closes
**Depends on:** 6.4-T05

### 6.4-T25 — Docs: OpenAPI spec for alert-config endpoints (GET, PUT, PATCH)  _(35 min)_
**Context:** The three alert-config Admin API endpoints added in this work package need OpenAPI 3.0 spec entries so the auto-generated API docs and contract tests are complete. Endpoints: GET /admin/v1/partners/{id}/alert-config, PUT /admin/v1/partners/{id}/alert-config, PATCH /admin/v1/partners/{id}/alert-config/status. Each needs paths, request body schemas, response schemas, and error codes (400, 403, 404, 422).
**Steps:** In the admin-api.yaml OpenAPI spec file, add path /admin/v1/partners/{id}/alert-config with GET and PUT operations; Add path /admin/v1/partners/{id}/alert-config/status with PATCH operation; Define LowBalanceAlertConfigRequest schema (threshold_usd number, alert_email string, is_active boolean); Define LowBalanceAlertConfigResponse schema (partner_id, threshold_usd, alert_email, is_active, updated_at, updated_by); Add error response refs for 400, 403, 404, 422 using existing error schema components; Validate spec with swagger-parser or equivalent - zero errors
**Deliverable:** Updated admin-api.yaml with three new operation entries and two new schema components
**Acceptance / logic checks:**
- swagger-parser validates admin-api.yaml with zero errors
- GET /admin/v1/partners/{id}/alert-config operation has 200 and 404 response codes defined
- PUT operation documents 400 (validation), 403 (INVALID_PARTNER_TYPE), and 422 response codes
- LowBalanceAlertConfigRequest schema marks threshold_usd as type: number, format: decimal, minimum: 0 (exclusive)
- LowBalanceAlertConfigResponse schema includes all 7 fields listed above
**Depends on:** 6.4-T05, 6.4-T22


## WBS 6.5 — Ledger reconciliation & statements
### 6.5-T01 — Add DB migration: reconciliation_run table  _(25 min)_
**Context:** GMEPay+ needs a table to record every daily reconciliation execution for audit and idempotency. Fields: id BIGINT PK, run_date DATE NOT NULL, scheme_id BIGINT FK qr_scheme, run_type VARCHAR(30) CHECK IN ('POOL_IDENTITY','PREFUNDING_LEDGER','SETTLEMENT_AGGREGATE','REVENUE_MONTHLY'), status VARCHAR(20) CHECK IN ('RUNNING','COMPLETED','FAILED','PARTIAL'), started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), completed_at TIMESTAMPTZ, items_checked INT DEFAULT 0, items_matched INT DEFAULT 0, items_discrepant INT DEFAULT 0, error_detail TEXT, created_by VARCHAR(120). UNIQUE(run_date, scheme_id, run_type). Flyway migration V6_5_001.
**Steps:** Create Flyway migration file V6_5_001__add_reconciliation_run.sql; Define table reconciliation_run with all columns, constraints, and CHECK values listed in context; Add index on (run_date, run_type) for daily query performance; Add index on (status) for polling incomplete runs; Run migration against local PostgreSQL on port 5433; verify with \d reconciliation_run
**Deliverable:** Flyway migration file V6_5_001__add_reconciliation_run.sql
**Acceptance / logic checks:**
- Table exists with correct columns and types after migration
- UNIQUE constraint on (run_date, scheme_id, run_type) rejects duplicate insert
- CHECK constraint on status rejects value 'UNKNOWN'
- CHECK constraint on run_type rejects value 'INVALID'
- Index on (run_date, run_type) present in pg_indexes

### 6.5-T02 — Add DB migration: recon_discrepancy table  _(25 min)_
**Context:** Each reconciliation run may produce discrepancy rows needing human review. Table recon_discrepancy: id BIGINT PK, run_id BIGINT FK reconciliation_run NOT NULL, discrepancy_type VARCHAR(40) CHECK IN ('POOL_IDENTITY_BREACH','PREFUNDING_LEDGER_MISMATCH','SETTLEMENT_COUNT_MISMATCH','SETTLEMENT_AMOUNT_MISMATCH','UNCERTAIN_UNRESOLVED','REVENUE_MISMATCH'), txn_ref VARCHAR(64), scheme_ref VARCHAR(128), expected_value DECIMAL(20,8), actual_value DECIMAL(20,8), delta DECIMAL(20,8), ccy CHAR(3), resolution_status VARCHAR(20) CHECK IN ('UNRESOLVED','RESOLVED','ESCALATED') DEFAULT 'UNRESOLVED', resolved_by VARCHAR(120), resolved_at TIMESTAMPTZ, resolution_note TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(). Index on (run_id), (resolution_status). Flyway V6_5_002.
**Steps:** Create Flyway migration V6_5_002__add_recon_discrepancy.sql; Define table with all columns, FKs, CHECK constraints from context; Add indexes on run_id and resolution_status; Run migration and verify schema; Confirm ON DELETE RESTRICT on run_id FK prevents orphan discrepancy rows
**Deliverable:** Flyway migration file V6_5_002__add_recon_discrepancy.sql
**Acceptance / logic checks:**
- Table exists with correct structure after migration
- FK run_id → reconciliation_run rejects unknown run_id
- CHECK on discrepancy_type rejects 'OTHER'
- CHECK on resolution_status rejects 'PENDING'
- Default resolution_status is UNRESOLVED on insert
**Depends on:** 6.5-T01

### 6.5-T03 — Add DB migration: ledger_statement table  _(20 min)_
**Context:** Partner-facing prefunding ledger statements are generated on demand or on schedule. Table ledger_statement: id BIGINT PK, partner_id BIGINT FK partner NOT NULL, statement_period_start DATE NOT NULL, statement_period_end DATE NOT NULL, opening_balance_usd DECIMAL(20,4), closing_balance_usd DECIMAL(20,4), total_debits_usd DECIMAL(20,4) DEFAULT 0, total_credits_usd DECIMAL(20,4) DEFAULT 0, debit_count INT DEFAULT 0, credit_count INT DEFAULT 0, status VARCHAR(20) CHECK IN ('DRAFT','FINAL') DEFAULT 'DRAFT', generated_at TIMESTAMPTZ, generated_by VARCHAR(120), file_path VARCHAR(512), created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(). UNIQUE(partner_id, statement_period_start, statement_period_end). Flyway V6_5_003. Only OVERSEAS partners (partner.type = 'OVERSEAS') ever have rows here.
**Steps:** Create Flyway migration V6_5_003__add_ledger_statement.sql; Define table with all columns and constraints from context; Add index on (partner_id, statement_period_start); Run migration and verify; Confirm UNIQUE constraint prevents duplicate statement for same partner and period
**Deliverable:** Flyway migration file V6_5_003__add_ledger_statement.sql
**Acceptance / logic checks:**
- Table exists with all columns after migration
- UNIQUE constraint on (partner_id, statement_period_start, statement_period_end) fires on duplicate
- CHECK on status rejects 'ARCHIVED'
- opening_balance_usd and closing_balance_usd allow NULL (computed at generation time)
- Index on (partner_id, statement_period_start) present

### 6.5-T04 — Implement PoolIdentityReconJob: daily per-transaction pool identity check  _(45 min)_
**Context:** RATE-04 §13.1 mandates a daily background job that re-checks every APPROVED cross-border transaction: |collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost| < 0.01 USD. The job queries the transaction table for status=APPROVED, committed_at within the target date, and is_same_currency=false. For each row the identity check is recomputed; any breach inserts a row into recon_discrepancy (type POOL_IDENTITY_BREACH, txn_ref, expected_value = payout_usd_cost, actual_value = collection_usd-collection_margin_usd-payout_margin_usd, delta = actual-expected). All values use BigDecimal arithmetic (no float). The job records its run in reconciliation_run (type POOL_IDENTITY). Service class: PoolIdentityReconJob in package com.gmepayplus.recon.
**Steps:** Create PoolIdentityReconJob Spring @Component with @Scheduled(cron) placeholder; Implement query: SELECT id, txn_ref, collection_usd, collection_margin_usd, payout_margin_usd, payout_usd_cost FROM transaction WHERE status='APPROVED' AND DATE(committed_at AT TIME ZONE 'Asia/Seoul')=:runDate AND (is_same_currency IS NULL OR is_same_currency=false); For each row compute delta = (collection_usd - collection_margin_usd - payout_margin_usd) - payout_usd_cost using BigDecimal; If abs(delta) >= 0.01 insert recon_discrepancy row with type POOL_IDENTITY_BREACH; Insert/update reconciliation_run row with counts and status COMPLETED or PARTIAL
**Deliverable:** PoolIdentityReconJob.java with runForDate(LocalDate) method
**Acceptance / logic checks:**
- For a transaction with collection_usd=36.9714, collection_margin_usd=0.3697, payout_margin_usd=0.3697, payout_usd_cost=36.2319: delta=0.0001 < 0.01 → no discrepancy row inserted
- For a synthetic row with delta=0.05: discrepancy_type=POOL_IDENTITY_BREACH is inserted with expected_value=36.2319, actual_value=36.2369, delta=0.0500
- Same-currency transactions (is_same_currency=true) are excluded from the check
- reconciliation_run row status=COMPLETED with items_checked=N, items_discrepant=M after run
- BigDecimal is used throughout; no double or float casts in the computation path
**Depends on:** 6.5-T01, 6.5-T02

### 6.5-T05 — Implement PrefundingLedgerReconJob: daily ledger vs balance check  _(45 min)_
**Context:** RATE-04 §13.3: daily reconciliation for each OVERSEAS partner: sum of all DEBIT_PAYMENT entries (amount) for the date + sum of DEBIT_REVERSAL entries (negative) + sum of CREDIT_TOPUP entries + sum of CREDIT_REVERSAL entries must equal (closing_balance - opening_balance) in the prefunding_account table for that date. prefunding_ledger_entry.entry_type IN ('DEBIT_PAYMENT','DEBIT_REVERSAL','CREDIT_TOPUP','CREDIT_REVERSAL'); DEBIT entries reduce balance, CREDIT entries increase it. Tolerance: 0.0001 USD. On mismatch insert recon_discrepancy type PREFUNDING_LEDGER_MISMATCH with expected_value=computed_net_change, actual_value=balance_delta, delta, ccy='USD'. Service class: PrefundingLedgerReconJob.
**Steps:** Create PrefundingLedgerReconJob Spring @Component; For each partner with type=OVERSEAS, fetch prefunding_account.balance at start-of-day and end-of-day snapshots (use min/max balance_after from prefunding_ledger_entry for that account_id and date range); Compute net_change_from_entries = sum(CREDIT entries) - sum(DEBIT entries) for the date, using entry_type and amount; Compute balance_delta = closing_balance - opening_balance from the ledger entry balance_after snapshots; If abs(net_change_from_entries - balance_delta) > 0.0001 USD, insert PREFUNDING_LEDGER_MISMATCH discrepancy; Record run in reconciliation_run with type PREFUNDING_LEDGER
**Deliverable:** PrefundingLedgerReconJob.java with runForDate(LocalDate) method
**Acceptance / logic checks:**
- Partner with 3 DEBIT_PAYMENT entries (2.00, 1.50, 0.75 USD) and 1 CREDIT_TOPUP (100.00 USD): net_change = +95.75; if balance_after snapshots agree → no discrepancy
- If balance_delta=95.74 and net_change=95.75: delta=0.01 > 0.0001 → PREFUNDING_LEDGER_MISMATCH inserted with expected=95.75, actual=95.74
- LOCAL partners (type=LOCAL) are skipped entirely
- Partner with no ledger entries on the run date: items_checked includes it, delta=0.00 no discrepancy
- reconciliation_run row correctly counts total partners checked and discrepant partners
**Depends on:** 6.5-T01, 6.5-T02

### 6.5-T06 — Implement SettlementAggregateReconJob: daily payout sum vs ZP0062/ZP0064 totals  _(50 min)_
**Context:** RATE-04 §13.2: daily, sum of target_payout (KRW BIGINT) for all APPROVED transactions in the settlement date must match the total reported in the ZP0062 (morning settlement result) and ZP0064 (afternoon settlement result) files from 한결원. settlement_batch table (file_type IN ('ZP0062','ZP0064'), settlement_date, status=RECEIVED) has total_amount and transaction_count. GMEPay+ computes its own sum from the transaction table (status=APPROVED, settlement_date matches). Count mismatch → SETTLEMENT_COUNT_MISMATCH discrepancy; amount mismatch (tolerance 0 KRW for exact integer match) → SETTLEMENT_AMOUNT_MISMATCH. Also verifies ZP0061/ZP0063 outbound counts match GMEPay+ outbound count for that date. Service class: SettlementAggregateReconJob.
**Steps:** Create SettlementAggregateReconJob Spring @Component; Query GMEPay+ approved transaction count and sum(target_payout) for the settlement_date; Query settlement_batch.total_amount and settlement_batch.transaction_count for ZP0062 and ZP0064 for same date (sum both morning and afternoon); Compare counts: if mismatch insert SETTLEMENT_COUNT_MISMATCH with expected_value=gme_count, actual_value=scheme_count; Compare amounts: if mismatch insert SETTLEMENT_AMOUNT_MISMATCH with expected_value=gme_sum_krw, actual_value=scheme_sum_krw in ccy=KRW; Record run in reconciliation_run type SETTLEMENT_AGGREGATE; flag status=PARTIAL if settlement_batch rows not yet received (status != RECEIVED)
**Deliverable:** SettlementAggregateReconJob.java with runForDate(LocalDate) method
**Acceptance / logic checks:**
- GME sum=1,500,000 KRW / count=3, ZP0062 total=1,500,000 / count=3 → no discrepancy, items_matched=1
- GME sum=1,500,000 / count=3, ZP0062+ZP0064 sum=1,400,000 / count=3 → SETTLEMENT_AMOUNT_MISMATCH with expected=1500000, actual=1400000, ccy=KRW
- GME count=3, scheme count=2 → SETTLEMENT_COUNT_MISMATCH inserted
- If ZP0062 batch not yet received (status=PENDING) → run status=PARTIAL, no false discrepancy
- KRW values compared as BIGINT arithmetic with zero tolerance
**Depends on:** 6.5-T01, 6.5-T02

### 6.5-T07 — Implement RevenueReconService: monthly FX margin and service fee aggregation  _(40 min)_
**Context:** RATE-04 §13.4: monthly revenue reconciliation. FX margin revenue = sum(collection_margin_usd + payout_margin_usd) across all APPROVED cross-border transactions in the period. Service fee revenue = sum(service_charge) per currency. Both are read from the revenue_record table (fx_margin_usd, service_charge_amount, service_charge_ccy). The monthly run produces a summary object: period (first/last day), partner_id, fx_margin_usd_total DECIMAL(20,4), service_fee_totals map(ccy→amount). No scheme_fee_share computed here (that requires ZeroPay's external statement). Inserts reconciliation_run type REVENUE_MONTHLY. Service: RevenueReconService.computeMonthlyRevenue(YearMonth, Long partnerId).
**Steps:** Create RevenueReconService Spring @Service; Implement query against revenue_record: WHERE revenue_date BETWEEN period_start AND period_end AND partner_id=:partnerId AND txn is cross-border (join transaction on is_same_currency=false); Sum fx_margin_usd across matched rows; group service_charge_amount by service_charge_ccy; Return MonthlyRevenueSummary DTO with fields: period_start, period_end, partner_id, fx_margin_usd_total, service_fee_map; Log result to reconciliation_run with type REVENUE_MONTHLY, items_checked=transaction count; Throw if partnerId is null (compute for all partners if null)
**Deliverable:** RevenueReconService.java with computeMonthlyRevenue(YearMonth, Long) and MonthlyRevenueSummary DTO
**Acceptance / logic checks:**
- 3 cross-border transactions with collection_margin_usd=0.37+0.50+0.20 and payout_margin_usd=0.37+0.50+0.20: fx_margin_total=2.28 USD
- Service charges KRW 500, KRW 500, USD 0.36: service_fee_map={KRW=1000, USD=0.36}
- Same-currency transactions are excluded from fx_margin sum
- Null partnerId aggregates all partners; specific partnerId returns only that partner's rows
- reconciliation_run row inserted with type REVENUE_MONTHLY and correct items_checked count
**Depends on:** 6.5-T01

### 6.5-T08 — Implement UncertainTransactionAlertJob: flag UNCERTAIN txns unresolved > 24h  _(35 min)_
**Context:** SAD-02 §5.4: if an UNCERTAIN transaction is not resolved within 24 hours of its SCHEME_SENT timestamp it must trigger an ops alert and enter the manual exception queue. Table: transaction.status='UNCERTAIN', and the transaction_event with step=SCHEME_SENT has occurred_at more than 24h ago. The job inserts a recon_discrepancy row with type UNCERTAIN_UNRESOLVED for each overdue transaction, then fires a notification (log at ERROR level; wire to existing notification service). Runs every hour via @Scheduled. Service class: UncertainTransactionAlertJob.
**Steps:** Create UncertainTransactionAlertJob Spring @Component with @Scheduled(fixedDelay=3600000); Query: SELECT t.id, t.txn_ref, e.occurred_at FROM transaction t JOIN transaction_event e ON e.txn_id=t.id AND e.step='SCHEME_SENT' WHERE t.status='UNCERTAIN' AND e.occurred_at < NOW() - INTERVAL '24 hours'; For each result insert recon_discrepancy if no existing UNRESOLVED row for same txn_ref and type UNCERTAIN_UNRESOLVED (idempotent); Log each overdue txn_ref at ERROR level with occurred_at and age in hours; Record run summary in reconciliation_run type POOL_IDENTITY (reuse) or add UNCERTAIN run_type via migration if needed
**Deliverable:** UncertainTransactionAlertJob.java
**Acceptance / logic checks:**
- Transaction with SCHEME_SENT 25 hours ago and status=UNCERTAIN → discrepancy row inserted with type UNCERTAIN_UNRESOLVED
- Transaction with SCHEME_SENT 23 hours ago → no row inserted
- Idempotency: running job twice for same overdue txn → exactly one UNRESOLVED discrepancy row, not two
- Transaction with SCHEME_SENT 25h ago but status=APPROVED (resolved) → not flagged
- Log entry at ERROR level contains txn_ref and age when overdue item found
**Depends on:** 6.5-T01, 6.5-T02

### 6.5-T09 — Implement LedgerStatementGenerator: build prefunding statement for a partner and period  _(45 min)_
**Context:** Generates a ledger_statement row and associated line-item data for an OVERSEAS partner. Algorithm: (1) determine opening_balance_usd = balance_after of the last prefunding_ledger_entry before period_start for that account_id; if none, opening=0; (2) sum all entries within period: DEBIT_PAYMENT and DEBIT_REVERSAL reduce balance, CREDIT_TOPUP and CREDIT_REVERSAL increase it; (3) closing_balance = opening + net; (4) insert/update ledger_statement row with all computed fields, status=DRAFT; (5) all amounts in USD DECIMAL(20,4). Service: LedgerStatementGenerator.generate(Long partnerId, LocalDate periodStart, LocalDate periodEnd). Reject if partner type != OVERSEAS.
**Steps:** Create LedgerStatementGenerator Spring @Service; Validate partner exists and type=OVERSEAS; throw PartnerTypeException otherwise; Fetch opening_balance: MAX(created_at) prefunding_ledger_entry before period_start for partner's account_id; Compute totals: sum CREDIT entries (CREDIT_TOPUP + CREDIT_REVERSAL), sum DEBIT entries (DEBIT_PAYMENT + DEBIT_REVERSAL), counts; Compute closing_balance_usd = opening + total_credits - total_debits; Upsert ledger_statement row (status=DRAFT) returning saved entity
**Deliverable:** LedgerStatementGenerator.java with generate() method
**Acceptance / logic checks:**
- Partner with opening balance 10000.00 USD, 2 DEBIT_PAYMENT (36.97, 50.00) and 1 CREDIT_TOPUP (1000.00) in period: closing=10913.03, total_debits=86.97, total_credits=1000.00, debit_count=2, credit_count=1
- No entries before period_start → opening_balance_usd=0.00
- Calling generate() twice for same partner/period upserts the same row (no duplicate)
- LOCAL partner throws PartnerTypeException before any DB writes
- period_start > period_end throws IllegalArgumentException
**Depends on:** 6.5-T03

### 6.5-T10 — Implement LedgerStatementFinalizer: finalize and persist statement as FINAL  _(40 min)_
**Context:** After review, an operator finalizes a DRAFT ledger_statement. Finalization: (1) validates that reconciliation_run for PREFUNDING_LEDGER on the same date range has status=COMPLETED with items_discrepant=0 (or operator explicitly overrides with a note); (2) sets ledger_statement.status=FINAL, generated_at=NOW(), generated_by=operator_id; (3) writes audit_log row (entity_type='ledger_statement', action='UPDATE', before_value, after_value). Method: LedgerStatementFinalizer.finalize(Long statementId, String operatorId, boolean forceOverride, String overrideNote). Service class in com.gmepayplus.recon.
**Steps:** Create LedgerStatementFinalizer Spring @Service; Load ledger_statement by id; verify status=DRAFT, else throw IllegalStateException; Check reconciliation_run completion: query reconciliation_run WHERE run_date BETWEEN stmt.period_start AND stmt.period_end AND run_type=PREFUNDING_LEDGER; if items_discrepant > 0 and forceOverride=false throw ReconIncompleteException; Update ledger_statement status=FINAL, generated_at, generated_by; Write audit_log row with before_value (status=DRAFT) and after_value (status=FINAL), actor_id=operatorId; Return updated LedgerStatement entity
**Deliverable:** LedgerStatementFinalizer.java
**Acceptance / logic checks:**
- DRAFT statement with completed clean recon run → status set to FINAL, generated_at non-null
- DRAFT statement with items_discrepant=1 and forceOverride=false → ReconIncompleteException, statement unchanged
- DRAFT statement with items_discrepant=1 and forceOverride=true, overrideNote='Approved by Finance' → finalized; audit_log row contains overrideNote in after_value
- Calling finalize on a FINAL statement → IllegalStateException
- audit_log.before_value.status='DRAFT', after_value.status='FINAL' after successful finalization
**Depends on:** 6.5-T03, 6.5-T05

### 6.5-T11 — Implement ReconciliationReportBuilder: assemble the daily recon report  _(35 min)_
**Context:** The parent deliverable is a Recon Report. ReconciliationReportBuilder assembles a structured ReconReport DTO for a given run_date and scheme_id from: (1) all reconciliation_run rows for that date/scheme; (2) all recon_discrepancy rows linked to those runs; (3) summary counts: total_checked, total_matched, total_discrepant, total_resolved. Output DTO: ReconReport { run_date, scheme_id, runs: List<RunSummary>, discrepancies: List<DiscrepancySummary>, total_checked, total_discrepant, total_resolved, generated_at }. Service: ReconciliationReportBuilder.build(LocalDate, Long schemeId).
**Steps:** Create ReconciliationReportBuilder Spring @Service; Query all reconciliation_run rows for run_date and scheme_id (or all schemes if schemeId null); For each run, query associated recon_discrepancy rows; Build RunSummary (run_id, run_type, status, items_checked, items_discrepant) list; Build DiscrepancySummary (discrepancy_type, txn_ref, delta, ccy, resolution_status) list; Aggregate totals and return ReconReport DTO with generated_at=NOW()
**Deliverable:** ReconciliationReportBuilder.java and ReconReport/RunSummary/DiscrepancySummary DTOs
**Acceptance / logic checks:**
- Report for date with 2 runs (POOL_IDENTITY: 100 checked, 0 discrepant; PREFUNDING_LEDGER: 5 checked, 1 discrepant): total_checked=105, total_discrepant=1, total_resolved=0
- Resolved discrepancy (resolution_status=RESOLVED) increments total_resolved
- Null schemeId includes all schemes in totals
- Empty date (no runs) returns ReconReport with all counts=0, runs=[], discrepancies=[]
- DiscrepancySummary.delta is BigDecimal, not double, in serialized output
**Depends on:** 6.5-T01, 6.5-T02

### 6.5-T12 — Expose GET /internal/v1/recon/report endpoint  _(30 min)_
**Context:** Admin operators need to fetch the daily recon report via REST. Endpoint: GET /internal/v1/recon/report?runDate=YYYY-MM-DD&schemeId={id}. Requires ROLE_OPS_ADMIN. Returns ReconReport JSON (see 6.5-T11). runDate defaults to yesterday KST if omitted. Returns 200 with report body; 400 if runDate format invalid; 404 if no runs found. Controller: ReconReportController in com.gmepayplus.admin.recon. The /internal/ prefix is behind the internal gateway and not exposed to partners.
**Steps:** Create ReconReportController @RestController @RequestMapping('/internal/v1/recon'); Add GET /report handler with @RequestParam(required=false) String runDate and @RequestParam(required=false) Long schemeId; Parse runDate to LocalDate (KST default = yesterday if null); return 400 on parse error; Call ReconciliationReportBuilder.build(); if result has zero runs return 404; Apply @PreAuthorize('hasRole(ROLE_OPS_ADMIN)'); Return 200 with ReconReport JSON body
**Deliverable:** ReconReportController.java with GET /internal/v1/recon/report handler
**Acceptance / logic checks:**
- GET /internal/v1/recon/report?runDate=2026-06-04 returns 200 with correct report body
- GET /internal/v1/recon/report?runDate=invalid returns 400
- GET /internal/v1/recon/report for date with no runs returns 404
- Request without ROLE_OPS_ADMIN returns 403
- runDate omitted defaults to yesterday (KST offset +09:00) not UTC
**Depends on:** 6.5-T11

### 6.5-T13 — Expose GET /internal/v1/recon/discrepancies endpoint with filtering  _(35 min)_
**Context:** Admin operators need to query open discrepancies to action them. Endpoint: GET /internal/v1/recon/discrepancies?runDate=&runType=&resolutionStatus=UNRESOLVED&page=0&size=20. ROLE_OPS_ADMIN required. Returns Page<DiscrepancySummary> sorted by created_at DESC. Filters: runDate (optional), run_type (optional), resolution_status (default UNRESOLVED). Controller: ReconDiscrepancyController. Reuses DiscrepancySummary DTO from 6.5-T11 plus run_id and run_type fields.
**Steps:** Create ReconDiscrepancyController @RestController; Implement GET /discrepancies with @RequestParam filters (runDate, runType, resolutionStatus default UNRESOLVED) and Pageable; Build JPA Specification or JPQL query joining recon_discrepancy→reconciliation_run filtering by supplied params; Return Page<DiscrepancySummary> with pagination metadata; Apply @PreAuthorize('hasRole(ROLE_OPS_ADMIN)'); Return 400 for invalid runType enum value
**Deliverable:** ReconDiscrepancyController.java with GET /internal/v1/recon/discrepancies handler
**Acceptance / logic checks:**
- Filter resolutionStatus=UNRESOLVED returns only UNRESOLVED rows
- Filter runType=POOL_IDENTITY returns only POOL_IDENTITY discrepancies
- Pagination: page=0&size=5 with 12 rows returns 5 items and totalElements=12
- Invalid runType value returns 400
- No ROLE_OPS_ADMIN → 403
**Depends on:** 6.5-T11

### 6.5-T14 — Expose PATCH /internal/v1/recon/discrepancies/{id}/resolve endpoint  _(35 min)_
**Context:** Ops operators must be able to mark a discrepancy as RESOLVED or ESCALATED. PATCH /internal/v1/recon/discrepancies/{id}/resolve. Body: { resolutionStatus: 'RESOLVED'|'ESCALATED', resolutionNote: string (required, max 500 chars) }. Sets resolved_by=authenticated user, resolved_at=NOW(), resolution_status, resolution_note. Requires ROLE_OPS_ADMIN. Writes audit_log row (entity_type='recon_discrepancy', action='UPDATE'). Returns 200 updated DiscrepancySummary. 404 if id not found. 409 if already RESOLVED or ESCALATED.
**Steps:** Add PATCH /discrepancies/{id}/resolve to ReconDiscrepancyController; Validate body: resolutionStatus IN (RESOLVED, ESCALATED), resolutionNote not blank and <= 500 chars; Load recon_discrepancy; return 404 if absent; return 409 if resolution_status != UNRESOLVED; Update resolution_status, resolved_by (from SecurityContext), resolved_at=NOW(), resolution_note; Write audit_log row: entity_type=recon_discrepancy, action=UPDATE, before/after values; Return 200 with updated DiscrepancySummary
**Deliverable:** PATCH /resolve handler in ReconDiscrepancyController
**Acceptance / logic checks:**
- UNRESOLVED discrepancy resolved with note → status=RESOLVED, resolved_by=operator id, resolved_at non-null
- Already RESOLVED → 409 with no DB change
- resolutionNote empty string → 400 validation error
- resolutionNote 501 chars → 400 validation error
- audit_log row present with before_value.resolution_status=UNRESOLVED and after_value.resolution_status=RESOLVED
**Depends on:** 6.5-T13

### 6.5-T15 — Expose GET /internal/v1/recon/statements endpoint for ledger statements  _(30 min)_
**Context:** Operators and internal systems need to list and view partner ledger statements. GET /internal/v1/recon/statements?partnerId=&status=&periodStart=&periodEnd= returns Page<LedgerStatementSummary> (id, partner_id, period_start, period_end, opening_balance_usd, closing_balance_usd, total_debits_usd, total_credits_usd, status, generated_at). ROLE_OPS_ADMIN required. GET /internal/v1/recon/statements/{id} returns full LedgerStatement. 404 if not found. Controller: LedgerStatementController.
**Steps:** Create LedgerStatementController @RestController @RequestMapping('/internal/v1/recon/statements'); Implement GET / with Pageable and optional filters (partnerId, status, periodStart, periodEnd); Implement GET /{id} returning full LedgerStatement entity as DTO; Return 404 if statement id not found; Apply @PreAuthorize('hasRole(ROLE_OPS_ADMIN)') on class; Add LedgerStatementSummary DTO mapping from ledger_statement table
**Deliverable:** LedgerStatementController.java with GET list and GET by-id handlers
**Acceptance / logic checks:**
- GET /statements?partnerId=1 returns only statements for partner 1
- GET /statements?status=FINAL returns only FINAL statements
- GET /statements/{valid_id} returns full statement with all balance fields
- GET /statements/{unknown_id} returns 404
- GET /statements without ROLE_OPS_ADMIN returns 403
**Depends on:** 6.5-T09

### 6.5-T16 — Expose POST /internal/v1/recon/statements/generate endpoint  _(30 min)_
**Context:** Operators trigger statement generation on demand. POST /internal/v1/recon/statements/generate body: { partnerId: Long, periodStart: date, periodEnd: date }. Calls LedgerStatementGenerator.generate(). Returns 201 with created LedgerStatement. 400 if partner is LOCAL type or period invalid. 409 if a FINAL statement already exists for that period. ROLE_OPS_ADMIN required.
**Steps:** Add POST /generate to LedgerStatementController; Validate request body: partnerId not null, periodStart <= periodEnd; Call LedgerStatementGenerator.generate(); catch PartnerTypeException → 400; Check if existing FINAL statement exists for partner/period; if so return 409; Return 201 with created statement body; Apply @PreAuthorize('hasRole(ROLE_OPS_ADMIN)')
**Deliverable:** POST /generate handler in LedgerStatementController
**Acceptance / logic checks:**
- Valid OVERSEAS partner and period → 201 with statement body, status=DRAFT
- LOCAL partner → 400 with error code PARTNER_TYPE_INVALID
- periodStart > periodEnd → 400
- Already-FINAL statement for same period → 409, no new row created
- DRAFT statement for same period → generates new run and upserts (idempotent), returns 201
**Depends on:** 6.5-T09, 6.5-T15

### 6.5-T17 — Expose POST /internal/v1/recon/statements/{id}/finalize endpoint  _(25 min)_
**Context:** Operators finalize a DRAFT statement. POST /internal/v1/recon/statements/{id}/finalize body: { forceOverride: bool, overrideNote: string (required when forceOverride=true) }. Calls LedgerStatementFinalizer.finalize(). Returns 200 with updated statement. 404 if not found, 409 if already FINAL, 422 if recon not clean and forceOverride=false. ROLE_OPS_ADMIN required.
**Steps:** Add POST /{id}/finalize to LedgerStatementController; Load statement; return 404 if absent; return 409 if status=FINAL; Validate: if forceOverride=true, overrideNote must not be blank → 400; Call LedgerStatementFinalizer.finalize(); catch ReconIncompleteException → 422 with detail; Return 200 with updated statement; Apply @PreAuthorize('hasRole(ROLE_OPS_ADMIN)')
**Deliverable:** POST /finalize handler in LedgerStatementController
**Acceptance / logic checks:**
- DRAFT statement with clean recon → 200, status=FINAL in response
- FINAL statement → 409
- forceOverride=true with blank overrideNote → 400
- forceOverride=false with open discrepancies → 422
- forceOverride=true with note='Finance approved' and open discrepancies → 200, status=FINAL
**Depends on:** 6.5-T10, 6.5-T15

### 6.5-T18 — Schedule PoolIdentityReconJob and PrefundingLedgerReconJob via cron  _(30 min)_
**Context:** Both daily recon jobs must run automatically. PoolIdentityReconJob fires at 06:00 KST (21:00 UTC prev day) to process prior day's committed transactions. PrefundingLedgerReconJob fires at 06:15 KST (21:15 UTC prev day). Both jobs must be idempotent — if run twice for the same date they must not insert duplicate reconciliation_run or recon_discrepancy rows. Use Spring @Scheduled with cron expressions and a DB-level idempotency guard (check for existing reconciliation_run with same run_date + run_type before creating new run). Jobs are disabled in test profile via @ConditionalOnProperty.
**Steps:** Add @Scheduled(cron='0 0 21 * * *', zone='UTC') to PoolIdentityReconJob.run() method; Add @Scheduled(cron='0 15 21 * * *', zone='UTC') to PrefundingLedgerReconJob.run() method; In each job's run() method, compute runDate = LocalDate.now(ZoneId.of('Asia/Seoul')).minusDays(1); Add idempotency check: if reconciliation_run exists for (runDate, run_type) and status=COMPLETED skip execution; log INFO; Add @ConditionalOnProperty(name='recon.scheduler.enabled', matchIfMissing=true) to both job beans; Verify cron fires via integration test with @TestPropertySource disabling schedule and calling runForDate() directly
**Deliverable:** @Scheduled annotations and idempotency guard in PoolIdentityReconJob and PrefundingLedgerReconJob
**Acceptance / logic checks:**
- PoolIdentityReconJob.run() invoked at 21:00 UTC computes runDate = Seoul date - 1 day correctly across midnight boundary
- Second invocation for same runDate with existing COMPLETED run → skipped, no duplicate reconciliation_run row
- Cron expression '0 0 21 * * *' zone=UTC verified to fire at 06:00 KST next day
- recon.scheduler.enabled=false disables bean instantiation in test context
- PrefundingLedgerReconJob runs 15 minutes after PoolIdentityReconJob (21:15 UTC)
**Depends on:** 6.5-T04, 6.5-T05

### 6.5-T19 — Schedule SettlementAggregateReconJob after ZP0062/ZP0064 receipt windows  _(30 min)_
**Context:** SettlementAggregateReconJob must run after ZP0062 (available ~10:00 KST) and again after ZP0064 (available ~19:00 KST) to reconcile morning and afternoon settlement results. Run at 10:30 KST (01:30 UTC) for morning window and 19:30 KST (10:30 UTC) for afternoon window. Idempotency: for each window check if settlement_batch rows for that file_type and date have status=RECEIVED before running; if not, record reconciliation_run status=PARTIAL. Same idempotency guard as 6.5-T18.
**Steps:** Add @Scheduled(cron='0 30 1 * * *', zone='UTC') for morning run and @Scheduled(cron='0 30 10 * * *', zone='UTC') for afternoon run to SettlementAggregateReconJob; Each trigger calls runForDate(LocalDate) passing Seoul current date or prior day as appropriate; Add pre-check: if ZP0062/ZP0064 batch not yet RECEIVED for the date, mark run PARTIAL and return early without inserting false discrepancies; Apply same idempotency guard (skip if COMPLETED run already exists); Add @ConditionalOnProperty(name='recon.scheduler.enabled') guard; Document the two windows and their ZP file dependencies in class Javadoc
**Deliverable:** @Scheduled triggers in SettlementAggregateReconJob with PARTIAL guard
**Acceptance / logic checks:**
- Morning trigger fires at 01:30 UTC (10:30 KST) and uses correct settlement_date
- ZP0062 batch with status=PENDING → run stored as PARTIAL, zero discrepancy rows
- ZP0062 batch with status=RECEIVED and correct totals → COMPLETED run, zero discrepancies
- Second run for same date after first COMPLETED → skipped by idempotency guard
- Afternoon run at 10:30 UTC processes ZP0064 file independently of morning
**Depends on:** 6.5-T06, 6.5-T18

### 6.5-T20 — Implement OpsAlertService integration for reconciliation discrepancies  _(35 min)_
**Context:** SAD-02 §7.8: recon discrepancies and UNCERTAIN overdue items must trigger ops alerts. OpsAlertService already handles low_balance alerts (topic prefunding.low_balance). Add a new alert type RECON_DISCREPANCY. When PoolIdentityReconJob, PrefundingLedgerReconJob, or SettlementAggregateReconJob produce items_discrepant > 0, they call OpsAlertService.sendReconAlert(RunSummary). Alert includes: run_date, run_type, scheme_id, items_discrepant, link to GET /internal/v1/recon/discrepancies. In Phase 1, alert is logged at ERROR level and queued on the 'ops.alerts' internal event topic. Actual email dispatch is handled by the existing Notification Service consumer.
**Steps:** Add sendReconAlert(RunSummary summary) to OpsAlertService; Format alert payload with run_date, run_type, scheme_id, items_discrepant, discrepancy_link; Publish event to ops.alerts topic (or log ERROR with structured JSON in Phase 1 if topic not yet wired); Call sendReconAlert in PoolIdentityReconJob, PrefundingLedgerReconJob, and SettlementAggregateReconJob after run completes if items_discrepant > 0; Call sendReconAlert from UncertainTransactionAlertJob when overdue txns found; Add unit test: mock OpsAlertService; verify called once per run with items_discrepant=2
**Deliverable:** OpsAlertService.sendReconAlert() and its invocations in all four recon jobs
**Acceptance / logic checks:**
- PoolIdentityReconJob with 2 discrepant items → sendReconAlert called once with items_discrepant=2
- Run with items_discrepant=0 → sendReconAlert NOT called
- Alert payload contains run_date, run_type, scheme_id as structured fields
- UncertainTransactionAlertJob with 1 overdue txn → alert fired with txn_ref in payload
- Phase 1: if ops.alerts topic absent, falls back to ERROR log without throwing exception
**Depends on:** 6.5-T04, 6.5-T05, 6.5-T06, 6.5-T08

### 6.5-T21 — Unit tests for PoolIdentityReconJob logic  _(40 min)_
**Context:** Test coverage for the pool identity check logic in PoolIdentityReconJob. Uses mocked TransactionRepository and ReconDiscrepancyRepository. Test vectors from RATE-04 §4.3 and §5.4: nominal case collection_usd=36.9714, collection_margin_usd=0.3697, payout_margin_usd=0.3697, payout_usd_cost=36.2319 → delta=0.0001 < 0.01 → no discrepancy. Breach case: collection_usd=36.9714, collection_margin_usd=0.3697, payout_margin_usd=0.3697, payout_usd_cost=36.2220 → delta=0.01 → discrepancy. Same-currency exclusion: is_same_currency=true → skipped. All arithmetic in BigDecimal.
**Steps:** Create PoolIdentityReconJobTest in src/test/java; Mock TransactionRepository.findApprovedCrossBorderForDate() returning controlled list of transaction projections; Test case 1: delta=0.0001 → verify save() on ReconDiscrepancyRepository NOT called; Test case 2: delta=0.0100 → verify save() called once with expected_value=36.2220, actual_value=36.2320, delta=0.0100; Test case 3: is_same_currency=true → verify transaction skipped (save not called even if delta>0.01 set); Test case 4: empty transaction list → reconciliation_run status=COMPLETED with items_checked=0
**Deliverable:** PoolIdentityReconJobTest.java with at least 4 test cases
**Acceptance / logic checks:**
- All 4 test cases pass with @Test assertions
- No floating-point comparison in test assertions (use BigDecimal compareTo or assertThat with precision)
- Test case 2 verifies discrepancy_type=POOL_IDENTITY_BREACH
- Test case 3 verifies same-currency transaction produces zero discrepancy rows
- Coverage report shows > 90% line coverage on PoolIdentityReconJob.runForDate()
**Depends on:** 6.5-T04

### 6.5-T22 — Unit tests for PrefundingLedgerReconJob logic  _(40 min)_
**Context:** Test the ledger vs balance reconciliation logic. Mock PrefundingAccountRepository and PrefundingLedgerEntryRepository. Test vectors: Partner A has opening balance 10000.00, 2 DEBIT_PAYMENT (36.97, 50.00), 1 CREDIT_TOPUP (200.00); net_change=+113.03; balance_after snapshots give delta=113.03 → no discrepancy. Mismatch case: same entries but balance_after gives delta=113.00 → delta=0.03 > 0.0001 → PREFUNDING_LEDGER_MISMATCH discrepancy. LOCAL partner exclusion: partner type=LOCAL → skipped. Edge: no entries in period → no discrepancy, items_checked includes partner.
**Steps:** Create PrefundingLedgerReconJobTest; Mock OVERSEAS partner list and ledger entry repository; Test case 1 (balanced): net_change=113.03, balance_delta=113.03 → no discrepancy; Test case 2 (mismatch): net_change=113.03, balance_delta=113.00 → PREFUNDING_LEDGER_MISMATCH with delta=0.03; Test case 3: LOCAL partner included in partner list → not processed, items_checked excludes it; Test case 4: no entries in period → delta=0.00, no discrepancy, items_checked=1
**Deliverable:** PrefundingLedgerReconJobTest.java with at least 4 test cases
**Acceptance / logic checks:**
- Test case 1 passes: save() on DiscrepancyRepo not called
- Test case 2 passes: save() called with type=PREFUNDING_LEDGER_MISMATCH, expected=113.03, actual=113.00, ccy=USD
- Test case 3 passes: LOCAL partner not counted in items_checked
- Test case 4 passes: items_checked=1, items_discrepant=0
- All BigDecimal comparisons use scale-aware comparison (not == or equals without scale normalisation)
**Depends on:** 6.5-T05

### 6.5-T23 — Unit tests for SettlementAggregateReconJob logic  _(40 min)_
**Context:** Test the settlement count/amount comparison logic. Mock TransactionRepository and SettlementBatchRepository. Test vectors: GME sum=1,500,000 KRW count=3; ZP0062+ZP0064 combined sum=1,500,000 count=3 → matched. Count mismatch: GME count=3, scheme count=2 → SETTLEMENT_COUNT_MISMATCH. Amount mismatch: GME sum=1,500,000 KRW, scheme sum=1,400,000 → SETTLEMENT_AMOUNT_MISMATCH with delta=100000 KRW. Partial: ZP0062 status=PENDING → run PARTIAL, no discrepancy. Edge: zero transactions for date → items_checked=0, COMPLETED.
**Steps:** Create SettlementAggregateReconJobTest; Mock GME transaction aggregation returning (count, sum) pairs; Mock settlement_batch repository returning ZP0062 and ZP0064 rows with controlled status and totals; Test case 1 (match): GME=3/1500000, scheme=3/1500000 → COMPLETED, no discrepancy; Test case 2 (count mismatch): GME=3, scheme=2 → SETTLEMENT_COUNT_MISMATCH discrepancy; Test case 3 (amount mismatch): GME sum=1500000, scheme sum=1400000 → SETTLEMENT_AMOUNT_MISMATCH delta=100000 ccy=KRW; Test case 4 (PARTIAL): ZP0062 not RECEIVED → run=PARTIAL, no discrepancy rows saved
**Deliverable:** SettlementAggregateReconJobTest.java with at least 4 test cases
**Acceptance / logic checks:**
- Test case 1: items_matched=1, items_discrepant=0, status=COMPLETED
- Test case 2: discrepancy type=SETTLEMENT_COUNT_MISMATCH, expected=3, actual=2
- Test case 3: discrepancy type=SETTLEMENT_AMOUNT_MISMATCH, delta=100000, ccy=KRW
- Test case 4: status=PARTIAL, save() on DiscrepancyRepo not called
- KRW amounts stored and compared as BIGINT (Long), not DECIMAL
**Depends on:** 6.5-T06

### 6.5-T24 — Unit tests for LedgerStatementGenerator  _(35 min)_
**Context:** Test statement generation logic. Mock PrefundingAccountRepository and PrefundingLedgerEntryRepository. Test vectors from spec: partner opening=10000.00, entries: DEBIT_PAYMENT 36.97, DEBIT_PAYMENT 50.00, CREDIT_TOPUP 1000.00 → closing=10913.03, total_debits=86.97, total_credits=1000.00, debit_count=2, credit_count=1. Edge: no prior entries (opening=0.00). Rejection: LOCAL partner throws PartnerTypeException. Idempotency: second call for same period upserts, not inserts duplicate.
**Steps:** Create LedgerStatementGeneratorTest with mocked repositories; Test case 1 (standard): opening=10000.00, 3 entries as above → assert closing=10913.03, debit_count=2, credit_count=1; Test case 2 (no prior entries): opening_balance=0.00 → closing = net of period entries; Test case 3 (LOCAL partner): expect PartnerTypeException thrown before any repo call; Test case 4 (idempotency): mock existing DRAFT statement → updated, not inserted again; result has same id; Verify all amounts are BigDecimal/DECIMAL not double
**Deliverable:** LedgerStatementGeneratorTest.java with at least 4 test cases
**Acceptance / logic checks:**
- Test case 1 passes: closing_balance_usd=10913.03 within DECIMAL(20,4) precision
- Test case 2 passes: opening=0.00, closing=net_change for period
- Test case 3: PartnerTypeException thrown; LedgerStatementRepository.save() never called
- Test case 4: existing DRAFT statement id unchanged after second generate call
- period_end < period_start test: IllegalArgumentException thrown
**Depends on:** 6.5-T09

### 6.5-T25 — Unit tests for ReconciliationReportBuilder  _(30 min)_
**Context:** Test the report assembly logic. Mock ReconciliationRunRepository and ReconDiscrepancyRepository. Test vectors: 2 runs for date (POOL_IDENTITY: 100 checked, 0 discrepant; PREFUNDING_LEDGER: 5 checked, 1 discrepant). 1 discrepancy row with resolution_status=UNRESOLVED. Report: total_checked=105, total_discrepant=1, total_resolved=0. Second scenario: discrepancy RESOLVED → total_resolved=1. Empty date: all counts zero.
**Steps:** Create ReconciliationReportBuilderTest; Mock run repository returning 2 run rows and discrepancy repository returning 1 UNRESOLVED discrepancy; Test case 1: assert total_checked=105, total_discrepant=1, total_resolved=0, runs.size=2, discrepancies.size=1; Test case 2: discrepancy resolution_status=RESOLVED → total_resolved=1; Test case 3: no runs for date → ReconReport with all counts=0, empty lists, generated_at non-null; Verify generated_at is set and after test start time
**Deliverable:** ReconciliationReportBuilderTest.java with at least 3 test cases
**Acceptance / logic checks:**
- Test case 1 passes with all aggregate counts correct
- Test case 2 passes with total_resolved=1
- Test case 3 returns empty report with non-null generated_at
- DiscrepancySummary in report contains txn_ref, delta, discrepancy_type, resolution_status
- Report DTO is serializable to JSON without errors (use Jackson ObjectMapper in test)
**Depends on:** 6.5-T11

### 6.5-T26 — Integration test: full daily recon cycle for a synthetic partner  _(55 min)_
**Context:** End-to-end integration test using @SpringBootTest with test PostgreSQL (Testcontainers). Inserts 3 APPROVED cross-border transactions with valid pool identity, 1 prefunding account with matching ledger entries, and 1 settlement_batch row (ZP0062 RECEIVED). Runs PoolIdentityReconJob.runForDate(), PrefundingLedgerReconJob.runForDate(), and SettlementAggregateReconJob.runForDate() directly. Asserts zero discrepancies, COMPLETED run rows, and correct ReconciliationReportBuilder output. Also inserts 1 transaction with pool identity breach and asserts 1 POOL_IDENTITY_BREACH discrepancy row.
**Steps:** Create ReconIntegrationTest @SpringBootTest with Testcontainers PostgreSQL; Insert test partner (OVERSEAS), prefunding_account, 3 valid APPROVED transactions using canonical RATE-04 §4.3 values; Insert matching prefunding_ledger_entry rows (opening→closing consistent); Insert settlement_batch ZP0062 RECEIVED with total matching GME sum; Run all three recon jobs for the test date; assert zero discrepancies and all runs COMPLETED; Insert 1 transaction with pool identity breach (payout_usd_cost off by 0.05); re-run PoolIdentityReconJob; assert 1 POOL_IDENTITY_BREACH row
**Deliverable:** ReconIntegrationTest.java passing with Testcontainers
**Acceptance / logic checks:**
- Clean data scenario: all 3 reconciliation_run rows status=COMPLETED, recon_discrepancy count=0
- Pool breach scenario: exactly 1 recon_discrepancy row with type=POOL_IDENTITY_BREACH after re-run
- ReconciliationReportBuilder.build() returns total_checked=3 (transactions) for clean run
- Second invocation of each job for same date does not create duplicate reconciliation_run rows
- Test runs successfully with @ActiveProfiles('test') and recon.scheduler.enabled=false
**Depends on:** 6.5-T04, 6.5-T05, 6.5-T06, 6.5-T11

### 6.5-T27 — Implement ReconReport CSV export: GET /internal/v1/recon/report/export  _(35 min)_
**Context:** Finance and Ops need to export the daily recon report as CSV for offline review. GET /internal/v1/recon/report/export?runDate=YYYY-MM-DD produces a CSV file. Columns: run_type, discrepancy_type, txn_ref, scheme_ref, expected_value, actual_value, delta, ccy, resolution_status. One row per recon_discrepancy for the date. Empty report → CSV with headers only. Content-Type: text/csv; filename: recon_report_{runDate}.csv. Uses ReconciliationReportBuilder output. ROLE_OPS_ADMIN required.
**Steps:** Add GET /report/export to ReconReportController; Call ReconciliationReportBuilder.build(runDate, null) to get full report; Map discrepancies list to CSV rows using OpenCSV or manual StringBuilder; Set response Content-Type: text/csv, Content-Disposition: attachment; filename=recon_report_{runDate}.csv; Return 200 with CSV body; 404 if no runs for date; 400 if date invalid; Include header row even when discrepancy list is empty
**Deliverable:** GET /report/export handler in ReconReportController
**Acceptance / logic checks:**
- 3 discrepancies for date → CSV has header row + 3 data rows
- Empty discrepancies → CSV has header row only, 200 not 404
- Content-Disposition header contains recon_report_2026-06-04.csv for runDate=2026-06-04
- Invalid date format → 400
- No ROLE_OPS_ADMIN → 403
**Depends on:** 6.5-T12

### 6.5-T28 — Implement LedgerStatement CSV line-item export  _(35 min)_
**Context:** Partners and Finance need a line-item CSV of prefunding_ledger_entry rows for a statement period. GET /internal/v1/recon/statements/{id}/export produces CSV. Columns: entry_type, txn_ref, amount, balance_after, note, created_at. Rows ordered by created_at ASC. Content-Type: text/csv; filename: statement_{partner_id}_{period_start}_{period_end}.csv. Only FINAL or DRAFT statements can be exported (any status). ROLE_OPS_ADMIN required. Statement 404 if not found.
**Steps:** Add GET /{id}/export to LedgerStatementController; Load ledger_statement by id; 404 if not found; Query prefunding_ledger_entry WHERE account_id = statement.partner's account_id AND created_at BETWEEN period_start 00:00 UTC AND period_end 23:59:59 UTC ORDER BY created_at ASC; Map rows to CSV: entry_type, txn_ref, amount (formatted to 4dp), balance_after, note, created_at ISO-8601; Set Content-Disposition filename using partner_id and period dates; Return 200 with CSV; header row always present
**Deliverable:** GET /{id}/export handler in LedgerStatementController
**Acceptance / logic checks:**
- Statement with 3 ledger entries → CSV has header + 3 rows in created_at order
- FINAL and DRAFT statements both exportable
- Content-Disposition contains correct partner_id and period in filename
- Statement id not found → 404
- amount column uses 4 decimal places (e.g. 36.9714 not 36.97)
**Depends on:** 6.5-T15

### 6.5-T29 — Add recon DB indexes and query performance validation  _(40 min)_
**Context:** Reconciliation queries scan large tables: transaction (millions of rows), prefunding_ledger_entry (many per partner per day). Required indexes (if not already present from earlier migrations): transaction(status, committed_at) for pool identity scan; transaction(settlement_date) for settlement aggregate; prefunding_ledger_entry(account_id, created_at) already specified in DAT-03 - verify it exists; reconciliation_run(run_date, run_type); recon_discrepancy(run_id). Add EXPLAIN ANALYZE test confirming index usage for each recon query. Flyway V6_5_004.
**Steps:** Create Flyway migration V6_5_004__add_recon_indexes.sql; Add index on transaction(status, committed_at) if absent; Add index on transaction(settlement_date) if absent; Verify prefunding_ledger_entry(account_id, created_at) exists via pg_indexes; add if absent; Run EXPLAIN ANALYZE on each of the 3 main recon queries against test DB with 10k+ synthetic rows; Assert Seq Scan on transaction not present for status+committed_at filter (should use index)
**Deliverable:** Flyway migration V6_5_004__add_recon_indexes.sql and EXPLAIN output in PR description
**Acceptance / logic checks:**
- EXPLAIN ANALYZE for pool identity query uses Index Scan on transaction(status, committed_at)
- EXPLAIN ANALYZE for prefunding ledger query uses Index Scan on prefunding_ledger_entry(account_id, created_at)
- EXPLAIN ANALYZE for settlement aggregate query uses Index Scan on transaction(settlement_date)
- Migration runs cleanly and is idempotent (CREATE INDEX IF NOT EXISTS)
- No existing migration broken by duplicate index name
**Depends on:** 6.5-T01, 6.5-T02

### 6.5-T30 — Document recon runbook: daily procedures and escalation  _(40 min)_
**Context:** OPS-13 requires an operational runbook for settlement and reconciliation procedures. Create recon-runbook.md covering: daily job schedule (times, run types), how to query open discrepancies via the API, how to resolve vs escalate, forced-finalize procedure for ledger statements, UNCERTAIN txn escalation path (manual exception queue), alert email setup. Must reference real endpoint URLs, field names, and error codes from this work-package. Audience: GME Ops team with no code access.
**Steps:** Create docs/ops/recon-runbook.md; Section 1: Daily schedule table (job name, cron time KST, run_type, source files); Section 2: Querying discrepancies (GET /internal/v1/recon/discrepancies with example curl); Section 3: Resolving vs escalating (PATCH /discrepancies/{id}/resolve, when to use ESCALATED); Section 4: Ledger statement generation and finalization (POST /generate, POST /finalize, forceOverride); Section 5: UNCERTAIN transaction escalation (24h threshold, manual exception queue entry procedure)
**Deliverable:** docs/ops/recon-runbook.md
**Acceptance / logic checks:**
- Section 1 contains correct KST times: PoolIdentity 06:00, PrefundingLedger 06:15, Settlement 10:30 and 19:30
- Section 2 includes a valid example curl with resolutionStatus=UNRESOLVED filter
- Section 3 distinguishes RESOLVED (self-service) from ESCALATED (Finance approval required) cases
- Section 4 explains forceOverride=true requires overrideNote, referenced to 422 error for missing note
- Section 5 states 24h SLA from SCHEME_SENT timestamp and names the ops.alerts alert topic
**Depends on:** 6.5-T12, 6.5-T13, 6.5-T14, 6.5-T16, 6.5-T17
