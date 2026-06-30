# kyb-adapter — CHANGELOG

All notable changes to the `services/kyb-adapter` module.

## [Unreleased] — 2026-06-30 (agent/kyb-adapter)

### Added — full KYB verification orchestration (UC-09-02)

Built the genuinely-missing KYB orchestration on top of the existing thin
sanctions-screening passthrough: a real multi-check verification run with
persistence, idempotency, and PASS/FAIL/MANUAL_REVIEW decisioning, behind
config-gated provider ports (real KFTC/Octa integration remains externally
blocked).

- **Business-registration verification port** — `BusinessRegistrationVerifier`
  (the KFTC 사업자등록 진위확인 leg), with `BizRegResult` /
  `BizRegStatus {VERIFIED, NOT_FOUND, MISMATCH, SKIPPED}`.
  - `StubBusinessRegistrationVerifier` — deterministic in-process default
    (`gmepay.kyb.biz-reg.provider=stub`); trigger tokens `BIZREG_NOTFOUND` /
    `BIZREG_MISMATCH` stage outcomes; blank tax id → `SKIPPED`.
  - `KftcBusinessRegistrationVerifier` — production placeholder
    (`gmepay.kyb.biz-reg.provider=kftc`), fails fast until the KFTC certificate
    lands (mirrors config-registry's `KftcVerificationAdapter`).
- **Orchestration service** — `KybVerificationService` folds sanctions/PEP
  screening (lib-kyb `KybProvider`) + business-registration verification +
  document-completeness into one `KybDecision`:
  - `FAIL` ← screening HIT or registration NOT_FOUND (outrank review);
  - `MANUAL_REVIEW` ← fuzzy match, registration MISMATCH/SKIPPED, or any
    required onboarding document missing;
  - `PASS` ← clean screen + verified registration + complete documents.
- **Adapter-side persistence** — `kyb_screening` table (Flyway `V001`,
  Postgres-compatible / H2 PostgreSQL mode), JPA `KybScreeningRecord` +
  `KybScreeningRepository`. The adapter now keeps its own durable run log
  (distinct from config-registry's `partner_kyb`) keyed by the deterministic
  `providerRef`.
- **Idempotent re-screen** — re-verifying an unchanged subject replays the
  stored run (no second vendor call, no duplicate event); `force=true` re-runs
  and replaces the row.
- **Idempotent verification API** the onboarding wizard can call:
  - `POST /v1/kyb/verify` (`KybVerificationRequest` → `KybVerificationResult`);
  - `GET /v1/kyb/result/{providerRef}` retrieval (the backlog
    `GET /v1/kyb/result/{vendorRef}` contract); 404 on unknown ref.
- **Event fan-out** — new `KybVerificationEvent` on topic
  `gmepay.kyb.verification` (collapsed decision), published best-effort on fresh
  runs only. Existing `gmepay.kyb.screening` passthrough unchanged.
- **Build** — added `spring-boot-starter-data-jpa`, `flyway-core`,
  `flyway-database-postgresql`, `postgresql`, H2; datasource + biz-reg provider
  config in `application.properties`.

### Tests
- `StubBusinessRegistrationVerifierTest` (5) — verdict/determinism.
- `KybVerificationServiceTest` (10) — PASS/FAIL/MANUAL_REVIEW decisioning,
  document completeness, idempotent replay, forced re-run, retrieval.
- `VerificationControllerTest` (3) — verify + result endpoints, 400/404 paths.
- Existing `ScreeningControllerTest` (5) unchanged and green.
- `./gradlew :services:kyb-adapter:test` → 23 tests, 0 failures.

### Externally blocked (built to the seam, gated stubs active)
- Real **Octa Solution** KYB/sanctions provider (ADR-014) — `OctaKybAdapter`
  fails fast pending sandbox credentials.
- Real **KFTC** business-registration channel — `KftcBusinessRegistrationVerifier`
  fails fast pending the production certificate.
