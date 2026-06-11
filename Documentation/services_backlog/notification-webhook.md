# notification-webhook  (backend)

**Scope:** Signed webhooks, retry/DLQ, email/SMS

**Owned WBS work-packages:** 8.6  ·  **Tickets:** 26  ·  **Est:** 17.2h

## Service contract (MSA: own DB, API-only communication)

- **Datastore (owned by this service):** delivery log + DLQ
- **APIs / events I EXPOSE:** webhook config API
- **APIs / events I CONSUME:** events payment.*, settlement.completed, prefunding.low (async)
- **Integration rule:** never read another service's database or import its private entities — call its API or consume its event; stub consumed services with WireMock in tests.

> Self-contained backlog for this service. Build it as its own repo/module with its own DB + Flyway migrations, against the `shared-libs` contracts (lib-money / lib-errors / lib-events / lib-api-contracts only). Each ticket has a deliverable + acceptance checks.


## WBS 8.6 — Webhooks: events, signing, retry
### 8.6-T01 — Create Flyway migration V30__webhook_outbox.sql in notification-webhook service  _(30 min)_
**Context:** The notification-webhook service (module: services/notification-webhook) uses PostgreSQL 16 for the transactional Outbox pattern. Domain events are written atomically with the business transaction, then polled and dispatched. We need: outbox_event table (id BIGSERIAL PK, aggregate_type VARCHAR(50), aggregate_id VARCHAR(100), event_type VARCHAR(100), payload JSONB NOT NULL, status VARCHAR(20) DEFAULT pending CHECK (status IN (pending,processing,delivered,failed,dlq)), attempts INT DEFAULT 0, next_attempt_at TIMESTAMPTZ, created_at TIMESTAMPTZ DEFAULT now(), processed_at TIMESTAMPTZ). Also webhook_delivery_attempt table (id BIGSERIAL PK, outbox_event_id BIGINT FK -> outbox_event, partner_id BIGINT, attempt_number INT, http_status INT, response_body TEXT, attempted_at TIMESTAMPTZ DEFAULT now(), duration_ms INT). Index: outbox_event(status, next_attempt_at) WHERE status IN (pending,processing).
**Steps:** Create services/notification-webhook/src/main/resources/db/migration/V30__webhook_outbox.sql; Add CREATE TABLE outbox_event with all columns and CHECK constraint on status; Add CREATE TABLE webhook_delivery_attempt with FK to outbox_event; Add partial index idx_outbox_event_pending on outbox_event(status, next_attempt_at) WHERE status IN (pending, processing); Add CREATE TABLE webhook_dlq (id BIGSERIAL PK, outbox_event_id BIGINT FK, partner_id BIGINT, event_type VARCHAR(100), payload JSONB, failure_reason TEXT, dlq_at TIMESTAMPTZ DEFAULT now(), resolved_at TIMESTAMPTZ, resolved_by VARCHAR(100))
**Deliverable:** services/notification-webhook/src/main/resources/db/migration/V30__webhook_outbox.sql
**Acceptance / logic checks:**
- Flyway applies migration without error on clean Testcontainers Postgres 16 instance
- outbox_event.status CHECK constraint rejects any value outside (pending, processing, delivered, failed, dlq)
- Partial index exists in pg_indexes for outbox_event WHERE status IN (pending, processing)
- webhook_delivery_attempt.outbox_event_id FK references outbox_event(id) with ON DELETE CASCADE
- webhook_dlq table has NOT NULL on outbox_event_id and event_type

