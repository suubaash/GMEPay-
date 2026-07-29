> 작업: CISO security audit / 출처: agent

# GMEPay+ CISO Security Audit — 2026-07-28

Lens: what stops this from passing a security review, a regulator, or enterprise customer due diligence. Every finding verified against code/config at the cited path — not against the backlog docs, which describe a far stronger posture than what is wired.

Scope read: `services/*` (21), `libs/*` (13), `apps/{admin-ui,partner-portal-ui}`, `docker-compose.yml`, `deploy/helm/**`, `docker/keycloak/realm-gmepay.json`, `run-fleet.ps1`, `.github/workflows/**`, all Flyway migrations, `Documentation/services_backlog/security-platform.md`, `Octa Solution AML external partner/`.

**Genuinely solid, and worth protecting during remediation:** `SecretHasher` (PBKDF2-HmacSHA256, 210k iterations, per-row 16-byte SecureRandom salt); `InternalAuthFilter` (constant-time compare, fail-closed at startup if enabled with a blank secret, correct filter ordering); `RbacClaimSigner`/`RbacContextFilter` claim-provenance design and `RbacClaimStampingFilter`'s strip-on-unauthenticated behaviour; `HmacSignatureFilter`'s fail-closed key/timestamp checks and pre-rewrite URI signing; `JwtHelper`'s hardcoded `alg` header (immune to algorithm confusion) and constant-time compare; `JwtTokenService`'s clamp-down-never-up TTL; the `audit_log` SHA-256 hash chain; 9Pay IPN signature verification, which fails closed; partner-credential rotation with a 4-eyes proposal and overlap window; git history is clean of private keys (368 commits checked). The gaps below are overwhelmingly **enablement and coverage**, not primitive design — which is the good news, because most are config-shaped.

---

## 1. BLOCKER-for-security-review — Unauthenticated `password=demo` mints an unsigned ADMIN token, reachable from the internet

`services/ops-partner-bff/src/main/java/com/gme/pay/bff/web/AuthController.java:61-108`. `POST /v1/auth/login` accepts **any** username with the literal password `demo` and returns `role:ADMIN`:

```java
static final String DEMO_PASSWORD = "demo";
...
if (username == null || username.isBlank()
        || password == null || !DEMO_PASSWORD.equals(password)) {
    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid credentials");
}
return issue(username, "ADMIN");
```

The returned token is not a JWT — it is `"mock.eyJ" + base64(payload)` with **no signature segment at all**, so the `role:ADMIN` claim can be hand-forged without any secret. `POST /v1/auth/refresh` is worse: any non-blank string returns a fresh 1-hour ADMIN token, no password needed.

There is **no `@Profile`, no `@ConditionalOnProperty`, no property guard** — only a `@Deprecated(forRemoval=true)` annotation and a Javadoc removal plan (ticket "1C.4-cleanup") that never landed. `NEXT_PUBLIC_ALLOW_DEV_LOGIN=false` (correctly pinned off in every Helm overlay) hides only the *UI form*; the endpoint stays live everywhere.

It is internet-reachable because `apps/admin-ui/next.config.mjs:31-34` rewrites `/api/:path*` → the BFF, and **there is no `middleware.ts` in either app** — the Next rewrite is an unauthenticated server-side proxy; `AuthGate.jsx` is client-side React only. `deploy/helm/gmepay/templates/ingress.yaml` + `values.yaml:95-104` publish `admin.gmepay.local` → admin-ui:3000. Net: `POST https://admin.<host>/api/v1/auth/login {"username":"x","password":"demo"}` from anywhere.

**Done when:** `AuthController`, `LoginRequest/Response`, `RefreshRequest` and their tests are deleted from the repo (not gated — deleted); a request to `/v1/auth/login` returns 404 in a built image; the admin-ui/partner-ui code paths that call it are removed; and an automated test asserts no endpoint anywhere issues a token on a static password.

---

## 2. BLOCKER-for-security-review — 20 of 21 services ship no authentication layer; the BFF's entire 36-controller admin surface is open

`api-gateway/build.gradle:31` (`spring-boot-starter-oauth2-resource-server`) is the **only** security dependency in the monorepo. No `spring-boot-starter-security`, no `SecurityFilterChain`, no `@EnableWebSecurity` in any other `services/*/build.gradle` — verified across all 21 services and 13 libs. There is exactly one Spring Security config file in the codebase: `services/api-gateway/src/main/java/com/gme/pay/gateway/config/SecurityConfig.java`.

Consequences, concretely:

- **`ops-partner-bff` has zero authentication.** Its 36 controllers cover partner lifecycle, KYB, bank accounts, commercial terms, credential rotation/reveal, prefunding, journals, audit read, change requests, scheme commission share, platform settings. Its only filter is `IssuedCredentialBundleLogMaskingFilter`, which by its own Javadoc (lines 34-41) *"does NOT interfere with the response stream — the attribute is the contract"*, and **nothing in the repo reads that attribute** (grep: only the filter and its tests). So the log-masking control for issued API-key/HMAC bundles is a no-op.
- **`auth-identity`** — which owns RBAC, mints platform JWTs, and issues partner API keys — has no Spring Security either. Its `/internal/**`, `/v1/rbac/**`, `/v1/approvals/**` are protected only by `InternalAuthFilter`, and that filter defaults **off**: `services/auth-identity/src/main/resources/application.yml:62` → `enabled: ${GMEPAY_INTERNAL_AUTH_ENABLED:false}`. It is turned on in `docker-compose.yml:465` and `deploy/helm/gmepay/values.yaml:175` only — `run-fleet.ps1` (the local fleet the tunnel actually serves) sets **no security env at all**, so in that mode RBAC management, approval decisions and API-key issuance are wide open.
- **`config-registry`** holds every partner identity/bank/UBO field and KYB document, exposes 28 controllers, and has no security config and no authorization annotations. `PartnerDocumentController` (`:60,94,104`) accepts document upload and serves `GET .../documents/{docId}/content` with no check whatsoever.
- **`Swagger UI + /v3/api-docs` are enabled unauthenticated on every service** (`springdoc.swagger-ui.path=/swagger-ui.html` in 17 modules), publishing the full internal API surface to anyone who can reach a port.

**Done when:** every service that exposes an HTTP port either (a) carries a `SecurityFilterChain` that denies by default and authenticates its callers, or (b) is provably unreachable from outside its trust boundary by an enforced NetworkPolicy/mesh policy — with an integration test per service asserting an unauthenticated request to its most privileged endpoint returns 401/403. Swagger disabled or authenticated in non-dev profiles.

