# qr-service  (backend)

**Scope:** EMVCo QR parse, CPM prepare/generate, MPM read/validate

**Owned WBS work-packages:** 5.3, 5.4  ·  **Tickets:** 47  ·  **Est:** 29.8h

> Self-contained backlog for this service. Build in its own module against `shared-libs` contracts. Each ticket has a deliverable + acceptance checks.


## WBS 5.3 — CPM token generation & flow
### 5.3-T01 — Add cpm_prepare_session DB migration: table + indexes  _(30 min)_
**Context:** WBS 5.3 CPM token generation. A cpm_prepare_session row tracks the life of one CPM QR token: issued, reserved prefunding, expiry. Columns needed: id BIGINT PK, cpm_token_id VARCHAR(64) UNIQUE, payment_id VARCHAR(64) UNIQUE, partner_id BIGINT FK partner, scheme_id BIGINT FK qr_scheme, direction VARCHAR(10), customer_ref VARCHAR(255), partner_txn_ref VARCHAR(64), prepare_token VARCHAR(128), qr_content VARCHAR(512), status VARCHAR(20) CHECK IN ('ISSUED','SCANNED','COMPLETED','EXPIRED','FAILED'), prefund_reserved_usd DECIMAL(20,4) NULL, expires_at TIMESTAMPTZ NOT NULL, created_at TIMESTAMPTZ DEFAULT NOW(), updated_at TIMESTAMPTZ. Indexes: (partner_id, created_at), (status, expires_at), unique on (partner_id, partner_txn_ref).
**Steps:** Create Flyway/Liquibase migration file V5_3_001__cpm_prepare_session.sql; Define table with all columns, types, NOT NULL constraints, and CHECK on status; Add UNIQUE constraint on cpm_token_id and on (partner_id, partner_txn_ref); Add composite indexes: (partner_id, created_at), (status, expires_at); Add FK constraints to partner and qr_scheme tables
**Deliverable:** Migration file V5_3_001__cpm_prepare_session.sql that applies cleanly and rolls back without error
**Acceptance / logic checks:**
- Table created with all 15 columns; psql \d cpm_prepare_session shows every column with correct type and nullability
- UNIQUE constraint on cpm_token_id rejects a duplicate insert
- UNIQUE constraint on (partner_id, partner_txn_ref) rejects duplicate for same partner
- CHECK constraint on status rejects INSERT with status = 'PENDING' (not in allowed set)
- Rollback (DOWN migration) drops table and all indexes without error

### 5.3-T02 — Add prefunding_reserved_usd column to prefunding_account migration  _(20 min)_
**Context:** WBS 5.3 CPM token generation. The prefunding_account table (one row per OVERSEAS partner, balance DECIMAL(20,4) in USD) needs a reserved_usd DECIMAL(20,4) NOT NULL DEFAULT 0 column to track amounts soft-reserved for active CPM tokens. available_balance = balance - reserved_usd. The scheme must never be called without a successful deduction; reservation at token-issue time enforces this. Adding the column does not break existing MPM flow which does a hard deduction at POST /v1/payments.
**Steps:** Create migration V5_3_002__prefunding_add_reserved.sql; ADD COLUMN reserved_usd DECIMAL(20,4) NOT NULL DEFAULT 0 to prefunding_account; Add CHECK constraint: reserved_usd >= 0 AND reserved_usd <= balance; Verify existing rows still satisfy the new constraint after migration
**Deliverable:** Migration file V5_3_002__prefunding_add_reserved.sql
**Acceptance / logic checks:**
- Column reserved_usd exists with DEFAULT 0; all pre-existing rows have reserved_usd = 0.00 after migration
- CHECK constraint rejects UPDATE setting reserved_usd = -1
- CHECK constraint rejects UPDATE setting reserved_usd > balance
- SELECT balance - reserved_usd AS available_balance FROM prefunding_account returns non-negative for all rows
- Rollback drops column without error

### 5.3-T03 — Define CpmGenerateRequest and CpmTokenResponse Java DTOs  _(30 min)_
**Context:** WBS 5.3. POST /v1/payments/cpm/generate accepts: scheme_id (String, required), direction (String, required, one of domestic/inbound/outbound/hub), customer_ref (String, required), partner_txn_ref (String, required), country_code (String, required, ISO-3166-1 alpha-2), prefund_reserve_usd (BigDecimal, optional). Response fields: cpm_token_id, prepare_token, qr_content, expires_at (ISO-8601 UTC), prefund_reserved_usd (String decimal, OVERSEAS only), payment_id, scheme_id, partner_txn_ref, created_at. Use Jackson annotations; all amounts as String to avoid floating-point serialisation issues.
**Steps:** Create CpmGenerateRequest record/class in dto package with @NotNull on required fields, @Pattern for country_code ([A-Z]{2}), @DecimalMin(0) on optional prefund_reserve_usd; Create CpmTokenResponse record/class with all response fields; amounts as String; Annotate both with @JsonInclude(NON_NULL) so OVERSEAS-only fields are omitted for LOCAL partners; Add unit test asserting Jackson round-trip of request and response with example values from spec
**Deliverable:** CpmGenerateRequest.java, CpmTokenResponse.java, and CpmDtoTest.java
**Acceptance / logic checks:**
- @Valid on CpmGenerateRequest rejects request missing customer_ref with ConstraintViolationException
- @Pattern on country_code rejects 'KOR' (3-char) and accepts 'KR'
- CpmTokenResponse serialised to JSON omits prefund_reserved_usd when null (@JsonInclude)
- Jackson round-trip of example request {scheme_id:zeropay, direction:inbound, customer_ref:cust_hashed_9f8e7d6c, partner_txn_ref:SENDMN-CPM-20260604-0042, country_code:KR} produces identical object after deserialisation
- BigDecimal field prefund_reserve_usd serialises as string '200.00' not 200.0
**Depends on:** 5.3-T01

### 5.3-T04 — Implement CpmGenerateService.resolveScheme(): country_code to active scheme  _(45 min)_
**Context:** WBS 5.3. When a partner calls POST /v1/payments/cpm/generate, GMEPay+ resolves the active QR scheme from country_code. The qr_scheme table has a supported_countries JSON/array column. If no active scheme for the country_code exists, return error NO_SCHEME_FOR_LOCATION (HTTP 422). If multiple active schemes exist for a country and scheme_id is not provided in the request, return VALIDATION_ERROR asking the partner to specify scheme_id. If scheme_id is provided, validate it is active and supports CPM mode (supported_modes includes CPM). Rule also must have CPM enabled for the partner/scheme/direction combination.
**Steps:** Create CpmGenerateService with method resolveScheme(String countryCode, String schemeIdHint, Long partnerId, String direction); Query qr_scheme table for rows where supported_countries contains countryCode AND status = ACTIVE AND supported_modes contains CPM; If zero matches: throw CpmException(NO_SCHEME_FOR_LOCATION); If multiple matches and schemeIdHint is null: throw CpmException(VALIDATION_ERROR, 'Multiple schemes for country; provide scheme_id'); If schemeIdHint provided: validate it exists in matches; if not, throw SCHEME_NOT_FOUND; Validate rule row for (partner_id, scheme_id, direction) has CPM enabled; throw PAYMENT_MODE_NOT_SUPPORTED if not
**Deliverable:** CpmGenerateService.resolveScheme() method with unit tests covering all 5 branches
**Acceptance / logic checks:**
- Returns correct scheme when exactly one active CPM-capable scheme matches countryCode=KR
- Throws NO_SCHEME_FOR_LOCATION when no active CPM scheme exists for countryCode=US (no ZeroPay)
- Throws VALIDATION_ERROR listing scheme options when two active CPM schemes found for country and no hint given
- Throws PAYMENT_MODE_NOT_SUPPORTED when rule exists but CPM not in supported_modes
- Returns correct scheme when schemeIdHint=zeropay narrows two-scheme result to one
**Depends on:** 5.3-T03

### 5.3-T05 — Implement prefundReserveService.reserveForCpmToken() with SELECT FOR UPDATE atomicity  _(55 min)_
**Context:** WBS 5.3. For OVERSEAS partners, the prefunding deduction MUST occur atomically at token issuance. Use SELECT FOR UPDATE on prefunding_account to prevent concurrent over-draws. Logic: lock row, check balance - reserved_usd >= deduction_amount (else INSUFFICIENT_PREFUNDING HTTP 402), UPDATE reserved_usd = reserved_usd + deduction_amount. The deduction_amount is collection_usd derived from the rate engine, or prefund_reserve_usd from the request if supplied (use the smaller of the two as the reservation; the actual hard deduction happens at scheme approval). LOCAL partners (partner.type = LOCAL) skip this step. The prefunding_ledger_entry row is NOT written yet (written at actual deduction on scheme approval).
**Steps:** Create PrefundReserveService with method reserve(Long partnerId, BigDecimal reserveAmountUsd, String cpmTokenId) returning ReservationResult; Begin transaction; SELECT FOR UPDATE on prefunding_account WHERE partner_id = ?; Compute availableBalance = balance - reserved_usd; if availableBalance < reserveAmountUsd throw InsufficientPrefundingException; UPDATE prefunding_account SET reserved_usd = reserved_usd + reserveAmountUsd, updated_at = NOW() WHERE id = ?; Return ReservationResult(accountId, reserveAmountUsd, balanceAfterReservation); Add rollback path: if subsequent step fails, call release(cpmTokenId) to decrement reserved_usd
**Deliverable:** PrefundReserveService.java with reserve() and release() methods + PrefundReserveServiceTest.java
**Acceptance / logic checks:**
- Concurrent calls: two threads each attempting to reserve 80 USD from a 100 USD balance; only one succeeds, the other gets INSUFFICIENT_PREFUNDING
- reserve() with partnerType=LOCAL returns a no-op ReservationResult without touching prefunding_account
- After successful reserve(), balance unchanged but reserved_usd incremented by reserveAmountUsd
- reserve() with reserveAmountUsd = availableBalance exactly (boundary) succeeds
- release() decrements reserved_usd back to original value
**Depends on:** 5.3-T02

### 5.3-T06 — Implement SchemeAdapter.prepareCPM() for ZeroPay: call scheme and get token  _(50 min)_
**Context:** WBS 5.3. The ZeroPay Adapter must call 한결원's real-time CPM prepare API to obtain a one-time opaque token (prepare_token) that the partner renders as a QR code. The adapter interface is prepareCPM(context: CpmPrepareContext) -> PrepareToken. ZeroPay returns prepare_token (opaque string, e.g. ZP-CPM-ABCDEF1234567890ABCDEF) and an expiry (default 60 seconds from issuance, configurable). CPM tokens are one-time-use. The ZeroPay realtime_api_base_url and credentials come from the encrypted scheme config (never hard-coded). If scheme API is unreachable: throw SchemeUnavailableException (maps to SCHEME_UNAVAILABLE HTTP 422, retryable). Store raw HTTP response in the request log for audit.
**Steps:** In ZeroPayAdapter, implement prepareCPM(CpmPrepareContext ctx) method; Load scheme config (realtime_api_base_url, credentials) from secrets vault via SchemeConfigService; POST to ZeroPay CPM prepare endpoint with required fields (partner reference, country_code, customer_token); On success: return PrepareToken(prepareToken, qrContent, expiresAt); On HTTP 4xx from ZeroPay: map to appropriate internal error code; On timeout or connection failure: throw SchemeUnavailableException
**Deliverable:** ZeroPayAdapter.prepareCPM() implementation with unit tests using a mock HTTP client
**Acceptance / logic checks:**
- Mock returning HTTP 200 with ZP-CPM-ABCDEF token: method returns PrepareToken with correct fields
- Mock returning HTTP 503: method throws SchemeUnavailableException
- Mock returning connection timeout: method throws SchemeUnavailableException
- Credentials are read from SchemeConfigService (mock), never from application.properties or code literal
- expiresAt equals response timestamp + configured token_ttl_seconds (default 60)
**Depends on:** 5.3-T04

