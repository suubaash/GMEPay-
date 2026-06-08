# transaction-mgmt  (backend)

**Scope:** Txn state machine, 8-step event trail, idempotency, outbox

**Owned WBS work-packages:** 3.3, 5.1, 5.7  ·  **Tickets:** 73  ·  **Est:** 46.6h

## Service contract (MSA: own DB, API-only communication)

- **Datastore (owned by this service):** PostgreSQL `txn` (transactions, event trail, outbox)
- **APIs / events I EXPOSE:** /v1/transactions/{id}, state ops; events transaction.*
- **APIs / events I CONSUME:** —
- **Integration rule:** never read another service's database or import its private entities — call its API or consume its event; stub consumed services with WireMock in tests.

> Self-contained backlog for this service. Build it as its own repo/module with its own DB + Flyway migrations, against the `shared-libs` contracts (lib-money / lib-errors / lib-events / lib-api-contracts only). Each ticket has a deliverable + acceptance checks.


## WBS 3.3 — Transaction & event tables (8-step trail)
### 3.3-T01 — Flyway migration: create transaction table with rate-lock columns  _(40 min)_
**Context:** Module: services/transaction-mgmt. The transaction table is the central fact table in PostgreSQL 16. Stores all 5-step USD-pool values (collection_usd DECIMAL(20,8), payout_usd_cost DECIMAL(20,8), collection_margin_usd DECIMAL(20,8), payout_margin_usd DECIMAL(20,8), send_amount DECIMAL(20,4)), derived rates (offer_rate_coll DECIMAL(20,8), cross_rate DECIMAL(20,8)), cost snapshots (cost_rate_coll DECIMAL(20,8), cost_rate_pay DECIMAL(20,8)), four ccy columns (CHAR(3)), status VARCHAR(20), txn_ref VARCHAR(64) UNIQUE (idempotency key), hub_txn_ref VARCHAR(64), rate_locked_at TIMESTAMPTZ, committed_at TIMESTAMPTZ, completed_at TIMESTAMPTZ. KRW amounts as DECIMAL(20,4) with scale 0 enforced by app. Enums as VARCHAR + CHECK constraints. All timestamps TIMESTAMPTZ UTC. Pool columns nullable (NULL for same-ccy short-circuit). Audit columns: created_at, updated_at, created_by VARCHAR(120), updated_by VARCHAR(120).
**Steps:** Create services/transaction-mgmt/src/main/resources/db/migration/V10__transaction.sql; Add BIGINT PK id (GENERATED ALWAYS AS IDENTITY), all identity/FK columns: partner_id, scheme_id, rule_id, rate_quote_id, merchant_id, qr_code_id as BIGINT NOT NULL (FKs added in V14); Add VARCHAR columns: txn_ref UNIQUE NOT NULL, hub_txn_ref NOT NULL, scheme_ref NULL, payment_mode CHECK IN (MPM,CPM), direction CHECK IN (INBOUND,OUTBOUND,DOMESTIC,HUB), status NOT NULL DEFAULT INITIATED; Add all money/rate columns per context with correct DECIMAL precision and nullability; add BOOLEAN is_same_ccy_shortcircuit DEFAULT FALSE, DECIMAL(20,4) prefunding_deducted_usd NULL; Add all TIMESTAMPTZ columns and audit columns; add CHECK on status for 9 valid values; Add six composite indexes: (partner_id,committed_at), (scheme_id,completed_at), (status,committed_at), scheme_ref, hub_txn_ref, plus partial index on (status,partner_id) WHERE status IN (INITIATED,PREFUND_DEDUCTED,SUBMITTED,UNCERTAIN)
**Deliverable:** services/transaction-mgmt/src/main/resources/db/migration/V10__transaction.sql
**Acceptance / logic checks:**
- Flyway migrate completes without error against a Testcontainers PostgreSQL 16 instance
- INSERT of a cross-border row with all DECIMAL(20,8) pool columns populated succeeds; SELECT returns exact values with no floating-point drift
- INSERT of a same-ccy row with is_same_ccy_shortcircuit=TRUE and NULL pool columns succeeds
- UNIQUE constraint on txn_ref: second INSERT with same txn_ref raises constraint violation
- status CHECK rejects value UNKNOWN; all 9 valid values (INITIATED,PREFUND_DEDUCTED,SUBMITTED,APPROVED,DECLINED,UNCERTAIN,FAILED,CANCELLED,REFUNDED) INSERT cleanly
- All 6 indexes visible in pg_indexes

### 3.3-T02 — Flyway migration: create rate_quote table  _(30 min)_
**Context:** Module: services/transaction-mgmt (shared schema with services/rate-fx). rate_quote captures the output of the rate engine at GET /v1/rates or CPM prepare time. All 5-step USD-pool fields plus derived rates and treasury FK snapshots. On CommitTransaction, values are copied to transaction and is_used flipped to TRUE. valid_until = quote_issued_at + partner.rate_quote_ttl_seconds (default 300s, range 60-1800s). Column types: DECIMAL(20,8) for all USD-pool and rate columns; DECIMAL(20,4) for send_amount/service_charge/collection_amount/target_payout. quote_ref VARCHAR(64) UNIQUE. FKs to rule, partner, qr_scheme, treasury_rate (x2 for coll and pay snapshots).
**Steps:** Create services/transaction-mgmt/src/main/resources/db/migration/V9__rate_quote.sql; Add BIGINT PK id (GENERATED ALWAYS AS IDENTITY), VARCHAR(64) UNIQUE quote_ref, BIGINT FKs to rule/partner/qr_scheme (referential enforcement added in V14); Add all pool columns (DECIMAL(20,8) nullable for same-ccy): payout_usd_cost, collection_usd, collection_margin_usd, payout_margin_usd, cost_rate_coll, cost_rate_pay; Add DECIMAL(20,4) columns: target_payout NOT NULL, send_amount NOT NULL, service_charge NOT NULL, collection_amount NOT NULL; CHAR(3) ccy columns for each; Add offer_rate_coll DECIMAL(20,8) NULL, cross_rate DECIMAL(20,8) NULL; BIGINT treasury_rate_id_coll NULL, treasury_rate_id_pay NULL; TIMESTAMPTZ quote_issued_at NOT NULL, valid_until NOT NULL; BOOLEAN is_used DEFAULT FALSE; Add index on (partner_id, valid_until) for TTL expiry queries; add index on rule_id
**Deliverable:** services/transaction-mgmt/src/main/resources/db/migration/V9__rate_quote.sql
**Acceptance / logic checks:**
- Flyway migration runs cleanly on Testcontainers PostgreSQL 16
- quote_ref UNIQUE constraint fires on duplicate insert
- INSERT with same-ccy path: all USD-pool columns NULL, collection_amount = target_payout + service_charge value confirmed by SELECT
- is_used flag updates from FALSE to TRUE via single UPDATE
- DECIMAL(20,8) precision: inserting 1380.12345678 stores and retrieves without rounding

### 3.3-T03 — Flyway migration: create transaction_event append-only trail table  _(25 min)_
**Context:** Module: services/transaction-mgmt. transaction_event stores the immutable 8-step event trail per transaction. Canonical steps (SEC-09 and PRD-07): 1=RATE_QUOTE_ISSUED, 2=PAYMENT_INITIATED, 3=PREFUND_DEDUCTED (OVERSEAS only; absent for LOCAL), 4=SCHEME_REQUEST_SENT, 5=SCHEME_RESPONSE_RECEIVED, 6=TRANSACTION_COMMITTED, 7=WEBHOOK_DISPATCHED, 8=WEBHOOK_DELIVERED. Columns: id BIGINT PK, txn_id BIGINT NOT NULL FK->transaction, step INT NOT NULL CHECK (1..8), event_type VARCHAR(50) NOT NULL, occurred_at TIMESTAMPTZ NOT NULL, duration_ms INT NULL (ms since previous step), detail JSONB NULL (step-specific payload e.g. {http_status:200} for step 8), created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(). No updated_at - append-only and immutable. Retention >= 7 years (BOK Korean financial record-keeping). UNIQUE(txn_id, step) prevents duplicate trail entries.
**Steps:** Create services/transaction-mgmt/src/main/resources/db/migration/V11__transaction_event.sql; Add columns: id BIGINT PK, txn_id BIGINT NOT NULL, step INT NOT NULL CHECK (step BETWEEN 1 AND 8), event_type VARCHAR(50) NOT NULL; Add occurred_at TIMESTAMPTZ NOT NULL, duration_ms INT NULL CHECK (duration_ms >= 0), detail JSONB NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(); Add UNIQUE constraint on (txn_id, step); Add index on (txn_id, step) for trail retrieval; add index on (txn_id, occurred_at) for chronological queries; Add table comment: append-only; no UPDATE or DELETE permitted; retention >= 7 years
**Deliverable:** services/transaction-mgmt/src/main/resources/db/migration/V11__transaction_event.sql
**Acceptance / logic checks:**
- Flyway migration runs cleanly on Testcontainers PostgreSQL 16
- INSERT of all 8 steps for one txn_id succeeds; SELECT ORDER BY step returns steps 1-8
- UNIQUE (txn_id, step) fires on duplicate step insert for same txn_id
- step CHECK rejects value 0 and value 9
- detail JSONB column accepts {http_status: 200} payload and is queryable with ->> operator
- duration_ms CHECK rejects negative value
**Depends on:** 3.3-T01

### 3.3-T04 — Flyway migration: create domain_event_outbox table (transactional Outbox)  _(20 min)_
**Context:** Module: services/transaction-mgmt. STACK.md Phase 1: transactional Outbox pattern - domain events written to outbox in the SAME DB transaction as business state change; a polling publisher reads and dispatches. Kafka is NOT wired in Phase 1 - the EventPublisher interface (lib-events) abstracts the transport. Columns: id BIGINT PK, aggregate_type VARCHAR(50) NOT NULL (e.g. TRANSACTION), aggregate_id VARCHAR(64) NOT NULL (txn_ref), event_type VARCHAR(100) NOT NULL (e.g. TransactionCommittedEvent), payload JSONB NOT NULL, status VARCHAR(20) NOT NULL DEFAULT PENDING CHECK IN (PENDING,DISPATCHED,FAILED), created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), dispatched_at TIMESTAMPTZ NULL, retry_count INT NOT NULL DEFAULT 0, last_error TEXT NULL.
**Steps:** Create services/transaction-mgmt/src/main/resources/db/migration/V12__domain_event_outbox.sql; Define all columns with types, NOT NULL constraints, CHECK on status IN (PENDING,DISPATCHED,FAILED); Add index on (status, created_at) for outbox poller query: SELECT ... WHERE status=PENDING ORDER BY created_at LIMIT 50; Add index on (aggregate_type, aggregate_id) for per-aggregate event lookup; Add table comment: Phase 1 outbox-only; Kafka transport wired in integration phase via EventPublisher interface in lib-events
**Deliverable:** services/transaction-mgmt/src/main/resources/db/migration/V12__domain_event_outbox.sql
**Acceptance / logic checks:**
- Flyway migration runs cleanly on Testcontainers PostgreSQL 16
- INSERT of a PENDING event succeeds; SELECT WHERE status=PENDING returns it
- UPDATE status to DISPATCHED + set dispatched_at: row visible with status=DISPATCHED
- status CHECK rejects value UNKNOWN
- payload JSONB column accepts and round-trips a TransactionCommittedEvent JSON object
- Index on (status,created_at) appears in pg_indexes
**Depends on:** 3.3-T01

### 3.3-T05 — Flyway migration: FK wiring for transaction, rate_quote, transaction_event  _(20 min)_
**Context:** Module: services/transaction-mgmt. Separate migration adds FK constraints after all tables exist: transaction.rate_quote_id->rate_quote(id), transaction.partner_id->partner(id), transaction.scheme_id->qr_scheme(id), transaction.rule_id->rule(id), transaction_event.txn_id->transaction(id). All ON DELETE RESTRICT. Also adds the CHECK constraint on transaction.status covering all 9 values, and confirms UNIQUE(txn_id,step) on transaction_event. Separate migration keeps V9-V12 clean and avoids ordering dependencies.
**Steps:** Create services/transaction-mgmt/src/main/resources/db/migration/V13__transaction_fk_constraints.sql; Add ALTER TABLE transaction ADD CONSTRAINT fk_txn_rate_quote FOREIGN KEY (rate_quote_id) REFERENCES rate_quote(id) ON DELETE RESTRICT; Add FKs for partner_id, scheme_id, rule_id referencing their respective tables ON DELETE RESTRICT; Add ALTER TABLE transaction_event ADD CONSTRAINT fk_txnevent_txn FOREIGN KEY (txn_id) REFERENCES transaction(id) ON DELETE RESTRICT; Verify UNIQUE(txn_id,step) on transaction_event exists; add if absent; Verify all 9 status CHECK values present; add or replace if absent
**Deliverable:** services/transaction-mgmt/src/main/resources/db/migration/V13__transaction_fk_constraints.sql
**Acceptance / logic checks:**
- Flyway V13 runs cleanly in sequence after V9-V12 on Testcontainers PostgreSQL 16
- DELETE from transaction with existing transaction_event rows raises FK violation
- INSERT transaction_event with txn_id not in transaction raises FK violation
- pg_constraint shows fk_txn_rate_quote and fk_txnevent_txn constraints
- status CHECK rejects INVALID_STATUS; all 9 valid statuses insert cleanly
**Depends on:** 3.3-T04, 3.3-T03, 3.3-T02

### 3.3-T06 — Define TransactionCommittedEvent and PrefundDeductedEvent in lib-events  _(30 min)_
**Context:** Module: lib-events (shared library). Domain event schemas must be defined now so outbox payloads are structured from day one. Two events for WBS 3.3: (1) TransactionCommittedEvent - fields: txn_ref String, partner_id Long, collection_usd BigDecimal, payout_usd_cost BigDecimal, collection_margin_usd BigDecimal, payout_margin_usd BigDecimal, send_amount BigDecimal, service_charge BigDecimal, collection_amount BigDecimal, offer_rate_coll BigDecimal, cross_rate BigDecimal, is_same_ccy_shortcircuit boolean, committed_at Instant. (2) PrefundDeductedEvent - fields: txn_ref String, partner_id Long, deducted_amount_usd BigDecimal, balance_after_usd BigDecimal, deducted_at Instant. Both as Java 21 records. All monetary fields as BigDecimal (never double). DomainEvent marker interface with aggregateType(), aggregateId(), eventType(), occurredAt().
**Steps:** Create lib-events/src/main/java/com/gme/pay/events/DomainEvent.java interface with String aggregateType(), String aggregateId(), String eventType(), Instant occurredAt(); Create lib-events/src/main/java/com/gme/pay/events/transaction/TransactionCommittedEvent.java as Java record implementing DomainEvent; aggregateType()=TRANSACTION, aggregateId()=txnRef, eventType()=TransactionCommittedEvent; Create lib-events/src/main/java/com/gme/pay/events/transaction/PrefundDeductedEvent.java as Java record implementing DomainEvent; same pattern; Add @JsonProperty annotations ensuring snake_case serialisation matching DB column names (txn_ref, collection_usd etc.); Add @JsonSerialize(using=ToStringSerializer.class) on BigDecimal fields to prevent scientific notation; Add unit test in lib-events verifying round-trip Jackson serialisation of both events
**Deliverable:** lib-events/src/main/java/com/gme/pay/events/transaction/TransactionCommittedEvent.java and PrefundDeductedEvent.java
**Acceptance / logic checks:**
- TransactionCommittedEvent serialises to JSON with snake_case keys matching DB column names
- PrefundDeductedEvent.aggregateType() returns TRANSACTION; aggregateId() returns txn_ref value
- BigDecimal 1380.12345678 serialises as 1380.12345678 (no scientific notation)
- Round-trip test: serialize then deserialize returns equal record
- Unit test passes with ./gradlew :lib-events:test

### 3.3-T07 — Implement EventPublisher interface and OutboxEventPublisher @Service in lib-events  _(40 min)_
**Context:** Module: lib-events. STACK.md: Phase 1 = transactional Outbox + polling publisher; Kafka wired in integration phase behind the same interface. EventPublisher interface: void publish(DomainEvent event). OutboxEventPublisher: @Service implementing EventPublisher; annotated @Transactional(propagation=MANDATORY) so it ONLY runs inside an existing caller transaction (guarantees atomicity with business write). Inserts one row into domain_event_outbox per call: aggregate_type, aggregate_id, event_type, payload=serialized JSON, status=PENDING, created_at=NOW(). Uses OutboxEvent @Entity and OutboxEventRepository extends JpaRepository<OutboxEvent,Long>. No Kafka dependency in this module.
**Steps:** Create lib-events/src/main/java/com/gme/pay/events/EventPublisher.java interface with void publish(DomainEvent event); Create lib-events/src/main/java/com/gme/pay/events/outbox/OutboxEvent.java @Entity mapped to domain_event_outbox table with all columns; Create lib-events/src/main/java/com/gme/pay/events/outbox/OutboxEventRepository.java extending JpaRepository<OutboxEvent,Long> with List<OutboxEvent> findTop50ByStatusOrderByCreatedAt(String status); Create lib-events/src/main/java/com/gme/pay/events/outbox/OutboxEventPublisher.java @Service implementing EventPublisher with @Transactional(propagation=MANDATORY); In publish(): serialize event payload via ObjectMapper, build OutboxEvent entity, save via repository; Add @ConditionalOnMissingBean so services can override with a Kafka publisher in Phase 2
**Deliverable:** lib-events/src/main/java/com/gme/pay/events/outbox/OutboxEventPublisher.java
**Acceptance / logic checks:**
- publish() called outside a @Transactional context throws TransactionRequiredException (MANDATORY propagation enforced)
- publish() called inside a transaction inserts one row with status=PENDING into domain_event_outbox
- If enclosing transaction rolls back, the outbox row is also absent (atomicity via Testcontainers Postgres)
- event_type column contains TransactionCommittedEvent for a TransactionCommittedEvent input
- payload column contains valid JSON round-trippable to original record
**Depends on:** 3.3-T06, 3.3-T04