### 8.6-T02 — Create Flyway migration V31__partner_webhook_config.sql for partner_webhook table  _(25 min)_
**Context:** The partner_webhook table stores per-partner webhook endpoint configuration per DAT-03 and API-05: id BIGINT PK, partner_id BIGINT FK -> partner NOT NULL, webhook_url VARCHAR(512) NOT NULL, event_types TEXT (comma-separated, null = all events), signing_secret_hash VARCHAR(128) NOT NULL (Argon2 hash of HMAC signing secret), is_active BOOLEAN NOT NULL DEFAULT true, created_at/updated_at TIMESTAMPTZ, created_by/updated_by VARCHAR(100). Webhook URLs must be HTTPS only enforced via CHECK constraint. UNIQUE(partner_id, webhook_url). Partial index for active endpoints. The signing secret plaintext is never stored in DB - only its Argon2 hash; plaintext lives in Vault.
**Steps:** Create services/notification-webhook/src/main/resources/db/migration/V31__partner_webhook_config.sql; Add CREATE TABLE partner_webhook with all columns and CHECK (webhook_url LIKE https://%) and UNIQUE(partner_id, webhook_url); Add index idx_partner_webhook_partner_active on partner_webhook(partner_id) WHERE is_active = true; Add COMMENT ON COLUMN partner_webhook.signing_secret_hash explaining Argon2 hash only - never plaintext
**Deliverable:** services/notification-webhook/src/main/resources/db/migration/V31__partner_webhook_config.sql
**Acceptance / logic checks:**
- CHECK constraint rejects http:// and ftp:// URLs
- UNIQUE(partner_id, webhook_url) prevents duplicate registration
- Partial index for is_active=true exists in pg_indexes
- signing_secret_hash column length is 128
- FK to partner table enforced: inserting orphan partner_id fails with FK violation
**Depends on:** 8.6-T01

### 8.6-T03 — Define WebhookEvent envelope and payload Java records in lib-events module  _(40 min)_
**Context:** lib-events (Gradle module: lib-events) holds all shared domain event schemas. For WBS 8.6 define: WebhookEventEnvelope record (event_id String, event_type String, created_at Instant, partner_id String, data Object) with @JsonInclude(NON_NULL). Five payload records: PaymentApprovedPayload (payment_id, partner_txn_ref, scheme_txn_id, merchant_id, merchant_name, direction, scheme_id, target_payout BigDecimal, payout_currency, offer_rate BigDecimal, collection_amount BigDecimal, collection_currency, service_charge BigDecimal, service_charge_currency, prefund_deducted_usd BigDecimal, approved_at Instant), PaymentPendingDebitPayload, PaymentFailedPayload (payment_id, partner_txn_ref, failure_code, failure_message, prefund_returned_usd BigDecimal, failed_at Instant), PaymentCancelledPayload, SettlementCompletedPayload. All BigDecimal fields use @JsonSerialize(using=ToStringSerializer.class). WebhookEventType enum with PAYMENT_APPROVED etc. and getEventTypeString() returning dot-notation.
**Steps:** Create lib-events/src/main/java/com/gme/pay/events/webhook/WebhookEventEnvelope.java as Java record with Jackson annotations; Create WebhookEventType.java enum with getEventTypeString() returning payment.approved etc.; Create all five payload Java records in lib-events/src/main/java/com/gme/pay/events/webhook/payload/; Annotate all BigDecimal fields with @JsonSerialize(using=ToStringSerializer.class); Add unit test WebhookEventEnvelopeSerializationTest verifying round-trip Jackson serialization preserves BigDecimal precision
**Deliverable:** lib-events/src/main/java/com/gme/pay/events/webhook/WebhookEventEnvelope.java and five payload classes
**Acceptance / logic checks:**
- Round-trip Jackson serialize/deserialize of PaymentApprovedPayload preserves BigDecimal to 8 decimal places
- WebhookEventType.PAYMENT_APPROVED.getEventTypeString() returns exactly payment.approved
- PaymentFailedPayload with null prefund_returned_usd serializes to absent field via NON_NULL
- All payload records are immutable (no setters, only all-args constructor via record)
- Unit test passes with JUnit 5 and no Spring context

### 8.6-T04 — Define OutboxEvent JPA entity and OutboxEventRepository in notification-webhook service  _(45 min)_
**Context:** notification-webhook (module: services/notification-webhook) uses Spring Data JPA backed by PostgreSQL 16. OutboxEvent @Entity maps to outbox_event table from V30 migration. Fields: id Long, aggregateType String, aggregateId String, eventType String, payload String (JSONB stored as text), status OutboxEventStatus enum (PENDING,PROCESSING,DELIVERED,FAILED,DLQ) with @Enumerated(STRING), attempts int, nextAttemptAt Instant, createdAt Instant, processedAt Instant. OutboxEventRepository extends JpaRepository. Add @Lock(PESSIMISTIC_WRITE) @QueryHints(@QueryHint(name=javax.persistence.lock.timeout, value=3000)) @Query to find next batch of up to 50 PENDING rows with nextAttemptAt <= :now SKIP LOCKED equivalent. Also add WebhookDeliveryAttempt and WebhookDlqEvent @Entity classes.
**Steps:** Create services/notification-webhook/src/main/java/com/gme/pay/notify/outbox/OutboxEvent.java @Entity @Table(name=outbox_event) with all fields; Create OutboxEventStatus.java enum with values PENDING, PROCESSING, DELIVERED, FAILED, DLQ; Create OutboxEventRepository.java extending JpaRepository with @Lock(PESSIMISTIC_WRITE) findNextBatch query; Create WebhookDeliveryAttempt.java @Entity mapping webhook_delivery_attempt table; Create WebhookDlqEvent.java @Entity mapping webhook_dlq table
**Deliverable:** services/notification-webhook/src/main/java/com/gme/pay/notify/outbox/OutboxEvent.java and OutboxEventRepository.java
**Acceptance / logic checks:**
- @Entity maps to table outbox_event verified by Spring @DataJpaTest slice with Testcontainers Postgres 16
- findNextBatch returns only PENDING rows with nextAttemptAt <= Instant.now()
- @Lock(PESSIMISTIC_WRITE) prevents two concurrent callers picking same row (verified in two-thread Testcontainers test)
- OutboxEventStatus enum values exactly match SQL CHECK constraint: PENDING PROCESSING DELIVERED FAILED DLQ
- Inserting OutboxEvent with invalid status string throws DataIntegrityViolationException
**Depends on:** 8.6-T01

### 8.6-T05 — Implement EventPublisher interface and OutboxEventPublisher in lib-events and notification-webhook  _(40 min)_
**Context:** Per STACK.md Phase 1 messaging strategy: all domain events flow through an EventPublisher interface so Kafka can be wired later without rework. Define in lib-events: interface EventPublisher { void publish(String aggregateType, String aggregateId, String eventType, Object payload); }. OutboxEventPublisher @Service @Primary in services/notification-webhook implements this: serializes payload to JSON via Jackson ObjectMapper, persists OutboxEvent with status=PENDING and nextAttemptAt=Instant.now(). Annotate publish() with @Transactional(propagation=MANDATORY) to enforce atomicity with the calling transaction. Provide NullOutboxEventPublisher @Profile(test) no-op for test profiles.
**Steps:** Create lib-events/src/main/java/com/gme/pay/events/EventPublisher.java interface with publish() method; Create services/notification-webhook/src/main/java/com/gme/pay/notify/outbox/OutboxEventPublisher.java @Service @Primary implementing EventPublisher; Inject OutboxEventRepository and ObjectMapper; serialize payload; persist OutboxEvent with PENDING status; Annotate publish() with @Transactional(propagation=MANDATORY); Create NullOutboxEventPublisher.java @Service @Profile(test) for test isolation
**Deliverable:** lib-events/src/main/java/com/gme/pay/events/EventPublisher.java and services/notification-webhook/src/main/java/com/gme/pay/notify/outbox/OutboxEventPublisher.java
**Acceptance / logic checks:**
- Calling publish() outside a @Transactional context throws IllegalTransactionStateException
- publish() within a transaction that rolls back leaves zero outbox_event rows (verified Testcontainers Postgres)
- publish() in committed transaction inserts exactly one PENDING row with correct eventType and payload
- ObjectMapper serializes BigDecimal as string preserving precision (no floating point loss)
- NullOutboxEventPublisher bean is active when Spring profile=test
**Depends on:** 8.6-T03, 8.6-T04

### 8.6-T06 — Implement WebhookSigningService: HMAC-SHA256 outbound signing with Vault secret retrieval  _(40 min)_
**Context:** Every webhook POST must include: X-GME-Webhook-Signature: sha256=<HMAC-SHA256(rawBodyBytes, signingSecret)>, X-GME-Webhook-Timestamp: ISO-8601 UTC, X-GME-Event-ID: event_id. Per API-05 section 6.3. The signingSecret is per-partner; plaintext stored in Vault (Spring Cloud Vault via VaultTemplate), never in DB (only Argon2 hash in partner_webhook.signing_secret_hash). Signing: HMAC-SHA256 of UTF-8 body bytes; output = sha256= + lowercase hex. Verification for testing: verifySignature(rawBody, secret, signatureHeader) using MessageDigest.isEqual (constant-time, prevents timing attacks). Secrets zeroed after use.
**Steps:** Create services/notification-webhook/src/main/java/com/gme/pay/notify/signing/WebhookSigningService.java @Service; Inject VaultTemplate; fetch per-partner signing secret from Vault path secret/data/webhook/{partnerId}/signing-secret at dispatch time; Implement sign(byte[] bodyBytes, String secret): String using javax.crypto.Mac HMAC_SHA256; return sha256= + Hex.encodeHexString(digest); Implement verifySignature(String rawBody, String secret, String signatureHeader): boolean using MessageDigest.isEqual; Zero secret byte array in finally block after use (Arrays.fill(secretBytes, (byte)0))
**Deliverable:** services/notification-webhook/src/main/java/com/gme/pay/notify/signing/WebhookSigningService.java
**Acceptance / logic checks:**
- sign(UTF-8 bytes of hello world, super-secret-key) matches hardcoded expected HMAC-SHA256 hex value in unit test
- verifySignature uses MessageDigest.isEqual not String.equals (inspect source)
- verifySignature returns false for tampered body (one-byte change)
- verifySignature returns false when header missing sha256= prefix
- Vault path is never logged (log output does not contain secret value); secret bytes zeroed after use
- Unit test passes with VaultTemplate mocked via @MockBean

### 8.6-T07 — Implement PartnerWebhookConfig JPA entity and Redis-cached repository  _(35 min)_
**Context:** notification-webhook service needs per-partner webhook configuration (URL, active flag, event_types filter) from the partner_webhook PostgreSQL table (created V31). PartnerWebhookConfig @Entity maps this table. Service layer method fetchActiveConfigs(Long partnerId) annotated @Cacheable(cacheNames=webhook-config, key=#partnerId) caches in Redis (Spring Cache, TTL 300s configured in application.yml). The signing secret plaintext is NOT cached - only fetched from Vault at dispatch time. @CacheEvict on deactivate/update methods. Redis configured via spring.data.redis in application.yml.
**Steps:** Create services/notification-webhook/src/main/java/com/gme/pay/notify/config/PartnerWebhookConfig.java @Entity @Table(name=partner_webhook); Create PartnerWebhookConfigRepository.java extending JpaRepository with findByPartnerIdAndIsActiveTrue(); Create PartnerWebhookConfigService.java @Service with @Cacheable(cacheNames=webhook-config) on fetchActiveConfigs(); Configure Redis cache TTL 300s in application.yml: spring.cache.type=redis with RedisCacheConfiguration TTL; Add @CacheEvict(cacheNames=webhook-config, key=#partnerId) on deactivate and update methods
**Deliverable:** services/notification-webhook/src/main/java/com/gme/pay/notify/config/PartnerWebhookConfig.java and PartnerWebhookConfigService.java
**Acceptance / logic checks:**
- @Cacheable reduces DB queries to 1 for 10 identical lookups (integration test with Testcontainers Postgres + Redis)
- @CacheEvict on deactivate clears Redis entry (verify key absent in Redis Testcontainers after evict)
- fetchActiveConfigs returns empty list for partner with all is_active=false configs
- PartnerWebhookConfig.eventTypes null means all events; non-null comma list means filtered events only
- http:// URL in partner_webhook triggers CHECK violation on insert (DataIntegrityViolationException)
**Depends on:** 8.6-T02

### 8.6-T08 — Implement OutboxPollerService: scheduled outbox polling with retry back-off and DLQ promotion  _(55 min)_
**Context:** notification-webhook service polls outbox_event for PENDING rows every 2 seconds. Each poll: SELECT FOR UPDATE SKIP LOCKED up to 50 rows with status=PENDING and next_attempt_at<=now(). For each row: set status=PROCESSING, attempt dispatch. On HTTP 2xx: set status=DELIVERED, processed_at=now(). On failure (non-2xx or timeout): increment attempts, compute next_attempt_at per retry schedule (attempts 1:0s, 2:30s, 3:120s, 4:600s, 5:1800s, 6-10:3600s each), reset status=PENDING. After 10 failed attempts: set status=DLQ, insert to webhook_dlq, fire Ops alert. All DB mutations within @Transactional. Disabled via webhook.outbox.poller.enabled=false property.
**Steps:** Create services/notification-webhook/src/main/java/com/gme/pay/notify/outbox/OutboxPollerService.java @Service @ConditionalOnProperty(name=webhook.outbox.poller.enabled, havingValue=true, matchIfMissing=true); Inject OutboxEventRepository, WebhookDispatchService, WebhookDlqRepository, WebhookAlertService, MeterRegistry; Implement @Scheduled(fixedDelayString=${webhook.outbox.poll-interval-ms:2000}) pollAndDispatch(); Implement computeNextAttemptAt(int attemptNumber): Instant using array [0,30,120,600,1800,3600,3600,3600,3600,3600] seconds; On 10th failure: insert webhook_dlq row, set status=DLQ, call WebhookAlertService.fireDlqAlert(); Emit Micrometer counter webhook_dispatch_attempts_total (tags: partner_id, event_type, status) and gauge webhook_outbox_pending_count
**Deliverable:** services/notification-webhook/src/main/java/com/gme/pay/notify/outbox/OutboxPollerService.java
**Acceptance / logic checks:**
- computeNextAttemptAt(1)=0s, (2)=30s, (3)=120s, (4)=600s, (5)=1800s, (6)=3600s (unit test all 10 values)
- After 10th failure: outbox_event.status=DLQ and webhook_dlq has exactly one row for that event
- @Scheduled does not fire when webhook.outbox.poller.enabled=false (Spring context test)
- Two concurrent poller threads do not process the same outbox_event row (SKIP LOCKED Testcontainers test)
- Micrometer counter webhook_dlq_total increments by 1 when event enters DLQ
**Depends on:** 8.6-T04, 8.6-T07

### 8.6-T09 — Implement WebhookDispatchService: HTTP POST with signing headers, timeout, and attempt recording  _(50 min)_
**Context:** notification-webhook service dispatches webhooks via Spring WebClient (reactive non-blocking) to partner-registered HTTPS URLs. Per API-05 section 6.1: partner must respond HTTP 2xx within 10 seconds; any other response (3xx, 4xx, 5xx, timeout) is a delivery failure. For each dispatch: (1) fetch PartnerWebhookConfig; (2) build WebhookEventEnvelope JSON; (3) add headers X-GME-Webhook-Signature (sha256=<HMAC-SHA256>), X-GME-Webhook-Timestamp (ISO-8601 UTC), X-GME-Event-ID; (4) POST Content-Type:application/json; (5) record WebhookDeliveryAttempt. Defense-in-depth: reject http:// URLs before any network call. No redirect follow (maxRedirects=0). Emit OpenTelemetry span webhook.dispatch.
**Steps:** Create services/notification-webhook/src/main/java/com/gme/pay/notify/dispatch/WebhookDispatchService.java @Service; Create WebClientConfig.java @Configuration creating WebClient.Builder bean with responseTimeout(Duration.ofSeconds(10)) and no redirects; Implement dispatch(OutboxEvent event): WebhookDispatchResult fetching config, signing body, POSTing, recording attempt; Reject webhook_url not starting with https:// by throwing WebhookUrlNotHttpsException before any HTTP call; Persist WebhookDeliveryAttempt (attempt_number, http_status, response_body truncated to 500 chars, duration_ms); Emit OTel span with attributes: partner_id, event_type, attempt_number, http_status
**Deliverable:** services/notification-webhook/src/main/java/com/gme/pay/notify/dispatch/WebhookDispatchService.java
**Acceptance / logic checks:**
- WireMock returning HTTP 200: dispatch returns SUCCESS result (integration test)
- WireMock returning HTTP 500: dispatch returns FAILURE (WireMock integration test)
- WireMock with 11-second delay: dispatch returns FAILURE with duration >= 10000ms
- HTTP 301 redirect treated as FAILURE (not followed)
- http:// URL throws WebhookUrlNotHttpsException before any network call
- WebhookDeliveryAttempt row persisted after every attempt with correct http_status and duration_ms
**Depends on:** 8.6-T06, 8.6-T07

### 8.6-T10 — Implement WebhookEventFactory: build typed WebhookEventEnvelope from OutboxEvent  _(35 min)_
**Context:** notification-webhook service converts raw outbox_event payload JSON to a typed WebhookEventEnvelope before HTTP dispatch. WebhookEventFactory.buildEnvelope(OutboxEvent): deserializes payload using Jackson ObjectMapper to the correct payload class (switch on event_type via WebhookEventType enum), sets event_id as evt_ + ULID (de.huxhorn.sulky:ulid dependency), sets created_at=Instant.now() (dispatch time), partner_id from outbox event. Event type mapping: PAYMENT_APPROVED -> payment.approved, PAYMENT_PENDING_DEBIT -> payment.pending_debit, PAYMENT_FAILED -> payment.failed, PAYMENT_CANCELLED -> payment.cancelled, SETTLEMENT_COMPLETED -> settlement.completed. Throw UnknownEventTypeException for unrecognized types.
**Steps:** Create services/notification-webhook/src/main/java/com/gme/pay/notify/factory/WebhookEventFactory.java @Service; Inject ObjectMapper; add de.huxhorn.sulky:de.huxhorn.sulky.ulid to services/notification-webhook/build.gradle; Implement buildEnvelope(OutboxEvent): use switch on WebhookEventType to deserialize payload to correct class; Generate event_id as evt_ + UlidCreator.getMonotonicUlid().toString(); Throw UnknownEventTypeException for types not in WebhookEventType enum; Add unit test verifying event_id format and round-trip serialization
**Deliverable:** services/notification-webhook/src/main/java/com/gme/pay/notify/factory/WebhookEventFactory.java
**Acceptance / logic checks:**
- buildEnvelope with eventType=PAYMENT_APPROVED produces envelope with event_type=payment.approved and data as PaymentApprovedPayload
- event_id matches regex evt_[0-9A-Z]{26}
- buildEnvelope with unknown eventType throws UnknownEventTypeException
- Serialize envelope to JSON then deserialize back: all fields equal (round-trip test)
- Two ULID event_ids generated 1ms apart are lexicographically ordered (monotonic ULID property)
**Depends on:** 8.6-T03, 8.6-T04

### 8.6-T11 — Implement WebhookReplayGuard and WebhookSignatureVerifier for replay-attack protection  _(30 min)_
**Context:** API-05 section 6.3: partners must reject events where X-GME-Webhook-Timestamp is more than 5 minutes old (replay protection). notification-webhook service also uses this for its sandbox verify utility endpoint. WebhookReplayGuard.isTimestampValid(String timestampHeader, Clock clock): parses ISO-8601 UTC, checks abs(now - parsed) < 5 minutes (tolerance both directions: future clock skew accepted if < 5 min). WebhookSignatureVerifier.verify(String rawBody, String signingSecret, String signatureHeader, String timestampHeader): VerificationResult enum (VALID, INVALID_SIGNATURE, TIMESTAMP_EXPIRED, MISSING_HEADER). Order: check presence -> check timestamp -> check HMAC. Clock is injected for testability.
**Steps:** Create services/notification-webhook/src/main/java/com/gme/pay/notify/signing/WebhookReplayGuard.java with isTimestampValid(String, Clock): boolean; Create VerificationResult.java enum: VALID, INVALID_SIGNATURE, TIMESTAMP_EXPIRED, MISSING_HEADER; Create WebhookSignatureVerifier.java @Service injecting WebhookReplayGuard and WebhookSigningService; Implement verify() checking: null/blank headers -> MISSING_HEADER; timestamp check -> TIMESTAMP_EXPIRED; HMAC check -> INVALID_SIGNATURE or VALID; Inject Clock bean (default Clock.systemUTC()) to enable deterministic unit tests
**Deliverable:** services/notification-webhook/src/main/java/com/gme/pay/notify/signing/WebhookReplayGuard.java and WebhookSignatureVerifier.java
**Acceptance / logic checks:**
- Timestamp exactly 4m59s old returns VALID
- Timestamp exactly 5m01s old returns TIMESTAMP_EXPIRED
- Missing X-GME-Webhook-Signature returns MISSING_HEADER before any HMAC computation
- Correct HMAC with valid timestamp returns VALID
- Tampered body returns INVALID_SIGNATURE even with valid timestamp
- Clock injection allows fully deterministic test without Thread.sleep or system time dependency
**Depends on:** 8.6-T06

### 8.6-T12 — Implement Ops admin endpoints GET/POST /internal/v1/webhook/dlq for DLQ review and retry  _(45 min)_
**Context:** After 10 failed delivery attempts events enter webhook_dlq. Ops needs endpoints (internal, not on public partner API gateway): GET /internal/v1/webhook/dlq?partner_id=&page=&size= returns paginated DLQ events. POST /internal/v1/webhook/dlq/{id}/retry resets to outbox_event with status=PENDING and attempts=0. POST /internal/v1/webhook/dlq/{id}/resolve marks resolved_at=now(), resolved_by=operator from JWT. Protected by @PreAuthorize(hasRole(ROLE_GME_OPS)). All mutating actions write audit_event entries. Module: services/notification-webhook.
**Steps:** Create services/notification-webhook/src/main/java/com/gme/pay/notify/api/WebhookDlqController.java @RestController @RequestMapping(/internal/v1/webhook/dlq); Create WebhookDlqService.java @Service with retryEvent() and resolveEvent() methods; Implement GET with @RequestParam Optional<Long> partnerId, Pageable; return Page<WebhookDlqResponse> DTO; Implement POST /{id}/retry: validate DLQ event exists (404 if not); restore to outbox_event; write audit_event row; Implement POST /{id}/resolve: set resolved_at, resolved_by from JWT principal; write audit_event row; Apply @PreAuthorize(hasRole(ROLE_GME_OPS)) at class level; enable via @EnableMethodSecurity
**Deliverable:** services/notification-webhook/src/main/java/com/gme/pay/notify/api/WebhookDlqController.java
**Acceptance / logic checks:**
- GET /internal/v1/webhook/dlq without ROLE_GME_OPS returns HTTP 403
- POST /{id}/retry creates new outbox_event with attempts=0 and status=PENDING
- POST /{id}/retry with non-existent id returns HTTP 404
- POST /{id}/resolve sets resolved_at non-null and resolved_by to JWT sub claim
- Audit event row written for both retry and resolve (verified in @WebMvcTest with mock service assertion)
**Depends on:** 8.6-T04, 8.6-T08

### 8.6-T13 — Implement partner event-type subscription filtering in dispatch pipeline  _(30 min)_
**Context:** Each partner_webhook row has event_types (comma-separated, null=all events). Before making any HTTP call, OutboxPollerService must check: if partner_webhook.event_types IS NULL deliver all events; if non-null split by comma strip whitespace and check if event.eventType (dot-notation e.g. payment.approved) is in the list. Unsubscribed events: set status=DELIVERED immediately (no HTTP call) and record WebhookDeliveryAttempt with http_status=-1 and response_body=FILTERED. This prevents unbounded pending rows for events the partner never opted into.
**Steps:** Add filterCheck(PartnerWebhookConfig config, String dotNotationEventType): boolean to WebhookDispatchService; If config.getEventTypes() is null return true (all events pass); Otherwise split by comma, trim each token, check dotNotationEventType is in the resulting set; In OutboxPollerService.pollAndDispatch(): call filterCheck; if false immediately mark DELIVERED with FILTERED attempt record and skip HTTP dispatch; Add unit tests for null event_types, matching event, non-matching event, multi-value list
**Deliverable:** services/notification-webhook/src/main/java/com/gme/pay/notify/dispatch/WebhookDispatchService.java (updated with filterCheck method)
**Acceptance / logic checks:**
- Partner with event_types=payment.approved does not receive payment.failed (status=DELIVERED, http_status=-1)
- Partner with event_types=null receives all five event types (no filtering)
- Partner subscribed to payment.approved,settlement.completed receives those two but not payment.failed
- FILTERED events are marked DELIVERED so they do not loop in pending indefinitely
- filterCheck is a pure function testable without any DB or network
**Depends on:** 8.6-T08, 8.6-T09

### 8.6-T14 — Integrate EventPublisher calls into Transaction Orchestrator for payment lifecycle events  _(50 min)_
**Context:** TransactionOrchestrator in services/transaction-mgmt must call EventPublisher.publish() at payment lifecycle transitions. Calls must be within the same @Transactional boundary as the state update to guarantee atomicity (outbox pattern). Events to publish: (1) PAYMENT_APPROVED when transaction reaches APPROVED state with PaymentApprovedPayload built from transaction entity; (2) PAYMENT_FAILED when FAILED; (3) PAYMENT_CANCELLED when CANCELLED; (4) PAYMENT_PENDING_DEBIT in CPM flow when merchant scans and amount is known, before final approval. Also write transaction_event_trail step 7 (WEBHOOK_QUEUED) in the same transaction as outbox_event insert. See 8-step trail: step 7 = webhook_dispatched.
**Steps:** Inject EventPublisher into TransactionOrchestrator in services/transaction-mgmt/src/main/java/com/gme/pay/txnmgmt/orchestrator/TransactionOrchestrator.java; On APPROVED transition: call publisher.publish(payment, payment_id, PAYMENT_APPROVED, buildApprovedPayload(txn)); On FAILED transition: call publisher.publish(payment, payment_id, PAYMENT_FAILED, buildFailedPayload(txn)); On CANCELLED: call publisher.publish(payment, payment_id, PAYMENT_CANCELLED, buildCancelledPayload(txn)); In CPM path: call publisher.publish(payment, payment_id, PAYMENT_PENDING_DEBIT, buildPendingDebitPayload(txn)) when merchant amount arrives; Write transaction_event_trail step 7 row in same @Transactional method as publish()
**Deliverable:** services/transaction-mgmt/src/main/java/com/gme/pay/txnmgmt/orchestrator/TransactionOrchestrator.java (updated)
**Acceptance / logic checks:**
- DB rollback after APPROVED state update leaves zero outbox_event rows (Testcontainers atomicity test)
- Transition to APPROVED inserts one PENDING outbox_event with eventType=PAYMENT_APPROVED
- transaction_event_trail step 7 row committed in same transaction as outbox_event
- CPM path: PAYMENT_PENDING_DEBIT event published at pending_debit stage before scheme confirmation
- Integration test uses Testcontainers Postgres with real @Transactional (not @Transactional rollback annotation)
**Depends on:** 8.6-T05, 8.6-T10

### 8.6-T15 — Implement step-8 trail update (WEBHOOK_DELIVERED) after successful dispatch  _(40 min)_
**Context:** Per the 8-step event trail, step 8 = WEBHOOK_DELIVERED (partner endpoint returned HTTP 2xx). After successful dispatch OutboxPollerService must write a transaction_event_trail row: txn_id from outbox_event.aggregate_id, step=8, event_type=WEBHOOK_DELIVERED, occurred_at=dispatch timestamp, duration_ms, detail JSONB = {http_status, attempt_number, duration_ms}. The notification-webhook service writes directly to transaction_event_trail table in PostgreSQL schema transactions (same Postgres instance, cross-schema write). This write and the outbox_event DELIVERED update happen in the same @Transactional. If trail insert fails (e.g. txn not found), log WARN but do not roll back the DELIVERED status (webhook was already delivered).
**Steps:** Add TransactionEventTrail @Entity @Table(name=transaction_event_trail, schema=transactions) to notification-webhook JPA config; Add TransactionEventTrailRepository extending JpaRepository to notification-webhook; In OutboxPollerService after successful HTTP 2xx: build trail row with step=8, detail JSONB, call trailRepository.save() in same @Transactional; Catch DataIntegrityViolationException on trail save: log WARN and continue (do not fail the DELIVERED update); Add integration test with Testcontainers: confirm step-8 trail row written after mock partner returns 200
**Deliverable:** services/notification-webhook/src/main/java/com/gme/pay/notify/outbox/OutboxPollerService.java (updated with step-8 trail write)
**Acceptance / logic checks:**
- Successful dispatch creates transaction_event_trail row with step=8 and event_type=WEBHOOK_DELIVERED
- detail JSONB contains http_status, attempt_number, and duration_ms fields
- Both outbox_event.status=DELIVERED and trail step 8 committed atomically (both present or both absent on normal path)
- Trail insert failure does not roll back DELIVERED status (outbox_event remains DELIVERED)
- Integration test with Testcontainers Postgres verifies step-8 row present after WireMock returns 200
**Depends on:** 8.6-T08, 8.6-T09

### 8.6-T16 — Implement KafkaEventPublisher stub (integration-phase, disabled by default) behind EventPublisher interface  _(30 min)_
**Context:** STACK.md: Kafka is deferred to integration phase but the EventPublisher interface must isolate it so it can be enabled without rework. Implement KafkaEventPublisher.java @Service @ConditionalOnProperty(name=events.publisher.mode, havingValue=kafka) using Spring Kafka KafkaTemplate to publish to topic gme.domain.events (key=aggregateType:aggregateId, value=JSON payload). When kafka mode is active: OutboxEventPublisher is inactive (add @ConditionalOnProperty matchIfMissing=true to OutboxEventPublisher) and webhook.outbox.poller.enabled should be set false (Kafka becomes transport). Add application-kafka.yml profile stub with placeholder bootstrap-servers. No actual Kafka infra required in this ticket.
**Steps:** Add @ConditionalOnProperty(name=events.publisher.mode, havingValue=outbox, matchIfMissing=true) to OutboxEventPublisher; Create services/notification-webhook/src/main/java/com/gme/pay/notify/outbox/KafkaEventPublisher.java @Service @ConditionalOnProperty(name=events.publisher.mode, havingValue=kafka); Inject KafkaTemplate<String, String>; implement publish() calling kafkaTemplate.send(gme.domain.events, aggregateType+:+aggregateId, json); Create src/main/resources/application-kafka.yml with spring.kafka.bootstrap-servers placeholder and producer acks=all settings; Add unit test: with events.publisher.mode=outbox only OutboxEventPublisher bean present; with kafka only KafkaEventPublisher bean present
**Deliverable:** services/notification-webhook/src/main/java/com/gme/pay/notify/outbox/KafkaEventPublisher.java
**Acceptance / logic checks:**
- With events.publisher.mode=outbox (default), KafkaEventPublisher bean absent from Spring context
- With events.publisher.mode=kafka, OutboxEventPublisher bean absent from Spring context
- KafkaEventPublisher.publish() calls KafkaTemplate.send with topic=gme.domain.events and key format aggregateType:aggregateId (verified via MockBean)
- EventPublisher interface unchanged (no method signature changes)
- No compilation errors in KafkaEventPublisher when kafka dependencies absent from classpath (conditional dependency)
**Depends on:** 8.6-T05

### 8.6-T17 — Add Micrometer metrics and OpenTelemetry tracing to webhook dispatch pipeline  _(35 min)_
**Context:** Observability per spec: key metrics for webhook pipeline: webhook_dispatch_attempts_total counter (tags: partner_id, event_type, result=[success|failure|filtered]), webhook_outbox_pending_count gauge (current PENDING row count), webhook_dlq_total counter (tags: partner_id), webhook_dispatch_duration_seconds timer (tags: partner_id, event_type). Spec alert thresholds: P2 when delivery failure after max retries; P2 queue depth > 500; P3 p95 > 30s. OpenTelemetry spans webhook.dispatch (already started in T09). Register all metrics via @Bean MeterBinder in WebhookMetricsConfig @Configuration to ensure gauge registers at startup.
**Steps:** Create services/notification-webhook/src/main/java/com/gme/pay/notify/metrics/WebhookMetricsConfig.java @Configuration; Register Gauge webhook_outbox_pending_count reading OutboxEventRepository.countByStatus(PENDING) with Gauge.builder().strongReference(true); In OutboxPollerService inject MeterRegistry; increment webhook_dispatch_attempts_total with tags on each attempt result; In WebhookDispatchService record Timer.record() for dispatch HTTP call duration; In DLQ promotion path increment webhook_dlq_total counter with partner_id tag; Verify /actuator/prometheus endpoint exposes all four metric names
**Deliverable:** services/notification-webhook/src/main/java/com/gme/pay/notify/metrics/WebhookMetricsConfig.java
**Acceptance / logic checks:**
- webhook_outbox_pending_count gauge reports 0 with no rows and N after inserting N PENDING rows (Testcontainers integration test)
- webhook_dispatch_attempts_total increments by 1 per attempt with correct partner_id and event_type tags
- webhook_dlq_total increments when event enters DLQ
- webhook_dispatch_duration_seconds timer has at least one sample after a WireMock dispatch test
- GET /actuator/prometheus contains webhook_dispatch_attempts_total in response body
**Depends on:** 8.6-T08, 8.6-T09

### 8.6-T18 — Write unit tests for OutboxPollerService retry schedule, DLQ promotion, and filtering  _(40 min)_
**Context:** Core logic unit tests for OutboxPollerService. All mocked with Mockito (no DB, no network). Use fixed Clock for determinism. Test retry delay array: attempt 1=0s, 2=30s, 3=120s, 4=600s, 5=1800s, 6-10=3600s. DLQ promotion: 9th attempt leaves PENDING; 10th attempt triggers DLQ insert. Success on attempt 3: status=DELIVERED, no DLQ. Filtering: unsubscribed event type => DELIVERED with no dispatch call. next_attempt_at computed relative to dispatch time not row creation time. Also test that @Scheduled does not fire when poller property disabled (Spring context integration test).
**Steps:** Create services/notification-webhook/src/test/java/com/gme/pay/notify/outbox/OutboxPollerServiceTest.java; Mock OutboxEventRepository, WebhookDispatchService (returns FAILURE for failure tests), WebhookDlqRepository, MeterRegistry, Clock; Test computeNextAttemptAt(attempt) for all 10 values with assertEquals to exact Duration; Test 9th attempt: status=PENDING, WebhookDlqRepository.save() NOT called; Test 10th attempt: status=DLQ, WebhookDlqRepository.save() called exactly once; Test success on attempt 3: status=DELIVERED, attempt count=3, DLQ not invoked; Test filter: WebhookDispatchService.dispatch() never called for filtered event; outbox status=DELIVERED
**Deliverable:** services/notification-webhook/src/test/java/com/gme/pay/notify/outbox/OutboxPollerServiceTest.java
**Acceptance / logic checks:**
- computeNextAttemptAt(1) == Duration.ZERO
- computeNextAttemptAt(2) == Duration.ofSeconds(30)
- computeNextAttemptAt(5) == Duration.ofSeconds(1800)
- computeNextAttemptAt(6) through (10) all == Duration.ofSeconds(3600)
- After attempt 10 failure: WebhookDlqRepository.save() called exactly once with correct outbox event
- Test class has zero dependencies on Spring context, DB, or network
**Depends on:** 8.6-T08

### 8.6-T19 — Write unit tests for WebhookSigningService and WebhookReplayGuard with hardcoded test vectors  _(35 min)_
**Context:** Security-critical unit tests. WebhookSigningServiceTest: use hardcoded HMAC-SHA256 test vectors (body=hello world UTF-8, secret=super-secret-key; pre-compute expected hex offline before writing test). Test empty body, unicode body, 1MB body. Test verifySignature constant-time (inspect usage of MessageDigest.isEqual in source). WebhookReplayGuardTest: inject fixed Clock; test boundary conditions: 299s old (valid), 300s old (valid per spec less-than-5-min), 301s old (expired), 4m59s future (valid), 5m01s future (expired). All tests are pure unit tests with no Spring context or external dependencies.
**Steps:** Create services/notification-webhook/src/test/java/com/gme/pay/notify/signing/WebhookSigningServiceTest.java; Pre-compute HMAC-SHA256 of hello world with super-secret-key using openssl or Python; embed expected hex as constant; Test sign() for that vector, empty body, 1MB random body (compare to independently computed values); Test verifySignature returns true for correct sha256=<expected> header; Test verifySignature returns false for tampered body (flip one byte); Create WebhookReplayGuardTest with Clock.fixed(); test all 5 boundary conditions listed in context
**Deliverable:** services/notification-webhook/src/test/java/com/gme/pay/notify/signing/WebhookSigningServiceTest.java and WebhookReplayGuardTest.java
**Acceptance / logic checks:**
- Known vector: sign(hello world, super-secret-key) == hardcoded expected hex (test fails if algorithm changes)
- verifySignature(tampered body) returns false
- verifySignature(header without sha256= prefix) returns false
- ReplayGuard: 299s old timestamp returns true; 301s old returns false
- ReplayGuard: 4m59s future timestamp returns true; 5m01s future returns false
- Zero Spring context dependencies in either test class
**Depends on:** 8.6-T06, 8.6-T11

### 8.6-T20 — Write @DataJpaTest slice tests for OutboxEvent repository with Testcontainers Postgres 16  _(45 min)_
**Context:** Repository-layer tests using Spring Boot @DataJpaTest slice with @Testcontainers PostgreSQLContainer (not H2 - real Postgres required for CHECK constraints and partial indexes). Test scenarios: (1) findNextBatch returns only PENDING rows with nextAttemptAt<=now and skips PROCESSING/DELIVERED/DLQ rows; (2) CHECK constraint rejects invalid status string; (3) UNIQUE constraint on event_id prevents duplicates; (4) SKIP LOCKED: two concurrent threads calling findNextBatch each get distinct rows (no overlap). Also test WebhookDeliveryAttempt FK cascade and WebhookDlqEvent insert.
**Steps:** Create services/notification-webhook/src/test/java/com/gme/pay/notify/outbox/OutboxEventRepositoryTest.java; Annotate with @DataJpaTest @Testcontainers; define @Container static PostgreSQLContainer; Test 1: insert 3 PENDING rows, findNextBatch(now, page 0-50) returns all 3; Test 2: insert PROCESSING row, findNextBatch returns 0 (status filter); Test 3: CHECK constraint: set status to invalid string -> DataIntegrityViolationException; Test 4: two-thread concurrent findNextBatch on 2 rows: each thread gets exactly 1 distinct row (SKIP LOCKED); Test 5: DELETE outbox_event cascades to webhook_delivery_attempt rows
**Deliverable:** services/notification-webhook/src/test/java/com/gme/pay/notify/outbox/OutboxEventRepositoryTest.java
**Acceptance / logic checks:**
- findNextBatch with PROCESSING row returns empty (not PROCESSING in WHERE clause)
- findNextBatch with future nextAttemptAt returns empty (time filter working)
- DataIntegrityViolationException thrown for invalid status value (real Postgres CHECK constraint)
- Two-thread test: row A assigned to thread 1 only; row B assigned to thread 2 only (no duplicates)
- WebhookDeliveryAttempt rows deleted when parent outbox_event deleted (CASCADE verified)
**Depends on:** 8.6-T04

### 8.6-T21 — Write end-to-end integration tests for webhook dispatch pipeline with Testcontainers and WireMock  _(55 min)_
**Context:** Full pipeline integration test using @SpringBootTest, @Testcontainers (PostgreSQLContainer, Redis via GenericContainer), and WireMock (partner webhook endpoint). Four scenarios: (1) Happy path: insert outbox_event + partner_webhook, WireMock returns 200, verify DELIVERED + trail step 8; (2) Transient failure then success: WireMock returns 500 twice then 200, verify attempt_count=3 and DELIVERED; (3) DLQ path: WireMock always 500 for 10 attempts, verify status=DLQ and webhook_dlq row; (4) Filtered event: partner subscribed to payment.approved only, insert payment.failed, verify zero WireMock calls and status=DELIVERED.
**Steps:** Create services/notification-webhook/src/test/java/com/gme/pay/notify/integration/WebhookDispatchIntegrationTest.java; Annotate @SpringBootTest @Testcontainers; define PostgreSQLContainer, Redis GenericContainer, WireMockServer as static fields; Scenario 1: seed DB, stub WireMock 200, trigger pollAndDispatch(), assert outbox_event.status=DELIVERED and trail step=8; Scenario 2: stub WireMock 500-500-200 sequence; trigger poller 3 times; assert delivery_attempt rows count=3; Scenario 3: always-500 WireMock; trigger poller 10 times; assert status=DLQ and webhook_dlq count=1; Scenario 4: event_types=payment.approved in partner_webhook; insert payment.failed outbox_event; assert WireMock 0 requests and DELIVERED
**Deliverable:** services/notification-webhook/src/test/java/com/gme/pay/notify/integration/WebhookDispatchIntegrationTest.java
**Acceptance / logic checks:**
- Scenario 1: outbox_event.status=DELIVERED and transaction_event_trail step=8 both present
- Scenario 2: webhook_delivery_attempt rows=3; first two http_status=500; third http_status=200
- Scenario 3: webhook_dlq.outbox_event_id matches the exhausted event; outbox_event.status=DLQ
- Scenario 4: WireMock.verify(0 requests); outbox_event.status=DELIVERED
- All four scenarios pass in CI with Testcontainers (no pre-deployed infra required)
**Depends on:** 8.6-T08, 8.6-T09, 8.6-T13, 8.6-T15

### 8.6-T22 — Write atomicity integration test for EventPublisher outbox insert with transaction rollback  _(40 min)_
**Context:** Critical correctness test for the transactional Outbox pattern: if the business transaction rolls back, the outbox_event row must also be absent. Uses Testcontainers Postgres (not H2). Test 1 (rollback): begin @Transactional, update transaction state, call EventPublisher.publish(), throw RuntimeException to force rollback; assert both transaction state unchanged and outbox_event count=0. Test 2 (commit): same flow without exception; assert APPROVED state and one PENDING outbox_event. Test 3 (idempotency): same idempotency key called twice; assert outbox_event count=1 (no duplicate). This proves the atomicity guarantee that outbox rows are never orphaned.
**Steps:** Create services/transaction-mgmt/src/test/java/com/gme/pay/txnmgmt/outbox/OutboxAtomicityTest.java @SpringBootTest @Testcontainers; Define static PostgreSQLContainer; configure datasource to point to container; Test 1: inject a @MockBean that throws RuntimeException after publish(); call orchestrator method; assert outbox_event SELECT COUNT(*)=0 and transaction state unchanged; Test 2: call orchestrator method with no exception; assert transaction status=APPROVED and outbox_event count=1 with status=PENDING; Test 3: call with same idempotency key twice; assert outbox_event count=1 (duplicate blocked)
**Deliverable:** services/transaction-mgmt/src/test/java/com/gme/pay/txnmgmt/outbox/OutboxAtomicityTest.java
**Acceptance / logic checks:**
- Test 1: zero rows in outbox_event after rollback (SELECT COUNT(*) = 0 via JdbcTemplate)
- Test 2: exactly one PENDING outbox_event row after commit
- Test 3: second identical call does not insert second outbox_event
- Tests use @Testcontainers with PostgreSQLContainer not H2
- Kafka and Redis beans are excluded or mocked (tests focus on DB atomicity only)
**Depends on:** 8.6-T05, 8.6-T14

### 8.6-T23 — Add webhook event schemas to openapi/partner-api.yaml as reusable components  _(40 min)_
**Context:** openapi/partner-api.yaml is the ground truth for contract tests (STACK.md). Webhook section (API-05 section 6) must be documented as OpenAPI 3 component schemas. Add: WebhookEvent (envelope), PaymentApprovedData (all 16 fields from spec 6.5), PaymentPendingDebitData, PaymentFailedData, PaymentCancelledData, SettlementCompletedData. BigDecimal fields documented as type: string (not number) to match Java serialization. required arrays must list all mandatory fields. Add x-webhook-events extension at root level mapping each event_type to its data schema $ref. Validate with swagger-parser.
**Steps:** Open openapi/partner-api.yaml; locate components/schemas section; Add WebhookEvent schema with event_id, event_type enum (5 values), created_at, partner_id, data properties; Add all five data schemas with field definitions matching API-05 section 6.5 exactly; Use type: string for target_payout, offer_rate, collection_amount, service_charge, prefund_deducted_usd fields; Add x-webhook-events extension at document root mapping event types to $ref data schemas; Run swagger-parser validation and fix any errors before marking done
**Deliverable:** openapi/partner-api.yaml (updated with WebhookEvent and five data schemas in components/schemas)
**Acceptance / logic checks:**
- swagger-parser validates file with zero errors
- WebhookEvent.event_type enum contains exactly payment.approved, payment.pending_debit, payment.failed, payment.cancelled, settlement.completed
- PaymentApprovedData.target_payout has type: string (not number)
- PaymentApprovedData required array contains all 16 fields from API-05 section 6.5
- x-webhook-events extension present at root level with five entries each pointing to a $ref data schema
**Depends on:** 8.6-T03

### 8.6-T24 — Implement Ops alert integration: P2 alert on DLQ promotion and queue depth breach  _(40 min)_
**Context:** Spec requires P2 alert: (a) partner webhook returns non-2xx after max retries (DLQ); (b) webhook queue depth > 500 unprocessed events. Phase 1: write to alert_event PostgreSQL table (id BIGSERIAL PK, alert_type VARCHAR(50), severity VARCHAR(5), partner_id BIGINT, message TEXT, context JSONB, fired_at TIMESTAMPTZ, acknowledged_at TIMESTAMPTZ, acknowledged_by VARCHAR(100)). Future phases swap for PagerDuty/Slack via same interface. Add dedup: do not insert queue-depth alert if an unacknowledged queue-depth alert for same partner exists fired within last 10 minutes (prevents alert storm). Flyway migration V32__alert_event.sql.
**Steps:** Create services/notification-webhook/src/main/resources/db/migration/V32__alert_event.sql with alert_event table; Create WebhookAlertService.java @Service with fireDlqAlert(Long partnerId, Long outboxEventId, String eventType) and fireQueueDepthAlert(Long partnerId, long pendingCount); fireDlqAlert always inserts P2 alert_event row with alert_type=WEBHOOK_DLQ; fireQueueDepthAlert inserts P2 row with alert_type=WEBHOOK_QUEUE_DEPTH only if count > 500 and no unacknowledged alert in last 10 minutes for same partner; Create AlertEventRepository extending JpaRepository with existsByPartnerIdAndAlertTypeAndAcknowledgedAtIsNullAndFiredAtAfter query; In OutboxPollerService call fireQueueDepthAlert after each poll batch
**Deliverable:** services/notification-webhook/src/main/java/com/gme/pay/notify/alert/WebhookAlertService.java and V32__alert_event.sql
**Acceptance / logic checks:**
- fireDlqAlert inserts alert_event with severity=P2 and alert_type=WEBHOOK_DLQ
- fireQueueDepthAlert with count=499 does NOT insert row (threshold strictly > 500)
- fireQueueDepthAlert with count=501 inserts P2 alert_event with alert_type=WEBHOOK_QUEUE_DEPTH
- Second fireQueueDepthAlert call within 10 minutes for same partner does NOT insert duplicate row
- V32 migration applies cleanly on Testcontainers Postgres 16
**Depends on:** 8.6-T08, 8.6-T01

### 8.6-T25 — Write contract tests validating webhook payloads against openapi/partner-api.yaml schemas  _(40 min)_
**Context:** openapi/partner-api.yaml is the ground truth. Contract tests must verify that WebhookEventFactory output matches the OpenAPI component schemas added in 8.6-T23. Use io.swagger.v3.parser.OpenAPIV3Parser to load the spec and a JSON Schema validator to validate serialized payloads. Test all five event types with concrete field values. Include negative test: PaymentApprovedData missing required field approved_at must fail validation. Tests are pure (no DB, no network): the spec file is read from classpath and payload objects are constructed directly.
**Steps:** Create services/notification-webhook/src/test/java/com/gme/pay/notify/contract/WebhookPayloadContractTest.java; Load openapi/partner-api.yaml from classpath using OpenAPIV3Parser; For each of five event types: construct concrete payload object, serialize via ObjectMapper, extract JSON Schema from components/schemas, validate; Assert all five happy-path payloads pass with no validation errors; Negative test: construct PaymentApprovedData without approved_at; assert validation failure mentioning approved_at; Negative test: event_type=payment.unknown in WebhookEvent; assert enum validation failure
**Deliverable:** services/notification-webhook/src/test/java/com/gme/pay/notify/contract/WebhookPayloadContractTest.java
**Acceptance / logic checks:**
- All five happy-path payloads pass schema validation (zero error messages)
- Missing approved_at in PaymentApprovedData triggers validation error mentioning approved_at
- event_type=payment.unknown fails WebhookEvent enum validation
- target_payout as BigDecimal serialized as string passes schema type:string validation
- Test runs without network or Spring context (spec loaded from src/test/resources classpath copy)
**Depends on:** 8.6-T10, 8.6-T23

### 8.6-T26 — Create notification-webhook Spring Boot service skeleton, Gradle module, and K8s manifests  _(45 min)_
**Context:** notification-webhook is a dedicated Spring Boot 3.x microservice in the monorepo. Gradle module: services/notification-webhook. Dependencies: lib-money, lib-errors, lib-api-contracts, lib-events, lib-persistence plus spring-boot-starter-web, spring-boot-starter-data-jpa, spring-boot-starter-cache, spring-data-redis, spring-kafka, spring-boot-starter-actuator, micrometer-registry-prometheus, spring-cloud-starter-vault-config, spring-boot-starter-security, spring-boot-starter-oauth2-resource-server, de.huxhorn.sulky:ulid. application.yml: server.port=8085, JDBC datasource, Redis, Flyway, actuator. K8s deployment: 2 replicas, 512Mi/0.5CPU limits. Add to root settings.gradle.
**Steps:** Add include(services:notification-webhook) to root settings.gradle; Create services/notification-webhook/build.gradle with all dependencies listed in context; Create services/notification-webhook/src/main/java/com/gme/pay/notify/NotificationWebhookApplication.java @SpringBootApplication; Create src/main/resources/application.yml with server.port=8085, datasource, redis, flyway, actuator, jwt issuer-uri placeholder; Create deploy/k8s/notification-webhook/deployment.yaml with replicas: 2, resource limits memory: 512Mi cpu: 500m; Run ./gradlew :services:notification-webhook:compileJava to verify no compilation errors
**Deliverable:** services/notification-webhook/build.gradle and NotificationWebhookApplication.java
**Acceptance / logic checks:**
- ./gradlew :services:notification-webhook:compileJava succeeds with zero errors
- Spring context loads in @SpringBootTest (ApplicationContextLoads test passes)
- server.port=8085 in application.yml
- K8s deployment.yaml has replicas: 2 and memory limit 512Mi
- Flyway migration location points to db/migration inside the module classpath

<!-- wbs-v3-gap-closure -->

---

## WBS v3 gap-closure tickets (re-baseline, 2026-06-10)

These tickets convert this service's PARTIAL audit findings into DONE and add work discovered during the build. Statuses live on the `Backlog` sheet of `GMEPay+_Task_Backlog.xlsx`; phase sequencing on the `Completion Plan v3` sheet of `GMEPay+_WBS.xlsx`.

### 17.2-G11 — notification-webhook: swap H2 for real PostgreSQL ITs
*Completion phase:* **R1** · *Est:* 120 min · *Role:* Backend · *Deps:* 17.1-G02

**Context.** Tests currently run on H2 in PostgreSQL mode. Acceptance requires real PG. Scope: webhook endpoints/deliveries/DLQ tables.

**Steps.**
- Add Testcontainers postgres:16 to the service's ITs
- Run Flyway migrations against it; fix PG-only syntax drift
- Keep H2 only for pure unit slices

**Deliverable.** Repository/migration ITs green on PostgreSQL 16

**Acceptance.**
- ./gradlew :services:notification-webhook:test green with Testcontainers
- Migration checksum stable; no H2-mode workarounds left

### 17.4-G04 — Consume payment.approved from Kafka
*Completion phase:* **R1** · *Est:* 140 min · *Role:* Backend · *Deps:* 17.4-G01

**Context.** Webhook service signs+delivers with backoff/DLQ but has no real consumer. Subscribe to payment.approved; trigger delivery pipeline.

**Steps.**
- @KafkaListener, manual ack after enqueue
- DLQ topic on poison messages
- IT: end-to-end produce→deliver to wiremock endpoint

**Deliverable.** Kafka-driven webhook delivery

**Acceptance.**
- payment.approved produces a signed POST to subscriber
- Poison message lands in DLQ, not retry loop