### 5.3-T07 — Implement CpmGenerateService.createSession(): orchestrate generate flow end-to-end  _(55 min)_
**Context:** WBS 5.3. The CPM generate handler must orchestrate: (1) resolve scheme from country_code, (2) validate partner rule enables CPM, (3) for OVERSEAS partners atomically reserve prefunding via SELECT FOR UPDATE, (4) call ZeroPay SchemeAdapter.prepareCPM() to get the token, (5) insert cpm_prepare_session row with status=ISSUED, (6) return CpmTokenResponse. If prefund reservation succeeds but scheme call fails: release reservation and throw SCHEME_UNAVAILABLE. If scheme call succeeds but DB insert fails: release reservation and re-raise (idempotency key prevents duplicate creation on retry). The partner.type field determines LOCAL vs OVERSEAS.
**Steps:** In CpmGenerateService add createSession(CpmGenerateRequest request, Long partnerId); Call resolveScheme(); call PrefundReserveService.reserve() if partner.type=OVERSEAS; Call SchemeAdapter.prepareCPM() wrapping in try-catch; on failure release reservation; Insert row into cpm_prepare_session with all fields; status=ISSUED; expires_at = NOW() + token_ttl_seconds; Build and return CpmTokenResponse; set prefund_reserved_usd only for OVERSEAS; Emit transaction event RATE_QUOTE_ISSUED (step 1 of 8-step trail)
**Deliverable:** CpmGenerateService.createSession() with integration-test-style unit tests covering happy path and failure paths
**Acceptance / logic checks:**
- Happy path OVERSEAS: reservation increments reserved_usd, cpm_prepare_session inserted with status=ISSUED, response contains prefund_reserved_usd
- Happy path LOCAL (GME Remit): reserved_usd on prefunding_account not touched, prefund_reserved_usd absent from response
- Scheme call failure after successful reservation: reserved_usd is released (back to original), no cpm_prepare_session row
- cpm_token_id in response matches cpm_prepare_session.cpm_token_id in DB
- payment_id assigned and stored in cpm_prepare_session
**Depends on:** 5.3-T05, 5.3-T06

### 5.3-T08 — Implement POST /v1/payments/cpm/generate controller endpoint  _(45 min)_
**Context:** WBS 5.3. Expose CpmGenerateService as a REST endpoint. Method POST, path /v1/payments/cpm/generate. Auth: HMAC-SHA256 X-Signature header verification (API key + secret, per-request canonical string). Required headers: X-API-Key, X-Timestamp (reject if > 5 min from server time: TIMESTAMP_OUT_OF_RANGE), Idempotency-Key (UUID, reject missing: MISSING_IDEMPOTENCY_KEY). Idempotency: cache (partner_id, Idempotency-Key) -> response for 24h in Redis; if same key+body seen again return stored response; if same key+different body return 422 IDEMPOTENCY_KEY_REUSE. Success: HTTP 201 with CpmTokenResponse. Error codes: VALIDATION_ERROR 400, MISSING_IDEMPOTENCY_KEY 400, INSUFFICIENT_PREFUNDING 402, NO_SCHEME_FOR_LOCATION 422, PAYMENT_MODE_NOT_SUPPORTED 422, SCHEME_UNAVAILABLE 422, RATE_LIMITED 429.
**Steps:** Add CpmController with @PostMapping('/v1/payments/cpm/generate'); Inject AuthService for HMAC verification and timestamp check; Inject IdempotencyService to check/store Redis cache before calling service; Call CpmGenerateService.createSession(); map CpmException to correct HTTP status and error envelope; Return ResponseEntity<CpmTokenResponse> with HTTP 201 on success; Log X-Request-ID in response header
**Deliverable:** CpmController.generate() endpoint handler + CpmControllerTest.java (MockMvc tests)
**Acceptance / logic checks:**
- POST with missing Idempotency-Key header returns 400 MISSING_IDEMPOTENCY_KEY
- POST with invalid HMAC signature returns 401 INVALID_SIGNATURE
- POST with valid request for OVERSEAS partner returns 201 with all CpmTokenResponse fields including prefund_reserved_usd
- Duplicate POST (same Idempotency-Key + same body) returns 201 with cached response; createSession() called exactly once
- POST causing INSUFFICIENT_PREFUNDING returns 402 with error envelope {error.code: INSUFFICIENT_PREFUNDING}
**Depends on:** 5.3-T07

### 5.3-T09 — Validate required request fields and enum values for CPM generate  _(40 min)_
**Context:** WBS 5.3. The generate endpoint must reject malformed requests before any business logic. Required: scheme_id, direction (domestic|inbound|outbound|hub), customer_ref, partner_txn_ref, country_code (ISO 3166-1 alpha-2, uppercase 2-char). Optional: prefund_reserve_usd (decimal string, must be > 0 if present). partner_txn_ref must be unique per partner (check against transactions + cpm_prepare_session; return DUPLICATE_PARTNER_TXN_REF 409 if already used). Error response format: {error.code: VALIDATION_ERROR, error.details: [{field, issue}]}.
**Steps:** Add @NotBlank on scheme_id, direction, customer_ref, partner_txn_ref, country_code in CpmGenerateRequest; Add @Pattern(regexp='[A-Z]{2}') on country_code; Add @DecimalMin(value='0', inclusive=false) on prefund_reserve_usd; Add @ValidEnum(DirectionEnum.class) custom annotation on direction; In CpmGenerateService: query (SELECT 1 FROM cpm_prepare_session WHERE partner_id=? AND partner_txn_ref=?) UNION (SELECT 1 FROM transaction WHERE partner_id=? AND partner_txn_ref=?); throw DUPLICATE_PARTNER_TXN_REF if found; Add global @ExceptionHandler for ConstraintViolationException producing VALIDATION_ERROR response with per-field details
**Deliverable:** Validation annotations on CpmGenerateRequest + duplicate-ref check in service + integration test CpmValidationTest.java
**Acceptance / logic checks:**
- POST missing country_code returns 400 VALIDATION_ERROR with details[0].field = country_code
- POST with direction='WRONG' returns 400 VALIDATION_ERROR for direction field
- POST with country_code='KOR' (3 chars) returns 400 VALIDATION_ERROR
- POST with duplicate partner_txn_ref (already in cpm_prepare_session) returns 409 DUPLICATE_PARTNER_TXN_REF
- POST with prefund_reserve_usd='-5' returns 400 VALIDATION_ERROR
**Depends on:** 5.3-T08

### 5.3-T10 — Implement CPM token expiry scheduler: mark ISSUED tokens as EXPIRED  _(45 min)_
**Context:** WBS 5.3. CPM tokens are one-time-use and expire (default 60 seconds). The cpm_prepare_session.expires_at column records the hard expiry. A scheduled task must sweep rows where status=ISSUED AND expires_at < NOW() and set status=EXPIRED. For OVERSEAS partners, any reservation held against an expired token must be released: UPDATE prefunding_account SET reserved_usd = reserved_usd - s.prefund_reserved_usd WHERE partner_id = s.partner_id. Run every 30 seconds. The sweep must be idempotent (repeated execution safe). Expired token IDs must be logged at INFO level for audit.
**Steps:** Create CpmTokenExpiryScheduler @Scheduled(fixedDelay=30000) bean; SELECT id, partner_id, prefund_reserved_usd FROM cpm_prepare_session WHERE status='ISSUED' AND expires_at < NOW() FOR UPDATE SKIP LOCKED; Bulk UPDATE cpm_prepare_session SET status='EXPIRED', updated_at=NOW() WHERE id IN (...); For OVERSEAS rows with prefund_reserved_usd > 0: UPDATE prefunding_account SET reserved_usd = reserved_usd - prefund_reserved_usd WHERE partner_id = ?; Log expired token IDs at INFO; Add unit test with time-mocked clock and two tokens: one expired, one still valid
**Deliverable:** CpmTokenExpiryScheduler.java with unit test
**Acceptance / logic checks:**
- Token with expires_at = NOW() - 1 second has status updated to EXPIRED by scheduler run
- Token with expires_at = NOW() + 30 seconds is NOT expired
- OVERSEAS token expiry releases reservation: reserved_usd on prefunding_account decremented by prefund_reserved_usd
- LOCAL token expiry does not touch prefunding_account
- Two scheduler runs on same expired token: second run is idempotent (no double-release) due to SKIP LOCKED
**Depends on:** 5.3-T05, 5.3-T07

### 5.3-T11 — Handle inbound scheme relay: CpmPendingEvent when merchant scans QR  _(55 min)_
**Context:** WBS 5.3. After the customer presents the QR code, ZeroPay's POS network sends an inbound CpmPending event to the ZeroPay Adapter's HTTPS endpoint. The adapter relays it to the Transaction Orchestrator as an inboundCpmPending event containing: prepare_token, merchant_id, payout_amount (KRW). The Orchestrator must: (1) look up cpm_prepare_session by prepare_token, validate status=ISSUED, (2) run the rate engine to compute offer_rate and collection_amount breakdown, (3) update cpm_prepare_session status=SCANNED, (4) dispatch payment.pending_debit webhook to partner. The prefunding reservation is already in place; no additional deduction at this stage. Respond to ZeroPay within the scheme SLA (assume 10s).
**Steps:** Create CpmPendingEventHandler.handle(CpmPendingEvent event) in Transaction Orchestrator; Fetch cpm_prepare_session by prepare_token; if not found or status != ISSUED return error to adapter; Run RateEngine.computeQuote(rule, payout_amount) to get offer_rate, estimated_collection_amount, service_charge; UPDATE cpm_prepare_session SET status=SCANNED, target_payout=payout_amount, rate_quote_id=<new quote id>; Dispatch payment.pending_debit webhook (async) with fields: payment_id, cpm_token_id, partner_txn_ref, merchant_id, merchant_name, target_payout, offer_rate, estimated_collection_amount, collection_currency, service_charge; Return acknowledgment to ZeroPay adapter within 10s; scheme SLA must be met
**Deliverable:** CpmPendingEventHandler.java + CpmPendingEventHandlerTest.java
**Acceptance / logic checks:**
- Valid prepare_token with status=ISSUED: cpm_prepare_session updated to SCANNED and payment.pending_debit webhook dispatched
- prepare_token not found: handler returns scheme error without dispatching webhook
- prepare_token with status=EXPIRED: handler returns error, no webhook dispatched
- payment.pending_debit payload contains offer_rate matching rate engine output for payout_amount=32000 KRW (numerical spot check)
- Handler completes within 10s under test with mocked rate engine and mock webhook dispatcher
**Depends on:** 5.3-T07, 5.3-T04