### 3.3-T08 — Implement OutboxPoller @Scheduled component in services/transaction-mgmt  _(45 min)_
**Context:** Module: services/transaction-mgmt. OutboxPoller polls domain_event_outbox for PENDING rows and dispatches them via EventPublisher. Spring @Scheduled(fixedDelayString=${outbox.poll.interval-ms:5000}). Polling uses SELECT ... FOR UPDATE SKIP LOCKED to prevent double-dispatch across multiple pod replicas. Batch size: 50 rows per poll. On success: UPDATE status=DISPATCHED, dispatched_at=NOW(). On failure: increment retry_count; if retry_count >= 3 set status=FAILED, last_error=exception message. Phase 1 dispatch = log event + mark DISPATCHED (no Kafka). @ConditionalOnProperty(name=outbox.poller.enabled, havingValue=true, matchIfMissing=true) allows disabling in tests.
**Steps:** Create services/transaction-mgmt/src/main/java/com/gme/pay/txnmgmt/outbox/OutboxPoller.java annotated @Component @ConditionalOnProperty; Inject OutboxEventRepository and EventPublisher; Implement @Scheduled pollAndDispatch(): open new @Transactional(propagation=REQUIRES_NEW), load up to 50 PENDING rows with native query using FOR UPDATE SKIP LOCKED; For each row: attempt EventPublisher dispatch; on success mark DISPATCHED + set dispatched_at; on exception increment retry_count, if >= 3 mark FAILED; Log each dispatched event at INFO with aggregate_type, aggregate_id, event_type using SLF4J MDC; Annotate pollAndDispatch with @Transactional(propagation=REQUIRES_NEW) so each poll batch is its own transaction
**Deliverable:** services/transaction-mgmt/src/main/java/com/gme/pay/txnmgmt/outbox/OutboxPoller.java
**Acceptance / logic checks:**
- Two concurrent poller instances do not double-dispatch the same row (SKIP LOCKED test: start 2 threads simultaneously against Testcontainers Postgres, verify each PENDING row dispatched exactly once)
- Row transitions from PENDING to DISPATCHED with dispatched_at set after one poll cycle
- Row with dispatch throwing exception 3 times transitions to FAILED with last_error populated
- Setting outbox.poller.enabled=false in test application.yml prevents @Scheduled from running
- pollAndDispatch() REQUIRES_NEW: DISPATCHED status commits even when called from a method with no surrounding transaction
**Depends on:** 3.3-T07

### 3.3-T09 — Implement Transaction and TransactionEvent @Entity classes with Spring Data JPA  _(40 min)_
**Context:** Module: services/transaction-mgmt. Spring Data JPA entities for the transaction and transaction_event tables in package com.gme.pay.txnmgmt.domain. Transaction @Entity: maps all columns from V10 migration. All money fields as BigDecimal. @Column(updatable=false) on all 13 rate-lock columns (collection_usd, payout_usd_cost, collection_margin_usd, payout_margin_usd, send_amount, service_charge, collection_amount, offer_rate_coll, cross_rate, cost_rate_coll, cost_rate_pay, quote_issued_at, valid_until) to prevent accidental JPA UPDATE. TransactionEvent @Entity: maps V11 columns; detail column mapped as String via @Type or @Column(columnDefinition=jsonb).
**Steps:** Create services/transaction-mgmt/src/main/java/com/gme/pay/txnmgmt/domain/Transaction.java @Entity @Table(name=transaction) with all column mappings; Add @Column(updatable=false) to all 13 rate-lock columns; Create services/transaction-mgmt/src/main/java/com/gme/pay/txnmgmt/domain/TransactionEvent.java @Entity @Table(name=transaction_event) with txn_id as @Column(name=txn_id), step, event_type, occurred_at, duration_ms, detail as String; Create TransactionRepository extends JpaRepository<Transaction,Long> with Optional<Transaction> findByTxnRef(String txnRef), Page<Transaction> findByPartnerIdAndStatusAndCommittedAtBetween(...); Create TransactionEventRepository extends JpaRepository<TransactionEvent,Long> with List<TransactionEvent> findByTxnIdOrderByStep(Long txnId); Verify @GeneratedValue(strategy=IDENTITY) on id fields of both entities
**Deliverable:** services/transaction-mgmt/src/main/java/com/gme/pay/txnmgmt/domain/Transaction.java, TransactionEvent.java, TransactionRepository.java, TransactionEventRepository.java
**Acceptance / logic checks:**
- Save a Transaction then reload by txn_ref: all BigDecimal fields equal saved values (BigDecimal.compareTo = 0, no floating-point drift)
- Attempt Spring Data save with modified collection_usd on a loaded entity raises exception (updatable=false)
- findByTxnIdOrderByStep returns events in ascending step order for an 8-event fixture
- @DataJpaTest slice with Testcontainers PostgreSQL 16 passes all repository tests
- detail column round-trips arbitrary JSON string without truncation
**Depends on:** 3.3-T05

### 3.3-T10 — Implement TransactionService: issue rate quote and record step 1 event  _(40 min)_
**Context:** Module: services/transaction-mgmt. @Service TransactionService. issueRateQuote() persists a rate_quote row and appends transaction_event step=1 RATE_QUOTE_ISSUED. Two sub-steps must share one @Transactional. detail JSONB for step 1: {quote_ref, offer_rate_coll, valid_until, collection_usd, send_amount}. valid_until = quote_issued_at + rule.rate_quote_ttl_seconds (default 300). For same-ccy short-circuit (is_same_ccy_shortcircuit=TRUE): USD-pool columns NULL, offer_rate_coll NULL, collection_amount = target_payout + service_charge. Return RateQuoteResponse (from lib-api-contracts) with all quote fields.
**Steps:** Create services/transaction-mgmt/src/main/java/com/gme/pay/txnmgmt/service/TransactionService.java @Service; Implement RateQuoteResponse issueRateQuote(IssueRateQuoteRequest req): persist RateQuote entity, build TransactionEvent with step=1, event_type=RATE_QUOTE_ISSUED, detail JSON, occurred_at=NOW(), duration_ms=NULL; Wrap in @Transactional(isolation=READ_COMMITTED); Handle same-ccy short-circuit: if req.isSameCcyShortcircuit(), set all USD-pool fields to null on RateQuote entity; Validate target_payout > 0 and service_charge >= 0; throw ValidationException with lib-errors error code if violated; Return RateQuoteResponse mapping all quote fields
**Deliverable:** TransactionService.issueRateQuote() in services/transaction-mgmt/src/main/java/com/gme/pay/txnmgmt/service/TransactionService.java
**Acceptance / logic checks:**
- issueRateQuote with cross-border request: rate_quote row created, step=1 event row created, both in same DB transaction
- issueRateQuote with same-ccy request: collection_usd IS NULL in persisted rate_quote, collection_amount = target_payout + service_charge
- target_payout = 0 throws ValidationException before any DB write
- quote_ref is unique UUID; two calls produce different quote_refs
- @DataJpaTest + Testcontainers: both rate_quote and transaction_event rows committed atomically
**Depends on:** 3.3-T09

### 3.3-T11 — Implement TransactionService: initiate transaction and record step 2 event  _(40 min)_
**Context:** Module: services/transaction-mgmt. initiateTransaction() is called when partner calls POST /v1/payments. Creates transaction row with status=INITIATED linking to the rate_quote, appends step=2 PAYMENT_INITIATED event. Validates: (1) rate_quote.valid_until > NOW(), otherwise throw QuoteExpiredException (lib-errors code QUOTE_EXPIRED); (2) rate_quote.is_used = FALSE, otherwise throw QuoteAlreadyUsedException. duration_ms for step 2 = occurred_at(step2) - occurred_at(step1) in milliseconds. detail JSONB: {txn_ref, partner_id, payment_mode, direction}. Returns txn_ref.
**Steps:** Add String initiateTransaction(InitiateTransactionRequest req) to TransactionService; Load rate_quote by quote_ref; validate valid_until > Instant.now(); throw QuoteExpiredException if expired; Validate is_used = FALSE; throw QuoteAlreadyUsedException if already used; Create Transaction entity: copy partner_id, scheme_id, rule_id, payment_mode, direction, is_same_ccy_shortcircuit from request/rule; set status=INITIATED, txn_ref=UUID; Persist Transaction; compute duration_ms from step1.occurred_at to NOW(); persist TransactionEvent step=2 PAYMENT_INITIATED with duration_ms and detail JSON; All in @Transactional(isolation=READ_COMMITTED); return txn_ref
**Deliverable:** TransactionService.initiateTransaction() method
**Acceptance / logic checks:**
- Expired quote (valid_until = 1 second ago) throws QuoteExpiredException; no transaction row created
- is_used=TRUE quote throws QuoteAlreadyUsedException before DB write
- Successful call: transaction row status=INITIATED, step=2 event with duration_ms >= 0
- Duplicate txn_ref (same idempotency key called twice) raises DataIntegrityViolationException on UNIQUE constraint
- @DataJpaTest + Testcontainers: both transaction and transaction_event rows committed atomically
**Depends on:** 3.3-T10

### 3.3-T12 — Implement TransactionService: prefund deduction (step 3) with SELECT FOR UPDATE  _(50 min)_
**Context:** Module: services/transaction-mgmt. deductPrefunding() is OVERSEAS-only (skip for LOCAL partners). Must atomically deduct collection_usd from prefunding_account.balance using SELECT ... FOR UPDATE (no app-level locking per DAT-03 and STACK.md). If balance < collection_usd: throw InsufficientPrefundingException (lib-errors code INSUFFICIENT_PREFUNDING) and do NOT call the scheme. Flow: lock prefunding_account row FOR UPDATE, verify balance >= collection_usd, deduct, insert prefunding_ledger_entry (entry_type=DEBIT_PAYMENT, amount=collection_usd, balance_after=new balance), update transaction.status=PREFUND_DEDUCTED, insert step=3 PREFUND_DEDUCTED event with detail={deducted_usd, balance_after_usd}, publish PrefundDeductedEvent to outbox. All in one @Transactional.
**Steps:** Add deductPrefunding(String txnRef, Long partnerId, BigDecimal collectionUsd) to TransactionService; Use @Lock(LockModeType.PESSIMISTIC_WRITE) on PrefundingAccountRepository.findByPartnerId() or native query SELECT ... FROM prefunding_account WHERE partner_id=? FOR UPDATE; Check account.balance.compareTo(collectionUsd) >= 0; if not throw InsufficientPrefundingException with current balance and required amount; Deduct: account.setBalance(account.getBalance().subtract(collectionUsd)); save; insert PrefundingLedgerEntry DEBIT_PAYMENT with balance_after; Update transaction status to PREFUND_DEDUCTED; insert TransactionEvent step=3 with detail JSON; Call eventPublisher.publish(new PrefundDeductedEvent(...)) within same @Transactional; wrap all in @Transactional(isolation=READ_COMMITTED)
**Deliverable:** TransactionService.deductPrefunding() method
**Acceptance / logic checks:**
- Concurrent deduction test (two @Async threads, both deductPrefunding for same partner with balance covering only one): exactly one succeeds, other throws InsufficientPrefundingException; Testcontainers Postgres required
- InsufficientPrefundingException thrown when balance=999.9900 and required=1000.0000
- prefunding_ledger_entry.balance_after = account.balance after successful deduction (to 4 decimal places)
- PrefundDeductedEvent row in domain_event_outbox with status=PENDING within same transaction
- Full rollback test: force RuntimeException after deduction, verify balance unchanged and no ledger entry
**Depends on:** 3.3-T11, 3.3-T07

### 3.3-T13 — Implement TransactionService: commit transaction with pool identity check (step 6)  _(45 min)_
**Context:** Module: services/transaction-mgmt. commitTransaction() copies all USD-pool values and derived rates from rate_quote to transaction, sets rate_locked_at=committed_at=NOW(), then verifies the pool identity invariant before persisting. Pool identity: collection_usd - collection_margin_usd - payout_margin_usd = payout_usd_cost within tolerance 0.01 USD. Check: diff.abs().compareTo(new BigDecimal(0.01)) <= 0. If check fails: throw PoolIdentityViolationException (lib-errors code POOL_IDENTITY_FAILURE); do NOT commit. For same-ccy short-circuit (is_same_ccy_shortcircuit=TRUE): skip pool check; collection_amount = target_payout + service_charge. Mark rate_quote.is_used=TRUE. Insert step=6 TRANSACTION_COMMITTED event with detail containing all 8 locked rate fields. Publish TransactionCommittedEvent to outbox.
**Steps:** Add commitTransaction(String txnRef) to TransactionService; Load transaction by txn_ref and linked rate_quote; validate rate_quote.valid_until > NOW(); If NOT is_same_ccy_shortcircuit: compute diff = collection_usd.subtract(collection_margin_usd).subtract(payout_margin_usd).subtract(payout_usd_cost); if diff.abs() > 0.01 throw PoolIdentityViolationException; Copy all 13 rate-lock fields from rate_quote to transaction; set rate_locked_at=NOW(), committed_at=NOW(); Persist transaction; mark rate_quote.is_used=TRUE; insert TransactionEvent step=6 TRANSACTION_COMMITTED with detail JSON of all locked rate fields; Call eventPublisher.publish(new TransactionCommittedEvent(...)) within same @Transactional
**Deliverable:** TransactionService.commitTransaction() method
**Acceptance / logic checks:**
- Pool identity violation (diff=0.02 USD): PoolIdentityViolationException thrown, no transaction update committed, is_used stays FALSE
- Pool identity passes (diff=0.005 USD, within 0.01 tolerance): transaction committed, rate_locked_at set, all 13 rate-lock fields populated
- After commitTransaction, JPA attempt to update collection_usd raises exception (@Column updatable=false)
- Same-ccy path: is_same_ccy_shortcircuit=TRUE, pool columns NULL, collection_amount = target_payout + service_charge after commit
- TransactionCommittedEvent row in domain_event_outbox with PENDING status and correct payload
**Depends on:** 3.3-T12, 3.3-T07

### 3.3-T14 — Implement TransactionService.recordEvent() for steps 4, 5, 7, 8  _(30 min)_
**Context:** Module: services/transaction-mgmt. recordEvent() is called by Payment Executor (steps 4,5) and Notification service (steps 7,8) to append trail entries. Signature: void recordEvent(String txnRef, int step, String eventType, Integer durationMs, Map<String,Object> detail). Validates: step in 1-8 (throw InvalidEventStepException), no existing row for (txn_id,step) (throw DuplicateEventStepException - UNIQUE constraint also catches at DB). For step 5: if detail contains key scheme_ref, update transaction.scheme_ref from detail value. For step 8 with WEBHOOK_FAILED (event_type=WEBHOOK_DELIVERED, detail.failed=true): record as step 8 with failure detail. occurred_at=NOW() server-side.
**Steps:** Add void recordEvent(String txnRef, int step, String eventType, Integer durationMs, Map<String,Object> detail) to TransactionService; Validate step between 1 and 8; throw InvalidEventStepException with step value if out of range; Check TransactionEventRepository.existsByTxnIdAndStep(txnId, step); throw DuplicateEventStepException if true; Build TransactionEvent entity, serialize detail map to JSON via ObjectMapper, set occurred_at=Instant.now(); If step=5 and detail.containsKey(scheme_ref): load transaction, set scheme_ref, save; Annotate with @Transactional(propagation=REQUIRED)
**Deliverable:** TransactionService.recordEvent() method
**Acceptance / logic checks:**
- recordEvent step=4 with detail={endpoint:ZeroPay}: persists row, SELECT by txn_id+step=4 returns correct detail JSON via ->> operator
- recordEvent step=9 throws InvalidEventStepException without any DB insert
- recordEvent called twice for same txn+step=4: second call throws DuplicateEventStepException
- transaction.scheme_ref updated when step=5 detail contains scheme_ref=ZP20260601001
- duration_ms NULL accepted (step 1 has no previous step); duration_ms=-1 rejected by DB CHECK constraint
**Depends on:** 3.3-T09

### 3.3-T15 — Implement TransactionService.getEventTrail() and TransactionEventDto in lib-api-contracts  _(30 min)_
**Context:** Module: services/transaction-mgmt and lib-api-contracts. getEventTrail() returns the ordered 8-step trail for Admin UI and partner-facing views. Signature: List<TransactionEventDto> getEventTrail(String txnRef). Loads all transaction_event rows ordered by step; absent steps are gaps (do not fabricate). For LOCAL partners step 3 will be absent - return list without it. TransactionEventDto is a Java record in lib-api-contracts: Long id, int step, String eventType, Instant occurredAt, Integer durationMs, String detailJson. Also add getTransactionSummary(String txnRef) returning TransactionSummaryDto with all locked rate fields for Admin transaction detail view.
**Steps:** Create lib-api-contracts/src/main/java/com/gme/pay/contracts/transaction/TransactionEventDto.java as Java record; Create lib-api-contracts/src/main/java/com/gme/pay/contracts/transaction/TransactionSummaryDto.java as Java record with all summary and rate-lock fields; Add List<TransactionEventDto> getEventTrail(String txnRef) to TransactionService: load transaction by txnRef (throw TransactionNotFoundException if absent), load events via findByTxnIdOrderByStep, map to DTOs; Add TransactionSummaryDto getTransactionSummary(String txnRef): load transaction, map all fields to DTO; Add @Transactional(readOnly=true) on both read methods; Return empty list (not null) if transaction exists but has no events yet
**Deliverable:** lib-api-contracts/src/main/java/com/gme/pay/contracts/transaction/TransactionEventDto.java and TransactionService read methods
**Acceptance / logic checks:**
- getEventTrail for OVERSEAS transaction with steps 1-8: returns list size=8, steps in order 1..8
- getEventTrail for LOCAL transaction missing step 3: returns list size=7, no element with step=3
- getEventTrail for unknown txnRef throws TransactionNotFoundException
- Step 6 dto.detailJson contains collection_usd and offer_rate_coll keys
- getEventTrail returns empty list (not null) for transaction with zero events
**Depends on:** 3.3-T14

