# payment-executor  (backend)

**Scope:** CPM/MPM orchestration, CommitTransaction, cancel, direction handling

**Owned WBS work-packages:** 5.2, 5.5, 5.6, 5.8, 8.4  ·  **Tickets:** 150  ·  **Est:** 97.9h

## Service contract (MSA: own DB, API-only communication)

- **Datastore (owned by this service):** none (orchestrator); Redis idempotency
- **APIs / events I EXPOSE:** POST /v1/payments, /v1/payments/cpm/generate, /cancel; events payment.approved, payment.failed
- **APIs / events I CONSUME:** smart-router, config-registry, rate-fx, prefunding(deduct), qr-service, scheme-adapter, transaction-mgmt — all sync
- **Integration rule:** never read another service's database or import its private entities — call its API or consume its event; stub consumed services with WireMock in tests.

> Self-contained backlog for this service. Build it as its own repo/module with its own DB + Flyway migrations, against the `shared-libs` contracts (lib-money / lib-errors / lib-events / lib-api-contracts only). Each ticket has a deliverable + acceptance checks.


## WBS 5.2 — Fixed MPM execution flow
### 5.2-T01 — Add payment_mode column and MPM constraint to transaction table  _(30 min)_
**Context:** The transaction table (DAT-03 §10.1) needs a payment_mode column (VARCHAR(5), CHECK IN ('MPM','CPM'), NOT NULL) to distinguish Fixed MPM from CPM payments. Without this column the MPM execution handler cannot record mode at insert time. The column must be NOT NULL with no default so existing untyped rows are caught at migration.
**Steps:** Write a Flyway/Liquibase migration: ALTER TABLE transaction ADD COLUMN payment_mode VARCHAR(5) NOT NULL CHECK (payment_mode IN ('MPM','CPM')); Add a partial index on (partner_id, status) WHERE payment_mode = 'MPM' to support prefunding deduction queries; Add prefunding_deducted_usd DECIMAL(20,4) NULL column if not already present (OVERSEAS only; NULL for LOCAL); Update the JPA/Hibernate entity class Transaction to add field paymentMode (enum PaymentMode {MPM, CPM}) and prefundingDeductedUsd (BigDecimal, nullable); Run migration against a local test DB; verify schema with INFORMATION_SCHEMA query
**Deliverable:** Flyway migration file V5_2_001__add_payment_mode_prefunding.sql and updated Transaction entity class
**Acceptance / logic checks:**
- Migration applies cleanly on a fresh schema and on a schema that already has existing rows (existing rows must require explicit backfill — migration must NOT silently default to MPM)
- transaction.payment_mode rejects any value other than MPM or CPM at DB level
- prefunding_deducted_usd column accepts NULL (LOCAL partner) and a positive DECIMAL value (OVERSEAS partner)
- Partial index on payment_mode='MPM' is visible in pg_indexes
- Entity field paymentMode maps to DB column without a default value — inserting a Transaction without paymentMode throws a constraint violation

### 5.2-T02 — Define MpmPaymentRequest and MpmPaymentResponse Java records for POST /v1/payments  _(35 min)_
**Context:** POST /v1/payments executes a Fixed MPM payment. Request fields (API-05 §4.3): quote_id (String, required), merchant_qr (String, required), direction (String, required), scheme_id (String, required), customer_ref (String, required), partner_txn_ref (String, required), collection_amount (String decimal, required), collection_currency (String, required), country_code (String, optional). Response fields: payment_id, status, scheme_txn_id, merchant_name, merchant_id, target_payout, payout_currency, offer_rate, collection_amount, collection_currency, service_charge, service_charge_currency, prefund_deducted_usd (OVERSEAS only), partner_txn_ref, created_at, approved_at.
**Steps:** Create MpmPaymentRequest record with @NotNull/@NotBlank validations on all required fields; collection_amount must match regex ^[0-9]+(\.[0-9]+)?$; Create MpmPaymentResponse record with all response fields; prefund_deducted_usd annotated @Nullable; Add Jackson @JsonProperty mappings for snake_case API field names; Write unit test MpmPaymentRequestTest: deserialise the example request JSON from spec and assert all fields parse correctly; Write unit test MpmPaymentResponseTest: serialise a fully-populated response and assert all required keys are present; assert prefund_deducted_usd is absent when null
**Deliverable:** MpmPaymentRequest.java, MpmPaymentResponse.java, and their unit test classes
**Acceptance / logic checks:**
- Deserialising the spec example body (quote_id=qte_01HX7MNP9AB..., collection_amount=35.77, collection_currency=USD) populates all fields with correct types
- Validation fails (ConstraintViolationException) when quote_id is null or blank
- Validation fails when collection_amount is not a valid decimal string (e.g. abc or -1)
- Serialising MpmPaymentResponse with prefund_deducted_usd=null omits the field from JSON (use @JsonInclude(NON_NULL))
- country_code is absent from validation annotations (optional field)
**Depends on:** 5.2-T01

### 5.2-T03 — Add quote_id lookup and TTL-expiry check to CommitTransaction service  _(45 min)_
**Context:** At POST /v1/payments the Orchestrator must call lockRateQuote(quote_id). A rate quote is stored in Redis with key rate_quote:{quote_id}, TTL = partner.rate_quote_ttl_seconds (default 300 s; range 60-1800 s). valid_until = quote_issued_at + ttl_seconds. If the quote is absent (TTL elapsed) or valid_until < NOW(UTC), reject with error code RATE_QUOTE_EXPIRED (HTTP 422). The quote record carries partner_id; reject with RATE_QUOTE_INVALID (HTTP 422) if partner_id on the quote does not match the authenticated caller.
**Steps:** In RateQuoteService.lockRateQuote(String quoteId, long partnerId): fetch the Redis key rate_quote:{quoteId}; If key missing, throw RateQuoteExpiredException(quoteId) -> mapped to HTTP 422 RATE_QUOTE_EXPIRED; If found, parse validUntil field; if Instant.now().isAfter(validUntil), throw same exception; If partner_id in stored quote != partnerId param, throw RateQuoteInvalidException -> HTTP 422 RATE_QUOTE_INVALID; Mark quote as consumed (delete or set a 'used' flag) atomically using Redis GETDEL or Lua script to prevent double-spend
**Deliverable:** RateQuoteService.lockRateQuote() method and unit tests RateQuoteLockTest
**Acceptance / logic checks:**
- lockRateQuote returns the full quote object when quote exists, valid_until is 10 s in the future, and partner_id matches
- lockRateQuote throws RateQuoteExpiredException when Redis key is missing
- lockRateQuote throws RateQuoteExpiredException when valid_until is 1 ms in the past
- lockRateQuote throws RateQuoteInvalidException when stored partner_id=101 but caller partner_id=102
- A second call with the same quote_id after a successful lock throws RateQuoteExpiredException (quote consumed, cannot be double-spent)
**Depends on:** 5.2-T01

### 5.2-T04 — Implement atomic prefunding deduction via SELECT FOR UPDATE on prefunding_account  _(55 min)_
**Context:** For OVERSEAS partners (partner.type = 'OVERSEAS'), prefunding must be deducted atomically at POST /v1/payments before the scheme is called. Rules: deduct collection_usd (the USD pool gross from the rate quote, DECIMAL(20,4)). Use SELECT ... FOR UPDATE on prefunding_account WHERE partner_id = ? within a DB transaction. If balance < collection_usd, throw InsufficientPrefundingException -> HTTP 402 INSUFFICIENT_PREFUNDING. On success: UPDATE prefunding_account SET balance = balance - collection_usd, updated_at = NOW(); INSERT INTO prefunding_ledger_entry (account_id, txn_ref, entry_type='DEBIT_PAYMENT', amount=collection_usd, balance_after=new_balance, created_at=NOW()). LOCAL partners (partner.type = 'LOCAL') skip this step entirely.
**Steps:** In PrefundingService.deductForMpm(long partnerId, String txnRef, BigDecimal collectionUsd): open a DB transaction with isolation level SERIALIZABLE or use SELECT FOR UPDATE; Execute SELECT id, balance FROM prefunding_account WHERE partner_id = :partnerId FOR UPDATE; If balance < collectionUsd throw InsufficientPrefundingException with current balance and required amount; UPDATE prefunding_account SET balance = balance - :collectionUsd, updated_at = NOW() WHERE id = :accountId; INSERT into prefunding_ledger_entry with entry_type=DEBIT_PAYMENT; Return PrefundingDeductionResult(accountId, deductedUsd, balanceAfter)
**Deliverable:** PrefundingService.deductForMpm() method and PrefundingServiceTest unit tests
**Acceptance / logic checks:**
- deductForMpm with balance=100.00 USD and collectionUsd=35.77 returns balanceAfter=64.23 and inserts one ledger row with amount=35.77 entry_type=DEBIT_PAYMENT
- deductForMpm with balance=30.00 USD and collectionUsd=35.77 throws InsufficientPrefundingException; no ledger row inserted; prefunding_account.balance unchanged
- Concurrent calls for the same partner with collectionUsd=60.00 each against balance=80.00 result in exactly one success and one InsufficientPrefundingException (no over-draw)
- LOCAL partner partner_id returns immediately without touching prefunding_account or prefunding_ledger_entry
- prefunding_ledger_entry.balance_after equals prefunding_account.balance after the same transaction commits (snapshot consistency)
**Depends on:** 5.2-T01

### 5.2-T05 — Implement merchant QR resolution and validation for MPM payment  _(35 min)_
**Context:** POST /v1/payments requires merchant_qr (the QR code string scanned by the customer). The QR must resolve to an active merchant in the local merchants table (kept in sync by daily ZeroPay batch). Fields needed: merchant_id, merchant_name, payout_currency. If qr not found -> MERCHANT_NOT_FOUND (HTTP 404). If merchant.status != 'active' -> also MERCHANT_NOT_FOUND per spec. The scheme_id from the request must match the scheme associated with the merchant's QR code; mismatch -> VALIDATION_ERROR. payout_currency on the resolved merchant must match payout_ccy of the rate quote; mismatch -> VALIDATION_ERROR.
**Steps:** In MerchantService.resolveForPayment(String merchantQr, String schemeId, String quotedPayoutCcy): query merchants table JOIN qr_codes on qr_code = merchantQr; If no row returned or merchant.status != 'active', throw MerchantNotFoundException(merchantQr) -> HTTP 404 MERCHANT_NOT_FOUND; If qr_codes.scheme_id != schemeId param, throw ValidationException('scheme_id mismatch'); If merchant.payout_currency != quotedPayoutCcy, throw ValidationException('payout_currency mismatch'); Return MerchantResolution(merchantId, merchantName, payoutCurrency)
**Deliverable:** MerchantService.resolveForPayment() and MerchantServiceTest
**Acceptance / logic checks:**
- resolveForPayment with QR=ZPQR00012345 (active, scheme=zeropay, payout_ccy=KRW) returns correct merchantId and merchantName
- resolveForPayment with unknown QR throws MerchantNotFoundException
- resolveForPayment with inactive merchant status throws MerchantNotFoundException (same error as not found, no information leakage)
- resolveForPayment with scheme_id=wrongscheme throws ValidationException mentioning scheme_id
- resolveForPayment with quotedPayoutCcy=USD when merchant.payout_currency=KRW throws ValidationException mentioning payout_currency
**Depends on:** 5.2-T01

### 5.2-T06 — Implement direction and mode eligibility check against partner Rule  _(30 min)_
**Context:** Each payment must be permitted by a Rule record (partner x scheme x direction). Rules: (1) A Rule row must exist in the rule table for (partner_id, scheme_id, direction); if absent -> DIRECTION_NOT_ENABLED (HTTP 422). (2) Rule.supported_modes must include 'MPM' (values: 'MPM', 'CPM', 'BOTH'); if not -> PAYMENT_MODE_NOT_SUPPORTED (HTTP 422). The direction in the POST /v1/payments request must exactly match the direction used in the POST /v1/rates request (stored in the rate quote record); mismatch -> VALIDATION_ERROR.
**Steps:** In RuleService.validateMpmEligibility(long partnerId, String schemeId, String direction, String quoteDirection): fetch Rule WHERE partner_id=? AND scheme_id=? AND direction=?; If no rule found, throw DirectionNotEnabledException -> HTTP 422 DIRECTION_NOT_ENABLED; If rule.supported_modes not in ('MPM','BOTH'), throw PaymentModeNotSupportedException -> HTTP 422 PAYMENT_MODE_NOT_SUPPORTED; If direction != quoteDirection, throw ValidationException('direction mismatch between payment and quote'); Return the resolved Rule object for use downstream (carries m_a, m_b, service_charge, etc.)
**Deliverable:** RuleService.validateMpmEligibility() and RuleServiceTest
**Acceptance / logic checks:**
- Rule with supported_modes='MPM' and direction='inbound' passes validation
- Rule with supported_modes='CPM' throws PaymentModeNotSupportedException
- Rule with supported_modes='BOTH' passes validation
- No rule row for (partner=99, scheme=zeropay, direction=inbound) throws DirectionNotEnabledException
- Payment direction='outbound' but quote direction='inbound' throws ValidationException regardless of rule
**Depends on:** 5.2-T01

### 5.2-T07 — Implement idempotency check for POST /v1/payments using partner_txn_ref  _(45 min)_
**Context:** partner_txn_ref must be unique per partner. If a POST /v1/payments arrives with a partner_txn_ref that already exists for the same partner_id with a DIFFERENT request body -> HTTP 409 DUPLICATE_PARTNER_TXN_REF. If the Idempotency-Key header matches a prior completed request with the same body -> return stored response without reprocessing (HTTP 201). If Idempotency-Key is missing -> HTTP 400 MISSING_IDEMPOTENCY_KEY. Storage: idempotency_cache table or Redis key idempotency:{partner_id}:{idempotency_key} with TTL 24 h, storing {response_status, response_body}.
**Steps:** In IdempotencyService.checkOrReserve(long partnerId, String idempotencyKey, String requestBodyHash): look up Redis key idempotency:{partnerId}:{idempotencyKey}; If key exists and body hash matches, return IdempotencyResult.REPLAY(storedResponseStatus, storedBody); If key exists and body hash differs, return IdempotencyResult.CONFLICT -> HTTP 422 IDEMPOTENCY_KEY_REUSE; If key absent, create a reservation (set key with status=IN_FLIGHT, TTL=24h) and return IdempotencyResult.NEW; Separately in TransactionService: before processing, check transaction table for partner_txn_ref uniqueness per partner_id; if duplicate with different payment_id -> HTTP 409 DUPLICATE_PARTNER_TXN_REF
**Deliverable:** IdempotencyService.checkOrReserve() and TransactionService duplicate-ref check, with IdempotencyServiceTest
**Acceptance / logic checks:**
- First call with idempotency_key=abc and body_hash=xyz returns IdempotencyResult.NEW and sets Redis key with TTL ~86400s
- Second call with same key and same body returns IdempotencyResult.REPLAY with stored 201 response
- Call with same key but different body returns IdempotencyResult.CONFLICT
- Two concurrent first calls with the same key and same body: exactly one proceeds as NEW; the other returns IN_FLIGHT (HTTP 409 with X-Idempotency-Status: in_flight)
- POST with existing partner_txn_ref for a different payment_id returns HTTP 409 DUPLICATE_PARTNER_TXN_REF
**Depends on:** 5.2-T01

### 5.2-T08 — Implement POST /v1/payments controller handler wiring all validation steps  _(55 min)_
**Context:** POST /v1/payments orchestrates: (1) auth + HMAC validation (pre-filter), (2) Idempotency-Key check, (3) partner_txn_ref uniqueness, (4) quote TTL lock (5.2-T03), (5) merchant resolution (5.2-T05), (6) rule/mode eligibility (5.2-T06), (7) prefunding deduction for OVERSEAS (5.2-T04), (8) rate-lock all USD pool values onto transaction record, (9) call ZeroPay adapter submitMpm(), (10) transition transaction to APPROVED, (11) enqueue payment.approved webhook. Order is strict: prefunding deduction (step 7) must occur after all validations (steps 1-6) and before scheme call (step 9). Scheme is NEVER called if prefunding deduction fails.
**Steps:** Add @PostMapping('/v1/payments') in PaymentController, accept MpmPaymentRequest + @RequestHeader Idempotency-Key; Call idempotencyService.checkOrReserve; if REPLAY return stored response immediately; Sequentially call: lockRateQuote, resolveForPayment, validateMpmEligibility; For OVERSEAS partner call prefundingService.deductForMpm; if InsufficientPrefundingException return HTTP 402 before any scheme call; Call schemeAdapter.submitMpm(); on success persist transaction in APPROVED state with all locked USD pool values and prefunding_deducted_usd; Enqueue WebhookEvent(payment.approved) asynchronously; update idempotency cache with 201 response; Return HTTP 201 MpmPaymentResponse; include prefund_deducted_usd only for OVERSEAS partners
**Deliverable:** PaymentController.executeMpmPayment() handler and integration test MpmPaymentControllerIT
**Acceptance / logic checks:**
- Valid request for OVERSEAS partner returns HTTP 201 with prefund_deducted_usd populated
- Valid request for LOCAL partner (GME Remit) returns HTTP 201 without prefund_deducted_usd field
- If prefunding deduction fails, controller returns HTTP 402 INSUFFICIENT_PREFUNDING and schemeAdapter.submitMpm is never called (verify with mock)
- Expired quote returns HTTP 422 RATE_QUOTE_EXPIRED before any deduction attempt
- Missing Idempotency-Key header returns HTTP 400 MISSING_IDEMPOTENCY_KEY
**Depends on:** 5.2-T03, 5.2-T04, 5.2-T05, 5.2-T06, 5.2-T07

### 5.2-T09 — Implement rate-lock: copy all USD pool values to transaction record at commit  _(45 min)_
**Context:** On CommitTransaction success (after scheme approval), all USD pool values must be permanently recorded on the transaction row. Fields to lock (from rate_quote): collection_usd, payout_usd_cost, collection_margin_usd, payout_margin_usd, send_amount, service_charge, offer_rate_coll (derived: send_amount / (collection_usd - collection_margin_usd)), cross_rate (derived: target_payout / send_amount), cost_rate_coll, cost_rate_pay, committed_at=NOW(). Pool identity invariant: collection_usd - collection_margin_usd - payout_margin_usd == payout_usd_cost (tolerance 0.01 USD). For same-currency short-circuit (is_same_ccy_shortcircuit=true), USD pool fields are NULL; collection_amount = target_payout + service_charge.
**Steps:** In TransactionService.applyRateLock(Transaction txn, RateQuote quote): copy all USD pool fields from quote to txn; Compute offer_rate_coll = send_amount / (collection_usd - collection_margin_usd); cross_rate = target_payout / send_amount using BigDecimal with HALF_UP, scale 8; Assert pool identity: abs(collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost) <= 0.01; throw RatePoolIdentityException if violated; For is_same_ccy_shortcircuit=true, set all USD pool fields null, verify collection_amount = target_payout + service_charge; Set committed_at = Instant.now(); persist txn; return locked txn
**Deliverable:** TransactionService.applyRateLock() and RateLockTest
**Acceptance / logic checks:**
- For target_payout=50000 KRW, cost_rate_pay=1400.00, m_a=0.025, m_b=0.010: payout_usd_cost=35.714286, collection_usd=37.267081, pool identity holds within 0.01 USD
- offer_rate_coll = 35.714286 / (37.267081 - 0.931677) = 0.97... computed correctly to 8 decimal places
- cross_rate = 50000 / send_amount computed and stored to 8 decimal places
- Same-currency short-circuit: target_payout=15000 KRW, service_charge=500 KRW => collection_amount=15500 KRW, all USD fields null
- Pool identity violation (deliberately corrupted payout_usd_cost off by 0.05 USD) raises RatePoolIdentityException and transaction is NOT committed
**Depends on:** 5.2-T01, 5.2-T03

### 5.2-T10 — Implement ZeroPay adapter submitMpm() method shell and interface contract  _(50 min)_
**Context:** The SchemeAdapter interface (SAD-02 §5.3) defines submitMpm(MpmSubmitRequest) -> MpmSubmitResponse. For ZeroPay, this wraps the 한결원 real-time HTTPS API CommitTransaction call. The adapter must: (1) map internal fields to the ZeroPay wire format, (2) send the request over mTLS-capable HTTPS, (3) parse the ApprovalCode response, (4) map ZeroPay error codes to internal error types. If the scheme returns a decline, throw SchemeDeclinedException. If no response within scheme SLA (configurable, default 10 s), throw SchemeTimeoutException (triggers UNCERTAIN state). This ticket implements the adapter shell and interface; the actual 한결원 API wire format is defined by ZeroPay's spec document (obtain from GME infra team). Use a stub/mock for the HTTP layer.
**Steps:** Define MpmSubmitRequest(String txnRef, String merchantId, BigDecimal payoutAmount, String payoutCurrency, String schemeHubTxnRef) and MpmSubmitResponse(String schemeApprovalCode, String schemeTxnRef, Instant approvedAt); Implement ZeroPayAdapter.submitMpm: build ZeroPay request DTO, call zeroPayHttpClient.post('/commit', dto, timeout=schemeTimeoutMs); On HTTP 200 with approval code, return MpmSubmitResponse; On HTTP 4xx/5xx from ZeroPay, throw SchemeDeclinedException(schemeErrorCode, schemeMessage); On timeout (configurable timeout_ms, default 10000), throw SchemeTimeoutException triggering UNCERTAIN state in Orchestrator; Add unit tests with MockWebServer for approved, declined, and timeout scenarios
**Deliverable:** ZeroPayAdapter.submitMpm() implementation and ZeroPayAdapterTest with mocked HTTP responses
**Acceptance / logic checks:**
- Mock HTTP 200 response with approval_code=ZP20260604093115001234 -> MpmSubmitResponse.schemeApprovalCode equals that value
- Mock HTTP 400 from ZeroPay scheme -> SchemeDeclinedException thrown with scheme error code populated
- Mock request that times out after 10001 ms -> SchemeTimeoutException thrown (not a declined exception)
- Timeout value reads from schemeConfig.timeoutMs property (default 10000); changing it to 5000 in config changes the client timeout
- MpmSubmitRequest maps txnRef to the ZeroPay hub_txn_ref field (exact field name TBD per ZeroPay spec — use a TODO comment)
**Depends on:** 5.2-T01

### 5.2-T11 — Implement UNCERTAIN state handling when ZeroPay scheme times out on MPM submit  _(45 min)_
**Context:** If ZeroPayAdapter.submitMpm() throws SchemeTimeoutException, the Orchestrator must NOT reverse the prefunding deduction. The transaction transitions to UNCERTAIN state. Rules (SAD-02 §5.2): prefunding deduction is held; the UNCERTAIN transaction is flagged for the Settlement Engine which resolves it via ZP0012 batch file within 24 h. If batch confirms FAILED, PrefundingService.reverseForTransaction() is called. If batch confirms APPROVED, transaction moves to APPROVED. Ops alert fires if UNCERTAIN transaction is unresolved after 24 h.
**Steps:** In TransactionOrchestrator after catching SchemeTimeoutException: transition txn.status to UNCERTAIN, set txn.updated_at=NOW(); Do NOT reverse prefunding deduction at this point (deduction stays in place); Persist transaction; emit internal event UncertainTransactionCreated(txnRef, partnerId, createdAt); In SettlementReconciliationService.resolveUncertain(String txnRef, SchemeResult result): if result=APPROVED -> transitionToApproved; if result=FAILED -> transitionToFailed then reverseDeduction; Add a scheduled check: query UNCERTAIN transactions older than 24 h; for each, emit UncertainTransactionAlert to ops alerting channel
**Deliverable:** UNCERTAIN state transition logic in TransactionOrchestrator and SettlementReconciliationService.resolveUncertain()
**Acceptance / logic checks:**
- After SchemeTimeoutException, transaction.status=UNCERTAIN and prefunding_account.balance is unchanged (deduction not reversed)
- resolveUncertain(txnRef, APPROVED) transitions status to APPROVED and does NOT reverse prefunding
- resolveUncertain(txnRef, FAILED) transitions status to FAILED and calls prefundingService.reverseForTransaction creating a CREDIT_REVERSAL ledger entry
- Calling resolveUncertain on a transaction that is already APPROVED throws an IllegalStateException (idempotency guard)
- Scheduled check at T+25h for an UNCERTAIN transaction emits alert event with txnRef and partnerId
**Depends on:** 5.2-T04, 5.2-T10

### 5.2-T12 — Implement prefunding reversal on scheme failure (FAILED state after scheme call)  _(40 min)_
**Context:** If the scheme synchronously declines (SchemeDeclinedException from submitMpm), the transaction moves to FAILED. For OVERSEAS partners, the prefunding deduction must be reversed immediately (unlike UNCERTAIN where it is held). Reversal: INSERT into prefunding_ledger_entry (entry_type='CREDIT_REVERSAL', amount=original_collection_usd, balance_after=balance+original); UPDATE prefunding_account.balance += original_collection_usd. This is the mirror of T04. Must be done in the same DB transaction as the status update. The HTTP response to the partner returns HTTP 422 SCHEME_UNAVAILABLE (or appropriate error mapped from SchemeDeclinedException.schemeErrorCode).
**Steps:** In PrefundingService.reverseForTransaction(String txnRef): fetch prefunding_ledger_entry WHERE txn_ref=txnRef AND entry_type='DEBIT_PAYMENT'; Retrieve original amount from that entry; SELECT FOR UPDATE prefunding_account; UPDATE balance += amount; INSERT prefunding_ledger_entry with entry_type=CREDIT_REVERSAL, same amount, txn_ref=txnRef, balance_after=new_balance; In TransactionOrchestrator.handleSchemeFailure: call reverseForTransaction then set transaction.status=FAILED, persist in same @Transactional boundary; Return HTTP 422 to partner with appropriate error code from SchemeDeclinedException
**Deliverable:** PrefundingService.reverseForTransaction() and TransactionOrchestrator.handleSchemeFailure(), with unit tests
**Acceptance / logic checks:**
- After SchemeDeclinedException, prefunding_account.balance is restored to value before deduction (net zero change)
- Two ledger entries exist for the transaction: one DEBIT_PAYMENT and one CREDIT_REVERSAL, both with the same amount
- reverseForTransaction for a LOCAL partner (no debit entry) returns immediately without touching prefunding tables
- Calling reverseForTransaction twice for the same txnRef throws DuplicateReversalException (guard against double-credit)
- Transaction.status=FAILED is persisted atomically with the CREDIT_REVERSAL ledger insert (same @Transactional)
**Depends on:** 5.2-T04, 5.2-T10

### 5.2-T13 — Enqueue payment.approved webhook after MPM scheme approval  _(50 min)_
**Context:** After the scheme approves an MPM payment (transaction.status=APPROVED), a payment.approved webhook must be dispatched asynchronously to the partner's registered webhook_url. Webhook envelope (API-05 §6.5): {event_id (ulid), event_type='payment.approved', created_at, partner_id, data: {payment_id, partner_txn_ref, scheme_txn_id, merchant_id, merchant_name, direction, scheme_id, target_payout, payout_currency, offer_rate, collection_amount, collection_currency, service_charge, service_charge_currency, prefund_deducted_usd (OVERSEAS only), approved_at}}. The webhook body must be signed: X-GME-Webhook-Signature: sha256=HMAC-SHA256(body, webhook_signing_secret). Retry schedule: immediate, 30 s, 2 min, 10 min, 30 min, 1 h x5 (max 10 attempts).
**Steps:** In WebhookService.enqueueApproved(Transaction txn): build PaymentApprovedEvent DTO from txn fields; Sign the serialised JSON body with HMAC-SHA256 using partner.webhook_signing_secret; set header X-GME-Webhook-Signature: sha256=<hex>; Persist a WebhookOutbox row (event_id, partner_id, event_type, payload, status=PENDING, attempts=0, next_attempt_at=NOW()); A WebhookDispatchWorker reads PENDING rows and POSTs to partner.webhook_url; on HTTP 2xx set status=DELIVERED; on failure increment attempts and set next_attempt_at per retry schedule; After 10 failed attempts, set status=DEAD_LETTER and emit alert to ops
**Deliverable:** WebhookService.enqueueApproved() and WebhookOutbox persistence with WebhookServiceTest
**Acceptance / logic checks:**
- PaymentApprovedEvent for OVERSEAS partner includes prefund_deducted_usd='35.77'; for LOCAL partner the field is absent
- X-GME-Webhook-Signature header value matches sha256=HMAC-SHA256(rawBody, signingSecret) using a known test secret
- WebhookOutbox row is inserted synchronously within the same @Transactional boundary as transaction.status=APPROVED
- On partner HTTP 500 response, attempts increments and next_attempt_at set to +30s (attempt 2)
- After 10 failed attempts the row status=DEAD_LETTER and ops alert event is emitted
**Depends on:** 5.2-T08, 5.2-T09

### 5.2-T14 — Implement low-balance alert after each successful prefunding deduction  _(35 min)_
**Context:** After each successful prefunding deduction (T04), if the resulting balance_after < low_balance_threshold_usd (configured on prefunding_account.low_balance_threshold, default 10000.00 USD), emit a LowBalanceAlert event. The alert must trigger an email to the partner's configured alert contacts (low_balance_alert_config.alert_email where is_active=true for that partner_id). Alert content: partner_id, current balance, threshold, payment_id that triggered it. Avoid alert storms: do not re-alert if balance was already below threshold before this deduction (only alert on crossing the threshold downward).
**Steps:** In PrefundingService.deductForMpm(), after successful deduction: fetch low_balance_alert_config WHERE partner_id=? AND is_active=true; Compare: if balance_before >= threshold AND balance_after < threshold (threshold crossing), emit LowBalanceEvent(partnerId, balanceAfter, threshold, txnRef); AlertDispatchService.onLowBalance(event): send email via EmailService to alert_email with subject 'GMEPay+ Low Prefunding Balance Alert'; Log the alert emission with partner_id, balance_after, threshold to audit log; Unit test: assert alert fires when balance crosses threshold but NOT when balance was already below threshold before deduction
**Deliverable:** LowBalanceAlert emission in PrefundingService and AlertDispatchService.onLowBalance(), with LowBalanceAlertTest
**Acceptance / logic checks:**
- balance_before=11000.00, deduction=2000.00, threshold=10000.00 -> balance_after=9000.00 -> alert fires
- balance_before=9500.00 (already below threshold), deduction=100.00 -> balance_after=9400.00 -> alert does NOT fire (no duplicate storm)
- balance_before=12000.00, deduction=500.00, threshold=10000.00 -> balance_after=11500.00 -> no alert
- Alert email contains partner_id, balance_after=9000.00, threshold=10000.00, and the triggering txnRef
- AlertDispatchService logs the alert event with all required fields in the audit log
**Depends on:** 5.2-T04

### 5.2-T15 — Implement transaction event trail steps 1-5 for MPM flow (RATE_QUOTE_ISSUED through TRANSACTION_COMMITTED)  _(40 min)_
**Context:** Every transaction must carry an 8-step event trail (SAD-02 Assumption A3, A-10). Steps for MPM: (1) RATE_QUOTE_ISSUED at POST /v1/rates, (2) PREFUND_DEDUCTED at deduction (OVERSEAS only; absent for LOCAL), (3) SCHEME_SUBMITTED at submitMpm() call, (4) SCHEME_APPROVED or SCHEME_DECLINED on scheme response, (5) TRANSACTION_COMMITTED on final status write. Steps 6-8 (SETTLEMENT_BATCHED, WEBHOOK_QUEUED, WEBHOOK_DELIVERED) are owned by Settlement and Webhook work-packages. All 8 step columns exist from day one even if not populated. Store each step as txn_event rows: (txn_id, step_number INT, event_name VARCHAR(40), event_at TIMESTAMPTZ, detail JSONB NULL).
**Steps:** Create migration V5_2_002__create_txn_event.sql: table txn_event(id BIGINT PK, txn_id BIGINT FK transaction, step_number INT, event_name VARCHAR(40), event_at TIMESTAMPTZ, detail JSONB); Add UNIQUE(txn_id, step_number) constraint; In EventTrailService.recordStep(long txnId, int step, String name, Instant at, Map detail): INSERT into txn_event; on duplicate step ignore (idempotent); Wire recordStep calls: step 1 in RateQuoteService after quote persisted; step 2 in PrefundingService after deduction; step 3 in TransactionOrchestrator before submitMpm; steps 4/5 in Orchestrator after scheme response; Unit test: full MPM OVERSEAS flow records steps 1,2,3,4,5 in order; LOCAL flow records steps 1,3,4,5 (no step 2)
**Deliverable:** txn_event migration, EventTrailService, and EventTrailTest
**Acceptance / logic checks:**
- OVERSEAS MPM approval: txn_event rows for step_number 1,2,3,4,5 all present with correct event_name values
- LOCAL MPM approval: txn_event rows for steps 1,3,4,5 present; no row with step_number=2
- SCHEME_DECLINED: txn_event row with step_number=4, event_name='SCHEME_DECLINED' inserted
- Calling recordStep with the same txn_id and step_number twice does not create duplicate rows (UNIQUE constraint)
- detail JSONB for step 2 contains {deducted_usd: '35.77', balance_after: '64.23'}
**Depends on:** 5.2-T01, 5.2-T04, 5.2-T08

### 5.2-T16 — Implement GET /v1/payments/{id} endpoint for MPM payment status retrieval  _(30 min)_
**Context:** GET /v1/payments/{id} returns the full payment record. The payment must belong to the calling partner (enforced via partner_id on the transaction row). If not found or not owned -> HTTP 404 PAYMENT_NOT_FOUND. Response includes all fields from the transaction record. Status values: pending, approved, failed, cancelled, uncertain. Timestamps: created_at, updated_at, approved_at (null if not approved), cancelled_at (null if not cancelled). prefund_deducted_usd included for OVERSEAS partners only. Auth and HMAC signing apply (pre-filter).
**Steps:** Add @GetMapping('/v1/payments/{id}') in PaymentController; Call TransactionService.findByIdAndPartner(paymentId, authenticatedPartnerId); if absent throw PaymentNotFoundException -> HTTP 404; Map Transaction entity to PaymentDetailResponse DTO (all fields, status as lowercase string); Set prefund_deducted_usd only when partner.type=OVERSEAS and value is non-null; Return HTTP 200 with X-Request-ID header
**Deliverable:** PaymentController.getPayment() handler and GetPaymentIT integration test
**Acceptance / logic checks:**
- GET /v1/payments/pay_01HX7MNQ5... for correct partner returns HTTP 200 with all fields including prefund_deducted_usd=35.77
- GET with payment_id belonging to a different partner returns HTTP 404 (not 403) to avoid information leakage
- GET on LOCAL partner payment: response does not contain prefund_deducted_usd field
- status field in response is lowercase ('approved', 'failed', 'uncertain') matching API-05 contract
- approved_at field is null in response when transaction.completed_at is null
**Depends on:** 5.2-T08, 5.2-T09

### 5.2-T17 — Implement POST /v1/payments/{id}/cancel for same-day MPM cancellation  _(50 min)_
**Context:** POST /v1/payments/{id}/cancel cancels a same-day payment. Rules (API-05 §4.6): only APPROVED or pending status; only same calendar day as creation (KST timezone, i.e. UTC+9). If status is not cancellable -> HTTP 400 CANCEL_NOT_PERMITTED. If already in terminal state (failed, cancelled) -> HTTP 409. On success: call schemeAdapter.cancelPayment(schemeTxnRef); transition status to REVERSED (internal) / 'cancelled' (API). For OVERSEAS partners, reverse prefunding deduction via PrefundingService.reverseForTransaction(). Webhook payment.cancelled dispatched. Response: {payment_id, status:'cancelled', cancelled_at, prefund_returned_usd (OVERSEAS only)}.
**Steps:** Add @PostMapping('/v1/payments/{id}/cancel') accepting CancelPaymentRequest(reason, reason_detail); Validate status: if FAILED or CANCELLED/REVERSED throw PaymentAlreadyTerminalException -> HTTP 409; Validate same-day: extract transaction.created_at.atZone(ZoneId.of('Asia/Seoul')).toLocalDate() and compare to LocalDate.now(ZoneId.of('Asia/Seoul'))'if different throw CancelNotPermittedException -> HTTP 400; Call schemeAdapter.cancelPayment(txn.schemeRef); on error log but continue (cancel may still be registered at ZeroPay); For OVERSEAS: call reverseForTransaction; transition status=REVERSED; enqueue payment.cancelled webhook; return 200
**Deliverable:** PaymentController.cancelPayment() handler and CancelPaymentIT integration test
**Acceptance / logic checks:**
- Cancel of an APPROVED same-day OVERSEAS payment returns HTTP 200 with prefund_returned_usd=35.77 and status=cancelled
- Cancel attempted on a different calendar day (KST) returns HTTP 400 CANCEL_NOT_PERMITTED
- Cancel of a FAILED payment returns HTTP 409
- Cancel of LOCAL partner payment: HTTP 200, no prefund_returned_usd field in response
- payment.cancelled webhook is enqueued with correct event_type and prefund_returned_usd
**Depends on:** 5.2-T12, 5.2-T13