---

## 3. BLOCKER-for-security-review — Privileged authorization is decided by a client-supplied header; the RBAC library is enabled in exactly one service

`services/ops-partner-bff/src/main/java/com/gme/pay/bff/web/OpsRbacGuard.java` gates the kill-switch and money/state-affecting operator actions on the raw `X-Gme-Permissions` request header. It never calls `RbacClaimSigner.verify` and never consults `RbacContextHolder` (which is unpopulated — `RbacContextFilter` is not installed in the BFF). `OpsActionController.java:68-72` reads it straight off the wire:

```java
@PostMapping("/ops/pause")
public ResponseEntity<...> pause(...,
        @RequestHeader(value = RbacHeaders.PERMISSIONS, required = false) String permissions) {
    rbac.requireOps(permissions);
```

The only component that strips or signs `X-Gme-*` is `RbacClaimStampingFilter` in the api-gateway — and **the gateway has no route to the BFF** (`GatewayRoutingConfig.java:98-186` routes only `/v1/{payments,rates,quotes,prefunding,route,merchants,partners,transactions,revenue,journals,settlements,reports,qr}`), and the BFF is not in the ingress. All BFF traffic arrives via the Next.js `/api/*` rewrite, which forwards client headers verbatim. So `curl -X POST https://admin.<host>/api/v1/admin/ops/pause -H 'X-Gme-Permissions: ops:operate'` authorizes a global platform pause. Same for `resume`, `maintenance`, `suspend`, `unsuspend`, `transactions/{ref}/resolve`, `webhooks/{id}/replay`, `settlements/recon/rerun`. **28 of the 36 BFF controllers use no guard at all.**

The admin-ui hard-codes the header itself — `apps/admin-ui/src/api/opsApi.js:20-22`: *"DEV NOTE: hard-coding the permission header here is a dev/tunnel convenience only… Do not ship this hard-coded header to prod."*

Compounding it, the in-process RBAC engine is dormant almost everywhere. `libs/lib-errors/src/main/java/com/gme/pay/rbac/RbacProperties.java:13` → `private boolean enabled = false;`, and `RbacAutoConfiguration` is `@ConditionalOnProperty(havingValue="true")`. `GMEPAY_RBAC_ENABLED: "true"` appears **only** for auth-identity (`docker-compose.yml:458`, `values.yaml:174`). Coverage:

| | count |
|---|---|
| Services with any `@RequiresPermission`/`@PreAuthorize` | **3** — auth-identity (3 sites), ops-partner-bff (3), api-gateway (0 real) |
| Services with **zero** authorization annotations on controllers | **17** — config-registry (28 controllers!), payment-executor, prefunding, transaction-mgmt, revenue-ledger, settlement-reconciliation, rate-fx, reporting-compliance, qr-service, merchant-qr-data, notification-webhook, kyb-adapter, smart-router, 4 scheme adapters |
| Services where RBAC autoconfig is actually active | **1** — auth-identity |

The worst case is `ops-partner-bff/web/RbacAdminController.java:51,57`: it *carries* `@RequiresPermission("rbac.manage")` on `POST /v1/admin/rbac/roles` and `PUT /v1/admin/rbac/roles/{role}/permissions`, so it looks covered — but with the autoconfig condition unmet the interceptor is never registered, and **arbitrary permission self-grant is unauthorized**. And because the gateway's stamping filter runs at order 8, *after* the unconditional `HmacSignatureFilter` at order 4 which 401s anything lacking `X-API-Key`, `RbacClaimStampingFilter` can never fire for real traffic at all — the signed-claim pipeline is non-functional end to end.

**Done when:** `gmepay.rbac.enabled=true` with a non-blank `verify.secret` on every service that makes an authorization decision; `OpsRbacGuard` reads `RbacContextHolder` (signature-verified) instead of a raw header and the raw-header path is deleted; all 36 BFF controllers carry an explicit permission; the admin-ui no longer sends `X-Gme-Permissions`; and a test asserts a forged `X-Gme-Permissions` with no valid `X-Gme-Sig` yields 403.

---

## 4. BLOCKER-for-security-review — The committed secrets *are* the live defaults; no vault, no rotation, no key versioning

**The JWT signing key.** `services/auth-identity/src/main/resources/application.yml:40`:

```yaml
signing-secret: ${GME_AUTH_JWT_SIGNING_SECRET:changeme-at-least-32-chars-long!!}
```

Same literal repeated as the `@Value` default in `auth/config/AuthConfig.java:44`. `GME_AUTH_JWT_SIGNING_SECRET` is set in **zero** files — not `docker-compose.yml`, not `values.yaml`, not `values-aws.yaml`/`values-azure.yaml`/`values-onprem.yaml` (which *do* template `GMEPAY_RBAC_SECRET`, `GMEPAY_INTERNAL_AUTH_SECRET`, `GMEPAY_WEBHOOK_SIGNING_SECRET`). The default is deliberately sized to pass the 32-char HS256 check, so it **fails open**: a deployment that forgets the env var boots cleanly and signs valid platform capability tokens with a key published in the repo.

**Other committed working secrets:**

| Value | Location |
|---|---|
| `dev-internal-svc-secret-not-for-prod` | `docker-compose.yml:359,466,826,856` (as the `:-` default) |
| `dev-rbac-edge-secret-not-for-prod` | `docker-compose.yml:459,853` |
| `dev-webhook-secret-not-for-prod` | `docker-compose.yml:714` |
| `KEYCLOAK_ADMIN: admin` / `KEYCLOAK_ADMIN_PASSWORD: admin` | `docker-compose.yml:311-312` (pure literals, no `${}`) |
| `admin-ui-dev-secret`, `partner-portal-ui-dev-secret` | `docker/keycloak/realm-gmepay.json:40,76` — **confidential** clients (`publicClient:false`) with `directAccessGrantsEnabled:true` (ROPC) and `"sslRequired":"none"` |
| users `admin`/`demo`, `partner-demo`/`demo` | `docker/keycloak/realm-gmepay.json:100-135` |
| `changeme-internal-token` | `services/qr-service/src/main/resources/application.yml:18` + `RestPrefundingReservationClient.java:44` — and **nothing verifies `X-Internal-Token`**, so it authenticates nothing |
| `POSTGRES_PASSWORD: gmepay` ×15, `MINIO_ROOT_PASSWORD: gmepay-minio` | `docker-compose.yml`; also baked into *library* code at `libs/lib-vault/.../VaultProperties.java:57,60` |
| **`server=10.25.10.70;database=DB_AML_SCREENING;uid=uatstaging;pwd=gM3R3Mli!K`** | `Octa Solution AML external partner/appsettings.json` — **tracked in git**, a real-looking internal SQL Server credential |