### 3.3-T16 — Expose TransactionInternalController @RestController in services/transaction-mgmt  _(45 min)_
**Context:** Module: services/transaction-mgmt. Internal REST API consumed by Payment Executor and Notification services (not partner-facing). Endpoints: POST /internal/v1/transactions/quote (issue quote + step 1), POST /internal/v1/transactions (initiate + step 2), POST /internal/v1/transactions/{txnRef}/prefund-deduct (step 3), POST /internal/v1/transactions/{txnRef}/commit (step 6), POST /internal/v1/transactions/{txnRef}/events (steps 4,5,7,8), GET /internal/v1/transactions/{txnRef}/trail (get event trail). Spring Security: @PreAuthorize(hasRole(INTERNAL_SERVICE)) on all endpoints. Request/response DTOs from lib-api-contracts. lib-errors ErrorResponse on failures.
**Steps:** Create services/transaction-mgmt/src/main/java/com/gme/pay/txnmgmt/api/TransactionInternalController.java @RestController @RequestMapping(/internal/v1/transactions); Implement POST /quote, POST /, POST /{txnRef}/prefund-deduct, POST /{txnRef}/commit each delegating to TransactionService; return 201 for creates, 200 for updates; Implement POST /{txnRef}/events delegating to recordEvent with request body fields; Implement GET /{txnRef}/trail delegating to getEventTrail, returning List<TransactionEventDto>; Add @Valid on all request bodies; @ExceptionHandler methods converting QuoteExpiredException->422, TransactionNotFoundException->404, DuplicateEventStepException->409, PoolIdentityViolationException->422, InsufficientPrefundingException->422 using lib-errors error codes; Add SLF4J MDC with txn_ref on all mutating endpoints; @PreAuthorize(hasRole(INTERNAL_SERVICE)) on class
**Deliverable:** services/transaction-mgmt/src/main/java/com/gme/pay/txnmgmt/api/TransactionInternalController.java
**Acceptance / logic checks:**
- POST /quote returns 201 with quote_ref and valid_until in response body
- POST /{txnRef}/commit returns 200 with collection_usd, offer_rate_coll, rate_locked_at in response
- POST /{txnRef}/prefund-deduct with insufficient balance returns 422 with error code INSUFFICIENT_PREFUNDING
- POST /{txnRef}/events with step=9 returns 400 with INVALID_EVENT_STEP error code
- Unauthenticated request returns 401 (WebMvcTest with Spring Security test)
- GET /{txnRef}/trail returns 200 with ordered list of event DTOs
**Depends on:** 3.3-T15

### 3.3-T17 — Unit test: pool identity invariant and same-currency short-circuit vectors  _(50 min)_
**Context:** Module: services/transaction-mgmt. Dedicated JUnit 5 test class for the mathematical invariants enforced at commitTransaction(). Uses @DataJpaTest + Testcontainers PostgreSQL 16. Test vectors use exact BigDecimal values. Vector A (cross-border pass): target_payout=45000.0000 KRW, cost_rate_pay=1380.00000000 (usd_krw), m_a=0.010000, m_b=0.015000; expected payout_usd_cost=32.608695..., collection_usd=33.877490..., pool identity diff < 0.01. Vector B (pool identity fail): manually corrupt collection_usd to create diff=0.02. Vector C (same-ccy short-circuit): target_payout=50000.0000, service_charge=500.0000, is_same_ccy=TRUE; expected collection_amount=50500.0000, pool cols NULL.
**Steps:** Create services/transaction-mgmt/src/test/java/com/gme/pay/txnmgmt/service/TransactionServicePoolIdentityTest.java; Seed DB with partner, scheme, rule (m_a=0.01, m_b=0.015), prefunding_account, treasury_rate usd_krw=1380 using @BeforeEach; Test vector A: issue quote with correct values, initiate, deduct, commit; assert committed_at set, all 13 rate-lock fields non-null, offer_rate_coll = send_amount / (collection_usd - collection_margin_usd); Test vector B: issue quote with corrupted collection_usd (diff=0.02), call commitTransaction, assert PoolIdentityViolationException thrown and transaction row not committed; Test vector C: use same-ccy rule, issue quote, commit; assert collection_amount=50500.0000 and collection_usd IS NULL in DB; Use @Testcontainers @Container PostgreSQLContainer<> for real PostgreSQL 16
**Deliverable:** services/transaction-mgmt/src/test/java/com/gme/pay/txnmgmt/service/TransactionServicePoolIdentityTest.java
**Acceptance / logic checks:**
- Vector A: committed_at non-null, offer_rate_coll.subtract(send_amount.divide(collection_usd.subtract(collection_margin_usd), 8, HALF_UP)).abs() < 0.000001
- Vector B: PoolIdentityViolationException thrown, SELECT transaction WHERE txn_ref=... shows no committed_at change
- Vector C: collection_amount=50500.0000 in persisted row, collection_usd IS NULL
- All 3 test vectors pass with ./gradlew :services:transaction-mgmt:test
- No floating-point types used: all assertions via BigDecimal.compareTo()
**Depends on:** 3.3-T13

### 3.3-T18 — Unit test: transactional outbox atomicity with Testcontainers PostgreSQL  _(45 min)_
**Context:** Module: services/transaction-mgmt. JUnit 5 tests verifying outbox events are written atomically with business state changes and that the OutboxPoller dispatches correctly. Uses @SpringBootTest + Testcontainers PostgreSQL 16, no Kafka. Scenarios: (A) commitTransaction publishes TransactionCommittedEvent to outbox in same transaction, (B) forced PoolIdentityViolationException rolls back both business state and outbox row, (C) OutboxPoller moves row PENDING->DISPATCHED, (D) failed dispatch after 3 retries sets FAILED.
**Steps:** Create services/transaction-mgmt/src/test/java/com/gme/pay/txnmgmt/outbox/OutboxAtomicityTest.java; Test A: call commitTransaction with valid vector, query domain_event_outbox: exactly 1 row status=PENDING with event_type=TransactionCommittedEvent; Test B: call commitTransaction with pool identity violation, query domain_event_outbox: exactly 0 rows (rollback atomicity); Test C: insert a PENDING outbox row manually, invoke OutboxPoller.pollAndDispatch(), query row: status=DISPATCHED and dispatched_at IS NOT NULL; Test D: configure mock dispatch to always throw; call pollAndDispatch 3 times; verify status=FAILED and retry_count=3; No Kafka container needed; disable outbox.poller.enabled for tests A and B to prevent background interference
**Deliverable:** services/transaction-mgmt/src/test/java/com/gme/pay/txnmgmt/outbox/OutboxAtomicityTest.java
**Acceptance / logic checks:**
- Test A: exactly 1 outbox row with correct event_type and PENDING status after successful commit
- Test B: 0 outbox rows after rolled-back commit (atomicity proven by Testcontainers real DB)
- Test C: outbox row status=DISPATCHED, dispatched_at IS NOT NULL after one poller invocation
- Test D: retry_count=3, status=FAILED, last_error IS NOT NULL
- All tests pass with ./gradlew :services:transaction-mgmt:test - zero Kafka dependency
**Depends on:** 3.3-T08, 3.3-T13

### 3.3-T19 — Unit test: 8-step event trail completeness for OVERSEAS and LOCAL partner flows  _(40 min)_
**Context:** Module: services/transaction-mgmt. JUnit 5 test covering the full 8-step lifecycle using TransactionService methods against Testcontainers PostgreSQL 16. OVERSEAS INBOUND MPM flow: steps 1,2,3,4,5,6,7,8 all recorded. LOCAL DOMESTIC flow: steps 1,2,4,5,6,7,8 (no step 3). Verify getEventTrail ordering and duration_ms. Use small Thread.sleep(10) between steps to ensure measurable duration_ms values.
**Steps:** Create services/transaction-mgmt/src/test/java/com/gme/pay/txnmgmt/service/TransactionEventTrailTest.java; Seed OVERSEAS partner fixture; call issueRateQuote, initiateTransaction, deductPrefunding, recordEvent(4), recordEvent(5), commitTransaction, recordEvent(7), recordEvent(8); Assert getEventTrail returns 8 DTOs in order step 1..8 with correct event_type values; Seed LOCAL partner fixture; call same sequence skipping deductPrefunding; assert getEventTrail returns 7 DTOs with no step=3; Assert duration_ms for step 2 = approx ms between step1.occurred_at and step2.occurred_at (within 50ms tolerance); Assert step 6 detailJson contains keys collection_usd and offer_rate_coll
**Deliverable:** services/transaction-mgmt/src/test/java/com/gme/pay/txnmgmt/service/TransactionEventTrailTest.java
**Acceptance / logic checks:**
- OVERSEAS flow: trail size=8, all event_type values match canonical names from SEC-09
- LOCAL flow: trail size=7, no DTO with step=3
- duration_ms for every step 2-8 is >= 0
- Step 6 detailJson round-trips to Map containing collection_usd non-null
- Duplicate recordEvent for step=4 throws DuplicateEventStepException without corrupting existing trail
**Depends on:** 3.3-T15

### 3.3-T20 — Integration test: full 8-step trail HTTP flow via TransactionInternalController  _(55 min)_
**Context:** Module: services/transaction-mgmt. @SpringBootTest(webEnvironment=RANDOM_PORT) end-to-end integration test exercising all 8 steps via HTTP through TransactionInternalController against Testcontainers PostgreSQL 16. OVERSEAS INBOUND MPM transaction. No Kafka, no external scheme. Seed: partner (OVERSEAS), scheme (ZEROPAY), rule (INBOUND, m_a=0.010000, m_b=0.015000), prefunding_account (balance=10000.0000 USD), treasury_rate (ccy_pair=usd_krw, rate=1380.00000000).
**Steps:** Create services/transaction-mgmt/src/test/java/com/gme/pay/txnmgmt/TransactionFullTrailIT.java with @SpringBootTest and @Testcontainers; Seed DB fixtures via TransactionRepository/JdbcTemplate in @BeforeEach; POST /internal/v1/transactions/quote with target_payout=45000 KRW; assert 201, quote_ref non-null, valid_until = ~5 min ahead; POST /internal/v1/transactions; assert 201, txn_ref non-null, status=INITIATED in DB; POST /{txnRef}/prefund-deduct; assert 200, verify prefunding_account.balance reduced by collection_usd, ledger entry created; POST /{txnRef}/commit; assert 200, verify rate_locked_at set, all 8 rate-lock fields non-null, outbox PENDING rows exist; POST /{txnRef}/events for steps 4,5,7,8; GET /{txnRef}/trail; assert 8-element list steps 1-8
**Deliverable:** services/transaction-mgmt/src/test/java/com/gme/pay/txnmgmt/TransactionFullTrailIT.java
**Acceptance / logic checks:**
- All 8 HTTP calls return 2xx status codes
- transaction_event table has exactly 8 rows for txn_ref, steps 1-8
- prefunding_account.balance = 10000.0000 - collection_usd (to 4 decimal places) after deduction
- domain_event_outbox contains TransactionCommittedEvent and PrefundDeductedEvent rows both PENDING
- GET /trail returns list with step 6 detailJson containing offer_rate_coll matching expected value from 5-step formula
**Depends on:** 3.3-T16, 3.3-T18

### 3.3-T21 — Configure services/transaction-mgmt Gradle module and application.yml  _(30 min)_
**Context:** Module: services/transaction-mgmt. Ensure the Gradle sub-module is declared in the root settings.gradle and the module build.gradle has all required dependencies per STACK.md: spring-boot-starter-data-jpa, spring-boot-starter-web, spring-boot-starter-security, flyway-core, postgresql (runtime), lib-money, lib-errors, lib-api-contracts, lib-events, testcontainers-postgresql (testImplementation), testcontainers-junit-jupiter (testImplementation), spring-security-test (testImplementation). application.yml: spring.flyway.locations=classpath:db/migration, spring.jpa.hibernate.ddl-auto=validate, outbox.poller.enabled=true.
**Steps:** Verify or add include(services:transaction-mgmt) in root settings.gradle; Create/update services/transaction-mgmt/build.gradle with all dependencies listed in context; set Java 21 toolchain; Create services/transaction-mgmt/src/main/resources/application.yml with placeholder datasource, flyway config (baseline-on-migrate=true, baseline-version=1), jpa ddl-auto=validate; Add test application.yml in src/test/resources with outbox.poller.enabled=false and a Testcontainers datasource placeholder; Run ./gradlew :services:transaction-mgmt:compileJava to verify zero compilation errors; Run ./gradlew :services:transaction-mgmt:test to confirm Testcontainers-backed tests pass
**Deliverable:** services/transaction-mgmt/build.gradle and services/transaction-mgmt/src/main/resources/application.yml
**Acceptance / logic checks:**
- ./gradlew :services:transaction-mgmt:build succeeds with zero compilation errors
- All lib-* project dependencies resolve correctly (project(:lib-money) etc.)
- spring.jpa.hibernate.ddl-auto=validate passes against schema created by Flyway V9-V13 in order
- No duplicate Flyway migration version numbers in V9-V13
- Flyway migrate runs all 5 migrations (V9,V10,V11,V12,V13) in version order without error
**Depends on:** 3.3-T05


## WBS 5.1 — Transaction state machine
### 5.1-T01 — Define TransactionStatus enum with all valid states  _(20 min)_
**Context:** WBS 5.1. The Transaction Orchestrator owns the state machine for every payment. States (from SAD-02 §5.2 and DAT-03 §11.3): QUOTED, PENDING_DEBIT, DEBITED, SCHEME_SENT, APPROVED, UNCERTAIN, FAILED, REVERSED, REFUNDED. QUOTED = rate quote issued; PENDING_DEBIT = CommitTransaction received, prefunding deduction in progress (OVERSEAS only); DEBITED = prefunding deducted or skipped (LOCAL); SCHEME_SENT = adapter call dispatched; APPROVED = scheme confirmed success; UNCERTAIN = no scheme response within SLA; FAILED = terminal failure; REVERSED = same-day cancel post-APPROVED; REFUNDED = post-settlement refund. Terminal states: FAILED, REVERSED, REFUNDED.
**Steps:** Create domain/model/TransactionStatus.java (Java enum) with values: QUOTED, PENDING_DEBIT, DEBITED, SCHEME_SENT, APPROVED, UNCERTAIN, FAILED, REVERSED, REFUNDED.; Add a boolean isTerminal() helper that returns true for FAILED, REVERSED, REFUNDED only.; Add Javadoc on each constant citing the SAD-02 §5.2 meaning.; Annotate with @JsonValue so JSON serialisation uses the enum name as-is.
**Deliverable:** domain/model/TransactionStatus.java — enum with 9 values and isTerminal() method
**Acceptance / logic checks:**
- TransactionStatus.values().length == 9.
- isTerminal() returns true for FAILED, REVERSED, REFUNDED and false for the other six.
- Jackson serialises QUOTED to the JSON string "QUOTED" (not an ordinal or lowercase).
- PENDING_DEBIT and DEBITED are distinct constants (typo regression guard).

### 5.1-T02 — Define permitted transition table as a static map  _(25 min)_
**Context:** WBS 5.1. Only specific state-to-state transitions are legal (SAD-02 §5.2 state diagram): QUOTED->PENDING_DEBIT (OVERSEAS CommitTransaction); QUOTED->DEBITED (LOCAL CommitTransaction, no prefund); QUOTED->FAILED (TTL expired or validation failure); PENDING_DEBIT->DEBITED (prefund deducted); PENDING_DEBIT->FAILED (insufficient prefunding); DEBITED->SCHEME_SENT (scheme adapter dispatched); SCHEME_SENT->APPROVED (scheme success); SCHEME_SENT->FAILED (scheme reject/error); SCHEME_SENT->UNCERTAIN (timeout); UNCERTAIN->APPROVED (batch reconciliation success); UNCERTAIN->FAILED (batch reconciliation failure); APPROVED->REVERSED (same-day cancel); APPROVED->REFUNDED (post-settlement refund). Terminal states have no outgoing transitions. All other pairs are forbidden.
**Steps:** Create domain/statemachine/TransactionTransitions.java with a private static final Map<TransactionStatus, Set<TransactionStatus>> ALLOWED_TRANSITIONS.; Populate the map exactly as the 13 pairs above; terminal states map to an empty set.; Add a static boolean isAllowed(TransactionStatus from, TransactionStatus to) helper.; Add a static Set<TransactionStatus> allowedFrom(TransactionStatus from) helper.; Unit-test file referenced in 5.1-T09 will verify this map.
**Deliverable:** domain/statemachine/TransactionTransitions.java with ALLOWED_TRANSITIONS map and two static helpers
**Acceptance / logic checks:**
- isAllowed(QUOTED, PENDING_DEBIT) == true; isAllowed(QUOTED, DEBITED) == true.
- isAllowed(PENDING_DEBIT, APPROVED) == false (not a permitted pair).
- isAllowed(FAILED, QUOTED) == false (no transitions out of terminal state).
- allowedFrom(SCHEME_SENT) returns exactly {APPROVED, FAILED, UNCERTAIN}.
- Map contains entries for all 9 states (including terminal states mapping to empty sets).
**Depends on:** 5.1-T01

### 5.1-T03 — Create TransitionBlockedException for illegal state transitions  _(20 min)_
**Context:** WBS 5.1. When code attempts a transition not in the ALLOWED_TRANSITIONS map (5.1-T02), a typed exception must be thrown rather than a generic RuntimeException. This exception is caught by the orchestrator and translated to an API error. It must carry from, to, and txn_ref fields so the caller can log structured context. It is NOT an end-user-facing error — it indicates a programming bug or race condition.
**Steps:** Create domain/statemachine/TransitionBlockedException.java extending RuntimeException.; Fields: TransactionStatus from, TransactionStatus to, String txnRef.; Constructor: TransitionBlockedException(String txnRef, TransactionStatus from, TransactionStatus to).; Override getMessage() to return a string containing txnRef, from, and to.; Ensure all three fields are accessible via getters.
**Deliverable:** domain/statemachine/TransitionBlockedException.java
**Acceptance / logic checks:**
- new TransitionBlockedException("txn_abc", QUOTED, APPROVED).getMessage() contains "QUOTED", "APPROVED", and "txn_abc".
- getFrom(), getTo(), getTxnRef() return the values passed to the constructor.
- Class extends RuntimeException (unchecked).
- No swallowing of the original cause — constructor variant accepting a Throwable cause is also present.
**Depends on:** 5.1-T01

