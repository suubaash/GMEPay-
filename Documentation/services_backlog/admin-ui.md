# admin-ui  (frontend)

**Scope:** Ops/Admin portal (React/Next.js): schemes, partners, mapping page, monitoring

**Owned WBS work-packages:** 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7, 10.8, 10.9, 10.10, 10.11, 12.2  ·  **Tickets:** 360  ·  **Est:** 246.6h

## Service contract (MSA: own DB, API-only communication)

- **Datastore (owned by this service):** none (browser client)
- **APIs / events I EXPOSE:** Ops/Admin web app (React/Next.js)
- **APIs / events I CONSUME:** bff + service APIs (read + config writes), sync
- **Integration rule:** never read another service's database or import its private entities — call its API or consume its event; stub consumed services with WireMock in tests.

> Self-contained backlog for this service. Build it as its own repo/module with its own DB + Flyway migrations, against the `shared-libs` contracts (lib-money / lib-errors / lib-events / lib-api-contracts only). Each ticket has a deliverable + acceptance checks.


## WBS 10.1 — Admin app shell, nav & auth (SSO/2FA)
### 10.1-T01 — Create hub_user and hub_role DB migration  _(40 min)_
**Context:** Admin System (PRD-07, SEC-09) uses RBAC with four roles: SUPER_ADMIN, OPS_OPERATOR, FINANCE_ANALYST, ADMIN_VIEWER. hub_user: id (UUID v4), email (unique), name, role_id (FK -> hub_role.id), is_active (bool default true), last_login_at, created_at, updated_at, created_by (UUID FK -> hub_user.id nullable). hub_role: id (UUID v4), role_code (ENUM: SUPER_ADMIN|OPS_OPERATOR|FINANCE_ANALYST|ADMIN_VIEWER), permissions (JSONB), created_at, updated_at. Passwords are bcrypt-hashed; stored in a separate hub_user_credential table (user_id FK, password_hash TEXT, must_change bool default true, updated_at). No local passwords for operators in the final SSO model, but Phase 1 uses local accounts.
**Steps:** Create Flyway/Liquibase migration V10_1_001: create hub_role with role_code ENUM, seed the four canonical roles with empty permissions JSONB; Create V10_1_002: create hub_user with all specified columns; FK to hub_role; index on email; Create V10_1_003: create hub_user_credential (user_id UUID PK FK, password_hash TEXT NOT NULL, must_change BOOLEAN DEFAULT TRUE, updated_at TIMESTAMPTZ); unique constraint on user_id; Create V10_1_004: create hub_auth_event (id UUID PK, user_id FK, event_type VARCHAR(30), ip_address INET, user_agent TEXT, session_id UUID nullable, occurred_at TIMESTAMPTZ); index on user_id and occurred_at; Verify migrations apply cleanly on a fresh schema with no data
**Deliverable:** Four Flyway/Liquibase migration files creating hub_role, hub_user, hub_user_credential, hub_auth_event tables
**Acceptance / logic checks:**
- hub_user.email has a unique index; inserting two rows with the same email raises a constraint violation
- hub_user.role_id FK rejects an unknown role UUID
- hub_user_credential.user_id is PK (one credential row per user); duplicate insert raises violation
- hub_auth_event.event_type accepts LOGIN_SUCCESS, LOGIN_FAILED, LOGOUT, SESSION_TIMEOUT, PASSWORD_CHANGED
- All four migrations apply and rollback cleanly; schema matches ERD

### 10.1-T02 — Seed canonical hub_role permission JSONB for all four roles  _(35 min)_
**Context:** PRD-07 §12.3 defines the permission matrix. hub_role.permissions JSONB encodes allowed actions as a flat map of action_code -> boolean. Action codes (from PRD-07 matrix): VIEW_DASHBOARD, MANAGE_SCHEME, ACTIVATE_SCHEME, VIEW_SCHEME, MANAGE_PARTNER, VIEW_CREDENTIALS, MANAGE_CREDENTIALS, MANAGE_RULE, UPDATE_FX_RATE, VIEW_FX_HISTORY, VIEW_TRANSACTIONS, VIEW_POOL_VALUES, VIEW_PREFUNDING, RECORD_TOPUP, MANUAL_ADJUSTMENT, INITIATE_REFUND, VIEW_REFUND, VIEW_SETTLEMENT, EXPORT_REVENUE, MANAGE_USERS, VIEW_AUDIT_LOG, EXPORT_AUDIT_LOG. Roles: SUPER_ADMIN=all true; OPS_OPERATOR: no MANAGE_USERS, no EXPORT_AUDIT_LOG, no MANUAL_ADJUSTMENT, no VIEW_POOL_VALUES (false); FINANCE_ANALYST: VIEW_DASHBOARD, VIEW_FX_HISTORY, VIEW_TRANSACTIONS, VIEW_POOL_VALUES, VIEW_PREFUNDING, MANUAL_ADJUSTMENT, VIEW_REFUND, VIEW_SETTLEMENT, EXPORT_REVENUE all true, rest false; ADMIN_VIEWER: VIEW_DASHBOARD, VIEW_FX_HISTORY, VIEW_TRANSACTIONS, VIEW_REFUND all true, rest false.
**Steps:** Write a data migration (V10_1_005) that UPDATEs hub_role.permissions JSONB for each of the four role_codes using the exact matrix from PRD-07 §12.3; Include a comment block in the migration listing every action code and its value per role for traceability; Add a CHECK constraint or trigger to reject any permissions JSONB that contains unknown action codes; Write a brief unit-test SQL that asserts SUPER_ADMIN has 22 true entries, ADMIN_VIEWER has exactly 4 true entries
**Deliverable:** Migration V10_1_005 seeding permissions JSONB for all four roles, plus SQL assertion test
**Acceptance / logic checks:**
- SUPER_ADMIN permissions JSONB contains all 22 action codes set to true
- ADMIN_VIEWER cannot VIEW_POOL_VALUES (value is false or absent)
- OPS_OPERATOR cannot MANAGE_USERS (false)
- FINANCE_ANALYST can MANUAL_ADJUSTMENT but cannot MANAGE_PARTNER
- Inserting a permissions JSONB with an unrecognised key raises a constraint violation
**Depends on:** 10.1-T01

### 10.1-T03 — Implement PermissionGuard service: hasPermission(userId, actionCode)  _(45 min)_
**Context:** All Admin API endpoints check whether the authenticated user holds the required action_code. The guard queries hub_user.role_id -> hub_role.permissions JSONB. Returns true only if permissions->actionCode = true. User accounts with is_active=false must always return false regardless of role. The service must cache the role permissions map in-process (invalidated on role update) to avoid per-request DB round-trips.
**Steps:** Create PermissionGuard class/service with method hasPermission(userId: UUID, actionCode: String): Boolean; Load role permissions from hub_role on startup and on role-update event; expose a refreshPermissions() hook for tests; On call: fetch user from DB (or cache), check is_active, look up role permissions map, return permissions[actionCode] == true; Throw FORBIDDEN (HTTP 403) when hasPermission returns false; throw UNAUTHORIZED (HTTP 401) when userId not found or session invalid; Write unit tests covering: active SUPER_ADMIN returns true for MANAGE_USERS, inactive SUPER_ADMIN returns false, OPS_OPERATOR returns false for MANAGE_USERS, unknown actionCode returns false
**Deliverable:** PermissionGuard service class with unit tests
**Acceptance / logic checks:**
- Active SUPER_ADMIN.hasPermission(MANAGE_USERS) == true
- Inactive SUPER_ADMIN.hasPermission(VIEW_DASHBOARD) == false regardless of role
- OPS_OPERATOR.hasPermission(MANAGE_USERS) == false
- Unknown actionCode returns false (no exception)
- FINANCE_ANALYST.hasPermission(MANUAL_ADJUSTMENT) == true
**Depends on:** 10.1-T02

### 10.1-T04 — Implement local-account password hashing and validation service  _(45 min)_
**Context:** Phase 1 uses local portal accounts (PRD-07 §12.5, SEC-09 §3.1 note: SSO is Phase 2). Passwords must be bcrypt-hashed at rest (SEC-09 §3.2: min 12 chars, complexity required). The PasswordService must: hash on creation/change, verify on login, enforce minimum 12 chars + at least 1 uppercase + 1 digit + 1 special char. must_change flag in hub_user_credential must be honoured: if true, any non-password-change API call returns HTTP 403 MUST_CHANGE_PASSWORD. Password history: last 5 hashes stored; reuse of a recent password rejected.
**Steps:** Implement PasswordService.hash(plaintext) using bcrypt with work factor 12; store result in hub_user_credential.password_hash; Implement PasswordService.verify(plaintext, hash) returning boolean; Implement PasswordService.validate(plaintext) throwing PasswordPolicyViolation if < 12 chars or missing uppercase/digit/special; Add hub_user_password_history table (user_id, password_hash, changed_at) via new migration; keep last 5 entries; Implement PasswordService.isRecentlyUsed(userId, plaintext) checking last 5 hashes; return true if match found
**Deliverable:** PasswordService class, hub_user_password_history migration, and unit tests
**Acceptance / logic checks:**
- Password 'Admin1234' (no special char) fails validation with MISSING_SPECIAL_CHAR
- Password 'Correct$Horse9' (13 chars) passes validation and hash verifies correctly
- Reusing the same password when it is in the last 5 hashes returns isRecentlyUsed=true
- bcrypt hash of same plaintext differs on each call (salt); verify returns true for both
- must_change=true blocks a non-password-change request with HTTP 403 MUST_CHANGE_PASSWORD
**Depends on:** 10.1-T01

### 10.1-T05 — Implement POST /admin/auth/login endpoint  _(50 min)_
**Context:** Admin System login (PRD-07 §12.5, SEC-09 §3.1): accepts email + password JSON body, verifies credentials, enforces account lockout after 5 consecutive failures, issues a session JWT (8-hour absolute expiry, 30-minute idle timeout configurable). Session JWT payload: {sub: user_id, email, role_code, session_id: UUID, iat, exp}. Signed with HS256 using internal JWT secret from secrets vault. All login events (success and failure) must be written to hub_auth_event with event_type LOGIN_SUCCESS or LOGIN_FAILED, ip_address from X-Forwarded-For header, occurred_at UTC. On LOGIN_FAILED x5, set is_active=false and event_type=ACCOUNT_LOCKED.
**Steps:** Create POST /admin/auth/login handler accepting {email, password}; Call PasswordService.verify; on failure increment consecutive_failures counter (stored in hub_user_credential or Redis); on 5th failure set is_active=false and write ACCOUNT_LOCKED event; On success: reset failure counter, update last_login_at, write LOGIN_SUCCESS to hub_auth_event, issue JWT; JWT expiry = min(iat+8h, last_activity+30min); include session_id UUID v4; Return 200 {token, expires_at, must_change} on success; 401 INVALID_CREDENTIALS on failure (never reveal which field is wrong); 423 ACCOUNT_LOCKED if is_active=false
**Deliverable:** POST /admin/auth/login handler with integration tests
**Acceptance / logic checks:**
- Valid credentials return 200 with JWT containing correct role_code and session_id
- 5 consecutive bad passwords for same account set is_active=false and subsequent login returns 423 ACCOUNT_LOCKED
- Successful login writes LOGIN_SUCCESS event to hub_auth_event with non-null ip_address
- must_change=true is returned in login response when hub_user_credential.must_change=true
- Login with inactive account (is_active=false) returns 423 before credential check
**Depends on:** 10.1-T03, 10.1-T04

### 10.1-T06 — Implement session JWT middleware and idle-timeout tracking  _(50 min)_
**Context:** Every authenticated Admin API request must pass through a JWT middleware that: verifies the HS256 signature, checks exp claim, checks the session has not been explicitly invalidated (logout/forced-revoke), and enforces the 30-minute idle timeout. Idle timeout is tracked by storing last_activity_at per session_id in Redis (TTL = 30 min). On each valid request the TTL is reset. If the Redis key is absent (expired), return 401 SESSION_EXPIRED. Logout deletes the Redis key. Session store key format: admin_session:{session_id}.
**Steps:** Implement JwtMiddleware that extracts Bearer token, verifies signature with vault-sourced secret, checks exp; On valid JWT: look up Redis key admin_session:{session_id}; if absent return 401 SESSION_EXPIRED; if present reset TTL to 30 minutes; Attach authenticated user context (user_id, role_code, session_id) to request for downstream handlers; Implement POST /admin/auth/logout that deletes admin_session:{session_id} from Redis and writes LOGOUT event to hub_auth_event; Write integration test: login -> wait (mock) 31 min idle -> next request returns 401 SESSION_EXPIRED
**Deliverable:** JwtMiddleware and POST /admin/auth/logout with integration tests
**Acceptance / logic checks:**
- Request with expired JWT (exp in past) returns 401 TOKEN_EXPIRED
- Request with valid JWT but no Redis session key returns 401 SESSION_EXPIRED
- Valid request resets Redis TTL to 30 minutes (observable via TTL command)
- POST /admin/auth/logout deletes Redis key and subsequent request returns 401 SESSION_EXPIRED
- Logout writes event_type=LOGOUT to hub_auth_event with correct session_id
**Depends on:** 10.1-T05

### 10.1-T07 — Implement POST /admin/auth/change-password endpoint  _(45 min)_
**Context:** When hub_user_credential.must_change=true, the user may only call POST /admin/auth/change-password before any other endpoint (all others return 403 MUST_CHANGE_PASSWORD). Endpoint requires authenticated session (JWT from login), accepts {current_password, new_password}. Must verify current password, enforce password policy (>=12 chars, uppercase+digit+special), check not in last 5 used, set must_change=false, update password_hash, record in hub_user_password_history, write PASSWORD_CHANGED to hub_auth_event. On success reissue a new JWT (new session_id, fresh 8h exp).
**Steps:** Implement POST /admin/auth/change-password behind JwtMiddleware; Verify current_password against existing hash; reject with 401 INVALID_CURRENT_PASSWORD if wrong; Call PasswordService.validate(new_password); call PasswordService.isRecentlyUsed; reject with 422 PASSWORD_RECENTLY_USED if match; Update hub_user_credential (password_hash, must_change=false, updated_at); insert into hub_user_password_history; Write PASSWORD_CHANGED to hub_auth_event; issue new JWT with new session_id; return {token, expires_at}
**Deliverable:** POST /admin/auth/change-password handler with unit and integration tests
**Acceptance / logic checks:**
- Wrong current_password returns 401 INVALID_CURRENT_PASSWORD, must_change remains true
- New password matching a hash in last 5 entries returns 422 PASSWORD_RECENTLY_USED
- Valid change sets must_change=false in hub_user_credential and returns new JWT
- New JWT has a different session_id from the login JWT
- Old Redis session key for prior session_id is deleted after password change
**Depends on:** 10.1-T06

### 10.1-T08 — Implement MFA TOTP enrollment and verification for admin accounts  _(55 min)_
**Context:** SEC-09 §3.1: MFA is required for all Admin portal logins. Phase 1 implementation: TOTP (RFC 6238, 30-second window, SHA-1, 6 digits). PRD-07 §12.5: MFA strongly recommended and enforced for OPS_OPERATOR and above (SUPER_ADMIN, OPS_OPERATOR). FINANCE_ANALYST and ADMIN_VIEWER may optionally enroll. Enrollment: generate TOTP secret (base32), return QR code URI (otpauth://totp/GMEPayAdmin:{email}?secret={secret}&issuer=GMEPay), store hashed secret in hub_user_mfa (user_id PK, totp_secret_enc TEXT encrypted with AES-256, is_enabled BOOL, enrolled_at). Recovery codes: 8 x 10-char alphanumeric codes stored hashed; each single-use.
**Steps:** Create migration V10_1_006: hub_user_mfa (user_id PK FK, totp_secret_enc TEXT, is_enabled BOOL DEFAULT FALSE, enrolled_at TIMESTAMPTZ); hub_user_recovery_code (id UUID PK, user_id FK, code_hash TEXT, used_at TIMESTAMPTZ nullable); Implement POST /admin/auth/mfa/enroll: generate TOTP secret, encrypt with AES-256, store in hub_user_mfa; return otpauth URI and 8 recovery code plaintexts (shown once); Implement POST /admin/auth/mfa/verify-enrollment {totp_code}: verify provided code against secret; if valid set is_enabled=true; Update login flow: after password verify, if is_enabled=true return 200 {mfa_required: true, mfa_token} (short-lived 5-min token); client must POST /admin/auth/mfa/challenge {mfa_token, totp_code} to complete login and receive session JWT; Enforce: SUPER_ADMIN and OPS_OPERATOR with is_enabled=false cannot complete login (return 403 MFA_ENROLLMENT_REQUIRED)
**Deliverable:** TOTP enrollment and challenge endpoints, hub_user_mfa migration, updated login flow
**Acceptance / logic checks:**
- Valid TOTP code at enrollment confirm sets is_enabled=true
- SUPER_ADMIN with MFA not enrolled cannot complete login (403 MFA_ENROLLMENT_REQUIRED)
- ADMIN_VIEWER without MFA can complete login (MFA optional for this role)
- TOTP code reuse within same 30-second window returns 401 TOTP_ALREADY_USED
- Valid recovery code completes MFA challenge and marks code used_at; reuse returns 401 RECOVERY_CODE_USED
**Depends on:** 10.1-T06

### 10.1-T09 — Implement forced account unlock endpoint (Super Admin only)  _(40 min)_
**Context:** SEC-09 §3.1: after 5 failed login attempts the account is locked (is_active=false). Only a SUPER_ADMIN can unlock accounts via POST /admin/users/{userId}/unlock. This requires the caller to hold MANAGE_USERS permission. The action resets consecutive_failures counter to 0, sets is_active=true, writes an ACCOUNT_UNLOCKED event to hub_auth_event with the acting user_id. Super Admins can also POST /admin/users/{userId}/force-password-reset to set must_change=true.
**Steps:** Implement POST /admin/users/{userId}/unlock behind JwtMiddleware + PermissionGuard(MANAGE_USERS); Set is_active=true, reset consecutive_failures=0 in hub_user_credential; write ACCOUNT_UNLOCKED to hub_auth_event with actor user_id and target user_id; Implement POST /admin/users/{userId}/force-password-reset: set must_change=true; write PASSWORD_RESET_FORCED to hub_auth_event; Return 204 on success; 404 if userId not found; 403 if caller lacks MANAGE_USERS; Write tests: OPS_OPERATOR attempting to unlock returns 403; SUPER_ADMIN unlocking a locked user allows subsequent login
**Deliverable:** Unlock and force-reset endpoints with permission guard and tests
**Acceptance / logic checks:**
- OPS_OPERATOR calling /unlock returns 403 FORBIDDEN
- SUPER_ADMIN unlocks a locked user; user can subsequently log in with valid credentials
- Unlock writes ACCOUNT_UNLOCKED to hub_auth_event with both actor_user_id and target user_id
- force-password-reset sets must_change=true and user sees 403 MUST_CHANGE_PASSWORD on next non-password-change request
- Unlocking a user who is not locked (is_active=true) returns 204 without error (idempotent)
**Depends on:** 10.1-T06, 10.1-T03

### 10.1-T10 — Implement GET /admin/users and POST /admin/users (user management CRUD)  _(45 min)_
**Context:** PRD-07 §12.4: SUPER_ADMIN can create user accounts (email + temporary password, must_change=true), assign role, deactivate/reactivate, view last_login_at and session history. Action code MANAGE_USERS required. POST /admin/users body: {email, name, role_code}. System generates a random 16-char temp password (bcrypt-hashed, must_change=true). GET /admin/users returns list: id, email, name, role_code, is_active, last_login_at. PATCH /admin/users/{id} allows updating name, role_code, is_active. All write operations must be audit-logged (see 10.1-T20 for audit-log write service).
**Steps:** Implement GET /admin/users (requires MANAGE_USERS): return paginated list with columns id, email, name, role_code, is_active, last_login_at; Implement POST /admin/users (MANAGE_USERS): validate email format, unique email, valid role_code; generate 16-char temp password; hash and store; set must_change=true; write USER_CREATED to audit log; Implement PATCH /admin/users/{id} (MANAGE_USERS): allow updating name, role_code (validate enum), is_active; write USER_UPDATED to audit log with changed fields; Return 409 DUPLICATE_EMAIL if email already exists; Temp password must be returned in POST response (plaintext, shown once only) with warning note in response body
**Deliverable:** GET, POST, PATCH /admin/users endpoints with unit tests
**Acceptance / logic checks:**
- POST /admin/users with duplicate email returns 409 DUPLICATE_EMAIL
- POST /admin/users returns temp password in response body; hub_user_credential.must_change=true
- PATCH /admin/users/{id} changing role_code=FINANCE_ANALYST takes effect on next permission check
- OPS_OPERATOR calling POST /admin/users returns 403 FORBIDDEN
- Deactivating a user (is_active=false via PATCH) prevents that user from logging in (423 ACCOUNT_LOCKED equivalent)
**Depends on:** 10.1-T03, 10.1-T06

### 10.1-T11 — Scaffold React SPA admin shell: Vite + React + React Router project structure  _(45 min)_
**Context:** UX-11 §3.1: Admin System is a React SPA (or equivalent). Target browser: latest 2 versions of Chrome/Firefox/Edge/Safari. Min viewport 1280px, desktop-first. Separate deployment from Partner Portal. Tech stack decision: Vite + React 18 + TypeScript + React Router v6 + Axios for API calls. Design tokens from UX-11 §2 (Inter font, 8px grid, named CSS custom properties). Project must support code-splitting per route. No native mobile support required.
**Steps:** Initialise Vite+React+TypeScript project at packages/admin-ui (or equivalent monorepo path); Install dependencies: react-router-dom v6, axios, date-fns (for KST display), @tanstack/react-query for server state; Define CSS custom properties in src/styles/tokens.css matching UX-11 §2.2 exactly: --color-brand #1A56DB, --color-danger #E02424, --color-warning #D97706, --color-success #057A55, --color-neutral-900 #111928, --color-neutral-50 #F9FAFB, --color-surface #FFFFFF; Set up React Router with lazy-loaded route components for each nav module (Dashboard, Schemes, Partners, Transactions, Settlement, FXRates, AuditLog, Settings, UsersRoles); Configure Vite proxy to backend API; set up Axios base instance with Authorization header injection from session token in localStorage
**Deliverable:** Scaffolded admin-ui Vite+React project that compiles, runs dev server, and renders a blank shell at localhost
**Acceptance / logic checks:**
- npm run dev starts without errors; http://localhost:5173 renders without console errors
- All 9 route paths are registered in React Router and render a placeholder component
- CSS tokens file defines all 9 --color-* variables matching UX-11 hex values exactly
- Axios base instance attaches Authorization: Bearer {token} header when token is present in localStorage
- Tree-shaken production build (npm run build) completes without errors

### 10.1-T12 — Build AdminShell layout component: sidebar + top bar + content area  _(50 min)_
**Context:** UX-11 §3.1 defines the Admin System shell: left sidebar 240px fixed, top bar 56px, content area max-width 1400px centred with 32px padding. Sidebar collapses to 56px icon-only at <1280px. Top bar: left hamburger to toggle sidebar, centre app title 'GMEPay+ Admin', right section shows logged-in user name + role badge + Logout button. Active nav item has --color-brand left border + brand background tint. Nav items: Dashboard, Schemes, Partners, Transactions, Settlement, FX Rates, Audit Log, Settings (divider before Settings). Font stack: Inter, fallback system fonts.
**Steps:** Create AdminShell.tsx wrapping all authenticated routes; includes Sidebar and TopBar components; Sidebar: render nav items as <NavLink> components; active state adds CSS class with 2px left border --color-brand and background tint; hover shows tooltip with item name; TopBar: hamburger toggles sidebar between 240px and 56px icon-only modes using CSS transition; display user email and role_code badge from auth context; Logout button calls /admin/auth/logout then clears localStorage and redirects to /login; Content area: flex-grow, max-width 1400px, margin auto, padding 32px; Sidebar collapse state persisted in localStorage key admin_sidebar_collapsed
**Deliverable:** AdminShell.tsx, Sidebar.tsx, TopBar.tsx components rendering correct layout
**Acceptance / logic checks:**
- Sidebar width transitions between 240px and 56px on hamburger click; icon-only mode shows nav icons without text
- Active route item has visible --color-brand left border; inactive items do not
- User name and role badge appear in top bar using data from auth context
- Logout button POSTs to /admin/auth/logout, clears token from localStorage, and navigates to /login
- At viewport <1280px sidebar automatically collapses to icon-only (no scrollbar on main content)
**Depends on:** 10.1-T11

### 10.1-T13 — Build Login page UI: email/password form with error states  _(45 min)_
**Context:** UX-11 design system (§2.4 buttons, §2.5 form controls, §2.9 error states). Login page is the unauthenticated entry point at /login. Fields: email (type=email, required), password (type=password, required, show/hide toggle). On submit: POST /admin/auth/login. Handle responses: 200 (store JWT, check must_change and mfa_required flags); 401 INVALID_CREDENTIALS (inline error 'Invalid email or password'); 423 ACCOUNT_LOCKED (inline error 'Account locked. Contact IT admin.'); 403 MFA_ENROLLMENT_REQUIRED (redirect to /setup-mfa). Loading state: disable button, show spinner. All form controls follow UX-11 §2.5 spec (36px height, 1px border, 2px brand focus ring).
**Steps:** Create /login route rendering LoginPage.tsx with email and password inputs and a primary Submit button; On submit call authApi.login(email, password); show skeleton/spinner while in flight; On 200: store token in localStorage; if must_change=true navigate to /change-password; else if mfa_required=true navigate to /mfa-challenge; else navigate to /dashboard; On 401: show inline error below password field using --color-danger text; On 423: show inline error 'Account locked. Contact your IT administrator.'; Ensure password field has show/hide toggle (eye icon); form submits on Enter key
**Deliverable:** LoginPage.tsx component with all states handled and unit tests for each API response
**Acceptance / logic checks:**
- Empty email or password prevents form submission and shows required-field asterisk error
- 401 response displays error below password field; email field is not cleared
- 423 response displays account-locked message (not a generic error)
- Successful login with must_change=true navigates to /change-password, not /dashboard
- Successful login stores JWT in localStorage and navigates to /dashboard
**Depends on:** 10.1-T12

### 10.1-T14 — Build MFA challenge page UI: TOTP code entry form  _(40 min)_
**Context:** After successful password login, if the backend returns {mfa_required: true, mfa_token}, the user is redirected to /mfa-challenge. The page shows a 6-digit TOTP input (autofocus, numeric keypad on mobile). On submit: POST /admin/auth/mfa/challenge {mfa_token, totp_code}. Handle: 200 (store session JWT, proceed to /dashboard or /change-password per must_change); 401 TOTP_INVALID (inline error 'Incorrect code. Try again.'); 401 TOTP_ALREADY_USED (inline error 'Code already used. Wait for next code.'); 401 MFA_TOKEN_EXPIRED (redirect back to /login with message 'Session expired, please log in again'). Also provide a 'Use recovery code instead' link that shows a text input for a 10-char recovery code.
**Steps:** Create /mfa-challenge route; store mfa_token in component state (from navigation state passed by LoginPage); Render 6-digit TOTP input (inputmode=numeric, maxlength=6, autofocus); primary 'Verify' button; On submit POST /admin/auth/mfa/challenge; handle all four response codes with correct messages; 'Use recovery code' toggle swaps TOTP input for a text input accepting a 10-char code; same endpoint with field totp_code omitted and recovery_code present; On 200: persist session JWT to localStorage; navigate per must_change flag; Redirect to /login if navigated to /mfa-challenge without mfa_token in state
**Deliverable:** MfaChallengePage.tsx with TOTP and recovery-code modes and unit tests
**Acceptance / logic checks:**
- Page auto-focuses the TOTP input on mount
- 401 TOTP_ALREADY_USED shows 'Code already used. Wait for next code.' (not a generic error)
- 401 MFA_TOKEN_EXPIRED redirects to /login with visible toast 'Session expired, please log in again'
- Direct navigation to /mfa-challenge without mfa_token in route state redirects to /login
- Recovery code mode text input accepts 10-char input and submits to same endpoint with recovery_code field
**Depends on:** 10.1-T13, 10.1-T08

### 10.1-T15 — Build MFA enrollment page UI: QR code display + verification step  _(50 min)_
**Context:** When a SUPER_ADMIN or OPS_OPERATOR logs in with is_enabled=false for MFA, backend returns 403 MFA_ENROLLMENT_REQUIRED. The admin-ui redirects to /setup-mfa. Flow: (1) Call POST /admin/auth/mfa/enroll to get {otpauth_uri, recovery_codes[8]}. (2) Display QR code (use a qrcode.react component rendering the otpauth_uri). (3) Show recovery codes in a monospace list with 'Copy all' button; display warning 'Store these codes safely - they cannot be shown again'. (4) User enters TOTP code to confirm enrollment. (5) POST /admin/auth/mfa/verify-enrollment {totp_code}; on 200 is_enabled=true, proceed to login flow. This page is only accessible when user has a valid session JWT but MFA not yet enrolled.
**Steps:** Create /setup-mfa route; on mount call POST /admin/auth/mfa/enroll; display loading skeleton while in flight; Render QR code using qrcode.react with the returned otpauth_uri value; Display 8 recovery codes in a 2-column monospace grid with 'Copy all codes' clipboard button; Below codes: 6-digit TOTP input for verification; primary 'Enable MFA' button; On 'Enable MFA' submit: POST /admin/auth/mfa/verify-enrollment; on 200 show success toast 'MFA enabled' and redirect to /dashboard; on 401 show inline error 'Incorrect code - scan QR again or wait for next code'; If user navigates away without completing enrollment, show a confirmation dialog 'MFA setup incomplete - your account will be blocked from login until MFA is enabled'
**Deliverable:** SetupMfaPage.tsx with QR display, recovery codes, and enrollment confirmation
**Acceptance / logic checks:**
- Recovery codes are displayed in monospace; 'Copy all' copies them as newline-separated text to clipboard
- QR code renders the correct otpauth URI (observable via rendered SVG data-testid)
- Invalid TOTP on verify returns inline error; the QR code remains visible (not cleared)
- On successful enrollment a success toast appears and user is redirected to /dashboard
- Navigating away mid-enrollment triggers the 'setup incomplete' confirmation dialog
**Depends on:** 10.1-T14, 10.1-T08

### 10.1-T16 — Implement PrivateRoute guard and auth context in React  _(45 min)_
**Context:** All routes except /login, /mfa-challenge, /setup-mfa must be behind authentication. The React AuthContext stores {user: {id, email, role_code, session_id}, token} from localStorage on page load. PrivateRoute checks AuthContext; if no token redirects to /login. If must_change=true and current route is not /change-password, redirect to /change-password. Permission-aware rendering: some nav items and buttons are conditionally rendered based on role_code. For example, 'Users & Roles' nav item is hidden for roles other than SUPER_ADMIN. Per UX-11: ADMIN_VIEWER cannot see locked pool values section in transaction detail.
**Steps:** Create AuthContext with {user, token, setAuth, clearAuth} and an AuthProvider wrapping the router; On AuthProvider mount: read token from localStorage, decode JWT payload (without re-verifying signature client-side), set user state; Create PrivateRoute component: if no token redirect to /login; if must_change and not on /change-password redirect to /change-password; Add usePermission(actionCode) hook that reads user.role_code from AuthContext and checks against client-side permission map (mirror of PRD-07 §12.3 matrix); Hide 'Users & Roles' nav item unless role_code===SUPER_ADMIN; hide 'View pool values' section in transaction detail unless hasPermission(VIEW_POOL_VALUES)
**Deliverable:** AuthContext, AuthProvider, PrivateRoute, usePermission hook with unit tests
**Acceptance / logic checks:**
- Unauthenticated user visiting /dashboard is redirected to /login
- User with must_change=true visiting /transactions is redirected to /change-password
- ADMIN_VIEWER role: usePermission(VIEW_POOL_VALUES) returns false
- SUPER_ADMIN role: usePermission(MANAGE_USERS) returns true; Users & Roles nav item is visible
- OPS_OPERATOR role: Users & Roles nav item is not rendered in the sidebar
**Depends on:** 10.1-T12, 10.1-T13

### 10.1-T17 — Build Breadcrumb component and page-title header pattern  _(35 min)_
**Context:** UX-11 §3.1: every page inside the shell shows a breadcrumb row above the page title. Format: Module / Sub-section / Current page. Links to parent levels. Breadcrumb is omitted on top-level pages (Dashboard). Page title is 24px weight 600 (UX-11 §2.1 'Page title' type). Breadcrumb links use --color-brand; current (non-link) segment uses --color-neutral-600. 12px body text for breadcrumb. Separator: ' / ' in neutral-600.
**Steps:** Create Breadcrumb.tsx accepting an array of {label, href?} items; render as <nav aria-label='breadcrumb'>; Link segments with href render as <a> styled with --color-brand; final segment (no href) renders as <span> in --color-neutral-600; Create PageHeader.tsx wrapping the page H1 title (24px 600) + Breadcrumb row above it; apply 0 bottom-margin between breadcrumb and title; Integrate PageHeader into at least 3 route pages: Schemes list (breadcrumb: 'Schemes'), Scheme edit (breadcrumb: 'Schemes / Edit Scheme'), and Dashboard (no breadcrumb); Write snapshot tests for Breadcrumb with 1, 2, and 3 segments
**Deliverable:** Breadcrumb.tsx, PageHeader.tsx components integrated into route pages, snapshot tests
**Acceptance / logic checks:**
- Single-segment breadcrumb (top-level page) renders no breadcrumb (PageHeader shows title only)
- Two-segment breadcrumb renders first segment as <a> link and second as plain text
- Breadcrumb links use CSS variable --color-brand; current segment uses --color-neutral-600
- aria-label='breadcrumb' is present on the nav element for accessibility
- Page title renders at 24px font-size and font-weight 600 per UX-11
**Depends on:** 10.1-T12

### 10.1-T18 — Build global toast notification service and modal base component  _(50 min)_
**Context:** UX-11 §2.10: toasts appear top-right, 16px from viewport edge, auto-dismiss 5s (success/info) or persistent until dismissed (error). Max 3 simultaneous, stacked. Variants: success (green left border), error (red), warning (amber), info (blue). Modals: max-width 560px (confirm) or 720px (review/detail). Overlay: 50% black scrim. Click-outside closes non-destructive modals. Always has X close button. Focus trap inside open modal. These are used globally by all admin modules.
**Steps:** Create ToastContext and useToast() hook; ToastProvider renders a fixed top-right container; Each toast: 16px left border in variant colour, close icon (X), auto-dismiss timer (cancelable on hover); queue max 3 (oldest dropped when 4th added); Create Modal base component accepting {isOpen, onClose, title, size: confirm|review, children}; renders portal into document.body; Modal: --color-neutral-50 overlay 50% opacity; focus trap (tab cycles through focusable children and close button only); ESC key closes non-destructive modals; Write unit tests: adding 4 toasts dismisses the oldest; success toast disappears after 5s; error toast persists; modal focuses first focusable element on open
**Deliverable:** ToastContext, useToast hook, Toast.tsx, Modal.tsx base components with tests
**Acceptance / logic checks:**
- Adding a 4th toast removes the first (oldest) from the stack; at most 3 visible
- Success toast starts a 5-second countdown and disappears; error toast does not auto-dismiss
- Modal renders inside a portal (appended to document.body, not inside the component tree)
- Pressing ESC closes a confirm modal (non-destructive); pressing ESC does not close a danger modal (destructive)
- Tab key focus cycles only within the open modal (focus trap active)
**Depends on:** 10.1-T12

### 10.1-T19 — Build empty, loading, and error states as reusable components  _(35 min)_
**Context:** UX-11 §2.9: empty state shows icon + 'No {entity} found' + 'Try adjusting your filters'. Loading state: skeleton shimmer (grey animated bars matching table row structure, min 200ms display). Error state: warning triangle icon + 'Could not load {entity}' + short error code + Retry button. These components are reused by all data-fetching pages (transactions, schemes, partners, etc.).
**Steps:** Create EmptyState.tsx accepting {icon, title, subtitle} props; apply UX-11 §2.9 layout (centred icon + text in a bordered box); Create SkeletonTable.tsx accepting {rows, cols} props; render animated shimmer rows using CSS animation (grey bars, 200ms minimum display enforced via useEffect timeout); Create ErrorState.tsx accepting {message, errorCode, onRetry} props; render warning icon, message, error code in monospace, and a Ghost variant Retry button; Export all three from a ui/states/index.ts barrel file; Write storybook stories or snapshot tests for each state component
**Deliverable:** EmptyState.tsx, SkeletonTable.tsx, ErrorState.tsx components with tests
**Acceptance / logic checks:**
- EmptyState renders subtitle 'Try adjusting your filters' when subtitle prop is omitted (default value)
- SkeletonTable with rows=5 cols=4 renders exactly 20 shimmer cells
- SkeletonTable enforces minimum 200ms display: even if data arrives in 50ms the skeleton is shown for at least 200ms
- ErrorState Retry button calls onRetry prop when clicked
- ErrorState displays errorCode in monospace font (JetBrains Mono / Fira Code stack)
**Depends on:** 10.1-T11

### 10.1-T20 — Implement audit log write service (backend): writeAuditEvent()  _(50 min)_
**Context:** PRD-07 §13.3: every config change, auth event, and admin action writes an audit log entry. hub_audit_log table (see DAT-03 §9 for schema cross-reference): id UUID PK, occurred_at TIMESTAMPTZ (ms precision), actor_user_id UUID FK, actor_display_name TEXT, actor_ip INET, event_type VARCHAR(60), entity_type VARCHAR(40), entity_id UUID, previous_value JSONB nullable, new_value JSONB nullable, description TEXT, metadata JSONB nullable. No UPDATE or DELETE permitted (enforced by DB-level policy or application constraint). Retained >= 7 years. writeAuditEvent() is called transactionally alongside the triggering write operation.
**Steps:** Create migration V10_1_007: hub_audit_log with all columns; add REVOKE UPDATE, DELETE ON hub_audit_log FROM application_role (DB-level immutability); Implement AuditService.writeAuditEvent({actor, eventType, entityType, entityId, previousValue, newValue, description, metadata}) as an async method that INSERTs into hub_audit_log; Ensure writeAuditEvent is called within the same DB transaction as the triggering change; if the outer transaction rolls back the audit entry also rolls back; Implement GET /admin/audit-log with filters: date_from, date_to, actor_user_id, entity_type, entity_id; paginated; requires VIEW_AUDIT_LOG permission; Write unit test: simulate a rule update transaction rollback; verify no audit entry is written
**Deliverable:** AuditService.writeAuditEvent(), migration V10_1_007, GET /admin/audit-log endpoint
**Acceptance / logic checks:**
- Attempting UPDATE on hub_audit_log from the application role raises a permission error
- writeAuditEvent called inside a rolled-back transaction leaves no row in hub_audit_log
- GET /admin/audit-log filtered by entity_id returns only entries for that entity in chronological order
- OPS_OPERATOR calling GET /admin/audit-log returns 200 (VIEW_AUDIT_LOG=true); ADMIN_VIEWER returns 403
- Audit entry occurred_at has millisecond-level precision (not truncated to seconds)
**Depends on:** 10.1-T01, 10.1-T03

### 10.1-T21 — Wire auth events to audit log (login, logout, MFA, account management)  _(45 min)_
**Context:** PRD-07 §13.2 and §12.5: all auth events must appear in the audit log. Event types to wire: LOGIN_SUCCESS (entity_type=hub_user, entity_id=user_id, metadata includes ip and user_agent), LOGIN_FAILED (actor_user_id null for unauthenticated attempts, metadata includes attempted email), ACCOUNT_LOCKED, LOGOUT, SESSION_TIMEOUT (written when Redis TTL expires and user next hits an endpoint), MFA_ENROLLED, PASSWORD_CHANGED, PASSWORD_RESET_FORCED, USER_CREATED, USER_UPDATED, ACCOUNT_UNLOCKED. All calls go through AuditService.writeAuditEvent from 10.1-T20.
**Steps:** Update POST /admin/auth/login to call AuditService.writeAuditEvent for LOGIN_SUCCESS and LOGIN_FAILED (use a separate non-transactional write for LOGIN_FAILED since no transaction context); Update JwtMiddleware to detect SESSION_EXPIRED Redis key miss and write SESSION_TIMEOUT event before returning 401; Update POST /admin/auth/logout, POST /admin/auth/change-password, POST /admin/auth/mfa/verify-enrollment, POST /admin/users endpoints to call writeAuditEvent with appropriate event types; For LOGIN_FAILED: actor_user_id=null; store attempted email in metadata.attempted_email; include ip_address from X-Forwarded-For; Write integration test: perform a full login+logout sequence and assert all expected audit events are present in hub_audit_log
**Deliverable:** All auth and user-management flows writing to hub_audit_log, verified by integration tests
**Acceptance / logic checks:**
- LOGIN_FAILED event has actor_user_id=null and metadata.attempted_email equal to the submitted email
- LOGIN_SUCCESS event has non-null actor_ip from X-Forwarded-For
- Logout writes LOGOUT event with matching session_id to the login session_id
- USER_CREATED event written when SUPER_ADMIN creates a new user, with new_value containing {email, role_code} (no password hash)
- SESSION_TIMEOUT written when Redis key is absent; actor_user_id=decoded JWT sub, session_id from JWT claim
**Depends on:** 10.1-T20, 10.1-T06, 10.1-T08, 10.1-T09, 10.1-T10

### 10.1-T22 — Unit tests: login lockout, session expiry, and MFA enforcement edge cases  _(55 min)_
**Context:** This is a dedicated test ticket for edge cases in the auth and session logic. Target: backend unit and integration tests (not UI). Uses the actual service layer and in-memory DB or testcontainers. Edge cases to cover: (1) exactly 5 failed logins locks the account; (2) 4 failures do not lock; (3) successful login resets failure counter; (4) JWT idle timeout at exactly 30 minutes; (5) JWT absolute expiry at exactly 8 hours; (6) SUPER_ADMIN with MFA disabled cannot log in; (7) FINANCE_ANALYST with MFA disabled can log in; (8) TOTP code valid in previous 30s window (RFC 6238 allows 1 window drift); (9) recovery code used twice returns error on second use.
**Steps:** Set up test harness with in-memory or containerised DB seeded with hub_role data from 10.1-T02; Write test: 4 failed logins -> 5th fails (not locked) -> 5th failure IS the lock trigger (exactly 5); assert is_active=false after 5th, still true after 4th; Write test: successful login after 4 failures resets counter to 0; subsequent 5-failure sequence locks; Write test: mock clock at login+30min-1s; request succeeds; mock clock at login+30min+1s; next request returns SESSION_EXPIRED; Write test for MFA role enforcement: SUPER_ADMIN without MFA returns 403; FINANCE_ANALYST without MFA returns 200 with session JWT
**Deliverable:** Test file covering 9 named edge-case scenarios, all passing
**Acceptance / logic checks:**
- Test 1 (lockout): is_active=false after exactly the 5th consecutive failure, not before
- Test 3 (reset on success): after successful login, failure counter=0 (5 new failures required to re-lock)
- Test 5 (absolute JWT expiry): request at t=8h+1s returns 401 TOKEN_EXPIRED even if session Redis key is still present
- Test 6 (SUPER_ADMIN no MFA): POST /admin/auth/login returns 403 MFA_ENROLLMENT_REQUIRED (not 200)
- Test 9 (recovery code reuse): second use of same recovery code returns 401 RECOVERY_CODE_USED
**Depends on:** 10.1-T05, 10.1-T06, 10.1-T08

### 10.1-T23 — Change-password page UI and post-login redirect logic  _(45 min)_
**Context:** When the backend returns must_change=true in the login response, the admin-ui must redirect to /change-password before any other page. POST /admin/auth/change-password (10.1-T07 backend). UI fields: current_password, new_password (show/hide toggles), confirm_new_password (client-side match check). Password policy reminder shown inline: min 12 chars, uppercase, digit, special char. On success: reissue JWT stored in localStorage, navigate to /dashboard. Error handling: 401 INVALID_CURRENT_PASSWORD (inline on current_password field), 422 PASSWORD_RECENTLY_USED (inline on new_password field), 422 PASSWORD_POLICY_VIOLATION (inline with specific rule that failed).
**Steps:** Create /change-password route; PrivateRoute allows access even with must_change=true; Render three password fields with show/hide eye icons; client-side validation: new_password == confirm_new_password before submitting (show 'Passwords do not match' inline if unequal); Display password policy checklist (live feedback as user types: checkmark green when rule satisfied, X red when not): length >=12, has uppercase, has digit, has special char; On submit POST /admin/auth/change-password; on 200 update localStorage with new JWT, navigate to /dashboard with success toast 'Password updated successfully'; On error map API error codes to inline field errors as specified in context
**Deliverable:** ChangePasswordPage.tsx with live policy checklist and all error states handled
**Acceptance / logic checks:**
- Submitting with new_password != confirm_new_password shows 'Passwords do not match' and does not call API
- Live policy checklist shows all 4 rules: length, uppercase, digit, special char; each updates in real-time as user types
- 422 PASSWORD_RECENTLY_USED shows inline error on new_password field: 'You have recently used this password'
- On success, new JWT is stored in localStorage (different value from the one stored at login) and user reaches /dashboard
- Navigating to any other route while must_change=true redirects back to /change-password (PrivateRoute guard)
**Depends on:** 10.1-T16, 10.1-T07

### 10.1-T24 — E2E smoke test: full login-to-dashboard flow (local account + TOTP)  _(55 min)_
**Context:** This is an automated E2E test (Playwright or Cypress) covering the critical path from unauthenticated to authenticated dashboard state, including MFA. Tests run against a local Docker Compose stack (backend + DB + Redis). Covers: (1) visit /dashboard unauthenticated -> redirect to /login; (2) login with valid email+password; (3) MFA TOTP challenge (use a test TOTP secret deterministic at T=0); (4) arrive at /dashboard; (5) assert sidebar nav items visible; (6) logout and verify redirect to /login.
**Steps:** Set up Playwright config pointing to localhost:5173 (admin-ui dev server) with backend on localhost:8080; Seed DB with a SUPER_ADMIN test user, known TOTP secret (base32 JBSWY3DPEHPK3PXP for deterministic codes), must_change=false; Write test 1: navigate to /dashboard -> assert redirect to /login; Write test 2-4: fill email+password -> submit -> TOTP challenge -> enter code from otplib.authenticator.generate(secret) -> assert /dashboard loaded; Write test 5-6: assert sidebar contains 'Dashboard', 'Schemes', 'Users & Roles'; click Logout -> assert /login
**Deliverable:** Playwright E2E test file (e2e/admin-auth.spec.ts) with 6 assertions, all passing in CI
**Acceptance / logic checks:**
- Test runs in CI without flakiness (3 consecutive passing runs)
- Unauthenticated /dashboard visit is redirected to /login (HTTP 302 or client navigation)
- Post-TOTP login lands on /dashboard with page title 'Dashboard' visible
- Sidebar shows 'Users & Roles' link (SUPER_ADMIN role)
- Logout click results in /login page with empty credential fields
**Depends on:** 10.1-T15, 10.1-T16, 10.1-T23


## WBS 10.2 — Scheme management screens
### 10.2-T01 — DB migration: qr_scheme and scheme_country tables  _(30 min)_
**Context:** GMEPay+ stores each QR Payment Scheme as a row in qr_scheme (no code change to add a scheme). Key columns: id BIGINT PK, scheme_code VARCHAR(30) UNIQUE (e.g. ZEROPAY), name VARCHAR(100), payout_ccy CHAR(3), supported_modes VARCHAR(20) (MPM/CPM/BOTH), settlement_counterparty VARCHAR(120), settlement_ccy CHAR(3), settlement_model VARCHAR(10) (NET/GROSS), gme_fee_share_pct DECIMAL(6,4), partner_b_quote_enabled BOOLEAN, partner_b_quote_deviation_pct DECIMAL(6,4) default 0.0100, sftp_host VARCHAR(255) encrypted, sftp_port INT default 22, sftp_username VARCHAR(120) encrypted, sftp_credential_ref VARCHAR(120), sftp_path_inbound VARCHAR(255), sftp_path_outbound VARCHAR(255), api_base_url VARCHAR(512), api_credential_ref VARCHAR(120), integration_type VARCHAR(20) (SFTP/REST/ISO8583), logo_url VARCHAR(512), morning_settlement_cutoff TIME, afternoon_settlement_cutoff TIME, detail_file_cutoff TIME, report_due_day SMALLINT, refund_window_days INT default 90, status VARCHAR(20) default DRAFT, created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ, created_by BIGINT FK hub_user, updated_by BIGINT FK hub_user. Table scheme_country: id BIGINT PK, scheme_id FK, country_code CHAR(2), is_active BOOLEAN, UNIQUE(scheme_id, country_code). Table scheme_merchant_fee: id BIGINT PK, scheme_id FK, merchant_type VARCHAR(60), domestic_rate DECIMAL(6,4), cross_border_rate DECIMAL(6,4), UNIQUE(scheme_id, merchant_type).
**Steps:** Create Flyway/Liquibase migration file V10_2_001__scheme_tables.sql; Add qr_scheme table with all columns listed in context; sensitive columns (sftp_host, sftp_username, sftp_credential_ref, api_credential_ref) are nullable varchars - encryption is applied at service layer; Add scheme_country table with UNIQUE(scheme_id, country_code) and FK to qr_scheme; Add scheme_merchant_fee table with UNIQUE(scheme_id, merchant_type) and FK to qr_scheme; Add indexes: qr_scheme(scheme_code), qr_scheme(status), scheme_country(scheme_id), scheme_merchant_fee(scheme_id); Run migration against local test DB and confirm schema matches spec
**Deliverable:** Migration file V10_2_001__scheme_tables.sql applied cleanly with all three tables and indexes present
**Acceptance / logic checks:**
- INSERT into qr_scheme with scheme_code=ZEROPAY succeeds; duplicate scheme_code rejected with unique constraint
- INSERT into scheme_country (scheme_id=1, country_code=KR) twice raises unique violation
- scheme_merchant_fee row with domestic_rate=0.0080 and cross_border_rate=0.0170 stores and retrieves with 4 decimal precision
- Dropping scheme_country row when referenced by active payment does NOT cascade delete qr_scheme (FK is scheme_country.scheme_id -> qr_scheme.id only)
- Migration rolls back cleanly via DOWN script (DROP TABLE in reverse order)

### 10.2-T02 — Backend: SchemeRepository CRUD and status-lifecycle transitions  _(45 min)_
**Context:** The qr_scheme table (see 10.2-T01) has status VARCHAR(20) with lifecycle: DRAFT -> TESTING -> ACTIVE -> SUSPENDED. Only DRAFT and TESTING statuses allow unrestricted field edits; ACTIVE and SUSPENDED are restricted (connectivity and fee fields can still be edited by SUPER_ADMIN). Activation requires: integration_type set, sftp_host/api_base_url set (per type), >= 1 active scheme_country row, payout_ccy non-null, >= 1 scheme_merchant_fee row. No UPDATE or DELETE on audit columns; updated_by/updated_at set on every write. Use Spring Data JPA or plain JPA repository.
**Steps:** Create SchemeRepository (JPA) with findBySchemeCode, findAllByStatus, existsBySchemeCode methods; Create SchemeService with createScheme, updateScheme, getScheme, listSchemes methods; Implement SchemeService.transitionStatus(schemeId, targetStatus, actorId): validates allowed transitions (DRAFT->TESTING, TESTING->ACTIVE, ACTIVE->SUSPENDED, SUSPENDED->ACTIVE); for ACTIVE transition, runs activation prerequisite checks; throws SchemeTransitionException with reason on violation; Implement activation prerequisite check method returning list of unmet conditions; Add @Transactional on createScheme and updateScheme; set updated_by=actorId, updated_at=now() on every save; Write unit tests for transitionStatus covering each valid and invalid transition
**Deliverable:** SchemeRepository + SchemeService classes with status transition logic and activation prerequisite validation
**Acceptance / logic checks:**
- transitionStatus(DRAFT -> ACTIVE) throws SchemeTransitionException listing unmet prerequisites when fee table is empty
- transitionStatus(TESTING -> ACTIVE) with all prerequisites met sets status=ACTIVE and persists
- transitionStatus(ACTIVE -> DRAFT) throws SchemeTransitionException (invalid transition)
- transitionStatus(ACTIVE -> SUSPENDED) succeeds and sets status=SUSPENDED
- createScheme sets status=DRAFT, created_at=now, created_by=actorId on first persist
**Depends on:** 10.2-T01

### 10.2-T03 — Backend: SchemeConnectivityService - SFTP test and credential encryption  _(50 min)_
**Context:** Scheme connectivity config (sftp_host, sftp_port, sftp_username, sftp_credential_ref, sftp_path_inbound, sftp_path_outbound, api_base_url, api_credential_ref) must be stored encrypted at rest (AES-256). Plaintext is never logged. A Test Connection action must attempt an SFTP connection (JSch library) or HTTP GET to api_base_url and return success/failure within 10 seconds timeout. integration_type determines which test to run: SFTP tries sftp.stat on inbound path; REST tries HTTP GET on api_base_url with auth header. Credentials are stored in DB column api_credential_ref / sftp_credential_ref as AES-256 encrypted blobs (key from application secrets / Vault). The Connectivity test result is stored as last_connectivity_test_at, last_connectivity_test_result (SUCCESS/FAILURE/TIMEOUT), last_connectivity_test_message on qr_scheme.
**Steps:** Add columns last_connectivity_test_at, last_connectivity_test_result VARCHAR(10), last_connectivity_test_message VARCHAR(255) to qr_scheme via migration V10_2_002; Create CredentialEncryptionService with encrypt(plaintext) and decrypt(ciphertext) using AES-256-GCM; key injected from environment/Vault; never log plaintext; Create SchemeConnectivityService.testConnection(schemeId): decrypt credentials, attempt SFTP stat or HTTP GET per integration_type, enforce 10s timeout, persist test result columns, return TestConnectionResult(success, message); Ensure decrypt errors surface as SchemeConfigException (not null pointer); sensitive fields are never included in exception messages or logs; Write unit tests for CredentialEncryptionService round-trip and for testConnection with mocked SFTP/HTTP clients
**Deliverable:** Migration V10_2_002, CredentialEncryptionService, SchemeConnectivityService with test-connection logic
**Acceptance / logic checks:**
- encrypt then decrypt returns identical plaintext for a 64-char password
- testConnection with mocked SFTP returning connection refused stores last_connectivity_test_result=FAILURE and message contains host:port
- testConnection with mocked SFTP success stores last_connectivity_test_result=SUCCESS
- Plaintext credential does not appear in any log output at INFO or DEBUG level during test
- testConnection times out after 10s and stores TIMEOUT result when SFTP server hangs
**Depends on:** 10.2-T01

### 10.2-T04 — Backend: REST API - GET /admin/schemes and GET /admin/schemes/{id}  _(40 min)_
**Context:** The Admin System frontend fetches scheme list and detail from backend REST endpoints secured by the portal's RBAC (role OPS_OPERATOR, OPS_ADMIN, FINANCE_ANALYST, SUPER_ADMIN may view scheme config; ADMIN_VIEWER may not). Roles are stored in hub_role table with role_code. Scheme list returns: id, scheme_code, name, payout_ccy, supported_modes, supported_countries (array of country_code), status, last_connectivity_test_result, updated_at. Scheme detail additionally returns all fields except plaintext credentials (sftp_credential_ref and api_credential_ref return masked string '***'). Pagination: page and size query params; default size=20. Filter params: status (optional multi-value).
**Steps:** Create SchemeController with GET /admin/schemes (paginated, filterable by status) and GET /admin/schemes/{id}; Add @PreAuthorize or role-check ensuring only OPS_OPERATOR/OPS_ADMIN/FINANCE_ANALYST/SUPER_ADMIN may call; ADMIN_VIEWER gets 403; Map qr_scheme + scheme_country to SchemeListDto and SchemeDetailDto; mask sftp_credential_ref and api_credential_ref as '***' in response; Include supported_countries as string[] in both DTOs derived from scheme_country table; Write integration test: seed one ZEROPAY scheme; GET /admin/schemes returns it; GET /admin/schemes/1 returns full detail with credentials masked; Write test: call with ADMIN_VIEWER token -> 403
**Deliverable:** SchemeController with GET /admin/schemes and GET /admin/schemes/{id}, SchemeListDto, SchemeDetailDto
**Acceptance / logic checks:**
- GET /admin/schemes with no filters returns paginated list; total reflects DB count
- GET /admin/schemes?status=ACTIVE returns only ACTIVE records
- GET /admin/schemes/{id} response does NOT contain plaintext sftp_credential value; field shows '***'
- GET /admin/schemes/{id} for non-existent id returns 404 with error body
- Call with ADMIN_VIEWER JWT returns HTTP 403
**Depends on:** 10.2-T02

### 10.2-T05 — Backend: REST API - POST /admin/schemes (create scheme)  _(50 min)_
**Context:** OPS_OPERATOR and OPS_ADMIN (not FINANCE_ANALYST) may create a new scheme. Body includes Identity Section fields: scheme_code (uppercase alphanumeric, max 20, unique), name (max 100, unique), logo_url (optional HTTPS URL), supported_modes (MPM/CPM/BOTH), payout_ccy (must exist in currency registry), status defaults to DRAFT. Connectivity Section fields: integration_type (SFTP/REST/ISO8583), sftp_host, sftp_port (default 22), sftp_username, sftp_password (plaintext in request body - encrypted at rest by service), sftp_path_inbound, sftp_path_outbound, api_base_url, api_key, api_secret (plaintext in request). Settlement Terms: settlement_counterparty, settlement_ccy, settlement_model (NET/GROSS), gme_fee_share_pct (0-100), morning_settlement_cutoff (TIME KST), afternoon_settlement_cutoff, detail_file_cutoff, report_due_day (1-28 or 0 for EOM). Supported countries NOT part of create body - added separately. On success, audit log entry with event=scheme.created, actor_user_id, entity_id, new_value=serialized scheme.
**Steps:** Create SchemeCreateRequest DTO with JSR-303 validation: scheme_code @Pattern([A-Z0-9]{1,20}), name @Size(max=100), logo_url @Pattern(https://.*) if present, payout_ccy existence checked in service; Implement POST /admin/schemes in SchemeController; @PreAuthorize OPS_OPERATOR or OPS_ADMIN; In SchemeService.createScheme: encrypt SFTP/API credentials before persisting, set status=DRAFT, set created_by=actorId; After persist, write AuditLogEntry(event=scheme.created, entity_type=scheme, entity_id=newId, new_value=JSON of scheme, actor_user_id, timestamp=now); Return 201 with SchemeDetailDto (credentials masked); Write tests: valid payload creates record; duplicate scheme_code returns 409; invalid logo_url (HTTP) returns 400
**Deliverable:** POST /admin/schemes endpoint with validation, encryption, and audit log write on creation
**Acceptance / logic checks:**
- POST with scheme_code=ZEROPAY, valid fields returns 201 and response body contains id, status=DRAFT
- POST with duplicate scheme_code=ZEROPAY returns 409 Conflict
- POST with logo_url=http://... (not HTTPS) returns 400 with field error on logo_url
- AuditLog table contains one row with event=scheme.created and entity_id matching returned id
- SFTP password in DB column sftp_credential_ref is not stored in plaintext (decrypt and compare to verify encryption applied)
**Depends on:** 10.2-T03, 10.2-T04

### 10.2-T06 — Backend: REST API - PUT /admin/schemes/{id} (update scheme)  _(45 min)_
**Context:** OPS_OPERATOR and OPS_ADMIN may edit a scheme. Edits are allowed when status=DRAFT or TESTING with no field restrictions. When status=ACTIVE or SUSPENDED, only connectivity fields and settlement terms are editable (scheme_code and name are immutable after ACTIVE). Audit log must record previous_value and new_value for every changed field individually (field-level granularity, event=scheme.field_changed). All changes apply only to new transactions; committed transactions retain locked values. The response is SchemeDetailDto with credentials masked.
**Steps:** Create SchemeUpdateRequest DTO; scheme_code field is ignored (immutable) when status=ACTIVE; Implement PUT /admin/schemes/{id} in SchemeController with @PreAuthorize OPS_OPERATOR or OPS_ADMIN; In SchemeService.updateScheme: load current state, compare field-by-field, for each changed field write AuditLogEntry with event=scheme.field_changed, field, previous_value, new_value; Enforce immutability guard: if status=ACTIVE and attempt to change scheme_code or name, return 422 with message; Re-encrypt credentials if they are included in the body (non-null credential fields trigger re-encryption); Write tests: update name on DRAFT scheme audits correctly; attempt to change scheme_code on ACTIVE scheme returns 422
**Deliverable:** PUT /admin/schemes/{id} endpoint with field-level audit logging and ACTIVE immutability guard
**Acceptance / logic checks:**
- Updating name from ZeroPay to ZeroPay v2 on a DRAFT scheme creates one AuditLogEntry with field=name, previous_value=ZeroPay, new_value=ZeroPay v2
- Updating scheme_code on an ACTIVE scheme returns 422 and no audit entry is created
- PUT with a changed sftp_host re-encrypts and persists the new value (decrypt to verify)
- PUT with no changed fields (same values) creates zero AuditLogEntry rows
- updated_at and updated_by are set to current time and actor on every PUT
**Depends on:** 10.2-T05

### 10.2-T07 — Backend: REST API - PATCH /admin/schemes/{id}/status (activate/suspend)  _(35 min)_
**Context:** Status transitions are: DRAFT->TESTING->ACTIVE->SUSPENDED and SUSPENDED->ACTIVE. Only OPS_OPERATOR and OPS_ADMIN may change status. Activating requires all prerequisites: integration_type set, connectivity credentials set (sftp_host for SFTP, api_base_url for REST), >= 1 active scheme_country, payout_ccy non-null, >= 1 scheme_merchant_fee row. If prerequisites not met, return 422 with array of unmet conditions. A confirmation step is represented by a required body field confirmed=true; if false or absent, return 400. Audit log entry: event=scheme.status_changed, previous_value=old status, new_value=new status.
**Steps:** Create SchemeStatusChangeRequest {targetStatus: enum, confirmed: boolean}; Implement PATCH /admin/schemes/{id}/status in SchemeController; Delegate to SchemeService.transitionStatus from 10.2-T02; catch SchemeTransitionException and map to 422 response with unmet conditions array; If confirmed=false return 400 {error: confirmation required}; Write AuditLogEntry(event=scheme.status_changed, previous_value, new_value) on success; Write tests: activate with all prerequisites met and confirmed=true succeeds; activate with missing merchant fee returns 422 listing that condition; suspend an ACTIVE scheme succeeds
**Deliverable:** PATCH /admin/schemes/{id}/status endpoint with prerequisite validation and confirmation guard
**Acceptance / logic checks:**
- PATCH with targetStatus=ACTIVE, confirmed=true, all prerequisites met returns 200 and status=ACTIVE
- PATCH with targetStatus=ACTIVE, confirmed=true but scheme has 0 scheme_country rows returns 422 with unmet_conditions containing supported_countries
- PATCH with confirmed=false returns 400
- PATCH with targetStatus=DRAFT on an ACTIVE scheme returns 422 (invalid transition)
- AuditLogEntry with event=scheme.status_changed is written exactly once on success
**Depends on:** 10.2-T02, 10.2-T06

### 10.2-T08 — Backend: REST API - scheme_country endpoints (add/remove countries)  _(35 min)_
**Context:** The scheme_country table maps a scheme to ISO 3166-1 alpha-2 country codes. These are managed separately from the main scheme form. Endpoints: POST /admin/schemes/{id}/countries body {country_code: KR}, DELETE /admin/schemes/{id}/countries/{countryCode}. OPS_OPERATOR and OPS_ADMIN only. Removing the last country from an ACTIVE scheme is blocked (status must not be ACTIVE if the result would leave 0 countries). Audit log: event=scheme.country_added or scheme.country_removed.
**Steps:** Create SchemeCountryController with POST /admin/schemes/{id}/countries and DELETE /admin/schemes/{id}/countries/{countryCode}; Validate country_code is a valid ISO 3166-1 alpha-2 code (use Java Locale or static set); For DELETE: if scheme status=ACTIVE and this is the last country, return 422 {error: cannot remove last country from active scheme}; Write audit log entry on each add/remove; Return updated supported_countries array in 200 response; Write tests: add KR, then JP; list shows both; delete KR; list shows JP; delete JP on ACTIVE scheme returns 422
**Deliverable:** SchemeCountryController with add/delete endpoints, last-country guard for ACTIVE schemes, and audit entries
**Acceptance / logic checks:**
- POST /admin/schemes/1/countries {country_code:KR} inserts row and returns supported_countries=[KR]
- Duplicate POST /admin/schemes/1/countries {country_code:KR} returns 409
- DELETE /admin/schemes/1/countries/KR when scheme is ACTIVE and KR is the only country returns 422
- DELETE succeeds when scheme has 2 countries; remaining country still present
- Invalid country_code=XX returns 400
**Depends on:** 10.2-T06

### 10.2-T09 — Backend: REST API - scheme_merchant_fee endpoints (CRUD fee table)  _(35 min)_
**Context:** The scheme_merchant_fee table (scheme_id FK, merchant_type VARCHAR(60), domestic_rate DECIMAL(6,4), cross_border_rate DECIMAL(6,4)) stores fee rates per merchant type. ZeroPay reference values: General=0.0080/0.0170. merchant_type codes must match ZeroPay batch file codes (ZP0041/ZP0051) - stored as free text until confirmed with KFTC (Assumption A-06). Endpoints: GET /admin/schemes/{id}/merchant-fees, POST /admin/schemes/{id}/merchant-fees, PUT /admin/schemes/{id}/merchant-fees/{feeId}, DELETE /admin/schemes/{id}/merchant-fees/{feeId}. OPS_OPERATOR and OPS_ADMIN only. Audit log on every write: event=scheme.fee_row_added/updated/deleted.
**Steps:** Create SchemeFeeCRUDController with GET/POST/PUT/DELETE for /admin/schemes/{id}/merchant-fees; Validate domestic_rate and cross_border_rate in [0.0000, 1.0000]; max 4 decimal places; Enforce UNIQUE(scheme_id, merchant_type) - duplicate merchant_type returns 409; Write audit log entry on POST, PUT, DELETE; Return full fee table list in every mutating response; Write tests: create General fee, update domestic_rate, delete it; duplicate merchant_type rejected; invalid rate=1.5 rejected
**Deliverable:** SchemeFeeCRUDController with full CRUD, validation, and audit logging
**Acceptance / logic checks:**
- POST with merchant_type=General, domestic_rate=0.0080, cross_border_rate=0.0170 returns 201 and persists with 4 decimal precision
- PUT updating domestic_rate to 0.0090 creates AuditLogEntry with field=domestic_rate, previous_value=0.0080, new_value=0.0090
- POST with duplicate merchant_type=General returns 409
- POST with domestic_rate=1.5 returns 400 (rate must be <= 1.0)
- GET /admin/schemes/{id}/merchant-fees returns all fee rows for that scheme
**Depends on:** 10.2-T06

### 10.2-T10 — Backend: POST /admin/schemes/{id}/test-connection endpoint  _(30 min)_
**Context:** A Test Connection button in the UI calls this endpoint. It invokes SchemeConnectivityService.testConnection (10.2-T03) for the given scheme. Result is returned immediately to the caller (synchronous, max 10s timeout). The endpoint persists last_connectivity_test_at, last_connectivity_test_result (SUCCESS/FAILURE/TIMEOUT), last_connectivity_test_message on qr_scheme. Returns 200 with {result: SUCCESS/FAILURE/TIMEOUT, message: ..., tested_at: ISO8601}. OPS_OPERATOR and OPS_ADMIN only. Audit log: event=scheme.connectivity_tested, result.
**Steps:** Add POST /admin/schemes/{id}/test-connection to SchemeController; Call SchemeConnectivityService.testConnection(schemeId) with 10s HTTP/SFTP timeout; Persist test result columns via SchemeRepository.updateConnectivityTestResult(schemeId, result, message, testedAt); Write AuditLogEntry(event=scheme.connectivity_tested, entity_id=schemeId, new_value={result,message}); Return 200 {result, message, tested_at} regardless of connection outcome (connection failure is not an HTTP error); Write tests: mocked SFTP success -> result=SUCCESS; mocked timeout -> result=TIMEOUT; scheme not found -> 404
**Deliverable:** POST /admin/schemes/{id}/test-connection endpoint wired to SchemeConnectivityService
**Acceptance / logic checks:**
- Call on a scheme with mocked SFTP success returns 200 {result:SUCCESS}
- Call on a scheme with mocked SFTP failure returns 200 {result:FAILURE, message contains reason}
- last_connectivity_test_result and last_connectivity_test_at columns updated after each call
- AuditLogEntry with event=scheme.connectivity_tested written on every call
- Call on non-existent scheme_id returns 404
**Depends on:** 10.2-T03, 10.2-T06

### 10.2-T11 — Frontend: Scheme List screen component  _(45 min)_
**Context:** The Schemes module in the Admin portal (left-nav item Schemes) renders a table with columns: Scheme Name, Scheme Code, Payout Currency, Supported Countries (comma-separated codes), Supported Modes, Status badge (Draft=grey/Testing=blue/Active=green/Suspended=red), Last Updated. Actions per row: View, Edit, Activate/Suspend (toggle based on status), View Rules. Data fetched from GET /admin/schemes (paginated, size=20). Filter by Status via dropdown (multi-select). RBAC: render Edit and Activate/Suspend buttons only if user role is OPS_OPERATOR or OPS_ADMIN; hide from FINANCE_ANALYST. No horizontal scrolling - table max 6 visible columns (Supported Countries shown as count with tooltip if > 3).
**Steps:** Create SchemeListPage component (React or Vue per project stack) that fetches GET /admin/schemes on mount with page and status filter params; Render table with columns: Name, Code, Payout Ccy, Countries (count badge + tooltip), Modes, Status badge, Last Updated, Actions; Implement status color badges: DRAFT=grey, TESTING=blue, ACTIVE=green, SUSPENDED=amber/red; Show Edit and Activate/Suspend action buttons only when user role is OPS_OPERATOR or OPS_ADMIN; Implement Status filter dropdown (multi-select) that refetches data on change; Add pagination controls (previous/next, page indicator); Add New Scheme button linking to create form (visible to OPS_OPERATOR/OPS_ADMIN)
**Deliverable:** SchemeListPage component rendering scheme table with filter, pagination, RBAC-gated actions
**Acceptance / logic checks:**
- Rendering with 3 ACTIVE and 1 DRAFT scheme shows 4 rows; DRAFT row has grey badge, ACTIVE rows have green badge
- Selecting status filter=ACTIVE fetches GET /admin/schemes?status=ACTIVE and re-renders with filtered rows
- FINANCE_ANALYST user sees no Edit or Activate/Suspend buttons in actions column
- Country column with [KR, JP, SG, US, TH] shows count badge 5 with tooltip listing all codes
- Pagination: with 25 schemes (size=20) shows page 1 of 2 and Next button
**Depends on:** 10.2-T04

### 10.2-T12 — Frontend: Scheme Create/Edit form - Identity section  _(45 min)_
**Context:** The Create/Edit Scheme form is organized into three sections rendered on a single page: Identity, Connectivity Configuration, and Settlement Terms. The Identity section (PRD-07 §4.3.1) contains: Scheme Name (text, required, max 100), Scheme Code (text, required, uppercase alphanumeric max 20, unique - validated on blur via API), Logo URL (optional, must be HTTPS if provided), Supported Countries (multi-select from ISO 3166-1 alpha-2 list), Supported Payment Modes (checkboxes: MPM, CPM - at least 1 required), Payout Currency (select from currency registry), Status (display-only on edit; select on create defaulting to DRAFT). On create: POST /admin/schemes. On edit: PUT /admin/schemes/{id} with full body.
**Steps:** Create SchemeFormPage component with mode prop (create/edit); on edit mode, fetch GET /admin/schemes/{id} to pre-populate fields; Implement Identity section with JSR-like client-side validation: scheme_code @pattern /^[A-Z0-9]{1,20}$/, name maxLength 100, logo_url must start with https:// if non-empty, >= 1 country selected, >= 1 mode checked; Add scheme_code uniqueness check on blur: call GET /admin/schemes?code={value} and display inline error if duplicate; Implement Supported Countries multi-select with search; show ISO code + country name in dropdown; Show Status as read-only display badge on edit; show select (defaulting to DRAFT) on create; Inline validation errors appear adjacent to fields without requiring Save click
**Deliverable:** SchemeFormPage Identity section with client-side validation, scheme_code uniqueness check, and country multi-select
**Acceptance / logic checks:**
- Entering scheme_code=zeropay auto-uppercases to ZEROPAY and validates pattern
- Entering scheme_code matching an existing scheme shows inline error: Scheme code already exists
- Logo URL http://example.com shows inline error: must be HTTPS
- Clearing all Supported Countries shows inline error: at least 1 country required
- On edit mode, Status field is non-editable and shows current status badge
**Depends on:** 10.2-T11

### 10.2-T13 — Frontend: Scheme Create/Edit form - Connectivity Configuration section  _(45 min)_
**Context:** The Connectivity section (PRD-07 §4.3.2) shows fields conditional on integration_type. SFTP fields: SFTP Host (required), SFTP Port (integer, default 22), SFTP Username (required), SFTP Password/Key (secret input, masked, required - send as plaintext in HTTPS request body to POST/PUT endpoint which encrypts at rest; on edit, show placeholder '***' and only overwrite if operator types a new value), SFTP Inbound Path, SFTP Outbound Path. REST fields: API Base URL (required, HTTPS), API Key (required), API Secret (masked, same masking rule as SFTP password). A Test Connection button calls POST /admin/schemes/{id}/test-connection (only available in edit mode after initial save). Shows result inline: green SUCCESS or red FAILURE/TIMEOUT with message.
**Steps:** Add Connectivity section to SchemeFormPage below Identity section; Conditionally render SFTP or REST fields based on integration_type select value; For password/secret fields: use type=password input; on edit mode pre-fill with placeholder '***'; only include in PUT body if the value differs from '***'; Implement Test Connection button (edit mode only): call POST /admin/schemes/{id}/test-connection, show spinner, then display inline result badge (SUCCESS green / FAILURE red / TIMEOUT amber) with message; Validate SFTP Host is non-empty when integration_type=SFTP; API Base URL is HTTPS when integration_type=REST; Write component unit tests for conditional field rendering and masked secret handling
**Deliverable:** Connectivity Configuration section with conditional fields, masked secrets, and inline Test Connection result
**Acceptance / logic checks:**
- Switching integration_type from SFTP to REST hides SFTP fields and shows REST fields
- Password field shows placeholder *** on edit mode; submitting without changing *** does NOT overwrite stored credential
- Test Connection button absent in create mode; present and clickable in edit mode
- Test Connection returning SUCCESS renders green badge; FAILURE renders red badge with message text
- SFTP Port defaults to 22 on create; clears to empty when integration_type switches to REST
**Depends on:** 10.2-T12, 10.2-T10

### 10.2-T14 — Frontend: Scheme Create/Edit form - Settlement Terms section and Merchant Fee Table  _(40 min)_
**Context:** Settlement Terms section (PRD-07 §4.3.3): Settlement Counterparty Name (text, required), Settlement Currency (ISO 4217 select, required), Settlement Model (NET/GROSS select, required), GME Fee Share % (decimal 0-100, required, e.g. 70 for ZeroPay), Morning Settlement Batch Cutoff (time KST, required for SFTP), Afternoon Settlement Batch Cutoff (time KST, optional), Detail File Cutoff (time KST, optional), Report Due Day (integer 1-28 or 0=EOM). Merchant Fee Table (PRD-07 §4.3.4): a structured table below the terms; each row has merchant_type (text), domestic_rate (%, 4 decimal places), cross_border_rate (%, 4 decimal places). Add/Remove row buttons. ZeroPay default row: General, 0.80%, 1.70%. On save, fee table rows are submitted to POST/PUT /admin/schemes/{id}/merchant-fees separately after the main form save.
**Steps:** Add Settlement Terms fields to SchemeFormPage with validation: gme_fee_share_pct in [0,100], morning_settlement_cutoff required if integration_type=SFTP, report_due_day in [0,28]; Add Merchant Fee Table component below Settlement Terms: array of rows {merchant_type, domestic_rate, cross_border_rate}; Add Row button appends empty row; Remove button on each row; Pre-populate merchant fee table from GET /admin/schemes/{id}/merchant-fees on edit mode; On form save: first save main scheme (POST or PUT), then synchronize fee table rows (POST new rows, PUT changed rows, DELETE removed rows) via scheme-fees endpoints; Validate each fee table row: domestic_rate and cross_border_rate in [0, 100]% (displayed as %, stored as decimal 0-1); Display conversion: UI shows 0.80% for stored value 0.0080
**Deliverable:** Settlement Terms section, Merchant Fee Table with add/remove rows, and fee table save logic
**Acceptance / logic checks:**
- Entering gme_fee_share_pct=110 shows inline error: must be between 0 and 100
- Adding a row with merchant_type=General, domestic_rate=0.80, cross_border_rate=1.70 saves and retrieves correctly as 0.0080/0.0170
- Removing a fee row marks it for deletion; on save it is deleted via DELETE endpoint
- Morning Settlement Batch Cutoff field shows required indicator when integration_type=SFTP
- Report Due Day=0 treated as EOM in display label
**Depends on:** 10.2-T13, 10.2-T09

### 10.2-T15 — Frontend: Scheme form - Review Changes diff, Save, and Activate/Suspend flow  _(40 min)_
**Context:** PRD-07 §4.3.5 and §14.2: before saving an existing scheme, a Review Changes diff dialog shows previous vs new values per field. The Save button is labeled Save Changes for edit, Create Scheme for create. Activating a scheme (status transition to ACTIVE) requires a confirmation dialog stating the prerequisites and requiring explicit confirm. Activation prerequisites: connectivity test passed (last_connectivity_test_result=SUCCESS), >= 1 supported country, payout_ccy set, >= 1 merchant fee row. If any unmet, dialog lists them and disables Confirm. The Activate/Suspend status change calls PATCH /admin/schemes/{id}/status. The list and form pages must refresh after status change. Audit trail link: a View Audit History tab/link opens the scheme audit log view (see 10.2-T18).
**Steps:** Implement Review Changes modal: on edit form submit, compute changed fields, show table with Field / Previous / New columns, Confirm Save and Cancel buttons; Wire Confirm Save to PUT /admin/schemes/{id}; on success close modal and navigate to scheme detail or list; Implement Activate button on scheme detail/edit page: fetch prerequisites status, open confirmation dialog listing prerequisites with green/red indicators, enable Confirm only when all prerequisites met; Wire Confirm Activate to PATCH /admin/schemes/{id}/status {targetStatus:ACTIVE, confirmed:true}; Implement Suspend confirmation dialog: shows current status and target; requires confirmation; Refresh scheme data in list and form after any status change
**Deliverable:** Review Changes diff modal, Save flow, Activate confirmation dialog with prerequisite checklist, Suspend confirmation dialog
**Acceptance / logic checks:**
- Editing only name field shows diff table with 1 row: field=Scheme Name, Previous=old name, New=new name; unchanged fields absent
- Activate dialog shows all 4 prerequisites; a scheme missing merchant fees shows that item in red and Confirm disabled
- Activate dialog with all prerequisites met shows all items green; Confirm enabled; click triggers PATCH
- Suspend confirmation requires explicit confirm; clicking Cancel does not change status
- After successful Activate, scheme list row shows status badge=ACTIVE (green)
**Depends on:** 10.2-T14, 10.2-T07

### 10.2-T16 — Frontend: Scheme Detail (read-only view) with status and connectivity summary  _(35 min)_
**Context:** Clicking View on the scheme list opens a read-only Scheme Detail page. It shows all fields organized in the same three sections (Identity, Connectivity, Settlement Terms) but in read-only display mode. Connectivity section shows masked credentials ('***') and the last_connectivity_test_result with timestamp (e.g. green SUCCESS 2026-06-01 14:32 KST). The Merchant Fee Table is shown read-only. Supported Countries are shown as a list of country name + code badges. A tab bar provides: Overview (main detail), Merchant Fees, Audit History, Rules (links to rule list filtered by this scheme). RBAC: ADMIN_VIEWER cannot see this page at all (403 if accessed directly). Actions panel: Edit (OPS only), Activate/Suspend (OPS only), Test Connection (OPS only).
**Steps:** Create SchemeDetailPage fetching GET /admin/schemes/{id} and GET /admin/schemes/{id}/merchant-fees on load; Render three read-only sections; credential fields show *** ; last_connectivity_test_result shown as colored badge with timestamp formatted as KST; Add tab bar: Overview, Merchant Fees, Audit History (loads 10.2-T18 component), Rules (links to /rules?scheme_id={id}); Show Edit / Activate/Suspend / Test Connection action buttons only for OPS_OPERATOR and OPS_ADMIN roles; Show 404 page if scheme not found; Write component test: ADMIN_VIEWER context hides action buttons
**Deliverable:** SchemeDetailPage with tabbed layout, read-only sections, masked credentials, and RBAC-gated actions
**Acceptance / logic checks:**
- sftp_password / api_secret fields display as *** in all view modes
- last_connectivity_test_result=SUCCESS renders green badge; last_connectivity_test_result=FAILURE renders red badge
- ADMIN_VIEWER user sees no Edit, Activate/Suspend, or Test Connection buttons
- Audit History tab loads scheme-scoped audit entries (entity_type=scheme, entity_id={id})
- Rules tab link navigates to Rules list pre-filtered by scheme
**Depends on:** 10.2-T15

### 10.2-T17 — Backend: AuditLogService - scheme entity logging and query endpoint  _(45 min)_
**Context:** All scheme write operations (create, update field, status change, connectivity test, country add/remove, fee row add/update/delete) must write to an audit_log table. Schema: id UUID PK, timestamp_utc TIMESTAMPTZ, actor_user_id BIGINT FK hub_user, actor_display_name VARCHAR(120), actor_ip VARCHAR(45), event_type VARCHAR(60) (e.g. scheme.created, scheme.field_changed, scheme.status_changed, scheme.country_added, scheme.fee_row_updated, scheme.connectivity_tested), entity_type VARCHAR(30), entity_id BIGINT, field_name VARCHAR(60) nullable, previous_value TEXT nullable, new_value TEXT nullable, description TEXT, metadata JSONB. Table is insert-only (no UPDATE/DELETE). Query endpoint: GET /admin/audit-log?entity_type=scheme&entity_id={id}&page=0&size=50 returns paginated log for that scheme. Filtered and sortable by timestamp DESC.
**Steps:** Create migration V10_2_003__audit_log_table.sql: audit_log table with all columns; no FK constraint on entity_id (cross-entity); index on (entity_type, entity_id), (actor_user_id), (timestamp_utc); Create AuditLogService.log(AuditLogEntry) that inserts a row; never throws (catches and logs internally to prevent audit failure from blocking the main operation); Create GET /admin/audit-log endpoint with query params: entity_type, entity_id, actor_user_id, event_type, date_from, date_to; paginated, sorted timestamp DESC; Enforce RBAC: OPS_OPERATOR, OPS_ADMIN, SUPER_ADMIN may query; FINANCE_ANALYST and ADMIN_VIEWER may not; Write migration to set DB-level INSERT-only permission for app service account on audit_log (or document this as a DBA step); Write test: log 3 entries for scheme_id=1; GET /admin/audit-log?entity_type=scheme&entity_id=1 returns 3 rows in reverse-timestamp order
**Deliverable:** Migration V10_2_003, AuditLogService, GET /admin/audit-log endpoint
**Acceptance / logic checks:**
- INSERT into audit_log via AuditLogService.log() persists; direct UPDATE on audit_log from app service account returns permission denied
- GET /admin/audit-log?entity_type=scheme&entity_id=5 returns only entries with entity_type=scheme AND entity_id=5
- Results are ordered timestamp_utc DESC
- FINANCE_ANALYST calling GET /admin/audit-log returns 403
- AuditLogService.log() does not throw and does not block calling code when DB is temporarily unavailable (exception is swallowed and logged at ERROR)
**Depends on:** 10.2-T01

### 10.2-T18 — Frontend: Scheme Audit History tab component  _(35 min)_
**Context:** The Audit History tab on SchemeDetailPage (10.2-T16) loads GET /admin/audit-log?entity_type=scheme&entity_id={id} and renders a table with columns: Timestamp (UTC + KST dual display), Actor (display name + user ID), Event Type, Field Changed, Previous Value, New Value, Effective From. Paginated (50 per page). A CSV Export button calls the same endpoint with page=0&size=10000 and downloads result as audit_scheme_{id}_{date}.csv. OPS_OPERATOR, OPS_ADMIN, SUPER_ADMIN only (enforced by API; UI hides the tab for other roles).
**Steps:** Create SchemeAuditHistoryTab component accepting schemeId prop; Fetch GET /admin/audit-log?entity_type=scheme&entity_id={schemeId}&page={page}&size=50 on mount and on page change; Render table: Timestamp (formatted as YYYY-MM-DD HH:mm:ss UTC / KST), Actor, Event Type chip, Field Changed, Previous Value, New Value; Add pagination; Previous/Next controls and page indicator; Implement CSV Export button: fetch with size=10000, convert JSON to CSV, trigger browser download with filename audit_scheme_{id}_{date}.csv; Write component test: 3 audit entries render as 3 table rows; page 2 fetches with page=1
**Deliverable:** SchemeAuditHistoryTab component with table, pagination, and CSV export
**Acceptance / logic checks:**
- Rendering 3 audit entries shows 3 table rows with correct field values
- Timestamp column shows both UTC and KST values
- CSV export download contains header row and one data row per audit entry
- Page 2 button fetches GET /admin/audit-log?...&page=1
- Tab is not rendered in DOM for ADMIN_VIEWER user context
**Depends on:** 10.2-T17, 10.2-T16

### 10.2-T19 — Unit tests: SchemeService status lifecycle and activation prerequisite logic  _(40 min)_
**Context:** SchemeService (10.2-T02) contains the status transition guard and activation prerequisite check. This ticket writes comprehensive unit tests (JUnit 5 + Mockito) covering all valid and invalid transitions and all combinations of unmet prerequisites. Prerequisites for ACTIVE: integration_type non-null, credentials set (sftp_host non-null for SFTP / api_base_url non-null for REST), scheme_country count >= 1, payout_ccy non-null, scheme_merchant_fee count >= 1. No live DB required; mock SchemeRepository and SchemeCountryRepository.
**Steps:** Write test class SchemeServiceTransitionTest with mocked repositories; Test case: transitionStatus(DRAFT->TESTING) always succeeds regardless of prerequisites; Test case: transitionStatus(TESTING->ACTIVE) with 0 scheme_country rows throws SchemeTransitionException with cause=NO_COUNTRIES; Test case: transitionStatus(TESTING->ACTIVE) with integration_type=SFTP and sftp_host=null throws with cause=MISSING_CONNECTIVITY_CONFIG; Test case: transitionStatus(TESTING->ACTIVE) with all 5 prerequisites met succeeds; Test case: transitionStatus(ACTIVE->DRAFT) throws (invalid transition); Test case: transitionStatus(SUSPENDED->ACTIVE) with all prerequisites met succeeds (reactivation); Test case: transitionStatus(ACTIVE->SUSPENDED) always succeeds
**Deliverable:** SchemeServiceTransitionTest with >= 8 test methods achieving >= 90% branch coverage on transitionStatus and prerequisite check
**Acceptance / logic checks:**
- All 8 test cases pass with no live DB dependency
- DRAFT->TESTING test confirms no prerequisite check is performed
- TESTING->ACTIVE with 0 merchant fees throws exception with message containing merchant_fee_table
- transitionStatus(ACTIVE->DRAFT) throws exception with message containing invalid transition
- 100% of SchemeService.transitionStatus and checkActivationPrerequisites lines covered
**Depends on:** 10.2-T02

### 10.2-T20 — Unit tests: CredentialEncryptionService AES-256 correctness and edge cases  _(30 min)_
**Context:** CredentialEncryptionService (10.2-T03) uses AES-256-GCM with a key from application config/Vault. Tests must verify: correct round-trip, different ciphertext for same plaintext (due to random IV), null/empty input handling, wrong-key decryption failure, and that decrypted value matches original exactly including Unicode. No network or DB required.
**Steps:** Write CredentialEncryptionServiceTest with a fixed test AES key (256-bit hex) injected via @TestPropertySource; Test round-trip: encrypt(plaintext) -> decrypt(result) == plaintext for a 64-char ASCII password; Test IV randomness: encrypt(same plaintext) twice produces different ciphertexts; Test empty string: encrypt('') and decrypt back returns ''; Test null input: encrypt(null) throws IllegalArgumentException; Test wrong key: decrypt with different key throws DecryptionException or equivalent; Test Unicode: encrypt decrypt correctly handles Korean characters (e.g. 한결원)
**Deliverable:** CredentialEncryptionServiceTest with 6 test methods, all passing, demonstrating AES-256-GCM correctness
**Acceptance / logic checks:**
- Round-trip test passes: decrypt(encrypt(hello_world_123)) == hello_world_123
- Two encryptions of identical plaintext produce different byte strings (IV uniqueness)
- decrypt with wrong key throws exception (not returns null)
- encrypt(null) throws IllegalArgumentException before any crypto operation
- Unicode test: decrypt(encrypt(한결원)) == 한결원
**Depends on:** 10.2-T03

### 10.2-T21 — Unit tests: SchemeController input validation and RBAC enforcement  _(40 min)_
**Context:** SchemeController (10.2-T05, 10.2-T06, 10.2-T07) must reject invalid inputs and enforce RBAC. Tests use MockMvc (Spring) or equivalent. RBAC roles: OPS_OPERATOR and OPS_ADMIN can create/edit/activate; FINANCE_ANALYST can only view (GET); ADMIN_VIEWER gets 403 on all scheme endpoints. Validate: scheme_code regex [A-Z0-9]{1,20}, logo_url must be HTTPS, gme_fee_share_pct in [0,100], status PATCH requires confirmed=true.
**Steps:** Write SchemeControllerTest using @WebMvcTest + mock SchemeService; Test POST /admin/schemes with scheme_code=lowercase returns 400 with field error on scheme_code; Test POST /admin/schemes with logo_url=http://... returns 400; Test POST /admin/schemes with gme_fee_share_pct=150 returns 400; Test PATCH /admin/schemes/1/status with confirmed=false returns 400; Test GET /admin/schemes with FINANCE_ANALYST token returns 200; Test POST /admin/schemes with FINANCE_ANALYST token returns 403; Test any /admin/schemes endpoint with ADMIN_VIEWER token returns 403; Test GET /admin/schemes/999 returns 404 when service throws SchemeNotFoundException
**Deliverable:** SchemeControllerTest with >= 8 MockMvc test methods covering validation and RBAC
**Acceptance / logic checks:**
- POST with scheme_code=zeropay returns 400 with field=scheme_code in error response
- POST with logo_url=http://example.com returns 400 with field=logo_url
- FINANCE_ANALYST can GET but gets 403 on POST
- ADMIN_VIEWER gets 403 on GET /admin/schemes
- PATCH with confirmed=false returns 400 with error message containing confirmation
**Depends on:** 10.2-T07

### 10.2-T22 — Unit tests: SchemeConnectivityService SFTP and REST test-connection paths  _(40 min)_
**Context:** SchemeConnectivityService (10.2-T03) has two code paths: SFTP (JSch) and REST (HTTP GET). Tests mock JSch ChannelSftp and the HTTP client. Must test: SFTP success path, SFTP auth failure, SFTP connection refused (host unreachable), SFTP timeout (> 10s), REST success (200 response), REST failure (non-200 response), REST timeout. Each path must update last_connectivity_test_result column correctly.
**Steps:** Write SchemeConnectivityServiceTest with mocked JSch SessionFactory and mocked HttpClient; Test SFTP success: mock ChannelSftp.stat returns a valid attrs object; assert result=SUCCESS, message contains sftp_host; Test SFTP auth failure: mock Session.connect throws JSchException(Auth fail); assert result=FAILURE, message contains auth; Test SFTP timeout: mock Session.connect hangs > 10s; assert result=TIMEOUT within 11s; Test REST success: mock HttpClient returns 200; assert result=SUCCESS; Test REST failure: mock HttpClient returns 401; assert result=FAILURE, message contains HTTP 401; Test REST timeout: mock HttpClient times out; assert result=TIMEOUT; Verify last_connectivity_test_result and last_connectivity_test_at updated by SchemeRepository.save() in all paths
**Deliverable:** SchemeConnectivityServiceTest with 7 test methods covering all success/failure/timeout paths
**Acceptance / logic checks:**
- SFTP auth failure test returns result=FAILURE without throwing exception to caller
- SFTP timeout test completes within 11 seconds (does not wait indefinitely)
- REST 401 test stores FAILURE with message containing 401 in last_connectivity_test_message
- All 7 tests pass with mocked dependencies (no live SFTP or HTTP server required)
- SchemeRepository.updateConnectivityTestResult called exactly once per testConnection invocation in all paths
**Depends on:** 10.2-T10

### 10.2-T23 — Unit tests: Merchant Fee Table validation and scheme_merchant_fee CRUD  _(35 min)_
**Context:** SchemeFeeCRUDController (10.2-T09) and underlying service must enforce: domestic_rate and cross_border_rate in [0.0000, 1.0000], unique merchant_type per scheme, audit log on every mutation. Tests use MockMvc + mock services. Also test the rate display conversion: UI sends 0.80 (as percent), service stores 0.0080 (as decimal).
**Steps:** Write SchemeFeeControllerTest with @WebMvcTest; Test POST valid row: merchant_type=General, domestic_rate=0.0080, cross_border_rate=0.0170 returns 201; Test POST with domestic_rate=1.5 (150%) returns 400; Test POST with negative domestic_rate returns 400; Test POST with duplicate merchant_type for same scheme returns 409; Test PUT updates domestic_rate and triggers AuditLogService.log() with previous_value and new_value; Test DELETE removes the row and triggers AuditLogService.log(); Test GET returns all rows for scheme_id=1 and empty array for scheme_id=99 (no fees)
**Deliverable:** SchemeFeeControllerTest with 7 test methods covering validation, uniqueness, and audit logging
**Acceptance / logic checks:**
- POST domestic_rate=1.5 returns 400 with field=domestic_rate
- POST duplicate merchant_type returns 409 Conflict
- PUT domestic_rate change: AuditLogService.log called with previous_value=0.0080, new_value=0.0090
- DELETE: AuditLogService.log called with event=scheme.fee_row_deleted
- GET for scheme with no fees returns 200 with empty array
**Depends on:** 10.2-T09

### 10.2-T24 — Integration test: full scheme onboarding happy path (ZEROPAY end-to-end)  _(55 min)_
**Context:** This ticket writes a Spring Boot integration test (with a real test DB, Testcontainers PostgreSQL) covering the full ZeroPay scheme onboarding lifecycle: create -> add countries -> add fee rows -> test connection (mocked SFTP) -> activate. Validates that all prerequisite checks, audit log entries, and status transitions work together correctly. Uses the REST API layer (not service layer directly) to test the full vertical slice.
**Steps:** Set up Testcontainers PostgreSQL and Spring Boot test context with @SpringBootTest and real JPA; Step 1: POST /admin/schemes with ZeroPay identity + SFTP connectivity + settlement terms; assert 201, status=DRAFT; Step 2: POST /admin/schemes/{id}/countries {country_code:KR}; assert 200; Step 3: POST /admin/schemes/{id}/merchant-fees with General 0.0080/0.0170; assert 201; Step 4: POST /admin/schemes/{id}/test-connection with mocked SFTP success; assert {result:SUCCESS}; Step 5: PATCH /admin/schemes/{id}/status {targetStatus:ACTIVE, confirmed:true}; assert 200, status=ACTIVE; Step 6: GET /admin/audit-log?entity_type=scheme&entity_id={id}; assert >= 4 entries (created, country_added, fee_row_added, status_changed); Step 7: attempt PATCH status to ACTIVE again; assert 422 (already ACTIVE is not a valid transition from ACTIVE)
**Deliverable:** SchemeOnboardingIntegrationTest with 7-step test covering create through activate with audit trail verification
**Acceptance / logic checks:**
- After step 5, GET /admin/schemes/{id} returns status=ACTIVE
- Audit log contains events: scheme.created, scheme.country_added, scheme.fee_row_added, scheme.status_changed in chronological order
- Step 7 (re-activate) returns 422
- All 7 steps pass in a single test run against Testcontainers DB
- Test runs in < 60 seconds including container startup
**Depends on:** 10.2-T17, 10.2-T09, 10.2-T07

### 10.2-T25 — Integration test: scheme activation prerequisite failure paths  _(45 min)_
**Context:** Complements 10.2-T24 by testing all negative prerequisite paths for activation. Uses Testcontainers PostgreSQL. Each sub-test creates a scheme in TESTING status with exactly one missing prerequisite and asserts PATCH /status returns 422 with the correct unmet condition identified. The four prerequisites are: (1) integration_type/credentials set, (2) >= 1 active country, (3) payout_ccy set, (4) >= 1 merchant fee row.
**Steps:** Test 1: scheme with integration_type=SFTP but sftp_host=null; PATCH to ACTIVE returns 422 with unmet=MISSING_CONNECTIVITY_CONFIG; Test 2: scheme with all connectivity set but 0 scheme_country rows; PATCH to ACTIVE returns 422 with unmet=NO_SUPPORTED_COUNTRIES; Test 3: scheme with payout_ccy=null; PATCH to ACTIVE returns 422 with unmet=NO_PAYOUT_CURRENCY; Test 4: scheme with all set but 0 merchant fee rows; PATCH to ACTIVE returns 422 with unmet=NO_MERCHANT_FEE_TABLE; Test 5: scheme with 2 missing prerequisites; PATCH to ACTIVE returns 422 with both unmet conditions in array; Write all tests against Testcontainers DB; each test inserts independent scheme record
**Deliverable:** SchemeActivationPrerequisiteTest with 5 integration test cases covering all 4 prerequisite failure modes
**Acceptance / logic checks:**
- Test 1 response body contains unmet_conditions with MISSING_CONNECTIVITY_CONFIG
- Test 2 response body contains NO_SUPPORTED_COUNTRIES
- Test 3 response body contains NO_PAYOUT_CURRENCY
- Test 4 response body contains NO_MERCHANT_FEE_TABLE
- Test 5 response body contains both missing items in unmet_conditions array
**Depends on:** 10.2-T24

### 10.2-T26 — Frontend component tests: SchemeFormPage validation and edge cases  _(40 min)_
**Context:** SchemeFormPage (10.2-T12 through 10.2-T14) contains client-side validation logic that must be tested in isolation using a component testing framework (Jest + React Testing Library or Vue Test Utils). Tests cover: scheme_code auto-uppercase, uniqueness API call on blur, HTTPS logo URL check, country requirement, mode requirement, SFTP conditional fields, fee table row add/remove, gme_fee_share_pct range, Review Changes diff modal content.
**Steps:** Write SchemeFormPage.test.jsx/spec.js using React Testing Library (or Vue Test Utils); Test: typing scheme_code=zeropay auto-uppercases to ZEROPAY in the input; Test: blur on scheme_code that matches existing code shows inline error via mocked GET /admin/schemes?code=ZEROPAY; Test: submitting form with logo_url=http://example.com shows inline error, does not call POST; Test: clearing all countries shows inline error on Save attempt; Test: switching integration_type from SFTP to REST hides sftp_host field and shows api_base_url field; Test: clicking Add Row in fee table adds a new empty row; clicking Remove deletes it; Test: Review Changes modal shows only changed fields (name changed, code unchanged -> only name in diff)
**Deliverable:** SchemeFormPage.test file with >= 8 component test cases, all passing
**Acceptance / logic checks:**
- scheme_code auto-uppercase test: user types 'zeropay', input value becomes 'ZEROPAY'
- logo_url http:// test: inline error appears without calling POST /admin/schemes
- Add Row test: fee table row count increases by 1
- Review Changes modal test: only modified fields appear in diff table
- All 8 tests pass without live API (all HTTP calls mocked)
**Depends on:** 10.2-T15

### 10.2-T27 — RBAC guard: enforce scheme module permissions server-side across all endpoints  _(50 min)_
**Context:** PRD-07 §12 RBAC matrix: Create/Edit Scheme = OPS_OPERATOR, OPS_ADMIN, SUPER_ADMIN only; Activate/Suspend Scheme = same; View Scheme Config = OPS_OPERATOR, OPS_ADMIN, FINANCE_ANALYST, SUPER_ADMIN; ADMIN_VIEWER = no access to any scheme endpoints. This ticket adds a dedicated RBAC test class that exhaustively verifies all scheme endpoints for all 4 roles. It also verifies that SUPER_ADMIN can perform all operations. Uses Spring Security test context with role-mocked JWTs.
**Steps:** Create SchemeRbacTest class using @SpringBootTest with mocked auth tokens for each role; Matrix test all 7 scheme endpoints (GET list, GET detail, POST create, PUT update, PATCH status, POST test-connection, POST countries, DELETE countries, POST fees, PUT fees, DELETE fees) against each role; Assert: ADMIN_VIEWER gets 403 on all endpoints; Assert: FINANCE_ANALYST gets 200 on GET list/detail; 403 on all write endpoints; Assert: OPS_OPERATOR gets 200/201 on all; 403 on none; Assert: SUPER_ADMIN gets 200/201 on all; Log a test matrix summary to stdout for review
**Deliverable:** SchemeRbacTest class with complete role x endpoint matrix test (>= 40 test cases), all passing
**Acceptance / logic checks:**
- ADMIN_VIEWER receives 403 on GET /admin/schemes (view blocked completely)
- FINANCE_ANALYST receives 403 on POST /admin/schemes
- FINANCE_ANALYST receives 200 on GET /admin/schemes
- OPS_OPERATOR receives 201 on POST /admin/schemes with valid payload
- SUPER_ADMIN receives 200/201 on every endpoint tested
**Depends on:** 10.2-T21

### 10.2-T28 — API documentation: OpenAPI spec for all scheme management endpoints  _(45 min)_
**Context:** The Admin System backend must expose an OpenAPI 3.0 spec (generated from code annotations or hand-authored) for all scheme endpoints: GET /admin/schemes, GET /admin/schemes/{id}, POST /admin/schemes, PUT /admin/schemes/{id}, PATCH /admin/schemes/{id}/status, POST /admin/schemes/{id}/test-connection, GET+POST+DELETE /admin/schemes/{id}/countries, GET+POST+PUT+DELETE /admin/schemes/{id}/merchant-fees, GET /admin/audit-log. The spec must include request/response schemas, error codes (400, 401, 403, 404, 409, 422), and security scheme (Bearer JWT). Consumed by frontend and QA teams.
**Steps:** Add springdoc-openapi or equivalent to project dependencies; Annotate all scheme controllers and DTOs with @Operation, @ApiResponse, @Schema annotations; Ensure error response bodies (400 validation errors, 422 unmet conditions) are documented with example payloads; Document security requirement: BearerAuth on all /admin/* endpoints; Generate openapi.json and commit to docs/openapi/scheme-management.json; Verify spec can be loaded in Swagger UI and all endpoints are listed with correct request/response shapes
**Deliverable:** docs/openapi/scheme-management.json covering all 13 scheme management endpoints with full schema documentation
**Acceptance / logic checks:**
- openapi.json is valid JSON and loads without errors in Swagger UI
- POST /admin/schemes schema documents all required fields including scheme_code pattern [A-Z0-9]{1,20}
- PATCH /admin/schemes/{id}/status documents 422 response with unmet_conditions array example
- All endpoints document 401 Unauthorized and 403 Forbidden responses
- Security scheme BearerAuth is referenced on every /admin/* endpoint
**Depends on:** 10.2-T07, 10.2-T09, 10.2-T10


## WBS 10.3 — Partner management & credentials
### 10.3-T01 — DB migration: create partner table  _(30 min)_
**Context:** GMEPay+ stores all partner configuration as runtime data — no code branches per partner. The partner table (PostgreSQL) must hold: id BIGINT PK, partner_code VARCHAR(30) UNIQUE NOT NULL (uppercase alphanumeric, max 20 chars, e.g. GME_REMIT), name VARCHAR(100) NOT NULL, partner_type VARCHAR(10) NOT NULL CHECK IN (LOCAL, OVERSEAS), collection_ccy CHAR(3) NOT NULL, settle_a_ccy CHAR(3) NOT NULL, webhook_url VARCHAR(512) NOT NULL, rate_quote_ttl_seconds INT NOT NULL DEFAULT 300 CHECK (60..1800), low_balance_threshold_usd DECIMAL(20,4) (nullable; OVERSEAS only), low_balance_alert_email VARCHAR(255) (nullable), status VARCHAR(20) NOT NULL DEFAULT ONBOARDING CHECK IN (ONBOARDING, ACTIVE, SUSPENDED, INACTIVE), is_active BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ, created_by VARCHAR(120), updated_by VARCHAR(120). partner_type is immutable after status transitions to ACTIVE.
**Steps:** Create a Flyway (or Liquibase) migration file V10_3_001__create_partner.sql; Define the partner table with all columns, types, constraints, and CHECK clauses listed in context; Add UNIQUE index on partner_code; Add a partial index on status WHERE is_active = TRUE for fast active-partner lookup; Write a rollback script (DROP TABLE partner)
**Deliverable:** Migration file V10_3_001__create_partner.sql that applies cleanly on an empty schema and rolls back without error
**Acceptance / logic checks:**
- INSERT a LOCAL partner with partner_code GME_REMIT succeeds; INSERT a duplicate partner_code raises unique-constraint violation
- INSERT with partner_type = BROKER raises CHECK violation; only LOCAL and OVERSEAS are accepted
- INSERT with rate_quote_ttl_seconds = 59 or 1801 raises CHECK violation; 60 and 1800 succeed
- SELECT on is_active index returns only rows where is_active = TRUE
- Rollback removes the table cleanly; re-apply recreates it without error

### 10.3-T02 — DB migration: create partner_credential table  _(25 min)_
**Context:** Partner API credentials are key+secret pairs. The secret is stored as a bcrypt hash; plaintext is shown once at generation and never retrievable. Table columns: id BIGINT PK, partner_id BIGINT FK partner NOT NULL, api_key VARCHAR(64) UNIQUE NOT NULL, api_secret_hash VARCHAR(128) NOT NULL, is_active BOOLEAN NOT NULL DEFAULT TRUE, expires_at TIMESTAMPTZ (NULL = no expiry), created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ, created_by VARCHAR(120), updated_by VARCHAR(120). Business rule: only one active credential per partner at a time — enforce via partial unique index on partner_id WHERE is_active = TRUE. All credential operations must be audit-logged (see ticket 10.3-T06 for audit trail).
**Steps:** Create migration file V10_3_002__create_partner_credential.sql; Define partner_credential table with all columns and FK to partner; Add UNIQUE index on api_key; Add partial unique index on (partner_id) WHERE is_active = TRUE to enforce single-active-credential invariant; Write rollback script
**Deliverable:** Migration file V10_3_002__create_partner_credential.sql
**Acceptance / logic checks:**
- INSERT two active credentials for the same partner raises unique-constraint violation on the partial index
- INSERT one active and one inactive credential for the same partner succeeds
- Deleting partner cascades or is prevented (FK constraint raises error if partner deleted while credential exists — confirm behaviour is RESTRICT)
- api_key UNIQUE index rejects duplicate key across different partners
- Rollback removes table cleanly
**Depends on:** 10.3-T01

### 10.3-T03 — DB migration: create prefunding_account and prefunding_ledger_entry tables  _(30 min)_
**Context:** OVERSEAS partners (e.g. SendMN, T-Bank) maintain a prepaid USD balance. prefunding_account: id BIGINT PK, partner_id BIGINT FK partner UNIQUE NOT NULL, currency CHAR(3) NOT NULL DEFAULT USD, balance DECIMAL(20,4) NOT NULL DEFAULT 0, low_balance_threshold DECIMAL(20,4) NOT NULL DEFAULT 10000.0000, created_at/updated_at/created_by/updated_by. prefunding_ledger_entry is append-only (no updated_at, no deletes): id BIGINT PK, account_id BIGINT FK prefunding_account NOT NULL, txn_ref VARCHAR(64), entry_type VARCHAR(20) NOT NULL CHECK IN (DEBIT_PAYMENT, DEBIT_REVERSAL, CREDIT_TOPUP, CREDIT_REVERSAL), amount DECIMAL(20,4) NOT NULL CHECK > 0, balance_after DECIMAL(20,4) NOT NULL, note VARCHAR(255), created_at TIMESTAMPTZ NOT NULL, created_by VARCHAR(120). Index: (account_id, created_at) for balance history. One prefunding_account per partner (UNIQUE on partner_id).
**Steps:** Create migration file V10_3_003__create_prefunding.sql; Define prefunding_account table with UNIQUE constraint on partner_id and balance column; Define prefunding_ledger_entry as append-only (no updated_at column, no UPDATE triggers on amount/balance_after); Add compound index on (account_id, created_at) on ledger; Add CHECK constraint on entry_type and amount > 0; Write rollback script
**Deliverable:** Migration file V10_3_003__create_prefunding.sql
**Acceptance / logic checks:**
- INSERT a second prefunding_account for the same partner_id raises unique-constraint violation
- INSERT a ledger entry with amount = 0 or negative raises CHECK violation
- INSERT a ledger entry with entry_type = TRANSFER raises CHECK violation
- Compound index (account_id, created_at) is present and used by EXPLAIN for balance history query: SELECT * FROM prefunding_ledger_entry WHERE account_id = 1 ORDER BY created_at DESC
- Currency column defaults to USD; attempting to set currency = EUR succeeds (no currency CHECK here — validated at app layer per 10.3-T09)
**Depends on:** 10.3-T01

### 10.3-T04 — DB migration: create low_balance_alert_config table  _(20 min)_
**Context:** Each OVERSEAS partner has a configurable low-balance alert. The system fires an email when balance drops below threshold after a deduction; the triggering transaction is NOT blocked. Table: id BIGINT PK, partner_id BIGINT FK partner UNIQUE NOT NULL, threshold_usd DECIMAL(20,4) NOT NULL DEFAULT 10000.0000, alert_email VARCHAR(255) NOT NULL (comma-separated for multiple recipients; validated at app layer), is_active BOOLEAN NOT NULL DEFAULT TRUE, created_at/updated_at/created_by/updated_by. UNIQUE on partner_id (one config per partner). Default threshold is USD 10,000 (per SendMN use-case).
**Steps:** Create migration file V10_3_004__create_low_balance_alert_config.sql; Define low_balance_alert_config table with UNIQUE on partner_id; Set default value for threshold_usd = 10000.0000; Write rollback script
**Deliverable:** Migration file V10_3_004__create_low_balance_alert_config.sql
**Acceptance / logic checks:**
- INSERT two alert_config rows for the same partner_id raises unique-constraint violation
- INSERT with threshold_usd = 0 is accepted at DB layer (validated as >= 0 at app layer — no negative balance threshold makes sense but zero is valid edge case)
- Default threshold_usd is 10000.0000 when not supplied
- Rollback drops table without error
- FK to partner raises error if partner does not exist
**Depends on:** 10.3-T01

### 10.3-T05 — DB migration: add audit_log table for admin-system events  _(30 min)_
**Context:** All write operations in the Admin System generate an immutable audit log entry. Table: id UUID PK DEFAULT gen_random_uuid(), timestamp_utc TIMESTAMPTZ NOT NULL DEFAULT now(), actor_user_id VARCHAR(120) NOT NULL, actor_display_name VARCHAR(200), actor_ip_address INET, event_type VARCHAR(60) NOT NULL (e.g. partner.created, partner.credential_generated, partner.credential_revoked, prefunding.topup_recorded, prefunding.adjustment_created), entity_type VARCHAR(40) NOT NULL (e.g. partner, partner_credential, prefunding_account), entity_id VARCHAR(60) NOT NULL, previous_value JSONB, new_value JSONB, description TEXT, metadata JSONB, created_at TIMESTAMPTZ NOT NULL DEFAULT now(). NO updated_at. The application service account must have INSERT-only permission (no UPDATE or DELETE) — enforced via GRANT in migration. Indexes: (entity_type, entity_id), (actor_user_id), (timestamp_utc).
**Steps:** Create migration file V10_3_005__create_audit_log.sql; Define audit_log table with UUID PK and all columns listed in context; Add indexes on (entity_type, entity_id), actor_user_id, and timestamp_utc; Add GRANT INSERT ON audit_log TO app_role; REVOKE UPDATE, DELETE ON audit_log FROM app_role; Write rollback script (DROP TABLE only — no REVOKE needed if role dropped)
**Deliverable:** Migration file V10_3_005__create_audit_log.sql
**Acceptance / logic checks:**
- INSERT into audit_log succeeds for the app_role
- UPDATE on audit_log raises permission error for app_role
- DELETE on audit_log raises permission error for app_role
- Index on (entity_type, entity_id) present and used by EXPLAIN for query WHERE entity_type = partner AND entity_id = 42
- Rollback drops table without error

### 10.3-T06 — Implement AuditLogService: write immutable audit entries  _(45 min)_
**Context:** AuditLogService is the single write path for all Admin System audit events. It must: accept (actorUserId, actorDisplayName, actorIp, eventType, entityType, entityId, previousValue, newValue, description, metadata); persist one row to audit_log with timestamp_utc = NOW() UTC; be transactional — the audit INSERT must succeed or the calling operation must fail (use same DB transaction). Event type codes are dot-separated: partner.created, partner.updated, partner.activated, partner.suspended, partner.credential_generated, partner.credential_revoked, prefunding.account_created, prefunding.topup_recorded, prefunding.adjustment_created, prefunding.alert_threshold_updated. previousValue and newValue are serialized as JSONB.
**Steps:** Create AuditLogService class with a writeEntry(AuditEntryRequest) method; Accept the fields listed in context; persist to audit_log in the same DB transaction as the calling operation; Throw AuditWriteException (which rolls back the outer transaction) if the INSERT fails; Provide a queryByEntity(entityType, entityId, pageable) read method returning paginated AuditLogEntry DTOs; Add an integration test: calling writeEntry within a transaction that is then rolled back must NOT persist the audit row
**Deliverable:** AuditLogService class (Java) with writeEntry and queryByEntity methods, and integration test
**Acceptance / logic checks:**
- writeEntry with valid inputs inserts exactly one row with correct event_type and entity_id
- writeEntry called inside a transaction that is rolled back does not persist the row (ACID test)
- queryByEntity returns rows sorted by timestamp_utc DESC, paginated correctly
- Calling writeEntry with null actorUserId throws IllegalArgumentException before any DB call
- Querying for a non-existent entity returns an empty page, not an error
**Depends on:** 10.3-T05

### 10.3-T07 — Implement PartnerRepository: CRUD and lookup methods  _(40 min)_
**Context:** PartnerRepository wraps the partner table. Required methods: save(Partner), findById(Long), findByPartnerCode(String), findAllByStatus(String, Pageable), existsByPartnerCode(String), updateStatus(Long partnerId, String newStatus, String updatedBy). All writes must update updated_at = NOW() and updated_by. The findByPartnerCode lookup is used at API authentication time and must be backed by the UNIQUE index on partner_code. Use DECIMAL types (not float/double) for monetary columns. partner_type is read from DB but must not be writable after status = ACTIVE (enforced at service layer, not repository).
**Steps:** Create JPA entity Partner mapped to the partner table with all columns; Create PartnerRepository (Spring Data JPA) with custom queries for findByPartnerCode and findAllByStatus; Ensure DECIMAL(20,4) maps to BigDecimal in Java for low_balance_threshold_usd; Write integration tests against a test DB (Testcontainers) for each method; Verify that findByPartnerCode uses the UNIQUE index (check via EXPLAIN in test)
**Deliverable:** Partner JPA entity + PartnerRepository with integration tests
**Acceptance / logic checks:**
- findByPartnerCode(GME_REMIT) returns the correct partner after INSERT
- findAllByStatus(ACTIVE, pageable) returns only ACTIVE partners
- save() with a duplicate partner_code throws DataIntegrityViolationException
- low_balance_threshold_usd round-trips as BigDecimal 10000.0000 without floating-point error
- updateStatus() updates status and updated_by but not partner_type or collection_ccy
**Depends on:** 10.3-T01

### 10.3-T08 — Implement PartnerCredentialService: generate and revoke API credentials  _(50 min)_
**Context:** Credentials are generated by GME Ops. On generate: (1) deactivate any existing active credential for the partner (set is_active = FALSE), (2) generate a cryptographically random api_key (UUID v4 hex, no dashes, 32 chars) and api_secret (32-byte random, base64url-encoded, 44 chars), (3) hash the secret with BCrypt cost 12, (4) persist the new credential with is_active = TRUE, (5) return the plaintext secret ONCE (never stored). On revoke: set is_active = FALSE on the active credential. Only one active credential per partner at a time — enforced by partial unique index on partner_id WHERE is_active = TRUE (see 10.3-T02) AND in service logic. Both operations are audit-logged with event_type partner.credential_generated or partner.credential_revoked. Partner must be in ACTIVE or ONBOARDING status to generate credentials.
**Steps:** Create PartnerCredentialService with generateCredentials(partnerId, actorUserId, actorIp) method; Implement deactivate-existing + generate-new in a single DB transaction; Use SecureRandom for key and secret generation; BCrypt strength 12 for hashing; Return a CredentialIssuanceResult DTO containing api_key and plaintext api_secret (shown once); Implement revokeCredentials(partnerId, actorUserId, actorIp) that sets is_active = FALSE; Call AuditLogService.writeEntry for both operations within the same transaction
**Deliverable:** PartnerCredentialService with generateCredentials and revokeCredentials, unit and integration tests
**Acceptance / logic checks:**
- generateCredentials for a partner with an existing active credential deactivates the old one and creates a new one — only one active credential remains
- api_secret returned in plaintext; subsequent calls to find the secret return only the hash (plaintext not stored)
- BCrypt hash of the returned plaintext secret matches api_secret_hash in DB: BCrypt.checkpw(plaintext, hash) == true
- revokeCredentials sets is_active = FALSE; partner has zero active credentials afterwards
- generateCredentials for a SUSPENDED partner throws PartnerNotEligibleException
- Audit log contains event partner.credential_generated with entity_id = partner_id after successful generation
**Depends on:** 10.3-T02, 10.3-T06, 10.3-T07

### 10.3-T09 — Implement PartnerService: create and validate partner  _(45 min)_
**Context:** PartnerService.createPartner accepts: partnerCode (uppercase alphanumeric, max 20 chars, unique), name (max 100 chars), partnerType (LOCAL|OVERSEAS), collectionCcy (ISO 4217, must exist in currency registry), settleACcy (ISO 4217, must exist), webhookUrl (HTTPS required), rateQuoteTtlSeconds (60..1800, default 300), contactEmail (required for low-balance alerts). Validation rules: (1) partnerCode must match regex [A-Z0-9_]{1,20}; (2) webhookUrl must be HTTPS; (3) for LOCAL partners, collectionCcy must equal settleACcy (e.g. both KRW); (4) for OVERSEAS partners, GME expects settleACcy = USD (warn if not); (5) if collectionCcy == settleACcy, the system notes potential same-currency rule later. Status is set to ONBOARDING on create. Calls AuditLogService with event_type partner.created. Returns the created partner entity including its generated id.
**Steps:** Create PartnerService.createPartner(CreatePartnerRequest, actorUserId, actorIp) method; Validate partnerCode regex, name length, webhookUrl HTTPS, rateQuoteTtlSeconds range; Validate collectionCcy and settleACcy exist in the currency registry table (treasury_rate has an entry or they are USD); For LOCAL: enforce collectionCcy == settleACcy; for OVERSEAS: warn (not block) if settleACcy != USD; Persist via PartnerRepository.save() with status = ONBOARDING; Call AuditLogService.writeEntry(partner.created, previous = null, new = partner as JSON)
**Deliverable:** PartnerService.createPartner with input validation and audit, plus unit tests covering each validation rule
**Acceptance / logic checks:**
- createPartner with partnerCode GME-REMIT (hyphen) throws ValidationException (only A-Z0-9_ allowed)
- createPartner with webhookUrl http://example.com (not HTTPS) throws ValidationException
- createPartner LOCAL with collectionCcy = MNT and settleACcy = KRW throws ValidationException (must be equal for LOCAL)
- createPartner with duplicate partnerCode throws DuplicatePartnerException
- Successful create persists status = ONBOARDING and is_active = FALSE
- Audit log entry partner.created exists with new_value containing the partner_code after successful create
**Depends on:** 10.3-T05, 10.3-T06, 10.3-T07

### 10.3-T10 — Implement PartnerService: activate partner with pre-condition checks  _(40 min)_
**Context:** Activating a partner (status ONBOARDING -> ACTIVE) requires: (1) settlementCurrency set; (2) at least one active API credential exists (see 10.3-T08); (3) webhookUrl is set (HTTPS); (4) for OVERSEAS partners: prefunding_account exists AND balance > 0. Activation sets status = ACTIVE, is_active = TRUE, and records updated_at. partner_type becomes immutable after activation (any subsequent attempt to change partner_type raises ImmutableFieldException). Audit event: partner.activated. If any pre-condition fails, return a structured error listing which conditions are unmet, e.g. {unmet: [NO_ACTIVE_CREDENTIAL, PREFUNDING_BALANCE_ZERO]}.
**Steps:** Create PartnerService.activatePartner(partnerId, actorUserId, actorIp) method; Check all 4 pre-conditions; collect unmet conditions into a list; If list is non-empty throw PartnerActivationException(unmet list); Set status = ACTIVE, is_active = TRUE, updated_at = NOW(); Mark partner_type as immutable: add a guard in updatePartner that throws ImmutableFieldException if partner is ACTIVE and partnerType field is changed; Call AuditLogService.writeEntry(partner.activated)
**Deliverable:** PartnerService.activatePartner with pre-condition checks and immutability guard, plus unit tests
**Acceptance / logic checks:**
- Activating a LOCAL partner with no active credential returns unmet = [NO_ACTIVE_CREDENTIAL]
- Activating an OVERSEAS partner with balance = 0 returns unmet = [PREFUNDING_BALANCE_ZERO]
- Activating with all conditions met sets status = ACTIVE and is_active = TRUE
- After activation, calling updatePartner with a changed partner_type throws ImmutableFieldException
- Audit log entry partner.activated with entity_id = partner_id exists after successful activation
- Attempting to activate an already-ACTIVE partner throws IllegalStateException (idempotency guard)
**Depends on:** 10.3-T08, 10.3-T09

### 10.3-T11 — Implement PartnerService: suspend and reactivate partner  _(35 min)_
**Context:** Suspending a partner (status ACTIVE -> SUSPENDED) halts all new payment processing for that partner. Reactivating (SUSPENDED -> ACTIVE) restores processing. Suspension does not affect in-flight transactions (they are already committed). On suspension: set status = SUSPENDED, is_active = FALSE. On reactivation: re-run the same pre-condition checks as activation (10.3-T10): credential, webhookUrl, and for OVERSEAS balance > 0. Audit events: partner.suspended and partner.reactivated. Both operations require actorUserId and actorIp for the audit trail.
**Steps:** Create PartnerService.suspendPartner(partnerId, reason, actorUserId, actorIp) method; Set status = SUSPENDED, is_active = FALSE, persist; Call AuditLogService.writeEntry(partner.suspended, metadata includes reason string); Create PartnerService.reactivatePartner(partnerId, actorUserId, actorIp) method; Run same pre-condition checks as activatePartner; throw PartnerActivationException if unmet; On success set status = ACTIVE, is_active = TRUE; call audit writeEntry(partner.reactivated)
**Deliverable:** PartnerService.suspendPartner and reactivatePartner, with unit tests
**Acceptance / logic checks:**
- suspendPartner on ACTIVE partner sets status = SUSPENDED and is_active = FALSE
- suspendPartner on ONBOARDING partner throws IllegalStateException (can only suspend ACTIVE partners)
- reactivatePartner on a partner with zero balance (OVERSEAS) returns unmet = [PREFUNDING_BALANCE_ZERO]
- After reactivatePartner succeeds, status = ACTIVE and is_active = TRUE
- Audit log contains partner.suspended and partner.reactivated events with actor info
- suspendPartner is idempotent: calling it twice does not error
**Depends on:** 10.3-T10

### 10.3-T12 — Implement PrefundingService: create prefunding account on partner creation  _(35 min)_
**Context:** When an OVERSEAS partner is created, a prefunding_account row must be created. For LOCAL partners, no prefunding_account is created. The initial balance is set when Ops records the first top-up or at activation (see 10.3-T13). prefunding_account.currency is always USD. prefunding_account.balance starts at 0.0000. The low_balance_threshold defaults to 10000.0000 USD (configurable per 10.3-T04). PrefundingService.createAccountForPartner(partnerId, thresholdUsd) creates the account and inserts a corresponding low_balance_alert_config row with the given threshold and alert_email from the partner record. Audit event: prefunding.account_created.
**Steps:** Create PrefundingService.createAccountForPartner(partnerId, thresholdUsd, alertEmail, actorUserId) method; Validate partnerType == OVERSEAS before creating account; throw UnsupportedOperationException for LOCAL; Insert prefunding_account row with balance = 0, currency = USD, low_balance_threshold = thresholdUsd; Insert low_balance_alert_config row with threshold_usd and alert_email; is_active = TRUE; Call AuditLogService.writeEntry(prefunding.account_created) in the same transaction; Test that calling this method twice for the same partner throws DataIntegrityViolationException (UNIQUE on partner_id)
**Deliverable:** PrefundingService.createAccountForPartner with account and alert config creation, unit and integration tests
**Acceptance / logic checks:**
- Calling for a LOCAL partner throws UnsupportedOperationException
- Calling for an OVERSEAS partner creates prefunding_account with balance = 0.0000 and currency = USD
- low_balance_alert_config is created with threshold = 10000.0000 by default when thresholdUsd = null
- Calling twice for the same partner throws DataIntegrityViolationException
- Audit log entry prefunding.account_created with entity_id = account_id exists after success
**Depends on:** 10.3-T03, 10.3-T04, 10.3-T06, 10.3-T07

### 10.3-T13 — Implement PrefundingService: record top-up atomically  _(50 min)_
**Context:** Ops records a top-up when a partner wires funds to GME. Top-up inputs: partnerId, amountUsd (BigDecimal, > 0), bankRef (required, non-empty string), valueDate (required date), notes (optional). Processing: (1) SELECT FOR UPDATE the prefunding_account row; (2) add amountUsd to balance; (3) INSERT a prefunding_ledger_entry with entry_type = CREDIT_TOPUP, amount = amountUsd, balance_after = new balance, txn_ref = null, note = bankRef; (4) COMMIT — all three steps in one transaction. Audit event: prefunding.topup_recorded with previous_value = old balance, new_value = new balance. Returns a TopUpResult with new balance and ledger entry id.
**Steps:** Create PrefundingService.recordTopUp(RecordTopUpRequest, actorUserId, actorIp) method; SELECT FOR UPDATE the prefunding_account; throw AccountNotFoundException if missing; Validate amountUsd > 0 and bankRef non-empty; throw ValidationException otherwise; Update balance = balance + amountUsd; INSERT ledger entry CREDIT_TOPUP with balance_after; Call AuditLogService.writeEntry(prefunding.topup_recorded, previous = old balance, new = new balance); Return TopUpResult(newBalance, ledgerEntryId)
**Deliverable:** PrefundingService.recordTopUp with SELECT FOR UPDATE atomicity, integration test including concurrent top-up scenario
**Acceptance / logic checks:**
- recordTopUp with amountUsd = 0 throws ValidationException
- recordTopUp with empty bankRef throws ValidationException
- After recordTopUp(100.00) on account with balance 500.00, balance = 600.0000
- ledger entry has entry_type = CREDIT_TOPUP, amount = 100.0000, balance_after = 600.0000
- Concurrent top-ups of 100.00 and 200.00 on balance 500.00 result in final balance = 800.0000 (not 600 or 700 — SELECT FOR UPDATE prevents race)
- Audit log contains prefunding.topup_recorded with previous_value = 500.0000 and new_value = 600.0000
**Depends on:** 10.3-T03, 10.3-T06, 10.3-T12

### 10.3-T14 — Implement PrefundingService: deduct balance atomically (payment path)  _(55 min)_
**Context:** For OVERSEAS partners, prefunding is deducted before any scheme call. Deduction must be ATOMIC: SELECT ... FOR UPDATE on prefunding_account, check balance >= deductionAmountUsd, subtract, INSERT ledger entry DEBIT_PAYMENT. If balance < deductionAmountUsd, throw InsufficientPrefundingException (transaction is rejected; scheme is never called). After deduction, check if balance < low_balance_threshold — if so, trigger LowBalanceAlertService.sendAlert (async OK). Deduction and scheme call are separate operations; if the scheme subsequently fails, a DEBIT_REVERSAL entry is created by a separate reversal path (10.3-T15). This method is called from the payment orchestration layer — it must be fast: target < 50 ms under normal load.
**Steps:** Create PrefundingService.deductBalance(partnerId, deductionAmountUsd, txnRef, actorUserId) method; SELECT FOR UPDATE prefunding_account WHERE partner_id = ?; If balance < deductionAmountUsd throw InsufficientPrefundingException(currentBalance, requested); balance = balance - deductionAmountUsd; INSERT ledger entry DEBIT_PAYMENT with balance_after; After commit, if new balance < low_balance_threshold call LowBalanceAlertService.checkAndAlert(partnerId) asynchronously; Return DeductionResult(newBalance, ledgerEntryId)
**Deliverable:** PrefundingService.deductBalance with SELECT FOR UPDATE, InsufficientPrefundingException on zero/low balance, unit and integration tests
**Acceptance / logic checks:**
- deductBalance of 200.00 on account with balance 150.00 throws InsufficientPrefundingException without modifying the balance
- deductBalance of 100.00 on account with balance 500.00 results in balance = 400.0000 and ledger entry DEBIT_PAYMENT with balance_after = 400.0000
- Two concurrent deductions of 300.00 on a balance of 500.00: exactly one succeeds (balance = 200.00), exactly one throws InsufficientPrefundingException — SELECT FOR UPDATE prevents double-spend
- txnRef is stored on the ledger entry for traceability back to the transaction
- balance = 0 after deduction triggers LowBalanceAlertService.checkAndAlert call; balance = 5000.00 with threshold 10000.00 also triggers alert
- deductBalance for a LOCAL partner (no prefunding_account) throws AccountNotFoundException immediately
**Depends on:** 10.3-T03, 10.3-T12

### 10.3-T15 — Implement PrefundingService: reverse deduction (cancellation and refund path)  _(45 min)_
**Context:** When a payment is cancelled or a refund is confirmed by ZeroPay (DEBIT_REVERSAL), the deducted amount must be credited back to the prefunding balance. reverseDeduction inputs: partnerId, originalTxnRef, reversalAmountUsd, reversalType (CANCEL|REFUND_CONFIRMED). Processing: SELECT FOR UPDATE on prefunding_account, add reversalAmountUsd to balance, INSERT ledger entry with entry_type = DEBIT_REVERSAL or CREDIT_REVERSAL (use DEBIT_REVERSAL for payment cancellation and CREDIT_REVERSAL for refund confirmation per 10.3-T03 schema). Reversal must be idempotent: if a ledger entry for the same originalTxnRef with entry_type DEBIT_REVERSAL already exists, return the existing result without double-crediting. Audit event: prefunding.deduction_reversed.
**Steps:** Create PrefundingService.reverseDeduction(partnerId, originalTxnRef, reversalAmountUsd, actorUserId) method; Check for existing DEBIT_REVERSAL ledger entry with the same txn_ref; if found return existing result (idempotency); SELECT FOR UPDATE prefunding_account; balance = balance + reversalAmountUsd; INSERT DEBIT_REVERSAL ledger entry with balance_after; Call AuditLogService.writeEntry(prefunding.deduction_reversed); Return ReversalResult(newBalance, ledgerEntryId)
**Deliverable:** PrefundingService.reverseDeduction with idempotency guard, integration tests including duplicate-call scenario
**Acceptance / logic checks:**
- reverseDeduction of 100.00 on balance 200.00 results in balance = 300.0000 and DEBIT_REVERSAL ledger entry with balance_after = 300.0000
- Calling reverseDeduction twice with the same originalTxnRef returns the same ledgerEntryId without creating a second ledger entry (idempotency)
- reverseDeduction with reversalAmountUsd = 0 throws ValidationException
- Concurrent reversal calls for the same txnRef: exactly one ledger row created (idempotency + SELECT FOR UPDATE)
- Audit log entry prefunding.deduction_reversed exists after first call; no second audit entry on idempotent replay
**Depends on:** 10.3-T14

### 10.3-T16 — Implement LowBalanceAlertService: threshold check and email alert  _(50 min)_
**Context:** After every deduction, the system checks if the partner balance has dropped below the configured threshold. If balance < threshold: send an email to all addresses in low_balance_alert_config.alert_email (comma-separated). Email content must include: partner name, current balance (e.g. USD 8,500.00), threshold (e.g. USD 10,000.00), and a suggested top-up amount (threshold - balance, rounded up to nearest 100). The transaction that triggered the alert is NOT blocked. If balance <= 0: send an urgent alert and set partner.is_active = FALSE (payments suspended). Alert delivery uses the platform email service (SMTP relay / SES). Deduplication: do not send a second alert within 1 hour for the same partner (use a last_alert_sent_at column or a simple Redis key with TTL 3600s).
**Steps:** Create LowBalanceAlertService.checkAndAlert(partnerId) method called asynchronously after deduction; Query low_balance_alert_config for the partner; if not found or is_active = FALSE, return without action; Compare balance against threshold; if balance < threshold prepare low-balance email; Parse comma-separated alert_email; send email to each address via EmailSendPort (interface, injectable); If balance <= 0: send urgent email AND call PartnerService.suspendPartner(reason = ZERO_BALANCE); Implement 1-hour deduplication using last_alert_sent_at in low_balance_alert_config or a cache TTL
**Deliverable:** LowBalanceAlertService.checkAndAlert with email composition and deduplication, unit tests with mock EmailSendPort
**Acceptance / logic checks:**
- balance = 8500.00 with threshold = 10000.00: email sent to all addresses in alert_email with suggested top-up = 1500.00 (10000 - 8500)
- balance = 15000.00 with threshold = 10000.00: no email sent
- balance = 0.00: urgent email sent AND partner is_active set to FALSE
- Second alert within 1 hour for same partner is suppressed (no duplicate email)
- Alert is sent again after 1 hour deduplication window expires
- Email content includes partner name, current balance formatted as USD 8,500.00, and threshold USD 10,000.00
**Depends on:** 10.3-T04, 10.3-T11, 10.3-T14

### 10.3-T17 — Implement PrefundingService: record manual adjustment with mandatory reason  _(40 min)_
**Context:** Finance or Ops (with appropriate RBAC) can create a manual ADJUSTMENT entry on a prefunding ledger to correct recording errors (e.g. a top-up was entered with the wrong amount). Inputs: partnerId, adjustmentAmountUsd (positive = credit, negative = debit), reason (mandatory, min 10 chars), actorUserId. Processing: SELECT FOR UPDATE on prefunding_account, compute new balance = balance + adjustmentAmountUsd, validate new balance >= 0 (no negative balance allowed), INSERT ledger entry. entry_type: CREDIT_TOPUP if adjustmentAmountUsd > 0, DEBIT_REVERSAL if < 0. Audit event: prefunding.adjustment_created with mandatory reason in metadata. Returns new balance and ledger entry id.
**Steps:** Create PrefundingService.recordAdjustment(RecordAdjustmentRequest, actorUserId, actorIp) method; Validate reason is non-null and >= 10 chars; validate adjustmentAmountUsd != 0; SELECT FOR UPDATE prefunding_account; compute newBalance = balance + adjustmentAmountUsd; Validate newBalance >= 0; throw InsufficientPrefundingException if adjustment would result in negative balance; INSERT appropriate ledger entry type; call AuditLogService.writeEntry(prefunding.adjustment_created, metadata = {reason}); Return AdjustmentResult(newBalance, ledgerEntryId)
**Deliverable:** PrefundingService.recordAdjustment with reason validation and negative-balance guard, unit tests
**Acceptance / logic checks:**
- adjustmentAmountUsd = -600.00 on balance 500.00 throws InsufficientPrefundingException (would go negative)
- adjustmentAmountUsd = +100.00 on balance 500.00 results in balance = 600.0000 with CREDIT_TOPUP ledger entry
- adjustmentAmountUsd = -100.00 on balance 500.00 results in balance = 400.0000 with DEBIT_REVERSAL ledger entry
- reason with fewer than 10 chars throws ValidationException
- Audit log entry prefunding.adjustment_created contains the reason in metadata
**Depends on:** 10.3-T13

### 10.3-T18 — Implement PartnerQueryService: partner list and detail with prefunding balance  _(40 min)_
**Context:** The Admin System partner list view requires a paginated query returning: partner_code, name, partner_type, settle_a_ccy, status, prefunding_balance_usd (for OVERSEAS only; null for LOCAL), last_updated. The detail view adds: collection_ccy, webhook_url, rate_quote_ttl_seconds, active API key (not secret), credential created_at and last_used_at (last_used_at is updated at API authentication time per 10.3-T22), low_balance_threshold, alert_email, and prefunding ledger summary (last 5 entries). Queries must be efficient: partner list should execute in < 100 ms for up to 1000 partners.
**Steps:** Create PartnerQueryService.listPartners(PartnerListFilter, Pageable) returning Page<PartnerSummaryDTO>; Left-join partner with prefunding_account for balance (null for LOCAL); Join with partner_credential WHERE is_active = TRUE to surface active api_key (never secret); Create PartnerQueryService.getPartnerDetail(partnerId) returning PartnerDetailDTO; Include low_balance_alert_config fields and last 5 prefunding_ledger_entry rows; Write integration tests asserting balance null for LOCAL and correct for OVERSEAS
**Deliverable:** PartnerQueryService with listPartners and getPartnerDetail, integration tests
**Acceptance / logic checks:**
- listPartners returns null prefunding_balance_usd for LOCAL partner GME_REMIT
- listPartners returns correct balance for OVERSEAS partner SENDMN
- getPartnerDetail returns active api_key string but NOT api_secret_hash
- getPartnerDetail returns last 5 ledger entries ordered by created_at DESC
- Filtering listPartners by status = ACTIVE excludes ONBOARDING and SUSPENDED partners
- Page 2 of listPartners with pageSize = 10 returns partners 11-20 in stable sort order
**Depends on:** 10.3-T07, 10.3-T08, 10.3-T12

### 10.3-T19 — REST endpoint: POST /admin/partners — create partner  _(45 min)_
**Context:** Admin API endpoint for creating a new partner. Access: OPS_OPERATOR and SUPER_ADMIN only (FINANCE_ANALYST and ADMIN_VIEWER return 403). Request body fields match PartnerService.createPartner: partnerCode, name, partnerType (LOCAL|OVERSEAS), collectionCcy, settleACcy, webhookUrl (HTTPS), rateQuoteTtlSeconds (60-1800), contactEmail. For OVERSEAS partners, optional prefundingThresholdUsd (default 10000) and alertEmail. On success: 201 Created with partner id, partnerCode, status = ONBOARDING. On validation error: 400 with field-level error details. On duplicate partnerCode: 409 Conflict. Actor identity is extracted from the JWT/session.
**Steps:** Create AdminPartnerController.createPartner(@RequestBody CreatePartnerRequest, Principal) endpoint; Validate request with @Valid annotations and custom validators for partnerCode regex and webhookUrl HTTPS; Call PartnerService.createPartner(); for OVERSEAS also call PrefundingService.createAccountForPartner(); Map exceptions to HTTP responses: ValidationException -> 400, DuplicatePartnerException -> 409; Enforce RBAC: @PreAuthorize(hasRole(OPS_OPERATOR) or hasRole(SUPER_ADMIN)); Integration test the endpoint with MockMvc or TestRestTemplate
**Deliverable:** POST /admin/partners endpoint with RBAC, validation, and integration tests
**Acceptance / logic checks:**
- POST with valid LOCAL partner body returns 201 with status = ONBOARDING
- POST with http:// webhook URL returns 400 with error on field webhookUrl
- POST with duplicate partnerCode returns 409
- POST with FINANCE_ANALYST role returns 403
- POST OVERSEAS partner creates both partner and prefunding_account in same request
- Audit log entry partner.created exists after successful 201 response
**Depends on:** 10.3-T09, 10.3-T12

### 10.3-T20 — REST endpoint: PUT /admin/partners/{id}/activate — activate partner  _(35 min)_
**Context:** Endpoint for activating a partner (ONBOARDING -> ACTIVE). Access: OPS_OPERATOR and SUPER_ADMIN. Calls PartnerService.activatePartner(id). On success: 200 with updated status = ACTIVE. If pre-conditions unmet: 422 Unprocessable Entity with body listing unmet conditions, e.g. {unmetConditions: [NO_ACTIVE_CREDENTIAL, PREFUNDING_BALANCE_ZERO]}. If partner not found: 404. If partner already ACTIVE: 200 (idempotent). Actor identity from JWT.
**Steps:** Create PUT /admin/partners/{id}/activate endpoint in AdminPartnerController; Call PartnerService.activatePartner(id, actorUserId, actorIp); Map PartnerActivationException to 422 with unmetConditions list in response body; Map PartnerNotFoundException to 404; Apply RBAC: OPS_OPERATOR or SUPER_ADMIN; Integration test: activate with missing credential, activate with zero balance (OVERSEAS), successful activation
**Deliverable:** PUT /admin/partners/{id}/activate endpoint with pre-condition error mapping, integration tests
**Acceptance / logic checks:**
- Activate a LOCAL partner with no credential returns 422 with unmetConditions = [NO_ACTIVE_CREDENTIAL]
- Activate an OVERSEAS partner with balance = 0 returns 422 with unmetConditions = [PREFUNDING_BALANCE_ZERO]
- Activate a fully configured LOCAL partner returns 200 with status = ACTIVE
- Activating an already-ACTIVE partner returns 200 (idempotent, no error)
- Calling with ADMIN_VIEWER role returns 403
- Audit log entry partner.activated exists after 200 response
**Depends on:** 10.3-T10, 10.3-T19

### 10.3-T21 — REST endpoint: POST /admin/partners/{id}/credentials — generate API credentials  _(35 min)_
**Context:** Endpoint for generating a new API key+secret for a partner. Access: OPS_OPERATOR and SUPER_ADMIN. Calls PartnerCredentialService.generateCredentials(). Response body: {apiKey: string, apiSecret: string (plaintext, shown ONCE), createdAt: ISO-8601}. The response must include a prominent warning header or body note: apiSecret is shown only once and cannot be retrieved. On success: 201. If partner not found: 404. If partner is SUSPENDED: 403. Any existing active credential is automatically deactivated.
**Steps:** Create POST /admin/partners/{id}/credentials endpoint; Call PartnerCredentialService.generateCredentials(partnerId, actorUserId, actorIp); Return 201 with CredentialIssuanceResponse: {apiKey, apiSecret, warningMessage: Copy this secret now — it will not be shown again, createdAt}; Map PartnerNotFoundException to 404, PartnerNotEligibleException to 403; Apply RBAC: OPS_OPERATOR or SUPER_ADMIN; Integration test: verify apiSecret not retrievable after first response
**Deliverable:** POST /admin/partners/{id}/credentials endpoint with one-time secret disclosure, integration tests
**Acceptance / logic checks:**
- 201 response includes both apiKey and apiSecret in plaintext
- Calling the endpoint twice: second call returns a new apiKey and apiSecret; querying DB shows only one active credential (the new one)
- GET /admin/partners/{id}/credentials (view endpoint per 10.3-T18) returns apiKey but NOT apiSecret
- Calling for a SUSPENDED partner returns 403
- Audit log entry partner.credential_generated with entity_id = partner_id exists after 201
- ADMIN_VIEWER role returns 403
**Depends on:** 10.3-T08, 10.3-T19

### 10.3-T22 — REST endpoint: DELETE /admin/partners/{id}/credentials — revoke credentials  _(30 min)_
**Context:** Endpoint for revoking the active API credential of a partner. After revocation, the partner cannot authenticate API calls until new credentials are generated (10.3-T21). Access: OPS_OPERATOR and SUPER_ADMIN. Requires an explicit confirmation flag in request body: {confirm: true}. On success: 200 with {status: revoked, revokedAt: ISO-8601}. If no active credential exists: 404. If partner not found: 404. Audit event: partner.credential_revoked.
**Steps:** Create DELETE /admin/partners/{id}/credentials endpoint (or POST /admin/partners/{id}/credentials/revoke if DELETE body not supported by proxy); Require confirm = true in request body; return 400 if absent or false; Call PartnerCredentialService.revokeCredentials(partnerId, actorUserId, actorIp); Map no-active-credential case to 404 with message: no active credential found for this partner; Apply RBAC: OPS_OPERATOR or SUPER_ADMIN; Integration test: revoke, then attempt partner API auth should fail
**Deliverable:** Revoke-credential endpoint with confirmation gate, integration tests
**Acceptance / logic checks:**
- Request without confirm = true returns 400
- Successful revoke returns 200 with revokedAt timestamp
- After revoke, attempting partner API authentication with the old credential returns 401
- Revoking when no active credential exists returns 404
- Audit log entry partner.credential_revoked exists after successful revoke
- ADMIN_VIEWER returns 403
**Depends on:** 10.3-T08, 10.3-T19

### 10.3-T23 — REST endpoint: POST /admin/partners/{id}/prefunding/topups — record top-up  _(35 min)_
**Context:** Endpoint for recording a prefunding top-up. Access: OPS_OPERATOR and SUPER_ADMIN (Finance Analyst can also view but not record; RBAC enforced). Request: {amountUsd: decimal, bankRef: string required, valueDate: date YYYY-MM-DD, notes: string optional}. Calls PrefundingService.recordTopUp(). On success: 201 with {newBalanceUsd, ledgerEntryId, recordedAt}. If partner is LOCAL (no prefunding account): 422 with message: LOCAL partners do not have prefunding accounts. If amountUsd <= 0: 400. Audit event: prefunding.topup_recorded.
**Steps:** Create POST /admin/partners/{id}/prefunding/topups endpoint; Validate amountUsd > 0 and bankRef non-empty; Call PrefundingService.recordTopUp(partnerId, request, actorUserId, actorIp); Map AccountNotFoundException (LOCAL partner) to 422; Return 201 with TopUpResponse including new balance; Apply RBAC: OPS_OPERATOR or SUPER_ADMIN for writes
**Deliverable:** POST /admin/partners/{id}/prefunding/topups endpoint with validation, integration tests
**Acceptance / logic checks:**
- POST with amountUsd = -50 returns 400
- POST for a LOCAL partner returns 422 with appropriate message
- Successful POST returns 201 with newBalanceUsd = previous balance + amountUsd
- Audit log entry prefunding.topup_recorded exists after 201
- Concurrent POST requests (2x100.00 on balance 500.00) result in final balance = 700.00 (atomicity check in integration test)
- FINANCE_ANALYST role returns 403 for this write endpoint
**Depends on:** 10.3-T13, 10.3-T19

### 10.3-T24 — REST endpoint: GET /admin/partners/{id}/prefunding/ledger — ledger history  _(35 min)_
**Context:** Paginated ledger view for a partner's prefunding account. Access: OPS_OPERATOR, SUPER_ADMIN, and FINANCE_ANALYST (read access per RBAC matrix in PRD-07 §12.3). Returns paginated list of ledger entries ordered by created_at DESC. Each entry includes: id, entry_type, amount, balance_after, txn_ref (nullable), note, created_at, created_by. Default page size 20; max 100. Filter by entry_type (optional) and date range (optional). Returns 200 with Page<LedgerEntryDTO>. If partner is LOCAL: 404 (no account). If partner not found: 404.
**Steps:** Create GET /admin/partners/{id}/prefunding/ledger endpoint; Support query params: entryType (optional), fromDate, toDate, page, size; Call PartnerQueryService or PrefundingService.getLedgerHistory(partnerId, filter, pageable); Apply RBAC: allow OPS_OPERATOR, SUPER_ADMIN, FINANCE_ANALYST; Return 404 for LOCAL partners or unknown partner id; Integration test with multiple entry types and date filters
**Deliverable:** GET /admin/partners/{id}/prefunding/ledger endpoint with filtering and pagination, integration tests
**Acceptance / logic checks:**
- Returns entries ordered by created_at DESC
- Filter by entryType = CREDIT_TOPUP returns only top-up entries
- ADMIN_VIEWER role returns 403
- GET for LOCAL partner returns 404
- Pagination: page 0 size 2 returns 2 entries; page 1 returns the next 2
- Date range filter fromDate = 2026-01-01 toDate = 2026-01-31 returns only entries in January 2026
**Depends on:** 10.3-T18, 10.3-T23

### 10.3-T25 — REST endpoint: GET /admin/partners and GET /admin/partners/{id} — list and detail  _(35 min)_
**Context:** List endpoint: GET /admin/partners returns Page<PartnerSummaryDTO> (partnerCode, name, partnerType, settleACcy, status, prefundingBalanceUsd, lastUpdated). Filter params: status, partnerType, search (name or code partial). Calls PartnerQueryService.listPartners(). Detail endpoint: GET /admin/partners/{id} returns PartnerDetailDTO including all identity, settlement, credential (apiKey only), and prefunding summary fields. Access for both: OPS_OPERATOR, SUPER_ADMIN, FINANCE_ANALYST (read). ADMIN_VIEWER gets 403 per permission matrix. Return 404 if id not found.
**Steps:** Create GET /admin/partners endpoint with filter and pagination params; Create GET /admin/partners/{id} endpoint; Call PartnerQueryService.listPartners and getPartnerDetail respectively; Apply RBAC: allow OPS_OPERATOR, SUPER_ADMIN, FINANCE_ANALYST; Return 404 for unknown id; Integration tests with various filter combinations
**Deliverable:** GET /admin/partners and GET /admin/partners/{id} endpoints with RBAC, integration tests
**Acceptance / logic checks:**
- GET /admin/partners?status=ACTIVE returns only ACTIVE partners
- GET /admin/partners?partnerType=OVERSEAS returns only OVERSEAS partners
- GET /admin/partners/{id} returns apiKey but no api_secret_hash field in response
- GET /admin/partners/{nonexistent} returns 404
- ADMIN_VIEWER role returns 403 on both endpoints
- prefundingBalanceUsd is null in summary for LOCAL partner and a decimal value for OVERSEAS
**Depends on:** 10.3-T18, 10.3-T19

### 10.3-T26 — Unit tests: PartnerService validation edge cases  _(40 min)_
**Context:** Exhaustive unit tests for all validation rules in PartnerService (10.3-T09, T10, T11). Test class PartnerServiceValidationTest. Use Mockito to stub PartnerRepository and AuditLogService. Test vectors must cover: (A) createPartner validations; (B) activatePartner pre-condition combinations; (C) updatePartner immutability after activation.
**Steps:** Write test cases A1-A5 for createPartner: partnerCode with hyphen, non-HTTPS webhook, LOCAL with mismatched currencies, duplicate code, valid LOCAL, valid OVERSEAS; Write test cases B1-B4 for activatePartner: no credential, no prefunding account (OVERSEAS), zero balance (OVERSEAS), all conditions met (LOCAL), all conditions met (OVERSEAS); Write test cases C1-C2 for updatePartner immutability: change partnerType after ACTIVE throws ImmutableFieldException, change name after ACTIVE succeeds; Run all tests; assert 0 failures
**Deliverable:** PartnerServiceValidationTest class with minimum 11 named test cases covering all rules above
**Acceptance / logic checks:**
- A1: partnerCode GME-REMIT throws ValidationException with message containing partnerCode
- A3: LOCAL partner with collectionCcy = MNT and settleACcy = KRW throws ValidationException
- B1: activatePartner with no credential returns unmet list containing NO_ACTIVE_CREDENTIAL
- B3: activatePartner OVERSEAS with balance = 0 returns unmet list containing PREFUNDING_BALANCE_ZERO
- C1: updating partnerType after ACTIVE throws ImmutableFieldException
- All 11+ tests pass with 0 failures in CI
**Depends on:** 10.3-T09, 10.3-T10, 10.3-T11

### 10.3-T27 — Unit tests: PrefundingService atomicity and edge cases  _(55 min)_
**Context:** Unit and integration tests for PrefundingService (10.3-T13, T14, T15, T17). Focus on: atomicity of top-up and deduction under concurrency, insufficient balance rejection, deduction reversal idempotency, and negative-balance guard on adjustments. Use Testcontainers (real PostgreSQL) for concurrency tests.
**Steps:** Write PrefundingServiceAtomicityTest using Testcontainers for a real PostgreSQL instance; Test concurrent top-ups: 10 threads each adding 100.00 on initial balance 0 -> expect final balance = 1000.00; Test concurrent deductions: 10 threads each deducting 60.00 on balance 500.00 -> expect 8 succeed, 2 fail with InsufficientPrefundingException and final balance = 20.00; Test reversal idempotency: call reverseDeduction 5 times with same txnRef -> exactly 1 ledger row, balance credited once; Test adjustment negative-balance guard: adjustment of -600.00 on balance 500.00 throws exception
**Deliverable:** PrefundingServiceAtomicityTest class with concurrency and idempotency tests against real PostgreSQL
**Acceptance / logic checks:**
- Concurrent top-ups test: final balance equals thread_count * amount with no lost updates
- Concurrent deductions test: number of successful deductions = floor(500.00 / 60.00) = 8; final balance = 500.00 - 8*60.00 = 20.00
- Reversal idempotency: exactly one DEBIT_REVERSAL ledger entry after 5 calls with same txnRef
- Adjustment -600 on balance 500 throws InsufficientPrefundingException; balance unchanged
- All tests pass in CI with Testcontainers PostgreSQL 15
**Depends on:** 10.3-T13, 10.3-T14, 10.3-T15, 10.3-T17

### 10.3-T28 — Unit tests: PartnerCredentialService key generation and bcrypt  _(40 min)_
**Context:** Unit tests for PartnerCredentialService (10.3-T08). Verify: (1) api_key format (32 hex chars, no dashes); (2) api_secret format (base64url, 44 chars); (3) bcrypt hash of returned plaintext matches DB hash; (4) single-active-credential invariant under concurrent generation; (5) revoke sets is_active = FALSE.
**Steps:** Write PartnerCredentialServiceTest with Mockito stubs for repository and audit; Test generateCredentials returns api_key matching [a-f0-9]{32} regex; Test BCrypt.checkpw(returnedPlaintext, storedHash) == true; Test that generating credentials when one active credential exists: call verify is_active = FALSE on old, new row is_active = TRUE; Test revokeCredentials: sets is_active = FALSE on the active credential; Test generateCredentials for SUSPENDED partner throws PartnerNotEligibleException
**Deliverable:** PartnerCredentialServiceTest class with 6 named test cases
**Acceptance / logic checks:**
- api_key matches [a-f0-9]{32}
- api_secret is 44 chars and valid base64url
- BCrypt.checkpw(returnedSecret, storedHash) returns true in all test cases
- After generateCredentials, only one row with is_active = TRUE for the partner
- revokeCredentials leaves zero active credentials for the partner
- SUSPENDED partner test throws PartnerNotEligibleException
**Depends on:** 10.3-T08

### 10.3-T29 — Unit tests: LowBalanceAlertService threshold and deduplication  _(35 min)_
**Context:** Unit tests for LowBalanceAlertService (10.3-T16). Use Mockito to stub EmailSendPort. Test all alert thresholds and the 1-hour deduplication logic.
**Steps:** Write LowBalanceAlertServiceTest with mocked EmailSendPort and mocked PrefundingAccountRepository; Test: balance 8500 with threshold 10000 -> EmailSendPort.send called once with correct addresses and suggested top-up = 1500.00; Test: balance 15000 with threshold 10000 -> no email sent; Test: balance 0 -> urgent email sent AND PartnerService.suspendPartner called; Test deduplication: first call sends email; second call within 1 hour suppressed; call after 1 hour sends again (mock the clock); Test comma-separated alert_email: partner with alert_email = ops@gme.com,finance@gme.com -> EmailSendPort.send called for each address
**Deliverable:** LowBalanceAlertServiceTest class with 5 named test cases, all passing
**Acceptance / logic checks:**
- EmailSendPort.send called with suggested top-up = (threshold - balance) = 1500.00 when balance = 8500
- No email when balance > threshold
- balance = 0: suspendPartner called with reason = ZERO_BALANCE
- Deduplication: only one email sent for two calls within 1 hour
- Comma-separated emails: both ops@gme.com and finance@gme.com receive separate calls to EmailSendPort.send
**Depends on:** 10.3-T16

### 10.3-T30 — Admin UI: Partner list page (read-only display)  _(55 min)_
**Context:** React (or the project front-end framework) page at /admin/partners. Displays a table with columns: Partner Name, Partner Code, Type (LOCAL|OVERSEAS badge), Settlement Currency, Status (coloured badge: ONBOARDING=grey, ACTIVE=green, SUSPENDED=red), Prefunding Balance (USD for OVERSEAS; dash for LOCAL), Last Updated. Fetches from GET /admin/partners with filter params mapped to query parameters. Supports filters: Status (multi-select), Type (multi-select), Search (text input, debounced 300ms). Pagination with 20 rows per page. Actions per row: View, Manage (Edit/Activate/Suspend). Prefunding balance shown as USD 12,345.67; red text if below threshold.
**Steps:** Create PartnerListPage component fetching GET /admin/partners; Implement filter bar with Status and Type multi-selects and a debounced search input; Render table with all 7 columns; format balance as USD 12,345.67 or dash; Show balance in red text when balance < low_balance_threshold; Implement pagination controls; Add row actions: View links to /admin/partners/{id}; Manage opens edit modal or links to edit page
**Deliverable:** PartnerListPage component with filters, pagination, balance color indicator, and row actions; renders correctly in Storybook or with mock API
**Acceptance / logic checks:**
- Table renders 20 rows per page; Next button fetches page 2
- LOCAL partner row shows dash in Prefunding Balance column
- OVERSEAS partner with balance 8500 and threshold 10000 shows USD 8,500.00 in red
- Status filter ACTIVE hides ONBOARDING and SUSPENDED rows
- Search input debounces: typing fast sends only one request after 300ms pause
- All 7 columns present with correct labels
**Depends on:** 10.3-T25

### 10.3-T31 — Admin UI: Create partner form — identity and settlement sections  _(55 min)_
**Context:** Form at /admin/partners/new. Sections: (1) Identity: Partner Name (text, required, max 100), Partner Code (text, uppercase alphanumeric + underscore, max 20, unique-validated on blur), Partner Type (LOCAL|OVERSEAS radio, immutable after activation — show tooltip), Contact Email (email, required), Webhook URL (HTTPS, required), Rate Quote TTL (integer 60-1800, default 300). (2) Settlement: Collection Currency (select from currency registry), Settlement A Currency (auto-set to same as Collection if LOCAL; editable for OVERSEAS). For OVERSEAS, show Prefunding section (10.3-T32). On submit call POST /admin/partners; show field errors from 400 response inline; show 409 as banner error on Partner Code field.
**Steps:** Create CreatePartnerForm component with react-hook-form or equivalent; Implement Section 1 fields with inline validation (regex on Partner Code, HTTPS on Webhook URL); Implement Section 2 Settlement fields; auto-set Settle A = Collection for LOCAL on partnerType change; Implement submit handler calling POST /admin/partners; map 400 errors to field-level messages; Map 409 to banner on Partner Code field: This code is already in use; Storybook/unit tests for each validation state
**Deliverable:** CreatePartnerForm component with identity and settlement sections; validation and API error display
**Acceptance / logic checks:**
- Entering partner code GME-REMIT (hyphen) shows inline error: only letters, numbers, and underscores
- Entering http:// webhook URL shows inline error: must be HTTPS
- Selecting LOCAL auto-sets Settlement A to match Collection Currency and disables the field
- 409 response shows banner error on Partner Code field
- Valid form submission calls POST /admin/partners with correct JSON body
- Partner Type radio shows tooltip: cannot be changed after activation
**Depends on:** 10.3-T19, 10.3-T30

### 10.3-T32 — Admin UI: Create partner form — prefunding setup section (OVERSEAS only)  _(40 min)_
**Context:** This section is visible only when Partner Type = OVERSEAS. Fields: Prefunding Account ID (text, required, auto-generated suggestion = PFUND-{PARTNERCODE}), Initial Prefunding Balance (decimal USD, >= 0, required for activation but can be 0 at creation), Low-Balance Alert Threshold (decimal USD, default 10000, >= 0), Alert Recipients (comma-separated emails, required minimum 1, validated format). Section is hidden and its fields excluded from form submission when Partner Type = LOCAL.
**Steps:** Add conditional prefunding section to CreatePartnerForm (visible only when partnerType = OVERSEAS); Implement Prefunding Account ID with auto-suggested value PFUND-{PARTNERCODE} that updates as user types code; Implement alert threshold with default 10000; validate >= 0; Implement Alert Recipients with email-list validation (split by comma, validate each as email); Exclude section entirely from form payload when partnerType = LOCAL; Unit test: switch partnerType from OVERSEAS to LOCAL -> section disappears and fields excluded from payload
**Deliverable:** Prefunding section integrated into CreatePartnerForm; conditional visibility and email-list validation
**Acceptance / logic checks:**
- Section invisible when partnerType = LOCAL
- Section visible when partnerType = OVERSEAS
- Alert Recipients field rejects non-email entries: ops@gme.com,not-an-email shows validation error
- Prefunding Account ID auto-suggests PFUND-SENDMN when partner code is SENDMN
- Switching from OVERSEAS to LOCAL removes prefundingThresholdUsd from submitted JSON body
- Default threshold = 10000 is pre-filled when section becomes visible
**Depends on:** 10.3-T31

### 10.3-T33 — Admin UI: Partner detail page — identity, credential, and action buttons  _(55 min)_
**Context:** Page at /admin/partners/{id}. Fetches GET /admin/partners/{id}. Displays: (1) Identity section (all fields, read-only with Edit button for name/webhook/ttl); (2) API Credentials section showing apiKey and createdAt (no secret); button Generate New Credentials (POST /admin/partners/{id}/credentials) shows confirmation modal; button Revoke Credentials (DELETE) shows confirmation modal with warning This will immediately block partner API access; (3) Activation action buttons: Activate (if ONBOARDING), Suspend (if ACTIVE), Reactivate (if SUSPENDED). Credential generation shows a one-time modal with the apiSecret and a Copy button; modal warns Copy and store the secret now — it cannot be retrieved again.
**Steps:** Create PartnerDetailPage fetching GET /admin/partners/{id}; Render identity section with inline Edit button opening an edit drawer; Render API Credentials section with apiKey and Generate/Revoke buttons; Implement Generate modal: call POST, display apiSecret in one-time modal with Copy button; modal does not re-show on close; Implement Revoke modal: require confirmation text before enabling Revoke button; Render status-dependent action button (Activate/Suspend/Reactivate) and call corresponding endpoints
**Deliverable:** PartnerDetailPage with credential management and status action buttons; Storybook stories for ONBOARDING, ACTIVE, SUSPENDED states
**Acceptance / logic checks:**
- Generate New Credentials modal shows apiSecret exactly once; closing and reopening does not show secret again
- Revoke Credentials button disabled until user types CONFIRM in confirmation input
- Activate button visible only when status = ONBOARDING; Suspend only when ACTIVE; Reactivate only when SUSPENDED
- apiKey is displayed; api_secret_hash is not present in page HTML or network response
- After Suspend action, page re-fetches and shows SUSPENDED badge
- Activate 422 response displays list of unmet conditions to the operator
**Depends on:** 10.3-T20, 10.3-T21, 10.3-T22, 10.3-T25

### 10.3-T34 — Admin UI: Prefunding balance panel and top-up form  _(50 min)_
**Context:** On the partner detail page (OVERSEAS only), render a Prefunding panel showing: current balance (USD), low-balance threshold, traffic-light indicator (green >= 150% threshold, amber 100-149%, red < 100%), last top-up date and amount, last deduction date and amount. Record Top-Up button opens a modal form: Amount (USD, > 0), Bank Reference (required), Value Date (date picker), Notes (optional). On submit call POST /admin/partners/{id}/prefunding/topups; show new balance in panel on success. Ledger link opens GET /admin/partners/{id}/prefunding/ledger in a paginated side-panel.
**Steps:** Add Prefunding panel to PartnerDetailPage (visible only when partner_type = OVERSEAS); Render balance with traffic-light icon based on balance / threshold ratio; Implement Record Top-Up modal with validation (amount > 0, bankRef required); On submit call POST topups endpoint; update balance display on 201 response; Add Ledger button opening paginated ledger side-panel calling GET /admin/partners/{id}/prefunding/ledger; Test traffic-light thresholds: balance 15000 threshold 10000 = green (150%); balance 10500 threshold 10000 = amber (105%); balance 9000 threshold 10000 = red (90%)
**Deliverable:** Prefunding panel component with top-up modal, traffic-light indicator, and ledger side-panel; unit tests for traffic-light logic
**Acceptance / logic checks:**
- balance 15000 / threshold 10000 = 150%: green indicator
- balance 10500 / threshold 10000 = 105%: amber indicator
- balance 9000 / threshold 10000 = 90%: red indicator
- Top-up with amount = 0 shows validation error; amount = 100.00 succeeds and updates balance display
- Ledger side-panel renders entries sorted by created_at DESC
- Panel is not rendered for LOCAL partners
**Depends on:** 10.3-T23, 10.3-T24, 10.3-T33

### 10.3-T35 — Admin UI: Audit log view for partner events  _(55 min)_
**Context:** On the partner detail page, add an Audit Log tab showing all audit events where entity_type = partner AND entity_id = {partnerId}. Also include related events: entity_type = partner_credential AND entity_id = any credential for this partner, and entity_type = prefunding_account. Table columns: Timestamp (UTC), Actor, Event Type (human-readable label), Field Changed, Previous Value, New Value. Sorted by timestamp DESC, paginated 20 per page. Export CSV button calls backend CSV export. Access: OPS_OPERATOR and SUPER_ADMIN only (FINANCE_ANALYST and ADMIN_VIEWER cannot see audit log per PRD-07 §12.3).
**Steps:** Add Audit Log tab to PartnerDetailPage; Fetch audit entries via GET /admin/partners/{id}/audit (new endpoint below) with pagination; Render table with 6 columns; format event_type codes as human-readable labels (e.g. partner.activated -> Partner Activated); Implement CSV export button triggering download; Hide tab for FINANCE_ANALYST and ADMIN_VIEWER (check role in UI state); Create GET /admin/partners/{id}/audit backend endpoint returning paginated audit entries for the partner and its credentials/prefunding
**Deliverable:** Audit Log tab on partner detail page with CSV export; backend GET /admin/partners/{id}/audit endpoint; RBAC hiding for non-authorized roles
**Acceptance / logic checks:**
- Tab visible for OPS_OPERATOR; not visible for FINANCE_ANALYST
- Entries include partner.credential_generated events with partner credential entity
- CSV export downloads file with correct headers: timestamp,actor,eventType,entityType,entityId,previousValue,newValue
- partner.activated event shows previousValue = {status: ONBOARDING} and newValue = {status: ACTIVE}
- Pagination: page 0 size 20 fetches first 20 entries; next page fetches correctly
- Events sorted by timestamp DESC (newest first)
**Depends on:** 10.3-T06, 10.3-T33

### 10.3-T36 — E2E integration test: full partner onboarding flow (LOCAL)  _(55 min)_
**Context:** End-to-end test simulating the complete onboarding of a LOCAL partner (GME_REMIT_TEST) using the Admin API. Flow: (1) POST /admin/partners -> ONBOARDING; (2) POST /admin/partners/{id}/credentials -> get apiKey+apiSecret; (3) PUT /admin/partners/{id}/activate -> expect 422 NO_ACTIVE_CREDENTIAL is NOT in unmet (credential just generated); (4) confirm status = ACTIVE; (5) verify partner appears in GET /admin/partners list with status ACTIVE; (6) verify audit log has partner.created, partner.credential_generated, partner.activated entries in order. Use a dedicated test PostgreSQL database (Testcontainers). Clean up after test.
**Steps:** Create PartnerOnboardingLocalE2ETest using Spring Boot test context and Testcontainers; Execute the 6-step flow against the running application; Assert each step response code and body; Query the audit_log table directly to verify event sequence; Assert no prefunding_account row exists for the LOCAL partner; Tear down by deleting the test partner or rolling back the transaction
**Deliverable:** PartnerOnboardingLocalE2ETest class with 6 sequential assertions, passing in CI
**Acceptance / logic checks:**
- Step 1: POST returns 201 with status = ONBOARDING
- Step 2: POST credentials returns 201 with apiKey (32 chars) and apiSecret (44 chars)
- Step 3: PUT activate returns 200 with status = ACTIVE (all pre-conditions met)
- Step 4: GET /admin/partners/{id} shows status = ACTIVE and is_active = TRUE
- Step 5: GET /admin/partners?status=ACTIVE includes the new partner
- Step 6: audit_log contains exactly 3 events in order: partner.created, partner.credential_generated, partner.activated
**Depends on:** 10.3-T19, 10.3-T20, 10.3-T21, 10.3-T25

### 10.3-T37 — E2E integration test: full partner onboarding flow (OVERSEAS with prefunding)  _(55 min)_
**Context:** End-to-end test for OVERSEAS partner (SENDMN_TEST). Flow: (1) POST /admin/partners OVERSEAS with threshold 10000, alertEmail = test@gme.com -> ONBOARDING + prefunding_account created with balance 0; (2) attempt activate -> expect 422 with unmet = [NO_ACTIVE_CREDENTIAL, PREFUNDING_BALANCE_ZERO]; (3) POST credentials; (4) attempt activate -> expect 422 with unmet = [PREFUNDING_BALANCE_ZERO]; (5) POST top-up 50000.00; (6) PUT activate -> 200 ACTIVE; (7) simulate deduction of 45000.00 -> balance = 5000 < threshold 10000 -> low-balance alert email triggered; (8) verify audit log sequence.
**Steps:** Create PartnerOnboardingOverseasE2ETest with Testcontainers and mocked EmailSendPort; Execute 8-step flow sequentially; Assert each step response and DB state; After step 7: verify EmailSendPort.send was called with suggested top-up = 5000.00 and correct recipient; After step 7: verify balance = 5000.00 in prefunding_account; Verify audit log has 6 events in order: partner.created, prefunding.account_created, partner.credential_generated, prefunding.topup_recorded, partner.activated, prefunding.deduction_reversed (if applicable)
**Deliverable:** PartnerOnboardingOverseasE2ETest class with 8 sequential assertions, passing in CI
**Acceptance / logic checks:**
- Step 2: 422 response lists both NO_ACTIVE_CREDENTIAL and PREFUNDING_BALANCE_ZERO
- Step 4: 422 response lists only PREFUNDING_BALANCE_ZERO (credential now exists)
- Step 6: 200 response with status = ACTIVE after top-up
- Step 7: balance = 5000.0000 in prefunding_account after deduction of 45000.00
- Step 7: EmailSendPort.send called with suggested top-up = 5000.00 (threshold 10000 - balance 5000)
- Audit log contains prefunding.account_created event immediately after partner.created
**Depends on:** 10.3-T14, 10.3-T16, 10.3-T36


## WBS 10.4 — Rule/Mapping page — 4 sections
### 10.4-T01 — Add rule table DB migration: currency quadruple, rate sources, margins, service charge  _(35 min)_
**Context:** The rule table is the join record (partner_id, scheme_id, direction). It stores the full currency quadruple auto-derived at creation (collection_ccy, settle_a_ccy, settle_b_ccy, payout_ccy), two rate source slots (rate_coll_source, rate_pay_source each CHECK IN ('IDENTITY','LIVE','MANUAL','PARTNER')), optional manual overrides (manual_rate_coll DECIMAL(20,8), manual_rate_pay DECIMAL(20,8)), override expiry dates (rate_coll_override_expires DATE, rate_pay_override_expires DATE), margins m_a and m_b (DECIMAL(8,6) DEFAULT 0 CHECK >= 0), service_charge_amount DECIMAL(20,4) DEFAULT 0, service_charge_ccy CHAR(3) must equal settle_a_ccy, is_same_ccy_shortcircuit BOOLEAN computed on save, status VARCHAR(20) CHECK IN ('DRAFT','ACTIVE','SUSPENDED'), effective_from TIMESTAMPTZ, standard audit cols (created_at, updated_at, created_by, updated_by). DB-level CHECK: is_same_ccy_shortcircuit OR (m_a + m_b) >= 0.02. UNIQUE constraint (partner_id, scheme_id, direction).
**Steps:** Create Flyway/Liquibase migration file V10_4_001__create_rule_table.sql; Add all columns with types, NOT NULL constraints, and CHECK constraints as specified; Add UNIQUE INDEX on (partner_id, scheme_id, direction); Add FK constraints to partner and qr_scheme tables; Add DB-level CHECK constraint: is_same_ccy_shortcircuit = TRUE OR (m_a + m_b) >= 0.02
**Deliverable:** Migration file V10_4_001__create_rule_table.sql that applies cleanly on a fresh schema
**Acceptance / logic checks:**
- Migration applies without error on empty schema; table has all 20+ columns with correct types
- UNIQUE constraint rejects duplicate (partner_id=1, scheme_id=1, direction='INBOUND') insert
- DB CHECK rejects INSERT with is_same_ccy_shortcircuit=false, m_a=0.005, m_b=0.010 (sum=1.5% < 2%)
- DB CHECK accepts INSERT with is_same_ccy_shortcircuit=true, m_a=0, m_b=0
- manual_rate_coll column accepts NULL when rate_coll_source = 'LIVE'

### 10.4-T02 — Add service_charge_tier table DB migration  _(20 min)_
**Context:** The service_charge_tier table provides optional volume-tier overrides for service charge per rule. Columns: id BIGINT PK auto-increment, rule_id BIGINT FK -> rule NOT NULL, min_collection_usd DECIMAL(20,4) NOT NULL (lower bound inclusive), max_collection_usd DECIMAL(20,4) NULL (upper bound exclusive; NULL = no cap), charge_amount DECIMAL(20,4) NOT NULL CHECK >= 0, standard audit cols (created_at, updated_at, created_by, updated_by). If no tier rows exist for a rule, the flat rule.service_charge_amount applies. Tiers are indexed by collection_usd (the USD pool amount before margin deduction).
**Steps:** Create migration file V10_4_002__create_service_charge_tier_table.sql; Add FK to rule table with ON DELETE CASCADE; Add CHECK constraint: charge_amount >= 0; Add CHECK constraint: max_collection_usd IS NULL OR max_collection_usd > min_collection_usd; Add index on rule_id for efficient tier lookup at transaction time
**Deliverable:** Migration file V10_4_002__create_service_charge_tier_table.sql
**Acceptance / logic checks:**
- FK ON DELETE CASCADE removes tier rows when parent rule is deleted
- CHECK rejects row with max_collection_usd=100 and min_collection_usd=200
- CHECK rejects negative charge_amount
- NULL max_collection_usd is accepted (open-ended top tier)
- Index on rule_id exists in EXPLAIN plan for SELECT WHERE rule_id = ?
**Depends on:** 10.4-T01

### 10.4-T03 — Add rule_audit_log table DB migration  _(20 min)_
**Context:** Every field-level change on the mapping page must be audit-logged with: rule_id, event type ('rule_field_changed'), actor_user_id VARCHAR, timestamp_utc TIMESTAMPTZ, field_name VARCHAR(80), previous_value TEXT, new_value TEXT, rule_key JSON (partner_code, scheme_code, direction). Audit rows are immutable (no UPDATE/DELETE). This table feeds the Rule Audit History view. Per PRD-07 §6.4.5 the audit event format is: {event, actor_user_id, timestamp_utc, rule_key:{partner, scheme, direction}, field, previous_value, new_value}.
**Steps:** Create migration V10_4_003__create_rule_audit_log_table.sql; Include columns: id BIGINT PK, rule_id BIGINT FK (nullable so records survive rule deletion), event VARCHAR(60) DEFAULT 'rule_field_changed', actor_user_id VARCHAR(120), timestamp_utc TIMESTAMPTZ NOT NULL DEFAULT NOW(), field_name VARCHAR(80), previous_value TEXT, new_value TEXT, rule_key JSONB; Add index on rule_id for history view queries; Add index on timestamp_utc for time-range searches; Do NOT add any UPDATE or DELETE privileges in migration comments
**Deliverable:** Migration file V10_4_003__create_rule_audit_log_table.sql
**Acceptance / logic checks:**
- Table created with all required columns and correct types
- Index on rule_id present
- Row inserted for field_name='m_a', previous_value='0.010000', new_value='0.012500' is retrievable
- No DB-level UPDATE or DELETE constraint violation on attempts to modify existing rows (verify no ON UPDATE trigger exists)
- JSONB rule_key column accepts valid JSON and rejects malformed strings
**Depends on:** 10.4-T01

### 10.4-T04 — Implement RuleRepository: CRUD + currency quadruple auto-derivation on create  _(40 min)_
**Context:** When a rule is created via the Admin portal, the system must auto-derive the 4 currency fields from the partner and scheme profiles: collection_ccy <- partner.collection_currency, settle_a_ccy <- partner.settlement_currency, settle_b_ccy <- qr_scheme.settlement_currency, payout_ccy <- qr_scheme.payout_currency. The is_same_ccy_shortcircuit flag must be computed on save: true iff collection_ccy = settle_a_ccy = settle_b_ccy = payout_ccy. The effective_from timestamp is set to NOW() on every save. Repository must also support findByPartnerSchemeDirection (primary key lookup) and findAll for list view.
**Steps:** Create RuleRepository interface with methods: save(Rule), findById(Long), findByPartnerSchemeDirection(Long partnerId, Long schemeId, String direction), findAll(); Implement JPA/JDBC mapping to the rule table; On create, join to partner and qr_scheme to populate the 4 currency fields before insert; Compute is_same_ccy_shortcircuit = (collection_ccy.equals(settle_a_ccy) && settle_a_ccy.equals(settle_b_ccy) && settle_b_ccy.equals(payout_ccy)); Set effective_from = Instant.now() on every save; do not allow caller to override this field
**Deliverable:** RuleRepository class and Rule entity with auto-derivation logic
**Acceptance / logic checks:**
- Creating a rule for GME Remit (collect=KRW, settleA=KRW) on ZeroPay (settleB=KRW, payout=KRW) sets is_same_ccy_shortcircuit=true
- Creating a rule for SendMN (collect=MNT, settleA=USD) on ZeroPay (settleB=KRW, payout=KRW) sets is_same_ccy_shortcircuit=false
- collection_ccy is populated from partner, not from caller input
- findByPartnerSchemeDirection returns empty Optional for non-existent (partnerId=99, schemeId=1, direction='INBOUND')
- effective_from is set to current UTC on every save call
**Depends on:** 10.4-T01

### 10.4-T05 — Implement RuleService: rate source auto-resolution logic  _(35 min)_
**Context:** Each rule has two rate source slots. Source type must be auto-resolved by the system at create/load time (not stored manually by operator): IDENTITY if settle_a_ccy='USD' (coll leg) or settle_b_ccy='USD' (pay leg); LIVE if settle ccy != USD and no operator MANUAL override is in effect; MANUAL if operator has set an override and the override has not expired; PARTNER if the qr_scheme.rate_source_pay='PARTNER' (scheme-level config). The operator can only switch a LIVE leg to MANUAL — they cannot override IDENTITY or PARTNER. For same-currency short-circuit rules, both slots must be IDENTITY regardless.
**Steps:** Create RuleService.resolveRateSource(Rule rule, String leg) that returns RateSourceType enum; For leg='coll': if settle_a_ccy='USD' -> IDENTITY; elif scheme configured PARTNER for coll -> PARTNER; elif manual_rate_coll is set and (rate_coll_override_expires IS NULL OR rate_coll_override_expires >= today) -> MANUAL; else -> LIVE; For leg='pay': if settle_b_ccy='USD' -> IDENTITY; elif scheme.rate_source_pay='PARTNER' -> PARTNER; elif manual_rate_pay is set and not expired -> MANUAL; else -> LIVE; Override same-currency short-circuit: if is_same_ccy_shortcircuit=true, both legs return IDENTITY; Call resolveRateSource on every rule load so the displayed badge is always current
**Deliverable:** RuleService.resolveRateSource(Rule, String) method returning RateSourceType
**Acceptance / logic checks:**
- Rule with settle_a_ccy='USD' returns IDENTITY for coll leg regardless of any manual_rate_coll value
- Rule with settle_a_ccy='MNT', no override -> LIVE for coll leg
- Rule with settle_a_ccy='MNT', manual_rate_coll=0.00072, no expiry -> MANUAL for coll leg
- Rule with manual_rate_coll set but rate_coll_override_expires='2025-01-01' (past date) -> LIVE for coll leg
- is_same_ccy_shortcircuit=true rule returns IDENTITY for both legs
**Depends on:** 10.4-T04

### 10.4-T06 — Implement Section 1 API endpoint: GET /admin/rules/{id}/currency-setup  _(30 min)_
**Context:** The Mapping Page Section 1 shows the operator the 5 read-only currency fields for the rule: collection_ccy, settle_a_ccy, usd_intermediary (always 'USD', displayed as N/A when is_same_ccy_shortcircuit=true), settle_b_ccy, payout_ccy. It also returns a same_ccy_notice flag (boolean) and, when true, the notice text: 'Same-currency rule - USD pool bypassed. collection_amount = target_payout + service_charge directly. Margin must be 0.' All fields are derived from the rule; none are editable. This endpoint serves the frontend read-only section.
**Steps:** Create GET /admin/rules/{id}/currency-setup handler in AdminRuleController; Return DTO: CurrencySetupDTO {collection_ccy, settle_a_ccy, usd_intermediary, settle_b_ccy, payout_ccy, same_ccy_shortcircuit: boolean, same_ccy_notice: String|null}; Set usd_intermediary='USD' always; set it to null or 'N/A' in the same_ccy_notice context per spec; Load rule by id; if not found, return 404; Require ROLE_OPS or ROLE_FINANCE_ANALYST to call this endpoint (read-only access is broad)
**Deliverable:** GET /admin/rules/{id}/currency-setup handler and CurrencySetupDTO
**Acceptance / logic checks:**
- Rule id=1 (SendMN INBOUND: coll=MNT, settleA=USD, settleB=KRW, payout=KRW) returns same_ccy_shortcircuit=false and usd_intermediary='USD'
- Rule id=2 (GME Remit DOMESTIC: all KRW) returns same_ccy_shortcircuit=true and same_ccy_notice contains 'USD pool bypassed'
- Unknown rule id returns HTTP 404
- No field in response is editable or writable (all are display-only in DTO)
- Unauthenticated request returns HTTP 401
**Depends on:** 10.4-T04

### 10.4-T07 — Implement Section 2 API endpoint: GET /admin/rules/{id}/rate-config  _(40 min)_
**Context:** Section 2 Rate Configuration shows two legs (coll and pay). For each leg, the response includes: slot_label (e.g. 'Collection Leg - USD -> MNT'), auto_derived_source (IDENTITY/LIVE/MANUAL/PARTNER resolved by RuleService.resolveRateSource), current_treasury_rate (from treasury_rate table, shown if LIVE or MANUAL; null if IDENTITY or PARTNER), override_source (current toggle state: LIVE or MANUAL), manual_rate_value (decimal, null if not MANUAL), override_expiry_date (date or null). For same-currency rules, both legs show IDENTITY and override_toggle_enabled=false. Current treasury rate is fetched as: SELECT rate FROM treasury_rate WHERE ccy_pair = 'usd_{ccy}' ORDER BY effective_at DESC LIMIT 1.
**Steps:** Create GET /admin/rules/{id}/rate-config handler; Build RateConfigDTO with nested CollLegDTO and PayLegDTO each containing the 6 fields above; Call RuleService.resolveRateSource for each leg to determine badge; Query treasury_rate for latest rate for each ccy; skip if IDENTITY; Set override_toggle_enabled=false if resolved source is IDENTITY, PARTNER, or same-currency; Require ROLE_OPS to call
**Deliverable:** GET /admin/rules/{id}/rate-config handler and RateConfigDTO
**Acceptance / logic checks:**
- SendMN INBOUND rule: coll leg returns source=IDENTITY (settleA=USD), pay leg returns source=LIVE (settleB=KRW) with current treasury rate value
- GME Remit DOMESTIC rule: both legs return source=IDENTITY, override_toggle_enabled=false
- Rule with manual override set on coll leg: returns source=MANUAL, manual_rate_value populated, override_toggle_enabled=true
- treasury_rate not found for ccy pair: current_treasury_rate=null in response, no 500 error
- PARTNER source leg returns override_toggle_enabled=false
**Depends on:** 10.4-T05

### 10.4-T08 — Implement Section 2 API endpoint: PATCH /admin/rules/{id}/rate-config (set/clear MANUAL override)  _(50 min)_
**Context:** The operator can switch a LIVE leg to MANUAL by providing a manual_rate_value (decimal, > 0) and optional override_expiry_date. They can revert to LIVE by clearing the override. Constraints: only LIVE legs can be switched to MANUAL; IDENTITY and PARTNER legs reject override attempts. MANUAL rate must be > 0. If the new manual rate deviates more than +-20% from the current LIVE treasury rate for that ccy, a deviation_warning flag is returned (HTTP 200 with warning=true) and the save is tentative; the caller must re-POST with confirm=true to commit. All saved changes are audit-logged to rule_audit_log with field names 'rate_coll_source'/'rate_pay_source', 'manual_rate_coll'/'manual_rate_pay', 'rate_coll_override_expires'/'rate_pay_override_expires'.
**Steps:** Create PATCH /admin/rules/{id}/rate-config accepting RateConfigUpdateDTO {leg: 'coll'|'pay', action: 'SET_MANUAL'|'CLEAR_MANUAL', manual_rate_value: Decimal, override_expiry_date: Date, confirm: boolean}; Validate: if action=SET_MANUAL and resolved source != LIVE, return 422 with error 'Cannot override IDENTITY or PARTNER leg'; Validate manual_rate_value > 0 when action=SET_MANUAL; Compute deviation: abs(manual_rate_value - current_treasury_rate) / current_treasury_rate; if > 0.20 and confirm!=true, return 200 with deviation_warning=true without saving; On confirmed save, update rule and write audit log entries for each changed field; Require ROLE_OPS
**Deliverable:** PATCH /admin/rules/{id}/rate-config handler with deviation guard
**Acceptance / logic checks:**
- Attempting MANUAL override on IDENTITY leg (settleA=USD) returns HTTP 422 with error message
- SET_MANUAL with manual_rate_value=0 returns validation error
- SET_MANUAL on LIVE leg with rate 30% above treasury and confirm=false returns 200 deviation_warning=true, rule NOT updated in DB
- Same request with confirm=true saves rule and writes audit log entry with field='manual_rate_coll', previous_value='null', new_value='0.00092500'
- CLEAR_MANUAL on a MANUAL leg sets manual_rate_coll=NULL and rate_coll_source resolves back to LIVE; audit log entry written
**Depends on:** 10.4-T07, 10.4-T03

### 10.4-T09 — Implement Section 3 API endpoint: GET /admin/rules/{id}/margin  _(25 min)_
**Context:** Section 3 returns margin fields for the rule: m_a (DECIMAL stored as fraction e.g. 0.010000 = 1%), m_b, combined_margin (m_a + m_b as fraction), and display_percent values for UI. Also returns is_same_ccy_shortcircuit so the UI knows to lock both fields at 0. For cross-border rules, combined must be >= 0.02 (2%). For same-currency rules, both must be 0. The response includes validation hints: min_combined_required (0.02 for cross-border, 0 for same-ccy) and fields_locked (true when same-currency).
**Steps:** Create GET /admin/rules/{id}/margin handler; Return MarginDTO: {m_a: decimal, m_b: decimal, combined_margin: decimal, m_a_pct: string, m_b_pct: string, combined_pct: string, is_same_ccy: boolean, min_combined_required: decimal, fields_locked: boolean}; Compute m_a_pct = m_a * 100 formatted to 4 decimal places (e.g. '1.0000'); Set min_combined_required=0.02 when is_same_ccy_shortcircuit=false, else 0; Set fields_locked=true when is_same_ccy_shortcircuit=true; Require ROLE_OPS or ROLE_FINANCE_ANALYST
**Deliverable:** GET /admin/rules/{id}/margin handler and MarginDTO
**Acceptance / logic checks:**
- SendMN INBOUND rule with m_a=0.01, m_b=0.01 returns combined_margin=0.02, combined_pct='2.0000', fields_locked=false
- GME Remit DOMESTIC (same-ccy) with m_a=0, m_b=0 returns fields_locked=true, min_combined_required=0
- Rule with m_a=0.005, m_b=0.005 (cross-border) returns combined_pct='1.0000' and min_combined_required=0.02
- m_a_pct for m_a=0.0125 returns '1.2500'
- HTTP 404 for unknown rule id
**Depends on:** 10.4-T04

### 10.4-T10 — Implement Section 3 API endpoint: PATCH /admin/rules/{id}/margin with validation  _(40 min)_
**Context:** Operator updates m_a and m_b. Both are DECIMAL(8,6) fractions (e.g. 0.010000 = 1%). Validation: each >= 0; each <= 1.0 (100%); max 4 decimal places when expressed as percent (max 6 as fraction). For cross-border rules (is_same_ccy_shortcircuit=false): m_a + m_b must be >= 0.02 (2% combined minimum, hard floor — Save button should be disabled on frontend, backend also enforces). For same-currency rules: m_a and m_b must both equal 0. All changes audit-logged to rule_audit_log with field names 'm_a' and 'm_b', previous and new values stored as 6-decimal strings (e.g. '0.010000'). Changes apply immediately to new transactions upon save; existing committed transactions unaffected.
**Steps:** Create PATCH /admin/rules/{id}/margin accepting MarginUpdateDTO {m_a: decimal, m_b: decimal}; Validate m_a >= 0 and m_b >= 0; Validate m_a <= 1.0 and m_b <= 1.0; For cross-border rules: reject with 422 if m_a + m_b < 0.02; error message: 'Cross-border rules require a combined margin of at least 2.00%'; For same-currency rules: reject with 422 if m_a != 0 or m_b != 0; On success, write audit log entries for m_a and m_b if changed; update rule; set effective_from=NOW(); Require ROLE_OPS
**Deliverable:** PATCH /admin/rules/{id}/margin handler with all validation rules
**Acceptance / logic checks:**
- Cross-border rule: PATCH with m_a=0.005, m_b=0.005 (1% total) returns HTTP 422 with 'combined margin' error message
- Cross-border rule: PATCH with m_a=0.010000, m_b=0.010000 (2% total) returns HTTP 200 and rule updated
- Same-currency rule: PATCH with m_a=0.01, m_b=0 returns HTTP 422
- Successful change from m_a=0.01 to m_a=0.0125: audit log row has field='m_a', previous_value='0.010000', new_value='0.012500'
- m_a=-0.001 returns HTTP 422 with validation error
**Depends on:** 10.4-T09, 10.4-T03

### 10.4-T11 — Implement Section 4 API endpoint: GET /admin/rules/{id}/service-charge  _(25 min)_
**Context:** Section 4 returns the flat per-transaction service charge and optional volume-tier table. Response fields: service_charge_amount DECIMAL(20,4), service_charge_ccy CHAR(3) (always = settle_a_ccy; display-only), volume_tiers_enabled BOOLEAN (true if at least one tier row exists), tiers: array of {id, min_collection_usd, max_collection_usd (null = open-ended), charge_amount}. Service charge is always in Settle A currency. Example: GME Remit DOMESTIC -> settle_a_ccy=KRW, service_charge_amount=500. OVERSEAS SendMN -> settle_a_ccy=USD.
**Steps:** Create GET /admin/rules/{id}/service-charge handler; Return ServiceChargeDTO: {service_charge_amount, service_charge_ccy, volume_tiers_enabled, tiers: List<TierDTO>}; Populate service_charge_ccy from rule.settle_a_ccy (never from caller input); Fetch all tier rows from service_charge_tier WHERE rule_id=? ORDER BY min_collection_usd ASC; Set volume_tiers_enabled = !tiers.isEmpty(); Require ROLE_OPS or ROLE_FINANCE_ANALYST
**Deliverable:** GET /admin/rules/{id}/service-charge handler and ServiceChargeDTO
**Acceptance / logic checks:**
- GME Remit rule returns service_charge_ccy='KRW' regardless of any input
- Rule with no tier rows returns volume_tiers_enabled=false and tiers=[]
- Rule with 2 tiers returns volume_tiers_enabled=true and tiers sorted by min_collection_usd ascending
- Tier with max_collection_usd=null is represented as null in JSON response
- HTTP 404 for unknown rule id
**Depends on:** 10.4-T04

### 10.4-T12 — Implement Section 4 API endpoint: PATCH /admin/rules/{id}/service-charge (flat charge update)  _(30 min)_
**Context:** Operator updates the flat service charge. service_charge_amount must be >= 0 (0 is valid for domestic rules); max 4 decimal places. service_charge_ccy is NOT accepted from the caller — it is always derived from rule.settle_a_ccy and any submitted currency is ignored. On save: audit log entry with field='service_charge_amount', previous and new values. The change applies to new transactions immediately. Note: this endpoint handles only the flat charge; tier table management is separate (T13/T14).
**Steps:** Create PATCH /admin/rules/{id}/service-charge accepting ServiceChargeUpdateDTO {service_charge_amount: decimal}; Validate service_charge_amount >= 0; Validate max 4 decimal places (reject e.g. 500.12345); Set service_charge_ccy = rule.settle_a_ccy (ignore any ccy field in request); Write audit log entry for service_charge_amount if changed; Update rule.service_charge_amount; set effective_from=NOW(); Require ROLE_OPS
**Deliverable:** PATCH /admin/rules/{id}/service-charge handler
**Acceptance / logic checks:**
- service_charge_amount=500.0000 accepted; rule updated with service_charge_ccy set to rule's settle_a_ccy, not any caller-provided value
- service_charge_amount=-1 returns HTTP 422
- service_charge_amount=500.12345 (5 decimal places) returns HTTP 422
- service_charge_amount=0 accepted (domestic rules may have 0)
- Audit log entry written with field='service_charge_amount', previous_value='500.0000', new_value='750.0000'
**Depends on:** 10.4-T11, 10.4-T03

### 10.4-T13 — Implement service charge tier CRUD API: POST and DELETE /admin/rules/{id}/service-charge/tiers  _(45 min)_
**Context:** The operator can add/remove volume tiers for service charge. Tiers are indexed by collection_usd (USD pool value before margin deduction). Each tier: min_collection_usd (inclusive), max_collection_usd (exclusive, NULL = open-ended), charge_amount >= 0 in settle_a_ccy. Business rule: tier ranges must not overlap (validated on add). A gap between tiers is allowed but flagged as a warning. The flat charge is the fallback if no tier matches at runtime. Tiers are only relevant for cross-border rules where collection_usd is computed; for same-currency short-circuit rules, tiers may exist but will never be used (warn, do not block).
**Steps:** Create POST /admin/rules/{id}/service-charge/tiers accepting TierCreateDTO {min_collection_usd, max_collection_usd, charge_amount}; Validate charge_amount >= 0; min_collection_usd >= 0; max_collection_usd > min_collection_usd when not null; Check overlap: query existing tiers for rule, reject if new range overlaps any existing tier; Detect and return gap_warning=true if new tier leaves a gap with adjacent tiers (non-fatal); Create DELETE /admin/rules/{id}/service-charge/tiers/{tier_id} that removes a single tier; Audit-log tier add/remove with field='service_charge_tier', previous_value=null for add, new_value=null for delete; Require ROLE_OPS
**Deliverable:** POST and DELETE /admin/rules/{id}/service-charge/tiers handlers with overlap validation
**Acceptance / logic checks:**
- Add tier {min=0, max=100, charge=500} and tier {min=100, max=200, charge=300} succeeds (contiguous, no overlap)
- Add tier {min=50, max=150, charge=400} when {min=0, max=100} exists returns HTTP 422 overlap error
- DELETE tier_id=5 removes the row and audit log entry is written
- Add tier with charge_amount=-1 returns HTTP 422
- Add tier with max=50, min=100 (max < min) returns HTTP 422
**Depends on:** 10.4-T02, 10.4-T03, 10.4-T11

### 10.4-T14 — Implement tier contiguity check service and gap-detection warning  _(35 min)_
**Context:** When the operator adds or modifies tiers, the system must validate contiguity: no tier range overlaps another and gaps (missing USD ranges) generate a warning. Implementation: load all tiers for rule sorted by min_collection_usd; for each pair of adjacent tiers, check tier[i].max_collection_usd == tier[i+1].min_collection_usd (contiguous) or tier[i].max_collection_usd < tier[i+1].min_collection_usd (gap). Overlap: tier[i].max_collection_usd > tier[i+1].min_collection_usd. This service is used by both the POST tier endpoint (T13) and the GET endpoint to return live warnings to the UI.
**Steps:** Create TierValidationService.validate(List<ServiceChargeTier> tiers) returning TierValidationResult {overlaps: List<String>, gaps: List<String>}; Sort tiers by min_collection_usd ascending; For each adjacent pair: if tiers[i].max_collection_usd > tiers[i+1].min_collection_usd -> overlap error; If tiers[i].max_collection_usd < tiers[i+1].min_collection_usd -> gap warning (record range); Return overlaps list (hard errors) and gaps list (warnings); Expose validation result in GET /admin/rules/{id}/service-charge response as tier_validation field
**Deliverable:** TierValidationService class used in tier CRUD and GET responses
**Acceptance / logic checks:**
- Tiers [{0-100},{100-200},{200-null}] returns empty overlaps and empty gaps
- Tiers [{0-100},{90-200}] returns overlap error mentioning range 90-100
- Tiers [{0-100},{150-200}] returns gap warning mentioning range 100-150
- Single tier returns empty overlaps and empty gaps
- Empty tier list returns empty overlaps and empty gaps
**Depends on:** 10.4-T13

### 10.4-T15 — Implement Review Changes diff service and POST /admin/rules/{id}/review endpoint  _(35 min)_
**Context:** Before saving the mapping page, the operator clicks 'Review Changes'. The backend computes a field-level diff of proposed values vs currently saved values and returns it. The diff must cover all editable fields: rate_coll_source, rate_pay_source, manual_rate_coll, manual_rate_pay, rate_coll_override_expires, rate_pay_override_expires, m_a, m_b, service_charge_amount, and tier changes. Each changed field entry: {field_name, previous_value, new_value, section (1-4)}. Unchanged fields are omitted. This is a read-only preview — it does not persist anything.
**Steps:** Create POST /admin/rules/{id}/review accepting MappingPageDraftDTO (all 4 sections combined); Load current rule from DB; Compute diff: for each field in the draft, compare to current; if different, add to diff list; Return ReviewChangesDTO: {changes: List<{field_name, section, previous_value, new_value}>, any_changes: boolean}; No DB writes in this endpoint; Require ROLE_OPS
**Deliverable:** POST /admin/rules/{id}/review diff endpoint and ReviewChangesDTO
**Acceptance / logic checks:**
- Draft with m_a changed from 0.010000 to 0.012500 returns changes list with entry {field='m_a', previous='0.010000', new='0.012500', section=3}
- Draft identical to current state returns any_changes=false and empty changes list
- Draft changing both manual_rate_coll and m_b returns 2 entries in changes list
- Endpoint does not modify the rule record in DB (verify by GET after call)
- HTTP 422 returned if draft fails any validation rule (e.g. cross-border m_a+m_b < 0.02)
**Depends on:** 10.4-T10, 10.4-T12

### 10.4-T16 — Implement atomic rule save: POST /admin/rules/{id}/save with full audit logging  _(50 min)_
**Context:** After reviewing, the operator clicks Save Rule. All four sections are committed atomically in one DB transaction. The save must: (1) validate all fields across all sections; (2) write all changed fields to rule_audit_log atomically with the rule update; (3) set effective_from=NOW() on the rule; (4) if is_new_rule (status=DRAFT for first save), keep status=DRAFT; if existing ACTIVE rule, changes take effect immediately. Audit log format per field: {event:'rule_field_changed', actor_user_id, timestamp_utc, rule_key:{partner, scheme, direction}, field, previous_value, new_value}. The entire operation must roll back if any part fails.
**Steps:** Create POST /admin/rules/{id}/save accepting MappingPageSaveDTO; Open a single DB transaction; Run all cross-section validations (rate source consistency, margin floors, service charge >= 0, tier contiguity); Compute changed fields vs current DB state; Write one rule_audit_log row per changed field within the same transaction; Update rule record with new values and effective_from=NOW(); Commit; return SaveResultDTO {rule_id, effective_from, audit_entries_written: int}
**Deliverable:** POST /admin/rules/{id}/save transactional handler with audit log writes
**Acceptance / logic checks:**
- Saving m_a=0.0125 and m_b=0.0075 (cross-border, combined=2%) succeeds and audit log has 2 rows with correct before/after values
- If audit log write fails (simulate constraint error), rule update is also rolled back (verify DB state unchanged)
- Saving a cross-border rule with m_a=0.005, m_b=0.005 returns HTTP 422 and no partial changes in DB
- effective_from on rule is updated to current UTC on every successful save
- audit_entries_written in response equals the number of actually changed fields
**Depends on:** 10.4-T15, 10.4-T03

### 10.4-T17 — Implement Activate Rule endpoint: POST /admin/rules/{id}/activate  _(30 min)_
**Context:** After initial creation, a DRAFT rule must be explicitly activated by the operator. Activation sets status='ACTIVE' and effective_from=NOW(). Constraint: a rule cannot be activated if it would violate any config guard: cross-border rules need m_a+m_b >= 0.02; service_charge_ccy must equal settle_a_ccy; rate source must be validly resolved. Once ACTIVE, the rule is used by the rate engine for new transactions. A DRAFT rule is never used by the engine. Activation is logged to rule_audit_log with field='status', previous_value='DRAFT', new_value='ACTIVE'. Only ROLE_OPS can activate.
**Steps:** Create POST /admin/rules/{id}/activate handler; Validate rule is in DRAFT status (reject 422 if already ACTIVE or SUSPENDED); Run all config guard checks (margin floor for cross-border, service_charge_ccy = settle_a_ccy, valid rate source); Set rule.status='ACTIVE', rule.effective_from=NOW(); Write audit log entry: field='status', previous_value='DRAFT', new_value='ACTIVE'; Return ActivationResultDTO {rule_id, status, effective_from}; Require ROLE_OPS
**Deliverable:** POST /admin/rules/{id}/activate handler
**Acceptance / logic checks:**
- DRAFT cross-border rule with m_a+m_b=0.02 activates successfully; status in DB is 'ACTIVE'
- DRAFT cross-border rule with m_a+m_b=0.015 returns HTTP 422 with margin error; status remains DRAFT
- Calling activate on already-ACTIVE rule returns HTTP 422
- Audit log has entry for field='status' with previous='DRAFT', new='ACTIVE'
- effective_from is updated to current UTC on activation
**Depends on:** 10.4-T16

### 10.4-T18 — Implement rule list view API: GET /admin/rules with summary columns  _(35 min)_
**Context:** The Rule List View table (PRD-07 §6.2) needs these columns: partner_name, scheme_name, direction, rate_source_summary (e.g. 'IDENTITY/LIVE'), combined_margin_pct (m_a+m_b as percentage string, e.g. '2.00%'), service_charge_display (e.g. '500 KRW' or '0.50 USD'), status, last_modified. Actions available per row: View/Edit, Activate/Deactivate, View Audit History. The endpoint supports optional query params: partner_id, scheme_id, direction, status for filtering.
**Steps:** Create GET /admin/rules handler with optional query params; Join rule to partner (for name) and qr_scheme (for name) tables; Compute rate_source_summary as '{coll_source}/{pay_source}'; Compute combined_margin_pct as ((m_a + m_b) * 100) formatted to 2 decimal places + '%'; Format service_charge_display as '{amount} {ccy}'; Return paginated RuleListDTO with items and total_count; default page_size=20; Require ROLE_OPS or ROLE_FINANCE_ANALYST
**Deliverable:** GET /admin/rules paginated list endpoint with filtering
**Acceptance / logic checks:**
- Returns all rules when no filters applied
- Filter by status='ACTIVE' returns only active rules
- Rule for SendMN INBOUND shows rate_source_summary='IDENTITY/LIVE' (settleA=USD->IDENTITY, settleB=KRW->LIVE)
- combined_margin_pct for m_a=0.01, m_b=0.01 returns '2.00%'
- service_charge_display for amount=500, ccy='KRW' returns '500 KRW'
**Depends on:** 10.4-T04

### 10.4-T19 — Implement audit history API: GET /admin/rules/{id}/audit-history with CSV export  _(35 min)_
**Context:** The Rule Audit History view (PRD-07 §6.5) shows a chronological table of all rule_audit_log rows for a rule. Columns: timestamp_utc, actor (display name + user ID), field_changed, previous_value, new_value, effective_from (= timestamp_utc of the save). Supports CSV export. The CSV must include all columns with proper escaping. Query should be paginated with optional date-range filter params: from_utc, to_utc.
**Steps:** Create GET /admin/rules/{id}/audit-history with optional params: from_utc, to_utc, page, page_size; default page_size=50; Join rule_audit_log to users table for actor display name; Return AuditHistoryDTO {items: List<AuditEntryDTO>, total_count, rule_key}; Support GET /admin/rules/{id}/audit-history?format=csv that returns Content-Type: text/csv with all rows (no pagination limit); CSV columns in order: timestamp_utc, actor_user_id, actor_name, field_changed, previous_value, new_value, effective_from; Require ROLE_OPS or ROLE_FINANCE_ANALYST
**Deliverable:** GET /admin/rules/{id}/audit-history endpoint with JSON and CSV modes
**Acceptance / logic checks:**
- Returns rows in descending timestamp_utc order by default
- from_utc filter excludes entries before that timestamp
- format=csv response has Content-Type: text/csv and header row as first line
- CSV values with commas (e.g. a previous_value containing a comma) are properly double-quoted
- Empty rule (no changes ever) returns empty items list with total_count=0
**Depends on:** 10.4-T03, 10.4-T16

### 10.4-T20 — Implement rule deactivation: POST /admin/rules/{id}/deactivate  _(25 min)_
**Context:** An ACTIVE rule can be suspended/deactivated. Status changes to SUSPENDED. A SUSPENDED rule is not used by the rate engine for new transactions. Existing committed transactions are unaffected. The operator must confirm via the UI (backend receives a confirm=true param). Deactivation is audit-logged: field='status', previous_value='ACTIVE', new_value='SUSPENDED'. Reactivation (SUSPENDED -> ACTIVE) uses the existing activate endpoint. Only ROLE_OPS can deactivate.
**Steps:** Create POST /admin/rules/{id}/deactivate accepting {confirm: boolean}; Reject with 400 if confirm != true; Reject with 422 if rule is not ACTIVE (e.g. already DRAFT or SUSPENDED); Set rule.status='SUSPENDED', rule.effective_from=NOW(); Write audit log entry field='status', previous_value='ACTIVE', new_value='SUSPENDED'; Return {rule_id, status: 'SUSPENDED', effective_from}; Require ROLE_OPS
**Deliverable:** POST /admin/rules/{id}/deactivate handler
**Acceptance / logic checks:**
- ACTIVE rule deactivated with confirm=true: status becomes 'SUSPENDED' in DB
- Calling deactivate with confirm=false returns HTTP 400
- Calling deactivate on DRAFT rule returns HTTP 422
- Audit log entry written with field='status', previous_value='ACTIVE', new_value='SUSPENDED'
- After deactivation, rate engine calls for that rule return RULE_NOT_ACTIVE error (verify by calling rate engine with rule id)
**Depends on:** 10.4-T17

### 10.4-T21 — Implement RuleConfigLoader used by rate engine: fetch active rule by partner+scheme+direction  _(45 min)_
**Context:** The rate engine (separate component) must fetch the active rule configuration at quote time. It calls RuleConfigLoader.loadActiveRule(partnerId, schemeId, direction) which returns a RuleConfig value object containing all rate engine inputs: rate_coll_source, rate_pay_source, manual_rate_coll, manual_rate_pay, m_a, m_b, service_charge_amount, service_charge_ccy, is_same_ccy_shortcircuit, and the collection/settle/payout currencies. Returns RULE_NOT_ACTIVE error if no ACTIVE rule exists. Also resolves current treasury rate for LIVE legs inline (calls treasury_rate table). Changes that have been saved (effective_from <= NOW()) are reflected immediately; the loader never returns stale config from a pre-save snapshot.
**Steps:** Create RuleConfigLoader.loadActiveRule(Long partnerId, Long schemeId, String direction); Query rule WHERE partner_id=? AND scheme_id=? AND direction=? AND status='ACTIVE'; If not found, throw RuleNotFoundException('RULE_NOT_ACTIVE'); Resolve treasury rate for LIVE legs: SELECT rate FROM treasury_rate WHERE ccy_pair=? ORDER BY effective_at DESC LIMIT 1; Check MANUAL override expiry: if rate_coll_override_expires < TODAY, treat as LIVE and log warning; Return RuleConfig value object with all fields; Add caching with TTL=5s to avoid hammering DB on high-volume payment processing (evict on rule save)
**Deliverable:** RuleConfigLoader class used by the rate engine
**Acceptance / logic checks:**
- Returns correct RuleConfig for ACTIVE SendMN INBOUND rule including m_a=0.01, m_b=0.01
- Throws RuleNotFoundException for DRAFT or SUSPENDED rule
- Returns LIVE source for coll leg of SendMN (settleA=USD -> IDENTITY, not LIVE)
- Expired MANUAL override (rate_coll_override_expires yesterday) returns LIVE source with current treasury rate
- Cache eviction: saving a rule change via T16 causes next loadActiveRule call to return updated values within 5s
**Depends on:** 10.4-T05, 10.4-T16

### 10.4-T22 — Implement service charge tier resolution at transaction time  _(35 min)_
**Context:** At quote time, the rate engine must resolve the correct service_charge for the transaction using the tier table. Resolution logic: if volume_tiers_enabled (tiers exist for rule) AND the rule is cross-border (not same-ccy short-circuit), find the tier where min_collection_usd <= collection_usd < max_collection_usd (NULL max = open-ended). If a matching tier exists, use tier.charge_amount. If no tier matches (gap in coverage), fall back to rule.service_charge_amount. If is_same_ccy_shortcircuit=true, always use flat rule.service_charge_amount regardless of tiers. This logic is part of the rate engine, called after Step 2 (collection_usd is known).
**Steps:** Create ServiceChargeTierResolver.resolve(Long ruleId, BigDecimal collection_usd, boolean is_same_ccy) returning BigDecimal charge_amount; If is_same_ccy=true, return flat rule.service_charge_amount immediately; Query service_charge_tier WHERE rule_id=? AND min_collection_usd <= collection_usd AND (max_collection_usd IS NULL OR max_collection_usd > collection_usd); If row found, return tier.charge_amount; If no row found, return rule.service_charge_amount as fallback; Log a warning if fallback is used due to a gap in tier coverage
**Deliverable:** ServiceChargeTierResolver class integrated into rate engine quote flow
**Acceptance / logic checks:**
- collection_usd=150 with tier {min=100, max=200, charge=300} returns 300
- collection_usd=250 with tiers {0-100:500, 100-200:300} and no tier for 200+ returns flat rule charge (fallback)
- is_same_ccy=true with tiers present returns flat rule charge, not tier value
- collection_usd=100 with tier {min=100, max=200} (inclusive lower bound) returns tier charge (not out of range)
- collection_usd=200 with tier {min=100, max=200} (exclusive upper bound) does NOT match this tier
**Depends on:** 10.4-T02, 10.4-T21

### 10.4-T23 — Unit tests: currency quadruple auto-derivation and same-ccy short-circuit detection  _(30 min)_
**Context:** Test RuleService and RuleRepository auto-derivation logic. Key scenarios: (1) LOCAL partner GME Remit (collect=KRW, settleA=KRW) on ZeroPay (settleB=KRW, payout=KRW) -> is_same_ccy_shortcircuit=true. (2) OVERSEAS SendMN (collect=MNT, settleA=USD) on ZeroPay (settleB=KRW, payout=KRW) -> is_same_ccy_shortcircuit=false. (3) Hypothetical all-USD rule -> is_same_ccy_shortcircuit=true. The quadruple must come from partner/scheme, never from caller.
**Steps:** Write unit test class RuleCurrencyDerivationTest using JUnit 5 + Mockito; Test case 1: build Partner(collect=KRW, settleA=KRW), Scheme(settleB=KRW, payout=KRW); call deriveRule(); assert is_same_ccy_shortcircuit=true and all 4 ccy fields correct; Test case 2: build Partner(collect=MNT, settleA=USD), Scheme(settleB=KRW, payout=KRW); assert is_same_ccy_shortcircuit=false; Test case 3: build Partner(collect=USD, settleA=USD), Scheme(settleB=USD, payout=USD); assert is_same_ccy_shortcircuit=true; Test case 4: verify caller-provided ccy fields are ignored (pass wrong values, assert derived values are correct)
**Deliverable:** RuleCurrencyDerivationTest.java with 4 passing test cases
**Acceptance / logic checks:**
- Test 1 passes: all KRW rule -> is_same_ccy_shortcircuit=true
- Test 2 passes: MNT/USD/KRW/KRW rule -> is_same_ccy_shortcircuit=false
- Test 3 passes: all USD -> is_same_ccy_shortcircuit=true
- Test 4 passes: caller-provided ccy overridden by derived values
- All 4 tests pass with mvn test -Dtest=RuleCurrencyDerivationTest
**Depends on:** 10.4-T04

### 10.4-T24 — Unit tests: rate source resolution across all 4 source types  _(30 min)_
**Context:** Test RuleService.resolveRateSource for all source type transitions. Key vectors: (A) settleA=USD -> IDENTITY (coll leg); (B) settleA=MNT, no override -> LIVE; (C) settleA=MNT, manual_rate_coll=0.00072, no expiry -> MANUAL; (D) manual_rate_coll set but override expired yesterday -> LIVE; (E) scheme.rate_source_pay=PARTNER -> PARTNER; (F) is_same_ccy_shortcircuit=true -> both legs IDENTITY regardless. Use mocked treasury_rate and scheme.
**Steps:** Write RateSourceResolutionTest with 6 test cases; Mock TreasuryRateRepository and QrSchemeRepository; Test A: settle_a_ccy='USD' -> resolveRateSource(rule,'coll') = IDENTITY; Test B: settle_a_ccy='MNT', no override -> LIVE; Test C: settle_a_ccy='MNT', manual set, no expiry -> MANUAL; Test D: settle_a_ccy='MNT', manual set, expiry=yesterday -> LIVE; Test E: scheme configured PARTNER for pay leg -> PARTNER; Test F: is_same_ccy_shortcircuit=true -> both coll and pay return IDENTITY
**Deliverable:** RateSourceResolutionTest.java with 6 passing test cases
**Acceptance / logic checks:**
- Test A passes
- Test B passes
- Test C passes
- Test D passes: expired override falls back to LIVE
- Tests E and F pass
- All 6 tests pass with mvn test -Dtest=RateSourceResolutionTest
**Depends on:** 10.4-T05

### 10.4-T25 — Unit tests: margin validation — cross-border floor and same-ccy lock  _(30 min)_
**Context:** Test the margin validation logic in the PATCH /margin handler. Key vectors: (1) cross-border rule, m_a=0.005, m_b=0.005 (1% combined) -> reject with MARGIN_TOO_LOW; (2) cross-border rule, m_a=0.01, m_b=0.01 (2% combined, exact floor) -> accept; (3) cross-border rule, m_a=0.015, m_b=0.01 (2.5%) -> accept; (4) same-ccy rule, m_a=0, m_b=0 -> accept; (5) same-ccy rule, m_a=0.01 -> reject; (6) negative m_a -> reject; (7) m_a=1.0 (100%) and m_b=0 -> accept (no upper cap on individual margin); (8) m_a=0.5, m_b=0.51 (combined 101%) -> reject (> 100% each is nonsensical; combined > 1.0 is rejected).
**Steps:** Write MarginValidationTest with 8 test cases using MockMvc or direct service call; Each test provides a rule (cross-border or same-ccy), input m_a and m_b, and asserts HTTP status code and error message when applicable; Test 1: 422 with message containing '2.00%'; Test 2: 200 ok; Test 3: 200 ok; Test 4: 200 ok; Tests 5-8: 422 with appropriate messages
**Deliverable:** MarginValidationTest.java with 8 passing test cases
**Acceptance / logic checks:**
- Test 1 passes: m_a+m_b=1% cross-border is rejected
- Test 2 passes: m_a+m_b=2% exactly is accepted
- Test 4 passes: same-ccy m_a=0, m_b=0 accepted
- Test 5 passes: same-ccy m_a=0.01 rejected
- All 8 tests pass with mvn test -Dtest=MarginValidationTest
**Depends on:** 10.4-T10

### 10.4-T26 — Unit tests: service charge tier resolution with boundary conditions  _(30 min)_
**Context:** Test ServiceChargeTierResolver.resolve with boundary conditions at tier edges. Numeric vectors: (A) collection_usd=100.00, tiers [{0-100:500},{100-200:300}] -> lower bound is inclusive so 100 matches SECOND tier: charge=300. (B) collection_usd=99.9999, same tiers -> matches first tier: charge=500. (C) collection_usd=200, tiers [{0-100:500},{100-200:300}] -> no tier covers 200 (max=200 is exclusive), fallback to flat. (D) collection_usd=350, tiers [{0-100:500},{100-null:300}] -> matches open-ended tier: charge=300. (E) is_same_ccy=true with tiers -> always flat. (F) no tiers at all -> flat charge.
**Steps:** Write ServiceChargeTierResolverTest with 6 test cases; Use in-memory tier lists (no DB needed); Test A: 100.00 matches second tier (min=100 inclusive); Test B: 99.9999 matches first tier; Test C: 200.00 falls through to flat fallback; Test D: 350.00 matches open-ended tier; Test E: is_same_ccy=true returns flat regardless; Test F: empty tiers list returns flat
**Deliverable:** ServiceChargeTierResolverTest.java with 6 passing test cases
**Acceptance / logic checks:**
- Test A passes: boundary value 100 routes to min=100 tier
- Test B passes: 99.9999 routes to min=0 tier
- Test C passes: fallback used when no tier covers value
- Test D passes: NULL max_collection_usd treated as infinity
- Tests E and F pass
- All 6 tests pass with mvn test -Dtest=ServiceChargeTierResolverTest
**Depends on:** 10.4-T22

### 10.4-T27 — Unit tests: audit log write correctness and atomicity  _(40 min)_
**Context:** Test that audit log entries are written correctly on rule save and that the save+audit is atomic (both succeed or both roll back). Use exact field name strings and value format: 6-decimal fraction for m_a/m_b (e.g. '0.010000'), 8-decimal for manual rates. Test rule_key JSON format: {partner, scheme, direction}. Also test that a transaction rollback (simulated by throwing after rule update, before audit log commit) leaves both rule and audit log unchanged.
**Steps:** Write RuleSaveAuditTest with 4 test cases using @Transactional test with Spring context or TestContainers; Test 1: save m_a from 0.01 to 0.0125 -> audit log has field='m_a', previous_value='0.010000', new_value='0.012500'; Test 2: save with no changes -> no audit log rows written (audit_entries_written=0); Test 3: simulate failure mid-transaction -> rule DB value unchanged AND no audit log row present; Test 4: save changing both m_a and manual_rate_coll -> 2 audit log rows, each with correct field name and values
**Deliverable:** RuleSaveAuditTest.java with 4 passing test cases
**Acceptance / logic checks:**
- Test 1 passes: previous_value format is '0.010000' (6 decimal fraction, not percent)
- Test 2 passes: no spurious audit rows on no-op save
- Test 3 passes: rollback leaves DB clean (atomicity confirmed)
- Test 4 passes: both fields appear in audit log in same transaction
- All 4 tests pass
**Depends on:** 10.4-T16

### 10.4-T28 — Unit tests: MANUAL rate deviation guard (+-20% guard-rail)  _(35 min)_
**Context:** Test the deviation guard in PATCH /admin/rules/{id}/rate-config. The guard fires when abs(manual_rate - live_rate) / live_rate > 0.20. Vectors: current treasury.usd_krw = 1380.00. (A) manual=1380.00 -> no deviation, saves immediately. (B) manual=1100.00 -> deviation = (1380-1100)/1380 = 20.3% -> guard fires, returns deviation_warning=true, does NOT save. (C) same request with confirm=true -> saves. (D) manual=1656.01 -> deviation = (1656.01-1380)/1380 = 20.0% -> exactly at limit -> guard fires. (E) manual=1655.99 -> 19.99% -> no warning, saves.
**Steps:** Write ManualRateDeviationTest with 5 test cases using MockMvc; Mock treasury rate as 1380.00 for usd_krw; Test A: 0% deviation -> HTTP 200, rule saved, no deviation_warning; Test B: 20.3% deviation, confirm=false -> HTTP 200 deviation_warning=true, rule NOT saved; Test C: 20.3% deviation, confirm=true -> HTTP 200, rule saved; Test D: exactly 20.0% deviation -> guard fires (>= 20% triggers warning); Test E: 19.99% deviation -> no guard, rule saved immediately
**Deliverable:** ManualRateDeviationTest.java with 5 passing test cases
**Acceptance / logic checks:**
- Test A passes
- Test B passes: DB not updated when deviation_warning=true and no confirm
- Test C passes: confirm=true bypasses warning and saves
- Test D passes: 20.0% triggers guard (boundary condition)
- All 5 tests pass with mvn test -Dtest=ManualRateDeviationTest
**Depends on:** 10.4-T08

### 10.4-T29 — Unit tests: RuleConfigLoader — active rule fetch, expiry, and cache eviction  _(35 min)_
**Context:** Test RuleConfigLoader.loadActiveRule behavior. Vectors: (1) ACTIVE rule returns correct RuleConfig. (2) DRAFT rule throws RuleNotFoundException. (3) SUSPENDED rule throws RuleNotFoundException. (4) Expired MANUAL override (rate_coll_override_expires < TODAY) returns LIVE source. (5) Cache eviction: modify rule, verify loader returns fresh config on next call within 5s (test with TestClock). Use mocked repository.
**Steps:** Write RuleConfigLoaderTest with 5 test cases; Mock RuleRepository, TreasuryRateRepository; Test 1: ACTIVE rule found -> RuleConfig contains m_a=0.01, m_b=0.01, is_same_ccy=false; Test 2: DRAFT rule -> throws RuleNotFoundException with message RULE_NOT_ACTIVE; Test 3: SUSPENDED rule -> throws RuleNotFoundException; Test 4: manual_rate_coll set, override_expires=yesterday -> resolvedSource=LIVE; Test 5: save new m_a=0.0125 -> cache evicted -> next loadActiveRule returns m_a=0.0125
**Deliverable:** RuleConfigLoaderTest.java with 5 passing test cases
**Acceptance / logic checks:**
- Test 1 passes: correct m_a/m_b in returned config
- Test 2 passes: DRAFT throws correct exception
- Test 3 passes: SUSPENDED throws correct exception
- Test 4 passes: expired override falls back to LIVE
- Test 5 passes: post-eviction reload returns updated values
- All 5 tests pass
**Depends on:** 10.4-T21

### 10.4-T30 — Integration test: full mapping page save round-trip with audit trail verification  _(55 min)_
**Context:** End-to-end test covering: create rule (DRAFT), populate all 4 sections via PATCH endpoints, call Review Changes (verify diff), call Save, call Activate, call GET /audit-history (verify entries). Use TestContainers with real PostgreSQL. Partner: SendMN (collect=MNT, settleA=USD). Scheme: ZeroPay (settleB=KRW, payout=KRW). Direction: INBOUND. Target config: rate_coll_source=IDENTITY, rate_pay_source=LIVE, m_a=0.01, m_b=0.01, service_charge_amount=0.36, service_charge_ccy=USD. Verify pool identity invariant holds for a sample transaction using this config.
**Steps:** Write MappingPageIntegrationTest extending AbstractIntegrationTest (TestContainers PostgreSQL); Step 1: POST /admin/rules to create DRAFT rule for SendMN+ZeroPay+INBOUND; Step 2: PATCH /margin with m_a=0.01, m_b=0.01; assert 200; Step 3: PATCH /service-charge with service_charge_amount=0.36; assert 200; Step 4: POST /review; assert changes list has service_charge_amount and margin entries; Step 5: POST /save; assert audit_entries_written=2; Step 6: POST /activate; assert status=ACTIVE; Step 7: GET /audit-history; assert entries for m_a, m_b, service_charge_amount, status fields; Step 8: call RuleConfigLoader.loadActiveRule; assert RuleConfig fields match saved values
**Deliverable:** MappingPageIntegrationTest.java with 8-step end-to-end test passing on real DB
**Acceptance / logic checks:**
- All 8 steps pass sequentially with no errors
- Audit history returns >= 4 entries (m_a, m_b, service_charge_amount, status=ACTIVE)
- RuleConfig from loader has m_a=0.01, m_b=0.01, service_charge_amount=0.36, service_charge_ccy='USD'
- Rule status in DB is 'ACTIVE' after step 6
- No rule changes visible to rate engine before step 6 (status=DRAFT is not returned by loadActiveRule)
**Depends on:** 10.4-T19, 10.4-T21, 10.4-T17

### 10.4-T31 — Integration test: pool identity invariant with saved rule config  _(45 min)_
**Context:** Using the saved rule from T30 (SendMN INBOUND: m_a=0.01, m_b=0.01, settleA=USD IDENTITY, settleB=KRW LIVE), execute the 5-step rate engine with target_payout=50000 KRW and treasury.usd_krw=1380.00 and verify: payout_usd_cost=36.2319, collection_usd=36.9714, collection_margin_usd=0.3697, payout_margin_usd=0.3697, send_amount=36.9714 (IDENTITY leg), collection_amount=36.9714+0.36=37.3314. Pool identity: |36.9714-0.3697-0.3697-36.2319| < 0.01. This test exercises the rate engine reading config from the rule, not testing the engine itself independently.
**Steps:** Write PoolIdentityIntegrationTest using TestContainers; Insert treasury rate usd_krw=1380.00 in treasury_rate table; Insert active rule from T30 config; Call RateEngineService.computeQuote(ruleId, target_payout=50000, payout_ccy='KRW'); Assert each 5-step output matches expected values to 4 decimal places; Assert |collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost| < 0.01; Assert offer_rate_coll = 36.9714 / (36.9714 - 0.3697) = approx 1.01010
**Deliverable:** PoolIdentityIntegrationTest.java verifying rate engine reads and uses mapping page config correctly
**Acceptance / logic checks:**
- payout_usd_cost = 36.2319 +/- 0.0001
- collection_usd = 36.9714 +/- 0.0001
- collection_margin_usd = payout_margin_usd = 0.3697 +/- 0.0001
- Pool identity: abs difference < 0.01 USD
- offer_rate_coll = 1.0101 +/- 0.0001
**Depends on:** 10.4-T30


## WBS 10.5 — FX/Treasury rate management
### 10.5-T01 — Create treasury_rate DB migration: table, constraints, indexes  _(30 min)_
**Context:** GMEPay+ stores all FX treasury rates in the table treasury_rate. Schema (from DAT-03): id BIGINT PK auto-increment, ccy_pair VARCHAR(10) NOT NULL (e.g. usd_krw), rate DECIMAL(20,8) NOT NULL CHECK > 0, source VARCHAR(10) NOT NULL CHECK IN ('LIVE','MANUAL'), effective_at TIMESTAMPTZ NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL, created_by VARCHAR(120) NULL, updated_by VARCHAR(120) NULL. Rate convention: treasury.usd_{ccy} = units of {ccy} per 1 USD (e.g. usd_krw = 1350.00 means 1 USD = 1350 KRW). The current effective rate for a ccy_pair is the row with the greatest effective_at <= NOW().
**Steps:** Write a Flyway/Liquibase migration file (e.g. V10_5_001__create_treasury_rate.sql); Add the table DDL with all columns, types, NOT NULL constraints, and CHECK constraints exactly as specified; Add a unique-style index on (ccy_pair, effective_at DESC) to support efficient current-rate lookups; Add a non-unique index on ccy_pair alone for history queries; Seed an initial row: ccy_pair='usd_usd', rate=1.00000000, source='MANUAL', effective_at=NOW(), created_by='SYSTEM' (USD identity; never updated manually)
**Deliverable:** Migration file V10_5_001__create_treasury_rate.sql creating the treasury_rate table with all constraints and indexes
**Acceptance / logic checks:**
- Migration applies cleanly on a fresh schema (no errors)
- CHECK (rate > 0) rejects INSERT of rate = 0.0 and rate = -1.0
- CHECK (source IN ('LIVE','MANUAL')) rejects source = 'FEED'
- Index on (ccy_pair, effective_at DESC) exists and is used by EXPLAIN on SELECT ... WHERE ccy_pair='usd_krw' ORDER BY effective_at DESC LIMIT 1
- Seed row for usd_usd with rate=1.0 and source='MANUAL' is present after migration

### 10.5-T02 — Create treasury_rate_audit DB migration: append-only audit trail table  _(25 min)_
**Context:** Every write to treasury_rate must produce an append-only audit log entry (PRD-07 §13). The audit table treasury_rate_audit stores: id BIGINT PK auto-increment, treasury_rate_id BIGINT NOT NULL FK -> treasury_rate.id, event_type VARCHAR(30) NOT NULL CHECK IN ('RATE_CREATED','RATE_SUPERSEDED'), ccy_pair VARCHAR(10) NOT NULL, previous_rate DECIMAL(20,8) NULL (NULL for first entry per ccy_pair), new_rate DECIMAL(20,8) NOT NULL, source VARCHAR(10) NOT NULL, effective_at TIMESTAMPTZ NOT NULL, actor_user_id VARCHAR(120) NOT NULL, actor_display_name VARCHAR(255) NOT NULL, actor_ip INET NULL, notes TEXT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(). The table must be insert-only at the application service account level.
**Steps:** Write migration file V10_5_002__create_treasury_rate_audit.sql; Add the table DDL with all columns, constraints, and FK to treasury_rate; Add index on (ccy_pair, created_at DESC) for history queries; Add index on actor_user_id for actor-based audit searches; Grant INSERT-only privilege on this table to the application DB role (revoke UPDATE/DELETE)
**Deliverable:** Migration file V10_5_002__create_treasury_rate_audit.sql creating the treasury_rate_audit table with constraints and restricted grants
**Acceptance / logic checks:**
- Migration applies after T01 with no errors
- FK treasury_rate_audit.treasury_rate_id -> treasury_rate.id enforces referential integrity (reject orphan insert)
- Application DB role can INSERT but SELECT on UPDATE attempt raises permission denied
- event_type CHECK rejects value 'MODIFIED'
- Index on (ccy_pair, created_at DESC) is present
**Depends on:** 10.5-T01

### 10.5-T03 — Define TreasuryRate JPA entity and TreasuryRateAudit JPA entity  _(30 min)_
**Context:** GMEPay+ backend is Java + PostgreSQL. Map the two tables from T01/T02 to JPA entities. TreasuryRate fields: id, ccyPair (String), rate (BigDecimal), source (enum RateSource: LIVE/MANUAL), effectiveAt (OffsetDateTime), createdAt, updatedAt, createdBy, updatedBy. TreasuryRateAudit fields: id, treasuryRate (ManyToOne), eventType (enum: RATE_CREATED/RATE_SUPERSEDED), ccyPair, previousRate (nullable BigDecimal), newRate, source (RateSource), effectiveAt, actorUserId, actorDisplayName, actorIp (String nullable), notes (String nullable), createdAt. Use DECIMAL(20,8) via @Column(precision=20,scale=8). RateSource must be stored as STRING in DB.
**Steps:** Create TreasuryRate.java entity with all fields, @Entity, @Table(name='treasury_rate'), @Column annotations; Create RateSource.java enum with LIVE and MANUAL values; Create TreasuryRateAudit.java entity with all fields, @Entity, @Table(name='treasury_rate_audit'), @ManyToOne(fetch=LAZY) to TreasuryRate; Annotate id fields with @GeneratedValue(strategy=IDENTITY); Confirm @Enumerated(EnumType.STRING) on both source fields
**Deliverable:** TreasuryRate.java, TreasuryRateAudit.java, RateSource.java in the domain model package
**Acceptance / logic checks:**
- Both entities compile without error
- RateSource.LIVE and RateSource.MANUAL map to string values 'LIVE' and 'MANUAL' in DB column
- TreasuryRate.rate is BigDecimal with precision 20 scale 8 per @Column annotation
- TreasuryRateAudit.previousRate is nullable (no NOT NULL constraint in entity)
- @ManyToOne on TreasuryRateAudit.treasuryRate uses LAZY fetch
**Depends on:** 10.5-T01, 10.5-T02

### 10.5-T04 — Implement TreasuryRateRepository: current rate lookup and history queries  _(40 min)_
**Context:** The rate engine resolves cost_rate_coll = treasury.usd_{settle_a_ccy} and cost_rate_pay = treasury.usd_{settle_b_ccy} using the most recent effective rate (greatest effective_at <= NOW()). TreasuryRateRepository needs: findCurrentRate(ccyPair, asOf): returns Optional<TreasuryRate> with greatest effective_at <= asOf; findHistory(ccyPair, pageable): all rows for a ccy_pair ordered by effective_at DESC; findAllCurrentRates(asOf): one current row per ccy_pair for the rate list view. Use Spring Data JPA or JPQL. The usd_usd identity row (rate=1.0) must be returned by findCurrentRate('usd_usd', ...) but is never manually editable.
**Steps:** Create TreasuryRateRepository extending JpaRepository<TreasuryRate, Long>; Add findCurrentRate: @Query SELECT t FROM TreasuryRate t WHERE t.ccyPair = :ccyPair AND t.effectiveAt <= :asOf ORDER BY t.effectiveAt DESC LIMIT 1; Add findHistory: return Page<TreasuryRate> ordered by effectiveAt DESC for given ccyPair; Add findAllCurrentRates: native or JPQL DISTINCT ON / subquery to return one row per ccy_pair with max effectiveAt <= :asOf; Write a repository integration test with 3 rows for usd_krw at t1 < t2 < t3 and assert findCurrentRate at t2+1s returns row t2
**Deliverable:** TreasuryRateRepository.java with current-rate, history, and all-current queries, plus integration test
**Acceptance / logic checks:**
- findCurrentRate('usd_krw', t2+1s) returns row with effective_at=t2 when rows exist at t1, t2, t3 (t3 is future)
- findCurrentRate returns Optional.empty() when no row has effective_at <= asOf
- findHistory returns all rows for ccy_pair newest-first
- findAllCurrentRates returns exactly one row per ccy_pair (the most recent)
- findCurrentRate('usd_usd', NOW()) returns the seed identity row with rate=1.0
**Depends on:** 10.5-T03

### 10.5-T05 — Implement TreasuryRateAuditRepository: insert and history queries  _(25 min)_
**Context:** treasury_rate_audit is append-only. TreasuryRateAuditRepository must support: save(TreasuryRateAudit) for inserts only (no update/delete methods exposed); findByCcyPairOrderByCreatedAtDesc(ccyPair, pageable) for history view; findByActorUserId(actorUserId, pageable) for audit log filtering. The audit table uses insert-only DB grants (from T02). Actor data (userId, displayName, IP) comes from the authenticated portal session context.
**Steps:** Create TreasuryRateAuditRepository extending JpaRepository<TreasuryRateAudit, Long>; Expose save() for inserts; do NOT expose saveAndFlush or any bulk update/delete derived queries; Add findByCcyPairOrderByCreatedAtDesc(String ccyPair, Pageable pageable); Add findByActorUserIdOrderByCreatedAtDesc(String actorUserId, Pageable pageable); Write unit test: verify that calling save() results in a row in DB and that the row is immutable (no update path exists on the repository interface)
**Deliverable:** TreasuryRateAuditRepository.java with save and query methods
**Acceptance / logic checks:**
- save() inserts a row with all required fields populated
- findByCcyPairOrderByCreatedAtDesc returns rows newest-first for that ccy_pair
- findByActorUserId filters correctly for a given actor
- No updateBy... or deleteBy... method signatures exist on the repository interface
- Attempting to call entityManager.merge() on an audit entity in a test throws or produces a new insert, never an UPDATE SQL statement
**Depends on:** 10.5-T03

### 10.5-T06 — Implement TreasuryRateService.getCurrentRate(ccyPair): rate lookup with RATE_UNAVAILABLE guard  _(35 min)_
**Context:** The rate engine calls TreasuryRateService.getCurrentRate(ccyPair, asOf) to obtain the BigDecimal cost rate. If no row is found (treasury never updated for that ccy_pair, or all rows are future-dated), the service must throw RateUnavailableException (mapped to error code RATE_UNAVAILABLE). The usd_usd identity pair always resolves to 1.0 (seed row). Per RATE-04 assumption A3: Phase 1 does not block on staleness - the system uses whatever value is stored. The method signature: BigDecimal getCurrentRate(String ccyPair, OffsetDateTime asOf) throws RateUnavailableException.
**Steps:** Create TreasuryRateService.java (Spring @Service); Inject TreasuryRateRepository; Implement getCurrentRate(ccyPair, asOf): call findCurrentRate; if empty throw RateUnavailableException(ccyPair); Create RateUnavailableException.java with field ccyPair and message 'No treasury rate available for {ccyPair} as of {asOf}'; Write unit tests: (a) rate found returns BigDecimal; (b) no row -> throws RateUnavailableException; (c) future-only rows -> throws RateUnavailableException
**Deliverable:** TreasuryRateService.java with getCurrentRate method and RateUnavailableException.java
**Acceptance / logic checks:**
- getCurrentRate('usd_krw', NOW()) returns 1350.00 when that is the stored rate
- getCurrentRate for a ccy_pair with no rows throws RateUnavailableException
- getCurrentRate where all rows have effective_at in the future throws RateUnavailableException
- RateUnavailableException.getCcyPair() returns the requested ccy_pair string
- getCurrentRate('usd_usd', NOW()) returns 1.0 from the identity seed row
**Depends on:** 10.5-T04

### 10.5-T07 — Implement TreasuryRateService.saveRate(): validate, persist, audit  _(45 min)_
**Context:** When an Ops Operator saves a new treasury rate via the admin portal, TreasuryRateService.saveRate() must: (1) validate rate > 0; (2) enforce guard-rail: if a previous rate exists and the new rate deviates by more than the configured guard-rail threshold (default 10%) from it, the caller must supply confirmed=true or the service throws GuardRailWarningException; (3) insert a new treasury_rate row (do NOT update the old row - all entries are historical); (4) write a treasury_rate_audit row with event_type=RATE_CREATED, previousRate=old rate or null, actor fields from the passed ActorContext; (5) return the saved TreasuryRateDTO. Method: TreasuryRateDTO saveRate(SaveRateCommand cmd, ActorContext actor, boolean confirmed).
**Steps:** Add saveRate(SaveRateCommand cmd, ActorContext actor, boolean confirmed) to TreasuryRateService; Validate cmd.rate > 0; throw InvalidRateException if not; Load current rate for cmd.ccyPair; compute deviation = abs(newRate - oldRate) / oldRate * 100; If deviation > guardRailPercent (inject from config, default 10.0) AND confirmed == false, throw GuardRailWarningException(deviation, oldRate); Insert new TreasuryRate row with source=MANUAL and effective_at = cmd.effectiveAt (default NOW() if not supplied); Insert TreasuryRateAudit row: eventType=RATE_CREATED, previousRate=oldRate or null, newRate, actor fields from ActorContext, notes from cmd
**Deliverable:** saveRate() method on TreasuryRateService with GuardRailWarningException and InvalidRateException
**Acceptance / logic checks:**
- saveRate with rate=0 throws InvalidRateException
- saveRate with rate=1500 when existing rate=1350 (deviation=11.1%) and confirmed=false throws GuardRailWarningException
- saveRate with same input and confirmed=true inserts the row and returns a TreasuryRateDTO
- Inserted row has source=MANUAL and effective_at equal to cmd.effectiveAt or current timestamp
- Audit row is created with previousRate=1350.00 and newRate=1500.00 and actor fields populated
**Depends on:** 10.5-T05, 10.5-T06

### 10.5-T08 — Implement TreasuryRateService: effective-date future-dating and same-ccyPair active history  _(35 min)_
**Context:** Ops may pre-announce a rate by supplying effective_at in the future (e.g. next business day 00:00 UTC). saveRate() must allow future effective_at values. The current rate at any point in time is the row with the greatest effective_at <= that point. Multiple rows for the same ccy_pair are allowed and represent a full history (no row is ever updated or deleted). The UI history view shows Effective Until = effective_at of the next newer row, or 'Current' if none. TreasuryRateService needs getHistory(ccyPair, pageable) returning List<TreasuryRateHistoryDTO> where each row includes effectiveUntil computed as next row's effectiveAt or null.
**Steps:** Add getHistory(String ccyPair, Pageable pageable) to TreasuryRateService; Retrieve page from TreasuryRateRepository.findHistory(ccyPair, pageable); For each row, compute effectiveUntil = effectiveAt of the chronologically next row; set null (current) for the most recent row; Map to TreasuryRateHistoryDTO: rateValue, effectiveFrom, effectiveUntil (nullable), updatedBy, updatedAt, notes; Write unit test: insert 3 rows for usd_krw at t1=2026-01-01, t2=2026-02-01, t3=2026-03-01; assert getHistory returns t3 with effectiveUntil=null, t2 with effectiveUntil=t3, t1 with effectiveUntil=t2
**Deliverable:** getHistory() method and TreasuryRateHistoryDTO with effectiveUntil computation
**Acceptance / logic checks:**
- Most recent row (greatest effectiveAt) has effectiveUntil=null in DTO
- Middle row has effectiveUntil = effectiveAt of the next newer row
- Rows are returned newest-first
- A future-dated row (effectiveAt > NOW()) appears in history with effectiveUntil=null until superseded
- getHistory for an unknown ccy_pair returns an empty list (no exception)
**Depends on:** 10.5-T04

### 10.5-T09 — Implement TreasuryRateService: getAllCurrentRates() for rate list view  _(35 min)_
**Context:** The FX Rates list view (PRD-07 §7.2) shows one row per registered currency with its current rate (greatest effective_at <= NOW()), last-updated-by, last-updated-at, effective-from, and source. TreasuryRateService.getAllCurrentRates() must return one TreasuryRateListDTO per registered ccy_pair. The usd_usd row must appear with rate=1.0 and source=MANUAL but must NOT be editable via the save endpoint. The list should also include registered currencies that have no rate yet (rate=null, indicating action required).
**Steps:** Add getAllCurrentRates(OffsetDateTime asOf) to TreasuryRateService; Call TreasuryRateRepository.findAllCurrentRates(asOf) to get one row per ccy_pair; Also load all registered ccy_pairs (from a CurrencyRegistry or config list); merge to include pairs with no rate (rate=null); Map each to TreasuryRateListDTO: ccyPair, ratePairDisplay (e.g. USD / KRW), currentRate, lastUpdatedBy, lastUpdatedAt, effectiveFrom, source; Write unit test: seed usd_krw and usd_mnt rows but not usd_eur; assert eur appears in list with null currentRate
**Deliverable:** getAllCurrentRates() method and TreasuryRateListDTO
**Acceptance / logic checks:**
- Returns one entry per registered ccy_pair (not per row)
- usd_krw entry shows rate=1350.00 when that is the most recent row
- usd_usd entry shows rate=1.0000 and source=MANUAL
- A registered ccy_pair with no rate rows has currentRate=null in the DTO
- List is ordered alphabetically by ccyPair
**Depends on:** 10.5-T04

### 10.5-T10 — Create CurrencyRegistry: registered currencies config and validation  _(40 min)_
**Context:** Only registered currencies can have treasury rates entered against them (PRD-07 §7.5). USD is always present and cannot be removed. The CurrencyRegistry maintains the list of known ccy codes. In Phase 1 this can be a DB-backed table or a config list. TreasuryRateService.saveRate() must reject attempts to save a rate for an unregistered currency. Additionally, USD (ccy_pair=usd_usd) must be treated as an identity currency: it always has rate=1.0 and source=MANUAL, and saveRate() must reject attempts to update it.
**Steps:** Create currency_registry DB table (or config): id BIGINT PK, ccy_code VARCHAR(10) NOT NULL UNIQUE, display_name VARCHAR(60), is_identity BOOLEAN NOT NULL DEFAULT FALSE, is_active BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMPTZ; Add migration V10_5_003__create_currency_registry.sql seeding USD (is_identity=true), KRW, MNT, EUR, THB as active; Create CurrencyRegistry.java service with isRegistered(ccyCode) and isIdentity(ccyCode) methods; Add validation in TreasuryRateService.saveRate(): if !isRegistered(ccyPair) throw UnknownCurrencyException; if isIdentity throw IdentityRateMutationException; Write unit test: saveRate for usd_usd throws IdentityRateMutationException; saveRate for 'usd_xyz' (unregistered) throws UnknownCurrencyException
**Deliverable:** currency_registry migration, CurrencyRegistry.java, and validation hooks in saveRate()
**Acceptance / logic checks:**
- saveRate for usd_usd (identity) throws IdentityRateMutationException
- saveRate for an unregistered ccy_pair throws UnknownCurrencyException
- getAllCurrentRates() returns usd_usd with is_identity=true flag in DTO
- isRegistered('usd_krw') returns true; isRegistered('usd_xyz') returns false
- USD row cannot be deleted from currency_registry by normal application paths
**Depends on:** 10.5-T07

### 10.5-T11 — Implement GET /admin/v1/fx-rates REST endpoint: current rate list  _(35 min)_
**Context:** Admin API endpoint for the FX Rates list view. Requires authenticated portal session with role OPS_OPERATOR, FINANCE_ANALYST, ADMIN_VIEWER, or SUPER_ADMIN (all roles can view rates per PRD-07 §12.3 permission matrix). Returns the list of current treasury rates from TreasuryRateService.getAllCurrentRates(). Response: array of {ccyPair, ratePairDisplay, currentRate, lastUpdatedBy, lastUpdatedAt, effectiveFrom, source}. HTTP 200 on success. If a rate is missing for a registered currency, currentRate field is null.
**Steps:** Create FxRateController.java in the admin API package with @RestController and base path /admin/v1/fx-rates; Implement GET / handler calling TreasuryRateService.getAllCurrentRates(OffsetDateTime.now()); Apply @PreAuthorize or role-check middleware for OPS_OPERATOR | FINANCE_ANALYST | ADMIN_VIEWER | SUPER_ADMIN; Map TreasuryRateListDTO list to JSON response array; Write controller unit test: mock service returns 2 entries; assert HTTP 200 and JSON array length = 2; Write security test: unauthenticated request returns HTTP 401; request with PARTNER role returns HTTP 403
**Deliverable:** GET /admin/v1/fx-rates endpoint in FxRateController.java
**Acceptance / logic checks:**
- GET /admin/v1/fx-rates returns HTTP 200 with JSON array when called by OPS_OPERATOR
- Unauthenticated request returns HTTP 401
- Request with PARTNER role returns HTTP 403
- Response includes usd_usd entry with currentRate=1.0
- A registered currency with no rate has currentRate=null in response (not omitted)
**Depends on:** 10.5-T09, 10.5-T10

### 10.5-T12 — Implement GET /admin/v1/fx-rates/{ccyPair}/history REST endpoint  _(30 min)_
**Context:** Rate history endpoint for a single currency pair (PRD-07 §7.4). All roles with view-rate access (OPS_OPERATOR, FINANCE_ANALYST, ADMIN_VIEWER, SUPER_ADMIN) can view history. Returns paginated list of TreasuryRateHistoryDTO rows for the given ccy_pair: rateValue, effectiveFrom, effectiveUntil (null = current), updatedBy, updatedAt, notes. Path param ccyPair is case-insensitive (normalise to lowercase). Pagination: page and size query params; default size=50, max=200. Returns HTTP 404 if ccy_pair is not registered.
**Steps:** Add GET /{ccyPair}/history endpoint to FxRateController; Normalise ccyPair path param to lowercase; Validate ccyPair is registered via CurrencyRegistry; return 404 with body {error: 'UNKNOWN_CURRENCY', ccyPair: ...} if not; Call TreasuryRateService.getHistory(ccyPair, pageable); Return {data: [...], page, size, totalElements} envelope; Write controller test: usd_krw with 5 rows returns correct pagination; unknown ccy returns 404
**Deliverable:** GET /admin/v1/fx-rates/{ccyPair}/history endpoint
**Acceptance / logic checks:**
- Returns HTTP 200 with paginated history array for registered ccy_pair
- Returns HTTP 404 with UNKNOWN_CURRENCY error body for unregistered ccy_pair
- Path param USD_KRW is normalised to usd_krw and resolves correctly
- Most recent row has effectiveUntil=null in response
- Page=0, size=2 with 5 rows returns 2 rows and totalElements=5
**Depends on:** 10.5-T08, 10.5-T11

### 10.5-T13 — Implement POST /admin/v1/fx-rates/{ccyPair} REST endpoint: manual rate entry  _(40 min)_
**Context:** The rate entry endpoint allows OPS_OPERATOR or SUPER_ADMIN (not FINANCE_ANALYST or ADMIN_VIEWER per permission matrix) to submit a new manual rate. Request body: {rate: decimal, effectiveAt: ISO-8601 datetime (optional, default NOW()), notes: string (optional)}. The service layer handles guard-rail logic (T07). If the guard-rail fires (deviation > 10% from previous rate) AND the request lacks confirmed=true, the endpoint returns HTTP 409 with {error: 'GUARD_RAIL_WARNING', deviation: X, previousRate: Y, message: 'Rate deviates X% from current value Y. Resend with confirmed=true to proceed.'}. On success returns HTTP 201 with the created TreasuryRateDTO.
**Steps:** Add POST /{ccyPair} endpoint to FxRateController; Extract actor context (userId, displayName, IP) from authenticated session; Parse request body to SaveRateCommand; extract optional confirmed=true query/body param; Call TreasuryRateService.saveRate(cmd, actor, confirmed); Catch GuardRailWarningException and return HTTP 409 with structured error body; Catch InvalidRateException and return HTTP 400; catch UnknownCurrencyException and return HTTP 404; catch IdentityRateMutationException and return HTTP 422; Return HTTP 201 Created with TreasuryRateDTO body on success
**Deliverable:** POST /admin/v1/fx-rates/{ccyPair} endpoint with guard-rail 409 response
**Acceptance / logic checks:**
- POST with rate=1500 for usd_krw (prev=1350, deviation=11.1%) and no confirmed param returns HTTP 409 with deviation=11.1 and previousRate=1350.00
- POST with same body and confirmed=true returns HTTP 201 and new rate row
- POST with rate=0 returns HTTP 400
- POST for usd_usd returns HTTP 422
- FINANCE_ANALYST role returns HTTP 403
**Depends on:** 10.5-T07, 10.5-T10, 10.5-T12

### 10.5-T14 — Implement guard-rail threshold config: configurable ±% via application properties  _(30 min)_
**Context:** The guard-rail deviation threshold for rate entry is configurable (PRD-07 Assumption A-09: default 10%, configurable via system parameter; UX spec §9 references ±20% for rule mapping page - the rate list uses ±10% per §7.3). The threshold must be externally configurable (application.yml property fx.rate.guard-rail.threshold-pct, default 10.0). TreasuryRateService must read this value at startup. Allow range 1.0 to 50.0; reject out-of-range config at startup with a clear error message.
**Steps:** Add @ConfigurationProperties class FxRateProperties with field guardRailThresholdPct (double, default 10.0); Inject FxRateProperties into TreasuryRateService and use guardRailThresholdPct in the deviation check; Add validation: @Min(1) @Max(50) on the property field, or manual check in @PostConstruct; Add application.yml entry fx.rate.guard-rail.threshold-pct: 10.0; Write unit test: set threshold to 5.0; submit new rate of 1400 when previous=1350 (deviation=3.7%); assert no GuardRailWarningException; Write unit test: same threshold=5.0; submit 1430 (deviation=5.9%); assert GuardRailWarningException fires
**Deliverable:** FxRateProperties.java config class with guardRailThresholdPct wired into TreasuryRateService
**Acceptance / logic checks:**
- Default threshold=10.0 is used when property is not set
- Setting fx.rate.guard-rail.threshold-pct=5.0 causes warning at 5.1% deviation but not at 4.9%
- Invalid property value (e.g. 0.5) causes startup failure with descriptive error
- FxRateProperties is exposed as a Spring bean and injectable
- GuardRailWarningException carries the configured threshold value in its fields
**Depends on:** 10.5-T07

### 10.5-T15 — Unit tests: TreasuryRateService saveRate() - validation and guard-rail edge cases  _(45 min)_
**Context:** Explicit unit-test ticket for edge-case coverage of TreasuryRateService.saveRate(). Rate convention: treasury.usd_{ccy} = units of {ccy} per 1 USD. Fixture rates from QA spec: usd_krw=1350.00, usd_mnt=3500.00, usd_eur=0.9200, usd_thb=35.500. Guard-rail default=10%. All tests use mocked TreasuryRateRepository and TreasuryRateAuditRepository.
**Steps:** Create TreasuryRateServiceTest.java with JUnit 5 + Mockito; Test case A: rate=0 -> InvalidRateException; Test case B: rate=-0.01 -> InvalidRateException; Test case C: usd_krw existing=1350, new=1200 (deviation=11.1%), confirmed=false -> GuardRailWarningException with deviation approx 11.1 and previousRate=1350.00; Test case D: same as C with confirmed=true -> inserts row and audit; returns DTO with rate=1200; Test case E: first-ever rate for a new ccy_pair (previousRate null), any positive rate -> no guard-rail warning even if deviation would be large; Test case F: effectiveAt is future date 2026-12-31 00:00 UTC -> row saved with that effectiveAt; findCurrentRate(ccyPair, 2026-12-30) returns old rate; findCurrentRate(ccyPair, 2026-12-31T00:01) returns new
**Deliverable:** TreasuryRateServiceTest.java covering 6 edge-case scenarios
**Acceptance / logic checks:**
- All 6 test cases pass (green)
- Test D verifies audit row has previousRate=1350 and newRate=1200
- Test E explicitly asserts no GuardRailWarningException when no previous rate exists
- Test F verifies temporal isolation: old rate still effective before future effective_at
- Test C verifies GuardRailWarningException.getDeviation() > 10.0 and < 12.0
**Depends on:** 10.5-T07, 10.5-T14

### 10.5-T16 — Unit tests: TreasuryRateService getCurrentRate() - rate resolution and RATE_UNAVAILABLE  _(35 min)_
**Context:** Explicit unit-test ticket for TreasuryRateService.getCurrentRate() edge cases. Rate resolution rule: return the row with greatest effective_at <= asOf. If none, throw RateUnavailableException. RATE-04 Assumption A3: Phase 1 does not enforce staleness - any stored value is used as-is. The usd_usd identity pair always resolves (from seed row). All tests use mocked TreasuryRateRepository.
**Steps:** Create TreasuryRateGetRateTest.java with JUnit 5 + Mockito; Test case A: two rows usd_krw at t1=1350, t2=1400; query at t2+1s -> returns 1400; Test case B: same rows; query at t1+1s -> returns 1350 (t2 is future relative to query time); Test case C: no rows for usd_mnt -> RateUnavailableException with ccyPair='usd_mnt'; Test case D: only future row for usd_eur (effectiveAt=tomorrow) -> RateUnavailableException; Test case E: usd_usd -> always returns BigDecimal(1.0) via seed row; Test case F: very old stale row (1 year ago) for usd_krw -> still returned with no exception (Phase 1 no staleness block)
**Deliverable:** TreasuryRateGetRateTest.java covering 6 rate-resolution scenarios
**Acceptance / logic checks:**
- All 6 test cases pass (green)
- Test B verifies temporal priority: query time determines which row is effective
- Test C verifies RateUnavailableException.getCcyPair() = 'usd_mnt'
- Test D verifies future-only row does not satisfy <= asOf and triggers exception
- Test F passes without exception (confirming no staleness guard in Phase 1)
**Depends on:** 10.5-T06

### 10.5-T17 — Integrate TreasuryRateService into RateEngine: resolve cost_rate_coll and cost_rate_pay  _(45 min)_
**Context:** The rate engine resolves cost rates at quote-issuance time. Per RATE-04: cost_rate_coll = treasury.usd_{settle_a_ccy} and cost_rate_pay = treasury.usd_{settle_b_ccy}. Identity leg: if settle_A = USD, cost_rate_coll = 1.0 (no lookup needed; guaranteed by usd_usd seed). The RateEngine must call TreasuryRateService.getCurrentRate(ccyPair, quoteIssuedAt) for each non-identity leg. RateUnavailableException must propagate as RATE_UNAVAILABLE error to the partner API. This ticket wires TreasuryRateService into the existing RateEngine (or its rate-resolution method) replacing any stub/hardcoded value.
**Steps:** Locate the RateEngine class and its rate-resolution logic (resolveCostRate method or equivalent); Inject TreasuryRateService into RateEngine; For settle_a_ccy: if ccy == 'USD' use 1.0 (identity); else call getCurrentRate('usd_'+settle_a_ccy.toLowerCase(), quoteIssuedAt); For settle_b_ccy: same pattern; Wrap RateUnavailableException as a domain exception that maps to HTTP 503 / error code RATE_UNAVAILABLE in the partner API layer; Write integration test: seed usd_krw=1380 in DB; call RateEngine for KRW payout; assert cost_rate_pay=1380.00
**Deliverable:** RateEngine wired to TreasuryRateService for live rate resolution, replacing any stub
**Acceptance / logic checks:**
- RateEngine resolves cost_rate_pay=1380.00 from DB when treasury.usd_krw=1380.00
- RateEngine uses cost_rate_coll=1.0 for USD settle_a (identity leg) without a DB lookup
- RateEngine throws RATE_UNAVAILABLE when no treasury row exists for the requested ccy_pair
- Integration test with seeded rate passes and the returned quote uses the seeded rate value
- No hardcoded or stub rate values remain in RateEngine after this change
**Depends on:** 10.5-T06, 10.5-T10

### 10.5-T18 — Implement rate snapshot at quote commit: lock treasury rate into transaction record  _(50 min)_
**Context:** RATE-04 rate-lock rule: at commit time (when scheme call succeeds), all USD pool values and derived rates are permanently recorded on the transaction. Subsequent treasury/margin changes have no effect on committed transactions. The transaction record must store the rate values used at quote time: cost_rate_coll_locked, cost_rate_pay_locked (both DECIMAL(20,8)). These are populated from the quote snapshot at commit. This ticket ensures the fields exist on the transaction entity and that they are written at commit and never overwritten. A BOK report reading from the committed record must see the locked values regardless of current treasury state.
**Steps:** Add columns cost_rate_coll_locked DECIMAL(20,8) and cost_rate_pay_locked DECIMAL(20,8) to the transaction/payment table via migration V10_5_004__add_locked_rate_columns.sql (nullable until committed, then populated); Add corresponding fields to the Transaction/Payment JPA entity; In TransactionOrchestrator (or commit handler), write these fields at commit time from the quote snapshot; use a single UPDATE that sets both fields atomically with status change to COMMITTED; Ensure no code path exists that updates these fields post-commit (add a guard: if already set, throw IllegalStateException); Write integration test: commit a transaction with treasury rate 1350; update treasury to 1400; assert transaction record still shows cost_rate_pay_locked=1350.00
**Deliverable:** Migration V10_5_004 and locked-rate write logic in TransactionOrchestrator
**Acceptance / logic checks:**
- Committed transaction has cost_rate_coll_locked and cost_rate_pay_locked populated
- After updating treasury.usd_krw from 1350 to 1400, a previously committed transaction still shows cost_rate_pay_locked=1350.00
- Attempting to overwrite a locked rate (call commit path twice) throws or is a no-op (no second UPDATE)
- Both fields are null for PENDING transactions (pre-commit)
- Migration V10_5_004 applies cleanly on top of prior migrations
**Depends on:** 10.5-T06, 10.5-T17

### 10.5-T19 — Audit log wiring: emit treasury_rate.changed audit event on every saveRate()  _(40 min)_
**Context:** PRD-07 §13 requires every FX rate change to produce an audit log entry with: event type 'treasury_rate.changed', entity type 'treasury_rate', entity id (new row id), previous value (serialized old rate + effective_at), new value (serialized new rate + effective_at), actor user id, display name, IP, timestamp. The audit entry must be inserted in the same DB transaction as the treasury_rate row insert. Failure to insert the audit row must roll back the rate insert (atomicity). The audit entry description must be human-readable, e.g. 'USD/KRW rate updated from 1350.00 to 1500.00 by ops@gme.com'.
**Steps:** Verify @Transactional annotation on TreasuryRateService.saveRate() covers both the treasury_rate insert and the TreasuryRateAudit insert; Ensure TreasuryRateAuditRepository.save() is called within the same transaction as TreasuryRate insert; Build the audit entry description string: 'USD/{CCY} rate updated from {prev} to {new} by {actor}'; For first-ever rate entry (no previous), use description 'USD/{CCY} initial rate set to {new} by {actor}'; Write integration test: force TreasuryRateAuditRepository.save() to throw; assert the treasury_rate row is also rolled back
**Deliverable:** @Transactional saveRate() with audit row atomically co-inserted, integration test for rollback
**Acceptance / logic checks:**
- Both treasury_rate and treasury_rate_audit rows are inserted in a single transaction
- If audit insert fails, treasury_rate insert is rolled back (no orphan rate row)
- Audit description for update reads 'USD/KRW rate updated from 1350.00 to 1500.00 by {actor}'
- Audit description for first entry reads 'USD/MNT initial rate set to 3500.00 by {actor}'
- Audit entry actor_user_id matches the portal session userId
**Depends on:** 10.5-T07, 10.5-T05

### 10.5-T20 — Implement GET /admin/v1/fx-rates/{ccyPair} endpoint: single currency current rate detail  _(30 min)_
**Context:** Endpoint for the rate detail / update form to pre-populate. Returns the current rate details for a single ccy_pair including: ccyPair, ratePairDisplay (e.g. USD / KRW), currentRate, lastUpdatedBy, lastUpdatedAt, effectiveFrom, source, notes (from last entry). Required roles: OPS_OPERATOR, FINANCE_ANALYST, ADMIN_VIEWER, SUPER_ADMIN. Returns HTTP 404 if ccy_pair is not registered. Also returns isIdentity=true for usd_usd to allow the UI to disable the edit button.
**Steps:** Add GET /{ccyPair} endpoint to FxRateController; Normalise ccyPair to lowercase; Check CurrencyRegistry; return 404 if unregistered; Call TreasuryRateService.getCurrentRate(ccyPair, NOW()) - if no row, return 200 with currentRate=null (rate not yet set); Also call getHistory(ccyPair, first page) to get the notes from the latest entry; Return TreasuryRateDetailDTO: ccyPair, ratePairDisplay, currentRate, lastUpdatedBy, lastUpdatedAt, effectiveFrom, source, notes, isIdentity; Write controller test: usd_krw returns 200 with currentRate=1350; usd_xyz returns 404
**Deliverable:** GET /admin/v1/fx-rates/{ccyPair} endpoint returning TreasuryRateDetailDTO
**Acceptance / logic checks:**
- Returns HTTP 200 with currentRate=1350.00 for registered usd_krw
- Returns HTTP 404 for unregistered ccy_pair
- usd_usd response has isIdentity=true and currentRate=1.0
- Registered currency with no rate rows returns HTTP 200 with currentRate=null (not 404)
- ADMIN_VIEWER role can access (read-only)
**Depends on:** 10.5-T11, 10.5-T13

### 10.5-T21 — Implement rate history CSV export: GET /admin/v1/fx-rates/{ccyPair}/history/export  _(35 min)_
**Context:** PRD-07 §13.5 requires audit/history export to CSV. The rate history export endpoint returns all historical rows for a ccy_pair as a CSV file. Columns: Rate Value, Effective From (UTC ISO-8601), Effective Until (UTC ISO-8601 or 'Current'), Updated By, Updated At (UTC), Notes. The response Content-Type must be text/csv and Content-Disposition attachment filename should be fx_rate_history_{ccyPair}_{yyyyMMdd}.csv. Maximum rows: 10,000 (hard limit; return HTTP 400 with message if exceeded - this is a future-proofing guard). Required roles: OPS_OPERATOR, FINANCE_ANALYST, SUPER_ADMIN (ADMIN_VIEWER excluded per permission matrix for exports).
**Steps:** Add GET /{ccyPair}/history/export endpoint to FxRateController; Check role: OPS_OPERATOR | FINANCE_ANALYST | SUPER_ADMIN; reject ADMIN_VIEWER with 403; Validate ccy_pair registered; return 404 if not; Load full history (unpaginated) up to 10,001 rows; if count > 10,000 return HTTP 400 with message 'History exceeds export limit of 10,000 rows'; Build CSV string with header row and data rows; set effectiveUntil as ISO-8601 or string 'Current'; Set response headers: Content-Type: text/csv; Content-Disposition: attachment; filename=fx_rate_history_usd_krw_20260605.csv
**Deliverable:** GET /admin/v1/fx-rates/{ccyPair}/history/export CSV endpoint
**Acceptance / logic checks:**
- Returns CSV file with correct Content-Disposition filename for usd_krw export
- CSV has header row: Rate Value,Effective From,Effective Until,Updated By,Updated At,Notes
- Most-recent row has Effective Until = 'Current' in CSV
- ADMIN_VIEWER role returns HTTP 403
- More than 10,000 history rows triggers HTTP 400 with descriptive message
**Depends on:** 10.5-T12

### 10.5-T22 — Unit tests: rate history effectiveUntil computation and pagination  _(30 min)_
**Context:** Explicit unit test for TreasuryRateService.getHistory() correctness. History rows must be returned newest-first with effectiveUntil computed as the next newer row's effectiveAt, or null for the most recent row. Pagination must be correct: page 0 of size 2 for 5 rows returns 2 rows, totalElements=5. Edge cases: single row (effectiveUntil=null), exactly 2 rows, future-dated rows.
**Steps:** Create TreasuryRateHistoryTest.java with JUnit 5 + Mockito; Test A: 3 rows at t1, t2, t3; getHistory page 0 size 10 -> 3 rows, t3 effectiveUntil=null, t2 effectiveUntil=t3, t1 effectiveUntil=t2; Test B: 1 row -> effectiveUntil=null; Test C: 5 rows; getHistory page 0 size 2 -> 2 rows returned, totalElements=5; Test D: 2 rows where t2 is future-dated -> newest row is t2 (effectiveUntil=null), t1 effectiveUntil=t2; Test E: no rows -> empty list, no exception
**Deliverable:** TreasuryRateHistoryTest.java with 5 test cases
**Acceptance / logic checks:**
- All 5 test cases pass (green)
- Test A: t2 row has effectiveUntil equal to t3's effectiveAt (not t3's createdAt)
- Test C: page 1 size 2 returns rows 3 and 4 (correct offset)
- Test D: future-dated row still appears as effectiveUntil=null since it is the most recent
- Test E returns empty Page without NullPointerException
**Depends on:** 10.5-T08

### 10.5-T23 — Integration test: full rate update flow - save, audit, history, and rate engine resolution  _(50 min)_
**Context:** End-to-end integration test covering the complete manual rate update flow. Exercises: POST rate -> verify treasury_rate row inserted -> verify audit row inserted -> GET history shows new row -> GET current rate shows new value -> simulate rate engine quote and verify cost_rate_pay uses new value. Uses a real test DB (PostgreSQL via Testcontainers). Rate fixture: usd_krw starts at 1350.00; update to 1380.00; verify engine uses 1380.00 for a KRW payout of 10000.
**Steps:** Create FxRateIntegrationTest.java using @SpringBootTest and Testcontainers PostgreSQL; Seed: insert usd_krw=1350.00 row; Call POST /admin/v1/fx-rates/usd_krw with {rate:1380.00, notes:'Daily update'} as OPS_OPERATOR -> assert HTTP 201; Call GET /admin/v1/fx-rates/usd_krw -> assert currentRate=1380.00; Call GET /admin/v1/fx-rates/usd_krw/history -> assert first row has rateValue=1380.00 and second has 1350.00; Call TreasuryRateService.getCurrentRate('usd_krw', NOW()) -> assert returns 1380.00; Assert treasury_rate_audit table has 1 new row with previousRate=1350.00 and newRate=1380.00
**Deliverable:** FxRateIntegrationTest.java with full end-to-end scenario
**Acceptance / logic checks:**
- POST returns HTTP 201 and new treasury_rate row exists in DB
- Audit row has previousRate=1350.00 and newRate=1380.00 with actor fields populated
- GET current rate returns 1380.00 after update
- History shows 1380.00 as current and 1350.00 as previous with effectiveUntil set correctly
- getCurrentRate returns 1380.00 (rate engine would use this value for cost_rate_pay in KRW payout calculation)
**Depends on:** 10.5-T13, 10.5-T19, 10.5-T17

### 10.5-T24 — Integration test: guard-rail 409 round-trip and confirmed=true acceptance  _(45 min)_
**Context:** Integration test for the guard-rail warning flow. Starting rate usd_krw=1350.00; submit 1560.00 (deviation=15.6%, above default 10% threshold). First POST must return HTTP 409 with deviation and previousRate. Second POST with confirmed=true must return HTTP 201. Verify DB state, audit, and that a PENDING transaction quote issued before the update still uses the old rate (T18 rate-lock), while a new quote issued after uses 1560.00.
**Steps:** Seed usd_krw=1350.00 in test DB; POST /admin/v1/fx-rates/usd_krw {rate:1560.00} -> assert HTTP 409, body.error='GUARD_RAIL_WARNING', body.deviation approx 15.6, body.previousRate=1350.00; POST same body with confirmed=true -> assert HTTP 201; Assert treasury_rate table has new row with rate=1560.00; Assert audit row has previousRate=1350.00 and newRate=1560.00; Assert getCurrentRate('usd_krw', time just before update) returns 1350.00; Assert getCurrentRate('usd_krw', time after update) returns 1560.00
**Deliverable:** GuardRailIntegrationTest.java covering 409 and confirmed=true round-trip
**Acceptance / logic checks:**
- First POST returns HTTP 409 with GUARD_RAIL_WARNING and deviation between 15 and 16
- Confirmed POST returns HTTP 201 and DB shows new rate row
- Temporal query before update effective_at returns 1350.00
- Temporal query after effective_at returns 1560.00
- Audit row exists with previousRate=1350.00 and newRate=1560.00
**Depends on:** 10.5-T13, 10.5-T14, 10.5-T23

### 10.5-T25 — Write OpenAPI documentation for FX rate management endpoints  _(35 min)_
**Context:** PRD-07 requires the Admin API to be documented. The four FX rate endpoints (GET list, GET detail, GET history, POST save) and the history CSV export must be documented in OpenAPI 3.0 YAML/annotation format. Each endpoint needs: summary, description (including role requirements), request/response schema, error codes (400/401/403/404/409/422), and example request/response. The guard-rail 409 response schema must be documented with fields: error, deviation, previousRate, message.
**Steps:** Add @Operation, @ApiResponse, @Parameter annotations to all endpoints in FxRateController.java; Document GET / list: role requirement, response schema TreasuryRateListDTO array; Document GET /{ccyPair}: 200, 404 responses; note isIdentity field for usd_usd; Document POST /{ccyPair}: 201, 400, 403, 404, 409 (GUARD_RAIL_WARNING), 422 responses; document confirmed query param; Document GET /{ccyPair}/history: pagination params, 404; Document GET /{ccyPair}/history/export: 200 CSV, 400 (row limit), 403, 404; Verify springdoc-openapi generates correct spec by loading /v3/api-docs and checking fx-rates paths exist
**Deliverable:** OpenAPI annotations on FxRateController.java with all 5 endpoints documented
**Acceptance / logic checks:**
- GET /v3/api-docs includes /admin/v1/fx-rates path with GET operation
- POST /{ccyPair} documents 409 response with schema containing error, deviation, previousRate fields
- confirmed query parameter is documented on the POST endpoint
- Export endpoint documents text/csv as response media type
- All error codes 400, 401, 403, 404, 409, 422 are listed in @ApiResponse annotations where applicable
**Depends on:** 10.5-T13, 10.5-T21


## WBS 10.6 — Transaction monitoring
### 10.6-T01 — Add DB indexes for transaction search filter dimensions  _(30 min)_
**Context:** WBS 10.6 — Transaction Monitoring UI (Admin System). The transaction table has columns: partner_id, scheme_id, status, direction, payment_mode, committed_at, hub_txn_ref, txn_ref (partner_txn_ref), merchant_id, failure_origin, failure_code. Composite indexes already exist per DAT-03: (partner_id, committed_at), (scheme_id, completed_at), (status, committed_at), (scheme_ref), (hub_txn_ref). The search UI (PRD-07 §8.2) also filters by: partner_txn_ref (exact), merchant_id (partial LIKE), failure_origin, failure_code, and payout amount range. Slow filter paths need covering indexes.
**Steps:** Review existing indexes on the transaction table in the migration history.; Add index on (partner_txn_ref) for exact-match lookup.; Add index on (merchant_id) supporting LIKE prefix search (btree sufficient for prefix).; Add composite index on (failure_origin, status) for failure-origin filter combined with status.; Add index on (target_payout, payout_ccy) for amount-range filter.; Write migration file V10_6_001__txn_search_indexes.sql.
**Deliverable:** Migration file V10_6_001__txn_search_indexes.sql with 4 new indexes on the transaction table
**Acceptance / logic checks:**
- \EXPLAIN SELECT on hub_txn_ref = 'HUB-001' uses index scan (not seq scan) on the new index.
- \EXPLAIN SELECT on merchant_id LIKE 'MRC-1%' uses index scan.
- \EXPLAIN SELECT on target_payout BETWEEN 5000 AND 20000 AND payout_ccy = 'KRW' uses index scan.
- Migration is idempotent — running it twice does not error (use CREATE INDEX IF NOT EXISTS).
- No existing index is dropped or altered.

### 10.6-T02 — Define TransactionSearchCriteria DTO and repository search method signature  _(35 min)_
**Context:** WBS 10.6. The Admin System backend (Java + Spring) needs a typed search criteria object covering all 10 filter dimensions from PRD-07 §8.2: partnerId (Set<Long>), schemeId (Set<Long>), direction (Set<String>), status (Set<String>), dateFrom/dateTo (OffsetDateTime, max span 90 days), hubTxnRef (String exact), partnerTxnRef (String exact), merchantId (String partial), failureOrigin (String), failureCode (String), payoutAmountFrom/payoutAmountTo (BigDecimal). Also pagination: page (0-based), pageSize (default 50), sortField (TIMESTAMP|PAYOUT_AMOUNT|PARTNER|STATUS), sortDirection (ASC|DESC).
**Steps:** Create class TransactionSearchCriteria in package com.gmepayplus.admin.txn.dto.; Add all 13 filter fields with types as described in context; add page/pageSize/sortField/sortDirection.; Add bean-validation annotations: dateFrom required if dateTo present; date span <= 90 days (custom constraint DateRangeMax90Days); pageSize max 50.; Create interface TransactionRepository method signature: Page<TransactionSummary> search(TransactionSearchCriteria criteria).; Create TransactionSummary projection interface/record with columns from PRD-07 §8.3: hubTxnRef, committedAt, partnerName, schemeName, direction, mode, targetPayout, payoutCcy, collectionAmount, collectionCcy, serviceCharge, status, durationMs.
**Deliverable:** TransactionSearchCriteria DTO class and TransactionRepository.search signature with TransactionSummary projection
**Acceptance / logic checks:**
- @Valid on TransactionSearchCriteria rejects dateFrom=2025-01-01 dateTo=2025-04-15 (span=104 days > 90) with constraint violation.
- @Valid accepts dateFrom=2025-01-01 dateTo=2025-03-31 (span=89 days).
- pageSize=100 fails validation; pageSize=50 passes.
- TransactionSummary contains all 13 columns from PRD-07 §8.3 — compile check.
- sortField enum rejects unknown value at deserialization.
**Depends on:** 10.6-T01

### 10.6-T03 — Implement JPQL/Criteria query for dynamic transaction search  _(55 min)_
**Context:** WBS 10.6. Implement the repository method TransactionRepository.search(TransactionSearchCriteria) using JPA Criteria API (or QueryDSL) so predicates are added only when the filter field is non-null/non-empty. Join transaction to partner (for partnerName), qr_scheme (schemeName). Apply merchant_id LIKE predicate as lower(t.merchantId) LIKE lower(:mid)+'%'. Apply date filter on committed_at column. Default date range when both dateFrom and dateTo are null: last 24 hours (now() minus 24h). Sort maps: TIMESTAMP -> committed_at, PAYOUT_AMOUNT -> target_payout, PARTNER -> partner.name, STATUS -> status.
**Steps:** Create TransactionRepositoryCustom interface and TransactionRepositoryImpl.; In search(), build a list of Predicates; add each only when the corresponding criteria field is non-null/non-empty.; Join to partner entity for name; join to qr_scheme for name.; Implement LIKE predicate for merchantId (case-insensitive prefix).; Map sortField enum to CriteriaBuilder.asc/desc on the correct path.; Return Page<TransactionSummary> using entityManager.createQuery + count query.
**Deliverable:** TransactionRepositoryImpl.search() — functional Criteria-API query covering all 10 filter dimensions
**Acceptance / logic checks:**
- Calling search with only status=[APPROVED] and partner=[SendMN id] returns only rows matching both filters (verified against test DB rows).
- Calling search with no filters returns transactions from the last 24 hours only (default range).
- Calling search with merchantId='MRC' returns rows where merchant_id starts with 'MRC' case-insensitively.
- Calling search with dateFrom and dateTo spanning 5 days returns 0 rows for an empty date range (no false positives).
- Total count query uses the same predicates as the data query (no count/data mismatch).
**Depends on:** 10.6-T02

### 10.6-T04 — Add GET /admin/v1/transactions search endpoint  _(40 min)_
**Context:** WBS 10.6. Expose the transaction search as a REST endpoint secured by RBAC. Roles OPS_ADMIN, OPS_VIEWER, FINANCE, SUPER_ADMIN may call it. ADMIN_VIEWER may search but cannot see locked pool values (that is enforced in the detail endpoint, not search). Endpoint: GET /admin/v1/transactions with query params matching TransactionSearchCriteria. Returns: { data: [TransactionSummary], page, pageSize, totalCount, totalPages }. Response time SLA: <= 2 seconds for typical query (enforced by index + pagination). Include response header X-Query-Duration-Ms for monitoring.
**Steps:** Create TransactionController with @GetMapping('/admin/v1/transactions').; Bind query params to TransactionSearchCriteria via @Valid @ModelAttribute; return 400 on validation failure with error details.; Call transactionService.search(criteria); service delegates to repository.; Map Page<TransactionSummary> to TransactionListResponse DTO.; Add @PreAuthorize checking role is one of OPS_ADMIN, OPS_VIEWER, FINANCE, SUPER_ADMIN.; Add response header X-Query-Duration-Ms recording elapsed milliseconds.
**Deliverable:** GET /admin/v1/transactions endpoint in TransactionController with RBAC and pagination response
**Acceptance / logic checks:**
- GET /admin/v1/transactions?status=APPROVED&partnerId=2&dateFrom=2025-05-01&dateTo=2025-05-07 returns HTTP 200 with correct result set.
- Request with dateFrom=2025-01-01&dateTo=2025-04-15 returns HTTP 400 with field error on date range.
- Unauthenticated request returns 401; request with role PARTNER returns 403.
- Response includes X-Query-Duration-Ms header with a numeric value.
- Response body contains totalCount, page=0, pageSize=50 for a default request.
**Depends on:** 10.6-T03

### 10.6-T05 — Implement transaction list CSV export endpoint (up to 100,000 rows)  _(55 min)_
**Context:** WBS 10.6. PRD-07 §8.2 specifies CSV export of all matching results up to 100,000 rows. The CSV columns are a superset of the list view: all §8.3 columns plus offer_rate_coll, scheme_ref, merchant_id, merchant_name, full hub_txn_ref. The export streams directly to HTTP response to avoid OOM; uses the same TransactionSearchCriteria filter. Endpoint: GET /admin/v1/transactions/export. Roles: same as search. File name header: Content-Disposition: attachment; filename=gmepay_txns_{from}_{to}.csv.
**Steps:** Add @GetMapping('/admin/v1/transactions/export') to TransactionController.; Validate criteria (same @Valid rules); if date range missing default to last 24h.; Use ScrollableResults or Spring Data Scroll API to stream rows in pages of 1000 without loading all into memory.; Write CSV header row then data rows via Jackson CsvMapper or manual StringBuilder; flush each page to HttpServletResponse OutputStream.; Cap at 100,000 rows; if count would exceed, write up to 100,000 rows and add response header X-Export-Truncated: true.; Set Content-Type: text/csv; charset=UTF-8 and Content-Disposition header with formatted filename.
**Deliverable:** GET /admin/v1/transactions/export streaming CSV endpoint with 100k row cap
**Acceptance / logic checks:**
- Export for a 7-day date range with 300 matching rows produces a CSV file with 301 lines (header + 300 data rows) and correct column order.
- Export where count > 100,000 returns exactly 100,000 data rows and includes header X-Export-Truncated: true.
- CSV includes column offer_rate_coll and full hub_txn_ref (not truncated).
- Memory usage does not grow proportionally with result size (streamed in pages of 1000 — verify no full-list collection in service layer).
- Unauthenticated request returns 401 before any data is written.
**Depends on:** 10.6-T04

### 10.6-T06 — Define TransactionDetailResponse DTO with full locked rate/pool fields  _(40 min)_
**Context:** WBS 10.6. PRD-07 §8.5.2 specifies 15 locked rate/pool fields shown in the detail view: target_payout, payout_usd_cost, collection_usd, collection_margin_usd, payout_margin_usd, send_amount, service_charge, collection_amount, cost_rate_coll, cost_rate_pay, offer_rate_coll, cross_rate, rate_quote_ttl_seconds, rate_quote_valid_until, and is_same_ccy_shortcircuit. For same-currency transactions (is_same_ccy_shortcircuit=true) the USD pool fields (payout_usd_cost, collection_usd, collection_margin_usd, payout_margin_usd, offer_rate_coll, cross_rate) must be null and a flag sameCcyShortCircuit=true set. ADMIN_VIEWER role must not see pool/margin values.
**Steps:** Create TransactionDetailResponse record with: identity section (§8.5.1 fields), ratePool section (§8.5.2 fields), eventTrail list (§8.5.3), prefundingEntry (§8.5.4, nullable), settlementLinkage (§8.5.5, nullable).; For ratePool, use BigDecimal for all monetary/rate fields; include sameCcyShortCircuit boolean.; Create RoleAwareTransactionDetailResponse that zeroes/nulls pool fields if caller role = ADMIN_VIEWER.; Add EventTrailStep record: step (int 1-8), eventType (String), occurredAt (OffsetDateTime), durationMs (Integer nullable), detail (Map<String,Object>), absent (boolean), absentReason (String).; Add PrefundingEntry record: balanceBefore, deductionAmountUsd, balanceAfter (all BigDecimal).
**Deliverable:** TransactionDetailResponse DTO hierarchy with role-aware pool value masking and EventTrailStep record
**Acceptance / logic checks:**
- TransactionDetailResponse for a cross-border txn contains all 15 rate/pool fields as non-null.
- TransactionDetailResponse for a same-ccy txn has sameCcyShortCircuit=true and payout_usd_cost=null, collection_usd=null, collection_margin_usd=null, payout_margin_usd=null.
- RoleAwareTransactionDetailResponse when role=ADMIN_VIEWER sets collection_margin_usd and payout_margin_usd to null.
- EventTrailStep with absent=true has absentReason populated (e.g. 'Step skipped: LOCAL partner has no prefunding deduction').
- Compile-time check: all 15 fields in §8.5.2 exist on the DTO.
**Depends on:** 10.6-T02

### 10.6-T07 — Implement transaction detail service — load txn + event trail + prefunding entry + settlement linkage  _(55 min)_
**Context:** WBS 10.6. The detail service loads a single transaction by hub_txn_ref, joining: partner, qr_scheme, merchant, rate_quote. It also loads the transaction_event rows (step 1-8) ordered by step, the prefunding_ledger_entry linked to this txn (for OVERSEAS), and settlement batch linkage (ZP0011/ZP0061/ZP0065 references stored in a settlement_txn_link table). For LOCAL partners, step 2 (prefunding_deducted) is absent — show as grayed with reason 'LOCAL partner: no prefunding deduction'. For absent steps beyond step 2, show reason 'Not yet reached' or 'Failed before this step'.
**Steps:** Create TransactionDetailService.getDetail(String hubTxnRef, String callerRole).; Load transaction + joins in a single JPQL query by hub_txn_ref; throw NotFoundException if not found.; Load List<TransactionEvent> by txn_id ordered by step asc.; Build full 8-step trail: for each step 1-8, find matching event or mark absent with reason.; For OVERSEAS txn, load prefunding_ledger_entry where txn_id matches and entry_type=DEDUCTION; map to PrefundingEntry.; Load settlement linkage rows; map to SettlementLinkage list.; Apply role masking via RoleAwareTransactionDetailResponse before returning.
**Deliverable:** TransactionDetailService.getDetail() returning fully-populated TransactionDetailResponse
**Acceptance / logic checks:**
- For a LOCAL partner txn, eventTrail[step=2].absent=true and absentReason contains 'LOCAL partner'.
- For an OVERSEAS partner txn, eventTrail[step=2].absent=false and prefundingEntry is non-null with correct deductionAmountUsd.
- For a FAILED txn that stopped at step 4, steps 5-8 have absent=true with reason 'Not yet reached'.
- When hub_txn_ref does not exist, service throws NotFoundException (maps to HTTP 404).
- For a same-ccy txn, ratePool.sameCcyShortCircuit=true and USD pool fields are all null.
**Depends on:** 10.6-T06

### 10.6-T08 — Add GET /admin/v1/transactions/{hubTxnRef} detail endpoint  _(35 min)_
**Context:** WBS 10.6. Expose the detail service as a REST endpoint. Path: GET /admin/v1/transactions/{hubTxnRef}. Roles: OPS_ADMIN, OPS_VIEWER, FINANCE, SUPER_ADMIN, ADMIN_VIEWER all allowed, but ADMIN_VIEWER receives masked pool values (handled in service via RoleAwareTransactionDetailResponse). Return HTTP 404 when not found; 200 with full TransactionDetailResponse otherwise. Log the access in the audit log (actor, timestamp, hubTxnRef accessed) — read-only audit entry type TXN_DETAIL_VIEWED.
**Steps:** Add @GetMapping('/admin/v1/transactions/{hubTxnRef}') to TransactionController.; Extract calling user's role from SecurityContext; pass to transactionDetailService.getDetail(hubTxnRef, role).; Catch NotFoundException and return ResponseEntity with 404 and problem-detail body.; After successful retrieval, call auditService.log(TXN_DETAIL_VIEWED, hubTxnRef, actor).; Add @PreAuthorize for all five roles.; Return 200 with TransactionDetailResponse body.
**Deliverable:** GET /admin/v1/transactions/{hubTxnRef} endpoint with RBAC, audit logging, and 404 handling
**Acceptance / logic checks:**
- GET /admin/v1/transactions/HUB-999-NOTEXIST returns HTTP 404 with body containing field hubTxnRef.
- GET /admin/v1/transactions/HUB-001 with role=ADMIN_VIEWER returns HTTP 200 but collection_margin_usd and payout_margin_usd are null in response.
- GET /admin/v1/transactions/HUB-001 with role=OPS_ADMIN returns all 15 pool fields populated.
- Audit log contains an entry with actor, action=TXN_DETAIL_VIEWED, entityRef=HUB-001 within 1 second of request.
- Unauthenticated request returns 401.
**Depends on:** 10.6-T07

### 10.6-T09 — Implement event trail duration_ms computation and step-ordering invariant  _(35 min)_
**Context:** WBS 10.6. PRD-07 §8.5.3 shows each step has duration_ms = milliseconds since previous step. transaction_event table has columns: step (INT 1-8), occurred_at (TIMESTAMPTZ), duration_ms (INT). The detail view must display computed delta regardless of whether duration_ms was stored. Also, if occurred_at values are stored out-of-order (e.g. clock skew), the service must sort by step, not occurred_at. PRD-07 assumption A-10: all 8 steps are stored on every transaction record from day one; absent steps have occurred_at=NULL.
**Steps:** In TransactionDetailService, after loading events, sort by step ascending.; For each step, compute display duration_ms: if step.duration_ms is non-null use it; else compute as DATEDIFF_MS(current_step.occurred_at, previous_step.occurred_at) where previous has a non-null occurred_at.; Step 1 always shows duration_ms as null (dash).; If occurred_at is null for a step, duration_ms is also shown as null.; Unit test: provide 3 events with clock-skew ordering (step 3 occurred_at < step 2 occurred_at) and assert output is sorted by step, not by time.
**Deliverable:** Duration-computation logic in TransactionDetailService with sort-by-step guarantee
**Acceptance / logic checks:**
- Given step1.occurred_at=T+0, step2.occurred_at=T+200ms, step3.occurred_at=T+150ms (clock skew), output trail is [step1, step2, step3] in step order, not time order.
- step1 display durationMs = null.
- step2 display durationMs = 200.
- step3 display durationMs: if stored duration_ms present use it; else computed as step3.occurred_at - step2.occurred_at = -50 (show as null or 0 to avoid negative display — document behavior in code comment).
- A step with occurred_at=null shows durationMs=null regardless of neighbors.
**Depends on:** 10.6-T07

### 10.6-T10 — Implement pool identity validation check on detail load (diagnostic flag)  _(30 min)_
**Context:** WBS 10.6. PRD-07 §8.5.2 states locked values are authoritative for settlement and BOK reporting. The detail view should surface a diagnostic warning if the committed values violate the pool identity invariant: collection_usd - collection_margin_usd - payout_margin_usd == payout_usd_cost (tolerance 0.01 USD). This is not a blocking error — it flags a data quality issue for Ops investigation. Add a field poolIdentityOk (boolean) and poolIdentityDelta (BigDecimal) to TransactionDetailResponse.ratePool. Same-ccy txns always have poolIdentityOk=true (N/A).
**Steps:** Add fields poolIdentityOk (Boolean) and poolIdentityDelta (BigDecimal) to the ratePool section of TransactionDetailResponse.; In TransactionDetailService, after loading the txn, for non-same-ccy txns compute: delta = collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost using BigDecimal.; Set poolIdentityOk = abs(delta) <= 0.01; set poolIdentityDelta = delta.; For same-ccy txns, set poolIdentityOk = true, poolIdentityDelta = null.; Log a WARN if poolIdentityOk = false, including hubTxnRef and delta value.
**Deliverable:** Pool identity check in TransactionDetailService with poolIdentityOk and poolIdentityDelta fields on response
**Acceptance / logic checks:**
- Given collection_usd=1000.00, collection_margin_usd=10.00, payout_margin_usd=8.00, payout_usd_cost=982.00: delta=0.00, poolIdentityOk=true.
- Given collection_usd=1000.00, collection_margin_usd=10.00, payout_margin_usd=8.00, payout_usd_cost=981.98: delta=0.02 > 0.01, poolIdentityOk=false.
- Given collection_usd=1000.00, collection_margin_usd=10.00, payout_margin_usd=8.00, payout_usd_cost=981.995: abs(delta)=0.005 <= 0.01, poolIdentityOk=true.
- For a same-ccy txn (is_same_ccy_shortcircuit=true), poolIdentityOk=true and poolIdentityDelta=null.
- A WARN log line is emitted when poolIdentityOk=false.
**Depends on:** 10.6-T07

### 10.6-T11 — Add DB index on transaction_event(txn_id, step) for trail lookups  _(20 min)_
**Context:** WBS 10.6. The detail view loads up to 8 transaction_event rows by txn_id ordered by step. The existing schema in DAT-03 does not list a composite index on (txn_id, step). Without it, loading the 8-step trail for every detail page open causes a full scan of a large event table. This ticket adds the covering index.
**Steps:** Create migration V10_6_002__txn_event_trail_index.sql.; Add CREATE UNIQUE INDEX IF NOT EXISTS uix_txn_event_txn_step ON transaction_event(txn_id, step) — unique because each txn has at most one event per step.; Add plain index on transaction_event(txn_id) for queries that load all events for a txn without step filter.; Verify EXPLAIN SELECT * FROM transaction_event WHERE txn_id=? ORDER BY step uses index scan.
**Deliverable:** Migration V10_6_002__txn_event_trail_index.sql with unique composite index on transaction_event(txn_id, step)
**Acceptance / logic checks:**
- Migration applies cleanly on schema with existing transaction_event rows.
- EXPLAIN SELECT * FROM transaction_event WHERE txn_id=42 ORDER BY step shows Index Scan using uix_txn_event_txn_step.
- Attempting to insert a second row for txn_id=42, step=3 raises unique constraint violation.
- Migration is idempotent (CREATE INDEX IF NOT EXISTS).
**Depends on:** 10.6-T01

### 10.6-T12 — Define TransactionStatusEnum and map status values to display labels and terminal/non-terminal flag  _(30 min)_
**Context:** WBS 10.6. PRD-07 §8.4 defines 10 status values: PENDING, COMMITTED, SUBMITTED_TO_SCHEME, SCHEME_APPROVED, APPROVED, UNCERTAIN, FAILED, CANCELLED, REFUNDED, REFUND_PENDING. The UI needs: (a) a display label for each, (b) a terminal flag (FAILED, CANCELLED, REFUNDED are terminal; APPROVED and SCHEME_APPROVED are final-success), (c) a color hint (RED for FAILED, AMBER for UNCERTAIN/REFUND_PENDING, GREEN for APPROVED/REFUNDED, GREY for CANCELLED, BLUE for in-progress). The frontend and backend filter chips both depend on this enum.
**Steps:** Create Java enum TransactionStatus with 10 values.; Add fields: displayLabel (String), terminal (boolean), colorHint (enum: RED/AMBER/GREEN/GREY/BLUE).; Annotate each value per spec: FAILED/CANCELLED terminal=true colorHint=RED/GREY; APPROVED terminal=true colorHint=GREEN; UNCERTAIN terminal=false colorHint=AMBER; PENDING/COMMITTED/SUBMITTED_TO_SCHEME/SCHEME_APPROVED terminal=false colorHint=BLUE; REFUNDED terminal=true colorHint=GREEN; REFUND_PENDING terminal=false colorHint=AMBER.; Expose a GET /admin/v1/transactions/status-values endpoint that returns the enum list as JSON for frontend filter chip population.; Write a small unit test asserting terminal and colorHint for all 10 values.
**Deliverable:** TransactionStatus enum with display metadata and GET /admin/v1/transactions/status-values endpoint
**Acceptance / logic checks:**
- FAILED.terminal=true, FAILED.colorHint=RED.
- APPROVED.terminal=true, APPROVED.colorHint=GREEN.
- UNCERTAIN.terminal=false, UNCERTAIN.colorHint=AMBER.
- GET /admin/v1/transactions/status-values returns a JSON array of 10 objects each containing value, displayLabel, terminal, colorHint.
- Unit test passes for all 10 status values.

### 10.6-T13 — Add failure_origin and failure_code columns to transaction table migration  _(25 min)_
**Context:** WBS 10.6. PRD-07 §8.2 filter includes Failure Origin (REQUEST/SETTLEMENT/PAYOUT/INTERNAL) and Failure Code (free text, e.g. INSUFFICIENT_PREFUNDING, PARTNER_B_QUOTE_DEVIATION). These two columns must exist on the transaction table. DAT-03 may not have explicitly called them out — add them now if missing. Failure Origin is a VARCHAR(20) enum-like column; Failure Code is VARCHAR(100). Both are nullable (null = no failure).
**Steps:** Check existing transaction table DDL for failure_origin and failure_code columns.; If missing, create migration V10_6_003__txn_failure_columns.sql adding: failure_origin VARCHAR(20) NULL CHECK (failure_origin IN ('REQUEST','SETTLEMENT','PAYOUT','INTERNAL')); failure_code VARCHAR(100) NULL.; Add a partial index on (failure_origin, status) WHERE failure_origin IS NOT NULL.; Update TransactionSummary projection and TransactionDetailResponse to include failureOrigin and failureCode fields.
**Deliverable:** Migration V10_6_003__txn_failure_columns.sql and updated projections with failureOrigin/failureCode
**Acceptance / logic checks:**
- INSERT with failure_origin='UNKNOWN' violates check constraint.
- INSERT with failure_origin='REQUEST', failure_code='INSUFFICIENT_PREFUNDING' succeeds.
- INSERT with failure_origin=NULL, failure_code=NULL succeeds (non-failure txn).
- TransactionSummary includes failureCode field — compile check.
- EXPLAIN SELECT on failure_origin='REQUEST' AND status='FAILED' uses the partial index.
**Depends on:** 10.6-T01

### 10.6-T14 — Implement UNCERTAIN status resolution tracking in event trail  _(35 min)_
**Context:** WBS 10.6. PRD-07 §8.4 states UNCERTAIN transactions are resolved to APPROVED or FAILED within 24 hours via ZeroPay batch reconciliation. When resolved, the transaction_event table receives a new event of type TRANSACTION_STATUS_UPDATED (step 6) with detail containing resolvedFrom='UNCERTAIN' and resolvedTo='APPROVED'|'FAILED'. The event trail must show this resolution event with elapsed time since UNCERTAIN was set. The detail view must show a note when the current status was previously UNCERTAIN.
**Steps:** In TransactionDetailService, check if any transaction_event has eventType=TRANSACTION_STATUS_UPDATED and detail.resolvedFrom='UNCERTAIN'.; If found, set a boolean field resolvedFromUncertain=true and resolvedAt=occurred_at on the ratePool/identity section of TransactionDetailResponse.; In the event trail display, step 6 with resolvedFrom='UNCERTAIN' shows a note: 'Resolved from UNCERTAIN via batch reconciliation at {resolvedAt}'.; Add a field resolvedFromUncertain (Boolean) and resolvedAt (OffsetDateTime) to TransactionDetailResponse identity section.; Unit test: build an event trail with step 6 eventType=TRANSACTION_STATUS_UPDATED detail={resolvedFrom:'UNCERTAIN',resolvedTo:'APPROVED'} and assert resolvedFromUncertain=true.
**Deliverable:** UNCERTAIN resolution tracking fields on TransactionDetailResponse and corresponding service logic
**Acceptance / logic checks:**
- A txn with step-6 event detail={resolvedFrom='UNCERTAIN',resolvedTo='APPROVED'} produces resolvedFromUncertain=true and resolvedAt populated in the response.
- A txn with no UNCERTAIN resolution produces resolvedFromUncertain=false (or null) and resolvedAt=null.
- The EventTrailStep for step 6 in this case includes detail map containing resolvedFrom and resolvedTo keys.
- Unit test passes for UNCERTAIN->APPROVED and UNCERTAIN->FAILED resolution cases.
- A currently-UNCERTAIN txn (step 6 absent) has resolvedFromUncertain=false.
**Depends on:** 10.6-T07

### 10.6-T15 — Unit tests — TransactionSearchCriteria validation rules  _(30 min)_
**Context:** WBS 10.6. The TransactionSearchCriteria DTO has validation constraints that must be unit-tested exhaustively: date range max 90 days, dateFrom required when dateTo present (and vice versa), pageSize max 50, amount range from <= to. Use JUnit 5 + Hibernate Validator to test the bean constraints without a Spring context.
**Steps:** Create TransactionSearchCriteriaValidationTest in the test package.; Test: dateFrom=null dateTo=2025-05-01 -> constraint violation on dateFrom.; Test: dateFrom=2025-01-01 dateTo=2025-04-15 (104 days) -> violation on date range.; Test: dateFrom=2025-01-01 dateTo=2025-03-31 (89 days) -> no violation.; Test: pageSize=51 -> violation; pageSize=50 -> no violation.; Test: payoutAmountFrom=500 payoutAmountTo=100 -> violation (from > to).
**Deliverable:** TransactionSearchCriteriaValidationTest with 8+ test cases covering all constraint rules
**Acceptance / logic checks:**
- All 8+ test cases pass.
- Date-range-over-90-days test produces exactly one ConstraintViolation on the dateRangeMax90Days constraint.
- pageSize=51 test produces violation on pageSize field.
- payoutAmountFrom > payoutAmountTo test produces violation identifying the cross-field constraint.
- Zero test cases use Spring context (pure unit test, fast < 3s total).
**Depends on:** 10.6-T02

### 10.6-T16 — Unit tests — TransactionDetailService pool identity check  _(30 min)_
**Context:** WBS 10.6. The pool identity invariant is collection_usd - collection_margin_usd - payout_margin_usd == payout_usd_cost (tolerance 0.01 USD). Unit test TransactionDetailService.computePoolIdentity with multiple numeric vectors including edge cases around the tolerance boundary.
**Steps:** Create TransactionDetailServicePoolIdentityTest using Mockito to stub the repository.; Test vector A: collection_usd=1000.00, collection_margin_usd=10.00, payout_margin_usd=8.00, payout_usd_cost=982.00 -> delta=0.00, ok=true.; Test vector B: collection_usd=1000.00, collection_margin_usd=10.00, payout_margin_usd=8.00, payout_usd_cost=981.98 -> delta=0.02, ok=false.; Test vector C: delta=0.01 exactly -> ok=true (boundary, inclusive).; Test vector D: delta=-0.01 exactly -> ok=true (negative delta within tolerance).; Test vector E: delta=0.011 -> ok=false.; Test same-ccy txn: is_same_ccy_shortcircuit=true -> poolIdentityOk=true, poolIdentityDelta=null.
**Deliverable:** TransactionDetailServicePoolIdentityTest with 7 test vectors covering boundary conditions
**Acceptance / logic checks:**
- All 7 test vectors pass.
- Vector B correctly produces poolIdentityOk=false.
- Vector C and D (boundary +/-0.01) both produce poolIdentityOk=true.
- Vector E (0.011) produces poolIdentityOk=false.
- Same-ccy test produces poolIdentityDelta=null.
**Depends on:** 10.6-T10

### 10.6-T17 — Unit tests — event trail step ordering and duration_ms computation  _(35 min)_
**Context:** WBS 10.6. Test the event trail service logic for step sorting, duration computation, and absent-step handling. Uses Mockito to stub repository; tests run without a database.
**Steps:** Create EventTrailServiceTest.; Test sort-by-step: supply events with out-of-order occurred_at (step3 < step2 time); assert output ordered by step.; Test duration: step1 at T+0ms, step2 at T+200ms -> step2.durationMs=200.; Test absent step: supply only steps 1,3,4,5,6,7,8 (step 2 missing, partner type=LOCAL) -> trail has 8 entries; entry at step 2 has absent=true, absentReason contains 'LOCAL'.; Test absent from failure: supply steps 1-4, txn status=FAILED -> steps 5-8 have absent=true, absentReason contains 'Not yet reached'.; Test null occurred_at step: step4.occurred_at=null -> step4.durationMs=null.
**Deliverable:** EventTrailServiceTest with 5+ test scenarios covering ordering, duration, and absent-step logic
**Acceptance / logic checks:**
- All 5+ tests pass.
- Out-of-order time test: output index 0=step1, index 1=step2, index 2=step3 (by step not time).
- Duration test: step1.durationMs=null, step2.durationMs=200.
- LOCAL partner step-2 absent test: eventTrail.get(1).absent=true.
- Null occurred_at test: eventTrail.get(3).durationMs=null.
**Depends on:** 10.6-T09

### 10.6-T18 — Unit tests — TransactionSearchCriteria default date range and sort mapping  _(50 min)_
**Context:** WBS 10.6. When neither dateFrom nor dateTo is provided, the search must default to the last 24 hours. Also verify that each sortField enum value maps to the correct transaction table column name in the generated query. These behaviors live in TransactionRepositoryImpl and must be tested.
**Steps:** Create TransactionRepositoryImplTest using an in-memory H2 database or Testcontainers PostgreSQL.; Insert 3 transactions: T1 at now()-30h (outside 24h window), T2 at now()-12h, T3 at now()-1h.; Call search with empty TransactionSearchCriteria (no dateFrom, no dateTo) and assert only T2 and T3 are returned.; Test sort: call with sortField=PAYOUT_AMOUNT sortDirection=DESC; assert rows sorted descending by target_payout.; Test sort: call with sortField=PARTNER sortDirection=ASC; assert rows sorted by partner.name ascending.
**Deliverable:** TransactionRepositoryImplTest with default-date-range and sort-mapping integration tests
**Acceptance / logic checks:**
- Default date range test: T1 (now-30h) is excluded; T2 and T3 are returned.
- Result page totalCount=2 for default range test.
- PAYOUT_AMOUNT DESC test: row with highest target_payout is first.
- PARTNER ASC test: rows in alphabetical order by partner name.
- Tests run against real SQL (H2 or Testcontainers) — no mocked query builder.
**Depends on:** 10.6-T03

### 10.6-T19 — Unit tests — RBAC pool value masking in detail response  _(25 min)_
**Context:** WBS 10.6. ADMIN_VIEWER role sees the transaction detail but collection_margin_usd and payout_margin_usd must be null (masked). All other roles receive full values. Test the RoleAwareTransactionDetailResponse logic.
**Steps:** Create RoleAwareResponseTest.; Build a TransactionDetailResponse with collection_margin_usd=10.00 and payout_margin_usd=8.00.; Apply role masking for ADMIN_VIEWER; assert both fields are null in the result.; Apply role masking for OPS_ADMIN; assert both fields retain original values.; Apply role masking for FINANCE; assert both fields retain original values.; Apply role masking for OPS_VIEWER; assert both fields retain original values.
**Deliverable:** RoleAwareResponseTest with 4 test cases covering all roles
**Acceptance / logic checks:**
- ADMIN_VIEWER test: collection_margin_usd=null, payout_margin_usd=null.
- OPS_ADMIN test: collection_margin_usd=10.00, payout_margin_usd=8.00 (unmasked).
- FINANCE test: values unmasked.
- OPS_VIEWER test: values unmasked.
- No other fields are inadvertently nulled by masking logic for any role.
**Depends on:** 10.6-T06

### 10.6-T20 — Integration test — transaction search endpoint with filter combinations  _(55 min)_
**Context:** WBS 10.6. End-to-end Spring MockMvc integration test for GET /admin/v1/transactions. Uses a test database seeded with known rows covering multiple partners, statuses, and date ranges. Verifies HTTP contract including pagination headers and filter correctness.
**Steps:** Create TransactionSearchControllerIT using @SpringBootTest + MockMvc.; Seed: 5 APPROVED rows for partner SendMN, 3 FAILED rows for partner T-Bank, 2 UNCERTAIN rows for SendMN, all within last 7 days; 1 row 10 days ago.; Test 1: filter partner=SendMN status=APPROVED -> expect 5 rows, HTTP 200.; Test 2: filter partner=SendMN status=APPROVED dateFrom=now-7d dateTo=now -> expect 5 rows (old row excluded).; Test 3: filter failureOrigin=REQUEST -> expect only rows with failure_origin=REQUEST.; Test 4: invalid date range (104 days) -> HTTP 400 with error body.; Test 5: no auth header -> HTTP 401.
**Deliverable:** TransactionSearchControllerIT with 5 integration test scenarios
**Acceptance / logic checks:**
- Test 1 returns HTTP 200, data array size=5, totalCount=5.
- Test 2 excludes the 10-day-old row.
- Test 3 returns only rows with failureOrigin=REQUEST.
- Test 4 returns HTTP 400 with field error referencing the date range constraint.
- Test 5 returns HTTP 401.
**Depends on:** 10.6-T04, 10.6-T13

### 10.6-T21 — Integration test — transaction detail endpoint for cross-border and same-currency transactions  _(55 min)_
**Context:** WBS 10.6. End-to-end MockMvc test for GET /admin/v1/transactions/{hubTxnRef} covering: (a) cross-border txn with full 8-step trail, (b) same-ccy txn with pool N/A, (c) OVERSEAS txn with prefunding entry, (d) LOCAL txn with absent step 2, (e) not-found hub ref.
**Steps:** Create TransactionDetailControllerIT with @SpringBootTest + MockMvc.; Seed a cross-border APPROVED txn (SendMN, KRW payout) with all 8 event rows and a prefunding ledger entry.; Seed a same-ccy APPROVED txn (GME Remit, KRW) with is_same_ccy_shortcircuit=true.; Test A: GET cross-border txn -> HTTP 200, eventTrail has 8 entries, step 2 present, payout_usd_cost non-null, poolIdentityOk=true.; Test B: GET same-ccy txn -> HTTP 200, sameCcyShortCircuit=true, collection_usd=null, prefundingEntry=null (LOCAL).; Test C: GET non-existent hub_txn_ref -> HTTP 404.; Test D: GET cross-border txn with role=ADMIN_VIEWER -> HTTP 200 but collection_margin_usd=null.
**Deliverable:** TransactionDetailControllerIT with 4 integration test scenarios
**Acceptance / logic checks:**
- Test A: response body eventTrail[1].absent=false (step 2 present for OVERSEAS); prefundingEntry non-null.
- Test B: response body sameCcyShortCircuit=true, collection_usd field is null or absent.
- Test C: HTTP 404 response.
- Test D: collection_margin_usd=null in response body.
- All 4 tests pass in CI.
**Depends on:** 10.6-T08, 10.6-T14

### 10.6-T22 — Frontend: Transaction search page — filter panel component  _(55 min)_
**Context:** WBS 10.6. Admin System frontend (React/TypeScript). Build the filter panel for the Transaction Monitoring search page. Filters from PRD-07 §8.2: Partner (multi-select), QR Scheme (multi-select), Direction (multi-select: INBOUND/OUTBOUND/DOMESTIC/HUB), Status (multi-select, chips loaded from GET /admin/v1/transactions/status-values), Date Range picker (default last 24h, max 90 days), Transaction ID (text, exact), Partner Transaction Ref (text), Merchant ID (text, partial), Failure Origin (select), Failure Code (text), Payout Amount range (from/to). Include an Apply Filters and Reset button.
**Steps:** Create TxnFilterPanel component with controlled form state (React Hook Form or useState).; Render multi-select dropdowns for Partner, Scheme, Direction, Status; load Status options from /admin/v1/transactions/status-values on mount.; Render DateRangePicker with default=[now-24h, now] and max-span validation of 90 days; show inline error if exceeded.; Render text inputs for Transaction ID, Partner Txn Ref, Merchant ID, Failure Code; single select for Failure Origin.; Render numeric inputs for payout amount from/to; validate from <= to.; On Apply, emit onFilterChange(criteria) callback with the current filter values.; On Reset, restore all fields to defaults.
**Deliverable:** TxnFilterPanel React component with all 10 filter dimensions, validation, and onFilterChange callback
**Acceptance / logic checks:**
- Setting date range > 90 days shows inline error message and disables Apply button.
- Resetting clears all fields and date range returns to default last-24h.
- Status multi-select chips are populated from the /status-values API response on mount.
- Setting payoutAmountFrom > payoutAmountTo shows inline error.
- onFilterChange is called with a correctly-typed criteria object matching the backend DTO shape when Apply is clicked.
**Depends on:** 10.6-T12

### 10.6-T23 — Frontend: Transaction list table component with pagination and sort  _(45 min)_
**Context:** WBS 10.6. Admin System frontend. Build the TxnListTable component that renders the search results from GET /admin/v1/transactions. Columns (PRD-07 §8.3): Hub Txn Ref (clickable link), Timestamp (UTC), Partner, Scheme, Direction, Mode, Payout Amount, Collection Amount, Service Charge, Status (colored chip), Duration. Pagination: 50 rows/page with page controls. Sort: clicking column headers for Timestamp, Payout Amount, Partner, Status toggles ASC/DESC and re-fetches.
**Steps:** Create TxnListTable component accepting props: data (TransactionSummary[]), totalCount, page, pageSize, onPageChange, onSortChange.; Render table with 11 columns; Hub Txn Ref column renders as a link navigating to /admin/transactions/{hubTxnRef}.; Status column renders as a colored chip using colorHint from TransactionStatus enum (green/amber/red/grey/blue).; Clicking sortable column header calls onSortChange(field, direction).; Render pagination controls showing page X of Y; Prev/Next buttons.; Show empty state message when data is empty.
**Deliverable:** TxnListTable React component with 11 columns, sort headers, pagination controls, and status chips
**Acceptance / logic checks:**
- Hub Txn Ref column renders as an anchor tag with href=/admin/transactions/{hubTxnRef}.
- Clicking Timestamp column header first time emits onSortChange('TIMESTAMP','ASC'); second click emits onSortChange('TIMESTAMP','DESC').
- Status chip for APPROVED renders with green background; UNCERTAIN with amber; FAILED with red.
- When data=[] renders empty state message (not an empty table with no rows).
- Pagination shows correct page label and Prev disabled on page 0.
**Depends on:** 10.6-T22

### 10.6-T24 — Frontend: Transaction search page — wire filter panel + list table + export button  _(55 min)_
**Context:** WBS 10.6. Admin System frontend. Compose TxnFilterPanel and TxnListTable on the /admin/transactions route. On Apply Filters, call GET /admin/v1/transactions with the filter criteria + page=0 + default sort. On page change or sort change, re-fetch with updated params. Export button calls GET /admin/v1/transactions/export with same filters and triggers file download. Show loading spinner during fetch; show error banner on API error.
**Steps:** Create TransactionSearchPage component at route /admin/transactions.; Hold state: criteria, page, sortField, sortDirection; initialize page=0, sort=TIMESTAMP DESC, criteria=defaults.; On TxnFilterPanel.onFilterChange: reset page to 0 and update criteria state.; useEffect triggers GET /admin/v1/transactions whenever criteria, page, or sort changes; set loading=true before fetch.; Pass result data to TxnListTable; pass totalCount for pagination.; Export button: GET /admin/v1/transactions/export with current criteria; set response as blob and trigger download with filename from Content-Disposition header.; Show error banner when API returns 4xx/5xx.
**Deliverable:** TransactionSearchPage React page component wiring filter, table, pagination, sort, and CSV export
**Acceptance / logic checks:**
- Applying a filter resets page to 0 and triggers a new fetch with the filter params.
- Changing page from 0 to 1 triggers fetch with page=1 using the same filter criteria.
- Clicking Export triggers a file download with filename matching gmepay_txns_{from}_{to}.csv pattern.
- A 400 response from the API displays a visible error banner (not a blank page or unhandled exception).
- Loading spinner is visible between filter apply and data render.
**Depends on:** 10.6-T23

### 10.6-T25 — Frontend: Transaction detail page — identity/metadata and locked rate panel  _(55 min)_
**Context:** WBS 10.6. Admin System frontend. Build the TransactionDetailPage at route /admin/transactions/:hubTxnRef. Section 1: Identity and Metadata (PRD-07 §8.5.1): Hub Txn Ref, Partner Txn Ref, Partner name, Scheme name, Direction, Mode, Merchant ID/Name, Timestamp UTC+KST, Current Status chip. Section 2: Locked Rate and Pool Values (PRD-07 §8.5.2): display all 15 fields as a read-only table; for same-currency txns show N/A with note 'Same-currency short-circuit applied'; show poolIdentityOk warning banner if poolIdentityOk=false.
**Steps:** Create TransactionDetailPage fetching GET /admin/v1/transactions/:hubTxnRef on mount; handle 404 (show not-found page) and loading.; Render IdentitySection with all §8.5.1 fields; format timestamps as 'YYYY-MM-DD HH:mm:ss UTC / KST' side by side.; Render RatePoolPanel as a two-column table: field label | value; for same-ccy rows show 'N/A' with tooltip.; If poolIdentityOk=false render a yellow warning banner: 'Pool identity check failed: delta = {poolIdentityDelta} USD. Ops investigation required.'; Mask collection_margin_usd and payout_margin_usd rows when response values are null (ADMIN_VIEWER) by showing '—' instead.
**Deliverable:** TransactionDetailPage React page with identity section and rate/pool panel including pool-identity warning
**Acceptance / logic checks:**
- For a cross-border txn all 15 rate/pool rows are populated with numeric values.
- For a same-ccy txn, payout_usd_cost row shows 'N/A' and a note 'Same-currency short-circuit applied' is visible.
- When poolIdentityOk=false a yellow warning banner is visible with the delta value.
- When poolIdentityOk=true no warning banner is shown.
- When collection_margin_usd is null in the response (ADMIN_VIEWER), that row shows a dash rather than null/undefined.
**Depends on:** 10.6-T24

### 10.6-T26 — Frontend: Transaction detail page — 8-step event trail timeline  _(50 min)_
**Context:** WBS 10.6. Admin System frontend. Render the event trail section of TransactionDetailPage as a vertical numbered timeline (PRD-07 §8.5.3). Each step shows: step number, event type label, occurred_at (UTC), duration since previous step (ms), and detail payload (expandable JSON). Absent steps are grayed out with the absentReason text. Steps with failures (eventType contains FAILED or detail.failureCode present) are highlighted in red.
**Steps:** Create EventTrailTimeline component accepting eventTrail: EventTrailStep[].; Render 8 step slots in order 1-8; for present steps render the full row; for absent steps render grayed row with step number, name, and absentReason.; For step 1, show durationMs as '—'; for others show '{durationMs} ms' or '—' if null.; If step has detail payload, render an expandable Disclosure/Accordion with JSON viewer.; Highlight step row red if detail.failureCode is present or eventType ends in _FAILED or _DECLINED.; If resolvedFromUncertain=true in the page-level response, show a banner above the trail: 'This transaction was previously UNCERTAIN and was resolved via batch reconciliation.'
**Deliverable:** EventTrailTimeline React component with 8-step vertical timeline, absent-step graying, failure highlighting, and UNCERTAIN-resolution banner
**Acceptance / logic checks:**
- Step 2 with absent=true renders grayed with absentReason text visible.
- Step 1 shows duration as dash.
- Step 3 with durationMs=150 shows '150 ms'.
- A step with detail.failureCode='INSUFFICIENT_PREFUNDING' renders with red highlight.
- resolvedFromUncertain=true displays the UNCERTAIN resolution banner above the timeline.
**Depends on:** 10.6-T25

### 10.6-T27 — Frontend: Transaction detail page — prefunding entry and settlement linkage sections  _(40 min)_
**Context:** WBS 10.6. Admin System frontend. PRD-07 §8.5.4: For OVERSEAS partners, show prefunding deduction entry: balance before, deduction amount (USD), balance after. PRD-07 §8.5.5: Show settlement linkage — which ZeroPay batch files (ZP0011/ZP0061/ZP0065) included this transaction, with batch date and registration result. If prefundingEntry is null (LOCAL partner) show the section header with a note 'Not applicable: LOCAL partner has no prefunding account'. If settlementLinkage is empty show 'Not yet included in a settlement batch'.
**Steps:** Create PrefundingEntryPanel component: if prefundingEntry non-null render 3-row table (balanceBefore, deductionAmountUsd, balanceAfter in USD); else render not-applicable note.; Create SettlementLinkagePanel component: if settlementLinkage non-empty render table with columns: File Type, Batch Date, File Ref, Registration Result; else render pending note.; Format all USD values with 2 decimal places and 'USD' prefix.; Integrate both panels into TransactionDetailPage below the event trail section.
**Deliverable:** PrefundingEntryPanel and SettlementLinkagePanel React components integrated into TransactionDetailPage
**Acceptance / logic checks:**
- For OVERSEAS txn with prefundingEntry: panel shows balanceBefore=10000.00 USD, deductionAmountUsd=150.00 USD, balanceAfter=9850.00 USD.
- For LOCAL txn (prefundingEntry=null): panel shows 'Not applicable: LOCAL partner has no prefunding account'.
- For txn with settlementLinkage=[{fileType:'ZP0011', batchDate:'2025-05-01', fileRef:'ZP0011_20250501', result:'REGISTERED'}]: panel shows one row with those values.
- For txn with empty settlementLinkage: panel shows 'Not yet included in a settlement batch'.
- USD amounts formatted to 2 decimal places with USD prefix.
**Depends on:** 10.6-T26

### 10.6-T28 — Backend: Add settlement_txn_link table migration for settlement linkage in detail view  _(35 min)_
**Context:** WBS 10.6. PRD-07 §8.5.5 requires showing which ZeroPay settlement batch files included a given transaction. This requires a junction table settlement_txn_link: id (BIGINT PK), txn_id (BIGINT FK transaction), file_type (VARCHAR(10), e.g. ZP0011/ZP0061/ZP0065), batch_date (DATE), file_ref (VARCHAR(100)), registration_result (VARCHAR(20): REGISTERED/REJECTED/PENDING), created_at (TIMESTAMPTZ). Add the migration and corresponding JPA entity.
**Steps:** Create migration V10_6_004__settlement_txn_link.sql with the table DDL and FK constraint to transaction.; Add index on (txn_id) for fast detail-page lookup.; Create SettlementTxnLink JPA entity in com.gmepayplus.settlement.domain.; Add OneToMany from Transaction to SettlementTxnLink (lazy fetch).; Expose settlementLinkage list in TransactionDetailService by fetching SettlementTxnLink rows where txn_id matches.
**Deliverable:** Migration V10_6_004__settlement_txn_link.sql and SettlementTxnLink JPA entity with index on txn_id
**Acceptance / logic checks:**
- Migration applies cleanly; table exists with correct columns and FK constraint.
- Inserting a row with txn_id pointing to a non-existent transaction raises FK violation.
- EXPLAIN SELECT * FROM settlement_txn_link WHERE txn_id=42 uses index scan.
- TransactionDetailService.getDetail() populates settlementLinkage list from the new table.
- TransactionDetailResponse.settlementLinkage is a non-null list (empty list if no rows).
**Depends on:** 10.6-T07

### 10.6-T29 — Backend: Audit log entry for transaction detail view access  _(40 min)_
**Context:** WBS 10.6. Every time an Ops/Finance/Admin user opens a transaction detail page, the action must be audit-logged (PRD-07 §12 and SEC-09 non-repudiation requirement). Audit entry fields: actor (hub_user email), action=TXN_DETAIL_VIEWED, entity_type=TRANSACTION, entity_ref=hub_txn_ref, occurred_at, ip_address (from request). Write to the existing audit_log table (schema from DAT-03/SEC-09: id, actor, action, entity_type, entity_ref, occurred_at, ip_address, detail JSONB).
**Steps:** Verify audit_log table has columns: id, actor, action, entity_type, entity_ref, occurred_at, ip_address, detail.; In TransactionDetailService or as a Spring AOP @AfterReturning aspect on the controller method, create and persist an AuditLogEntry with action=TXN_DETAIL_VIEWED after successful retrieval.; Extract IP address from HttpServletRequest (use X-Forwarded-For header first, fallback to remoteAddr).; Ensure audit write is in a separate transaction (REQUIRES_NEW) so that a detail-load failure does not roll back the audit entry.; Unit test: mock auditLogRepository; call getDetail; verify auditLogRepository.save called once with correct action and entityRef.
**Deliverable:** Audit logging for TXN_DETAIL_VIEWED events in a separate transaction with IP capture
**Acceptance / logic checks:**
- Calling getDetail('HUB-001', role) inserts one audit_log row with action=TXN_DETAIL_VIEWED and entity_ref=HUB-001.
- Audit entry is committed even if the detail response serialization throws an exception (separate transaction).
- ip_address field is populated from X-Forwarded-For header when present.
- ip_address falls back to remoteAddr when X-Forwarded-For is absent.
- audit_log rows cannot be deleted via any service method (no delete method exposed on AuditLogRepository).
**Depends on:** 10.6-T08

### 10.6-T30 — Backend: Endpoint and query performance test — search returns <= 2s for typical filter  _(55 min)_
**Context:** WBS 10.6. PRD-07 §15.5 acceptance criterion: transaction search by Partner=SendMN, Status=APPROVED, last 7 days returns correct result set within 2 seconds. Write a JMH micro-benchmark or a Testcontainers-based timing test against a dataset of 100,000 transaction rows with representative distribution.
**Steps:** Set up a Testcontainers PostgreSQL instance with a Flyway migration + seed script generating 100,000 transaction rows (Python or SQL loop): mix of partners, statuses, dates.; Run TransactionRepository.search with criteria: partnerId=<SendMN id>, status=[APPROVED], dateFrom=now-7d, dateTo=now.; Assert p50 query time <= 500ms and p99 <= 2000ms (measure 10 runs).; Run with EXPLAIN ANALYZE and assert index scan (not seq scan) is used on the (status, committed_at) index.; Document results in a test comment.
**Deliverable:** Performance integration test asserting p99 <= 2000ms for the standard search scenario against 100k rows
**Acceptance / logic checks:**
- Test seed inserts 100,000 rows without error.
- p50 query time <= 500ms across 10 runs.
- p99 query time <= 2000ms across 10 runs.
- EXPLAIN ANALYZE output shows Index Scan on the (status, committed_at) composite index.
- Test is tagged @Tag('performance') and excluded from default CI (runs on demand / nightly).
**Depends on:** 10.6-T03, 10.6-T11

### 10.6-T31 — Frontend: Transaction detail page — Cancel and Refund action buttons (conditional display)  _(35 min)_
**Context:** WBS 10.6. The transaction detail page must show action buttons based on transaction state and user role, per PRD-07 §10. Cancel button: visible when status is COMMITTED or SUBMITTED_TO_SCHEME and the role is OPS_ADMIN or SUPER_ADMIN. Refund button: visible when status is APPROVED or SCHEME_APPROVED and role is OPS_ADMIN or SUPER_ADMIN. Clicking either opens a confirmation dialog (implementation of the dialog is a separate ticket for WBS 10.7/10.8 — this ticket only adds the buttons with correct visibility logic). Buttons are absent (not disabled) when conditions are not met.
**Steps:** In TransactionDetailPage, receive callerRole from auth context.; Compute canCancel: status in ['COMMITTED','SUBMITTED_TO_SCHEME'] AND role in ['OPS_ADMIN','SUPER_ADMIN'].; Compute canRefund: status in ['APPROVED','SCHEME_APPROVED'] AND role in ['OPS_ADMIN','SUPER_ADMIN'].; Render Cancel button only when canCancel=true; Refund button only when canRefund=true.; Clicking Cancel or Refund calls a passed onCancelClick or onRefundClick callback (no dialog implemented here).; Unit test: assert button visibility for 5 combinations of status and role.
**Deliverable:** Conditional Cancel/Refund button logic in TransactionDetailPage with unit tests for 5 role-status combinations
**Acceptance / logic checks:**
- status=COMMITTED, role=OPS_ADMIN: Cancel visible, Refund not rendered.
- status=APPROVED, role=OPS_ADMIN: Refund visible, Cancel not rendered.
- status=FAILED, role=OPS_ADMIN: neither button rendered.
- status=COMMITTED, role=FINANCE: neither button rendered (Finance cannot cancel).
- status=APPROVED, role=SUPER_ADMIN: Refund visible.
**Depends on:** 10.6-T25

### 10.6-T32 — Docs: OpenAPI spec for /admin/v1/transactions search and detail endpoints  _(40 min)_
**Context:** WBS 10.6. Add OpenAPI 3.0 annotations (or YAML) for the two transaction monitoring endpoints: GET /admin/v1/transactions (search) and GET /admin/v1/transactions/{hubTxnRef} (detail). Document all query parameters with types and constraints, all response schemas referencing TransactionListResponse and TransactionDetailResponse, error responses (400, 401, 403, 404), and the RBAC role requirement in the security section.
**Steps:** Add @Operation, @Parameter, and @ApiResponse annotations to TransactionController search and detail methods (if using springdoc-openapi).; Document each query parameter with description, required flag, example value, and constraint (e.g. dateFrom: date-time, example: 2025-05-01T00:00:00Z).; Document TransactionListResponse and TransactionDetailResponse schemas inline or via $ref.; Document 400 error response with ConstraintViolationProblem schema; 404 with ProblemDetail schema.; Verify generated Swagger UI shows correct schema at /v3/api-docs.
**Deliverable:** OpenAPI annotations/YAML for /admin/v1/transactions and /admin/v1/transactions/{hubTxnRef} visible in Swagger UI
**Acceptance / logic checks:**
- GET /admin/v1/transactions in Swagger UI shows all 13 query parameters with descriptions.
- TransactionDetailResponse schema in Swagger UI includes eventTrail array with EventTrailStep fields.
- 400 response documented with ConstraintViolationProblem example.
- /v3/api-docs endpoint returns valid JSON with no schema validation errors (run openapi-validator).
- Security requirement 'bearerAuth' is listed for both endpoints.
**Depends on:** 10.6-T08


## WBS 10.7 — Prefunding management UI
### 10.7-T01 — Create DB migration: prefunding_account table  _(25 min)_
**Context:** GMEPay+ prefunds OVERSEAS partners only. Table prefunding_account stores one row per OVERSEAS partner: id BIGINT PK, partner_id BIGINT FK->partner UNIQUE, currency CHAR(3) always USD, balance DECIMAL(20,4) current balance updated atomically, low_balance_threshold DECIMAL(20,4) default 10000.00, created_at/updated_at/created_by/updated_by. Balance updates MUST use SELECT FOR UPDATE - no app-level locking. LOCAL partners (e.g. GME Remit) never have a row here.
**Steps:** Write Flyway/Liquibase migration file V10_7_001__create_prefunding_account.sql; Add columns as specified; set NOT NULL constraints on all required fields; Add UNIQUE constraint on partner_id; Add DEFAULT 10000.00 on low_balance_threshold; Add CHECK constraint: balance >= 0 and low_balance_threshold > 0; Run migration on dev DB; confirm schema with \d prefunding_account
**Deliverable:** Migration file V10_7_001__create_prefunding_account.sql applied successfully to dev DB
**Acceptance / logic checks:**
- Table exists with all 9 columns at correct types after migration runs
- UNIQUE constraint on partner_id rejects a second insert for the same partner
- Inserting balance = -1.00 is rejected by CHECK constraint
- Inserting low_balance_threshold = 0 is rejected by CHECK constraint
- Default value of low_balance_threshold is 10000.0000 when not supplied

### 10.7-T02 — Create DB migration: prefunding_ledger_entry table  _(25 min)_
**Context:** prefunding_ledger_entry is append-only; every debit or credit creates one immutable row. Columns: id BIGINT PK, account_id BIGINT FK->prefunding_account, txn_ref VARCHAR(64) nullable (transaction.txn_ref for debits; internal ref for top-ups), entry_type VARCHAR(20) values DEBIT_PAYMENT/DEBIT_REVERSAL/CREDIT_TOPUP/CREDIT_REVERSAL/CREDIT_ADJUSTMENT, amount DECIMAL(20,4) always positive (direction conveyed by entry_type), balance_after DECIMAL(20,4) running balance snapshot, note VARCHAR(255), created_at TIMESTAMPTZ immutable (no updated_at), created_by VARCHAR(120). Index on (account_id, created_at) for history queries. No UPDATE or DELETE permitted via app service account.
**Steps:** Write migration V10_7_002__create_prefunding_ledger_entry.sql; Define all columns with correct nullability; entry_type as VARCHAR(20) with CHECK IN list; Add index on (account_id, created_at); Add CHECK constraint: amount > 0; Confirm no updated_at column exists; Grant INSERT-only privilege to app service account; revoke UPDATE/DELETE
**Deliverable:** Migration file V10_7_002__create_prefunding_ledger_entry.sql applied; app account has INSERT-only on this table
**Acceptance / logic checks:**
- All 10 columns present at correct types after migration
- INSERT with amount = 0 is rejected by CHECK constraint
- INSERT with entry_type = INVALID_TYPE is rejected by CHECK constraint
- UPDATE on any row returns permission denied for app service account
- Index idx_prefunding_ledger_entry_account_date exists on (account_id, created_at)
**Depends on:** 10.7-T01

### 10.7-T03 — Create DB migration: low_balance_alert_config table  _(20 min)_
**Context:** low_balance_alert_config stores per-partner alert settings for OVERSEAS partners. Columns: id BIGINT PK, partner_id BIGINT FK->partner UNIQUE, threshold_usd DECIMAL(20,4) default 10000.00, alert_email VARCHAR(255) partner contact email (comma-separated for multiple recipients per PRD-07 §5.3.3), is_active BOOLEAN default true, created_at/updated_at/created_by/updated_by. Separate from prefunding_account.low_balance_threshold to allow independent alert config management. A partner may have only one alert config row (UNIQUE on partner_id).
**Steps:** Write migration V10_7_003__create_low_balance_alert_config.sql; Add all columns with NOT NULL on required fields; Add UNIQUE on partner_id; Add CHECK: threshold_usd > 0; Insert default row automatically via trigger or handle in service layer (document choice)
**Deliverable:** Migration file V10_7_003__create_low_balance_alert_config.sql applied to dev DB
**Acceptance / logic checks:**
- Table exists with all 8 columns after migration
- UNIQUE constraint on partner_id prevents duplicate rows for same partner
- Inserting threshold_usd = 0 is rejected
- alert_email column accepts multiple addresses separated by commas (at least 255 chars wide)
- is_active defaults to TRUE when not supplied
**Depends on:** 10.7-T01

### 10.7-T04 — Implement PrefundingAccountRepository with atomic balance update  _(45 min)_
**Context:** OVERSEAS partner prefunding balance must be updated atomically using SELECT FOR UPDATE. Repository targets prefunding_account table. Key operations: findByPartnerId(partnerId) returning Optional<PrefundingAccount>; deductBalance(accountId, amount) using SELECT FOR UPDATE inside a transaction - must fail with InsufficientBalanceException if balance - amount < 0; creditBalance(accountId, amount) for top-ups. Use DECIMAL types throughout - never double/float. All methods annotated @Transactional where needed.
**Steps:** Create entity class PrefundingAccount mapping prefunding_account table; Create PrefundingAccountRepository (JPA or JDBC; your choice - document); Implement deductBalance using native query SELECT ... FOR UPDATE then UPDATE; throw InsufficientBalanceException if resulting balance < 0; Implement creditBalance for CREDIT_TOPUP and CREDIT_REVERSAL ops; Write integration test against real Postgres (testcontainers) confirming atomic deduction under 2 concurrent threads - only one should succeed when balance = exactly 1 deduction amount
**Deliverable:** PrefundingAccountRepository class with deductBalance, creditBalance, findByPartnerId methods; integration test passing
**Acceptance / logic checks:**
- deductBalance(accountId=1, amount=100.00) on account with balance=100.00 sets balance to 0.0000 and returns updated entity
- deductBalance(accountId=1, amount=100.01) on account with balance=100.00 throws InsufficientBalanceException without mutating the row
- Two concurrent threads both calling deductBalance for 100.00 on a 100.00-balance account: only one succeeds, one throws InsufficientBalanceException; final balance = 0
- creditBalance(accountId=1, amount=50000.00) increases balance by 50000.00 and returns new total
- All money fields use DECIMAL(20,4) / BigDecimal; no double/float anywhere in the call chain
**Depends on:** 10.7-T01

### 10.7-T05 — Implement PrefundingLedgerEntryRepository and ledger append service  _(40 min)_
**Context:** prefunding_ledger_entry is append-only. Every balance change must create a corresponding ledger entry atomically in the same transaction as the balance update. Entry types: DEBIT_PAYMENT (payment deduction), DEBIT_REVERSAL (cancellation reversal), CREDIT_TOPUP (manual top-up), CREDIT_REVERSAL (refund confirmed), CREDIT_ADJUSTMENT (manual correction). Each entry records amount (always positive), balance_after (running balance snapshot), txn_ref (nullable), note, created_by.
**Steps:** Create PrefundingLedgerEntry entity and repository with insertEntry method; Create LedgerAppendService with methods: appendDebit(accountId, amount, balanceAfter, txnRef, createdBy), appendCredit(accountId, entryType, amount, balanceAfter, ref, note, createdBy); Ensure both deductBalance in T04 and creditBalance call appendEntry in the same @Transactional scope; Create findByAccountIdOrderByCreatedAtDesc(accountId, Pageable) for the ledger view
**Deliverable:** PrefundingLedgerEntryRepository and LedgerAppendService; all balance changes produce a matching ledger row
**Acceptance / logic checks:**
- After a deductBalance call for USD 120.50, exactly one DEBIT_PAYMENT ledger entry exists with amount=120.5000 and balance_after equal to the new balance
- After a creditBalance (CREDIT_TOPUP) call for USD 50000.00, ledger row has entry_type=CREDIT_TOPUP, amount=50000.0000
- If the DB transaction rolls back, no ledger entry is persisted (atomicity check)
- findByAccountIdOrderByCreatedAtDesc returns entries newest-first with correct pagination
- balance_after in each ledger entry matches the sequence of running balance: e.g. start 200.00, deduct 50.00 -> entry balance_after=150.0000, deduct 30.00 -> entry balance_after=120.0000
**Depends on:** 10.7-T04

### 10.7-T06 — Implement TopUpRecordingService - record manual top-up with audit log  _(40 min)_
**Context:** Ops records a top-up when a partner wire arrives. Fields from PRD-07 §9.3: partner_id, top_up_amount_usd (Decimal USD > 0), bank_ref (required text), value_date (date funds received), notes (optional). The service must: (1) call creditBalance atomically, (2) append a CREDIT_TOPUP ledger entry, (3) write an audit log entry with actor, timestamp, entity_type=prefunding_account, event_type=prefunding.topup_recorded, previous_balance, new_balance. RBAC: only OPS_OPERATOR and SUPER_ADMIN may call this endpoint. LOCAL partners must be rejected.
**Steps:** Create TopUpRequest DTO with fields: partnerId, amountUsd BigDecimal, bankRef String, valueDate LocalDate, notes String; Create TopUpRecordingService.recordTopUp(TopUpRequest, actorUserId) with @Transactional; Validate: partner exists, partner type = OVERSEAS, amountUsd > 0, bankRef not blank; Call PrefundingAccountRepository.creditBalance then LedgerAppendService.appendCredit in same transaction; Write audit log entry via AuditLogService; Return TopUpResult with ledger entry id, new balance, timestamp
**Deliverable:** TopUpRecordingService with recordTopUp method; audit entry written on each call
**Acceptance / logic checks:**
- recordTopUp for SendMN (OVERSEAS) with amountUsd=50000.00 increases displayed balance by 50000.00 immediately
- Calling recordTopUp on GME Remit (LOCAL partner) throws PartnerTypeException with message indicating prefunding not applicable
- recordTopUp with amountUsd=0 throws ValidationException
- Audit log entry exists with event_type=prefunding.topup_recorded, previous_balance and new_balance populated correctly
- If creditBalance fails, no audit entry and no ledger entry are persisted (rollback)
**Depends on:** 10.7-T05

### 10.7-T07 — Implement ManualAdjustmentService - Finance-initiated balance corrections  _(35 min)_
**Context:** Finance Analyst (FINANCE_ANALYST role) or SUPER_ADMIN can create manual ADJUSTMENT entries to correct recording errors (PRD-07 §9.4). Fields: partner_id, adjustment_amount_usd (positive or negative decimal), reason (mandatory text >= 10 chars), entry_type always CREDIT_ADJUSTMENT for positive or a separate DEBIT_ADJUSTMENT type for negative. The resulting balance must never go below 0 - negative adjustments that would cause negative balance must be rejected. All adjustments are audit-logged. OPS_OPERATOR cannot create adjustments per permission matrix (§12.3).
**Steps:** Create AdjustmentRequest DTO: partnerId, adjustmentAmountUsd BigDecimal, reason String; Create ManualAdjustmentService.applyAdjustment(AdjustmentRequest, actorUserId); Enforce RBAC: reject if caller role is OPS_OPERATOR; Validate: reason length >= 10 chars, adjustmentAmountUsd != 0; Load account with SELECT FOR UPDATE; check resulting balance >= 0 before applying; Append ledger entry CREDIT_ADJUSTMENT (positive) or DEBIT_ADJUSTMENT (negative); write audit log
**Deliverable:** ManualAdjustmentService.applyAdjustment with RBAC, validation, atomic balance update, and audit entry
**Acceptance / logic checks:**
- FINANCE_ANALYST applies adjustment of +1000.00 on account with balance 5000.00; new balance is 6000.0000 with CREDIT_ADJUSTMENT ledger entry
- OPS_OPERATOR calling applyAdjustment receives 403 / AccessDeniedException
- Negative adjustment of -6000.00 on account with balance 5000.00 is rejected with error indicating insufficient balance
- reason of 5 chars is rejected with ValidationException
- Audit log entry includes previous_balance, new_balance, reason text, and actor ID
**Depends on:** 10.7-T05

### 10.7-T08 — Implement LowBalanceAlertService - post-deduction alert logic  _(40 min)_
**Context:** After each successful deduction, the system checks if the new balance < low_balance_threshold (from prefunding_account). If so, it sends an email alert to all addresses in low_balance_alert_config.alert_email for that partner. Alert content per PRD-07 §9.5: partner name, current balance, threshold, suggested top-up amount (= threshold * 2 - current_balance). A second URGENT alert fires when balance reaches USD 0.00 or below (which should only occur via adjustment, not payment since deductBalance prevents negative balances). Alerts do NOT block transactions.
**Steps:** Create LowBalanceAlertService.checkAndAlert(partnerId, newBalanceUsd) - called after every deductBalance; Load low_balance_alert_config for partner; if is_active = false, skip; If newBalanceUsd < threshold_usd and previous balance was >= threshold_usd, send LOW_BALANCE email to all recipients in alert_email (split on comma); If newBalanceUsd <= 0, send URGENT_ZERO_BALANCE email and post in-portal dashboard alert via DashboardAlertService; Email subject and body must include: partner name, current balance USD, threshold USD, suggested top-up = max(threshold*2 - newBalance, threshold); Write audit log entry with event_type=prefunding.low_balance_alert_triggered
**Deliverable:** LowBalanceAlertService.checkAndAlert integrated into deduction flow; email sent when crossing threshold
**Acceptance / logic checks:**
- Deduction taking SendMN balance from 12000.00 to 8500.00 (threshold=10000.00) sends exactly one LOW_BALANCE email to all configured recipients
- Deduction taking balance from 8000.00 to 7000.00 (already below threshold) does NOT send a duplicate alert (only fires on crossing, not every deduction while below)
- Deduction to exactly 0.00 triggers URGENT_ZERO_BALANCE email in addition to or instead of normal LOW_BALANCE email
- Alert email body contains: partner name, current balance 8500.00 USD, threshold 10000.00 USD, suggested top-up amount
- If alert_config.is_active = false, no email is sent after deduction below threshold
**Depends on:** 10.7-T05, 10.7-T03

### 10.7-T09 — Implement PrefundingDashboardService - real-time balance summary per partner  _(35 min)_
**Context:** The Prefunding Dashboard (PRD-07 §9.2) shows a summary card per OVERSEAS partner: partner name, current balance USD, low_balance_threshold, balance as % of threshold, traffic-light status (GREEN >= 150%, AMBER 100-150%, RED < 100%), last deduction timestamp+amount, last top-up timestamp+amount. Balances must be real-time (no caching - query live DB). Sourced from prefunding_account and last 2 ledger entries by type.
**Steps:** Create PrefundingDashboardEntry DTO with fields: partnerId, partnerName, balanceUsd, thresholdUsd, balancePct (2dp), trafficLight enum GREEN/AMBER/RED, lastDeductionAt, lastDeductionAmountUsd, lastTopUpAt, lastTopUpAmountUsd; Create PrefundingDashboardService.getDashboardEntries() querying all OVERSEAS partners with their prefunding_account; Compute balancePct = (balance / threshold) * 100; assign trafficLight: >= 150 = GREEN, 100 <= x < 150 = AMBER, < 100 = RED; Fetch last DEBIT_PAYMENT entry and last CREDIT_TOPUP entry per account for last-deduction/top-up fields; Return list sorted by trafficLight severity (RED first, then AMBER, then GREEN)
**Deliverable:** PrefundingDashboardService.getDashboardEntries() returning real-time DTO list sorted by severity
**Acceptance / logic checks:**
- Partner with balance=8500.00, threshold=10000.00 returns balancePct=85.00 and trafficLight=RED
- Partner with balance=14000.00, threshold=10000.00 returns balancePct=140.00 and trafficLight=AMBER
- Partner with balance=18000.00, threshold=10000.00 returns balancePct=180.00 and trafficLight=GREEN
- RED entries appear before AMBER before GREEN in returned list
- lastDeductionAt and lastDeductionAmountUsd match the most recent DEBIT_PAYMENT entry for that account
**Depends on:** 10.7-T05

### 10.7-T10 — Implement PrefundingLedgerQueryService - paginated ledger view with filtering  _(35 min)_
**Context:** PRD-07 §9.4 requires a full transaction-level ledger per partner with columns: ledger entry id, timestamp UTC, entry_type (TOPUP/DEDUCTION/ADJUSTMENT/REFUND_REVERSAL), amount, balance_after, hub_txn_ref (for DEBIT_PAYMENT entries), reference (bank ref for TOPUP), recorded_by. Supports pagination (default 50 per page) and filter by entry_type and date range. Note: entry_type display labels differ from DB enum: DEBIT_PAYMENT shows as DEDUCTION, CREDIT_TOPUP shows as TOPUP, CREDIT_ADJUSTMENT/DEBIT_ADJUSTMENT shows as ADJUSTMENT, DEBIT_REVERSAL/CREDIT_REVERSAL shows as REFUND_REVERSAL.
**Steps:** Create LedgerEntryDTO with all display columns including mapped display_type; Create PrefundingLedgerQueryService.getLedger(partnerId, entryTypeFilter, fromDate, toDate, Pageable); Map DB entry_type enum to display labels as specified; Join with transaction table on txn_ref to get hub_txn_ref for DEBIT_PAYMENT entries; Return Page<LedgerEntryDTO> ordered by created_at DESC; Validate partnerId is OVERSEAS partner; throw PartnerNotFoundException otherwise
**Deliverable:** PrefundingLedgerQueryService.getLedger returning paginated, filtered, display-mapped ledger entries
**Acceptance / logic checks:**
- Query for partnerId=SendMN with no filters returns all entries newest-first, max 50 per page
- DEBIT_PAYMENT entry in DB appears in result with display_type=DEDUCTION and hub_txn_ref populated from linked transaction
- CREDIT_TOPUP entry appears with display_type=TOPUP and bank reference in reference column
- Filter by entry_type=TOPUP returns only CREDIT_TOPUP rows
- Date range filter from 2026-06-01 to 2026-06-05 excludes entries outside that window
**Depends on:** 10.7-T05

### 10.7-T11 — REST API: GET /admin/v1/prefunding/dashboard - prefunding summary endpoint  _(35 min)_
**Context:** Admin API endpoint returning the real-time prefunding dashboard data (PRD-07 §9.2). RBAC: roles OPS_OPERATOR, FINANCE_ANALYST, SUPER_ADMIN may read; ADMIN_VIEWER is not permitted per permission matrix §12.3. Response is an array of summary cards. Must not cache - always reads live DB. Response fields per PrefundingDashboardEntry DTO from T09.
**Steps:** Create PrefundingController with GET /admin/v1/prefunding/dashboard; Apply @PreAuthorize or role-check for OPS_OPERATOR, FINANCE_ANALYST, SUPER_ADMIN; reject ADMIN_VIEWER with 403; Call PrefundingDashboardService.getDashboardEntries(); Wrap in standard API envelope {data: [...], meta: {count, as_of: ISO-8601 timestamp}}; Write integration test: 200 with correct payload for authorized role, 403 for ADMIN_VIEWER
**Deliverable:** GET /admin/v1/prefunding/dashboard endpoint returning real-time summary; integration test passing
**Acceptance / logic checks:**
- GET with FINANCE_ANALYST JWT returns 200 with array of partner summary objects including trafficLight
- GET with ADMIN_VIEWER JWT returns 403
- Response body includes as_of timestamp within 1 second of query time
- Partner with balance=8500, threshold=10000 appears in response with trafficLight=RED and balancePct=85.00
- Response time < 500ms with up to 10 OVERSEAS partners in DB
**Depends on:** 10.7-T09

### 10.7-T12 — REST API: GET /admin/v1/prefunding/{partnerId}/ledger - ledger pagination endpoint  _(30 min)_
**Context:** Admin API endpoint for the paginated prefunding ledger view (PRD-07 §9.4). Supports query params: page (0-indexed), size (default 50, max 200), entryType (optional filter), from (ISO date), to (ISO date). RBAC: OPS_OPERATOR, FINANCE_ANALYST, SUPER_ADMIN allowed; ADMIN_VIEWER not permitted. Response includes entries plus pagination metadata (totalElements, totalPages, currentPage).
**Steps:** Add GET /admin/v1/prefunding/{partnerId}/ledger to PrefundingController; Validate partnerId path variable; return 404 if partner not found; Apply role check; return 403 for ADMIN_VIEWER; Parse and validate query params; reject size > 200 with 400; Call PrefundingLedgerQueryService.getLedger with params; Return {data: [LedgerEntryDTO], pagination: {page, size, totalElements, totalPages}}
**Deliverable:** GET /admin/v1/prefunding/{partnerId}/ledger endpoint with pagination and filtering
**Acceptance / logic checks:**
- GET /admin/v1/prefunding/prt_sendmn_001/ledger returns 200 with up to 50 entries and pagination metadata
- GET with page=0&size=5 returns exactly 5 entries when more than 5 exist
- GET with entryType=TOPUP returns only TOPUP entries
- GET with unknown partnerId returns 404
- GET with ADMIN_VIEWER JWT returns 403
**Depends on:** 10.7-T10

### 10.7-T13 — REST API: POST /admin/v1/prefunding/{partnerId}/topup - record top-up  _(40 min)_
**Context:** Ops records a manual top-up via this endpoint (PRD-07 §9.3). Request body: amountUsd (decimal string, required, > 0), bankRef (string, required), valueDate (ISO date, required), notes (string, optional). RBAC: OPS_OPERATOR and SUPER_ADMIN only. Response: new balance, ledger entry id, timestamp. Idempotency: if the same bankRef is submitted twice for the same partner within 24 hours, return 409 Conflict with the original entry id.
**Steps:** Add POST /admin/v1/prefunding/{partnerId}/topup to PrefundingController; Apply role check; FINANCE_ANALYST and ADMIN_VIEWER get 403; Deserialize and validate request body: amountUsd > 0, bankRef not blank, valueDate present and not in future; Check idempotency: if CREDIT_TOPUP ledger entry with same bankRef+partnerId exists within 24h, return 409 with existing entry id; Call TopUpRecordingService.recordTopUp; return 201 with {ledgerEntryId, newBalanceUsd, timestamp}; Integration test covering success, duplicate, and FINANCE_ANALYST 403 cases
**Deliverable:** POST /admin/v1/prefunding/{partnerId}/topup endpoint with idempotency and RBAC; integration tests passing
**Acceptance / logic checks:**
- POST with amountUsd=50000.00 and valid bankRef returns 201; balance increases by 50000.00
- Second POST with same bankRef for same partner within 24h returns 409 with original ledger entry id
- POST with amountUsd=0 returns 400 with validation error
- FINANCE_ANALYST token returns 403
- POST on a LOCAL partner returns 400/422 with error indicating prefunding not applicable to LOCAL partners
**Depends on:** 10.7-T06

### 10.7-T14 — REST API: POST /admin/v1/prefunding/{partnerId}/adjustment - manual adjustment  _(40 min)_
**Context:** Finance Analyst or Super Admin creates a manual adjustment to correct a recording error (PRD-07 §9.4). Request: adjustmentAmountUsd (decimal, non-zero, positive or negative), reason (string, min 10 chars). Negative adjustment that would cause balance < 0 must be rejected with 422. Requires two-step confirmation per PRD-07 §14.2 UX notes - implement as: first POST returns a preview object (no DB write); client must confirm via POST with confirm=true flag. RBAC: FINANCE_ANALYST and SUPER_ADMIN only; OPS_OPERATOR gets 403.
**Steps:** Add POST /admin/v1/prefunding/{partnerId}/adjustment to PrefundingController; Apply role check: reject OPS_OPERATOR with 403; Parse body: adjustmentAmountUsd, reason, confirm (boolean, default false); If confirm=false: return preview {currentBalance, adjustmentAmount, projectedBalance} with 200, no DB write; If confirm=true: call ManualAdjustmentService.applyAdjustment; return 201 with new balance and ledger entry id; Return 422 if projected balance would be negative (both preview and confirmed paths)
**Deliverable:** POST /admin/v1/prefunding/{partnerId}/adjustment endpoint with two-step confirmation flow
**Acceptance / logic checks:**
- POST with confirm=false returns preview object showing projectedBalance = currentBalance + adjustmentAmountUsd, no DB rows created
- POST with confirm=true and valid data returns 201; new ledger entry exists with correct entry_type
- OPS_OPERATOR token returns 403
- adjustmentAmountUsd=-6000.00 on account with balance=5000.00 returns 422 on both preview and confirmed paths
- reason of 9 chars returns 400
**Depends on:** 10.7-T07

### 10.7-T15 — REST API: GET /admin/v1/prefunding/{partnerId}/balance - real-time balance point  _(25 min)_
**Context:** Simple endpoint returning the current real-time balance for one partner - used by dashboard refresh and other modules that need balance without full ledger. Must NOT use any cache layer; reads directly from prefunding_account table. Response: {partnerId, balanceUsd, thresholdUsd, isBelow Threshold, lastUpdatedAt}. RBAC: OPS_OPERATOR, FINANCE_ANALYST, SUPER_ADMIN. PRD-07 §14.1: prefunding balance reads must be real-time reflecting atomic DB state.
**Steps:** Add GET /admin/v1/prefunding/{partnerId}/balance to PrefundingController; Apply role check; Load prefunding_account by partnerId; return 404 if not found or if partner is LOCAL type; Compute isBelow Threshold = balance < low_balance_threshold; Return {partnerId, balanceUsd, thresholdUsd, isBelowThreshold, currency: USD, asOf: now()}; Verify no Redis/cache layer is bypassed - always queries Postgres directly
**Deliverable:** GET /admin/v1/prefunding/{partnerId}/balance endpoint returning live balance
**Acceptance / logic checks:**
- Response isBelowThreshold=true when balanceUsd=8500.00 and thresholdUsd=10000.00
- Response isBelowThreshold=false when balanceUsd=15000.00 and thresholdUsd=10000.00
- GET on LOCAL partner partnerId returns 404 or 400 with clear error
- asOf timestamp in response is within 100ms of request time
- Two sequential calls returning different balances (if a deduction occurred between them) confirms no caching
**Depends on:** 10.7-T04

### 10.7-T16 — Prefunding Dashboard UI page - balance summary cards with traffic-light indicators  _(50 min)_
**Context:** Admin System frontend: Prefunding module main page showing one summary card per OVERSEAS partner (PRD-07 §9.2, §14.2). Each card: partner name, balance USD (2dp), threshold USD, balance %, traffic-light badge (RED/AMBER/GREEN per §9.2 thresholds: green >= 150%, amber 100-150%, red < 100%), last deduction, last top-up. Cards sorted RED first. Clicking a card navigates to that partner ledger page. UX: traffic-light uses CSS colour classes (red=#D32F2F, amber=#F57C00, green=#388E3C). Refresh button re-fetches from GET /admin/v1/prefunding/dashboard (no auto-poll).
**Steps:** Create PrefundingDashboardPage component (React or framework in use); Fetch data from GET /admin/v1/prefunding/dashboard on mount and on Refresh click; Render one card per entry with all required fields; apply CSS class based on trafficLight enum value; Show Loading spinner while fetch in progress; show error banner on API error; Sort cards: RED first, AMBER second, GREEN third; Write a component test: mock API returning 2 partners (one RED, one GREEN); assert RED renders first with correct colour class
**Deliverable:** PrefundingDashboardPage component with traffic-light cards, sorted by severity, with Refresh button
**Acceptance / logic checks:**
- Partner with trafficLight=RED renders with CSS class or background colour #D32F2F or equivalent red token
- Partner with trafficLight=GREEN renders with green styling and appears after RED partner in DOM order
- Clicking a card navigates to /admin/prefunding/{partnerId}/ledger
- Refresh button triggers a new GET request and re-renders updated balances
- If API returns 403, page displays an access-denied message rather than blank or crash
**Depends on:** 10.7-T11

### 10.7-T17 — Prefunding Ledger UI page - paginated ledger table with filters  _(50 min)_
**Context:** Admin System frontend: per-partner prefunding ledger view (PRD-07 §9.4). Table columns: Ledger Entry ID, Timestamp (UTC), Type (display name: TOPUP/DEDUCTION/ADJUSTMENT/REFUND_REVERSAL), Amount USD (coloured: green for credit, red for debit), Balance After USD, Hub Txn Ref (link to transaction detail if present), Reference, Recorded By. Filters: entry type multi-select, date range picker. Pagination: 50 rows per page with next/prev. Balance reads must be real-time; no stale cache. Data from GET /admin/v1/prefunding/{partnerId}/ledger.
**Steps:** Create PrefundingLedgerPage component accepting partnerId from route param; Render filter bar: entry type multi-select (TOPUP, DEDUCTION, ADJUSTMENT, REFUND_REVERSAL), date range (from/to date pickers); Render table with all 8 columns; amount positive = green text, negative = red text; Implement pagination using page/size query params; show total record count; Hub Txn Ref cell: if present, render as link to /admin/transactions/{txnRef}; Fetch from API on filter change with debounce 300ms; show loading state
**Deliverable:** PrefundingLedgerPage component with filtered, paginated ledger table
**Acceptance / logic checks:**
- DEDUCTION entry renders amount in red text; TOPUP entry renders amount in green text
- Applying filter type=TOPUP causes API request with entryType=TOPUP param and table re-renders with only TOPUP rows
- Hub Txn Ref cell renders as clickable link when txn_ref is present; renders dash when null
- Pagination controls: Next button disabled on last page; Prev button disabled on page 0
- Component test: mock 55 entries, assert page 1 shows 50 rows and Next button is enabled
**Depends on:** 10.7-T12

### 10.7-T18 — Top-Up Recording UI form with confirmation dialog  _(45 min)_
**Context:** Admin System frontend: Top-Up Recording form (PRD-07 §9.3). Accessible from Prefunding Dashboard via Record Top-Up button (visible only to OPS_OPERATOR and SUPER_ADMIN). Fields: Partner (pre-selected if from partner context), Top-Up Amount USD (positive decimal), Bank Reference (text, required), Value Date (date picker, required, not future), Notes (optional textarea). On submit: show confirmation dialog with all entered values + current and projected balance; confirm triggers POST /admin/v1/prefunding/{partnerId}/topup. On success: show success toast with new balance; refresh dashboard.
**Steps:** Create TopUpFormModal component with all required fields and validation; Add confirmation dialog showing: partner name, amount, bankRef, valueDate, current balance, projected balance; On dialog confirm: POST to /admin/v1/prefunding/{partnerId}/topup with {amountUsd, bankRef, valueDate, notes}; Handle 409 Conflict: show warning that this bankRef was already recorded, display original entry ID; Handle 400/422: show inline field errors; On success: emit event to parent to refresh balance; show toast with new balance USD
**Deliverable:** TopUpFormModal component with validation, confirmation dialog, and 409 duplicate handling
**Acceptance / logic checks:**
- Submitting form without bankRef shows required field error inline before confirmation dialog opens
- Confirmation dialog displays projected balance = current balance + entered amount (e.g. current=5000, amount=50000, projected=55000.00)
- Entering a future date in valueDate shows validation error
- On 409 response, modal shows a warning containing the original ledger entry ID
- After successful submit, dashboard card for that partner shows updated balance without full page reload
**Depends on:** 10.7-T13, 10.7-T16

### 10.7-T19 — Manual Adjustment UI form with two-step preview-then-confirm flow  _(45 min)_
**Context:** Admin System frontend: Manual Adjustment form (PRD-07 §9.4, §14.2). Accessible to FINANCE_ANALYST and SUPER_ADMIN only (OPS_OPERATOR must not see the button). Fields: Adjustment Amount USD (can be positive or negative), Reason (required, min 10 chars). Flow: (1) submit triggers POST with confirm=false to get preview response; (2) show preview dialog with current balance, adjustment, projected balance; (3) if projected < 0 show error in dialog; (4) user clicks Confirm triggers POST with confirm=true. Uses two-step UX per PRD-07 §14.2.
**Steps:** Create AdjustmentFormModal with adjustmentAmountUsd (number input, positive or negative) and reason (textarea, min 10 chars client-side); On first submit: POST /admin/v1/prefunding/{partnerId}/adjustment with confirm=false; render preview dialog with API-returned projectedBalance; In preview dialog: if projectedBalance < 0 show red error and disable Confirm button; On Confirm click: POST same endpoint with confirm=true; handle success and errors; Hide Record Adjustment button entirely for OPS_OPERATOR role (check from auth context); Write component test: assert Confirm button is disabled when API preview returns projectedBalance=-500
**Deliverable:** AdjustmentFormModal component with two-step flow, negative balance guard, and role-based visibility
**Acceptance / logic checks:**
- First POST (confirm=false) does not create a DB row; preview dialog shows correct projectedBalance
- If adjustment would result in projectedBalance < 0, Confirm button is disabled and error message shown in dialog
- Reason field of 9 chars prevents form submission with client-side validation error
- OPS_OPERATOR auth context: Record Adjustment button not rendered in DOM
- Successful confirm=true POST closes modal and dashboard balance reflects adjustment
**Depends on:** 10.7-T14, 10.7-T16

### 10.7-T20 — Low-balance banner on Admin Dashboard - in-portal urgent alert  _(40 min)_
**Context:** When any OVERSEAS partner balance reaches USD 0.00 (urgent alert case per PRD-07 §9.5), the Admin Dashboard must display a persistent red banner: Partner {name} prefunding balance is USD 0.00 - all payments suspended. Banner is dismissible for the session but reappears on next login if balance is still 0. Dashboard also shows amber badge on partners between 0 and threshold. Banner data sourced from GET /admin/v1/prefunding/dashboard checking for balanceUsd=0 entries. Payments-suspended state is enforced in backend (T04 InsufficientBalanceException) but this ticket only covers the in-portal banner display.
**Steps:** In Admin Dashboard component, fetch GET /admin/v1/prefunding/dashboard and filter for entries with balanceUsd <= 0; Render a dismissible red banner for each zero-balance partner: Partner {name} has USD 0.00 prefunding balance - payments are suspended; Store dismissed banner IDs in sessionStorage; do not show dismissed ones until next session; For AMBER entries (balanceUsd < thresholdUsd and > 0), show a smaller amber warning badge in the left-nav Prefunding menu item showing count; Auto-refresh dashboard data every 60 seconds on the Dashboard page only
**Deliverable:** Dashboard component showing red zero-balance banners and amber nav badge; session-dismiss working
**Acceptance / logic checks:**
- Partner with balanceUsd=0 renders a red banner with partner name and suspended warning
- Dismissing banner removes it from view; it reappears on page reload (sessionStorage cleared on close)
- Partner with balanceUsd=5000 and threshold=10000 causes amber badge with count=1 on Prefunding nav item
- Partner with balanceUsd=15000 and threshold=10000 causes no banner or badge
- Dashboard data auto-refreshes every 60 seconds (verify with network request timing)
**Depends on:** 10.7-T11, 10.7-T08

### 10.7-T21 — Email alert delivery integration - low-balance and urgent email via notification service  _(40 min)_
**Context:** LowBalanceAlertService (T08) needs to actually send emails. The notification infrastructure sends emails via the Hub internal NotificationService. Email template for LOW_BALANCE: subject = GMEPay+ Alert: Low Prefunding Balance - {partnerName}; body fields per PRD-07 §9.5: partner name, current balance USD, threshold USD, suggested top-up = max(threshold * 2 - balance, threshold). URGENT template: subject = URGENT: {partnerName} Prefunding Balance Exhausted - Payments Suspended; body includes same fields plus payments-suspended notice. Recipients from low_balance_alert_config.alert_email (split on comma, trimmed).
**Steps:** Create AlertEmailTemplate enum with LOW_BALANCE and URGENT_ZERO_BALANCE entries plus template rendering; Implement NotificationService.sendLowBalanceAlert(partnerName, balanceUsd, thresholdUsd, recipients); Wire into LowBalanceAlertService.checkAndAlert from T08; Write unit test mocking SMTP/email client: deduction from 12000 to 8500 (threshold=10000) sends exactly one LOW_BALANCE email to configured address; Write unit test for URGENT path: balance becomes 0, URGENT_ZERO_BALANCE email is sent; Confirm alert_email with two comma-separated addresses sends to both
**Deliverable:** NotificationService.sendLowBalanceAlert and sendUrgentZeroBalanceAlert integrated and unit-tested
**Acceptance / logic checks:**
- Unit test: deduction to 8500 (threshold 10000) calls sendLowBalanceAlert with correct partnerName, balanceUsd, thresholdUsd
- Suggested top-up computed as max(10000*2 - 8500, 10000) = 11500.00 appears in email body
- alert_email = ops@gme.com,finance@gme.com results in exactly 2 email deliveries
- Balance deduction remaining above threshold (15000 -> 12000 with threshold 10000) sends no alert
- URGENT template email subject contains the word URGENT and phrase Payments Suspended
**Depends on:** 10.7-T08

### 10.7-T22 — Alert threshold configuration - update low_balance_threshold via admin API and UI  _(40 min)_
**Context:** The low-balance threshold is configurable per OVERSEAS partner (PRD-07 §5.3.3: default USD 10,000). Ops can update it from the Partner edit page or from the Prefunding module. Backend: PATCH /admin/v1/prefunding/{partnerId}/alert-config updates low_balance_alert_config.threshold_usd and also syncs prefunding_account.low_balance_threshold (both must stay consistent). Validation: threshold > 0. Change is audit-logged. Frontend: a small inline edit widget on the Prefunding Dashboard card showing the threshold with an Edit icon; input > 0 only.
**Steps:** Create PATCH /admin/v1/prefunding/{partnerId}/alert-config endpoint accepting {thresholdUsd, alertEmail (optional update), isActive}; Validate thresholdUsd > 0; reject with 400 otherwise; Update low_balance_alert_config row; also update prefunding_account.low_balance_threshold to match; Write audit log entry: event_type=prefunding.alert_threshold_updated, previous and new threshold values; Frontend: add inline-edit pencil icon on threshold display in dashboard card; on save call PATCH; re-render with new threshold and updated trafficLight badge; RBAC: OPS_OPERATOR and SUPER_ADMIN only
**Deliverable:** PATCH /admin/v1/prefunding/{partnerId}/alert-config endpoint and inline-edit UI on dashboard card
**Acceptance / logic checks:**
- PATCH with thresholdUsd=20000.00 updates both low_balance_alert_config.threshold_usd and prefunding_account.low_balance_threshold to 20000.0000
- PATCH with thresholdUsd=0 returns 400
- Audit log entry exists with previous_threshold and new_threshold after update
- After threshold update from 10000 to 20000, partner with balance=15000 now shows trafficLight=AMBER instead of GREEN on dashboard
- FINANCE_ANALYST calling PATCH returns 403
**Depends on:** 10.7-T03, 10.7-T11

### 10.7-T23 — Prefunding section on Partner detail page - balance panel and quick-links  _(35 min)_
**Context:** The Partner detail page in the Partners module (PRD-07 §5.3.3) must show a Prefunding Setup section for OVERSEAS partners only: prefunding account ID, current balance USD (real-time), low-balance threshold, alert recipients, traffic-light badge. Quick-link buttons: View Ledger (-> /admin/prefunding/{partnerId}/ledger), Record Top-Up (opens TopUpFormModal). For LOCAL partners (e.g. GME Remit), the entire Prefunding section is hidden. Data from GET /admin/v1/prefunding/{partnerId}/balance and alert config.
**Steps:** In PartnerDetailPage component, conditionally render PrefundingPanel only when partner.type === OVERSEAS; PrefundingPanel fetches GET /admin/v1/prefunding/{partnerId}/balance for live balance; Display: account ID, balance USD (2dp), threshold USD, isBelowThreshold badge, alert_email list; Render View Ledger link and Record Top-Up button (button hidden for FINANCE_ANALYST role); Write component test: LOCAL partner renders no PrefundingPanel; OVERSEAS partner renders panel with balance data
**Deliverable:** PrefundingPanel sub-component on PartnerDetailPage; hidden for LOCAL partners; live balance displayed
**Acceptance / logic checks:**
- PartnerDetailPage for GME Remit (LOCAL) renders no PrefundingPanel section
- PartnerDetailPage for SendMN (OVERSEAS) renders PrefundingPanel with live balance and threshold
- isBelowThreshold=true in API response renders amber/red indicator on the panel
- Record Top-Up button is visible for OPS_OPERATOR and hidden for FINANCE_ANALYST
- View Ledger link navigates to /admin/prefunding/prt_sendmn_001/ledger
**Depends on:** 10.7-T15, 10.7-T18

### 10.7-T24 — Unit tests: TopUpRecordingService and ManualAdjustmentService logic  _(40 min)_
**Context:** Unit-test suite for T06 and T07 service logic. Tests must cover: normal top-up flow, duplicate bankRef idempotency, LOCAL partner rejection, zero-amount validation, adjustment preview vs commit, adjustment negative-balance guard, RBAC enforcement, and audit log invocation. Use Mockito (or equivalent) to mock repositories and AuditLogService. Tests must be self-contained with no DB dependency.
**Steps:** Create TopUpRecordingServiceTest with test vectors listed in checks; Create ManualAdjustmentServiceTest with test vectors listed in checks; Mock PrefundingAccountRepository, LedgerAppendService, AuditLogService; Verify AuditLogService.log() is called exactly once per successful operation; Assert InsufficientBalanceException is thrown (not a different exception) when balance would go negative; Run tests with ./mvnw test -pl <module> and confirm all pass
**Deliverable:** TopUpRecordingServiceTest and ManualAdjustmentServiceTest; all tests green
**Acceptance / logic checks:**
- recordTopUp(partnerId=LOCAL_PARTNER, ...) throws PartnerTypeException
- recordTopUp(amountUsd=0) throws ValidationException before any repo call
- recordTopUp(amountUsd=50000, bankRef=WIRE-001) succeeds; verify creditBalance called with 50000.0000 and appendCredit called with CREDIT_TOPUP
- applyAdjustment called by OPS_OPERATOR role throws AccessDeniedException
- applyAdjustment(-6000) on account with balance 5000 throws InsufficientBalanceException; verify no ledger entry appended
- applyAdjustment(+1000, reason=fix error in entry) with reason < 10 chars throws ValidationException
**Depends on:** 10.7-T06, 10.7-T07

### 10.7-T25 — Unit tests: LowBalanceAlertService threshold-crossing logic  _(30 min)_
**Context:** Unit-test suite for T08 LowBalanceAlertService. Key invariant: alert fires only when balance crosses from above threshold to below (not on every deduction while already below). Urgent alert fires when balance <= 0. Tests use mocked NotificationService and mocked prefunding_account reads.
**Steps:** Create LowBalanceAlertServiceTest; Test 1: previousBalance=12000, newBalance=8500, threshold=10000 -> sendLowBalanceAlert called once; Test 2: previousBalance=8000, newBalance=7000, threshold=10000 -> NO alert (already below; no crossing); Test 3: previousBalance=12000, newBalance=0, threshold=10000 -> sendUrgentZeroBalanceAlert called (not low-balance); Test 4: previousBalance=12000, newBalance=10001, threshold=10000 -> no alert (still above threshold); Test 5: is_active=false on alert config -> no email sent regardless of balance
**Deliverable:** LowBalanceAlertServiceTest with all 5 test vectors passing
**Acceptance / logic checks:**
- Test 1 passes: crossing from above to below threshold triggers exactly one LOW_BALANCE email
- Test 2 passes: no duplicate alert when already below threshold; NotificationService never called
- Test 3 passes: newBalance=0 triggers URGENT path, not LOW_BALANCE
- Test 4 passes: balance remains above threshold, no alert sent
- Test 5 passes: is_active=false suppresses all notifications
**Depends on:** 10.7-T08

### 10.7-T26 — Unit tests: PrefundingAccountRepository atomic deduction (concurrent scenario)  _(40 min)_
**Context:** Integration-level unit test (Testcontainers Postgres) verifying SELECT FOR UPDATE prevents double-spend. Scenario: two threads simultaneously call deductBalance on an account with balance=100.00, each deducting 100.00. Expected: exactly one succeeds, one throws InsufficientBalanceException, final balance=0.00. Also tests creditBalance precision using BigDecimal arithmetic.
**Steps:** Configure Testcontainers Postgres in test scope; apply migrations from T01/T02; Write ConcurrentDeductionTest: create account with balance=100.00; launch 2 threads calling deductBalance(100.00) simultaneously via ExecutorService; Collect results; assert exactly 1 success and 1 InsufficientBalanceException; Assert final balance = 0.0000 in DB after both threads complete; Write PrecisionTest: creditBalance(0.0001) then creditBalance(0.0001) -> balance = 0.0002 (not 0.00019... floating error)
**Deliverable:** ConcurrentDeductionTest and PrecisionTest passing against real Postgres via Testcontainers
**Acceptance / logic checks:**
- Concurrent test: exactly 1 of 2 threads succeeds when both request 100.00 from a 100.00 account
- Final DB balance after concurrent test = 0.0000 (not negative, not 100.00)
- Precision test: two deductions of 0.0001 each result in balance change of exactly 0.0002 in DECIMAL(20,4) column
- creditBalance(50000.00) followed by deductBalance(50000.00) results in balance=0.0000 with no floating-point rounding error
- Test runs without external dependencies other than Testcontainers Postgres
**Depends on:** 10.7-T04

### 10.7-T27 — Unit tests: PrefundingDashboardService - traffic-light computation and sort order  _(25 min)_
**Context:** Unit tests for PrefundingDashboardService (T09) verifying traffic-light classification and sort logic. All tests use mocked repositories; no DB required.
**Steps:** Create PrefundingDashboardServiceTest; Test balancePct and trafficLight for boundary values: 149.99% = AMBER, 150.00% = GREEN, 100.00% = AMBER, 99.99% = RED; Test sort order: list of [AMBER, GREEN, RED] partners returns [RED, AMBER, GREEN]; Test lastDeduction/lastTopUp fields populated from mocked ledger entries; Test partner with no ledger entries returns null for lastDeductionAt and lastTopUpAt (no NPE)
**Deliverable:** PrefundingDashboardServiceTest with boundary and sort tests all passing
**Acceptance / logic checks:**
- balance=14999, threshold=10000 -> balancePct=149.99, trafficLight=AMBER
- balance=15000, threshold=10000 -> balancePct=150.00, trafficLight=GREEN
- balance=10000, threshold=10000 -> balancePct=100.00, trafficLight=AMBER
- balance=9999, threshold=10000 -> balancePct=99.99, trafficLight=RED
- List input [AMBER, GREEN, RED] is returned sorted as [RED, AMBER, GREEN]
**Depends on:** 10.7-T09

### 10.7-T28 — Acceptance test: end-to-end top-up and deduction flow via API  _(50 min)_
**Context:** End-to-end integration test (Spring Boot test with Testcontainers Postgres) covering the full prefunding lifecycle: onboard OVERSEAS partner -> record top-up -> simulate payment deduction -> verify ledger entries -> trigger low-balance check. Verifies all layers (service + repository + DB) work together correctly. Focuses on the data correctness path, not the email delivery.
**Steps:** Set up test: insert OVERSEAS partner (SendMN) and prefunding_account with balance=0; POST /admin/v1/prefunding/{id}/topup amountUsd=50000.00 bankRef=TEST-WIRE-001; assert 201 and balance=50000.00; Directly call PrefundingAccountRepository.deductBalance(accountId, 49500.00) to simulate payment; assert balance=500.00; GET /admin/v1/prefunding/{id}/balance; assert balanceUsd=500.00 and isBelowThreshold=true (threshold=10000); GET /admin/v1/prefunding/{id}/ledger; assert 2 entries: CREDIT_TOPUP(50000) and DEBIT_PAYMENT(49500); balance_after values correct; Attempt deductBalance(600.00) on balance=500.00; assert InsufficientBalanceException thrown; balance remains 500.00
**Deliverable:** End-to-end integration test passing all 6 assertion steps
**Acceptance / logic checks:**
- After topup, balance=50000.0000 in DB
- After deduction, balance=500.0000 in DB
- GET /balance returns isBelowThreshold=true (500 < 10000)
- Ledger has exactly 2 entries in correct order with correct balance_after snapshots (50000, then 500)
- Overdraft attempt throws InsufficientBalanceException and leaves balance at 500.0000
- All 6 steps pass in a single test run with no manual DB setup required
**Depends on:** 10.7-T13, 10.7-T04, 10.7-T05, 10.7-T26

### 10.7-T29 — Docs: Prefunding module operator runbook (inline API docs + Swagger annotations)  _(35 min)_
**Context:** Every admin API endpoint must have OpenAPI 3.0 / Swagger annotations so the auto-generated spec is accurate (PRD-07 requirement for operations team). Additionally a short operator runbook comment block in each Service class method explains when and why Ops would use each operation. No separate .md files; documentation lives in code annotations and Swagger.
**Steps:** Add @Operation, @ApiResponse, @Parameter Swagger annotations to all 5 prefunding endpoints (dashboard, ledger, balance, topup, adjustment); Document error responses: 400, 403, 404, 409, 422 with description per endpoint; Add Javadoc block to TopUpRecordingService.recordTopUp, ManualAdjustmentService.applyAdjustment, LowBalanceAlertService.checkAndAlert explaining business purpose and when to use; Verify /v3/api-docs renders all 5 endpoints with correct request/response schemas; Add @Schema annotations to all DTO fields with example values (e.g. @Schema(example=50000.00))
**Deliverable:** All 5 prefunding endpoints documented in Swagger with examples; key service methods have Javadoc
**Acceptance / logic checks:**
- GET /v3/api-docs includes all 5 prefunding endpoints with non-empty summary and description
- TopUp endpoint schema shows amountUsd with example 50000.00 and bankRef with example WIRE-2026-001
- All 4xx/5xx response codes documented with meaningful description per endpoint
- Javadoc on recordTopUp explains the two-phase commit: creditBalance then ledger entry then audit log
- Swagger UI renders the prefunding section without errors when loaded in browser
**Depends on:** 10.7-T13, 10.7-T14, 10.7-T15


## WBS 10.8 — Refund/cancellation handling UI
### 10.8-T01 — Add DB migration: refund table with all Phase 1 columns  _(30 min)_
**Context:** PRD-07 §10.3 requires a refund entity. DAT-03 §5.4 defines: refund(id BIGINT PK, txn_id BIGINT FK->transaction, refund_ref VARCHAR(64) UNIQUE, refund_amount DECIMAL(20,4), refund_ccy CHAR(3), reason VARCHAR(255), status VARCHAR(20) [REFUND_PENDING|REFUND_SUBMITTED|REFUND_CONFIRMED|REFUND_FAILED|REFUNDED], scheme_refund_ref VARCHAR(128), initiated_by VARCHAR(120), created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ, created_by VARCHAR(120), updated_by VARCHAR(120)). The refund_ref is the gme_refund_id used in ZP0021 batch. One refund row per refund request; partial refunds (refund_amount < original target_payout) are supported.
**Steps:** Create a Flyway/Liquibase migration file V<next>__create_refund_table.sql; Add all columns from DAT-03 §5.4 with correct types and constraints; Add index on txn_id (for fast lookup from transaction detail page); Add index on status (for batch aggregation of REFUND_PENDING rows); Add CHECK constraint: status IN ('REFUND_PENDING','REFUND_SUBMITTED','REFUND_CONFIRMED','REFUND_FAILED','REFUNDED')
**Deliverable:** Migration file V<next>__create_refund_table.sql applied cleanly to local schema
**Acceptance / logic checks:**
- Migration runs without error on a clean schema; rollback script reverts cleanly
- refund_ref column has UNIQUE constraint; inserting duplicate refund_ref raises DB error
- refund_amount column is DECIMAL(20,4); inserting a value with 5 decimal places is rejected or rounded per DB precision rules
- status column rejects values outside the 5 allowed enum values via CHECK constraint
- txn_id FK references transaction.id; inserting a refund with a non-existent txn_id raises FK violation

### 10.8-T02 — Add refund_window_days config to qr_scheme table  _(20 min)_
**Context:** PRD-07 §10.3.1 and A-07: the refund eligibility window defaults to 90 days and is configurable per scheme. The qr_scheme table (DAT-03 §4.1) needs a new integer column refund_window_days (NOT NULL DEFAULT 90, CHECK refund_window_days > 0). This value is read at refund-initiation time to determine eligibility.
**Steps:** Create migration file adding refund_window_days INT NOT NULL DEFAULT 90 to qr_scheme; Add CHECK constraint: refund_window_days > 0; Update the ZeroPay seed/fixture row to set refund_window_days = 90; Document the column in the schema comments
**Deliverable:** Migration file adding refund_window_days to qr_scheme, applied cleanly
**Acceptance / logic checks:**
- Column exists with DEFAULT 90 after migration; existing ZeroPay row reads 90
- Setting refund_window_days = 0 is rejected by CHECK constraint
- Setting refund_window_days = 180 persists correctly
- Existing qr_scheme rows without an explicit value default to 90

### 10.8-T03 — Add RefundRepository: CRUD + status-update + batch-query methods  _(40 min)_
**Context:** The refund table (10.8-T01) needs a Spring Data / JPA repository (or equivalent). Key query patterns needed: findByTxnId, findByStatus(REFUND_PENDING), updateStatus(id, newStatus, updatedBy), countByTxnIdAndStatusNotIn (to guard against duplicate refunds). Use DECIMAL for all monetary fields (never double/float). No UPDATE permitted on refund_amount or txn_id after insert.
**Steps:** Create RefundEntity JPA class mapping refund table columns; Create RefundRepository interface with findByTxnId, findAllByStatus, updateStatus; Add a method findPendingRefundsForBatch() returning rows with status=REFUND_PENDING ordered by created_at ASC; Ensure updateStatus uses optimistic locking (version field or explicit WHERE status=<expected>) to detect race conditions; Write a constructor that disallows null txn_id, null refund_amount, or refund_amount <= 0
**Deliverable:** RefundRepository class and RefundEntity with all columns mapped
**Acceptance / logic checks:**
- findAllByStatus(REFUND_PENDING) returns only rows in that status
- updateStatus fails (throws OptimisticLockException or returns 0 rows updated) if concurrent update has already changed status
- Constructor throws IllegalArgumentException when refund_amount is 0 or negative
- findByTxnId returns all refunds for that transaction (allowing detection of already-refunded state)
- No floating-point types (float, double) used for monetary fields; DECIMAL/BigDecimal only
**Depends on:** 10.8-T01

### 10.8-T04 — Implement RefundEligibilityService: validate a transaction is refundable  _(45 min)_
**Context:** PRD-07 §10.3.1: a refund is eligible when (1) transaction status is APPROVED or SCHEME_APPROVED, (2) transaction created_at is within the scheme's refund_window_days of today (KST), (3) transaction has not already been refunded (no existing refund row in status other than REFUND_FAILED), (4) transaction is linked to an active ZeroPay merchant record (merchant.is_active = true). Return a typed result (eligible=true/false + reason code). KST = UTC+9; use ZoneId.of('Asia/Seoul') for date arithmetic.
**Steps:** Create RefundEligibilityService.checkEligibility(txnId) -> EligibilityResult; Check transaction.status in [APPROVED, SCHEME_APPROVED]; else return NOT_ELIGIBLE_STATUS; Check (today_KST - transaction.created_at_KST) <= scheme.refund_window_days; else return NOT_ELIGIBLE_WINDOW; Query refund table: if any row for txn_id with status not in [REFUND_FAILED] exists, return ALREADY_REFUNDED; Check merchant.is_active = true for the merchant linked to this transaction; else return MERCHANT_INACTIVE; Return ELIGIBLE if all checks pass
**Deliverable:** RefundEligibilityService class with checkEligibility method and EligibilityResult enum/record
**Acceptance / logic checks:**
- Transaction in FAILED status returns NOT_ELIGIBLE_STATUS
- Transaction created 91 days ago with scheme refund_window_days=90 returns NOT_ELIGIBLE_WINDOW; 89 days ago returns ELIGIBLE (all other checks pass)
- Transaction with an existing refund in REFUND_PENDING status returns ALREADY_REFUNDED
- Transaction with a REFUND_FAILED refund and no other refund returns ELIGIBLE (failed refund does not block retry)
- Transaction linked to a merchant with is_active=false returns MERCHANT_INACTIVE
**Depends on:** 10.8-T01, 10.8-T02, 10.8-T03

### 10.8-T05 — Implement RefundInitiationService: create refund record and audit log entry  _(50 min)_
**Context:** PRD-07 §10.3.2: on Submit Refund, the backend must (a) call RefundEligibilityService, (b) validate refund_amount <= original transaction target_payout (payout ccy), (c) insert a refund row in status REFUND_PENDING with a unique refund_ref (e.g. 'RFD-' + hub_txn_ref + '-' + timestamp millis), (d) write an audit log entry with event_type='refund.initiated', actor_user_id, entity_type='refund', entity_id, previous_value=null, new_value=JSON of refund record. The parent transaction status must NOT change at this point (it transitions only when ZP0022 confirms). Reason codes are: CUSTOMER_REQUEST, MERCHANT_ERROR, SETTLEMENT_ERROR.
**Steps:** Create RefundInitiationService.initiateRefund(txnId, refundAmount, reasonCode, internalNotes, actorUserId); Call RefundEligibilityService; throw RefundNotEligibleException with reason code if not eligible; Validate refundAmount <= transaction.target_payout; throw RefundAmountExceededException if not; Generate unique refund_ref using format 'RFD-{hub_txn_ref}-{epochMillis}'; Insert refund row with status=REFUND_PENDING inside a @Transactional block; Write audit log entry (event_type=refund.initiated, actor_user_id, entity_id=refund.id); Return the created RefundRecord
**Deliverable:** RefundInitiationService with initiateRefund method, wrapped in @Transactional
**Acceptance / logic checks:**
- Calling initiateRefund on an APPROVED transaction with refund_amount = original target_payout creates one REFUND_PENDING row and one audit log entry
- Calling initiateRefund with refund_amount = original_payout + 1 KRW throws RefundAmountExceededException (e.g. original KRW 10000, attempted KRW 10001)
- Calling initiateRefund twice on the same transaction throws RefundNotEligibleException with reason ALREADY_REFUNDED on the second call
- Audit log entry contains actor_user_id, event_type=refund.initiated, and new_value JSON including refund_amount and reason_code
- Transaction status remains APPROVED after initiation (not yet REFUND_PENDING on the transaction itself)
**Depends on:** 10.8-T03, 10.8-T04

### 10.8-T06 — Implement admin-portal cancel for same-day COMMITTED/SUBMITTED_TO_SCHEME transactions  _(55 min)_
**Context:** PRD-07 §10.2: Ops can cancel a transaction via portal if status is COMMITTED or SUBMITTED_TO_SCHEME and it is still the same calendar day (KST) as transaction creation. On cancel: (a) call the ZeroPay real-time cancel API via the scheme adapter, (b) for OVERSEAS partners reverse the prefunding deduction (insert a REFUND_REVERSAL ledger entry with positive amount = original deduction), (c) set transaction.status = CANCELLED, (d) write audit log entry event_type=transaction.cancelled. Same-day = created_at_KST.date == today_KST.date. This is distinct from the post-settlement refund flow.
**Steps:** Create CancellationService.cancelTransaction(txnId, actorUserId); Validate transaction.status in [COMMITTED, SUBMITTED_TO_SCHEME]; else throw CancellationNotAllowedException; Validate same-day window: transaction.created_at in KST must equal today KST date; else throw CancellationWindowExpiredException; Call scheme adapter cancelPayment(hub_txn_ref); handle adapter errors; If partner is OVERSEAS: insert prefunding_ledger_entry type=REFUND_REVERSAL, amount=+original_deduction_usd, atomically update prefunding_account.balance; Update transaction.status = CANCELLED inside @Transactional; Write audit log entry event_type=transaction.cancelled with actor_user_id
**Deliverable:** CancellationService class with cancelTransaction method
**Acceptance / logic checks:**
- Cancelling a COMMITTED OVERSEAS transaction on the same day sets status=CANCELLED and creates a REFUND_REVERSAL prefunding ledger entry equal to the original collection_amount in USD
- Cancelling a LOCAL (GME Remit) COMMITTED transaction sets status=CANCELLED with no prefunding ledger entry
- Attempting to cancel an APPROVED (post-scheme) transaction throws CancellationNotAllowedException
- Cancelling a transaction created on a previous KST calendar day throws CancellationWindowExpiredException even if fewer than 24 hours have passed (e.g. created at 23:50 KST yesterday, cancelled at 00:10 KST today is rejected)
- Audit log entry is written with event_type=transaction.cancelled and actor_user_id
**Depends on:** 10.8-T03

### 10.8-T07 — Implement ZP0021 batch file generator: aggregate REFUND_PENDING rows into fixed-width file  _(55 min)_
**Context:** SCH-06 §6.2: ZP0021 is transmitted by GME to ZeroPay at ~02:00 KST daily. Scope: all refunds in status REFUND_PENDING where the refund was created before the batch cutoff (i.e. created_at_KST < today 02:00 KST). Record layout (fixed-width): record_type CHAR(1)='D', gme_refund_id CHAR(20), original_zeropay_txn_ref CHAR(20), gme_original_txn_id CHAR(20), merchant_id CHAR(10), refund_date DATE(8) YYYYMMDD KST, refund_time TIME(6) HHMMSS KST, refund_amount_krw NUM(12) right-justified zero-padded, merchant_fee_adj_amt NUM(12), partner_type CHAR(1) [D=Domestic/I=International], refund_reason_code CHAR(2), status_code CHAR(1)='R'. File header and trailer follow ZP0011 pattern with file_type=ZP0021.
**Steps:** Create Zp0021BatchGenerator.generateFile(settlementDate) that queries all REFUND_PENDING refunds for the given business date; Map each refund record to the fixed-width layout using the ZP0021 field definitions; Write file header (file_type=ZP0021) and trailer (record count); Persist a settlement_batch row and settlement_file row for this batch run; Update each included refund row from REFUND_PENDING to REFUND_SUBMITTED atomically; Return the generated file bytes / path for SFTP upload
**Deliverable:** Zp0021BatchGenerator class with generateFile method producing a compliant fixed-width file
**Acceptance / logic checks:**
- A refund with refund_amount=10000 KRW produces a record with refund_amount_krw field right-justified zero-padded to 12 digits: '000000010000'
- refund_reason_code CUSTOMER_REQUEST maps to scheme-defined 2-char code (e.g. '01'); verify mapping table is complete for all 3 reason codes
- After batch generation all included refund rows have status=REFUND_SUBMITTED; rows created after the batch cutoff remain REFUND_PENDING
- File trailer record count equals the number of detail records in the file
- Domestic partner (GME Remit) produces partner_type='D'; overseas (SendMN) produces 'I'
**Depends on:** 10.8-T03

### 10.8-T08 — Implement ZP0022 result file processor: parse and apply refund registration results  _(55 min)_
**Context:** SCH-06 §6.3: ZeroPay returns ZP0022 by ~05:00 KST. Record layout: original_zeropay_txn_ref CHAR(20), gme_refund_id CHAR(20), result_code CHAR(2) [00=Success], result_message VARCHAR(100), registered_refund_amount NUM(12), adjustment_settlement_date DATE(8). Match key is original_zeropay_txn_ref + gme_refund_id. Success (result_code=00): update refund.status=REFUND_CONFIRMED, store scheme_refund_ref, then trigger post-confirmation handler. Failure (result_code != 00): update refund.status=REFUND_FAILED with error stored in reason field; send Ops alert. For UNCERTAIN reconciliation: on ZP0022 receipt also resolve UNCERTAIN transactions per SAD-02 §5.4.
**Steps:** Create Zp0022Processor.processFile(fileBytes) that parses the fixed-width file; For each record look up refund by gme_refund_id; log and skip if not found (log as MISSING_REFUND_REF exception); If result_code=00: update status to REFUND_CONFIRMED, store scheme_refund_ref = gme_refund_id echo, write audit log event_type=refund.confirmed; If result_code != 00: update status to REFUND_FAILED, store error in reason, write audit log event_type=refund.failed, emit Ops alert; If registered_refund_amount != refund.refund_amount: flag as AMOUNT_MISMATCH exception record for Ops review; do not mark CONFIRMED; After all records, call RefundCompletionService to apply post-confirmation steps
**Deliverable:** Zp0022Processor class with processFile method and associated audit logging
**Acceptance / logic checks:**
- Record with result_code=00 transitions refund to REFUND_CONFIRMED and writes audit log
- Record with result_code=01 (example failure) transitions refund to REFUND_FAILED and triggers Ops alert
- registered_refund_amount=9000 vs refund.refund_amount=10000 KRW creates an AMOUNT_MISMATCH exception record and does NOT mark the refund CONFIRMED
- A gme_refund_id in ZP0022 that does not exist in the refund table is logged as MISSING_REFUND_REF exception and processing continues (no crash)
- Processing is idempotent: re-processing the same ZP0022 file does not create duplicate audit log entries or double-apply prefunding reversals
**Depends on:** 10.8-T03, 10.8-T07

### 10.8-T09 — Implement RefundCompletionService: apply prefunding reversal on REFUND_CONFIRMED (OVERSEAS only)  _(50 min)_
**Context:** PRD-07 §10.3.2: for OVERSEAS partners, the prefunding balance is reversed when ZeroPay confirms the refund via ZP0022 (not at initiation). Specifically: (1) insert prefunding_ledger_entry type=REFUND_REVERSAL with amount=+refund_amount_in_usd (convert from KRW using the rate locked on the original transaction: locked cost_rate_pay = treasury.usd_krw at commit time), (2) update prefunding_account.balance atomically via SELECT FOR UPDATE, (3) update refund.status from REFUND_CONFIRMED to REFUNDED, (4) write audit log event_type=refund.completed. For LOCAL partners (GME Remit) skip steps 1-2, still update to REFUNDED. Conversion: reversal_usd = refund_amount_krw / locked_cost_rate_pay (cost_rate_pay from the original transaction's locked rate).
**Steps:** Create RefundCompletionService.applyCompletion(refundId) called by Zp0022Processor on each confirmed refund; Load refund and original transaction; check partner type; If OVERSEAS: compute reversal_usd = refund_amount / transaction.cost_rate_pay (BigDecimal, scale 8); Insert prefunding_ledger_entry type=REFUND_REVERSAL inside SELECT FOR UPDATE on prefunding_account; Update refund.status = REFUNDED atomically in same transaction; Write audit log entry event_type=refund.completed with actor=SYSTEM
**Deliverable:** RefundCompletionService with applyCompletion method, @Transactional
**Acceptance / logic checks:**
- OVERSEAS refund of KRW 10000 with locked cost_rate_pay=1350.000000 produces reversal_usd = 10000/1350 = 7.407407 USD (stored to 8 decimal places) in prefunding ledger
- LOCAL (GME Remit) refund completion updates status to REFUNDED with no prefunding_ledger_entry created
- Concurrent calls to applyCompletion for the same refund_id: second call finds status already REFUNDED and exits without creating a duplicate ledger entry (idempotency via status check inside SELECT FOR UPDATE)
- prefunding_account.balance increases by reversal_usd after completion for OVERSEAS partner
- Audit log entry event_type=refund.completed is written with entity_id = refund.id
**Depends on:** 10.8-T03, 10.8-T08

### 10.8-T10 — Expose admin API endpoint POST /admin/v1/transactions/{id}/refund  _(45 min)_
**Context:** The refund initiation flow (10.8-T05) must be callable from the admin portal UI. Endpoint: POST /admin/v1/transactions/{id}/refund. Auth: requires OPS_OPERATOR or SUPER_ADMIN role (RBAC check per PRD-07 §12.3). Request body: {refund_amount: decimal, reason_code: enum[CUSTOMER_REQUEST|MERCHANT_ERROR|SETTLEMENT_ERROR], internal_notes: string min 20 chars}. Response 201: {refund_id, refund_ref, status: REFUND_PENDING, refund_amount, refund_ccy}. Error 409 if ALREADY_REFUNDED; 422 if amount exceeds original or ineligible status; 403 if caller lacks permission.
**Steps:** Create RefundController with POST /admin/v1/transactions/{id}/refund handler; Add request body validation: refund_amount > 0, reason_code in allowed set, internal_notes >= 20 chars; Enforce RBAC: reject with 403 if authenticated user role is not OPS_OPERATOR or SUPER_ADMIN; Call RefundInitiationService.initiateRefund; map exceptions to HTTP status codes; Return 201 with RefundResponse DTO on success; Add endpoint to OpenAPI spec
**Deliverable:** RefundController with POST /admin/v1/transactions/{id}/refund endpoint, RBAC-enforced
**Acceptance / logic checks:**
- POST with valid body and OPS_OPERATOR session returns 201 with status=REFUND_PENDING
- POST with FINANCE_ANALYST session returns 403
- POST with refund_amount > original target_payout returns 422 with error code REFUND_AMOUNT_EXCEEDED
- POST with internal_notes of 19 characters returns 422 with field validation error
- POST for a transaction that already has a REFUND_PENDING refund returns 409 with error code ALREADY_REFUNDED
**Depends on:** 10.8-T05

### 10.8-T11 — Expose admin API endpoint POST /admin/v1/transactions/{id}/cancel (portal cancel)  _(35 min)_
**Context:** CancellationService (10.8-T06) must be callable from the admin portal UI. Endpoint: POST /admin/v1/transactions/{id}/cancel. Auth: OPS_OPERATOR or SUPER_ADMIN only. No request body required (actor is the authenticated user). Response 200: {hub_txn_ref, status: CANCELLED, cancelled_at, cancelled_by}. Error 409 if status not cancelable; 422 if same-day window expired. This is the admin-portal cancel path; the partner API cancel (API-05 §4.6) is a separate endpoint/flow.
**Steps:** Create or extend TransactionAdminController with POST /admin/v1/transactions/{id}/cancel; Enforce RBAC: 403 if not OPS_OPERATOR or SUPER_ADMIN; Call CancellationService.cancelTransaction(txnId, actorUserId); Map CancellationNotAllowedException to 409; CancellationWindowExpiredException to 422; Return 200 with CancelResponse DTO on success; Add endpoint to OpenAPI spec
**Deliverable:** POST /admin/v1/transactions/{id}/cancel endpoint handler
**Acceptance / logic checks:**
- POST on a COMMITTED OVERSEAS transaction on the same KST day returns 200 and transaction status becomes CANCELLED
- POST on an APPROVED (post-scheme) transaction returns 409
- POST on a transaction from a prior KST date returns 422
- POST with ADMIN_VIEWER session returns 403
- Response body includes cancelled_by matching the authenticated user ID
**Depends on:** 10.8-T06

### 10.8-T12 — Expose admin API: GET /admin/v1/refunds — paginated refund list with filters  _(40 min)_
**Context:** PRD-07 §10.3.5 requires a Refund module list view. Endpoint: GET /admin/v1/refunds. Query params: status (optional, multi-value), partner_id (optional), date_from/date_to (optional, KST), page/size (default size=50). Response: paginated list of refunds with fields: refund_id, refund_ref, hub_txn_ref, partner_name, scheme_name, refund_amount, refund_ccy, reason_code, status, initiated_by, created_at. Auth: OPS_OPERATOR, FINANCE_ANALYST, SUPER_ADMIN, ADMIN_VIEWER (all roles can view per PRD-07 §12.3 View Refund Status).
**Steps:** Create GET /admin/v1/refunds handler in RefundController; Accept query params: status[], partner_id, date_from, date_to, page, size; Build JPA/SQL query with dynamic filters and pagination; Join to transaction, partner, scheme tables for display fields; Return paginated RefundListResponse with total_count; Enforce read access for all four roles; no write permission check needed for GET
**Deliverable:** GET /admin/v1/refunds endpoint with pagination and filtering
**Acceptance / logic checks:**
- GET with status=REFUND_PENDING returns only rows in that status
- GET with date_from=2026-06-01 and date_to=2026-06-05 returns only refunds created in that window (KST)
- ADMIN_VIEWER session can call GET and receives 200 (not 403)
- Response includes total_count matching the number of records in the filtered result set
- GET with page=2 and size=10 returns the correct second page of results
**Depends on:** 10.8-T03

### 10.8-T13 — Expose admin API: GET /admin/v1/refunds/{id} — refund detail view  _(35 min)_
**Context:** Ops needs to view individual refund details. Endpoint: GET /admin/v1/refunds/{id}. Returns full refund record plus linked transaction summary (hub_txn_ref, original target_payout, merchant_id, merchant_name, partner_name, scheme_name), and status history (all audit log entries for this refund_id). Auth: all four roles can read refund status (PRD-07 §12.3).
**Steps:** Add GET /admin/v1/refunds/{id} handler; Load refund row; 404 if not found; Load linked transaction summary fields; Query audit_log for entity_type=refund AND entity_id=id to build status history list; Return RefundDetailResponse including refund fields, transaction summary, and status_history array; Enforce minimum role = ADMIN_VIEWER
**Deliverable:** GET /admin/v1/refunds/{id} endpoint returning full detail and audit history
**Acceptance / logic checks:**
- Returns 404 for non-existent refund_id
- Response includes original transaction hub_txn_ref and target_payout amount
- status_history array contains at least one entry (the refund.initiated audit event)
- ADMIN_VIEWER role returns 200; unauthenticated call returns 401
- After a full refund lifecycle (REFUND_PENDING -> REFUND_SUBMITTED -> REFUND_CONFIRMED -> REFUNDED), status_history has 4 entries in chronological order
**Depends on:** 10.8-T10, 10.8-T12

### 10.8-T14 — Expose admin API: GET /admin/v1/refunds/batches — daily batch summary view  _(40 min)_
**Context:** PRD-07 §10.3.5 requires a batch sub-view showing refunds grouped by daily batch: batch_date, total_count, total_krw_amount, zp0021_transmission_status (PENDING|SENT|FAILED), zp0021_sent_at, zp0022_receipt_status (PENDING|RECEIVED|FAILED), zp0022_received_at, per_status_breakdown (count per status). Source: settlement_batch and settlement_file tables for ZP0021/ZP0022 entries, joined to refund rows. Auth: all four roles.
**Steps:** Add GET /admin/v1/refunds/batches handler with optional date_from/date_to filter; Query settlement_batch filtered to file_type IN ('ZP0021','ZP0022'); For each batch date, aggregate: total refund count, total KRW amount, status breakdown from refund table; Include ZP0021 file transmission status and ZP0022 receipt status from settlement_file rows; Return list of RefundBatchSummary sorted by batch_date DESC; Default to last 30 days if no date filter provided
**Deliverable:** GET /admin/v1/refunds/batches endpoint returning daily batch summaries
**Acceptance / logic checks:**
- Response for a date with 3 refunds shows total_count=3 and correct total_krw_amount
- zp0021_transmission_status=SENT only when a settlement_file row with file_type=ZP0021 and status=SENT exists for that batch_date
- zp0022_receipt_status=RECEIVED only when a settlement_file row with file_type=ZP0022 and status=RECEIVED exists
- per_status_breakdown shows correct counts, e.g. {REFUNDED: 2, REFUND_FAILED: 1} for a batch with those outcomes
- Date with no refunds does not appear in the result list
**Depends on:** 10.8-T07, 10.8-T08

### 10.8-T15 — Admin UI: Refund Initiation form on transaction detail page  _(55 min)_
**Context:** PRD-07 §10.3.2 and UX-11: the transaction detail page must show an Initiate Refund button when the transaction is eligible (status APPROVED or SCHEME_APPROVED, within refund window, not already refunded, active merchant). Clicking the button opens a modal/form with fields: Refund Amount (decimal, payout ccy, pre-filled with original target_payout, editable down to 1 KRW), Reason Code (select: CUSTOMER_REQUEST | MERCHANT_ERROR | SETTLEMENT_ERROR), Internal Notes (textarea, min 20 chars). Two-step confirm per UX-11 (danger primary button labelled 'Refund transaction {hub_txn_ref}?'). On success show toast; on error show inline error message.
**Steps:** Add Initiate Refund button to transaction detail page; visible only if GET /admin/v1/transactions/{id} returns is_refundable=true; Build refund modal component with Refund Amount, Reason Code, Internal Notes fields; Validate client-side: amount > 0 and <= original payout, notes >= 20 chars, reason code selected; Show two-step confirmation dialog with danger-styled primary button naming the hub_txn_ref; On confirm call POST /admin/v1/transactions/{id}/refund; show success toast or inline error; After success, refresh transaction status to reflect refund link
**Deliverable:** Refund initiation modal component wired to the transaction detail page
**Acceptance / logic checks:**
- Initiate Refund button is absent when transaction.status=FAILED
- Attempting to submit with refund_amount=0 shows inline validation error before API call
- Attempting to submit with internal_notes='short note' (< 20 chars) shows inline validation error
- Confirmation dialog heading reads 'Refund transaction {hub_txn_ref}?' with danger (red) primary button
- After successful submission, transaction detail page shows a link to the new refund record in REFUND_PENDING status
**Depends on:** 10.8-T10

### 10.8-T16 — Admin UI: same-day Cancel button on transaction detail page  _(45 min)_
**Context:** PRD-07 §10.2: a Cancel button appears on the transaction detail page when status is COMMITTED or SUBMITTED_TO_SCHEME and the transaction was created today (KST). The button must be absent for post-settlement states. Two-step confirm dialog required (UX-11 danger pattern). On success transition displayed status to CANCELLED. For OVERSEAS partners the cancel result must confirm prefunding was reversed (show balance-after in success message).
**Steps:** Add Cancel button to transaction detail page; show only when status in [COMMITTED, SUBMITTED_TO_SCHEME] and is_same_day=true from API; Build two-step cancel confirmation modal with danger-styled button labelled 'Cancel transaction {hub_txn_ref}?'; On confirm call POST /admin/v1/transactions/{id}/cancel; On success: update displayed status to CANCELLED; if partner is OVERSEAS display 'Prefunding balance restored' message; On error 409 or 422: show descriptive error (e.g. 'Transaction cannot be cancelled after settlement'); Hide Cancel button after successful cancellation; show Cancelled badge instead
**Deliverable:** Cancel button component on transaction detail page, wired to cancel endpoint
**Acceptance / logic checks:**
- Cancel button visible for COMMITTED transaction created today; absent for APPROVED transaction
- Cancel button absent for transaction created on a prior KST date even if < 24h ago
- Confirmation modal heading includes hub_txn_ref
- After successful cancel, status badge shows CANCELLED and Cancel button is removed
- FINANCE_ANALYST user sees no Cancel button (enforced client-side via role check; server also returns 403)
**Depends on:** 10.8-T11

### 10.8-T17 — Admin UI: Refunds module list page with status filters and batch sub-view  _(55 min)_
**Context:** PRD-07 §3.1 defines a top-level Refunds navigation module. The main tab shows a paginated list (50/page) using GET /admin/v1/refunds. Columns (max 6 per UX-11): Refund Ref, Transaction Ref, Partner, Refund Amount (KRW), Status (badge), Initiated At. Filter bar: Status (multi-select), Partner (select), Date range. A secondary tab labelled 'Batches' shows the daily batch summary from GET /admin/v1/refunds/batches. All four roles can access this module (PRD-07 §12.3 View Refund Status).
**Steps:** Create Refunds module page with two tabs: Refunds list and Batches; Implement Refunds list tab with filter bar and paginated table (columns: Refund Ref, Transaction Ref, Partner, Amount, Status, Initiated At); Status column shows colour-coded badge (REFUND_PENDING=amber, REFUNDED=green, REFUND_FAILED=red); Implement Batches tab with table (columns: Batch Date, Count, Total KRW, ZP0021 Status, ZP0022 Status, Breakdown); Clicking a refund row navigates to GET /admin/v1/refunds/{id} detail page; Add Refunds link to persistent left sidebar navigation
**Deliverable:** Refunds module page with list and batch tabs in the admin portal UI
**Acceptance / logic checks:**
- Refunds list shows amber badge for REFUND_PENDING, green for REFUNDED, red for REFUND_FAILED
- Filtering by status=REFUNDED shows only completed refunds
- Batches tab shows one row per business day with ZP0021 and ZP0022 statuses
- Table has no more than 6 columns (per UX-11 convention)
- ADMIN_VIEWER user can navigate to the Refunds module and view the list (no 403)
**Depends on:** 10.8-T12, 10.8-T14

### 10.8-T18 — Admin UI: Refund detail page  _(45 min)_
**Context:** Clicking a refund row in the Refunds module opens a detail page backed by GET /admin/v1/refunds/{id}. Fields to display: Refund Ref, status badge, Refund Amount + currency, Reason Code, Internal Notes, Initiated By, Initiated At, Linked Transaction (hub_txn_ref as link), Merchant Name, Partner Name, Scheme Refund Ref (when available from ZP0022), Status History timeline. For FAILED refunds show error message and a Retry Refund button (which calls POST /admin/v1/transactions/{txn_id}/refund again with same parameters, only for OPS_OPERATOR/SUPER_ADMIN).
**Steps:** Create RefundDetailPage component fetching from GET /admin/v1/refunds/{id}; Display all fields listed in context with read-only layout; Render Status History as a chronological timeline (one entry per audit log event for this refund); Show Retry Refund button only when status=REFUND_FAILED and user role is OPS_OPERATOR or SUPER_ADMIN; Wire Retry Refund button to POST /admin/v1/transactions/{txn_id}/refund with original parameters; Show scheme_refund_ref field when status=REFUND_CONFIRMED or REFUNDED
**Deliverable:** Refund detail page component rendered from GET /admin/v1/refunds/{id}
**Acceptance / logic checks:**
- Status History timeline shows events in ascending time order
- scheme_refund_ref is displayed when status=REFUNDED but absent/empty when status=REFUND_PENDING
- Retry Refund button is visible for REFUND_FAILED status with OPS_OPERATOR role; absent for ADMIN_VIEWER
- Linked Transaction hub_txn_ref is a clickable link to the transaction detail page
- Internal Notes field (min 20 chars) is fully displayed without truncation
**Depends on:** 10.8-T13

### 10.8-T19 — Add is_refundable and is_cancelable flags to transaction detail API response  _(35 min)_
**Context:** The UI needs to know whether to show the Refund and Cancel buttons without duplicating eligibility logic client-side. Extend the GET /admin/v1/transactions/{id} response DTO to include: is_refundable (boolean): true iff RefundEligibilityService.checkEligibility returns ELIGIBLE; is_cancelable (boolean): true iff status in [COMMITTED, SUBMITTED_TO_SCHEME] and created_at_KST.date == today_KST.date. These flags are computed server-side on every detail page load (no caching).
**Steps:** Extend TransactionDetailResponse DTO with is_refundable and is_cancelable boolean fields; In TransactionAdminController.getTransactionDetail, call RefundEligibilityService and evaluate cancellation window; Set is_refundable=true only when eligibility result is ELIGIBLE; Set is_cancelable=true only when status in [COMMITTED, SUBMITTED_TO_SCHEME] and same KST date; Add unit test cases covering each flag value combination; Update OpenAPI spec for the response schema
**Deliverable:** TransactionDetailResponse DTO with is_refundable and is_cancelable fields, populated server-side
**Acceptance / logic checks:**
- APPROVED transaction within 90-day window with no existing refund and active merchant returns is_refundable=true
- FAILED transaction returns is_refundable=false
- COMMITTED transaction created today returns is_cancelable=true; same transaction tomorrow returns is_cancelable=false
- Transaction with status=APPROVED (post-scheme settlement) returns is_cancelable=false
- Response is consistent with what RefundEligibilityService.checkEligibility returns (no logic duplication)
**Depends on:** 10.8-T04, 10.8-T06

### 10.8-T20 — Cron job: schedule ZP0021 batch generation at 02:00 KST daily  _(45 min)_
**Context:** SCH-06 §6.2: ZP0021 must be transmitted to ZeroPay by 02:00 KST. A scheduled cron job must trigger Zp0021BatchGenerator.generateFile(previousBusinessDay) at 01:50 KST (10 minutes before deadline) and then SFTP-upload the file via the ZeroPay scheme adapter. The job must be idempotent: if re-run for the same settlement_date, it must detect the existing settlement_batch row and skip re-generation. If no REFUND_PENDING refunds exist, the job must still generate a file (empty body with header/trailer) and transmit it per ZeroPay requirements.
**Steps:** Create Zp0021BatchJob @Scheduled(cron='50 1 * * *', zone='Asia/Seoul'); Check for existing settlement_batch row with file_type=ZP0021 and settlement_date=previousBusinessDay; skip if already SENT; Call Zp0021BatchGenerator.generateFile(previousBusinessDay); Upload file via SchemeAdapter.sftpUpload(filePath, scheme=ZEROPAY); Update settlement_batch/settlement_file status to SENT or FAILED; Log and alert Ops on any exception (P2 alert)
**Deliverable:** Zp0021BatchJob scheduled component with idempotency guard
**Acceptance / logic checks:**
- Running the job twice for the same settlement_date: second run detects existing settlement_batch with status=SENT and exits without generating a second file
- Running the job when zero REFUND_PENDING refunds exist generates a valid file with header and trailer only (0 detail records) and transmits it
- Job fires at 01:50 KST (verify via integration test with mocked clock)
- SFTP upload failure sets settlement_batch.status=FAILED and emits an ops alert; job does not mark refunds as REFUND_SUBMITTED if upload fails
- After a successful run, all refunds included in the file have status=REFUND_SUBMITTED
**Depends on:** 10.8-T07

### 10.8-T21 — Cron job: schedule ZP0022 result polling and processing at 05:00 KST daily  _(45 min)_
**Context:** SCH-06 §6.3: ZeroPay delivers ZP0022 by ~05:00 KST. A scheduled job polls the SFTP inbound directory for a ZP0022 file for previousBusinessDay at 05:10 KST. If found, call Zp0022Processor.processFile. If not found by 05:30 KST, raise a P2 alert. Job must be idempotent: if ZP0022 was already processed for that date (settlement_file row exists with status=PROCESSED), skip. Mark settlement_file status=PROCESSED on success or FAILED on error.
**Steps:** Create Zp0022PollingJob @Scheduled(cron='10 5 * * *', zone='Asia/Seoul'); Check settlement_file for file_type=ZP0022 and settlement_date; skip if already PROCESSED; Poll SFTP inbound path for a ZP0022 file matching the date pattern; If file found: call Zp0022Processor.processFile, update settlement_file status=PROCESSED; If file not found after 20-minute wait (retry twice at 5-minute intervals): log P2 alert and set status=MISSING; Add retry job at 05:30 KST if initial run finds no file
**Deliverable:** Zp0022PollingJob scheduled component with retry and idempotency logic
**Acceptance / logic checks:**
- If ZP0022 file found at 05:10: all refunds processed, settlement_file.status=PROCESSED, no second processing on retry
- If file not found by 05:30: settlement_file.status=MISSING and an ops alert is emitted
- Re-running after status=PROCESSED exits without reprocessing (idempotency)
- Processing 3 refunds (2 success, 1 fail): 2 records reach REFUNDED/REFUND_CONFIRMED, 1 reaches REFUND_FAILED
- Any exception during processFile sets settlement_file.status=FAILED (not PROCESSED) and emits ops alert
**Depends on:** 10.8-T08

### 10.8-T22 — Unit tests: RefundEligibilityService — all eligibility branches  _(45 min)_
**Context:** 10.8-T04 implements RefundEligibilityService. This ticket adds exhaustive unit tests covering all 5 eligibility branches using JUnit 5 + Mockito. Exact test vectors must cover boundary conditions for the refund window calculation. Reference data: refund_window_days=90 (ZeroPay default). KST timezone: ZoneId.of('Asia/Seoul').
**Steps:** Create RefundEligibilityServiceTest class; Test NOT_ELIGIBLE_STATUS: transaction.status=FAILED returns NOT_ELIGIBLE_STATUS; Test NOT_ELIGIBLE_WINDOW boundary: transaction created exactly 90 days ago (same time) → ELIGIBLE; 90 days + 1 second ago → NOT_ELIGIBLE_WINDOW; Test ALREADY_REFUNDED: existing refund with status=REFUND_PENDING → ALREADY_REFUNDED; existing refund with status=REFUND_FAILED only → ELIGIBLE; Test MERCHANT_INACTIVE: merchant.is_active=false → MERCHANT_INACTIVE; Test ELIGIBLE: all conditions pass → ELIGIBLE
**Deliverable:** RefundEligibilityServiceTest with ≥ 8 test methods covering all branches and boundary cases
**Acceptance / logic checks:**
- All 8+ test methods pass in CI
- Boundary: created_at = now minus exactly 90 days → ELIGIBLE (not yet expired at the boundary)
- Boundary: created_at = now minus 90 days and 1 second → NOT_ELIGIBLE_WINDOW
- Test for ALREADY_REFUNDED with REFUND_FAILED existing refund returns ELIGIBLE (retry allowed)
- 100% branch coverage on checkEligibility method (verified by JaCoCo or equivalent)
**Depends on:** 10.8-T04

### 10.8-T23 — Unit tests: RefundInitiationService — amount validation and duplicate guard  _(40 min)_
**Context:** 10.8-T05 implements RefundInitiationService. Unit tests must cover: (a) amount validation (exact boundary: refund_amount == target_payout passes; refund_amount = target_payout + 0.0001 fails for KRW since KRW has 0 decimal precision), (b) duplicate guard (ALREADY_REFUNDED), (c) partial refund (refund_amount = 5000 on a 10000 KRW transaction), (d) audit log entry written on success, (e) transaction status NOT updated to REFUND_PENDING.
**Steps:** Create RefundInitiationServiceTest with mocked RefundEligibilityService and RefundRepository; Test full-amount refund: amount = 10000 KRW on 10000 KRW txn → success, refund.status=REFUND_PENDING; Test partial refund: amount = 5000 KRW on 10000 KRW txn → success; Test amount exceeded: amount = 10001 KRW on 10000 KRW txn → RefundAmountExceededException; Test duplicate call: second initiateRefund on same txnId with existing REFUND_PENDING → exception; Test audit log: verify AuditLogService.log called with event_type=refund.initiated and correct entity fields; Test ineligible transaction: eligibility returns NOT_ELIGIBLE_STATUS → RefundNotEligibleException
**Deliverable:** RefundInitiationServiceTest with ≥ 7 test methods
**Acceptance / logic checks:**
- All 7+ test methods pass
- Amount = 10001 KRW on 10000 KRW transaction throws RefundAmountExceededException (not passes)
- Transaction status is NOT changed by initiateRefund (verified by asserting no updateStatus call on transaction)
- AuditLogService.log is called exactly once per successful initiation
- refund_ref generated by service matches expected format 'RFD-{hub_txn_ref}-{millis}'
**Depends on:** 10.8-T05

### 10.8-T24 — Unit tests: Zp0021BatchGenerator — file layout and edge cases  _(45 min)_
**Context:** 10.8-T07 implements Zp0021BatchGenerator. Tests must verify fixed-width field formatting, empty-batch handling, and status transition. Key format rule: refund_amount_krw is NUM(12) right-justified zero-padded. Reason code mapping: CUSTOMER_REQUEST→'CR', MERCHANT_ERROR→'ME', SETTLEMENT_ERROR→'SE' (use scheme-defined codes; confirm mapping in implementation; use those codes in tests). Partner type: D for Domestic (LOCAL), I for International (OVERSEAS).
**Steps:** Create Zp0021BatchGeneratorTest; Test single-record file: KRW 10000 refund produces refund_amount_krw='000000010000' (12 chars); Test record partner_type: LOCAL partner → 'D'; OVERSEAS → 'I'; Test empty batch: 0 REFUND_PENDING records → file with only header and trailer, record count = 0; Test status transition: after generateFile, all included refunds have status=REFUND_SUBMITTED; Test idempotency guard: calling generateFile twice for same date returns existing file without creating second settlement_batch row
**Deliverable:** Zp0021BatchGeneratorTest with ≥ 6 test methods validating file format and state transitions
**Acceptance / logic checks:**
- refund_amount_krw for KRW 1 is '000000000001' (12 chars, right-justified zero-padded)
- refund_amount_krw for KRW 999999999999 is '999999999999' (maximum 12-digit value fits)
- Empty-batch file has exactly 2 lines (header + trailer) with trailer record_count=0
- After generateFile, refund rows included in file have status=REFUND_SUBMITTED; a refund created after batch cutoff remains REFUND_PENDING
- File header contains file_type='ZP0021'
**Depends on:** 10.8-T07

### 10.8-T25 — Unit tests: Zp0022Processor — result code handling and idempotency  _(45 min)_
**Context:** 10.8-T08 implements Zp0022Processor. Tests must verify: success path (result_code=00), failure path (result_code != 00), amount mismatch exception, missing refund ref handling, and idempotency (reprocessing same file does not double-apply). Use representative test data matching ZP0022 field layout.
**Steps:** Create Zp0022ProcessorTest; Test success: record with result_code=00 → refund.status=REFUND_CONFIRMED, scheme_refund_ref populated, audit log written; Test failure: result_code=01 → refund.status=REFUND_FAILED, Ops alert emitted; Test amount mismatch: registered_refund_amount=9000 vs refund.refund_amount=10000 → AMOUNT_MISMATCH exception record created, refund stays REFUND_SUBMITTED; Test missing ref: gme_refund_id not found in DB → MISSING_REFUND_REF logged, processing continues without exception; Test idempotency: reprocessing file with already-REFUNDED refund → no second audit log entry or prefunding reversal
**Deliverable:** Zp0022ProcessorTest with ≥ 6 test methods
**Acceptance / logic checks:**
- result_code=00 transitions refund to REFUND_CONFIRMED in test
- result_code=01 transitions refund to REFUND_FAILED in test
- Amount mismatch does NOT set status to REFUND_CONFIRMED
- Idempotency test: processFile called twice with same file does not create 2 audit log entries for the same refund
- Missing gme_refund_id does not throw uncaught exception; test completes with MISSING_REFUND_REF log entry
**Depends on:** 10.8-T08

### 10.8-T26 — Unit tests: RefundCompletionService — prefunding reversal math for OVERSEAS  _(40 min)_
**Context:** 10.8-T09 implements RefundCompletionService. Test the USD reversal computation: reversal_usd = refund_amount_krw / locked cost_rate_pay (from transaction). Representative vector: KRW 10000 refund, locked cost_rate_pay = 1350.000000 (treasury.usd_krw at commit time), expected reversal_usd = 10000 / 1350.000000 = 7.40740740... USD stored to 8 decimal places (7.40740741 after rounding). Also test LOCAL partner skip, idempotency, and concurrent call protection.
**Steps:** Create RefundCompletionServiceTest; Test OVERSEAS: KRW 10000 / cost_rate_pay 1350.0 = reversal_usd 7.40740741 (BigDecimal HALF_UP 8 dp); Test OVERSEAS: KRW 5000 / cost_rate_pay 1380.0 = 3.62318841 USD (verify to 8 dp); Test LOCAL: applyCompletion for LOCAL partner creates no prefunding_ledger_entry, sets status=REFUNDED; Test idempotency: calling applyCompletion on already-REFUNDED record exits without creating duplicate ledger entry; Test concurrent lock: mock SELECT FOR UPDATE contention → second call waits or retries, final balance correct
**Deliverable:** RefundCompletionServiceTest with ≥ 5 test methods covering math and edge cases
**Acceptance / logic checks:**
- KRW 10000 / 1350.0 yields BigDecimal 7.40740741 (8 decimal places, HALF_UP rounding)
- KRW 5000 / 1380.0 yields BigDecimal 3.62318841
- LOCAL partner test has zero calls to prefunding ledger repository
- Idempotency: second call to applyCompletion returns without error and ledger has exactly 1 REFUND_REVERSAL entry
- Status transitions from REFUND_CONFIRMED to REFUNDED in both OVERSEAS and LOCAL cases
**Depends on:** 10.8-T09

### 10.8-T27 — Integration test: full refund lifecycle E2E (initiate -> ZP0021 -> ZP0022 -> complete)  _(55 min)_
**Context:** End-to-end test covering the complete Phase 1 refund path: (1) create an APPROVED OVERSEAS transaction with locked cost_rate_pay=1350, target_payout=KRW 10000; (2) call POST /admin/v1/transactions/{id}/refund with amount=KRW 10000, reason=CUSTOMER_REQUEST, notes='Test refund for customer request integration'; (3) run Zp0021BatchGenerator for that date and verify file contains the refund; (4) feed a synthetic ZP0022 success response with result_code=00; (5) call Zp0022Processor; (6) verify final state: refund.status=REFUNDED, prefunding_ledger_entry REFUND_REVERSAL = 7.40740741 USD, transaction.status unchanged (still APPROVED).
**Steps:** Create RefundLifecycleIntegrationTest using @SpringBootTest with test DB; Insert a test APPROVED OVERSEAS (SendMN) transaction with target_payout=10000 KRW, locked cost_rate_pay=1350.000000; Call POST /admin/v1/transactions/{id}/refund via MockMvc; assert 201 and refund.status=REFUND_PENDING; Run Zp0021BatchGenerator; assert file generated, refund.status=REFUND_SUBMITTED; Synthesize ZP0022 success record; call Zp0022Processor.processFile; Assert refund.status=REFUNDED, prefunding_ledger_entry.amount=7.40740741, transaction.status=APPROVED (unchanged)
**Deliverable:** RefundLifecycleIntegrationTest with full lifecycle covered in a single test scenario
**Acceptance / logic checks:**
- All 6 lifecycle steps pass in sequence with no intermediate failures
- Final refund.status=REFUNDED
- Final prefunding_ledger_entry.amount=7.40740741 USD (8 decimal places)
- transaction.status remains APPROVED throughout (not modified by refund flow)
- Audit log contains 3 entries for this refund_id: refund.initiated, refund.confirmed, refund.completed
**Depends on:** 10.8-T05, 10.8-T07, 10.8-T08, 10.8-T09

### 10.8-T28 — Integration test: same-day cancel lifecycle E2E (OVERSEAS, prefunding reversal)  _(50 min)_
**Context:** Test portal cancel for an OVERSEAS partner (SendMN): (1) insert COMMITTED transaction with prefunding deduction of collection_amount=2.00 USD, (2) call POST /admin/v1/transactions/{id}/cancel (same KST day), (3) assert transaction.status=CANCELLED, (4) assert prefunding_ledger_entry REFUND_REVERSAL with amount=+2.00 USD, (5) assert prefunding_account.balance increased by 2.00 USD. Also test that cancel is rejected if called the following KST day (transaction.created_at set to yesterday KST).
**Steps:** Create CancellationIntegrationTest using @SpringBootTest with test DB; Insert COMMITTED OVERSEAS transaction with prefunding_deduction=2.00 USD and today KST date; Call POST /admin/v1/transactions/{id}/cancel; assert 200 and status=CANCELLED; Assert prefunding_ledger_entry type=REFUND_REVERSAL amount=2.00 USD created; Assert prefunding_account.balance increased by 2.00 USD; Repeat with created_at set to yesterday KST; assert 422 response
**Deliverable:** CancellationIntegrationTest covering same-day success and prior-day rejection
**Acceptance / logic checks:**
- Same-day cancel returns 200, transaction.status=CANCELLED, prefunding_ledger_entry.amount=+2.00 USD
- Prior-day cancel returns 422
- LOCAL partner same-day cancel returns 200 with no prefunding_ledger_entry
- Audit log contains event_type=transaction.cancelled with actor_user_id matching test user
- Calling cancel a second time on an already-CANCELLED transaction returns 409
**Depends on:** 10.8-T06, 10.8-T11

### 10.8-T29 — RBAC enforcement tests: verify permission matrix for refund and cancel actions  _(35 min)_
**Context:** PRD-07 §12.3: Initiate Refund and admin Cancel require OPS_OPERATOR or SUPER_ADMIN. FINANCE_ANALYST and ADMIN_VIEWER must receive 403. View Refund Status (GET /admin/v1/refunds, GET /admin/v1/refunds/{id}, GET /admin/v1/refunds/batches) must be accessible to all four roles. Tests should use Spring Security test utilities with role mocking.
**Steps:** Create RefundRbacTest using @SpringBootTest + @WithMockUser; Test POST /admin/v1/transactions/{id}/refund with FINANCE_ANALYST → 403; Test POST /admin/v1/transactions/{id}/refund with ADMIN_VIEWER → 403; Test POST /admin/v1/transactions/{id}/cancel with FINANCE_ANALYST → 403; Test GET /admin/v1/refunds with ADMIN_VIEWER → 200; Test GET /admin/v1/refunds/{id} with ADMIN_VIEWER → 200; Test GET /admin/v1/refunds/batches with FINANCE_ANALYST → 200
**Deliverable:** RefundRbacTest class with ≥ 7 role/endpoint combinations verified
**Acceptance / logic checks:**
- FINANCE_ANALYST POST /refund returns 403
- ADMIN_VIEWER POST /cancel returns 403
- ADMIN_VIEWER GET /refunds returns 200 (not 403)
- SUPER_ADMIN POST /refund returns 201 (not 403)
- Unauthenticated POST /refund returns 401 (not 403)
**Depends on:** 10.8-T10, 10.8-T11, 10.8-T12, 10.8-T13, 10.8-T14

### 10.8-T30 — Ops alert: notify Ops when ZP0022 refund registration fails or file missing  _(40 min)_
**Context:** PRD-07 §10.3.4 and SCH-06 §6.4: when ZP0022 returns a failed result_code for a refund, an Ops alert must be sent. Also, if ZP0022 file is not received by 05:30 KST, a P2 alert fires. Use the existing notification service (email to Ops alert recipients or portal in-app notification). Alert content must include: refund_ref, hub_txn_ref, failure reason, current timestamp. An AMOUNT_MISMATCH exception must also trigger an alert and create an exception queue record visible in the Admin portal.
**Steps:** Extend Zp0022Processor to call NotificationService.sendOpsAlert on REFUND_FAILED outcome; Alert body must include: refund_ref, hub_txn_ref, result_code, result_message, current KST timestamp; For AMOUNT_MISMATCH: insert an exception_record row and call NotificationService.sendOpsAlert with discrepancy details; Extend Zp0022PollingJob to call NotificationService.sendOpsAlert when status=MISSING (file not received by 05:30 KST); Write unit test: mock Zp0022Processor with REFUND_FAILED outcome; assert NotificationService.sendOpsAlert called with correct arguments
**Deliverable:** Ops alert wiring in Zp0022Processor and Zp0022PollingJob, with unit test
**Acceptance / logic checks:**
- REFUND_FAILED outcome triggers NotificationService.sendOpsAlert exactly once with refund_ref and hub_txn_ref in message
- AMOUNT_MISMATCH outcome creates an exception_record row with discrepancy_amount = |registered - submitted| and triggers an alert
- ZP0022 file not received by 05:30 KST sets settlement_file.status=MISSING and triggers a P2 ops alert
- Successful refund (result_code=00) does NOT trigger any alert
- Alert for missing file includes the expected_arrival_time=05:00 KST in the message body
**Depends on:** 10.8-T08, 10.8-T21

### 10.8-T31 — Admin portal docs: developer README for refund/cancellation module  _(35 min)_
**Context:** A developer joining the team needs to understand how to run the refund batch locally, simulate ZP0022 responses in dev/sandbox, and understand the state machine transitions. Document: (1) how to manually trigger Zp0021BatchJob in local dev, (2) how to place a synthetic ZP0022 file in the SFTP mock directory to trigger Zp0022PollingJob, (3) the full refund state machine diagram (REFUND_PENDING->REFUND_SUBMITTED->REFUND_CONFIRMED->REFUNDED and REFUND_FAILED branch), (4) RBAC summary table for refund/cancel actions, (5) how partial refunds work (amount < original). Place in docs/admin-portal/refund-cancellation.md.
**Steps:** Create docs/admin-portal/refund-cancellation.md; Section 1: Refund state machine (ASCII diagram showing all 5 states and transitions); Section 2: Cancellation flow (same-day window rule, KST timezone note, prefunding reversal for OVERSEAS); Section 3: Running batches locally (curl/CLI commands to trigger Zp0021BatchJob and place ZP0022 mock file); Section 4: RBAC table for refund/cancel/view actions per role; Section 5: Partial refund rules and examples
**Deliverable:** docs/admin-portal/refund-cancellation.md covering state machine, batch ops, RBAC, and partial refunds
**Acceptance / logic checks:**
- State machine diagram shows all 5 refund statuses and valid transitions
- Cancellation window rule is documented: same KST calendar date as creation, not rolling 24h
- Local dev instructions include the exact command to trigger Zp0021BatchJob outside of cron
- RBAC table lists all 4 roles with Y/N for Initiate Refund, Cancel, View Refund Status
- Partial refund example: original KRW 10000, partial refund KRW 3000, partial refund succeeds
**Depends on:** 10.8-T09, 10.8-T16, 10.8-T17


## WBS 10.9 — Settlement & revenue dashboards
### 10.9-T01 — Define SettlementBatchDTO and RevenueReportDTO interface contracts  _(30 min)_
**Context:** WBS 10.9 builds the Settlement & Revenue UI module (PRD-07 §11). The backend must expose two primary read models: (1) SettlementBatchDTO: settlement_date (DATE/KST), scheme_id, file_type (ZP0011/ZP0021/ZP0061/ZP0062/ZP0063/ZP0064/ZP0065/ZP0066), direction (GME_TO_ZP|ZP_TO_GME), status (PENDING/GENERATED/TRANSMITTED/RECEIVED/RECONCILED/ERROR), transaction_count, total_amount, total_amount_ccy, transmitted_at, received_at, reconciled_at, error_detail. (2) RevenueReportRowDTO: period, partner_id, partner_name, scheme_id, transaction_count, gross_payout_krw, merchant_fee_total_krw, gme_scheme_share_krw (70% of net merchant fee), collection_margin_usd, payout_margin_usd, total_fx_margin_usd, service_charges_settle_a_ccy, service_charge_ccy, total_revenue_krw_equiv. Both DTOs must be documented as Java interfaces/records in the admin-service module.
**Steps:** Create SettlementBatchDTO as a Java record with all fields listed in context; use BigDecimal for monetary amounts, LocalDate for settlement_date, OffsetDateTime for timestamps; Create RevenueReportRowDTO as a Java record with all fields listed in context; total_revenue_krw_equiv is derived (scheme_share + fx_margin_converted + service_charge_converted); Create RevenueReportSummaryDTO containing List<RevenueReportRowDTO> rows and summary totals: total_gme_scheme_share_krw, total_fx_margin_usd, total_service_charges, grand_total_revenue_krw_equiv; Create SettlementBatchListDTO as a paged wrapper (content, totalElements, page, size); Add Javadoc to each DTO field referencing the source column in settlement_batch or revenue_record tables
**Deliverable:** Java records SettlementBatchDTO, RevenueReportRowDTO, RevenueReportSummaryDTO, SettlementBatchListDTO in admin-service/src/main/java/.../dto/settlement/ package
**Acceptance / logic checks:**
- SettlementBatchDTO compiles with all 14 required fields; no field omitted
- RevenueReportRowDTO contains exactly collection_margin_usd and payout_margin_usd as separate BigDecimal fields (not combined) and a separate total_fx_margin_usd field
- total_revenue_krw_equiv field is present in RevenueReportRowDTO and its Javadoc notes it is derived from scheme_share + converted FX margin + converted service charge
- RevenueReportSummaryDTO contains a List<RevenueReportRowDTO> and three BigDecimal summary totals
- All DTO classes are immutable records (no setters)

### 10.9-T02 — Define RevenueReportFilterDTO and BatchStatusFilterDTO query parameter objects  _(25 min)_
**Context:** The Settlement & Revenue UI supports filters. Revenue report (PRD-07 §11.3.2): period type (DAILY/WEEKLY/MONTHLY/CUSTOM), date_from, date_to, partner_id (nullable), scheme_id (nullable), direction (nullable), revenue_stream (SCHEME_FEE|FX_MARGIN|ALL). Settlement batch filter (PRD-07 §11.2.2): settlement_date, scheme_id (nullable). These are validated query param POJOs used by AdminSettlementController.
**Steps:** Create RevenueReportFilterDTO with fields: periodType (enum DAILY/WEEKLY/MONTHLY/CUSTOM), dateFrom (LocalDate), dateTo (LocalDate), partnerId (Long nullable), schemeId (Long nullable), direction (enum INBOUND/OUTBOUND/DOMESTIC/HUB nullable), revenueStream (enum SCHEME_FEE/FX_MARGIN/ALL, default ALL); Add Bean Validation: @NotNull on periodType, dateFrom, dateTo; custom cross-field validator that dateTo >= dateFrom and date range <= 366 days; Create SettlementBatchFilterDTO with: settlementDate (LocalDate, @NotNull), schemeId (Long nullable); Add unit test: RevenueReportFilterDTOValidationTest verifies (a) missing dateFrom fails validation, (b) dateTo before dateFrom fails, (c) range of 400 days fails, (d) valid 30-day MONTHLY filter passes
**Deliverable:** RevenueReportFilterDTO, SettlementBatchFilterDTO POJOs with validation annotations; RevenueReportFilterDTOValidationTest
**Acceptance / logic checks:**
- Validator rejects dateTo=2026-05-01 with dateFrom=2026-06-01 (reversed range)
- Validator rejects date range of 400 days
- Validator accepts dateFrom=2026-05-01 dateTo=2026-05-31 with periodType=MONTHLY
- partnerId and schemeId are nullable and absence does not fail validation
- revenueStream defaults to ALL when not supplied
**Depends on:** 10.9-T01

### 10.9-T03 — Create AdminSettlementController REST endpoints skeleton  _(35 min)_
**Context:** The Admin System backend (admin-service) exposes a REST API consumed by the frontend. PRD-07 §11 requires two groups of endpoints: (A) Settlement batch status: GET /admin/settlement/batches?settlementDate=&schemeId= returning SettlementBatchListDTO. (B) Revenue reporting: GET /admin/revenue/report?[filter params] returning RevenueReportSummaryDTO; GET /admin/revenue/export/csv?[same params] returning a streaming CSV download. Access control: FINANCE_ANALYST, OPS_OPERATOR, SUPER_ADMIN roles may call GET; no write endpoints in this module.
**Steps:** Create AdminSettlementController in admin-service with @RestController @RequestMapping('/admin/settlement'); Add GET /batches endpoint accepting SettlementBatchFilterDTO as @ModelAttribute; return ResponseEntity<SettlementBatchListDTO>; stub with empty list; Create AdminRevenueController with @RequestMapping('/admin/revenue'); Add GET /report accepting RevenueReportFilterDTO; return ResponseEntity<RevenueReportSummaryDTO>; stub with empty summary; Add GET /export/csv accepting same filter; set Content-Type: text/csv, Content-Disposition: attachment; filename=revenue_report_{dateFrom}_{dateTo}.csv; stub returns empty CSV with header row; Annotate all endpoints with @PreAuthorize('hasAnyRole(SUPER_ADMIN,OPS_OPERATOR,FINANCE_ANALYST)')
**Deliverable:** AdminSettlementController and AdminRevenueController classes with three stubbed endpoints and role-based security annotations
**Acceptance / logic checks:**
- GET /admin/settlement/batches without authentication returns 401
- GET /admin/settlement/batches with ADMIN_VIEWER role returns 403
- GET /admin/settlement/batches with FINANCE_ANALYST role returns 200 (empty list)
- GET /admin/revenue/export/csv with valid filter returns Content-Type: text/csv header
- Endpoint GET /admin/revenue/report returns RevenueReportSummaryDTO shape (empty) with HTTP 200
**Depends on:** 10.9-T01, 10.9-T02

### 10.9-T04 — Implement SettlementBatchQueryService - fetch batch status by date and scheme  _(40 min)_
**Context:** The settlement_batch table (DAT-03 §7.1 / §10.5) stores one row per daily batch run per scheme per file type. Columns used: id, scheme_id, file_type (ZP0011/ZP0021/ZP0061/ZP0062/ZP0063/ZP0064/ZP0065/ZP0066), direction, settlement_date (KST DATE), window, status (PENDING/GENERATED/TRANSMITTED/RECEIVED/RECONCILED/ERROR), transaction_count, total_amount, total_amount_ccy, transmitted_at, received_at, reconciled_at, error_detail. The UI shows a row-per-date view with one column per file type status. The daily timeline is: ZP0011 ~02:00, ZP0012 ~05:00, ZP0061 ~05:00, ZP0062 ~10:00, ZP0063 ~14:00, ZP0064 ~19:00, ZP0065 ~22:00, ZP0066 ~22:00 (all KST).
**Steps:** Create SettlementBatchQueryService with method List<SettlementBatchDTO> getBatchesByDate(LocalDate settlementDate, Long schemeId); Implement JPQL query: SELECT b FROM SettlementBatch b WHERE b.settlementDate = :date AND (:schemeId IS NULL OR b.schemeId = :schemeId) ORDER BY b.fileType; Map JPA entity fields to SettlementBatchDTO; ensure BigDecimal is used for total_amount; preserve null transmitted_at/received_at/reconciled_at as null in DTO; Add a second method SettlementBatchSummaryDTO getBatchSummaryForDate(LocalDate date) that returns aggregate: total transaction_count, total gross payout KRW across all ZP006x batches for that date; Handle edge case: if no rows exist for a given date/scheme combination return empty list (not 404)
**Deliverable:** SettlementBatchQueryService class with two public methods; backed by SettlementBatchRepository JPA repository
**Acceptance / logic checks:**
- Given settlement_date=2026-06-01 with ZP0061 status=TRANSMITTED and ZP0062 status=RECEIVED, the returned list contains exactly two DTOs with correct statuses
- When schemeId=null the method returns batches across all schemes for the date
- When no rows exist for date=2026-06-01, method returns empty list without throwing
- total_amount in DTO for a batch row with total_amount=12345678.0 returns BigDecimal with value 12345678.00 (2dp after mapping)
- getBatchSummaryForDate correctly sums transaction_count across ZP0065 and ZP0066 file types only
**Depends on:** 10.9-T01

### 10.9-T05 — Implement ReconciliationQueryService - fetch and aggregate reconciliation items  _(45 min)_
**Context:** The reconciliation_item table (DAT-03 §7.3) has: id, batch_id (FK settlement_batch), txn_ref, scheme_ref, match_status (MATCHED/DISCREPANCY/MISSING_GME/MISSING_SCHEME), gme_amount, scheme_amount, discrepancy_amount, ccy, resolution_status (UNRESOLVED/RESOLVED/ESCALATED), resolved_by, resolved_at, resolution_note. The UI (PRD-07 §11.2.3) shows reconciliation_status per batch as Matched/Discrepancy/Pending, and allows drilldown to flagged items. An auto-flag occurs for: MISSING_IN_SCHEME (txn present in GME but absent from ZP0062/ZP0064), AMOUNT_MISMATCH (|gme_amount - scheme_amount| > 0), SCHEME_REJECTION (scheme explicitly rejected the txn).
**Steps:** Create ReconciliationQueryService with method ReconciliationSummaryDTO getReconciliationSummaryForBatch(Long batchId); ReconciliationSummaryDTO contains: batchId, totalItems, matchedCount, discrepancyCount, missingInSchemeCount, missingInGmeCount, unresolvedCount, overallStatus (MATCHED if discrepancyCount+missingCount=0, DISCREPANCY otherwise, PENDING if no items yet); Add method Page<ReconciliationItemDTO> getDiscrepancyItems(Long batchId, Pageable pageable) returning only non-MATCHED items; Add method void markResolved(Long itemId, String operatorId, String note) that sets resolution_status=RESOLVED, resolved_by, resolved_at=now(); audit-logs the action with entity_type='reconciliation_item'; Handle edge: batchId not found -> throw ReconciliationNotFoundException (maps to 404)
**Deliverable:** ReconciliationQueryService with four methods; ReconciliationSummaryDTO; ReconciliationItemDTO
**Acceptance / logic checks:**
- Given batch with 100 items: 95 MATCHED, 3 AMOUNT_MISMATCH, 2 MISSING_IN_SCHEME, overallStatus=DISCREPANCY
- Given batch with 100 items all MATCHED, overallStatus=MATCHED
- Given batch with 0 reconciliation_items, overallStatus=PENDING
- markResolved on already-RESOLVED item throws AlreadyResolvedException (not silently succeeds)
- getDiscrepancyItems with batchId that has only MATCHED items returns empty page
**Depends on:** 10.9-T01

### 10.9-T06 — Implement RevenueReportQueryService - aggregate revenue_record by filter  _(55 min)_
**Context:** The revenue_record table (DAT-03 §8.2) has: id, txn_id (FK transaction), partner_id, scheme_id, revenue_date (DATE), fx_margin_usd (=collection_margin_usd+payout_margin_usd as DECIMAL(20,4)), service_charge_amount (DECIMAL(20,4)), service_charge_ccy (CHAR(3)), fee_share_pct (DECIMAL(6,4), e.g. 0.7000 for 70%), estimated_fee_share_usd (DECIMAL(20,4)). The report groups rows by (period, partner, scheme) and aggregates. total_fx_margin_usd = SUM(fx_margin_usd). gme_scheme_share = SUM(estimated_fee_share_usd). service_charges in settle_a_ccy = SUM(service_charge_amount) grouped by service_charge_ccy. total_revenue_krw_equiv = (gme_scheme_share + total_fx_margin_usd) converted using current treasury.usd_krw rate, plus service_charge_amount if service_charge_ccy=KRW. Filtering: dateFrom/dateTo on revenue_date; partnerId optional; schemeId optional; direction via JOIN to transaction.direction; revenueStream: SCHEME_FEE hides fx_margin columns, FX_MARGIN hides scheme_share column.
**Steps:** Create RevenueReportQueryService with method RevenueReportSummaryDTO getReport(RevenueReportFilterDTO filter); Build JPQL aggregation: SELECT rr.partner_id, rr.scheme_id, COUNT(rr.id), SUM(rr.fx_margin_usd), SUM(rr.estimated_fee_share_usd), SUM(rr.service_charge_amount), rr.service_charge_ccy GROUP BY rr.partner_id, rr.scheme_id, rr.service_charge_ccy WHERE rr.revenue_date BETWEEN :from AND :to AND (:partnerId IS NULL OR rr.partner_id=:partnerId); Join to transaction to filter by direction when filter.direction is non-null; Fetch current treasury rate usd_krw from treasury_rate table for KRW conversion in total_revenue_krw_equiv calculation; use BigDecimal multiply, scale to 0 (KRW has 0 decimal places); Build summary totals across all rows; return RevenueReportSummaryDTO with list of rows and aggregated totals; When revenueStream=SCHEME_FEE, zero out fx_margin fields in each row DTO; when FX_MARGIN, zero out gme_scheme_share_krw
**Deliverable:** RevenueReportQueryService class with getReport(RevenueReportFilterDTO) method
**Acceptance / logic checks:**
- Given 3 revenue_record rows for partner SendMN on 2026-06-01: fx_margin_usd=[1.00, 2.00, 1.50], estimated_fee_share_usd=[0.50, 0.50, 0.50], getReport returns total_fx_margin_usd=4.50 and gme_scheme_share sum=1.50 for that partner
- When filter.revenueStream=SCHEME_FEE the returned rows have total_fx_margin_usd=0.00
- When filter.revenueStream=FX_MARGIN the returned rows have gme_scheme_share_krw=0
- Conversion: total_fx_margin_usd=100.00 with usd_krw=1350.42 yields total_revenue_krw_equiv contribution of 135042 KRW (rounded to 0 dp)
- Filter by direction=INBOUND returns only revenue_records whose linked transaction.direction='INBOUND'
**Depends on:** 10.9-T01, 10.9-T02

### 10.9-T07 — Wire settlement batch query service into AdminSettlementController  _(40 min)_
**Context:** AdminSettlementController.getBatches() (created in T03) currently returns an empty stub. It must call SettlementBatchQueryService.getBatchesByDate(). The controller accepts SettlementBatchFilterDTO (@ModelAttribute), validates it, calls the service, and returns SettlementBatchListDTO. Pagination: the list is small (max 10 file-type rows per date per scheme) so no DB-level pagination is needed; the wrapper DTO provides totalElements for frontend parity.
**Steps:** Inject SettlementBatchQueryService into AdminSettlementController; Replace stub implementation: validate SettlementBatchFilterDTO (@Valid); call getBatchesByDate(filter.settlementDate, filter.schemeId); Wrap result in SettlementBatchListDTO with totalElements=list.size(), page=0, size=list.size(); Add @ExceptionHandler for MethodArgumentNotValidException returning HTTP 400 with field-level error map; Write integration test: AdminSettlementControllerIT seeds two settlement_batch rows for 2026-06-01 (ZP0061 TRANSMITTED, ZP0062 RECEIVED) and verifies GET /admin/settlement/batches?settlementDate=2026-06-01 returns 200 with content array size=2 and correct statuses
**Deliverable:** Updated AdminSettlementController.getBatches() fully wired; AdminSettlementControllerIT integration test
**Acceptance / logic checks:**
- GET /admin/settlement/batches?settlementDate=2026-06-01 with seeded rows returns HTTP 200 and content[].length=2
- Returned DTO contains file_type=ZP0061 with status=TRANSMITTED and file_type=ZP0062 with status=RECEIVED
- GET /admin/settlement/batches without settlementDate param returns HTTP 400 with error on field settlementDate
- ADMIN_VIEWER JWT returns HTTP 403
- GET /admin/settlement/batches?settlementDate=2026-06-01&schemeId=999 (no matching rows) returns 200 with empty content list
**Depends on:** 10.9-T03, 10.9-T04

### 10.9-T08 — Wire revenue report query service into AdminRevenueController  _(40 min)_
**Context:** AdminRevenueController.getReport() (T03 stub) must call RevenueReportQueryService.getReport(filter). Validation is via @Valid on RevenueReportFilterDTO. The endpoint returns RevenueReportSummaryDTO as JSON. The summary DTO includes totals row and a list of per-partner-scheme rows. KRW values must be serialized as integers (0 decimal places per currency scale rules); USD values as strings with 4 decimal places to avoid floating-point loss.
**Steps:** Inject RevenueReportQueryService into AdminRevenueController; Replace stub: call service.getReport(filter), return ResponseEntity.ok(summary); Configure Jackson serializer: BigDecimal fields with _krw suffix serialized as rounded integer strings; _usd suffix fields serialized with 4 decimal places; Add @ExceptionHandler for validation errors: return HTTP 400 with errors map; Write AdminRevenueControllerIT: seed 5 revenue_record rows for May 2026 and verify GET /admin/revenue/report?periodType=MONTHLY&dateFrom=2026-05-01&dateTo=2026-05-31 returns correct totals; Verify that the response body total_revenue_krw_equiv rounds to nearest whole KRW (no decimal point in JSON)
**Deliverable:** Updated AdminRevenueController.getReport() fully wired; AdminRevenueControllerIT
**Acceptance / logic checks:**
- GET /admin/revenue/report with 5 seeded rows returns HTTP 200 with rows[].length matching distinct partner-scheme combos
- JSON field total_fx_margin_usd serialized as '4.5000' (4dp string) not as float 4.5
- JSON field total_revenue_krw_equiv serialized as integer 135042 not 135042.00
- GET /admin/revenue/report without dateFrom returns HTTP 400
- Request with dateTo before dateFrom returns HTTP 400 with message referencing the cross-field rule
**Depends on:** 10.9-T03, 10.9-T06

### 10.9-T09 — Implement CSV export endpoint for revenue report  _(50 min)_
**Context:** GET /admin/revenue/export/csv (stubbed in T03) must stream a CSV file. Columns (PRD-07 §11.3.2): Period, Partner, Scheme, Transaction Count, Gross Payout (KRW), Merchant Fee Total (KRW), GME Scheme Share (KRW), Collection Margin (USD), Payout Margin (USD), Total FX Margin (USD), Service Charges (Settle A ccy), Service Charge Ccy, Total Revenue (KRW equiv). The CSV is generated synchronously for up to 366 days of data. The system must complete within 10 seconds for a 30-day period (PRD-07 §15.8). Audit event DATA_EXPORT must be logged (entity_type=revenue_report, actor from JWT, timestamp, filter params as metadata JSON).
**Steps:** Create RevenueCsvExportService with method void writeCsv(RevenueReportFilterDTO filter, OutputStream out, String actorId) using Apache Commons CSV or OpenCSV; Write header row with exact column names from context; write one data row per RevenueReportRowDTO; Format KRW amounts as plain integers (no decimals, comma-separated thousands); USD amounts as 0.0000 (4dp); service charges in their native ccy with 2dp; After writing CSV, call AuditLogService.log(DATA_EXPORT, 'revenue_report', null, actorId, Map.of('filter', filterJson)); Wire into AdminRevenueController.exportCsv(): set Content-Disposition attachment filename=revenue_report_{dateFrom}_{dateTo}.csv, stream via StreamingResponseBody; Integration test: request export for 30-day period, download body, parse CSV, verify header row and that row count matches getReport row count for same filter
**Deliverable:** RevenueCsvExportService; updated AdminRevenueController.exportCsv(); integration test
**Acceptance / logic checks:**
- CSV header row exactly matches the 13 column names listed in the context (case-sensitive)
- KRW value of 45000 appears in CSV as '45,000' (comma thousands, no decimal)
- USD value of 4.5 appears as '4.5000'
- Audit log entry DATA_EXPORT created with actor_id from JWT and metadata containing dateFrom and dateTo
- GET /admin/revenue/export/csv returns Content-Disposition header attachment; filename=revenue_report_2026-05-01_2026-05-31.csv for matching dates
**Depends on:** 10.9-T06, 10.9-T03

### 10.9-T10 — Implement ReconciliationDetailController endpoints  _(45 min)_
**Context:** PRD-07 §11.2.3: Ops can view flagged reconciliation items for a batch and mark them resolved. Endpoints needed: GET /admin/settlement/batches/{batchId}/reconciliation returning ReconciliationSummaryDTO; GET /admin/settlement/batches/{batchId}/reconciliation/items?page=&size= returning Page<ReconciliationItemDTO> (non-MATCHED only); POST /admin/settlement/batches/{batchId}/reconciliation/items/{itemId}/resolve with body {note: string} marking the item RESOLVED. POST endpoint requires OPS_OPERATOR or SUPER_ADMIN; GET endpoints also accessible to FINANCE_ANALYST. All resolution actions are audit-logged with entity_type=reconciliation_item and event DATA_EXPORT not applicable here - use RESOLVE action.
**Steps:** Add three endpoints to AdminSettlementController (or a new ReconciliationController); GET /{batchId}/reconciliation: call ReconciliationQueryService.getReconciliationSummaryForBatch(batchId), return 200 or 404 if batch not found; GET /{batchId}/reconciliation/items: call getDiscrepancyItems(batchId, pageable), return 200 with Page<ReconciliationItemDTO>; POST /{batchId}/reconciliation/items/{itemId}/resolve: @PreAuthorize OPS_OPERATOR|SUPER_ADMIN only; call markResolved(itemId, actorId, note); return 204; Add exception handler for ReconciliationNotFoundException -> HTTP 404; Integration test: seed batch + 2 DISCREPANCY items, verify GET items returns both, POST resolve on item 1 returns 204, subsequent GET shows item 1 has resolution_status=RESOLVED
**Deliverable:** Reconciliation endpoints in ReconciliationController; integration test ReconciliationControllerIT
**Acceptance / logic checks:**
- GET /admin/settlement/batches/999/reconciliation returns HTTP 404 when batch 999 does not exist
- GET items endpoint with FINANCE_ANALYST returns 200
- POST resolve with FINANCE_ANALYST returns 403
- POST resolve on already-RESOLVED item returns HTTP 409 with error message
- Integration test passes: resolve item returns 204, subsequent GET reconciliation shows unresolvedCount decremented by 1
**Depends on:** 10.9-T05, 10.9-T03

### 10.9-T11 — Implement settlement batch status aggregation for UI dashboard view  _(45 min)_
**Context:** PRD-07 §11.2.2 and UX-11 §4.9: the UI shows one row per settlement date with columns for each batch file status. The backend needs to transform the list of settlement_batch rows (one per file_type) into a pivot structure: BatchDayStatusDTO {settlementDate, scheme, transactionCount (from ZP0065), grossPayoutKrw (total_amount from ZP0065), gmeFeeShareKrw (from settlement computation), netSettlementKrw, batchStatuses: Map<fileType, String>}. gmeFeeShareKrw = grossPayoutKrw x fee_share_pct (e.g. 70% = 0.70) for Domestic batches; for International (gross settlement) gmeFeeShareKrw is invoiced separately so it is 0 in this view. netSettlementKrw = grossPayoutKrw - gmeFeeShareKrw.
**Steps:** Create BatchDayStatusDTO with fields: settlementDate, schemeName, transactionCount, grossPayoutKrw (BigDecimal, KRW), gmeFeeShareKrw (BigDecimal, KRW), netSettlementKrw (BigDecimal, KRW), batchStatuses (Map<String, String> of fileType->status); Add method BatchDayStatusDTO aggregateDayStatus(List<SettlementBatchDTO> dayBatches, String settlementModel) to SettlementBatchQueryService; transactionCount from fileType=ZP0065 row if present, else 0; grossPayoutKrw from total_amount of ZP0065 row; If settlementModel=DOMESTIC: gmeFeeShareKrw = grossPayoutKrw.multiply(schemeFeePct).setScale(0, HALF_UP); else gmeFeeShareKrw=0; netSettlementKrw = grossPayoutKrw.subtract(gmeFeeShareKrw); batchStatuses = map each DTO to fileType->status; Expose aggregated view via updated GET /admin/settlement/batches endpoint: return List<BatchDayStatusDTO> when query param view=DAY_SUMMARY is present
**Deliverable:** BatchDayStatusDTO; aggregateDayStatus() method; updated GET /admin/settlement/batches?view=DAY_SUMMARY
**Acceptance / logic checks:**
- Given ZP0065 row: total_amount=1000000.00 KRW, fee_share_pct=0.70, settlementModel=DOMESTIC: gmeFeeShareKrw=700000, netSettlementKrw=300000
- Given settlementModel=INTERNATIONAL: gmeFeeShareKrw=0, netSettlementKrw=grossPayoutKrw
- batchStatuses map contains entries for all file types present in input list
- When ZP0065 row is absent, transactionCount=0 and grossPayoutKrw=0
- GET /admin/settlement/batches?settlementDate=2026-06-01&view=DAY_SUMMARY returns List<BatchDayStatusDTO>
**Depends on:** 10.9-T04, 10.9-T07

### 10.9-T12 — Build frontend SettlementBatchStatusTable React component  _(55 min)_
**Context:** UX-11 §4.9 wireframe shows a settlement batch table with columns: Batch (file type), Due (KST time), Status (Sent/Acknowledged/Failed/Recv/LATE/Matched/Discrepancy), File ID, Actions ([View] / [Reconcile] / [Retry]). Status uses color indicators: green tick for success states (TRANSMITTED/RECEIVED/RECONCILED), red X for ERROR, amber clock for PENDING/LATE. The component fetches from GET /admin/settlement/batches?settlementDate={date}&schemeId={schemeId}. The table must not exceed 5 visible columns per the no-horizontal-scroll convention; Actions column is the 5th.
**Steps:** Create SettlementBatchStatusTable.tsx accepting props: settlementDate (string ISO), schemeId (number|null); Fetch data from GET /admin/settlement/batches on mount and on prop change; show loading spinner during fetch; Map status to badge variant: TRANSMITTED/RECEIVED/RECONCILED -> success (green); ERROR -> danger (red); PENDING -> warning (amber); GENERATED -> info (blue); Compute LATE status client-side: if status=PENDING and current UTC time > due time KST for that file type (ZP0061 due 05:00 KST = 20:00 UTC prior day, ZP0062 due 10:00 KST = 01:00 UTC, ZP0063 due 14:00 KST = 05:00 UTC, ZP0064 due 19:00 KST = 10:00 UTC) render LATE badge; Actions: show [View] for all rows; show [Reconcile] only when status=RECEIVED and fileType in (ZP0062, ZP0064); show [Retry] only when status=ERROR
**Deliverable:** SettlementBatchStatusTable.tsx component with fetch, status badge rendering, and conditional actions
**Acceptance / logic checks:**
- Component renders 5 columns maximum (Batch, Due, Status, File ID, Actions)
- Row with status=TRANSMITTED shows green success badge
- Row with status=PENDING past ZP0062 due time 10:00 KST shows LATE amber badge
- [Reconcile] button only visible on rows with fileType=ZP0062 or ZP0064 and status=RECEIVED
- [Retry] button visible only on ERROR rows; [View] visible on all rows
**Depends on:** 10.9-T07

### 10.9-T13 — Build frontend RevenueSummaryCard React component  _(30 min)_
**Context:** UX-11 §4.9 wireframe shows a REVENUE SUMMARY card above the batch table with four KPI tiles: Scheme fee share (USD, 70% of net ZeroPay), FX margin total (USD, sum of m_a+m_b extracted), Service fees total (USD flat charges), Total revenue (USD). The component receives the RevenueReportSummaryDTO from the parent page (already fetched) and renders the summary totals. Data comes from the /admin/revenue/report endpoint with period/scheme filters from the page-level filter bar. USD values are displayed with 2dp and comma thousands. The card is read-only.
**Steps:** Create RevenueSummaryCard.tsx accepting prop: summary: RevenueReportSummaryDTO | null; If null, render skeleton loader (4 grey placeholder tiles); Render four KPI tiles: Scheme Fee Share = summary.totalGmeSchemeShareKrw converted display (show both USD equiv and KRW); FX Margin Total = summary.totalFxMarginUsd; Service Fees Total = derived from service charge sum in summary; Total Revenue = summary.grandTotalRevenueKrwEquiv; Format USD with 2dp, comma thousands separator; KRW as integer with comma thousands and KRW suffix; Add aria-label to each tile value for screen reader accessibility (e.g. 'Scheme fee share: USD 1,234.56')
**Deliverable:** RevenueSummaryCard.tsx component with four KPI tiles and loading state
**Acceptance / logic checks:**
- When summary=null, four skeleton placeholder elements are rendered (not an error state)
- USD value 1234.56 renders as '1,234.56'
- KRW value 135042 renders as '135,042 KRW'
- Each tile has an aria-label attribute containing the label and formatted value
- Component renders without errors when all summary numeric fields are 0.0000
**Depends on:** 10.9-T08

### 10.9-T14 — Build frontend RevenueReportTable React component with filter bar  _(55 min)_
**Context:** PRD-07 §11.3.2 and UX-11 §4.9: the revenue report view has a filter bar (Period type dropdown: DAILY/WEEKLY/MONTHLY/CUSTOM; date-from/date-to pickers; Partner dropdown; Scheme dropdown; Revenue stream dropdown: ALL/SCHEME_FEE/FX_MARGIN) and a paginated table. Table columns (max 6): Period, Partner, Scheme, Transaction Count, Total FX Margin (USD), GME Scheme Share (KRW). Additional columns accessible in an expandable row detail panel: Gross Payout KRW, Merchant Fee Total KRW, Collection Margin USD, Payout Margin USD, Service Charges, Total Revenue KRW equiv. An Export CSV button triggers GET /admin/revenue/export/csv with the same filter params.
**Steps:** Create RevenueReportTable.tsx with state: filters (RevenueReportFilter), data (RevenueReportSummaryDTO|null), loading; Filter bar: period type select, date-from/date-to date pickers (disabled when periodType != CUSTOM), partner select (populated from GET /admin/partners/list), scheme select, revenue stream select; On Search button click: validate dateFrom <= dateTo, then fetch GET /admin/revenue/report with filter params; update data state; Table: 6 visible columns (Period, Partner, Scheme, Txn Count, Total FX Margin USD, GME Scheme Share KRW); each row has an expand toggle showing the 7-field detail panel; Export CSV button: onClick fetches /admin/revenue/export/csv, triggers browser download via Blob URL; show loading spinner on button during fetch; Show RevenueSummaryCard above the table using the summary totals from the same response
**Deliverable:** RevenueReportTable.tsx component with filter bar, paginated table, row expand, and CSV export button
**Acceptance / logic checks:**
- Filter bar disables date pickers when periodType=MONTHLY and auto-populates dateFrom/dateTo to start/end of selected month
- Search with dateTo before dateFrom shows inline error 'Date range is invalid' before making API call
- Expanded row panel shows Collection Margin USD and Payout Margin USD as separate fields
- Export CSV button triggers browser file download with filename matching pattern revenue_report_{from}_{to}.csv
- When revenueStream=SCHEME_FEE, the Total FX Margin USD column shows '--' (not 0) to distinguish suppressed from zero
**Depends on:** 10.9-T08, 10.9-T09, 10.9-T13

### 10.9-T15 — Build frontend ReconciliationDetailModal component  _(55 min)_
**Context:** PRD-07 §11.2.3: from the batch status table, clicking [Reconcile] on a ZP0062 or ZP0064 row opens a modal showing reconciliation summary (total items, matched count, discrepancy count) and a list of flagged items (non-MATCHED). Each flagged item shows: txn_ref, scheme_ref, match_status (MISSING_IN_SCHEME/AMOUNT_MISMATCH/SCHEME_REJECTION), gme_amount, scheme_amount, discrepancy_amount, resolution_status (UNRESOLVED/RESOLVED/ESCALATED). OPS_OPERATOR users see a [Mark Resolved] button per UNRESOLVED item; FINANCE_ANALYST users see items read-only. Resolving requires a confirmation step with a mandatory note field.
**Steps:** Create ReconciliationDetailModal.tsx accepting props: batchId (number), fileType (string), isOpen (boolean), onClose ()=>void, userRole (string); On open: fetch GET /admin/settlement/batches/{batchId}/reconciliation and GET /admin/settlement/batches/{batchId}/reconciliation/items?page=0&size=50; Render summary tile row (total, matched, discrepancies, unresolved) above item list; Item list: columns: Txn Ref, Status badge, GME Amount, Scheme Amount, Discrepancy, Resolution; For OPS_OPERATOR: [Mark Resolved] button per UNRESOLVED row; clicking opens inline note input (required, min 10 chars) + [Confirm] button; on confirm POST /{batchId}/reconciliation/items/{itemId}/resolve {note}; on success update item row to RESOLVED; FINANCE_ANALYST sees items but no [Mark Resolved] button (controlled by userRole prop)
**Deliverable:** ReconciliationDetailModal.tsx component with summary, item list, and resolve workflow
**Acceptance / logic checks:**
- Modal fetches data on open and shows loading spinner before data arrives
- UNRESOLVED item shows [Mark Resolved] when userRole=OPS_OPERATOR
- [Confirm] is disabled until note field has >= 10 characters
- After successful POST resolve, the row's resolution_status updates to RESOLVED inline without full modal reload
- When userRole=FINANCE_ANALYST, no [Mark Resolved] button is rendered on any row
**Depends on:** 10.9-T10, 10.9-T12

### 10.9-T16 — Build frontend SettlementRevenuePage container and route  _(50 min)_
**Context:** PRD-07 §3 nav map: the Settlement section has sub-items Batch Status, Revenue, Reconciliation. In Phase 1 these are tabs on a single page (/admin/settlement-revenue). The page-level filter bar (Period, Partner, Scheme) is shared between the batch view and revenue view to avoid re-entering. The Revenue tab shows RevenueSummaryCard + RevenueReportTable. The Batch Status tab shows SettlementBatchStatusTable. State for the shared filter is lifted to the page container. The page is accessible to SUPER_ADMIN, OPS_OPERATOR, FINANCE_ANALYST (checked via route guard); ADMIN_VIEWER is redirected to /403.
**Steps:** Create SettlementRevenuePage.tsx at route /admin/settlement-revenue; Add role guard: if user role is ADMIN_VIEWER redirect to /403; Page-level state: activeTab (BATCH_STATUS|REVENUE), settlementDate (default today KST), partnerId, schemeId, dateFrom, dateTo, periodType; Render shared filter bar at top (date/partner/scheme selectors); Render tab switcher; tab BATCH_STATUS renders SettlementBatchStatusTable with settlementDate+schemeId; tab REVENUE renders RevenueReportTable with full filter; On tab switch: preserve filter state; do not re-fetch unnecessarily (use React Query or similar with filter as cache key); Add breadcrumb: Settlement / Settlement & Revenue
**Deliverable:** SettlementRevenuePage.tsx container with routing, role guard, tab switcher, and shared filter state
**Acceptance / logic checks:**
- Visiting /admin/settlement-revenue as ADMIN_VIEWER redirects to /403
- Switching from BATCH_STATUS to REVENUE tab preserves the selected partner and scheme
- Page breadcrumb displays 'Settlement / Settlement & Revenue'
- Shared filter bar renders period selector, partner selector, scheme selector in a single row
- On initial load, settlementDate defaults to today's KST date (not UTC)
**Depends on:** 10.9-T12, 10.9-T14

### 10.9-T17 — Unit test RevenueReportQueryService aggregation logic  _(40 min)_
**Context:** RevenueReportQueryService (T06) performs: (1) aggregation of fx_margin_usd and estimated_fee_share_usd, (2) grouping by partner+scheme, (3) KRW conversion using treasury rate, (4) revenueStream filter (zero-out fields). These are logic-bearing and require unit tests with exact numeric vectors independent of the database. treasury.usd_krw = 1350.42 in test vectors. Three-revenue-stream scenario: fx_margin_usd=4.50, estimated_fee_share_usd=1.50, service_charge_amount=500 KRW (service_charge_ccy=KRW). Expected total_revenue_krw_equiv = (1.50+4.50)*1350.42 + 500 = 8102.52 + 500 = 8602.52, rounded to 8603 KRW.
**Steps:** Create RevenueReportQueryServiceTest in admin-service test package; Test 1 - full aggregation: mock repository returning 3 revenue_record rows for partner SendMN (fx_margin_usd=[1.00,2.00,1.50], estimated_fee_share_usd=[0.50,0.50,0.50], service_charge_amount=0); verify total_fx_margin_usd=4.50, gme_scheme_share=1.50; Test 2 - KRW conversion: use treasury usd_krw=1350.42; fx_margin_usd=4.50, fee_share=1.50, service_charge KRW=500; verify total_revenue_krw_equiv=8603 (rounded HALF_UP to 0dp); Test 3 - revenueStream=SCHEME_FEE filter: verify returned row has total_fx_margin_usd=0.00 and gme_scheme_share > 0; Test 4 - revenueStream=FX_MARGIN filter: verify returned row has gme_scheme_share=0 and total_fx_margin_usd > 0; Test 5 - empty result: mock returns empty list; verify summary has all totals=0 and rows=[]
**Deliverable:** RevenueReportQueryServiceTest with 5 unit tests, all passing
**Acceptance / logic checks:**
- Test 1 asserts total_fx_margin_usd equals new BigDecimal('4.50') using compareTo (not equals to avoid scale mismatch)
- Test 2 asserts total_revenue_krw_equiv == 8603 (integer, no decimal)
- Test 3 asserts total_fx_margin_usd.compareTo(BigDecimal.ZERO)==0 when revenueStream=SCHEME_FEE
- Test 4 asserts gme_scheme_share_krw == 0 when revenueStream=FX_MARGIN
- Test 5 runs without NullPointerException and returns summary.grandTotalRevenueKrwEquiv=0
**Depends on:** 10.9-T06

### 10.9-T18 — Unit test ReconciliationQueryService summary and resolution logic  _(35 min)_
**Context:** ReconciliationQueryService (T05) computes overallStatus from discrepancy/missing counts and enforces resolution state transitions (UNRESOLVED -> RESOLVED; already-RESOLVED throws). Test vectors: Batch A: 100 items (95 MATCHED, 3 AMOUNT_MISMATCH, 2 MISSING_IN_SCHEME) -> overallStatus=DISCREPANCY. Batch B: 50 items (50 MATCHED) -> overallStatus=MATCHED. Batch C: 0 items -> overallStatus=PENDING. Resolution: item in RESOLVED state -> AlreadyResolvedException.
**Steps:** Create ReconciliationQueryServiceTest; Test 1 - discrepancy detection: mock 100 items (95 MATCHED, 3 AMOUNT_MISMATCH, 2 MISSING_IN_SCHEME); assert overallStatus=DISCREPANCY, discrepancyCount=3, missingInSchemeCount=2, unresolvedCount=5; Test 2 - all matched: mock 50 MATCHED items; assert overallStatus=MATCHED, discrepancyCount=0; Test 3 - empty batch: mock 0 items; assert overallStatus=PENDING; Test 4 - resolve transition: mock item with resolution_status=UNRESOLVED; call markResolved; verify resolved_at is non-null and resolution_status=RESOLVED; Test 5 - double resolve guard: call markResolved on item with resolution_status=RESOLVED; verify AlreadyResolvedException is thrown
**Deliverable:** ReconciliationQueryServiceTest with 5 unit tests, all passing
**Acceptance / logic checks:**
- Test 1: overallStatus.name() equals 'DISCREPANCY' and unresolvedCount equals 5
- Test 2: overallStatus.name() equals 'MATCHED'
- Test 3: overallStatus.name() equals 'PENDING'
- Test 4: after markResolved, the item's resolved_at timestamp is within 2 seconds of now
- Test 5: AlreadyResolvedException thrown (not silently ignored)
**Depends on:** 10.9-T05

### 10.9-T19 — Unit test BatchDayStatusDTO aggregation for net vs gross settlement  _(35 min)_
**Context:** SettlementBatchQueryService.aggregateDayStatus() (T11) computes gmeFeeShareKrw and netSettlementKrw based on settlementModel (DOMESTIC vs INTERNATIONAL). Domestic: fee_share_pct=0.70; given grossPayoutKrw=1000000.00, gmeFeeShareKrw=700000, netSettlementKrw=300000. International: gmeFeeShareKrw=0, netSettlementKrw=grossPayoutKrw. Edge: KRW amounts must scale to 0 decimal places. Verify batchStatuses map built correctly from a mixed list of fileType rows.
**Steps:** Create BatchDayStatusAggregationTest; Test 1 - domestic net settlement: mock ZP0065 batch row with total_amount=1000000.00, fee_share_pct=0.70, settlementModel=DOMESTIC; assert gmeFeeShareKrw=700000, netSettlementKrw=300000 (both BigDecimal with scale 0); Test 2 - international gross settlement: same ZP0065 row with settlementModel=INTERNATIONAL; assert gmeFeeShareKrw=0, netSettlementKrw=1000000; Test 3 - KRW scale: input total_amount=1000000.9999 (from DECIMAL(20,4)); assert gmeFeeShareKrw and netSettlementKrw both have scale=0 (rounded HALF_UP); Test 4 - batchStatuses map: input list [ZP0061 TRANSMITTED, ZP0062 RECEIVED, ZP0063 ERROR]; assert map keys contain 'ZP0061','ZP0062','ZP0063' with correct status values; Test 5 - missing ZP0065: input list has no ZP0065 row; assert transactionCount=0 and grossPayoutKrw=BigDecimal.ZERO
**Deliverable:** BatchDayStatusAggregationTest with 5 unit tests, all passing
**Acceptance / logic checks:**
- Test 1: gmeFeeShareKrw.compareTo(new BigDecimal('700000'))==0 and scale()==0
- Test 2: gmeFeeShareKrw.compareTo(BigDecimal.ZERO)==0
- Test 3: no ArithmeticException thrown; result has scale=0
- Test 4: map.get('ZP0063') equals 'ERROR'
- Test 5: grossPayoutKrw.compareTo(BigDecimal.ZERO)==0
**Depends on:** 10.9-T11

### 10.9-T20 — Unit test RevenueCsvExportService column names, formatting, and audit logging  _(40 min)_
**Context:** RevenueCsvExportService (T09) must produce a CSV with exact column headers and correctly formatted numeric values. KRW integers use comma thousands separator; USD uses 4dp. The service must emit a DATA_EXPORT audit log entry after writing. Test vectors: one RevenueReportRowDTO with total_fx_margin_usd=BigDecimal('4.5000'), gme_scheme_share_krw=135042, service_charge_amount=500.00 KRW.
**Steps:** Create RevenueCsvExportServiceTest; Test 1 - header row: call writeCsv with empty filter and empty rows list; parse resulting CSV string; assert first row contains all 13 expected column names in order; Test 2 - KRW formatting: single row with gme_scheme_share_krw=135042; assert the corresponding CSV cell contains '135,042' (comma thousands, no decimal point); Test 3 - USD formatting: single row with total_fx_margin_usd=BigDecimal('4.5'); assert CSV cell contains '4.5000' (4dp); Test 4 - audit log: mock AuditLogService; call writeCsv; verify auditLogService.log was called with action=DATA_EXPORT, entityType='revenue_report'; Test 5 - empty rows: writeCsv with zero rows produces exactly one line (header) and no data rows
**Deliverable:** RevenueCsvExportServiceTest with 5 unit tests, all passing
**Acceptance / logic checks:**
- Test 1: header row string contains exactly the substring 'GME Scheme Share (KRW)' (exact case)
- Test 2: cell value is '135,042' not '135042' or '135,042.00'
- Test 3: cell value is '4.5000' not '4.5' or '4.50'
- Test 4: verify(auditLogService).log(eq(DATA_EXPORT), eq('revenue_report'), ...) called once
- Test 5: CSV output split by line-separator has length == 1 (header only, no trailing empty line)
**Depends on:** 10.9-T09

### 10.9-T21 — Add DB indexes for settlement and revenue query performance  _(30 min)_
**Context:** The settlement_batch and revenue_record tables are queried heavily by the dashboard. settlement_batch is queried by (settlement_date, scheme_id) and by (batch_id) for reconciliation drilldown. revenue_record is queried by (revenue_date, partner_id, scheme_id) for the revenue report aggregation. reconciliation_item is queried by (batch_id, match_status) to retrieve non-MATCHED items. Without indexes these queries will full-scan as data grows. PRD-07 §14.1: CSV export for 30-day period must complete within 10 seconds.
**Steps:** Create Flyway migration V10_9__settlement_revenue_indexes.sql; Add index: CREATE INDEX IF NOT EXISTS idx_settlement_batch_date_scheme ON settlement_batch(settlement_date, scheme_id); Add index: CREATE INDEX IF NOT EXISTS idx_settlement_batch_status ON settlement_batch(status) WHERE status IN ('PENDING','ERROR'); Add index: CREATE INDEX IF NOT EXISTS idx_revenue_record_date_partner_scheme ON revenue_record(revenue_date, partner_id, scheme_id); Add index: CREATE INDEX IF NOT EXISTS idx_reconciliation_item_batch_match ON reconciliation_item(batch_id, match_status); Verify migration runs cleanly on a dev DB with flyway migrate; confirm explain plan for SELECT * FROM revenue_record WHERE revenue_date BETWEEN '2026-05-01' AND '2026-05-31' uses the new index
**Deliverable:** Flyway migration V10_9__settlement_revenue_indexes.sql with 4 index definitions
**Acceptance / logic checks:**
- Migration runs without error via mvn flyway:migrate on a clean schema
- EXPLAIN on SELECT ... FROM settlement_batch WHERE settlement_date='2026-06-01' AND scheme_id=1 shows Index Scan using idx_settlement_batch_date_scheme
- EXPLAIN on SELECT ... FROM revenue_record WHERE revenue_date BETWEEN '2026-05-01' AND '2026-05-31' shows Index Scan using idx_revenue_record_date_partner_scheme
- EXPLAIN on SELECT ... FROM reconciliation_item WHERE batch_id=1 AND match_status != 'MATCHED' shows Index Scan using idx_reconciliation_item_batch_match
- Migration is idempotent: running twice does not error (IF NOT EXISTS)

### 10.9-T22 — Add merchant fee schedule read-only reference view to Settlement module  _(40 min)_
**Context:** PRD-07 §11.3.3: the Settlement & Revenue module must display the merchant fee schedule for the scheme as a reference (read-only; edits go through the Schemes module). The fee schedule is stored in qr_scheme (fee_schedule JSON or structured fee_tier columns per DAT-03). The view shows: merchant type, fee rate %, domestic/crossborder distinction, GME fee share %. This gives Finance context for reconciling revenue figures. The endpoint is GET /admin/settlement/fee-schedule?schemeId=. FINANCE_ANALYST, OPS_OPERATOR, SUPER_ADMIN may access.
**Steps:** Create MerchantFeeScheduleDTO with fields: schemeId, schemeName, feeRows: List<FeeRowDTO> where FeeRowDTO has merchantType, feeRatePct, settlementType (DOMESTIC|INTERNATIONAL), gmeFeeSharePct; Add method List<MerchantFeeScheduleDTO> getFeeSchedule(Long schemeId) to SettlementBatchQueryService (or a new FeeScheduleQueryService): query qr_scheme joined to fee configuration; Add GET /admin/settlement/fee-schedule?schemeId= endpoint in AdminSettlementController; @PreAuthorize same roles as other settlement endpoints; Create FeeSchedulePanel.tsx React component: renders a read-only table of merchant types and fee rates; data fetched when Settlement page loads; no edit capability; Verify a FINANCE_ANALYST user can see the panel; verify clicking fee rows does not offer any edit interaction
**Deliverable:** MerchantFeeScheduleDTO; GET /admin/settlement/fee-schedule endpoint; FeeSchedulePanel.tsx
**Acceptance / logic checks:**
- GET /admin/settlement/fee-schedule?schemeId=1 returns HTTP 200 with at least one fee row for ZeroPay
- FeeRowDTO contains gmeFeeSharePct field with value 0.70 (70%) for ZeroPay scheme
- FeeSchedulePanel.tsx renders a table with no input fields or edit buttons
- ADMIN_VIEWER role receives 403 from the endpoint
- schemeId=999 (non-existent) returns empty list (not 404)
**Depends on:** 10.9-T03

### 10.9-T23 — Audit-log all export and resolution actions in Settlement module  _(40 min)_
**Context:** PRD-07 §13.2 requires that data exports and settlement resolution actions are audit-logged. The audit_log table (DAT-03 §9.1) has: actor_id (VARCHAR), actor_type (OPERATOR), action (CREATE/UPDATE/DELETE/ACTIVATE/DEACTIVATE), entity_type (VARCHAR), entity_id, previous_value (JSON), new_value (JSON), ip_address, description. For this module: (1) CSV export: action=UPDATE (repurpose as DATA_EXPORT event code in description), entity_type='revenue_report'. (2) Reconciliation item resolve: action=UPDATE, entity_type='reconciliation_item', entity_id=itemId, previous_value={resolution_status:'UNRESOLVED'}, new_value={resolution_status:'RESOLVED', resolved_by, resolution_note}. (3) Flag exception: action=UPDATE, entity_type='reconciliation_item', new_value={resolution_status:'ESCALATED'}. Actor IP must be captured from HttpServletRequest.
**Steps:** Confirm AuditLogService.log(String action, String entityType, Long entityId, String actorId, String actorIp, Map previousValue, Map newValue, String description) method exists (add if missing); In RevenueCsvExportService.writeCsv: after streaming CSV, call auditLogService.log with action='DATA_EXPORT', entityType='revenue_report', entityId=null, description containing dateFrom/dateTo filter values; In ReconciliationQueryService.markResolved: call auditLogService.log with action='UPDATE', entityType='reconciliation_item', entityId=itemId, previous={resolution_status:'UNRESOLVED'}, new={resolution_status:'RESOLVED', note}; In any future escalation path: action='UPDATE', new_value={resolution_status:'ESCALATED'}; Integration test: call exportCsv endpoint, then query audit_log table, verify one entry with action='DATA_EXPORT' and actor_id matching the JWT subject
**Deliverable:** AuditLogService integration in RevenueCsvExportService and ReconciliationQueryService; integration test verifying audit entries
**Acceptance / logic checks:**
- After POST resolve item 42, audit_log contains one row with entity_type='reconciliation_item' and entity_id=42
- Audit row new_value JSON contains key resolution_status with value RESOLVED
- After GET /admin/revenue/export/csv, audit_log contains row with action=DATA_EXPORT and description containing the filter dateFrom value
- audit_log entry actor_ip is populated (not null) from the HTTP request context
- No audit entry is created when a read-only GET endpoint is called (only writes are audited)
**Depends on:** 10.9-T09, 10.9-T10

### 10.9-T24 — Integration test: full settlement batch status flow end-to-end  _(55 min)_
**Context:** Validates the complete backend flow for T04+T07+T11: seed settlement_batch rows, call GET /admin/settlement/batches, verify SettlementBatchListDTO response, call DAY_SUMMARY view, verify BatchDayStatusDTO aggregation. Uses a test PostgreSQL DB (Testcontainers). Seed data: settlement_date=2026-06-01, scheme=ZeroPay (id=1), rows: ZP0061 TRANSMITTED total_amount=0, ZP0062 RECEIVED total_amount=0, ZP0065 RECONCILED total_amount=1000000.00 KRW, transaction_count=150. fee_share_pct=0.70 for ZeroPay.
**Steps:** Create SettlementBatchIntegrationTest using @SpringBootTest with Testcontainers PostgreSQL; Seed 3 settlement_batch rows as described in context using a SQL script or @Sql annotation; Test 1: GET /admin/settlement/batches?settlementDate=2026-06-01 returns HTTP 200 with content.length=3 and statuses matching seeded values; Test 2: GET /admin/settlement/batches?settlementDate=2026-06-01&view=DAY_SUMMARY returns HTTP 200 with single BatchDayStatusDTO having transactionCount=150, grossPayoutKrw=1000000, gmeFeeShareKrw=700000, netSettlementKrw=300000; Test 3: GET /admin/settlement/batches?settlementDate=2026-07-01 (no rows) returns HTTP 200 with empty content; Test 4: Unauthenticated request returns HTTP 401
**Deliverable:** SettlementBatchIntegrationTest with 4 test cases, all passing against Testcontainers DB
**Acceptance / logic checks:**
- Test 1 passes: content array has 3 elements with file_type values ZP0061, ZP0062, ZP0065
- Test 2 passes: gmeFeeShareKrw equals 700000 and netSettlementKrw equals 300000
- Test 3 passes: empty content list returned (not 404)
- Test 4 passes: 401 returned for unauthenticated request
- All 4 tests pass in CI without manual DB setup
**Depends on:** 10.9-T07, 10.9-T11

### 10.9-T25 — Integration test: revenue report query and CSV export end-to-end  _(55 min)_
**Context:** Validates RevenueReportQueryService + AdminRevenueController + RevenueCsvExportService together. Seed: 5 revenue_record rows for May 2026: partner=SendMN (id=2), scheme=ZeroPay (id=1), fx_margin_usd=[1.00,2.00,1.50,0.50,0.50], estimated_fee_share_usd=[0.30,0.30,0.30,0.30,0.30], service_charge_amount=0. treasury_rate usd_krw=1350.42 seeded. Expected totals: total_fx_margin_usd=5.50, gme_scheme_share=1.50, total_revenue_krw_equiv = (5.50+1.50)*1350.42 = 9452.94 rounded to 9453 KRW.
**Steps:** Create RevenueReportIntegrationTest using @SpringBootTest + Testcontainers; Seed revenue_record rows and treasury_rate row via @Sql script; Test 1: GET /admin/revenue/report?periodType=MONTHLY&dateFrom=2026-05-01&dateTo=2026-05-31 returns HTTP 200 with summary.totalFxMarginUsd='5.5000' and summary.grandTotalRevenueKrwEquiv=9453; Test 2: same filter with revenueStream=SCHEME_FEE returns rows with totalFxMarginUsd='0.0000'; Test 3: GET /admin/revenue/export/csv with same filter returns HTTP 200 with Content-Type: text/csv; parse CSV body; first row is header; data rows total correct tx count; Test 4: verify audit_log has one DATA_EXPORT entry after the CSV export call
**Deliverable:** RevenueReportIntegrationTest with 4 test cases, all passing against Testcontainers DB
**Acceptance / logic checks:**
- Test 1: grandTotalRevenueKrwEquiv equals 9453 (integer)
- Test 2: all row DTOs have totalFxMarginUsd with value 0.0000 when revenueStream=SCHEME_FEE
- Test 3: CSV body row count (excluding header) equals 1 (one partner-scheme group)
- Test 4: audit_log table contains exactly one row with action=DATA_EXPORT after the export call
- Test 1 assertion uses exact BigDecimal comparison for totalFxMarginUsd: '5.5000'
**Depends on:** 10.9-T08, 10.9-T09, 10.9-T23


## WBS 10.10 — User & role management (RBAC)
### 10.10-T01 — Create DB migration: hub_role table with 4 canonical roles  _(25 min)_
**Context:** DAT-03 §9.2 defines hub_role: id (BIGINT PK), role_code VARCHAR(20) UNIQUE NOT NULL, permissions JSONB NOT NULL, created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ. Four canonical role_codes: SUPER_ADMIN, OPS_OPERATOR, FINANCE_ANALYST, ADMIN_VIEWER. These are seeded once; application logic references them by role_code string.
**Steps:** Create Flyway migration V10_10_001__create_hub_role.sql; Define table hub_role with columns: id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, role_code VARCHAR(20) NOT NULL UNIQUE, permissions JSONB NOT NULL DEFAULT '{}', created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(); Add seed INSERT for all four roles with empty permissions JSONB; Add unique index on role_code; Verify migration runs cleanly on a fresh schema
**Deliverable:** Flyway migration file V10_10_001__create_hub_role.sql with table DDL and 4 seed rows
**Acceptance / logic checks:**
- Migration applies without error on empty schema and on top of prior migrations
- SELECT COUNT(*) FROM hub_role returns 4 after seed
- role_code column has UNIQUE constraint: inserting a 5th row with role_code='SUPER_ADMIN' raises unique-violation error
- permissions column accepts and returns valid JSONB: UPDATE hub_role SET permissions='{"view_dashboard":true}' WHERE role_code='ADMIN_VIEWER' succeeds
- Rollback/undo migration removes table cleanly (no orphan objects)

### 10.10-T02 — Create DB migration: hub_user table with FK to hub_role  _(30 min)_
**Context:** DAT-03 §9.2 defines hub_user: id (BIGINT PK), email VARCHAR(254) UNIQUE NOT NULL, name VARCHAR(200) NOT NULL, role_id BIGINT FK -> hub_role NOT NULL, is_active BOOLEAN NOT NULL DEFAULT true, password_hash VARCHAR(255) NOT NULL, must_change_password BOOLEAN NOT NULL DEFAULT false, failed_login_count INT NOT NULL DEFAULT 0, locked_until TIMESTAMPTZ NULL, last_login_at TIMESTAMPTZ NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), created_by BIGINT FK -> hub_user NULL (self-ref; NULL for bootstrap user). SEC-09 §3.1: 5 consecutive failures lock the account; lockout cleared by IT admin.
**Steps:** Create Flyway migration V10_10_002__create_hub_user.sql; Define hub_user table with all columns listed in context; Add FK constraint hub_user.role_id -> hub_role.id (ON DELETE RESTRICT); Add self-referential FK hub_user.created_by -> hub_user.id (ON DELETE SET NULL); Add unique index on email; add index on role_id; add index on is_active; Insert bootstrap SUPER_ADMIN user with email='admin@gmeremit.com', must_change_password=true
**Deliverable:** Flyway migration V10_10_002__create_hub_user.sql with full hub_user DDL, FK constraints, indexes, and bootstrap user seed
**Acceptance / logic checks:**
- Migration applies after T01 with no errors
- SELECT COUNT(*) FROM hub_user returns 1 (bootstrap user)
- Inserting a hub_user with a non-existent role_id raises FK violation
- Inserting a duplicate email raises unique-constraint violation
- Bootstrap user has must_change_password=true and role_code SUPER_ADMIN confirmed via JOIN
- failed_login_count defaults to 0 and locked_until defaults to NULL
**Depends on:** 10.10-T01

### 10.10-T03 — Define RbacPermission enum and permissions JSONB schema  _(35 min)_
**Context:** The hub_role.permissions JSONB column encodes which actions a role may perform. The 20 actions from PRD-07 §12.3 permission matrix are: VIEW_DASHBOARD, CREATE_EDIT_SCHEME, ACTIVATE_SUSPEND_SCHEME, VIEW_SCHEME_CONFIG, CREATE_EDIT_PARTNER, VIEW_API_CREDENTIALS, GENERATE_REVOKE_API_CREDENTIALS, CREATE_EDIT_RULE, UPDATE_FX_RATES, VIEW_FX_RATE_HISTORY, SEARCH_VIEW_TRANSACTIONS, VIEW_LOCKED_RATE_POOL_VALUES, VIEW_PREFUNDING_BALANCE, RECORD_TOP_UP, CREATE_MANUAL_ADJUSTMENT, INITIATE_REFUND, VIEW_REFUND_STATUS, VIEW_SETTLEMENT_BATCHES, EXPORT_REVENUE_REPORT, MANAGE_USERS_AND_ROLES, VIEW_AUDIT_LOG, EXPORT_AUDIT_LOG. permissions JSONB stores a set of these keys each with boolean true.
**Steps:** Create Java enum RbacPermission with one constant per action name listed in context (22 constants); Create PermissionSet value class wrapping Set<RbacPermission> with methods: has(RbacPermission), toJsonb(), fromJsonb(String); Write unit test PermissionSetTest: serialize and deserialize a set of 3 permissions and verify round-trip equality; Add Hibernate custom type or JPA AttributeConverter to map permissions JSONB column to Set<RbacPermission>
**Deliverable:** RbacPermission.java enum, PermissionSet.java value class, PermissionSetTest.java, and JPA AttributeConverter PermissionSetConverter.java
**Acceptance / logic checks:**
- RbacPermission.values().length == 22
- PermissionSet.fromJsonb('{"VIEW_DASHBOARD":true,"RECORD_TOP_UP":true}') returns a set with exactly VIEW_DASHBOARD and RECORD_TOP_UP
- PermissionSet.fromJsonb('{}') returns empty set with no exception
- Serializing a set of all 22 permissions and deserializing returns identical set
- Unknown key in JSONB (e.g. 'LEGACY_KEY') is silently ignored during deserialization (forward-compatibility)
**Depends on:** 10.10-T01

### 10.10-T04 — Seed canonical permission sets for all four hub_role rows  _(25 min)_
**Context:** PRD-07 §12.3 defines the permission matrix for 4 roles. SUPER_ADMIN has all 22 permissions. OPS_OPERATOR has: VIEW_DASHBOARD, CREATE_EDIT_SCHEME, ACTIVATE_SUSPEND_SCHEME, VIEW_SCHEME_CONFIG, CREATE_EDIT_PARTNER, VIEW_API_CREDENTIALS, GENERATE_REVOKE_API_CREDENTIALS, CREATE_EDIT_RULE, UPDATE_FX_RATES, VIEW_FX_RATE_HISTORY, SEARCH_VIEW_TRANSACTIONS, VIEW_LOCKED_RATE_POOL_VALUES, VIEW_PREFUNDING_BALANCE, RECORD_TOP_UP, INITIATE_REFUND, VIEW_REFUND_STATUS, VIEW_SETTLEMENT_BATCHES, EXPORT_REVENUE_REPORT, VIEW_AUDIT_LOG. FINANCE_ANALYST has: VIEW_DASHBOARD, VIEW_FX_RATE_HISTORY, SEARCH_VIEW_TRANSACTIONS, VIEW_LOCKED_RATE_POOL_VALUES, VIEW_PREFUNDING_BALANCE, CREATE_MANUAL_ADJUSTMENT, VIEW_REFUND_STATUS, VIEW_SETTLEMENT_BATCHES, EXPORT_REVENUE_REPORT. ADMIN_VIEWER has: VIEW_DASHBOARD, VIEW_FX_RATE_HISTORY, SEARCH_VIEW_TRANSACTIONS, VIEW_REFUND_STATUS.
**Steps:** Create Flyway migration V10_10_003__seed_role_permissions.sql; For each of the 4 hub_role rows UPDATE permissions = '<jsonb>' WHERE role_code='<code>'; Build the JSONB literal for each role from the exact permission lists in context; Add a CHECK or comment for the SUPER_ADMIN assertion (all 22 keys present)
**Deliverable:** Flyway migration V10_10_003__seed_role_permissions.sql populating hub_role.permissions for all 4 roles
**Acceptance / logic checks:**
- After migration, SUPER_ADMIN permissions JSONB contains exactly 22 keys all set to true
- OPS_OPERATOR does NOT have MANAGE_USERS_AND_ROLES or EXPORT_AUDIT_LOG or CREATE_MANUAL_ADJUSTMENT keys
- FINANCE_ANALYST does NOT have CREATE_EDIT_SCHEME, CREATE_EDIT_PARTNER, or MANAGE_USERS_AND_ROLES
- ADMIN_VIEWER has exactly 4 permissions: VIEW_DASHBOARD, VIEW_FX_RATE_HISTORY, SEARCH_VIEW_TRANSACTIONS, VIEW_REFUND_STATUS
- Migration is idempotent: running it twice on the same DB produces no error and same result
**Depends on:** 10.10-T03

### 10.10-T05 — Implement HubUserRepository and HubRoleRepository (JPA)  _(40 min)_
**Context:** Hub services need CRUD access to hub_user and hub_role tables. hub_user fields include email, name, role_id (FK), is_active, password_hash, must_change_password, failed_login_count, locked_until, last_login_at, created_by. hub_role fields: role_code, permissions (JSONB mapped via PermissionSetConverter from T03). Use Spring Data JPA repositories. Password hash stored using BCrypt (strength 12). Entity classes must use the PermissionSetConverter from T03.
**Steps:** Create HubRole JPA entity mapping hub_role columns; use @Convert(converter=PermissionSetConverter.class) for permissions; Create HubUser JPA entity mapping hub_user columns; use @ManyToOne to HubRole; Create HubRoleRepository extends JpaRepository<HubRole,Long> with findByRoleCode(String roleCode); Create HubUserRepository extends JpaRepository<HubUser,Long> with findByEmail(String email), findAllByIsActiveTrue(), findAllByRoleId(Long roleId); Write RepositoryIT integration test: save a user, reload it, verify role permissions round-trip via PermissionSetConverter
**Deliverable:** HubRole.java, HubUser.java entity classes; HubRoleRepository.java; HubUserRepository.java; RepositoryIT.java
**Acceptance / logic checks:**
- HubRoleRepository.findByRoleCode('SUPER_ADMIN') returns a non-empty Optional
- HubUserRepository.findByEmail on the bootstrap user returns correct entity with role SUPER_ADMIN
- Saving a new HubUser with must_change_password=true and reloading returns must_change_password=true
- HubUser.role.permissions.has(RbacPermission.VIEW_DASHBOARD) is true for SUPER_ADMIN and false for a role with empty permissions
- Integration test passes against real embedded H2 or Testcontainers Postgres
**Depends on:** 10.10-T03, 10.10-T04

### 10.10-T06 — Implement AuthenticationService: login, lockout, session JWT issuance  _(55 min)_
**Context:** SEC-09 §3.1 specifies Admin Portal auth: max 5 consecutive failures lock the account (locked_until set by IT admin unlock, not timed); session duration 8 hours max; idle timeout 30 minutes (enforced by frontend inactivity + backend token TTL). Phase 1 uses local portal accounts (SSO is Phase 2). JWT tokens: internal service JWTs have 1-hour TTL (SEC-09 §2.7) but portal sessions use 8h max / 30min idle pattern: issue short-lived access token (30min) + refresh token (8h). On login: verify BCrypt password, check is_active, check locked_until IS NULL, reset failed_login_count=0 on success, increment on failure (lock account when count reaches 5). Audit event: AUTH_LOGIN_SUCCESS or AUTH_LOGIN_FAILED.
**Steps:** Create AuthenticationService.login(email, password) returning TokenPair(accessToken, refreshToken); Implement lockout logic: if failed_login_count >= 5 set locked_until = 'infinity' and throw AccountLockedException; On success: set last_login_at=now(), failed_login_count=0; Issue JWT access token (sub=userId, role=role_code, permissions=Set<RbacPermission>, exp=now+30min, jti=UUID); Issue opaque refresh token (UUID stored hashed in hub_user.refresh_token_hash, exp=now+8h); Publish AuditEvent(AUTH_LOGIN_SUCCESS or AUTH_LOGIN_FAILED, actor_id, ip_address) via application event
**Deliverable:** AuthenticationService.java with login(), unlockUser() methods and AccountLockedException; JWT claims documented inline
**Acceptance / logic checks:**
- Correct password returns TokenPair with non-null accessToken and refreshToken
- Wrong password 5 times sets hub_user.locked_until to a non-null value and 6th attempt throws AccountLockedException without further incrementing count
- Correct password after account is locked throws AccountLockedException regardless of credentials
- Decoded JWT contains role_code and at least one permission from the user's role
- must_change_password=true is included as a claim in the JWT so the frontend can force the change flow
**Depends on:** 10.10-T05

### 10.10-T07 — Implement RbacFilter: JWT extraction and SecurityContext population  _(45 min)_
**Context:** Every Admin Portal API request must carry a valid JWT access token (Authorization: Bearer <token>). The filter decodes and validates the JWT (signature, expiry), extracts userId, role_code, and Set<RbacPermission> from claims, and populates Spring SecurityContext with a HubPrincipal. No DB lookup on every request; all needed data is in the JWT. Return HTTP 401 on missing/invalid/expired token. Token is issued by AuthenticationService (T06) and signed with an HMAC-SHA256 secret loaded from application config (never hardcoded).
**Steps:** Create HubPrincipal implementing Authentication with userId, roleCode, Set<RbacPermission>; Create JwtRbacFilter extends OncePerRequestFilter: extract Bearer token, validate with JJWT, build HubPrincipal, set in SecurityContext; Register filter in Spring Security filter chain before UsernamePasswordAuthenticationFilter; Return 401 JSON {error:'UNAUTHORIZED'} on missing/invalid token; return 401 {error:'TOKEN_EXPIRED'} on expiry; Exempt /api/admin/auth/login and /api/admin/auth/refresh from filter
**Deliverable:** HubPrincipal.java, JwtRbacFilter.java, and SecurityConfig.java wiring the filter
**Acceptance / logic checks:**
- Request with valid token populates SecurityContext with correct userId and permissions
- Request with no Authorization header returns HTTP 401 with error code UNAUTHORIZED
- Request with expired token (exp in the past) returns HTTP 401 with error code TOKEN_EXPIRED
- Request with a tampered token signature returns HTTP 401
- Request to /api/admin/auth/login passes through without requiring a token
**Depends on:** 10.10-T06

### 10.10-T08 — Implement @RequiresPermission annotation and PermissionEnforcer AOP aspect  _(40 min)_
**Context:** Controller methods need declarative RBAC enforcement. Design: @RequiresPermission(RbacPermission.MANAGE_USERS_AND_ROLES) on a method causes the AOP aspect to check HubPrincipal.permissions.has(required) before invocation. If check fails: throw AccessDeniedException which maps to HTTP 403 {error:'FORBIDDEN', requiredPermission:'MANAGE_USERS_AND_ROLES'}. Use Spring AOP @Around. HubPrincipal is retrieved from SecurityContextHolder (populated by JwtRbacFilter in T07).
**Steps:** Create @RequiresPermission annotation with RbacPermission value(); Create PermissionEnforcerAspect @Aspect @Component with @Around advice targeting methods annotated with @RequiresPermission; Extract HubPrincipal from SecurityContextHolder; throw AccessDeniedException if principal is null or permissions check fails; Create GlobalExceptionHandler mapping AccessDeniedException to 403 response body {error:'FORBIDDEN',requiredPermission:'<name>'}; Write unit test PermissionEnforcerTest: mock principal with OPS_OPERATOR permissions; call method requiring MANAGE_USERS_AND_ROLES; assert 403
**Deliverable:** RequiresPermission.java annotation, PermissionEnforcerAspect.java, AccessDeniedException.java, and PermissionEnforcerTest.java
**Acceptance / logic checks:**
- Method annotated @RequiresPermission(MANAGE_USERS_AND_ROLES) called with SUPER_ADMIN principal proceeds without exception
- Same method called with OPS_OPERATOR principal throws AccessDeniedException
- AccessDeniedException maps to HTTP 403 response with requiredPermission field set to 'MANAGE_USERS_AND_ROLES'
- Unauthenticated call (no principal in context) throws AccessDeniedException with error UNAUTHORIZED
- Method with no annotation is never intercepted by the aspect (verified via invocation count)
**Depends on:** 10.10-T07

### 10.10-T09 — Implement UserManagementService: create user, assign role, deactivate, reactivate  _(50 min)_
**Context:** PRD-07 §12.4: only SUPER_ADMIN can manage users. Operations: (a) create user with email, name, role_code -> generates temporary password (UUID-based), sets must_change_password=true, hashes with BCrypt strength 12; (b) assign a different role to an existing user; (c) deactivate user (is_active=false, invalidate sessions); (d) reactivate user (is_active=true, reset locked_until=NULL, failed_login_count=0). All operations must emit an audit event: USER_CREATED, USER_ROLE_ASSIGNED, USER_DEACTIVATED, USER_REACTIVATED. A user cannot deactivate themselves. SUPER_ADMIN is the only role that can perform these operations (enforced via @RequiresPermission(MANAGE_USERS_AND_ROLES) from T08).
**Steps:** Create UserManagementService with methods: createUser(email, name, roleCode, createdBy), assignRole(userId, roleCode, actorId), deactivateUser(userId, actorId), reactivateUser(userId, actorId); In createUser: validate email format and uniqueness (throw DuplicateEmailException if exists), generate temp password, BCrypt hash, set must_change_password=true; In deactivateUser: throw SelfDeactivationException if userId==actorId; Emit AuditEvent for each operation with actor_id, entity_type='hub_user', entity_id, event_type code, previous_value/new_value JSON; Annotate each service method with @RequiresPermission(MANAGE_USERS_AND_ROLES)
**Deliverable:** UserManagementService.java with 4 operations plus DuplicateEmailException.java and SelfDeactivationException.java
**Acceptance / logic checks:**
- createUser with duplicate email throws DuplicateEmailException; no DB row is written
- Created user has must_change_password=true and password_hash is BCrypt-encoded (starts with '$2a$12$')
- deactivateUser where actorId==userId throws SelfDeactivationException
- reactivateUser clears locked_until=NULL and failed_login_count=0
- Each of the 4 operations fires exactly one AuditEvent with correct event_type code
**Depends on:** 10.10-T08

### 10.10-T10 — Implement ForcePasswordChange flow: validate and update password  _(45 min)_
**Context:** PRD-07 §12.4: new users are created with a temporary password and must_change_password=true. SEC-09 §3.2 password policy (applied to all portal users): minimum 12 characters; must contain at least one uppercase, one lowercase, one digit, one special character; cannot reuse the previous password. On successful change: set must_change_password=false, update password_hash (BCrypt strength 12), revoke all existing refresh tokens for the user. JWT claim must_change_password=true forces the frontend to the change-password screen before any other action. Emit audit event PASSWORD_CHANGED.
**Steps:** Create PasswordChangeService.changePassword(userId, currentPassword, newPassword) method; Validate: currentPassword matches stored hash (BCrypt verify); newPassword != currentPassword (no reuse check against stored hash); newPassword passes complexity rule (regex: (?=.*[A-Z])(?=.*[a-z])(?=.*\d)(?=.*[^A-Za-z0-9]).{12,}); Update password_hash with BCrypt strength 12; set must_change_password=false; nullify refresh_token_hash; Emit AuditEvent PASSWORD_CHANGED with actor=userId; Create PasswordChangeController POST /api/admin/auth/password-change; allow unauthenticated but require valid access-token with must_change_password=true claim OR authenticated session
**Deliverable:** PasswordChangeService.java, PasswordChangeController.java, and PasswordPolicyValidator.java
**Acceptance / logic checks:**
- Password 'abc' (11 chars, no complexity) is rejected with INVALID_PASSWORD_POLICY
- Password equal to current password is rejected with PASSWORD_REUSE_DENIED
- Valid new password updates password_hash and sets must_change_password=false
- After successful change, old access token with must_change_password=true claim is still technically valid for the 30-min TTL but refresh token is revoked (refresh attempt returns 401)
- Audit event PASSWORD_CHANGED is emitted exactly once per successful change
**Depends on:** 10.10-T06, 10.10-T09

### 10.10-T11 — Implement force-password-reset by Super Admin  _(35 min)_
**Context:** PRD-07 §12.4: Super Admin can force a password reset for any user. This sets must_change_password=true and generates a new temporary password (UUID-based), invalidates all existing sessions by nullifying refresh_token_hash. The temporary password should be returned once in the API response for the Super Admin to communicate to the user out-of-band (it is hashed immediately; never stored in plain text in DB). Emit audit event PASSWORD_RESET_FORCED with actor=superAdminId and entity=targetUserId.
**Steps:** Add UserManagementService.forcePasswordReset(targetUserId, actorId) method; Generate UUID-based temp password; BCrypt-hash it; store hash; set must_change_password=true; nullify refresh_token_hash; Return plain temp password as a one-time value in the service return object; Annotate with @RequiresPermission(MANAGE_USERS_AND_ROLES); Add endpoint POST /api/admin/users/{userId}/force-password-reset returning {temporaryPassword: '...'} in response body; Emit AuditEvent PASSWORD_RESET_FORCED
**Deliverable:** Extended UserManagementService.forcePasswordReset() and endpoint in UserManagementController.java
**Acceptance / logic checks:**
- After forcePasswordReset, old refresh token returns 401 on token refresh attempt
- must_change_password is true after the call
- Returned temporaryPassword matches the BCrypt-stored hash (verify with BCrypt.checkpw)
- Calling endpoint as OPS_OPERATOR returns HTTP 403
- Audit event PASSWORD_RESET_FORCED logged with correct actor_id and entity_id
**Depends on:** 10.10-T09, 10.10-T10

### 10.10-T12 — Implement unlock-user endpoint (IT Admin / Super Admin clears lockout)  _(30 min)_
**Context:** SEC-09 §3.1: 5 consecutive login failures lock the account (locked_until set to infinity). Only Super Admin can unlock. Unlocking: set locked_until=NULL, failed_login_count=0. Emit audit event USER_UNLOCKED with actor and target user IDs. A Super Admin may not lock themselves out inadvertently via this path (already prevented by deactivate guard; unlock itself is always safe). Endpoint: POST /api/admin/users/{userId}/unlock.
**Steps:** Add UserManagementService.unlockUser(targetUserId, actorId) method; Set locked_until=NULL and failed_login_count=0 on the target hub_user row; Annotate with @RequiresPermission(MANAGE_USERS_AND_ROLES); Emit AuditEvent USER_UNLOCKED; Add POST /api/admin/users/{userId}/unlock endpoint returning 200 {status:'unlocked'}; Verify idempotency: calling unlock on a non-locked user succeeds without error
**Deliverable:** unlockUser() method in UserManagementService.java and unlock endpoint in UserManagementController.java
**Acceptance / logic checks:**
- After 5 failed logins the user is locked; calling unlock allows 6th login attempt to succeed if password is correct
- Unlock called by OPS_OPERATOR returns HTTP 403
- Unlock on an already-unlocked user returns HTTP 200 (idempotent)
- locked_until and failed_login_count are both NULL/0 after unlock
- AuditEvent USER_UNLOCKED is emitted with actor_id and entity_id
**Depends on:** 10.10-T09

### 10.10-T13 — Implement GET /api/admin/users list and GET /api/admin/users/{id} detail  _(35 min)_
**Context:** PRD-07 §12.4: Super Admin can view all users including last_login_at and session history per user. Response should include: id, email, name, role_code, is_active, last_login_at, created_at, created_by_name. Sensitive fields NOT returned: password_hash, refresh_token_hash, failed_login_count, locked_until (omit from response DTO). List endpoint supports optional filters: ?is_active=true|false&role_code=OPS_OPERATOR. Both endpoints require @RequiresPermission(MANAGE_USERS_AND_ROLES).
**Steps:** Create UserResponseDto with fields: id, email, name, roleCode, isActive, lastLoginAt, createdAt, createdByName; Create UserManagementController GET /api/admin/users with optional query params is_active and role_code; map to HubUserRepository query; Create GET /api/admin/users/{id} returning single UserResponseDto or 404 if not found; Annotate both with @RequiresPermission(MANAGE_USERS_AND_ROLES); Write controller slice test: mock service; verify password_hash field is absent from JSON response
**Deliverable:** UserManagementController.java list+detail endpoints and UserResponseDto.java
**Acceptance / logic checks:**
- GET /api/admin/users with OPS_OPERATOR token returns HTTP 403
- GET /api/admin/users returns all 4 seed users when called as SUPER_ADMIN (bootstrap + 3 test users)
- Response JSON does NOT contain password_hash, refresh_token_hash, failed_login_count, or locked_until fields
- GET /api/admin/users?role_code=FINANCE_ANALYST returns only Finance Analyst users
- GET /api/admin/users/{nonExistentId} returns HTTP 404
**Depends on:** 10.10-T09

### 10.10-T14 — Implement POST /api/admin/users (create user) and PUT /api/admin/users/{id}/role (assign role)  _(40 min)_
**Context:** PRD-07 §12.4: Super Admin creates new portal users with email, name, and role_code. A temporary password is returned once in the response. Super Admin can also reassign a user to a different role. Request body for create: {email, name, roleCode}. Request body for role assign: {roleCode}. Both require @RequiresPermission(MANAGE_USERS_AND_ROLES). Audit events USER_CREATED and USER_ROLE_ASSIGNED must fire (implemented in UserManagementService T09). Return 409 on duplicate email. Role assignment to a non-existent roleCode returns 400.
**Steps:** Add POST /api/admin/users endpoint calling UserManagementService.createUser(); return 201 {userId, email, name, roleCode, temporaryPassword}; Add PUT /api/admin/users/{id}/role endpoint calling UserManagementService.assignRole(); return 200 {userId, roleCode}; Validate request body with @Valid: email must be RFC-5321 format; name 1-200 chars; roleCode must be one of 4 canonical values; Return 409 {error:'DUPLICATE_EMAIL'} when email already exists; Return 400 {error:'INVALID_ROLE_CODE'} when roleCode is not a known role
**Deliverable:** Create-user and assign-role endpoints in UserManagementController.java with @Valid request DTOs
**Acceptance / logic checks:**
- POST with valid body returns 201 and temporaryPassword is a non-empty string
- POST with email='not-an-email' returns 400 with field validation error
- POST with existing email returns 409 with error code DUPLICATE_EMAIL
- PUT /api/admin/users/{id}/role with roleCode='UNKNOWN' returns 400
- PUT with OPS_OPERATOR token returns 403
**Depends on:** 10.10-T09, 10.10-T13

### 10.10-T15 — Implement POST /api/admin/users/{id}/deactivate and /reactivate endpoints  _(30 min)_
**Context:** PRD-07 §12.4: Super Admin can deactivate (is_active=false) and reactivate (is_active=true) user accounts. Deactivating a user also invalidates their active sessions by nullifying refresh_token_hash. A Super Admin cannot deactivate themselves. Reactivation clears locked_until and failed_login_count. Both operations require @RequiresPermission(MANAGE_USERS_AND_ROLES). Audit events USER_DEACTIVATED and USER_REACTIVATED must be emitted (implemented in UserManagementService T09). Deactivating an already-inactive user is idempotent (200 OK, no error).
**Steps:** Add POST /api/admin/users/{id}/deactivate calling UserManagementService.deactivateUser(userId, actorId); Add POST /api/admin/users/{id}/reactivate calling UserManagementService.reactivateUser(userId, actorId); Map SelfDeactivationException to HTTP 422 {error:'CANNOT_DEACTIVATE_SELF'}; Both endpoints return 200 {userId, isActive} after success; Add 404 guard if userId does not exist
**Deliverable:** Deactivate and reactivate endpoints in UserManagementController.java
**Acceptance / logic checks:**
- Deactivating an active user returns 200 and is_active becomes false in DB
- After deactivation, login attempt returns 401 (is_active check in AuthenticationService)
- Deactivate where actorId==userId returns HTTP 422 with error CANNOT_DEACTIVATE_SELF
- Reactivate restores is_active=true and clears locked_until
- Deactivating an already-inactive user returns 200 (idempotent)
**Depends on:** 10.10-T09, 10.10-T13

### 10.10-T16 — Implement token refresh endpoint POST /api/admin/auth/refresh  _(50 min)_
**Context:** Session design (T06): access tokens expire in 30 minutes; refresh tokens expire in 8 hours (SEC-09 §3.1 8h max session). POST /api/admin/auth/refresh accepts {refreshToken: '<opaque-uuid>'}, validates it against the hashed value in hub_user.refresh_token_hash (BCrypt), checks is_active=true and locked_until IS NULL, checks refresh token not older than 8h (stored as refresh_token_issued_at TIMESTAMPTZ on hub_user). On success: issues a new access token (30min) and rotates refresh token (new UUID, rehash, update DB). On failure: return 401.
**Steps:** Add refresh_token_hash VARCHAR(255) and refresh_token_issued_at TIMESTAMPTZ columns to hub_user via migration V10_10_004__add_refresh_token_cols.sql; Implement TokenRefreshService.refresh(rawRefreshToken) method: lookup user by finding matching hash, validate age (now - refresh_token_issued_at < 8h), issue new access JWT, rotate refresh token; Return 401 {error:'INVALID_REFRESH_TOKEN'} if hash not found or age exceeded; Return 401 {error:'ACCOUNT_INACTIVE'} if is_active=false; Create POST /api/admin/auth/refresh endpoint (no auth filter required)
**Deliverable:** Migration V10_10_004, TokenRefreshService.java, and refresh endpoint in AuthController.java
**Acceptance / logic checks:**
- Valid refresh token within 8h returns new accessToken and new refreshToken
- Using the old refreshToken after rotation returns 401 INVALID_REFRESH_TOKEN (single-use rotation)
- Refresh token older than 8h (refresh_token_issued_at + 8h < now) returns 401
- Refresh token belonging to a deactivated user returns 401 ACCOUNT_INACTIVE
- New access token contains correct role and permissions for the user
**Depends on:** 10.10-T06, 10.10-T07

### 10.10-T17 — Implement POST /api/admin/auth/logout endpoint  _(25 min)_
**Context:** Logging out invalidates the session by nullifying hub_user.refresh_token_hash. The access token (JWT) cannot be server-side revoked (stateless); it expires naturally within 30 minutes. Logout must accept the current valid access token (Bearer) and nullify refresh_token_hash for the authenticated user (extracted from JWT sub claim). Emit audit event AUTH_LOGOUT. After logout, token refresh returns 401. Phase 1 local accounts only; SSO logout is Phase 2.
**Steps:** Create POST /api/admin/auth/logout endpoint; protected by JwtRbacFilter (requires valid access token); Extract userId from HubPrincipal in SecurityContext; Set hub_user.refresh_token_hash=NULL for that userId; Emit AuditEvent AUTH_LOGOUT with actor_id and ip_address; Return 204 No Content on success
**Deliverable:** Logout endpoint in AuthController.java
**Acceptance / logic checks:**
- POST /api/admin/auth/logout with valid token returns 204 and clears refresh_token_hash in DB
- Subsequent POST /api/admin/auth/refresh with the old refresh token returns 401
- Logout with no token returns 401 (filter rejects)
- AuditEvent AUTH_LOGOUT is emitted with the correct actor userId
- Second logout call (after first already cleared token hash) returns 204 (idempotent)
**Depends on:** 10.10-T16

### 10.10-T18 — Implement Admin Portal permission guard: Users & Roles module visibility  _(25 min)_
**Context:** PRD-07 §15.9 acceptance: OPS_OPERATOR cannot access the Users & Roles module; FINANCE_ANALYST and ADMIN_VIEWER also cannot. Only SUPER_ADMIN (with MANAGE_USERS_AND_ROLES permission) can see and access this module. Backend: all /api/admin/users/** and /api/admin/roles/** routes already require @RequiresPermission(MANAGE_USERS_AND_ROLES) (T08-T15). Frontend: the nav item 'Users & Roles' must be conditionally rendered based on permissions in the JWT. This ticket implements a backend endpoint the frontend uses to determine which nav items to show.
**Steps:** Create GET /api/admin/me endpoint returning {userId, email, name, roleCode, permissions:[...]} from current HubPrincipal; This endpoint is protected by JwtRbacFilter (any authenticated user can call it); Response permissions field is the array of RbacPermission names the user holds; Frontend will use permissions.includes('MANAGE_USERS_AND_ROLES') to decide nav visibility; Write a controller test asserting ADMIN_VIEWER token returns permissions without MANAGE_USERS_AND_ROLES
**Deliverable:** GET /api/admin/me endpoint in AuthController.java returning current user profile and permissions list
**Acceptance / logic checks:**
- SUPER_ADMIN token returns permissions array containing MANAGE_USERS_AND_ROLES
- OPS_OPERATOR token returns permissions array NOT containing MANAGE_USERS_AND_ROLES
- ADMIN_VIEWER token returns permissions array with exactly VIEW_DASHBOARD, VIEW_FX_RATE_HISTORY, SEARCH_VIEW_TRANSACTIONS, VIEW_REFUND_STATUS
- Unauthenticated request returns 401
- Response contains roleCode field matching the user's actual role_code from DB
**Depends on:** 10.10-T07, 10.10-T13

### 10.10-T19 — Implement view-only credential masking: ADMIN_VIEWER cannot see pool values or API credentials  _(40 min)_
**Context:** PRD-07 §15.9: ADMIN_VIEWER can search transactions but cannot see locked pool values or API credentials. This is enforced at the service/controller layer, not just UI. Pool value fields (collection_usd, payout_usd_cost, collection_margin_usd, payout_margin_usd, offer_rate_coll, cross_rate) must be omitted or replaced with null in transaction detail responses when the caller lacks VIEW_LOCKED_RATE_POOL_VALUES. API credential fields (api_key, api_secret_hash) must be omitted from partner detail responses when caller lacks VIEW_API_CREDENTIALS.
**Steps:** Create ResponseMaskingService with maskTransactionDetail(TransactionDetailDto dto, Set<RbacPermission> perms) and maskPartnerDetail(PartnerDetailDto dto, Set<RbacPermission> perms); In maskTransactionDetail: if perms does not contain VIEW_LOCKED_RATE_POOL_VALUES, set collection_usd, payout_usd_cost, collection_margin_usd, payout_margin_usd, offer_rate_coll, cross_rate to null; In maskPartnerDetail: if perms does not contain VIEW_API_CREDENTIALS, set api_key and api_secret_hash to null; Inject ResponseMaskingService into TransactionController and PartnerController; call masking after fetching data; Write unit test with 4 scenarios: SUPER_ADMIN sees all fields; ADMIN_VIEWER sees null pool values and null credentials
**Deliverable:** ResponseMaskingService.java and unit test ResponseMaskingServiceTest.java
**Acceptance / logic checks:**
- ADMIN_VIEWER calling GET /api/admin/transactions/{id} receives response where collection_usd is null
- FINANCE_ANALYST calling GET /api/admin/transactions/{id} receives response where collection_usd is non-null (FINANCE_ANALYST has VIEW_LOCKED_RATE_POOL_VALUES)
- ADMIN_VIEWER calling GET /api/admin/partners/{id} receives response where api_key is null
- OPS_OPERATOR calling GET /api/admin/partners/{id} receives api_key non-null
- Masking does not alter the underlying DB entity; re-fetching returns full data for SUPER_ADMIN
**Depends on:** 10.10-T08

### 10.10-T20 — Implement AuditEventService and admin_audit_log table  _(45 min)_
**Context:** PRD-07 §13 and DAT-03: audit_log table stores immutable append-only entries. Fields per §13.3: id UUID PK, occurred_at TIMESTAMPTZ ms precision, actor_user_id BIGINT FK hub_user, actor_display_name VARCHAR(200), actor_ip_address INET, event_type VARCHAR(60) (e.g. USER_CREATED, AUTH_LOGIN_FAILED), entity_type VARCHAR(40) (hub_user, hub_role, partner, scheme, rule, etc.), entity_id VARCHAR(64), previous_value JSONB, new_value JSONB, description TEXT, metadata JSONB. No UPDATE or DELETE permitted (application service account has INSERT-only permission on this table per DAT-03 §9.1).
**Steps:** Create Flyway migration V10_10_005__create_admin_audit_log.sql with table DDL; no FK enforcement on actor_user_id to allow system/bootstrap events without a user row; Create AuditLogEntry JPA entity (insertable=true, updatable=false for all columns to enforce immutability at ORM layer); Create AuditEventService.log(AuditEventDto) method that persists via a dedicated EntityManager with INSERT-only semantics; Define AuditEventDto record with all fields from context; Consume Spring ApplicationEvents (published by T09/T10/etc.) and delegate to AuditEventService in an @EventListener
**Deliverable:** Migration V10_10_005, AuditLogEntry.java entity, AuditEventService.java, AuditEventDto.java
**Acceptance / logic checks:**
- INSERT into admin_audit_log succeeds; subsequent UPDATE raises an exception (ORM @Column(updatable=false) guard)
- AuditEventService.log() with AUTH_LOGIN_FAILED event results in a row with correct event_type in DB
- ApplicationEvent published by UserManagementService.createUser() results in a persisted audit row within the same transaction
- occurred_at stored with millisecond precision (not second)
- Attempting to delete a row via AuditEventService throws UnsupportedOperationException
**Depends on:** 10.10-T02

### 10.10-T21 — Implement audit log search endpoint GET /api/admin/audit-log  _(45 min)_
**Context:** PRD-07 §13.5: audit log is searchable by actor (user ID), event_type, entity_type, entity_id, date range (required; max 12-month window per query), ip_address. Results paginated at 100 per page. Requires @RequiresPermission(VIEW_AUDIT_LOG) - only SUPER_ADMIN and OPS_OPERATOR have this permission. Date range is required: reject if start_date or end_date missing; reject if range exceeds 366 days. Max 100 rows per page; page param (0-based).
**Steps:** Create AuditLogSearchRequest DTO with fields: actorUserId (Long, optional), eventType (String, optional), entityType (String, optional), entityId (String, optional), startDate (LocalDate, required), endDate (LocalDate, required), ipAddress (String, optional), page (int default 0); Create AuditLogRepository with dynamic query using JPA Specification or QueryDSL; Create GET /api/admin/audit-log endpoint; validate date range present and <= 366 days; annotate @RequiresPermission(VIEW_AUDIT_LOG); Return paginated response {content:[...], totalElements, page, pageSize:100}; Reject requests without startDate/endDate with 400 {error:'DATE_RANGE_REQUIRED'}
**Deliverable:** AuditLogRepository.java with search, AuditLogController.java with GET endpoint, AuditLogSearchRequest.java
**Acceptance / logic checks:**
- Request without startDate returns HTTP 400 DATE_RANGE_REQUIRED
- Request with date range of 400 days returns HTTP 400 DATE_RANGE_EXCEEDS_MAX
- ADMIN_VIEWER token returns HTTP 403 (lacks VIEW_AUDIT_LOG)
- OPS_OPERATOR token with valid date range returns 200 with paginated results
- Filter by entity_type='hub_user' returns only user-management events
**Depends on:** 10.10-T20, 10.10-T08

### 10.10-T22 — Implement audit log CSV export POST /api/admin/audit-log/export  _(50 min)_
**Context:** PRD-07 §13.5: CSV export of all matching audit log entries; up to 500,000 rows. Exports up to 30-day window complete synchronously within 30 seconds; windows > 30 days are queued as async jobs with email notification when ready. Requires @RequiresPermission(EXPORT_AUDIT_LOG) - only SUPER_ADMIN has this. CSV columns: id, occurred_at, actor_display_name, actor_ip_address, event_type, entity_type, entity_id, previous_value (JSON string), new_value (JSON string), description. Synchronous response: Content-Disposition: attachment; filename=audit_log_<start>_<end>.csv.
**Steps:** Create AuditLogExportService.export(AuditLogSearchRequest request) that streams CSV rows using JPA ScrollableResults or JdbcTemplate streaming; For window <= 30 days: stream directly to HTTP response as application/octet-stream with Content-Disposition header; For window > 30 days: queue an AsyncExportJob (store in DB table audit_export_job), return 202 {jobId, message:'Export queued; you will receive an email when ready'}; Annotate endpoint POST /api/admin/audit-log/export with @RequiresPermission(EXPORT_AUDIT_LOG); Write a test that exports 10 seed audit rows and validates CSV column headers and row count
**Deliverable:** AuditLogExportService.java, export endpoint in AuditLogController.java, CSV column spec
**Acceptance / logic checks:**
- Export with date range of 7 days returns HTTP 200 with Content-Disposition header containing 'attachment'
- CSV first row contains expected headers: id,occurred_at,actor_display_name,actor_ip_address,event_type,entity_type,entity_id,previous_value,new_value,description
- Export with date range > 30 days returns HTTP 202 with jobId in response body
- OPS_OPERATOR token returns HTTP 403 (lacks EXPORT_AUDIT_LOG)
- Export of 10 seed rows produces CSV with exactly 11 lines (1 header + 10 data rows)
**Depends on:** 10.10-T21

### 10.10-T23 — Unit tests: AuthenticationService login and lockout logic  _(40 min)_
**Context:** Test the lockout and session logic of AuthenticationService (T06) with exact input/output scenarios. Use JUnit 5 + Mockito; no real DB. Scenarios: (a) correct credentials, (b) wrong password increments failed_login_count, (c) 5th failure locks account (locked_until != null), (d) locked account rejects even with correct password, (e) unlocked account (T12) allows login again, (f) inactive account (is_active=false) rejects login with ACCOUNT_INACTIVE error.
**Steps:** Create AuthenticationServiceTest.java with 6 test methods as described in context; Mock HubUserRepository to return pre-configured HubUser stubs; Assert failed_login_count increments on each wrong password call; Assert locked_until is non-null after the 5th consecutive failure; Assert that a 6th call with correct password still throws AccountLockedException (account remains locked); Assert is_active=false user throws AccountInactiveException regardless of password
**Deliverable:** AuthenticationServiceTest.java with 6 named test methods covering all lockout scenarios
**Acceptance / logic checks:**
- Test (a) correctCredentials: returns TokenPair with non-null accessToken
- Test (b) wrongPasswordOnce: hub_user.failed_login_count==1 after call
- Test (c) fiveConsecutiveFailures: hub_user.locked_until is non-null
- Test (d) lockedAccountCorrectPassword: throws AccountLockedException
- Test (e) after unlockUser() sets locked_until=null and failed_login_count=0: correct login succeeds
- Test (f) inactiveUser: throws AccountInactiveException
**Depends on:** 10.10-T06, 10.10-T12

### 10.10-T24 — Unit tests: PermissionEnforcer - all 4 roles against permission matrix  _(45 min)_
**Context:** Systematically verify that every role-permission combination from PRD-07 §12.3 is correctly enforced. 22 permissions x 4 roles = 88 combinations. Test method pattern: given a HubPrincipal with role X, call PermissionEnforcerAspect check for permission Y, assert granted or denied. Key boundary cases: FINANCE_ANALYST has CREATE_MANUAL_ADJUSTMENT but not RECORD_TOP_UP; OPS_OPERATOR has RECORD_TOP_UP but not CREATE_MANUAL_ADJUSTMENT; ADMIN_VIEWER has VIEW_REFUND_STATUS but not VIEW_LOCKED_RATE_POOL_VALUES or VIEW_PREFUNDING_BALANCE.
**Steps:** Create PermissionMatrixTest.java; Build a parameterized test @ParameterizedTest @MethodSource with tuples of (roleCode, permission, expectedGranted); Source method returns all 88 role-permission pairs from the canonical matrix in T04; For each tuple: create HubPrincipal with the role's canonical permissions, invoke aspect check, assert granted or denied; Flag any tuple where expected != actual as a test failure with descriptive message
**Deliverable:** PermissionMatrixTest.java with 88 parameterized test cases derived from the canonical matrix
**Acceptance / logic checks:**
- FINANCE_ANALYST + CREATE_MANUAL_ADJUSTMENT -> granted
- FINANCE_ANALYST + RECORD_TOP_UP -> denied
- OPS_OPERATOR + MANAGE_USERS_AND_ROLES -> denied
- ADMIN_VIEWER + VIEW_LOCKED_RATE_POOL_VALUES -> denied
- SUPER_ADMIN + any of the 22 permissions -> all 22 granted
**Depends on:** 10.10-T08, 10.10-T04

### 10.10-T25 — Unit tests: UserManagementService create, assign role, deactivate, reactivate  _(45 min)_
**Context:** Test UserManagementService (T09) with Mockito-mocked repositories. Verify: (a) createUser persists a user with BCrypt hash and must_change_password=true; (b) createUser with duplicate email throws DuplicateEmailException and does NOT call repository.save(); (c) assignRole updates role_id; (d) deactivateUser with actorId==userId throws SelfDeactivationException; (e) deactivate sets is_active=false and nullifies refresh_token_hash; (f) reactivate clears locked_until and failed_login_count; (g) all 4 operations fire exactly one AuditEvent each.
**Steps:** Create UserManagementServiceTest.java with 7 test methods as listed in context; Mock HubUserRepository and HubRoleRepository; mock ApplicationEventPublisher; In test (a): capture the saved HubUser entity and verify password_hash starts with '$2a$12$'; In test (b): stub repository.findByEmail() to return non-empty Optional; verify save() never called; In test (d): call deactivateUser(userId=5, actorId=5); assert SelfDeactivationException thrown; In tests (e)(f): verify specific field values on the captured save() argument
**Deliverable:** UserManagementServiceTest.java with 7 test methods all passing
**Acceptance / logic checks:**
- Test (a): saved user.passwordHash starts with '$2a$12$' (BCrypt strength 12 prefix)
- Test (a): saved user.mustChangePassword==true
- Test (b): repository.save() is never invoked when email is duplicate
- Test (d): SelfDeactivationException thrown when userId==actorId
- Test (g): ApplicationEventPublisher.publishEvent() called exactly once per operation with correct AuditEventDto.eventType
**Depends on:** 10.10-T09

### 10.10-T26 — Unit tests: ResponseMaskingService field nullification by role  _(35 min)_
**Context:** Test ResponseMaskingService (T19) with 4 scenarios corresponding to the 4 roles. TransactionDetailDto has fields: txnRef, status, collectionUsd, payoutUsdCost, collectionMarginUsd, payoutMarginUsd, offerRateColl, crossRate, and non-sensitive fields (partnerName, amount, currency). PartnerDetailDto has fields: partnerId, partnerName, apiKey, apiSecretHash, and non-sensitive fields. Verify masking does not touch non-sensitive fields.
**Steps:** Create ResponseMaskingServiceTest.java; Build canonical PermissionSet for each of the 4 roles (reuse PermissionSet.fromJsonb from T03 or derive from T04 seeds); Test maskTransactionDetail for each role; assert pool fields null only for ADMIN_VIEWER; non-sensitive fields (partnerName, amount) always non-null; Test maskPartnerDetail for each role; assert apiKey null for FINANCE_ANALYST and ADMIN_VIEWER; assert apiKey non-null for SUPER_ADMIN and OPS_OPERATOR; Verify masking returns a new DTO (does not mutate the input)
**Deliverable:** ResponseMaskingServiceTest.java with 8 test methods (2 DTO types x 4 roles)
**Acceptance / logic checks:**
- ADMIN_VIEWER: collectionUsd is null, partnerName is non-null in masked TransactionDetailDto
- FINANCE_ANALYST: collectionUsd is non-null in masked TransactionDetailDto
- ADMIN_VIEWER: apiKey is null in masked PartnerDetailDto
- OPS_OPERATOR: apiKey is non-null in masked PartnerDetailDto
- Input DTO fields are unchanged after masking call (immutability)
**Depends on:** 10.10-T19

### 10.10-T27 — Integration test: full RBAC flow - login, access guarded endpoint, token refresh, logout  _(55 min)_
**Context:** End-to-end integration test using Testcontainers (PostgreSQL) and Spring Boot test slice. Verifies: (a) OPS_OPERATOR logs in and gets JWT; (b) OPS_OPERATOR calls GET /api/admin/users returns 403; (c) SUPER_ADMIN logs in; (d) SUPER_ADMIN calls GET /api/admin/users returns 200; (e) SUPER_ADMIN creates a new OPS_OPERATOR user; (f) new user logs in with temp password; (g) new user is forced to change password; (h) after password change, new user gets a normal JWT without must_change_password claim; (i) token refresh works; (j) logout invalidates refresh token.
**Steps:** Create RbacIntegrationTest.java with @SpringBootTest and @Testcontainers (or embedded Postgres); Use MockMvc or TestRestTemplate; run Flyway migrations including seed data from T01-T05; Implement steps (a)-(j) as an ordered @Test sequence or @TestMethodOrder; Assert HTTP status codes match expectations at each step; Assert specific JWT claim values at steps (f) and (h)
**Deliverable:** RbacIntegrationTest.java covering the 10-step scenario described in context
**Acceptance / logic checks:**
- Step (b) returns HTTP 403 with OPS_OPERATOR token
- Step (d) returns HTTP 200 with SUPER_ADMIN token
- Step (f) JWT contains claim must_change_password=true
- Step (h) JWT does NOT contain must_change_password=true (or it is false)
- Step (j) token refresh after logout returns HTTP 401 INVALID_REFRESH_TOKEN
**Depends on:** 10.10-T17, 10.10-T14, 10.10-T10

### 10.10-T28 — Integration test: audit log immutability and event coverage for user management  _(50 min)_
**Context:** Verifies that audit log entries are correctly written and cannot be deleted for all user management operations. Uses Testcontainers Postgres. Sequence: (a) SUPER_ADMIN creates a user -> verify USER_CREATED event in audit log; (b) assign role -> USER_ROLE_ASSIGNED; (c) force password reset -> PASSWORD_RESET_FORCED; (d) deactivate -> USER_DEACTIVATED; (e) reactivate -> USER_REACTIVATED; (f) unlock -> USER_UNLOCKED; (g) attempt DELETE on admin_audit_log via JdbcTemplate -> verify exception or 0 rows affected. Also verify AUTH_LOGIN_FAILED event written on bad-password attempt.
**Steps:** Create AuditLogIntegrationTest.java with @SpringBootTest and Testcontainers Postgres; Perform each user management operation via service layer (not REST); then query admin_audit_log by event_type; Assert each operation produces exactly 1 audit row with correct entity_id; Attempt JDBC DELETE FROM admin_audit_log WHERE id=<id>; assert either exception or rowsAffected==0 (depends on DB-level permission setup; mock the permission by testing ORM updatable=false guard); Verify AUTH_LOGIN_FAILED written after 1 bad login attempt
**Deliverable:** AuditLogIntegrationTest.java verifying 7 event types and immutability guard
**Acceptance / logic checks:**
- USER_CREATED audit row exists after createUser() call with correct actor_user_id
- USER_DEACTIVATED audit row contains previous_value JSON with is_active:true
- Attempting UPDATE on audit log entity via JPA throws exception (updatable=false columns)
- AUTH_LOGIN_FAILED row written with actor_ip_address matching the test IP
- All 7 event types (USER_CREATED, USER_ROLE_ASSIGNED, PASSWORD_RESET_FORCED, USER_DEACTIVATED, USER_REACTIVATED, USER_UNLOCKED, AUTH_LOGIN_FAILED) have at least 1 row after running all operations
**Depends on:** 10.10-T20, 10.10-T27

### 10.10-T29 — Implement Users & Roles UI: user list table (React component)  _(50 min)_
**Context:** PRD-07 §12 / UX-11: The Users & Roles module shows a table of admin users. Columns: Name, Email, Role, Status (Active/Inactive badge), Last Login, Actions (Edit Role / Deactivate / Reactivate / Force Reset). Visible only when permissions.includes('MANAGE_USERS_AND_ROLES') (from GET /api/admin/me). Uses GET /api/admin/users API (T13). Data displayed: UserResponseDto fields. No horizontal scroll; max 6 columns (per CONVENTIONS.md). Role badge uses colour coding: SUPER_ADMIN=red, OPS_OPERATOR=blue, FINANCE_ANALYST=green, ADMIN_VIEWER=grey.
**Steps:** Create UserListPage.tsx React component fetching GET /api/admin/users; Render a table with 6 columns: Name, Email, Role (badge), Status (badge), Last Login (formatted datetime), Actions; Conditionally render 'Users & Roles' nav link only when hasPermission('MANAGE_USERS_AND_ROLES') from useAuth() hook; Role badge colours: SUPER_ADMIN=#DC2626, OPS_OPERATOR=#2563EB, FINANCE_ANALYST=#16A34A, ADMIN_VIEWER=#6B7280; Actions column: show Deactivate button for active users, Reactivate for inactive; always show Edit Role and Force Reset
**Deliverable:** UserListPage.tsx React component with permission-gated nav link
**Acceptance / logic checks:**
- Nav link 'Users & Roles' is not rendered in DOM when user has OPS_OPERATOR role (verified via React Testing Library query)
- Table renders 4 columns visible without horizontal scroll at 1280px viewport
- SUPER_ADMIN badge has background color #DC2626
- Deactivate button is shown for is_active=true users; Reactivate for is_active=false users
- Empty state message is rendered when no users are returned from API
**Depends on:** 10.10-T13, 10.10-T18

### 10.10-T30 — Implement Users & Roles UI: create user modal and assign role form  _(50 min)_
**Context:** PRD-07 §12.4 / UX-11: The Create User modal collects: Email, Full Name, Role (dropdown with 4 canonical roles). On submit calls POST /api/admin/users (T14). On success shows a one-time dialog displaying the temporary password with a copy-to-clipboard button and a warning 'This password will not be shown again'. Role selector shows all 4 role codes with human-readable labels: SUPER_ADMIN=Super Admin, OPS_OPERATOR=Ops Operator, FINANCE_ANALYST=Finance Analyst, ADMIN_VIEWER=Admin Viewer. Assign Role uses PUT /api/admin/users/{id}/role (T14). Both forms use inline validation.
**Steps:** Create CreateUserModal.tsx with controlled form fields: email, name, roleCode selector; On POST success: render TemporaryPasswordDialog.tsx showing temporaryPassword from API response with copy button; Create AssignRoleModal.tsx with role selector dropdown; calls PUT /api/admin/users/{id}/role; Inline validation: email format check before submit; name min 1 char; roleCode required; Handle 409 DUPLICATE_EMAIL response by showing inline error 'A user with this email already exists'
**Deliverable:** CreateUserModal.tsx, TemporaryPasswordDialog.tsx, and AssignRoleModal.tsx React components
**Acceptance / logic checks:**
- Submitting form with invalid email format shows inline error without API call
- On successful create, TemporaryPasswordDialog renders with the temporary password value
- TemporaryPasswordDialog copy button copies text to clipboard (jsdom navigator.clipboard mock)
- 409 response from API renders inline field error on the email field
- Role dropdown renders exactly 4 options with human-readable labels
**Depends on:** 10.10-T29, 10.10-T14

### 10.10-T31 — Implement Users & Roles UI: deactivate, reactivate, force-reset confirmation dialogs  _(45 min)_
**Context:** PRD-07 §14.2: destructive or irreversible actions require a two-step confirmation with a summary dialog before execution. Deactivating a user, reactivating, and forcing a password reset all require confirmation dialogs. Dialog content: Deactivate - 'Deactivate [Name]? They will be immediately logged out and cannot log in until reactivated.'; Force Reset - 'Reset password for [Name]? A new temporary password will be generated. Their current sessions will be invalidated.'; Reactivate - 'Reactivate [Name]? They will be able to log in again.' Actions call POST /api/admin/users/{id}/deactivate, POST /api/admin/users/{id}/reactivate, POST /api/admin/users/{id}/force-password-reset (T11, T15).
**Steps:** Create ConfirmActionDialog.tsx generic dialog with title, description, confirm/cancel buttons and loading state; Wire Deactivate button in UserListPage to open ConfirmActionDialog; on confirm call deactivate API; on success refresh list; Wire Reactivate button similarly; Wire Force Reset to open dialog; on confirm call force-reset API; on success show TemporaryPasswordDialog with new temp password; Handle 422 CANNOT_DEACTIVATE_SELF by showing error toast 'You cannot deactivate your own account'
**Deliverable:** ConfirmActionDialog.tsx reusable component and wiring in UserListPage.tsx
**Acceptance / logic checks:**
- Clicking Deactivate opens dialog with correct user name in description text
- Clicking Cancel on dialog does not call the API (verified with mock assertions)
- Successful deactivation closes dialog and user's status badge changes to Inactive in list
- Force Reset success shows TemporaryPasswordDialog with new temporary password
- 422 CANNOT_DEACTIVATE_SELF response shows error toast with correct message
**Depends on:** 10.10-T30, 10.10-T11, 10.10-T15

### 10.10-T32 — Implement forced-password-change screen (first login flow)  _(50 min)_
**Context:** PRD-07 §12.4: new users must change their password on first login. JWT claim must_change_password=true triggers this. On login success if must_change_password=true, frontend redirects to /admin/change-password before any other route. Form collects: Current Password, New Password, Confirm New Password. Submits to POST /api/admin/auth/password-change (T10). Password policy displayed on screen: min 12 chars, uppercase, lowercase, digit, special character. On success: re-issues JWT (must_change_password no longer true) and redirects to dashboard.
**Steps:** Create ChangePasswordPage.tsx route /admin/change-password; In router/auth guard: check JWT claim must_change_password; if true redirect to /admin/change-password regardless of target route; Form fields: currentPassword, newPassword, confirmNewPassword with show/hide toggles; Display password policy checklist with live validation (green/red indicators per rule as user types); On 400 INVALID_PASSWORD_POLICY response display specific failed rule; on 400 PASSWORD_REUSE_DENIED show 'New password cannot be the same as current password'
**Deliverable:** ChangePasswordPage.tsx and route guard update in AuthGuard.tsx
**Acceptance / logic checks:**
- User with must_change_password=true JWT navigating to /admin/dashboard is redirected to /admin/change-password
- Entering newPassword='Short1!' (less than 12 chars) shows live indicator for min-length rule as red
- Entering mismatched confirmNewPassword shows inline error before submit
- On successful change, user is redirected to /admin/dashboard with a new JWT where must_change_password is absent or false
- Typing a password meeting all rules turns all 5 policy indicators green
**Depends on:** 10.10-T10, 10.10-T29

### 10.10-T33 — Frontend unit tests: UserListPage permission gating and RBAC rendering  _(40 min)_
**Context:** React Testing Library tests for UserListPage.tsx (T29) and related modals. Verify permission-gated rendering for different roles by mocking the useAuth() hook to return different permission sets. Key scenarios: (a) OPS_OPERATOR: Users & Roles nav link absent; (b) SUPER_ADMIN: nav link present; (c) table renders correct badge colours; (d) ADMIN_VIEWER does not have access to this page at all (redirect); (e) create user button only visible to SUPER_ADMIN.
**Steps:** Create UserListPage.test.tsx using React Testing Library and vitest/jest; Mock GET /api/admin/users with msw (Mock Service Worker) returning 3 test users; For each of 4 roles: render UserListPage with mocked useAuth() permissions; assert nav visibility; Assert role badge colour for SUPER_ADMIN row is #DC2626 (computed style or className); Assert create user button is present for SUPER_ADMIN and absent for non-SUPER_ADMIN roles
**Deliverable:** UserListPage.test.tsx with 6+ test cases covering permission rendering
**Acceptance / logic checks:**
- With OPS_OPERATOR permissions, queryByText('Users & Roles') in nav returns null
- With SUPER_ADMIN permissions, getByText('Users & Roles') in nav is found
- With SUPER_ADMIN, 'Create User' button is present in DOM
- With FINANCE_ANALYST permissions, 'Create User' button is absent
- Table row for a SUPER_ADMIN user has the correct red badge class/style
**Depends on:** 10.10-T29


## WBS 10.11 — Audit log viewer
### 10.11-T01 — Add prev_hash and entry_hash columns to audit_log migration  _(30 min)_
**Context:** SEC-09 §6.3 requires a SHA-256 hash chain on the audit_log table: each row stores entry_hash (SHA-256 of this row's canonical fields) and prev_hash (entry_hash of the immediately preceding row; NULL for the very first row). This enables the daily integrity check job to detect any tampering or deletion. The audit_log table (DAT-03 §10.7) currently has: id BIGINT PK, actor_id VARCHAR(120), actor_type VARCHAR(20), action VARCHAR(20), entity_type VARCHAR(50), entity_id BIGINT, before_value JSONB, after_value JSONB, occurred_at TIMESTAMPTZ, ip_address INET, request_id VARCHAR(64). No UPDATE or DELETE is ever permitted; retention >= 7 years.
**Steps:** Write Flyway migration V10_11_001: ALTER TABLE audit_log ADD COLUMN prev_hash CHAR(64) NULL, ADD COLUMN entry_hash CHAR(64) NOT NULL DEFAULT ''; Add a partial index: CREATE INDEX idx_audit_log_occurred_at ON audit_log(occurred_at DESC); Add a unique index on entry_hash to prevent duplicate chain entries: CREATE UNIQUE INDEX idx_audit_log_entry_hash ON audit_log(entry_hash) WHERE entry_hash <> ''; Document canonical hash input: SHA-256 of concat(id||actor_id||actor_type||action||entity_type||entity_id::text||COALESCE(before_value::text,'')||COALESCE(after_value::text,'')||occurred_at::text||COALESCE(prev_hash,'')) in migration comments; Verify migration applies on an existing audit_log table without data loss
**Deliverable:** Flyway migration V10_11_001 adding prev_hash CHAR(64) NULL and entry_hash CHAR(64) NOT NULL to audit_log, with index on occurred_at and unique index on entry_hash
**Acceptance / logic checks:**
- Migration applies cleanly on a table with existing rows (entry_hash defaults to empty string pending backfill)
- prev_hash column is NULL for the first row inserted after migration
- Inserting two rows with identical entry_hash raises a unique constraint violation
- occurred_at DESC index is present and used by EXPLAIN for ORDER BY occurred_at DESC queries
- No existing audit_log data is modified or deleted by the migration
**Depends on:** 10.1-T01

### 10.11-T02 — Implement AuditLogWriter service with SHA-256 hash-chain computation  _(50 min)_
**Context:** All write operations in the Admin System must call AuditLogWriter.write() which inserts one row into audit_log and computes the hash chain. The canonical hash input is: SHA-256(id::text + actor_id + actor_type + action + entity_type + entity_id::text + COALESCE(before_value::jsonb::text,'') + COALESCE(after_value::jsonb::text,'') + occurred_at::ISO8601 + COALESCE(prev_hash,'')).  prev_hash = entry_hash of the row with the largest id less than the new row. The insert must be atomic: use a DB-level serialisable transaction or SELECT MAX(id) FOR UPDATE to prevent concurrent inserts racing on prev_hash. The application service account must have INSERT-only (no UPDATE, no DELETE) on audit_log; this is enforced at DB-user grant level. Retention >= 7 years; never purge via application code.
**Steps:** Create AuditLogWriter.write(actor_id, actor_type, action, entity_type, entity_id, before_value, after_value, ip_address, request_id) method; Inside a serialisable transaction: SELECT entry_hash FROM audit_log ORDER BY id DESC LIMIT 1 FOR UPDATE to obtain prev_hash; Compute occurred_at = current UTC timestamp (server-generated, not client-supplied); Compute entry_hash = hex(SHA-256(canonical_string)) where canonical_string is defined above; INSERT row; if unique-constraint violation on entry_hash, retry with fresh occurred_at (max 3 retries, then throw); Verify the service account has INSERT only (no UPDATE/DELETE) on audit_log by running an UPDATE and asserting it is rejected with a permission error in the test suite
**Deliverable:** AuditLogWriter service class with hash-chain insert logic and unit tests
**Acceptance / logic checks:**
- Two successive writes produce rows where row2.prev_hash == row1.entry_hash
- entry_hash is a valid 64-char hex string (SHA-256 output)
- prev_hash of the very first row in a clean test DB is NULL
- Concurrent write from two threads produces a valid linear chain (no duplicate prev_hash)
- An attempt to UPDATE an audit_log row via the application service account is rejected with a DB permission error
**Depends on:** 10.11-T01

### 10.11-T03 — Integrate AuditLogWriter into all Admin System write operations  _(55 min)_
**Context:** PRD-07 §13.2 lists every write operation that must generate an audit log entry: config changes (create/edit scheme, partner, rule, treasury rate), credential operations (generate/revoke API key), refund operations, prefunding operations (top-up, manual adjustment), settlement actions (flag/resolve exception), user management (create user, assign role, deactivate), auth events (login success/failure, logout). Each entry requires: actor_id (from JWT sub), actor_role (from JWT role_code), action (CREATE/UPDATE/DELETE/ACTIVATE/DEACTIVATE), entity_type (table name), entity_id (row PK), before_value (JSONB snapshot before change; NULL for CREATE), after_value (JSONB snapshot after change; NULL for DELETE), ip_address (from request context), request_id (trace ID). before_value and after_value must never contain plaintext secrets (API secret, password hash). The call to AuditLogWriter.write() must happen in the same DB transaction as the business write so they are atomic.
**Steps:** Identify all service methods that perform Admin System writes (at minimum: SchemeService, PartnerService, RuleService, TreasuryRateService, CredentialService, RefundService, PrefundingService, UserService, AuthService); For each service method wrap the business write + AuditLogWriter.write() in a single transaction; Strip sensitive fields from before_value/after_value: remove password_hash, api_secret_hash, signing_secret_hash before serialising to JSONB; Add integration test for rule update: assert audit_log gains exactly one row with entity_type='rule', action='UPDATE', before_value.m_a = old value, after_value.m_a = new value; Add integration test for credential generation: assert audit_log row has entity_type='partner', action='UPDATE', after_value does NOT contain api_secret
**Deliverable:** Updated service layer where every Admin write atomically appends an audit_log entry, plus integration tests for rule-update and credential-generation audit entries
**Acceptance / logic checks:**
- Updating rule m_a from 1.00 to 1.25 produces audit_log row: action=UPDATE, entity_type=rule, before_value.m_a=1.00, after_value.m_a=1.25, actor_id=operator UUID
- Generating API credentials produces audit_log row with entity_type=partner; after_value must not contain plaintext or hashed api_secret field
- Creating a new partner produces audit_log row with action=CREATE, before_value=null
- If the business write succeeds but AuditLogWriter.write() throws, the entire transaction rolls back (no silent audit gaps)
- Auth events (login success, login failure) are written to audit_log with actor_type=OPERATOR and ip_address populated
**Depends on:** 10.11-T02, 10.1-T03

### 10.11-T04 — Create GET /admin/audit-log API endpoint with filter and pagination  _(50 min)_
**Context:** PRD-07 §13.5 defines the audit log search filters: actor (actor_id), event_type (maps to action column or a derived event_code), entity_type, entity_id, date_range (required; max 12-month window), ip_address. Results are paginated at 100 rows per page, ordered by occurred_at DESC. RBAC: VIEW_AUDIT_LOG permission required (SUPER_ADMIN and OPS_OPERATOR only; see PRD-07 §12.3). Query parameters: actor_id (optional), action (optional, multi-value), entity_type (optional), entity_id (optional BIGINT), from_date (required ISO-8601 UTC), to_date (required ISO-8601 UTC), ip_address (optional), page (default 1), page_size (default 100 max 100). Response: {total_count, page, page_size, items: [{id, actor_id, actor_type, action, entity_type, entity_id, occurred_at, ip_address, request_id, has_diff: bool}]}. before_value and after_value are NOT returned in the list response (only in the detail endpoint).
**Steps:** Create GET /admin/audit-log handler; apply PermissionGuard(VIEW_AUDIT_LOG); Parse and validate query params: from_date required, to_date required, window = to_date - from_date must be <= 366 days; reject with 400 DATE_RANGE_EXCEEDS_LIMIT otherwise; Build dynamic WHERE clause from provided filters; always include: occurred_at BETWEEN from_date AND to_date; Execute COUNT query for total_count, then SELECT with LIMIT 100 OFFSET (page-1)*100 ORDER BY occurred_at DESC; Return 200 JSON response; include has_diff=true when before_value IS NOT NULL or after_value IS NOT NULL; Return 403 FORBIDDEN if user lacks VIEW_AUDIT_LOG permission; 401 if unauthenticated
**Deliverable:** GET /admin/audit-log endpoint with filter, pagination, and RBAC guard
**Acceptance / logic checks:**
- Request with from_date=2026-01-01 and to_date=2027-02-01 (>12 months) returns 400 DATE_RANGE_EXCEEDS_LIMIT
- Request by FINANCE_ANALYST (lacking VIEW_AUDIT_LOG) returns 403 FORBIDDEN
- Filtering by entity_type=rule and entity_id=42 returns only rows where entity_type=rule AND entity_id=42
- Response items do not include before_value or after_value fields (only has_diff boolean)
- page=2 with page_size=100 returns rows 101-200 ordered by occurred_at DESC
**Depends on:** 10.11-T02, 10.1-T03

### 10.11-T05 — Create GET /admin/audit-log/{id} detail endpoint  _(35 min)_
**Context:** Clicking [View] in the audit log list (UX-11 §4.11) opens an audit detail panel showing: entity before_value (JSONB), entity after_value (JSONB), and action metadata (ip_address, actor_id, actor_role, actor_type, occurred_at millisecond precision, request_id, entry_hash, prev_hash). RBAC: VIEW_AUDIT_LOG required. The entry_hash and prev_hash are exposed so Super Admins can manually verify chain integrity. Sensitive fields (api_secret_hash, password_hash, signing_secret_hash) must never appear in any response regardless of role.
**Steps:** Create GET /admin/audit-log/{id} handler; apply PermissionGuard(VIEW_AUDIT_LOG); Fetch single audit_log row by id BIGINT; return 404 if not found; Sanitise before_value and after_value JSONB: strip any keys matching the blocklist [api_secret_hash, password_hash, signing_secret_hash, secret_hash] before returning; Return 200 with full row: id, actor_id, actor_type, action, entity_type, entity_id, before_value (sanitised), after_value (sanitised), occurred_at, ip_address, request_id, entry_hash, prev_hash; Write unit test: fetch detail of an audit entry created for a credential-generation event; assert api_secret_hash is absent from after_value
**Deliverable:** GET /admin/audit-log/{id} detail endpoint with sanitised JSONB fields
**Acceptance / logic checks:**
- Fetching a rule-update entry returns before_value.m_a and after_value.m_a with the actual numeric values
- Fetching a credential-generation entry returns after_value without any field matching *secret* or *hash* pattern
- Fetching a non-existent id returns 404
- entry_hash and prev_hash are present in the response body
- ADMIN_VIEWER requesting the endpoint receives 403 FORBIDDEN
**Depends on:** 10.11-T04

### 10.11-T06 — Implement audit log CSV export endpoint (sync and async paths)  _(55 min)_
**Context:** PRD-07 §13.5 and §14.1: export to CSV of all matching entries, up to 500,000 rows. Exports <= 30-day window must complete synchronously within 30 seconds (return CSV as attachment). Exports > 30 days (and up to 12 months) are queued as async jobs; the endpoint returns 202 Accepted with a job_id; when complete the operator receives an email notification with a download link. RBAC: EXPORT_AUDIT_LOG permission required (SUPER_ADMIN only; OPS_OPERATOR does NOT have EXPORT_AUDIT_LOG per PRD-07 §12.3). CSV columns: id, occurred_at (UTC ISO-8601), actor_id, actor_type, action, entity_type, entity_id, ip_address, request_id, has_diff. before_value and after_value are NOT included in the CSV export (security: bulk export of config snapshots is out of scope).
**Steps:** Create POST /admin/audit-log/export accepting same filter params as the search endpoint (from_date, to_date required; same 12-month max window guard); Apply PermissionGuard(EXPORT_AUDIT_LOG); return 403 if OPS_OPERATOR or lower; If window <= 30 days: stream query results directly to CSV response (Content-Disposition: attachment; filename=audit_log_{from}_{to}.csv) with RFC 4180 formatting; If window > 30 days: enqueue async export job (store in audit_export_job table: id UUID, user_id, filter_params JSONB, status PENDING/RUNNING/DONE/FAILED, file_url, created_at, completed_at); return 202 {job_id}; On async completion: write CSV to object storage (S3-compatible); update audit_export_job.file_url; send email to requesting user's address with pre-signed download URL (24h TTL)
**Deliverable:** POST /admin/audit-log/export endpoint with sync path (<=30 days) and async path (>30 days), audit_export_job table migration, and unit tests
**Acceptance / logic checks:**
- OPS_OPERATOR calling export returns 403 FORBIDDEN
- 7-day window export returns Content-Type: text/csv with correct CSV headers as first row and no before_value/after_value columns
- 35-day window export returns 202 with a job_id UUID and creates a row in audit_export_job with status=PENDING
- Async job on completion writes file_url and sets status=DONE; file_url is a pre-signed URL expiring in 24 hours
- CSV row count for a 7-day window matches the total_count returned by the GET /admin/audit-log endpoint for the same date range
**Depends on:** 10.11-T04

### 10.11-T07 — Create audit_export_job DB table migration  _(25 min)_
**Context:** The async CSV export path (WBS 10.11-T06) requires a persistent job tracking table. The table stores: id (UUID v4 PK), user_id (FK -> hub_user.id), filter_params (JSONB: from_date, to_date, actor_id, action, entity_type, entity_id, ip_address), status (VARCHAR(10): PENDING, RUNNING, DONE, FAILED), estimated_row_count (INT nullable), file_url (TEXT nullable; pre-signed S3 URL), error_message (TEXT nullable), created_at (TIMESTAMPTZ NOT NULL DEFAULT NOW()), started_at (TIMESTAMPTZ NULL), completed_at (TIMESTAMPTZ NULL). Index on (user_id, created_at DESC) for status polling queries. Jobs older than 30 days can be purged by a scheduled cleanup job (the file itself expires via S3 lifecycle rule).
**Steps:** Write Flyway migration V10_11_002 creating audit_export_job table with all columns and constraints; Add CHECK constraint on status: IN ('PENDING','RUNNING','DONE','FAILED'); Add index on (user_id, created_at DESC); Add a scheduled cleanup migration comment noting that rows older than 30 days are purged by the background job defined in 10.11-T14; Verify migration applies cleanly; test insert of a PENDING row and transition to DONE
**Deliverable:** Flyway migration V10_11_002 creating audit_export_job table
**Acceptance / logic checks:**
- Inserting a row with status='INVALID' raises a CHECK constraint violation
- user_id FK rejects an unknown hub_user UUID
- status can transition from PENDING to RUNNING to DONE in successive UPDATE statements (table is NOT append-only unlike audit_log)
- Index on (user_id, created_at DESC) is visible in pg_indexes
- completed_at is NULL for a PENDING row and NOT NULL after a DONE update
**Depends on:** 10.1-T01

### 10.11-T08 — Implement daily audit log hash-chain integrity check job  _(55 min)_
**Context:** SEC-09 §6.3 and NFR-10 N-18 require a daily job that verifies the SHA-256 hash chain is unbroken. The job must: (1) read all audit_log rows in id-ascending order; (2) for each row, recompute entry_hash from the canonical fields and compare to the stored entry_hash; (3) verify row[n].prev_hash == row[n-1].entry_hash; (4) if any mismatch is found, raise a P1 alert (AUDIT_LOG_CHAIN_BREAK) and write the finding to a separate admin-only table audit_integrity_check_result. The job result is written to audit_integrity_check_result (id UUID, run_at TIMESTAMPTZ, rows_checked INT, status VARCHAR(10) OK/FAILED, first_break_id BIGINT NULL, error_detail TEXT NULL). Per SEC-09, the result is available at an admin-only monitoring endpoint.
**Steps:** Write Flyway migration V10_11_003 creating audit_integrity_check_result table with columns: id UUID PK, run_at TIMESTAMPTZ NOT NULL, rows_checked INT NOT NULL, status VARCHAR(10) CHECK IN ('OK','FAILED'), first_break_id BIGINT NULL FK -> audit_log(id), error_detail TEXT NULL; Implement IntegrityCheckJob.run(): stream audit_log rows ORDER BY id ASC in batches of 10,000 using a keyset cursor; for each row recompute entry_hash and verify prev_hash linkage; On first mismatch: record first_break_id, set status=FAILED, stop scan, write result row, emit P1 alert metric AUDIT_LOG_CHAIN_BREAK=1; On success: set status=OK, write result row, emit metric AUDIT_LOG_CHAIN_BREAK=0; Schedule job to run daily at 03:00 UTC (outside ZeroPay batch window 01:30-05:30 KST = 16:30-22:30 UTC previous day; 03:00 UTC = 12:00 KST, well outside window)
**Deliverable:** Flyway migration V10_11_003, IntegrityCheckJob class, and unit tests covering both OK and FAILED chain scenarios
**Acceptance / logic checks:**
- A clean audit_log with 1,000 rows and correct hash chain produces status=OK with rows_checked=1000
- Manually corrupting entry_hash on row id=500 causes the job to report status=FAILED with first_break_id=500
- Manually deleting a row (simulated by setting prev_hash mismatch) causes FAILED with the first affected row id
- Job result row is written to audit_integrity_check_result regardless of outcome
- P1 alert metric AUDIT_LOG_CHAIN_BREAK is emitted as 1 on failure, 0 on success
**Depends on:** 10.11-T02

### 10.11-T09 — Create GET /admin/audit-integrity-check endpoint (Super Admin only)  _(30 min)_
**Context:** SEC-09 §6.3 states the integrity check job result is written to an admin-only monitoring endpoint. PRD-07 A-11 confirms this is visible to Super Admins in the Admin System. The endpoint returns the most recent N integrity check results from audit_integrity_check_result. RBAC: SUPER_ADMIN role only (not even OPS_OPERATOR). Response: {items: [{id, run_at, rows_checked, status, first_break_id, error_detail}], latest_status: 'OK'|'FAILED'|'NEVER_RUN'}. latest_status is 'NEVER_RUN' when the table is empty. If latest_status is FAILED, response HTTP status is 200 (not 5xx) but the payload makes the failure explicit; it is the monitoring system's job to alert.
**Steps:** Create GET /admin/audit-integrity-check handler; Apply PermissionGuard requiring role_code = SUPER_ADMIN (not just VIEW_AUDIT_LOG; use a dedicated check); Query audit_integrity_check_result ORDER BY run_at DESC LIMIT 10; Compute latest_status from the most recent row status; return NEVER_RUN if no rows exist; Return 200 with items array and latest_status field; Write integration test: seed two result rows (OK then FAILED); assert latest_status=FAILED and items length=2
**Deliverable:** GET /admin/audit-integrity-check endpoint restricted to SUPER_ADMIN, with integration test
**Acceptance / logic checks:**
- OPS_OPERATOR calling the endpoint receives 403 FORBIDDEN
- FINANCE_ANALYST calling the endpoint receives 403 FORBIDDEN
- Empty audit_integrity_check_result table returns 200 with latest_status=NEVER_RUN
- Two rows (first OK, second FAILED) return latest_status=FAILED
- Response items are ordered most-recent-first (run_at DESC)
**Depends on:** 10.11-T08, 10.1-T03

### 10.11-T10 — Build Audit Log list view UI component (filter bar + results table)  _(55 min)_
**Context:** UX-11 §4.11 shows the Audit Log screen: a filter bar with [Date from] [Date to] [Actor] [Entity type dropdown] [Search] button, and a results table with columns: Time (KST display), Actor, Action, Entity, Detail. Dates are stored as UTC; the UI displays occurred_at converted to KST (+09:00). The table is paginated (100 rows per page) with Prev/Next controls. The RBAC gate means the Audit Log nav item is only visible to SUPER_ADMIN and OPS_OPERATOR (VIEW_AUDIT_LOG permission). Filters: date range defaults to today; Actor is a free-text input matching actor_id substring; Entity type is a fixed dropdown (scheme, partner, rule, treasury_rate, partner_credential, user, prefunding, settlement, auth). The Date from and Date to fields reject a range wider than 12 months with an inline error.
**Steps:** Create AuditLogListPage component with filter bar inputs and [Search] button; Implement GET /admin/audit-log call with constructed query params on Search click; show loading indicator during fetch; Render results table with columns: Time (occurred_at converted from UTC to KST, format: YYYY-MM-DD HH:mm:ss KST), Actor (actor_id), Action (action), Entity (entity_type/entity_id), Detail ([View] button); Show inline error 'Date range cannot exceed 12 months' if to_date - from_date > 366 days (client-side guard before API call); Implement pagination: show current page, total rows, Prev/Next buttons; disable Prev on page 1; disable Next when page*100 >= total_count; Hide Audit Log nav item for roles without VIEW_AUDIT_LOG (FINANCE_ANALYST, ADMIN_VIEWER)
**Deliverable:** AuditLogListPage React component with filter bar, paginated results table, and RBAC-gated nav item
**Acceptance / logic checks:**
- Selecting Date from=2025-01-01 and Date to=2026-06-01 (>12 months) shows inline error and does not call the API
- Table column Time shows occurred_at in KST: UTC 2026-06-05T05:00:00Z displays as 2026-06-05 14:00:00 KST
- ADMIN_VIEWER session does not show Audit Log in the left nav
- Clicking Next on the last page does not trigger an API call (Next button is disabled)
- Searching by actor_id='ops@gme' returns only rows where actor_id contains that substring
**Depends on:** 10.11-T04

### 10.11-T11 — Build Audit Log entry detail panel UI component  _(45 min)_
**Context:** UX-11 §4.11: clicking [View] on an audit log row opens a detail panel (slide-over or modal) showing: entity before_value (formatted JSON), entity after_value (formatted JSON), and metadata: ip_address, actor_id, actor_role, actor_type, occurred_at (millisecond precision UTC + KST), request_id, entry_hash, prev_hash. The panel calls GET /admin/audit-log/{id}. For CREATE events before_value is null (display 'N/A - new record'). For DELETE events after_value is null (display 'N/A - record deleted'). The JSON diff viewer highlights changed keys in yellow when both before and after are present (inline diff). entry_hash and prev_hash are shown in a collapsible 'Chain Integrity' section visible only to SUPER_ADMIN.
**Steps:** Create AuditEntryDetailPanel component accepting entry_id prop; call GET /admin/audit-log/{id} on open; Display metadata section: occurred_at (both UTC and KST), actor_id, actor_role, ip_address, request_id; Render before_value and after_value as syntax-highlighted JSON; show 'N/A - new record' / 'N/A - record deleted' for null values; Implement inline JSON diff: when both before and after are non-null, highlight keys that differ with a yellow background; Add collapsible 'Chain Integrity' section showing entry_hash and prev_hash; visible only when user role = SUPER_ADMIN; Write component test: given a rule-update entry with before_value.m_a=1.00 and after_value.m_a=1.25, assert m_a key is highlighted as changed
**Deliverable:** AuditEntryDetailPanel React component with JSON diff display and SUPER_ADMIN-only hash chain section
**Acceptance / logic checks:**
- CREATE event panel shows before_value area as 'N/A - new record' and after_value as the new entity JSON
- Rule update entry highlights m_a in yellow when before_value.m_a != after_value.m_a
- SUPER_ADMIN sees Chain Integrity section with 64-char entry_hash and prev_hash (or 'null' for first record)
- OPS_OPERATOR does not see the Chain Integrity section
- Panel closes on Escape key or clicking outside; re-opening re-fetches the entry
**Depends on:** 10.11-T05, 10.11-T10

### 10.11-T12 — Build Audit Log CSV export UI: Export button and async job status polling  _(50 min)_
**Context:** PRD-07 §13.5: the audit log list view has an [Export CSV] button. Export is available only to SUPER_ADMIN (EXPORT_AUDIT_LOG permission). The button calls POST /admin/audit-log/export with the current filter params. If the window is <= 30 days the browser receives a CSV file download immediately. If the window is > 30 days the API returns 202 with a job_id; the UI shows an 'Export queued' toast with a status link. The status link opens a polling view that shows the job status (PENDING, RUNNING, DONE, FAILED); on DONE it renders a download link. Polling interval: every 10 seconds, stop after DONE or FAILED or after 10 minutes (timeout).
**Steps:** Add [Export CSV] button to AuditLogListPage; render only when user has EXPORT_AUDIT_LOG permission (SUPER_ADMIN only); On click: call POST /admin/audit-log/export with current filter params; if response is 200 with Content-Type: text/csv trigger browser file download with filename audit_log_{from}_{to}.csv; If response is 202: extract job_id from body; show toast 'Export queued - job {job_id}'; render [Check Status] link; Implement ExportJobStatusPanel: poll GET /admin/audit-log/export-jobs/{job_id} every 10 seconds; display status badge (PENDING=grey, RUNNING=blue, DONE=green, FAILED=red); on DONE show [Download] link to file_url; Stop polling after DONE, FAILED, or 10 minutes; show timeout message if 10-min limit reached; Write component test: mock 202 response, verify toast shown, verify poll URL called after 10s
**Deliverable:** Export button on AuditLogListPage and ExportJobStatusPanel component with polling logic
**Acceptance / logic checks:**
- OPS_OPERATOR does not see the [Export CSV] button
- 7-day window export triggers a browser file download without a job_id toast
- 35-day window export shows 'Export queued' toast and does not trigger a direct download
- ExportJobStatusPanel stops polling and shows [Download] link when job status transitions to DONE
- Polling stops after 10 minutes of no DONE/FAILED response and displays a timeout message
**Depends on:** 10.11-T06, 10.11-T10

### 10.11-T13 — Add GET /admin/audit-log/export-jobs/{job_id} status endpoint  _(40 min)_
**Context:** The async export polling UI (10.11-T12) calls GET /admin/audit-log/export-jobs/{job_id} to check progress. Response: {job_id, status, estimated_row_count, file_url (null until DONE), created_at, started_at, completed_at, error_message (null unless FAILED)}. RBAC: user must be SUPER_ADMIN AND the requesting user_id must match audit_export_job.user_id (a user cannot poll another user's job). Return 404 if job_id does not exist. Return 403 if job exists but belongs to a different user. When status=DONE, file_url is a pre-signed S3 URL (24h TTL); if the URL has expired (>24h since completed_at), re-generate a fresh pre-signed URL on each request.
**Steps:** Create GET /admin/audit-log/export-jobs/{job_id} handler; apply PermissionGuard(EXPORT_AUDIT_LOG); Fetch audit_export_job by id; return 404 if not found; Assert job.user_id == authenticated user_id; return 403 FORBIDDEN if mismatch (even for SUPER_ADMIN calling another user's job); If status=DONE and completed_at < NOW() - 24h, regenerate pre-signed URL with 24h TTL and return fresh URL (do not update file_url in DB; serve fresh on every call); Return 200 JSON with all fields; file_url is null when status is not DONE; Write unit test: user A cannot access job belonging to user B; returns 403
**Deliverable:** GET /admin/audit-log/export-jobs/{job_id} endpoint with ownership check and URL regeneration logic
**Acceptance / logic checks:**
- Fetching a PENDING job returns status=PENDING with file_url=null
- Fetching a DONE job within 24h returns the original file_url (pre-signed, non-null)
- Fetching a DONE job after 24h since completion returns a freshly generated pre-signed URL (different from original)
- User B requesting User A's job_id returns 403 FORBIDDEN even if both are SUPER_ADMIN
- Non-existent job_id returns 404
**Depends on:** 10.11-T06, 10.11-T07

### 10.11-T14 — Implement async CSV export worker and cleanup job  _(55 min)_
**Context:** When an audit_export_job row is created with status=PENDING (by 10.11-T06), a background worker must pick it up, generate the CSV, upload to object storage, and update the job to DONE. Worker steps: (1) SELECT ... FOR UPDATE SKIP LOCKED to claim a PENDING job and set status=RUNNING; (2) stream audit_log query with filter_params JSONB fields as WHERE clauses, ORDER BY occurred_at DESC, in batches of 10,000 rows; (3) write CSV rows (columns: id, occurred_at, actor_id, actor_type, action, entity_type, entity_id, ip_address, request_id, has_diff); (4) upload to S3-compatible storage at key audit-exports/{user_id}/{job_id}.csv; (5) generate pre-signed URL (24h TTL); (6) update job to DONE with file_url and completed_at. On failure: set status=FAILED with error_message. A separate cleanup job runs daily and deletes audit_export_job rows older than 30 days; it does NOT delete the underlying audit_log rows. Object storage lifecycle rule (separate config) expires the CSV files after 24h.
**Steps:** Implement AuditExportWorker.process(jobId): claim job with SELECT FOR UPDATE SKIP LOCKED, transition to RUNNING; Stream audit_log rows matching filter_params in batches of 10,000 (keyset pagination by id); write to a temp file in CSV format; Upload completed CSV to S3 at key audit-exports/{user_id}/{job_id}.csv; generate 24h pre-signed URL; Update job to DONE (file_url, completed_at); send email notification via EmailService to job.user.email; On any exception: catch, update job to FAILED (error_message = exception message truncated to 500 chars), re-throw for monitoring; Implement AuditExportCleanupJob: DELETE FROM audit_export_job WHERE created_at < NOW() - INTERVAL '30 days'; schedule at 04:00 UTC daily
**Deliverable:** AuditExportWorker and AuditExportCleanupJob classes with integration tests
**Acceptance / logic checks:**
- Worker processes a PENDING job and transitions status: PENDING -> RUNNING -> DONE within the test
- CSV file for a 35-day filter with 50,000 rows has exactly 50,000 data rows plus 1 header row
- Simulated S3 upload failure causes job status to transition to FAILED with non-empty error_message
- Two concurrent workers both claiming PENDING jobs do not both claim the same job (SELECT FOR UPDATE SKIP LOCKED enforced)
- Cleanup job deletes job rows older than 30 days and does not touch rows newer than 30 days
**Depends on:** 10.11-T07, 10.11-T06

### 10.11-T15 — Add audit log search DB indexes for filter performance  _(35 min)_
**Context:** The GET /admin/audit-log endpoint (10.11-T04) runs queries filtered by occurred_at (required range), actor_id (optional), action (optional), entity_type (optional), entity_id (optional), ip_address (optional). With 7-year retention and high write volume, unindexed queries will time out. Required indexes: (occurred_at DESC) already added in T01; additionally need (entity_type, entity_id, occurred_at DESC) for entity-scoped history queries; (actor_id, occurred_at DESC) for actor filter; (action, occurred_at DESC) for event-type filter. All are partial or composite B-tree indexes. Use BRIN index on occurred_at as an alternative for very large tables if the PG version supports it and data is written in time order.
**Steps:** Write Flyway migration V10_11_004 adding indexes: CREATE INDEX CONCURRENTLY idx_audit_log_entity ON audit_log(entity_type, entity_id, occurred_at DESC); Add: CREATE INDEX CONCURRENTLY idx_audit_log_actor ON audit_log(actor_id, occurred_at DESC); Add: CREATE INDEX CONCURRENTLY idx_audit_log_action ON audit_log(action, occurred_at DESC); Add: CREATE INDEX CONCURRENTLY idx_audit_log_ip ON audit_log(ip_address, occurred_at DESC) WHERE ip_address IS NOT NULL; Run EXPLAIN ANALYZE on each of the four common query patterns and confirm Index Scan (not Seq Scan) is used for a table with 1M rows
**Deliverable:** Flyway migration V10_11_004 with four CONCURRENT indexes and EXPLAIN output showing index usage
**Acceptance / logic checks:**
- EXPLAIN ANALYZE for WHERE entity_type='rule' AND entity_id=42 ORDER BY occurred_at DESC uses idx_audit_log_entity
- EXPLAIN ANALYZE for WHERE actor_id='ops-user-001' ORDER BY occurred_at DESC uses idx_audit_log_actor
- EXPLAIN ANALYZE for WHERE action='UPDATE' ORDER BY occurred_at DESC uses idx_audit_log_action
- Migration uses CONCURRENTLY so it does not lock the table; verify no AccessExclusiveLock during index creation
- All four indexes appear in pg_indexes with the correct table name audit_log
**Depends on:** 10.11-T01

### 10.11-T16 — Expose per-entity audit history from entity detail pages  _(45 min)_
**Context:** PRD-07 §14.2 states: 'The audit history for a rule, partner, or scheme must be accessible within one click from the entity detail view.' Each entity detail page (Partner Detail, Scheme Detail, Rule/Mapping Page) must show a tab or section 'Audit History' that calls GET /admin/audit-log?entity_type={type}&entity_id={id}&from_date={90daysAgo}&to_date={today} and renders a compact read-only table (no export, no filter bar) with columns: Time (KST), Actor, Action, Detail [View]. RBAC: only users with VIEW_AUDIT_LOG see this tab; others see a locked icon and 'Access restricted'. The tab is not a full page navigation - it is an in-page tab on the existing entity detail component.
**Steps:** Create shared EntityAuditHistoryTab component accepting entity_type and entity_id props; On tab activation (lazy-load): call GET /admin/audit-log with entity_type, entity_id, from_date=NOW()-90d, to_date=NOW(), page_size=100; Render compact table: occurred_at (KST), actor_id, action, [View] button (opens AuditEntryDetailPanel); If user lacks VIEW_AUDIT_LOG: render a greyed-out tab with lock icon and tooltip 'Requires OPS_OPERATOR or SUPER_ADMIN'; Add EntityAuditHistoryTab to: PartnerDetailPage, SchemeDetailPage, RuleMappingPage; Write component test: FINANCE_ANALYST sees the locked tab on PartnerDetailPage; OPS_OPERATOR sees audit rows
**Deliverable:** EntityAuditHistoryTab component integrated into PartnerDetailPage, SchemeDetailPage, and RuleMappingPage
**Acceptance / logic checks:**
- Clicking the Audit History tab on Partner id=5 loads GET /admin/audit-log?entity_type=partner&entity_id=5 (lazy, not on page load)
- FINANCE_ANALYST visiting Partner Detail sees 'Audit History' tab with lock icon and no data rows
- Clicking [View] on an audit row opens the AuditEntryDetailPanel with correct entry_id
- Table shows at most 100 rows (from_date=90 days ago); if more exist a 'View full audit log' link navigates to the main Audit Log page pre-filtered
- Audit history tab is present on RuleMappingPage for the current rule's (partner_id, scheme_id, direction) entity
**Depends on:** 10.11-T10, 10.11-T11, 10.11-T04

### 10.11-T17 — Unit tests: AuditLogWriter hash-chain correctness with edge-case vectors  _(45 min)_
**Context:** AuditLogWriter (10.11-T02) must produce correct SHA-256 hash chains. Canonical input: SHA-256(id::text + actor_id + actor_type + action + entity_type + entity_id::text + COALESCE(before_value::jsonb::text,'') + COALESCE(after_value::jsonb::text,'') + occurred_at::ISO8601ms + COALESCE(prev_hash,'')). Edge cases: first row (prev_hash=NULL -> use empty string), before_value=NULL (CREATE), after_value=NULL (DELETE), JSONB with Unicode characters, entity_id=0, concurrent inserts.
**Steps:** Write test vector 1: first row insert - actor_id='sys', actor_type='SYSTEM', action='CREATE', entity_type='partner', entity_id=1, before_value=null, after_value={name:'T-Bank'}, occurred_at='2026-06-05T00:00:00.000Z'; assert entry_hash = expected SHA-256 hex (compute expected value in test setup); Write test vector 2: second row uses prev_hash = row1.entry_hash; assert row2.entry_hash != row1.entry_hash and row2.prev_hash == row1.entry_hash; Write test vector 3: CREATE row (before_value=null) - canonical uses empty string for before_value; assert hash differs from a row where before_value='{}'; Write test vector 4: Unicode in after_value (e.g. partner name '한결원') - assert hash is a valid 64-char hex string and is deterministic across two runs; Write test vector 5: simulate concurrent insert race by calling write() from two threads simultaneously in a test transaction; assert only one prev_hash chain is produced (linear, no forks)
**Deliverable:** Unit test class AuditLogWriterHashChainTest with 5 named test vectors, each with explicit expected hash value or invariant
**Acceptance / logic checks:**
- Test vector 1: entry_hash is exactly the SHA-256 hex of the canonical string with empty prev_hash
- Test vector 2: row2.prev_hash == row1.entry_hash exactly
- Test vector 3: hash for before_value=null differs from hash for before_value='{}'
- Test vector 4: Unicode input produces a valid 64-char lowercase hex entry_hash deterministically
- Test vector 5: concurrent inserts produce a linear chain with no two rows sharing the same prev_hash
**Depends on:** 10.11-T02

### 10.11-T18 — Unit tests: GET /admin/audit-log endpoint filter and pagination logic  _(40 min)_
**Context:** The search endpoint (10.11-T04) must correctly apply all five filters, enforce the 12-month window, paginate at 100 rows, and respect RBAC. Test vectors: (A) exact date boundary, (B) multi-value action filter, (C) entity_type + entity_id combo, (D) 12-month+1-day rejection, (E) page 2 offset correctness, (F) FINANCE_ANALYST rejected.
**Steps:** Seed test DB with 250 audit_log rows: 100 action=UPDATE entity_type=rule, 100 action=CREATE entity_type=partner, 50 action=DELETE entity_type=scheme; occurred_at spanning 2026-01-01 to 2026-06-30; Test A: from_date=2026-01-01T00:00:00Z to_date=2026-01-01T23:59:59Z returns only rows where occurred_at is within that day; Test B: action=UPDATE filter returns exactly 100 rows; Test C: entity_type=rule AND entity_id=specific_id returns only those rows; Test D: from_date=2025-01-01 to_date=2026-01-02 (>366 days) returns 400 DATE_RANGE_EXCEEDS_LIMIT; Test E: page=1 returns rows 1-100; page=2 returns rows 101-200; page=3 returns rows 201-250 (total_count=250); page=4 returns empty items array; Test F: request authenticated as FINANCE_ANALYST returns 403
**Deliverable:** Integration test class AuditLogSearchEndpointTest with 6 named test cases
**Acceptance / logic checks:**
- Test A: response items all have occurred_at between 2026-01-01T00:00:00Z and 2026-01-01T23:59:59Z
- Test B: total_count=100 when action=UPDATE filter applied
- Test D: HTTP 400 with error code DATE_RANGE_EXCEEDS_LIMIT for >366-day window
- Test E: page=3 returns 50 rows; page=4 returns 0 rows with total_count=250
- Test F: FINANCE_ANALYST token returns HTTP 403
**Depends on:** 10.11-T04

### 10.11-T19 — Unit tests: immutability enforcement - no UPDATE or DELETE on audit_log  _(35 min)_
**Context:** SEC-09 §6.3 and DAT-03 §10.7 require that the audit_log table is strictly append-only. The application service account must have INSERT-only permissions. This must be verified via automated tests, not just by convention. Test: (1) the application DB user can INSERT a row; (2) any attempt to UPDATE an audit_log row via the application DB user is rejected with a PostgreSQL permission error; (3) any attempt to DELETE an audit_log row via the application DB user is rejected; (4) the AuditLogWriter service does not expose any update or delete method. Additionally, attempting to delete via the Admin API (if any DELETE /admin/audit-log route were mistakenly wired) must return 403, per PRD-07 §15.10.
**Steps:** Write DB-level test: connect as the application service account; INSERT one row into audit_log; assert success; Attempt UPDATE audit_log SET actor_id='tampered' WHERE id=<inserted_id>; assert PostgreSQL raises 42501 (insufficient_privilege); Attempt DELETE FROM audit_log WHERE id=<inserted_id>; assert PostgreSQL raises 42501; Confirm AuditLogWriter class has no update() or delete() methods (static analysis or reflection test); Confirm no route in the Admin API router maps to DELETE /admin/audit-log or PUT /admin/audit-log; write a route-table scan test asserting no such routes exist
**Deliverable:** Test class AuditLogImmutabilityTest with 5 assertions covering DB permissions and application-layer guards
**Acceptance / logic checks:**
- INSERT by application DB user succeeds
- UPDATE by application DB user raises SQL error code 42501 (insufficient_privilege)
- DELETE by application DB user raises SQL error code 42501
- AuditLogWriter class contains no public update() or delete() method
- Admin router has no DELETE or PUT route matching /admin/audit-log*
**Depends on:** 10.11-T02, 10.11-T03

### 10.11-T20 — Unit tests: IntegrityCheckJob detection of chain breaks and deletions  _(45 min)_
**Context:** The daily hash-chain integrity job (10.11-T08) must detect two failure modes: (1) an entry whose stored entry_hash does not match the recomputed hash (tampering of a field value), and (2) a break in the prev_hash chain (e.g. a row was deleted, causing the next row's prev_hash to not match the predecessor's entry_hash). Test vectors must cover: OK chain, single-field tamper on row 500 of 1000, deleted middle row (rows 1-499 then 501-1000 in sequence), last-row tamper, empty table.
**Steps:** Test vector 1 (OK): seed 1,000 rows with correct hash chain; run job; assert result row has status=OK, rows_checked=1000; Test vector 2 (field tamper): insert 1,000 rows correctly, then UPDATE audit_log SET actor_id='hacked' WHERE id=500 using DBA account (bypassing app-layer); run job; assert status=FAILED, first_break_id=500; Test vector 3 (deletion): insert 1,000 rows, DELETE row 500 using DBA account; run job; assert status=FAILED, first_break_id=501 (the row whose prev_hash no longer matches row 499's entry_hash); Test vector 4 (last-row tamper): tamper row 1000; assert status=FAILED, first_break_id=1000; Test vector 5 (empty table): run job on empty audit_log; assert status=OK, rows_checked=0
**Deliverable:** Test class IntegrityCheckJobTest with 5 test vectors covering OK and all FAILED scenarios
**Acceptance / logic checks:**
- Test vector 1: status=OK, rows_checked=1000, first_break_id=NULL
- Test vector 2: status=FAILED, first_break_id=500
- Test vector 3: status=FAILED, first_break_id=501
- Test vector 4: status=FAILED, first_break_id=1000
- Test vector 5: status=OK, rows_checked=0, no result row error
**Depends on:** 10.11-T08

### 10.11-T21 — E2E test: rule margin change produces correct audit entry visible in UI  _(50 min)_
**Context:** End-to-end acceptance test covering the full flow: OPS_OPERATOR changes rule m_a from 1.00 to 1.25 for SendMN x ZeroPay x INBOUND via the Mapping Page -> system writes audit_log entry -> entry appears in Audit Log list view within 5 seconds (PRD-07 §15.10 acceptance criteria) -> clicking [View] on the entry shows before_value.m_a=1.00 and after_value.m_a=1.25. This test also verifies the entity audit history tab on the Rule Mapping Page shows the same entry.
**Steps:** Seed DB: create hub_user with role OPS_OPERATOR; create rule SendMN x ZeroPay x INBOUND with m_a=1.00, m_b=1.50; Log in as OPS_OPERATOR; navigate to Mapping Page for SendMN x ZeroPay x INBOUND; Change m_a from 1.00 to 1.25; click Save Rule; Assert audit_log contains a row within 5 seconds: entity_type=rule, action=UPDATE, before_value.m_a=1.00 (or '1.00'), after_value.m_a=1.25 (or '1.25'), actor_id=ops_user_id; Navigate to Audit Log screen; search with entity_type=rule and entity_id=<rule_id>; assert the entry is visible in the results table; Click [View] on the entry; assert detail panel shows before_value.m_a=1.00 and after_value.m_a=1.25
**Deliverable:** E2E test AuditLogRuleChangeE2ETest covering the full margin-change -> audit-log-visible flow
**Acceptance / logic checks:**
- audit_log row is present within 5 seconds of Save Rule (poll with 500ms interval up to 5s)
- audit_log row has actor_id matching the OPS_OPERATOR user UUID (not a generic SYSTEM actor)
- Detail panel before_value.m_a is '1.00' or 1.00 (numeric or string, consistent with serialisation)
- Detail panel after_value.m_a is '1.25' or 1.25
- The Audit History tab on the Rule Mapping Page also shows this entry without navigating away
**Depends on:** 10.11-T10, 10.11-T11, 10.11-T16, 10.11-T03

### 10.11-T22 — E2E test: SUPER_ADMIN CSV export for 7-day window completes within 30 seconds  _(45 min)_
**Context:** PRD-07 §14.1 and §15.10 require: CSV export of a 7-day audit log completes within 30 seconds. This E2E test seeds a realistic volume of audit_log rows (~5,000 rows over 7 days), triggers the export, and measures elapsed time. It also verifies CSV integrity: correct headers, no before_value/after_value columns, row count matches the search total_count, and entry for a known rule-change is present.
**Steps:** Seed audit_log with 5,000 rows spread over a 7-day window (2026-06-01 to 2026-06-07) using a test data factory; Log in as SUPER_ADMIN; navigate to Audit Log screen; set date filter to 2026-06-01 - 2026-06-07; click Search; verify total_count=5000; Click [Export CSV]; measure wall-clock time from click to browser download complete (or file save to disk in headless mode); Assert elapsed time <= 30 seconds; Parse downloaded CSV: assert first row is header row with columns id,occurred_at,actor_id,actor_type,action,entity_type,entity_id,ip_address,request_id,has_diff; Assert CSV has exactly 5,001 lines (1 header + 5,000 data rows); assert no before_value or after_value column exists
**Deliverable:** E2E test AuditLogExportE2ETest with timing assertion and CSV integrity checks
**Acceptance / logic checks:**
- Export of 5,000 rows completes in <= 30 seconds
- CSV header row is exactly: id,occurred_at,actor_id,actor_type,action,entity_type,entity_id,ip_address,request_id,has_diff
- CSV has 5,001 lines (1 header + 5,000 data rows)
- No before_value or after_value column appears in the CSV
- OPS_OPERATOR attempting export sees button absent (not rendered); direct API call returns 403
**Depends on:** 10.11-T06, 10.11-T10, 10.11-T12

### 10.11-T23 — Documentation: Audit Log Viewer operator guide  _(35 min)_
**Context:** The Admin System requires inline contextual documentation for operators. The audit log viewer is a compliance-critical feature; operators must understand what is logged, what immutability means, how to interpret hash chain fields, and how to use the export for regulatory submissions. Document format: Confluence-style markdown page stored alongside the Admin System frontend source. Audience: GME Ops and Finance staff. Sections: Overview, What is logged (categories table), How to search (filter reference), Export options (sync vs async), Hash chain integrity (Super Admin only), Data retention (7 years for config changes per SEC-09 §6.2), FAQ.
**Steps:** Write docs/admin/audit-log-viewer.md covering: Overview (1 paragraph on purpose per PRD-07 §13.1), What is logged (reproduce the 8-category table from PRD-07 §13.2), Search filters reference (reproduce filter table from PRD-07 §13.5 with examples), Export (sync <= 30 days; async > 30 days; email notification; SUPER_ADMIN only), Hash Chain Integrity (entry_hash/prev_hash definition; how to interpret the Chain Integrity section; what SUPER_ADMIN should do if integrity check fails), Data retention (7 years for config change audit; 3 years for auth events; 1 year for API access logs), FAQ (3 entries: 'Can I delete an audit entry?', 'Why is before_value null?', 'The export job shows FAILED - what next?'); Ensure all field names match the DB schema (actor_id, entity_type, entity_id, before_value, after_value, occurred_at, entry_hash, prev_hash); Add a worked example showing the JSON diff for a rule m_a change from 1.00 to 1.25
**Deliverable:** docs/admin/audit-log-viewer.md operator guide covering all 7 sections with worked examples
**Acceptance / logic checks:**
- Document contains the 8-category table from PRD-07 §13.2 (config changes, FX rate changes, credential ops, refund ops, prefunding ops, settlement actions, user management, auth events)
- Export section clearly distinguishes sync path (<= 30 days) from async path (> 30 days) with the 30-second SLA stated
- Hash Chain section explains entry_hash and prev_hash without requiring the reader to know SHA-256 implementation details
- FAQ answers 'Can I delete an audit entry?' with 'No - audit entries are permanently immutable; deletion requires DBA escalation and is itself logged'
- Worked example shows before_value.m_a: 1.00 and after_value.m_a: 1.25 for a rule update event
**Depends on:** 10.11-T11, 10.11-T12


## WBS 12.2 — Admin screens: wireframe→hi-fi
### 12.2-T01 — Define design system tokens file (colours, typography, spacing)  _(40 min)_
**Context:** WBS 12.2 — Admin screens wireframe to hi-fi. UX-11 §2 defines the single design system shared by both Admin System and Partner Portal. Tokens: --color-brand #1A56DB, --color-danger #E02424, --color-warning #D97706, --color-success #057A55, --color-neutral-900 #111928, --color-neutral-600 #4B5563, --color-neutral-200 #E5E7EB, --color-neutral-50 #F9FAFB, --color-surface #FFFFFF. Typography: Inter font stack; page-title 24 px/600, section-heading 18 px/600, body 14 px/400, caption 12 px/400, mono 13 px/400 (JetBrains Mono). Spacing base 8 px; tokens 4,8,12,16,24,32,48,64 px. All UI string literals must be externalised to an i18n key file from first commit (UX-11 A-05).
**Steps:** Create tokens/colors.css (or design-tokens.ts) exporting every colour token listed above as CSS custom properties.; Create tokens/typography.ts exporting font-stack, size, and weight constants.; Create tokens/spacing.ts exporting the 8-scale array.; Create i18n/en.json as the English string key file (empty but structured by module).; Write a storybook story or Jest snapshot that reads each token and asserts the hex value.
**Deliverable:** tokens/ directory with colors, typography, spacing files; i18n/en.json; token smoke-test snapshot
**Acceptance / logic checks:**
- --color-brand resolves to #1A56DB and --color-danger to #E02424 in the compiled CSS output.
- All 9 colour tokens, 5 typography roles, and 8 spacing values are exported and importable by name.
- i18n/en.json is valid JSON and contains at least one key per top-level module (dashboard, schemes, partners, rules, transactions, prefunding, refunds, settlement, fx, users, audit).
- Snapshot test passes with zero diff after initial commit — proves tokens are stable.

### 12.2-T02 — Build StatusBadge component for transaction and settlement statuses  _(35 min)_
**Context:** UX-11 §2.7 defines pill badges: 12 px bold text, 4 px border-radius, 6 px horizontal padding. Transaction badges: APPROVED (#DEF7EC bg / #057A55 text), DECLINED (#FDE8E8 / #E02424), PENDING (#FEF3C7 / #D97706), CANCELLED (#E5E7EB / #4B5563), REFUNDED (#E0F2FE / #0369A1), UNCERTAIN (#FEF9C3 / #92400E). Settlement badges: DRAFT (#E5E7EB / #4B5563), CONFIRMED (#DEF7EC / #057A55), DISPUTED (#FDE8E8 / #E02424). Each badge must include aria-label with full text (not just colour) for screen readers (WCAG 2.1 AA).
**Steps:** Create components/StatusBadge.tsx accepting status string and optional variant (transaction | settlement).; Map each status to its background and text colour from the token table above.; Add aria-label={status} to the pill element.; Write stories for all 9 statuses.; Write Jest tests asserting correct colours and aria-label for APPROVED, DECLINED, UNCERTAIN, REFUNDED, and DISPUTED.
**Deliverable:** components/StatusBadge.tsx with stories and unit tests covering all 9 status values
**Acceptance / logic checks:**
- APPROVED badge renders with bg #DEF7EC and text #057A55.
- UNCERTAIN badge renders with bg #FEF9C3 and text #92400E.
- aria-label equals the status string for each variant.
- Unknown status string does not crash — falls back to neutral styling.
- WCAG colour-contrast ratio >= 4.5:1 verified for all badge combinations (test helper or manual check).
**Depends on:** 12.2-T01

### 12.2-T03 — Build reusable DataTable component (max 6 columns, sticky header, sort)  _(45 min)_
**Context:** UX-11 §2.6: header row 12 px uppercase neutral-600 on neutral-50, body 14 px neutral-900, row height 44 px (compact 36 px), alternating neutral-50/white. Right-align numeric/currency columns; left-align text; centre-align badges. Sticky header on scroll. Sortable columns with up/down icons. Max 6 columns (convention). Hover state: neutral-50 on hovered row. Tables must have <th scope=col> for screen readers.
**Steps:** Create components/DataTable.tsx accepting columns config (key, label, align, sortable), rows array, and optional compact prop.; Implement sticky thead via CSS position:sticky.; Implement client-side sort (asc/desc toggle) per sortable column.; Add hover background via CSS :hover on tr.; Enforce max 6 columns with a console.warn in development.; Write stories showing a 6-column table and a compact variant.
**Deliverable:** components/DataTable.tsx with column config API, sticky header, sort, and storybook story
**Acceptance / logic checks:**
- Rendering 7 columns logs a warning in development mode.
- Clicking a sortable column header toggles asc/desc; aria-sort attribute updates accordingly.
- thead remains visible when table body is scrolled 300 px.
- Numeric columns have text-align:right; text columns text-align:left.
- th elements carry scope=col attribute.
**Depends on:** 12.2-T01

### 12.2-T04 — Build ConfirmModal component (two-step confirmation pattern)  _(40 min)_
**Context:** UX-11 §2.10 and §2.11: modals max-width 560 px (confirm) or 720 px (review). Overlay 50% black scrim; click-outside closes non-destructive modals. Close (X) button in header. Focus trap (keyboard cycles within modal). Dangerous actions use --color-danger filled button. Two-step pattern: Step 1 shows before/after diff summary; Step 2 shows result toast. aria-modal=true, role=dialog.
**Steps:** Create components/ConfirmModal.tsx accepting title, description, diffRows (array of {field,before,after}), onConfirm, onCancel, and isDangerous boolean.; Render diff table only when diffRows is provided.; Style primary button as --color-danger when isDangerous=true.; Implement focus trap using a focus-trap library or manual Tab key handler.; Emit onConfirm on primary button click; onCancel on Cancel or X click.; Write tests: focus trap cycles within modal; clicking overlay calls onCancel; isDangerous applies danger colour.
**Deliverable:** components/ConfirmModal.tsx with diff table, focus trap, and dangerous/safe variants
**Acceptance / logic checks:**
- Tab key cycles through focusable elements within open modal and does not escape to page behind.
- isDangerous=true renders primary button with background --color-danger.
- diffRows renders a table with Before and After columns.
- Clicking the backdrop scrim calls onCancel.
- role=dialog and aria-modal=true are present on the modal root element.
**Depends on:** 12.2-T01

### 12.2-T05 — Build AdminShell layout (sidebar, topbar, breadcrumb, content area)  _(55 min)_
**Context:** UX-11 §3.1: left sidebar 240 px fixed, collapsible to 56 px icon-only. Top bar 56 px: hamburger left, app title centre, user name + role badge + logout right. Content area fills remaining width, max-width 1,400 px centred, 32 px padding. Breadcrumb above page title (Module / Sub-section / Current page). Active nav item: brand-colour left border + brand background tint. Hover tooltip. Responsive: sidebar collapses to icon-only at < 1,280 px; at 1,024-1,279 px hidden with hamburger overlay. Nav items: Dashboard, Schemes, Partners, Transactions, Settlement, FX Rates, Audit Log, Settings.
**Steps:** Create layouts/AdminShell.tsx with sidebar, topbar, and content area regions.; Implement sidebar collapse toggle storing state in local storage.; Render breadcrumb from a prop array of {label, href} items.; Highlight active nav item based on current route path.; Implement responsive breakpoints per UX-11 §8.3.; Write a smoke test asserting sidebar renders 8 nav items.
**Deliverable:** layouts/AdminShell.tsx with responsive sidebar, topbar, breadcrumb, and collapse state
**Acceptance / logic checks:**
- At viewport >= 1,440 px sidebar is 240 px wide and all 8 nav items are visible.
- At viewport 1,024-1,279 px sidebar is hidden; hamburger button is present and opens overlay on click.
- Clicking hamburger at >= 1,280 px collapses sidebar to 56 px (icon-only).
- Active route item shows brand-colour left border.
- Breadcrumb renders links for each item except the last (current page), which is plain text.
**Depends on:** 12.2-T01

### 12.2-T06 — Hi-fi design: Admin Dashboard page  _(55 min)_
**Context:** UX-11 §4.1 and PRD-07 §3: Dashboard shows 4 KPI cards (today txns, today volume, success rate, alerts), prefunding balances table (Partner, Balance, Threshold, Status with traffic-light), recent transactions table (last 20), and settlement batch status row (ZP0011 through ZP0065 with check/warning icons). Prefunding status: green >= 150% of threshold, amber 100-150%, red < 100%. Settlement batch statuses use check (sent/received) or X+LATE labels. PRD-07 G-04: Ops must diagnose failed transaction within 5 min.
**Steps:** Create pages/admin/Dashboard.tsx consuming mock data for KPI cards, prefunding balances, and batch statuses.; Render KPI cards using a KpiCard component with label, value, and delta.; Render prefunding balances using DataTable (max 4 cols: Partner, Balance USD, Threshold USD, Status).; Apply traffic-light StatusBadge: green OK / amber LOW / red CRITICAL based on balance vs threshold.; Render settlement batch status row showing file codes and icons.; Add Refresh button to prefunding panel that re-fetches data.
**Deliverable:** pages/admin/Dashboard.tsx with KPI cards, prefunding table, recent transactions stub, and batch status row
**Acceptance / logic checks:**
- SendMN balance USD 45,200 with threshold USD 10,000 shows green OK badge (45,200 >= 150% of 10,000 = 15,000).
- T-Bank balance USD 8,400 with threshold USD 10,000 shows red badge (8,400 < 10,000).
- Settlement batch row renders ZP0011 through ZP0065 with distinct sent/late visual indicators.
- KPI cards show label, numeric value, and delta indicator.
- Page renders without console errors on first load with mock data.
**Depends on:** 12.2-T02, 12.2-T03, 12.2-T05

### 12.2-T07 — Hi-fi design: Scheme List page  _(40 min)_
**Context:** UX-11 §4.2 and PRD-07 §4.2: Scheme list table columns: Name, Country (ISO 3166-1), Currency (ISO 4217), Status (Draft/Testing/Active/Suspended), Actions (Edit/View). Status uses StatusBadge. Max 5 columns. New Scheme button top-right. Actions: View, Edit, Activate/Suspend, View Rules. Page title Breadcrumb: Schemes. Empty state shows no-schemes message with + New Scheme CTA.
**Steps:** Create pages/admin/schemes/SchemeList.tsx.; Use DataTable with columns: Name, Countries, Payout Currency, Status, Actions.; Render Status column with StatusBadge (Active = success, Draft = neutral, Testing = warning, Suspended = danger).; Add + New Scheme button routing to /admin/schemes/new.; Render empty state component when list is empty.; Write unit test: table renders 2 rows from mock data; empty state renders when array is empty.
**Deliverable:** pages/admin/schemes/SchemeList.tsx with table, status badges, and empty state
**Acceptance / logic checks:**
- ZeroPay row shows Status badge Active in green (#057A55) and Edit action button.
- QPay row with Status Draft shows neutral badge.
- Empty list renders the empty-state component with + New Scheme CTA.
- + New Scheme button navigates to /admin/schemes/new.
- Table has at most 5 columns and no horizontal scroll at 1,440 px.
**Depends on:** 12.2-T02, 12.2-T03, 12.2-T05

### 12.2-T08 — Hi-fi design: Scheme Create/Edit form — Identity and Settlement tabs  _(55 min)_
**Context:** UX-11 §4.2 and PRD-07 §4.3.1, §4.3.3: Scheme form uses tabbed layout [Identity] [Settlement Terms] [Connection] [Fee Table]. Identity tab fields: Scheme Name (text, required, max 100, unique), Scheme Code (uppercase alphanumeric, max 20, unique, immutable after activation), Logo URL (HTTPS optional), Supported Countries (multi-select ISO 3166-1, >= 1), Supported Payment Modes (MPM/CPM checkboxes, >= 1), Payout Currency (select ISO 4217), Status (Draft/Testing/Active/Suspended, default Draft). Settlement Terms: Counterparty Name, Settlement Currency, Settlement Model (Net/Gross), GME Fee Share % (0-100), Morning Settlement Cutoff (KST time), Afternoon Cutoff (optional), Report Due Day (1-28 or EOM).
**Steps:** Create pages/admin/schemes/SchemeForm.tsx with tabbed layout using a Tabs component.; Implement Identity tab with all fields, required markers, and blur-on-field validation.; Implement Settlement Terms tab with all fields including conditional cutoff times.; Implement Save/Cancel buttons; Save is disabled while validation errors exist.; Show a change diff ConfirmModal before saving an existing scheme (Review Changes pattern).; Write validation unit tests for Scheme Code (rejects lowercase, max 20 chars) and GME Fee Share (rejects > 100).
**Deliverable:** pages/admin/schemes/SchemeForm.tsx covering Identity and Settlement tabs with inline validation
**Acceptance / logic checks:**
- Scheme Code field rejects lowercase input and values longer than 20 characters with inline error below the field.
- GME Fee Share accepts 70 and rejects 101 with error message 'Enter a value between 0 and 100'.
- Saving an existing record opens ConfirmModal with a diff table showing changed fields.
- Status field defaults to Draft on a new scheme form.
- Submit button is disabled while any validation error is present.
**Depends on:** 12.2-T04, 12.2-T05

### 12.2-T09 — Hi-fi design: Scheme form — Connection and Fee Table tabs  _(50 min)_
**Context:** PRD-07 §4.3.2 and §4.3.4: Connection tab fields: Integration Type (SFTP/REST/ISO 8583). SFTP fields shown conditionally: Host, Port (default 22), Username, Password/Key (masked, stored encrypted, plaintext never logged), Inbound Path, Outbound Path. REST fields: API Base URL, API Key/Secret (masked). Connection Test button runs inline test showing success/failure. Fee Table tab: merchant type rows with Domestic Rate % and Cross-Border Rate %. ZeroPay reference: General = 0.80% domestic, 1.70% cross-border. Rows must match ZeroPay merchant type codes (stored as free-text string per A-06 until KFTC confirms codes). Secret values never appear in the rendered HTML after initial entry.
**Steps:** Add Connection tab to SchemeForm showing SFTP fields when Integration Type = SFTP, REST fields when REST.; Mask password/key fields (type=password); add a show/hide toggle.; Implement Connection Test button that calls a mock test endpoint and shows inline success or failure result.; Add Fee Table tab with an editable table of {merchant_type, domestic_rate, cross_border_rate} rows and Add Row / Delete Row actions.; Pre-populate one row with merchant_type=GENERAL, domestic_rate=0.80, cross_border_rate=1.70 for ZeroPay.; Write test: switching Integration Type from SFTP to REST hides SFTP fields and shows REST fields.
**Deliverable:** Connection and Fee Table tabs added to SchemeForm with conditional rendering and connection test button
**Acceptance / logic checks:**
- Selecting Integration Type = REST hides SFTP Host/Port/Username/Password/Path fields.
- Password/Key field renders as type=password; toggling show reveals plaintext and hides it again.
- Connection Test button shows a success banner with green colour or failure with red colour inline.
- Fee Table row with merchant_type=GENERAL, domestic_rate=0.80, cross_border_rate=1.70 is pre-populated.
- Adding a new row inserts an empty editable row; deleting a row removes it from the table.
**Depends on:** 12.2-T08

### 12.2-T10 — Hi-fi design: Partner List page  _(35 min)_
**Context:** UX-11 §4.3 and PRD-07 §5.2: Partner list columns: Partner Name, Type (LOCAL/OVERSEAS), Schemes (count or names), Status (Draft/Active/Suspended), Prefunding Balance (USD shown only for OVERSEAS partners). Actions: View, Edit, Manage Rules, View Transactions, Suspend/Reactivate. + New Partner button top-right. Prefunding balance column is blank/NA for LOCAL partners. Status badge colours match StatusBadge component.
**Steps:** Create pages/admin/partners/PartnerList.tsx.; Use DataTable with columns: Name, Type, Schemes, Status, Prefunding Balance (USD or N/A), Actions.; Display Prefunding Balance only for OVERSEAS partners; show em-dash for LOCAL.; Add + New Partner button routing to /admin/partners/new.; Render empty state when list is empty.; Write test: LOCAL partner row shows N/A for Prefunding Balance; OVERSEAS partner shows USD value.
**Deliverable:** pages/admin/partners/PartnerList.tsx with correct Prefunding Balance conditional rendering
**Acceptance / logic checks:**
- GME Remit (LOCAL) row shows N/A in Prefunding Balance column.
- SendMN (OVERSEAS) row shows USD 45,200.00 formatted per money format spec.
- Active partner shows green StatusBadge; Draft shows neutral.
- + New Partner navigates to /admin/partners/new.
- Table does not exceed 6 columns.
**Depends on:** 12.2-T02, 12.2-T03, 12.2-T05

### 12.2-T11 — Hi-fi design: New Partner wizard — Steps 1 and 2 (Identity and Credentials)  _(55 min)_
**Context:** UX-11 §4.3 and PRD-07 §5.3.1, §5.3.2, §5.3.4: 4-step wizard. Step 1 Identity: Partner Name (text, required, max 100, unique), Partner Code (uppercase alphanumeric, max 20, unique, immutable after activation), Partner Type (LOCAL/OVERSEAS radio, required), Contact Email (required, email), Webhook URL (required, HTTPS), Rate Quote TTL seconds (integer 60-1800, default 300), Collection Currency (ISO 4217), Settlement Currency (ISO 4217). Step 2 Credentials: Generate API Key Pair button. After generation: API Key ID displayed; API Secret shown once with warning 'Secret cannot be retrieved later'. Step 3 is Prefunding (OVERSEAS only). Step 4 is Review and Activate.
**Steps:** Create pages/admin/partners/PartnerWizard.tsx with a stepper component showing 4 steps.; Implement Step 1 form with all identity fields; validate Partner Code is uppercase alphanumeric and max 20 chars.; Implement Step 2 with Generate API Key Pair button. On click, display mock API Key ID and a warning-styled secret field.; Disable Next on Step 2 until credentials are generated.; Add stepper progress indicator (1 of 4, 2 of 4 etc) at top.; Write test: Rate Quote TTL=59 is rejected; TTL=300 passes; Webhook URL without https:// is rejected.
**Deliverable:** PartnerWizard.tsx steps 1 and 2 with identity validation and credential generation UI
**Acceptance / logic checks:**
- Partner Code 'sendmn' (lowercase) is rejected with error; 'SENDMN' is accepted.
- Rate Quote TTL=59 shows error 'TTL must be between 60 and 1,800 seconds'; TTL=1800 passes.
- Webhook URL 'http://example.com' shows error 'Webhook URL must be a valid HTTPS address'.
- After clicking Generate API Key Pair, the secret field appears with a warning banner 'Secret cannot be retrieved later'.
- Next button on Step 2 is disabled until Generate has been clicked.
**Depends on:** 12.2-T04, 12.2-T05

### 12.2-T12 — Hi-fi design: New Partner wizard — Steps 3 and 4 (Prefunding and Review/Activate)  _(50 min)_
**Context:** PRD-07 §5.3.3 and §5.3.5: Step 3 Prefunding Setup shown ONLY when Partner Type = OVERSEAS. Fields: Prefunding Account ID (text, required), Initial Prefunding Balance (decimal USD, required, min 0), Low-Balance Alert Threshold (decimal USD, default 10000), Alert Recipients (comma-separated emails, min 1). Step 4 Review: summary of all entered values, radio (Activate now / Save as Draft), Create Partner button. Activating requires settlement currency set, webhook tested optional, credentials generated. For OVERSEAS: prefunding account configured and initial balance >= 0.
**Steps:** Add Step 3 to PartnerWizard: skip rendering the Prefunding section entirely if Partner Type = LOCAL; show it if OVERSEAS.; Implement Step 3 fields with Low-Balance Alert Threshold defaulting to 10000.; Implement Step 4 as a read-only summary of all wizard fields with Activate Now / Save as Draft radio.; Validate Alert Recipients: each comma-separated value must be a valid email.; On Create Partner click, show ConfirmModal summarising the activation.; Write test: with Partner Type = LOCAL, Step 3 is absent from stepper; with OVERSEAS it is present.
**Deliverable:** PartnerWizard.tsx steps 3 and 4 complete; LOCAL partners skip step 3
**Acceptance / logic checks:**
- Partner Type = LOCAL shows stepper as 1-2-4 (step 3 absent) or collapses step 3.
- Low-Balance Alert Threshold defaults to USD 10,000 when step 3 is rendered.
- Alert Recipients 'noemail' is rejected; 'ops@gme.com,finance@gme.com' is accepted.
- Step 4 Review section lists Partner Name, Partner Code, Type, and all key fields entered.
- Clicking Create Partner opens ConfirmModal with entity name before writing.
**Depends on:** 12.2-T04, 12.2-T11

### 12.2-T13 — Hi-fi design: Mapping Page — Section 1 Currency Setup (read-only auto-derived)  _(35 min)_
**Context:** UX-11 §4.4 and PRD-07 §6.4.1: Section 1 of the 4-section Mapping Page. ALL fields are read-only. Derived from: collection_ccy from Partner.collection_currency; settle_a_ccy from Partner.settlement_currency; usd_intermediary always USD; settle_b_ccy from Scheme.settlement_currency; payout_ccy from Scheme.payout_currency. For SendMN x ZeroPay x INBOUND: collection=MNT, settle_A=USD, settle_B=KRW, payout=KRW. Same-currency short-circuit: if all four = same currency (e.g. all KRW for GME Remit), display a prominent blue info banner: 'Same-currency rule - USD pool bypassed. collection_amount = target_payout + service_charge directly. Margin must be 0.' Read-only fields must use greyed background or lock icon (PRD-07 §14.2).
**Steps:** Create components/mapping/CurrencySetupSection.tsx accepting partnerCcy, settleACcy, settleBCcy, payoutCcy props.; Render 4 labelled read-only fields with greyed background (neutral-50 background, lock icon).; Detect same-currency short-circuit: if all four values are equal, render a blue info banner with the exact message above.; Add a sub-note under the section: 'Modify via Partner or Scheme edit, not here.'; Write unit test for INBOUND SendMN: assert MNT/USD/KRW/KRW values rendered.; Write unit test for DOMESTIC GME Remit: assert all-KRW with short-circuit banner visible.
**Deliverable:** components/mapping/CurrencySetupSection.tsx with auto-derive display and same-currency short-circuit banner
**Acceptance / logic checks:**
- SendMN x INBOUND renders Collection=MNT, Settle A=USD, Settle B=KRW, Payout=KRW with all fields greyed/locked.
- GME Remit x DOMESTIC (all KRW) renders the blue banner containing 'Same-currency rule - USD pool bypassed'.
- No field in Section 1 is editable (all inputs are disabled or read-only divs).
- Attempting to modify a field programmatically via a test does not change the displayed value.
- Lock icon is visible next to each read-only field.
**Depends on:** 12.2-T01, 12.2-T05

### 12.2-T14 — Hi-fi design: Mapping Page — Section 2 Rate Configuration  _(50 min)_
**Context:** PRD-07 §6.4.2: Two rate slots (Collection Leg and Payout Leg). Each slot shows: Pair label (e.g. USD -> KRW), auto-derived Source badge (IDENTITY/LIVE/MANUAL/PARTNER), current treasury rate (if LIVE or MANUAL), Override toggle (LIVE/MANUAL available only when auto-derived=LIVE), Manual rate value field (editable only when override=MANUAL, stored as treasury_override_coll or treasury_override_pay), Override expiry date (optional). Constraints: operator can only override LIVE -> MANUAL; cannot change IDENTITY, PARTNER; same-currency rules show both slots as IDENTITY with toggle disabled. MANUAL rate guard-rail: +-20% of current LIVE rate triggers a warning dialog (configurable, default 20%, per A-09). MANUAL rate must be > 0.
**Steps:** Create components/mapping/RateConfigSection.tsx accepting two rate slot configs.; Render Source badge for each slot using StatusBadge or a custom RateSourceBadge.; Show Override toggle (LIVE/MANUAL) only when auto-derived source = LIVE.; Show manual rate input and expiry date fields only when override = MANUAL.; Implement guard-rail: if manual rate deviates > 20% from current live rate, open ConfirmModal with deviation percentage message.; Write tests: IDENTITY slot has toggle disabled; LIVE->MANUAL toggle shows manual rate input; 25% deviation opens warning modal.
**Deliverable:** components/mapping/RateConfigSection.tsx with conditional LIVE/MANUAL toggle, guard-rail warning, and read-only IDENTITY/PARTNER slots
**Acceptance / logic checks:**
- Collection leg with settle_A=USD renders IDENTITY badge with disabled toggle.
- Payout leg with settle_B=KRW renders LIVE badge; toggling to MANUAL reveals manual rate input.
- Entering a manual KRW rate of 1080 when LIVE rate is 1350 (deviation = 20%) shows the guard-rail confirmation dialog.
- Entering a manual rate of 0 is rejected with error 'Manual rate must be greater than 0'.
- PARTNER source badge shows as PARTNER with toggle disabled.
**Depends on:** 12.2-T13

### 12.2-T15 — Hi-fi design: Mapping Page — Section 3 Margin Configuration with inline validation  _(40 min)_
**Context:** PRD-07 §6.4.3 and UX-11 §7.2: Fields m_a (Collection-Side Margin %) and m_b (Payout-Side Margin %), both decimal, >= 0, <= 100, max 4 dp. Combined margin display (m_a + m_b, auto-updated). Cross-border rule constraint: m_a + m_b >= 2.00%; Save disabled if not met; inline error: 'Combined margin must be at least 2.00% for cross-border rules'. Same-currency rule: both locked at 0.00 (fields disabled). Margin reduction warning (inline, not blocker): 'Reducing the margin will lower GME revenue on new transactions. Confirm this is intentional.' Margin fields display 4 decimal places.
**Steps:** Create components/mapping/MarginSection.tsx accepting m_a, m_b, isSameCurrency, isEditing props.; Auto-compute combinedMargin = m_a + m_b and display with green check or red error icon.; Show inline error below the combined display when combined < 2.00% for cross-border rules.; Lock m_a and m_b to 0.00 and disable fields when isSameCurrency=true.; Show inline warning (amber, non-blocking) when edited m_a or m_b < previous saved value.; Write tests: m_a=1.00 m_b=0.90 combined=1.90 triggers error; m_a=1.00 m_b=1.00 combined=2.00 shows check; same-currency locks both to 0.
**Deliverable:** components/mapping/MarginSection.tsx with combined margin live display, cross-border validation, and same-currency lock
**Acceptance / logic checks:**
- m_a=1.00 m_b=0.90 shows combined 1.90% with red error 'Combined margin must be at least 2.00% for cross-border rules'.
- m_a=1.00 m_b=1.00 shows combined 2.00% with green check icon.
- isSameCurrency=true disables both inputs and displays 0.0000% in each.
- Reducing m_a from 1.25 to 0.80 shows amber warning text without blocking further input.
- Combined margin live-updates on each keystroke without requiring form submit.
**Depends on:** 12.2-T13

### 12.2-T16 — Hi-fi design: Mapping Page — Section 4 Service Charge with volume tier table  _(45 min)_
**Context:** PRD-07 §6.4.4: service_charge_amount (decimal >= 0, max 4 dp), service_charge_ccy shown as display-only (auto-set to Settle A ccy; cannot be changed). Examples: GME Remit -> KRW 500; SendMN -> USD 0.50. Optional volume-tier table: toggle to enable; columns Tier From (USD), Tier To (USD, blank = and above), Service Charge (Settle A ccy). Validation: tier ranges contiguous and non-overlapping (gaps flagged as warning); each tier charge >= 0; flat charge becomes fallback when tier table enabled. Currency label shown prominently next to amount field.
**Steps:** Create components/mapping/ServiceChargeSection.tsx accepting settleACcy, existing flat charge, and optional tiers.; Render flat charge input with currency suffix label showing settleACcy prominently.; Add a toggle Enable volume-tier table; when toggled on, render the tier table with Add Tier / Remove Tier actions.; Validate tier table on blur: detect gaps (warn) and overlaps (error).; Write test: settleACcy=KRW renders 'KRW' label next to amount; settleACcy=USD renders 'USD'.; Write test: tier from=0 to=100 and from=200 to=300 (gap 100-200) shows gap warning.
**Deliverable:** components/mapping/ServiceChargeSection.tsx with currency label, flat charge, and optional volume-tier table
**Acceptance / logic checks:**
- settleACcy=KRW renders currency label 'KRW' prominently next to the flat charge input.
- Negative flat charge value is rejected with error 'Service charge must be zero or positive'.
- Enabling the tier toggle renders an empty tier table with Add Tier button.
- Tier from=50 to=100 and from=50 to=200 triggers an overlap error.
- Tier from=0 to=100 and from=200 to=300 triggers a gap warning (not a hard error).
**Depends on:** 12.2-T13

### 12.2-T17 — Assemble full Mapping Page (4 sections + Review Changes + Save flow)  _(55 min)_
**Context:** UX-11 §4.4 and PRD-07 §6.4.5: Four sections displayed vertically. Rule key: Partner x Scheme x Direction. Header: Rule name, Last modified by actor at timestamp, Audit -> link. Sections 1-4 in order. Footer: Cancel button, Review Changes button. Review Changes opens ConfirmModal with before/after diff per changed field. Confirming writes atomically; each changed field emits audit event rule_field_changed with actor_user_id, timestamp_utc, rule_key {partner, scheme, direction}, field, previous_value, new_value. On first save status=Draft; Activate Rule button then appears. Editing active rule: confirmation dialog states 'These changes will apply to all new transactions from this moment. Existing committed transactions are not affected.'
**Steps:** Create pages/admin/rules/MappingPage.tsx composing CurrencySetupSection, RateConfigSection, MarginSection, ServiceChargeSection.; Implement Review Changes button that opens ConfirmModal with a diff of all changed fields.; After Save, call an onSave callback with the full rule payload and a field-change audit list.; Show Activate Rule button when rule status = Draft; show active-rule warning in ConfirmModal otherwise.; Wire breadcrumb: Partners / {partnerName} / Rules / {scheme} x {direction}.; Write integration test: change m_a from 1.00 to 1.25, click Review Changes, assert diff row shows field=m_a previous=1.00 new=1.25.
**Deliverable:** pages/admin/rules/MappingPage.tsx assembling all 4 sections with Review Changes diff modal and audit payload
**Acceptance / logic checks:**
- Page header shows Rule: SendMN x ZeroPay x Inbound and a clickable Audit -> link.
- Changing m_a from 1.00 to 1.25 and clicking Review Changes shows diff table row {field: m_a, before: 1.00%, after: 1.25%}.
- Save for a new rule sets status=Draft and reveals Activate Rule button.
- Saving an active rule shows confirmation 'These changes will apply to all new transactions from this moment. Existing committed transactions are not affected.'
- Audit payload emitted on save contains rule_key with partner=SENDMN, scheme=ZEROPAY, direction=INBOUND and changed fields list.
**Depends on:** 12.2-T14, 12.2-T15, 12.2-T16

### 12.2-T18 — Hi-fi design: Rule List page (under Partner detail Rules tab)  _(45 min)_
**Context:** PRD-07 §6.2 and UX-11 §4.3: Rule list accessed from Partner Edit under Rules tab. Columns: Scheme Name, Direction, Rate Source (summary: LIVE/MANUAL/IDENTITY), Combined Margin %, Service Charge (Settle A ccy), Status (Active/Inactive/Draft), Last Modified. Actions: View/Edit (opens Mapping Page), Activate/Deactivate, View Audit History. + Add Rule button.
**Steps:** Create components/partner/RuleListTab.tsx accepting partnerId and showing all rules for that partner.; Use DataTable with columns: Scheme, Direction, Rate Source, Margin (combined %), Service Charge, Status, Actions.; Combined margin column: grey if same-currency (0%), yellow if < 2% (invalid state), green if >= 2%.; + Add Rule button opens a dialog to select Scheme and Direction before navigating to MappingPage.; View Audit History action routes to /admin/audit?entityId={ruleId}.; Write test: rule with m_a=1.00 m_b=1.00 shows combined 2.00% in green; m_a=0 m_b=0 cross-border shows grey.
**Deliverable:** components/partner/RuleListTab.tsx with margin colour coding and Add Rule dialog
**Acceptance / logic checks:**
- SendMN rule INBOUND shows Combined Margin 2.00% in green.
- Draft rule has Activate action available; Active rule has Deactivate action.
- Clicking + Add Rule opens a dialog with Scheme selector and Direction selector before proceeding.
- View Audit History navigates to audit log filtered by rule entity ID.
- Table has exactly 6 columns (Scheme, Direction, Rate Source, Margin, Status, Actions — Service Charge can be in a sub-row or merged).
**Depends on:** 12.2-T05, 12.2-T17

### 12.2-T19 — Hi-fi design: Rule Audit History view  _(35 min)_
**Context:** PRD-07 §6.5 and UX-11 §4.11: Each rule has an Audit History tab showing: Timestamp (UTC), Actor (display name + ID), Field Changed, Previous Value, New Value, Effective From. Export as CSV. Audit entries are append-only; no delete UI. Breadcrumb: Partners / {partner} / Rules / {rule} / Audit History.
**Steps:** Create pages/admin/rules/RuleAuditHistory.tsx showing the rule key header and audit table.; Use DataTable with columns: Timestamp, Actor, Field, Previous Value, New Value, Effective From.; Add Export CSV button that triggers a download of all displayed rows.; Render Timestamp in UTC ISO 8601 format.; Write test: 3 mock audit entries render as 3 rows in correct column order.; Write test: Export CSV button calls the download handler with correct filename pattern rule_{ruleId}_audit_{date}.csv.
**Deliverable:** pages/admin/rules/RuleAuditHistory.tsx with audit table and CSV export
**Acceptance / logic checks:**
- Table renders 3 mock rows with correct field values in all 6 columns.
- Timestamps render in YYYY-MM-DD HH:mm:ss UTC format.
- Export CSV button triggers file download with at least the 6 column headers.
- Page is accessible within one click from the Mapping Page Audit -> link.
- No edit, delete, or update controls exist on the audit history page.
**Depends on:** 12.2-T03, 12.2-T17

### 12.2-T20 — Hi-fi design: FX Rates List and Update pages  _(45 min)_
**Context:** UX-11 §4.10 and PRD-07 §7.2, §7.3: Rate list columns: Pair (USD/{ccy}), Rate (6 dp), Source (MANUAL/LIVE/IDENTITY), Updated By, Updated At, Actions (Edit; locked for IDENTITY). Update form: Currency select, New Rate > 0 max 6 dp, Effective From (datetime, default now, may be future-dated), Notes (optional). Guard-rail: if new rate deviates > 10% from current, show warning dialog 'This rate is X% different from the current value of Y. Please confirm.' USD (usd_usd) is IDENTITY, rate=1.000000, locked. + Add Rate Pair button for new currencies. Rate History link per currency.
**Steps:** Create pages/admin/fx/FXRateList.tsx with DataTable showing Pair, Rate, Source, Updated By, Updated At, Actions.; Lock Edit action for IDENTITY rows (usd_usd); show Edit for MANUAL and LIVE rows.; Create pages/admin/fx/FXRateForm.tsx with Currency, New Rate, Effective From, Notes fields.; Implement 10% guard-rail: compute deviation=(|newRate-currentRate|/currentRate)*100; if > 10, open ConfirmModal.; Render Effective From as a datetime picker defaulting to now().; Write test: entering 1490 when current rate is 1350 (deviation 10.37%) shows guard-rail modal; entering 1370 (deviation 1.48%) saves without modal.
**Deliverable:** pages/admin/fx/FXRateList.tsx and FXRateForm.tsx with guard-rail confirmation
**Acceptance / logic checks:**
- usd_usd row shows Source=IDENTITY and Edit action is absent or disabled.
- usd_mnt row shows Source=MANUAL with Edit action enabled.
- Entering new rate 1490 when current is 1350 (10.37% deviation) opens ConfirmModal with message 'This rate is 10.37% different from the current value of 1350.0000'.
- Entering new rate 0 is rejected with error 'Enter a positive rate value'.
- Effective From datetime picker defaults to the current time.
**Depends on:** 12.2-T04, 12.2-T05

### 12.2-T21 — Hi-fi design: FX Rate History page  _(30 min)_
**Context:** PRD-07 §7.4: Rate History per currency shows: Rate Value, Effective From, Effective Until (next rate or 'Current'), Updated By, Updated At, Notes. Full history, all entries. Accessed via [Rate History ->] link on FX Rates list. The rate engine uses rate effective at rate_quote_issued time; once a transaction is committed the rate is locked and never recomputed from treasury table.
**Steps:** Create pages/admin/fx/FXRateHistory.tsx accepting currencyCode as route param.; Use DataTable with columns: Rate Value, Effective From, Effective Until, Updated By, Notes.; Show 'Current' in Effective Until for the most recent entry.; Add breadcrumb: FX Rates / USD/{ccy} / History.; Write test: 3 history entries render with correct Effective Until values (first two have dates, last shows 'Current').
**Deliverable:** pages/admin/fx/FXRateHistory.tsx with chronological rate history table
**Acceptance / logic checks:**
- Most recent entry shows 'Current' in Effective Until column.
- Older entries show the datetime of the entry that replaced them.
- Rate values render to 6 decimal places (e.g. 1350.420000).
- Page breadcrumb shows FX Rates / USD/KRW / History when currencyCode=KRW.
- No edit controls present on this page.
**Depends on:** 12.2-T03, 12.2-T20

### 12.2-T22 — Hi-fi design: Transaction List page with filters  _(50 min)_
**Context:** UX-11 §4.5 and PRD-07 §8.2: Filter bar: Partner (multi-select), QR Scheme (multi-select), Direction (multi-select), Status (multi-select), Date Range (default last 24 h, max 90 days), Transaction ID (exact text), Partner Txn Ref (text), Merchant ID (partial), Failure Origin (select), Amount Range Payout from/to. Table columns: Time (KST), Hub Txn Ref, Partner, Payout Amount (KRW), Status badge. Pagination 50 rows/page, sortable by Timestamp/Amount/Partner/Status. Export CSV button (up to 100,000 rows). Date range > 90 days shows error 'Date range cannot exceed 90 days'.
**Steps:** Create pages/admin/transactions/TransactionList.tsx with filter bar and DataTable.; Implement multi-select dropdowns for Partner, Scheme, Direction, Status.; Validate date range: if to - from > 90 days show inline error below the date pickers.; Add Export CSV button that shows a loading spinner while generating.; Add pagination controls: Previous/Next and page size selector (50 default).; Write test: date range 91 days shows error; 90 days passes; Status multi-select with APPROVED and FAILED filters table to 2 matching rows.
**Deliverable:** pages/admin/transactions/TransactionList.tsx with filter bar, validation, pagination, and CSV export button
**Acceptance / logic checks:**
- Date range spanning 91 days shows error 'Date range cannot exceed 90 days' inline.
- Multi-select Partner filter with SendMN selected hides GME Remit rows.
- Table shows Time (KST), Hub Txn Ref (truncated), Partner, Payout Amount, Status columns — 5 columns max.
- Export CSV button triggers download handler; spinner shown during generation.
- Pagination shows page 1 of N with Next/Prev controls and 50-row page size.
**Depends on:** 12.2-T02, 12.2-T03, 12.2-T05

### 12.2-T23 — Hi-fi design: Transaction Detail page — locked rate/pool values panel  _(45 min)_
**Context:** UX-11 §4.6 and PRD-07 §8.5.2: Transaction detail shows identity/metadata, then USD Pool (locked) section with read-only fields: target_payout, payout_usd_cost (Step 1), collection_usd (Step 2), collection_margin_usd (m_a x collection_usd), payout_margin_usd (m_b x collection_usd), send_amount (Step 4), service_charge (Step 5 Settle A ccy), collection_amount (send_amount + service_charge), cost_rate_coll, cost_rate_pay, offer_rate_coll, cross_rate, rate_quote_ttl_seconds, rate_quote_valid_until. Pool identity invariant: collection_usd - collection_margin_usd - payout_margin_usd == payout_usd_cost (tolerance 0.01 USD). For same-currency rules all pool fields show N/A with note 'Same-currency short-circuit applied.' ADMIN_VIEWER role cannot see this section (PRD-07 §12.3).
**Steps:** Create components/transaction/LockedPoolPanel.tsx accepting all pool value props and userRole.; Render all 14 fields as read-only labelled rows.; When isSameCurrency=true, replace all values with 'N/A' and show a blue note.; Hide the entire panel when userRole=ADMIN_VIEWER.; Add a visual pool-identity verification row: compute difference and show green check if within 0.01 USD, red warning otherwise.; Write unit test: collection_usd=34.00, collection_margin_usd=0.34, payout_margin_usd=0.33, payout_usd_cost=33.33 -> identity passes (difference 0.00).
**Deliverable:** components/transaction/LockedPoolPanel.tsx with read-only pool values, identity check, and ADMIN_VIEWER hide
**Acceptance / logic checks:**
- collection_usd USD 34.00, collection_margin_usd USD 0.34, payout_margin_usd USD 0.33, payout_usd_cost USD 33.33 — identity check shows green (difference 0.00 <= 0.01).
- isSameCurrency=true renders all 14 fields as N/A with blue note.
- userRole=ADMIN_VIEWER does not render the LockedPoolPanel at all.
- offer_rate_coll and cross_rate values render with 6 significant figures.
- All values are read-only — no input or edit controls present.
**Depends on:** 12.2-T05

### 12.2-T24 — Hi-fi design: Transaction Detail page — 8-step event trail  _(40 min)_
**Context:** UX-11 §4.6 and PRD-07 §8.5.3: Numbered timeline showing all 8 steps: 1 rate_quote_issued, 2 prefunding_deducted (OVERSEAS only; greyed for LOCAL), 3 transaction_committed, 4 scheme_request_sent, 5 scheme_response_received, 6 transaction_status_updated, 7 settlement_recorded, 8 webhook_delivered. Each step shows: step number, event name, UTC timestamp, delta duration since previous step (ms). Failed steps show failure code and error detail inline. Absent steps (e.g. step 2 for LOCAL) show greyed-out with reason 'N/A: LOCAL partner'.
**Steps:** Create components/transaction/EventTrail.tsx accepting an array of EventStep objects.; Render 8 numbered steps; grey out steps where timestamp is null with a reason note.; For each step with a timestamp, show delta (ms) since the previous timestamped step.; Show failure code and error detail in red for FAILED steps.; Write test: 8-step array for OVERSEAS SendMN transaction renders step 2 with timestamp and USD amount.; Write test: same array with step 2 absent (LOCAL GME Remit) renders step 2 greyed with reason 'N/A: LOCAL partner'.
**Deliverable:** components/transaction/EventTrail.tsx rendering all 8 steps with delta times, failure details, and LOCAL/OVERSEAS conditional
**Acceptance / logic checks:**
- Step 1 (rate_quote_issued) always renders with timestamp and TTL.
- Step 2 (prefunding_deducted) renders greyed-out with reason for LOCAL partner transactions.
- Delta duration between step 4 and step 5 renders as 'Delta: 1200 ms' format.
- A failed step 5 renders failure code SCHEME_REJECTION in red text.
- All 8 steps are present in the rendered list for an OVERSEAS APPROVED transaction.
**Depends on:** 12.2-T05

### 12.2-T25 — Hi-fi design: Transaction Detail page — full assembly  _(50 min)_
**Context:** UX-11 §4.6 and PRD-07 §8.5: Transaction detail combines identity/metadata section, LockedPoolPanel (right column), EventTrail (right column), scheme reference, prefunding ledger entry (OVERSEAS), settlement linkage, and action buttons: Process Refund, View in Audit Log, Copy Txn ID. Process Refund shown only for APPROVED or SCHEME_APPROVED transactions within 90-day window (PRD-07 A-07). Back button top-left. Breadcrumb: Transactions / {hub_txn_ref}.
**Steps:** Create pages/admin/transactions/TransactionDetail.tsx composing LockedPoolPanel and EventTrail in a two-column layout.; Render identity section: Hub Txn Ref, Partner Txn Ref, Partner, Scheme, Direction, Mode, Merchant ID, Merchant Name, Timestamp (UTC and KST), Status.; Show Process Refund button only when status=APPROVED or status=SCHEME_APPROVED and transactionAge <= 90 days.; Add Copy Txn ID button that copies hub_txn_ref to clipboard and shows success toast.; Write test: status=CANCELLED does not show Process Refund button; status=APPROVED within 90 days shows it.; Write test: status=APPROVED older than 90 days does not show Process Refund button.
**Deliverable:** pages/admin/transactions/TransactionDetail.tsx assembling all sub-panels with conditional action buttons
**Acceptance / logic checks:**
- Transaction with status=APPROVED and age 30 days shows Process Refund button.
- Transaction with status=APPROVED and age 95 days does not show Process Refund button.
- Transaction with status=CANCELLED shows no Process Refund button.
- Copy Txn ID button calls navigator.clipboard.writeText with hub_txn_ref and triggers success toast.
- Breadcrumb reads Transactions / HUB-20260601-00123.
**Depends on:** 12.2-T23, 12.2-T24

### 12.2-T26 — Hi-fi design: Prefunding Dashboard and Top-Up form  _(45 min)_
**Context:** UX-11 §4.7 and PRD-07 §9.2, §9.3: Prefunding dashboard shows per-OVERSEAS-partner card: Partner name, balance (USD), threshold, traffic-light badge (green >= 150%, amber 100-150%, red < 100%), last deduction, last top-up. Credit Balance form: Partner select (OVERSEAS only), Top-Up Amount USD (> 0), Reference/Bank Ref (required), Value Date (date, required), Notes (optional). Saving increases displayed balance immediately. Audit-logged. Confirmation before saving (ConfirmModal showing partner name and amount).
**Steps:** Create pages/admin/prefunding/PrefundingDashboard.tsx showing partner cards.; Implement traffic-light logic: balance >= 1.5 * threshold = green OK; 1.0-1.5 = amber LOW; < 1.0 = red CRITICAL.; Create components/prefunding/TopUpForm.tsx with fields and ConfirmModal before submit.; Validate: Amount must be > 0; Bank Ref required; Value Date required.; Restrict Partner select to OVERSEAS partners only.; Write test: balance=15000 threshold=10000 (150%) = green; balance=12000 threshold=10000 (120%) = amber; balance=8000 threshold=10000 (80%) = red.
**Deliverable:** pages/admin/prefunding/PrefundingDashboard.tsx with traffic-light cards and TopUpForm.tsx
**Acceptance / logic checks:**
- SendMN balance USD 45,200 with threshold USD 10,000 (452%) shows green OK.
- T-Bank balance USD 8,400 with threshold USD 10,000 (84%) shows red CRITICAL.
- Top-Up Amount of 0 is rejected with error; 50000 is accepted.
- Bank Ref empty is rejected with required error.
- Saving top-up opens ConfirmModal showing partner name and USD amount before executing.
**Depends on:** 12.2-T04, 12.2-T05

### 12.2-T27 — Hi-fi design: Prefunding Ledger page  _(45 min)_
**Context:** PRD-07 §9.4: Full ledger per partner. Columns: Ledger Entry ID, Timestamp (UTC), Entry Type (TOPUP/DEDUCTION/ADJUSTMENT/REFUND_REVERSAL), Amount (positive TOPUP/REFUND_REVERSAL, negative DEDUCTION in --color-danger), Balance After, Hub Txn Ref (for DEDUCTION), Reference, Recorded By. Manual ADJUSTMENT entries require mandatory reason field and are audit-logged. Finance Analyst role can create ADJUSTMENT (PRD-07 §12.3); OPS_OPERATOR cannot.
**Steps:** Create pages/admin/prefunding/PrefundingLedger.tsx accepting partnerId.; Use DataTable with columns: Timestamp, Type, Amount, Balance After, Reference, Recorded By.; Render DEDUCTION amounts in --color-danger with minus prefix.; Add Create Adjustment button visible only to FINANCE_ANALYST and SUPER_ADMIN roles.; Create Adjustment form modal: amount (positive or negative), reason (required min 20 chars), opens ConfirmModal.; Write test: OPS_OPERATOR does not see Create Adjustment button; FINANCE_ANALYST does.
**Deliverable:** pages/admin/prefunding/PrefundingLedger.tsx with role-gated adjustment form
**Acceptance / logic checks:**
- DEDUCTION row shows amount in red with Unicode minus prefix e.g. '- USD 33.83'.
- TOPUP row shows amount in default colour (positive).
- Create Adjustment button is absent for OPS_OPERATOR role.
- Adjustment reason fewer than 20 chars is rejected with error 'Reason must be at least 20 characters'.
- Balance After column shows running balance e.g. USD 45,200.00 after deduction.
**Depends on:** 12.2-T03, 12.2-T05, 12.2-T26

### 12.2-T28 — Hi-fi design: Refund initiation screen  _(45 min)_
**Context:** UX-11 §4.8 and PRD-07 §10.3: Refund form shows original transaction (payout KRW, collection USD, status). Refund type: Full / Partial radio. If Partial, enter Refund Amount (payout ccy, <= original payout amount). Reason Code select (CUSTOMER_REQUEST / MERCHANT_ERROR / SETTLEMENT_ERROR). Internal Notes text (required, min 20 chars). Consequences panel: '1. Return USD X to SendMN prefunding balance, 2. Submit ZP0021 refund to ZeroPay in next batch, 3. Send payment.refunded webhook to partner'. Review Refund button opens danger-style ConfirmModal with transaction ID in heading. Eligibility: APPROVED or SCHEME_APPROVED, within 90 days, not already refunded. OVERSEAS-only consequences shown for OVERSEAS partners.
**Steps:** Create pages/admin/refunds/RefundForm.tsx accepting transactionId.; Prefill Original Transaction panel (read-only) with payout, collection, status.; Implement Full/Partial radio; show Amount input only for Partial; validate amount <= original payout.; Validate Internal Notes >= 20 chars.; Render Consequences panel with partner-type-conditional prefunding note.; Open danger ConfirmModal on Review Refund with heading 'Refund transaction {hub_txn_ref}?'; submit on confirm.
**Deliverable:** pages/admin/refunds/RefundForm.tsx with full/partial logic, consequences panel, and danger confirm modal
**Acceptance / logic checks:**
- Partial refund amount > original payout amount shows error 'Refund amount cannot exceed the original collection amount'.
- Internal Notes with 19 characters is rejected; 20 characters is accepted.
- OVERSEAS partner shows prefunding reversal consequence; LOCAL partner does not.
- Review Refund opens ConfirmModal with --color-danger primary button and heading 'Refund transaction HUB-20260601-00123?'.
- Full refund pre-fills the amount field with the original collection amount.
**Depends on:** 12.2-T04, 12.2-T05

### 12.2-T29 — Hi-fi design: Settlement batch status and reconciliation screen  _(50 min)_
**Context:** UX-11 §4.9 and PRD-07 §11.2: Settlement screen tabs: Batch Status and Revenue Report. Batch Status: one row per settlement date per scheme (ZeroPay). Columns: Settlement Date, Txn Count, Gross Payout KRW, GME Fee Share KRW, Net Settlement KRW, ZP0011 Status, ZP0061 Status, Reconciliation Status. Reconciliation Status badge: MATCHED (green), DISCREPANCY (red), PENDING (amber). Discrepancy flag action. Batch file timeline shows ZP0011/ZP0012/ZP0021/ZP0022/ZP0061-ZP0066 with timestamps and sent/received/late status icons per PRD-07 §11.2.1.
**Steps:** Create pages/admin/settlement/SettlementBatchList.tsx with DataTable (max 6 cols) and a file timeline row below each date.; Render Reconciliation Status with StatusBadge: MATCHED=success, DISCREPANCY=danger, PENDING=warning.; Render batch timeline showing file codes and sent/received/late icons.; Add Flag Discrepancy action for rows with DISCREPANCY status.; Add period filter (date range) and Partner/Scheme selectors.; Write test: row with Reconciliation=DISCREPANCY shows danger badge and Flag Discrepancy action; MATCHED shows no flag action.
**Deliverable:** pages/admin/settlement/SettlementBatchList.tsx with reconciliation badges, file timeline, and discrepancy action
**Acceptance / logic checks:**
- MATCHED row shows green StatusBadge and no Flag Discrepancy button.
- DISCREPANCY row shows red StatusBadge and visible Flag Discrepancy action button.
- Batch timeline shows ZP0011 through ZP0065 in chronological order with correct labels.
- LATE batch file shows red X icon and LATE label.
- Gross Payout column is right-aligned with KRW currency prefix.
**Depends on:** 12.2-T02, 12.2-T03, 12.2-T05

### 12.2-T30 — Hi-fi design: Revenue Report view with CSV export  _(45 min)_
**Context:** PRD-07 §11.3.2: Revenue report filterable by period (daily/weekly/monthly/custom), partner, scheme, direction, revenue stream. Columns: Period, Partner, Scheme, Txn Count, Gross Payout KRW, Merchant Fee Total KRW, GME Scheme Share KRW (70% of net), Collection Margin USD, Payout Margin USD, Total FX Margin USD, Service Charges (Settle A ccy), Total Revenue KRW equiv. Export CSV must complete within 10 seconds for 30-day period (UX: show spinner, then download). Revenue streams shown as separate line items.
**Steps:** Create pages/admin/settlement/RevenueReport.tsx with period/partner/scheme/direction filter bar and DataTable.; Add Revenue Stream filter: All / Scheme Fee / FX Margin.; Implement Export CSV button with spinner; simulate async download (resolve after 2 s in mock).; Right-align all monetary columns.; Show summary totals row at bottom of table.; Write test: filtering Revenue Stream = Scheme Fee shows only rows with GME Scheme Share column populated.
**Deliverable:** pages/admin/settlement/RevenueReport.tsx with filters, revenue table, totals row, and timed CSV export
**Acceptance / logic checks:**
- Period filter defaults to current month.
- Collection Margin USD and Payout Margin USD are separate columns.
- Export CSV button shows spinner, then triggers download with filename revenue_{period}_{date}.csv.
- Totals row at bottom of table sums Transaction Count and all monetary columns.
- GME Scheme Share column displays 70% of the Merchant Fee Total for each row (verifiable with a mock row).
**Depends on:** 12.2-T03, 12.2-T29

### 12.2-T31 — Hi-fi design: Audit Log search and detail panel  _(50 min)_
**Context:** UX-11 §4.11 and PRD-07 §13: Audit log list: filter by Actor (user), Event Type, Entity Type, Entity ID, Date Range (required, max 12 months), IP Address. Columns: Time (UTC), Actor, Action (event type), Entity (type + ID), Detail link. 100 rows/page. Export CSV up to 500,000 rows; larger exports queued as async jobs with email notification. Detail panel (click View): before state JSON, after state JSON, IP address, user-agent, session ID. No edit/delete controls. ADMIN_VIEWER and FINANCE_ANALYST cannot access Audit Log (PRD-07 §12.3).
**Steps:** Create pages/admin/audit/AuditLog.tsx with filter bar and DataTable.; Require Date Range filter (show error if not set before search).; Add Export CSV button; if estimated row count > 500,000 show a dialog 'This export is large. We will email you when it is ready.'; Implement Detail side-panel showing before/after JSON with syntax highlighting or code block.; Hide entire page and redirect for FINANCE_ANALYST and ADMIN_VIEWER roles.; Write test: Date Range empty on search shows error 'Date range is required'; with range set, search proceeds.
**Deliverable:** pages/admin/audit/AuditLog.tsx with required date range, async export handling, and JSON detail panel
**Acceptance / logic checks:**
- Searching without Date Range shows error 'Date range is required for Audit Log queries'.
- Date range > 12 months shows error 'Audit log queries are limited to 12-month windows'.
- Detail panel shows before-state and after-state as formatted JSON code blocks.
- FINANCE_ANALYST user is redirected away from the Audit Log page.
- Export with estimated > 500,000 rows shows async-email dialog instead of immediate download.
**Depends on:** 12.2-T03, 12.2-T05

### 12.2-T32 — Hi-fi design: Users and Roles management pages  _(40 min)_
**Context:** PRD-07 §12.4 and §12.5: Users & Roles visible only to SUPER_ADMIN. User list: Name, Email, Roles, Last Login, Status (Active/Inactive), Actions (Edit, Deactivate/Reactivate, Force Password Reset). Create user: email + temporary password; forced password change on first login. Roles: SUPER_ADMIN, OPS_OPERATOR, FINANCE_ANALYST, ADMIN_VIEWER — multi-select per user. Session timeout 30 min inactivity. All user management events audit-logged.
**Steps:** Create pages/admin/users/UserList.tsx with DataTable (Name, Email, Roles, Last Login, Status, Actions).; Hide page for non-SUPER_ADMIN roles.; Create pages/admin/users/UserForm.tsx for create/edit with email, role multi-select, status toggle.; Show temporary password field on create; require password change flag to be set.; Deactivate button opens ConfirmModal 'Deactivate user {name}?'.; Write test: SUPER_ADMIN sees UserList; OPS_OPERATOR is redirected to dashboard.
**Deliverable:** pages/admin/users/UserList.tsx and UserForm.tsx with SUPER_ADMIN guard and role assignment
**Acceptance / logic checks:**
- OPS_OPERATOR navigating to /admin/users is redirected to dashboard with access-denied toast.
- Create user form has email, role multi-select (all 4 roles available), and forces password change on next login flag.
- Deactivate button opens danger ConfirmModal with user display name in heading.
- Last Login renders as YYYY-MM-DD HH:mm UTC or 'Never' for new users.
- Inactive users show a grey StatusBadge.
**Depends on:** 12.2-T04, 12.2-T05

### 12.2-T33 — Hi-fi design: Empty, Loading, and Error states for all data-fetching pages  _(35 min)_
**Context:** UX-11 §2.9: Empty state: icon (search or document) + message + optional CTA. Loading state: skeleton shimmer bars matching table row structure; minimum display 200 ms. Error state: warning triangle icon + short error code + Retry button. These three states must be implemented consistently for all pages that fetch remote data (Transaction List, Rule List, Scheme List, Partner List, FX Rates, Audit Log, Settlement, Prefunding Ledger).
**Steps:** Create components/states/EmptyState.tsx accepting icon, message, and optional action button.; Create components/states/LoadingTable.tsx rendering animated shimmer rows with column count prop.; Create components/states/ErrorState.tsx accepting errorCode and onRetry callback.; Wrap each data-fetching page component to show loading/error/empty states from a useAsync hook.; Write tests: LoadingTable renders N shimmer rows when columnCount=5; ErrorState shows Retry button that calls onRetry; EmptyState shows message text.
**Deliverable:** EmptyState, LoadingTable, ErrorState components applied to all data-fetching pages with consistent behaviour
**Acceptance / logic checks:**
- LoadingTable with columnCount=5 renders 5 shimmer columns per row.
- ErrorState Retry button triggers onRetry callback exactly once on click.
- EmptyState renders passed message string and optional action button.
- Transaction List page shows LoadingTable while fetch is pending, then transitions to DataTable or EmptyState.
- Error state shows short error code e.g. 'NETWORK_ERROR' not a stack trace.
**Depends on:** 12.2-T03

### 12.2-T34 — Hi-fi design: Toast notification service  _(35 min)_
**Context:** UX-11 §2.10: Toasts are top-right, 16 px from viewport edge, max 3 simultaneous, stacked. Auto-dismiss: 5 seconds for success/info; persistent for error until dismissed. Variants: success (green left border), error (red left border), warning (amber left border), info (blue left border). Used across all admin pages for save confirmations, validation results, and async completions.
**Steps:** Create services/toast.ts (or context/ToastContext.tsx) with showToast(message, variant) API.; Create components/ToastContainer.tsx rendering up to 3 active toasts in top-right position.; Implement auto-dismiss timer: 5 s for success/info; none for error.; Implement manual dismiss X button on each toast.; Write tests: showing 4 toasts drops the oldest (or queue); error toast does not auto-dismiss after 5 s; success toast is removed from DOM after 5 s.
**Deliverable:** ToastContext + ToastContainer with auto-dismiss, max-3 cap, and variant styling
**Acceptance / logic checks:**
- Success toast disappears automatically after 5 seconds.
- Error toast remains visible after 5 seconds and requires manual dismiss.
- Fourth toast shown when 3 are visible removes the oldest toast.
- Success toast has green left border; error has red left border (--color-danger).
- showToast is callable from any component via context hook without prop drilling.
**Depends on:** 12.2-T01

### 12.2-T35 — Accessibility audit pass: WCAG 2.1 AA compliance across Admin UI components  _(55 min)_
**Context:** UX-11 §8.1 WCAG 2.1 AA requirements: colour contrast all text/bg >= 4.5:1 (normal) or 3:1 (large); keyboard navigation: all interactive elements reachable via Tab, logical focus order, skip-to-content; screen reader: all form controls have associated label, tables have th scope=col/row, status badges have aria-label; focus indicators: visible 2 px brand-colour focus ring not suppressed; errors linked via aria-describedby and aria-invalid=true; modal focus trap with role=dialog aria-modal=true; transitions <= 300 ms with prefers-reduced-motion disable.
**Steps:** Run automated axe-core or jest-axe tests on AdminShell, DataTable, ConfirmModal, StatusBadge, SchemeForm, MappingPage, TransactionList.; Fix any violation: missing label, missing th scope, missing aria-describedby on error fields.; Add skip-to-content link as first focusable element in AdminShell.; Add prefers-reduced-motion media query disabling transitions in tokens/animations.css.; Document any false-positive axe rules with inline justification comments.; Write a summary table of components tested and pass/fail per WCAG criterion.
**Deliverable:** jest-axe test suite passing for all 8 listed components; skip-to-content link; prefers-reduced-motion CSS
**Acceptance / logic checks:**
- jest-axe reports 0 violations for AdminShell, DataTable, ConfirmModal, StatusBadge.
- All form error messages are linked to their input via aria-describedby and the input carries aria-invalid=true.
- Skip-to-content link is the first focusable element on every Admin page.
- All status badges have aria-label equal to the status string.
- CSS includes @media (prefers-reduced-motion: reduce) { * { transition: none; } } or equivalent.
**Depends on:** 12.2-T02, 12.2-T03, 12.2-T04, 12.2-T05

### 12.2-T36 — Responsive behaviour implementation and breakpoint tests  _(55 min)_
**Context:** UX-11 §8.3: Desktop >= 1,440 px full sidebar. Desktop min 1,280-1,439 px sidebar collapses to 56 px icon-only. Tablet landscape 1,024-1,279 px sidebar hidden with hamburger overlay. Tablet portrait 768-1,023 px and mobile < 768 px not supported in Phase 1 for Admin System. At tablet landscape, table columns reduce to 3 most critical; + expand control per row reveals suppressed columns.
**Steps:** Implement CSS media queries in AdminShell for the 3 breakpoints.; Implement DataTable column collapse at 1,024-1,279 px: keep first 3 columns, hide rest, add + expand button per row.; Test each breakpoint in Storybook viewport addon: 1,440 px, 1,280 px, 1,024 px.; Write Playwright or React Testing Library viewport tests for sidebar collapse at 1,280 px.; Add a 'not supported' overlay for viewports < 1,024 px with message 'Please use a desktop browser for the Admin System'.
**Deliverable:** AdminShell and DataTable responsive CSS for 3 breakpoints with viewport tests
**Acceptance / logic checks:**
- At 1,440 px viewport width, sidebar is visible at 240 px with text labels.
- At 1,280 px, sidebar collapses to 56 px icon-only mode.
- At 1,024 px, sidebar is hidden and hamburger button is visible.
- DataTable at 1,024 px shows only first 3 columns with a + expand control per row.
- Viewport < 1,024 px shows the unsupported-browser overlay message.
**Depends on:** 12.2-T05, 12.2-T03

### 12.2-T37 — i18n wiring: externalise all UI string literals across Admin pages  _(45 min)_
**Context:** UX-11 A-05 and §8.2: All UI string literals must be externalised to an i18n key file from the first commit so Korean strings can be added in Phase 2 without code changes. Phase 1 launches English only. Key file: i18n/en.json structured by module (dashboard, schemes, partners, rules, transactions, prefunding, refunds, settlement, fx, users, audit). All error messages, labels, button text, and status badge aria-labels must use translation keys, not hardcoded strings.
**Steps:** Audit all Admin pages and components for hardcoded English strings.; Add missing keys to i18n/en.json grouped by module.; Replace hardcoded strings with t('key') calls using a lightweight i18n hook or react-i18next.; Write a CI check that fails if any component contains a string matching /^[A-Z][a-z]/ that is not a translation key call.; Write test: changing en.json key 'common.save' from 'Save' to 'Save Changes' is reflected in all buttons without code change.
**Deliverable:** All Admin UI strings externalised to i18n/en.json; CI lint rule for hardcoded strings
**Acceptance / logic checks:**
- i18n/en.json contains at least 80 keys covering all visible English labels and error messages.
- Replacing a key value in en.json changes the rendered text in affected components without code changes.
- CI check fails on a component with a hardcoded 'Combined margin must be at least 2.00%' string that is not wrapped in t().
- All StatusBadge aria-labels use translation keys.
- No translation key is duplicated in en.json (enforced by the CI check or a Jest test on the JSON structure).
**Depends on:** 12.2-T01, 12.2-T05

### 12.2-T38 — Unit tests: Mapping Page Section 3 and 4 edge-case validation logic  _(40 min)_
**Context:** PRD-07 §6.4.3 and §6.4.4: Test vectors for margin and service charge validation. Cross-border: m_a=1.00 m_b=0.99 combined=1.99 -> FAIL; m_a=1.00 m_b=1.00 combined=2.00 -> PASS; m_a=0 m_b=2.01 combined=2.01 -> PASS; m_a=101 -> FAIL (max 100). Same-currency: m_a=0 m_b=0 -> PASS; m_a=0.01 m_b=0 -> FAIL (must be 0). Service charge: value=-0.01 -> FAIL; value=0 -> PASS; value=500 KRW -> PASS; tier gap -> WARN (not error); tier overlap -> FAIL.
**Steps:** Create __tests__/MarginSection.test.ts with all vectors listed above as Jest test cases.; Create __tests__/ServiceChargeSection.test.ts with service charge and tier validation vectors.; Assert each validation function returns correct error string or null.; Test the combined margin auto-calculation function: combinedMargin(1.00, 1.00) === 2.00.; Test same-currency lock: calling setMargin(0.01) on a same-currency rule is rejected.
**Deliverable:** __tests__/MarginSection.test.ts and ServiceChargeSection.test.ts covering all edge cases
**Acceptance / logic checks:**
- combinedMargin(1.00, 0.99) triggers error 'Combined margin must be at least 2.00% for cross-border rules'.
- combinedMargin(1.00, 1.00) returns null (no error).
- validateMarginForSameCurrency(0.01, 0.00) returns error 'Margins must be 0 for same-currency rules'.
- validateServiceCharge(-0.01) returns error; validateServiceCharge(0) returns null.
- Tier overlap validation returns an error string containing 'overlap'; tier gap returns a warning string.
**Depends on:** 12.2-T15, 12.2-T16

### 12.2-T39 — Unit tests: Rate engine display logic — Section 2 rate source derivation  _(35 min)_
**Context:** PRD-07 §6.4.2: Rate source derivation rules. IDENTITY when settle_ccy = USD. LIVE when settle_ccy != USD and no override. MANUAL when operator has set override. PARTNER when scheme uses Partner B quote API. For same-currency rules both slots = IDENTITY and override toggle disabled. Manual rate guard-rail: |newRate - liveRate| / liveRate > 0.20 triggers warning (configurable default 20%).
**Steps:** Create __tests__/RateConfigSection.test.ts.; Test deriveRateSource(settleACcy='USD') === 'IDENTITY'.; Test deriveRateSource(settleACcy='KRW', override=null) === 'LIVE'.; Test deriveRateSource(settleACcy='KRW', override=1300) === 'MANUAL'.; Test guardRail(newRate=1620, liveRate=1350, threshold=0.20) returns true (deviation=20.0% exactly, borderline pass); guardRail(newRate=1621, liveRate=1350) returns true (deviation 20.07% > 20% triggers warning).; Test same-currency rule: both slots return IDENTITY and isToggleDisabled=true.
**Deliverable:** __tests__/RateConfigSection.test.ts with derivation and guard-rail vectors
**Acceptance / logic checks:**
- deriveRateSource('USD', null) === 'IDENTITY' passes.
- deriveRateSource('KRW', null) === 'LIVE' passes.
- deriveRateSource('KRW', 1300) === 'MANUAL' passes.
- guardRail(1621, 1350, 0.20) returns true (triggers warning); guardRail(1350, 1350, 0.20) returns false.
- Same-currency input produces IDENTITY for both slots with toggle disabled flag set.
**Depends on:** 12.2-T14

### 12.2-T40 — Unit tests: Transaction Detail pool identity verification  _(35 min)_
**Context:** PRD-07 §8.5.2 and RATE-04 canonical formula: Pool identity invariant: collection_usd - collection_margin_usd - payout_margin_usd == payout_usd_cost (tolerance 0.01 USD). For SendMN example: target_payout=45000 KRW, cost_rate_pay=1350.42, payout_usd_cost=45000/1350.42=33.3228, m_a=1.00% m_b=1.00%, collection_usd=33.3228/(1-0.02)=34.0029, collection_margin_usd=34.0029*0.01=0.3400, payout_margin_usd=34.0029*0.01=0.3400, identity check: 34.0029-0.3400-0.3400=33.3229 vs payout_usd_cost=33.3228 diff=0.0001 -> PASS (< 0.01).
**Steps:** Create __tests__/LockedPoolPanel.test.ts.; Test checkPoolIdentity({collection_usd: 34.0029, collection_margin_usd: 0.3400, payout_margin_usd: 0.3400, payout_usd_cost: 33.3228}) returns {pass: true, diff: 0.0001}.; Test checkPoolIdentity with diff exactly 0.01 -> pass; diff 0.011 -> fail.; Test same-currency case: checkPoolIdentity({isSameCurrency: true}) returns {pass: true, diff: null}.; Test that LockedPoolPanel renders green check icon when checkPoolIdentity returns pass=true.; Test that LockedPoolPanel renders red warning when pass=false.
**Deliverable:** __tests__/LockedPoolPanel.test.ts with pool identity vectors and 0.01 tolerance boundary
**Acceptance / logic checks:**
- diff=0.0001 returns pass=true.
- diff=0.01 returns pass=true (boundary).
- diff=0.011 returns pass=false.
- isSameCurrency=true returns pass=true with diff=null.
- LockedPoolPanel renders a green check icon for the pass=true case.
**Depends on:** 12.2-T23

### 12.2-T41 — Unit tests: StatusBadge and money formatting utilities  _(30 min)_
**Context:** UX-11 §2.7 and §2.8: StatusBadge must render correct bg and text colours per status. Money formatting: {currency_code} {amount}. KRW = 0 decimal places; USD = 2 dp; MNT = 2 dp. Thousands separator comma; decimal separator period. Exchange rates 6 significant figures. Negative amounts prefix Unicode minus U+2212 in --color-danger. Zero = 'USD 0.00' not blank.
**Steps:** Create __tests__/StatusBadge.test.ts with tests for all 9 status values asserting bg and text colour hex values.; Create __tests__/moneyFormat.test.ts testing formatMoney function.; Test formatMoney(45000, 'KRW') === 'KRW 45,000' (no decimal).; Test formatMoney(33.83, 'USD') === 'USD 33.83'.; Test formatMoney(0, 'USD') === 'USD 0.00' (not blank).; Test formatMoney(-33.83, 'USD') returns string starting with Unicode minus U+2212 and styled danger.; Test formatRate(1350.42, 'KRW') === '1,350.420000' (6 sig fig).
**Deliverable:** __tests__/StatusBadge.test.ts and moneyFormat.test.ts with all format edge cases
**Acceptance / logic checks:**
- StatusBadge APPROVED test asserts background #DEF7EC and text #057A55.
- StatusBadge UNCERTAIN test asserts background #FEF9C3 and text #92400E.
- formatMoney(45000, 'KRW') === 'KRW 45,000' (integer, no decimal point).
- formatMoney(0, 'USD') === 'USD 0.00' (not empty string or null).
- formatMoney(-33.83, 'USD') starts with Unicode minus (char code 8722) not ASCII hyphen (45).
**Depends on:** 12.2-T02

### 12.2-T42 — Design handoff: annotated component inventory and token reference page  _(55 min)_
**Context:** UX-11 A-01: No Figma files exist; text wireframes are the sole design authority. A-04: ASCII wireframes show layout intent; pixel dimensions determined by front-end team within design-system constraints. The handoff artifact is a living Storybook page listing all components, their props, usage guidelines, and design token values so any developer can implement Admin screens without this document.
**Steps:** Create a Storybook Introduction story that lists all components in WBS 12.2 with links to their stories.; Add JSDoc prop comments to each major component (AdminShell, DataTable, StatusBadge, ConfirmModal, CurrencySetupSection, RateConfigSection, MarginSection, ServiceChargeSection, MappingPage).; Create a Storybook Design Tokens story rendering all colour swatches, type scales, and spacing tiles.; Ensure Storybook builds without errors (yarn storybook build).; Verify all 8 Admin wireframe pages (Dashboard, Scheme List/Edit, Partner List/Wizard, Mapping Page, Transaction List/Detail, Prefunding, Settlement, FX Rates, Audit Log) have at least one Storybook story.
**Deliverable:** Storybook build with Introduction story, Design Tokens story, and stories for all 8 Admin module pages
**Acceptance / logic checks:**
- yarn storybook build completes with 0 errors.
- Design Tokens story renders all 9 colour swatches with hex values and token names.
- Introduction story contains links to stories for Dashboard, SchemeForm, PartnerWizard, MappingPage, TransactionList, TransactionDetail, PrefundingDashboard, SettlementBatchList, FXRateList, AuditLog.
- Each exported component has at least one JSDoc comment on its primary prop.
- All stories are named following the Module/ComponentName convention.
**Depends on:** 12.2-T06, 12.2-T07, 12.2-T08, 12.2-T10, 12.2-T17, 12.2-T22, 12.2-T25, 12.2-T26, 12.2-T29, 12.2-T31