**No secrets manager exists.** `libs/lib-vault` is *not* a vault — its entire port is `store(...)`/`retrieve(...)` for document blobs (`VaultClient.java`), with no `getSecret`, no encrypt/decrypt, no key wrap. Its **default implementation is `InMemoryVaultClient`** backed by a `LinkedHashMap` (`:47`); MinIO activates only when `gmepay.vault.endpoint` is set, which `docker-compose.yml` never sets for config-registry. It has exactly one consumer in the whole codebase (`config-registry/document/PartnerDocumentService.java`). Its Javadoc promises *"crypto-shredding the per-partner encryption key (Vault-managed keys)"* — there is no key and no shred code.

**No rotation, no `kid`.** Grep for `kid`/`keyVersion`/`key_version`/`activeKeyId` across all `.java`/`.yml`/`.sql`: zero crypto-key hits. The JWT key, RBAC stamp key, internal-auth secret, webhook signing secret, 9Pay RSA-2048 and SendMN RSA-4096 keys are each a single static value with no dual-key overlap — rotating any of them is a fleet-wide downtime cutover. Scheme private keys also have **no delivery path**: they are env-var-only PEM strings (`scheme-adapter-ninepay/application.yml:55`, `scheme-adapter-sendmn/application.yml:42-44`) and are absent from `values.yaml`'s `secrets.data` entirely.

Root `.gitignore` (484 bytes, full content reviewed) has **no rule for `*.pem`, `*.key`, `*.p12`, `*.jks`, `*.env`, `secret*`, `credential*`** — an untracked, un-ignored Java KeyStore is already sitting at `docker/certs/gme-truststore`, one `git add -A` from being committed.

**Done when:** the Octa credential is rotated at the DB and the file removed from the working tree (history is clean of *keys*, but this credential is committed — rotate first, then decide on history rewrite); every secret's `${VAR:default}` default is blank so the service fails closed; a real secrets backend (External Secrets Operator / CSI / Key Vault / Secrets Manager) is wired and `secrets.create=false` in prod; `lib-vault` is renamed to reflect that it is a document store; scheme RSA keys have a chart-wired delivery path; a `kid`-based dual-key window exists for the JWT and RBAC-stamp keys; root `.gitignore` covers key/env patterns; and gitleaks runs in CI (see §13).

---

## 5. BLOCKER-for-security-review — The partner API authenticates against hardcoded stub keys published in source, with every edge control fail-open or off

`services/api-gateway/src/main/resources/application.yml:94` — `partner-credentials.source: stub`, and `ConfigPartnerCredentialProperties.java:40` confirms `stub` is the code default. That resolves to `services/api-gateway/src/main/java/com/gme/pay/gateway/partner/StubPartnerCredentialService.java`:

```java
"pk_test_abc", new PartnerCredentials("partner_test_001", "pk_test_abc", "sk_test_xyz",
        List.of(), PartnerCredentials.PartnerType.OVERSEAS, 300, STUB_MTLS_FINGERPRINT),
"pk_test_no_mtls", new PartnerCredentials("partner_test_002", "pk_test_no_mtls", "sk_test_no_mtls", ...)
```

`List.of()` is the IP allowlist — i.e. **no IP restriction**. `SecurityConfig.java:104-112` sets the whole `/v1/**` surface (every routed API: payments, transactions, settlements, reports, prefunding, merchants, partners, rates, QR) to `permitAll` at the Spring Security layer, delegating authentication entirely to `HmacSignatureFilter`. That delegation is architecturally correct and the filter does fail closed — but with the stub as the credential source, anyone holding the repo can HMAC-sign requests as `partner_test_001` against a default-configured gateway. `sk_test_xyz` is also live in `gmepay-test-platform/src/engine/testkit.ts:16`.

Every compensating control at the edge is off or fail-open by default in the same file:

```yaml
gateway:
  replay-protection: { fail-open: true, store: memory }   # :71-75
  trust-proxy: false
  rate-limit: { enabled: false, fail-open: true, store: memory }   # :80-86
security:
  gateway:
    mtls: { enabled: false, client-cert-header: X-Client-Cert-Fingerprint }   # :110-113
    allowlist: { trust_header_only_in_dev: true, fail-open: true }            # :116-119
```

mTLS, when enabled, trusts a plain `X-Client-Cert-Fingerprint` header — spoofable unless the Nginx terminator is provably the only ingress path, which no config in the repo establishes. `OIDC_ISSUER_URI` is also set nowhere in compose or Helm, so the gateway falls back to `http://localhost:8090/realms/gmepay` — a JWKS URI that resolves to nothing inside the pod, meaning every JWT-bearing request would 401.

**Done when:** `partner-credentials.source` defaults to `config`/DB with no stub bean on the classpath in non-test builds (or the stub is `@Profile("test")`); `pk_test_abc`/`sk_test_xyz` appear nowhere outside test fixtures; replay protection and rate limiting are enabled and fail **closed** with a shared Redis store; the IP allowlist fails closed; `trust-proxy`/`trust_header_only_in_dev` are correct for the real ingress topology; and `OIDC_ISSUER_URI` is set in every deployment target.

---

## 6. BLOCKER-for-security-review — Unauthenticated money-moving internal endpoints, and a sandbox payment runner reachable from the internet

**Money movement with no caller authentication.** `services/prefunding/src/main/java/com/gme/pay/prefunding/api/internal/PrefundingInternalController.java:40` exposes `POST /internal/v1/prefunding/{partnerId}/deduct` and `/reverse` — real balance movement — with no security annotation. The protection is a Javadoc assertion (`:36-37`): *"This path is internal-network only… network-level policy is the trust boundary."* **That trust boundary does not exist**: `deploy/helm/gmepay/templates/` contains only `configmap`, `deployments`, `ingress`, `secret`, `_deployment.tpl`, `_helpers.tpl` — there is no NetworkPolicy anywhere in the repo, and `docker-compose.yml` puts all 44 services on one flat bridge network with 43 host port bindings on all interfaces.