### 5.1-T04 — Implement TransactionStateMachine.transition() with guard and persistence  _(55 min)_
**Context:** WBS 5.1. The Transaction Orchestrator is the only component that may change a transaction's status. The transition must be atomic: (1) verify the transition is allowed via TransactionTransitions.isAllowed(); (2) SELECT FOR UPDATE the transaction row to prevent concurrent transitions on the same txn; (3) assert current DB status equals expected from-state (optimistic check); (4) UPDATE status in the transaction table; (5) append a transaction_event row (step number derived from new status, see 5.1-T06); (6) return the updated entity. If the transition is not allowed, throw TransitionBlockedException. If the DB current status differs from expected (concurrent update), throw a ConcurrentTransitionException (new, see 5.1-T05).
**Steps:** Create service/statemachine/TransactionStateMachine.java with method: TransactionEntity transition(String txnRef, TransactionStatus expectedFrom, TransactionStatus to, TransitionContext ctx).; SELECT the transaction row FOR UPDATE inside a @Transactional method.; Assert entity.status == expectedFrom; else throw ConcurrentTransitionException.; Call TransactionTransitions.isAllowed(expectedFrom, to); else throw TransitionBlockedException.; UPDATE transaction.status = to, transaction.updated_at = now(); set completed_at when transitioning to APPROVED, FAILED, or REVERSED.; Delegate event appending to EventTrailService.recordStep(txn, to, ctx) (see 5.1-T06).
**Deliverable:** service/statemachine/TransactionStateMachine.java with atomic transition() method
**Acceptance / logic checks:**
- Calling transition(txnRef, QUOTED, PENDING_DEBIT, ctx) on a QUOTED row in test DB changes status to PENDING_DEBIT and appends an event row.
- Calling transition on a row already at DEBITED with expectedFrom=QUOTED throws ConcurrentTransitionException (simulates race condition).
- Calling transition(txnRef, QUOTED, APPROVED, ctx) throws TransitionBlockedException (not an allowed pair).
- completed_at is set when transitioning to APPROVED; it remains null when transitioning to DEBITED.
- The entire operation is inside a single DB transaction (rolled back on exception).
**Depends on:** 5.1-T02, 5.1-T03, 5.1-T05, 5.1-T06

### 5.1-T05 — Create ConcurrentTransitionException for stale-state conflicts  _(15 min)_
**Context:** WBS 5.1. When TransactionStateMachine.transition() reads the DB row FOR UPDATE and finds the current status differs from the expectedFrom argument (another thread already changed it), it must throw ConcurrentTransitionException rather than silently proceeding or throwing a generic error. The caller (orchestrator) retries or returns a conflict error to the partner API. Fields needed: txnRef, expectedStatus, actualStatus.
**Steps:** Create domain/statemachine/ConcurrentTransitionException.java extending RuntimeException.; Fields: String txnRef, TransactionStatus expectedStatus, TransactionStatus actualStatus.; Constructor: ConcurrentTransitionException(String txnRef, TransactionStatus expected, TransactionStatus actual).; getMessage() returns a string referencing all three fields.; Provide getters for all three fields.
**Deliverable:** domain/statemachine/ConcurrentTransitionException.java
**Acceptance / logic checks:**
- new ConcurrentTransitionException("t1", QUOTED, DEBITED).getMessage() contains "QUOTED" and "DEBITED".
- getExpectedStatus() == QUOTED, getActualStatus() == DEBITED in the above example.
- Class is unchecked (extends RuntimeException).
- Distinct from TransitionBlockedException — they carry different semantics (race vs. disallowed pair).
**Depends on:** 5.1-T01

### 5.1-T06 — Implement EventTrailService.recordStep() for 8-step event trail  _(40 min)_
**Context:** WBS 5.1 and SEC-09 §6.1. Every transaction must carry an immutable 8-step event trail stored in the transaction_event table (DAT-03 §5.3). The 8 steps and their event_type strings: (1) rate_quote_issued, (2) payment_initiated, (3) prefund_deducted (OVERSEAS only; absent for LOCAL), (4) scheme_request_sent, (5) scheme_response_received, (6) transaction_committed, (7) webhook_dispatched, (8) webhook_delivered. Columns: id BIGINT PK, txn_id FK, step INT (1-8), event_type VARCHAR(50), occurred_at TIMESTAMPTZ, duration_ms INT (ms since previous step), detail JSONB, created_at. Records are INSERT-only (never updated or deleted; 7-year retention). For LOCAL partners, step 3 row is still inserted with a note in detail: {skipped: true, reason: LOCAL_PARTNER_NO_PREFUND}.
**Steps:** Create service/audit/EventTrailService.java with method: void recordStep(long txnId, int step, String eventType, Instant occurredAt, Long previousStepOccurredAt, Map<String,Object> detail).; Compute duration_ms = (previousStepOccurredAt != null) ? ChronoUnit.MILLIS.between(previousStepOccurredAt, occurredAt) : null.; INSERT into transaction_event: txn_id, step, event_type, occurred_at, duration_ms, detail (serialised as JSONB), created_at=now().; Do not update or delete any existing event rows (append-only).; Add overloaded convenience method recordStepNow(long txnId, int step, String eventType, Map<String,Object> detail) that uses Instant.now() for occurred_at.
**Deliverable:** service/audit/EventTrailService.java with append-only recordStep()
**Acceptance / logic checks:**
- Calling recordStep for steps 1..8 inserts 8 rows; no UPDATE statements are issued.
- duration_ms for step 1 is null; for step 2 it equals the ms elapsed since step 1 occurred_at.
- Attempting to INSERT a duplicate (txn_id, step) pair raises a DB unique constraint violation (the table must have a UNIQUE(txn_id, step) constraint — verify migration exists).
- detail JSONB is stored and retrievable as a Java Map.
- For a LOCAL partner step 3 row: detail contains key skipped=true.
**Depends on:** 5.1-T07

### 5.1-T07 — Migration: add UNIQUE(txn_id, step) constraint to transaction_event  _(20 min)_
**Context:** WBS 5.1. The transaction_event table (DAT-03 §5.3) must enforce exactly one row per (transaction, step) pair to guarantee the 8-step trail is non-duplicate and append-only. Without this constraint, a retry bug could insert duplicate step rows. Columns already exist (from DAT-03 migration): id, txn_id, step, event_type, occurred_at, duration_ms, detail, created_at. This ticket adds only the missing constraint and a covering index.
**Steps:** Create migration V5_1_001__transaction_event_unique_step.sql (Flyway naming).; ADD CONSTRAINT uq_txn_event_step UNIQUE (txn_id, step) — ensures no duplicate step per transaction.; Add index idx_txn_event_txn_id ON transaction_event(txn_id) if not already present (for lookup by txn_id).; Run migration against local dev DB on port 5433 and verify with \d transaction_event.
**Deliverable:** Flyway migration V5_1_001__transaction_event_unique_step.sql applied to dev DB
**Acceptance / logic checks:**
- \d transaction_event shows constraint uq_txn_event_step on (txn_id, step).
- Inserting two rows with same txn_id=1 and step=1 raises a unique violation.
- Inserting txn_id=1 step=1 and txn_id=1 step=2 succeeds (different steps).
- Inserting txn_id=1 step=1 and txn_id=2 step=1 succeeds (different transactions).

### 5.1-T08 — Migration: add status CHECK constraint to transaction table  _(15 min)_
**Context:** WBS 5.1. The transaction.status column (DAT-03 §5.2) must be constrained to the 9 valid state-machine values: QUOTED, PENDING_DEBIT, DEBITED, SCHEME_SENT, APPROVED, UNCERTAIN, FAILED, REVERSED, REFUNDED. Without a DB CHECK constraint, a code bug could store an arbitrary string. This migration adds the constraint without touching other columns.
**Steps:** Create migration V5_1_002__transaction_status_check.sql.; ALTER TABLE transaction ADD CONSTRAINT chk_txn_status CHECK (status IN ('QUOTED','PENDING_DEBIT','DEBITED','SCHEME_SENT','APPROVED','UNCERTAIN','FAILED','REVERSED','REFUNDED')).; Run on local dev DB (port 5433).; Verify constraint name appears in \d transaction.
**Deliverable:** Flyway migration V5_1_002__transaction_status_check.sql applied to dev DB
**Acceptance / logic checks:**
- INSERT with status='QUOTED' succeeds.
- INSERT with status='INVALID_STATUS' raises a check-constraint violation.
- All 9 valid state names can be inserted without error.
- Migration is idempotent when run twice (Flyway versioned migration — second run is a no-op).

### 5.1-T09 — Unit tests for TransactionTransitions allowed/forbidden pairs  _(35 min)_
**Context:** WBS 5.1. The transition table defined in TransactionTransitions.java (5.1-T02) is the ground truth for legal transitions. This ticket is an exhaustive unit-test suite. Allowed pairs (13 total): QUOTED->PENDING_DEBIT, QUOTED->DEBITED, QUOTED->FAILED, PENDING_DEBIT->DEBITED, PENDING_DEBIT->FAILED, DEBITED->SCHEME_SENT, SCHEME_SENT->APPROVED, SCHEME_SENT->FAILED, SCHEME_SENT->UNCERTAIN, UNCERTAIN->APPROVED, UNCERTAIN->FAILED, APPROVED->REVERSED, APPROVED->REFUNDED. All other pairs are forbidden (9x9=81 minus 13 = 68 forbidden pairs, plus self-transitions = all forbidden). Terminal states: FAILED, REVERSED, REFUNDED have zero outgoing.
**Steps:** Create test class TransactionTransitionsTest.java.; Parameterised test @AllowedTransitions: for each of the 13 allowed pairs, assert isAllowed(from, to) == true.; Parameterised test @ForbiddenTransitions: spot-check at least 12 forbidden pairs including all self-transitions, all terminal outgoing, and at least two backward transitions (e.g. APPROVED->QUOTED, DEBITED->QUOTED).; Test @TerminalHasNoOutgoing: for FAILED, REVERSED, REFUNDED verify allowedFrom returns an empty set.; Test @AllStatesPresent: all 9 TransactionStatus values appear as keys in the transition map.
**Deliverable:** test/statemachine/TransactionTransitionsTest.java with parameterised tests covering all 13 allowed pairs and 12+ forbidden pairs
**Acceptance / logic checks:**
- All 13 allowed pair assertions pass.
- All forbidden pair assertions (isAllowed returns false) pass.
- FAILED, REVERSED, REFUNDED return empty allowedFrom sets.
- APPROVED->REVERSED is allowed; REVERSED->APPROVED is forbidden.
- No test uses hardcoded ordinal integers — all references use the enum constants.
**Depends on:** 5.1-T02

### 5.1-T10 — MPM QUOTED->PENDING_DEBIT transition on CommitTransaction (OVERSEAS)  _(50 min)_
**Context:** WBS 5.1 and SAD-02 §6.1. For OVERSEAS partners, POST /v1/payments triggers: (1) verify quote TTL not expired (valid_until > now()); if expired, transition QUOTED->FAILED with error RATE_QUOTE_EXPIRED and return HTTP 422; (2) validate idempotency key (24h Redis dedup); (3) if valid, call TransactionStateMachine.transition(txnRef, QUOTED, PENDING_DEBIT, ctx) — this begins atomic prefunding deduction (see 5.1-T11). The partner type is determined from the Rule linked to the rate_quote_id. TTL check uses valid_until field copied from rate_quote at transaction creation. No scheme call happens at this step.
**Steps:** In PaymentOrchestrator.commitMpm(CommitRequest req): load transaction by txn_ref; verify valid_until > Instant.now(); if expired, call transition(txnRef, QUOTED, FAILED, ctx.withError(RATE_QUOTE_EXPIRED)) and return error.; If partner.type == OVERSEAS, call transition(txnRef, QUOTED, PENDING_DEBIT, ctx).; Record event trail step 2 (payment_initiated) via EventTrailService.; Return 202 Accepted to the API layer (prefunding deduction is next step, 5.1-T11).; Add idempotency dedup before any state mutation: if txn already past QUOTED, return cached response.
**Deliverable:** PaymentOrchestrator.commitMpm() handling OVERSEAS path: QUOTED->PENDING_DEBIT or QUOTED->FAILED on expiry
**Acceptance / logic checks:**
- CommitTransaction with valid TTL on OVERSEAS partner transitions status to PENDING_DEBIT.
- CommitTransaction with expired TTL (valid_until in the past) transitions to FAILED, returns HTTP 422, error code RATE_QUOTE_EXPIRED, and no prefunding deduction occurs.
- Second call with the same Idempotency-Key and identical body returns the cached first response without retransitioning.
- Event trail step 2 row (payment_initiated) is inserted.
- Partner.type == LOCAL does not enter this path (no PENDING_DEBIT transition for LOCAL).
**Depends on:** 5.1-T04, 5.1-T06, 5.1-T09

### 5.1-T11 — PENDING_DEBIT->DEBITED: atomic prefunding deduction for OVERSEAS  _(55 min)_
**Context:** WBS 5.1 and SAD-02 §5.2. Prefunding deduction uses SELECT FOR UPDATE on the prefunding_account row (DAT-03 §6.1, balance DECIMAL(20,4) in USD). Deduct prefunding_deducted_usd from the account balance. If balance < deduction_amount, transition PENDING_DEBIT->FAILED with error INSUFFICIENT_PREFUNDING (HTTP 402) and do NOT call the scheme. If deduction succeeds, INSERT a prefunding_ledger_entry (type=DEBIT, amount, txn_ref), update prefunding_account.balance, then transition PENDING_DEBIT->DEBITED. The scheme must never be called without a prior successful deduction (AD-06). prefunding_deducted_usd on the transaction equals collection_usd (the Step 1-4 USD pool amount, not collection_amount which includes service_charge).
**Steps:** In PrefundingService.deductForTransaction(txnId, partnerId, deductionUsd): open @Transactional, SELECT prefunding_account WHERE partner_id=? FOR UPDATE.; If account.balance < deductionUsd: call transition(txnRef, PENDING_DEBIT, FAILED, ctx.withError(INSUFFICIENT_PREFUNDING)); throw InsufficientPrefundingException.; Else: UPDATE balance = balance - deductionUsd; INSERT prefunding_ledger_entry(txn_id, type=DEBIT, amount=deductionUsd, occurred_at=now()).; Call transition(txnRef, PENDING_DEBIT, DEBITED, ctx); record event trail step 3 (prefund_deducted).; Return updated balance.
**Deliverable:** PrefundingService.deductForTransaction() with atomic SELECT FOR UPDATE, balance check, ledger entry, and transition to DEBITED
**Acceptance / logic checks:**
- Deduction of 35.77 USD from a 100.00 USD balance transitions to DEBITED and sets account.balance to 64.23 USD.
- Deduction of 110.00 USD from a 100.00 USD balance transitions to FAILED, returns INSUFFICIENT_PREFUNDING, and balance remains 100.00 USD.
- Two concurrent threads deducting 70.00 USD each from a 100.00 USD balance: exactly one succeeds (DEBITED), one fails (FAILED) — no overdraft.
- prefunding_ledger_entry row is inserted with type=DEBIT and amount matching deductionUsd.
- Event trail step 3 (prefund_deducted) is recorded with detail containing balance_before and balance_after.
**Depends on:** 5.1-T04, 5.1-T06, 5.1-T07

### 5.1-T12 — MPM QUOTED->DEBITED transition for LOCAL partners (no prefunding)  _(35 min)_
**Context:** WBS 5.1 and SAD-02 §5.2. For LOCAL partners (partner.type == LOCAL, e.g. GME Remit), CommitTransaction skips prefunding deduction entirely and transitions directly QUOTED->DEBITED. There is no PENDING_DEBIT state for LOCAL. Event trail step 3 (prefund_deducted) is still inserted with detail: {skipped: true, reason: LOCAL_PARTNER_NO_PREFUND} per A-10 (all 8 steps present on every transaction, even if some are skipped). The idempotency and TTL checks from 5.1-T10 still apply.
**Steps:** In PaymentOrchestrator.commitMpm(): after partner-type check, if partner.type == LOCAL, call transition(txnRef, QUOTED, DEBITED, ctx).; Record event trail step 2 (payment_initiated).; Record event trail step 3 (prefund_deducted) with detail={skipped:true, reason:'LOCAL_PARTNER_NO_PREFUND'}.; Proceed directly to scheme dispatch (5.1-T13).; Do not call PrefundingService.deductForTransaction() for LOCAL partners.
**Deliverable:** PaymentOrchestrator.commitMpm() LOCAL branch: QUOTED->DEBITED with skipped step-3 event trail
**Acceptance / logic checks:**
- CommitTransaction for LOCAL partner transitions QUOTED->DEBITED in one step (no PENDING_DEBIT row in DB history).
- Event trail step 3 row is present with detail.skipped == true and detail.reason == LOCAL_PARTNER_NO_PREFUND.
- prefunding_account balance is unchanged for the LOCAL partner's account (or no account exists).
- LOCAL partner path does NOT call PrefundingService.deductForTransaction().
- OVERSEAS partner path still goes through PENDING_DEBIT (no regression).
**Depends on:** 5.1-T10

### 5.1-T13 — DEBITED->SCHEME_SENT transition on MPM scheme adapter dispatch  _(40 min)_
**Context:** WBS 5.1. After reaching DEBITED (prefund deducted or LOCAL skip), the Orchestrator calls the ZeroPay Scheme Adapter's submitMpm(). Before the call, transition DEBITED->SCHEME_SENT and record event trail step 4 (scheme_request_sent). The transition must happen BEFORE the adapter call so that if the process crashes mid-call, the DB correctly shows SCHEME_SENT and the reconciliation engine can identify the transaction. The scheme_id and merchant_id are read from the transaction record. hub_txn_ref is the GMEPay+ reference sent to ZeroPay.
**Steps:** In PaymentOrchestrator, after DEBITED state: call transition(txnRef, DEBITED, SCHEME_SENT, ctx).; Record event trail step 4 (scheme_request_sent) with detail={hub_txn_ref, merchant_id, scheme_id}.; Call schemeAdapter.submitMpm(hubTxnRef, merchantId, targetPayout, payoutCcy).; Handle response in 5.1-T14 (synchronous success), 5.1-T15 (scheme reject), 5.1-T16 (timeout/UNCERTAIN).
**Deliverable:** PaymentOrchestrator DEBITED->SCHEME_SENT transition and scheme adapter call invocation
**Acceptance / logic checks:**
- After commitMpm for OVERSEAS partner, DB shows status=SCHEME_SENT before adapter returns.
- Event trail step 4 row contains detail.hub_txn_ref matching the transaction's hub_txn_ref.
- If submitMpm() throws a network exception immediately, status is SCHEME_SENT (not DEBITED), enabling reconciliation.
- The state transition to SCHEME_SENT uses TransactionStateMachine.transition() not a direct SQL UPDATE.
- Transition happens before the HTTP call to ZeroPay — verified by test with a mock adapter that asserts DB state in its stub.
**Depends on:** 5.1-T04, 5.1-T06, 5.1-T11, 5.1-T12