### 5.2-T18 — Implement HMAC-SHA256 request authentication pre-filter for all /v1/ endpoints  _(55 min)_
**Context:** Every northbound request must pass HMAC-SHA256 authentication (API-05 §3.2). Required headers: X-API-Key, X-Timestamp (ISO-8601 UTC ms precision), X-Signature. Canonical string = {HTTP_METHOD}\n{PATH_WITH_QUERY}\n{X-Timestamp value}\n{SHA256_HEX_OF_BODY}. Signature = HMAC-SHA256(canonical_string, api_secret) hex-encoded. Rejection rules: (1) X-Timestamp older or newer than 300 s from server UTC -> HTTP 401 TIMESTAMP_OUT_OF_RANGE, (2) signature mismatch -> HTTP 401 INVALID_SIGNATURE, (3) unknown API key -> HTTP 401 INVALID_API_KEY. Replay protection: store (partner_id, X-Signature) in Redis for 10 min; reject duplicate signatures within that window.
**Steps:** Implement HmacAuthFilter (OncePerRequestFilter): extract X-API-Key, X-Timestamp, X-Signature from headers; Validate timestamp within 300 s of Instant.now(); reject with 401 TIMESTAMP_OUT_OF_RANGE if outside; Look up partner by api_key; reject with 401 INVALID_API_KEY if not found; retrieve hashed api_secret; Compute canonical string and HMAC-SHA256; compare to X-Signature using MessageDigest.isEqual (constant-time); reject with 401 INVALID_SIGNATURE if mismatch; Check Redis key replay:{partner_id}:{signature}; if exists return 401 INVALID_SIGNATURE; if not, set key TTL=600s; Attach authenticated partner context to SecurityContextHolder for downstream handlers
**Deliverable:** HmacAuthFilter and HmacAuthFilterTest
**Acceptance / logic checks:**
- Valid request with correct signature, timestamp within 5 s of now passes filter
- Request with timestamp 301 s in the past returns HTTP 401 TIMESTAMP_OUT_OF_RANGE
- Request with tampered body (different SHA256) returns HTTP 401 INVALID_SIGNATURE
- Replay of an identical request within 10 minutes returns HTTP 401 INVALID_SIGNATURE
- Unknown X-API-Key returns HTTP 401 INVALID_API_KEY

### 5.2-T19 — Implement IP allowlist enforcement in auth pre-filter  _(35 min)_
**Context:** Partners may configure up to 10 source IP CIDR ranges (API-05 §3.3). When an IP allowlist is configured and the request source IP is not in any range, reject with HTTP 403 IP_NOT_ALLOWLISTED before HMAC signature verification. If no allowlist is configured for the partner, all IPs are permitted. Source IP is taken from X-Forwarded-For header (first non-private IP) or getRemoteAddr() if no proxy header. CIDR matching must support both IPv4 and IPv6.
**Steps:** In HmacAuthFilter, after partner lookup, check if partner has ip_allowlist entries; If allowlist is non-empty, extract effective client IP: parse X-Forwarded-For header first non-private IP; fall back to RemoteAddr; For each CIDR in allowlist, check if clientIp is contained using InetAddressUtils.isInRange(cidr, clientIp); If no CIDR matches, return HTTP 403 IP_NOT_ALLOWLISTED before computing HMAC; Cache parsed CIDR ranges per partner in a short-lived (60 s) Caffeine cache to avoid DB lookup per request
**Deliverable:** IP allowlist check in HmacAuthFilter and IpAllowlistTest
**Acceptance / logic checks:**
- Partner with allowlist [203.0.113.0/24]: request from 203.0.113.45 passes; request from 198.51.100.1 returns HTTP 403 IP_NOT_ALLOWLISTED
- Partner with no allowlist configured: any source IP passes the check
- IPv6 CIDR 2001:db8::/32: request from 2001:db8::1 passes; 2001:db9::1 returns HTTP 403
- IP check occurs before HMAC verification (wrong signature from an allowlisted IP returns 401, not 403 — IP check passes first)
- X-Forwarded-For: 10.0.0.1, 203.0.113.45 uses 203.0.113.45 as the effective client IP (first non-private)
**Depends on:** 5.2-T18

### 5.2-T20 — Unit tests: MPM rate-engine 5-step USD pool calculation with numeric fixture vectors  _(40 min)_
**Context:** RATE-04 5-step RECEIVE mode: (1) payout_usd_cost = target_payout / cost_rate_pay, (2) collection_usd = payout_usd_cost / (1 - m_a - m_b), (3) collection_margin_usd = collection_usd * m_a; payout_margin_usd = collection_usd * m_b, (4) send_amount = collection_usd * cost_rate_coll, (5) collection_amount = send_amount + service_charge. Pool identity: collection_usd - collection_margin_usd - payout_margin_usd == payout_usd_cost (tolerance 0.01 USD). Offer rate (BOK): offer_rate_coll = send_amount / (collection_usd - collection_margin_usd). cross_rate = target_payout / send_amount. Use fixture: target_payout=50000 KRW, cost_rate_pay=1406.00 (usd_krw), cost_rate_coll=1.0 (USD settle A), m_a=0.025, m_b=0.010, service_charge=0.35 USD.
**Steps:** In RateEngineTest, add test fixture: target_payout=50000, cost_rate_pay=1406.00, cost_rate_coll=1.0, m_a=0.025, m_b=0.010, service_charge=0.35; Compute expected values with BigDecimal: payout_usd_cost=35.562589, collection_usd=37.015197, send_amount=37.015197, collection_amount=37.365197; Assert each of the 5 steps against expected values with scale 4 tolerance; Assert pool identity: abs(collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost) < 0.01; Assert offer_rate_coll = send_amount / (collection_usd - collection_margin_usd) = 37.015197 / 36.090317 ~= 1.02561 (6 dp)
**Deliverable:** RateEngineTest class with at least 3 fixture vectors (standard cross-border, identity-leg settle_A=USD, same-currency short-circuit)
**Acceptance / logic checks:**
- Fixture 1 (above): all 5 step outputs match expected BigDecimal values within scale 4
- Fixture 2 (identity leg settle_A=USD, cost_rate_coll=1.0): send_amount numerically equals collection_usd; offer_rate_coll computed correctly
- Fixture 3 (same-currency short-circuit, collection=KRW=payout=KRW): collection_amount = target_payout + service_charge; collection_usd=null
- Pool identity assertion holds for all 3 fixtures
- m_a + m_b < 0.02 on a cross-border rule throws MinCombinedMarginException
**Depends on:** 5.2-T09

### 5.2-T21 — Unit tests: prefunding deduction and reversal atomicity under concurrent MPM requests  _(60 min)_
**Context:** PrefundingService.deductForMpm must guarantee no over-draw under concurrent requests (SELECT FOR UPDATE). This test uses two threads hitting the same partner account. Partner prefunding_account.balance=80.00 USD. Two concurrent MPM payments each requiring collection_usd=60.00 USD. Expected: exactly one succeeds (deducts 60.00, balance=20.00), one throws InsufficientPrefundingException. After the failing payment's scheme call also fails, reverseForTransaction must restore balance to 80.00 (net zero if both fail) or stay at 20.00 if one succeeds. Tests require a real PostgreSQL test container (Testcontainers).
**Steps:** Set up Testcontainers PostgreSQL with the full migration applied; Seed prefunding_account with balance=80.00 USD for test partner; Submit two concurrent deductForMpm(partnerId, 'txn1', 60.00) and deductForMpm(partnerId, 'txn2', 60.00) via ExecutorService with 2 threads; Wait for both futures; assert exactly one completed and one threw InsufficientPrefundingException; Verify final prefunding_account.balance=20.00 and exactly one DEBIT_PAYMENT ledger entry exists; Call reverseForTransaction on the successful txn; assert balance returns to 80.00 and CREDIT_REVERSAL ledger entry inserted
**Deliverable:** PrefundingConcurrencyTest using Testcontainers PostgreSQL
**Acceptance / logic checks:**
- Concurrent execution: exactly one of two 60.00 USD deductions succeeds; balance=20.00 not 0.00 or negative
- No over-draw scenario: balance never goes below 0.00 even under high concurrency (10 threads, each 15.00 USD against balance=100.00 -> exactly 6 succeed, 4 fail)
- After successful reversal, balance equals pre-deduction balance exactly
- prefunding_ledger_entry count = successful_debits + successful_reversals (no phantom rows)
- SELECT balance FROM prefunding_account within the same transaction as deduction sees the locked value
**Depends on:** 5.2-T04, 5.2-T12

### 5.2-T22 — Unit tests: POST /v1/payments full MPM happy-path and key error paths  _(55 min)_
**Context:** Integration-level tests for the POST /v1/payments handler covering the full MPM flow. Use MockMvc or RestAssured with mocked downstream dependencies (ZeroPay adapter, prefunding service, rate quote cache). Fixtures based on spec examples: partner=SendMN (OVERSEAS, USD), target_payout=50000 KRW, quote_id=qte_01HX7MNP9AB2CDEF3GH456IJ, merchant_qr=ZPQR00012345678901234567890, collection_amount=35.77, collection_currency=USD. Expected: HTTP 201, payment_id present, status=approved, prefund_deducted_usd=35.77.
**Steps:** Test T22-1 happy path OVERSEAS: mock valid quote in Redis, active merchant, OVERSEAS rule, prefunding balance=1000 USD; POST -> assert HTTP 201, prefund_deducted_usd=35.77, scheme mock called once; Test T22-2 LOCAL partner (GME Remit): mock valid quote, no prefunding account; POST -> HTTP 201, no prefund_deducted_usd field in response; Test T22-3 expired quote: mock Redis cache miss for quote_id; POST -> HTTP 422 RATE_QUOTE_EXPIRED, scheme mock never called; Test T22-4 insufficient prefunding: balance=10.00 USD; POST -> HTTP 402 INSUFFICIENT_PREFUNDING, scheme mock never called; Test T22-5 inactive merchant: merchant.status=inactive; POST -> HTTP 404 MERCHANT_NOT_FOUND
**Deliverable:** MpmPaymentControllerTest class with 5+ test cases covering happy path and error paths
**Acceptance / logic checks:**
- T22-1: HTTP 201, response body contains payment_id, scheme_txn_id, prefund_deducted_usd=35.77, approved_at timestamp
- T22-2: HTTP 201, response body does NOT contain prefund_deducted_usd key
- T22-3: HTTP 422, error.code=RATE_QUOTE_EXPIRED, ZeroPay mock verifyNoInteractions
- T22-4: HTTP 402, error.code=INSUFFICIENT_PREFUNDING, ZeroPay mock verifyNoInteractions
- T22-5: HTTP 404, error.code=MERCHANT_NOT_FOUND
**Depends on:** 5.2-T08, 5.2-T16, 5.2-T17

### 5.2-T23 — Unit tests: idempotency replay and duplicate partner_txn_ref scenarios  _(45 min)_
**Context:** Two idempotency scenarios must be unit-tested. (A) Idempotency key replay: same Idempotency-Key + same body submitted twice -> second call returns stored 201 response, no double-processing (scheme mock called exactly once, prefunding deducted exactly once). (B) Idempotency key reuse: same Idempotency-Key + different body -> HTTP 422 IDEMPOTENCY_KEY_REUSE. (C) Duplicate partner_txn_ref: different Idempotency-Keys but same partner_txn_ref -> HTTP 409 DUPLICATE_PARTNER_TXN_REF. (D) In-flight: concurrent requests with same idempotency key -> one proceeds, one returns HTTP 409 with X-Idempotency-Status: in_flight.
**Steps:** Test T23-A: submit POST /v1/payments twice with same Idempotency-Key and same body; mock first call succeeds; assert second call returns same 201 body, scheme called once; Test T23-B: submit with same Idempotency-Key but different collection_amount; assert HTTP 422 IDEMPOTENCY_KEY_REUSE; Test T23-C: two different Idempotency-Keys, same partner_txn_ref; assert second returns HTTP 409 DUPLICATE_PARTNER_TXN_REF; Test T23-D: simulate in-flight by setting Redis key to IN_FLIGHT status before second call; assert HTTP 409 X-Idempotency-Status: in_flight; Verify in T23-A that prefunding_ledger_entry has exactly one DEBIT_PAYMENT row for that txnRef
**Deliverable:** IdempotencyIntegrationTest with 4 test cases
**Acceptance / logic checks:**
- T23-A: scheme mock called exactly once; HTTP 201 both times with identical response body
- T23-B: HTTP 422, error.code=IDEMPOTENCY_KEY_REUSE
- T23-C: HTTP 409, error.code=DUPLICATE_PARTNER_TXN_REF
- T23-D: HTTP 409, X-Idempotency-Status header value=in_flight
- T23-A: prefunding_ledger_entry contains exactly one row for the txnRef, not two
**Depends on:** 5.2-T07, 5.2-T22

### 5.2-T24 — Unit tests: UNCERTAIN state and batch-reconciliation resolution for MPM timeout  _(45 min)_
**Context:** When ZeroPay times out on submitMpm, the transaction moves to UNCERTAIN and prefunding is NOT reversed. This test validates the full lifecycle: (1) scheme timeout -> UNCERTAIN + prefunding held, (2) batch reconciliation confirms APPROVED -> status becomes APPROVED, no reversal, (3) alternate: batch confirms FAILED -> status FAILED, prefunding reversed. Also tests the 24 h alert.
**Steps:** Test T24-1: mock SchemeTimeoutException from adapter; assert transaction.status=UNCERTAIN, prefunding_account.balance unchanged after call; Test T24-2: call resolveUncertain(txnRef, APPROVED); assert status=APPROVED, prefunding_account.balance still deducted (not reversed); Test T24-3: call resolveUncertain(txnRef, FAILED); assert status=FAILED, CREDIT_REVERSAL ledger entry exists, balance restored; Test T24-4: attempt resolveUncertain on an already-APPROVED transaction; assert IllegalStateException thrown; Test T24-5: scheduled alert check with UNCERTAIN transaction created 25 h ago; assert UncertainTransactionAlert emitted
**Deliverable:** UncertainStateTest class with 5 test cases
**Acceptance / logic checks:**
- T24-1: transaction.status=UNCERTAIN immediately after SchemeTimeoutException; prefunding_account.balance = value before payment call
- T24-2: after resolveUncertain APPROVED, transaction.status=APPROVED; no CREDIT_REVERSAL in ledger
- T24-3: after resolveUncertain FAILED, CREDIT_REVERSAL row with amount equal to original DEBIT_PAYMENT amount
- T24-4: resolveUncertain on APPROVED throws IllegalStateException (idempotency protection)
- T24-5: alert fired for transaction with created_at < NOW()-24h; no alert for created_at=NOW()-23h
**Depends on:** 5.2-T11, 5.2-T21

### 5.2-T25 — Write OpenAPI 3.1 spec fragment for POST /v1/payments (MPM) endpoint  _(40 min)_
**Context:** The API-05 spec is normative. An OpenAPI 3.1 YAML fragment for POST /v1/payments must document: request body schema (all fields with types, required flags, example values from spec), response schemas (201, 202, 400, 402, 404, 409, 422, 429), all error codes (RATE_QUOTE_EXPIRED, INSUFFICIENT_PREFUNDING, MERCHANT_NOT_FOUND, DUPLICATE_PARTNER_TXN_REF, PAYMENT_MODE_NOT_SUPPORTED, DIRECTION_NOT_ENABLED, MISSING_IDEMPOTENCY_KEY), and required headers (X-API-Key, X-Timestamp, X-Signature, Idempotency-Key). The fragment must be machine-parseable and usable for contract tests. Add to the existing openapi.yaml under paths./v1/payments.post.
**Steps:** Open (or create) src/main/resources/openapi/openapi.yaml; Under paths./v1/payments, add post: with summary, operationId=executeMpmPayment, security referencing hmacAuth; Define requestBody referencing $ref: '#/components/schemas/MpmPaymentRequest' with all field descriptions and example; Define responses for 201 (MpmPaymentResponse), 202, 400, 402, 404, 409, 422 (multiple error codes), 429; Validate the YAML with swagger-cli lint; ensure no schema errors
**Deliverable:** OpenAPI fragment in openapi.yaml for POST /v1/payments and passing swagger-cli lint
**Acceptance / logic checks:**
- swagger-cli lint openapi.yaml exits with 0 errors
- MpmPaymentRequest schema marks quote_id, merchant_qr, direction, scheme_id, customer_ref, partner_txn_ref, collection_amount, collection_currency as required
- MpmPaymentResponse schema includes prefund_deducted_usd as optional (not required)
- Response 402 is documented with error code INSUFFICIENT_PREFUNDING
- Example request body in spec matches the field values from API-05: quote_id=qte_01HX7MNP9AB2CDEF3GH456IJ, collection_amount=35.77, collection_currency=USD
**Depends on:** 5.2-T02, 5.2-T08

### 5.2-T26 — Add rate limiting enforcement (50 req/s) for POST /v1/payments per partner  _(40 min)_
**Context:** API-05 §3.5: POST /v1/payments is rate-limited to 50 requests/second per partner. When exceeded, return HTTP 429 RATE_LIMITED with header Retry-After (seconds until window resets), X-RateLimit-Limit: 50, X-RateLimit-Remaining: 0, X-RateLimit-Reset: <unix epoch>. Implementation: use Redis token bucket or sliding window counter with key ratelimit:payments:{partner_id}, window=1s, limit=50. The limit is configurable per partner in partner config (default 50; GME Ops can override). Limits for other endpoints (POST /v1/rates = 20/s, all endpoints = 100/s) are in scope for a separate ticket.
**Steps:** Implement RateLimiterService.checkPaymentsLimit(long partnerId): INCR Redis key ratelimit:payments:{partnerId} with EXPIRE 1s; If count > partnerConfig.paymentRateLimit (default 50), throw RateLimitExceededException; In RateLimitExceededException handler: return HTTP 429 with X-RateLimit-Limit, X-RateLimit-Remaining=0, X-RateLimit-Reset, Retry-After headers; Wire RateLimiterService check as the first step in PaymentController.executeMpmPayment before idempotency check; Unit test: 51 rapid requests for same partner -> first 50 succeed (or reach next step), 51st returns 429
**Deliverable:** RateLimiterService.checkPaymentsLimit() and RateLimitTest
**Acceptance / logic checks:**
- 50 requests within 1 s for same partner all pass the rate limiter
- 51st request within same 1 s window returns HTTP 429 with Retry-After header present
- Requests from partner A do not consume partner B's rate limit quota (per-partner isolation)
- Partner with custom limit=100 configured by ops: 100 requests/s pass, 101st returns 429
- X-RateLimit-Reset value is a future Unix timestamp (within 2 s of now)
**Depends on:** 5.2-T08

### 5.2-T27 — Implement GET /v1/balance endpoint for OVERSEAS partner prefunding balance inquiry  _(35 min)_
**Context:** GET /v1/balance returns the calling OVERSEAS partner's current prefunding balance (API-05 §4.8). Response: partner_id, balance_usd (DECIMAL as string), low_balance_threshold_usd, is_below_threshold (bool), as_of (timestamp). Optional query param ?include_history=true returns last 10 deduction events from prefunding_ledger_entry. LOCAL partners calling this endpoint -> HTTP 403 FORBIDDEN with error code FORBIDDEN. Balance read does NOT require SELECT FOR UPDATE (read-only). balance_usd read must be consistent (no dirty read); use READ COMMITTED isolation at minimum.
**Steps:** Add @GetMapping('/v1/balance') in BalanceController; Resolve authenticated partner; if partner.type='LOCAL' return HTTP 403 error(FORBIDDEN, 'Prefunding balance is not applicable for LOCAL partners'); Fetch prefunding_account WHERE partner_id=? and low_balance_threshold from same row; Set is_below_threshold = balance < low_balance_threshold; If include_history=true, fetch last 10 rows from prefunding_ledger_entry ORDER BY created_at DESC for account_id; Return PrefundingBalanceResponse with as_of=Instant.now()
**Deliverable:** BalanceController.getBalance() and BalanceControllerTest
**Acceptance / logic checks:**
- OVERSEAS partner with balance=48234.56, threshold=10000.00 -> HTTP 200, is_below_threshold=false
- OVERSEAS partner with balance=9500.00, threshold=10000.00 -> HTTP 200, is_below_threshold=true
- LOCAL partner (GME Remit) -> HTTP 403 error.code=FORBIDDEN
- ?include_history=true returns at most 10 entries with fields: payment_id, amount_usd, balance_after_usd, event_at
- balance_usd in response serialises as decimal string '48234.5600' (or trimmed '48234.56') NOT as a JSON number
**Depends on:** 5.2-T04, 5.2-T18

### 5.2-T28 — Write end-to-end MPM flow integration test covering certification checklist C-01 through C-03 and C-13  _(60 min)_
**Context:** API-05 §10.4 certification tests. C-01: GET /v1/rates returns valid quote_id and valid_until. C-02: POST /v1/payments with valid quote returns HTTP 201 status=approved. C-03: POST /v1/payments with expired quote returns HTTP 422 RATE_QUOTE_EXPIRED. C-13: POST /v1/payments with insufficient prefunding returns HTTP 402 INSUFFICIENT_PREFUNDING. These tests use Testcontainers (PostgreSQL + Redis) and a mocked ZeroPay adapter. They must run as part of the CI build. Use sandbox magic QR values: ZPQR_TEST_APPROVED for C-02.
**Steps:** Set up Testcontainers PostgreSQL + Redis; run all migrations; seed test partner (OVERSEAS, SendMN), prefunding balance=1000 USD, merchant with QR=ZPQR_TEST_APPROVED; C-01: POST /v1/rates with target_payout=50000&payout_currency=KRW&scheme_id=zeropay; assert HTTP 200, quote_id non-null, valid_until is a future timestamp; C-02: use quote_id from C-01; POST /v1/payments; assert HTTP 201, status=approved, prefund_deducted_usd present; C-03: expire the Redis quote key manually (DEL) then POST /v1/payments with that quote_id; assert HTTP 422 RATE_QUOTE_EXPIRED; C-13: seed balance=5.00 USD; POST /v1/payments requiring 35.77 USD; assert HTTP 402 INSUFFICIENT_PREFUNDING
**Deliverable:** MpmCertificationIT integration test class covering C-01, C-02, C-03, C-13
**Acceptance / logic checks:**
- C-01: HTTP 200, valid_until parses as ISO-8601 UTC and is > 290 s in the future (default TTL 300 s)
- C-02: HTTP 201, response body matches MpmPaymentResponse schema; scheme_txn_id non-null
- C-03: HTTP 422, error.code=RATE_QUOTE_EXPIRED; no prefunding_ledger_entry created for that attempt
- C-13: HTTP 402, error.code=INSUFFICIENT_PREFUNDING; prefunding_account.balance remains 5.00 after rejection
- All 4 test cases pass in CI (Maven/Gradle test phase) with Testcontainers auto-provisioning
**Depends on:** 5.2-T08, 5.2-T16, 5.2-T22, 5.2-T27


## WBS 5.5 — CommitTransaction & payment.approved
### 5.5-T01 — Add rate-lock columns to transaction table migration  _(40 min)_
**Context:** The transaction table (DAT-03 §5.2) must store all rate-engine values permanently at commit. Columns payout_usd_cost, collection_usd, collection_margin_usd, payout_margin_usd, send_amount, service_charge, collection_amount, offer_rate_coll, cross_rate, cost_rate_coll, cost_rate_pay, rate_locked_at, committed_at are set once at CommitTransaction and are NEVER subsequently updated. Types: DECIMAL(20,8) for USD-pool and rate fields; DECIMAL(20,4) for Settle-A amounts; TIMESTAMPTZ for timestamps. NULL allowed on USD-pool fields for same-currency short-circuit rows (is_same_ccy_shortcircuit=TRUE). Existing columns: id BIGINT PK, txn_ref VARCHAR(64) UNIQUE, status VARCHAR(20), partner_id, scheme_id, rule_id, rate_quote_id, merchant_id, payment_mode, direction, target_payout DECIMAL(20,4), payout_ccy CHAR(3), collection_ccy CHAR(3), settle_a_ccy CHAR(3), settle_b_ccy CHAR(3), is_same_ccy_shortcircuit BOOLEAN, prefunding_deducted_usd DECIMAL(20,4).
**Steps:** Create Flyway/Liquibase migration file V5_5__add_rate_lock_columns.sql; Add columns: payout_usd_cost DECIMAL(20,8) NULL, collection_usd DECIMAL(20,8) NULL, collection_margin_usd DECIMAL(20,8) NULL, payout_margin_usd DECIMAL(20,8) NULL, send_amount DECIMAL(20,4) NOT NULL DEFAULT 0, service_charge DECIMAL(20,4) NOT NULL DEFAULT 0, collection_amount DECIMAL(20,4) NOT NULL DEFAULT 0, offer_rate_coll DECIMAL(20,8) NULL, cross_rate DECIMAL(20,8) NULL, cost_rate_coll DECIMAL(20,8) NULL, cost_rate_pay DECIMAL(20,8) NULL, rate_locked_at TIMESTAMPTZ NULL, committed_at TIMESTAMPTZ NULL; Add DB-level immutability: create trigger prevent_rate_lock_update that raises exception if any of those 13 columns are updated when rate_locked_at IS NOT NULL; Add composite indexes: (partner_id, committed_at), (scheme_id, completed_at), (status, committed_at); Apply migration against dev PostgreSQL on port 5433; verify schema with \d transaction
**Deliverable:** Migration file V5_5__add_rate_lock_columns.sql and DB trigger prevent_rate_lock_update applied to dev PostgreSQL
**Acceptance / logic checks:**
- All 13 rate-lock columns present with correct types and nullability as specified
- INSERT with rate_locked_at NULL succeeds; subsequent UPDATE to non-rate-lock columns also succeeds
- Attempt to UPDATE collection_usd after rate_locked_at IS NOT NULL raises exception (trigger fires)
- is_same_ccy_shortcircuit=TRUE rows allow NULL in payout_usd_cost and collection_usd without constraint error
- Composite indexes (partner_id, committed_at) and (status, committed_at) visible in pg_indexes

### 5.5-T02 — Define CommitRequest and CommitResult domain types  _(35 min)_
**Context:** CommitTransaction is the central entry point for finalizing a payment. It accepts a quote reference and partner-supplied fields, performs validation, rate-lock, prefunding deduction (OVERSEAS), scheme call, and status update. Define the input/output value objects that flow through the orchestration boundary. Fields in CommitRequest: quote_id (String), merchant_qr (String), direction (enum: INBOUND/OUTBOUND/DOMESTIC/HUB), scheme_id (String), customer_ref (String), partner_txn_ref (String), collection_amount (BigDecimal), collection_currency (String), country_code (String nullable), idempotency_key (String). Fields in CommitResult: payment_id (String), status (PaymentStatus), scheme_txn_id (String), merchant_name (String), merchant_id (String), target_payout (BigDecimal), payout_currency (String), offer_rate (BigDecimal), collection_amount (BigDecimal), collection_currency (String), service_charge (BigDecimal), service_charge_currency (String), prefund_deducted_usd (BigDecimal nullable), partner_txn_ref (String), created_at (Instant), approved_at (Instant nullable). PaymentStatus enum values: APPROVED, PENDING, FAILED, UNCERTAIN.
**Steps:** Create CommitRequest record/POJO with all listed fields and validation annotations (NotNull, NotBlank where required); Create CommitResult record/POJO with all listed response fields; Create PaymentStatus enum with values APPROVED, PENDING, FAILED, UNCERTAIN, CANCELLED, REVERSED, REFUNDED; Create CommitTransactionException hierarchy: QuoteExpiredException, QuoteNotFoundException, InsufficientPrefundingException, MerchantNotFoundException, SchemeUnavailableException, PartnerBQuoteDeviationException, PartnerBQuoteUnavailableException, PoolIdentityFailureException; Add unit test verifying CommitRequest rejects null quote_id and null partner_txn_ref
**Deliverable:** CommitRequest, CommitResult, PaymentStatus enum, and CommitTransactionException hierarchy in domain package
**Acceptance / logic checks:**
- CommitRequest with null quote_id throws ConstraintViolationException or equivalent on validation
- CommitResult with all fields populated serializes to JSON with correct field names matching API-05 spec (payment_id, offer_rate, prefund_deducted_usd, etc.)
- PaymentStatus.APPROVED.name() == 'APPROVED'; all 7 values present
- QuoteExpiredException is a subtype of CommitTransactionException
- Unit test passes with a valid CommitRequest instance built with all required fields
**Depends on:** 5.5-T01

### 5.5-T03 — Define RateQuote domain model and Redis cache interface  _(45 min)_
**Context:** At quote time (GET /v1/rates), the rate engine computes all 5-step USD pool values and stores them in Redis with key rate_quote:{quote_id}, TTL = partner.rate_quote_ttl_seconds (default 300s; 60s for aggregator-bound). At CommitTransaction, the orchestrator loads this quote from Redis to verify TTL and extract all pool values for rate-lock. The RateQuote object must carry: quote_id (String), partner_id (Long), scheme_id (String), direction (String), target_payout (BigDecimal), payout_ccy (String), settle_a_ccy (String), settle_b_ccy (String), is_same_ccy_shortcircuit (Boolean), payout_usd_cost (BigDecimal nullable), collection_usd (BigDecimal nullable), collection_margin_usd (BigDecimal nullable), payout_margin_usd (BigDecimal nullable), send_amount (BigDecimal), service_charge (BigDecimal), collection_amount (BigDecimal), offer_rate_coll (BigDecimal), cross_rate (BigDecimal), cost_rate_coll (BigDecimal), cost_rate_pay (BigDecimal), cost_rate_pay_source (enum: IDENTITY/LIVE/MANUAL/PARTNER), quote_issued_at (Instant), valid_until (Instant), is_used (Boolean).
**Steps:** Create RateQuote record with all listed fields; Create RateQuoteRepository interface with methods: save(RateQuote, long ttlSeconds), findById(String quoteId): Optional<RateQuote>, markUsed(String quoteId); Create RedisRateQuoteRepository implementing RateQuoteRepository using Redis SETEX with key rate_quote:{quoteId} and JSON serialization; Write unit test: save a RateQuote, retrieve it, verify all BigDecimal fields match to 8 decimal places; Write unit test: markUsed flips is_used=true; subsequent findById returns updated object
**Deliverable:** RateQuote domain class, RateQuoteRepository interface, RedisRateQuoteRepository implementation, and unit tests
**Acceptance / logic checks:**
- RateQuote with is_same_ccy_shortcircuit=true can be stored and retrieved with payout_usd_cost=null
- TTL is set correctly: a quote saved with ttlSeconds=60 is absent from Redis after 61 seconds (verified with integration test or mock)
- findById returns Optional.empty() for a non-existent key
- markUsed idempotent: calling twice does not throw
- BigDecimal fields round-trip through JSON without precision loss (10-digit values preserved)
**Depends on:** 5.5-T02

### 5.5-T04 — Implement CommitTransaction quote validation step  _(35 min)_
**Context:** The first step in CommitTransaction is to load the rate quote from Redis and validate it. Rules: (1) quote_id must exist in Redis (rate_quote:{quote_id}) - error RATE_QUOTE_INVALID if absent; (2) quote.partner_id must match the authenticated partner - error FORBIDDEN; (3) now_utc() must be <= quote.valid_until - error RATE_QUOTE_EXPIRED; (4) quote.is_used must be false - error RATE_QUOTE_INVALID (already committed); (5) quote.direction and quote.scheme_id must match the CommitRequest fields - error VALIDATION_ERROR. The method signature is: RateQuote loadAndValidateQuote(String quoteId, Long partnerId, String direction, String schemeId, Instant now). This is a pure validation step - it does NOT mark the quote as used yet (that happens atomically at rate-lock).
**Steps:** Create QuoteValidationService with method loadAndValidateQuote; Implement Redis fetch via RateQuoteRepository.findById; throw QuoteNotFoundException if absent; Check partner_id match; throw ForbiddenException if mismatch; Check valid_until >= now; throw QuoteExpiredException with quote.valid_until in message; Check is_used == false; throw QuoteAlreadyUsedException (subtype of QuoteNotFoundException) if true; Check direction and scheme_id match commit request; throw ValidationException with field detail if mismatch
**Deliverable:** QuoteValidationService.loadAndValidateQuote with all 5 validation rules
**Acceptance / logic checks:**
- Non-existent quote_id throws QuoteNotFoundException
- Quote with valid_until = now-1s throws QuoteExpiredException; valid_until = now+1s passes
- Quote owned by partner_id=1 accessed by partner_id=2 throws ForbiddenException
- Quote with is_used=true throws QuoteAlreadyUsedException
- Direction mismatch (commit says 'domestic', quote says 'inbound') throws ValidationException with field='direction'
**Depends on:** 5.5-T03

### 5.5-T05 — Implement Partner B quote deviation check at commit time  _(40 min)_
**Context:** When a rule's cost_rate_pay_source == PARTNER, GMEPay+ calls Partner B's quote API again at commit time and checks deviation. Formula: deviation = |commit_quote - rates_quote| / rates_quote. If deviation > partner.partner_b_deviation_tolerance (default 0.01 = 1%, per-partner configurable), reject with PARTNER_B_QUOTE_DEVIATION. If the Partner B API is unreachable at commit time, reject with PARTNER_B_QUOTE_UNAVAILABLE. If cost_rate_pay_source != PARTNER, skip this check entirely. The deviation tolerance is stored on the partner record as partner_b_deviation_tolerance DECIMAL(6,4). Example: rates_quote cost_rate_pay=1380.00, commit_quote=1393.80. deviation=|1393.80-1380.00|/1380.00=0.01=1.0%. At tolerance=0.01 this is exactly at the boundary - implement as: deviation > tolerance (strict greater), so 1.0% at tolerance 1.0% PASSES. At 1.01% it FAILS.
**Steps:** Create PartnerBQuoteDeviationChecker with method checkDeviation(RateQuote storedQuote, BigDecimal partnerBCommitQuote, BigDecimal tolerance); Implement deviation = abs(commitQuote - storedQuote.cost_rate_pay) / storedQuote.cost_rate_pay using BigDecimal arithmetic; Throw PartnerBQuoteDeviationException if deviation.compareTo(tolerance) > 0 (strict greater); Create PartnerBQuoteClient interface with method fetchCurrentQuote(String schemeId, BigDecimal payoutAmount): BigDecimal - throw PartnerBQuoteUnavailableException if unreachable; Wire into commit flow: if cost_rate_pay_source == PARTNER, call client then checker; if source != PARTNER, skip; Write unit tests with exact numeric examples from context
**Deliverable:** PartnerBQuoteDeviationChecker, PartnerBQuoteClient interface, and unit tests
**Acceptance / logic checks:**
- rates_quote=1380.00, commit=1393.80, tolerance=0.01: deviation=0.01000 which is NOT > 0.01, so no exception (boundary pass)
- rates_quote=1380.00, commit=1394.58, tolerance=0.01: deviation=0.010565 > 0.01, throws PartnerBQuoteDeviationException
- PartnerBQuoteClient throwing IOException causes PartnerBQuoteUnavailableException to propagate
- cost_rate_pay_source=LIVE: checker is never called (skipped entirely)
- cost_rate_pay_source=IDENTITY: checker is never called
**Depends on:** 5.5-T04

### 5.5-T06 — Implement atomic prefunding deduction in CommitTransaction (OVERSEAS partners)  _(50 min)_
**Context:** For OVERSEAS partners (partner.type == OVERSEAS), CommitTransaction must atomically deduct collection_usd from the partner's prefunding_account.balance using SELECT FOR UPDATE to prevent concurrent over-draws. The deduction amount is quote.collection_usd (the USD pool gross - NOT collection_amount which is in Settle-A ccy). Steps: (1) BEGIN transaction; (2) SELECT balance FROM prefunding_account WHERE partner_id=? FOR UPDATE; (3) if balance < collection_usd ROLLBACK and throw InsufficientPrefundingException; (4) UPDATE prefunding_account SET balance = balance - collection_usd WHERE partner_id=?; (5) INSERT INTO prefunding_ledger_entry (account_id, txn_ref, entry_type='DEBIT_PAYMENT', amount=collection_usd, balance_after=balance-collection_usd); (6) COMMIT. For LOCAL partners (partner.type == LOCAL), skip deduction entirely - no prefunding exists. The scheme must NEVER be called if deduction fails.
**Steps:** Create PrefundingDeductionService with method deductForPayment(Long partnerId, String txnRef, BigDecimal collectionUsd): PrefundingDeductionResult; Implement SELECT FOR UPDATE on prefunding_account within a @Transactional method; Check balance >= collectionUsd; throw InsufficientPrefundingException with partner_id and required amount if insufficient; Perform UPDATE balance and INSERT ledger entry atomically; Return PrefundingDeductionResult{deductedUsd, balanceAfter}; Add bypass: if partner.type == LOCAL return PrefundingDeductionResult{deductedUsd=ZERO, balanceAfter=null}
**Deliverable:** PrefundingDeductionService.deductForPayment with atomic SELECT FOR UPDATE, ledger entry insert, and LOCAL partner bypass
**Acceptance / logic checks:**
- Concurrent calls with total demand exceeding balance: exactly one succeeds, others throw InsufficientPrefundingException (test with 2 threads, balance=100.00, each requesting 60.00)
- After successful deduction of 36.97 USD from balance 500.00, prefunding_account.balance = 463.03 and prefunding_ledger_entry.balance_after = 463.03
- LOCAL partner call returns deductedUsd=0 and no ledger entry is created
- InsufficientPrefundingException carries the shortfall amount (e.g. required=36.97, available=10.00)
- deductForPayment is @Transactional: if the ledger INSERT fails, the balance UPDATE is rolled back
**Depends on:** 5.5-T03

### 5.5-T07 — Implement rate-lock: write all pool values to transaction record  _(40 min)_
**Context:** After prefunding deduction succeeds and scheme call succeeds (state transitions to APPROVED), the orchestrator writes all rate-engine values permanently to the transaction record. The exact columns and values to write are (from quote): payout_usd_cost, collection_usd, collection_margin_usd, payout_margin_usd, send_amount, service_charge, collection_amount, offer_rate_coll, cross_rate, cost_rate_coll, cost_rate_pay. Additionally: rate_locked_at = now_utc(), committed_at = now_utc(), status = 'APPROVED', scheme_ref = scheme approval code, completed_at = scheme response time. Rule: these columns must be written in a single UPDATE statement; no second UPDATE to these columns is ever permitted after rate_locked_at IS NOT NULL (enforced by DB trigger from T01). For same-currency short-circuit: payout_usd_cost=NULL, collection_usd=NULL, collection_margin_usd=0, payout_margin_usd=0, cost_rate_coll=1.0, cost_rate_pay=1.0.
**Steps:** Create RateLockService with method lockRate(Long txnId, RateQuote quote, String schemeRef, Instant schemeApprovedAt); Build UPDATE statement covering all 13 rate-lock columns plus rate_locked_at, committed_at, completed_at, status, scheme_ref in one call; Use BigDecimal for all monetary/rate values; never cast to double; Handle same-ccy short-circuit: set collection_usd=NULL, payout_usd_cost=NULL, collection_margin_usd=0, payout_margin_usd=0 when quote.is_same_ccy_shortcircuit=true; Verify the UPDATE affected exactly 1 row; throw OptimisticLockException if 0 rows (txn not found or already locked); Write integration test: lock rates, then attempt a second lock on same txnId and confirm DB trigger blocks it
**Deliverable:** RateLockService.lockRate that writes all pool values atomically in one UPDATE
**Acceptance / logic checks:**
- After lockRate, SELECT from transaction confirms rate_locked_at IS NOT NULL and all 13 columns match quote values to 8 decimal places
- Second call to lockRate on same txnId raises exception (DB trigger from T01 fires)
- Same-ccy short-circuit row: collection_usd IS NULL, payout_usd_cost IS NULL, collection_margin_usd=0.00000000, offer_rate_coll=1.00000000
- rate_locked_at timestamp matches now_utc() within 1 second of test execution
- status column = 'APPROVED' after lockRate
**Depends on:** 5.5-T01, 5.5-T03

