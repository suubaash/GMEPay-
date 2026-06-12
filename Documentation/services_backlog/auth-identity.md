# auth-identity  (backend)

**Scope:** Partner HMAC, operator OAuth2/JWT+RBAC, mTLS, key lifecycle

**Owned WBS work-packages:** 8.2, 13.2, 13.9  ·  **Tickets:** 80  ·  **Est:** 51.8h

## Service contract (MSA: own DB, API-only communication)

- **Datastore (owned by this service):** Vault-backed secrets; partner creds via config-registry
- **APIs / events I EXPOSE:** /internal/auth/verify, JWT issue/verify
- **APIs / events I CONSUME:** config-registry (partner credentials, sync)
- **Integration rule:** never read another service's database or import its private entities — call its API or consume its event; stub consumed services with WireMock in tests.

> Self-contained backlog for this service. Build it as its own repo/module with its own DB + Flyway migrations, against the `shared-libs` contracts (lib-money / lib-errors / lib-events / lib-api-contracts only). Each ticket has a deliverable + acceptance checks.


## WBS 8.2 — Auth: API key + HMAC signing
### 8.2-T01 — Flyway migration: partner_credential table in auth-identity service  _(25 min)_
**Context:** WBS 8.2 delivers the Spring Cloud Gateway HMAC-SHA256 auth filter. Before any filter logic, the DB schema must exist. The partner_credential table (SEC-09 §2.3, API-05 §3.1, DAT-03 §4.4) holds: id BIGINT GENERATED ALWAYS AS IDENTITY PK, partner_id BIGINT FK->partner ON DELETE RESTRICT, api_key VARCHAR(64) UNIQUE NOT NULL, api_secret_hash VARCHAR(128) NOT NULL (bcrypt cost>=12 of api_secret, plaintext shown once then discarded), is_active BOOLEAN NOT NULL DEFAULT TRUE, expires_at TIMESTAMPTZ NULL (NULL=no expiry), created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL, created_by VARCHAR(120), updated_by VARCHAR(120). Partial unique index on (partner_id) WHERE is_active=TRUE enforces one-active-credential-per-partner. HMAC secret is NOT stored here; it lives in Vault (see 8.2-T03). Hard deletes prohibited on this table.
**Steps:** Determine next Flyway version number in services/auth-identity/src/main/resources/db/migration/; Create V{n}__partner_credential.sql with CREATE TABLE partner_credential as specified; Add UNIQUE constraint on api_key and partial unique index: CREATE UNIQUE INDEX uidx_partner_credential_active ON partner_credential (partner_id) WHERE is_active = TRUE; Add FK: partner_id BIGINT REFERENCES partner(id) ON DELETE RESTRICT; Add SQL header comment citing API-05 §3.1, SEC-09 §2.3, DAT-03 §4.4; Run ./gradlew :services:auth-identity:flywayMigrate against local PostgreSQL on port 5433
**Deliverable:** services/auth-identity/src/main/resources/db/migration/V{n}__partner_credential.sql
**Acceptance / logic checks:**
- Migration applies cleanly on a fresh schema (flywayInfo shows success)
- UNIQUE index on api_key prevents duplicate keys (INSERT with same api_key raises unique violation)
- Partial unique index allows two is_active=FALSE rows for same partner but rejects second is_active=TRUE row
- FK on partner_id: INSERT with non-existent partner_id raises foreign key violation
- api_secret_hash is VARCHAR(128) not TEXT
- No column named hmac_secret or api_secret (secret not stored in DB)

### 8.2-T02 — PartnerCredential JPA entity and Spring Data repository in auth-identity module  _(25 min)_
**Context:** The auth-identity service (services/auth-identity, Spring Boot 3.x, Java 21) needs a JPA entity mapping to the partner_credential table from 8.2-T01. The entity must expose findByApiKeyAndIsActiveTrue(String apiKey) used by the HMAC filter to look up the hashed secret. Uses lib-persistence common JPA utilities. No plaintext secret is ever stored or returned; only api_secret_hash. api_secret_hash column stores a BCrypt hash (starts with $2a$ or $2b$) used only for identity confirmation at credential generation, not for HMAC signing (which uses the Vault-stored hmac_secret).
**Steps:** In services/auth-identity/src/main/java/com/gme/pay/auth/domain/ create PartnerCredential.java as @Entity @Table(name=partner_credential) with all columns mapped; Annotate id with @Id @GeneratedValue(strategy=GenerationType.IDENTITY); Create PartnerCredentialRepository.java extending JpaRepository<PartnerCredential,Long> with Optional<PartnerCredential> findByApiKeyAndIsActiveTrue(String apiKey); Add @Transactional(readOnly=true) on the repository query method; Ensure no getter is named getApiSecretPlaintext (the only secret field is api_secret_hash)
**Deliverable:** services/auth-identity/src/main/java/com/gme/pay/auth/domain/PartnerCredential.java and PartnerCredentialRepository.java
**Acceptance / logic checks:**
- findByApiKeyAndIsActiveTrue returns non-empty Optional for a known active api_key
- findByApiKeyAndIsActiveTrue returns empty Optional for unknown api_key or inactive credential
- No field or getter named api_secret exists on the entity (only api_secret_hash)
- Spring Data generates SELECT ... WHERE api_key=? AND is_active=TRUE (verify via Hibernate SQL log in test)
- ./gradlew :services:auth-identity:compileJava succeeds with no unmapped column warnings
**Depends on:** 8.2-T01

### 8.2-T03 — PartnerCredentialService: Spring Cloud Vault integration for HMAC secret retrieval  _(35 min)_
**Context:** Per SEC-09 §2.3 and §9.1, the partner HMAC signing secret (distinct from the bcrypt-hashed api_secret) is stored encrypted in HashiCorp Vault at path secret/data/partners/{partner_id}/hmac_secret. The auth-identity service uses Spring Cloud Vault (spring-cloud-vault-config, spring-vault-core) to read this secret at request time. spring.cloud.vault.uri and spring.cloud.vault.token are injected via environment variables at runtime (never hardcoded). A Caffeine cache with 5-minute TTL reduces Vault round trips. Throws CredentialNotFoundException (lib-errors) if path absent.
**Steps:** Add spring-cloud-vault-config and spring-vault-core to services/auth-identity/build.gradle dependencies; Create PartnerCredentialService.java in com.gme.pay.auth.service annotated @Service; Inject VaultTemplate and implement String getHmacSecret(Long partnerId) that calls vaultTemplate.read(secret/data/partners/{partnerId}/hmac_secret) and returns the hmac_secret field; Add @Cacheable(value=hmacSecretCache, key=#partnerId) and configure a CacheManager with Caffeine TTL=300 seconds; Throw CredentialNotFoundException from lib-errors if Vault returns null or path not found; Ensure the returned secret is never written to any log statement
**Deliverable:** services/auth-identity/src/main/java/com/gme/pay/auth/service/PartnerCredentialService.java
**Acceptance / logic checks:**
- getHmacSecret(knownPartnerId) returns non-null non-empty string when Vault is populated at path secret/data/partners/{id}/hmac_secret
- getHmacSecret(unknownPartnerId) throws CredentialNotFoundException
- Vault path pattern is exactly secret/data/partners/{partnerId}/hmac_secret (kv-v2 format)
- HMAC secret is never referenced in any log.debug/info/error call (code review check)
- Cache hit on second call: Vault is not called a second time within 5 minutes (mock Vault to verify zero second call)
**Depends on:** 8.2-T02

### 8.2-T04 — HmacSignatureVerifier: pure Java HMAC-SHA256 canonical-string utility in lib-security  _(30 min)_
**Context:** Per SEC-09 §3.3 and API-05 §3.2, the canonical request string is: HTTP_METHOD + newline + PATH_WITH_QUERY + newline + X-Timestamp + newline + SHA256_HEX_OF_BODY (4 parts joined by literal backslash-n). The HMAC signature = HMAC-SHA256(canonical_string, hmac_secret) hex-encoded. X-Timestamp is ISO-8601 UTC millisecond precision. Body hash uses SHA-256 of raw request bytes; empty body (GET) uses SHA-256 of empty byte array = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855. Lives in lib/lib-security as a pure Java utility (no Spring dependency) so both api-gateway and future verifiers can reuse it.
**Steps:** Create lib/lib-security/src/main/java/com/gme/pay/security/HmacSignatureVerifier.java; Implement static String computeBodyHash(byte[] body) using MessageDigest.getInstance(SHA-256), hex-encoded lowercase; Implement static String buildCanonicalString(String method, String pathWithQuery, String timestamp, String bodyHash) joining with literal newline char; Implement static String computeSignature(String canonicalString, String hmacSecret) using Mac.getInstance(HmacSHA256), hex-encoded lowercase; Implement static boolean verifySignature(String expected, String canonicalString, String hmacSecret) using MessageDigest.isEqual to prevent timing attacks
**Deliverable:** lib/lib-security/src/main/java/com/gme/pay/security/HmacSignatureVerifier.java
**Acceptance / logic checks:**
- computeBodyHash(new byte[0]) equals e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
- computeSignature for a known input matches a pre-computed HMAC-SHA256 reference value
- verifySignature uses MessageDigest.isEqual not String.equals (constant-time comparison)
- buildCanonicalString joins exactly 4 parts with single newline character between each
- Module :lib:lib-security compiles with no imports of Spring Framework classes
- ./gradlew :lib:lib-security:test passes

### 8.2-T05 — TimestampValidator and RedisNonceStore: replay-protection utilities in lib-security  _(35 min)_
**Context:** Per SEC-09 §3.3 and API-05 §3.6, two replay guards: (1) |server_now - X-Timestamp| > 300 seconds -> reject HTTP 401 TIMESTAMP_DRIFT; (2) seen (partner_id, X-Nonce) within 600 seconds -> reject HTTP 401 REPLAY_DETECTED. X-Timestamp in API-05 is ISO-8601 UTC millisecond precision; SEC-09 also mentions Unix epoch integer format. Both must be parsed. Nonces stored in Redis with TTL=600s using key nonce:{partnerId}:{nonce} and SET NX EX (atomic check-and-set). NonceStore is an interface; RedisNonceStore is the ReactiveRedisTemplate implementation. Both classes go in lib/lib-security.
**Steps:** Create TimestampValidator.java with static boolean isWithinWindow(String xTimestamp, Instant serverNow, long windowSeconds): parse ISO-8601 if string contains T, otherwise parse as Unix epoch Long; compute absolute difference in seconds; Create NonceStore.java interface with Mono<Boolean> checkAndSetNonce(String partnerId, String nonce, Duration ttl); Create RedisNonceStore.java implementing NonceStore using ReactiveRedisTemplate<String,String>; key = nonce:{partnerId}:{nonce}; use setIfAbsent(key, 1, Duration) which maps to SET NX EX; Throw TimestampDriftException (extends AuthException from lib-errors) from filter when window exceeded; Throw ReplayDetectedException (extends AuthException from lib-errors) when checkAndSetNonce returns false
**Deliverable:** lib/lib-security/src/main/java/com/gme/pay/security/TimestampValidator.java and NonceStore.java and RedisNonceStore.java
**Acceptance / logic checks:**
- isWithinWindow(timestamp=now-300s, now, 300) returns true (boundary inclusive)
- isWithinWindow(timestamp=now-301s, now, 300) returns false
- isWithinWindow(timestamp=now+301s, now, 300) returns false (future timestamps also rejected)
- Unix epoch string (e.g. 1749034260) parses correctly without exception
- checkAndSetNonce returns Mono(true) on first call, Mono(false) on second call with same partner+nonce
- Redis key nonce:{partnerId}:{nonce} TTL is 600 seconds after first call
**Depends on:** 8.2-T04

### 8.2-T06 — PartnerResolver: WebClient call from api-gateway to auth-identity to resolve api_key  _(40 min)_
**Context:** The Spring Cloud Gateway filter (services/api-gateway) must resolve partner_id and hmac_secret from the X-API-Key header before verifying the HMAC. The auth-identity service exposes POST /internal/v1/credentials/resolve accepting {apiKey:String} and returning {partnerId:Long, hmacSecret:String, isActive:Boolean} over the internal mTLS network (Application Zone per SEC-09 §2.1). The Gateway calls this via WebClient with Resilience4j CircuitBreaker fallback. DTO classes live in lib-api-contracts. The hmac_secret in the response body is the raw Vault-retrieved secret (transmitted only on internal mTLS network).
**Steps:** Add CredentialResolveRequest {String apiKey} and CredentialResolveResponse {Long partnerId, String hmacSecret, boolean isActive} to lib/lib-api-contracts/src/main/java/com/gme/pay/contracts/auth/; In services/auth-identity create @RestController CredentialResolveController.java at POST /internal/v1/credentials/resolve; call PartnerCredentialRepository.findByApiKeyAndIsActiveTrue then PartnerCredentialService.getHmacSecret; return 200 or 401; In services/api-gateway create PartnerResolver.java @Component with WebClient; implement Mono<CredentialResolveResponse> resolve(String apiKey); Add @CircuitBreaker(name=authIdentity, fallbackMethod=resolveFallback) returning Mono.empty() on fallback; Configure WebClient base-url from property auth-identity.base-url (injected via environment variable AUTH_IDENTITY_BASE_URL)
**Deliverable:** services/auth-identity/src/main/java/com/gme/pay/auth/web/CredentialResolveController.java and services/api-gateway/src/main/java/com/gme/pay/gateway/auth/PartnerResolver.java
**Acceptance / logic checks:**
- POST /internal/v1/credentials/resolve with valid active api_key returns 200 with correct partnerId and non-null hmacSecret
- POST with unknown or inactive api_key returns 401
- PartnerResolver returns Mono.empty() when auth-identity is unreachable (circuit open or network error)
- hmacSecret in response is the actual secret value not a vault path reference
- Internal endpoint URL contains /internal/ prefix (not accessible from public network)
**Depends on:** 8.2-T03

### 8.2-T07 — HmacAuthGatewayFilter: Spring Cloud Gateway GlobalFilter enforcing HMAC-SHA256 auth  _(55 min)_
**Context:** The core WBS 8.2 deliverable: a Spring Cloud Gateway GlobalFilter (services/api-gateway) that enforces HMAC-SHA256 on every inbound partner request. Order=-10 (runs before routing). Per request: (1) extract X-API-Key, X-Timestamp, X-Signature, X-Nonce; (2) if any absent return 400 error.code=MISSING_REQUIRED_HEADER; (3) call PartnerResolver for {partnerId, hmacSecret}; (4) validate timestamp window 300s (401 TIMESTAMP_DRIFT); (5) check Redis nonce TTL=600s (401 REPLAY_DETECTED); (6) read body via DataBufferUtils.join, compute canonical = METHOD+newline+PATH_WITH_QUERY+newline+X-Timestamp+newline+SHA256_HEX(body), compare HMAC-SHA256 with X-Signature (401 INVALID_SIGNATURE); (7) mutate exchange to add X-Partner-ID header; (8) call chain.filter. All errors use lib-errors canonical shape {error:{code,message,request_id}}. X-Request-ID is generated and added to every response.
**Steps:** Create HmacAuthGatewayFilter.java in services/api-gateway/src/main/java/com/gme/pay/gateway/filter/ implementing GlobalFilter and Ordered; getOrder() returns -10; Inject PartnerResolver, TimestampValidator (from lib-security), NonceStore (RedisNonceStore), HmacSignatureVerifier (from lib-security); Implement reactive filter(ServerWebExchange, GatewayFilterChain) that caches request body using ServerRequest.create then DataBufferUtils.join; On each auth failure return a JSON error response using ServerHttpResponse.writeWith; set Content-Type: application/json and X-Request-ID header; On success mutate ServerHttpRequest to add X-Partner-ID:{partnerId} and pass to chain.filter
**Deliverable:** services/api-gateway/src/main/java/com/gme/pay/gateway/filter/HmacAuthGatewayFilter.java
**Acceptance / logic checks:**
- Valid signed request passes through and downstream receives X-Partner-ID header
- Missing X-API-Key returns HTTP 400 with error.code=MISSING_REQUIRED_HEADER
- X-Timestamp older than 300 seconds returns HTTP 401 with error.code=TIMESTAMP_DRIFT
- Replayed X-Nonce (same value within 600s) returns HTTP 401 with error.code=REPLAY_DETECTED
- Tampered body (signature mismatch) returns HTTP 401 with error.code=INVALID_SIGNATURE
- Unknown X-API-Key (PartnerResolver returns empty Mono) returns HTTP 401 with error.code=INVALID_API_KEY
- Every response carries X-Request-ID header
**Depends on:** 8.2-T05, 8.2-T06

### 8.2-T08 — IdempotencyGatewayFilter: Redis-backed 24-hour POST deduplication in api-gateway  _(55 min)_
**Context:** Per API-05 §2.6 and §7.1, all POST requests must include Idempotency-Key header (UUID, 16-128 chars). Hub stores (partner_id, idempotency_key) -> (response_status, response_body, body_hash) in Redis for 24 hours (86400s TTL). Duplicate same key+same body hash: return cached response, skip downstream. Same key+different body hash: return 422 IDEMPOTENCY_BODY_MISMATCH. Key exists but response not yet written (in-flight): return 409 with header X-Idempotency-Status: in_flight. GET requests are skipped entirely. This is GlobalFilter order=-9 (after HMAC filter). Partner_id comes from X-Partner-ID header set by 8.2-T07. Redis key: idem:{partnerId}:{idempotencyKey}; value is a Redis hash with fields status (IN_FLIGHT|COMPLETE), body_hash, response_status, response_body.
**Steps:** Create IdempotencyGatewayFilter.java in services/api-gateway/src/main/java/com/gme/pay/gateway/filter/ implementing GlobalFilter with getOrder()=-9; For GET/DELETE/PUT requests: call chain.filter immediately (no-op); For POST: read Idempotency-Key header; if absent return 400 MISSING_IDEMPOTENCY_KEY; Compute SHA-256 hex of request body; check Redis hash at idem:{partnerId}:{key}; If absent: set status=IN_FLIGHT via hSet NX then EXPIRE 86400; call chain.filter; after response update Redis with status=COMPLETE, body_hash, response_status, response_body; If present status=IN_FLIGHT: return 409 X-Idempotency-Status: in_flight; If present status=COMPLETE: compare body_hash; match -> return cached response; mismatch -> return 422 IDEMPOTENCY_BODY_MISMATCH
**Deliverable:** services/api-gateway/src/main/java/com/gme/pay/gateway/filter/IdempotencyGatewayFilter.java
**Acceptance / logic checks:**
- GET requests: Redis is never queried (zero ReactiveRedisTemplate calls)
- POST missing Idempotency-Key returns 400 with error.code=MISSING_IDEMPOTENCY_KEY
- Second POST same key+same body returns cached response; downstream not called second time
- Second POST same key+different body returns 422 with error.code=IDEMPOTENCY_BODY_MISMATCH
- Concurrent in-flight key returns 409 with header X-Idempotency-Status: in_flight
- Redis key idem:{partnerId}:{key} has TTL of 86400 seconds after creation
**Depends on:** 8.2-T07

### 8.2-T09 — Flyway migration: audit_log table in auth-identity (append-only, JSONB detail)  _(20 min)_
**Context:** Per SEC-09 §2 and non-repudiation objectives, credential generation, credential rotation, and auth failures must be permanently audit-logged. Table audit_log in the auth-identity schema: id BIGINT GENERATED ALWAYS AS IDENTITY, event_type VARCHAR(60) NOT NULL, actor VARCHAR(120) NOT NULL DEFAULT SYSTEM, partner_id BIGINT (nullable for system events), detail JSONB, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(). No updated_at (append-only by design; no UPDATE or DELETE permitted by application DB role). Add GIN index on detail for JSON queries. Also add index on (partner_id, created_at DESC). The audit log must survive partner deletion (no FK on partner_id).
**Steps:** Determine next Flyway version in services/auth-identity/src/main/resources/db/migration/; Create V{n}__audit_log.sql with CREATE TABLE audit_log as specified; id uses GENERATED ALWAYS AS IDENTITY; Add CREATE INDEX idx_audit_log_detail ON audit_log USING GIN (detail); Add CREATE INDEX idx_audit_log_partner ON audit_log (partner_id, created_at DESC) WHERE partner_id IS NOT NULL; Add SQL comment: -- No FK on partner_id intentional: audit log outlives partners. Application DB role: INSERT only, no UPDATE/DELETE.; Run ./gradlew :services:auth-identity:flywayMigrate to verify
**Deliverable:** services/auth-identity/src/main/resources/db/migration/V{n}__audit_log.sql
**Acceptance / logic checks:**
- Migration applies cleanly on fresh schema
- Table has no updated_at column (append-only)
- GIN index on detail column is present (\d audit_log shows two indexes)
- id uses GENERATED ALWAYS AS IDENTITY not SERIAL
- partner_id is nullable (INSERT with partner_id=NULL succeeds for system events)
- No FK constraint on partner_id (verified by \d audit_log showing no FK)
**Depends on:** 8.2-T01

### 8.2-T10 — AuditService and AuditLogRepository: write auth events to audit_log in auth-identity  _(35 min)_
**Context:** The auth-identity @Service AuditService writes audit events to the audit_log table (8.2-T09). Events for WBS 8.2: CREDENTIAL_GENERATED (actor=opsUserId, detail={api_key_prefix: first 8 chars only, partner_id}), CREDENTIAL_ROTATED (same), AUTH_FAILURE (actor=SYSTEM, detail={error_code, api_key_prefix, ip_address}). Writes are synchronous for credential events (within the same transaction as credential save) and fire-and-forget async for auth failures (from the gateway filter via internal HTTP POST). The detail JSONB must never contain hmac_secret, api_secret, or api_secret_hash.
**Steps:** Create AuditLogEntry.java @Entity in services/auth-identity/src/main/java/com/gme/pay/auth/domain/ mapping to audit_log; Create AuditLogRepository.java extending JpaRepository<AuditLogEntry,Long>; Create AuditService.java @Service with void logEvent(String eventType, String actor, Long partnerId, Map<String,Object> detail); sanitize detail to remove any key named hmac_secret/api_secret/api_secret_hash before save; Expose @RestController AuditController.java at POST /internal/v1/audit accepting AuditEventRequest DTO; call auditService.logEvent; mark method @Async for fire-and-forget caller; Add @Transactional on logEvent to ensure audit write is atomic with caller transaction when called synchronously
**Deliverable:** services/auth-identity/src/main/java/com/gme/pay/auth/service/AuditService.java and AuditController.java
**Acceptance / logic checks:**
- CREDENTIAL_GENERATED event is inserted in audit_log after generateCredentials; detail contains api_key_prefix but NOT api_secret or api_secret_hash
- AUTH_FAILURE event inserted via /internal/v1/audit with error_code in detail JSONB
- detail sanitizer removes any key named hmac_secret from the map before persistence (test by passing {hmac_secret: x} and asserting row detail does not contain that key)
- AuditController POST /internal/v1/audit returns 202 Accepted for fire-and-forget pattern
- created_at is populated by DEFAULT NOW() without explicit set from application code
**Depends on:** 8.2-T09

### 8.2-T11 — CredentialGenerationService: generate api_key+api_secret+hmac_secret, bcrypt+Vault store  _(40 min)_
**Context:** Per API-05 §3.1, SEC-09 §2.3 and §9.1: at partner onboarding GME Ops generates: api_key (prefix pk_live_ + 32 random alphanumeric chars = 39 chars total), api_secret (32 SecureRandom bytes base64url-encoded, shown once to operator), hmac_secret (32 SecureRandom bytes base64url-encoded, stored in Vault). api_secret is bcrypt-hashed (cost=12) and stored in partner_credential.api_secret_hash. hmac_secret is written to Vault at secret/data/partners/{partner_id}/hmac_secret. Returned PartnerCredentials DTO contains both plaintexts for one-time display; caller must not log them. On rotation: SELECT FOR UPDATE existing active credential, set is_active=FALSE, then insert new row. Credential operations are audit-logged via AuditService.
**Steps:** Create CredentialGenerationService.java in services/auth-identity/src/main/java/com/gme/pay/auth/service/ with @Service @Transactional; Inject PartnerCredentialRepository, VaultTemplate, BCryptPasswordEncoder(strength=12), AuditService; Implement PartnerCredentials generateCredentials(Long partnerId, String actorUserId) using SecureRandom for api_secret and hmac_secret (32 bytes each, Base64.getUrlEncoder().withoutPadding().encodeToString); Set api_key = pk_live_ + random alphanumeric 32 chars; hash api_secret with bcryptEncoder; write hmac_secret to Vault; save PartnerCredential to DB; On rotation: find existing active credential via findByPartnerIdAndIsActiveTrue, set is_active=FALSE, save, then generate new; Call auditService.logEvent(CREDENTIAL_GENERATED, actorUserId, partnerId, {api_key_prefix: apiKey.substring(0,15)}) after successful save
**Deliverable:** services/auth-identity/src/main/java/com/gme/pay/auth/service/CredentialGenerationService.java
**Acceptance / logic checks:**
- generateCredentials inserts exactly one row with is_active=TRUE into partner_credential
- api_secret_hash in DB is a valid BCrypt hash string starting with $2a$ or $2b$ and cost 12
- Vault path secret/data/partners/{partnerId}/hmac_secret is written with hmac_secret key
- Returned PartnerCredentials.apiKey is 39 chars starting with pk_live_
- On rotation: previous active credential row has is_active=FALSE; only one active row remains
- AUDIT log row CREDENTIAL_GENERATED contains api_key_prefix but no plaintext api_secret or hmac_secret
**Depends on:** 8.2-T03, 8.2-T10

### 8.2-T12 — Flyway migration: idempotency_key_log table for durable idempotency audit trail  _(20 min)_
**Context:** Redis alone is volatile. Per SEC-09 non-repudiation requirements, idempotency decisions for POST requests must also be durable in PostgreSQL. Table idempotency_key_log in auth-identity schema: id BIGINT GENERATED ALWAYS AS IDENTITY PK, partner_id BIGINT NOT NULL, idempotency_key VARCHAR(128) NOT NULL, request_body_hash CHAR(64) NOT NULL (SHA-256 hex), response_status SMALLINT, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), UNIQUE(partner_id, idempotency_key). No FK on partner_id. Write is async (fire-and-forget) after Redis entry is set; never on the hot path. This is a compliance/audit record; response_body is intentionally NOT stored here (too large; Redis holds the cached response).
**Steps:** Determine next Flyway version in services/auth-identity/src/main/resources/db/migration/; Create V{n}__idempotency_key_log.sql with table DDL as specified; Add UNIQUE constraint on (partner_id, idempotency_key); Add index: CREATE INDEX ON idempotency_key_log (partner_id, created_at DESC); Add SQL comment: -- Append-only audit of idempotency decisions. Application role: INSERT only. Response body not stored here.; Run flywayMigrate to confirm no conflicts
**Deliverable:** services/auth-identity/src/main/resources/db/migration/V{n}__idempotency_key_log.sql
**Acceptance / logic checks:**
- Migration applies cleanly
- UNIQUE constraint on (partner_id, idempotency_key) prevents duplicate rows
- request_body_hash is CHAR(64) (SHA-256 hex is exactly 64 chars)
- created_at has DEFAULT NOW() and is NOT NULL
- No response_body column (intentionally omitted; too large for audit table)
- Index on (partner_id, created_at DESC) is visible in \d idempotency_key_log
**Depends on:** 8.2-T01