### 5.1-T14 — SCHEME_SENT->APPROVED transition on synchronous scheme success (MPM)  _(45 min)_
**Context:** WBS 5.1. When submitMpm() returns a successful MpmSubmitResponse (scheme_approval_code present), the Orchestrator: (1) transitions SCHEME_SENT->APPROVED; (2) sets transaction.completed_at = now(); (3) rate-locks all USD pool values (they were already copied from rate_quote at commit but now marked immutable — see 5.1-T18 for immutability enforcement); (4) records event trail step 5 (scheme_response_received) and step 6 (transaction_committed); (5) stores scheme_ref from the adapter response; (6) dispatches the payment.approved webhook (step 7/8 handled by 5.1-T19). The APPROVED->terminal path completes here.
**Steps:** In PaymentOrchestrator, on successful adapter response: call transition(txnRef, SCHEME_SENT, APPROVED, ctx.withSchemeRef(approvalCode)).; Set transaction.completed_at = now() and transaction.scheme_ref = approvalCode in the same DB transaction.; Record event trail step 5 (scheme_response_received, detail={scheme_approval_code}).; Record event trail step 6 (transaction_committed, detail snapshot: collection_usd, payout_usd_cost, offer_rate_coll, cross_rate).; Enqueue payment.approved webhook event (async) via WebhookService.enqueue(txnRef, APPROVED).
**Deliverable:** PaymentOrchestrator SCHEME_SENT->APPROVED transition with completed_at, scheme_ref, event steps 5-6, and webhook enqueue
**Acceptance / logic checks:**
- After successful submitMpm, transaction.status == APPROVED and completed_at is set.
- transaction.scheme_ref equals the scheme_approval_code returned by the adapter.
- Event trail has step 5 with event_type scheme_response_received and step 6 with event_type transaction_committed.
- Step 6 detail JSONB contains collection_usd, payout_usd_cost, offer_rate_coll as numeric strings.
- WebhookService.enqueue is called once with event type payment.approved.
**Depends on:** 5.1-T13

### 5.1-T15 — SCHEME_SENT->FAILED transition on synchronous scheme rejection  _(50 min)_
**Context:** WBS 5.1. When submitMpm() returns a rejection (MpmSubmitResponse with a scheme error code, or adapter throws a SchemeRejectException), the Orchestrator transitions SCHEME_SENT->FAILED. For OVERSEAS partners, the prefunding deduction that occurred at PENDING_DEBIT->DEBITED must be REVERSED immediately: insert a CREDIT entry to the prefunding ledger and restore the balance. The scheme_ref from the rejection response is stored if present. Event trail step 5 (scheme_response_received, detail={error_code}) and step 6 (transaction_committed, detail={outcome:FAILED}) are recorded. Dispatch payment.failed webhook.
**Steps:** On SchemeRejectException or non-success adapter response: call transition(txnRef, SCHEME_SENT, FAILED, ctx.withError(schemeErrorCode)).; If partner.type == OVERSEAS: call PrefundingService.reverseDeduction(txnRef, partnerId) — INSERT CREDIT ledger entry and UPDATE balance += amount.; Record event trail step 5 (scheme_response_received, detail={scheme_error_code, reason}).; Record event trail step 6 (transaction_committed, detail={outcome:FAILED}).; Enqueue payment.failed webhook.
**Deliverable:** PaymentOrchestrator SCHEME_SENT->FAILED path with prefunding reversal for OVERSEAS and event steps 5-6
**Acceptance / logic checks:**
- On scheme rejection, transaction.status == FAILED.
- OVERSEAS partner balance is restored: balance_after == balance_before_deduction (deduction reversed).
- LOCAL partner has no prefunding reversal logic triggered.
- prefunding_ledger_entry CREDIT row inserted with amount matching the original DEBIT row.
- Event trail step 5 detail.scheme_error_code is set; step 6 detail.outcome == FAILED.
**Depends on:** 5.1-T14, 5.1-T11

### 5.1-T16 — SCHEME_SENT->UNCERTAIN transition on scheme timeout  _(40 min)_
**Context:** WBS 5.1. When the scheme adapter call times out or returns no response within scheme SLA, the Orchestrator transitions SCHEME_SENT->UNCERTAIN. The prefunding deduction is HELD (not reversed) — it will be reversed only if batch reconciliation later confirms FAILED (SAD-02 §5.2). Event trail step 5 is recorded with event_type scheme_response_received, detail={outcome:TIMEOUT}. No webhook is dispatched yet. The transaction is flagged for the Settlement Engine's UNCERTAIN resolution job. If UNCERTAIN remains unresolved after 24 hours, an ops alert must fire (monitoring hook, not this ticket).
**Steps:** On adapter call timeout (caught exception / timeout after scheme SLA): call transition(txnRef, SCHEME_SENT, UNCERTAIN, ctx.withTimeout()).; Record event trail step 5 (scheme_response_received, detail={outcome:TIMEOUT, timed_out_at: now()}).; Do NOT reverse prefunding deduction (balance stays reduced).; Do NOT dispatch any webhook at this point.; Set transaction.uncertain_since = now() for the 24h alert threshold (add column if missing in migration 5.1-T07 or a new migration).
**Deliverable:** PaymentOrchestrator SCHEME_SENT->UNCERTAIN path: status change, held deduction, step-5 event, no webhook
**Acceptance / logic checks:**
- On adapter timeout, transaction.status == UNCERTAIN.
- Prefunding account balance is NOT restored (deduction held).
- Event trail step 5 detail.outcome == TIMEOUT.
- No payment.failed or payment.approved webhook is enqueued.
- transaction.uncertain_since timestamp is set (for 24h expiry alert).
**Depends on:** 5.1-T13

### 5.1-T17 — UNCERTAIN->APPROVED and UNCERTAIN->FAILED transitions via batch reconciliation  _(50 min)_
**Context:** WBS 5.1. The Settlement Engine calls the Orchestrator to resolve UNCERTAIN transactions after processing ZP0012 (payment result) or ZP0022 (refund result) from ZeroPay (~05:00 KST). If ZP0012 record for the hub_txn_ref has result_code=00: transition UNCERTAIN->APPROVED (same logic as 5.1-T14 but without re-calling the scheme). If result_code != 00 or absent after 24h: transition UNCERTAIN->FAILED AND reverse prefunding for OVERSEAS. Reconciliation must be idempotent: calling it twice for the same txnRef is a no-op if already APPROVED or FAILED.
**Steps:** Create method PaymentOrchestrator.resolveUncertain(String txnRef, ReconciliationOutcome outcome) where outcome is APPROVED or FAILED.; If transaction.status != UNCERTAIN, log a warning and return (idempotency guard).; If outcome == APPROVED: call transition(txnRef, UNCERTAIN, APPROVED, ctx); set completed_at; record step 5 detail update and step 6 (transaction_committed); enqueue payment.approved webhook.; If outcome == FAILED: call transition(txnRef, UNCERTAIN, FAILED, ctx); reverse OVERSEAS prefunding via PrefundingService.reverseDeduction(); record step 6; enqueue payment.failed webhook.; Log reconciliation_source (ZP0012/ZP0022/MANUAL) in the event detail.
**Deliverable:** PaymentOrchestrator.resolveUncertain() handling both APPROVED and FAILED outcomes with prefunding reversal and idempotency guard
**Acceptance / logic checks:**
- resolveUncertain(txnRef, APPROVED) on an UNCERTAIN transaction transitions to APPROVED and enqueues payment.approved.
- resolveUncertain(txnRef, FAILED) on an UNCERTAIN transaction transitions to FAILED and reverses OVERSEAS prefunding.
- Calling resolveUncertain twice with APPROVED is a no-op on the second call (idempotency).
- OVERSEAS balance is restored only on FAILED resolution, not on APPROVED.
- Step 6 event trail detail includes reconciliation_source field.
**Depends on:** 5.1-T14, 5.1-T15, 5.1-T16

### 5.1-T18 — Enforce rate-lock immutability on APPROVED transaction fields  _(45 min)_
**Context:** WBS 5.1 and SEC-09 §5.4. Once a transaction transitions to APPROVED (or is resolved via reconciliation), the following fields must never be updated: collection_usd, payout_usd_cost, collection_margin_usd, payout_margin_usd, offer_rate_coll, cross_rate, cost_rate_coll, cost_rate_pay, send_amount, service_charge, collection_amount. Enforcement: (1) DB-level: add a BEFORE UPDATE trigger or application-level guard; (2) application-level: TransactionEntity must throw ImmutableFieldException if any of these fields is set after status reaches APPROVED. This applies to committed transactions whether APPROVED by direct scheme response or by reconciliation.
**Steps:** Create ImmutableFieldException extends RuntimeException with fields: String txnRef, String fieldName, TransactionStatus currentStatus.; In TransactionEntity add a setter guard: before any of the locked fields is mutated, check if status is APPROVED, REVERSED, or REFUNDED; if yes, throw ImmutableFieldException.; Add a DB CHECK or trigger in migration V5_1_003__rate_lock_immutability.sql that prevents UPDATE on locked columns when status = APPROVED (PostgreSQL: use a rule or a check trigger).; Unit test: set an APPROVED transaction's collection_usd to a different value; assert ImmutableFieldException thrown.; Verify that updating non-locked fields (e.g. scheme_ref, completed_at) does not throw.
**Deliverable:** ImmutableFieldException.java and setter guards in TransactionEntity, plus migration V5_1_003 for DB-level enforcement
**Acceptance / logic checks:**
- Setting collection_usd on an APPROVED transaction throws ImmutableFieldException with fieldName=collection_usd.
- Setting scheme_ref on an APPROVED transaction succeeds (non-locked field).
- ImmutableFieldException message includes txnRef and fieldName.
- The DB trigger/rule prevents a raw SQL UPDATE on collection_usd WHERE status=APPROVED.
- QUOTED and DEBITED transactions can still have locked fields set (they are not yet locked).
**Depends on:** 5.1-T04, 5.1-T07

### 5.1-T19 — APPROVED->REVERSED transition: same-day cancel via Cancel API  _(45 min)_
**Context:** WBS 5.1 and API-05 §HC-007. Partners may cancel a payment on the same business day via POST /v1/payments/{id}/cancel. Precondition: status must be APPROVED and cancellation must be within the same KST calendar day as committed_at. If outside the same day, reject with CANCEL_NOT_ALLOWED (HTTP 409). On cancellation: (1) transition APPROVED->REVERSED; (2) reverse OVERSEAS prefunding (re-credit the prefunding_account); (3) store cancel reason and actor; (4) dispatch payment.reversed webhook. Admin-initiated cancel follows the same path via the Admin API.
**Steps:** Create PaymentOrchestrator.cancelPayment(String txnRef, String actor, String reason): load transaction; validate status == APPROVED; validate same-day in KST (committed_at.atZone(KST).toLocalDate() == LocalDate.now(KST)); else throw CancelNotAllowedException.; Call transition(txnRef, APPROVED, REVERSED, ctx.withActor(actor).withReason(reason)).; If partner.type == OVERSEAS: call PrefundingService.reverseDeduction(txnRef, partnerId) to re-credit balance.; Store cancel_reason and cancelled_by on the transaction record.; Enqueue payment.reversed webhook.
**Deliverable:** PaymentOrchestrator.cancelPayment() with same-day KST guard, APPROVED->REVERSED transition, prefunding reversal, and webhook
**Acceptance / logic checks:**
- Cancel on an APPROVED transaction with committed_at today (KST) transitions to REVERSED.
- Cancel on an APPROVED transaction with committed_at yesterday (KST) throws CancelNotAllowedException / returns HTTP 409 CANCEL_NOT_ALLOWED.
- OVERSEAS partner balance is restored after cancellation.
- payment.reversed webhook is enqueued.
- cancel_reason and cancelled_by are persisted on the transaction row.
**Depends on:** 5.1-T14, 5.1-T11

### 5.1-T20 — APPROVED->REFUNDED transition: admin-initiated post-settlement refund  _(50 min)_
**Context:** WBS 5.1 and PRD-07 §10.3. Post-settlement refunds are admin-only (Phase 1). The refund state sub-machine is: REFUND_PENDING -> REFUND_SUBMITTED -> REFUND_CONFIRMED -> REFUNDED (or REFUND_FAILED). When admin initiates a refund on an APPROVED transaction: (1) create a refund record (table: refund, DAT-03 §5.4) with status=REFUND_PENDING; (2) do NOT transition the parent transaction yet — the transaction transitions to REFUNDED only after ZP0022 confirms. For OVERSEAS partners, prefunding reversal happens when ZP0022 confirms (not on initiation). Constraint: can only refund an APPROVED transaction; REVERSED/FAILED/QUOTED transactions are not eligible.
**Steps:** Create RefundService.initiateRefund(String txnRef, BigDecimal amount, String currency, String reason, String operatorId): load transaction; assert status == APPROVED; else throw RefundNotAllowedException.; INSERT refund row: txn_id, refund_ref (generated), refund_amount, refund_ccy, reason, initiated_by, status=REFUND_PENDING, created_at.; Do NOT change parent transaction.status at this stage.; Create RefundService.confirmRefund(String refundRef, String schemeRefundRef): UPDATE refund.status=REFUNDED, scheme_refund_ref; then call transition(txnRef, APPROVED, REFUNDED, ctx).; For OVERSEAS: after confirming, reverse prefunding deduction.
**Deliverable:** RefundService with initiateRefund() (creates REFUND_PENDING record) and confirmRefund() (transitions parent to REFUNDED)
**Acceptance / logic checks:**
- initiateRefund on APPROVED transaction creates refund row with status=REFUND_PENDING; parent transaction.status remains APPROVED.
- initiateRefund on REVERSED transaction throws RefundNotAllowedException.
- confirmRefund transitions parent transaction to REFUNDED and sets transaction.completed_at.
- OVERSEAS prefunding is re-credited only at confirmRefund, not at initiateRefund.
- refund.scheme_refund_ref is set from the ZP0022 confirmation argument.
**Depends on:** 5.1-T14, 5.1-T11

### 5.1-T21 — CPM QUOTED state at QR token generation (POST /v1/payments/cpm/generate)  _(55 min)_
**Context:** WBS 5.1 and SAD-02 §6.2. CPM (Customer Presented Mode) differs from MPM: the transaction enters QUOTED when the partner calls POST /v1/payments/cpm/generate (not at GET /v1/rates). The rate quote is computed at this point and the QR token is requested from the scheme. When the merchant scans the QR, the scheme sends an inbound notification (inboundCpmPending) which triggers quote computation, prefunding deduction (OVERSEAS), and payment.pending_debit webhook. The partner then calls POST /v1/payments/cpm/confirm to complete. CPM OVERSEAS uses PENDING_DEBIT at inboundCpmPending, not at /cpm/generate. CPM LOCAL goes QUOTED->DEBITED at inboundCpmPending.
**Steps:** In CpmOrchestrator.generateToken(CpmGenerateRequest req): create transaction with status=QUOTED; call EventTrailService step 1 (rate_quote_issued) with CPM flag in detail.; Retrieve CPM token from scheme adapter (schemeAdapter.requestCpmToken(countryCode)); store prepare_token and qr_payload on transaction.; Return 200 OK with prepare_token and qr_payload to partner.; On inboundCpmPending: run OVERSEAS->PENDING_DEBIT or LOCAL->DEBITED path (same as 5.1-T10/T12 but triggered by scheme push).; On /cpm/confirm: run DEBITED->SCHEME_SENT->APPROVED path (same as 5.1-T13/T14 but via confirmCpm adapter call).
**Deliverable:** CpmOrchestrator with generateToken(), inboundCpmPending(), and confirm() methods mapping to the CPM-specific state transitions
**Acceptance / logic checks:**
- POST /v1/payments/cpm/generate creates a QUOTED transaction and returns a prepare_token.
- inboundCpmPending for OVERSEAS transitions QUOTED->PENDING_DEBIT and dispatches payment.pending_debit webhook.
- inboundCpmPending for LOCAL transitions QUOTED->DEBITED (no PENDING_DEBIT).
- POST /v1/payments/cpm/confirm transitions DEBITED->SCHEME_SENT->APPROVED on scheme success.
- Event trail step 1 detail contains payment_mode=CPM.
**Depends on:** 5.1-T10, 5.1-T12, 5.1-T13, 5.1-T14

### 5.1-T22 — TTL expiry guard: QUOTED->FAILED on stale rate quote commit  _(35 min)_
**Context:** WBS 5.1 and API-05 §HC-005. If a partner calls POST /v1/payments or POST /v1/payments/cpm/confirm after the rate quote has expired (now() > valid_until), the Orchestrator must: (1) NOT deduct prefunding; (2) transition QUOTED->FAILED with error RATE_QUOTE_EXPIRED; (3) return HTTP 422. valid_until is stored on the transaction record (copied from rate_quote at QUOTED). The guard must run before any prefunding deduction. Expired QUOTED transactions with no commit attempt should be cleaned up by the scheduled expiry job (5.1-T23), but the TTL guard also handles the case where commit arrives after expiry.
**Steps:** In PaymentOrchestrator.commitMpm() and CpmOrchestrator.confirm(): as the first check after loading the transaction, compare Instant.now() > transaction.valid_until.; If expired: call transition(txnRef, QUOTED, FAILED, ctx.withError(RATE_QUOTE_EXPIRED)). Record event trail step 2 (payment_initiated, detail={expired:true}) and step 6 (transaction_committed, detail={outcome:FAILED,error:RATE_QUOTE_EXPIRED}).; Return HTTP 422 with error body: {error:{code:RATE_QUOTE_EXPIRED, message:'...', txn_ref:'...'}}.; If not expired: continue with normal flow.; Test: commit exactly at valid_until boundary — accept if now() == valid_until (inclusive on the boundary per spec).
**Deliverable:** TTL expiry guard in PaymentOrchestrator and CpmOrchestrator returning HTTP 422 RATE_QUOTE_EXPIRED with no side effects
**Acceptance / logic checks:**
- Commit at valid_until - 1 second succeeds (not expired).
- Commit at valid_until + 1 second returns HTTP 422, error code RATE_QUOTE_EXPIRED, transaction.status == FAILED.
- prefunding_account balance is unchanged for OVERSEAS partner on expiry path.
- No scheme call is made on expired commit.
- Event trail step 2 is recorded even on expiry path so the trail is queryable.
**Depends on:** 5.1-T10, 5.1-T21