### 5.5-T08 — Implement TransactionOrchestrator.commitTransaction - MPM happy path  _(55 min)_
**Context:** CommitTransaction for Fixed MPM (POST /v1/payments) orchestrates: (1) load+validate quote (T04); (2) Partner B deviation check if applicable (T05); (3) create transaction record in DB with status PENDING_DEBIT; (4) for OVERSEAS: deduct prefunding (T06), transition status to DEBITED; (5) call ZeroPay scheme adapter submitMpm(txnRef, merchantId, payoutAmount); (6) on scheme success: call RateLockService.lockRate (T07), transition status to APPROVED, mark quote as used; (7) dispatch payment.approved webhook async; (8) return CommitResult. Idempotency: if a transaction with matching (partner_id, idempotency_key) already exists and is APPROVED, return the stored CommitResult without re-processing. State transitions: QUOTED->PENDING_DEBIT->DEBITED->SCHEME_SENT->APPROVED. Correlation field txn_ref = idempotency_key. The scheme must never be called if step 4 fails.
**Steps:** In TransactionOrchestrator, implement commitMpmTransaction(CommitRequest req, Long partnerId) method; Load and validate quote; create transaction row (status=QUOTED, all non-rate columns populated); Check idempotency: if existing approved transaction found by (partner_id, idempotency_key), return stored CommitResult; For OVERSEAS: call deductForPayment; transition status QUOTED->PENDING_DEBIT->DEBITED; if deduction fails set FAILED and return error; Call schemeAdapter.submitMpm; transition status to SCHEME_SENT; on success call lockRate and set APPROVED; Call webhookDispatcher.enqueueApproved async (do not block on delivery); Return CommitResult built from transaction record fields
**Deliverable:** TransactionOrchestrator.commitMpmTransaction orchestrating the full MPM happy path
**Acceptance / logic checks:**
- OVERSEAS partner with sufficient balance: status progresses QUOTED->PENDING_DEBIT->DEBITED->SCHEME_SENT->APPROVED in transaction_event trail
- LOCAL partner: DEBITED state reached without prefunding_ledger_entry being created
- Idempotent re-submission (same idempotency_key) returns same payment_id without double scheme call or double deduction
- Scheme call is never made if prefunding deduction throws InsufficientPrefundingException
- CommitResult.prefund_deducted_usd equals quote.collection_usd for OVERSEAS; null for LOCAL
**Depends on:** 5.5-T04, 5.5-T05, 5.5-T06, 5.5-T07

### 5.5-T09 — Implement TransactionOrchestrator.commitTransaction - CPM confirm path  _(50 min)_
**Context:** CPM CommitTransaction (POST /v1/payments/cpm/confirm) differs from MPM: prefunding was already deducted at QR-generate time (AD-07), so step 4 is skipped. The state machine entry point is DEBITED (not QUOTED). Steps: (1) load transaction by txn_ref; (2) validate status == DEBITED (reject if not); (3) load+validate rate quote linked to transaction (validate TTL still valid); (4) Partner B deviation check if applicable; (5) call schemeAdapter.confirmCpm(txnRef, prepareToken); (6) on scheme success: call RateLockService.lockRate, set status APPROVED; (7) dispatch payment.approved webhook async; (8) return CommitResult. The rate-lock values come from the quote that was computed when the CPM inbound notification arrived and stored on the transaction. Transition path: DEBITED->SCHEME_SENT->APPROVED.
**Steps:** Add commitCpmTransaction(String txnRef, Long partnerId) to TransactionOrchestrator; Load transaction by txn_ref; validate status == DEBITED; validate partner ownership; Reload quote from Redis using transaction.rate_quote_id; validate TTL; Perform Partner B deviation check if cost_rate_pay_source == PARTNER; Call schemeAdapter.confirmCpm; on success call lockRate with scheme approval code; Dispatch payment.approved webhook async; transition status to APPROVED; Return CommitResult with all rate-lock values populated
**Deliverable:** TransactionOrchestrator.commitCpmTransaction for CPM confirm path
**Acceptance / logic checks:**
- Transaction in QUOTED state (not DEBITED) returns error - cannot commit CPM without prior pending_debit
- After confirmCpm, rate_locked_at IS NOT NULL and all pool values match the CPM-time quote
- payment.approved webhook enqueued after successful commitCpmTransaction
- CommitResult.prefund_deducted_usd is populated (was deducted at generate time, echoed from transaction record)
- Calling commitCpmTransaction twice with same txnRef: second call returns stored CommitResult (idempotent)
**Depends on:** 5.5-T08

### 5.5-T10 — Implement quote expiry check and RATE_QUOTE_EXPIRED error path  _(30 min)_
**Context:** If a partner calls POST /v1/payments after quote.valid_until has passed (now_utc() > quote.valid_until), CommitTransaction must reject the request before any prefunding deduction or scheme call. Error: HTTP 422, error_code=RATE_QUOTE_EXPIRED, message includes the quote_id and the expired valid_until timestamp. The partner must re-call POST /v1/rates to get a fresh quote. No transaction record should be created for expired-quote commits (fail fast before any side effects). TTL is per-partner: aggregator-bound=60s default, others=300s default, range 60-1800s. Example: quote_issued_at=09:31:00Z, ttl=300s, valid_until=09:36:00Z. Commit at 09:36:01Z fails. Commit at 09:36:00Z passes (boundary: valid_until is inclusive, i.e. now <= valid_until passes).
**Steps:** In QuoteValidationService, ensure expiry check uses now.isAfter(quote.valid_until) (exclusive: at exactly valid_until it passes); Map QuoteExpiredException to HTTP 422 with error_code=RATE_QUOTE_EXPIRED in the exception handler; Include quote.valid_until formatted as ISO-8601 UTC in error message; Verify no transaction row is inserted before expiry check (expiry is step 1 of validation); Write unit test: commit at valid_until-1ms passes; commit at valid_until+1ms throws QuoteExpiredException; Write unit test: OVERSEAS partner - no prefunding_ledger_entry created when QuoteExpiredException thrown
**Deliverable:** Expiry guard in QuoteValidationService and HTTP 422 mapping with correct error payload
**Acceptance / logic checks:**
- commit at valid_until + 1 ms returns HTTP 422 with error.code=RATE_QUOTE_EXPIRED and error.details[0].field=quote_id
- commit at exactly valid_until passes (boundary inclusive)
- No transaction row created in DB when expiry check fails
- No prefunding_ledger_entry created for expired-quote attempt
- Error response includes the expired valid_until timestamp in ISO-8601 UTC format
**Depends on:** 5.5-T04

### 5.5-T11 — Implement merchant validation at commit time  _(35 min)_
**Context:** At CommitTransaction for MPM, the merchant_qr from the CommitRequest must be validated against GMEPay+'s local merchant DB (synced from ZeroPay via daily SFTP - AD-12). Validation rules: (1) QR code must exist in the qr_code table; (2) linked merchant must have status='active'; (3) merchant.scheme_id must match commit request scheme_id. Error: MERCHANT_NOT_FOUND (HTTP 404) if QR not found or merchant inactive; additionally if QR exists but merchant is inactive, HTTP 422 is acceptable per API-05. The validation must happen BEFORE prefunding deduction and scheme call. The merchant_id resolved here is stored on the transaction record. CPM commits use merchant_id already stored from the inbound notification (skip this validation).
**Steps:** Create MerchantValidationService.validateMerchantForCommit(String merchantQr, String schemeId): MerchantInfo; Query qr_code table by qr_value; throw MerchantNotFoundException if absent; Check merchant.status == 'active'; throw MerchantInactiveException (HTTP 422) if not active; Check merchant.scheme_id matches schemeId; throw ValidationException if mismatch; Return MerchantInfo{merchantId, merchantName, schemeId}; Wire into commitMpmTransaction BEFORE deductForPayment call; Skip this step in commitCpmTransaction (merchant already resolved at CPM inbound notify)
**Deliverable:** MerchantValidationService.validateMerchantForCommit wired into MPM commit path
**Acceptance / logic checks:**
- Unknown QR code returns HTTP 404 with error_code=MERCHANT_NOT_FOUND before any prefunding deduction
- Inactive merchant QR returns HTTP 422 before prefunding deduction
- Scheme mismatch (QR belongs to zeropay, request says scheme_id=khqr) returns HTTP 422 VALIDATION_ERROR
- Valid active merchant: MerchantInfo returned and merchant_id stored on transaction record
- CPM commit path does not call validateMerchantForCommit
**Depends on:** 5.5-T08