### 5.3-T12 — Implement POST /v1/payments/cpm/confirm: commit CPM transaction to scheme  _(55 min)_
**Context:** WBS 5.3. After the partner receives the payment.pending_debit webhook and customer approves, the partner calls POST /v1/payments/cpm/confirm with {txn_ref} (the payment_id from the generate response). The Orchestrator must: (1) validate cpm_prepare_session status=SCANNED (reject if ISSUED/EXPIRED/COMPLETED/FAILED), (2) call SchemeAdapter.commitPayment(prepare_token, amount_krw), (3) on scheme approval: convert the prefunding reservation to a hard deduction (write prefunding_ledger_entry with entry_type=DEBIT_PAYMENT, decrement balance AND reserved_usd), update cpm_prepare_session status=COMPLETED, record all rate fields as locked, dispatch payment.approved webhook. On scheme decline: status=FAILED, release reservation, dispatch payment.failed webhook.
**Steps:** Create CpmConfirmService.confirm(String paymentId, Long partnerId); Fetch cpm_prepare_session; assert status=SCANNED; throw appropriate error otherwise; Call SchemeAdapter.commitPayment(prepare_token, target_payout) and get scheme_approval_code; On approval: in single DB transaction update cpm_prepare_session status=COMPLETED, INSERT prefunding_ledger_entry(DEBIT_PAYMENT), UPDATE prefunding_account SET balance = balance - prefund_reserved_usd, reserved_usd = reserved_usd - prefund_reserved_usd; On scheme decline: UPDATE status=FAILED, call PrefundReserveService.release(); Dispatch payment.approved or payment.failed webhook asynchronously
**Deliverable:** CpmConfirmService.java + POST /v1/payments/cpm/confirm controller endpoint + CpmConfirmServiceTest.java
**Acceptance / logic checks:**
- Scheme approval: prefunding_ledger_entry DEBIT_PAYMENT written; balance decremented; reserved_usd decremented; cpm_prepare_session status=COMPLETED
- Scheme decline: no ledger entry written; reserved_usd released; cpm_prepare_session status=FAILED
- Confirm on status=ISSUED (not yet scanned): returns 422 with appropriate error; no ledger write
- Confirm on status=COMPLETED (replay): idempotent; returns stored approval response; no double-deduction
- Prefunding atomicity: balance and reserved_usd updated in same transaction as ledger entry insert
**Depends on:** 5.3-T11, 5.3-T05

### 5.3-T13 — Implement low-balance alert trigger after CPM prefunding reservation  _(35 min)_
**Context:** WBS 5.3. After each successful prefunding reservation, check whether (balance - reserved_usd) <= low_balance_threshold from low_balance_alert_config for that partner. If so, publish a LowBalanceAlertEvent. The alert must fire at most once per configurable cooldown window (default 1 hour) to avoid alert spam: store last_alerted_at in low_balance_alert_config and skip if NOW() - last_alerted_at < cooldown_seconds. Threshold default 10000.00 USD. This applies to CPM reservations and MPM hard deductions equally; the shared service call is made from PrefundReserveService after every successful operation.
**Steps:** In PrefundReserveService, after successful reserve(), read available_balance = balance - reserved_usd; Load low_balance_alert_config for partner; if is_active=true and available_balance <= threshold_usd; Check last_alerted_at: if null or (NOW() - last_alerted_at) > cooldown_seconds, publish LowBalanceAlertEvent; UPDATE low_balance_alert_config SET last_alerted_at = NOW(); LowBalanceAlertEvent includes partner_id, available_balance, threshold_usd, triggered_at; Add unit test: balance 9500, threshold 10000, last_alerted_at null -> event published
**Deliverable:** Low-balance check in PrefundReserveService + LowBalanceAlertEvent + LowBalanceAlertServiceTest.java
**Acceptance / logic checks:**
- available_balance 9500 USD, threshold 10000 USD, last_alerted_at null: LowBalanceAlertEvent published once
- available_balance 9500, last_alerted_at = NOW() - 30 min (cooldown 60 min): event NOT published (within cooldown)
- available_balance 10001 USD (above threshold): event NOT published
- available_balance 10000 exactly (boundary = threshold): event published
- is_active=false: event NOT published regardless of balance
**Depends on:** 5.3-T05

### 5.3-T14 — Validate prefund_reserve_usd against scheme max transaction limit  _(35 min)_
**Context:** WBS 5.3. The generate request accepts an optional prefund_reserve_usd. If provided, GMEPay+ must validate it does not exceed the scheme's configured max_txn_amount_usd (stored on qr_scheme or scheme_limit config). If omitted, GMEPay+ reserves based on scheme max transaction limit. The reserved amount must always be >= min_txn_amount_usd for the scheme. Return VALIDATION_ERROR with field detail if bounds violated. For ZeroPay: assume max_txn_amount_usd = 1000 USD, min_txn_amount_usd = 0.10 USD (values from scheme config, not hard-coded).
**Steps:** In CpmGenerateService.createSession(), after resolveScheme(), load SchemeLimit for the resolved scheme; If request.prefund_reserve_usd is null: set reservationAmount = scheme.max_txn_amount_usd; Else: validate request.prefund_reserve_usd <= scheme.max_txn_amount_usd and >= scheme.min_txn_amount_usd; throw VALIDATION_ERROR if outside bounds; Pass resolvedReservationAmount to PrefundReserveService.reserve(); Add tests for above threshold, below threshold, and null (uses max)
**Deliverable:** Scheme-limit validation logic in CpmGenerateService + unit tests
**Acceptance / logic checks:**
- prefund_reserve_usd omitted: reservation amount used equals scheme.max_txn_amount_usd (e.g. 1000.00 USD)
- prefund_reserve_usd=500.00 (within bounds): reservation amount = 500.00
- prefund_reserve_usd=1001.00 (above max 1000.00): 400 VALIDATION_ERROR with field prefund_reserve_usd
- prefund_reserve_usd=0.05 (below min 0.10): 400 VALIDATION_ERROR with field prefund_reserve_usd
- Scheme max/min values read from scheme config record, not hard-coded
**Depends on:** 5.3-T07

### 5.3-T15 — Handle SCHEME_UNAVAILABLE: reject CPM generate cleanly without deduction  _(35 min)_
**Context:** WBS 5.3. If ZeroPay is unavailable when prepareCPM() is called, the system must not leave a dangling prefunding reservation. The handler must: (1) release any reservation made in step 3, (2) return SCHEME_UNAVAILABLE HTTP 422 to the partner. Partners should retry with backoff; a new Idempotency-Key is required on retry because the original request did not complete. The scheme is never called without a prior successful reservation, and the reservation is never left unreleased on failure. Also handle: if ZeroPay returns a non-retryable error (e.g. invalid credentials) map to INTERNAL_ERROR 500.
**Steps:** In CpmGenerateService.createSession(), wrap SchemeAdapter.prepareCPM() in try-catch; On SchemeUnavailableException: call PrefundReserveService.release(); throw mapped CpmException(SCHEME_UNAVAILABLE); On unexpected RuntimeException: call release(); throw CpmException(INTERNAL_ERROR); In controller, map SCHEME_UNAVAILABLE to HTTP 422 with retryable=true in error envelope; Add unit test simulating scheme timeout after reservation
**Deliverable:** Exception handling in CpmGenerateService + unit tests for unavailable-scheme path
**Acceptance / logic checks:**
- Mock scheme unavailable: reserved_usd on prefunding_account returns to pre-call value
- Mock scheme unavailable: HTTP 422 SCHEME_UNAVAILABLE returned to partner
- Mock scheme throws unexpected NPE: reserved_usd released; HTTP 500 INTERNAL_ERROR returned
- No cpm_prepare_session row inserted when scheme call fails
- Happy path after failed attempt (new Idempotency-Key): second attempt succeeds and inserts session row
**Depends on:** 5.3-T07, 5.3-T05

### 5.3-T16 — Idempotency layer for CPM generate: Redis cache with 24h TTL  _(50 min)_
**Context:** WBS 5.3. All POST endpoints require Idempotency-Key (UUID, 16-128 chars). The Hub caches (partner_id, idempotency_key) -> (response_status, response_body) in Redis for 24 hours. Behaviour: same key + same body = return cached response without re-processing; same key + different body = return 422 IDEMPOTENCY_KEY_REUSE; absent key = 400 MISSING_IDEMPOTENCY_KEY; in-flight duplicate = 409 with X-Idempotency-Status: in_flight. For CPM generate, the response cached is the CpmTokenResponse with all fields (even if the token has since expired). Implement using IdempotencyService shared with other endpoints.
**Steps:** In IdempotencyService, implement checkAndStore(partnerId, key, requestBodyHash) before service call; Store key as Redis hash with fields: status, body, request_hash, created_at; TTL 86400s; On cache miss: set status=in_flight; proceed with service; on completion store final response; On hit with same request_hash and status != in_flight: return cached response; On hit with different request_hash: return 422 IDEMPOTENCY_KEY_REUSE; On hit with status=in_flight: return 409 with X-Idempotency-Status: in_flight
**Deliverable:** IdempotencyService caching logic for CPM generate in IdempotencyServiceTest.java and wired into CpmController
**Acceptance / logic checks:**
- First call stores response in Redis with TTL ~86400s; second call with same key returns cached 201 without calling createSession()
- Second call with same key but different country_code in body returns 422 IDEMPOTENCY_KEY_REUSE
- Concurrent duplicate calls while first is in_flight: second returns 409
- Missing Idempotency-Key header returns 400 MISSING_IDEMPOTENCY_KEY
- After Redis TTL expiry (mock clock advance 25h): same key treated as fresh request
**Depends on:** 5.3-T08

### 5.3-T17 — Unit tests: rate-engine integration in CPM pending event with numeric vectors  _(45 min)_
**Context:** WBS 5.3. The rate engine runs when the CpmPendingEvent arrives (merchant scan). Test the full 5-step USD pool calculation with real numeric vectors. Inputs: partner SendMN (OVERSEAS, USD collection), direction=inbound, target_payout=50000 KRW, cost_rate_coll (usd_usd=1.0 since collect ccy=USD), cost_rate_pay (usd_krw=1400.00), m_a=0.015, m_b=0.005, service_charge=0.50 USD. Expected: payout_usd_cost = 50000/1400 = 35.714286; collection_usd = 35.714286/(1-0.02) = 36.443149; send_amount = 36.443149 * 1.0 = 36.443149; collection_amount = 36.443149 + 0.50 = 36.943149; pool identity: 36.443149 - 36.443149*0.015 - 36.443149*0.005 = 35.714286 (within 0.01 USD). offer_rate_coll = 36.443149 / (36.443149 - 0.546647) = 1.015228 per unit; cross_rate = 50000/36.443149 = 1371.97.
**Steps:** Add test class RateEngineCpmTest; Set up test Rule with m_a=0.015, m_b=0.005, service_charge_amount=0.50, cost_rate_coll=1.0, cost_rate_pay=1400.00; Call rateEngine.computeQuote(rule, 50000, KRW); Assert payout_usd_cost = 35.714286 (tolerance 0.000001); Assert collection_usd = 36.443149 (tolerance 0.000001); Assert pool identity: collection_usd - collection_margin_usd - payout_margin_usd == payout_usd_cost within 0.01; Assert offer_rate_coll computed correctly; assert collection_amount = 36.943149
**Deliverable:** RateEngineCpmTest.java with at least 4 parameterised test vectors including the above
**Acceptance / logic checks:**
- payout_usd_cost asserted within 0.000001 of 35.714286 using BigDecimal arithmetic
- Pool identity holds: 36.443149 - 0.546647 - 0.182216 = 35.714286 (delta < 0.01 USD)
- collection_amount = 36.943149 (send_amount + service_charge)
- offer_rate_coll = send_amount / (collection_usd - collection_margin_usd) = 1.015228 within tolerance
- Second vector: target_payout=32000 KRW, same rule: payout_usd_cost=22.857143, collection_usd=23.323615, collection_amount=23.823615
**Depends on:** 5.3-T11