### 5.1-T23 — Scheduled job: expire QUOTED transactions past valid_until  _(45 min)_
**Context:** WBS 5.1. QUOTED transactions where valid_until < now() and no commit has been received should be transitioned to FAILED by a background job, so they do not accumulate as stale open records. The job runs every 60 seconds. It selects all transactions WHERE status=QUOTED AND valid_until < now() FOR UPDATE SKIP LOCKED (to avoid contending with concurrent commit attempts). For each row, it calls TransactionStateMachine.transition(txnRef, QUOTED, FAILED, ctx.withError(TTL_EXPIRED_BY_JOB)). The job must be idempotent: if a row has already moved past QUOTED by a concurrent commit, the ConcurrentTransitionException is caught and logged as INFO (not an error).
**Steps:** Create scheduled job QuotedExpiryJob.java with @Scheduled(fixedDelay=60000).; Query: SELECT * FROM transaction WHERE status='QUOTED' AND valid_until < NOW() FOR UPDATE SKIP LOCKED LIMIT 100.; For each result: try { stateMachine.transition(txnRef, QUOTED, FAILED, ctx) } catch (ConcurrentTransitionException e) { log.info("Already moved: {}", txnRef); }.; Record event trail step 2 (payment_initiated, detail={expired_by_job:true}) on expiry.; Log job execution summary: rows_checked, rows_expired, rows_already_moved.
**Deliverable:** QuotedExpiryJob.java: scheduled every 60s, batch-expires stale QUOTED rows, idempotent on concurrent transitions
**Acceptance / logic checks:**
- A QUOTED transaction with valid_until 5 minutes ago is transitioned to FAILED by the next job run.
- A QUOTED transaction with valid_until 30 seconds in the future is NOT touched.
- If a concurrent thread commits the transaction between the query and the transition, ConcurrentTransitionException is caught and logged as INFO (no error/alert).
- Job running twice in quick succession does not create duplicate FAILED events (second run finds no rows matching the WHERE clause).
- Batch size limit of 100 prevents the job from holding locks too long.
**Depends on:** 5.1-T04, 5.1-T06

### 5.1-T24 — UNCERTAIN 24h alert: fire ops alert on unresolved UNCERTAIN transactions  _(30 min)_
**Context:** WBS 5.1 and SAD-02 §5.2. If a transaction remains in UNCERTAIN status for more than 24 hours (uncertain_since < now() - 24h), it has not been resolved by the normal ZP0012 batch cycle and requires manual Ops intervention. A scheduled job must detect this condition and fire a structured alert. The alert must include: txn_ref, hub_txn_ref, partner_id, scheme_id, uncertain_since. The transaction is NOT automatically failed — it enters the manual exception queue. Resolution remains via resolveUncertain() (5.1-T17) called by Ops.
**Steps:** Create UncertainAlertJob.java with @Scheduled(fixedDelay=300000) (every 5 minutes).; Query: SELECT * FROM transaction WHERE status='UNCERTAIN' AND uncertain_since < NOW() - INTERVAL '24 hours'.; For each result: publish an UNCERTAIN_TIMEOUT alert via AlertService.fire(AlertLevel.HIGH, txnRef, hub_txn_ref, partnerId, uncertainSince).; Log alert as structured log with all fields at WARN level.; Do NOT change transaction status — Ops resolves manually via resolveUncertain().
**Deliverable:** UncertainAlertJob.java: fires HIGH-severity alerts for UNCERTAIN transactions older than 24h without mutating state
**Acceptance / logic checks:**
- Transaction with uncertain_since = 25 hours ago triggers an alert on the next job run.
- Transaction with uncertain_since = 23 hours ago does NOT trigger an alert.
- Alert payload contains txn_ref, hub_txn_ref, partner_id, uncertain_since.
- Job does not modify transaction.status.
- Running the job twice for the same unresolved transaction fires the alert twice (acceptable — monitoring deduplicates).
**Depends on:** 5.1-T16

### 5.1-T25 — Unit tests: MPM happy path state transitions (QUOTED->DEBITED->SCHEME_SENT->APPROVED)  _(45 min)_
**Context:** WBS 5.1. Tests for the complete LOCAL-partner MPM happy path through the state machine. Partner type = LOCAL. Steps: (1) transaction starts in QUOTED; (2) commitMpm() -> DEBITED (no prefunding); (3) scheme adapter submitMpm() returns success -> SCHEME_SENT -> APPROVED. Each state must be verified in the DB. Event trail steps 1, 2 (payment_initiated), 3 (skipped/LOCAL), 4 (scheme_request_sent), 5 (scheme_response_received), 6 (transaction_committed) must all be present. Uses an in-memory H2 or test-Postgres DB. Scheme adapter is mocked.
**Steps:** Create test class MpmHappyPathTest.java.; Set up: insert partner (type=LOCAL), scheme, rule, rate_quote (valid_until = +5 min), transaction in QUOTED status.; Call paymentOrchestrator.commitMpm(txnRef, idempotencyKey): assert status transitions QUOTED->DEBITED.; Mock schemeAdapter.submitMpm() to return approval code APPROVAL-001.; Assert final status APPROVED, completed_at set, scheme_ref=APPROVAL-001.; Assert event trail rows for steps 1-6, step 3 detail.skipped==true.
**Deliverable:** test/statemachine/MpmHappyPathTest.java: full LOCAL MPM flow from QUOTED to APPROVED with event trail verification
**Acceptance / logic checks:**
- Final transaction.status == APPROVED.
- Event trail has exactly 6 rows for steps 1-6 (steps 7-8 not yet triggered as webhook is async).
- Step 3 detail.skipped == true and detail.reason == LOCAL_PARTNER_NO_PREFUND.
- Step 6 detail contains collection_usd and payout_usd_cost as non-null values.
- scheme_ref == APPROVAL-001 on the transaction record.
**Depends on:** 5.1-T12, 5.1-T13, 5.1-T14

### 5.1-T26 — Unit tests: OVERSEAS MPM prefunding deduction path (QUOTED->PENDING_DEBIT->DEBITED->APPROVED)  _(45 min)_
**Context:** WBS 5.1. Tests for the OVERSEAS partner MPM path including prefunding deduction. Partner: OVERSEAS, initial balance 100.00 USD. prefunding_deducted_usd = 35.77 USD (= collection_usd from RATE-04 worked example with target_payout=50000 KRW, usd_krw=1380.00, m_a=m_b=0.01). Expected: balance after deduction = 64.23 USD. Verify PENDING_DEBIT state is reached, then DEBITED, then APPROVED. Verify event trail step 3 (prefund_deducted) with balance_before=100.00 and balance_after=64.23.
**Steps:** Insert OVERSEAS partner with prefunding_account.balance=100.00 USD.; Insert rate_quote with collection_usd=36.2320 (RATE-04 §4.3 cross-border values — use these exact Decimals).; Call commitMpm(); assert transition to PENDING_DEBIT.; Assert PrefundingService deducts 36.2320 USD; balance becomes 63.7680 USD; transaction -> DEBITED.; Mock schemeAdapter success; assert -> APPROVED. Verify event trail.
**Deliverable:** test/statemachine/OverseasMpmDeductionTest.java verifying QUOTED->PENDING_DEBIT->DEBITED->APPROVED with exact balance arithmetic
**Acceptance / logic checks:**
- prefunding_account.balance after deduction == 100.00 - 36.2320 == 63.7680 USD (Decimal, not float).
- Event trail step 3 detail.balance_before == 100.00, detail.balance_after == 63.7680.
- Status sequence in event trail: QUOTED, PENDING_DEBIT, DEBITED, SCHEME_SENT, APPROVED.
- No prefunding balance update is made for LOCAL partner (regression check by running same test with LOCAL type).
- transaction.prefunding_deducted_usd == 36.2320 on the final record.
**Depends on:** 5.1-T11, 5.1-T25

### 5.1-T27 — Unit tests: QUOTED->FAILED on expired TTL, insufficient prefunding, and scheme rejection  _(50 min)_
**Context:** WBS 5.1. Three failure-path unit tests covering: (A) TTL expiry — commit arrives 1 second after valid_until; (B) Insufficient prefunding — OVERSEAS partner balance 10.00 USD, deduction amount 35.77 USD; (C) Scheme rejection — submitMpm returns SchemeRejectException with code ZP_DECLINED. Each test must verify: final status FAILED, no prefunding deduction on expiry path, prefunding restored on scheme-rejection path, event trail step 6 detail.outcome==FAILED with correct error code.
**Steps:** Test A (TTL): set valid_until = now()-1s; call commitMpm(); assert FAILED, HTTP 422 RATE_QUOTE_EXPIRED, balance unchanged.; Test B (Insufficient): set balance=10.00, deduction=35.77; call commitMpm(); assert FAILED after PENDING_DEBIT, balance unchanged at 10.00, error INSUFFICIENT_PREFUNDING.; Test C (Scheme rejection): set balance=100.00, deduction=35.77; mock submitMpm to throw SchemeRejectException(ZP_DECLINED); assert FAILED, balance restored to 100.00 (deduction reversed).; In each test verify event trail step 6 with outcome=FAILED and correct error_code.; In test B verify transition sequence: QUOTED->PENDING_DEBIT->FAILED (no DEBITED).
**Deliverable:** test/statemachine/TransactionFailurePathsTest.java covering expiry, insufficient funds, and scheme rejection
**Acceptance / logic checks:**
- Test A: transaction.status==FAILED, balance unchanged, error code RATE_QUOTE_EXPIRED.
- Test B: PENDING_DEBIT row exists in event trail but DEBITED does not; balance == 10.00 unchanged.
- Test C: balance restored to 100.00 USD after scheme rejection (deduction reversed).
- All three tests verify step 6 event trail row with outcome==FAILED.
- No float arithmetic in any assertion — Decimal comparisons throughout.
**Depends on:** 5.1-T22, 5.1-T15, 5.1-T26

### 5.1-T28 — Unit tests: UNCERTAIN resolution via batch reconciliation  _(40 min)_
**Context:** WBS 5.1. Tests for the UNCERTAIN->APPROVED and UNCERTAIN->FAILED paths via resolveUncertain(). Setup: OVERSEAS transaction in UNCERTAIN status, prefunding_deducted_usd=35.77 USD, prefunding_account.balance=64.23 USD (deducted). Test A: resolveUncertain(txnRef, APPROVED) -> status APPROVED, balance unchanged at 64.23, payment.approved webhook enqueued. Test B: resolveUncertain(txnRef, FAILED) -> status FAILED, balance restored to 100.00, payment.failed webhook enqueued. Test C (idempotency): call resolveUncertain(txnRef, APPROVED) twice; second call is a no-op (status still APPROVED, no duplicate events, no duplicate webhooks).
**Steps:** Set up OVERSEAS transaction in UNCERTAIN; prefunding deducted (balance=64.23).; Test A: call resolveUncertain(txnRef, APPROVED); assert status APPROVED, balance==64.23, step 6 present.; Test B: reset to UNCERTAIN; call resolveUncertain(txnRef, FAILED); assert status FAILED, balance==100.00.; Test C: call resolveUncertain twice with APPROVED; assert no duplicate event rows, no duplicate webhook enqueue calls.; Verify in all tests that reconciliation_source is set in step 6 event trail detail.
**Deliverable:** test/statemachine/UncertainResolutionTest.java: UNCERTAIN->APPROVED, UNCERTAIN->FAILED, and idempotency
**Acceptance / logic checks:**
- Test A: status APPROVED, balance 64.23 (no reversal), payment.approved enqueued once.
- Test B: status FAILED, balance 100.00 (deduction reversed), payment.failed enqueued once.
- Test C: second resolveUncertain call does not insert a second step 6 event row.
- transaction.completed_at is set on APPROVED resolution.
- step 6 detail.reconciliation_source is non-null in all cases.
**Depends on:** 5.1-T17, 5.1-T26

### 5.1-T29 — Unit tests: same-day cancel (APPROVED->REVERSED) and out-of-window rejection  _(40 min)_
**Context:** WBS 5.1 and API-05 §HC-007. Tests for cancelPayment(). Test A (success): OVERSEAS transaction APPROVED today (KST); call cancelPayment(); assert REVERSED, balance restored, payment.reversed webhook enqueued. Test B (out-of-window): APPROVED transaction with committed_at yesterday KST; call cancelPayment(); assert HTTP 409 CANCEL_NOT_ALLOWED, status still APPROVED, balance unchanged. Test C (wrong initial state): transaction in SCHEME_SENT; call cancelPayment(); assert CANCEL_NOT_ALLOWED (only APPROVED can be cancelled). KST is Asia/Seoul timezone.
**Steps:** Set up OVERSEAS transaction APPROVED with committed_at = today KST 10:00.; Test A: call cancelPayment(); assert REVERSED, balance restored to pre-deduction level.; Test B: set committed_at = yesterday KST 23:59; call cancelPayment(); expect CancelNotAllowedException / HTTP 409.; Test C: set status=SCHEME_SENT; call cancelPayment(); expect rejection (status != APPROVED).; Verify payment.reversed webhook enqueued in Test A only.
**Deliverable:** test/statemachine/CancelPaymentTest.java covering success, out-of-KST-day, and wrong-state cases
**Acceptance / logic checks:**
- Test A: final status REVERSED, OVERSEAS balance restored, payment.reversed webhook enqueued.
- Test B: CancelNotAllowedException thrown, transaction.status remains APPROVED.
- Test C: rejection when initial status != APPROVED.
- KST boundary test: committed_at = 23:59 yesterday KST is rejected; committed_at = 00:01 today KST is accepted.
- cancel_reason and cancelled_by are set on the transaction record in Test A.
**Depends on:** 5.1-T19, 5.1-T26

### 5.1-T30 — Unit tests: rate-lock immutability after APPROVED  _(40 min)_
**Context:** WBS 5.1 and SEC-09 §5.4. Tests for the immutability guard in TransactionEntity. After a transaction reaches APPROVED, any attempt to modify rate-locked fields must raise ImmutableFieldException. Locked fields: collection_usd, payout_usd_cost, collection_margin_usd, payout_margin_usd, offer_rate_coll, cross_rate, cost_rate_coll, cost_rate_pay, send_amount, service_charge, collection_amount. Non-locked fields (scheme_ref, completed_at, cancel_reason) may still be updated. Also test that QUOTED and DEBITED transactions allow updates to locked fields.
**Steps:** Create test class RateLockImmutabilityTest.java.; For each of the 11 locked fields: set transaction to APPROVED, attempt to set the field, assert ImmutableFieldException with the correct fieldName.; For each of 3 non-locked fields (scheme_ref, completed_at, cancel_reason): set APPROVED, update field, assert no exception.; Test with QUOTED status: set collection_usd to a new value; assert NO ImmutableFieldException.; Test DB-level guard (via raw JDBC update): attempt UPDATE transaction SET collection_usd=999 WHERE status='APPROVED'; expect constraint violation.
**Deliverable:** test/statemachine/RateLockImmutabilityTest.java: 11 locked fields and 3 non-locked fields tested, plus raw SQL guard
**Acceptance / logic checks:**
- All 11 locked field setters throw ImmutableFieldException when status==APPROVED.
- ImmutableFieldException.fieldName matches the field being set (e.g. collection_usd not a generic message).
- Non-locked fields (scheme_ref, completed_at) can be updated on APPROVED transactions without exception.
- QUOTED transaction allows setting collection_usd (not yet locked).
- Raw SQL UPDATE attempt on APPROVED row is rejected by DB constraint.
**Depends on:** 5.1-T18, 5.1-T25

### 5.1-T31 — Integration test: full MPM OVERSEAS payment E2E state transitions  _(60 min)_
**Context:** WBS 5.1. An end-to-end integration test that exercises the full OVERSEAS MPM flow against a real test-Postgres DB (port 5433) with a mocked ZeroPay adapter. Sequence: (1) insert test partner (OVERSEAS, balance=200.00 USD), scheme, rule, treasury rate (usd_krw=1380.00), merchant; (2) call GET /v1/rates (target_payout=50000 KRW, m_a=m_b=0.01) — verify rate quote returned, collection_usd=36.9714; (3) POST /v1/payments with quote_ref — verify transitions QUOTED->PENDING_DEBIT->DEBITED->SCHEME_SENT->APPROVED; (4) verify balance=200.00-36.9714=163.0286; (5) verify 6 event trail rows; (6) verify rate-locked fields match the quote values.
**Steps:** Set up test DB via Flyway migration (Testcontainers or local port 5433 with test schema).; Insert prerequisites: partner (OVERSEAS), prefunding_account (balance=200.00), scheme (ZeroPay), rule (m_a=0.01, m_b=0.01, service_charge=0.35 USD), treasury_rate (usd_krw=1380.00).; POST /v1/rates; assert collection_usd=36.9714 (8dp Decimal), collection_amount=37.3214 (36.9714+0.35 rounded to 4dp KRW scale).; POST /v1/payments; mock adapter to return APPROVAL-E2E; verify final status APPROVED.; Assert balance=200.00-36.9714=163.0286, event trail steps 1-6 present, transaction.collection_usd=36.9714.
**Deliverable:** test/integration/MpmOverseasE2ETest.java verifying full OVERSEAS MPM flow with exact balance and rate-lock assertions
**Acceptance / logic checks:**
- transaction.status == APPROVED after full flow.
- prefunding_account.balance == 163.0286 USD (200.00 - 36.9714, to 4dp).
- collection_usd == 36.9714 on the transaction record (rate-locked, 8dp).
- Event trail has 6 rows (steps 1-6); step 3 detail.skipped == false.
- Pool identity check: 36.9714 - 0.3697 - 0.3697 - 36.2320 = 0.0000 (within 0.01 USD tolerance).
**Depends on:** 5.1-T25, 5.1-T26, 5.1-T11, 5.1-T14