### 5.5-T12 — Implement direction and scheme_id consistency check at commit  _(30 min)_
**Context:** The CommitRequest.direction and CommitRequest.scheme_id must match the values stored in the rate quote from POST /v1/rates. If a partner requests a quote for direction=inbound, scheme_id=zeropay, they must submit the commit with the same values. Mismatch returns HTTP 422 VALIDATION_ERROR. Additionally, the rule (partner x scheme x direction) must still be active at commit time (rules can be deactivated between quote and commit - use the rule_id stored in the quote, not the partner's current active rule). Check: rule.effective_to IS NULL OR rule.effective_to > now_utc(). If rule was deactivated, return HTTP 422 DIRECTION_NOT_ENABLED. These checks happen in QuoteValidationService after the quote is loaded.
**Steps:** Add direction match check to QuoteValidationService: throw ValidationException(field=direction) if mismatch; Add scheme_id match check: throw ValidationException(field=scheme_id) if mismatch; Add rule active check: load rule by quote.rule_id; if effective_to IS NOT NULL AND effective_to <= now, throw DirectionNotEnabledException; Map DirectionNotEnabledException to HTTP 422 with error_code=DIRECTION_NOT_ENABLED; Write unit test: direction mismatch with committed rule returns VALIDATION_ERROR; Write unit test: rule deactivated after quote issued returns DIRECTION_NOT_ENABLED
**Deliverable:** Direction/scheme/rule-active validation in QuoteValidationService
**Acceptance / logic checks:**
- Quote direction=inbound, commit direction=domestic: HTTP 422 VALIDATION_ERROR with field=direction
- Quote scheme_id=zeropay, commit scheme_id=khqr: HTTP 422 VALIDATION_ERROR with field=scheme_id
- Rule.effective_to = now-1s: HTTP 422 DIRECTION_NOT_ENABLED
- Rule.effective_to = null: passes (rule still active)
- Rule.effective_to = now+1s: passes (not yet expired)
**Depends on:** 5.5-T04

### 5.5-T13 — Implement SCHEME_SENT state and scheme timeout/UNCERTAIN handling  _(45 min)_
**Context:** After prefunding deduction, the orchestrator transitions state to SCHEME_SENT and calls the scheme adapter. Three outcomes: (1) success - scheme returns approval code, proceed to rate-lock and APPROVED; (2) synchronous failure - scheme returns explicit reject, set status=FAILED; (3) timeout or no response within scheme SLA - set status=UNCERTAIN and flag for settlement reconciliation. UNCERTAIN rule: if scheme response not received within scheme.sla_timeout_ms (default 30000ms for ZeroPay), set UNCERTAIN. A deduction held (NOT reversed) while UNCERTAIN. Reversal only happens if reconciliation later confirms FAILED. The orchestrator must NOT block the API response indefinitely - return HTTP 202 with status=pending for UNCERTAIN cases.
**Steps:** Transition status to SCHEME_SENT before calling scheme adapter; insert transaction_event step 3 (SCHEME_SUBMITTED); Call schemeAdapter.submitMpm with configured timeout; catch TimeoutException and SocketException; On timeout: set status=UNCERTAIN, insert SCHEME_UNCERTAIN event, return CommitResult{status=UNCERTAIN}; On scheme explicit reject: set status=FAILED, insert SCHEME_DECLINED event, return error; On scheme success: proceed to lockRate; insert SCHEME_APPROVED event; Map UNCERTAIN to HTTP 202 in the API handler; FAILED to HTTP 422 SCHEME_UNAVAILABLE; Log txn_ref, partner_id, scheme_id as correlation fields on all paths
**Deliverable:** Scheme call with UNCERTAIN handling and HTTP 202 response in TransactionOrchestrator
**Acceptance / logic checks:**
- Scheme timeout returns HTTP 202 with status field='pending' (or 'uncertain') and payment_id present
- Prefunding_account.balance NOT restored when status=UNCERTAIN (deduction held)
- status=UNCERTAIN transaction appears in a query for unresolved uncertainties (for settlement engine)
- Scheme explicit DECLINED: status=FAILED, prefunding deduction reversed for OVERSEAS partner
- transaction_event trail contains step SCHEME_SUBMITTED before scheme call and SCHEME_APPROVED/SCHEME_DECLINED/UNCERTAIN after
**Depends on:** 5.5-T08

### 5.5-T14 — Implement transaction_event 8-step audit trail writes in commit flow  _(40 min)_
**Context:** Every transaction carries an 8-step event trail in the transaction_event table (columns: id BIGINT PK, txn_id FK, step INT 1-8, event_type VARCHAR(50), occurred_at TIMESTAMPTZ, duration_ms INT, detail JSONB). The 8 steps are: (1) RATE_QUOTE_ISSUED, (2) PREFUND_DEDUCTED, (3) SCHEME_SUBMITTED, (4) SCHEME_APPROVED or SCHEME_DECLINED, (5) TRANSACTION_COMMITTED, (6) SETTLEMENT_BATCHED, (7) WEBHOOK_QUEUED, (8) WEBHOOK_DELIVERED. Steps 1-5 are written in the commit flow. Step 1 is written at rate-quote time (not this WBS). Steps 2-5 are written here. duration_ms = milliseconds since the previous step's occurred_at. detail JSONB carries step-specific payload: step 2 = {deducted_usd, balance_after}; step 3 = {scheme_id, payout_amount}; step 4 = {scheme_txn_id, approval_code} or {failure_code}; step 5 = {rate_locked_at}.
**Steps:** Create TransactionEventService with method writeEvent(Long txnId, int step, String eventType, JSONB detail); Calculate duration_ms from previous event's occurred_at (query last event for txn_id before inserting); Write step 2 event after successful prefunding deduction with detail={deducted_usd, balance_after}; Write step 3 event before scheme call with detail={scheme_id, payout_amount}; Write step 4 event after scheme response with event_type=SCHEME_APPROVED or SCHEME_DECLINED; Write step 5 event after rate-lock with detail={rate_locked_at}; Steps 6-8 are written by other services; do not write them here
**Deliverable:** TransactionEventService.writeEvent and step 2-5 writes integrated into commit flow
**Acceptance / logic checks:**
- After successful MPM commit, transaction_event has steps 2, 3, 4, 5 for that txnId in order
- Step 2 detail JSON contains keys deducted_usd and balance_after with correct values
- Step 4 event_type=SCHEME_APPROVED when scheme succeeds; SCHEME_DECLINED when scheme rejects
- duration_ms for step 3 is >= 0 and < 60000 (sanity check for non-negative millisecond value)
- LOCAL partner: step 2 is NOT written (no deduction occurred)
**Depends on:** 5.5-T08, 5.5-T13

### 5.5-T15 — Implement idempotency enforcement for POST /v1/payments  _(50 min)_
**Context:** All POST endpoints require an Idempotency-Key header (UUID, min 16 chars, max 128 chars). GMEPay+ stores (partner_id, idempotency_key, response_body_json, http_status) in Redis with 24h TTL. Scenarios: (1) same key + same body, request in-flight: return HTTP 409 with X-Idempotency-Status: in_flight; (2) same key + same body, completed: return stored response without re-processing; (3) same key + different body: return HTTP 422 IDEMPOTENCY_KEY_REUSE; (4) missing key on POST: HTTP 400 MISSING_IDEMPOTENCY_KEY. The idempotency check is the FIRST thing executed in the handler, before any business logic. Key format: idempotency:{partner_id}:{idempotency_key}. Store request body hash alongside response to detect body mismatch.
**Steps:** Create IdempotencyService with methods: checkAndLock(partnerId, key, bodyHash), storeResult(partnerId, key, httpStatus, responseBody), release(partnerId, key); Implement Redis SETNX for in-flight locking with 30s TTL (covers max request duration); if SETNX returns 0, check stored result; If stored result exists with different bodyHash, throw IdempotencyKeyReuseException; If stored result exists with same bodyHash, return StoredIdempotencyResult{httpStatus, responseBody}; On success: call storeResult with 24h TTL; Add filter/interceptor that enforces Idempotency-Key header on all POST endpoints
**Deliverable:** IdempotencyService with Redis-backed lock and result storage, wired to POST /v1/payments handler
**Acceptance / logic checks:**
- Missing Idempotency-Key header on POST /v1/payments returns HTTP 400 MISSING_IDEMPOTENCY_KEY
- Same key + same body, second call returns stored HTTP 201 response without hitting scheme adapter
- Same key + different body returns HTTP 422 IDEMPOTENCY_KEY_REUSE
- Concurrent duplicate calls (two threads, same key): one returns the in-flight 409, the other completes normally
- Stored idempotency result expires and key can be reused after 24h (verified with TTL inspection)
**Depends on:** 5.5-T08

### 5.5-T16 — Build payment.approved webhook payload assembler  _(35 min)_
**Context:** After CommitTransaction transitions to APPROVED, the orchestrator enqueues a payment.approved webhook to the partner's registered webhook_url. The payload follows the API-05 §6.5 schema: event envelope {event_id, event_type='payment.approved', created_at, partner_id, data:{...}}. The data object contains: payment_id, partner_txn_ref, scheme_txn_id, merchant_id, merchant_name, direction, scheme_id, target_payout, payout_currency, offer_rate (= offer_rate_coll from rate-lock), collection_amount (partner-supplied value echoed), collection_currency, service_charge, service_charge_currency (= settle_a_ccy), prefund_deducted_usd (OVERSEAS only; omit key for LOCAL), approved_at. offer_rate is the LOCKED offer_rate_coll (not re-computed). All monetary values are decimal strings in major units (e.g. '50000' KRW, '35.77' USD). event_id is a new globally-unique identifier (not payment_id).
**Steps:** Create PaymentApprovedPayloadBuilder with method build(Transaction txn, String schemeApprovalCode): WebhookPayload; Map txn.offer_rate_coll -> data.offer_rate as decimal string; For OVERSEAS: include prefund_deducted_usd = txn.prefunding_deducted_usd as string; for LOCAL: omit field entirely; Format all Instant fields as ISO-8601 UTC millisecond precision (e.g. 2026-06-04T09:31:16.847Z); Generate unique event_id with prefix 'evt_' + ULID or UUID; Write unit test asserting LOCAL partner payload has no prefund_deducted_usd key in JSON
**Deliverable:** PaymentApprovedPayloadBuilder producing spec-compliant JSON payload
**Acceptance / logic checks:**
- Serialized payload contains event_type='payment.approved' and all required data fields from API-05 §6.5
- offer_rate in payload matches txn.offer_rate_coll exactly (same 8-decimal value, formatted as decimal string)
- LOCAL partner payload: JSON does not contain key prefund_deducted_usd
- OVERSEAS partner payload: prefund_deducted_usd present and equals txn.prefunding_deducted_usd
- approved_at matches txn.completed_at in ISO-8601 UTC format
**Depends on:** 5.5-T07

### 5.5-T17 — Implement webhook dispatcher with HMAC signing and retry queue  _(55 min)_
**Context:** Webhook delivery uses POST to partner.webhook_url with HMAC-SHA256 signing. Headers: X-GME-Webhook-Signature: sha256=<HMAC-SHA256(body, webhook_signing_secret)>, X-GME-Webhook-Timestamp: <ISO-8601 UTC>, X-GME-Event-ID: <event_id>. The signing secret (webhook_signing_secret) is distinct from the API secret and stored in Secrets Manager. Partner must return HTTP 2xx within 10 seconds; any other response or timeout is a delivery failure. Retry back-off: immediate, 30s, 2min, 10min, 30min, 1h, 1h, 1h, 1h, 1h (10 attempts total per API-05 §6.2). After 10 failures: mark event delivery_failed, alert GME Ops. Payment.approved dispatch from commit flow is ASYNC (does not block the API response). Step 7 = WEBHOOK_QUEUED event written when enqueued; Step 8 = WEBHOOK_DELIVERED when 2xx received.
**Steps:** Create WebhookDispatcher with enqueueApproved(Long txnId, WebhookPayload payload) that pushes to async queue (Kafka topic or in-memory queue); Create WebhookDeliveryWorker that consumes the queue: HMAC-sign body, POST to webhook_url, handle retries; Compute signature: sha256=HMAC-SHA256(rawJsonBody, partnerWebhookSigningSecret); Implement retry with scheduled delays: immediate, 30s, 2min, 10min, 30min, 5x1h; after 10 failures mark delivery_failed; Write transaction_event step 7 (WEBHOOK_QUEUED) on enqueue; step 8 (WEBHOOK_DELIVERED) on 2xx; Alert GME Ops (log ERROR with partner_id and event_id) after final failed attempt
**Deliverable:** WebhookDispatcher with async delivery, HMAC signing, and 10-attempt retry
**Acceptance / logic checks:**
- HMAC-SHA256(body, secret) matches X-GME-Webhook-Signature header value (verified by computing expected signature in test)
- Partner returning HTTP 500 triggers retry; partner returning HTTP 200 marks delivered on first success
- After 10 failed attempts, webhook_event.delivery_status='delivery_failed' and Ops alert logged
- Webhook dispatch does NOT block commitMpmTransaction return (async)
- Step 7 (WEBHOOK_QUEUED) event written at enqueue time; Step 8 (WEBHOOK_DELIVERED) written after HTTP 200 received
**Depends on:** 5.5-T16

### 5.5-T18 — Implement POST /v1/payments API endpoint handler  _(45 min)_
**Context:** POST /v1/payments (API-05 §4.3) executes a Fixed MPM payment. The handler must: (1) authenticate partner via HMAC-SHA256 API key; (2) enforce Idempotency-Key header; (3) deserialize CommitRequest; (4) call orchestrator.commitMpmTransaction; (5) serialize CommitResult to response. HTTP status codes: 201 Created for APPROVED, 202 Accepted for PENDING/UNCERTAIN, 400 for validation errors, 402 for INSUFFICIENT_PREFUNDING, 404 for MERCHANT_NOT_FOUND, 409 for duplicate partner_txn_ref, 422 for RATE_QUOTE_EXPIRED/RATE_QUOTE_INVALID/PARTNER_B_QUOTE_DEVIATION/PARTNER_B_QUOTE_UNAVAILABLE/SCHEME_UNAVAILABLE, 429 for rate limit. Response includes X-Request-ID header with a fresh request ID. Required request fields: quote_id, merchant_qr, direction, scheme_id, customer_ref, partner_txn_ref, collection_amount, collection_currency.
**Steps:** Create PaymentsController with POST /v1/payments handler; Add authentication filter (from WBS 4.x or reference existing); add idempotency filter; Validate all required request fields; return HTTP 400 with VALIDATION_ERROR and error.details per field on failure; Call orchestrator.commitMpmTransaction(req, authenticatedPartnerId); Map CommitResult to HTTP 201 (APPROVED) or 202 (PENDING/UNCERTAIN); Map all CommitTransactionException subtypes to correct HTTP status codes and error_code values per API-05 §8.2; Add X-Request-ID response header generated per request
**Deliverable:** POST /v1/payments endpoint handler with correct HTTP status mapping and error model
**Acceptance / logic checks:**
- Valid request with sufficient prefunding returns HTTP 201 with payment_id and status='approved'
- Missing quote_id field returns HTTP 400 with error.code=VALIDATION_ERROR and error.details[0].field='quote_id'
- Expired quote returns HTTP 422 with error.code=RATE_QUOTE_EXPIRED
- Insufficient prefunding returns HTTP 402 with error.code=INSUFFICIENT_PREFUNDING
- Every response includes X-Request-ID header
**Depends on:** 5.5-T08, 5.5-T11, 5.5-T15

### 5.5-T19 — Implement POST /v1/payments/cpm/confirm API endpoint handler  _(35 min)_
**Context:** POST /v1/payments/cpm/confirm (implicitly derived from API-05 CPM flow §5.2) confirms a CPM payment after the partner receives payment.pending_debit and the customer approves. The request body contains txn_ref. The orchestrator.commitCpmTransaction is called. Response mirrors the MPM response structure (same CommitResult shape). HTTP 201 for APPROVED, 202 for UNCERTAIN, 422 for RATE_QUOTE_EXPIRED, 422 for transaction not in DEBITED state (PAYMENT_STATE_INVALID error). X-Request-ID header required. Authentication and Idempotency-Key required. Path is POST /v1/payments/cpm/confirm with body {txn_ref}.
**Steps:** Add POST /v1/payments/cpm/confirm endpoint to PaymentsController; Validate txn_ref is present in request body; return 400 VALIDATION_ERROR if missing; Call orchestrator.commitCpmTransaction(txnRef, authenticatedPartnerId); Map CommitResult to HTTP 201/202 same as MPM handler; Map invalid-state error to HTTP 422 with error_code=PAYMENT_STATE_INVALID; Add integration test: mock scheme returning approval; verify HTTP 201 and payment.approved webhook enqueued
**Deliverable:** POST /v1/payments/cpm/confirm endpoint handler
**Acceptance / logic checks:**
- Missing txn_ref returns HTTP 400 VALIDATION_ERROR
- Transaction in QUOTED state (not DEBITED) returns HTTP 422 PAYMENT_STATE_INVALID
- Successful CPM confirm returns HTTP 201 with status='approved' and rate-locked offer_rate
- UNCERTAIN scheme timeout returns HTTP 202
- Response contains X-Request-ID header
**Depends on:** 5.5-T09, 5.5-T18

### 5.5-T20 — Implement GET /v1/payments/{id} endpoint handler  _(30 min)_
**Context:** GET /v1/payments/{id} (API-05 §4.5) returns the full payment record. The response is the same shape as the POST /v1/payments response plus updated_at and cancelled_at. The endpoint must enforce partner ownership: a partner can only retrieve payments they created (partner_id match). Status values in response: 'approved', 'pending', 'failed', 'cancelled', 'uncertain'. Rate-locked fields (offer_rate, service_charge, prefund_deducted_usd) are always returned from the locked transaction columns - never re-computed. HTTP 200 if found, 404 PAYMENT_NOT_FOUND if not found or partner mismatch.
**Steps:** Add GET /v1/payments/{id} to PaymentsController; Load transaction by payment_id; throw PaymentNotFoundException if absent; Check transaction.partner_id == authenticatedPartnerId; throw PaymentNotFoundException (do not leak existence to other partners); Map transaction entity to PaymentResponse DTO (same fields as CommitResult plus updated_at, cancelled_at); Map status enum to API string values: APPROVED->'approved', UNCERTAIN->'uncertain', etc.; Return HTTP 200 with X-Request-ID header
**Deliverable:** GET /v1/payments/{id} endpoint returning full payment detail with ownership check
**Acceptance / logic checks:**
- Requesting payment owned by different partner returns HTTP 404 (not 403 - do not leak existence)
- Approved payment response includes rate-locked offer_rate, service_charge, prefund_deducted_usd from DB columns
- Status UNCERTAIN maps to string 'uncertain' in response
- HTTP 200 with X-Request-ID header on success
- All monetary fields are decimal strings in major units (e.g. '50000' not 50000000)
**Depends on:** 5.5-T18

### 5.5-T21 — Implement duplicate partner_txn_ref guard  _(35 min)_
**Context:** partner_txn_ref is a partner-supplied unique reference per transaction. The combination (partner_id, partner_txn_ref) must be unique. If a POST /v1/payments arrives with a partner_txn_ref already used by that partner (but with a different idempotency_key), return HTTP 409 with error_code=DUPLICATE_PARTNER_TXN_REF. This is distinct from idempotency: same idempotency_key=replay-safe response; different idempotency_key + same partner_txn_ref = business duplicate error. The transaction table must have a UNIQUE constraint on (partner_id, partner_txn_ref). The check happens when creating the transaction row, catching the unique constraint violation.
**Steps:** Add UNIQUE INDEX idx_transaction_partner_txn_ref ON transaction(partner_id, partner_txn_ref) in a migration (V5_5_T21 or add to V5_5 migration); In TransactionOrchestrator, catch DataIntegrityViolationException (unique constraint) on transaction INSERT; Re-query by partner_id + partner_txn_ref to check if it is the same idempotency_key: if yes, return idempotent response; if no, throw DuplicatePartnerTxnRefException; Map DuplicatePartnerTxnRefException to HTTP 409 with error_code=DUPLICATE_PARTNER_TXN_REF; Write unit test: same partner_txn_ref, different idempotency_key, different quote_id returns 409; Write unit test: same partner_txn_ref AND same idempotency_key returns stored 201 response
**Deliverable:** UNIQUE constraint on (partner_id, partner_txn_ref) and HTTP 409 guard in orchestrator
**Acceptance / logic checks:**
- Two distinct payments from same partner with same partner_txn_ref returns HTTP 409 DUPLICATE_PARTNER_TXN_REF for second
- Same partner_txn_ref + same idempotency_key (retry) returns HTTP 201 with stored response (not 409)
- Different partners can use same partner_txn_ref without conflict
- DB UNIQUE INDEX present on (partner_id, partner_txn_ref) in pg_indexes
- DUPLICATE_PARTNER_TXN_REF error has HTTP status 409 (not 422)
**Depends on:** 5.5-T15, 5.5-T08

### 5.5-T22 — Unit tests: rate-lock field values for cross-border inbound example  _(35 min)_
**Context:** Canonical numeric test vector (RATE-04 §4.3): target_payout=50000 KRW, cost_rate_pay=1380.00 (treasury.usd_krw), cost_rate_coll=1.0 (IDENTITY: Settle A=USD), m_a=0.01, m_b=0.01, service_charge=0.36 USD. Expected: payout_usd_cost=36.2319 USD (50000/1380), collection_usd=36.9714 USD (36.2319/0.98), collection_margin_usd=0.3697 USD, payout_margin_usd=0.3697 USD, send_amount=36.9714 USD (identity leg), collection_amount=37.3314 USD (36.9714+0.36), offer_rate_coll=1.01010 (36.9714/(36.9714-0.3697)), cross_rate=1352.24 KRW/USD (50000/36.9714). Pool identity: 36.9714-0.3697-0.3697=36.2320 (diff 0.0001 < 0.01 passes). All BigDecimal assertions must use scale tolerance of 0.0001 for 4-decimal-place values and 0.00000001 for 8-decimal-place values.
**Steps:** Create RateEngineCommitTest class; Build a RateQuote with the exact values above and call RateLockService.lockRate on a test transaction; Assert each of the 9 locked fields against expected values with tolerance; Assert pool identity: abs(collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost) < 0.01; Assert rate_locked_at IS NOT NULL after lock; Assert offer_rate_coll = send_amount / (collection_usd - collection_margin_usd) to 5 decimal places
**Deliverable:** Unit test class RateEngineCommitTest with cross-border inbound test vector fully asserted
**Acceptance / logic checks:**
- payout_usd_cost within 0.0001 of 36.2319
- collection_usd within 0.0001 of 36.9714
- collection_margin_usd within 0.0001 of 0.3697
- offer_rate_coll within 0.00001 of 1.01010
- Pool identity check: deviation < 0.01 USD (assert abs(36.9714-0.3697-0.3697-payout_usd_cost) < 0.01)
**Depends on:** 5.5-T07

### 5.5-T23 — Unit tests: rate-lock field values for USD identity-legs example  _(25 min)_
**Context:** Canonical numeric test vector (RATE-04 §7.1): OVERSEAS partner, USD settlement both sides. target_payout=100.00 USD, cost_rate_coll=1.0 (IDENTITY), cost_rate_pay=1.0 (IDENTITY), m_a=0.01, m_b=0.01, service_charge=0.50 USD. Expected: payout_usd_cost=100.00, collection_usd=102.0408 (100.00/0.98), collection_margin_usd=1.0204, payout_margin_usd=1.0204, send_amount=102.0408, collection_amount=102.5408, offer_rate_coll=1.01010 (102.0408/(102.0408-1.0204)), cross_rate=0.98000 (100.00/102.0408). Pool identity: 102.0408-1.0204-1.0204=100.0000 exactly. Test both the rate engine computation AND the rate-lock storage.
**Steps:** Add test method testIdentityLegsCommit to RateEngineCommitTest; Build RateQuote with both cost_rate_coll=1.0 and cost_rate_pay=1.0 (IDENTITY source), all values as above; Call lockRate; assert all 9 stored field values; Assert pool identity difference < 0.0001 USD (stricter since both legs are identity); Assert offer_rate_coll = 102.0408 / (102.0408 - 1.0204) within 0.00001; Assert cross_rate = 0.98000 within 0.00001
**Deliverable:** testIdentityLegsCommit test method in RateEngineCommitTest
**Acceptance / logic checks:**
- collection_usd within 0.0001 of 102.0408
- collection_amount within 0.0001 of 102.5408
- offer_rate_coll within 0.00001 of 1.01010
- cross_rate within 0.00001 of 0.98000
- Pool identity deviation < 0.0001
**Depends on:** 5.5-T22

### 5.5-T24 — Unit tests: rate-lock for same-currency short-circuit (domestic)  _(25 min)_
**Context:** Canonical numeric test vector (RATE-04 §7.2): GME Remit (LOCAL, KRW domestic). target_payout=15000 KRW, service_charge=500 KRW, m_a=0, m_b=0, is_same_ccy_shortcircuit=true. Expected: collection_amount=15500 KRW (15000+500), payout_usd_cost=NULL, collection_usd=NULL, collection_margin_usd=0, payout_margin_usd=0, cost_rate_coll=1.0, cost_rate_pay=1.0, offer_rate_coll=1.0, cross_rate=1.0. No prefunding deduction (LOCAL partner). Rate-lock must store NULLs for USD pool columns and zeros for margin columns. Test also verifies no prefunding_ledger_entry created.
**Steps:** Add test method testSameCurrencyShortCircuitCommit to RateEngineCommitTest; Build RateQuote with is_same_ccy_shortcircuit=true, target_payout=15000, service_charge=500, m_a=0, m_b=0; Call lockRate; assert payout_usd_cost IS NULL and collection_usd IS NULL in DB; Assert collection_amount=15500, collection_margin_usd=0, payout_margin_usd=0; Assert offer_rate_coll=1.0 and cross_rate=1.0; Assert no prefunding_ledger_entry row for this txnId
**Deliverable:** testSameCurrencyShortCircuitCommit test method with NULL-assertion on USD pool fields
**Acceptance / logic checks:**
- payout_usd_cost IS NULL in transaction row after lockRate
- collection_usd IS NULL in transaction row after lockRate
- collection_amount = 15500 (integer KRW scale, 0 decimals)
- No prefunding_ledger_entry with this txn_ref
- offer_rate_coll = 1.00000000 and cross_rate = 1.00000000
**Depends on:** 5.5-T22

### 5.5-T25 — Unit tests: PARTNER_B_QUOTE_DEVIATION and boundary conditions  _(30 min)_
**Context:** Test the deviation check (T05) with exact numeric examples. Test case A: rates_quote=1380.00, commit_quote=1393.80, tolerance=0.01. deviation=13.80/1380.00=0.01 EXACTLY. Expected: no exception (strict greater, not >=). Test case B: rates_quote=1380.00, commit_quote=1394.58, tolerance=0.01. deviation=14.58/1380.00=0.01057>0.01. Expected: PartnerBQuoteDeviationException. Test case C: Partner B API unreachable at commit time. Expected: PartnerBQuoteUnavailableException, no prefunding deduction. Test case D: cost_rate_pay_source=LIVE. Expected: deviation check skipped, no exception regardless of rate change.
**Steps:** Create PartnerBDeviationCheckerTest class; Test A: assert no exception for deviation exactly equal to tolerance (0.01 == 0.01, strict greater fails); Test B: assert PartnerBQuoteDeviationException for deviation 0.01057 > 0.01; Test C: mock PartnerBQuoteClient to throw IOException; assert PartnerBQuoteUnavailableException propagates; Test D: set cost_rate_pay_source=LIVE in quote; assert checkDeviation is never invoked; Test E: zero tolerance configured (tolerance=0.0): any difference throws exception
**Deliverable:** PartnerBDeviationCheckerTest with 5 test methods covering all deviation paths
**Acceptance / logic checks:**
- Test A passes (no exception): deviation=0.01, tolerance=0.01
- Test B throws PartnerBQuoteDeviationException: deviation=0.01057, tolerance=0.01
- Test C throws PartnerBQuoteUnavailableException when client is unreachable
- Test D: spy confirms checkDeviation never called when source=LIVE
- Test E throws for deviation=0.001 when tolerance=0.0
**Depends on:** 5.5-T05

### 5.5-T26 — Unit tests: pool identity failure guard  _(30 min)_
**Context:** The pool identity check (RATE-04 §5, §11.2) must fire if the computed collection_usd - collection_margin_usd - payout_margin_usd differs from payout_usd_cost by more than 0.01 USD. This should not happen with correct implementation, but must be tested by injecting a deliberately wrong value. Test: construct a RateQuote where collection_usd=36.9714, collection_margin_usd=0.3697, payout_margin_usd=0.3697 but artificially set payout_usd_cost=36.2000 (deviation=0.0320>0.01). The rate-lock service or computation layer should detect this and throw PoolIdentityFailureException (mapped to INTERNAL_ERROR HTTP 500). Also test the boundary: deviation=0.0099 passes; deviation=0.0101 fails.
**Steps:** Create PoolIdentityTest class; Test 1: valid pool (deviation 0.0001) - assert no exception from identity check; Test 2: deliberately wrong payout_usd_cost creating deviation 0.0320 - assert PoolIdentityFailureException; Test 3: boundary exactly at 0.01 deviation - assert no exception (tolerance is exclusive: > 0.01 fails); Test 4: boundary at 0.0101 - assert PoolIdentityFailureException; Verify PoolIdentityFailureException maps to HTTP 500 INTERNAL_ERROR in exception handler (not 422)
**Deliverable:** PoolIdentityTest class with 4 test methods and HTTP 500 mapping verification
**Acceptance / logic checks:**
- Valid deviation 0.0001 passes without exception
- Deviation 0.0320 throws PoolIdentityFailureException
- Deviation exactly 0.01 passes (not > 0.01)
- Deviation 0.0101 throws PoolIdentityFailureException
- PoolIdentityFailureException maps to HTTP 500 with error_code=INTERNAL_ERROR
**Depends on:** 5.5-T07, 5.5-T22

### 5.5-T27 — Unit tests: prefunding deduction atomicity under concurrency  _(45 min)_
**Context:** Two concurrent CommitTransaction calls for the same OVERSEAS partner, each requesting collection_usd=60.00 USD, while the account balance is 100.00 USD. Only one should succeed; the other should receive InsufficientPrefundingException. The successful one should leave balance=40.00 USD. Test requires two threads simultaneously calling deductForPayment. The SELECT FOR UPDATE ensures no race condition. Also test: single call requesting exactly the full balance (100.00 USD from 100.00 balance) succeeds; call requesting 100.01 USD fails.
**Steps:** Create PrefundingDeductionConcurrencyTest; Test 1: exact balance (100.00 USD request from 100.00 balance) succeeds, balance after = 0.00; Test 2: over-balance (100.01 USD from 100.00) fails with InsufficientPrefundingException carrying required=100.01, available=100.00; Test 3: two concurrent threads each requesting 60.00 from balance 100.00 - use CountDownLatch to synchronize start; assert exactly one succeeds, one throws InsufficientPrefundingException; Test 4: after successful deduction, prefunding_ledger_entry.balance_after = account.balance (consistency check); Use real PostgreSQL (port 5433) for concurrency test; mock is insufficient for SELECT FOR UPDATE verification
**Deliverable:** PrefundingDeductionConcurrencyTest with real DB concurrency test
**Acceptance / logic checks:**
- Test 1: balance after deduction = 0.00
- Test 2: InsufficientPrefundingException message contains available=100.00 and required=100.01
- Test 3: exactly 1 of 2 threads succeeds; balance after = 40.00
- Test 4: prefunding_ledger_entry.balance_after matches account.balance post-deduction
- Test 3 is deterministic (not flaky): SELECT FOR UPDATE guarantees mutual exclusion
**Depends on:** 5.5-T06

### 5.5-T28 — Unit tests: transaction state machine transitions in commit flow  _(35 min)_
**Context:** The transaction state machine (SAD-02 §5.2) has strict allowed transitions. Valid commit-time transitions: QUOTED->PENDING_DEBIT (OVERSEAS commit received), QUOTED->DEBITED (LOCAL partner, no prefund), PENDING_DEBIT->DEBITED (deduction success), DEBITED->SCHEME_SENT (scheme call dispatched), SCHEME_SENT->APPROVED (scheme success), SCHEME_SENT->FAILED (scheme reject), SCHEME_SENT->UNCERTAIN (timeout). Invalid: APPROVED->PENDING_DEBIT, FAILED->anything, DEBITED->APPROVED (must go through SCHEME_SENT). Each transition must be recorded in transaction_event. Test the transition guard: attempting an invalid transition raises InvalidStateTransitionException.
**Steps:** Create TransactionStateMachineTest; Test valid path: QUOTED->PENDING_DEBIT->DEBITED->SCHEME_SENT->APPROVED - assert each state persisted after each step; Test invalid transition: APPROVED->PENDING_DEBIT throws InvalidStateTransitionException; Test invalid transition: DEBITED->APPROVED (skipping SCHEME_SENT) throws InvalidStateTransitionException; Test terminal state: FAILED->DEBITED throws InvalidStateTransitionException; Assert transaction_event rows match state transitions in order with correct step numbers
**Deliverable:** TransactionStateMachineTest with valid and invalid transition tests
**Acceptance / logic checks:**
- Full QUOTED->APPROVED path produces 5 transaction_event rows with steps 2, 3, 4, 5 (step 1 already written at quote time)
- APPROVED->PENDING_DEBIT throws InvalidStateTransitionException
- DEBITED->APPROVED (bypassing SCHEME_SENT) throws InvalidStateTransitionException
- FAILED is terminal: any further transition attempt throws
- State persisted to DB after each transition (not just in-memory)
**Depends on:** 5.5-T13, 5.5-T14

### 5.5-T29 — Unit tests: payment.approved webhook payload correctness  _(35 min)_
**Context:** Test that the webhook payload produced by PaymentApprovedPayloadBuilder (T16) matches the exact schema from API-05 §6.5 for both OVERSEAS and LOCAL partner cases. OVERSEAS test vector: payment_id=pay_01, partner_txn_ref=SENDMN-TXN-001, scheme_txn_id=ZP2026001, merchant_name=Seoul Coffee House, direction=inbound, scheme_id=zeropay, target_payout=50000 KRW, offer_rate=0.000703, collection_amount=35.77, collection_currency=USD, service_charge=0.35, service_charge_currency=USD, prefund_deducted_usd=35.77. LOCAL test vector: all same except no prefund_deducted_usd field. HMAC signing test: verify X-GME-Webhook-Signature = 'sha256=' + hex(HMAC-SHA256(body_bytes, secret_bytes)).
**Steps:** Create PaymentApprovedWebhookTest class; Test 1 OVERSEAS: build payload from mock transaction with above fields; serialize to JSON; assert all fields present and correct; Test 2 LOCAL: assert JSON does not contain key prefund_deducted_usd; Test 3 HMAC signing: compute expected HMAC-SHA256 with known secret; compare to actual header value; Test 4 event_id uniqueness: build two payloads; assert event_ids differ; Test 5 timestamp format: assert created_at matches regex \d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z
**Deliverable:** PaymentApprovedWebhookTest with 5 test methods covering payload and signing
**Acceptance / logic checks:**
- All 15 required fields present in OVERSEAS payload matching API-05 §6.5 schema
- LOCAL payload JSON node count is 14 (no prefund_deducted_usd)
- HMAC signature: sha256=computed_hex matches X-GME-Webhook-Signature header
- Two consecutive payload builds produce different event_ids
- approved_at field in payload matches transaction.completed_at in UTC millisecond format
**Depends on:** 5.5-T16, 5.5-T17

### 5.5-T30 — Unit tests: idempotency semantics for POST /v1/payments  _(40 min)_
**Context:** Test the idempotency layer (T15) for all 4 scenarios from API-05 §7.1. Scenario 1: same key + same body, completed -> stored response returned. Scenario 2: same key + same body, in-flight -> HTTP 409 X-Idempotency-Status: in_flight. Scenario 3: same key + different body -> HTTP 422 IDEMPOTENCY_KEY_REUSE. Scenario 4: missing key -> HTTP 400 MISSING_IDEMPOTENCY_KEY. Additional: verify no double scheme call on idempotent replay (mock scheme adapter call count = 1 for two identical requests). Verify no double prefunding deduction (ledger entry count = 1 for two identical requests).
**Steps:** Create IdempotencyTest class; Test S1: complete a payment; replay with same key+body; assert second response matches first (same payment_id, HTTP 201); Test S2: simulate in-flight by holding the lock in Redis; second call returns HTTP 409; Test S3: change collection_amount in second request while reusing key; assert HTTP 422 IDEMPOTENCY_KEY_REUSE; Test S4: POST without Idempotency-Key header; assert HTTP 400 MISSING_IDEMPOTENCY_KEY; Test double-deduction prevention: verify schemeAdapter.submitMpm called exactly once for S1 replay
**Deliverable:** IdempotencyTest class with 5 test methods covering all idempotency scenarios
**Acceptance / logic checks:**
- S1: second call returns HTTP 201 with same payment_id as first call
- S2: in-flight second call returns HTTP 409 with X-Idempotency-Status: in_flight
- S3: different body returns HTTP 422 with error_code=IDEMPOTENCY_KEY_REUSE
- S4: missing header returns HTTP 400 with error_code=MISSING_IDEMPOTENCY_KEY
- S1 replay: schemeAdapter.submitMpm invocation count = 1 (no duplicate scheme call)
**Depends on:** 5.5-T15, 5.5-T18

### 5.5-T31 — Integration test: full MPM commit happy path (OVERSEAS partner)  _(55 min)_
**Context:** End-to-end integration test for MPM Fixed payment from POST /v1/rates through POST /v1/payments to APPROVED state and webhook enqueue. Uses real PostgreSQL (port 5433), real Redis, and a mock ZeroPay scheme adapter. Test setup: OVERSEAS partner (SendMN), rule with m_a=0.01, m_b=0.01, service_charge=0.36 USD, cost_rate_pay=1380.00, cost_rate_coll=1.0 (IDENTITY). Step 1: POST /v1/rates with target_payout=50000 KRW, expect quote_id and offer_rate=approx 0.000703. Step 2: POST /v1/payments with quote_id, expect HTTP 201 APPROVED, scheme_txn_id present. Step 3: verify transaction DB row has all 13 rate-lock columns set. Step 4: verify prefunding balance reduced by 36.97 USD (approx collection_usd). Step 5: verify payment.approved webhook enqueued.
**Steps:** Create MpmCommitIntegrationTest with @SpringBootTest or equivalent; Seed partner, scheme, rule, merchant, QR in test DB; set prefunding balance to 500.00 USD; Execute POST /v1/rates and capture quote_id; Execute POST /v1/payments; assert HTTP 201 and APPROVED status; Query transaction table; assert rate_locked_at IS NOT NULL, offer_rate_coll within 0.0001 of 1.01010; Query prefunding_account; assert balance reduced by approx 36.97 USD (within 0.01); Assert webhook message enqueued for payment.approved event
**Deliverable:** MpmCommitIntegrationTest covering full happy path from quote to approved to webhook
**Acceptance / logic checks:**
- HTTP 201 with status='approved' and scheme_txn_id present
- Transaction.rate_locked_at IS NOT NULL after commit
- Transaction.collection_usd within 0.01 of 36.97
- prefunding_account.balance decreased by transaction.collection_usd exactly
- Webhook queue contains one payment.approved event for this payment_id
**Depends on:** 5.5-T18, 5.5-T22

### 5.5-T32 — Integration test: full CPM commit happy path  _(55 min)_
**Context:** End-to-end test for CPM flow: POST /v1/payments/cpm/generate -> inbound CPM notification (simulated) -> POST /v1/payments/cpm/confirm -> APPROVED. Uses real DB and Redis. Test setup same as T31. Step 1: POST /v1/payments/cpm/generate - get cpm_token_id and prepare_token; verify prefunding reserved. Step 2: simulate ZeroPay inbound notification (call internal handler directly or via test endpoint) with payout_amount=32000 KRW, merchant_id - triggers rate computation and payment.pending_debit webhook. Step 3: POST /v1/payments/cpm/confirm with txn_ref - expect HTTP 201 APPROVED. Step 4: verify rate-lock columns set, verify two webhook events: payment.pending_debit and payment.approved.
**Steps:** Create CpmCommitIntegrationTest; POST /v1/payments/cpm/generate with OVERSEAS partner; assert HTTP 201 and prepare_token; Simulate inbound CPM notification with payout_amount=32000 KRW; assert payment.pending_debit webhook enqueued; POST /v1/payments/cpm/confirm; assert HTTP 201 APPROVED; Verify transaction rate-lock columns: payout_usd_cost = 32000/1380 = approx 23.19, collection_usd = approx 23.66; Verify two webhook events queued: payment.pending_debit and payment.approved in that order
**Deliverable:** CpmCommitIntegrationTest covering full CPM flow to approved and both webhooks
**Acceptance / logic checks:**
- HTTP 201 from /cpm/generate with prefund_reserved_usd > 0
- payment.pending_debit webhook enqueued after inbound notification with offer_rate field present
- HTTP 201 from /cpm/confirm with status='approved'
- transaction.payout_usd_cost within 0.01 of 23.19 (32000/1380)
- Two webhook events queued: pending_debit before approved
**Depends on:** 5.5-T09, 5.5-T31

### 5.5-T33 — Integration test: commit with expired quote returns 422  _(35 min)_
**Context:** Verify the TTL expiry guard end-to-end. Test: obtain a quote with TTL=5s (set partner.rate_quote_ttl_seconds=5 for test partner), wait 6 seconds, then attempt POST /v1/payments. Expected: HTTP 422 with error_code=RATE_QUOTE_EXPIRED, no transaction row created, no prefunding deduction. Also verify that the error.details[0].issue field contains the expired valid_until timestamp. Additionally test that attempting to reuse the quote_id even after obtaining a fresh quote does not allow the expired quote to be used (expired quote stays expired).
**Steps:** Create QuoteExpiryIntegrationTest; Configure test partner with rate_quote_ttl_seconds=5; POST /v1/rates; capture quote_id and valid_until; Wait 6 seconds (Thread.sleep or advance mock clock); POST /v1/payments with expired quote_id; assert HTTP 422 RATE_QUOTE_EXPIRED; Query transaction table; assert no row with this quote_id; Query prefunding_account; assert balance unchanged
**Deliverable:** QuoteExpiryIntegrationTest verifying expiry guard end-to-end
**Acceptance / logic checks:**
- HTTP 422 with error_code=RATE_QUOTE_EXPIRED
- No transaction row created for expired commit attempt
- prefunding_account.balance unchanged after failed expired-quote attempt
- error.details contains the expired valid_until timestamp
- Fresh quote with same partner can be committed successfully (expiry does not affect new quotes)
**Depends on:** 5.5-T10, 5.5-T31

### 5.5-T34 — Integration test: insufficient prefunding returns 402  _(35 min)_
**Context:** Verify the insufficient prefunding guard end-to-end. Test: OVERSEAS partner with balance=10.00 USD attempts a payment where collection_usd would be approx 36.97 USD (target_payout=50000 KRW, same rates as T31). Expected: HTTP 402 with error_code=INSUFFICIENT_PREFUNDING, no transaction row, no scheme call, balance unchanged. Also test the LOCAL partner version: GET /v1/balance returns HTTP 403 for LOCAL partner (prefunding does not apply).
**Steps:** Create PrefundingIntegrationTest; Set OVERSEAS partner prefunding balance to 10.00 USD in test DB; POST /v1/rates with target_payout=50000 KRW; capture quote_id (collection_usd approx 36.97); POST /v1/payments; assert HTTP 402 INSUFFICIENT_PREFUNDING; Query transaction table; assert no committed row for this attempt; Assert mock scheme adapter was NOT called; Assert prefunding_account.balance remains 10.00 USD
**Deliverable:** PrefundingIntegrationTest with insufficient balance and LOCAL partner 403 tests
**Acceptance / logic checks:**
- HTTP 402 with error_code=INSUFFICIENT_PREFUNDING for balance=10.00 USD, collection_usd=36.97 USD
- No transaction row created for failed prefunding attempt
- schemeAdapter.submitMpm was never called
- balance remains 10.00 USD after failed attempt
- LOCAL partner calling GET /v1/balance returns HTTP 403
**Depends on:** 5.5-T06, 5.5-T31

### 5.5-T35 — Write API-05 endpoint documentation for POST /v1/payments commit flow  _(40 min)_
**Context:** Document the POST /v1/payments endpoint in the project's API reference docs (OpenAPI 3.0 YAML or equivalent). The documentation must cover: request fields (all 8 required + 1 optional from API-05 §4.3), response fields (all 14 from spec), all HTTP status codes (201, 202, 400, 402, 404, 409, 422, 429), all error codes with descriptions, the note that collection_amount is echoed not validated, the rate-lock guarantee statement, and the idempotency header requirement. Include the worked example from the spec (target_payout=50000 KRW, offer_rate=0.000703).
**Steps:** Locate or create openapi/paths/payments.yaml; Add POST /v1/payments operation with requestBody schema including all 8 required fields; Add responses for 201, 202, 400, 402, 404, 409, 422, 429 with error schema references; Add description note: GMEPay+ records collection_amount as supplied without validation; Add description note: all USD pool values are permanently locked at execution (rate_locked_at); Add example request/response using the canonical SendMN/KRW example from API-05 §4.3; Validate YAML against OpenAPI 3.0 schema using a linter
**Deliverable:** OpenAPI 3.0 YAML for POST /v1/payments with all fields, status codes, and worked example
**Acceptance / logic checks:**
- OpenAPI linter reports 0 errors on the YAML file
- All 8 required request fields present with correct types (string/decimal for monetary, string for IDs)
- All 6 HTTP status code responses documented (201, 202, 400, 402, 404, 422)
- collection_amount description notes it is echoed not validated
- Rate-lock guarantee mentioned in endpoint description
**Depends on:** 5.5-T18

### 5.5-T36 — Write API-05 endpoint documentation for payment.approved webhook  _(30 min)_
**Context:** Document the payment.approved webhook event in the project's API reference docs. The documentation must cover: event envelope schema (event_id, event_type, created_at, partner_id, data), all data fields from API-05 §6.5 including the conditional prefund_deducted_usd (OVERSEAS only), webhook signing headers (X-GME-Webhook-Signature format: sha256=<HMAC-SHA256>, X-GME-Webhook-Timestamp, X-GME-Event-ID), retry schedule (10 attempts: immediate, 30s, 2min, 10min, 30min, 1h x5), partner verification instructions (compare HMAC, reject if timestamp > 5min old), and the worked example from API-05 §6.5. Also document the event delivery guarantee: at-least-once (idempotent handling required).
**Steps:** Locate or create openapi/webhooks/payment_approved.yaml or equivalent docs section; Document event envelope schema with all fields and types; Document data object with all 14 fields including conditional prefund_deducted_usd; Document the three signing headers and HMAC verification algorithm; Document retry schedule as a table: attempt number -> delay; Add partner verification code snippet (pseudocode for HMAC check); Validate the worked example JSON against the documented schema
**Deliverable:** Webhook documentation for payment.approved event with schema, signing, retry, and example
**Acceptance / logic checks:**
- Documentation lists all 14 data fields from API-05 §6.5
- Retry schedule shows 10 attempts with correct delays (immediate, 30s, 2min, 10min, 30min, 5x1h)
- HMAC verification algorithm documented: sha256=hex(HMAC-SHA256(raw_body, webhook_signing_secret))
- Conditional note for prefund_deducted_usd: present for OVERSEAS partners only
- At-least-once delivery guarantee and idempotency requirement documented
**Depends on:** 5.5-T17, 5.5-T35

### 5.5-T37 — Resolve partner settlement rounding mode at commit  _(30 min)_
**Context:** payment-executor calls config-registry GET /v1/partners/{id} to get settlementRoundingMode (default HALF_UP). Stub the client until integration.
**Steps:** Add PartnerConfigClient interface + call at commit; Default HALF_UP if unavailable
**Deliverable:** PartnerConfigClient returning the rounding mode
**Acceptance / logic checks:**
- mode resolved per partner
- fallback HALF_UP on miss
**Depends on:** 8.4

### 5.5-T38 — Book settlement amount under partner rule + lock on transaction  _(40 min)_
**Context:** Use lib-money SettlementRounding.book(preciseSettlementAmount, scale, mode) to compute booked + residual; persist booked + mode + residual onto the transaction (rate-lock).
**Steps:** Call SettlementRounding.book(...) at CommitTransaction; Set booked_settlement_amount, settlement_rounding_mode, rounding_residual on the txn
**Deliverable:** Commit path books settlement under partner rule and locks values
**Acceptance / logic checks:**
- booked == precise rounded by partner mode
- residual == precise - booked
- values locked on txn
**Depends on:** 3.3

### 5.5-T39 — Emit rounding residual to revenue-ledger on commit  _(30 min)_
**Context:** After booking, call revenue-ledger postRoundingResidual(ref, residual, ccy) (via event or sync) so the rounding gain/loss is posted to REVENUE_ROUNDING.
**Steps:** On commit, publish residual to revenue-ledger; Handle zero residual (no post)
**Deliverable:** Residual emitted to revenue-ledger at commit
**Acceptance / logic checks:**
- non-zero residual posted
- zero residual posts nothing
**Depends on:** 7.3


## WBS 5.6 — Same-day cancellation
### 5.6-T01 — Add cancelled_at and cancel_reason columns to payments table (migration)  _(25 min)_
**Context:** WBS 5.6 Same-day cancellation. The payments table must store cancellation metadata. New columns: cancelled_at (TIMESTAMPTZ, nullable), cancel_reason (VARCHAR(50), nullable, allowed values: customer_request, merchant_request, timeout, other), cancel_reason_detail (VARCHAR(200), nullable). Status column already exists with values PENDING, APPROVED, FAILED etc.; CANCELLED is a new valid value to be added. KST timezone is used for same-day window checks but timestamps are stored in UTC.
**Steps:** Create a Flyway migration file V5_6_001__cancel_columns.sql in db/migrations/; Add column: ALTER TABLE payments ADD COLUMN cancelled_at TIMESTAMPTZ NULL; Add column: ALTER TABLE payments ADD COLUMN cancel_reason VARCHAR(50) NULL; Add column: ALTER TABLE payments ADD COLUMN cancel_reason_detail VARCHAR(200) NULL; Add CHECK constraint: cancel_reason IN ('customer_request','merchant_request','timeout','other'); Add the CANCELLED value to the status enum/check constraint if not already present
**Deliverable:** db/migrations/V5_6_001__cancel_columns.sql — applied cleanly via Flyway on a fresh schema
**Acceptance / logic checks:**
- Migration applies without error on an empty schema and on a schema with existing payment rows
- cancelled_at column accepts NULL and a valid UTC timestamp; rejects non-timestamp values
- cancel_reason column rejects any value not in the allowed set ('customer_request','merchant_request','timeout','other')
- cancel_reason_detail column accepts NULL and strings up to 200 chars; rejects strings > 200 chars
- Existing rows with status APPROVED remain unaffected after migration

### 5.6-T02 — Add prefund_reversal_usd column to payments table (migration)  _(20 min)_
**Context:** WBS 5.6 Same-day cancellation. When an OVERSEAS partner payment is cancelled, the prefunding balance is restored by exactly the collection_usd amount that was deducted. The response and webhook field prefund_returned_usd is sourced from this column. The column must be stored as NUMERIC(18,8) to match existing monetary field precision. This is nullable because LOCAL partners have no prefunding.
**Steps:** Create migration file V5_6_002__cancel_prefund_reversal.sql in db/migrations/; Add column: ALTER TABLE payments ADD COLUMN prefund_reversal_usd NUMERIC(18,8) NULL; Add comment/documentation: COMMENT ON COLUMN payments.prefund_reversal_usd IS 'Amount returned to OVERSEAS partner prefunding on cancel; equals collection_usd at time of deduction; NULL for LOCAL partners'
**Deliverable:** db/migrations/V5_6_002__cancel_prefund_reversal.sql
**Acceptance / logic checks:**
- Migration applies cleanly after V5_6_001
- Column stores values such as 35.77000000 correctly (2 decimal precision example)
- Column stores NULL for a LOCAL partner row
- Column rejects non-numeric input
- Existing rows are unaffected (column is NULL by default)
**Depends on:** 5.6-T01

### 5.6-T03 — Add DB index on (partner_id, created_at_kst_date) for same-day window query  _(20 min)_
**Context:** WBS 5.6 Same-day cancellation. The cancel eligibility check must query whether a payment was created on the same calendar day in KST (UTC+9). Created_at is stored as UTC TIMESTAMPTZ. A functional index on (partner_id, (created_at AT TIME ZONE 'Asia/Seoul')::date) enables efficient same-day lookup. Without it, the eligibility check is a full-table scan.
**Steps:** Create migration file V5_6_003__cancel_sameday_index.sql in db/migrations/; Add index: CREATE INDEX idx_payments_partner_kst_date ON payments (partner_id, ((created_at AT TIME ZONE 'Asia/Seoul')::date)); Verify the index name is unique across existing indexes
**Deliverable:** db/migrations/V5_6_003__cancel_sameday_index.sql
**Acceptance / logic checks:**
- Migration applies cleanly
- EXPLAIN on SELECT * FROM payments WHERE partner_id='prt_1' AND (created_at AT TIME ZONE 'Asia/Seoul')::date = '2026-06-04' uses the new index (not seq scan)
- Index does not exist prior to migration; exists after
- Migration is idempotent when run in a CI fresh-schema pipeline
**Depends on:** 5.6-T01

### 5.6-T04 — Define CancelPaymentRequest and CancelPaymentResponse DTOs  _(30 min)_
**Context:** WBS 5.6 Same-day cancellation. The cancel endpoint POST /v1/payments/{id}/cancel accepts a JSON body with: reason (string, required, enum: customer_request|merchant_request|timeout|other) and reason_detail (string, optional, max 200 chars). Response fields: payment_id (string), status (always 'cancelled'), cancelled_at (ISO-8601 UTC string), prefund_returned_usd (string decimal, OVERSEAS only, omit or null for LOCAL). All DTOs must use Java records or immutable POJOs with Jackson annotations.
**Steps:** Create src/main/java/com/gmepayplus/api/cancel/dto/CancelPaymentRequest.java; Add @NotNull @Pattern validation on reason field limiting to the four allowed values; Add @Size(max=200) on reason_detail (nullable); Create src/main/java/com/gmepayplus/api/cancel/dto/CancelPaymentResponse.java; Add fields: payment_id (String), status (String), cancelled_at (Instant), prefund_returned_usd (BigDecimal, nullable); Add @JsonInclude(NON_NULL) so prefund_returned_usd is omitted from LOCAL partner responses
**Deliverable:** CancelPaymentRequest.java and CancelPaymentResponse.java in the cancel/dto package
**Acceptance / logic checks:**
- Jackson serializes a CancelPaymentResponse with prefund_returned_usd=null and the field is absent from JSON output
- Jackson serializes a CancelPaymentResponse with prefund_returned_usd=35.77 as the string '35.77' (decimal string, not float)
- @Valid on CancelPaymentRequest rejects reason='wrong' with a 400 binding error
- @Valid on CancelPaymentRequest rejects reason_detail of 201 characters
- @Valid on CancelPaymentRequest accepts reason='customer_request' with null reason_detail
**Depends on:** 5.6-T01

### 5.6-T05 — Implement SameDayWindowService: determine if a payment is within the same-day KST cancel window  _(30 min)_
**Context:** WBS 5.6 Same-day cancellation. Cancellation is only permitted when the payment's created_at falls on the same calendar day in KST (UTC+9) as the current instant. Business rule: compare (created_at AT TIME ZONE Asia/Seoul)::date == (NOW() AT TIME ZONE Asia/Seoul)::date. The service must accept a created_at Instant and return boolean. No hardcoded timezone strings — use ZoneId.of('Asia/Seoul'). This service is used by the cancel eligibility check before any DB write.
**Steps:** Create src/main/java/com/gmepayplus/cancel/SameDayWindowService.java; Inject a Clock bean (not System.currentTimeMillis) so tests can fix the current time; Implement boolean isWithinSameDayWindow(Instant createdAt): convert both instants to Asia/Seoul LocalDate and compare; Add a Spring @Service annotation; Write no DB calls in this class — pure time logic only
**Deliverable:** SameDayWindowService.java with a Clock-injectable constructor
**Acceptance / logic checks:**
- isWithinSameDayWindow returns true when createdAt=2026-06-04T00:00:00Z and clock=2026-06-04T14:59:59Z (both KST date 2026-06-05... wait: UTC 00:00 = KST 09:00, so KST date is 2026-06-04; clock 14:59:59Z = KST 23:59:59 same day) -> true
- isWithinSameDayWindow returns false when createdAt=2026-06-03T15:00:00Z (KST 2026-06-04 00:00) and clock=2026-06-04T15:00:00Z (KST 2026-06-05 00:00) -> false (next KST day)
- isWithinSameDayWindow returns false when createdAt=2026-06-03T12:00:00Z (KST 2026-06-03 21:00) and clock=2026-06-04T12:00:00Z (KST 2026-06-04 21:00) -> false
- isWithinSameDayWindow returns true for createdAt one second ago relative to fixed clock
- Service compiles and has zero direct calls to ZonedDateTime.now() without the injected Clock

### 5.6-T06 — Implement CancelEligibilityService: validate payment status and same-day window  _(30 min)_
**Context:** WBS 5.6 Same-day cancellation. A payment is cancellable only when: (1) status is APPROVED or PENDING (not FAILED, CANCELLED, or any terminal state); and (2) the same-day KST window is open (see SameDayWindowService). If status is already CANCELLED or FAILED, return ALREADY_TERMINAL. If status is valid but window is closed, return CANCEL_NOT_PERMITTED. If both conditions pass, return ELIGIBLE. Use these result codes as an enum: ELIGIBLE, ALREADY_TERMINAL, CANCEL_NOT_PERMITTED. This service is called before any DB write or prefunding reversal.
**Steps:** Create src/main/java/com/gmepayplus/cancel/CancelEligibilityService.java; Define inner enum CancelEligibilityResult {ELIGIBLE, ALREADY_TERMINAL, CANCEL_NOT_PERMITTED}; Inject SameDayWindowService; Implement CancelEligibilityResult check(Payment payment): check status first, then window; Cancellable statuses: APPROVED, PENDING (PENDING means rate-quoted not yet committed — also cancellable); Non-cancellable terminal statuses: FAILED, CANCELLED, REFUNDED, REVERSED — return ALREADY_TERMINAL
**Deliverable:** CancelEligibilityService.java with enum CancelEligibilityResult
**Acceptance / logic checks:**
- Payment(status=APPROVED, createdAt=today KST) -> ELIGIBLE
- Payment(status=CANCELLED, createdAt=today KST) -> ALREADY_TERMINAL
- Payment(status=FAILED, createdAt=today KST) -> ALREADY_TERMINAL
- Payment(status=APPROVED, createdAt=yesterday KST) -> CANCEL_NOT_PERMITTED
- Payment(status=PENDING, createdAt=today KST) -> ELIGIBLE
**Depends on:** 5.6-T05

### 5.6-T07 — Implement PrefundingReversalService: atomically restore collection_usd to OVERSEAS partner balance  _(45 min)_
**Context:** WBS 5.6 Same-day cancellation. When an OVERSEAS partner payment is cancelled, the prefunding balance must be restored by exactly the collection_usd amount stored on the payment record (the amount deducted at payment commit). The reversal uses SELECT ... FOR UPDATE on the prefunding_balances row (same atomicity pattern as the deduction in WBS RATE-04/prefunding). LOCAL partners (partner_type=LOCAL) skip this step. The service records a ledger entry with type=CANCEL_REVERSAL. Returns the restored amount as BigDecimal.
**Steps:** Create src/main/java/com/gmepayplus/cancel/PrefundingReversalService.java; Inject PrefundingRepository (existing from prefunding deduction work); Implement BigDecimal reverse(UUID paymentId, UUID partnerId, PartnerType partnerType, BigDecimal collectionUsd) inside a @Transactional method; If partnerType == LOCAL, skip DB write, return BigDecimal.ZERO; SELECT ... FOR UPDATE on prefunding_balances WHERE partner_id = partnerId; Increment balance by collectionUsd; Insert ledger entry: type=CANCEL_REVERSAL, payment_id, amount_usd=collectionUsd (positive), timestamp=now; Return collectionUsd as the restored amount
**Deliverable:** PrefundingReversalService.java with @Transactional reverse() method
**Acceptance / logic checks:**
- OVERSEAS partner with balance 100.00 USD and collectionUsd 35.77: after reverse(), balance is 135.77 USD
- LOCAL partner: reverse() returns 0.00 and no ledger row is inserted
- Concurrent calls for different partners do not deadlock (row-level lock only)
- Ledger entry type is CANCEL_REVERSAL and amount_usd matches collectionUsd exactly
- reverse() is idempotent-safe: calling twice for the same paymentId should be guarded by the caller (no double-credit logic here — caller ensures single invocation via idempotency key)
**Depends on:** 5.6-T02

### 5.6-T08 — Implement ZeroPayCancelAdapter: call ZeroPay real-time cancel API  _(40 min)_
**Context:** WBS 5.6 Same-day cancellation. The ZeroPay scheme adapter interface includes cancelPayment(scheme_txn_ref) -> CancelResult (spec SCH-06 section 1.3.1). For same-day cancel, GMEPay+ must call ZeroPay's real-time HTTPS cancel API with the scheme_txn_ref stored on the payment. The call uses the existing scheme config (realtime_api_base_url from scheme config record). A successful ZeroPay cancel returns HTTP 200. On HTTP 4xx, throw CancelSchemeException with the raw ZeroPay error code. On HTTP 5xx or timeout, throw SchemeUnavailableException. If the payment has no scheme_txn_ref (e.g. status was PENDING before scheme submission), skip the scheme call and return CancelResult.NOT_SUBMITTED.
**Steps:** Create src/main/java/com/gmepayplus/scheme/zeropay/ZeroPayCancelAdapter.java; Inject the ZeroPay HTTP client (existing from payment commit work); Implement CancelResult cancelPayment(String schemeTxnRef): if schemeTxnRef is null, return CancelResult.NOT_SUBMITTED; Build the ZeroPay cancel request per scheme spec (POST to {base_url}/cancel with body {txn_ref: schemeTxnRef}); On 200 response, return CancelResult.SUCCESS; On 4xx, parse ZeroPay error code, throw CancelSchemeException(code, message); On 5xx/timeout, throw SchemeUnavailableException
**Deliverable:** ZeroPayCancelAdapter.java implementing SchemeAdapter.cancelPayment()
**Acceptance / logic checks:**
- Mock ZeroPay returning 200: CancelResult.SUCCESS returned
- Mock ZeroPay returning 404: CancelSchemeException thrown with raw ZeroPay code
- Mock ZeroPay returning 500: SchemeUnavailableException thrown
- schemeTxnRef=null: CancelResult.NOT_SUBMITTED returned without any HTTP call
- Adapter reads base_url from SchemeConfig, not a hardcoded string

### 5.6-T09 — Implement CancelPaymentUseCase: orchestrate eligibility, scheme cancel, prefund reversal, status update  _(55 min)_
**Context:** WBS 5.6 Same-day cancellation. The core cancel orchestration: (1) Load payment by ID, verify it belongs to the calling partner (return 404 if not found, 403 if wrong partner). (2) Check eligibility via CancelEligibilityService; map ALREADY_TERMINAL -> HTTP 409, CANCEL_NOT_PERMITTED -> HTTP 400 with error code CANCEL_NOT_PERMITTED. (3) Call ZeroPayCancelAdapter.cancelPayment(scheme_txn_ref) if status is APPROVED/SUBMITTED (skip if PENDING/not yet submitted). (4) Call PrefundingReversalService.reverse() for OVERSEAS partners. (5) In a single @Transactional block: update payment status=CANCELLED, set cancelled_at=now(), cancel_reason, cancel_reason_detail, prefund_reversal_usd. (6) Return CancelPaymentResponse. Steps 3 and 4 must occur before the DB commit in step 5 to ensure consistency.
**Steps:** Create src/main/java/com/gmepayplus/cancel/CancelPaymentUseCase.java; Inject PaymentRepository, CancelEligibilityService, ZeroPayCancelAdapter, PrefundingReversalService; Implement CancelPaymentResponse execute(UUID paymentId, UUID partnerId, CancelPaymentRequest request); Fetch payment; throw PaymentNotFoundException if absent; throw UnauthorizedException if payment.partner_id != partnerId; Call eligibilityService.check(); throw appropriate exception based on result; Call adapter.cancelPayment() if payment has a scheme_txn_ref (status APPROVED or higher); tolerate NOT_SUBMITTED; Call prefundingReversalService.reverse() and capture reversedAmount; In @Transactional: set status=CANCELLED, cancelled_at, cancel_reason, cancel_reason_detail, prefund_reversal_usd=reversedAmount; Build and return CancelPaymentResponse
**Deliverable:** CancelPaymentUseCase.java with full orchestration logic
**Acceptance / logic checks:**
- APPROVED OVERSEAS payment: status becomes CANCELLED, prefund_reversal_usd equals original collection_usd, scheme cancel is called
- PENDING LOCAL payment: status becomes CANCELLED, prefund_reversal_usd is null, scheme cancel is NOT called (no scheme_txn_ref)
- Already-CANCELLED payment: returns HTTP 409 without any DB write
- APPROVED payment from wrong partner_id: throws 404 (do not leak existence to wrong partner)
- Window-expired APPROVED payment (created yesterday KST): returns HTTP 400 with code CANCEL_NOT_PERMITTED
**Depends on:** 5.6-T06, 5.6-T07, 5.6-T08

### 5.6-T10 — Implement POST /v1/payments/{id}/cancel HTTP controller  _(40 min)_
**Context:** WBS 5.6 Same-day cancellation. The Northbound REST endpoint is POST /v1/payments/{id}/cancel. Auth: existing partner API-key + X-Timestamp + X-Signature headers (already validated by a shared filter). Idempotency-Key header is required (HTTP 400 MISSING_IDEMPOTENCY_KEY if absent). Path param: id (payment_id). Request body: CancelPaymentRequest. The idempotency cache stores (partner_id, idempotency_key, response_body, http_status) for 24 hours — if a duplicate request arrives with the same key, return the cached response. Status codes: 200 OK, 400 CANCEL_NOT_PERMITTED, 404 PAYMENT_NOT_FOUND, 409 (terminal state conflict).
**Steps:** Create src/main/java/com/gmepayplus/api/cancel/CancelPaymentController.java; Annotate with @RestController, @RequestMapping('/v1/payments'); Implement @PostMapping('/{id}/cancel') handler; Extract partner_id from authenticated security context (set by API-key filter); Check Idempotency-Key header; return 400 MISSING_IDEMPOTENCY_KEY if absent; Check idempotency cache: if hit with matching partner_id+key, return cached response; Call CancelPaymentUseCase.execute(); cache the response before returning; Map exceptions: PaymentNotFoundException->404, ALREADY_TERMINAL->409, CANCEL_NOT_PERMITTED->400; Return CancelPaymentResponse as JSON with X-Request-ID header
**Deliverable:** CancelPaymentController.java wired to CancelPaymentUseCase
**Acceptance / logic checks:**
- POST with valid approved same-day payment returns HTTP 200 and JSON body with status='cancelled'
- POST without Idempotency-Key header returns HTTP 400 with code MISSING_IDEMPOTENCY_KEY
- Second POST with same Idempotency-Key returns same 200 response without re-executing use case
- POST for unknown payment_id returns HTTP 404
- POST for wrong partner's payment returns HTTP 404 (not 403, to avoid info leak)
**Depends on:** 5.6-T04, 5.6-T09

### 5.6-T11 — Enqueue payment.cancelled webhook event after successful cancellation  _(35 min)_
**Context:** WBS 5.6 Same-day cancellation. On successful cancel, GMEPay+ must deliver a payment.cancelled webhook to the partner's configured webhook_url. Event schema: {event_id, event_type:'payment.cancelled', created_at, partner_id, data:{payment_id, partner_txn_ref, reason, prefund_returned_usd (OVERSEAS only), cancelled_at}}. Webhook delivery is at-least-once with exponential backoff (1s, 2s, 4s, max 30s, 5 attempts). The webhook is enqueued (not sent synchronously) after the cancel DB transaction commits. Use the existing WebhookOutbox table (or equivalent outbox pattern).
**Steps:** In CancelPaymentUseCase, after the @Transactional commit completes, call WebhookOutboxService.enqueue(partnerId, 'payment.cancelled', payload); Build the payload from CancelPaymentResponse: include payment_id, partner_txn_ref, reason, cancelled_at; include prefund_returned_usd only for OVERSEAS (non-null value); Ensure the enqueue call is OUTSIDE the cancel @Transactional block so a webhook enqueue failure does not roll back the cancel; Add event_type='payment.cancelled' to the WebhookEventType enum if not already present; Confirm WebhookOutbox delivery worker handles retries per the existing retry policy
**Deliverable:** payment.cancelled event enqueued via WebhookOutboxService in CancelPaymentUseCase
**Acceptance / logic checks:**
- After successful cancel, one row with event_type=payment.cancelled is present in the webhook_outbox table
- Payload includes partner_txn_ref and cancelled_at
- OVERSEAS payment: payload includes prefund_returned_usd as decimal string e.g. '35.77'
- LOCAL payment: prefund_returned_usd is absent from payload
- Webhook enqueue failure does not roll back the payment CANCELLED status update
**Depends on:** 5.6-T09

### 5.6-T12 — Record CANCEL_INITIATED and CANCEL_CONFIRMED audit events on payment event trail  _(30 min)_
**Context:** WBS 5.6 Same-day cancellation. Every payment has an 8-step event trail stored in payment_events (or equivalent). Cancellation must append two events: (1) CANCEL_INITIATED — recorded when cancel request is accepted (before scheme call), (2) CANCEL_CONFIRMED — recorded after status is set to CANCELLED. Fields per event row: payment_id, event_type, actor (partner_id for API cancel), timestamp (UTC), metadata (JSON: reason, cancel_reason_detail if present). These events are immutable once written.
**Steps:** Identify the payment_events table and its insert method in the existing codebase; In CancelPaymentUseCase, before calling the scheme adapter, insert event CANCEL_INITIATED with actor=partnerId and metadata={reason: request.reason}; After the @Transactional commit, insert event CANCEL_CONFIRMED with actor=partnerId and metadata={cancelled_at, prefund_returned_usd}; Ensure both inserts use the same payment_id UUID; Do not delete or update existing payment events — append only
**Deliverable:** CancelPaymentUseCase updated to write CANCEL_INITIATED and CANCEL_CONFIRMED events to the payment_events table
**Acceptance / logic checks:**
- After a successful cancel, payment_events for the payment contains exactly one CANCEL_INITIATED and one CANCEL_CONFIRMED row
- CANCEL_INITIATED timestamp is earlier than CANCEL_CONFIRMED timestamp
- actor field on both events equals the calling partner_id
- metadata on CANCEL_INITIATED includes the reason value from the request
- Attempting to DELETE a payment_event row via direct SQL returns success (no DB-level guard needed here), but no application code path ever deletes events
**Depends on:** 5.6-T09

### 5.6-T13 — Unit tests: SameDayWindowService boundary cases  _(30 min)_
**Context:** WBS 5.6 Same-day cancellation. SameDayWindowService.isWithinSameDayWindow(Instant createdAt) uses a Clock bean and compares calendar dates in Asia/Seoul (UTC+9). Critical boundaries: midnight KST transitions (KST midnight = 15:00 UTC previous day), and the transition from one day to the next.
**Steps:** Create src/test/java/com/gmepayplus/cancel/SameDayWindowServiceTest.java; Use a fixed Clock for each test case; Test 1: createdAt=2026-06-04T14:59:59Z, clock=2026-06-04T14:59:59Z; KST dates both 2026-06-04 23:59:59 -> true; Test 2: createdAt=2026-06-04T14:59:59Z, clock=2026-06-04T15:00:00Z; createdAt KST=2026-06-04, clock KST=2026-06-05 00:00 -> false (day boundary crossed); Test 3: createdAt=2026-06-03T15:00:00Z (KST 2026-06-04 00:00), clock=2026-06-04T14:59:59Z (KST 2026-06-04 23:59:59) -> true (same KST day); Test 4: createdAt=2026-06-04T00:00:00Z (KST 2026-06-04 09:00), clock=2026-06-05T00:00:00Z (KST 2026-06-05 09:00) -> false; Test 5: createdAt=now (same instant as clock) -> true
**Deliverable:** SameDayWindowServiceTest.java with 5 parameterized or individual test cases, all green
**Acceptance / logic checks:**
- All 5 test cases pass
- Test 2 specifically validates the KST midnight boundary at 15:00 UTC
- No test uses System.currentTimeMillis() — all use fixed Clock
- Test class has zero Spring context load (plain unit test, fast)
- Coverage of SameDayWindowService.isWithinSameDayWindow reaches 100%
**Depends on:** 5.6-T05

### 5.6-T14 — Unit tests: CancelEligibilityService all status and window combinations  _(30 min)_
**Context:** WBS 5.6 Same-day cancellation. CancelEligibilityService.check(Payment) returns ELIGIBLE, ALREADY_TERMINAL, or CANCEL_NOT_PERMITTED. Cancellable statuses: APPROVED, PENDING. Terminal statuses: FAILED, CANCELLED, REFUNDED. Window check delegates to SameDayWindowService (mock it). Edge case: status=APPROVED but window closed -> CANCEL_NOT_PERMITTED.
**Steps:** Create src/test/java/com/gmepayplus/cancel/CancelEligibilityServiceTest.java; Mock SameDayWindowService; Test APPROVED + window open -> ELIGIBLE; Test APPROVED + window closed -> CANCEL_NOT_PERMITTED; Test PENDING + window open -> ELIGIBLE; Test CANCELLED (any window) -> ALREADY_TERMINAL; Test FAILED (any window) -> ALREADY_TERMINAL; Test REFUNDED (any window) -> ALREADY_TERMINAL
**Deliverable:** CancelEligibilityServiceTest.java with 7 test cases, all green
**Acceptance / logic checks:**
- All 7 test cases pass
- ALREADY_TERMINAL result never calls SameDayWindowService (verify mock is not invoked for terminal statuses)
- CANCEL_NOT_PERMITTED is returned only when status is valid but window is closed — not for terminal statuses
- Test class loads no Spring context
- All enum values of CancelEligibilityResult appear in at least one test assertion
**Depends on:** 5.6-T06, 5.6-T13

### 5.6-T15 — Unit tests: CancelPaymentUseCase orchestration — happy path and error paths  _(45 min)_
**Context:** WBS 5.6 Same-day cancellation. CancelPaymentUseCase.execute() orchestrates eligibility check, scheme cancel, prefund reversal, and status update. Mock all collaborators. Key vectors: (A) OVERSEAS APPROVED same-day payment — full happy path; (B) LOCAL PENDING same-day — no scheme call, no prefund reversal; (C) wrong partner_id — PaymentNotFoundException; (D) CANCELLED payment — 409-throwing exception; (E) APPROVED but next-day KST — CANCEL_NOT_PERMITTED exception.
**Steps:** Create src/test/java/com/gmepayplus/cancel/CancelPaymentUseCaseTest.java; Mock PaymentRepository, CancelEligibilityService, ZeroPayCancelAdapter, PrefundingReversalService; Vector A: eligibility=ELIGIBLE, partnerType=OVERSEAS, schemeTxnRef='ZP123' -> verifySchemeCancel called, reversal called, status=CANCELLED, prefund_reversal_usd=35.77; Vector B: eligibility=ELIGIBLE, partnerType=LOCAL, schemeTxnRef=null -> verifySchemeCancel NOT called, reversal returns 0, prefund_reversal_usd=null in response; Vector C: PaymentRepository returns empty for payment not belonging to partner -> PaymentNotFoundException thrown, no other service called; Vector D: eligibility=ALREADY_TERMINAL -> appropriate exception thrown before any scheme call; Vector E: eligibility=CANCEL_NOT_PERMITTED -> CancelNotPermittedException thrown before scheme call
**Deliverable:** CancelPaymentUseCaseTest.java with 5 test vectors, all green
**Acceptance / logic checks:**
- Vector A: ZeroPayCancelAdapter.cancelPayment() is called with correct scheme_txn_ref
- Vector B: ZeroPayCancelAdapter.cancelPayment() is never called (Mockito verify(adapter, never()))
- Vector C: PaymentRepository is called once; no downstream services called
- Vector D and E: Exception type matches the mapped HTTP status (409 and 400 respectively)
- All mocks are verified — no unexpected interactions
**Depends on:** 5.6-T09

### 5.6-T16 — Unit tests: PrefundingReversalService — OVERSEAS balance restore and LOCAL skip  _(40 min)_
**Context:** WBS 5.6 Same-day cancellation. PrefundingReversalService.reverse() must atomically restore collection_usd to OVERSEAS partner balance and insert a CANCEL_REVERSAL ledger row. For LOCAL partners it must be a no-op returning BigDecimal.ZERO.
**Steps:** Create src/test/java/com/gmepayplus/cancel/PrefundingReversalServiceTest.java; Use an in-memory or H2 test DB with the prefunding_balances and prefunding_ledger tables; Test 1: OVERSEAS partner, initial balance 100.00 USD, collectionUsd=35.77 -> after reverse(), balance=135.77, ledger row type=CANCEL_REVERSAL, amount=35.77; Test 2: LOCAL partner, any balance -> reverse() returns 0.00, no ledger row inserted, balance unchanged; Test 3: OVERSEAS partner, verify the SELECT FOR UPDATE is used (check @Transactional and that a second concurrent call cannot read an intermediate state — simulate with two threads if feasible, or verify annotation is present)
**Deliverable:** PrefundingReversalServiceTest.java with 3 test cases, all green
**Acceptance / logic checks:**
- Test 1: balance after reversal equals 100.00 + 35.77 = 135.77 exactly (BigDecimal comparison, no floating point)
- Test 1: ledger row exists with type=CANCEL_REVERSAL, amount_usd=35.77, payment_id correct
- Test 2: no ledger row is inserted for LOCAL partner
- Test 2: return value is exactly BigDecimal.ZERO
- @Transactional annotation is present on the reverse() method
**Depends on:** 5.6-T07

### 5.6-T17 — Integration test: full cancel flow — OVERSEAS approved payment  _(55 min)_
**Context:** WBS 5.6 Same-day cancellation. End-to-end integration test in a Spring Boot test slice with a real H2/PostgreSQL test DB and a WireMock stub for the ZeroPay cancel API. Scenario RF-001 from the test spec: POST /v1/payments/{id}/cancel for an OVERSEAS partner same-day APPROVED payment. Expected: HTTP 200, status=CANCELLED in DB, prefunding balance restored, webhook_outbox row inserted, payment_events has CANCEL_CONFIRMED.
**Steps:** Create src/test/java/com/gmepayplus/cancel/CancelPaymentIntegrationTest.java with @SpringBootTest; Seed DB: OVERSEAS partner, prefunding balance=100.00, approved payment with collection_usd=35.77, created_at=today UTC, status=APPROVED; Stub WireMock for ZeroPay cancel endpoint returning 200; POST /v1/payments/{payment_id}/cancel with body {reason:'customer_request'} and valid Idempotency-Key header; Assert HTTP 200 and response body: status='cancelled', cancelled_at not null, prefund_returned_usd='35.77'; Assert DB: payment.status=CANCELLED, payment.cancelled_at set, payment.prefund_reversal_usd=35.77; Assert prefunding_balances.balance_usd=135.77; Assert webhook_outbox has one row with event_type=payment.cancelled; Assert payment_events has CANCEL_INITIATED and CANCEL_CONFIRMED rows
**Deliverable:** CancelPaymentIntegrationTest.java — integration test covering RF-001, all assertions green
**Acceptance / logic checks:**
- HTTP 200 response with correct JSON body
- DB payment.status=CANCELLED and prefund_reversal_usd=35.77 after call
- Prefunding balance increased from 100.00 to 135.77
- WireMock verifies ZeroPay cancel endpoint was called exactly once
- payment_events table has both CANCEL_INITIATED and CANCEL_CONFIRMED rows for the payment
**Depends on:** 5.6-T10, 5.6-T11, 5.6-T12

### 5.6-T18 — Integration test: cancel rejected — payment from wrong partner returns 404  _(30 min)_
**Context:** WBS 5.6 Same-day cancellation. Security invariant: a partner must not be able to cancel another partner's payment. POST /v1/payments/{id}/cancel where the payment belongs to partner B but the caller authenticates as partner A must return 404 (not 403, to avoid leaking existence). No DB mutation should occur.
**Steps:** Extend or create CancelPaymentSecurityTest.java; Seed DB: payment owned by partner_id=prt_B in APPROVED status, created today; Authenticate as partner_id=prt_A; POST /v1/payments/{payment_id}/cancel; Assert HTTP 404 with error code PAYMENT_NOT_FOUND; Assert payment status in DB is still APPROVED; Assert no prefunding change for either partner; Assert no webhook_outbox row inserted
**Deliverable:** Test method in CancelPaymentSecurityTest.java (or appended to CancelPaymentIntegrationTest.java) confirming cross-partner isolation
**Acceptance / logic checks:**
- Response is HTTP 404 (not 403)
- Error code in response body is PAYMENT_NOT_FOUND
- payment.status remains APPROVED in DB after the rejected call
- No CANCEL_INITIATED event row in payment_events
- No prefunding ledger entry created
**Depends on:** 5.6-T17

### 5.6-T19 — Integration test: cancel rejected — CANCEL_NOT_PERMITTED when window closed (next-day KST)  _(35 min)_
**Context:** WBS 5.6 Same-day cancellation. Scenario RF-004 from the test spec: attempting to cancel a payment whose created_at is a prior KST calendar day must return HTTP 400 with error code CANCEL_NOT_PERMITTED. Use a fixed Clock bean in the test context to simulate current time being the next KST day.
**Steps:** Create or extend test class with a @TestConfiguration providing a fixed Clock set to 2026-06-05T15:00:00Z (KST 2026-06-06 00:00); Seed DB: OVERSEAS partner, APPROVED payment with created_at=2026-06-04T10:00:00Z (KST 2026-06-04 19:00 — prior day); POST /v1/payments/{payment_id}/cancel with valid headers; Assert HTTP 400; Assert error code is CANCEL_NOT_PERMITTED; Assert payment status is still APPROVED in DB; Assert no ZeroPay API call was made (WireMock verify 0 calls); Assert no prefunding change
**Deliverable:** Test method confirming window-closed rejection with a fixed Clock, all assertions green
**Acceptance / logic checks:**
- HTTP 400 returned
- Error code CANCEL_NOT_PERMITTED in response body
- Payment DB status unchanged (APPROVED)
- ZeroPay WireMock receives zero cancel requests
- Prefunding balance unchanged
**Depends on:** 5.6-T17

### 5.6-T20 — Integration test: idempotency — duplicate cancel request returns same response  _(35 min)_
**Context:** WBS 5.6 Same-day cancellation. The Idempotency-Key cache for POST /v1/payments/{id}/cancel stores (partner_id, idempotency_key, response_body, http_status) for 24 hours. A duplicate request with the same Idempotency-Key must return the original 200 response without re-executing the use case. Verify the use case is invoked only once (not twice) by checking that the ZeroPay cancel stub is called exactly once.
**Steps:** Seed DB: APPROVED OVERSEAS payment, created today, prefunding balance 100.00; POST /v1/payments/{id}/cancel with Idempotency-Key: idem-key-001 -> assert HTTP 200; POST /v1/payments/{id}/cancel again with same Idempotency-Key: idem-key-001 -> assert HTTP 200 with identical response body; Assert prefunding balance is 135.77 (not 171.54 — reversal not applied twice); Assert ZeroPay WireMock cancel endpoint called exactly once (not twice); Assert only one CANCEL_CONFIRMED row in payment_events
**Deliverable:** Test method confirming idempotent cancel duplicate handling, all assertions green
**Acceptance / logic checks:**
- Both responses return HTTP 200 with identical JSON body
- Prefunding balance is exactly 135.77 after both calls (not double-reversed)
- ZeroPay cancel API called exactly once (WireMock.verify(1, ...))
- payment_events has exactly one CANCEL_INITIATED row and one CANCEL_CONFIRMED row
- Second POST returns X-Idempotency-Status header or equivalent indicating cache hit
**Depends on:** 5.6-T17

### 5.6-T21 — Integration test: cancel of already-cancelled payment returns 409  _(25 min)_
**Context:** WBS 5.6 Same-day cancellation. A payment already in CANCELLED status is a terminal state. POST /v1/payments/{id}/cancel on such a payment must return HTTP 409. This ensures partners retrying after a successful cancel receive a clear signal rather than a 500 or a double-cancel.
**Steps:** Seed DB: payment in CANCELLED status, created today; POST /v1/payments/{id}/cancel; Assert HTTP 409; Assert error code maps to PAYMENT_ALREADY_CANCELLED or similar (confirm the exact code from the error catalog); Assert no ZeroPay API call; Assert no prefunding change; Assert no new payment_events rows for this payment
**Deliverable:** Test method confirming 409 on already-cancelled payment
**Acceptance / logic checks:**
- HTTP 409 returned
- Error response body contains a machine-readable error code
- No ZeroPay WireMock requests recorded
- No new payment_events rows added beyond the existing CANCEL_CONFIRMED
- No prefunding ledger entry added
**Depends on:** 5.6-T17

### 5.6-T22 — Integration test: LOCAL partner cancel — no prefunding reversal, no prefund_returned_usd in response  _(30 min)_
**Context:** WBS 5.6 Same-day cancellation. LOCAL partners (e.g. GME Remit, partner_type=LOCAL) have no prefunding. Cancelling a LOCAL partner payment must: set status=CANCELLED, skip any prefunding call, and omit prefund_returned_usd from the response body (field must be absent, not null).
**Steps:** Seed DB: LOCAL partner (partner_type=LOCAL), APPROVED payment created today, no prefunding_balances row; POST /v1/payments/{id}/cancel with body {reason:'merchant_request'}; Assert HTTP 200; Assert response JSON does not contain prefund_returned_usd key; Assert payment.status=CANCELLED in DB; Assert payment.prefund_reversal_usd is NULL in DB; Assert no prefunding_ledger row inserted
**Deliverable:** Test method confirming LOCAL partner cancel behaviour, all assertions green
**Acceptance / logic checks:**
- HTTP 200 returned
- prefund_returned_usd is absent from the JSON response (not present even as null)
- payment.prefund_reversal_usd is NULL in DB
- No prefunding_ledger row with type=CANCEL_REVERSAL exists for this payment
- payment.status=CANCELLED in DB
**Depends on:** 5.6-T17

### 5.6-T23 — OpenAPI spec: add /v1/payments/{id}/cancel path to partner-api.yaml  _(40 min)_
**Context:** WBS 5.6 Same-day cancellation. The ground truth API contract is openapi/partner-api.yaml (API-05). The cancel endpoint must be declared: POST /v1/payments/{id}/cancel with CancelRequest schema (reason enum, reason_detail), 200 response (payment_id, status: cancelled, cancelled_at, prefund_returned_usd), 400/404/409 responses referencing shared error schemas, and required Idempotency-Key header. The CancelRequest and cancel 200 response should be named schemas under #/components/schemas.
**Steps:** Open openapi/partner-api.yaml; Under paths, add /v1/payments/{id}/cancel with POST operation operationId: cancelPayment; Add parameters: path param id, $ref to shared IdempotencyKey, XTimestamp, XSignature headers; Add requestBody referencing CancelRequest schema; Add CancelRequest schema under #/components/schemas: reason (enum string, required), reason_detail (string, maxLength 200, nullable); Add 200 response schema with required fields payment_id, status (const: cancelled), cancelled_at; optional prefund_returned_usd; Reference existing 400/404/409 response schemas; Run a YAML lint / openapi-generator validate to confirm schema is valid
**Deliverable:** openapi/partner-api.yaml updated with cancelPayment operation and CancelRequest schema, passing openapi-generator validate
**Acceptance / logic checks:**
- openapi-generator validate --input-spec openapi/partner-api.yaml exits 0
- CancelRequest.reason is declared as enum with exactly 4 values: customer_request, merchant_request, timeout, other
- 200 response schema declares prefund_returned_usd as optional (not required)
- Path /v1/payments/{id}/cancel POST is present and passes contract tests in CI
- All $ref references in the new section resolve correctly (no dangling refs)
**Depends on:** 5.6-T04

### 5.6-T24 — Contract test: verify /v1/payments/{id}/cancel against openapi/partner-api.yaml  _(45 min)_
**Context:** WBS 5.6 Same-day cancellation. All endpoints in openapi/partner-api.yaml must have contract test coverage. Using the existing contract-test framework (Dredd, schemathesis, or Pact as configured in the project), add test coverage for POST /v1/payments/{id}/cancel: happy path (same-day -> 200), post-settlement (422/400), and missing idempotency key (400). Contract tests run against a running test server with seeded data.
**Steps:** Locate the existing contract test configuration file (dredd.yml, schemathesis config, or equivalent); Add a test transaction or hook for the cancel happy-path: APPROVED payment same-day -> assert 200 response matches schema; Add a test for CANCEL_NOT_PERMITTED: prior-day payment -> assert 400 with CANCEL_NOT_PERMITTED error code; Add a test for missing Idempotency-Key -> assert 400 with MISSING_IDEMPOTENCY_KEY error code; Add a test for already-CANCELLED payment -> assert 409; Run the contract test suite and confirm all 4 new cases pass
**Deliverable:** Contract test coverage for cancelPayment operation added to the existing test suite, 4 test cases green
**Acceptance / logic checks:**
- Same-day cancel returns 200 and response body matches the CancelResponse schema in openapi/partner-api.yaml
- CANCEL_NOT_PERMITTED case returns 400 with error code CANCEL_NOT_PERMITTED
- Missing Idempotency-Key returns 400 with code MISSING_IDEMPOTENCY_KEY
- Already-cancelled returns 409
- All 4 new contract tests run in CI without requiring manual intervention
**Depends on:** 5.6-T23, 5.6-T10

### 5.6-T25 — Add cancel fields to transaction detail view in Admin System (read-only display)  _(45 min)_
**Context:** WBS 5.6 Same-day cancellation. The Admin System transaction detail view (PRD-07 section 8.5) must show cancellation metadata for CANCELLED transactions: Cancellation Reason, Cancellation Reason Detail (if present), Cancelled At (UTC and KST), Prefund Returned USD (OVERSEAS only). These fields are read-only. Non-cancelled transactions show these fields as blank/absent. The data is fetched from the existing transaction detail API endpoint — update that endpoint to include cancel fields.
**Steps:** Add cancel fields to the GET /v1/admin/transactions/{id} response DTO: cancel_reason, cancel_reason_detail, cancelled_at, prefund_reversal_usd; Map the new DB columns (from T01/T02) to the DTO in the transaction query service; In the Admin frontend transaction detail component, add a Cancellation section visible only when status=CANCELLED; Display: Reason (formatted enum label), Detail, Cancelled At (UTC + KST display), Prefund Returned USD (omit row for LOCAL partners); Confirm no cancel fields appear on the detail view for APPROVED or FAILED transactions
**Deliverable:** Updated transaction detail API response DTO and Admin frontend component showing cancel metadata for CANCELLED payments
**Acceptance / logic checks:**
- GET /v1/admin/transactions/{id} for a CANCELLED OVERSEAS payment returns cancel_reason, cancelled_at, prefund_reversal_usd in response
- GET /v1/admin/transactions/{id} for an APPROVED payment returns null/absent cancel_reason and cancelled_at
- Admin UI transaction detail for a CANCELLED payment shows Cancellation section with formatted reason label and KST timestamp
- Admin UI transaction detail for an APPROVED payment has no Cancellation section visible
- OVERSEAS CANCELLED transaction shows Prefund Returned USD row; LOCAL CANCELLED transaction does not
**Depends on:** 5.6-T01, 5.6-T02

### 5.6-T26 — Document cancel flow in partner-facing API reference (inline OpenAPI descriptions)  _(30 min)_
**Context:** WBS 5.6 Same-day cancellation. The OpenAPI spec is the primary partner documentation. Ensure all inline descriptions in the cancel operation are complete and accurate: operation summary and description must state the same-day KST restriction, the admin-only refund note (Phase 1), and the idempotency requirement. The CancelRequest.reason enum values must have individual descriptions. Error code CANCEL_NOT_PERMITTED must appear in the error catalog section.
**Steps:** In openapi/partner-api.yaml, expand the description of the cancelPayment operation to include: same calendar day KST restriction, Phase 1 note (refunds are admin-only), idempotency requirement; Add x-enum-descriptions or description on each enum value of CancelRequest.reason: customer_request, merchant_request, timeout, other; Verify CANCEL_NOT_PERMITTED is listed in the error codes section of the spec with HTTP 400 and retryable=false; Run openapi-generator validate to confirm no schema breaks; Review rendered Swagger/Redoc output to confirm descriptions appear correctly for the cancel endpoint
**Deliverable:** openapi/partner-api.yaml cancel operation with complete inline documentation and CANCEL_NOT_PERMITTED in error catalog
**Acceptance / logic checks:**
- cancelPayment operation description mentions same calendar day KST and admin-only refunds
- Each of the 4 reason enum values has a non-empty description in the spec
- CANCEL_NOT_PERMITTED appears in the error code table with HTTP 400 and retryable=false
- openapi-generator validate exits 0 after edits
- Rendered spec (e.g. Swagger UI) shows the cancel endpoint with full description visible
**Depends on:** 5.6-T23


## WBS 5.8 — Direction routing & extensibility
### 5.8-T01 — Add direction CHECK constraint and index to rule table migration  _(25 min)_
**Context:** The rule table (DAT-03 §4.6) has a direction column VARCHAR(10) with permitted values INBOUND, OUTBOUND, DOMESTIC, HUB. It is part of the UNIQUE key (partner_id, scheme_id, direction). The direction field determines fee tier and BOK reporting fields. A missing or wrong CHECK constraint lets invalid directions reach the rate engine. Primary key is (partner_id, scheme_id, direction); all four directions must be insertable from day one even though only DOMESTIC and INBOUND are activated in Phase 1.
**Steps:** Write a Flyway migration: ALTER TABLE rule ADD CONSTRAINT chk_rule_direction CHECK (direction IN ('INBOUND','OUTBOUND','DOMESTIC','HUB')); Verify the UNIQUE constraint (partner_id, scheme_id, direction) exists; add it if absent; Add a covering index on (partner_id, scheme_id, direction) to support O(1) rule lookup; Ensure is_same_ccy_shortcircuit BOOLEAN column exists (computed flag; TRUE when collection_ccy = settle_a_ccy = settle_b_ccy = payout_ccy) and status column has CHECK IN ('DRAFT','ACTIVE','SUSPENDED'); Apply migration against a local dev DB; confirm all four direction values insert cleanly and a fifth value 'BILATERAL' is rejected at the DB level
**Deliverable:** Flyway migration file V5_8_001__rule_direction_constraint_index.sql
**Acceptance / logic checks:**
- INSERT with direction='DOMESTIC' succeeds; direction='OUTBOUND' succeeds; direction='HUB' succeeds; direction='INBOUND' succeeds
- INSERT with direction='BILATERAL' raises a CHECK constraint violation
- The UNIQUE constraint rejects a second row with the same (partner_id, scheme_id, direction) triplet
- Index on (partner_id, scheme_id, direction) appears in pg_indexes for the rule table
- migration is idempotent: re-running in a transaction that is then rolled back leaves the schema unchanged

### 5.8-T02 — Define Direction enum and RuleKey value object in domain model  _(25 min)_
**Context:** GMEPay+ routes every payment via a rule identified by the composite key (partner_id, scheme_id, direction). Direction is one of INBOUND (overseas partner -> Korean merchant, Phase 1), OUTBOUND (Korean partner -> foreign merchant, Phase 2), DOMESTIC (same-country, same-currency, short-circuit), HUB (overseas->overseas, Phase 2+). A RuleKey value object is needed by the Smart Router and Rate Engine to look up rules without passing three separate parameters. All four directions must be representable in code from day one.
**Steps:** Create enum Direction { INBOUND, OUTBOUND, DOMESTIC, HUB } with a fromString(String) factory that throws IllegalArgumentException on unknown values; Create immutable value class RuleKey(long partnerId, long schemeId, Direction direction) with equals/hashCode based on all three fields; Annotate Direction with @JsonValue / @JsonCreator for clean serialisation to/from lowercase API strings (domestic, inbound, outbound, hub); Add a boolean isCrossBorder() helper on Direction: returns false only for DOMESTIC, true for all others; Write unit tests: Direction.fromString for all four values and one unknown; RuleKey equality and hashCode; JSON round-trip for each direction value
**Deliverable:** Direction.java enum, RuleKey.java value class, DirectionTest.java, RuleKeyTest.java
**Acceptance / logic checks:**
- Direction.fromString("INBOUND") == Direction.INBOUND; fromString("inbound") is case-insensitive or documented as case-sensitive
- Direction.fromString("BILATERAL") throws IllegalArgumentException
- RuleKey(1,2,DOMESTIC).equals(RuleKey(1,2,DOMESTIC)) is true; RuleKey(1,2,DOMESTIC).equals(RuleKey(1,2,INBOUND)) is false
- Direction.DOMESTIC.isCrossBorder() == false; Direction.INBOUND.isCrossBorder() == true; Direction.HUB.isCrossBorder() == true
- JSON serialisation of Direction.INBOUND produces the string 'INBOUND' and deserialises back correctly
**Depends on:** 5.8-T01

### 5.8-T03 — Implement RuleRepository.findByKey() with caching for rule lookup  _(40 min)_
**Context:** The Smart Router and Rate Engine call fetchRule(partner_id, scheme_id, direction) to load the full rule record including margins m_a, m_b, service_charge_amount, is_same_ccy_shortcircuit, rate_coll_source, rate_pay_source, and status. The UNIQUE key (partner_id, scheme_id, direction) guarantees at most one row. Cache in Redis with key rule:{partnerId}:{schemeId}:{direction} for fast O(1) lookup; TTL 300 s. On cache miss, query DB and populate cache. If status = DRAFT or SUSPENDED, treat as not found (return empty). Only ACTIVE rules are routable.
**Steps:** Add RuleRepository.findByKey(RuleKey key): query rule table WHERE partner_id=:p AND scheme_id=:s AND direction=:d AND status='ACTIVE'; Wrap with a Redis cache: on hit return cached Rule; on miss query DB, store result with TTL 300 s using key pattern rule:{partnerId}:{schemeId}:{direction}; Return Optional<Rule>; empty if no ACTIVE row; Expose RuleRepository.evictCache(RuleKey key) called by the Admin Service whenever a rule is saved/activated/suspended; Write unit tests: active rule returned; draft rule returns empty; suspended rule returns empty; cache hit does not query DB (verify with mock); cache eviction causes next call to re-query
**Deliverable:** RuleRepository.java with findByKey() and evictCache(), RuleRepositoryTest.java
**Acceptance / logic checks:**
- findByKey for an ACTIVE rule returns a populated Rule with correct partner_id, scheme_id, direction, m_a, m_b fields
- findByKey for a DRAFT rule returns Optional.empty()
- findByKey for a SUSPENDED rule returns Optional.empty()
- Second call with same RuleKey hits Redis cache and does not execute a SQL query (verified via query count mock)
- After evictCache(key), the next findByKey re-queries the database (cache miss)
**Depends on:** 5.8-T02

### 5.8-T04 — Implement SmartRouter.resolveRoute() returning RouteContext for a payment request  _(45 min)_
**Context:** The Smart Router (SAD-02 §5) selects the correct SchemeAdapter given (partner_id, scheme_id, direction) and returns a RouteContext containing the matched rule, the scheme adapter handle, and the resolved direction. The router must: (1) verify the rule exists and is ACTIVE, (2) verify the partner is ACTIVE, (3) verify the scheme is ACTIVE, (4) verify the requested direction is permitted by the feature-flag table (FEATURE_OUTBOUND_PAYMENTS=false blocks OUTBOUND; OUTBOUND and HUB are scaffolded but not live in Phase 1). Error code DIRECTION_NOT_ENABLED (HTTP 422) is returned when the direction is not enabled.
**Steps:** Create RouteContext record: rule (Rule), schemeAdapterRef (String scheme_id), direction (Direction), partnerId (long), schemeId (long); In SmartRouter.resolveRoute(long partnerId, long schemeId, Direction direction): call RuleRepository.findByKey(RuleKey); if empty throw DirectionNotEnabledException -> DIRECTION_NOT_ENABLED (HTTP 422); Check PartnerRepository.isActive(partnerId); if false throw PartnerInactiveException -> PARTNER_INACTIVE (HTTP 422); Check SchemeRepository.isActive(schemeId); if false throw SchemeUnavailableException -> SCHEME_UNAVAILABLE (HTTP 422); Check FeatureFlagService.isDirectionAllowed(direction): OUTBOUND and HUB return false unless FEATURE_OUTBOUND_PAYMENTS=true; if blocked throw DirectionNotEnabledException; Return RouteContext with resolved rule and adapter reference
**Deliverable:** SmartRouter.java, RouteContext.java, SmartRouterTest.java
**Acceptance / logic checks:**
- resolveRoute(partnerId=1, schemeId=1, DOMESTIC) with active partner+scheme and ACTIVE rule returns RouteContext with correct rule
- resolveRoute with no matching ACTIVE rule throws DirectionNotEnabledException with code DIRECTION_NOT_ENABLED
- resolveRoute with partner status=SUSPENDED throws PartnerInactiveException
- resolveRoute with direction=OUTBOUND and FEATURE_OUTBOUND_PAYMENTS=false throws DirectionNotEnabledException regardless of whether a rule exists
- resolveRoute with direction=HUB and FEATURE_OUTBOUND_PAYMENTS=false throws DirectionNotEnabledException
**Depends on:** 5.8-T03

### 5.8-T05 — Implement FeatureFlagService with FEATURE_OUTBOUND_PAYMENTS and FEATURE_MULTI_SCHEME_ROUTING flags  _(30 min)_
**Context:** SAD-02 §5.2 defines feature flags stored in environment variables or a lightweight config table, loaded at service startup. FEATURE_OUTBOUND_PAYMENTS (default false) gates OUTBOUND and HUB directions. FEATURE_MULTI_SCHEME_ROUTING (default false) gates routing across multiple active schemes. In Phase 1 both are false: only DOMESTIC and INBOUND are live. The service must read flags at startup and expose isDirectionAllowed(Direction) and isMultiSchemeRoutingEnabled() without hard-coded direction names in caller code.
**Steps:** Create FeatureFlags configuration class reading FEATURE_OUTBOUND_PAYMENTS (boolean, default false) and FEATURE_MULTI_SCHEME_ROUTING (boolean, default false) from environment or application.properties; Create FeatureFlagService.isDirectionAllowed(Direction d): return true for DOMESTIC and INBOUND always; for OUTBOUND and HUB return the value of FEATURE_OUTBOUND_PAYMENTS; Add isMultiSchemeRoutingEnabled(): returns FEATURE_MULTI_SCHEME_ROUTING value; Write unit tests with both flag values for each direction; Document that changing a flag requires a config update and service restart (add a comment in the class)
**Deliverable:** FeatureFlags.java config class, FeatureFlagService.java, FeatureFlagServiceTest.java
**Acceptance / logic checks:**
- With FEATURE_OUTBOUND_PAYMENTS=false: DOMESTIC=true, INBOUND=true, OUTBOUND=false, HUB=false
- With FEATURE_OUTBOUND_PAYMENTS=true: all four directions return true
- isMultiSchemeRoutingEnabled() returns false when FEATURE_MULTI_SCHEME_ROUTING is absent from env
- FeatureFlagService has no hard-coded string comparisons; uses Direction enum only
- Unit tests cover all four Direction values under both flag states (8 assertions minimum)
**Depends on:** 5.8-T02

### 5.8-T06 — Implement DOMESTIC direction routing: same-currency short-circuit detection in rule resolution  _(35 min)_
**Context:** When direction=DOMESTIC the rule's is_same_ccy_shortcircuit=true (collection_ccy = settle_a_ccy = settle_b_ccy = payout_ccy). The Smart Router must detect this flag and set a shortCircuit=true field on RouteContext so the Rate Engine skips the USD pool entirely. Example: GME Remit on ZeroPay, all KRW. For DOMESTIC rules m_a and m_b must both be 0. If is_same_ccy_shortcircuit=false on a DOMESTIC direction rule, that is a data integrity error and must be logged as an ops alert (should not happen but must be caught).
**Steps:** Add boolean shortCircuit field to RouteContext record; In SmartRouter.resolveRoute(), after fetching the rule, if direction == DOMESTIC check rule.isSameCcyShortcircuit(); If true, set routeContext.shortCircuit = true; If false (data integrity violation): log ERROR with alert tag, throw RoutingIntegrityException -> HTTP 500 INTERNAL_ERROR; Also validate m_a == 0 and m_b == 0 on a short-circuit rule; if nonzero log ERROR and throw RoutingIntegrityException; Write unit test scenarios for all branches
**Deliverable:** Updated SmartRouter.java with short-circuit detection, updated RouteContext.java, SmartRouterDomesticTest.java
**Acceptance / logic checks:**
- resolveRoute with DOMESTIC rule where is_same_ccy_shortcircuit=true returns RouteContext with shortCircuit=true
- resolveRoute with DOMESTIC rule where is_same_ccy_shortcircuit=false throws RoutingIntegrityException
- resolveRoute with DOMESTIC rule where m_a=0.01 and is_same_ccy_shortcircuit=true throws RoutingIntegrityException (m_a must be 0)
- resolveRoute with INBOUND rule where is_same_ccy_shortcircuit=false returns RouteContext with shortCircuit=false (normal path)
- No hard-coded currency string KRW in short-circuit detection logic; purely uses rule.isSameCcyShortcircuit() flag
**Depends on:** 5.8-T04

### 5.8-T07 — Implement INBOUND direction routing: cross-border validation and prefunding requirement flag  _(35 min)_
**Context:** Direction=INBOUND means an overseas partner customer pays at a domestic (Korean) merchant. The partner type must be OVERSEAS (prefunding required). The rule is cross-border (is_same_ccy_shortcircuit=false) and must have m_a + m_b >= 2.0% (e.g. SendMN: m_a=0.01, m_b=0.01 => 2.0%). If the resolved rule has m_a + m_b < 0.02 on an INBOUND rule, reject with a RoutingIntegrityException (data error). The RouteContext must carry requiresPrefunding=true for INBOUND so the Transaction Orchestrator knows to call PrefundingService.
**Steps:** Add boolean requiresPrefunding field to RouteContext record; In SmartRouter.resolveRoute(), when direction == INBOUND: verify partner.type == OVERSEAS; if not throw RoutingIntegrityException with message 'INBOUND direction requires OVERSEAS partner'; Verify rule.m_a + rule.m_b >= 0.02 (i.e. 2.0%); if below threshold throw RoutingIntegrityException with message 'INBOUND rule combined margin below 2%'; Set routeContext.requiresPrefunding = (partner.type == OVERSEAS); Write unit tests covering partner type mismatch, margin below 2%, and happy path
**Deliverable:** Updated SmartRouter.java with INBOUND validation, SmartRouterInboundTest.java
**Acceptance / logic checks:**
- resolveRoute INBOUND with OVERSEAS partner, m_a=0.01 m_b=0.01 (sum=0.02) returns RouteContext with requiresPrefunding=true
- resolveRoute INBOUND with LOCAL partner throws RoutingIntegrityException mentioning OVERSEAS partner requirement
- resolveRoute INBOUND with m_a=0.005 m_b=0.005 (sum=0.01 < 2%) throws RoutingIntegrityException mentioning combined margin
- resolveRoute INBOUND with m_a=0.015 m_b=0.015 (sum=0.03 > 2%) succeeds
- resolveRoute DOMESTIC with LOCAL partner returns requiresPrefunding=false
**Depends on:** 5.8-T05, 5.8-T06

### 5.8-T08 — Scaffold OUTBOUND direction handler: stub implementation returning DIRECTION_NOT_ENABLED  _(30 min)_
**Context:** OUTBOUND (domestic partner customer pays at foreign merchant) is Phase 2 scope. The architecture must support it from day one with no retrofitting (BRD-01 §9, SAD-02 A1). In Phase 1 with FEATURE_OUTBOUND_PAYMENTS=false the route resolution already rejects OUTBOUND via FeatureFlagService. This ticket adds the OUTBOUND-specific RouteContext fields and a stub OutboundDirectionHandler so that enabling the feature flag in Phase 2 requires only handler implementation, not architectural changes. No live payment logic is implemented here.
**Steps:** Create OutboundDirectionHandler.java with method RouteContext enrichOutbound(RouteContext ctx, Rule rule): throws UnsupportedOperationException('OUTBOUND handler not yet implemented') — this is the Phase 2 hook; Add OUTBOUND-specific fields to RouteContext: outboundSchemeCountryCode (String, nullable), outboundPartnerSettlementCcy (String, nullable); Add a TODO comment citing SAD-02 §5 and BRD-01 §9 explaining what Phase 2 must implement (partner B quote, foreign scheme adapter call, FX1014 BOK fields); Write a unit test asserting OutboundDirectionHandler.enrichOutbound() throws UnsupportedOperationException with a descriptive message; Verify that SmartRouter with FEATURE_OUTBOUND_PAYMENTS=false still throws DirectionNotEnabledException before OutboundDirectionHandler is ever called
**Deliverable:** OutboundDirectionHandler.java stub, updated RouteContext.java with OUTBOUND fields, OutboundDirectionHandlerTest.java
**Acceptance / logic checks:**
- OutboundDirectionHandler.enrichOutbound() throws UnsupportedOperationException with message containing 'Phase 2'
- RouteContext compiles with outboundSchemeCountryCode and outboundPartnerSettlementCcy fields present but null for non-OUTBOUND routes
- SmartRouter test: direction=OUTBOUND with FEATURE_OUTBOUND_PAYMENTS=false throws DirectionNotEnabledException before OutboundDirectionHandler is invoked (verify with mock.never())
- SmartRouter test: direction=OUTBOUND with FEATURE_OUTBOUND_PAYMENTS=true reaches OutboundDirectionHandler.enrichOutbound() (which then throws UnsupportedOperationException as expected in Phase 1)
- No production code path calls OutboundDirectionHandler in Phase 1 without the feature flag being true
**Depends on:** 5.8-T07

### 5.8-T09 — Scaffold HUB direction handler: stub with dual-IDENTITY leg detection  _(30 min)_
**Context:** HUB direction (overseas partner -> overseas merchant, both non-Korean) is Phase 2+ scope. SAD-02 A1 requires the architecture support it from day one. For HUB, both rate legs may be IDENTITY (settle_a_ccy=USD and settle_b_ccy=USD) with margins still applied normally. Example: target_payout=100 USD, cost_rate_coll=1.0 (IDENTITY), cost_rate_pay=1.0 (IDENTITY), m_a=0.01, m_b=0.01, service_charge=0.50 USD -> collection_usd=102.0408 USD, collection_amount=102.5408 USD. The stub must detect dual-IDENTITY and record this for the rate engine, but throw UnsupportedOperationException until Phase 2.
**Steps:** Create HubDirectionHandler.java with method RouteContext enrichHub(RouteContext ctx, Rule rule): if rule.rateColl==IDENTITY AND rule.ratePay==IDENTITY set ctx.hubDualIdentity=true; Add hubDualIdentity boolean field (nullable, default false) to RouteContext; Throw UnsupportedOperationException('HUB handler not yet implemented - Phase 2+') after setting the flag; Add TODO comment citing RATE-04 §7.1 dual-IDENTITY worked example for Phase 2 implementor; Write unit test: enrichHub with dual-IDENTITY rule sets hubDualIdentity=true then throws UnsupportedOperationException
**Deliverable:** HubDirectionHandler.java stub, updated RouteContext.java with hubDualIdentity, HubDirectionHandlerTest.java
**Acceptance / logic checks:**
- HubDirectionHandler.enrichHub() sets routeContext.hubDualIdentity=true when both rate sources are IDENTITY
- HubDirectionHandler.enrichHub() throws UnsupportedOperationException with message containing 'Phase 2'
- SmartRouter with FEATURE_OUTBOUND_PAYMENTS=false throws DirectionNotEnabledException for HUB before HubDirectionHandler is called
- RouteContext.hubDualIdentity defaults to false for DOMESTIC and INBOUND routes
- Unit test verifies the dual-IDENTITY numeric example from RATE-04: target_payout=100 USD, m_a=0.01, m_b=0.01 -> collection_usd=100/0.98=102.0408, collection_amount=102.5408 as a documentation comment
**Depends on:** 5.8-T07

### 5.8-T10 — Implement rule lookup integration in RateEngine: use RouteContext to load rule fields  _(45 min)_
**Context:** The Rate Engine (RATE-04 §3) resolves the rule via fetchRule(partner_id, scheme_id, direction) to obtain m_a, m_b, service_charge_amount, cost_rate_coll source, cost_rate_pay source, and is_same_ccy_shortcircuit. In the new design, SmartRouter.resolveRoute() has already resolved the rule and passed it in RouteContext. The Rate Engine must consume RouteContext rather than re-querying the rule table, to avoid a second DB round-trip. If RouteContext.shortCircuit==true, skip the 5-step USD pool calculation entirely.
**Steps:** Refactor RateEngine.computeQuote(QuoteRequest request) to accept RouteContext as a parameter instead of calling RuleRepository internally; Extract rule fields from routeContext.rule: m_a, m_b, serviceChargeAmount, rateColl, ratePay, isSameCcyShortcircuit; If routeContext.shortCircuit == true, execute short-circuit: return collection_amount = target_payout + serviceCharge; set all USD pool fields to null; record both rate sources as IDENTITY; Otherwise execute the 5-step RECEIVE-mode calculation using the rule's rate sources; Add an assertion: if shortCircuit==true and m_a != 0 or m_b != 0, throw IllegalStateException (should have been caught by SmartRouter)
**Deliverable:** Refactored RateEngine.computeQuote() method signature and body, RateEngineRouteContextTest.java
**Acceptance / logic checks:**
- computeQuote with shortCircuit=true, target_payout=15000 KRW, service_charge=500 KRW returns collection_amount=15500 KRW with payout_usd_cost=null and collection_usd=null
- computeQuote with shortCircuit=false, INBOUND rule (m_a=0.01, m_b=0.01, cost_rate_pay=1380.00, cost_rate_coll=1.0, target_payout=50000 KRW) returns collection_usd=36.9714 USD (tolerance 0.0001)
- computeQuote does not call RuleRepository (verified with mock.never() on RuleRepository)
- computeQuote with shortCircuit=true and m_a=0.01 throws IllegalStateException
- computeQuote returns all five USD pool step values in the QuoteResult for non-short-circuit routes
**Depends on:** 5.8-T06, 5.8-T07

### 5.8-T11 — Implement direction-aware BOK reporting field population on rate quote  _(40 min)_
**Context:** The direction field determines which BOK FX reporting form is required. INBOUND -> FX1015 (payment to Korean merchant); OUTBOUND -> FX1014 (Korean customer paying overseas, Phase 2); DOMESTIC -> exempt (same-currency, no BOK form); HUB -> TBD (open issue OI-03). At quote time the system must stamp the bok_form_type field on the rate_quote record. The offer_rate_coll derived by the rate engine (= send_amount / (collection_usd - collection_margin_usd)) maps to BOK FX1015 field #14 for INBOUND transactions.
**Steps:** Add bok_form_type VARCHAR(10) column to rate_quote table via migration: CHECK IN ('FX1015','FX1014','EXEMPT','PENDING_OI03') NULLABLE; In RateEngine.computeQuote(), after computing the quote, set bokFormType based on direction: INBOUND->FX1015, OUTBOUND->FX1014, DOMESTIC->EXEMPT, HUB->PENDING_OI03; For INBOUND, verify offer_rate_coll is computed and non-null (offer_rate_coll = send_amount / (collection_usd - collection_margin_usd)); assert > 0; Store offer_rate_coll in rate_quote.offer_rate_coll column; this is the value that maps to BOK FX1015 field #14; Write unit test: INBOUND quote has bok_form_type=FX1015 and offer_rate_coll=(37.3317)/(36.9714-0.3697)=1.0101 for the standard numeric example
**Deliverable:** Migration V5_8_002__rate_quote_bok_form_type.sql, updated RateEngine.computeQuote() with BOK field stamping, BokFormTypeTest.java
**Acceptance / logic checks:**
- INBOUND direction quote has bok_form_type='FX1015' and offer_rate_coll is non-null and > 1.0 (for USD->KRW with margins)
- DOMESTIC direction quote has bok_form_type='EXEMPT' and offer_rate_coll=1.0
- HUB direction quote has bok_form_type='PENDING_OI03'
- offer_rate_coll for INBOUND example (send_amount=36.9714, collection_usd=36.9714, collection_margin_usd=0.3697): send_amount/(collection_usd-collection_margin_usd)=36.9714/36.6017=1.01010 (tolerance 0.0001)
- BOK FX1015 field migration CHECK constraint rejects value 'FX1016'
**Depends on:** 5.8-T10

### 5.8-T12 — Add direction column and routing fields to transaction table and transaction insert  _(35 min)_
**Context:** The transaction table (DAT-03 §5.2) has a direction column VARCHAR(10) CHECK IN ('INBOUND','OUTBOUND','DOMESTIC','HUB'). At commit time, direction must be copied from the rate_quote and stored immutably on the transaction record alongside the rate lock. The transaction also carries settle_a_ccy, settle_b_ccy, collection_ccy, payout_ccy (all from the rate quote). These fields are required for settlement model selection (DOMESTIC=net settlement; INBOUND/OUTBOUND/HUB=gross settlement).
**Steps:** Verify or add direction column to transaction table with CHECK constraint: ALTER TABLE transaction ADD CONSTRAINT chk_txn_direction CHECK (direction IN ('INBOUND','OUTBOUND','DOMESTIC','HUB')); Verify settle_a_ccy, settle_b_ccy, collection_ccy, payout_ccy CHAR(3) columns exist on transaction; In TransactionService.commitTransaction(), when copying locked rate quote fields to the transaction row, also copy direction, settle_a_ccy, settle_b_ccy from the resolved rule via RouteContext; Add a composite index (direction, status, committed_at) to support settlement batch queries that filter by direction; Write migration and verify with test: insert one DOMESTIC row and one INBOUND row, assert direction stored correctly
**Deliverable:** Migration V5_8_003__transaction_direction_index.sql, updated TransactionService.commitTransaction(), TransactionDirectionTest.java
**Acceptance / logic checks:**
- INSERT transaction with direction='INBOUND' stores correctly; direction='INVALID' is rejected by CHECK constraint
- commitTransaction() for a DOMESTIC route stores direction='DOMESTIC' and settle_a_ccy='KRW', payout_ccy='KRW'
- commitTransaction() for an INBOUND route (SendMN) stores direction='INBOUND', settle_a_ccy='USD', payout_ccy='KRW'
- Composite index (direction, status, committed_at) exists in pg_indexes
- direction column on transaction is NOT NULL (cannot insert a transaction without a direction)
**Depends on:** 5.8-T10

### 5.8-T13 — Implement direction-to-settlement-model mapping: DOMESTIC=net, INBOUND=gross  _(30 min)_
**Context:** The Settlement Engine (SAD-02 §5.4, RATE-04 §12) selects the settlement model based on direction. DOMESTIC -> Net Settlement: GME deducts its fee share and remits the remainder to ZeroPay (ZP0061/ZP0063 include net amount = target_payout - GME_fee_share). INBOUND (and future OUTBOUND/HUB) -> Gross Settlement: GME remits the full target_payout to ZeroPay; GME invoices merchants monthly for its fee share. This mapping must be a data-driven function, not hardcoded per partner name. The settlement model is read from direction, not from the partner or rule.
**Steps:** Create SettlementModelResolver.java with method SettlementModel resolve(Direction direction): DOMESTIC -> NET, all others -> GROSS; SettlementModel is an enum { NET, GROSS }; In SettlementBatchService.buildZP0061Record(Transaction txn), call SettlementModelResolver.resolve(txn.direction) to determine net vs gross settlement amount; For NET: settlement_amount = target_payout - gme_fee_share_amount; for GROSS: settlement_amount = target_payout; Write unit tests for all four direction values and both settlement amounts
**Deliverable:** SettlementModelResolver.java, SettlementModel.java enum, SettlementModelResolverTest.java
**Acceptance / logic checks:**
- resolve(DOMESTIC) == NET
- resolve(INBOUND) == GROSS
- resolve(OUTBOUND) == GROSS
- resolve(HUB) == GROSS
- For a DOMESTIC transaction with target_payout=15000 KRW and gme_fee_share=45 KRW, settlement_amount=14955 KRW (NET)
- For an INBOUND transaction with target_payout=50000 KRW, settlement_amount=50000 KRW (GROSS, full amount)
**Depends on:** 5.8-T12

### 5.8-T14 — Add direction filter to transaction query service and settlement batch query  _(40 min)_
**Context:** The Settlement Engine generates ZP0011 (payment registration) and ZP0061 (settlement request) batch files from transactions. The batch query must be direction-aware: the ZP0011 file includes all APPROVED transactions regardless of direction; the settlement amount computation in ZP0061 differs by direction (NET for DOMESTIC, GROSS for others - see 5.8-T13). The Admin System transaction search (PRD-07) also supports filtering by direction. Both queries use the (direction, status, committed_at) composite index added in 5.8-T12.
**Steps:** In TransactionQueryService.findForSettlement(LocalDate date): query WHERE status='APPROVED' AND DATE(committed_at)=:date; return results grouped by direction; In SettlementBatchService.buildDailyBatch(LocalDate date): call findForSettlement then split results into domestic and crossBorder lists by direction; apply SettlementModelResolver to each group; Add TransactionQueryService.findByFilters(direction, status, dateFrom, dateTo, partnerId, schemeId) used by Admin transaction search; direction filter is optional (null = all directions); Write unit test: findForSettlement returns transactions from both DOMESTIC and INBOUND directions; cross-border INBOUND transactions use GROSS settlement amount
**Deliverable:** Updated TransactionQueryService.java with direction-filtered queries, updated SettlementBatchService.buildDailyBatch(), TransactionQueryDirectionTest.java
**Acceptance / logic checks:**
- findForSettlement for a date with 3 DOMESTIC + 2 INBOUND APPROVED transactions returns 5 rows with correct direction values
- buildDailyBatch produces domestic list size=3 and crossBorder list size=2
- DOMESTIC transaction in settlement batch has settlement_amount = target_payout - fee_share (NET)
- INBOUND transaction in settlement batch has settlement_amount = target_payout (GROSS)
- findByFilters with direction=null returns all directions; direction=INBOUND returns only INBOUND transactions
**Depends on:** 5.8-T13

### 5.8-T15 — Implement DIRECTION_NOT_ENABLED error response mapping  _(35 min)_
**Context:** When SmartRouter.resolveRoute() throws DirectionNotEnabledException, the API Gateway must return HTTP 422 with error code DIRECTION_NOT_ENABLED per API-05 error catalogue. This is a non-retryable error. The error response body must conform to the standard error schema: { error_code: 'DIRECTION_NOT_ENABLED', message: '...', retryable: false }. The same handler must also map RoutingIntegrityException (data integrity error) to HTTP 500 INTERNAL_ERROR which is retryable.
**Steps:** Create DirectionNotEnabledException extends RuntimeException with fields direction (Direction) and ruleKey (RuleKey); Create RoutingIntegrityException extends RuntimeException with fields ruleKey and violationType (String); In the global exception handler (@RestControllerAdvice): map DirectionNotEnabledException -> HTTP 422 body {error_code:'DIRECTION_NOT_ENABLED', message:'Direction {d} is not enabled for partner {p} on scheme {s}', retryable:false}; Map RoutingIntegrityException -> HTTP 500 body {error_code:'INTERNAL_ERROR', message:'Routing integrity check failed', retryable:true}; Write MockMvc integration tests for both error paths verifying HTTP status, error_code, and retryable fields
**Deliverable:** DirectionNotEnabledException.java, RoutingIntegrityException.java, updated GlobalExceptionHandler.java, DirectionErrorMappingTest.java
**Acceptance / logic checks:**
- GET /v1/rates with direction=OUTBOUND (FEATURE_OUTBOUND_PAYMENTS=false) returns HTTP 422 with error_code='DIRECTION_NOT_ENABLED' and retryable=false
- POST /v1/payments with direction=OUTBOUND returns HTTP 422 with error_code='DIRECTION_NOT_ENABLED'
- RoutingIntegrityException maps to HTTP 500 with error_code='INTERNAL_ERROR' and retryable=true
- Error response body for DIRECTION_NOT_ENABLED includes the direction value in the message field
- DirectionNotEnabledException is never mapped to HTTP 500 (assert status code explicitly in tests)
**Depends on:** 5.8-T04

### 5.8-T16 — Add direction field to rate quote API request/response and validate direction is enabled  _(40 min)_
**Context:** API-05 defines GET /v1/rates request parameters: partner_id, scheme_id, direction (enum: domestic, inbound, outbound, hub), target_payout, payout_currency. The direction field is required. Before calling the Rate Engine, the API layer must call SmartRouter.resolveRoute() to validate that the direction is enabled and the rule is ACTIVE. The resolved RouteContext is then passed to the Rate Engine. The GET /v1/rates response includes direction in the response body alongside offer_rate, collection_amount, validUntil.
**Steps:** In RateQuoteRequest DTO add direction field (String, required, @NotNull) and map to Direction enum via Direction.fromString() before passing to SmartRouter; In RateQuoteController.getRate(): deserialise request, call SmartRouter.resolveRoute(partnerId, schemeId, direction), pass RouteContext to RateEngine.computeQuote(); Add direction field to RateQuoteResponse DTO (String, uppercase enum name); Write MockMvc test: GET /v1/rates with direction=domestic for GME Remit rule returns HTTP 200 with direction='DOMESTIC' in response; Write MockMvc test: GET /v1/rates with direction=outbound returns HTTP 422 DIRECTION_NOT_ENABLED
**Deliverable:** Updated RateQuoteRequest.java, updated RateQuoteController.getRate() method, updated RateQuoteResponse.java, RateQuoteDirectionIntegrationTest.java
**Acceptance / logic checks:**
- GET /v1/rates with direction=domestic returns HTTP 200 and response body contains direction='DOMESTIC'
- GET /v1/rates with direction=outbound (feature flag false) returns HTTP 422 with error_code='DIRECTION_NOT_ENABLED'
- GET /v1/rates with direction=inbound, m_a=0.01, m_b=0.01, target_payout=50000 KRW returns collection_usd approx 36.97 USD
- GET /v1/rates without direction field returns HTTP 400 (validation error, field required)
- GET /v1/rates with direction=invalid_value returns HTTP 400 with a descriptive validation error
**Depends on:** 5.8-T15

### 5.8-T17 — Add direction field to payment commit API and validate direction matches quote  _(35 min)_
**Context:** POST /v1/payments (MPM commit) carries a direction field that must match the direction stored in the locked rate_quote. A mismatch (e.g. quote was for DOMESTIC but commit specifies INBOUND) must be rejected with RATE_QUOTE_INVALID (HTTP 422). The direction on the committed transaction is taken from the rate_quote (the locked value), not from the commit request, to prevent tampering. This check applies to both MPM (POST /v1/payments) and CPM commit flows.
**Steps:** In MpmPaymentRequest add direction field (String, required); In TransactionService.commitTransaction(): after lockRateQuote(), assert request.direction == quote.direction; if mismatch throw RateQuoteInvalidException with message 'direction mismatch: request=%s quote=%s'; The transaction.direction field is always taken from quote.direction (immutable locked value), never from the request body after validation; Add same check to CPM commit path if present in scope; Write unit tests: direction matches -> proceeds; direction mismatches -> RateQuoteInvalidException
**Deliverable:** Updated MpmPaymentRequest.java, updated TransactionService.commitTransaction() with direction cross-check, DirectionMismatchTest.java
**Acceptance / logic checks:**
- commitTransaction with request.direction=INBOUND and quote.direction=INBOUND succeeds
- commitTransaction with request.direction=DOMESTIC and quote.direction=INBOUND throws RateQuoteInvalidException with code RATE_QUOTE_INVALID
- transaction.direction after commit equals quote.direction (INBOUND), not the tampered request value
- commitTransaction with request.direction=OUTBOUND (regardless of quote) throws DIRECTION_NOT_ENABLED before quote lock (feature flag check first)
- RateQuoteInvalidException maps to HTTP 422 with error_code='RATE_QUOTE_INVALID'
**Depends on:** 5.8-T16

### 5.8-T18 — Implement rule CRUD admin endpoints: create, activate, suspend rule by key  _(55 min)_
**Context:** The Admin Service (PRD-07 §6) must support: POST /admin/rules (create a new DRAFT rule for a (partner, scheme, direction) triplet), PUT /admin/rules/{id}/activate (DRAFT -> ACTIVE; evicts cache), PUT /admin/rules/{id}/suspend (ACTIVE -> SUSPENDED; evicts cache). The UNIQUE constraint (partner_id, scheme_id, direction) enforces at most one rule per triplet. All three operations must write an audit log entry with actor, timestamp, field, previous_value, new_value. A rule can be created for any of the four directions including OUTBOUND and HUB (scaffolded in Phase 1 as DRAFT, never activated until Phase 2).
**Steps:** Implement AdminRuleController.createRule(CreateRuleRequest req): validate partner+scheme exist; verify no existing rule for (partner_id, scheme_id, direction); INSERT rule with status=DRAFT; write audit log entry {event:'rule_created', actor, rule_key, status:'DRAFT'}; Implement activateRule(long ruleId): transition DRAFT->ACTIVE; evict RuleRepository cache; write audit log {event:'rule_activated', previous:'DRAFT', new:'ACTIVE'}; Implement suspendRule(long ruleId): transition ACTIVE->SUSPENDED; evict cache; write audit log {event:'rule_suspended', previous:'ACTIVE', new:'SUSPENDED'}; Return 409 CONFLICT if createRule is called for a duplicate (partner_id, scheme_id, direction); Write integration tests for all three endpoints including audit log verification
**Deliverable:** AdminRuleController.java with createRule, activateRule, suspendRule; AdminRuleControllerTest.java
**Acceptance / logic checks:**
- POST /admin/rules with new (partner, scheme, direction) triplet returns HTTP 201 with status=DRAFT
- POST /admin/rules with duplicate triplet returns HTTP 409
- PUT /admin/rules/{id}/activate transitions DRAFT->ACTIVE, evicts Redis cache, inserts audit log row with event=rule_activated
- PUT /admin/rules/{id}/suspend transitions ACTIVE->SUSPENDED, evicts Redis cache, inserts audit log row with event=rule_suspended
- PUT /admin/rules/{id}/activate for a rule with direction=OUTBOUND succeeds (creates a DRAFT->ACTIVE OUTBOUND rule; routing still blocked by feature flag)
**Depends on:** 5.8-T03

### 5.8-T19 — Implement rule margin and service-charge update endpoint with cross-border minimum validation  _(45 min)_
**Context:** PUT /admin/rules/{id}/margins updates m_a, m_b, service_charge_amount on a rule. Business rules: for cross-border rules (is_same_ccy_shortcircuit=false) m_a + m_b >= 0.02 (2.0%); for same-currency rules m_a = m_b = 0 (locked). service_charge_amount >= 0. Changes take effect for new transactions only; committed transactions retain locked values. Each changed field must produce an audit log entry per field (not one entry for the whole update) with previous_value and new_value per the spec example: {event:'rule_field_changed', rule_key:{partner,scheme,direction}, field:'m_a', previous_value:'1.00', new_value:'1.25'}.
**Steps:** Implement AdminRuleController.updateMargins(long ruleId, UpdateMarginsRequest req): load rule; validate cross-border margin floor if !is_same_ccy_shortcircuit; validate same-currency margin is 0 if is_same_ccy_shortcircuit; Apply changes atomically; evict RuleRepository cache for the rule's key; For each field that changed, write a separate audit log entry: {event:'rule_field_changed', actor_user_id, timestamp_utc, rule_key, field, previous_value, new_value}; Return HTTP 200 with updated rule; return HTTP 422 with VALIDATION_ERROR if margin constraints violated; Write tests: cross-border m_a+m_b=0.015 rejected; same-currency m_a=0.01 rejected; valid update produces correct audit entries
**Deliverable:** AdminRuleController.updateMargins(), UpdateMarginsRequest.java, AdminRuleMarginUpdateTest.java
**Acceptance / logic checks:**
- Updating INBOUND rule (cross-border) to m_a=0.005, m_b=0.005 (sum=0.01) returns HTTP 422 VALIDATION_ERROR
- Updating INBOUND rule to m_a=0.015, m_b=0.01 (sum=0.025) returns HTTP 200
- Updating DOMESTIC rule (same-currency) to m_a=0.01 returns HTTP 422 (must be 0)
- Successful update produces one audit log entry per changed field with correct previous_value and new_value
- After update, RuleRepository.findByKey() reflects new m_a and m_b values (cache evicted)
**Depends on:** 5.8-T18

### 5.8-T20 — Unit tests: SmartRouter full routing matrix across all four directions and feature flag states  _(55 min)_
**Context:** Comprehensive unit test suite for SmartRouter covering all routing branches. Test matrix: 4 directions x 2 feature flag states x 3 partner types (LOCAL/OVERSEAS/missing) x 2 rule statuses (ACTIVE/DRAFT). Key invariants: DOMESTIC always routes to short-circuit; INBOUND always requires OVERSEAS partner and >= 2% margin; OUTBOUND/HUB blocked when feature flag false; OUTBOUND/HUB throw UnsupportedOperationException when feature flag true (Phase 1 stubs).
**Steps:** Write parameterised unit tests for SmartRouter.resolveRoute() covering: DOMESTIC+LOCAL+ACTIVE->RouteContext with shortCircuit=true; INBOUND+OVERSEAS+ACTIVE+2%margin->RouteContext requiresPrefunding=true; OUTBOUND+flag=false->DirectionNotEnabledException; OUTBOUND+flag=true->UnsupportedOperationException; HUB+flag=false->DirectionNotEnabledException; HUB+flag=true->UnsupportedOperationException; INBOUND+LOCAL partner->RoutingIntegrityException; INBOUND+OVERSEAS+1% margin->RoutingIntegrityException; any direction+DRAFT rule->DirectionNotEnabledException; any direction+SUSPENDED rule->DirectionNotEnabledException; Use Mockito to mock RuleRepository, PartnerRepository, SchemeRepository, FeatureFlagService; Assert exact exception types, HTTP error codes, and RouteContext field values for success paths
**Deliverable:** SmartRouterMatrixTest.java with >= 14 parameterised test cases
**Acceptance / logic checks:**
- All 14+ routing matrix combinations have an explicit test case
- DOMESTIC+LOCAL+ACTIVE returns shortCircuit=true, requiresPrefunding=false
- INBOUND+OVERSEAS+m_a=0.01+m_b=0.01+ACTIVE returns shortCircuit=false, requiresPrefunding=true
- OUTBOUND with flag=false throws DirectionNotEnabledException; with flag=true throws UnsupportedOperationException
- INBOUND with LOCAL partner throws RoutingIntegrityException (not DirectionNotEnabledException)
**Depends on:** 5.8-T09

### 5.8-T21 — Unit tests: Rate engine for DOMESTIC short-circuit and INBOUND cross-border numeric examples  _(45 min)_
**Context:** Explicit unit tests for the two canonical rate engine paths triggered by direction routing. DOMESTIC (short-circuit): target_payout=15000 KRW, service_charge=500 KRW -> collection_amount=15500 KRW; USD pool fields null; both rate sources IDENTITY. INBOUND cross-border (5-step): target_payout=50000 KRW, cost_rate_pay=1380.00 (usd_krw), cost_rate_coll=1.0 (IDENTITY), m_a=0.01, m_b=0.01, service_charge=0.36 USD -> payout_usd_cost=36.2319, collection_usd=36.9714, collection_margin_usd=0.3697, payout_margin_usd=0.3697, send_amount=36.9714, collection_amount=37.3314; pool identity holds within 0.01 USD tolerance.
**Steps:** Write DomesticShortCircuitRateTest: call RateEngine.computeQuote with shortCircuit=true RouteContext; assert collection_amount=15500 KRW, payout_usd_cost=null, offer_rate_coll=1.0; Write InboundCrossBorderRateTest: call RateEngine.computeQuote with shortCircuit=false RouteContext, m_a=0.01, m_b=0.01, cost_rate_pay=1380.00, cost_rate_coll=1.0, target_payout=50000 KRW; assert each step output within tolerance 0.0001; Assert pool identity: collection_usd - collection_margin_usd - payout_margin_usd == payout_usd_cost within 0.01 USD; Assert offer_rate_coll = send_amount / (collection_usd - collection_margin_usd) = 36.9714 / (36.9714 - 0.3697) = 36.9714/36.6017 = 1.01010 within tolerance 0.0001; Assert bok_form_type=EXEMPT for DOMESTIC and FX1015 for INBOUND
**Deliverable:** DomesticShortCircuitRateTest.java, InboundCrossBorderRateTest.java
**Acceptance / logic checks:**
- DOMESTIC: collection_amount=15500, payout_usd_cost=null, collection_usd=null, offer_rate_coll=1.0
- INBOUND: payout_usd_cost=36.2319 (tolerance 0.0001), collection_usd=36.9714, collection_margin_usd=0.3697, payout_margin_usd=0.3697
- INBOUND pool identity: 36.9714 - 0.3697 - 0.3697 = 36.2320 == payout_usd_cost within 0.01 USD
- INBOUND offer_rate_coll=1.01010 within tolerance 0.0001
- DOMESTIC bok_form_type=EXEMPT; INBOUND bok_form_type=FX1015
**Depends on:** 5.8-T11

### 5.8-T22 — Unit tests: DIRECTION_NOT_ENABLED and routing error API responses  _(45 min)_
**Context:** Integration tests verifying the complete HTTP response for direction-related errors. Test that DIRECTION_NOT_ENABLED is returned as HTTP 422 with retryable=false when OUTBOUND is requested; that a missing or DRAFT rule also returns DIRECTION_NOT_ENABLED; that a DOMESTIC short-circuit route returns the expected collection_amount=target_payout+service_charge with no USD pool fields; and that direction mismatch at commit time returns RATE_QUOTE_INVALID.
**Steps:** Write MockMvc test GET /v1/rates direction=outbound -> HTTP 422 {error_code:'DIRECTION_NOT_ENABLED', retryable:false}; Write MockMvc test GET /v1/rates direction=domestic with active GME Remit rule -> HTTP 200 response includes direction='DOMESTIC', collection_amount=15500, no payout_usd_cost field; Write MockMvc test GET /v1/rates with no active rule for (partner, scheme, INBOUND) -> HTTP 422 {error_code:'DIRECTION_NOT_ENABLED'}; Write MockMvc test POST /v1/payments with direction=DOMESTIC but locked quote.direction=INBOUND -> HTTP 422 {error_code:'RATE_QUOTE_INVALID'}; Write MockMvc test GET /v1/rates direction=hub -> HTTP 422 {error_code:'DIRECTION_NOT_ENABLED'}
**Deliverable:** DirectionApiErrorTest.java with 5+ MockMvc test cases
**Acceptance / logic checks:**
- direction=outbound returns HTTP 422 with error_code=DIRECTION_NOT_ENABLED and retryable=false
- direction=domestic (short-circuit active rule) returns HTTP 200 with collection_amount present and payout_usd_cost absent from response body
- direction=inbound with no ACTIVE rule returns HTTP 422 DIRECTION_NOT_ENABLED
- direction mismatch at commit returns HTTP 422 RATE_QUOTE_INVALID
- direction=hub returns HTTP 422 DIRECTION_NOT_ENABLED (feature flag false)
**Depends on:** 5.8-T17

### 5.8-T23 — Add direction routing config to Admin UI: display and validate direction field on rule create  _(45 min)_
**Context:** PRD-07 §6.3 states that to create a rule the operator selects Partner, QR Scheme, and Direction (INBOUND/OUTBOUND/DOMESTIC/HUB). The backend Create Rule endpoint (5.8-T18) must accept and validate the direction selection. This ticket covers the backend API contract for the Admin UI rule creation flow, specifically the direction validation logic and the response that drives the UI's currency-setup section (Section 1 of the Mapping Page). The UI calls POST /admin/rules with {partner_id, scheme_id, direction} and receives back the auto-derived currency quadruple.
**Steps:** CreateRuleRequest DTO: add direction field (String, required) with validation Direction.fromString(); In AdminRuleService.createRule(): derive currency quadruple from partner.collection_currency, partner.settlement_currency, scheme.settlement_currency, scheme.payout_currency; set is_same_ccy_shortcircuit = (all four equal); Return CreateRuleResponse with: rule_id, status='DRAFT', direction, collection_ccy, settle_a_ccy, settle_b_ccy, payout_ccy, is_same_ccy_shortcircuit; For direction=DOMESTIC: validate that is_same_ccy_shortcircuit is true for the given partner+scheme combination; if not, return HTTP 422 with error message 'DOMESTIC direction requires same-currency partner-scheme combination'; Write tests: DOMESTIC direction with KRW/KRW/KRW/KRW currencies returns is_same_ccy_shortcircuit=true; INBOUND direction with MNT/USD/KRW/KRW returns is_same_ccy_shortcircuit=false
**Deliverable:** Updated CreateRuleRequest.java, updated AdminRuleService.createRule() with currency derivation, AdminRuleCreateDirectionTest.java
**Acceptance / logic checks:**
- POST /admin/rules {partner_id:GME_REMIT, scheme_id:ZEROPAY, direction:'DOMESTIC'} returns {direction:'DOMESTIC', collection_ccy:'KRW', settle_a_ccy:'KRW', settle_b_ccy:'KRW', payout_ccy:'KRW', is_same_ccy_shortcircuit:true}
- POST /admin/rules {partner_id:SENDMN, scheme_id:ZEROPAY, direction:'INBOUND'} returns {direction:'INBOUND', collection_ccy:'MNT', settle_a_ccy:'USD', settle_b_ccy:'KRW', payout_ccy:'KRW', is_same_ccy_shortcircuit:false}
- POST /admin/rules with direction='DOMESTIC' for a cross-currency partner returns HTTP 422
- POST /admin/rules with direction='INVALID_DIR' returns HTTP 400
- POST /admin/rules with direction='HUB' creates a DRAFT rule (no feature flag check at rule creation time; flag only checked at transaction time)
**Depends on:** 5.8-T18

### 5.8-T24 — Integration test: end-to-end DOMESTIC payment routing via SmartRouter through to transaction commit  _(55 min)_
**Context:** End-to-end integration test for the DOMESTIC direction routing path: from GET /v1/rates through SmartRouter -> RateEngine (short-circuit) -> redis quote store -> POST /v1/payments commit -> transaction persisted with direction=DOMESTIC. Uses an in-memory H2 or Testcontainers Postgres DB. Partner: GME Remit (LOCAL, KRW). Scheme: ZeroPay (payout_ccy=KRW). Rule: ACTIVE, is_same_ccy_shortcircuit=true, m_a=0, m_b=0, service_charge=500 KRW. Target payout: 15000 KRW. Expected collection_amount: 15500 KRW.
**Steps:** Set up test data: insert partner (GME_REMIT, type=LOCAL, collection_currency=KRW, settlement_currency=KRW), scheme (ZEROPAY, payout_currency=KRW), and rule (direction=DOMESTIC, is_same_ccy_shortcircuit=true, m_a=0, m_b=0, service_charge=500, status=ACTIVE); Call GET /v1/rates with direction=domestic, target_payout=15000, payout_currency=KRW; assert HTTP 200 and collection_amount=15500; Extract quote_id from response; call POST /v1/payments with same direction=domestic and quote_id; Assert HTTP 201 from commit; assert transaction row in DB with direction='DOMESTIC', status=APPROVED (or SCHEME_SENT), collection_amount=15500, payout_usd_cost=NULL; Assert no entry in prefunding_ledger (LOCAL partner, no prefunding deduction)
**Deliverable:** DomesticPaymentRoutingIntegrationTest.java
**Acceptance / logic checks:**
- GET /v1/rates returns collection_amount=15500 KRW with no payout_usd_cost field in response
- POST /v1/payments returns HTTP 201 with status in {APPROVED, SCHEME_SENT}
- transaction.direction='DOMESTIC' in DB after commit
- transaction.payout_usd_cost=NULL in DB (short-circuit applied)
- prefunding_ledger has zero rows for this partner after the payment (LOCAL partner, no deduction)
**Depends on:** 5.8-T22

### 5.8-T25 — Integration test: end-to-end INBOUND payment routing via SmartRouter through to transaction commit  _(55 min)_
**Context:** End-to-end integration test for the INBOUND direction routing path. Partner: SendMN (OVERSEAS, collection_currency=MNT, settlement_currency=USD). Scheme: ZeroPay (payout_currency=KRW, settle_b_ccy=KRW). Rule: ACTIVE, is_same_ccy_shortcircuit=false, m_a=0.01, m_b=0.01, service_charge=0.36 USD, status=ACTIVE. Treasury: usd_krw=1380.00, usd_usd=1.0. Target payout: 50000 KRW. Expected payout_usd_cost=36.2319, collection_usd=36.9714, collection_amount=37.3314 USD. Prefunding balance must be >= 36.9714 USD before the test.
**Steps:** Set up test data: insert partner SENDMN (OVERSEAS, collection_currency=USD, settlement_currency=USD), prefunding_account with balance=200.00 USD, scheme ZEROPAY, rule (INBOUND, m_a=0.01, m_b=0.01, service_charge=0.36, status=ACTIVE), treasury rows usd_krw=1380.00; Call GET /v1/rates with direction=inbound, target_payout=50000, payout_currency=KRW; assert HTTP 200, collection_usd approx 36.97; Capture quote_id; call POST /v1/payments with direction=inbound; assert HTTP 201; Assert transaction row: direction='INBOUND', collection_usd=36.9714 (tolerance 0.01), bok_form_type='FX1015'; Assert prefunding_account.balance decreased by 36.9714 USD (tolerance 0.01); assert one DEBIT_PAYMENT ledger entry
**Deliverable:** InboundPaymentRoutingIntegrationTest.java
**Acceptance / logic checks:**
- GET /v1/rates returns payout_usd_cost approx 36.23 and collection_usd approx 36.97 (tolerance 0.01)
- POST /v1/payments returns HTTP 201
- transaction.direction='INBOUND', transaction.bok_form_type='FX1015' in DB
- prefunding_account.balance after payment = 200.00 - 36.9714 = 163.0286 USD (tolerance 0.01)
- Pool identity holds: collection_usd - collection_margin_usd - payout_margin_usd == payout_usd_cost within 0.01 USD in the stored transaction row
**Depends on:** 5.8-T24


## WBS 8.4 — POST /payments & /cpm/generate
### 8.4-T01 — Flyway migration V301: create payments table in payment-executor (PostgreSQL 16)  _(35 min)_
**Context:** WBS 8.4 delivers POST /v1/payments (MPM execute) and POST /v1/payments/cpm/generate (CPM token issue) in the payment-executor Spring Boot service (services/payment-executor/). Both endpoints persist to PostgreSQL 16. The payments table stores: payment_id TEXT PK, partner_id TEXT NOT NULL, quote_id TEXT, status TEXT NOT NULL CHECK(status IN ('CPM_PENDING','PENDING','APPROVED','FAILED','CANCELLED','UNCERTAIN')), payment_mode TEXT NOT NULL CHECK(payment_mode IN ('MPM','CPM')), direction TEXT NOT NULL, scheme_id TEXT NOT NULL, merchant_qr TEXT, customer_ref TEXT NOT NULL, partner_txn_ref TEXT NOT NULL, collection_amount NUMERIC(20,4), collection_currency TEXT, target_payout NUMERIC(20,4), payout_currency TEXT, offer_rate NUMERIC(24,10), send_amount NUMERIC(20,8), service_charge NUMERIC(20,4), service_charge_currency TEXT, collection_usd NUMERIC(20,8), payout_usd_cost NUMERIC(20,8), collection_margin_usd NUMERIC(20,8), payout_margin_usd NUMERIC(20,8), cross_rate NUMERIC(24,10), prefund_deducted_usd NUMERIC(20,8), scheme_txn_id TEXT, country_code TEXT, idempotency_key TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), approved_at TIMESTAMPTZ, updated_at TIMESTAMPTZ, cancelled_at TIMESTAMPTZ. Unique constraint on (partner_id, idempotency_key) and (partner_id, partner_txn_ref). All NUMERIC money fields use NUMERIC (not FLOAT). Migrations via Flyway naming convention Vnnn__*.sql.
**Steps:** Create services/payment-executor/src/main/resources/db/migration/V301__create_payments_table.sql with the payments table DDL including all columns listed above.; Add CHECK constraints on status and payment_mode columns.; Add UNIQUE INDEX on (partner_id, idempotency_key) and (partner_id, partner_txn_ref).; Add indexes on (partner_id, status), (quote_id), (scheme_txn_id) WHERE scheme_txn_id IS NOT NULL, (partner_id, created_at DESC).; Run flyway:migrate against local Postgres on port 5433 (docker-compose) and confirm table exists with correct structure.
**Deliverable:** services/payment-executor/src/main/resources/db/migration/V301__create_payments_table.sql
**Acceptance / logic checks:**
- Migration runs cleanly; flyway_schema_history shows V301 as success.
- UNIQUE constraint on (partner_id, idempotency_key): duplicate INSERT raises constraint violation.
- UNIQUE constraint on (partner_id, partner_txn_ref): duplicate partner_txn_ref per partner rejected.
- CHECK on status rejects INSERT with status='INVALID'.
- All monetary columns are NUMERIC not FLOAT (verify with information_schema.columns).

