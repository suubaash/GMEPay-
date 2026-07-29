> 작업: T0-6/T0-7 partner keys + secrets / 출처: agent

# T0-7 partner edge + T0-6 committed secrets

Gap sources: `Documentation/GAP_REGISTER.md` T0-6 / T0-7, from
`outputs/agent/audit_ciso-security_2026-07-28.md` findings #4 and #5. Builds on T1-1
(`fix_t1-credentials_2026-07-28.md` — real keys, PBKDF2 at rest, issue→resolve→revoke contract
tests, all still green) and mirrors the one-anchor secret idiom from
`fix_fleet-config-convergence_2026-07-28.md`.

Nothing was started: no Docker, no Keycloak, no fleet, no server. Static + unit evidence only.

---

## 1. The one thing that could not be fixed the obvious way

**HMAC-SHA256 needs the verifier to hold the plaintext secret. The real credential store cannot
supply one.** `api_keys` stores a salted PBKDF2-HMAC-SHA256 digest and `ApiKeyIssuanceService`
discards the plaintext at issuance (SEC-09 §4 — the property the audit itself named as worth
protecting). Grep confirms it: no `hmacSecret` column, no reversible copy, anywhere.

So "make the partner edge authenticate against auth-identity's API keys" cannot mean "get the secret
from auth-identity". Three options were considered:

| Option | Rejected because |
|---|---|
| Add a reversibly-encrypted `secret_enc` column + AES-GCM with an operator key | Weakens the one-way-at-rest property the audit praised, and needs a schema migration + a KMS-held key — that is T1-6, which the brief explicitly excludes |
| Move signature verification into auth-identity (`POST /internal/auth/verify` already exists) | Its `PartnerCredentialPort` production adapter calls config-registry `GET /v1/partners/{id}` expecting an `hmacSecret` field that endpoint does not return, so it resolves empty for every key. Fail-closed, but it authenticates nobody either — and it would relocate the problem, not solve it |
| **Split the responsibility (chosen)** | auth-identity is authoritative for **identity + lifecycle**; operator config supplies **signing material + edge policy**. A key must appear in **both**. |

The split is not a workaround for its own sake: the store genuinely does not model the other half of
what the edge needs (IP CIDR ranges, mTLS fingerprint, quote TTL). What it does add, which did not
exist before, is that **revocation now works at the edge** — previously the gateway had no notion
that a key could be revoked, so revoking one upstream changed nothing about what it accepted.

**Consequence, stated plainly: the partner API authenticates nobody until an operator populates
`gateway.partner-credentials.partners[]`.** That is the fail-closed posture the brief asked for, and
the reason T0-7 is `[~]` not `[x]`.

## 2. T0-7 — what changed at the edge

### The published keys

`StubPartnerCredentialService` was **deleted** from `src/main`, not `@Profile("test")`-gated: a
profile still ships the literals, and a literal in a repo is a literal an attacker has. The two
pairs now exist only as `src/test/.../partner/TestPartnerCredentials`, which is what the negative
tests assert against — their continued presence in tests is the proof the shipped build rejects them.

`gateway.partner-credentials.source` defaulted to `stub` in **both** the shipped `application.yml`
and `ConfigPartnerCredentialProperties`, and was overridden in no deployment file. Default is now
`config`; `stub` — and any unrecognised value, because a typo must not silently select a different
credential store — makes the gateway **refuse to start** (`PartnerCredentialConfig`).

### New classes (`services/api-gateway/.../partner/`)

| Class | Role |
|---|---|
| `PartnerCredentialConfig` | the single place the source is built; fails closed at startup on `stub`/unknown; carries the operator checklist in its javadoc |
| `AuthIdentityCredentialStatusClient` | `POST /internal/auth/keys/resolve` + `X-Gme-Internal`; found/active → ACTIVE / UNKNOWN / INACTIVE; every failure → unavailable |
| `AuthIdentityVerifiedPartnerCredentialService` | requires BOTH halves; short-circuits on a local miss so the edge cannot be used to probe the store |
| `PartnerCredentialSourceUnavailableException` | separates 401 (a decision) from 503 (the absence of one) |

`ConfigPartnerCredentialService` lost its `@Service`/`@Primary`/`@ConditionalOnProperty` — it is now
a plain class the config constructs, so there is exactly one wiring path. It also drops rows with a
blank `hmac-secret` (HMAC-ing with an empty key is forgeable by anyone).

### "Edge controls fail-open" — what it meant concretely, and each branch

Verified against the code rather than the audit's summary. Two of the five keys the audit listed
turned out to be **dead config**.