### 5.3-T18 — Unit tests: atomic prefunding deduction for CPM with concurrency vector  _(55 min)_
**Context:** WBS 5.3. Test that SELECT FOR UPDATE prevents concurrent over-draws. Scenario: partner has balance=100.00 USD, reserved_usd=0. Two concurrent CPM generate calls each requesting reservation of 70.00 USD. Only one should succeed; the other must receive INSUFFICIENT_PREFUNDING. Use a DB-level test (TestContainers or equivalent) to exercise actual locking, not application-level mocking. Also test: reservation of exactly 100.00 (boundary), reservation of 100.01 (over by 0.01 rejected).
**Steps:** Set up TestContainers PostgreSQL with prefunding_account row: balance=100.00, reserved_usd=0; Spawn two threads simultaneously calling PrefundReserveService.reserve(partnerId, 70.00, tokenId); Assert exactly one thread returns success and the other throws InsufficientPrefundingException; After first success: reserved_usd=70.00, available=30.00; second attempt of 70.00 must fail; Add boundary test: reserve(100.00) succeeds; reserve(100.01) fails; Add release test: after reserve(70.00) then release, reserved_usd returns to 0
**Deliverable:** PrefundConcurrencyTest.java using TestContainers
**Acceptance / logic checks:**
- Of two concurrent 70.00 USD reservation attempts on 100.00 balance, exactly one succeeds
- balance unchanged after reservation; reserved_usd = 70.00 after first success
- reserve(100.00) on available balance exactly 100.00 succeeds (boundary)
- reserve(100.01) fails with InsufficientPrefundingException even at boundary
- After release(): reserved_usd = 0; subsequent reserve(100.00) succeeds again
**Depends on:** 5.3-T05

### 5.3-T19 — Unit tests: CPM token expiry and reservation release with mocked clock  _(40 min)_
**Context:** WBS 5.3. Test the expiry scheduler edge cases. Scenario A: token with expires_at = T-1s, OVERSEAS, prefund_reserved_usd=200.00 -> status becomes EXPIRED, reserved_usd decremented by 200. Scenario B: two expired tokens for same partner, both released in one scheduler run. Scenario C: token already SCANNED (merchant has scanned): scheduler must NOT expire it (SCANNED tokens are past the reservation stage, they will be handled by confirm flow). Scenario D: SKIP LOCKED behaviour: token locked by another transaction is skipped (not double-processed).
**Steps:** Create CpmTokenExpirySchedulerTest with mocked Clock; Seed cpm_prepare_session rows for each scenario; Run scheduler.sweep() with mocked clock; Assert scenario A: row status=EXPIRED, prefunding_account.reserved_usd decremented; Assert scenario B: both rows EXPIRED, reserved_usd decremented correctly (sum); Assert scenario C: SCANNED row not touched; Assert scenario D: locked row skipped without error or double-release
**Deliverable:** CpmTokenExpirySchedulerTest.java with 4 test scenarios
**Acceptance / logic checks:**
- Scenario A: reserved_usd decremented by exactly 200.00 after expiry
- Scenario B: two tokens (150.00 + 200.00): reserved_usd decremented by 350.00 total
- Scenario C: SCANNED token unchanged after scheduler run
- Scenario D: scheduler logs skipped token ID but does not update it
- All scenarios: no rows with status=ISSUED and expires_at < NOW() remain after sweep
**Depends on:** 5.3-T10

### 5.3-T20 — Unit tests: CPM confirm happy path and idempotency  _(50 min)_
**Context:** WBS 5.3. Test POST /v1/payments/cpm/confirm success and replay. Scenario A (happy path): cpm_prepare_session status=SCANNED, scheme approves -> ledger entry DEBIT_PAYMENT written, balance decremented by prefund_reserved_usd, reserved_usd decremented, session status=COMPLETED, payment.approved webhook dispatched. Scenario B (replay): call confirm again on same paymentId (session already COMPLETED) -> returns stored approval; no second ledger entry; no double deduction. Scenario C: session status=ISSUED at confirm time (not yet scanned) -> returns 422. Scenario D: scheme declines -> ledger entry NOT written, reserved_usd released, session status=FAILED, payment.failed webhook dispatched.
**Steps:** Set up CpmConfirmServiceTest with mock SchemeAdapter, mock PrefundReserveService, in-memory or TestContainers DB; Run scenario A: assert ledger entry inserted, balance and reserved_usd updated atomically; Run scenario B: call confirm twice on same paymentId; count prefunding_ledger_entry rows = 1; Run scenario C: assert 422 error code CPM_NOT_READY or equivalent; Run scenario D: assert no ledger entry, reserved_usd released, status=FAILED; Check that payment.approved and payment.failed webhooks are dispatched in correct scenarios
**Deliverable:** CpmConfirmServiceTest.java with 4 scenarios
**Acceptance / logic checks:**
- Scenario A: exactly one DEBIT_PAYMENT ledger entry; balance = balance - prefund_reserved_usd; reserved_usd = 0
- Scenario B: second confirm call returns same approval response; ledger row count unchanged at 1
- Scenario C: confirm on ISSUED session returns 422; no ledger entry written
- Scenario D: scheme decline writes no ledger entry; reserved_usd = 0 (released); status=FAILED
- Scenario A: payment.approved webhook dispatch called once with correct fields
**Depends on:** 5.3-T12

### 5.3-T21 — Unit tests: CPM generate error paths (expired token, invalid scheme, duplicate ref)  _(45 min)_
**Context:** WBS 5.3. Test all error rejection paths at the generate endpoint. (1) Duplicate partner_txn_ref: same ref used twice by same partner -> 409 DUPLICATE_PARTNER_TXN_REF on second call. (2) NO_SCHEME_FOR_LOCATION: country_code=XX (no scheme) -> 422. (3) PAYMENT_MODE_NOT_SUPPORTED: rule exists but CPM not in supported_modes -> 422. (4) DIRECTION_NOT_ENABLED: direction=outbound but rule only has inbound -> 422. (5) SCHEME_UNAVAILABLE during prepareCPM: reserved_usd released, 422 returned, no cpm_prepare_session row. (6) INSUFFICIENT_PREFUNDING: balance - reserved_usd < requested amount -> 402 with no scheme call.
**Steps:** Create CpmGenerateErrorTest with MockMvc + mock dependencies; Test case 1: insert existing session with same partner_txn_ref, call generate; Test case 2: configure no scheme for country_code=XX, call generate; Test case 3: configure rule without CPM in supported_modes; Test case 4: configure rule with direction=INBOUND only, call with direction=outbound; Test case 5: mock prepareCPM to throw SchemeUnavailableException; assert no DB row and reservation released; Test case 6: set prefunding balance to 10.00, request reserve_usd=50.00
**Deliverable:** CpmGenerateErrorTest.java with 6 error-path test cases
**Acceptance / logic checks:**
- Case 1: HTTP 409 DUPLICATE_PARTNER_TXN_REF; no new cpm_prepare_session row
- Case 2: HTTP 422 NO_SCHEME_FOR_LOCATION; no reservation
- Case 3: HTTP 422 PAYMENT_MODE_NOT_SUPPORTED; no reservation
- Case 4: HTTP 422 DIRECTION_NOT_ENABLED; no reservation
- Case 5: HTTP 422 SCHEME_UNAVAILABLE; reserved_usd unchanged after call
- Case 6: HTTP 402 INSUFFICIENT_PREFUNDING; no scheme call (mock verifies prepareCPM never invoked)
**Depends on:** 5.3-T09, 5.3-T15

### 5.3-T22 — OpenAPI spec: document POST /v1/payments/cpm/generate in partner-api.yaml  _(40 min)_
**Context:** WBS 5.3. The openapi/partner-api.yaml is the ground truth for contract tests. Add the full spec for POST /v1/payments/cpm/generate: path, method, summary, required headers (X-API-Key, X-Timestamp, X-Signature, Idempotency-Key), requestBody schema with all fields and constraints (scheme_id string, direction enum, customer_ref string, partner_txn_ref string, country_code pattern [A-Z]{2}, prefund_reserve_usd string decimal optional), responses 201 CpmTokenResponse schema, 400 MISSING_IDEMPOTENCY_KEY, 400 VALIDATION_ERROR, 402 INSUFFICIENT_PREFUNDING, 409 DUPLICATE_PARTNER_TXN_REF, 422 (NO_SCHEME_FOR_LOCATION, PAYMENT_MODE_NOT_SUPPORTED, SCHEME_UNAVAILABLE), 429 RATE_LIMITED. All amount fields as type: string (decimal) per spec convention.
**Steps:** Open openapi/partner-api.yaml; Add path /v1/payments/cpm/generate with post operation; Define requestBody schema $ref CpmGenerateRequest with required array and property constraints; Define 201 response schema $ref CpmTokenResponse; all amount fields type: string; Add error response schemas for each listed HTTP status reusing shared ErrorEnvelope $ref; Add example request/response matching spec example (partner_txn_ref: SENDMN-CPM-20260604-0042); Run openapi-generator validate or spectral lint; zero errors
**Deliverable:** Updated openapi/partner-api.yaml with CPM generate endpoint fully specified, passing lint
**Acceptance / logic checks:**
- openapi-generator validate reports 0 errors and 0 warnings on the updated file
- 201 response schema contains all 9 CpmTokenResponse fields with correct types
- prefund_reserved_usd has type: string and is not in required array (optional, OVERSEAS only)
- country_code property has pattern: ^[A-Z]{2}$ in schema
- direction property has enum: [domestic, inbound, outbound, hub]
**Depends on:** 5.3-T08

### 5.3-T23 — Document payment.pending_debit webhook in partner-api.yaml and developer guide  _(35 min)_
**Context:** WBS 5.3. The payment.pending_debit webhook is CPM-only; delivered after merchant POS scans the QR. Partners use it to show the estimated charge to the customer. Fields: event_id, event_type=payment.pending_debit, created_at, partner_id, data.{payment_id, cpm_token_id, partner_txn_ref, merchant_id, merchant_name, target_payout, payout_currency, offer_rate, estimated_collection_amount, collection_currency, service_charge, service_charge_currency}. The webhook is signed: X-GME-Webhook-Signature: sha256=HMAC-SHA256(body, webhook_signing_secret). Partner must respond HTTP 2xx within 10s. Note in docs: this webhook arrives BEFORE payment.approved; customer sees amount only after scheme approval (OI-01).
**Steps:** In openapi/partner-api.yaml add webhook schema for payment.pending_debit under webhooks or components/schemas; Add all data fields with types and examples matching spec (offer_rate: 0.000703, estimated_collection_amount: 22.83 for 32000 KRW); Document signing header X-GME-Webhook-Signature in webhook description; Note: estimated_collection_amount is GMEPay+ derived; partner may charge a different amount to customer (partner computes final); In developer guide (docs/partner-integration.md or equivalent), add CPM webhook flow section with sequence note
**Deliverable:** payment.pending_debit webhook schema in openapi/partner-api.yaml + documentation note in developer guide
**Acceptance / logic checks:**
- payment.pending_debit schema has all 12 data fields listed above
- offer_rate field type is string with example 0.000703
- Documentation notes that payment.pending_debit precedes payment.approved and is CPM-only
- X-GME-Webhook-Signature header documented with HMAC-SHA256 signing note
- openapi lint passes after addition
**Depends on:** 5.3-T22

