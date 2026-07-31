# api-gateway — CHANGELOG

## 2026-07-30 — The edge's cross-request state is now shared: api-gateway can run N>1

Closes T0-7 residual (a). The per-partner rate-limit window and the replay-protection nonce set were
`ConcurrentHashMap`s, which made both controls **wrong** above one replica rather than merely
un-scaled: N replicas enforced N x the configured cap, and a captured signed request was replayable
once per replica inside the 5-minute clock-skew window. No new infrastructure — docker-compose
already ran `redis:7-alpine` and already exported `SPRING_DATA_REDIS_HOST` to this service.

### Added
- **`ratelimit/RedisRateLimitStore`** — window-indexed `INCR` + `EXPIRE`. The window number is part
  of the key, so a single atomic `INCR` is the whole algorithm (no Lua, no lock, no read-modify-write)
  and every replica agrees on where the window boundary is. A failed `EXPIRE` can therefore only leak
  one key for one window; it can never pin a partner at its limit forever, which is the failure worth
  designing away.
- **`replay/RedisNonceStore`** — one `SET key 1 NX EX ttl`. Redis picks the winner server-side and the
  TTL is attached in the same command, so a nonce is never recorded without an expiry. An empty reply
  reads as **not fresh**: "I do not know whether this nonce is new" must not resolve to "accept".
- **`sharedstate/GatewaySharedStateConfig`** — the single wiring point. `gateway.shared-state.store`
  = `auto` (default: Redis when `spring.data.redis.host` is set, memory otherwise, logging which and
  the resulting replica ceiling) | `redis` (**refuse to start** without a host) | `memory` (legal,
  logged `WARN`) | anything else (**refuse to start**). **One switch governs both stores** on purpose:
  the half-shared configuration — global nonces, per-pod rate window — is a state nobody intends and
  anybody could produce with two keys.
- **`replay/ReplayProtectionProperties`** — `nonce-ttl-seconds`, `max-nonce-length` (256; an
  over-long `X-Nonce` is now **400 `INVALID_NONCE`** before it becomes an unbounded write into
  infrastructure shared with every other tenant of this Redis) and `on-store-error`.
- **`gateway.rate-limit.on-store-error`** — `deny` (default) | `local` | `allow`.

### Changed
- **The store-unavailable posture is named per control, and both defaults stay T0-7-consistent.**
  Rate limit defaults to **deny** (429), exactly as `fail-open: false` did — and `fail-open: true`
  still maps to `allow`, so a deployment that set it keeps what it configured. What is new is
  **`local`**: degrade to the per-JVM window, so the cap becomes N x rather than disappearing. That is
  the recommended setting for a multi-replica deployment that judges a Redis outage worse than an N x
  cap, and it is the option a boolean could not express.
- **Replay protection now answers store errors instead of leaking them.** Previously the store was a
  map and nothing caught it, so a Redis error would have surfaced as a bare 500 from the reactive
  pipeline. Now **503 `REPLAY_STORE_UNAVAILABLE`**, or a degraded per-JVM check under
  `on-store-error: local`. **There is deliberately no `allow`** — a replay check an attacker can
  disable by making one Redis unreachable is not a replay check — and an unrecognised value fails
  property binding, so the service refuses to start rather than resolving to something permissive.
  The `onErrorResume` is scoped to the replay decision only (the outcome is materialised into an enum
  before the chain is invoked), so a downstream error is not swallowed and no verdict is written onto
  an already-committed response — the placement rule T0-7 established for the credential filters.
- `InMemoryRateLimitStore` / `InMemoryNonceStore` lost their `@Component @Primary
  @ConditionalOnProperty` annotations, which named a property nothing set and an alternative that did
  not exist. Both are now plain classes with one wiring path (the correction T0-7 applied to
  `ConfigPartnerCredentialService`), serving two roles: the selected store under `memory`, and the
  fallback under `on-store-error: local`.

### Operational consequence, stated plainly
Under the shipped defaults **Redis is a hard dependency of the partner edge**: an outage answers
partner traffic 503/429, and `management.health.redis.enabled: true` also takes the pods out of the
readiness rotation. That is what "fail closed" means here, and it is deliberate rather than a
side effect. All four Helm values files now carry an operator note to run Redis HA or to set
`on-store-error=local` knowingly.