Same shape, also unguarded: `/internal/scheme/zeropay` (+`/registration-status`), `/internal/scheme/nepal`, `/internal/scheme/sendmn` (+`/fx-rate/latest`) — five controllers across four adapter services. `InternalAuthProperties`' default patterns (`/v1/rbac/**`, `/v1/approvals/**`, `/internal/**`) would cover them, but none of those services sets `gmepay.internal-auth.enabled=true`.

**The sandbox E2E runner is internet-exposed and executes real payments.** `services/payment-executor/src/main/java/com/gme/pay/payment/web/SandboxE2eController.java:37,64` maps `POST /v1/sandbox/e2e/run` with no security annotation and — critically — **no `@Profile` or `@ConditionalOnProperty`**, so it is registered in every build. `E2eRunner` drives the real `/v1/pay/classify` + `/v1/pay` endpoints over loopback. It is published by `apps/admin-ui/next.config.mjs:40-43`:

```js
{ source: '/e2e/:path*',        destination: `${paymentExecutorUrl}/v1/sandbox/e2e/:path*` },
{ source: '/sim-nepal-qr/:path*', destination: `${simNepalQrUrl}/:path*` },
```

With no Next middleware (§1), `POST https://admin.<host>/e2e/run` executes a payment flow with zero credentials, bypassing the gateway's HMAC, replay, idempotency, rate-limit and IP-allowlist filters entirely. The second rewrite publishes the Nepal simulator, whose `/sim/nepal-qr/qrscan-thirdparty/pay/` and control endpoints are equally open. Sibling simulators expose trust-anchor and behaviour control — `sim-ninepay` `POST /sim/partner-key` and `POST /sim/scenario`, `sim-sendmn` `POST /sim/fx-rate` and `/sim/fx-rate/push` — so anything that can reach a sim can rewrite its keys or push an FX rate.

**On the tunnel:** there is **no `cloudflared` config, no tunnel definition, and no Cloudflare Access policy anywhere in the repo** — grep for `cloudflare|cloudflared|tunnel` returns only four source comments in admin-ui explaining why these same-origin rewrites exist *because* the console is reached over a tunnel. So the sole control standing between everything above and the public internet is unversioned local operator config that no reviewer can inspect and no CI can regress-test.

**Done when:** `SandboxE2eController` and the `/e2e/*` + `/sim-nepal-qr/*` rewrites are removed from any non-dev build (gate on a build-time flag, and assert their absence in the production image); all `/internal/**` endpoints require `X-Gme-Internal` (`gmepay.internal-auth.enabled=true` fleet-wide) *and* are covered by an enforced NetworkPolicy/mesh policy; simulators are not built or deployed into any environment reachable from the tunnel; and the tunnel + Access policy are committed as code with the Access gate proven to cover every published hostname.

---

## 7. BLOCKER-for-security-review — PII is plaintext at rest and plaintext in transit, everywhere

**At rest: zero column-level encryption exists.** `grep -rniE "pgcrypto|PGP_SYM|ENCRYPT|encrypted"` across all 60 Flyway migration files returns **0 hits**. The only `AttributeConverter` in the codebase is `ChangeRequestEntity.FieldSetConverter` (a string-list joiner, not crypto). The only `Cipher.getInstance` is `sendmn/crypto/RsaAesEnvelopeCodec.java` — outbound *transport* encryption, and it is off by default (`mode: plain`, `application.yml:39`). `MinioVaultClient.java:103` calls `putObject` with **no SSE headers** (line 37 notes they *could* be added).

What is plaintext, by table:

| Service | Table | Plaintext sensitive columns |
|---|---|---|
| config-registry | `partner_bank_account` | **`iban_or_account_number`**, `account_holder_name`, `bank_name`, `bic_swift` |
| config-registry | `partner_kyb` | **`ubo_set_jsonb`** — `[{name, ownershipPct, isPep, country}]` natural persons + PEP flags |
| config-registry | `partners` | `legal_name_local/romanized`, `tax_id`, registered + operating addresses, `lei`, `legal_form` |
| config-registry | `partner_contact` | `name`, `email`, `phone_e164`, `is_authorized_signatory` |
| config-registry | `audit_log` | `before_jsonb`/`after_jsonb` — **full-row snapshots of everything above**, never purged |
| scheme-adapter-ninepay | `np_payouts` | **`account_no`**, `account_name`, `bank_no`, `account_type` (1 = bank card), `sender_uid` |
| scheme-adapter-ninepay | `np_ipn_events` | `raw_payload TEXT NOT NULL` — verbatim callback bodies incl. beneficiary account + name |
| transaction-mgmt | `transactions` | `user_ref` (end-customer/wallet id), indexed |
| qr-service | `qr_parse_cache` | `raw_payload` (full EMVCo QR), `merchant_name`, `merchant_city`, `mcc` |

This directly violates the project's own spec — `Documentation/services_backlog/security-platform.md:377` (DAT-03 A6) requires `merchant.account_no` be AES-256 encrypted at the application layer, and `:400-407` requires AES-256-GCM + HSM for the vault.

**In transit: no TLS anywhere in application config.** `grep -rniE "server\.ssl|key-store|keystore|truststore|enabled-protocols"` across `services/ libs/ apps/ deploy/ docker/` → **0 hits**. There is no `application-prod.*` or `application-tls.*` profile in the repo. Every inter-service base URL is `http://` (~30 across `ops-partner-bff/application.properties:17-24`, `payment-executor/application.properties:21-33`, and every adapter). `docker-compose.yml` has no TLS terminator. And the Helm default is **`ingress.tls: []`** (`values.yaml:93-94`, with `annotations: {}`), while `values-onprem.yaml:48-52` has no TLS block at all — so on the self-host path, bearer tokens and PII transit in cleartext. `values.yaml:59-60` even points the OIDC issuer at `http://keycloak:8080/realms/gmepay`, fetching JWKS over plain HTTP.

Two supporting leaks: `payment-executor/domain/GmeremitPaymentService.java:124` logs the entire QR payload (`log.warn("Payment declined: no merchant registered for qr={}", qrPayload)`) on every unknown-merchant decline; and KYB document bytes plus the whole config-registry PII store **default to in-memory** (`InMemoryVaultClient`; `jdbc:h2:mem:configreg`), so a misconfigured deploy silently runs PII on volatile unencrypted storage.

*(Credit: no Lombok in any main source, so no accidental `@ToString` PII dumps; secret-bearing DTOs consistently redact `toString()`; no `show-sql`, no wire-level DEBUG logging; the `gmepay.trace` tracer is off by default and captures only `caller/callee/method/path/status/latency` — never bodies.)*