### 5.3-T24 — Add 8-step event trail entries for CPM generate and confirm steps  _(40 min)_
**Context:** WBS 5.3. Every transaction must carry an 8-step audit trail in transaction_event table: step 1=RATE_QUOTE_ISSUED, step 2=PREFUND_DEDUCTED, step 3=SCHEME_SUBMITTED, step 4=SCHEME_APPROVED or SCHEME_DECLINED, step 5=TRANSACTION_COMMITTED, step 6=SETTLEMENT_BATCHED, step 7=WEBHOOK_QUEUED, step 8=WEBHOOK_DELIVERED. For CPM: RATE_QUOTE_ISSUED fires when pending_debit webhook is dispatched (rate computed at scan time); PREFUND_DEDUCTED fires at confirm when reservation converts to hard deduction; steps 3-8 follow standard flow. For LOCAL partners, step 2 is recorded with detail={skipped:true} to satisfy the 8-row requirement.
**Steps:** In CpmPendingEventHandler, after rate engine call, INSERT transaction_event(txn_id, step=1, event_type=RATE_QUOTE_ISSUED, detail={quote_ref, offer_rate}); In CpmConfirmService, after successful deduction, INSERT transaction_event(step=2, event_type=PREFUND_DEDUCTED, detail={amount_usd, balance_after}) for OVERSEAS; for LOCAL insert with detail={skipped:true}; Add SCHEME_SUBMITTED event before scheme adapter call; Chain remaining events in existing commit/approve/webhook flow; Assert no transaction is committed without all 8 event rows in unit test
**Deliverable:** Event trail inserts in CpmPendingEventHandler and CpmConfirmService + TransactionEventTrailTest.java
**Acceptance / logic checks:**
- After CPM confirm: transaction_event table has rows for steps 1-5 with correct event_type values
- OVERSEAS partner: step 2 event_type=PREFUND_DEDUCTED; detail contains amount_usd and balance_after
- LOCAL partner: step 2 row present with detail containing skipped:true; not absent
- Step 1 occurred_at <= step 2 occurred_at (chronological ordering)
- duration_ms on step 2 reflects time since step 1 was recorded
**Depends on:** 5.3-T12, 5.3-T11

### 5.3-T25 — Sandbox: simulate CPM token generate and prefunding for test partners  _(50 min)_
**Context:** WBS 5.3. The sandbox environment must support CPM generate with simulated ZeroPay responses. Sandbox differences: no real ZeroPay connection; prepareCPM returns a deterministic test token ZP-CPM-TEST-<uuid>; prefunding balance pre-loaded (configurable via sandbox control API). Sandbox must support certification test C-04 (generate CPM token -> HTTP 201, prepare_token present) and C-13 (insufficient prefunding -> HTTP 402). Sandbox test partner SendMN should have prefunding balance 5000.00 USD. Token expiry still fires at 60s in sandbox. Ensure sandbox ZeroPayAdapter stub returns token without hitting real API.
**Steps:** In ZeroPayAdapterStub (sandbox profile), implement prepareCPM to return PrepareToken(ZP-CPM-TEST-<uuid>, ZP-CPM-TEST-<uuid>, NOW()+60s); Ensure sandbox prefunding seeded at 5000.00 USD for test OVERSEAS partner; Expose sandbox control endpoint POST /sandbox/prefunding/{partnerId}/set {balance_usd} for test setup; Verify C-04: POST /v1/payments/cpm/generate with test credentials returns 201 with prepare_token=ZP-CPM-TEST-*; Verify C-13: set balance to 0 via control API; call generate; returns 402 INSUFFICIENT_PREFUNDING; Add sandbox integration test asserting both scenarios
**Deliverable:** ZeroPayAdapterStub.prepareCPM() + sandbox seed data + sandbox control endpoint + SandboxCpmIntegrationTest.java
**Acceptance / logic checks:**
- Sandbox POST /v1/payments/cpm/generate with test partner credentials returns 201 and prepare_token matching ZP-CPM-TEST-* pattern
- Sandbox cpm_prepare_session row created with status=ISSUED and expires_at ~ NOW()+60s
- After POST /sandbox/prefunding/set {balance_usd:0}: generate returns 402 INSUFFICIENT_PREFUNDING
- Sandbox prepareCPM never calls real ZeroPay URL (verified by no outbound HTTP to ZeroPay in test)
- Token expiry scheduler runs in sandbox and moves ISSUED tokens to EXPIRED after 60s
**Depends on:** 5.3-T08, 5.3-T10


## WBS 5.4 — QR parsing (EMVCo) & merchant resolution
### 5.4-T01 — Define MerchantIdentifier and ParsedQRPayload Java interfaces  _(20 min)_
**Context:** WBS 5.4 — QR Parser module. The QR Parser is a Hub Core component that decodes EMVCo MPM QR payloads and resolves the merchant identity from the local DB. Its public contract is scheme-agnostic. Two output types are needed: ParsedQRPayload (raw parse result before DB resolution) and MerchantIdentifier (fully resolved merchant record returned to the Orchestrator). Fields: ParsedQRPayload{rawPayload:String, formatIndicator:int, currencyCode:String, merchantName:String, merchantCity:String, mcc:String, countryCode:String, maiTag:int, merchantId:String, qrCodeId:String, encodedAmount:BigDecimal nullable, crcVerified:boolean}. MerchantIdentifier{merchantId:String, qrCodeId:String, merchantName:String, merchantType:String, feeTier:String, schemeId:String, isActive:boolean}.
**Steps:** Create package com.gmepayplus.qrparser.model; Define interface/record ParsedQRPayload with all fields listed in context; Define interface/record MerchantIdentifier with all fields listed in context; Add Javadoc referencing EMVCo tag IDs (tag 52=MCC, 53=currency, 58=country, 59=name, 60=city, 63=CRC, 26-51=MAI); Commit with no logic — pure data contract
**Deliverable:** ParsedQRPayload.java and MerchantIdentifier.java in com.gmepayplus.qrparser.model
**Acceptance / logic checks:**
- ParsedQRPayload has crcVerified boolean and nullable encodedAmount
- MerchantIdentifier has feeTier and merchantType fields for fee-rate selection
- Both types are immutable (all-final or record)
- No scheme-specific imports; no ZeroPay references in these interfaces

### 5.4-T02 — Define QRParseException hierarchy with error codes  _(20 min)_
**Context:** WBS 5.4 — QR Parser. Parse failures must be categorised with specific error codes so the Orchestrator can return correct HTTP 422 responses. Required codes (from SCH-06 §3.4): QR_INVALID_CHECKSUM (CRC-16 mismatch), QR_UNKNOWN_SCHEME (MAI tag does not match any registered scheme), QR_MALFORMED (missing mandatory tag: 00, 52, 53, 58, 59, 60, or 63), QR_CURRENCY_MISMATCH (tag 53 != 410 for ZeroPay). Additional resolution codes: MERCHANT_NOT_FOUND, MERCHANT_INACTIVE, QR_NOT_FOUND, QR_DEACTIVATED, QR_MERCHANT_MISMATCH (qr_code references a different merchant_id than the payload). All extend QRParseException(errorCode, message).
**Steps:** Create QRParseException(String errorCode, String message, Throwable cause); Create enum QRErrorCode with all six parse codes and three resolution codes listed in context; Create subclasses or use factory methods: QRInvalidChecksumException, QRMalformedException, QRUnknownSchemeException, QRCurrencyMismatchException; Create resolution exception classes: MerchantNotFoundException, MerchantInactiveException, QRDeactivatedException, QRMerchantMismatchException; Add getErrorCode() method returning the enum value
**Deliverable:** QRParseException.java, QRErrorCode.java, and subclass files in com.gmepayplus.qrparser.exception
**Acceptance / logic checks:**
- QRErrorCode enum contains exactly: QR_INVALID_CHECKSUM, QR_UNKNOWN_SCHEME, QR_MALFORMED, QR_CURRENCY_MISMATCH, MERCHANT_NOT_FOUND, MERCHANT_INACTIVE, QR_NOT_FOUND, QR_DEACTIVATED, QR_MERCHANT_MISMATCH
- All exceptions extend QRParseException
- getErrorCode() returns a non-null QRErrorCode
- No checked exceptions — all extend RuntimeException
**Depends on:** 5.4-T01

### 5.4-T03 — Define QRParserService interface and SchemeQRParser SPI  _(30 min)_
**Context:** WBS 5.4 — QR Parser. The Hub Core calls QRParserService.parse(rawPayload, schemeId) which returns a ParsedQRPayload. Internally, it delegates to a registered SchemeQRParser (the Strategy/SPI pattern) looked up by schemeId. A ZeroPayQRParser implements SchemeQRParser for schemeId=ZEROPAY. This design allows future schemes (KHQR, QPay) to add a new SchemeQRParser without changing Hub Core. QRParserService also has resolveIdentifier(ParsedQRPayload) -> MerchantIdentifier (DB lookup, scheme-agnostic). Both methods declare throws QRParseException.
**Steps:** Create interface QRParserService with methods: ParsedQRPayload parse(String rawPayload, String schemeId); MerchantIdentifier resolveIdentifier(ParsedQRPayload parsed); Create interface SchemeQRParser with methods: boolean supports(String schemeId); ParsedQRPayload parse(String rawPayload); Create @Service class QRParserServiceImpl that takes List<SchemeQRParser> and routes to matching parser; Add getSupportedSchemes() helper returning list of registered schemeIds; Wire with Spring @Autowired constructor injection
**Deliverable:** QRParserService.java, SchemeQRParser.java interfaces and QRParserServiceImpl.java skeleton (no ZeroPay logic yet)
**Acceptance / logic checks:**
- QRParserServiceImpl.parse() throws QRUnknownSchemeException when no parser supports the given schemeId
- QRParserServiceImpl accepts List<SchemeQRParser> — adding a new parser requires only registering a new @Component bean
- resolveIdentifier is defined on the service, not the scheme parser (DB lookup is scheme-agnostic)
- Unit test: registering two parsers with different schemeId, calling parse(payload, ZEROPAY) routes to correct one
**Depends on:** 5.4-T01, 5.4-T02

### 5.4-T04 — Implement EMVCo TLV tokenizer (tag-length-value splitter)  _(40 min)_
**Context:** WBS 5.4 — QR Parser. EMVCo QR payloads are ASCII strings in TLV format: each data object is exactly TAG(2 chars) + LENGTH(2 chars, decimal) + VALUE(LENGTH chars). Example: '000201' = tag 00, length 02, value '01'. Tags are at the top level and recursively inside Merchant Account Information (MAI) templates. The tokenizer must handle: nested TLV (MAI slot tag 26-51 contains its own TLV sub-objects), overlapping length values, and malformed input where declared length exceeds remaining string. Implement as a stateless utility class EMVCoTlvParser with a static method: Map<Integer,String> parseTopLevel(String payload) and Map<Integer,String> parseTemplate(String templateValue).
**Steps:** Create class EMVCoTlvParser in com.gmepayplus.qrparser.emvco; Implement parseTopLevel(String payload): iterate by reading 2-char tag, 2-char decimal length, then slice VALUE; accumulate into LinkedHashMap<Integer,String>; Implement parseTemplate(String value): identical algorithm applied to a MAI template string; Throw QRMalformedException if declared length > remaining string length; Throw QRMalformedException if payload is null or blank; Preserve insertion order; duplicate tags should throw QRMalformedException
**Deliverable:** EMVCoTlvParser.java utility class with parseTopLevel and parseTemplate methods
**Acceptance / logic checks:**
- parseTopLevel('000201520412345399KRW') returns {0:'01', 52:'1234', 53:'KRW'} — verify tag/length/value slicing
- parseTopLevel throws QRMalformedException when declared length 99 but only 3 chars remain
- parseTemplate works correctly on a ZeroPay MAI sub-string containing merchant_id and qr_code_id sub-tags
- Empty string input throws QRMalformedException with errorCode QR_MALFORMED
- Duplicate tag in same level throws QRMalformedException
**Depends on:** 5.4-T02

