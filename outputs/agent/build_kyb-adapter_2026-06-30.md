> 작업: kyb-adapter backlog 완성 / 출처: agent

# kyb-adapter build report — 2026-06-30

Branch: `agent/kyb-adapter` · Worktree: `D:/GMEPay+/wt/kyb-adapter`

## Build status
`./gradlew :services:kyb-adapter:test` → **BUILD SUCCESSFUL**, 23 tests, 0 failures, 0 skipped.
(5 pre-existing screening tests + 18 new.)

## Starting state (actual code, not stale backlog %)
The module already had: stub/Octa `KybProvider` wiring (`KybProviderConfig`),
`POST /v1/kyb/screen` + `/health`, sanctions event fan-out
(`gmepay.kyb.screening`), and the FROZEN lib-kyb port. It was a thin
**sanctions-screening passthrough with no persistence, no idempotency, no
business-registration verification, no decisioning, and no result retrieval** —
exactly the UC-09-02 gap ("stubs minting non-verifiable results").

## Tickets / gaps implemented this run
1. **KYB screening orchestration** (`KybVerificationService`) — folds sanctions
   screening + business-registration verification + document-completeness into a
   single `PASS / FAIL / MANUAL_REVIEW` decision with explicit reason strings.
   FAIL (HIT / registration NOT_FOUND) outranks MANUAL_REVIEW (fuzzy match,
   MISMATCH, SKIPPED biz-reg, missing docs).
2. **Business-registration (KFTC) verification behind a provider port** —
   `BusinessRegistrationVerifier` + deterministic `StubBusinessRegistrationVerifier`
   (default) + fail-fast `KftcBusinessRegistrationVerifier`
   (`gmepay.kyb.biz-reg.provider=kftc`), gated exactly like config-registry's
   `KftcVerificationAdapter`.
3. **Persistence of screening results** — `kyb_screening` table (Flyway V001,
   Postgres/H2), JPA entity + repository. Adapter-side run log distinct from
   config-registry's `partner_kyb`, keyed by the deterministic `providerRef`.
4. **Idempotent re-screen** — repeat verification of an unchanged subject replays
   the stored run (no vendor re-call, no duplicate event); `force=true` re-runs
   and replaces the row.
5. **Idempotent verification API for the onboarding wizard** —
   `POST /v1/kyb/verify` and `GET /v1/kyb/result/{providerRef}` (the backlog's
   `GET /v1/kyb/result/{vendorRef}` contract). Existing `/screen` kept for
   back-compat.
6. **Verification event** — `KybVerificationEvent` on `gmepay.kyb.verification`
   (collapsed decision), published best-effort, fresh-runs-only.
7. **Tests** — verifier determinism, full decisioning matrix, idempotent replay,
   forced re-run, retrieval, controller 200/400/404. H2 test scope.

## Completion estimate
kyb-adapter (excluding externally-blocked vendor calls): **~90%**. The port
seams, orchestration, persistence, idempotency, decisioning, API and events are
complete and green. Remaining items are either externally blocked or
integration-gated (below).

## Externally blocked (built to the seam; gated stubs active)
- **Octa Solution** KYB/sanctions vendor (ADR-014) — `OctaKybAdapter` fails fast
  pending sandbox credentials + API spec.
- **KFTC** 사업자등록 진위확인 channel — `KftcBusinessRegistrationVerifier`
  fails fast pending GME's production certificate (same gating as the KFTC
  account-verification rail in config-registry).

## INTEGRATION REQUESTS
1. **config-registry — call `POST /v1/kyb/verify` from the onboarding wizard
   (step 3) instead of (or in addition to) `POST /v1/kyb/screen`.** The new
   endpoint returns the collapsed `KybVerificationResult` (PASS/FAIL/MANUAL_REVIEW
   + screening + biz-reg + missing docs + `providerRef`). Suggest config-registry
   add a `verify(...)` method to `KybScreeningClient`/`RestKybClient` mirroring its
   existing `screen(...)`. Request: confirm the `KybVerificationRequest` wire shape
   (subject + `suppliedDocuments` + `force`) and the required-document roster
   (`BUSINESS_REGISTRATION`, `AOA`, `UBO_DECLARATION`) match the wizard's document
   upload step.
2. **config-registry / reporting-compliance — consume the new
   `gmepay.kyb.verification` Kafka topic** (collapsed decision), alongside the
   existing `gmepay.kyb.screening`. Confirm whether config-registry should persist
   the verification decision onto `partner_kyb` (and whether the `providerRef` is
   the agreed correlation key for `GET /v1/kyb/result/{providerRef}`).
3. **lib-kyb (FROZEN) — consider promoting `KybVerificationRequest` /
   `KybVerificationResult` / `KybDecision` / `BusinessRegistrationVerifier` into
   the shared port library** so config-registry binds to the same types rather than
   re-declaring them. Implemented locally in `services/kyb-adapter` for now to
   respect the freeze.

## Remaining (next run, not blocking green)
- Daily rescreen scheduler (the `@Scheduled` rescreen named in the service
  javadoc) over persisted `kyb_screening` rows.
- Persist + surface the full hit detail on retrieval (currently only hit COUNT is
  stored; `GET /result` returns an empty hit list — fine for a verdict replay).
- Wire `runFullKyb` (lib-kyb registry/license/UBO checks) into the orchestration
  once a vendor provides real registry data.