**Done when:** account numbers, tax IDs, UBO sets and contact PII are encrypted at the application layer with a KMS-held key (or tokenized), with the DAT-03 A6 columns specifically covered; object-store SSE is enabled; TLS 1.2+ terminates at the ingress in **every** overlay including on-prem, with HSTS; internal service-to-service traffic is TLS or mTLS per SEC-09 §2.4; the QR-payload log statement is redacted; and no service can start on an in-memory datastore or vault outside a test profile.

---

## 8. BLOCKER-for-security-review — Cross-partner IDOR: tenant identity is a localStorage-controlled header on an unauthenticated BFF

`apps/partner-portal-ui/src/api/client.js:47-50` sends the tenant identity as a client-controlled header:

```js
if (token) headers['Authorization'] = `Bearer ${token}`;
const partnerId = getPartnerId() || ENV_PARTNER_ID;
if (partnerId) headers['X-Partner-Id'] = partnerId;
```

The file states the risk itself at `:18-20`: *"Production deploys MUST NOT trust X-Partner-Id."* Nothing enforces that. Worse, the partner id does not come from a token at all — `apps/partner-portal-ui/src/api/auth.js:21-23`: *"The BFF does NOT return a partnerId — the Portal UI treats the form's `partnerId` field as the partner identity."* Every portal route is path-parameterised by it (`/v1/portal/{partnerId}/{overview,balance,transactions,webhooks,profile,api-keys,statement}`, `client.js:5-12`) against a BFF with no authentication (§2). Editing one `localStorage` key returns another partner's balances, transactions, statements and API-key metadata.

Session handling compounds it: access, id **and refresh** tokens all live in `localStorage` (`apps/admin-ui/src/api/auth.js:26-31,130-139`; partner portal `:36-40`), with no rotation, no revocation, and no `httpOnly` cookie anywhere in the repo (`document.cookie`: zero hits). Neither app sets any security header — no CSP, HSTS, `X-Frame-Options`, or `X-Content-Type-Options` (grep across both `next.config.mjs` and the gateway: zero hits). Any XSS in either SPA exfiltrates a persistent credential set.

**Done when:** the partner identity is derived server-side from the verified token subject and `X-Partner-Id` is ignored (and stripped at the edge); every `/v1/portal/{partnerId}/**` handler asserts the path id equals the authenticated tenant, with a test proving a cross-tenant request returns 403; tokens move to `httpOnly`+`Secure`+`SameSite` cookies issued by the BFF (the ADR-011 "phase-D" item); refresh-token rotation and server-side revocation exist; and both apps ship CSP + HSTS + `X-Frame-Options`.

---

## 9. BLOCKER-for-security-review — The audit trail is not regulator-grade: spoofable actor, no authN auditing, no WORM, 2 of 21 services instrumented

**The actor is unauthenticated client input.** Every config-registry write endpoint takes the operator identity as an optional request header — 28 occurrences of `@RequestHeader(name = "X-Actor", required = false)` across `registry/web/*.java` — and 24 service classes fall back to `private static final String DEFAULT_ACTOR = "system"` when it is absent. `grep -rn "X-Actor" services/api-gateway/src/main` returns **nothing**: the gateway neither stamps, validates, nor strips it (it carefully handles `X-Gme-*`, but `X-Actor` is outside that set). So any caller can attribute a fee, limit, credential or KYB change to an arbitrary operator name — or omit the header and have it logged as `"system"`, which is *also* the literal the 4-eyes DB constraint carves out (§10). `actorIp` is passed as literal `null` at every call site except `OpsControlService`, which takes it from the equally client-supplied `X-Forwarded-For`.

**Coverage is 2 of 21 services.** Only `config-registry` (28 call sites) and `api-gateway` depend on `lib-audit`. api-gateway has no datasource, so it falls through to `LogAuditPublisher` — edge security rejections (`GATEWAY_IP_REJECTED`, spoofed-partner) are `log.info` lines in no table. High-risk operations, audited YES/NO:

| Operation | Audited? |
|---|---|
| Login / token issue / API-key issue | **NO** — `auth-identity` has no `lib-audit`, no audit table, and `JwtTokenService`/`AuthVerificationService`/`ApiKeyIssuanceService` emit **not even a log line** |
| RBAC role/permission grant | **NO** — `RbacAdminService.createRole/grantPermission/assignRole/createConstraint` (`:122,155,188,229`) emit nothing. Granting `*` leaves no trace |
| Prefunding balance movement | **NO** — `ledger_entry` (V002) has no actor, no reason, no IP column |
| Settlement generation | **NO** — `settlement_batches` has no actor |
| FX rate change | **NO** — `rate_snapshots` permits `source='MANUAL'` with no actor column |
| Refunds | **NO** — and no refund endpoint exists |
| Scheme config / merchant fee change | YES — but with the spoofable `X-Actor` above |

**No tamper protection beyond the chain, and the chain has holes.** `HashChain.canonicalise()` hashes only `eventType | actorId | recordedAt | before | after` — `aggregateType`, `aggregateId` and `actorIp` are **not in the digest**, so those columns can be rewritten without breaking verification. Repo-wide grep for `REVOKE`/`GRANT`/`CREATE ROLE`/`TRIGGER` in `**/*.sql`: **zero hits**, and compose runs config-registry as the schema owner `gmepay` — the "append-only" property is application discipline only, as `V006__audit_log.sql:24-26` admits. `DbAuditPublisher` inserts **32 zero bytes** as the row hash when one is missing (`:210`), producing an unverifiable row that still passes the length CHECK. Audit tiers 2 and 3 (Kafka fan-out, object-locked cold archive) are not deployed — config-registry has no `SPRING_KAFKA_BOOTSTRAP_SERVERS` in `docker-compose.yml`, so **no off-box copy of the audit log exists**. And 25 of 26 call sites use `auditLogProvider.getIfAvailable(); if (auditLog != null)` — if the bean fails to wire, every business write succeeds with zero audit rows and zero errors. Operator-action audit is also `stub` by default (`ops-partner-bff/application.properties`: `gmepay.operator-action-audit.client=${...:stub}`), i.e. discarded in-memory.