### 8.4-T02 — Flyway migration V302: create cpm_tokens table in payment-executor  _(25 min)_
**Context:** The cpm_tokens table stores CPM QR tokens issued by POST /v1/payments/cpm/generate. Schema: cpm_token_id TEXT PK, payment_id TEXT NOT NULL REFERENCES payments(payment_id) ON DELETE RESTRICT, prepare_token TEXT NOT NULL UNIQUE, qr_content TEXT NOT NULL, expires_at TIMESTAMPTZ NOT NULL, prefund_reserved_usd NUMERIC(20,8), status TEXT NOT NULL CHECK(status IN ('ACTIVE','CONSUMED','EXPIRED')) DEFAULT 'ACTIVE', created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(). Index on expires_at for TTL expiry cleanup job. Index on (status, expires_at) for finding expired-but-not-consumed tokens. Token TTL default is 60 seconds from generation; configurable per partner via Config Registry.
**Steps:** Create services/payment-executor/src/main/resources/db/migration/V302__create_cpm_tokens_table.sql.; Add FK cpm_tokens.payment_id -> payments.payment_id ON DELETE RESTRICT.; Add UNIQUE constraint on prepare_token.; Add indexes on expires_at and (status, expires_at).; Run flyway:migrate and confirm table and FK exist.
**Deliverable:** services/payment-executor/src/main/resources/db/migration/V302__create_cpm_tokens_table.sql
**Acceptance / logic checks:**
- Migration V302 runs cleanly after V301.
- UNIQUE on prepare_token: second INSERT with same prepare_token raises constraint violation.
- FK violation: INSERT with non-existent payment_id raises FK error.
- CHECK on status: INSERT with status='INVALID' rejected.
- expires_at index exists in pg_indexes.
**Depends on:** 8.4-T01

