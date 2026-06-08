# security-platform  (platform)

**Scope:** Security arch, Vault, threat model, audit pipeline, privacy, pen test

**Owned WBS work-packages:** 3.6, 13.1, 13.3, 13.4, 13.5, 13.6, 13.10  ·  **Tickets:** 199  ·  **Est:** 146.2h

> Self-contained backlog for this service. Build in its own module against `shared-libs` contracts. Each ticket has a deliverable + acceptance checks.


## WBS 3.6 — Audit log tables (immutable)
### 3.6-T01 — Create Flyway migration: audit_log base table in config-registry PostgreSQL  _(25 min)_
**Context:** WBS 3.6 - Audit log tables (immutable). The audit_log table lives in the config-registry PostgreSQL 16 schema (same DB as partner, scheme, rule, treasury_rate). Columns per DAT-03 §10.7: id BIGINT PK auto-increment, actor_id VARCHAR(120) NOT NULL (operator user ID or SYSTEM), actor_type VARCHAR(20) NOT NULL CHECK IN ('OPERATOR','SYSTEM','PARTNER'), action VARCHAR(20) NOT NULL CHECK IN ('CREATE','UPDATE','DELETE','ACTIVATE','DEACTIVATE'), entity_type VARCHAR(50) NOT NULL (table name e.g. rule, partner, treasury_rate), entity_id BIGINT NOT NULL, before_value JSONB NULL (NULL for CREATE), after_value JSONB NULL (NULL for DELETE), occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), ip_address INET NULL, request_id VARCHAR(64) NULL. No UPDATE or DELETE ever permitted. Retention >= 7 years per SEC-09 §6.2.
**Steps:** In services/config-registry/src/main/resources/db/migration/ create V201__audit_log.sql; Define the table with all 11 columns, check constraints on actor_type and action; Add index on (entity_type, entity_id) for config entity history queries; Add index on (actor_id, occurred_at) for actor timeline queries; Add index on occurred_at for retention monitoring queries; do NOT add any FK from entity_id to other tables (audit must survive entity deletes)
**Deliverable:** services/config-registry/src/main/resources/db/migration/V201__audit_log.sql
**Acceptance / logic checks:**
- Migration applies cleanly via Flyway on a fresh PostgreSQL 16 Testcontainers instance with no errors
- actor_type column rejects values outside ('OPERATOR','SYSTEM','PARTNER') with a check-constraint violation
- action column rejects values outside ('CREATE','UPDATE','DELETE','ACTIVATE','DEACTIVATE')
- before_value and after_value accept NULL and valid JSONB without error
- All three indexes exist in pg_indexes after migration

### 3.6-T02 — Add prev_hash column to audit_log for SHA-256 tamper-evident hash chain  _(15 min)_
**Context:** SEC-09 §6.3 requires each audit record to include a hash of the previous record (prev_hash) to form a tamper-evident chain. Hash algorithm: SHA-256 hex string (64 chars). The first record has prev_hash = NULL (genesis). Subsequent rows store SHA256(prev.id||'|'||prev.actor_id||'|'||prev.occurred_at::text||'|'||coalesce(prev.before_value::text,'')||'|'||coalesce(prev.after_value::text,'')) computed at insert time by the application. Column: prev_hash VARCHAR(64) NULL. A daily integrity check job (3.6-T13) walks the chain and alerts on breaks (P1 severity per SEC-09 §10.4).
**Steps:** Create V202__audit_log_hash_chain.sql in services/config-registry/src/main/resources/db/migration/; ALTER TABLE audit_log ADD COLUMN prev_hash VARCHAR(64) NULL; Add index on prev_hash for chain-walk queries; Add comment documenting that first row has prev_hash = NULL (genesis sentinel) and subsequent rows contain SHA-256 of previous row canonical string
**Deliverable:** services/config-registry/src/main/resources/db/migration/V202__audit_log_hash_chain.sql
**Acceptance / logic checks:**
- Migration V202 applies cleanly after V201 on Testcontainers PostgreSQL 16
- prev_hash column exists with VARCHAR(64) nullable type
- Index on prev_hash is present in pg_indexes
- Existing rows default to NULL without error
**Depends on:** 3.6-T01

### 3.6-T03 — Flyway migration: REVOKE UPDATE/DELETE on audit_log + append-only trigger  _(25 min)_
**Context:** SEC-09 §6.3 and DAT-03 §9.1 mandate no UPDATE or DELETE on audit_log. Enforce at DB level as defense-in-depth: (1) REVOKE UPDATE, DELETE on audit_log FROM the application DB role gme_app; (2) create a PL/pgSQL trigger function that raises EXCEPTION 'audit_log is append-only' on BEFORE UPDATE OR DELETE FOR EACH ROW. The application role is gme_app; migration owner (superuser) is gme_flyway. Module: services/config-registry Flyway scripts.
**Steps:** Create V203__audit_log_immutable_grants.sql in services/config-registry/src/main/resources/db/migration/; REVOKE UPDATE, DELETE ON TABLE audit_log FROM gme_app; keep INSERT and SELECT; Create PL/pgSQL function audit_log_immutable() RETURNS TRIGGER that raises EXCEPTION 'audit_log is append-only'; Create trigger BEFORE UPDATE OR DELETE ON audit_log FOR EACH ROW EXECUTE FUNCTION audit_log_immutable(); Verify INSERT and SELECT remain permitted for gme_app
**Deliverable:** services/config-registry/src/main/resources/db/migration/V203__audit_log_immutable_grants.sql
**Acceptance / logic checks:**
- After migration, UPDATE audit_log SET actor_id='x' WHERE id=1 as gme_app raises a privilege error
- After migration, DELETE FROM audit_log WHERE id=1 as gme_app raises a privilege error
- INSERT as gme_app succeeds without error
- SELECT as gme_app succeeds without error
- Trigger function audit_log_immutable exists in pg_proc after migration
**Depends on:** 3.6-T02

### 3.6-T04 — Define AuditLogEntry domain record and enums in lib-events module  _(20 min)_
**Context:** All services that write audit entries share a canonical domain type. Place in lib-events module under com.gme.pay.events.audit. Java 21 record AuditLogEntry with fields: Long id (null before persist), String actorId, AuditActorType actorType, AuditAction action, String entityType, Long entityId, String beforeValueJson (nullable), String afterValueJson (nullable), Instant occurredAt, String ipAddress (nullable), String requestId (nullable), String prevHash (nullable), String actorRole (nullable). Enums: AuditActorType {OPERATOR, SYSTEM, PARTNER}; AuditAction {CREATE, UPDATE, DELETE, ACTIVATE, DEACTIVATE}. No JPA or Spring annotations - pure domain type.
**Steps:** Create lib-events/src/main/java/com/gme/pay/events/audit/AuditActorType.java enum with 3 values; Create AuditAction.java enum with 5 values in same package; Create AuditLogEntry.java as a Java record with all 13 fields; Confirm no JPA, Spring, or persistence imports in any of the 3 files; Build lib-events with ./gradlew :lib-events:build to verify compilation
**Deliverable:** lib-events/src/main/java/com/gme/pay/events/audit/AuditLogEntry.java (plus AuditAction.java and AuditActorType.java)
**Acceptance / logic checks:**
- Module compiles with Java 21 with no warnings
- AuditAction enum has exactly 5 values matching the DB check constraint
- AuditActorType enum has exactly 3 values matching the DB check constraint
- AuditLogEntry is a Java record (immutable by construction)
- No import from org.springframework or javax.persistence in any file
**Depends on:** 3.6-T01

### 3.6-T05 — Implement AuditLogJpaEntity and AuditLogRepository in config-registry  _(35 min)_
**Context:** The config-registry Spring Boot 3.x service owns the audit_log PostgreSQL 16 table. Create a Hibernate @Entity mapping audit_log (including prev_hash and actor_role columns from V202/V204) and a Spring Data JPA repository. The entity must be INSERT-only: annotate with Hibernate @Immutable to prevent dirty-checking; never expose an update path. Map JSONB columns before_value/after_value as String via @Column with columnDefinition='jsonb'. Module: services/config-registry.
**Steps:** Create AuditLogJpaEntity.java in services/config-registry/src/main/java/com/gme/pay/configregistry/audit/ with @Entity @Table(name='audit_log') @Immutable and all 13 mapped columns; Map id with @Id @GeneratedValue(strategy=GenerationType.IDENTITY); Map occurred_at with @Column(insertable=true, updatable=false) @CreationTimestamp; Create AuditLogRepository.java extending JpaRepository<AuditLogJpaEntity,Long> with findTopByOrderByIdDesc() and findByEntityTypeAndEntityIdOrderByOccurredAtDesc(Pageable); Ensure no merge/saveAndFlush path would issue UPDATE SQL
**Deliverable:** services/config-registry/src/main/java/com/gme/pay/configregistry/audit/AuditLogJpaEntity.java and AuditLogRepository.java
**Acceptance / logic checks:**
- JPA entity maps all 13 columns with correct snake_case @Column names
- @Immutable prevents Hibernate dirty-checking (no UPDATE SQL in SQL logs)
- findTopByOrderByIdDesc() returns the most-recent row in a Testcontainers PostgreSQL integration test
- JSONB columns round-trip as String ('{}' inserted and retrieved without corruption)
- Calling repository.save() on a detached entity with an existing id does NOT issue an UPDATE statement
**Depends on:** 3.6-T03, 3.6-T04

### 3.6-T06 — Implement AuditHashChainService: compute SHA-256 prev_hash inside transaction  _(30 min)_
**Context:** SEC-09 §6.3 hash chain: each new audit entry's prev_hash = SHA256(canonical string of previous row). Canonical string: prev.id + '|' + prev.actorId + '|' + prev.occurredAt.toString() + '|' + coalesce(prev.beforeValueJson,'') + '|' + coalesce(prev.afterValueJson,''). Genesis row (first insert): prev_hash = NULL. Must be called inside the same DB transaction as the INSERT to avoid race conditions. Use java.security.MessageDigest('SHA-256'); StandardCharsets.UTF_8 for all byte conversions. Return 64-char lowercase hex or null. Module: services/config-registry.
**Steps:** Create AuditHashChainService.java in services/config-registry/src/main/java/com/gme/pay/configregistry/audit/ annotated @Service; Inject AuditLogRepository; Implement public String computePrevHash(): call findTopByOrderByIdDesc(); if empty return null; otherwise compute SHA-256 as specified; Annotate computePrevHash() with @Transactional(readOnly=true) so it joins the caller's transaction; Return lowercase hex string using Hex.encodeHexString (Apache Commons Codec) or manual byte loop
**Deliverable:** services/config-registry/src/main/java/com/gme/pay/configregistry/audit/AuditHashChainService.java
**Acceptance / logic checks:**
- computePrevHash() returns null when table is empty
- Given a fixed previous row (id=1, actorId='ops1', occurredAt=2025-01-01T00:00:00Z, beforeValueJson=null, afterValueJson='{"name":"x"}'), returns the pre-computed SHA-256 hex value deterministically
- Canonical string uses empty string (not 'null') for null JSON fields
- Method uses SHA-256 algorithm (not MD5 or SHA-1)
- @Transactional(readOnly=true) annotation present on the method
**Depends on:** 3.6-T05

### 3.6-T07 — Implement Vault transit key provisioner for audit field-level encryption  _(35 min)_
**Context:** SEC-09 §2.3 and §2.6: sensitive field values in before_value/after_value JSONB must be Vault-encrypted at rest for sensitive entity types (partner_credential, treasury_rate, rule). Use HashiCorp Vault transit engine (Spring Vault spring-vault-core). Transit key name: 'audit-key', type aes256-gcm96. Provision key at startup via @EventListener(ApplicationReadyEvent) if absent. Vault address from env VAULT_ADDR; token from env VAULT_TOKEN (never hardcoded). If Vault unreachable: log WARN and continue in degraded mode (encryption skipped). Module: services/config-registry.
**Steps:** Add dependency implementation 'org.springframework.vault:spring-vault-core' to services/config-registry/build.gradle; Create VaultTransitKeyProvisioner.java annotated @Component; inject VaultTemplate; Implement onApplicationReady(@EventListener ApplicationReadyEvent): check if transit mount exists; if not, enable it; check if 'audit-key' exists; if not, create with type aes256-gcm96; Catch VaultException: log WARN 'Vault unavailable - audit encryption in degraded mode'; set degradedMode=true flag; Expose isDegradedMode() boolean for AuditLogService to check
**Deliverable:** services/config-registry/src/main/java/com/gme/pay/configregistry/audit/VaultTransitKeyProvisioner.java and updated services/config-registry/build.gradle
**Acceptance / logic checks:**
- Application starts successfully when Vault is available and 'audit-key' is created on first run
- Application starts in degraded mode (WARN logged, no exception thrown) when Vault is unavailable
- Provisioner is idempotent: running twice when key exists does not throw an error
- Key type is aes256-gcm96 confirmed via Vault API response in WireMock test
- VAULT_ADDR and VAULT_TOKEN are read from environment variables, not from any config file
**Depends on:** 3.6-T05

### 3.6-T08 — Implement AuditLogService: append-only write with hash chain and Vault encryption  _(40 min)_
**Context:** AuditLogService is the single entry-point for writing audit records in services/config-registry. Flow: (1) call AuditHashChainService.computePrevHash(); (2) if entity_type in SENSITIVE_TYPES {'partner_credential','treasury_rate','rule'} and Vault not degraded, encrypt before/after JSON via vaultTemplate.opsForTransit().encrypt('audit-key', plaintext); (3) build AuditLogJpaEntity; (4) save via AuditLogRepository. The entire append() method is @Transactional so hash-read + insert are atomic. Never calls update or delete. SEC-09 §6.1 full field list: actor_id, actor_role, action, entity_type, entity_id, before_value (prev), after_value (new), occurred_at (server-generated), ip_address, request_id.
**Steps:** Create AuditLogService.java annotated @Service @Transactional in services/config-registry/src/main/java/com/gme/pay/configregistry/audit/; Inject AuditLogRepository, AuditHashChainService, VaultTransitKeyProvisioner, VaultTemplate; Implement public void append(AuditLogEntry entry): compute prevHash; encrypt if sensitive and not degraded; build entity; save; Define private static final Set<String> SENSITIVE_ENTITY_TYPES = Set.of('partner_credential','treasury_rate','rule'); Never call any repository method that issues UPDATE or DELETE
**Deliverable:** services/config-registry/src/main/java/com/gme/pay/configregistry/audit/AuditLogService.java
**Acceptance / logic checks:**
- append() for entity_type='partner_credential' stores Vault ciphertext in before_value (not plaintext)
- append() for entity_type='merchant' stores plaintext JSONB (non-sensitive)
- append() for action=CREATE stores NULL in before_value without NPE
- Two concurrent append() calls in different transactions do not produce duplicate prev_hash (serialization guaranteed by @Transactional)
- The method issues only INSERT statements; no UPDATE or DELETE SQL observed in Hibernate SQL log
**Depends on:** 3.6-T06, 3.6-T07

### 3.6-T09 — Add actor_role column to audit_log via Flyway migration and update entity  _(25 min)_
**Context:** SEC-09 §6.1 lists actor_role (role at time of change) as a required audit field alongside actor_id. Add it to the audit_log table. Column: actor_role VARCHAR(30) NULL. New entries from AuditLogService must populate it from the authenticated Spring Security granted authority (ADMIN, OPS, FINANCE, SYSTEM). Existing rows remain NULL. Update AuditLogJpaEntity and AuditLogEntry record to include the field.
**Steps:** Create V204__audit_log_actor_role.sql: ALTER TABLE audit_log ADD COLUMN actor_role VARCHAR(30) NULL; Add actorRole field to AuditLogJpaEntity.java with @Column(name='actor_role', insertable=true, updatable=false); AuditLogEntry Java record already includes actorRole (added in 3.6-T04; confirm it is present); Update AuditLogService.append() to set actorRole from AuditContext (populated by interceptor in 3.6-T10)
**Deliverable:** services/config-registry/src/main/resources/db/migration/V204__audit_log_actor_role.sql and updated AuditLogJpaEntity.java
**Acceptance / logic checks:**
- Migration V204 applies cleanly after V201-V203 on Testcontainers PostgreSQL 16
- actor_role column exists as VARCHAR(30) nullable
- Audit entry for an ADMIN-role request has actor_role='ADMIN' in the persisted row
- Audit entry for a SYSTEM-type action has actor_role='SYSTEM'
- V204 does not alter existing rows; they remain NULL
**Depends on:** 3.6-T08

### 3.6-T10 — Implement AuditContext request-scoped bean and HandlerInterceptor in config-registry  _(30 min)_
**Context:** SEC-09 §6.1 requires ip_address (operator IP) and request_id (trace ID) in every audit entry. Use a request-scoped Spring bean AuditContext populated by a HandlerInterceptor. Extract: ip from X-Forwarded-For header (first value) falling back to request.getRemoteAddr(); requestId from X-Request-ID header generating UUID if absent; actorRole from SecurityContextHolder granted authority. AuditLogService reads from injected AuditContext. For non-HTTP (SYSTEM) audit entries, ip=null, requestId='SYSTEM-'+UUID. Module: services/config-registry.
**Steps:** Create AuditContext.java as @Component @Scope(value='request', proxyMode=ScopedProxyMode.TARGET_CLASS) with fields String ipAddress, String requestId, String actorRole; Create AuditContextInterceptor.java implements HandlerInterceptor; in preHandle(): extract ip (X-Forwarded-For first, else remoteAddr), requestId (X-Request-ID or new UUID), actorRole from SecurityContextHolder; populate AuditContext; Register interceptor in WebMvcConfigurer.addInterceptors(); Inject AuditContext into AuditLogService; read ip, requestId, actorRole when building AuditLogJpaEntity
**Deliverable:** services/config-registry/src/main/java/com/gme/pay/configregistry/audit/AuditContext.java and AuditContextInterceptor.java
**Acceptance / logic checks:**
- HTTP request with X-Forwarded-For: 10.0.0.1 results in audit_log.ip_address = '10.0.0.1'
- HTTP request with no X-Forwarded-For header uses request.getRemoteAddr() value
- HTTP request with X-Request-ID: abc-123 results in audit_log.request_id = 'abc-123'
- HTTP request with no X-Request-ID header generates a UUID stored in audit_log.request_id
- SYSTEM-triggered append (no HTTP context) stores null ip_address and non-null requestId starting with 'SYSTEM-'
**Depends on:** 3.6-T08

### 3.6-T11 — Wire AuditLogService into config-registry RuleService, PartnerService, SchemeService, TreasuryRateService  _(45 min)_
**Context:** Every write operation on rule, partner, scheme, and treasury_rate in config-registry must emit an audit entry per SEC-09 §6.1. Each @Service method must: (1) snapshot before_value as objectMapper.writeValueAsString(existingEntity) before applying changes; (2) apply changes and snapshot after_value; (3) call auditLogService.append() inside the same @Transactional method. For ACTIVATE/DEACTIVATE use action=ACTIVATE/DEACTIVATE. If the entity save fails, the transaction rolls back and no audit row is written. SEC-09 §6.1 fields: actor_id, actor_role (via AuditContext), changed entity type and id, before/after JSON.
**Steps:** In RuleService.updateRule() capture beforeSnapshot before the mutation; call auditLogService.append() after mutation with action=UPDATE, entity_type='rule'; In RuleService.activateRule() call append with action=ACTIVATE; beforeSnapshot=current status, afterSnapshot=activated; In PartnerService.createPartner() call append with action=CREATE, beforeValueJson=null; In PartnerService.updatePartner() capture before/after and call append with action=UPDATE; Repeat the same before/after snapshot + append pattern in SchemeService and TreasuryRateService for all CUD and ACTIVATE/DEACTIVATE operations
**Deliverable:** Updated services/config-registry/src/main/java/com/gme/pay/configregistry/service/RuleService.java, PartnerService.java, SchemeService.java, TreasuryRateService.java
**Acceptance / logic checks:**
- After updateRule(), exactly one audit_log row with action='UPDATE', entity_type='rule', non-null before_value and after_value
- After createPartner(), one audit_log row with action='CREATE', before_value=NULL
- After activateRule(), one audit_log row with action='ACTIVATE'
- If entity save throws RuntimeException, no audit_log row is persisted (rollback test)
- actor_id in audit row matches the authenticated operator username from the JWT (not hardcoded)
**Depends on:** 3.6-T10

### 3.6-T12 — Create Flyway migration: transaction_event_trail table in transaction-mgmt PostgreSQL  _(25 min)_
**Context:** SEC-09 §6.1 defines an 8-step event trail per payment transaction. Table belongs to services/transaction-mgmt PostgreSQL 16 DB. Columns: id BIGINT PK auto-increment, transaction_id BIGINT NOT NULL REFERENCES transaction(id), event VARCHAR(60) NOT NULL CHECK IN ('rate_quote_issued','payment_initiated','prefund_deducted','scheme_request_sent','scheme_response_received','transaction_committed','webhook_dispatched','webhook_delivered'), occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), duration_ms BIGINT NULL (ms since previous step, null for first step), state_snapshot JSONB NULL (step 6 carries locked rate values). Unique constraint (transaction_id, event). No UPDATE or DELETE.
**Steps:** Create services/transaction-mgmt/src/main/resources/db/migration/V301__transaction_event_trail.sql; Define table with all 6 columns, check constraint on event with the 8 allowed values; Add index on (transaction_id, occurred_at) for trail retrieval; Add UNIQUE constraint on (transaction_id, event) to prevent duplicate steps; Do not add UPDATE/DELETE triggers yet (handled in 3.6-T13)
**Deliverable:** services/transaction-mgmt/src/main/resources/db/migration/V301__transaction_event_trail.sql
**Acceptance / logic checks:**
- Migration applies cleanly on Testcontainers PostgreSQL 16
- event column rejects values not in the 8-step list via check constraint
- UNIQUE constraint (transaction_id, event) prevents inserting the same step twice
- Index (transaction_id, occurred_at) exists in pg_indexes
- FK transaction_id REFERENCES transaction(id) is enforced: insert for non-existent transaction fails
**Depends on:** 3.6-T01

### 3.6-T13 — Flyway migration: REVOKE + immutable trigger on transaction_event_trail  _(20 min)_
**Context:** Same defense-in-depth pattern as audit_log (3.6-T03) applied to transaction_event_trail. Application DB role for transaction-mgmt is gme_txn_app. REVOKE UPDATE, DELETE; create trigger function txn_trail_immutable() raising EXCEPTION 'transaction_event_trail is append-only'; attach BEFORE UPDATE OR DELETE trigger. Module: services/transaction-mgmt.
**Steps:** Create V302__transaction_event_trail_immutable.sql in services/transaction-mgmt/src/main/resources/db/migration/; REVOKE UPDATE, DELETE ON TABLE transaction_event_trail FROM gme_txn_app; Create PL/pgSQL function txn_trail_immutable() RETURNS TRIGGER that raises EXCEPTION 'transaction_event_trail is append-only'; Create BEFORE UPDATE OR DELETE trigger trg_txn_trail_immutable ON transaction_event_trail FOR EACH ROW EXECUTE FUNCTION txn_trail_immutable()
**Deliverable:** services/transaction-mgmt/src/main/resources/db/migration/V302__transaction_event_trail_immutable.sql
**Acceptance / logic checks:**
- UPDATE on transaction_event_trail as gme_txn_app raises privilege error
- DELETE on transaction_event_trail as gme_txn_app raises privilege error
- INSERT and SELECT as gme_txn_app succeed
- Trigger function txn_trail_immutable exists in pg_proc after migration
- Trigger fires even if UPDATE is executed by a superuser-initiated SQL (trigger EXCEPTION is raised)
**Depends on:** 3.6-T12

### 3.6-T14 — Implement TransactionEventTrailService: append 8 steps with duration and state snapshot  _(40 min)_
**Context:** services/transaction-mgmt owns transaction_event_trail. The service appends individual event steps per SEC-09 §6.1. duration_ms = occurred_at minus previous step's occurred_at for same transaction_id (null for first step). Step 6 (transaction_committed) must include state_snapshot JSONB with 7 locked rate values: collection_usd, payout_usd_cost, collection_margin_usd, payout_margin_usd, send_amount, offer_rate_coll (= send_amount / (collection_usd - collection_margin_usd)), cross_rate (= target_payout / send_amount). These are the rate-lock values; later treasury/margin changes never affect them per RATE-04.
**Steps:** Create TransactionEventTrailJpaEntity.java and TransactionEventTrailRepository.java (findByTransactionIdOrderByOccurredAtAsc, findTopByTransactionIdOrderByOccurredAtDesc); Create TransactionEventTrailService.java @Service with appendEvent(Long txnId, String event, Map<String,Object> stateSnapshot): fetch latest row for duration_ms, build entity, save @Transactional; Implement getTrail(Long txnId) returning ordered list; Step 6 caller must pass all 7 rate snapshot keys; other callers pass null for stateSnapshot; Validate event string is one of the 8 allowed values at service layer before DB insert
**Deliverable:** services/transaction-mgmt/src/main/java/com/gme/pay/txnmgmt/audit/TransactionEventTrailService.java
**Acceptance / logic checks:**
- After appending step 1 and step 2, step 2 row has duration_ms > 0
- Step 6 row has non-null state_snapshot containing keys collection_usd and offer_rate_coll
- Calling appendEvent with same txnId + event a second time throws DataIntegrityViolationException (unique constraint)
- appendEvent with unknown event string 'foo' throws IllegalArgumentException before hitting DB
- getTrail() returns rows in occurred_at ascending order
**Depends on:** 3.6-T13

### 3.6-T15 — Wire TransactionEventTrailService steps 1-5 and 7-8 into payment-executor  _(45 min)_
**Context:** payment-executor must call TransactionEventTrailService for steps 1-5 and 7-8 of the event trail. Steps: 1=rate_quote_issued (in rate-fx RateController after GET /v1/rates response), 2=payment_initiated (POST /v1/payments handler), 3=prefund_deducted (OVERSEAS: after successful SELECT FOR UPDATE deduction in prefunding-balance service), 4=scheme_request_sent (before ZeroPay adapter call), 5=scheme_response_received (after ZeroPay adapter returns), 7=webhook_dispatched (notification-webhook service before dispatch), 8=webhook_delivered (after partner returns HTTP 2xx). Call via services/transaction-mgmt internal REST endpoint POST /internal/txn-trail/{txnId}/events. stateSnapshot=null for steps 1-5,7,8.
**Steps:** Create TransactionEventTrailClient.java in services/payment-executor using Spring RestClient or OpenFeign to call POST /internal/txn-trail/{txnId}/events with body {event, stateSnapshot}; Wire step 2 call in PaymentExecutorService after creating the transaction record; Wire step 4 call in ZeroPayAdapterClient before dispatching to scheme; Wire step 5 call after receiving scheme response; Wire steps 7 and 8 in NotificationService (services/notification-webhook); All trail calls are fire-and-not-rollback: if trail append fails, log WARN but do not roll back the payment
**Deliverable:** services/payment-executor/src/main/java/com/gme/pay/payexec/audit/TransactionEventTrailClient.java and wiring changes in PaymentExecutorService.java, ZeroPayAdapterClient.java, NotificationService.java
**Acceptance / logic checks:**
- After a successful payment flow, all 8 steps appear in transaction_event_trail ordered by occurred_at
- If trail service is unavailable, payment processing continues and step is logged as WARN, not failed
- Step 3 is only appended for OVERSEAS partner type (not LOCAL/GME Remit)
- Step 7 is appended before HTTP dispatch and step 8 after 2xx confirmation
- WireMock stub for POST /internal/txn-trail/{txnId}/events verifies correct event name in request body
**Depends on:** 3.6-T14

### 3.6-T16 — Wire step 6 transaction_committed into PaymentCommitService with full rate state snapshot  _(35 min)_
**Context:** SEC-09 §6.1 step 6 (transaction_committed): rate and amount values must be permanently locked at commit. In services/payment-executor PaymentCommitService.commitPayment(), after the DB save, call transactionEventTrailClient.appendEvent(txnId, 'transaction_committed', stateSnapshot) with all 7 required fields from RateEngineResult: collection_usd, payout_usd_cost, collection_margin_usd, payout_margin_usd, send_amount, offer_rate_coll (= send_amount / (collection_usd - collection_margin_usd)), cross_rate (= target_payout / send_amount). These are DECIMAL values (BigDecimal); serialize as numeric JSON (not strings). BOK FX1015 field #14 = offer_rate_coll per SEC-09 §8.1.
**Steps:** In PaymentCommitService.commitPayment() build stateSnapshot Map<String,BigDecimal> with all 7 keys from RateEngineResult; Call transactionEventTrailClient.appendEvent(txnId, 'transaction_committed', stateSnapshot) after the transaction DB record is saved; Ensure this call is inside the same @Transactional(propagation=REQUIRES_NEW) block so it does not roll back the payment; Assert offer_rate_coll = send_amount / (collection_usd - collection_margin_usd) at the service layer before storing; Assert pool identity: collection_usd - collection_margin_usd - payout_margin_usd == payout_usd_cost (tolerance 0.01 USD); if violated log ERROR and throw RatePoolIdentityException
**Deliverable:** Updated services/payment-executor/src/main/java/com/gme/pay/payexec/PaymentCommitService.java
**Acceptance / logic checks:**
- state_snapshot on step 6 row contains all 7 keys with numeric (not string) values
- offer_rate_coll = send_amount / (collection_usd - collection_margin_usd) exactly as stored
- Pool identity check: |collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost| <= 0.01 USD; violation throws RatePoolIdentityException
- WireMock test verifies the JSON body of POST /internal/txn-trail/{txnId}/events for step 6 contains numeric collection_usd
- A pool identity violation (e.g. collection_usd = 100.00, margins sum to 3.00, payout_usd_cost = 96.50 - delta 0.50) is rejected with RatePoolIdentityException
**Depends on:** 3.6-T15

### 3.6-T17 — Expose GET /internal/audit-log/{entityType}/{entityId} endpoint in config-registry  _(35 min)_
**Context:** Admin portal (PRD-07) shows audit history for any config entity. Expose an internal REST endpoint in config-registry: GET /internal/audit-log/{entityType}/{entityId}?page=0&size=50. Returns paginated AuditLogEntryDto list. SEC-09 §3.4 permission matrix: audit log view is ADMIN role only. Guard with Spring Security @PreAuthorize('hasRole("ADMIN")'). @EnableMethodSecurity must be active. DTO fields: id, actorId, actorRole, actorType, action, entityType, entityId, beforeValueJson, afterValueJson, occurredAt (ISO-8601 UTC), ipAddress, requestId. Return encrypted ciphertext as-is; decryption is out of scope.
**Steps:** Create AuditLogController.java annotated @RestController @RequestMapping('/internal/audit-log') in services/config-registry/src/main/java/com/gme/pay/configregistry/audit/; Create AuditLogEntryDto record with all 13 fields; Implement @GetMapping('/{entityType}/{entityId}') returning ResponseEntity<Page<AuditLogEntryDto>> with @PreAuthorize('hasRole("ADMIN")'); Map AuditLogJpaEntity -> AuditLogEntryDto in a private mapper method; Return HTTP 200 empty page for unknown entity (not 404); return HTTP 403 for non-ADMIN roles; return HTTP 401 for unauthenticated
**Deliverable:** services/config-registry/src/main/java/com/gme/pay/configregistry/audit/AuditLogController.java
**Acceptance / logic checks:**
- GET /internal/audit-log/rule/42 with ADMIN JWT returns HTTP 200 with paginated JSON body
- GET /internal/audit-log/rule/42 with OPS JWT returns HTTP 403
- GET /internal/audit-log/rule/99999 (non-existent entity) returns HTTP 200 with empty content array
- page=0&size=5 query parameter limits result to at most 5 entries
- occurredAt in response is ISO-8601 UTC format (e.g. 2025-01-01T00:00:00Z, not epoch millis)
**Depends on:** 3.6-T11

### 3.6-T18 — Implement AuditLogIntegrityCheckService: daily SHA-256 chain validation job  _(45 min)_
**Context:** SEC-09 §6.3 requires daily integrity check of the audit_log hash chain. Algorithm: walk all rows ordered by id ASC in pages of 1000; for each row after the first, recompute expected_prev_hash using the same canonical string formula as 3.6-T06 and compare to row.prev_hash; mismatch = chain break. First row must have prev_hash=NULL. Any failure is P1 alert per SEC-09 §10.4. Schedule: @Scheduled(cron='0 0 2 * * *') (02:00 UTC daily). Emit OpenTelemetry counter metric 'audit.integrity.breaks'. Log results as structured JSON for SIEM forwarding per SEC-09 §6.5. Module: services/config-registry.
**Steps:** Create AuditLogIntegrityCheckService.java @Service in services/config-registry/src/main/java/com/gme/pay/configregistry/audit/; Implement runDailyCheck() @Scheduled(cron='0 0 2 * * *') that pages through audit_log rows 1000 at a time; For each row (after the first) recompute prevHash and compare; collect mismatches (broken id, expected, actual); If mismatches > 0 log ERROR with SIEM JSON: {event:'AUDIT_CHAIN_BREAK', breach_count:N, first_broken_id:X, checked_at:ISO}; Emit OTel counter metric named 'audit.integrity.breaks' with value = mismatch count (0 on clean run)
**Deliverable:** services/config-registry/src/main/java/com/gme/pay/configregistry/audit/AuditLogIntegrityCheckService.java
**Acceptance / logic checks:**
- Valid 5-row chain: runDailyCheck() logs INFO 'audit chain OK', metric 'audit.integrity.breaks' = 0
- Corrupted prev_hash on row 3: ERROR logged with first_broken_id=3, metric = 1
- Single genesis row (prev_hash=NULL): valid, metric = 0
- Paged query (page size 1000) is used; no in-memory full-table load
- @Scheduled cron expression is exactly '0 0 2 * * *'
**Depends on:** 3.6-T06

### 3.6-T19 — Implement AuditLogRetentionGuardService: monthly overdue retention warning  _(30 min)_
**Context:** SEC-09 §6.2 mandates >= 7-year retention for audit_log and transaction_event_trail. Implement a monthly scheduled job in services/config-registry that counts rows in audit_log older than the configured retention period and logs SIEM-compatible JSON WARN if any exist. The job does NOT delete rows (deletion is a privileged DBA-only operation). Schedule: @Scheduled(cron='0 0 3 1 * *') (03:00 UTC on 1st of each month). Property: audit.log.retention-years (default 7, injected via @Value).
**Steps:** Create AuditLogRetentionGuardService.java @Service in services/config-registry/src/main/java/com/gme/pay/configregistry/audit/; Inject JdbcTemplate; inject @Value('${audit.log.retention-years:7}') int retentionYears; Implement checkRetention() @Scheduled(cron='0 0 3 1 * *'); Query: SELECT COUNT(*) FROM audit_log WHERE occurred_at < NOW() - INTERVAL '? years' with retentionYears parameter; If count > 0 log WARN with JSON: {event:'AUDIT_RETENTION_OVERDUE',overdue_count:N,retention_years:7}; else log INFO 'audit retention OK'; Method must NOT issue DELETE or UPDATE
**Deliverable:** services/config-registry/src/main/java/com/gme/pay/configregistry/audit/AuditLogRetentionGuardService.java
**Acceptance / logic checks:**
- With rows older than 7 years in DB, WARN log contains {event:'AUDIT_RETENTION_OVERDUE'} and correct overdue_count
- With no old rows, INFO 'audit retention OK' logged, no WARN
- Setting audit.log.retention-years=5 changes threshold to 5 years without code change
- @Scheduled cron is '0 0 3 1 * *'
- No DELETE or UPDATE SQL is issued by this method (verified in SQL log)
**Depends on:** 3.6-T05

### 3.6-T20 — Unit tests: AuditHashChainService SHA-256 correctness with known test vectors  _(30 min)_
**Context:** Test 3.6-T06's AuditHashChainService in services/config-registry with JUnit 5 + Mockito. No DB required; mock AuditLogRepository. Provide 3 deterministic test vectors with pre-computed SHA-256 reference hashes to verify correctness and canonical string construction.
**Steps:** Create AuditHashChainServiceTest.java in services/config-registry/src/test/java/com/gme/pay/configregistry/audit/; Annotate @ExtendWith(MockitoExtension.class); mock AuditLogRepository; Vector 1: prev row id=1, actorId='ops1', occurredAt='2025-01-01T00:00:00Z', beforeValueJson=null, afterValueJson='{"name":"test"}' -> compute reference SHA-256 and assert exact match; Vector 2: prev row with both non-null before and after JSON -> compute reference and assert; Vector 3: empty table (findTopByOrderByIdDesc returns empty Optional) -> computePrevHash() returns null; Assert canonical string uses empty string (not 'null') for null JSON fields in vectors 1 and 2
**Deliverable:** services/config-registry/src/test/java/com/gme/pay/configregistry/audit/AuditHashChainServiceTest.java
**Acceptance / logic checks:**
- All 3 test vectors pass on repeated runs (deterministic)
- Null beforeValueJson uses empty string in hash input (vector 1 reference value accounts for this)
- computePrevHash() with empty table returns null (vector 3)
- Test runs in under 5 seconds with no DB dependency
- Test class has @ExtendWith(MockitoExtension.class) annotation
**Depends on:** 3.6-T06

### 3.6-T21 — Integration tests: AuditLogService append-only enforcement with Testcontainers + WireMock  _(50 min)_
**Context:** Integration test for AuditLogService (3.6-T08) and DB-level immutability (V203 migration). Use @SpringBootTest + Testcontainers PostgreSQL 16 with full Flyway migrations applied. Stub Vault transit API using WireMock. Verify: normal append works; direct JDBC UPDATE raises exception; direct JDBC DELETE raises exception; second row prev_hash is correct SHA-256 of first row.
**Steps:** Create AuditLogServiceIntegrationTest.java in services/config-registry/src/test/java/com/gme/pay/configregistry/audit/; Annotate @SpringBootTest @Testcontainers; declare @Container static PostgreSQLContainer; Start WireMock server; stub POST /v1/transit/encrypt/audit-key to return {data:{ciphertext:'vault:v1:abc'}}; Test 1: append() for action=UPDATE, entity_type='rule' - assert persisted row with correct fields; Test 2: jdbcTemplate.execute('UPDATE audit_log SET actor_id=x WHERE id=1') - assert DataAccessException; Test 3: jdbcTemplate.execute('DELETE FROM audit_log WHERE id=1') - assert DataAccessException; Test 4: two sequential appends - assert row 2 prev_hash equals SHA-256 of row 1 canonical string
**Deliverable:** services/config-registry/src/test/java/com/gme/pay/configregistry/audit/AuditLogServiceIntegrationTest.java
**Acceptance / logic checks:**
- Test 2 throws DataAccessException containing 'append-only' or privilege denied message
- Test 3 throws DataAccessException for DELETE
- Test 4 row-2 prev_hash matches reference SHA-256 computed in test
- WireMock stub for Vault transit is invoked when entity_type='rule' (sensitive type)
- All 4 tests pass with Testcontainers PostgreSQL 16 image
**Depends on:** 3.6-T08, 3.6-T20

### 3.6-T22 — Integration tests: TransactionEventTrailService 8-step append and immutability in transaction-mgmt  _(45 min)_
**Context:** Test TransactionEventTrailService (3.6-T14) with Testcontainers PostgreSQL 16 in services/transaction-mgmt. Verify: all 8 steps append in order; duration_ms is positive on step 2+; step 6 state_snapshot contains 7 rate keys; duplicate step raises exception; UPDATE is blocked by trigger.
**Steps:** Create TransactionEventTrailServiceIntegrationTest.java in services/transaction-mgmt/src/test/java/com/gme/pay/txnmgmt/audit/; Spin up @Container PostgreSQLContainer; apply Flyway migrations V301+V302; Insert prerequisite transaction row; Append all 8 event steps in order; assert each row exists by querying getTrail(); Assert step 2 (payment_initiated) has duration_ms > 0; Assert step 6 (transaction_committed) has state_snapshot containing keys: collection_usd, payout_usd_cost, collection_margin_usd, payout_margin_usd, send_amount, offer_rate_coll, cross_rate; Attempt duplicate step 1; assert DataIntegrityViolationException; attempt UPDATE via JDBC, assert DataAccessException
**Deliverable:** services/transaction-mgmt/src/test/java/com/gme/pay/txnmgmt/audit/TransactionEventTrailServiceIntegrationTest.java
**Acceptance / logic checks:**
- All 8 event steps inserted and retrieved in ascending occurred_at order
- duration_ms on step 2 is positive (not zero)
- state_snapshot on step 6 contains all 7 required keys with non-null numeric values
- Duplicate step 1 insert throws DataIntegrityViolationException
- JDBC UPDATE on any trail row throws DataAccessException (immutable trigger active)
**Depends on:** 3.6-T14

### 3.6-T23 — Unit tests: AuditLogIntegrityCheckService detects chain break and emits OTel metric  _(35 min)_
**Context:** Test AuditLogIntegrityCheckService (3.6-T18) with JUnit 5 + Mockito + in-memory MeterRegistry. No DB required. Build a 5-row chain dataset where row 3 has a manually corrupted prev_hash. Verify first_broken_id=3, metric=1. Also verify valid chain yields metric=0. Verify genesis (single row) is valid.
**Steps:** Create AuditLogIntegrityCheckServiceTest.java in services/config-registry/src/test/java/com/gme/pay/configregistry/audit/; Use @ExtendWith(MockitoExtension.class); mock AuditLogRepository to return paged results; Build helper to generate a valid 5-row chain with correct prev_hash using AuditHashChainService logic; Test 1: valid chain -> no ERROR log; metric 'audit.integrity.breaks' = 0; Test 2: corrupt row 3 prev_hash='deadbeef...' -> ERROR logged with first_broken_id=3; metric = 1; Test 3: single genesis row (prev_hash=NULL) -> valid; metric = 0
**Deliverable:** services/config-registry/src/test/java/com/gme/pay/configregistry/audit/AuditLogIntegrityCheckServiceTest.java
**Acceptance / logic checks:**
- Test 1 produces no ERROR log and metric = 0
- Test 2 identifies first_broken_id=3 (not 4 or 5)
- Test 3 passes with single genesis row
- OTel metric 'audit.integrity.breaks' captured from in-memory MeterRegistry with correct value
- Test runs in under 10 seconds with no DB or Vault dependency
**Depends on:** 3.6-T18

### 3.6-T24 — Unit tests: AuditLogController RBAC enforcement with @WebMvcTest Spring Security slice  _(25 min)_
**Context:** Test AuditLogController (3.6-T17) RBAC using @WebMvcTest + Spring Security test support in services/config-registry. Cover: ADMIN gets 200, OPS gets 403, FINANCE gets 403, unauthenticated gets 401. Also verify pagination parameters are passed to the repository.
**Steps:** Create AuditLogControllerTest.java in services/config-registry/src/test/java/com/gme/pay/configregistry/audit/; Annotate @WebMvcTest(AuditLogController.class); @MockBean AuditLogRepository; Test 1: @WithMockUser(roles='ADMIN') GET /internal/audit-log/rule/1 returns 200 with JSON body; Test 2: @WithMockUser(roles='OPS') returns 403; Test 3: @WithMockUser(roles='FINANCE') returns 403; Test 4: no authentication returns 401; Test 5: ADMIN with ?page=0&size=3 - verify MockBean called with matching Pageable
**Deliverable:** services/config-registry/src/test/java/com/gme/pay/configregistry/audit/AuditLogControllerTest.java
**Acceptance / logic checks:**
- ADMIN role returns HTTP 200 with JSON 'content' array
- OPS role returns HTTP 403
- FINANCE role returns HTTP 403
- Unauthenticated returns HTTP 401
- Pageable with pageSize=3 passed to AuditLogRepository mock verified by Mockito.verify
**Depends on:** 3.6-T17


## WBS 13.1 — Security architecture & secrets/encryption
### 13.1-T01 — Define network-zone security group rules schema and IaC module  _(45 min)_
**Context:** SEC-09 §2.1 defines a four-zone model: Public DMZ (API Gateway), Application Zone (Hub Core + portal backends), Data Zone (primary DB, audit log store, secrets vault), Scheme Integration Zone (SFTP client + ZeroPay batch processor). No component in the Application or Data Zone accepts inbound connections from the public internet. Scheme Integration Zone is restricted egress only; no inbound connections. The Data Zone has no direct internet access.
**Steps:** Create an IaC module (Terraform or CloudFormation) that declares four named security groups: sg-dmz, sg-app, sg-data, sg-scheme.; For sg-dmz: allow inbound TCP 443 from 0.0.0.0/0; allow outbound to sg-app only.; For sg-app: allow inbound from sg-dmz and sg-app only; allow outbound to sg-data and sg-app.; For sg-data: allow inbound from sg-app only; no outbound to internet.; For sg-scheme: allow outbound to ZeroPay SFTP endpoint (IP allowlist, TCP 22) only; no inbound.; Add a README block documenting each zone and its trust level.
**Deliverable:** IaC module (e.g., modules/network-zones/main.tf) with four security group definitions matching SEC-09 §2.1 zone rules.
**Acceptance / logic checks:**
- sg-dmz has inbound 443 open but no inbound 80; verifiable with a plan output showing ingress rules.
- sg-data has zero inbound rules from 0.0.0.0/0 or sg-dmz; plan output confirms.
- sg-scheme has no inbound rules defined; only one egress rule to the ZeroPay IP allowlist on port 22.
- sg-app allows inbound from sg-dmz (sourced by group ID, not CIDR); plan output shows security group references not IP ranges.
- A linting check (tfsec or checkov) passes with no HIGH-severity findings on these security groups.

### 13.1-T02 — Configure API Gateway: TLS policy, HSTS, and HTTP redirect  _(40 min)_
**Context:** SEC-09 §2.2 and §2.4 require TLS 1.2 minimum (TLS 1.3 preferred) for all partner-facing endpoints. Unencrypted HTTP is not permitted for any connection carrying credentials, payment data, or PII. Admin/Partner portal backends must enforce HSTS. The API Gateway (Kong, AWS API GW, or Spring Cloud Gateway) is in the Public DMZ security group.
**Steps:** Configure the API Gateway TLS listener to reject TLS < 1.2; set preferred cipher suites to TLS 1.3 first.; Add an HTTP-to-HTTPS redirect (301) listener on port 80 for all host patterns.; Set Strict-Transport-Security header: max-age=31536000; includeSubDomains on all responses.; Verify the gateway config is stored in IaC (no manual console changes); commit to repository.; Run an sslscan or testssl.sh check in CI and fail the build if TLS 1.0/1.1 is offered.
**Deliverable:** API Gateway TLS policy configuration (IaC) with HTTP redirect and HSTS header; CI step that fails on TLS < 1.2.
**Acceptance / logic checks:**
- sslscan or testssl.sh against sandbox endpoint shows only TLS 1.2 and 1.3 negotiated; TLS 1.0 and 1.1 absent.
- HTTP request to port 80 returns HTTP 301 to https:// equivalent URL.
- curl -I response includes Strict-Transport-Security: max-age=31536000; includeSubDomains.
- CI pipeline step exits non-zero when the TLS scan detects a protocol below 1.2.
**Depends on:** 13.1-T01

### 13.1-T03 — Provision secrets vault (Vault/Secrets Manager) and define secret path taxonomy  _(55 min)_
**Context:** SEC-09 §2.3 requires HashiCorp Vault or a cloud-equivalent (AWS Secrets Manager, Azure Key Vault) for: partner HMAC signing keys, ZeroPay/scheme credentials, SFTP private keys, DB credentials, and internal service-to-service tokens. No credentials may appear in environment variables, config files, or source code. Each secret type has a restricted access path per service.
**Steps:** Provision a Vault instance (or Secrets Manager namespace) in the Data Zone (sg-data).; Define the secret path taxonomy: secrets/partner/{partner_id}/hmac_key, secrets/scheme/zeropay/sftp_private_key, secrets/scheme/zeropay/api_credentials, secrets/db/{service_name}/credentials, secrets/internal/service_tokens.; Configure Vault policies so that only the named service account for each service can read its path (e.g., auth-service can read secrets/partner/*/hmac_key; no other service can).; Enable audit logging on the Vault instance (file or syslog backend).; Document the path taxonomy and policy bindings in a VAULT_PATHS.md file in the infra repo.
**Deliverable:** Vault/Secrets Manager instance with defined path taxonomy, per-service access policies, and audit logging enabled; VAULT_PATHS.md committed to infra repo.
**Acceptance / logic checks:**
- A Vault token bound to the scheme-integration policy can read secrets/scheme/zeropay/sftp_private_key but receives 403 on secrets/partner/*/hmac_key.
- A Vault token bound to the auth-service policy can read secrets/partner/{uuid}/hmac_key but receives 403 on secrets/db/*/credentials.
- Vault audit log records each read event with the accessor identity and secret path.
- grep -r 'sftp_private_key\|hmac_key\|db_password' on the application source directory returns zero matches (no hardcoded secrets).
- Vault audit log is stored in sg-data with no public internet egress path.
**Depends on:** 13.1-T01

### 13.1-T04 — Implement runtime secret injection for all application services  _(55 min)_
**Context:** SEC-09 §2.3 forbids credentials in environment variables, config files, or source code. DB credentials, HMAC secrets, and scheme API credentials must be fetched from the secrets vault at service startup and refreshed before TTL expiry. Internal service-to-service tokens are short-lived JWTs (1-hour TTL) issued by an internal identity service.
**Steps:** Add a SecretProvider interface/class to the shared-libs module with methods: getString(path), refresh(path), and a background refresh scheduler that re-fetches secrets when remaining TTL < 20% of lease duration.; Implement VaultSecretProvider (or AwsSecretsManagerProvider) backed by the vault provisioned in T03.; Wire each application service (auth-service, scheme-integration, db pool) to fetch its credentials via SecretProvider on startup; remove all credential properties from application.properties / environment variables.; Add a startup health check that fails (prevents service start) if any required secret path is unreachable.; Write a unit test using a mock SecretProvider that verifies refresh is called when TTL < 20%.
**Deliverable:** SecretProvider interface and VaultSecretProvider implementation in shared-libs; all services wired to use it; no credentials in config or env.
**Acceptance / logic checks:**
- Service starts successfully when vault is reachable and configured paths exist; logs show 'secrets loaded' not any credential value.
- Service refuses to start and logs a fatal error if vault is unreachable at startup (tested by mocking vault unavailability).
- Unit test: mock SecretProvider with TTL=100s; after advancing clock to t=81s, verify refresh() is called.
- grep -r 'password=\|secret=' on all application.properties files returns zero credential values.
- SAST scan finds no hardcoded credential patterns (secret scanning passes in CI).
**Depends on:** 13.1-T03

### 13.1-T05 — Generate Ed25519 SSH key pair for ZeroPay SFTP and store in vault  _(45 min)_
**Context:** SEC-09 §2.5 and SCH-06 §2.3.1 require GMEPay+ to generate an Ed25519 key pair per environment (sandbox, production) for ZeroPay SFTP authentication. The private key must be stored in the secrets vault at path secrets/scheme/zeropay/sftp_private_key; it must never be written to disk outside the vault. The public key is registered with KFTC. ZeroPay host public key is pinned in config (connection rejected if host key changes).
**Steps:** Write a one-time ops script (generate_sftp_keypair.sh) that: generates an Ed25519 key pair in memory using ssh-keygen or Java Bouncy Castle, writes the private key directly to Vault path secrets/scheme/zeropay/sftp_private_key, and prints the public key to stdout for operator to copy to KFTC.; Add a ZeroPay SFTP host key pinning config property (zeropay.sftp.known_host_fingerprint) read from vault/config; the SFTP client checks this fingerprint on every connection and aborts with error SFTP_HOST_KEY_MISMATCH if it differs.; Ensure the script produces no private-key output to disk or logs; only the public key is printed.; Audit-log the key generation event: actor, timestamp, environment, event_type=SFTP_KEY_GENERATED.
**Deliverable:** generate_sftp_keypair.sh ops script; SFTP client code that enforces host key pinning; audit log event on key generation.
**Acceptance / logic checks:**
- Running the script results in private key stored in vault at the correct path; no .pem or .key file created on disk.
- SFTP client code: when the known_host_fingerprint config does not match the server fingerprint, the connection is rejected with error code SFTP_HOST_KEY_MISMATCH and the payment pipeline does not proceed.
- The public key printed by the script is a valid Ed25519 public key (ssh-keygen -l -f validates it).
- Audit log contains one entry with event_type=SFTP_KEY_GENERATED, actor=ops_user_id, timestamp within 1s.
- grep on the script source confirms no private key material is written to stdout or a file path.
**Depends on:** 13.1-T03

### 13.1-T06 — Implement mTLS configuration for internal service-to-service communication  _(55 min)_
**Context:** SEC-09 §2.4 requires mTLS (mutual certificate authentication) for all internal service-to-service connections in the Application Zone. Each service must present a client certificate; connections without a valid certificate must be rejected. API-05 §3.4 also notes that Partners requiring enhanced transport security may request mTLS onboarding at the API Gateway; the X-Signature header is still required in addition to mTLS.
**Steps:** Generate an internal CA (or use Vault PKI secrets engine) to issue per-service TLS certificates for: auth-service, rate-engine, transaction-orchestrator, scheme-integration, notification-service, config-registry.; Configure each service's HTTP client to present its client certificate on outbound calls and to require a valid server certificate signed by the internal CA.; Configure each service's inbound TLS listener to require client certificate verification (TLS mutual auth mode).; Store internal CA cert and per-service private keys in the secrets vault under secrets/internal/tls/{service_name}/.; Add an integration smoke test that verifies a call from service A to service B with a certificate from a different (external) CA is rejected with a TLS handshake error.
**Deliverable:** Internal CA setup (Vault PKI or equivalent); per-service mTLS configuration; integration smoke test for rejection of unauthorized certs.
**Acceptance / logic checks:**
- A test HTTP call from a client with no certificate to any internal service endpoint returns a TLS handshake failure (not HTTP 401 — the rejection is at the TLS layer).
- A test HTTP call from a client with a certificate signed by an unrecognized CA is rejected at the TLS layer.
- A test HTTP call between two services with valid internal-CA certs succeeds and returns HTTP 200.
- Vault PKI issues certs with a 90-day TTL; automated renewal runs at 14 days before expiry (verify via Vault config).
- All internal service URLs use https:// scheme; no plain http:// service calls in the codebase.
**Depends on:** 13.1-T03

### 13.1-T07 — Configure AES-256 storage-layer encryption for primary database  _(50 min)_
**Context:** SEC-09 §2.6 requires AES-256 encryption at the storage layer for all database tables. This is typically achieved via Transparent Data Encryption (TDE) at the PostgreSQL/RDS level. Additionally, DAT-03 Assumption A6 specifies that merchant.account_no is stored AES-256 encrypted at the application layer (column-level encryption) because GMEPay+ never transmits it outbound.
**Steps:** Enable storage-layer AES-256 encryption for the PostgreSQL database (RDS encryption at rest with KMS key, or pg_tde extension for self-hosted).; Verify that the encryption key is stored in the secrets vault / KMS, not alongside the data.; Implement column-level AES-256-GCM encryption in the Java DAO layer for the merchant.account_no column using a dedicated DEK stored in vault at secrets/db/column_keys/merchant_account_no.; Add a Flyway migration (V{n}__add_merchant_account_no_encrypted.sql) that alters the column type to BYTEA and adds the encrypted_at column (TIMESTAMPTZ).; Write a unit test: encrypt 'KR123456789' with the DEK, store, retrieve, decrypt, assert result equals 'KR123456789'.
**Deliverable:** Database storage encryption enabled (IaC config); column-level encryption DAO for merchant.account_no; Flyway migration; unit test.
**Acceptance / logic checks:**
- Connecting directly to the database storage volume without the KMS key yields unreadable data (verified by infrastructure team via AWS console or equivalent).
- merchant.account_no in raw DB rows is BYTEA ciphertext, not plaintext KR123456789; confirmed via psql SELECT.
- Unit test: encrypt/decrypt roundtrip for merchant.account_no='KR123456789' passes.
- Unit test: decrypting with a wrong key throws a DecryptionException, not returning garbled data silently.
- Flyway migration runs cleanly on a fresh schema; V{n}__add_merchant_account_no_encrypted.sql is idempotent.
**Depends on:** 13.1-T03

### 13.1-T08 — Configure AES-256 volume encryption for SFTP file staging area  _(45 min)_
**Context:** SEC-09 §2.6 requires AES-256 volume encryption for the SFTP file staging area (where ZeroPay batch files are staged before/after transmission). SCH-06 §2.3.5 states all batch files (raw, decrypted, response) are retained for 90 days in an encrypted storage volume, with files purged after the retention window by a scheduled cleanup job.
**Steps:** Provision the SFTP staging volume (EBS, EFS, or equivalent) with AES-256 encryption enabled at creation, using a KMS key stored separately from the data.; Verify the volume is mounted only in the Scheme Integration Zone (sg-scheme) and is not accessible from Application or DMZ zones.; Set the file_retention_days property (default 90) in application config, read from vault.; Implement a scheduled cleanup job (SftpFileRetentionJob) that deletes files older than file_retention_days from the staging directory and logs: files_deleted count, oldest_file_deleted_at, job_run_at.; Add a Spring Scheduler test that verifies the cleanup job deletes files with mtime older than retention threshold and does not delete newer files.
**Deliverable:** Encrypted staging volume (IaC); SftpFileRetentionJob class with scheduler; unit test for retention logic.
**Acceptance / logic checks:**
- IaC plan shows the volume is provisioned with encryption=true and a KMS key ARN; not encrypted with default AWS-managed key without explicit key reference.
- SftpFileRetentionJob test: given three files aged 91d, 90d, 89d (retention=90), only the 91d file is deleted.
- SftpFileRetentionJob test: given retention=90 and no files older than 90 days, zero files deleted; log shows files_deleted=0.
- Cleanup job logs include oldest_file_deleted_at and files_deleted count (not just a success message).
- Volume mount point is absent from sg-app and sg-dmz security group definitions.
**Depends on:** 13.1-T01, 13.1-T03

### 13.1-T09 — Configure AES-256-GCM vault encryption and HSM backend  _(45 min)_
**Context:** SEC-09 §2.6 requires the secrets vault itself to use AES-256-GCM encryption, backed by a hardware security module (HSM) where available. SEC-09 §2.7 requires the vault master key rotation to use a dual-control re-seal/unseal ceremony annually. Backups must be AES-256 encrypted with the backup encryption key stored separately from the data encryption key.
**Steps:** Configure HashiCorp Vault (or AWS Secrets Manager) to use AES-256-GCM as its encryption backend; if on AWS, enable automatic key rotation on the KMS key used by Secrets Manager.; Enable AWS CloudHSM or Vault's PKCS#11 HSM seal where available in the environment; document as optional if HSM is not available in sandbox.; Configure vault backup (snapshot) to a separate S3 bucket with server-side encryption using a distinct KMS key (not the same key as the vault data key).; Add a Vault policy that requires two distinct admin tokens to unseal the vault (Shamir secret sharing with k=2, n=3 key shares).; Document the dual-control unseal procedure in an OPERATIONS_RUNBOOK.md section: step-by-step for the re-seal/unseal ceremony.
**Deliverable:** Vault encryption-backend config (AES-256-GCM); backup bucket with separate KMS key; Shamir k=2 unseal config; runbook section OPERATIONS_RUNBOOK.md.
**Acceptance / logic checks:**
- Vault status output shows Shamir seal with n=3 key shares, threshold=2; a single share cannot unseal the vault.
- Backup bucket uses a different KMS key ARN than the vault data key; confirmed in IaC plan output.
- AES-256-GCM is the active seal type in vault config; verified by vault operator get-config output.
- OPERATIONS_RUNBOOK.md contains a numbered unseal procedure referencing both key-holder roles.
- Vault audit log records the unseal event with two distinct accessor IDs.
**Depends on:** 13.1-T03

### 13.1-T10 — Implement partner API key and HMAC secret hashing and issuance  _(55 min)_
**Context:** SEC-09 §2.3 and §9.1: partner API secrets are hashed (bcrypt or Argon2) at rest; plaintext shown only once at issuance, then not retrievable. The HMAC signing key is distinct from the API key; both are issued together. HMAC secret is stored encrypted in the vault (not hashed) because the system needs to retrieve it to verify incoming signatures. API-05 §3.1 states the signing_secret_hash column in partner_credential uses HMAC-SHA256 stored as a bcrypt hash.
**Steps:** Create PartnerCredentialService.issueCredentials(partnerId): generates crypto-random api_key (UUID v4), crypto-random hmac_secret (32-byte hex), hashes api_key with bcrypt (cost >= 12), stores hash in partner_credential.api_key_hash, stores hmac_secret in vault at secrets/partner/{partner_id}/hmac_key, returns plaintext {api_key, hmac_secret} once.; Ensure PartnerCredentialService.getCredentials(partnerId) returns only metadata (key_id, created_at, status) — never the plaintext api_key or hmac_secret.; Add a method revokeCredentials(partnerId, keyId) that sets status=REVOKED in DB and deletes the vault entry.; Audit-log issuance and revocation events with actor_id, partner_id, event_type, timestamp.; Write unit tests for issueCredentials and revokeCredentials including that getCredentials after revocation does not return the secret.
**Deliverable:** PartnerCredentialService class with issueCredentials, getCredentials, revokeCredentials methods; audit log integration; unit tests.
**Acceptance / logic checks:**
- After issueCredentials, partner_credential.api_key_hash column contains a bcrypt string (starts with $2a$12$); raw api_key is not stored anywhere in DB.
- After issueCredentials, calling getCredentials returns status=ACTIVE, created_at, key_id but no api_key or hmac_secret field.
- revokeCredentials sets DB status=REVOKED and deletes vault entry; subsequent vault read of secrets/partner/{id}/hmac_key returns 404.
- Audit log entry for CREDENTIAL_ISSUED contains actor_id, partner_id, timestamp but no plaintext credential value.
- Unit test: bcrypt verify(plaintext_api_key, stored_hash) returns true; bcrypt verify(wrong_key, stored_hash) returns false.
**Depends on:** 13.1-T03, 13.1-T04

### 13.1-T11 — Implement HMAC-SHA256 request signing and server-side signature verification  _(55 min)_
**Context:** SEC-09 §3.3 and API-05 §3.2: every Partner API request must include X-Api-Key and X-Signature (HMAC-SHA256). Signature formula: HMAC-SHA256(key=partner_hmac_secret, data=HTTP_METHOD + newline + request_path + newline + timestamp_unix + newline + SHA256(request_body)). Headers required: X-Timestamp (Unix epoch seconds), X-Nonce (UUID v4). Replay protection: reject if X-Timestamp deviates more than +-300s from server time (error TIMESTAMP_DRIFT). Nonce cache: server caches seen nonces for 600s; duplicate nonce -> HTTP 401 REPLAY_DETECTED.
**Steps:** Implement HmacSignatureVerifier.verify(request): fetches partner hmac_secret from vault using X-Api-Key to look up partner_id, computes expected_sig = HMAC-SHA256(secret, METHOD+LF+path+LF+timestamp+LF+SHA256(body)), compares constant-time with X-Signature.; Add timestamp drift check: if abs(server_epoch - X-Timestamp) > 300, return HTTP 401 with error code TIMESTAMP_DRIFT.; Add nonce cache (Redis, TTL=600s): if X-Nonce already exists in cache, return HTTP 401 REPLAY_DETECTED; otherwise store nonce.; Wire HmacSignatureVerifier as a Spring Security filter or API Gateway plugin that runs before all partner endpoints.; Write unit tests for: valid signature passes, wrong secret fails, timestamp drift > 300s fails, duplicate nonce fails.
**Deliverable:** HmacSignatureVerifier class; Redis nonce cache integration; Spring Security filter wiring; unit tests.
**Acceptance / logic checks:**
- Valid request (correct HMAC, timestamp within 300s, fresh nonce) passes with HTTP 200.
- Request with X-Timestamp 301s in the past returns HTTP 401 with body containing TIMESTAMP_DRIFT.
- Request with a previously used X-Nonce (within 600s) returns HTTP 401 with body containing REPLAY_DETECTED.
- Request with a tampered body (body differs from what was signed) returns HTTP 401 (signature mismatch).
- Comparison uses MessageDigest.isEqual or equivalent constant-time comparison to prevent timing attacks; code review confirms no string.equals() on signature bytes.
**Depends on:** 13.1-T10

### 13.1-T12 — Implement mTLS optional onboarding for Partner API at API Gateway  _(50 min)_
**Context:** SEC-09 §2.4 and API-05 §3.4: Partners requiring enhanced transport security may request mTLS onboarding. GME provisions a client certificate per Partner. When mTLS is active for a partner, the X-Signature header is still required (defence in depth). mTLS is configured at the API Gateway level; no endpoint changes are needed. The partner's mTLS flag is stored in the partner_credential record.
**Steps:** Add column mtls_enabled (BOOLEAN, default FALSE) and mtls_cert_fingerprint (VARCHAR(128)) to the partner_credential table via a Flyway migration.; Create an ops script or Admin portal action that generates a client TLS certificate for a partner via the internal CA (Vault PKI), stores the cert fingerprint in partner_credential.mtls_cert_fingerprint, and sets mtls_enabled=TRUE.; Configure the API Gateway: for partners with mtls_enabled=TRUE, require a client certificate on TLS handshake and verify its fingerprint matches partner_credential.mtls_cert_fingerprint; HMAC X-Signature is still required after mTLS passes.; Write a unit/integration test: partner with mtls_enabled=TRUE, valid mTLS cert AND valid HMAC passes; valid mTLS cert but invalid HMAC returns 401; no cert but valid HMAC returns TLS handshake failure.; Audit-log mTLS enablement event.
**Deliverable:** Flyway migration for mtls_enabled/mtls_cert_fingerprint; API Gateway mTLS policy; integration test; audit log event.
**Acceptance / logic checks:**
- Partner with mtls_enabled=FALSE can connect without a client cert (normal HMAC-only flow) and succeeds with valid HMAC.
- Partner with mtls_enabled=TRUE and wrong client cert fingerprint gets a TLS-level rejection before any HTTP response.
- Partner with mtls_enabled=TRUE, valid cert, but invalid HMAC returns HTTP 401 (mTLS passed, HMAC failed).
- Flyway migration is reversible (down migration drops the columns cleanly).
- Audit log entry for MTLS_ENABLED contains partner_id, actor_id, cert_fingerprint, timestamp.
**Depends on:** 13.1-T06, 13.1-T11

### 13.1-T13 — Implement Admin portal SSO and MFA authentication  _(55 min)_
**Context:** SEC-09 §3.1: Admin portal uses GME corporate SSO (SAML 2.0 or OIDC). MFA is required for all logins (hardware token or authenticator app). Session duration: 8 hours max; idle timeout 30 minutes. Failed login lockout: 5 consecutive failures locks account; unlocked by IT admin. Privileged actions (finance settlement approval, partner activation) require re-authentication within the session.
**Steps:** Configure Spring Security SAML 2.0 / OIDC integration pointing to the GME IdP (stub IdP in sandbox using Keycloak or similar).; Enforce MFA assertion in the SAML/OIDC response: reject logins where the AMR (Authentication Methods References) claim does not include 'mfa' or 'totp'.; Set session max-age to 28800s (8h) and idle timeout to 1800s (30min); implement idle-timeout tracking via last-request timestamp in session store.; Implement failed-login lockout: track consecutive_failures per user_id in Redis; after 5 failures, set account status=LOCKED in admin_user table; only IT-Admin role can unlock.; Mark partner activation and settlement approval endpoints as requiring re-authentication: if session re_auth_at is older than 900s, return HTTP 403 with error REAUTH_REQUIRED.
**Deliverable:** Spring Security SSO/MFA config; session timeout enforcement; account lockout logic; re-auth guard on privileged endpoints.
**Acceptance / logic checks:**
- Login attempt without MFA claim in SAML assertion returns HTTP 403; session is not created.
- After 5 consecutive failed logins, the 6th attempt returns HTTP 403 ACCOUNT_LOCKED regardless of correct password; admin_user.status=LOCKED in DB.
- GET /admin/session returns session_expires_at = login_time + 8h; after 30min of inactivity, session is invalidated.
- POST /admin/partners/{id}/activate with re_auth_at older than 900s returns HTTP 403 REAUTH_REQUIRED.
- Correct SSO login with valid MFA creates a session and returns HTTP 200; session store contains user_id, roles, mfa_verified=true.
**Depends on:** 13.1-T03

### 13.1-T14 — Implement Partner Portal password policy, TOTP MFA, and lockout  _(55 min)_
**Context:** SEC-09 §3.2: Partner Portal users authenticate with email + password + TOTP. Password policy: minimum 12 characters, complexity required, bcrypt hashed (cost >= 12) at rest. Session: 8 hours max, idle timeout 15 minutes. MFA: TOTP required; recovery codes issued at setup, stored hashed. Lockout: 5 consecutive failures -> 15-minute lockout (not permanent, unlike Admin portal).
**Steps:** Implement PasswordPolicyValidator: reject passwords shorter than 12 chars or missing any of: uppercase, lowercase, digit, special character.; Hash accepted passwords with bcrypt(cost=12) before storing in portal_user.password_hash.; Implement TOTP enrollment: generate a TOTP secret (Base32, 160-bit), return as QR URI (otpauth://), generate 8 recovery codes (random hex), store hashed recovery codes in portal_user_recovery_code table.; Implement login flow: verify password hash, then require TOTP (RFC 6238, 30s window, 1-step drift tolerance); reject with HTTP 401 TOTP_INVALID on failure.; Implement lockout: track consecutive_failures in Redis; after 5 failures, set lockout_until = now + 900s; requests during lockout return HTTP 429 ACCOUNT_LOCKED with Retry-After header.
**Deliverable:** PasswordPolicyValidator; TOTP enrollment and verification service; bcrypt storage; lockout logic with Redis TTL; unit tests.
**Acceptance / logic checks:**
- Password 'Password1!' (11 chars) is rejected; 'Password1!x' (12 chars) passes.
- Password without a special character is rejected with error MISSING_SPECIAL_CHAR.
- TOTP verification with a code 1 step (30s) ahead or behind current time passes; code 2 steps away fails.
- After 5 failed login attempts, the 6th attempt within 15 min returns HTTP 429 with Retry-After: 900 (approx); after 15 minutes, login succeeds with correct credentials.
- Recovery code: using one of the 8 hashed recovery codes in place of TOTP succeeds; the same recovery code cannot be used a second time (marked used in DB).

### 13.1-T15 — Implement RBAC permission enforcement for Admin portal endpoints  _(50 min)_
**Context:** SEC-09 §3.4: Admin portal has three roles: Admin (full), Ops (transaction monitoring, settlement mgmt, config view, rule margin update), Finance (settlement approval, revenue reporting, BOK reports, prefunding view). The permission matrix is explicit: e.g., Rule margin update = Admin(Full) + Ops(Full); Partner registration/activation = Admin only; Audit log view = Admin only; BOK report generation = Admin + Finance.
**Steps:** Define a RolePermission enum mapping each role (ADMIN, OPS, FINANCE) to its allowed capabilities (e.g. PARTNER_ACTIVATE, RULE_MARGIN_UPDATE, AUDIT_LOG_VIEW, BOK_REPORT_GENERATE).; Implement a @RequiresPermission(capability) Spring Security annotation and AOP interceptor that checks the authenticated user's role against the permission matrix; throws HTTP 403 FORBIDDEN if not allowed.; Annotate each controller method with the correct @RequiresPermission (e.g. POST /admin/partners/{id}/activate -> PARTNER_ACTIVATE; GET /admin/audit-log -> AUDIT_LOG_VIEW; POST /admin/bok-reports -> BOK_REPORT_GENERATE).; Write unit tests for each row of the permission matrix covering at least one allowed and one disallowed role per capability.; Audit-log all permission denials with actor_id, attempted_capability, timestamp.
**Deliverable:** RolePermission enum; @RequiresPermission interceptor; annotated controllers; unit tests covering permission matrix; audit logging of denials.
**Acceptance / logic checks:**
- OPS role attempting POST /admin/partners/{id}/activate returns HTTP 403 FORBIDDEN.
- FINANCE role attempting GET /admin/audit-log returns HTTP 403 FORBIDDEN.
- ADMIN role can call all three of the above endpoints successfully (HTTP 200).
- OPS role attempting POST /admin/rules/{id}/margin (margin update) returns HTTP 200.
- Permission denial events appear in audit_log with event_type=PERMISSION_DENIED, actor_role=OPS, attempted_capability=PARTNER_ACTIVATE.
**Depends on:** 13.1-T13

### 13.1-T16 — Implement partner_id ownership enforcement for all Partner API queries  _(50 min)_
**Context:** SEC-09 §3.5 and OWASP API1: every data record belonging to a partner includes a non-nullable partner_id FK column. All API endpoints returning partner-scoped data must enforce WHERE partner_id = authenticated_partner_id at the query layer. Partner Portal backend must never accept partner_id from the client request body. Threat T-05: IDOR — Partner queries another partner's transaction ID.
**Steps:** Create a PartnerScopeFilter Spring component that extracts partner_id from the authenticated JWT/API-key session and stores it in a request-scoped PartnerContext bean.; Modify all repository/DAO query methods that return partner-scoped entities (Transaction, PrefundingBalance, SettlementRecord) to accept a required partnerIdScope parameter and append AND partner_id = :scope to every query.; Add a compile-time test that fails if any repository method fetching partner-scoped data lacks the partnerIdScope parameter.; Write an IDOR integration test: Partner A authenticates; attempts GET /v1/payments/{txn_id_of_partner_B}; asserts HTTP 404 (not 403, to avoid confirming resource existence).; Ensure no controller reads partner_id from the request body or query string; all scoping uses PartnerContext.
**Deliverable:** PartnerScopeFilter; partnerIdScope parameter on all partner-scoped DAO queries; IDOR integration test returning HTTP 404.
**Acceptance / logic checks:**
- IDOR integration test: Partner A querying a transaction ID belonging to Partner B receives HTTP 404, not HTTP 200 or HTTP 403.
- PartnerContext.getPartnerId() returns the authenticated partner_id, not any value from request body; verified by test that passes a different partner_id in the JSON body and confirms query uses session value.
- All Transaction repository query methods include partnerIdScope in their signatures; grep confirms no findById without scope.
- PartnerContext is request-scoped (new instance per request); concurrent requests for different partners do not share context.
- Admin endpoints (Admin portal) still return data across all partners when role is ADMIN or OPS (scoping bypassed for admin context).
**Depends on:** 13.1-T11, 13.1-T15

### 13.1-T17 — Implement append-only audit log with SHA-256 hash chain  _(55 min)_
**Context:** SEC-09 §6.1 and §6.3: the audit log is immutable. Each configuration change must log: actor_id, actor_role, event_type, entity_type, entity_id, changed_field, previous_value, new_value, timestamp (UTC ISO-8601, server-generated), ip_address. Each audit record includes a prev_hash field (SHA-256 of the previous record's canonical serialisation) to form a hash chain. No UPDATE or DELETE is permitted on audit records. The DB table uses insert-only permissions for the application service account.
**Steps:** Create Flyway migration V{n}__create_audit_log.sql: table audit_log with columns id (UUID PK), actor_id (VARCHAR), actor_role (VARCHAR), event_type (VARCHAR), entity_type (VARCHAR), entity_id (UUID), changed_field (VARCHAR), previous_value (TEXT), new_value (TEXT), timestamp (TIMESTAMPTZ DEFAULT now()), ip_address (VARCHAR), record_hash (CHAR(64)), prev_hash (CHAR(64)).; Grant the application DB role INSERT only on audit_log (no UPDATE, no DELETE); verify with REVOKE UPDATE, DELETE ON audit_log FROM app_role.; Implement AuditLogService.append(event): compute prev_hash = SHA-256 of the last record's canonical JSON; compute record_hash = SHA-256 of the new record's canonical JSON including prev_hash; insert.; Add a daily AuditIntegrityJob that re-computes and verifies the entire hash chain; logs INTEGRITY_OK or INTEGRITY_BREACH (alert severity P1 per SEC-09 §10.4).; Write unit test: insert three records; verify chain: record[2].prev_hash == record[1].record_hash.
**Deliverable:** audit_log Flyway migration with insert-only grant; AuditLogService.append with hash chain; AuditIntegrityJob; unit test for chain.
**Acceptance / logic checks:**
- Unit test: three-record chain passes integrity check; mutating record[1].new_value breaks the chain at record[2] (prev_hash mismatch detected).
- psql: UPDATE audit_log SET new_value='x' WHERE id='{id}' returns ERROR: permission denied for table audit_log when using app_role.
- AuditLogService.append writes a record_hash that equals SHA-256 of the record's canonical JSON serialization; verified by manual re-computation in test.
- AuditIntegrityJob run on a clean chain logs INTEGRITY_OK; run after manually inserting a record with wrong prev_hash logs INTEGRITY_BREACH.
- Each audit record's timestamp is server-generated (DEFAULT now()); the application does not pass a client-supplied timestamp.

### 13.1-T18 — Implement config-change audit logging for partner, scheme, and rule mutations  _(50 min)_
**Context:** SEC-09 §6.1 requires every change to a partner, scheme, or rule record to be logged in the audit log with: actor_id, actor_role, event_type (e.g. RULE_MARGIN_UPDATED, PARTNER_ACTIVATED), entity_type/entity_id, changed_field, previous_value, new_value, timestamp, ip_address. This uses the AuditLogService from T17. Config changes must apply only to NEW transactions (existing committed transactions are unaffected by rate/margin changes).
**Steps:** Create AuditableEntityListener (JPA EntityListener or Spring AOP @Around) that intercepts save/update/delete on Partner, Scheme, and Rule entities.; Before each update, fetch the current DB state to capture previous_value for each changed field.; Call AuditLogService.append(...) with all required fields; extract actor_id, actor_role, and ip_address from the authenticated session context.; Ensure the audit log write is part of the same DB transaction as the entity change (so no change can occur without an audit entry).; Write an integration test: update Rule.m_a from 1.5 to 2.0 as Ops user; assert audit_log contains one entry with event_type=RULE_MARGIN_UPDATED, changed_field=m_a, previous_value=1.5, new_value=2.0, actor_role=OPS.
**Deliverable:** AuditableEntityListener or AOP advice wired to Partner/Scheme/Rule mutations; integration test verifying audit entry content.
**Acceptance / logic checks:**
- Integration test: Rule.m_a update from 1.5 to 2.0 produces audit_log row with previous_value=1.5, new_value=2.0, event_type=RULE_MARGIN_UPDATED.
- Audit entry is absent if the transaction rolls back (e.g. validation fails); verified by injecting a post-update exception and asserting no partial audit entry.
- Actor_id and ip_address in audit entry match the authenticated session values, not any client-supplied values.
- Deleting a Partner entity creates an audit entry with event_type=PARTNER_DELETED and previous_value containing the partner's name and status.
- AuditLogService is called in the same DB transaction (same @Transactional scope) as the entity update.
**Depends on:** 13.1-T17, 13.1-T15

### 13.1-T19 — Implement 8-step transaction event trail logging  _(55 min)_
**Context:** SEC-09 §6.1: every payment transaction must produce an 8-step event log: (1) rate_quote_issued, (2) payment_initiated, (3) prefund_deducted (OVERSEAS only), (4) scheme_request_sent, (5) scheme_response_received, (6) transaction_committed (rate/amount values locked), (7) webhook_dispatched, (8) webhook_delivered. Each step record includes: transaction_id, event, timestamp, duration_ms (from previous step), and relevant state snapshot (e.g. collection_usd, payout_usd_cost at step 6).
**Steps:** Create Flyway migration V{n}__create_txn_event_trail.sql: table txn_event_trail with columns id (UUID PK), transaction_id (UUID FK), event (VARCHAR(64)), timestamp (TIMESTAMPTZ DEFAULT now()), duration_ms (INT), state_snapshot (JSONB).; Implement TxnEventTrailService.record(transactionId, event, prevTimestamp, stateSnapshot): computes duration_ms, inserts row.; Wire TxnEventTrailService calls at each of the 8 points in the payment pipeline (rate engine, payment controller, prefunding service, scheme adapter, commit service, webhook dispatcher).; At step 6 (transaction_committed), state_snapshot must include: collection_usd, payout_usd_cost, collection_margin_usd, payout_margin_usd, offer_rate_coll.; Write an integration test for a complete OVERSEAS MPM payment: assert all 8 events are present in order, with duration_ms >= 0, and step 6 snapshot contains non-null collection_usd.
**Deliverable:** txn_event_trail Flyway migration; TxnEventTrailService; wiring at all 8 points; integration test asserting 8 events.
**Acceptance / logic checks:**
- Integration test OVERSEAS MPM: 8 events are present in txn_event_trail ordered by timestamp; event names match the exact list in SEC-09 §6.1.
- Integration test LOCAL (no prefunding): 7 events are present (prefund_deducted is absent); no null duration_ms.
- Step 6 (transaction_committed) snapshot: state_snapshot JSONB contains collection_usd, payout_usd_cost, collection_margin_usd, payout_margin_usd, offer_rate_coll as numeric values.
- duration_ms for each step is >= 0 and < 60000 (sanity bound); a negative duration_ms causes the insert to fail with a check constraint.
- txn_event_trail rows are INSERT-only (no UPDATE/DELETE grant for app_role); verified with psql permission check.
**Depends on:** 13.1-T17

### 13.1-T20 — Implement API access log redaction and structured logging  _(40 min)_
**Context:** SEC-09 §6.4 requires every inbound API request to be logged at the API Gateway with: timestamp, partner_id, method, path, query_string (sensitive params redacted), response_code, response_time_ms, ip_address, request_id. Sensitive fields (API key, HMAC secret, password) must never be written to access logs. SEC-09 §6.5 requires logs to be in structured JSON format for SIEM export.
**Steps:** Configure the API Gateway access log format as structured JSON with fields: timestamp, partner_id, method, path, query_string_redacted, response_code, response_time_ms, ip_address, request_id.; Implement a QueryStringRedactor that replaces the values of parameters named: api_key, secret, password, hmac_secret, token with [REDACTED] in the logged query string.; Add a RequestBodyRedactor that strips any JSON field named password, secret, api_key, or signing_secret from request body logs before writing.; Configure log output to a structured JSON log file or stream (no plain-text multi-line format); each log entry is one JSON line.; Write a unit test: given a request with query ?api_key=ABC&amount=100, the logged query_string_redacted equals ?api_key=[REDACTED]&amount=100.
**Deliverable:** API Gateway access log config with JSON format and QueryStringRedactor; RequestBodyRedactor; unit tests.
**Acceptance / logic checks:**
- Unit test: query string ?api_key=secret123&amount=500 -> logged as ?api_key=[REDACTED]&amount=500.
- Unit test: request body {"password":"hunter2","partner_id":"uuid"} -> logged body contains partner_id but password field is absent.
- A sample log file line parses as valid JSON with all required fields present (timestamp, partner_id, method, path, response_code, response_time_ms, ip_address, request_id).
- grep 'hunter2' on the access log file after a login attempt returns zero matches.
- response_time_ms is a numeric value (not a string); verified by parsing the JSON log entry.

### 13.1-T21 — Configure log retention policies for all log categories  _(40 min)_
**Context:** SEC-09 §6.2 defines retention periods: transaction event trail 7 years, configuration change audit 7 years, API access logs 1 year, authentication events 3 years, SFTP batch file logs 7 years. These must be enforced by storage lifecycle policies, not application code. Log data must remain in Korean region (SEC-09 §7.3).
**Steps:** Create S3 lifecycle policies (or equivalent) for each log bucket: txn-event-trail-logs (expiry 2555 days), config-audit-logs (expiry 2555 days), api-access-logs (expiry 365 days), auth-event-logs (expiry 1095 days), sftp-batch-logs (expiry 2555 days).; Ensure all log buckets are in AWS ap-northeast-2 (Seoul) or equivalent Korean region; block public access on all buckets.; Add a Glacier/cold-storage transition rule after 90 days for the 7-year buckets to reduce cost.; Configure S3 Object Lock (WORM) on txn-event-trail and config-audit log buckets to prevent deletion within retention period.; Document the lifecycle policy IDs and bucket names in OPERATIONS_RUNBOOK.md.
**Deliverable:** IaC lifecycle policies for five log categories; WORM config on 7-year buckets; Korean region constraint; OPERATIONS_RUNBOOK.md section.
**Acceptance / logic checks:**
- IaC plan shows txn-event-trail-logs bucket has expiry=2555 days and Object Lock enabled in COMPLIANCE mode.
- IaC plan shows api-access-logs bucket has expiry=365 days and no Object Lock (not required by spec).
- All five buckets have region=ap-northeast-2 (or equivalent Korean region); IaC plan confirms.
- A test upload to the txn-event-trail-logs bucket followed by an immediate DELETE attempt returns AccessDenied (Object Lock prevents deletion).
- auth-event-logs bucket has expiry=1095 days (3 years = 365*3); IaC plan confirms.
**Depends on:** 13.1-T08

### 13.1-T22 — Implement rate limiting at API Gateway (per-partner and auth-failure IP block)  _(45 min)_
**Context:** SEC-09 §5.4 and §2.2: API requests 300 rpm per partner (configurable); quote requests 120 rpm per partner (configurable); failed auth attempts 20 per IP per 10 minutes -> 15-minute block (not configurable). HTTP 429 with Retry-After header on breach. Threat T-10: flood of GET /v1/rates exhausts rate engine. Rate limits are stored per partner in config and enforced at the API Gateway.
**Steps:** Configure API Gateway rate limiting plugin (Kong Rate Limiting or AWS WAF) with two per-partner counters keyed by X-Api-Key: api_rate_limit_rpm (default 300) and quote_rate_limit_rpm (default 120); values read from partner config.; Configure a fixed-window or sliding-window counter for failed auth attempts per IP (no api_key context); threshold 20 per 10-minute window; on breach, block IP for 900 seconds.; Ensure the partner api_rate_limit_rpm and quote_rate_limit_rpm values are configurable per partner in the partner_config table and loaded at gateway startup / cache refresh.; Return HTTP 429 with header Retry-After: {seconds_until_reset} on rate limit breach for both per-partner and IP blocks.; Write integration tests: burst 301 requests in 60s for one partner -> 301st gets 429; the other partner is unaffected.
**Deliverable:** API Gateway rate limiting config for per-partner counters and IP block; partner-configurable rpm values; HTTP 429 with Retry-After; integration tests.
**Acceptance / logic checks:**
- Integration test: 301 API requests in 60s from Partner A -> 301st returns HTTP 429 with Retry-After header.
- Integration test: Partner A hitting rate limit does not affect Partner B (counter is keyed by partner, not global).
- Integration test: 21 failed auth attempts from IP 10.0.0.1 within 10 minutes -> 21st attempt returns HTTP 429; attempt from 10.0.0.2 succeeds normally.
- Retry-After header value in 429 response is a positive integer (seconds); parsing it as int does not throw.
- Partner with custom api_rate_limit_rpm=600 can make 600 requests/min without hitting 429.
**Depends on:** 13.1-T11

### 13.1-T23 — Implement partner API key rotation with overlap window  _(55 min)_
**Context:** SEC-09 §9.1 and §9.2: scheduled key rotation generates new API key + HMAC secret; Ops sets an overlap window (default 7 days) during which both old and new credentials are accepted. After the overlap window, old credentials are automatically revoked. Emergency rotation revokes old credentials immediately. Rotation event is audit-logged with actor, timestamp, overlap window end date.
**Steps:** Implement PartnerCredentialService.rotateCredentials(partnerId, overlapDays): issues new credentials (T10 logic), sets old credential status=PENDING_REVOCATION and revoke_at = now + overlapDays*86400s, returns new plaintext credentials once.; Add a scheduled job (CredentialRevocationJob, runs every hour) that finds credentials with status=PENDING_REVOCATION and revoke_at <= now(), sets status=REVOKED, and deletes vault entry.; Implement HmacSignatureVerifier to accept both ACTIVE and PENDING_REVOCATION credentials during the overlap window.; Implement emergencyRotate(partnerId): issues new credentials and immediately sets old to REVOKED (no overlap window); notifies partner via webhook (or logs for Ops follow-up).; Write integration test: rotate credentials with 0-day overlap; old credential rejected immediately; new credential accepted.
**Deliverable:** rotateCredentials and emergencyRotate in PartnerCredentialService; CredentialRevocationJob; dual-credential acceptance logic; integration tests.
**Acceptance / logic checks:**
- Integration test 7-day overlap: request signed with old credential at t+1d succeeds; request signed with old credential at t+8d (after revoke_at) returns HTTP 401 CREDENTIALS_REVOKED.
- Integration test 0-day (emergency) overlap: request signed with old credential immediately after rotation returns HTTP 401 CREDENTIALS_REVOKED.
- CredentialRevocationJob: credential with revoke_at = now()-1s is revoked on next job run; credential with revoke_at = now()+3600s is not revoked.
- Audit log entry for CREDENTIAL_ROTATED contains actor_id, partner_id, overlap_window_end_date, timestamp.
- HmacSignatureVerifier accepts both old (PENDING_REVOCATION) and new (ACTIVE) credentials simultaneously during the overlap window.
**Depends on:** 13.1-T10, 13.1-T11

### 13.1-T24 — Implement SFTP key rotation procedure and vault key pointer update  _(50 min)_
**Context:** SEC-09 §9.3: SFTP key rotation requires generating a new Ed25519 key pair in vault, extracting the public key via Admin portal, registering with KFTC, then activating the new key (vault pointer updated) and revoking the old key. Old key must NOT be revoked until KFTC confirms the new key is active. Minimum lead time: 5 business days. Rotation event is audit-logged.
**Steps:** Add vault path secrets/scheme/zeropay/sftp_private_key_pending for the new key during rotation; keep the active key at secrets/scheme/zeropay/sftp_private_key until activation.; Implement SftpKeyRotationService.initiateRotation(): generates new Ed25519 pair in vault at the _pending path, returns new public key for operator to submit to KFTC.; Implement SftpKeyRotationService.activateNewKey(): moves vault pointer (copies _pending to active path, deletes _pending), revokes old key, audit-logs SFTP_KEY_ROTATED.; Implement SftpKeyRotationService.cancelRotation(): deletes _pending vault entry without affecting active key.; Write unit tests for initiateRotation, activateNewKey (verify old key deleted and active key updated), and cancelRotation (verify pending deleted, active unchanged).
**Deliverable:** SftpKeyRotationService with initiateRotation, activateNewKey, cancelRotation; audit log on activation; unit tests.
**Acceptance / logic checks:**
- After initiateRotation: vault has both active key and _pending key; SFTP client still uses the active key (verified by mock vault returning correct path).
- After activateNewKey: vault _pending path is absent; active path contains the new key; old key is deleted from vault.
- After cancelRotation: vault _pending path is absent; active path is unchanged.
- Audit log entry for SFTP_KEY_ROTATED contains actor_id, timestamp, event_type=SFTP_KEY_ROTATED; absent if cancelRotation is called instead.
- Unit test: calling activateNewKey without prior initiateRotation throws SftpKeyRotationStateException.
**Depends on:** 13.1-T05, 13.1-T17

### 13.1-T25 — Implement DEK rotation and re-encryption for column-level encrypted data  _(55 min)_
**Context:** SEC-09 §2.7: Data encryption keys (DEK) rotate annually or on suspected compromise. Re-encryption re-encrypts data with new DEK; old DEK is revoked. The column merchant.account_no uses a DEK at vault path secrets/db/column_keys/merchant_account_no (from T07). Re-encryption must not cause downtime; it should use a dual-key phase where both old and new DEK are accepted during migration.
**Steps:** Implement DekRotationService.initiateRotation(columnKey): generates a new DEK in vault at secrets/db/column_keys/{key}_v{n+1}; stores a version mapping in vault metadata.; Implement a batch re-encryption job (DekReencryptionJob) that reads each merchant.account_no row, decrypts with old DEK, re-encrypts with new DEK, updates the row, and records the dek_version used per row (add column account_no_dek_version SMALLINT).; During the migration window, the DAO decrypts using the version indicated by account_no_dek_version; after all rows are migrated, old DEK is revoked.; Add a Flyway migration for the account_no_dek_version column.; Write a unit test: row encrypted with DEK v1; after initiateRotation and re-encryption, row is decryptable with DEK v2 and not with DEK v1.
**Deliverable:** DekRotationService; DekReencryptionJob; account_no_dek_version Flyway migration; unit test.
**Acceptance / logic checks:**
- Unit test: after re-encryption, decrypting with new DEK returns plaintext; decrypting with old DEK throws DecryptionException.
- DekReencryptionJob processes rows in batches of 1000; does not load all rows into memory (verified by code review showing paginated query).
- account_no_dek_version column is NOT NULL with a default value matching the current active DEK version.
- After all rows have account_no_dek_version = new version, DekRotationService.revokeOldDek() removes the old DEK from vault; subsequent decrypt with old DEK throws.
- Re-encryption job is idempotent: running it twice does not corrupt data (a row already on new DEK version is skipped).
**Depends on:** 13.1-T07, 13.1-T03

### 13.1-T26 — Implement TLS certificate auto-renewal and expiry alerting  _(45 min)_
**Context:** SEC-09 §2.7: TLS certificates rotate every 90 days via ACME/Let's Encrypt or internal PKI. Automated renewal must alert 14 days before expiry. Both the external TLS certificate (Partner API, portal) and internal mTLS certificates (from T06) need auto-renewal.
**Steps:** Configure cert-manager (Kubernetes) or AWS Certificate Manager with auto-renewal for the external API Gateway TLS certificate; set renewal threshold to 30 days before expiry.; Configure Vault PKI issuer (from T06) to auto-renew internal service certs when remaining TTL < 20% of 90-day lease (i.e., < 18 days).; Implement a daily CertExpiryMonitorJob that checks all managed certificates; for any certificate with expiry < 14 days, emit an alert log entry at WARN level with cert_name, expiry_date, days_remaining.; Write an integration test: create a mock cert expiring in 13 days; run CertExpiryMonitorJob; assert a WARN log entry is emitted containing days_remaining=13.; Document the renewal procedure in OPERATIONS_RUNBOOK.md: steps for manual intervention if auto-renewal fails.
**Deliverable:** Cert-manager / ACM auto-renewal config; CertExpiryMonitorJob with 14-day alert; integration test; OPERATIONS_RUNBOOK.md section.
**Acceptance / logic checks:**
- Integration test: cert expiring in 13 days causes CertExpiryMonitorJob to emit WARN log with days_remaining=13.
- Integration test: cert expiring in 15 days does NOT emit a WARN log (threshold is < 14 days).
- Vault PKI issuer config shows ttl=90d and renew_before_expiry=18d (verified in Vault config output).
- OPERATIONS_RUNBOOK.md has a titled section 'TLS Certificate Renewal' with at least 4 steps including escalation contact.
- CertExpiryMonitorJob runs daily (cron schedule configured); verified by Scheduler config inspection.
**Depends on:** 13.1-T06, 13.1-T09

### 13.1-T27 — Implement security incident alerting for P1/P2 events  _(55 min)_
**Context:** SEC-09 §10.4 defines key security alert triggers: failed auth attempts > 20/IP/10min (P3), any REPLAY_DETECTED event (P2), audit log hash chain break (P1), prefunding balance = 0 for a partner (P2), unusual transaction velocity > 3x 7-day average per partner per 5min (P3), SFTP delivery failure past deadline (P2), secrets vault access anomaly (P1). SEC-09 §10.2 requires P1/P2 triage within 15 minutes.
**Steps:** Create a SecurityAlertService with method emitAlert(severity, alertType, context): writes a structured JSON alert to the security-alerts log stream and to a dedicated SECURITY_ALERT table (id UUID, severity VARCHAR, alert_type VARCHAR, context JSONB, emitted_at TIMESTAMPTZ).; Wire emitAlert calls: in HmacSignatureVerifier emit P2 REPLAY_DETECTED on duplicate nonce; in AuditIntegrityJob emit P1 AUDIT_CHAIN_BREAK on any hash mismatch; in PrefundingService emit P2 PREFUND_BALANCE_ZERO when balance reaches 0; in VaultAccessMonitor emit P1 VAULT_ACCESS_ANOMALY on out-of-pattern access.; Create a velocity alert job (VelocityAlertJob, runs every 5 minutes): compute per-partner txn count in last 5 min; compare to 7-day rolling average; if > 3x, emit P3 VELOCITY_SPIKE.; Ensure SECURITY_ALERT table is append-only (same insert-only grant as audit_log).; Write unit tests: REPLAY_DETECTED event produces alert with severity=P2; balance=0 produces P2 PREFUND_BALANCE_ZERO.
**Deliverable:** SecurityAlertService; SECURITY_ALERT table (Flyway migration); wired alert calls at all 7 trigger points; VelocityAlertJob; unit tests.
**Acceptance / logic checks:**
- Unit test: duplicate nonce in HmacSignatureVerifier triggers SecurityAlertService.emitAlert(P2, REPLAY_DETECTED, ...).
- Unit test: PrefundingService.deduct resulting in balance=0 triggers emitAlert(P2, PREFUND_BALANCE_ZERO, {partner_id}).
- Unit test: VelocityAlertJob with partner having 10 txns in 5min and 7-day average 3 txns/5min (3.33 txns x 3 = 10) emits P3 alert; partner with 9 txns does not.
- SECURITY_ALERT table has no UPDATE or DELETE grant for app_role (verified by psql).
- SecurityAlertService.emitAlert produces a single-line JSON log entry with fields: severity, alert_type, context, emitted_at.
**Depends on:** 13.1-T11, 13.1-T17, 13.1-T19

### 13.1-T28 — Add secret scanning and SAST to CI pipeline  _(50 min)_
**Context:** SEC-09 §5.6 requires the CI pipeline to include: SAST (e.g. SonarQube or Semgrep) on every PR, dependency scanning for CVEs (critical/high block merge), secret scanning (e.g. TruffleHog/git-secrets; any finding blocks merge and triggers immediate rotation reminder), and container image scanning (e.g. Trivy/Grype). SEC-09 §5.7 requires branch protection with passing CI + approval before merge to main.
**Steps:** Add a GitHub Actions (or GitLab CI) job 'sast' that runs Semgrep with the java, secrets, and owasp rule packs on every PR; fail the build on any HIGH or CRITICAL finding.; Add a job 'dependency-scan' that runs OWASP Dependency-Check or Snyk; fail the build on any CVE with CVSS >= 7.0.; Add a job 'secret-scan' that runs TruffleHog (--only-verified) or git-secrets on every commit pushed to any branch; fail the build on any verified credential finding and emit a comment on the PR: 'Potential secret detected. Rotate immediately if real.'; Add a job 'container-scan' that runs Trivy on the built Docker image; fail on CRITICAL CVEs in OS packages.; Configure branch protection on main: require all four CI jobs to pass + at least one reviewer approval before merge; block force-push.
**Deliverable:** CI pipeline config (.github/workflows/security.yml or equivalent) with sast, dependency-scan, secret-scan, container-scan jobs; branch protection rules.
**Acceptance / logic checks:**
- PR with a hardcoded test password string (e.g. password=supersecret123) fails the secret-scan job with a non-zero exit code.
- PR introducing a dependency with a known CVE CVSS >= 7.0 fails the dependency-scan job.
- PR with a Semgrep HIGH finding (e.g. SQL injection pattern) fails the sast job.
- A direct push to main branch without a PR is blocked (branch protection rule active).
- CI pipeline file includes all four job definitions; each job has a 'fail-on' threshold documented in the job step.

### 13.1-T29 — Unit tests: HMAC signing and replay protection vectors  _(40 min)_
**Context:** SEC-09 §3.3: HMAC-SHA256 signature formula: HMAC-SHA256(key=partner_hmac_secret, data=METHOD+LF+path+LF+timestamp_unix+LF+SHA256(body)). Replay protection: +-300s timestamp window; nonce cache 600s. These unit tests cover the HmacSignatureVerifier from T11 with explicit input vectors.
**Steps:** Write test vector T29-TV-01: method=POST, path=/v1/payments, timestamp=1700000000, body={"target_payout":50000}, hmac_secret=0123456789abcdef0123456789abcdef. Compute expected HMAC and assert verify() returns true.; Write T29-TV-02: same inputs but body tampered to {"target_payout":60000} after signing; assert verify() returns false.; Write T29-TV-03: valid signature but X-Timestamp = server_time - 301 (drift > 300s); assert HTTP 401 TIMESTAMP_DRIFT.; Write T29-TV-04: valid signature, valid timestamp, X-Nonce = 'test-nonce-1' used twice within 600s; first call passes, second returns HTTP 401 REPLAY_DETECTED.; Write T29-TV-05: valid signature, valid timestamp, X-Nonce reused after 601s (nonce expired from cache); second call passes (nonce no longer cached).
**Deliverable:** Unit test class HmacSignatureVerifierTest with 5 named test vectors T29-TV-01 through T29-TV-05.
**Acceptance / logic checks:**
- T29-TV-01: verify() returns true for the correct pre-computed HMAC vector.
- T29-TV-02: verify() returns false when body is tampered; no exception thrown, just false.
- T29-TV-03: verify() throws or returns HTTP 401 with error code TIMESTAMP_DRIFT for drift of 301s.
- T29-TV-04: second call with duplicate nonce returns HTTP 401 REPLAY_DETECTED; Redis mock confirms nonce was cached.
- T29-TV-05: second call with same nonce after 601s (mock clock advanced) returns true (nonce evicted from cache).
**Depends on:** 13.1-T11

### 13.1-T30 — Unit tests: audit log hash chain integrity verification vectors  _(40 min)_
**Context:** SEC-09 §6.3: the audit log uses SHA-256 hash chain. Each record's record_hash = SHA-256(canonical JSON of record including prev_hash). The daily AuditIntegrityJob re-computes and verifies the chain. These tests cover the hash chain logic from T17 with explicit vectors.
**Steps:** Write T30-TV-01: insert record R1 (prev_hash=0000...0000 for genesis); compute expected record_hash = SHA-256 of R1 canonical JSON; assert AuditLogService inserts the correct hash.; Write T30-TV-02: insert R1 then R2; assert R2.prev_hash == R1.record_hash.; Write T30-TV-03: insert R1, R2, R3; mutate R2.new_value in DB directly (simulating tampering); run AuditIntegrityJob; assert it returns INTEGRITY_BREACH at position R3 (R3.prev_hash does not match recomputed R2.record_hash).; Write T30-TV-04: insert 100 records; AuditIntegrityJob completes without INTEGRITY_BREACH.; Write T30-TV-05: insert two records concurrently (thread pool size 2); assert exactly 2 records are inserted, chain is valid (no duplicate prev_hash), no transaction conflict.
**Deliverable:** Unit/integration test class AuditLogHashChainTest with 5 named test vectors T30-TV-01 through T30-TV-05.
**Acceptance / logic checks:**
- T30-TV-01: computed record_hash matches manually computed SHA-256 of the canonical JSON string (deterministic serialisation).
- T30-TV-02: R2.prev_hash equals R1.record_hash byte-for-byte.
- T30-TV-03: AuditIntegrityJob reports INTEGRITY_BREACH pointing to R3 after R2 is tampered.
- T30-TV-04: 100-record chain passes integrity check with INTEGRITY_OK.
- T30-TV-05: concurrent inserts produce a valid chain; no two records share the same prev_hash (chain is linear).
**Depends on:** 13.1-T17

### 13.1-T31 — Unit tests: partner data isolation (IDOR) vectors  _(40 min)_
**Context:** SEC-09 §3.5 and T-05: IDOR — Partner A must not be able to read Partner B transactions. Every partner-scoped query enforces WHERE partner_id = authenticated_partner_id. UUID v4 identifiers prevent enumeration. These tests use explicit partner IDs and transaction IDs.
**Steps:** Write T31-TV-01: create Partner A (UUID p-a) and Partner B (UUID p-b); create Transaction T1 belonging to p-b; authenticate as p-a; GET /v1/payments/{T1.id}; assert HTTP 404.; Write T31-TV-02: authenticate as p-a; GET /v1/payments?partner_id={p-b} (partner_id injected in query string); assert response only contains p-a transactions (query string value ignored; scoping uses session).; Write T31-TV-03: authenticate as p-a; POST /v1/payments with body partner_id={p-b}; assert transaction is created under p-a not p-b.; Write T31-TV-04: Admin user with OPS role; GET /v1/admin/payments/{T1.id}; assert HTTP 200 (admin bypasses partner scope).; Write T31-TV-05: authenticate as p-a; attempt sequential UUIDs for transaction IDs (10 UUIDs not belonging to p-a); all return HTTP 404 (not 403, to avoid confirming existence).
**Deliverable:** Integration test class PartnerDataIsolationTest with 5 named test vectors T31-TV-01 through T31-TV-05.
**Acceptance / logic checks:**
- T31-TV-01: HTTP 404 for p-a accessing p-b transaction.
- T31-TV-02: response list contains only transactions with partner_id=p-a regardless of query string.
- T31-TV-03: created transaction has partner_id=p-a in DB; body-supplied partner_id=p-b is silently ignored.
- T31-TV-04: OPS admin gets HTTP 200 for p-b transaction without authentication as p-b.
- T31-TV-05: all 10 sequential UUID lookups return 404; none return 403.
**Depends on:** 13.1-T16, 13.1-T15

### 13.1-T32 — Unit tests: at-rest encryption and DEK rotation vectors  _(40 min)_
**Context:** T07 implements AES-256-GCM column encryption for merchant.account_no. T25 implements DEK rotation with version tracking. These unit tests verify encrypt/decrypt correctness, wrong-key rejection, and DEK version migration.
**Steps:** Write T32-TV-01: encrypt merchant.account_no='KR123456789012' with DEK v1; assert ciphertext != plaintext; decrypt with DEK v1; assert result == 'KR123456789012'.; Write T32-TV-02: decrypt with wrong DEK throws DecryptionException (not returning garbage data silently).; Write T32-TV-03: decrypt with correct DEK but corrupted ciphertext (flip 1 bit) throws AEADBadTagException (GCM auth tag fails).; Write T32-TV-04: DEK rotation - encrypt with v1, run DekReencryptionJob, decrypt result with v2; assert plaintext unchanged; assert decryption with v1 throws after revocation.; Write T32-TV-05: DekReencryptionJob is idempotent - run twice on same data; second run skips rows with account_no_dek_version already at v2; assert row count updated matches only first run.
**Deliverable:** Unit test class AtRestEncryptionTest with 5 named test vectors T32-TV-01 through T32-TV-05.
**Acceptance / logic checks:**
- T32-TV-01: roundtrip encrypt->decrypt with correct DEK returns original plaintext 'KR123456789012'.
- T32-TV-02: DecryptionException is thrown; no plaintext or garbled string returned.
- T32-TV-03: AEADBadTagException (or equivalent) thrown on 1-bit corruption; not a silent wrong-data return.
- T32-TV-04: after DEK rotation, plaintext is recoverable with v2 DEK; v1 DEK decryption fails after revocation.
- T32-TV-05: second DekReencryptionJob run updates 0 rows (idempotent); first run updated N > 0 rows.
**Depends on:** 13.1-T07, 13.1-T25


## WBS 13.3 — Threat model (STRIDE) & mitigations
### 13.3-T01 — Define ThreatRegister domain model and enums for STRIDE categories  _(30 min)_
**Context:** WBS 13.3 delivers the STRIDE threat model and mitigations register for GMEPay+. The register covers 10 named threats (T-01 through T-10) with categories: Spoofing, Tampering, Repudiation, Information Disclosure, Denial of Service, Elevation of Privilege. A Java domain object is needed so all downstream tickets can reference a typed model rather than loose strings.
**Steps:** Create package com.gmepayplus.security.threat; Define enum StrideCategory {SPOOFING, TAMPERING, REPUDIATION, INFORMATION_DISCLOSURE, DENIAL_OF_SERVICE, ELEVATION_OF_PRIVILEGE}; Define enum ThreatStatus {OPEN, MITIGATED, ACCEPTED, TRANSFERRED}; Define immutable record ThreatEntry(String id, String title, StrideCategory category, String attackVector, String mitigation, ThreatStatus status); Add a ThreatRegister class with a static unmodifiable list of all 10 entries matching SEC-09 §4; Write unit test asserting exactly 10 entries load and IDs are T-01..T-10
**Deliverable:** Package com.gmepayplus.security.threat with StrideCategory enum, ThreatEntry record, ThreatRegister class, and companion unit test ThreatRegisterTest
**Acceptance / logic checks:**
- ThreatRegister.ALL returns exactly 10 entries
- Every entry has a non-null, non-blank id, title, category, attackVector, mitigation
- Entry with id T-01 has category SPOOFING; T-02 has REPUDIATION; T-03 has TAMPERING
- Entry with id T-05 has category INFORMATION_DISCLOSURE; T-10 has DENIAL_OF_SERVICE
- No two entries share the same id (uniqueness assertion in test)

### 13.3-T02 — Implement HMAC-SHA256 request signing utility for Partner API (T-01 mitigation)  _(35 min)_
**Context:** Threat T-01 (Partner API credential theft, Spoofing) is mitigated by HMAC-SHA256 signature binding the key to request body + timestamp. The canonical signature string is: HTTP_METHOD + newline + request_path + newline + timestamp_unix + newline + SHA256(request_body). The HMAC key is the partner_hmac_secret stored encrypted in the secrets vault (distinct from the API key). This utility is used by the API Gateway signature verifier.
**Steps:** Create class HmacSignatureService in com.gmepayplus.security.auth; Implement String computeSignature(String method, String path, long timestampUnix, byte[] requestBody, String hmacSecret) using HMAC-SHA256; Compute SHA256 of requestBody as lower-case hex; concatenate canonical string with literal newlines; Return lower-case hex of the HMAC output; Write unit test with fixed inputs: method=POST, path=/v1/payments, timestamp=1700000000, body={} → assert exact expected hex value (pre-computed reference value); Ensure method throws IllegalArgumentException if hmacSecret is null or blank
**Deliverable:** Class HmacSignatureService with computeSignature method and HmacSignatureServiceTest with at least 3 test vectors including empty body and non-ASCII path
**Acceptance / logic checks:**
- Given method=GET, path=/v1/rates, timestamp=1700000001, body=empty-bytes, secret=testsecret → output matches pre-computed reference hex
- Null hmacSecret throws IllegalArgumentException before any crypto operation
- Body with Unicode characters is SHA-256 hashed as UTF-8 bytes consistently
- Changing a single byte of requestBody produces a completely different signature (avalanche check in test)
**Depends on:** 13.3-T01

### 13.3-T03 — Implement X-Timestamp replay window validator (T-02 mitigation)  _(25 min)_
**Context:** Threat T-02 (Replay attack, Repudiation) is mitigated by rejecting requests where X-Timestamp deviates more than 300 seconds from server UTC time (HTTP 401, error code TIMESTAMP_DRIFT). X-Timestamp is Unix epoch seconds supplied in the request header. Server time is obtained from a Clock dependency so tests can inject a fixed instant.
**Steps:** Create class TimestampValidator in com.gmepayplus.security.replay; Inject Clock clock in constructor (use Clock.systemUTC() as default); Implement ValidationResult validate(long requestTimestampUnix): compute delta = abs(clock.millis()/1000 - requestTimestampUnix); if delta > 300 return ValidationResult.REJECTED with reason TIMESTAMP_DRIFT else return ValidationResult.ACCEPTED; Define enum ValidationResult {ACCEPTED, REJECTED} with an optional String reason field; Write unit tests: exactly 300 seconds drift is ACCEPTED; 301 seconds is REJECTED; timestamp 0 is REJECTED; future timestamp 300s ahead is ACCEPTED; future 301s ahead is REJECTED
**Deliverable:** Class TimestampValidator, ValidationResult enum, TimestampValidatorTest covering boundary conditions
**Acceptance / logic checks:**
- delta == 300 returns ACCEPTED (boundary inclusive)
- delta == 301 returns REJECTED with reason TIMESTAMP_DRIFT
- Timestamp of 0 (Unix epoch) is rejected as drift >> 300 against any current time
- Injecting a fixed Clock in tests makes output deterministic
- Negative timestamps are handled without exception (treated as far-past, rejected)
**Depends on:** 13.3-T01

### 13.3-T04 — Implement nonce cache for replay detection (T-02 mitigation)  _(35 min)_
**Context:** Threat T-02 mitigation also requires X-Nonce uniqueness: the server caches seen nonces (UUID v4) for 600 seconds. Duplicate nonce within the window → HTTP 401, REPLAY_DETECTED. The cache must be thread-safe and automatically expire entries after 600 seconds. Use Caffeine or a ConcurrentHashMap with expiry backed by the injected Clock.
**Steps:** Create class NonceCache in com.gmepayplus.security.replay; Implement boolean checkAndStore(String nonce): return true if nonce is new (store it, expiry = now+600s), false if already seen; Use Caffeine cache with expireAfterWrite(600, SECONDS) or equivalent; fall back to ConcurrentHashMap with timestamp if Caffeine not available; Inject Clock for testability; default to Clock.systemUTC(); Write unit tests: first call with a UUID returns true; second call with same UUID within 600s returns false; after simulated 601s expiry, same UUID returns true again; empty-string nonce throws IllegalArgumentException
**Deliverable:** Class NonceCache and NonceCacheTest covering at-least-4 scenarios including expiry simulation
**Acceptance / logic checks:**
- checkAndStore(uuid) returns true on first call
- checkAndStore(uuid) returns false on second call with same uuid within 600s
- Expired nonce (601s elapsed) is treated as new and returns true
- Blank or null nonce throws IllegalArgumentException before cache lookup
- Concurrent calls with distinct UUIDs all return true (thread-safety spot-check with 100 parallel threads)
**Depends on:** 13.3-T01

### 13.3-T05 — Create API Gateway security filter integrating signature, timestamp, and nonce checks  _(55 min)_
**Context:** Threats T-01 and T-02 require the API Gateway layer to reject requests failing HMAC signature, timestamp window, or nonce reuse checks. In the Spring Boot application, this is a servlet filter or Spring Security filter applied before all Partner API endpoints. It reads headers X-Api-Key, X-Signature, X-Timestamp, X-Nonce; invokes HmacSignatureService, TimestampValidator, and NonceCache; and returns HTTP 401 with a JSON body {errorCode, message} on any failure.
**Steps:** Create class PartnerApiSecurityFilter implements Filter in com.gmepayplus.security.filter; Inject HmacSignatureService, TimestampValidator, NonceCache, and PartnerCredentialService (looks up hmac_secret by X-Api-Key); On each request: (1) validate timestamp, (2) check nonce, (3) compute expected signature over method+path+timestamp+body, (4) compare signatures with constant-time comparison; Return HTTP 401 with JSON {errorCode: TIMESTAMP_DRIFT | REPLAY_DETECTED | SIGNATURE_INVALID | UNKNOWN_API_KEY, message: ...} for each failure; Register filter on URL pattern /v1/**; Write integration test with MockMvc: valid request passes; missing X-Nonce returns 401 SIGNATURE_INVALID; replayed nonce returns 401 REPLAY_DETECTED
**Deliverable:** PartnerApiSecurityFilter class registered in Spring Security filter chain, with PartnerApiSecurityFilterTest covering 6 scenarios
**Acceptance / logic checks:**
- Valid request with correct signature, fresh nonce, current timestamp returns HTTP 2xx (passes filter)
- Request with X-Timestamp 301 seconds old returns HTTP 401 with errorCode TIMESTAMP_DRIFT
- Replayed X-Nonce (submitted twice within 600s) returns HTTP 401 REPLAY_DETECTED
- Signature mismatch (body tampered) returns HTTP 401 SIGNATURE_INVALID
- Unknown X-Api-Key returns HTTP 401 UNKNOWN_API_KEY
- Signature comparison uses MessageDigest.isEqual or equivalent to prevent timing attacks
**Depends on:** 13.3-T02, 13.3-T03, 13.3-T04

### 13.3-T06 — Enforce server-side amount derivation - reject client-supplied payment amounts (T-03, T-04 mitigations)  _(30 min)_
**Context:** Threat T-03 (amount tampering) and T-04 (prefunding manipulation) are mitigated by computing all payment amounts server-side from the rate engine; amounts from the partner request body are never used as inputs to prefunding deduction or scheme calls. Fields target_payout, collection_amount, payout_usd_cost in POST /v1/payments and POST /v1/payments/cpm/generate are RECEIVED from client only to validate schema; the authoritative values come from RateEngineService using locked rate parameters. This ticket adds an explicit guard.
**Steps:** In PaymentService.initiatePayment(), after parsing the request DTO, assert that collection_amount and payout_usd_cost fields are NOT read from the request DTO for any calculation; Add a comment block SECURITY: amounts derived server-side referencing T-03/T-04 above the rate engine invocation; Add a unit test PaymentAmountImmutabilityTest that constructs a request DTO with deliberately wrong collection_amount=999999 and asserts the resulting payment record has the rate-engine-derived value (e.g. 1234.56), not 999999; Document the guard in JavaDoc on PaymentService.initiatePayment()
**Deliverable:** PaymentService updated with explicit guard comment and PaymentAmountImmutabilityTest asserting client-supplied amounts are ignored
**Acceptance / logic checks:**
- Test passes: request DTO with collection_amount=999999 produces a payment with rate-engine collection_amount != 999999
- No code path in PaymentService reads collection_amount or payout_usd_cost from the inbound DTO for arithmetic
- Unit test asserts exact expected collection_amount produced by the rate engine for a known input (e.g. target_payout=100 KRW at cost_rate_pay=1300, m_a=0.01, m_b=0.01)
- JavaDoc block present on initiatePayment() referencing SECURITY: T-03 T-04
**Depends on:** 13.3-T01

### 13.3-T07 — Implement atomic prefunding deduction with SELECT FOR UPDATE (T-04 mitigation)  _(55 min)_
**Context:** Threat T-04 (prefunding manipulation, Elevation of Privilege) is mitigated by atomic deduction: the prefunding balance for OVERSEAS partners is deducted using a database-level SELECT ... FOR UPDATE within the same transaction as the payment record insert. A forged or concurrent request cannot over-deduct or skip deduction. Deduction happens before any scheme call. Insufficient balance returns HTTP 422 INSUFFICIENT_PREFUNDING. LOCAL partners (type=LOCAL) skip prefunding entirely.
**Steps:** In PrefundingService.deductAtomic(UUID partnerId, BigDecimal usdAmount), open a DB transaction; Execute SELECT balance FROM prefunding_balance WHERE partner_id = ? FOR UPDATE to lock the row; If balance < usdAmount throw InsufficientPrefundingException (maps to HTTP 422, INSUFFICIENT_PREFUNDING); Update balance = balance - usdAmount; commit; Wrap in @Transactional(isolation = SERIALIZABLE) or ensure the FOR UPDATE is executed within the enclosing payment transaction; Write unit test using @DataJpaTest or TestContainers: two concurrent threads each attempt to deduct 60 USD from a 100 USD balance; exactly one succeeds and one fails with InsufficientPrefundingException
**Deliverable:** PrefundingService.deductAtomic() method and PrefundingConcurrencyTest demonstrating exactly-once deduction under race condition
**Acceptance / logic checks:**
- Single thread deducting 50 USD from 100 USD balance leaves exactly 50 USD remaining
- Two concurrent threads each deducting 60 USD from 100 USD results in exactly one success and one InsufficientPrefundingException
- Deduction of exactly 0.00 USD throws IllegalArgumentException (guard against no-op)
- LOCAL partner bypass: calling deductAtomic on a LOCAL-type partner throws UnsupportedOperationException
- Balance never goes negative under any concurrent scenario (DB constraint + application check)
**Depends on:** 13.3-T01

### 13.3-T08 — Implement partner_id ownership enforcement at query layer (T-05 IDOR mitigation)  _(40 min)_
**Context:** Threat T-05 (IDOR, Information Disclosure) is mitigated by enforcing WHERE partner_id = authenticated_partner_id on every query that returns partner-scoped data. The authenticated partner_id comes exclusively from the verified session/token, never from the request body or URL parameter. This ticket implements a query-layer guard applicable to TransactionRepository and PrefundingRepository.
**Steps:** Add method Optional<Transaction> findByIdAndPartnerId(UUID transactionId, UUID partnerId) to TransactionRepository (Spring Data JPA derived query); Add method Optional<PrefundingBalance> findByPartnerId(UUID partnerId) to PrefundingRepository; In TransactionService.getTransaction(UUID txnId, UUID authenticatedPartnerId), call findByIdAndPartnerId; if empty throw TransactionNotFoundException (HTTP 404) regardless of whether the txn exists for another partner; Never expose an unscoped findById method on any partner-data repository in the public service layer; Write test TransactionIdorTest: partner_a requests transaction owned by partner_b → HTTP 404 (not 403, to avoid confirming existence)
**Deliverable:** Updated TransactionRepository and PrefundingRepository with partner-scoped queries, TransactionService guard, and TransactionIdorTest
**Acceptance / logic checks:**
- GET /v1/payments/{id} by partner_a where txn belongs to partner_b returns HTTP 404
- GET /v1/payments/{id} by the owning partner returns HTTP 200 with correct data
- No unscoped findById on transaction or prefunding in service-layer public methods
- Repository query uses both transactionId and partnerId parameters in WHERE clause (verify via JPQL or method name)
- SQL logs in test show partner_id column in WHERE clause for all partner-scoped queries
**Depends on:** 13.3-T01

### 13.3-T09 — Enforce UUID v4 identifiers for all partner, transaction, and rule entities (T-09 mitigation)  _(30 min)_
**Context:** Threat T-09 (enumeration of partner IDs, Information Disclosure) is mitigated by using UUID v4 for all partner, transaction, and rule identifiers so sequential enumeration is infeasible. All JPA entities in GMEPay+ (Partner, Transaction, Rule, Scheme) must use UUID primary keys generated server-side (never auto-increment integers).
**Steps:** Audit all @Entity classes in com.gmepayplus.domain for @Id field type; For any entity using Long/Integer @GeneratedValue(strategy=IDENTITY), update to UUID with @GeneratedValue(generator=uuid2) or @UuidGenerator; Ensure UUID is generated on server (not accepted from client) in all POST endpoints: PaymentController, PartnerController, RuleController; Add a test EntityIdFormatTest asserting that calling POST /v1/payments returns a txn_id matching UUID v4 regex pattern [0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}; Confirm no endpoint accepts a client-supplied transaction_id or partner_id for resource creation
**Deliverable:** All domain entities using UUID v4 PKs and EntityIdFormatTest validating the pattern
**Acceptance / logic checks:**
- POST /v1/payments response txn_id matches UUID v4 regex
- Partner.id, Transaction.id, Rule.id are all UUID type in JPA entities (no Long/Integer PKs)
- Supplying a custom id in POST request body is silently ignored (server generates its own)
- Two consecutive POST /v1/payments calls produce different, non-sequential IDs
- No auto-increment integer sequence visible in schema migration files for partner/transaction/rule tables
**Depends on:** 13.3-T01

### 13.3-T10 — Implement per-partner rate limiting at API Gateway layer (T-10 DoS mitigation)  _(50 min)_
**Context:** Threat T-10 (DoS on quote endpoint, Denial of Service) is mitigated by per-partner rate limiting at the API Gateway: default 300 rpm for general API requests, 120 rpm for GET /v1/rates quote requests. On breach, return HTTP 429 with Retry-After header. Limits are configurable per partner in the partner record. In Spring Boot, implement using a token-bucket filter backed by an in-memory Caffeine cache keyed by (partner_id, endpoint_group).
**Steps:** Create RateLimitService with method RateLimitResult checkLimit(UUID partnerId, RateLimitGroup group): group is GENERAL or QUOTE; Store per-partner token buckets in Caffeine cache; refill tokens at the configured rpm rate using leaky-bucket or token-bucket algorithm; Read per-partner rate limit overrides from partner.api_rate_limit_rpm and partner.quote_rate_limit_rpm (default 300 and 120 if null); In RateLimitFilter (runs after PartnerApiSecurityFilter), call checkLimit; on EXCEEDED return HTTP 429 with header Retry-After: <seconds until bucket refills>; Write test: simulate 121 requests from same partner to GET /v1/rates within 1 minute; assert first 120 return non-429 and 121st returns 429
**Deliverable:** RateLimitService, RateLimitFilter, and RateLimitFilterTest with burst scenario
**Acceptance / logic checks:**
- 121st quote request within 60s from same partner returns HTTP 429
- HTTP 429 response includes Retry-After header with a positive integer value
- 301st general request within 60s returns HTTP 429
- Partner with configured quote_rate_limit_rpm=60 is limited at 60, not the default 120
- Requests from different partner IDs do not share the same bucket (isolation check)
**Depends on:** 13.3-T05

### 13.3-T11 — Implement minimum combined margin constraint enforcement (T-07 mitigation - rate engine guard)  _(30 min)_
**Context:** Threat T-07 (insider config change lowering margins, Tampering) is mitigated in part by the rate engine enforcing m_a + m_b >= 2.0% for cross-border rules. If an Ops user edits a rule to set m_a=0.005 and m_b=0.005 (combined 1%), the rate engine must refuse to compute and the rule save must be blocked. Same-currency rules may have m_a + m_b = 0. A Rule has fields margin_a (m_a) and margin_b (m_b) as BigDecimal, and a boolean is_cross_border.
**Steps:** In RuleValidationService, add void validateMargins(BigDecimal mA, BigDecimal mB, boolean isCrossBorder); If isCrossBorder and mA.add(mB).compareTo(new BigDecimal(0.02)) < 0, throw MarginBelowMinimumException with message combined margin mA+mB must be >= 2.00% for cross-border rules; If not isCrossBorder, allow mA=mB=0 (no minimum); Call validateMargins in RuleService.updateMargins() before persisting and in RateEngineService.compute() as a guard; Write unit tests: mA=0.01, mB=0.01, cross-border → valid; mA=0.009, mB=0.01, cross-border → MarginBelowMinimumException; mA=0.0, mB=0.0, same-currency → valid; mA=0.0199, mB=0.0, cross-border → exception
**Deliverable:** RuleValidationService.validateMargins() method and MarginValidationTest with 6 test vectors
**Acceptance / logic checks:**
- mA=0.01, mB=0.01, cross-border passes validation
- mA=0.009, mB=0.01, cross-border throws MarginBelowMinimumException
- mA=0.0, mB=0.0, same-currency passes validation (0% allowed)
- mA=0.02, mB=0.0, cross-border passes (exactly 2% boundary is valid)
- mA=0.0199, mB=0.0, cross-border throws exception (1.99% is below minimum)
- RateEngineService.compute() calls validateMargins and propagates exception before any calculation
**Depends on:** 13.3-T01

### 13.3-T12 — Implement audit log append-only write with SHA-256 hash chain (T-07, T-06 mitigations)  _(55 min)_
**Context:** Threat T-07 (insider config change) and T-06 (SFTP tampering) are mitigated by an immutable audit trail. Each audit_log record includes fields: id (UUID), actor_id, actor_role, event_type, entity_type, entity_id, changed_field, previous_value, new_value, timestamp (UTC), ip_address, and prev_hash (SHA-256 of the previous record's canonical JSON). No UPDATE or DELETE is permitted on audit_log. The hash chain enables tamper detection.
**Steps:** Create AuditLogRepository extending JpaRepository<AuditLog, UUID> but do NOT expose save(T) override that allows updates (only saveNew is public); In AuditLogService.append(AuditLogEntry entry), query the latest record to obtain its prev_hash, compute SHA-256 of that record's canonical JSON, set it as prev_hash on the new entry, then persist; Define canonical JSON as field-sorted UTF-8 JSON excluding prev_hash itself to avoid circular dependency; Ensure AuditLog entity has no @PreUpdate or @PrePersist that would allow modification after creation; Write test AuditHashChainTest: insert 3 records; verify prev_hash of record 2 == SHA-256(canonical-JSON of record 1); verify prev_hash of record 3 == SHA-256 of record 2
**Deliverable:** AuditLogService.append() with hash-chain logic and AuditHashChainTest verifying chaining for 3 consecutive records
**Acceptance / logic checks:**
- prev_hash of record N+1 equals SHA-256 hex of canonical JSON of record N
- First audit record in an empty table has prev_hash = SHA-256 of empty string or a defined genesis constant
- Calling update on AuditLog entity via repository raises UnsupportedOperationException
- Two records inserted in sequence have different prev_hash values
- Canonical JSON is deterministic: same fields in same order produce the same SHA-256
**Depends on:** 13.3-T01

### 13.3-T13 — Implement daily audit log integrity check job  _(45 min)_
**Context:** SEC-09 §6.3 requires a daily job to verify the SHA-256 hash chain on the audit_log table. Breaks in the chain (any record where prev_hash != SHA-256 of previous record) must be detected and logged to a separate admin-only monitoring endpoint at severity P1. The job should page through all audit records in insertion order and verify each link.
**Steps:** Create AuditIntegrityCheckJob as a Spring @Scheduled task (cron = 0 2 * * *, i.e. 02:00 UTC daily); Page through audit_log ordered by created_at ASC in batches of 1000 using JPA Slice/Pageable; For each record (except the first), compute SHA-256 of previous record canonical JSON and compare to current record.prev_hash; On mismatch, publish AuditChainBreakEvent with the offending record id and sequence position; log at ERROR and write to IntegrityCheckResult table; Write AuditIntegrityCheckJobTest with 10 records (5 clean, then 1 tampered prev_hash, then 4 clean); assert job detects exactly 1 break at position 6
**Deliverable:** AuditIntegrityCheckJob class and AuditIntegrityCheckJobTest
**Acceptance / logic checks:**
- Job with clean chain of 10 records reports 0 breaks
- Job detects a single tampered record at position 6 and reports break at that ID
- AuditChainBreakEvent contains the tampered record id
- Job completes processing 1000 records without OutOfMemoryError (pages in batches)
- On break detection, ERROR log line contains audit record id and position index
**Depends on:** 13.3-T12

### 13.3-T14 — Implement RBAC permission enforcement for Admin portal - rule margin update restricted to Admin+Ops  _(35 min)_
**Context:** Threat T-07 (insider config change) mitigation requires: Rule margin update is allowed for Admin role (full) and Ops role (full per permission matrix in SEC-09 §3.4.3), but NOT for Finance role. Partner activation is Admin-only. This ticket implements Spring Security method-level @PreAuthorize annotations and a PermissionEvaluator for the AdminSystem backend.
**Steps:** Define enum AdminRole {ADMIN, OPS, FINANCE}; Annotate RuleService.updateMargins() with @PreAuthorize(hasAnyRole(ADMIN, OPS)); Annotate PartnerService.activatePartner() with @PreAuthorize(hasRole(ADMIN)); Annotate PartnerService.createPartner() with @PreAuthorize(hasRole(ADMIN)); Write RbacEnforcementTest: call updateMargins as OPS role → succeeds; call updateMargins as FINANCE role → AccessDeniedException; call activatePartner as OPS role → AccessDeniedException; call activatePartner as ADMIN role → succeeds
**Deliverable:** @PreAuthorize annotations on RuleService and PartnerService, and RbacEnforcementTest with 4 scenario assertions
**Acceptance / logic checks:**
- updateMargins called with OPS role returns successfully
- updateMargins called with FINANCE role throws AccessDeniedException (HTTP 403)
- activatePartner called with OPS role throws AccessDeniedException
- activatePartner called with ADMIN role returns successfully
- Unauthenticated call to any protected method throws AuthenticationException (HTTP 401)
**Depends on:** 13.3-T01

### 13.3-T15 — Implement Admin portal authentication: SSO session config, idle timeout 30 min, MFA flag check  _(50 min)_
**Context:** Threat T-08 (credential theft via admin portal, Spoofing) is mitigated by SSO + MFA mandatory (§3.1). Admin portal uses corporate SSO (SAML 2.0 or OIDC). Session maximum is 8 hours; idle timeout is 30 minutes. Privileged actions (partner activation, settlement approval) require re-authentication. Account lockout after 5 failures. This ticket configures the Spring Security session management for the Admin backend.
**Steps:** In AdminSecurityConfig, set session.maximumSessionsPerUser(1) and session.invalidSessionUrl(/login?expired); Configure session idle timeout at 30 minutes using HttpSessionEventPublisher and server.servlet.session.timeout=30m; Add MfaRequiredFilter that checks session attribute mfa_verified == true; if not, redirect to /mfa/challenge; Add ReauthRequiredFilter for endpoints matching /admin/partners/*/activate and /admin/settlements/*/approve requiring re-authentication within the last 5 minutes (session attribute last_reauth_at); Write AdminSessionTest: session after 31 minutes idle is invalidated; MFA-unverified session redirected to /mfa/challenge; reauth required for activation endpoint
**Deliverable:** AdminSecurityConfig with session settings, MfaRequiredFilter, ReauthRequiredFilter, and AdminSessionTest
**Acceptance / logic checks:**
- Session with last_activity 31 minutes ago is invalidated and returns 302 to /login?expired
- Request to any admin endpoint without mfa_verified=true is redirected to /mfa/challenge
- POST /admin/partners/{id}/activate without recent reauth (last_reauth_at > 5 min ago) returns 302 to /reauth
- Valid SSO session with mfa_verified=true and recent reauth can activate a partner
- Concurrent login from second device invalidates the first session (maximumSessions=1)
**Depends on:** 13.3-T14

### 13.3-T16 — Implement Partner Portal TOTP MFA and account lockout (T-08 mitigation)  _(50 min)_
**Context:** Partner Portal users (Partner Admin, Partner Viewer) authenticate with email + password + TOTP (SEC-09 §3.2). Password minimum 12 characters, bcrypt hashed. Account lockout after 5 consecutive failures for 15 minutes. Session max 8 hours, idle 15 minutes. Recovery codes are issued at MFA setup, stored hashed. This ticket covers the authentication service for the Partner Portal backend.
**Steps:** In PartnerPortalAuthService.login(email, password, totpCode), verify bcrypt(password) then verify TOTP code using a standard TOTP library (e.g. GoogleAuth or java-otp); Track failed_login_count and last_failed_at per portal user; on 5th consecutive failure set locked_until = now + 15 min; On login attempt while locked_until > now, return HTTP 401 ACCOUNT_LOCKED with remaining lock time; On successful login, reset failed_login_count = 0; Write PartnerPortalAuthTest: correct credentials unlock after 15 min; 5 failures in sequence lock the account; 6th attempt while locked returns ACCOUNT_LOCKED; correct TOTP after 4 failures resets counter to 0
**Deliverable:** PartnerPortalAuthService with lockout logic and PartnerPortalAuthTest with 5 scenarios
**Acceptance / logic checks:**
- 5 consecutive wrong passwords set locked_until to now+15 minutes
- 6th attempt while locked returns HTTP 401 with errorCode ACCOUNT_LOCKED
- Successful TOTP login resets failed_login_count to 0
- Wrong TOTP code (even with correct password) increments failed_login_count
- Recovery code accepted as alternative to TOTP; each recovery code is single-use (hashed in DB)
**Depends on:** 13.3-T01

### 13.3-T17 — Implement secrets vault integration - no credentials in config files or environment variables  _(45 min)_
**Context:** SEC-09 §2.3 mandates that partner HMAC signing keys, SFTP private keys, DB credentials, and ZeroPay API credentials are stored encrypted in a secrets vault (HashiCorp Vault or AWS Secrets Manager) and injected at runtime. No secret may appear in application.yml, environment variables, or source code. This ticket implements a VaultSecretLoader that retrieves secrets at startup and a configuration test that asserts no secret patterns exist in config files.
**Steps:** Create VaultSecretLoader implementing InitializingBean; inject VaultTemplate (Spring Vault) or AwsSecretsManagerClient; Load paths: secret/gmepayplus/db, secret/gmepayplus/sftp-private-key, secret/gmepayplus/partner-hmac-keys/{partner_id}; Expose SecretAccessor.getDbPassword(), SecretAccessor.getSftpPrivateKey(), SecretAccessor.getPartnerHmacSecret(UUID) as typed getters; Write NoCredentialsInConfigTest that reads all .yml files under src/main/resources and asserts none match regex (password|secret|private_key)\s*:\s*\S{8,}; Write VaultSecretLoaderTest using a mock vault that returns test values; assert all getters return expected non-null values
**Deliverable:** VaultSecretLoader, SecretAccessor, NoCredentialsInConfigTest, and VaultSecretLoaderTest
**Acceptance / logic checks:**
- NoCredentialsInConfigTest finds 0 matches for the credential regex in all resource .yml files
- VaultSecretLoaderTest: mock vault returning test-secret for db-password causes SecretAccessor.getDbPassword() to return test-secret
- Null vault response for any required secret throws SecretNotAvailableException at startup
- SFTP private key is returned as a String (PEM format) from getSftpPrivateKey()
- Credentials are never written to application logs (log appender test: no log line contains the retrieved secret value)
**Depends on:** 13.3-T01

### 13.3-T18 — Implement SFTP host key pinning for ZeroPay connection (T-06 mitigation)  _(45 min)_
**Context:** Threat T-06 (SFTP file tampering) is mitigated in part by host key pinning: GMEPay+ rejects SFTP connections to ZeroPay if the host key does not match the pinned value. The pinned host public key is stored in the secrets vault (not in code). Connection is refused and an alert is raised if the host key changes. Uses JSch or Apache SSHD library.
**Steps:** In ZeroPaySftpClient, inject a HostKeyVerifier that retrieves the expected ZeroPay host public key fingerprint from SecretAccessor.getZeroPayHostKey(); Implement HostKeyVerifier.verify(hostname, port, serverKey): compute fingerprint of serverKey; compare to stored fingerprint; if mismatch throw HostKeyMismatchException and publish HostKeyMismatchEvent; On HostKeyMismatchException, log ERROR with hostname and presented fingerprint (not the key itself), and do NOT proceed with SFTP operations; Write ZeroPaySftpHostKeyTest: mock SSH server with correct key → connection succeeds; mock server with wrong key → HostKeyMismatchException raised; Ensure pinned fingerprint is SHA-256 format (not MD5)
**Deliverable:** ZeroPaySftpClient HostKeyVerifier implementation and ZeroPaySftpHostKeyTest
**Acceptance / logic checks:**
- Connection to mock server with matching host key fingerprint succeeds (no exception)
- Connection to mock server with different host key fingerprint throws HostKeyMismatchException
- HostKeyMismatchEvent is published when mismatch detected
- Error log on mismatch includes the presented fingerprint but NOT the private key
- Fingerprint comparison uses SHA-256 format
**Depends on:** 13.3-T17

### 13.3-T19 — Implement audit logging for SFTP batch file generation (T-06 mitigation)  _(40 min)_
**Context:** Threat T-06 (SFTP file tampering) mitigation requires an audit log entry recording the SHA-256 hash of each ZeroPay batch file at the time of generation, before transmission. This enables post-hoc verification that the transmitted file matches the generated file. The audit log entry event_type = SFTP_FILE_GENERATED, entity_type = sftp_batch_file.
**Steps:** In ZeroPayBatchFileService.generateFile(batchType, records), after writing the file bytes to the staging volume, compute SHA256 hex of the byte array; Call AuditLogService.append() with event_type=SFTP_FILE_GENERATED, entity_type=sftp_batch_file, entity_id=filename, new_value=sha256hex; Before transmission, re-compute SHA256 of the staged file and compare to the audit log entry; if mismatch throw FileIntegrityException; Write SftpFileAuditTest: generate a test batch file; assert audit record created with correct sha256; modify file bytes; assert pre-transmission check throws FileIntegrityException
**Deliverable:** ZeroPayBatchFileService with hash recording and SftpFileAuditTest
**Acceptance / logic checks:**
- Audit log record with event_type=SFTP_FILE_GENERATED is created for each generated batch file
- Audit record new_value field equals SHA-256 hex of the generated file bytes
- Modifying file content after generation causes pre-transmission integrity check to throw FileIntegrityException
- Audit record entity_id matches the batch filename (e.g. ZP0061_20260605.csv)
- SHA-256 is computed over raw bytes (not base64 or text representation)
**Depends on:** 13.3-T12

### 13.3-T20 — Implement input validation for monetary amounts, currency codes, and partner IDs (T-03, T-04)  _(40 min)_
**Context:** SEC-09 §5.1 requires server-side validation of all API inputs. Monetary amounts must be positive decimals within per-partner max limits; currency codes must be ISO 4217 whitelist; partner/transaction IDs must be UUID v4. Invalid inputs return HTTP 422 with structured error. This ticket implements a reusable InputValidator class and applies it in PaymentRequestValidator.
**Steps:** Create InputValidator in com.gmepayplus.security.validation with static methods: validatePositiveAmount(BigDecimal amount, String fieldName), validateCurrencyCode(String code), validateUuidV4(String uuid, String fieldName), validateWebhookUrl(String url); validateCurrencyCode checks against a whitelist Set<String> {KRW, USD, JPY, ...} (at minimum KRW and USD); unknown code throws ValidationException(HTTP 422, INVALID_CURRENCY_CODE); validateUuidV4 checks regex [0-9a-f]{8}-[0-9a-f]{4}-4...pattern; invalid throws ValidationException(HTTP 422, INVALID_UUID); Apply all validators in PaymentRequestValidator.validate(PaymentRequest); Write InputValidatorTest: amount=-1 throws exception; amount=0 throws exception; ccy=XYZ throws exception; uuid=12345 throws exception; valid inputs pass
**Deliverable:** InputValidator class, updated PaymentRequestValidator, and InputValidatorTest with 10 test vectors
**Acceptance / logic checks:**
- target_payout=-1 returns HTTP 422 with errorCode INVALID_AMOUNT
- currency_code=XYZ returns HTTP 422 with errorCode INVALID_CURRENCY_CODE
- partner_id=not-a-uuid returns HTTP 422 with errorCode INVALID_UUID
- webhook_url=http://... (not HTTPS) returns HTTP 422 with errorCode INVALID_WEBHOOK_URL
- Valid POST /v1/payments request with correct fields passes all validators without exception
**Depends on:** 13.3-T01

### 13.3-T21 — Implement idempotency key caching for payment-mutating endpoints (T-02, T-04)  _(45 min)_
**Context:** SEC-09 §5.2 requires all payment-mutating endpoints to accept a client-supplied Idempotency-Key (UUID v4). The server caches the response keyed by (partner_id, idempotency_key) for 24 hours. Duplicate requests within the window return the cached response without re-processing. This prevents replay-triggered double-payments and supports T-02 and T-04 mitigations.
**Steps:** Create IdempotencyService with method IdempotencyResult checkOrStore(UUID partnerId, String idempotencyKey, Supplier<ResponseEntity<?>> action); If (partnerId, idempotencyKey) exists in cache (Caffeine, 24h TTL), return the cached ResponseEntity; Otherwise call action.get(), cache the result, and return it; Validate idempotencyKey is UUID v4 format before cache lookup; invalid format → HTTP 422 INVALID_IDEMPOTENCY_KEY; Apply IdempotencyService in PaymentController for POST /v1/payments and POST /v1/payments/cpm/generate; Write IdempotencyTest: same request sent twice within 24h returns identical response body and HTTP 200 on second call without calling PaymentService again
**Deliverable:** IdempotencyService and IdempotencyTest confirming PaymentService.initiatePayment() is called exactly once for duplicate keys
**Acceptance / logic checks:**
- Second call with same (partner_id, idempotency_key) within 24h returns HTTP 200 with same body as first call
- PaymentService.initiatePayment() is called exactly once (verified with Mockito verify(times(1)))
- Non-UUID v4 idempotency key returns HTTP 422 INVALID_IDEMPOTENCY_KEY
- Different idempotency keys from same partner result in separate PaymentService calls
- Missing Idempotency-Key header returns HTTP 400 MISSING_IDEMPOTENCY_KEY
**Depends on:** 13.3-T04

### 13.3-T22 — Implement config change audit logging for Rule margin updates (T-07 mitigation)  _(40 min)_
**Context:** Threat T-07 mitigation requires all Rule margin changes to produce an audit_log entry with fields: actor_id, actor_role, event_type=RULE_MARGIN_UPDATED, entity_type=rule, entity_id={rule_uuid}, changed_field=margin_a or margin_b, previous_value, new_value, timestamp (UTC server-generated), ip_address. The previous_value must never be omitted. This ticket wires AuditLogService into RuleService.updateMargins().
**Steps:** In RuleService.updateMargins(UUID ruleId, BigDecimal newMa, BigDecimal newMb, AuditContext ctx), load the current rule to capture previous margin_a and margin_b values; For each changed field, call AuditLogService.append() once per field with: event_type=RULE_MARGIN_UPDATED, entity_type=rule, entity_id=ruleId, changed_field=margin_a (or margin_b), previous_value=old value as string, new_value=new value as string; Set timestamp = Instant.now(clock) (UTC); set ip_address from AuditContext (populated from request); Ensure both audit records and the rule update are committed in the same DB transaction; Write RuleMarginAuditTest: update margin_a from 0.015 to 0.02 for rule X; assert audit log has 1 record with previous_value=0.015, new_value=0.020, event_type=RULE_MARGIN_UPDATED
**Deliverable:** Updated RuleService.updateMargins() with audit logging and RuleMarginAuditTest
**Acceptance / logic checks:**
- Audit record created with event_type=RULE_MARGIN_UPDATED when margin_a is changed
- previous_value in audit record equals the old margin_a value before update (e.g. 0.015)
- new_value in audit record equals the submitted new value (e.g. 0.020)
- If only margin_b changes, exactly 1 audit record is created for changed_field=margin_b
- If DB transaction rolls back, audit record is also rolled back (atomicity)
**Depends on:** 13.3-T12, 13.3-T11

### 13.3-T23 — Implement partner credential revocation and 7-day overlap window (T-01 mitigation)  _(55 min)_
**Context:** SEC-09 §9.1 requires: on credential rotation, old credentials remain valid during a configurable overlap window (default 7 days). After the window, old credentials are auto-revoked. Immediate revocation (emergency) deactivates old credentials instantly. Revoked credentials return HTTP 401 CREDENTIALS_REVOKED on any API call. This ticket implements CredentialService.rotateCredentials() and CredentialService.revokeCredentials().
**Steps:** In CredentialService.rotateCredentials(UUID partnerId, int overlapDays): generate new API key + HMAC secret, save new credential record with status=ACTIVE, set old credential status=PENDING_REVOCATION with revocation_at = now + overlapDays days; In CredentialService.revokeCredentials(UUID partnerId): immediately set all PENDING_REVOCATION and ACTIVE credentials except the newest to status=REVOKED; Add a @Scheduled job RevokeExpiredCredentialsJob that runs hourly, finds credentials with revocation_at < now and status=PENDING_REVOCATION, and sets them to REVOKED; In PartnerApiSecurityFilter, if credential status=REVOKED return HTTP 401 CREDENTIALS_REVOKED; Write CredentialRotationTest: rotate with overlap=7 days; assert old credential accepted on day 6; assert old credential rejected on day 8 after job runs
**Deliverable:** CredentialService.rotateCredentials(), revokeCredentials(), RevokeExpiredCredentialsJob, and CredentialRotationTest
**Acceptance / logic checks:**
- Old credential accepted within 7-day overlap window
- Old credential rejected after overlap window expires and revocation job runs (HTTP 401 CREDENTIALS_REVOKED)
- New credential accepted immediately after rotation
- Emergency revoke (overlapDays=0) makes old credential return CREDENTIALS_REVOKED immediately
- Rotation event audit logged with event_type=CREDENTIAL_ROTATED, overlap_window_days, and actor_id
**Depends on:** 13.3-T05, 13.3-T12

### 13.3-T24 — Unit test suite: T-01 credential theft scenario end-to-end  _(40 min)_
**Context:** WBS 13.3 requires explicit test tickets. This ticket implements an end-to-end test scenario simulating the T-01 threat: stolen API key without the matching HMAC secret cannot initiate a payment. The test uses MockMvc against the full Spring Security filter chain with the real HmacSignatureService.
**Steps:** In CredentialTheftScenarioTest, create a partner with known API key and HMAC secret; Attempt 1: send POST /v1/payments with valid X-Api-Key but signature computed with wrong HMAC secret → assert HTTP 401 SIGNATURE_INVALID; Attempt 2: send POST /v1/payments with valid X-Api-Key and NO X-Signature header → assert HTTP 401 SIGNATURE_INVALID; Attempt 3: send POST /v1/payments with valid X-Api-Key and correct signature computed with correct HMAC secret → assert HTTP 200 (or 422 for business validation, not auth failure); Assert that attempt 1 and 2 do NOT create any payment record in the database
**Deliverable:** CredentialTheftScenarioTest with 3 sub-scenarios and DB state assertions
**Acceptance / logic checks:**
- Attempt with wrong HMAC secret returns HTTP 401 SIGNATURE_INVALID
- Attempt with missing X-Signature header returns HTTP 401 SIGNATURE_INVALID
- Attempt with correct credentials passes authentication (HTTP != 401)
- No payment record created in DB for failed auth attempts (DB count = 0 after attempts 1 and 2)
- Test uses full Spring Security filter chain (not bypassed with @WithMockUser)
**Depends on:** 13.3-T05

### 13.3-T25 — Unit test suite: T-02 replay attack scenario  _(35 min)_
**Context:** WBS 13.3 requires explicit test tickets. This ticket implements test scenarios simulating T-02 replay attack: captured valid request re-submitted. Covers both timestamp-window and nonce-cache replay detection.
**Steps:** In ReplayAttackScenarioTest, construct a valid signed payment request with timestamp T and nonce N; Submit the request at time T → expect HTTP 200 or 422 (not auth failure); Submit the identical request again (same nonce N, same timestamp T) within 600 seconds → expect HTTP 401 REPLAY_DETECTED; Construct a new request with a fresh nonce but timestamp = T - 301 seconds → expect HTTP 401 TIMESTAMP_DRIFT; Construct a new request with fresh nonce and current timestamp → expect HTTP 200 (passes); Assert no payment record is created for the replayed or stale-timestamp request
**Deliverable:** ReplayAttackScenarioTest with 4 sub-scenarios
**Acceptance / logic checks:**
- Replayed nonce returns HTTP 401 REPLAY_DETECTED
- Stale timestamp (301s old) returns HTTP 401 TIMESTAMP_DRIFT
- Stale timestamp exactly 300s old (boundary) is accepted (HTTP != 401)
- No DB record created for rejected replay attempts
- Fresh request after replay attempt succeeds (system not permanently disrupted)
**Depends on:** 13.3-T03, 13.3-T04, 13.3-T05

### 13.3-T26 — Unit test suite: T-05 IDOR cross-partner access scenario  _(35 min)_
**Context:** WBS 13.3 requires explicit test tickets. This ticket implements the mandatory IDOR test cases referenced in SEC-09 §3.5 and §5.5 (OWASP API1). Partner A must not be able to read Partner B's transactions or prefunding balance, even if they know the UUID.
**Steps:** In IdorScenarioTest, create partner_a and partner_b with separate credentials; Create a transaction T1 owned by partner_b; Authenticate as partner_a and call GET /v1/payments/{T1.id} → assert HTTP 404 (not 403); Authenticate as partner_a and call GET /v1/balance for partner_b's partner_id embedded in a query param → assert HTTP 403 or the call is not routed (partner_id always derived from auth token); Authenticate as partner_b and call GET /v1/payments/{T1.id} → assert HTTP 200 with correct data; Assert no data from partner_b is present in partner_a's response at any point
**Deliverable:** IdorScenarioTest with 4 sub-scenarios and partner isolation assertions
**Acceptance / logic checks:**
- partner_a accessing partner_b transaction UUID returns HTTP 404
- HTTP 404 (not HTTP 403) is returned to avoid confirming existence
- partner_b accessing their own transaction returns HTTP 200 with correct amount
- GET /v1/balance always returns the authenticated partner's balance regardless of any query params
- No partner_b data fields present in any response to partner_a
**Depends on:** 13.3-T08

### 13.3-T27 — Unit test suite: T-04 prefunding manipulation and atomicity  _(35 min)_
**Context:** WBS 13.3 requires explicit test tickets. This ticket tests that prefunding manipulation is impossible: client-supplied collection_amount is ignored, deduction is atomic, and insufficient balance blocks scheme call. Tests complement the concurrency test in 13.3-T07 with integration-level assertions.
**Steps:** In PrefundingManipulationTest, create OVERSEAS partner with prefunding balance of 100 USD; Send payment request with target_payout=5000 KRW and client-supplied collection_amount=1 (manipulated low) → assert resulting deduction equals rate-engine-derived value (not 1); Send payment request with target_payout requiring 150 USD deduction against 100 USD balance → assert HTTP 422 INSUFFICIENT_PREFUNDING and no ZeroPay scheme call; Assert ZeroPaySchemeClient.submit() is never called when prefunding fails (verify with Mockito); Assert balance remains 100 USD after the failed payment
**Deliverable:** PrefundingManipulationTest with 3 sub-scenarios
**Acceptance / logic checks:**
- Manipulated collection_amount=1 is ignored; deduction reflects rate-engine output
- HTTP 422 INSUFFICIENT_PREFUNDING returned when balance insufficient
- ZeroPaySchemeClient.submit() not called for insufficient-balance payment (Mockito verify(never()))
- Balance unchanged (100 USD) after failed payment attempt
- OVERSEAS partner with balance=0 returns HTTP 422 on any payment attempt
**Depends on:** 13.3-T06, 13.3-T07

### 13.3-T28 — Write threat model register document as living Markdown in /docs/security  _(40 min)_
**Context:** WBS 13.3 deliverable is the Threat Model document (SEC-09 threat model). This ticket produces the human-readable threat register as a Markdown file at /docs/security/threat-model.md, populated from the 10 STRIDE threats in the code-level ThreatRegister class. It must be kept in sync with code; a unit test asserts the doc covers all 10 threat IDs.
**Steps:** Create /docs/security/threat-model.md with: overview section, STRIDE threat table (columns: ID, Threat, STRIDE, Attack Vector, Mitigation, Ticket), and a mitigations summary section; Populate the table with all 10 threats from SEC-09 §4 with their implementing ticket IDs (13.3-T02 through 13.3-T27); Add a residual risk section noting T-06 SFTP file tampering has residual risk pending ZeroPay PGP confirmation (OI from SCH-06); Add a references section linking to SEC-09, API-05 §3.3, and SCH-06 §2.3; Write ThreatModelDocTest that loads ThreatRegister.ALL and asserts each entry.id (T-01..T-10) appears as a string in /docs/security/threat-model.md
**Deliverable:** /docs/security/threat-model.md and ThreatModelDocTest asserting all 10 threat IDs are documented
**Acceptance / logic checks:**
- threat-model.md contains exactly 10 rows in the STRIDE threat table (one per T-01..T-10)
- ThreatModelDocTest passes: all IDs T-01 through T-10 found in the document
- Each table row includes a non-empty Mitigation column and at least one ticket reference
- Residual risk section present with at least 1 entry
- References section contains links to SEC-09 and API-05
**Depends on:** 13.3-T01, 13.3-T27


## WBS 13.4 — App security: OWASP API Top 10
### 13.4-T01 — Define InputValidationConfig schema for per-field validation rules  _(35 min)_
**Context:** GMEPay+ must enforce server-side input validation on all Partner API endpoints (SEC-09 §5.1). A central config schema is needed so validation rules are declarative and testable rather than scattered. Fields include: monetary amounts, ISO-4217 currency codes, ISO-3166-1 alpha-2 country codes, UUID v4 IDs, webhook URLs (HTTPS-only), and free-text fields (max length, no HTML/script).
**Steps:** Create Java record InputValidationConfig with fields: maxAmountDecimalScale (int), maxFreeTextLength (int, default 500), currencyCodeWhitelist (Set<String>), countryCodeWhitelist (Set<String>); Create enum ValidatedFieldType with values: MONETARY_AMOUNT, CURRENCY_CODE, COUNTRY_CODE, UUID_V4, WEBHOOK_URL, FREE_TEXT; Add @ConfigurationProperties(prefix='gmepayplus.validation') binding class so whitelist sets load from application.yml; Write a unit test asserting the config bean loads with KRW in currencyCodeWhitelist and KR in countryCodeWhitelist
**Deliverable:** InputValidationConfig.java config record + ValidatedFieldType.java enum + application.yml stanza with ISO-4217 and ISO-3166 seed lists
**Acceptance / logic checks:**
- Config bean loads without error with KRW, USD, KPW in currencyCodeWhitelist
- Config bean loads KR, US in countryCodeWhitelist
- ValidatedFieldType enum contains exactly 6 values: MONETARY_AMOUNT, CURRENCY_CODE, COUNTRY_CODE, UUID_V4, WEBHOOK_URL, FREE_TEXT
- Unit test passes with Spring context loading the @ConfigurationProperties bean

### 13.4-T02 — Implement monetary amount validator (positive decimal, max scale, max value)  _(40 min)_
**Context:** SEC-09 §5.1: monetary amount fields (target_payout etc.) must be positive decimals with explicit scale and a configurable per-partner max value. GMEPay+ uses Java BigDecimal for all money. KRW scale = 0 decimals, USD scale = 2 decimals. A zero or negative amount must be rejected with HTTP 422. An amount exceeding the partner's configured max triggers the same rejection.
**Steps:** Create MonetaryAmountValidator with method validate(BigDecimal amount, String currency, BigDecimal partnerMax) returning ValidationResult; Reject if amount <= 0 (AMOUNT_NON_POSITIVE), if scale > currency scale (AMOUNT_SCALE_EXCEEDED), if amount > partnerMax (AMOUNT_EXCEEDS_PARTNER_LIMIT); Return ValidationResult record with boolean valid, String errorCode, String detail; Write unit tests: 0.00 KRW rejected, -1.00 USD rejected, 1234.5 KRW (scale 1) rejected, 9999999.00 USD above partnerMax=1000.00 rejected, 500.00 USD with max=1000.00 accepted
**Deliverable:** MonetaryAmountValidator.java + MonetaryAmountValidatorTest.java
**Acceptance / logic checks:**
- amount=0, currency=USD -> AMOUNT_NON_POSITIVE
- amount=-0.01, currency=USD -> AMOUNT_NON_POSITIVE
- amount=1234.5, currency=KRW -> AMOUNT_SCALE_EXCEEDED (KRW allows 0 decimal places)
- amount=1500.00, currency=USD, partnerMax=1000.00 -> AMOUNT_EXCEEDS_PARTNER_LIMIT
- amount=999.99, currency=USD, partnerMax=1000.00 -> valid=true
**Depends on:** 13.4-T01

### 13.4-T03 — Implement ISO-4217 currency code whitelist validator  _(30 min)_
**Context:** SEC-09 §5.1: currency code fields (payout_currency, service_charge_currency etc.) must be validated against an ISO-4217 whitelist. Unknown or malformed codes return HTTP 422. The whitelist is loaded from InputValidationConfig (13.4-T01). Case-insensitive matching is not permitted; codes must be uppercase 3-letter strings.
**Steps:** Create CurrencyCodeValidator with method validate(String code, Set<String> whitelist) returning ValidationResult; Reject if code is null or blank (CURRENCY_CODE_MISSING), if not 3 uppercase ASCII letters (CURRENCY_CODE_FORMAT), if not in whitelist (CURRENCY_CODE_UNKNOWN); Write unit tests covering: null -> CURRENCY_CODE_MISSING, 'krw' (lowercase) -> CURRENCY_CODE_FORMAT, 'XXX' not in whitelist -> CURRENCY_CODE_UNKNOWN, 'KRW' in whitelist -> valid
**Deliverable:** CurrencyCodeValidator.java + CurrencyCodeValidatorTest.java
**Acceptance / logic checks:**
- null input -> CURRENCY_CODE_MISSING
- 'krw' -> CURRENCY_CODE_FORMAT (must be uppercase)
- 'ZZZ' not in whitelist -> CURRENCY_CODE_UNKNOWN
- 'KRW' in whitelist -> valid=true
- 'K1W' (contains digit) -> CURRENCY_CODE_FORMAT
**Depends on:** 13.4-T01

### 13.4-T04 — Implement ISO-3166-1 alpha-2 country code whitelist validator  _(25 min)_
**Context:** SEC-09 §5.1: country code fields must be validated against an ISO-3166-1 alpha-2 whitelist loaded from InputValidationConfig (13.4-T01). Must be exactly 2 uppercase ASCII letters. HTTP 422 on violation. Country codes appear in partner and merchant configuration payloads.
**Steps:** Create CountryCodeValidator with method validate(String code, Set<String> whitelist) returning ValidationResult; Reject null/blank (COUNTRY_CODE_MISSING), not exactly 2 uppercase letters (COUNTRY_CODE_FORMAT), not in whitelist (COUNTRY_CODE_UNKNOWN); Write unit tests: null -> COUNTRY_CODE_MISSING, 'kr' -> COUNTRY_CODE_FORMAT, 'ZZ' not in whitelist -> COUNTRY_CODE_UNKNOWN, 'KR' in whitelist -> valid
**Deliverable:** CountryCodeValidator.java + CountryCodeValidatorTest.java
**Acceptance / logic checks:**
- null -> COUNTRY_CODE_MISSING
- 'kr' (lowercase) -> COUNTRY_CODE_FORMAT
- 'KOR' (3 letters) -> COUNTRY_CODE_FORMAT
- 'ZZ' absent from whitelist -> COUNTRY_CODE_UNKNOWN
- 'KR' in whitelist -> valid=true
**Depends on:** 13.4-T01

### 13.4-T05 — Implement UUID v4 format and existence validator  _(35 min)_
**Context:** SEC-09 §5.1: partner_id and transaction_id fields must be UUID v4 format and must exist in the database. UUID v4 has the form xxxxxxxx-xxxx-4xxx-[89ab]xxx-xxxxxxxxxxxx. A syntactically valid UUID that does not exist in the DB returns HTTP 404; a malformed string returns HTTP 422 (UUID_FORMAT_INVALID).
**Steps:** Create UuidV4Validator with method validateFormat(String id) returning ValidationResult - checks regex ^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$; Add method validateExists(UUID id, Function<UUID,Boolean> existsFn) returning ValidationResult - calls existsFn and returns UUID_NOT_FOUND if false; Write unit tests: malformed string -> UUID_FORMAT_INVALID, UUID v1 (version bit != 4) -> UUID_FORMAT_INVALID, valid v4 UUID not in DB -> UUID_NOT_FOUND, valid v4 UUID in DB -> valid
**Deliverable:** UuidV4Validator.java + UuidV4ValidatorTest.java
**Acceptance / logic checks:**
- 'not-a-uuid' -> UUID_FORMAT_INVALID
- '550e8400-e29b-11d4-a716-446655440000' (v1, version bit=1) -> UUID_FORMAT_INVALID
- Valid v4 UUID with existsFn returning false -> UUID_NOT_FOUND
- Valid v4 UUID with existsFn returning true -> valid=true
- Null input -> UUID_FORMAT_INVALID

### 13.4-T06 — Implement webhook URL validator (HTTPS-only, allowlisted host check)  _(45 min)_
**Context:** SEC-09 §5.1 and OWASP API7 (SSRF): webhook URLs must be HTTPS and validated at partner registration time, not just at delivery time. Outbound HTTP is only permitted to allowlisted hosts. The host allowlist is a per-partner configuration. Plain HTTP URLs, private IP ranges (10.x, 172.16-31.x, 192.168.x, 127.x, ::1), and localhost are always rejected to prevent SSRF.
**Steps:** Create WebhookUrlValidator with method validate(String url, Set<String> allowlistedHosts); Reject non-HTTPS scheme (WEBHOOK_URL_NOT_HTTPS), malformed URL (WEBHOOK_URL_MALFORMED), private/loopback IP target (WEBHOOK_URL_PRIVATE_IP), host not in partner allowlist (WEBHOOK_URL_HOST_NOT_ALLOWED); Resolve hostname to IP at validation time and check against RFC-1918 ranges: 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 127.0.0.0/8, ::1; Write unit tests covering all rejection cases and one valid HTTPS public host
**Deliverable:** WebhookUrlValidator.java + WebhookUrlValidatorTest.java
**Acceptance / logic checks:**
- 'http://partner.example.com/hook' -> WEBHOOK_URL_NOT_HTTPS
- 'https://192.168.1.100/hook' -> WEBHOOK_URL_PRIVATE_IP
- 'https://10.0.0.1/hook' -> WEBHOOK_URL_PRIVATE_IP
- 'https://unknown-host.evil.com/hook' with empty allowlist -> WEBHOOK_URL_HOST_NOT_ALLOWED
- 'https://partner.example.com/hook' with 'partner.example.com' in allowlist -> valid=true
**Depends on:** 13.4-T01

### 13.4-T07 — Implement free-text field sanitiser (max length, HTML/script tag rejection)  _(30 min)_
**Context:** SEC-09 §5.1: free-text fields (merchant names, notes, partner_ref) must enforce a maximum length and reject HTML and script injection. Default max = 500 characters (configurable in InputValidationConfig). Fields containing <script, <iframe, javascript:, or any HTML tag pattern must be rejected with HTTP 422 (FREE_TEXT_INJECTION_DETECTED).
**Steps:** Create FreeTextValidator with method validate(String value, int maxLength) returning ValidationResult; Reject null is allowed (return valid for optional fields); reject if length > maxLength (FREE_TEXT_TOO_LONG); Reject if value matches HTML tag pattern [<][a-zA-Z/] or contains 'javascript:' (case-insensitive) (FREE_TEXT_INJECTION_DETECTED); Write unit tests: 501-char string -> FREE_TEXT_TOO_LONG, '<script>alert(1)</script>' -> FREE_TEXT_INJECTION_DETECTED, 'javascript:void(0)' -> FREE_TEXT_INJECTION_DETECTED, '<b>bold</b>' -> FREE_TEXT_INJECTION_DETECTED, 'Normal merchant name' -> valid, null -> valid
**Deliverable:** FreeTextValidator.java + FreeTextValidatorTest.java
**Acceptance / logic checks:**
- string of 501 chars with maxLength=500 -> FREE_TEXT_TOO_LONG
- '<script>x</script>' -> FREE_TEXT_INJECTION_DETECTED
- 'JAVASCRIPT:void(0)' (uppercase) -> FREE_TEXT_INJECTION_DETECTED
- '<img src=x>' -> FREE_TEXT_INJECTION_DETECTED
- 'Normal text 123' with maxLength=500 -> valid=true
**Depends on:** 13.4-T01

### 13.4-T08 — Create central ValidationService composing all field validators  _(50 min)_
**Context:** The individual validators (13.4-T02 through 13.4-T07) must be composed into a single ValidationService that can validate a full API request payload. The service returns a list of ValidationResult objects (one per field), and throws a ConstraintViolationException with HTTP 422 if any field fails. This replaces ad-hoc null checks scattered across endpoint handlers.
**Steps:** Create ValidationService Spring bean injecting MonetaryAmountValidator, CurrencyCodeValidator, CountryCodeValidator, UuidV4Validator, WebhookUrlValidator, FreeTextValidator; Add method validateRateRequest(RateQuoteRequest req, Partner partner) that validates: target_payout (monetary), payout_currency (ISO-4217), scheme_id (free-text <=50), direction (enum allowlist), merchant_qr (free-text <=512 optional), partner_ref (free-text <=128 optional); Add method validatePaymentRequest(PaymentRequest req, Partner partner) validating quote_id (UUID v4 format) in addition to rate fields; Throw ValidationException with list of all failing field errors (not just the first); serialize as HTTP 422 with errors array in response body
**Deliverable:** ValidationService.java with validateRateRequest and validatePaymentRequest methods
**Acceptance / logic checks:**
- RateQuoteRequest with currency='ZZZ' (unknown) AND amount=-1 returns 2 errors in the errors array, not just 1
- Valid request with KRW amount=50000, direction='inbound', scheme_id='zeropay' passes without exception
- PaymentRequest with quote_id='not-a-uuid' returns UUID_FORMAT_INVALID error
- direction value 'sideways' (not in allowlist) returns DIRECTION_INVALID error
- HTTP response body contains 'errors' array field when validation fails
**Depends on:** 13.4-T02, 13.4-T03, 13.4-T04, 13.4-T05, 13.4-T06, 13.4-T07

### 13.4-T09 — Wire ValidationService into POST /v1/rates and POST /v1/payments handlers  _(45 min)_
**Context:** The ValidationService (13.4-T08) must be called at the top of the POST /v1/rates and POST /v1/payments endpoint handlers before any rate engine or prefunding logic runs. On failure, return HTTP 422 with the errors array. No amount or rate calculation is accepted from the client - all computed values come from the rate engine server-side (SEC-09 §5.1 last paragraph).
**Steps:** In RateController.createQuote(), call validationService.validateRateRequest(request, authenticatedPartner) before any other processing; catch ValidationException and return 422 with errors; In PaymentController.createPayment(), call validationService.validatePaymentRequest(request, authenticatedPartner) before prefunding check; Ensure that if a partner sends target_payout, collection_amount, or offer_rate in the payment request body, those client-provided computed values are ignored (stripped before processing); Add integration test: POST /v1/rates with payout_currency='FAKE' returns HTTP 422 with CURRENCY_CODE_UNKNOWN in errors array
**Deliverable:** Updated RateController.java and PaymentController.java with validation wiring + integration test
**Acceptance / logic checks:**
- POST /v1/rates with payout_currency='FAKE' -> HTTP 422 body contains {errorCode:'CURRENCY_CODE_UNKNOWN'}
- POST /v1/payments with amount=0 -> HTTP 422 body contains {errorCode:'AMOUNT_NON_POSITIVE'}
- POST /v1/rates with valid request still reaches rate engine (validation does not block valid requests)
- Client-supplied collection_amount field in POST /v1/payments body is ignored; server-computed value is used
- POST /v1/payments/cpm/generate with invalid quote_id format -> HTTP 422 UUID_FORMAT_INVALID
**Depends on:** 13.4-T08

### 13.4-T10 — Wire ValidationService into POST /v1/payments/cpm/generate handler  _(35 min)_
**Context:** SEC-09 §5.1 applies to the CPM flow as well. POST /v1/payments/cpm/generate accepts quote_id (UUID v4), scheme_id, direction, and merchant_qr. Prefunding deduction for OVERSEAS partners happens atomically in this handler (deduct before calling scheme). Validation must run before the prefunding deduction step. See RATE-04 prefunding model.
**Steps:** In CpmController.generateToken(), call validationService.validatePaymentRequest() before prefunding deduction; Ensure quote_id is validated as UUID v4 format before attempting DB lookup; Return HTTP 422 with errors array on any validation failure; do NOT perform prefunding deduction if validation fails; Add integration test: POST /v1/payments/cpm/generate with non-UUID quote_id -> HTTP 422, no prefunding ledger entry created
**Deliverable:** Updated CpmController.java with validation wiring + integration test asserting no prefund deduction on invalid input
**Acceptance / logic checks:**
- quote_id='bad-id' -> HTTP 422, prefunding ledger unchanged
- Valid request with OVERSEAS partner proceeds to prefunding check
- direction='hub' in Phase 1 returns a meaningful error (DIRECTION_NOT_ACTIVE or similar) before reaching the rate engine
- Null quote_id returns UUID_FORMAT_INVALID, not NullPointerException
**Depends on:** 13.4-T08

### 13.4-T11 — Implement per-partner transaction amount limit enforcement (anti-fraud hook)  _(45 min)_
**Context:** SEC-09 §5.3 anti-fraud controls: each partner has a configurable single-transaction amount limit (stored in the partner config record). Payments exceeding this limit must be rejected before the rate engine call with HTTP 422 error code AMOUNT_EXCEEDS_PARTNER_LIMIT. The limit is denominated in the partner's settlement currency. The MonetaryAmountValidator (13.4-T02) performs the check; this ticket wires the partner's configured max into that call.
**Steps:** Add field max_transaction_amount (NUMERIC(18,8)) and max_transaction_currency (VARCHAR(3)) to the partner config table or partner_rule record; create migration V{n}__add_partner_transaction_limit.sql; Add maxTransactionAmount and maxTransactionCurrency to the Partner domain object; In ValidationService.validateRateRequest(), retrieve partner.maxTransactionAmount and pass to MonetaryAmountValidator.validate(); Add integration test: partner configured with max=1000.00 USD; request with target_payout=1500.00 -> HTTP 422 AMOUNT_EXCEEDS_PARTNER_LIMIT
**Deliverable:** DB migration V{n}__add_partner_transaction_limit.sql + updated Partner entity + updated ValidationService
**Acceptance / logic checks:**
- Partner with max=1000.00 USD: target_payout=1000.00 -> accepted
- Partner with max=1000.00 USD: target_payout=1000.01 -> HTTP 422 AMOUNT_EXCEEDS_PARTNER_LIMIT
- Partner with no max configured: any positive amount passes amount-limit check
- DB migration runs forward without error on empty and populated partner table
- partner config audit log entry created when GME Ops sets max_transaction_amount
**Depends on:** 13.4-T02, 13.4-T08

### 13.4-T12 — Implement per-partner velocity limit hook (transactions per minute)  _(50 min)_
**Context:** SEC-09 §5.3 anti-fraud: configurable max transactions per minute per partner at API Gateway. When velocity is exceeded, HTTP 429 with Retry-After header. Default: 300 rpm (all endpoints), 120 rpm (POST /v1/rates). The limit is enforced at the API Gateway plugin layer; this ticket configures and tests the gateway rate-limit plugin per partner. Each partner has a transactions_per_minute field in their config. See also SEC-09 §5.4 rate limiting table.
**Steps:** Add transactions_per_minute (INT, default 300) to partner config; add rate_quote_per_minute (INT, default 120); create DB migration V{n}__add_partner_velocity_limits.sql; Create VelocityLimitConfigExporter service that reads all partner configs and generates API Gateway rate-limit plugin config (YAML/JSON) for each partner key; Add a @Scheduled job that re-exports config when a partner velocity limit is changed (triggered by config change event); Write integration test simulating 121 POST /v1/rates requests in 1 minute from the same partner key and asserting the 121st returns HTTP 429 with Retry-After header
**Deliverable:** DB migration + VelocityLimitConfigExporter.java + integration test for 429 on velocity breach
**Acceptance / logic checks:**
- 121st request to POST /v1/rates within 60s from same partner_id -> HTTP 429
- HTTP 429 response includes Retry-After header (non-negative integer seconds)
- 301st request to any endpoint within 60s -> HTTP 429
- Partner with custom transactions_per_minute=10 rejects on 11th request
- Config exporter runs within 5 minutes of a partner velocity limit change
**Depends on:** 13.4-T11

### 13.4-T13 — Implement high-velocity and large-amount Ops alert hook  _(55 min)_
**Context:** SEC-09 §5.3: high-velocity or large-amount transactions trigger an Ops alert (configured in monitoring via OPS-13). Phase 1 implementation: when a single transaction exceeds the large-amount threshold OR when a partner exceeds the high-velocity threshold within 60 seconds, publish a FraudAlertEvent to the internal event bus. A listener forwards it to the configured alerting channel (e.g. Slack webhook or PagerDuty). Thresholds are per-partner configurable.
**Steps:** Add alert_amount_threshold (NUMERIC, nullable) and alert_velocity_threshold_per_minute (INT, nullable) to partner config; create DB migration V{n}__add_partner_alert_thresholds.sql; Create FraudAlertEvent record with fields: partnerId, alertType (LARGE_AMOUNT|HIGH_VELOCITY), transactionRef, triggeredValue, threshold, timestamp; In PaymentController, after validation and before prefunding, check if target_payout (in USD equivalent) exceeds alert_amount_threshold; if so, publish FraudAlertEvent; Create a VelocityCounter (backed by Redis or in-memory sliding window) that tracks payment initiations per partner per 60s; publish FraudAlertEvent when alert_velocity_threshold_per_minute is breached; Write unit test: 5 payments in 60s with alert threshold=3 -> FraudAlertEvent published with alertType=HIGH_VELOCITY after 4th payment
**Deliverable:** DB migration + FraudAlertEvent.java + VelocityCounter.java + FraudAlertEventPublisher wired in PaymentController
**Acceptance / logic checks:**
- Payment with target_payout_usd=50001 and alert threshold=50000 -> FraudAlertEvent(alertType=LARGE_AMOUNT) published
- 4 payments in 60s with alert_velocity_threshold=3 -> FraudAlertEvent(alertType=HIGH_VELOCITY) published after 4th
- Payment within threshold -> no FraudAlertEvent published
- Partner with null alert thresholds -> no alerts regardless of amount or velocity
- FraudAlertEvent includes transactionRef and timestamp fields
**Depends on:** 13.4-T11

### 13.4-T14 — Implement prefunding gate as anti-fraud control (OVERSEAS partners)  _(45 min)_
**Context:** SEC-09 §5.3 prefunding gate: OVERSEAS partners (type=OVERSEAS) must have their payment rejected before calling the scheme if the prefunding balance is insufficient. This is an existing business rule (RATE-04 prefunding model) but is explicitly called out as an anti-fraud control. The check must be atomic (SELECT FOR UPDATE on the prefunding_ledger row). Insufficient balance returns HTTP 422 INSUFFICIENT_PREFUNDING. LOCAL partners skip this gate entirely.
**Steps:** Confirm that PrefundingService.deduct() uses SELECT FOR UPDATE on the prefunding_ledger row for the partner_id; if not, add it; Confirm that the deduct() call occurs before any scheme adapter call in PaymentOrchestrator; Add explicit check: if partner.type == LOCAL, skip prefunding gate entirely; if OVERSEAS and balance < collection_usd, throw InsufficientPrefundingException; Write unit test: OVERSEAS partner with balance=100.00 USD, collection_usd=100.01 -> HTTP 422 INSUFFICIENT_PREFUNDING, no scheme call made; Write unit test: LOCAL partner with collection_usd=99999 -> payment proceeds regardless of prefunding ledger state
**Deliverable:** Updated PrefundingService.java confirming SELECT FOR UPDATE + unit tests for LOCAL/OVERSEAS gate logic
**Acceptance / logic checks:**
- OVERSEAS partner: balance=50.00 USD, collection_usd=50.01 -> INSUFFICIENT_PREFUNDING, scheme adapter NOT called
- OVERSEAS partner: balance=50.01 USD, collection_usd=50.01 -> deduction succeeds, scheme adapter called
- LOCAL partner: no prefunding ledger entry -> payment proceeds to scheme without prefunding check
- Concurrent deduction test: two simultaneous requests each requesting 60.00 from 100.00 balance -> exactly one succeeds, other gets INSUFFICIENT_PREFUNDING
- Deduction is rolled back if scheme call subsequently fails
**Depends on:** 13.4-T09

### 13.4-T15 — Implement idempotency key validation and cache (SEC-09 §5.2)  _(50 min)_
**Context:** SEC-09 §5.2: all payment-mutating endpoints (POST /v1/payments, POST /v1/payments/cpm/generate, POST /v1/payments/{id}/cancel) require a client-supplied Idempotency-Key (UUID v4 header). The server caches the response keyed by (partner_id, idempotency_key) for 24 hours. Duplicate requests within 24h return the original cached response without re-processing. Missing or malformed key returns HTTP 422 IDEMPOTENCY_KEY_MISSING or IDEMPOTENCY_KEY_FORMAT.
**Steps:** Create IdempotencyFilter (Spring OncePerRequestFilter) that checks for Idempotency-Key header on POST /v1/payments and /v1/payments/cpm/generate and /v1/payments/{id}/cancel; Validate UUID v4 format; return HTTP 422 IDEMPOTENCY_KEY_FORMAT if malformed; Check idempotency_cache table (or Redis) for (partner_id, idempotency_key); if hit, return cached HTTP status + body; On cache miss, allow request through and store response after completion; set cache TTL = 86400 seconds; Write unit test: identical POST /v1/payments sent twice with same Idempotency-Key -> second returns cached response, payment record count = 1
**Deliverable:** IdempotencyFilter.java + idempotency_cache table migration or Redis config + unit/integration tests
**Acceptance / logic checks:**
- POST /v1/payments without Idempotency-Key header -> HTTP 422 IDEMPOTENCY_KEY_MISSING
- POST /v1/payments with Idempotency-Key='not-a-uuid' -> HTTP 422 IDEMPOTENCY_KEY_FORMAT
- Two identical POST /v1/payments with same key -> second returns same body as first, transaction table has 1 row
- POST /v1/rates does NOT require Idempotency-Key (read-like operation)
- After 24h TTL, same key treated as new request (cache expired)
**Depends on:** 13.4-T08

### 13.4-T16 — Implement HMAC replay protection: nonce cache and timestamp window  _(40 min)_
**Context:** SEC-09 §3.3 / API-05 §3.6: requests with |server_time - X-Timestamp| > 300 seconds are rejected (HTTP 401 TIMESTAMP_DRIFT). Additionally the (partner_id, X-Signature) tuple is cached for 600 seconds; duplicate signature within 600s returns HTTP 401 REPLAY_DETECTED. X-Nonce (UUID v4) must be present; server caches seen nonces for 600s. This ticket adds the nonce cache and signature replay cache to the existing HMAC filter.
**Steps:** In HmacAuthFilter (or equivalent), after signature verification passes, check if the incoming X-Nonce is already in the nonce cache (Redis key: nonce:{partner_id}:{nonce}, TTL=600s); if present return HTTP 401 REPLAY_DETECTED; Store the X-Nonce in the cache with TTL=600s on a cache miss; Check X-Timestamp: parse ISO-8601 to epoch ms; if |now_ms - request_ms| > 300000 return HTTP 401 TIMESTAMP_DRIFT; Write unit test: valid request replayed 5 seconds later with same X-Nonce -> second returns HTTP 401 REPLAY_DETECTED; Write unit test: request with X-Timestamp 301 seconds in the past -> HTTP 401 TIMESTAMP_DRIFT
**Deliverable:** Updated HmacAuthFilter.java with nonce cache + timestamp window + unit tests
**Acceptance / logic checks:**
- Same X-Nonce used twice within 600s -> second call returns HTTP 401 REPLAY_DETECTED
- X-Timestamp 301s in past -> HTTP 401 TIMESTAMP_DRIFT
- X-Timestamp 301s in future -> HTTP 401 TIMESTAMP_DRIFT
- X-Nonce in correct UUID v4 format, first use, timestamp valid -> auth passes
- Missing X-Nonce header -> HTTP 401 NONCE_MISSING

### 13.4-T17 — Enforce partner_id ownership at query layer for all data-returning endpoints  _(45 min)_
**Context:** SEC-09 §3.5 and OWASP API1 (Broken Object Level Auth): every endpoint returning partner-scoped data (GET /v1/payments/{id}, GET /v1/balance, GET /v1/transactions) must enforce WHERE partner_id = authenticated_partner_id. The partner_id must come from the authenticated session/token, never from the request body or URL parameter. A partner querying another partner's resource ID returns HTTP 404 (not 403) to avoid confirming existence.
**Steps:** Audit TransactionRepository, PrefundingRepository, and any other repository methods called by partner-facing endpoints; add assertPartnerOwnership(UUID resourceId, UUID authenticatedPartnerId) helper; In each query method that returns a partner-scoped record, append AND partner_id = :authenticatedPartnerId to the query; If the record exists but belongs to another partner, return empty Optional (which maps to HTTP 404 in controller); Write integration test: partner A's token used to GET /v1/payments/{txn_id_belonging_to_partner_B} -> HTTP 404; Write integration test: partner A's token used to GET /v1/payments/{own_txn_id} -> HTTP 200
**Deliverable:** Updated repository query methods + PartnerOwnershipEnforcer helper + integration tests
**Acceptance / logic checks:**
- GET /v1/payments/{partner_B_txn_id} with partner_A token -> HTTP 404
- GET /v1/balance with partner_A token -> returns only partner_A balance
- GET /v1/payments/{own_txn_id} with correct partner token -> HTTP 200 with correct data
- partner_id is never accepted from request body in any partner-facing endpoint
- Admin endpoints (all-partner view) still work correctly with Admin role token

### 13.4-T18 — Suppress internal rate fields from Partner API responses (API3 control)  _(40 min)_
**Context:** SEC-09 §5.5 OWASP API3 (Broken Object Property Level Auth): the Partner API must never expose internal fields m_a (collection margin %), m_b (payout margin %), cost_rate_pay, cost_rate_coll, payout_usd_cost internals, or partner-level fee configurations in any API response. These fields are computed internally by the rate engine but must be stripped before serialisation. The contract test must assert their absence.
**Steps:** Create a @JsonIgnore or dedicated PartnerRateQuoteResponse DTO that maps from the internal RateQuoteResult, explicitly listing only the allowed output fields: quote_id, offer_rate, send_amount, service_charge, service_charge_currency, collection_usd, payout_usd_cost, collection_margin_usd, payout_margin_usd, validUntil, partner_ref; Ensure RateController returns PartnerRateQuoteResponse, not the internal RateQuoteResult; Create a contract test that deserialises the POST /v1/rates response and asserts m_a, m_b, cost_rate_pay, cost_rate_coll are absent; Repeat for POST /v1/payments response: assert internal cost fields are absent
**Deliverable:** PartnerRateQuoteResponse.java DTO + PartnerPaymentResponse.java DTO + contract tests asserting field suppression
**Acceptance / logic checks:**
- POST /v1/rates response JSON does not contain key 'm_a'
- POST /v1/rates response JSON does not contain key 'm_b'
- POST /v1/rates response JSON does not contain key 'cost_rate_pay' or 'cost_rate_coll'
- POST /v1/payments response does not contain 'collection_margin_pct' or any margin percentage
- Valid rate response still contains offer_rate, send_amount, service_charge, validUntil
**Depends on:** 13.4-T09

### 13.4-T19 — Implement UUID v4 identifiers for all partner, transaction, and rule entities  _(40 min)_
**Context:** SEC-09 §4 (Threat T-09): all partner, transaction, and rule identifiers must use UUID v4 to prevent enumeration attacks. Sequential or predictable IDs allow an attacker to iterate through resources. This ticket audits and enforces that all entity primary keys are UUID v4, generated server-side, and that no auto-increment integer IDs are exposed in any API response.
**Steps:** Audit partner, transaction, transaction_event, rule, and refund tables: verify PK type is UUID (PostgreSQL uuid column type) with DEFAULT gen_random_uuid(); If any table still uses BIGSERIAL or SERIAL PK, create migration V{n}__convert_{table}_pk_to_uuid.sql; Audit all API response DTOs: ensure no integer ID fields are included; replace any integer id fields with UUID string fields; Write unit test: create 100 transactions via service and assert all IDs match UUID v4 regex ^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$
**Deliverable:** Audit report (inline in PR description) + any required migration files + unit test asserting UUID v4 generation
**Acceptance / logic checks:**
- partner.id column type is UUID with gen_random_uuid() default
- transaction.id column type is UUID with gen_random_uuid() default
- rule.id column type is UUID with gen_random_uuid() default
- 100 generated transaction IDs all match UUID v4 regex
- POST /v1/payments response 'id' field matches UUID v4 regex

### 13.4-T20 — Implement ZeroPay inbound batch file schema validation (API10 control)  _(50 min)_
**Context:** SEC-09 §5.5 OWASP API10 (Unsafe Consumption of APIs): ZeroPay SFTP response files (ZP0012 payment result, ZP0022 refund result, ZP0043 merchant sync) must be validated and parsed defensively. Each file has a defined fixed-width or CSV schema (per SCH-06). Unexpected field counts, invalid amounts, or unknown record types must be rejected and logged rather than passed to the settlement engine. A malformed batch file must never cause silent data corruption.
**Steps:** Create ZeroPayBatchSchemaValidator with method validate(BatchFileType type, List<String> lines) returning BatchValidationResult; For ZP0012: validate each record has expected column count (per SCH-06 layout), amount fields are numeric, transaction_ref matches UUID format, status code is in known set (AP/NG); On schema violation, record the offending line number and field name in BatchValidationResult.errors; do NOT process the file further if error count > 0; Log validation failure as WARN with file name, line count, and error summary; publish BatchValidationFailedEvent for Ops alerting; Write unit test: ZP0012 file with one record where amount field contains 'INVALID' -> BatchValidationResult.valid=false, errors contains line number and field name
**Deliverable:** ZeroPayBatchSchemaValidator.java + BatchValidationResult.java + unit tests for ZP0012 malformed amount and ZP0043 wrong column count
**Acceptance / logic checks:**
- ZP0012 record with amount='INVALID' -> BatchValidationResult.valid=false, error identifies line 1 field 'amount'
- ZP0043 record with wrong column count -> validation fails with WRONG_COLUMN_COUNT error
- ZP0012 with all valid records -> valid=true, errors=[]
- Validation failure causes BatchValidationFailedEvent to be published
- Settlement engine is NOT called when BatchValidationResult.valid=false

### 13.4-T21 — Configure OWASP Dependency-Check in Maven/Gradle CI pipeline  _(45 min)_
**Context:** SEC-09 §5.6: the CI pipeline must include dependency scanning for known CVEs in all third-party libraries using OWASP Dependency-Check (or Snyk). Critical/High CVEs must block merge. The tool must run on every pull request. This ticket configures the Maven/Gradle OWASP Dependency-Check plugin and the CI pipeline step.
**Steps:** Add org.owasp:dependency-check-maven plugin (version >=9.x) to pom.xml (or equivalent Gradle plugin) with failBuildOnCVSS=7 (blocks on High/Critical: CVSS >= 7.0); Configure suppressions file owasp-suppressions.xml for known false positives (empty initially); add to .gitignore exclusions for the NVD cache dir; Add CI step 'dependency-scan' in .github/workflows/ci.yml (or equivalent) that runs mvn dependency-check:check; must run after compile, before merge gate; Configure NVD API key as CI secret OWASP_NVD_API_KEY to avoid rate limiting; Verify: introduce a test dependency with a known critical CVE, confirm CI step fails; then remove it and confirm CI passes
**Deliverable:** Updated pom.xml with OWASP plugin config + .github/workflows/ci.yml dependency-scan step + owasp-suppressions.xml
**Acceptance / logic checks:**
- CI pipeline includes a 'dependency-scan' step that runs on every PR
- Adding a dependency with CVSS >= 7.0 causes the dependency-scan step to fail and blocks merge
- CI step passes on a clean dependency tree
- owasp-suppressions.xml exists and is tracked in git (even if empty)
- failBuildOnCVSS is set to 7 (not higher than 7)

### 13.4-T22 — Configure Snyk or GitHub Advanced Security for dependency and container scanning  _(40 min)_
**Context:** SEC-09 §5.6: in addition to OWASP Dependency-Check, the pipeline must scan container images for CVEs (e.g. Trivy, Grype). Critical/High findings in the container image must also block merge or deployment. This ticket adds Trivy container scanning to the CI pipeline for the main application Docker image.
**Steps:** Add a 'container-scan' CI job in .github/workflows/ci.yml that runs after the Docker build step; Use aquasecurity/trivy-action@master to scan the built image; set exit-code=1 and severity=CRITICAL,HIGH to fail on critical/high; Output Trivy scan results as SARIF and upload to GitHub Security tab (uses actions/upload-sarif); Add .trivyignore file for accepted false positives (empty initially); Verify: build with an image known to have a critical CVE (e.g. old ubuntu base) -> CI fails; switch to current base -> CI passes
**Deliverable:** Updated .github/workflows/ci.yml with container-scan job + .trivyignore file
**Acceptance / logic checks:**
- Container-scan job runs after Docker build on every PR
- Image with a known Critical CVE causes container-scan to fail with exit code 1
- Image with no Critical/High CVEs -> container-scan passes
- SARIF output is uploaded to GitHub Security tab (visible in Security > Code scanning alerts)
- Trivy scan covers OS packages AND application dependencies within the image
**Depends on:** 13.4-T21

### 13.4-T23 — Configure secret scanning (TruffleHog or GitHub Advanced Security) in CI  _(35 min)_
**Context:** SEC-09 §5.6: secret scanning must be configured to scan all commits for accidentally committed credentials. Any finding must block merge and trigger immediate rotation procedure. This ticket configures TruffleHog (or GitHub Advanced Security secret scanning) as a required CI check on all pull requests and enables push protection on the repository.
**Steps:** Enable GitHub Advanced Security secret scanning on the repository (Settings > Security > Secret scanning); enable Push protection to block secrets at push time; Add CI step 'secret-scan' in .github/workflows/ci.yml using trufflesecurity/trufflehog-actions-scan@main; configure --only-verified to reduce false positives; Set fail-on-found=true so any verified secret finding blocks merge; Document the rotation procedure in a short comment in the workflow file: 'Any finding: immediately rotate the credential, then suppress the finding with a trufflehog inline suppression comment after rotation is confirmed'; Test: temporarily commit a dummy AWS key pattern, verify CI blocks; revert and verify CI passes
**Deliverable:** Updated .github/workflows/ci.yml with secret-scan step + GitHub push protection enabled
**Acceptance / logic checks:**
- CI pipeline includes a 'secret-scan' step running on every PR
- Committing a pattern matching a known secret type (e.g. AWS access key format) causes secret-scan to fail
- Push protection is enabled: a direct push containing a secret pattern is blocked at the git push step
- TruffleHog runs with --only-verified flag
- PR cannot be merged while secret-scan check is failing

### 13.4-T24 — Configure SAST (Semgrep or SonarQube) in CI pipeline  _(35 min)_
**Context:** SEC-09 §5.6 and §5.7: static analysis (SAST) must run on every pull request. Semgrep with the java and owasp rulesets or SonarQube with the Java security profile is acceptable. Critical/High findings must block merge. This ticket integrates Semgrep into the CI pipeline. SonarQube is an acceptable alternative if already licensed.
**Steps:** Add CI step 'sast' in .github/workflows/ci.yml using returntocorp/semgrep-action@v1; configure rules: p/java, p/owasp-top-ten, p/security-audit; Set audit-on=pr and fail-open=false (blocking mode) so critical findings block merge; Configure SEMGREP_APP_TOKEN as a CI secret if using Semgrep App for dashboard visibility; Add .semgrepignore for test files if needed (to reduce noise from test code); Verify: introduce a SQL injection pattern in a test branch -> sast step flags it; remove -> passes
**Deliverable:** Updated .github/workflows/ci.yml with sast step + .semgrepignore file
**Acceptance / logic checks:**
- SAST step runs on every pull request
- A pattern matching a known Semgrep rule (e.g. java.lang.security.audit.formatted-sql-string) causes sast step to fail
- Clean code passes sast step
- Semgrep rules include p/owasp-top-ten
- sast step result is required for merge (configured as required status check in branch protection)

### 13.4-T25 — Enforce branch protection rules and required CI checks  _(25 min)_
**Context:** SEC-09 §5.7 secure SDLC: main and production branches must require passing CI and at least one approving review before merge; force-push must be blocked. This ticket configures the branch protection rules and ensures all security CI jobs (dependency-scan, container-scan, secret-scan, sast) are listed as required status checks.
**Steps:** In GitHub repository Settings > Branches, add branch protection rule for 'main' and 'production' (or 'release/*'); Enable: Require pull request reviews before merging (minimum 1 approval), Require status checks to pass before merging, Include: dependency-scan, container-scan, secret-scan, sast, build, test; Enable: Restrict who can push to matching branches (only GitHub Actions bot and named admins); Enable: Block force pushes; Document the rule configuration in the PR as a screenshot or JSON export via GitHub API
**Deliverable:** Branch protection rules configured on main/production branches with all 4 security CI jobs as required checks
**Acceptance / logic checks:**
- Attempting to merge a PR with failing dependency-scan -> blocked
- Attempting to merge a PR with 0 approvals -> blocked
- Force push to main -> rejected with 'protected branch' error
- PR with all checks green and 1 approval -> can be merged
- Branch protection settings are visible in GitHub Settings > Branches
**Depends on:** 13.4-T21, 13.4-T22, 13.4-T23, 13.4-T24

### 13.4-T26 — Unit tests for CurrencyCodeValidator and CountryCodeValidator edge cases  _(25 min)_
**Context:** Test ticket for 13.4-T03 and 13.4-T04. Covers boundary and edge cases not fully specified in the implementation tickets, including empty string, whitespace-only, mixed-case, numeric, and special characters. These validators are called on every API request; edge case failures could cause false 422s or bypass injection.
**Steps:** Write CurrencyCodeValidatorEdgeCaseTest covering: empty string, single space, 'US' (2 chars), 'USDA' (4 chars), 'U$D' (special char), 'USD' valid, 'KRW' valid; Write CountryCodeValidatorEdgeCaseTest covering: empty string, 'K' (1 char), 'KRW' (3 chars), 'K1' (digit), ' KR' (leading space), 'KR' valid; Assert each case returns the expected errorCode or valid=true; Confirm whitespace is trimmed before validation (so ' KRW ' is treated as 'KRW')
**Deliverable:** CurrencyCodeValidatorEdgeCaseTest.java + CountryCodeValidatorEdgeCaseTest.java
**Acceptance / logic checks:**
- Empty string -> CURRENCY_CODE_MISSING
- 'US' (2 chars) -> CURRENCY_CODE_FORMAT
- 'USDA' (4 chars) -> CURRENCY_CODE_FORMAT
- 'U$D' -> CURRENCY_CODE_FORMAT
- ' KR ' with trim -> valid if KR in whitelist
- 'K1' -> COUNTRY_CODE_FORMAT
**Depends on:** 13.4-T03, 13.4-T04

### 13.4-T27 — Unit tests for WebhookUrlValidator SSRF edge cases  _(30 min)_
**Context:** Test ticket for 13.4-T06 (WebhookUrlValidator). SSRF prevention depends on exhaustive IP range checks. This ticket adds test vectors for edge cases in the private/loopback IP detection logic, including decimal IP notation, IPv6, URL-encoded characters, and DNS rebinding-style inputs.
**Steps:** Add test cases: 'https://0x7f.0.0.1/hook' (hex IP loopback) -> WEBHOOK_URL_PRIVATE_IP; 'https://[::1]/hook' (IPv6 loopback) -> WEBHOOK_URL_PRIVATE_IP; 'https://[fc00::1]/hook' (IPv6 ULA) -> WEBHOOK_URL_PRIVATE_IP; 'https://169.254.169.254/hook' (AWS metadata) -> WEBHOOK_URL_PRIVATE_IP; 'https://partner.example.com%2Fevil.com/hook' (URL-encoded slash) -> WEBHOOK_URL_MALFORMED or WEBHOOK_URL_HOST_NOT_ALLOWED; 'https://partner.example.com/hook' with resolving to 192.168.1.1 at runtime -> WEBHOOK_URL_PRIVATE_IP
**Deliverable:** WebhookUrlValidatorSsrfTest.java with at least 8 SSRF edge case vectors
**Acceptance / logic checks:**
- 0x7f.0.0.1 -> WEBHOOK_URL_PRIVATE_IP
- [::1] -> WEBHOOK_URL_PRIVATE_IP
- 169.254.169.254 -> WEBHOOK_URL_PRIVATE_IP
- URL with %2F encoded slash in host -> WEBHOOK_URL_MALFORMED or WEBHOOK_URL_HOST_NOT_ALLOWED
- Valid public HTTPS host in allowlist -> valid=true
- IPv6 ULA fc00::/7 -> WEBHOOK_URL_PRIVATE_IP
**Depends on:** 13.4-T06

### 13.4-T28 — Unit tests for monetary amount validator currency scale and pool identity  _(25 min)_
**Context:** Test ticket for 13.4-T02 (MonetaryAmountValidator). Covers currency scale enforcement (KRW=0, USD=2, JPY=0) and verifies that invalid scale inputs are caught before reaching the rate engine. Also verifies that an amount of exactly 0 KRW and exactly the partner max are handled at the boundary.
**Steps:** Write MonetaryAmountValidatorScaleTest with cases: 50000 KRW (scale 0) -> valid, 50000.5 KRW (scale 1) -> AMOUNT_SCALE_EXCEEDED, 99.99 USD (scale 2) -> valid, 99.999 USD (scale 3) -> AMOUNT_SCALE_EXCEEDED; Add boundary cases: amount=0.00 -> AMOUNT_NON_POSITIVE, amount=partnerMax exactly -> valid, amount=partnerMax+0.01 -> AMOUNT_EXCEEDS_PARTNER_LIMIT; Add case: BigDecimal('1E+3') (scientific notation for 1000, scale -3) -> accepted as valid after normalization for KRW; Confirm BigDecimal.stripTrailingZeros() is used before scale check to avoid false scale violations
**Deliverable:** MonetaryAmountValidatorScaleTest.java with at least 8 test vectors
**Acceptance / logic checks:**
- 50000.5 KRW -> AMOUNT_SCALE_EXCEEDED
- 99.999 USD -> AMOUNT_SCALE_EXCEEDED
- 50000 KRW -> valid
- 0.00 USD -> AMOUNT_NON_POSITIVE
- amount=partnerMax -> valid (boundary inclusive)
- amount=partnerMax+0.01 -> AMOUNT_EXCEEDS_PARTNER_LIMIT
**Depends on:** 13.4-T02

### 13.4-T29 — Integration test: full OWASP API1 IDOR scenario for /v1/payments  _(35 min)_
**Context:** Test ticket for 13.4-T17. An end-to-end integration test that verifies partner data isolation. Partner A creates a payment; Partner B's token is used to attempt to read Partner A's transaction. The test confirms HTTP 404 (not 403) to avoid confirming existence. Also confirms Partner A can read their own transaction.
**Steps:** In integration test suite, create two partner fixtures: partnerA (OVERSEAS) and partnerB (OVERSEAS) with separate credentials; Create a payment transaction for partnerA with a known txn_id; Use partnerB's HMAC-signed request to call GET /v1/payments/{partnerA_txn_id}; Assert HTTP 404 response; Use partnerA's HMAC-signed request to call GET /v1/payments/{partnerA_txn_id}; assert HTTP 200 with correct data
**Deliverable:** IdroCrossPartnerIntegrationTest.java testing IDOR on GET /v1/payments/{id} and GET /v1/balance
**Acceptance / logic checks:**
- partnerB token + partnerA txn_id -> HTTP 404
- partnerA token + partnerA txn_id -> HTTP 200
- partnerB token + GET /v1/balance -> returns only partnerB balance, not partnerA balance
- HTTP 404 response body does NOT contain partnerA's transaction data
- Response code is 404 not 403 (does not confirm existence to attacker)
**Depends on:** 13.4-T17

### 13.4-T30 — Integration test: HMAC replay attack and timestamp drift rejection  _(35 min)_
**Context:** Test ticket for 13.4-T16. End-to-end integration tests verifying that the HMAC replay protection and timestamp window actually block replayed requests. Uses a real Spring Boot test context with the full filter chain active.
**Steps:** Write HmacReplayIntegrationTest in the integration test suite; Test 1: capture a valid signed request; wait 0 seconds; replay the exact same request with identical X-Nonce and X-Signature -> HTTP 401 REPLAY_DETECTED; Test 2: send a request with X-Timestamp set to (now - 301 seconds) -> HTTP 401 TIMESTAMP_DRIFT; Test 3: send a request with X-Timestamp set to (now + 301 seconds) -> HTTP 401 TIMESTAMP_DRIFT; Test 4: valid request with fresh X-Nonce and current X-Timestamp -> HTTP 200 (or expected response)
**Deliverable:** HmacReplayIntegrationTest.java with at least 4 test scenarios
**Acceptance / logic checks:**
- Replayed request with same X-Nonce within 600s -> HTTP 401 REPLAY_DETECTED
- X-Timestamp 301s in past -> HTTP 401 TIMESTAMP_DRIFT
- X-Timestamp 301s in future -> HTTP 401 TIMESTAMP_DRIFT
- Fresh X-Nonce within timestamp window -> request succeeds
- X-Timestamp exactly 300s in past -> request succeeds (boundary is exclusive at 300s)
**Depends on:** 13.4-T16

### 13.4-T31 — Integration test: anti-fraud velocity alert triggered at correct threshold  _(40 min)_
**Context:** Test ticket for 13.4-T13. Verifies that the FraudAlertEvent is published at exactly the configured velocity threshold, not before and not one late. Also verifies that a large-amount single transaction triggers the LARGE_AMOUNT alert. Uses a test event listener to capture published events without needing a real alerting channel.
**Steps:** Configure partnerA with alert_velocity_threshold_per_minute=3 and alert_amount_threshold=10000.00 USD in test fixture; Submit 3 valid payments within 30 seconds; assert no FraudAlertEvent published after 3rd; Submit 4th payment within 60 seconds; assert FraudAlertEvent(alertType=HIGH_VELOCITY, partnerId=partnerA) published; Reset counter; submit 1 payment with target_payout equivalent to 10001 USD; assert FraudAlertEvent(alertType=LARGE_AMOUNT) published; Submit 1 payment at exactly 10000.00 USD; assert no LARGE_AMOUNT alert
**Deliverable:** AntiFraudAlertIntegrationTest.java with velocity and large-amount alert test scenarios
**Acceptance / logic checks:**
- 3rd payment within threshold -> no alert
- 4th payment exceeding velocity threshold -> FraudAlertEvent(alertType=HIGH_VELOCITY)
- Payment of 10001 USD equivalent -> FraudAlertEvent(alertType=LARGE_AMOUNT)
- Payment of exactly 10000 USD -> no alert (threshold is exclusive)
- FraudAlertEvent contains correct partnerId and transactionRef
**Depends on:** 13.4-T13

### 13.4-T32 — Integration test: prefunding gate blocks scheme call on insufficient balance  _(40 min)_
**Context:** Test ticket for 13.4-T14. Verifies the prefunding gate behaviour end-to-end: an OVERSEAS partner with insufficient balance is rejected before the scheme is called. Uses a mock ZeroPay scheme adapter to confirm the scheme was never called. Also verifies concurrent requests are handled correctly.
**Steps:** Set partnerA (OVERSEAS) balance to 100.00 USD in test fixture; Submit payment with collection_usd=100.01; assert HTTP 422 INSUFFICIENT_PREFUNDING; assert mock scheme adapter call count = 0; Submit payment with collection_usd=100.00; assert payment proceeds; assert mock scheme adapter called once; Reset balance to 100.00; submit two concurrent requests each with collection_usd=60.00; assert exactly one succeeds and one returns INSUFFICIENT_PREFUNDING; Submit payment for partnerB (LOCAL) with no prefunding ledger entry; assert payment proceeds normally
**Deliverable:** PrefundingGateIntegrationTest.java with 4 test scenarios
**Acceptance / logic checks:**
- OVERSEAS partner: collection_usd=100.01 with balance=100.00 -> HTTP 422 INSUFFICIENT_PREFUNDING
- Scheme adapter mock not called when prefunding gate rejects
- OVERSEAS partner: collection_usd=100.00 with balance=100.00 -> payment proceeds
- Two concurrent requests sum > balance -> exactly one succeeds
- LOCAL partner -> payment proceeds regardless of prefunding ledger state
**Depends on:** 13.4-T14

### 13.4-T33 — Write developer security onboarding doc for OWASP API Top 10 controls  _(35 min)_
**Context:** SEC-09 §5.7 requires all developers to understand OWASP Top 10 controls. This ticket writes a concise SECURITY_CONTROLS.md (max 2 pages) in the repo documenting: which OWASP API Top 10 risks are mitigated, the key code entry points (class names, filter chain order), how to run the security CI checks locally, and the mandatory rotation procedure for any accidentally committed secret. This replaces verbal onboarding for new developers.
**Steps:** Create docs/SECURITY_CONTROLS.md in the repository; Section 1: OWASP API Top 10 mitigation table (risk, control, key class) matching SEC-09 §5.5; Section 2: Security filter chain order (IP allowlist -> HMAC auth -> rate limit -> nonce replay -> input validation -> RBAC); Section 3: How to run security checks locally: mvn dependency-check:check, semgrep --config p/owasp-top-ten ., docker run trivy image <image>; Section 4: Secret rotation procedure: detect (CI fails) -> revoke credential immediately -> rotate in secrets vault -> update CI secret -> suppress finding after rotation confirmed
**Deliverable:** docs/SECURITY_CONTROLS.md (max 500 lines)
**Acceptance / logic checks:**
- File exists at docs/SECURITY_CONTROLS.md in repo root
- Contains all 10 OWASP API risks from SEC-09 §5.5 with their GMEPay+ control
- Section 3 contains exact CLI commands to run dependency-check, semgrep, and Trivy locally
- Section 4 describes the secret rotation procedure in numbered steps
- Document is under 500 lines
**Depends on:** 13.4-T21, 13.4-T22, 13.4-T23, 13.4-T24


## WBS 13.5 — Audit/logging & SIEM export
### 13.5-T01 — Create audit_log DB migration with append-only constraints  _(40 min)_
**Context:** audit_log is the immutable record of all config-entity changes. Schema (DAT-03 §9.1): id BIGINT PK, actor_id VARCHAR(120), actor_type VARCHAR(20) CHECK IN (OPERATOR,SYSTEM,PARTNER), action VARCHAR(20) CHECK IN (CREATE,UPDATE,DELETE,ACTIVATE,DEACTIVATE), entity_type VARCHAR(50), entity_id BIGINT, before_value JSONB NULL, after_value JSONB NULL, occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), ip_address INET, request_id VARCHAR(64). No UPDATE or DELETE allowed; retained >= 7 years. SEC-09 §6.3 requires append-only enforcement at the DB layer. Also add two columns not in DAT-03 but required by SEC-09 §6.1: actor_role VARCHAR(50), changed_field VARCHAR(120).
**Steps:** Write a Flyway/Liquibase migration V13_5_001 creating table audit_log with all columns above.; Add a RULE or trigger on PostgreSQL: CREATE RULE no_update_audit AS ON UPDATE TO audit_log DO INSTEAD NOTHING; and CREATE RULE no_delete_audit AS ON DELETE TO audit_log DO INSTEAD NOTHING;; Add index on (entity_type, entity_id, occurred_at DESC) for entity history lookups.; Add index on (actor_id, occurred_at DESC) for operator activity queries.; Add index on occurred_at for retention purge scans.
**Deliverable:** Flyway migration file V13_5_001__create_audit_log.sql
**Acceptance / logic checks:**
- Migration applies cleanly on a fresh schema and on the existing integration DB; rollback script V13_5_001 is provided.
- INSERT into audit_log succeeds; UPDATE on an existing row returns 0 rows affected (rule swallows it silently); DELETE returns 0 rows affected.
- before_value IS NULL for action=CREATE rows; after_value IS NULL for action=DELETE rows — enforced by a CHECK constraint or documented in migration comments.
- Index (entity_type, entity_id, occurred_at DESC) is present and used by EXPLAIN ANALYZE on a query filtering entity_type=rule AND entity_id=42.
- actor_type CHECK constraint rejects value OTHER with a constraint violation error.

### 13.5-T02 — Add prev_hash column and SHA-256 hash-chain trigger to audit_log  _(50 min)_
**Context:** SEC-09 §6.3: each audit_log record includes a prev_hash of the previous record forming a SHA-256 hash chain. A break in the chain indicates tampering and triggers a P1 alert. The hash of record N is SHA256(id || actor_id || actor_type || action || entity_type || entity_id || before_value || after_value || occurred_at || ip_address || request_id || prev_hash_of_N-1). The very first record uses prev_hash = SHA256('GENESIS'). This hash is computed and stored at INSERT time via a DB trigger (not in application code) so no application path can bypass it.
**Steps:** Write migration V13_5_002 adding column prev_hash VARCHAR(64) NOT NULL DEFAULT '' to audit_log.; Implement PostgreSQL trigger function audit_log_hash_chain() using pgcrypto encode(digest(concat_ws('|', ...), 'sha256'), 'hex'); set NEW.prev_hash = hash of the previous row (SELECT prev_hash || id from audit_log ORDER BY id DESC LIMIT 1).; The trigger fires BEFORE INSERT FOR EACH ROW.; Write a migration comment documenting the exact concatenation order used for hashing.; Verify GENESIS seed: when audit_log is empty, prev_hash = encode(digest('GENESIS','sha256'),'hex').
**Deliverable:** Migration V13_5_002__audit_log_hash_chain.sql plus trigger function audit_log_hash_chain()
**Acceptance / logic checks:**
- Insert three rows; SHA256 of row 1 (using GENESIS seed) matches the stored prev_hash of row 2; SHA256 of row 2 matches prev_hash of row 3.
- Manual UPDATE of any column on row 1 is blocked by the no_update_audit rule (from T01); hash chain still reports a break when checked by the integrity job (T05).
- pgcrypto extension is declared as a dependency in the migration; migration fails gracefully if pgcrypto is absent with an error message.
- The concatenation order is deterministic: changing field order in the concat produces a different hash (verified by unit test in T06).
- prev_hash on the first ever inserted row equals encode(digest('GENESIS','sha256'),'hex') = '6a67...'; confirm with SELECT.
**Depends on:** 13.5-T01

### 13.5-T03 — Implement AuditLogEntry domain class and AuditLogRepository  _(40 min)_
**Context:** Application-layer model matching the audit_log table. Fields: id (Long), actorId (String), actorRole (String), actorType (AuditActorType enum: OPERATOR/SYSTEM/PARTNER), action (AuditAction enum: CREATE/UPDATE/DELETE/ACTIVATE/DEACTIVATE), entityType (String), entityId (Long), changedField (String nullable), beforeValue (JsonNode nullable), afterValue (JsonNode nullable), occurredAt (OffsetDateTime), ipAddress (String), requestId (String). Repository must expose: save(AuditLogEntry) -> AuditLogEntry; findByEntity(entityType, entityId, Pageable); findByActor(actorId, Pageable); findByOccurredAtBetween(from, to, Pageable). No update or delete methods.
**Steps:** Create AuditLogEntry @Entity/@Table(name=audit_log) with all fields; use @Column(insertable=true, updatable=false) on every column.; Create AuditAction and AuditActorType enums.; Create AuditLogRepository extends JpaRepository<AuditLogEntry,Long> with the four query methods above.; Annotate class with @Immutable (Hibernate) to prevent accidental flush of dirty state.; Add integration test verifying save and findByEntity work against the real DB schema (T01 migration applied).
**Deliverable:** AuditLogEntry.java, AuditAction.java, AuditActorType.java, AuditLogRepository.java
**Acceptance / logic checks:**
- AuditLogEntry.save() persists a row; a subsequent findById returns matching values.
- Calling entityManager.merge() on a loaded AuditLogEntry throws HibernateException or does nothing (updatable=false columns not sent in UPDATE).
- findByEntity(rule, 42, page) returns only rows with entity_type=rule AND entity_id=42 ordered by occurred_at DESC.
- AuditAction enum has exactly 5 values: CREATE UPDATE DELETE ACTIVATE DEACTIVATE; AuditActorType has exactly 3: OPERATOR SYSTEM PARTNER.
- No delete method exists on the repository; attempting to call auditLogRepo.delete(...) fails at compile time (method not defined).
**Depends on:** 13.5-T01

### 13.5-T04 — Implement AuditService.record() for config-entity change events  _(45 min)_
**Context:** SEC-09 §6.1 requires every change to partner, scheme, or rule to log: actor_id, actor_role, event_type (e.g. RULE_MARGIN_UPDATED), entity_type/entity_id, changed_field, previous_value, new_value, timestamp (server UTC, not client), ip_address. AuditService is the single entry point for all audit writes. It must be called from within the same DB transaction as the config change (so a failed config write never produces an orphan audit row and vice versa). The service derives occurredAt = OffsetDateTime.now(ZoneOffset.UTC) internally; callers never supply a timestamp.
**Steps:** Create AuditService with method record(AuditContext ctx) where AuditContext carries actorId, actorRole, actorType, action, entityType, entityId, changedField, beforeValue, afterValue, requestId, ipAddress.; Implement record() to build AuditLogEntry (server-generated occurredAt) and call auditLogRepository.save() -- must run inside caller's @Transactional.; Add a convenience method recordAll(List<AuditContext>) for multi-field updates in one config change.; Reject null actorId, null entityType, null entityId with IllegalArgumentException before DB call.; Log a WARN if beforeValue is null on an UPDATE action (data quality safeguard, not an exception).
**Deliverable:** AuditService.java with record() and recordAll() methods
**Acceptance / logic checks:**
- record() called inside an outer @Transactional; if the outer txn rolls back, the audit row is also absent from DB.
- record() called outside a transaction: Spring throws TransactionRequiredException (annotate method @Transactional(propagation=MANDATORY)).
- Null actorId throws IllegalArgumentException before any DB interaction.
- Two calls to recordAll() in the same transaction for rule margin change produce exactly 2 rows (one per changed field) with matching request_id.
- occurredAt on the saved row is server-generated UTC; passing a timestamp in AuditContext is not possible (no such field on AuditContext).
**Depends on:** 13.5-T03

### 13.5-T05 — Implement daily hash-chain integrity check job  _(55 min)_
**Context:** SEC-09 §6.3 and alert T-06 in SEC-09 §10.4: Any audit log hash chain break is a P1 alert (Audit log hash chain break). The daily integrity check job reads all audit_log rows in id order, recomputes SHA-256(concat fields) for each row, and verifies it matches the stored prev_hash of the next row. On any mismatch, it must write a structured ERROR log event (event=AUDIT_CHAIN_BREAK, first_broken_id=N) and emit an application metric audit_chain_break_count (Prometheus counter). The job runs daily at 02:00 UTC (configurable). SEC-09 §6.3 Assumption: result is written to an admin-only monitoring endpoint; no email/pager is sent by this job directly -- the Prometheus alert rule picks up the counter.
**Steps:** Create AuditIntegrityCheckJob as a @Scheduled component (cron configurable via audit.integrity.cron, default 0 0 2 * * *).; Stream audit_log rows in batches of 1000 ordered by id ASC; recompute SHA-256 for each row using the same field concatenation as the DB trigger (T02).; For each row N, verify recomputed hash == prev_hash stored on row N+1; on mismatch increment Prometheus counter audit_chain_break_count with label first_broken_id.; Write structured log: level=ERROR, event=AUDIT_CHAIN_BREAK, first_broken_id, total_breaks.; Write summary structured log on success: level=INFO, event=AUDIT_CHAIN_OK, rows_checked.
**Deliverable:** AuditIntegrityCheckJob.java with Prometheus counter audit_chain_break_count
**Acceptance / logic checks:**
- Job completes on 100 valid rows with 0 breaks, emitting event=AUDIT_CHAIN_OK and rows_checked=100.
- Simulate a break by directly updating prev_hash on row 50 via a test-only DB helper; job detects the break at id=51 and increments audit_chain_break_count{first_broken_id=51} by 1.
- Job is idempotent: running twice on unchanged data reports 0 breaks both times.
- audit.integrity.cron=0 * * * * * (every minute) in test config triggers execution within 60 seconds.
- Job uses batched streaming (fetchSize=1000) and does not load all rows into memory at once -- verified by watching heap usage with 100k rows.
**Depends on:** 13.5-T02, 13.5-T03

### 13.5-T06 — Unit tests for hash-chain computation correctness  _(35 min)_
**Context:** The SHA-256 hash chain (T02) must produce deterministic, order-sensitive hashes. The hash input is concat_ws('|', id, actor_id, actor_type, action, entity_type, entity_id, before_value, after_value, occurred_at, ip_address, request_id, prev_hash_of_prev_row). GENESIS seed = SHA256('GENESIS'). These tests run in Java (not DB) to verify the Java re-implementation in T05 matches the DB trigger exactly. If they differ, the integrity check job will always report breaks on valid data.
**Steps:** Create AuditHashChainTest with 5 test vectors constructed manually.; Test 1: GENESIS row -- compute expected = SHA256('GENESIS'); verify HashChainUtil.genesisHash() returns matching hex.; Test 2: Row 1 with known fields; compute prev_hash manually; verify HashChainUtil.computeHash(row1, genesisHash) matches.; Test 3: Row 2 uses row1's hash as input; verify chain continuity.; Test 4: Changing a single field (e.g. actor_id) produces a different hash (sensitivity test).; Test 5: NULL fields (before_value=null for CREATE action) are represented as empty string in concat and produce stable hash.
**Deliverable:** AuditHashChainTest.java (5 unit test methods)
**Acceptance / logic checks:**
- All 5 tests pass with mvn test -pl hub-core -Dtest=AuditHashChainTest.
- HashChainUtil.genesisHash() returns '6a672...'; exact value documented in test as a constant.
- Modifying actor_id on row 1 from 'user-1' to 'user-2' produces a different hash for row 1 and a cascade difference for row 2.
- NULL before_value treated as empty string '' in concat; two rows differing only in before_value=NULL vs before_value='{}' produce different hashes.
- HashChainUtil is a stateless utility class with no Spring dependencies (runs in plain JUnit without Spring context).
**Depends on:** 13.5-T02, 13.5-T05

### 13.5-T07 — Wire AuditService into partner config change endpoints  _(55 min)_
**Context:** SEC-09 §6.1 and §3.4: All operator changes to partner, scheme, and rule records must be audit-logged with actor, previous value, new value, changed_field. In SAD-02 §5.1, config changes propagate from Admin Portal -> Hub Core Config Service. The config service already has partner activation, rule margin update, rate source change, scheme activation endpoints. Each must call AuditService.record() or recordAll() in the same transaction. actor_id and ip_address come from the authenticated session (Spring Security context). Event type naming convention: {ENTITY_TYPE}_{FIELD}_UPDATED e.g. RULE_MARGIN_UPDATED, PARTNER_ACTIVATED, SCHEME_DEACTIVATED.
**Steps:** In PartnerConfigService.activatePartner(), add AuditService.record() call with action=ACTIVATE, entityType=partner, before_value=snapshot before change.; In RuleConfigService.updateMargin(), call auditService.recordAll() for each changed field (m_a, m_b may change together); changedField=m_a or m_b; before_value=old decimal; after_value=new decimal.; In SchemeConfigService.activateScheme() and deactivateScheme(), add audit calls with action=ACTIVATE/DEACTIVATE.; In TreasuryRateConfigService.updateRate(), add audit call with changedField=rate_value, before/after as JSONB.; Ensure all calls use @Transactional(propagation=MANDATORY) so they fail loudly if called outside a transaction.
**Deliverable:** Updated PartnerConfigService, RuleConfigService, SchemeConfigService, TreasuryRateConfigService each calling AuditService
**Acceptance / logic checks:**
- POST /admin/v1/rules/{id}/margin with body {m_a:0.015, m_b:0.01} produces 2 audit_log rows: one for m_a (before=previous value, after=0.015) and one for m_b (after=0.01).
- Audit rows have actor_id matching the authenticated operator's user UUID, not a hardcoded string.
- If the DB transaction rolls back (e.g. constraint violation on the rule table), audit rows are also absent.
- PARTNER_ACTIVATED event is logged when an inactive partner is activated; no audit row is created for activating an already-active partner (idempotent guard).
- ip_address on the audit row matches the X-Forwarded-For or remote address from the HTTP request context.
**Depends on:** 13.5-T04

### 13.5-T08 — Wire AuditService into credential issuance and rotation events  _(40 min)_
**Context:** SEC-09 §9.1/§9.2: Partner API key issuance, rotation (scheduled and emergency), and revocation are privileged Admin actions that must be audit-logged. Event types: CREDENTIAL_ISSUED, CREDENTIAL_ROTATED, CREDENTIAL_REVOKED. The audit row must NOT store the plaintext secret or its hash in before_value/after_value -- these fields must contain only the key_id and rotation metadata (overlap_window_days, revoked_at). actor_type=OPERATOR for manual actions, SYSTEM for automatic overlap-window expiry.
**Steps:** In CredentialService.issueCredential(), add audit call: action=CREATE, entityType=partner_credential, after_value={key_id, issued_at}.; In CredentialService.rotateCredential(), add audit call: action=UPDATE, changedField=api_key, before_value={old_key_id}, after_value={new_key_id, overlap_ends_at}.; In CredentialService.revokeCredential() (manual), add audit with action=DEACTIVATE, actor_type=OPERATOR.; In CredentialExpiryJob (automatic overlap expiry), add audit with action=DEACTIVATE, actor_type=SYSTEM, actor_id=credential-expiry-job.; Assert that before_value and after_value never contain the string 'secret' or 'hmac' as a JSON key.
**Deliverable:** Updated CredentialService.java and CredentialExpiryJob.java with audit calls
**Acceptance / logic checks:**
- Issuing credentials for partner UUID abc123 produces 1 audit row with entityType=partner_credential, action=CREATE, after_value containing key_id but NOT secret.
- Rotating credentials produces 1 audit row with changedField=api_key; before_value.old_key_id and after_value.new_key_id are different strings.
- Automated revocation (overlap expiry) produces actor_type=SYSTEM, actor_id=credential-expiry-job.
- A regex scan on after_value JSONB text for keys 'secret','hmac','password' returns 0 results across 100 sample audit rows.
- CREDENTIAL_REVOKED event is searchable in audit log by entity_type=partner_credential AND action=DEACTIVATE.
**Depends on:** 13.5-T04

### 13.5-T09 — Implement transaction_event table and TransactionEventService  _(45 min)_
**Context:** SEC-09 §6.1 defines an 8-step event trail per payment transaction. Each step: transaction_id, event (rate_quote_issued | payment_initiated | prefund_deducted | scheme_request_sent | scheme_response_received | transaction_committed | webhook_dispatched | webhook_delivered), timestamp UTC, duration_ms (from previous step), and a state snapshot JSONB. This is separate from audit_log -- it covers payment lifecycle, not config changes. Table transaction_event already exists in DAT-03 §5.3 but the service layer must be built. The service is append-only; no updates or deletes.
**Steps:** Confirm transaction_event table exists (DAT-03 §5.3); if not, write migration V13_5_003.; Create TransactionEvent @Entity with fields: id BIGINT, transaction_id BIGINT FK, event_name VARCHAR(50), occurred_at TIMESTAMPTZ, duration_ms INT, state_snapshot JSONB.; Create TransactionEventRepository (no delete/update methods).; Create TransactionEventService.record(transactionId, eventName, prevStepTimestamp, snapshot) computing duration_ms = now - prevStepTimestamp; save row.; Add convenience method getTrail(transactionId) returning List<TransactionEvent> ordered by occurred_at ASC.
**Deliverable:** TransactionEventService.java, TransactionEventRepository.java, and migration V13_5_003 (if needed)
**Acceptance / logic checks:**
- Recording 8 sequential events for transaction UUID t1 produces 8 rows; getTrail(t1) returns them in step order.
- duration_ms on step 2 (payment_initiated) = time between step 1 recorded_at and step 2 recorded_at in milliseconds (verified with known timestamps).
- state_snapshot for step 6 (transaction_committed) must contain keys collection_usd and payout_usd_cost; a CHECK constraint or application validation rejects step-6 snapshots missing these fields.
- Calling record() with an unknown event name e.g. foo_event throws IllegalArgumentException (enum or whitelist validation).
- No update or delete methods are present on TransactionEventRepository.
**Depends on:** 13.5-T01

### 13.5-T10 — Wire 8-step transaction event trail into Transaction Orchestrator  _(55 min)_
**Context:** The Transaction Orchestrator (SAD-02 §5.2) drives the payment state machine. At each of the 8 checkpoints it must call TransactionEventService.record(). Steps: 1=rate_quote_issued (GET /v1/rates responds), 2=payment_initiated (POST /v1/payments called), 3=prefund_deducted (OVERSEAS only, after SELECT FOR UPDATE succeeds), 4=scheme_request_sent (request dispatched to ZeroPay), 5=scheme_response_received (ZeroPay returns approval/rejection), 6=transaction_committed (rate lock written to DB), 7=webhook_dispatched (webhook POST sent), 8=webhook_delivered (partner returns HTTP 2xx). Step 6 snapshot must include: collection_usd, payout_usd_cost, collection_margin_usd, payout_margin_usd, send_amount, offer_rate_coll, cross_rate.
**Steps:** In RateService.issueQuote(), call transactionEventService.record(txnId, RATE_QUOTE_ISSUED, null, rateSnapshot) after persisting quote.; In PaymentOrchestrator.initiatePayment(), record PAYMENT_INITIATED with request metadata snapshot.; In PrefundingService.deduct(), record PREFUND_DEDUCTED only on success; skip for LOCAL partners.; In SchemeAdapter.send() and receive(), record SCHEME_REQUEST_SENT and SCHEME_RESPONSE_RECEIVED.; In CommitTransaction, record TRANSACTION_COMMITTED with the 7-field rate-lock snapshot; record WEBHOOK_DISPATCHED and WEBHOOK_DELIVERED in WebhookDispatcher.
**Deliverable:** Updated PaymentOrchestrator, PrefundingService, SchemeAdapter, WebhookDispatcher with TransactionEventService calls
**Acceptance / logic checks:**
- A complete MPM payment (OVERSEAS) produces exactly 8 event rows in transaction_event; LOCAL partner payment produces 7 rows (no PREFUND_DEDUCTED).
- Step 6 state_snapshot contains all 7 keys: collection_usd, payout_usd_cost, collection_margin_usd, payout_margin_usd, send_amount, offer_rate_coll, cross_rate.
- duration_ms on SCHEME_RESPONSE_RECEIVED >= 0 and represents actual elapsed time from SCHEME_REQUEST_SENT (verified in integration test with a 100ms stub delay).
- A rejected payment (ZeroPay returns rejection at step 5) still records steps 1-5 with SCHEME_RESPONSE_RECEIVED; steps 6-8 are absent.
- WEBHOOK_DELIVERED is only recorded when partner returns HTTP 2xx; HTTP 5xx response from partner records WEBHOOK_DISPATCHED but not WEBHOOK_DELIVERED.
**Depends on:** 13.5-T09

### 13.5-T11 — Implement api_request_log table and API gateway logging filter  _(50 min)_
**Context:** SEC-09 §6.4: every inbound API request must be logged with timestamp, partner_id (or user_id), method, path, query_string (sensitive params redacted), response_code, response_time_ms, ip_address, request_id. The api_request_log table (DAT-03 §9.3) has: id BIGINT PK, request_id VARCHAR(64) UNIQUE, partner_id BIGINT FK, method VARCHAR(10), path VARCHAR(255), status_code INT, request_body_hash VARCHAR(64) (SHA-256 of body, never plaintext), response_time_ms INT, occurred_at TIMESTAMPTZ, ip_address INET. Sensitive fields API key, HMAC secret, password must NEVER appear in this log. The filter runs as a Spring OncePerRequestFilter on all /v1/* endpoints.
**Steps:** Confirm migration for api_request_log exists (DAT-03 §9.3); write V13_5_004 if not.; Implement ApiRequestLogFilter extends OncePerRequestFilter; capture start time, extract partner_id from authenticated principal, compute SHA-256 of request body.; On response, build ApiRequestLog entity with all fields; save via ApiRequestLogRepository.saveAsync() (non-blocking -- use @Async to not add latency to payment path).; Redact sensitive query params: strip any param whose name matches api_key, secret, password, token, hmac (case-insensitive) before storing path+query.; Add integration test: POST /v1/rates with valid credentials; verify log row exists with correct partner_id, method=POST, path=/v1/rates, status_code=200, ip_address set.
**Deliverable:** ApiRequestLogFilter.java, ApiRequestLogRepository.java, migration V13_5_004
**Acceptance / logic checks:**
- After a POST /v1/payments call, api_request_log contains a row with request_id matching the X-Request-ID response header.
- request_body_hash is a 64-char hex string (SHA-256); the plaintext body is absent from the log table.
- A request with query param ?api_key=secret123 is stored with query_string containing api_key=[REDACTED].
- Log write is async: the HTTP response is returned before the DB insert completes (verify with a 500ms simulated DB delay in test -- response latency < 100ms).
- Sensitive fields check: SELECT * FROM api_request_log WHERE path LIKE '%/v1/%' returns 0 rows containing the string 'hmac' or 'secret' in any text column.
**Depends on:** 13.5-T01

### 13.5-T12 — Implement authentication event logging for Admin and Partner portals  _(45 min)_
**Context:** SEC-09 §6.2: Authentication events (login, logout, MFA) must be retained 3 years. Loggable events: ADMIN_LOGIN_SUCCESS, ADMIN_LOGIN_FAILURE, ADMIN_LOGOUT, ADMIN_MFA_SUCCESS, ADMIN_MFA_FAILURE, PARTNER_LOGIN_SUCCESS, PARTNER_LOGIN_FAILURE, PARTNER_LOGOUT, PARTNER_MFA_SUCCESS, PARTNER_MFA_FAILURE. Each event must capture: user_id, event_type, occurred_at (UTC), ip_address, user_agent, session_id (for login/logout). These events feed the SIEM (T17) and the security dashboard (OPS-13 §7.4). Stored in the existing audit_log table with actor_type=OPERATOR or PARTNER, entity_type=auth_session, action=CREATE (login) or DELETE (logout).
**Steps:** Create AuthEventService with logAuthEvent(userId, actorType, eventType, ipAddress, userAgent, sessionId) method.; Call AuditService.record() with entityType=auth_session, changedField=event_type, after_value={event_type, session_id, user_agent}.; Wire AuthEventService into Spring Security success/failure handlers: AuthenticationSuccessHandler, AuthenticationFailureHandler, LogoutSuccessHandler.; Wire MFA events: on TOTP verification success/failure, call AuthEventService.; Do not log password or TOTP code in any field; after_value must contain only session metadata.
**Deliverable:** AuthEventService.java wired into Spring Security auth handlers
**Acceptance / logic checks:**
- A successful Admin login produces an audit_log row with actor_type=OPERATOR, entityType=auth_session, action=CREATE, after_value containing event_type=ADMIN_LOGIN_SUCCESS.
- A failed partner login (wrong password) produces actor_type=PARTNER, after_value.event_type=PARTNER_LOGIN_FAILURE; no password field anywhere in the row.
- 5 consecutive failed logins for the same user_id produce 5 PARTNER_LOGIN_FAILURE audit rows, all with the same ip_address.
- Logout event produces action=DELETE, after_value.event_type=ADMIN_LOGOUT or PARTNER_LOGOUT with matching session_id.
- MFA failure produces ADMIN_MFA_FAILURE or PARTNER_MFA_FAILURE; the TOTP code is absent from all columns.
**Depends on:** 13.5-T04

### 13.5-T13 — Implement log retention enforcement -- 7-year archive and purge job  _(50 min)_
**Context:** SEC-09 §6.2 retention schedule: transaction_event and audit_log (config changes) = 7 years; api_request_log = 1 year; auth events in audit_log = 3 years; SFTP batch file logs = 7 years. A scheduled purge job must delete rows (or partition-drop) older than the retention window. However, the audit_log table is append-only with no-delete rules (T01); the purge job must use a privileged DB role that bypasses the RULE, OR the 7-year rule means NO purge for audit_log within that window -- only rows older than 7 years are eligible. For api_request_log the job deletes rows where occurred_at < NOW() - INTERVAL 1 YEAR. All deletions are preceded by an archival export to object storage.
**Steps:** Create LogRetentionJob as @Scheduled (cron 0 30 3 * * *, i.e. 03:30 UTC daily, configurable).; For api_request_log: DELETE WHERE occurred_at < NOW() - INTERVAL 1 YEAR; log count of deleted rows at INFO.; For audit_log auth events (actor_type IN (OPERATOR,PARTNER) AND entity_type=auth_session): DELETE WHERE occurred_at < NOW() - INTERVAL 3 YEARS using privileged JDBC connection (bypass no-delete rule).; For audit_log config changes and transaction_event: assert 0 rows older than 7 years exist; alert (WARN) if any found but do not delete (compliance hold required).; Write a structured log: event=LOG_RETENTION_RUN, api_request_deleted=N, auth_events_deleted=M, config_audit_older_7y=K.
**Deliverable:** LogRetentionJob.java with configurable retention windows
**Acceptance / logic checks:**
- api_request_log rows with occurred_at = NOW() - 400 DAYS are deleted; rows at NOW() - 300 DAYS remain.
- Auth-event audit rows (entity_type=auth_session) older than 3 years are deleted; config-change rows at the same age are NOT deleted.
- Job emits event=LOG_RETENTION_RUN with correct counts; a no-op run (nothing to delete) emits counts of 0.
- Retention windows configurable via properties: audit.retention.api-request-days=365, audit.retention.auth-events-days=1095, audit.retention.config-audit-days=2555.
- If config_audit_older_7y > 0, a WARN is emitted with count; no delete occurs (human review required).
**Depends on:** 13.5-T01, 13.5-T03

### 13.5-T14 — Implement SIEM export -- structured JSON log forwarder  _(55 min)_
**Context:** SEC-09 §6.5: All security-relevant log streams must be forwarded to the SIEM via structured JSON over a secure transport. Streams to forward: (1) authentication events, (2) audit_log entries (config changes), (3) API gateway alerts (rate limit, replay detected, timestamp drift), (4) anomaly detections. The SIEM product and integration pattern are defined in OPS-13 but the transport is a secure outbound connection. Implementation: use a Logback appender (or Logstash TCP/TLS appender) that forwards JSON events to a configurable siem.host:siem.port over TLS. The appender must be configured via application.yml (siem.enabled, siem.host, siem.port, siem.tls.enabled). All events must include the standard structured log fields (OPS-13 §7.2): timestamp, level, service, env, trace_id, partner_id, event.
**Steps:** Add logstash-logback-encoder dependency; configure SiemTlsAppender in logback-spring.xml pointing to ${siem.host}:${siem.port}.; Create SiemEventLogger bean: emits structured JSON log at level=INFO with event, actor_id, entity_type, entity_id for every audit record written by AuditService (via ApplicationEvent).; Publish AuditLogWrittenEvent from AuditService after each save; SiemEventLogger listens and emits to the SIEM appender.; For auth failure events, emit with level=WARN; for hash chain breaks (T05), emit with level=ERROR and event=AUDIT_CHAIN_BREAK.; Add siem.enabled=false default; when false, forwarder bean is a no-op (no connection attempted). Integration test sets siem.enabled=true with a mock TLS server.
**Deliverable:** SiemEventLogger.java, logback-spring.xml SIEM appender config, application.yml siem.* properties
**Acceptance / logic checks:**
- With siem.enabled=true and a mock TLS server running, an audit_log INSERT triggers a JSON message received by the mock server within 500ms.
- The forwarded JSON contains fields: timestamp (ISO-8601), level, service=hub-core, event, actor_id, entity_type, entity_id, env.
- Sensitive fields (secret, hmac, password, before_value raw) are NOT present in the forwarded SIEM event; only metadata fields are forwarded.
- With siem.enabled=false, AuditService.record() completes without attempting any network connection (verified by mock server receiving 0 messages).
- TLS is used for the SIEM connection when siem.tls.enabled=true; plaintext TCP is rejected at config validation startup (fail-fast).
**Depends on:** 13.5-T04, 13.5-T12

### 13.5-T15 — Implement AuditLogQueryService for Admin portal search and export  _(55 min)_
**Context:** PRD-07 §2.1: Admin portal includes Audit Log module -- immutable event log, search and export. NFR (PRD-07 §14.1): audit log export for 30-day windows must complete within 30 seconds; larger windows are async. The query service exposes: search(filter) -> Page<AuditLogEntry> with filters: actorId, entityType, entityId, actionType, dateFrom, dateTo. Export: exportCsv(filter) -> byte[] for <= 30 days; for > 30 days return AsyncExportHandle and process in background. CSV columns: id, occurred_at, actor_id, actor_role, action, entity_type, entity_id, changed_field, before_value, after_value, ip_address. Audit log is Admin-only (RBAC: only Admin role can view; Ops/Finance cannot -- SEC-09 §3.4.3).
**Steps:** Create AuditLogQueryService.search(AuditLogFilter filter, Pageable page) using Specifications or JPQL with dynamic predicates.; Create AuditLogQueryService.exportCsv(AuditLogFilter filter) -- if filter spans <= 30 days, execute synchronously; if > 30 days, submit to @Async executor and return job_id.; Implement CsvExportHelper that streams rows to OutputStream to avoid OOM on large exports.; Create GET /admin/v1/audit-logs endpoint (Admin role only) calling search(); return 403 for Ops/Finance roles.; Create GET /admin/v1/audit-logs/export endpoint returning CSV inline for <= 30 days or {job_id} for async.
**Deliverable:** AuditLogQueryService.java, AuditLogController.java (GET /admin/v1/audit-logs and /export)
**Acceptance / logic checks:**
- GET /admin/v1/audit-logs?entity_type=rule&date_from=2026-01-01&date_to=2026-01-31 returns paginated results within 2 seconds on a dataset with 500k rows (index on entity_type + occurred_at).
- GET /admin/v1/audit-logs/export?date_from=2026-01-01&date_to=2026-01-31 (30-day window) returns Content-Type: text/csv and CSV data within 30 seconds.
- GET /admin/v1/audit-logs/export?date_from=2025-01-01&date_to=2026-01-31 (> 30 days) returns HTTP 202 with {job_id} and does not block.
- An Ops-role JWT calling GET /admin/v1/audit-logs receives HTTP 403 (only Admin role is permitted per SEC-09 §3.4.3).
- CSV export never includes column 'password', 'secret', or 'hmac' regardless of before_value/after_value content.
**Depends on:** 13.5-T03, 13.5-T07

### 13.5-T16 — Implement async CSV export job and download endpoint  _(50 min)_
**Context:** Continuation of T15 for large audit log exports (> 30 days). The async export job writes a CSV file to a temporary secure location (local encrypted volume or S3 with SSE), tracks status in a job table (job_id UUID, status PENDING/RUNNING/COMPLETE/FAILED, created_at, completed_at, file_path, requester_user_id). Download link is valid for 1 hour. The file must be deleted after download or after 24 hours, whichever comes first. NFR: export for 365-day window must complete within 5 minutes (background).
**Steps:** Create audit_export_job table: job_id UUID PK, status VARCHAR(20), created_at TIMESTAMPTZ, completed_at TIMESTAMPTZ, file_path VARCHAR(500), requester_user_id VARCHAR(120), expires_at TIMESTAMPTZ.; Create AuditExportJob @Async bean that streams audit rows to a temp file (encrypted at rest); updates job status on completion.; Create GET /admin/v1/audit-logs/export/{job_id} endpoint: if status=COMPLETE and current time < expires_at, stream file as attachment; else 404 or 425 (too early).; On first download or on expires_at, delete the temp file and set file_path=NULL in job table.; Add a cleanup job to delete temp files for jobs older than 24 hours.
**Deliverable:** AuditExportJob.java, audit_export_job migration V13_5_005, updated AuditLogController with export download endpoint
**Acceptance / logic checks:**
- POST /admin/v1/audit-logs/export for 365-day window returns 202 with job_id; polling shows status=COMPLETE within 5 minutes.
- Download endpoint for completed job streams valid CSV; Content-Disposition header contains filename=audit_export_{job_id}.csv.
- Download endpoint called after 1 hour (expires_at passed) returns 404.
- Second download attempt after first download returns 404 (file deleted on first download).
- Cleanup job deletes temp files for jobs where created_at < NOW() - INTERVAL 24 HOURS.
**Depends on:** 13.5-T15

### 13.5-T17 — Implement Prometheus metrics and alert rules for audit pipeline  _(45 min)_
**Context:** SEC-09 §10.4 defines alert thresholds: Audit log hash chain break -> P1; any REPLAY_DETECTED event -> P2; failed auth attempts > 20 per IP per 10 min -> P3. OPS-13 §7.4 Security dashboard must show: auth failure rate, abnormal IP, rate-limit triggers. Required Prometheus metrics: audit_chain_break_count (counter, from T05), auth_failure_count (counter, labels: portal=admin|partner, ip_address), audit_log_insert_total (counter), siem_forward_failure_total (counter), api_replay_detected_total (counter). Alert rules: CRITICAL if audit_chain_break_count > 0 in last 24h; WARNING if auth_failure_count by ip > 20 in 10min.
**Steps:** Register Micrometer counters in a SecurityMetrics bean: audit_chain_break_count, auth_failure_count{portal,ip_address}, audit_log_insert_total, siem_forward_failure_total, api_replay_detected_total.; Increment audit_log_insert_total in AuditService.record() after successful save.; Increment auth_failure_count in AuthEventService on ADMIN_LOGIN_FAILURE or PARTNER_LOGIN_FAILURE events.; Increment siem_forward_failure_total in SiemEventLogger on TLS connection failure or timeout.; Write Prometheus alert rules file alerts/audit.yml with two rules: audit_chain_break_alert (severity=critical) and auth_failure_rate_alert (severity=warning, threshold > 20 per IP per 10m).
**Deliverable:** SecurityMetrics.java, alerts/audit.yml Prometheus alert rules
**Acceptance / logic checks:**
- GET /actuator/prometheus returns audit_chain_break_count, auth_failure_count, audit_log_insert_total, siem_forward_failure_total, api_replay_detected_total metrics after startup.
- After one failed login, auth_failure_count{portal=admin} increments by 1 (verified via /actuator/prometheus scrape).
- alerts/audit.yml is valid Prometheus YAML (passes promtool check-rules alerts/audit.yml with 0 errors).
- audit_chain_break_alert has severity=critical and expr referencing audit_chain_break_count > 0.
- siem_forward_failure_total increments when SiemEventLogger fails to connect to SIEM (mock server down in test).
**Depends on:** 13.5-T05, 13.5-T12, 13.5-T14

### 13.5-T18 — Integration test -- audit trail completeness for rule margin update  _(55 min)_
**Context:** End-to-end test covering the full audit pipeline for a config change. A rule margin update (m_a changes from 0.015 to 0.020, m_b stays 0.010) by an Ops user must: (1) produce 1 audit_log row per changed field, (2) update the hash chain (prev_hash on new row links to prior row), (3) forward a SIEM event (to mock server), (4) appear in GET /admin/v1/audit-logs search results. This test requires the DB (T01/T02), AuditService (T04), SIEM forwarder (T14), and query endpoint (T15) all wired together.
**Steps:** Start test with Docker Compose (PostgreSQL + mock TLS SIEM server); apply all migrations T01-T05.; Authenticate as Ops user; call PATCH /admin/v1/rules/{rule_id}/margin with {m_a: 0.020}.; Assert audit_log has 1 new row: entity_type=rule, changed_field=m_a, before_value={m_a:0.015}, after_value={m_a:0.020}, actor_role=Ops.; Assert hash chain is intact: AuditIntegrityCheckJob.runNow() returns 0 breaks.; Assert mock SIEM server received exactly 1 JSON event with event=RULE_M_A_UPDATED (or RULE_MARGIN_UPDATED) within 1 second.; Assert GET /admin/v1/audit-logs?entity_type=rule&entity_id={rule_id} returns the new row.
**Deliverable:** AuditTrailRuleUpdateIT.java (Spring Boot integration test)
**Acceptance / logic checks:**
- Test passes with mvn verify -Dtest=AuditTrailRuleUpdateIT in a CI environment with Docker.
- Exactly 1 audit_log row is created (not 2, not 0); changed_field=m_a.
- prev_hash on the new row equals SHA256 of the row preceding it in the chain.
- Mock SIEM server received JSON with partner_id or entity_id matching the rule; no secret or hmac field present.
- GET /admin/v1/audit-logs search returns the new row; an Ops-role token receives HTTP 403 for the same endpoint.
**Depends on:** 13.5-T07, 13.5-T14, 13.5-T15

### 13.5-T19 — Integration test -- 8-step transaction event trail for MPM payment  _(55 min)_
**Context:** End-to-end test verifying the complete 8-step transaction event trail (T10) for a Fixed MPM payment by an OVERSEAS partner. The test must verify all 8 events are recorded with correct step order, duration_ms >= 0, and that step 6 (transaction_committed) state_snapshot contains the rate-lock fields. Uses the test merchant QR (ZPQR_TEST_001) and a ZeroPay stub returning approval.
**Steps:** Configure test with OVERSEAS partner (SendMN), prefunding balance $10,000 USD, rule m_a=0.015 m_b=0.010, cost_rate_coll=1300.0 (USD/KRW), cost_rate_pay=1.0.; Submit POST /v1/payments MPM with target_payout=10000 KRW; ZeroPay stub returns approval.; Query GET /admin/v1/transactions/{txn_ref}/events (or direct DB query); assert 8 rows in order.; Assert step 3 (prefund_deducted): state_snapshot contains deducted_usd > 0.; Assert step 6 (transaction_committed): state_snapshot.collection_usd - state_snapshot.collection_margin_usd - state_snapshot.payout_margin_usd == state_snapshot.payout_usd_cost (pool identity, tolerance 0.01 USD).
**Deliverable:** TransactionEventTrailIT.java (Spring Boot integration test)
**Acceptance / logic checks:**
- Exactly 8 events recorded; event_name sequence is: rate_quote_issued, payment_initiated, prefund_deducted, scheme_request_sent, scheme_response_received, transaction_committed, webhook_dispatched, webhook_delivered.
- duration_ms on each step >= 0; duration_ms on step 1 is null (no previous step).
- step 6 state_snapshot pool identity holds: collection_usd(7.6923) - collection_margin_usd(0.1154) - payout_margin_usd(0.0769) == payout_usd_cost(7.5000) within 0.01 USD (example values for 10000 KRW at 1300 KRW/USD with m_a=1.5%, m_b=1.0%).
- For a LOCAL partner payment (no prefunding), exactly 7 events recorded -- PREFUND_DEDUCTED absent.
- A rejected payment records events 1-5; events 6-8 are absent.
**Depends on:** 13.5-T10, 13.5-T09

### 13.5-T20 — Integration test -- hash chain tamper detection  _(45 min)_
**Context:** Verifies that the AuditIntegrityCheckJob (T05) detects tampering. Test procedure: insert 20 valid audit rows, directly UPDATE the before_value of row 10 using a privileged test-only DB helper (bypassing the no-update rule), run the integrity check job, and assert it reports a break at row 11 (the first row whose prev_hash no longer matches the recomputed hash of row 10). This test proves the tamper-evidence mechanism actually works. Also test the monitoring output: audit_chain_break_count Prometheus counter must increment.
**Steps:** Insert 20 audit rows via AuditService; verify initial check reports 0 breaks.; Use a test JDBC template with privileged role to UPDATE audit_log SET before_value='{"tampered":true}' WHERE id={row_10_id}.; Call AuditIntegrityCheckJob.runNow() synchronously.; Assert job returns first_broken_id = row_11_id and total_breaks = 1.; Assert audit_chain_break_count Prometheus counter incremented by 1.; Assert structured log contains level=ERROR, event=AUDIT_CHAIN_BREAK, first_broken_id=row_11_id.
**Deliverable:** AuditTamperDetectionIT.java (Spring Boot integration test)
**Acceptance / logic checks:**
- Initial 20 valid rows: runNow() returns total_breaks=0.
- After tampering row 10: runNow() returns first_broken_id matching row_11_id and total_breaks=1.
- audit_chain_break_count counter = 1 after tamper test.
- Structured log captured by test appender contains event=AUDIT_CHAIN_BREAK and first_broken_id.
- Running the job again immediately (rows unchanged) still reports 1 break (idempotent reporting).
**Depends on:** 13.5-T05, 13.5-T17

### 13.5-T21 — Unit tests for AuditService edge cases  _(35 min)_
**Context:** Logic-bearing edge cases for AuditService (T04): null actorId rejection, multi-field recordAll() atomicity, and the invariant that no audit row is created when the enclosing transaction rolls back. Also tests that the RULE_MARGIN_UPDATED event names match the SEC-09 §6.1 naming convention. Test vectors are self-contained and do not require a running DB (use H2 or Mockito).
**Steps:** Test 1: record() with null actorId -> IllegalArgumentException thrown before any DB call.; Test 2: record() with null entityType -> IllegalArgumentException.; Test 3: record() with action=UPDATE and null beforeValue -> no exception; WARN log emitted.; Test 4: recordAll() with empty list -> no DB call, no exception, returns immediately.; Test 5: recordAll() with 3 AuditContext entries -> exactly 3 save() calls on the mocked repository.; Test 6: event_type naming check -- AuditContext with entityType=rule, changedField=m_a, action=UPDATE -> after_value JSON key 'm_a' present.
**Deliverable:** AuditServiceTest.java (6 unit test methods, Mockito)
**Acceptance / logic checks:**
- All 6 tests pass with mvn test -Dtest=AuditServiceTest.
- Test 1 and Test 2 verify no interaction with auditLogRepository mock (verify(repo, never()).save(...)).
- Test 3 verifies WARN log is emitted (captured via test log appender) but no exception.
- Test 5 verifies exactly 3 invocations of auditLogRepository.save() via Mockito.verify(repo, times(3)).
- Test 6 verifies after_value JsonNode contains key 'm_a' with the expected new value.
**Depends on:** 13.5-T04

### 13.5-T22 — Unit tests for LogRetentionJob boundary conditions  _(35 min)_
**Context:** Tests for LogRetentionJob (T13) verifying that the correct rows are retained and deleted based on retention windows. Uses in-memory H2 or mocked repository. Retention windows under test: api_request_log=365 days, auth events=1095 days, config audit=2555 days (7 years). Edge cases: row at exactly 365 days (boundary, should delete), row at 364 days (retain), row with no occurred_at (should not fail job).
**Steps:** Test 1: api_request_log row with occurred_at = NOW() - 366 DAYS -> job deletes it; row at NOW() - 364 DAYS -> retained.; Test 2: auth event audit row with occurred_at = NOW() - 1096 DAYS -> deleted; row at 1094 DAYS -> retained.; Test 3: config change audit row with occurred_at = NOW() - 2556 DAYS -> NOT deleted; WARN emitted with count=1.; Test 4: empty tables -> job runs without error; all counts = 0 in structured log.; Test 5: two api_request_log rows to delete -> structured log shows api_request_deleted=2.
**Deliverable:** LogRetentionJobTest.java (5 unit test methods)
**Acceptance / logic checks:**
- All 5 tests pass with mvn test -Dtest=LogRetentionJobTest.
- Test 3 verifies zero delete calls on audit_log and exactly one WARN log emission with count=1.
- Test 1 boundary: row at exactly 365 days is deleted (strictly less than condition is exclusive, so occurred_at < NOW() - 365d uses strict less-than -- confirm boundary behavior matches spec).
- Test 4 confirms structured log event=LOG_RETENTION_RUN with api_request_deleted=0, auth_events_deleted=0.
- Tests use a fixed clock (Clock.fixed) to make date arithmetic deterministic.
**Depends on:** 13.5-T13

### 13.5-T23 — Document audit pipeline runbook -- operations reference  _(30 min)_
**Context:** OPS-13 and SEC-09 require operational documentation for: (1) how to verify audit log integrity manually, (2) how to respond to a P1 hash chain break alert, (3) how to export audit logs for a forensics request, (4) retention policy summary. This is an inline code comment + a small runbook properties file (not a Markdown doc) embedded in the ops config directory as audit-runbook.properties for the Admin portal context-sensitive help. The Admin portal (PRD-07) links to this from the Audit Log module.
**Steps:** Create src/main/resources/ops/audit-runbook.properties with keys: integrity_check.manual_command, hash_break.response_steps, forensic_export.admin_endpoint, retention.summary.; integrity_check.manual_command: SQL query to verify chain for a date range (SELECT id, prev_hash, digest(concat_ws('|',...)) from audit_log ORDER BY id).; hash_break.response_steps: 5-step procedure matching SEC-09 §10.2 (preserve logs -> isolate -> triage -> eradication -> RCA).; forensic_export.admin_endpoint: GET /admin/v1/audit-logs/export with param descriptions.; retention.summary: table of log categories and retention periods from SEC-09 §6.2.
**Deliverable:** src/main/resources/ops/audit-runbook.properties
**Acceptance / logic checks:**
- File parses as valid Java .properties format (Properties p = new Properties(); p.load(stream) succeeds with no exception).
- integrity_check.manual_command value contains the string 'audit_log' and 'prev_hash'.
- hash_break.response_steps value references 'preserve' and '5' or contains 5 numbered steps.
- retention.summary value contains '7 years' and '1 year' and '3 years' (matching SEC-09 §6.2 retention schedule).
- File is present at the expected classpath path and loadable via ClassPathResource in a unit test.
**Depends on:** 13.5-T05, 13.5-T15, 13.5-T13


## WBS 13.6 — Data privacy (PIPA) & retention
### 13.6-T01 — Create PII inventory table and entity mapping document  _(35 min)_
**Context:** GMEPay+ SEC-09 §7.1 defines exactly which PII the platform holds: partner_admin.email, partner_admin.mfa_seed (TOTP), merchant.name and merchant.id (synced from ZeroPay), transaction amounts (KRW + partner ccy), applied FX rates, partner API key hash, and operator session tokens. End-user name, national ID, bank account, phone, and address are NEVER held. This ticket produces a living reference document (as a DB-backed config table) that maps each PII field to its legal basis, storage table, retention period, and residency constraint — used by all downstream privacy tickets.
**Steps:** Create DB migration: table pii_inventory (id SERIAL PK, data_element VARCHAR(120) NOT NULL, storage_table VARCHAR(80), legal_basis VARCHAR(80), retention_years SMALLINT, residency_required BOOLEAN, notes TEXT, created_at TIMESTAMPTZ DEFAULT now()).; Insert the 8 canonical rows from SEC-09 §7.1: partner_admin_email, partner_admin_mfa_seed, merchant_id, merchant_name, transaction_amount_krw, transaction_amount_partner_ccy, applied_fx_rate, partner_api_key_hash; set residency_required=true for all, legal_basis=contractual_necessity or regulatory_compliance as applicable.; Add a NOT NULL constraint on data_element and a UNIQUE constraint on (data_element, storage_table).; Write a Liquibase/Flyway changelog entry (V13_6_001) for this migration.
**Deliverable:** Flyway migration V13_6_001__pii_inventory.sql plus seed data insert script with 8 rows.
**Acceptance / logic checks:**
- Migration runs cleanly on a fresh schema; SELECT COUNT(*) FROM pii_inventory returns 8.
- All 8 rows have residency_required = true and a non-null retention_years value.
- No row in pii_inventory references end_user_name, national_id, bank_account, phone, or address — confirming SEC-09 §7.1 exclusions.
- UNIQUE constraint rejects a duplicate (data_element, storage_table) insert.

### 13.6-T02 — Add data_classification column to all PII-bearing tables  _(40 min)_
**Context:** SEC-09 §7.1 identifies partner_admin (email, mfa_seed), merchant (id, name), and transaction (amounts, rates) as PII-bearing tables. Korean PIPA requires all personal data to be identifiable at the schema level so retention jobs and audit controls can operate on them systematically. Each PII column needs a PostgreSQL comment or a companion classification registry row that records its sensitivity tier: PERSONAL (direct PII), SENSITIVE (MFA seed), FINANCIAL (amounts, rates), or OPERATIONAL (hashed key).
**Steps:** Run COMMENT ON COLUMN statements for each PII column in partner_admin, merchant, transaction tables, e.g. COMMENT ON COLUMN partner_admin.email IS 'PII:PERSONAL retention=contract+1yr residency=KR'.; Create a companion view v_pii_columns that queries pg_description joined to pg_attribute and pg_class to list all columns with a comment starting PII:, returning table_name, column_name, classification, retention_label.; Write migration V13_6_002 for the COMMENT statements.; Verify the view lists at least 6 columns across the 3 target tables.
**Deliverable:** Flyway migration V13_6_002__pii_column_comments.sql and view definition v_pii_columns.
**Acceptance / logic checks:**
- SELECT * FROM v_pii_columns returns >= 6 rows covering partner_admin.email, partner_admin.mfa_seed, merchant.id, merchant.name, and at least 2 transaction columns.
- No end-user identity column (name, national_id, bank_account) appears in v_pii_columns.
- COMMENT ON a non-PII column (e.g. transaction.id) does NOT appear in v_pii_columns.
- View is read-only and requires no special privileges beyond SELECT on the system catalogs.
**Depends on:** 13.6-T01

### 13.6-T03 — Implement data residency enforcement configuration  _(45 min)_
**Context:** SEC-09 §7.3 requires all GMEPay+ production data (DB, audit logs, backups) to be hosted in a Korean data centre or Korean cloud region (e.g. AWS ap-northeast-2 Seoul). Cross-border transfer of personal data is prohibited under Korean PIPA without consent. This ticket adds a startup check and runtime guard: the application refuses to start if the configured data_residency_region does not match the allowed-regions list, and logs the region on every startup.
**Steps:** Add application.yml property: gmepayplus.privacy.allowed-residency-regions (list, default [ap-northeast-2, kr-central-1]) and gmepayplus.privacy.data-residency-region (string, required).; Implement DataResidencyGuard as a Spring ApplicationListener<ApplicationStartedEvent> that compares data-residency-region against allowed-residency-regions; throws IllegalStateException with message DATA_RESIDENCY_VIOLATION: region={value} not in allowed={list} if not matched.; Log INFO on valid startup: Data residency check passed: region={value}.; Write unit tests: valid region passes, invalid region throws, empty region throws.
**Deliverable:** DataResidencyGuard.java, application.yml additions, and DataResidencyGuardTest.java.
**Acceptance / logic checks:**
- App starts normally when gmepayplus.privacy.data-residency-region=ap-northeast-2.
- App throws IllegalStateException containing DATA_RESIDENCY_VIOLATION when region=us-east-1.
- IllegalStateException message includes both the offending value and the allowed list.
- Unit test for empty/null region also throws (not silently passes).
**Depends on:** 13.6-T01

### 13.6-T04 — Implement PII field masking utility for log output  _(50 min)_
**Context:** SEC-09 §7.2 and §6.4 require that log records exclude end-user PII and that sensitive fields (API key, HMAC secret, password) are never written to access logs. If end-user data is accidentally passed in a request field it must not reach persistent logs. This ticket creates a PiiMaskingUtils class with masking methods used by all log serializers and audit formatters in the platform.
**Steps:** Create PiiMaskingUtils in package com.gmepayplus.privacy with static methods: maskEmail(String email) -> first 2 chars + *** + @domain; maskApiKey(String key) -> first 4 chars + *** (never full key); maskMfaSeed(String seed) -> ***REDACTED***; isSuspectedPii(String fieldName) returns true if fieldName contains any of [email, phone, name, address, id_number, national, passport, account_no].; Implement a Jackson JsonSerializer<String>, PiiAwareStringSerializer, that calls isSuspectedPii on the property name and applies appropriate masking if true.; Write unit tests covering: maskEmail(subash@gmeremit.com) == su***@gmeremit.com; maskApiKey(ABCD1234EFGH) == ABCD***; maskMfaSeed(any) == ***REDACTED***; isSuspectedPii(email) == true; isSuspectedPii(amount) == false.
**Deliverable:** PiiMaskingUtils.java, PiiAwareStringSerializer.java, PiiMaskingUtilsTest.java.
**Acceptance / logic checks:**
- maskEmail(subash@gmeremit.com) returns su***@gmeremit.com.
- maskApiKey(ABCD1234EFGH5678) returns ABCD***.
- isSuspectedPii(national_id) returns true; isSuspectedPii(transaction_amount) returns false.
- A Jackson ObjectMapper configured with PiiAwareStringSerializer serializes a map {email: test@x.com} as {email: te***@x.com}.
- maskMfaSeed with any non-null input returns ***REDACTED***.

### 13.6-T05 — Enforce PII exclusion in Partner API request/response logging filter  _(55 min)_
**Context:** SEC-09 §6.4 specifies that every inbound API request is logged with: timestamp, partner_id, method, path, query_string (redacted for sensitive params), response_code, response_time_ms, ip_address, request_id. Sensitive fields (API key, HMAC secret, password) must never appear. This ticket wires the PiiMaskingUtils (13.6-T04) into the API Gateway logging filter so all access logs are safe before persistence.
**Steps:** Create AccessLogFilter implementing javax.servlet.Filter; log a structured JSON line per request including: timestamp, partner_id (from auth context), method, path, query_string (run through maskQueryString which removes or masks params named in SEC-09 §6.4 sensitive list), response_code, response_time_ms, ip_address, request_id.; In maskQueryString: any query param whose name matches isSuspectedPii() or equals X-Api-Key or X-Signature is replaced with REDACTED.; Register filter at ORDER_HIGHEST_PRECEDENCE+1 (after auth).; Write integration test: POST /v1/payments with X-Api-Key header present; assert the log output does NOT contain the raw key value but DOES contain partner_id and request_id.
**Deliverable:** AccessLogFilter.java with maskQueryString logic and AccessLogFilterTest.java.
**Acceptance / logic checks:**
- Log line for a request with query ?email=foo@bar.com contains email=REDACTED not the raw email.
- X-Api-Key header value never appears in the logged line.
- Log line always includes timestamp, method, path, response_code, and request_id fields.
- A request with no sensitive params is logged verbatim (no over-redaction).
- Integration test passes with a real MockMvc request.
**Depends on:** 13.6-T04

### 13.6-T06 — Create retention policy configuration table and seed data  _(40 min)_
**Context:** SEC-09 §7.4 defines these retention periods: transaction records = 7 years; partner portal user accounts = duration of partner contract + 1 year; merchant sync data (from ZeroPay ZP0041/ZP0051) = 7 years; operator session tokens = session duration only (auto-expiry); backup data = 90 days rolling. Each category needs a DB-driven config so retention jobs can read the policy without code changes.
**Steps:** Create migration V13_6_003: table retention_policy (id SERIAL PK, data_category VARCHAR(80) UNIQUE NOT NULL, retention_years SMALLINT, retention_days SMALLINT, deletion_method VARCHAR(80) NOT NULL, notes TEXT, updated_at TIMESTAMPTZ DEFAULT now()). Note: use retention_years OR retention_days (one non-null, other null).; Insert 5 rows: (transaction_record, 7, null, secure_overwrite), (partner_portal_user, null, null, anonymisation, notes=contract_duration+1yr), (merchant_sync_data, 7, null, secure_deletion), (operator_session_token, null, 1, auto_expiry), (backup_data, null, 90, automated_lifecycle).; Add CHECK constraint: (retention_years IS NOT NULL OR retention_days IS NOT NULL).; Write a RetentionPolicyRepository with findByDataCategory(String) returning Optional<RetentionPolicy>.
**Deliverable:** Flyway migration V13_6_003__retention_policy.sql, RetentionPolicy.java (JPA entity), RetentionPolicyRepository.java.
**Acceptance / logic checks:**
- SELECT COUNT(*) FROM retention_policy = 5 after migration.
- findByDataCategory(transaction_record).get().getRetentionYears() == 7.
- findByDataCategory(backup_data).get().getRetentionDays() == 90.
- CHECK constraint rejects a row where both retention_years and retention_days are null.
- findByDataCategory(nonexistent) returns Optional.empty().
**Depends on:** 13.6-T01

### 13.6-T07 — Implement scheduled transaction record retention/deletion job  _(60 min)_
**Context:** SEC-09 §7.4: transaction records must be retained for 7 years then securely deleted (overwrite + audit log of deletion). The retention_policy table (13.6-T06) row data_category=transaction_record has retention_years=7. The job runs daily, identifies transaction rows where committed_at < NOW() - INTERVAL 7 YEARS, anonymises PII fields (sets partner_admin refs to null/placeholder), and writes a deletion audit log entry. Table: transaction columns of interest: id, txn_ref, committed_at, partner_id.
**Steps:** Create TransactionRetentionJob as a Spring @Scheduled job (cron = 0 2 * * * by default, configurable via gmepayplus.privacy.transaction-retention-cron).; Query: SELECT id FROM transaction WHERE committed_at < NOW() - INTERVAL (retention_years || years) and status IN (SETTLED, FAILED, CANCELLED).; For each batch of 500 rows: execute UPDATE transaction SET ... = null/PURGED for any PII-bearing columns identified in v_pii_columns, set purged_at = NOW(), purged_reason = RETENTION_POLICY_7YR.; After each batch write one audit_log entry: event_type=DATA_PURGED, entity_type=transaction, entity_ids=[list], actor=SYSTEM, details={count:N, policy:transaction_record, retention_years:7}.; Log summary at INFO: Purged N transaction records older than 7 years.
**Deliverable:** TransactionRetentionJob.java with batch purge logic and an integration test with 3 transactions (2 older than 7 years, 1 within retention).
**Acceptance / logic checks:**
- Integration test: 2 rows with committed_at = 8 years ago are purged; 1 row with committed_at = 6 years ago is untouched.
- After purge, purged rows have purged_at IS NOT NULL and PII columns set to PURGED or null.
- One audit_log row is written per batch with event_type=DATA_PURGED and correct count.
- Job never deletes rows with status=PENDING or PROCESSING.
- Job processes in batches of 500; a test with 1001 eligible rows produces at least 3 audit_log entries.
**Depends on:** 13.6-T06, 13.6-T02

### 13.6-T08 — Implement scheduled partner portal user account anonymisation job  _(55 min)_
**Context:** SEC-09 §7.4: partner portal user accounts are retained for the duration of the partner contract + 1 year, then anonymised (not deleted). Anonymisation replaces email and mfa_seed with non-recoverable tokens and marks the account ANONYMISED. The partner_portal_user table has columns: id, partner_id, email, mfa_seed, status (ACTIVE|INACTIVE|ANONYMISED), deactivated_at, anonymised_at. Retention basis: if deactivated_at IS NOT NULL AND deactivated_at < NOW() - INTERVAL 1 YEAR then eligible (assumes partner contract has ended if account is deactivated).
**Steps:** Create PartnerUserAnonymisationJob as @Scheduled (cron = 0 3 * * * by default, configurable).; Query eligible users: SELECT id FROM partner_portal_user WHERE status=INACTIVE AND deactivated_at < NOW() - INTERVAL 1 YEAR AND anonymised_at IS NULL.; For each eligible user: UPDATE SET email = ANON_ || id || @purged.invalid, mfa_seed = ***ANONYMISED***, status = ANONYMISED, anonymised_at = NOW().; Write audit_log entry: event_type=USER_ANONYMISED, entity_type=partner_portal_user, entity_id=id, actor=SYSTEM.; Add DB index on (status, deactivated_at) to support efficient query.
**Deliverable:** PartnerUserAnonymisationJob.java, Flyway migration V13_6_004__partner_user_anonymisation_index.sql, and PartnerUserAnonymisationJobTest.java.
**Acceptance / logic checks:**
- Test: user with deactivated_at = 400 days ago and status=INACTIVE is anonymised; email becomes ANON_{id}@purged.invalid.
- Test: user with deactivated_at = 200 days ago is NOT anonymised.
- Test: user already with status=ANONYMISED is not re-processed.
- audit_log entry is written for each anonymised user with correct event_type.
- DB index on (status, deactivated_at) exists after migration.
**Depends on:** 13.6-T06

### 13.6-T09 — Implement merchant sync data retention/deletion job  _(55 min)_
**Context:** SEC-09 §7.4: merchant sync data from ZeroPay (ZP0041 master file, ZP0051 update file) must be retained for 7 years (settlement evidence) then securely deleted. The merchant table has columns: id, zeropay_merchant_id, name, status, synced_at, last_used_at, deleted_at. Merchant data is PII-bearing per pii_inventory (13.6-T01). Deletion method is secure_deletion (hard delete + audit log). A merchant row is eligible for deletion if: last_used_at < NOW() - INTERVAL 7 YEARS AND no transaction references this merchant_id with committed_at >= NOW() - INTERVAL 7 YEARS.
**Steps:** Create MerchantSyncDataRetentionJob as @Scheduled (cron = 0 4 * * * by default, configurable).; Safety check query: SELECT m.id FROM merchant m WHERE m.last_used_at < NOW() - INTERVAL 7 YEARS AND NOT EXISTS (SELECT 1 FROM transaction t WHERE t.merchant_id = m.id AND t.committed_at >= NOW() - INTERVAL 7 YEARS).; For eligible rows: hard-delete from merchant table (CASCADE must not affect settled transaction rows — verify FK is SET NULL or no FK).; Write audit_log entry per deleted merchant: event_type=MERCHANT_DATA_PURGED, entity_type=merchant, entity_id={id}, actor=SYSTEM, details={zeropay_merchant_id: X, reason: RETENTION_POLICY_7YR}.; Log summary at INFO level.
**Deliverable:** MerchantSyncDataRetentionJob.java and MerchantSyncDataRetentionJobTest.java.
**Acceptance / logic checks:**
- Merchant with last_used_at = 8 years ago and no recent transactions is hard-deleted.
- Merchant with last_used_at = 8 years ago but a transaction committed 3 years ago is NOT deleted.
- Merchant with last_used_at = 6 years ago is NOT deleted.
- audit_log row with event_type=MERCHANT_DATA_PURGED and correct zeropay_merchant_id is written for each deleted row.
- Transaction rows referencing the deleted merchant have merchant_id set to null (not cascade-deleted).
**Depends on:** 13.6-T06, 13.6-T02

### 13.6-T10 — Implement backup data lifecycle policy (90-day rolling)  _(45 min)_
**Context:** SEC-09 §7.4: backup data retention is 90 days rolling, managed by automated backup lifecycle policy. This ticket wires the retention_policy.retention_days=90 configuration into the infrastructure-as-code backup lifecycle rule (AWS S3 lifecycle or equivalent) and creates a Spring configuration bean that emits a startup warning if the configured backup retention deviates from the policy value.
**Steps:** Create BackupRetentionPolicyValidator as a Spring @Component with @PostConstruct; reads gmepayplus.privacy.backup-retention-days (integer, from application.yml) and compares to retention_policy table row data_category=backup_data; if deviation > 0 days emit WARN: Backup retention config {actual}d deviates from policy {policy}d — update infrastructure lifecycle rule.; Add application.yml property: gmepayplus.privacy.backup-retention-days=90.; Create IaC template snippet (Terraform or CloudFormation, as comments in a .tf.tpl or .yaml.tpl file): S3 lifecycle rule with expiration_days=90 and abort_incomplete_multipart_upload_days=7.; Write unit test: validator with config=90 and policy=90 emits no warning; config=60 emits WARN.
**Deliverable:** BackupRetentionPolicyValidator.java, application.yml addition, backup_lifecycle_policy.tf.tpl, BackupRetentionPolicyValidatorTest.java.
**Acceptance / logic checks:**
- Unit test: config=90 matches policy=90 -> no warning logged.
- Unit test: config=60 mismatches policy=90 -> WARN log contains both values.
- Terraform template file contains expiration_days = 90.
- @PostConstruct runs at startup without requiring DB to be empty (handles missing policy row gracefully with WARN).
**Depends on:** 13.6-T06

### 13.6-T11 — Add purged_at and purge_reason columns to transaction table  _(30 min)_
**Context:** SEC-09 §7.4 specifies that transaction records are purged after 7 years with secure overwrite and an audit log of deletion. The purge job (13.6-T07) needs to mark rows in place rather than hard-delete them (to preserve referential integrity with audit_log, bok_report_record, and settlement tables). This ticket adds two columns: purged_at TIMESTAMPTZ and purge_reason VARCHAR(80) to the transaction table, plus a partial index for the retention job query.
**Steps:** Write Flyway migration V13_6_005: ALTER TABLE transaction ADD COLUMN purged_at TIMESTAMPTZ, ADD COLUMN purge_reason VARCHAR(80).; Add partial index: CREATE INDEX idx_transaction_retention ON transaction(committed_at) WHERE purged_at IS NULL AND status IN (SETTLED, FAILED, CANCELLED).; Update TransactionRetentionJob (13.6-T07) to set purged_at=NOW() and purge_reason=RETENTION_POLICY_7YR on eligible rows.; Verify the partial index is used by EXPLAIN ANALYZE on the retention job query (confirm Index Scan in test).
**Deliverable:** Flyway migration V13_6_005__transaction_purge_columns.sql with index definition.
**Acceptance / logic checks:**
- Migration applies cleanly; SELECT purged_at FROM transaction LIMIT 1 returns null (column exists).
- EXPLAIN on SELECT id FROM transaction WHERE committed_at < NOW()-INTERVAL 7 YEARS AND purged_at IS NULL AND status=SETTLED shows idx_transaction_retention.
- purge_reason accepts RETENTION_POLICY_7YR (80 chars constraint not violated).
- Column purged_at is nullable (rows not yet purged remain null).
**Depends on:** 13.6-T07

### 13.6-T12 — Implement data minimisation filter for ZeroPay merchant ingest  _(50 min)_
**Context:** SEC-09 §7.2 requires that merchant data received from ZeroPay (ZP0041 master, ZP0051 update files) is used only for payment-time validation and that fields not required for validation (e.g. merchant owner personal details) are discarded on ingest. The ZeroPay merchant file parser (SCH-06) may provide fields beyond what GMEPay+ needs. Only the following fields are retained: zeropay_merchant_id, merchant_name, merchant_category_code, status, settlement_bank_code (for reconciliation ref only, AES-256 encrypted per DAT-03 assumption A6), last_updated.
**Steps:** Create MerchantIngestMapper.toEntity(ZeroPayMerchantRecord raw) that maps ONLY the 6 allowed fields listed above to a Merchant JPA entity; all other fields in ZeroPayMerchantRecord are not mapped and are not logged.; Add a @SneakyThrows guard: if raw contains a field matching isSuspectedPii() beyond the allowed list, log WARN: Discarding unexpected PII field {fieldName} from ZeroPay merchant record — do not log the value.; Write unit test with a ZeroPayMerchantRecord containing 10 fields including owner_name and phone_number; assert the resulting Merchant entity has null for owner_name and phone_number and the 6 required fields populated.; Verify settlement_bank_code is stored AES-256 encrypted (assert stored value != plaintext in DB test).
**Deliverable:** MerchantIngestMapper.java and MerchantIngestMapperTest.java.
**Acceptance / logic checks:**
- Mapping a record with owner_name=John Doe produces a Merchant with no owner_name field populated.
- Mapping logs a WARN for each discarded suspected-PII field name (not value).
- Resulting entity has zeropay_merchant_id, merchant_name, merchant_category_code, status populated from source.
- settlement_bank_code stored value is not equal to the raw input (encryption applied).
- Unit test passes with a 10-field input record.
**Depends on:** 13.6-T04, 13.6-T02

### 13.6-T13 — Implement PIPA personal information processing policy document endpoint  _(55 min)_
**Context:** SEC-09 §7.5 (Korean PIPA): GMEPay+ must maintain a personal information processing policy (개인정보 처리방침) covering all collected data categories. This ticket exposes a GET /v1/privacy/policy endpoint (public, no auth required) returning a structured JSON policy document generated from the live pii_inventory and retention_policy tables. This ensures the policy always reflects the actual data held.
**Steps:** Create PrivacyPolicyController with GET /v1/privacy/policy (no authentication required, rate-limited to 60 req/min per IP by API Gateway config).; Service reads all rows from pii_inventory joined to retention_policy (by data_category) and builds a PrivacyPolicyResponse DTO: {version: string, generated_at: ISO8601, data_controller: GME Remittance Co., pipo_email: dpo@gmeremit.com, legal_basis: contractual_necessity, data_elements: [{name, storage_description, retention_description, residency}], last_updated}.; Map retention_years to human string e.g. 7 years and retention_days to e.g. 90 days.; Write integration test asserting 200 response, content-type application/json, and that data_elements list contains at least partner_admin_email.
**Deliverable:** PrivacyPolicyController.java, PrivacyPolicyService.java, PrivacyPolicyResponse.java DTO, and PrivacyPolicyControllerTest.java.
**Acceptance / logic checks:**
- GET /v1/privacy/policy returns HTTP 200 with Content-Type: application/json.
- Response body contains data_controller = GME Remittance Co.
- data_elements array contains an entry with name=partner_admin_email and retention_description containing 7 years or contract.
- No raw table column names or internal IDs appear in the response (human-readable descriptions only).
- Endpoint requires no authentication and returns 200 without X-Api-Key.
**Depends on:** 13.6-T01, 13.6-T06

### 13.6-T14 — Implement PIPC breach notification workflow data capture  _(50 min)_
**Context:** SEC-09 §7.5 and §10.3: if personal data is involved in a security incident, GME must notify the Personal Information Protection Commission (PIPC) within 72 hours. This ticket creates the data capture mechanism: a data_breach_event table and a DataBreachNotificationService that records breach details, calculates the 72-hour notification deadline, and exposes a query for ops to retrieve pending notifications.
**Steps:** Write Flyway migration V13_6_006: table data_breach_event (id SERIAL PK, detected_at TIMESTAMPTZ NOT NULL, data_categories TEXT[] NOT NULL, estimated_affected_count INTEGER, description TEXT, pipc_notification_deadline TIMESTAMPTZ GENERATED ALWAYS AS (detected_at + INTERVAL 72 HOURS) STORED, notified_at TIMESTAMPTZ, notification_ref VARCHAR(80), status VARCHAR(20) DEFAULT PENDING, created_by VARCHAR(80) NOT NULL).; Implement DataBreachNotificationService.recordBreach(DetectedAt, categories[], affectedCount, description, createdBy) -> DataBreachEvent; persist and return the entity.; Implement getPendingNotifications() -> List<DataBreachEvent> WHERE notified_at IS NULL AND status=PENDING.; Write unit test: recordBreach with detected_at=2026-06-05T10:00:00Z produces pipc_notification_deadline=2026-06-07T10:00:00Z.
**Deliverable:** Flyway migration V13_6_006__data_breach_event.sql, DataBreachNotificationService.java, DataBreachEvent.java, DataBreachNotificationServiceTest.java.
**Acceptance / logic checks:**
- recordBreach with detected_at=2026-06-05T10:00:00Z -> pipc_notification_deadline=2026-06-07T10:00:00Z.
- getPendingNotifications returns only rows with notified_at IS NULL.
- data_categories column rejects null/empty array.
- status defaults to PENDING on insert.
- Migration applies cleanly; generated column pipc_notification_deadline cannot be set manually (attempt raises error).
**Depends on:** 13.6-T01

### 13.6-T15 — Add PIPA-required fields to Admin portal: PIPO designation and policy version  _(55 min)_
**Context:** SEC-09 §7.5 and the go-live compliance checklist item #10: PIPA processing policy must be documented and a PIPO (Personal Information Protection Officer / 개인정보보호책임자) must be designated. This ticket adds a system_config table (or extends an existing one) with PIPA-specific config keys: pipa.pipo_name, pipa.pipo_email, pipa.policy_version, pipa.policy_effective_date. These values feed the public privacy policy endpoint (13.6-T13) and the go-live checklist.
**Steps:** Write Flyway migration V13_6_007: if system_config does not exist, create it as (key VARCHAR(80) PK, value TEXT NOT NULL, updated_at TIMESTAMPTZ, updated_by VARCHAR(80)). If it exists, just insert the 4 seed rows.; Insert seed rows: (pipa.pipo_name, TBD, ...), (pipa.pipo_email, dpo@gmeremit.com, ...), (pipa.policy_version, 1.0, ...), (pipa.policy_effective_date, 2026-06-01, ...).; Expose AdminPrivacyConfigController (Admin role only) with GET /admin/privacy/config and PUT /admin/privacy/config/{key} to update the 4 PIPA keys; all updates audit-logged.; Update PrivacyPolicyService (13.6-T13) to read pipo_email and policy_version from system_config instead of hardcoded values.
**Deliverable:** Flyway migration V13_6_007__pipa_system_config.sql, AdminPrivacyConfigController.java with audit logging, and updated PrivacyPolicyService.java.
**Acceptance / logic checks:**
- GET /admin/privacy/config returns all 4 pipa.* keys with their current values (Admin role).
- GET /admin/privacy/config returns 403 for Partner Admin role.
- PUT /admin/privacy/config/pipa.pipo_email with value compliance@gmeremit.com updates the row and writes an audit_log entry with actor, old_value, new_value.
- GET /v1/privacy/policy response reflects the updated pipo_email after the PUT.
- Migration inserts exactly 4 rows with pipa.* keys.
**Depends on:** 13.6-T13

### 13.6-T16 — Unit tests: PII inventory completeness and residency rules  _(40 min)_
**Context:** WBS 13.6 test ticket. Tests confirm that the pii_inventory table (13.6-T01) is complete per SEC-09 §7.1, that residency_required=true for all rows, and that no prohibited data elements (end-user name, national_id, bank_account, phone, address) are present. Also tests that the v_pii_columns view (13.6-T02) covers the correct tables and columns.
**Steps:** Write PiiInventoryCompletenessTest as a Spring DataJpaTest.; Test 1: assert pii_inventory contains rows for all 8 canonical elements: partner_admin_email, partner_admin_mfa_seed, merchant_id, merchant_name, transaction_amount_krw, transaction_amount_partner_ccy, applied_fx_rate, partner_api_key_hash.; Test 2: assert ALL rows have residency_required=true.; Test 3: assert no row exists with data_element IN (end_user_name, national_id, bank_account, phone, address).; Test 4: query v_pii_columns and assert it contains >= 6 rows covering partner_admin.email and at least one transaction column.
**Deliverable:** PiiInventoryCompletenessTest.java with 4 test methods.
**Acceptance / logic checks:**
- Test 1 passes: exactly 8 expected elements present.
- Test 2 passes: no row has residency_required=false.
- Test 3 passes: prohibited elements absent.
- Test 4 passes: v_pii_columns >= 6 rows, includes partner_admin.email.
- All 4 tests run in < 5 seconds against an H2 or Testcontainers PostgreSQL.
**Depends on:** 13.6-T01, 13.6-T02

### 13.6-T17 — Unit tests: retention policy values and deletion method correctness  _(35 min)_
**Context:** WBS 13.6 test ticket. Verifies that the retention_policy table (13.6-T06) has the exact values mandated by SEC-09 §7.4: transaction_record=7yr secure_overwrite, partner_portal_user=contract+1yr anonymisation, merchant_sync_data=7yr secure_deletion, operator_session_token=auto_expiry, backup_data=90days automated_lifecycle.
**Steps:** Write RetentionPolicyValueTest as a Spring DataJpaTest.; Test each of the 5 rows by data_category: assert retention_years, retention_days, and deletion_method match SEC-09 §7.4 exactly.; Test CHECK constraint: attempt to save a RetentionPolicy with both retention_years=null and retention_days=null; expect ConstraintViolationException.; Test findByDataCategory for a non-existent key returns Optional.empty().
**Deliverable:** RetentionPolicyValueTest.java with 7 test methods (5 value checks + 1 constraint + 1 not-found).
**Acceptance / logic checks:**
- findByDataCategory(transaction_record).retentionYears == 7 and deletionMethod == secure_overwrite.
- findByDataCategory(backup_data).retentionDays == 90 and deletionMethod == automated_lifecycle.
- findByDataCategory(partner_portal_user).deletionMethod == anonymisation.
- Saving a row with null retention_years and null retention_days throws ConstraintViolationException.
- All 7 test methods pass.
**Depends on:** 13.6-T06

### 13.6-T18 — Unit tests: transaction retention job logic (7-year boundary)  _(55 min)_
**Context:** WBS 13.6 test ticket. Verifies TransactionRetentionJob (13.6-T07) with precise boundary test vectors. Uses Testcontainers PostgreSQL to run real SQL. Tests the 7-year boundary (eligible vs. not-eligible), batch size, and audit log generation.
**Steps:** Write TransactionRetentionJobTest using @SpringBootTest with Testcontainers.; Seed: 3 transactions with committed_at = NOW()-8yr (status=SETTLED), 1 with committed_at=NOW()-6yr (status=SETTLED), 1 with committed_at=NOW()-8yr (status=PENDING).; Trigger job manually: transactionRetentionJob.run().; Assert: the 3 SETTLED 8yr-old rows have purged_at IS NOT NULL and purge_reason=RETENTION_POLICY_7YR.; Assert: the 6yr-old SETTLED row and the 8yr-old PENDING row are untouched (purged_at IS NULL).; Assert: audit_log contains at least 1 row with event_type=DATA_PURGED and details containing count:3.
**Deliverable:** TransactionRetentionJobTest.java with 5 assertions.
**Acceptance / logic checks:**
- 3 eligible rows purged; 2 ineligible rows untouched — verified by DB state post-run.
- purge_reason=RETENTION_POLICY_7YR on all purged rows.
- audit_log row exists with event_type=DATA_PURGED.
- PENDING row with committed_at=NOW()-8yr is NOT purged.
- Test completes in < 30 seconds.
**Depends on:** 13.6-T07, 13.6-T11

### 13.6-T19 — Unit tests: partner portal user anonymisation job  _(45 min)_
**Context:** WBS 13.6 test ticket. Verifies PartnerUserAnonymisationJob (13.6-T08) with exact boundary vectors: user deactivated 400 days ago (eligible), 200 days ago (not eligible), already anonymised (idempotent).
**Steps:** Write PartnerUserAnonymisationJobTest using Testcontainers.; Seed: user A with status=INACTIVE, deactivated_at=NOW()-400d; user B with status=INACTIVE, deactivated_at=NOW()-200d; user C with status=ANONYMISED, deactivated_at=NOW()-500d.; Run job manually.; Assert user A: status=ANONYMISED, email=ANON_{id}@purged.invalid, mfa_seed=***ANONYMISED***, anonymised_at IS NOT NULL.; Assert user B: status=INACTIVE, email unchanged.; Assert user C: anonymised_at unchanged (not re-processed).
**Deliverable:** PartnerUserAnonymisationJobTest.java with 6 assertions.
**Acceptance / logic checks:**
- User A email becomes ANON_{A.id}@purged.invalid after job run.
- User A mfa_seed becomes ***ANONYMISED***.
- User B email is unchanged after job run.
- User C anonymised_at is not updated (idempotent).
- One audit_log row per anonymised user with event_type=USER_ANONYMISED.
**Depends on:** 13.6-T08

### 13.6-T20 — Unit tests: PII masking utility edge cases  _(30 min)_
**Context:** WBS 13.6 test ticket. Thorough edge-case coverage for PiiMaskingUtils (13.6-T04): null/empty inputs, single-char local parts, unicode email addresses, very short API keys, and the isSuspectedPii field-name heuristic.
**Steps:** Write PiiMaskingUtilsTest with the following test cases:; maskEmail(null) returns ***REDACTED*** (no NullPointerException).; maskEmail(a@b.com) returns a***@b.com (single-char local part: show 1 char + ***).; maskEmail(subash@gmeremit.com) returns su***@gmeremit.com.; maskApiKey(AB) returns AB*** (key shorter than 4 chars: show all chars + ***).; maskApiKey(ABCDEFGH) returns ABCD***.; isSuspectedPii(email) true; isSuspectedPii(partner_email) true; isSuspectedPii(amount) false; isSuspectedPii(national_id) true; isSuspectedPii(txn_ref) false.
**Deliverable:** PiiMaskingUtilsTest.java with >= 10 test methods.
**Acceptance / logic checks:**
- maskEmail(null) does not throw and returns a non-null masked string.
- maskEmail(a@b.com) returns a***@b.com.
- maskApiKey(AB) returns AB*** (no IndexOutOfBoundsException).
- isSuspectedPii(national_id) returns true.
- isSuspectedPii(amount) returns false.
**Depends on:** 13.6-T04

### 13.6-T21 — Unit tests: PIPC breach notification deadline calculation  _(40 min)_
**Context:** WBS 13.6 test ticket. Verifies DataBreachNotificationService (13.6-T14) deadline arithmetic (detected_at + 72 hours = pipc_notification_deadline) across DST boundary and end-of-day cases. Also tests the pending-notifications filter.
**Steps:** Write DataBreachNotificationServiceTest as a Spring DataJpaTest.; Test 1: recordBreach(detected_at=2026-06-05T10:00:00Z) -> pipc_notification_deadline=2026-06-07T10:00:00Z.; Test 2: recordBreach(detected_at=2026-03-28T23:00:00Z, DST change night) -> deadline=2026-03-30T23:00:00Z (72h UTC arithmetic, not wall-clock).; Test 3: getPendingNotifications returns only records with notified_at IS NULL.; Test 4: after setting notified_at on a record, getPendingNotifications no longer returns it.
**Deliverable:** DataBreachNotificationServiceTest.java with 4 test methods.
**Acceptance / logic checks:**
- Test 1 passes: deadline is exactly detected_at + 72h UTC.
- Test 2 passes: DST boundary does not shift the deadline.
- Test 3 passes: 2 breach records inserted, 1 notified -> getPendingNotifications returns 1.
- Test 4 passes: after mark-notified, getPendingNotifications returns 0.
- All tests run against Testcontainers PostgreSQL (generated column requires real Postgres, not H2).
**Depends on:** 13.6-T14

### 13.6-T22 — Implement data residency compliance check in go-live checklist endpoint  _(60 min)_
**Context:** SEC-09 §11 go-live compliance checklist item #9: data residency confirmed (Korea region). This ticket adds a /admin/compliance/checklist endpoint that returns the status of all 15 go-live checklist items, with item #9 resolved dynamically from DataResidencyGuard (13.6-T03) and item #10 from system_config pipa.pipo_email (13.6-T15). Other items are stored as manual-verification records in a compliance_checklist_item table.
**Steps:** Write Flyway migration V13_6_008: table compliance_checklist_item (item_no SMALLINT PK, description VARCHAR(200), verified_by VARCHAR(80), verified_at TIMESTAMPTZ, status VARCHAR(20) DEFAULT PENDING, notes TEXT). Insert 15 rows matching SEC-09 §11 items 1-15.; Create ComplianceChecklistController GET /admin/compliance/checklist (Admin role only) returning all 15 items; items #9 and #10 are computed dynamically (region check + PIPO email presence).; PUT /admin/compliance/checklist/{item_no} allows Admin to set status=VERIFIED, verified_by, notes; action is audit-logged.; Write integration test: item #9 status=VERIFIED when data-residency-region=ap-northeast-2; item #10 status=PENDING when pipa.pipo_email=TBD.
**Deliverable:** Flyway migration V13_6_008__compliance_checklist.sql, ComplianceChecklistController.java, ComplianceChecklistService.java, and ComplianceChecklistControllerTest.java.
**Acceptance / logic checks:**
- GET /admin/compliance/checklist returns 15 items.
- Item #9 status=VERIFIED when gmepayplus.privacy.data-residency-region=ap-northeast-2.
- Item #10 status=PENDING when pipa.pipo_email=TBD or empty.
- PUT /admin/compliance/checklist/1 with status=VERIFIED writes an audit_log entry.
- Endpoint returns 403 for Finance and Ops roles; 200 for Admin role.
**Depends on:** 13.6-T03, 13.6-T15

### 13.6-T23 — Write developer runbook section: PIPA obligations and privacy controls  _(40 min)_
**Context:** SEC-09 §7 and the go-live checklist require that the PIPA processing policy is documented. This ticket adds a PRIVACY.md section to the repo docs directory that a developer can read in < 5 minutes to understand: what PII GMEPay+ holds, what it does NOT hold, how retention jobs work, the 72-hour PIPC notification obligation, residency requirement, and the list of privacy-related endpoints/jobs.
**Steps:** Create docs/PRIVACY.md with sections: 1) PII Inventory (table from pii_inventory, 8 rows); 2) What we do NOT hold (end-user name, national ID, bank account etc.); 3) Retention schedule (table from retention_policy, 5 rows with human-readable descriptions); 4) Data residency (Korea region, ap-northeast-2, PIPA cross-border prohibition); 5) Jobs (TransactionRetentionJob cron schedule, PartnerUserAnonymisationJob, MerchantSyncDataRetentionJob); 6) PIPC breach notification (72-hour deadline, DataBreachNotificationService, PIPO contact); 7) Endpoints (GET /v1/privacy/policy, GET /admin/compliance/checklist).; Include a note: GMEPay+ is a routing hub and does not hold end-user KYC data; the PII listed here is limited to platform operator and merchant metadata.
**Deliverable:** docs/PRIVACY.md file (< 300 lines).
**Acceptance / logic checks:**
- File exists at docs/PRIVACY.md.
- Section 1 table lists exactly 8 data elements matching pii_inventory seed data.
- Section 3 retention table lists 5 categories with correct retention periods matching SEC-09 §7.4 (7yr, contract+1yr, 7yr, session, 90d).
- Section 4 states ap-northeast-2 as the required region.
- Section 6 states 72 hours as the PIPC notification deadline.
**Depends on:** 13.6-T01, 13.6-T06, 13.6-T13, 13.6-T14, 13.6-T22


## WBS 13.10 — Security testing & penetration test
### 13.10-T01 — Define pen-test scope document and target system inventory  _(45 min)_
**Context:** WBS 13.10 covers application and penetration security testing against SEC-09 controls, with remediation, culminating in a signed Pen-test Report. Before testing begins, an agreed scope document must be produced listing all in-scope surfaces: Partner API endpoints (GET /v1/rates, POST /v1/payments, POST /v1/payments/cpm/generate, POST /v1/payments/cpm/commit, GET /v1/payments/{id}, POST /v1/payments/{id}/cancel, GET /v1/balance, GET /v1/transactions, POST /v1/auth/token), Admin portal, Partner portal, ZeroPay SFTP client, secrets vault access paths, and inter-service mTLS links. SEC-09 §4 STRIDE threats T-01 to T-10 and §11 go-live checklist items 1-15 frame the coverage requirements.
**Steps:** List all GMEPay+ internet-facing and internal network surfaces from SAD-02 and SEC-09 §2.1 network zones; Map each surface to the corresponding SEC-09 STRIDE threat (T-01 to T-10) and go-live checklist item; Enumerate test accounts: test partners P-TEST-001 to P-TEST-005, Admin roles (Admin/Ops/Finance), Partner Admin/Viewer roles; Agree out-of-scope items: partner app UIs, live KFTC production SFTP, production secrets vault; Produce scope document and get sign-off from GME security contact; Commit scope document to /docs/security/pentest-scope-v1.md in the repo
**Deliverable:** pentest-scope-v1.md document listing all in-scope surfaces, test accounts, threat coverage map, and explicit out-of-scope items
**Acceptance / logic checks:**
- All 10 STRIDE threats (T-01 to T-10) from SEC-09 §4 have at least one in-scope test surface assigned
- All 15 go-live checklist items from SEC-09 §11 are mapped to a test category or marked as verified by another workstream
- Five synthetic test partners and three Admin roles are listed with pre-provisioned credentials
- Production secrets vault and live KFTC SFTP are explicitly listed as out of scope
- Document is signed off by GME security contact before testing begins

### 13.10-T02 — Provision isolated pen-test environment and test accounts  _(60 min)_
**Context:** Penetration testing must run in the staging environment (see QA-12 §2.3) seeded with synthetic data; it must never touch production data, production secrets, or live KFTC SFTP. Staging must have all five synthetic partners (P-TEST-001 to P-TEST-005), treasury rates from QA-12 §3.3 (e.g. treasury.usd_krw=1350.00, treasury.usd_mnt=3500.00), prefunding balances from QA-12 §3.4, and five synthetic merchants (M-TEST-0001 to M-TEST-0005). Five separate Admin portal accounts must exist covering Admin, Ops, and Finance roles per SEC-09 §3.4. A separate Partner Portal test user per partner must exist with TOTP seeds recorded.
**Steps:** Verify staging environment is deployed and healthy (all services responding); Seed test DB with partners, treasury rates, merchants, and prefunding balances per QA-12 §3.3 and §3.4; Create Admin portal accounts: one Admin, one Ops, one Finance; record credentials in a secure test-only vault; Create Partner Portal accounts for P-TEST-001 and P-TEST-002 with TOTP seeds; Generate Partner API key pairs for P-TEST-001 to P-TEST-005 via Admin portal; record API keys and HMAC secrets; Snapshot staging DB state so environment can be restored after destructive tests
**Deliverable:** Provisioned staging pen-test environment with confirmed healthy status, seeded test data, and a secure test-credential manifest (stored in test-only vault, not in source code)
**Acceptance / logic checks:**
- GET /v1/rates for P-TEST-002 returns HTTP 200 with treasury.usd_mnt=3500.00 and treasury.usd_krw=1350.00 in the rate computation
- All five synthetic merchants are queryable from the Admin portal
- Partner Portal login for P-TEST-001 user succeeds with TOTP
- DB snapshot restore completes without error in < 5 minutes
- No test credentials appear in any git commit or log file
**Depends on:** 13.10-T01

### 13.10-T03 — Execute TLS configuration scan on all endpoints (SEC-09 checklist item 1)  _(45 min)_
**Context:** SEC-09 §2.4 requires TLS 1.2 minimum (TLS 1.3 preferred) for Partner API, Admin portal, and Partner Portal. HTTP must be rejected for any connection carrying credentials, payment data, or PII. SEC-09 §11 item 1: TLS 1.2+ on all endpoints; no HTTP. HSTS must be enforced on Admin/portal browsers. The ZeroPay SFTP uses SSH (Ed25519/RSA-4096), not TLS, but host key pinning applies (SEC-09 §2.5). Use tools such as testssl.sh or sslyze against the staging API Gateway and portal hostnames.
**Steps:** Run testssl.sh (or sslyze) against API Gateway staging hostname on port 443; Run against Admin portal staging hostname; Run against Partner Portal staging hostname; Attempt plain HTTP connection to each endpoint; confirm redirect or rejection; Check HSTS header is present on Admin and Partner Portal responses; Verify TLS 1.0 and 1.1 are rejected (connection should fail)
**Deliverable:** TLS scan report (CSV/JSON output from testssl.sh) for all three endpoints, with pass/fail annotation against SEC-09 §2.4 requirements
**Acceptance / logic checks:**
- TLS 1.2 and TLS 1.3 are both accepted on all three endpoints; TLS 1.0 and 1.1 connections are refused
- HTTP (port 80) plain-text connection returns HTTP 301 redirect to HTTPS or TCP reset; never serves API data
- Strict-Transport-Security header present with max-age >= 31536000 on Admin and Partner Portal responses
- No weak cipher suites (RC4, 3DES, export ciphers) are offered by any endpoint
- Scan report saved to /docs/security/pentest-results/tls-scan.json and references go-live checklist item 1 as PASS or FAIL
**Depends on:** 13.10-T02

### 13.10-T04 — Test HMAC replay protection and timestamp window (threat T-01, T-02; checklist item 2)  _(45 min)_
**Context:** SEC-09 §3.3 defines Partner API authentication: HMAC-SHA256 over (HTTP_METHOD + newline + request_path + newline + timestamp_unix + newline + SHA256(request_body)) using partner_hmac_secret. Replay protection: X-Timestamp drift > +/-300 seconds returns HTTP 401 TIMESTAMP_DRIFT. X-Nonce (UUID v4) is cached for 600 seconds; duplicate nonce returns HTTP 401 REPLAY_DETECTED. STRIDE threat T-01 (credential theft) and T-02 (replay). SEC-09 §11 item 2. Use P-TEST-002 credentials (OVERSEAS partner).
**Steps:** Send valid POST /v1/payments with correct HMAC, X-Timestamp within window, and unique X-Nonce; confirm HTTP 200; Replay same request (identical X-Nonce, X-Timestamp, HMAC) within 600 seconds; confirm HTTP 401 REPLAY_DETECTED; Send request with X-Timestamp = current_unix - 400 (outside 300-second window); confirm HTTP 401 TIMESTAMP_DRIFT; Send request with X-Timestamp = current_unix + 400; confirm HTTP 401 TIMESTAMP_DRIFT; Send request with valid X-Timestamp and X-Nonce but corrupted HMAC (change last 4 chars); confirm HTTP 401; Send request with valid X-Api-Key but no X-Signature header; confirm HTTP 401
**Deliverable:** Test execution report for HMAC/replay tests with exact request payloads, response codes, and error codes logged
**Acceptance / logic checks:**
- Replayed request within 600-second nonce cache window returns HTTP 401 with error code REPLAY_DETECTED
- X-Timestamp 400 seconds in the past returns HTTP 401 TIMESTAMP_DRIFT (beyond +/-300 second window)
- X-Timestamp 400 seconds in the future returns HTTP 401 TIMESTAMP_DRIFT
- Corrupted HMAC returns HTTP 401 (not 200 or 500)
- Missing X-Signature returns HTTP 401 (not 500 or validation leak)
**Depends on:** 13.10-T02

### 13.10-T05 — Test IDOR and cross-partner data isolation (threat T-05; checklist item 3)  _(50 min)_
**Context:** SEC-09 §3.5 and STRIDE threat T-05: every data record has a non-nullable partner_id; queries enforce WHERE partner_id = authenticated_partner_id. Partner Portal backend derives partner_id from session token only, never from the request body. SEC-09 §11 item 3: RBAC and IDOR test cases. QA-12 §8.2 lists IDOR as a mandatory security test category. Use P-TEST-001 and P-TEST-002 API credentials and portal accounts. Partner UUIDs are non-sequential (UUID v4).
**Steps:** Authenticate as P-TEST-001 via Partner API; capture a known transaction ID from P-TEST-001; Authenticate as P-TEST-002 via Partner API; attempt GET /v1/payments/{P-TEST-001-txn-id}; confirm HTTP 403 or 404; Attempt GET /v1/balance with P-TEST-001 Bearer token while injecting partner_id=P-TEST-002 in request body or query string; confirm only P-TEST-001 balance is returned; Login to Partner Portal as P-TEST-001 user; attempt to access /transactions?partner_id=P-TEST-002-uuid URL parameter; confirm no P-TEST-002 data returned; Test sequential UUID enumeration: attempt GET /v1/payments/ with a manually incremented integer ID; confirm UUID format is required and no data returned; Attempt to access Admin portal endpoints (e.g. GET /admin/api/partners) using P-TEST-001 Partner API Bearer token; confirm HTTP 401 or 403
**Deliverable:** IDOR test execution report with exact endpoint URLs, injected parameters, and response codes for all six test cases
**Acceptance / logic checks:**
- GET /v1/payments/{P-TEST-001-txn-id} with P-TEST-002 credentials returns HTTP 403 or 404, never HTTP 200 with P-TEST-001 data
- Injecting partner_id=P-TEST-002 in GET /v1/balance request body or URL with P-TEST-001 token returns only P-TEST-001 balance
- Partner Portal UI returns zero results when partner_id query parameter is manually set to another partner UUID
- Integer-format transaction ID (e.g. /v1/payments/12345) returns HTTP 400 or 404 with no data
- Admin portal API endpoint returns HTTP 401 or 403 when called with Partner API token
**Depends on:** 13.10-T02

### 13.10-T06 — Test RBAC permission matrix across Admin portal roles (SEC-09 §3.4; checklist item 3)  _(45 min)_
**Context:** SEC-09 §3.4 defines three Admin roles: Admin (full access), Ops (monitoring/config view), Finance (settlement/reporting). The permission matrix defines which actions each role can perform: e.g. Ops cannot activate partners (Admin-only), Finance cannot access audit logs. SEC-09 §11 item 3. Test using three distinct Admin accounts: admin-test@, ops-test@, finance-test@ configured in the staging environment. Key admin-only actions include: partner registration/activation, scheme registration, Admin user management. Finance-only: BOK report generation.
**Steps:** Login to Admin portal as Ops role; attempt to activate a partner (should be denied per permission matrix); Login as Ops; attempt to access Audit log view (should be denied - Admin-only); Login as Finance; attempt partner registration (should be denied); Login as Finance; confirm BOK report generation is accessible (Finance has Full access); Login as Admin; confirm all above actions succeed; Login as Ops; confirm transaction search is accessible (Ops has Full access)
**Deliverable:** RBAC test execution report mapping each tested action to expected role permission and actual HTTP response code or UI behaviour
**Acceptance / logic checks:**
- Ops account attempting partner activation returns HTTP 403 or UI shows Access Denied with no partial effect
- Ops account cannot view audit log (HTTP 403 or menu item absent/disabled)
- Finance account cannot register or activate partners (HTTP 403)
- Finance account can successfully generate or view BOK FX reports (HTTP 200 / feature accessible)
- Admin account succeeds on all tested actions (HTTP 200/201)
- All access-denied attempts are audit-logged with actor_id, actor_role, event_type, and timestamp
**Depends on:** 13.10-T02

### 13.10-T07 — Test Admin portal authentication controls: MFA enforcement and session security (SEC-09 §3.1; checklist item 8)  _(55 min)_
**Context:** SEC-09 §3.1 Admin portal authentication: SSO (SAML 2.0 or OIDC) with MFA required for all logins (hardware token or authenticator app). Session max 8 hours; idle timeout 30 minutes. 5 consecutive failures lock account (unlocked by IT admin). Finance settlement approval and partner activation require re-authentication within session. HSTS enforced (covered in T-03). Privileged actions require step-up auth.
**Steps:** Attempt Admin portal login without completing MFA challenge; confirm access is denied; Attempt 5 consecutive failed logins on ops-test@ account; confirm account locks on 5th failure; Verify locked account cannot log in even with correct credentials; verify IT admin unlock is the only path; Let an authenticated Admin session sit idle for 35 minutes; attempt to perform an action; confirm idle timeout re-prompts for authentication; Authenticate as Finance role; attempt to initiate settlement approval; confirm step-up re-authentication is required; Verify session token is invalidated on logout (token cannot be reused after logout)
**Deliverable:** Admin portal authentication test report with browser network traces and screenshots showing MFA gate, lockout response, idle timeout, and step-up auth prompts
**Acceptance / logic checks:**
- Login flow without MFA step completion does not issue a session token or grant portal access
- Fifth consecutive failed login results in account lock; sixth attempt with correct password also returns locked-account error
- After 30+ minutes of inactivity, performing any portal action triggers re-authentication, not silent continuation
- Finance-role settlement approval prompts for re-authentication within the current session
- Logout invalidates the session token; replaying the prior session cookie returns HTTP 401 or redirects to login
- Account lockout event appears in audit log with actor IP, timestamp, and event_type=ACCOUNT_LOCKED
**Depends on:** 13.10-T02

### 13.10-T08 — Test Partner Portal authentication controls: TOTP, password policy, and session (SEC-09 §3.2)  _(45 min)_
**Context:** SEC-09 §3.2 Partner Portal authentication: email + password + TOTP. Password minimum 12 characters with complexity; bcrypt hashed. Session max 8 hours; idle timeout 15 minutes. 5 consecutive failures cause 15-minute lockout (not admin-unlock, unlike Admin portal). Recovery codes issued at TOTP setup, stored hashed. Test using the P-TEST-001 portal user provisioned in T02.
**Steps:** Attempt login with correct email/password but wrong TOTP code; confirm rejection; Attempt 5 consecutive failed logins; confirm 15-minute lockout (not immediate unlock path); Attempt to register a new partner portal user with password shorter than 12 characters; confirm rejection; Attempt a login after lockout window of 15 minutes; confirm account re-allows login; Test session idle timeout: after 16+ minutes of inactivity attempt an action; confirm re-authentication required; Attempt to use a TOTP recovery code to log in; confirm it is accepted and single-use (second use of same code rejected)
**Deliverable:** Partner Portal authentication test report covering TOTP rejection, lockout timing, password policy enforcement, idle timeout, and recovery code single-use
**Acceptance / logic checks:**
- Wrong TOTP with correct email/password returns login failure without revealing which factor failed
- 5 consecutive failures trigger a 15-minute lockout message; the account automatically becomes accessible after 15 minutes without admin action
- Password of 11 characters is rejected at registration with a minimum-length error
- After 16 minutes of idle, an action triggers re-authentication redirect rather than silently proceeding
- Recovery code is accepted on first use and rejected as USED/INVALID on second use
**Depends on:** 13.10-T02

### 13.10-T09 — Test secrets management: no credentials in logs, responses, or error messages (SEC-09 §2.3; checklist items 5,7)  _(50 min)_
**Context:** SEC-09 §2.3 and §6.4: partner API secrets are stored bcrypt/Argon2 hashed; HMAC signing keys encrypted in vault; neither is logged or returned in any API response. SEC-09 §6.4 explicitly states sensitive fields (API key, HMAC secret, password) are never written to access logs. SEC-09 §11 item 5: partner secrets hashed at rest; plaintext never in logs. Item 7: no credentials in config files or source code. QA-12 §8.2: secret management test category. Test using staged error conditions and log inspection.
**Steps:** Trigger a 401 error by sending an invalid HMAC; inspect the response body and headers for any reflection of the submitted HMAC secret or API key; Send a POST /v1/auth/token with an incorrect password; inspect response body for any partial credential echo; Check staging application logs (API Gateway access log and application log) after a series of auth failures for any occurrence of API key, HMAC secret, or password values; Attempt path traversal on the API Gateway error handler (e.g. /v1/../etc/passwd) and inspect error response for internal path or config data; Inspect any verbose error responses (HTTP 500) for stack traces containing connection strings or vault tokens; Check that GET /v1/payments/{id} response for a committed transaction does not return m_a, m_b, cost_rate_coll, cost_rate_pay, or payout_margin_usd fields
**Deliverable:** Secrets-in-outputs test report with log excerpts (redacted), response bodies, and a finding for any credential or internal rate data found in logs or API responses
**Acceptance / logic checks:**
- No API key or HMAC secret value appears in the 401 response body or in any response header after an auth failure
- Application access logs for the test window contain no occurrence of the HMAC secret string or plaintext password
- HTTP 500 error responses do not include Java/Python stack traces, DB connection strings, or vault token references
- GET /v1/payments/{id} response JSON contains no fields named m_a, m_b, cost_rate_coll, cost_rate_pay, payout_margin_usd, or collection_margin_usd
- Path traversal attempt on error handler returns HTTP 400 or 404 with a generic error message, no file system path
**Depends on:** 13.10-T02

### 13.10-T10 — Test input validation and injection resistance on Partner API (SEC-09 §5.1; OWASP API1, API6)  _(45 min)_
**Context:** SEC-09 §5.1 mandates server-side validation: monetary amounts must be positive decimals; currency codes ISO 4217 whitelist (unknown codes return HTTP 422); partner/transaction IDs must be UUID v4; free-text fields (merchant names) must have maximum length enforced and reject HTML/script tags. No amount calculation is accepted from the partner client. SEC-09 §5.1 also states amounts must have explicit scale (KRW=0 decimals, USD=2 decimals). Test against POST /v1/payments and GET /v1/rates.
**Steps:** Submit POST /v1/payments with target_payout = -1 (negative amount); confirm HTTP 422 with descriptive error; Submit GET /v1/rates with currency=INVALID (not in ISO 4217); confirm HTTP 422; Submit POST /v1/payments with target_payout as a string (e.g. '13500 OR 1=1'); confirm HTTP 422 and no SQL error leaked; Submit POST /v1/payments with partner_id field in request body set to a different partner UUID; confirm it is ignored and server derives partner from auth token only; Submit POST /v1/payments with an oversized free-text field (e.g. merchant_note = 10,000 character string); confirm HTTP 422 or field truncation with no server crash; Submit POST /v1/payments with target_payout = 0 (zero amount); confirm HTTP 422
**Deliverable:** Input validation test report with request payloads, HTTP response codes, and error bodies for all six test cases
**Acceptance / logic checks:**
- Negative target_payout returns HTTP 422 with an error code indicating invalid amount, not HTTP 500
- Unknown currency code INVALID returns HTTP 422 (not 200 or 500); error body does not leak internal schema details
- SQL injection string in target_payout field returns HTTP 422; no SQL error message appears in the response body
- partner_id injected in POST body is silently ignored; transaction is attributed to the authenticated partner only
- Zero target_payout returns HTTP 422
- 10,000-character free-text field returns HTTP 422 or is silently truncated to the documented maximum; server does not crash or return HTTP 500
**Depends on:** 13.10-T02

### 13.10-T11 — Test OWASP API Top 10 systematic coverage on Partner API (SEC-09 §5.5; QA-12 §8.2)  _(55 min)_
**Context:** SEC-09 §5.5 maps each OWASP API Security Top 10 risk to a GMEPay+ control. This ticket covers the four risks not individually tested in T04/T05/T10: API3 (broken object property level auth - internal fields hidden), API7 (SSRF via webhook URL), API8 (security misconfiguration), API9 (improper inventory management). API3 check: m_a, m_b, cost rates never returned in Partner API or portal. API7: webhook URLs validated at registration time to be HTTPS only. API8: no default credentials. API9: no /v1 deprecated endpoints accessible, /v2 does not exist yet.
**Steps:** Test API3: call GET /v1/rates and POST /v1/payments responses; grep for m_a, m_b, cost_rate_coll, cost_rate_pay, payout_margin_usd, collection_margin_usd fields; Test API7: attempt to register a partner with a webhook URL starting with http:// (not https://); confirm rejection; attempt with a URL pointing to an internal host (e.g. http://10.0.0.1/webhook); confirm rejection; Test API8: attempt to access the API with empty/null credentials; attempt login to Admin portal with default/blank password; Test API9: confirm GET /v2/rates and GET /v1.0/rates return HTTP 404; confirm no hidden /internal/ or /debug/ endpoints respond with 200; Test rate limiting (API4): send 121 GET /v1/rates requests within one minute from P-TEST-002; confirm HTTP 429 with Retry-After header on breach (default limit 120 rpm per SEC-09 §5.4); Confirm /v1/admin/* endpoints return HTTP 404 or 403 when routed through the Partner API Gateway
**Deliverable:** OWASP API Top 10 coverage report with findings and pass/fail status for API3, API4, API7, API8, API9 controls
**Acceptance / logic checks:**
- GET /v1/rates response JSON contains no field named m_a, m_b, cost_rate_coll, or any GME internal margin field
- Webhook registration with http:// URL returns HTTP 422 or 400 rejecting the URL; internal host URL also rejected
- Admin portal rejects blank/empty password login with HTTP 401; no default credential pair (admin/admin, admin/password) succeeds
- GET /v2/rates returns HTTP 404; no /internal/ or /debug/ route responds with HTTP 200
- 121st GET /v1/rates request within 60 seconds returns HTTP 429 with a Retry-After header value > 0
- Partner API token cannot access any /admin/ prefixed route (HTTP 403 or 404)
**Depends on:** 13.10-T02

### 13.10-T12 — Test prefunding manipulation resistance: negative amounts, overflow, and race condition (SEC-09 §5.3, threat T-04)  _(55 min)_
**Context:** STRIDE threat T-04: forged requests to over-deduct or skip prefunding deduction. SEC-09 §5.3: prefunding gate - OVERSEAS payment rejected before scheme if prefunding insufficient. Deduction is atomic (SELECT FOR UPDATE per RATE-04 §prefunding). Amounts are derived server-side from rate engine; client-supplied amounts are ignored. QA-12 PF-003 tests race condition: two concurrent requests from same partner must result in exactly one deduction per transaction. P-TEST-002 has prefunding balance USD 50,000 in the test fixture.
**Steps:** Submit POST /v1/payments for P-TEST-002 with a crafted request body that includes a custom collection_usd field set to 0.01 (attempting to bypass real deduction); verify that the deduction uses the server-computed amount from rate engine; Submit POST /v1/payments with target_payout = 9999999999 KRW (very large value); confirm either HTTP 422 (exceeds partner limit) or that the rate engine computes the correct collection_usd and checks prefunding properly; Submit two simultaneous POST /v1/payments from P-TEST-002 using different idempotency keys; verify total prefunding deducted equals the sum of both collection_usd amounts (no double-count, no skip); Set P-TEST-002 prefunding balance to USD 9,000 via test DB; attempt a payment where computed collection_usd = 10,000 USD; confirm INSUFFICIENT_PREFUNDING before scheme is called; Verify that the scheme integration (ZeroPay mock) is never invoked when a prefunding check fails - check the mock SFTP/API call log; Send POST /v1/payments with a negative target_payout (covered in T10) and separately verify no negative deduction occurs in the prefunding balance
**Deliverable:** Prefunding manipulation test report with balance logs before and after each test, mock scheme call logs, and confirmation of atomic deduction behaviour
**Acceptance / logic checks:**
- Collection_usd used for deduction matches the server-side rate engine output, not any client-supplied value; injected collection_usd field in request body is ignored
- Two concurrent payment requests each produce exactly one prefunding deduction at the correct amount; no double-deduct or skip occurs
- INSUFFICIENT_PREFUNDING error is returned before any ZeroPay mock API/SFTP call is recorded in the mock call log
- Prefunding balance does not go negative under any test scenario; balance floor is 0
- Negative target_payout produces HTTP 422 and zero change to prefunding balance
**Depends on:** 13.10-T02

### 13.10-T13 — Test SFTP path traversal and host key pinning for ZeroPay integration (SEC-09 §2.5, threat T-06; checklist item 6)  _(55 min)_
**Context:** SEC-09 §2.5: ZeroPay SFTP uses Ed25519 key pair; ZeroPay host public key is pinned; connection is rejected if host key changes. STRIDE threat T-06: ZP0061 settlement file modified before transmission. SEC-09 §11 item 6: SFTP host key pinning verified. QA-12 §8.2 SFTP path traversal test. The ZeroPay SFTP client operates in the Scheme Integration Zone (restricted egress only). SFTP private key is in the secrets vault, never on disk.
**Steps:** Configure a mock SFTP server with a different host key than the pinned ZeroPay key in staging; attempt a ZeroPay batch transmission; confirm connection is refused; Inspect the batch file staging area: attempt to access files outside the designated SFTP upload directory (e.g. ../etc/passwd) via any SFTP path parameter in the batch job configuration; Confirm the SFTP private key is loaded from the secrets vault at runtime, not from a local file path on the application server; check application startup logs for vault access rather than file read; Verify a generated ZP0011 batch file has its hash logged in the audit system before SFTP transmission; manually alter the file on disk and confirm the hash mismatch is detected; Confirm no SFTP credentials (private key material, username) appear in the batch job application logs; Attempt to trigger the batch job API with a crafted file path parameter containing ../ sequences; confirm the batch job rejects or sanitises the path
**Deliverable:** SFTP security test report covering host key pinning rejection, path traversal resistance, vault-based key loading, and batch file audit hash verification
**Acceptance / logic checks:**
- SFTP connection to mock server with wrong host key is rejected with a key mismatch error and the batch job does not transmit any data
- Batch job API rejects or sanitises any ../ sequence in file path parameters; no files outside the designated directory are accessible
- Application startup log shows vault token request for the SFTP key, not a local file read; no private key material appears in logs
- A manually modified ZP0011 file (after hash is logged) causes a hash-mismatch alert when the integrity check runs; the unaltered hash in audit log matches the original file
- SFTP connection username and private key material are absent from batch job application logs
**Depends on:** 13.10-T02

### 13.10-T14 — Test audit log append-only integrity and tamper evidence (SEC-09 §6.3; checklist item 4)  _(55 min)_
**Context:** SEC-09 §6.3: audit log is append-only (no UPDATE/DELETE on audit records); each record includes prev_hash (SHA-256 of previous record) to form a hash chain; daily integrity check job detects breaks in the chain and logs results to an admin-only monitoring endpoint. SEC-09 §11 item 4: audit log append-only; hash chain integrity verified. SEC-09 §6.1 requires configuration changes to log actor_id, actor_role, event_type, entity_type/entity_id, changed_field, previous_value, new_value, timestamp, ip_address.
**Steps:** As Admin, update a rule's m_a from 1.5% to 1.6%; query audit log and verify the entry contains actor_id, actor_role, event_type=RULE_MARGIN_UPDATED, changed_field=m_a, previous_value=0.015, new_value=0.016, timestamp (UTC), and ip_address; Attempt a SQL UPDATE directly on the audit_log table in the staging DB using the application DB user; confirm it is rejected (DB user has INSERT-only permission on audit_log); Attempt a SQL DELETE on the audit_log table; confirm it is rejected; Trigger the daily hash chain integrity check job; confirm it reports no breaks when the log is unaltered; Manually alter one audit record row in the staging DB using a DBA-level account; re-run the integrity check job; confirm it reports a chain break at the altered record; Verify that Ops-role access to audit log is denied (Admin-only per permission matrix in SEC-09 §3.4)
**Deliverable:** Audit log integrity test report with SQL attempt logs, audit record JSON sample, hash chain check output (clean and broken states), and permission denial evidence
**Acceptance / logic checks:**
- Rule margin change audit entry contains all 9 required fields (actor_id, actor_role, event_type, entity_type, entity_id, changed_field, previous_value, new_value, timestamp) with correct values: previous_value=0.015, new_value=0.016
- Application DB user cannot execute UPDATE or DELETE on audit_log table (permission denied SQL error)
- Hash chain integrity check reports PASS when no records are altered
- Hash chain integrity check reports FAIL/BREAK at the correct row position after a record is manually altered
- Ops role cannot view audit log (HTTP 403 or UI feature absent)
**Depends on:** 13.10-T02

### 13.10-T15 — Test rate injection and margin bypass attempts (SEC-09 §5.1, threat T-07; QA-12 §8.2)  _(50 min)_
**Context:** STRIDE threat T-07: Ops user lowers m_a + m_b below 2% on live cross-border rule. SEC-09 §3.4: Admin role required for rule activation; Ops cannot activate. Minimum combined margin 2% enforced in rate engine (RATE-04). SEC-09 §5.1: no amount calculation is accepted from the partner client - all payment amounts, FX rates, collection amounts derived server-side. QA-12 test AD-007 and RV-08: margin below 2% rejected at config time. This ticket covers the security angle: can the 2% minimum be bypassed via API or direct manipulation?
**Steps:** As Ops role, attempt to set m_a = 0.010 and m_b = 0.009 (combined 1.9%) on a cross-border rule via Admin portal; confirm validation error and rule not saved; As Admin role, submit the same sub-2% margin via the Admin API directly (bypass UI); confirm the API layer also rejects it with a validation error; Attempt to craft a POST /v1/payments request with a custom rate_override field or offer_rate field in the body; confirm server ignores it and computes rates from rule configuration; Attempt to call POST /v1/payments with a manually specified collection_usd value in the request body; confirm the server ignores the client-supplied value; As Admin role, activate a rule with m_a + m_b = exactly 2.0% (1.0% + 1.0%); confirm it is accepted and a payment using this rule produces collection_usd = target_payout / cost_rate_pay / 0.980; Verify audit log records both the rejected margin attempt and the accepted 2.0% margin save, each with previous_value and new_value
**Deliverable:** Rate injection test report with Admin portal validation screenshots, Admin API rejection responses, and rate engine output for the 2.0% boundary case
**Acceptance / logic checks:**
- Admin portal rejects m_a=0.010, m_b=0.009 (combined 1.9%) with a validation error message referencing the 2.0% minimum; rule is not persisted in DB
- Admin API also rejects the same sub-2% margin with HTTP 422; error response contains a field-level validation error
- POST /v1/payments body with rate_override or collection_usd field is silently ignored; transaction uses server-computed rates
- Payment using a rule with m_a=0.010, m_b=0.010 against target_payout=13500 KRW, cost_rate_pay=1350.00 produces collection_usd = 10.2041 USD (= 10.0 / 0.980) within 0.01 USD tolerance
- Rejected and accepted margin changes both appear in audit log with correct previous_value and new_value fields
**Depends on:** 13.10-T02

### 13.10-T16 — Test rate quote TTL enforcement and rate-lock at commit (SEC-09 §5.1, threat T-03; HC-005, HC-008)  _(50 min)_
**Context:** RATE-04 rate quote TTL: default 60 seconds (aggregator-bound) or 300 seconds otherwise; configurable 60-1800 seconds. validUntil = quote_issued_at + ttl. On commit, all USD-pool values and derived rates are permanently locked; later treasury or margin changes never affect committed transactions. SEC-09 §5.1 covers amount tampering in transit (T-03). This ensures a forged or stale rate quote cannot be used to manipulate the amounts at commit time.
**Steps:** Call GET /v1/rates for P-TEST-002; note the validUntil timestamp in the response; wait until validUntil has passed (TTL expires); attempt POST /v1/payments using the expired quote reference; confirm RATE_QUOTE_EXPIRED error; Call GET /v1/rates; immediately commit POST /v1/payments within TTL; confirm payment is committed with the locked rates; After committing a payment, change treasury.usd_krw in the test DB from 1350 to 1400; retrieve the committed transaction via GET /v1/payments/{id}; confirm cost_rate_pay is still locked at 1350.00; After committing a payment, change m_a for the rule from 1.5% to 2.0%; retrieve the committed transaction; confirm collection_margin_usd is still locked at the original 1.5% computation; Attempt to POST /v1/payments with a manually crafted validUntil timestamp in the future in the request body; confirm server uses server-side TTL, not client-supplied expiry
**Deliverable:** Rate-lock and TTL test report with timestamps, rate values before/after config changes, and transaction record field comparisons
**Acceptance / logic checks:**
- POST /v1/payments submitted after validUntil returns HTTP 422 with error code RATE_QUOTE_EXPIRED; no prefunding deduction occurs
- Committed transaction GET /v1/payments/{id} shows cost_rate_pay = 1350.00 even after treasury.usd_krw is changed to 1400.00 in the DB
- Committed transaction shows collection_margin_usd computed at 1.5% even after rule m_a is changed to 2.0%
- Client-supplied validUntil in request body is ignored; server computes TTL from quote_issued_at + configured ttl
- Expired quote does not cause any partial state change in the prefunding balance or transaction table
**Depends on:** 13.10-T02

### 13.10-T17 — Test idempotency key enforcement under replay and concurrent conditions (SEC-09 §5.2; PA-007)  _(45 min)_
**Context:** SEC-09 §5.2: all payment-mutating endpoints require client-supplied Idempotency-Key (UUID v4); server caches response keyed by (partner_id, idempotency_key) for 24 hours; duplicate requests return original response without re-processing. This prevents duplicate payments from network retries. Also covers OWASP API6: unrestricted access to sensitive business flows. Distinct from nonce replay protection (X-Nonce, T04) which is a request-level control; idempotency is a business-level deduplication.
**Steps:** Submit POST /v1/payments for P-TEST-002 with Idempotency-Key=UUID-A; note the response and record collection_usd deducted; Re-submit the identical request with Idempotency-Key=UUID-A within 24 hours; confirm same response body and HTTP status returned; confirm prefunding balance unchanged (no second deduction); Submit a different POST /v1/payments for P-TEST-002 using the same Idempotency-Key=UUID-A but with different target_payout amount; confirm HTTP 422 with error code DUPLICATE_IDEMPOTENCY_KEY or the original response is returned unchanged; Submit two concurrent POST /v1/payments from P-TEST-002 using the same Idempotency-Key=UUID-B simultaneously; confirm only one transaction is created and prefunding deducted once; Submit POST /v1/payments without any Idempotency-Key header; confirm HTTP 400 or 422 requiring the key
**Deliverable:** Idempotency test report with prefunding balance log before and after each attempt, response comparison for duplicate vs unique keys, and concurrent request execution trace
**Acceptance / logic checks:**
- Second POST with same Idempotency-Key=UUID-A returns identical HTTP status and response body as first call; prefunding balance shows only one deduction
- POST with same Idempotency-Key=UUID-A but different target_payout returns HTTP 422 DUPLICATE_IDEMPOTENCY_KEY or original response, not a new transaction
- Two concurrent POSTs with same Idempotency-Key=UUID-B result in exactly one transaction record in the DB and one prefunding deduction
- POST without Idempotency-Key header returns HTTP 400 or 422 with a descriptive error; no transaction is created
**Depends on:** 13.10-T02

### 13.10-T18 — Test SAST, dependency scanning, and secret scanning CI pipeline gates (SEC-09 §5.6; checklist item 13)  _(45 min)_
**Context:** SEC-09 §5.6 CI pipeline must include: SAST (e.g. SonarQube, Semgrep) on every pull request; dependency scanning for known CVEs in third-party libraries - critical/high CVEs block merge; secret scanning (e.g. TruffleHog, git-secrets) on every commit - any finding blocks merge and triggers immediate rotation; container image scanning (e.g. Trivy) before deployment. SEC-09 §11 item 13: dependency and secret scanning in CI. This ticket verifies the gates are active, not bypassed, and correctly configured.
**Steps:** Review CI pipeline configuration (GitHub Actions / GitLab CI YAML) and confirm SAST job runs on every pull request; check that SAST failure blocks merge; Create a test branch with a known dummy secret string (e.g. a fake AWS key pattern) committed in a comment; confirm secret scanning job detects it and blocks merge; Introduce a test dependency with a known CRITICAL CVE (using a specific pinned old version of a library) in a test branch; confirm dependency scan detects it and blocks merge; Review the most recent Trivy or Grype container image scan report; confirm no CRITICAL findings are outstanding (all CRITICAL findings resolved or have documented waivers); Confirm SonarQube or Semgrep quality gate is set to fail on any high-severity security finding; check that the gate cannot be skipped without explicit approval; Verify that the CI pipeline does not use --no-verify or similar flags that bypass hooks
**Deliverable:** CI pipeline security gates review report with evidence (CI YAML excerpts, scan job outputs, PR blocking screenshots) for SAST, dependency, secret, and image scanning
**Acceptance / logic checks:**
- SAST job is present in CI config and set as a required check blocking PR merge; no --no-verify bypass is configured
- A PR containing a fake AWS secret string is blocked by the secret scanning job before merge; the CI log shows the detection
- A PR introducing a library with a pinned CRITICAL CVE is blocked by the dependency scan job
- Container image scan report shows zero CRITICAL-severity unresolved findings in the current main branch image
- SonarQube or Semgrep quality gate blocks merge on high-severity security finding; gate configuration is not overrideable without a documented approval step
**Depends on:** 13.10-T01

### 13.10-T19 — Test data residency and encryption at rest configuration (SEC-09 §2.6, §7.3; checklist item 9)  _(50 min)_
**Context:** SEC-09 §2.6: database AES-256 encryption at storage layer; audit log store AES-256 append-only with tamper-evident hash chain; SFTP staging area AES-256 volume encryption; secrets vault AES-256-GCM backed by HSM. SEC-09 §7.3: all production data (DB, logs, backups) must be hosted in Korean cloud region (e.g. AWS ap-northeast-2 Seoul). Assumption A6 from DAT-03: merchant.account_no stored AES-256 encrypted. Checklist item 9: data residency confirmed. This ticket verifies the infrastructure configuration, not runtime decryption.
**Steps:** Review AWS or cloud console configuration for the staging DB instance; confirm storage encryption is enabled (AES-256) and confirm the hosting region is ap-northeast-2 or equivalent Korean region; Review the SFTP batch file staging storage volume or S3 bucket configuration; confirm encryption at rest is enabled; Review the audit log store configuration (e.g. separate encrypted append-only store or DB table); confirm AES-256 encryption is configured; Review the secrets vault (Vault/Secrets Manager) configuration to confirm its own encryption is enabled and references an HSM or KMS; Check the backup configuration: confirm backups are AES-256 encrypted and the backup encryption key is stored separately from the data encryption key; Query the DB for a sample merchant.account_no value; confirm it is stored as an encrypted/hashed value, not plaintext
**Deliverable:** Data residency and encryption-at-rest audit report with cloud console screenshots or CLI output confirming encryption settings and region for DB, SFTP storage, audit store, vault, and backups
**Acceptance / logic checks:**
- DB instance storage encryption setting shows AES-256 enabled and region is ap-northeast-2 or documented Korean equivalent
- SFTP staging storage encryption at rest is enabled (confirmed via cloud console or infra-as-code config)
- Audit log store encryption is confirmed as AES-256; append-only constraint is verified by attempting a direct SQL UPDATE (rejected per T14 checks)
- Secrets vault encryption is enabled; HSM or KMS integration is configured (vault configuration file or cloud console shows KMS key reference)
- A merchant.account_no value retrieved directly from the DB is not plaintext legible without vault decryption
**Depends on:** 13.10-T01

### 13.10-T20 — Test log retention policies configuration (SEC-09 §6.2; checklist item 14)  _(40 min)_
**Context:** SEC-09 §6.2 retention requirements: transaction event trail 7 years (Korean financial record-keeping obligation); configuration change audit 7 years; API access logs 1 year; authentication events (login/logout/MFA) 3 years; SFTP batch file logs 7 years. SCH-06: batch files retained 90 days locally (configurable via file_retention_days). Checklist item 14: log retention policies configured (7-year for transactions).
**Steps:** Review the log management configuration (e.g. CloudWatch log group retention, S3 lifecycle policy, or log aggregation platform settings) for the transaction event trail log stream; confirm retention period >= 7 years (2555 days); Check the configuration audit log retention policy; confirm >= 7 years; Check API access log retention policy; confirm >= 1 year; Check authentication event log retention policy; confirm >= 3 years; Check SFTP batch file log retention policy; confirm >= 7 years; Review the batch file staging area lifecycle policy; confirm local file_retention_days >= 90
**Deliverable:** Log retention audit report with cloud console or configuration excerpts for each of the six log categories, each annotated with configured retention period and PASS/FAIL against SEC-09 §6.2 requirements
**Acceptance / logic checks:**
- Transaction event trail log retention is configured to at least 2555 days (7 years); any automatic deletion policy is set to >= 2555 days
- Configuration change audit log retention is >= 2555 days
- API access log retention is >= 365 days (1 year)
- Authentication event log retention is >= 1095 days (3 years)
- SFTP batch file log retention is >= 2555 days
- Batch file staging file_retention_days is >= 90
**Depends on:** 13.10-T01

### 13.10-T21 — Test PII minimisation: GMEPay+ never stores or logs end-user personal data (SEC-09 §7.1, §7.2)  _(45 min)_
**Context:** SEC-09 §7.1 PII inventory: GMEPay+ does NOT hold end-user name, national ID/passport, bank account, phone number, home address, or biometric data. These are partner-held. SEC-09 §7.2: log records must exclude end-user PII; if end-user data is passed accidentally in a request field, it must not be written to persistent logs. Only partner admin email and TOTP seed, merchant ID/name, transaction amounts, applied FX rates, and partner API key (hashed) are held.
**Steps:** Submit a POST /v1/payments request with extra JSON fields containing mock PII (e.g. end_user_name: 'John Doe', end_user_id: 'A12345678') in the request body; retrieve the stored transaction via GET /v1/payments/{id} and confirm no PII fields are stored or returned; Check API access logs after the above request to confirm no PII fields from the request body are written to the log; Submit a GET /v1/payments with a query parameter containing mock PII (e.g. ?end_user_name=John%20Doe); confirm it is not stored in access logs (query param is redacted for sensitive params per SEC-09 §6.4); Verify the transaction table schema in the DB: confirm there are no columns for end_user_name, national_id, passport, bank_account, phone, or address; Check the Partner Portal transaction detail view for a committed transaction; confirm it shows no end-user PII fields
**Deliverable:** PII minimisation test report confirming no end-user PII is persisted or logged, with DB schema screenshot, API response samples, and log excerpt review
**Acceptance / logic checks:**
- GET /v1/payments/{id} response contains no end_user_name, end_user_id, or any PII field passed in the original request
- API access log entry for the PII-laden request does not contain the values John Doe or A12345678 in any field
- Transaction DB table has no columns for end_user_name, national_id, passport_no, bank_account, phone, or home_address
- Partner Portal transaction detail page displays no PII fields beyond the documented allowed fields (txn_id, amounts, status, merchant_id, timestamp)
- PII-containing query parameter is absent from the access log or shown as [REDACTED]
**Depends on:** 13.10-T02

### 13.10-T22 — Test partner API credential issuance and revocation lifecycle (SEC-09 §9.1, §9.2)  _(50 min)_
**Context:** SEC-09 §9.1: partner API key + HMAC secret issued via Admin portal; plaintext shown once; hash stored by GMEPay+. Rotation: new credentials generated; overlap window default 7 days during which both old and new are accepted; old credentials auto-revoked after overlap. Emergency revocation: immediate; partner notified within 1 hour. Revocation: HTTP 401 CREDENTIALS_REVOKED on all subsequent requests. SEC-09 §2.3: HMAC secrets encrypted at rest in vault.
**Steps:** As Admin, generate new credentials for P-TEST-003 via Admin portal; verify plaintext secret is shown exactly once in the UI; Store the new API key and HMAC secret; attempt a GET /v1/rates with the new credentials; confirm HTTP 200; Attempt to retrieve the secret again from the Admin portal; confirm it is no longer displayed (only shows that credentials exist); Perform a credential rotation for P-TEST-003: generate new credentials with 7-day overlap; confirm both old and new credentials work during the overlap window; After manually expiring the overlap window (set end date to past in test DB), confirm old credentials return HTTP 401 CREDENTIALS_REVOKED while new credentials still work; Perform emergency revocation of the new P-TEST-003 credentials; confirm immediate rejection of API calls with HTTP 401 CREDENTIALS_REVOKED; confirm rotation event is in audit log
**Deliverable:** Credential lifecycle test report with Admin portal screenshots for issuance, rotation overlap, and revocation states, and API response evidence for each phase
**Acceptance / logic checks:**
- Newly issued secret is displayed exactly once in Admin portal; second visit to the credential detail page shows no secret value
- GET /v1/rates with new credentials returns HTTP 200
- Credentials after overlap window expiry return HTTP 401 CREDENTIALS_REVOKED; new credentials still return HTTP 200 during the same period
- Emergency revocation causes immediate HTTP 401 CREDENTIALS_REVOKED on the next API call (within the same test minute)
- Rotation event audit log entry contains actor_id, timestamp, event_type=CREDENTIAL_ROTATED, and overlap window end date
**Depends on:** 13.10-T02

### 13.10-T23 — Test BOK FX report data capture and lock at transaction commit (SEC-09 §8.1; checklist item 11)  _(50 min)_
**Context:** SEC-09 §8.1: FX1015 (inbound, payout to Korean merchant) requires offer_rate_coll = send_amount / (collection_usd - collection_margin_usd) (BOK FX1015 field #14), target_payout, payout_usd_cost, transaction commit timestamp, and partner_id - all captured and locked at transaction commit (step 6 of the 8-step event trail). FX1014 (outbound, Phase 2) fields must be present in schema even if not yet reported. Checklist item 11: BOK FX1015 data fields captured and locked at commit.
**Steps:** Commit an inbound payment for P-TEST-002: target_payout=13500 KRW, treasury.usd_krw=1350.00, treasury.usd_mnt=3500.00, m_a=0.015, m_b=0.010, service_charge=500 MNT; Query the bok_report_record table for the committed transaction; verify all FX1015 fields are present; Compute expected offer_rate_coll: send_amount=35897.44 MNT, collection_usd=10.2564, collection_margin_usd=0.1538; offer_rate_coll = 35897.44 / (10.2564 - 0.1538) = 3553.28 MNT/USD (approximate); verify DB value matches within 0.01; Verify transaction_committed event timestamp in event trail matches the timestamp in bok_report_record; Change treasury.usd_krw to 1400 after commit; verify bok_report_record still shows offer_rate_coll computed at 1350.00; Verify a domestic (same-currency KRW→KRW) transaction does NOT create a bok_report_record row
**Deliverable:** BOK FX data capture test report with bok_report_record row JSON for the test transaction, field-by-field verification against expected values, and confirmation that domestic transactions are exempt
**Acceptance / logic checks:**
- bok_report_record row exists for the inbound transaction and contains: offer_rate_coll (FX1015 field 14), target_payout=13500, payout_usd_cost=10.0000 USD (within 0.01), partner_id, and transaction_committed timestamp
- offer_rate_coll in bok_report_record matches the formula-derived value 3553.28 MNT/USD within 0.01
- Changing treasury rates after commit does not alter the locked offer_rate_coll in bok_report_record
- A domestic KRW→KRW transaction (same-currency short-circuit) has no corresponding bok_report_record row
- FX1014 fields (collection_ccy, collection_amount, send_amount, collection_usd, partner_id, transaction_timestamp) are present as columns in the transaction schema even though Phase 1 does not generate FX1014 reports
**Depends on:** 13.10-T02

### 13.10-T24 — Test SFTP key rotation procedure and key not written to disk (SEC-09 §9.3; §2.5)  _(50 min)_
**Context:** SEC-09 §9.3 SFTP key rotation: GMEPay+ generates new Ed25519 key pair in secrets vault; Ops extracts public key from Admin portal; registers with KFTC; KFTC confirms; Ops activates new key (vault pointer updated); old key revoked; rotation event audit-logged. SEC-09 §2.5: private key stored in vault; never written to disk outside the vault. Key rotation requires minimum 5 business days lead time with KFTC (staging assumption: use mock KFTC confirmation). This ticket covers the staging simulation of the rotation procedure.
**Steps:** Trigger new Ed25519 key pair generation from Admin portal SFTP Settings page; confirm the UI shows the new public key for registration; Verify the private key is stored in the vault (check vault audit log for a new key write) and NOT written to any application server file system path; Simulate KFTC confirmation by updating the staging SFTP mock to accept the new public key; Activate the new key via Admin portal; confirm the SFTP client uses the new key for the next batch transmission to the mock SFTP server; Revoke the old key via Admin portal; confirm the mock SFTP server configured with only the old key now rejects the connection; Verify rotation event is in audit log with actor_id, timestamp, event_type=SFTP_KEY_ROTATED
**Deliverable:** SFTP key rotation test report with vault audit log excerpt, SFTP connection test results before/after rotation, and audit log entry for the rotation event
**Acceptance / logic checks:**
- New Ed25519 public key is visible in Admin portal after generation; private key is accessible only via vault audit log (vault shows a new key write)
- No private key file (id_ed25519 or equivalent) exists in any application server file system path after key generation
- After activation, the SFTP batch job successfully connects to the mock SFTP server using the new key
- After old key revocation, an SFTP mock configured with only the old key refuses the connection
- SFTP_KEY_ROTATED event in audit log contains actor_id, timestamp, and event_type
**Depends on:** 13.10-T02

### 13.10-T25 — Test incident response alerting triggers and SIEM log forwarding (SEC-09 §10.4; OPS-13)  _(55 min)_
**Context:** SEC-09 §10.4 security alert triggers: failed auth > 20 per IP per 10 minutes = P3; any REPLAY_DETECTED event = P2; audit log hash chain break = P1; prefunding balance zero = P2; unusual transaction velocity > 3x 7-day average per 5 minutes = P3; SFTP delivery failure past deadline = P2; secrets vault access anomaly = P1. SEC-09 §6.5: all security-relevant log streams forwarded to SIEM via JSON over secure transport. This ticket exercises the alerting pipeline, not full IR response.
**Steps:** Send 21 failed auth requests from the same test IP within 10 minutes to POST /v1/auth/token; confirm a P3 alert is triggered in the monitoring system (OPS-13 alerting endpoint or SIEM); Send a replayed payment request (duplicate X-Nonce within 600 seconds) captured from T04; confirm REPLAY_DETECTED event appears in the security log and triggers a P2 alert; Set P-TEST-002 prefunding balance to 0 in test DB and trigger a payment attempt; confirm P2 alert (balance zero) fires; Check the SIEM or log aggregation platform to confirm authentication event logs and audit log entries are present in JSON format; Break the audit log hash chain (from T14); confirm the P1 alert fires on the next integrity check job run; Verify that sensitive fields (API key value, HMAC secret) are absent from all SIEM-forwarded log entries
**Deliverable:** Alerting pipeline test report with evidence of alert triggers for failed auth (P3), REPLAY_DETECTED (P2), zero prefunding (P2), and audit hash chain break (P1), plus SIEM log format sample
**Acceptance / logic checks:**
- 21 failed auth requests from same IP within 10 minutes produce a P3 alert notification in the monitoring system within 1 minute
- REPLAY_DETECTED event triggers a P2 alert; the alert payload includes the partner_id and timestamp of the duplicate nonce
- Zero prefunding balance on P-TEST-002 triggers a P2 alert that includes partner_id=P-TEST-002
- Audit log hash chain break (manually introduced) triggers a P1 critical alert on the next integrity check job run
- SIEM-forwarded log entries for the above events are in JSON format and contain no API key or HMAC secret values
**Depends on:** 13.10-T02, 13.10-T14

### 13.10-T26 — Execute external penetration test - reconnaissance and discovery phase  _(60 min)_
**Context:** SEC-09 §5.7: external pen test required before Phase 3 go-live (Aug 2026). This is the first phase of the structured external engagement: passive and active reconnaissance to enumerate attack surface, identify exposed endpoints, check for information leakage in headers/errors, and produce a finding list for deeper testing. Scope is defined in 13.10-T01 scope document. Target: staging environment. Tester: external security firm. GMEPay+ surfaces: API Gateway (partner API), Admin portal, Partner portal, SFTP client (outbound only, no inbound from internet).
**Steps:** External tester performs passive recon: DNS enumeration, certificate transparency log review, Shodan/FOFA checks for staging hostnames; Active scan: port scan on staging API Gateway and portal hosts; identify all open ports and services; HTTP header analysis: check for Server, X-Powered-By, X-AspNet-Version, or other technology-disclosure headers on all three surfaces; Error enumeration: trigger 400, 401, 403, 404, 500 errors on each surface; document any stack trace, internal path, or version information disclosed; Endpoint discovery: run a wordlist-based directory scan against the API Gateway; document any unexpected paths that return 200; Spider/crawl Partner Portal and Admin portal login pages for hidden fields, comments, or version strings
**Deliverable:** Pen-test reconnaissance report section: enumerated open ports/services, header analysis findings, error disclosure findings, and unexpected endpoint discovery results, with CVSS severity rating for each finding
**Acceptance / logic checks:**
- Report documents all open ports on staging hosts; unexpected open ports (beyond 443) are flagged as findings
- No Server or X-Powered-By header discloses framework version or runtime version on any of the three surfaces
- HTTP 500 error responses contain no stack trace, DB connection string, or internal file path
- Directory scan reveals no unexpected paths (e.g. /admin, /debug, /metrics, /actuator) returning HTTP 200 without authentication
- Reconnaissance findings are each assigned a CVSS 3.1 base score and mapped to a SEC-09 STRIDE threat or OWASP API Top 10 category
**Depends on:** 13.10-T01, 13.10-T02

### 13.10-T27 — Execute external penetration test - authentication and session exploitation phase  _(60 min)_
**Context:** Continuation of external pen test (SEC-09 §5.7). This phase covers deep exploitation attempts on the authentication mechanisms identified in SEC-09 §3.1, §3.2, §3.3: HMAC bypass, session fixation, token forging, MFA bypass on Admin/Partner portals, brute force resistance. Tester has valid Partner API credentials (P-TEST-001) to test from authenticated context as well. All findings classified by CVSS 3.1.
**Steps:** Test HMAC signature bypass: attempt to call POST /v1/payments without X-Signature header; with forged HMAC; with HMAC computed from wrong key; Test JWT/session token manipulation: decode Admin or Partner Portal session token; attempt to modify role claim; re-sign with known-weak key or blank secret; submit modified token; Test MFA bypass on Admin portal: enumerate common bypass techniques (response manipulation, backup code guessing, cookie replay from pre-MFA state); Test session fixation: set a known session ID before login; complete login; check if the session ID remains the same post-authentication; Test privilege escalation: login as Partner Viewer; attempt HTTP requests to Partner Admin-only API routes (e.g. user management) using the Viewer session token; Test brute force: measure account lockout triggers (should be at 5 attempts per §3.2 for Partner Portal)
**Deliverable:** Pen-test authentication exploitation report section: findings for each test case with reproduction steps, CVSS score, and evidence (request/response snippets)
**Acceptance / logic checks:**
- Every HMAC bypass technique returns HTTP 401; no partial processing occurs for requests with invalid or missing signatures
- Session token role-claim modification is rejected (signature invalid or claim mismatch error); no elevated access is granted
- No MFA bypass technique succeeds; pre-MFA session state cannot be used to access authenticated resources
- Session ID changes after successful login (session fixation mitigation confirmed)
- Partner Viewer cannot access Partner Admin routes; HTTP 403 returned with no data leakage
- Account locks after exactly 5 failed login attempts; lock is confirmed by attempting correct credentials on the 6th attempt
**Depends on:** 13.10-T26

### 13.10-T28 — Execute external penetration test - business logic and financial flow exploitation phase  _(60 min)_
**Context:** Continuation of external pen test (SEC-09 §5.7). This phase targets the financial-specific attack surfaces unique to GMEPay+: prefunding manipulation (T-04), amount tampering (T-03), rate injection (T-07), IDOR on financial records (T-05), and ZeroPay batch file manipulation (T-06). Tester uses both P-TEST-001 (LOCAL) and P-TEST-002 (OVERSEAS) credentials. Goal: find any path to misdirect funds, bypass prefunding gates, or access another partner's financial data.
**Steps:** Attempt to manipulate target_payout via HTTP request modification (e.g. intercept and modify the signed request body after HMAC is computed — should fail as HMAC covers body hash); Attempt parameter pollution: submit POST /v1/payments with duplicate target_payout fields (e.g. target_payout[]=1 and target_payout[]=9999999); Test mass assignment: submit POST /v1/payments with extra fields (collection_usd, payout_usd_cost, m_a, m_b) and verify server ignores them in the computation; Attempt IDOR on settlement endpoints: if any Admin API exposes settlement batch data, test if Partner API token can access it; Test CPM QR token reuse: generate a CPM QR token for P-TEST-002; attempt to commit the same QR token twice; confirm only one transaction and one prefunding deduction; Attempt to craft a malformed ZeroPay-format batch file and submit it to the GMEPay+ SFTP ingest endpoint (if exposed); test for parsing vulnerabilities (XML/CSV injection, buffer overflow in fixed-width fields)
**Deliverable:** Pen-test business logic exploitation report section: findings for financial flow attack attempts, with CVSS scores, reproduction steps, and evidence
**Acceptance / logic checks:**
- HMAC body hash covers target_payout; modifying body after HMAC computation causes signature verification failure and HTTP 401
- Parameter pollution with duplicate target_payout fields returns HTTP 422 or uses only the first value; no ambiguous amount processing
- Mass assignment of collection_usd, m_a, m_b in request body is silently ignored; server-computed values are used
- Settlement batch admin API is not accessible via Partner API Bearer token
- CPM QR token committed twice results in exactly one transaction and one prefunding deduction; second commit returns DUPLICATE_IDEMPOTENCY_KEY or a rejected-already-processed error
- Malformed ZeroPay batch file causes a parsing error logged to audit; no server crash, code execution, or data corruption occurs
**Depends on:** 13.10-T26

### 13.10-T29 — Execute external penetration test - infrastructure and SFTP perimeter phase  _(60 min)_
**Context:** Continuation of external pen test (SEC-09 §5.7). This phase covers network-layer and infrastructure security: SSRF via webhook URL registration (OWASP API7), internal service exposure, secrets in environment variables or config, container security, and the ZeroPay SFTP client perimeter. The Scheme Integration Zone allows restricted egress only (SEC-09 §2.1). Admin and Data zones have no direct internet access.
**Steps:** Test SSRF via webhook URL: register a partner webhook URL pointing to http://169.254.169.254/latest/meta-data/ (AWS metadata); confirm request is blocked at registration or delivery; Test internal network access via SSRF: attempt webhook URLs pointing to http://10.0.0.0/8 internal ranges; confirm blocked; Check for exposed internal services: probe common internal ports (8080, 8443, 9000, 9090, 3000, 5432) on the API Gateway and portal hosts from the external perspective; confirm no internal service ports are reachable; Test secrets in environment: attempt to access common misconfiguration paths (/.env, /config.json, /application.properties, /actuator/env) on API Gateway and portal hostnames; Test container escape or privilege escalation if remote code execution is achieved (documented theoretical finding if no RCE path exists); Review network security group / firewall rules for the staging environment to confirm Scheme Integration Zone has egress-only access to ZeroPay SFTP and no inbound internet access
**Deliverable:** Pen-test infrastructure exploitation report section: SSRF test results, internal service exposure findings, secrets-in-environment findings, and network zone boundary review
**Acceptance / logic checks:**
- Webhook URL pointing to AWS metadata endpoint (169.254.169.254) is rejected at registration or delivery with a validation error, not a successful fetch
- Webhook URL pointing to any RFC-1918 private IP range is rejected at registration
- No internal service ports (8080, 5432, 9090, etc.) are accessible from the external test host on staging
- /.env, /config.json, /application.properties, and /actuator/env paths on all three surfaces return HTTP 404 or 403 with no credential data
- Scheme Integration Zone security group review confirms inbound rules allow no connections from the public internet; only outbound SFTP to the ZeroPay host is permitted
**Depends on:** 13.10-T26

### 13.10-T30 — Triage and severity-classify all pen-test findings (SEC-09 §10.1; SEC-09 §5.7)  _(60 min)_
**Context:** SEC-09 §10.1 incident severity classification: P1 Critical (active exploitation, funds at risk, data breach), P2 High (potential exploitation, significant data exposure), P3 Medium (control degraded, no immediate exploitation), P4 Low (minor policy violation). SEC-09 §5.7: all CRITICAL and HIGH findings must be remediated before go-live; remaining findings accepted with documented rationale. QA-12 §2.2.7 security testing exit criteria: all CRITICAL and HIGH findings remediated. This ticket consolidates findings from T03-T29 into a master finding register.
**Steps:** Collect all raw findings from pen-test phases T03 to T29 and internal security tests T03 to T25; Assign each finding a CVSS 3.1 base score and map to SEC-09 severity (P1=CVSS>=9, P2=CVSS 7-8.9, P3=CVSS 4-6.9, P4=CVSS<4); Map each finding to the corresponding SEC-09 go-live checklist item (1-15) or STRIDE threat (T-01 to T-10); Classify each finding as: Confirmed Vulnerability, Informational, or False Positive; Prioritise: CRITICAL/P1 findings must be fixed before Phase 3 (Oct 10 2026); HIGH/P2 before Phase 3; MEDIUM/P3 with documented rationale; LOW/P4 tracked in backlog; Produce the master finding register (CSV/spreadsheet) with columns: finding_id, title, severity, CVSS_score, affected_surface, SEC09_reference, status, assignee, due_date
**Deliverable:** Master pen-test finding register (pentest-findings-register.csv) with all findings from T03-T29, CVSS scores, severity classifications, and remediation assignments
**Acceptance / logic checks:**
- Every finding from phases T03-T29 appears in the register with a unique finding_id
- Each finding has a CVSS 3.1 base score and a P1/P2/P3/P4 severity classification
- Every P1 and P2 finding has an assignee and a due_date before Oct 10 2026 (Phase 3 go-live)
- Each finding is mapped to at least one SEC-09 checklist item or STRIDE threat
- Register is reviewed and signed off by GME security contact before remediation begins
**Depends on:** 13.10-T03, 13.10-T04, 13.10-T05, 13.10-T06, 13.10-T07, 13.10-T08, 13.10-T09, 13.10-T10, 13.10-T11, 13.10-T12, 13.10-T13, 13.10-T14, 13.10-T15, 13.10-T16, 13.10-T17, 13.10-T18, 13.10-T19, 13.10-T20, 13.10-T21, 13.10-T22, 13.10-T23, 13.10-T24, 13.10-T25, 13.10-T26, 13.10-T27, 13.10-T28, 13.10-T29

### 13.10-T31 — Remediate CRITICAL (P1) pen-test findings  _(60 min)_
**Context:** SEC-09 §5.7 and QA-12 §2.2.7: all CRITICAL and HIGH findings must be remediated before Phase 3 go-live (Oct 10 2026). P1 findings (CVSS >= 9.0) represent active exploitation risk or fund/data breach potential (e.g. HMAC bypass, IDOR leaking financial data, unauthenticated access to payment API, audit log corruption). Each P1 finding from the master register (13.10-T30) requires a code fix, config change, or infrastructure change; the fix must be verified by the pen-test team. This ticket covers the remediation workflow for P1 findings only.
**Steps:** Pull the list of P1 findings from the master register (13.10-T30); assign each to a developer with a 48-hour SLA per SEC-09 §5.7 (critical vulnerability hotfix within 48 hours); For each P1 finding, implement the code or configuration fix in a dedicated remediation branch; Write a regression test that reproduces the finding and confirms the fix prevents exploitation; Merge the fix through the standard CI pipeline (SAST + dependency scan + secret scan + container scan must all pass); Notify the pen-test team to re-test each P1 finding in staging and confirm closure; Update the master finding register: set status=CLOSED for each verified fix; document the fix commit SHA and re-test date
**Deliverable:** All P1 findings from the master register have status=CLOSED with a linked fix commit SHA, regression test, and pen-test team closure confirmation
**Acceptance / logic checks:**
- Every P1 finding in the master register has status=CLOSED before Phase 3 go-live (Oct 10 2026)
- Each closed P1 finding has a linked regression test that was green on merge
- Each closed P1 finding has a pen-test team re-test confirmation entry (tester name, re-test date, result=CLOSED)
- CI pipeline for each remediation branch passed all security gates (SAST, dependency scan, secret scan, container scan) before merge
- Finding register shows zero open P1 findings at the time of pen-test report sign-off
**Depends on:** 13.10-T30

### 13.10-T32 — Remediate HIGH (P2) pen-test findings  _(60 min)_
**Context:** SEC-09 §5.7 and QA-12 §2.2.7: all HIGH findings must be remediated before Phase 3 go-live. P2 findings (CVSS 7.0-8.9): potential exploitation or significant data exposure (e.g. MFA bypass, partial IDOR, rate limiting misconfiguration, audit log gaps, weak session controls). P2 SLA: remediation before Phase 3 gate. Same workflow as 13.10-T31 but for P2 findings. Each fix requires regression test and pen-test team re-confirmation.
**Steps:** Pull the list of P2 findings from the master register; assign each to a developer with a due date before Phase 3 go-live; For each P2 finding, implement the fix in a remediation branch; Write a regression test that reproduces the finding and confirms prevention; Merge through CI pipeline (all security gates must pass); Notify pen-test team to re-test in staging; Update finding register with status=CLOSED, fix commit SHA, and re-test confirmation
**Deliverable:** All P2 findings from the master register have status=CLOSED with linked fix commit SHA, regression test, and pen-test team confirmation
**Acceptance / logic checks:**
- Every P2 finding in the master register has status=CLOSED before Phase 3 go-live
- Each closed P2 finding has a linked regression test (green on merge)
- Each closed P2 finding has a pen-test team re-test entry confirming closure
- CI pipeline for each remediation branch passed all security gates
- Finding register shows zero open P2 findings at pen-test report sign-off
**Depends on:** 13.10-T30

### 13.10-T33 — Document accepted MEDIUM and LOW findings with rationale (SEC-09 §5.7)  _(45 min)_
**Context:** SEC-09 §5.7: remaining findings (P3 Medium, P4 Low) that are not remediated before Phase 3 must be accepted with documented rationale. CVSS 4.0-6.9 = P3, CVSS < 4.0 = P4. Acceptance requires: description of finding, why it is not remediated before go-live, compensating control (if any), planned remediation timeline or backlog reference, and sign-off by GME security contact. These become tracked backlog items.
**Steps:** Pull the list of P3 and P4 findings from the master register; For each P3 finding, prepare an acceptance rationale: describe the finding, explain why it is not remediated pre-go-live (resource constraint, low exploitability, compensating control), and identify the compensating control; For each P4 finding, prepare a brief acceptance note; Add all accepted findings to the engineering backlog with priority labels; Obtain GME security contact sign-off on each accepted P3/P4 finding; Update master finding register with status=ACCEPTED, acceptance_rationale, compensating_control, and planned_remediation_date for each
**Deliverable:** Accepted findings section of the pen-test report with all P3/P4 findings documented with rationale, compensating controls, and GME security sign-off; backlog items created for each
**Acceptance / logic checks:**
- Every P3 and P4 finding has an acceptance_rationale entry in the master finding register
- Each accepted P3 finding identifies at least one compensating control or explains why no compensating control exists
- Each accepted finding has a planned_remediation_date or is explicitly marked as backlog-only with justification
- GME security contact signature or written approval is recorded for each accepted P3 finding
- All accepted findings appear as backlog items with priority labels matching their P3/P4 severity
**Depends on:** 13.10-T30

### 13.10-T34 — Perform pen-test retest: verify all P1 and P2 remediations are effective  _(60 min)_
**Context:** After P1 (13.10-T31) and P2 (13.10-T32) remediations are merged and deployed to staging, the external pen-test team must re-execute the specific test cases that triggered each finding to confirm the fix is effective and has not introduced a regression. SEC-09 §5.7: findings must be remediated before launch. QA-12 §2.2.7: all CRITICAL and HIGH findings remediated. This is the formal re-test phase; findings that fail retest revert to OPEN and require a new fix cycle.
**Steps:** Deploy all P1 and P2 remediation commits to the staging pen-test environment; Pen-test team re-executes the exact reproduction steps documented in each P1/P2 finding; For each finding, tester records: retest date, tester name, reproduction attempt result (FAIL=still vulnerable / PASS=fixed), and CVSS re-assessment; Any finding that retests as FAIL reverts to OPEN status in the master register and triggers a new fix cycle per 13.10-T31/T32; Run the full internal regression test suite to confirm no new regressions were introduced by remediation commits; Update master finding register with retest outcomes
**Deliverable:** Pen-test retest report section listing each retested P1/P2 finding, retest result (PASS/FAIL), and updated finding register showing zero open P1/P2 findings
**Acceptance / logic checks:**
- Every P1 finding retests as PASS (no longer exploitable after fix)
- Every P2 finding retests as PASS
- No finding that retested as PASS reverts to FAIL in a subsequent regression run
- Full regression test suite is green after all remediation commits are merged to the staging branch
- Master finding register shows zero findings with status=OPEN at a severity of P1 or P2 after retest
**Depends on:** 13.10-T31, 13.10-T32

### 13.10-T35 — Verify all 15 SEC-09 go-live checklist items are satisfied  _(55 min)_
**Context:** SEC-09 §11 defines 15 controls that must be verified before Phase 3 go-live (Oct 10 2026). This ticket performs a final structured walkthrough of all 15 items, collecting evidence from prior tickets. Items: 1=TLS (T03), 2=HMAC replay (T04), 3=RBAC/IDOR (T05/T06), 4=audit log (T14), 5=secrets hashed (T09), 6=SFTP host key pinning (T13), 7=secrets vault operational (T09/T19), 8=MFA admin (T07), 9=data residency (T19), 10=PIPA policy (compliance workstream), 11=BOK FX1015 locked (T23), 12=pen-test report (T36), 13=CI dependency/secret scan (T18), 14=log retention (T20), 15=incident response contacts (OPS-13).
**Steps:** Create a verification matrix with all 15 checklist items; link each to the evidence ticket/artifact from this work-package or from other workstreams; For each item, record: evidence artifact, test date, result (PASS/FAIL/PENDING), and responsible party; Items 10 (PIPA policy) and 15 (incident response contacts) are owned by GME Legal/Compliance and OPS-13; confirm their status with the relevant team leads; Items 12 (pen-test report) is covered by 13.10-T36; mark as PENDING until T36 is complete; Produce the completed verification matrix as a section of the pen-test report; Obtain GME sign-off on the completed verification matrix
**Deliverable:** SEC-09 go-live checklist verification matrix document with all 15 items, evidence references, test results, and GME sign-off
**Acceptance / logic checks:**
- All 15 items have a result of PASS, ACCEPTED (with rationale), or PENDING (with owner and target date) - no items are blank or unknown
- Items 1-9 and 11, 13, 14 are all PASS based on evidence from this work-package
- Item 10 (PIPA policy) has confirmation from GME Legal/Compliance with a date
- Item 15 (incident response contacts) has confirmation from OPS-13 workstream
- Matrix is signed off by GME security contact and product owner before Phase 3 gate
**Depends on:** 13.10-T03, 13.10-T04, 13.10-T05, 13.10-T06, 13.10-T07, 13.10-T08, 13.10-T09, 13.10-T13, 13.10-T14, 13.10-T18, 13.10-T19, 13.10-T20, 13.10-T23, 13.10-T34

### 13.10-T36 — Produce final signed pen-test report  _(60 min)_
**Context:** SEC-09 §5.7: external pen test required before Phase 3 go-live (Aug 2026 per original plan, Oct 10 2026 per PM-14); findings must be remediated before launch. The deliverable for WBS 13.10 is a signed Pen-test Report. The report consolidates: executive summary, scope and methodology, all findings from T26-T29 and T03-T25 (internal), remediation status (T31-T34), accepted risk register (T33), and the SEC-09 go-live checklist verification matrix (T35). The report must be produced by the external pen-test firm and countersigned by GME security contact.
**Steps:** External pen-test firm compiles the report from all phases: recon (T26), auth (T27), business logic (T28), infrastructure (T29), and internal tests (T03-T25); Include executive summary with risk posture, total findings by severity (P1/P2/P3/P4), and remediation closure rate; Include full finding register with all findings at CLOSED, ACCEPTED, or OPEN status; confirm zero OPEN P1/P2 findings; Include retest evidence summary from T34; Include accepted risk register from T33 with rationale and GME sign-off evidence; Include the SEC-09 go-live checklist verification matrix from T35 with all items PASS or ACCEPTED; Obtain signature from the external pen-test firm lead tester and from GME security contact (CISO or designated security owner)
**Deliverable:** Signed Pen-test Report document (PDF) stored at /docs/security/pentest-report-phase3.pdf with all required sections, finding register, retest evidence, accepted risk register, checklist matrix, and dual signatures
**Acceptance / logic checks:**
- Report contains an executive summary quantifying total findings by P1/P2/P3/P4 with remediation closure rate expressed as a percentage
- Finding register in the report shows zero findings at OPEN status with P1 or P2 severity
- Report includes a retest evidence table confirming PASS result for every previously P1 and P2 finding
- Accepted risk register section documents every P3 and P4 accepted finding with rationale and GME sign-off date
- SEC-09 go-live checklist section shows all 15 items at PASS or ACCEPTED with no blank entries
- Report PDF is signed by the external pen-test firm lead tester and countersigned by GME security contact; both signatures include date and role
**Depends on:** 13.10-T33, 13.10-T34, 13.10-T35