**Done when:** the audited actor is derived from the verified token/PDP and `X-Actor` is stripped at the edge and removed from every controller; authentication events (success, failure, lockout) and every RBAC grant/revoke write audit rows; prefunding, settlement and rate-fx mutations carry actor + reason; `UPDATE`/`DELETE` on `audit_log` is revoked at the DB role level and the app runs as a non-owner; the hash digest covers `aggregateType`/`aggregateId`/`actorIp`; a missing row hash is a hard failure not a zero-fill; the Kafka + object-locked archive tiers are deployed; and `operator-action-audit` defaults to `rest`.

---

## 10. MAJOR — Segregation of duties is implemented twice and enforced on nothing financially material

Two unconnected implementations exist, and both are real code with correct maker-checker logic — `auth-identity`'s `ApprovalWorkflowService.guardMakerChecker()` (`:193-205`) blocks requester-as-approver and repeat-approver with a `UNIQUE (request_id, approver_id)` DB backstop plus an optimistic-lock version column (the strongest control in the codebase), and `config-registry`'s `V005__change_request.sql:38-45` enforces `proposed_by IS DISTINCT FROM approved_by` as a DB CHECK.

They are nonetheless bypassable and largely unreachable:

- **No producer.** `grep -rn "RequestApprovalCommand|ApprovalWorkflowService|/v1/approvals"` outside auth-identity finds only the BFF's **read-side** queue client. Nothing in transaction-mgmt, payment-executor, prefunding, settlement-reconciliation or config-registry ever calls `POST /v1/approvals`. Only `REFUND` policies are seeded (`V005:92-95`), and no refund endpoint exists.
- **The RBAC loop that would gate endpoints on prior approval is dead.** `ApprovalConstraintEvaluator` reads `ctx.approvalGranted()`, fed by `X-Gme-Approval-Granted` — which `RbacClaimStampingFilter` **unconditionally removes** in both `stamp()` (`:139`) and `strip()` (`:162`), with the comment *"the P6 cross-service wiring is pending"*. **No endpoint anywhere is approval-gated.**
- **Change-request coverage is 2 aggregate types.** Only `PartnerChangeRequestApplier` (`partner`) and `PartnerLifecycleChangeRequestApplier` (`partner_lifecycle`) exist. Direct single-actor write endpoints cover everything else: merchant fee schedules, scheme + partner commission shares, fee schedules, FX config, **AML velocity limits**, corridors, rules, prefunding config **and balances**, settlement config, regulatory config, KYB, bank accounts, documents, **API credential rotation**, mTLS certs, IP allowlist, platform settings, and the ops kill switch.
- **Two documented bypasses.** `'system'` is a blanket carve-out in the 4-eyes CHECK, and since `X-Actor` is unauthenticated and defaults to `"system"` (§9), a header-less propose + header-less approve self-approves legally. And `isCfo(perms)` returns true for the literal permission `"*"` or `approval.cfo_override` (`:222-224`, seeded on `HUB_ADMIN`), jumping `currentStep` straight to `requiredSteps` — collapsing a 2-step >$5k approval into **one signature**. Since RBAC grants are unaudited (§9), granting oneself `*` leaves no trace.

**Done when:** the operations a regulator expects 4-eyes on (fee/rate changes, prefunding credit, credential rotation, settlement generation, refunds, limit changes) each route through a change request or approval request with no direct-write path; the `'system'` carve-out is removed and the actor is authenticated; `X-Gme-Approval-Granted` is stamped from the verified approval store so `ApprovalConstraintEvaluator` actually gates; CFO override advances one step and is itself audited; and a test proves each protected operation is rejected without a distinct approver.

---

## 11. MAJOR — The regulatory lanes fabricate acceptance at the boundary; nothing has ever been filed

The computation logic is real and unit-tested — KoFIU CTR/STR thresholds with the statutory KRW 10M fallback, BOK FX1014/FX1015 direction mapping and fixed-width padding, Hometax monthly VAT aggregation net of GME's 2% spread and the KRW 500 levy. That part is claimable. The transmission is not, and the code returns success anyway:

- **Hometax:** the only `HometaxClient` implementation is `StubHometaxClient`, which fabricates the NTS acknowledgement — `"STUB-INV-" + seq`, a spec-shaped fake 24-char `ntsConfirmation`, and literal status **`"ACCEPTED"`**. Anyone reading `report_filing` or the logs sees an accepted NTS filing that never left the JVM. The mTLS cert is the literal string `stub-cert-id` (`application.yml`, `HometaxInvoiceScheduler.java:70`); no keystore, no XML signing.
- **KoFIU:** `StubKofiuFeedClient` returns `"STUB-" + UUID.randomUUID()`. `StubKofiuTransactionPort` — the only wired port — returns `List.of()`, so **no CTR or STR has ever been computed from real data**. The file layout is guesswork: `KofiuFeedFileBuilder.java:22-26` states the spec was unconfirmed, with five in-body TODOs for header order, CTR/STR field positions and trailer format.
- **BOK:** `BokFxFileBuilder.java:107` defines `TODO_OI03 = "TODO_OI03"` and writes it into **col 15 `bok_txn_code` and col 16 `bok_fx_reporting_category`** (`:221-224`) — two mandatory codes, so any file generated today is rejectable on its face. `submitStub()` (`:229-242`) only logs *"[STUB] Would SFTP-submit…"*. The real-data adapter is gated off (`gmepay.transaction-mgmt.fx-committed.enabled: false`), so output is built from fixtures.
- All three master gates default `false`. `ReportFilingService.recordSubmission(...)` — the only method that sets status `SUBMITTED` — has **no production caller**, so `report_filing` never leaves `GENERATED`, exactly as `V001__create_report_filing.sql` warns.
- **The readiness board overstates itself.** `RegulatoryConfigSummary` derives `hometaxSet` as `v.hometaxIssuerCertId() != null` — and that value is legitimately `"stub-cert-id"` in every environment, so **`hometaxSet` reads `true` on a fully stubbed lane**. Its own Javadoc (`:12-13`) admits a `true` flag asserts only that config was entered.

**Claimable in due diligence:** threshold/VAT/FX-mapping logic implemented and tested; a per-partner regulatory config store exists. **Not claimable:** any filing to KOFIU, BOK or NTS; any SFTP/mTLS/signing implementation; any BOK-valid file.

**Done when:** the stub clients cannot be wired outside a test profile (the `"ACCEPTED"` literal is deleted); `TODO_OI03` is replaced with real BOK codes; the KoFIU file spec is confirmed and the layout TODOs closed; `RestCommittedFxTransactionPort` and a real KoFIU transaction port are enabled; `recordSubmission` is called on real channel acknowledgement; and the compliance-overview flags distinguish "config entered" from "channel live" in the UI, not only in a Javadoc.

