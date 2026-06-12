# partner-portal-ui  (frontend)

**Scope:** Partner self-service portal (React/Next.js): balance, history

**Owned WBS work-packages:** 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7, 12.3  ·  **Tickets:** 240  ·  **Est:** 154.8h

## Service contract (MSA: own DB, API-only communication)

- **Datastore (owned by this service):** none (browser client)
- **APIs / events I EXPOSE:** Partner self-service web app (React/Next.js)
- **APIs / events I CONSUME:** bff + service APIs (read), sync
- **Integration rule:** never read another service's database or import its private entities — call its API or consume its event; stub consumed services with WireMock in tests.

> Self-contained backlog for this service. Build it as its own repo/module with its own DB + Flyway migrations, against the `shared-libs` contracts (lib-money / lib-errors / lib-events / lib-api-contracts only). Each ticket has a deliverable + acceptance checks.


## WBS 11.1 — Portal shell, scoped auth & isolation
### 11.1-T01 — DB migration: portal_user table with partner scope and auth fields  _(25 min)_
**Context:** The Partner Portal requires a separate user-account store from the Partner API credential store. Each portal user belongs to exactly one partner (partner_id FK, non-nullable). Auth fields required: email (unique), password_hash (bcrypt, cost >= 12), totp_secret_hash (TOTP seed stored hashed), totp_recovery_codes (hashed array), role (ENUM: PARTNER_ADMIN | PARTNER_VIEWER), is_active (BOOLEAN), failed_login_count (INT default 0), locked_until (TIMESTAMPTZ nullable), last_login_at (TIMESTAMPTZ), created_by_operator_id (UUID FK to admin_users), created_at, updated_at. UUIDs (v4) for all IDs per SEC-09 T-09 (no enumerable IDs).
**Steps:** Create migration file V11_1_001__portal_user.sql; Define table portal_users with columns: id UUID PK DEFAULT gen_random_uuid(), partner_id UUID NOT NULL REFERENCES partners(id), email VARCHAR(254) NOT NULL, password_hash VARCHAR(128) NOT NULL, totp_secret_hash VARCHAR(128) NOT NULL, totp_recovery_codes TEXT[] NOT NULL DEFAULT '{}', role VARCHAR(32) NOT NULL CHECK (role IN ('PARTNER_ADMIN','PARTNER_VIEWER')), is_active BOOLEAN NOT NULL DEFAULT TRUE, failed_login_count INT NOT NULL DEFAULT 0, locked_until TIMESTAMPTZ NULL, last_login_at TIMESTAMPTZ NULL, created_by_operator_id UUID NOT NULL REFERENCES admin_users(id), created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(); Add unique constraint on (email) -- email is globally unique across all partners; Add index idx_portal_users_partner_id on portal_users(partner_id); Run migration and verify schema via \d portal_users
**Deliverable:** Migration file V11_1_001__portal_user.sql applied cleanly to the dev database
**Acceptance / logic checks:**
- Table portal_users exists with all specified columns and correct types
- UNIQUE constraint on email: inserting duplicate email raises DB error
- FK partner_id references partners(id); inserting non-existent partner_id raises FK error
- role column rejects values outside PARTNER_ADMIN and PARTNER_VIEWER
- id is UUID v4 (no sequential integer); two rows get distinct UUIDs

### 11.1-T02 — DB migration: portal_session table for JWT refresh-token tracking  _(20 min)_
**Context:** Portal sessions issue short-lived JWTs (access token) plus a refresh token stored server-side for revocation. Table portal_sessions: id UUID PK, portal_user_id UUID FK portal_users(id) NOT NULL, refresh_token_hash VARCHAR(128) NOT NULL (SHA-256 of plaintext token), issued_at TIMESTAMPTZ NOT NULL, expires_at TIMESTAMPTZ NOT NULL (issued_at + 24h absolute limit per PRD-08 §2.1), last_active_at TIMESTAMPTZ NOT NULL (updated on each valid request for idle-timeout tracking), revoked_at TIMESTAMPTZ NULL, revocation_reason VARCHAR(64) NULL. Session idle timeout = 15 min (SEC-09 §3.2); absolute limit = 24 h (PRD-08 §2.1).
**Steps:** Create migration file V11_1_002__portal_session.sql; Define table portal_sessions with columns as described in context; Add index idx_portal_sessions_user on portal_sessions(portal_user_id); Add index idx_portal_sessions_expires on portal_sessions(expires_at) for cleanup jobs; Run migration and verify schema
**Deliverable:** Migration file V11_1_002__portal_session.sql applied cleanly
**Acceptance / logic checks:**
- portal_user_id FK enforced: inserting orphan user_id raises error
- expires_at = issued_at + interval '24 hours' enforced by application (DB column allows any value; test enforced at service layer in T08)
- revoked_at is nullable: active sessions have NULL revoked_at
- idx_portal_sessions_expires index exists for efficient expired-session cleanup
- Dual-index (portal_user_id, revoked_at) allows fast lookup of active sessions per user
**Depends on:** 11.1-T01

### 11.1-T03 — DB migration: portal_notification table (per-user, per-partner alerts)  _(20 min)_
**Context:** PRD-08 §9.1 specifies in-portal notifications stored per-partner-user: types LOW_BALANCE (High severity), STATEMENT_READY (Info), SETTLEMENT_CONFIRMED (Info), WEBHOOK_FAILURE (Warning). Table portal_notifications: id UUID PK, portal_user_id UUID FK portal_users(id) NOT NULL, partner_id UUID FK partners(id) NOT NULL (denormalised for fast partner-scoped queries), notification_type VARCHAR(64) NOT NULL, severity VARCHAR(16) NOT NULL CHECK (severity IN ('HIGH','WARNING','INFO')), title VARCHAR(255) NOT NULL, body TEXT NOT NULL, is_read BOOLEAN NOT NULL DEFAULT FALSE, read_at TIMESTAMPTZ NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(). Notifications are per-user: dismissing clears only that user's record (other users on same partner retain their own copy).
**Steps:** Create migration file V11_1_003__portal_notification.sql; Define table portal_notifications with all columns; Add index idx_portal_notifications_user_unread on (portal_user_id, is_read) WHERE is_read = FALSE for fast unread-count queries; Add index idx_portal_notifications_partner on (partner_id, created_at DESC); Run migration and verify
**Deliverable:** Migration file V11_1_003__portal_notification.sql applied cleanly
**Acceptance / logic checks:**
- notification_type accepts any non-null varchar; application-layer enum validated in T14
- severity column rejects values outside HIGH / WARNING / INFO
- is_read defaults to FALSE; read_at is NULL for unread rows
- Partial index idx_portal_notifications_user_unread covers only unread rows
- SELECT COUNT(*) WHERE portal_user_id = X AND is_read = FALSE uses the partial index (confirmed via EXPLAIN)
**Depends on:** 11.1-T01

### 11.1-T04 — PortalUserRepository: CRUD with mandatory partner_id filter  _(35 min)_
**Context:** Data isolation is the most critical security property (PRD-08 §2.2, SEC-09 §3.5). Every query on portal_users must include WHERE partner_id = :authenticated_partner_id at the repository layer, not only in business logic. Class PortalUserRepository (Spring Data JPA or equivalent). Methods: findByIdAndPartnerId(UUID id, UUID partnerId): Optional<PortalUser>; findByEmailAndPartnerId(String email, UUID partnerId): Optional<PortalUser>; findAllByPartnerId(UUID partnerId, Pageable): Page<PortalUser>; save(PortalUser): PortalUser; the repository must NOT expose findById(UUID) without a partner_id parameter (to prevent accidental cross-partner lookup).
**Steps:** Create entity class PortalUser mapping to portal_users table; Create PortalUserRepository extending JpaRepository; Define only the partner-scoped query methods listed in context; Annotate findById (inherited) as @Deprecated with javadoc 'Use findByIdAndPartnerId instead' to warn callers; Write a unit test PortalUserRepositoryTest that inserts two users from different partners and asserts findByIdAndPartnerId(user_b_id, partner_a_id) returns empty
**Deliverable:** PortalUserRepository.java with all partner-scoped query methods; PortalUserRepositoryTest passing
**Acceptance / logic checks:**
- findByIdAndPartnerId(userB.id, partnerA.id) returns Optional.empty() even when userB exists in DB
- findAllByPartnerId(partnerA.id) returns only users belonging to partnerA
- Inherited findById is deprecated with clear javadoc warning
- Test class asserts cross-partner query returns empty (not an error/exception)
- save() persists a new PortalUser with all required non-null fields set
**Depends on:** 11.1-T01

### 11.1-T05 — PortalUserService: provision first Partner Admin via Admin System call  _(45 min)_
**Context:** GME Ops provisions the first Partner Admin account during partner onboarding (PRD-08 §2.3 assumption A-02). This is an internal Admin System action, not self-registration. PortalUserService.createPortalUser(CreatePortalUserCommand cmd) where cmd contains: partner_id, email, plain_password, role, created_by_operator_id. Rules: password must be >= 12 chars with complexity (uppercase + lowercase + digit + special char); hash with BCrypt cost 12; generate TOTP secret (Base32), derive totp_secret_hash; generate 8 recovery codes (alphanumeric, 10 chars each), store each hashed (SHA-256); only one PARTNER_ADMIN per partner may be created via this method for the initial account (subsequent admin invitations use T06). Return PortalUserCreatedResult with plain TOTP secret and plain recovery codes (shown once; caller displays and then discards).
**Steps:** Implement CreatePortalUserCommand record with fields partner_id, email, plain_password, role, created_by_operator_id; Implement validation: password length >= 12 and matches regex (?=.*[A-Z])(?=.*[a-z])(?=.*\d)(?=.*\W); throw PasswordPolicyException if fails; BCrypt hash password with cost 12; generate TOTP secret via library (e.g. GoogleAuthenticator or dev.samstevens.totp); hash secret for storage; Generate 8 recovery codes, hash each individually, store array; Persist via PortalUserRepository.save(); Return PortalUserCreatedResult containing plain_totp_secret and plain_recovery_codes (NOT stored)
**Deliverable:** PortalUserService.createPortalUser() method; PortalUserServiceTest with password-policy and TOTP-setup cases
**Acceptance / logic checks:**
- Password 'password' (no complexity) throws PasswordPolicyException
- Password 'Tr0ub4dor&3xP!' (12 chars, complexity OK) persists successfully
- Returned plain_totp_secret is Base32 and produces valid 6-digit OTPs when seeded into an authenticator
- Stored totp_secret_hash differs from plain_totp_secret (it is a hash)
- 8 recovery codes returned; each stored as a distinct SHA-256 hash in totp_recovery_codes array
**Depends on:** 11.1-T04

### 11.1-T06 — PortalUserService: Partner Admin invites Viewer (partner-scoped user management)  _(40 min)_
**Context:** A Partner Admin can invite additional portal users within the same partner (PRD-08 §2.3). A Partner Viewer cannot manage users (AC-14). Method PortalUserService.invitePortalUser(UUID actorUserId, UUID actorPartnerId, InvitePortalUserCommand cmd). cmd contains: email, role. Rules: actorUserId must belong to actorPartnerId and have role PARTNER_ADMIN (throw InsufficientRoleException if Viewer); invited user's partner_id = actorPartnerId (never from cmd); invited email must not already exist in portal_users (globally unique per T01 schema constraint); new user is created with is_active=TRUE and a temporary password sent via email (plain password returned to caller for email dispatch; stored hashed). Partner Admin cannot create another PARTNER_ADMIN via invite (only GME Ops can via T05); role must be PARTNER_VIEWER.
**Steps:** Add invitePortalUser method to PortalUserService; Validate actor role: load actor via PortalUserRepository.findByIdAndPartnerId(actorUserId, actorPartnerId); throw InsufficientRoleException if role != PARTNER_ADMIN; Reject cmd.role != PARTNER_VIEWER with InvalidRoleException (only Viewers created via invite); Check email uniqueness; throw EmailAlreadyExistsException if duplicate; Generate temporary password (16 chars random alphanumeric), hash it, create user record; Return InviteResult with plain temporary password for email dispatch
**Deliverable:** PortalUserService.invitePortalUser() method; unit tests for role guard, email-uniqueness guard, and successful invite
**Acceptance / logic checks:**
- Viewer actor throws InsufficientRoleException
- Actor from different partner (wrong actorPartnerId) gets empty actor lookup -> throws EntityNotFoundException
- Inviting with role=PARTNER_ADMIN throws InvalidRoleException
- Duplicate email throws EmailAlreadyExistsException (or propagates DB unique violation)
- Successful invite sets invited user's partner_id = actorPartnerId (not a value from cmd)
**Depends on:** 11.1-T05

### 11.1-T07 — PortalAuthService: login with username+password+TOTP validation  _(55 min)_
**Context:** SEC-09 §3.2: login requires email + password + TOTP code. Account lockout: 5 consecutive failures -> 15-minute lockout (locked_until = now() + 15 min). Password checked via BCrypt; TOTP checked against TOTP secret (30-second window, allow 1 step tolerance = codes T-1, T, T+1). If TOTP fails but password succeeded, still increment failed_login_count (TOTP failure is an auth failure). Recovery code path: if totp_code matches one of the hashed recovery codes, accept login and nullify that recovery code entry from the array. On success: reset failed_login_count to 0 and locked_until to NULL; update last_login_at. Method: PortalAuthService.login(LoginRequest req) -> LoginResult(accessToken, refreshToken, expiresAt).
**Steps:** Implement PortalAuthService.login(LoginRequest) accepting email, password, totp_code; Load user by email; if not found return generic AuthFailedException (do not reveal whether email exists); Check locked_until: if non-null and in the future, throw AccountLockedException with unlock time; Verify BCrypt password; on mismatch increment failed_login_count; if count reaches 5 set locked_until = now() + 15 min; throw AuthFailedException; Verify TOTP code against stored (un-hashed) secret using TOTP library with window=1; on mismatch apply same increment/lockout logic; throw AuthFailedException; On success: reset failed_login_count=0, locked_until=NULL, last_login_at=now(); issue JWT access token (15 min TTL) and refresh token (store hash in portal_sessions); return LoginResult
**Deliverable:** PortalAuthService.login() method; unit tests covering wrong-password lockout, TOTP failure, recovery-code login, successful login
**Acceptance / logic checks:**
- After 5 wrong passwords, locked_until is set approximately 15 min in the future and subsequent attempts throw AccountLockedException
- Wrong TOTP (correct password) also increments failed_login_count
- Valid TOTP code from T-1 window (previous 30-sec slot) is accepted (window tolerance)
- Using a recovery code succeeds and that code is removed from the stored array (cannot be reused)
- Successful login resets failed_login_count to 0 and updates last_login_at
**Depends on:** 11.1-T05

### 11.1-T08 — PortalAuthService: JWT issuance, idle-timeout enforcement, and session revocation  _(50 min)_
**Context:** PRD-08 §2.1: session idle timeout = 15 min (SEC-09 §3.2 for Partner Portal); absolute session limit = 24 h. JWT access token: short-lived 15 min; claims must include sub (portal_user_id), partner_id, role, exp, iat, jti (unique per token). Refresh token: opaque UUID stored as SHA-256 hash in portal_sessions.refresh_token_hash; used to issue a new access token and update last_active_at. Idle check: if now() - last_active_at > 15 min OR now() > expires_at, session is expired (return 401 SESSION_EXPIRED). Logout: set revoked_at = now(), revocation_reason = 'LOGOUT'. Partner_id in JWT claims is authoritative -- never read partner_id from request body or URL path parameter.
**Steps:** Implement issueTokenPair(PortalUser user) -> TokenPair(accessToken JWT, refreshToken UUID); JWT claims: sub=user.id.toString(), partner_id=user.partnerId.toString(), role=user.role, exp=now+15min, iat=now, jti=random UUID; Store SHA-256(refreshToken) in portal_sessions with expires_at=now+24h, last_active_at=now; Implement refreshSession(String refreshToken): lookup by SHA-256 hash, check revoked_at IS NULL, check now < expires_at, check now - last_active_at <= 15 min; if all OK update last_active_at and issue new access token; Implement logout(String refreshToken): set revoked_at=now, revocation_reason=LOGOUT; Write unit tests for idle-timeout rejection, absolute-expiry rejection, revoked-session rejection, and successful refresh
**Deliverable:** PortalAuthService token-management methods; PortalAuthServiceTest covering timeout and revocation scenarios
**Acceptance / logic checks:**
- JWT access token contains partner_id claim matching the user's partner_id
- refreshSession() called with last_active_at = now() - 16 min returns 401 SESSION_EXPIRED (idle timeout breach)
- refreshSession() called at now() = issued_at + 25h returns 401 SESSION_EXPIRED (absolute limit breach)
- logout() sets revoked_at; subsequent refreshSession() call returns 401 SESSION_REVOKED
- Decoded JWT jti is unique across two consecutive issueTokenPair() calls
**Depends on:** 11.1-T07, 11.1-T02

### 11.1-T09 — PortalSecurityFilter: extract partner_id from JWT claims and bind to request context  _(45 min)_
**Context:** SEC-09 §3.5 and PRD-08 §2.2: partner_id must come from the authenticated JWT claims -- never from URL path params or request body. Implement a servlet filter (or Spring Security filter) PortalJwtAuthFilter that: (1) extracts Bearer token from Authorization header, (2) validates signature and exp, (3) extracts partner_id and portal_user_id and role claims, (4) populates a PortalSecurityContext (thread-local or Spring SecurityContextHolder) with PortalPrincipal{partnerId, userId, role}. If token is absent or invalid, return HTTP 401. If token is valid but role check fails for the endpoint, return HTTP 403. The filter must run before any controller handler. partner_id from path variables (e.g. /{partner_slug}/...) must be validated against JWT partner_id in a separate guard (see T10).
**Steps:** Implement PortalPrincipal record: UUID partnerId, UUID userId, PortalRole role; Implement PortalJwtAuthFilter extending OncePerRequestFilter; Extract Bearer token; validate via JWT library using server secret; on failure return 401 with body {error: INVALID_TOKEN}; Populate SecurityContextHolder with UsernamePasswordAuthenticationToken carrying PortalPrincipal; Skip filter for public endpoints: POST /portal/auth/login, POST /portal/auth/refresh; Write unit tests using MockMvc: request with valid token populates principal; expired token returns 401; missing token returns 401
**Deliverable:** PortalJwtAuthFilter.java; PortalJwtAuthFilterTest covering valid, expired, and missing token cases
**Acceptance / logic checks:**
- Request with valid JWT sets SecurityContextHolder principal with correct partner_id UUID
- Request with expired JWT (exp in past) returns HTTP 401 with error=INVALID_TOKEN
- Request with tampered JWT signature returns HTTP 401
- POST /portal/auth/login proceeds without Authorization header (public endpoint)
- PortalPrincipal.partnerId equals the partner_id claim in the JWT -- not a value from the request URL
**Depends on:** 11.1-T08

### 11.1-T10 — PortalOwnershipGuard: validate URL path partner_slug against JWT partner_id  _(35 min)_
**Context:** Portal URLs are partner-scoped: /{partner_slug}/transactions/{hub_txn_ref} etc. (PRD-08 §5). The partner_slug in the URL is cosmetic but must still be validated against the authenticated JWT partner_id to prevent IDOR where a user replaces the slug with another partner's slug. PortalOwnershipGuard is a Spring HandlerInterceptor (or AOP aspect) that: (1) resolves partner_slug from the path variable to a partner UUID by querying the partner table, (2) asserts partner UUID == PortalPrincipal.partnerId from JWT, (3) if mismatch returns HTTP 403 FORBIDDEN (not 404 -- the slug mismatch is an auth violation, not a not-found). This guard applies to all portal endpoints that include {partner_slug} in the path. Note: transaction-detail cross-partner access returns 404, not 403 (PRD-08 §5 -- do not reveal existence); the guard returns 403 only for slug mismatch at routing level.
**Steps:** Implement PortalOwnershipGuard implementing HandlerInterceptor; In preHandle: extract partner_slug path variable; resolve to partner UUID via PartnerRepository.findBySlug(slug); if not found return 404; Compare resolved UUID with PortalPrincipal.partnerId; if mismatch return 403 with body {error: PARTNER_MISMATCH}; Register guard for all paths matching /portal/{partner_slug}/**; Write unit test: user from partnerA accessing /portal/partnerB-slug/ returns 403; user accessing own slug proceeds
**Deliverable:** PortalOwnershipGuard.java; PortalOwnershipGuardTest covering own-slug pass and cross-partner-slug rejection
**Acceptance / logic checks:**
- GET /portal/partner-b-slug/transactions with JWT for partner-A returns HTTP 403 PARTNER_MISMATCH
- GET /portal/partner-a-slug/transactions with JWT for partner-A proceeds to controller
- GET /portal/unknown-slug/transactions returns HTTP 404 (slug not found)
- Guard does not query partner_id from query params or request body
- Mismatch result is HTTP 403, not HTTP 401 (token is valid but ownership violated)
**Depends on:** 11.1-T09

### 11.1-T11 — PortalRoleGuard: annotation-driven RBAC enforcement for PARTNER_ADMIN-only endpoints  _(35 min)_
**Context:** PRD-08 §2.3 and SEC-09 §3.4.2: two roles exist in the portal -- PARTNER_ADMIN (full read + user management) and PARTNER_VIEWER (read only, no user management). User-management endpoints (invite user, deactivate user, list users) are ADMIN-only. Implement a custom annotation @RequiresPortalRole(PortalRole.PARTNER_ADMIN) and a Spring AOP aspect or HandlerMethodArgumentResolver that reads PortalPrincipal.role from the security context and throws InsufficientRoleException (-> HTTP 403) if the role requirement is not met. The annotation should be applicable to controller methods. Read-only endpoints (GET transactions, GET balance etc.) require any authenticated portal user (no role annotation needed -- filter T09 is sufficient).
**Steps:** Define @RequiresPortalRole annotation with PortalRole[] value(); Implement PortalRoleAspect: @Around advice on methods annotated with @RequiresPortalRole; read PortalPrincipal from SecurityContext; if role not in value() throw InsufficientRoleException; Map InsufficientRoleException to HTTP 403 in global exception handler with body {error: INSUFFICIENT_ROLE, required: PARTNER_ADMIN}; Annotate PortalUserManagementController methods (inviteUser, deactivateUser, listUsers) with @RequiresPortalRole(PARTNER_ADMIN); Write unit test: Viewer calling inviteUser() returns 403; Admin calling inviteUser() proceeds
**Deliverable:** @RequiresPortalRole annotation + PortalRoleAspect; PortalRoleAspectTest with Viewer and Admin scenarios
**Acceptance / logic checks:**
- PARTNER_VIEWER calling POST /portal/{slug}/users/invite returns HTTP 403 INSUFFICIENT_ROLE
- PARTNER_ADMIN calling POST /portal/{slug}/users/invite is not blocked by the aspect
- Aspect reads role from SecurityContextHolder (PortalPrincipal), not from request body
- GET /portal/{slug}/transactions (no @RequiresPortalRole annotation) allows both ADMIN and VIEWER
- InsufficientRoleException mapped to HTTP 403 -- not 401 or 500
**Depends on:** 11.1-T09

### 11.1-T12 — POST /portal/auth/login endpoint: wire LoginRequest to PortalAuthService  _(40 min)_
**Context:** Public REST endpoint for portal login. POST /portal/auth/login accepts JSON {email, password, totp_code}. Calls PortalAuthService.login() (T07). Returns 200 with {access_token, refresh_token, expires_in_seconds: 900, partner_id, role} on success. Error mappings: AuthFailedException -> 401 {error: AUTH_FAILED} (do not distinguish wrong password vs wrong TOTP to prevent oracle attacks); AccountLockedException -> 423 {error: ACCOUNT_LOCKED, unlock_at: ISO-8601}; missing/invalid body fields -> 400. Rate-limiting: login endpoint subject to 20 failed attempts per IP per 10 min (SEC-09 §5.4) -- configure at API Gateway or via a filter; this ticket implements the endpoint and service wiring only.
**Steps:** Create PortalAuthController with POST /portal/auth/login mapped method; Define LoginRequest DTO: email (not blank), password (not blank), totp_code (not blank, 6 digits or recovery format); Call PortalAuthService.login(); map exceptions to HTTP responses as specified; Return LoginResponse DTO: access_token, refresh_token, expires_in_seconds=900, partner_id (UUID string), role; Write integration test (MockMvc): valid credentials return 200 with access_token; wrong password returns 401 with error=AUTH_FAILED; locked account returns 423 with unlock_at
**Deliverable:** PortalAuthController.login() endpoint; PortalAuthControllerTest with success, wrong-credential, and lockout scenarios
**Acceptance / logic checks:**
- POST /portal/auth/login with valid email+password+TOTP returns 200 and non-null access_token and refresh_token
- Wrong password returns 401 {error: AUTH_FAILED} -- body does not reveal whether email was found
- Wrong TOTP (correct password) also returns 401 {error: AUTH_FAILED} -- same response shape
- Locked account returns 423 with unlock_at field set to the locked_until value
- Missing totp_code field returns 400 validation error
**Depends on:** 11.1-T07, 11.1-T08

### 11.1-T13 — POST /portal/auth/refresh and POST /portal/auth/logout endpoints  _(35 min)_
**Context:** POST /portal/auth/refresh: accepts {refresh_token} (opaque UUID string), calls PortalAuthService.refreshSession(). Returns 200 {access_token, expires_in_seconds:900} on success; 401 SESSION_EXPIRED if idle or absolute timeout exceeded; 401 SESSION_REVOKED if logout was called. POST /portal/auth/logout: requires valid Bearer access token (goes through PortalJwtAuthFilter); accepts {refresh_token} in body; calls PortalAuthService.logout(). Returns 204 No Content. Both endpoints are unauthenticated (for refresh) or authenticated (for logout) as noted.
**Steps:** Add refreshSession endpoint: POST /portal/auth/refresh, no auth filter required; accept RefreshRequest {refresh_token}; call PortalAuthService.refreshSession(); return RefreshResponse {access_token, expires_in_seconds}; Map SessionExpiredException -> 401 {error: SESSION_EXPIRED}; SessionRevokedException -> 401 {error: SESSION_REVOKED}; Add logout endpoint: POST /portal/auth/logout, requires Bearer token (PortalJwtAuthFilter); accept LogoutRequest {refresh_token}; call PortalAuthService.logout(); return 204; Write integration tests: successful refresh returns new access_token; calling refresh after logout returns 401 SESSION_REVOKED; refresh after 16-min idle returns 401 SESSION_EXPIRED
**Deliverable:** PortalAuthController.refresh() and logout() endpoints; integration tests covering revocation and expiry paths
**Acceptance / logic checks:**
- POST /portal/auth/refresh with valid non-expired token returns 200 with new access_token
- POST /portal/auth/logout returns 204; subsequent POST /portal/auth/refresh with same refresh_token returns 401 SESSION_REVOKED
- POST /portal/auth/refresh with refresh_token whose last_active_at is 16 min ago returns 401 SESSION_EXPIRED
- POST /portal/auth/logout without valid Bearer token returns 401 (filter rejects)
- Refresh response does not return a new refresh_token -- only a new short-lived access_token
**Depends on:** 11.1-T08, 11.1-T12

### 11.1-T14 — GET /portal/{partner_slug}/dashboard/summary: partner-scoped dashboard aggregate  _(55 min)_
**Context:** PRD-08 §3 Dashboard. Returns: (a) balance panel (OVERSEAS partners only: current_balance USD, low_balance_threshold, last_topup_date, reserved_pending USD), (b) today-activity (txn count, payout_volume in KRW, collection_volume in partner settle-A ccy, success_rate), (c) recent-10-transactions list (txn_timestamp KST, hub_txn_ref truncated to 12 chars, merchant_name, target_payout, payout_ccy, collection_amount, collection_ccy, txn_status). partner_id sourced exclusively from JWT claims (PortalPrincipal). LOCAL partners (type=LOCAL) get null balance panel. Today = current calendar day in KST (UTC+09:00 constant offset, Korea has no DST). Success rate = approved / (approved + declined) * 100 for today; division by zero -> null.
**Steps:** Implement DashboardService.getDashboardSummary(UUID partnerId): load partner type; conditionally build balance panel; query today-activity aggregates; fetch 10 most recent transactions; For today-activity: WHERE partner_id = :partnerId AND txn_timestamp >= today_kst_start AND txn_timestamp < today_kst_start + 1 day (KST = UTC+9, use AT TIME ZONE or offset arithmetic); For balance panel: only if partner.type = OVERSEAS; fetch prefunding_balance record for partner; low_balance_threshold from partner config; Truncate hub_txn_ref to first 12 chars in the response; Map to DashboardSummaryResponse DTO; wire to GET /portal/{partner_slug}/dashboard/summary controller method
**Deliverable:** DashboardService.getDashboardSummary() + controller endpoint; unit test with OVERSEAS and LOCAL partner scenarios
**Acceptance / logic checks:**
- LOCAL partner response has balancePanel = null
- OVERSEAS partner with balance USD 8000 and threshold USD 10000 has balancePanel.lowBalanceIndicator = true
- Today-activity count uses KST date boundary: transaction at 2026-06-05T00:30+09:00 (= 2026-06-04T15:30Z) is counted in June 5 KST day
- hub_txn_ref in recent-transactions list is exactly 12 characters (truncated)
- success_rate = null (not NaN or error) when approved + declined = 0 today
**Depends on:** 11.1-T09, 11.1-T10

### 11.1-T15 — GET /portal/{partner_slug}/transactions: partner-scoped paginated transaction list  _(55 min)_
**Context:** PRD-08 §4. Filters: date range (default last 7 days; max 90-day range per query), scheme (dropdown of partner's mapped schemes), direction (Domestic/Inbound/Outbound/Hub), status (multi-select: Approved/Declined/Pending/Cancelled/Refunded), amount_min/amount_max (in partner settle-A ccy), payment_mode (MPM/CPM). Pagination: default 50 rows; sizes 25/50/100; returns page, pageSize, totalCount, totalPages. All queries enforce WHERE partner_id = :authenticated_partner_id (from JWT, never from request params). Columns: txn_timestamp (KST), hub_txn_ref, scheme_name, direction, target_payout, payout_ccy, collection_amount, collection_ccy, txn_status, payment_mode.
**Steps:** Implement TransactionQueryService.listTransactions(UUID partnerId, TransactionFilter filter, Pageable pageable); Apply partner_id filter first; then apply optional filters in any combination; ensure compound index on (partner_id, txn_timestamp DESC) is used; Validate date range: max span = 90 days; throw DateRangeExceededException -> 400 if exceeded; Wire to GET /portal/{partner_slug}/transactions with query params: from_date, to_date, scheme, direction, status (repeatable), amount_min, amount_max, mode, page (0-indexed), page_size (25/50/100 default 50); Return PagedTransactionResponse: items list + pagination metadata
**Deliverable:** TransactionQueryService.listTransactions() + controller GET endpoint; unit tests for filter combinations and range validation
**Acceptance / logic checks:**
- Query with no optional filters returns up to 50 transactions all belonging to authenticated partner (none from other partners)
- Date range span of 91 days returns HTTP 400 with error=DATE_RANGE_EXCEEDED
- status=Approved&status=Declined (multi-value) returns transactions in either status
- amount_min=100 amount_max=50 (inverted range) returns HTTP 400 with validation error
- Total count in response matches actual DB count matching the filter for that partner
**Depends on:** 11.1-T09, 11.1-T10

### 11.1-T16 — GET /portal/{partner_slug}/transactions/{hub_txn_ref}: partner-scoped transaction detail  _(50 min)_
**Context:** PRD-08 §5. Returns full transaction detail including: target_payout, payout_ccy, collection_amount, collection_ccy, service_charge, send_amount, offer_rate, cross_rate. For cross-border (direction != Domestic): also collection_usd, payout_usd_cost, rate_locked_at. For Domestic (same-currency): USD pool fields NOT included in response. Field visibility rule from PRD-08 §5.1: m_a, m_b, cost_rate_coll, cost_rate_pay, collection_margin_usd, payout_margin_usd are NEVER returned (internal only). Status+event trail: simplified 4-step view {Initiated, Scheme Processing, Approved/Declined, Notified} with timestamps derived from the 8-step internal trail. Scheme reference: scheme_txn_ref, scheme_name, merchant_id, merchant_name, payment_mode. CRITICAL: if transaction belongs to a different partner, return HTTP 404 (not 403) -- do not reveal existence (PRD-08 §5).
**Steps:** Implement TransactionDetailService.getDetail(UUID partnerId, String hubTxnRef): query WHERE hub_txn_ref = :ref AND partner_id = :partnerId; return Optional; Map Optional.empty() to HTTP 404 (applies whether txn does not exist OR belongs to another partner); Build response DTO excluding m_a, m_b, cost_rate_coll, cost_rate_pay, collection_margin_usd, payout_margin_usd; Conditionally include USD pool fields: if direction in (Inbound, Outbound, Hub) include collection_usd, payout_usd_cost, rate_locked_at; if Domestic omit; Map 8-step internal trail to 4-step partner view: step1=Initiated(step2 timestamp), step2=Scheme Processing(step4), step3=Approved/Declined(step5), step4=Notified(step7 or step8 whichever available); Wire to GET /portal/{partner_slug}/transactions/{hub_txn_ref}
**Deliverable:** TransactionDetailService + controller endpoint; unit tests for cross-partner 404, domestic-no-USD-fields, cross-border-has-USD-fields
**Acceptance / logic checks:**
- Requesting a hub_txn_ref that belongs to another partner returns HTTP 404 (not 403)
- Requesting a non-existent hub_txn_ref returns HTTP 404 (same response as cross-partner)
- Response for cross-border transaction contains collection_usd and payout_usd_cost fields
- Response for Domestic transaction does NOT contain collection_usd or payout_usd_cost fields
- Response for any transaction never contains m_a, m_b, cost_rate_coll, cost_rate_pay, collection_margin_usd, or payout_margin_usd fields
**Depends on:** 11.1-T15

### 11.1-T17 — GET /portal/{partner_slug}/transactions/export: CSV export with 10k-row cap and field restrictions  _(50 min)_
**Context:** PRD-08 §4.4. Export all rows matching current filter (same params as list endpoint T15) up to 10,000 rows. Columns: txn_timestamp, hub_txn_ref (FULL, not truncated), scheme_txn_ref, scheme_name, direction, target_payout, payout_ccy, collection_amount, collection_ccy, service_charge, offer_rate, merchant_id, merchant_name, payment_mode, txn_status. NEVER include m_a, m_b, cost_rate_coll, cost_rate_pay, collection_margin_usd, payout_margin_usd (PRD-08 §4.4 data restriction). Filename format: gmepay_txns_{partner_id}_{from_date}_{to_date}.csv (e.g. gmepay_txns_abc123_2026-05-01_2026-05-31.csv). Max 10,000 rows per request; return HTTP 400 with error=EXPORT_LIMIT_EXCEEDED and instruction to use sub-ranges if query would return > 10,000 rows.
**Steps:** Implement TransactionExportService.exportCsv(UUID partnerId, TransactionFilter filter): count matching rows first; if > 10000 throw ExportLimitExceededException; Fetch up to 10000 rows applying same partner_id filter as T15; Build CSV using streaming writer (do not buffer all rows in heap); columns as specified; timestamp in ISO-8601 UTC; Set Content-Type: text/csv; Content-Disposition: attachment; filename=gmepay_txns_{partner_id}_{from}_{to}.csv; Assert excluded fields are absent by inspecting CSV header row in test; wire to GET /portal/{partner_slug}/transactions/export with same query params as list endpoint
**Deliverable:** TransactionExportService + controller endpoint; CsvExportTest asserting column list and exclusion of internal fields
**Acceptance / logic checks:**
- CSV header row contains hub_txn_ref (full) and does not contain columns m_a, m_b, cost_rate_coll, cost_rate_pay, collection_margin_usd, payout_margin_usd
- Filter returning 10001 matching rows returns HTTP 400 error=EXPORT_LIMIT_EXCEEDED before any CSV bytes are written
- Filter returning 5000 rows returns 5000 data rows plus 1 header row (total 5001 lines)
- Filename header matches pattern gmepay_txns_{partner_id}_{from_date}_{to_date}.csv
- hub_txn_ref column contains full UUID (not truncated 12-char version used in list view)
**Depends on:** 11.1-T15

### 11.1-T18 — GET /portal/{partner_slug}/balance: real-time prefunding balance (OVERSEAS only)  _(35 min)_
**Context:** PRD-08 §6.1. OVERSEAS partners only: current_balance (USD, 2 decimal places), low_balance_threshold (USD), status_indicator (GREEN if balance > threshold, RED if balance <= threshold), last_updated timestamp. LOCAL partners: return HTTP 404 (balance section does not exist for them -- per PRD-08 §6 'this section is displayed only for OVERSEAS partners'). Balance is the real-time available balance; atomic deductions are immediately reflected. Query: SELECT current_balance FROM partner_prefunding WHERE partner_id = :partnerId. Threshold from partner config table field low_balance_threshold_usd (default 10000.00).
**Steps:** Implement BalanceService.getBalance(UUID partnerId): load partner; if partner.type != OVERSEAS return BalanceResult.notApplicable(); Query partner_prefunding for current balance; load low_balance_threshold_usd from partner_config; Compute status: GREEN if current_balance > threshold, RED if current_balance <= threshold; Map notApplicable to HTTP 404 in controller; Return BalanceResponse {available_balance_usd, low_balance_threshold_usd, status_indicator, last_updated} for OVERSEAS partners; Wire to GET /portal/{partner_slug}/balance; protect with PortalJwtAuthFilter
**Deliverable:** BalanceService.getBalance() + controller endpoint; unit tests for OVERSEAS success, LOCAL 404, and threshold boundary
**Acceptance / logic checks:**
- LOCAL partner GET /portal/{slug}/balance returns HTTP 404
- OVERSEAS partner with balance=10000.00 and threshold=10000.00 returns status_indicator=RED (at-threshold is RED, not GREEN)
- OVERSEAS partner with balance=10000.01 and threshold=10000.00 returns status_indicator=GREEN
- Balance value returned as USD with 2 decimal places (e.g. 10000.00 not 10000 or 10000.0)
- Endpoint rejects request without valid JWT (401 from PortalJwtAuthFilter)
**Depends on:** 11.1-T09, 11.1-T10

### 11.1-T19 — GET /portal/{partner_slug}/balance/ledger: prefunding movement history with running balance  _(40 min)_
**Context:** PRD-08 §6.2. Paginated ledger of all prefunding balance movements for OVERSEAS partners. Columns: movement_timestamp (KST), type (DEBIT/CREDIT/ADJUSTMENT), reference (hub_txn_ref for debits, top-up ref for credits), amount_usd (negative for debits, positive for credits), running_balance_usd. Default view: last 30 days; filterable by date range max 1 year. Running balance is stored in the prefunding_ledger table (not computed on-the-fly) to ensure consistency. LOCAL partners return HTTP 404. Pagination: default 50 rows, sizes 25/50/100. The sum of all amount_usd entries must equal the current_balance in partner_prefunding (invariant; validated in T21 unit test).
**Steps:** Implement LedgerService.getLedger(UUID partnerId, DateRange range, Pageable pageable): validate partner.type=OVERSEAS; validate date range <= 1 year; Query prefunding_ledger WHERE partner_id = :partnerId AND movement_timestamp BETWEEN :from AND :to ORDER BY movement_timestamp DESC; Map to LedgerEntryResponse list with fields: movement_timestamp (KST), type, reference, amount_usd, running_balance_usd; Wire to GET /portal/{partner_slug}/balance/ledger with query params from_date, to_date, page, page_size; LOCAL partner or non-OVERSEAS partner -> HTTP 404
**Deliverable:** LedgerService.getLedger() + controller endpoint; unit tests for OVERSEAS paginated result and LOCAL 404
**Acceptance / logic checks:**
- LOCAL partner returns HTTP 404
- Date range > 1 year returns HTTP 400 with error=DATE_RANGE_EXCEEDED
- Debit entries have negative amount_usd (e.g. -150.00); credit entries have positive amount_usd
- Results ordered by movement_timestamp descending (most recent first)
- All results in response have partner_id = authenticated partner_id (cross-partner isolation)
**Depends on:** 11.1-T09, 11.1-T10, 11.1-T18

### 11.1-T20 — GET /portal/{partner_slug}/statements: settlement statement list and download links  _(45 min)_
**Context:** PRD-08 §7.1-7.3. List of settlement periods for authenticated partner. Columns: period (e.g. 'May 2026'), settlement_date, total_transactions, total_payout_volume (payout ccy), total_collection (Settle-A ccy), total_service_fees (Settle-A ccy), status (Draft/Confirmed/Disputed), download_pdf_url, download_csv_url. Data restriction: m_a, m_b, GME margin not included. Statements retained 7 years (SEC-09 §6.2). GET /portal/{partner_slug}/statements returns list. GET /portal/{partner_slug}/statements/{statement_id}/download?format=pdf|csv returns the file (or presigned URL to file in object storage). Statement is partner-scoped: statement_id must belong to authenticated partner (return 404 otherwise).
**Steps:** Implement StatementService.listStatements(UUID partnerId): query partner_statements WHERE partner_id = :partnerId ORDER BY period DESC; Build StatementListResponse: items with period, settlement_date, totals, status, statement_id; Implement StatementService.getDownloadUrl(UUID partnerId, UUID statementId, String format): verify statement belongs to partner (404 if not); return presigned URL or stream PDF/CSV; Wire GET /portal/{partner_slug}/statements and GET /portal/{partner_slug}/statements/{statement_id}/download; Return 404 if statement_id belongs to another partner (do not reveal existence)
**Deliverable:** StatementService + controller endpoints for list and download; unit tests for partner ownership 404 and format selection
**Acceptance / logic checks:**
- Listing statements returns only statements belonging to authenticated partner
- Requesting download for another partner's statement_id returns HTTP 404
- download?format=pdf returns a PDF (Content-Type: application/pdf or presigned URL)
- download?format=csv returns a CSV (Content-Type: text/csv or presigned URL)
- CSV download columns do not include m_a, m_b, collection_margin_usd, payout_margin_usd, or GME revenue fields
**Depends on:** 11.1-T09, 11.1-T10

### 11.1-T21 — GET /portal/{partner_slug}/credentials: read-only API credentials view  _(30 min)_
**Context:** PRD-08 §8.1. Read-only view of partner API configuration. Shown fields: api_key_id (public key identifier), api_key_status (Active/Revoked), webhook_url, webhook_last_delivery_status, webhook_last_delivery_at, sandbox_api_key_id, sandbox_base_url, rate_quote_ttl_seconds. NEVER show: api_secret_hash or any value that could reconstruct the API secret (PRD-08 §8.1 -- API Secret = No). Source tables: partner_credential, partner_webhook_config, partner_config. All data is scoped to authenticated partner_id from JWT.
**Steps:** Implement CredentialsViewService.getCredentialsView(UUID partnerId): load partner_credential (active record), partner_webhook_config, partner_config for the authenticated partner; Map to CredentialsViewResponse: include only fields listed in context; exclude api_secret_hash and any secret-bearing column; Wire to GET /portal/{partner_slug}/credentials; Write unit test asserting api_secret_hash is NOT present in the serialised JSON response (use ObjectMapper to inspect all keys)
**Deliverable:** CredentialsViewService + GET /portal/{partner_slug}/credentials endpoint; unit test asserting secret exclusion
**Acceptance / logic checks:**
- Response JSON does not contain key api_secret, api_secret_hash, or any variant of 'secret' (case-insensitive scan of JSON keys)
- api_key_id is the public identifier UUID, not the hashed value
- webhook_url field is present in response
- Response scoped to authenticated partner: partner B cannot read partner A credentials (returns partner B data when JWT = partner B)
- rate_quote_ttl_seconds is present and matches partner_config.rate_quote_ttl_seconds value
**Depends on:** 11.1-T09, 11.1-T10

### 11.1-T22 — PortalUserManagementController: list and deactivate portal users (PARTNER_ADMIN only)  _(45 min)_
**Context:** PRD-08 §2.3: Partner Admin can invite/deactivate other portal users within the same partner. Deactivating a user sets is_active=FALSE and immediately revokes all their active portal sessions (set revoked_at=now() on all their portal_sessions where revoked_at IS NULL). Listing users is admin-only (Partner Viewer sees no user management screen -- AC-14). Endpoints: GET /portal/{partner_slug}/users (list all users for partner); DELETE /portal/{partner_slug}/users/{user_id} (deactivate; 204 on success). Both require @RequiresPortalRole(PARTNER_ADMIN). A user cannot deactivate themselves (self-deactivation not allowed; return 400 SELF_DEACTIVATION_FORBIDDEN).
**Steps:** Add listUsers(UUID partnerId): query portal_users WHERE partner_id = :partnerId; return list of {id, email, role, is_active, last_login_at}; Add deactivateUser(UUID actorUserId, UUID partnerId, UUID targetUserId): verify actor and target belong to same partnerId; reject if actorUserId == targetUserId with SelfDeactivationException; set target is_active=FALSE; revoke all target sessions; Wire GET /portal/{partner_slug}/users with @RequiresPortalRole(PARTNER_ADMIN); Wire DELETE /portal/{partner_slug}/users/{user_id} with @RequiresPortalRole(PARTNER_ADMIN); Write tests: Viewer calling list/deactivate gets 403; Admin deactivating self gets 400; cross-partner deactivation gets 404
**Deliverable:** PortalUserManagementController with list and deactivate; tests for role guard, self-deactivation, and cross-partner isolation
**Acceptance / logic checks:**
- PARTNER_VIEWER calling GET /portal/{slug}/users returns HTTP 403
- PARTNER_ADMIN deactivating own user_id returns HTTP 400 SELF_DEACTIVATION_FORBIDDEN
- PARTNER_ADMIN deactivating a user_id from a different partner returns HTTP 404 (not 403)
- Successful deactivation sets is_active=FALSE and revokes all active sessions for target user
- After deactivation, target user's refresh token returns 401 SESSION_REVOKED on next refresh attempt
**Depends on:** 11.1-T11, 11.1-T06, 11.1-T13

### 11.1-T23 — GET /portal/{partner_slug}/notifications: list unread + mark-as-read endpoint  _(40 min)_
**Context:** PRD-08 §9.1. Notification bell: unread count shown on portal header. Types: LOW_BALANCE (High), STATEMENT_READY (Info), SETTLEMENT_CONFIRMED (Info), WEBHOOK_FAILURE (Warning). Notifications are per-user: marking read affects only the calling user's record. Endpoints: GET /portal/{partner_slug}/notifications returns {unread_count, notifications: [{id, type, severity, title, body, is_read, created_at}]}. POST /portal/{partner_slug}/notifications/{notification_id}/read marks one notification as read (sets is_read=TRUE, read_at=now()). Notification belongs to the calling portal_user_id AND partner_id (both from JWT claims). Attempting to mark another user's notification returns 404.
**Steps:** Implement NotificationService.listNotifications(UUID userId, UUID partnerId): query portal_notifications WHERE portal_user_id = :userId AND partner_id = :partnerId ORDER BY created_at DESC LIMIT 50; count unread separately; Implement NotificationService.markAsRead(UUID userId, UUID partnerId, UUID notificationId): query WHERE id = :notificationId AND portal_user_id = :userId AND partner_id = :partnerId; if empty return 404; set is_read=TRUE, read_at=now(); Wire GET endpoint returning NotificationListResponse {unread_count, notifications}; Wire POST /{notification_id}/read returning 204; Write unit test: marking another user's notification returns 404; marking own notification sets is_read=TRUE
**Deliverable:** NotificationService + GET notifications + POST .../read endpoints; unit tests for user-scoped isolation
**Acceptance / logic checks:**
- GET notifications for user A returns only notifications with portal_user_id = user A (not user B of same partner)
- POST /notifications/{id}/read where notification belongs to different user returns HTTP 404
- POST /notifications/{id}/read on own notification: subsequent GET shows is_read=TRUE for that notification
- unread_count in GET response matches count of is_read=FALSE notifications for that user
- Endpoint requires valid JWT (PortalJwtAuthFilter enforces 401 for unauthenticated)
**Depends on:** 11.1-T09, 11.1-T10, 11.1-T03

### 11.1-T24 — NotificationDispatcher: create in-portal notifications on trigger events  _(35 min)_
**Context:** PRD-08 §9.1. Notifications are created when: (1) LOW_BALANCE -- triggered by prefunding deduction that causes balance < threshold; (2) STATEMENT_READY -- triggered by settlement statement generation job; (3) SETTLEMENT_CONFIRMED -- triggered by GME Ops confirming a settlement period; (4) WEBHOOK_FAILURE -- triggered after 3 consecutive webhook delivery failures. NotificationDispatcher.createForAllPartnerUsers(UUID partnerId, NotificationType type, String title, String body) creates one portal_notification row per active portal_user belonging to that partner. Integration points: prefunding deduction service calls createForAllPartnerUsers on LOW_BALANCE; webhook delivery tracker calls it on 3rd consecutive failure. Notification data must not include internal margin or rate fields.
**Steps:** Implement NotificationDispatcher.createForAllPartnerUsers(UUID partnerId, NotificationType type, String title, String body); Load all portal_users WHERE partner_id = :partnerId AND is_active = TRUE; For each user insert portal_notifications row with portal_user_id, partner_id, type, severity (derived from type enum), title, body, is_read=FALSE; Annotate method @Transactional; Write unit test: calling for partner with 3 active users + 1 deactivated user creates exactly 3 notifications; Write unit test: LOW_BALANCE notification has severity=HIGH; STATEMENT_READY has severity=INFO
**Deliverable:** NotificationDispatcher.createForAllPartnerUsers() + unit tests for active-user filtering and severity mapping
**Acceptance / logic checks:**
- For partner with 3 active + 1 deactivated user, exactly 3 notification rows are inserted
- Notifications are inserted with is_read=FALSE and read_at=NULL
- LOW_BALANCE -> severity=HIGH; WEBHOOK_FAILURE -> severity=WARNING; STATEMENT_READY -> severity=INFO; SETTLEMENT_CONFIRMED -> severity=INFO
- Each notification row has portal_user_id set to one of the active users (not null, not deactivated user)
- Method is transactional: if any insert fails, all inserts roll back (no partial creation)
**Depends on:** 11.1-T03, 11.1-T05

### 11.1-T25 — EmailNotificationService: send email alerts for low-balance and statement events  _(40 min)_
**Context:** PRD-08 §9.2. Email sent to partner's registered alert_email_addresses (one or more, stored in partner_config.alert_emails JSON array, set by GME Ops). Trigger events: (1) balance drops below threshold -> email {current_balance USD, threshold USD, top-up instructions, GME account team contact}; (2) balance reaches zero -> urgent email (all overseas payments suspended); (3) statement_ready -> email {link to portal statement page, period summary}; (4) settlement_confirmed -> email with confirmation totals. Email delivery via configured SMTP/SES. If alert_emails is empty or null for a partner, skip silently and log a warning. Emails must not include m_a, m_b, or GME internal revenue figures.
**Steps:** Implement EmailNotificationService.sendLowBalanceAlert(UUID partnerId, BigDecimal currentBalance, BigDecimal threshold); Load alert_emails from partner_config; if empty log WARN and return; Build email body: current balance (USD 2dp), threshold (USD 2dp), static top-up instructions text, GME account team contact placeholder; If currentBalance.compareTo(BigDecimal.ZERO) == 0, set subject prefix URGENT and note payments suspended; Send via JavaMailSender or AWS SES client; log send attempt and result; Write unit tests using mock mail sender: verifies to-addresses, that balance=0.00 triggers urgent subject, that alert_emails=[] skips silently
**Deliverable:** EmailNotificationService.sendLowBalanceAlert() + unit tests with mock mail sender
**Acceptance / logic checks:**
- Email is sent to all addresses in alert_emails array (e.g. 2 addresses -> 2 to-field recipients OR one email with multiple recipients)
- partner with alert_emails=[] results in zero emails sent and a WARN log entry
- Balance=0.00 email has subject containing 'URGENT' (case-insensitive)
- Balance=5000.00 threshold=10000.00 email body contains '5000.00' and '10000.00'
- Email body does not contain substrings m_a, m_b, cost_rate, margin_usd, or collection_margin
**Depends on:** 11.1-T24

### 11.1-T26 — Integration test: full partner-scoped auth flow and cross-partner isolation  _(55 min)_
**Context:** SEC-09 §3.5 and PRD-08 §2.2. IDOR testing is a mandatory test case (SEC-09 §3.5). This test ticket covers end-to-end auth and isolation using two real database-backed partners (partner_a and partner_b) with one portal user each. Tests run against an embedded/test database. Isolation invariant: partner_a user can never retrieve partner_b transactions, balance, or statements -- and gets HTTP 404 (not 403 or 500) for transaction detail and HTTP 404 for balance (if partner_b is LOCAL).
**Steps:** Set up two partners: partner_a (OVERSEAS, balance USD 5000.00) and partner_b (LOCAL) with one portal user each; Create 3 transactions for partner_a and 2 for partner_b; Log in as partner_a user (POST /portal/auth/login); extract access_token; Assert GET /portal/partner_a_slug/transactions returns 3 rows all with partner_id = partner_a; Assert GET /portal/partner_a_slug/transactions/{partner_b_txn_ref} returns HTTP 404; Assert GET /portal/partner_b_slug/transactions with partner_a JWT returns HTTP 403 PARTNER_MISMATCH (slug guard T10); Assert GET /portal/partner_a_slug/balance returns 200 for OVERSEAS partner_a; GET /portal/partner_b_slug/balance (as partner_b user) returns 404 for LOCAL partner_b
**Deliverable:** PortalIsolationIntegrationTest class with all IDOR and auth-flow scenarios passing
**Acceptance / logic checks:**
- partner_a user GET transactions returns exactly 3 rows, all partner_id = partner_a
- partner_a user accessing partner_b transaction ref returns HTTP 404
- partner_a JWT used against partner_b slug returns HTTP 403 PARTNER_MISMATCH
- partner_b (LOCAL) balance endpoint returns HTTP 404
- partner_a user login with wrong password 5 times results in 423 ACCOUNT_LOCKED on 6th attempt
**Depends on:** 11.1-T12, 11.1-T14, 11.1-T15, 11.1-T16, 11.1-T18, 11.1-T10

### 11.1-T27 — Unit tests: PortalAuthService TOTP, lockout, recovery-code, and session-expiry edge cases  _(50 min)_
**Context:** Logic-bearing edge cases for PortalAuthService (T07, T08) require dedicated unit-test coverage. Test vectors: (a) TOTP window: code for T-1 slot accepted; code for T-2 slot rejected; (b) lockout: 4 failures -> not locked; 5th failure -> locked; locked account returns AccountLockedException before password check; (c) recovery code: used once and removed; reuse of same code fails; (d) idle timeout: last_active_at 14 min ago -> refresh succeeds; 16 min ago -> SESSION_EXPIRED; (e) absolute timeout: issued 23h59m ago -> refresh succeeds; issued 24h1m ago -> SESSION_EXPIRED. All tests use mocked repositories -- no DB required.
**Steps:** Write PortalAuthServiceUnitTest with @ExtendWith(MockitoExtension.class); Test T-1 TOTP slot acceptance and T-2 slot rejection using a fixed TOTP secret and known window; Test lockout progression: 4 failures leave locked_until null; 5th failure sets locked_until approximately now+15min; Test recovery code single-use: first use succeeds and removes code; immediate reuse of same code fails AuthFailedException; Test idle timeout boundary: mock last_active_at 14 min ago returns new token; 16 min ago throws SessionExpiredException; Test absolute timeout boundary: issued_at 23h59m ago succeeds; issued_at 24h1m ago throws SessionExpiredException
**Deliverable:** PortalAuthServiceUnitTest class with all 6+ test cases passing
**Acceptance / logic checks:**
- TOTP T-2 window (60+ seconds old code) throws AuthFailedException
- 5th consecutive failed login sets locked_until = now + 15min (within +-5s tolerance)
- Recovery code that was used in the test is absent from the stored array after login
- refreshSession with last_active_at = now - 14min returns a new access_token JWT
- refreshSession with last_active_at = now - 16min throws SessionExpiredException
- refreshSession with issued_at = now - 24h - 1min throws SessionExpiredException
**Depends on:** 11.1-T07, 11.1-T08

### 11.1-T28 — Unit tests: data-isolation invariants for TransactionQueryService and BalanceService  _(50 min)_
**Context:** Unit tests verifying that repository methods used by TransactionQueryService (T15) and BalanceService (T18) always include partner_id in WHERE clauses. Uses @DataJpaTest with an H2 or embedded Postgres. Test vectors: (a) 10 txns for partner_a and 5 for partner_b -- query with partner_a id returns exactly 10; (b) balance for partner_a = USD 8000; query with partner_b id returns partner_b balance, not 8000; (c) ledger running-balance invariant: sum of all amount_usd entries for a partner equals current_balance in partner_prefunding (use partner_a with 3 credits and 2 debits).
**Steps:** Write TransactionIsolationRepositoryTest with @DataJpaTest; Insert 10 transactions for partner_a and 5 for partner_b; Call transactionRepository.findByPartnerId(partner_a_id) and assert size = 10; Call transactionRepository.findByPartnerId(partner_b_id) and assert size = 5; Write BalanceIsolationTest: insert balances for both partners; assert getBalance(partner_a_id) returns partner_a balance; Write LedgerInvariantTest: seed 3 credits (+500 each) and 2 debits (-100 each) for partner_a; assert sum = 1300; assert current_balance in prefunding table = 1300.00
**Deliverable:** TransactionIsolationRepositoryTest + BalanceIsolationTest + LedgerInvariantTest all passing
**Acceptance / logic checks:**
- transactionRepository.findByPartnerId(partner_a_id) returns 10 rows; not 15 (no partner_b leakage)
- getBalance(partner_a_id) returns 8000.00 USD regardless of partner_b balance value
- Sum of amount_usd for partner_a ledger entries = current_balance in partner_prefunding for partner_a (tolerance 0.00)
- KST date-boundary test: transaction at 2026-06-05T00:00:00+09:00 (= 2026-06-04T15:00Z) counts in KST June 5 today-filter
- All assertions use BigDecimal.compareTo(expected) == 0 (not == or .equals on double)
**Depends on:** 11.1-T15, 11.1-T18, 11.1-T19

### 11.1-T29 — Portal shell: React app scaffold with auth context, router, and protected-route wrapper  _(55 min)_
**Context:** The Partner Portal is a separate frontend SPA from the Admin System (PRD-08 assumption A-06). Tech stack per project convention. The shell provides: (a) PortalAuthContext holding {accessToken, partnerId, role, logout()}; (b) React Router with routes: /login (public), /{partnerSlug}/dashboard, /{partnerSlug}/transactions, /{partnerSlug}/transactions/:ref, /{partnerSlug}/balance, /{partnerSlug}/balance/ledger, /{partnerSlug}/statements, /{partnerSlug}/credentials, /{partnerSlug}/users (admin only); (c) ProtectedRoute component that redirects to /login if no valid access token in context; (d) RoleProtectedRoute (wraps user-management route) that shows 403 page if role != PARTNER_ADMIN.
**Steps:** Scaffold React app (Vite or CRA) with TypeScript; install React Router, axios, react-query or SWR; Implement PortalAuthContext: store access_token + partner_id + role in memory (not localStorage for XSS safety); persist refresh_token in httpOnly cookie or memory; Implement ProtectedRoute: check PortalAuthContext.accessToken; if null redirect to /login; Implement RoleProtectedRoute: additionally check role == PARTNER_ADMIN; if not, render Forbidden403 component; Define all routes as specified; lazy-load each page component; Write ProtectedRoute unit test: renders children when token present; redirects when token absent
**Deliverable:** Portal SPA scaffold with all routes, PortalAuthContext, ProtectedRoute, and RoleProtectedRoute components
**Acceptance / logic checks:**
- Navigating to /{slug}/dashboard without a token redirects to /login
- Navigating to /{slug}/users as PARTNER_VIEWER renders the Forbidden403 component (not redirect)
- PortalAuthContext.logout() clears accessToken; next render triggers redirect to /login
- All routes compile with TypeScript strict mode (no any types on auth context)
- Access token is NOT stored in localStorage (inspectable via document.cookie or memory check in unit test)

### 11.1-T30 — Login page: email + password + TOTP form with lockout feedback  _(50 min)_
**Context:** PRD-08 §2.1. Login form fields: email, password, totp_code (6-digit numeric or recovery code). On submit: POST /portal/auth/login; store returned access_token in PortalAuthContext; store refresh_token per T29 convention; redirect to /{partnerSlug}/dashboard (partnerSlug derived from partner_id by looking up slug mapping on login response or a separate API call). Error states: 401 -> show generic 'Invalid credentials' (do not distinguish password vs TOTP -- oracle prevention); 423 ACCOUNT_LOCKED -> show 'Account locked until {unlock_at formatted in user local time}'; 400 validation -> inline field errors. TOTP input should be auto-focused when password is filled (UX). Loading state on submit button.
**Steps:** Create LoginPage component with controlled form state for email, password, totp_code; On submit call POST /portal/auth/login via axios; disable submit button while loading; On 200 success: store tokens in PortalAuthContext; redirect to /{partnerSlug}/dashboard; On 401: display 'Invalid credentials or one-time code. Please try again.' (no distinction); On 423: display 'Account locked. Try again after {unlock_at local time}'; Write unit tests (React Testing Library): form submission sends correct payload; 401 shows generic error; 423 shows unlock time
**Deliverable:** LoginPage component; LoginPage.test.tsx with API success, 401, and 423 scenarios
**Acceptance / logic checks:**
- Form submits JSON {email, password, totp_code} to POST /portal/auth/login
- 401 response shows generic message not revealing which field was wrong
- 423 response shows unlock time formatted in browser local time (not raw ISO-8601)
- Submit button is disabled while request is in-flight (prevents double-submit)
- After successful login, window location is /{partnerSlug}/dashboard (redirect confirmed in test)
**Depends on:** 11.1-T29, 11.1-T12

### 11.1-T31 — Portal header: notification bell with unread count and session-expiry auto-logout  _(50 min)_
**Context:** PRD-08 §9.1: notification bell in portal header shows unread count. Auto-logout: when an API call returns 401 SESSION_EXPIRED or SESSION_REVOKED, clear PortalAuthContext and redirect to /login (with flash message 'Your session has expired. Please log in again.'). Notification bell: polls GET /portal/{slug}/notifications every 60 seconds; shows red badge with unread_count when > 0. Clicking bell opens notification dropdown (list of 10 most recent; clicking each marks it read). Header also shows partner name, logged-in user email, and a Logout button.
**Steps:** Create PortalHeader component: partner name, user email, logout button, notification bell; Implement useNotifications hook: fetch GET /{slug}/notifications on mount and every 60s via setInterval; expose {unread_count, notifications, markAsRead}; markAsRead(id): POST /{slug}/notifications/{id}/read; optimistically set is_read=TRUE in local state; Implement global axios response interceptor: on 401 with error=SESSION_EXPIRED or SESSION_REVOKED call PortalAuthContext.logout() and navigate to /login with flash; Logout button: calls POST /portal/auth/logout then clears context and redirects to /login; Write unit test: 401 SESSION_EXPIRED on any API call triggers logout and redirect
**Deliverable:** PortalHeader component + useNotifications hook + axios interceptor; test for auto-logout on 401 SESSION_EXPIRED
**Acceptance / logic checks:**
- Notification bell shows red badge with count=3 when API returns unread_count=3
- Clicking a notification calls POST .../read and the item shows as read without full page reload
- Any API call returning 401 {error: SESSION_EXPIRED} clears auth context and redirects to /login
- Logout button calls POST /portal/auth/logout and then navigates to /login
- Poll interval is 60 seconds (confirm via jest fake timers: after 60s, a second fetch is made)
**Depends on:** 11.1-T29, 11.1-T23, 11.1-T13


## WBS 11.2 — Dashboard (balance/volume/alerts)
### 11.2-T01 — Create portal_notification DB migration for in-portal alerts  _(30 min)_
**Context:** PRD-08 §9.1 specifies in-portal notifications for Partner Portal users. Each notification belongs to a partner_portal_user. Fields: id (UUID v4 PK), partner_id (UUID FK -> partner, NOT NULL), user_id (UUID FK -> partner_portal_user, NOT NULL), notification_type (VARCHAR(40): LOW_BALANCE|STATEMENT_READY|SETTLEMENT_CONFIRMED|WEBHOOK_FAILURE), severity (VARCHAR(10): HIGH|INFO|WARNING), title VARCHAR(200), body TEXT, is_read (BOOLEAN DEFAULT false), created_at (TIMESTAMPTZ), read_at (TIMESTAMPTZ nullable), dismissed_for_session (BOOLEAN DEFAULT false). Indexes: (user_id, is_read) for bell-count query; (partner_id, created_at DESC) for listing.
**Steps:** Create Flyway/Liquibase migration V11_2_001: create portal_notification table with all columns listed above; Add index idx_portal_notification_user_unread on (user_id, is_read) for fast unread-count query; Add index idx_portal_notification_partner_created on (partner_id, created_at DESC) for partner-scoped listing; Add CHECK constraint notification_type IN (LOW_BALANCE, STATEMENT_READY, SETTLEMENT_CONFIRMED, WEBHOOK_FAILURE); Verify migration applies and rolls back cleanly on fresh schema
**Deliverable:** Flyway migration V11_2_001 creating portal_notification table with indexes and constraint
**Acceptance / logic checks:**
- Table accepts a row with all required fields; UUID PK auto-generated
- notification_type outside the allowed enum raises a CHECK constraint violation
- Index idx_portal_notification_user_unread exists and is used by EXPLAIN on SELECT ... WHERE user_id=? AND is_read=false
- Inserting a row with a NULL partner_id raises NOT NULL violation
- dismissed_for_session defaults to false when not provided

### 11.2-T02 — Create partner_portal_user DB migration for portal user accounts  _(35 min)_
**Context:** PRD-08 §2.1 and §2.3: each portal user belongs to exactly one partner. partner_portal_user: id (UUID v4 PK), partner_id (UUID FK -> partner NOT NULL), email (VARCHAR(255) UNIQUE NOT NULL), name (VARCHAR(120) NOT NULL), role (VARCHAR(20): PARTNER_ADMIN|PARTNER_VIEWER NOT NULL), is_active (BOOLEAN DEFAULT true NOT NULL), totp_secret (TEXT nullable, stored encrypted), totp_verified (BOOLEAN DEFAULT false), recovery_codes (JSONB nullable, hashed), consecutive_failures (SMALLINT DEFAULT 0), locked_until (TIMESTAMPTZ nullable), last_login_at (TIMESTAMPTZ nullable), created_at, updated_at, created_by (UUID FK -> hub_user nullable). Index on (partner_id, email).
**Steps:** Create migration V11_2_002: create partner_portal_user with all columns above; Add UNIQUE index on email (global, not per-partner — email is login key); Add index idx_ppu_partner_id on (partner_id) to support per-partner user list; Add CHECK role IN (PARTNER_ADMIN, PARTNER_VIEWER); Add partner_portal_credential table: user_id (UUID PK FK), password_hash TEXT NOT NULL, must_change BOOLEAN DEFAULT true, updated_at TIMESTAMPTZ
**Deliverable:** Migrations V11_2_002 creating partner_portal_user and partner_portal_credential tables
**Acceptance / logic checks:**
- Two users with the same email across different partners raise UNIQUE violation (email is globally unique)
- role outside (PARTNER_ADMIN, PARTNER_VIEWER) raises CHECK constraint violation
- partner_portal_credential.user_id is PK; second insert for same user_id raises violation
- is_active defaults to true; null insert raises NOT NULL violation
- CHECK constraint exists on role column and rejects SUPER_ADMIN
**Depends on:** 11.2-T01

### 11.2-T03 — Create portal_session_dismiss DB migration for session-dismissed alerts  _(25 min)_
**Context:** PRD-08 §3.4: the low-balance alert on the Dashboard is dismissible for the current session only. To track session-level dismissals without storing state in the JWT (stateless-session-compatible), record dismissals in a short-lived table. portal_session_dismiss: id (UUID v4 PK), session_id (UUID NOT NULL), notification_type (VARCHAR(40) NOT NULL), partner_id (UUID NOT NULL), dismissed_at (TIMESTAMPTZ DEFAULT now()), expires_at (TIMESTAMPTZ NOT NULL). Index on (session_id, notification_type). A scheduled job purges rows where expires_at < now(); retention max 24 hours matching absolute session limit.
**Steps:** Create migration V11_2_003: create portal_session_dismiss table with all columns above; Add index idx_psd_session_type on (session_id, notification_type) for lookup at dashboard render; Add partial index or note that expires_at is used by purge job (no FK to session; session_id is opaque UUID from JWT); Document in migration comment that rows are purged by a scheduled cleanup job when expires_at < now(); Verify constraint: notification_type CHECK IN (LOW_BALANCE, SETTLEMENT_DUE)
**Deliverable:** Migration V11_2_003 creating portal_session_dismiss table
**Acceptance / logic checks:**
- Row inserted with session_id + LOW_BALANCE is retrievable by (session_id, LOW_BALANCE) lookup
- notification_type outside (LOW_BALANCE, SETTLEMENT_DUE) raises CHECK violation
- expires_at is NOT NULL; null insert raises violation
- Index idx_psd_session_type appears in EXPLAIN for the session-dismiss lookup
- Two rows with the same (session_id, notification_type) are allowed (no unique constraint needed; presence of any row = dismissed)
**Depends on:** 11.2-T01

### 11.2-T04 — Implement GET /portal/v1/balance endpoint (prefunding balance read)  _(45 min)_
**Context:** PRD-08 §3.1, §6.1, API-05 §4.8: returns real-time prefunding balance for OVERSEAS partner. Response: {partner_id, balance_usd (DECIMAL as string to 2dp), low_balance_threshold_usd, is_below_threshold (balance_usd < low_balance_threshold_usd), as_of (ISO-8601 UTC), last_topup_date (DATE of most recent CREDIT_TOPUP entry or null)}. For LOCAL partners return HTTP 403 {error: PREFUNDING_NOT_APPLICABLE}. partner_id is derived from the authenticated JWT claims only — never from request body. Data source: prefunding_account (balance, low_balance_threshold) joined to most recent prefunding_ledger_entry with entry_type=CREDIT_TOPUP. Response time target: P95 < 1 second.
**Steps:** Create PortalBalanceController with GET /portal/v1/balance mapped to getBalance(); Extract partner_id from JWT claims; query partner table for type; return 403 if type=LOCAL; Query prefunding_account WHERE partner_id = :partner_id using SELECT (no FOR UPDATE — read only); Query MAX(created_at) from prefunding_ledger_entry WHERE account_id = account.id AND entry_type = CREDIT_TOPUP for last_topup_date; Compute is_below_threshold = (balance < low_balance_threshold); serialize balance_usd as string with exactly 2 decimal places; set as_of to current UTC timestamp
**Deliverable:** GET /portal/v1/balance endpoint with service and repository layer
**Acceptance / logic checks:**
- OVERSEAS partner with balance=48234.56, threshold=10000.00: response returns balance_usd=48234.56, is_below_threshold=false
- OVERSEAS partner with balance=8500.00, threshold=10000.00: is_below_threshold=true
- LOCAL partner returns HTTP 403 with error code PREFUNDING_NOT_APPLICABLE
- partner_id from JWT overrides any partner_id supplied in query params (isolation check)
- balance_usd is serialized as a string with exactly 2 decimal places (e.g. 48234.56 not 48234.5600)
**Depends on:** 11.2-T02

### 11.2-T05 — Implement GET /portal/v1/dashboard/activity — today activity summary  _(50 min)_
**Context:** PRD-08 §3.2: today is defined as current calendar day in KST (UTC+9). Fields: today_txn_count (count of transactions WHERE partner_id=:pid AND txn_status=APPROVED AND DATE(txn_timestamp AT TIME ZONE KST) = today_kst), today_payout_volume_krw (SUM(target_payout) WHERE payout_ccy=KRW and same filter), today_collection_volume (SUM(collection_amount) in partner settlement currency), success_rate = APPROVED / (APPROVED + DECLINED) * 100 formatted to 1dp, collection_ccy. Transactions table fields: partner_id, txn_status (APPROVED|DECLINED|PENDING|CANCELLED|REFUNDED), txn_timestamp (TIMESTAMPTZ stored UTC), target_payout DECIMAL(20,0) for KRW (0dp), collection_amount DECIMAL(20,4), payout_ccy CHAR(3), collection_ccy CHAR(3). Zero-transaction day: return counts=0, volumes=0, success_rate=null.
**Steps:** Create DashboardActivityService.getTodayActivity(partnerId, nowUtc); Compute today_kst = nowUtc converted to KST (UTC+9); define day window as [today_kst 00:00:00 KST, today_kst 23:59:59 KST] expressed in UTC for query; Query transactions: COUNT where txn_status=APPROVED, SUM(target_payout) where payout_ccy=KRW, SUM(collection_amount) with collection_ccy, COUNT(DECLINED) for success_rate denominator; single query with conditional aggregates; Compute success_rate = CASE WHEN (approved+declined)=0 THEN null ELSE ROUND(approved*100.0/(approved+declined),1) END; Return DTO with all fields; collection_ccy taken from first row or partner's settlement currency config
**Deliverable:** GET /portal/v1/dashboard/activity endpoint and DashboardActivityService
**Acceptance / logic checks:**
- Partner with 234 APPROVED and 2 DECLINED today: today_txn_count=234, success_rate=99.2 (not 99.1 — verify: 234/236*100=99.152 rounds to 99.2)
- Partner with 0 transactions today: all counts=0, success_rate=null (not 0.0)
- Transactions from yesterday (KST) are not counted even if they fall within UTC today boundary (e.g. 2026-06-04T15:59:59Z = 2026-06-05T00:59:59 KST counted as today, 2026-06-04T14:59:59Z = 2026-06-04T23:59:59 KST counted as yesterday)
- partner_id filter ensures Partner A activity is not visible to Partner B
- today_payout_volume_krw uses KRW 0 decimal places (integer sum, no decimal point)
**Depends on:** 11.2-T04

### 11.2-T06 — Implement GET /portal/v1/dashboard/recent-transactions — last 10 transactions  _(40 min)_
**Context:** PRD-08 §3.3: returns the 10 most recent transactions for the authenticated partner. Columns: txn_timestamp (UTC in DB, convert to KST for display), hub_txn_ref (first 12 chars truncated + full ref for link), merchant_name (from gmepay merchant table), target_payout DECIMAL + payout_ccy, collection_amount DECIMAL + collection_ccy, txn_status. Sort: txn_timestamp DESC LIMIT 10. Transaction timestamps stored in UTC; response includes both utc_timestamp and kst_display (YYYY-MM-DD HH:mm:ss KST). Security: WHERE partner_id = :authenticated_partner_id at repository layer.
**Steps:** Create DashboardRecentTxnRepository.findTop10ByPartnerId(partnerId) using query: SELECT t.txn_timestamp, t.hub_txn_ref, m.merchant_name, t.target_payout, t.payout_ccy, t.collection_amount, t.collection_ccy, t.txn_status FROM transaction t LEFT JOIN merchant m ON m.id=t.merchant_id WHERE t.partner_id=:partnerId ORDER BY t.txn_timestamp DESC LIMIT 10; Map hub_txn_ref to truncated_txn_ref = first 12 chars; include full hub_txn_ref in response; Convert txn_timestamp UTC to KST offset (+09:00) for kst_display field; Expose GET /portal/v1/dashboard/recent-transactions returning array of up to 10 objects; Handle case where merchant not found (LEFT JOIN): merchant_name = null
**Deliverable:** GET /portal/v1/dashboard/recent-transactions endpoint with repository
**Acceptance / logic checks:**
- Returns exactly 10 rows when partner has >= 10 transactions; fewer rows when < 10
- Rows are ordered by txn_timestamp DESC (most recent first)
- hub_txn_ref HUB-20260601-00123456 returned as truncated_txn_ref=HUB-20260601 (12 chars)
- txn_timestamp 2026-06-04T09:14:32Z returned as kst_display=2026-06-04 18:14:32 (UTC+9 offset)
- Partner A cannot see Partner B transactions: querying with Partner A session returns only Partner A rows even if Partner B has more recent transactions
**Depends on:** 11.2-T05

### 11.2-T07 — Implement GET /portal/v1/dashboard — aggregate dashboard response  _(50 min)_
**Context:** PRD-08 §3: the Dashboard page requires three data sources combined into one API response to meet the P95 < 2 second page load target (PRD-08 §10.1). Aggregate endpoint: GET /portal/v1/dashboard returns {balance_panel (null for LOCAL), activity_today, recent_transactions[10], alerts[]}. For LOCAL partners: balance_panel=null, no prefunding fields. Alerts array is populated from portal_notification WHERE user_id=:uid AND is_read=false AND NOT session-dismissed. Each data section is fetched in parallel (async/CompletableFuture or equivalent). partner_type from partner table drives conditional balance fetch.
**Steps:** Create DashboardFacade that calls BalanceService, ActivityService, RecentTxnService, and AlertService in parallel; For LOCAL partners skip BalanceService call and set balance_panel=null in response; Collect all futures with a timeout of 1500ms; on timeout return partial response with timed-out sections set to null and error_sections array listing which sections failed; Expose GET /portal/v1/dashboard returning the composite DTO; Include partner_type (LOCAL|OVERSEAS) in response so frontend can conditionally render balance panel without a separate call
**Deliverable:** GET /portal/v1/dashboard composite endpoint with DashboardFacade
**Acceptance / logic checks:**
- OVERSEAS partner response contains non-null balance_panel with balance_usd, low_balance_threshold_usd, is_below_threshold, last_topup_date
- LOCAL partner response: balance_panel is null; partner_type=LOCAL in response body
- Response always includes activity_today and recent_transactions even if balance fetch fails (partial degradation)
- Concurrent load: two users from different partners calling simultaneously receive correct partner-scoped data
- Response is returned within 1500ms timeout; timed-out sections appear in error_sections list
**Depends on:** 11.2-T04, 11.2-T05, 11.2-T06

### 11.2-T08 — Implement low-balance alert evaluation logic (post-deduction hook)  _(45 min)_
**Context:** PRD-08 §9.1, PRD-07 §9.5: after every successful prefunding deduction, the Prefunding Ledger must evaluate whether the new balance is below the partner's configured low_balance_threshold (prefunding_account.low_balance_threshold, default 10000.00 USD). If balance_after < threshold: emit internal event prefunding.low_balance containing {partner_id, balance_after, threshold, entry_id}. If balance_after <= 0.00: emit prefunding.zero_balance (all payments suspended). The evaluation is part of the atomic deduction transaction — the event is published AFTER the DB commit (transactional outbox or post-commit hook). The deduction itself is NOT blocked by alert evaluation.
**Steps:** In PrefundingLedgerService.deductBalance(), after committing the new balance, call AlertEvaluator.evaluate(partnerId, balanceAfter, threshold); AlertEvaluator.evaluate: if balanceAfter <= 0 publish PREFUNDING_ZERO_BALANCE event; else if balanceAfter < threshold publish PREFUNDING_LOW_BALANCE event; else no event; Use transactional outbox pattern: write event to outbox_event table within same DB transaction; relay publishes after commit; Include idempotency: if a PREFUNDING_LOW_BALANCE event was already created for this entry_id (prefunding_ledger_entry.id), skip duplicate publish; Expose AlertEvaluator as a testable unit with no DB dependency (pass balance, threshold as inputs)
**Deliverable:** AlertEvaluator service and transactional outbox integration, unit tested
**Acceptance / logic checks:**
- balance_after=8500.00, threshold=10000.00: PREFUNDING_LOW_BALANCE event emitted
- balance_after=10000.00, threshold=10000.00: PREFUNDING_LOW_BALANCE emitted (strictly less is false — boundary: 10000 is NOT below 10000, so no alert)
- balance_after=9999.99, threshold=10000.00: PREFUNDING_LOW_BALANCE emitted
- balance_after=0.00, threshold=10000.00: PREFUNDING_ZERO_BALANCE emitted (not LOW_BALANCE)
- balance_after=-0.01 (should never occur due to guard, but defensive): PREFUNDING_ZERO_BALANCE emitted
**Depends on:** 11.2-T04

### 11.2-T09 — Implement in-portal notification creation from alert events  _(40 min)_
**Context:** PRD-08 §9.1: when a PREFUNDING_LOW_BALANCE event is received, create a portal_notification row for every active portal user of that partner (role PARTNER_ADMIN and PARTNER_VIEWER). Type=LOW_BALANCE, severity=HIGH, title=Low Balance Alert, body=Current balance: USD {balance_after} is below your threshold of USD {threshold}. Request immediate top-up via wire transfer.. When PREFUNDING_ZERO_BALANCE: type=LOW_BALANCE, severity=HIGH, body=URGENT: Balance has reached USD 0.00. All overseas payments are suspended until balance is restored.. Insert one row per user in partner_portal_user WHERE partner_id=:pid AND is_active=true. Do not create duplicates if an unread LOW_BALANCE notification already exists for the same user (idempotency key: user_id + notification_type + DATE(created_at)).
**Steps:** Create NotificationEventConsumer listening on PREFUNDING_LOW_BALANCE and PREFUNDING_ZERO_BALANCE events; For each event fetch all active partner portal users (partner_id, is_active=true); Check for existing unread notification of same type created today (same calendar day UTC) per user; skip if exists; Bulk insert portal_notification rows for users who do not already have an unread notification of this type today; Log number of notifications created and any skipped (idempotency hit)
**Deliverable:** NotificationEventConsumer creating portal_notification rows per user on low-balance events
**Acceptance / logic checks:**
- PREFUNDING_LOW_BALANCE event for partner with 3 active users creates 3 portal_notification rows with type=LOW_BALANCE, severity=HIGH
- Second event for same partner same day (already has unread notification): no duplicate rows created
- PREFUNDING_ZERO_BALANCE creates notification with body starting URGENT:
- Inactive portal user (is_active=false) does not receive a notification row
- Notification body contains exact current balance and threshold values from the event payload
**Depends on:** 11.2-T08, 11.2-T01

### 11.2-T10 — Implement email dispatch for low-balance and zero-balance alerts  _(45 min)_
**Context:** PRD-08 §9.2: when PREFUNDING_LOW_BALANCE or PREFUNDING_ZERO_BALANCE event fires, send email to all addresses in the partner's alert email list. Alert emails are configured in low_balance_alert_config.alert_email (one or more comma-separated addresses, stored as VARCHAR(255) per row — there may be multiple rows per partner). Email content for LOW_BALANCE: subject=GMEPay+ Low Balance Alert — {partner_name}, body includes current balance (USD), threshold (USD), recommended top-up amount (threshold - current_balance, minimum USD 0), GME bank account top-up instructions, account team contact email. For ZERO_BALANCE: subject=URGENT: GMEPay+ Balance Depleted — {partner_name}. Use platform email service (SMTP/SES). Low-balance email is NOT suppressed if in-portal notification creation fails.
**Steps:** Create LowBalanceEmailService.sendAlert(partnerId, balanceAfter, threshold, eventType); Query low_balance_alert_config WHERE partner_id=:pid AND is_active=true; collect all alert_email values; Build email DTO: compute recommended_topup = MAX(0, threshold - balance_after); format all USD amounts to 2dp; Dispatch email via EmailGateway (injected interface over SMTP/SES); log success/failure per recipient address; For ZERO_BALANCE: use URGENT subject line and highlight that all overseas payments are now suspended
**Deliverable:** LowBalanceEmailService with unit tests and integration to event consumer
**Acceptance / logic checks:**
- partner with threshold=10000.00 and balance_after=7800.00: recommended_topup in email body=2200.00 (10000.00-7800.00)
- partner with balance_after=0.00: recommended_topup=10000.00 (threshold, since balance already 0)
- Email sent to all active alert_email addresses (e.g. 3 rows in config -> 3 separate emails or 3 recipients)
- Email is sent even when in-portal notification insert fails (independent error paths)
- No email sent if no active low_balance_alert_config rows exist for partner (no alert configured)
**Depends on:** 11.2-T08

### 11.2-T11 — Implement GET /portal/v1/notifications endpoint and unread count  _(35 min)_
**Context:** PRD-08 §9.1: the portal header shows a notification bell with unread count. GET /portal/v1/notifications returns all notifications for the authenticated user ordered by created_at DESC, with pagination (page, size default 20). Each item: {id, notification_type, severity, title, body, is_read, created_at}. GET /portal/v1/notifications/count returns {unread_count: N}. The unread count is the number of portal_notification rows WHERE user_id=:uid AND is_read=false. partner_id from JWT; user_id from JWT claims. Both endpoints are partner-user scoped — a user cannot see another user's notifications even within the same partner.
**Steps:** Create NotificationRepository.findByUserId(userId, pageable) and countUnread(userId); Expose GET /portal/v1/notifications?page=0&size=20 returning paginated list; Expose GET /portal/v1/notifications/count returning {unread_count}; Extract user_id from JWT claims (not from query param); enforce WHERE user_id = :authenticated_user_id; Return empty list (not 404) when user has no notifications
**Deliverable:** GET /portal/v1/notifications and GET /portal/v1/notifications/count endpoints
**Acceptance / logic checks:**
- User with 5 unread and 3 read notifications: /count returns {unread_count:5}; /notifications returns 8 total ordered by created_at DESC
- Pagination: size=3 returns first 3 notifications; page=1&size=3 returns next 3
- User A cannot see User B's notifications even if both belong to the same partner (user_id isolation)
- Empty notification list returns {items:[], total:0} not 404
- After marking all as read: /count returns {unread_count:0}
**Depends on:** 11.2-T09

### 11.2-T12 — Implement PATCH /portal/v1/notifications/{id}/read to mark notification read  _(30 min)_
**Context:** PRD-08 §9.1: dismissing a notification (marking it read) clears it for that user only. PATCH /portal/v1/notifications/{id}/read sets portal_notification.is_read=true, read_at=now() for the specified notification row. Security: the notification must belong to the authenticated user (WHERE id=:id AND user_id=:authenticated_user_id). If not found or belongs to another user, return 404 (never 403 to avoid confirming existence). Also implement PATCH /portal/v1/notifications/read-all to mark all unread notifications for the user as read in a single UPDATE.
**Steps:** Create NotificationService.markRead(notificationId, userId): UPDATE portal_notification SET is_read=true, read_at=now() WHERE id=:id AND user_id=:userId; if 0 rows updated return 404; Expose PATCH /portal/v1/notifications/{id}/read calling markRead; return 200 {id, is_read:true, read_at}; Create NotificationService.markAllRead(userId): UPDATE ... WHERE user_id=:userId AND is_read=false; return count of rows updated; Expose PATCH /portal/v1/notifications/read-all returning {marked_read_count: N}; Validate notification id is valid UUID v4 format; return 422 for malformed IDs
**Deliverable:** PATCH /portal/v1/notifications/{id}/read and PATCH /portal/v1/notifications/read-all endpoints
**Acceptance / logic checks:**
- Valid notification belonging to authenticated user: returns 200 with is_read=true and non-null read_at
- Notification belonging to different user: returns 404 (not 403)
- Non-existent notification ID: returns 404
- read-all endpoint: if user has 5 unread notifications, returns {marked_read_count:5}; subsequent /count returns 0
- Malformed UUID in path returns 422
**Depends on:** 11.2-T11

### 11.2-T13 — Implement POST /portal/v1/notifications/dismiss-session for session-scoped low-balance dismissal  _(40 min)_
**Context:** PRD-08 §3.4: the low-balance alert on the Dashboard is dismissible for the session only. On dismiss, the client calls this endpoint. The server writes a row to portal_session_dismiss (session_id from JWT, notification_type=LOW_BALANCE, partner_id, expires_at = now() + 8h matching max session lifetime). At Dashboard render, the GET /portal/v1/dashboard response includes alerts[] which checks portal_session_dismiss for the current session_id; if a matching row exists, the low-balance alert is omitted from the alerts array even if is_below_threshold=true. The session_id is a UUID embedded in the JWT (separate from user_id).
**Steps:** Expose POST /portal/v1/notifications/dismiss-session with body {notification_type: LOW_BALANCE}; Extract session_id from JWT claims; insert into portal_session_dismiss (session_id, notification_type, partner_id, dismissed_at=now(), expires_at=now()+8h); In DashboardAlertService.getAlerts(userId, partnerId, sessionId): for each potential alert check portal_session_dismiss for (session_id, notification_type) with expires_at > now(); suppress if dismissed; Return 200 {dismissed: true, expires_at} on success; Add cleanup: scheduled job DELETE FROM portal_session_dismiss WHERE expires_at < now() (runs every 30 min)
**Deliverable:** POST /portal/v1/notifications/dismiss-session endpoint and DashboardAlertService session-dismiss check
**Acceptance / logic checks:**
- After calling dismiss-session for LOW_BALANCE, subsequent GET /portal/v1/dashboard alerts[] does not include low-balance alert even when balance < threshold
- Dismissal is session-scoped: a different user session for the same partner still sees the low-balance alert
- Dismissal expires after 8 hours: expired entry (expires_at in past) does not suppress the alert
- notification_type not in (LOW_BALANCE, SETTLEMENT_DUE) returns 422
- Cleanup job removes rows with expires_at < now() and leaves valid rows untouched
**Depends on:** 11.2-T07, 11.2-T03

### 11.2-T14 — Implement DashboardAlertService: populate alerts[] in dashboard response  _(45 min)_
**Context:** PRD-08 §3.4: Dashboard alerts[] contains active alerts for the authenticated partner user. Two alert types: (1) LOW_BALANCE: shown when prefunding_account.balance < prefunding_account.low_balance_threshold AND NOT session-dismissed (check portal_session_dismiss). Alert payload: {type:LOW_BALANCE, current_balance_usd, threshold_usd, is_zero_balance (balance<=0), topup_instructions_url:/portal/v1/prefunding/topup-info}. (2) SETTLEMENT_DUE: shown when the latest settlement_batch for partner has status != RECONCILED and created_at < now() minus configurable window (default 2 business days). LOCAL partners: never show LOW_BALANCE alert. Zero-balance alert is a variant of LOW_BALANCE with is_zero_balance=true.
**Steps:** Create DashboardAlertService.getAlerts(partnerId, userId, sessionId) returning List<AlertDTO>; For OVERSEAS partners: fetch balance and threshold from prefunding_account; check session dismiss; add LOW_BALANCE alert if balance < threshold and not dismissed; set is_zero_balance=true if balance<=0; For SETTLEMENT_DUE: query MAX(settlement_batch.created_at) for partner; if overdue by > 2 business days (configurable: settlement_overdue_threshold_days, default=2) add SETTLEMENT_DUE alert; Integrate DashboardAlertService into DashboardFacade (11.2-T07) so alerts[] is populated in the dashboard response; Business day calculation: skip Saturday and Sunday when counting days overdue
**Deliverable:** DashboardAlertService integrated into dashboard response with both alert types
**Acceptance / logic checks:**
- OVERSEAS partner balance=8000.00, threshold=10000.00, not session-dismissed: alerts[] contains one LOW_BALANCE entry with current_balance_usd=8000.00
- OVERSEAS partner balance=0.00: LOW_BALANCE alert with is_zero_balance=true
- LOCAL partner: alerts[] never contains LOW_BALANCE even if partner has no prefunding data
- Settlement overdue by 3 business days (configured threshold=2): SETTLEMENT_DUE appears in alerts
- Settlement overdue by 1 business day (threshold=2): SETTLEMENT_DUE not in alerts
**Depends on:** 11.2-T13, 11.2-T07

### 11.2-T15 — Implement GET /portal/v1/prefunding/balance-panel — balance summary widget data  _(40 min)_
**Context:** PRD-08 §3.1 and §6.1: the balance summary panel on the dashboard and the Prefunding section both show the same fields. This dedicated endpoint returns full balance panel data: available_balance_usd, low_balance_threshold_usd, is_below_threshold, last_topup_date (date of most recent CREDIT_TOPUP entry), reserved_pending_usd (sum of in-flight payment deductions not yet in status APPROVED or DECLINED — informational; sum of prefunding_ledger_entry.amount WHERE entry_type=DEBIT_PAYMENT AND txn_ref in transactions with txn_status=PENDING), status_indicator (GREEN if not below threshold, RED if below threshold), last_updated (most recent updated_at on prefunding_account). Returns 403 for LOCAL partners.
**Steps:** Create BalancePanelService.getBalancePanelData(partnerId); Query prefunding_account for balance, low_balance_threshold, last_updated (updated_at); Query prefunding_ledger_entry for last CREDIT_TOPUP entry: SELECT MAX(created_at) WHERE account_id=:id AND entry_type=CREDIT_TOPUP; Compute reserved_pending_usd: SUM(ple.amount) FROM prefunding_ledger_entry ple JOIN transaction t ON t.hub_txn_ref=ple.txn_ref WHERE ple.account_id=:id AND ple.entry_type=DEBIT_PAYMENT AND t.txn_status=PENDING; Compute status_indicator: GREEN if balance >= threshold, RED if balance < threshold; expose GET /portal/v1/prefunding/balance-panel
**Deliverable:** GET /portal/v1/prefunding/balance-panel endpoint and BalancePanelService
**Acceptance / logic checks:**
- Balance=45200.00, threshold=10000.00: status_indicator=GREEN, is_below_threshold=false
- Balance=9999.99, threshold=10000.00: status_indicator=RED, is_below_threshold=true
- Balance=10000.00, threshold=10000.00: status_indicator=GREEN, is_below_threshold=false (not strictly less than)
- reserved_pending_usd = 0.00 when there are no PENDING transactions
- LOCAL partner: HTTP 403 returned; no DB balance query executed
**Depends on:** 11.2-T04

### 11.2-T16 — Unit tests: balance endpoint edge cases and threshold logic  _(45 min)_
**Context:** WBS 11.2 balance logic has several numeric edge cases: (1) balance exactly equals threshold (boundary: not below), (2) balance is 0.00 (payments suspended), (3) balance is negative (possible if concurrent race slips through guard — should not occur but must be handled defensively), (4) threshold is 0.00 (no alert ever fires). DECIMAL fields use scale=4 in DB (DECIMAL(20,4)) but are presented to portal as 2dp strings. The is_below_threshold boolean must use strict less-than: balance < threshold. Test the BalanceService, AlertEvaluator, and DashboardAlertService in isolation using mocked repositories.
**Steps:** Write BalanceServiceTest: test getBalance() returns is_below_threshold=false when balance==threshold (10000.0000 == 10000.0000); Write BalanceServiceTest: balance=0.0000 returns balance_usd=0.00 as string and is_below_threshold=true; Write BalanceServiceTest: balance=-50.0000 returns balance_usd=-50.00 (defensive; no exception); Write AlertEvaluatorTest covering all 5 boundary cases from 11.2-T08 checks; Write DashboardAlertServiceTest: LOCAL partner never returns LOW_BALANCE alert; threshold=0.00 never returns LOW_BALANCE (0 < 0 is false)
**Deliverable:** Unit test class BalanceServiceTest, AlertEvaluatorTest, DashboardAlertServiceTest with >= 15 test methods
**Acceptance / logic checks:**
- BalanceServiceTest: balance=10000.0000, threshold=10000.0000 -> is_below_threshold=false
- AlertEvaluatorTest: balance_after=0.00 -> PREFUNDING_ZERO_BALANCE (not LOW_BALANCE)
- DashboardAlertServiceTest: threshold=0.00 -> no LOW_BALANCE alert for any balance value
- BalanceServiceTest: balance_usd string has exactly 2dp for all test inputs (0.00, 48234.56, 10000.00)
- All 15+ tests pass with no mocked expectations left unverified
**Depends on:** 11.2-T08, 11.2-T14, 11.2-T15

### 11.2-T17 — Unit tests: today activity summary KST date boundary and success rate  _(40 min)_
**Context:** DashboardActivityService (11.2-T05) computes today in KST (UTC+9). Two critical edge cases: (1) a transaction at 2026-06-04T14:59:59Z = 2026-06-04T23:59:59 KST is counted as 2026-06-04 in KST; (2) a transaction at 2026-06-04T15:00:00Z = 2026-06-05T00:00:00 KST is counted as 2026-06-05 KST. Success rate: ROUND(approved/(approved+declined)*100, 1); zero denominator returns null. Test with fixed clock (injected Clock or Instant for determinism).
**Steps:** Write ActivityServiceTest with mocked repository returning transactions at specific UTC timestamps; Test KST boundary: transaction at T14:59:59Z with nowUtc=2026-06-04T15:00:00Z -> included in today count (both are 2026-06-04 KST); Test KST boundary: transaction at T14:59:59Z with nowUtc=2026-06-05T00:00:00Z -> NOT included (yesterday KST); Test success_rate: 234 approved, 2 declined -> 99.2 (verify: 234/236=0.99152 rounds to 99.2 not 99.1); Test zero-transaction day: all counts 0, success_rate=null
**Deliverable:** ActivityServiceTest with >= 8 test methods covering KST boundary and rate calculation
**Acceptance / logic checks:**
- Transaction at 2026-06-04T14:59:59Z is counted when server clock is 2026-06-04T15:30:00Z (both KST 2026-06-04)
- Transaction at 2026-06-04T15:00:00Z is NOT counted when server clock is 2026-06-04T15:30:00Z (transaction is KST 2026-06-05, future)
- 234 approved + 2 declined -> success_rate = 99.2 (ROUND(234/236*100, 1) = 99.2)
- 100 approved + 0 declined -> success_rate = 100.0
- 0 approved + 0 declined -> success_rate = null (not 0.0 or NaN)
**Depends on:** 11.2-T05

### 11.2-T18 — Unit tests: notification creation idempotency and event routing  _(40 min)_
**Context:** NotificationEventConsumer (11.2-T09) must not create duplicate in-portal notifications. Idempotency rule: if a portal_notification row already exists with user_id=X, notification_type=LOW_BALANCE, is_read=false, and DATE(created_at)=today, skip creation. Test with a mocked portal_notification repository. Also test LowBalanceEmailService: correct recipient count, recommended_topup computation, ZERO_BALANCE subject line, and no-config-no-email guard.
**Steps:** Write NotificationConsumerTest: first LOW_BALANCE event creates 3 notifications for 3 active users; Write NotificationConsumerTest: second LOW_BALANCE event same day -> 0 new rows created (all 3 already have unread notification); Write NotificationConsumerTest: user with is_active=false -> not included in recipient list; Write EmailServiceTest: balance=7800, threshold=10000 -> recommended_topup=2200.00 in email body; Write EmailServiceTest: balance=0, threshold=10000 -> recommended_topup=10000.00; subject contains URGENT
**Deliverable:** NotificationConsumerTest and EmailServiceTest with >= 10 test methods
**Acceptance / logic checks:**
- Second identical LOW_BALANCE event for same partner same day creates 0 additional portal_notification rows
- Inactive user not included: partner has 2 active and 1 inactive user -> only 2 notifications created
- recommended_topup for balance=7800.00, threshold=10000.00 is 2200.00 (not negative)
- recommended_topup for balance=12000.00 (above threshold, guard alert not fired): edge case — MAX(0, 10000-12000)=0.00
- ZERO_BALANCE email subject contains the word URGENT
**Depends on:** 11.2-T09, 11.2-T10

### 11.2-T19 — Integration test: dashboard balance panel data isolation between partners  _(55 min)_
**Context:** SEC-09 §3.5 and PRD-08 §2.2: the most critical security property is that partner A cannot see partner B data. This integration test uses a real (test) database with two OVERSEAS partners (SendMN with balance=45200.00, TBank with balance=8500.00, threshold=10000.00) and two portal user accounts, one per partner. Exercises GET /portal/v1/dashboard and GET /portal/v1/balance for each partner and asserts complete data isolation.
**Steps:** Seed test DB: partner SendMN (OVERSEAS, balance=45200.00, threshold=10000.00), partner TBank (OVERSEAS, balance=8500.00, threshold=10000.00); Create portal_user_sendmn (role=PARTNER_VIEWER) and portal_user_tbank (role=PARTNER_VIEWER); issue separate JWTs with correct partner_id claims; Call GET /portal/v1/balance with portal_user_sendmn JWT; assert balance_usd=45200.00, is_below_threshold=false; Call GET /portal/v1/balance with portal_user_tbank JWT; assert balance_usd=8500.00, is_below_threshold=true; Call GET /portal/v1/dashboard with portal_user_sendmn JWT; assert alerts[] is empty (no LOW_BALANCE since 45200>10000); assert balance_panel.balance_usd=45200.00
**Deliverable:** Integration test class DashboardIsolationTest with 5+ test methods against in-memory or Testcontainers PostgreSQL
**Acceptance / logic checks:**
- SendMN user cannot see TBank balance: GET /portal/v1/balance with SendMN JWT returns 45200.00 not 8500.00
- TBank user sees is_below_threshold=true (8500 < 10000)
- SendMN dashboard alerts[] is empty (balance above threshold)
- TBank dashboard alerts[] contains LOW_BALANCE with current_balance_usd=8500.00
- Attempting to pass partner_id=sendmn_id as query param while authenticated as TBank user still returns TBank balance (JWT overrides query param)
**Depends on:** 11.2-T07, 11.2-T14

### 11.2-T20 — Integration test: low-balance alert end-to-end flow (deduction -> event -> notification -> email)  _(55 min)_
**Context:** End-to-end integration covering the full low-balance alert pipeline: (1) prefunding deduction drops balance below threshold, (2) PREFUNDING_LOW_BALANCE event emitted via transactional outbox, (3) NotificationEventConsumer creates portal_notification rows, (4) LowBalanceEmailService dispatches email to configured recipients, (5) GET /portal/v1/notifications returns the new notification. Uses Testcontainers (PostgreSQL + embedded SMTP or mock EmailGateway). Partner: balance=10500.00, threshold=10000.00; deduction amount=600.00 -> balance_after=9900.00.
**Steps:** Seed: OVERSEAS partner with balance=10500.00, threshold=10000.00; portal user (active); low_balance_alert_config with alert_email=ops@partner.example; Trigger deduction of 600.00 USD via PrefundingLedgerService.deductBalance(); Assert prefunding_account.balance=9900.00 after deduction; Wait for outbox relay to publish event and consumer to process (or call processOutbox() directly in test); Assert: portal_notification row created for user with type=LOW_BALANCE; mock EmailGateway received 1 call with recipient=ops@partner.example and body containing 9900.00 and 10000.00
**Deliverable:** Integration test LowBalanceAlertIntegrationTest with 5 assertion steps
**Acceptance / logic checks:**
- After deduction: prefunding_account.balance=9900.00 (10500.00-600.00)
- portal_notification created with notification_type=LOW_BALANCE, body contains current_balance_usd=9900.00
- EmailGateway.send() called once with recipient ops@partner.example
- Email body contains recommended_topup=100.00 (10000.00-9900.00)
- Second deduction of 100.00 (balance=9800.00) does NOT create second notification same day (idempotency)
**Depends on:** 11.2-T09, 11.2-T10, 11.2-T18

### 11.2-T21 — Implement GET /portal/v1/prefunding/ledger — paginated balance movement history  _(40 min)_
**Context:** PRD-08 §6.2: paginated ledger of all prefunding balance movements for OVERSEAS partner. Columns: created_at (KST display), entry_type (DEBIT_PAYMENT|CREDIT_TOPUP|DEBIT_REVERSAL|CREDIT_REVERSAL -> display as Debit|Credit|Adjustment), txn_ref, amount_usd (negative for DEBIT types in display, positive for CREDIT), balance_after_usd. Default date range: last 30 days. Filter: date_from, date_to (max 1 year). Pagination: default page size 30, max 100. Sort: created_at DESC. Running balance must match balance_after stored in prefunding_ledger_entry (do not recompute — use stored value). Returns 403 for LOCAL partners.
**Steps:** Create PrefundingLedgerRepository.findByAccountId(accountId, dateFrom, dateTo, pageable); Map entry_type to display_type: DEBIT_PAYMENT->Debit, CREDIT_TOPUP->Credit, DEBIT_REVERSAL->Adjustment, CREDIT_REVERSAL->Adjustment; Format amount: DEBIT_* types display amount as negative (e.g. -33.83); CREDIT_* as positive; Expose GET /portal/v1/prefunding/ledger?date_from=&date_to=&page=&size= returning {items[], total, page, size}; Validate date range <= 365 days; return 422 if range exceeds 365 days
**Deliverable:** GET /portal/v1/prefunding/ledger endpoint with PrefundingLedgerRepository
**Acceptance / logic checks:**
- DEBIT_PAYMENT entry with amount=33.83 displayed as amount_display=-33.83 USD
- CREDIT_TOPUP entry with amount=10000.00 displayed as amount_display=+10000.00 USD
- Date range exceeding 365 days returns 422 with error code DATE_RANGE_EXCEEDED
- balance_after_usd matches the stored balance_after column (not recomputed from sum)
- LOCAL partner returns 403; OVERSEAS partner with no entries in the last 30 days returns empty list not 404
**Depends on:** 11.2-T15

### 11.2-T22 — Add DB index for today activity summary and recent-transactions queries  _(35 min)_
**Context:** PRD-08 §10.1: Dashboard P95 < 2 seconds. The two most expensive dashboard queries are: (1) today activity aggregation on transaction table filtered by partner_id + txn_timestamp (KST day window) + txn_status, and (2) recent transactions SELECT TOP 10 filtered by partner_id ORDER BY txn_timestamp DESC. Without indexes these will full-scan on a large transaction table. Required indexes: idx_txn_partner_timestamp on (partner_id, txn_timestamp DESC) for both queries; idx_txn_partner_status_timestamp on (partner_id, txn_status, txn_timestamp DESC) for status-filtered aggregation. Check whether transaction table already has these indexes (from earlier epics); create only if absent.
**Steps:** Create migration V11_2_004: CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_txn_partner_timestamp ON transaction(partner_id, txn_timestamp DESC); Create migration V11_2_005: CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_txn_partner_status_ts ON transaction(partner_id, txn_status, txn_timestamp DESC); Run EXPLAIN ANALYZE on the today-activity query (partner_id=X, txn_timestamp BETWEEN day_start AND day_end); verify index scan used; Run EXPLAIN ANALYZE on recent-transactions query (partner_id=X ORDER BY txn_timestamp DESC LIMIT 10); verify index scan; Document in migration comment that CONCURRENTLY avoids table lock on production
**Deliverable:** Two Flyway migration files adding compound indexes on transaction table, with EXPLAIN ANALYZE output in migration comment
**Acceptance / logic checks:**
- EXPLAIN on recent-transactions query shows Index Scan on idx_txn_partner_timestamp, not Seq Scan
- EXPLAIN on today-activity query shows Index Scan on idx_txn_partner_status_ts
- CREATE INDEX IF NOT EXISTS ensures migration is idempotent (no error if index already exists from earlier epic)
- Both indexes have the correct column order: partner_id first (leading column for equality filter)
- Migration applies and rolls back cleanly; CONCURRENTLY flag is used (no table lock)
**Depends on:** 11.2-T05, 11.2-T06

### 11.2-T23 — Implement scheduled cleanup job for portal_session_dismiss table  _(35 min)_
**Context:** portal_session_dismiss rows have an expires_at column (max 8 hours, matching portal session lifetime per PRD-08 §2.1). Stale rows accumulate if not purged. A scheduled job must DELETE FROM portal_session_dismiss WHERE expires_at < now(). Job schedule: every 30 minutes. Deletion must be in batches (DELETE ... WHERE id IN (SELECT id ... LIMIT 500)) to avoid long lock on the table. Job must log: rows_deleted, run_at, duration_ms. If job fails (DB unavailable) it must retry on the next scheduled run without crashing the application.
**Steps:** Create SessionDismissCleanupJob annotated with @Scheduled(fixedDelay=30min) or cron equivalent; Implement batch delete: loop DELETE FROM portal_session_dismiss WHERE id IN (SELECT id FROM portal_session_dismiss WHERE expires_at < now() LIMIT 500) until rows_deleted_in_batch=0; Log total rows_deleted, run_at (UTC), duration_ms after each run; Wrap execution in try-catch; on exception log error and return (do not rethrow to scheduler); Write unit test: seed 600 expired rows and 50 valid rows; run job; assert 600 deleted and 50 remain
**Deliverable:** SessionDismissCleanupJob with unit test
**Acceptance / logic checks:**
- Job deletes rows where expires_at < now() in batches of max 500 per iteration
- Valid rows (expires_at > now()) are never deleted
- Job exception does not propagate to scheduler thread (application stays running)
- After job run with 600 expired rows: all 600 deleted; 50 valid rows untouched
- Logs contain rows_deleted count and duration_ms for each run
**Depends on:** 11.2-T13

### 11.2-T24 — Wire dashboard frontend: balance panel component (OVERSEAS/LOCAL conditional)  _(45 min)_
**Context:** PRD-08 §3.1 and UX-11 §5.1 wireframe: the balance panel is shown only for OVERSEAS partners. GET /portal/v1/dashboard response includes partner_type and balance_panel (null for LOCAL). Frontend balance panel displays: Available balance USD XX,XXX.XX (2dp, currency code prefix), Threshold USD XX,XXX.XX, status indicator dot (green=OK, red=LOW BALANCE), Last top-up date. When is_below_threshold=true, render a red badge LOW BALANCE next to the balance figure. When balance=0.00 render a red banner PAYMENTS SUSPENDED. Data is fetched on page mount; no manual refresh required (static until page reload).
**Steps:** Create BalancePanelComponent receiving balance_panel prop (null | BalancePanelDTO); If balance_panel is null render nothing (LOCAL partner: component returns null); Render USD amounts formatted as USD XX,XXX.XX (thousands separator, 2dp, currency prefix) using Intl.NumberFormat or equivalent; Render status dot: green circle + OK when not below threshold; red circle + LOW BALANCE when below threshold; Render PAYMENTS SUSPENDED red banner when balance_usd=0.00 (compare as Decimal, not float equality)
**Deliverable:** BalancePanelComponent (React/Vue/Angular per project stack) with Storybook stories or equivalent snapshot tests
**Acceptance / logic checks:**
- balance_panel=null: component renders nothing (no DOM nodes output)
- balance_usd=45200.00, threshold=10000.00: displays USD 45,200.00 and green OK indicator
- balance_usd=8500.00, threshold=10000.00: displays red LOW BALANCE badge
- balance_usd=0.00: displays PAYMENTS SUSPENDED banner (in addition to LOW BALANCE badge)
- balance_usd=48234.56: formatted as USD 48,234.56 (not 48234.56 or USD48234.56)
**Depends on:** 11.2-T07

### 11.2-T25 — Wire dashboard frontend: today activity summary component  _(35 min)_
**Context:** PRD-08 §3.2 and UX-11 §5.1: Today's Activity panel shows: Transactions (integer), Payout volume (KRW with thousands separator, 0dp as KRW has no decimal), Collection volume (partner settlement ccy, 2dp), Success rate (1dp percentage or -- if null). Data comes from GET /portal/v1/dashboard activity_today section. KRW amounts use 0 decimal places (e.g. KRW 12,345,678 not KRW 12,345,678.00). Collection amounts use 2dp (e.g. USD 9,107.22). Success rate null displays as -- not 0.0%.
**Steps:** Create ActivitySummaryComponent receiving activity_today prop; Format today_txn_count as integer with thousands separator (e.g. 1,234); Format today_payout_volume_krw as KRW {amount} with 0dp and thousands separator; Format today_collection_volume as {ccy} {amount} with 2dp; Render success_rate: if null display -- ; else display {rate}% (e.g. 99.2%)
**Deliverable:** ActivitySummaryComponent with snapshot tests covering KRW formatting and null success rate
**Acceptance / logic checks:**
- today_txn_count=1234 renders as 1,234 (with comma separator)
- today_payout_volume_krw=12300000 renders as KRW 12,300,000 (0dp, no decimal point)
- today_collection_volume=9107.22, collection_ccy=USD renders as USD 9,107.22
- success_rate=null renders as -- (not 0.0% or NaN%)
- success_rate=100.0 renders as 100.0% (not 100%)
**Depends on:** 11.2-T07

### 11.2-T26 — Wire dashboard frontend: alerts panel with low-balance alert and session dismiss  _(45 min)_
**Context:** PRD-08 §3.4: the Alerts panel renders alerts from the GET /portal/v1/dashboard alerts[] array. LOW_BALANCE alert shows: current balance, threshold, call-to-action link to prefunding section with top-up instructions. Alert has an X dismiss button that calls POST /portal/v1/notifications/dismiss-session; on success removes the alert from the panel for the rest of the session. SETTLEMENT_DUE alert shows settlement period name and a link to Statements page; not dismissible. Zero-balance alert (is_zero_balance=true on LOW_BALANCE item) shows PAYMENTS SUSPENDED in red with urgent styling. Alerts array empty: panel renders nothing (no empty-state message needed).
**Steps:** Create AlertsPanelComponent receiving alerts[] prop; For each alert render a card with title, body, and optional dismiss button; LOW_BALANCE alert: show current_balance_usd, threshold_usd, link to /prefunding; render dismiss X button; on click call dismissSession(LOW_BALANCE) then remove from local state; LOW_BALANCE with is_zero_balance=true: use red urgent styling and PAYMENTS SUSPENDED heading; SETTLEMENT_DUE alert: show info icon, settlement period text, link to /statements; no dismiss button
**Deliverable:** AlertsPanelComponent with interaction tests for dismiss flow
**Acceptance / logic checks:**
- Empty alerts[]: nothing rendered (no DOM nodes)
- LOW_BALANCE alert renders current balance and threshold from payload
- Clicking X calls POST /portal/v1/notifications/dismiss-session with {notification_type:LOW_BALANCE} and removes alert from DOM on success
- LOW_BALANCE with is_zero_balance=true renders PAYMENTS SUSPENDED heading with red styling
- SETTLEMENT_DUE alert has no dismiss X button
**Depends on:** 11.2-T13, 11.2-T24

### 11.2-T27 — Wire dashboard frontend: recent transactions summary table  _(40 min)_
**Context:** PRD-08 §3.3 and UX-11 §5.1: Recent Transactions table shows 10 rows. Columns: Time (KST HH:mm:ss), Txn ID (truncated 12 chars, linked to /transactions/{hub_txn_ref}), Merchant name, Payout amount + ccy, Status badge. Status badge colors: APPROVED=green, DECLINED=red, PENDING=amber, CANCELLED=grey, REFUNDED=blue. KRW payout amounts use 0dp. Clicking a Txn ID navigates to the transaction detail page at /{partner_slug}/transactions/{hub_txn_ref} (full ref from API). View all link navigates to transaction history page.
**Steps:** Create RecentTransactionsTableComponent receiving recent_transactions[] prop; Render table with columns: time_kst (HH:mm:ss portion of kst_display), truncated_txn_ref as link href=/{partner_slug}/transactions/{hub_txn_ref}, merchant_name, payout amount formatted by currency, status badge; Format payout: if payout_ccy=KRW use 0dp (Intl.NumberFormat with maximumFractionDigits:0); else 2dp; Status badge: APPROVED renders green badge, DECLINED renders red badge; Render View all link to /transactions page
**Deliverable:** RecentTransactionsTableComponent with snapshot tests covering status badges and KRW formatting
**Acceptance / logic checks:**
- APPROVED status renders green badge CSS class
- DECLINED status renders red badge CSS class
- KRW payout=45000 renders as KRW 45,000 (no decimal point)
- USD payout=33.83 renders as USD 33.83
- Txn ID link href for hub_txn_ref=HUB-20260601-00123 is /{partner_slug}/transactions/HUB-20260601-00123 (full ref in href, truncated display)
**Depends on:** 11.2-T07, 11.2-T26

### 11.2-T28 — Wire notification bell in portal header with unread count badge  _(50 min)_
**Context:** PRD-08 §9.1: the portal header contains a bell icon showing unread notification count. Count is fetched from GET /portal/v1/notifications/count on page load and refreshed every 60 seconds (polling). Unread count > 0 shows a red badge with the number. Clicking the bell opens a notification drawer/dropdown listing notifications from GET /portal/v1/notifications (page=0, size=10). Each notification in the drawer shows: severity icon (red for HIGH, amber for WARNING, blue for INFO), title, timestamp (relative: 2 minutes ago). Clicking a notification marks it read (PATCH /portal/v1/notifications/{id}/read) and updates the unread count. Mark all read button calls PATCH /portal/v1/notifications/read-all.
**Steps:** Create NotificationBell component: fetches /notifications/count on mount; sets up 60-second polling interval; displays badge when count > 0; On bell click: fetch /notifications?page=0&size=10; render drawer with notification items; Each notification item: severity icon by severity field; title; relative timestamp (use date-fns or dayjs); On notification item click: call PATCH /notifications/{id}/read; decrement local unread count; update item to show read state; Mark all read button calls PATCH /notifications/read-all; set unread count to 0; refresh list
**Deliverable:** NotificationBell component with polling, drawer, and read/mark-all interaction tests
**Acceptance / logic checks:**
- Unread count=5: bell shows red badge with 5
- Unread count=0: no badge rendered
- Clicking notification calls PATCH /{id}/read and decrements badge count by 1
- Mark all read calls PATCH /read-all and sets badge to 0
- Polling interval is cleared on component unmount (no memory leak)
**Depends on:** 11.2-T11, 11.2-T12

### 11.2-T29 — End-to-end test: Dashboard page for OVERSEAS partner (Playwright/Cypress)  _(55 min)_
**Context:** PRD-08 AC-03 and AC-04: Dashboard shows real-time prefunding balance within 1 second, and LOW BALANCE alert badge displays when balance < threshold. This E2E test uses a seeded test environment: OVERSEAS partner SendMN with balance=8500.00, threshold=10000.00, portal user ops@sendmn.test. Test verifies the full page render including balance panel, activity summary, alerts panel with LOW BALANCE badge, and notification bell count.
**Steps:** Seed test DB via API or SQL: SendMN partner balance=8500.00, threshold=10000.00; today transactions: 10 APPROVED KRW 450000 each, 1 DECLINED; low_balance alert config with ops@sendmn.test; create portal_notification LOW_BALANCE unread for test user; Log in as ops@sendmn.test (bypass TOTP in test env via test-mode flag); Navigate to /sendmn/dashboard; assert balance panel shows USD 8,500.00 and red LOW BALANCE badge; Assert alerts panel contains LOW BALANCE alert with current_balance_usd=8500.00 and threshold=10000.00; Assert activity_today shows txn_count=10 and success_rate=90.9% (10/(10+1)*100=90.9)
**Deliverable:** E2E test file DashboardOverseasPartner.spec (Playwright/Cypress) with 5+ assertions
**Acceptance / logic checks:**
- Balance panel displays USD 8,500.00 formatted with thousands separator and 2dp
- RED LOW BALANCE badge is visible on balance panel
- Alerts panel contains LOW BALANCE card with dismiss X button
- Activity summary success_rate displays 90.9%
- Notification bell badge count >= 1 (unread LOW_BALANCE notification seeded)
**Depends on:** 11.2-T24, 11.2-T25, 11.2-T26, 11.2-T27, 11.2-T28

### 11.2-T30 — End-to-end test: Dashboard page for LOCAL partner (no balance panel)  _(40 min)_
**Context:** PRD-08 §3.1 A-10: LOCAL partners (e.g. GME Remit, partner type=LOCAL) see no balance panel, no LOW BALANCE alert, and no prefunding-related fields. This E2E test verifies the LOCAL partner dashboard renders correctly with activity summary and recent transactions but without any balance or prefunding UI elements. Also verifies GET /portal/v1/balance returns 403 for LOCAL partner session.
**Steps:** Seed test DB: LOCAL partner GMERemit with portal user fin@gmeremit.test; 5 APPROVED transactions today; Log in as fin@gmeremit.test; Navigate to /gmeremit/dashboard; Assert balance panel is not present in the DOM; Assert activity summary shows correct txn_count and no USD amounts in collection volume (LOCAL may use KRW collection)
**Deliverable:** E2E test file DashboardLocalPartner.spec with 4+ assertions
**Acceptance / logic checks:**
- Balance panel component is not rendered (no element with data-testid=balance-panel or equivalent)
- No LOW BALANCE badge or alert card present
- GET /portal/v1/balance with LOCAL partner JWT returns HTTP 403
- Activity summary is present and shows today transaction count
- Alerts panel does not contain LOW_BALANCE type alerts
**Depends on:** 11.2-T29


## WBS 11.3 — Transaction history (filter/export)
### 11.3-T01 — Define TransactionHistoryFilter DTO and query parameter schema  _(30 min)_
**Context:** WBS 11.3 - Partner Portal transaction history. The filter supports: date_from/date_to (ISO-8601 date, default last 7 days, max range 90 days), scheme_id (string), direction (enum: DOMESTIC|INBOUND|OUTBOUND|HUB), status (multi-select enum: APPROVED|DECLINED|PENDING|CANCELLED|REFUNDED), amount_min/amount_max (decimal, in partner settlement currency), payment_mode (enum: MPM|CPM). partner_id is always taken from the authenticated JWT claims, never from request parameters. All fields are optional. Page size options: 25, 50 (default), 100. Sortable by txn_timestamp, target_payout, status.
**Steps:** Create TransactionHistoryFilterDTO class/record with fields: date_from (LocalDate), date_to (LocalDate), scheme_id (String), direction (Direction enum), status (Set<TxnStatus>), amount_min (BigDecimal), amount_max (BigDecimal), payment_mode (PaymentMode enum), page (int default 1), page_size (int default 50), sort_by (enum: TIMESTAMP|PAYOUT_AMOUNT|STATUS), sort_order (enum: ASC|DESC default DESC); Annotate with validation constraints: date range not null if either provided; page_size must be one of [25,50,100]; amount_min and amount_max must be >= 0; sort_by/sort_order have defaults; Write a static factory method fromQueryParams() or equivalent Spring/Jakarta binding; Add unit test verifying defaults are applied when no params provided
**Deliverable:** TransactionHistoryFilterDTO class with validation annotations and default values
**Acceptance / logic checks:**
- date_from and date_to default to (today-6 days) and today when omitted
- page_size rejects any value not in [25, 50, 100]
- status field accepts multiple values (e.g. APPROVED,DECLINED) and parses to a set
- direction field rejects an invalid value (e.g. INVALID) with a 400-level error
- amount_min defaults to null (no lower bound) when omitted

### 11.3-T02 — Add date-range and partner-isolation validation service for filter DTO  _(25 min)_
**Context:** WBS 11.3 - Partner Portal transaction history. Business rules: (1) max date range is 90 calendar days per PRD-08 §4.1; (2) date_from must be <= date_to; (3) partner_id is always injected from the authenticated session JWT claims, never trusted from client input - every query must include WHERE partner_id = :auth_partner_id at the DB layer per PRD-08 §2.2.
**Steps:** Create TransactionFilterValidator class with method validate(TransactionHistoryFilterDTO dto) that returns a list of validation errors; Enforce: if both dates provided, date_from <= date_to; range in calendar days (date_to - date_from) <= 90; Enforce: if only one of date_from/date_to is provided, set the other to complete a valid 7-day or single-day range per business logic; Write unit tests covering: range exactly 90 days (valid), range 91 days (invalid), date_from > date_to (invalid), single date provided (valid, fills other end)
**Deliverable:** TransactionFilterValidator class with unit tests
**Acceptance / logic checks:**
- 90-day range (e.g. 2026-01-01 to 2026-04-01) passes validation
- 91-day range (e.g. 2026-01-01 to 2026-04-02) returns error with message matching spec: Date range cannot exceed 90 days
- date_from = 2026-06-05, date_to = 2026-06-01 returns error (from > to)
- Validation does not inspect or accept partner_id from the DTO; partner_id comes only from auth context
**Depends on:** 11.3-T01

### 11.3-T03 — Create DB index on transactions table to support history filter queries  _(30 min)_
**Context:** WBS 11.3 - Partner Portal transaction history. The transactions table (per DAT-03) has columns: partner_id, txn_timestamp, scheme_id, direction, txn_status, payment_mode, target_payout, collection_amount, hub_txn_ref. Queries filter by partner_id (always) plus any combination of date range, scheme, direction, status, mode, and amount range. The spec requires transaction history query P95 < 3 seconds for up to 1,000 rows (PRD-08 §10.1). Partner_id must always be the leading column in any composite index.
**Steps:** Write a new Flyway migration file (e.g. V11_3_001__txn_history_filter_indexes.sql); Add composite index: idx_txn_partner_timestamp ON transactions (partner_id, txn_timestamp DESC); Add composite index: idx_txn_partner_status ON transactions (partner_id, txn_status, txn_timestamp DESC); Add composite index: idx_txn_partner_scheme_dir ON transactions (partner_id, scheme_id, direction, txn_timestamp DESC); Add comment in migration file explaining each index purpose and the filter patterns it serves
**Deliverable:** Flyway migration V11_3_001__txn_history_filter_indexes.sql with 3 composite indexes
**Acceptance / logic checks:**
- Migration applies cleanly on a fresh schema with no errors
- All indexes have partner_id as the leading column
- EXPLAIN ANALYZE on a query filtering partner_id + date range uses idx_txn_partner_timestamp (not a seq scan) given a dataset of 100k rows
- EXPLAIN ANALYZE on a query filtering partner_id + status uses idx_txn_partner_status
- Migration is idempotent (applying twice does not fail due to IF NOT EXISTS or equivalent guard)

### 11.3-T04 — Implement TransactionHistoryRepository query method with dynamic filtering  _(45 min)_
**Context:** WBS 11.3 - Partner Portal transaction history. The repository must build a dynamic query against the transactions table based on the filter DTO. Columns needed: hub_txn_ref, txn_timestamp, scheme_id, scheme_name, direction, target_payout, payout_ccy, collection_amount, collection_ccy, txn_status, payment_mode. partner_id must be applied as a WHERE clause at the ORM/repository layer (not only in business logic) per PRD-08 §2.2 data isolation rule. Use JPA Criteria API, jOOQ, or Spring Data Specifications - whichever matches the project stack.
**Steps:** Create TransactionHistoryRepository with method: Page<TransactionSummary> findByFilter(UUID partnerId, TransactionHistoryFilterDTO filter, Pageable pageable); Ensure partner_id = :partnerId is always included in the WHERE clause regardless of other filter fields; Dynamically add predicates for each non-null filter field: date range, scheme_id, direction, status (IN clause), payment_mode; Dynamically add amount range predicates on collection_amount when amount_min or amount_max provided; Apply sort from Pageable; default sort is txn_timestamp DESC; Return a Page object containing the result slice and total count
**Deliverable:** TransactionHistoryRepository with findByFilter method
**Acceptance / logic checks:**
- Query with only partner_id always includes WHERE partner_id = ? clause (verified via query log or test assertion)
- Providing status=[APPROVED, DECLINED] generates an IN (APPROVED, DECLINED) clause
- Providing amount_min=10.00 and amount_max=100.00 generates BETWEEN 10.00 AND 100.00 on collection_amount
- Total result count is correct even when page_size < total matching rows (count query is separate)
- Providing no optional filters returns all transactions for the partner ordered by txn_timestamp DESC
**Depends on:** 11.3-T03

### 11.3-T05 — Implement GET /partner/v1/transactions endpoint handler  _(45 min)_
**Context:** WBS 11.3 - Partner Portal transaction history. Endpoint: GET /partner/v1/transactions. Auth: JWT bearer token; partner_id extracted from claims. Query params match TransactionHistoryFilterDTO (T01). Response: { data: [TransactionSummaryDTO], meta: { total_count, page, page_size, total_pages } }. TransactionSummaryDTO fields: hub_txn_ref (truncated to first 12 chars in display but full ref in JSON), txn_timestamp (ISO-8601 UTC, displayed as KST in UI), scheme_name, direction, target_payout, payout_ccy, collection_amount, collection_ccy, txn_status, payment_mode. Per PRD-08 §2.2, partner_id comes only from JWT claims. Performance target: P95 < 3 seconds for up to 1,000 rows.
**Steps:** Create TransactionHistoryController with GET /partner/v1/transactions method; Extract partner_id from Spring Security / JWT principal - never from request params; Call TransactionFilterValidator.validate(); return HTTP 400 with error list on failure; Call TransactionHistoryRepository.findByFilter() with validated filter and Pageable; Map results to TransactionSummaryDTO; include full hub_txn_ref in JSON (truncation is UI-side only); Build and return response envelope with data array and meta object; HTTP 200
**Deliverable:** GET /partner/v1/transactions endpoint returning paginated transaction list
**Acceptance / logic checks:**
- Request without Authorization header returns HTTP 401
- Request with valid JWT for partner A and a filter returns only that partner's transactions (test with seed data for two partners)
- Invalid date range (91 days) returns HTTP 400 with message containing Date range cannot exceed 90 days
- Empty result set returns HTTP 200 with data:[] and meta.total_count:0 (not 404)
- hub_txn_ref field in JSON response contains full reference (e.g. HUB-20260601-00123), not truncated
**Depends on:** 11.3-T02, 11.3-T04

### 11.3-T06 — Implement partner data-isolation enforcement at repository layer (integration test)  _(40 min)_
**Context:** WBS 11.3 - Partner Portal data isolation. PRD-08 §2.2 states: A user from Partner A must receive an empty result (not an error) when querying transaction IDs that belong to Partner B. Integration tests must explicitly verify this. The authenticated partner_id must be embedded in the WHERE clause at the ORM/repository layer - not just in business logic. This is the most critical security property of the Partner Portal.
**Steps:** Create integration test class TransactionIsolationIT with a test DB seeded with transactions for two partners: partner_a (UUID: aaa...) and partner_b (UUID: bbb...); Seed 5 transactions for partner_a and 3 transactions for partner_b with distinct hub_txn_refs; Call findByFilter(partner_a_id, emptyFilter, pageable) and assert total_count = 5 and all returned hub_txn_refs belong to partner_a; Call findByFilter(partner_b_id, emptyFilter, pageable) and assert total_count = 3 and all returned hub_txn_refs belong to partner_b; Attempt direct SQL query without partner_id filter and verify the test schema does not expose a route to bypass the repository method
**Deliverable:** TransactionIsolationIT integration test class with 3+ test methods
**Acceptance / logic checks:**
- partner_a query returns exactly 5 results, all with partner_id = partner_a
- partner_b query returns exactly 3 results, all with partner_id = partner_b
- Querying partner_a with one of partner_b hub_txn_refs in the filter returns empty result (not partner_b data)
- Test fails if the WHERE partner_id clause is removed from the repository method - verifying the test is meaningful
**Depends on:** 11.3-T04, 11.3-T05

### 11.3-T07 — Implement TransactionSummaryDTO mapping and money formatting rules  _(35 min)_
**Context:** WBS 11.3 - Partner Portal transaction history. PRD-08 §10.2 and UX-11 §2.8 specify money formatting: currency code always precedes amount (e.g. USD 10,234.56, KRW 45,000). KRW has 0 decimal places; other currencies use 2 decimal places. Rates displayed to 6 significant figures. Timestamps stored in UTC in DB; displayed as KST (UTC+9) per A-07. TransactionSummaryDTO must contain: hub_txn_ref (String, full), txn_timestamp (String ISO-8601 UTC), scheme_name (String), direction (String), target_payout (BigDecimal), payout_ccy (String), collection_amount (BigDecimal), collection_ccy (String), txn_status (String), payment_mode (String).
**Steps:** Create TransactionSummaryDTO with all required fields as listed in context; Create TransactionSummaryMapper with method fromEntity(Transaction t) -> TransactionSummaryDTO; Ensure txn_timestamp is serialized as ISO-8601 UTC string in JSON (e.g. 2026-06-01T00:14:32Z); Add MoneyFormatter utility: format(BigDecimal amount, String ccy) returns String formatted per UX-11 rules: KRW as integer, others as 2dp, with thousands comma separator; Write unit tests for MoneyFormatter: USD 10234.56 -> USD 10,234.56; KRW 45000 -> KRW 45,000; MNT 123456.78 -> MNT 123,456.78
**Deliverable:** TransactionSummaryDTO, TransactionSummaryMapper, and MoneyFormatter utility with unit tests
**Acceptance / logic checks:**
- MoneyFormatter.format(BigDecimal(45000), KRW) returns KRW 45,000 (no decimal places)
- MoneyFormatter.format(BigDecimal(10234.56), USD) returns USD 10,234.56
- MoneyFormatter.format(BigDecimal(0), USD) returns USD 0.00 (not blank or dash)
- txn_timestamp serialized as ISO-8601 UTC (ends with Z), e.g. 2026-06-01T00:14:32Z
- Negative amounts (for use in ledger contexts) return prefix with minus sign e.g. -USD 33.83
**Depends on:** 11.3-T01

### 11.3-T08 — Implement scheme dropdown population endpoint for filter UI  _(30 min)_
**Context:** WBS 11.3 - Partner Portal transaction history filter. The scheme dropdown in the filter panel must be populated with only the schemes the authenticated partner is mapped to (PRD-08 §4.1: Populated with schemes the partner is mapped to). This prevents exposing scheme names from other partners. Endpoint: GET /partner/v1/transactions/filter-options. Returns list of schemes for the current partner only.
**Steps:** Create GET /partner/v1/transactions/filter-options endpoint; Extract partner_id from JWT claims; Query the rules table (or partner_scheme_mapping view) for all scheme_id and scheme_name pairs where the partner has at least one active rule; Return JSON: { schemes: [{id, name}], directions: [DOMESTIC, INBOUND, OUTBOUND, HUB], statuses: [APPROVED, DECLINED, PENDING, CANCELLED, REFUNDED], payment_modes: [MPM, CPM] }; Ensure a partner with no active rules returns empty schemes array (not an error)
**Deliverable:** GET /partner/v1/transactions/filter-options endpoint
**Acceptance / logic checks:**
- Partner with rules for ZeroPay only returns schemes: [{id:zeropay, name:ZeroPay}]
- Partner with no rules returns schemes: []
- Response includes all 5 status values and both payment modes
- Partner A cannot see Partner B schemes (auth enforced by partner_id from JWT)
- HTTP 401 returned when no auth token provided
**Depends on:** 11.3-T05

### 11.3-T09 — Implement GET /partner/v1/transactions CSV export endpoint - core  _(50 min)_
**Context:** WBS 11.3 - Partner Portal CSV export. PRD-08 §4.4 rules: (1) Exports all rows matching current filter, not just current page; (2) Maximum 10,000 rows per request - larger ranges must be broken into sub-ranges; (3) CSV columns include all §4.2 columns PLUS: offer_rate, scheme_txn_ref, merchant_id, merchant_name, full hub_txn_ref; (4) Data restriction - NEVER include: m_a, m_b, cost_rate_coll, cost_rate_pay, collection_margin_usd, payout_margin_usd, or any GME internal revenue figure; (5) File name format: gmepay_txns_{partner_id}_{from_date}_{to_date}.csv.
**Steps:** Create GET /partner/v1/transactions/export endpoint accepting same filter params as the history list endpoint; Extract partner_id from JWT; validate filter same as list endpoint; If filter would return > 10,000 rows, return HTTP 422 with error: Export exceeds 10,000 row limit. Refine your date range.; Query all matching rows (no pagination) using TransactionHistoryRepository with page_size=10000; Stream response as text/csv with Content-Disposition: attachment; filename=gmepay_txns_{partner_id}_{from_date}_{to_date}.csv; Write CSV rows: txn_timestamp(UTC), hub_txn_ref(full), scheme_name, direction, target_payout, payout_ccy, collection_amount, collection_ccy, txn_status, payment_mode, offer_rate, scheme_txn_ref, merchant_id, merchant_name
**Deliverable:** GET /partner/v1/transactions/export endpoint returning CSV file
**Acceptance / logic checks:**
- CSV header row contains exactly the 14 specified columns (none more, none less)
- m_a, m_b, cost_rate_coll, cost_rate_pay, collection_margin_usd, payout_margin_usd are absent from CSV output (assert column headers do not contain these field names)
- Filter matching 10,001 rows returns HTTP 422 with correct error message
- Filter matching 50 rows returns HTTP 200 with Content-Disposition header containing gmepay_txns_{partner_id}
- File contains 50 data rows plus 1 header row = 51 lines total
**Depends on:** 11.3-T04, 11.3-T07

### 11.3-T10 — Implement CSV export file name generation and date formatting  _(20 min)_
**Context:** WBS 11.3 - Partner Portal CSV export. File name format per PRD-08 §4.4: gmepay_txns_{partner_id}_{from_date}_{to_date}.csv where dates are in YYYYMMDD format. If no date filter is applied, use the actual date range of returned records (min and max txn_timestamp dates). Example: gmepay_txns_sendmn_20260601_20260607.csv. partner_id in filename should use the partner slug (short identifier), not UUID.
**Steps:** Create CsvExportFilenameBuilder utility class with method build(String partnerSlug, LocalDate from, LocalDate to) -> String; Implement date formatting: YYYYMMDD pattern (e.g. 2026-06-01 -> 20260601); Handle edge case: if from_date equals to_date, still emit both in filename (e.g. gmepay_txns_sendmn_20260601_20260601.csv); Wire into the export endpoint to set Content-Disposition header using the built filename; Add unit tests for: normal range, same-day range, and verify UUID partner_id is mapped to slug
**Deliverable:** CsvExportFilenameBuilder utility with unit tests wired into export endpoint
**Acceptance / logic checks:**
- build(sendmn, 2026-06-01, 2026-06-07) returns gmepay_txns_sendmn_20260601_20260607.csv
- build(sendmn, 2026-06-01, 2026-06-01) returns gmepay_txns_sendmn_20260601_20260601.csv
- Content-Disposition header on export response matches expected filename pattern
- Partner slug (not UUID) is used in filename (e.g. sendmn not 3f4a-...)
**Depends on:** 11.3-T09

### 11.3-T11 — Implement CSV data restriction enforcement - blocked field unit tests  _(30 min)_
**Context:** WBS 11.3 - Partner Portal CSV export data restriction. PRD-08 §4.4 and §UC-10-02 prohibit these fields in partner-facing exports: m_a, m_b, cost_rate_coll, cost_rate_pay, collection_margin_usd, payout_margin_usd, and any GME internal revenue figures. This restriction is a compliance requirement and must be unit-tested independently of the export endpoint to prevent accidental inclusion via mapper changes.
**Steps:** Create CsvExportColumnGuardTest unit test class; Define the permitted column set as a constant: ALLOWED_COLUMNS = [txn_timestamp, hub_txn_ref, scheme_name, direction, target_payout, payout_ccy, collection_amount, collection_ccy, txn_status, payment_mode, offer_rate, scheme_txn_ref, merchant_id, merchant_name]; Define the prohibited column set: PROHIBITED_COLUMNS = [m_a, m_b, cost_rate_coll, cost_rate_pay, collection_margin_usd, payout_margin_usd, collection_margin, payout_margin, revenue]; Write test: generate a CSV export with a known transaction containing all rate-engine fields populated, parse the CSV header, assert none of PROHIBITED_COLUMNS appear; Write test: assert all of ALLOWED_COLUMNS appear in the CSV header
**Deliverable:** CsvExportColumnGuardTest unit test class that will fail if a prohibited field is accidentally added
**Acceptance / logic checks:**
- Test fails if m_a is added to the CSV column list (sentinel test verifies guard is active)
- Test passes with current allowed column list of exactly 14 columns
- Test checks case-insensitively to prevent header renaming bypasses (e.g. M_A or margin_a would also fail)
- A transaction with m_a=1.5, m_b=1.0 in DB is exported and CSV row does not contain those values
**Depends on:** 11.3-T09

### 11.3-T12 — Implement pagination response envelope and metadata  _(25 min)_
**Context:** WBS 11.3 - Partner Portal transaction history. PRD-08 §4.3: Default page size 50 rows; options 25/50/100; navigation previous/next/jump-to-page; total result count displayed. API response meta object must include: total_count (int), page (int, 1-based), page_size (int), total_pages (int, ceil(total_count/page_size)), has_previous (bool), has_next (bool). This meta object is consumed by the frontend pagination controls.
**Steps:** Create PaginationMeta DTO with fields: total_count, page, page_size, total_pages, has_previous, has_next; Create PaginatedResponse<T> generic wrapper with fields: data (List<T>) and meta (PaginationMeta); Add static factory: PaginationMeta.from(Page<T> page) computing total_pages as ceil(total_count / page_size); Ensure total_pages is 1 when total_count is 0 (avoid division-by-zero or returning 0 total_pages); Write unit tests for PaginationMeta.from() with representative inputs
**Deliverable:** PaginationMeta DTO, PaginatedResponse wrapper, and unit tests
**Acceptance / logic checks:**
- total_count=234, page=1, page_size=50 -> total_pages=5, has_next=true, has_previous=false
- total_count=234, page=5, page_size=50 -> total_pages=5, has_next=false, has_previous=true
- total_count=0, page=1, page_size=50 -> total_pages=1, has_next=false, has_previous=false
- total_count=100, page=2, page_size=50 -> total_pages=2, has_next=false, has_previous=true
- total_count=51, page=1, page_size=50 -> total_pages=2, has_next=true, has_previous=false
**Depends on:** 11.3-T05

### 11.3-T13 — Add sorting support to transaction history query  _(30 min)_
**Context:** WBS 11.3 - Partner Portal transaction history. PRD-08 §4.2: List is sortable by Timestamp, Payout Amount, Partner (not applicable for partner portal - partner is fixed), Status. Sort parameters: sort_by (enum: TIMESTAMP|PAYOUT_AMOUNT|STATUS) and sort_order (ASC|DESC, default DESC). When sort_by=TIMESTAMP, sort on txn_timestamp. When sort_by=PAYOUT_AMOUNT, sort on target_payout. When sort_by=STATUS, sort on txn_status (alphabetical). Default sort: sort_by=TIMESTAMP, sort_order=DESC.
**Steps:** Extend TransactionHistoryFilterDTO with sort_by (default TIMESTAMP) and sort_order (default DESC) fields; Map SortBy enum values to column names: TIMESTAMP->txn_timestamp, PAYOUT_AMOUNT->target_payout, STATUS->txn_status; Pass sort specification to repository via Spring Pageable or equivalent ORDER BY clause; Validate sort_by value is a recognized enum; return 400 for unknown values; Write unit tests: sort by PAYOUT_AMOUNT DESC returns higher-value transactions first; sort by STATUS ASC returns APPROVED before DECLINED alphabetically
**Deliverable:** Sorting support integrated into transaction history filter and repository query
**Acceptance / logic checks:**
- sort_by=PAYOUT_AMOUNT&sort_order=DESC returns transactions ordered target_payout descending
- sort_by=STATUS&sort_order=ASC returns APPROVED before DECLINED (alphabetical order)
- sort_by=TIMESTAMP&sort_order=ASC returns oldest transactions first
- Invalid sort_by value (e.g. sort_by=MERCHANT) returns HTTP 400
- Default response (no sort params) is ordered by txn_timestamp DESC
**Depends on:** 11.3-T04, 11.3-T05

### 11.3-T14 — Implement empty-state and boundary-value handling in history endpoint  _(30 min)_
**Context:** WBS 11.3 - Partner Portal transaction history edge cases. Spec requires specific empty-state behaviour per UX-11 §2.9: no transactions found returns HTTP 200 with data:[] (not 404). Additional edge cases: page number beyond total_pages (e.g. page=100 when total_pages=3) must return empty data with correct meta, not an error. A partner with no transactions at all must return HTTP 200 with total_count=0.
**Steps:** Add check in controller: if requested page > total_pages and total_count > 0, return HTTP 200 with data:[] and meta reflecting the out-of-range page; Ensure partner with no transactions returns HTTP 200 with data:[], total_count:0, total_pages:1; Write integration test: seed partner with 0 transactions, call endpoint, assert HTTP 200 and empty data; Write integration test: seed partner with 50 transactions, request page=10 (page_size=50), assert HTTP 200 with empty data and meta.page=10, meta.total_count=50; Ensure HTTP 404 is never returned for empty results (only for non-existent resources like wrong partner_id in URL)
**Deliverable:** Edge case handling for empty results and out-of-range pagination, with integration tests
**Acceptance / logic checks:**
- Partner with 0 transactions: HTTP 200, data:[], meta.total_count:0
- Page 10 requested when only 1 page exists: HTTP 200, data:[], meta.total_pages:1
- Filter that matches no transactions (e.g. status=REFUNDED with no refunds): HTTP 200, data:[], meta.total_count:0
- HTTP 404 is never returned by the history list endpoint for empty results
- Response always includes meta object even when data is empty
**Depends on:** 11.3-T05, 11.3-T12

### 11.3-T15 — Implement amount-range filter in partner settlement currency  _(25 min)_
**Context:** WBS 11.3 - Partner Portal transaction history filter. PRD-08 §4.1: Amount range filter (min/max numeric) is in the partner's settlement currency (collection_ccy). The filter applies to collection_amount column. Validation rules: amount_min >= 0, amount_max >= 0, if both provided then amount_min <= amount_max. A partner with settlement currency KRW filtering by amount must use integer values (no decimals). A partner with settlement currency USD may use 2 decimal places.
**Steps:** Ensure TransactionHistoryFilterDTO amount_min and amount_max are typed as BigDecimal (not double); Add validation in TransactionFilterValidator: if both provided, amount_min <= amount_max; both must be >= 0; In repository query, apply predicate: collection_amount >= amount_min AND collection_amount <= amount_max when values are provided; Add validation note that currency precision is the responsibility of the caller (KRW callers should not send decimals; the DB stores DECIMAL(20,4) so comparison still works); Write unit test: amount_min=100.00, amount_max=50.00 returns validation error; amount_min=50.00, amount_max=100.00 is valid
**Deliverable:** Amount range filter predicates in repository query with validation unit tests
**Acceptance / logic checks:**
- amount_min=50.00 and amount_max=100.00 returns only transactions where 50.00 <= collection_amount <= 100.00
- amount_min=100.00 and amount_max=50.00 returns HTTP 400 validation error
- amount_min=-1.00 returns HTTP 400 validation error (negative amount not allowed)
- amount_min=0 returns all transactions (including 0-amount edge case)
- Only amount_min provided (no max): returns transactions with collection_amount >= amount_min with no upper bound
**Depends on:** 11.3-T02, 11.3-T04

### 11.3-T16 — Implement multi-select status filter with IN clause  _(25 min)_
**Context:** WBS 11.3 - Partner Portal transaction history filter. PRD-08 §4.1: Status filter is multi-select; values are APPROVED, DECLINED, PENDING, CANCELLED, REFUNDED. A partner may select multiple statuses simultaneously (e.g. DECLINED,PENDING). The query must use an IN clause, not separate OR conditions. When no status filter is provided, all statuses are returned. Status values come from TxnStatus enum per DAT-03.
**Steps:** Ensure TransactionHistoryFilterDTO.status is typed as Set<TxnStatus> (not String); In repository query builder, add IN predicate when status set is non-null and non-empty: WHERE txn_status IN (:statuses); When status set is null or empty, do not add status predicate (return all statuses); Write unit test: filter with status={DECLINED, PENDING} returns only transactions with those statuses; Write unit test: filter with empty status set returns transactions of all statuses
**Deliverable:** Multi-status IN clause filter integrated into repository query with unit tests
**Acceptance / logic checks:**
- Filter status=[DECLINED] returns only DECLINED transactions
- Filter status=[APPROVED, DECLINED] returns APPROVED and DECLINED but not PENDING, CANCELLED, or REFUNDED
- Filter with status=[] (empty set) returns all transactions regardless of status
- Filter with unknown status value (e.g. status=UNKNOWN) returns HTTP 400
- SQL generated includes IN clause, not multiple OR conditions (verify via query log in test)
**Depends on:** 11.3-T04

### 11.3-T17 — Implement CSV streaming for large exports to avoid memory overflow  _(45 min)_
**Context:** WBS 11.3 - Partner Portal CSV export. PRD-08 §4.4: max 10,000 rows per export; P95 < 30 seconds (PRD-08 §10.1). To avoid loading 10,000 transaction objects into heap at once, use streaming: ResponseBodyEmitter, StreamingResponseBody (Spring), or database cursor-backed streaming. Write CSV rows directly to the output stream as they are fetched. Do not buffer the entire result set.
**Steps:** Modify the export endpoint to use StreamingResponseBody (Spring) or equivalent to write CSV rows incrementally; Use a scrollable/cursor query in the repository (e.g. JPA ScrollableResults or Stream<Transaction>) to avoid loading all rows into memory; Write each CSV row to the OutputStream as it is produced; flush periodically (every 500 rows); Set response Content-Type: text/csv; charset=UTF-8 and Content-Disposition header before streaming begins; Write a performance test or comment confirming that a 10,000-row export does not require more than 50MB heap (row-by-row streaming)
**Deliverable:** Streaming CSV export implementation that does not buffer entire result in memory
**Acceptance / logic checks:**
- Export of 10,000 rows completes without OutOfMemoryError under default JVM heap settings
- First CSV row begins appearing in response within 2 seconds of request (streaming, not wait-then-dump)
- CSV output is well-formed (no truncated rows) even for the maximum 10,000-row export
- Content-Type header is text/csv; charset=UTF-8
- Connection close or client abort during streaming does not cause a server-side exception that is unhandled
**Depends on:** 11.3-T09

### 11.3-T18 — Add performance index for CSV export query (no-pagination full-scan path)  _(40 min)_
**Context:** WBS 11.3 - Partner Portal CSV export. The export query fetches up to 10,000 rows without pagination, ordered by txn_timestamp DESC for the given partner and filter. This is a different access pattern from the paginated list query. A covering index or secondary index that supports the full-scan export without reading the entire table is required to meet the 30-second P95 SLA.
**Steps:** Analyse the CSV export query execution plan (EXPLAIN ANALYZE) for a dataset of 500,000 rows filtered by partner_id and date range; If existing indexes from T03 are insufficient (e.g. seq scan on large date ranges), add a partial or covering index in a new migration: V11_3_002__txn_export_index.sql; Consider: idx_txn_partner_timestamp_covering ON transactions (partner_id, txn_timestamp DESC) INCLUDE (hub_txn_ref, scheme_id, direction, target_payout, payout_ccy, collection_amount, collection_ccy, txn_status, payment_mode, offer_rate, scheme_txn_ref, merchant_id, merchant_name); Document in migration comments why the INCLUDE columns were chosen; Verify the covering index is used by the export query via EXPLAIN ANALYZE
**Deliverable:** Flyway migration V11_3_002__txn_export_index.sql with covering index for export query
**Acceptance / logic checks:**
- EXPLAIN ANALYZE on export query for partner with 500k rows uses the covering index (Index Only Scan or Bitmap Index Scan)
- Export of 10,000 rows from a 500k-row table completes in < 30 seconds in local perf test
- Migration applies cleanly without errors
- No existing queries are regressed (check EXPLAIN plans for paginated list query still use T03 index)
**Depends on:** 11.3-T03, 11.3-T09, 11.3-T17

### 11.3-T19 — Implement transaction history frontend filter panel component  _(50 min)_
**Context:** WBS 11.3 - Partner Portal frontend. UX-11 §5.2 wireframe shows: [Date from] [Date to] [Scheme dropdown] [Status multi-select] [Mode dropdown] [Direction dropdown] [Min amount] [Max amount] [Search button] [Export button]. The component must: (1) pre-populate Scheme dropdown from GET /partner/v1/transactions/filter-options; (2) default date range to last 7 days; (3) validate date range <= 90 days client-side (with error message: Date range cannot exceed 90 days per UX-11 §7.2); (4) show error inline below the date fields on validation failure; (5) disable Search and Export buttons while fetch is in flight.
**Steps:** Create TransactionFilterPanel React component accepting onSearch(filter) and onExport(filter) callbacks; Implement date pickers for date_from and date_to with default values (today-6d, today); Add client-side validation: if date range > 90 days, show inline error below date fields; disable Search button; Fetch scheme options from /partner/v1/transactions/filter-options on component mount; populate Scheme dropdown; Implement Status multi-select checkboxes or multi-select component with all 5 options; Disable Search and Export buttons with spinner during active fetch; re-enable on completion
**Deliverable:** TransactionFilterPanel React component
**Acceptance / logic checks:**
- Date range of 91 days shows inline error Date range cannot exceed 90 days below the date fields
- Search button is disabled while fetch is in progress (aria-disabled=true, cursor:not-allowed)
- Scheme dropdown is populated after mount with values from /filter-options endpoint
- Clearing all filters and clicking Search sends a request with no filter params (returns all)
- Status multi-select allows selecting APPROVED and DECLINED simultaneously
**Depends on:** 11.3-T08

### 11.3-T20 — Implement transaction history table component with sortable columns  _(45 min)_
**Context:** WBS 11.3 - Partner Portal frontend. UX-11 §5.2 and PRD-08 §4.2 define the transaction list table. Columns: Time (KST), Transaction ID (truncated hub_txn_ref first 12 chars), Scheme, Direction, Payout Amount, Collection Amount, Status (badge), Mode. Sortable by Timestamp, Payout Amount, Status. Money amounts formatted per UX-11 §2.8 (currency code prefix, KRW integer, others 2dp, comma thousands). Status badges use colour tokens from UX-11 §2.7. Clicking a Transaction ID navigates to the detail page.
**Steps:** Create TransactionHistoryTable React component accepting transactions (array) and onSort(sortBy, sortOrder) callback; Render columns per spec; truncate hub_txn_ref to 12 chars for display column, use full value for navigation link; Implement sort indicators (up/down arrows) on Timestamp, Payout Amount, Status columns; highlight active sort column; Render StatusBadge component for txn_status using correct colour tokens: APPROVED=#DEF7EC/#057A55, DECLINED=#FDE8E8/#E02424, PENDING=#FEF3C7/#D97706, CANCELLED=#E5E7EB/#4B5563, REFUNDED=#E0F2FE/#0369A1; Format money amounts: use MoneyFormatter logic (KRW no decimals, USD 2dp, comma thousands); Clicking Transaction ID link navigates to /{partner_slug}/transactions/{hub_txn_ref} (full ref in URL)
**Deliverable:** TransactionHistoryTable React component with sorting and status badges
**Acceptance / logic checks:**
- hub_txn_ref HUB-20260601-00123 displays as HUB-20260601 in the table cell (12 chars) but full ref in the URL href
- APPROVED status renders with background #DEF7EC and text color #057A55
- KRW 45000 renders as KRW 45,000 (no decimal places)
- USD 33.83 renders as USD 33.83
- Clicking Timestamp column header calls onSort(TIMESTAMP, ASC) then onSort(TIMESTAMP, DESC) on second click (toggle)
**Depends on:** 11.3-T07, 11.3-T19

### 11.3-T21 — Implement pagination controls component for transaction history  _(35 min)_
**Context:** WBS 11.3 - Partner Portal frontend. PRD-08 §4.3: Navigation includes previous/next/jump-to-page; total result count displayed; page size selector with options 25, 50 (default), 100. The pagination controls must show: Showing X-Y of Z records; Previous button (disabled on page 1); page number display; Next button (disabled on last page); page size dropdown. Changing page size resets to page 1.
**Steps:** Create PaginationControls React component accepting meta ({total_count, page, page_size, total_pages, has_previous, has_next}) and onPageChange(page, page_size) callback; Render Showing {start}-{end} of {total_count} label (e.g. Showing 1-50 of 234); Render Previous button; disable (aria-disabled=true) when meta.has_previous is false; Render Next button; disable when meta.has_next is false; Render page size dropdown with options [25, 50, 100]; changing value calls onPageChange(1, newPageSize); Write unit tests: page 1 of 5 - Previous disabled, Next enabled; page 5 of 5 - Previous enabled, Next disabled; total 0 - showing 0-0 of 0
**Deliverable:** PaginationControls React component with unit tests
**Acceptance / logic checks:**
- Page 1 of 5: Previous button has aria-disabled=true, Next has aria-disabled=false
- Page 5 of 5: Previous has aria-disabled=false, Next has aria-disabled=true
- Showing 51-100 of 234 label renders correctly for page=2, page_size=50, total_count=234
- Changing page size from 50 to 100 triggers onPageChange(1, 100) (reset to page 1)
- total_count=0 renders Showing 0-0 of 0 without error
**Depends on:** 11.3-T12

### 11.3-T22 — Implement Export CSV button and download trigger in history page  _(40 min)_
**Context:** WBS 11.3 - Partner Portal frontend. The Export button on the transaction history page (UX-11 §5.2, UX-11 §6.4 step 10) triggers a CSV download of all rows matching the current filter (not just the current page). On click: (1) validate filter (same client-side validation as Search); (2) call GET /partner/v1/transactions/export with current filter params; (3) download the response as a file using the Content-Disposition filename from the response header; (4) if HTTP 422 returned (> 10,000 rows), show a warning toast: Export exceeds 10,000 rows. Please narrow your date range.
**Steps:** Add Export button to TransactionFilterPanel or TransactionHistoryPage, positioned alongside Search per wireframe; On click, run same client-side filter validation; abort if invalid; Make GET request to /partner/v1/transactions/export with current filter state as query params; On HTTP 200: read response as Blob, extract filename from Content-Disposition header, trigger browser download via URL.createObjectURL + anchor click; On HTTP 422: show error toast with message: Export exceeds 10,000 rows. Please narrow your date range.; Disable Export button and show loading spinner while export request is in flight
**Deliverable:** Export CSV button integrated into TransactionHistoryPage with download and error handling
**Acceptance / logic checks:**
- Clicking Export with a valid filter triggers a file download with filename matching gmepay_txns_{partner_id}_{from}_{to}.csv pattern
- HTTP 422 response shows toast: Export exceeds 10,000 rows. Please narrow your date range.
- Export button is disabled (spinner shown) while download is in progress
- Clicking Export with invalid date range (> 90 days) shows the date range validation error and does not make the API call
- Downloaded CSV file opens in a spreadsheet application without error
**Depends on:** 11.3-T09, 11.3-T19

### 11.3-T23 — Implement empty, loading, and error states for transaction history table  _(35 min)_
**Context:** WBS 11.3 - Partner Portal frontend. UX-11 §2.9 specifies three states: (1) Loading: skeleton shimmer matching table row structure, minimum display 200ms to avoid flash; (2) Empty: icon + message No transactions found / Try adjusting your filters; (3) Error: icon + Could not load transactions + error code + Retry button. The Retry button triggers the same fetch. Error code shown is human-readable (not a stack trace). Status 401 on fetch must redirect to login page.
**Steps:** Add loading state to TransactionHistoryPage: when fetch is in flight, render SkeletonTable with 5 shimmer rows matching table structure; enforce minimum 200ms display duration; Add empty state: when data=[] and !loading, render EmptyState component with search icon, text No transactions found, subtext Try adjusting your filters; Add error state: when fetch fails, render ErrorState component with warning icon, text Could not load transactions, error code (e.g. NETWORK_ERROR or server-returned code), and Retry button; On Retry click, re-trigger the same fetch with current filter; On 401 response, redirect to /login; do not show the error state
**Deliverable:** Loading, empty, and error states for TransactionHistoryPage with 200ms skeleton minimum
**Acceptance / logic checks:**
- Table shows 5 skeleton shimmer rows during fetch; skeleton disappears after data loads
- Empty results show No transactions found message with Try adjusting your filters subtext (not an error state)
- Simulated network failure shows Could not load transactions with error code and Retry button
- Retry button triggers a new API call with the same filter parameters
- 401 response redirects to /login page, not to the error state
**Depends on:** 11.3-T20

### 11.3-T24 — Implement TransactionHistoryPage integration - wire filter, table, pagination  _(45 min)_
**Context:** WBS 11.3 - Partner Portal frontend. The TransactionHistoryPage assembles TransactionFilterPanel, TransactionHistoryTable, and PaginationControls into a working page. State management: filter state, current page, page size, sort state, data, loading, error. On mount: fetch with default filter (last 7 days, page 1, page_size 50). On filter submit: reset to page 1, fetch new results. On sort change: reset to page 1, fetch. On pagination: fetch new page with same filter.
**Steps:** Create TransactionHistoryPage component managing state: filter, page, page_size, sort_by, sort_order, transactions, meta, loading, error; On mount, auto-fetch with default 7-day date range, page=1, page_size=50, sort_by=TIMESTAMP, sort_order=DESC; Connect TransactionFilterPanel.onSearch callback: update filter state, reset page to 1, trigger fetch; Connect TransactionHistoryTable.onSort callback: update sort state, reset page to 1, trigger fetch; Connect PaginationControls.onPageChange callback: update page/page_size state, trigger fetch; Pass loading, error, empty states down to table component
**Deliverable:** TransactionHistoryPage integrating all sub-components with correct state management
**Acceptance / logic checks:**
- Page load auto-fetches last-7-day transactions without user interaction
- Submitting a new filter resets to page 1 (not retains previous page)
- Changing sort order resets to page 1 and re-fetches
- Changing page size to 100 resets to page 1 and re-fetches with page_size=100
- Navigating to page 3 and then changing the filter resets to page 1 (not stays on page 3)
**Depends on:** 11.3-T19, 11.3-T20, 11.3-T21, 11.3-T23

### 11.3-T25 — Unit tests - TransactionHistoryRepository filter combinations  _(50 min)_
**Context:** WBS 11.3 - Unit tests for the filter query logic. The repository must correctly handle 6 filter dimensions in combination. Test vectors must cover: single filter, compound filter, no filter, and edge cases. Uses an embedded test DB (H2 or Testcontainers PostgreSQL) seeded with known data.
**Steps:** Create TransactionHistoryRepositoryTest with Testcontainers (PostgreSQL) or H2 embedded DB; Seed 20 transactions across 2 partners, 2 schemes, 3 statuses (APPROVED/DECLINED/PENDING), 2 modes (MPM/CPM), varying amounts and timestamps; Test 1 (date range): filter date_from=2026-06-01, date_to=2026-06-01 returns only transactions on that day; Test 2 (scheme): filter scheme_id=zeropay returns only ZeroPay transactions; Test 3 (multi-status): filter status=[DECLINED, PENDING] returns only those two statuses; Test 4 (compound): filter scheme_id=zeropay + status=[APPROVED] + amount_min=10.00 returns only ZeroPay APPROVED transactions above 10; Test 5 (empty result): filter status=[REFUNDED] with no refunds in seed returns empty list with total_count=0
**Deliverable:** TransactionHistoryRepositoryTest with 5+ parameterised test cases covering filter combinations
**Acceptance / logic checks:**
- Test 1: date filter returns exactly the seeded transactions on 2026-06-01 and no others
- Test 3: status IN [DECLINED, PENDING] returns correct count matching seeded data
- Test 4: compound filter returns only the intersection of all filter conditions
- Test 5: empty result returns Page with total_count=0 and empty content list
- All tests pass with the partner_id isolation enforced (seeded partner B data never appears in partner A results)
**Depends on:** 11.3-T04, 11.3-T16

### 11.3-T26 — Unit tests - CSV export field restriction and column completeness  _(35 min)_
**Context:** WBS 11.3 - Unit tests for CSV export correctness. Two concerns: (1) all 14 allowed columns are present; (2) no prohibited field (m_a, m_b, cost_rate_coll, cost_rate_pay, collection_margin_usd, payout_margin_usd) appears. Test uses a known transaction entity with all rate-engine fields populated to ensure the mapper does not accidentally pass through internal values.
**Steps:** Create CsvExportServiceTest unit test class (no DB required; mock repository); Build a test Transaction entity with all fields set including m_a=1.5, m_b=1.0, cost_rate_coll=1350.42, cost_rate_pay=1.0, collection_margin_usd=0.50, payout_margin_usd=0.43; Call the CSV export mapper/writer with this entity and capture the output string; Parse the CSV header row; assert it contains exactly the 14 allowed columns; Parse the data row; assert none of the prohibited field values (1.5, 1.0 for m_a/m_b) appear as isolated column values
**Deliverable:** CsvExportServiceTest unit tests for field restriction and completeness
**Acceptance / logic checks:**
- CSV header contains exactly 14 columns: txn_timestamp, hub_txn_ref, scheme_name, direction, target_payout, payout_ccy, collection_amount, collection_ccy, txn_status, payment_mode, offer_rate, scheme_txn_ref, merchant_id, merchant_name
- CSV header does NOT contain m_a, m_b, cost_rate_coll, cost_rate_pay, collection_margin_usd, or payout_margin_usd
- Data row for the test transaction does not contain the string 1.5 in a column that could correspond to m_a
- offer_rate is present in data row with correct 6-significant-figure value
- hub_txn_ref in CSV is the full reference (not truncated to 12 chars)
**Depends on:** 11.3-T09, 11.3-T11

### 11.3-T27 — Unit tests - pagination metadata computation edge cases  _(20 min)_
**Context:** WBS 11.3 - Unit tests for pagination metadata. PaginationMeta.from() must handle all boundary conditions without arithmetic errors: zero results, exactly one page, exactly page_size results, one result over a page boundary, and last page with partial results.
**Steps:** Create PaginationMetaTest unit test class; Test vector 1: total=0, page=1, size=50 -> total_pages=1, has_prev=false, has_next=false; Test vector 2: total=50, page=1, size=50 -> total_pages=1, has_prev=false, has_next=false; Test vector 3: total=51, page=1, size=50 -> total_pages=2, has_next=true; Test vector 4: total=51, page=2, size=50 -> total_pages=2, has_prev=true, has_next=false; Test vector 5: total=100, page=2, size=25 -> total_pages=4, has_prev=true, has_next=true; Test vector 6: total=1, page=1, size=100 -> total_pages=1, has_prev=false, has_next=false
**Deliverable:** PaginationMetaTest with 6 test vectors covering all boundary conditions
**Acceptance / logic checks:**
- Vector 1 (total=0): total_pages=1, no arithmetic exception
- Vector 3 (51 rows, page 1): has_next=true, total_pages=2
- Vector 4 (51 rows, page 2 of 2): has_prev=true, has_next=false
- Vector 6 (1 row, size 100): total_pages=1, has_next=false
- No division by zero exception for any input
**Depends on:** 11.3-T12

### 11.3-T28 — Unit tests - date range validation boundary vectors  _(25 min)_
**Context:** WBS 11.3 - Unit tests for date range validation. TransactionFilterValidator must enforce: date_from <= date_to, range <= 90 calendar days. Boundary vectors: exactly 90 days (valid), exactly 91 days (invalid), same day (valid), date_from > date_to (invalid), only date_from provided (valid), only date_to provided (valid), neither provided (valid - defaults applied).
**Steps:** Create TransactionFilterValidatorTest unit test class; Vector 1: date_from=2026-01-01, date_to=2026-04-01 (90 days) -> valid; Vector 2: date_from=2026-01-01, date_to=2026-04-02 (91 days) -> invalid, error contains 90 days; Vector 3: date_from=2026-06-05, date_to=2026-06-05 (same day) -> valid; Vector 4: date_from=2026-06-05, date_to=2026-06-01 (from > to) -> invalid; Vector 5: only date_from=2026-06-01 provided -> valid (to defaults to today or from+6); Vector 6: neither date provided -> valid (defaults to last 7 days)
**Deliverable:** TransactionFilterValidatorTest with 6 test vectors
**Acceptance / logic checks:**
- Vector 1 (90 days): validate() returns no errors
- Vector 2 (91 days): validate() returns error with message Date range cannot exceed 90 days
- Vector 3 (same day): validate() returns no errors
- Vector 4 (from > to): validate() returns error
- Vector 5 (only from): validate() returns no errors
- Vector 6 (no dates): validate() returns no errors and defaults are applied
**Depends on:** 11.3-T02

### 11.3-T29 — Unit tests - money formatting for all supported currencies  _(20 min)_
**Context:** WBS 11.3 - Unit tests for MoneyFormatter. UX-11 §2.8 rules: KRW has 0 decimal places; other currencies 2 decimal places; comma thousands separator; currency code precedes amount; negative amounts use minus prefix; zero renders as currency+0.00 (not blank). Test vectors must cover KRW, USD, MNT, and a zero-amount case.
**Steps:** Create MoneyFormatterTest unit test class; Test 1: format(BigDecimal(45000), KRW) -> KRW 45,000; Test 2: format(BigDecimal(10234.56), USD) -> USD 10,234.56; Test 3: format(BigDecimal(123456.78), MNT) -> MNT 123,456.78; Test 4: format(BigDecimal(0), USD) -> USD 0.00; Test 5: format(BigDecimal(-33.83), USD) -> -USD 33.83 (for debit display in ledger); Test 6: format(BigDecimal(1000000), KRW) -> KRW 1,000,000 (millions separator); Test 7: format(BigDecimal(0.50), USD) -> USD 0.50 (sub-dollar with leading zero)
**Deliverable:** MoneyFormatterTest with 7 test vectors
**Acceptance / logic checks:**
- KRW 45000 -> KRW 45,000 (no decimal, comma thousands)
- USD 0 -> USD 0.00 (not USD 0 or blank)
- MNT 123456.78 -> MNT 123,456.78
- USD -33.83 -> -USD 33.83
- USD 0.50 -> USD 0.50 (correct sub-unit rendering)
- KRW 1000000 -> KRW 1,000,000
**Depends on:** 11.3-T07

### 11.3-T30 — API contract test for GET /partner/v1/transactions against OpenAPI spec  _(40 min)_
**Context:** WBS 11.3 - Contract tests. The GET /partner/v1/transactions endpoint must conform to the OpenAPI spec (openapi/partner-api.yaml). Contract tests validate: (1) query parameter schema matches spec; (2) response JSON schema matches spec (data array items have required fields, meta has required fields); (3) HTTP 400 returned for invalid input matches error schema; (4) HTTP 401 returned for missing/invalid auth token. Use schemathesis, Dredd, or equivalent contract test framework per QA-12 §2.2.3.
**Steps:** Locate or create the OpenAPI path entry for GET /partner/v1/transactions and GET /partner/v1/transactions/export in openapi/partner-api.yaml; Run schemathesis or equivalent against a live test server seeded with fixture data; Assert all query parameters are documented with correct types and constraints (date strings, enum values, integer ranges); Assert response 200 schema: data items contain hub_txn_ref, txn_timestamp, scheme_name, direction, target_payout, payout_ccy, collection_amount, collection_ccy, txn_status, payment_mode; meta contains total_count, page, page_size, total_pages; Assert HTTP 401 schema returned when Authorization header is absent; Assert HTTP 400 schema returned for invalid date range (e.g. date_to - date_from > 90)
**Deliverable:** OpenAPI contract tests for history and export endpoints passing in CI
**Acceptance / logic checks:**
- All 200 response fields listed in spec are present in live endpoint response
- No extra fields (internal fields like m_a) appear in the response schema
- HTTP 401 matches error schema when auth token missing
- HTTP 400 matches error schema for invalid date range input
- Contract test suite runs in CI and fails the build on schema mismatch
**Depends on:** 11.3-T05, 11.3-T09

### 11.3-T31 — Performance test: transaction history query P95 < 3 seconds for 1,000 rows  _(50 min)_
**Context:** WBS 11.3 - Performance acceptance. PRD-08 §10.1: Transaction history query P95 < 3 seconds for up to 1,000 rows. CSV export (up to 10,000 rows) < 30 seconds. This ticket creates a repeatable performance test using a seeded dataset of 500,000 transactions for a single partner to simulate production load.
**Steps:** Create a DB seed script that inserts 500,000 transactions for partner sendmn across 90 days with random statuses, amounts, and schemes; Run GET /partner/v1/transactions?date_from=2026-03-07&date_to=2026-06-05&page_size=50 (90-day window) and record P95 latency over 10 runs; Assert P95 < 3000ms for the list query; Run GET /partner/v1/transactions/export?date_from=2026-05-06&date_to=2026-06-05 (10,000-row export scenario); Assert export completes in < 30000ms; Record EXPLAIN ANALYZE output and attach to test as comments for future reference
**Deliverable:** Performance test script and result assertions for history query and CSV export
**Acceptance / logic checks:**
- List query (500k partner rows, 90-day window) returns in < 3 seconds P95
- CSV export of 10,000 rows completes in < 30 seconds
- EXPLAIN ANALYZE confirms index usage (not seq scan) for both queries
- Tests are repeatable (idempotent seed, deterministic filter)
- Results are logged to CI output for baseline tracking
**Depends on:** 11.3-T03, 11.3-T05, 11.3-T09, 11.3-T18

### 11.3-T32 — Document GET /partner/v1/transactions and /export in OpenAPI spec  _(40 min)_
**Context:** WBS 11.3 - API documentation. The OpenAPI spec (openapi/partner-api.yaml) must include complete entries for both the history list and CSV export endpoints. Entries must include: all query parameters with types, constraints, and defaults; response schemas for 200, 400, 401, 422; example request/response pairs; the data restriction note (m_a/m_b etc. never included in export). Developers at partner organisations use this spec directly for integration.
**Steps:** Open openapi/partner-api.yaml and add or update the path /partner/v1/transactions with GET operation; Document all query parameters: date_from (date), date_to (date), scheme_id (string), direction (enum), status (array of enum), amount_min (number), amount_max (number), payment_mode (enum), page (int, default 1, min 1), page_size (int, enum [25,50,100], default 50), sort_by (enum TIMESTAMP|PAYOUT_AMOUNT|STATUS), sort_order (enum ASC|DESC, default DESC); Add response schemas: 200 (PaginatedTransactionResponse), 400 (ValidationErrorResponse), 401 (AuthErrorResponse); Add or update /partner/v1/transactions/export with GET operation; document same filter params; document 200 (text/csv), 401, 422 (row limit exceeded); add x-data-restriction note in description; Add at least one example for the 200 list response and one example for the 200 CSV export response
**Deliverable:** Updated openapi/partner-api.yaml with complete history and export endpoint documentation
**Acceptance / logic checks:**
- openapi-lint or swagger-validator reports no errors on the updated spec
- All 13 query parameters are documented with correct type and constraints
- 422 response is documented for the export endpoint with message body schema
- Description of export endpoint explicitly states m_a, m_b, cost_rate_coll, cost_rate_pay, collection_margin_usd, payout_margin_usd are excluded from the CSV
- A developer reading the spec can determine the filename format (gmepay_txns_{partner_id}_{from_date}_{to_date}.csv) from the description or example
**Depends on:** 11.3-T05, 11.3-T09


## WBS 11.4 — Transaction detail view
### 11.4-T01 — Define TransactionDetailResponse DTO with all partner-visible fields  _(30 min)_
**Context:** WBS 11.4: Partner Portal transaction detail view. The detail page is accessible at /{partner_slug}/transactions/{hub_txn_ref}. Partner-visible amount fields (PRD-08 §5.1): target_payout, payout_ccy, collection_amount, collection_ccy, service_charge, send_amount, offer_rate (= offer_rate_coll from DB), cross_rate. Locked pool fields for cross-border only (§5.2): collection_usd, payout_usd_cost, rate_locked_at (= committed_at). Internal fields NEVER exposed: m_a, m_b, cost_rate_coll, cost_rate_pay, collection_margin_usd, payout_margin_usd. Scheme reference fields (§5.4): scheme_txn_ref, scheme_name, merchant_id, merchant_name, payment_mode. Identity fields: hub_txn_ref, partner_txn_ref, direction, txn_timestamp (UTC + KST offset). Status field. is_same_ccy_shortcircuit flag drives conditional display.
**Steps:** Create TransactionDetailResponse Java record/class with all partner-visible fields listed in context, grouping into nested objects: AmountFields, LockedPoolFields (nullable for same-ccy), SchemeReference, EventTrailStep list; Mark LockedPoolFields as nullable; add is_same_ccy_shortcircuit boolean; Annotate with Jackson @JsonProperty for exact API naming (e.g. offer_rate not offer_rate_coll); Add Javadoc listing which source DB column each field maps to; Add OpenAPI annotations (@Schema) with field descriptions
**Deliverable:** TransactionDetailResponse DTO class with nested LockedPoolFields (nullable), AmountFields, SchemeReference, and List<EventTrailStep> fields, with Jackson and OpenAPI annotations
**Acceptance / logic checks:**
- LockedPoolFields is null when is_same_ccy_shortcircuit = true
- Fields m_a, m_b, cost_rate_coll, cost_rate_pay, collection_margin_usd, payout_margin_usd are absent from the class entirely
- offer_rate JSON key maps to DB column offer_rate_coll
- rate_locked_at maps to DB column committed_at
- All monetary DECIMAL fields use BigDecimal type; all currency fields use String (CHAR 3)

### 11.4-T02 — Define EventTrailStep DTO for the 4-step partner-visible event trail  _(20 min)_
**Context:** WBS 11.4: PRD-08 §5.3 states partners see a simplified 4-step view: Initiated, Scheme Processing, Approved/Declined, Notified. The full internal 8-step trail (SEC-09 §6.1) is: 1=rate_quote_issued, 2=payment_initiated, 3=prefund_deducted, 4=scheme_request_sent, 5=scheme_response_received, 6=transaction_committed, 7=webhook_dispatched, 8=webhook_delivered. The 4 partner steps map as: Initiated -> steps 1-2, Scheme Processing -> steps 4-5, Approved/Declined -> step 6, Notified -> steps 7-8. Each step shows: step label, timestamp (UTC and KST), duration_ms from previous visible step, and status (COMPLETED / SKIPPED / PENDING / FAILED). Absent steps (e.g. step 3 prefund_deducted for LOCAL partners) appear grayed-out with reason. Failure steps include error_code and error_detail from the step detail JSONB.
**Steps:** Create EventTrailStep record with fields: step_number (1-4), label (enum: INITIATED, SCHEME_PROCESSING, OUTCOME, NOTIFIED), occurred_at_utc, occurred_at_kst, duration_ms (nullable), status (COMPLETED/SKIPPED/PENDING/FAILED), skip_reason (nullable String), error_code (nullable), error_detail (nullable); Add KST conversion note: occurred_at_kst = occurred_at_utc + 9 hours, formatted as ISO-8601 with +09:00 offset; Confirm step_number 1-4 aligns with partner-visible labeling
**Deliverable:** EventTrailStep DTO record class with all fields above and a PartnerTrailStepLabel enum (INITIATED, SCHEME_PROCESSING, OUTCOME, NOTIFIED)
**Acceptance / logic checks:**
- step_number range is 1-4 only
- occurred_at_kst is offset +09:00 of occurred_at_utc, e.g. 2026-05-01T10:00:00Z becomes 2026-05-01T19:00:00+09:00
- skip_reason is non-null when status=SKIPPED
- error_code and error_detail non-null only when status=FAILED
- PartnerTrailStepLabel has exactly 4 values
**Depends on:** 11.4-T01

### 11.4-T03 — Add DB query method: fetch transaction by hub_txn_ref scoped to partner_id  _(30 min)_
**Context:** WBS 11.4: Data isolation rule (PRD-08 §2.2): every query is filtered by partner_id from JWT claims, never from URL. Attempting to access a transaction belonging to another partner must return empty (NOT an error that reveals existence). The transaction table has hub_txn_ref VARCHAR(64) UNIQUE and partner_id BIGINT FK. Composite indexes include (hub_txn_ref) and (partner_id, committed_at). The query must JOIN to transaction_event, merchant (for merchant_name), and rate_quote. DB columns to fetch: all fields listed in 11.4-T01 plus internal DB columns needed for mapping (committed_at as rate_locked_at, offer_rate_coll, is_same_ccy_shortcircuit).
**Steps:** Add findByHubTxnRefAndPartnerId(String hubTxnRef, Long partnerId) to TransactionRepository; Use JPQL or named query: SELECT t FROM Transaction t LEFT JOIN FETCH t.events LEFT JOIN FETCH t.merchant WHERE t.hub_txn_ref = :ref AND t.partner_id = :pid; Return Optional<Transaction> - empty if not found or partner mismatch; Add index hint comment if hub_txn_ref index is not already declared in JPA entity; Verify no raw SQL exposes partner_id filter bypass
**Deliverable:** TransactionRepository.findByHubTxnRefAndPartnerId method with JOIN FETCH for events and merchant
**Acceptance / logic checks:**
- Returns Optional.empty() when hub_txn_ref exists but partner_id does not match (no exception thrown)
- Returns populated Optional when both match
- LEFT JOIN FETCH on transaction_event loads all event rows in one query
- SQL generated (via Hibernate show_sql) contains WHERE t.hub_txn_ref = ? AND t.partner_id = ?
- Method has no overload that accepts hub_txn_ref alone (prevents accidental bypass)

### 11.4-T04 — Implement TransactionDetailService.getDetail mapping logic  _(45 min)_
**Context:** WBS 11.4: Service layer maps Transaction entity to TransactionDetailResponse DTO. Key mapping rules: (1) offer_rate in DTO = offer_rate_coll from DB. (2) If is_same_ccy_shortcircuit=true, set LockedPoolFields to null and omit collection_usd, payout_usd_cost. (3) rate_locked_at = committed_at timestamp. (4) KST display: txn_timestamp_kst = txn_timestamp_utc + 9h. (5) For LOCAL partner (partner.type=LOCAL), step 3 (prefund_deducted) is SKIPPED. (6) Internal fields m_a, m_b, cost_rate_coll, cost_rate_pay, collection_margin_usd, payout_margin_usd must NOT appear in output. (7) EventTrailStep list is built from transaction_event rows sorted by step ASC; the 4-step mapping collapses 8 internal steps to 4 partner steps per T02.
**Steps:** Inject TransactionRepository; implement getDetail(String hubTxnRef, Long partnerId) returning Optional<TransactionDetailResponse>; Map all amount fields from Transaction entity to AmountFields nested DTO; Conditionally set lockedPoolFields based on is_same_ccy_shortcircuit flag; Build EventTrailStep list by collapsing 8 steps to 4 partner-visible steps; mark step 3 (prefund_deducted) as SKIPPED with reason 'LOCAL partner - no prefunding' when partner.type=LOCAL; Map occurred_at from transaction_event to UTC and KST forms; Throw no exception if not found - return Optional.empty()
**Deliverable:** TransactionDetailService class with getDetail method returning Optional<TransactionDetailResponse>
**Acceptance / logic checks:**
- For is_same_ccy_shortcircuit=true input, returned DTO has lockedPoolFields=null and no USD pool values
- For cross-border input with collection_usd=100.0, payout_usd_cost=96.08, returned DTO.lockedPoolFields contains those exact values
- LOCAL partner transaction has EventTrailStep for INITIATED with status=COMPLETED and step for prefund_deducted mapped as SKIPPED within the 4-step view
- offer_rate in DTO equals offer_rate_coll from entity (not a separate computation)
- No internal margin fields appear anywhere in the returned object graph
**Depends on:** 11.4-T01, 11.4-T02, 11.4-T03

### 11.4-T05 — Implement GET /v1/partner/transactions/{hub_txn_ref} endpoint handler  _(30 min)_
**Context:** WBS 11.4: REST endpoint for partner portal transaction detail. URL: GET /v1/partner/transactions/{hub_txn_ref}. Authentication: JWT bearer token; partner_id extracted from claims field 'partner_id' - NEVER from URL path or query param. Security rule (PRD-08 §2.2): if transaction belongs to another partner, return HTTP 404 (not 403) to avoid confirming existence. Response: 200 with TransactionDetailResponse JSON. Error cases: 404 if not found or wrong partner; 401 if no/invalid JWT. hub_txn_ref is URL path variable. The endpoint is partner-portal-scoped and must go through the partner JWT filter chain.
**Steps:** Add @GetMapping("/v1/partner/transactions/{hubTxnRef}") in PartnerTransactionController; Extract partner_id from SecurityContextHolder JWT claims (not from request parameter); Call TransactionDetailService.getDetail(hubTxnRef, partnerId); Return 200 with TransactionDetailResponse body if present; return ResponseEntity.notFound().build() if Optional.empty(); Ensure endpoint is behind the partner JWT security filter (not admin filter)
**Deliverable:** PartnerTransactionController.getTransactionDetail endpoint returning 200 or 404
**Acceptance / logic checks:**
- GET /v1/partner/transactions/HUB-001 with valid JWT for partner owning HUB-001 returns 200 and correct DTO
- GET /v1/partner/transactions/HUB-001 with valid JWT for a different partner returns 404 (not 403)
- GET /v1/partner/transactions/HUB-001 with no JWT returns 401
- Response body contains no m_a, m_b, cost_rate_coll, cost_rate_pay fields (verified by JSON key scan)
- Request with partner_id injected as query param is ignored; only JWT claims partner_id is used
**Depends on:** 11.4-T04

### 11.4-T06 — Build 8-to-4 step collapse utility: map internal event trail to partner view  _(45 min)_
**Context:** WBS 11.4: The internal transaction_event table stores up to 8 steps (step INT 1-8, event_type VARCHAR, occurred_at TIMESTAMPTZ, duration_ms INT, detail JSONB). The partner sees 4 steps (PRD-08 §5.3, assumption A-09): Step 1 INITIATED covers internal steps 1 (rate_quote_issued) and 2 (payment_initiated) - use the earlier timestamp; Step 2 SCHEME_PROCESSING covers internal steps 4 (scheme_request_sent) and 5 (scheme_response_received) - use scheme_request_sent timestamp, duration = time between steps 4 and 5; Step 3 OUTCOME covers internal step 6 (transaction_committed) - maps APPROVED or DECLINED based on txn status; Step 4 NOTIFIED covers internal steps 7 (webhook_dispatched) and 8 (webhook_delivered) - use step 7 timestamp. Step 3 (internal step 3 prefund_deducted) is skipped for LOCAL partners. A missing internal step (null event row) produces PENDING status for that partner step.
**Steps:** Create EventTrailMapper utility class with static mapToPartnerSteps(List<TransactionEvent> events, String partnerType, String txnStatus) method returning List<EventTrailStep> of size exactly 4; Implement collapse logic per context mapping rules; Mark a partner step as PENDING if its required internal steps are not yet present; Mark as FAILED if step detail JSONB contains error_code key; Set skip_reason = 'Not applicable for LOCAL partner' for partner step 1 sub-item prefund_deducted when partnerType=LOCAL; Compute duration_ms for each partner step as difference between first and last internal step timestamps within that group
**Deliverable:** EventTrailMapper utility class with mapToPartnerSteps method, covering all 4 partner steps and PENDING/SKIPPED/FAILED states
**Acceptance / logic checks:**
- Input with all 8 steps present produces List of exactly 4 EventTrailStep objects
- Input missing step 7 and 8 produces partner step 4 NOTIFIED with status=PENDING
- Input with step 5 detail JSONB containing error_code produces partner step 2 status=FAILED with error_code populated
- LOCAL partner with 7 internal steps (step 3 absent) produces step 1 INITIATED with status=COMPLETED (step 3 absence is expected, not FAILED)
- Timestamps for partner step 2 SCHEME_PROCESSING equal the occurred_at of internal step 4 (scheme_request_sent)
**Depends on:** 11.4-T02

### 11.4-T07 — Unit tests for EventTrailMapper collapse logic  _(40 min)_
**Context:** WBS 11.4: Test the EventTrailMapper from 11.4-T06. The mapper collapses 8 internal transaction_event rows into 4 partner-visible steps. Key test vectors: (A) happy-path OVERSEAS partner, all 8 steps present, txnStatus=APPROVED; (B) LOCAL partner, step 3 (prefund_deducted) absent, all other steps present; (C) OVERSEAS transaction declined at step 5, steps 6-8 absent; (D) webhook pending - steps 1-7 present, step 8 absent; (E) partial trail - only steps 1-2 present (payment just initiated). Use the event_type values: rate_quote_issued, payment_initiated, prefund_deducted, scheme_request_sent, scheme_response_received, transaction_committed, webhook_dispatched, webhook_delivered.
**Steps:** Create EventTrailMapperTest JUnit 5 class; Implement test vector A: all 8 steps, OVERSEAS, APPROVED - assert 4 steps all COMPLETED, step 3 OUTCOME label=APPROVED; Implement test vector B: LOCAL partner, step 3 absent - assert 4 steps, step 1 INITIATED COMPLETED, step 2 SCHEME_PROCESSING COMPLETED, no FAILED; Implement test vector C: declined at step 5, steps 6-8 absent - assert step 2 SCHEME_PROCESSING FAILED, steps 3 and 4 PENDING; Implement test vector D: step 8 absent - assert step 4 NOTIFIED PENDING; Implement test vector E: only steps 1-2 present - assert step 1 COMPLETED, steps 2-4 PENDING
**Deliverable:** EventTrailMapperTest with 6 test methods covering vectors A-E
**Acceptance / logic checks:**
- Vector A: all 4 partner steps have status=COMPLETED and occurred_at non-null
- Vector B: result list size=4, no step has status=FAILED due to absent step 3
- Vector C: partnerStep[1].status=FAILED, partnerStep[2].status=PENDING, partnerStep[3].status=PENDING
- Vector D: partnerStep[3].status=PENDING, partnerStep[0-2].status=COMPLETED
- Vector E: partnerStep[0].status=COMPLETED, partnerStep[1-3].status=PENDING
- All 6 tests pass with zero failures
**Depends on:** 11.4-T06

### 11.4-T08 — Unit tests for TransactionDetailService mapping: cross-border vs same-currency  _(40 min)_
**Context:** WBS 11.4: Test TransactionDetailService.getDetail from 11.4-T04. Two primary scenarios driven by is_same_ccy_shortcircuit: (A) Cross-border transaction (KRW payout, USD settlement): target_payout=50000 KRW, payout_usd_cost=38.46 USD (cost_rate_pay=1300.0 KRW/USD so payout_usd_cost=50000/1300=38.4615), collection_usd=39.24 USD (m_a=0.01, m_b=0.01, so collection_usd=38.46/(1-0.02)=39.2449), send_amount=51012 KRW (=39.2449*1299.0, cost_rate_coll=1299.0), service_charge=500 KRW, collection_amount=51512 KRW, offer_rate_coll=51012/(39.2449-0.3924)=1313.something, cross_rate=50000/51012=0.9802; (B) Same-currency (Domestic KRW): is_same_ccy_shortcircuit=true, collection_usd=null, payout_usd_cost=null. Mock repository to return preset Transaction entities.
**Steps:** Create TransactionDetailServiceTest with Mockito mocks for TransactionRepository; Implement test A: cross-border scenario - build a mock Transaction entity with the numeric values in context, verify returned DTO has lockedPoolFields non-null, collection_usd=39.24 (4dp), payout_usd_cost=38.46, offer_rate matches offer_rate_coll from entity; Implement test B: same-currency scenario - build mock Transaction with is_same_ccy_shortcircuit=true and null USD fields, verify DTO.lockedPoolFields=null; Implement test C: partner_id mismatch - repository returns Optional.empty(), verify service returns Optional.empty(); Assert no internal margin field appears in DTO for either test A or B
**Deliverable:** TransactionDetailServiceTest with at least 4 test methods covering cross-border, same-ccy, and not-found scenarios
**Acceptance / logic checks:**
- Test A: DTO.lockedPoolFields.collection_usd=39.2449 (or 39.24 rounded to 4dp per scale), payout_usd_cost=38.4615
- Test A: DTO.amountFields.offer_rate equals entity.offer_rate_coll value exactly
- Test B: DTO.lockedPoolFields is null
- Test C: Optional.empty() returned when repository returns empty
- Test A: DTO contains no field named m_a, m_b, collection_margin_usd, payout_margin_usd
**Depends on:** 11.4-T04

### 11.4-T09 — Unit tests for TransactionDetailService: data isolation and field exclusion  _(35 min)_
**Context:** WBS 11.4: Critical security property (PRD-08 §2.2): partner A must never see partner B data. The service must return Optional.empty() (not throw) when hub_txn_ref exists but partner_id does not match. Also verify internal field exclusion: m_a, m_b, cost_rate_coll, cost_rate_pay, collection_margin_usd, payout_margin_usd must not appear in the DTO. Additionally test rate_locked_at mapping: committed_at from Transaction entity must appear as rate_locked_at in LockedPoolFields.
**Steps:** Add test: repository returns Optional.empty() for (hubTxnRef=HUB-999, partnerId=2) even though HUB-999 exists for partnerId=1 - assert service returns Optional.empty() with no exception; Add test: verify via reflection that TransactionDetailResponse class has no field named m_a, m_b, cost_rate_coll, cost_rate_pay, collection_margin_usd, or payout_margin_usd anywhere in its object graph; Add test: committed_at=2026-05-01T10:00:00Z on entity maps to rate_locked_at=2026-05-01T10:00:00Z in LockedPoolFields; Add test: txn_timestamp=2026-05-01T10:00:00Z in DB maps to txn_timestamp_utc=2026-05-01T10:00:00Z and txn_timestamp_kst=2026-05-01T19:00:00+09:00
**Deliverable:** Additional test methods appended to TransactionDetailServiceTest or a new TransactionDetailSecurityTest class
**Acceptance / logic checks:**
- Data isolation test: Optional.empty() returned, no NotFoundException or other exception thrown
- Reflection-based field scan: zero fields named m_a, m_b, cost_rate_coll, cost_rate_pay, collection_margin_usd, payout_margin_usd found in full DTO hierarchy
- rate_locked_at in DTO equals committed_at from entity (same Instant value)
- KST offset test: 10:00 UTC -> 19:00+09:00 asserted on txn_timestamp_kst
- All 4 tests pass
**Depends on:** 11.4-T04

### 11.4-T10 — Add partner-scoped security filter and partner_id claim extraction utility  _(45 min)_
**Context:** WBS 11.4: PRD-08 §2.2 requires partner_id to come exclusively from the JWT claims, never from URL params or request body. The JWT bearer token contains a claim 'partner_id' (Long). A Spring Security filter (or HandlerMethodArgumentResolver) must extract this and expose it to controllers. The filter chain for /v1/partner/** endpoints must be distinct from /v1/admin/** so partner tokens cannot hit admin endpoints. JWT validation uses the shared GMEPay+ JWT secret (HS256 or RS256 per SEC-09). If partner_id claim is missing or token is invalid/expired, return 401.
**Steps:** Create PartnerJwtAuthenticationFilter extending OncePerRequestFilter - parse Bearer token, validate signature, extract partner_id claim as Long; Register filter on /v1/partner/** URL pattern only; Create @CurrentPartnerId annotation + HandlerMethodArgumentResolver that pulls Long partner_id from SecurityContext for use in controller method signatures; Return 401 with error body {code: UNAUTHORIZED, message: Invalid or missing partner token} if token invalid; Write unit test for the resolver: mock SecurityContext with partner_id=42, assert resolver returns 42L
**Deliverable:** PartnerJwtAuthenticationFilter, @CurrentPartnerId annotation, and PartnerIdArgumentResolver with unit test
**Acceptance / logic checks:**
- Request to /v1/partner/transactions/X with valid partner JWT for partner_id=5 populates SecurityContext with partner_id=5
- Request with expired JWT returns 401
- Request with valid admin JWT (no partner_id claim) to /v1/partner/** returns 401
- @CurrentPartnerId resolver returns exactly the Long value from the JWT partner_id claim
- Filter does NOT apply to /v1/admin/** endpoints

### 11.4-T11 — Integration test: GET /v1/partner/transactions/{hub_txn_ref} data isolation  _(55 min)_
**Context:** WBS 11.4: PRD-08 AC-02 and AC-07. Integration test using Spring Boot test slice or Testcontainers PostgreSQL. Seed: two partners (id=1, id=2), one transaction per partner. Partner 1 has a cross-border transaction HUB-P1-001 (is_same_ccy_shortcircuit=false, collection_usd=100.00, payout_usd_cost=96.08, offer_rate_coll=1299.50, cross_rate=0.9802). Partner 2 has transaction HUB-P2-001. Test: partner 1 token accessing HUB-P1-001 gets 200. Partner 1 token accessing HUB-P2-001 gets 404. No 403. AC-07: response contains collection_usd and payout_usd_cost and offer_rate. AC-08: same-currency transaction (seed a 3rd txn for partner 1 with is_same_ccy_shortcircuit=true) returns 200 without USD pool fields.
**Steps:** Set up @SpringBootTest with Testcontainers Postgres; seed partners and transactions in @BeforeEach; Test 1: GET HUB-P1-001 with partner 1 JWT -> assert 200, body.lockedPoolFields.collection_usd=100.00, body.lockedPoolFields.payout_usd_cost=96.08, body.amountFields.offer_rate=1299.50; Test 2: GET HUB-P2-001 with partner 1 JWT -> assert 404; Test 3: GET HUB-P1-SAME (is_same_ccy=true) with partner 1 JWT -> assert 200, body.lockedPoolFields=null; Test 4: Request with no JWT -> assert 401
**Deliverable:** PartnerTransactionDetailIntegrationTest with 4 test methods
**Acceptance / logic checks:**
- Test 1 passes: 200, collection_usd=100.00, payout_usd_cost=96.08, offer_rate=1299.50 in response JSON
- Test 2 passes: HTTP 404 (not 403)
- Test 3 passes: 200, lockedPoolFields is null or absent from JSON
- Test 4 passes: HTTP 401
- Response JSON for test 1 contains no key m_a, m_b, cost_rate_coll, cost_rate_pay, collection_margin_usd, or payout_margin_usd (verified by JSONPath scan)
**Depends on:** 11.4-T05, 11.4-T10

### 11.4-T12 — Add DB indexes for transaction detail query performance  _(25 min)_
**Context:** WBS 11.4: The detail endpoint queries transaction by (hub_txn_ref, partner_id). The existing composite index on hub_txn_ref (unique) and (partner_id, committed_at) from DAT-03 are noted in the spec. Verify these indexes exist; add any missing ones. Also: transaction_event is queried by txn_id to build the event trail - ensure index on transaction_event.txn_id exists. Performance target: transaction history query P95 < 3 seconds (NFR-10); detail view is a single-row fetch so target < 200 ms.
**Steps:** Review existing Flyway migration files for transaction and transaction_event table definitions; Add Flyway migration V{next}__add_txn_detail_indexes.sql if any of these are missing: UNIQUE index on transaction.hub_txn_ref; index on transaction(partner_id, committed_at); index on transaction_event(txn_id); Do NOT drop existing indexes - only add missing ones; Add a comment in the migration explaining each index purpose; Verify migration runs cleanly on a local test DB
**Deliverable:** Flyway migration file V{next}__add_txn_detail_indexes.sql (may be a no-op if all indexes already exist, but must be verified and documented)
**Acceptance / logic checks:**
- \d transaction in psql shows unique index on hub_txn_ref
- \d transaction shows index on (partner_id, committed_at)
- \d transaction_event shows index on txn_id
- Flyway migrate runs with exit code 0 on a clean schema
- EXPLAIN SELECT * FROM transaction WHERE hub_txn_ref='X' AND partner_id=1 uses index scan not seq scan

### 11.4-T13 — Implement conditional display logic: same-currency vs cross-border in detail response  _(30 min)_
**Context:** WBS 11.4: PRD-08 §5.2 and AC-08: for same-currency (Domestic) transactions where is_same_ccy_shortcircuit=true, the USD pool fields (collection_usd, payout_usd_cost, rate_locked_at) must NOT be displayed to the partner. The DB columns are NULL for these transactions (per DAT-03 §5.2: payout_usd_cost NULL for same-ccy, collection_usd NULL). The service layer must detect is_same_ccy_shortcircuit=true and set lockedPoolFields=null in the DTO. The API JSON must not include the lockedPoolFields key when null (use @JsonInclude(NON_NULL)). The section note 'Same-currency short-circuit applied' should appear in a separate display_note field on the response.
**Steps:** Annotate TransactionDetailResponse and nested LockedPoolFields with @JsonInclude(JsonInclude.Include.NON_NULL) so null objects are omitted from JSON; Add display_note field (nullable String) to TransactionDetailResponse; In TransactionDetailService, when is_same_ccy_shortcircuit=true, set lockedPoolFields=null and display_note='Same-currency transaction - USD pool not applicable'; Write unit test: assert that Jackson serialization of a DTO with lockedPoolFields=null produces JSON with no lockedPoolFields key; Write unit test: cross-border DTO produces JSON that does include lockedPoolFields key
**Deliverable:** @JsonInclude annotations on DTO + display_note field + service logic + 2 unit tests for JSON serialization behavior
**Acceptance / logic checks:**
- Jackson serialization of is_same_ccy=true DTO: JSON string does not contain 'lockedPoolFields' key
- Jackson serialization of is_same_ccy=false DTO: JSON string contains 'lockedPoolFields' with collection_usd and payout_usd_cost
- display_note='Same-currency transaction - USD pool not applicable' when is_same_ccy=true
- display_note=null (and absent from JSON) when is_same_ccy=false
- Unit tests pass
**Depends on:** 11.4-T04

### 11.4-T14 — Implement 4-step partner event trail display with timestamps in KST  _(35 min)_
**Context:** WBS 11.4: PRD-08 §5.3 and assumption A-09. The partner sees 4 steps with labels: Initiated, Scheme Processing, Approved/Declined, Notified. Each step shows: label, occurred_at in KST (UTC+9, formatted ISO-8601 with +09:00 offset), duration_ms since previous partner-visible step (not since previous internal step). The service must compute inter-step duration correctly. Example: if partner step 1 (INITIATED) occurred at 10:00:00.000 UTC and partner step 2 (SCHEME_PROCESSING) at 10:00:02.500 UTC, duration_ms for step 2 = 2500. Steps with no occurred_at (PENDING) have duration_ms=null. The 4-step mapping from 8 internal steps uses timestamps from the anchor internal step for each group (see 11.4-T06).
**Steps:** In EventTrailMapper.mapToPartnerSteps, after building 4 steps in order, compute duration_ms for steps 2-4 as (current_step.occurred_at - previous_completed_step.occurred_at) in milliseconds; For PENDING steps set duration_ms=null; Format occurred_at_kst as OffsetDateTime with ZoneOffset.of('+09:00') applied to the UTC Instant; Return the list sorted by step_number ascending; Add a test vector with specific timestamps to assert duration_ms computation
**Deliverable:** Updated EventTrailMapper with inter-step duration computation and KST formatting, plus one additional test verifying duration_ms values
**Acceptance / logic checks:**
- Step 1 INITIATED has duration_ms=null (first step, no previous)
- If step 1 occurred_at=10:00:00Z and step 2 occurred_at=10:00:02.5Z, step 2 duration_ms=2500
- PENDING steps have duration_ms=null
- occurred_at_kst for 10:00:00Z is 19:00:00+09:00
- Steps are returned in ascending step_number order (1,2,3,4)
**Depends on:** 11.4-T06

### 11.4-T15 — Add scheme reference section to detail response and service mapping  _(30 min)_
**Context:** WBS 11.4: PRD-08 §5.4 scheme reference fields shown on the detail view: scheme_txn_ref (= transaction.scheme_ref from DB), scheme_name (from qr_scheme.scheme_name joined via transaction.scheme_id), merchant_id (transaction.merchant_id), merchant_name (from merchant.merchant_name joined via transaction.merchant_id), payment_mode (MPM or CPM). These are non-sensitive metadata fields visible to all partner roles. The merchant name comes from GME local merchant DB (DAT-03 merchant table), not from ZeroPay live API. hub_txn_ref is also shown on the detail view as the primary transaction identifier.
**Steps:** Confirm SchemeReference nested DTO in TransactionDetailResponse has: hub_txn_ref, scheme_txn_ref, scheme_name, merchant_id, merchant_name, payment_mode, direction, txn_timestamp_utc, txn_timestamp_kst, status fields; Verify the TransactionRepository query (11.4-T03) JOIN FETCHes the Merchant entity and QrScheme entity to avoid N+1 queries; In TransactionDetailService, map Transaction.scheme_ref -> SchemeReference.scheme_txn_ref, Transaction.merchant.merchant_name -> SchemeReference.merchant_name, Transaction.scheme.scheme_name -> SchemeReference.scheme_name; Write unit test: entity with scheme_ref='ZP-12345', merchant_name='Test Merchant', scheme_name='ZeroPay' produces correct SchemeReference fields
**Deliverable:** SchemeReference populated in TransactionDetailService with correct field mappings and 1 unit test
**Acceptance / logic checks:**
- scheme_txn_ref in DTO equals transaction.scheme_ref from entity (e.g. 'ZP-12345')
- merchant_name in DTO equals transaction.merchant.merchant_name
- scheme_name in DTO equals transaction.scheme.scheme_name
- payment_mode is exactly 'MPM' or 'CPM' (not lowercase, not null)
- Unit test passes with the sample values scheme_ref=ZP-12345, merchant_name=Test Merchant, scheme_name=ZeroPay
**Depends on:** 11.4-T04

### 11.4-T16 — Implement partner portal transaction detail page: amounts section (frontend)  _(45 min)_
**Context:** WBS 11.4: Frontend React/TypeScript component for the amounts section of the transaction detail page at route /{partnerSlug}/transactions/{hubTxnRef}. Displays partner-visible amount fields from TransactionDetailResponse.amountFields: target_payout (with payout_ccy), collection_amount (with collection_ccy), service_charge, send_amount, offer_rate (6 significant figures per UX-11 §10.2), cross_rate. Currency amounts shown with currency code and 2 decimal places (e.g. 'KRW 50,000' for 0-decimal currency or 'USD 1,234.56'). Offer rate and cross_rate shown to 6 significant figures. Desktop-first, min 1280px width per PRD-08 §10.2.
**Steps:** Create TransactionAmountsSection React component accepting TransactionDetailResponse as prop; Render each amount field as a labeled row: label on left, formatted value + currency on right; Format KRW amounts with 0 decimal places and comma thousands separator; USD amounts with 2 decimal places; Format offer_rate and cross_rate to 6 significant figures using toLocaleString or toPrecision(6); Show section only when data is loaded; show skeleton loader while fetching
**Deliverable:** TransactionAmountsSection React component with currency-aware formatting and skeleton state
**Acceptance / logic checks:**
- KRW 50000 renders as 'KRW 50,000' (no decimal places, comma separator)
- USD 1234.5678 renders as 'USD 1,234.57' (2 decimal places)
- offer_rate=1299.5 renders as '1,299.50' or '1299.50' to 6 significant figures
- Component renders skeleton placeholders when prop is undefined/loading
- Component renders all 6 amount field rows when fully populated
**Depends on:** 11.4-T01

### 11.4-T17 — Implement partner portal transaction detail page: locked pool values section (frontend)  _(40 min)_
**Context:** WBS 11.4: PRD-08 §5.2 and AC-07/AC-08. The locked pool values section is only shown for cross-border transactions (is_same_ccy_shortcircuit=false, lockedPoolFields non-null). When is_same_ccy_shortcircuit=true, the section is hidden entirely and replaced with a note 'Same-currency transaction - USD intermediary pool not applicable'. When shown, displays: collection_usd (USD, 4dp), payout_usd_cost (USD, 4dp), rate_locked_at (formatted as date-time in KST). Section header: 'Rate Lock & USD Pool'. All values are read-only and clearly labeled as locked-at-commit values for partner reconciliation.
**Steps:** Create LockedPoolSection React component accepting lockedPoolFields: LockedPoolFields | null and displayNote: string | null; If lockedPoolFields is null, render a gray info box showing displayNote text instead of the table; If lockedPoolFields non-null, render table rows for collection_usd and payout_usd_cost as 'USD X.XXXX' and rate_locked_at formatted as KST datetime string; Add 'Locked at commit - immutable' subtitle to section header; Write 2 Vitest/Jest snapshot or assertion tests: one for null lockedPoolFields (shows note), one for populated lockedPoolFields (shows values)
**Deliverable:** LockedPoolSection React component with conditional rendering and 2 unit tests
**Acceptance / logic checks:**
- When lockedPoolFields=null, component renders the displayNote string and no USD amount rows
- When lockedPoolFields={collection_usd:'100.0000',payout_usd_cost:'96.0800',rate_locked_at:'2026-05-01T10:00:00Z'}, renders 'USD 100.0000' and 'USD 96.0800'
- rate_locked_at is displayed in KST format (e.g. 2026-05-01 19:00:00 KST)
- Component does not render collection_margin_usd or payout_margin_usd (internal fields never in DTO)
- Both unit tests pass
**Depends on:** 11.4-T16

### 11.4-T18 — Implement partner portal transaction detail page: 4-step event trail section (frontend)  _(45 min)_
**Context:** WBS 11.4: PRD-08 §5.3 and A-09. The 4-step partner trail is displayed as a vertical timeline. Each step shows: step number (1-4), label (Initiated, Scheme Processing, Approved/Declined, Notified), timestamp in KST, duration_ms from previous step (shown as 'X ms' or 'X.X s'), status indicator (green check = COMPLETED, gray = PENDING, red X = FAILED, gray dash = SKIPPED with skip_reason tooltip). Steps with status=FAILED show error_code and error_detail below the step in a red callout box. The Approved/Declined label dynamically shows 'Approved' or 'Declined' based on the step outcome. Absent/SKIPPED steps are grayed-out with skip_reason shown as tooltip text.
**Steps:** Create EventTrailTimeline React component accepting steps: EventTrailStep[] (length 4); Render a vertical timeline with 4 nodes; each node has a status icon (check/X/dash/spinner); For COMPLETED steps: green check, show occurred_at_kst and duration_ms formatted; For PENDING steps: gray spinner, show 'Awaiting...' text; For FAILED steps: red X icon, occurred_at_kst, error_code + error_detail in red callout; For SKIPPED steps: gray dash, show skip_reason as tooltip or inline note; Write unit test asserting FAILED step renders error_code text
**Deliverable:** EventTrailTimeline React component with 4 status states and 1 unit test for FAILED step rendering
**Acceptance / logic checks:**
- COMPLETED step renders green check and occurred_at_kst timestamp
- PENDING step renders 'Awaiting...' text and no timestamp
- FAILED step renders error_code value visibly in red callout
- SKIPPED step renders skip_reason (not the internal step name)
- All 4 steps are always rendered (PENDING if not yet occurred), never omitted
**Depends on:** 11.4-T02

### 11.4-T19 — Implement partner portal transaction detail page: scheme reference section (frontend)  _(40 min)_
**Context:** WBS 11.4: PRD-08 §5.4. The scheme reference section displays: hub_txn_ref (full, copyable), scheme_txn_ref, scheme_name, merchant_id, merchant_name, payment_mode (MPM/CPM badge), direction, transaction timestamp (KST), status badge. This is above the fold on the detail page. hub_txn_ref must be fully copyable (copy button). Status is shown as a color-coded badge matching UX-11 badge styles: APPROVED=green, DECLINED=red, PENDING=amber, CANCELLED=gray, REFUNDED=blue. URL for this page is /{partnerSlug}/transactions/{hubTxnRef} - the partnerSlug and hubTxnRef are extracted from the URL by the React Router route.
**Steps:** Create TransactionHeaderSection React component accepting SchemeReference and status from the DTO; Render hub_txn_ref as a monospace text field with a Copy button (copies to clipboard via navigator.clipboard.writeText); Render payment_mode as a pill badge (MPM=blue, CPM=purple); Render status as color-coded badge (APPROVED=green, DECLINED=red, PENDING=amber, CANCELLED=gray, REFUNDED=blue); Render all remaining fields (scheme_txn_ref, scheme_name, merchant_id, merchant_name, direction, txn_timestamp_kst) as labeled rows
**Deliverable:** TransactionHeaderSection React component with copy-to-clipboard for hub_txn_ref and status badge
**Acceptance / logic checks:**
- hub_txn_ref displayed in full (not truncated) with Copy button
- Clicking Copy button invokes navigator.clipboard.writeText with the full hub_txn_ref value
- APPROVED status renders with green badge class, DECLINED with red
- payment_mode=MPM renders MPM badge, CPM renders CPM badge
- txn_timestamp displayed in KST format
**Depends on:** 11.4-T01

### 11.4-T20 — Wire up transaction detail page route and API call in partner portal  _(45 min)_
**Context:** WBS 11.4: The detail page is reachable at route /{partnerSlug}/transactions/:hubTxnRef. The frontend calls GET /v1/partner/transactions/{hubTxnRef} with the JWT bearer token from the auth context. The hub_txn_ref is extracted from the URL parameter :hubTxnRef. partner_id is NOT sent as a URL or query param - it comes from the server-side JWT. On 404 response, show a 'Transaction not found' page (not a generic error). On 401 response, redirect to login. On success, render the 4 sections from T16-T19 in order: header -> amounts -> locked pool -> event trail.
**Steps:** Add route /{partnerSlug}/transactions/:hubTxnRef in the React Router config; Create TransactionDetailPage component that calls useTransactionDetail(hubTxnRef) custom hook; Implement useTransactionDetail hook using React Query or SWR: GET /v1/partner/transactions/{hubTxnRef}, attach Authorization: Bearer {token} from auth context, handle loading/error/success states; Render 404 message component on HTTP 404 response (not a generic error page); On HTTP 401, clear auth context and redirect to /login; Render 4 sections in order: TransactionHeaderSection, TransactionAmountsSection, LockedPoolSection, EventTrailTimeline
**Deliverable:** TransactionDetailPage component with route wiring, useTransactionDetail hook, and correct 404/401 handling
**Acceptance / logic checks:**
- Navigating to /{partnerSlug}/transactions/HUB-001 renders the page and calls GET /v1/partner/transactions/HUB-001
- JWT token is sent in Authorization header (never in URL or query param)
- 404 response renders a 'Transaction not found' message (not a 500 error)
- 401 response redirects to /login
- Success renders all 4 sections with data from the API response
**Depends on:** 11.4-T16, 11.4-T17, 11.4-T18, 11.4-T19, 11.4-T05

### 11.4-T21 — Integration test: transaction detail API with full event trail  _(55 min)_
**Context:** WBS 11.4: End-to-end API integration test covering the event trail section. Seeds a transaction with all 8 transaction_event rows for an OVERSEAS partner. Calls GET /v1/partner/transactions/{hub_txn_ref} and asserts the response eventTrail has 4 items, each with correct labels and timestamps. Also tests a transaction with only 5 internal steps (steps 6-8 absent) - partner steps 3 and 4 should be PENDING. Uses Testcontainers PostgreSQL.
**Steps:** Seed OVERSEAS partner with a complete transaction (all 8 event steps with realistic timestamps spaced 200-500ms apart); Call GET /v1/partner/transactions/{ref} and assert: response.eventTrail.length=4, step 1 label=INITIATED, step 1 status=COMPLETED, step 4 label=NOTIFIED, step 4 status=COMPLETED; Seed a second transaction for same partner with only 5 internal steps (steps 1-5 present, 6-8 absent); Call detail for second transaction and assert: step 3 OUTCOME status=PENDING, step 4 NOTIFIED status=PENDING; Assert duration_ms for step 2 equals the ms difference between internal step 4 and step 2 timestamps
**Deliverable:** TransactionDetailEventTrailIntegrationTest with 2 test scenarios
**Acceptance / logic checks:**
- Complete-trail scenario: all 4 partner steps COMPLETED, timestamps match seeded event data
- Partial-trail scenario: step 3 OUTCOME and step 4 NOTIFIED are PENDING
- duration_ms for partner step 2 equals time between internal step 4 occurred_at and internal step 2 occurred_at
- eventTrail array has exactly 4 elements in both scenarios
- HTTP 200 returned in both cases
**Depends on:** 11.4-T11, 11.4-T14

### 11.4-T22 — Add offer_rate and locked values to transaction list CSV export (exclusion verification)  _(30 min)_
**Context:** WBS 11.4: PRD-08 §4.4 CSV export includes offer_rate in the column set but NEVER includes m_a, m_b, cost_rate_coll, cost_rate_pay, collection_margin_usd, payout_margin_usd. These are the same exclusion rules as the detail view. The CSV export endpoint (from WBS 11.x transaction history) must be confirmed to include offer_rate and to exclude all internal margin fields. This ticket verifies the export contract aligns with the detail view and adds a regression test.
**Steps:** Locate the existing CSV export service/query for transaction history; Verify offer_rate_coll is mapped to column header 'offer_rate' in the CSV (not 'offer_rate_coll'); Assert that columns m_a, m_b, cost_rate_coll, cost_rate_pay, collection_margin_usd, payout_margin_usd are absent from the CSV column list; If offer_rate is missing from the export, add it to the column set; Write a unit test that generates a CSV for a set of transactions and asserts column headers contain 'offer_rate' and do not contain any of the 6 excluded field names
**Deliverable:** Regression unit test for CSV export column set, plus any fix to add offer_rate if missing
**Acceptance / logic checks:**
- CSV column header row contains 'offer_rate'
- CSV column header row does NOT contain 'm_a', 'm_b', 'cost_rate_coll', 'cost_rate_pay', 'collection_margin_usd', 'payout_margin_usd'
- CSV column set also includes scheme_txn_ref, merchant_id, merchant_name, full hub_txn_ref as required by §4.4
- Unit test passes
- No partial fix that renames internal columns instead of excluding them
**Depends on:** 11.4-T01

### 11.4-T23 — Add prefunding ledger linkage display in transaction detail (OVERSEAS only)  _(35 min)_
**Context:** WBS 11.4: PRD-08 §8.5.4 (Admin System) and PRD-08 §5.2 (Partner Portal). For OVERSEAS partners, the transaction detail shows the linked prefunding ledger entry: balance_before (USD), deduction_amount (= collection_amount in USD = transaction.prefunding_deducted_usd), balance_after (USD). The prefunding_ledger_entry table has columns: account_id, txn_id (FK -> transaction), entry_type (DEDUCTION), amount (DECIMAL 20,4), balance_after (DECIMAL 20,4). balance_before = balance_after + abs(amount). For LOCAL partners this section is hidden entirely (PRD-08 A-10).
**Steps:** Add optional PrefundingLedgerSummary nested DTO to TransactionDetailResponse: balance_before_usd, deduction_amount_usd, balance_after_usd (all DECIMAL, all nullable); In TransactionRepository query, LEFT JOIN prefunding_ledger_entry ON ple.txn_id = t.id; In TransactionDetailService, if partner.type=OVERSEAS and prefunding ledger entry exists, populate PrefundingLedgerSummary; else set to null; Annotate PrefundingLedgerSummary with @JsonInclude(NON_NULL) so it is absent for LOCAL partners; Write unit test: OVERSEAS txn with prefunding_deducted_usd=39.24, balance_after=5000.00 -> balance_before=5039.24
**Deliverable:** PrefundingLedgerSummary nested DTO, service mapping logic, and 1 unit test
**Acceptance / logic checks:**
- OVERSEAS transaction with deduction_amount_usd=39.24 and balance_after=5000.00 produces balance_before=5039.24 in DTO
- LOCAL partner transaction produces prefundingLedgerSummary=null (absent from JSON due to @JsonInclude(NON_NULL))
- deduction_amount_usd in DTO equals transaction.prefunding_deducted_usd from entity
- Unit test asserts balance_before = balance_after + abs(deduction_amount)
- Serialized JSON for LOCAL partner contains no key 'prefundingLedgerSummary'
**Depends on:** 11.4-T04

### 11.4-T24 — Add settlement linkage display in transaction detail  _(35 min)_
**Context:** WBS 11.4: PRD-08 §8.5.5 (Admin System) and Partner Portal §5 - shows which settlement batch this transaction was included in. The settlement_file table (from DAT-03 or SCH-06) links transactions to ZeroPay batch files. Fields to show: settlement_batch_id or batch_date, zp_file_type (ZP0011/ZP0061/ZP0065), batch_date, registration_result. If transaction is not yet batched (status != SETTLED), show 'Pending settlement'. This is informational only - the partner cannot take action. Settlement linkage is relevant to all partners (not OVERSEAS-only).
**Steps:** Add SettlementLinkage nested DTO to TransactionDetailResponse with fields: batch_date (nullable LocalDate), zp_file_type (nullable String), registration_result (nullable String), settlement_status (SETTLED or PENDING_SETTLEMENT enum); Add LEFT JOIN to settlement-related table in the transaction repository query; In service, populate SettlementLinkage: if batch exists, set all fields; else set settlement_status=PENDING_SETTLEMENT and other fields null; Annotate SettlementLinkage.batch_date etc. with @JsonInclude(NON_NULL) to omit nulls; Write unit test: un-batched transaction produces settlement_status=PENDING_SETTLEMENT; batched transaction produces batch_date and zp_file_type
**Deliverable:** SettlementLinkage nested DTO in TransactionDetailResponse with service mapping and 2 unit tests
**Acceptance / logic checks:**
- Un-batched transaction: settlementLinkage.settlement_status=PENDING_SETTLEMENT, batch_date absent from JSON
- Batched transaction: settlementLinkage.batch_date and zp_file_type populated
- zp_file_type is one of ZP0011, ZP0061, ZP0065 (not free text)
- Both unit tests pass
- JSON for pending settlement contains no null batch_date key (omitted by @JsonInclude(NON_NULL))
**Depends on:** 11.4-T04

### 11.4-T25 — Unit tests for offer_rate precision: verify correct formula application in edge cases  _(30 min)_
**Context:** WBS 11.4: offer_rate_coll formula (TICKET_BRIEF canonical fact and PRD-08): offer_rate_coll = send_amount / (collection_usd - collection_margin_usd). For numeric example: send_amount=51012 KRW, collection_usd=39.2449 USD, collection_margin_usd=0.3924 USD (m_a=0.01 * 39.2449), offer_rate_coll = 51012 / (39.2449 - 0.3924) = 51012 / 38.8525 = 1313.26 (approx). cross_rate = target_payout / send_amount = 50000 / 51012 = 0.9802. These are computed by the rate engine and stored as locked values; the detail view displays them directly from DB (no recomputation). Test that the display service does not recompute these but reads them verbatim from the entity.
**Steps:** Create TransactionDetailRatePrecisionTest; Test 1: entity with offer_rate_coll=1313.2600 (8dp) -> DTO.amountFields.offer_rate=1313.2600 (no rounding by service); Test 2: entity with cross_rate=0.98020000 -> DTO.amountFields.cross_rate=0.98020000; Test 3: entity with offer_rate_coll=null (same-ccy) -> DTO lockedPoolFields=null, no NPE; Test 4: entity with cross_rate=null (same-ccy) -> DTO amountFields.cross_rate=null, handled gracefully; Assert that the service does NOT call any rate-engine computation methods (verify via Mockito verifyNoInteractions on any RateEngine component)
**Deliverable:** TransactionDetailRatePrecisionTest with 4 test methods verifying pass-through of locked rate values
**Acceptance / logic checks:**
- Test 1: DTO.amountFields.offer_rate = BigDecimal(1313.2600) - same scale as entity value
- Test 2: DTO.amountFields.cross_rate = BigDecimal(0.98020000)
- Test 3: no NullPointerException; DTO.lockedPoolFields=null
- Test 4: DTO.amountFields.cross_rate=null with no exception
- RateEngine component is never invoked (Mockito verifyNoInteractions passes)
**Depends on:** 11.4-T04

### 11.4-T26 — Frontend unit tests for TransactionDetailPage: loading, 404, and data rendering  _(50 min)_
**Context:** WBS 11.4: React component tests for TransactionDetailPage (from 11.4-T20). Use React Testing Library and Mock Service Worker (MSW) to mock the GET /v1/partner/transactions/{hubTxnRef} endpoint. Test scenarios: (A) loading state shows skeleton loaders; (B) successful 200 response renders all 4 sections with correct data; (C) 404 response renders 'Transaction not found' message and no section content; (D) 401 response triggers navigation to /login. For scenario B, use a mock response with is_same_ccy_shortcircuit=false to verify LockedPoolSection renders, and assert offer_rate value is visible in the DOM.
**Steps:** Set up MSW handlers for GET /v1/partner/transactions/:hubTxnRef with 200, 404, and 401 responses; Test A: render TransactionDetailPage, assert skeleton elements present before response resolves; Test B: resolve with full mock response (include offer_rate=1299.50, collection_usd=100.0000, eventTrail with 4 steps), assert all key values visible in DOM; Test C: resolve with 404, assert 'Transaction not found' text present, no amount rows rendered; Test D: resolve with 401, assert navigate('/login') called
**Deliverable:** TransactionDetailPage.test.tsx with 4 test cases
**Acceptance / logic checks:**
- Test A passes: skeleton loader elements visible during pending state
- Test B passes: 'KRW 50,000' visible, 'USD 100.0000' visible, '1,299.50' (offer_rate) visible
- Test C passes: 'Transaction not found' text in DOM, no USD amount rows
- Test D passes: useNavigate called with '/login'
- All 4 tests pass with no console errors
**Depends on:** 11.4-T20

### 11.4-T27 — Add deep-link URL support: direct navigation to transaction detail by URL  _(30 min)_
**Context:** WBS 11.4: PRD-08 §5 states the detail page is accessible at direct URL /{partner_slug}/transactions/{hub_txn_ref}. This means the page must be bookmarkable and shareable. When navigating directly (page refresh or external link), the React app must still fetch the transaction and render correctly. The /{partner_slug} prefix is the partner's URL slug (not the partner_id). The backend must accept hub_txn_ref as a path parameter (already done in T05) but also support the partner_slug -> partner_id resolution. If hub_txn_ref belongs to a different partner than the slug implies, return 404.
**Steps:** Verify the React Router route /{partnerSlug}/transactions/:hubTxnRef exists (from T20) and renders TransactionDetailPage on direct navigation; Confirm the backend endpoint resolves partner_id from JWT claims and does NOT use partnerSlug to resolve the partner (the slug in URL is cosmetic; the JWT is authoritative); Add backend integration test: JWT for partner_id=1 (slug='gmereq') accessing /gmereq/transactions/HUB-P1-001 returns 200; same JWT accessing /differentslug/transactions/HUB-P1-001 also returns 200 (slug is ignored; JWT is authoritative); Add integration test: direct navigation (simulated via test HTTP client with no referrer) to the detail URL returns 200 with correct data
**Deliverable:** Integration test confirming direct URL navigation works; documentation comment in controller confirming slug-in-URL is cosmetic
**Acceptance / logic checks:**
- GET /v1/partner/transactions/HUB-P1-001 with valid JWT returns 200 regardless of any slug prefix (slug not in the API path)
- Direct browser navigation to the React route /{partnerSlug}/transactions/HUB-001 triggers the API call and renders the page
- Page renders correctly on browser refresh (no blank page due to SPA route handling)
- 404 is still returned if hub_txn_ref does not belong to the JWT partner_id
**Depends on:** 11.4-T11, 11.4-T20

### 11.4-T28 — Verify rate-immutability: locked values never change after commit in detail view  _(40 min)_
**Context:** WBS 11.4: Canonical rule (TICKET_BRIEF and SEC-09 §5.4): once a transaction reaches transaction_committed (step 6 of the 8-step trail), all USD pool values and derived rates are permanently recorded and must never be updated. The transaction detail view must reflect these locked values. If treasury rates or margins change after commit, the detail page must still show the original committed values. Test this by updating treasury rates after a committed transaction and verifying the detail API still returns the original locked values.
**Steps:** Write integration test using Testcontainers Postgres: (1) create and commit a transaction with cost_rate_coll=1300.0 and offer_rate_coll=1313.26; (2) update the treasury_rate table to set usd_krw=1500.0; (3) call GET /v1/partner/transactions/{ref} and assert offer_rate still equals 1313.26 (original locked value) not any new derived value; Verify the detail service reads rate values from the transaction table (locked columns), not from treasury_rate or rate_quote tables at query time; Add a comment in TransactionDetailService confirming it reads locked columns only
**Deliverable:** Rate-immutability integration test + service-layer comment confirming locked-column reads
**Acceptance / logic checks:**
- After treasury rate update from 1300 to 1500, detail API still returns offer_rate=1313.26
- Detail API returns send_amount equal to original locked send_amount (not recomputed from new rate)
- Service reads from transaction.offer_rate_coll (locked column), not treasury_rate table
- Integration test passes
- Comment in TransactionDetailService source states 'reads locked rate columns - do not substitute treasury rate'
**Depends on:** 11.4-T11


## WBS 11.5 — Prefunding/balance & statements
### 11.5-T01 — Add DB migration: partner_statement table for monthly settlement statements  _(30 min)_
**Context:** GMEPay+ Partner Portal (PRD-08 §7) requires downloadable monthly settlement statements for OVERSEAS partners. Statements cover a calendar month and have statuses: Draft, Confirmed, Disputed. The table must record period, settlement date, totals, status, and references to generated file paths. Statements are retained for 7 years (SEC-09 data retention). partner_id comes from the authenticated session — never from user input.
**Steps:** Create migration file db/migrations/V<next>__create_partner_statement.sql.; Define table partner_statement with columns: id BIGINT PK, partner_id BIGINT FK partner NOT NULL, period_year SMALLINT NOT NULL, period_month SMALLINT NOT NULL (CHECK 1-12), settlement_date DATE, total_transactions INTEGER NOT NULL DEFAULT 0, total_payout_amount DECIMAL(20,4), total_payout_ccy CHAR(3), total_collection_amount DECIMAL(20,4), total_collection_ccy CHAR(3), total_service_fees DECIMAL(20,4), total_service_fees_ccy CHAR(3), status VARCHAR(20) NOT NULL CHECK IN ('DRAFT','CONFIRMED','DISPUTED') DEFAULT 'DRAFT', pdf_file_path VARCHAR(500), csv_file_path VARCHAR(500), generated_at TIMESTAMPTZ, confirmed_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW().; Add UNIQUE constraint on (partner_id, period_year, period_month).; Add index on (partner_id, period_year DESC, period_month DESC) for portal list queries.; Apply migration to dev DB and verify schema with describe table.
**Deliverable:** Migration file db/migrations/V<N>__create_partner_statement.sql applied successfully to dev DB with all columns, constraints, and indexes verified.
**Acceptance / logic checks:**
- UNIQUE constraint on (partner_id, period_year, period_month) rejects a duplicate insert for the same partner and period.
- CHECK constraint on status rejects any value outside DRAFT, CONFIRMED, DISPUTED.
- CHECK constraint on period_month rejects values outside 1-12.
- Index on (partner_id, period_year DESC, period_month DESC) is present in pg_indexes.
- Inserting a row with status=CONFIRMED, period_year=2026, period_month=5, partner_id=1 and then SELECT returns all columns correctly with DECIMAL precision preserved.

### 11.5-T02 — Add DB migration: partner_statement_line table for per-transaction fee breakdown  _(25 min)_
**Context:** PRD-08 §7.2 requires a per-transaction line-level breakdown within each settlement statement showing: date, hub_txn_ref, target_payout+currency, collection_amount+currency, service_charge, offer_rate. Internal fields (m_a, m_b, margin USD, cost_rate_coll, cost_rate_pay, GME revenue) are NEVER stored here. Each line links to a parent partner_statement row. Table must support bulk insert of up to 50,000 rows per statement generation job.
**Steps:** Create migration db/migrations/V<next>__create_partner_statement_line.sql.; Define table partner_statement_line with columns: id BIGINT PK, statement_id BIGINT FK partner_statement NOT NULL, txn_date DATE NOT NULL, hub_txn_ref VARCHAR(64) NOT NULL, target_payout DECIMAL(20,4) NOT NULL, payout_ccy CHAR(3) NOT NULL, collection_amount DECIMAL(20,4) NOT NULL, collection_ccy CHAR(3) NOT NULL, service_charge DECIMAL(20,4) NOT NULL, service_charge_ccy CHAR(3) NOT NULL, offer_rate DECIMAL(20,8) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW().; Add index on (statement_id) for bulk retrieval.; Add UNIQUE constraint on (statement_id, hub_txn_ref) to prevent duplicate lines per statement.; Apply migration and verify with explain analyze on a SELECT WHERE statement_id = X.
**Deliverable:** Migration file db/migrations/V<N>__create_partner_statement_line.sql applied successfully; table partner_statement_line exists with all constraints.
**Acceptance / logic checks:**
- UNIQUE constraint on (statement_id, hub_txn_ref) prevents a second insert of the same txn into the same statement.
- Index on statement_id is present and used by EXPLAIN ANALYZE SELECT WHERE statement_id = 1.
- offer_rate column is DECIMAL(20,8) not FLOAT (verified via information_schema.columns).
- Inserting 1000 rows in a single multi-row INSERT completes without error.
- Columns m_a, m_b, collection_margin_usd, payout_margin_usd, cost_rate_coll, cost_rate_pay are absent from the table definition.
**Depends on:** 11.5-T01

### 11.5-T03 — Add DB migration: partner_notification table for in-portal alerts  _(25 min)_
**Context:** PRD-08 §9.1 requires in-portal notifications stored per partner-user. Notification types: LOW_BALANCE (High), STATEMENT_READY (Info), SETTLEMENT_CONFIRMED (Info), WEBHOOK_FAILURE (Warning). Notifications are per-user (dismissing clears for that user only); unread count drives the bell icon. A portal_user_id FK links to the existing portal_user table.
**Steps:** Create migration db/migrations/V<next>__create_partner_notification.sql.; Define table partner_notification with columns: id BIGINT PK, portal_user_id BIGINT FK portal_user NOT NULL, partner_id BIGINT FK partner NOT NULL, notification_type VARCHAR(30) NOT NULL CHECK IN ('LOW_BALANCE','STATEMENT_READY','SETTLEMENT_CONFIRMED','WEBHOOK_FAILURE'), severity VARCHAR(10) NOT NULL CHECK IN ('HIGH','INFO','WARNING'), title VARCHAR(255) NOT NULL, body TEXT, reference_id VARCHAR(64), is_read BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), read_at TIMESTAMPTZ.; Add index on (portal_user_id, is_read, created_at DESC) for unread count and notification list.; Add index on (partner_id, notification_type, created_at DESC) for event fan-out lookups.; Apply migration.
**Deliverable:** Migration file creating partner_notification table applied; all constraints, indexes, and CHECK values verified.
**Acceptance / logic checks:**
- CHECK on notification_type rejects a value of UNKNOWN_TYPE.
- CHECK on severity rejects a value of CRITICAL.
- Index on (portal_user_id, is_read, created_at DESC) appears in pg_indexes.
- Inserting two notifications for different portal_user_ids with the same partner_id succeeds — they are independent rows.
- SELECT COUNT(*) WHERE portal_user_id=X AND is_read=FALSE returns 0 after a batch UPDATE is_read=TRUE.

### 11.5-T04 — Implement PartnerBalanceRepository: real-time balance and ledger queries  _(45 min)_
**Context:** The prefunding_account table (one row per OVERSEAS partner) holds balance DECIMAL(20,4) in USD. The prefunding_ledger_entry table (append-only) has columns: id, account_id FK, txn_ref VARCHAR(64), entry_type VARCHAR(20) CHECK IN ('DEBIT_PAYMENT','DEBIT_REVERSAL','CREDIT_TOPUP','CREDIT_REVERSAL'), amount DECIMAL(20,4), balance_after DECIMAL(20,4), note VARCHAR(255), created_at TIMESTAMPTZ. The repository must enforce partner_id isolation at the query layer, not just in business logic (PRD-08 §2.2).
**Steps:** Create PartnerBalanceRepository (Spring Data JPA or JDBC) in package com.gmepayplus.portal.repository.; Implement findAccountByPartnerId(Long partnerId): returns Optional<PrefundingAccount>; query includes WHERE partner_id = :partnerId.; Implement findLedgerEntries(Long partnerId, Instant from, Instant to, Pageable pageable): joins prefunding_account to filter by partner_id, orders by created_at DESC, supports pagination.; Implement countLedgerEntries(Long partnerId, Instant from, Instant to): for pagination total count.; Write a repository integration test that inserts accounts for partner A and partner B, then asserts findAccountByPartnerId(A) never returns partner B's account.
**Deliverable:** PartnerBalanceRepository class with 3 methods; integration test confirming partner isolation.
**Acceptance / logic checks:**
- findAccountByPartnerId(partnerB_id) called in a session authenticated as partnerA returns Optional.empty().
- findLedgerEntries with from=2026-01-01 and to=2026-01-31 returns only entries with created_at in that range.
- Query plan for findLedgerEntries uses the index on prefunding_account.partner_id (verified via EXPLAIN).
- Pagination: requesting page=0, size=50 returns at most 50 rows; requesting page=1 returns the next batch.
- Integration test passes with two partners having separate ledger histories.
**Depends on:** 11.5-T01, 11.5-T02

### 11.5-T05 — Implement GET /portal/v1/balance REST endpoint  _(40 min)_
**Context:** PRD-08 §6.1 defines the real-time balance panel for OVERSEAS partners: available_balance USD, low_balance_threshold USD, status indicator (GREEN above threshold, RED at or below), last_updated timestamp. Partner type is derived from the JWT claims (partner_type=OVERSEAS required). LOCAL partners receive HTTP 403. The public Partner API GET /v1/balance (API-05 §4.8) is a separate endpoint for machine clients; this portal endpoint is for browser sessions and returns additional UI fields. Response times must be under 1 second P95.
**Steps:** Create PortalBalanceController in com.gmepayplus.portal.controller with mapping GET /portal/v1/balance.; Extract partner_id and partner_type from the authenticated JWT — never from URL params or request body.; If partner_type == LOCAL return HTTP 403 with body: {error: 'PREFUNDING_NOT_APPLICABLE'}.; Call PartnerBalanceRepository.findAccountByPartnerId; if not found return HTTP 404.; Build response DTO: {partner_id, available_balance_usd, low_balance_threshold_usd, status: 'GREEN'|'RED', last_updated (ISO-8601 UTC), currency: 'USD'} where status=RED if available_balance_usd <= low_balance_threshold_usd.; Return HTTP 200 with the DTO.
**Deliverable:** GET /portal/v1/balance endpoint; integration tests covering OVERSEAS partner (200), LOCAL partner (403), and partner with no prefunding account (404).
**Acceptance / logic checks:**
- OVERSEAS partner with balance=15000.00, threshold=10000.00 receives status=GREEN.
- OVERSEAS partner with balance=9999.99, threshold=10000.00 receives status=RED (boundary: at threshold is RED, not GREEN).
- OVERSEAS partner with balance=10000.00, threshold=10000.00 receives status=RED (at-threshold is RED per PRD-08 §6.1).
- LOCAL partner receives HTTP 403 with error code PREFUNDING_NOT_APPLICABLE.
- Response available_balance_usd field is a string-encoded decimal with 2 decimal places, e.g. 48234.56.
**Depends on:** 11.5-T04

### 11.5-T06 — Implement GET /portal/v1/ledger REST endpoint (paginated balance movements)  _(45 min)_
**Context:** PRD-08 §6.2 defines the balance ledger: columns date/time (KST displayed, stored UTC), type (Debit/Credit/Adjustment), reference (hub_txn_ref for debits, top-up ref for credits), amount USD (positive for credits, negative for debits), running balance USD. Default view: last 30 days; filterable by date range max 1 year. Entry types from prefunding_ledger_entry: DEBIT_PAYMENT and DEBIT_REVERSAL map to Debit; CREDIT_TOPUP maps to Credit; CREDIT_REVERSAL maps to Adjustment. Page size options: 25, 50, 100; default 50.
**Steps:** Create GET /portal/v1/ledger endpoint in PortalBalanceController with query params: from_date (ISO date, default today-30d), to_date (ISO date, default today), page (default 0), page_size (default 50, allowed 25|50|100).; Validate from_date and to_date: to_date >= from_date, range <= 366 days; return HTTP 400 with error detail if invalid.; Call PartnerBalanceRepository.findLedgerEntries(partnerId, from, to, pageable).; Map each prefunding_ledger_entry to response DTO: {entry_id, occurred_at_utc, occurred_at_kst, movement_type ('DEBIT'|'CREDIT'|'ADJUSTMENT'), reference, amount_usd (negative for debits), balance_after_usd, note}.; Return HTTP 200 with {data: [...], page, page_size, total_count, from_date, to_date}.; Return HTTP 403 for LOCAL partners.
**Deliverable:** GET /portal/v1/ledger endpoint with pagination, date filtering, and movement_type mapping; integration tests for date range validation and type mapping.
**Acceptance / logic checks:**
- DEBIT_PAYMENT entry maps to movement_type=DEBIT with amount_usd negative, e.g. entry amount=35.77 returns amount_usd=-35.77.
- CREDIT_TOPUP entry maps to movement_type=CREDIT with amount_usd positive.
- CREDIT_REVERSAL entry maps to movement_type=ADJUSTMENT.
- Request with from_date=2025-01-01, to_date=2026-06-01 (367 days) returns HTTP 400 with error=DATE_RANGE_TOO_LARGE.
- Request with to_date before from_date returns HTTP 400.
- LOCAL partner receives HTTP 403.
**Depends on:** 11.5-T04

### 11.5-T07 — Implement StatementGenerationService: aggregate transactions into partner_statement  _(50 min)_
**Context:** PRD-08 §7.1 and Assumption A-05: Statement generation is triggered by a scheduled job on T+1 business day after period close. The service aggregates committed transactions for a given partner and calendar month. Aggregate fields: total_transactions (count), total_payout_amount (SUM target_payout), total_collection_amount (SUM collection_amount), total_service_fees (SUM service_charge). Statement is created in DRAFT status. Do NOT include transactions with status != COMMITTED (i.e. exclude DECLINED, CANCELLED, PENDING). Partner_id isolation enforced at query level.
**Steps:** Create StatementGenerationService in com.gmepayplus.portal.service.; Implement generateStatement(Long partnerId, int year, int month): query transactions WHERE partner_id=:pid AND txn_status='COMMITTED' AND DATE_TRUNC('month', txn_timestamp) = target_month.; Aggregate: count rows, SUM target_payout (grouped by payout_ccy), SUM collection_amount (grouped by collection_ccy), SUM service_charge.; Create a partner_statement row in DRAFT status with computed totals; use INSERT ... ON CONFLICT (partner_id, period_year, period_month) DO NOTHING to handle re-runs gracefully.; Bulk-insert partner_statement_line rows for all matching transactions (fields: txn_date, hub_txn_ref, target_payout, payout_ccy, collection_amount, collection_ccy, service_charge, service_charge_ccy, offer_rate). Exclude m_a, m_b, margin USD fields.; Return the created statement id.
**Deliverable:** StatementGenerationService.generateStatement method; unit tests with known transaction set verifying totals.
**Acceptance / logic checks:**
- Given 3 committed transactions with target_payout=50000 KRW each, total_payout_amount=150000.
- Given 3 committed transactions with collection_amount=35.77 USD each, total_collection_amount=107.31.
- A CANCELLED transaction in the period is excluded from totals and line items.
- Re-running generateStatement for the same partner+period does not create a duplicate partner_statement row.
- partner_statement_line rows contain offer_rate but NOT m_a, m_b, collection_margin_usd, or payout_margin_usd.
**Depends on:** 11.5-T01, 11.5-T02

### 11.5-T08 — Implement StatementConfirmationService: transition statement DRAFT -> CONFIRMED  _(40 min)_
**Context:** PRD-08 §7.1 lists statement statuses: Draft, Confirmed, Disputed. GME Ops triggers confirmation via the Admin System (not partner-facing). A statement can only be confirmed if it is currently DRAFT. On confirmation: set status=CONFIRMED, confirmed_at=NOW(), update updated_at. This transition is audited (actor, timestamp). Confirmed statements are immutable — no further edits allowed. Disputed status is set by a separate Ops workflow (out of scope here; just enforce the guard).
**Steps:** Create StatementConfirmationService in com.gmepayplus.portal.service.; Implement confirmStatement(Long statementId, String operatorId): fetch partner_statement by id; throw StatementNotFoundException if absent; throw InvalidStatementStateException if status != DRAFT.; Execute UPDATE partner_statement SET status='CONFIRMED', confirmed_at=NOW(), updated_at=NOW() WHERE id=:id AND status='DRAFT'; check affected rows = 1 (optimistic guard against race).; Write an audit_log entry: entity=partner_statement, entity_id=statementId, action=CONFIRM, actor=operatorId, previous_value=DRAFT, new_value=CONFIRMED, timestamp=NOW().; Return the updated statement.
**Deliverable:** StatementConfirmationService with confirmStatement method; unit tests for happy path, already-confirmed guard, and not-found guard.
**Acceptance / logic checks:**
- Confirming a DRAFT statement sets status=CONFIRMED and confirmed_at is populated.
- Calling confirmStatement on a CONFIRMED statement throws InvalidStatementStateException (idempotency guard).
- Calling confirmStatement on a non-existent id throws StatementNotFoundException.
- An audit_log row is created with action=CONFIRM, actor=operatorId, previous_value=DRAFT.
- Concurrent calls: two threads simultaneously calling confirmStatement on the same DRAFT statement result in exactly one success and one InvalidStatementStateException (optimistic lock via affected-rows check).
**Depends on:** 11.5-T01

### 11.5-T09 — Implement GET /portal/v1/statements REST endpoint (statement list)  _(35 min)_
**Context:** PRD-08 §7.1 defines the statement list view for the partner: period (year/month), settlement_date, total_transactions, total_payout_volume (with currency), total_collection (with currency), total_service_fees (with currency), status (Draft/Confirmed/Disputed), download links (PDF and CSV). Only statements belonging to the authenticated partner are returned. Partner_id is taken from JWT claims only. Sorted by period descending. OVERSEAS partners only — LOCAL partners receive 403.
**Steps:** Create PortalStatementController with GET /portal/v1/statements, query params: year (optional int), page (default 0), page_size (default 12, max 36).; Extract partner_id from JWT; return 403 for LOCAL partners.; Query partner_statement WHERE partner_id=:pid AND (year=:year if provided) ORDER BY period_year DESC, period_month DESC with pagination.; Map each row to response DTO: {statement_id, period ('2026-05'), settlement_date, total_transactions, total_payout_amount, total_payout_ccy, total_collection_amount, total_collection_ccy, total_service_fees, total_service_fees_ccy, status, pdf_download_url (present only if pdf_file_path is not null), csv_download_url (present only if csv_file_path is not null)}.; Return HTTP 200 with {data: [...], page, page_size, total_count}.
**Deliverable:** GET /portal/v1/statements endpoint; integration tests for partner isolation, ordering, and optional year filter.
**Acceptance / logic checks:**
- Partner A calling GET /portal/v1/statements never receives statements belonging to Partner B.
- Statements are returned in descending period order: 2026-05 before 2026-04 before 2026-03.
- Filter year=2025 returns only statements with period_year=2025.
- pdf_download_url is absent from the DTO when pdf_file_path is NULL in the database.
- LOCAL partner receives HTTP 403.
**Depends on:** 11.5-T07

### 11.5-T10 — Implement GET /portal/v1/statements/{id}/lines REST endpoint (fee breakdown)  _(35 min)_
**Context:** PRD-08 §7.2 requires line-level breakdown per statement: date, hub_txn_ref, target_payout+currency, collection_amount+currency, service_charge, offer_rate. Internal fields (m_a, m_b, margin USD, cost_rate_coll, cost_rate_pay, GME revenue) must never appear in this response. Accessing another partner's statement returns HTTP 404 (not 403) per PRD-08 §5 data isolation principle (do not confirm existence). Max page_size=100; default 50.
**Steps:** Add GET /portal/v1/statements/{id}/lines to PortalStatementController with query params page (default 0), page_size (default 50, max 100).; Extract partner_id from JWT; query partner_statement WHERE id=:id AND partner_id=:pid; if not found return HTTP 404.; Query partner_statement_line WHERE statement_id=:id ORDER BY txn_date ASC, id ASC with pagination.; Map to DTO: {line_id, txn_date, hub_txn_ref, target_payout, payout_ccy, collection_amount, collection_ccy, service_charge, service_charge_ccy, offer_rate}.; Return HTTP 200 with {statement_id, data:[...], page, page_size, total_count}.
**Deliverable:** GET /portal/v1/statements/{id}/lines endpoint; integration tests verifying field exclusion and cross-partner 404.
**Acceptance / logic checks:**
- Response DTO contains exactly the fields listed in steps — no m_a, m_b, collection_margin_usd, payout_margin_usd fields.
- Accessing statement_id owned by Partner B while authenticated as Partner A returns HTTP 404.
- offer_rate is a string-encoded decimal with 8 significant digits, e.g. 0.00070300.
- Requesting page_size=150 returns HTTP 400 with error=INVALID_PAGE_SIZE.
- Lines are ordered by txn_date ASC.
**Depends on:** 11.5-T07, 11.5-T09

### 11.5-T11 — Implement CSV export for settlement statement lines  _(45 min)_
**Context:** PRD-08 §7.3 requires a machine-readable CSV of the statement line-level data (same fields as §7.2: txn_date, hub_txn_ref, target_payout, payout_ccy, collection_amount, collection_ccy, service_charge, offer_rate). File name format: gmepay_stmt_{partner_id}_{YYYY-MM}.csv. Internal margin fields are excluded. The endpoint streams the response to avoid holding large result sets in memory; all lines for the statement are included (no pagination cap for this export). Retained for 7 years per SEC-09.
**Steps:** Add GET /portal/v1/statements/{id}/export/csv to PortalStatementController.; Validate partner owns the statement (same as T10 — return 404 if not found or wrong partner).; Set response headers: Content-Type: text/csv; charset=UTF-8, Content-Disposition: attachment; filename=gmepay_stmt_{partner_id}_{YYYY-MM}.csv.; Stream all partner_statement_line rows for the statement using a scrollable cursor or JPA stream() to avoid OOM; write header row then data rows.; CSV header row: txn_date,hub_txn_ref,target_payout,payout_ccy,collection_amount,collection_ccy,service_charge,service_charge_ccy,offer_rate.; Test with a statement containing 5000 lines; verify file completeness.
**Deliverable:** GET /portal/v1/statements/{id}/export/csv streaming endpoint; integration test with 5000-line statement verifying row count and field exclusion.
**Acceptance / logic checks:**
- Downloaded CSV for a statement with 5000 lines contains exactly 5001 rows (1 header + 5000 data rows).
- Filename matches pattern gmepay_stmt_{partner_id}_{YYYY-MM}.csv, e.g. gmepay_stmt_42_2026-05.csv.
- CSV does not contain columns m_a, m_b, collection_margin_usd, payout_margin_usd.
- Cross-partner access returns HTTP 404.
- Amount fields (target_payout, collection_amount, service_charge) are numeric strings, not currency-formatted with commas (to avoid CSV parsing issues).
**Depends on:** 11.5-T07, 11.5-T10

### 11.5-T12 — Implement PDF statement generation using a template renderer  _(55 min)_
**Context:** PRD-08 §7.3 requires a formatted PDF statement suitable for accounting records. Contents: partner name, period (e.g. May 2026), summary totals (total_transactions, total_payout_volume, total_collection, total_service_fees), and transaction list (same columns as §7.2). Layout note: OI-U4 (open item) means exact header/footer/logo placement is TBD — implement with a placeholder GMEPay+ header and leave a named template placeholder for logo. Internal fields excluded. PDF is generated at statement confirmation time and stored at pdf_file_path. Use a Java PDF library (e.g. iText 7 or OpenPDF).
**Steps:** Add PdfStatementGenerator service in com.gmepayplus.portal.service.statement.; Implement generatePdf(Long statementId): load partner_statement and all partner_statement_line rows; instantiate PDF document with A4 page size.; Render header section: GMEPay+ logo placeholder (image slot), partner_name, period_label (e.g. Settlement Statement — May 2026), generation_date.; Render summary table: 4-row table with label and value for total_transactions, total_payout_amount+ccy, total_collection_amount+ccy, total_service_fees+ccy.; Render transaction table with columns: Date, Transaction ID, Payout Amount, Collection Amount, Service Charge, Offer Rate. For statements >500 lines paginate across PDF pages.; Store PDF bytes to file storage (local path for dev; S3/blob storage path for prod); update partner_statement.pdf_file_path.
**Deliverable:** PdfStatementGenerator service; integration test generating a PDF for a 10-transaction statement and verifying it is a valid PDF with correct page count and text content.
**Acceptance / logic checks:**
- Generated file is a valid PDF (readable by a PDF parser without error).
- PDF contains the partner name and period string, e.g. Settlement Statement — May 2026.
- Summary section shows total_transactions, total_payout, total_collection, total_service_fees.
- Internal fields m_a, m_b, margin USD are absent from all rendered content.
- partner_statement.pdf_file_path is updated to a non-null value after generation.
**Depends on:** 11.5-T07

### 11.5-T13 — Implement GET /portal/v1/statements/{id}/export/pdf endpoint  _(30 min)_
**Context:** PRD-08 §7.3 requires a PDF download button per statement. The endpoint validates partner ownership (404 for wrong partner), then serves the pre-generated PDF from pdf_file_path. If the PDF has not yet been generated (pdf_file_path IS NULL), return HTTP 202 with body {status:'GENERATING', retry_after_seconds:60} to indicate the file is not yet ready (statements available T+1 business day after period close per Assumption A-05). The endpoint never regenerates on the fly — generation is an async job. Retained 7 years.
**Steps:** Add GET /portal/v1/statements/{id}/export/pdf to PortalStatementController.; Validate partner owns statement (404 if not).; If partner_statement.pdf_file_path IS NULL return HTTP 202 with body {status:'GENERATING', retry_after_seconds:60}.; Load file bytes from storage at pdf_file_path; if file missing on disk return HTTP 500 with error=STATEMENT_FILE_MISSING (triggers ops alert).; Set response headers: Content-Type: application/pdf, Content-Disposition: attachment; filename=gmepay_stmt_{partner_id}_{YYYY-MM}.pdf.; Stream file bytes in response.
**Deliverable:** GET /portal/v1/statements/{id}/export/pdf endpoint; integration tests for PDF-ready (200), not-yet-generated (202), and cross-partner (404).
**Acceptance / logic checks:**
- Statement with pdf_file_path populated returns HTTP 200 with Content-Type: application/pdf.
- Statement with pdf_file_path IS NULL returns HTTP 202 with body containing retry_after_seconds=60.
- Cross-partner access returns HTTP 404.
- Response Content-Disposition filename matches pattern gmepay_stmt_{partner_id}_{YYYY-MM}.pdf.
- File bytes in response match the bytes at pdf_file_path on disk (SHA-256 of response body equals SHA-256 of file).
**Depends on:** 11.5-T12, 11.5-T09

### 11.5-T14 — Implement scheduled StatementGenerationJob to run T+1 business day after period close  _(50 min)_
**Context:** PRD-08 Assumption A-05: Statement generation is triggered by a scheduled job on T+1 business day after settlement period close. For a calendar-month period closing on the last day of the month, T+1 business day means the next non-weekend non-holiday working day. The job must process all OVERSEAS partners. OI-P2 notes the ZeroPay afternoon settlement (~19:00 KST) completes before the T+1 job runs. The job is idempotent — re-running it for a period where statements already exist does not create duplicates (INSERT ON CONFLICT DO NOTHING in StatementGenerationService).
**Steps:** Create StatementGenerationJob in com.gmepayplus.portal.job annotated with @Scheduled(cron = '0 0 2 * * *') (2:00 AM KST daily; adjust to UTC offset +9 → 17:00 UTC prior day).; On each run: compute the most recently closed calendar month (if today is T+1 business day after last-month-end, proceed; otherwise skip).; Fetch all OVERSEAS partner IDs from the partner repository.; For each partner, call StatementGenerationService.generateStatement(partnerId, year, month); catch and log exceptions per partner without stopping the overall job.; After generation, call PdfStatementGenerator.generatePdf(statementId) for each newly created statement.; Log job start, partner count processed, success count, and failure count.
**Deliverable:** StatementGenerationJob scheduled class; integration test that mocks the clock to T+1 business day after a period close and verifies statements are created for all OVERSEAS partners.
**Acceptance / logic checks:**
- Running the job when today is the first business day after a period close creates one partner_statement row per OVERSEAS partner with status=DRAFT.
- Running the job a second time for the same period does not create duplicate partner_statement rows.
- A LOCAL partner has no partner_statement row created by the job.
- Job failure for one partner (simulated by throwing an exception) does not prevent other partners from being processed.
- pdf_file_path is populated on the partner_statement after the job completes successfully.
**Depends on:** 11.5-T07, 11.5-T12

### 11.5-T15 — Implement NotificationService: create and fan-out in-portal notifications  _(45 min)_
**Context:** PRD-08 §9.1 defines four in-portal notification types: LOW_BALANCE (severity=HIGH), STATEMENT_READY (INFO), SETTLEMENT_CONFIRMED (INFO), WEBHOOK_FAILURE (WARNING). Notifications are stored per portal_user via partner_notification table. All portal users for a given partner receive the notification (fan-out by partner_id). Dismissing marks is_read=TRUE for that user only. Unread count is shown on the bell icon. This service is called by the balance deduction path (for LOW_BALANCE) and by the statement confirmation path (for STATEMENT_CONFIRMED).
**Steps:** Create NotificationService in com.gmepayplus.portal.service.; Implement createNotification(Long partnerId, NotificationType type, String title, String body, String referenceId): query all active portal_user IDs for the partner; bulk-insert one partner_notification row per user with is_read=FALSE.; Implement markRead(Long notificationId, Long portalUserId): UPDATE partner_notification SET is_read=TRUE, read_at=NOW() WHERE id=:id AND portal_user_id=:uid; return updated entity.; Implement countUnread(Long portalUserId): SELECT COUNT(*) WHERE portal_user_id=:uid AND is_read=FALSE.; Implement listNotifications(Long portalUserId, Pageable pageable): SELECT WHERE portal_user_id=:uid ORDER BY created_at DESC.; Ensure markRead rejects if portal_user_id does not match (returns NotFoundException) — prevents one user dismissing another's notifications.
**Deliverable:** NotificationService with 4 methods; unit tests verifying fan-out to all partner users and per-user dismiss isolation.
**Acceptance / logic checks:**
- createNotification for a partner with 3 portal users inserts exactly 3 partner_notification rows.
- markRead called by user A on a notification belonging to user B throws NotFoundException and does not mutate the row.
- countUnread returns 0 after all notifications for a user are marked read.
- listNotifications returns notifications ordered by created_at DESC.
- A second call to markRead on an already-read notification is idempotent (no error, read_at unchanged).
**Depends on:** 11.5-T03

### 11.5-T16 — Implement GET /portal/v1/notifications and PATCH /portal/v1/notifications/{id}/read endpoints  _(35 min)_
**Context:** PRD-08 §9.1 defines the notification bell icon and notification list. GET returns the authenticated user's notifications (unread first, then read, max 50); also returns unread_count for the bell badge. PATCH marks a single notification as read. Partner isolation enforced via portal_user_id from JWT — never from request body. Notifications are per-user; the portal_user_id must match the authenticated session.
**Steps:** Create PortalNotificationController with GET /portal/v1/notifications: extract portal_user_id from JWT; call NotificationService.listNotifications; call countUnread; return {unread_count, data: [...]}.; Map notification to DTO: {notification_id, type, severity, title, body, reference_id, is_read, created_at}.; Add PATCH /portal/v1/notifications/{id}/read: extract portal_user_id from JWT; call NotificationService.markRead(id, portalUserId); return 200 with updated DTO.; Return HTTP 404 if notification_id does not belong to the authenticated user.; Add integration tests: user A cannot mark user B's notification as read; unread_count decrements after marking read.
**Deliverable:** GET /portal/v1/notifications and PATCH /portal/v1/notifications/{id}/read endpoints; integration tests for user isolation and unread count.
**Acceptance / logic checks:**
- GET returns unread_count=3 for a user with 3 unread notifications.
- After PATCH marking one notification read, subsequent GET returns unread_count=2.
- PATCH on a notification_id belonging to another portal user returns HTTP 404.
- GET returns notifications ordered: unread first (is_read=FALSE) then read (is_read=TRUE), within each group by created_at DESC.
- DTO does not expose partner_notification.portal_user_id in the response body (internal field).
**Depends on:** 11.5-T15

### 11.5-T17 — Implement EmailAlertService: send low-balance and statement-ready emails  _(50 min)_
**Context:** PRD-08 §9.2 requires email notifications to configured alert addresses for: (1) balance drops below threshold — include current balance, threshold, top-up instructions, GME account team contact; (2) balance reaches zero — urgent notice, all overseas payments suspended; (3) statement available — link to portal statement page, period summary; (4) settlement confirmed — confirmation with totals. Alert email addresses (one or more per partner) are stored in a partner_alert_email table (to be assumed present; stored as email VARCHAR(254), partner_id FK, is_active BOOLEAN). Email is sent via SMTP relay or SES. GME Ops manages addresses; partners cannot edit them via portal.
**Steps:** Create EmailAlertService in com.gmepayplus.portal.service.; Implement sendLowBalanceAlert(Long partnerId, BigDecimal currentBalance, BigDecimal threshold).; Implement sendZeroBalanceAlert(Long partnerId).; Implement sendStatementReadyAlert(Long partnerId, int year, int month).; Implement sendSettlementConfirmedAlert(Long partnerId, Long statementId).; Unit test each method with mocked mail sender and assert recipient count, subject pattern, and key body values.
**Deliverable:** EmailAlertService with 4 send methods; unit tests confirming recipient addresses, subject lines, and body content inclusion.
**Acceptance / logic checks:**
- sendLowBalanceAlert called with currentBalance=8500.00, threshold=10000.00 sends to all active alert emails and body contains both values.
- sendZeroBalanceAlert sends to all active alert emails for the partner with subject containing URGENT and body mentioning payment suspension.
- sendStatementReadyAlert sends email with subject containing the period, e.g. 2026-05, and includes a URL path /portal/statements.
- A partner with 2 active alert emails receives 2 separate emails per sendLowBalanceAlert call.
- A partner with no active alert emails logs a warning but does not throw an exception.
**Depends on:** 11.5-T15

### 11.5-T18 — Wire low-balance email and in-portal notification on prefunding deduction  _(45 min)_
**Context:** PRD-08 §3.4 and §9: low-balance alert fires when current_balance drops below low_balance_threshold after a payment. This wiring must be placed after the atomic prefunding deduction (SELECT FOR UPDATE in PrefundingDeductionService). Two actions required: (1) create in-portal LOW_BALANCE notification via NotificationService; (2) send email via EmailAlertService.sendLowBalanceAlert. Zero-balance condition triggers sendZeroBalanceAlert instead. This logic runs in the same transaction as the deduction so that the alert is only sent on committed deductions.
**Steps:** In PrefundingDeductionService (existing service in WBS 11.1-11.3), after a successful deduction, read updated balance from prefunding_account.; Compare updated balance to low_balance_threshold: if balance <= 0 trigger zero-balance alert path; else if balance < low_balance_threshold trigger low-balance alert path; else no alert.; Call NotificationService.createNotification(partnerId, LOW_BALANCE, ...) with body including current balance and threshold.; Call EmailAlertService.sendLowBalanceAlert or sendZeroBalanceAlert asynchronously (use @Async or publish to an internal event queue to avoid blocking the payment response).; Write an integration test: deduct balance from 10500.00 to 9800.00 (below threshold 10000.00) and verify a LOW_BALANCE notification is created and email is queued.; Write a second test: deduct to 0.00 and verify ZERO_BALANCE path fires.
**Deliverable:** Alert wiring in PrefundingDeductionService; integration tests verifying notification creation and email dispatch for sub-threshold and zero-balance deductions.
**Acceptance / logic checks:**
- Deduction leaving balance=9999.99 (threshold=10000.00) creates exactly one LOW_BALANCE partner_notification row per portal user of that partner.
- Deduction leaving balance=0.00 triggers sendZeroBalanceAlert, not sendLowBalanceAlert.
- Deduction leaving balance=15000.00 (above threshold) creates no notification.
- The alert is dispatched asynchronously and does not add more than 20ms to the payment response time (verified by timing the POST /v1/payments response in integration test with async spy).
- Alert fires exactly once per threshold-crossing deduction (not on every subsequent deduction that remains below threshold — check: second deduction from 9500.00 to 9000.00 does not create a second LOW_BALANCE notification in the same session).
**Depends on:** 11.5-T15, 11.5-T17

### 11.5-T19 — Wire STATEMENT_READY and SETTLEMENT_CONFIRMED notifications on statement lifecycle events  _(40 min)_
**Context:** PRD-08 §9: STATEMENT_READY notification fires when a new monthly statement is available (after StatementGenerationJob completes); SETTLEMENT_CONFIRMED notification fires when GME Ops confirms a statement (after StatementConfirmationService.confirmStatement). Both trigger in-portal notification (NotificationService) and email (EmailAlertService). Wiring goes in StatementGenerationJob (post-generation) and StatementConfirmationService (post-confirmation).
**Steps:** In StatementGenerationJob, after each statement is successfully generated and PDF is ready, call NotificationService.createNotification(partnerId, STATEMENT_READY, 'Your May 2026 statement is ready', ..., statementId).; Also call EmailAlertService.sendStatementReadyAlert(partnerId, year, month) asynchronously.; In StatementConfirmationService.confirmStatement, after the status transition to CONFIRMED, call NotificationService.createNotification(partnerId, SETTLEMENT_CONFIRMED, ...) and EmailAlertService.sendSettlementConfirmedAlert asynchronously.; Write integration test for StatementGenerationJob: after job run, assert STATEMENT_READY notification rows exist for all portal users of each OVERSEAS partner.; Write integration test for StatementConfirmationService: after confirm, assert SETTLEMENT_CONFIRMED notification row exists.
**Deliverable:** Notification/email wiring in StatementGenerationJob and StatementConfirmationService; integration tests asserting notification creation on both events.
**Acceptance / logic checks:**
- After StatementGenerationJob processes 2 OVERSEAS partners (each with 2 portal users), 4 STATEMENT_READY partner_notification rows are created (2 partners x 2 users).
- After confirmStatement, exactly 1 SETTLEMENT_CONFIRMED notification row is created per portal user of the partner.
- sendStatementReadyAlert is called once per partner per job run (not per portal user).
- Notifications have reference_id set to the statement_id as a string.
- If PDF generation fails for a partner, STATEMENT_READY notification is NOT sent for that partner (alert only after successful PDF).
**Depends on:** 11.5-T14, 11.5-T15, 11.5-T17

### 11.5-T20 — Implement WEBHOOK_FAILURE in-portal notification after 3 consecutive failures  _(40 min)_
**Context:** PRD-08 §9.1: a WEBHOOK_FAILURE in-portal notification (severity=WARNING) is raised after 3 consecutive webhook delivery failures. The webhook delivery retry logic already exists (API-05 §6.2 — up to 10 retries). The wiring here listens for the 3rd consecutive failure event and calls NotificationService. The webhook_delivery_attempt table (or equivalent) tracks attempt number and outcome. Trigger: after saving a failed attempt where consecutive_failure_count = 3 for the same payment event.
**Steps:** In WebhookDeliveryService (existing), after recording a failed delivery attempt, compute consecutive_failure_count: count of consecutive FAILED attempts for the same (partner_id, event_id) with no SUCCESS in between.; If consecutive_failure_count == 3, call NotificationService.createNotification(partnerId, WEBHOOK_FAILURE, 'Webhook delivery failing', 'Three consecutive delivery failures for event {event_id}. Check your webhook URL.', event_id).; Ensure duplicate notification is not created: check if a WEBHOOK_FAILURE notification for the same reference_id (event_id) already exists before inserting.; Write unit test: simulate 3 consecutive failures for an event and verify exactly one WEBHOOK_FAILURE notification is created.; Write second unit test: simulate failure, success, 3 failures and verify the notification fires on the third consecutive failure of the second run, not the first.
**Deliverable:** WEBHOOK_FAILURE notification wiring in WebhookDeliveryService; unit tests for trigger threshold and duplicate guard.
**Acceptance / logic checks:**
- 2 consecutive failures for an event do not trigger a notification.
- 3rd consecutive failure triggers exactly one WEBHOOK_FAILURE notification per portal user of the partner.
- A success delivery resets the consecutive counter; subsequent 3 new failures again trigger the notification.
- Duplicate guard: if WEBHOOK_FAILURE notification for event_id already exists (is_read=FALSE), a 4th consecutive failure does not create a second notification.
- Notification reference_id equals the event_id string.
**Depends on:** 11.5-T15

### 11.5-T21 — Implement GET /portal/v1/dashboard endpoint (aggregated dashboard data)  _(50 min)_
**Context:** PRD-08 §3 defines the dashboard: balance summary panel (OVERSEAS only), today's activity summary (transaction count, payment volume in KRW, collection volume in USD, success rate), 10 most recent transactions. Today is defined as the current calendar day in KST (UTC+9). Success rate = approved / (approved + declined) * 100 today. All data is partner-scoped from JWT. Performance target: P95 < 2 seconds.
**Steps:** Create PortalDashboardController with GET /portal/v1/dashboard.; For OVERSEAS partners: call PartnerBalanceRepository.findAccountByPartnerId to get balance fields.; Query transactions for today (KST date range: from midnight KST to now KST) to compute: today_txn_count, today_payout_volume (SUM target_payout WHERE txn_status='COMMITTED'), today_collection_volume (SUM collection_amount WHERE txn_status='COMMITTED'), approved_count, declined_count; compute success_rate = approved_count / (approved_count + declined_count) * 100.0 (return null if denominator is 0).; Query 10 most recent transactions for the partner (any date, txn_status in COMMITTED/DECLINED/PENDING) for the recent_transactions array.; Build and return dashboard DTO: {balance (OVERSEAS only), today_summary: {txn_count, payout_volume, payout_ccy, collection_volume, collection_ccy, success_rate_pct}, recent_transactions: [{txn_timestamp_kst, hub_txn_ref_short (first 12 chars), merchant_name, target_payout, payout_ccy, collection_amount, collection_ccy, status}]}.
**Deliverable:** GET /portal/v1/dashboard endpoint; integration tests for OVERSEAS (with balance) and LOCAL (no balance) partners, and success_rate edge cases.
**Acceptance / logic checks:**
- OVERSEAS partner response includes balance object with available_balance_usd, low_balance_threshold_usd, status, last_updated.
- LOCAL partner response does not include balance object.
- today_summary.success_rate_pct with 8 approved and 2 declined = 80.0.
- today_summary.success_rate_pct with 0 approved and 0 declined returns null (not a division-by-zero error).
- recent_transactions contains at most 10 items; hub_txn_ref_short is the first 12 characters of hub_txn_ref.
**Depends on:** 11.5-T05, 11.5-T04

### 11.5-T22 — Unit tests: StatementGenerationService aggregation logic with edge cases  _(45 min)_
**Context:** StatementGenerationService (T07) aggregates transactions for a given partner and month. This ticket adds explicit unit-test vectors covering: (a) mix of COMMITTED and CANCELLED/DECLINED transactions, (b) multi-currency payout (KRW + USD in same period), (c) zero transactions in period, (d) service_charge=0.00 (allowed), (e) same-currency domestic transactions (USD pool fields are NULL/0 — must not affect totals). These are pure unit tests mocking the repository layer.
**Steps:** Create StatementGenerationServiceTest in src/test/java.; Test A: 5 COMMITTED + 2 CANCELLED transactions — verify total_transactions=5 and CANCELLED amounts excluded.; Test B: 3 transactions with payout_ccy=KRW and 2 with payout_ccy=USD in same month — verify totals are grouped by currency (two total_payout_amount rows or currency field is set correctly in the statement for the dominant currency; align with T07 implementation).; Test C: 0 transactions in period — verify statement is created with total_transactions=0, total_payout_amount=0, total_collection_amount=0.; Test D: transactions with service_charge=0.00 — verify total_service_fees=0.00 (no null pointer).; Test E: domestic same-currency transaction (collection_usd=NULL, payout_usd_cost=NULL) — verify aggregation does not error and line item is included.
**Deliverable:** StatementGenerationServiceTest class with 5 named test methods; all pass.
**Acceptance / logic checks:**
- Test A passes: total_transactions=5 with 2 CANCELLED transactions excluded from count and totals.
- Test B passes: total_payout_amount is computed only for COMMITTED transactions; both currency groups represented (implementation must handle multi-currency correctly or test verifies the defined behavior).
- Test C passes: no exception thrown for zero-transaction period; total_transactions=0.
- Test D passes: total_service_fees=0.00 (BigDecimal ZERO, not null).
- Test E passes: domestic transaction with NULL collection_usd is included in statement line items with offer_rate as stored.
**Depends on:** 11.5-T07

### 11.5-T23 — Unit tests: PartnerBalanceRepository running balance invariant  _(35 min)_
**Context:** The prefunding_ledger_entry.balance_after field must be a running balance: each entry's balance_after = previous entry's balance_after +/- amount (credits add, debits subtract). This invariant is critical for the ledger view (PRD-08 §6.2) showing correct running balance to partners. This ticket verifies the invariant holds across a sequence of mixed debit/credit entries, including edge cases: first entry (no prior balance), reversal entries, and zero-amount adjustment.
**Steps:** Create PrefundingLedgerInvariantTest.; Set up test data: partner with initial balance=0, then insert entries: CREDIT_TOPUP 50000.00 (balance_after=50000.00), DEBIT_PAYMENT 35.77 (balance_after=49964.23), DEBIT_PAYMENT 22.50 (balance_after=49941.73), CREDIT_REVERSAL 35.77 (balance_after=49977.50).; Query findLedgerEntries for this partner and verify: (a) 4 rows returned in chronological order; (b) balance_after values match the sequence above within 0.01 USD tolerance; (c) amount fields match the inserted amounts.; Test edge case: a single CREDIT_TOPUP as the first entry — balance_after should equal amount.; Test zero-amount entry is rejected at the DB level (amount DECIMAL(20,4) > 0 constraint if present, or application-level validation).
**Deliverable:** PrefundingLedgerInvariantTest with 3 test methods; all pass against the live dev DB schema.
**Acceptance / logic checks:**
- 4 entries for the test partner are returned in created_at ASC order.
- balance_after sequence matches 50000.00, 49964.23, 49941.73, 49977.50 within 0.01 USD tolerance.
- CREDIT_TOPUP as first entry has balance_after equal to its amount (50000.00).
- Sum of all CREDIT amounts minus sum of all DEBIT amounts equals the final balance_after: (50000.00+35.77) - (35.77+22.50) = 49977.50.
- Inserting an entry with amount=-1.00 (negative) is rejected at the application layer with IllegalArgumentException.
**Depends on:** 11.5-T04

### 11.5-T24 — Unit tests: balance endpoint status indicator boundary conditions  _(30 min)_
**Context:** The status indicator in GET /portal/v1/balance (T05) is RED when available_balance_usd <= low_balance_threshold_usd, GREEN otherwise. PRD-08 §6.1 specifies at-or-below is RED. Boundary tests must verify the exact threshold boundary and that decimal precision is respected (DECIMAL(20,4) — differences of 0.0001 USD must be distinguishable).
**Steps:** Create PortalBalanceControllerTest with MockMvc or WebMvcTest.; Test 1: balance=10000.01, threshold=10000.00 — expect status=GREEN.; Test 2: balance=10000.00, threshold=10000.00 — expect status=RED (at threshold).; Test 3: balance=9999.99, threshold=10000.00 — expect status=RED (below threshold).; Test 4: balance=0.00, threshold=10000.00 — expect status=RED, available_balance_usd=0.00.; Test 5: LOCAL partner calls endpoint — expect HTTP 403 with error=PREFUNDING_NOT_APPLICABLE.
**Deliverable:** PortalBalanceControllerTest with 5 test methods covering all boundary cases; all pass.
**Acceptance / logic checks:**
- Test 1 (balance=10000.01) returns status=GREEN.
- Test 2 (balance=10000.00) returns status=RED.
- Test 3 (balance=9999.99) returns status=RED.
- Test 4 (balance=0.00) returns status=RED with available_balance_usd field equal to 0.00.
- Test 5 (LOCAL partner) returns HTTP 403 with error code PREFUNDING_NOT_APPLICABLE.
**Depends on:** 11.5-T05

### 11.5-T25 — Unit tests: partner data isolation for balance, ledger, and statement endpoints  _(35 min)_
**Context:** PRD-08 §2.2 and AC-02 require strict data isolation: a user from Partner A must never see data belonging to Partner B (returns 404, not 403). This ticket tests isolation across all three data-access endpoints: GET /portal/v1/balance, GET /portal/v1/ledger, GET /portal/v1/statements/{id}/lines. The partner_id in JWT must be the only source of scoping.
**Steps:** Create PartnerDataIsolationTest with two seeded partners: partnerA (id=1, OVERSEAS) and partnerB (id=2, OVERSEAS).; Seed data: partnerA has a prefunding_account, 5 ledger entries, and 1 statement; partnerB has the same.; Test 1: JWT for partnerA calling GET /portal/v1/balance — verify response partner_id equals partnerA.id.; Test 2: JWT for partnerA calling GET /portal/v1/ledger — verify all returned entries have account_id linked to partnerA's account (none from partnerB).; Test 3: JWT for partnerA calling GET /portal/v1/statements/{partnerB_statement_id}/lines — verify HTTP 404 (not 403, per PRD-08 §5).; Test 4: JWT for partnerA calling GET /portal/v1/statements/{partnerB_statement_id}/export/csv — verify HTTP 404.
**Deliverable:** PartnerDataIsolationTest with 4 cross-partner isolation tests; all pass.
**Acceptance / logic checks:**
- Test 1: balance response contains partnerA balance (USD 48000.00), not partnerB balance.
- Test 2: all ledger entries returned belong to partnerA account; count matches partnerA's 5 seeded entries.
- Test 3: accessing partnerB's statement returns HTTP 404 (not HTTP 403).
- Test 4: accessing partnerB's statement CSV export returns HTTP 404.
- No test leaks data across partners in any response body or error message.
**Depends on:** 11.5-T05, 11.5-T06, 11.5-T10, 11.5-T11

### 11.5-T26 — Unit tests: CSV export field exclusion and filename format  _(30 min)_
**Context:** PRD-08 AC-06 requires that CSV exports never include m_a, m_b, cost_rate_coll, cost_rate_pay, collection_margin_usd, payout_margin_usd, or GME internal revenue figures. The CSV filename must match gmepay_stmt_{partner_id}_{YYYY-MM}.csv. This ticket verifies both the field exclusion invariant and the filename convention for the statement line export (T11).
**Steps:** Create StatementCsvExportTest.; Seed a partner_statement with 5 partner_statement_line rows; each row has all columns populated (ensure offer_rate is set, and service_charge is set).; Call GET /portal/v1/statements/{id}/export/csv and parse the response body as CSV.; Verify header row contains exactly: txn_date, hub_txn_ref, target_payout, payout_ccy, collection_amount, collection_ccy, service_charge, service_charge_ccy, offer_rate (9 columns).; Verify no header column matches any of: m_a, m_b, cost_rate_coll, cost_rate_pay, collection_margin_usd, payout_margin_usd.; Verify Content-Disposition header filename matches regex gmepay_stmt_[0-9]+_[0-9]{4}-[0-9]{2}.csv.
**Deliverable:** StatementCsvExportTest with 3 assertions (column count, field exclusion, filename); all pass.
**Acceptance / logic checks:**
- CSV header has exactly 9 columns.
- None of m_a, m_b, cost_rate_coll, cost_rate_pay, collection_margin_usd, payout_margin_usd appear in any column header.
- Content-Disposition filename matches gmepay_stmt_{partner_id}_{YYYY-MM}.csv format.
- CSV data rows count equals the number of statement lines seeded.
- offer_rate value in CSV matches the DECIMAL(20,8) stored value without scientific notation.
**Depends on:** 11.5-T11

### 11.5-T27 — Add DB indexes and query performance verification for balance and ledger endpoints  _(35 min)_
**Context:** PRD-08 §10.1: balance inquiry P95 < 1 second; dashboard P95 < 2 seconds; transaction history P95 < 3 seconds. The ledger query (prefunding_ledger_entry filtered by account_id and date range) and the statement list query (partner_statement filtered by partner_id, ordered by period) are the main performance-sensitive queries. This ticket verifies indexes are used and adds any missing ones.
**Steps:** Run EXPLAIN ANALYZE on the ledger query: SELECT * FROM prefunding_ledger_entry WHERE account_id = :id AND created_at BETWEEN :from AND :to ORDER BY created_at DESC LIMIT 50 — confirm index scan (not seq scan).; Run EXPLAIN ANALYZE on the statement list query: SELECT * FROM partner_statement WHERE partner_id = :pid ORDER BY period_year DESC, period_month DESC LIMIT 12 — confirm index scan.; If seq scan is found, add the missing index via a new migration file (V<N>__add_perf_indexes.sql).; Run EXPLAIN ANALYZE on: SELECT COUNT(*) FROM prefunding_ledger_entry WHERE account_id = :id AND created_at BETWEEN :from AND :to — confirm index-only scan where possible.; Document the EXPLAIN ANALYZE output for both queries in a code comment or test log.
**Deliverable:** Migration file with any missing indexes; EXPLAIN ANALYZE output showing index scans for both queries; integration test confirming query uses index.
**Acceptance / logic checks:**
- EXPLAIN ANALYZE for ledger query shows Index Scan or Bitmap Index Scan on account_id/created_at, not Seq Scan.
- EXPLAIN ANALYZE for statement list shows Index Scan on partner_id/period_year/period_month, not Seq Scan.
- Migration file applies cleanly with no errors on dev DB.
- Index names follow project naming convention (idx_{table}_{columns}).
- No existing index is duplicated.
**Depends on:** 11.5-T01, 11.5-T04

### 11.5-T28 — Implement top-up instructions static content endpoint  _(25 min)_
**Context:** PRD-08 §6.3 defines a static informational section for top-up instructions (OVERSEAS partners only): GME bank account details for USD wire transfer, reference format (partner_id + date), expected processing time T+1 business day, and GME account team contact. This is read-only config; no payment initiation. The content is partner-specific (bank account may vary) or global. In Phase 1 it is static content stored in application config or a simple DB table.
**Steps:** Create GET /portal/v1/balance/topup-instructions endpoint in PortalBalanceController.; Return HTTP 403 for LOCAL partners.; Load topup instructions from application configuration (application.yml key: portal.topup.bank-account-name, bank-account-number, bank-swift-code, reference-format, processing-time-days, contact-email).; Build response DTO: {bank_account_name, bank_account_number, swift_code, reference_format (e.g. GMEPAY-{partner_id}-{YYYYMMDD}), processing_time_days, contact_email, currency: USD}.; Return HTTP 200 with DTO.; Write unit test verifying LOCAL partner gets 403 and OVERSEAS gets 200 with non-null bank_account_number.
**Deliverable:** GET /portal/v1/balance/topup-instructions endpoint; unit test for OVERSEAS (200) and LOCAL (403).
**Acceptance / logic checks:**
- OVERSEAS partner receives HTTP 200 with non-null bank_account_number, swift_code, reference_format, and contact_email fields.
- LOCAL partner receives HTTP 403.
- reference_format contains the placeholder {partner_id} in the response string (e.g. GMEPAY-{partner_id}-{YYYYMMDD}).
- No sensitive credentials (DB passwords, API secrets) are included in the response.
- Response content_type is application/json.
**Depends on:** 11.5-T05

### 11.5-T29 — Implement portal_user management: invite and deactivate within partner (Partner Admin role)  _(50 min)_
**Context:** PRD-08 §1.3 and §2.3: Partner Admins can invite and deactivate portal users within their own partner. Partner Viewer cannot manage users (AC-14). GME Ops creates the first Partner Admin during onboarding. A portal_user table is assumed to exist with columns: id, partner_id, email, role VARCHAR(20) CHECK IN ('PARTNER_ADMIN','PARTNER_VIEWER'), status VARCHAR(20) CHECK IN ('ACTIVE','INVITED','DEACTIVATED'), totp_secret_hash, created_at. This ticket adds invite and deactivate endpoints.
**Steps:** Create PortalUserManagementController with POST /portal/v1/users/invite (Partner Admin only) and PATCH /portal/v1/users/{id}/deactivate (Partner Admin only).; POST /portal/v1/users/invite: extract partner_id from JWT; assert calling user role=PARTNER_ADMIN; validate request body {email, role} (role must be PARTNER_ADMIN or PARTNER_VIEWER); create portal_user row with status=INVITED; send an invite email (placeholder: log the invite link); return 201 with user_id.; PATCH /portal/v1/users/{id}/deactivate: assert calling user role=PARTNER_ADMIN; assert target user belongs to same partner_id; set status=DEACTIVATED; return 200.; Guard: a Partner Admin cannot deactivate themselves (would lock out the partner).; Guard: a PARTNER_VIEWER calling either endpoint receives HTTP 403.
**Deliverable:** Two endpoints (invite, deactivate) with role guard; integration tests for viewer rejection, cross-partner deactivation rejection, and self-deactivation rejection.
**Acceptance / logic checks:**
- Partner Viewer calling POST /portal/v1/users/invite receives HTTP 403.
- Partner Admin inviting a user in another partner receives HTTP 403 (partner_id scoped from JWT, not request body).
- Partner Admin attempting to deactivate themselves receives HTTP 400 with error=SELF_DEACTIVATION_NOT_PERMITTED.
- Successful deactivation sets portal_user.status=DEACTIVATED; subsequent login attempt for that user returns HTTP 401.
- AC-14: Partner Viewer calling PATCH /portal/v1/users/{id}/deactivate receives HTTP 403.
**Depends on:** 11.5-T03

### 11.5-T30 — Add OpenAPI documentation for all WBS 11.5 portal endpoints  _(50 min)_
**Context:** PRD-08 and GMEPay+ API-first conventions require all endpoints to be documented in OpenAPI 3.0. This covers 10 endpoints introduced in WBS 11.5: GET /portal/v1/balance, GET /portal/v1/balance/topup-instructions, GET /portal/v1/ledger, GET /portal/v1/statements, GET /portal/v1/statements/{id}/lines, GET /portal/v1/statements/{id}/export/csv, GET /portal/v1/statements/{id}/export/pdf, GET /portal/v1/notifications, PATCH /portal/v1/notifications/{id}/read, GET /portal/v1/dashboard. Internal-only fields (m_a, m_b, margin USD) must not appear in any response schema.
**Steps:** In the OpenAPI spec file (src/main/resources/openapi/portal.yml or generated via Springdoc annotations), add or complete the schema for each of the 10 endpoints listed in context.; For each endpoint: document path, HTTP method, security requirement (BearerAuth JWT), query parameters, response schema (200 and error codes), and a brief description referencing PRD-08 section.; Ensure response schemas for balance and ledger never include m_a, m_b, cost_rate_coll, cost_rate_pay, collection_margin_usd, payout_margin_usd.; Validate the OpenAPI file using swagger-parser (mvn test or CLI) — zero validation errors.; Add a schema test: load the OpenAPI spec and assert none of the excluded field names appear in any response schema definition.
**Deliverable:** portal.yml (or Springdoc-annotated controllers) with all 10 endpoints fully documented; OpenAPI validation passes with zero errors.
**Acceptance / logic checks:**
- swagger-parser validates portal.yml with zero errors and zero warnings.
- All 10 endpoint paths are present in the spec.
- No response schema in the spec contains properties named m_a, m_b, cost_rate_coll, cost_rate_pay, collection_margin_usd, or payout_margin_usd.
- Each endpoint has at least a 200 response schema and a 403 or 404 error response documented.
- Schema test (automated) passes as part of the CI pipeline.
**Depends on:** 11.5-T05, 11.5-T06, 11.5-T09, 11.5-T10, 11.5-T11, 11.5-T13, 11.5-T16, 11.5-T21, 11.5-T28


## WBS 11.6 — API & credentials view
### 11.6-T01 — Add webhook_delivery_log DB migration: table, indexes, constraints  _(30 min)_
**Context:** The Partner Portal credentials view (PRD-08 §8.1) must show webhook last delivery status (success/failure + timestamp). No webhook_delivery_log table currently exists in schema. Required columns: id BIGINT PK auto-increment, partner_webhook_id BIGINT NOT NULL FK -> partner_webhook.id, event_id VARCHAR(64) NOT NULL (X-GME-Event-ID value), event_type VARCHAR(64) NOT NULL, target_url VARCHAR(512) NOT NULL, attempt_number SMALLINT NOT NULL CHECK > 0 CHECK <= 10, http_status_code SMALLINT NULL (NULL on timeout), response_body_preview VARCHAR(500) NULL, delivered BOOLEAN NOT NULL, delivered_at TIMESTAMPTZ NULL, failure_reason VARCHAR(255) NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(). Per API-05 §6.2 max 10 attempts per event; delivered=TRUE when HTTP 2xx received within 10 s.
**Steps:** Write Flyway migration V11_6_001__create_webhook_delivery_log.sql; Add DDL with all columns, types, NOT NULL constraints, and CHECK constraints; Add index on (partner_webhook_id, created_at DESC) for latest-delivery queries; Add unique index on (event_id, attempt_number) to prevent duplicate attempt rows; Add index on delivered and delivered_at for failure-count queries
**Deliverable:** Migration file V11_6_001__create_webhook_delivery_log.sql
**Acceptance / logic checks:**
- Migration applies cleanly on schema that already contains partner_webhook table
- FK to partner_webhook.id enforces referential integrity; orphan insert is rejected
- CHECK attempt_number > 0 rejects 0; CHECK attempt_number <= 10 rejects 11
- Unique constraint on (event_id, attempt_number) rejects a duplicate attempt row for the same event
- Index on (partner_webhook_id, created_at DESC) is confirmed by EXPLAIN on SELECT ... WHERE partner_webhook_id = ? ORDER BY created_at DESC LIMIT 1

### 11.6-T02 — Add last_delivery_status denorm columns to partner_webhook migration  _(25 min)_
**Context:** PRD-08 §8.1 requires showing webhook last delivery status (success/failure) and timestamp without a subquery on every page load. To support O(1) lookup, add denormalised columns to partner_webhook: last_delivery_status VARCHAR(20) CHECK IN ('SUCCESS','FAILURE','NEVER_ATTEMPTED'), last_delivery_at TIMESTAMPTZ NULL, consecutive_failure_count SMALLINT NOT NULL DEFAULT 0. These are updated by the Webhook Notification Service each time a delivery attempt concludes. consecutive_failure_count is reset to 0 on any SUCCESS. Per PRD-08 §9.1 a webhook_failure in-portal notification is raised after 3 consecutive failures.
**Steps:** Write Flyway migration V11_6_002__add_partner_webhook_delivery_status_columns.sql; Add columns last_delivery_status, last_delivery_at, consecutive_failure_count to partner_webhook; Set default last_delivery_status = 'NEVER_ATTEMPTED' for all existing rows in the migration; Add CHECK constraint on last_delivery_status; Add index on consecutive_failure_count for alert queries (WHERE consecutive_failure_count >= 3)
**Deliverable:** Migration file V11_6_002__add_partner_webhook_delivery_status_columns.sql
**Acceptance / logic checks:**
- Migration applies after T01 with no errors
- All pre-existing partner_webhook rows have last_delivery_status = 'NEVER_ATTEMPTED' after migration
- CHECK rejects last_delivery_status = 'PENDING'
- consecutive_failure_count defaults to 0 on new INSERT
- Index on consecutive_failure_count is present and used by EXPLAIN on SELECT ... WHERE consecutive_failure_count >= 3
**Depends on:** 11.6-T01

### 11.6-T03 — Create PartnerCredential and PartnerWebhook JPA entities with new fields  _(35 min)_
**Context:** GMEPay+ backend is Java + PostgreSQL. JPA entities must reflect the schema for the credentials view. partner_credential (DAT-03 §4.4): id BIGINT, partnerId BIGINT FK, apiKey VARCHAR(64) UNIQUE, apiSecretHash VARCHAR(128), isActive BOOLEAN, expiresAt OffsetDateTime nullable, createdAt, updatedAt, createdBy, updatedBy. partner_webhook (DAT-03 §4.5 + T02 additions): id BIGINT, partnerId BIGINT FK, webhookUrl VARCHAR(512), eventTypes String, signingSecretHash VARCHAR(128), isActive BOOLEAN, lastDeliveryStatus (enum WebhookDeliveryStatus: SUCCESS/FAILURE/NEVER_ATTEMPTED), lastDeliveryAt OffsetDateTime nullable, consecutiveFailureCount int, createdAt, updatedAt, createdBy, updatedBy. CRITICAL: apiSecretHash and signingSecretHash fields must never appear in any DTO or JSON serialisation — annotate @JsonIgnore and never include in projections.
**Steps:** Create or update PartnerCredential.java entity with all fields, @Column annotations, @JsonIgnore on apiSecretHash; Create WebhookDeliveryStatus.java enum with values SUCCESS, FAILURE, NEVER_ATTEMPTED stored as STRING; Create or update PartnerWebhook.java entity adding lastDeliveryStatus (enum), lastDeliveryAt, consecutiveFailureCount fields; Annotate signingSecretHash with @JsonIgnore; Write a unit test asserting that Jackson serialisation of PartnerCredential produces JSON with no apiSecretHash or signingSecretHash key
**Deliverable:** PartnerCredential.java, PartnerWebhook.java, WebhookDeliveryStatus.java entities
**Acceptance / logic checks:**
- Jackson serialisation of PartnerCredential contains apiKey but NOT apiSecretHash
- Jackson serialisation of PartnerWebhook contains lastDeliveryStatus but NOT signingSecretHash
- WebhookDeliveryStatus.NEVER_ATTEMPTED serialises as string 'NEVER_ATTEMPTED' in DB column
- consecutiveFailureCount defaults to 0 in entity constructor
- PartnerCredential.expiresAt is nullable (Optional or null value accepted without error)
**Depends on:** 11.6-T01, 11.6-T02

### 11.6-T04 — Define CredentialsViewDto response model for Portal API endpoint  _(25 min)_
**Context:** The Partner Portal backend exposes an internal portal API (distinct from the partner-facing northbound API) consumed by the frontend SPA. The credentials view (PRD-08 §8.1) returns a single JSON object per partner. CredentialsViewDto must contain exactly: apiKeyId (String, from partner_credential.api_key of the active credential), apiKeyStatus (String: 'ACTIVE' or 'REVOKED'), apiSecretNote (String: always literal 'Not shown for security'), webhookUrl (String nullable), webhookStatus (String: 'SUCCESS'/'FAILURE'/'NEVER_ATTEMPTED'), webhookLastAttemptAt (ISO-8601 String nullable), sandboxApiKeyId (String nullable, from sandbox partner_credential if exists), sandboxBaseUrl (String: always 'https://api-sandbox.gmepayplus.com'), rateQuoteTtlSeconds (Integer, from partner.rate_quote_ttl_seconds). The API secret itself is NEVER included. This is Phase 1 read-only; no regenerate fields.
**Steps:** Create CredentialsViewDto.java as an immutable record or final class with all fields listed above; Add @JsonProperty annotations where field names differ from Java convention; Add Javadoc on apiSecretNote explaining the design decision; Write a unit test that serialises a sample CredentialsViewDto instance and asserts apiSecretNote == 'Not shown for security' and no field named apiSecret or apiSecretHash is present in output JSON; Confirm sandboxBaseUrl is a hardcoded constant 'https://api-sandbox.gmepayplus.com' not sourced from DB
**Deliverable:** CredentialsViewDto.java
**Acceptance / logic checks:**
- Serialised JSON contains exactly the 9 fields listed with correct names (apiKeyId, apiKeyStatus, apiSecretNote, webhookUrl, webhookStatus, webhookLastAttemptAt, sandboxApiKeyId, sandboxBaseUrl, rateQuoteTtlSeconds)
- No field named apiSecret, apiSecretHash, or signingSecretHash appears in output
- apiSecretNote is always the string 'Not shown for security'
- sandboxBaseUrl is always 'https://api-sandbox.gmepayplus.com'
- rateQuoteTtlSeconds is an Integer (not a String)
**Depends on:** 11.6-T03

### 11.6-T05 — Implement PartnerCredentialRepository: find active credential by partner  _(35 min)_
**Context:** The credentials view needs to resolve the currently active API key for a partner. partner_credential has column is_active BOOLEAN; per DAT-03 §4.4 only one active credential per partner at a time. Repository must provide: findActiveByPartnerId(Long partnerId): returns Optional<PartnerCredential> where is_active = TRUE; findSandboxActiveByPartnerId(Long partnerId): returns Optional<PartnerCredential> where is_active = TRUE and api_key like 'pk_test_%' (sandbox prefix per API-05 §10.2). Sandbox credentials have api_key prefixed pk_test_ while production credentials are prefixed pk_live_ (or no prefix). Phase 1: credentials are provisioned by GME Ops only; no self-service.
**Steps:** Create PartnerCredentialRepository extending JpaRepository<PartnerCredential, Long>; Add @Query findActiveByPartnerId: WHERE p.partnerId = :partnerId AND p.isActive = TRUE; Add @Query findSandboxActiveByPartnerId: WHERE p.partnerId = :partnerId AND p.isActive = TRUE AND p.apiKey LIKE 'pk_test_%'; Write integration test with 3 credentials: one active production (pk_live_...), one active sandbox (pk_test_...), one inactive; assert findActiveByPartnerId returns the production one and findSandboxActiveByPartnerId returns the sandbox one; Assert findActiveByPartnerId returns Optional.empty() when no active credential exists
**Deliverable:** PartnerCredentialRepository.java with active and sandbox-active query methods, plus integration test
**Acceptance / logic checks:**
- findActiveByPartnerId returns the is_active=TRUE credential for the given partner
- findSandboxActiveByPartnerId returns credential with api_key starting pk_test_ and is_active=TRUE
- Both return Optional.empty() when no matching row exists
- findSandboxActiveByPartnerId does NOT return a production (pk_live_) credential even if is_active=TRUE
- Integration test passes with 3-credential fixture as described
**Depends on:** 11.6-T03

### 11.6-T06 — Implement PartnerWebhookRepository: find active webhook and latest delivery status  _(30 min)_
**Context:** The credentials view shows webhook URL and last delivery status from the partner_webhook table (with denorm columns added in T02). Repository needs: findActiveByPartnerId(Long partnerId): Optional<PartnerWebhook> where is_active = TRUE. Only one active webhook per partner is assumed for Phase 1 (primary). lastDeliveryStatus, lastDeliveryAt, and consecutiveFailureCount are already on the entity (T03), so no join needed for display. Also needed for the alert logic (PRD-08 §9.1): findPartnersWithConsecutiveFailures(int threshold): returns List<Long> of partner_ids where consecutive_failure_count >= threshold.
**Steps:** Create PartnerWebhookRepository extending JpaRepository<PartnerWebhook, Long>; Add findByPartnerIdAndIsActiveTrue(Long partnerId): returns Optional<PartnerWebhook>; Add @Query findPartnersWithConsecutiveFailures: SELECT pw.partnerId FROM PartnerWebhook pw WHERE pw.consecutiveFailureCount >= :threshold AND pw.isActive = TRUE; Write integration test: insert two webhooks for different partners; partner A has consecutiveFailureCount=3, partner B has 1; assert findPartnersWithConsecutiveFailures(3) returns only partner A id; Assert findByPartnerIdAndIsActiveTrue returns Optional.empty() when no active webhook
**Deliverable:** PartnerWebhookRepository.java with active-lookup and failure-threshold query, plus integration test
**Acceptance / logic checks:**
- findByPartnerIdAndIsActiveTrue returns the is_active=TRUE webhook for given partner
- findByPartnerIdAndIsActiveTrue returns Optional.empty() when partner has no active webhook
- findPartnersWithConsecutiveFailures(3) returns partner_ids where consecutiveFailureCount >= 3
- Inactive webhooks (isActive=FALSE) are excluded from findPartnersWithConsecutiveFailures even if consecutiveFailureCount >= threshold
- Integration test with partner A (count=3) and partner B (count=1) passes for threshold=3
**Depends on:** 11.6-T03

### 11.6-T07 — Implement CredentialsViewService: assemble CredentialsViewDto for a partner  _(40 min)_
**Context:** The service resolves and assembles the CredentialsViewDto (T04) using PartnerCredentialRepository (T05), PartnerWebhookRepository (T06), and the Partner record (rate_quote_ttl_seconds from partner table). Business rules: (1) apiKeyId = active production credential api_key; if none, apiKeyId = null and apiKeyStatus = 'REVOKED'. (2) sandboxApiKeyId = sandbox credential api_key (pk_test_ prefix) if exists else null. (3) webhookUrl = active webhook webhookUrl or null if none configured. (4) webhookStatus = active webhook lastDeliveryStatus.name() or 'NEVER_ATTEMPTED' if no active webhook. (5) webhookLastAttemptAt = active webhook lastDeliveryAt as ISO-8601 UTC string or null. (6) sandboxBaseUrl always 'https://api-sandbox.gmepayplus.com'. (7) rateQuoteTtlSeconds = partner.rateQuoteTtlSeconds. CRITICAL: partner_id is always sourced from the authenticated JWT claims, never from request body.
**Steps:** Create CredentialsViewService.java with method getCredentialsView(Long authenticatedPartnerId): CredentialsViewDto; Inject PartnerCredentialRepository, PartnerWebhookRepository, and PartnerRepository; Implement assembly logic following all 7 rules above; Handle missing active credential: return apiKeyId=null, apiKeyStatus='REVOKED'; Handle missing active webhook: return webhookUrl=null, webhookStatus='NEVER_ATTEMPTED', webhookLastAttemptAt=null
**Deliverable:** CredentialsViewService.java
**Acceptance / logic checks:**
- When active production credential exists, returned apiKeyId equals api_key and apiKeyStatus is 'ACTIVE'
- When no active credential exists, apiKeyId is null and apiKeyStatus is 'REVOKED'
- When active webhook exists with lastDeliveryStatus=FAILURE, webhookStatus is 'FAILURE'
- When no active webhook exists, webhookUrl is null and webhookStatus is 'NEVER_ATTEMPTED'
- sandboxBaseUrl is always 'https://api-sandbox.gmepayplus.com' regardless of DB state
**Depends on:** 11.6-T04, 11.6-T05, 11.6-T06

### 11.6-T08 — Implement GET /portal/v1/credentials portal API endpoint  _(35 min)_
**Context:** The Partner Portal frontend calls a backend portal API (separate from the northbound partner API) to load the credentials view. Endpoint: GET /portal/v1/credentials. Auth: JWT session token in Authorization: Bearer header; partner_id extracted from claims (NEVER from query param or body). Response: 200 OK with CredentialsViewDto JSON. Errors: 401 if unauthenticated, 403 if session partner_id does not match or role is insufficient. Roles: Partner Admin and Partner Viewer both have read access to credentials (PRD-08 §2.3). Data isolation: the service is called with partner_id from JWT only. No query parameters accepted. Response must not include any secret fields.
**Steps:** Create CredentialsController.java (or add to existing portal controller) with @GetMapping('/portal/v1/credentials'); Extract authenticatedPartnerId from SecurityContext JWT claims only (not from request); Call CredentialsViewService.getCredentialsView(authenticatedPartnerId); Return 200 ResponseEntity<CredentialsViewDto>; Add @PreAuthorize requiring role PARTNER_ADMIN or PARTNER_VIEWER
**Deliverable:** GET /portal/v1/credentials endpoint in CredentialsController.java
**Acceptance / logic checks:**
- GET /portal/v1/credentials with valid JWT returns 200 and correct CredentialsViewDto JSON
- GET without Authorization header returns 401
- GET with JWT whose partner_id belongs to Partner A returns data for Partner A even if a query param for Partner B is appended
- Response JSON does not contain any field named apiSecret, apiSecretHash, or signingSecretHash
- Both PARTNER_ADMIN and PARTNER_VIEWER roles can access the endpoint; a request with role OPERATOR (admin portal role) is rejected with 403
**Depends on:** 11.6-T07

### 11.6-T09 — Implement WebhookDeliveryLogger: persist delivery attempts and update denorm status  _(45 min)_
**Context:** The Webhook Notification Service (part of Hub Core backend) dispatches events and must log each attempt to webhook_delivery_log (T01) and update the denorm columns on partner_webhook (T02). WebhookDeliveryLogger.recordAttempt(Long partnerWebhookId, String eventId, String eventType, String targetUrl, int attemptNumber, Integer httpStatusCode, String responseBodyPreview, boolean delivered, OffsetDateTime deliveredAt, String failureReason) must: (1) INSERT a row into webhook_delivery_log; (2) UPDATE partner_webhook SET last_delivery_status = (delivered ? 'SUCCESS' : 'FAILURE'), last_delivery_at = NOW(), consecutive_failure_count = (delivered ? 0 : consecutive_failure_count + 1) WHERE id = partnerWebhookId. Both operations must be in a single DB transaction. consecutiveFailureCount is reset to 0 on any SUCCESS.
**Steps:** Create WebhookDeliveryLogger.java with @Transactional method recordAttempt(...); Inject WebhookDeliveryLogRepository (new repository for webhook_delivery_log) and PartnerWebhookRepository; INSERT to webhook_delivery_log using the repository save(); UPDATE partner_webhook using a @Modifying @Query or entity update pattern for the 3 denorm columns atomically; Write unit test: after two failures then one success, assert consecutiveFailureCount=0 and lastDeliveryStatus=SUCCESS
**Deliverable:** WebhookDeliveryLogger.java and WebhookDeliveryLogRepository.java
**Acceptance / logic checks:**
- recordAttempt with delivered=true sets last_delivery_status='SUCCESS' and consecutive_failure_count=0 on partner_webhook
- recordAttempt with delivered=false increments consecutive_failure_count by 1
- Two consecutive failures set consecutive_failure_count=2; subsequent success resets it to 0
- Both the webhook_delivery_log insert and the partner_webhook update commit or rollback together (transaction rollback test)
- attempt_number unique constraint violation (duplicate attempt for same event_id + attempt_number) causes the entire transaction to roll back
**Depends on:** 11.6-T02, 11.6-T03

### 11.6-T10 — Implement WebhookFailureAlertService: detect >= 3 consecutive failures and raise in-portal notification  _(35 min)_
**Context:** PRD-08 §9.1 specifies: webhook_failure in-portal notification is raised after 3 consecutive failed delivery attempts (consecutive_failure_count >= 3 on partner_webhook). This service is called by the Webhook Notification Service after each failed delivery. If consecutiveFailureCount reaches exactly 3 (i.e. the value after increment), a portal notification record must be created. Notification table: portal_notification (assumed to exist from WBS 11.x notification work) with fields: id, partner_id, notification_type ('WEBHOOK_FAILURE'), severity ('WARNING'), message TEXT, is_read BOOLEAN DEFAULT FALSE, created_at. De-duplication: if an unread WEBHOOK_FAILURE notification already exists for this partner, do not create a duplicate (check before insert).
**Steps:** Create WebhookFailureAlertService.java with method checkAndRaiseAlert(Long partnerId, int currentConsecutiveFailureCount); If consecutiveFailureCount < 3, return immediately (no action); Query portal_notification for existing unread WEBHOOK_FAILURE notification for this partner; If none exists, insert a new portal_notification row with notification_type='WEBHOOK_FAILURE', severity='WARNING', message='Webhook delivery has failed 3 or more consecutive times. Please verify your webhook endpoint is reachable.'; Write unit test: assert alert is created when count reaches 3; assert no duplicate when unread alert already exists; assert no alert when count = 2
**Deliverable:** WebhookFailureAlertService.java
**Acceptance / logic checks:**
- checkAndRaiseAlert(partnerId, 3) inserts a portal_notification row with type WEBHOOK_FAILURE
- checkAndRaiseAlert(partnerId, 2) inserts nothing
- checkAndRaiseAlert(partnerId, 3) called twice does NOT produce two rows when the first is still unread
- checkAndRaiseAlert(partnerId, 5) also inserts if no unread alert exists (>= 3 threshold)
- portal_notification row has severity='WARNING' and is_read=FALSE on creation
**Depends on:** 11.6-T09

### 11.6-T11 — Wire WebhookDeliveryLogger and WebhookFailureAlertService into Webhook Notification Service  _(45 min)_
**Context:** The existing Webhook Notification Service dispatches payment events (payment.pending_debit, payment.approved, payment.failed, payment.reversed) per API-05 §6.1. After each delivery attempt it must call WebhookDeliveryLogger.recordAttempt(...) and then WebhookFailureAlertService.checkAndRaiseAlert(...). Retry schedule per API-05 §6.2: attempt 1 immediate, 2 at 30s, 3 at 2min, 4 at 10min, 5 at 30min, attempts 6-10 at 1h each. After 10 failed attempts the event is marked delivery_failed. The delivery is considered successful on HTTP 2xx received within 10 seconds (API-05 §6.1). Non-2xx, timeout, 3xx, 4xx, 5xx are all failures.
**Steps:** Inject WebhookDeliveryLogger and WebhookFailureAlertService into the existing Webhook Notification Service class; After each HTTP call (success or failure), call WebhookDeliveryLogger.recordAttempt with the attempt number, HTTP status code (null on timeout), and delivered flag; After each failed attempt, call WebhookFailureAlertService.checkAndRaiseAlert with updated consecutiveFailureCount; After attempt 10 fails, mark event delivery_failed (existing logic) and ensure final recordAttempt is called; Write integration test: simulate 3 consecutive HTTP 500 responses and assert consecutive_failure_count=3, portal_notification row created, last_delivery_status='FAILURE'
**Deliverable:** Updated Webhook Notification Service with delivery logging and failure alert wiring
**Acceptance / logic checks:**
- After 3 HTTP 500 responses, webhook_delivery_log has 3 rows with delivered=FALSE for the event
- After 3 failures, partner_webhook.consecutive_failure_count = 3 and last_delivery_status = 'FAILURE'
- After 3 failures, a portal_notification row with type WEBHOOK_FAILURE exists
- HTTP 200 response sets delivered=TRUE, consecutive_failure_count=0, last_delivery_status='SUCCESS'
- Timeout (no response within 10s) is recorded as delivered=FALSE with http_status_code=NULL in webhook_delivery_log
**Depends on:** 11.6-T09, 11.6-T10

### 11.6-T12 — Add partner.sandbox_api_key_prefix config and SandboxInfoService  _(25 min)_
**Context:** PRD-08 §8.2 requires a static sandbox info panel showing: sandbox base URL ('https://api-sandbox.gmepayplus.com'), API-05 documentation URL, and GME technical contact email for integration support. These values are system-wide config constants, not per-partner DB values. The sandbox base URL and documentation URL are stable but must be configurable (not hardcoded in source) via application.properties/YAML. GME tech contact email is similarly config-driven. SandboxInfoService.getSandboxInfo() returns SandboxInfoDto: sandboxBaseUrl (String), apiDocUrl (String), techSupportEmail (String). Values are injected from config with defaults: sandboxBaseUrl='https://api-sandbox.gmepayplus.com', apiDocUrl='https://docs.gmepayplus.com/api/v1', techSupportEmail='api-support@gmeremit.com'.
**Steps:** Add config properties: gmepayplus.sandbox.base-url, gmepayplus.sandbox.api-doc-url, gmepayplus.sandbox.tech-support-email to application.yml with the default values; Create SandboxInfoDto.java record with fields sandboxBaseUrl, apiDocUrl, techSupportEmail; Create SandboxInfoService.java that reads the three properties via @Value and returns SandboxInfoDto; Write unit test asserting all three fields are populated from config and are non-null; Verify that CredentialsViewDto (T04) sandboxBaseUrl is sourced from SandboxInfoService rather than hardcoded
**Deliverable:** SandboxInfoService.java, SandboxInfoDto.java, application.yml config entries
**Acceptance / logic checks:**
- SandboxInfoService.getSandboxInfo() returns sandboxBaseUrl='https://api-sandbox.gmepayplus.com' from default config
- Overriding gmepayplus.sandbox.base-url in test config changes the returned value
- apiDocUrl and techSupportEmail are non-null and non-empty from default config
- Config values survive application context refresh without restart (if @RefreshScope is used)
- CredentialsViewDto populated by CredentialsViewService contains sandboxBaseUrl matching SandboxInfoService output
**Depends on:** 11.6-T07

### 11.6-T13 — Unit tests: CredentialsViewService all branches  _(35 min)_
**Context:** Explicit unit-test ticket for CredentialsViewService (T07). Test vectors: (A) Partner with active production credential pk_live_abc123, active sandbox credential pk_test_xyz789, active webhook with lastDeliveryStatus=SUCCESS and lastDeliveryAt=2026-06-01T10:00:00Z, rateQuoteTtlSeconds=300. Expected CredentialsViewDto: apiKeyId='pk_live_abc123', apiKeyStatus='ACTIVE', apiSecretNote='Not shown for security', sandboxApiKeyId='pk_test_xyz789', webhookStatus='SUCCESS', webhookLastAttemptAt='2026-06-01T10:00:00Z', rateQuoteTtlSeconds=300. (B) Partner with no active production credential, no sandbox credential, no webhook. Expected: apiKeyId=null, apiKeyStatus='REVOKED', sandboxApiKeyId=null, webhookUrl=null, webhookStatus='NEVER_ATTEMPTED', webhookLastAttemptAt=null. (C) Partner with active credential but webhook lastDeliveryStatus=FAILURE and consecutiveFailureCount=5.
**Steps:** Create CredentialsViewServiceTest.java using Mockito to mock all three repositories; Implement test case A (full active state) and assert all 9 DTO fields; Implement test case B (all nulls/revoked) and assert all 9 DTO fields; Implement test case C (active credential, failed webhook) and assert webhookStatus='FAILURE'; Assert that getCredentialsView never calls any method that would return or log a secret hash value
**Deliverable:** CredentialsViewServiceTest.java covering 3 test vectors
**Acceptance / logic checks:**
- Test A: apiKeyId='pk_live_abc123', apiKeyStatus='ACTIVE', sandboxApiKeyId='pk_test_xyz789', webhookStatus='SUCCESS', rateQuoteTtlSeconds=300
- Test B: apiKeyId=null, apiKeyStatus='REVOKED', webhookUrl=null, webhookStatus='NEVER_ATTEMPTED', webhookLastAttemptAt=null
- Test C: webhookStatus='FAILURE' when lastDeliveryStatus=FAILURE
- No test case produces a DTO that contains a field with value matching hash pattern (60-char bcrypt string)
- All 3 tests pass with 0 failures
**Depends on:** 11.6-T07

### 11.6-T14 — Unit tests: WebhookDeliveryLogger state transitions  _(40 min)_
**Context:** Explicit unit-test ticket for WebhookDeliveryLogger (T09). Test vectors: (A) First delivery attempt succeeds (HTTP 200, delivered=true): assert webhook_delivery_log row inserted with delivered=TRUE, http_status_code=200, partner_webhook.last_delivery_status='SUCCESS', consecutive_failure_count=0. (B) Two consecutive failures (HTTP 503, HTTP 504): assert consecutive_failure_count=2, last_delivery_status='FAILURE'. (C) Two failures followed by success: assert consecutive_failure_count resets to 0 and last_delivery_status='SUCCESS'. (D) Timeout scenario (no HTTP response): delivered=false, http_status_code=NULL in log row. (E) Attempt 10 duplicate (same event_id + attempt_number): transaction rolls back and no duplicate row exists.
**Steps:** Create WebhookDeliveryLoggerTest.java using Spring @DataJpaTest or Mockito; Implement test vector A: single success delivery; Implement test vector B: two failures, assert count=2; Implement test vector C: two failures then success, assert reset to 0; Implement test vector D: timeout (null httpStatusCode), assert http_status_code IS NULL in DB row; Implement test vector E: duplicate attempt_number for same event_id throws exception and no duplicate row
**Deliverable:** WebhookDeliveryLoggerTest.java covering 5 test vectors
**Acceptance / logic checks:**
- Test A: last_delivery_status='SUCCESS', consecutive_failure_count=0, delivered=TRUE in log row
- Test B: after 2 failures consecutive_failure_count=2 and last_delivery_status='FAILURE'
- Test C: after 2 failures + 1 success consecutive_failure_count=0
- Test D: log row has http_status_code=NULL for timeout, delivered=FALSE
- Test E: duplicate (event_id, attempt_number) causes rollback; only 1 row exists in webhook_delivery_log for that event + attempt pair
**Depends on:** 11.6-T09

### 11.6-T15 — Unit tests: WebhookFailureAlertService de-duplication logic  _(30 min)_
**Context:** Explicit unit-test ticket for WebhookFailureAlertService (T10). Test vectors: (A) consecutiveFailureCount=3, no existing unread alert: one portal_notification row created with type='WEBHOOK_FAILURE', severity='WARNING', is_read=FALSE. (B) consecutiveFailureCount=2: no row created. (C) consecutiveFailureCount=1: no row created. (D) consecutiveFailureCount=3, unread WEBHOOK_FAILURE alert already exists for this partner: no additional row created (de-duplication). (E) consecutiveFailureCount=3, existing alert for this partner but it is_read=TRUE (user dismissed it): new alert IS created. (F) consecutiveFailureCount=5: row IS created (>= 3 threshold).
**Steps:** Create WebhookFailureAlertServiceTest.java with Mockito mocks for PortalNotificationRepository; Implement test A (count=3, no existing alert) and assert save() called once with correct fields; Implement tests B and C (count < 3) and assert save() never called; Implement test D (count=3, unread alert exists) and assert save() not called; Implement test E (count=3, read alert exists) and assert save() called for new alert; Implement test F (count=5) and assert save() called
**Deliverable:** WebhookFailureAlertServiceTest.java covering 6 test vectors
**Acceptance / logic checks:**
- Test A: save() called once with notification_type='WEBHOOK_FAILURE', severity='WARNING'
- Tests B and C: save() never called
- Test D: save() not called when unread WEBHOOK_FAILURE notification already exists for partner
- Test E: save() called when existing alert is is_read=TRUE
- Test F: save() called for count=5 (>= 3 threshold)
**Depends on:** 11.6-T10

### 11.6-T16 — Unit tests: GET /portal/v1/credentials endpoint security and data isolation  _(35 min)_
**Context:** Explicit unit-test ticket for CredentialsController (T08). Tests must verify: (1) Data isolation — partner_id is sourced exclusively from JWT claims; (2) Authentication — unauthenticated request returns 401; (3) Authorization — PARTNER_VIEWER and PARTNER_ADMIN get 200; (4) No secret leakage — response body never contains apiSecretHash or signingSecretHash. Test vectors: (A) Valid JWT for partner_id=42, no URL params: returns 200 with CredentialsViewDto where all data belongs to partner 42. (B) Valid JWT for partner_id=42 but URL has ?partner_id=99: still returns data for partner 42 (param ignored). (C) No JWT: 401. (D) JWT with role OPERATOR (admin role, not partner role): 403. (E) JWT with role PARTNER_VIEWER: 200.
**Steps:** Create CredentialsControllerTest.java using @WebMvcTest and MockMvc; Mock CredentialsViewService to return a fixed CredentialsViewDto for partner_id=42; Test A: valid JWT partner_id=42, assert 200 and service called with 42; Test B: valid JWT partner_id=42 plus query param partner_id=99, assert service still called with 42 (never 99); Test C: no Authorization header, assert 401; Test D: JWT with role OPERATOR, assert 403; Test E: JWT with role PARTNER_VIEWER, assert 200
**Deliverable:** CredentialsControllerTest.java covering 5 test vectors
**Acceptance / logic checks:**
- Test A: HTTP 200, CredentialsViewService.getCredentialsView called with exact partner_id from JWT
- Test B: service called with 42 even when query param says 99 — data isolation enforced
- Test C: no JWT returns 401
- Test D: OPERATOR role returns 403
- Response body for test A does not contain string 'apiSecretHash' or 'signingSecretHash'
**Depends on:** 11.6-T08

### 11.6-T17 — Frontend: CredentialsPage component skeleton and routing  _(30 min)_
**Context:** The Partner Portal is a React/TypeScript SPA (per PRD-08 and UX-11). A dedicated Credentials page at route /credentials must be added to the portal navigation. This ticket adds the page skeleton only (no data loading yet). The page must: appear in the left-nav sidebar under a 'Developer' or 'API' section; be accessible by both PARTNER_ADMIN and PARTNER_VIEWER roles; show a page header 'API & Credentials'; contain two empty section placeholders: 'Production Credentials' and 'Sandbox'. Minimum supported width 1280px per PRD-08 §10.2. No destructive actions exist on this page (read-only portal).
**Steps:** Create src/pages/CredentialsPage.tsx with basic layout: page header, two section card containers; Add route /credentials to the portal React Router config; Add nav link 'API Credentials' (or 'Developer') to the sidebar nav component; Gate the route with the existing auth guard (redirect to login if unauthenticated); Confirm both PARTNER_ADMIN and PARTNER_VIEWER roles can navigate to the route (no role-based redirect from this page)
**Deliverable:** CredentialsPage.tsx and updated router/nav config
**Acceptance / logic checks:**
- Navigating to /credentials renders the page header 'API & Credentials'
- Sidebar nav contains a link that routes to /credentials
- Unauthenticated access to /credentials redirects to /login
- Page renders without errors at viewport width 1280px
- Both PARTNER_ADMIN and PARTNER_VIEWER sessions can reach /credentials without redirect

### 11.6-T18 — Frontend: CredentialsService API client for GET /portal/v1/credentials  _(30 min)_
**Context:** The frontend must call GET /portal/v1/credentials on the GMEPay+ portal backend. The response is a CredentialsViewDto JSON object (T04 schema). Create a typed API client function: getCredentials(): Promise<CredentialsViewDto>. The function sends the session JWT in the Authorization: Bearer header. Response shape: { apiKeyId: string | null, apiKeyStatus: 'ACTIVE' | 'REVOKED', apiSecretNote: string, webhookUrl: string | null, webhookStatus: 'SUCCESS' | 'FAILURE' | 'NEVER_ATTEMPTED', webhookLastAttemptAt: string | null, sandboxApiKeyId: string | null, sandboxBaseUrl: string, rateQuoteTtlSeconds: number }. On 401 the client must trigger the global session-expiry handler (redirect to login). On other non-2xx throw a typed ApiError.
**Steps:** Create src/api/credentialsApi.ts defining CredentialsViewDto TypeScript interface; Implement getCredentials() using the existing axios/fetch portal client with Authorization header injection; Handle 401 by calling the global session-expiry hook (e.g. useAuth logout); Handle other non-2xx by throwing ApiError with status code and message; Write a Jest unit test mocking the HTTP client: assert 200 returns typed DTO, 401 triggers logout, 500 throws ApiError
**Deliverable:** src/api/credentialsApi.ts with TypeScript interface and getCredentials function, plus Jest test
**Acceptance / logic checks:**
- getCredentials() on 200 returns a CredentialsViewDto with all 9 fields correctly typed
- getCredentials() on 401 triggers the session-expiry/logout handler
- getCredentials() on 500 throws ApiError with status=500
- TypeScript interface CredentialsViewDto has apiKeyId as string | null (not required string)
- No field named apiSecret or apiSecretHash appears in the TypeScript interface
**Depends on:** 11.6-T17

### 11.6-T19 — Frontend: ProductionCredentialsCard component  _(35 min)_
**Context:** The Production Credentials section (PRD-08 §8.1) must show: API Key ID (the api_key value e.g. 'pk_live_abc123def456...'), API Key Status badge (green 'Active' or red 'Revoked'), a note 'API Secret: Not shown for security' in muted text, Webhook URL (or '—' if null), Webhook Status badge (green 'Success', red 'Failure', or grey 'Never attempted' based on webhookStatus), Webhook Last Attempt date-time (or '—' if null, formatted as 'YYYY-MM-DD HH:mm UTC'), Rate Quote TTL in seconds (e.g. '300 s'). No edit/copy/regenerate buttons in Phase 1. The API Key ID should be displayed in a monospace font. No secret value is ever shown.
**Steps:** Create src/components/credentials/ProductionCredentialsCard.tsx accepting props: CredentialsViewDto; Render each field as a labeled row: label on left, value on right; Render API Key ID in monospace <code> element; Render API Key Status as a coloured badge: green for ACTIVE, red for REVOKED; Render Webhook Status as badge: green SUCCESS, red FAILURE, grey NEVER_ATTEMPTED; Render Webhook Last Attempt as formatted date or dash; Rate Quote TTL as '{n} s'
**Deliverable:** src/components/credentials/ProductionCredentialsCard.tsx
**Acceptance / logic checks:**
- apiKeyStatus='ACTIVE' renders green badge; 'REVOKED' renders red badge
- webhookStatus='FAILURE' renders red badge; 'NEVER_ATTEMPTED' renders grey badge
- API Key ID is wrapped in a monospace element (code or pre)
- No button or input element exists on the component (read-only)
- webhookUrl=null renders as '—'; webhookLastAttemptAt=null renders as '—'
**Depends on:** 11.6-T18

### 11.6-T20 — Frontend: SandboxInfoPanel component  _(25 min)_
**Context:** PRD-08 §8.2 requires a static sandbox info panel showing: Sandbox API Key ID (from sandboxApiKeyId, or '—' if not yet provisioned), Sandbox Base URL (always 'https://api-sandbox.gmepayplus.com' — display as clickable link), API Documentation URL (from SandboxInfoDto.apiDocUrl e.g. 'https://docs.gmepayplus.com/api/v1' — display as clickable link), GME Technical Contact email (from techSupportEmail e.g. 'api-support@gmeremit.com' — display as mailto: link). The sandbox base URL and doc URL must open in a new tab (target='_blank' rel='noopener noreferrer'). The sandbox section is visible to all partner users regardless of partner type (LOCAL or OVERSEAS).
**Steps:** Create src/components/credentials/SandboxInfoPanel.tsx accepting props: sandboxApiKeyId (string|null), sandboxBaseUrl (string), apiDocUrl (string), techSupportEmail (string); Render Sandbox API Key ID in monospace or '—' if null; Render Sandbox Base URL as <a href> opening in new tab; Render API Doc URL as <a href> opening in new tab; Render tech support email as <a href='mailto:...'> link
**Deliverable:** src/components/credentials/SandboxInfoPanel.tsx
**Acceptance / logic checks:**
- Sandbox Base URL link has target='_blank' and rel='noopener noreferrer'
- API Doc URL link has target='_blank' and rel='noopener noreferrer'
- Tech support email is a mailto: link
- sandboxApiKeyId=null renders as '—'
- Component renders without errors when all props are provided with valid values
**Depends on:** 11.6-T17

### 11.6-T21 — Frontend: CredentialsPage data loading with useCredentials hook  _(35 min)_
**Context:** CredentialsPage (T17) must load data from the portal API using getCredentials() (T18) and pass it to ProductionCredentialsCard (T19) and SandboxInfoPanel (T20). Implement a useCredentials custom hook that manages loading, data, and error states. Loading state: show a skeleton placeholder or spinner while fetch is in progress. Error state: show an inline error message (do not redirect; the user may retry). On success: pass CredentialsViewDto to child components. The hook uses React Query or useEffect with useState. The page must re-fetch when the user navigates back to /credentials.
**Steps:** Create src/hooks/useCredentials.ts returning { data: CredentialsViewDto | null, isLoading: boolean, error: string | null }; Call getCredentials() on mount; handle loading/error/success states; In CredentialsPage.tsx, import and call useCredentials hook; Render a Spinner or skeleton when isLoading=true; Render an error alert when error is non-null with a Retry button that calls refetch(); Render ProductionCredentialsCard and SandboxInfoPanel when data is loaded
**Deliverable:** src/hooks/useCredentials.ts and updated CredentialsPage.tsx
**Acceptance / logic checks:**
- isLoading=true while fetch is in progress; spinner is visible
- error message is displayed when API returns non-2xx; Retry button is present
- ProductionCredentialsCard receives correct CredentialsViewDto props when data loads
- SandboxInfoPanel receives sandboxBaseUrl='https://api-sandbox.gmepayplus.com' from loaded data
- Navigating away and back to /credentials triggers a fresh API fetch
**Depends on:** 11.6-T18, 11.6-T19, 11.6-T20

### 11.6-T22 — Frontend component tests: ProductionCredentialsCard all display states  _(30 min)_
**Context:** Explicit frontend test ticket for ProductionCredentialsCard (T19). Test vectors: (A) Active credential with successful webhook: apiKeyId='pk_live_abc123', apiKeyStatus='ACTIVE', webhookUrl='https://partner.example.com/hook', webhookStatus='SUCCESS', webhookLastAttemptAt='2026-06-01T10:00:00Z', rateQuoteTtlSeconds=300. Assert green Active badge, green Success badge, URL displayed, TTL shows '300 s'. (B) Revoked credential, failed webhook: apiKeyId=null, apiKeyStatus='REVOKED', webhookUrl=null, webhookStatus='FAILURE', webhookLastAttemptAt='2026-06-04T08:30:00Z'. Assert red Revoked badge, red Failure badge, webhook URL shows '—'. (C) No webhook configured: webhookStatus='NEVER_ATTEMPTED', webhookLastAttemptAt=null, webhookUrl=null. Assert grey badge, '—' for URL and last attempt.
**Steps:** Create src/components/credentials/__tests__/ProductionCredentialsCard.test.tsx using React Testing Library; Implement test A and assert badge colours (via data-testid or aria-label) and text content; Implement test B and assert red badges and '—' for null fields; Implement test C and assert grey Never attempted badge; Assert no element with text content matching a 64-char hex or bcrypt hash pattern exists in any rendered output; Assert no button element with text 'Regenerate' or 'Rotate' exists (Phase 1 scope)
**Deliverable:** ProductionCredentialsCard.test.tsx covering 3 test vectors
**Acceptance / logic checks:**
- Test A: 'Active' badge present with green indicator; webhookLastAttemptAt formatted correctly
- Test B: 'Revoked' badge present; webhookUrl renders as '—'
- Test C: 'Never attempted' badge is grey; webhookLastAttemptAt renders as '—'
- No regenerate/rotate button present in any test case
- Rendered output does not contain any 60+-character hash-like string
**Depends on:** 11.6-T19

### 11.6-T23 — Integration test: credentials view data isolation between partners  _(45 min)_
**Context:** PRD-08 §2.2 mandates that a user from Partner A can never see data from Partner B. This integration test verifies the full stack from HTTP request to DB for the credentials endpoint. Setup: create Partner A (id=1) with credential pk_live_aaa111 and Partner B (id=2) with credential pk_live_bbb222. Create a JWT session for Partner A user. Test: GET /portal/v1/credentials returns apiKeyId='pk_live_aaa111' for Partner A session. Test: the same endpoint with Partner B JWT returns apiKeyId='pk_live_bbb222'. Test: attempting to forge partner_id=2 in a query parameter while authenticated as Partner A still returns Partner A data. The test must use @SpringBootTest and a real (H2 or Testcontainers PostgreSQL) database.
**Steps:** Create CredentialsIsolationIntegrationTest.java using @SpringBootTest with real DB; Insert Partner A (id=1, credential pk_live_aaa111) and Partner B (id=2, credential pk_live_bbb222) via test fixtures; Build JWT for Partner A user and Partner B user using test auth helper; GET /portal/v1/credentials with Partner A JWT: assert HTTP 200 and apiKeyId='pk_live_aaa111'; GET /portal/v1/credentials with Partner B JWT: assert HTTP 200 and apiKeyId='pk_live_bbb222'; GET /portal/v1/credentials?partner_id=2 with Partner A JWT: assert HTTP 200 and apiKeyId='pk_live_aaa111' (not Partner B's key)
**Deliverable:** CredentialsIsolationIntegrationTest.java
**Acceptance / logic checks:**
- Partner A JWT returns pk_live_aaa111
- Partner B JWT returns pk_live_bbb222
- Partner A JWT with forged query param partner_id=2 still returns pk_live_aaa111
- No test returns Partner B data to Partner A session
- Test uses real DB (H2 or Testcontainers) not mocks for the repository layer
**Depends on:** 11.6-T08, 11.6-T13, 11.6-T16

### 11.6-T24 — Acceptance criterion validation: AC-11 API credentials view never shows secret  _(40 min)_
**Context:** PRD-08 AC-11 states: API credentials view shows Key ID and webhook URL but never shows the API secret. This end-to-end validation test uses an automated browser test (Playwright or Selenium) or a MockMvc full-stack test to verify AC-11. A partner_credential row is inserted with api_key='pk_live_testkey123' and api_secret_hash='$2a$12$examplehashvalue...'. The test loads /credentials as an authenticated Partner Viewer. It asserts: (1) the text 'pk_live_testkey123' is visible; (2) no text matching a bcrypt hash ($2a$...) is present anywhere in the DOM; (3) the literal string 'api_secret_hash' does not appear in the rendered HTML; (4) the text 'Not shown for security' is visible (from apiSecretNote field).
**Steps:** Create CredentialsSecretLeakTest.java (MockMvc or Playwright) targeting the credentials page; Insert test partner_credential with a known api_key and a bcrypt-format api_secret_hash value; Authenticate as Partner Viewer and request the credentials view; Assert the api_key value is present in the response; Assert the response body does not contain any substring matching /$2[ab]\$\d+\$/ (bcrypt prefix pattern); Assert the response body contains 'Not shown for security'
**Deliverable:** CredentialsSecretLeakTest.java (or .spec.ts for Playwright)
**Acceptance / logic checks:**
- api_key 'pk_live_testkey123' appears in rendered output
- No bcrypt hash substring ($2a$ or $2b$) appears anywhere in the response body or DOM
- String 'api_secret_hash' does not appear as a JSON key or visible text
- 'Not shown for security' text is present in the response
- Test fails if a code change accidentally includes the hash in the DTO serialisation
**Depends on:** 11.6-T08, 11.6-T16, 11.6-T21


## WBS 11.7 — Notifications (in-portal + email)
### 11.7-T01 — Create DB migration: portal_notification table  _(30 min)_
**Context:** GMEPay+ stores in-portal notifications per partner-user (PRD-08 §9.1). Four types exist: LOW_BALANCE (High severity), SETTLEMENT_STATEMENT_READY (Info), SETTLEMENT_CONFIRMED (Info), WEBHOOK_FAILURE (Warning). Dismissing a notification clears it only for that user; other users on the same partner retain it. The unread count per user drives the bell-icon badge.
**Steps:** Create migration file V11_7_001__create_portal_notification.sql; Add table portal_notification: id BIGSERIAL PK, partner_id BIGINT FK partner NOT NULL, hub_user_id BIGINT FK hub_user NOT NULL, notification_type VARCHAR(40) NOT NULL CHECK IN (LOW_BALANCE, SETTLEMENT_STATEMENT_READY, SETTLEMENT_CONFIRMED, WEBHOOK_FAILURE), severity VARCHAR(10) NOT NULL CHECK IN (HIGH, INFO, WARNING), payload JSONB NOT NULL DEFAULT {}, is_read BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), read_at TIMESTAMPTZ NULL; Add index idx_portal_notification_user_unread ON portal_notification(hub_user_id, is_read) WHERE is_read = FALSE; Add index idx_portal_notification_partner ON portal_notification(partner_id, created_at DESC); Write and apply migration; confirm table and indexes exist with \d
**Deliverable:** Migration file V11_7_001__create_portal_notification.sql with portal_notification table and two indexes
**Acceptance / logic checks:**
- Table portal_notification exists with all specified columns and correct types
- CHECK constraint on notification_type rejects INSERT with type OTHER
- CHECK constraint on severity rejects value CRITICAL
- Partial index idx_portal_notification_user_unread is present and confirmed by EXPLAIN on query WHERE hub_user_id = X AND is_read = FALSE
- Inserting two rows with same partner_id but different hub_user_id succeeds (no uniqueness constraint across users)

### 11.7-T02 — Create DB migration: low_balance_alert_config table  _(25 min)_
**Context:** Per DAT-03 §6.3, each OVERSEAS partner has a low_balance_alert_config row: id BIGSERIAL PK, partner_id BIGINT FK partner UNIQUE, threshold_usd DECIMAL(20,4) DEFAULT 10000.00, alert_email VARCHAR(255) (single legacy column - see NOTE), is_active BOOLEAN DEFAULT TRUE, created_at/updated_at/created_by/updated_by. The spec also references alert_recipients as a comma-separated list in onboarding (PRD-07 §5.3.3). Add alert_recipients TEXT[] to store multiple emails. Only applies to OVERSEAS partners (LOCAL partners have NULL prefunding). Default threshold: USD 10,000.
**Steps:** Create migration file V11_7_002__create_low_balance_alert_config.sql; Add table low_balance_alert_config: id BIGSERIAL PK, partner_id BIGINT FK partner UNIQUE NOT NULL, threshold_usd DECIMAL(20,4) NOT NULL DEFAULT 10000.00 CHECK threshold_usd >= 0, alert_recipients TEXT[] NOT NULL DEFAULT '{}', is_active BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMPTZ DEFAULT now(), updated_at TIMESTAMPTZ DEFAULT now(), created_by VARCHAR(120), updated_by VARCHAR(120); Add constraint chk_min_one_recipient CHECK (array_length(alert_recipients,1) >= 1) deferred - applied at application layer for onboarding flow; Add index idx_low_balance_alert_partner ON low_balance_alert_config(partner_id); Apply migration and verify
**Deliverable:** Migration file V11_7_002__create_low_balance_alert_config.sql with low_balance_alert_config table
**Acceptance / logic checks:**
- Table low_balance_alert_config exists with threshold_usd DECIMAL(20,4) DEFAULT 10000.00
- UNIQUE constraint on partner_id prevents two rows for the same partner
- CHECK threshold_usd >= 0 rejects INSERT with threshold_usd = -1
- alert_recipients column accepts TEXT[] e.g. ARRAY['ops@sendmn.com','finance@sendmn.com']
- migration is idempotent (run twice does not error with IF NOT EXISTS guard)

### 11.7-T03 — Create DB migration: email_notification_log table  _(25 min)_
**Context:** All outbound emails (low-balance alert, zero-balance urgent, settlement statement available, settlement confirmed) must be logged for audit and deduplication. Store: partner_id, notification_type (LOW_BALANCE, ZERO_BALANCE, SETTLEMENT_STATEMENT_READY, SETTLEMENT_CONFIRMED), recipient_email, subject, status (QUEUED, SENT, FAILED), attempt_count INT, last_attempted_at, sent_at, error_detail, payload JSONB (stores balance, threshold etc for traceability), created_at. Index on (partner_id, notification_type, created_at) for deduplication queries.
**Steps:** Create migration file V11_7_003__create_email_notification_log.sql; Define table email_notification_log with all columns listed in context; Add CHECK status IN ('QUEUED','SENT','FAILED','SUPPRESSED'); Add index idx_email_notif_partner_type ON email_notification_log(partner_id, notification_type, created_at DESC); Add index idx_email_notif_status ON email_notification_log(status) WHERE status IN ('QUEUED','FAILED'); Apply and verify with \d
**Deliverable:** Migration file V11_7_003__create_email_notification_log.sql with email_notification_log table and indexes
**Acceptance / logic checks:**
- Table exists with status CHECK constraint; INSERT with status='DELIVERED' is rejected
- Index idx_email_notif_partner_type exists and is used by EXPLAIN on (partner_id=X, notification_type='LOW_BALANCE')
- payload JSONB column accepts {"balance_usd":"9500.00","threshold_usd":"10000.00"} without error
- Inserting two rows with same partner_id and notification_type is allowed (no uniqueness - needed for repeated alerts)
- attempt_count column defaults to 0 and accepts increment to 3

### 11.7-T04 — Define NotificationEvent domain model and NotificationType enum  _(30 min)_
**Context:** Notification dispatch is triggered internally by the Prefunding service (after atomic deduction) and the Settlement service (after ZP0062/ZP0064 confirmation). Define a NotificationEvent value object that carries: partnerId, partnerName, notificationType (LOW_BALANCE, ZERO_BALANCE, SETTLEMENT_STATEMENT_READY, SETTLEMENT_CONFIRMED, WEBHOOK_FAILURE), severityLevel, payload Map<String,Object>. LOW_BALANCE payload must include: balanceUsd, thresholdUsd, suggestedTopupUsd (= threshold - balance, floored at 0), partnerName, topupInstructions. SETTLEMENT_CONFIRMED payload: settlementId, settlementDate, totalTransactions, totalPayoutKrw, totalPrefundDeductedUsd, batchFileRef. Use immutable record/POJO.
**Steps:** Create enum NotificationType {LOW_BALANCE, ZERO_BALANCE, SETTLEMENT_STATEMENT_READY, SETTLEMENT_CONFIRMED, WEBHOOK_FAILURE} in package notifications.domain; Create enum SeverityLevel {HIGH, INFO, WARNING}; Create immutable value object NotificationEvent with fields: partnerId (Long), partnerName (String), notificationType (NotificationType), severity (SeverityLevel), payload (Map<String,Object>); Add static factory methods: NotificationEvent.lowBalance(partnerId, partnerName, balanceUsd, thresholdUsd) and NotificationEvent.settlementConfirmed(partnerId, partnerName, settlementId, settlementDate, totalTransactions, totalPayoutKrw, totalPrefundDeductedUsd, batchFileRef); Validate all required fields non-null in constructors; throw IllegalArgumentException if null
**Deliverable:** NotificationType enum, SeverityLevel enum, NotificationEvent value object with factory methods
**Acceptance / logic checks:**
- NotificationEvent.lowBalance factory populates suggestedTopupUsd = threshold - balance; for balance=9500, threshold=10000 result is 500.00
- NotificationEvent.lowBalance with balance=10500 and threshold=10000 still produces event (caller is responsible for guard; value object is neutral)
- NotificationEvent.settlementConfirmed factory sets severity = INFO
- NotificationEvent constructed with null partnerId throws IllegalArgumentException
- All fields accessible via getters; object is immutable (no setters)
**Depends on:** 11.7-T01, 11.7-T02, 11.7-T03

### 11.7-T05 — Implement LowBalanceAlertConfigRepository (JPA/JDBC)  _(40 min)_
**Context:** low_balance_alert_config table (from 11.7-T02) stores per-partner threshold and recipient list. The repository must support: findByPartnerId(Long) returning Optional, findAllActive() returning all rows where is_active=TRUE, save(LowBalanceAlertConfig), upsert on partner_id (one row per partner). Entity maps: id, partnerId, thresholdUsd (BigDecimal), alertRecipients (List<String> mapped from TEXT[]), isActive, audit fields. Only OVERSEAS partners have config rows; LOCAL partners return empty Optional.
**Steps:** Create JPA entity LowBalanceAlertConfig mapping to low_balance_alert_config table; Map alertRecipients as @Type(PostgreSQLArrayType.class) for TEXT[]; Create Spring Data repository LowBalanceAlertConfigRepository extending JpaRepository<LowBalanceAlertConfig, Long>; Add custom method Optional<LowBalanceAlertConfig> findByPartnerId(Long partnerId); Add custom query List<LowBalanceAlertConfig> findAllByIsActiveTrue(); Write integration test: insert two rows (partnerId=1 active, partnerId=2 inactive), verify findAllByIsActiveTrue returns only partnerId=1
**Deliverable:** LowBalanceAlertConfig entity + LowBalanceAlertConfigRepository with integration test
**Acceptance / logic checks:**
- findByPartnerId(1L) returns config with thresholdUsd=10000.00 and alertRecipients=['a@b.com','c@d.com'] matching inserted data
- findByPartnerId(99L) returns Optional.empty()
- findAllByIsActiveTrue omits rows where is_active=FALSE
- alertRecipients round-trips correctly as List<String> through persist and findById
- Entity save() on duplicate partnerId updates the existing row (upsert via @Transactional + findOrCreate pattern)
**Depends on:** 11.7-T02

### 11.7-T06 — Implement PortalNotificationRepository (JPA/JDBC)  _(40 min)_
**Context:** portal_notification table (from 11.7-T01) stores in-portal notifications per hub_user_id. Key queries: (a) fetch unread count per user, (b) fetch paginated notification list for a user, (c) mark one or all as read for a user (sets is_read=TRUE, read_at=now()). No global delete - individual user dismiss only. Notifications are OVERSEAS-partner scoped but hub_user is the isolation key.
**Steps:** Create JPA entity PortalNotification mapping to portal_notification; map payload as JSONB via Map<String,Object>; Create PortalNotificationRepository extending JpaRepository; Add long countByHubUserIdAndIsReadFalse(Long hubUserId); Add Page<PortalNotification> findByHubUserIdOrderByCreatedAtDesc(Long hubUserId, Pageable pageable); Add @Modifying @Query to markAsRead(Long notificationId, Long hubUserId): UPDATE portal_notification SET is_read=TRUE, read_at=now() WHERE id=:id AND hub_user_id=:userId; Add @Modifying @Query to markAllAsRead(Long hubUserId): UPDATE portal_notification SET is_read=TRUE, read_at=now() WHERE hub_user_id=:userId AND is_read=FALSE
**Deliverable:** PortalNotification entity + PortalNotificationRepository with markAsRead and markAllAsRead mutations
**Acceptance / logic checks:**
- countByHubUserIdAndIsReadFalse returns 2 after inserting 2 unread notifications for user 1 and 1 unread for user 2
- markAsRead(notificationId=5, hubUserId=10) returns 0 updated rows when notification 5 belongs to user 99 (cross-user safety)
- markAllAsRead(hubUserId=10) sets is_read=TRUE only for user 10's rows
- findByHubUserIdOrderByCreatedAtDesc returns items newest-first (assert first item created_at > second)
- payload JSONB stored as Map<String,Object> retrieves {balanceUsd:9500.00} correctly
**Depends on:** 11.7-T01

### 11.7-T07 — Implement EmailNotificationLogRepository  _(35 min)_
**Context:** email_notification_log table (from 11.7-T03) records all outbound email dispatch attempts. Key operations: insert new log entry with status QUEUED, update status to SENT or FAILED with timestamp, query recent LOW_BALANCE entries for a partner to support suppression (avoid spamming). Suppression window: do not resend LOW_BALANCE email to same partner within 1 hour of a previous SENT entry.
**Steps:** Create JPA entity EmailNotificationLog mapping to email_notification_log; Create EmailNotificationLogRepository extending JpaRepository; Add Optional<EmailNotificationLog> findFirstByPartnerIdAndNotificationTypeOrderBySentAtDesc(Long partnerId, String notificationType); Add @Modifying updateStatus(Long id, String status, String errorDetail, Instant sentAt); Add boolean existsSentSince(Long partnerId, String notificationType, Instant since) via @Query checking status='SENT' and sent_at >= :since; Write unit test: insert SENT LOW_BALANCE for partner=1 at T-30min, then existsSentSince(1, LOW_BALANCE, T-60min) returns TRUE, existsSentSince(1, LOW_BALANCE, T-10min) returns FALSE
**Deliverable:** EmailNotificationLog entity + EmailNotificationLogRepository with suppression query and unit test
**Acceptance / logic checks:**
- existsSentSince(partnerId=1, LOW_BALANCE, now()-60min) returns TRUE when a SENT row exists at now()-30min
- existsSentSince(partnerId=1, LOW_BALANCE, now()-10min) returns FALSE for same row (row is outside the 10-min window)
- findFirstByPartnerIdAndNotificationTypeOrderBySentAtDesc returns most recent row for the partner
- updateStatus correctly sets status=SENT and sent_at on the row
- Insert with status=QUEUED succeeds; attempt_count defaults to 0
**Depends on:** 11.7-T03

### 11.7-T08 — Implement EmailDispatchService (SMTP/SES integration)  _(45 min)_
**Context:** PRD-08 §9.2 states email delivery uses the GMEPay+ platform email service (SMTP relay / SES). Service must send templated emails with: subject, recipient list (from alert_recipients), HTML and plain-text body. Config: spring.mail.host, spring.mail.port, spring.mail.username, spring.mail.password, from.address (all env-injectable). Service must return a success/failure result so callers can update email_notification_log. No retry logic here (retry handled by caller). Throw EmailDispatchException on SMTP error with root cause.
**Steps:** Add spring-boot-starter-mail dependency; Create application config class EmailConfig binding spring.mail.* properties; Create EmailDispatchService with method EmailDispatchResult send(String subject, List<String> recipients, String htmlBody, String plainTextBody); Implement using JavaMailSender; build MimeMessage with multipart/alternative (text/plain + text/html parts); Return EmailDispatchResult(success=true, messageId) on success; catch MailException and return EmailDispatchResult(success=false, error=e.getMessage()); Write unit test with MockJavaMailSender: assert MimeMessage is built correctly (correct recipients, from address, multipart)
**Deliverable:** EmailDispatchService with send() method, EmailConfig, unit test with mock sender
**Acceptance / logic checks:**
- Unit test with MockJavaMailSender verifies To: header equals recipients list ['a@b.com','c@d.com']
- Unit test verifies From: header equals configured from.address value
- MailException from JavaMailSender is caught and returns EmailDispatchResult with success=false
- send() with empty recipients list throws IllegalArgumentException before attempting SMTP
- Config binds from application.properties: spring.mail.host=localhost, spring.mail.port=587

### 11.7-T09 — Implement email templates for low-balance and zero-balance alerts  _(45 min)_
**Context:** PRD-08 §9.2: Low-balance email content must include current balance (USD), threshold (USD), top-up instructions, and GME account team contact. Zero-balance email is an urgent notice stating all overseas payments are suspended. Use Thymeleaf (or equivalent template engine). Template variables for low-balance: partnerName, balanceUsd, thresholdUsd, suggestedTopupUsd, topupInstructions (static text: wire to GME bank account with reference format partner_code + date), accountTeamContact. Zero-balance template: partnerName, suspendedAt (ISO-8601 KST). Subject lines: low-balance = [GMEPay+] Low Balance Alert: {partnerName} - USD {balanceUsd} remaining; zero-balance = [GMEPay+ URGENT] Zero Balance: {partnerName} payments suspended.
**Steps:** Create Thymeleaf template resources/templates/email/low-balance-alert.html with all required variables; Create plain-text variant low-balance-alert.txt; Create resources/templates/email/zero-balance-alert.html and zero-balance-alert.txt; Create EmailTemplateService with renderLowBalanceAlert(partnerName, balanceUsd, thresholdUsd, suggestedTopupUsd) returning RenderedEmail(subject, htmlBody, plainText); Create renderZeroBalanceAlert(partnerName, suspendedAt) returning RenderedEmail; Write unit test: renderLowBalanceAlert(SendMN, 9500.00, 10000.00, 500.00) - assert subject contains SendMN and 9500.00; HTML body contains top-up instructions text
**Deliverable:** Two email templates (low-balance, zero-balance) + EmailTemplateService with unit test
**Acceptance / logic checks:**
- renderLowBalanceAlert with balanceUsd=9500.00 produces subject containing USD 9500.00
- renderLowBalanceAlert HTML body contains suggestedTopupUsd=500.00
- renderZeroBalanceAlert subject starts with [GMEPay+ URGENT]
- renderZeroBalanceAlert HTML body contains the word 'suspended'
- Plain-text variants contain the same key data as HTML (no HTML tags in .txt output)
**Depends on:** 11.7-T04, 11.7-T08

### 11.7-T10 — Implement email templates for settlement-complete and statement-ready notifications  _(40 min)_
**Context:** PRD-08 §9.2: Settlement confirmed email content = confirmation with totals. Settlement statement available email = link to portal statement page + period summary. Settlement confirmed template variables: partnerName, settlementId, settlementDate (e.g. 2026-06-03), totalTransactions, totalPayoutKrw (formatted with comma separator, 0 decimals per KRW scale), totalPrefundDeductedUsd (2 decimals). Statement available variables: partnerName, period (e.g. May 2026), portalStatementUrl. Subject: [GMEPay+] Settlement Confirmed for {settlementDate}; [GMEPay+] Settlement Statement Ready: {period}.
**Steps:** Create Thymeleaf templates email/settlement-confirmed.html and settlement-confirmed.txt; Create email/settlement-statement-ready.html and settlement-statement-ready.txt; Add renderSettlementConfirmed(partnerName, settlementId, settlementDate, totalTransactions, totalPayoutKrw, totalPrefundDeductedUsd) to EmailTemplateService; Add renderSettlementStatementReady(partnerName, period, portalStatementUrl) to EmailTemplateService; Write unit test: renderSettlementConfirmed produces subject containing the settlementDate; HTML body contains totalPayoutKrw formatted as 7,125,000 (KRW no decimals); Write unit test: renderSettlementStatementReady HTML body contains the portalStatementUrl
**Deliverable:** Two settlement email templates + EmailTemplateService methods with unit tests
**Acceptance / logic checks:**
- renderSettlementConfirmed(partnerName=SendMN, settlementDate=2026-06-03, totalTransactions=142, totalPayoutKrw=7125000, totalPrefundDeductedUsd=5023.89) subject contains 2026-06-03
- HTML body formats totalPayoutKrw as KRW 7,125,000 (comma-separated, 0 decimals)
- HTML body shows totalPrefundDeductedUsd as USD 5,023.89 (2 decimals)
- renderSettlementStatementReady HTML body contains a clickable link to portalStatementUrl
- Plain-text variants are free of HTML tags
**Depends on:** 11.7-T08

### 11.7-T11 — Implement LowBalanceNotificationService - core dispatch logic  _(55 min)_
**Context:** After each successful prefunding deduction, the Prefunding service publishes a prefunding.low_balance event (SAD-02 §7 message queue). The LowBalanceNotificationService consumes this event and: (1) checks if balance < threshold for the partner; (2) if yes and not suppressed within 1 hour (per 11.7-T07), sends email to all alert_recipients via EmailDispatchService; (3) logs attempt in email_notification_log; (4) creates in-portal portal_notification rows for all hub_users of the partner. Zero-balance case: if balance <= 0, use zero-balance email template and create URGENT in-portal notification. Transaction is never blocked. Service is OVERSEAS-only (skip if partner type = LOCAL).
**Steps:** Create LowBalanceNotificationService consuming LowBalanceEvent(partnerId, partnerName, balanceUsd BigDecimal, thresholdUsd BigDecimal, deductedByPaymentId); Add partner type guard: load partner, return immediately if type = LOCAL; Check balance < threshold; if not below threshold, return (no notification); Check suppression: call emailNotificationLogRepo.existsSentSince(partnerId, LOW_BALANCE, now()-1h); if TRUE skip email but still create in-portal notification if not already created in last 1h; Else: render email, send via EmailDispatchService, log result in email_notification_log; Create portal_notification rows for each active hub_user of the partner: notificationType=LOW_BALANCE, severity=HIGH, payload={balanceUsd, thresholdUsd}; For zero-balance (balance <= 0): use ZERO_BALANCE type, send urgent email regardless of suppression window, create portal_notification with type=LOW_BALANCE severity=HIGH
**Deliverable:** LowBalanceNotificationService with guard logic, suppression check, email send, portal notification creation
**Acceptance / logic checks:**
- LOCAL partner event returns immediately without any email send or notification insert
- balance=10001 with threshold=10000 returns without action (above threshold)
- balance=9500 with threshold=10000 triggers email to all alert_recipients and inserts portal_notification rows for all partner hub_users
- Second identical event within 1 hour: email is suppressed (existsSentSince returns true) but portal_notification row is still inserted if none in last 1h
- balance=0 triggers ZERO_BALANCE email bypassing suppression window and inserts portal_notification with severity=HIGH
**Depends on:** 11.7-T04, 11.7-T05, 11.7-T06, 11.7-T07, 11.7-T08, 11.7-T09

### 11.7-T12 — Implement SettlementCompleteNotificationService  _(50 min)_
**Context:** When a ZeroPay settlement batch (ZP0062 for morning, ZP0064 for afternoon) is confirmed, the settlement engine emits a settlement.completed event with: settlementId, settlementDate (DATE), schemeId=zeropay, totalTransactions, totalPayoutKrw, totalPrefundDeductedUsd, batchFileRef. SettlementCompleteNotificationService must: (1) for each OVERSEAS partner with transactions in the settlement, send a settlement-confirmed email to all alert_recipients; (2) create in-portal portal_notification rows for all hub_users of each affected partner; (3) also create a SETTLEMENT_STATEMENT_READY in-portal notification when the monthly statement becomes available (triggered separately by the statement-generation job). The settlement confirmed email uses template from 11.7-T10. No suppression - send once per settlement_id per partner.
**Steps:** Create SettlementCompleteNotificationService with handleSettlementCompleted(SettlementCompletedEvent event, List<Long> affectedPartnerIds); For each partnerId in affectedPartnerIds: load partner, skip if LOCAL; Check email_notification_log: skip if SENT entry already exists for (partnerId, SETTLEMENT_CONFIRMED, settlementId) to prevent duplicate send on retry; Render email via EmailTemplateService.renderSettlementConfirmed; send via EmailDispatchService; log result; Create portal_notification row for each hub_user: type=SETTLEMENT_CONFIRMED, severity=INFO, payload={settlementId, settlementDate, totalTransactions}; Add separate method notifyStatementReady(Long partnerId, String period, String portalStatementUrl): send SETTLEMENT_STATEMENT_READY email and create in-portal notification
**Deliverable:** SettlementCompleteNotificationService handling settlement.completed event and statement-ready notification
**Acceptance / logic checks:**
- LOCAL partner is skipped (no email, no portal_notification inserted)
- OVERSEAS partner with 1 hub_user gets 1 portal_notification row with type=SETTLEMENT_CONFIRMED and severity=INFO
- Re-processing same settlementId for same partner (retry scenario) does NOT send a second email (idempotency check via email_notification_log)
- notifyStatementReady creates portal_notification with type=SETTLEMENT_STATEMENT_READY and emails portalStatementUrl to all alert_recipients
- Event with two affected OVERSEAS partners creates 2 separate email_notification_log rows, one per partner
**Depends on:** 11.7-T06, 11.7-T07, 11.7-T08, 11.7-T10

### 11.7-T13 — Implement GET /internal/v1/notifications REST endpoint (unread count)  _(45 min)_
**Context:** The partner portal header bell icon must display the unread notification count per logged-in partner-user. Implement an internal REST endpoint (partner-portal backend calls this, authenticated via session token). GET /internal/v1/notifications/count returns {unreadCount: N} for the authenticated hub_user_id. Also implement GET /internal/v1/notifications (paginated list) returning notifications for that user, newest-first, with fields: id, notificationType, severity, payload, isRead, createdAt. Pagination: page and size params, default size=20. Authentication: read hub_user_id from security context (JWT/session).
**Steps:** Create NotificationController in the partner portal API layer; Implement GET /internal/v1/notifications/count: extract hubUserId from SecurityContext, call portalNotificationRepo.countByHubUserIdAndIsReadFalse(hubUserId), return {unreadCount: N}; Implement GET /internal/v1/notifications?page=0&size=20: call portalNotificationRepo.findByHubUserIdOrderByCreatedAtDesc with Pageable, map to NotificationDto; Create NotificationDto: id, notificationType (String), severity (String), payload (Map<String,Object>), isRead (boolean), createdAt (ISO-8601 UTC); Secure both endpoints: require authenticated hub_user; a user can only read their own notifications (hubUserId from token, not request param); Add integration test: insert 3 notifications for user 1 (2 unread, 1 read) and 1 for user 2; GET /count for user 1 returns {unreadCount:2}
**Deliverable:** NotificationController with /count and paginated list endpoints + integration test
**Acceptance / logic checks:**
- GET /count for user with 2 unread notifications returns {unreadCount:2}
- GET /count for user with 0 notifications returns {unreadCount:0}
- GET /notifications returns newest-first (assert createdAt of first item > second item)
- Attempt to GET /notifications without authentication returns 401
- GET /notifications?page=0&size=5 with 10 notifications returns exactly 5 items and correct pagination metadata
**Depends on:** 11.7-T06

### 11.7-T14 — Implement POST /internal/v1/notifications/{id}/read and /read-all endpoints  _(35 min)_
**Context:** PRD-08 §9.1: Dismissing a notification clears it for that user only. Implement: POST /internal/v1/notifications/{id}/read marks a single notification as read for the authenticated user; POST /internal/v1/notifications/read-all marks all unread as read for the authenticated user. Both return 204 No Content. Cross-user read attempt must be silently ignored (returns 204 but updates 0 rows) - do NOT return 403 to avoid information leakage about notification existence.
**Steps:** Add POST /internal/v1/notifications/{id}/read to NotificationController; Extract hubUserId from SecurityContext; call portalNotificationRepo.markAsRead(id, hubUserId); return 204; Add POST /internal/v1/notifications/read-all; Call portalNotificationRepo.markAllAsRead(hubUserId); return 204; Write integration test: user A marks user B's notification as read - verify 204 returned but notification still shows unread for user B; Write integration test: read-all for user A marks 3 unread as read; /count subsequently returns {unreadCount:0}
**Deliverable:** Mark-read and read-all endpoints with cross-user isolation integration test
**Acceptance / logic checks:**
- POST /read on own unread notification returns 204 and subsequent /count decrements by 1
- POST /read on notification owned by another user returns 204 but does not change that notification's is_read status
- POST /read-all for user with 3 unread returns 204; subsequent /count returns {unreadCount:0}
- POST /read on already-read notification is idempotent (returns 204, no error)
- POST /read without authentication returns 401
**Depends on:** 11.7-T06, 11.7-T13

### 11.7-T15 — Wire low-balance event publisher into Prefunding deduction flow  _(50 min)_
**Context:** The Prefunding service (WBS component) performs atomic deduction via SELECT FOR UPDATE on prefunding_account. After a successful commit, it must publish a LowBalanceEvent to trigger the notification service. The event must carry: partnerId, partnerName, balanceUsd (the balance AFTER deduction from prefunding_account.balance), thresholdUsd (from low_balance_alert_config.threshold_usd), deductedByPaymentId. Publishing must be inside the same transaction if using an outbox pattern, or immediately after commit via ApplicationEvent. The scheme is never called before the deduction is committed. Low-balance publishing must NOT affect deduction atomicity or block the payment flow.
**Steps:** Locate the prefunding deduction service method (performs SELECT FOR UPDATE on prefunding_account); After successful deduction commit, retrieve updated balance and threshold from low_balance_alert_config; Publish LowBalanceEvent using Spring ApplicationEventPublisher (or message queue if outbox pattern is established in the project); Use @TransactionalEventListener(phase = AFTER_COMMIT) on LowBalanceNotificationService to ensure event fires only after successful commit; Confirm no exception in notification dispatch can roll back or delay the parent deduction transaction; Write integration test: deduction dropping balance from 10500 to 9500 (below threshold 10000) results in LowBalanceEvent published with balanceUsd=9500
**Deliverable:** LowBalanceEvent publisher wired into deduction service; @TransactionalEventListener on LowBalanceNotificationService; integration test
**Acceptance / logic checks:**
- Deduction dropping balance from USD 10,500 to USD 9,500 publishes LowBalanceEvent with balanceUsd=9500.00 and thresholdUsd=10000.00
- Deduction dropping balance from USD 12,000 to USD 11,000 does NOT publish LowBalanceEvent (still above threshold)
- Exception in LowBalanceNotificationService does NOT roll back the deduction (payment succeeds regardless)
- Deduction that drops balance exactly to 0 publishes LowBalanceEvent with balanceUsd=0.00
- Event carries the correct deductedByPaymentId matching the committed payment
**Depends on:** 11.7-T11

### 11.7-T16 — Wire settlement-complete event consumer into settlement batch processing flow  _(50 min)_
**Context:** The Settlement Engine processes ZP0062 (morning) and ZP0064 (afternoon) ZeroPay settlement receipt files and, upon successful reconciliation, transitions settlement_batch.status to RECONCILED. At this point it must invoke SettlementCompleteNotificationService.handleSettlementCompleted() with the settlementId, settlementDate, schemeId, totalTransactions, totalPayoutKrw, totalPrefundDeductedUsd, batchFileRef, and the list of affectedPartnerIds (OVERSEAS partners with at least 1 transaction in the batch). Wiring must be transactional-safe: notification is sent after batch status is committed.
**Steps:** Locate the settlement batch reconciliation completion code path (status transition to RECONCILED); After successful RECONCILED transition commit, collect the list of OVERSEAS partnerIds with transactions in this batch from reconciliation_item table; Build SettlementCompletedEvent with all required fields; Publish via @TransactionalEventListener(AFTER_COMMIT) to SettlementCompleteNotificationService.handleSettlementCompleted; Write integration test: mock settlement batch with 2 OVERSEAS partner transactions transitions to RECONCILED, assert handleSettlementCompleted is called with affectedPartnerIds containing both partner IDs; Confirm LOCAL partner transactions in the same batch do NOT appear in affectedPartnerIds
**Deliverable:** SettlementCompletedEvent publisher wired in settlement reconciliation; @TransactionalEventListener handler; integration test
**Acceptance / logic checks:**
- Batch with 2 OVERSEAS and 1 LOCAL partner transitions to RECONCILED; handleSettlementCompleted called with exactly 2 partner IDs
- handleSettlementCompleted is not called if batch transitions to ERROR state instead of RECONCILED
- Local partner ID is excluded from affectedPartnerIds
- SettlementCompletedEvent carries correct batchFileRef (e.g. ZP0062-20260604)
- Exception in handleSettlementCompleted does not rollback the RECONCILED batch status
**Depends on:** 11.7-T12

### 11.7-T17 — Wire statement-ready notification into statement generation job  _(35 min)_
**Context:** PRD-08 §9.2 and AC-10: settlement statement available within T+1 business day; email sent with link to portal statement page. A scheduled job generates monthly settlement statements and, upon completion, calls SettlementCompleteNotificationService.notifyStatementReady(partnerId, period, portalStatementUrl). The portalStatementUrl must be constructed as {partnerPortalBaseUrl}/statements/{statementId}. Config property: partner.portal.base-url. Each partner gets a separate notification. Applies to OVERSEAS partners with statements for the period.
**Steps:** Locate or create the statement generation job (cron-scheduled); After successful statement generation for a partner, build portalStatementUrl = partnerPortalBaseUrl + /statements/ + statementId; Call SettlementCompleteNotificationService.notifyStatementReady(partnerId, period, portalStatementUrl); Inject partner.portal.base-url from application configuration; Write unit test: statementId=stmt_001, baseUrl=https://portal.gmepay.com -> url = https://portal.gmepay.com/statements/stmt_001; Confirm LOCAL partners are skipped in the statement-ready notification
**Deliverable:** Statement-ready notification wired into statement generation job + URL construction unit test
**Acceptance / logic checks:**
- notifyStatementReady called with portalStatementUrl=https://portal.gmepay.com/statements/stmt_001 when baseUrl=https://portal.gmepay.com and statementId=stmt_001
- LOCAL partner statement generation does NOT call notifyStatementReady
- Period string passed as May 2026 for statements covering calendar month May 2026
- notifyStatementReady creates 1 email_notification_log row with type=SETTLEMENT_STATEMENT_READY for the partner
- notifyStatementReady creates portal_notification rows for all hub_users of the partner with type=SETTLEMENT_STATEMENT_READY
**Depends on:** 11.7-T12

### 11.7-T18 — Implement dashboard low-balance indicator API response field  _(30 min)_
**Context:** PRD-08 §3.4 and §6.4: the partner portal dashboard must display a low-balance indicator (red badge) when current_balance < low_balance_threshold. The existing GET /internal/v1/balance endpoint (from prefunding module) already returns balance_usd and low_balance_threshold_usd. Verify that the is_below_threshold boolean is correctly derived and returned. API-05 §4.8 specifies: is_below_threshold = TRUE if balance_usd < low_balance_threshold_usd (strict less-than). Ensure the partner portal dashboard component can use this flag without additional logic. Also confirm the field is absent (or null) for LOCAL partners.
**Steps:** Locate the GET /internal/v1/balance or equivalent partner balance endpoint; Confirm is_below_threshold = (balance_usd < low_balance_threshold_usd) is evaluated server-side using BigDecimal.compareTo(); Ensure is_below_threshold is absent or explicitly null in the response for LOCAL partners (HTTP 403 or null field); Write unit test: balance=9999.99, threshold=10000.00 -> is_below_threshold=true; balance=10000.00, threshold=10000.00 -> is_below_threshold=false; balance=10000.01, threshold=10000.00 -> is_below_threshold=false; Confirm the dashboard panel reads is_below_threshold to show/hide the red badge (frontend integration point documented)
**Deliverable:** Verified and tested is_below_threshold field in balance response; unit test with boundary values
**Acceptance / logic checks:**
- balance=9999.99 with threshold=10000.00 returns is_below_threshold=true
- balance=10000.00 with threshold=10000.00 returns is_below_threshold=false (threshold is exclusive lower bound)
- balance=10000.01 with threshold=10000.00 returns is_below_threshold=false
- LOCAL partner GET /balance returns HTTP 403 (per API-05 §4.8 spec)
- Comparison uses BigDecimal.compareTo() not floating-point equality to avoid rounding error
**Depends on:** 11.7-T05

### 11.7-T19 — Implement GME Ops admin API: configure low-balance alert settings  _(45 min)_
**Context:** PRD-08 §9 and PRD-07 §5.3.3: GME Ops configures low-balance alert threshold (default USD 10,000) and alert_recipients (minimum 1 email) during partner onboarding and can update them later. Admin System only; partners cannot self-manage in Phase 1. Endpoint: PUT /admin/v1/partners/{partnerId}/alert-config accepts: thresholdUsd (DECIMAL, required, >= 0), alertRecipients (array of valid email strings, min 1 element). Response: updated alert config. Validate email format per RFC 5322 simple pattern. Requires OVERSEAS partner type. Change is audit-logged (actor, timestamp, previous threshold, previous recipients).
**Steps:** Create PUT /admin/v1/partners/{partnerId}/alert-config in admin partner controller; Validate partnerId exists and type=OVERSEAS; return 422 PARTNER_TYPE_INVALID for LOCAL; Validate request: thresholdUsd >= 0, alertRecipients min 1 element, each email matches [^@]+@[^@]+\.[^@]+ pattern; Upsert low_balance_alert_config via LowBalanceAlertConfigRepository; Write audit log entry: entity=LOW_BALANCE_ALERT_CONFIG, entityId=partnerId, actor=authenticated operator, previousThreshold, newThreshold, previousRecipients, newRecipients; Return 200 with updated config; return 404 if partner not found
**Deliverable:** PUT /admin/v1/partners/{partnerId}/alert-config endpoint with validation and audit logging
**Acceptance / logic checks:**
- PUT for OVERSEAS partner with thresholdUsd=5000.00, alertRecipients=['a@b.com'] returns 200 and persists threshold=5000.00
- PUT for LOCAL partner returns 422 with error code PARTNER_TYPE_INVALID
- PUT with alertRecipients=[] (empty array) returns 422 with validation error
- PUT with alertRecipients=['not-an-email'] returns 422 with email format error
- Audit log entry exists with previousThreshold and newThreshold after a change from 10000 to 5000
**Depends on:** 11.7-T05, 11.7-T04

### 11.7-T20 — Implement GET /admin/v1/partners/{partnerId}/alert-config endpoint  _(30 min)_
**Context:** Admin operators need to view the current low-balance alert configuration for a partner (threshold, recipients, active status) in the Partner Management module. GET /admin/v1/partners/{partnerId}/alert-config returns the current low_balance_alert_config row for the partner. Returns 404 if not found. Returns 403 if the requesting user lacks OVERSEAS partner access. Also used to pre-populate the edit form in PUT (11.7-T19). Response fields: partnerId, thresholdUsd, alertRecipients (array), isActive, updatedAt, updatedBy.
**Steps:** Add GET /admin/v1/partners/{partnerId}/alert-config to admin partner controller; Load config via LowBalanceAlertConfigRepository.findByPartnerId(partnerId); If not found return 404 with body {error: ALERT_CONFIG_NOT_FOUND}; Map to AlertConfigDto: partnerId, thresholdUsd, alertRecipients, isActive, updatedAt, updatedBy; Secure with role check: requires OPS_OPERATOR or SUPER_ADMIN role; Write integration test: create config, GET returns correct thresholdUsd and alertRecipients array
**Deliverable:** GET /admin/v1/partners/{partnerId}/alert-config endpoint with integration test
**Acceptance / logic checks:**
- GET for partner with threshold=5000.00 and recipients=['a@b.com','b@c.com'] returns both recipients in array form
- GET for partner with no alert config row returns 404 ALERT_CONFIG_NOT_FOUND
- GET with FINANCE_ANALYST role (insufficient) returns 403
- Response includes updatedBy showing the operator ID of the last change
- thresholdUsd returned as string decimal (e.g. '5000.00') not float
**Depends on:** 11.7-T05, 11.7-T19

### 11.7-T21 — Unit tests for LowBalanceNotificationService - dispatch logic  _(50 min)_
**Context:** 11.7-T11 implements the core dispatch logic. Write comprehensive unit tests covering: all conditional branches (LOCAL skip, above-threshold skip, suppression, below-threshold send, zero-balance), verifying collaborator interactions via mocks. Use Mockito. Test class: LowBalanceNotificationServiceTest.
**Steps:** Mock all dependencies: LowBalanceAlertConfigRepository, EmailNotificationLogRepository, PortalNotificationRepository, EmailDispatchService, EmailTemplateService, PartnerRepository; Test case 1: partner type LOCAL -> verify emailDispatchService.send() is never called; Test case 2: balanceUsd=10500, threshold=10000 -> no send, no portal_notification; Test case 3: balanceUsd=9500, threshold=10000, no suppression -> send() called once with recipients=['a@b.com'], portal_notification inserted; Test case 4: balanceUsd=9500, threshold=10000, suppressed (existsSentSince=true) -> send() NOT called, but portal_notification IS inserted (deduplicate check: if no portal_notification in last 1h); Test case 5: balanceUsd=0, threshold=10000 -> zero-balance template used, send() called regardless of suppression
**Deliverable:** LowBalanceNotificationServiceTest with 5+ test cases covering all conditional branches
**Acceptance / logic checks:**
- Test case 1 passes: emailDispatchService.send() verify(never())
- Test case 2 passes: no calls to emailDispatchService or portalNotificationRepository
- Test case 3 passes: emailDispatchService.send() called with correct recipients; email_notification_log saved with status=SENT
- Test case 4 passes: emailDispatchService.send() not called; portalNotificationRepository.save() called once
- Test case 5 passes: renderZeroBalanceAlert() called instead of renderLowBalanceAlert()
**Depends on:** 11.7-T11

### 11.7-T22 — Unit tests for SettlementCompleteNotificationService  _(50 min)_
**Context:** 11.7-T12 implements settlement notification dispatch. Write unit tests covering: LOCAL partner skip, OVERSEAS send with correct template, idempotency (duplicate settlementId), notifyStatementReady correct URL. Use Mockito.
**Steps:** Mock: EmailDispatchService, EmailTemplateService, EmailNotificationLogRepository, PortalNotificationRepository, PartnerRepository; Test case 1: LOCAL partner in affectedPartnerIds -> no email, no portal_notification; Test case 2: OVERSEAS partner, fresh settlementId -> renderSettlementConfirmed called; email sent; portal_notification inserted with type=SETTLEMENT_CONFIRMED; Test case 3: same OVERSEAS partner, same settlementId called again (retry) -> no second email (idempotency: emailNotificationLogRepo returns existing SENT entry); Test case 4: notifyStatementReady -> renderSettlementStatementReady called with correct period and URL; email sent; portal_notification type=SETTLEMENT_STATEMENT_READY; Test case 5: EmailDispatchService throws exception -> status=FAILED logged in email_notification_log; no exception propagated to caller
**Deliverable:** SettlementCompleteNotificationServiceTest with 5 test cases
**Acceptance / logic checks:**
- Test case 1 passes: verify no calls to emailDispatchService
- Test case 2 passes: renderSettlementConfirmed called with totalPayoutKrw=7125000; portal_notification save() called
- Test case 3 passes: emailDispatchService.send() verify(never()) on second invocation
- Test case 4 passes: renderSettlementStatementReady called with portalStatementUrl containing statementId
- Test case 5 passes: email_notification_log saved with status=FAILED and errorDetail non-null; no exception thrown
**Depends on:** 11.7-T12

### 11.7-T23 — Unit tests for EmailTemplateService - all four templates  _(40 min)_
**Context:** 11.7-T09 and 11.7-T10 implement four email templates. Write parameterized unit tests verifying subject lines, required body content, and formatting for each template. Test vectors from spec: SendMN, balance=9500.00, threshold=10000.00, totalPayoutKrw=7125000, totalPrefundDeductedUsd=5023.89, settlementDate=2026-06-03.
**Steps:** Write test for renderLowBalanceAlert(SendMN, 9500.00, 10000.00, 500.00): assert subject contains SendMN and 9500.00; html body contains 10,000.00 and 500.00 and top-up instructions text; Write test for renderZeroBalanceAlert(SendMN, 2026-06-05T01:00:00Z): assert subject starts with [GMEPay+ URGENT]; html body contains suspended; Write test for renderSettlementConfirmed(SendMN, stl_001, 2026-06-03, 142, 7125000, 5023.89): assert subject contains 2026-06-03; html body contains 7,125,000 (comma-formatted KRW) and 5,023.89; Write test for renderSettlementStatementReady(SendMN, May 2026, https://portal.gmepay.com/statements/stmt_001): assert html body contains full URL; Write test for plain-text variant: renderLowBalanceAlert plain text does not contain < HTML tags
**Deliverable:** EmailTemplateServiceTest with 5 parameterized test cases covering all 4 templates
**Acceptance / logic checks:**
- renderLowBalanceAlert subject contains both partnerName and balanceUsd formatted as 9,500.00
- renderZeroBalanceAlert subject starts with exactly [GMEPay+ URGENT]
- renderSettlementConfirmed formats totalPayoutKrw=7125000 as KRW 7,125,000 in HTML body
- renderSettlementStatementReady HTML body contains the full https://portal.gmepay.com/statements/stmt_001 URL
- Low-balance plain-text body does not match regex .*<[a-z]+>.*
**Depends on:** 11.7-T09, 11.7-T10

### 11.7-T24 — Integration test: full low-balance notification flow end-to-end  _(55 min)_
**Context:** Verify the full path: prefunding deduction -> LowBalanceEvent -> LowBalanceNotificationService -> email_notification_log + portal_notification. Use embedded PostgreSQL (Testcontainers) and MockJavaMailSender. Scenario: partner SendMN (OVERSEAS), threshold=10000, alert_recipients=['ops@sendmn.com'], 2 active hub_users. Deduction takes balance from 11000 to 9500.
**Steps:** Configure Testcontainers with real PostgreSQL; apply all migrations through V11_7_003; Seed: partner SENDMN OVERSEAS, low_balance_alert_config (threshold=10000, recipients=[ops@sendmn.com]), 2 hub_users, prefunding_account balance=11000.00; Execute deduction of 1500.00 (balance -> 9500.00) via the deduction service method; Wait for @TransactionalEventListener AFTER_COMMIT to fire (synchronous in test context); Assert email_notification_log has 1 row with status=SENT, notification_type=LOW_BALANCE, recipient_email=ops@sendmn.com; Assert portal_notification has 2 rows (one per hub_user) with type=LOW_BALANCE, is_read=FALSE
**Deliverable:** End-to-end integration test for low-balance notification flow using Testcontainers
**Acceptance / logic checks:**
- email_notification_log has exactly 1 SENT row for partner SENDMN after the deduction
- portal_notification has 2 rows (one per hub_user) with is_read=FALSE
- Second deduction within 1 hour does not add a second email_notification_log SENT row (suppression)
- Second deduction within 1 hour adds 2 more portal_notification rows (in-portal still fires)
- Deduction that keeps balance at 11000 (above threshold) results in 0 email_notification_log rows
**Depends on:** 11.7-T11, 11.7-T15

### 11.7-T25 — Integration test: full settlement-complete notification flow end-to-end  _(55 min)_
**Context:** Verify the full path from settlement_batch RECONCILED transition -> SettlementCompleteNotificationService -> email_notification_log + portal_notification. Use Testcontainers. Scenario: settlement batch ZP0062-20260604, 2 OVERSEAS partners (SendMN, TBank) with transactions, 1 LOCAL partner (GMERemit). Assert only OVERSEAS partners notified.
**Steps:** Seed: 3 partners (SENDMN OVERSEAS, TBANK OVERSEAS, GME_REMIT LOCAL), 1 hub_user each, alert configs for OVERSEAS partners; Seed settlement_batch and reconciliation_item rows linking transactions to all 3 partners; Trigger settlement RECONCILED transition via settlement service; Assert handleSettlementCompleted fires with affectedPartnerIds=[SENDMN, TBANK] (not GME_REMIT); Assert email_notification_log has 2 SENT rows (one per OVERSEAS partner) with type=SETTLEMENT_CONFIRMED; Assert portal_notification has 2 rows (one per hub_user of OVERSEAS partners) with type=SETTLEMENT_CONFIRMED
**Deliverable:** End-to-end integration test for settlement notification using Testcontainers
**Acceptance / logic checks:**
- email_notification_log has 2 rows, none for GME_REMIT (LOCAL partner)
- portal_notification has 2 rows, none for GME_REMIT hub_user
- Re-running the same settlement transition does not create duplicate email_notification_log rows (idempotency)
- Each email_notification_log row payload contains settlementId and settlementDate
- portal_notification rows have severity=INFO and type=SETTLEMENT_CONFIRMED
**Depends on:** 11.7-T12, 11.7-T16

### 11.7-T26 — Integration test: mark-read and unread-count portal notification API  _(50 min)_
**Context:** Verify GET /internal/v1/notifications/count, GET /internal/v1/notifications, POST /internal/v1/notifications/{id}/read, POST /internal/v1/notifications/read-all using Spring Boot test slice with Testcontainers. Use mock JWT tokens for two users (user1, user2) sharing partner SENDMN.
**Steps:** Seed 3 portal_notification rows: 2 unread for user1 (LOW_BALANCE, SETTLEMENT_CONFIRMED), 1 unread for user2 (LOW_BALANCE); GET /count as user1 -> assert {unreadCount:2}; GET /notifications as user1 -> assert 2 items returned, newest-first; POST /notifications/{idBelongingToUser2}/read as user1 -> assert 204 returned; GET /count as user2 still returns {unreadCount:1}; POST /read-all as user1 -> assert 204; GET /count as user1 returns {unreadCount:0}; GET /notifications as unauthenticated -> assert 401
**Deliverable:** Integration test suite for notification portal API (count, list, read, read-all)
**Acceptance / logic checks:**
- GET /count as user1 with 2 unread returns {unreadCount:2}
- GET /notifications returns items sorted newest-first
- POST /read on user2 notification as user1 returns 204 but user2 unread count stays 1
- POST /read-all for user1 sets both user1 notifications to is_read=TRUE without touching user2
- GET /notifications as unauthenticated returns HTTP 401
**Depends on:** 11.7-T13, 11.7-T14

### 11.7-T27 — Acceptance test: AC-04 low-balance dashboard indicator and AC-12 email  _(50 min)_
**Context:** PRD-08 acceptance criteria AC-04: LOW balance alert badge displayed on Dashboard when balance < configured threshold. AC-12: Low-balance email notification sent to all configured alert addresses when balance drops below threshold. Write acceptance tests covering both ACs in a single test class. Use Testcontainers (real DB) and mock SMTP.
**Steps:** AC-04 test: seed SENDMN with threshold=10000, balance=9800; GET /internal/v1/balance returns is_below_threshold=true; simulate dashboard API call and assert red badge flag present in response; AC-04 negative test: balance=10000 (equal to threshold) -> is_below_threshold=false; AC-12 test: configure alert_recipients=['a@test.com','b@test.com'] for SENDMN; trigger deduction that drops balance below 10000; verify MockJavaMailSender captured 1 message sent to To: a@test.com and b@test.com; AC-12 suppression test: second deduction within 1 hour does not send a second email; AC-12 multi-recipient test: both email addresses appear in the To or CC field of the sent message
**Deliverable:** AcceptanceTestNotifications test class validating AC-04 and AC-12
**Acceptance / logic checks:**
- AC-04: is_below_threshold=true when balance=9800 < threshold=10000
- AC-04: is_below_threshold=false when balance=10000 (equal)
- AC-12: exactly 1 email captured by MockJavaMailSender with recipients a@test.com and b@test.com
- AC-12: second trigger within 1 hour = 0 additional emails sent
- AC-12: email subject line contains the partner name and current balance amount
**Depends on:** 11.7-T18, 11.7-T24


## WBS 12.3 — Portal screens: wireframe→hi-fi
### 12.3-T01 — Define design-system token file for Partner Portal (colours, typography, spacing)  _(30 min)_
**Context:** GMEPay+ Partner Portal is a React SPA deployed at portal.gmepay.com (separate from admin.gmepay.com). UX-11 §2 defines canonical tokens: --color-brand #1A56DB, --color-danger #E02424, --color-warning #D97706, --color-success #057A55, --color-neutral-900 #111928, --color-neutral-600 #4B5563, --color-neutral-200 #E5E7EB, --color-neutral-50 #F9FAFB, --color-surface #FFFFFF. Typography: page-title 24px/600, section-heading 18px/600, body 14px/400, caption 12px/400, monospace 13px/400 (JetBrains Mono). Spacing base 8px; tokens: 4,8,12,16,24,32,48,64px.
**Steps:** Create src/design-system/tokens.css (CSS custom properties) containing all colour tokens from UX-11 §2.2.; Add typography scale variables matching UX-11 §2.1 (font-size, font-weight for each role).; Add spacing-scale variables for 4,8,12,16,24,32,48,64 px.; Add font-stack variables: Inter/system-ui for body, JetBrains Mono/Fira Code for monospace.; Export a tokens.ts file that mirrors the CSS variables as typed JS constants for use in component props.
**Deliverable:** src/design-system/tokens.css and src/design-system/tokens.ts
**Acceptance / logic checks:**
- All 9 colour tokens (#1A56DB through #FFFFFF) are present and match the hex values in UX-11 §2.2.
- Typography scale has entries for all 5 roles (page-title, section-heading, subsection, body, caption, monospace) with correct px sizes and weights.
- Spacing variables cover exactly 8 steps (4px through 64px).
- tokens.ts exports typed constants whose values reference the CSS custom properties (not hard-coded hex).
- Importing tokens.css in a blank HTML page and inspecting computed var(--color-brand) resolves to #1A56DB.

### 12.3-T02 — Build StatusBadge component for transaction and settlement statuses  _(35 min)_
**Context:** UX-11 §2.7 specifies two badge groups. Transaction: APPROVED bg #DEF7EC text #057A55; DECLINED bg #FDE8E8 text #E02424; PENDING bg #FEF3C7 text #D97706; CANCELLED bg #E5E7EB text #4B5563; REFUNDED bg #E0F2FE text #0369A1; UNCERTAIN bg #FEF9C3 text #92400E. Settlement: DRAFT bg #E5E7EB text #4B5563; CONFIRMED bg #DEF7EC text #057A55; DISPUTED bg #FDE8E8 text #E02424. Badge style: 12px bold text, 4px border-radius, 6px horizontal padding. Accessibility requires aria-label with full status text (not colour only).
**Steps:** Create src/components/StatusBadge/StatusBadge.tsx accepting props: status (string union of all 9 values) and optional className.; Map each status to its bg/text colour pair from the spec.; Apply 12px bold, 4px border-radius, 6px horizontal padding styling.; Set aria-label={status} on the span element so screen readers announce the full text.; Export a Storybook story or equivalent visual test showing all 9 variants side by side.
**Deliverable:** src/components/StatusBadge/StatusBadge.tsx with all 9 variants
**Acceptance / logic checks:**
- Rendering <StatusBadge status='APPROVED' /> produces a span with background #DEF7EC and text colour #057A55.
- Rendering <StatusBadge status='DECLINED' /> produces background #FDE8E8 text #E02424.
- All 9 variants render without console errors.
- aria-label attribute equals the status string (e.g. 'DECLINED') enabling screen-reader readout.
- Font-size computed as 12px and font-weight as bold (700) in every variant.
**Depends on:** 12.3-T01

### 12.3-T03 — Build MoneyDisplay component formatting currency amounts per UX-11 rules  _(30 min)_
**Context:** UX-11 §2.8 mandates: currency code precedes amount (e.g. 'USD 10,234.56'); comma thousands separator, period decimal; KRW = 0 decimal places, all others 2dp; rates to 6 significant figures; negative amounts use Unicode minus U+2212 in --color-danger; zero = 'USD 0.00' not blank. PRD-08 §10.2 adds: money always shows currency code. Component must handle KRW (integer), USD (2dp), MNT (2dp).
**Steps:** Create src/components/MoneyDisplay/MoneyDisplay.tsx accepting amount (number), currency (string), and optional isNegative (boolean).; Implement formatting: KRW rounds to 0dp, others 2dp; comma thousands separator.; Prefix with currency code followed by a space; prepend Unicode minus (U+2212) and apply --color-danger when isNegative=true.; Handle zero: render 'USD 0.00', 'KRW 0', never blank.; Export a separate formatRate(value: number): string utility for 6-significant-figure rates used in transaction detail.
**Deliverable:** src/components/MoneyDisplay/MoneyDisplay.tsx and src/utils/formatRate.ts
**Acceptance / logic checks:**
- formatMoney(10234.56, 'USD') renders 'USD 10,234.56'.
- formatMoney(45000, 'KRW') renders 'KRW 45,000' (no decimal).
- formatMoney(0, 'USD') renders 'USD 0.00'.
- isNegative=true on USD 33.83 renders '−25 33.83' in --color-danger colour (not a plain hyphen).
- formatRate(1350.42) renders '1,350.42' (6 significant figures).
**Depends on:** 12.3-T01

### 12.3-T04 — Build DataTable base component matching UX-11 table specification  _(45 min)_
**Context:** UX-11 §2.6 specifies: header row 12px uppercase neutral-600 neutral-50 bg 1px bottom border; body rows 14px neutral-900 44px height (compact 36px) alternating neutral-50/white; right-align numeric/currency cols, left-align text, centre-align badges; sticky header on scroll; hover neutral-50; sortable columns with sort icons; max 6 columns. Must support column definitions with alignment hints and optional sort.
**Steps:** Create src/components/DataTable/DataTable.tsx accepting columns (ColumnDef[]) and data (row objects), plus optional compact boolean.; Implement sticky thead with 12px uppercase neutral-600 neutral-50 background.; Alternate tbody row backgrounds neutral-50/white; hover state neutral-50.; Row height: 44px default, 36px when compact=true.; Apply right-align class when column.align='right', center when 'center', default left.; Add sortable prop on ColumnDef; clicking a sortable header emits onSort(columnKey, direction).
**Deliverable:** src/components/DataTable/DataTable.tsx with ColumnDef type
**Acceptance / logic checks:**
- Rendering a 3-column table shows sticky header that stays visible when scrolling 200px of body rows.
- Alternating rows render neutral-50 (#F9FAFB) and white (#FFFFFF) backgrounds.
- A column with align='right' has text-align: right in every data cell.
- Clicking a sortable column header fires onSort with the column key and toggles asc/desc direction.
- compact=true reduces row height from 44px to 36px.
**Depends on:** 12.3-T01

### 12.3-T05 — Build EmptyState and ErrorState placeholder components  _(35 min)_
**Context:** UX-11 §2.9 specifies empty state: centred icon + 'No transactions found' + 'Try adjusting your filters' text. Error state: warning-triangle icon + 'Could not load [entity]' + error code (human-readable, no stack trace) + [Retry] button that re-triggers the failed fetch. Loading state: skeleton shimmer matching table row structure, minimum 200ms display to avoid flash.
**Steps:** Create src/components/EmptyState/EmptyState.tsx accepting optional message and subtext props.; Create src/components/ErrorState/ErrorState.tsx accepting errorCode (string), entityLabel (string), and onRetry callback.; Create src/components/SkeletonTable/SkeletonTable.tsx accepting rowCount and columnCount, rendering animated grey shimmer bars.; SkeletonTable must display for at least 200ms even if data arrives sooner (use a minimum-display timer).; Error code displayed as human-readable text; no technical stack trace or raw exception message.
**Deliverable:** EmptyState, ErrorState, and SkeletonTable components
**Acceptance / logic checks:**
- EmptyState renders the two message lines without any data rows.
- ErrorState renders the error code string and a Retry button; clicking Retry calls onRetry once.
- SkeletonTable renders rowCount x columnCount grey bars with CSS shimmer animation.
- SkeletonTable does not disappear until at least 200ms have elapsed even if data prop is set immediately.
- ErrorState does not render a stack trace or exception object; only the errorCode string.
**Depends on:** 12.3-T01

### 12.3-T06 — Build Partner Portal Shell layout (top bar, left nav, content area)  _(50 min)_
**Context:** UX-11 §3.2 defines the Partner Portal shell: top bar shows 'GMEPay+ Partner Portal' + partner name + notification bell with unread-count badge + Logout; left nav 200px with 5 items (Dashboard, Transactions, Balance, Statements, API and Creds); content area max-width 1,200px 32px padding. Desktop min 1,280px; tablet landscape (1,024px+) collapses sidebar to icon-only overlay triggered by hamburger. Active nav item has brand-colour left border.
**Steps:** Create src/layouts/PortalShell/PortalShell.tsx accepting partnerName, notificationCount, onLogout, and children.; Implement top bar (56px height) with partner name, bell icon showing notificationCount badge (shown only when count > 0), and Logout button.; Implement left nav (200px) with five items; active item indicated by 2px brand-colour left border and brand bg tint.; At viewport < 1,024px collapse nav to hidden overlay; add hamburger toggle.; Content area: max-width 1,200px, 32px padding all sides.
**Deliverable:** src/layouts/PortalShell/PortalShell.tsx with responsive behaviour
**Acceptance / logic checks:**
- Top bar renders partner name string and bell icon; badge shows '3' when notificationCount=3 and is hidden when notificationCount=0.
- Active nav item for 'Transactions' has left border colour matching --color-brand (#1A56DB).
- At viewport width 1,023px the left nav is hidden; a hamburger button is visible.
- Logout button calls onLogout prop when clicked.
- Content area has max-width 1,200px and 32px padding on all sides.
**Depends on:** 12.3-T01, 12.3-T02

### 12.3-T07 — Build BalanceSummaryPanel component for Dashboard (OVERSEAS partners only)  _(35 min)_
**Context:** PRD-08 §3.1: Balance panel shown only when partner type = OVERSEAS. Fields: current prefunding balance (USD real-time); low-balance indicator -- red badge when balance < low_balance_threshold (default USD 10,000); last top-up date; reserved/pending amount (informational). UX-11 §5.1 wireframe: 'USD 45,200.00 OK' or warning state. --color-warning #D97706 used for LOW badge. Balance must display within 1 second of page load (PRD-08 G-02).
**Steps:** Create src/components/BalanceSummaryPanel/BalanceSummaryPanel.tsx accepting balance (number), threshold (number), lastTopUpDate (string ISO), reserved (number), and isOverseas (boolean).; When isOverseas=false return null (panel hidden for LOCAL partners such as GME Remit).; Render balance as MoneyDisplay with currency='USD'.; Show green 'OK' indicator when balance >= threshold; show amber/red 'LOW' badge (--color-warning) when balance < threshold.; Render last top-up date formatted YYYY-MM-DD; render reserved amount with label 'Reserved (pending)'.
**Deliverable:** src/components/BalanceSummaryPanel/BalanceSummaryPanel.tsx
**Acceptance / logic checks:**
- isOverseas=false renders null -- nothing is mounted in the DOM.
- balance=8400, threshold=10000 renders a LOW badge using --color-warning (#D97706).
- balance=45200, threshold=10000 renders an OK indicator in --color-success (#057A55).
- MoneyDisplay for balance='0' renders 'USD 0.00' not blank.
- lastTopUpDate='2026-05-31' renders as '2026-05-31' (ISO format, not locale-specific).
**Depends on:** 12.3-T03, 12.3-T05

### 12.3-T08 — Build TodayActivitySummary panel for Dashboard  _(30 min)_
**Context:** PRD-08 §3.2: Today's activity panel shows transaction count (count of approved payments current calendar day KST), payout volume (sum of target_payout in KRW for ZeroPay), collection volume (sum of collection_amount in partner settlement ccy), and success rate (Approved / (Approved + Declined) x 100% today). UX-11 §5.1 wireframe shows Transactions: 234, Payout volume: KRW 12.3M, Collection: USD 9,107.22, Success rate: 99.1%.
**Steps:** Create src/components/TodayActivitySummary/TodayActivitySummary.tsx accepting txnCount, payoutVolume, payoutCcy, collectionVolume, collectionCcy, successRate (number 0-100).; Render each metric in its own labelled cell.; Format payoutVolume and collectionVolume using MoneyDisplay.; Format successRate to 1 decimal place followed by '%' (e.g. 99.1%).; If successRate cannot be computed (txnCount=0), display '--' not NaN or divide-by-zero error.
**Deliverable:** src/components/TodayActivitySummary/TodayActivitySummary.tsx
**Acceptance / logic checks:**
- txnCount=234, payoutVolume=12300000, payoutCcy='KRW' renders 'KRW 12,300,000'.
- successRate=99.1 renders '99.1%'.
- successRate from 0 approved and 0 declined renders '--' not NaN%.
- collectionVolume=9107.22, collectionCcy='USD' renders 'USD 9,107.22'.
- Component renders 4 labelled sections matching PRD-08 §3.2 field names.
**Depends on:** 12.3-T03

### 12.3-T09 — Build RecentTransactionsTable for Dashboard (10-row summary)  _(40 min)_
**Context:** PRD-08 §3.3: Dashboard shows the 10 most recent transactions for the authenticated partner. Columns: txn_timestamp (partner timezone, default KST), hub_txn_ref truncated to first 12 chars (links to detail), merchant_name, target_payout + payout_ccy, collection_amount + collection_ccy, status badge. UX-11 §5.1 wireframe shows [View all ->] link. Clicking a row navigates to /{partner_slug}/transactions/{hub_txn_ref}.
**Steps:** Create src/components/RecentTransactionsTable/RecentTransactionsTable.tsx accepting transactions (array of up to 10 rows) and partnerSlug.; Render using DataTable with columns: Time, Txn ID (truncated to 12 chars, clickable link), Merchant, Payout, Status badge.; Truncate hub_txn_ref to first 12 characters for display; link target is /{partnerSlug}/transactions/{full hub_txn_ref}.; Display txn_timestamp in KST (UTC+9); format as HH:mm:ss.; Show StatusBadge for txn_status. Add [View all] link above or below the table.
**Deliverable:** src/components/RecentTransactionsTable/RecentTransactionsTable.tsx
**Acceptance / logic checks:**
- hub_txn_ref 'HUB-20260601-00123' is displayed truncated as 'HUB-20260601-' (first 12 chars) and links to /partner-slug/transactions/HUB-20260601-00123.
- txn_timestamp in UTC renders converted to KST (UTC+9) in HH:mm:ss format.
- Status APPROVED renders StatusBadge with bg #DEF7EC.
- Table shows at most 10 rows even if transactions array has more than 10 entries.
- [View all] link is present and navigates to the Transactions page.
**Depends on:** 12.3-T02, 12.3-T03, 12.3-T04

### 12.3-T10 — Build AlertsPanel component for Dashboard (low-balance and settlement-due alerts)  _(35 min)_
**Context:** PRD-08 §3.4: Low-balance alert when current_balance < low_balance_threshold -- shows balance, threshold, top-up CTA, dismissible per session. Settlement-due alert when latest statement is overdue (default T+2 business days after period close). UX-11 --color-warning used for warning-level items. Notifications stored per-partner-user; PRD-08 §9.1 lists severities: Low balance = High, Settlement statement ready = Info.
**Steps:** Create src/components/AlertsPanel/AlertsPanel.tsx accepting alerts array with shape {type: 'LOW_BALANCE'|'SETTLEMENT_DUE', balance?: number, threshold?: number, periodLabel?: string}.; For LOW_BALANCE: render current balance (MoneyDisplay USD), threshold (MoneyDisplay USD), and a 'Top up now' link opening the top-up instructions section.; Add a Dismiss button per alert; dismissed alerts are removed from rendered output and stored in sessionStorage keyed by alert type.; For SETTLEMENT_DUE: render the period label and a link to the Statements page.; When alerts array is empty render nothing (null).
**Deliverable:** src/components/AlertsPanel/AlertsPanel.tsx
**Acceptance / logic checks:**
- LOW_BALANCE alert with balance=8400, threshold=10000 renders 'USD 8,400.00' and 'USD 10,000.00' in the panel.
- Clicking Dismiss removes the alert from the DOM and writes to sessionStorage so it does not reappear on the same page refresh.
- SETTLEMENT_DUE alert renders the periodLabel string and a link.
- Empty alerts array renders no DOM nodes.
- LOW_BALANCE alert uses --color-warning or --color-danger styling (not success green).
**Depends on:** 12.3-T03, 12.3-T05

### 12.3-T11 — Build Dashboard page composing all four panels (BalanceSummary, TodayActivity, RecentTransactions, Alerts)  _(45 min)_
**Context:** PRD-08 §3 and UX-11 §5.1: Dashboard is the landing page after login. Left column: BalanceSummaryPanel (OVERSEAS only) + TodayActivitySummary. Below: AlertsPanel. Full width: RecentTransactionsTable. Data fetched from backend API endpoints GET /portal/v1/dashboard (single aggregate call). Page must load within P95 2 seconds (PRD-08 §10.1). Render skeleton shimmer while loading.
**Steps:** Create src/pages/Dashboard/DashboardPage.tsx. On mount fetch GET /portal/v1/dashboard.; While fetching render SkeletonTable (4 rows, 5 columns) in the transactions area and grey shimmer blocks for metric panels.; On success render BalanceSummaryPanel, TodayActivitySummary, AlertsPanel, RecentTransactionsTable with data from response.; On error render ErrorState with errorCode from response and onRetry re-triggering the fetch.; BalanceSummaryPanel receives isOverseas from the authenticated partner's type claim in the JWT.
**Deliverable:** src/pages/Dashboard/DashboardPage.tsx
**Acceptance / logic checks:**
- Page mounts and calls GET /portal/v1/dashboard exactly once on load.
- While fetch is in flight SkeletonTable is visible; after data arrives SkeletonTable is replaced by RecentTransactionsTable.
- For a LOCAL partner (type=LOCAL) BalanceSummaryPanel renders null and is not present in the DOM.
- For an OVERSEAS partner with balance < threshold the AlertsPanel renders a LOW_BALANCE alert.
- On API error ErrorState is shown with a Retry button.
**Depends on:** 12.3-T06, 12.3-T07, 12.3-T08, 12.3-T09, 12.3-T10

### 12.3-T12 — Build TransactionFilterBar component for Transaction History page  _(40 min)_
**Context:** PRD-08 §4.1 and UX-11 §5.2: Filter controls -- date range (from/to date picker, default last 7 days, max 90 days per query); QR scheme dropdown (schemes the partner is mapped to); direction dropdown (Domestic/Inbound/Outbound/Hub); status multi-select (Approved/Declined/Pending/Cancelled/Refunded); amount range (min/max numeric in settlement ccy); payment mode dropdown (MPM/CPM/All). UX-11 §7.2: date range validation -- from <= to, range <= 90 days, error 'Date range cannot exceed 90 days'.
**Steps:** Create src/components/TransactionFilterBar/TransactionFilterBar.tsx accepting availableSchemes[], onSearch(filters), onExport().; Render date-range picker initialised to today-7d / today.; Add scheme dropdown, direction dropdown, status multi-select, amount min/max inputs, mode dropdown.; On Search click validate: dateFrom <= dateTo, difference <= 90 days -- show inline error 'Date range cannot exceed 90 days' if violated; block submission.; Export button calls onExport(currentFilters) regardless of validation (export uses same filter state).
**Deliverable:** src/components/TransactionFilterBar/TransactionFilterBar.tsx
**Acceptance / logic checks:**
- Default date range on mount is today minus 7 days to today.
- Selecting a 91-day range and clicking Search shows inline error 'Date range cannot exceed 90 days' and does not call onSearch.
- A 90-day range (exactly) passes validation and calls onSearch.
- Status multi-select allows selecting Approved + Declined simultaneously (two values in the filter).
- onExport is called with the current filter state including all selected values.
**Depends on:** 12.3-T01

### 12.3-T13 — Build TransactionListTable component for Transaction History page  _(45 min)_
**Context:** PRD-08 §4.2 and UX-11 §5.2: Columns -- Date/Time (txn_timestamp KST), Transaction ID (hub_txn_ref truncated, click opens detail), Scheme (scheme_name), Direction, Payout amount (target_payout + payout_ccy), Collection amount (collection_amount + collection_ccy), Status badge (txn_status), Mode (payment_mode MPM/CPM). Pagination: default 50 rows; options 25/50/100; prev/next/jump-to-page; total count displayed. UX-11 §2.6: max 6 columns -- show 6 primary columns; remaining accessible via expandable row.
**Steps:** Create src/components/TransactionListTable/TransactionListTable.tsx accepting rows[], totalCount, page, pageSize, onPageChange, onPageSizeChange, onRowClick.; Render DataTable with 6 primary visible columns (Time, Txn ID, Payout, Collection, Status, Mode); Scheme and Direction accessible in expandable row detail.; Truncate hub_txn_ref to first 12 chars for display; row click calls onRowClick(hub_txn_ref).; Render pagination footer showing 'Showing {start}-{end} of {totalCount}' with page-size selector (25/50/100) and prev/next/jump controls.; Format txn_timestamp as KST (UTC+9) in format YYYY-MM-DD HH:mm:ss.
**Deliverable:** src/components/TransactionListTable/TransactionListTable.tsx
**Acceptance / logic checks:**
- Table renders 6 visible columns in the header row.
- hub_txn_ref 'HUB-20260601-00123' displays as 'HUB-20260601-' in the ID column (12 chars).
- Pagination footer shows 'Showing 1-50 of 234' for page=1, pageSize=50, totalCount=234.
- Clicking Next page calls onPageChange(2).
- Page-size selector shows options 25, 50, 100.
**Depends on:** 12.3-T02, 12.3-T03, 12.3-T04

### 12.3-T14 — Build Transaction History page composing filter bar, table, and CSV export  _(45 min)_
**Context:** PRD-08 §4 and UX-11 §5.2: Transaction History page at /{partnerSlug}/transactions. Fetches GET /portal/v1/transactions with filter params. CSV export: GET /portal/v1/transactions/export -- max 10,000 rows, filename gmepay_txns_{partner_id}_{from_date}_{to_date}.csv. Export columns include offer_rate, scheme_txn_ref, merchant_id, merchant_name, full hub_txn_ref. Internal fields (m_a, m_b, cost_rate_coll, cost_rate_pay, collection_margin_usd, payout_margin_usd) must never appear in export.
**Steps:** Create src/pages/Transactions/TransactionHistoryPage.tsx.; On TransactionFilterBar onSearch call GET /portal/v1/transactions with filter params; show SkeletonTable while loading.; On success render TransactionListTable with results; on error render ErrorState.; On Export button call GET /portal/v1/transactions/export and trigger browser download with filename gmepay_txns_{partner_id}_{fromDate}_{toDate}.csv.; Clicking a row navigates to /{partnerSlug}/transactions/{hub_txn_ref}.
**Deliverable:** src/pages/Transactions/TransactionHistoryPage.tsx
**Acceptance / logic checks:**
- Changing filter and clicking Search calls GET /portal/v1/transactions with updated query params.
- Export triggers a file download; filename matches pattern gmepay_txns_{partner_id}_{from_date}_{to_date}.csv.
- While transaction fetch is in flight SkeletonTable is shown; DataTable shown after response.
- Clicking a table row navigates to the correct detail URL /{partnerSlug}/transactions/{full hub_txn_ref}.
- API error on the transactions fetch renders ErrorState with Retry button.
**Depends on:** 12.3-T12, 12.3-T13, 12.3-T05

### 12.3-T15 — Build AmountsPanel component for Transaction Detail showing partner-visible fields only  _(35 min)_
**Context:** PRD-08 §5.1 and §5.2: Partner-visible amount fields -- target_payout (+ payout_ccy), collection_amount (+ collection_ccy), service_charge (Settle A ccy), send_amount, offer_rate (6 sig figs), cross_rate (target_payout / send_amount, 6 sig figs). For cross-border only: collection_usd, payout_usd_cost, rate_locked_at (ISO-8601 UTC). For Domestic (same-currency) transactions: USD pool fields NOT displayed. Fields that must NEVER appear: m_a, m_b, cost_rate_coll, cost_rate_pay, collection_margin_usd, payout_margin_usd.
**Steps:** Create src/components/AmountsPanel/AmountsPanel.tsx accepting transaction object with all fields; isCrossBorder boolean derived from direction !== 'Domestic'.; Render partner-visible fields using MoneyDisplay and formatRate.; Conditionally render the USD pool section (collection_usd, payout_usd_cost, rate_locked_at) only when isCrossBorder=true.; Never render m_a, m_b, cost_rate_coll, cost_rate_pay, collection_margin_usd, payout_margin_usd -- these props should not even exist in the component interface.; rate_locked_at displayed as ISO-8601 UTC string (not converted to local time).
**Deliverable:** src/components/AmountsPanel/AmountsPanel.tsx
**Acceptance / logic checks:**
- Domestic transaction (isCrossBorder=false) renders no collection_usd, payout_usd_cost, or rate_locked_at elements.
- Cross-border transaction renders collection_usd formatted as MoneyDisplay USD and rate_locked_at as ISO string.
- The component TypeScript interface does not include m_a, m_b, cost_rate_coll, or cost_rate_pay as props.
- offer_rate=1350.42 renders as '1,350.42' (6 significant figures via formatRate).
- service_charge=0.50 in USD renders as 'USD 0.50'.
**Depends on:** 12.3-T03

### 12.3-T16 — Build EventTimeline component showing simplified 4-step partner view  _(35 min)_
**Context:** PRD-08 §5.3 and UX-11 §5.3: Partners see a 4-step simplified event trail: Initiated (step 2 timestamp), Scheme Processing (step 4 timestamp), Approved/Declined (step 5/6 timestamp), Partner Notified (step 8 timestamp). The full 8-step trail is Admin System only. For OVERSEAS partners step 3 (Prefund deducted) is available internally but not shown in this component. Each visible step shows its timestamp in the partner's timezone (default KST UTC+9).
**Steps:** Create src/components/EventTimeline/EventTimeline.tsx accepting eventSteps array where each step has label and timestamp (ISO UTC string or null).; Map 8-step internal trail to 4-step partner view: step 2 -> Initiated, step 4 -> Scheme Processing, step 5 or 6 -> Approved/Declined, step 8 -> Partner Notified.; Render each step as a timeline item with a circle indicator: filled circle for completed steps, empty for pending.; Display timestamp in KST (UTC+9) as HH:mm:ss; null timestamp shows '--'.; Apply --color-success for APPROVED terminal step, --color-danger for DECLINED.
**Deliverable:** src/components/EventTimeline/EventTimeline.tsx
**Acceptance / logic checks:**
- Rendering with step 2 timestamp '2026-06-01T00:14:31Z' shows 'Initiated 09:14:31' (UTC+9).
- A DECLINED final state step renders in --color-danger (#E02424).
- Steps with null timestamp render '--' not an empty string or error.
- The component renders exactly 4 steps, not 8.
- Completed steps (non-null timestamp) show a filled circle; pending steps show an empty circle.
**Depends on:** 12.3-T01

### 12.3-T17 — Build SchemeReferencePanel component for Transaction Detail  _(25 min)_
**Context:** PRD-08 §5.4: Scheme reference section shows scheme_txn_ref (ZeroPay/scheme-side ID), scheme name (e.g. ZeroPay), merchant_id, merchant_name, payment_mode (MPM/CPM), and direction (Domestic/Inbound/Outbound/Hub). UX-11 §5.3 wireframe shows scheme ref, mode, direction in a right panel alongside the event timeline.
**Steps:** Create src/components/SchemeReferencePanel/SchemeReferencePanel.tsx accepting schemeTxnRef, schemeName, merchantId, merchantName, paymentMode, direction.; Render each field as a labelled key-value row.; scheme_txn_ref and merchantId use monospace font (13px JetBrains Mono per design system).; payment_mode displays 'MPM' or 'CPM'.; direction displays human-readable label matching UX-11 values: Domestic / Inbound / Outbound / Hub.
**Deliverable:** src/components/SchemeReferencePanel/SchemeReferencePanel.tsx
**Acceptance / logic checks:**
- schemeTxnRef='ZP-9988-77' renders in monospace font.
- paymentMode='MPM' renders the text 'MPM'.
- merchantId='M-001234' renders in monospace font.
- All 6 fields (schemeTxnRef, schemeName, merchantId, merchantName, paymentMode, direction) are present in the rendered output.
- Component renders no amount or rate fields -- those are the responsibility of AmountsPanel.
**Depends on:** 12.3-T01

### 12.3-T18 — Build Transaction Detail page composing amounts, event timeline, and scheme panels  _(45 min)_
**Context:** PRD-08 §5 and UX-11 §5.3: Transaction Detail at /{partnerSlug}/transactions/{hub_txn_ref}. URL is partner-scoped: accessing another partner's txn_ref returns HTTP 404 (not 403). Full hub_txn_ref in the page title. Back button returns to Transaction History. Page layout: two-column -- left AmountsPanel, right EventTimeline + SchemeReferencePanel. Data from GET /portal/v1/transactions/{hub_txn_ref}.
**Steps:** Create src/pages/Transactions/TransactionDetailPage.tsx extracting hub_txn_ref from URL param.; Fetch GET /portal/v1/transactions/{hub_txn_ref} on mount; show SkeletonTable while loading.; On 404 response render a 'Transaction not found' EmptyState (not 'Access denied' -- per PRD-08 security requirement).; On success render two-column layout: AmountsPanel (left), EventTimeline + SchemeReferencePanel (right).; Add [Back] button that navigates to /{partnerSlug}/transactions.
**Deliverable:** src/pages/Transactions/TransactionDetailPage.tsx
**Acceptance / logic checks:**
- Page title shows full hub_txn_ref (not truncated).
- API 404 response renders EmptyState with 'Transaction not found' message (does not reveal whether transaction exists for another partner).
- AmountsPanel receives isCrossBorder=false for direction='Domestic' transactions.
- Back button navigates to the transaction history page.
- EventTimeline renders 4 steps derived from the transaction's event trail.
**Depends on:** 12.3-T05, 12.3-T15, 12.3-T16, 12.3-T17

### 12.3-T19 — Build BalanceLedgerTable component for Prefunding and Balance page  _(40 min)_
**Context:** PRD-08 §6.2 and UX-11 §5.4: Balance ledger columns -- Date/Time (KST), Type (Debit/Credit/Adjustment), Reference (hub_txn_ref for debits; top-up reference for credits), Amount USD (negative for debits, positive for credits), Running balance USD. Default: last 30 days; filterable by date range max 1 year. Debit amounts: Unicode minus U+2212 in --color-danger. Running balance must equal sum of all prior entries.
**Steps:** Create src/components/BalanceLedgerTable/BalanceLedgerTable.tsx accepting entries array and totals.; Render DataTable with columns: Date/Time, Type, Reference, Amount, Running Balance.; Debit Amount formatted with MoneyDisplay isNegative=true (Unicode minus, danger colour); Credit positive.; Reference field uses monospace font.; Add date-range filter inputs (default last 30 days, max 365 days) above the table; changing range calls onDateRangeChange(from, to).
**Deliverable:** src/components/BalanceLedgerTable/BalanceLedgerTable.tsx
**Acceptance / logic checks:**
- Debit row Amount renders with Unicode minus U+2212 and --color-danger text colour.
- Credit row Amount renders as positive with no minus sign.
- Running balance column shows a USD value in every row.
- Reference column renders in monospace font (13px JetBrains Mono).
- Date range exceeding 365 days shows an inline validation error and blocks the query.
**Depends on:** 12.3-T03, 12.3-T04

### 12.3-T20 — Build TopUpInstructionsPanel component (static informational content)  _(20 min)_
**Context:** PRD-08 §6.3: Top-up is a manual wire transfer process in Phase 1. Panel shows static content: GME bank account details for USD wire, reference format (partner_id + YYYYMMDD, e.g. SENDMN-20260601), expected processing time T+1 business day, GME account team contact. No payment initiation UI. This is informational only and never triggers any backend action.
**Steps:** Create src/components/TopUpInstructionsPanel/TopUpInstructionsPanel.tsx accepting partnerId (string) and contactEmail (string).; Render static beneficiary name, account details as placeholder fields (to be filled with actual GME bank details via environment config).; Render the reference format dynamically: {partnerId}-{YYYYMMDD} where YYYYMMDD is today's date.; Render 'T+1 business day' processing time note.; Render contactEmail as a mailto link.
**Deliverable:** src/components/TopUpInstructionsPanel/TopUpInstructionsPanel.tsx
**Acceptance / logic checks:**
- partnerId='SENDMN' renders reference example as 'SENDMN-{today in YYYYMMDD}'.
- contactEmail='ops@gme.com' renders as a mailto:ops@gme.com link.
- No form or submit button is present -- panel is purely informational.
- 'T+1 business day' text is present.
- Panel renders without any API calls -- it is static/display only.
**Depends on:** 12.3-T01

### 12.3-T21 — Build Prefunding and Balance page composing balance panel, ledger, and top-up instructions  _(40 min)_
**Context:** PRD-08 §6 and UX-11 §5.4: Balance page at /{partnerSlug}/balance. OVERSEAS partners only (LOCAL partners: display message 'Balance management not applicable for your account type'). Sections: CurrentBalance (real-time, < 1s per G-02), BalanceLedgerTable, TopUpInstructionsPanel. Data: GET /portal/v1/balance (real-time balance) and GET /portal/v1/balance/ledger?from=&to= (ledger movements).
**Steps:** Create src/pages/Balance/BalancePage.tsx. Check partnerType from JWT claims; if LOCAL render informational message and stop.; Fetch GET /portal/v1/balance in parallel with initial ledger fetch GET /portal/v1/balance/ledger (last 30 days).; Render BalanceSummaryPanel (isOverseas=true) using balance data.; Render BalanceLedgerTable below; on date range change re-fetch ledger with new params.; Render TopUpInstructionsPanel at the bottom of the page.
**Deliverable:** src/pages/Balance/BalancePage.tsx
**Acceptance / logic checks:**
- LOCAL partner renders 'Balance management not applicable for your account type' and no balance panels.
- OVERSEAS partner renders BalanceSummaryPanel with real-time balance.
- Date range change in the ledger triggers GET /portal/v1/balance/ledger with updated from/to query params.
- TopUpInstructionsPanel is present for OVERSEAS partners and shows the partner's reference format.
- Both balance and ledger fetches are issued in parallel (not sequentially).
**Depends on:** 12.3-T07, 12.3-T19, 12.3-T20

### 12.3-T22 — Build StatementListTable component for Statements and Reports page  _(35 min)_
**Context:** PRD-08 §7.1: Settlement statement list columns -- Period (e.g. May 2026), Settlement date, Total transactions, Total payout volume (target_payout ccy), Total collection (Settle A ccy), Total service fees (Settle A ccy), Status badge (DRAFT/CONFIRMED/DISPUTED), PDF download button, CSV download button. Data restriction: m_a, m_b, GME margin and VAN fee never shown. Status badge uses UX-11 §2.7 settlement badge definitions.
**Steps:** Create src/components/StatementListTable/StatementListTable.tsx accepting statements array with shape {period, settlementDate, txnCount, payoutVolume, payoutCcy, collectionTotal, collectionCcy, serviceFeeTotal, status, pdfUrl, csvUrl}.; Render DataTable with all 9 visible columns.; Status column uses StatusBadge component with DRAFT/CONFIRMED/DISPUTED values.; PDF and CSV columns render download anchor tags pointing to pdfUrl and csvUrl; clicking triggers download.; Payout, collection, and service fee columns use MoneyDisplay.
**Deliverable:** src/components/StatementListTable/StatementListTable.tsx
**Acceptance / logic checks:**
- Status CONFIRMED renders StatusBadge bg #DEF7EC text #057A55.
- Status DISPUTED renders StatusBadge bg #FDE8E8 text #E02424.
- PDF button is an anchor with href=pdfUrl and download attribute set.
- payoutVolume=12300000, payoutCcy='KRW' renders 'KRW 12,300,000'.
- The table does not render any column for m_a, m_b, VAN fee, or GME margin.
**Depends on:** 12.3-T02, 12.3-T03, 12.3-T04

### 12.3-T23 — Build FeeBreakdownTable component for settlement period line-level detail  _(30 min)_
**Context:** PRD-08 §7.2: Fee breakdown for a selected settlement period. Columns -- Date, Transaction ID (hub_txn_ref), Payout amount (+ ccy), Collection amount (+ ccy), Service charge (Settle A ccy), Offer rate (6 sig figs). Data restriction: merchant fee, VAN fee, GME margin percentages, GME revenue figures never shown to partners. Partners see only their own debit obligations.
**Steps:** Create src/components/FeeBreakdownTable/FeeBreakdownTable.tsx accepting rows array and periodLabel.; Render DataTable with 6 columns: Date, Txn ID, Payout Amount, Collection Amount, Service Charge, Offer Rate.; hub_txn_ref displayed in full (not truncated) in monospace font.; Offer rate formatted using formatRate (6 significant figures).; Ensure no column for VAN fee, merchant fee, m_a, m_b, or any GME revenue figure exists in the component interface or rendering.
**Deliverable:** src/components/FeeBreakdownTable/FeeBreakdownTable.tsx
**Acceptance / logic checks:**
- Table has exactly 6 columns matching the spec names.
- offer_rate=1350.42 renders '1,350.42'.
- hub_txn_ref is untruncated (full ref string) in monospace font.
- The component TypeScript props interface has no field named m_a, m_b, vanFee, merchantFee, or margin.
- service_charge=0.50 with Settle A ccy USD renders 'USD 0.50'.
**Depends on:** 12.3-T03, 12.3-T04

### 12.3-T24 — Build Statements and Reports page composing statement list and fee breakdown  _(40 min)_
**Context:** PRD-08 §7 and UX-11 §5.4 (shared Balance/Statement page context): Page at /{partnerSlug}/statements. Shows StatementListTable at top. Clicking a period row expands or navigates to show FeeBreakdownTable for that period. PDF/CSV download links per statement. Statements available from T+1 business day after period close (PRD-08 §7.3, AC-10).
**Steps:** Create src/pages/Statements/StatementsPage.tsx. Fetch GET /portal/v1/statements on mount; show SkeletonTable while loading.; Render StatementListTable with the results.; Clicking a row (or a [View] action) fetches GET /portal/v1/statements/{periodId}/breakdown and renders FeeBreakdownTable below the main list.; PDF button calls GET /portal/v1/statements/{periodId}/pdf and triggers browser download.; CSV button calls GET /portal/v1/statements/{periodId}/csv and triggers browser download.
**Deliverable:** src/pages/Statements/StatementsPage.tsx
**Acceptance / logic checks:**
- Mount triggers GET /portal/v1/statements; SkeletonTable shown during fetch.
- Clicking a CONFIRMED period row fetches the breakdown and renders FeeBreakdownTable.
- PDF download button triggers a file download with a .pdf file (Content-Type application/pdf expected).
- CSV download button triggers a file download with a .csv file.
- If no statements exist EmptyState renders 'No statements found'.
**Depends on:** 12.3-T05, 12.3-T22, 12.3-T23

### 12.3-T25 — Build APICredentialsPanel component for API and Credentials page  _(30 min)_
**Context:** PRD-08 §8.1: Displayed fields -- API Key ID (public identifier, shown), API Key Status (Active/Revoked), API Secret (NEVER displayed, not even masked), Webhook URL (HTTPS, shown), Webhook status (last delivery success/failure + timestamp), Sandbox API Key ID (shown), Sandbox base URL (shown), Rate quote TTL in seconds (rate_quote_ttl_seconds value, shown). PRD-08 §8.2: static sandbox panel links to API docs URL and technical contact email. Security: secret must not appear in any DOM element, even as type='password'.
**Steps:** Create src/components/APICredentialsPanel/APICredentialsPanel.tsx accepting apiKeyId, apiKeyStatus, webhookUrl, webhookStatus, webhookLastAttempt, sandboxKeyId, sandboxBaseUrl, rateQuoteTtl, apiDocsUrl, techContactEmail.; Render each field as a labelled row; API key ID and sandbox key ID in monospace.; Include an explicit text notice 'API Secret: not displayed for security. Contact GME Ops to rotate.' -- do not render any input or masked value.; webhookStatus renders as a StatusBadge-like indicator (success/failure); webhookLastAttempt as an ISO timestamp.; Sandbox panel renders apiDocsUrl as a link and techContactEmail as a mailto link.
**Deliverable:** src/components/APICredentialsPanel/APICredentialsPanel.tsx
**Acceptance / logic checks:**
- No DOM element with name or id containing 'secret' is rendered (inspect DOM structure).
- API Key ID renders in monospace font.
- webhookStatus='failure' renders a danger-coloured indicator.
- rateQuoteTtl=300 renders '300 seconds'.
- The static notice 'Contact GME Ops to rotate' is present in the rendered output.
**Depends on:** 12.3-T01

### 12.3-T26 — Build API and Credentials page composing the credentials panel  _(30 min)_
**Context:** PRD-08 §8: Page at /{partnerSlug}/api-credentials. Read-only view of API configuration. No credential regeneration in Phase 1. Data from GET /portal/v1/credentials. Webhook failure notification is raised after 3 consecutive failed deliveries (PRD-08 §9.1 AC-13).
**Steps:** Create src/pages/APICredentials/APICredentialsPage.tsx. Fetch GET /portal/v1/credentials on mount.; Show SkeletonTable while loading; on success render APICredentialsPanel.; If webhookStatus indicates 3+ consecutive failures render an inline warning banner: 'Webhook delivery failing. Check your endpoint and contact GME support.'; Add page title 'API and Credentials' with breadcrumb.; On fetch error render ErrorState with Retry.
**Deliverable:** src/pages/APICredentials/APICredentialsPage.tsx
**Acceptance / logic checks:**
- Page fetches GET /portal/v1/credentials on mount.
- webhookConsecutiveFailures >= 3 renders the warning banner text.
- webhookConsecutiveFailures = 2 does not render the warning banner.
- APICredentialsPanel is rendered with data from the API response.
- ErrorState with Retry renders on network failure.
**Depends on:** 12.3-T05, 12.3-T25

### 12.3-T27 — Build NotificationBell component and NotificationsDropdown for portal header  _(35 min)_
**Context:** PRD-08 §9.1: Notification bell in portal header shows unread count badge. Notification types: Low balance (High severity -- color-danger), Settlement statement ready (Info -- color-brand), Settlement confirmed (Info), Webhook failure (Warning -- color-warning). Notifications stored per-partner-user; dismissing clears for that user only. Unread count on bell icon. UX-11 §3.2 shows bell with badge count in top bar.
**Steps:** Create src/components/NotificationBell/NotificationBell.tsx accepting notifications array {id, type, message, severity, isRead} and onDismiss(id).; Show numeric badge on bell icon when unread count > 0; hide badge when count = 0.; Clicking bell toggles a dropdown list of notifications.; Each notification shows severity-appropriate left border colour (danger for Low balance, warning for Webhook failure, brand/blue for Info).; Dismiss button per notification calls onDismiss(id); dismissed item removed from list and unread count decremented.
**Deliverable:** src/components/NotificationBell/NotificationBell.tsx
**Acceptance / logic checks:**
- notifications=[{...isRead:false},...] with 3 unread items shows badge '3' on bell icon.
- All notifications read: badge hidden.
- Clicking bell renders a dropdown listing notification messages.
- Dismissing a 'Low balance' notification (severity=HIGH) removes it from the list and decrements badge count.
- Low balance notification has danger-colour (#E02424) left border; webhook failure notification has warning-colour (#D97706).
**Depends on:** 12.3-T01

### 12.3-T28 — Build LoginPage with username, password, and TOTP fields  _(40 min)_
**Context:** PRD-08 §2.1 and SEC-09: Phase 1 auth is GMEPay+ native -- username + password + TOTP 2FA, all required. Session: 8-hour idle timeout, 24-hour absolute limit. Password policy enforced server-side. Portal at portal.gmepay.com (separate domain from admin.gmepay.com). POST /portal/v1/auth/login with {username, password, totp_code}. On success receive JWT; store in httpOnly cookie (not localStorage). On failure show inline error 'Invalid credentials or 2FA code'.
**Steps:** Create src/pages/Login/LoginPage.tsx with username (text), password (password), and totp_code (text, 6-digit numeric) inputs.; On submit POST /portal/v1/auth/login; show spinner on submit button during request.; On success store JWT in httpOnly session cookie (server sets cookie; client does not manipulate localStorage).; On 401 response render inline error 'Invalid credentials or 2FA code' below the form.; All fields have associated label elements; totp_code has inputmode='numeric' and maxLength=6.
**Deliverable:** src/pages/Login/LoginPage.tsx
**Acceptance / logic checks:**
- Form has three fields: username, password (type=password), totp_code (inputmode=numeric maxLength=6).
- Submit button shows spinner and is disabled during the POST request.
- 401 response renders inline error 'Invalid credentials or 2FA code'.
- Successful login navigates to the Dashboard page.
- No JWT or credential is written to localStorage or sessionStorage by the client.
**Depends on:** 12.3-T01

### 12.3-T29 — Implement route guard and partner-scoped routing configuration  _(45 min)_
**Context:** PRD-08 §2.1: every page of the Partner Portal requires an authenticated session. Unauthenticated requests redirect to /login. Partner context (partner_id, partner_name, partner_type, role) is derived from the JWT claims on the server; the frontend reads it from the /portal/v1/me endpoint on app load. PRD-08 §2.2: partner_id comes only from JWT claims, never from URL parameters. Route structure: /{partnerSlug}/dashboard, /{partnerSlug}/transactions, /{partnerSlug}/transactions/:txnRef, /{partnerSlug}/balance, /{partnerSlug}/statements, /{partnerSlug}/api-credentials.
**Steps:** Create src/router/AppRouter.tsx defining all 6 portal routes under /{partnerSlug}/.; Create ProtectedRoute component that checks auth state; if unauthenticated redirects to /login.; On app load fetch GET /portal/v1/me to hydrate partner context (partnerName, partnerType, role) into React context.; If GET /portal/v1/me returns 401 redirect to /login.; PartnerViewer role users attempting to access /users management route receive a 'Access denied' message (AC-14; user management is out of scope in Phase 1 but role enforcement must be present).
**Deliverable:** src/router/AppRouter.tsx and src/contexts/PartnerContext.tsx
**Acceptance / logic checks:**
- Navigating to /{slug}/dashboard without a valid session redirects to /login.
- After login GET /portal/v1/me is called and partnerName is shown in PortalShell top bar.
- GET /portal/v1/me returning 401 redirects to /login.
- All 6 routes render the correct page component.
- partnerType from /me response is passed to BalanceSummaryPanel as isOverseas correctly.
**Depends on:** 12.3-T06, 12.3-T11, 12.3-T28

### 12.3-T30 — Write unit tests for MoneyDisplay and formatRate utilities  _(30 min)_
**Context:** UX-11 §2.8 and PRD-08 §10.2 specify strict money and rate formatting. Test vectors: formatMoney(10234.56,'USD') = 'USD 10,234.56'; formatMoney(45000,'KRW') = 'KRW 45,000'; formatMoney(0,'USD') = 'USD 0.00'; formatMoney(-33.83,'USD',true) uses Unicode minus U+2212 not hyphen; formatRate(1350.42) = '1,350.42'; formatRate(0.00074) = '0.000740' (6 sig figs). All tests must pass with no floating-point rounding errors.
**Steps:** Create src/utils/__tests__/formatMoney.test.ts with Jest or Vitest.; Add test cases for all vectors listed in context including zero, negative, KRW integer, USD 2dp, MNT 2dp.; Add test case asserting the negative sign character is U+2212 (charCodeAt check), not hyphen-minus U+002D.; Create src/utils/__tests__/formatRate.test.ts with vectors: 1350.42, 0.00074, 1.0, 1350420.0.; Assert all tests pass with no floating-point rounding divergence (use exact string equality).
**Deliverable:** src/utils/__tests__/formatMoney.test.ts and src/utils/__tests__/formatRate.test.ts
**Acceptance / logic checks:**
- formatMoney(10234.56,'USD') === 'USD 10,234.56' passes.
- formatMoney(45000,'KRW') === 'KRW 45,000' (no decimal) passes.
- formatMoney(0,'USD') === 'USD 0.00' passes (not blank).
- Negative test: the minus character in the output has charCode 8722 (U+2212), not 45 (hyphen).
- formatRate(0.00074) === '0.000740' passes (6 sig figs, trailing zero preserved).
**Depends on:** 12.3-T03

### 12.3-T31 — Write unit tests for TransactionFilterBar validation logic  _(35 min)_
**Context:** UX-11 §7.2: date range from <= to, range <= 90 days; error message exactly 'Date range cannot exceed 90 days'. Validation fires on Search click, not on keystroke. PRD-08 §4.1: max range 90 days per query; default last 7 days. Test vectors: 90-day range must pass; 91-day range must fail; reversed dates (to < from) must fail; same-day range (from = to) must pass.
**Steps:** Create src/components/TransactionFilterBar/__tests__/TransactionFilterBar.test.tsx.; Test: default date range on mount is today-7d to today (use mocked date).; Test: setting 91-day range and clicking Search shows error 'Date range cannot exceed 90 days' and does not call onSearch.; Test: setting exactly 90-day range and clicking Search calls onSearch without error.; Test: to < from shows validation error and does not call onSearch.
**Deliverable:** src/components/TransactionFilterBar/__tests__/TransactionFilterBar.test.tsx
**Acceptance / logic checks:**
- 91-day range test asserts onSearch mock is NOT called and error text 'Date range cannot exceed 90 days' is in the DOM.
- 90-day range test asserts onSearch IS called once.
- Reversed-date test asserts onSearch is NOT called.
- Same-day range test asserts onSearch IS called.
- Default mount test asserts dateFrom is exactly 7 days before today using mocked current date.
**Depends on:** 12.3-T12

### 12.3-T32 — Write unit tests for BalanceSummaryPanel conditional rendering and threshold logic  _(30 min)_
**Context:** PRD-08 §3.1 and §6.1: Panel hidden for LOCAL partners (isOverseas=false). LOW badge shown when balance < threshold; OK indicator when balance >= threshold. Threshold default USD 10,000. Edge cases: balance exactly equal to threshold shows OK (not LOW); balance = 0 shows LOW and USD 0.00 (not blank).
**Steps:** Create src/components/BalanceSummaryPanel/__tests__/BalanceSummaryPanel.test.tsx.; Test: isOverseas=false renders null (queryByTestId returns null).; Test: balance=9999.99, threshold=10000 renders LOW badge.; Test: balance=10000, threshold=10000 renders OK indicator (boundary: equal = OK, not LOW).; Test: balance=0, threshold=10000 renders LOW badge and 'USD 0.00' text.; Test: balance=45200, threshold=10000 renders OK indicator.
**Deliverable:** src/components/BalanceSummaryPanel/__tests__/BalanceSummaryPanel.test.tsx
**Acceptance / logic checks:**
- isOverseas=false test: component returns null and no child elements are in the DOM.
- balance=9999.99 test: LOW badge element is present in the DOM.
- balance=10000 test: OK indicator is present, LOW badge is absent (boundary equality).
- balance=0 test: LOW badge present AND 'USD 0.00' text present.
- balance=45200 test: OK indicator present, LOW badge absent.
**Depends on:** 12.3-T07

### 12.3-T33 — Write data-isolation integration test for partner-scoped API calls  _(40 min)_
**Context:** PRD-08 §2.2 and AC-02: A user from Partner A must receive an empty result (not an error) when querying transaction IDs that belong to Partner B. The server derives partner_id from JWT claims only -- never from URL params or request body. Test uses two synthetic partners P-TEST-002 (TestSendMN) and P-TEST-005 (TestPartnerB) from QA-12 §3.1.
**Steps:** Create src/api/__tests__/dataIsolation.test.ts (integration test against a test API server or MSW mock).; Authenticate as P-TEST-002; obtain a JWT with partner_id=P-TEST-002.; Attempt to fetch a transaction known to belong to P-TEST-005 via GET /portal/v1/transactions/{partnerB_txn_ref}.; Assert the response is HTTP 404 (not 403, not 200 with data).; Assert the response body does not contain any amount, rate, or reference data belonging to P-TEST-005.
**Deliverable:** src/api/__tests__/dataIsolation.test.ts
**Acceptance / logic checks:**
- P-TEST-002 JWT querying a P-TEST-005 txn_ref receives HTTP 404.
- The 404 response body does not contain partner_id, collection_amount, or any P-TEST-005 data fields.
- The same request with P-TEST-005 JWT receives HTTP 200 with the expected transaction data (positive control).
- Response does not reveal whether the transaction exists for another partner (no 403 or 'Forbidden' message).
- Test passes with partner_id in JWT claims and NOT in the request URL or body.
**Depends on:** 12.3-T18, 12.3-T29

### 12.3-T34 — Write unit tests for AmountsPanel field visibility (cross-border vs Domestic)  _(30 min)_
**Context:** PRD-08 §5.1 and §5.2 and AC-07/AC-08: Cross-border transaction shows collection_usd, payout_usd_cost, rate_locked_at. Domestic (same-currency) transaction does NOT show those fields. Neither transaction type ever shows m_a, m_b, cost_rate_coll, cost_rate_pay, collection_margin_usd, or payout_margin_usd.
**Steps:** Create src/components/AmountsPanel/__tests__/AmountsPanel.test.tsx.; Test: cross-border transaction (isCrossBorder=true) renders elements with data-testid containing 'collection-usd', 'payout-usd-cost', 'rate-locked-at'.; Test: domestic transaction (isCrossBorder=false) does NOT render elements with those data-testids.; Test: neither transaction type renders elements with data-testid containing 'm-a', 'm-b', 'cost-rate', 'collection-margin', 'payout-margin'.; Test: offer_rate=1350.42 renders '1,350.42' using formatRate (6 sig figs).
**Deliverable:** src/components/AmountsPanel/__tests__/AmountsPanel.test.tsx
**Acceptance / logic checks:**
- Cross-border test: collection-usd element present in DOM.
- Domestic test: collection-usd element absent from DOM.
- Internal-fields test: querying DOM for m_a text or data-testid='m-a' returns null for both transaction types.
- offer_rate format test: '1,350.42' string present in rendered output.
- rate_locked_at for cross-border renders ISO-8601 UTC string, not a local time.
**Depends on:** 12.3-T15

### 12.3-T35 — Write unit tests for CSV export field exclusion  _(30 min)_
**Context:** PRD-08 §4.4 and AC-06: CSV export must include offer_rate, scheme_txn_ref, merchant_id, merchant_name, full hub_txn_ref, plus all standard history columns. It must NEVER include m_a, m_b, cost_rate_coll, cost_rate_pay, collection_margin_usd, or payout_margin_usd. Max 10,000 rows per request. Filename pattern: gmepay_txns_{partner_id}_{from_date}_{to_date}.csv.
**Steps:** Create src/api/__tests__/csvExport.test.ts (unit test against a mock CSV generation function or the API handler in a test environment).; Test: exported CSV header row contains offer_rate, scheme_txn_ref, merchant_id, merchant_name, hub_txn_ref columns.; Test: exported CSV header row does NOT contain m_a, m_b, cost_rate_coll, cost_rate_pay, collection_margin_usd, payout_margin_usd.; Test: filename is gmepay_txns_SENDMN_2026-06-01_2026-06-07.csv for partner_id=SENDMN, from=2026-06-01, to=2026-06-07.; Test: requesting export with > 10,000 rows returns an error or is capped at 10,000.
**Deliverable:** src/api/__tests__/csvExport.test.ts
**Acceptance / logic checks:**
- CSV header contains 'offer_rate' column.
- CSV header does NOT contain 'm_a' or 'm_b' columns (exact header name check).
- Filename test: generated filename matches gmepay_txns_SENDMN_2026-06-01_2026-06-07.csv exactly.
- CSV header does not contain 'cost_rate_coll', 'cost_rate_pay', 'collection_margin_usd', or 'payout_margin_usd'.
- Export request for 10,001 rows returns an appropriate error or exactly 10,000 rows.
**Depends on:** 12.3-T14

### 12.3-T36 — Write accessibility audit tests for portal pages (WCAG 2.1 AA)  _(45 min)_
**Context:** UX-11 §8.1: WCAG 2.1 AA requirements -- colour contrast 4.5:1 normal text, 3:1 large text; all interactive elements reachable via Tab; form controls have associated label; tables have th scope col/row; status badges include aria-label with full text; focus trap in modals; aria-invalid on invalid inputs; role=dialog aria-modal=true on modals.
**Steps:** Create src/accessibility/__tests__/axe.test.tsx using jest-axe or equivalent.; Run axe accessibility scan on rendered LoginPage, DashboardPage, TransactionHistoryPage, TransactionDetailPage.; Assert zero WCAG 2.1 AA violations reported by axe-core for each page.; Add targeted test: StatusBadge aria-label equals the status string (e.g. aria-label='DECLINED') not just colour.; Add targeted test: TransactionFilterBar date inputs each have an associated label element with for attribute.
**Deliverable:** src/accessibility/__tests__/axe.test.tsx
**Acceptance / logic checks:**
- axe-core reports 0 violations on the LoginPage render.
- axe-core reports 0 violations on the DashboardPage render (with mock data).
- StatusBadge with status='DECLINED' has aria-label='DECLINED'.
- TransactionFilterBar date-from input has an associated label (htmlFor matches input id).
- All form controls across the portal have associated label elements (no orphaned inputs).
**Depends on:** 12.3-T11, 12.3-T14, 12.3-T18, 12.3-T28

### 12.3-T37 — Implement i18n key-file infrastructure and extract all UI strings  _(55 min)_
**Context:** UX-11 §8.2 and A-05: All UI string literals must be externalised to i18n key files from the first commit so Korean can be added in Phase 2 without code changes. Phase 1 ships English only. Use react-i18next or equivalent. Key naming convention: {page}.{component}.{label} e.g. dashboard.balance.currentBalance.
**Steps:** Install react-i18next and configure an i18nProvider wrapping the app.; Create public/locales/en/translation.json with keys for all visible string literals used across all Portal pages and components.; Replace hard-coded strings in all components created in T01-T29 with t('key') calls.; Verify the app runs identically after the replacement (no visual regressions).; Document the key naming convention in a brief comment at the top of translation.json.
**Deliverable:** public/locales/en/translation.json and i18n config in src/i18n.ts
**Acceptance / logic checks:**
- No hard-coded English string literals remain in any .tsx component file (grep shows 0 matches for quoted label strings like 'Dashboard' or 'Transaction History').
- public/locales/en/translation.json is valid JSON with all required keys.
- App renders correctly in English with all labels matching the spec text.
- Adding a public/locales/ko/translation.json with Korean values and switching locale renders Korean text without any code changes.
- i18n key naming follows {page}.{component}.{label} convention throughout.
**Depends on:** 12.3-T11, 12.3-T14, 12.3-T18, 12.3-T21, 12.3-T24, 12.3-T26

### 12.3-T38 — Implement responsive breakpoint behaviour for Portal Shell and tables  _(50 min)_
**Context:** UX-11 §8.3: Desktop >= 1,440px: full left nav + content. Desktop min 1,280-1,439px: sidebar collapses to icon-only. Tablet landscape 1,024-1,279px: sidebar hidden, hamburger shows overlay. Tablet portrait (768-1,023px): not supported Phase 1 (show unsupported message). Mobile < 768px: not supported. At tablet landscape, tables reduce to 3 most critical columns; a '+' expand control per row reveals suppressed columns.
**Steps:** Add CSS media queries to PortalShell: at 1,280-1,439px collapse nav to 56px icon-only; at < 1,024px hide nav and show hamburger.; Add a viewport-width check: at < 1,024px AND >= 768px render a banner 'For the best experience use a screen wider than 1,280 px'.; Update DataTable to accept a mobileColumns prop (array of column keys to show at tablet landscape); at < 1,280px hide non-primary columns.; Add a '+' expand toggle per row that reveals suppressed column values inline when clicked.; Verify no horizontal scroll appears at 1,280px viewport width on any page.
**Deliverable:** Updated PortalShell CSS, DataTable responsive logic, unsupported-viewport banner
**Acceptance / logic checks:**
- At 1,280px viewport PortalShell nav collapses to 56px icon-only width.
- At 1,023px viewport hamburger is visible and nav is hidden.
- At 768px viewport the 'use wider screen' banner is displayed.
- DataTable at 1,024px renders exactly the 3 columns specified in mobileColumns prop.
- No horizontal scrollbar appears on TransactionHistoryPage at 1,280px viewport width.
**Depends on:** 12.3-T06, 12.3-T04


<!-- wbs-v3-gap-closure -->

---

## WBS v3 gap-closure tickets (re-baseline, 2026-06-10)

These tickets convert this service's PARTIAL audit findings into DONE and add work discovered during the build. Statuses live on the `Backlog` sheet of `GMEPay+_Task_Backlog.xlsx`; phase sequencing on the `Completion Plan v3` sheet of `GMEPay+_WBS.xlsx`.

### 18.4-G04 — Portal real login + partner scoping
*Completion phase:* **R3** · *Est:* 120 min · *Role:* Frontend · *Deps:* 18.4-G02

**Context.** Same as admin but partner-scoped: partnerId claim drives all data fetches; no partner switcher.

**Steps.**
- Auth slice update
- partnerId from token, not localStorage
- 403 page on scope violation

**Deliverable.** Real login in portal

**Acceptance.**
- Cross-partner URL manipulation shows 403 page

### 18.5-G02 — Per-slice contract tests (portal)
*Completion phase:* **R4** · *Est:* 100 min · *Role:* Frontend · *Deps:* 18.1-G01

**Context.** Same for the portal's 8 slices.

**Steps.**
- Same fixture pattern
- Statement CSV header pinned too

**Deliverable.** 8 slices contract-pinned

**Acceptance.**
- Drift fails portal CI