### 8.4-T03 — Flyway migration V303: payment_events audit trail table in payment-executor  _(25 min)_
**Context:** Every transaction carries an 8-step event trail per spec DAT-03 and API-05 §5. Steps (in order): RATE_QUOTE_ISSUED, PREFUND_DEDUCTED, SCHEME_SUBMITTED, SCHEME_APPROVED, SCHEME_DECLINED, TRANSACTION_COMMITTED, WEBHOOK_QUEUED, WEBHOOK_DELIVERED. An additional PREFUND_REVERSED step covers scheme-fail reversal; CPM_TOKEN_ISSUED covers CPM generation. The payment_events table: event_id TEXT PK, payment_id TEXT NOT NULL REFERENCES payments(payment_id) ON DELETE CASCADE, event_type TEXT NOT NULL, occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), actor TEXT, payload JSONB. CHECK constraint on event_type covers all valid steps. Composite index on (payment_id, event_type) and index on occurred_at.
**Steps:** Create services/payment-executor/src/main/resources/db/migration/V303__create_payment_events_table.sql.; Add CHECK constraint on event_type listing all 10 valid values (RATE_QUOTE_ISSUED, PREFUND_DEDUCTED, SCHEME_SUBMITTED, SCHEME_APPROVED, SCHEME_DECLINED, TRANSACTION_COMMITTED, WEBHOOK_QUEUED, WEBHOOK_DELIVERED, PREFUND_REVERSED, CPM_TOKEN_ISSUED).; Add FK to payments(payment_id) ON DELETE CASCADE.; Add composite index on (payment_id, occurred_at DESC) for audit trail queries.; Run flyway:migrate and confirm table exists.
**Deliverable:** services/payment-executor/src/main/resources/db/migration/V303__create_payment_events_table.sql
**Acceptance / logic checks:**
- V303 runs cleanly after V302.
- INSERT of all 10 valid event_type values succeeds.
- INSERT with event_type='UNKNOWN_STEP' raises CHECK violation.
- FK: INSERT with non-existent payment_id raises FK error.
- payload column accepts arbitrary JSONB objects.
**Depends on:** 8.4-T01