---

## 12. MAJOR — No AML transaction monitoring, and no real sanctions/PEP screening in the payment path

- **Nothing screens a transaction, sender or beneficiary.** `grep -rni "sanction|screening|watchlist|pep" services/{payment-executor,transaction-mgmt,smart-router}/src/main` → no hits. Screening exists only as an **onboarding-time** KYB call (`POST /v1/partners/{id}/kyb/screen`) on the partner entity and its UBOs.
- **The default screening provider is a substring match.** `libs/lib-kyb/.../StubKybAdapter.java`: `HIT_TOKEN = "SANCTIONED"` → HIT with score 0.99 on list `"STUB_WATCHLIST"`; `REVIEW_TOKEN = "REVIEW"` → NEEDS_REVIEW. It is the wired default (`kyb-adapter/application.properties:26` → `gmepay.kyb.provider=stub`).
- **The real provider is a hard-fail placeholder.** `services/kyb-adapter/.../octa/OctaKybAdapter.java` — `screen()` and `runFullKyb()` both `throw notYetAvailable()` ("Octa Solution sandbox credentials pending — ADR-014"). Its Javadoc lists what is unbuilt: entity+UBO fan-out, full CDD, **ongoing-monitoring subscription**, raw-response archival.
- **No ongoing rescreening** of LIVE partners against updated lists, despite `KybService.java:48` noting *"KoFIU/FSS expect rescreens for LIVE partners."* **PEP is self-declared** — `KybJson.java:71` writes `"isPep"` from operator input, not from a PEP list.
- **No monitoring or alerting.** No rule engine, no velocity/structuring detection, no case management. The only threshold logic is the KoFIU batch job of §11 (gate off, empty data source). `LimitsEntity.java:61` holds a per-partner AML velocity cap whose push to prefunding is *"(gated; no-op by default)"* (`LimitsService.java:117-118`). `prefunding/V003__create_balance_alert.sql` is a float alert, not an AML alert.
- **The `Octa Solution AML external partner/` folder is vendored source for a different system** — a standalone ASP.NET project, not in `settings.gradle`, not in `docker-compose.yml`, called by no Java service. Its own controller says *"Currently support tran only"*, its mapper populates `COUNTRY_CD` from `Sender_name`, its Octa base URL has an empty host (`http:///view/...`), and all three config keys point at an absolute developer path under `C:\Users\GME\Documents\...` that exists on one machine. It also carries the committed DB credential in §4.

**Done when:** a screening call sits in the payment authorization path for sender/beneficiary (or a documented, risk-accepted exemption exists with a compensating control); the Octa adapter is live or a real vendor is integrated, with the stub unusable outside tests; ongoing rescreening runs on a schedule for LIVE partners; PEP status comes from a list not a checkbox; threshold/velocity alerting fires in real time to a case queue; and the vendored .NET folder is removed from this repo.

---

## 13. MAJOR — Webhook and IPN integrity: one global secret signs every partner's webhooks; scheme response verification off; the documented IP allowlist does not exist

- **A single shared HMAC secret signs all partners' webhooks.** `notification-webhook/.../dispatcher/DefaultWebhookTargetResolver.java:46,79-81` resolves the target URL per partner but returns `configuredSecret` — the one global `gmepay.webhook.signing-secret` — as the signing key for every one of them. The `webhook_endpoint` row stores only a hash of the intended per-endpoint plaintext; the Vault-backed resolver the design calls for does not exist. One leaked value forges webhooks to every partner. *(It does at least fail closed when unset, leaving rows PENDING.)*
- **9Pay response-signature verification is off by default.** `scheme-adapter-ninepay/application.yml:58-59` → `verify-responses: false`, and `NinepayApiClient.java:384` returns early when false. So API responses that drive payout state are accepted unverified in the default configuration. *(IPN verification is always attempted and correctly fails closed to HTTP 400 — `NinepaySchemeAdapter.java:215-226` — which is the right behaviour.)*
- **No IPN replay protection.** `handleIpn` audits then applies `recordIpn` unconditionally; a captured, validly-signed IPN can be replayed to re-apply state. The nonce store used for gateway HMAC replay (`JpaNonceStore`) is not used here.
- **The IP whitelisting relied on for the IPN edge is not implemented anywhere.** `scheme-adapter-ninepay/application.yml` states 9Pay requires mutual IP whitelisting (test `35.221.251.138`; prod `35.240.219.196`, `35.187.225.236`) and that it is *"enforced at the network layer, not here."* There is no NetworkPolicy, no ingress allowlist annotation, and no firewall config in the repo — the same missing trust boundary as §6.
- Gateway-side replay protection is separately fail-open by default (§5).

**Done when:** each webhook endpoint signs with its own Vault-retrieved secret and rotation is supported; `verify-responses` defaults to `true` and the service refuses to start in a live profile with a blank 9Pay public key; IPN handling is idempotent with a persisted `(request_id, trans_id, code)` replay guard; and the 9Pay IP allowlist is enforced by committed ingress/network config with a test proving a non-whitelisted source is rejected.

---

## 14. MAJOR — Zero supply-chain or security gates in CI, on an out-of-support framework floor, in root containers

`.github/workflows/` contains two files. `ci.yml` is a correctness pipeline: `build`, `integration` (Testcontainers), `e2e`, `compose-smoke` (still `continue-on-error: true` despite the in-file note to flip it once green), `ui-build`. Against the seven gates the project's own `Documentation/services_backlog/security-platform.md:626` (SEC-09 §5.6/5.7) mandates:

| Gate | Status |
|---|---|
| Dependency vulnerability scanning | **ABSENT** — no dependabot/renovate, no dependencyCheck/snyk/trivy; `npm audit` is *explicitly disabled* via `--no-audit` (`ci.yml:205`, `apps/admin-ui/Dockerfile:4`) |
| SAST / CodeQL / semgrep | **ABSENT** — the only custom check is `portabilityGuard` (cloud-SDK coordinates), not a security guard |
| Secret scanning | **ABSENT** — `platform-infra.md:2057` specifies a TruffleHog/gitleaks workflow that was never implemented |
| Container image scanning | **ABSENT** — 24 Dockerfiles, no Trivy/Grype/Scout |
| SBOM | **ABSENT** |
| License check | **ABSENT** |
| Branch protection / CODEOWNERS | **NO EVIDENCE IN REPO** — no CODEOWNERS, no ruleset, despite `program-mgmt.md:20,27` requiring it |