### 5.1-T32 — State machine Javadoc and inline transition diagram comment  _(30 min)_
**Context:** WBS 5.1. The TransactionStateMachine and TransactionTransitions classes are the authoritative runtime documentation of the state machine. All downstream engineers must be able to understand the state graph from the source alone. This ticket adds Javadoc with the full state diagram (ASCII or mermaid in comment) and links to SAD-02 §5.2 and API-05 §4.1 in the class-level Javadoc. No logic changes — documentation only.
**Steps:** Open TransactionTransitions.java; add class-level Javadoc with the full stateDiagram-v2 (mermaid syntax as a @implNote block) listing all 13 allowed transitions verbatim.; Reference SAD-02 §5.2 and API-05 §4.1 in @see tags.; Open TransactionStateMachine.java; add class-level Javadoc explaining it is the sole owner of status mutations, referencing TransactionTransitions for the allowed pairs.; For each enum constant in TransactionStatus, verify the Javadoc from 5.1-T01 is present and accurate after all tickets have been merged.; No logic changes — this is documentation-only.
**Deliverable:** Updated Javadoc on TransactionTransitions.java, TransactionStateMachine.java, and TransactionStatus.java with full state diagram and spec cross-references
**Acceptance / logic checks:**
- TransactionTransitions.java class Javadoc contains the string stateDiagram-v2 and all 9 state names.
- TransactionStateMachine.java Javadoc states it is the sole component that may call transition().
- All 9 TransactionStatus constants have non-empty Javadoc.
- @see tags reference SAD-02 and API-05.
- No Java compilation errors introduced.
**Depends on:** 5.1-T02, 5.1-T04, 5.1-T31


## WBS 5.7 — Idempotency & request dedup
### 5.7-T01 — Create idempotency_record Redis key schema and TTL constants  _(25 min)_
**Context:** GMEPay+ deduplicates all POST requests by storing (partner_id, idempotency_key) -> (response_status, response_body) in Redis for 24 hours (86400 s). Keys must be 16-128 chars (UUID v4 recommended). Redis key pattern: idem:{partner_id}:{idempotency_key}. An in-flight lock key is also needed: idem_lock:{partner_id}:{idempotency_key} with short TTL (e.g. 30 s) to detect concurrent duplicate requests and return HTTP 409 with X-Idempotency-Status: in_flight.
**Steps:** Define Java constants class IdempotencyConstants: KEY_PREFIX = "idem", LOCK_PREFIX = "idem_lock", TTL_SECONDS = 86400, LOCK_TTL_SECONDS = 30, MIN_KEY_LENGTH = 16, MAX_KEY_LENGTH = 128; Define a record IdempotencyRecord { String idempotencyKey, String partnerId, int httpStatus, String responseBodyJson, Instant createdAt, Instant expiresAt }; Document Redis key format in a code comment: idem:{partnerId}:{idempotencyKey} -> JSON(IdempotencyRecord); idem_lock:{partnerId}:{idempotencyKey} -> 1; Write unit test asserting TTL_SECONDS == 86400 and LOCK_TTL_SECONDS <= 60
**Deliverable:** IdempotencyConstants.java and IdempotencyRecord.java in the idempotency package
**Acceptance / logic checks:**
- TTL_SECONDS constant equals 86400
- MIN_KEY_LENGTH = 16, MAX_KEY_LENGTH = 128 match API-05 §7.1 constraints
- Redis key format encodes both partner_id and idempotency_key so keys from different partners never collide
- IdempotencyRecord carries httpStatus (int) and responseBodyJson (String) sufficient to replay any stored response

### 5.7-T02 — Create IdempotencyRepository: Redis read/write/lock operations  _(45 min)_
**Context:** The idempotency service needs three Redis operations: (1) SET NX PX <lock_ttl_ms> on the lock key to acquire an in-flight lock atomically; (2) GET on the result key to check for a completed stored response; (3) SET EX <86400> on the result key to persist the completed response. Uses Redis SETNX semantics via Spring Data Redis or Lettuce. Key format: idem:{partnerId}:{idempotencyKey} for results, idem_lock:{partnerId}:{idempotencyKey} for in-flight locks.
**Steps:** Create IdempotencyRepository interface with methods: Optional<IdempotencyRecord> findCompleted(String partnerId, String key); boolean acquireLock(String partnerId, String key); void saveCompleted(String partnerId, String key, int httpStatus, String responseBody); void releaseLock(String partnerId, String key); Implement using RedisTemplate<String,String>: acquireLock uses SET idem_lock:{p}:{k} 1 NX PX 30000; findCompleted uses GET idem:{p}:{k} then deserializes JSON; saveCompleted uses SET idem:{p}:{k} <json> EX 86400; releaseLock does DEL on lock key; Serialize IdempotencyRecord to/from JSON using Jackson ObjectMapper; Add integration test against embedded Redis: store a record with status 201 and body '{"id":"pay_001"}', retrieve it, verify status and body match exactly
**Deliverable:** IdempotencyRepository.java interface + RedisIdempotencyRepository.java implementation
**Acceptance / logic checks:**
- acquireLock returns true on first call for a given (partnerId, key) pair and false on second concurrent call (NX semantics)
- saveCompleted followed by findCompleted returns the exact same httpStatus and responseBodyJson
- TTL on the result key is set to 86400 s (verify via Redis TTL command in integration test)
- Lock key TTL is 30 s; lock auto-expires if releaseLock is never called (crash safety)
- Keys from partnerId=p1 and partnerId=p2 with identical idempotency_key values do not collide
**Depends on:** 5.7-T01

### 5.7-T03 — Create IdempotencyService: lookup-or-lock core logic  _(50 min)_
**Context:** The idempotency service orchestrates the three-state decision before any POST handler runs: (1) if a completed record exists -> return stored response immediately; (2) if in-flight lock cannot be acquired -> return HTTP 409 with header X-Idempotency-Status: in_flight; (3) otherwise -> proceed with processing, then persist the response on completion. Stored responses include 4xx results (a validation failure on the first call is stored and replayed). Body hash must match: if body differs from what was stored at lock time, return HTTP 422 IDEMPOTENCY_KEY_REUSE.
**Steps:** Create IdempotencyService with method IdempotencyDecision check(String partnerId, String idempotencyKey, String requestBodyHash); IdempotencyDecision is a sealed interface with variants: Replay(int status, String body), InFlight(), Proceed(); In check(): first call findCompleted; if present and bodyHash matches -> Replay; if present and bodyHash differs -> throw IdempotencyKeyReuseException; if absent, call acquireLock; if lock fails -> InFlight; else store bodyHash alongside lock key and return Proceed; Add method void complete(String partnerId, String idempotencyKey, int status, String responseBody) to persist result and release lock; Store requestBodyHash in the lock value (not a separate key) so body-mismatch can be detected on replay without re-hashing the stored body
**Deliverable:** IdempotencyService.java with inner sealed interface IdempotencyDecision
**Acceptance / logic checks:**
- First call with key K returns Proceed
- Second call with same key K and same body hash while first is in flight returns InFlight
- Second call after first completes returns Replay with original status and body
- Call with same key K but different body hash after completion throws IdempotencyKeyReuseException (maps to HTTP 422 IDEMPOTENCY_KEY_REUSE)
- A 4xx response stored by complete() is replayed on subsequent calls (validation failures are idempotent)
**Depends on:** 5.7-T02

### 5.7-T04 — Implement request body SHA-256 hashing for idempotency comparison  _(30 min)_
**Context:** To detect body mismatches (IDEMPOTENCY_KEY_REUSE), GMEPay+ hashes the raw request body bytes with SHA-256 and stores the hex digest alongside the idempotency lock entry. The hash must be computed from the raw byte stream before JSON deserialization so that equivalent JSON with different whitespace or key ordering is treated consistently. The hashing component is shared by the idempotency filter and the IdempotencyService.
**Steps:** Create RequestBodyHasher utility class with static method String sha256Hex(byte[] body) using java.security.MessageDigest; Create CachingRequestWrapper extending HttpServletRequestWrapper that buffers the request body so it can be read multiple times; Ensure sha256Hex of the same logical payload with and without trailing whitespace produces different hashes (raw bytes, not parsed JSON) -- this is intentional and documented; Add unit tests: sha256Hex("hello".getBytes()) == expected SHA-256 hex; different inputs produce different digests; empty body produces SHA-256 of empty string (not null)
**Deliverable:** RequestBodyHasher.java and CachingRequestWrapper.java in the idempotency package
**Acceptance / logic checks:**
- sha256Hex produces a 64-char lowercase hex string
- sha256Hex({}) != sha256Hex({ }) (different byte content -> different hash, even if semantically equivalent JSON)
- CachingRequestWrapper.getInputStream() can be called twice and returns the same bytes both times
- sha256Hex(null_or_empty_body) returns the SHA-256 of an empty byte array without NPE
**Depends on:** 5.7-T01

### 5.7-T05 — Implement IdempotencyFilter: per-request interception for all POST endpoints  _(55 min)_
**Context:** A Spring OncePerRequestFilter intercepts every incoming POST request before it reaches any handler. It: (1) reads the Idempotency-Key header; (2) if missing, short-circuits with HTTP 400 error code MISSING_IDEMPOTENCY_KEY; (3) validates key length (16-128 chars); (4) computes SHA-256 body hash using CachingRequestWrapper; (5) calls IdempotencyService.check(); (6) on Replay, writes the stored response directly and bypasses the filter chain; (7) on InFlight, returns HTTP 409 with X-Idempotency-Status: in_flight; (8) on Proceed, continues filter chain and calls IdempotencyService.complete() after response is written.
**Steps:** Create IdempotencyFilter extends OncePerRequestFilter, inject IdempotencyService and ObjectMapper; Wrap request with CachingRequestWrapper before reading body; Check Idempotency-Key header present; if absent return 400 JSON {error_code: MISSING_IDEMPOTENCY_KEY}; Validate key length 16-128; if invalid return 400 VALIDATION_ERROR; Call IdempotencyService.check(); branch on Replay/InFlight/Proceed; For Replay: copy stored httpStatus and responseBodyJson to HttpServletResponse; add header X-Idempotency-Status: replayed; skip filter chain; For InFlight: return 409 with X-Idempotency-Status: in_flight; For Proceed: wrap HttpServletResponse with a response-capturing wrapper; pass to chain; after chain completes call idempotencyService.complete() with captured status and body; Register filter at order before AuthenticationFilter to ensure idempotency check happens early but after auth (adjust order if auth must run first)
**Deliverable:** IdempotencyFilter.java registered as a Spring @Component with @Order
**Acceptance / logic checks:**
- POST without Idempotency-Key header returns HTTP 400, error_code MISSING_IDEMPOTENCY_KEY
- POST with key length 15 returns HTTP 400 VALIDATION_ERROR; key length 128 is accepted
- POST with same key and same body after completion returns stored status and body with header X-Idempotency-Status: replayed
- POST with same key and different body returns HTTP 422 IDEMPOTENCY_KEY_REUSE
- Concurrent identical POST requests: one returns the real response, the other returns HTTP 409 X-Idempotency-Status: in_flight
**Depends on:** 5.7-T03, 5.7-T04

### 5.7-T06 — Validate Idempotency-Key format: UUID detection and length enforcement  _(30 min)_
**Context:** API-05 §7.1 states the key must be 16-128 characters, UUID v4 is recommended but not required. The validation layer must reject keys outside the 16-128 char range with HTTP 400 VALIDATION_ERROR. UUID format is not strictly enforced (any random string of valid length is accepted). However, keys that contain null bytes or characters outside printable ASCII (0x20-0x7E) must be rejected to prevent Redis key injection. This is part of the IdempotencyFilter validation step.
**Steps:** Add validateKey(String key) method to IdempotencyFilter or a separate IdempotencyKeyValidator class; Reject if key is null or blank -> MISSING_IDEMPOTENCY_KEY; Reject if length < 16 or > 128 -> VALIDATION_ERROR with detail field_name=Idempotency-Key, constraint=length_16_to_128; Reject if any character is outside printable ASCII (char < 0x20 or char > 0x7E) -> VALIDATION_ERROR with detail=invalid_characters; Add unit tests covering: null, empty string, 15-char, 16-char (accept), 128-char (accept), 129-char, valid UUID v4, string with tab character (reject)
**Deliverable:** IdempotencyKeyValidator.java (or method in IdempotencyFilter) with full unit-test coverage
**Acceptance / logic checks:**
- Key of exactly 16 printable ASCII chars passes validation
- Key of 129 chars returns HTTP 400 VALIDATION_ERROR
- Key containing char 0x09 (tab) returns HTTP 400 VALIDATION_ERROR
- Valid UUID v4 '7b1e3f2a-89cd-4d02-9f3b-0a1c2d3e4f56' (36 chars) passes validation
- Null key returns HTTP 400 MISSING_IDEMPOTENCY_KEY, not VALIDATION_ERROR
**Depends on:** 5.7-T05

