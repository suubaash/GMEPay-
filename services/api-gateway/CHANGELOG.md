# api-gateway — CHANGELOG

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
