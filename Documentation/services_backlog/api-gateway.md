# api-gateway  (backend)

**Scope:** Spring Cloud Gateway: routing, /v1, rate-limit, idempotency, OpenAPI

**Owned WBS work-packages:** 8.1, 8.5, 8.8, 8.9, 8.10  ·  **Tickets:** 135  ·  **Est:** 83.6h

## Service contract (MSA: own DB, API-only communication)

- **Datastore (owned by this service):** none (stateless edge); Redis for rate-limit/idempotency
- **APIs / events I EXPOSE:** public /v1/* partner API surface
- **APIs / events I CONSUME:** routes to all public services (sync); auth-identity (sync)
- **Integration rule:** never read another service's database or import its private entities — call its API or consume its event; stub consumed services with WireMock in tests.

> Self-contained backlog for this service. Build it as its own repo/module with its own DB + Flyway migrations, against the `shared-libs` contracts (lib-money / lib-errors / lib-events / lib-api-contracts only). Each ticket has a deliverable + acceptance checks.


## WBS 8.1 — API gateway & versioning framework
### 8.1-T01 — Scaffold Spring Cloud Gateway Gradle module in monorepo  _(25 min)_
**Context:** WBS 8.1 delivers the API gateway layer for GMEPay+. The monorepo uses Gradle multi-module with root settings.gradle. A dedicated gateway module named services/api-gateway must be created as a Spring Boot 3.x + Spring Cloud Gateway application. It is the single HTTPS entry point for all partner traffic (api.gmepayplus.com and api-sandbox.gmepayplus.com). No routing logic yet — just the buildable skeleton.
**Steps:** Add entry include('services:api-gateway') to root settings.gradle; Create services/api-gateway/build.gradle with dependencies: spring-cloud-starter-gateway, spring-boot-starter-actuator, spring-boot-starter-data-redis-reactive (for rate-limit state), micrometer-registry-prometheus; Create main class ApiGatewayApplication.java at services/api-gateway/src/main/java/com/gme/pay/gateway/ApiGatewayApplication.java annotated @SpringBootApplication; Add application.yml with spring.application.name=api-gateway, server.port=8080, management.endpoints.web.exposure.include=health,prometheus; Verify ./gradlew :services:api-gateway:build passes with no sources errors
**Deliverable:** services/api-gateway/build.gradle and services/api-gateway/src/main/java/com/gme/pay/gateway/ApiGatewayApplication.java
**Acceptance / logic checks:**
- ./gradlew :services:api-gateway:build completes without error
- Spring Boot context starts (log shows 'Started ApiGatewayApplication')
- GET /actuator/health returns {status:UP}
- Module appears in ./gradlew projects output

### 8.1-T02 — Define /v1 route prefix convention and global URI rewrite filter  _(30 min)_
**Context:** API-05 section 2.2: all endpoints are prefixed /v1. Spring Cloud Gateway must strip this prefix when forwarding to downstream services via Kubernetes service discovery (lb://service-name). A RewritePath filter maps /v1/{segment} to /{segment}. All routes live in module services/api-gateway; downstream service URIs follow lb://rate-fx, lb://payment-executor, lb://merchant-qr-data, lb://prefunding-balance.
**Steps:** Create GatewayRoutingConfig.java at services/api-gateway/src/main/java/com/gme/pay/gateway/config/GatewayRoutingConfig.java with a RouteLocatorBuilder bean; Define placeholder route for /v1/rates/** to lb://rate-fx with RewritePath=/v1/(?<segment>.*),/$\{segment}; Define placeholder route for /v1/payments/** to lb://payment-executor with same RewritePath pattern; Add global default-filter AddRequestHeader: X-Gateway-Version=v1 in application.yml; Set spring.cloud.gateway.discovery.locator.enabled=false (manual route registration only)
**Deliverable:** services/api-gateway/src/main/java/com/gme/pay/gateway/config/GatewayRoutingConfig.java
**Acceptance / logic checks:**
- Route for /v1/rates/** forwards to lb://rate-fx with /v1 prefix stripped
- Route for /v1/payments/** forwards to lb://payment-executor
- Unknown paths return 404 (no catch-all route)
- X-Gateway-Version: v1 header present on all forwarded downstream requests
- ./gradlew :services:api-gateway:build passes
**Depends on:** 8.1-T01

### 8.1-T03 — Implement X-Request-ID generation and propagation filter  _(35 min)_
**Context:** API-05 section 2.7: every response (success or error) must include X-Request-ID set by GMEPay+, format req_<ULID> e.g. req_01HX7MNPQRS4T5U6VWXY012Z. The filter runs at highest priority (Ordered.HIGHEST_PRECEDENCE). It sets the ID on both the downstream forwarded request and the outbound response. All downstream services use X-Request-ID as structured log correlation field per SAD-02 requirements.
**Steps:** Add com.github.f4b6a3:ulid-creator to services/api-gateway/build.gradle; Create RequestIdFilter.java at services/api-gateway/src/main/java/com/gme/pay/gateway/filter/RequestIdFilter.java implementing GlobalFilter, Ordered with order=Ordered.HIGHEST_PRECEDENCE+1; In filter: if X-Request-ID absent, generate req_+UlidCreator.getMonotonicUlid().toString() and set on request mutate and response headers; If X-Request-ID already present in incoming request, preserve it unchanged (idempotent); Write unit test RequestIdFilterTest.java: absent header generates new ID matching req_[A-Z0-9]{26}, present header is preserved
**Deliverable:** services/api-gateway/src/main/java/com/gme/pay/gateway/filter/RequestIdFilter.java and src/test/java/com/gme/pay/gateway/filter/RequestIdFilterTest.java
**Acceptance / logic checks:**
- Response always contains X-Request-ID matching pattern req_[A-Z0-9]{26}
- Incoming X-Request-ID is echoed unchanged in response (idempotent)
- Downstream requests carry X-Request-ID header
- Filter order equals Ordered.HIGHEST_PRECEDENCE+1
- Unit test covers absent and present header scenarios
**Depends on:** 8.1-T01

### 8.1-T04 — Implement X-Timestamp replay protection filter (300-second window)  _(35 min)_
**Context:** API-05 section 3.2: GMEPay+ rejects requests where |server_time - X-Timestamp| > 300 seconds. X-Timestamp must be ISO-8601 UTC millisecond precision (e.g. 2026-06-04T09:31:00.000Z). Error code: TIMESTAMP_OUT_OF_RANGE, HTTP 401. Error body matches lib-errors ApiErrorResponse envelope: {error:{code,message,request_id,details:[]}}. This filter runs before signature verification so malformed/stale requests are rejected cheaply.
**Steps:** Create TimestampValidationFilter.java at services/api-gateway/src/main/java/com/gme/pay/gateway/filter/TimestampValidationFilter.java implementing GlobalFilter, Ordered (order after RequestIdFilter); Parse X-Timestamp header as Instant (ISO-8601 UTC); if absent or unparseable return 401 TIMESTAMP_OUT_OF_RANGE; Compute Math.abs(Duration.between(timestamp, Instant.now()).toSeconds()); if > 300 return 401 TIMESTAMP_OUT_OF_RANGE; Serialize error as {error:{code:'TIMESTAMP_OUT_OF_RANGE',message:...,request_id:<X-Request-ID>,details:[]}} with Content-Type: application/json; Write unit test with 4 vectors: 299s past (pass), 301s past (fail), 300s future (pass), 301s future (fail)
**Deliverable:** services/api-gateway/src/main/java/com/gme/pay/gateway/filter/TimestampValidationFilter.java
**Acceptance / logic checks:**
- X-Timestamp 299s in past: request passes filter
- X-Timestamp 301s in past: returns 401 TIMESTAMP_OUT_OF_RANGE
- X-Timestamp 301s in future: returns 401
- Missing X-Timestamp: returns 401
- Error body includes request_id matching X-Request-ID response header
**Depends on:** 8.1-T03

### 8.1-T05 — Implement HMAC-SHA256 request signature verification filter  _(45 min)_
**Context:** API-05 section 3.2: every request must carry X-API-Key, X-Timestamp, X-Signature. Canonical string = HTTP_METHOD + newline + PATH_WITH_QUERY + newline + X-Timestamp value + newline + SHA256_HEX_OF_BODY. HMAC-SHA256(canonical_string, api_secret) must equal X-Signature (hex). For GET/DELETE with no body, SHA-256 of empty string is the body hash. Error INVALID_API_KEY (401) if key unknown; INVALID_SIGNATURE (401) if mismatch. Constant-time comparison required (MessageDigest.isEqual).
**Steps:** Create HmacSignatureFilter.java at services/api-gateway/src/main/java/com/gme/pay/gateway/filter/HmacSignatureFilter.java implementing GlobalFilter, Ordered (after TimestampValidationFilter); Resolve partner by X-API-Key via PartnerCredentialService (stubbed in Phase 1 via application.yml test entries); Mono.empty() -> return 401 INVALID_API_KEY; Cache request body via ServerWebExchange.mutate() + DataBufferUtils.join(); compute SHA-256 hex of body bytes (empty string for bodyless requests); Build canonical string, compute HMAC-SHA256 via javax.crypto.Mac HmacSHA256, compare with X-Signature using MessageDigest.isEqual; Write unit test with pre-computed vectors: valid signature passes, 1-byte body tamper fails, wrong secret fails
**Deliverable:** services/api-gateway/src/main/java/com/gme/pay/gateway/filter/HmacSignatureFilter.java
**Acceptance / logic checks:**
- Valid signature (known key/secret/body vector) passes filter
- Tampered body (1 byte changed) returns 401 INVALID_SIGNATURE
- Missing X-API-Key returns 401 INVALID_API_KEY
- GET with no body uses SHA-256 of empty string for body hash (unit test confirms)
- Comparison uses constant-time MessageDigest.isEqual (no timing oracle)
**Depends on:** 8.1-T04

### 8.1-T06 — Implement signature replay deduplication via Redis (10-minute window)  _(40 min)_
**Context:** API-05 section 3.6: after passing the 300s timestamp window, GMEPay+ stores (partner_id, X-Signature) in Redis for 10 minutes and rejects exact-signature duplicates. Redis key format: sig_dedup:{partner_id}:{x_signature_hex}, SET NX EX 600. Error: 401 INVALID_SIGNATURE with message 'Duplicate signature detected'. Redis is spring-boot-starter-data-redis-reactive already in the gateway module. On Redis unavailability: fail-open (configurable via gateway.replay-protection.fail-open=true, default true).
**Steps:** Create ReplayProtectionFilter.java at services/api-gateway/src/main/java/com/gme/pay/gateway/filter/ReplayProtectionFilter.java implementing GlobalFilter, Ordered (after HmacSignatureFilter); Inject ReactiveRedisTemplate<String,String>; attempt setIfAbsent(key, '1', Duration.ofSeconds(600)); If setIfAbsent returns false (key existed), return 401 INVALID_SIGNATURE body {error:{code:'INVALID_SIGNATURE',message:'Duplicate signature detected',request_id:...}}; On Redis exception: if fail-open=true log WARN and continue; if fail-open=false return 503; Write Testcontainers Redis integration test: first request passes, identical second within 600s rejected, third after TTL passes
**Deliverable:** services/api-gateway/src/main/java/com/gme/pay/gateway/filter/ReplayProtectionFilter.java
**Acceptance / logic checks:**
- First request with signature S passes
- Second request with identical (partner_id, S) within 600s returns 401 INVALID_SIGNATURE
- Redis key is sig_dedup:{partner_id}:{sig_hex} with TTL=600s (verify via Redis TTL command in test)
- Testcontainers Redis integration test passes all 3 scenarios
- fail-open=true allows request when Redis throws exception (unit test with mock Redis)
**Depends on:** 8.1-T05

### 8.1-T07 — Implement per-partner rate limiting via Redis RequestRateLimiter filter  _(45 min)_
**Context:** API-05 section 3.5 rate limits: 100 req/s per partner (global), 20 req/s for POST /v1/rates, 50 req/s for POST /v1/payments and POST /v1/payments/cpm/generate. HTTP 429 RATE_LIMITED with Retry-After header. Responses include X-RateLimit-Limit, X-RateLimit-Remaining, X-RateLimit-Reset. Spring Cloud Gateway's built-in RequestRateLimiter filter backed by Redis uses RedisRateLimiter with replenishRate and burstCapacity. KeyResolver extracts partner_id from resolved credential placed in exchange attributes by HmacSignatureFilter.
**Steps:** Configure RedisRateLimiter beans in GatewayRoutingConfig.java: globalRateLimiter(100,100), ratesRateLimiter(20,20), paymentsRateLimiter(50,50); Define KeyResolver bean: exchange -> Mono.justOrEmpty(exchange.getAttribute('partner_id')); Apply globalRateLimiter to all routes; override with specific limiters on /v1/rates and /v1/payments routes; Create RateLimitResponseFilter.java to add X-RateLimit-Limit, X-RateLimit-Remaining, X-RateLimit-Reset response headers from Spring's internal rate-limit result headers; Write Testcontainers Redis test: send 21 sequential requests to /v1/rates from same partner; assert 21st returns 429 with Retry-After header
**Deliverable:** Updated GatewayRoutingConfig.java with rate limiter beans; services/api-gateway/src/main/java/com/gme/pay/gateway/filter/RateLimitResponseFilter.java
**Acceptance / logic checks:**
- 21st POST /v1/rates within 1s from same partner returns 429 RATE_LIMITED
- Response on 429 includes Retry-After header
- All responses include X-RateLimit-Limit, X-RateLimit-Remaining, X-RateLimit-Reset
- Different partners have independent counters (partner A limit does not block partner B)
- Testcontainers Redis test passes for 20-req/s POST /v1/rates scenario
**Depends on:** 8.1-T06

### 8.1-T08 — Implement Idempotency-Key presence and format enforcement for POST endpoints  _(30 min)_
**Context:** API-05 section 2.6: all POST requests must include Idempotency-Key header (UUID, min 16 chars, max 128 chars). Missing key returns HTTP 400 MISSING_IDEMPOTENCY_KEY. Invalid length returns HTTP 400 VALIDATION_ERROR with details field=Idempotency-Key. This is a gateway-level guard only — actual idempotency storage (partner_id + key -> response, 24h TTL) is handled per downstream service. GET requests are not affected.
**Steps:** Create IdempotencyKeyFilter.java at services/api-gateway/src/main/java/com/gme/pay/gateway/filter/IdempotencyKeyFilter.java implementing GlobalFilter, Ordered; Apply only when exchange.getRequest().getMethod() == HttpMethod.POST; If Idempotency-Key header absent: return 400 MISSING_IDEMPOTENCY_KEY; If Idempotency-Key length < 16 or > 128: return 400 VALIDATION_ERROR with details=[{field:'Idempotency-Key',issue:'length must be 16-128 chars'}]; Write unit test: absent key->400 MISSING_IDEMPOTENCY_KEY, 15-char->400 VALIDATION_ERROR, 16-char->pass, 128-char->pass, 129-char->400, GET with no key->pass
**Deliverable:** services/api-gateway/src/main/java/com/gme/pay/gateway/filter/IdempotencyKeyFilter.java
**Acceptance / logic checks:**
- POST without Idempotency-Key returns 400 MISSING_IDEMPOTENCY_KEY
- POST with 15-char key returns 400 VALIDATION_ERROR
- POST with valid 36-char UUID passes
- GET without Idempotency-Key passes (filter skipped)
- Unit test covers all boundary lengths: 15, 16, 128, 129
**Depends on:** 8.1-T03

### 8.1-T09 — Implement Content-Type enforcement filter for POST/PUT requests  _(20 min)_
**Context:** API-05 section 2.3: all requests with a body must use Content-Type: application/json; charset=utf-8. The gateway rejects POST/PUT where Content-Type is absent or not application/json (charset suffix ignored for comparison). Error: HTTP 400 VALIDATION_ERROR with details [{field:'Content-Type',issue:'must be application/json'}]. This prevents non-JSON payloads reaching downstream services.
**Steps:** Create ContentTypeFilter.java at services/api-gateway/src/main/java/com/gme/pay/gateway/filter/ContentTypeFilter.java implementing GlobalFilter; Apply only to POST and PUT methods; Extract MediaType from Content-Type header; if not MediaType.APPLICATION_JSON_compatible return 400 VALIDATION_ERROR; Error body: {error:{code:'VALIDATION_ERROR',message:'Content-Type must be application/json',details:[{field:'Content-Type',issue:'must be application/json'}],request_id:...}}; Write unit test: POST application/json passes, POST application/json;charset=utf-8 passes, POST application/xml fails, POST missing header fails, GET missing header passes
**Deliverable:** services/api-gateway/src/main/java/com/gme/pay/gateway/filter/ContentTypeFilter.java
**Acceptance / logic checks:**
- POST with Content-Type: application/json passes
- POST with Content-Type: application/json;charset=utf-8 passes
- POST with Content-Type: application/xml returns 400 VALIDATION_ERROR
- POST without Content-Type returns 400
- GET without Content-Type passes (not checked)
**Depends on:** 8.1-T03

### 8.1-T10 — Implement canonical error envelope for all gateway-level rejections  _(35 min)_
**Context:** API-05 section 8.1: all error responses use {error:{code,message,request_id,details:[]}}. Every gateway filter that rejects a request must produce this envelope with Content-Type: application/json. The error.request_id must match the X-Request-ID response header (set by RequestIdFilter). This ticket creates a GatewayErrorWriter utility used by all filters and an ErrorWebExceptionHandler to catch unhandled exceptions.
**Steps:** Create GatewayErrorWriter.java at services/api-gateway/src/main/java/com/gme/pay/gateway/filter/GatewayErrorWriter.java with static method writeError(ServerWebExchange, HttpStatus, ApiErrorCode, String) -> Mono<Void> that serializes {error:{code,message,request_id,details:[]}} and sets Content-Type: application/json; Ensure request_id is read from exchange response headers (set by RequestIdFilter) or generated if missing; Register GatewayGlobalExceptionHandler.java implementing ErrorWebExceptionHandler (Ordered.HIGHEST_PRECEDENCE-1) to catch Throwable, map to 500 INTERNAL_ERROR envelope; Update all existing filters (T04, T05, T06, T08, T09) to use GatewayErrorWriter instead of ad-hoc response writes; Write integration test: trigger each rejection type, assert envelope fields {error.code, error.request_id, Content-Type}
**Deliverable:** services/api-gateway/src/main/java/com/gme/pay/gateway/filter/GatewayErrorWriter.java and GatewayGlobalExceptionHandler.java
**Acceptance / logic checks:**
- All 4xx/5xx gateway responses contain {error:{code,message,request_id,details[]}} shape
- Content-Type is application/json on all error responses
- error.request_id matches X-Request-ID response header
- Unhandled exception returns 500 INTERNAL_ERROR (not Spring Whitelabel error)
- Integration test verifies 401, 400, 429, 403 envelopes are correct
**Depends on:** 8.1-T04, 8.1-T05, 8.1-T07, 8.1-T08, 8.1-T09

### 8.1-T11 — Implement IP allowlist enforcement filter  _(35 min)_
**Context:** API-05 section 3.3: partners may register up to 10 source IP CIDR ranges. When an allowlist is configured for a partner, requests from unlisted IPs are rejected 403 IP_NOT_ALLOWLISTED before signature verification. Partner IP config is loaded from PostgreSQL config_registry via PartnerCredentialService (8.1-T18). IP check must run before HMAC filter. Remote address extracted from ServerWebExchange.getRequest().getRemoteAddress(); X-Forwarded-For honoured when gateway.trust-proxy=true.
**Steps:** Create IpAllowlistFilter.java at services/api-gateway/src/main/java/com/gme/pay/gateway/filter/IpAllowlistFilter.java implementing GlobalFilter, Ordered with order before HmacSignatureFilter; Resolve partner from X-API-Key header; if key unknown skip (HMAC filter handles that); If partner.ipCidrRanges is non-empty, parse each CIDR with com.google.guava SubnetUtils or Apache Commons Net SubnetUtils; check remoteIp; If source IP not in any CIDR, return 403 IP_NOT_ALLOWLISTED via GatewayErrorWriter; Write unit test: partner allowlist 192.168.1.0/24 — 192.168.1.5 passes, 10.0.0.1 returns 403, no allowlist configured passes any IP
**Deliverable:** services/api-gateway/src/main/java/com/gme/pay/gateway/filter/IpAllowlistFilter.java
**Acceptance / logic checks:**
- Partner with allowlist: IP in /24 range passes
- Partner with allowlist: IP outside range returns 403 IP_NOT_ALLOWLISTED
- Partner with no allowlist: all IPs pass
- X-Forwarded-For header honoured when gateway.trust-proxy=true
- Unit test covers /24, /32, and no-allowlist scenarios
**Depends on:** 8.1-T05

### 8.1-T12 — Define canonical pagination model in lib-api-contracts  _(30 min)_
**Context:** API-05 section 2.8: list endpoints use cursor-based pagination. Query params: limit (default 20, max 100), after (next_cursor from prior page), before (prev_cursor). Response envelope: {data:[], meta:{total_count, has_more, next_cursor, prev_cursor}}. This model lives in lib-api-contracts so all services use a consistent implementation. It is not gateway-specific but is foundational for all list endpoints built in subsequent WBS items.
**Steps:** Create PaginationParams.java at lib-api-contracts/src/main/java/com/gme/pay/api/pagination/PaginationParams.java as a Java record: int limit, String after (nullable), String before (nullable); Add static factory PaginationParams.from(MultiValueMap<String,String> queryParams): parse limit (clamp 1-100, default 20), after, before; Create PaginationMeta.java record: long totalCount, boolean hasMore, String nextCursor (nullable), String prevCursor (nullable); Create PagedResponse<T>.java record: List<T> data, PaginationMeta meta; Jackson serializes keys as total_count, has_more, next_cursor, prev_cursor; Write PaginationParamsTest.java: limit absent->20, limit=0->1, limit=101->100, limit=50->50, after/before null when absent
**Deliverable:** lib-api-contracts/src/main/java/com/gme/pay/api/pagination/PaginationParams.java, PaginationMeta.java, PagedResponse.java
**Acceptance / logic checks:**
- PaginationParams.from({}) returns limit=20, after=null, before=null
- PaginationParams.from({limit:0}) returns limit=1
- PaginationParams.from({limit:101}) returns limit=100
- PagedResponse<String> serializes to {data:[],meta:{total_count:0,has_more:false,next_cursor:null,prev_cursor:null}}
- Unit test PaginationParamsTest covers all 5 boundary cases

### 8.1-T13 — Define canonical error model with all 24 API-05 error codes in lib-errors  _(30 min)_
**Context:** API-05 section 8.2 defines 24 error codes each with HTTP status and retryable flag. lib-errors is shared across all services. The error envelope is {error:{code,message,request_id,details:[{field,issue}]}}. This ticket creates the enum and response model used by the gateway (T10) and all downstream services.
**Steps:** Create ApiErrorCode.java enum at lib-errors/src/main/java/com/gme/pay/errors/ApiErrorCode.java with all 24 codes: VALIDATION_ERROR(400), MISSING_IDEMPOTENCY_KEY(400), INVALID_SIGNATURE(401), INVALID_API_KEY(401), TIMESTAMP_OUT_OF_RANGE(401), FORBIDDEN(403), IP_NOT_ALLOWLISTED(403), PAYMENT_NOT_FOUND(404), MERCHANT_NOT_FOUND(404), SCHEME_NOT_FOUND(404), IDEMPOTENCY_KEY_REUSE(422), RATE_QUOTE_EXPIRED(422), RATE_QUOTE_INVALID(422), PARTNER_B_QUOTE_DEVIATION(422), PARTNER_B_QUOTE_UNAVAILABLE(422), SCHEME_UNAVAILABLE(422), PAYMENT_MODE_NOT_SUPPORTED(422), DIRECTION_NOT_ENABLED(422), CANCEL_NOT_PERMITTED(400), INSUFFICIENT_PREFUNDING(402), DUPLICATE_PARTNER_TXN_REF(409), RATE_LIMITED(429), INTERNAL_ERROR(500), SERVICE_UNAVAILABLE(503); Add fields to enum: int httpStatus, boolean retryable; add getHttpStatus() and isRetryable() methods; Create ApiErrorDetail.java record: String field, String issue; Create ApiError.java record: ApiErrorCode code, String message, String requestId, List<ApiErrorDetail> details; @JsonProperty annotations for camelCase -> snake_case; Create ApiErrorResponse.java record: ApiError error (top-level wrapper); write unit test confirming Jackson serialization produces API-05 shape
**Deliverable:** lib-errors/src/main/java/com/gme/pay/errors/ApiErrorCode.java, ApiError.java, ApiErrorDetail.java, ApiErrorResponse.java
**Acceptance / logic checks:**
- All 24 error codes present in ApiErrorCode enum with correct HTTP status
- RATE_QUOTE_EXPIRED.getHttpStatus()==422, RATE_QUOTE_EXPIRED.isRetryable()==false
- RATE_LIMITED.isRetryable()==true, SERVICE_UNAVAILABLE.isRetryable()==true
- ApiErrorResponse JSON output is {error:{code:'RATE_QUOTE_EXPIRED',message:...,request_id:...,details:[]}}
- Unit test ApiErrorResponseTest.java asserts serialization of all fields including empty details

### 8.1-T14 — Implement API version deprecation and Sunset headers filter  _(30 min)_
**Context:** API-05 section 11.3: when a version is deprecated, responses include Deprecation: true and Sunset: <RFC-1123 date>. After sunset date, requests to that version return HTTP 410 Gone. In Phase 1, /v1 is not deprecated but the mechanism must exist. Config: api.versions.v1.deprecated=false, api.versions.v1.sunset-date (empty = no header). The VersionHeaderFilter adds headers; a VersionEnforcementFilter returns 410 if today > sunset date.
**Steps:** Create ApiVersionProperties.java at services/api-gateway/src/main/java/com/gme/pay/gateway/config/ApiVersionProperties.java: @ConfigurationProperties('api.versions'), Map<String,VersionConfig> where VersionConfig has boolean deprecated, LocalDate sunsetDate (nullable); Create VersionEnforcementFilter.java: extract version from first path segment (/v1 -> '1'), look up config, if sunsetDate != null and LocalDate.now().isAfter(sunsetDate) return 410 Gone with body {error:{code:'GONE',message:'API version /v1 retired on <date>. Upgrade to /v2.',request_id:...}}; Create VersionHeaderFilter.java: if deprecated=true add response headers Deprecation: true and Sunset: <RFC-1123 format of sunsetDate>; Write unit test: sunsetDate in past -> 410, sunsetDate in future -> pass + Deprecation header, no sunsetDate -> no headers, v1 default (deprecated=false) -> no headers
**Deliverable:** services/api-gateway/src/main/java/com/gme/pay/gateway/filter/VersionEnforcementFilter.java, VersionHeaderFilter.java, config/ApiVersionProperties.java
**Acceptance / logic checks:**
- api.versions.v1.deprecated=false (default): no Deprecation or Sunset headers emitted
- api.versions.v1.deprecated=true + sunset-date=2026-10-31: response includes Deprecation: true and Sunset: Sat, 31 Oct 2026 23:59:59 GMT
- Path /v1/rates with past sunset-date returns 410 Gone with error code GONE
- Unit test covers past/future/null sunset-date
- Sunset header format is RFC-1123 per API-05 section 11.3
**Depends on:** 8.1-T10

### 8.1-T15 — Implement mTLS optional onboarding configuration at gateway  _(40 min)_
**Context:** API-05 section 3.4: partners may request mTLS. GME provisions a client cert per partner. When mTLS active, X-Signature is still required. mTLS is configured at gateway level (no endpoint changes). Spring Cloud Gateway uses Netty; server.ssl.client-auth=WANT allows optional client cert. A dedicated Spring profile 'mtls' enables it. Client cert Subject DN is forwarded as X-Client-Cert-DN header for downstream audit.
**Steps:** Create application-mtls.yml at services/api-gateway/src/main/resources/application-mtls.yml with server.ssl.enabled=true, server.ssl.client-auth=WANT, server.ssl.key-store=${GATEWAY_KEYSTORE_PATH}, server.ssl.trust-store=${GATEWAY_TRUSTSTORE_PATH}; Create MtlsCertFilter.java at services/api-gateway/src/main/java/com/gme/pay/gateway/filter/MtlsCertFilter.java: if client cert present in SslInfo, extract Subject CN and add X-Client-Cert-DN header to downstream request; No certificate-to-partner mapping in this ticket; mapping validated by IpAllowlistFilter extension (future); Ensure without mtls profile gateway starts normally with plain TLS (or no TLS in local dev); Write test: with mtls profile and test self-signed cert, X-Client-Cert-DN header is set; without cert the request still proceeds (WANT not NEED)
**Deliverable:** services/api-gateway/src/main/resources/application-mtls.yml and services/api-gateway/src/main/java/com/gme/pay/gateway/filter/MtlsCertFilter.java
**Acceptance / logic checks:**
- With mtls profile: request without client cert is accepted (WANT behaviour)
- With mtls profile: request with valid cert adds X-Client-Cert-DN header
- Without mtls profile: gateway starts without SSL config (local dev mode)
- GATEWAY_KEYSTORE_PATH and GATEWAY_TRUSTSTORE_PATH env vars control cert paths
- No cert paths or secrets hard-coded in code or application-mtls.yml
**Depends on:** 8.1-T01

### 8.1-T16 — Add OpenTelemetry tracing instrumentation to gateway  _(35 min)_
**Context:** STACK.md requires OpenTelemetry traces -> Jaeger. The gateway is the trace origin for all partner requests. Each request span must carry attributes: request_id, partner_id, http.method, http.route, http.status_code. W3C traceparent/tracestate headers propagate trace context to downstream services. OTEL_EXPORTER_OTLP_ENDPOINT env var configures exporter endpoint (default http://localhost:4317).
**Steps:** Add io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter to services/api-gateway/build.gradle; Set otel.service.name=api-gateway and otel.exporter.otlp.endpoint=${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4317} in application.yml; Create TracingContextFilter.java at services/api-gateway/src/main/java/com/gme/pay/gateway/filter/TracingContextFilter.java implementing GlobalFilter: add span attributes Span.current().setAttribute('request_id', ...) and setAttribute('partner_id', exchange.getAttribute('partner_id')); Verify W3C traceparent header is forwarded to downstream (auto-propagation by Spring OTel); Write unit test asserting TracingContextFilter sets request_id and partner_id as span attributes using OpenTelemetry Testing SDK
**Deliverable:** services/api-gateway/src/main/java/com/gme/pay/gateway/filter/TracingContextFilter.java and updated build.gradle
**Acceptance / logic checks:**
- Span attributes include request_id, partner_id after filter executes (unit test)
- traceparent header is present on forwarded downstream request
- OTEL_EXPORTER_OTLP_ENDPOINT overrides default endpoint
- Span service.name tag is api-gateway
- ./gradlew :services:api-gateway:test passes
**Depends on:** 8.1-T03

### 8.1-T17 — Implement structured JSON access logging filter  _(35 min)_
**Context:** SAD-02 requires structured JSON logs with correlation fields: request_id, partner_id, scheme_id, http.method, path, status, latency_ms. The gateway access log is the primary audit trail for all partner API calls. Uses SLF4J + Logback with logstash-logback-encoder. Log level: INFO for 2xx, WARN for 4xx, ERROR for 5xx. Partner_id is stored in Reactor context after authentication.
**Steps:** Add net.logstash.logback:logstash-logback-encoder to services/api-gateway/build.gradle; Create logback-spring.xml at services/api-gateway/src/main/resources/logback-spring.xml: use JsonEncoder for non-local Spring profiles, ConsoleAppender for local; Create AccessLogFilter.java at services/api-gateway/src/main/java/com/gme/pay/gateway/filter/AccessLogFilter.java implementing GlobalFilter, Ordered(Ordered.LOWEST_PRECEDENCE): record startTime, after response complete emit log with MDC fields: request_id, partner_id, method, path, status_code, latency_ms; Use MDCContext from Reactor Context to propagate partner_id through reactive chain; Write unit test asserting MDC fields are populated and log level matches response status
**Deliverable:** services/api-gateway/src/main/java/com/gme/pay/gateway/filter/AccessLogFilter.java and src/main/resources/logback-spring.xml
**Acceptance / logic checks:**
- Log entry contains fields: request_id, partner_id, method, path, status_code, latency_ms
- Log level is INFO for 200, WARN for 401, ERROR for 500
- JSON log format active on non-local Spring profile (logstash encoder)
- partner_id is non-null after successful authentication
- Unit test verifies MDC field population and log level
**Depends on:** 8.1-T03, 8.1-T16

### 8.1-T18 — Implement PartnerCredentialService with PostgreSQL + Redis cache  _(45 min)_
**Context:** Gateway filters (HMAC T05, IP allowlist T11, rate limit T07) need to resolve partner credentials by X-API-Key: partner_id, api_secret_hmac_key, ip_cidr_ranges, partner_type (LOCAL|OVERSEAS), rate_quote_ttl_seconds. Credentials live in PostgreSQL config_registry database (table partner_credentials, migration in T19). Redis caches responses (key: partner_cred:{apiKey}, TTL 60s). Uses Spring Data R2DBC for reactive DB access.
**Steps:** Create PartnerCredentials.java record at services/api-gateway/src/main/java/com/gme/pay/gateway/partner/PartnerCredentials.java: String partnerId, String apiKeyHash, String apiSecretHmacKey, List<String> ipCidrRanges, PartnerType type, int rateQuoteTtlSeconds; enum PartnerType {LOCAL, OVERSEAS}; Create PartnerCredentialService.java interface: Mono<PartnerCredentials> findByApiKey(String apiKey); Implement PostgresPartnerCredentialService.java using DatabaseClient (R2DBC) querying partner_credentials table in config-registry datasource; map columns to record; Wrap with Redis cache: on miss query DB, on hit return cached; TTL 60s; key=partner_cred:{apiKey}; Write Testcontainers PostgreSQL 16 integration test: insert row, call findByApiKey, assert all fields, second call within 60s served from Redis (mock DB client verifies 0 queries on 2nd call)
**Deliverable:** services/api-gateway/src/main/java/com/gme/pay/gateway/partner/PartnerCredentialService.java, PartnerCredentials.java, PostgresPartnerCredentialService.java
**Acceptance / logic checks:**
- findByApiKey returns correct PartnerCredentials from PostgreSQL (Testcontainers test)
- Unknown API key returns Mono.empty()
- Second call within 60s served from Redis cache with 0 DB queries
- PartnerCredentials.type enum is LOCAL or OVERSEAS
- Testcontainers test inserts row with ip_cidr_ranges={'192.168.1.0/24'} and asserts list deserialized correctly
**Depends on:** 8.1-T05, 8.1-T11

### 8.1-T19 — Write Flyway migration V001 for partner_credentials table in config-registry  _(30 min)_
**Context:** PartnerCredentialService (T18) reads from PostgreSQL table partner_credentials in the config-registry service database. Flyway manages migrations for services/config-registry. Fields: id UUID PK default gen_random_uuid(), partner_id VARCHAR(64) UNIQUE NOT NULL, api_key VARCHAR(128) UNIQUE NOT NULL, api_secret_bcrypt VARCHAR(256) NOT NULL (bcrypt cost >= 12 per API-05 section 3.1), ip_cidr_ranges TEXT[] nullable, partner_type VARCHAR(16) NOT NULL CHECK IN ('LOCAL','OVERSEAS'), rate_quote_ttl_seconds INTEGER NOT NULL DEFAULT 300, webhook_url VARCHAR(512), created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW().
**Steps:** Identify Flyway migration directory: services/config-registry/src/main/resources/db/migration/; Create V001__create_partner_credentials.sql with CREATE TABLE partner_credentials (all columns above); Add UNIQUE INDEX idx_partner_credentials_api_key ON partner_credentials(api_key); Add partial index idx_partner_credentials_partner_id ON partner_credentials(partner_id) WHERE partner_id IS NOT NULL; Write Testcontainers migration test PartnerCredentialsMigrationTest.java: apply migration to fresh PostgreSQL 16, assert table exists with all columns, insert row, verify UNIQUE and CHECK constraints
**Deliverable:** services/config-registry/src/main/resources/db/migration/V001__create_partner_credentials.sql
**Acceptance / logic checks:**
- Migration applies cleanly to fresh PostgreSQL 16 (Testcontainers)
- Table has all 10 columns including webhook_url and ip_cidr_ranges TEXT[]
- UNIQUE constraint on api_key: insert duplicate key fails with unique violation
- partner_type CHECK rejects value 'ADMIN' (only LOCAL and OVERSEAS allowed)
- rate_quote_ttl_seconds DEFAULT is 300 (insert row without specifying it, verify default)

### 8.1-T20 — Configure all 7 API-05 endpoint routes with full security filter chain  _(45 min)_
**Context:** API-05 section 4.1 defines 7 endpoints: POST /v1/rates->lb://rate-fx, POST /v1/payments->lb://payment-executor, POST /v1/payments/cpm/generate->lb://payment-executor, GET /v1/payments/{id}->lb://payment-executor, POST /v1/payments/{id}/cancel->lb://payment-executor, GET /v1/merchants/{qr}->lb://merchant-qr-data, GET /v1/balance->lb://prefunding-balance. Each route applies the full filter chain. Connect timeout 2s, response timeout 10s. Resilience4j CircuitBreaker fallback: 503 SERVICE_UNAVAILABLE.
**Steps:** Extend GatewayRoutingConfig.java with all 7 routes using RouteLocatorBuilder fluent DSL; Apply per-route filters: TimestampValidationFilter, HmacSignatureFilter, ReplayProtectionFilter, per-endpoint RedisRateLimiter, IdempotencyKeyFilter (POST only), ContentTypeFilter (POST only), VersionEnforcementFilter; Configure connect timeout 2s and response timeout 10s via HttpClient metadata in route config; Add CircuitBreaker GatewayFilter per route using Resilience4j: fallback to inline response 503 SERVICE_UNAVAILABLE; Write WireMock integration test: stub lb://rate-fx, verify POST /v1/rates with valid signed request reaches WireMock stub and X-Gateway-Version: v1 is present
**Deliverable:** Updated services/api-gateway/src/main/java/com/gme/pay/gateway/config/GatewayRoutingConfig.java with all 7 routes and circuit breakers
**Acceptance / logic checks:**
- POST /v1/rates routes to lb://rate-fx (WireMock test with signed request)
- GET /v1/balance routes to lb://prefunding-balance
- Circuit breaker opens after 5 consecutive 503s and returns 503 SERVICE_UNAVAILABLE without hitting downstream
- Response timeout of 10s: WireMock stub with 11s delay triggers timeout and returns 504 or 503
- All 7 route IDs are unique and non-overlapping (no accidental catch-all overlap)
**Depends on:** 8.1-T07, 8.1-T08, 8.1-T09, 8.1-T10, 8.1-T11, 8.1-T14

### 8.1-T21 — Define filter execution order and write filter chain order unit test  _(35 min)_
**Context:** The gateway has multiple GlobalFilters that must execute in a defined order to avoid security bypass: (1) RequestIdFilter, (2) IpAllowlistFilter, (3) TimestampValidationFilter, (4) HmacSignatureFilter, (5) ReplayProtectionFilter, (6) RateLimitResponseFilter, (7) IdempotencyKeyFilter, (8) ContentTypeFilter, (9) VersionEnforcementFilter, (10) VersionHeaderFilter, (11) TracingContextFilter, (12) AccessLogFilter. Wrong order can let replayed requests bypass signature check.
**Steps:** Verify each filter implements Ordered and returns a unique getOrder() value matching the priority list above; Create FilterOrderTest.java at services/api-gateway/src/test/java/com/gme/pay/gateway/FilterOrderTest.java; Assert each filter bean's getOrder() equals expected value using @SpringBootTest and ApplicationContext; Write scenario test: mock TimestampValidationFilter to return 401; assert HmacSignatureFilter (higher order number) is NOT invoked (verify with mock spy); Write scenario test: valid timestamp + invalid signature -> HmacSignatureFilter runs and returns 401 before ReplayProtectionFilter
**Deliverable:** services/api-gateway/src/test/java/com/gme/pay/gateway/FilterOrderTest.java
**Acceptance / logic checks:**
- Each filter's getOrder() is unique and matches specified priority
- IpAllowlistFilter order < HmacSignatureFilter order (IP checked before sig)
- TimestampValidationFilter order < HmacSignatureFilter order
- ReplayProtectionFilter order > HmacSignatureFilter order (replay check after sig passes)
- Scenario test confirms TimestampValidationFilter short-circuits chain (HMAC filter not invoked)
**Depends on:** 8.1-T04, 8.1-T05, 8.1-T06, 8.1-T07, 8.1-T08, 8.1-T09, 8.1-T10, 8.1-T11

### 8.1-T22 — Write gateway security integration test suite (Testcontainers + WireMock)  _(50 min)_
**Context:** All gateway security filters must be integration-tested together against real Redis (Testcontainers) and WireMock downstream. Covers API-05 certification checklist items: C-15 (invalid signature -> 401), C-16 (idempotent retry same key+body), replay dedup, rate limit breach, missing idempotency key. Test partner: api_key=pk_test_abc, api_secret=sk_test_xyz, type=OVERSEAS, no IP allowlist — loaded via test PartnerCredentialService stub.
**Steps:** Create GatewaySecurityIntegrationTest.java at services/api-gateway/src/test/java/com/gme/pay/gateway/GatewaySecurityIntegrationTest.java using @SpringBootTest(webEnvironment=RANDOM_PORT); Use @Testcontainers with Redis GenericContainer and WireMock for lb://rate-fx stub; Test C-15: POST /v1/rates with invalid X-Signature -> 401 INVALID_SIGNATURE, error envelope correct; Test replay: identical (partner_id, X-Signature) on two sequential requests -> second returns 401 INVALID_SIGNATURE; Test rate limit: send 21 POST /v1/rates within 1s -> 21st returns 429 RATE_LIMITED with Retry-After; Test missing idempotency key: POST /v1/rates without Idempotency-Key -> 400 MISSING_IDEMPOTENCY_KEY; Test valid full flow: correctly signed POST /v1/rates reaches WireMock stub and returns stubbed 200 response
**Deliverable:** services/api-gateway/src/test/java/com/gme/pay/gateway/GatewaySecurityIntegrationTest.java
**Acceptance / logic checks:**
- C-15: invalid signature -> 401 INVALID_SIGNATURE (test passes in CI)
- Replay: 2nd identical signature within 10min -> 401 (test passes)
- Rate limit: 21st request -> 429 with Retry-After (test passes)
- Missing Idempotency-Key -> 400 MISSING_IDEMPOTENCY_KEY (test passes)
- Valid signed request reaches WireMock stub and response is proxied correctly (test passes)
**Depends on:** 8.1-T20, 8.1-T21

### 8.1-T23 — Add Prometheus metrics filter for per-route request telemetry  _(30 min)_
**Context:** STACK.md requires Prometheus + Grafana for metrics. The gateway must expose per-route request metrics: http.server.requests counter (by route_id, method, status), gateway.request.duration histogram (by route_id, method). Custom tag partner_id enables per-partner SLA monitoring. Micrometer auto-instruments Spring WebFlux but a custom GatewayMetricsFilter adds the partner_id and route_id tags that Micrometer's auto-instrumentation does not capture.
**Steps:** Confirm micrometer-registry-prometheus on classpath (added in T01); Configure management.metrics.tags.application=api-gateway in application.yml; Create GatewayMetricsFilter.java at services/api-gateway/src/main/java/com/gme/pay/gateway/filter/GatewayMetricsFilter.java: record Timer with tags route_id, method, status, partner_id using MeterRegistry; Use Timer.builder('gateway.request.duration').tags(...).register(meterRegistry).record(duration); Write unit test asserting Timer is registered with expected tag values after a mock exchange is processed
**Deliverable:** services/api-gateway/src/main/java/com/gme/pay/gateway/filter/GatewayMetricsFilter.java
**Acceptance / logic checks:**
- GET /actuator/prometheus returns gateway_request_duration_seconds histogram metric
- Metric has tags: route_id, method, status, partner_id
- p99 latency observable via histogram_quantile in Prometheus
- Unit test asserts timer.record called with correct tags
- Metric names follow snake_case Prometheus conventions
**Depends on:** 8.1-T17

### 8.1-T24 — Define gateway-level conventions in openapi/partner-api.yaml (skeleton)  _(40 min)_
**Context:** STACK.md requires contract tests against openapi/partner-api.yaml. This skeleton covers gateway conventions only: common request headers (X-API-Key, X-Timestamp, X-Signature, Idempotency-Key, Content-Type), common response headers (X-Request-ID, X-RateLimit-Limit, X-RateLimit-Remaining, X-RateLimit-Reset, Deprecation, Sunset), and the error envelope schema matching lib-errors ApiErrorResponse. Endpoint paths are added by subsequent WBS tickets.
**Steps:** Create openapi/partner-api.yaml at repo root with openapi: 3.1.0, info: {title: 'GMEPay+ Partner API', version: '1.0.0'}; Add servers: [{url: 'https://api-sandbox.gmepayplus.com', description: Sandbox}, {url: 'https://api.gmepayplus.com', description: Production}]; Define components.securitySchemes: ApiKeyAuth (apiKey, in: header, name: X-API-Key), HmacSignature (apiKey, in: header, name: X-Signature); Define components.headers: X-Request-ID (string), X-RateLimit-Limit, X-RateLimit-Remaining, X-RateLimit-Reset (integer), Deprecation (boolean), Sunset (string); Define components.schemas: ApiErrorDetail {field, issue}, ApiError {code, message, request_id, details[]}, ApiErrorResponse {error: ApiError}, PaginationMeta {total_count, has_more, next_cursor, prev_cursor}; run redocly lint or swagger-parser to confirm no errors
**Deliverable:** openapi/partner-api.yaml (conventions skeleton, no endpoint paths yet)
**Acceptance / logic checks:**
- openapi/partner-api.yaml passes redocly lint or swagger-parser validation with zero errors
- components.securitySchemes includes ApiKeyAuth and HmacSignature
- ApiError schema shape matches API-05 section 8.1: {code, message, request_id, details[{field,issue}]}
- PaginationMeta fields are total_count, has_more, next_cursor, prev_cursor (snake_case)
- All 6 common response headers are defined in components.headers
**Depends on:** 8.1-T12, 8.1-T13

### 8.1-T25 — Write health and readiness probes for gateway with config DB indicator  _(25 min)_
**Context:** Kubernetes readiness probe checks that Redis and PostgreSQL config DB are reachable before gateway accepts traffic. Spring Boot Actuator with probes.enabled=true exposes /actuator/health/liveness and /actuator/health/readiness. Liveness: JVM alive. Readiness: Redis + PostgreSQL config DB. A custom PartnerConfigHealthIndicator executes SELECT 1 on the config R2DBC datasource.
**Steps:** Set management.endpoint.health.probes.enabled=true and management.health.redis.enabled=true in application.yml; Create PartnerConfigHealthIndicator.java at services/api-gateway/src/main/java/com/gme/pay/gateway/health/PartnerConfigHealthIndicator.java implementing ReactiveHealthIndicator: execute DatabaseClient.sql('SELECT 1').fetch().rowsUpdated(), return Health.up() on success or Health.down(exception) on error; Register as @Component with @ConditionalOnProperty allowing it to be disabled in tests; Ensure /actuator/health, /actuator/health/liveness, /actuator/health/readiness paths are not behind security filter chain; Write unit test: DB reachable -> Health.UP, DB throws exception -> Health.DOWN with exception detail
**Deliverable:** services/api-gateway/src/main/java/com/gme/pay/gateway/health/PartnerConfigHealthIndicator.java
**Acceptance / logic checks:**
- GET /actuator/health returns {status:UP} when Redis and DB are reachable
- GET /actuator/health/readiness returns {status:OUT_OF_SERVICE} when Redis is down (Testcontainers: kill Redis)
- GET /actuator/health/liveness returns {status:UP} regardless of Redis/DB state
- PartnerConfigHealthIndicator returns Health.DOWN when DB unreachable (unit test with mock throwing exception)
- Health endpoints accessible without X-API-Key or authentication headers
**Depends on:** 8.1-T01, 8.1-T18

### 8.1-T26 — Write contract tests for gateway against openapi/partner-api.yaml  _(40 min)_
**Context:** STACK.md requires contract tests against openapi/partner-api.yaml. The gateway enforces the API contract at entry: required headers (X-API-Key, X-Timestamp, X-Signature, Content-Type, Idempotency-Key on POST), response envelope shape ({error:{code,message,request_id,details}}), and standard response headers (X-Request-ID, X-RateLimit-*). Spring REST Docs or a swagger-request-validator can assert request/response shapes against the OpenAPI spec.
**Steps:** Add com.atlassian.oai:swagger-request-validator-restassured (or equivalent) to services/api-gateway/build.gradle (test scope); Create GatewayContractTest.java at services/api-gateway/src/test/java/com/gme/pay/gateway/GatewayContractTest.java using @SpringBootTest + RestAssured; Validate that a missing X-Signature request returns 401 with body matching ApiErrorResponse schema in openapi/partner-api.yaml; Validate that a missing Idempotency-Key POST returns 400 with body matching ApiErrorResponse schema; Validate that a valid request reaching WireMock downstream returns X-Request-ID and X-RateLimit-* headers matching declared response headers in partner-api.yaml; Run contract tests as part of ./gradlew :services:api-gateway:test
**Deliverable:** services/api-gateway/src/test/java/com/gme/pay/gateway/GatewayContractTest.java
**Acceptance / logic checks:**
- Missing X-Signature: 401 response body validates against openapi/partner-api.yaml ApiErrorResponse schema
- Missing Idempotency-Key: 400 response body validates against schema
- Valid request: X-Request-ID header present and matches declared header in partner-api.yaml
- Valid request: X-RateLimit-Limit header present
- ./gradlew :services:api-gateway:test passes with all contract tests green
**Depends on:** 8.1-T22, 8.1-T24


## WBS 8.5 — Query/cancel/merchant/balance endpoints
### 8.5-T01 — Add GetPaymentResponse DTO to lib-api-contracts for GET /v1/payments/{id}  _(25 min)_
**Context:** WBS 8.5 adds GET /v1/payments/{id}, POST /v1/payments/{id}/cancel, GET /v1/merchants/{qr}, and GET /v1/balance. lib-api-contracts (Gradle module) holds all OpenAPI-generated DTOs shared across services. GET /v1/payments/{id} returns: payment_id, status (pending|approved|failed|cancelled|uncertain), scheme_txn_id, merchant_name, merchant_id, target_payout (string), payout_currency, offer_rate (string decimal), collection_amount (string decimal), collection_currency, service_charge (string decimal), service_charge_currency, prefund_deducted_usd (string decimal), partner_txn_ref, direction, scheme_id, created_at, approved_at, updated_at, cancelled_at (nullable).
**Steps:** In lib-api-contracts/src/main/java/com/gme/pay/contracts/payment/GetPaymentResponse.java create an immutable record with all fields above typed as String for monetary amounts and Instant for timestamps.; Add status enum PaymentStatus {PENDING,APPROVED,FAILED,CANCELLED,UNCERTAIN} in com.gme.pay.contracts.payment.; Annotate with @JsonInclude(NON_NULL) so nullable fields are omitted when absent.; Add unit test GetPaymentResponseSerializationTest confirming JSON round-trip with the example values from spec.
**Deliverable:** lib-api-contracts/src/main/java/com/gme/pay/contracts/payment/GetPaymentResponse.java and PaymentStatus.java
**Acceptance / logic checks:**
- All 20 fields present with correct types.
- cancelled_at and approved_at absent from JSON when null.
- PaymentStatus.valueOf("UNCERTAIN") succeeds.
- JSON round-trip with spec example values produces identical string output.

### 8.5-T02 — Add CancelPaymentRequest and CancelPaymentResponse DTOs to lib-api-contracts  _(25 min)_
**Context:** POST /v1/payments/{id}/cancel accepts: reason (required, enum: customer_request|merchant_request|timeout|other) and reason_detail (optional, max 200 chars). Response: payment_id, status=cancelled, cancelled_at (ISO-8601 UTC), prefund_returned_usd (string decimal, OVERSEAS only, nullable). Error codes: CANCEL_NOT_PERMITTED (400), PAYMENT_NOT_FOUND (404), 409 if already terminal. The Idempotency-Key header is required on all POST endpoints (400 MISSING_IDEMPOTENCY_KEY if absent).
**Steps:** Create CancelPaymentRequest.java record with reason (CancellationReason enum) and reason_detail (nullable String, max 200 chars).; Create CancellationReason enum with values CUSTOMER_REQUEST, MERCHANT_REQUEST, TIMEOUT, OTHER.; Create CancelPaymentResponse.java record with payment_id, status (String), cancelled_at (Instant), prefund_returned_usd (nullable String).; Add @JsonInclude(NON_NULL) on CancelPaymentResponse.; Write CancelPaymentRequestValidationTest: assert @NotNull on reason and @Size(max=200) on reason_detail pass Bean Validation.
**Deliverable:** lib-api-contracts/src/main/java/com/gme/pay/contracts/payment/CancelPaymentRequest.java, CancellationReason.java, CancelPaymentResponse.java
**Acceptance / logic checks:**
- reason_detail null passes validation.
- reason_detail of 201 chars fails @Size.
- CancellationReason.from("customer_request") deserializes correctly via Jackson.
- prefund_returned_usd absent from JSON when null.
**Depends on:** 8.5-T01

### 8.5-T03 — Add MerchantDetailResponse DTO to lib-api-contracts for GET /v1/merchants/{qr}  _(20 min)_
**Context:** GET /v1/merchants/{qr} resolves a URL-encoded QR string against the local merchant MongoDB store (Merchant and QR Data service). Response fields: merchant_id, merchant_name, merchant_type, scheme_id, qr_code, status (active|inactive), payout_currency, address (nullable). 200 = found and active; 404 = MERCHANT_NOT_FOUND (QR not in DB or merchant inactive); 422 = QR found but merchant inactive or QR deactivated. The merchant store is in MongoDB (Merchant and QR Data service); reads come from CQRS read-model projections.
**Steps:** Create MerchantDetailResponse.java record with all 8 fields; address is @JsonInclude(NON_NULL).; Add MerchantStatus enum {ACTIVE, INACTIVE}.; Write MerchantDetailResponseSerializationTest confirming spec example JSON matches.
**Deliverable:** lib-api-contracts/src/main/java/com/gme/pay/contracts/merchant/MerchantDetailResponse.java, MerchantStatus.java
**Acceptance / logic checks:**
- All 8 fields serialize with spec field names (snake_case).
- address absent from JSON when null.
- MerchantStatus.ACTIVE serializes as "active" (lowercase).
- Round-trip with spec example JSON passes assertEquals.

### 8.5-T04 — Add BalanceResponse and DeductionEvent DTOs to lib-api-contracts for GET /v1/balance  _(20 min)_
**Context:** GET /v1/balance returns current prefunding balance (OVERSEAS partners only; LOCAL returns 403 FORBIDDEN). Response: partner_id, balance_usd (string decimal), low_balance_threshold_usd (string decimal), is_below_threshold (boolean), as_of (ISO-8601 UTC Instant), recent_deductions (array, only when ?include_history=true). Each deduction event: payment_id, amount_usd (string decimal; positive=deduction, negative=return for cancellation), balance_after_usd (string decimal), event_at (Instant). Prefunding stored in PostgreSQL prefunding table (NUMERIC columns, SELECT FOR UPDATE for deductions).
**Steps:** Create BalanceResponse.java record with partner_id (String), balance_usd (String), low_balance_threshold_usd (String), is_below_threshold (boolean), as_of (Instant), recent_deductions (List<DeductionEvent>, @JsonInclude(NON_NULL)).; Create DeductionEvent.java record with payment_id, amount_usd, balance_after_usd (all String), event_at (Instant).; Write BalanceResponseSerializationTest with ?include_history scenario and without.
**Deliverable:** lib-api-contracts/src/main/java/com/gme/pay/contracts/balance/BalanceResponse.java, DeductionEvent.java
**Acceptance / logic checks:**
- recent_deductions omitted when null.
- Negative amount_usd (cancellation return) serializes as string "-35.77".
- is_below_threshold=true when balance_usd < low_balance_threshold_usd (assert in test).
- as_of serializes as ISO-8601 UTC string.

### 8.5-T05 — Flyway migration V201__payment_query_indexes.sql on Transaction Mgmt PostgreSQL  _(25 min)_
**Context:** GET /v1/payments/{id} queries the transaction table by payment_id filtered by partner_id (ownership check: 404 if not owned by calling partner). The transaction table already exists from earlier epics. This ticket adds composite indexes to support fast ownership-scoped lookups. DB: PostgreSQL 16 in Transaction Mgmt service (services/transaction-mgmt). Migrations live in services/transaction-mgmt/src/main/resources/db/migration/. NUMERIC columns for all monetary fields. payment_id is globally unique (PK or unique index already); partner_id is a FK. The ownership check is (payment_id, partner_id) tuple.
**Steps:** Create services/transaction-mgmt/src/main/resources/db/migration/V201__payment_query_indexes.sql.; Add index: CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_txn_partner_payment ON transaction(partner_id, payment_id);; Add index: CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_txn_partner_status ON transaction(partner_id, status, created_at DESC); (supports future list queries).; Verify with EXPLAIN on SELECT * FROM transaction WHERE partner_id=? AND payment_id=? uses the index.
**Deliverable:** services/transaction-mgmt/src/main/resources/db/migration/V201__payment_query_indexes.sql
**Acceptance / logic checks:**
- Flyway applies migration cleanly on a fresh schema (Testcontainers integration test).
- EXPLAIN ANALYZE on ownership query shows Index Scan on idx_txn_partner_payment.
- IF NOT EXISTS prevents failure on repeated apply.
- Migration version V201 does not conflict with any existing version.

### 8.5-T06 — Flyway migration V202__cancel_fields.sql -- add cancelled_at and cancel_reason to transaction  _(25 min)_
**Context:** POST /v1/payments/{id}/cancel sets status=CANCELLED and records cancelled_at (TIMESTAMPTZ), cancel_reason (VARCHAR 50, enum values: customer_request|merchant_request|timeout|other), and cancel_reason_detail (TEXT, nullable, max 200 chars). These fields do not exist on the transaction table yet. DB: PostgreSQL 16, Transaction Mgmt service. Altering the table with nullable columns is safe for existing rows. Also add CHECK constraint on cancel_reason to guard against bad values at DB level.
**Steps:** Create services/transaction-mgmt/src/main/resources/db/migration/V202__cancel_fields.sql.; ALTER TABLE transaction ADD COLUMN cancelled_at TIMESTAMPTZ NULL, ADD COLUMN cancel_reason VARCHAR(50) NULL, ADD COLUMN cancel_reason_detail TEXT NULL.; ADD CONSTRAINT chk_cancel_reason CHECK (cancel_reason IN ('customer_request','merchant_request','timeout','other')).; Write Testcontainers migration test verifying INSERT with valid and invalid cancel_reason values.
**Deliverable:** services/transaction-mgmt/src/main/resources/db/migration/V202__cancel_fields.sql
**Acceptance / logic checks:**
- Migration applies cleanly after V201.
- Existing rows unaffected (cancelled_at NULL).
- INSERT with cancel_reason='other' succeeds.
- INSERT with cancel_reason='invalid' violates CHECK constraint.
- cancel_reason_detail of 201 chars stores without truncation (TEXT, no length limit at DB layer).
**Depends on:** 8.5-T05

### 8.5-T07 — Flyway migration V203__prefunding_history.sql -- deduction event log table  _(25 min)_
**Context:** GET /v1/balance with ?include_history=true returns last 10 deduction events (payment_id, amount_usd, balance_after_usd, event_at). These must be persisted in a prefunding_deduction_event table in PostgreSQL (Prefunding/Balance service DB). amount_usd is NUMERIC(20,4); positive = deduction, negative = return (cancellation). balance_after_usd is NUMERIC(20,4). Prefunding service: services/prefunding. This table is already being written to in earlier epics for each atomic deduction; confirm columns match the balance endpoint response shape.
**Steps:** Create services/prefunding/src/main/resources/db/migration/V203__prefunding_history.sql.; CREATE TABLE IF NOT EXISTS prefunding_deduction_event (id BIGSERIAL PRIMARY KEY, partner_id VARCHAR(64) NOT NULL, payment_id VARCHAR(64) NOT NULL, amount_usd NUMERIC(20,4) NOT NULL, balance_after_usd NUMERIC(20,4) NOT NULL, event_at TIMESTAMPTZ NOT NULL DEFAULT NOW()).; CREATE INDEX idx_pde_partner_time ON prefunding_deduction_event(partner_id, event_at DESC);; Verify via Testcontainers that SELECT ... ORDER BY event_at DESC LIMIT 10 returns correct rows.
**Deliverable:** services/prefunding/src/main/resources/db/migration/V203__prefunding_history.sql
**Acceptance / logic checks:**
- Table created with all columns and correct types.
- NUMERIC(20,4) for amount_usd stores -35.77 and 35.77 correctly.
- Index idx_pde_partner_time exists after migration.
- LIMIT 10 query on 20-row test data returns exactly 10 most-recent rows.

### 8.5-T08 — Implement GetPaymentQueryService in transaction-mgmt service  _(35 min)_
**Context:** GET /v1/payments/{id} is served by the Transaction Mgmt service (services/transaction-mgmt). The @Service class reads the transaction table by (payment_id, partner_id) -- 404 if not found (ownership enforced). Returns GetPaymentResponse DTO from lib-api-contracts. Use Spring Data JPA repository (TransactionRepository extends JpaRepository). The payment object includes rate-locked fields (offer_rate, collection_amount, service_charge, prefund_deducted_usd) that are stored as NUMERIC in PostgreSQL; map to String in the response. The spec says updated_at and cancelled_at are included when applicable.
**Steps:** Create GetPaymentQueryService.java annotated @Service in services/transaction-mgmt/src/main/java/com/gme/pay/txnmgmt/query/.; Inject TransactionRepository; add method GetPaymentResponse getPayment(String paymentId, String partnerId).; Query: transactionRepository.findByPaymentIdAndPartnerId(paymentId, partnerId).orElseThrow(() -> new PaymentNotFoundException(paymentId)).; Map JPA entity fields to GetPaymentResponse; convert NUMERIC to String; map status enum to PaymentStatus.; Implement PaymentNotFoundException extending RuntimeException in lib-errors, error code PAYMENT_NOT_FOUND, HTTP 404.
**Deliverable:** services/transaction-mgmt/src/main/java/com/gme/pay/txnmgmt/query/GetPaymentQueryService.java
**Acceptance / logic checks:**
- Returns correct DTO for known payment_id owned by partner.
- Throws PaymentNotFoundException for unknown payment_id.
- Throws PaymentNotFoundException when payment_id exists but belongs to a different partner (ownership enforced).
- NUMERIC monetary fields map to String with correct decimal scale (KRW 0 decimals, USD 2 decimals).
- cancelled_at null in response when status != CANCELLED.
**Depends on:** 8.5-T01, 8.5-T05

### 8.5-T09 — Implement GetPaymentController @RestController in transaction-mgmt for GET /v1/payments/{id}  _(30 min)_
**Context:** The Ops/Partner BFF routes GET /v1/payments/{id} through Spring Cloud Gateway to the Transaction Mgmt service. The @RestController lives in services/transaction-mgmt. Authentication: the partner_id is extracted from the JWT/HMAC security context (Spring Security, @AuthenticationPrincipal PartnerPrincipal). Idempotency-Key is NOT required on GET. SLA: p50 <100ms, p99 <300ms. Return 200 with GetPaymentResponse or 404 with standard lib-errors error envelope.
**Steps:** Create PaymentQueryController.java annotated @RestController, @RequestMapping("/v1/payments") in services/transaction-mgmt/src/main/java/com/gme/pay/txnmgmt/web/.; Implement GET /{id} method: extract partner_id from @AuthenticationPrincipal, call GetPaymentQueryService.getPayment(id, partnerId), return ResponseEntity<GetPaymentResponse> 200.; Wire @ControllerAdvice (PaymentExceptionHandler) to map PaymentNotFoundException to HTTP 404 with lib-errors error envelope {code: PAYMENT_NOT_FOUND, message, request_id}.; Add @Operation (SpringDoc/OpenAPI) annotation for API docs generation.
**Deliverable:** services/transaction-mgmt/src/main/java/com/gme/pay/txnmgmt/web/PaymentQueryController.java
**Acceptance / logic checks:**
- GET /v1/payments/{known-id} returns 200 with full payment object.
- GET /v1/payments/{unknown-id} returns 404 with JSON body {error.code: PAYMENT_NOT_FOUND}.
- GET /v1/payments/{id-of-other-partner} returns 404 (ownership enforced, not 403 to avoid enumeration).
- No Idempotency-Key required; header ignored if present.
**Depends on:** 8.5-T08

### 8.5-T10 — Implement CancelPaymentService core logic in transaction-mgmt  _(45 min)_
**Context:** POST /v1/payments/{id}/cancel: cancellable statuses are PENDING and APPROVED; same-day (KST) window only. Steps: (1) load transaction by (payment_id, partner_id) -- 404 if not found; (2) check status in {PENDING, APPROVED} -- 409 if already terminal (FAILED, CANCELLED); (3) check created_at date == today in KST (ZoneId Asia/Seoul) -- 400 CANCEL_NOT_PERMITTED if different day; (4) update status to CANCELLED, set cancelled_at=now(), cancel_reason, cancel_reason_detail; (5) if OVERSEAS partner, return prefunding (atomic: UPDATE prefunding SET balance_usd = balance_usd + ? WHERE partner_id=? within same @Transactional); (6) write outbox event payment.cancelled; (7) return CancelPaymentResponse.
**Steps:** Create CancelPaymentService.java @Service in services/transaction-mgmt/src/main/java/com/gme/pay/txnmgmt/cancel/.; Annotate service method with @Transactional (Spring); within one DB transaction: load entity, validate status and same-day window, update entity, conditionally credit prefunding via PrefundingClient (Feign/RestClient), insert outbox event.; Implement same-day check: transaction.getCreatedAt().atZone(ZoneId.of("Asia/Seoul")).toLocalDate().equals(LocalDate.now(ZoneId.of("Asia/Seoul"))).; Throw CancelNotPermittedException (400 CANCEL_NOT_PERMITTED) if date mismatch or status not cancellable; throw PaymentAlreadyTerminalException (409) if status is FAILED or CANCELLED.; Insert row into outbox table with event_type=payment.cancelled, payload JSON serialized from CancelPaymentResponse fields.
**Deliverable:** services/transaction-mgmt/src/main/java/com/gme/pay/txnmgmt/cancel/CancelPaymentService.java
**Acceptance / logic checks:**
- APPROVED payment with today KST date cancels successfully; status becomes CANCELLED.
- PENDING payment with today KST date cancels successfully.
- Payment with created_at yesterday KST returns 400 CANCEL_NOT_PERMITTED.
- Already CANCELLED payment returns 409.
- FAILED payment returns 409.
- Prefunding credited for OVERSEAS partner (verified by mock PrefundingClient call).
- Outbox row inserted with event_type=payment.cancelled in same transaction.
**Depends on:** 8.5-T06, 8.5-T02

### 8.5-T11 — Implement CancelPaymentController @RestController for POST /v1/payments/{id}/cancel  _(40 min)_
**Context:** POST /v1/payments/{id}/cancel in Transaction Mgmt service. Requires Idempotency-Key header (400 MISSING_IDEMPOTENCY_KEY if absent). Idempotency: store (partner_id, idempotency_key, response_body, status) in Redis with 24h TTL; on duplicate key+same-body return cached response; on duplicate key+different-body return 422 IDEMPOTENCY_KEY_REUSE. Extract partner_id from @AuthenticationPrincipal PartnerPrincipal. HMAC signature already validated by Spring Cloud Gateway filter upstream.
**Steps:** Create or extend PaymentCommandController.java @RestController @RequestMapping("/v1/payments") in services/transaction-mgmt/src/main/java/com/gme/pay/txnmgmt/web/.; Implement POST /{id}/cancel: (a) extract partner_id; (b) check Idempotency-Key header present; (c) check Redis for cached response (RedisTemplate<String,String>, key = partner_id+":"+idempotencyKey); (d) if cached, return cached; (e) else call CancelPaymentService, cache result with 24h TTL.; Map CancelNotPermittedException -> 400, PaymentAlreadyTerminalException -> 409, PaymentNotFoundException -> 404 via @ControllerAdvice.; Return ResponseEntity<CancelPaymentResponse> 200 on success.
**Deliverable:** services/transaction-mgmt/src/main/java/com/gme/pay/txnmgmt/web/PaymentCommandController.java (POST /{id}/cancel method)
**Acceptance / logic checks:**
- Missing Idempotency-Key returns 400 MISSING_IDEMPOTENCY_KEY.
- Second call with same key+same body returns 200 from Redis cache; CancelPaymentService not called again.
- Same key+different body returns 422 IDEMPOTENCY_KEY_REUSE.
- Successful cancel returns 200 with status=cancelled and cancelled_at.
- OVERSEAS partner response includes prefund_returned_usd; LOCAL partner omits it.
**Depends on:** 8.5-T10

### 8.5-T12 — Prefunding credit-back on cancel -- atomic UPDATE in prefunding service  _(40 min)_
**Context:** When a payment is cancelled, prefunding must be credited back for OVERSEAS partners only. The Prefunding service (services/prefunding) owns the prefunding table (PostgreSQL, NUMERIC balance_usd). Credit-back must be atomic: UPDATE prefunding SET balance_usd = balance_usd + :amount WHERE partner_id = :partnerId AND partner_type = 'OVERSEAS'. Also insert a prefunding_deduction_event row with negative amount_usd (indicating return). Both updates in one @Transactional. Expose as PrefundingService.returnFunds(String partnerId, BigDecimal amountUsd, String paymentId). Called by Transaction Mgmt via internal Feign client (same cluster).
**Steps:** In services/prefunding/src/main/java/com/gme/pay/prefunding/service/PrefundingService.java add method returnFunds(String partnerId, BigDecimal amountUsd, String paymentId).; Within @Transactional: execute UPDATE prefunding SET balance_usd = balance_usd + ? WHERE partner_id = ? AND partner_type = 'OVERSEAS' (JDBC or Spring Data JPA native query); assert rowsUpdated == 1 else throw IllegalStateException.; INSERT into prefunding_deduction_event with amount_usd = -amountUsd (negative), balance_after_usd = new balance (SELECT after update), payment_id, event_at=now().; Expose via PrefundingController @RestController POST /internal/prefunding/{partnerId}/return for Feign call from transaction-mgmt.
**Deliverable:** services/prefunding/src/main/java/com/gme/pay/prefunding/service/PrefundingService.java (returnFunds method) + PrefundingController.java (internal endpoint)
**Acceptance / logic checks:**
- returnFunds on OVERSEAS partner increases balance by exact BigDecimal amount.
- LOCAL partner (partner_type != OVERSEAS) results in rowsUpdated=0 and IllegalStateException.
- prefunding_deduction_event row has amount_usd = -35.77 for a 35.77 return.
- Operation is atomic: concurrent returnFunds and deductFunds on same partner_id produces correct final balance (Testcontainers test with two threads).
**Depends on:** 8.5-T07

### 8.5-T13 — Transactional Outbox event for payment.cancelled in transaction-mgmt  _(30 min)_
**Context:** After a successful cancel, a payment.cancelled domain event must be written to the outbox table (in transaction-mgmt PostgreSQL DB) within the same @Transactional as the status update. Outbox pattern: table columns id (UUID), aggregate_id (payment_id), event_type (VARCHAR), payload (JSONB), created_at, published_at (nullable). Published_at set by the outbox poller when delivered. The EventPublisher interface (lib-events) is used -- in Phase 1, it writes to the outbox table; Kafka is wired in integration phase. The payment.cancelled payload matches the webhook schema: {payment_id, partner_txn_ref, reason, prefund_returned_usd, cancelled_at}.
**Steps:** Confirm outbox table exists (created in earlier epic); if not, create migration V204__outbox.sql with the schema above.; In CancelPaymentService, inject EventPublisher (lib-events interface).; After status update, call eventPublisher.publish(new PaymentCancelledEvent(paymentId, partnerTxnRef, reason, prefundReturnedUsd, cancelledAt)).; Define PaymentCancelledEvent in lib-events/src/main/java/com/gme/pay/events/payment/PaymentCancelledEvent.java with all fields; annotate with @DomainEvent(type=payment.cancelled).; Verify: if outbox INSERT fails, the whole transaction rolls back (no partial cancel).
**Deliverable:** lib-events/src/main/java/com/gme/pay/events/payment/PaymentCancelledEvent.java + outbox INSERT in CancelPaymentService
**Acceptance / logic checks:**
- PaymentCancelledEvent serializes to JSON matching the payment.cancelled webhook schema.
- If EventPublisher.publish throws, status update is rolled back (transaction atomicity test).
- Outbox row has event_type=payment.cancelled and payload with prefund_returned_usd=null for LOCAL partner.
- Outbox poller (from lib-events) sets published_at when consumed -- verify in integration smoke test.
**Depends on:** 8.5-T10

### 8.5-T14 — Implement MerchantQueryService in merchant-qr-data service for GET /v1/merchants/{qr}  _(30 min)_
**Context:** GET /v1/merchants/{qr} resolves a URL-encoded QR string. The Merchant and QR Data service (services/merchant-qr-data) owns the MongoDB merchant store (CQRS read-model). Collection: merchants. Fields stored: _id (merchant_id), merchant_name, merchant_type, scheme_id, qr_code, status (active|inactive), payout_currency, address. The qr_code field is indexed. Query: db.merchants.findOne({qr_code: decodedQr}). If not found: 404 MERCHANT_NOT_FOUND. If found but status=inactive: 422 MERCHANT_INACTIVE (maps to spec 422 message). URL-decode the qr path parameter before query.
**Steps:** Create MerchantQueryService.java @Service in services/merchant-qr-data/src/main/java/com/gme/pay/merchantqr/query/.; Inject MongoTemplate or MerchantRepository (MongoRepository); implement getMerchant(String encodedQr): decode with URLDecoder.decode(encodedQr, UTF_8), query by qr_code field.; Throw MerchantNotFoundException (404 MERCHANT_NOT_FOUND) if no document found.; Throw MerchantInactiveException (422 MERCHANT_INACTIVE) if document found but status != active.; Map MongoDB document to MerchantDetailResponse from lib-api-contracts.
**Deliverable:** services/merchant-qr-data/src/main/java/com/gme/pay/merchantqr/query/MerchantQueryService.java
**Acceptance / logic checks:**
- Known active QR returns correct MerchantDetailResponse with all 8 fields.
- Unknown QR throws MerchantNotFoundException.
- Inactive merchant QR throws MerchantInactiveException.
- URL-encoded QR (spaces as %20 or +) decodes correctly before MongoDB query.
- address field null when not present in MongoDB document.
**Depends on:** 8.5-T03

### 8.5-T15 — Implement MerchantController @RestController in merchant-qr-data for GET /v1/merchants/{qr}  _(25 min)_
**Context:** GET /v1/merchants/{qr} is a read-only endpoint in the Merchant and QR Data service. No Idempotency-Key required. No prefunding involved. Authentication: HMAC-SHA256 validated by Spring Cloud Gateway upstream; partner_id available in security context but no ownership restriction (any authenticated partner can resolve any QR). HTTP 200 = active merchant found; 404 = MERCHANT_NOT_FOUND; 422 = merchant inactive or QR deactivated. SLA: p50 <200ms (MongoDB lookup from local read-model).
**Steps:** Create MerchantController.java @RestController @RequestMapping("/v1/merchants") in services/merchant-qr-data/src/main/java/com/gme/pay/merchantqr/web/.; Implement GET /{qr} method; @PathVariable String qr passed to MerchantQueryService.getMerchant(qr).; Add @ControllerAdvice mapping MerchantNotFoundException -> 404, MerchantInactiveException -> 422 using lib-errors error envelope.; Return ResponseEntity<MerchantDetailResponse> 200.; Add @Operation annotation for OpenAPI.
**Deliverable:** services/merchant-qr-data/src/main/java/com/gme/pay/merchantqr/web/MerchantController.java
**Acceptance / logic checks:**
- GET /v1/merchants/ZPQR00012345678901234567890 returns 200 with merchant JSON.
- GET /v1/merchants/UNKNOWN returns 404 JSON {error.code: MERCHANT_NOT_FOUND}.
- GET /v1/merchants/{inactive-qr} returns 422 JSON.
- URL-encoded path segment decoded before service call.
**Depends on:** 8.5-T14

### 8.5-T16 — Ensure MongoDB index on qr_code field in merchant-qr-data collection  _(20 min)_
**Context:** GET /v1/merchants/{qr} queries MongoDB by qr_code. Without an index, this is a full collection scan. The merchant collection lives in the Merchant and QR Data service MongoDB. Index must be created on application startup via Spring Data MongoDB @Document + @Indexed or via a MongoMigration/ChangeSet (Mongock library, used in this service). QR codes are unique per scheme.
**Steps:** In services/merchant-qr-data/src/main/java/com/gme/pay/merchantqr/domain/MerchantDocument.java, add @Indexed(unique=false) on qrCode field (unique=false because different schemes may reuse code format, uniqueness is enforced per scheme_id).; Alternatively, create a Mongock ChangeSet V004_MerchantQrIndex adding db.merchants.createIndex({qr_code:1}) with background:true.; Write integration test (Testcontainers MongoDB) verifying that explain() on {qr_code: value} uses IXSCAN not COLLSCAN.
**Deliverable:** services/merchant-qr-data/src/main/java/com/gme/pay/merchantqr/domain/MerchantDocument.java (annotated) OR Mongock ChangeSet V004
**Acceptance / logic checks:**
- explain() on qr_code query shows IXSCAN.
- Index created automatically on app startup without manual intervention.
- Duplicate qr_code for same scheme_id detected only at application layer (DB index is non-unique).
**Depends on:** 8.5-T14

### 8.5-T17 — Implement BalanceQueryService in prefunding service for GET /v1/balance  _(30 min)_
**Context:** GET /v1/balance returns current prefunding for OVERSEAS partners only. LOCAL partners get 403 FORBIDDEN. Service reads: (1) prefunding table in PostgreSQL (balance_usd NUMERIC, low_balance_threshold_usd NUMERIC, partner_type); (2) optionally prefunding_deduction_event last 10 rows when include_history=true. is_below_threshold = balance_usd < low_balance_threshold_usd. as_of = current DB server time (CURRENT_TIMESTAMP). Partner type checked from partner_type column in prefunding or from JWT claim. Return BalanceResponse from lib-api-contracts.
**Steps:** Create BalanceQueryService.java @Service in services/prefunding/src/main/java/com/gme/pay/prefunding/query/.; Load prefunding row by partner_id using PrefundingRepository; if partner_type != OVERSEAS throw PartnerForbiddenException (403 FORBIDDEN).; Compute is_below_threshold = balance_usd.compareTo(low_balance_threshold_usd) < 0.; If includeHistory=true, query prefunding_deduction_event WHERE partner_id=? ORDER BY event_at DESC LIMIT 10.; Map to BalanceResponse, setting recent_deductions to null when includeHistory=false.
**Deliverable:** services/prefunding/src/main/java/com/gme/pay/prefunding/query/BalanceQueryService.java
**Acceptance / logic checks:**
- OVERSEAS partner with balance_usd=48234.56 and threshold=10000 returns is_below_threshold=false.
- OVERSEAS partner with balance_usd=9999.00 and threshold=10000 returns is_below_threshold=true.
- LOCAL partner throws PartnerForbiddenException (403).
- include_history=true returns exactly 10 most-recent events when 20 exist.
- include_history=false (default) returns recent_deductions=null (omitted from JSON).
**Depends on:** 8.5-T04, 8.5-T07

### 8.5-T18 — Implement BalanceController @RestController in prefunding service for GET /v1/balance  _(25 min)_
**Context:** GET /v1/balance in Prefunding service (services/prefunding). No Idempotency-Key required (GET). Query param include_history (boolean, default false). Partner_id from @AuthenticationPrincipal. 200 = balance returned; 403 = LOCAL partner. Error envelope via lib-errors. SLA: p50 <200ms (single PostgreSQL read).
**Steps:** Create BalanceController.java @RestController @RequestMapping("/v1") in services/prefunding/src/main/java/com/gme/pay/prefunding/web/.; Implement GET /balance with @RequestParam(defaultValue="false") boolean includeHistory, @AuthenticationPrincipal PartnerPrincipal principal.; Call BalanceQueryService.getBalance(principal.getPartnerId(), includeHistory).; Map PartnerForbiddenException -> 403 via @ControllerAdvice with lib-errors envelope {code: FORBIDDEN}.; Return ResponseEntity<BalanceResponse> 200.
**Deliverable:** services/prefunding/src/main/java/com/gme/pay/prefunding/web/BalanceController.java
**Acceptance / logic checks:**
- OVERSEAS partner GET /v1/balance returns 200 with balance JSON.
- LOCAL partner returns 403 {error.code: FORBIDDEN}.
- GET /v1/balance?include_history=true returns recent_deductions array.
- GET /v1/balance (no param) returns recent_deductions absent from JSON.
**Depends on:** 8.5-T17

### 8.5-T19 — Spring Cloud Gateway routing rules for WBS 8.5 endpoints  _(35 min)_
**Context:** Spring Cloud Gateway (edge service, services/gateway) routes: GET /v1/payments/{id} -> transaction-mgmt; POST /v1/payments/{id}/cancel -> transaction-mgmt; GET /v1/merchants/{qr} -> merchant-qr-data; GET /v1/balance -> prefunding. HMAC-SHA256 signature validation filter is already wired on all /v1/** routes. Idempotency-Key validation filter: enforce header present on all POST /v1/** routes (return 400 MISSING_IDEMPOTENCY_KEY if absent). Rate limiting: Redis-based (Spring Cloud Gateway RequestRateLimiter filter, key = partner_id from JWT).
**Steps:** In services/gateway/src/main/resources/application.yml, add route entries under spring.cloud.gateway.routes for the 4 new endpoints with uri: lb://transaction-mgmt, lb://merchant-qr-data, lb://prefunding respectively.; Add predicates: Path, Method filters.; Confirm HmacValidationGatewayFilter is applied globally (already exists from earlier epic).; Verify IdempotencyKeyGatewayFilter applies only to POST routes (add predicate Method=POST).; Write @SpringBootTest gateway integration test with WireMock stubs for downstream services verifying routing.
**Deliverable:** services/gateway/src/main/resources/application.yml (new route entries)
**Acceptance / logic checks:**
- GET /v1/payments/{id} routes to transaction-mgmt (WireMock stub returns 200).
- POST /v1/payments/{id}/cancel without Idempotency-Key returns 400 at gateway level.
- GET /v1/merchants/{qr} routes to merchant-qr-data.
- GET /v1/balance routes to prefunding.
- HMAC signature validation still fires for all 4 routes.
**Depends on:** 8.5-T09, 8.5-T11, 8.5-T15, 8.5-T18

### 8.5-T20 — Unit tests for GetPaymentQueryService -- ownership and status mapping  _(30 min)_
**Context:** Test the GetPaymentQueryService in services/transaction-mgmt. Use JUnit 5 + Mockito (no DB needed). Three test scenarios: (1) happy path with status=APPROVED; (2) payment not found (404); (3) ownership violation (payment belongs to different partner). Also test each PaymentStatus enum value mapping. Use exact spec example values: payment_id=pay_01HX7MNQ5ABCDEF123456789, target_payout=50000, payout_currency=KRW, offer_rate=0.000703, collection_amount=35.77, collection_currency=USD, service_charge=0.35.
**Steps:** Create GetPaymentQueryServiceTest.java in services/transaction-mgmt/src/test/java/com/gme/pay/txnmgmt/query/.; Mock TransactionRepository; stub findByPaymentIdAndPartnerId to return Optional.of(entity) with spec example values.; Assert returned GetPaymentResponse fields match exactly (target_payout="50000", offer_rate="0.000703").; Test not-found: stub returns Optional.empty(); assert PaymentNotFoundException thrown.; Test each status: PENDING, APPROVED, FAILED, CANCELLED, UNCERTAIN mapped to correct PaymentStatus enum.
**Deliverable:** services/transaction-mgmt/src/test/java/com/gme/pay/txnmgmt/query/GetPaymentQueryServiceTest.java
**Acceptance / logic checks:**
- Happy path: target_payout string equals "50000" (KRW, 0 decimals, no trailing .0).
- offer_rate string equals "0.000703".
- PaymentNotFoundException thrown for unknown payment_id.
- All 5 status values map correctly.
- cancelled_at null when status != CANCELLED.
**Depends on:** 8.5-T08

### 8.5-T21 — Unit tests for CancelPaymentService -- same-day KST window and status guards  _(40 min)_
**Context:** Test CancelPaymentService in transaction-mgmt. Use JUnit 5 + Mockito + a fixed Clock injected into the service (use Clock.fixed(..., ZoneOffset.UTC) to control now()). Test vectors: (1) APPROVED payment, created today KST -> success; (2) APPROVED payment, created yesterday KST -> CANCEL_NOT_PERMITTED; (3) CANCELLED payment -> 409; (4) FAILED payment -> 409; (5) PENDING payment, today KST -> success; (6) OVERSEAS partner -> prefunding return called; (7) LOCAL partner -> prefunding return NOT called. Inject Clock via constructor for testability.
**Steps:** Refactor CancelPaymentService to accept Clock dependency (constructor injection, default Clock.systemDefaultZone()).; Create CancelPaymentServiceTest.java in services/transaction-mgmt/src/test/java/com/gme/pay/txnmgmt/cancel/.; Use Clock.fixed(Instant.parse("2026-06-05T01:00:00Z"), ZoneOffset.UTC) so today KST = 2026-06-05. Set created_at to 2026-06-04T16:00:00Z (which is 2026-06-05 01:00 KST -- same day) for passing test.; For yesterday test: set created_at to 2026-06-04T14:59:00Z (= 2026-06-04 23:59 KST -- previous day).; Assert EventPublisher.publish called once on success; not called on rejection.
**Deliverable:** services/transaction-mgmt/src/test/java/com/gme/pay/txnmgmt/cancel/CancelPaymentServiceTest.java
**Acceptance / logic checks:**
- Today-KST cancellation succeeds; cancelled_at set to now().
- Yesterday-KST throws CancelNotPermittedException with code CANCEL_NOT_PERMITTED.
- CANCELLED status throws PaymentAlreadyTerminalException (409).
- OVERSEAS partner: PrefundingClient.returnFunds called with exact collection_usd amount.
- LOCAL partner: PrefundingClient.returnFunds NOT called.
- EventPublisher.publish called exactly once per successful cancel.
**Depends on:** 8.5-T10

### 8.5-T22 — Unit tests for MerchantQueryService -- active, inactive, not-found, URL-decode  _(30 min)_
**Context:** Test MerchantQueryService in merchant-qr-data service. Use JUnit 5 + Mockito + Embedded MongoDB (Flapdoodle) or plain Mockito mock of MerchantRepository. Test vectors: (1) active merchant found by qr_code; (2) inactive merchant found but throws MerchantInactiveException; (3) unknown QR throws MerchantNotFoundException; (4) URL-encoded QR (ZPQR%20001) decodes to ZPQR 001 before query. Check all 8 response fields with spec example values: merchant_id=mch_01HX7MNQ9ABC, merchant_name=Seoul Coffee House, payout_currency=KRW.
**Steps:** Create MerchantQueryServiceTest.java in services/merchant-qr-data/src/test/java/com/gme/pay/merchantqr/query/.; Mock MerchantRepository (or MongoTemplate); stub findByQrCode("ZPQR00012345678901234567890") to return active MerchantDocument with spec values.; Assert MerchantDetailResponse.merchantName().equals("Seoul Coffee House") and payoutCurrency().equals("KRW").; Stub findByQrCode for inactive doc; assert MerchantInactiveException.; Test URL decode: pass encoded "ZPQR%2000012345678901234567890"; stub matching decoded string.
**Deliverable:** services/merchant-qr-data/src/test/java/com/gme/pay/merchantqr/query/MerchantQueryServiceTest.java
**Acceptance / logic checks:**
- Active merchant: all 8 fields correct per spec example.
- Inactive merchant: MerchantInactiveException thrown.
- Unknown QR: MerchantNotFoundException thrown.
- URL-encoded QR decoded before repository query.
- address null in response when MongoDB document has no address field.
**Depends on:** 8.5-T14

### 8.5-T23 — Unit tests for BalanceQueryService -- OVERSEAS/LOCAL, threshold logic, history  _(25 min)_
**Context:** Test BalanceQueryService in prefunding service. JUnit 5 + Mockito. Test vectors: (1) OVERSEAS partner, balance=48234.56, threshold=10000.00, is_below_threshold=false; (2) OVERSEAS partner, balance=9999.00, threshold=10000.00, is_below_threshold=true; (3) LOCAL partner throws PartnerForbiddenException; (4) include_history=true returns last 10 deduction events; (5) include_history=false returns null recent_deductions. Use BigDecimal arithmetic -- no floating point.
**Steps:** Create BalanceQueryServiceTest.java in services/prefunding/src/test/java/com/gme/pay/prefunding/query/.; Mock PrefundingRepository and DeductionEventRepository (Spring Data JPA).; Stub prefunding row with balance_usd=new BigDecimal("48234.56"), low_balance_threshold_usd=new BigDecimal("10000.00"), partner_type=OVERSEAS.; Assert is_below_threshold = (balance_usd.compareTo(threshold) < 0); equals false for above vector, true for 9999.00.; Stub 20 deduction events; verify findTop10ByPartnerIdOrderByEventAtDesc returns exactly 10.
**Deliverable:** services/prefunding/src/test/java/com/gme/pay/prefunding/query/BalanceQueryServiceTest.java
**Acceptance / logic checks:**
- balance=48234.56, threshold=10000.00 -> is_below_threshold=false.
- balance=9999.00, threshold=10000.00 -> is_below_threshold=true.
- LOCAL partner throws PartnerForbiddenException.
- include_history=true -> 10 events returned (when 20 exist).
- include_history=false -> recent_deductions is null.
**Depends on:** 8.5-T17

### 8.5-T24 — Unit tests for prefunding credit-back atomicity in CancelPaymentService (concurrent test)  _(45 min)_
**Context:** Verify that when CancelPaymentService is called concurrently for two different payments by the same OVERSEAS partner, the prefunding balance converges correctly. Use Testcontainers (PostgreSQL 16) with two threads each cancelling a separate APPROVED payment (each with collection_usd=35.77). Initial balance=200.00. After both cancels, balance must be 200.00+35.77+35.77=271.54. Also verify that if prefunding UPDATE fails mid-transaction, the transaction status update rolls back (inject fault via spy).
**Steps:** Create CancelPaymentConcurrencyTest.java in services/transaction-mgmt/src/test/java/com/gme/pay/txnmgmt/cancel/ annotated @Testcontainers @SpringBootTest.; Seed PostgreSQL (Testcontainers) with partner balance=200.00 and two APPROVED payments each with collection_usd=35.77 created today KST.; Submit two concurrent CancelPaymentService.cancel() calls via ExecutorService.invokeAll().; Assert final balance = 271.54 (BigDecimal.compareTo).; Inject fault in PrefundingClient: throw RuntimeException after status update but before prefunding credit; assert transaction rolled back (status remains APPROVED, balance unchanged).
**Deliverable:** services/transaction-mgmt/src/test/java/com/gme/pay/txnmgmt/cancel/CancelPaymentConcurrencyTest.java
**Acceptance / logic checks:**
- Concurrent dual cancel: final balance == 271.54.
- Neither payment is double-credited.
- Fault injection: transaction status rolled back when prefunding credit fails.
- Balance unchanged when rollback occurs.
**Depends on:** 8.5-T10, 8.5-T12

### 8.5-T25 — Testcontainers integration test for GET /v1/payments/{id} slice  _(40 min)_
**Context:** End-to-end Spring Boot test slice for the payment query flow in transaction-mgmt. Use @SpringBootTest + Testcontainers (PostgreSQL 16) + MockMvc. Seed a CANCELLED payment row (with cancelled_at set) and an APPROVED payment row owned by partner prt_sendmn_001. Test: (1) GET /v1/payments/{approved-id} with correct auth -> 200 all fields; (2) GET /v1/payments/{cancelled-id} -> 200 includes cancelled_at; (3) GET /v1/payments/{id-of-other-partner} -> 404; (4) GET /v1/payments/nonexistent -> 404. Auth stub via Spring Security test support (@WithMockUser or custom PartnerPrincipal).
**Steps:** Create GetPaymentIntegrationTest.java in services/transaction-mgmt/src/test/java/com/gme/pay/txnmgmt/web/.; Annotate @SpringBootTest(webEnvironment=RANDOM_PORT) @Testcontainers; define @Container PostgreSQLContainer.; Seed test data via JdbcTemplate in @BeforeEach.; Use MockMvc or TestRestTemplate; set Authorization header with test JWT containing partner_id=prt_sendmn_001.; Assert response JSON with jsonPath: $.payment_id, $.status, $.cancelled_at present/absent.
**Deliverable:** services/transaction-mgmt/src/test/java/com/gme/pay/txnmgmt/web/GetPaymentIntegrationTest.java
**Acceptance / logic checks:**
- Approved payment: 200, status=approved, cancelled_at absent from JSON.
- Cancelled payment: 200, status=cancelled, cancelled_at present.
- Other partner's payment: 404 PAYMENT_NOT_FOUND.
- Nonexistent payment: 404 PAYMENT_NOT_FOUND.
**Depends on:** 8.5-T09, 8.5-T05

### 8.5-T26 — Testcontainers integration test for POST /v1/payments/{id}/cancel slice  _(45 min)_
**Context:** Integration test for cancel flow in transaction-mgmt. Use @SpringBootTest + Testcontainers (PostgreSQL 16) + WireMock (for PrefundingClient HTTP call) + MockMvc. Test: (1) cancel APPROVED OVERSEAS payment today KST -> 200, prefund_returned_usd=35.77, WireMock stub confirms prefunding return call made; (2) cancel already-CANCELLED -> 409; (3) cancel next-day payment -> 400 CANCEL_NOT_PERMITTED; (4) missing Idempotency-Key -> 400; (5) same key + same body second call -> 200 from Redis cache. Use Redis Testcontainer for idempotency check.
**Steps:** Create CancelPaymentIntegrationTest.java in services/transaction-mgmt/src/test/java/com/gme/pay/txnmgmt/web/.; @Testcontainers with PostgreSQLContainer + @Container RedisContainer (Testcontainers Redis).; Stub PrefundingClient with WireMock (@WireMockTest or WireMockServer): POST /internal/prefunding/prt_sendmn_001/return returns 200.; Seed APPROVED OVERSEAS payment with collection_usd=35.77, created_at=today 00:30 KST.; Run all 5 test scenarios; assert WireMock received exactly 1 call for scenario 5 (second call uses cache).
**Deliverable:** services/transaction-mgmt/src/test/java/com/gme/pay/txnmgmt/web/CancelPaymentIntegrationTest.java
**Acceptance / logic checks:**
- 200 cancel: prefund_returned_usd=35.77, status=cancelled.
- 409 on already-cancelled payment.
- 400 CANCEL_NOT_PERMITTED on next-day payment.
- 400 MISSING_IDEMPOTENCY_KEY when header absent.
- Duplicate idempotency key: WireMock receives 0 second calls (cache used).
**Depends on:** 8.5-T11, 8.5-T12

### 8.5-T27 — Testcontainers integration test for GET /v1/merchants/{qr} slice  _(35 min)_
**Context:** Integration test for merchant QR resolution in merchant-qr-data service. Use @SpringBootTest + Testcontainers (MongoDB) + MockMvc. Seed 3 merchant documents: (1) active merchant ZPQR00012345678901234567890; (2) inactive merchant ZPQR_INACTIVE; (3) no address field. Test: 200 active, 422 inactive, 404 unknown QR, URL-encoded QR decoded correctly.
**Steps:** Create MerchantQueryIntegrationTest.java in services/merchant-qr-data/src/test/java/com/gme/pay/merchantqr/web/.; Use MongoDBContainer from Testcontainers; seed via MongoTemplate.insert() in @BeforeEach.; Test GET /v1/merchants/ZPQR00012345678901234567890 -> 200, merchant_name=Seoul Coffee House.; Test GET /v1/merchants/ZPQR_INACTIVE -> 422.; Test GET /v1/merchants/UNKNOWN -> 404 MERCHANT_NOT_FOUND.; Test GET /v1/merchants/ZPQR%3D001 (URL-encoded = sign) decoded and queried correctly.
**Deliverable:** services/merchant-qr-data/src/test/java/com/gme/pay/merchantqr/web/MerchantQueryIntegrationTest.java
**Acceptance / logic checks:**
- Active QR: 200 merchant_name=Seoul Coffee House, payout_currency=KRW.
- Inactive QR: 422 response.
- Unknown QR: 404 MERCHANT_NOT_FOUND.
- URL-encoded QR resolves to correct merchant.
- address absent from JSON when not seeded.
**Depends on:** 8.5-T15, 8.5-T16

### 8.5-T28 — Testcontainers integration test for GET /v1/balance slice  _(35 min)_
**Context:** Integration test for balance endpoint in prefunding service. Use @SpringBootTest + Testcontainers (PostgreSQL 16) + MockMvc. Seed: OVERSEAS partner prt_sendmn_001 with balance_usd=48234.56, threshold=10000.00; LOCAL partner prt_gmere_001 with partner_type=LOCAL; 15 deduction events for prt_sendmn_001. Test: 200 OVERSEAS, 200 with include_history, 403 LOCAL, is_below_threshold logic.
**Steps:** Create BalanceIntegrationTest.java in services/prefunding/src/test/java/com/gme/pay/prefunding/web/.; PostgreSQLContainer; seed prefunding and prefunding_deduction_event tables.; Test GET /v1/balance (OVERSEAS partner) -> 200, balance_usd=48234.56, is_below_threshold=false, recent_deductions absent.; Test GET /v1/balance?include_history=true -> recent_deductions has exactly 10 elements.; Test GET /v1/balance with LOCAL partner auth -> 403 {error.code: FORBIDDEN}.
**Deliverable:** services/prefunding/src/test/java/com/gme/pay/prefunding/web/BalanceIntegrationTest.java
**Acceptance / logic checks:**
- balance_usd=48234.56 returned as string.
- is_below_threshold=false for 48234.56 > 10000.00.
- include_history=true returns exactly 10 most-recent events (15 seeded).
- LOCAL partner: 403 FORBIDDEN.
- recent_deductions absent (not null, omitted) when include_history=false.
**Depends on:** 8.5-T18

### 8.5-T29 — OpenAPI spec update in openapi/partner-api.yaml for WBS 8.5 endpoints  _(40 min)_
**Context:** The authoritative OpenAPI spec at openapi/partner-api.yaml (root of monorepo) must be updated with the 4 new endpoint definitions: GET /v1/payments/{id}, POST /v1/payments/{id}/cancel, GET /v1/merchants/{qr}, GET /v1/balance. Each endpoint must include: summary, operationId, security (hmacAuth), request parameters, response schemas referencing lib-api-contracts DTOs, and all error responses (400/403/404/409/422 as applicable). This spec is the source of truth for lib-api-contracts code generation.
**Steps:** Open openapi/partner-api.yaml; locate the paths section after existing payment paths.; Add path /v1/payments/{id}: get with operationId getPayment, parameter id (path, string, required), responses 200 (schema $ref GetPaymentResponse), 404 ($ref ErrorResponse).; Add path /v1/payments/{id}/cancel: post with operationId cancelPayment, requestBody $ref CancelPaymentRequest, Idempotency-Key header param, responses 200/400/404/409.; Add path /v1/merchants/{qr}: get operationId resolveMerchantQr, responses 200/404/422.; Add path /v1/balance: get operationId getPrefundingBalance, query param include_history (boolean, default false), responses 200/403.
**Deliverable:** openapi/partner-api.yaml (updated with 4 new path entries)
**Acceptance / logic checks:**
- openapi-generator validate passes with no errors on the updated YAML.
- All 4 new paths present in rendered Swagger UI.
- CancelPaymentRequest reason field shows enum [customer_request,merchant_request,timeout,other].
- GET /v1/balance shows 403 response schema with ErrorResponse.
- include_history query param shows default=false in spec.
**Depends on:** 8.5-T01, 8.5-T02, 8.5-T03, 8.5-T04

### 8.5-T30 — Webhook payment.cancelled event outbox poller and delivery verification  _(45 min)_
**Context:** The outbox poller (in lib-events or transaction-mgmt) periodically reads unpublished rows from the outbox table (event_type=payment.cancelled) and delivers them to the Notification and Webhook service (services/notification-webhook) via the EventPublisher interface. In Phase 1 this is in-process polling (not Kafka). The notification service then calls the partner's registered webhook_url via HTTP POST with the payment.cancelled payload, including HMAC-SHA256 signature header X-GME-Webhook-Signature. Delivery follows 10-attempt exponential back-off: 0s, 30s, 2m, 10m, 30m, 1h*5.
**Steps:** In services/notification-webhook/src/main/java/com/gme/pay/notifwebhook/delivery/WebhookDeliveryService.java, add handler for PaymentCancelledEvent event type.; Build HTTP POST payload matching payment.cancelled schema; sign with HMAC-SHA256(body, webhookSigningSecret) and set X-GME-Webhook-Signature: sha256=<sig> header.; Implement retry scheduling using Spring @Scheduled or existing back-off framework; mark outbox row published_at on first 2xx response; mark delivery_failed after 10 attempts.; Write WebhookDeliveryServiceTest using WireMock: (a) 2xx on first attempt sets published_at; (b) 5xx triggers retry; (c) after 10 failures marks delivery_failed.
**Deliverable:** services/notification-webhook/src/main/java/com/gme/pay/notifwebhook/delivery/WebhookDeliveryService.java (payment.cancelled handler)
**Acceptance / logic checks:**
- Webhook POST body matches payment.cancelled schema exactly.
- X-GME-Webhook-Signature header computed as sha256=HMAC-SHA256(body, secret).
- 2xx response: outbox row published_at set; no retry scheduled.
- 5xx response: retry with 30s delay (mocked scheduler asserted).
- After 10 failures: delivery_failed status set; GME Ops alert triggered (log or event).
**Depends on:** 8.5-T13


## WBS 8.8 — Rate limiting & IP allowlist
### 8.8-T01 — Flyway migration: partner_ip_allowlist and partner_rate_limit_config tables  _(25 min)_
**Context:** Spring Cloud Gateway needs per-partner IP allowlist (up to 10 CIDR ranges; HTTP 403 IP_NOT_ALLOWLISTED when configured but source IP not matched; applied before signature check) and per-partner rate-limit overrides. Default limits per API-05 sec 3.5: 100 req/s global per partner, 20 req/s on POST /v1/rates, 50 req/s on POST /v1/payments and POST /v1/payments/cpm/generate. SEC-09 sec 5.4 adds 300 rpm global and 120 rpm quote per partner. Tables live in the services/gateway module Flyway path.
**Steps:** Create services/gateway/src/main/resources/db/migration/V8001__gateway_ip_allowlist.sql with partner_ip_allowlist(id BIGINT GENERATED ALWAYS AS IDENTITY PK, partner_id BIGINT NOT NULL REFERENCES partner(id), cidr_range VARCHAR(50) NOT NULL, is_active BOOLEAN NOT NULL DEFAULT TRUE, created_by VARCHAR(64) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()); add UNIQUE(partner_id, cidr_range); add index idx_ip_allowlist_partner ON partner_ip_allowlist(partner_id) WHERE is_active=TRUE; Create services/gateway/src/main/resources/db/migration/V8002__gateway_rate_limit_config.sql with partner_rate_limit_config(id BIGINT GENERATED ALWAYS AS IDENTITY PK, partner_id BIGINT NOT NULL UNIQUE REFERENCES partner(id), global_rps INT NOT NULL DEFAULT 100, rates_rps INT NOT NULL DEFAULT 20, payments_rps INT NOT NULL DEFAULT 50, cpm_rps INT NOT NULL DEFAULT 50, global_rpm INT NOT NULL DEFAULT 300, quote_rpm INT NOT NULL DEFAULT 120, updated_by VARCHAR(64) NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT now()); add CHECK(global_rps BETWEEN 1 AND 10000), CHECK(rates_rps BETWEEN 1 AND 10000), CHECK(payments_rps BETWEEN 1 AND 10000), CHECK(cpm_rps BETWEEN 1 AND 10000); Add Flyway and PostgreSQL JDBC dependencies to services/gateway/build.gradle if not already present
**Deliverable:** services/gateway/src/main/resources/db/migration/V8001__gateway_ip_allowlist.sql and V8002__gateway_rate_limit_config.sql
**Acceptance / logic checks:**
- V8001 creates partner_ip_allowlist with all columns, FK to partner, UNIQUE(partner_id,cidr_range), and partial index on is_active=TRUE
- V8002 creates partner_rate_limit_config with correct DEFAULT values (global_rps=100, rates_rps=20, payments_rps=50, cpm_rps=50, global_rpm=300, quote_rpm=120) and CHECK constraints
- CHECK constraint rejects global_rps=0 with a constraint violation
- flyway migrate runs clean against a Testcontainers PostgreSQL 16 instance without error
- Inserting a CIDR duplicate for same partner violates UNIQUE constraint

### 8.8-T02 — JPA entities and repositories for IpAllowlistEntry and PartnerRateLimitConfig in services/gateway  _(30 min)_
**Context:** Spring Cloud Gateway service (services/gateway, Java 21, Spring Boot 3.x) needs JPA entities and Spring Data repositories for the two tables added in 8.8-T01. These are used by the IP allowlist filter and the dynamic rate-limit resolver to load per-partner config from PostgreSQL (then cached in Redis via PartnerConfigCache in 8.8-T03).
**Steps:** Create services/gateway/src/main/java/com/gme/pay/gateway/persistence/IpAllowlistEntry.java as @Entity @Table(name=partner_ip_allowlist) with fields: id Long, partnerId Long, cidrRange String, isActive boolean, createdBy String, createdAt Instant, updatedAt Instant; add @Index on partnerId in @Table annotation; Create services/gateway/src/main/java/com/gme/pay/gateway/persistence/PartnerRateLimitConfig.java as @Entity @Table(name=partner_rate_limit_config) with fields: id Long, partnerId Long, globalRps int, ratesRps int, paymentsRps int, cpmRps int, globalRpm int, quoteRpm int, updatedBy String, updatedAt Instant; Create IpAllowlistRepository extends JpaRepository<IpAllowlistEntry,Long> with query method List<IpAllowlistEntry> findByPartnerIdAndIsActiveTrue(Long partnerId); Create PartnerRateLimitConfigRepository extends JpaRepository<PartnerRateLimitConfig,Long> with Optional<PartnerRateLimitConfig> findByPartnerId(Long partnerId)
**Deliverable:** services/gateway/src/main/java/com/gme/pay/gateway/persistence/IpAllowlistEntry.java, PartnerRateLimitConfig.java, IpAllowlistRepository.java, PartnerRateLimitConfigRepository.java
**Acceptance / logic checks:**
- @DataJpaTest with Testcontainers PostgreSQL 16 can persist and retrieve IpAllowlistEntry; findByPartnerIdAndIsActiveTrue returns only is_active=TRUE rows
- @DataJpaTest can persist and retrieve PartnerRateLimitConfig by partnerId
- Entity field names match migration column names via @Column annotations (snake_case DB, camelCase Java)
- No Hibernate schema-generation warnings at application startup
**Depends on:** 8.8-T01

### 8.8-T03 — Implement PartnerConfigCache: Redis-backed cache for IP allowlist and rate-limit config  _(35 min)_
**Context:** The IP allowlist filter and rate-limit resolver both need per-partner config. Config must be cached in Redis (the stack cache layer) with TTL 300s (configurable via gateway.cache.ttl-seconds) so that IP allowlist changes take effect within 5 minutes per API-05 sec 3.3. An evict() method is called by the admin service on every config change. Module: services/gateway. Uses IpAllowlistRepository and PartnerRateLimitConfigRepository from 8.8-T02.
**Steps:** Create services/gateway/src/main/java/com/gme/pay/gateway/cache/PartnerConfigCache.java as a @Component using Spring Data Redis (ReactiveRedisTemplate<String,String> for reactive gateway context); Implement Mono<List<IpAllowlistEntry>> getIpAllowlist(Long partnerId): check Redis key gateway:ip_allowlist:{partnerId}; if absent load from IpAllowlistRepository via subscribeOn(boundedElastic), serialize to JSON, store with TTL 300s, return; Implement Mono<PartnerRateLimitConfig> getRateLimitConfig(Long partnerId): check Redis key gateway:rate_limit:{partnerId}; if absent load from PartnerRateLimitConfigRepository; if no DB row return default config (globalRps=100, ratesRps=20, paymentsRps=50, cpmRps=50, globalRpm=300, quoteRpm=120); cache result with TTL 300s; Implement Mono<Void> evict(Long partnerId): delete both Redis keys gateway:ip_allowlist:{partnerId} and gateway:rate_limit:{partnerId}; Add @ConfigurationProperties(prefix=gateway.cache) with field ttlSeconds defaulting to 300
**Deliverable:** services/gateway/src/main/java/com/gme/pay/gateway/cache/PartnerConfigCache.java
**Acceptance / logic checks:**
- On first call getIpAllowlist queries PostgreSQL and writes to Redis; second call within TTL returns from Redis (verify by counting repository mock invocations = 1)
- After evict(partnerId) next getIpAllowlist call queries PostgreSQL again (count increments to 2)
- TTL of Redis key is between 295 and 310 seconds after write (check via RedisTemplate.getExpire)
- getRateLimitConfig with no DB row returns defaults: globalRps=100, ratesRps=20, paymentsRps=50, cpmRps=50
- Testcontainers Redis 7 used in integration test
**Depends on:** 8.8-T02

### 8.8-T04 — Implement PartnerIdExtractor: resolve partner_id from X-Api-Key header with Redis read-through cache  _(30 min)_
**Context:** Gateway filters (IP allowlist and rate limiter) need the partner_id (Long) to look up per-partner config. The partner_id is resolved from the X-Api-Key header by consulting the partner_credential table (api_key VARCHAR(64) UNIQUE, partner_id BIGINT FK, is_active BOOLEAN). A Redis read-through cache at key gateway:api_key:{apiKey} with TTL 60s avoids a DB hit on every request. Unknown keys are cached as a miss-sentinel (value -1, TTL 10s) to prevent DB hammering. Module: services/gateway.
**Steps:** Create services/gateway/src/main/java/com/gme/pay/gateway/auth/PartnerIdExtractor.java as a @Component; Implement Mono<Optional<Long>> resolve(ServerWebExchange exchange): extract X-Api-Key header; if absent return Mono.just(Optional.empty()); Check Redis key gateway:api_key:{apiKey}: if value is numeric and > 0 return that partner_id; if value equals -1 return Optional.empty() (cached miss); If key absent: query partner_credential WHERE api_key=? AND is_active=TRUE via PartnerCredentialRepository (new repository, query method findByApiKeyAndIsActiveTrue); if found cache partnerId with TTL 60s; if not found cache -1 with TTL 10s; return accordingly; Inject into IpAllowlistGatewayFilter (8.8-T05) and PartnerRateLimitKeyResolver (8.8-T07)
**Deliverable:** services/gateway/src/main/java/com/gme/pay/gateway/auth/PartnerIdExtractor.java and PartnerCredentialRepository.java
**Acceptance / logic checks:**
- Valid api_key returns correct partner_id from DB on first call; Redis cache hit on second call (repository invoked once total)
- Missing X-Api-Key header returns Optional.empty() with zero DB calls
- Unknown api_key caches miss sentinel; second call within 10s does not hit DB
- Inactive credential (is_active=FALSE) returns Optional.empty()
- Testcontainers Redis 7 + PostgreSQL 16 integration test
**Depends on:** 8.8-T02

### 8.8-T05 — Implement IpAllowlistGatewayFilter: Spring Cloud Gateway GlobalFilter for IP allowlist enforcement  _(45 min)_
**Context:** API-05 sec 3.3: if a partner has any active CIDR entries in partner_ip_allowlist, requests whose source IP does not match any CIDR must be rejected with HTTP 403 and JSON body {errorCode: IP_NOT_ALLOWLISTED, message: Request source IP is not on partner IP allowlist} before signature verification. If the partner has no CIDRs the filter is a no-op. Source IP is taken from X-Forwarded-For first hop; falls back to RemoteAddr. Module: services/gateway.
**Steps:** Create services/gateway/src/main/java/com/gme/pay/gateway/filter/IpAllowlistGatewayFilter.java implementing GlobalFilter and Ordered with getOrder() = Ordered.HIGHEST_PRECEDENCE + 10; Extract client IP: split X-Forwarded-For on comma and use first element if present; otherwise use exchange.getRequest().getRemoteAddress(); Call PartnerIdExtractor.resolve; if empty proceed (auth will fail later, not this filter's concern); Call PartnerConfigCache.getIpAllowlist(partnerId).flatMap: if list empty proceed; else check source IP against each cidr_range using Apache Commons Net SubnetUtils (commons-net dependency); On match proceed; on no match set status 403, write JSON error body {errorCode: IP_NOT_ALLOWLISTED, message: Request source IP is not on partner IP allowlist}, log WARN via SLF4J with MDC fields partner_id and result=BLOCKED; Skip filter for /internal/v1/** paths (check request path prefix)
**Deliverable:** services/gateway/src/main/java/com/gme/pay/gateway/filter/IpAllowlistGatewayFilter.java
**Acceptance / logic checks:**
- Partner with CIDR 10.0.0.0/24 and request from 10.0.0.50: HTTP 200 forwarded downstream
- Partner with CIDR 10.0.0.0/24 and request from 192.168.1.1: HTTP 403 body contains errorCode=IP_NOT_ALLOWLISTED
- Partner with no active CIDRs: any source IP proceeds
- X-Forwarded-For: 192.168.1.1, 10.0.0.5 uses 192.168.1.1 (first hop is client)
- Path /internal/v1/partners/1/ip-allowlist bypasses this filter regardless of IP
- Testcontainers Redis + PostgreSQL + WireMock stub downstream
**Depends on:** 8.8-T03, 8.8-T04

### 8.8-T06 — Unit tests: IpAllowlistGatewayFilter CIDR boundary, IPv6, X-Forwarded-For spoofing edge cases  _(30 min)_
**Context:** IpAllowlistGatewayFilter (8.8-T05) must correctly handle IPv6 CIDRs, /32 single-host CIDRs, malformed CIDR entries in DB, and multi-hop X-Forwarded-For headers. These are unit tests with mocked PartnerConfigCache and PartnerIdExtractor; no Testcontainers needed. Module: services/gateway.
**Steps:** Create services/gateway/src/test/java/com/gme/pay/gateway/filter/IpAllowlistGatewayFilterTest.java; Mock PartnerConfigCache and PartnerIdExtractor with Mockito; use MockServerWebExchange for request construction; Test: IPv6 CIDR 2001:db8::/32 matches 2001:db8::1 and rejects 2001:db9::1; Test: /32 CIDR 10.0.0.1/32 matches 10.0.0.1 and rejects 10.0.0.2; Test: X-Forwarded-For with 3 hops - uses first IP as client; Test: malformed CIDR string not-a-cidr in allowlist is skipped without exception; request allowed; WARN logged (verify with test Logback appender); Test: empty CIDR list allows all IPs; Test: /internal/v1/foo path skips filter even for blocked IP
**Deliverable:** services/gateway/src/test/java/com/gme/pay/gateway/filter/IpAllowlistGatewayFilterTest.java
**Acceptance / logic checks:**
- IPv6 boundary 2001:db8:ffff:ffff:ffff:ffff:ffff:ffff matches 2001:db8::/32
- Single /32: only exact IP matches, +1 address rejected
- Malformed CIDR: no exception thrown, WARN logged, request proceeds
- All 8 test cases pass in under 5 seconds (pure unit, no network)
**Depends on:** 8.8-T05

### 8.8-T07 — Implement PartnerRateLimitKeyResolver: per-partner Redis bucket key for Spring Cloud Gateway RequestRateLimiter  _(25 min)_
**Context:** Spring Cloud Gateway RequestRateLimiterGatewayFilterFactory needs a KeyResolver to assign requests to rate-limit buckets. GMEPay+ buckets per partner: key = partner:{partnerId} for authenticated requests, ip:{sourceIp} for unauthenticated (fallback). Uses PartnerIdExtractor (8.8-T04). Bean is annotated @Primary to override the default PrincipalNameKeyResolver. Module: services/gateway.
**Steps:** Create services/gateway/src/main/java/com/gme/pay/gateway/ratelimit/PartnerRateLimitKeyResolver.java implementing KeyResolver from Spring Cloud Gateway; Implement resolve(ServerWebExchange exchange): call PartnerIdExtractor.resolve; if partner present return Mono.just(partner: + partnerId); else extract source IP same logic as IpAllowlistFilter and return Mono.just(ip: + sourceIp); Ensure Mono is never empty (Mono.just always returned); Register as @Bean @Primary in GatewayRateLimitConfig @Configuration in same package
**Deliverable:** services/gateway/src/main/java/com/gme/pay/gateway/ratelimit/PartnerRateLimitKeyResolver.java and GatewayRateLimitConfig.java
**Acceptance / logic checks:**
- Request with valid X-Api-Key for partner_id=42 resolves to key partner:42
- Request with no X-Api-Key resolves to ip:<source-ip>
- Mono is never empty (key is always produced)
- @SpringBootTest context loads with exactly one @Primary KeyResolver bean
- Unit test with mocked PartnerIdExtractor covers both branches
**Depends on:** 8.8-T04

### 8.8-T08 — Implement DynamicRedisRateLimiter: per-partner, per-endpoint token bucket backed by Redis  _(45 min)_
**Context:** Spring Cloud Gateway RedisRateLimiter uses token bucket algorithm. GMEPay+ needs dynamic per-partner limits: replenish-rate and burst-capacity (=2x replenish-rate) are loaded from PartnerRateLimitConfig via PartnerConfigCache. Endpoint config key (rates/payments/global) selects the right field. Defaults when no DB row: globalRps=100, ratesRps=20, paymentsRps=50, cpmRps=50. Responses must include X-RateLimit-Limit, X-RateLimit-Remaining, X-RateLimit-Reset headers. HTTP 429 response body: {errorCode: RATE_LIMITED, message: Request exceeds rate limit} with Retry-After header. Module: services/gateway.
**Steps:** Create services/gateway/src/main/java/com/gme/pay/gateway/ratelimit/DynamicRedisRateLimiter.java extending RedisRateLimiter (spring-cloud-starter-gateway includes this); Override isAllowed(String routeId, String id): extract partnerId from id (strip partner: prefix); load PartnerRateLimitConfig from PartnerConfigCache; derive replenishRate and burstCapacity based on routeId suffix (rates->ratesRps, payments->paymentsRps, default->globalRps); call super or delegate to Redis Lua script with computed values; Register as @Bean in GatewayRateLimitConfig; configure includeHeaders=true so rate-limit headers are always included; Add a custom GatewayFilter in GatewayRateLimitConfig that sets response status 429 with JSON body {errorCode: RATE_LIMITED, message: Request exceeds rate limit} and Retry-After: {X-RateLimit-Reset value} when downstream sets 429; Fallback to defaults (100/20/50/50) if PartnerConfigCache throws; log WARN
**Deliverable:** services/gateway/src/main/java/com/gme/pay/gateway/ratelimit/DynamicRedisRateLimiter.java
**Acceptance / logic checks:**
- Partner with default config: replenishRate=100 for global route, 20 for rates route, 50 for payments route
- Custom partner_rate_limit_config.global_rps=200: replenishRate=200 returned for global route
- burstCapacity always equals 2 x replenishRate
- Response headers X-RateLimit-Limit, X-RateLimit-Remaining, X-RateLimit-Reset present on every response
- 429 response body contains errorCode=RATE_LIMITED and Retry-After header
**Depends on:** 8.8-T07, 8.8-T03

### 8.8-T09 — Implement AuthFailureTrackingFilter: IP-level block after 20 failed auth attempts in 10 minutes  _(40 min)_
**Context:** SEC-09 sec 5.4: failed authentication attempts per IP are limited to 20 per 10-minute window; on breach, the source IP is blocked for 15 minutes returning HTTP 429. Tracked in Redis keys authfail:ip:{ip} (counter, TTL 600s) and authblock:ip:{ip} (block flag, TTL 900s). The filter listens for AuthFailureEvent published by the HMAC validation filter. Module: services/gateway.
**Steps:** Create services/gateway/src/main/java/com/gme/pay/gateway/event/AuthFailureEvent.java as a Spring ApplicationEvent with field sourceIp String; Create services/gateway/src/main/java/com/gme/pay/gateway/filter/AuthFailureTrackingFilter.java implementing GlobalFilter and Ordered; order = HIGHEST_PRECEDENCE + 5 (before auth filter, so block check runs first); At start of filter: extract source IP; check Redis key authblock:ip:{ip}; if exists return HTTP 429 immediately with body {errorCode: RATE_LIMITED, message: IP temporarily blocked due to repeated authentication failures} and Retry-After = Redis TTL of block key; Proceed with chain; register an ApplicationEventListener<AuthFailureEvent>: on receive, increment INCR authfail:ip:{ip}; if not exists set EXPIRE 600; if count reaches 20 SET authblock:ip:{ip} 1 EX 900 and DEL authfail:ip:{ip}; The HMAC validation filter (out of scope for this ticket) publishes AuthFailureEvent(sourceIp) on invalid signature or unknown api_key
**Deliverable:** services/gateway/src/main/java/com/gme/pay/gateway/filter/AuthFailureTrackingFilter.java and AuthFailureEvent.java
**Acceptance / logic checks:**
- 20 failed auth events from IP 192.0.2.1 within 10 min: 21st request returns 429 even with valid API key
- After 15 min (simulate by deleting authblock key in test): requests allowed again
- Successful auth does not increment the counter
- Two different IPs have independent counters
- Testcontainers Redis 7 integration test; counter incremented via direct event publish in test
**Depends on:** 8.8-T04

### 8.8-T10 — Flyway migration: gateway_config_outbox table for audit event transactional outbox  _(20 min)_
**Context:** Config changes (IP allowlist add/remove, rate-limit override change) must be audit-logged atomically with the DB mutation using the transactional Outbox pattern. The outbox table is in the services/gateway PostgreSQL schema. Per GMEPay+ messaging approach, Phase 1 uses outbox + EventPublisher interface; Kafka is wired in the integration phase behind the same interface. Payload is JSONB containing actor, changed_at, entity_type, partner_id, previous_value, new_value.
**Steps:** Create services/gateway/src/main/resources/db/migration/V8003__gateway_outbox.sql with gateway_config_outbox(id BIGINT GENERATED ALWAYS AS IDENTITY PK, aggregate_type VARCHAR(50) NOT NULL, aggregate_id VARCHAR(100) NOT NULL, event_type VARCHAR(100) NOT NULL, payload JSONB NOT NULL, status VARCHAR(20) NOT NULL DEFAULT PENDING, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), processed_at TIMESTAMPTZ); Add CHECK(status IN (PENDING, PROCESSED, FAILED)); Add partial index idx_outbox_pending ON gateway_config_outbox(created_at ASC) WHERE status=PENDING; Document JSONB payload schema in a SQL comment: {actor: string, changed_at: ISO8601, entity_type: IP_ALLOWLIST|RATE_LIMIT, partner_id: long, previous_value: object, new_value: object}
**Deliverable:** services/gateway/src/main/resources/db/migration/V8003__gateway_outbox.sql
**Acceptance / logic checks:**
- Migration creates table with correct columns, CHECK on status, and PENDING default
- Partial index on status=PENDING is created
- JSONB payload column accepts and returns structured JSON
- flyway migrate sequence V8001 -> V8002 -> V8003 completes cleanly on Testcontainers PostgreSQL 16
- CHECK rejects status value INVALID
**Depends on:** 8.8-T01

### 8.8-T11 — Implement GatewayConfigAuditService: atomic DB + outbox write for IP allowlist and rate-limit changes  _(40 min)_
**Context:** When Ops adds/removes a CIDR or updates a rate-limit override, the mutation and outbox event must be written in a single ACID transaction (@Transactional). After commit, PartnerConfigCache.evict(partnerId) ensures allowlist changes propagate within 5 minutes (API-05 sec 3.3). CIDR limit: max 10 active entries per partner (API-05 sec 3.3). Module: services/gateway.
**Steps:** Create services/gateway/src/main/java/com/gme/pay/gateway/config/GatewayConfigAuditService.java as @Service with @Transactional; Implement addIpCidr(Long partnerId, String cidr, String actor): validate CIDR format (reject malformed); count active CIDRs for partner; if >=10 throw GatewayConfigException(CIDR_LIMIT_EXCEEDED) before any write; save IpAllowlistEntry; write outbox row with event_type=IP_ALLOWLIST_ADDED, payload containing actor, previous_value=existing list, new_value=list after add; call PartnerConfigCache.evict(partnerId); Implement removeIpCidr(Long partnerId, Long entryId, String actor): load entry; set isActive=FALSE; write outbox row event_type=IP_ALLOWLIST_REMOVED; evict cache; Implement upsertRateLimitConfig(Long partnerId, PartnerRateLimitConfigDto dto, String actor): validate all rps fields in range 1-10000; upsert row; write outbox row event_type=RATE_LIMIT_UPDATED with previous and new values; evict cache; Create GatewayConfigException with an errorCode String field (CIDR_LIMIT_EXCEEDED, INVALID_CIDR, etc.)
**Deliverable:** services/gateway/src/main/java/com/gme/pay/gateway/config/GatewayConfigAuditService.java and GatewayConfigException.java
**Acceptance / logic checks:**
- addIpCidr success: IpAllowlistEntry row present and outbox row present with correct event_type and payload in same transaction
- Rollback test: mock OutboxRepository to throw after IpAllowlistEntry save; assert neither row committed (count unchanged)
- 11th CIDR attempt throws CIDR_LIMIT_EXCEEDED and zero DB rows written
- Outbox payload JSON contains actor, entity_type, partner_id, previous_value and new_value fields
- Testcontainers PostgreSQL 16 + mocked ReactiveRedisTemplate for evict
**Depends on:** 8.8-T10, 8.8-T03

### 8.8-T12 — Implement GatewayAdminController: REST admin endpoints for IP allowlist and rate-limit management  _(40 min)_
**Context:** GME Ops manages IP allowlists and rate-limit overrides via an internal REST API. Endpoints: POST/GET/DELETE /internal/v1/partners/{partnerId}/ip-allowlist and GET/PUT /internal/v1/partners/{partnerId}/rate-limits. These are on a separate internal route not exposed through the public partner gateway. OAuth2/JWT + RBAC role GATEWAY_ADMIN required. Module: services/gateway. Uses GatewayConfigAuditService (8.8-T11).
**Steps:** Create services/gateway/src/main/java/com/gme/pay/gateway/admin/GatewayAdminController.java as @RestController @RequestMapping(/internal/v1/partners); POST /{partnerId}/ip-allowlist body {cidr, description}: call addIpCidr; return 201 with IpAllowlistEntryDto; catch CIDR_LIMIT_EXCEEDED -> 422; catch INVALID_CIDR -> 400; GET /{partnerId}/ip-allowlist: return active CIDRs as List<IpAllowlistEntryDto>; DELETE /{partnerId}/ip-allowlist/{entryId}: call removeIpCidr; return 204; 404 if entry not found or not owned by partnerId; GET /{partnerId}/rate-limits: return current config (or defaults if no row); PUT /{partnerId}/rate-limits body PartnerRateLimitConfigDto {globalRps, ratesRps, paymentsRps, cpmRps, globalRpm, quoteRpm}: validate 1-10000; call upsertRateLimitConfig; return 200; Secure all endpoints with @PreAuthorize(hasRole(GATEWAY_ADMIN)); add Spring Security OAuth2 resource server config
**Deliverable:** services/gateway/src/main/java/com/gme/pay/gateway/admin/GatewayAdminController.java
**Acceptance / logic checks:**
- POST valid CIDR returns 201; GET lists it; DELETE sets inactive and GET no longer includes it
- POST 11th CIDR returns 422 with errorCode CIDR_LIMIT_EXCEEDED
- PUT with globalRps=0 returns 400 (Bean Validation @Min(1))
- Request without GATEWAY_ADMIN JWT role returns 403
- @WebMvcTest or @WebFluxTest slice with MockMvc covers all five endpoints
**Depends on:** 8.8-T11

### 8.8-T13 — Spring Cloud Gateway route configuration: wire all filters to partner and internal routes in application.yml  _(35 min)_
**Context:** The gateway application.yml must declare routes attaching: IpAllowlistGatewayFilter (GlobalFilter auto-applied), RequestRateLimiter with DynamicRedisRateLimiter and PartnerRateLimitKeyResolver per route, and AuthFailureTrackingFilter (GlobalFilter auto-applied). Three partner routes (rates, payments, global) each use the appropriate rate-limit config key. Internal /internal/v1/** route bypasses partner rate limiting and IP allowlist. Module: services/gateway.
**Steps:** Edit services/gateway/src/main/resources/application.yml: add spring.cloud.gateway.routes section; Route id=partner-rates: predicates Path=/v1/rates/**, filters RequestRateLimiter with key-resolver=#{@partnerRateLimitKeyResolver}, rate-limiter=#{@dynamicRedisRateLimiter}, with metadata endpoint-key=rates; Route id=partner-payments: predicates Path=/v1/payments/**, filters RequestRateLimiter with endpoint-key=payments; Route id=partner-global: predicates not(Path=/v1/rates/**) and not(Path=/v1/payments/**) under /v1/**, filters RequestRateLimiter with endpoint-key=global; Route id=internal-admin: predicates Path=/internal/v1/**, no RequestRateLimiter filter; add RemoteAddr predicate to restrict to cluster-internal IP range (e.g. 10.0.0.0/8) configurable via ${gateway.internal.allowed-cidr}; Confirm IpAllowlistGatewayFilter and AuthFailureTrackingFilter are GlobalFilters and therefore auto-applied to all routes; add a condition to skip both for /internal/v1/** paths
**Deliverable:** services/gateway/src/main/resources/application.yml (gateway.routes section)
**Acceptance / logic checks:**
- POST /v1/rates routes through partner-rates route and applies rates rate-limit bucket
- POST /v1/payments routes through partner-payments route and applies payments bucket
- POST /internal/v1/partners/1/ip-allowlist is not subject to partner rate-limit filter
- /actuator/gateway/routes actuator shows all four routes defined
- Integration test with WebTestClient confirms filter application order: IP check -> auth-failure block -> rate limit -> routing
**Depends on:** 8.8-T05, 8.8-T08, 8.8-T09, 8.8-T12

### 8.8-T14 — Implement GatewayOutboxPoller: poll gateway_config_outbox and publish events via EventPublisher  _(35 min)_
**Context:** The transactional Outbox table gateway_config_outbox (8.8-T10) must be polled every 5 seconds. Each PENDING row is dispatched via the EventPublisher interface from lib-events (Phase 1 = in-process; Kafka wired in integration phase). On success the row is marked PROCESSED. Uses SELECT ... FOR UPDATE SKIP LOCKED to support multiple gateway instances. Module: services/gateway.
**Steps:** Create services/gateway/src/main/java/com/gme/pay/gateway/outbox/GatewayOutboxPoller.java as @Component with @Scheduled(fixedDelayString=#{gateway.outbox.poll-interval-ms:5000}); Query via JPQL or native SQL: SELECT * FROM gateway_config_outbox WHERE status=PENDING ORDER BY created_at LIMIT 100 FOR UPDATE SKIP LOCKED; use @Lock(PESSIMISTIC_WRITE) or native query; For each row: deserialize payload JSON to GatewayConfigChangedEvent; call EventPublisher.publish(event); on success update status=PROCESSED, processed_at=now(); on exception update status=FAILED (or leave PENDING depending on retry policy - use PENDING for retry); Create lib-events/src/main/java/com/gme/pay/events/gateway/GatewayConfigChangedEvent.java as a record with fields: eventId, entityType, partnerId, actor, changedAt, previousValue (Map), newValue (Map); Add @ConditionalOnProperty(name=gateway.outbox.enabled, havingValue=true, matchIfMissing=true) to allow disabling in unit tests
**Deliverable:** services/gateway/src/main/java/com/gme/pay/gateway/outbox/GatewayOutboxPoller.java and lib-events/src/main/java/com/gme/pay/events/gateway/GatewayConfigChangedEvent.java
**Acceptance / logic checks:**
- PENDING row is processed and marked PROCESSED within 10 seconds of insert
- Two concurrent poller instances do not double-process the same row (FOR UPDATE SKIP LOCKED test with two threads and Testcontainers PostgreSQL)
- Exception during publish leaves row as PENDING for retry
- EventPublisher.publish receives GatewayConfigChangedEvent with correct entityType and partnerId
- @Scheduled not triggered when gateway.outbox.enabled=false
**Depends on:** 8.8-T10

### 8.8-T15 — Integration test: end-to-end IP allowlist enforcement (Testcontainers PostgreSQL + Redis + WireMock)  _(45 min)_
**Context:** Full integration test for the IP allowlist path in services/gateway: partner CIDR written to PostgreSQL, cached in Redis, enforced by IpAllowlistGatewayFilter. WireMock stubs the downstream payment service. Tests the add-CIDR admin flow and the 5-minute propagation guarantee via explicit cache evict.
**Steps:** Create services/gateway/src/test/java/com/gme/pay/gateway/integration/IpAllowlistIntegrationTest.java with @SpringBootTest(webEnvironment=RANDOM_PORT) and @Testcontainers; Provision PostgreSQL 16 and Redis 7 via Testcontainers; run all Flyway migrations; Use @Sql to insert partner row and one IP allowlist entry CIDR 192.168.1.0/24; Start WireMock server; stub POST /v1/rates -> 200 OK; set downstream URI in application properties; Test 1: POST /v1/rates with X-Forwarded-For: 192.168.1.50 and valid X-Api-Key -> assert 200; Test 2: same request with X-Forwarded-For: 10.0.0.1 -> assert 403 body errorCode=IP_NOT_ALLOWLISTED; Test 3: call admin endpoint POST /internal/v1/partners/{id}/ip-allowlist with cidr=10.0.0.0/24; then retry 10.0.0.1 request -> assert 200 (cache evicted by admin call); Test 4: partner with no CIDR entries: any IP passes
**Deliverable:** services/gateway/src/test/java/com/gme/pay/gateway/integration/IpAllowlistIntegrationTest.java
**Acceptance / logic checks:**
- IP in CIDR: 200 forwarded to WireMock
- IP not in CIDR: 403 with errorCode=IP_NOT_ALLOWLISTED
- After admin add of new CIDR, previously blocked IP passes (cache evict verified)
- No-CIDR partner: all IPs pass
- Test suite completes in under 90 seconds
**Depends on:** 8.8-T13, 8.8-T12

### 8.8-T16 — Integration test: end-to-end rate limiting (Testcontainers Redis + PostgreSQL), verifying 429 and headers  _(45 min)_
**Context:** Full integration test for rate-limiting in services/gateway: token buckets in Redis, endpoint-specific limits, correct response headers, and per-partner custom overrides. Tests default limits (ratesRps=20) and a custom partner_rate_limit_config.
**Steps:** Create services/gateway/src/test/java/com/gme/pay/gateway/integration/RateLimitIntegrationTest.java with @SpringBootTest and Testcontainers Redis 7 + PostgreSQL 16; Insert partner A with no partner_rate_limit_config (defaults apply: globalRps=100, ratesRps=20); Send 21 rapid POST /v1/rates requests for partner A; assert 21st returns 429 with Retry-After header and body {errorCode: RATE_LIMITED}; Assert first 20 responses include X-RateLimit-Limit=20, X-RateLimit-Remaining (decrementing from 19 to 0), X-RateLimit-Reset (epoch second in future); Insert partner_rate_limit_config for partner B with ratesRps=5; send 6 requests; assert 6th is 429; Verify partner A and partner B rate buckets are independent (partner A exhausting its rate does not affect partner B)
**Deliverable:** services/gateway/src/test/java/com/gme/pay/gateway/integration/RateLimitIntegrationTest.java
**Acceptance / logic checks:**
- Default ratesRps=20: 21st /v1/rates request is 429
- X-RateLimit-Remaining decrements from 19 to 0 across 20 requests
- Custom ratesRps=5 partner: 6th request is 429
- Partner A and B buckets independent
- Retry-After is a valid epoch second greater than current time
**Depends on:** 8.8-T13

### 8.8-T17 — Unit tests: GatewayConfigAuditService rollback, CIDR limit, and outbox payload correctness  _(35 min)_
**Context:** GatewayConfigAuditService (8.8-T11) has critical transactional and business-rule invariants. These are tested with @DataJpaTest (Testcontainers PostgreSQL) for DB-level assertions and Mockito for PartnerConfigCache. Focus: atomic rollback on outbox failure, CIDR limit at exactly 10, and outbox payload JSON completeness.
**Steps:** Create services/gateway/src/test/java/com/gme/pay/gateway/config/GatewayConfigAuditServiceTest.java with @SpringBootTest and Testcontainers PostgreSQL 16; Test addIpCidr success: verify IpAllowlistEntry row saved, outbox row saved with event_type=IP_ALLOWLIST_ADDED and payload containing actor, partner_id, previous_value, new_value; Test rollback: replace OutboxRepository bean with a mock that throws after IpAllowlistEntry save; call addIpCidr; assert IpAllowlistEntry count unchanged and outbox count unchanged; Test CIDR limit: insert 10 active CIDRs; call addIpCidr for 11th; assert GatewayConfigException thrown with errorCode CIDR_LIMIT_EXCEEDED; assert count still 10; Test removeIpCidr: entry is_active set to FALSE; outbox row event_type=IP_ALLOWLIST_REMOVED; Test upsertRateLimitConfig: second call updates existing row (not insert), outbox event written with previous and new values
**Deliverable:** services/gateway/src/test/java/com/gme/pay/gateway/config/GatewayConfigAuditServiceTest.java
**Acceptance / logic checks:**
- Rollback test: zero new rows in both partner_ip_allowlist and gateway_config_outbox after mock throws
- 11th CIDR: count stays 10, exception carries errorCode=CIDR_LIMIT_EXCEEDED
- Outbox payload JSON deserializes to GatewayConfigChangedEvent with all required fields non-null
- Upsert: partner_rate_limit_config row count stays 1 after two consecutive upserts
- Test suite completes in under 60 seconds
**Depends on:** 8.8-T11

### 8.8-T18 — Unit tests: DynamicRedisRateLimiter endpoint-key routing and default fallback  _(25 min)_
**Context:** DynamicRedisRateLimiter (8.8-T08) must select the correct rate-limit field based on endpoint-key (rates->ratesRps, payments->paymentsRps, global->globalRps) and return 2x as burst-capacity. When PartnerConfigCache throws, defaults must be returned without propagating the exception. Pure unit test with mocked PartnerConfigCache.
**Steps:** Create services/gateway/src/test/java/com/gme/pay/gateway/ratelimit/DynamicRedisRateLimiterTest.java; Mock PartnerConfigCache to return config with globalRps=200, ratesRps=30, paymentsRps=80; Test endpoint-key=rates: replenishRate=30, burstCapacity=60; Test endpoint-key=payments: replenishRate=80, burstCapacity=160; Test endpoint-key=global (or unknown key): replenishRate=200, burstCapacity=400; Test no DB row (default config): replenishRate=100, burstCapacity=200; Test PartnerConfigCache throws RuntimeException: replenishRate=100, burstCapacity=200 (default fallback, no exception propagated); Test burst is always exactly 2x replenishRate for a custom config
**Deliverable:** services/gateway/src/test/java/com/gme/pay/gateway/ratelimit/DynamicRedisRateLimiterTest.java
**Acceptance / logic checks:**
- rates key uses ratesRps=30 not globalRps=200
- burstCapacity=2x replenishRate in all 7 scenarios
- RuntimeException in cache lookup: defaults returned, no exception thrown
- All tests complete in under 3 seconds (pure unit, no containers)
**Depends on:** 8.8-T08

### 8.8-T19 — Unit tests: AuthFailureTrackingFilter counter increment, block activation, and IP isolation  _(30 min)_
**Context:** AuthFailureTrackingFilter (8.8-T09) counter and block logic must be correct. Test: counter increments on each AuthFailureEvent; at count=20 block is activated; different IPs are isolated; successful auth does not increment. Uses Testcontainers Redis 7 for counter/block key assertions.
**Steps:** Create services/gateway/src/test/java/com/gme/pay/gateway/filter/AuthFailureTrackingFilterTest.java with Testcontainers Redis 7; Inject ApplicationEventPublisher and publish AuthFailureEvent(192.0.2.1) 19 times; assert Redis key authblock:ip:192.0.2.1 absent; Publish 20th event; assert authblock:ip:192.0.2.1 exists with TTL between 895 and 905 seconds; assert authfail counter key deleted; Test next request from 192.0.2.1 returns 429 immediately (simulate via MockServerWebExchange through filter); Test 20 events from 192.0.2.2 do not affect 192.0.2.1 bucket; Test: after block key expires (delete it manually in test), request from 192.0.2.1 is allowed; Test: event is not published on successful auth (counter key for that IP stays absent)
**Deliverable:** services/gateway/src/test/java/com/gme/pay/gateway/filter/AuthFailureTrackingFilterTest.java
**Acceptance / logic checks:**
- 19 failures: no block key in Redis
- 20th failure: authblock key present, TTL ~900s, counter key deleted
- Blocked IP returns 429 from filter before reaching downstream
- Two IPs have independent counters
- Test suite completes in under 30 seconds
**Depends on:** 8.8-T09

### 8.8-T20 — OpenAPI documentation: gateway-admin.yaml for IP allowlist and rate-limit management endpoints  _(30 min)_
**Context:** The internal admin endpoints (8.8-T12) must be documented in openapi/gateway-admin.yaml following the same conventions as openapi/partner-api.yaml. This enables the Ops/Admin BFF (React/Next.js frontend) to generate typed API clients. Error responses use the canonical error model from lib-errors (errorCode + message fields).
**Steps:** Create openapi/gateway-admin.yaml with openapi: 3.1.0, info (title: GMEPay+ Gateway Admin API, version: 1.0.0), servers (url: /internal/v1), security scheme OAuth2 Bearer; Add path POST /partners/{partnerId}/ip-allowlist: request body schema {cidr: string, description: string}, responses 201 (IpAllowlistEntryDto), 400 (invalid CIDR), 409 (duplicate), 422 (CIDR_LIMIT_EXCEEDED); Add path GET /partners/{partnerId}/ip-allowlist: response 200 array of IpAllowlistEntryDto (id, partnerId, cidrRange, isActive, createdAt, createdBy); Add path DELETE /partners/{partnerId}/ip-allowlist/{entryId}: response 204, 404; Add path GET /partners/{partnerId}/rate-limits: response 200 PartnerRateLimitConfigDto; Add path PUT /partners/{partnerId}/rate-limits: request body PartnerRateLimitConfigDto {globalRps: int min 1 max 10000, ratesRps, paymentsRps, cpmRps, globalRpm, quoteRpm}, response 200; Define reusable ErrorResponse component schema with fields errorCode (string) and message (string) from lib-errors canonical model
**Deliverable:** openapi/gateway-admin.yaml
**Acceptance / logic checks:**
- YAML validates with swagger-parser or redocly lint with zero errors
- All six endpoints documented with correct HTTP methods and paths
- PartnerRateLimitConfigDto fields show minimum=1 and maximum=10000 constraints
- IpAllowlistEntryDto schema matches Java entity fields exactly
- ErrorResponse schema referenced in all 4xx response definitions
**Depends on:** 8.8-T12

### 8.8-T21 — Observability: Micrometer metrics and MDC structured logging in gateway filters  _(35 min)_
**Context:** GMEPay+ observability stack: OpenTelemetry -> Prometheus + Grafana (metrics), ELK (logs). Gateway filters must emit Micrometer counters and timers to Prometheus and include MDC correlation fields (partner_id, request_id) in all log entries. Metric names: ip_allowlist_blocked_total (tag: partner_id), rate_limit_exceeded_total (tags: partner_id, endpoint), auth_failure_blocked_total (tag: source_ip), ip_allowlist_check_duration_seconds (timer), rate_limit_check_duration_seconds (timer). Module: services/gateway.
**Steps:** Inject MeterRegistry into IpAllowlistGatewayFilter: increment Counter ip_allowlist_blocked_total with tag partner_id on each 403 block; wrap CIDR match loop in Timer.Sample for ip_allowlist_check_duration_seconds; Inject MeterRegistry into DynamicRedisRateLimiter or the 429 response filter: increment Counter rate_limit_exceeded_total with tags partner_id and endpoint on each 429; Inject MeterRegistry into AuthFailureTrackingFilter: increment auth_failure_blocked_total with tag source_ip on each IP block trigger (when count reaches 20); In a GatewayFilter registered at HIGHEST_PRECEDENCE: set MDC partner_id (from X-Api-Key resolution), request_id (from X-Request-ID header or generate UUID); clear MDC in doFinally; Verify /actuator/prometheus endpoint exposes all five metric names
**Deliverable:** Updated IpAllowlistGatewayFilter.java, DynamicRedisRateLimiter.java, and AuthFailureTrackingFilter.java with Micrometer instrumentation; MDC filter in services/gateway/src/main/java/com/gme/pay/gateway/filter/MdcPopulatingFilter.java
**Acceptance / logic checks:**
- One blocked IP-allowlist request: ip_allowlist_blocked_total{partner_id=42} = 1 at /actuator/prometheus
- One rate-limited request to /v1/rates: rate_limit_exceeded_total{partner_id=42,endpoint=/v1/rates} = 1
- Timer histogram buckets for ip_allowlist_check_duration_seconds present in Prometheus output
- Log entry for a blocked request contains JSON fields partner_id and request_id (test with Logback ListAppender)
- MDC cleared after each request: subsequent test request with different partner_id shows correct partner_id in logs
**Depends on:** 8.8-T05, 8.8-T08, 8.8-T09


## WBS 8.9 — OpenAPI finalization & sandbox
### 8.9-T01 — Scaffold openapi/partner-api.yaml skeleton with all 7 v1 endpoints  _(25 min)_
**Context:** WBS 8.9: OpenAPI finalisation and sandbox. The Partner API (API-05) exposes 7 endpoints under /v1: POST /v1/rates, POST /v1/payments, POST /v1/payments/cpm/generate, GET /v1/payments/{id}, POST /v1/payments/{id}/cancel, GET /v1/merchants/{qr}, GET /v1/balance. The YAML lives at openapi/partner-api.yaml in the monorepo root. It must declare info (title: GMEPay+ Partner API, version: 1.0.0), servers (https://api-sandbox.gmepayplus.com, https://api.gmepayplus.com), and stub path entries (no request/response schemas yet) for all 7 endpoints.
**Steps:** Create openapi/partner-api.yaml at monorepo root with openapi: 3.1.0 header; Add info block: title, version 1.0.0, description referencing API-05; Add servers block with sandbox and production base URLs; Add paths block with stub entries for all 7 endpoints (method + operationId only); Validate YAML is well-formed with a linter (e.g. gradle openApiValidate task or redocly lint)
**Deliverable:** openapi/partner-api.yaml - valid OpenAPI 3.1 skeleton with 7 path stubs
**Acceptance / logic checks:**
- File parses without errors under openapi-generator or redocly lint
- All 7 operationIds present: getRateQuote, executeFixedMpmPayment, generateCpmToken, getPaymentStatus, cancelPayment, resolveMerchantQr, getPrefundBalance
- servers block contains both https://api-sandbox.gmepayplus.com and https://api.gmepayplus.com
- info.version = 1.0.0

### 8.9-T02 — Define shared components: Money, ErrorEnvelope, ErrorDetail, pagination Meta schemas  _(25 min)_
**Context:** API-05 §2.5 and §8.1. Money is two fields: amount (string decimal) and currency (ISO-4217). Error envelope: {error:{code,message,request_id,details:[{field,issue}]}}. Pagination meta: {total_count,has_more,next_cursor,prev_cursor}. All defined as reusable schemas under components/schemas in openapi/partner-api.yaml. Also add reusable header components: IdempotencyKey (required, UUID 16-128 chars), X-Timestamp (ISO-8601 UTC ms), X-Signature (HMAC-SHA256 64-char hex), X-Request-ID (response header).
**Steps:** Open openapi/partner-api.yaml; Add components/schemas: MoneyAmount (amount string decimal pattern, currency 3-char string); Add ErrorDetail (field, issue), ErrorEnvelope (error object with code, message, request_id, details array); Add PaginationMeta (total_count integer, has_more boolean, next_cursor nullable, prev_cursor nullable); Add components/parameters: IdempotencyKey (header, required, minLength 16, maxLength 128), XTimestamp, XSignature; Run linter to verify 0 errors
**Deliverable:** components/schemas and components/parameters block in openapi/partner-api.yaml with all shared types
**Acceptance / logic checks:**
- MoneyAmount has required: [amount, currency]; amount pattern allows '50000' and '35.77'
- ErrorEnvelope error.details is type array with items ref to ErrorDetail
- PaginationMeta has_more is boolean not string
- XSignature parameter pattern is ^[0-9a-f]{64}$
- Linter reports 0 errors
**Depends on:** 8.9-T01

### 8.9-T03 — Add request/response schemas for POST /v1/rates in partner-api.yaml  _(35 min)_
**Context:** API-05 §4.2. Request fields: target_payout (string, required), payout_currency (string ISO-4217, required), scheme_id (string, required), direction (enum domestic|inbound|outbound|hub, required), merchant_qr (string, optional), partner_ref (string, optional). Response 200: quote_id, offer_rate, send_amount, service_charge, service_charge_currency, collection_usd (nullable, cross-border only), payout_usd_cost (nullable), collection_margin_usd (nullable), payout_margin_usd (nullable), cross_rate (nullable), valid_until (ISO-8601 UTC), scheme_id, direction, partner_ref. USD pool fields omitted for domestic transactions. Also add 400/404/422/429 responses referencing ErrorEnvelope.
**Steps:** Define RateQuoteRequest schema under components/schemas; Define RateQuoteResponse schema; USD pool fields nullable; Wire into paths./v1/rates.post requestBody and responses; Reference IdempotencyKey and auth headers as parameters; Run linter
**Deliverable:** POST /v1/rates operation fully typed in openapi/partner-api.yaml
**Acceptance / logic checks:**
- direction enum has exactly 4 values: domestic, inbound, outbound, hub
- valid_until format is date-time
- collection_usd marked nullable: true
- 400, 404, 422, 429 responses all reference ErrorEnvelope
- Example from API-05 §4.2 embedded as examples block (target_payout=50000 KRW inbound to ZeroPay)
**Depends on:** 8.9-T02

### 8.9-T04 — Add request/response schemas for POST /v1/payments in partner-api.yaml  _(35 min)_
**Context:** API-05 §4.3. Request: quote_id (required), merchant_qr (required), direction (required), scheme_id (required), customer_ref (required, tokenised no PII), partner_txn_ref (required, unique per partner), collection_amount (string decimal, required), collection_currency (required), country_code (optional ISO-3166-1 alpha-2). Response 201: payment_id, status enum(approved|pending|failed), scheme_txn_id, merchant_name, merchant_id, target_payout, payout_currency, offer_rate, collection_amount, collection_currency, service_charge, service_charge_currency, prefund_deducted_usd (nullable OVERSEAS only), partner_txn_ref, created_at, approved_at (nullable). HTTP codes: 201, 202, 400, 402, 404, 409, 422, 429.
**Steps:** Define FixedMpmPaymentRequest schema; Define FixedMpmPaymentResponse schema with prefund_deducted_usd nullable; Wire into paths./v1/payments.post with Idempotency-Key header parameter; Add all documented HTTP status responses referencing ErrorEnvelope; Run linter
**Deliverable:** POST /v1/payments operation fully typed in openapi/partner-api.yaml
**Acceptance / logic checks:**
- partner_txn_ref marked required in request schema
- prefund_deducted_usd nullable (LOCAL partners do not receive it)
- 202 response documented alongside 201 (async pending case)
- 409 response description references DUPLICATE_PARTNER_TXN_REF
- 402 response description references INSUFFICIENT_PREFUNDING
**Depends on:** 8.9-T03

### 8.9-T05 — Add schemas for POST /v1/payments/cpm/generate and GET /v1/payments/{id}  _(35 min)_
**Context:** API-05 §4.4 and §4.5. CPM generate request: scheme_id, direction, customer_ref, partner_txn_ref (all required), country_code (required for CPM), prefund_reserve_usd (optional). CPM response 201: cpm_token_id, prepare_token, qr_content, expires_at (ISO-8601; token valid 60 s), prefund_reserved_usd (nullable), payment_id, scheme_id, partner_txn_ref, created_at. GET /v1/payments/{id} path param id. Response 200: full payment object with updated_at and cancelled_at (nullable). Status enum extended to: pending, approved, failed, cancelled, uncertain.
**Steps:** Define CpmGenerateRequest and CpmGenerateResponse schemas; Wire into paths./v1/payments/cpm/generate.post; Define PaymentDetailResponse schema (superset of FixedMpmPaymentResponse with updated_at, cancelled_at, status including uncertain); Wire into paths./v1/payments/{id}.get with path param id; Add 404 response for GET; Run linter
**Deliverable:** POST /v1/payments/cpm/generate and GET /v1/payments/{id} operations typed in openapi/partner-api.yaml
**Acceptance / logic checks:**
- CPM expires_at format date-time with description noting 60 s validity
- status enum in PaymentDetailResponse has 5 values: pending, approved, failed, cancelled, uncertain
- cancelled_at nullable in PaymentDetailResponse
- GET /v1/payments/{id} has no requestBody
- prefund_reserved_usd nullable (LOCAL partners get null)
**Depends on:** 8.9-T04

### 8.9-T06 — Add schemas for POST cancel, GET merchant, GET balance endpoints to complete all 7 ops  _(35 min)_
**Context:** API-05 §4.6-4.8. Cancel request: reason (enum customer_request|merchant_request|timeout|other, required), reason_detail (string max 200 chars, optional). Cancel response 200: payment_id, status=cancelled, cancelled_at, prefund_returned_usd (nullable). GET /v1/merchants/{qr}: merchant_id, merchant_name, merchant_type, scheme_id, qr_code, status (active|inactive), payout_currency, address. HTTP 404 MERCHANT_NOT_FOUND, 422 inactive. GET /v1/balance: partner_id, balance_usd, low_balance_threshold_usd, is_below_threshold (boolean), as_of, recent_deductions array (when include_history=true). Deduction item: payment_id, amount_usd, balance_after_usd, event_at. LOCAL partner returns 403.
**Steps:** Define CancelPaymentRequest and CancelPaymentResponse schemas; wire cancel op; Define MerchantDetailResponse; wire GET /v1/merchants/{qr}; Define PrefundBalanceResponse and BalanceDeductionEvent schemas; Wire GET /v1/balance with optional query param include_history (boolean); Run linter - must report 0 errors with all 7 paths complete
**Deliverable:** Three remaining operations in openapi/partner-api.yaml; all 7 endpoints complete and linting clean
**Acceptance / logic checks:**
- reason enum has exactly 4 values
- MerchantDetailResponse status enum: active, inactive
- balance 403 response documented with FORBIDDEN error code
- include_history query param is boolean and not required
- is_below_threshold type is boolean in schema
**Depends on:** 8.9-T05

### 8.9-T07 — Add webhook event schemas to partner-api.yaml (5 event types)  _(35 min)_
**Context:** API-05 §6.5. All events share envelope: {event_id, event_type, created_at, partner_id, data:{...}}. Event types: payment.approved (includes offer_rate, target_payout, payout_currency, collection_amount, collection_currency, service_charge, service_charge_currency, prefund_deducted_usd nullable, approved_at, scheme_txn_id, merchant_id, merchant_name, direction, scheme_id, partner_txn_ref, payment_id). payment.pending_debit (CPM only; estimated_collection_amount, cpm_token_id). payment.failed (failure_code, failure_message, prefund_returned_usd nullable, failed_at). payment.cancelled (reason, prefund_returned_usd nullable, cancelled_at). settlement.completed (settlement_id, settlement_date, scheme_id, total_transactions, total_payout_krw, total_prefund_deducted_usd, batch_file_ref, settled_at). Webhook signing headers: X-GME-Webhook-Signature (sha256= prefix), X-GME-Webhook-Timestamp, X-GME-Event-ID.
**Steps:** Define WebhookEnvelope base schema (event_id, event_type enum 5 values, created_at, partner_id, data); Define 5 data sub-schemas as named components; Add webhook signing headers to components/headers; Reference webhook schemas from a webhooks block or x-webhooks extension in YAML; Run linter
**Deliverable:** 5 webhook event schemas in openapi/partner-api.yaml components/schemas; linting clean
**Acceptance / logic checks:**
- payment.approved data includes prefund_deducted_usd nullable
- payment.pending_debit uses estimated_collection_amount not collection_amount
- settlement.completed includes batch_file_ref string field
- WebhookEnvelope event_type enum has exactly 5 values
- Webhook signing headers documented in components/headers with correct format descriptions
**Depends on:** 8.9-T06

### 8.9-T08 — Configure openapi-generator Gradle task to generate Java DTOs into lib-api-contracts  _(40 min)_
**Context:** Stack: Gradle multi-module monorepo, Java 21, Spring Boot 3.x. libs/lib-api-contracts is the shared module. Use openapi-generator-gradle-plugin (org.openapi.generator) to generate DTO model classes from openapi/partner-api.yaml. Generator: spring with library=spring-boot. Output package: com.gme.pay.api.contracts.model. Generated sources go to libs/lib-api-contracts/build/generated/openapi/src/main/java. Set generateApiTests=false, generateModelTests=false. Add useJakartaEe=true for Spring Boot 3.x compatibility. The module exposes model classes only; no controller stubs.
**Steps:** Add openapi-generator-gradle-plugin to libs/lib-api-contracts/build.gradle; Configure openApiGenerate task: generatorName=spring, inputSpec path to openapi/partner-api.yaml, outputDir=build/generated/openapi, modelPackage=com.gme.pay.api.contracts.model, apiPackage=com.gme.pay.api.contracts.api, useJakartaEe=true, generateApiTests=false; Add sourceSets.main.java.srcDir pointing to generated output; Wire compileJava.dependsOn openApiGenerate; Run ./gradlew :libs:lib-api-contracts:openApiGenerate to verify compile
**Deliverable:** libs/lib-api-contracts/build.gradle with working openapi-generator task; generated DTOs compile under com.gme.pay.api.contracts.model
**Acceptance / logic checks:**
- ./gradlew :libs:lib-api-contracts:build succeeds without errors
- Generated class RateQuoteRequest.java exists under build/generated/openapi
- Generated class FixedMpmPaymentRequest.java exists with partner_txn_ref field
- Generated class ErrorEnvelope.java exists
- No hand-written Java in libs/lib-api-contracts/src/main/java (all from generator)
**Depends on:** 8.9-T07

### 8.9-T09 — Wire lib-api-contracts as dependency into svc-payment-executor and svc-rate-fx  _(25 min)_
**Context:** Stack: Gradle multi-module monorepo. Services svc-payment-executor and svc-rate-fx need the generated DTOs from libs/lib-api-contracts to use as @RequestBody and response types in @RestController methods. Add implementation(project(':libs:lib-api-contracts')) to each service's build.gradle. Verify no duplicate spring-web conflict (lib-api-contracts must not transitively pull in a second copy of spring-webmvc).
**Steps:** Open services/svc-payment-executor/build.gradle; add implementation(project(':libs:lib-api-contracts')); Open services/svc-rate-fx/build.gradle; add implementation(project(':libs:lib-api-contracts')); Run ./gradlew :services:svc-payment-executor:compileJava and :services:svc-rate-fx:compileJava; Verify no duplicate spring-webmvc JAR via ./gradlew :services:svc-rate-fx:dependencyInsight --dependency spring-webmvc; Add a stub import of RateQuoteRequest in svc-rate-fx to confirm compile-time resolution
**Deliverable:** Updated build.gradle for svc-payment-executor and svc-rate-fx; both compile clean
**Acceptance / logic checks:**
- ./gradlew :services:svc-payment-executor:compileJava exits 0
- ./gradlew :services:svc-rate-fx:compileJava exits 0
- import com.gme.pay.api.contracts.model.RateQuoteRequest resolves in svc-rate-fx
- ./gradlew dependencyInsight shows single version of spring-webmvc (no duplicate)
- import com.gme.pay.api.contracts.model.ErrorEnvelope resolves in svc-payment-executor
**Depends on:** 8.9-T08

### 8.9-T10 — Implement HMAC request signing filter in svc-payment-executor (Spring Security OncePerRequestFilter)  _(55 min)_
**Context:** API-05 §3.2 and §3.6. Every request must carry X-API-Key, X-Timestamp (ISO-8601 UTC ms precision), X-Signature (HMAC-SHA256 hex over canonical string). Canonical string = HTTP_METHOD + LF + PATH_WITH_QUERY + LF + X-Timestamp_value + LF + SHA256_HEX_OF_BODY. Reject if |serverTime - X-Timestamp| > 300 s with 401 TIMESTAMP_OUT_OF_RANGE. Replay protection: store (partner_id, X-Signature) in Redis key partner:{id}:sig:{hex} with 10-min TTL; reject duplicate with 401 INVALID_SIGNATURE. API secret fetched from Vault via Spring Cloud Vault. File: services/svc-payment-executor/src/main/java/com/gme/pay/executor/security/HmacRequestSigningFilter.java. Secrets hashed at rest with bcrypt cost 12.
**Steps:** Create HmacRequestSigningFilter extending OncePerRequestFilter; inject PartnerCredentialService, RedisTemplate<String,String>, Clock; Wrap request with ContentCachingRequestWrapper to allow body re-read; Compute canonical string; compute HMAC-SHA256 using javax.crypto.Mac with HmacSHA256; Check timestamp window (300 s); return 401 ErrorEnvelope TIMESTAMP_OUT_OF_RANGE if outside; Check Redis replay key; reject with 401 INVALID_SIGNATURE if found; store key with 10-min TTL; Register filter in SecurityFilterChain before UsernamePasswordAuthenticationFilter at order -100
**Deliverable:** services/svc-payment-executor/src/main/java/com/gme/pay/executor/security/HmacRequestSigningFilter.java
**Acceptance / logic checks:**
- Valid signature with current timestamp proceeds to controller (no 4xx)
- X-Timestamp 301 s old returns 401 with error.code=TIMESTAMP_OUT_OF_RANGE
- Replayed request (same signature within 10 min) returns 401 with error.code=INVALID_SIGNATURE
- Missing X-API-Key header returns 401 error.code=INVALID_API_KEY before HMAC compute
- Unknown X-API-Key returns 401 error.code=INVALID_API_KEY
**Depends on:** 8.9-T09

### 8.9-T11 — Implement Idempotency-Key middleware in svc-payment-executor (Redis-backed 24h TTL)  _(50 min)_
**Context:** API-05 §2.6 and §7.1. All POST endpoints require Idempotency-Key header (UUID, 16-128 chars). Store (partner_id, idempotency_key) -> (response_body, response_status, body_hash) in Redis with 24h TTL. Redis key: idempotency:{partnerId}:{idempotencyKey}. Body hash: SHA-256 hex of raw request bytes. Scenarios: missing key -> 400 MISSING_IDEMPOTENCY_KEY; same key + same body + in-flight -> 409 X-Idempotency-Status: in_flight; same key + same body + completed -> replay stored response; same key + different body -> 422 IDEMPOTENCY_KEY_REUSE. GET requests bypass the filter. File: services/svc-payment-executor/src/main/java/com/gme/pay/executor/web/IdempotencyFilter.java.
**Steps:** Create IdempotencyFilter extending OncePerRequestFilter; inject StringRedisTemplate; Compute Redis key from authenticated partner_id and Idempotency-Key header; On cache miss: set IN_FLIGHT with 24h TTL; call chain; on response commit overwrite with (status, body, body_hash); On cache hit IN_FLIGHT: return 409 with X-Idempotency-Status: in_flight header; On cache hit COMPLETED: compare SHA-256 of current body vs stored body_hash; mismatch -> 422 IDEMPOTENCY_KEY_REUSE; match -> replay stored response; Skip filter for GET requests (check request.getMethod())
**Deliverable:** services/svc-payment-executor/src/main/java/com/gme/pay/executor/web/IdempotencyFilter.java
**Acceptance / logic checks:**
- POST without Idempotency-Key header returns 400 MISSING_IDEMPOTENCY_KEY
- Same key same body completed: second call returns stored response; service layer not invoked again (verified by mock count)
- Same key different body returns 422 IDEMPOTENCY_KEY_REUSE
- GET request without Idempotency-Key header proceeds without 400
- Redis TTL on stored key is 24 h (86400 s)
**Depends on:** 8.9-T10

### 8.9-T12 — Implement POST /v1/rates @RestController in svc-rate-fx  _(45 min)_
**Context:** API-05 §4.2. RateController.java at services/svc-rate-fx/src/main/java/com/gme/pay/ratefx/web/RateController.java accepts RateQuoteRequest (from lib-api-contracts), delegates to RateEngineService.quote(), returns RateQuoteResponse HTTP 200. Validation: target_payout > 0, direction is valid enum value. Scheme existence check via ConfigRegistryClient (OpenFeign) calling internal scheme registry; unknown scheme -> 404 SCHEME_NOT_FOUND. Rate quote TTL per partner config (field rate_quote_ttl_seconds, range 60-1800 s, default 300 s) fetched from Redis config cache. valid_until = Instant.now(clock).plusSeconds(ttl). USD pool fields null in response when direction=domestic (same-currency short-circuit). X-Request-ID response header injected from MDC.
**Steps:** Create RateController.java @RestController @RequestMapping('/v1/rates'); Inject RateEngineService, ConfigRegistryClient, Clock; Implement postRates(@RequestBody @Valid RateQuoteRequest, HttpServletRequest) returning ResponseEntity<RateQuoteResponse>; Validate target_payout > 0; let @Valid handle direction enum constraint; Resolve scheme; throw SchemeNotFoundException (-> 404) on missing; Compute valid_until = now(clock) + ttl; build response; set X-Request-ID header
**Deliverable:** services/svc-rate-fx/src/main/java/com/gme/pay/ratefx/web/RateController.java
**Acceptance / logic checks:**
- POST /v1/rates valid inbound request returns 200 with non-null quote_id and valid_until in the future
- target_payout=0 returns 400 VALIDATION_ERROR
- Unknown scheme_id returns 404 SCHEME_NOT_FOUND
- valid_until = quote_issued_at + rate_quote_ttl_seconds (verified with fixed Clock)
- collection_usd field is null in response when direction=domestic
**Depends on:** 8.9-T11

### 8.9-T13 — Implement POST /v1/payments @RestController for Fixed MPM in svc-payment-executor  _(55 min)_
**Context:** API-05 §4.3 and §5.1. PaymentController.java at services/svc-payment-executor/src/main/java/com/gme/pay/executor/web/PaymentController.java. Flow: 1) load quote from Redis by quote_id; reject expired quote with 422 RATE_QUOTE_EXPIRED (no prefunding touch); 2) validate direction and scheme_id match quote; 3) for OVERSEAS partner: @Transactional SELECT FOR UPDATE on prefunding_balance table (PostgreSQL, column balance_usd NUMERIC(20,4)); deduct collection_usd; reject with 402 INSUFFICIENT_PREFUNDING if insufficient; 4) write all USD pool values to transaction table (rate lock, field rate_locked_at); 5) call SchemeAdapterClient.commitPayment(); 6) record event trail steps PREFUND_DEDUCTED (step 2) and TRANSACTION_COMMITTED (step 5); return 201 approved or 202 async pending.
**Steps:** Create PaymentController.java @RestController @RequestMapping('/v1/payments'); Inject QuoteRedisRepository, PrefundingService, PaymentExecutorService, SchemeAdapterClient; Check quote validity from Redis; return 422 RATE_QUOTE_EXPIRED if expired; For OVERSEAS partner call prefundingService.deductAtomic(partnerId, collectionUsd) @Transactional; catch InsufficientFundsException -> 402; Call schemeAdapterClient.commitPayment(quote, req); map to FixedMpmPaymentResponse; Return 201 on synchronous scheme approval; 202 on async pending
**Deliverable:** services/svc-payment-executor/src/main/java/com/gme/pay/executor/web/PaymentController.java
**Acceptance / logic checks:**
- Expired quote_id returns 422 RATE_QUOTE_EXPIRED; prefunding_balance row not touched
- OVERSEAS partner with balance 35.00 and required collection_usd 35.77 returns 402 INSUFFICIENT_PREFUNDING; scheme adapter never called
- Successful approval returns 201 with payment_id and scheme_txn_id
- transaction row has rate_locked_at timestamp set at execution time
- LOCAL partner request skips prefunding deduction (no SELECT FOR UPDATE issued)
**Depends on:** 8.9-T12

### 8.9-T14 — Implement POST /v1/payments/cpm/generate @RestController in svc-payment-executor  _(50 min)_
**Context:** API-05 §4.4 and §5.2. CpmController.java at services/svc-payment-executor/src/main/java/com/gme/pay/executor/web/CpmController.java. For OVERSEAS partner: deduct prefunding atomically (SELECT FOR UPDATE on prefunding_balance) BEFORE calling scheme. If deduction fails -> 402 INSUFFICIENT_PREFUNDING; scheme never called. prefund amount = prefund_reserve_usd if provided, else scheme max transaction limit from ConfigRegistryClient. Call SchemeAdapterClient.prepareCpm() to get prepare_token and qr_content. expires_at = now + token_ttl_seconds (default 60, configurable). Store cpm_token correlation in Redis key cpm:{cpmTokenId} with same TTL. Record event step 2 (PREFUND_DEDUCTED). country_code is required for CPM.
**Steps:** Create CpmController.java @RestController; Inject PrefundingService, SchemeAdapterClient, ConfigRegistryClient, CpmRedisRepository; Resolve prefund amount (prefund_reserve_usd or scheme max limit); For OVERSEAS: call prefundingService.deductAtomic() @Transactional; catch InsufficientFundsException -> 402; Call schemeAdapterClient.prepareCpm(schemeId, direction, customerRef); Build CpmGenerateResponse with pre-assigned payment_id, prepare_token, expires_at = now+60s; store in Redis with TTL; return 201
**Deliverable:** services/svc-payment-executor/src/main/java/com/gme/pay/executor/web/CpmController.java
**Acceptance / logic checks:**
- OVERSEAS partner with insufficient balance returns 402; prepareCpm never called (verify via mock)
- Response expires_at is exactly 60 s after created_at (fixed Clock)
- LOCAL partner request proceeds without touching prefunding_balance table
- cpm_token_id stored in Redis with TTL <= 60 s (verify via RedisTemplate.getExpire)
- Missing country_code returns 400 VALIDATION_ERROR
**Depends on:** 8.9-T13

### 8.9-T15 — Implement GET /v1/payments/{id}, POST cancel, GET /v1/merchants/{qr}, GET /v1/balance  _(55 min)_
**Context:** API-05 §4.5-4.8. GET /v1/payments/{id}: query PostgreSQL transaction table WHERE payment_id AND partner_id = calling partner; 404 PAYMENT_NOT_FOUND if not owned. POST cancel: only if status in (approved,pending) AND created_at date == today KST; else 400 CANCEL_NOT_PERMITTED; call SchemeAdapterClient.cancelPayment(); for OVERSEAS return prefund_returned_usd. GET /v1/merchants/{qr}: query MongoDB merchant collection (service: svc-merchant-qr, repository MerchantRepository Spring Data MongoDB) by qr_code field; 404 not found; 422 if status=inactive. GET /v1/balance: LOCAL partner -> 403 FORBIDDEN; OVERSEAS -> query prefunding_balance PostgreSQL by partner_id; include_history=true returns last 10 rows from prefunding_deduction_event ORDER BY event_at DESC LIMIT 10.
**Steps:** Add getPaymentStatus(@PathVariable id) and cancelPayment methods to PaymentController.java; Implement cancel KST same-day validation using ZoneId.of('Asia/Seoul'); Create MerchantController.java @RestController /v1/merchants injecting MerchantRepository (Spring Data MongoDB); Create BalanceController.java @RestController /v1/balance injecting PrefundingRepository (JPA); Enforce partner ownership on all lookups; throw appropriate typed exceptions mapped to ErrorEnvelope; Return correct HTTP codes for all error cases
**Deliverable:** MerchantController.java, BalanceController.java; getPaymentStatus and cancelPayment in PaymentController.java
**Acceptance / logic checks:**
- GET /v1/payments/{id} with another partner's payment_id returns 404 PAYMENT_NOT_FOUND
- Cancel on payment created yesterday KST returns 400 CANCEL_NOT_PERMITTED
- GET /v1/merchants/{qr} for inactive merchant returns 422 not 404
- GET /v1/balance for LOCAL partner returns 403 FORBIDDEN
- GET /v1/balance?include_history=true returns array of at most 10 deduction entries
**Depends on:** 8.9-T14

### 8.9-T16 — Implement webhook delivery service with retry, signing, and outbox pattern in svc-notification-webhook  _(55 min)_
**Context:** API-05 §6.1-6.4. WebhookDeliveryService.java at services/svc-notification-webhook/src/main/java/com/gme/pay/webhook/WebhookDeliveryService.java. Uses transactional Outbox table webhook_outbox (PostgreSQL) polled every 5 s via @Scheduled. Signing: X-GME-Webhook-Signature = sha256=HMAC-SHA256(serialised_body, webhook_signing_secret); X-GME-Webhook-Timestamp = ISO-8601; X-GME-Event-ID = event_id. webhook_signing_secret fetched from Vault (distinct from API secret). RestTemplate 10 s read timeout; non-2xx or exception = failure. Retry back-off: immediate, 30 s, 2 min, 10 min, 30 min, 1 h x5 = 10 attempts. After 10 failures: set status=delivery_failed, publish alert to outbox.
**Steps:** Create WebhookDeliveryService.java @Service with @Scheduled(fixedDelay=5000) outbox polling method; Inject RestTemplate (10 s read timeout), VaultTemplate, WebhookOutboxRepository (JPA); Compute HMAC-SHA256 signature over serialised event body using partner webhook_signing_secret from Vault; Use RestTemplate.postForEntity; treat non-2xx or ResourceAccessException as failure; Update webhook_outbox: increment attempt_count; compute next_retry_at from back-off schedule; mark delivery_failed after attempt 10; Log event_id and partner_id on every attempt for audit
**Deliverable:** services/svc-notification-webhook/src/main/java/com/gme/pay/webhook/WebhookDeliveryService.java
**Acceptance / logic checks:**
- Attempt 1 fires immediately (no initial delay); attempt 2 fires after 30 s (fixed scheduler)
- After 10 failed attempts: webhook_outbox.status = delivery_failed
- HMAC-SHA256 header value verifiable by re-computing with test secret
- 10 s timeout enforced: mock endpoint sleeping 11 s triggers failure not success
- Signing secret fetched from VaultTemplate not from application.yaml
**Depends on:** 8.9-T15

### 8.9-T17 — Create Spring @Profile(sandbox) config class with SandboxSchemeAdapterStub  _(45 min)_
**Context:** API-05 §10.1 and §10.3. Sandbox mirrors production with: scheme calls simulated, prefunding pre-loaded, test treasury rates static, settlement simulated hourly. SandboxConfig.java @Configuration @Profile('sandbox') at services/svc-payment-executor/src/main/java/com/gme/pay/executor/config/SandboxConfig.java registers SandboxSchemeAdapterStub @Bean @Primary replacing real SchemeAdapterClient. Stub returns canned responses based on merchant_qr magic values: ZPQR_TEST_APPROVED -> approved immediately; ZPQR_TEST_PENDING -> pending 30 s then approved; ZPQR_TEST_DECLINED -> SCHEME_UNAVAILABLE 422; ZPQR_TEST_TIMEOUT -> uncertain status; ZPQR_TEST_INACTIVE -> MERCHANT_NOT_FOUND 404. application-sandbox.yml at services/svc-payment-executor/src/main/resources/ disables live FX feed and configures hourly settlement simulation.
**Steps:** Create application-sandbox.yml with zeropay.realtime.enabled=false and settlement.simulation.cron=0 0 * * * *; Create SandboxConfig.java @Configuration @Profile('sandbox'); Implement SandboxSchemeAdapterStub @Bean @Primary implementing SchemeAdapterClient interface; Switch on merchant_qr value for each magic ZPQR_TEST_* and return appropriate canned result; Add SettlementSimulationJob.java @Component @Profile('sandbox') with @Scheduled for hourly simulation; Add @ConditionalOnProperty(name='zeropay.realtime.enabled', havingValue='false') to disable real adapter
**Deliverable:** services/svc-payment-executor/src/main/resources/application-sandbox.yml; services/svc-payment-executor/src/main/java/com/gme/pay/executor/config/SandboxConfig.java; SandboxSchemeAdapterStub.java
**Acceptance / logic checks:**
- With spring.profiles.active=sandbox, SandboxSchemeAdapterStub is primary bean; real ZeroPay adapter not instantiated
- ZPQR_TEST_APPROVED merchant_qr returns approved payment response
- ZPQR_TEST_DECLINED returns 422 SCHEME_UNAVAILABLE
- ZPQR_TEST_TIMEOUT sets payment status to uncertain
- zeropay.realtime.enabled=false confirmed in application-sandbox.yml
**Depends on:** 8.9-T16

### 8.9-T18 — Implement sandbox prefunding control endpoint POST /sandbox/v1/prefunding/set  _(35 min)_
**Context:** API-05 §10.1: sandbox control API allows setting prefunding balance to any value. Endpoint: POST /sandbox/v1/prefunding/set @Profile('sandbox') only. Body: {partner_id: string, balance_usd: string decimal}. Requires valid pk_test_ API key verified by HmacRequestSigningFilter. Updates prefunding_balance.balance_usd (NUMERIC(20,4)) @Transactional. Returns 200 {partner_id, balance_usd, updated_at}. Negative balance_usd -> 400 VALIDATION_ERROR. Unknown partner_id -> 404. Controller: services/svc-payment-executor/src/main/java/com/gme/pay/executor/sandbox/SandboxController.java.
**Steps:** Create SandboxController.java @RestController @Profile('sandbox') @RequestMapping('/sandbox/v1'); Inject PrefundingRepository (Spring Data JPA); Implement setPrefundBalance(@RequestBody @Valid SetPrefundRequest req) @Transactional; Validate balance_usd as BigDecimal >= 0; reject negative with 400; Call PrefundingRepository native UPDATE query: UPDATE prefunding_balance SET balance_usd=:bal WHERE partner_id=:pid; check rows updated=1 else 404; Return 200 SetPrefundResponse with partner_id, balance_usd, updated_at=Instant.now()
**Deliverable:** services/svc-payment-executor/src/main/java/com/gme/pay/executor/sandbox/SandboxController.java
**Acceptance / logic checks:**
- POST /sandbox/v1/prefunding/set balance_usd=1000.00 updates PostgreSQL row and returns 200
- Negative balance_usd=-1 returns 400 VALIDATION_ERROR
- Endpoint not registered with spring.profiles.active=production (bean absent)
- balance_usd stored as NUMERIC(20,4): value 99999.9999 preserved exactly
- Unknown partner_id returns 404 not DB exception
**Depends on:** 8.9-T17

### 8.9-T19 — Seed sandbox PostgreSQL via Flyway: test treasury rates, test partners, prefunding balances  _(35 min)_
**Context:** Stack: Flyway migrations at db/migration/. Sandbox needs static test treasury rates (treasury_rate table: usd_krw=1420.00, usd_usd=1.0, usd_mnt=3430.00), 2 test partners (SENDMN: OVERSEAS, pk_test_/sk_test_ credentials bcrypt-hashed cost 12, and GME_REMIT: LOCAL, pk_test_/sk_test_), and prefunding_balance for SENDMN = 10000.00 USD. Sandbox-only migrations use a separate Flyway location: classpath:db/migration/sandbox configured in application-sandbox.yml. V9xx numbering to avoid collision with production migrations.
**Steps:** Create db/migration/sandbox/V901__sandbox_treasury_rates.sql with INSERT INTO treasury_rate rows for usd_krw=1420.00, usd_usd=1.0, usd_mnt=3430.00; Create db/migration/sandbox/V902__sandbox_test_partners.sql with INSERT INTO partner rows for SENDMN and GME_REMIT with bcrypt-hashed API secrets; Create db/migration/sandbox/V903__sandbox_prefunding_balances.sql with INSERT INTO prefunding_balance for SENDMN balance_usd=10000.00; Configure application-sandbox.yml: spring.flyway.locations=classpath:db/migration,classpath:db/migration/sandbox; Add sandbox resource path to svc-payment-executor Gradle resources block
**Deliverable:** db/migration/sandbox/V901__sandbox_treasury_rates.sql, V902__sandbox_test_partners.sql, V903__sandbox_prefunding_balances.sql
**Acceptance / logic checks:**
- With sandbox profile, Flyway applies all 3 V9xx migrations on fresh schema without errors
- treasury_rate table has usd_krw=1420.00 after V901
- prefunding_balance for SENDMN = 10000.00 USD after V903
- V9xx migrations do NOT run when spring.flyway.locations=classpath:db/migration only (production mode)
- SENDMN api_key has pk_test_ prefix; api_secret bcrypt hash verifies against test sk_test_ value
**Depends on:** 8.9-T18

### 8.9-T20 — Write Jackson round-trip contract tests for generated DTOs in lib-api-contracts  _(40 min)_
**Context:** Stack: JUnit 5, Jackson ObjectMapper (Spring Boot auto-configured). Tests in libs/lib-api-contracts/src/test/java/com/gme/pay/api/contracts/DtoContractTest.java verify the generated Java DTOs serialise/deserialise exactly as described in openapi/partner-api.yaml. Tests cover: required vs optional fields, enum values, nullable fields, and money fields as string not number. These are pure unit tests with no Spring context needed.
**Steps:** Create DtoContractTest.java in libs/lib-api-contracts/src/test/java/com/gme/pay/api/contracts/; Test RT-01: RateQuoteRequest round-trip; verify target_payout and payout_currency present; direction enum rejects 'invalid'; Test RT-02: RateQuoteResponse round-trip; verify valid_until present; collection_usd null when not set with Jackson INCLUDE_NON_NULL; Test RT-03: ErrorEnvelope round-trip; nested error.code, error.details array serialises correctly; Test RT-04: FixedMpmPaymentRequest; verify collection_amount serialises as JSON string not numeric; Test RT-05: CpmGenerateResponse; expires_at present; prefund_reserved_usd nullable; Run ./gradlew :libs:lib-api-contracts:test
**Deliverable:** libs/lib-api-contracts/src/test/java/com/gme/pay/api/contracts/DtoContractTest.java with 5+ test methods
**Acceptance / logic checks:**
- All tests pass on ./gradlew :libs:lib-api-contracts:test
- RT-01: direction='invalid' throws JsonMappingException during deserialisation
- RT-02: collection_usd absent from JSON output when null (Jackson INCLUDE_NON_NULL configured)
- RT-03: ErrorEnvelope round-trip preserves error.details[0].field and .issue
- RT-04: collection_amount in JSON is string value not numeric value
**Depends on:** 8.9-T09

### 8.9-T21 — Write Spring MockMvc contract tests for POST /v1/rates (WireMock for config-registry)  _(50 min)_
**Context:** Stack: JUnit 5, @WebMvcTest(RateController.class), WireMock (com.github.tomakehurst:wiremock). Test class: services/svc-rate-fx/src/test/java/com/gme/pay/ratefx/web/RateControllerContractTest.java. Tests verify HTTP layer only (not rate engine internals). WireMock stubs the config-registry internal calls. Helper builds valid HMAC-signed headers for each test request.
**Steps:** Create RateControllerContractTest.java @WebMvcTest(RateController.class) @AutoConfigureMockMvc; Register WireMock @RegisterExtension; stub GET /internal/schemes/zeropay -> 200 scheme JSON; Create buildSignedHeaders(method, path, body) helper producing valid X-API-Key, X-Timestamp, X-Signature; Test C-01: valid inbound rate quote request -> 200 with quote_id and valid_until present; Test: unknown scheme_id (WireMock returns 404) -> controller returns 404 SCHEME_NOT_FOUND; Test: missing X-API-Key -> 401 INVALID_API_KEY; Test: direction=invalid -> 400 VALIDATION_ERROR with error.details[0].field=direction
**Deliverable:** services/svc-rate-fx/src/test/java/com/gme/pay/ratefx/web/RateControllerContractTest.java
**Acceptance / logic checks:**
- All 4 test methods pass
- C-01 response valid_until matches ISO-8601 pattern
- SCHEME_NOT_FOUND test asserts HTTP 404 and error.code=SCHEME_NOT_FOUND
- VALIDATION_ERROR test asserts error.details[0].field=direction
- WireMock verifies exactly 1 call to scheme config endpoint for C-01
**Depends on:** 8.9-T20

### 8.9-T22 — Write Testcontainers integration test for MPM payment flow with PostgreSQL  _(55 min)_
**Context:** Stack: JUnit 5, Testcontainers PostgreSQL 16, @SpringBootTest(webEnvironment=RANDOM_PORT), WireMock for scheme adapter. Test class: services/svc-payment-executor/src/test/java/com/gme/pay/executor/MpmPaymentIntegrationTest.java. Applies Flyway migrations including sandbox seed (SENDMN with 10000.00 USD). Verifies: quote fetch -> MPM payment -> prefunding_balance decremented atomically in PostgreSQL. After payment of collection_usd=36.37, balance must be 9963.63. Also verifies idempotent retry does not double-debit.
**Steps:** Add testcontainers-postgresql dependency to svc-payment-executor test scope; Create MpmPaymentIntegrationTest.java @SpringBootTest @Testcontainers with @Container PostgreSQLContainer; Apply Flyway with sandbox location in @BeforeAll; Stub WireMock: POST /internal/scheme/zeropay/commit -> 200 approved; Call POST /v1/rates then POST /v1/payments with SENDMN credentials and collection_usd=36.37; Assert HTTP 201 and query JdbcTemplate: SELECT balance_usd FROM prefunding_balance WHERE partner_id='SENDMN' = 9963.63; Call same POST /v1/payments with same Idempotency-Key; assert 201 again and balance still 9963.63
**Deliverable:** services/svc-payment-executor/src/test/java/com/gme/pay/executor/MpmPaymentIntegrationTest.java
**Acceptance / logic checks:**
- Test passes with ./gradlew :services:svc-payment-executor:test
- prefunding_balance.balance_usd = 9963.63 after first payment (BigDecimal precision, no rounding error)
- Second call with same Idempotency-Key returns stored 201 without re-debiting (balance stays 9963.63)
- Payment with expired quote returns 422; balance unchanged at 10000.00
- Test completes in under 60 s including Testcontainers startup
**Depends on:** 8.9-T21

### 8.9-T23 — Write Testcontainers concurrency test for CPM prefunding atomicity (10 concurrent requests)  _(55 min)_
**Context:** Stack: JUnit 5, Testcontainers PostgreSQL 16, @SpringBootTest. Verifies SELECT FOR UPDATE on prefunding_balance prevents double-spend. SENDMN seeded with 100.00 USD balance. 10 concurrent threads each POST /v1/payments/cpm/generate with prefund_reserve_usd=50.00 and unique Idempotency-Keys. Exactly 2 should return 201; exactly 8 should return 402 INSUFFICIENT_PREFUNDING. Final balance must be 0.00 USD (not negative). Test class: services/svc-payment-executor/src/test/java/com/gme/pay/executor/CpmConcurrencyTest.java.
**Steps:** Create CpmConcurrencyTest.java @SpringBootTest @Testcontainers; Seed SENDMN with balance_usd=100.00 via SandboxController endpoint or direct SQL; Use ExecutorService(10 threads) to fire 10 simultaneous POST /v1/payments/cpm/generate each with prefund_reserve_usd=50.00 and unique Idempotency-Keys; Collect all HTTP status codes and count 201 vs 402 responses; Assert exactly 2 succeed (201) and exactly 8 fail (402); Query final prefunding_balance.balance_usd; assert = 0.00
**Deliverable:** services/svc-payment-executor/src/test/java/com/gme/pay/executor/CpmConcurrencyTest.java
**Acceptance / logic checks:**
- Exactly 2 of 10 concurrent requests return HTTP 201
- Exactly 8 return HTTP 402 INSUFFICIENT_PREFUNDING
- Final prefunding_balance.balance_usd = 0.00 (not negative)
- No DB deadlock or exception logged during test
- Test passes on 3 consecutive runs (repeatable)
**Depends on:** 8.9-T22

### 8.9-T24 — Write unit tests for HmacRequestSigningFilter (5 scenarios, fixed Clock, mocked Redis)  _(40 min)_
**Context:** Stack: JUnit 5, Mockito, MockHttpServletRequest. Tests for HmacRequestSigningFilter in isolation: no HTTP server needed. Clock injected as java.time.Clock so tests fix the instant. Redis mocked via Mockito. Tests cover valid request, stale timestamp, replay detection, missing API key, and canonical string construction.
**Steps:** Create HmacRequestSigningFilterTest.java in services/svc-payment-executor/src/test/java/com/gme/pay/executor/security/; Test H1: valid signature + current timestamp -> FilterChain.doFilter called once; Test H2: valid signature + timestamp 301 s old (fixed Clock) -> 401 TIMESTAMP_OUT_OF_RANGE; chain NOT called; Test H3: valid signature but Redis returns existing replay key -> 401 INVALID_SIGNATURE; Redis SET not called again; Test H4: missing X-API-Key header -> 401 INVALID_API_KEY; no HMAC computation (verify Mac never called); Test H5: canonical string construction: assert computed string = METHOD + LF + PATH + LF + timestamp + LF + SHA256_of_body_hex
**Deliverable:** services/svc-payment-executor/src/test/java/com/gme/pay/executor/security/HmacRequestSigningFilterTest.java with 5 test methods
**Acceptance / logic checks:**
- All 5 tests pass
- H2 uses Clock.fixed at now()-301s; asserts error.code=TIMESTAMP_OUT_OF_RANGE
- H3 asserts Redis SET never called after replay detected
- H5 asserts canonical string contains correct SHA-256 hex of test body bytes
- No real network call: Redis and PartnerCredentialService fully mocked
**Depends on:** 8.9-T10

### 8.9-T25 — Write unit tests for IdempotencyFilter (5 scenarios from API-05 §7.1)  _(40 min)_
**Context:** Stack: JUnit 5, Mockito, MockHttpServletRequest/Response. Tests for IdempotencyFilter covering all 5 scenarios: S1 missing key, S2 in-flight, S3 completed same body, S4 completed different body, S5 GET bypass. StringRedisTemplate mocked. No Spring context needed.
**Steps:** Create IdempotencyFilterTest.java in services/svc-payment-executor/src/test/java/com/gme/pay/executor/web/; Mock StringRedisTemplate with controlled return values per scenario; Test S1: POST without Idempotency-Key -> 400 MISSING_IDEMPOTENCY_KEY; Test S2: Redis returns IN_FLIGHT -> 409 with X-Idempotency-Status: in_flight header; Test S3: Redis returns COMPLETED + matching body hash -> stored response replayed; FilterChain.doFilter NOT called; Test S4: Redis returns COMPLETED + different body hash -> 422 IDEMPOTENCY_KEY_REUSE; Test S5: GET request without Idempotency-Key -> FilterChain.doFilter called; no 400
**Deliverable:** services/svc-payment-executor/src/test/java/com/gme/pay/executor/web/IdempotencyFilterTest.java with 5 test methods
**Acceptance / logic checks:**
- All 5 tests pass
- S3 asserts FilterChain.doFilter never called (stored response replayed)
- S4 asserts error.code=IDEMPOTENCY_KEY_REUSE in response body
- S2 asserts response header X-Idempotency-Status=in_flight
- S5 asserts FilterChain.doFilter called exactly once for GET
**Depends on:** 8.9-T11

### 8.9-T26 — Write unit tests for WebhookDeliveryService: signing, retry schedule, failure escalation  _(45 min)_
**Context:** Stack: JUnit 5, Mockito. Tests for WebhookDeliveryService at services/svc-notification-webhook/src/test/java/com/gme/pay/webhook/WebhookDeliveryServiceTest.java. RestTemplate and VaultTemplate mocked. Fixed Clock for retry timing. Covers: successful delivery, retry back-off, timeout failure, 10-attempt escalation, HMAC signature correctness.
**Steps:** Create WebhookDeliveryServiceTest.java; mock RestTemplate and VaultTemplate (return test webhook_signing_secret=test_secret_32bytes); Test W1: delivery returns 200 -> webhook_outbox.status=DELIVERED; no retry scheduled; Test W2: delivery returns 500 -> attempt_count incremented to 1; next_retry_at = now+30s (fixed Clock); Test W3: delivery throws ResourceAccessException (timeout) -> treated as failure, attempt_count incremented; Test W4: after 10 consecutive failures -> status=DELIVERY_FAILED; alert event written to outbox; Test W5: re-compute HMAC-SHA256(body, test_secret_32bytes); assert equals X-GME-Webhook-Signature header value sent by service (strip sha256= prefix before compare)
**Deliverable:** services/svc-notification-webhook/src/test/java/com/gme/pay/webhook/WebhookDeliveryServiceTest.java with 5 test methods
**Acceptance / logic checks:**
- W5 signature comparison passes (byte-exact HMAC-SHA256)
- W2 next_retry_at is exactly 30 s after failure timestamp (fixed Clock)
- W4 asserts attempt_count=10 and outbox status=DELIVERY_FAILED
- W3 ResourceAccessException treated as delivery failure not unhandled exception
- W1 no retry row scheduled after successful delivery
**Depends on:** 8.9-T16

### 8.9-T27 — Write Spring OpenAPI contract validation tests against running sandbox server  _(55 min)_
**Context:** Stack: JUnit 5, Testcontainers (PostgreSQL 16 + Redis), @SpringBootTest(webEnvironment=RANDOM_PORT), io.swagger.parser.v3:swagger-parser. Tests spin up the real application with sandbox profile, make HTTP requests, and validate each JSON response body against openapi/partner-api.yaml schemas using swagger-parser SchemaValidator. Catches DTO serialisation divergence (field name typos, wrong types). Test class: services/svc-payment-executor/src/test/java/com/gme/pay/executor/OpenApiContractValidationTest.java.
**Steps:** Add io.swagger.parser.v3:swagger-parser to svc-payment-executor test scope; Create OpenApiContractValidationTest.java @SpringBootTest @Testcontainers with PostgreSQL and Redis containers; profile=sandbox; Load openapi/partner-api.yaml via new OpenAPIV3Parser().read(specPath); For POST /v1/rates 200: execute request; capture response JSON; validate against RateQuoteResponse schema; assert 0 violations; For POST /v1/payments 201: execute full MPM flow; validate FixedMpmPaymentResponse; assert 0 violations; For GET /v1/payments/{id} 200: execute; validate PaymentDetailResponse; assert 0 violations
**Deliverable:** services/svc-payment-executor/src/test/java/com/gme/pay/executor/OpenApiContractValidationTest.java
**Acceptance / logic checks:**
- Zero schema violations for /v1/rates 200 response
- Zero schema violations for /v1/payments 201 response
- Zero schema violations for GET /v1/payments/{id} 200 response
- If a required field is absent from DTO the test fails (regression safety verified by temporarily removing a field)
- ./gradlew :services:svc-payment-executor:test --tests *OpenApiContract* passes
**Depends on:** 8.9-T23

### 8.9-T28 — Add x-sandbox-config extension and certification checklist to partner-api.yaml  _(30 min)_
**Context:** API-05 §10 (Sandbox and Onboarding). Certification requires 16 test cases C-01 to C-16. Sandbox credential format: pk_test_<32-char-hex> / sk_test_<64-char-hex>. Magic merchant_qr values: ZPQR_TEST_APPROVED (approved), ZPQR_TEST_PENDING (pending 30s then approved), ZPQR_TEST_DECLINED (SCHEME_UNAVAILABLE), ZPQR_TEST_TIMEOUT (uncertain), ZPQR_TEST_INACTIVE (MERCHANT_NOT_FOUND). Simulated settlement fires top of every hour. Add all of this as an x-sandbox-config extension block under info in openapi/partner-api.yaml so partner tooling can read it programmatically. No new code files.
**Steps:** Open openapi/partner-api.yaml; Add info.x-sandbox-config object with: base_url, credential_format (pk_test_/sk_test_ pattern), test_merchant_qr_values (map of value to outcome string); Add certification_checklist array with 16 entries each containing id (C-01 to C-16), test_case description, pass_criteria string matching API-05 §10.4 table; Verify YAML still lints clean after extension block addition; Run redocly lint; assert 0 errors
**Deliverable:** x-sandbox-config extension block in openapi/partner-api.yaml info section covering all C-01 to C-16 certification tests and magic QR values
**Acceptance / logic checks:**
- openapi/partner-api.yaml lints clean after addition
- All 16 test cases C-01 through C-16 present with pass_criteria field
- ZPQR_TEST_APPROVED maps to outcome: 'Payment approved immediately'
- credential_format shows pk_test_ and sk_test_ prefixes
- sandbox base_url = https://api-sandbox.gmepayplus.com
**Depends on:** 8.9-T19


## WBS 8.10 — Partner integration & certification kit
### 8.10-T01 — Define HmacSignatureUtil in lib-api-contracts: canonical-string builder and verifier  _(40 min)_
**Context:** API-05 §3.2: every partner request is signed with HMAC-SHA256 over canonical string = HTTP_METHOD + LF + PATH_WITH_QUERY + LF + ISO8601_UTC_TIMESTAMP + LF + SHA256_HEX_OF_BODY. For GET/DELETE body-hash = SHA-256 of empty string. Signature is hex-encoded HMAC-SHA256(canonical_string, api_secret). Replay window = 300 s. Module: lib-api-contracts (shared library used by Auth service and tests).
**Steps:** Create lib-api-contracts/src/main/java/com/gme/pay/contracts/hmac/HmacSignatureUtil.java; Implement buildCanonicalString(method, pathWithQuery, timestamp, bodyBytes): String; Implement computeSignature(canonicalString, apiSecret): String using javax.crypto.Mac HMAC-SHA256; Implement verify(canonicalString, providedSignature, apiSecret, serverInstant, requestInstant): boolean -- rejects if |serverInstant - requestInstant| > 300 s; Add unit test HmacSignatureUtilTest: happy path, GET empty-body, timestamp-expired, wrong-secret
**Deliverable:** lib-api-contracts/src/main/java/com/gme/pay/contracts/hmac/HmacSignatureUtil.java and HmacSignatureUtilTest.java
**Acceptance / logic checks:**
- buildCanonicalString produces exactly 4 lines joined by LF with no trailing LF
- computeSignature for the fixed test vector POST + /v1/rates + 2026-06-04T09:31:00.000Z + SHA256(body) produces a stable hex output
- verify returns false when |delta| > 300 s
- verify returns false when apiSecret is wrong
- GET request uses SHA-256 of empty string as body hash

### 8.10-T02 — Implement HmacAuthFilter in services/auth-identity as a Spring Security OncePerRequestFilter  _(45 min)_
**Context:** API-05 §3.1-3.2: every northbound request carries X-API-Key, X-Timestamp, X-Signature headers. The Auth and Identity service (services/auth-identity) must validate these before any endpoint logic. Uses HmacSignatureUtil from lib-api-contracts (8.10-T01). Secrets stored in Vault/KMS; never in config files. On failure return standard error envelope (lib-errors) with codes INVALID_API_KEY (401), INVALID_SIGNATURE (401), TIMESTAMP_OUT_OF_RANGE (401).
**Steps:** Add HmacAuthFilter extends OncePerRequestFilter in services/auth-identity/src/main/java/com/gme/pay/auth/filter/HmacAuthFilter.java; Extract X-API-Key, X-Timestamp, X-Signature from request; cache body bytes via ContentCachingRequestWrapper; Load api_secret from Vault secret path secret/gmepay/partners/{apiKey}; Call HmacSignatureUtil.verify; on failure write error envelope JSON (lib-errors ErrorResponse) with correct HTTP status; Register filter in SecurityConfig ahead of UsernamePasswordAuthenticationFilter
**Deliverable:** services/auth-identity/src/main/java/com/gme/pay/auth/filter/HmacAuthFilter.java
**Acceptance / logic checks:**
- Missing X-API-Key returns HTTP 401 INVALID_API_KEY
- X-Timestamp more than 300 s from server time returns HTTP 401 TIMESTAMP_OUT_OF_RANGE
- Wrong signature returns HTTP 401 INVALID_SIGNATURE
- Valid headers allow the request to pass through to downstream handler
- Body bytes are not consumed by the filter (ContentCachingRequestWrapper used)
**Depends on:** 8.10-T01

### 8.10-T03 — Flyway migration V9001__partner_credentials.sql: partner credential and IP-allowlist tables  _(30 min)_
**Context:** API-05 §3.1, §3.3: each partner has one credential pair (X-API-Key / api_secret bcrypt-hashed, cost >= 12), optional up to 10 IP CIDR allowlist entries, and a rate_quote_ttl_seconds (range 60-1800, default 300). Module: services/auth-identity; DB: PostgreSQL 16 (port 5433). Flyway migration sequence must be the next available in that service.
**Steps:** Create services/auth-identity/src/main/resources/db/migration/V9001__partner_credentials.sql; Define table partner_credential (id UUID PK, partner_id UUID NOT NULL FK partners, api_key VARCHAR(64) UNIQUE NOT NULL, api_secret_hash VARCHAR(256) NOT NULL, rate_quote_ttl_seconds INT NOT NULL DEFAULT 300 CHECK (rate_quote_ttl_seconds BETWEEN 60 AND 1800), is_sandbox BOOLEAN NOT NULL DEFAULT false, status VARCHAR(16) NOT NULL DEFAULT active, created_at TIMESTAMPTZ, revoked_at TIMESTAMPTZ); Define table partner_ip_allowlist (id UUID PK, partner_credential_id UUID FK partner_credential ON DELETE CASCADE, cidr VARCHAR(48) NOT NULL, created_at TIMESTAMPTZ); Add UNIQUE constraint on (partner_credential_id, cidr); create index on api_key
**Deliverable:** services/auth-identity/src/main/resources/db/migration/V9001__partner_credentials.sql
**Acceptance / logic checks:**
- Flyway applies migration cleanly on a fresh Testcontainers Postgres 16 instance
- api_key column has UNIQUE constraint
- rate_quote_ttl_seconds CHECK rejects 59 and 1801
- partner_ip_allowlist FK cascades on delete of parent credential row
- Migration is idempotent when re-applied (Flyway checksum passes)

### 8.10-T04 — Implement IP-allowlist check in HmacAuthFilter: reject requests from non-allowlisted IPs  _(35 min)_
**Context:** API-05 §3.3: partners may register up to 10 CIDR ranges. When configured, requests from unlisted IPs are rejected with HTTP 403 IP_NOT_ALLOWLISTED before signature verification. Allowlist cached in Redis (key: ip_allowlist:{partner_id}, TTL 300 s per spec). Uses partner_ip_allowlist table from 8.10-T03. Module: services/auth-identity.
**Steps:** Add IpAllowlistService @Service in services/auth-identity/src/main/java/com/gme/pay/auth/service/IpAllowlistService.java; loadAllowlist(partnerId): check Redis first (key ip_allowlist:{partnerId}), fall back to partner_ip_allowlist table, cache result in Redis with EX 300; In HmacAuthFilter: before signature check call IpAllowlistService; if allowlist non-empty and request IP not in any CIDR, return HTTP 403 IP_NOT_ALLOWLISTED; Add unit test: IP in range passes; IP outside range blocked; empty allowlist passes all
**Deliverable:** services/auth-identity/src/main/java/com/gme/pay/auth/service/IpAllowlistService.java (and updated HmacAuthFilter)
**Acceptance / logic checks:**
- Request from 10.0.0.5 blocked when allowlist contains only 192.168.1.0/24
- Request from 192.168.1.50 passes the same allowlist
- Partner with no allowlist entries allows any IP
- Cache miss causes DB lookup; second call hits Redis (verify with Mockito spy)
- HTTP 403 body contains error code IP_NOT_ALLOWLISTED in lib-errors envelope
**Depends on:** 8.10-T02, 8.10-T03

### 8.10-T05 — Implement replay-signature dedup store in Redis: reject exact-signature duplicates within 10 min  _(30 min)_
**Context:** API-05 §3.6: in addition to the 300-s timestamp window, GMEPay+ stores (partner_id, X-Signature) for 10 minutes and rejects exact-signature duplicates within that window. Redis key: sig_replay:{partner_id}:{signature}, TTL 600 s. Module: services/auth-identity.
**Steps:** Add ReplayProtectionService @Service in services/auth-identity/src/main/java/com/gme/pay/auth/service/ReplayProtectionService.java; checkAndRecord(partnerId, signature): SETNX sig_replay:{partnerId}:{signature} with EX 600; return false if key already existed (duplicate); Inject into HmacAuthFilter; after signature validation call checkAndRecord; reject HTTP 401 INVALID_SIGNATURE if duplicate; Add integration test with Testcontainers Redis: first call passes, second identical call fails, third with different signature passes
**Deliverable:** services/auth-identity/src/main/java/com/gme/pay/auth/service/ReplayProtectionService.java
**Acceptance / logic checks:**
- Identical (partner_id, signature) pair within 10 min returns HTTP 401 on second call
- Same partner different signature is accepted within same window
- SETNX is atomic -- concurrent identical calls result in exactly one success
- After Redis key TTL expires the same signature is accepted again
- ReplayProtectionService does not call DB (Redis only)
**Depends on:** 8.10-T02

### 8.10-T06 — Implement idempotency-key store and middleware in services/payment-executor  _(45 min)_
**Context:** API-05 §2.6 and §7.1: all POST endpoints require Idempotency-Key (UUID, 16-128 chars). GMEPay+ stores (partner_id, idempotency_key, response_body, response_status) for 24 h (Redis TTL 86400 s). Same key + same body returns stored response. Same key + different body returns HTTP 422 IDEMPOTENCY_KEY_REUSE. Missing key returns HTTP 400 MISSING_IDEMPOTENCY_KEY. In-flight same key returns HTTP 409 with X-Idempotency-Status: in_flight. Module: services/payment-executor.
**Steps:** Add IdempotencyService @Service in services/payment-executor/src/main/java/com/gme/pay/executor/idempotency/IdempotencyService.java; Store keyed by idempotency:{partner_id}:{key} in Redis; value JSON {status, responseBody, requestBodyHash, locked}; checkOrLock(partnerId, key, requestBodyHash): SETNX locked entry first; update to completed on response; Create IdempotencyFilter extends OncePerRequestFilter: extract header, call service, short-circuit if found; Return X-Idempotency-Status header on replayed responses
**Deliverable:** services/payment-executor/src/main/java/com/gme/pay/executor/idempotency/IdempotencyService.java and IdempotencyFilter.java
**Acceptance / logic checks:**
- Missing header returns HTTP 400 MISSING_IDEMPOTENCY_KEY
- Second identical request returns stored HTTP status and body verbatim
- Same key different body hash returns HTTP 422 IDEMPOTENCY_KEY_REUSE
- In-flight (lock held) returns HTTP 409 with X-Idempotency-Status: in_flight
- Redis key TTL is 86400 s

### 8.10-T07 — Write HmacSampleClient.java: self-contained Java HMAC signing example for partner integration kit  _(40 min)_
**Context:** WBS 8.10 deliverable: sample HMAC client. API-05 §3.2 canonical string: HTTP_METHOD + LF + PATH_WITH_QUERY + LF + ISO8601_UTC_TIMESTAMP_MS + LF + SHA256_HEX_OF_BODY. Headers: X-API-Key, X-Timestamp, X-Signature, Idempotency-Key, Content-Type: application/json. Target: Java 11+ stdlib only (java.net.http, javax.crypto). Location: integration-kit/samples/java/HmacSampleClient.java.
**Steps:** Create integration-kit/samples/java/HmacSampleClient.java with main method; Implement buildCanonicalString and sign methods inline (no external deps); Demonstrate POST /v1/rates to https://api-sandbox.gmepayplus.com with placeholder test credentials pk_test_XXX / sk_test_YYY; Print all request headers and response body to stdout; Add inline comments explaining each step of canonical string construction
**Deliverable:** integration-kit/samples/java/HmacSampleClient.java
**Acceptance / logic checks:**
- Compiles with javac 11 with no extra classpath entries
- Running produces X-API-Key, X-Timestamp, X-Signature headers printed to stdout
- SHA-256 of empty string used when body is empty (GET example commented inline)
- Canonical string uses LF (0x0A) not CRLF as line separator
- X-Timestamp is millisecond-precision ISO-8601 UTC ending .000Z
**Depends on:** 8.10-T01

### 8.10-T08 — Write HmacSampleClient.py: Python 3 HMAC signing example for partner integration kit  _(30 min)_
**Context:** WBS 8.10 deliverable: sample HMAC client in Python. Same canonical string as 8.10-T07. Uses only stdlib: hashlib, hmac, http.client, datetime. Target: integration-kit/samples/python/hmac_sample_client.py. Demonstrates POST /v1/rates against https://api-sandbox.gmepayplus.com.
**Steps:** Create integration-kit/samples/python/hmac_sample_client.py; Implement sign_request(method, path_with_query, body_bytes, api_key, api_secret) -> dict of headers; Build canonical string with LF separator; SHA-256 body hash via hashlib.sha256; Demonstrate POST /v1/rates with sample payload; print response; Include __main__ guard with example invocation
**Deliverable:** integration-kit/samples/python/hmac_sample_client.py
**Acceptance / logic checks:**
- Runs with python3 hmac_sample_client.py and prints headers plus response
- HMAC output for a fixed test vector matches Java sample HmacSampleClient output
- hashlib.sha256(b'').hexdigest() used for body hash when body is empty
- X-Timestamp is UTC millisecond ISO-8601 (2026-06-04T09:31:00.000Z format)
- No third-party imports (stdlib only)
**Depends on:** 8.10-T07

### 8.10-T09 — Write HmacSampleClient.js (Node.js 18+): HMAC signing example for partner integration kit  _(25 min)_
**Context:** WBS 8.10 deliverable: sample HMAC client in JavaScript. Same canonical string as 8.10-T07. Uses only Node.js built-ins: crypto, https. Location: integration-kit/samples/nodejs/hmac_sample_client.js. Demonstrates POST /v1/rates against sandbox.
**Steps:** Create integration-kit/samples/nodejs/hmac_sample_client.js; Implement signRequest(method, pathWithQuery, bodyStr, apiKey, apiSecret) returning headers object; Use crypto.createHmac('sha256', apiSecret).update(canonical).digest('hex') for signature; Use crypto.createHash('sha256').update(bodyBytes).digest('hex') for body hash; Demonstrate POST /v1/rates; log headers and response to console
**Deliverable:** integration-kit/samples/nodejs/hmac_sample_client.js
**Acceptance / logic checks:**
- node hmac_sample_client.js executes on Node 18+ without additional packages
- X-Timestamp is new Date().toISOString() (millisecond precision UTC)
- HMAC for a fixed test vector matches Python and Java sample outputs
- Empty body uses SHA-256 of empty Buffer (crypto.createHash('sha256').update(Buffer.alloc(0)).digest('hex'))
- Signature header value is lowercase hex
**Depends on:** 8.10-T07

### 8.10-T10 — Create integration-kit/openapi/partner-api.yaml: OpenAPI 3.1 spec for all 7 Partner API endpoints  _(55 min)_
**Context:** API-05 §4.1: endpoints are POST /v1/rates, POST /v1/payments, POST /v1/payments/cpm/generate, GET /v1/payments/{id}, POST /v1/payments/{id}/cancel, GET /v1/merchants/{qr}, GET /v1/balance. Money fields are strings (decimal). All timestamps ISO-8601 UTC milliseconds. Error envelope: error.code, error.message, error.request_id, error.details[]. Security: custom headers X-API-Key and X-Signature. Location: integration-kit/openapi/partner-api.yaml.
**Steps:** Create integration-kit/openapi/partner-api.yaml with openapi: 3.1.0, info block (title: GMEPay+ Partner API, version: 1.0.0); Define securitySchemes for X-API-Key (apiKey in header) and X-Signature (apiKey in header); Define all 7 paths with request bodies, response schemas, and all HTTP status codes from API-05 §4; Define reusable schemas: MoneyAmount, ErrorResponse, RateQuoteRequest, RateQuoteResponse, PaymentRequest, PaymentResponse, CpmGenerateRequest, CpmGenerateResponse, CancelRequest, CancelResponse, MerchantDetailResponse, BalanceResponse; Add examples from API-05 example sections
**Deliverable:** integration-kit/openapi/partner-api.yaml
**Acceptance / logic checks:**
- openapi-generator validate returns zero errors
- All 7 paths present with correct HTTP methods and at least success + error status codes
- RateQuoteResponse includes quote_id, offer_rate, send_amount, service_charge, collection_usd, payout_usd_cost, collection_margin_usd, payout_margin_usd, cross_rate, valid_until
- ErrorResponse has error.code, error.message, error.request_id, error.details as array
- All money amount fields typed as string with format: decimal pattern ^[0-9]+(.[0-9]+)?$

### 8.10-T11 — Implement RateQuoteController POST /v1/rates in services/rate-fx  _(45 min)_
**Context:** API-05 §4.2: POST /v1/rates takes target_payout (string decimal), payout_currency, scheme_id, direction, optional merchant_qr and partner_ref. Returns quote_id, offer_rate, send_amount, service_charge, collection_usd, payout_usd_cost, collection_margin_usd, payout_margin_usd, cross_rate, valid_until. Cross-border: 5-step RATE-04 engine. Domestic same-currency short-circuit omits USD pool fields. Quote stored in Redis TTL = rate_quote_ttl_seconds (default 300 s). X-Request-ID on every response.
**Steps:** Create services/rate-fx/src/main/java/com/gme/pay/ratefx/api/RateQuoteController.java @RestController; Inject RateEngineService and QuoteStoreService; Validate required fields; call rateEngine.computeQuote(request); store result in Redis key quote:{quoteId} with EX = ttl; Return RateQuoteResponse DTO; omit USD pool fields for domestic same-currency short-circuit; Add X-Request-ID response header via ResponseHeaderFilter (lib-api-contracts)
**Deliverable:** services/rate-fx/src/main/java/com/gme/pay/ratefx/api/RateQuoteController.java
**Acceptance / logic checks:**
- POST with valid inbound KRW/USD returns HTTP 200 with quote_id and non-null valid_until
- valid_until = quote_issued_at + rate_quote_ttl_seconds within 1-second tolerance
- Domestic same-currency request omits collection_usd and payout_usd_cost from response
- Unknown scheme_id returns HTTP 404 SCHEME_NOT_FOUND
- Response always includes X-Request-ID header
**Depends on:** 8.10-T06

### 8.10-T12 — Implement QuoteStoreService: Redis-backed quote persistence and expiry check for rate-fx service  _(35 min)_
**Context:** Rate quotes cached in Redis at key quote:{quoteId} with TTL = rate_quote_ttl_seconds (60-1800 s; default 300). At payment time, POST /v1/payments calls QuoteStoreService.fetchQuote(quoteId, partnerId) which returns the stored quote or throws RateQuoteExpiredException if TTL elapsed or not found or wrong partner. Quote record must carry all locked rate values: offer_rate, send_amount, service_charge, collection_usd, payout_usd_cost, collection_margin_usd, payout_margin_usd, cross_rate. Module: services/rate-fx.
**Steps:** Create services/rate-fx/src/main/java/com/gme/pay/ratefx/service/QuoteStoreService.java @Service; storeQuote(RateQuote quote, int ttlSeconds): serialize to JSON, SETEX quote:{quoteId} ttlSeconds; fetchQuote(String quoteId, UUID partnerId): GET + deserialize; throw RateQuoteExpiredException if absent or partner mismatch; Add integration test with Testcontainers Redis: store then fetch within TTL passes; fetch after TTL throws exception
**Deliverable:** services/rate-fx/src/main/java/com/gme/pay/ratefx/service/QuoteStoreService.java
**Acceptance / logic checks:**
- Fetch within TTL returns full quote with all NUMERIC rate fields (BigDecimal)
- Fetch after TTL throws RateQuoteExpiredException (maps to HTTP 422 RATE_QUOTE_EXPIRED)
- Fetch with wrong partnerId throws RateQuoteExpiredException
- TTL in Redis matches the configured rate_quote_ttl_seconds value
- BigDecimal serialization round-trip preserves at least 10 decimal places of precision
**Depends on:** 8.10-T11

### 8.10-T13 — Implement PaymentExecutorController POST /v1/payments (Fixed MPM) in services/payment-executor  _(55 min)_
**Context:** API-05 §4.3: POST /v1/payments requires quote_id, merchant_qr, direction, scheme_id, customer_ref, partner_txn_ref, collection_amount, collection_currency. Flow: (1) validate quote not expired via QuoteStoreService, (2) OVERSEAS only: atomic prefund deduction via SELECT FOR UPDATE (reject HTTP 402 INSUFFICIENT_PREFUNDING if low), (3) call Scheme Adapter commitPayment via Anti-Corruption Layer, (4) lock rate -- store all USD pool values in PostgreSQL transaction record, (5) return HTTP 201 with payment_id, status, scheme_txn_id, offer_rate, rate-locked fields, (6) write payment.approved outbox event.
**Steps:** Create services/payment-executor/src/main/java/com/gme/pay/executor/api/PaymentExecutorController.java @RestController; Validate quote not expired via QuoteStoreService.fetchQuote; return 422 RATE_QUOTE_EXPIRED if expired; For OVERSEAS: call PrefundingService.deductAtomic(partnerId, amountUsd); return 402 if throws InsufficientPrefundingException; Call SchemeAdapterService.commitPayment(prepare_token, amountKrw) via adapter port; Persist transaction record with all RATE-04 locked USD pool values; write TRANSACTION_COMMITTED and PREFUND_DEDUCTED event to outbox; Return PaymentResponse HTTP 201; write payment.approved outbox event for webhook dispatch
**Deliverable:** services/payment-executor/src/main/java/com/gme/pay/executor/api/PaymentExecutorController.java
**Acceptance / logic checks:**
- Expired quote_id returns HTTP 422 RATE_QUOTE_EXPIRED before any prefund deduction (WireMock scheme stub sees zero calls)
- OVERSEAS partner with balance 10.00 USD and required deduction 35.77 USD returns HTTP 402 INSUFFICIENT_PREFUNDING
- Successful payment returns HTTP 201 with all rate-locked values matching the stored quote
- scheme call never made if prefund deduction fails
- Duplicate partner_txn_ref with different body returns HTTP 409 DUPLICATE_PARTNER_TXN_REF
**Depends on:** 8.10-T06, 8.10-T12

### 8.10-T14 — Implement PrefundingService.deductAtomic with SELECT FOR UPDATE in PostgreSQL  _(50 min)_
**Context:** Canonical RATE-04 fact: prefund deduction is ATOMIC (SELECT FOR UPDATE). Table prefunding_balance (partner_id UUID PK, balance_usd NUMERIC(18,2), low_balance_threshold_usd NUMERIC(18,2), updated_at TIMESTAMPTZ). Deduct at POST /v1/payments (MPM) and POST /v1/payments/cpm/generate (CPM). Scheme is never called without prior successful deduction. After deduction if balance < threshold emit LowBalanceEvent to outbox. LOCAL partners are exempt. Module: services/prefunding-balance.
**Steps:** Create services/prefunding-balance/src/main/java/com/gme/pay/prefunding/service/PrefundingService.java @Service; deductAtomic(UUID partnerId, BigDecimal amountUsd): @Transactional -- SELECT balance FOR UPDATE, check >= amountUsd else throw InsufficientPrefundingException, UPDATE balance = balance - amountUsd, insert prefunding_ledger row; Return DeductResult (new balance, deducted amount, low_balance flag); If post-deduction balance < low_balance_threshold, write LowBalanceEvent to outbox; Add integration test with Testcontainers Postgres: two concurrent threads each deducting 50 USD from 100 USD balance -- exactly one succeeds, one throws
**Deliverable:** services/prefunding-balance/src/main/java/com/gme/pay/prefunding/service/PrefundingService.java
**Acceptance / logic checks:**
- Concurrent calls from 2 threads each deducting 50 USD from 100 USD: total deducted = 50 USD (one fails), no double-spend
- Balance stored as NUMERIC(18,2) -- no floating-point drift
- LOCAL partner invocation throws UnsupportedOperationException
- LowBalanceEvent written to outbox when post-deduction balance < threshold
- SELECT FOR UPDATE row lock held within transaction (verify via pg_locks in integration test)
**Depends on:** 8.10-T03

### 8.10-T15 — Implement CPM token endpoint POST /v1/payments/cpm/generate in services/payment-executor  _(45 min)_
**Context:** API-05 §4.4: POST /v1/payments/cpm/generate takes scheme_id, direction, customer_ref, partner_txn_ref, country_code, optional prefund_reserve_usd. For OVERSEAS: atomic prefund deduction before scheme call (8.10-T14). Returns cpm_token_id, prepare_token, qr_content, expires_at (60 s configurable), prefund_reserved_usd, payment_id (pre-allocated). Token stored in Redis key cpm_token:{cpmTokenId} TTL 60 s. Rate is computed by scheme at scan time and delivered via payment.pending_debit webhook. Module: services/payment-executor.
**Steps:** Add POST /v1/payments/cpm/generate handler in PaymentExecutorController; For OVERSEAS: call PrefundingService.deductAtomic with prefund_reserve_usd or scheme max limit; Call SchemeAdapterService.prepareCPM to get prepare_token from ZeroPay adapter; Store CPM token in Redis key cpm_token:{cpmTokenId} with EX 60; assign pre-allocated payment_id; Return CpmGenerateResponse HTTP 201; write PREFUND_DEDUCTED event to outbox; Unit test: OVERSEAS partner deduction before scheme call; LOCAL partner skips deduction
**Deliverable:** services/payment-executor/src/main/java/com/gme/pay/executor/api/PaymentExecutorController.java (updated with CPM handler)
**Acceptance / logic checks:**
- OVERSEAS: scheme adapter WireMock stub is NOT called when prefund deduction fails (HTTP 402)
- prepare_token and qr_content populated from scheme adapter response
- expires_at = now + 60 s (within 1-second tolerance)
- payment_id pre-allocated and stable for subsequent GET /v1/payments/{id} polling
- Redis key cpm_token:{cpmTokenId} exists with TTL <= 60 s immediately after call
**Depends on:** 8.10-T13, 8.10-T14

### 8.10-T16 — Implement GET /v1/payments/{id} payment status endpoint in services/payment-executor  _(35 min)_
**Context:** API-05 §4.5: GET /v1/payments/{id} returns full payment object including status (pending|approved|failed|cancelled|uncertain), all rate-locked fields, scheme_txn_id, created_at, approved_at, updated_at, cancelled_at where applicable. Returns HTTP 404 PAYMENT_NOT_FOUND if payment_id not owned by calling partner. Reads from PostgreSQL transaction table. Module: services/payment-executor.
**Steps:** Add GET /v1/payments/{id} handler in PaymentExecutorController; Load transaction from PostgreSQL by payment_id with partner_id FK constraint check; Map to PaymentDetailResponse DTO including all rate-locked BigDecimal fields; Return HTTP 404 PAYMENT_NOT_FOUND if not found or partner mismatch; Add @SpringBootTest slice test with Testcontainers Postgres: create payment, fetch, verify all fields present
**Deliverable:** services/payment-executor/src/main/java/com/gme/pay/executor/api/PaymentExecutorController.java (updated with GET handler)
**Acceptance / logic checks:**
- Returns HTTP 200 with all fields matching the stored transaction record
- Calling with a payment_id owned by different partner returns HTTP 404 PAYMENT_NOT_FOUND
- status reflects latest state including cancelled and uncertain values
- All rate-locked BigDecimal fields serialized as strings in JSON response
- updated_at and cancelled_at are present when applicable
**Depends on:** 8.10-T13

### 8.10-T17 — Implement POST /v1/payments/{id}/cancel with same-day KST guard and prefund return  _(40 min)_
**Context:** API-05 §4.6: cancel allowed only for payments in approved or pending status on the same calendar day in KST (UTC+9). HTTP 400 CANCEL_NOT_PERMITTED if cross-day or wrong status. HTTP 409 if already in terminal state. Reason codes: customer_request, merchant_request, timeout, other. For OVERSEAS: return prefund_returned_usd to prefunding_balance in same transaction. Emit payment.cancelled outbox event for webhook delivery. Module: services/payment-executor.
**Steps:** Add POST /v1/payments/{id}/cancel handler in PaymentExecutorController; Check payment status in [approved, pending]; check created_at date in KST = today in KST; else return HTTP 400 CANCEL_NOT_PERMITTED; Call SchemeAdapterService.cancelPayment(schemeTxnRef); For OVERSEAS: @Transactional call PrefundingService.returnFunds(partnerId, amountUsd) then update transaction status to cancelled; Write payment.cancelled outbox event; unit test: cancel on next-day payment, cancel on already-cancelled payment
**Deliverable:** services/payment-executor/src/main/java/com/gme/pay/executor/api/PaymentExecutorController.java (updated with cancel handler)
**Acceptance / logic checks:**
- Cancel at 23:59 KST day N succeeds; cancel at 00:01 KST day N+1 returns HTTP 400 CANCEL_NOT_PERMITTED
- Cancelling a failed payment returns HTTP 409
- OVERSEAS cancel: prefunding_balance restored by prefund_returned_usd (verify via SELECT after cancel)
- Scheme adapter cancelPayment stub called exactly once per successful cancel
- payment.cancelled outbox event written with reason field matching request
**Depends on:** 8.10-T14, 8.10-T16

### 8.10-T18 — Implement GET /v1/merchants/{qr} merchant resolution in services/merchant-qr-data (MongoDB)  _(35 min)_
**Context:** API-05 §4.7: GET /v1/merchants/{qr} resolves URL-encoded QR string against MongoDB merchants collection (populated by daily ZeroPay sync SCH-06). Returns merchant_id, merchant_name, merchant_type, scheme_id, qr_code, status, payout_currency, address. HTTP 404 MERCHANT_NOT_FOUND if absent; HTTP 422 if merchant status == inactive. Module: services/merchant-qr-data (MongoDB datastore, not PostgreSQL).
**Steps:** Create services/merchant-qr-data/src/main/java/com/gme/pay/merchant/api/MerchantController.java @RestController; GET /v1/merchants/{qr}: URL-decode path param; query MongoDB merchants collection by qr_code field; Map document to MerchantDetailResponse; check status field: inactive -> HTTP 422; Return HTTP 404 MERCHANT_NOT_FOUND if document not found; Add integration test with Testcontainers MongoDB: seed one active and one inactive merchant, verify both response paths
**Deliverable:** services/merchant-qr-data/src/main/java/com/gme/pay/merchant/api/MerchantController.java
**Acceptance / logic checks:**
- Active merchant QR returns HTTP 200 with all 8 response fields populated
- Inactive merchant returns HTTP 422 (not 404)
- Unknown QR returns HTTP 404 MERCHANT_NOT_FOUND
- QR value containing + or / characters is URL-decoded before MongoDB lookup
- MongoDB query uses index on qr_code field (verified via explain in integration test)

### 8.10-T19 — Implement GET /v1/balance prefunding balance endpoint in services/prefunding-balance  _(30 min)_
**Context:** API-05 §4.8: GET /v1/balance returns partner_id, balance_usd, low_balance_threshold_usd, is_below_threshold, as_of. Optional ?include_history=true returns last 10 deduction events from prefunding_ledger (fields: payment_id, amount_usd, balance_after_usd, event_at). LOCAL partners (type LOCAL) return HTTP 403. OVERSEAS partners only. Module: services/prefunding-balance (PostgreSQL).
**Steps:** Create services/prefunding-balance/src/main/java/com/gme/pay/prefunding/api/BalanceController.java @RestController; Inject partner type from authenticated principal; return HTTP 403 for LOCAL type; Query prefunding_balance by partner_id; If include_history=true: SELECT last 10 rows from prefunding_ledger WHERE partner_id = ? ORDER BY event_at DESC LIMIT 10; Return BalanceResponse DTO; is_below_threshold = balance_usd < low_balance_threshold_usd
**Deliverable:** services/prefunding-balance/src/main/java/com/gme/pay/prefunding/api/BalanceController.java
**Acceptance / logic checks:**
- LOCAL partner returns HTTP 403
- OVERSEAS partner balance 48234.56 USD threshold 10000.00: is_below_threshold = false
- OVERSEAS partner balance 5000.00 threshold 10000.00: is_below_threshold = true
- include_history=true returns array of max 10 events with all 4 fields (payment_id, amount_usd, balance_after_usd, event_at)
- as_of timestamp is within 1 s of server time at query execution
**Depends on:** 8.10-T14

### 8.10-T20 — Implement WebhookDispatchService: transactional Outbox polling and delivery with exponential backoff  _(50 min)_
**Context:** API-05 §6.1-6.2: GMEPay+ POSTs webhook events to partner webhook_url; partner must respond HTTP 2xx within 10 s. Retry schedule per API-05: attempt 1 immediate, 2 at +30 s, 3 at +2 min, 4 at +10 min, 5 at +30 min, 6-10 at +1 h each (total ~6 h). After 10 failures: mark delivery_failed, alert Ops. Phase 1: poll transactional Outbox table in PostgreSQL. Kafka deferred behind EventPublisher interface (lib-events). Module: services/notification-webhook.
**Steps:** Create services/notification-webhook/src/main/java/com/gme/pay/webhook/service/WebhookDispatchService.java @Service @Scheduled; Poll outbox table for events with status = pending AND next_attempt_at <= now; Send HTTP POST to webhook_url using RestClient with 10-s timeout; On success: set status = delivered, delivered_at = now; On failure: increment attempt_count, compute next_attempt_at per backoff schedule; after 10 failures set status = delivery_failed and write OpsAlertEvent to outbox
**Deliverable:** services/notification-webhook/src/main/java/com/gme/pay/webhook/service/WebhookDispatchService.java
**Acceptance / logic checks:**
- next_attempt_at after attempt 1 failure = event_time + 30 s (within 1 s)
- next_attempt_at after attempt 5 failure = event_time + 42 min 30 s (sum: 0 + 30s + 2m + 10m + 30m)
- After 10 failures status = delivery_failed in outbox table
- Successful delivery stops retries (status = delivered, no further next_attempt_at update)
- HTTP 3xx redirect from webhook URL counted as failure (not 2xx)

### 8.10-T21 — Implement WebhookSigningService: HMAC-SHA256 signing of outgoing webhook payloads  _(30 min)_
**Context:** API-05 §6.3: outgoing webhook requests carry X-GME-Webhook-Signature: sha256=<HMAC-SHA256(body, webhook_signing_secret)>, X-GME-Event-ID (outbox event UUID), X-GME-Webhook-Timestamp (ISO-8601 UTC ms). Webhook signing secret is distinct from API secret; stored in Vault per partner (secret path secret/gmepay/webhooks/{partnerId}). Partners must verify signature and reject events older than 5 min. Module: services/notification-webhook.
**Steps:** Create services/notification-webhook/src/main/java/com/gme/pay/webhook/service/WebhookSigningService.java @Service; sign(byte[] body, String webhookSigningSecret): return sha256= + HMAC-SHA256 hex; In WebhookDispatchService: before POST, call WebhookSigningService.sign; add X-GME-Webhook-Signature, X-GME-Webhook-Timestamp, X-GME-Event-ID headers; Unit test: verify header format is sha256={lowercase_hex}, HMAC over empty body matches test vector; Confirm webhook_signing_secret loaded from Vault not api_secret
**Deliverable:** services/notification-webhook/src/main/java/com/gme/pay/webhook/service/WebhookSigningService.java
**Acceptance / logic checks:**
- X-GME-Webhook-Signature value starts with sha256=
- HMAC-SHA256 over empty body produces stable known hex (test vector)
- X-GME-Event-ID equals the outbox event UUID
- X-GME-Webhook-Timestamp is ISO-8601 UTC millisecond precision
- Signing uses webhook_signing_secret not api_secret (injected as distinct parameter)
**Depends on:** 8.10-T20

### 8.10-T22 — Define lib-events Avro/JSON schemas for all 5 webhook event types  _(40 min)_
**Context:** API-05 §6.5 defines 5 webhook event types: payment.approved, payment.pending_debit, payment.failed, payment.cancelled, settlement.completed. All share envelope: event_id, event_type, created_at, partner_id, data {}. Phase 1: events stored in outbox as JSON. Kafka wire format deferred behind EventPublisher interface. Schemas live in lib-events. Module: lib-events.
**Steps:** Create lib-events/src/main/avro/payment_approved.avsc with all data fields from API-05 §6.5; Create avsc schemas for payment_pending_debit, payment_failed, payment_cancelled, settlement_completed; Mirror each as JSON Schema in lib-events/src/main/resources/schemas/; Add WebhookEventType enum in lib-events/src/main/java/com/gme/pay/events/WebhookEventType.java; Write EventSchemaTest: serialize then deserialize each event type, assert no field loss
**Deliverable:** lib-events/src/main/avro/ (5 .avsc files) and lib-events/src/main/resources/schemas/ (5 .json files)
**Acceptance / logic checks:**
- payment_approved.avsc includes payment_id, partner_txn_ref, scheme_txn_id, offer_rate, collection_amount, prefund_deducted_usd, approved_at
- payment_pending_debit.avsc includes estimated_collection_amount and cpm_token_id
- payment_failed.avsc includes failure_code, failure_message, prefund_returned_usd
- settlement_completed.avsc includes settlement_id, settlement_date, total_payout_krw, total_prefund_deducted_usd, batch_file_ref
- Round-trip serialize/deserialize for each type produces identical object (EventSchemaTest passes)

### 8.10-T23 — Implement rate limiting in Spring Cloud Gateway for Partner API: per-partner per-endpoint Redis token-bucket  _(45 min)_
**Context:** API-05 §3.5: rate limits -- 100 req/s per partner (all endpoints), 20 req/s for POST /v1/rates, 50 req/s for POST /v1/payments and POST /v1/payments/cpm/generate. Exceed -> HTTP 429 with Retry-After header. Response headers: X-RateLimit-Limit, X-RateLimit-Remaining, X-RateLimit-Reset (Unix epoch seconds). Implementation: Spring Cloud Gateway RedisRateLimiter. Limits configurable per partner by GME Ops. Module: services/api-gateway.
**Steps:** Configure RedisRateLimiter in services/api-gateway/src/main/resources/application.yml for routes /v1/rates (20/s), /v1/payments (50/s), /v1/payments/cpm/generate (50/s), and global (100/s); Implement PartnerKeyResolver @Bean in services/api-gateway/src/main/java/com/gme/pay/gateway/PartnerKeyResolver.java extracting partner_id from X-API-Key header; Store per-partner limit overrides in Redis or config-service with fallback to defaults; Add GlobalFilter to inject X-RateLimit-Limit, X-RateLimit-Remaining, X-RateLimit-Reset headers on every response; Integration test: burst 25 POST /v1/rates calls in 1 s, verify 5 rejected with HTTP 429 and Retry-After
**Deliverable:** services/api-gateway/src/main/resources/application.yml (rate limiter config) and services/api-gateway/src/main/java/com/gme/pay/gateway/PartnerKeyResolver.java
**Acceptance / logic checks:**
- 21st POST /v1/rates within 1 s returns HTTP 429 with Retry-After header
- 51st POST /v1/payments within 1 s returns HTTP 429
- X-RateLimit-Remaining decrements from limit to 0 across accepted requests
- X-RateLimit-Reset is a Unix epoch integer (not ISO-8601)
- Per-partner limit override in Redis takes precedence over default

### 8.10-T24 — Implement sandbox simulator: deterministic ZeroPay scheme adapter for 5 magic test QR values  _(50 min)_
**Context:** API-05 §10.3: sandbox magic QR values trigger deterministic outcomes. ZPQR_TEST_APPROVED -> approved immediately; ZPQR_TEST_PENDING -> pending 30 s then approved; ZPQR_TEST_DECLINED -> SCHEME_UNAVAILABLE; ZPQR_TEST_TIMEOUT -> status uncertain; ZPQR_TEST_INACTIVE -> MERCHANT_NOT_FOUND. Sandbox simulator activated via Spring profile sandbox replacing the real ZeroPay adapter. Module: services/scheme-adapter-zeropay.
**Steps:** Create SandboxZeroPaySchemeAdapter @Profile(sandbox) @Service in services/scheme-adapter-zeropay/src/main/java/com/gme/pay/scheme/zeropay/sandbox/SandboxZeroPaySchemeAdapter.java implementing SchemeAdapterPort; commitPayment: switch on merchant_qr; APPROVED returns SchemeResult.approved with fake approval code; DECLINED throws SchemeUnavailableException; TIMEOUT returns SchemeResult.uncertain; ZPQR_TEST_PENDING: return SchemeResult.pending initially; schedule status flip to approved after 30 s via ScheduledExecutorService; parseMerchantQR: ZPQR_TEST_INACTIVE throws MerchantNotFoundException; Add @SpringBootTest sandbox profile test covering all 5 QR values
**Deliverable:** services/scheme-adapter-zeropay/src/main/java/com/gme/pay/scheme/zeropay/sandbox/SandboxZeroPaySchemeAdapter.java
**Acceptance / logic checks:**
- ZPQR_TEST_APPROVED returns SchemeResult.approved with non-null approval code
- ZPQR_TEST_DECLINED throws SchemeUnavailableException (maps to HTTP 422 SCHEME_UNAVAILABLE)
- ZPQR_TEST_TIMEOUT returns SchemeResult.uncertain
- ZPQR_TEST_INACTIVE parseMerchantQR throws MerchantNotFoundException (maps to HTTP 422 on merchant lookup)
- ZPQR_TEST_PENDING: commitPayment returns pending; after 30 s transition GET /v1/payments/{id} returns approved

### 8.10-T25 — Write certification checklist: integration-kit/docs/certification-checklist.md (C-01 to C-16)  _(45 min)_
**Context:** API-05 §10.4: partners must pass 16 certification tests before production credentials are issued. Each test has ID, name, expected HTTP status, and pass criteria. Sandbox base URL: https://api-sandbox.gmepayplus.com. Credentials format: X-API-Key pk_test_XXX, API Secret sk_test_YYY. Document location: integration-kit/docs/certification-checklist.md.
**Steps:** Create integration-kit/docs/certification-checklist.md; For each of the 16 tests (C-01 to C-16) write: test ID, name, purpose, signed curl command referencing HmacSampleClient, expected HTTP status, 2-3 pass criteria; C-01: POST /v1/rates inbound KRW -> HTTP 200 with quote_id and valid_until; C-03: POST /v1/payments with expired quote_id -> HTTP 422 RATE_QUOTE_EXPIRED; C-13: POST /v1/payments insufficient prefunding -> HTTP 402 INSUFFICIENT_PREFUNDING; C-15: POST with tampered X-Signature -> HTTP 401 INVALID_SIGNATURE; C-16: same Idempotency-Key + same body repeated -> HTTP 201 stored response, no double-processing
**Deliverable:** integration-kit/docs/certification-checklist.md
**Acceptance / logic checks:**
- All 16 test cases C-01 through C-16 present with HTTP status codes matching API-05 §10.4 table exactly
- C-10 documents both OVERSEAS (200) and LOCAL (403) expected outcomes
- C-11 includes webhook signature verification step (X-GME-Webhook-Signature verification)
- C-16 idempotent retry explicitly states partner must verify no double scheme call occurred
- Each test includes a curl command example referencing signed headers from HmacSampleClient
**Depends on:** 8.10-T07, 8.10-T10

### 8.10-T26 — Write partner onboarding guide: integration-kit/docs/onboarding-guide.md (7 sections)  _(50 min)_
**Context:** WBS 8.10 deliverable: onboarding guide for partner engineers. Must cover: sandbox vs production environments, HMAC signing step-by-step, first rate quote and payment, webhook setup and signature verification, idempotency key rules, error handling and retries, certification process. Cross-references API-05 §2 through §10. Document location: integration-kit/docs/onboarding-guide.md.
**Steps:** Create integration-kit/docs/onboarding-guide.md; Section 1: Overview (one hub, many QR schemes; partner role); Section 2: Sandbox environment -- base URL, pre-loaded prefunding, magic QR values, simulated hourly settlement; Section 3: HMAC signing step-by-step -- annotated canonical string example, each header labeled, 300-s drift rule, 10-min replay rule; Section 4: First rate quote (POST /v1/rates) and Fixed MPM payment (POST /v1/payments) with example payloads from API-05; Section 5: Webhook setup -- register URL, verify X-GME-Webhook-Signature (sha256= prefix, HMAC-SHA256), idempotent event processing; Section 6: Error handling and retry guide -- table of all 20 error codes with retryable flag per API-05 §8.2; Section 7: Certification process -- link to certification-checklist.md, credential promotion path
**Deliverable:** integration-kit/docs/onboarding-guide.md
**Acceptance / logic checks:**
- Section 3 canonical string shows exactly 4 lines with LF separator, matching API-05 §3.2 verbatim
- Section 5 webhook verification shows stripping sha256= prefix before HMAC comparison
- Section 6 error table contains all 20 error codes from API-05 §8.2 with correct HTTP status and retryable column
- Section 4 retry guidance matches API-05 §7.2: same key for 5xx/429, new key for 402/422/4xx validation
- Section 2 lists all 5 magic QR values (ZPQR_TEST_APPROVED through ZPQR_TEST_INACTIVE) with expected outcomes
**Depends on:** 8.10-T07, 8.10-T08, 8.10-T09, 8.10-T25

### 8.10-T27 — Unit test suite: HmacAuthFilterTest covering all authentication failure paths  _(45 min)_
**Context:** Tests for HmacAuthFilter (8.10-T02), IpAllowlistService (8.10-T04), and ReplayProtectionService (8.10-T05) in services/auth-identity. Use JUnit 5 + @WebMvcTest. WireMock for Vault secret retrieval. Test vectors must be deterministic: SHA-256('') = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855, canonical-string HMAC must be hard-coded. Module: services/auth-identity.
**Steps:** Create services/auth-identity/src/test/java/com/gme/pay/auth/filter/HmacAuthFilterTest.java @WebMvcTest; Test missing X-API-Key -> 401 INVALID_API_KEY; Test X-Timestamp 301 s in past -> 401 TIMESTAMP_OUT_OF_RANGE; Test correct signature and timestamp -> 200 passthrough; Test tampered body -> 401 INVALID_SIGNATURE (body hash differs); Test IP not in allowlist -> 403 IP_NOT_ALLOWLISTED; Test replay (same signature twice) -> 401 INVALID_SIGNATURE on second call; Stub Vault via WireMock returning sk_test_abc123 for test api key
**Deliverable:** services/auth-identity/src/test/java/com/gme/pay/auth/filter/HmacAuthFilterTest.java
**Acceptance / logic checks:**
- All 7 test cases pass with correct HTTP status and error code in JSON body
- Tampered-body test uses deterministic vector: SHA-256 of 'hello world' = b94d27b9934d3e08a52e52d7da7dabfac484efe04294e576a3b6c0e992b16c6
- Replay test verifies second identical request returns 401 within the 600-s window
- WireMock Vault stub called once per test (not cached across tests)
- Tests run in CI using Testcontainers Redis for replay store (no external Redis required)
**Depends on:** 8.10-T02, 8.10-T04, 8.10-T05

### 8.10-T28 — Unit test suite: PaymentExecutorControllerTest for MPM payment happy path and all rejection paths  _(55 min)_
**Context:** Tests for POST /v1/payments in services/payment-executor (8.10-T13). Use JUnit 5 + @SpringBootTest with Testcontainers Postgres and Redis. WireMock for ZeroPay scheme adapter. Test vector from API-05 §4.3 example: target_payout = 50000 KRW, OVERSEAS partner balance = 100.00 USD, expected deduction = 35.77 USD, pool identity: 36.37 - 0.91 - 0.40 == 35.06 (within 0.01 USD). Module: services/payment-executor.
**Steps:** Create services/payment-executor/src/test/java/com/gme/pay/executor/api/PaymentExecutorControllerTest.java; Happy path: valid quote, OVERSEAS balance 100 USD, merchant ZPQR_TEST_APPROVED -> HTTP 201 with payment_id, scheme_txn_id, all rate-locked fields; Expired quote -> HTTP 422 RATE_QUOTE_EXPIRED; WireMock scheme stub asserts zero invocations; OVERSEAS balance 10 USD deduction 35.77 -> HTTP 402; WireMock scheme stub asserts zero invocations; Duplicate partner_txn_ref different body -> HTTP 409; LOCAL partner same flow -> HTTP 201 without prefund deduction (prefunding_balance not touched)
**Deliverable:** services/payment-executor/src/test/java/com/gme/pay/executor/api/PaymentExecutorControllerTest.java
**Acceptance / logic checks:**
- Happy path response includes all fields matching stored quote (offer_rate = 0.000703)
- Post-deduction prefunding_balance row = 100.00 - 35.77 = 64.23 (NUMERIC, no float drift)
- Pool identity: collection_usd(36.37) - collection_margin_usd(0.91) - payout_margin_usd(0.40) == payout_usd_cost(35.06) within 0.01 USD
- Idempotent retry: same key + same body returns 201 with stored response, WireMock scheme stub has exactly 1 total invocation
- LOCAL partner test verifies no row change in prefunding_balance table
**Depends on:** 8.10-T13, 8.10-T14

### 8.10-T29 — Unit test suite: WebhookDispatchServiceTest covering retry schedule and delivery-failed escalation  _(45 min)_
**Context:** Tests for WebhookDispatchService (8.10-T20) retry schedule per API-05 §6.2: immediate, +30 s, +2 min, +10 min, +30 min, +1 h each up to 10 attempts. After 10 failures: delivery_failed. Use JUnit 5 + Testcontainers Postgres (outbox table) + WireMock for partner webhook URL. Module: services/notification-webhook.
**Steps:** Create services/notification-webhook/src/test/java/com/gme/pay/webhook/service/WebhookDispatchServiceTest.java; Test: first delivery fails (WireMock returns 500) -> next_attempt_at = now + 30 s (within 1 s tolerance); Test: fifth failure -> next_attempt_at = now + 30 min (cumulative delay: 0+30s+2m+10m+30m); Test: tenth failure -> outbox row status = delivery_failed, OpsAlertEvent written to outbox; Test: success on attempt 3 -> status = delivered, no further retry; WireMock: stub webhook URL to return 500 for N attempts then 200
**Deliverable:** services/notification-webhook/src/test/java/com/gme/pay/webhook/service/WebhookDispatchServiceTest.java
**Acceptance / logic checks:**
- next_attempt_at after attempt 1 failure is 30 s after event_time (within 1 s)
- next_attempt_at after attempt 5 failure is approximately event_time + 42 min 30 s
- After 10 failures outbox row status = delivery_failed and OpsAlertEvent present in outbox
- Successful second attempt leaves status = delivered with no next_attempt_at
- HTTP 301 redirect counted as failure (WireMock redirect stub used in one test)
**Depends on:** 8.10-T20, 8.10-T21

### 8.10-T30 — Contract test: validate all 7 live endpoint responses against partner-api.yaml OpenAPI spec  _(40 min)_
**Context:** Ensure runtime responses from all 7 Partner API endpoints conform to integration-kit/openapi/partner-api.yaml (8.10-T10). Use openapi4j RequestValidator/ResponseValidator in @SpringBootTest. Validates both success and error response shapes. Module: integration-tests/ module in monorepo root.
**Steps:** Create integration-tests/src/test/java/com/gme/pay/contract/PartnerApiContractTest.java; Load integration-kit/openapi/partner-api.yaml with openapi4j schema; For each of 7 endpoints issue a test request via MockMvc or TestRestTemplate and validate response body against schema; Assert zero schema violations for success responses and for error responses (400, 402, 404, 422); Add contract test to CI pipeline gate: must pass before Docker image build step
**Deliverable:** integration-tests/src/test/java/com/gme/pay/contract/PartnerApiContractTest.java
**Acceptance / logic checks:**
- Zero schema violations for POST /v1/rates HTTP 200 response
- Zero schema violations for POST /v1/payments HTTP 201 and HTTP 402 responses
- Zero schema violations for GET /v1/balance HTTP 200 and HTTP 403 responses
- Test fails if a required response field (e.g. quote_id) is removed from controller output
- Error envelope responses validate against ErrorResponse schema (error.code, error.message, error.request_id present)
**Depends on:** 8.10-T10, 8.10-T11, 8.10-T13, 8.10-T16, 8.10-T17, 8.10-T18, 8.10-T19