### Tests
33 new (176 total, 0 failures). `TwoReplicaSharedStateTest` runs each scenario twice — two
Redis-backed stores over one in-process double, and two per-JVM stores — and asserts the shared pair
is right **and the per-JVM pair is wrong**, so a green test proves the defect was real rather than
merely that the code runs. `GatewaySharedStateConfigTest` pins the decision table, both
refuse-to-start cases and the shipped `application.yml`. `EdgeStoreUnavailablePostureTest` pins every
posture branch. T0-7's own fail-open/fail-closed tests are untouched and still green.
**Not verified:** no Docker on the build machine, so nothing here has spoken to a real Redis — the
Lettuce wire protocol, connection pooling and cluster key routing are unproven.

## 2026-07-28 — Partner edge authenticates against the real credential store; every fail-open branch denies (T0-7)

### Removed
- **`StubPartnerCredentialService` — DELETED from `src/main`.** It authenticated partner API calls
  against api keys *and HMAC secrets published in this repository* (`pk_test_abc`/`sk_test_xyz`,
  `pk_test_no_mtls`/`sk_test_no_mtls`) with `List.of()` as the IP allowlist, and it was the DEFAULT
  credential source in every environment. Deleted rather than `@Profile`-gated: a profile still ships
  the literals. The pairs now live only in `src/test` (`partner/TestPartnerCredentials`), which is what
  the negative tests assert against.
- **Dead config removed** — `gateway.replay-protection.{fail-open,store}` and `gateway.trust-proxy`
  were read by nothing in the repo. The CISO audit reasonably read the first as "replay protection is
  fail-open"; it never was (replay protection is unconditional: no `X-Nonce` ⇒ 400, reuse ⇒ 401).
  Removed rather than left implying a control that does not exist.
- `StubConfigRegistryClient` no longer seeds `partner_test_001` with loopback/RFC1918 SANDBOX ranges.
  The checked-in credential came with a checked-in allowlist, and since `gmepay.config-registry.client`
  was set for this service nowhere, that seed WAS the live allowlist. It now returns nothing (⇒ 403)
  and logs why at construction.

### Added
- **`partner/PartnerCredentialConfig`** — the one place the credential source is built. `source=stub`
  (and any unrecognised value) makes the service **refuse to start**; `config` is the default.
- **`partner/AuthIdentityCredentialStatusClient`** — `POST /internal/auth/keys/resolve` on
  auth-identity (the T1-1 endpoint), presenting `X-Gme-Internal`. Maps found/active to
  ACTIVE / UNKNOWN / INACTIVE, and every failure mode (transport, non-2xx, timeout, empty body, blank
  internal token) to `PartnerCredentialSourceUnavailableException`.
- **`partner/AuthIdentityVerifiedPartnerCredentialService`** — a key is accepted only if the gateway
  holds signing material for it **and** auth-identity says the credential is live. Revocation is now
  effective at the edge. The local lookup short-circuits, so an unauthenticated caller cannot probe
  the upstream store.
- **`partner/PartnerCredentialSourceUnavailableException`** — separates "unknown key" (401, a
  decision) from "store unavailable" (503, the absence of one).

### Changed — fail-closed defaults
- `gateway.partner-credentials.source` `stub` → **`config`**, table empty by default (⇒ 401 to
  everyone until an operator populates it), plus `verify-with-auth-identity: true`.
- `security.gateway.allowlist.trust_header_only_in_dev` `true` → **`false`**. The unauthenticated
  `X-Partner-Id` header used to choose *which partner's* allowlist was checked.
- `security.gateway.allowlist.fail-open` `true` → **`false`**.
- `gateway.rate-limit.enabled` `false` → **`true`**, `fail-open` `true` → **`false`**.
- `HmacSignatureFilter`, `MtlsFingerprintFilter`, `PartnerIpAllowlistFilter` answer **503
  `CREDENTIAL_SERVICE_UNAVAILABLE`** when the credential store cannot be consulted, instead of
  passing through to the next filter or 500-ing. The HMAC filter catches every throwable there.
- A config row with a blank `hmac-secret` is dropped (HMAC-ing with an empty key is forgeable).