### 8.2-T13 — IdempotencyLogService: async PostgreSQL write-through for idempotency audit in auth-identity  _(30 min)_
**Context:** After IdempotencyGatewayFilter (8.2-T08) writes to Redis, it also asynchronously persists to idempotency_key_log (8.2-T12) via auth-identity POST /internal/v1/idempotency/log. The write is fire-and-forget (WebClient .subscribe() in the filter) and must never block the gateway response path. If DB write fails (duplicate key = rare race), log WARN and swallow. auth-identity IdempotencyLogService is @Service with @Async method. @Async executor is configured with bounded thread pool maxPoolSize=10.
**Steps:** Create IdempotencyLogRequest DTO {String partnerId, String idempotencyKey, String requestBodyHash, int responseStatus} in lib-api-contracts; Create IdempotencyLogService.java in services/auth-identity with @Service; inject IdempotencyKeyLogRepository (Spring Data JPA for idempotency_key_log); Implement @Async void persistLog(IdempotencyLogRequest req) annotated @Transactional; on DataIntegrityViolationException (duplicate) log WARN and return; Create @RestController IdempotencyLogController.java at POST /internal/v1/idempotency/log; call service; return 202 Accepted; In IdempotencyGatewayFilter (8.2-T08) call the auth-identity endpoint using WebClient after updating Redis; use .subscribe() not .block()
**Deliverable:** services/auth-identity/src/main/java/com/gme/pay/auth/service/IdempotencyLogService.java and IdempotencyLogController.java
**Acceptance / logic checks:**
- persistLog does not propagate DataIntegrityViolationException (swallows duplicate quietly with WARN log)
- Method is @Async and returns void immediately (verifiable by checking return type and annotation)
- DB row inserted with correct partner_id, idempotency_key, request_body_hash values
- @Async executor bean has maxPoolSize=10 configured in application
- WebClient call in filter uses .subscribe() not .block() (code review check - no blocking call on reactive thread)
**Depends on:** 8.2-T08, 8.2-T12

### 8.2-T14 — RateLimitGatewayFilter: per-partner Redis sliding-window rate limiter in api-gateway  _(45 min)_
**Context:** Per API-05 §3.5, requests exceeding per-partner rate limits return 429 with Retry-After header. Default = 100 req/min per partner. Per-partner overrides stored in Redis at config:partner:{partnerId}:rate_limit_per_min. Implementation: GlobalFilter order=-8 (after idempotency filter); Redis counter keyed ratelimit:{partnerId}:{epochMinute} using INCR + EXPIRE 60s. Response headers: X-RateLimit-Limit, X-RateLimit-Remaining, X-RateLimit-Reset (epoch second of next minute boundary). On limit exceeded: 429 error.code=RATE_LIMITED with Retry-After header. X-Partner-ID header (set by HMAC filter) provides partner_id.
**Steps:** Create RateLimitGatewayFilter.java in services/api-gateway/src/main/java/com/gme/pay/gateway/filter/ implementing GlobalFilter with getOrder()=-8; Read X-Partner-ID header; if absent skip filter (HMAC filter already rejected such requests); Compute minute bucket: long epochMinute = Instant.now().getEpochSecond() / 60; Read per-partner limit from Redis config:partner:{partnerId}:rate_limit_per_min; fall back to configurable default (property gme.security.default-rate-limit-per-min=100); Use ReactiveRedisTemplate INCR on ratelimit:{partnerId}:{epochMinute} then EXPIRE 60; if count > limit return 429; Set X-RateLimit-Limit, X-RateLimit-Remaining, X-RateLimit-Reset headers on all non-rejected responses
**Deliverable:** services/api-gateway/src/main/java/com/gme/pay/gateway/filter/RateLimitGatewayFilter.java
**Acceptance / logic checks:**
- 101st request in same minute for same partner returns 429 with error.code=RATE_LIMITED
- Retry-After header value is positive integer between 1 and 60
- X-RateLimit-Remaining decrements from 100 to 0 over 100 requests
- Partner with Redis override config:partner:2:rate_limit_per_min=50 is limited at 50 not 100
- No X-Partner-ID present: filter is a complete no-op (chain.filter called, no Redis calls)
- Redis key ratelimit:{partnerId}:{bucket} has TTL of 60 seconds
**Depends on:** 8.2-T07