### 8.4-T04 — Flyway migration V304: idempotency_cache table and outbox_events table in payment-executor  _(20 min)_
**Context:** Two supporting tables needed. (1) idempotency_cache: partner_id TEXT NOT NULL, idempotency_key TEXT NOT NULL, request_body_hash TEXT NOT NULL, response_status INT NOT NULL, response_body TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), expires_at TIMESTAMPTZ NOT NULL. PRIMARY KEY (partner_id, idempotency_key). Index on expires_at for TTL cleanup. (2) outbox_events (transactional Outbox pattern, Phase 1 - Kafka deferred): id BIGSERIAL PK, aggregate_id TEXT NOT NULL, event_type TEXT NOT NULL, payload JSONB NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), published_at TIMESTAMPTZ, status TEXT NOT NULL DEFAULT 'UNPUBLISHED' CHECK(status IN ('UNPUBLISHED','PUBLISHED','FAILED')). Index on (status, created_at) for OutboxPoller queries.
**Steps:** Create services/payment-executor/src/main/resources/db/migration/V304__create_idempotency_and_outbox_tables.sql with both table DDLs.; Add PRIMARY KEY (partner_id, idempotency_key) on idempotency_cache.; Add CHECK(status IN ('UNPUBLISHED','PUBLISHED','FAILED')) on outbox_events.; Add index on (status, created_at) on outbox_events.; Run flyway:migrate and confirm both tables exist.
**Deliverable:** services/payment-executor/src/main/resources/db/migration/V304__create_idempotency_and_outbox_tables.sql
**Acceptance / logic checks:**
- Both tables exist after migration.
- PRIMARY KEY on idempotency_cache: duplicate (partner_id, idempotency_key) INSERT raises PK violation.
- outbox_events CHECK on status rejects INSERT with status='BAD'.
- Index on (status, created_at) exists for outbox_events.
- JSONB column payload on outbox_events accepts and returns nested JSON objects.
**Depends on:** 8.4-T01

### 8.4-T05 — Define PaymentExecuteRequest, CpmGenerateRequest and response DTOs in lib-api-contracts  _(35 min)_
**Context:** lib-api-contracts holds OpenAPI-generated DTOs shared across services. For POST /v1/payments the inbound DTO is PaymentExecuteRequest fields: quoteId (required), merchantQr (required), direction (required), schemeId (required), customerRef (required), partnerTxnRef (required), collectionAmount String (required), collectionCurrency (required), countryCode (optional). For POST /v1/payments/cpm/generate the DTO is CpmGenerateRequest fields: schemeId (required), direction (required), customerRef (required), partnerTxnRef (required), countryCode (required), prefundReserveUsd String (optional). Response DTOs: PaymentExecuteResponse (paymentId, status, schemeTxnId, merchantName, merchantId, targetPayout, payoutCurrency, offerRate, collectionAmount, collectionCurrency, serviceCharge, serviceChargeCurrency, prefundDeductedUsd nullable, partnerTxnRef, createdAt, approvedAt). CpmGenerateResponse (cpmTokenId, prepareToken, qrContent, expiresAt, prefundReservedUsd nullable, paymentId, schemeId, partnerTxnRef, createdAt). All money amounts are String (decimal). Timestamps are String (ISO-8601 UTC ms precision e.g. 2026-06-04T09:31:00.000Z).
**Steps:** Add PaymentExecuteRequest, CpmGenerateRequest, PaymentExecuteResponse, CpmGenerateResponse schemas to openapi/partner-api.yaml under components/schemas.; Mark required fields in schemas; prefundReserveUsd and prefundDeductedUsd are nullable/optional.; Run ./gradlew :lib-api-contracts:generateOpenApiSources to regenerate Java DTOs.; Verify generated Java classes in lib-api-contracts/build/generated contain all fields as String or nullable String.; Run ./gradlew :lib-api-contracts:build and confirm zero compile errors.
**Deliverable:** openapi/partner-api.yaml (schemas section updated) and regenerated DTOs in lib/api-contracts/
**Acceptance / logic checks:**
- PaymentExecuteRequest has 9 fields; collectionAmount is present as required.
- CpmGenerateRequest: prefundReserveUsd is optional (not in required array).
- PaymentExecuteResponse: prefundDeductedUsd is nullable String (null for LOCAL partners).
- CpmGenerateResponse: expiresAt and prefundReservedUsd fields present.
- ./gradlew :lib-api-contracts:build GREEN with zero errors.

### 8.4-T06 — Define domain event records for payment lifecycle in lib-events  _(40 min)_
**Context:** lib-events module defines all domain event schemas (transactional Outbox pattern; Kafka transport wired in integration phase behind EventPublisher interface). Events for WBS 8.4: PaymentInitiatedEvent (paymentId, partnerId, quoteId, direction, schemeId, collectionAmount BigDecimal, collectionCurrency, targetPayout BigDecimal, payoutCurrency, createdAt Instant), PrefundDeductedEvent (paymentId, partnerId, amountUsd BigDecimal, balanceAfterUsd BigDecimal, deductedAt Instant), SchemeSubmittedEvent (paymentId, schemeTxnId, submittedAt Instant), PaymentApprovedEvent (paymentId, schemeTxnId, offerRate BigDecimal, approvedAt Instant), PaymentFailedEvent (paymentId, failureCode String, prefundReturnedUsd BigDecimal nullable, failedAt Instant), CpmTokenIssuedEvent (cpmTokenId, paymentId, partnerId, prefundReservedUsd BigDecimal nullable, expiresAt Instant, createdAt Instant), LowBalanceAlertEvent (partnerId, balanceUsd BigDecimal, thresholdUsd BigDecimal, triggeredAt Instant). All implement DomainEvent interface (eventId String, eventType String, occurredAt Instant, aggregateId String).
**Steps:** Create Java record classes for each event in lib-events/src/main/java/com/gme/pay/events/payment/ (6 classes) and lib-events/src/main/java/com/gme/pay/events/prefunding/ (LowBalanceAlertEvent).; Ensure each record implements the DomainEvent marker interface already in lib-events.; Add @JsonProperty Jackson annotations on each record field.; Write JUnit 5 unit tests for each event: serialize to JSON then deserialize back; assert equal objects.; Run ./gradlew :lib-events:test GREEN.
**Deliverable:** lib-events/src/main/java/com/gme/pay/events/payment/ (6 event records) and lib-events/src/main/java/com/gme/pay/events/prefunding/LowBalanceAlertEvent.java
**Acceptance / logic checks:**
- All 7 event records implement DomainEvent interface.
- Jackson round-trip test passes for each record (serialize -> deserialize -> equals).
- PaymentFailedEvent.prefundReturnedUsd is BigDecimal nullable (null for LOCAL partners).
- CpmTokenIssuedEvent.prefundReservedUsd is BigDecimal (not double or float).
- ./gradlew :lib-events:test GREEN.

### 8.4-T07 — Implement PaymentExecutorService interface and command/result records  _(30 min)_
**Context:** payment-executor service (services/payment-executor/) is the orchestrator for both endpoints. Define PaymentExecutorService interface with: ExecutePaymentResult executeMpmPayment(ExecuteMpmCommand cmd) and IssueCpmTokenResult issueCpmToken(IssueCpmTokenCommand cmd). ExecuteMpmCommand record: partnerId String, quoteId String, merchantQr String, direction String, schemeId String, customerRef String, partnerTxnRef String, collectionAmount BigDecimal, collectionCurrency String, countryCode String, idempotencyKey String. IssueCpmTokenCommand record: partnerId String, schemeId String, direction String, customerRef String, partnerTxnRef String, countryCode String, prefundReserveUsd BigDecimal nullable, idempotencyKey String. ExecutePaymentResult and IssueCpmTokenResult contain all fields needed to build the API response DTOs. PaymentExecutorServiceImpl is @Service.
**Steps:** Create services/payment-executor/src/main/java/com/gme/pay/executor/PaymentExecutorService.java interface.; Create ExecuteMpmCommand, IssueCpmTokenCommand, ExecutePaymentResult, IssueCpmTokenResult as Java records in the same package. Use BigDecimal for all money fields.; Create PaymentExecutorServiceImpl @Service implementing the interface with method stubs throwing UnsupportedOperationException.; Confirm module compiles: ./gradlew :payment-executor:compileJava GREEN.
**Deliverable:** services/payment-executor/src/main/java/com/gme/pay/executor/PaymentExecutorService.java and supporting command/result records
**Acceptance / logic checks:**
- Interface compiles with both method signatures.
- ExecuteMpmCommand.collectionAmount is BigDecimal not String or double.
- ExecutePaymentResult.prefundDeductedUsd is BigDecimal nullable.
- IssueCpmTokenCommand.prefundReserveUsd is BigDecimal nullable (optional field from request).
- ./gradlew :payment-executor:compileJava GREEN.
**Depends on:** 8.4-T05, 8.4-T06

### 8.4-T08 — Implement IdempotencyService: Redis primary + Postgres fallback in payment-executor  _(50 min)_
**Context:** All POST endpoints must honour the Idempotency-Key header (UUID, 24h TTL). IdempotencyService stores (partner_id, idempotency_key, request_body_hash SHA-256 hex, response_status, response_body JSON) in Redis (key: idem:{partnerId}:{idempotencyKey}, TTL=86400s via RedisTemplate<String,String> SETNX+EXPIRE) and writes through to idempotency_cache Postgres table (V304). On duplicate: same key+same body hash -> return stored response (REPLAYED); same key+different body hash -> throw IdempotencyKeyReuseException (HTTP 422 IDEMPOTENCY_KEY_REUSE); in-flight duplicate (processing flag key with 30s TTL) -> throw InFlightDuplicateException (HTTP 409 X-Idempotency-Status: in_flight). Missing key -> throw MissingIdempotencyKeyException (HTTP 400). If Redis is unavailable, fall back to Postgres SELECT for duplicate check.
**Steps:** Create services/payment-executor/src/main/java/com/gme/pay/executor/idempotency/IdempotencyService.java @Service.; Implement checkOrReserve(partnerId, key, bodyHash): Redis SETNX in-flight flag (30s TTL); if key exists check stored hash; if match return stored response; if mismatch throw IdempotencyKeyReuseException.; Implement complete(partnerId, key, responseStatus, responseBody): store final response in Redis (86400s TTL) and write-through to idempotency_cache Postgres table.; Handle RedisException by falling back to Postgres idempotency_cache SELECT.; Write Testcontainers (Redis + Postgres) JUnit 5 test covering all 5 scenarios from API-05 §7.1.
**Deliverable:** services/payment-executor/src/main/java/com/gme/pay/executor/idempotency/IdempotencyService.java
**Acceptance / logic checks:**
- Testcontainers: first call returns REPLAYED=false (NEW); second call same key+same hash returns stored response (REPLAYED=true) without reprocessing.
- Same key + different body hash throws IdempotencyKeyReuseException.
- Concurrent duplicate within 30s processing window throws InFlightDuplicateException.
- Missing key (null/blank) throws MissingIdempotencyKeyException.
- Redis-down scenario: Postgres fallback SELECT prevents duplicate processing.
**Depends on:** 8.4-T04, 8.4-T07

### 8.4-T09 — Implement QuoteCache: fetch and validate rate quote from Redis in payment-executor  _(40 min)_
**Context:** Before executing MPM payment, the system validates the quote_id from POST /v1/payments. Rate quotes are stored in Redis by the rate-fx service (key: rate-quote:{quoteId}, TTL set from valid_until). Cached JSON blob contains: quoteId, partnerId, schemeId, direction, targetPayout BigDecimal, payoutCurrency, offerRate BigDecimal, sendAmount BigDecimal, serviceCharge BigDecimal, serviceChargeCurrency, collectionUsd BigDecimal nullable (null for domestic), payout_usd_cost BigDecimal nullable, collectionMarginUsd BigDecimal nullable, payoutMarginUsd BigDecimal nullable, crossRate BigDecimal nullable, validUntil Instant. QuoteCache @Component uses Spring Data Redis (RedisTemplate<String,String>). Validation: key exists (not TTL-expired), validUntil > now(), partnerId matches caller, schemeId and direction match request. Failures: RATE_QUOTE_EXPIRED (key missing or validUntil past) -> HTTP 422; RATE_QUOTE_INVALID (partner/scheme/direction mismatch) -> HTTP 422.
**Steps:** Create services/payment-executor/src/main/java/com/gme/pay/executor/quote/QuoteCache.java @Component with method RateQuoteSnapshot fetchAndValidate(quoteId, partnerId, schemeId, direction, Instant now).; Deserialize Redis JSON to RateQuoteSnapshot record using Jackson ObjectMapper.; Throw RateQuoteExpiredException if key absent or validUntil <= now.; Throw RateQuoteInvalidException if partnerId, schemeId, or direction mismatch.; Write Testcontainers Redis JUnit 5 test: valid quote returns snapshot; TTL-expired key throws expired; wrong partnerId throws invalid; direction mismatch throws invalid.
**Deliverable:** services/payment-executor/src/main/java/com/gme/pay/executor/quote/QuoteCache.java and RateQuoteSnapshot.java record
**Acceptance / logic checks:**
- Testcontainers Redis: valid quote with matching fields returns RateQuoteSnapshot with correct BigDecimal offerRate.
- TTL-expired key (manually deleted from Redis in test) throws RateQuoteExpiredException with code RATE_QUOTE_EXPIRED.
- Wrong partnerId throws RateQuoteInvalidException with code RATE_QUOTE_INVALID.
- Direction mismatch throws RateQuoteInvalidException.
- RateQuoteSnapshot.collectionUsd is null for domestic (same-currency) quotes stored with null value.
**Depends on:** 8.4-T07

### 8.4-T10 — Implement PrefundingPort interface and PrefundingClientAdapter in payment-executor  _(40 min)_
**Context:** OVERSEAS partners (partnerType=OVERSEAS) require atomic prefund deduction before scheme call. The prefunding-balance Spring Boot service owns the balance (PostgreSQL SELECT...FOR UPDATE). payment-executor calls prefunding-balance via internal REST (WebClient). Define PrefundingPort interface with deductBalance(partnerId String, amountUsd BigDecimal) returning PrefundDeductResult (newBalanceUsd BigDecimal) and releaseBalance(partnerId String, amountUsd BigDecimal). PrefundingClientAdapter implements PrefundingPort using WebClient to prefunding-balance service URL from application.yml property prefunding.service.url. HTTP 402 from prefunding-balance -> InsufficientPrefundingException (HTTP 402 INSUFFICIENT_PREFUNDING). LOCAL partners skip deduction entirely: partnerType is read from Config & Registry service (cached in Redis 5-min TTL). Deduction amount is quoteSnapshot.collectionUsd() (NOT the partner-supplied collectionAmount).
**Steps:** Create services/payment-executor/src/main/java/com/gme/pay/executor/prefunding/PrefundingPort.java interface.; Create PrefundingClientAdapter @Component implementing PrefundingPort using WebClient.; Implement deductBalance() calling POST to {prefunding.service.url}/internal/v1/prefunding/deduct; map 402 to InsufficientPrefundingException.; Implement releaseBalance() calling POST to {prefunding.service.url}/internal/v1/prefunding/release.; Write WireMock JUnit 5 test: stub deduct returning 200 -> succeeds; stub returning 402 -> InsufficientPrefundingException; LOCAL partner path -> zero WireMock calls.
**Deliverable:** services/payment-executor/src/main/java/com/gme/pay/executor/prefunding/PrefundingPort.java and PrefundingClientAdapter.java
**Acceptance / logic checks:**
- WireMock: 200 from prefunding service returns PrefundDeductResult with newBalanceUsd parsed correctly.
- WireMock: 402 from prefunding service throws InsufficientPrefundingException with code INSUFFICIENT_PREFUNDING.
- LOCAL partner path: WireMock deduct endpoint receives zero requests.
- Deduction amount passed to adapter is quoteSnapshot.collectionUsd(), not partnerCollectionAmount.
- Property prefunding.service.url injected from application.yml (no hardcoded URL).
**Depends on:** 8.4-T09

### 8.4-T11 — Implement SchemeAdapterPort and ZeroPaySchemeAdapterClient in payment-executor  _(40 min)_
**Context:** payment-executor calls the Scheme Adapter Layer (Anti-Corruption Layer) to submit MPM transactions to ZeroPay. Define SchemeAdapterPort interface with: SchemeCommitResult commitMpmPayment(CommitMpmCommand cmd) and PrepareTokenResult generateCpmToken(GenerateCpmTokenCommand cmd). CommitMpmCommand record: paymentId, merchantQr, targetPayout BigDecimal, payoutCurrency, schemeId. SchemeCommitResult record: schemeTxnId String, status enum(APPROVED,DECLINED,UNCERTAIN), approvedAt Instant nullable. GenerateCpmTokenCommand record: paymentId, partnerId, schemeId, direction. PrepareTokenResult record: prepareToken, qrContent, expiresAt Instant. ZeroPaySchemeAdapterClient @Component uses WebClient targeting scheme-adapter.zeropay.url from application.yml. HTTP 200 -> APPROVED; HTTP 202 -> UNCERTAIN; HTTP 4xx/5xx -> DECLINED. Scheme is NEVER called without prior successful prefund deduction.
**Steps:** Create SchemeAdapterPort interface and ZeroPaySchemeAdapterClient @Component in services/payment-executor/src/main/java/com/gme/pay/executor/scheme/.; Implement commitMpmPayment() using WebClient POST to {scheme-adapter.zeropay.url}/internal/v1/zeropay/commit.; Implement generateCpmToken() using WebClient POST to {scheme-adapter.zeropay.url}/internal/v1/zeropay/cpm/generate.; Map HTTP status codes to SchemeCommitResult.status enum as described.; Write WireMock JUnit 5 test: 200 -> APPROVED with schemeTxnId; 202 -> UNCERTAIN; 500 -> DECLINED.
**Deliverable:** services/payment-executor/src/main/java/com/gme/pay/executor/scheme/SchemeAdapterPort.java and ZeroPaySchemeAdapterClient.java
**Acceptance / logic checks:**
- WireMock: 200 response maps to APPROVED with correct schemeTxnId.
- WireMock: 202 response maps to UNCERTAIN status.
- WireMock: 500 response maps to DECLINED with failure code SCHEME_UNAVAILABLE.
- Property scheme-adapter.zeropay.url injected from application.yml.
- generateCpmToken() WireMock: 200 response returns PrepareTokenResult with non-null prepareToken and expiresAt.
**Depends on:** 8.4-T10

### 8.4-T12 — Implement rate-lock commit and payment_events insertion in PaymentExecutorServiceImpl  _(50 min)_
**Context:** At POST /v1/payments commit (after scheme APPROVED), payment-executor permanently records all USD pool values from RateQuoteSnapshot. These are: offerRate, sendAmount, serviceCharge, collectionUsd, payoutUsdCost, collectionMarginUsd, payoutMarginUsd, crossRate. Once committed, later treasury or margin changes NEVER affect this record (rate-lock invariant). The payments row status transitions to APPROVED; approvedAt is set to scheme approvedAt. Four payment_events rows are inserted in the same @Transactional block: PREFUND_DEDUCTED (OVERSEAS only), SCHEME_SUBMITTED, SCHEME_APPROVED, TRANSACTION_COMMITTED. For UNCERTAIN: status=UNCERTAIN, insert only SCHEME_SUBMITTED; no TRANSACTION_COMMITTED. For domestic (same-currency) quotes: collectionUsd, payoutUsdCost, collectionMarginUsd, payoutMarginUsd are null.
**Steps:** In PaymentExecutorServiceImpl, implement the commit step in a @Transactional method: save payments row with all 9 rate fields from RateQuoteSnapshot; insert payment_events rows.; Use PaymentRepository (Spring Data JPA @Repository) to persist payments.; Use PaymentEventsRepository to batch-insert payment_events rows.; For UNCERTAIN result: set payments.status=UNCERTAIN; insert only SCHEME_SUBMITTED event.; Write Testcontainers Postgres JUnit 5 test: full OVERSEAS approved flow; assert payments row has all 9 rate fields and 4 payment_events rows; verify pool identity: collectionUsd - collectionMarginUsd - payoutMarginUsd == payoutUsdCost within 0.01 USD tolerance.
**Deliverable:** PaymentRepository.java, PaymentEventsRepository.java, and rate-lock commit logic in services/payment-executor/src/main/java/com/gme/pay/executor/PaymentExecutorServiceImpl.java
**Acceptance / logic checks:**
- Testcontainers Postgres: approved OVERSEAS payment row has non-null collectionUsd, payoutUsdCost, collectionMarginUsd, payoutMarginUsd.
- Pool identity: collectionUsd.subtract(collectionMarginUsd).subtract(payoutMarginUsd).compareTo(payoutUsdCost) within 0.01 USD.
- payment_events has 4 rows in order: PREFUND_DEDUCTED, SCHEME_SUBMITTED, SCHEME_APPROVED, TRANSACTION_COMMITTED.
- UNCERTAIN flow: payments.status=UNCERTAIN; only 1 payment_events row (SCHEME_SUBMITTED).
- Domestic flow: collectionUsd null in persisted row.
**Depends on:** 8.4-T11, 8.4-T03

### 8.4-T13 — Implement prefund reversal on scheme DECLINED/exception in PaymentExecutorServiceImpl  _(45 min)_
**Context:** If scheme adapter returns DECLINED or throws after prefund deduction succeeded, the deducted amount must be reversed via PrefundingPort.releaseBalance(partnerId, amountUsd). Insert SCHEME_DECLINED event, then PREFUND_REVERSED event. Set payments.status=FAILED. If reversal itself fails (PrefundingPort.releaseBalance throws), insert REVERSAL_FAILED event, set status=FAILED, publish PrefundReversalFailedEvent to outbox (ops alert). For UNCERTAIN status: do NOT reverse; hold deduction until reconciliation resolves. For LOCAL partners: no reversal call (no deduction was made).
**Steps:** In PaymentExecutorServiceImpl.executeMpmPayment(), wrap scheme adapter call in try/catch: on DECLINED result or exception, call prefundingPort.releaseBalance().; Set payments.status=FAILED; insert SCHEME_DECLINED then PREFUND_REVERSED events in @Transactional.; On reversal failure: catch exception; insert REVERSAL_FAILED event; publish PrefundReversalFailedEvent via EventPublisher outbox.; UNCERTAIN branch: set payments.status=UNCERTAIN; do NOT call releaseBalance.; Write WireMock JUnit 5 tests: (a) DECLINED -> releaseBalance called with correct amountUsd; (b) UNCERTAIN -> releaseBalance not called; (c) DECLINED + reversal 500 -> REVERSAL_FAILED event inserted.
**Deliverable:** Reversal and UNCERTAIN handling logic in services/payment-executor/src/main/java/com/gme/pay/executor/PaymentExecutorServiceImpl.java
**Acceptance / logic checks:**
- WireMock: DECLINED scheme triggers releaseBalance with amountUsd equal to quoteSnapshot.collectionUsd().
- UNCERTAIN scheme: releaseBalance not called (WireMock verifies 0 requests).
- Reversal failure path: REVERSAL_FAILED event row in payment_events; status=FAILED.
- LOCAL partner DECLINED: releaseBalance not called (no deduction was made).
- SCHEME_DECLINED event inserted before PREFUND_REVERSED event (order matters for audit trail).
**Depends on:** 8.4-T12

### 8.4-T14 — Implement outbox event publishing via OutboxEventPublisher and OutboxPoller  _(50 min)_
**Context:** Phase 1 uses the transactional Outbox pattern (lib-events EventPublisher interface). The EventPublisher interface has publish(DomainEvent event). OutboxEventPublisher @Component implements EventPublisher and writes to outbox_events table (V304 migration) inside the caller's active @Transactional context using Spring TransactionSynchronizationManager. This ensures event write and business row change are atomic. OutboxPoller @Component has @Scheduled(fixedDelay=1000) that queries UNPUBLISHED rows ordered by created_at, calls publishExternal() (Phase 1: SLF4J INFO log; Phase 2: Kafka producer behind same interface), updates status=PUBLISHED and sets published_at. PaymentExecutorServiceImpl injects EventPublisher and calls publish() for all events.
**Steps:** Create services/payment-executor/src/main/java/com/gme/pay/executor/outbox/OutboxEventPublisher.java @Component implementing EventPublisher; serialize DomainEvent to JSON via Jackson; insert outbox_events row via JdbcTemplate within current transaction.; Create OutboxPoller @Component with @Scheduled(fixedDelay=1000); query up to 100 UNPUBLISHED rows; for each: call publishExternal (log); update status=PUBLISHED and published_at=NOW().; Inject EventPublisher into PaymentExecutorServiceImpl; call publish() for PaymentInitiatedEvent, PrefundDeductedEvent, SchemeSubmittedEvent, PaymentApprovedEvent/PaymentFailedEvent, CpmTokenIssuedEvent.; Write Testcontainers Postgres JUnit 5 test: execute MPM payment; assert outbox_events has PaymentInitiatedEvent + PrefundDeductedEvent in UNPUBLISHED state before poller runs; run poller manually; assert status=PUBLISHED.; Verify rollback atomicity: if payments.save() fails (simulate by throwing in @Transactional), outbox_events has no rows.
**Deliverable:** services/payment-executor/src/main/java/com/gme/pay/executor/outbox/OutboxEventPublisher.java and OutboxPoller.java
**Acceptance / logic checks:**
- Testcontainers: outbox_events row inserted in same transaction as payments row (rollback of payment removes outbox row).
- Poller updates status=PUBLISHED and published_at on processed rows.
- OutboxEventPublisher.publish() within active @Transactional does not open a new transaction (uses existing connection).
- All 6 event types from 8.4-T06 produce correct JSON in payload column.
- Index on (status, created_at) used by poller query (EXPLAIN shows index scan).
**Depends on:** 8.4-T06, 8.4-T12, 8.4-T04