### Notes
- **The partner API now authenticates nobody until configured.** HMAC needs the plaintext secret and
  auth-identity stores only a PBKDF2 digest, so signing material must come from
  `gateway.partner-credentials.partners[]` (env-injected). See `PartnerCredentialConfig`'s javadoc and
  `docs/COMPOSE.md` §"Partner API credentials (T0-7)".
- **Per-instance windows:** the only `NonceStore`/`RateLimitStore` implementations are in-memory, so
  with N replicas the rate cap is N x the configured value. Recorded as a T0-7 residual.
- mTLS stays off: the fingerprint header is spoofable without a terminator this repo does not define.
- 44 new tests (143 total, 0 failures).

## 2026-06-30 — OIDC issuer made provider-neutral (agent/cloud-audit)

### Changed
- **Resource-server issuer URI is now injected and provider-neutral** — accepts ANY
  OIDC issuer (self-hosted Keycloak or any cloud IdP), not a literal Keycloak URL.
  `spring.security.oauth2.resourceserver.jwt.issuer-uri` now defaults to
  `${OIDC_ISSUER_URI:http://localhost:8090/realms/gmepay}` — the local Keycloak default
  is dev-only. Set `OIDC_ISSUER_URI` (provider-neutral env name) per environment;
  `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` (relaxed binding) still works
  and takes precedence. JWKS can be pinned via
  `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI` when discovery is unreachable.
  No code change; the JWT decoder already binds from this property. Additive — local
  Keycloak flow unchanged.

## 2026-06-30 — Rate limiting + config-backed credential source (agent/api-gateway)

### Added
- **Per-partner rate limiting (API-05 §3.5).** New `RateLimitFilter` (GlobalFilter, ORDER=6,
  between ReplayProtectionFilter=5 and IdempotencyKeyFilter=7). Fixed-window per-second throttle
  keyed on `(partner_id, scope)`. Scopes resolved from the original (pre-rewrite) request path:
  `rates` (POST /v1/rates), `payments` (POST /v1/payments[/cpm/generate]), and `global` fallback.
  Limits 20 / 50 / 100 req/s respectively (configurable). Breach → 429 `RATE_LIMITED` with
  `Retry-After`; every response carries `X-RateLimit-Limit/-Remaining/-Reset`.
  - `RateLimitStore` port + `InMemoryRateLimitStore` (default, `@Primary`,
    `gateway.rate-limit.store=memory`) — Redis-optional, single-instance fallback, mirroring the
    `NonceStore` precedent.
  - `RateLimitProperties` (`gateway.rate-limit.*`); **disabled by default** (`enabled=false`) so
    existing flows/tests are unthrottled until switched on per environment. `fail-open` (default
    true) decides behaviour on store error.
- **Config-backed partner credential source.** `ConfigPartnerCredentialService` (`@Primary`,
  active on `gateway.partner-credentials.source=config`) resolves partners from
  `ConfigPartnerCredentialProperties` (`gateway.partner-credentials.partners[]`) instead of the
  hard-coded `StubPartnerCredentialService`. The stub remains the default fallback so the gateway
  still boots standalone. Same `PartnerCredentialService` interface a future R2DBC/Redis impl (T18)
  can replace without touching any filter. `hmac-secret` is intended to come from an env-var
  placeholder, never a checked-in literal.

### Changed
- `ApiGatewayApplication` now `@EnableConfigurationProperties({RateLimitProperties,
  ConfigPartnerCredentialProperties})`.
- `application.yml`: added `gateway.rate-limit.*`, `gateway.partner-credentials.*`, and
  `gateway.replay-protection.store` defaults.
- `FilterChainOrderTest`: asserts RateLimitFilter ORDER=6 between replay (5) and idempotency (7).

### Tests
- `InMemoryRateLimitStoreTest` (3) — window counting, breach, per-key isolation, rollover.
- `RateLimitFilterTest` (8) — disabled pass-through, non-partner skip, within-limit headers,
  429+Retry-After on breach, scope independence, fail-open/fail-closed on store error, reset rounding.
- `ConfigPartnerCredentialServiceTest` (3) — field round-trip, unknown/null key empty, blank row skipped.
- `FilterChainOrderTest` (+1) — rate-limit ordering.

Build: `./gradlew :services:api-gateway:test` green.