*Clean:* no `pull_request_target`, no secrets referenced in PR-triggered workflows, all actions GitHub-owned (tag-pinned, not SHA-pinned). `maven-publish.yml` is a dead stock template targeting a nonexistent `pom.xml` on JDK 11 while holding `packages: write` and `github.token` — delete it.

**Dependency floor (all BOM-resolved, none pinned in-repo):** Spring Boot **3.3.4** (Sept 2024, ~22 months stale, past OSS support) → Spring Framework 6.1.13, Spring Security 6.3.3, **nimbus-jose-jwt 9.37.3** (the JWT verification path), Tomcat 10.1.30, Netty 4.1.113, Logback 1.5.8, Hibernate 6.5.3, BouncyCastle 1.78. Spring Cloud **2023.0.3** is two release trains behind. Next.js **14.2.18** (admin-ui) / **14.2.5** (partner portal — divergent patches, two majors behind and EOL for security backports); ESLint 8.57.x is EOL. Nothing in CI would ever surface a CVE against any of these.

**Containers:** **23 of 24 run as root** — only `apps/partner-portal-ui/Dockerfile:29-37` sets a `USER`. All 22 JVM Dockerfiles share one template, so this is a single propagating edit. No digest pinning anywhere (`eclipse-temurin:21-jre`, `node:20-alpine`, `mongo:7`, `postgres:16-alpine`, `keycloak:25.0`, own services at mutable `:dev`); `cp-schema-registry:7.6.1` runs against `cp-kafka:7.5.0`. Keycloak runs `start-dev --import-realm` with `KC_HOSTNAME_STRICT_HTTPS: "false"`. *(No privileged/host-network/docker-socket mounts — clean there.)* Gradle wrapper has **no `distributionSha256Sum`** and CI runs no wrapper-validation action, while all 22 JVM builds resolve dependencies through a deliberately-trusted TLS-inspecting proxy (`keytool -importcert … gme-root-ca.crt`) with no dependency verification or locking.

**Done when:** dependency scanning, SAST, secret scanning, image scanning and SBOM generation all run and **block** merge; `compose-smoke` is required; `--no-audit` is removed; `maven-publish.yml` is deleted; Spring Boot/Spring Cloud/Next.js are on supported lines with a standing upgrade cadence; a `USER` directive lands in all 23 root Dockerfiles; base and infra images are digest-pinned; `distributionSha256Sum` + wrapper validation + `dependencyLocking`/`verification-metadata.xml` are in place; and CODEOWNERS + branch protection are evidenced.

---

## 15. MINOR — Dev/stub fallbacks are the defaults, and there is no retention or erasure capability

**Insecure-by-default fallbacks** that a real deployment inherits silently unless every env var is remembered — each individually small, collectively the reason most findings above are live rather than theoretical:

- `SPRING_DATASOURCE_URL` defaults to `jdbc:h2:mem:...` in **every** service, including `config-registry` (all partner PII) and `kyb-adapter` (screening decision trail).
- `gmepay.vault.endpoint` unset ⇒ `InMemoryVaultClient` (KYB document bytes in heap).
- **Mongo, Redis and Kafka run with no authentication at all** and publish host ports (`docker-compose.yml:200-213,250`; `KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: "PLAINTEXT:PLAINTEXT,EXTERNAL:PLAINTEXT"`). Absence of a password is worse than a weak one. `merchant-qr-data` also excludes Mongo autoconfiguration entirely by default, falling back to `InMemoryMerchantRepository`.
- 12 of the BFF's upstream clients default to `stub` (`GMEPAY_*_CLIENT`), including `operator-action-audit` (§9) — so an under-configured deploy shows plausible synthetic data instead of failing.
- `run-fleet.ps1` sets **no security env at all**: no RBAC, no internal-auth, no secrets — every default above applies in the mode most likely to be tunnel-exposed.

**No retention or erasure capability.** `grep -rniE "retention|purge|anonymi|pseudonym|erasure|gdpr"` across all service/lib main sources and migrations returns only the HMAC nonce window, the vault object-lock config, and `toString()` redactions. No `@Scheduled` job deletes PII; `JpaNonceStore.java:26` explicitly defers pruning to *"an out-of-band sweep job (not in this codebase)"*; no Mongo TTL index; no `expires_at` purge on any PII table. `security-platform.md:233` describes a job that only **counts** rows past `audit.log.retention-years=7` and logs WARN — *"does NOT delete"*. `platform-infra.md:674-676` references a `mask_staging_data.sh` that does not exist.

Two design choices make erasure **structurally impossible** today: `audit_log.before_jsonb/after_jsonb` hold plaintext full-row partner snapshots in a per-aggregate hash chain with no purge path (deleting a row breaks verification by design), and vault documents are written under object-lock **COMPLIANCE** mode for 10 years (`VaultBucketInitializer.java:17-19`: *"not GOVERNANCE: even bucket admins cannot delete during retention"*) — so a KYB/UBO scan is undeletable for a decade. Combined with SCD-6 bitemporal append-only `partner_*` tables, there is no PIPA Art. 21 / GDPR Art. 17 path.

**Done when:** no service starts on H2/in-memory storage, an in-memory vault, or a `stub` client outside a test profile (fail fast on a missing datasource/vault/secret); Mongo, Redis and Kafka require authentication and are not published to the host; retention windows are implemented as purge/anonymization jobs per data class; audit snapshots are field-minimized or reversibly tokenized so a subject can be erased without breaking the chain; and vault retention is GOVERNANCE (or crypto-shredding via a per-partner KMS key is actually implemented, as `VaultClient`'s Javadoc already promises).

---

## Remediation shape

Items 1, 3, 4, 5, 6 are dominated by **configuration and deletion**, not new engineering — a dev endpoint to delete, a stub bean to profile-gate, four env vars to require, `gmepay.rbac.enabled=true` fleet-wide, a Next middleware. Those buy the largest risk reduction per hour and should land first. Item 2 (a security filter chain per service) and item 7 (column encryption + TLS) are the two genuine engineering programs. Items 9-12 are the ones an auditor will open first, and item 11 is the one that must not be *claimed* until it is true — a fabricated `"ACCEPTED"` from `StubHometaxClient` in front of a regulator is a materially worse outcome than an honestly empty filing register.