| # | Branch | Was | Now |
|---|---|---|---|
| 1 | `security.gateway.allowlist.trust_header_only_in_dev` | `true` — the **unauthenticated** `X-Partner-Id` header chose *which partner's* allowlist was checked, so whichever partner had the broadest ranges became everyone's allowlist | `false`; a mismatching header is 403 `PARTNER_ID_MISMATCH`. Logged loudly if switched back on |
| 2 | `security.gateway.allowlist.fail-open` | `true` — a config-registry outage admitted traffic that was never allowlist-checked | `false` ⇒ 403 |
| 3 | `gateway.rate-limit.enabled` | `false` — the documented API-05 per-partner cap was applied **nowhere** | `true` |
| 4 | `gateway.rate-limit.fail-open` | `true` — a store error admitted unlimited traffic, i.e. exactly the outcome an attacker wants from the control meant to bound enumeration | `false` ⇒ 429 |
| 5 | `StubConfigRegistryClient`'s seeded allowlist | `partner_test_001` + loopback/RFC1918 SANDBOX ranges, and `gmepay.config-registry.client` was set for this service in **no file**, so that seed was the live allowlist in every environment | returns nothing (⇒ 403) + a startup WARN naming the cause; `rest` wired in compose + Helm + fleet |
| 6 | credential-store failure in the 3 resolving filters | propagated → bare 500, or (mTLS/allowlist) **passed through** to the next filter | **503 `CREDENTIAL_SERVICE_UNAVAILABLE`** in all three. The HMAC filter catches every throwable, so a bug in the credential path cannot degrade to "allow" |
| 7 | `gateway.replay-protection.fail-open` | **dead config — read by nothing.** Replay protection is unconditional and already fail-closed (no `X-Nonce` ⇒ 400, reuse ⇒ 401) | removed, so it stops implying a control that does not exist |
| 8 | `gateway.trust-proxy` | **dead config — read by nothing** | removed |
| 9 | `security.gateway.mtls.enabled` | `false`, and when enabled it trusts a plain `X-Client-Cert-Fingerprint` header | **left off, deliberately.** No Nginx config, no ingress mTLS annotation and no NetworkPolicy exists in this repo, so enabling it would manufacture a spoofable "mTLS enforced" signal — worse than off-and-documented. Recorded as a residual |

The 503-on-error placement matters: `onErrorResume` is scoped to credential *resolution* only (the
outcome is materialised into an `Optional` first), so a downstream/proxy error is not swallowed and
no 503 is written onto an already-committed response.

## 3. T0-6 — what changed

### The JWT signing key (the priority case)

`gme.auth.jwt.signing-secret` defaulted to `changeme-at-least-32-chars-long!!` in **two** places —
`application.yml:40` and the `@Value` in `config/AuthConfig.java:44` — while
`GME_AUTH_JWT_SIGNING_SECRET` was set in **zero** files. HS256 is symmetric and the literal was
deliberately 33 chars so it cleared the length check: every environment signed real capability tokens
with a key readable from the repo, and nothing anywhere complained. That is forgeable tokens, failing
open silently.

Both defaults are gone. New `config/JwtSigningKeyEnforcedConfig` (same shape, same
`refuses to start` prefix and same `InitializingBean` mechanism as `prefunding`'s
`InternalAuthEnforcedConfig`) refuses to start on:

- blank / absent (the common case);
- shorter than 32 bytes (RFC 7518 §3.2);
- placeholder-shaped: `changeme`, `change_me`, `change-me`, `replace_`, `replace-with`, `replacefrom`,
  `your-secret`, `todo`, `xxxxx` — a Helm placeholder that reached a pod unsubstituted is a
  misconfiguration, not a key;
- **any literal ever published in this repo**, rejected *by value* (`PUBLISHED_KEYS`), so re-adding
  one is a boot failure and not a lint warning.

Only auth-identity reads this key (grep-verified), so no cross-service coordination was needed.

### Compose literal hygiene — one anchor per secret

| Anchor | Value | Was |
|---|---|---|
| `x-internal-auth-secret` | `dev-internal-svc-secret-not-for-prod` | pre-existing (T0-2) |
| `x-auth-jwt-signing-secret` | `dev-auth-jwt-signing-key-not-for-prod` | **new** — the variable was in no file at all |
| `x-rbac-edge-secret` | `dev-rbac-edge-secret-not-for-prod` | **new** — 2 inline copies |
| `x-pg-password` | `gmepay` | **new** — **28** inline copies (14 containers + 14 services), so rotating the local DB password meant 28 edits and one miss broke a service silently |

`KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD` and `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` were
**bare literals with no `${}` indirection at all** and are now env-overridable. Helm gained no
working credential at any layer.

### The vendor DB credential