### 5.4-T05 — Implement CRC-16/CCITT checksum verifier for EMVCo QR  _(40 min)_
**Context:** WBS 5.4 — QR Parser. EMVCo QR payloads carry a CRC-16/CCITT checksum in tag 63 (always 4 hex chars, last data object). The checksum covers all characters of the payload UP TO AND INCLUDING the tag and length of tag 63 (i.e. the string ends with '6304' before the 4 hex CRC digits). Algorithm: CRC-16/CCITT, polynomial 0x1021, initial value 0xFFFF, no input/output reflection, no final XOR. Example: payload='000201...6304' then CRC computed over that string; result must equal the 4 hex chars of tag 63 value. Error code on mismatch: QR_INVALID_CHECKSUM.
**Steps:** Create class EMVCoCrcVerifier in com.gmepayplus.qrparser.emvco; Implement static boolean verify(String fullPayload): find tag 63 position, extract 4-char hex CRC value, compute CRC-16/CCITT over fullPayload.substring(0, tag63ValueStart), compare; Implement static String compute(String data): returns 4-char uppercase hex CRC-16/CCITT; Use polynomial 0x1021, init 0xFFFF, process byte-by-byte, no reflection; Throw QRInvalidChecksumException with errorCode QR_INVALID_CHECKSUM when verification fails
**Deliverable:** EMVCoCrcVerifier.java with verify() and compute() methods
**Acceptance / logic checks:**
- Known vector: EMVCo spec sample payload must produce matching CRC-16 value (use standard EMVCo test vector from spec appendix, e.g. '00020101021153160004000205678900000000000005303156304A60A' — verify last 4 chars A60A)
- verify() returns true for correct payload
- verify() throws QRInvalidChecksumException for payload where last 4 chars are changed to '0000'
- compute() on empty string returns 'FFFF' (CRC of empty = init value 0xFFFF, no processing)
- Tag 63 absent from payload throws QRMalformedException not NullPointerException
**Depends on:** 5.4-T02

### 5.4-T06 — Implement ZeroPayQRParser — mandatory tag extraction  _(35 min)_
**Context:** WBS 5.4 — QR Parser. ZeroPayQRParser implements SchemeQRParser for schemeId=ZEROPAY. Parse steps (SCH-06 §3.4): (1) Verify CRC-16 via EMVCoCrcVerifier — throw QR_INVALID_CHECKSUM on fail. (2) Confirm tag 00 value = '01' (format indicator) — throw QR_MALFORMED if absent or wrong. (3) Extract tag 52 (MCC), tag 53 (currency — must be '410' for KRW, else QR_CURRENCY_MISMATCH), tag 58 (country), tag 59 (merchantName), tag 60 (merchantCity). (4) All of tags 00, 52, 53, 58, 59, 60, 63 are mandatory — missing any throws QR_MALFORMED. Encoded amount is optional (tag 54). This ticket: implement mandatory tags and CRC only; MAI extraction is T07.
**Steps:** Create ZeroPayQRParser implements SchemeQRParser in package com.gmepayplus.qrparser.zeropay; Implement parse(String rawPayload): call EMVCoCrcVerifier.verify(), then EMVCoTlvParser.parseTopLevel(); Check tag 00 == '01'; check all mandatory tags present (52,53,58,59,60,63); check tag 53 == '410'; Extract optional tag 54 as BigDecimal if present; Return a partially-filled ParsedQRPayload (maiTag=0, merchantId=null, qrCodeId=null — filled by T07); Annotate with @Component to auto-register
**Deliverable:** ZeroPayQRParser.java with mandatory tag validation and partial ParsedQRPayload construction
**Acceptance / logic checks:**
- Valid payload with all mandatory tags and tag53=410 returns ParsedQRPayload with crcVerified=true
- Payload with tag53=840 (USD) throws QRCurrencyMismatchException
- Payload missing tag52 throws QRMalformedException
- Payload with bad CRC throws QRInvalidChecksumException before any other validation
- Tag54 present as '50000' is parsed to encodedAmount=BigDecimal(50000); absent gives null
**Depends on:** 5.4-T03, 5.4-T04, 5.4-T05

### 5.4-T07 — Implement ZeroPayQRParser — MAI slot extraction (merchant_id, qr_code_id)  _(35 min)_
**Context:** WBS 5.4 — QR Parser. After mandatory tags pass, ZeroPayQRParser must locate the ZeroPay MAI (Merchant Account Information) slot. MAI uses tags 26-51 in the EMVCo top-level TLV. ZeroPay occupies one registered tag in this range (Assumption A-05 in SCH-06: exact tag TBD; treat as configurable — load from scheme config key zeropay.mai_tag, default 26). Within the MAI template, sub-tag 01 = merchant_id (CHAR up to 10), sub-tag 02 = qr_code_id (CHAR up to 20), sub-tag 00 = reverse domain name (informational). Missing MAI tag throws QR_UNKNOWN_SCHEME. Missing sub-tag 01 or 02 throws QR_MALFORMED. Store resolved maiTag (int) and both IDs on ParsedQRPayload.
**Steps:** Inject schemeConfig to read zeropay.mai_tag (integer, default 26); After parseTopLevel, scan tags 26-51 for the configured MAI tag; if absent try all tags 26-51 and match by GUID prefix if scheme config includes a known reverse domain (optional fallback); Call EMVCoTlvParser.parseTemplate(maiValue) to get sub-tags; Extract sub-tag 01 as merchantId; sub-tag 02 as qrCodeId; Throw QRUnknownSchemeException if no MAI slot found; throw QRMalformedException if sub-tag 01 or 02 missing; Complete ParsedQRPayload with maiTag, merchantId, qrCodeId
**Deliverable:** Updated ZeroPayQRParser.java with full MAI extraction completing ParsedQRPayload
**Acceptance / logic checks:**
- MAI at tag 26 with sub-tag 01='M123456789' and sub-tag 02='QR00000000000000000001' populates ParsedQRPayload.merchantId and qrCodeId correctly
- Missing MAI entirely (no tags 26-51 in payload) throws QRUnknownSchemeException with errorCode QR_UNKNOWN_SCHEME
- MAI present but missing sub-tag 02 throws QRMalformedException
- Configuration zeropay.mai_tag=29 causes parser to look at tag 29 instead of 26
- merchantId is trimmed of whitespace before returning
**Depends on:** 5.4-T06

### 5.4-T08 — Implement QRParserServiceImpl.resolveIdentifier — merchant DB lookup  _(30 min)_
**Context:** WBS 5.4 — QR Parser. After parsing, resolveIdentifier(ParsedQRPayload) queries the local merchants table (see DAT-03 §7.4): SELECT * FROM merchant WHERE merchant_id = :merchantId. Merchant table columns: merchant_id VARCHAR(50), status VARCHAR(20) (ACTIVE/SUSPENDED/DEACTIVATED), is_active BOOLEAN, merchant_type VARCHAR(30), fee_tier VARCHAR(20). Resolution rules (SCH-06 §3.5): (1) merchant_id must exist — else MERCHANT_NOT_FOUND. (2) status must be ACTIVE and is_active=true — else MERCHANT_INACTIVE. (3) Next ticket handles QR validation. Returns partial MerchantIdentifier (without qrCodeId confirmed yet).
**Steps:** Inject MerchantRepository (Spring Data JPA) into QRParserServiceImpl; In resolveIdentifier(), query merchantRepository.findByMerchantId(parsed.getMerchantId()); Throw MerchantNotFoundException(MERCHANT_NOT_FOUND) if Optional is empty; Throw MerchantInactiveException(MERCHANT_INACTIVE) if status != ACTIVE or is_active = false; Build partial MerchantIdentifier with merchantId, merchantType, feeTier, merchantName from DB row; Continue to QR resolution (T09) before returning
**Deliverable:** Merchant lookup logic in QRParserServiceImpl.resolveIdentifier(), with MerchantRepository dependency
**Acceptance / logic checks:**
- merchant_id 'UNKNOWN123' not in DB throws MerchantNotFoundException
- merchant with status=SUSPENDED throws MerchantInactiveException
- merchant with status=ACTIVE and is_active=false throws MerchantInactiveException
- merchant with status=ACTIVE and is_active=true proceeds without exception
- Returned partial MerchantIdentifier has merchantType and feeTier from merchant row
**Depends on:** 5.4-T03, 5.4-T02

