# auth-identity — Changelog

All notable changes to the auth-identity service. Newest first.

## 2026-06-30 — JWT token surface + DB-backed credential lookup + key rotation

### Added
- **HS256 service-to-service token issuance/verify endpoint** (`/internal/auth/token`):
  - `POST /internal/auth/token/issue` — mint a short-lived signed capability
    token for an internal subject + claims (api-gateway claim resolver, ops BFF,
    config-registry). `Bearer` token type, configurable per-request TTL clamped to
    `gme.auth.jwt.max-token-ttl-seconds` (default 3600s).
  - `POST /internal/auth/token/verify` — validate signature + expiry, returning
    decoded `sub`/`jti`/`exp`; distinguishes `INVALID_TOKEN` vs `EXPIRED_TOKEN`.
  - Activates the previously-unused `JwtHelper` bean via new `JwtTokenService`.
  - NOT a human-operator session issuer — operator sessions remain Keycloak-owned
    (ADR-011); endpoints live on the `/internal/auth/**` machine surface.
- **`JwtHelper.verifyDetailed(...)`** — reports VALID / EXPIRED / INVALID so callers
  map precise error codes; plus an `issue(subject, claims, ttlSeconds)` overload and
  null-claims tolerance. Existing `verify(...)` behaviour unchanged.
- **DB-backed partner credential lookup** the api-gateway can consume:
  `POST /internal/auth/keys/resolve` → `{found, active, partnerId}` resolved from
  the local `api_keys` table. Reports key existence + lifecycle (ACTIVE + not expired)
  and the owning partner id, and never returns secret material (only the salted
  PBKDF2 hash is stored; the plaintext HMAC secret is unrecoverable by design).
- **Key rotation**: `POST /internal/auth/keys/rotate` — atomically revokes a
  principal's currently-ACTIVE keys and issues a fresh replacement (returns the new
  one-time plaintext); degrades to plain issue when the principal does not yet exist.

### Tests
- `JwtTokenServiceTest` (8, pure unit): issue→verify accept; tampered-signature reject;
  wrong-secret reject; expired-token reject; garbage/null reject; TTL clamp-to-max;
  shorter-TTL honoured; missing-subject 400.
- `ApiKeyIssuanceServiceTest` (+7, `@DataJpaTest`): rotate revokes-then-issues and
  leaves exactly one active key; rotate-with-no-principal behaves like issue; resolve
  found/active/partnerId for issued key; unknown-key not-found; blank/null not-found;
  revoked-key found-but-inactive; expired-key found-but-inactive.

### Notes
- HMAC signature verification (`POST /internal/auth/verify`) is unchanged and remains
  the canonical entry point; the new `keys/resolve` lookup complements it for key
  identity/lifecycle validation without exposing the HMAC secret.