### 8.4-T15 — Implement CPM token generation logic in PaymentExecutorServiceImpl (issueCpmToken)  _(50 min)_
**Context:** POST /v1/payments/cpm/generate orchestration: (1) IdempotencyService check, (2) validate scheme+direction active via Config & Registry service (cached in Redis), (3) for OVERSEAS: call PrefundingPort.deductBalance with prefundReserveUsd from request OR scheme max-limit from Config Registry if omitted (default $200.00 per ZeroPay scheme config), (4) call SchemeAdapterPort.generateCpmToken() returning PrepareTokenResult, (5) in single @Transactional: save payments row (status=CPM_PENDING) + cpm_tokens row (status=ACTIVE, expires_at=now+tokenTtlSeconds) + outbox CpmTokenIssuedEvent, (6) return IssueCpmTokenResult. Token TTL configurable per partner in Redis config key cpm.token.ttl.{partnerId} (default 60s). If prefund deduction succeeds but scheme generate fails, reverse deduction and throw.
**Steps:** Implement issueCpmToken(IssueCpmTokenCommand cmd) in PaymentExecutorServiceImpl with the 6 steps above.; Create CpmTokenRepository @Repository (Spring Data JPA) with save() and findByPrepareToken().; For OVERSEAS: call prefundingPort.deductBalance(partnerId, effectiveReserveUsd) before scheme call.; In @Transactional: save both payments and cpm_tokens rows; call eventPublisher.publish(CpmTokenIssuedEvent).; Write Testcontainers Postgres + WireMock JUnit 5 test: OVERSEAS partner generates token; assert cpm_tokens row status=ACTIVE, expires_at within now+65s, payments row status=CPM_PENDING, outbox has CpmTokenIssuedEvent.
**Deliverable:** issueCpmToken() implementation in services/payment-executor/src/main/java/com/gme/pay/executor/PaymentExecutorServiceImpl.java and CpmTokenRepository.java
**Acceptance / logic checks:**
- Testcontainers: cpm_tokens row exists with status=ACTIVE after successful issuance.
- cpm_tokens.expires_at == now + tokenTtlSeconds (within 2s tolerance; default 60s).
- payments row inserted with status=CPM_PENDING and pre-assigned payment_id.
- WireMock: prefunding-balance deduct called once with correct amountUsd for OVERSEAS partner.
- outbox_events has 1 CpmTokenIssuedEvent row in UNPUBLISHED status in same transaction.
**Depends on:** 8.4-T14, 8.4-T11

### 8.4-T16 — Implement POST /v1/payments @RestController in payment-executor  _(45 min)_
**Context:** PaymentController @RestController in services/payment-executor/ exposes POST /v1/payments. Accepts PaymentExecuteRequest DTO (lib-api-contracts) with @Valid Jakarta Bean Validation. Partner context (partnerId) extracted from Spring Security SecurityContextHolder (set by HmacSignatureFilter after verification). Idempotency-Key extracted from HttpServletRequest header. Delegates to PaymentExecutorService.executeMpmPayment(). Maps ExecutePaymentResult to PaymentExecuteResponse DTO. HTTP 201 on APPROVED; HTTP 202 on UNCERTAIN; HTTP 402 on InsufficientPrefundingException; HTTP 422 on RateQuoteExpiredException (RATE_QUOTE_EXPIRED) or RateQuoteInvalidException; HTTP 409 on InFlightDuplicateException; HTTP 422 on IdempotencyKeyReuseException. X-Request-ID header set via RequestIdFilter from lib-errors. lib-errors canonical error envelope applied to all 4xx/5xx.
**Steps:** Create services/payment-executor/src/main/java/com/gme/pay/executor/api/PaymentController.java @RestController @RequestMapping(/v1).; Implement POST /payments method with @Valid @RequestBody PaymentExecuteRequest and Idempotency-Key header extraction.; Map all business exceptions to correct HTTP status and lib-errors error envelope using @ExceptionHandler.; Return ResponseEntity<PaymentExecuteResponse> with status 201 or 202.; Write @WebMvcTest slice test: mock PaymentExecutorService; valid request -> 201 with paymentId; missing collectionAmount -> 400 VALIDATION_ERROR with details; InsufficientPrefundingException -> 402; missing Idempotency-Key header -> 400 MISSING_IDEMPOTENCY_KEY.
**Deliverable:** services/payment-executor/src/main/java/com/gme/pay/executor/api/PaymentController.java
**Acceptance / logic checks:**
- @WebMvcTest: valid body + mocked APPROVED result returns HTTP 201 with payment_id in response JSON.
- @WebMvcTest: missing required field collection_amount returns HTTP 400 with error.code=VALIDATION_ERROR and non-empty details array.
- @WebMvcTest: InsufficientPrefundingException returns HTTP 402 with error.code=INSUFFICIENT_PREFUNDING.
- @WebMvcTest: missing Idempotency-Key header returns HTTP 400 MISSING_IDEMPOTENCY_KEY.
- X-Request-ID present in response headers on all responses.
**Depends on:** 8.4-T08, 8.4-T15

### 8.4-T17 — Implement POST /v1/payments/cpm/generate @RestController in payment-executor  _(35 min)_
**Context:** CpmController (or extend PaymentController) exposes POST /v1/payments/cpm/generate. Accepts CpmGenerateRequest DTO (lib-api-contracts) with @Valid. Partner context from Spring Security. Idempotency-Key from header. Delegates to PaymentExecutorService.issueCpmToken(). Maps IssueCpmTokenResult to CpmGenerateResponse DTO. HTTP 201 on success; HTTP 402 on InsufficientPrefundingException; HTTP 422 on scheme unavailable (SchemeUnavailableException). prefundReserveUsd in request is optional String decimal; parse to BigDecimal if present; pass null to command if absent. Rate limiting at 50 req/s per partner enforced at Spring Cloud Gateway layer (not in this service).
**Steps:** Create or extend controller at /v1/payments/cpm/generate in services/payment-executor/src/main/java/com/gme/pay/executor/api/.; Parse optional prefundReserveUsd String to BigDecimal if present; leave null if absent.; Map IssueCpmTokenResult to CpmGenerateResponse; return ResponseEntity with HTTP 201.; Map InsufficientPrefundingException to 402; SchemeUnavailableException to 422 SCHEME_UNAVAILABLE.; Write @WebMvcTest: valid request returns 201 with cpmTokenId, prepareToken, expiresAt; missing customerRef returns 400; absent prefundReserveUsd returns 201 (optional field).
**Deliverable:** services/payment-executor/src/main/java/com/gme/pay/executor/api/CpmController.java (or method in PaymentController)
**Acceptance / logic checks:**
- @WebMvcTest: 201 response body contains cpm_token_id, prepare_token, expires_at, payment_id.
- @WebMvcTest: missing required field customer_ref returns 400 VALIDATION_ERROR.
- @WebMvcTest: absent prefund_reserve_usd (optional) returns 201 without 400.
- @WebMvcTest: InsufficientPrefundingException maps to 402 INSUFFICIENT_PREFUNDING.
- Response Content-Type is application/json;charset=UTF-8.
**Depends on:** 8.4-T16

### 8.4-T18 — Implement HMAC-SHA256 request signing filter in payment-executor (Spring Security)  _(55 min)_
**Context:** All inbound Partner API requests verified by HmacSignatureFilter extends OncePerRequestFilter. Canonical string: {HTTP_METHOD}\n{PATH_WITH_QUERY}\n{X-Timestamp}\n{SHA256_HEX_OF_BODY}. Required headers: X-API-Key, X-Timestamp, X-Signature. Reject conditions: |serverTime - X-Timestamp| > 300s -> HTTP 401 TIMESTAMP_OUT_OF_RANGE; HMAC mismatch -> HTTP 401 INVALID_SIGNATURE; X-API-Key unknown -> HTTP 401 INVALID_API_KEY. API secret fetched from Config & Registry service (cached in Redis 5-min TTL keyed by X-API-Key). Filter wraps request in CachedBodyHttpServletRequestWrapper so @RequestBody can still be read downstream. Filter registered before UsernamePasswordAuthenticationFilter in SecurityFilterChain @Configuration.
**Steps:** Create services/payment-executor/src/main/java/com/gme/pay/executor/security/HmacSignatureFilter.java extending OncePerRequestFilter.; Create CachedBodyHttpServletRequestWrapper to buffer request body bytes.; Fetch API secret via PartnerCredentialService @Component (Redis-cached, WebClient fallback to Config Registry service).; Compute HMAC-SHA256 of canonical string; compare with X-Signature header in constant-time (MessageDigest.isEqual).; Write @SpringBootTest integration test: valid signed request -> 201; tampered body -> 401 INVALID_SIGNATURE; X-Timestamp +301s -> 401 TIMESTAMP_OUT_OF_RANGE; missing X-API-Key -> 401 INVALID_API_KEY.
**Deliverable:** services/payment-executor/src/main/java/com/gme/pay/executor/security/HmacSignatureFilter.java and SecurityConfig.java
**Acceptance / logic checks:**
- Valid HMAC-signed request reaches @RestController (returns 201 in integration test).
- Body tampered post-signing returns 401 INVALID_SIGNATURE.
- X-Timestamp more than 300s in past returns 401 TIMESTAMP_OUT_OF_RANGE.
- Missing X-API-Key header returns 401 INVALID_API_KEY.
- CachedBodyHttpServletRequestWrapper allows @RequestBody to be read after filter has consumed the stream (controller receives correct body).
**Depends on:** 8.4-T17

### 8.4-T19 — Flyway migration V201: prefunding_accounts and prefunding_ledger tables in prefunding-balance service  _(25 min)_
**Context:** The prefunding-balance Spring Boot service (services/prefunding-balance/) owns OVERSEAS partner balances in PostgreSQL 16. prefunding_accounts: partner_id TEXT PK, balance_usd NUMERIC(20,8) NOT NULL DEFAULT 0 CHECK(balance_usd >= 0), low_balance_threshold_usd NUMERIC(20,8) NOT NULL DEFAULT 500, updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(). prefunding_ledger: id BIGSERIAL PK, partner_id TEXT NOT NULL REFERENCES prefunding_accounts(partner_id), payment_id TEXT NOT NULL, amount_usd NUMERIC(20,8) NOT NULL, direction TEXT NOT NULL CHECK(direction IN ('DEBIT','CREDIT')), balance_after_usd NUMERIC(20,8) NOT NULL, event_at TIMESTAMPTZ NOT NULL DEFAULT NOW(). Index on (partner_id, event_at DESC). The deduction must use UPDATE ... WHERE balance_usd >= amount (no SELECT FOR UPDATE needed at application layer; Postgres row-level locking from single UPDATE is sufficient).
**Steps:** Create services/prefunding-balance/src/main/resources/db/migration/V201__create_prefunding_accounts_table.sql.; Create V202__create_prefunding_ledger_table.sql with FK to prefunding_accounts.; Add CHECK(balance_usd >= 0) to prevent balance going negative at DB level.; Add CHECK(direction IN ('DEBIT','CREDIT')) to prefunding_ledger.; Run flyway:migrate on local Postgres port 5433 and confirm both tables.
**Deliverable:** services/prefunding-balance/src/main/resources/db/migration/V201__create_prefunding_accounts_table.sql and V202__create_prefunding_ledger_table.sql
**Acceptance / logic checks:**
- Both migrations run cleanly.
- CHECK(balance_usd >= 0) rejects UPDATE that would set balance below zero.
- FK on prefunding_ledger.partner_id enforced.
- direction CHECK rejects 'UNKNOWN' value.
- Index on (partner_id, event_at DESC) present in pg_indexes.

### 8.4-T20 — Implement PrefundingBalanceService with atomic deduction (UPDATE WHERE) in prefunding-balance  _(50 min)_
**Context:** PrefundingBalanceService @Service in services/prefunding-balance/ implements deductBalance(partnerId, amountUsd) and releaseBalance(partnerId, amountUsd). Atomic deduction SQL: UPDATE prefunding_accounts SET balance_usd = balance_usd - :amount, updated_at = NOW() WHERE partner_id = :partnerId AND balance_usd >= :amount; if 0 rows updated -> throw InsufficientPrefundingException (HTTP 402). No SELECT FOR UPDATE needed: single-row UPDATE with WHERE clause provides atomicity under Postgres MVCC. After deduction: insert DEBIT row into prefunding_ledger in same @Transactional. After deduction: if new balance < low_balance_threshold_usd -> publish LowBalanceAlertEvent to outbox_events (same transaction). releaseBalance() is inverse: UPDATE SET balance_usd = balance_usd + :amount; insert CREDIT ledger row.
**Steps:** Create services/prefunding-balance/src/main/java/com/gme/pay/prefunding/PrefundingBalanceService.java @Service.; Implement deductBalance using @Modifying @Query native SQL UPDATE with WHERE balance_usd >= :amount.; After successful UPDATE: insert prefunding_ledger DEBIT row; check new balance against threshold; if below threshold insert LowBalanceAlertEvent into outbox_events.; Implement releaseBalance using UPDATE SET balance_usd = balance_usd + :amount; insert CREDIT ledger row.; Write Testcontainers Postgres JUnit 5 concurrent test: 2 threads each deducting 60 from balance=100; assert exactly 1 succeeds and 1 throws InsufficientPrefundingException; final balance=40.00.
**Deliverable:** services/prefunding-balance/src/main/java/com/gme/pay/prefunding/PrefundingBalanceService.java
**Acceptance / logic checks:**
- Testcontainers concurrent test: exactly 1 of 2 threads succeeds; balance_usd=40.00 after; 1 prefunding_ledger DEBIT row.
- 0-rows-updated (balance < amount) throws InsufficientPrefundingException with code INSUFFICIENT_PREFUNDING.
- prefunding_ledger insert and balance_usd UPDATE are in same @Transactional (rollback test: simulate exception after UPDATE; balance_usd unchanged).
- LowBalanceAlertEvent inserted to outbox when newBalance < threshold (test with balance=100, threshold=200, deduct 80).
- releaseBalance() increases balance_usd and inserts CREDIT ledger row.
**Depends on:** 8.4-T19, 8.4-T06

### 8.4-T21 — Expose internal deduct/release REST endpoints in prefunding-balance service  _(35 min)_
**Context:** payment-executor calls prefunding-balance via internal REST. Expose: POST /internal/v1/prefunding/deduct with body {partnerId, amountUsd} -> 200 {newBalanceUsd} or 402 {error}; POST /internal/v1/prefunding/release with body {partnerId, paymentId, amountUsd} -> 200 {newBalanceUsd}. These endpoints are on a non-public port (internal Spring Boot management port or separate server.port) not routed via Spring Cloud Gateway. Use Spring @RestController; no HMAC auth needed (internal network only); secure via NetworkPolicy at Kubernetes level. Both endpoints are @Transactional via service calls.
**Steps:** Create services/prefunding-balance/src/main/java/com/gme/pay/prefunding/api/PrefundingInternalController.java @RestController @RequestMapping(/internal/v1/prefunding).; Implement POST /deduct delegating to PrefundingBalanceService.deductBalance(); return 200 {newBalanceUsd} or 402 error envelope.; Implement POST /release delegating to PrefundingBalanceService.releaseBalance(); return 200 {newBalanceUsd}.; Write @WebMvcTest: mock PrefundingBalanceService; deduct 200 response has newBalanceUsd; deduct throws InsufficientPrefundingException -> 402; release 200 response.; Confirm internal port configuration in application.yml (server.port=8080, management.server.port=8081 or separate internal port).
**Deliverable:** services/prefunding-balance/src/main/java/com/gme/pay/prefunding/api/PrefundingInternalController.java
**Acceptance / logic checks:**
- @WebMvcTest: POST /internal/v1/prefunding/deduct with valid body returns 200 with newBalanceUsd.
- @WebMvcTest: deduct when service throws InsufficientPrefundingException returns 402 with error.code=INSUFFICIENT_PREFUNDING.
- @WebMvcTest: POST /internal/v1/prefunding/release returns 200 with newBalanceUsd.
- Endpoint not registered under /v1 (no Spring Cloud Gateway route to it).
- ./gradlew :prefunding-balance:build GREEN.
**Depends on:** 8.4-T20

### 8.4-T22 — Spring Cloud Gateway routes for /v1/payments and /v1/payments/cpm/generate with Redis rate limiting  _(45 min)_
**Context:** Spring Cloud Gateway (services/api-gateway/) routes inbound Partner API calls to payment-executor. Add route definitions for POST /v1/payments (id=payments-mpm) and POST /v1/payments/cpm/generate (id=payments-cpm-generate). Each route applies: (1) RequestRateLimiter GatewayFilter using Redis rate limiter (redis-rate-limiter.replenishRate=50, burstCapacity=50) keyed by X-API-Key via PartnerKeyResolver @Bean; (2) AddResponseHeader filter for X-Request-ID if not already set. Routes forward to lb://payment-executor via Spring Cloud LoadBalancer. Rate limit breach returns 429 with Retry-After header. Limits configurable per partner by GME Ops (Redis key: rate-limit.{partnerId}).
**Steps:** Edit services/api-gateway/src/main/resources/application.yml: add two route entries under spring.cloud.gateway.routes.; Create services/api-gateway/src/main/java/com/gme/pay/gateway/PartnerKeyResolver.java implementing KeyResolver; extract X-API-Key header; return Mono<String>.; Configure RequestRateLimiter filter in each route with redis-rate-limiter bean.; Write @SpringBootTest gateway slice test with WireMock payment-executor stub: POST /v1/payments proxied; 51st request within 1s returns 429.; Confirm lb://payment-executor resolves via Spring Cloud LoadBalancer in application.yml.
**Deliverable:** services/api-gateway/src/main/resources/application.yml (routes for payments-mpm and payments-cpm-generate) and PartnerKeyResolver.java
**Acceptance / logic checks:**
- POST /v1/payments route forwards to lb://payment-executor; WireMock stub response returned to caller.
- POST /v1/payments/cpm/generate route forwards to lb://payment-executor.
- 51st request from same X-API-Key within 1s returns HTTP 429 with Retry-After header.
- X-Request-ID header appears in all gateway responses.
- PartnerKeyResolver returns X-API-Key header value as rate-limit key (tested via unit test).
**Depends on:** 8.4-T17

### 8.4-T23 — Unit tests: POST /v1/payments OVERSEAS inbound MPM happy path  _(35 min)_
**Context:** Full unit test for OVERSEAS partner (SendMN, type=OVERSEAS) MPM payment. Quote: targetPayout=50000 KRW, offerRate=0.000703, sendAmount=35.06 USD, serviceCharge=0.35 USD, serviceChargeCurrency=USD, collectionUsd=36.37 USD, payoutUsdCost=35.06 USD, collectionMarginUsd=0.91 USD, payoutMarginUsd=0.40 USD. Prefund deduction amount = collectionUsd = 36.37 USD. Scheme approved. Expected result: status=APPROVED, prefundDeductedUsd=36.37, schemeTxnId non-null. Pool identity invariant: 36.37 - 0.91 - 0.40 == 35.06 (within 0.01 USD). Uses Mockito mocks for all ports.
**Steps:** Create services/payment-executor/src/test/java/com/gme/pay/executor/MpmPaymentOverseasHappyPathTest.java with @ExtendWith(MockitoExtension.class).; Seed QuoteCache mock to return RateQuoteSnapshot with values above.; Mock PrefundingPort.deductBalance to return PrefundDeductResult(newBalanceUsd=48200.00).; Mock SchemeAdapterPort.commitMpmPayment to return SchemeCommitResult(status=APPROVED, schemeTxnId=ZP20260604093115001234).; Assert result.status()==APPROVED; result.prefundDeductedUsd()==36.37; pool identity assertion.
**Deliverable:** services/payment-executor/src/test/java/com/gme/pay/executor/MpmPaymentOverseasHappyPathTest.java
**Acceptance / logic checks:**
- Test GREEN: result.status == APPROVED.
- PrefundingPort.deductBalance called with BigDecimal 36.37 (collectionUsd, not 35.77 collectionAmount).
- result.prefundDeductedUsd == 36.37.
- Pool identity: 36.37 - 0.91 - 0.40 == 35.06 asserted with abs(diff) <= 0.01.
- SchemeAdapterPort.commitMpmPayment called exactly once.
**Depends on:** 8.4-T12

### 8.4-T24 — Unit tests: POST /v1/payments LOCAL partner domestic same-currency short-circuit  _(25 min)_
**Context:** LOCAL partner (GME Remit, type=LOCAL) paying KRW -> KRW. Same-currency short-circuit: collectionUsd null, no USD pool. serviceCharge=500 KRW. targetPayout=15000 KRW. No prefunding deduction (LOCAL). Expected: PrefundingPort.deductBalance never called; result.prefundDeductedUsd null; scheme called with targetPayout=15000 KRW; result.status==APPROVED.
**Steps:** Create services/payment-executor/src/test/java/com/gme/pay/executor/MpmPaymentLocalPartnerTest.java.; Mock partner config to return partnerType=LOCAL.; Mock QuoteCache to return RateQuoteSnapshot with collectionUsd=null and serviceCharge=BigDecimal(500) KRW.; Assert Mockito verify(prefundingPort, never()).deductBalance(any(), any()).; Assert result.prefundDeductedUsd == null.
**Deliverable:** services/payment-executor/src/test/java/com/gme/pay/executor/MpmPaymentLocalPartnerTest.java
**Acceptance / logic checks:**
- PrefundingPort.deductBalance invoked zero times.
- result.prefundDeductedUsd == null.
- SchemeAdapterPort.commitMpmPayment called once with targetPayout=BigDecimal(15000) KRW.
- result.status == APPROVED.
- Test GREEN.
**Depends on:** 8.4-T12

### 8.4-T25 — Unit tests: POST /v1/payments — insufficient prefunding and expired quote rejections  _(25 min)_
**Context:** Two rejection unit tests. (A) Insufficient prefunding: OVERSEAS partner, collectionUsd=200.00, available balance=100.00. PrefundingPort.deductBalance throws InsufficientPrefundingException. Expected: exception propagates; SchemeAdapterPort never called; PaymentRepository.save never called. (B) Expired quote: QuoteCache.fetchAndValidate throws RateQuoteExpiredException (validUntil in past). Expected: exception propagates before PrefundingPort is called; both ports called zero times.
**Steps:** Create services/payment-executor/src/test/java/com/gme/pay/executor/MpmPaymentRejectionTest.java with two @Test methods.; Test A: mock PrefundingPort.deductBalance to throw InsufficientPrefundingException; assert exception thrown; verify SchemeAdapterPort.commitMpmPayment never called; verify PaymentRepository.save never called.; Test B: mock QuoteCache.fetchAndValidate to throw RateQuoteExpiredException; assert exception thrown; verify PrefundingPort.deductBalance never called.; Both tests use @ExtendWith(MockitoExtension.class).
**Deliverable:** services/payment-executor/src/test/java/com/gme/pay/executor/MpmPaymentRejectionTest.java
**Acceptance / logic checks:**
- Test A: InsufficientPrefundingException with code INSUFFICIENT_PREFUNDING thrown.
- Test A: SchemeAdapterPort invocation count = 0.
- Test B: RateQuoteExpiredException with code RATE_QUOTE_EXPIRED thrown.
- Test B: PrefundingPort.deductBalance invocation count = 0.
- Both tests GREEN.
**Depends on:** 8.4-T12

### 8.4-T26 — Unit tests: POST /v1/payments — scheme DECLINED triggers prefund reversal  _(30 min)_
**Context:** OVERSEAS partner: prefund deduction succeeds (200), scheme adapter returns DECLINED. Expected: PrefundingPort.releaseBalance called with exact amountUsd=36.37 (collectionUsd); payments status=FAILED; payment_events contains SCHEME_DECLINED then PREFUND_REVERSED; SchemeAdapterPort called once. Second test: UNCERTAIN result -> releaseBalance NOT called; status=UNCERTAIN.
**Steps:** Create services/payment-executor/src/test/java/com/gme/pay/executor/MpmPaymentSchemeResultTest.java with two @Test methods.; Test DECLINED: mock deductBalance success; mock commitMpmPayment to return DECLINED; assert releaseBalance called with BigDecimal(36.37); assert persisted status=FAILED; assert event_types captured include SCHEME_DECLINED and PREFUND_REVERSED.; Test UNCERTAIN: mock commitMpmPayment to return UNCERTAIN; assert releaseBalance NOT called; assert status=UNCERTAIN.
**Deliverable:** services/payment-executor/src/test/java/com/gme/pay/executor/MpmPaymentSchemeResultTest.java
**Acceptance / logic checks:**
- DECLINED: PrefundingPort.releaseBalance called once with amountUsd=36.37.
- DECLINED: persisted payment status = FAILED.
- DECLINED: event list includes SCHEME_DECLINED before PREFUND_REVERSED.
- UNCERTAIN: releaseBalance invocation count = 0.
- UNCERTAIN: persisted payment status = UNCERTAIN.
**Depends on:** 8.4-T13

### 8.4-T27 — Unit tests: POST /v1/payments/cpm/generate — OVERSEAS happy path and insufficient-funds rejection  _(35 min)_
**Context:** Two CPM generation unit tests. (A) OVERSEAS happy path: prefundReserveUsd omitted (default 200.00 USD from Config Registry). Mock deductBalance success; mock generateCpmToken returns prepareToken=ZP-CPM-TEST123, expiresAt=now+60s. Expected: result.prefundReservedUsd=200.00; result.prepareToken=ZP-CPM-TEST123; cpm_tokens saved with status=ACTIVE; outbox has CpmTokenIssuedEvent. (B) Insufficient funds: deductBalance throws InsufficientPrefundingException. Expected: exception propagates; generateCpmToken never called; no cpm_tokens row saved.
**Steps:** Create services/payment-executor/src/test/java/com/gme/pay/executor/CpmGenerateTest.java with two @Test methods.; Test A: mock ConfigRegistry returning schemeMaxLimitUsd=200.00; mock deductBalance success; mock generateCpmToken returning PrepareTokenResult; assert result fields.; Test B: mock deductBalance to throw InsufficientPrefundingException; verify generateCpmToken never called; verify CpmTokenRepository.save never called.
**Deliverable:** services/payment-executor/src/test/java/com/gme/pay/executor/CpmGenerateTest.java
**Acceptance / logic checks:**
- Test A: result.prefundReservedUsd == 200.00 BigDecimal.
- Test A: result.prepareToken == ZP-CPM-TEST123.
- Test A: CpmTokenRepository.save argument has status=ACTIVE and expiresAt within now+65s.
- Test B: InsufficientPrefundingException with code INSUFFICIENT_PREFUNDING propagates.
- Test B: SchemeAdapterPort.generateCpmToken invocation count = 0.
**Depends on:** 8.4-T15

### 8.4-T28 — Unit tests: idempotency scenarios for POST /v1/payments  _(30 min)_
**Context:** Four idempotency unit tests using Mockito-mocked IdempotencyService. (A) Duplicate same key+body: first call processes; second call returns stored response (IdempotencyService returns REPLAYED); PrefundingPort called exactly 1 time total. (B) Same key+different body: IdempotencyService throws IdempotencyKeyReuseException -> HTTP 422. (C) In-flight: IdempotencyService throws InFlightDuplicateException -> HTTP 409. (D) Missing key: IdempotencyService throws MissingIdempotencyKeyException -> HTTP 400.
**Steps:** Create services/payment-executor/src/test/java/com/gme/pay/executor/IdempotencyTest.java with four @Test methods.; Test A: first call checkOrReserve returns NEW; service processes; second call checkOrReserve returns REPLAYED stored response; assert PrefundingPort.deductBalance total count=1.; Test B: checkOrReserve throws IdempotencyKeyReuseException; assert it propagates.; Test C: checkOrReserve throws InFlightDuplicateException; assert it propagates.; Test D: idempotency key null; assert MissingIdempotencyKeyException thrown before any port call.
**Deliverable:** services/payment-executor/src/test/java/com/gme/pay/executor/IdempotencyTest.java
**Acceptance / logic checks:**
- Test A: second call returns same payment_id; PrefundingPort.deductBalance total=1.
- Test B: IdempotencyKeyReuseException propagates with code IDEMPOTENCY_KEY_REUSE.
- Test C: InFlightDuplicateException propagates.
- Test D: MissingIdempotencyKeyException thrown; PrefundingPort invocation count=0.
- All 4 tests GREEN.
**Depends on:** 8.4-T08

### 8.4-T29 — Integration test: full MPM payment flow with Testcontainers (Postgres + Redis + WireMock)  _(60 min)_
**Context:** End-to-end integration test for POST /v1/payments in payment-executor. @SpringBootTest(webEnvironment=RANDOM_PORT) with @Testcontainers. Containers: PostgreSQLContainer (Postgres 16) and GenericContainer for Redis; datasource and Redis URL via @DynamicPropertySource. WireMockServer for prefunding-balance (port 8091) and zeropay-adapter (port 8092). Seeds: partner record type=OVERSEAS in DB; rate quote JSON in Redis key rate-quote:qte_TEST with collectionUsd=36.37, validUntil=now+300s. Flow: POST /v1/payments with valid HMAC-signed request using test API key -> prefunding-balance stub returns 200 {newBalanceUsd:48200} -> zeropay-adapter stub returns 200 {schemeTxnId:ZP001} -> assert HTTP 201. Query Postgres via JdbcTemplate: payments row, 4 payment_events rows, 1 outbox_events row.
**Steps:** Create services/payment-executor/src/test/java/com/gme/pay/executor/integration/MpmPaymentIntegrationTest.java.; Declare @Container static PostgreSQLContainer<> and static GenericContainer<?> for Redis; configure via @DynamicPropertySource.; Configure WireMockServer stubs for prefunding-balance /internal/v1/prefunding/deduct and zeropay-adapter /internal/v1/zeropay/commit.; POST request with HMAC signature computed using test API key and secret; assert HTTP 201 with payment_id.; Query Postgres: assert payments row, 4 payment_events, 1 outbox_events UNPUBLISHED.
**Deliverable:** services/payment-executor/src/test/java/com/gme/pay/executor/integration/MpmPaymentIntegrationTest.java
**Acceptance / logic checks:**
- HTTP 201 response with payment_id, status=approved, scheme_txn_id=ZP001.
- Postgres payments row: collection_usd=36.37, payout_usd_cost=35.06; pool identity holds within 0.01.
- payment_events has 4 rows: PREFUND_DEDUCTED, SCHEME_SUBMITTED, SCHEME_APPROVED, TRANSACTION_COMMITTED.
- outbox_events has 1 row event_type=PaymentApprovedEvent status=UNPUBLISHED.
- WireMock prefunding-balance deduct endpoint received exactly 1 request.
**Depends on:** 8.4-T18, 8.4-T14

### 8.4-T30 — Integration test: CPM token generation with Testcontainers (Postgres + Redis + WireMock)  _(50 min)_
**Context:** End-to-end integration test for POST /v1/payments/cpm/generate using same Testcontainers setup as 8.4-T29. WireMock stubs: prefunding-balance deduct 200 {newBalanceUsd:48000}; zeropay-adapter /internal/v1/zeropay/cpm/generate 201 {prepareToken:ZP-CPM-TESTTOKEN, qrContent:ZP-CPM-TESTTOKEN, expiresAt:now+60s}. Request: OVERSEAS partner, scheme=zeropay, direction=inbound, no prefundReserveUsd (default 200.00). Assert: HTTP 201 response; Postgres cpm_tokens row status=ACTIVE; payments row status=CPM_PENDING; outbox_events has CpmTokenIssuedEvent UNPUBLISHED.
**Steps:** Create services/payment-executor/src/test/java/com/gme/pay/executor/integration/CpmGenerateIntegrationTest.java.; Reuse Testcontainers infrastructure from 8.4-T29 (shared base class or @Nested).; Configure WireMock stubs for prefunding deduct and zeropay cpm/generate.; POST /v1/payments/cpm/generate with valid HMAC-signed request; assert HTTP 201.; Query Postgres: cpm_tokens row status=ACTIVE; payments row status=CPM_PENDING; outbox row CpmTokenIssuedEvent UNPUBLISHED.
**Deliverable:** services/payment-executor/src/test/java/com/gme/pay/executor/integration/CpmGenerateIntegrationTest.java
**Acceptance / logic checks:**
- HTTP 201 response contains cpm_token_id, prepare_token=ZP-CPM-TESTTOKEN, expires_at within now+65s.
- Postgres cpm_tokens.status = ACTIVE.
- Postgres payments.status = CPM_PENDING.
- outbox_events has 1 row event_type=CpmTokenIssuedEvent status=UNPUBLISHED.
- WireMock zeropay-adapter generate endpoint received exactly 1 request.
**Depends on:** 8.4-T29, 8.4-T15

### 8.4-T31 — OpenAPI contract tests for POST /v1/payments and POST /v1/payments/cpm/generate  _(45 min)_
**Context:** Contract tests verify that both endpoints exactly match openapi/partner-api.yaml schemas. Use schemathesis or a JVM contract test library against the running @SpringBootTest (RANDOM_PORT). All external dependencies (PrefundingPort, SchemeAdapterPort, QuoteCache, IdempotencyService) mocked with valid responses. Tests cover: valid requests return 2xx with response bodies matching declared schemas; required fields missing return 400 with ErrorResponse schema; optional fields absent do not cause errors.
**Steps:** Add schemathesis or OpenAPI contract test dependency to services/payment-executor/build.gradle testImplementation.; Create services/payment-executor/src/test/java/com/gme/pay/executor/contract/PaymentContractTest.java loading openapi/partner-api.yaml.; Mock all external ports to return valid successful responses for schema-valid requests.; Run schema-driven test cases for POST /v1/payments: valid -> 201 response validates against PaymentExecuteResponse schema; missing collection_amount -> 400 VALIDATION_ERROR matches ErrorResponse schema.; Run same for POST /v1/payments/cpm/generate: valid -> 201 matches CpmGenerateResponse schema; absent prefund_reserve_usd -> 201 (not 400).
**Deliverable:** services/payment-executor/src/test/java/com/gme/pay/executor/contract/PaymentContractTest.java
**Acceptance / logic checks:**
- All schema-valid POST /v1/payments requests return 2xx (no 5xx for valid inputs).
- 201 response body validates against PaymentExecuteResponse OpenAPI schema.
- Missing collection_amount returns 400 with error envelope matching ErrorResponse schema.
- 201 CPM response validates against CpmGenerateResponse schema.
- Absent prefund_reserve_usd (optional) in CPM request does not cause 400.
**Depends on:** 8.4-T17, 8.4-T05

### 8.4-T32 — Low-balance alert: LowBalanceAlertEvent outbox publish after prefund deduction in prefunding-balance  _(35 min)_
**Context:** After each successful prefund deduction in PrefundingBalanceService, compare newBalance against low_balance_threshold_usd. If newBalance < threshold, write LowBalanceAlertEvent (from lib-events) to outbox_events table in same @Transactional block as the balance UPDATE. The outbox_events table in prefunding-balance service mirrors the schema from V304 (add Flyway V203__create_prefunding_outbox_events_table.sql in services/prefunding-balance/). The Notification & Webhook service consumes this event to alert GME Ops. Threshold is per-partner, stored in prefunding_accounts.low_balance_threshold_usd.
**Steps:** Create services/prefunding-balance/src/main/resources/db/migration/V203__create_prefunding_outbox_events_table.sql mirroring V304 structure.; In PrefundingBalanceService.deductBalance(), after UPDATE: fetch newBalance; if newBalance < low_balance_threshold_usd, call eventPublisher.publish(new LowBalanceAlertEvent(...)) writing to outbox_events in same transaction.; Add EventPublisher @Autowired to PrefundingBalanceService (same OutboxEventPublisher pattern as payment-executor).; Write Testcontainers Postgres unit test: deduct 80 from balance=100 (threshold=200) -> LowBalanceAlertEvent inserted to outbox_events. Deduct 80 from balance=1000 (threshold=200) -> no alert.; Verify rollback: if @Transactional is rolled back, outbox alert also rolled back.
**Deliverable:** services/prefunding-balance/src/main/resources/db/migration/V203__create_prefunding_outbox_events_table.sql and LowBalanceAlertEvent publish logic in PrefundingBalanceService
**Acceptance / logic checks:**
- balance=100, threshold=200, deduct=80 -> newBalance=20 < 200 -> LowBalanceAlertEvent in outbox_events.
- balance=1000, threshold=200, deduct=80 -> newBalance=920 >= 200 -> no alert.
- Alert event contains partnerId, balanceUsd=20, thresholdUsd=200, triggeredAt non-null.
- Rollback of deduction transaction also removes alert event row.
- V203 migration runs cleanly after V202.
**Depends on:** 8.4-T20, 8.4-T06


<!-- wbs-v3-gap-closure -->

---

## WBS v3 gap-closure tickets (re-baseline, 2026-06-10)

These tickets convert this service's PARTIAL audit findings into DONE and add work discovered during the build. Statuses live on the `Backlog` sheet of `GMEPay+_Task_Backlog.xlsx`; phase sequencing on the `Completion Plan v3` sheet of `GMEPay+_WBS.xlsx`.

### 17.2-G08 — payment-executor: swap H2 for real PostgreSQL ITs
*Completion phase:* **R1** · *Est:* 120 min · *Role:* Backend · *Deps:* 17.1-G02

**Context.** Tests currently run on H2 in PostgreSQL mode. Acceptance requires real PG. Scope: execution attempts + idempotency tables.

**Steps.**
- Add Testcontainers postgres:16 to the service's ITs
- Run Flyway migrations against it; fix PG-only syntax drift
- Keep H2 only for pure unit slices

**Deliverable.** Repository/migration ITs green on PostgreSQL 16

**Acceptance.**
- ./gradlew :services:payment-executor:test green with Testcontainers
- Migration checksum stable; no H2-mode workarounds left

### 17.5-G01 — Verify executor's 7 REST clients E2E
*Completion phase:* **R2** · *Est:* 180 min · *Role:* Backend · *Deps:* 17.1-G03,18.6-G01

**Context.** Rest*Client @Primary impls compile but were never exercised against live services. Run executor against the compose stack and verify each hop.

**Steps.**
- Boot compose core profile
- Execute scripted payment; capture each REST call (logbook or wiretap)
- Fix DTO/path drift found

**Deliverable.** 7/7 clients verified live

**Acceptance.**
- One payment exercises rate-fx, prefunding, config-registry, qr-service, txn-mgmt, scheme-adapter, revenue-ledger over HTTP
- No stub bean active in compose profile