### 5.4-T09 — Implement QRParserServiceImpl.resolveIdentifier — QR code DB lookup and cross-check  _(30 min)_
**Context:** WBS 5.4 — QR Parser. After merchant lookup passes, validate the QR code (SCH-06 §3.5 steps 3-5). Query qr_code table (DAT-03 §7.5): columns qr_code_id CHAR(20), merchant_id BIGINT FK, status VARCHAR(20) (ACTIVE/DEACTIVATED), is_active BOOLEAN. Rules: (1) qr_code_id must exist — QR_NOT_FOUND. (2) status must be ACTIVE — QR_DEACTIVATED. (3) qr_code.merchant_id (FK to merchant.id) must match the merchant resolved in T08 — QR_MERCHANT_MISMATCH (prevents one merchant spoofing another's QR). On success, complete MerchantIdentifier adding qrCodeId.
**Steps:** Inject QRCodeRepository into QRParserServiceImpl; Look up qrCodeRepository.findByQrCodeId(parsed.getQrCodeId()); Throw QRParseException(QR_NOT_FOUND) if absent; Throw QRDeactivatedException(QR_DEACTIVATED) if status != ACTIVE or is_active=false; Compare qrCode.getMerchantFk() with resolved merchant.getId(); if mismatch throw QRMerchantMismatchException(QR_MERCHANT_MISMATCH); Set qrCodeId on MerchantIdentifier and return completed object
**Deliverable:** QR code lookup and cross-check logic in QRParserServiceImpl.resolveIdentifier()
**Acceptance / logic checks:**
- qr_code_id 'NOQR' absent from DB throws exception with code QR_NOT_FOUND
- Active qr_code referencing merchant_id=99 but parsed payload has merchant_id resolving to merchant.id=42 throws QR_MERCHANT_MISMATCH
- Deactivated qr_code (status=DEACTIVATED) throws QR_DEACTIVATED even if merchant is active
- Happy path: active merchant + active QR with matching FK returns completed MerchantIdentifier with qrCodeId populated
- resolveIdentifier is a single transactional method — merchant lookup and QR lookup both use the same DB transaction (@Transactional(readOnly=true))
**Depends on:** 5.4-T08

### 5.4-T10 — Add DB indexes on merchant.merchant_id and qr_code.qr_code_id for payment-critical path  _(25 min)_
**Context:** WBS 5.4 — QR Parser. AD-12 (SAD-02) mandates local DB lookups on the payment critical path with sub-second latency. The merchants table and qr_code table are queried on every MPM payment. Currently merchant_id is VARCHAR(50) UNIQUE (index exists by PK uniqueness) and qr_code.qr_code_id is VARCHAR(512) UNIQUE (from DAT-03). Verify both UNIQUE constraints translate to indexes; additionally add a composite index on qr_code(merchant_id, status) to support validation queries that filter both. Write as a Flyway migration file.
**Steps:** Create migration V5_4__qr_parser_indexes.sql in src/main/resources/db/migration; Add: CREATE INDEX IF NOT EXISTS idx_merchant_merchant_id ON merchant(merchant_id); (belt-and-suspenders if UNIQUE already present); Add: CREATE INDEX IF NOT EXISTS idx_qr_code_qr_code_id ON qr_code(qr_code_id);; Add: CREATE INDEX IF NOT EXISTS idx_qr_code_merchant_status ON qr_code(merchant_id, status);; Add comment block explaining purpose: payment-time merchant resolution, SCH-06 AD-12; Verify migration applies cleanly with Flyway validate
**Deliverable:** V5_4__qr_parser_indexes.sql Flyway migration
**Acceptance / logic checks:**
- \d merchant in psql shows index on merchant_id column after migration
- \d qr_code shows index on qr_code_id and composite index on (merchant_id, status)
- EXPLAIN ANALYZE on SELECT * FROM merchant WHERE merchant_id='M123' shows Index Scan, not Seq Scan on a table with 100k rows
- Migration is idempotent (IF NOT EXISTS) — running twice does not error
- Migration file version V5_4 does not conflict with existing migration versions

### 5.4-T11 — Implement CPM payload handling — pass-through and token structure  _(30 min)_
**Context:** WBS 5.4 — QR Parser. In CPM mode the customer shows a dynamic QR generated by ZeroPay via GMEPay+ (SCH-06 §3.3). The CPM QR contains a one-time payment token issued by ZeroPay, not a merchant identity. Therefore parseMerchantQR is NOT called for inbound CPM payloads — the ZeroPay Adapter handles CPM token relay. However, the parser module must expose a method parseCpmToken(String rawPayload) -> CpmTokenPayload that extracts the opaque token string. CpmTokenPayload{token:String, schemeId:String, issuedAt:Instant, expiresAt:Instant (token TTL default 60s per SCH-06 §3.3)}. If the payload cannot be decoded, throw QRMalformedException. CPM token payloads use EMVCo TLV; the token is in a scheme-specific tag.
**Steps:** Create CpmTokenPayload record with fields: token, schemeId, issuedAt, expiresAt; Add parseCpmToken(String rawPayload, String schemeId) method to QRParserService interface; Implement in ZeroPayQRParser as parseCpmPayload(String rawPayload): extract token from ZeroPay CPM-specific MAI slot (treat as configurable tag, default 26); compute expiresAt = issuedAt + 60s; Add isExpired() convenience method to CpmTokenPayload; Throw QRMalformedException if token field is blank or missing
**Deliverable:** CpmTokenPayload.java and parseCpmToken implementation in ZeroPayQRParser
**Acceptance / logic checks:**
- Valid CPM payload returns CpmTokenPayload with non-blank token
- isExpired() returns false for freshly issued token (issuedAt = now)
- isExpired() returns true for token with expiresAt 61 seconds in the past
- Missing token sub-tag throws QRMalformedException with code QR_MALFORMED
- CPM parsing does not touch merchant or qr_code DB tables
**Depends on:** 5.4-T07

### 5.4-T12 — Implement QRParserServiceImpl.parse() end-to-end for MPM mode  _(30 min)_
**Context:** WBS 5.4 — QR Parser. Wire full MPM parse path: QRParserServiceImpl.parse(rawPayload, schemeId) must (1) route to matching SchemeQRParser, (2) call parser.parse(rawPayload) getting ParsedQRPayload, (3) verify parsedPayload.crcVerified=true (belt-and-suspenders guard), (4) return the ParsedQRPayload. The resolveIdentifier() step is a separate call made by the Orchestrator after parse(). QRParserService is a @Service singleton. Ensure thread safety (no mutable state). Add MDC logging: log schemeId, outcome (SUCCESS/FAIL), errorCode on failure at INFO level. Do not log rawPayload (may contain PII).
**Steps:** Complete QRParserServiceImpl.parse() to call the correct SchemeQRParser via supports() matching; Add guard: if returned parsedPayload.crcVerified==false throw QRInvalidChecksumException (defensive; parser should have already checked); Wrap parse call in try/catch QRParseException; log at WARN with MDC fields schemeId and errorCode before re-throwing; Log successful parse at DEBUG with schemeId and merchantId (no raw payload); Annotate class @Slf4j; use MDC.put/remove in try-finally
**Deliverable:** Completed QRParserServiceImpl.parse() method with logging, no mutable state
**Acceptance / logic checks:**
- parse(validMpmPayload, ZEROPAY) returns ParsedQRPayload with all mandatory fields populated
- parse(validPayload, KHQR) throws QRUnknownSchemeException (no KHQR parser registered)
- QRParseException is re-thrown with original errorCode intact after logging
- No rawPayload string appears in any log output (verified by log capture in test)
- Calling parse() concurrently from 10 threads returns correct results with no race condition (parsers are stateless)
**Depends on:** 5.4-T06, 5.4-T07, 5.4-T03

### 5.4-T13 — Unit tests — EMVCo TLV tokenizer edge cases  _(35 min)_
**Context:** WBS 5.4 — QR Parser. EMVCoTlvParser (T04) needs a comprehensive test class covering the edge-case vectors that would corrupt merchant resolution if unhandled. Test file: EMVCoTlvParserTest.java. Use JUnit 5 + AssertJ. No Spring context needed — pure unit tests.
**Steps:** Create EMVCoTlvParserTest.java in src/test matching package; Test 1: normal two-tag string '0002015204AB' -> {0:'01', 52:'AB'}; Test 2: declared length 99 but only 3 chars remain -> QRMalformedException; Test 3: null input -> QRMalformedException; Test 4: empty string -> QRMalformedException; Test 5: nested template parse of '01' + length + 'M123456789' + '02' + length + 'QR001' returns correct sub-tags; Test 6: duplicate top-level tag -> QRMalformedException; Test 7: zero-length value ('520052' = tag 52, length 0, tag 52 again) -> tag 52 value is empty string (not an error)
**Deliverable:** EMVCoTlvParserTest.java with 7 test methods all passing
**Acceptance / logic checks:**
- All 7 tests pass (mvn test -pl qr-parser -Dtest=EMVCoTlvParserTest)
- No Mockito or Spring context used — pure unit
- Length-overflow test confirms exception type is QRMalformedException with errorCode QR_MALFORMED
- Nested template test confirms parseTemplate('01' + len + value + ...) returns sub-tag map with key 1
**Depends on:** 5.4-T04

### 5.4-T14 — Unit tests — CRC-16/CCITT checksum verifier  _(35 min)_
**Context:** WBS 5.4 — QR Parser. EMVCoCrcVerifier (T05) must be tested with known vectors. EMVCo spec provides sample payloads with known CRC values. The polynomial is 0x1021, init 0xFFFF, no reflection. Test class: EMVCoCrcVerifierTest.java. Known test vector from EMVCo QR code spec appendix: payload string ending in '6304' then CRC. A widely cited sample: '00020101021153160004000205678900000000000005303156304' with CRC 'A60A' (to be verified against the spec; use this as the reference vector and confirm by running the algorithm).
**Steps:** Create EMVCoCrcVerifierTest.java; Test 1: compute('00020101021153160004000205678900000000000005303156304') == 'A60A' (or correct value from EMVCo spec; developer must confirm against official spec vector); Test 2: verify(payload + 'A60A') returns true for the above payload; Test 3: verify(payload + '0000') throws QRInvalidChecksumException; Test 4: payload without tag 63 throws QRMalformedException; Test 5: compute empty string -> returns 4-char uppercase hex string (exact value depends on algorithm; verify it equals CRC-16/CCITT of empty); Test 6: single ASCII char 'A' (0x41) -> manually computed CRC-16/CCITT value (developer must compute and hardcode)
**Deliverable:** EMVCoCrcVerifierTest.java with 6 test methods all passing
**Acceptance / logic checks:**
- Test 1 CRC vector matches official EMVCo reference (developer must confirm against spec appendix)
- verify() with tampered CRC throws QRInvalidChecksumException, not returns false
- Tag 63 absent throws QRMalformedException not NullPointerException
- All 6 tests pass with no external dependencies
**Depends on:** 5.4-T05

### 5.4-T15 — Unit tests — ZeroPayQRParser mandatory tag and currency validation  _(45 min)_
**Context:** WBS 5.4 — QR Parser. ZeroPayQRParser (T06, T07) needs tests covering each rejection path. Build synthetic TLV payloads in tests using a helper that constructs valid TLV strings and computes correct CRC. Test class: ZeroPayQRParserTest.java. The parser is a Spring @Component but tests instantiate it directly with a mock SchemeConfigService.
**Steps:** Create TlvBuilder test helper: addTag(int tag, String value).build() produces a valid TLV string with correct CRC appended; Test 1 (happy path): payload with tags 00=01, 52=5411, 53=410, 58=KR, 59=TestMerchant, 60=Seoul, MAI@26 with sub01=M123 sub02=QR001 -> ParsedQRPayload.merchantId='M123'; Test 2: tag53=840 -> QRCurrencyMismatchException; Test 3: tag00=02 -> QRMalformedException; Test 4: missing tag52 -> QRMalformedException; Test 5: MAI slot absent (no tag in 26-51 range) -> QRUnknownSchemeException; Test 6: MAI present but sub-tag01 missing -> QRMalformedException; Test 7: bad CRC -> QRInvalidChecksumException raised before any other check
**Deliverable:** ZeroPayQRParserTest.java with TlvBuilder helper and 7 test methods all passing
**Acceptance / logic checks:**
- TlvBuilder produces parseable payloads that pass CRC verification in Test 1
- Test 7 (bad CRC) fails before tag validation — confirmed by adding a mock spy that would throw on tag parsing; it is never called
- Test 2 exception has errorCode QR_CURRENCY_MISMATCH
- Test 5 exception has errorCode QR_UNKNOWN_SCHEME
- All 7 tests pass with no Spring context (direct instantiation)
**Depends on:** 5.4-T06, 5.4-T07

### 5.4-T16 — Unit tests — resolveIdentifier merchant and QR happy path + failure paths  _(35 min)_
**Context:** WBS 5.4 — QR Parser. QRParserServiceImpl.resolveIdentifier() (T08, T09) must be tested with mocked repositories. No DB needed. Test class: QRParserServiceImplResolutionTest.java. Use Mockito to stub MerchantRepository and QRCodeRepository. Build a standard ParsedQRPayload fixture: merchantId='M123456789', qrCodeId='QR00000000000000000001'. Build a Merchant entity fixture: id=42, merchant_id='M123456789', status=ACTIVE, is_active=true, merchant_type=GENERAL, fee_tier=DOMESTIC. Build a QRCode entity fixture: id=7, merchant_id=42, qr_code_id='QR00000000000000000001', status=ACTIVE, is_active=true.
**Steps:** Create QRParserServiceImplResolutionTest.java with @ExtendWith(MockitoExtension.class); Test 1 (happy path): stubs return merchant+qr fixtures -> MerchantIdentifier.merchantId='M123456789', qrCodeId='QR00000000000000000001', feeTier=DOMESTIC; Test 2: merchantRepository returns empty -> MerchantNotFoundException with errorCode MERCHANT_NOT_FOUND; Test 3: merchant status=SUSPENDED -> MerchantInactiveException with MERCHANT_INACTIVE; Test 4: merchant active, qrCode absent -> QR_NOT_FOUND; Test 5: qrCode status=DEACTIVATED -> QR_DEACTIVATED; Test 6: qrCode.merchantFk=99 but merchant.id=42 -> QR_MERCHANT_MISMATCH
**Deliverable:** QRParserServiceImplResolutionTest.java with 6 test methods all passing
**Acceptance / logic checks:**
- Test 1 MerchantIdentifier has feeTier=DOMESTIC and qrCodeId='QR00000000000000000001'
- Test 6 exception has errorCode QR_MERCHANT_MISMATCH confirming cross-check logic
- No real DB calls — only Mockito stubs
- All 6 tests pass and QRCodeRepository.findByQrCodeId is never called when merchant lookup fails in Test 2 (short-circuit)
**Depends on:** 5.4-T08, 5.4-T09

### 5.4-T17 — Integration test — full MPM parse + resolve with H2 test DB  _(40 min)_
**Context:** WBS 5.4 — QR Parser. An end-to-end Spring integration test verifies parse() + resolveIdentifier() work together against a real (H2 in-memory) database. Seed DB with: merchant row (merchant_id='M999', status=ACTIVE, is_active=true, merchant_type=GENERAL, fee_tier=DOMESTIC) and qr_code row (qr_code_id='QR999', merchant_id FK to above, status=ACTIVE, is_active=true). Use TlvBuilder from T15 to build a valid ZeroPay payload embedding merchantId='M999', qrCodeId='QR999'. Test class: QRParserIntegrationTest.java with @SpringBootTest(classes={...}) slicing.
**Steps:** Create QRParserIntegrationTest annotated @SpringBootTest and @Transactional; Seed merchant and qr_code rows in @BeforeEach via repositories; Build valid payload with TlvBuilder: merchantId=M999, qrCodeId=QR999, tag53=410, all mandatory tags; Call qrParserService.parse(payload, ZEROPAY) -> verify ParsedQRPayload.merchantId=M999; Call qrParserService.resolveIdentifier(parsed) -> verify MerchantIdentifier.qrCodeId='QR999' and feeTier='DOMESTIC'; Add negative test: change qr_code.status to DEACTIVATED in DB; call resolveIdentifier again -> QR_DEACTIVATED
**Deliverable:** QRParserIntegrationTest.java with happy path and deactivated-QR tests passing against H2
**Acceptance / logic checks:**
- Happy path resolveIdentifier returns MerchantIdentifier with qrCodeId='QR999'
- After deactivating QR in DB, resolveIdentifier throws exception with code QR_DEACTIVATED
- CRC computation in TlvBuilder produces a payload that passes EMVCoCrcVerifier.verify()
- Test runs in under 10 seconds (no external dependencies, H2 in-memory)
**Depends on:** 5.4-T09, 5.4-T15, 5.4-T10

### 5.4-T18 — Add Flyway migration for merchant and qr_code tables if not already created by earlier WBS  _(30 min)_
**Context:** WBS 5.4 — QR Parser. The merchant and qr_code tables are defined in DAT-03 §7.4 and §7.5. If a prior WBS migration (e.g. WBS 1.x data model migrations) has not already created these tables, create them here. Check existing migration files first. Merchant columns: id BIGINT PK, merchant_id VARCHAR(50) UNIQUE NOT NULL, merchant_type VARCHAR(30), name VARCHAR(200), business_registration_no VARCHAR(30), franchise_code VARCHAR(30), category_code VARCHAR(20), bank_code VARCHAR(10), account_no VARCHAR(30), fee_tier VARCHAR(20), status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', is_active BOOLEAN NOT NULL DEFAULT TRUE, synced_at TIMESTAMPTZ, created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ. QR code table per DAT-03 §7.5.
**Steps:** Check existing Flyway migrations for CREATE TABLE merchant and CREATE TABLE qr_code; If absent, create V5_4_2__merchant_and_qr_code_tables.sql; Add merchant table DDL with all columns from context; add UNIQUE(merchant_id); Add qr_code table DDL: id, merchant_id BIGINT FK REFERENCES merchant(id), qr_code_value VARCHAR(512) UNIQUE, qr_code_type VARCHAR(10), status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', is_active BOOLEAN NOT NULL DEFAULT TRUE, synced_at, created_at, updated_at; Add merchant_sync_log table per DAT-03 §7.6 if absent: id, file_type, sync_date, records_received/inserted/updated/deactivated, error_count, status, detail, created_at; Verify migration applies on clean DB
**Deliverable:** V5_4_2__merchant_and_qr_code_tables.sql (only if not already created by prior WBS migrations)
**Acceptance / logic checks:**
- After migration, psql \d merchant shows all columns including fee_tier and is_active
- qr_code.merchant_id has FK constraint to merchant(id)
- account_no column has no plaintext constraint — encryption is application-level per DAT-03 note
- merchant_sync_log table exists with status VARCHAR(20)
- Migration is idempotent only via Flyway versioning — duplicate version rejected, confirming it has a unique version number

### 5.4-T19 — Expose QRParserService via internal REST endpoint for Orchestrator  _(40 min)_
**Context:** WBS 5.4 — QR Parser. The Transaction Orchestrator calls QRParserService internally (same JVM), but for testability and potential microservice extraction, expose a REST endpoint POST /internal/v1/qr/parse accepting {rawPayload:String, schemeId:String} returning ParsedQRPayload JSON, and POST /internal/v1/qr/resolve accepting ParsedQRPayload JSON returning MerchantIdentifier JSON. These are internal-only endpoints (not on the Northbound API). Secure with internal API key header X-Internal-Token (value from application config internal.api.token). Return 422 with {errorCode, message} on any QRParseException.
**Steps:** Create QRParserController @RestController at path /internal/v1/qr; Implement POST /parse: deserialize request, call qrParserService.parse(), return 200 with ParsedQRPayload; Implement POST /resolve: deserialize ParsedQRPayload, call resolveIdentifier(), return 200 with MerchantIdentifier; Add @PreAuthorize or filter checking X-Internal-Token header against internal.api.token config value; Add @ExceptionHandler(QRParseException.class) returning 422 {errorCode: ex.getErrorCode().name(), message: ex.getMessage()}; Do not expose on the public API gateway (configure route exclusion)
**Deliverable:** QRParserController.java at /internal/v1/qr with parse and resolve endpoints
**Acceptance / logic checks:**
- POST /internal/v1/parse with valid ZeroPay payload and correct X-Internal-Token returns 200 with ParsedQRPayload JSON
- POST /internal/v1/parse with wrong X-Internal-Token returns 401
- POST /internal/v1/parse with payload missing tag52 returns 422 with errorCode=QR_MALFORMED
- POST /internal/v1/resolve with non-existent merchantId returns 422 with errorCode=MERCHANT_NOT_FOUND
- rawPayload is never logged at INFO level (PII guard)
**Depends on:** 5.4-T12, 5.4-T09

### 5.4-T20 — Unit test — QRParserController error mapping and security filter  _(30 min)_
**Context:** WBS 5.4 — QR Parser. Test QRParserController (T19) using @WebMvcTest slice. Mock QRParserService. Verify HTTP status codes and response body shapes for success and each error code. Also verify the X-Internal-Token filter blocks requests with wrong or missing token.
**Steps:** Create QRParserControllerTest.java with @WebMvcTest(QRParserController.class); Mock QRParserService via @MockBean; Test 1: valid token + parse throws QRMalformedException -> 422 body contains errorCode=QR_MALFORMED; Test 2: valid token + resolve returns MerchantIdentifier -> 200 with merchantId in JSON; Test 3: missing X-Internal-Token header -> 401; Test 4: wrong token value -> 401; Test 5: resolve throws MerchantInactiveException -> 422 with errorCode=MERCHANT_INACTIVE
**Deliverable:** QRParserControllerTest.java with 5 test methods all passing
**Acceptance / logic checks:**
- Test 1 response body JSON has field errorCode with value QR_MALFORMED
- Test 3 and 4 both return HTTP 401 with no QRParserService invocation (verify with Mockito.verifyNoInteractions)
- Test 2 response body has merchantId field
- Test 5 status is 422 not 500
- All 5 tests pass without starting a real Spring application server
**Depends on:** 5.4-T19

### 5.4-T21 — Add merchant and QR resolution metrics (Micrometer counters)  _(30 min)_
**Context:** WBS 5.4 — QR Parser. For operational visibility (NFR-10), instrument QRParserServiceImpl with Micrometer counters: qr.parse.total (tags: scheme, outcome=success|failure), qr.parse.errors (tags: scheme, errorCode), qr.resolve.total (tags: outcome), qr.resolve.errors (tags: errorCode). Use MeterRegistry injected via constructor. Increment on every call. Record parse latency as qr.parse.duration Timer (tag: scheme). Do not record latency for failures after CRC (CRC failure is fast; only record when full parse attempted).
**Steps:** Inject MeterRegistry into QRParserServiceImpl constructor; Add Counter qr.parse.total with tags scheme and outcome; increment in parse() on success and in catch block for failure; Add Counter qr.parse.errors with tags scheme and errorCode; increment only on QRParseException; Add Timer qr.parse.duration; record full parse latency via Timer.record(); Add Counter qr.resolve.total and qr.resolve.errors with tag errorCode; Wire in resolveIdentifier similarly
**Deliverable:** Micrometer instrumentation in QRParserServiceImpl with 4 metrics as described
**Acceptance / logic checks:**
- After calling parse() twice with success and once with QR_MALFORMED, counter qr.parse.total{outcome=success} = 2 and qr.parse.total{outcome=failure} = 1
- Counter qr.parse.errors{errorCode=QR_MALFORMED} = 1
- Timer qr.parse.duration has count >= 1 after a successful parse
- Failed CRC parse (QRInvalidChecksumException) still increments qr.parse.total{outcome=failure}
- MeterRegistry is a constructor parameter — no static global references
**Depends on:** 5.4-T12

### 5.4-T22 — Document QRParserService public contract in CLAUDE.md / developer README section  _(25 min)_
**Context:** WBS 5.4 — QR Parser. The QR Parser is a standalone module. A developer adding a new scheme must know: (a) how to implement SchemeQRParser, (b) which error codes to throw, (c) what fields are mandatory on ParsedQRPayload. Write a concise section (max 1 page) in the module-level README or CLAUDE.md under heading 'QR Parser Module'. Include: interface method signatures, error code table with conditions, the EMVCo tag reference (tags 00, 52, 53, 58, 59, 60, 63, 26-51 MAI), and a 5-line example of how to register a new scheme parser. Do NOT duplicate spec text verbatim.
**Steps:** Open (or create if absent) src/main/java/com/gmepayplus/qrparser/README.md; Write section: Overview (2 sentences), SchemeQRParser interface signature, Error codes table (code + trigger condition), EMVCo tag quick reference, New-scheme recipe (5 bullet steps); Keep to <=60 lines; No spec text verbatim — summarise in own words; Commit alongside code
**Deliverable:** qrparser/README.md with QR Parser developer documentation section
**Acceptance / logic checks:**
- README contains SchemeQRParser interface signature (supports + parse methods)
- Error codes table lists at minimum QR_INVALID_CHECKSUM, QR_MALFORMED, QR_UNKNOWN_SCHEME, QR_CURRENCY_MISMATCH
- New-scheme recipe includes: implement SchemeQRParser, annotate @Component, configure mai_tag, register schemeId in scheme config
- Document is <=60 lines
- No sentences copied verbatim from spec_full.txt
**Depends on:** 5.4-T03, 5.4-T07