### 8.2-T15 — Application config: wire all auth filters, Redis, and auth-identity base-url in api-gateway  _(25 min)_
**Context:** Wire HmacAuthGatewayFilter (order=-10), IdempotencyGatewayFilter (order=-9), and RateLimitGatewayFilter (order=-8) into services/api-gateway. Set application.yml: auth-identity.base-url from ${AUTH_IDENTITY_BASE_URL}, spring.data.redis.host/port from env, gme.security.replay-nonce-ttl-seconds=600, gme.security.idempotency-ttl-seconds=86400, gme.security.default-rate-limit-per-min=100. Add lib-security as a dependency in api-gateway build.gradle. Add @ConditionalOnProperty(name=gme.security.hmac-filter.enabled, matchIfMissing=true) to allow disabling in local dev. Update docker-compose.yml to set env vars for api-gateway.
**Steps:** Open services/api-gateway/src/main/resources/application.yml; add auth-identity.base-url, redis config, and gme.security.* properties as specified; In services/api-gateway/build.gradle add implementation project(:lib:lib-security); Annotate HmacAuthGatewayFilter, IdempotencyGatewayFilter, RateLimitGatewayFilter with @ConditionalOnProperty(name=gme.security.hmac-filter.enabled, matchIfMissing=true); Update docker-compose.yml api-gateway service environment block with AUTH_IDENTITY_BASE_URL, REDIS_HOST, REDIS_PORT; Verify no hardcoded secrets, tokens, or passwords appear in any application*.yml file
**Deliverable:** services/api-gateway/src/main/resources/application.yml (modified), services/api-gateway/build.gradle (modified), docker-compose.yml (modified)
**Acceptance / logic checks:**
- application.yml contains no hardcoded secret values
- auth-identity.base-url uses ${AUTH_IDENTITY_BASE_URL:http://auth-identity:8080} (env var with fallback)
- ./gradlew :services:api-gateway:compileJava succeeds after adding lib-security dependency
- gme.security.replay-nonce-ttl-seconds=600 property present in application.yml
- docker-compose.yml api-gateway environment block contains AUTH_IDENTITY_BASE_URL entry
- Setting gme.security.hmac-filter.enabled=false disables all three filters for local dev
**Depends on:** 8.2-T07, 8.2-T08, 8.2-T14

### 8.2-T16 — docker-compose: Vault dev-mode service with seed data for local HMAC auth development  _(30 min)_
**Context:** Developers need a local Vault in dev-mode with seed partner HMAC secrets to test the auth filter. Add hashicorp/vault:1.15 to docker-compose.yml with VAULT_DEV_ROOT_TOKEN_ID=root. Add a vault-init init container (curlimages/curl) that waits for Vault health then seeds: POST http://vault:8200/v1/secret/data/partners/1/hmac_secret with {data:{hmac_secret:test-partner-1-hmac-secret-32ch}}. Add spring.cloud.vault.uri and spring.cloud.vault.token to services/auth-identity/src/main/resources/application-local.yml. Auth-identity depends_on vault (healthy).
**Steps:** Add vault service to docker-compose.yml: image hashicorp/vault:1.15, VAULT_DEV_ROOT_TOKEN_ID=root, VAULT_DEV_LISTEN_ADDRESS=0.0.0.0:8200, ports 8200:8200, healthcheck on GET /v1/sys/health; Add vault-init service using curlimages/curl with depends_on vault, command that POSTs seed secret to Vault kv-v2 path; Add depends_on: {vault: {condition: service_healthy}} to auth-identity service in docker-compose.yml; Create services/auth-identity/src/main/resources/application-local.yml with spring.cloud.vault.uri=${VAULT_URI:http://localhost:8200} and spring.cloud.vault.token=${VAULT_TOKEN:root}; Verify: docker-compose up vault vault-init completes; curl http://localhost:8200/v1/secret/data/partners/1/hmac_secret -H X-Vault-Token:root returns hmac_secret value
**Deliverable:** docker-compose.yml (modified with vault and vault-init) and services/auth-identity/src/main/resources/application-local.yml
**Acceptance / logic checks:**
- docker-compose up vault vault-init exits with code 0
- Vault health endpoint GET http://localhost:8200/v1/sys/health returns HTTP 200
- After vault-init: curl GET /v1/secret/data/partners/1/hmac_secret returns {data:{hmac_secret:test-partner-1-hmac-secret-32ch}}
- VAULT_DEV_ROOT_TOKEN_ID=root is set only in docker-compose.yml (not in any *.yml config committed to git)
- application-local.yml uses ${VAULT_TOKEN:root} env var reference not hardcoded string root
**Depends on:** 8.2-T03

### 8.2-T17 — Unit tests: HmacSignatureVerifier test vectors and constant-time comparison  _(25 min)_
**Context:** Pure unit tests (no Spring context, no containers) for HmacSignatureVerifier in lib/lib-security (8.2-T04). Must cover: (1) empty-body SHA-256 hash, (2) canonical-string format with newline delimiter, (3) signature computation against a known reference HMAC-SHA256 vector, (4) verifySignature correct returns true, (5) verifySignature wrong secret returns false, (6) verifySignature uses constant-time comparison. Test class: lib/lib-security/src/test/java/com/gme/pay/security/HmacSignatureVerifierTest.java using JUnit 5 @ParameterizedTest.
**Steps:** Create HmacSignatureVerifierTest.java with @Test methods for each check; Vector 1: method=POST, path=/v1/payments?foo=bar, timestamp=2026-06-04T09:31:00.000Z, body={}, secret=test-secret-exactly-32-chars-here; pre-compute expected signature with an external HMAC-SHA256 tool; Vector 2: method=GET, path=/v1/rate-quotes/qref123, body=empty; assert bodyHash equals e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855; Test verifySignature(correct expected, canonical, secret) = true; Test verifySignature(wrong expected, canonical, secret) = false; Add code comment confirming MessageDigest.isEqual is used (timing-safe); grep the source file in test as assertion
**Deliverable:** lib/lib-security/src/test/java/com/gme/pay/security/HmacSignatureVerifierTest.java
**Acceptance / logic checks:**
- All 6 test cases pass in ./gradlew :lib:lib-security:test
- Empty body hash matches e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855 exactly
- computeSignature for vector 1 matches pre-computed reference hex string
- verifySignature(correct) = true, verifySignature(wrong-by-1-char) = false
- Test class has zero Spring/Mockito annotations (pure JUnit 5)
- Total test execution time < 500ms
**Depends on:** 8.2-T04

### 8.2-T18 — Unit tests: TimestampValidator boundary conditions and RedisNonceStore with Testcontainers  _(30 min)_
**Context:** Tests for TimestampValidator (pure unit) and RedisNonceStore (Testcontainers Redis 7) from lib-security (8.2-T05). TimestampValidator tests: boundary at exactly 300s, 301s, future timestamps, ISO-8601 and Unix-epoch formats, malformed input. RedisNonceStore tests: first call true, second call false, TTL assertion, different nonces are independent. Test files: lib/lib-security/src/test/java/com/gme/pay/security/TimestampValidatorTest.java and RedisNonceStoreIT.java.
**Steps:** Create TimestampValidatorTest.java with @ParameterizedTest: now-300s (PASS), now-301s (FAIL), now+301s (FAIL), Unix epoch integer string (PASS), malformed string (expect exception); Create RedisNonceStoreIT.java with @Testcontainers; @Container GenericContainer redis = new GenericContainer(redis:7) withExposedPorts(6379); Configure ReactiveRedisTemplate in test with the container host/port via @DynamicPropertySource; Test checkAndSetNonce: first call Mono<Boolean> true; second call same nonce+partner Mono<Boolean> false; Test TTL: RedisTemplate.getExpire(key) returns value <= 600 seconds after checkAndSetNonce; Test two different nonces for same partner both return true
**Deliverable:** lib/lib-security/src/test/java/com/gme/pay/security/TimestampValidatorTest.java and RedisNonceStoreIT.java
**Acceptance / logic checks:**
- isWithinWindow(now-300s, now, 300) returns true (inclusive boundary)
- isWithinWindow(now-301s, now, 300) returns false
- Unix epoch integer string parses without exception
- checkAndSetNonce returns false on second call with same partner+nonce
- Redis key TTL is between 590 and 600 seconds (allows for test execution time)
- All tests pass in ./gradlew :lib:lib-security:test
**Depends on:** 8.2-T05

### 8.2-T19 — Unit tests: CredentialGenerationService with Mockito-mocked Vault and Repository  _(30 min)_
**Context:** Unit tests for CredentialGenerationService (8.2-T11) using Mockito to mock VaultTemplate and PartnerCredentialRepository. No containers needed. Covers: credential generation (key format, bcrypt hash format, Vault write), rotation (old credential deactivated), Vault write path correctness, returned DTO fields, and that no plaintext secret appears in AuditService call. Test file: services/auth-identity/src/test/java/com/gme/pay/auth/service/CredentialGenerationServiceTest.java.
**Steps:** Create CredentialGenerationServiceTest.java with @ExtendWith(MockitoExtension.class); Mock PartnerCredentialRepository, VaultTemplate, AuditService, BCryptPasswordEncoder; Test generateCredentials: verify repository.save called with entity where api_secret_hash matches BCrypt pattern ^\$2[ab]\$12\$.*; Verify vaultTemplate.write called with path containing secret/data/partners/{partnerId}/hmac_secret; Test rotation: when findByPartnerIdAndIsActiveTrue returns an existing credential, verify that credential is saved with is_active=FALSE before new insert; Assert returned PartnerCredentials.apiKey is 39 chars starting with pk_live_; Assert auditService.logEvent called with event type CREDENTIAL_GENERATED and detail map NOT containing key hmac_secret
**Deliverable:** services/auth-identity/src/test/java/com/gme/pay/auth/service/CredentialGenerationServiceTest.java
**Acceptance / logic checks:**
- Mock verify: repository.save called with api_secret_hash matching BCrypt regex
- Mock verify: vaultTemplate.write path equals secret/data/partners/{partnerId}/hmac_secret
- Rotation test: first save called with is_active=FALSE on existing; second save with is_active=TRUE on new
- apiKey is exactly 39 characters starting with pk_live_
- auditService.logEvent detail map does not contain key hmac_secret (assert via ArgumentCaptor)
- All tests pass in ./gradlew :services:auth-identity:test
**Depends on:** 8.2-T11

### 8.2-T20 — Unit tests: IdempotencyGatewayFilter all 6 scenarios with MockServerWebExchange  _(40 min)_
**Context:** Unit tests for IdempotencyGatewayFilter (8.2-T08) using MockServerWebExchange and Mockito-mocked ReactiveRedisTemplate. No containers needed. Must cover all 6 spec-defined scenarios from API-05 §7.1: new key (pass through and store), duplicate same body (return cached), duplicate different body (422), in-flight (409), GET request (skip), missing header (400). Test file: services/api-gateway/src/test/java/com/gme/pay/gateway/filter/IdempotencyGatewayFilterTest.java.
**Steps:** Create IdempotencyGatewayFilterTest.java with @ExtendWith(MockitoExtension.class); Mock ReactiveRedisTemplate<String,String> and GatewayFilterChain; Test new key: mock hGet returns empty -> verify chain.filter called; verify hSet IN_FLIGHT then COMPLETE; Test duplicate same body: mock hGet returns COMPLETE + matching body_hash -> chain.filter NOT called; response matches stored; Test different body: mock hGet returns COMPLETE + different body_hash -> response is 422 IDEMPOTENCY_BODY_MISMATCH; Test in-flight: mock hGet returns IN_FLIGHT -> response is 409 X-Idempotency-Status: in_flight; Test GET: ReactiveRedisTemplate never invoked; chain.filter always called; Test missing header on POST: response is 400 MISSING_IDEMPOTENCY_KEY
**Deliverable:** services/api-gateway/src/test/java/com/gme/pay/gateway/filter/IdempotencyGatewayFilterTest.java
**Acceptance / logic checks:**
- Test duplicate same body: Mockito verify(chain, never()).filter(...) passes
- Test 422: response body error.code equals IDEMPOTENCY_BODY_MISMATCH
- Test 409: response header X-Idempotency-Status equals in_flight
- Test GET: zero invocations on ReactiveRedisTemplate (Mockito verifyNoInteractions)
- Test 400: response status 400 and error.code equals MISSING_IDEMPOTENCY_KEY
- All 8 test methods pass in ./gradlew :services:api-gateway:test
**Depends on:** 8.2-T08

### 8.2-T21 — Integration test: HmacAuthGatewayFilter + IdempotencyGatewayFilter with Testcontainers and WireMock  _(55 min)_
**Context:** Spring Boot integration test for both gateway filters together using Testcontainers Redis 7 and WireMock for the auth-identity internal endpoint and a stub downstream service. Boots the full api-gateway application. Tests live in services/api-gateway/src/test/. Test class: HmacAuthGatewayFilterIT.java. Constructs correctly signed requests using HmacSignatureVerifier with test hmac_secret and asserts all auth scenarios end-to-end including idempotency cache replay through both filters.
**Steps:** Create HmacAuthGatewayFilterIT.java with @SpringBootTest(webEnvironment=RANDOM_PORT) and @Testcontainers; Start Redis 7 Testcontainer and two WireMock servers (auth-identity stub, downstream stub); configure via @DynamicPropertySource; Stub WireMock auth-identity POST /internal/v1/credentials/resolve -> 200 {partnerId:1, hmacSecret:test-partner-1-hmac-secret-32ch, isActive:true}; Test valid signed POST passes to downstream and X-Partner-ID=1 is forwarded; Test stale timestamp (now-301s) returns 401 TIMESTAMP_DRIFT; Test replayed nonce (same X-Nonce twice within 600s) returns 401 REPLAY_DETECTED; Test tampered body returns 401 INVALID_SIGNATURE; Test duplicate Idempotency-Key returns cached 200 without second WireMock downstream call
**Deliverable:** services/api-gateway/src/test/java/com/gme/pay/gateway/filter/HmacAuthGatewayFilterIT.java
**Acceptance / logic checks:**
- Valid signed request: upstream receives X-Partner-ID=1 header and WireMock downstream called exactly once
- Stale timestamp: HTTP 401 response error.code=TIMESTAMP_DRIFT
- Replayed nonce: HTTP 401 response error.code=REPLAY_DETECTED
- Tampered body: HTTP 401 response error.code=INVALID_SIGNATURE
- Duplicate idempotency key: WireMock downstream stub called exactly once for two identical POST requests
- All 7 test scenarios pass in ./gradlew :services:api-gateway:integrationTest
**Depends on:** 8.2-T08, 8.2-T15

### 8.2-T22 — OpenAPI spec: declare HMAC auth headers and Idempotency-Key in partner-api.yaml  _(25 min)_
**Context:** Per the STACK.md testing approach, contract tests run against openapi/partner-api.yaml. The HMAC headers (X-API-Key, X-Timestamp, X-Signature, X-Nonce) and Idempotency-Key must be declared as required headers so contract-test tooling can generate valid requests and validate auth requirements. Add a securitySchemes entry, reusable header parameters, and a metadata extension documenting the replay-protection windows.
**Steps:** Open openapi/partner-api.yaml and add securitySchemes.HmacAuth: {type: apiKey, in: header, name: X-API-Key}; Add components.parameters: XTimestamp, XSignature, XNonce, IdempotencyKey as header parameters with schema type: string and required: true; Apply security: [{HmacAuth: []}] globally under the top-level security field; Add X-Timestamp, X-Signature, X-Nonce as parameters to every path operation; Add Idempotency-Key parameter reference to all POST operations; Add info extension x-gme-auth: {hmac-canonical-format: METHOD+newline+PATH_WITH_QUERY+newline+X-Timestamp+newline+SHA256HEX(body), timestamp-window-seconds: 300, nonce-ttl-seconds: 600, idempotency-ttl-seconds: 86400}
**Deliverable:** openapi/partner-api.yaml (modified)
**Acceptance / logic checks:**
- openapi-generator or swagger-parser validates the file without errors (./gradlew :lib:lib-api-contracts:generateApiDocs)
- All POST paths include $ref to IdempotencyKey header parameter
- X-Timestamp, X-Signature, X-Nonce are required: true in their definitions
- info.x-gme-auth.timestamp-window-seconds equals 300
- info.x-gme-auth.nonce-ttl-seconds equals 600
- No existing endpoint operation is broken by the security additions
**Depends on:** 8.2-T07


## WBS 13.2 — RBAC model & permission matrix
### 13.2-T01 — Create DB migration: hub_role table with Admin Portal role codes  _(25 min)_
**Context:** GMEPay+ Admin Portal uses four roles: SUPER_ADMIN (full access incl. user mgmt), OPS_OPERATOR (configure schemes/partners/rules/FX, process refunds, monitor txns), FINANCE_ANALYST (view settlement/revenue/txns/prefunding; no config writes), ADMIN_VIEWER (read-only txns/balances/reports; cannot view credentials). These are stored in hub_role (id UUID PK, role_code VARCHAR(30) UNIQUE NOT NULL, description TEXT, permissions JSONB NOT NULL, created_at TIMESTAMPTZ DEFAULT now(), updated_at TIMESTAMPTZ DEFAULT now()). The permissions JSONB field holds an array of permission strings. Source: SEC-09 §3.4, PRD-07 §12.2.
**Steps:** Create a Flyway/Liquibase migration file V13_2_001__create_hub_role.sql; Define hub_role table with columns: id UUID DEFAULT gen_random_uuid() PK, role_code VARCHAR(30) UNIQUE NOT NULL, description TEXT, permissions JSONB NOT NULL DEFAULT '[]', created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(); Add a CHECK constraint: role_code IN ('SUPER_ADMIN','OPS_OPERATOR','FINANCE_ANALYST','ADMIN_VIEWER'); Add index: CREATE UNIQUE INDEX hub_role_code_uidx ON hub_role(role_code); Insert the four seed rows with empty permissions JSONB arrays (permissions populated in T02)
**Deliverable:** Migration file V13_2_001__create_hub_role.sql that creates and seeds the hub_role table with 4 rows
**Acceptance / logic checks:**
- Table hub_role exists after migration with columns id, role_code, description, permissions, created_at, updated_at
- UNIQUE constraint on role_code: inserting a duplicate role_code raises a unique violation error
- CHECK constraint rejects any role_code not in the allowed set (e.g. INSERT with role_code='UNKNOWN' fails)
- Four seed rows present: SUPER_ADMIN, OPS_OPERATOR, FINANCE_ANALYST, ADMIN_VIEWER
- permissions column defaults to '[]' JSON array; column accepts valid JSONB

### 13.2-T02 — Create DB migration: hub_user table with FK to hub_role (Admin Portal)  _(25 min)_
**Context:** Admin Portal users are stored in hub_user (id UUID PK, email VARCHAR(255) UNIQUE NOT NULL, name VARCHAR(200) NOT NULL, role_id UUID NOT NULL FK -> hub_role.id, is_active BOOLEAN NOT NULL DEFAULT true, last_login_at TIMESTAMPTZ, created_at TIMESTAMPTZ DEFAULT now(), updated_at TIMESTAMPTZ DEFAULT now(), created_by UUID FK -> hub_user.id NULLABLE). Every admin portal user has exactly one role. Multiple roles per user are not supported in Phase 1 (PRD-07 §12.2 assigns one role per user). The created_by column tracks which SUPER_ADMIN created the account. Source: DAT-03 §9.2, PRD-07 §12.4.
**Steps:** Create migration V13_2_002__create_hub_user.sql; Define hub_user table with all columns as specified in context; Add FK constraint: hub_user.role_id REFERENCES hub_role(id) ON DELETE RESTRICT; Add FK constraint: hub_user.created_by REFERENCES hub_user(id) ON DELETE SET NULL (nullable); Add index on email for login lookup: CREATE INDEX hub_user_email_idx ON hub_user(email); Add index on role_id for role-based queries: CREATE INDEX hub_user_role_idx ON hub_user(role_id)
**Deliverable:** Migration file V13_2_002__create_hub_user.sql that creates the hub_user table
**Acceptance / logic checks:**
- hub_user table exists with all specified columns and correct types
- FK hub_user.role_id -> hub_role.id enforced: inserting a user with a non-existent role_id raises FK violation
- FK ON DELETE RESTRICT: deleting a hub_role row that has associated users is rejected
- Unique constraint on email: inserting two users with same email raises unique violation
- Index hub_user_email_idx present (verify via pg_indexes)
**Depends on:** 13.2-T01

### 13.2-T03 — Define Admin Portal permission constants and RBAC permission matrix as code  _(35 min)_
**Context:** The Admin Portal permission matrix (SEC-09 §3.4.3, PRD-07 §12.3) maps 4 roles to 24 capability flags. Capabilities include: VIEW_DASHBOARD, CREATE_EDIT_SCHEME, ACTIVATE_SUSPEND_SCHEME, VIEW_SCHEME_CONFIG, CREATE_EDIT_PARTNER, VIEW_API_CREDENTIALS, GENERATE_REVOKE_API_CREDENTIALS, CREATE_EDIT_RULE, UPDATE_FX_RATES, VIEW_FX_RATE_HISTORY, SEARCH_VIEW_TRANSACTIONS, VIEW_LOCKED_RATE_POOL_VALUES, VIEW_PREFUNDING_BALANCE, RECORD_TOPUP, CREATE_MANUAL_ADJUSTMENT, INITIATE_REFUND, VIEW_REFUND_STATUS, VIEW_SETTLEMENT_BATCHES, EXPORT_REVENUE_REPORT, MANAGE_USERS_AND_ROLES, VIEW_AUDIT_LOG, EXPORT_AUDIT_LOG, PARTNER_REGISTRATION_ACTIVATION, RULE_MARGIN_UPDATE. Create an enum/constants class and a static permission map. Source: SEC-09 §3.4.3, PRD-07 §12.3.
**Steps:** Create AdminPermission enum (or constants class) listing all 24 permission codes as string constants; Create AdminRolePermissions class with a static final Map<String, Set<String>> mapping each role_code to its permitted set; Populate SUPER_ADMIN: all 24 permissions; Populate OPS_OPERATOR: VIEW_DASHBOARD, CREATE_EDIT_SCHEME, ACTIVATE_SUSPEND_SCHEME, VIEW_SCHEME_CONFIG, CREATE_EDIT_PARTNER, VIEW_API_CREDENTIALS, GENERATE_REVOKE_API_CREDENTIALS, CREATE_EDIT_RULE, UPDATE_FX_RATES, VIEW_FX_RATE_HISTORY, SEARCH_VIEW_TRANSACTIONS, VIEW_LOCKED_RATE_POOL_VALUES, VIEW_PREFUNDING_BALANCE, RECORD_TOPUP, INITIATE_REFUND, VIEW_REFUND_STATUS, VIEW_SETTLEMENT_BATCHES, EXPORT_REVENUE_REPORT, VIEW_AUDIT_LOG; Populate FINANCE_ANALYST: VIEW_DASHBOARD, VIEW_SCHEME_CONFIG, VIEW_FX_RATE_HISTORY, SEARCH_VIEW_TRANSACTIONS, VIEW_LOCKED_RATE_POOL_VALUES, VIEW_PREFUNDING_BALANCE, CREATE_MANUAL_ADJUSTMENT, VIEW_REFUND_STATUS, VIEW_SETTLEMENT_BATCHES, EXPORT_REVENUE_REPORT; Populate ADMIN_VIEWER: VIEW_DASHBOARD, VIEW_FX_RATE_HISTORY, SEARCH_VIEW_TRANSACTIONS, VIEW_REFUND_STATUS
**Deliverable:** AdminPermission enum/constants and AdminRolePermissions class with static role-to-permissions map
**Acceptance / logic checks:**
- SUPER_ADMIN set contains all 24 defined permission constants
- OPS_OPERATOR does NOT contain MANAGE_USERS_AND_ROLES, EXPORT_AUDIT_LOG, CREATE_MANUAL_ADJUSTMENT
- FINANCE_ANALYST does NOT contain CREATE_EDIT_SCHEME, CREATE_EDIT_PARTNER, CREATE_EDIT_RULE, UPDATE_FX_RATES, GENERATE_REVOKE_API_CREDENTIALS, INITIATE_REFUND, MANAGE_USERS_AND_ROLES, VIEW_AUDIT_LOG, EXPORT_AUDIT_LOG
- ADMIN_VIEWER does NOT contain VIEW_LOCKED_RATE_POOL_VALUES, VIEW_PREFUNDING_BALANCE, VIEW_API_CREDENTIALS, any write permission
- ADMIN_VIEWER contains VIEW_DASHBOARD, SEARCH_VIEW_TRANSACTIONS, VIEW_FX_RATE_HISTORY, VIEW_REFUND_STATUS
**Depends on:** 13.2-T01

### 13.2-T04 — Seed hub_role.permissions JSONB with canonical permission arrays  _(30 min)_
**Context:** After the permission matrix is defined (T03), a data migration must persist the permission arrays into the hub_role table rows seeded in T01. Each role row's permissions column must contain a JSONB array of the permission strings from AdminRolePermissions. This ensures the DB is authoritative and the code map can be generated/validated from the DB. Source: PRD-07 §12.3, DAT-03 §9.2.
**Steps:** Create migration V13_2_003__seed_hub_role_permissions.sql; UPDATE hub_role SET permissions = '["VIEW_DASHBOARD","CREATE_EDIT_SCHEME",...all 24...]'::jsonb WHERE role_code = 'SUPER_ADMIN'; UPDATE hub_role SET permissions = '["VIEW_DASHBOARD","CREATE_EDIT_SCHEME",...]'::jsonb WHERE role_code = 'OPS_OPERATOR' (19 permissions per T03); UPDATE hub_role SET permissions = '["VIEW_DASHBOARD",...]'::jsonb WHERE role_code = 'FINANCE_ANALYST' (10 permissions per T03); UPDATE hub_role SET permissions = '["VIEW_DASHBOARD","VIEW_FX_RATE_HISTORY","SEARCH_VIEW_TRANSACTIONS","VIEW_REFUND_STATUS"]'::jsonb WHERE role_code = 'ADMIN_VIEWER'
**Deliverable:** Migration V13_2_003__seed_hub_role_permissions.sql that populates permissions JSONB on all 4 hub_role rows
**Acceptance / logic checks:**
- After migration, SELECT jsonb_array_length(permissions) FROM hub_role WHERE role_code='SUPER_ADMIN' returns 24
- SELECT jsonb_array_length(permissions) FROM hub_role WHERE role_code='ADMIN_VIEWER' returns 4
- 'MANAGE_USERS_AND_ROLES' appears in SUPER_ADMIN permissions array; NOT in OPS_OPERATOR, FINANCE_ANALYST, ADMIN_VIEWER
- 'INITIATE_REFUND' appears in SUPER_ADMIN and OPS_OPERATOR only
- 'CREATE_MANUAL_ADJUSTMENT' appears in SUPER_ADMIN and FINANCE_ANALYST only
**Depends on:** 13.2-T01, 13.2-T03

### 13.2-T05 — Create DB migration: partner_portal_user and partner_portal_role tables  _(30 min)_
**Context:** The Partner Portal uses two roles: PARTNER_ADMIN (full read access + can invite/deactivate other portal users within the same partner) and PARTNER_VIEWER (all read access; cannot manage users). Users belong to exactly one partner. Table partner_portal_role: id UUID PK, role_code VARCHAR(30) UNIQUE CHECK IN ('PARTNER_ADMIN','PARTNER_VIEWER'), description TEXT, permissions JSONB NOT NULL DEFAULT '[]', created_at TIMESTAMPTZ. Table partner_portal_user: id UUID PK, partner_id UUID NOT NULL FK->partner.id, email VARCHAR(255) NOT NULL, name VARCHAR(200), role_id UUID NOT NULL FK->partner_portal_role.id, is_active BOOLEAN DEFAULT true, last_login_at TIMESTAMPTZ, totp_secret_enc TEXT (AES-256 encrypted TOTP seed), created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ, created_by UUID FK->partner_portal_user.id NULLABLE. UNIQUE(partner_id, email). Source: PRD-08 §2.3, SEC-09 §3.2.
**Steps:** Create migration V13_2_004__create_partner_portal_role_user.sql; Create partner_portal_role table with CHECK constraint on role_code; Insert seed rows for PARTNER_ADMIN and PARTNER_VIEWER; Create partner_portal_user table with all columns including totp_secret_enc; Add UNIQUE constraint on (partner_id, email): one email per partner, but the same email can be used at different partners; Add FK partner_portal_user.partner_id -> partner.id ON DELETE RESTRICT; Add index on partner_id for all-users-per-partner queries
**Deliverable:** Migration V13_2_004__create_partner_portal_role_user.sql creating both tables with seed role rows
**Acceptance / logic checks:**
- UNIQUE(partner_id, email) allows same email address across different partners but rejects duplicate within same partner
- FK partner_portal_user.partner_id enforced: inserting user with non-existent partner_id fails
- Two seed rows in partner_portal_role: PARTNER_ADMIN and PARTNER_VIEWER
- CHECK constraint rejects role_code='PARTNER_SUPER' or any unlisted value
- totp_secret_enc column is nullable (set at TOTP enrolment, not at account creation)
**Depends on:** 13.2-T01

### 13.2-T06 — Seed partner_portal_role.permissions JSONB for PARTNER_ADMIN and PARTNER_VIEWER  _(20 min)_
**Context:** Partner Portal permission matrix (PRD-08 §2.3, SEC-09 §3.4.2 and §3.4.3): PARTNER_ADMIN permissions: VIEW_OWN_PARTNER_CONFIG, VIEW_OWN_TRANSACTIONS, VIEW_OWN_PREFUNDING_BALANCE, VIEW_OWN_SETTLEMENT_STATEMENTS, VIEW_OWN_API_CREDENTIALS, MANAGE_PORTAL_USERS (invite/deactivate users within own partner). PARTNER_VIEWER permissions: VIEW_OWN_PARTNER_CONFIG, VIEW_OWN_TRANSACTIONS, VIEW_OWN_PREFUNDING_BALANCE, VIEW_OWN_SETTLEMENT_STATEMENTS, VIEW_OWN_API_CREDENTIALS. Neither role can modify any configuration; all config writes are Admin System only in Phase 1. Source: PRD-08 §2.3, SEC-09 §3.4.3.
**Steps:** Create migration V13_2_005__seed_partner_portal_role_permissions.sql; UPDATE partner_portal_role SET permissions = '["VIEW_OWN_PARTNER_CONFIG","VIEW_OWN_TRANSACTIONS","VIEW_OWN_PREFUNDING_BALANCE","VIEW_OWN_SETTLEMENT_STATEMENTS","VIEW_OWN_API_CREDENTIALS","MANAGE_PORTAL_USERS"]'::jsonb WHERE role_code='PARTNER_ADMIN'; UPDATE partner_portal_role SET permissions = '["VIEW_OWN_PARTNER_CONFIG","VIEW_OWN_TRANSACTIONS","VIEW_OWN_PREFUNDING_BALANCE","VIEW_OWN_SETTLEMENT_STATEMENTS","VIEW_OWN_API_CREDENTIALS"]'::jsonb WHERE role_code='PARTNER_VIEWER'
**Deliverable:** Migration V13_2_005__seed_partner_portal_role_permissions.sql with correct permissions for both partner portal roles
**Acceptance / logic checks:**
- PARTNER_ADMIN has 6 permissions; PARTNER_VIEWER has 5
- MANAGE_PORTAL_USERS present in PARTNER_ADMIN only
- Neither role contains any write-config permission (no CREATE_EDIT_PARTNER, no UPDATE_FX_RATES, etc.)
- Both roles contain VIEW_OWN_API_CREDENTIALS (partners can view but not regenerate credentials in Phase 1)
- SELECT jsonb_array_length(permissions) FROM partner_portal_role WHERE role_code='PARTNER_VIEWER' returns 5
**Depends on:** 13.2-T05

### 13.2-T07 — Implement AdminPermissionEvaluator service: check if a hub_user has a given permission  _(40 min)_
**Context:** The enforcement layer for Admin Portal RBAC. Given an authenticated hub_user (with their role_code loaded from the DB), the service checks whether their role's permissions JSONB array contains the requested permission string. The permissions for a role should be cached in memory at startup (or per request from the DB). Method signature: boolean hasPermission(UUID userId, String permission). Internally: load hub_user -> lookup hub_role -> check permissions JSONB array contains permission. Returns false (never throws) if user not found, inactive, or permission not present. Source: SEC-09 §3.4, PRD-07 §12.3.
**Steps:** Create AdminPermissionEvaluator class (service/component); Implement loadUserRole(UUID userId): queries hub_user JOIN hub_role, returns Optional<RolePrincipal> containing role_code and permissions set; returns empty if user not found or is_active=false; Implement hasPermission(UUID userId, String permission): calls loadUserRole, checks permissions set contains permission, returns false on empty/absent; Add in-memory cache (Caffeine or Spring Cache) on loadUserRole with TTL 5 minutes to avoid per-request DB hits; Expose a method getPermissions(UUID userId) returning Set<String> for token/session enrichment
**Deliverable:** AdminPermissionEvaluator service class with hasPermission and getPermissions methods
**Acceptance / logic checks:**
- hasPermission returns true when user is SUPER_ADMIN and permission is MANAGE_USERS_AND_ROLES
- hasPermission returns false when user is OPS_OPERATOR and permission is MANAGE_USERS_AND_ROLES
- hasPermission returns false when userId does not exist in DB
- hasPermission returns false when hub_user.is_active = false even if role has the permission
- getPermissions for ADMIN_VIEWER returns exactly {VIEW_DASHBOARD, VIEW_FX_RATE_HISTORY, SEARCH_VIEW_TRANSACTIONS, VIEW_REFUND_STATUS}
**Depends on:** 13.2-T03, 13.2-T04

### 13.2-T08 — Implement PartnerPortalPermissionEvaluator service for partner portal users  _(35 min)_
**Context:** Partner portal enforcement. Given an authenticated partner_portal_user, checks whether their role permits a given action AND enforces partner-scoped data isolation: the authenticated partner_id from session must match the requested resource's partner_id. Method: boolean hasPermission(UUID portalUserId, String permission). Also: boolean isOwnPartner(UUID portalUserId, UUID resourcePartnerId) — always enforced at the repository/service layer, never trusted from request body (PRD-08 §2.2, SEC-09 §3.5). Returns false if user not found or inactive.
**Steps:** Create PartnerPortalPermissionEvaluator class; Implement loadPortalUserRole(UUID portalUserId): query partner_portal_user JOIN partner_portal_role; cache per-user with TTL 5 minutes; return Optional<PortalPrincipal> containing partner_id, role_code, permissions; Implement hasPermission(UUID portalUserId, String permission): calls loadPortalUserRole; checks permissions set; Implement isOwnPartner(UUID portalUserId, UUID resourcePartnerId): loads PortalPrincipal; returns principal.partnerId.equals(resourcePartnerId); Add helper requireOwnPartner(UUID portalUserId, UUID resourcePartnerId) that throws AccessDeniedException if isOwnPartner returns false
**Deliverable:** PartnerPortalPermissionEvaluator service class with hasPermission, isOwnPartner, requireOwnPartner methods
**Acceptance / logic checks:**
- hasPermission returns false for PARTNER_VIEWER when permission is MANAGE_PORTAL_USERS
- hasPermission returns true for PARTNER_ADMIN when permission is MANAGE_PORTAL_USERS
- isOwnPartner returns true when portalUserId belongs to partner X and resourcePartnerId is also X
- isOwnPartner returns false (not throw) when resourcePartnerId belongs to different partner Y
- requireOwnPartner throws AccessDeniedException when partner mismatch; does not reveal that partner Y exists
**Depends on:** 13.2-T05, 13.2-T06

### 13.2-T09 — Implement @RequiresAdminPermission AOP annotation and interceptor  _(40 min)_
**Context:** To enforce Admin Portal RBAC declaratively on Spring MVC controller methods, create a custom annotation @RequiresAdminPermission(value = AdminPermission.MANAGE_USERS_AND_ROLES) and an AOP aspect that intercepts annotated methods, extracts the authenticated hub_user ID from the SecurityContext, calls AdminPermissionEvaluator.hasPermission, and throws AccessDeniedException (HTTP 403) if false. The aspect must run before controller method body executes. Source: SEC-09 §3.4, PRD-07 §12.3.
**Steps:** Create @RequiresAdminPermission annotation (Target=METHOD, Retention=RUNTIME) with String value() attribute; Create AdminRbacAspect (@Aspect @Component) with @Around on methods annotated with @RequiresAdminPermission; In the Around advice: extract userId from SecurityContextHolder (expect Principal type HubUserPrincipal with getId()); Call AdminPermissionEvaluator.hasPermission(userId, annotation.value()); If false: throw AccessDeniedException('Insufficient permissions: ' + annotation.value()) — Spring Security maps this to HTTP 403; If true: proceed with joinpoint.proceed()
**Deliverable:** @RequiresAdminPermission annotation and AdminRbacAspect aspect class
**Acceptance / logic checks:**
- Controller method annotated @RequiresAdminPermission('MANAGE_USERS_AND_ROLES') called by OPS_OPERATOR user returns HTTP 403
- Same method called by SUPER_ADMIN user proceeds and returns HTTP 200
- AccessDeniedException message contains the permission name for debuggability
- Aspect fires BEFORE controller body: if permission denied, no DB write or business logic executes
- Unauthenticated request (no Principal) throws/returns HTTP 401 rather than 403
**Depends on:** 13.2-T07

### 13.2-T10 — Implement @RequiresPortalPermission AOP annotation and interceptor  _(40 min)_
**Context:** Partner Portal equivalent of T09. @RequiresPortalPermission(value = PortalPermission.MANAGE_PORTAL_USERS) applied to partner portal controller methods. The aspect extracts authenticated partner_portal_user ID from SecurityContext, calls PartnerPortalPermissionEvaluator.hasPermission, and throws AccessDeniedException on failure. Additionally, any method annotated @EnforcePartnerScope triggers requireOwnPartner check against a partnerId path variable or resolved entity. Source: PRD-08 §2.2, SEC-09 §3.5.
**Steps:** Create @RequiresPortalPermission annotation (Target=METHOD, Retention=RUNTIME) with String value(); Create @EnforcePartnerScope annotation (Target=METHOD) with String partnerIdParam() defaulting to 'partnerId'; Create PartnerPortalRbacAspect with @Around advice for @RequiresPortalPermission and @EnforcePartnerScope; For @RequiresPortalPermission: extract portalUserId from SecurityContext; call PartnerPortalPermissionEvaluator.hasPermission; throw AccessDeniedException on false; For @EnforcePartnerScope: extract partnerId from JoinPoint args by parameter name; call requireOwnPartner; throw AccessDeniedException on mismatch
**Deliverable:** @RequiresPortalPermission and @EnforcePartnerScope annotations plus PartnerPortalRbacAspect
**Acceptance / logic checks:**
- PARTNER_VIEWER calling endpoint annotated @RequiresPortalPermission('MANAGE_PORTAL_USERS') returns 403
- PARTNER_ADMIN calling same endpoint returns 200
- @EnforcePartnerScope on a transaction-list endpoint prevents Partner A user from accessing Partner B's transactions (receives 403, not the data)
- @EnforcePartnerScope does not reveal Partner B's existence (403, not 404) to prevent enumeration
- Unauthenticated portal request returns 401
**Depends on:** 13.2-T08

### 13.2-T11 — Annotate Admin Portal controllers with @RequiresAdminPermission  _(45 min)_
**Context:** Apply the @RequiresAdminPermission annotation to all Admin Portal controller endpoints that require role gating. Per the permission matrix (PRD-07 §12.3, SEC-09 §3.4.3): scheme create/edit/activate requires CREATE_EDIT_SCHEME or ACTIVATE_SUSPEND_SCHEME; partner create/edit requires CREATE_EDIT_PARTNER; rule create/edit requires CREATE_EDIT_RULE; FX update requires UPDATE_FX_RATES; user management requires MANAGE_USERS_AND_ROLES; audit log export requires EXPORT_AUDIT_LOG; audit log view requires VIEW_AUDIT_LOG; initiate refund requires INITIATE_REFUND; record top-up requires RECORD_TOPUP; manual adjustment requires CREATE_MANUAL_ADJUSTMENT.
**Steps:** Open each Admin Portal controller class (SchemeController, PartnerController, RuleController, FxRateController, UserManagementController, AuditLogController, RefundController, PrefundingController); Annotate each write/mutating endpoint with the appropriate @RequiresAdminPermission(AdminPermission.XXX); Annotate read endpoints that are role-restricted (VIEW_LOCKED_RATE_POOL_VALUES, VIEW_API_CREDENTIALS, VIEW_PREFUNDING_BALANCE, VIEW_SCHEME_CONFIG for Ops+); Leave VIEW_DASHBOARD and SEARCH_VIEW_TRANSACTIONS without restriction beyond authentication (all roles have these); Ensure no admin-only endpoint is reachable through the Partner API gateway (route-level separation confirmed in routing config)
**Deliverable:** All Admin Portal controllers with correct @RequiresAdminPermission annotations on every gated endpoint
**Acceptance / logic checks:**
- POST /admin/v1/schemes returns 403 when called with FINANCE_ANALYST JWT
- GET /admin/v1/audit-log/export returns 403 for OPS_OPERATOR JWT
- POST /admin/v1/prefunding/adjustment returns 403 for OPS_OPERATOR JWT (FINANCE_ANALYST only)
- GET /admin/v1/transactions/{id}/pool-values returns 403 for ADMIN_VIEWER JWT
- DELETE or deactivate user endpoint returns 403 for any non-SUPER_ADMIN role
**Depends on:** 13.2-T09

### 13.2-T12 — Annotate Partner Portal controllers with @RequiresPortalPermission and @EnforcePartnerScope  _(40 min)_
**Context:** Apply portal permission annotations to Partner Portal controllers. All endpoints require at minimum a valid portal session (PARTNER_VIEWER or PARTNER_ADMIN). MANAGE_PORTAL_USERS endpoints (invite user, deactivate user) require @RequiresPortalPermission('MANAGE_PORTAL_USERS'). Every endpoint returning partner-scoped data must have @EnforcePartnerScope. The partner_id used for all DB queries must come from the authenticated session JWT claim, never from the request body or URL parameter directly (PRD-08 §2.2).
**Steps:** Open PartnerTransactionController, PartnerBalanceController, PartnerStatementController, PartnerUserManagementController, PartnerCredentialsController; Add @EnforcePartnerScope to all data-returning endpoints in PartnerTransactionController and PartnerBalanceController; Add @RequiresPortalPermission('MANAGE_PORTAL_USERS') to invite-user and deactivate-user endpoints in PartnerUserManagementController; Verify that partner_id in all service-layer calls derives from authenticated principal, not from request body; Add integration test stubs (//TODO: covered in T23) at each annotated method
**Deliverable:** Partner Portal controllers with @RequiresPortalPermission and @EnforcePartnerScope annotations applied
**Acceptance / logic checks:**
- PARTNER_VIEWER calling POST /portal/v1/users returns 403
- PARTNER_ADMIN for Partner A calling GET /portal/v1/transactions with partner_id=Partner_B_UUID returns 403
- PARTNER_ADMIN calling GET /portal/v1/transactions correctly returns only Partner A transactions
- API credentials view endpoint accessible to both PARTNER_ADMIN and PARTNER_VIEWER
- Balance endpoint hidden (403) if attempted by any user with partner_id mismatch
**Depends on:** 13.2-T10

### 13.2-T13 — Implement Admin Portal user management API: create, deactivate, reactivate, assign role  _(50 min)_
**Context:** SUPER_ADMIN can create new portal user accounts, assign one role, deactivate/reactivate accounts, and force password reset (PRD-07 §12.4). Endpoint: POST /admin/v1/users (create), PATCH /admin/v1/users/{userId}/status (deactivate/reactivate), PATCH /admin/v1/users/{userId}/role (assign role), POST /admin/v1/users/{userId}/force-password-reset. All endpoints require MANAGE_USERS_AND_ROLES permission. On create: generate temporary password (12 chars, meets complexity), set must_change_password=true. Audit log entry created for each operation with event_type: user.created, user.deactivated, user.role_assigned, user.password_reset_forced.
**Steps:** Create AdminUserManagementController with the four endpoints, all annotated @RequiresAdminPermission('MANAGE_USERS_AND_ROLES'); Implement AdminUserService.createUser(email, name, roleCode, actorId): validate role_code exists in hub_role; create hub_user row; generate temp password; record audit log entry with event_type='user.created', previous_value=null, new_value=serialized user; Implement deactivateUser / reactivateUser: update hub_user.is_active; audit log 'user.deactivated' or 'user.reactivated' with previous_value=true/false; Implement assignRole: update hub_user.role_id; audit log 'user.role_assigned' with previous_value=old_role_code, new_value=new_role_code; Implement forcePasswordReset: set must_change_password=true; audit log 'user.password_reset_forced'
**Deliverable:** AdminUserManagementController and AdminUserService with CRUD user operations and audit logging
**Acceptance / logic checks:**
- POST /admin/v1/users by OPS_OPERATOR returns 403; by SUPER_ADMIN returns 201
- Creating a user with role_code='NONEXISTENT' returns 422 with error code INVALID_ROLE
- Deactivating a user sets is_active=false; subsequent hasPermission call for that user returns false
- Assigning a new role creates audit log entry with previous_value=old_role_code and new_value=new_role_code
- Newly created user has must_change_password=true; creating a duplicate email returns 409
**Depends on:** 13.2-T07, 13.2-T09

### 13.2-T14 — Implement Partner Portal user management API: invite, deactivate user (PARTNER_ADMIN only)  _(45 min)_
**Context:** PARTNER_ADMIN can invite new portal users (creating a partner_portal_user row for their own partner) and deactivate existing users within the same partner (PRD-08 §2.3, SEC-09 §3.4.3). Endpoints: POST /portal/v1/users (invite), PATCH /portal/v1/users/{portalUserId}/status. Both require MANAGE_PORTAL_USERS permission. Data isolation: newly created user's partner_id is set from the authenticated PARTNER_ADMIN's session partner_id — never from the request body. PARTNER_ADMIN cannot modify users of other partners. New users are assigned PARTNER_VIEWER by default; PARTNER_ADMIN can upgrade to PARTNER_ADMIN. GME Ops creates the initial PARTNER_ADMIN account during onboarding.
**Steps:** Create PortalUserManagementController with invite and deactivate endpoints; annotate with @RequiresPortalPermission('MANAGE_PORTAL_USERS'); Implement PortalUserService.inviteUser(invitingUserId, email, name, roleCode): extract partner_id from authenticated principal; validate roleCode in {PARTNER_ADMIN, PARTNER_VIEWER}; create partner_portal_user with partner_id from session; send invitation email; Validate that the invited user's partner_id is always the session partner_id (ignore any partner_id in request body); Implement deactivatePortalUser(actingUserId, targetPortalUserId): verify target user belongs to same partner via requireOwnPartner; set is_active=false; Log user.invited and user.deactivated to audit log with actor_id from session
**Deliverable:** PortalUserManagementController and PortalUserService for partner-scoped user management
**Acceptance / logic checks:**
- PARTNER_VIEWER calling POST /portal/v1/users returns 403
- PARTNER_ADMIN for Partner A cannot deactivate a user belonging to Partner B (403)
- Invited user's partner_id equals authenticated PARTNER_ADMIN's partner_id even if a different partner_id is supplied in the request body
- Deactivating already-inactive user returns 409 with error ALREADY_INACTIVE
- Audit log entry created for user.invited with actor_id = inviting PARTNER_ADMIN's userId
**Depends on:** 13.2-T08, 13.2-T10

### 13.2-T15 — Implement JWT claims enrichment: embed role and permissions in session token (Admin Portal)  _(55 min)_
**Context:** After successful Admin Portal login, the session JWT must include: sub (hub_user.id), email, role_code, permissions (array of permission strings from hub_role.permissions). This allows the API Gateway and AOP aspects to resolve permissions without a DB lookup per request. Token signed with HS256/RS256. Session duration: max 8 hours, idle timeout 30 minutes (SEC-09 §3.1). On token issuance, record the login event in the audit log (event_type='auth.login', ip_address from request). Token must be invalidated on deactivation — use a jti blocklist or short TTL (15 minutes) with refresh token pattern.
**Steps:** Create AdminJwtService: issueToken(HubUser user, String ipAddress) builds JWT with claims: sub=user.id, email, role_code, permissions array from hub_role, iat, exp=iat+8h, jti=UUID; Add idle timeout: access token TTL 30 minutes; client must refresh using a refresh token (TTL 8h); Record audit log entry 'auth.login' with actor_id, ip_address, timestamp on every token issuance; On logout or deactivation: add jti to a jti_blocklist table (id UUID, expires_at TIMESTAMPTZ); check blocklist on every token validation; Create jti_blocklist migration V13_2_006__create_jti_blocklist.sql with an index on jti and scheduled purge of expired rows
**Deliverable:** AdminJwtService and jti_blocklist migration V13_2_006; JWT includes role and permissions claims
**Acceptance / logic checks:**
- Issued JWT contains claims: sub, email, role_code, permissions array
- Permissions array in JWT for OPS_OPERATOR does not contain MANAGE_USERS_AND_ROLES
- Token with jti in the blocklist is rejected (HTTP 401) even if signature is valid and not expired
- Token exp is at most now + 8h; access token TTL is 30 minutes
- auth.login audit log entry written with correct ip_address on each successful login
**Depends on:** 13.2-T07, 13.2-T04

### 13.2-T16 — Implement JWT claims enrichment for Partner Portal session token  _(55 min)_
**Context:** Partner Portal JWT (SEC-09 §3.2, PRD-08 §2.1): after successful TOTP-verified login, issue a JWT with claims: sub=partner_portal_user.id, partner_id, role_code, permissions array from partner_portal_role.permissions, email, iat, exp. Session: 8h absolute / idle timeout 15 minutes (PRD-08 §2.1 states 8h idle timeout; use 15-minute access token + refresh). Password policy: min 12 chars, bcrypt. TOTP must have been enrolled (totp_secret_enc not null) before login succeeds — if not enrolled, redirect to enrolment flow. Log auth.login with partner_id and ip_address in audit log.
**Steps:** Create PortalJwtService: issueToken(PartnerPortalUser user, String ipAddress) builds JWT with sub, partner_id, role_code, permissions, iat, exp=iat+8h, jti; Validate TOTP at login: PortalAuthService.login(email, password, totpCode, partnerId) — verify password bcrypt, verify TOTP code; if totp_secret_enc is null, return error code TOTP_NOT_ENROLLED; Issue access token (TTL 15 min) + refresh token (TTL 8h); refresh endpoint POST /portal/v1/auth/refresh; Reuse jti_blocklist table (separate from admin; or partition by portal type) for invalidation; Log auth.login audit entry with actor_id, partner_id, ip_address
**Deliverable:** PortalJwtService and PortalAuthService.login with TOTP enforcement and JWT containing partner_id + permissions
**Acceptance / logic checks:**
- Login without TOTP code returns 401 with error TOTP_REQUIRED
- Login with incorrect TOTP returns 401 TOTP_INVALID
- Issued JWT contains partner_id claim matching the authenticated user's partner
- PARTNER_VIEWER JWT permissions array does not contain MANAGE_PORTAL_USERS
- 5 consecutive failed logins lock the account for 15 minutes per SEC-09 §3.2
**Depends on:** 13.2-T08, 13.2-T06

### 13.2-T17 — Implement repository-layer partner_id enforcement for all partner-scoped queries  _(50 min)_
**Context:** SEC-09 §3.5 requires that every DB query returning partner-scoped data includes WHERE partner_id = authenticated_partner_id at the ORM/repository layer, not only in business logic. This is the defence-in-depth layer below the AOP annotations. Implement a PartnerScopedRepository base class or Spring Data JPA Specification that automatically appends partner_id filtering. The partner_id must come from a ThreadLocal/RequestContext set by the authentication filter, never from the caller's parameter.
**Steps:** Create PartnerContext ThreadLocal holder: PartnerContext.set(UUID partnerId) called by auth filter after token validation; PartnerContext.get() used in repository layer; Create PartnerScopedSpecification<T> that appends AND partner_id = PartnerContext.get() to any JPA Specification; Override relevant repository findBy methods in TransactionRepository, PrefundingLedgerRepository, PartnerPortalUserRepository to require PartnerContext is set (throw IllegalStateException if PartnerContext.get() returns null in a partner-scoped method); Add a Spring Security filter PartnerContextFilter that sets PartnerContext from JWT partner_id claim after token validation; clears PartnerContext in finally block; Add integration test verifying that TransactionRepository.findById(partnerB_txnId) returns empty when PartnerContext is set to Partner A's id
**Deliverable:** PartnerContext ThreadLocal, PartnerContextFilter, and updated repository methods with mandatory partner_id scoping
**Acceptance / logic checks:**
- TransactionRepository.findById on a txn belonging to Partner B returns Optional.empty() when PartnerContext holds Partner A's id
- Calling a partner-scoped repository method without PartnerContext set throws IllegalStateException (not a silent data leak)
- PartnerContext is cleared from ThreadLocal after each request (no ThreadLocal leakage across requests in thread pool)
- Admin portal service calls (no PartnerContext) are unaffected: they use separate non-partner-scoped repositories
- Unit test: SQL generated by PartnerScopedSpecification includes WHERE partner_id = :partnerId clause
**Depends on:** 13.2-T08, 13.2-T05

### 13.2-T18 — Implement RBAC enforcement middleware for admin-only endpoints: route-level guard  _(45 min)_
**Context:** Admin-only endpoints (POST /admin/v1/*, GET /admin/v1/audit-log/*, etc.) must never be reachable through the Partner API gateway (SEC-09 §3.4 note: 'admin-only endpoints not routed through partner API gateway'). Implement a Spring Security configuration that: (1) routes /admin/v1/** to require authentication as hub_user; (2) routes /portal/v1/** to require authentication as partner_portal_user; (3) completely blocks /admin/v1/** from any request bearing a Partner Portal JWT. Separate SecurityFilterChain beans for each portal.
**Steps:** Create AdminPortalSecurityConfig: SecurityFilterChain that matches /admin/v1/** and requires HubUserAuthenticationToken in SecurityContext; Create PartnerPortalSecurityConfig: SecurityFilterChain that matches /portal/v1/** and requires PartnerPortalAuthenticationToken; In AdminPortalSecurityConfig, add a filter that rejects requests bearing Partner Portal JWTs with HTTP 403 (wrong token type claim); In PartnerPortalSecurityConfig, add a filter that rejects requests bearing Admin Portal JWTs with HTTP 403; Both chains: unauthenticated requests return HTTP 401 with WWW-Authenticate header
**Deliverable:** AdminPortalSecurityConfig and PartnerPortalSecurityConfig Spring Security filter chain beans enforcing portal separation
**Acceptance / logic checks:**
- POST /admin/v1/schemes with a valid Partner Portal JWT returns 403
- POST /portal/v1/auth/login with a valid Admin Portal JWT returns 403
- GET /admin/v1/users with no token returns 401
- GET /portal/v1/transactions with a valid Admin Portal JWT returns 403
- Two SecurityFilterChain beans both load without conflict; admin chain matches /admin/v1/** and portal chain matches /portal/v1/**
**Depends on:** 13.2-T15, 13.2-T16

### 13.2-T19 — Implement privilege escalation guard: Ops cannot activate partner; Finance cannot write config  _(45 min)_
**Context:** SEC-09 §3.4 threat T-07 specifically states: 'RBAC prevents Ops from activating partners (Admin only); margin changes are audit-logged with previous value and actor; Admin approval required for rule activation.' Implement service-layer guards (separate from AOP) that enforce: (1) PartnerActivationService.activatePartner throws AccessDeniedException unless caller has PARTNER_REGISTRATION_ACTIVATION permission (SUPER_ADMIN only); (2) RuleActivationService.activateRule throws AccessDeniedException unless caller has CREATE_EDIT_RULE permission; (3) MarginUpdateService.updateMargin also enforces RULE_MARGIN_UPDATE permission. These are defence-in-depth below the controller annotations.
**Steps:** In PartnerActivationService.activatePartner(partnerId, actorId): call AdminPermissionEvaluator.hasPermission(actorId, 'PARTNER_REGISTRATION_ACTIVATION'); throw AccessDeniedException if false; In RuleActivationService.activateRule(ruleId, actorId): call hasPermission(actorId, 'CREATE_EDIT_RULE'); In MarginUpdateService.updateMargin(ruleId, newMarginA, newMarginB, actorId): call hasPermission(actorId, 'RULE_MARGIN_UPDATE'); additionally enforce combined margin >= 2.0% for cross-border rules (min_combined_margin invariant from RATE-04); Each service method writes an audit log entry on success with previous_value and new_value; Write a unit test that simulates OPS_OPERATOR calling activatePartner and asserts AccessDeniedException is thrown
**Deliverable:** Service-layer permission guards in PartnerActivationService, RuleActivationService, MarginUpdateService
**Acceptance / logic checks:**
- OPS_OPERATOR calling activatePartner throws AccessDeniedException at service layer (not just controller)
- SUPER_ADMIN calling activatePartner succeeds
- MarginUpdateService rejects m_a=1.0 + m_b=0.9 (combined 1.9%) with MARGIN_BELOW_MINIMUM error
- MarginUpdateService accepts m_a=1.0 + m_b=1.0 (combined 2.0%) and writes audit entry with previous m_a value
- RuleActivationService throws AccessDeniedException for FINANCE_ANALYST even if controller annotation were bypassed
**Depends on:** 13.2-T07, 13.2-T11

### 13.2-T20 — Implement IDOR prevention: Admin portal endpoint test for cross-partner resource access  _(45 min)_
**Context:** OWASP API1 / SEC-09 §3.5: every endpoint that retrieves a resource by ID must verify ownership or access rights before returning data. For Admin Portal: admin roles (Ops, Finance) may legitimately view all partners' data — no partner scoping. For Partner Portal: must enforce partner_id ownership. The IDOR test is a mandatory test case per QA-12. This ticket implements the service-layer check method and a reusable test harness. Pattern: PartnerResourceAccessChecker.assertAccessible(UUID resourcePartnerId, Principal caller) — for Admin portal callers always passes; for portal callers verifies partnerId matches.
**Steps:** Create PartnerResourceAccessChecker service with method assertAccessible(UUID resourcePartnerId, SecurityPrincipal caller): if caller is HubUserPrincipal (admin) return immediately; if caller is PortalPrincipal call requireOwnPartner; Integrate assertAccessible into TransactionService.getTransaction(transactionId, caller) and PrefundingService.getBalance(partnerId, caller); Add test class IdorPreventionTest: create two partner portal users (User_A for PartnerA, User_B for PartnerB) and a transaction T_A belonging to PartnerA; Assert User_B calling getTransaction(T_A.id, User_B_principal) returns empty or throws AccessDeniedException; Assert User_A calling getTransaction(T_A.id, User_A_principal) returns the transaction
**Deliverable:** PartnerResourceAccessChecker service and IdorPreventionTest integration test class
**Acceptance / logic checks:**
- User_B cannot retrieve Transaction T_A (different partner) — AccessDeniedException or empty result
- User_A can retrieve Transaction T_A — correct data returned
- Admin portal OPS_OPERATOR can retrieve Transaction T_A for any partner — no restriction
- The check runs at the service layer so it cannot be bypassed by calling the repository directly from a controller
- Test class runs as part of standard mvn test suite without external dependencies
**Depends on:** 13.2-T17, 13.2-T08

### 13.2-T21 — Implement audit log write service for RBAC-relevant events  _(50 min)_
**Context:** SEC-09 §6.1 and PRD-07 §13.2 define that every write operation in the Admin System generates an audit log entry. Relevant RBAC events: user.created, user.deactivated, user.reactivated, user.role_assigned, user.password_reset_forced, auth.login, auth.logout, auth.login_failed, portal.user.invited, portal.user.deactivated. Each entry includes: entry_id UUID, timestamp UTC millisecond, actor_user_id, actor_role at time, event_type, entity_type, entity_id, previous_value JSONB, new_value JSONB, ip_address. The audit_log table is append-only (insert-only DB permissions for the app service account). Source: SEC-09 §6.1, PRD-07 §13.3.
**Steps:** Confirm audit_log table exists (from earlier work package or create migration V13_2_007__ensure_audit_log.sql with columns as above if not already present); Create AuditLogService.writeEntry(AuditEntry entry): INSERT into audit_log; never update or delete; method is @Transactional(propagation=REQUIRES_NEW) to ensure the audit entry is committed even if the calling transaction rolls back; Define AuditEntry record/POJO with all fields from SEC-09 §6.1; Wire AuditLogService into AdminUserService, PortalUserService, AdminJwtService, PortalAuthService at each RBAC-event point; Add a constraint test: calling UPDATE on audit_log returns a permission denied error (simulated via a test user without UPDATE grant)
**Deliverable:** AuditLogService with writeEntry method wired into all RBAC event sites; audit_log table confirmed append-only
**Acceptance / logic checks:**
- Calling AdminUserService.createUser writes an audit log entry with event_type='user.created', previous_value=null
- Audit log entry for user.role_assigned contains both previous_value (old role_code) and new_value (new role_code)
- AuditLogService.writeEntry uses REQUIRES_NEW propagation: if the outer transaction rolls back, the audit entry is still committed
- auth.login entry is written with the correct ip_address on successful Admin Portal login
- auth.login_failed entry is written (with ip_address) on each failed login attempt
**Depends on:** 13.2-T13, 13.2-T14, 13.2-T15, 13.2-T16

### 13.2-T22 — Unit tests: AdminPermissionEvaluator — full permission matrix coverage  _(45 min)_
**Context:** Test all 5 roles x 24 permissions combinations to verify the permission matrix is correctly implemented (SEC-09 §3.4.3, PRD-07 §12.3). Use mock/stubbed hub_role repository returning the JSONB arrays seeded in T04. Key boundaries: FINANCE_ANALYST has CREATE_MANUAL_ADJUSTMENT but not RECORD_TOPUP; OPS_OPERATOR has RECORD_TOPUP but not CREATE_MANUAL_ADJUSTMENT; only SUPER_ADMIN has MANAGE_USERS_AND_ROLES and EXPORT_AUDIT_LOG; ADMIN_VIEWER has only 4 permissions.
**Steps:** Create AdminPermissionEvaluatorTest JUnit 5 class; Mock hub_user repository to return users with each of the 4 role codes; Test SUPER_ADMIN: assert hasPermission returns true for all 24 permissions; Test OPS_OPERATOR: assert hasPermission returns true for its 19 permissions; false for MANAGE_USERS_AND_ROLES, EXPORT_AUDIT_LOG, CREATE_MANUAL_ADJUSTMENT, PARTNER_REGISTRATION_ACTIVATION, and ACTIVATE_SUSPEND_SCHEME (check matrix); Test FINANCE_ANALYST: true for its 10; false for all write-config, INITIATE_REFUND, RECORD_TOPUP, MANAGE_USERS_AND_ROLES; Test ADMIN_VIEWER: true for VIEW_DASHBOARD, VIEW_FX_RATE_HISTORY, SEARCH_VIEW_TRANSACTIONS, VIEW_REFUND_STATUS only; false for all others; Test inactive user: hasPermission always returns false regardless of role
**Deliverable:** AdminPermissionEvaluatorTest with >=50 assertion-bearing test cases covering full matrix
**Acceptance / logic checks:**
- All 4 roles tested; total assertions >= 50 (24 perms x roles with selected combos)
- OPS_OPERATOR false on MANAGE_USERS_AND_ROLES confirmed explicitly
- FINANCE_ANALYST true on CREATE_MANUAL_ADJUSTMENT but false on RECORD_TOPUP confirmed explicitly
- Inactive user returns false on VIEW_DASHBOARD (a permission all roles have when active)
- Test suite passes with mvn test -pl <module> in under 10 seconds
**Depends on:** 13.2-T07

### 13.2-T23 — Unit tests: PartnerPortalPermissionEvaluator — role isolation and partner scoping  _(35 min)_
**Context:** Test PartnerPortalPermissionEvaluator (T08) for: (1) permission checks for PARTNER_ADMIN and PARTNER_VIEWER; (2) partner scoping via isOwnPartner and requireOwnPartner. PARTNER_ADMIN has MANAGE_PORTAL_USERS; PARTNER_VIEWER does not. Both have VIEW_OWN_TRANSACTIONS. requireOwnPartner must throw AccessDeniedException when partner mismatch.
**Steps:** Create PartnerPortalPermissionEvaluatorTest JUnit 5 class with mocked partner_portal_user repository; Test PARTNER_VIEWER: true for VIEW_OWN_TRANSACTIONS, VIEW_OWN_PREFUNDING_BALANCE, VIEW_OWN_API_CREDENTIALS; false for MANAGE_PORTAL_USERS; Test PARTNER_ADMIN: true for all 6 permissions including MANAGE_PORTAL_USERS; Test isOwnPartner: returns true when portalUserId.partner_id == resourcePartnerId; false otherwise; Test requireOwnPartner: throws AccessDeniedException when partner mismatch; does NOT throw when match; Test inactive portal user: hasPermission returns false for any permission
**Deliverable:** PartnerPortalPermissionEvaluatorTest with >= 20 assertion-bearing test cases
**Acceptance / logic checks:**
- PARTNER_VIEWER false on MANAGE_PORTAL_USERS asserted explicitly
- PARTNER_ADMIN true on MANAGE_PORTAL_USERS asserted explicitly
- requireOwnPartner throws AccessDeniedException with cross-partner call (not AccessDeniedException revealing partner B name)
- isOwnPartner returns true for same-partner call and false for cross-partner call
- Inactive PARTNER_ADMIN returns false on VIEW_OWN_TRANSACTIONS
**Depends on:** 13.2-T08

### 13.2-T24 — Integration tests: Admin Portal RBAC enforcement end-to-end (HTTP layer)  _(55 min)_
**Context:** Verify that RBAC annotations and filter chain enforce the permission matrix at the HTTP level for the Admin Portal. Use Spring Boot test with TestRestTemplate or MockMvc. Create test JWT tokens for each of the 4 roles, exercise key gated endpoints, and assert correct HTTP status codes. Cover: write endpoints returning 403 for under-privileged roles; read endpoints returning 200 for permitted roles; auth endpoints returning 401 for unauthenticated. Test list: (1) OPS_OPERATOR POST /admin/v1/users -> 403; (2) FINANCE_ANALYST POST /admin/v1/schemes -> 403; (3) ADMIN_VIEWER GET /admin/v1/transactions -> 200; (4) ADMIN_VIEWER GET /admin/v1/prefunding/balance -> 403; (5) Partner Portal JWT on /admin/v1/* -> 403.
**Steps:** Create AdminRbacIntegrationTest @SpringBootTest class with @AutoConfigureMockMvc; Create helper buildAdminJwt(roleCode) to produce a valid signed JWT with the correct claims; Test case: SUPER_ADMIN POST /admin/v1/users -> 201 (or 200 depending on impl); Test case: OPS_OPERATOR POST /admin/v1/users -> 403; Test case: FINANCE_ANALYST POST /admin/v1/schemes -> 403; Test case: ADMIN_VIEWER GET /admin/v1/transactions -> 200; Test case: ADMIN_VIEWER GET /admin/v1/transactions/{id}/pool-values -> 403; Test case: valid Partner Portal JWT on POST /admin/v1/schemes -> 403; Test case: no token on GET /admin/v1/users -> 401
**Deliverable:** AdminRbacIntegrationTest class with >=9 integration test cases covering HTTP-layer RBAC enforcement
**Acceptance / logic checks:**
- All 9 test cases pass
- OPS_OPERATOR 403 on user management endpoints confirmed
- ADMIN_VIEWER 403 on pool-values endpoint confirmed
- Partner Portal JWT rejected at Admin Portal endpoint (403, not 500)
- Unauthenticated request returns 401 not 403
**Depends on:** 13.2-T11, 13.2-T15, 13.2-T18

### 13.2-T25 — Integration tests: Partner Portal IDOR prevention and role enforcement end-to-end  _(55 min)_
**Context:** Mandatory IDOR test cases per QA-12 and SEC-09 §3.5. Verify (1) Partner A user cannot access Partner B transaction/balance; (2) PARTNER_VIEWER cannot call user-management endpoints. Use Spring Boot test, create two partner records (PartnerA, PartnerB), two users (UserA PARTNER_ADMIN, UserB PARTNER_VIEWER), two transactions (TxnA for PartnerA, TxnB for PartnerB). Assert HTTP responses and that no Partner B data is visible to Partner A.
**Steps:** Create PartnerPortalIdorIntegrationTest @SpringBootTest class; Insert test data: PartnerA (id=UUID_A), PartnerB (id=UUID_B), UserA (PARTNER_ADMIN for UUID_A), UserB (PARTNER_VIEWER for UUID_B), TxnA (partner_id=UUID_A), TxnB (partner_id=UUID_B); Test: UserA GET /portal/v1/transactions/{TxnA.id} -> 200 with correct data; Test: UserA GET /portal/v1/transactions/{TxnB.id} -> 403 (not 404, to avoid confirming existence); Test: UserB POST /portal/v1/users -> 403 (PARTNER_VIEWER cannot manage users); Test: UserA POST /portal/v1/users -> 200 or 201 (PARTNER_ADMIN can); Test: UserA GET /portal/v1/balance (for own partner) -> 200; Test: UserA GET /portal/v1/balance with partner_id=UUID_B in request body -> 200 but data is Partner A's balance (body param ignored)
**Deliverable:** PartnerPortalIdorIntegrationTest with >= 8 test cases covering IDOR and role enforcement
**Acceptance / logic checks:**
- UserA accessing TxnB returns 403 (IDOR blocked)
- UserB calling POST /portal/v1/users returns 403
- Response body for cross-partner 403 does not reveal TxnB data or PartnerB name
- Balance endpoint returns PartnerA balance even when partner_id override attempted via request body
- All 8 test cases pass in standard mvn test run
**Depends on:** 13.2-T12, 13.2-T17, 13.2-T16

### 13.2-T26 — Unit tests: audit log write for RBAC events — verify entries are correct and append-only  _(50 min)_
**Context:** Verify that AuditLogService (T21) writes correct entries for RBAC events and that the append-only constraint is respected. Test: user.created entry has all mandatory fields; user.role_assigned has previous_value; auth.login_failed increments attempt count; REQUIRES_NEW propagation commits audit entry even when outer transaction rolls back.
**Steps:** Create AuditLogServiceTest JUnit 5 class with in-memory H2 database or TestContainers PostgreSQL; Test user.created: call AdminUserService.createUser with mocked dependencies; capture audit log INSERT; assert entry_id UUID, event_type='user.created', entity_type='hub_user', previous_value=null, new_value contains email and role_code; Test user.role_assigned: call AdminUserService.assignRole from SUPER_ADMIN; assert previous_value=old_role_code, new_value=new_role_code in audit entry; Test REQUIRES_NEW propagation: execute createUser inside a transaction that deliberately rolls back; verify audit log entry is still present; Test append-only: verify no UPDATE or DELETE on audit_log is possible via AuditLogService (method does not exist); attempt via JDBC with app user credentials and expect permission denied (if using TestContainers with restricted user)
**Deliverable:** AuditLogServiceTest with >= 10 test cases covering correct entry fields and append-only semantics
**Acceptance / logic checks:**
- user.created audit entry has non-null entry_id, timestamp, actor_user_id, ip_address
- user.role_assigned audit entry previous_value is old role_code; new_value is new role_code
- Audit entry committed even when outer transaction rolls back (REQUIRES_NEW confirmed)
- AuditLogService class has no update() or delete() method
- Test for append-only DB permission passes (no UPDATE granted to app service account)
**Depends on:** 13.2-T21

### 13.2-T27 — Add session-idle and absolute-timeout enforcement for Admin Portal  _(55 min)_
**Context:** SEC-09 §3.1: Admin Portal session max 8 hours, idle timeout 30 minutes. Phase 1 uses local portal accounts (not SSO). Implementation: access token TTL = 30 minutes; refresh token TTL = 8 hours; POST /admin/v1/auth/refresh exchanges refresh token for a new access token if within 8h window; refresh token is single-use (rotated); both are added to jti_blocklist on logout. Re-authentication required for Finance settlement approval and partner activation (privileged actions) — implement a re-auth endpoint POST /admin/v1/auth/reauth that returns a short-lived elevated token (TTL 5 minutes) for these operations.
**Steps:** Create AdminAuthController: POST /admin/v1/auth/login, POST /admin/v1/auth/logout, POST /admin/v1/auth/refresh, POST /admin/v1/auth/reauth; Login: verify email+password (bcrypt); if must_change_password=true return 403 MUST_CHANGE_PASSWORD; write auth.login audit entry; issue access token (30 min) + refresh token (8h); Refresh: validate refresh token signature and jti not in blocklist; rotate refresh token (add old jti to blocklist); issue new access token + new refresh token; Logout: add both access token jti and refresh token jti to jti_blocklist; write auth.logout audit entry; Reauth: verify current password again; issue elevated token with claim elevated=true and TTL 5 minutes; only FINANCE_ANALYST settlement and SUPER_ADMIN partner activation service methods accept elevated=true claim
**Deliverable:** AdminAuthController with login, logout, refresh, reauth endpoints enforcing SEC-09 §3.1 session controls
**Acceptance / logic checks:**
- Accessing /admin/v1/users with an expired access token (>30 min) returns 401
- Refresh endpoint returns new tokens and invalidates the old refresh token jti
- Using the old refresh token after rotation returns 401 (jti in blocklist)
- Reauth endpoint returns elevated token with TTL 5 minutes; using it after 5 min for partner activation returns 401
- After logout, using the access token jti that was invalidated returns 401
**Depends on:** 13.2-T15, 13.2-T21

### 13.2-T28 — Add session-idle and absolute-timeout enforcement for Partner Portal  _(55 min)_
**Context:** SEC-09 §3.2: Partner Portal session 8 hours absolute, idle timeout 15 minutes. Account lockout: 5 consecutive failures -> 15-minute lockout. MFA (TOTP) required for all portal users. Implement: access token TTL 15 min, refresh TTL 8h, same jti rotation pattern as T27. Lockout: track failed_login_attempts (column on partner_portal_user) and locked_until TIMESTAMPTZ; lockout for 15 minutes after 5th failure; reset on successful login. Lockout is per-email (not per IP).
**Steps:** Add migration V13_2_008__partner_portal_user_lockout.sql: add columns failed_login_attempts INT DEFAULT 0 and locked_until TIMESTAMPTZ to partner_portal_user; In PortalAuthService.login: if locked_until > now() return 401 ACCOUNT_LOCKED with Retry-After header (seconds until unlock); On each failed login: increment failed_login_attempts; if count reaches 5 set locked_until = now() + INTERVAL '15 minutes'; write auth.login_failed audit entry; On successful login: reset failed_login_attempts=0, locked_until=null; write auth.login audit entry; Create PortalAuthController: POST /portal/v1/auth/login, /logout, /refresh (same rotation pattern as T27)
**Deliverable:** Migration V13_2_008, PortalAuthController, and updated PortalAuthService with lockout and session controls
**Acceptance / logic checks:**
- 5 consecutive wrong passwords lock the account; 6th attempt within lock window returns 401 ACCOUNT_LOCKED
- Correct password after lockout expires (15 min) succeeds and resets counter to 0
- Idle timeout enforced: accessing portal with 15-min-old access token returns 401
- Using rotated-away refresh token returns 401 (jti in blocklist)
- auth.login_failed audit entry written on each failure with ip_address
**Depends on:** 13.2-T16, 13.2-T21

### 13.2-T29 — Document RBAC model: internal developer reference for permission matrix and enforcement points  _(40 min)_
**Context:** Per the ticket brief, every work package that has logic-bearing tickets must include a documentation ticket. This produces a concise internal technical reference (not a user guide) describing the RBAC model, the permission constants, the AOP annotation usage, the partner scoping pattern, and the session token structure. Target audience: developers joining the team. Format: Markdown file in the /docs/security/ directory, max 4 pages. Must include the full permission matrix table for both portals.
**Steps:** Create /docs/security/rbac-model.md; Section 1: Overview — two portals (Admin and Partner), roles, and the separation between them; Section 2: Admin Portal permission matrix — table with 4 roles x 24 capabilities with Yes/No per cell, matching SEC-09 §3.4.3 and PRD-07 §12.3; Section 3: Partner Portal permission matrix — PARTNER_ADMIN vs PARTNER_VIEWER x 6 capabilities; Section 4: Enforcement architecture — @RequiresAdminPermission / @RequiresPortalPermission AOP, PartnerContext ThreadLocal, JWT claims, jti_blocklist; Section 5: Adding a new permission — step-by-step: add constant to AdminPermission, update AdminRolePermissions, add DB migration to seed new permission, annotate endpoint, add test
**Deliverable:** /docs/security/rbac-model.md internal developer reference document
**Acceptance / logic checks:**
- File exists at /docs/security/rbac-model.md and is valid Markdown
- Admin permission matrix table lists all 24 permission codes with correct Yes/No for all 4 roles
- Partner portal matrix lists 6 permissions with PARTNER_ADMIN vs PARTNER_VIEWER
- Section 4 names the correct class names: AdminRbacAspect, PartnerPortalRbacAspect, PartnerContext, jti_blocklist
- Section 5 includes the DB migration step (without it a new permission would not be persisted to hub_role)
**Depends on:** 13.2-T11, 13.2-T12, 13.2-T18


## WBS 13.9 — Credential lifecycle & key rotation
### 13.9-T01 — DB migration: partner_credential table with overlap window support  _(30 min)_
**Context:** GMEPay+ issues API key + HMAC secret pairs to partners at onboarding. Credentials are stored in partner_credential (id BIGINT PK, partner_id BIGINT FK, api_key VARCHAR(64) UNIQUE, api_secret_hash VARCHAR(128) bcrypt hash, hmac_secret_vault_ref VARCHAR(256) vault path, status VARCHAR(20) PENDING|ACTIVE|REVOKED, overlap_expires_at TIMESTAMPTZ nullable, expires_at TIMESTAMPTZ nullable, created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ, created_by VARCHAR(64), updated_by VARCHAR(64)). Only one ACTIVE credential per partner allowed; PENDING credentials co-exist with ACTIVE during the overlap window (default 7 days). The HMAC secret is distinct from the API key and stored encrypted in vault; only its vault path is in the DB. The API secret is stored as a bcrypt hash; plaintext is never persisted.
**Steps:** Create Flyway/Liquibase migration V13_9_01__partner_credential.sql; Add partner_credential table with all columns listed in context; Add UNIQUE index on api_key; Add partial unique index: only one ACTIVE row per partner_id (WHERE status = ACTIVE); Add index on (partner_id, status) for lookup performance; Add index on overlap_expires_at for expiry sweep job
**Deliverable:** Migration file V13_9_01__partner_credential.sql applying cleanly to a fresh schema
**Acceptance / logic checks:**
- Table exists with all specified columns and correct types after migration runs
- UNIQUE constraint on api_key rejects a duplicate api_key insert
- Partial unique index prevents inserting a second ACTIVE row for the same partner_id
- A PENDING row can coexist with an ACTIVE row for the same partner_id (partial index does not block it)
- Migration is idempotent via Flyway checksum; re-running on an already-migrated DB is a no-op

### 13.9-T02 — DB migration: sftp_key table for ZeroPay SFTP key lifecycle  _(25 min)_
**Context:** GMEPay+ manages Ed25519 SSH key pairs per environment (sandbox, production) for ZeroPay SFTP authentication. The private key is stored in HashiCorp Vault (or cloud-equivalent); only the vault reference is stored in the DB. The public key is registered with KFTC. sftp_key table: id BIGINT PK, environment VARCHAR(20) SANDBOX|PRODUCTION, vault_private_key_ref VARCHAR(256), public_key_fingerprint VARCHAR(128), public_key_pem TEXT, status VARCHAR(20) GENERATING|PENDING_KFTC|ACTIVE|REVOKED, kftc_confirmed_at TIMESTAMPTZ nullable, activated_at TIMESTAMPTZ nullable, revoked_at TIMESTAMPTZ nullable, created_at TIMESTAMPTZ, created_by VARCHAR(64), updated_at TIMESTAMPTZ, updated_by VARCHAR(64). Only one ACTIVE key per environment at a time.
**Steps:** Create migration V13_9_02__sftp_key.sql; Add sftp_key table with all columns above; Add partial unique index: one ACTIVE row per environment (WHERE status = ACTIVE); Add index on (environment, status); Seed a placeholder PENDING_KFTC row for each environment (optional, document as seed data)
**Deliverable:** Migration file V13_9_02__sftp_key.sql
**Acceptance / logic checks:**
- sftp_key table exists with all columns and correct types
- Partial unique index prevents a second ACTIVE row for the same environment
- A PENDING_KFTC row and an ACTIVE row can coexist for the same environment
- public_key_fingerprint is stored as VARCHAR(128), not as a secret (it is public)
- vault_private_key_ref column is not UNIQUE globally (multiple envs can have separate refs)
**Depends on:** 13.9-T01

### 13.9-T03 — DB migration: credential_audit_event table for key lifecycle events  _(25 min)_
**Context:** SEC-09 §6.1 and §9 require that every credential operation (issuance, rotation, revocation, expiry sweep) is appended to an immutable audit log. credential_audit_event table: id BIGINT PK, event_type VARCHAR(50) e.g. API_KEY_ISSUED, API_KEY_ROTATED, API_KEY_REVOKED, API_KEY_EXPIRED, SFTP_KEY_GENERATED, SFTP_KEY_KFTC_CONFIRMED, SFTP_KEY_ACTIVATED, SFTP_KEY_REVOKED, entity_type VARCHAR(30) PARTNER_CREDENTIAL|SFTP_KEY, entity_id BIGINT, partner_id BIGINT nullable (null for SFTP events), actor_id VARCHAR(64), actor_role VARCHAR(30), previous_status VARCHAR(20) nullable, new_status VARCHAR(20), overlap_expires_at TIMESTAMPTZ nullable, ip_address VARCHAR(45), note TEXT nullable, created_at TIMESTAMPTZ server-generated. Table is append-only; application DB user must NOT have UPDATE or DELETE on this table. Each row includes prev_hash VARCHAR(64) SHA-256 over previous row to form hash chain.
**Steps:** Create migration V13_9_03__credential_audit_event.sql; Add credential_audit_event table with all columns above; Grant INSERT-only privilege to the application service account on this table (document in migration comments if DB permission grants are managed separately); Add index on (partner_id, created_at) for portal queries; Add index on (entity_type, entity_id)
**Deliverable:** Migration file V13_9_03__credential_audit_event.sql
**Acceptance / logic checks:**
- credential_audit_event table exists with prev_hash VARCHAR(64) column
- Application role cannot UPDATE or DELETE rows (verify with INFORMATION_SCHEMA or role grant check)
- All expected event_type values fit in VARCHAR(50)
- Index on (partner_id, created_at) exists and is used by EXPLAIN ANALYZE for partner-scoped queries
- prev_hash column is nullable only for the very first row (first entry has no predecessor)
**Depends on:** 13.9-T02

### 13.9-T04 — CredentialGeneratorService: generate API key + HMAC secret pair  _(45 min)_
**Context:** SEC-09 §9.1 requires GME Admin (Admin role) to generate a credential pair (API key + HMAC secret) via Admin portal. API key = cryptographically random 32-byte value hex-encoded to 64 chars (VARCHAR(64)); HMAC secret = cryptographically random 32-byte value base64url-encoded to ~44 chars. The API secret_hash = bcrypt(plaintext_secret, cost=12). The HMAC secret is stored in vault at path gmepay/{env}/partner/{partner_uuid}/hmac and only the vault path is stored in partner_credential.hmac_secret_vault_ref. The plaintext secret is returned ONCE from this service method and NEVER stored. The generated credential is inserted with status=PENDING until explicitly activated (or it becomes the first credential, see T05).
**Steps:** Create CredentialGeneratorService class with method generateCredentialPair(partnerId, actorId, actorRole, ipAddress); Generate api_key: SecureRandom 32 bytes -> hex string; Generate hmac_secret plaintext: SecureRandom 32 bytes -> base64url; Hash api_secret: BCrypt.hashpw(plaintextSecret, BCrypt.gensalt(12)); Write hmac_secret to vault; store vault path in hmac_secret_vault_ref; Insert row into partner_credential with status=PENDING; Return DTO { api_key, plaintext_secret (once only), credential_id }
**Deliverable:** CredentialGeneratorService.generateCredentialPair() method and unit tests
**Acceptance / logic checks:**
- api_key is exactly 64 hex characters (matches [0-9a-f]{64})
- api_secret_hash stored in DB passes BCrypt.checkpw(plaintextSecret, storedHash) = true
- plaintext_secret is NOT stored anywhere in DB or logs (check by querying partner_credential table after call)
- hmac_secret_vault_ref matches pattern gmepay/{env}/partner/{uuid}/hmac and vault path is reachable
- New credential row is inserted with status=PENDING and created_by = actorId
**Depends on:** 13.9-T03

### 13.9-T05 — CredentialActivationService: activate first credential or rotate with overlap  _(45 min)_
**Context:** SEC-09 §9.1 and §9.2: when a partner receives their first credential, it transitions PENDING->ACTIVE immediately. For rotation, a new PENDING credential is activated and the old ACTIVE credential is moved to status=PENDING_EXPIRY (will auto-expire) with overlap_expires_at = now() + overlapDays (default 7, configurable 0-30 days). During the overlap window, both old and new credentials are accepted by the auth service. If overlapDays=0 (emergency revocation), old credential is immediately set to REVOKED. Business rule: only one credential may be in ACTIVE status per partner. A credential in PENDING_EXPIRY is still valid for auth but cannot be rotated again.
**Steps:** Create CredentialActivationService.activateCredential(credentialId, overlapDays, actorId, actorRole, ipAddress); Fetch the new credential (must be PENDING), fetch current ACTIVE credential if any; In a DB transaction: set new cred status=ACTIVE; if old ACTIVE cred exists and overlapDays>0 set it status=PENDING_EXPIRY overlap_expires_at=now()+overlapDays; if overlapDays=0 set old cred status=REVOKED; Write audit event API_KEY_ISSUED (first issuance) or API_KEY_ROTATED (rotation) to credential_audit_event; Return activation result with overlap_expires_at
**Deliverable:** CredentialActivationService.activateCredential() with @Transactional and unit tests
**Acceptance / logic checks:**
- After first-issuance activation: partner has exactly one ACTIVE credential, no PENDING rows remain
- After rotation with overlapDays=7: partner has one ACTIVE (new) + one PENDING_EXPIRY (old) with overlap_expires_at = now()+7d within 1 second tolerance
- After emergency rotation with overlapDays=0: old credential status=REVOKED, no PENDING_EXPIRY row exists
- DB constraint enforced: attempting to activate a second credential without transitioning the first throws ConstraintViolationException (partial unique index)
- Audit event row written with correct previous_status and new_status fields
**Depends on:** 13.9-T04

### 13.9-T06 — CredentialRevokeService: immediate revocation of partner API key  _(40 min)_
**Context:** SEC-09 §9.1: revocation deactivates the credential; all subsequent requests using it are rejected with HTTP 401 CREDENTIALS_REVOKED. Revocation applies to any credential in ACTIVE or PENDING_EXPIRY status. After revocation: status=REVOKED, revoked_at=now(). The HMAC secret vault entry is deleted or disabled at the vault path hmac_secret_vault_ref. Revocation is logged to credential_audit_event as API_KEY_REVOKED with actor, reason, and ip_address. Revocation is irreversible; a new credential must be issued if the partner needs access restored.
**Steps:** Create CredentialRevokeService.revokeCredential(credentialId, reason, actorId, actorRole, ipAddress); Validate credential belongs to the correct partner (prevent IDOR); fetch row; In a DB transaction: set status=REVOKED, updated_at=now(), updated_by=actorId; Invalidate vault entry at hmac_secret_vault_ref (delete or disable depending on vault backend); Write API_KEY_REVOKED audit event with reason, previous_status, new_status=REVOKED; Return revocation confirmation
**Deliverable:** CredentialRevokeService.revokeCredential() with unit tests covering normal and edge cases
**Acceptance / logic checks:**
- Revoking an ACTIVE credential sets status=REVOKED and revoked_at is non-null
- Revoking a PENDING_EXPIRY credential also sets status=REVOKED
- Attempting to revoke an already-REVOKED credential throws InvalidStateException (idempotent guard)
- Revoking a credential belonging to a different partner_id throws AccessDeniedException (IDOR guard)
- Audit event row is written with actor_id, reason, and event_type=API_KEY_REVOKED
**Depends on:** 13.9-T05

### 13.9-T07 — PartnerApiAuthService: multi-credential lookup supporting overlap window  _(55 min)_
**Context:** SEC-09 §3.3 and §9.2: during the overlap window, both ACTIVE and PENDING_EXPIRY credentials are accepted. Auth lookup must: 1) find the credential row by api_key; 2) check status IN (ACTIVE, PENDING_EXPIRY); 3) if PENDING_EXPIRY, verify overlap_expires_at > now(); 4) verify BCrypt.checkpw(providedSecret, api_secret_hash). If status=REVOKED or PENDING_EXPIRY expired, return HTTP 401 CREDENTIALS_REVOKED. If api_key not found, return HTTP 401 INVALID_CREDENTIALS. If HMAC signature invalid, return HTTP 401 SIGNATURE_INVALID. This service is called by the API Gateway auth filter on every inbound request.
**Steps:** Create PartnerApiAuthService.authenticate(apiKey, providedSecret, hmacSignature, requestCanonical); Query partner_credential WHERE api_key = ? AND status IN (ACTIVE, PENDING_EXPIRY); If row found and status=PENDING_EXPIRY and overlap_expires_at <= now(): treat as REVOKED, return 401 CREDENTIALS_REVOKED; Check BCrypt.checkpw(providedSecret, api_secret_hash); on failure return 401 INVALID_CREDENTIALS; Fetch hmac_secret from vault via hmac_secret_vault_ref; verify HMAC-SHA256(hmac_secret, requestCanonical); on failure return 401 SIGNATURE_INVALID; On success return AuthResult with partner_id, credential_id, status
**Deliverable:** PartnerApiAuthService.authenticate() and unit tests with happy path, revoked, expired-overlap, wrong-secret, wrong-hmac cases
**Acceptance / logic checks:**
- ACTIVE credential + correct secret + correct HMAC -> AuthResult with partner_id populated
- REVOKED credential with correct secret -> 401 CREDENTIALS_REVOKED
- PENDING_EXPIRY credential with overlap_expires_at in future + correct creds -> AuthResult (overlap accepted)
- PENDING_EXPIRY credential with overlap_expires_at in the past -> 401 CREDENTIALS_REVOKED
- Correct api_key + correct secret but wrong HMAC -> 401 SIGNATURE_INVALID
**Depends on:** 13.9-T06

### 13.9-T08 — OverlapExpiryJob: scheduled job to auto-revoke expired PENDING_EXPIRY credentials  _(45 min)_
**Context:** SEC-09 §9.2: after the overlap window (default 7 days, configurable), the old credential is automatically revoked. A scheduled job (daily, configurable) sweeps partner_credential WHERE status=PENDING_EXPIRY AND overlap_expires_at <= now() and sets each to REVOKED. For each revoked credential, the vault entry is disabled and an API_KEY_EXPIRED audit event is written to credential_audit_event. The job must be idempotent (safe to re-run). Log the count of credentials auto-revoked per run.
**Steps:** Create OverlapExpiryJob (Spring @Scheduled or Quartz, configurable cron, default 0 0 * * * i.e. hourly); Query SELECT id, hmac_secret_vault_ref FROM partner_credential WHERE status = PENDING_EXPIRY AND overlap_expires_at <= now(); For each row: in a transaction set status=REVOKED, updated_at=now(); disable vault entry; write API_KEY_EXPIRED audit event; Log total count of revoked credentials at INFO level; Expose job result via admin monitoring endpoint (last_run, count_revoked, errors)
**Deliverable:** OverlapExpiryJob class with integration test seeding 3 expired PENDING_EXPIRY rows and verifying all 3 are REVOKED after job run
**Acceptance / logic checks:**
- Job run with 3 expired PENDING_EXPIRY rows -> all 3 set to REVOKED, 3 audit events written
- Job run with no expired rows -> 0 rows changed, no error
- Non-expired PENDING_EXPIRY rows (overlap_expires_at in future) are NOT revoked by the job
- ACTIVE rows are NOT touched by the job
- Job is idempotent: running twice on same data produces no additional changes or duplicate audit events
**Depends on:** 13.9-T07

### 13.9-T09 — SftpKeyGeneratorService: generate Ed25519 key pair in vault  _(50 min)_
**Context:** SEC-09 §2.5 and §9.3: GMEPay+ generates Ed25519 SSH key pairs (one per environment: SANDBOX, PRODUCTION). The private key is generated and stored exclusively in HashiCorp Vault (path: gmepay/{env}/sftp/zeropay/private_key); the private key NEVER touches disk or DB. The public key fingerprint (SHA-256 of public key bytes, hex-encoded) and the public key in OpenSSH format are stored in sftp_key table. Initial status = GENERATING. After vault write, status transitions to PENDING_KFTC. The vault entry version is recorded for audit. If vault write fails, no DB row is created (atomic failure).
**Steps:** Create SftpKeyGeneratorService.generateKeyPair(environment, actorId, actorRole, ipAddress); Generate Ed25519 key pair in memory using Java security provider (BouncyCastle or JCE); Write private key to vault at gmepay/{env}/sftp/zeropay/private_key_v{timestamp}; record vault_ref; Compute public key fingerprint: SHA-256(raw public key bytes) hex-encoded; Insert sftp_key row: status=PENDING_KFTC, public_key_pem=OpenSSH format, vault_private_key_ref, public_key_fingerprint; Write SFTP_KEY_GENERATED audit event; Return SftpKeyDto { sftp_key_id, public_key_pem, public_key_fingerprint }
**Deliverable:** SftpKeyGeneratorService.generateKeyPair() with unit tests (vault mocked)
**Acceptance / logic checks:**
- public_key_fingerprint is exactly 64 hex chars (SHA-256 = 32 bytes = 64 hex)
- sftp_key row status=PENDING_KFTC after successful generation
- If vault write throws an exception, no sftp_key row is committed to DB (transaction rollback)
- Private key bytes are not present in any log output or return value
- Audit event SFTP_KEY_GENERATED written with actor_id and environment
**Depends on:** 13.9-T03

### 13.9-T10 — SftpKeyActivationService: mark KFTC confirmation and activate new SFTP key  _(40 min)_
**Context:** SEC-09 §9.3 lifecycle: after Ops registers the new public key with KFTC, KFTC confirms. Ops then records the confirmation in the Admin portal. The service: (1) marks kftc_confirmed_at=now() on the sftp_key row; (2) transitions status PENDING_KFTC->ACTIVE; (3) updates vault pointer gmepay/{env}/sftp/zeropay/active_ref to point to the new key's vault path; (4) does NOT yet revoke the old key (old key must stay usable until Ops explicitly revokes it, per the 5-business-day lead time assumption). Business rule: only one key may be ACTIVE per environment. If another key is already ACTIVE, it is moved to status PENDING_REVOCATION; it is NOT revoked until T11 is called.
**Steps:** Create SftpKeyActivationService.confirmKftcAndActivate(sftpKeyId, actorId, actorRole, ipAddress); Validate sftpKeyId exists and status=PENDING_KFTC; fetch environment; In DB transaction: set kftc_confirmed_at=now(), status=ACTIVE, activated_at=now() on new key; move old ACTIVE key (if any) to PENDING_REVOCATION; Update vault active_ref pointer to new key's vault path; Write SFTP_KEY_KFTC_CONFIRMED and SFTP_KEY_ACTIVATED audit events; Return activation result with old key id (if any) for Ops awareness
**Deliverable:** SftpKeyActivationService.confirmKftcAndActivate() with unit tests
**Acceptance / logic checks:**
- After activation: new sftp_key row status=ACTIVE, kftc_confirmed_at is non-null
- Old ACTIVE key (if existed) is now PENDING_REVOCATION (not REVOKED)
- Vault active_ref pointer updated to new key path
- Activating a key already in ACTIVE status throws InvalidStateException
- Both audit events (KFTC_CONFIRMED and ACTIVATED) are written in a single call
**Depends on:** 13.9-T09

### 13.9-T11 — SftpKeyRevokeService: revoke old SFTP key and destroy vault entry  _(40 min)_
**Context:** SEC-09 §9.3: Ops revokes the old SFTP key only after KFTC confirms the new key is active. Revocation: status=REVOKED, revoked_at=now(); the old private key vault entry is permanently deleted (not just disabled). Per assumption: minimum 5 business days lead time; Ops must not call this until KFTC confirmation is received. Revocation is only allowed on keys in PENDING_REVOCATION status (enforced). After revocation, the vault entry is destroyed; recovery is impossible. Audit event SFTP_KEY_REVOKED with actor and reason.
**Steps:** Create SftpKeyRevokeService.revokeKey(sftpKeyId, reason, actorId, actorRole, ipAddress); Validate sftpKeyId exists and status=PENDING_REVOCATION (block revocation of ACTIVE or GENERATING keys); Permanently delete vault entry at vault_private_key_ref; if vault delete fails, abort and throw (do not set REVOKED until vault delete confirmed); In DB transaction: set status=REVOKED, revoked_at=now(); Write SFTP_KEY_REVOKED audit event with reason and vault_private_key_ref (for evidence); Return revocation confirmation
**Deliverable:** SftpKeyRevokeService.revokeKey() with unit tests covering all status guard cases
**Acceptance / logic checks:**
- Revoking a PENDING_REVOCATION key: status=REVOKED, revoked_at non-null, vault entry deleted
- Attempting to revoke an ACTIVE key throws InvalidStateException (ACTIVE keys cannot be directly revoked)
- If vault delete throws, DB row status remains PENDING_REVOCATION (no partial state)
- Revoking an already-REVOKED key throws InvalidStateException (idempotency guard)
- Audit event SFTP_KEY_REVOKED is written with vault_private_key_ref and reason fields
**Depends on:** 13.9-T10

### 13.9-T12 — Admin API: POST /admin/v1/partners/{id}/credentials/generate  _(45 min)_
**Context:** SEC-09 §9.1 and PRD-07 §5.3.4: Admin (Admin role only) generates a new credential pair for a partner. This endpoint calls CredentialGeneratorService.generateCredentialPair() then CredentialActivationService.activateCredential(). The plaintext secret is returned ONCE in the response body and never stored. If the partner has an existing ACTIVE credential, the request body must include overlapDays (0-30, default 7). RBAC: only Admin role may call this endpoint; Ops and Finance roles receive 403. Request: POST /admin/v1/partners/{partnerId}/credentials/generate body: { overlapDays: 7 }. Response 201: { api_key, plaintext_secret, hmac_secret_ref (vault path for ops reference), overlap_expires_at, credential_id }.
**Steps:** Create CredentialController.generateCredentials() with @PreAuthorize(hasRole(ADMIN)); Validate partnerId exists; validate overlapDays 0-30; Call CredentialGeneratorService.generateCredentialPair() then CredentialActivationService.activateCredential(); Return 201 with response DTO; plaintext_secret must appear exactly once; Ensure plaintext_secret is excluded from all logs (mask in access log filter)
**Deliverable:** POST /admin/v1/partners/{id}/credentials/generate endpoint handler with integration tests
**Acceptance / logic checks:**
- Admin role calling with valid partnerId and overlapDays=7 -> 201 with api_key (64 hex chars) and non-null plaintext_secret
- Ops role calling same endpoint -> 403 Forbidden
- Partner with no existing credential + overlapDays omitted -> generates with no overlap (first issuance)
- plaintext_secret is not present in application logs after the call (verify log output in test)
- Response plaintext_secret passes BCrypt.checkpw against the stored api_secret_hash in DB
**Depends on:** 13.9-T05

### 13.9-T13 — Admin API: POST /admin/v1/partners/{id}/credentials/{credId}/revoke  _(40 min)_
**Context:** SEC-09 §9.1 and PRD-07 §5.3.4: Admin (Admin role only) immediately revokes a partner API credential. Calls CredentialRevokeService.revokeCredential(). Used for both scheduled revocation (after overlap window) and emergency revocation (suspected compromise). Request: POST /admin/v1/partners/{partnerId}/credentials/{credentialId}/revoke body: { reason: string, emergency: boolean }. If emergency=true, a P1 SEC incident record is created and partner is notified (via async event). Response 200: { credential_id, status: REVOKED, revoked_at, audit_event_id }. Requires confirmation token in header X-Confirm: REVOKE to prevent accidental calls.
**Steps:** Create CredentialController.revokeCredential() with @PreAuthorize(hasRole(ADMIN)); Validate X-Confirm header equals literal string REVOKE (reject 400 if absent); Validate credentialId belongs to partnerId (IDOR guard); Call CredentialRevokeService.revokeCredential() with reason and actorId; If emergency=true, publish PartnerCredentialCompromisedEvent (async); log at WARN level; Return 200 with credential_id, status, revoked_at, audit_event_id
**Deliverable:** POST /admin/v1/partners/{id}/credentials/{credId}/revoke endpoint with integration tests
**Acceptance / logic checks:**
- Valid Admin call with X-Confirm header -> 200, DB status=REVOKED
- Missing X-Confirm header -> 400 Bad Request
- Ops role call -> 403 Forbidden
- Revoking credId belonging to a different partnerId -> 404 (IDOR guard; do not reveal existence)
- After revocation, calling PartnerApiAuthService.authenticate with the revoked api_key -> 401 CREDENTIALS_REVOKED
**Depends on:** 13.9-T06, 13.9-T12

### 13.9-T14 — Admin API: GET /admin/v1/partners/{id}/credentials  _(30 min)_
**Context:** PRD-07 §5.3.4 View Active Key: shows api_key (not secret), creation timestamp, last_used_at timestamp, status, overlap_expires_at. Returns ALL credentials for the partner (ACTIVE, PENDING_EXPIRY, PENDING_REVOCATION, REVOKED) sorted by created_at DESC. The api_secret_hash is never returned. The hmac_secret is never returned. last_used_at is tracked separately (see T15). Accessible by Admin role (all partners) and Ops role (all partners, read-only). Partner roles cannot call this endpoint.
**Steps:** Create GET /admin/v1/partners/{id}/credentials endpoint; Query partner_credential WHERE partner_id = ? ORDER BY created_at DESC; Map to CredentialListItemDto: { credential_id, api_key (NOT secret), status, created_at, expires_at, overlap_expires_at, last_used_at, created_by }; Enforce RBAC: Admin and Ops roles allowed; Finance and Partner roles -> 403; Return 200 with array; 404 if partner not found
**Deliverable:** GET /admin/v1/partners/{id}/credentials endpoint with integration tests
**Acceptance / logic checks:**
- Admin role fetching credentials for a valid partner -> 200 with api_key present but api_secret_hash absent from response
- Ops role call -> 200 (read allowed)
- Finance role call -> 403
- Response contains all status values (ACTIVE, PENDING_EXPIRY) not just ACTIVE ones
- api_key in response is exactly 64 hex characters matching the DB value
**Depends on:** 13.9-T07

### 13.9-T15 — Auth filter: update last_used_at on successful credential use  _(35 min)_
**Context:** SEC-09 §6.4 and PRD-07 §5.3.4: the Admin portal shows last_used_at for each credential. After every successful authentication in PartnerApiAuthService, the credential's last_used_at is updated to now(). To avoid write contention on the hot path, updates are async (fire-and-forget via a bounded queue or @Async method). Write failures in the async updater must be logged at WARN but must NOT fail the authentication response. last_used_at is stored in partner_credential table (add column via migration V13_9_15).
**Steps:** Create migration V13_9_15__credential_last_used.sql adding last_used_at TIMESTAMPTZ nullable to partner_credential; Create LastUsedUpdater.recordUse(credentialId) @Async method that executes UPDATE partner_credential SET last_used_at=now() WHERE id=?; Call LastUsedUpdater.recordUse() from PartnerApiAuthService after successful auth; Verify async failures are caught and logged at WARN; auth response is unaffected; Add last_used_at to CredentialListItemDto in GET /admin/v1/partners/{id}/credentials response (T14)
**Deliverable:** Migration V13_9_15 + LastUsedUpdater + integration test verifying last_used_at is populated after a successful auth call
**Acceptance / logic checks:**
- After a successful auth, last_used_at on the credential row updates to within 2 seconds of now()
- If LastUsedUpdater throws an exception, the authentication result is still returned successfully (no propagation)
- last_used_at remains null for a credential that has never been used
- last_used_at is returned in the GET /admin/v1/partners/{id}/credentials response
- Migration adds column without dropping or modifying any existing column
**Depends on:** 13.9-T14

### 13.9-T16 — Admin API: POST /admin/v1/sftp-keys/generate - generate new SFTP key pair  _(35 min)_
**Context:** SEC-09 §9.3 and OPS-13 §8.4: Ops (Admin role) initiates SFTP key rotation from Admin portal. Creates a new Ed25519 key pair via SftpKeyGeneratorService. Request: POST /admin/v1/sftp-keys/generate body: { environment: SANDBOX|PRODUCTION }. Response 200: { sftp_key_id, public_key_pem (OpenSSH format), public_key_fingerprint (SHA-256 hex), status: PENDING_KFTC, created_at }. The public key PEM must be displayed in the Admin portal so Ops can copy it for submission to KFTC. Only one key generation is allowed per environment while another is already in PENDING_KFTC status.
**Steps:** Create SftpKeyController.generateSftpKey() with @PreAuthorize(hasRole(ADMIN)); Validate environment in {SANDBOX, PRODUCTION}; Check no key already in PENDING_KFTC or GENERATING status for that environment; if so return 409 ROTATION_ALREADY_IN_PROGRESS; Call SftpKeyGeneratorService.generateKeyPair(); Return 200 with public_key_pem, public_key_fingerprint, sftp_key_id, status
**Deliverable:** POST /admin/v1/sftp-keys/generate endpoint with integration tests
**Acceptance / logic checks:**
- Admin role call with environment=PRODUCTION -> 200 with public_key_pem in OpenSSH ed25519 format
- Ops role call -> 403 (only Admin role may trigger key generation)
- Second call when a PENDING_KFTC key already exists for the same environment -> 409 ROTATION_ALREADY_IN_PROGRESS
- public_key_fingerprint in response is 64 hex chars
- sftp_key row in DB has status=PENDING_KFTC after the call
**Depends on:** 13.9-T09

### 13.9-T17 — Admin API: POST /admin/v1/sftp-keys/{id}/confirm-kftc - confirm KFTC activation  _(35 min)_
**Context:** SEC-09 §9.3: after KFTC confirms the new public key is active on their SFTP server, Ops records this in the Admin portal. Request: POST /admin/v1/sftp-keys/{sftpKeyId}/confirm-kftc body: { kftc_confirmation_ref: string (KFTC ticket or email reference, max 200 chars) }. Calls SftpKeyActivationService.confirmKftcAndActivate(). After this call, the vault active_ref pointer is updated; the SFTP client begins using the new key on next connection. The old key (if any) moves to PENDING_REVOCATION. The kftc_confirmation_ref is stored in the note field of the audit event.
**Steps:** Create SftpKeyController.confirmKftcActivation() with @PreAuthorize(hasRole(ADMIN)); Validate sftpKeyId exists and status=PENDING_KFTC; validate kftc_confirmation_ref non-blank max 200 chars; Call SftpKeyActivationService.confirmKftcAndActivate(); Return 200 with { sftp_key_id, status: ACTIVE, kftc_confirmed_at, old_key_id (nullable), old_key_status: PENDING_REVOCATION }
**Deliverable:** POST /admin/v1/sftp-keys/{id}/confirm-kftc endpoint with integration tests
**Acceptance / logic checks:**
- Valid Admin call with PENDING_KFTC key -> 200, key status=ACTIVE, old key status=PENDING_REVOCATION
- Calling on a key that is already ACTIVE -> 409 with error INVALID_KEY_STATUS
- Ops role call -> 403
- kftc_confirmation_ref is stored in the audit event note field
- vault active_ref pointer reflects the newly activated key id after the call
**Depends on:** 13.9-T10, 13.9-T16

### 13.9-T18 — Admin API: POST /admin/v1/sftp-keys/{id}/revoke - revoke old SFTP key  _(35 min)_
**Context:** SEC-09 §9.3: Ops revokes the old SFTP key (in PENDING_REVOCATION status) only after confirming KFTC has the new key active. Minimum assumed lead time is 5 business days (enforced as a soft check: warn if revoked_at - kftc_confirmed_at < 5 business days, but do not block). Request: POST /admin/v1/sftp-keys/{sftpKeyId}/revoke body: { reason: string }. Requires X-Confirm: REVOKE header. Calls SftpKeyRevokeService.revokeKey(). Response 200: { sftp_key_id, status: REVOKED, revoked_at }. If the key is in ACTIVE status, reject 409 CANNOT_REVOKE_ACTIVE_KEY.
**Steps:** Create SftpKeyController.revokeSftpKey() with @PreAuthorize(hasRole(ADMIN)); Validate X-Confirm: REVOKE header present; Validate sftpKeyId exists and status=PENDING_REVOCATION; if ACTIVE return 409 CANNOT_REVOKE_ACTIVE_KEY; Check business-day gap between kftc_confirmed_at and now(); if < 5 business days, log WARN but proceed; Call SftpKeyRevokeService.revokeKey(); Return 200 with sftp_key_id, status=REVOKED, revoked_at
**Deliverable:** POST /admin/v1/sftp-keys/{id}/revoke endpoint with integration tests
**Acceptance / logic checks:**
- Valid Admin call on PENDING_REVOCATION key -> 200 status=REVOKED
- Missing X-Confirm header -> 400
- Calling on an ACTIVE key -> 409 CANNOT_REVOKE_ACTIVE_KEY
- Ops role call -> 403
- Revoking a key 2 business days after KFTC confirmation -> proceeds with WARN log entry (soft check only, not blocked)
**Depends on:** 13.9-T11, 13.9-T17

### 13.9-T19 — Admin API: GET /admin/v1/sftp-keys - list SFTP keys with status  _(25 min)_
**Context:** OPS-13 §8.4 and SEC-09 §9.3: Ops needs to see all SFTP keys, their status, and fingerprints. GET /admin/v1/sftp-keys?environment=PRODUCTION returns sftp_key rows with: sftp_key_id, environment, status, public_key_fingerprint, kftc_confirmed_at, activated_at, revoked_at, created_at, created_by. The private key and vault_private_key_ref are NEVER returned in the response. Admin and Ops roles may call; Finance and Partner roles are blocked.
**Steps:** Create SftpKeyController.listSftpKeys() GET /admin/v1/sftp-keys with optional ?environment query param; Query sftp_key table with optional WHERE environment = ? ORDER BY created_at DESC; Map to SftpKeyListItemDto excluding vault_private_key_ref and any private key material; Enforce RBAC: Admin and Ops roles allowed; Return 200 with list; empty list if no keys exist
**Deliverable:** GET /admin/v1/sftp-keys endpoint with integration tests
**Acceptance / logic checks:**
- Admin or Ops role with no filter -> returns all keys across environments
- Admin or Ops role with environment=PRODUCTION filter -> returns only PRODUCTION rows
- Finance role -> 403
- Response DTO does not contain vault_private_key_ref or any private key material
- Response correctly shows distinct statuses: PENDING_KFTC, ACTIVE, PENDING_REVOCATION, REVOKED
**Depends on:** 13.9-T18

### 13.9-T20 — Credential audit log API: GET /admin/v1/partners/{id}/credential-audit  _(30 min)_
**Context:** SEC-09 §6.1: all credential lifecycle events are recorded in credential_audit_event. The Admin portal must surface these to Super Admins. GET /admin/v1/partners/{partnerId}/credential-audit?from=&to= returns events for that partner ordered by created_at DESC. Each row: id, event_type, entity_id, actor_id, actor_role, previous_status, new_status, overlap_expires_at, ip_address, note, created_at. prev_hash is included so auditors can verify chain integrity. Only Admin role (Super Admin) may access this endpoint.
**Steps:** Create AuditController.getCredentialAudit() GET /admin/v1/partners/{id}/credential-audit; Accept optional from/to query params (ISO-8601 UTC); default last 90 days; Query credential_audit_event WHERE partner_id = ? AND created_at BETWEEN from AND to ORDER BY created_at DESC with max 500 rows; Return 200 with list including prev_hash for chain verification; Enforce RBAC: Admin role only; Ops, Finance, Partner roles -> 403
**Deliverable:** GET /admin/v1/partners/{id}/credential-audit endpoint with integration tests
**Acceptance / logic checks:**
- Admin role with valid partnerId -> 200 with audit events in descending created_at order
- Ops role -> 403
- Response includes prev_hash field on each row
- Date-range filter from/to correctly limits results
- SFTP events (partner_id=null) are not included in partner-scoped query; they appear only on the sftp-key audit endpoint
**Depends on:** 13.9-T13

### 13.9-T21 — Credential audit log API: GET /admin/v1/sftp-keys/{id}/audit  _(30 min)_
**Context:** SEC-09 §9.3: all SFTP key lifecycle events must be auditable. GET /admin/v1/sftp-keys/{sftpKeyId}/audit returns credential_audit_event rows WHERE entity_type=SFTP_KEY AND entity_id=sftpKeyId, ordered by created_at DESC. Each row includes event_type, actor_id, actor_role, previous_status, new_status, note (contains kftc_confirmation_ref for KFTC events), created_at, prev_hash. Admin role only.
**Steps:** Create AuditController.getSftpKeyAudit() GET /admin/v1/sftp-keys/{id}/audit; Query credential_audit_event WHERE entity_type=SFTP_KEY AND entity_id=? ORDER BY created_at DESC; Return 200 with audit event list including prev_hash; Enforce RBAC: Admin role only -> 403 for all others; Return 404 if sftpKeyId does not exist in sftp_key table
**Deliverable:** GET /admin/v1/sftp-keys/{id}/audit endpoint with integration tests
**Acceptance / logic checks:**
- Full SFTP key lifecycle test: generate -> confirm-kftc -> activate -> revoke produces 4 audit events in correct order
- Ops role -> 403
- Non-existent sftpKeyId -> 404
- note field contains kftc_confirmation_ref string for the SFTP_KEY_KFTC_CONFIRMED event
- prev_hash chain: each row's prev_hash matches SHA-256 of the previous row (except first row which is null)
**Depends on:** 13.9-T20

### 13.9-T22 — AuditHashChainService: compute and verify SHA-256 prev_hash chain  _(45 min)_
**Context:** SEC-09 §6.3: each credential_audit_event row contains prev_hash = SHA-256(JSON serialisation of the previous row excluding prev_hash field itself). The hash chain makes tampering detectable. Two operations needed: (1) computeHash(row): serialise all fields except prev_hash as canonical JSON (sorted keys, no whitespace), return hex(SHA-256(json)). (2) verifyChain(entityType, entityId): fetch all rows ordered by id ASC, re-compute expected prev_hash for each, compare to stored value; return VerificationResult { intact: boolean, firstBreakAtId: Long }. The daily integrity check job (T25) uses verifyChain.
**Steps:** Create AuditHashChainService.computeHash(CredentialAuditEventRow row) returning hex SHA-256 string; Implement canonical JSON: fields sorted alphabetically, no null fields omitted (include as null), no trailing whitespace; Create AuditHashChainService.verifyChain(String entityType, Long entityId) returning VerificationResult; Call computeHash during audit event insertion (before INSERT) to set prev_hash on the new row; Write unit tests with 3-row chain and verify each hash
**Deliverable:** AuditHashChainService with computeHash() and verifyChain() and unit tests
**Acceptance / logic checks:**
- computeHash on a known row with fixed field values produces a deterministic SHA-256 hex string
- For a 3-event chain: row2.prev_hash equals computeHash(row1) exactly
- verifyChain on an intact 3-row chain returns { intact: true, firstBreakAtId: null }
- If row2 fields are modified externally, verifyChain detects the break and returns { intact: false, firstBreakAtId: row2.id }
- First row in chain has prev_hash = null and is treated as valid by verifyChain
**Depends on:** 13.9-T03

### 13.9-T23 — Unit tests: CredentialGeneratorService - key format and storage invariants  _(35 min)_
**Context:** WBS 13.9 test ticket. Tests for CredentialGeneratorService (T04). Test vectors: (1) api_key = SecureRandom 32 bytes hexed, must be exactly 64 chars matching [0-9a-f]{64}. (2) BCrypt hash of the generated secret must verify with BCrypt.checkpw. (3) Calling generateCredentialPair twice produces different api_key values (no collision). (4) The returned DTO plaintext_secret field does not appear in the DB api_secret_hash column. (5) hmac_secret_vault_ref matches pattern gmepay/(sandbox|production)/partner/[0-9a-f-]{36}/hmac.
**Steps:** Open CredentialGeneratorServiceTest; Write test: generated api_key matches regex [0-9a-f]{64}; Write test: BCrypt.checkpw(returned_plaintext_secret, stored_api_secret_hash) == true; Write test: two calls produce distinct api_key values; Write test: DB api_secret_hash is NOT equal to plaintext_secret (hash is not identity); Write test: hmac_secret_vault_ref matches expected pattern using regex
**Deliverable:** CredentialGeneratorServiceTest with 5 test methods all passing
**Acceptance / logic checks:**
- All 5 test methods pass with zero failures
- api_key regex test uses Pattern.matches([0-9a-f]{64}, apiKey)
- BCrypt verify test uses BCrypt.checkpw(plaintext, hash) not string equality
- Distinctness test runs generateCredentialPair twice with same partnerId mock and asserts api_keys differ
- Vault ref pattern test uses a regex that requires UUID v4 format in the path
**Depends on:** 13.9-T04

### 13.9-T24 — Unit tests: PartnerApiAuthService - credential auth decision matrix  _(40 min)_
**Context:** WBS 13.9 test ticket. Tests for PartnerApiAuthService (T07). All 6 decision branches: (1) ACTIVE + correct secret + correct HMAC -> AuthResult with partner_id. (2) REVOKED + correct secret -> 401 CREDENTIALS_REVOKED. (3) PENDING_EXPIRY + overlap in future + correct creds -> AuthResult. (4) PENDING_EXPIRY + overlap expired -> 401 CREDENTIALS_REVOKED. (5) ACTIVE + correct api_key + wrong secret -> 401 INVALID_CREDENTIALS. (6) ACTIVE + correct secret + wrong HMAC -> 401 SIGNATURE_INVALID. Mock VaultClient and DB.
**Steps:** Create PartnerApiAuthServiceTest with Mockito mocks for partner_credential repository and VaultClient; Write test case for each of 6 branches above; For branch 3: set overlap_expires_at = now() + 1 day; For branch 4: set overlap_expires_at = now() - 1 second; Verify correct HTTP status codes and error codes in thrown exceptions or returned error objects
**Deliverable:** PartnerApiAuthServiceTest with 6 test methods all passing
**Acceptance / logic checks:**
- All 6 test methods pass
- Branch 3 test uses a fixed clock/time mock so overlap_expires_at comparison is deterministic
- Branch 4 test verifies error code is CREDENTIALS_REVOKED not INVALID_CREDENTIALS
- Branch 6 test verifies error code is SIGNATURE_INVALID distinct from INVALID_CREDENTIALS
- Each failing branch test verifies NO partner_id is leaked in the error response
**Depends on:** 13.9-T07

### 13.9-T25 — Unit tests: OverlapExpiryJob - sweep logic with mixed credential states  _(35 min)_
**Context:** WBS 13.9 test ticket. Tests for OverlapExpiryJob (T08). Seed: 5 credentials - 2 PENDING_EXPIRY with overlap_expires_at in the past (should be revoked), 1 PENDING_EXPIRY with overlap_expires_at in the future (should NOT be revoked), 1 ACTIVE (should NOT be touched), 1 REVOKED (should NOT be touched). After job run: exactly 2 credentials set to REVOKED, 1 PENDING_EXPIRY unchanged, 1 ACTIVE unchanged, 1 REVOKED unchanged. Exactly 2 API_KEY_EXPIRED audit events written.
**Steps:** Create OverlapExpiryJobTest with an in-memory DB (H2) or repository mocks; Seed 5 partner_credential rows as described in context; Call overlapExpiryJob.run(); Assert exactly 2 rows have status=REVOKED with revoked_at non-null; Assert 1 PENDING_EXPIRY row untouched; 1 ACTIVE untouched; 1 pre-existing REVOKED untouched; Assert exactly 2 credential_audit_event rows with event_type=API_KEY_EXPIRED
**Deliverable:** OverlapExpiryJobTest with assertions on all 5 credential rows and audit event count
**Acceptance / logic checks:**
- 2 expired PENDING_EXPIRY rows -> REVOKED after job run
- 1 future PENDING_EXPIRY row unchanged
- 1 ACTIVE row unchanged
- Pre-existing REVOKED row unchanged (not double-audited)
- Exactly 2 API_KEY_EXPIRED audit events in credential_audit_event table after run
**Depends on:** 13.9-T08

### 13.9-T26 — Unit tests: AuditHashChainService - chain integrity and tamper detection  _(35 min)_
**Context:** WBS 13.9 test ticket. Tests for AuditHashChainService (T22). Build a 4-row chain in memory: row1 (prev_hash=null), row2 (prev_hash=hash(row1)), row3 (prev_hash=hash(row2)), row4 (prev_hash=hash(row3)). Test: (1) verifyChain returns intact=true. (2) Modify row2.new_status externally; verifyChain returns intact=false, firstBreakAtId=row2.id. (3) computeHash is deterministic: same input twice produces identical hash. (4) computeHash output is exactly 64 hex chars. (5) Canonical JSON: field order does not affect hash (swap field order in serializer, same hash expected).
**Steps:** Create AuditHashChainServiceTest; Build 4-row chain using AuditHashChainService.computeHash() for each row; Test verifyChain on intact chain -> intact=true; Mutate row2.new_status field in-memory; re-call verifyChain -> intact=false, firstBreakAtId=row2.id; Test computeHash determinism: call twice with same row, compare outputs; Test computeHash output length == 64 and matches [0-9a-f]{64}
**Deliverable:** AuditHashChainServiceTest with 5+ test methods all passing
**Acceptance / logic checks:**
- verifyChain intact chain -> { intact: true, firstBreakAtId: null }
- verifyChain after mutating row2 -> { intact: false, firstBreakAtId: row2.id }
- computeHash is deterministic across two calls with identical input
- computeHash output exactly 64 lowercase hex chars
- Row 1 with prev_hash=null is not flagged as a chain break by verifyChain
**Depends on:** 13.9-T22

### 13.9-T27 — Integration test: full partner API key lifecycle - issue, use, rotate, expire, revoke  _(55 min)_
**Context:** WBS 13.9 integration test ticket. End-to-end happy path for the partner API credential lifecycle using an in-process test environment (TestContainers or H2 + Vault mock). Steps: (1) POST generate -> get api_key1, secret1. (2) Verify authenticate(api_key1, secret1) succeeds. (3) POST rotate with overlapDays=7 -> get api_key2, secret2; api_key1 is PENDING_EXPIRY. (4) Verify both api_key1 and api_key2 authenticate successfully. (5) Fast-forward clock to overlapDays+1; run OverlapExpiryJob. (6) Verify api_key1 auth -> 401 CREDENTIALS_REVOKED; api_key2 still succeeds. (7) POST revoke api_key2 -> api_key2 auth -> 401 CREDENTIALS_REVOKED.
**Steps:** Create CredentialLifecycleIntegrationTest using @SpringBootTest with TestContainers (PostgreSQL); Execute lifecycle steps 1-7 in order, asserting each expected state; Use fixed-clock bean override for steps 5-6; Assert credential_audit_event table contains events: API_KEY_ISSUED, API_KEY_ROTATED, API_KEY_EXPIRED, API_KEY_REVOKED; Assert last_used_at is populated on api_key1 after step 2
**Deliverable:** CredentialLifecycleIntegrationTest with all 7 lifecycle steps verified
**Acceptance / logic checks:**
- After step 3: DB shows one ACTIVE (api_key2) and one PENDING_EXPIRY (api_key1) row for the same partner
- After step 5 job run: api_key1 status=REVOKED, api_key2 status=ACTIVE
- After step 6: api_key1 auth throws 401 CREDENTIALS_REVOKED, api_key2 auth succeeds
- After step 7: api_key2 auth throws 401 CREDENTIALS_REVOKED
- audit_event table has exactly 4 rows for the partner with correct event_types in chronological order
**Depends on:** 13.9-T25, 13.9-T15

### 13.9-T28 — Integration test: SFTP key lifecycle - generate, confirm-KFTC, activate, revoke  _(55 min)_
**Context:** WBS 13.9 integration test. End-to-end happy path for SFTP key rotation using in-process test environment (Vault mocked). Steps: (1) POST /admin/v1/sftp-keys/generate environment=PRODUCTION -> sftp_key_id1, public_key_pem1, status=PENDING_KFTC. (2) Attempt second generate for PRODUCTION -> expect 409 ROTATION_ALREADY_IN_PROGRESS. (3) POST confirm-kftc on sftp_key_id1 with kftc_confirmation_ref=KFTC-REF-001 -> status=ACTIVE, vault active_ref updated. (4) POST generate again for PRODUCTION (previous now ACTIVE) -> sftp_key_id2, status=PENDING_KFTC; sftp_key_id1 transitions to PENDING_REVOCATION. (5) POST confirm-kftc for sftp_key_id2 -> sftp_key_id2 ACTIVE, sftp_key_id1 now PENDING_REVOCATION. (6) POST revoke sftp_key_id1 -> status=REVOKED.
**Steps:** Create SftpKeyLifecycleIntegrationTest; Execute lifecycle steps 1-6 in order; Assert DB states after each step; Assert audit events: SFTP_KEY_GENERATED, SFTP_KEY_KFTC_CONFIRMED, SFTP_KEY_ACTIVATED, SFTP_KEY_GENERATED (for key2), SFTP_KEY_REVOKED; Verify vault mock was called to delete the old private key path on step 6
**Deliverable:** SftpKeyLifecycleIntegrationTest with all 6 lifecycle steps verified
**Acceptance / logic checks:**
- After step 1: sftp_key row status=PENDING_KFTC, public_key_pem starts with ssh-ed25519
- After step 2: 409 ROTATION_ALREADY_IN_PROGRESS returned
- After step 3: status=ACTIVE, kftc_confirmed_at non-null
- After step 5: sftp_key_id2 ACTIVE, sftp_key_id1 PENDING_REVOCATION (two distinct keys in DB)
- After step 6: sftp_key_id1 REVOKED, vault delete called with correct private key path
**Depends on:** 13.9-T19, 13.9-T21

### 13.9-T29 — Runbook doc: credential lifecycle operational procedures (SEC-09 §9 distillation)  _(40 min)_
**Context:** OPS-13 §8.3 and §8.4 and SEC-09 §9: operators need step-by-step procedures for credential operations. Document covers: (1) Issue partner API credentials (first time). (2) Rotate partner API credentials - scheduled (7-day overlap default). (3) Emergency rotation on suspected compromise (overlap=0, create P1 incident, notify partner within 1 hour). (4) Rotate ZeroPay SFTP keys (5-business-day minimum lead time with KFTC; do not revoke old key until KFTC confirms new key). (5) Revoke compromised SFTP key. Document stored in docs/ops/credential-lifecycle.md.
**Steps:** Create docs/ops/credential-lifecycle.md; Write section 1: Issue partner API key - prerequisites, Admin portal path, secret transmission method (encrypted email or secure link), confirmation steps; Write section 2: Scheduled rotation - overlap window, monitoring auth logs, deactivation checklist; Write section 3: Emergency rotation - immediate revoke, P1 incident creation, partner notification within 1 hour, SEC-09 §10 reference; Write section 4: SFTP key rotation with KFTC coordination steps and 5-business-day note; Write section 5: Emergency SFTP revocation and vault destruction confirmation
**Deliverable:** docs/ops/credential-lifecycle.md covering all 5 procedures
**Acceptance / logic checks:**
- Section 3 (emergency) explicitly states overlap window = 0 days and partner notification within 1 hour
- Section 4 includes the 5-business-day minimum lead time assumption and the instruction to not revoke old key until KFTC confirms
- Each section references the Admin portal menu path (e.g. Partners -> [Name] -> Credentials)
- Document references SEC-09 §9.1, §9.2, §9.3 and OPS-13 §8.3, §8.4 in the appropriate sections
- Vault path format gmepay/{env}/partner/{uuid}/hmac and gmepay/{env}/sftp/zeropay/private_key is documented
**Depends on:** 13.9-T18


<!-- wbs-v3-gap-closure -->

---

## WBS v3 gap-closure tickets (re-baseline, 2026-06-10)

These tickets convert this service's PARTIAL audit findings into DONE and add work discovered during the build. Statuses live on the `Backlog` sheet of `GMEPay+_Task_Backlog.xlsx`; phase sequencing on the `Completion Plan v3` sheet of `GMEPay+_WBS.xlsx`.

### 17.2-G09 — auth-identity: swap H2 for real PostgreSQL ITs
*Completion phase:* **R1** · *Est:* 120 min · *Role:* Backend · *Deps:* 17.1-G02

**Context.** Tests currently run on H2 in PostgreSQL mode. Acceptance requires real PG. Scope: principals/api-keys/roles tables.

**Steps.**
- Add Testcontainers postgres:16 to the service's ITs
- Run Flyway migrations against it; fix PG-only syntax drift
- Keep H2 only for pure unit slices

**Deliverable.** Repository/migration ITs green on PostgreSQL 16

**Acceptance.**
- ./gradlew :services:auth-identity:test green with Testcontainers
- Migration checksum stable; no H2-mode workarounds left

### 18.4-G01 — JWT issuance (password + client-credentials)
*Completion phase:* **R3** · *Est:* 200 min · *Role:* Backend · *Deps:* 17.2-G09

**Context.** Replace BFF's password=demo stub. auth-identity issues RS256 JWTs with roles + partnerId claims; JWKS endpoint.

**Steps.**
- /oauth/token password + client_credentials grants
- RS256 keypair, /.well-known/jwks.json
- Refresh token rotation + revocation table

**Deliverable.** Real token issuance

**Acceptance.**
- Token validates via JWKS; refresh rotates; revoked refresh rejected

