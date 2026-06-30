> 작업: api-gateway backlog 완성 / 출처: agent

# api-gateway build report — 2026-06-30

## Assessment of actual code state
The published backlog %s are stale: the service is far ahead of "scaffold" status. Verified
present and tested under `services/api-gateway/src` before doing any work:

- Spring Cloud Gateway scaffold, `/v1` route table, two security chains (HMAC + OIDC).
- `HmacSignatureFilter` (SHA-256 canonical string, constant-time compare, clock-skew window,
  partner_id stamping), `HmacSignatureVerifier`.
- `ReplayProtectionFilter` (ORDER 5) **already existed** — per-partner `X-Nonce` via a
  `NonceStore` port + `InMemoryNonceStore` (Redis-optional, `@Primary`/`@ConditionalOnProperty`,
  fail-open). The gap-plan ask for replay protection was already met.
- `PartnerIpAllowlistFilter` + `ConfigRegistryClient` (REST + stub) for IP allowlist — a
  config-registry-backed source already existed *for allowlists* (not for credentials).
- `MtlsFingerprintFilter`, `IdempotencyKeyFilter`, `GatewayErrorWriter` canonical error envelope,
  RBAC claim stamping, health/metrics wiring.
- `StubPartnerCredentialService` — only 2 hard-coded keys; **no config/DB-backed credential source**.
- **No rate limiter of any kind** (grep confirmed: only comments referenced "rate-limit").

So the two genuinely-missing, highest-value items were the **rate limiter** and the
**config-backed credential source**. Both implemented this run.

## Tickets / gap items done
1. **Per-partner rate limiting (API-05 §3.5)** — `RateLimitFilter` (GlobalFilter ORDER=6),
   `RateLimitStore` port, `InMemoryRateLimitStore` (Redis-optional, fixed-window, `@Primary`),
   `RateLimitProperties`. Per-scope limits (rates 20 / payments 50 / global 100 req/s), 429
   `RATE_LIMITED` + `Retry-After`, `X-RateLimit-Limit/-Remaining/-Reset` on every response.
   Disabled by default; fail-open/closed on store error per config. (≈ WBS 8.1-T07 intent,
   without a hard Redis dependency.)
2. **Config-backed credential source with stub fallback** — `ConfigPartnerCredentialService`
   (`@Primary`, active on `gateway.partner-credentials.source=config`) +
   `ConfigPartnerCredentialProperties` (`gateway.partner-credentials.partners[]`). Stub remains
   the default fallback; same `PartnerCredentialService` interface a live R2DBC/Redis impl (T18)
   can drop into. (Addresses the "StubPartnerCredentialService has only 2 hardcoded keys" gap.)
3. **Filter chain order updated + asserted** — `FilterChainOrderTest` now pins RateLimitFilter
   ORDER=6 between replay (5) and idempotency (7).
4. **Wiring + config** — `@EnableConfigurationProperties` on the app class; `application.yml`
   defaults for `gateway.rate-limit.*`, `gateway.partner-credentials.*`, and
   `gateway.replay-protection.store`.

### Tests added (all green, no skips)
- `InMemoryRateLimitStoreTest` (3), `RateLimitFilterTest` (8), `ConfigPartnerCredentialServiceTest`
  (3), `FilterChainOrderTest` (+1). Covers: replay/rate breach rejected (429), within-limit accept
  with headers, signature path untouched, fail-open vs fail-closed, scope isolation, config
  resolution + unknown-key empty.

## Build status
`./gradlew :services:api-gateway:test` — **BUILD SUCCESSFUL**. New tests: 8+3+3 plus 1 added to
FilterChainOrder, 0 failures / 0 skipped. Baseline suite remained green throughout.

## % estimate
Service is functionally ~90% of the WBS 8.1 security/edge surface (routing, both security chains,
HMAC, replay, IP allowlist, mTLS-fingerprint, idempotency, content-type/version, error envelope,
and now rate limiting + a config-backed credential source). Remaining ~10% is integration-gated
(live Redis-backed stores, live config-registry credential lookup, OpenAPI/sandbox/cert kit
8.9/8.10) — not buildable inside this frozen-boundary worktree.

## INTEGRATION REQUESTS
1. **config-registry — partner credential lookup contract.** Need a read endpoint to resolve full
   partner credentials by API key (partner_id, hmac_secret reference, ip_cidr_ranges, partner_type,
   rate_quote_ttl_seconds, mtls_cert_fingerprint), analogous to the existing
   `GET /v1/admin/partners/{partnerCode}/ip-allowlist`. The gateway has coded to a
   `PartnerCredentialService` interface with a config-backed fallback; a `RestPartnerCredentialService`
   can be added behind `gateway.partner-credentials.source=rest` once this contract exists. (No
   config-registry edits made from this worktree.)
2. **Redis-backed `NonceStore` and `RateLimitStore`.** Both ports default to in-process
   implementations and select a `redis` backend via `gateway.replay-protection.store=redis` /
   `gateway.rate-limit.store=redis`. The Redis (`ReactiveStringRedisTemplate`, atomic `SET NX EX`
   for nonce; INCR+EXPIRE for rate-limit window) implementations should be added when a shared
   Redis is provisioned, so limits are enforced cluster-wide rather than per-pod.
3. **Confirm 429 `RATE_LIMITED` + `Retry-After`/`X-RateLimit-*` envelope** against the partner-facing
   OpenAPI spec (8.9) once the cert kit / sandbox tickets land, so the response headers match the
   published contract.

## Remaining (top items)
- Live Redis-backed `NonceStore`/`RateLimitStore` (gated on shared Redis — see IR #2).
- `RestPartnerCredentialService` (gated on config-registry contract — see IR #1).
- OpenAPI/sandbox/cert kit (WBS 8.9/8.10) — externally gated.