### 5.7-T07 — Implement response-capturing wrapper for IdempotencyFilter post-processing  _(40 min)_
**Context:** After the filter chain processes a Proceed request, IdempotencyFilter must capture the response status code and body to persist in Redis. A standard HttpServletResponse does not support re-reading the body after it is written. A ContentCachingResponseWrapper (Spring's built-in) or custom ResponseCapturingWrapper must buffer the output stream so the filter can read the body bytes and call IdempotencyService.complete() before flushing to the actual client.
**Steps:** Use Spring's ContentCachingResponseWrapper to wrap the HttpServletResponse passed to the filter chain; After chain.doFilter() returns, read the captured body via getContentAsByteArray(); Convert body bytes to UTF-8 string; call idempotencyService.complete(partnerId, idempotencyKey, response.getStatus(), bodyString); Call copyBodyToResponse() to flush the buffered body to the actual client; Handle edge case: if response is a streaming response or body is empty (e.g. 204 No Content), store empty string as responseBodyJson; Add unit test: mock filter chain that writes JSON body; verify IdempotencyService.complete() is called with correct status and body string
**Deliverable:** Updated IdempotencyFilter.java using ContentCachingResponseWrapper with complete() hook
**Acceptance / logic checks:**
- After a successful POST /v1/payments (201), the captured body stored in Redis matches the exact JSON returned to the client
- A 422 validation error response is also captured and stored (4xx responses are idempotent)
- 204 No Content response stores empty string body without error
- ContentCachingResponseWrapper.copyBodyToResponse() is called so the client receives the actual response, not an empty body
- idempotencyService.complete() is called exactly once per Proceed request regardless of whether the handler throws or returns normally
**Depends on:** 5.7-T05

### 5.7-T08 — Handle in-flight lock expiry and crash recovery on lock release  _(35 min)_
**Context:** If a service instance crashes after acquiring the in-flight lock (idem_lock:{p}:{k}) but before calling releaseLock(), the lock must auto-expire so subsequent retries are not permanently blocked. The lock TTL is 30 s. However, if normal processing takes longer than 30 s (e.g. slow scheme call), the lock may expire before complete() is called, causing a duplicate to slip through. The service must handle: (a) lock expiry during processing by detecting that saveCompleted() overwrites correctly, and (b) the rare case where two requests both pass acquireLock (first lock expired, second acquired), by using a Redis SET NX EX on the result key so only one write wins.
**Steps:** Ensure saveCompleted() uses SET idem:{p}:{k} <json> EX 86400 NX=false (allow overwrite, last writer wins for result) -- this is safe because both concurrent requests computed the same business outcome for identical bodies; Add a warning log when complete() is called and a completed record already exists (indicates lock expired mid-flight); Add unit test: simulate lock expiry by setting LOCK_TTL_SECONDS=1 in test config, sleep 2 s, then call complete() -- verify the result is still stored correctly; Document in code comment: for operations expected to exceed 30 s (e.g. scheme timeouts), the lock expiry is acceptable because the result key write is idempotent for identical bodies
**Deliverable:** Updated RedisIdempotencyRepository.java with overwrite-safe saveCompleted and a warning log path
**Acceptance / logic checks:**
- saveCompleted() called twice with same key and same body does not throw; second call is a no-op or overwrites with identical data
- saveCompleted() called with same key but different body (crash + different outcome) logs a WARN and stores the second value (last-writer-wins; operator should investigate via audit log)
- acquireLock with LOCK_TTL=1s; after 1.1 s, a second acquireLock on the same key returns true (lock auto-expired)
- No permanent deadlock: after any scenario, subsequent requests with the same key either get Replay or Proceed within 35 s
**Depends on:** 5.7-T02, 5.7-T03

### 5.7-T09 — Implement duplicate partner_txn_ref detection (DUPLICATE_PARTNER_TXN_REF)  _(35 min)_
**Context:** API-05 error table defines error code DUPLICATE_PARTNER_TXN_REF (HTTP 409): partner_txn_ref already used for a different payment by this partner. This is distinct from idempotency: an idempotency retry with the same body is allowed; a new payment submission with a different idempotency key but a previously-used partner_txn_ref is rejected. The transaction table has a UNIQUE constraint on (partner_id, partner_txn_ref). The PaymentService must catch the unique-constraint violation and translate it to this error code.
**Steps:** Confirm the transaction table has UNIQUE constraint on (partner_id, partner_txn_ref) -- if not, add a DB migration (reference 5.7-T10); In PaymentService.createPayment(), catch DataIntegrityViolationException (Spring) or PSQLException with SQLState=23505 on the partner_txn_ref column; Map to ApiException(409, DUPLICATE_PARTNER_TXN_REF, 'partner_txn_ref already used for a different payment by this partner.'); Ensure the check is ONLY for genuinely new idempotency keys; idempotency replay with same body and same partner_txn_ref must not trigger this error (it returns the stored result before DB write); Add unit test: create payment with partner_txn_ref='SENDMN-TXN-001'; attempt second payment with different idempotency_key and same partner_txn_ref='SENDMN-TXN-001'; expect HTTP 409 DUPLICATE_PARTNER_TXN_REF
**Deliverable:** Exception mapping in PaymentService.java for DUPLICATE_PARTNER_TXN_REF
**Acceptance / logic checks:**
- POST /v1/payments with idempotency_key=K1, partner_txn_ref=REF-001 succeeds (201)
- POST /v1/payments with idempotency_key=K2 (new key), partner_txn_ref=REF-001 (same ref) returns HTTP 409 DUPLICATE_PARTNER_TXN_REF
- POST /v1/payments with idempotency_key=K1 (same key), same body returns HTTP 2xx replay (idempotency, not duplicate error)
- The error response body follows the standard error envelope: {error_code: DUPLICATE_PARTNER_TXN_REF, message: '...', txn_ref: null}
- DataIntegrityViolationException on a different column (e.g. scheme_ref) does not map to DUPLICATE_PARTNER_TXN_REF
**Depends on:** 5.7-T05

### 5.7-T10 — DB migration: UNIQUE constraint on (partner_id, partner_txn_ref) in transaction table  _(20 min)_
**Context:** The transaction table must enforce uniqueness of partner_txn_ref per partner at the DB level (not just application level) to prevent race conditions under concurrent inserts. The migration adds a unique index on (partner_id, partner_txn_ref). partner_txn_ref is nullable (scheme-initiated payments may lack it), so the constraint must allow multiple NULL values -- use a partial index: WHERE partner_txn_ref IS NOT NULL.
**Steps:** Create Flyway migration V57__add_unique_partner_txn_ref.sql; Add: CREATE UNIQUE INDEX IF NOT EXISTS uq_transaction_partner_txn_ref ON transaction(partner_id, partner_txn_ref) WHERE partner_txn_ref IS NOT NULL;; Verify the migration is idempotent (IF NOT EXISTS) so re-running does not fail; Run migration against local PostgreSQL 16 on port 5433 (as per project config) and confirm via psql \d transaction; Add a comment in the migration file explaining the partial index rationale (NULLs excluded to allow multiple scheme-only transactions)
**Deliverable:** Flyway migration file V57__add_unique_partner_txn_ref.sql
**Acceptance / logic checks:**
- Migration applies cleanly on a fresh schema with no existing data
- Two rows with partner_id=1, partner_txn_ref='REF-001' cannot coexist (INSERT fails with unique violation)
- Two rows with partner_id=1, partner_txn_ref=NULL can coexist (partial index allows multiple NULLs)
- Two rows with partner_id=1 and partner_id=2 sharing partner_txn_ref='REF-001' can coexist (different partner scopes)
- psql \d transaction shows the index uq_transaction_partner_txn_ref

### 5.7-T11 — Implement GET endpoint idempotency bypass: filter must skip GET requests  _(25 min)_
**Context:** API-05 states idempotency keys are required on POST requests that create or modify resources. GET endpoints (e.g. GET /v1/rates, GET /v1/payments/{id}) are inherently idempotent and must NOT require an Idempotency-Key header. The IdempotencyFilter must skip all non-POST methods. Additionally, health-check and actuator endpoints must be excluded. This ticket tightens the filter's shouldNotFilter() logic.
**Steps:** Override shouldNotFilter(HttpServletRequest request) in IdempotencyFilter; Return true (skip) if request method is not POST; Return true (skip) if request URI matches /actuator/** or /health or /v1/rates (GET-only paths); Add unit tests: verify filter skips GET /v1/payments/pay_001, OPTIONS /v1/payments, and /actuator/health; verify filter runs on POST /v1/payments and POST /v1/rates; Confirm that POST /v1/rates is included (rate quote endpoint is a POST in API-05 and requires idempotency)
**Deliverable:** Updated IdempotencyFilter.shouldNotFilter() method with unit tests
**Acceptance / logic checks:**
- GET /v1/payments/{id} with no Idempotency-Key header receives HTTP 200, not HTTP 400
- POST /v1/rates without Idempotency-Key header receives HTTP 400 MISSING_IDEMPOTENCY_KEY
- POST /v1/payments without Idempotency-Key header receives HTTP 400 MISSING_IDEMPOTENCY_KEY
- GET /actuator/health is never intercepted by IdempotencyFilter
- OPTIONS /v1/payments (CORS preflight) is not intercepted
**Depends on:** 5.7-T05

### 5.7-T12 — Implement X-Request-ID response header on all responses  _(30 min)_
**Context:** API-05 §2.7 states every response (success or error) must include an X-Request-ID response header set by GMEPay+. This is the primary diagnostic key for support escalations. Partners must log this value. The request ID is generated server-side (not partner-supplied), uses the format req_{ULID} (e.g. req_01HX7MNPQRS4T5U6VWXY012Z), and is unrelated to idempotency keys. It is generated fresh for every HTTP transaction including idempotency replays.
**Steps:** Create RequestIdFilter extends OncePerRequestFilter; Generate a ULID or UUID-based ID with prefix req_ on every request: String requestId = 'req_' + UlidCreator.getMonotonicUlid().toString(); Store requestId in request attribute and add to MDC for log correlation: MDC.put('requestId', requestId); Add X-Request-ID: {requestId} header to every response including idempotency replays and error responses; Register filter at lowest order (runs first) so all subsequent filters and handlers can read the requestId from request attributes; Add unit test: any POST returns response with X-Request-ID header matching pattern req_[A-Z0-9]{26}
**Deliverable:** RequestIdFilter.java registered as a Spring @Component
**Acceptance / logic checks:**
- Every HTTP response (200, 201, 400, 409, 422, 500) includes X-Request-ID header
- X-Request-ID value matches pattern req_[A-Za-z0-9]{20,30}
- Each request generates a unique X-Request-ID (two concurrent requests have different IDs)
- Idempotency replay responses also include a fresh X-Request-ID (not the original request's ID)
- X-Request-ID appears in application logs via MDC for the same request

### 5.7-T13 — Unit tests: idempotency happy-path and replay scenarios  _(55 min)_
**Context:** Explicit unit-test ticket for IdempotencyService and IdempotencyFilter. All logic-bearing paths must be covered with concrete input/output vectors. Uses MockRedis (embedded Redis or Mockito mocks). Tests verify the full state machine: Proceed -> complete -> Replay; InFlight; body-mismatch; missing key; key length boundary.
**Steps:** Create IdempotencyServiceTest with MockBean IdempotencyRepository; Test 1 (Proceed): partnerId=p1, key=K1, bodyHash=H1 -> check returns Proceed; Test 2 (complete + Replay): after complete(p1, K1, 201, '{"id":"pay_001"}'), check(p1, K1, H1) returns Replay{status=201, body='{"id":"pay_001"}'}; Test 3 (InFlight): acquireLock mocked to return false -> check returns InFlight; Test 4 (body mismatch): complete(p1, K1, 201, '...'); check(p1, K1, H2) where H2 != H1 -> throws IdempotencyKeyReuseException; Test 5 (4xx stored): complete(p1, K1, 422, '{"error_code":"RATE_QUOTE_EXPIRED"}'); check(p1, K1, H1) returns Replay{status=422}; Test 6 (missing key): IdempotencyFilter with no Idempotency-Key header returns MockMvc response status 400, error_code MISSING_IDEMPOTENCY_KEY; Test 7 (length boundary): key of 16 chars passes; key of 15 chars returns 400; key of 128 chars passes; key of 129 chars returns 400
**Deliverable:** IdempotencyServiceTest.java and IdempotencyFilterTest.java with 7 test methods
**Acceptance / logic checks:**
- All 7 tests pass (mvn test -pl idempotency-module)
- Test 2 verifies exact body string match: '{"id":"pay_001"}'
- Test 4 throws IdempotencyKeyReuseException (not a generic exception)
- Test 6 verifies response body contains error_code field equal to MISSING_IDEMPOTENCY_KEY
- Test 7 boundary: key='1234567890123456' (16 chars) returns Proceed; key='123456789012345' (15 chars) returns HTTP 400
**Depends on:** 5.7-T03, 5.7-T05, 5.7-T06

### 5.7-T14 — Unit tests: concurrent in-flight deduplication under race conditions  _(50 min)_
**Context:** The in-flight lock mechanism (Redis SET NX) must prevent double-processing when two requests with the same idempotency key arrive simultaneously. This test uses a CountDownLatch to simulate concurrent request handling and verifies that exactly one request proceeds and the other returns InFlight. Uses a real embedded Redis (Testcontainers Redis or spring-boot-testcontainers) to test actual NX semantics rather than mocks.
**Steps:** Add Testcontainers Redis dependency to test scope if not already present; Create IdempotencyRaceConditionTest using @SpringBootTest with embedded Redis container; Submit two threads simultaneously with same partnerId=p1, key=K1, bodyHash=H1 using ExecutorService with CountDownLatch; Collect both IdempotencyDecision results; assert exactly 1 is Proceed and exactly 1 is InFlight; Repeat the test 10 times in a loop to catch non-deterministic failures; Add test: lock expires after 30 s (mock clock or set LOCK_TTL=1 s in test); after expiry, new request returns Proceed again
**Deliverable:** IdempotencyRaceConditionTest.java using Testcontainers Redis
**Acceptance / logic checks:**
- Exactly 1 of 2 concurrent requests returns Proceed; exactly 1 returns InFlight across 10 runs
- Neither request returns an exception or error state
- After lock TTL expiry (1 s in test), a third request with the same key returns Proceed
- The embedded Redis container's NX semantics match production Redis 7 behaviour
- Test completes in under 10 s (no deadlocks)
**Depends on:** 5.7-T02, 5.7-T03

### 5.7-T15 — Unit tests: DUPLICATE_PARTNER_TXN_REF detection and idempotency interaction  _(40 min)_
**Context:** Tests that verify the boundary between idempotency replay (same key, same body -> replayed response) and DUPLICATE_PARTNER_TXN_REF (new key, same partner_txn_ref -> 409). These are different mechanisms: idempotency is Redis-based (pre-DB), duplicate partner_txn_ref is DB-constraint-based (post-DB). The test must confirm they do not interfere with each other.
**Steps:** Create PaymentServiceDuplicateTxnRefTest using @DataJpaTest or @SpringBootTest with test DB; Test 1: POST payment with key=K1, partner_txn_ref=REF-001 -> 201 (succeeds); Test 2: POST payment with key=K1 (same), same body -> 201 replay (idempotency, not duplicate error); Test 3: POST payment with key=K2 (new key), partner_txn_ref=REF-001 (same ref) -> 409 DUPLICATE_PARTNER_TXN_REF; Test 4: POST payment with key=K3 (new key), partner_txn_ref=REF-002 (new ref) -> 201 (different ref, succeeds); Assert error envelopes contain correct error_code field for each case
**Deliverable:** PaymentServiceDuplicateTxnRefTest.java with 4 test methods
**Acceptance / logic checks:**
- Test 1 returns HTTP 201 with a txn_ref in the response body
- Test 2 returns HTTP 201 with the identical txn_ref from Test 1 (replay)
- Test 3 returns HTTP 409 with error_code=DUPLICATE_PARTNER_TXN_REF and no txn_ref field
- Test 4 returns HTTP 201 with a new distinct txn_ref
- The DB contains exactly 2 transaction rows after all 4 tests (K1/REF-001 and K3/REF-002)
**Depends on:** 5.7-T09, 5.7-T10, 5.7-T13

### 5.7-T16 — Integration test: safe retry guidance -- 5xx retry with same key succeeds  _(55 min)_
**Context:** API-05 §7.2 safe retry guidance: on HTTP 5xx, partner retries with the SAME Idempotency-Key after exponential back-off. If the first request failed mid-way (e.g. scheme timeout producing UNCERTAIN state), the retry must return the stored response if the first request completed (even with 5xx), or process as new if the first never completed (lock expired). This integration test covers both scenarios.
**Steps:** Test 1 (5xx stored): mock the payment handler to return 500 on first call; IdempotencyFilter must store the 500 response; second call with same key returns stored 500 without re-invoking handler; Test 2 (no response stored, lock expired): simulate crash by calling acquireLock without complete(); advance clock past 30 s lock TTL; retry with same key -> Proceed (re-processes as new); Test 3 (network timeout, UNCERTAIN state): mock scheme call to time out; transaction enters UNCERTAIN state; response to partner is 202 UNCERTAIN; retry with same key returns stored 202 UNCERTAIN without re-submitting to scheme; Assert in Test 3 that the scheme mock was called exactly once (not twice)
**Deliverable:** IdempotencyRetryIntegrationTest.java with 3 test methods
**Acceptance / logic checks:**
- Test 1: handler invocation count = 1; both first and second call return HTTP 500 with same body
- Test 2: after lock TTL expiry, same key is treated as new (handler invocation count = 2)
- Test 3: scheme mock call count = 1 despite two HTTP requests with same key
- Test 3: second request returns X-Idempotency-Status: replayed header
- All 3 tests pass with no false positives across 5 consecutive runs
**Depends on:** 5.7-T07, 5.7-T08, 5.7-T13

### 5.7-T17 — Implement idempotency key TTL expiry: re-used expired key treated as new request  _(25 min)_
**Context:** API-05 §7.3: keys expire after 24 hours. After expiry, a re-used key is treated as a new request (not an error). The Redis TTL of 86400 s handles this automatically -- once the key expires, findCompleted() returns empty and the request is processed fresh. No special application code is needed for expiry itself, but the service must not interpret 'key not found' as an error. This ticket verifies the expiry behaviour and adds a test.
**Steps:** Confirm IdempotencyService.check() treats Optional.empty() from findCompleted() as Proceed (not an error) -- this should already be the case from T03; Add unit test with MOCK clock: store a record with createdAt = now() - 86401 s (simulated by setting Redis TTL=1 in test); after TTL expires, check returns Proceed; Add a warning log in IdempotencyService when a key that was previously in the completed state (found in findCompleted) has been re-submitted after expiry -- this is informational only; Document in code comments: 'After 24 h TTL expiry, the key is gone from Redis; a new submission with the same key string is a fresh request per API-05 §7.3'
**Deliverable:** Updated IdempotencyService.java with expiry test in IdempotencyServiceTest.java
**Acceptance / logic checks:**
- After Redis TTL expires (simulated via LOCK_TTL=1 s in test), check() returns Proceed, not Replay
- No exception is thrown when the key has expired and is resubmitted
- The 24-hour TTL (86400 s) is set on the result key in saveCompleted() -- verifiable via Redis TTL command in integration test
- A key that expires and is resubmitted is treated identically to a brand-new key (no history stored after expiry)
**Depends on:** 5.7-T03, 5.7-T13

### 5.7-T18 — Wire IdempotencyFilter into Spring Security filter chain at correct order  _(35 min)_
**Context:** The IdempotencyFilter must be placed AFTER the AuthenticationFilter (HMAC signature validation) but BEFORE any business-logic handler. Reason: idempotency replay should only work for authenticated requests (prevents replay of cached responses with a stolen key by an unauthenticated caller). If auth fails, the request is rejected before idempotency lookup. Spring Security filter order is managed via FilterOrderRegistration or @Order annotation. Auth filter order must be explicitly confirmed.
**Steps:** Check the existing AuthenticationFilter @Order or SecurityFilterChain configuration to determine its numeric order; Set IdempotencyFilter @Order to AuthFilter_order + 1 (e.g. if AuthFilter = 10, set IdempotencyFilter = 11); Add integration test: unauthenticated POST with valid Idempotency-Key returns HTTP 401 INVALID_API_KEY (not 400 MISSING_IDEMPOTENCY_KEY and not a replay); Add integration test: authenticated POST with valid key processes normally through idempotency logic; Verify filter ordering in Spring context using FilterChainProxy.getFilterChains() in a @SpringBootTest
**Deliverable:** IdempotencyFilter order configuration with integration tests confirming auth-before-idempotency
**Acceptance / logic checks:**
- Unauthenticated request returns HTTP 401 (auth fails) before idempotency check runs
- Authenticated request with missing Idempotency-Key returns HTTP 400 MISSING_IDEMPOTENCY_KEY (auth passes, idempotency fails)
- IdempotencyFilter appears after AuthenticationFilter in FilterChainProxy order
- A stored idempotency replay is only returned to a caller that presents valid HMAC credentials
- No NullPointerException occurs when auth filter populates security context before idempotency filter reads partner_id
**Depends on:** 5.7-T05, 5.7-T12

### 5.7-T19 — Idempotency metrics and observability: Micrometer counters  _(30 min)_
**Context:** Operations needs visibility into idempotency behaviour to detect anomalies (e.g. a partner sending the same key repeatedly indicating retry storms). Add Micrometer counters for: idempotency.replay (tagged by endpoint), idempotency.in_flight, idempotency.key_reuse (body mismatch), idempotency.missing_key. These counters feed the existing observability stack (Prometheus / Grafana per OPS-13).
**Steps:** Inject MeterRegistry into IdempotencyFilter and IdempotencyService; Increment counter idempotency.replay with tag endpoint={requestURI} on every Replay decision; Increment counter idempotency.in_flight on every InFlight decision; Increment counter idempotency.key_reuse on every IdempotencyKeyReuseException; Increment counter idempotency.missing_key on every missing-header rejection; Add unit test asserting counters are incremented: use SimpleMeterRegistry; simulate each scenario; assert counter value = 1
**Deliverable:** Micrometer counter instrumentation in IdempotencyFilter.java and IdempotencyService.java with unit tests
**Acceptance / logic checks:**
- After one Replay event, counter idempotency.replay{endpoint=/v1/payments} = 1
- After one InFlight event, counter idempotency.in_flight = 1
- After one body-mismatch event, counter idempotency.key_reuse = 1
- After one missing-key rejection, counter idempotency.missing_key = 1
- Counters are exported to /actuator/prometheus endpoint in integration test
**Depends on:** 5.7-T05, 5.7-T03

### 5.7-T20 — Document idempotency service API contract in Javadoc and package README  _(30 min)_
**Context:** A developer with no project context must be able to use the idempotency service from Javadoc alone. This ticket adds Javadoc to all public methods in IdempotencyService, IdempotencyRepository, and IdempotencyFilter, and a brief package-info.java explaining the Redis key schema, TTL values, and the three-state decision model (Proceed / Replay / InFlight). No separate markdown file is needed; Javadoc is sufficient.
**Steps:** Add Javadoc to IdempotencyService.check(): document all three return variants, the body-hash mismatch exception, and the Redis key format; Add Javadoc to IdempotencyService.complete(): document when to call it, idempotency of the call itself, and what happens on duplicate complete() calls; Add Javadoc to IdempotencyRepository interface methods: document Redis commands used, TTL semantics, and NX semantics for acquireLock; Create package-info.java for the idempotency package with a 5-10 line summary: purpose, Redis key schema (idem:{partnerId}:{key}, idem_lock:{partnerId}:{key}), TTL (86400 s / 30 s), three-state model, and reference to API-05 §7; Run javadoc generation and verify no warnings for the idempotency package
**Deliverable:** Javadoc on all public methods in the idempotency package + package-info.java
**Acceptance / logic checks:**
- javadoc -d /tmp/docs src/main/java/**/idempotency/** produces zero warnings
- IdempotencyService.check() Javadoc describes all 3 return types and the body-mismatch exception
- package-info.java states the Redis key format idem:{partnerId}:{idempotencyKey} and the 86400 s TTL
- No @param or @return tags are missing on any public method in the idempotency package
- package-info.java is present and non-empty in the compiled JAR (visible in javadoc index)
**Depends on:** 5.7-T03, 5.7-T02, 5.7-T05
