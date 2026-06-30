> 작업: config-registry backlog 완성 / 출처: agent

# config-registry — build report (2026-06-30)

## Build status
- `./gradlew :services:config-registry:test` — **GREEN** (BUILD SUCCESSFUL, full module suite).
- Baseline (before any change) also green; kept green throughout.

## Headline finding
The published backlog (`Documentation/services_backlog/config-registry.md`, the
V201-/V010-series aspirational design in package `com.gme.pay.config`) does **not**
match the service that was actually built. The real service lives under
`com.gme.pay.registry` with migrations **V001–V034** and is far more complete than the
backlog or the stale WBS audit (`docs/WBS_STATUS.md` says 4% — that audit predates the
bulk of this service). The backlog tickets are therefore not actionable as written;
the gap-plan doc named in the brief
(`Documentation/7. PRD Use-Case Audit & Gap Plan.md`) is not present in this worktree.
I worked instead against the live PRD-derived priorities and the in-repo
`docs/INTER_SERVICE_CONTRACTS.md`.

## P0 gap-plan items — verified state
1. **Scheme-catalog `GET /v1/schemes`** — already DONE (`SchemeCatalogController` +
   `SchemeCatalogService`, with `SchemeCatalogControllerTest`).
2. **Partner-type lookup for payment-executor** — already DONE
   (`GET /v1/partners/{id}` returns `PartnerView.type` = LOCAL/OVERSEAS).
3. **Rules / scheme / onboarding endpoints complete & consumable** — the 8-slice
   onboarding wizard, per-partner rules (`/v1/partners/{id}/rules` + lib-domain margin
   invariant), per-partner scheme enablements (`/v1/admin/partners/.../step-7/schemes`),
   change-request 4-eyes, hash-chained audit, commission split — all present and tested.

## Ticket completed THIS run
**Scheme roster drift fix** (a real, user-facing consumability defect the gap plan's
"ensure scheme endpoints are complete and consumable" directly targets):
- `GET /v1/schemes` advertised `QPAY` / `SBP` / `PROMPTPAY`, none of which the V022
  `ck_partner_scheme_scheme` DB CHECK or the Slice-7 enablement endpoint accept, and it
  omitted `BAKONG` / `NAPAS_247` / `FAST_SG` which ARE enableable. The Admin UI scheme
  picker is fed by this catalog, so picking a phantom scheme failed step-7 save with 400.
- Fix: catalog roster now equals the platform-wide roster already encoded in V022 and in
  the BFF `StubConfigRegistryClient` (`ZEROPAY, BAKONG, KHQR, NAPAS_247, PROMPT_PAY,
  FAST_SG, QRIS`). `PartnerSchemeService.SCHEMES` now DERIVES from
  `SchemeCatalogService.schemeIds()` so the two rosters cannot drift again.
- Tests: new `SchemeCatalogServiceTest` (4 tests, incl. catalog == V022 DB CHECK parsed
  from the migration); `SchemeCatalogControllerTest` updated to 7 schemes.
- Wire shape (BFF `SchemeSummary` field names) unchanged — only the data rows changed.
- `services/config-registry/CHANGELOG.md` created.

## Completion estimate
The service was already ~95% against its live functional scope before this run; the P0
gap-plan items were all satisfied. This run closed the one genuine open consumability
defect I could find. Remaining items below are deliberately NOT built (speculative /
frozen-module / out-of-scope).

## INTEGRATION REQUESTS
1. **rate-fx / treasury-rate source-of-truth divergence.** `docs/INTER_SERVICE_CONTRACTS.md`
   lists config-registry as exposing `/v1/treasury-rates` and rate-fx as consuming
   treasury rates from it (sync). In reality rate-fx sources cost rates from its OWN
   `RateSnapshotEntity` store (XE scheduler upserts), and config-registry has NO
   treasury-rate table/entity/endpoint. Either (a) update the contract doc to record
   rate-fx as the rate owner, or (b) decide config-registry should own treasury rates
   and have rate-fx consume them — but do NOT build a config-registry treasury endpoint
   speculatively (would create a second, unused source of truth). I did not build it.
2. **BFF scheme-catalog stub vs live drift (frozen module, no edit made).** The BFF
   `StubConfigRegistryClient` (`services/ops-partner-bff/...:1686`) already hard-codes the
   correct V022 roster, so the live `GET /v1/schemes` now agrees with it. No BFF change is
   required, but if the BFF ever switches its scheme picker to the live client, confirm it
   reads `schemeId/name/country/currency/mode/status` by field name (it does today).

## Remaining (top, all intentionally deferred)
1. Treasury-rate exposure — gated on INTEGRATION REQUEST #1 (ownership decision).
2. Backlog/WBS-doc reconciliation — the V201/V010-series backlog and the 4%
   WBS_STATUS entry are stale vs the shipped `com.gme.pay.registry` service; a doc-owner
   should re-baseline them (out of scope for a build agent; no code impact).
3. Outbox/Kafka event dispatch for config changes (backlog 2.5-T22) — audit events are
   published in-process via `AuditLogService`/`AuditPublisher`; a Kafka transport behind
   the existing interface is Phase-2 and unbuilt.