`Octa Solution AML external partner/appsettings.json` carried a real SQL Server login
(`server=10.25.10.70;database=DB_AML_SCREENING;uid=uatstaging;pwd=…`), tracked in git. Removed from
the working tree, replaced by an empty fail-closed value plus in-file operator instructions
(`ConnectionStrings__DefaultConnection` env var or `dotnet user-secrets`, both of which outrank the
file in ASP.NET Core's configuration order). The file also had a trailing-comma JSON syntax error,
now fixed. **The value remains in git history, so the login must be ROTATED at the database** — the
one required action this repo cannot perform.

## 4. What an operator must now supply

| Variable | Consequence if absent / left at the placeholder |
|---|---|
| **`GME_AUTH_JWT_SIGNING_SECRET`** (≥32 chars random) | `auth-identity` **refuses to start** |
| **`gateway.partner-credentials.partners[]`** — per live partner: `api-key` (the `pk_…` auth-identity issued), `partner-id` (config-registry partner **CODE**), `hmac-secret` (the one-time `sk_…` plaintext), `ip-cidr-ranges`, optional `mtls-cert-fingerprint` | the partner API answers **401** to every request. Empty is the intended default |
| `GMEPAY_INTERNAL_AUTH_SECRET` (already wired) | now on the hot path for **every** partner request (the lifecycle lookup) ⇒ partner traffic **503** |
| `GMEPAY_AUTH_IDENTITY_BASE_URL` (already wired) | same ⇒ **503** |
| `GMEPAY_CONFIG_REGISTRY_CLIENT=rest` (now wired on all 3 surfaces) | every partner request **403 `IP_NOT_ALLOWED`** |
| `GMEPAY_RBAC_SECRET`, `GMEPAY_WEBHOOK_SIGNING_SECRET` (unchanged, now single-anchor) | RBAC bundles refused unsigned / webhook deliveries stay PENDING |
| `GMEPAY_LOCAL_PG_PASSWORD`, `KEYCLOAK_ADMIN(_PASSWORD)`, `MINIO_ROOT_(USER|PASSWORD)` | dev literals on **published host ports** (5433-5446, 8097, 9000/9001) |
| **ROTATE the `uatstaging` SQL Server login** | still disclosed in git history |

Documented in `docs/COMPOSE.md` §"Secrets an operator must supply (T0-6)" and
§"Partner API credentials (T0-7)", plus the `run-fleet.ps1` `.NOTES` header.

## 5. Files changed

**api-gateway** — deleted `partner/StubPartnerCredentialService.java`; new
`partner/{PartnerCredentialConfig, AuthIdentityCredentialStatusClient,
AuthIdentityVerifiedPartnerCredentialService, PartnerCredentialSourceUnavailableException}.java`;
changed `partner/{PartnerCredentialService, ConfigPartnerCredentialService,
ConfigPartnerCredentialProperties}.java`, `filter/{HmacSignatureFilter, MtlsFingerprintFilter,
PartnerIpAllowlistFilter}.java`, `ratelimit/RateLimitProperties.java`,
`registry/StubConfigRegistryClient.java`, `src/main/resources/application.yml`, `CHANGELOG.md`.
Tests: new `partner/{TestPartnerCredentials, PartnerCredentialConfigTest,
AuthIdentityCredentialStatusClientTest, AuthIdentityVerifiedPartnerCredentialServiceTest}.java`,
`filter/PartnerEdgeFailClosedTest.java`; one line in `filter/RateLimitFilterTest.java` (a test of the
*disabled* path now has to opt out explicitly).

**auth-identity** — new `config/JwtSigningKeyEnforcedConfig.java` + its test; changed
`config/AuthConfig.java`, `src/main/resources/application.yml`,
`src/test/resources/application-test.properties`, `CHANGELOG.md`.

**Deployment / docs** — `docker-compose.yml`, `deploy/helm/gmepay/values{,-aws,-azure,-onprem}.yaml`,
`run-fleet.ps1`, `scripts/check_internal_auth_wiring.py` (+20 T0-6 assertions), `docs/COMPOSE.md`,
`Documentation/GAP_REGISTER.md`, `Octa Solution AML external partner/appsettings.json`.

**Not touched, per the ownership constraint** (`payment-executor`, `transaction-mgmt`,
`revenue-ledger`, `notification-webhook`, `settlement-reconciliation`): nothing was needed in any of
them — no change here alters an interface they consume. The gateway's partner-credential path is
internal to api-gateway plus the already-published auth-identity resolve endpoint.

## 6. Verification (static + unit only)

| Check | Result |
|---|---|
| `gradlew :services:api-gateway:test` | ✅ **143** tests, 0 failures (44 new) |
| `gradlew :services:auth-identity:test` | ✅ **215** tests, 0 failures (8 new); T1-1's `PartnerCredentialIssuanceContractTest` still green |
| `gradlew testClasses` (whole repo) | ✅ BUILD SUCCESSFUL |
| `python scripts/check_internal_auth_wiring.py` | ✅ **90/90** (was 67/67 + 4 pre-existing; 20 new T0-6 assertions) |
| `python scripts/check_monitoring_wiring.py` | ✅ 36/36 |
| `node docker/keycloak/check-topology.mjs` | ✅ 101/101 |
| PyYAML parse: compose + 4 Helm values + `Chart.yaml`, values asserted programmatically | ✅ all parse |
| PowerShell `Parser::ParseFile` on `run-fleet.ps1` | ✅ 0 errors (2161 tokens) |

What the new tests actually assert:

- **a real issued key with a valid signature passes** and reaches downstream; **a bogus key 401s**;
  **the published stub keys 401** even when the lifecycle client is rigged to answer `active=true`
  for anything — so the rejection provably comes from the gateway holding no material for them, not
  from auth-identity saying no;
- **revoked-upstream 401s** while still present in gateway config (revocation is effective at the
  edge), and **never-issued-upstream 401s**;
- **store unreachable → 503, not 401 and not a pass**, at each of the three resolving filters, with
  downstream asserted never invoked; transport failure, 5xx, 401-from-upstream, empty body and a
  blank internal token are each covered separately;
- **an unauthenticated caller cannot probe the credential store** (zero lookups on a local miss);
- **`source=stub` and unknown sources refuse to boot**; `stub` is absent from the accepted roster;
- **a service with the JWT key unset refuses to boot** — every rejection path, plus an assertion over
  the *shipped* `application.yml` so the regression cannot return;
- **the shipped gateway `application.yml` selects `config`, ships the table empty, and carries no
  published credential**, and a walk over every `src/main` file fails if a published HMAC secret is
  re-added anywhere in the module.

## 7. Residual risk / not done

1. **Key rotation and versioning are NOT built** — out of reach here, as the brief anticipated. Grep
   for `kid`/`keyVersion`/`activeKeyId` across `.java`/`.yml`/`.sql` is still zero hits. The JWT key,
   RBAC stamp key, internal-auth token, webhook signing secret and the two scheme RSA keys are each a
   single static value with no dual-key overlap, so rotating any of them is a fleet-wide downtime
   cutover. Doing it properly needs a `kid` header, an accept-old/verify-new window and a key table —
   a build. Left as a precise register note rather than half-built.
2. **The partner API authenticates nobody until configured.** Deliberate and documented, but it is a
   functional regression for anything that relied on the stub: `gmepay-test-platform`'s `testkit.ts`
   (signs with `sk_test_xyz`) and `Documentation/services_backlog/api-gateway.md`'s certification
   checklist both assume the published pair. Those cases will now correctly 401.
3. **Per-instance windows.** `InMemoryNonceStore` and `InMemoryRateLimitStore` are the only
   implementations, so with N gateway replicas the rate cap is N x the configured value and a captured
   request can be replayed once per replica inside the 5-minute skew window. The `store:` keys that
   named Redis were dead config and are removed rather than left as a false promise.
4. **mTLS still off** — see §2 row 9. Not closed, and enabling it would be worse than leaving it.
5. **Still-committed credentials outside this task's write scope**: `docker/keycloak/realm-gmepay.json`
   (`admin-ui-dev-secret` / `partner-portal-ui-dev-secret` on `directAccessGrantsEnabled` confidential
   clients, plus `admin`/`demo` and `partner-demo`/`demo` users); `services/qr-service`'s
   `changeme-internal-token` (nothing verifies it, so it authenticates nothing — a cleanup, not a
   hole); `libs/lib-vault`'s `VaultProperties` dev defaults; `postgres-keycloak`'s `keycloak`/`keycloak`.
   Each needs an owner.
6. **No mechanical guard against the next committed secret.** Root `.gitignore` (484 bytes) still has
   no `*.pem`/`*.key`/`*.p12`/`*.jks`/`*.env` rule, and there is no gitleaks/TruffleHog CI job. An
   untracked, un-ignored keystore is still sitting at `docker/certs/gme-truststore`.
7. **No live end-to-end confirmation.** No server, no Docker, no fleet, per the constraint. The
   accept/reject/503 claims are asserted at the filter boundary with mocked collaborators and a
   no-network `WebClient` exchange function; the compose/Helm/fleet claims are from PyYAML/PowerShell
   parses and the wiring checker. The `PartnerOnboardingE2ETest` fleet would now need
   `gateway.partner-credentials` rows to exercise a signed partner call — not added, not run.
8. **The gateway's RBAC claim resolver reads a property no manifest sets.**
   `WebClientRbacClaimResolver` binds `gme.auth-identity.base-url` while compose/Helm set
   `GMEPAY_AUTH_IDENTITY_BASE_URL` (→ `gmepay.auth-identity.base-url`). Pre-existing and currently
   harmless (RBAC stamping resolution is fail-open by design and the filter is conditional), and my
   new lifecycle client deliberately uses the `gmepay.*` name the manifests actually set. Flagged, not
   fixed — changing the RBAC resolver's property name is a T0-3 concern.
